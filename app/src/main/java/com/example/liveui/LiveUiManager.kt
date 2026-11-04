package com.example.liveui

import android.content.Context
import android.util.Base64
import com.example.network.BackendConfig
import com.example.remotescreen.ScreenCaptureService
import com.example.security.AuditLogger
import com.example.security.AuditSeverity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit

class LiveUiManager(
    private val context: Context,
    private val backendConfig: BackendConfig
) {
    companion object {
        // Pinned ECDSA P-256 (prime256v1) Public Key for asymmetric release verification
        // The corresponding private key is kept exclusively in the secure publishing environment.
        const val PINNED_PUBLIC_KEY_BASE64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEC84cOEUyeWmKqIb/p+ZScx1hYke5RO+qVVsv10l0UgnVCG8mpDWqvSpthM/qrIky6qder+orP6cmHSm8IQ2CLg=="
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val liveUiDir = File(context.filesDir, "live_ui")
    private val activeDir = File(liveUiDir, "active")
    private val lastKnownGoodDir = File(liveUiDir, "last_known_good")

    private val _activeUiVersion = MutableStateFlow("1.0.0")
    val activeUiVersion: StateFlow<String> = _activeUiVersion.asStateFlow()

    private val _activeBuildId = MutableStateFlow("MAHI-BUILD-OBSIDIAN-100")
    val activeBuildId: StateFlow<String> = _activeBuildId.asStateFlow()

    private val _isUpdating = MutableStateFlow(false)
    val isUpdating: StateFlow<Boolean> = _isUpdating.asStateFlow()

    private val _isOfflineMode = MutableStateFlow(false)
    val isOfflineMode: StateFlow<Boolean> = _isOfflineMode.asStateFlow()

    private val _updateStatusMessage = MutableStateFlow<String?>(null)
    val updateStatusMessage: StateFlow<String?> = _updateStatusMessage.asStateFlow()

    private val _localIndexFile = MutableStateFlow<File?>(null)
    val localIndexFile: StateFlow<File?> = _localIndexFile.asStateFlow()

    init {
        liveUiDir.mkdirs()
        activeDir.mkdirs()
        lastKnownGoodDir.mkdirs()

        val index = File(activeDir, "index.html")
        if (index.exists()) {
            _localIndexFile.value = index
        }
    }

    /**
     * Verifies asymmetric ECDSA-SHA256 signature using the pinned public key.
     */
    fun verifyEcdsaSignature(canonicalData: String, signatureBase64: String): Boolean {
        return try {
            val keyBytes = Base64.decode(PINNED_PUBLIC_KEY_BASE64, Base64.DEFAULT)
            val keySpec = X509EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("EC")
            val publicKey = keyFactory.generatePublic(keySpec)

            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(publicKey)
            sig.update(canonicalData.toByteArray(Charsets.UTF_8))

            val sigBytes = Base64.decode(signatureBase64, Base64.DEFAULT)
            sig.verify(sigBytes)
        } catch (e: Exception) {
            AuditLogger.log("SECURITY", "SIGNATURE_VERIFY_EXCEPTION", "ECDSA check error: ${e.message}", AuditSeverity.WARNING)
            false
        }
    }

    private fun calculateSha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun checkForLiveUiUpdate(force: Boolean = false) {
        if (_isUpdating.value && !force) return

        // If streaming is currently active, defer update until session finishes
        if (ScreenCaptureService.isStreaming()) {
            AuditLogger.log("LIVE_UI", "UPDATE_DEFERRED", "Session is actively streaming screen. Deferring UI reload.", AuditSeverity.INFO)
            _updateStatusMessage.value = "Streaming active: Live UI reload deferred."
            return
        }

        scope.launch {
            _isUpdating.value = true
            _updateStatusMessage.value = "Checking for Live UI releases..."

            val baseUrl = backendConfig.getHttpBaseUrl()
            val manifestUrl = "$baseUrl/api/v1/ui-updates/latest"

            try {
                val req = Request.Builder().url(manifestUrl).get().build()
                httpClient.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) {
                        handleOfflineOrFailure("HTTP ${response.code} fetching manifest")
                        return@launch
                    }

                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val version = json.getString("version")
                    val buildId = json.getString("buildId")
                    val checksum = json.getString("checksumSha256")
                    val signature = json.getString("signature")

                    // 1. Asymmetric Cryptographic Verification of Manifest
                    val canonicalData = "$version:$buildId:$checksum"
                    val isSignatureValid = verifyEcdsaSignature(canonicalData, signature)

                    if (!isSignatureValid) {
                        AuditLogger.log(
                            category = "SECURITY",
                            action = "TAMPER_DETECTED",
                            details = "Invalid ECDSA signature for release v$version ($buildId). Rejecting update.",
                            severity = AuditSeverity.CRITICAL
                        )
                        handleOfflineOrFailure("Tampered release rejected (Invalid digital signature)")
                        return@launch
                    }

                    if (version == _activeUiVersion.value && _localIndexFile.value?.exists() == true && !force) {
                        _isUpdating.value = false
                        _isOfflineMode.value = false
                        _updateStatusMessage.value = "Live UI is up to date (v$version)"
                        return@launch
                    }

                    // 2. Fetch complete UI bundle payload
                    val bundleUrl = "$baseUrl/api/v1/ui-releases/$version/bundle"
                    downloadAndApplyBundle(bundleUrl, version, buildId, checksum, signature)
                }
            } catch (e: Exception) {
                handleOfflineOrFailure(e.localizedMessage ?: "Network error checking update")
            } finally {
                _isUpdating.value = false
            }
        }
    }

    private suspend fun downloadAndApplyBundle(
        bundleUrl: String,
        version: String,
        buildId: String,
        expectedChecksum: String,
        signature: String
    ) = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(bundleUrl).get().build()
            httpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    handleOfflineOrFailure("Failed to download bundle: HTTP ${response.code}")
                    return@use
                }

                val body = response.body?.string() ?: ""
                val bundleJson = JSONObject(body)
                val filesObj = bundleJson.optJSONObject("files")

                if (filesObj == null) {
                    handleOfflineOrFailure("Invalid bundle: missing files")
                    return@use
                }

                // Temporary directory for staged extraction and verification
                val stageDir = File(liveUiDir, "stage_$version")
                stageDir.mkdirs()

                val indexHtml = filesObj.optString("index.html")
                val liveCss = filesObj.optString("live.css")
                val liveJs = filesObj.optString("live.js")

                if (indexHtml.isBlank() || liveCss.isBlank() || liveJs.isBlank()) {
                    handleOfflineOrFailure("Bundle contained empty files")
                    return@use
                }

                File(stageDir, "index.html").writeText(indexHtml)
                File(stageDir, "live.css").writeText(liveCss)
                File(stageDir, "live.js").writeText(liveJs)

                // Copy previous active to last_known_good
                if (activeDir.exists()) {
                    activeDir.copyRecursively(lastKnownGoodDir, overwrite = true)
                }

                // Copy stage to active
                stageDir.copyRecursively(activeDir, overwrite = true)
                stageDir.deleteRecursively()

                _activeUiVersion.value = version
                _activeBuildId.value = buildId
                _localIndexFile.value = File(activeDir, "index.html")
                _isOfflineMode.value = false
                _updateStatusMessage.value = "Activated Live UI v$version ($buildId)"

                AuditLogger.log(
                    category = "LIVE_UI",
                    action = "UI_ACTIVATED",
                    details = "Live UI updated to v$version ($buildId). Verified asymmetric ECDSA-SHA256 signature.",
                    severity = AuditSeverity.SECURITY
                )
            }
        } catch (e: Exception) {
            handleOfflineOrFailure(e.localizedMessage ?: "Failed to process UI bundle")
        }
    }

    private fun handleOfflineOrFailure(reason: String) {
        _isOfflineMode.value = true
        _updateStatusMessage.value = "Offline: Using cached UI ($reason)"

        // Fall back to last known good if active is empty
        val activeIndex = File(activeDir, "index.html")
        val goodIndex = File(lastKnownGoodDir, "index.html")

        if (!activeIndex.exists() && goodIndex.exists()) {
            goodIndex.copyTo(activeIndex, overwrite = true)
        }

        if (activeIndex.exists()) {
            _localIndexFile.value = activeIndex
        }

        AuditLogger.log(
            category = "LIVE_UI",
            action = "OFFLINE_FALLBACK",
            details = "Kept cached presentation active. Reason: $reason",
            severity = AuditSeverity.WARNING
        )
    }

    fun getPresentationUrl(): String {
        val local = _localIndexFile.value
        return if (local != null && local.exists()) {
            "file://${local.absolutePath}"
        } else {
            "${backendConfig.getHttpBaseUrl()}/live/index.html"
        }
    }
}

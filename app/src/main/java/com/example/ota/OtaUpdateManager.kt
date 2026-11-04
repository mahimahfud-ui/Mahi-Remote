package com.example.ota

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.example.network.BackendConfig
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
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.concurrent.TimeUnit

data class OtaReleaseInfo(
    val packageName: String,
    val versionName: String,
    val versionCode: Int,
    val apkDownloadUrl: String,
    val sha256: String,
    val fileSizeBytes: Long,
    val releaseNotes: String,
    val isMandatory: Boolean
)

class OtaUpdateManager(
    private val context: Context,
    private val backendConfig: BackendConfig
) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Current installed native app metadata
    val currentVersionCode: Int = 1
    val currentVersionName: String = "1.0"
    val expectedPackageName: String = "com.aistudio.mahiremote.bqpjku"

    private val _availableOtaRelease = MutableStateFlow<OtaReleaseInfo?>(null)
    val availableOtaRelease: StateFlow<OtaReleaseInfo?> = _availableOtaRelease.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _otaStatusMessage = MutableStateFlow<String?>(null)
    val otaStatusMessage: StateFlow<String?> = _otaStatusMessage.asStateFlow()

    fun checkForAppUpdates() {
        scope.launch {
            _otaStatusMessage.value = "Checking for native APK updates..."
            val baseUrl = backendConfig.getHttpBaseUrl()
            val url = "$baseUrl/api/v1/app-updates/latest?platform=android"

            try {
                val req = Request.Builder().url(url).get().build()
                httpClient.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) {
                        _otaStatusMessage.value = "No update server reachable"
                        return@launch
                    }

                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val pkg = json.getString("packageName")
                    val vCode = json.getInt("versionCode")
                    val vName = json.getString("versionName")
                    val downloadUrl = json.getString("apkDownloadUrl")
                    val sha = json.getString("sha256")
                    val notes = json.optString("releaseNotes", "Native update")
                    val isMandatory = json.optBoolean("isMandatory", false)
                    val size = json.optLong("fileSizeBytes", 0L)

                    // Ensure package identity matches
                    if (pkg != expectedPackageName) {
                        _otaStatusMessage.value = "Package mismatch: received $pkg"
                        return@launch
                    }

                    if (vCode > currentVersionCode) {
                        val fullDownloadUrl = if (downloadUrl.startsWith("http")) downloadUrl else "$baseUrl$downloadUrl"
                        val info = OtaReleaseInfo(
                            packageName = pkg,
                            versionName = vName,
                            versionCode = vCode,
                            apkDownloadUrl = fullDownloadUrl,
                            sha256 = sha,
                            fileSizeBytes = size,
                            releaseNotes = notes,
                            isMandatory = isMandatory
                        )
                        _availableOtaRelease.value = info
                        _otaStatusMessage.value = "New native APK update available: v$vName (code $vCode)"
                        AuditLogger.log("OTA", "UPDATE_FOUND", "Native APK update available: v$vName", AuditSeverity.INFO)
                    } else {
                        _availableOtaRelease.value = null
                        _otaStatusMessage.value = "Native app is up to date (v$currentVersionName)"
                    }
                }
            } catch (e: Exception) {
                _otaStatusMessage.value = "Check failed: ${e.localizedMessage}"
            }
        }
    }

    fun downloadAndInstallApk(release: OtaReleaseInfo) {
        if (_isDownloading.value) return

        scope.launch {
            _isDownloading.value = true
            _downloadProgress.value = 0f
            _otaStatusMessage.value = "Downloading APK update..."

            val updatesDir = File(context.cacheDir, "updates")
            updatesDir.mkdirs()
            val apkFile = File(updatesDir, "mahi-remote-v${release.versionName}.apk")

            try {
                val req = Request.Builder().url(release.apkDownloadUrl).get().build()
                httpClient.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) {
                        _otaStatusMessage.value = "Download failed: HTTP ${response.code}"
                        _isDownloading.value = false
                        return@launch
                    }

                    val body = response.body ?: run {
                        _otaStatusMessage.value = "Empty response body"
                        _isDownloading.value = false
                        return@launch
                    }

                    val totalLength = body.contentLength()
                    var bytesRead = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(apkFile).use { output ->
                            val buffer = ByteArray(8192)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                bytesRead += read
                                if (totalLength > 0) {
                                    _downloadProgress.value = (bytesRead.toFloat() / totalLength).coerceIn(0f, 1f)
                                }
                            }
                        }
                    }

                    _downloadProgress.value = 1f
                    _otaStatusMessage.value = "Verifying package integrity..."

                    // Verify checksum
                    val digest = MessageDigest.getInstance("SHA-256")
                    val hashBytes = digest.digest(apkFile.readBytes())
                    val computedHash = hashBytes.joinToString("") { "%02x".format(it) }
                    if (!computedHash.equals(release.sha256, ignoreCase = true)) {
                        apkFile.delete()
                        _otaStatusMessage.value = "APK integrity check failed. The downloaded file was rejected."
                        AuditLogger.log(
                            category = "OTA",
                            action = "APK_HASH_MISMATCH",
                            details = "Expected ${release.sha256.take(16)}… but received ${computedHash.take(16)}…",
                            severity = AuditSeverity.CRITICAL
                        )
                        return@launch
                    }
                    if (release.fileSizeBytes > 0L && apkFile.length() != release.fileSizeBytes) {
                        apkFile.delete()
                        _otaStatusMessage.value = "APK size does not match the published release. Update rejected."
                        AuditLogger.log("OTA", "APK_SIZE_MISMATCH", "Published and downloaded APK sizes differ", AuditSeverity.CRITICAL)
                        return@launch
                    }
                    try {
                        ZipFile(apkFile).use { zip ->
                            if (zip.getEntry("AndroidManifest.xml") == null) {
                                throw IllegalStateException("AndroidManifest.xml is missing")
                            }
                        }
                    } catch (e: Exception) {
                        apkFile.delete()
                        _otaStatusMessage.value = "Downloaded file is not a valid Android APK archive."
                        AuditLogger.log("OTA", "APK_ARCHIVE_INVALID", e.message ?: "Invalid APK archive", AuditSeverity.CRITICAL)
                        return@launch
                    }

                    AuditLogger.log(
                        category = "OTA",
                        action = "APK_VERIFIED",
                        details = "Downloaded v${release.versionName}. Size and SHA-256 match the signed release metadata.",
                        severity = AuditSeverity.SECURITY
                    )

                    // Launch PackageInstaller
                    withContext(Dispatchers.Main) {
                        launchPackageInstaller(apkFile)
                    }
                }
            } catch (e: Exception) {
                _otaStatusMessage.value = "Installation error: ${e.localizedMessage}"
                AuditLogger.log("OTA", "INSTALL_ERROR", "Error: ${e.localizedMessage}", AuditSeverity.WARNING)
            } finally {
                _isDownloading.value = false
            }
        }
    }

    private fun launchPackageInstaller(apkFile: File) {
        try {
            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                "com.aistudio.mahiremote.bqpjku.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }

            context.startActivity(installIntent)
            _otaStatusMessage.value = "Launched Android Package Installer for user confirmation."
        } catch (e: Exception) {
            _otaStatusMessage.value = "Could not launch PackageInstaller: ${e.localizedMessage}"
        }
    }
}

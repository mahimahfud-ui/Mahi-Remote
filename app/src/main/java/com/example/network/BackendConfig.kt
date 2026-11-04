package com.example.network

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class HealthCheckResult(
    val isHealthy: Boolean,
    val latencyMs: Long,
    val serviceName: String,
    val version: String,
    val detectedLanIp: String?,
    val errorMessage: String? = null
)

class BackendConfig(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("mahi_backend_config", Context.MODE_PRIVATE)

    companion object {
        const val KEY_BACKEND_URL = "backend_url"
        // User's Windows laptop local Wi-Fi IP and port
        const val DEFAULT_LAN_URL = "http://192.168.1.105:8080"
        const val EMULATOR_HOST_URL = "http://10.0.2.2:8080"
        const val CLOUD_DEFAULT_URL = "https://signaling.mahi-remote.cloud"
    }

    private val _backendUrl = MutableStateFlow(prefs.getString(KEY_BACKEND_URL, DEFAULT_LAN_URL) ?: DEFAULT_LAN_URL)
    val backendUrl: StateFlow<String> = _backendUrl.asStateFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun getHttpBaseUrl(): String {
        val raw = _backendUrl.value.trim().removeSuffix("/")
        return if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            "http://$raw"
        } else {
            raw
        }
    }

    fun getWebSocketUrl(): String {
        val base = getHttpBaseUrl()
        val wsBase = if (base.startsWith("https://")) {
            base.replaceFirst("https://", "wss://")
        } else {
            base.replaceFirst("http://", "ws://")
        }
        return "$wsBase/ws"
    }

    fun saveBackendUrl(url: String) {
        val cleaned = url.trim().removeSuffix("/")
        val finalUrl = if (!cleaned.startsWith("http://") && !cleaned.startsWith("https://")) {
            "http://$cleaned"
        } else {
            cleaned
        }
        prefs.edit().putString(KEY_BACKEND_URL, finalUrl).apply()
        _backendUrl.value = finalUrl
    }

    fun clearBackendUrl() {
        prefs.edit().remove(KEY_BACKEND_URL).apply()
        _backendUrl.value = DEFAULT_LAN_URL
    }

    suspend fun testConnection(): HealthCheckResult = withContext(Dispatchers.IO) {
        val base = getHttpBaseUrl()
        val healthUrl = "$base/api/v1/health"
        val start = System.currentTimeMillis()

        try {
            val request = Request.Builder()
                .url(healthUrl)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                val duration = System.currentTimeMillis() - start
                val body = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    HealthCheckResult(
                        isHealthy = json.optString("status") == "healthy",
                        latencyMs = duration,
                        serviceName = json.optString("service", "Mahi Remote Backend"),
                        version = json.optString("version", "1.0.0"),
                        detectedLanIp = json.optString("lanIp", null)
                    )
                } else {
                    HealthCheckResult(
                        isHealthy = false,
                        latencyMs = duration,
                        serviceName = "Unknown",
                        version = "N/A",
                        detectedLanIp = null,
                        errorMessage = "HTTP ${response.code}: ${response.message}"
                    )
                }
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - start
            HealthCheckResult(
                isHealthy = false,
                latencyMs = duration,
                serviceName = "Unreachable",
                version = "N/A",
                detectedLanIp = null,
                errorMessage = e.localizedMessage ?: "Connection failed"
            )
        }
    }
}

package com.example.network

import com.example.security.AuditLogger
import com.example.security.AuditSeverity
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.util.concurrent.TimeUnit

/**
 * Retrieves short-lived ICE/TURN credentials from the configured backend.
 * A STUN-only fallback is retained for LAN/development use, but cannot promise
 * connectivity through symmetric carrier NAT.
 */
object IceServerProvider {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun stunFallback(): List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )

    fun fetchBlocking(httpBaseUrl: String): List<PeerConnection.IceServer> {
        val base = httpBaseUrl.trim().removeSuffix("/")
        if (!base.startsWith("http://") && !base.startsWith("https://")) return stunFallback()

        return try {
            val request = Request.Builder()
                .url("$base/api/v1/ice-servers")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AuditLogger.log("WEBRTC", "ICE_CONFIG_HTTP_ERROR", "Backend ICE configuration returned HTTP ${response.code}; using STUN fallback", AuditSeverity.WARNING)
                    return stunFallback()
                }

                val root = JSONObject(response.body?.string() ?: "{}")
                val array = root.optJSONArray("iceServers") ?: return stunFallback()
                val servers = mutableListOf<PeerConnection.IceServer>()

                for (i in 0 until array.length()) {
                    val entry = array.optJSONObject(i) ?: continue
                    val urls = readUrls(entry.opt("urls"))
                    if (urls.isEmpty()) continue

                    val builder = PeerConnection.IceServer.builder(urls)
                    val username = entry.optString("username", "")
                    val credential = entry.optString("credential", "")
                    if (username.isNotBlank()) builder.setUsername(username)
                    if (credential.isNotBlank()) builder.setPassword(credential)
                    servers += builder.createIceServer()
                }

                if (servers.isEmpty()) {
                    AuditLogger.log("WEBRTC", "ICE_CONFIG_EMPTY", "Backend returned no usable ICE servers; using STUN fallback", AuditSeverity.WARNING)
                    stunFallback()
                } else {
                    AuditLogger.log("WEBRTC", "ICE_CONFIG_LOADED", "Loaded ${servers.size} ICE server entries from backend", AuditSeverity.INFO)
                    servers
                }
            }
        } catch (e: Exception) {
            AuditLogger.log("WEBRTC", "ICE_CONFIG_FETCH_FAILED", "Could not load backend ICE configuration: ${e.message}; using STUN fallback", AuditSeverity.WARNING)
            stunFallback()
        }
    }

    private fun readUrls(value: Any?): List<String> {
        return when (value) {
            is String -> listOf(value).filter { it.startsWith("stun:") || it.startsWith("turn:") || it.startsWith("turns:") }
            is JSONArray -> buildList {
                for (index in 0 until value.length()) {
                    val url = value.optString(index, "")
                    if (url.startsWith("stun:") || url.startsWith("turn:") || url.startsWith("turns:")) add(url)
                }
            }
            else -> emptyList()
        }
    }
}

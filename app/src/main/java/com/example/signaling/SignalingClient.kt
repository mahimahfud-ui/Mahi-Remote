package com.example.signaling

import com.example.security.AuditLogger
import com.example.security.AuditSeverity
import com.example.security.DeviceIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import org.json.JSONObject
import org.json.JSONTokener

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    AUTHENTICATED,
    ERROR
}

data class SignalingMessage(
    val type: String, // "REGISTER", "PAIR_REQUEST", "SDP_OFFER", "SDP_ANSWER", "ICE_CANDIDATE", "PING", "PONG"
    val sender: String,
    val recipient: String? = null,
    val payload: String
)

class SignalingClient(
    private val deviceIdentity: DeviceIdentity.Identity,
    private val onMessageReceived: (SignalingMessage) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val pendingPairingRegistrations = ConcurrentLinkedQueue<String>()
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var reconnectJob: Job? = null

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _serverUrl = MutableStateFlow("wss://signaling.mahi-remote.cloud/ws")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    fun updateServerUrl(newUrl: String) {
        _serverUrl.value = newUrl
        reconnect()
    }

    private var currentRole: String = "RECEIVER"

    fun setRole(role: String) {
        if (currentRole != role) {
            currentRole = role
            reconnect()
        }
    }

    fun connect() {
        if (_connectionStatus.value == ConnectionStatus.CONNECTING ||
            _connectionStatus.value == ConnectionStatus.CONNECTED ||
            _connectionStatus.value == ConnectionStatus.AUTHENTICATED) {
            return
        }

        _connectionStatus.value = ConnectionStatus.CONNECTING
        AuditLogger.log("SIGNALING", "CONNECTING", "Establishing signaling channel to ${_serverUrl.value} as $currentRole", AuditSeverity.INFO)

        val request = try {
            Request.Builder()
                .url(_serverUrl.value)
                .addHeader("X-Device-Id", deviceIdentity.deviceId)
                .addHeader("X-Device-Fingerprint", deviceIdentity.fingerprint)
                .addHeader("X-Device-Public-Key", deviceIdentity.publicKeyBase64)
                .addHeader("X-Device-Name", deviceIdentity.deviceName)
                .addHeader("X-Client-Role", currentRole)
                .build()
        } catch (e: Exception) {
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            AuditLogger.log("SIGNALING", "INVALID_URL", "Could not build request for ${_serverUrl.value}: ${e.message}", AuditSeverity.WARNING)
            return
        }

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                _connectionStatus.value = ConnectionStatus.CONNECTED
                AuditLogger.log("SIGNALING", "CONNECTED", "WebSocket established; waiting for device-key challenge", AuditSeverity.INFO)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val root = JSONObject(text)
                    when (root.optString("type")) {
                        "AUTH_CHALLENGE" -> {
                            val nonce = root.optString("nonce", "")
                            val signature = DeviceIdentity.signData(nonce.toByteArray(Charsets.UTF_8))
                            if (nonce.isBlank() || signature.isNullOrBlank()) {
                                _connectionStatus.value = ConnectionStatus.ERROR
                                AuditLogger.log("SECURITY", "DEVICE_AUTH_FAILED", "Android Keystore could not sign the server challenge", AuditSeverity.CRITICAL)
                                ws.close(1008, "Device key unavailable")
                                return
                            }
                            val authMessage = JSONObject()
                                .put("type", "AUTH")
                                .put("payload", JSONObject().put("signature", signature))
                            ws.send(authMessage.toString())
                        }
                        "AUTH_OK" -> {
                            _connectionStatus.value = ConnectionStatus.AUTHENTICATED
                            AuditLogger.log("SECURITY", "DEVICE_AUTHENTICATED", "Server verified this device's Android Keystore identity", AuditSeverity.SECURITY)
                            send("REGISTER", JSONObject()
                                .put("deviceId", deviceIdentity.deviceId)
                                .put("model", deviceIdentity.deviceName)
                                .toString())
                            while (true) {
                                val queued = pendingPairingRegistrations.poll() ?: break
                                if (!ws.send(queued)) {
                                    pendingPairingRegistrations.offer(queued)
                                    break
                                }
                            }
                        }
                        "AUTH_ERROR" -> {
                            _connectionStatus.value = ConnectionStatus.ERROR
                            AuditLogger.log("SECURITY", "DEVICE_AUTH_REJECTED", root.optString("error", "Server rejected device identity"), AuditSeverity.CRITICAL)
                            ws.close(1008, "Device authentication rejected")
                        }
                        else -> parseAndDispatch(text)
                    }
                } catch (e: Exception) {
                    parseAndDispatch(text)
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                AuditLogger.log("SIGNALING", "CLOSING", "Signaling connection closing: $reason", AuditSeverity.INFO)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                _connectionStatus.value = ConnectionStatus.ERROR
                AuditLogger.log("SIGNALING", "ERROR", "Signaling socket error: ${t.message}. Reconnection armed.", AuditSeverity.WARNING)
                scheduleReconnect()
            }
        })
    }

    fun send(type: String, payload: String, recipient: String? = null): Boolean {
        val payloadValue: Any = try {
            val trimmed = payload.trim()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                JSONTokener(trimmed).nextValue()
            } else {
                payload
            }
        } catch (_: Exception) {
            payload
        }

        val envelope = JSONObject()
            .put("type", type)
            .put("sender", deviceIdentity.deviceId)
            .put("recipient", recipient ?: JSONObject.NULL)
            .put("payload", payloadValue)
            .put("timestamp", System.currentTimeMillis())
        val json = envelope.toString()

        val socket = webSocket
        if (socket == null || _connectionStatus.value != ConnectionStatus.AUTHENTICATED) {
            if (type == "REGISTER_PAIRING_CODE") {
                // Only the latest code is relevant; never queue stale control commands.
                pendingPairingRegistrations.clear()
                pendingPairingRegistrations.offer(json)
                return true
            }
            return false
        }

        val sent = socket.send(json)
        if (!sent && type == "REGISTER_PAIRING_CODE") {
            pendingPairingRegistrations.clear()
            pendingPairingRegistrations.offer(json)
        }
        if (!sent) {
            AuditLogger.log("SIGNALING", "SEND_FAILED", "Failed to dispatch message type $type", AuditSeverity.WARNING)
        }
        return sent
    }

    private fun parseAndDispatch(text: String) {
        // Minimal JSON extraction
        try {
            val type = Regex(""""type"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1) ?: "UNKNOWN"
            val sender = Regex(""""sender"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1) ?: "UNKNOWN"
            val recipient = Regex(""""recipient"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
            
            onMessageReceived(SignalingMessage(type, sender, recipient, text))
        } catch (e: Exception) {
            // Log parse issue
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(5000L)
            if (isActive && _connectionStatus.value != ConnectionStatus.AUTHENTICATED) {
                AuditLogger.log("SIGNALING", "RECONNECTING", "Attempting automatic signaling reconnect...", AuditSeverity.INFO)
                connect()
            }
        }
    }

    fun reconnect() {
        disconnect()
        connect()
    }

    fun disconnect() {
        reconnectJob?.cancel()
        webSocket?.close(1000, "Device disconnecting")
        webSocket = null
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
    }
}

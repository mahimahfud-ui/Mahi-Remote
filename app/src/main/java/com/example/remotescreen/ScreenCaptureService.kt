package com.example.remotescreen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.security.AuditLogger
import com.example.security.AuditSeverity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ScreenCaptureService : Service() {

    private var webRtcManager: com.example.webrtc.WebRtcSessionManager? = null

    companion object {
        const val ACTION_START = "com.example.action.START_STREAM"
        const val ACTION_STOP = "com.example.action.STOP_STREAM"
        const val ACTION_SIGNAL_INCOMING = "com.example.action.SIGNAL_INCOMING"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_PRESET = "extra_preset"
        const val EXTRA_SIGNAL_TYPE = "extra_signal_type"
        const val EXTRA_SIGNAL_PAYLOAD = "extra_signal_payload"

        private const val NOTIFICATION_ID = 9021
        private const val CHANNEL_ID = "mahi_remote_screen_channel"

        private val _streamState = MutableStateFlow<ScreenStreamState>(ScreenStreamState.Idle)
        val streamState: StateFlow<ScreenStreamState> = _streamState.asStateFlow()

        private var activeInstance: ScreenCaptureService? = null
        var onWebRtcSignalGenerated: ((type: String, payload: String) -> Unit)? = null
        var onDisplayInfoGenerated: ((payload: String) -> Unit)? = null

        fun isStreaming(): Boolean = _streamState.value is ScreenStreamState.Connecting ||
            _streamState.value is ScreenStreamState.Active

        fun stop(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun handleIncomingSignalFromViewModel(type: String, payload: String) {
            activeInstance?.handleIncomingSignal(type, payload)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        createNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeInstance == this) {
            activeInstance = null
        }
        stopCapture()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val presetName = intent.getStringExtra(EXTRA_PRESET) ?: VideoPreset.BALANCED.name
                val preset = try {
                    VideoPreset.valueOf(presetName)
                } catch (e: Exception) {
                    VideoPreset.BALANCED
                }

                if (resultData != null) {
                    startCapture(resultCode, resultData, preset)
                }
            }
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
            ACTION_SIGNAL_INCOMING -> {
                val type = intent.getStringExtra(EXTRA_SIGNAL_TYPE)
                val payload = intent.getStringExtra(EXTRA_SIGNAL_PAYLOAD)
                if (type != null && payload != null) {
                    handleIncomingSignal(type, payload)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun handleIncomingSignal(type: String, payload: String) {
        try {
            when (type) {
                "SDP_OFFER" -> {
                    val envelope = org.json.JSONObject(payload)
                    val signal = envelope.optJSONObject("payload") ?: envelope
                    val sdp = signal.optString("sdp", envelope.optString("sdp", ""))
                    if (sdp.isNotEmpty()) webRtcManager?.handleRemoteOfferAndCreateAnswer(sdp)
                }
                "SDP_ANSWER" -> {
                    val envelope = org.json.JSONObject(payload)
                    val signal = envelope.optJSONObject("payload") ?: envelope
                    val sdp = signal.optString("sdp", envelope.optString("sdp", ""))
                    if (sdp.isNotEmpty()) webRtcManager?.handleRemoteAnswer(sdp)
                }
                "ICE_CANDIDATE" -> {
                    val envelope = org.json.JSONObject(payload)
                    val signal = envelope.optJSONObject("payload") ?: envelope
                    val candidateObject = signal.optJSONObject("candidate")
                    val candObj = candidateObject ?: signal
                    val sdpMid = candObj.optString("sdpMid", "0")
                    val sdpMLineIndex = candObj.optInt("sdpMLineIndex", 0)
                    val candidate = candObj.optString("candidate", "")
                    if (candidate.isNotEmpty()) {
                        webRtcManager?.addRemoteIceCandidate(sdpMid, sdpMLineIndex, candidate)
                    }
                }
            }
        } catch (e: Exception) {
            AuditLogger.log("WEBRTC", "SIGNAL_DISPATCH_ERR", "Error processing incoming WebRTC signal ($type): ${e.message}", AuditSeverity.WARNING)
        }
    }

    private fun startCapture(resultCode: Int, resultData: Intent, preset: VideoPreset) {
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        try {
            val windowManager = getSystemService(WINDOW_SERVICE) as? android.view.WindowManager
            val metrics = resources.displayMetrics
            val displayRotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display?.rotation ?: 0
            } else {
                @Suppress("DEPRECATION")
                windowManager?.defaultDisplay?.rotation ?: 0
            }

            // Report real display parameters to active controller
            val displayInfoPayload = """{"width":${metrics.widthPixels},"height":${metrics.heightPixels},"rotation":$displayRotation,"densityDpi":${metrics.densityDpi}}"""
            onDisplayInfoGenerated?.invoke(displayInfoPayload)
            val displayBroadcast = Intent("com.example.action.BROADCAST_DISPLAY_INFO").apply {
                putExtra("payload", displayInfoPayload)
            }
            sendBroadcast(displayBroadcast)

            // Initialize WebRTC screen capturer and session pipeline
            _streamState.value = ScreenStreamState.Connecting
            webRtcManager = com.example.webrtc.WebRtcSessionManager(
                context = this,
                onCaptureStopped = {
                    _streamState.value = ScreenStreamState.Stopped
                    AuditLogger.log("SCREEN", "PROJECTION_REVOKED", "Android stopped screen capture permission", AuditSeverity.WARNING)
                },
                onSignalingMessage = { type, payload ->
                    onWebRtcSignalGenerated?.invoke(type, payload)
                    val intent = Intent("com.example.action.WEBRTC_SIGNAL").apply {
                        putExtra("type", type)
                        putExtra("payload", payload)
                    }
                    sendBroadcast(intent)
                }
            )

            val videoTrack = webRtcManager?.startScreenCapture(
                projectionResultCode = resultCode,
                projectionData = resultData,
                width = preset.width,
                height = preset.height,
                fps = preset.fps
            ) ?: throw IllegalStateException("Android MediaProjection could not initialize a video track")

            // ICE state is the real connection signal. Do not fabricate frame,
            // bitrate, RTT or packet-loss values while the peer is negotiating.
            webRtcManager?.initPeerConnection(
                iceServers = listOf(
                    org.webrtc.PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
                ),
                isSender = true,
                onIceConnectionStateChanged = { iceState ->
                    when (iceState) {
                        org.webrtc.PeerConnection.IceConnectionState.CONNECTED,
                        org.webrtc.PeerConnection.IceConnectionState.COMPLETED -> {
                            _streamState.value = ScreenStreamState.Active(
                                StreamMetrics(
                                    currentFps = 0,
                                    bitrateKbps = 0,
                                    resolution = "${preset.width}x${preset.height}",
                                    framesEncoded = 0,
                                    bytesTransferred = 0,
                                    roundTripTimeMs = 0,
                                    packetLossPercent = 0f
                                ),
                                preset
                            )
                        }
                        org.webrtc.PeerConnection.IceConnectionState.FAILED -> {
                            _streamState.value = ScreenStreamState.Error("WebRTC ICE connection failed. Check TURN/network settings.")
                        }
                        org.webrtc.PeerConnection.IceConnectionState.DISCONNECTED,
                        org.webrtc.PeerConnection.IceConnectionState.CHECKING,
                        org.webrtc.PeerConnection.IceConnectionState.NEW -> {
                            _streamState.value = ScreenStreamState.Connecting
                        }
                        org.webrtc.PeerConnection.IceConnectionState.CLOSED -> {
                            _streamState.value = ScreenStreamState.Stopped
                        }
                        else -> Unit
                    }
                }
            )

            AuditLogger.log("SCREEN", "CAPTURE_INITIALIZED", "MediaProjection video source initialized at ${metrics.widthPixels}x${metrics.heightPixels} (rot: $displayRotation); waiting for ICE connection", AuditSeverity.INFO)
        } catch (e: Exception) {
            _streamState.value = ScreenStreamState.Error(e.message ?: "Failed to start MediaProjection")
            AuditLogger.log("SCREEN", "ERROR", "Failed to start MediaProjection: ${e.message}", AuditSeverity.CRITICAL)
        }
    }


    private fun stopCapture() {
        try {
            webRtcManager?.close()
            webRtcManager = null
                  } catch (e: Exception) {
            // Ignore clean teardown exceptions
        }
        _streamState.value = ScreenStreamState.Stopped
        AuditLogger.log("SCREEN", "STREAM_STOPPED", "Screen streaming terminated cleanly", AuditSeverity.INFO)
    }

    private fun buildForegroundNotification(): Notification {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mahi Remote Screen Active")
            .setContentText("Screen is being shared with the approved controller")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop Sharing",
                stopPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mahi Remote Screen Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows persistent status while remote screen is sharing"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}

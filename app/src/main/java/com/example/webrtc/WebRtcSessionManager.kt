package com.example.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import com.example.security.AuditLogger
import com.example.security.AuditSeverity
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class WebRtcSessionManager(
    private val context: Context,
    private val onSignalingMessage: (type: String, payload: String) -> Unit,
    private val onDataChannelMessage: ((String) -> Unit)? = null
) {
    val eglBase: EglBase = EglBase.create()
    private val executor = Executors.newSingleThreadExecutor()

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var dataChannel: DataChannel? = null

    var remoteVideoTrack: VideoTrack? = null
        private set

    init {
        initializeFactory()
    }

    private fun initializeFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
    }

    fun startScreenCapture(
        projectionResultCode: Int,
        projectionData: Intent,
        width: Int = 1080,
        height: Int = 2400,
        fps: Int = 30
    ): VideoTrack? {
        val factory = peerConnectionFactory ?: return null

        screenCapturer = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
            override fun onStop() {
                AuditLogger.log("WEBRTC", "PROJECTION_REVOKED", "System MediaProjection revoked by OS", AuditSeverity.WARNING)
                stopScreenCapture()
            }
        })

        surfaceTextureHelper = SurfaceTextureHelper.create("MahiScreenCaptureThread", eglBase.eglBaseContext)
        videoSource = factory.createVideoSource(true)
        screenCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
        screenCapturer?.startCapture(width, height, fps)

        videoTrack = factory.createVideoTrack("MAHI_VIDEO_TRACK", videoSource)
        videoTrack?.setEnabled(true)
        AuditLogger.log("WEBRTC", "CAPTURE_INITIALIZED", "ScreenCapturerAndroid active at ${width}x${height} @ ${fps}fps", AuditSeverity.SECURITY)
        return videoTrack
    }

    fun stopScreenCapture() {
        try {
            screenCapturer?.stopCapture()
            screenCapturer?.dispose()
            screenCapturer = null
            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null
            videoSource?.dispose()
            videoSource = null
            videoTrack?.dispose()
            videoTrack = null
        } catch (e: Exception) {
            AuditLogger.log("WEBRTC", "CAPTURE_CLEANUP_ERR", "Error stopping screen capture: ${e.message}", AuditSeverity.WARNING)
        }
    }

    fun initPeerConnection(
        iceServers: List<PeerConnection.IceServer>,
        isSender: Boolean,
        onRemoteTrack: ((VideoTrack) -> Unit)? = null
    ) {
        val factory = peerConnectionFactory ?: return

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                AuditLogger.log("WEBRTC", "ICE_STATE", "ICE Connection State: $state", AuditSeverity.INFO)
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null) {
                    val payload = """{"sdpMid":"${candidate.sdpMid}","sdpMLineIndex":${candidate.sdpMLineIndex},"candidate":"${candidate.sdp}"}"""
                    onSignalingMessage("ICE_CANDIDATE", payload)
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onAddStream(stream: MediaStream?) {}

            override fun onRemoveStream(stream: MediaStream?) {}

            override fun onDataChannel(dc: DataChannel?) {
                dataChannel = dc
                dc?.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) {}
                    override fun onStateChange() {}
                    override fun onMessage(buffer: DataChannel.Buffer?) {
                        if (buffer != null) {
                            val data = ByteArray(buffer.data.remaining())
                            buffer.data.get(data)
                            onDataChannelMessage?.invoke(String(data, Charsets.UTF_8))
                        }
                    }
                })
            }

            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                val track = receiver?.track()
                if (track is VideoTrack) {
                    remoteVideoTrack = track
                    onRemoteTrack?.invoke(track)
                    AuditLogger.log("WEBRTC", "REMOTE_TRACK_ATTACHED", "Received remote video track", AuditSeverity.INFO)
                }
            }
        })

        if (isSender) {
            videoTrack?.let { vt ->
                peerConnection?.addTrack(vt, listOf("MAHI_STREAM"))
            }
        } else {
            // Receiver / Controller creates DataChannel for remote touch events
            val init = DataChannel.Init().apply {
                ordered = true
                maxRetransmits = 0
            }
            dataChannel = peerConnection?.createDataChannel("mahi_input", init)
        }
    }

    fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc != null) {
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            onSignalingMessage("SDP_OFFER", """{"sdp":"${desc.description.replace("\r\n", "\\r\\n")}"}""")
                        }
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(p0: String?) {}
                    }, desc)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    fun handleRemoteOfferAndCreateAnswer(remoteSdp: String) {
        val desc = SessionDescription(SessionDescription.Type.OFFER, remoteSdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                val constraints = MediaConstraints()
                peerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answerDesc: SessionDescription?) {
                        if (answerDesc != null) {
                            peerConnection?.setLocalDescription(object : SdpObserver {
                                override fun onCreateSuccess(p0: SessionDescription?) {}
                                override fun onSetSuccess() {
                                    onSignalingMessage("SDP_ANSWER", """{"sdp":"${answerDesc.description.replace("\r\n", "\\r\\n")}"}""")
                                }
                                override fun onCreateFailure(p0: String?) {}
                                override fun onSetFailure(p0: String?) {}
                            }, answerDesc)
                        }
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, constraints)
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, desc)
    }

    fun handleRemoteAnswer(remoteSdp: String) {
        val desc = SessionDescription(SessionDescription.Type.ANSWER, remoteSdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                AuditLogger.log("WEBRTC", "ANSWER_APPLIED", "Remote SDP answer applied successfully", AuditSeverity.INFO)
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, desc)
    }

    fun addRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, sdp: String) {
        val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
        peerConnection?.addIceCandidate(candidate)
    }

    fun sendDataChannelMessage(msg: String): Boolean {
        val dc = dataChannel ?: return false
        if (dc.state() == DataChannel.State.OPEN) {
            val buffer = DataChannel.Buffer(ByteBuffer.wrap(msg.toByteArray(Charsets.UTF_8)), false)
            return dc.send(buffer)
        }
        return false
    }

    fun attachRenderer(renderer: SurfaceViewRenderer) {
        renderer.init(eglBase.eglBaseContext, null)
        renderer.setEnableHardwareScaler(true)
        remoteVideoTrack?.addSink(renderer)
    }

    fun close() {
        stopScreenCapture()
        try {
            dataChannel?.close()
            dataChannel = null
            peerConnection?.close()
            peerConnection = null
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null
            eglBase.release()
        } catch (e: Exception) {
            AuditLogger.log("WEBRTC", "DISPOSE_ERR", "Error disposing WebRTC resources: ${e.message}", AuditSeverity.INFO)
        }
    }
}

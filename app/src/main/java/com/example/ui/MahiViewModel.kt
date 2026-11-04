package com.example.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.appmanager.AppItem
import com.example.appmanager.AppManager
import com.example.controller.AppRole
import com.example.controller.ControllerSession
import com.example.controller.KnownTargetDevice
import com.example.controller.RemoteTelemetry
import com.example.controller.RemoteViewerTab
import com.example.diagnostics.DiagnosticItem
import com.example.diagnostics.DiagnosticsManager
import com.example.filetransfer.FileEntry
import com.example.filetransfer.FileManager
import com.example.pairing.OperatorRequest
import com.example.pairing.PairingManager
import com.example.remoteinput.MahiAccessibilityService
import com.example.remotescreen.ScreenCaptureService
import com.example.remotescreen.ScreenStreamState
import com.example.remotescreen.StreamMetrics
import com.example.remotescreen.VideoPreset
import com.example.rootintegration.RootDetector
import com.example.security.AuditLogger
import com.example.security.AuditSeverity
import com.example.security.DeviceIdentity
import com.example.signaling.ConnectionStatus
import com.example.signaling.SignalingClient
import com.example.signaling.SignalingMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class MainTab(val title: String) {
    DASHBOARD("Dashboard"),
    LIVE_UI("Live UI"),
    PAIRING("Pairing"),
    SERVICES("Services"),
    DIAGNOSTICS("Diagnostics"),
    FILES("Files"),
    APPS("Apps"),
    AUDIT("Security Logs")
}

class MahiViewModel(application: Application) : AndroidViewModel(application) {

    val identity = DeviceIdentity.getOrCreateIdentity(application)
    val backendConfig = com.example.network.BackendConfig(application)
    val liveUiManager = com.example.liveui.LiveUiManager(application, backendConfig)
    val otaUpdateManager = com.example.ota.OtaUpdateManager(application, backendConfig)
    val pairingManager = PairingManager(application)
    val diagnosticsManager = DiagnosticsManager(application)
    val fileManager = FileManager(application)
    val appManager = AppManager(application)
    val rootDetector = RootDetector(application)

    private val _showBackendSettingsDialog = MutableStateFlow(false)
    val showBackendSettingsDialog: StateFlow<Boolean> = _showBackendSettingsDialog.asStateFlow()

    fun setShowBackendSettingsDialog(show: Boolean) {
        _showBackendSettingsDialog.value = show
    }

    fun updateBackendUrl(newUrl: String) {
        backendConfig.saveBackendUrl(newUrl)
        signalingClient.updateServerUrl(backendConfig.getWebSocketUrl())
        liveUiManager.checkForLiveUiUpdate(force = true)
        otaUpdateManager.checkForAppUpdates()
        AuditLogger.log("BACKEND", "URL_UPDATED", "Configured server endpoint: $newUrl", AuditSeverity.INFO)
    }

    // Role state: Receiver (default) or Controller
    private val _appRole = MutableStateFlow(AppRole.RECEIVER)
    val appRole: StateFlow<AppRole> = _appRole.asStateFlow()

    // Receiver state
    private val _selectedTab = MutableStateFlow(MainTab.DASHBOARD)
    val selectedTab: StateFlow<MainTab> = _selectedTab.asStateFlow()

    private val _showQrDialog = MutableStateFlow(false)
    val showQrDialog: StateFlow<Boolean> = _showQrDialog.asStateFlow()

    private val _showDisconnectDialog = MutableStateFlow(false)
    val showDisconnectDialog: StateFlow<Boolean> = _showDisconnectDialog.asStateFlow()

    private val _videoPreset = MutableStateFlow(VideoPreset.BALANCED)
    val videoPreset: StateFlow<VideoPreset> = _videoPreset.asStateFlow()

    private val _currentBrowsingPath = MutableStateFlow(application.filesDir.absolutePath)
    val currentBrowsingPath: StateFlow<String> = _currentBrowsingPath.asStateFlow()

    private val _currentFileList = MutableStateFlow<List<FileEntry>>(emptyList())
    val currentFileList: StateFlow<List<FileEntry>> = _currentFileList.asStateFlow()

    private val _installedApps = MutableStateFlow<List<AppItem>>(emptyList())
    val installedApps: StateFlow<List<AppItem>> = _installedApps.asStateFlow()

    private val _appSearchQuery = MutableStateFlow("")
    val appSearchQuery: StateFlow<String> = _appSearchQuery.asStateFlow()

    private val _isDiagnosticsRunning = MutableStateFlow(false)
    val isDiagnosticsRunning: StateFlow<Boolean> = _isDiagnosticsRunning.asStateFlow()

    val screenStreamState: StateFlow<ScreenStreamState> = ScreenCaptureService.streamState
    val auditEvents = AuditLogger.events

    // Controller Mode State
    private val _isControllerConnecting = MutableStateFlow(false)
    val isControllerConnecting: StateFlow<Boolean> = _isControllerConnecting.asStateFlow()

    private val _controllerErrorMessage = MutableStateFlow<String?>(null)
    val controllerErrorMessage: StateFlow<String?> = _controllerErrorMessage.asStateFlow()

    private val _activeControllerSession = MutableStateFlow<ControllerSession?>(null)
    val activeControllerSession: StateFlow<ControllerSession?> = _activeControllerSession.asStateFlow()

    private val _recentTargetDevices = MutableStateFlow<List<KnownTargetDevice>>(emptyList())
    val recentTargetDevices: StateFlow<List<KnownTargetDevice>> = _recentTargetDevices.asStateFlow()

    private val _remoteViewerTab = MutableStateFlow(RemoteViewerTab.SCREEN)
    val remoteViewerTab: StateFlow<RemoteViewerTab> = _remoteViewerTab.asStateFlow()

    private val _remoteFileList = MutableStateFlow<List<FileEntry>>(emptyList())
    val remoteFileList: StateFlow<List<FileEntry>> = _remoteFileList.asStateFlow()

    private val _remoteAppList = MutableStateFlow<List<AppItem>>(emptyList())
    val remoteAppList: StateFlow<List<AppItem>> = _remoteAppList.asStateFlow()

    private val _remoteTelemetry = MutableStateFlow<RemoteTelemetry?>(null)
    val remoteTelemetry: StateFlow<RemoteTelemetry?> = _remoteTelemetry.asStateFlow()

    private val _remoteDisplayMetrics = MutableStateFlow(com.example.controller.RemoteDisplayMetrics())
    val remoteDisplayMetrics: StateFlow<com.example.controller.RemoteDisplayMetrics> = _remoteDisplayMetrics.asStateFlow()

    private val _remoteMetrics = MutableStateFlow(StreamMetrics())
    val remoteMetrics: StateFlow<StreamMetrics> = _remoteMetrics.asStateFlow()

    val signalingClient: SignalingClient

    var controllerWebRtcManager: com.example.webrtc.WebRtcSessionManager? = null
        private set

    init {
        // Wire ScreenCaptureService outgoing WebRTC signaling and display telemetry
        ScreenCaptureService.onWebRtcSignalGenerated = { type, payload ->
            val target = pairingManager.activeSession.value?.controllerId ?: pairingManager.pendingRequest.value?.controllerId
            signalingClient.send(type, payload, target)
        }
        ScreenCaptureService.onDisplayInfoGenerated = { payload ->
            val target = pairingManager.activeSession.value?.controllerId ?: pairingManager.pendingRequest.value?.controllerId
            signalingClient.send("DISPLAY_INFO", payload, target)
        }

        signalingClient = SignalingClient(identity) { msg ->
            handleIncomingSignalingMessage(msg)
        }
        signalingClient.updateServerUrl(backendConfig.getWebSocketUrl())
        signalingClient.connect()

        // Register initial pairing code with backend signaling server
        pairingManager.currentPairingCode.value?.let { pCode ->
            signalingClient.send(
                "REGISTER_PAIRING_CODE",
                """{"code":"${pCode.code}","expiresAt":${pCode.expiresAtMillis}}"""
            )
        }

        refreshFiles()
        refreshApps()

        viewModelScope.launch {
            runDiagnostics()
            liveUiManager.checkForLiveUiUpdate()
            otaUpdateManager.checkForAppUpdates()
        }
    }

    private fun handleIncomingSignalingMessage(msg: SignalingMessage) {
        viewModelScope.launch {
            try {
                when (msg.type) {
                    "UI_UPDATE_AVAILABLE" -> {
                        liveUiManager.checkForLiveUiUpdate(force = true)
                    }
                    "APP_UPDATE_AVAILABLE" -> {
                        otaUpdateManager.checkForAppUpdates()
                    }
                    "PAIR_REQUEST" -> {
                        val root = JSONObject(msg.payload)
                        val opName = root.optString("operatorName", root.optString("controllerName", "Mahi Remote Controller"))
                        val opLoc = root.optString("operatorLocation", "Remote Android Controller")
                        val opFp = root.optString("fingerprint", root.optString("controllerFingerprint", "UNKNOWN-FP"))
                        val cId = root.optString("controllerId", msg.sender)
                        val scopesJson = root.optJSONArray("requestedScopes")
                        val scopes = mutableListOf<String>()
                        if (scopesJson != null) {
                            for (i in 0 until scopesJson.length()) {
                                scopes.add(scopesJson.getString(i))
                            }
                        } else {
                            scopes.addAll(listOf("Screen Mirroring", "Remote Interaction", "File Transfer", "Diagnostics"))
                        }
                        pairingManager.recordIncomingOperatorRequest(
                            controllerId = cId,
                            operatorName = opName,
                            location = opLoc,
                            fingerprint = opFp,
                            scopes = scopes
                        )
                    }
                    "PAIR_ACCEPT" -> {
                        // Handled on Controller side: target device approved!
                        val root = JSONObject(msg.payload)
                        val sessionId = root.optString("sessionId", UUID.randomUUID().toString())
                        val receiverName = root.optString("receiverName", "Android Receiver Phone")
                        val targetFp = root.optString("fingerprint", "TARGET-FP")
                        val session = ControllerSession(
                            sessionId = sessionId,
                            targetDeviceId = msg.sender,
                            targetDeviceName = receiverName,
                            targetFingerprint = targetFp,
                            sessionToken = UUID.randomUUID().toString(),
                            connectedAtMillis = System.currentTimeMillis()
                        )
                        _activeControllerSession.value = session
                        _isControllerConnecting.value = false
                        _controllerErrorMessage.value = null

                        // Add to recent target devices
                        val updated = _recentTargetDevices.value.filter { it.deviceId != msg.sender } +
                            KnownTargetDevice(
                                deviceId = msg.sender,
                                name = receiverName,
                                model = "Android Device",
                                fingerprint = targetFp,
                                lastConnectedAtMillis = System.currentTimeMillis(),
                                isOnline = true
                            )
                        _recentTargetDevices.value = updated

                        AuditLogger.log("CONTROLLER", "SESSION_ESTABLISHED", "Connected to remote phone: $receiverName", AuditSeverity.SECURITY)

                        // Request initial remote app and file catalog
                        fetchRemoteApps()
                        fetchRemoteFiles()

                        // Initialize controller WebRTC session to receive remote screen
                        initControllerWebRtc(msg.sender)
                    }
                    "PAIR_REJECT" -> {
                        _isControllerConnecting.value = false
                        _controllerErrorMessage.value = "Remote phone denied the connection request."
                        AuditLogger.log("CONTROLLER", "SESSION_DENIED", "Target phone rejected pairing request", AuditSeverity.WARNING)
                    }
                    "PAIR_ERROR" -> {
                        val root = JSONObject(msg.payload)
                        _isControllerConnecting.value = false
                        _controllerErrorMessage.value = root.optString("error", "Pairing error occurred.")
                    }
                    "APP_LIST_RESPONSE" -> {
                        val root = JSONObject(msg.payload)
                        val raw = root.optString("payload", msg.payload)
                        val jsonArr = if (raw.startsWith("[")) JSONArray(raw) else root.optJSONArray("apps") ?: JSONArray()
                        val list = mutableListOf<AppItem>()
                        for (i in 0 until jsonArr.length()) {
                            val obj = jsonArr.getJSONObject(i)
                            list.add(
                                AppItem(
                                    packageName = obj.optString("pkg", obj.optString("packageName")),
                                    appName = obj.optString("name", obj.optString("appName")),
                                    versionName = obj.optString("ver", "1.0"),
                                    versionCode = 1L,
                                    isSystemApp = false,
                                    targetSdk = 34
                                )
                            )
                        }
                        _remoteAppList.value = list
                    }
                    "FILE_LIST_RESPONSE" -> {
                        val root = JSONObject(msg.payload)
                        val raw = root.optString("payload", msg.payload)
                        val jsonArr = if (raw.startsWith("[")) JSONArray(raw) else root.optJSONArray("files") ?: JSONArray()
                        val list = mutableListOf<FileEntry>()
                        for (i in 0 until jsonArr.length()) {
                            val obj = jsonArr.getJSONObject(i)
                            val size = obj.optLong("size", 0L)
                            list.add(
                                FileEntry(
                                    name = obj.optString("name"),
                                    path = obj.optString("path"),
                                    sizeBytes = size,
                                    formattedSize = if (size > 1048576) "${size / 1048576} MB" else "${size / 1024} KB",
                                    isDirectory = obj.optBoolean("isDir"),
                                    lastModified = "Just now",
                                    mimeType = if (obj.optBoolean("isDir")) "inode/directory" else "application/octet-stream"
                                )
                            )
                        }
                        _remoteFileList.value = list
                    }
                    "DIAGNOSTICS_RESULT" -> {
                        val root = JSONObject(msg.payload)
                        val payloadObj = if (root.has("payload") && root.get("payload") is JSONObject) root.getJSONObject("payload") else root
                        if (!payloadObj.has("battery") && !payloadObj.has("temp") && !payloadObj.has("storage")) {
                            _remoteTelemetry.value = null
                        } else {
                            val battery = payloadObj.optInt("battery", -1)
                            val temp = payloadObj.optDouble("temp", -1.0).toFloat()
                            val storage = payloadObj.optString("storage", "Unknown")
                            _remoteTelemetry.value = RemoteTelemetry(
                                batteryPercent = battery,
                                batteryTempC = temp,
                                storageFree = storage,
                                roundTripTimeMs = payloadObj.optInt("rtt", 0)
                            )
                        }
                    }
                    "DISPLAY_INFO" -> {
                        val envelope = JSONObject(msg.payload)
                        val root = envelope.optJSONObject("payload") ?: envelope
                        val w = root.optInt("width", 1080)
                        val h = root.optInt("height", 2400)
                        val rot = root.optInt("rotation", 0)
                        val dpi = root.optInt("densityDpi", 420)
                        _remoteDisplayMetrics.value = com.example.controller.RemoteDisplayMetrics(
                            width = w,
                            height = h,
                            rotation = rot,
                            densityDpi = dpi
                        )
                        AuditLogger.log("DISPLAY", "METRICS_UPDATED", "Remote display configured to ${w}x${h} (rot: $rot)", AuditSeverity.INFO)
                    }
                    "INPUT_EVENT" -> {
                        handleInputEvent(msg)
                    }
                    "APP_COMMAND" -> {
                        handleAppCommand(msg)
                    }
                    "FILE_COMMAND" -> {
                        handleFileCommand(msg)
                    }
                    "DIAGNOSTICS_RUN" -> {
                        runDiagnostics()
                        val telemetry = diagnosticsManager.telemetry.value
                        signalingClient.send(
                            "DIAGNOSTICS_RESULT",
                            """{"battery":${telemetry.batteryPercent},"temp":${telemetry.batteryTempC},"storage":"${telemetry.freeStorageGb}GB"}""",
                            msg.sender
                        )
                    }
                    "SDP_OFFER" -> {
                        if (_appRole.value == AppRole.CONTROLLER) {
                            val root = JSONObject(msg.payload)
                            val signal = root.optJSONObject("payload") ?: root
                            val sdp = signal.optString("sdp", root.optString("sdp", ""))
                            if (sdp.isNotEmpty()) controllerWebRtcManager?.handleRemoteOfferAndCreateAnswer(sdp)
                        } else {
                            ScreenCaptureService.handleIncomingSignalFromViewModel(msg.type, msg.payload)
                        }
                    }
                    "SDP_ANSWER" -> {
                        if (_appRole.value == AppRole.CONTROLLER) {
                            val root = JSONObject(msg.payload)
                            val signal = root.optJSONObject("payload") ?: root
                            val sdp = signal.optString("sdp", root.optString("sdp", ""))
                            if (sdp.isNotEmpty()) controllerWebRtcManager?.handleRemoteAnswer(sdp)
                        } else {
                            ScreenCaptureService.handleIncomingSignalFromViewModel(msg.type, msg.payload)
                        }
                    }
                    "ICE_CANDIDATE" -> {
                        if (_appRole.value == AppRole.CONTROLLER) {
                            val root = JSONObject(msg.payload)
                            val signal = root.optJSONObject("payload") ?: root
                            val candidateObject = signal.optJSONObject("candidate")
                            val candObj = candidateObject ?: signal
                            val sdpMid = candObj.optString("sdpMid", "0")
                            val sdpMLineIndex = candObj.optInt("sdpMLineIndex", 0)
                            val candidate = candObj.optString("candidate", "")
                            if (candidate.isNotEmpty()) controllerWebRtcManager?.addRemoteIceCandidate(sdpMid, sdpMLineIndex, candidate)
                        } else {
                            ScreenCaptureService.handleIncomingSignalFromViewModel(msg.type, msg.payload)
                        }
                    }
                    "EMERGENCY_DISCONNECT" -> {
                        if (_activeControllerSession.value != null) {
                            disconnectControllerSession()
                        } else {
                            emergencyDisconnect()
                        }
                    }
                }
            } catch (e: Exception) {
                AuditLogger.log("PROTOCOL", "DISPATCH_ERROR", "Error parsing remote message: ${e.message}", AuditSeverity.WARNING)
            }
        }
    }

    private fun commandPayload(msg: SignalingMessage): JSONObject {
        val envelope = JSONObject(msg.payload)
        return envelope.optJSONObject("payload") ?: envelope
    }

    private fun handleInputEvent(msg: SignalingMessage) {
        val root = commandPayload(msg)
        val cmd = root.optJSONObject("command") ?: root
        val requestedAction = cmd.optString("action", "").uppercase()
        val type = when (requestedAction) {
            "LONG_PRESS" -> "LONG_PRESS"
            "DRAG" -> "DRAG"
            "DOUBLE_TAP" -> "DOUBLE_TAP"
            "TAP" -> "TAP"
            else -> cmd.optString("type", "")
        }

        val dm = getApplication<Application>().resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        when (type.uppercase()) {
            "TAP" -> {
                val x = cmd.optDouble("x", -1.0).toFloat()
                val y = cmd.optDouble("y", -1.0).toFloat()
                MahiAccessibilityService.performTap(x, y, screenW, screenH)
            }
            "DOUBLETAP", "DOUBLE_TAP" -> {
                val x = cmd.optDouble("x", -1.0).toFloat()
                val y = cmd.optDouble("y", -1.0).toFloat()
                MahiAccessibilityService.performDoubleTap(x, y, screenW, screenH)
            }
            "LONGPRESS", "LONG_PRESS" -> {
                val x = cmd.optDouble("x", -1.0).toFloat()
                val y = cmd.optDouble("y", -1.0).toFloat()
                MahiAccessibilityService.performLongPress(x, y, screenW, screenH)
            }
            "SWIPE" -> {
                val x1 = cmd.optDouble("x", -1.0).toFloat()
                val y1 = cmd.optDouble("y", -1.0).toFloat()
                val x2 = cmd.optDouble("x2", -1.0).toFloat()
                val y2 = cmd.optDouble("y2", -1.0).toFloat()
                val duration = cmd.optLong("durationMs", 300L)
                MahiAccessibilityService.performSwipe(x1, y1, x2, y2, duration, screenW, screenH)
            }
            "DRAG" -> {
                val x1 = cmd.optDouble("x", -1.0).toFloat()
                val y1 = cmd.optDouble("y", -1.0).toFloat()
                val x2 = cmd.optDouble("x2", -1.0).toFloat()
                val y2 = cmd.optDouble("y2", -1.0).toFloat()
                val duration = cmd.optLong("durationMs", 600L)
                MahiAccessibilityService.performDrag(x1, y1, x2, y2, duration, screenW, screenH)
            }
            "SCROLL" -> {
                val action = cmd.optString("action", "DOWN")
                val startY = if (action.equals("UP", ignoreCase = true)) 1400f else 600f
                val endY = if (action.equals("UP", ignoreCase = true)) 600f else 1400f
                MahiAccessibilityService.performSwipe(540f, startY, 540f, endY, 250L, screenW, screenH)
            }
            "GLOBALACTION", "GLOBAL_ACTION" -> {
                val action = cmd.optString("action", "BACK")
                MahiAccessibilityService.performGlobal(action)
            }
            "TEXTINPUT", "TEXT_INPUT" -> {
                val text = cmd.optString("text", "")
                MahiAccessibilityService.performTextInput(text)
            }
            "VOLUME" -> {
                val dir = cmd.optString("action", "UP")
                MahiAccessibilityService.adjustVolume(dir, getApplication())
            }
        }
    }

    private fun handleAppCommand(msg: SignalingMessage) {
        val root = commandPayload(msg)
        val action = root.optString("action", "LIST")
        val pkg = root.optString("packageName", "")

        when (action.uppercase()) {
            "LIST" -> {
                val apps = appManager.getInstalledApps(false)
                val jsonArr = apps.take(50).joinToString(",") {
                    """{"name":"${it.appName}","pkg":"${it.packageName}","ver":"${it.versionName}"}"""
                }
                signalingClient.send("APP_LIST_RESPONSE", "[$jsonArr]", msg.sender)
            }
            "LAUNCH" -> {
                if (pkg.isNotEmpty()) appManager.launchApp(pkg)
            }
            "SETTINGS" -> {
                if (pkg.isNotEmpty()) appManager.openAppSettings(pkg)
            }
            "UNINSTALL" -> {
                if (pkg.isNotEmpty()) appManager.requestUninstall(pkg)
            }
        }
    }

    private fun handleFileCommand(msg: SignalingMessage) {
        val root = commandPayload(msg)
        val action = root.optString("action", "LIST")
        val path = root.optString("path", getApplication<Application>().filesDir.absolutePath)

        when (action.uppercase()) {
            "LIST" -> {
                val files = fileManager.listFiles(path)
                val jsonArr = files.joinToString(",") {
                    """{"name":"${it.name}","path":"${it.path}","isDir":${it.isDirectory},"size":${it.sizeBytes}}"""
                }
                signalingClient.send("FILE_LIST_RESPONSE", "[$jsonArr]", msg.sender)
            }
            "DELETE" -> {
                fileManager.deleteFile(path, true)
                refreshFiles()
            }
            "CREATE_FOLDER" -> {
                val name = root.optString("name", "NewFolder")
                fileManager.createFolder(path, name)
                refreshFiles()
            }
            "READ_CHUNK" -> {
                val offset = root.optLong("offset", 0L)
                val len = root.optInt("length", 32768)
                val bytes = fileManager.readFileChunk(path, offset, len)
                val base64 = if (bytes != null) android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP) else ""
                signalingClient.send("FILE_CHUNK_RESPONSE", """{"path":"$path","offset":$offset,"data":"$base64"}""", msg.sender)
            }
            "WRITE_CHUNK" -> {
                val base64 = root.optString("data", "")
                val append = root.optBoolean("append", true)
                val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                fileManager.writeFileChunk(path, bytes, append)
                refreshFiles()
            }
        }
    }

    // Role switcher
    fun setAppRole(role: AppRole) {
        _appRole.value = role
        signalingClient.setRole(role.name)
        AuditLogger.log("ROLE", "ROLE_SWITCHED", "Switched application mode to ${role.title}", AuditSeverity.INFO)
    }

    // Controller actions
    fun connectToTargetWithCode(code: String) {
        _isControllerConnecting.value = true
        _controllerErrorMessage.value = null

        val formatted = code.trim().uppercase()
        val payload = """
            {
              "type": "PAIR_BY_CODE",
              "code": "$formatted",
              "controllerName": "${identity.deviceName}",
              "controllerModel": "${android.os.Build.MODEL}",
              "fingerprint": "${identity.fingerprint}",
              "requestedScopes": ["Screen Mirroring", "Remote Interaction", "File Transfer", "Diagnostics"]
            }
        """.trimIndent()

        val sent = signalingClient.send("PAIR_BY_CODE", payload)
        if (!sent) {
            _isControllerConnecting.value = false
            _controllerErrorMessage.value = "Signaling connection offline. Please check network."
        }
    }

    fun connectToTargetWithQr(qrJson: String) {
        try {
            val root = JSONObject(qrJson)
            val code = root.optString("pairingCode", "")
            if (code.isNotBlank()) {
                connectToTargetWithCode(code)
            } else {
                _controllerErrorMessage.value = "QR payload does not contain a valid pairing code."
            }
        } catch (e: Exception) {
            _controllerErrorMessage.value = "Invalid QR code format: ${e.message}"
        }
    }

    fun connectToTargetDeviceId(deviceId: String, name: String) {
        _isControllerConnecting.value = true
        _controllerErrorMessage.value = null

        val payload = """
            {
              "type": "PAIR_REQUEST",
              "controllerId": "${identity.deviceId}",
              "operatorName": "${identity.deviceName}",
              "operatorLocation": "Remote Android Controller",
              "fingerprint": "${identity.fingerprint}",
              "requestedScopes": ["Screen Mirroring", "Remote Interaction", "File Transfer", "Diagnostics"]
            }
        """.trimIndent()

        signalingClient.send("PAIR_REQUEST", payload, deviceId)
    }

    fun removeRecentDevice(deviceId: String) {
        _recentTargetDevices.value = _recentTargetDevices.value.filter { it.deviceId != deviceId }
    }

    private fun initControllerWebRtc(targetDeviceId: String) {
        try {
            controllerWebRtcManager?.close()
            controllerWebRtcManager = com.example.webrtc.WebRtcSessionManager(
                context = getApplication(),
                onSignalingMessage = { type, payload ->
                    signalingClient.send(type, payload, targetDeviceId)
                }
            ).apply {
                initPeerConnection(
                    iceServers = listOf(
                        org.webrtc.PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
                    ),
                    isSender = false,
                    onRemoteTrack = { _ ->
                        AuditLogger.log("CONTROLLER", "STREAM_ESTABLISHED", "Remote video track connected to controller", AuditSeverity.INFO)
                    }
                )
                createOffer()
            }
        } catch (e: Exception) {
            AuditLogger.log("CONTROLLER", "WEBRTC_INIT_ERR", "Failed initializing controller WebRTC: ${e.message}", AuditSeverity.WARNING)
        }
    }

    fun disconnectControllerSession() {
        val session = _activeControllerSession.value
        try {
            controllerWebRtcManager?.close()
            controllerWebRtcManager = null
        } catch (e: Exception) {
            // Ignore teardown exception
        }
        if (session != null) {
            signalingClient.send("EMERGENCY_DISCONNECT", """{"reason":"Controller disconnected"}""", session.targetDeviceId)
            _activeControllerSession.value = null
            AuditLogger.log("CONTROLLER", "DISCONNECTED", "Disconnected from ${session.targetDeviceName}", AuditSeverity.INFO)
        }
    }

    fun sendRemoteTap(x: Float, y: Float, canvasW: Float, canvasH: Float) {
        val session = _activeControllerSession.value ?: return
        val targetWidth = _remoteDisplayMetrics.value.width.toFloat()
        val targetHeight = _remoteDisplayMetrics.value.height.toFloat()
        val targetX = (x / canvasW) * targetWidth
        val targetY = (y / canvasH) * targetHeight

        val payload = """
            {
              "type": "INPUT_EVENT",
              "command": {
                "type": "TAP",
                "x": $targetX,
                "y": $targetY
              }
            }
        """.trimIndent()
        signalingClient.send("INPUT_EVENT", payload, session.targetDeviceId)
    }

    fun sendRemoteDoubleTap(x: Float, y: Float, canvasW: Float, canvasH: Float) {
        val session = _activeControllerSession.value ?: return
        val targetWidth = _remoteDisplayMetrics.value.width.toFloat()
        val targetHeight = _remoteDisplayMetrics.value.height.toFloat()
        val targetX = (x / canvasW) * targetWidth
        val targetY = (y / canvasH) * targetHeight

        val payload = """
            {
              "type": "INPUT_EVENT",
              "command": {
                "type": "DOUBLE_TAP",
                "x": $targetX,
                "y": $targetY
              }
            }
        """.trimIndent()
        signalingClient.send("INPUT_EVENT", payload, session.targetDeviceId)
    }

    fun sendRemoteLongPress(x: Float, y: Float, canvasW: Float, canvasH: Float) {
        val session = _activeControllerSession.value ?: return
        val targetWidth = _remoteDisplayMetrics.value.width.toFloat()
        val targetHeight = _remoteDisplayMetrics.value.height.toFloat()
        val targetX = (x / canvasW) * targetWidth
        val targetY = (y / canvasH) * targetHeight

        val payload = """
            {
              "type": "INPUT_EVENT",
              "command": {
                "type": "LONG_PRESS",
                "x": $targetX,
                "y": $targetY
              }
            }
        """.trimIndent()
        signalingClient.send("INPUT_EVENT", payload, session.targetDeviceId)
    }

    fun sendRemoteSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long, canvasW: Float, canvasH: Float) {
        val session = _activeControllerSession.value ?: return
        val targetWidth = _remoteDisplayMetrics.value.width.toFloat()
        val targetHeight = _remoteDisplayMetrics.value.height.toFloat()
        val targetX1 = (x1 / canvasW) * targetWidth
        val targetY1 = (y1 / canvasH) * targetHeight
        val targetX2 = (x2 / canvasW) * targetWidth
        val targetY2 = (y2 / canvasH) * targetHeight

        val payload = """
            {
              "type": "INPUT_EVENT",
              "command": {
                "type": "SWIPE",
                "x": $targetX1,
                "y": $targetY1,
                "x2": $targetX2,
                "y2": $targetY2,
                "durationMs": $durationMs
              }
            }
        """.trimIndent()
        signalingClient.send("INPUT_EVENT", payload, session.targetDeviceId)
    }

    fun sendRemoteGlobalAction(action: String) {
        val session = _activeControllerSession.value ?: return
        val payload = """
            {
              "type": "INPUT_EVENT",
              "command": {
                "type": "GLOBAL_ACTION",
                "action": "$action"
              }
            }
        """.trimIndent()
        signalingClient.send("INPUT_EVENT", payload, session.targetDeviceId)
    }

    fun sendRemoteTextInput(text: String) {
        val session = _activeControllerSession.value ?: return
        val safeText = JSONObject.quote(text)
        val payload = """
            {
              "type": "INPUT_EVENT",
              "command": {
                "type": "TEXT_INPUT",
                "text": $safeText
              }
            }
        """.trimIndent()
        signalingClient.send("INPUT_EVENT", payload, session.targetDeviceId)
    }

    fun sendRemoteVolume(direction: String) {
        val session = _activeControllerSession.value ?: return
        val payload = """
            {
              "type": "INPUT_EVENT",
              "command": {
                "type": "VOLUME",
                "action": "$direction"
              }
            }
        """.trimIndent()
        signalingClient.send("INPUT_EVENT", payload, session.targetDeviceId)
    }

    fun fetchRemoteFiles(path: String = "") {
        val session = _activeControllerSession.value ?: return
        val payload = """{"action":"LIST","path":"$path"}"""
        signalingClient.send("FILE_COMMAND", payload, session.targetDeviceId)
    }

    fun deleteRemoteFile(path: String) {
        val session = _activeControllerSession.value ?: return
        val payload = """{"action":"DELETE","path":"$path"}"""
        signalingClient.send("FILE_COMMAND", payload, session.targetDeviceId)
        fetchRemoteFiles()
    }

    fun fetchRemoteApps() {
        val session = _activeControllerSession.value ?: return
        val payload = """{"action":"LIST"}"""
        signalingClient.send("APP_COMMAND", payload, session.targetDeviceId)
    }

    fun launchRemoteApp(packageName: String) {
        val session = _activeControllerSession.value ?: return
        val payload = """{"action":"LAUNCH","packageName":"$packageName"}"""
        signalingClient.send("APP_COMMAND", payload, session.targetDeviceId)
    }

    fun openRemoteAppSettings(packageName: String) {
        val session = _activeControllerSession.value ?: return
        val payload = """{"action":"SETTINGS","packageName":"$packageName"}"""
        signalingClient.send("APP_COMMAND", payload, session.targetDeviceId)
    }

    fun uninstallRemoteApp(packageName: String) {
        val session = _activeControllerSession.value ?: return
        val payload = """{"action":"UNINSTALL","packageName":"$packageName"}"""
        signalingClient.send("APP_COMMAND", payload, session.targetDeviceId)
    }

    fun runRemoteDiagnostics() {
        val session = _activeControllerSession.value ?: return
        signalingClient.send("DIAGNOSTICS_RUN", """{}""", session.targetDeviceId)
    }

    fun setRemoteViewerTab(tab: RemoteViewerTab) {
        _remoteViewerTab.value = tab
    }

    // Receiver methods
    fun selectTab(tab: MainTab) {
        _selectedTab.value = tab
    }

    fun setShowQrDialog(show: Boolean) {
        _showQrDialog.value = show
    }

    fun setShowDisconnectDialog(show: Boolean) {
        _showDisconnectDialog.value = show
    }

    fun setVideoPreset(preset: VideoPreset) {
        _videoPreset.value = preset
    }

    fun setAppSearchQuery(query: String) {
        _appSearchQuery.value = query
    }

    fun refreshFiles() {
        _currentFileList.value = fileManager.listFiles(_currentBrowsingPath.value)
    }

    fun navigateToFolder(path: String) {
        _currentBrowsingPath.value = path
        refreshFiles()
    }

    fun deleteFile(path: String) {
        fileManager.deleteFile(path, confirmed = true)
        refreshFiles()
    }

    fun refreshApps() {
        _installedApps.value = appManager.getInstalledApps(includeSystem = false)
    }

    fun launchApp(pkg: String) {
        appManager.launchApp(pkg)
    }

    fun openAppSettings(pkg: String) {
        appManager.openAppSettings(pkg)
    }

    fun requestUninstall(pkg: String) {
        appManager.requestUninstall(pkg)
    }

    fun runDiagnostics() {
        viewModelScope.launch {
            _isDiagnosticsRunning.value = true
            diagnosticsManager.runDiagnostics()
            diagnosticsManager.refreshTelemetry()
            _isDiagnosticsRunning.value = false
        }
    }

    fun executeRepair(actionId: String) {
        viewModelScope.launch {
            diagnosticsManager.executeSafeRepair(actionId)
            runDiagnostics()
        }
    }

    fun emergencyDisconnect() {
        pairingManager.emergencyDisconnect("Emergency killswitch activated by user")
        ScreenCaptureService.stop(getApplication())
        _showDisconnectDialog.value = false
    }

    fun simulateOperatorConnection() {
        pairingManager.simulateIncomingOperatorRequest()
    }

    fun approveIncomingRequest(req: OperatorRequest) {
        pairingManager.approveRequest(req)
        // If request originated from an Android or Windows controller, send PAIR_ACCEPT back
        if (req.controllerId.isNotBlank()) {
            val sessionId = UUID.randomUUID().toString()
            signalingClient.send(
                "PAIR_ACCEPT",
                """{"sessionId":"$sessionId","receiverName":"${identity.deviceName}","status":"APPROVED","fingerprint":"${identity.fingerprint}"}""",
                req.controllerId
            )
        }
    }

    fun rejectIncomingRequest(req: OperatorRequest) {
        pairingManager.rejectRequest(req)
        if (req.controllerId.isNotBlank()) {
            signalingClient.send(
                "PAIR_REJECT",
                """{"status":"DENIED","reason":"User declined connection request"}""",
                req.controllerId
            )
        }
    }

    fun generateNewPairingCode() {
        val code = pairingManager.generateNewPairingCode()
        signalingClient.send(
            "REGISTER_PAIRING_CODE",
            """{"code":"${code.code}","expiresAt":${code.expiresAtMillis}}"""
        )
    }

    override fun onCleared() {
        signalingClient.disconnect()
        super.onCleared()
    }
}

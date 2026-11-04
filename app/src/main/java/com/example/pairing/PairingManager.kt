package com.example.pairing

import android.content.Context
import com.example.security.AuditLogger
import com.example.security.AuditSeverity
import com.example.security.DeviceIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import java.util.UUID

class PairingManager(private val context: Context) {

    private val secureRandom = SecureRandom()
    private val identity = DeviceIdentity.getOrCreateIdentity(context)

    private val _currentPairingCode = MutableStateFlow<PairingCodeData?>(null)
    val currentPairingCode: StateFlow<PairingCodeData?> = _currentPairingCode.asStateFlow()

    private val _activeSession = MutableStateFlow<ActiveSession?>(null)
    val activeSession: StateFlow<ActiveSession?> = _activeSession.asStateFlow()

    private val _pendingRequest = MutableStateFlow<OperatorRequest?>(null)
    val pendingRequest: StateFlow<OperatorRequest?> = _pendingRequest.asStateFlow()

    private val _trustedControllers = MutableStateFlow<List<TrustedController>>(emptyList())
    val trustedControllers: StateFlow<List<TrustedController>> = _trustedControllers.asStateFlow()

    init {
        // Generate initial pairing code
        generateNewPairingCode()
    }

    fun generateNewPairingCode(): PairingCodeData {
        val num = secureRandom.nextInt(900000) + 100000
        val formattedCode = "%03d-%03d".format(num / 1000, num % 1000)
        val expiresAt = System.currentTimeMillis() + (5 * 60 * 1000L) // 5 minutes

        val qrPayload = """
            {
              "deviceId": "${identity.deviceId}",
              "deviceName": "${identity.deviceName}",
              "fingerprint": "${identity.fingerprint}",
              "pairingCode": "$formattedCode",
              "expiresAt": $expiresAt,
              "v": 1
            }
        """.trimIndent()

        val data = PairingCodeData(
            code = formattedCode,
            expiresAtMillis = expiresAt,
            qrPayloadJson = qrPayload
        )
        _currentPairingCode.value = data
        AuditLogger.log("PAIRING", "CODE_GENERATED", "Generated 6-digit code: $formattedCode (expires in 5m)", AuditSeverity.INFO)
        return data
    }

    fun recordIncomingOperatorRequest(
        controllerId: String,
        operatorName: String,
        location: String,
        fingerprint: String,
        scopes: List<String>
    ) {
        val request = OperatorRequest(
            requestId = UUID.randomUUID().toString(),
            controllerId = controllerId,
            operatorName = operatorName,
            operatorLocation = location,
            controllerFingerprint = fingerprint,
            requestedScopes = scopes,
            timestamp = System.currentTimeMillis()
        )
        _pendingRequest.value = request
        AuditLogger.log("PAIRING", "OPERATOR_REQUEST", "Connection request from $operatorName ($location)", AuditSeverity.SECURITY)
    }

    fun approveRequest(request: OperatorRequest) {
        val session = ActiveSession(
            sessionId = UUID.randomUUID().toString(),
            controllerId = request.controllerId,
            operatorName = request.operatorName,
            operatorLocation = request.operatorLocation,
            controllerFingerprint = request.controllerFingerprint,
            startedAtMillis = System.currentTimeMillis(),
            isScreenStreaming = false,
            isInputEnabled = request.requestedScopes.contains("Remote Interaction")
        )
        _activeSession.value = session
        _pendingRequest.value = null

        // Only cryptographically identified Android controllers are retained
        // as trusted devices. Browser/other ephemeral sessions remain session-only.
        val verifiedFingerprint = request.controllerFingerprint.matches(
            Regex("([A-Fa-f0-9]{2}:){23}[A-Fa-f0-9]{2}")
        )
        if (verifiedFingerprint) {
            val existing = _trustedControllers.value.find { it.fingerprint == request.controllerFingerprint }
            val updatedList = if (existing != null) {
                _trustedControllers.value.map {
                    if (it.fingerprint == request.controllerFingerprint) {
                        it.copy(lastConnectedAtMillis = System.currentTimeMillis(), isRevoked = false)
                    } else it
                }
            } else {
                _trustedControllers.value + TrustedController(
                    id = "ctrl_" + UUID.randomUUID().toString().take(8),
                    name = request.operatorName,
                    location = request.operatorLocation,
                    fingerprint = request.controllerFingerprint,
                    pairedAtMillis = System.currentTimeMillis(),
                    lastConnectedAtMillis = System.currentTimeMillis()
                )
            }
            _trustedControllers.value = updatedList
        }

        AuditLogger.log("SESSION", "ESTABLISHED", "Session ${session.sessionId.take(8)} authorized for ${request.operatorName}", AuditSeverity.SECURITY)
    }

    fun setScreenStreaming(active: Boolean) {
        _activeSession.value = _activeSession.value?.copy(isScreenStreaming = active)
    }

    fun rejectRequest(request: OperatorRequest) {
        _pendingRequest.value = null
        AuditLogger.log("PAIRING", "REJECTED_BY_USER", "Denied connection from ${request.operatorName}", AuditSeverity.WARNING)
    }

    fun revokeController(controllerId: String) {
        val target = _trustedControllers.value.find { it.id == controllerId }
        _trustedControllers.value = _trustedControllers.value.map {
            if (it.id == controllerId) it.copy(isRevoked = true) else it
        }
        if (target != null && _activeSession.value?.controllerFingerprint == target.fingerprint) {
            emergencyDisconnect("Trusted controller was revoked by device owner")
        }
        AuditLogger.log("SECURITY", "CONTROLLER_REVOKED", "Revoked credentials for $controllerId", AuditSeverity.CRITICAL)
    }

    fun emergencyDisconnect(reason: String = "Emergency disconnect triggered by user") {
        val currentSession = _activeSession.value
        _activeSession.value = null
        _pendingRequest.value = null
        AuditLogger.log(
            category = "SECURITY",
            action = "EMERGENCY_DISCONNECT",
            details = "$reason (Terminated session ${currentSession?.sessionId?.take(8) ?: "N/A"})",
            severity = AuditSeverity.CRITICAL
        )
    }
}

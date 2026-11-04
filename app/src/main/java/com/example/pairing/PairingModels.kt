package com.example.pairing

data class TrustedController(
    val id: String,
    val name: String,
    val location: String,
    val fingerprint: String,
    val pairedAtMillis: Long,
    val lastConnectedAtMillis: Long,
    val isRevoked: Boolean = false
)

data class OperatorRequest(
    val requestId: String,
    val controllerId: String = "",
    val operatorName: String,
    val operatorLocation: String,
    val controllerFingerprint: String,
    val requestedScopes: List<String>,
    val timestamp: Long
)

data class ActiveSession(
    val sessionId: String,
    val controllerId: String = "",
    val operatorName: String,
    val operatorLocation: String,
    val controllerFingerprint: String,
    val startedAtMillis: Long,
    val isScreenStreaming: Boolean = false,
    val isInputEnabled: Boolean = true
)

data class PairingCodeData(
    val code: String,
    val expiresAtMillis: Long,
    val qrPayloadJson: String
)

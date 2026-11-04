package com.example.controller

enum class AppRole(val title: String) {
    RECEIVER("Allow Remote Control"),
    CONTROLLER("Control Another Device")
}

data class ControllerSession(
    val sessionId: String,
    val targetDeviceId: String,
    val targetDeviceName: String,
    val targetFingerprint: String,
    val sessionToken: String,
    val connectedAtMillis: Long,
    val screenResolution: String = "1080x1920"
)

data class KnownTargetDevice(
    val deviceId: String,
    val name: String,
    val model: String,
    val fingerprint: String,
    val lastConnectedAtMillis: Long,
    val isOnline: Boolean = true
)

data class RemoteTelemetry(
    val batteryPercent: Int = -1,
    val batteryTempC: Float = -1f,
    val storageFree: String = "Unknown",
    val roundTripTimeMs: Int = 0
)

data class RemoteDisplayMetrics(
    val width: Int = 1080,
    val height: Int = 2400,
    val rotation: Int = 0,
    val densityDpi: Int = 420
)

enum class RemoteViewerTab(val title: String) {
    SCREEN("Screen"),
    FILES("Files"),
    APPS("Apps"),
    DIAGNOSTICS("Diagnostics")
}


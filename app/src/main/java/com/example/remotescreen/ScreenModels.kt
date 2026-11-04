package com.example.remotescreen

enum class VideoPreset(
    val label: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int
) {
    HIGH("1080p High (60 FPS)", 1080, 1920, 60, 4000),
    BALANCED("720p Balanced (30 FPS)", 720, 1280, 30, 2000),
    EFFICIENCY("480p Low-Bandwidth (15 FPS)", 480, 854, 15, 800)
}

data class StreamMetrics(
    val currentFps: Int = 30,
    val bitrateKbps: Int = 2000,
    val resolution: String = "720x1280",
    val framesEncoded: Long = 0,
    val bytesTransferred: Long = 0,
    val roundTripTimeMs: Int = 34,
    val packetLossPercent: Float = 0.02f
)

sealed class ScreenStreamState {
    object Idle : ScreenStreamState()
    object RequestingConsent : ScreenStreamState()
    data class Active(val metrics: StreamMetrics, val preset: VideoPreset) : ScreenStreamState()
    object Stopped : ScreenStreamState()
    data class Error(val message: String) : ScreenStreamState()
}

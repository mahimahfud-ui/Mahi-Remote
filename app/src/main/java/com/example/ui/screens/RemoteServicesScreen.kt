package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.remoteinput.MahiAccessibilityService
import com.example.remotescreen.ScreenStreamState
import com.example.remotescreen.VideoPreset
import com.example.ui.MahiViewModel
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DarkCharcoalBg
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceBorder
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.ErrorCoral
import com.example.ui.theme.MintAccent
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber

@Composable
fun RemoteServicesScreen(
    viewModel: MahiViewModel,
    onRequestScreenShare: () -> Unit
) {
    val context = LocalContext.current
    val streamState by viewModel.screenStreamState.collectAsState()
    val videoPreset by viewModel.videoPreset.collectAsState()
    val rootStatus by viewModel.rootDetector.status.collectAsState()
    val isAccessibilityActive by MahiAccessibilityService.isServiceActive.collectAsState()

    var showPresetMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section: MediaProjection Screen Streaming
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkSurfaceBorder),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CastConnected, contentDescription = null, tint = CyanAccent)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "MediaProjection Video Pipeline",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (streamState is ScreenStreamState.Active) MintAccent.copy(alpha = 0.2f)
                                else WarningAmber.copy(alpha = 0.2f)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (streamState is ScreenStreamState.Active) "STREAMING" else "IDLE",
                            color = if (streamState is ScreenStreamState.Active) MintAccent else WarningAmber,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = "Hardware AVC/H.264 virtual display capture with adaptive bitrate transport over WebRTC. Protected screens (FLAG_SECURE) are safeguarded.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                // Video Preset Selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkSurfaceElevated)
                        .clickable { showPresetMenu = true }
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = "Quality Preset", color = TextSecondary, fontSize = 11.sp)
                        Text(text = videoPreset.label, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(text = "Change", color = CyanAccent, fontSize = 12.sp)

                    DropdownMenu(
                        expanded = showPresetMenu,
                        onDismissRequest = { showPresetMenu = false }
                    ) {
                        VideoPreset.values().forEach { preset ->
                            DropdownMenuItem(
                                text = { Text(preset.label) },
                                onClick = {
                                    viewModel.setVideoPreset(preset)
                                    showPresetMenu = false
                                }
                            )
                        }
                    }
                }

                // Live Streaming Metrics when active
                if (streamState is ScreenStreamState.Active) {
                    val metrics = (streamState as ScreenStreamState.Active).metrics
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(DarkCharcoalBg)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ServiceMetricRow("Resolution", metrics.resolution)
                        ServiceMetricRow("Frame Rate", "${metrics.currentFps} FPS")
                        ServiceMetricRow("Bitrate", "${metrics.bitrateKbps} Kbps")
                        ServiceMetricRow("Round-Trip Latency", "${metrics.roundTripTimeMs} ms")
                        ServiceMetricRow("Bytes Streamed", "%.2f MB".format(metrics.bytesTransferred / (1024.0 * 1024.0)))
                    }
                }

                Button(
                    onClick = onRequestScreenShare,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (streamState is ScreenStreamState.Active) ErrorCoral else CyanAccent,
                        contentColor = DarkCharcoalBg
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("toggle_screen_stream_btn")
                ) {
                    Icon(
                        imageVector = if (streamState is ScreenStreamState.Active) Icons.Default.Warning else Icons.Default.CastConnected,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (streamState is ScreenStreamState.Active) "Stop Screen Sharing" else "Start Screen Sharing Consent Flow",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Section: Remote Accessibility Service
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkSurfaceBorder),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AccessibilityNew, contentDescription = null, tint = MintAccent)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Remote Input & Interaction",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isAccessibilityActive) MintAccent.copy(alpha = 0.2f)
                                else WarningAmber.copy(alpha = 0.2f)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isAccessibilityActive) "BOUND & READY" else "NOT BOUND",
                            color = if (isAccessibilityActive) MintAccent else WarningAmber,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = "Enables mouse clicks to taps, drag gestures to swipes, and physical keyboard input for the remote Windows operator. Enforces strict input coordinate bounds.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                OutlinedButton(
                    onClick = {
                        try {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Could not open Accessibility settings", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("open_accessibility_settings_btn"),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanAccent),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(CyanAccent)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Configure in Accessibility Settings")
                }

                // Global Nav test buttons
                Text(
                    text = "Quick Navigation Actions Test:",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NavButton(label = "Back", modifier = Modifier.weight(1f)) {
                        MahiAccessibilityService.performGlobal("BACK")
                    }
                    NavButton(label = "Home", modifier = Modifier.weight(1f)) {
                        MahiAccessibilityService.performGlobal("HOME")
                    }
                    NavButton(label = "Recents", modifier = Modifier.weight(1f)) {
                        MahiAccessibilityService.performGlobal("RECENTS")
                    }
                    NavButton(label = "Notifs", modifier = Modifier.weight(1f)) {
                        MahiAccessibilityService.performGlobal("NOTIFICATIONS")
                    }
                }
            }
        }

        // Section: Optional Magisk / Root Integration
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkSurfaceBorder),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = WarningAmber)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Optional Root Integration",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = if (rootStatus.isRootAvailable) "MAGISK DETECTED" else "NON-ROOT (SECURE)",
                        color = if (rootStatus.isRootAvailable) WarningAmber else MintAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "Device is currently running in Standard Non-Root mode. Rooting is NEVER required for remote screen viewing, input control, file transfer, or diagnostics.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkSurfaceElevated)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Privileged Mode Opt-In", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (rootStatus.isRootAvailable) "Allows signed allowlist commands only" else "Unavailable on unrooted devices",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }

                    Switch(
                        checked = rootStatus.isOptInEnabled,
                        onCheckedChange = { enable ->
                            viewModel.rootDetector.toggleRootOptIn(enable, confirmedByUser = true)
                        },
                        enabled = rootStatus.isRootAvailable,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = WarningAmber,
                            checkedTrackColor = WarningAmber.copy(alpha = 0.3f)
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun ServiceMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = TextSecondary, fontSize = 11.sp)
        Text(text = value, color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun NavButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
        border = ButtonDefaults.outlinedButtonBorder.copy(
            brush = androidx.compose.ui.graphics.SolidColor(DarkSurfaceBorder)
        )
    ) {
        Text(text = label, fontSize = 11.sp)
    }
}

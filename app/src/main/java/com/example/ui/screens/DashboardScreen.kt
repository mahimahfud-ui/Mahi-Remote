package com.example.ui.screens

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
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.remotescreen.ScreenCaptureService
import com.example.remotescreen.ScreenStreamState
import com.example.ui.MainTab
import com.example.ui.MahiViewModel
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.CyanGlow
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
fun DashboardScreen(
    viewModel: MahiViewModel,
    onRequestScreenShare: () -> Unit
) {
    val telemetry by viewModel.diagnosticsManager.telemetry.collectAsState()
    val activeSession by viewModel.pairingManager.activeSession.collectAsState()
    val streamState by viewModel.screenStreamState.collectAsState()
    val pairingCode by viewModel.pairingManager.currentPairingCode.collectAsState()
    val isScreenCaptureActive = ScreenCaptureService.isStreaming()

    val appRole by viewModel.appRole.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Application Role Selector
        RoleSelectorCard(
            currentRole = appRole,
            onRoleSelected = { viewModel.setAppRole(it) }
        )

        // Status Hero Banner
        HeroStatusBanner(
            activeSession = activeSession,
            isStreaming = streamState is ScreenStreamState.Active,
            deviceId = viewModel.identity.deviceId,
            onEmergencyDisconnect = { viewModel.setShowDisconnectDialog(true) }
        )

        // Quick Action Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ActionTile(
                title = if (isScreenCaptureActive) "Stop Share" else "Share Screen",
                subtitle = when {
                    streamState is ScreenStreamState.Connecting -> "Negotiating WebRTC…"
                    streamState is ScreenStreamState.Active -> "Connected • stats unavailable"
                    streamState is ScreenStreamState.Error && isScreenCaptureActive -> "Connection error • stop sharing"
                    streamState is ScreenStreamState.Error -> "Capture unavailable"
                    streamState is ScreenStreamState.Stopped -> "Stopped"
                    else -> "MediaProjection"
                },
                icon = Icons.Default.CastConnected,
                tint = if (isScreenCaptureActive) MintAccent else CyanAccent,
                modifier = Modifier
                    .weight(1f)
                    .testTag("quick_share_screen_btn"),
                onClick = onRequestScreenShare
            )
            ActionTile(
                title = "Pairing Code",
                subtitle = pairingCode?.code ?: "Generate",
                icon = Icons.Default.QrCode,
                tint = CyanAccent,
                modifier = Modifier
                    .weight(1f)
                    .testTag("quick_pairing_btn"),
                onClick = { viewModel.selectTab(MainTab.PAIRING) }
            )
            ActionTile(
                title = "Diagnostics",
                subtitle = "Automated test",
                icon = Icons.Default.Speed,
                tint = WarningAmber,
                modifier = Modifier
                    .weight(1f)
                    .testTag("quick_diagnostics_btn"),
                onClick = { viewModel.selectTab(MainTab.DIAGNOSTICS) }
            )
        }

        // Active Session Card (if any)
        if (activeSession != null) {
            val session = activeSession!!
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MintAccent.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(MintAccent)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "ACTIVE REMOTE OPERATOR",
                                color = MintAccent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                        Text(
                            text = "SESSION ACTIVE",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(DarkSurfaceElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Computer, contentDescription = null, tint = CyanAccent)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = session.operatorName,
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${session.operatorLocation} • Fingerprint: ${session.controllerFingerprint.take(14)}...",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = { viewModel.setShowDisconnectDialog(true) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dashboard_killswitch_button"),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorCoral),
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = androidx.compose.ui.graphics.SolidColor(ErrorCoral)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Emergency Disconnect (Killswitch)", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Hardware & Power Telemetry
        Text(
            text = "HARDWARE & POWER TELEMETRY",
            color = TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TelemetryMetricCard(
                title = "Battery",
                value = "${telemetry.batteryPercent}%",
                subtitle = if (telemetry.isCharging) "Charging (Fast)" else "Discharging",
                icon = Icons.Default.BatteryChargingFull,
                tint = if (telemetry.batteryPercent > 20) MintAccent else WarningAmber,
                modifier = Modifier.weight(1f)
            )
            TelemetryMetricCard(
                title = "Temperature",
                value = "%.1f°C".format(telemetry.batteryTempC),
                subtitle = "${telemetry.batteryVoltageMv} mV",
                icon = Icons.Default.Bolt,
                tint = CyanAccent,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TelemetryMetricCard(
                title = "Network",
                value = telemetry.networkType,
                subtitle = if (telemetry.wifiSsid != "N/A") telemetry.wifiSsid else telemetry.ipAddress,
                icon = Icons.Default.Wifi,
                tint = CyanAccent,
                modifier = Modifier.weight(1f)
            )
            TelemetryMetricCard(
                title = "RAM",
                value = "${telemetry.freeRamMb} MB",
                subtitle = "of ${telemetry.totalRamMb} MB total",
                icon = Icons.Default.Memory,
                tint = MintAccent,
                modifier = Modifier.weight(1f)
            )
        }

        // Device Identity Specs Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkSurfaceBorder),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = CyanAccent)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Device Specifications",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                DetailLine("Device ID", viewModel.identity.deviceId, isMono = true)
                DetailLine("Model", "${telemetry.manufacturer} ${telemetry.model}")
                DetailLine("OS Version", "${telemetry.androidVersion} (API ${telemetry.apiLevel})")
                DetailLine("Security Patch", telemetry.securityPatch)
                DetailLine("System Uptime", telemetry.uptimeFormatted)
                DetailLine(
                    "Internal Storage",
                    "%.1f GB free of %.1f GB".format(telemetry.freeStorageGb, telemetry.totalStorageGb)
                )
            }
        }
    }
}

@Composable
private fun HeroStatusBanner(
    activeSession: com.example.pairing.ActiveSession?,
    isStreaming: Boolean,
    deviceId: String,
    onEmergencyDisconnect: () -> Unit
) {
    val bgBorder = when {
        activeSession != null -> MintAccent
        isStreaming -> CyanAccent
        else -> DarkSurfaceBorder
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(DarkSurface)
            .border(1.dp, bgBorder, RoundedCornerShape(20.dp))
            .padding(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    activeSession != null -> MintAccent
                                    isStreaming -> CyanAccent
                                    else -> WarningAmber
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            activeSession != null -> "CONNECTED TO OPERATOR"
                            isStreaming -> "SCREEN STREAMING ACTIVE"
                            else -> "READY FOR PAIRING"
                        },
                        color = when {
                            activeSession != null -> MintAccent
                            isStreaming -> CyanAccent
                            else -> WarningAmber
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }

                Text(
                    text = deviceId.takeLast(9),
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Text(
                text = "Mahi Remote Control",
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Secure internet-connected remote support platform. Operates within official Android MediaProjection and Accessibility boundaries.",
                color = TextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun ActionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkSurfaceBorder),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(tint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Text(text = title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(text = subtitle, color = TextSecondary, fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
private fun TelemetryMetricCard(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkSurfaceBorder),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(text = title, color = TextSecondary, fontSize = 11.sp)
                Text(text = value, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(text = subtitle, color = TextMuted, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String, isMono: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = TextSecondary, fontSize = 12.sp)
        Text(
            text = value,
            color = TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = if (isMono) FontFamily.Monospace else FontFamily.Default
        )
    }
}

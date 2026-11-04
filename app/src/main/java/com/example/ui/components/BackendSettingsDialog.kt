package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.network.BackendConfig
import com.example.network.HealthCheckResult
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
import kotlinx.coroutines.launch

@Composable
fun BackendSettingsDialog(
    viewModel: MahiViewModel,
    onDismiss: () -> Unit
) {
    val backendConfig = viewModel.backendConfig
    val otaManager = viewModel.otaUpdateManager
    val liveUiManager = viewModel.liveUiManager

    val currentUrl by backendConfig.backendUrl.collectAsState()
    var inputUrl by remember { mutableStateOf(currentUrl) }
    var healthResult by remember { mutableStateOf<HealthCheckResult?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    val availableOta by otaManager.availableOtaRelease.collectAsState()
    val isDownloadingOta by otaManager.isDownloading.collectAsState()
    val otaProgress by otaManager.downloadProgress.collectAsState()
    val otaStatus by otaManager.otaStatusMessage.collectAsState()

    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(16.dp)),
            color = DarkSurface
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lan, contentDescription = null, tint = CyanAccent)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Backend & OTA Settings",
                            color = TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Configure your Windows laptop or remote server address. All HTTP, WebRTC signaling, and live update requests use this endpoint.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Presets
                Text(
                    text = "QUICK PRESETS",
                    color = TextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PresetChip("Windows LAN", BackendConfig.DEFAULT_LAN_URL, inputUrl) { inputUrl = it }
                    PresetChip("Emulator", BackendConfig.EMULATOR_HOST_URL, inputUrl) { inputUrl = it }
                    PresetChip("Cloud", BackendConfig.CLOUD_DEFAULT_URL, inputUrl) { inputUrl = it }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // URL Input Field
                OutlinedTextField(
                    value = inputUrl,
                    onValueChange = { inputUrl = it },
                    label = { Text("Server Base URL", fontSize = 12.sp) },
                    placeholder = { Text("http://192.168.1.105:8080", color = TextMuted) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanAccent,
                        unfocusedBorderColor = DarkSurfaceBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = CyanAccent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_backend_url")
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Health Check / Test Results Card
                if (healthResult != null) {
                    val hr = healthResult!!
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkSurfaceElevated)
                            .border(1.dp, if (hr.isHealthy) MintAccent else ErrorCoral, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (hr.isHealthy) Icons.Default.CheckCircle else Icons.Default.Error,
                                    contentDescription = null,
                                    tint = if (hr.isHealthy) MintAccent else ErrorCoral,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (hr.isHealthy) "Server Healthy (${hr.latencyMs} ms)" else "Server Unreachable",
                                    color = if (hr.isHealthy) MintAccent else ErrorCoral,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (hr.isHealthy) {
                                Text(
                                    text = "Detected LAN: ${hr.detectedLanIp ?: "Local"} | Version: ${hr.version}",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            } else if (hr.errorMessage != null) {
                                Text(
                                    text = hr.errorMessage,
                                    color = ErrorCoral,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Action Buttons: Test Connection & Save
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            isTesting = true
                            scope.launch {
                                // Temporarily test target URL
                                val tempConfig = BackendConfig(viewModel.getApplication())
                                tempConfig.saveBackendUrl(inputUrl)
                                healthResult = tempConfig.testConnection()
                                isTesting = false
                            }
                        },
                        enabled = !isTesting,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanAccent),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).testTag("btn_test_backend_connection")
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = CyanAccent)
                        } else {
                            Text("Test Connection", fontSize = 11.sp)
                        }
                    }

                    Button(
                        onClick = {
                            viewModel.updateBackendUrl(inputUrl)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = DarkCharcoalBg),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).testTag("btn_save_backend_address")
                    ) {
                        Text("Save & Apply", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Native APK OTA Section
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkSurfaceElevated)
                        .padding(12.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Native Android APK OTA (Type B)", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Installed Version: v${otaManager.currentVersionName} (code ${otaManager.currentVersionCode})\nPackage: ${otaManager.expectedPackageName}",
                            color = TextSecondary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )

                        if (otaStatus != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = otaStatus!!, color = CyanAccent, fontSize = 11.sp)
                        }

                        if (isDownloadingOta) {
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { otaProgress },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                color = CyanAccent,
                                trackColor = DarkSurfaceBorder
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { otaManager.checkForAppUpdates() },
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Check OTA", fontSize = 10.sp)
                            }

                            if (availableOta != null) {
                                Button(
                                    onClick = { otaManager.downloadAndInstallApk(availableOta!!) },
                                    enabled = !isDownloadingOta,
                                    colors = ButtonDefaults.buttonColors(containerColor = MintAccent, contentColor = DarkCharcoalBg),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Install v${availableOta!!.versionName}", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Clear / Reset
                TextButton(
                    onClick = {
                        backendConfig.clearBackendUrl()
                        inputUrl = BackendConfig.DEFAULT_LAN_URL
                        viewModel.updateBackendUrl(BackendConfig.DEFAULT_LAN_URL)
                        healthResult = null
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally).testTag("btn_clear_backend_address")
                ) {
                    Text("Reset to Windows LAN Default (192.168.1.105)", color = TextMuted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun PresetChip(
    label: String,
    url: String,
    currentInput: String,
    onSelect: (String) -> Unit
) {
    val isSelected = currentInput.trim().removeSuffix("/") == url.removeSuffix("/")
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isSelected) DarkSurfaceElevated else DarkCharcoalBg)
            .border(1.dp, if (isSelected) CyanAccent else DarkSurfaceBorder, RoundedCornerShape(6.dp))
            .clickable { onSelect(url) }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = if (isSelected) CyanAccent else TextSecondary,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

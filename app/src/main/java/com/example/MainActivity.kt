package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import com.example.liveui.LivePresentationContainer
import com.example.ui.components.BackendSettingsDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.remotescreen.ScreenCaptureService
import com.example.remotescreen.ScreenStreamState
import com.example.ui.MainTab
import com.example.ui.MahiViewModel
import com.example.ui.components.OperatorApprovalDialog
import com.example.ui.screens.AppManagerScreen
import com.example.ui.screens.AuditLogScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.DiagnosticsScreen
import com.example.ui.screens.FileManagerScreen
import com.example.ui.screens.PairingScreen
import com.example.ui.screens.RemoteServicesScreen
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DarkCharcoalBg
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceBorder
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.ErrorCoral
import com.example.ui.theme.MahiRemoteTheme
import com.example.ui.theme.MintAccent
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber

class MainActivity : ComponentActivity() {

    private val viewModel: MahiViewModel by viewModels()

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenCaptureService.EXTRA_PRESET, viewModel.videoPreset.value.name)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else {
            Toast.makeText(this, "Screen share permission was declined", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Notification permission handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MahiRemoteTheme {
                MainAppScaffold(
                    viewModel = viewModel,
                    onRequestScreenShare = { requestScreenShareConsent() }
                )
            }
        }
    }

    private fun requestScreenShareConsent() {
        if (ScreenCaptureService.isStreaming()) {
            ScreenCaptureService.stop(this)
            Toast.makeText(this, "Screen streaming stopped", Toast.LENGTH_SHORT).show()
            return
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = projectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScaffold(
    viewModel: MahiViewModel,
    onRequestScreenShare: () -> Unit
) {
    val appRole by viewModel.appRole.collectAsState()
    val activeControllerSession by viewModel.activeControllerSession.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val pendingRequest by viewModel.pairingManager.pendingRequest.collectAsState()
    val activeSession by viewModel.pairingManager.activeSession.collectAsState()
    val showDisconnectDialog by viewModel.showDisconnectDialog.collectAsState()
    val showBackendSettingsDialog by viewModel.showBackendSettingsDialog.collectAsState()
    val streamState by viewModel.screenStreamState.collectAsState()

    // If Controller has an active session, display the full-screen remote viewer
    if (appRole == com.example.controller.AppRole.CONTROLLER && activeControllerSession != null) {
        com.example.ui.screens.ControllerViewerScreen(viewModel = viewModel)
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = DarkCharcoalBg,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        activeSession != null -> MintAccent
                                        activeControllerSession != null -> MintAccent
                                        streamState is ScreenStreamState.Active -> CyanAccent
                                        else -> WarningAmber
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (appRole == com.example.controller.AppRole.CONTROLLER) "MAHI CONTROLLER" else "MAHI RECEIVER",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        )
                    }
                },
                actions = {
                    // Backend & OTA Settings Button
                    IconButton(
                        onClick = { viewModel.setShowBackendSettingsDialog(true) },
                        modifier = Modifier.testTag("appbar_backend_settings_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lan,
                            contentDescription = "Backend Settings",
                            tint = CyanAccent
                        )
                    }

                    // Quick Role Switcher Button
                    IconButton(
                        onClick = {
                            val newRole = if (appRole == com.example.controller.AppRole.RECEIVER) {
                                com.example.controller.AppRole.CONTROLLER
                            } else {
                                com.example.controller.AppRole.RECEIVER
                            }
                            viewModel.setAppRole(newRole)
                        },
                        modifier = Modifier.testTag("appbar_role_switch_btn")
                    ) {
                        Icon(
                            imageVector = if (appRole == com.example.controller.AppRole.RECEIVER) Icons.Default.PhoneAndroid else Icons.Default.Security,
                            contentDescription = "Switch Role",
                            tint = CyanAccent
                        )
                    }

                    if (appRole == com.example.controller.AppRole.RECEIVER) {
                        IconButton(
                            onClick = { viewModel.setShowDisconnectDialog(true) },
                            modifier = Modifier.testTag("appbar_emergency_btn")
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = "Emergency Disconnect",
                                tint = if (activeSession != null) ErrorCoral else TextMuted
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = DarkSurface
                )
            )
        },
        bottomBar = {
            if (appRole == com.example.controller.AppRole.RECEIVER) {
                NavigationBar(
                    containerColor = DarkSurface,
                    tonalElevation = 8.dp
                ) {
                    val tabs = listOf(
                        MainTab.DASHBOARD to Icons.Default.Dashboard,
                        MainTab.LIVE_UI to Icons.Default.AutoAwesome,
                        MainTab.PAIRING to Icons.Default.QrCode,
                        MainTab.SERVICES to Icons.Default.CastConnected,
                        MainTab.DIAGNOSTICS to Icons.Default.Speed,
                        MainTab.FILES to Icons.Default.Folder,
                        MainTab.APPS to Icons.Default.Apps,
                        MainTab.AUDIT to Icons.Default.Security
                    )

                    tabs.forEach { (tab, icon) ->
                        val isSelected = selectedTab == tab
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = { viewModel.selectTab(tab) },
                            icon = {
                                Icon(
                                    icon,
                                    contentDescription = tab.title,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = tab.title,
                                    fontSize = 9.sp,
                                    maxLines = 1,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = CyanAccent,
                                selectedTextColor = CyanAccent,
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextMuted,
                                indicatorColor = DarkSurfaceElevated
                            ),
                            modifier = Modifier.testTag("tab_${tab.name.lowercase()}")
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (appRole == com.example.controller.AppRole.CONTROLLER) {
                com.example.ui.screens.ControllerDashboardScreen(viewModel = viewModel)
            } else {
                when (selectedTab) {
                    MainTab.DASHBOARD -> DashboardScreen(viewModel, onRequestScreenShare)
                    MainTab.LIVE_UI -> LivePresentationContainer(viewModel, onRequestScreenShare)
                    MainTab.PAIRING -> PairingScreen(viewModel)
                    MainTab.SERVICES -> RemoteServicesScreen(viewModel, onRequestScreenShare)
                    MainTab.DIAGNOSTICS -> DiagnosticsScreen(viewModel)
                    MainTab.FILES -> FileManagerScreen(viewModel)
                    MainTab.APPS -> AppManagerScreen(viewModel)
                    MainTab.AUDIT -> AuditLogScreen(viewModel)
                }
            }
        }
    }

    // Modal: Backend & OTA Settings Dialog
    if (showBackendSettingsDialog) {
        BackendSettingsDialog(
            viewModel = viewModel,
            onDismiss = { viewModel.setShowBackendSettingsDialog(false) }
        )
    }

    // Modal: Operator Approval Request
    if (pendingRequest != null) {
        OperatorApprovalDialog(
            request = pendingRequest!!,
            onApprove = { viewModel.approveIncomingRequest(pendingRequest!!) },
            onReject = { viewModel.rejectIncomingRequest(pendingRequest!!) }
        )
    }

    // Modal: Emergency Disconnect Confirmation
    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.setShowDisconnectDialog(false) },
            containerColor = DarkSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = ErrorCoral)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Emergency Killswitch", color = TextPrimary, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    text = "This will immediately terminate all active remote screen streams, disconnect the Windows operator in Sylhet, and revoke current session tokens.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.emergencyDisconnect() },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorCoral, contentColor = DarkCharcoalBg),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("confirm_emergency_killswitch")
                ) {
                    Text("Sever Connection Now", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.setShowDisconnectDialog(false) }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}



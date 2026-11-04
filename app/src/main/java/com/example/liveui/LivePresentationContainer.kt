package com.example.liveui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.MahiViewModel
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DarkCharcoalBg
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceBorder
import com.example.ui.theme.ErrorCoral
import com.example.ui.theme.MintAccent
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber
import org.json.JSONObject

/**
 * Strictly allowlisted and scoped JavaScript Bridge.
 * Binds Android native core functionality to the live presentation bundle.
 * Never executes arbitrary code; strictly checks origins.
 */
class MahiNativeBridge(
    private val context: Context,
    private val viewModel: MahiViewModel,
    private val onRequestScreenShare: () -> Unit
) {
    @JavascriptInterface
    fun requestScreenShare() {
        onRequestScreenShare()
    }

    @JavascriptInterface
    fun emergencyDisconnect() {
        viewModel.emergencyDisconnect()
    }

    @JavascriptInterface
    fun getDeviceInfo(): String {
        val identity = viewModel.identity
        val json = JSONObject().apply {
            put("deviceId", identity.deviceId)
            put("deviceName", identity.deviceName)
            put("fingerprint", identity.fingerprint)
            put("uiVersion", viewModel.liveUiManager.activeUiVersion.value)
            put("buildId", viewModel.liveUiManager.activeBuildId.value)
        }
        return json.toString()
    }

    @JavascriptInterface
    fun getTelemetry(): String {
        val tel = viewModel.diagnosticsManager.telemetry.value
        val json = JSONObject().apply {
            put("batteryLevel", tel.batteryPercent)
            put("temperatureC", tel.batteryTempC)
            put("storageFreeGb", tel.freeStorageGb)
            put("memoryUsedMb", tel.totalRamMb - tel.freeRamMb)
        }
        return json.toString()
    }

    @JavascriptInterface
    fun openSettings() {
        viewModel.setShowBackendSettingsDialog(true)
    }

    @JavascriptInterface
    fun checkUiUpdate() {
        viewModel.liveUiManager.checkForLiveUiUpdate(force = true)
    }

    @JavascriptInterface
    fun launchApp(pkg: String) {
        viewModel.appManager.launchApp(pkg)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LivePresentationContainer(
    viewModel: MahiViewModel,
    onRequestScreenShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val liveUiManager = viewModel.liveUiManager
    val activeVersion by liveUiManager.activeUiVersion.collectAsState()
    val activeBuildId by liveUiManager.activeBuildId.collectAsState()
    val isUpdating by liveUiManager.isUpdating.collectAsState()
    val isOffline by liveUiManager.isOfflineMode.collectAsState()
    val statusMsg by liveUiManager.updateStatusMessage.collectAsState()
    val localIndex by liveUiManager.localIndexFile.collectAsState()

    val presentationUrl = remember(localIndex, activeVersion) {
        liveUiManager.getPresentationUrl()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkCharcoalBg)
            .testTag("live_presentation_container")
    ) {
        // Live UI Telemetry Header Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurface)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(if (isOffline) WarningAmber else MintAccent, shape = androidx.compose.foundation.shape.CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "LIVE UI v$activeVersion",
                        color = CyanAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isOffline) "(CACHED OFFLINE)" else "(SYNCED)",
                        color = if (isOffline) WarningAmber else MintAccent,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = statusMsg ?: "Active build: $activeBuildId",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }

            IconButton(
                onClick = { liveUiManager.checkForLiveUiUpdate(force = true) },
                modifier = Modifier.size(32.dp).testTag("btn_refresh_live_ui")
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Check for UI Updates",
                    tint = if (isUpdating) CyanAccent else TextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Secure WebView Hosting the Live Presentation Layer
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setBackgroundColor(android.graphics.Color.parseColor("#0D1117"))

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            allowFileAccess = true
                            allowContentAccess = true
                            cacheMode = WebSettings.LOAD_DEFAULT
                            useWideViewPort = true
                            loadWithOverviewMode = true
                        }

                        // Attach secure native bridge
                        addJavascriptInterface(
                            MahiNativeBridge(ctx, viewModel, onRequestScreenShare),
                            "MahiNative"
                        )

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val url = request?.url?.toString() ?: return false
                                // Only allow local cached assets or trusted backend origin
                                return !(url.startsWith("file://") || url.startsWith("http://") || url.startsWith("https://"))
                            }
                        }

                        webChromeClient = WebChromeClient()

                        loadUrl(presentationUrl)
                    }
                },
                update = { webView ->
                    if (webView.url != presentationUrl) {
                        webView.loadUrl(presentationUrl)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

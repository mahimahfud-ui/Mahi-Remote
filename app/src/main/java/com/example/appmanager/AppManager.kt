package com.example.appmanager

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.example.security.AuditLogger
import com.example.security.AuditSeverity

data class AppItem(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val isSystemApp: Boolean,
    val targetSdk: Int
)

class AppManager(private val context: Context) {

    fun getInstalledApps(includeSystem: Boolean = false): List<AppItem> {
        val pm = context.packageManager
        val packages: List<PackageInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }

        return packages.mapNotNull { pkg ->
            val appInfo = pkg.applicationInfo ?: return@mapNotNull null
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (!includeSystem && isSystem) return@mapNotNull null

            val name = pm.getApplicationLabel(appInfo).toString()
            val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkg.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkg.versionCode.toLong()
            }

            AppItem(
                packageName = pkg.packageName,
                appName = name,
                versionName = pkg.versionName ?: "1.0",
                versionCode = vCode,
                isSystemApp = isSystem,
                targetSdk = appInfo.targetSdkVersion
            )
        }.sortedBy { it.appName.lowercase() }
    }

    fun launchApp(packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                AuditLogger.log("APP", "LAUNCHED", "Launched app: $packageName", AuditSeverity.INFO)
                true
            } else {
                AuditLogger.log("APP", "LAUNCH_FAILED", "No launch intent for: $packageName", AuditSeverity.WARNING)
                false
            }
        } catch (e: Exception) {
            AuditLogger.log("APP", "LAUNCH_ERROR", "Error launching $packageName: ${e.message}", AuditSeverity.WARNING)
            false
        }
    }

    fun openAppSettings(packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AuditLogger.log("APP", "SETTINGS_OPENED", "Opened settings for $packageName", AuditSeverity.INFO)
        } catch (e: Exception) {
            AuditLogger.log("APP", "SETTINGS_ERROR", "Failed to open settings: ${e.message}", AuditSeverity.WARNING)
        }
    }

    fun requestUninstall(packageName: String) {
        try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AuditLogger.log("APP", "UNINSTALL_REQUESTED", "Requested system uninstall prompt for $packageName", AuditSeverity.SECURITY)
        } catch (e: Exception) {
            AuditLogger.log("APP", "UNINSTALL_ERROR", "Failed to trigger uninstall: ${e.message}", AuditSeverity.WARNING)
        }
    }
}

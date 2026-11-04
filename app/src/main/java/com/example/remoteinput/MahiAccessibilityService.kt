package com.example.remoteinput

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.security.AllowlistValidator
import com.example.security.AuditLogger
import com.example.security.AuditSeverity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

class MahiAccessibilityService : AccessibilityService() {

    companion object {
        private var instanceRef: WeakReference<MahiAccessibilityService>? = null

        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

        fun isAccessibilityEnabled(context: Context): Boolean {
            val expectedServiceName = "${context.packageName}/${MahiAccessibilityService::class.java.canonicalName}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)
            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.equals(expectedServiceName, ignoreCase = true) ||
                    componentName.contains("MahiAccessibilityService")) {
                    return true
                }
            }
            return false
        }

        fun performTap(x: Float, y: Float, screenW: Int, screenH: Int): Boolean {
            val service = instanceRef?.get() ?: return false
            val validation = AllowlistValidator.validateTap(x, y, screenW, screenH)
            if (validation !is AllowlistValidator.ValidationResult.Valid) return false

            val path = Path().apply {
                moveTo(x, y)
                lineTo(x, y)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 50)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            AuditLogger.log("INPUT", "TAP_EXECUTED", "Tap at ($x, $y)", AuditSeverity.INFO)
            return service.dispatchGesture(gesture, null, null)
        }

        fun performDoubleTap(x: Float, y: Float, screenW: Int, screenH: Int): Boolean {
            val service = instanceRef?.get() ?: return false
            val validation = AllowlistValidator.validateTap(x, y, screenW, screenH)
            if (validation !is AllowlistValidator.ValidationResult.Valid) return false

            val path1 = Path().apply { moveTo(x, y); lineTo(x, y) }
            val stroke1 = GestureDescription.StrokeDescription(path1, 0, 40)
            val path2 = Path().apply { moveTo(x, y); lineTo(x, y) }
            val stroke2 = GestureDescription.StrokeDescription(path2, 100, 40)

            val gesture = GestureDescription.Builder()
                .addStroke(stroke1)
                .addStroke(stroke2)
                .build()

            AuditLogger.log("INPUT", "DOUBLE_TAP_EXECUTED", "Double Tap at ($x, $y)", AuditSeverity.INFO)
            return service.dispatchGesture(gesture, null, null)
        }

        fun performLongPress(x: Float, y: Float, screenW: Int, screenH: Int): Boolean {
            val service = instanceRef?.get() ?: return false
            val validation = AllowlistValidator.validateTap(x, y, screenW, screenH)
            if (validation !is AllowlistValidator.ValidationResult.Valid) return false

            val path = Path().apply {
                moveTo(x, y)
                lineTo(x, y)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 800) // 800ms hold
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            AuditLogger.log("INPUT", "LONG_PRESS_EXECUTED", "Long press (800ms) at ($x, $y)", AuditSeverity.INFO)
            return service.dispatchGesture(gesture, null, null)
        }

        fun performSwipe(
            x1: Float, y1: Float,
            x2: Float, y2: Float,
            durationMs: Long,
            screenW: Int, screenH: Int
        ): Boolean {
            val service = instanceRef?.get() ?: return false
            val validation = AllowlistValidator.validateSwipe(x1, y1, x2, y2, durationMs, screenW, screenH)
            if (validation !is AllowlistValidator.ValidationResult.Valid) return false

            val path = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            AuditLogger.log("INPUT", "SWIPE_EXECUTED", "Swipe ($x1, $y1) -> ($x2, $y2) in ${durationMs}ms", AuditSeverity.INFO)
            return service.dispatchGesture(gesture, null, null)
        }

        fun performDrag(
            x1: Float, y1: Float,
            x2: Float, y2: Float,
            durationMs: Long,
            screenW: Int, screenH: Int
        ): Boolean {
            val service = instanceRef?.get() ?: return false
            val validation = AllowlistValidator.validateSwipe(x1, y1, x2, y2, durationMs, screenW, screenH)
            if (validation !is AllowlistValidator.ValidationResult.Valid) return false

            // Drag gesture: press at x1,y1, move slowly to x2,y2
            val path = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
            val totalDuration = if (durationMs < 400) 600L else durationMs
            val stroke = GestureDescription.StrokeDescription(path, 200, totalDuration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            AuditLogger.log("INPUT", "DRAG_EXECUTED", "Drag ($x1, $y1) -> ($x2, $y2) in ${totalDuration}ms", AuditSeverity.INFO)
            return service.dispatchGesture(gesture, null, null)
        }

        fun performPinchZoom(
            centerX: Float, centerY: Float,
            startSpan: Float, endSpan: Float,
            durationMs: Long,
            screenW: Int, screenH: Int
        ): Boolean {
            val service = instanceRef?.get() ?: return false
            val halfStart = startSpan / 2f
            val halfEnd = endSpan / 2f

            val pathFinger1 = Path().apply {
                moveTo(centerX - halfStart, centerY)
                lineTo(centerX - halfEnd, centerY)
            }
            val pathFinger2 = Path().apply {
                moveTo(centerX + halfStart, centerY)
                lineTo(centerX + halfEnd, centerY)
            }

            val stroke1 = GestureDescription.StrokeDescription(pathFinger1, 0, durationMs)
            val stroke2 = GestureDescription.StrokeDescription(pathFinger2, 0, durationMs)

            val gesture = GestureDescription.Builder()
                .addStroke(stroke1)
                .addStroke(stroke2)
                .build()

            AuditLogger.log("INPUT", "PINCH_EXECUTED", "2-Finger Pinch at ($centerX, $centerY)", AuditSeverity.INFO)
            return service.dispatchGesture(gesture, null, null)
        }

        fun performGlobal(action: String): Boolean {
            val service = instanceRef?.get() ?: return false
            val validation = AllowlistValidator.validateGlobalAction(action)
            if (validation !is AllowlistValidator.ValidationResult.Valid) return false

            val globalCode = when (action.uppercase()) {
                "BACK" -> GLOBAL_ACTION_BACK
                "HOME" -> GLOBAL_ACTION_HOME
                "RECENTS" -> GLOBAL_ACTION_RECENTS
                "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
                "QUICK_SETTINGS" -> GLOBAL_ACTION_QUICK_SETTINGS
                "POWER_DIALOG" -> GLOBAL_ACTION_POWER_DIALOG
                "LOCK_SCREEN" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_LOCK_SCREEN else GLOBAL_ACTION_BACK
                else -> return false
            }

            val success = service.performGlobalAction(globalCode)
            AuditLogger.log("INPUT", "NAV_EXECUTED", "Global navigation: $action (success=$success)", AuditSeverity.INFO)
            return success
        }

        fun adjustVolume(direction: String, context: Context): Boolean {
            return try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val flag = when (direction.uppercase()) {
                    "UP" -> AudioManager.ADJUST_RAISE
                    "DOWN" -> AudioManager.ADJUST_LOWER
                    "MUTE" -> AudioManager.ADJUST_TOGGLE_MUTE
                    else -> return false
                }
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, flag, AudioManager.FLAG_SHOW_UI)
                AuditLogger.log("INPUT", "VOLUME_ADJUSTED", "Volume adjusted: $direction", AuditSeverity.INFO)
                true
            } catch (e: Exception) {
                false
            }
        }

        fun performTextInput(text: String): Boolean {
            val service = instanceRef?.get() ?: return false
            val validation = AllowlistValidator.validateTextInput(text)
            if (validation !is AllowlistValidator.ValidationResult.Valid) return false

            val rootNode = service.rootInActiveWindow ?: return false
            val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

            if (focusedNode != null && focusedNode.isEditable) {
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                AuditLogger.log("INPUT", "TEXT_INJECTED", "Set text (${text.length} chars) into active field (success=$success)", AuditSeverity.INFO)
                return success
            }
            return false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
        _isServiceActive.value = true
        AuditLogger.log("ACCESSIBILITY", "CONNECTED", "Mahi Remote Input Service bound & operational", AuditSeverity.SECURITY)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Window state monitoring
    }

    override fun onInterrupt() {
        AuditLogger.log("ACCESSIBILITY", "INTERRUPTED", "Mahi Remote Accessibility Service interrupted", AuditSeverity.WARNING)
    }

    override fun onDestroy() {
        instanceRef = null
        _isServiceActive.value = false
        AuditLogger.log("ACCESSIBILITY", "DESTROYED", "Mahi Remote Accessibility Service unbound", AuditSeverity.INFO)
        super.onDestroy()
    }
}

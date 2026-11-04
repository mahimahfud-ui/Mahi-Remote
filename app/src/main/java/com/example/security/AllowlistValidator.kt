package com.example.security

object AllowlistValidator {

    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class Rejected(val reason: String) : ValidationResult()
    }

    private val ALLOWED_GLOBAL_ACTIONS = setOf(
        "BACK",
        "HOME",
        "RECENTS",
        "NOTIFICATIONS",
        "QUICK_SETTINGS",
        "LOCK_SCREEN"
    )

    fun validateTap(x: Float, y: Float, screenWidth: Int, screenHeight: Int): ValidationResult {
        if (x < 0 || x > screenWidth || y < 0 || y > screenHeight) {
            val reason = "Tap coordinate ($x, $y) out of display bounds ($screenWidth x $screenHeight)"
            AuditLogger.log("INPUT", "TAP_REJECTED", reason, AuditSeverity.WARNING)
            return ValidationResult.Rejected(reason)
        }
        return ValidationResult.Valid
    }

    fun validateSwipe(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        durationMs: Long,
        screenWidth: Int,
        screenHeight: Int
    ): ValidationResult {
        if (x1 < 0 || x1 > screenWidth || y1 < 0 || y1 > screenHeight ||
            x2 < 0 || x2 > screenWidth || y2 < 0 || y2 > screenHeight) {
            val reason = "Swipe trajectory out of screen bounds"
            AuditLogger.log("INPUT", "SWIPE_REJECTED", reason, AuditSeverity.WARNING)
            return ValidationResult.Rejected(reason)
        }
        if (durationMs !in 50..5000) {
            val reason = "Swipe duration $durationMs ms invalid (must be 50-5000ms)"
            AuditLogger.log("INPUT", "SWIPE_REJECTED", reason, AuditSeverity.WARNING)
            return ValidationResult.Rejected(reason)
        }
        return ValidationResult.Valid
    }

    fun validateGlobalAction(action: String): ValidationResult {
        if (!ALLOWED_GLOBAL_ACTIONS.contains(action.uppercase())) {
            val reason = "Unauthorized global navigation action: '$action'"
            AuditLogger.log("INPUT", "ACTION_REJECTED", reason, AuditSeverity.CRITICAL)
            return ValidationResult.Rejected(reason)
        }
        return ValidationResult.Valid
    }

    fun validateTextInput(text: String): ValidationResult {
        if (text.length > 2000) {
            val reason = "Text input payload exceeds 2000 chars limit"
            AuditLogger.log("INPUT", "TEXT_REJECTED", reason, AuditSeverity.WARNING)
            return ValidationResult.Rejected(reason)
        }
        return ValidationResult.Valid
    }

    fun validateFilePath(path: String, allowedRoots: List<String>): ValidationResult {
        if (path.contains("..")) {
            val reason = "Directory traversal pattern '..' detected in path: $path"
            AuditLogger.log("FILE", "ACCESS_DENIED", reason, AuditSeverity.CRITICAL)
            return ValidationResult.Rejected(reason)
        }
        val isAllowed = allowedRoots.any { root -> path.startsWith(root) }
        if (!isAllowed) {
            val reason = "Attempted access outside permitted scoped directory: $path"
            AuditLogger.log("FILE", "ACCESS_DENIED", reason, AuditSeverity.CRITICAL)
            return ValidationResult.Rejected(reason)
        }
        return ValidationResult.Valid
    }

    fun validateRawCommand(command: String): ValidationResult {
        // Strict prohibition of arbitrary network shell commands
        val reason = "Arbitrary shell command execution is prohibited by security policy: '$command'"
        AuditLogger.log("SECURITY", "SHELL_EXECUTION_BLOCKED", reason, AuditSeverity.CRITICAL)
        return ValidationResult.Rejected(reason)
    }
}

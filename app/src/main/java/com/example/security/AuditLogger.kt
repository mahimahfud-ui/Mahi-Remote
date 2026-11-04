package com.example.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class AuditSeverity {
    INFO,
    WARNING,
    CRITICAL,
    SECURITY
}

data class AuditEvent(
    val id: Long,
    val timestamp: Long,
    val formattedTime: String,
    val category: String,
    val action: String,
    val details: String,
    val severity: AuditSeverity
)

object AuditLogger {
    private val idCounter = AtomicLong(1)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val _events = MutableStateFlow<List<AuditEvent>>(emptyList())
    val events: StateFlow<List<AuditEvent>> = _events.asStateFlow()

    init {
        log(
            category = "SYSTEM",
            action = "INITIALIZED",
            details = "Mahi Remote Security Engine active. Non-root secure sandbox initialized.",
            severity = AuditSeverity.INFO
        )
    }

    fun log(category: String, action: String, details: String, severity: AuditSeverity = AuditSeverity.INFO) {
        val now = System.currentTimeMillis()
        val event = AuditEvent(
            id = idCounter.getAndIncrement(),
            timestamp = now,
            formattedTime = timeFormat.format(Date(now)),
            category = category,
            action = action,
            details = details,
            severity = severity
        )
        // Keep last 150 events in memory
        _events.value = (listOf(event) + _events.value).take(150)
    }

    fun clear() {
        _events.value = emptyList()
    }
}

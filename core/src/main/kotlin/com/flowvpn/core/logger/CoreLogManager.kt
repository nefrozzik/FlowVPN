package com.flowvpn.core.logger

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Уровень логирования.
 */
enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

/**
 * Запись лога ядра или приложения.
 */
data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String,
) {
    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
}

/**
 * Менеджер логов ядра sing-box и VPN-сервиса (Ring Buffer).
 *
 * Хранит до [MAX_LOG_ENTRIES] последних записей в памяти и предоставляет
 * реактивный поток [logs] для отображения в Core Log Viewer.
 */
object CoreLogManager {

    private const val MAX_LOG_ENTRIES = 1000

    private val queue = ConcurrentLinkedQueue<LogEntry>()
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun log(message: String, level: LogLevel = LogLevel.INFO, tag: String = "Core") {
        val entry = LogEntry(
            level = parseLogLevel(message, level),
            tag = tag,
            message = message
        )

        queue.add(entry)
        while (queue.size > MAX_LOG_ENTRIES) {
            queue.poll()
        }

        _logs.value = queue.toList()

        // Дублируем в файловый логгер приложения
        AppLogManager.log(tag, entry.level, message)
    }

    fun clear() {
        queue.clear()
        _logs.value = emptyList()
    }

    private fun parseLogLevel(message: String, defaultLevel: LogLevel): LogLevel {
        val lower = message.lowercase()
        return when {
            lower.contains("error") || lower.contains("fatal") || lower.contains("panic") -> LogLevel.ERROR
            lower.contains("warn") -> LogLevel.WARN
            lower.contains("debug") || lower.contains("trace") -> LogLevel.DEBUG
            else -> defaultLevel
        }
    }
}

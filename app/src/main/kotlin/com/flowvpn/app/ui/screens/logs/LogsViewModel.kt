package com.flowvpn.app.ui.screens.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowvpn.core.logger.CoreLogManager
import com.flowvpn.core.logger.LogEntry
import com.flowvpn.core.logger.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class LogsViewModel : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedLevel = MutableStateFlow<LogLevel?>(null) // null = all
    val selectedLevel: StateFlow<LogLevel?> = _selectedLevel.asStateFlow()

    val filteredLogs: StateFlow<List<LogEntry>> = combine(
        CoreLogManager.logs,
        _searchQuery,
        _selectedLevel
    ) { logs, query, level ->
        logs.filter { entry ->
            (level == null || entry.level == level) &&
            (query.isBlank() || entry.message.contains(query, ignoreCase = true) || entry.tag.contains(query, ignoreCase = true))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setLevel(level: LogLevel?) {
        _selectedLevel.value = level
    }

    private val _crashReport = MutableStateFlow<String?>(null)
    val crashReport: StateFlow<String?> = _crashReport.asStateFlow()

    init {
        checkCrashReport()
    }

    fun checkCrashReport() {
        _crashReport.value = com.flowvpn.core.logger.AppLogManager.getCrashReport()
    }

    fun clearCrashReport() {
        com.flowvpn.core.logger.AppLogManager.clearCrashReport()
        _crashReport.value = null
    }

    fun clearLogs() {
        CoreLogManager.clear()
        com.flowvpn.core.logger.AppLogManager.clearAllLogs()
        _crashReport.value = null
    }

    fun getAllLogsText(): String {
        val crash = _crashReport.value
        val logsText = filteredLogs.value.joinToString("\n") {
            "[${it.formattedTime}] [${it.level.name}] [${it.tag}]: ${it.message}"
        }
        return if (!crash.isNullOrBlank()) {
            "=== CRASH REPORT ===\n$crash\n\n=== LOGS ===\n$logsText"
        } else {
            logsText
        }
    }
}

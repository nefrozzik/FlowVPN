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

    fun clearLogs() {
        CoreLogManager.clear()
    }

    fun getAllLogsText(): String {
        return filteredLogs.value.joinToString("\n") {
            "[${it.formattedTime}] [${it.level.name}] [${it.tag}]: ${it.message}"
        }
    }
}

package com.flowvpn.app.ui.screens.splittunnel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flowvpn.app.FlowVpnApplication
import com.flowvpn.core.model.SplitTunnelConfig
import com.flowvpn.core.model.SplitTunnelMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SplitTunnelingViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as FlowVpnApplication).container.splitTunnelRepository

    val config: StateFlow<SplitTunnelConfig> = repository.config

    private val _allApps = MutableStateFlow<List<AppInfo>>(emptyList())
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _showSystemApps = MutableStateFlow(false)
    val showSystemApps: StateFlow<Boolean> = _showSystemApps.asStateFlow()

    init {
        loadApps()
    }

    fun loadApps() {
        viewModelScope.launch {
            _isLoading.value = true
            _allApps.value = repository.getInstalledApps()
            _isLoading.value = false
        }
    }

    val filteredApps: StateFlow<List<AppInfo>> = combine(
        _allApps,
        _searchQuery,
        _showSystemApps
    ) { apps, query, showSystem ->
        apps.filter { app ->
            (showSystem || !app.isSystemApp) &&
            (query.isBlank() || app.name.contains(query, ignoreCase = true) || app.packageName.contains(query, ignoreCase = true))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleShowSystemApps() {
        _showSystemApps.value = !_showSystemApps.value
    }

    fun setMode(mode: SplitTunnelMode) {
        viewModelScope.launch {
            repository.setMode(mode)
        }
    }

    fun toggleApp(packageName: String) {
        viewModelScope.launch {
            repository.togglePackage(packageName)
        }
    }

    fun selectAllVisible() {
        viewModelScope.launch {
            val visiblePkgs = filteredApps.value.map { it.packageName }
            val current = config.value.selectedPackages
            repository.selectAll(current + visiblePkgs)
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            repository.clearSelection()
        }
    }
}

package com.flowvpn.app.ui.screens.servers

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flowvpn.app.FlowVpnApplication
import com.flowvpn.core.model.ProxyServerConfig
import com.flowvpn.core.model.SubscriptionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Socket

class ServersViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as FlowVpnApplication).container
    private val repository = container.subscriptionRepository

    val selectedServer: StateFlow<ProxyServerConfig?> = container.selectedServer

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _serverLatencies = MutableStateFlow<Map<String, Int?>>(emptyMap())
    val serverLatencies: StateFlow<Map<String, Int?>> = _serverLatencies.asStateFlow()

    private val _isPinging = MutableStateFlow(false)
    val isPinging: StateFlow<Boolean> = _isPinging.asStateFlow()

    val subscriptions: StateFlow<List<SubscriptionInfo>> = repository.getAllSubscriptions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Список всех серверов с учетом поиска
    val filteredServers: StateFlow<List<ProxyServerConfig>> = combine(
        subscriptions,
        _searchQuery,
        _serverLatencies
    ) { subs, query, latencies ->
        val all = subs.flatMap { it.servers }
        val updated = all.map { s ->
            if (latencies.containsKey(s.id)) {
                s.copy(latencyMs = latencies[s.id])
            } else s
        }

        if (query.isBlank()) {
            updated
        } else {
            updated.filter {
                it.name.contains(query, ignoreCase = true) ||
                it.address.contains(query, ignoreCase = true) ||
                it.protocol.displayName.contains(query, ignoreCase = true) ||
                (it.country != null && it.country!!.contains(query, ignoreCase = true))
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var pingJob: Job? = null

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    private val latencyTester = com.flowvpn.core.net.NetworkLatencyTester()

    fun selectServer(server: ProxyServerConfig) {
        container.selectServer(server)
    }

    /**
     * Параллельное тестирование задержки с ограничением конкурентности.
     */
    fun pingAllServers() {
        pingJob?.cancel()
        pingJob = viewModelScope.launch {
            _isPinging.value = true
            val servers = filteredServers.value
            val latencies = _serverLatencies.value.toMutableMap()

            latencyTester.testAllParallel(servers, maxConcurrency = 16)
                .collect { (serverId, latency) ->
                    latencies[serverId] = latency
                    _serverLatencies.value = HashMap(latencies)
                }

            _isPinging.value = false
        }
    }

    fun pingServer(server: ProxyServerConfig) {
        viewModelScope.launch {
            val latency = latencyTester.testTcpLatency(server.address, server.port)
            val current = _serverLatencies.value.toMutableMap()
            current[server.id] = latency
            _serverLatencies.value = current
        }
    }

    fun addOpenFluxServer(server: ProxyServerConfig, selectImmediately: Boolean = true) {
        viewModelScope.launch {
            repository.addServer(server)
            if (selectImmediately) {
                selectServer(server)
            }
        }
    }

    suspend fun checkLocalSocket(host: String = "127.0.0.1", port: Int = 10808, timeoutMs: Int = 1500): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    true
                }
            } catch (_: Exception) {
                false
            }
        }
    }
}

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

data class SubscriptionGroupUiModel(
    val subscriptionId: String,
    val title: String,
    val isFavoriteGroup: Boolean = false,
    val totalServersCount: Int,
    val isCollapsed: Boolean,
    val servers: List<ProxyServerConfig>
)

class ServersViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as FlowVpnApplication).container
    private val repository = container.subscriptionRepository

    val selectedServer: StateFlow<ProxyServerConfig?> = container.selectedServer
    val favoriteServerIds: StateFlow<Set<String>> = container.favoriteServerIds

    private val _collapsedSubscriptionIds = MutableStateFlow<Set<String>>(emptySet())
    val collapsedSubscriptionIds: StateFlow<Set<String>> = _collapsedSubscriptionIds.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _serverLatencies = MutableStateFlow<Map<String, Int?>>(emptyMap())
    val serverLatencies: StateFlow<Map<String, Int?>> = _serverLatencies.asStateFlow()

    private val _isPinging = MutableStateFlow(false)
    val isPinging: StateFlow<Boolean> = _isPinging.asStateFlow()

    val subscriptions: StateFlow<List<SubscriptionInfo>> = repository.getAllSubscriptions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Список всех серверов с учетом поиска (для пинга и глобальных операций)
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
                (it.country != null && it.country!!.contains(query, ignoreCase = true)) ||
                (it.isOutline && "outline".contains(query.trim().lowercase()))
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Серверы, сгруппированные по подпискам, со сворачиванием и закрепленным "Избранным"
    val serverGroups: StateFlow<List<SubscriptionGroupUiModel>> = combine(
        subscriptions,
        favoriteServerIds,
        _collapsedSubscriptionIds,
        _searchQuery,
        _serverLatencies
    ) { subs, favIds, collapsedIds, query, latencies ->
        val groups = mutableListOf<SubscriptionGroupUiModel>()

        val allServersWithLatency = subs.flatMap { it.servers }.map { s ->
            if (latencies.containsKey(s.id)) s.copy(latencyMs = latencies[s.id]) else s
        }

        fun filterServer(s: ProxyServerConfig): Boolean {
            if (query.isBlank()) return true
            return s.name.contains(query, ignoreCase = true) ||
                   s.address.contains(query, ignoreCase = true) ||
                   s.protocol.displayName.contains(query, ignoreCase = true) ||
                   (s.country != null && s.country!!.contains(query, ignoreCase = true)) ||
                   (s.isOutline && "outline".contains(query.trim().lowercase()))
        }

        // 1. Секция "⭐ Избранное"
        val favoriteServers = allServersWithLatency
            .filter { favIds.contains(it.id) }
            .distinctBy { it.id }

        if (favoriteServers.isNotEmpty()) {
            val filteredFavs = favoriteServers.filter(::filterServer)
            if (query.isBlank() || filteredFavs.isNotEmpty()) {
                val isCollapsed = if (query.isNotBlank()) false else collapsedIds.contains("group_favorites")
                groups.add(
                    SubscriptionGroupUiModel(
                        subscriptionId = "group_favorites",
                        title = "⭐ Избранное",
                        isFavoriteGroup = true,
                        totalServersCount = favoriteServers.size,
                        isCollapsed = isCollapsed,
                        servers = filteredFavs
                    )
                )
            }
        }

        // 2. Секции по подпискам
        for (sub in subs) {
            val subServers = sub.servers.map { s ->
                if (latencies.containsKey(s.id)) s.copy(latencyMs = latencies[s.id]) else s
            }
            val filteredSubServers = subServers.filter(::filterServer)

            // Если идет поиск, скрываем пустые группы
            if (query.isNotBlank() && filteredSubServers.isEmpty()) {
                continue
            }

            val isCollapsed = if (query.isNotBlank()) false else collapsedIds.contains(sub.id)
            groups.add(
                SubscriptionGroupUiModel(
                    subscriptionId = sub.id,
                    title = sub.name.ifBlank { "Подписка" },
                    isFavoriteGroup = false,
                    totalServersCount = sub.servers.size,
                    isCollapsed = isCollapsed,
                    servers = filteredSubServers
                )
            )
        }

        groups
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleSubscriptionCollapse(subscriptionId: String) {
        val current = _collapsedSubscriptionIds.value.toMutableSet()
        if (current.contains(subscriptionId)) {
            current.remove(subscriptionId)
        } else {
            current.add(subscriptionId)
        }
        _collapsedSubscriptionIds.value = current
    }

    fun toggleFavorite(serverId: String) {
        container.toggleFavorite(serverId)
    }

    fun isFavorite(serverId: String): Boolean = container.isFavorite(serverId)

    fun deleteServer(server: ProxyServerConfig) {
        viewModelScope.launch {
            val success = repository.deleteServer(server.id)
            if (success) {
                if (container.isFavorite(server.id)) {
                    container.toggleFavorite(server.id)
                }
                if (selectedServer.value?.id == server.id) {
                    container.selectServer(null)
                }
            }
        }
    }

    fun updateServer(server: ProxyServerConfig) {
        viewModelScope.launch {
            val success = repository.updateServer(server)
            if (success) {
                if (selectedServer.value?.id == server.id) {
                    container.selectServer(server)
                }
            }
        }
    }

    fun scanWarpEndpoints(
        warpConfig: com.flowvpn.core.model.WarpConfig? = null,
        onProgress: (checked: Int, total: Int, latestWorking: com.flowvpn.core.warp.WarpScanResult?) -> Unit,
        onComplete: (List<com.flowvpn.core.warp.WarpScanResult>) -> Unit
    ) {
        viewModelScope.launch {
            val results = com.flowvpn.core.warp.WarpScanner.scan(
                warpConfig = warpConfig,
                onProgress = onProgress
            )
            onComplete(results)
        }
    }

    fun importOutlineKey(text: String, onResult: (Int, ProxyServerConfig?) -> Unit) {
        viewModelScope.launch {
            try {
                val trimmed = text.trim()
                val configs = mutableListOf<ProxyServerConfig>()

                if (trimmed.contains("ssconf://", ignoreCase = true)) {
                    // Динамическая ссылка Outline — скачиваем актуальный профиль по HTTPS
                    try {
                        val subManager = com.flowvpn.core.subscription.SubscriptionManager()
                        val subInfo = subManager.fetchSubscription(trimmed)
                        configs.addAll(subInfo.servers)
                    } catch (e: Exception) {
                        Timber.w(e, "Не удалось загрузить ssconf динамический профиль: ${e.message}")
                    }
                }

                if (configs.isEmpty()) {
                    configs.addAll(com.flowvpn.core.parser.OutlineParser.parseMultipleOrText(trimmed))
                }

                // Фильтруем некорректные серверы: у Shadowsocks/Outline обязателен пароль
                val validConfigs = configs.filter {
                    !(it.protocol == com.flowvpn.core.model.ProxyProtocol.SHADOWSOCKS && it.password.isNullOrBlank())
                }

                if (validConfigs.isNotEmpty()) {
                    var addedCount = 0
                    var lastAdded: ProxyServerConfig? = null
                    for (cfg in validConfigs) {
                        repository.addServer(cfg)
                        addedCount++
                        lastAdded = cfg
                    }
                    if (lastAdded != null) {
                        selectServer(lastAdded)
                    }
                    onResult(addedCount, lastAdded)
                } else {
                    val count = repository.importFromText(text)
                    val selected = filteredServers.value.lastOrNull()
                    if (selected != null && count > 0) {
                        selectServer(selected)
                    }
                    onResult(count, selected)
                }
            } catch (e: Exception) {
                Timber.e(e, "Ошибка импорта Outline ключа")
                onResult(0, null)
            }
        }
    }

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

package com.flowvpn.app.ui.screens.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flowvpn.app.FlowVpnApplication
import com.flowvpn.app.FlowVpnApplication.Companion.getConfigDirectory
import com.flowvpn.core.config.SingBoxConfigBuilder
import com.flowvpn.core.model.ProxyProtocol
import com.flowvpn.core.model.ProxyServerConfig
import com.flowvpn.core.model.TlsConfig
import com.flowvpn.core.model.VpnState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as FlowVpnApplication).container
    private val repository = container.subscriptionRepository

    val vpnState: StateFlow<VpnState> = container.vpnState

    /** Текущий выбранный сервер из AppContainer */
    val selectedServer: StateFlow<ProxyServerConfig?> = container.selectedServer

    private val _resolvedCountry = MutableStateFlow<String>("—")
    val resolvedCountry: StateFlow<String> = _resolvedCountry.asStateFlow()

    private val _isRefreshingConfig = MutableStateFlow(false)
    val isRefreshingConfig: StateFlow<Boolean> = _isRefreshingConfig.asStateFlow()

    private val _refreshMessage = MutableStateFlow<String?>(null)
    val refreshMessage: StateFlow<String?> = _refreshMessage.asStateFlow()

    fun clearRefreshMessage() {
        _refreshMessage.value = null
    }

    init {
        // Определение страны сервера по IP / названию
        viewModelScope.launch {
            selectedServer.collect { server ->
                if (server == null) {
                    _resolvedCountry.value = "—"
                    return@collect
                }
                // 1. Попытка быстрого разрешения (из кеша, из флага в названии или поля country)
                val fast = com.flowvpn.core.geoip.GeoIpService.getFastCountry(server)
                if (!fast.isNullOrBlank()) {
                    _resolvedCountry.value = fast
                } else {
                    _resolvedCountry.value = "..."
                }

                // 2. Асинхронное GeoIP определение по IP адресу
                val resolved = com.flowvpn.core.geoip.GeoIpService.resolveCountry(server)
                if (!resolved.isNullOrBlank()) {
                    _resolvedCountry.value = resolved
                    // Сохраняем определенную страну в модель текущего сервера, чтобы сохранить на диск
                    val current = container.selectedServer.value
                    if (current != null && current.id == server.id && current.country != resolved) {
                        container.selectServer(current.copy(country = resolved))
                    }
                } else if (fast == null) {
                    _resolvedCountry.value = "—"
                }
            }
        }

        // Восстановление выбранного сервера или выбор первого доступного из подписок
        viewModelScope.launch {
            repository.getAllSubscriptions().collect { subs ->
                val allServers = subs.flatMap { it.servers }
                if (allServers.isNotEmpty()) {
                    val current = container.selectedServer.value
                    val savedId = container.getSavedServerId()
                    val savedCountry = container.getSavedCountry()
                    val matching = if (current != null) {
                        allServers.find {
                            it.id == current.id ||
                            it.name == current.name ||
                            (it.address == current.address && it.port == current.port)
                        } ?: if (!savedCountry.isNullOrBlank()) {
                            allServers.find { it.country?.equals(savedCountry, ignoreCase = true) == true }
                        } else null
                    } else if (!savedId.isNullOrBlank()) {
                        allServers.find { it.id == savedId }
                    } else if (!savedCountry.isNullOrBlank()) {
                        allServers.find { it.country?.equals(savedCountry, ignoreCase = true) == true }
                    } else null

                    if (matching != null) {
                        container.selectServer(matching)
                        com.flowvpn.core.logger.CoreLogManager.log("Восстановлен выбранный сервер: ${matching.name} [${matching.country ?: "Global"}]", com.flowvpn.core.logger.LogLevel.DEBUG, tag = "Core")
                    } else if (container.selectedServer.value == null) {
                        val first = allServers.first()
                        container.selectServer(first)
                        com.flowvpn.core.logger.CoreLogManager.log("Выбран начальный сервер: ${first.name} [${first.country ?: "Global"}]", com.flowvpn.core.logger.LogLevel.DEBUG, tag = "Core")
                    }
                }
            }
        }

        // Автоматическая проверка устаревания конфигурации при запуске приложения
        viewModelScope.launch {
            kotlinx.coroutines.delay(800)
            val settings = container.settingsRepository.settings.value
            if (settings.autoUpdateSubscriptions) {
                val maxAgeMs = settings.autoUpdateIntervalHours * 3600 * 1000L
                val now = System.currentTimeMillis()
                val subs = repository.getAllSubscriptions().firstOrNull() ?: emptyList()
                val needsUpdate = subs.any { sub ->
                    val last = sub.lastUpdatedMs
                    sub.url.isNotBlank() && (last == null || (now - last > maxAgeMs))
                }
                if (needsUpdate) {
                    com.flowvpn.core.logger.CoreLogManager.log("Обнаружена устаревшая конфигурация (интервал: ${settings.autoUpdateIntervalHours}ч). Автообновление...")
                    refreshConfiguration()
                }
            }
        }
    }

    /**
     * Обновить конфигурацию (все подписки) из сети вручную.
     */
    fun refreshConfiguration() {
        viewModelScope.launch {
            if (_isRefreshingConfig.value) return@launch
            _isRefreshingConfig.value = true
            try {
                com.flowvpn.core.logger.CoreLogManager.log("Запуск обновления конфигураций из сети...")
                val updated = repository.updateAllSubscriptions()
                val totalServers = updated.sumOf { it.servers.size }

                val currentSelected = selectedServer.value
                val newServer = if (currentSelected != null) {
                    updated.flatMap { it.servers }.find { it.id == currentSelected.id || it.name == currentSelected.name }
                        ?: updated.firstOrNull { it.servers.isNotEmpty() }?.servers?.firstOrNull()
                } else {
                    updated.firstOrNull { it.servers.isNotEmpty() }?.servers?.firstOrNull()
                }

                if (newServer != null) {
                    container.selectServer(newServer)
                }

                prepareConfig()
                _refreshMessage.value = "Конфигурация обновлена ($totalServers серверов)"
                com.flowvpn.core.logger.CoreLogManager.log("Конфигурация успешно обновлена: $totalServers серверов")
            } catch (e: Exception) {
                Timber.e(e, "Ошибка обновления конфигурации")
                _refreshMessage.value = "Ошибка обновления: ${e.message}"
                com.flowvpn.core.logger.CoreLogManager.log("Ошибка обновления конфигурации: ${e.message}", com.flowvpn.core.logger.LogLevel.ERROR)
            } finally {
                _isRefreshingConfig.value = false
            }
        }
    }

    /**
     * Подготовить конфигурацию sing-box и вернуть путь к config.json.
     */
    fun prepareConfig(): String? {
        val server = selectedServer.value ?: getDemoServer().also {
            container.selectServer(it)
        }
        com.flowvpn.core.state.VpnStateManager.currentServerName = server.name
        com.flowvpn.core.state.VpnStateManager.activeServerConfig = server

        return try {
            val splitConfig = container.splitTunnelRepository.config.value
            val enabledApps = if (splitConfig.mode == com.flowvpn.core.model.SplitTunnelMode.ALLOW_LIST) {
                splitConfig.selectedPackages.takeIf { it.isNotEmpty() }?.toList()
            } else null

            val excludedApps = when (splitConfig.mode) {
                com.flowvpn.core.model.SplitTunnelMode.ALLOW_LIST -> null
                com.flowvpn.core.model.SplitTunnelMode.DISALLOW_LIST -> {
                    (splitConfig.selectedPackages + getApplication<Application>().packageName).toList()
                }
                com.flowvpn.core.model.SplitTunnelMode.DISABLED -> {
                    listOf(getApplication<Application>().packageName)
                }
            }

            val settings = container.settingsRepository.settings.value

            val isWarpServer = server.id.startsWith("warp-") ||
                    server.protocol == com.flowvpn.core.model.ProxyProtocol.MASQUE ||
                    (server.protocol == com.flowvpn.core.model.ProxyProtocol.WIREGUARD &&
                            (server.name.contains("WARP", ignoreCase = true) || server.address.startsWith("162.159.") || server.address.startsWith("188.114.")))

            val candidateProxy = if (isWarpServer) {
                container.subscriptionRepository.getCachedSubscriptions()
                    .flatMap { it.servers }
                    .firstOrNull { candidate ->
                        candidate.id != server.id &&
                        candidate.protocol != com.flowvpn.core.model.ProxyProtocol.WIREGUARD &&
                        candidate.protocol != com.flowvpn.core.model.ProxyProtocol.MASQUE &&
                        (settings.warpMode != com.flowvpn.core.model.WarpMode.WIREGUARD || (!candidate.isOutline && candidate.prefix.isNullOrBlank()))
                    }
            } else null

            val underlyingProxy = if (isWarpServer && (settings.enableWarpChaining || candidateProxy != null)) {
                candidateProxy
            } else null

            val configDir = getApplication<Application>().getConfigDirectory()
            val underlyingFile = File(configDir, "underlying_proxy.json")
            if (underlyingProxy != null) {
                underlyingFile.writeText(underlyingProxy.toJson().toString(2))
                com.flowvpn.core.logger.CoreLogManager.log(
                    "Cloudflare WARP: цепочка через прокси «${underlyingProxy.name}» (${underlyingProxy.protocol}) для надежного обхода ТСПУ",
                    tag = "WARP"
                )
            } else {
                if (underlyingFile.exists()) underlyingFile.delete()
                if (isWarpServer) {
                    com.flowvpn.core.logger.CoreLogManager.log(
                        "Cloudflare WARP: прямое подключение к ${server.address}:${server.port} (внимание: в РФ прямые подключения к WARP могут блокироваться ТСПУ)",
                        tag = "WARP"
                    )
                }
            }

            val configJson = SingBoxConfigBuilder.build(
                config = server,
                settings = settings,
                enabledApps = enabledApps,
                excludedApps = excludedApps,
                underlyingProxy = underlyingProxy,
            )
            val configFile = File(configDir, "config.json")

            configDir.mkdirs()
            configFile.writeText(configJson)

            Timber.d("HomeViewModel: config.json записан: ${configFile.absolutePath}")
            configFile.absolutePath
        } catch (e: Exception) {
            Timber.e(e, "HomeViewModel: Ошибка записи конфигурации")
            container.updateVpnState(VpnState.Error("Ошибка генерации конфигурации: ${e.message}"))
            null
        }
    }

    fun updateVpnState(state: VpnState) {
        container.updateVpnState(state)
    }

    fun onConnecting() {
        container.updateVpnState(VpnState.Connecting)
    }

    fun onDisconnected() {
        container.updateVpnState(VpnState.Disconnected)
    }

    private fun getDemoServer(): ProxyServerConfig {
        return ProxyServerConfig(
            name = "\uD83C\uDDE9\uD83C\uDDEA Demo Server (Frankfurt)",
            protocol = ProxyProtocol.VLESS,
            address = "example.com",
            port = 443,
            uuid = "00000000-0000-0000-0000-000000000000",
            flow = "xtls-rprx-vision",
            tls = TlsConfig(
                enabled = true,
                serverName = "example.com",
                utlsFingerprint = "chrome",
            ),
            country = "DE",
        )
    }
}

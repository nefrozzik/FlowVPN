package com.flowvpn.core.state

import com.flowvpn.core.model.VpnState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Глобальный менеджер состояния VPN-соединения.
 *
 * Предоставляет реактивный [StateFlow] для наблюдения за жизненным циклом
 * туннеля из UI (:app) и обновления статуса из системной службы [FlowVpnService] (:vpn).
 */
object VpnStateManager {

    private val _vpnState = MutableStateFlow<VpnState>(VpnState.Disconnected)
    val vpnState: StateFlow<VpnState> = _vpnState.asStateFlow()

    /** Имя текущего подключенного сервера */
    var currentServerName: String? = null

    /** Активная конфигурация выбранного сервера для прокси-туннелирования */
    var activeServerConfig: com.flowvpn.core.model.ProxyServerConfig? = null

    /**
     * Обновить состояние VPN.
     */
    fun updateState(newState: VpnState) {
        _vpnState.value = newState
    }

    /**
     * Установить состояние "Подключено".
     */
    fun setConnected(serverName: String, protocol: com.flowvpn.core.model.ProxyProtocol? = null) {
        currentServerName = serverName
        _vpnState.value = VpnState.Connected(
            serverName = serverName,
            protocol = protocol,
        )
    }

    /**
     * Установить состояние "Отключено".
     */
    fun setDisconnected() {
        _vpnState.value = VpnState.Disconnected
    }

    /**
     * Установить ошибку соединения.
     */
    fun setError(message: String) {
        _vpnState.value = VpnState.Error(message)
    }
}

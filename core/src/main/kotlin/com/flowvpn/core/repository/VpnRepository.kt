package com.flowvpn.core.repository

import com.flowvpn.core.model.ProxyServerConfig
import com.flowvpn.core.model.VpnState
import kotlinx.coroutines.flow.StateFlow

/**
 * Интерфейс репозитория VPN (Clean Architecture boundary).
 *
 * Определяет контракт между Domain-слоем и Data/VPN-слоем.
 * Реализация ([com.flowvpn.vpn.BoxServiceManager]) находится в модуле :vpn,
 * а UI-слой взаимодействует только через этот интерфейс.
 */
interface VpnRepository {

    /** Реактивный поток текущего состояния VPN */
    val vpnState: StateFlow<VpnState>

    /**
     * Запустить VPN-соединение с указанной конфигурацией.
     *
     * Метод не блокирует — состояние обновляется через [vpnState].
     * Вызывает переход: Disconnected → Connecting → Connected | Error
     *
     * @param config конфигурация прокси-сервера для подключения
     * @throws IllegalStateException если VPN уже активен
     */
    suspend fun connect(config: ProxyServerConfig)

    /**
     * Остановить текущее VPN-соединение.
     *
     * Вызывает переход: Connected → Disconnecting → Disconnected
     */
    suspend fun disconnect()

    /**
     * Получить текущую активную конфигурацию сервера.
     *
     * @return конфигурацию подключённого сервера или null если не подключён
     */
    fun getActiveServer(): ProxyServerConfig?
}

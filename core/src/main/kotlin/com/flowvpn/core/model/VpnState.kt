package com.flowvpn.core.model

/**
 * Состояние VPN-соединения.
 *
 * Sealed interface обеспечивает исчерпывающую обработку состояний
 * через when-выражения (exhaustive when) в UI и ViewModel.
 *
 * Граф переходов:
 * ```
 * Disconnected ──► Connecting ──► Connected
 *       ▲              │               │
 *       │              ▼               ▼
 *       └────── Error          Disconnecting
 *                                    │
 *                                    ▼
 *                              Disconnected
 * ```
 */
sealed interface VpnState {

    /** VPN не подключён — начальное состояние */
    data object Disconnected : VpnState

    /** Устанавливается соединение с сервером */
    data object Connecting : VpnState

    /**
     * VPN активен и туннелирует трафик.
     *
     * @param serverName отображаемое имя подключённого сервера
     * @param startTimeMillis System.currentTimeMillis() момента подключения
     * @param protocol протокол, через который подключены
     */
    data class Connected(
        val serverName: String,
        val startTimeMillis: Long = System.currentTimeMillis(),
        val protocol: ProxyProtocol? = null,
    ) : VpnState

    /**
     * Ошибка подключения или работы ядра.
     *
     * @param message человекочитаемое описание ошибки
     * @param cause исходное исключение (для логирования)
     */
    data class Error(
        val message: String,
        val cause: Throwable? = null,
    ) : VpnState

    /** Соединение разрывается (cleanup) */
    data object Disconnecting : VpnState
}

package com.flowvpn.core.model

/**
 * Режим раздельного туннелирования (Split Tunneling).
 */
enum class SplitTunnelMode(val title: String, val description: String) {
    /** Выключено — весь трафик устройства направляется через VPN */
    DISABLED(
        title = "Выключено",
        description = "Все приложения используют VPN"
    ),

    /** Только выбранные приложения используют VPN (Whitelist / addAllowedApplication) */
    ALLOW_LIST(
        title = "Проксировать только выбранные",
        description = "Через VPN идут только отмеченные приложения"
    ),

    /** Все приложения используют VPN, кроме выбранных (Blacklist / addDisallowedApplication) */
    DISALLOW_LIST(
        title = "Обходить VPN для выбранных",
        description = "Отмеченные приложения идут напрямую в обход VPN"
    )
}

/**
 * Конфигурация Split Tunneling.
 */
data class SplitTunnelConfig(
    val mode: SplitTunnelMode = SplitTunnelMode.DISABLED,
    val selectedPackages: Set<String> = emptySet(),
)

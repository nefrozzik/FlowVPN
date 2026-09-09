package com.flowvpn.core.model

/**
 * Пресеты DNS-провайдеров, поддерживаемые в FlowVPN (в стиле Amnezia VPN).
 */
enum class DnsProvider(
    val displayName: String,
    val description: String,
    val dohUrl: String,
    val primaryIp: String,
) {
    CLOUDFLARE(
        displayName = "Cloudflare (1.1.1.1)",
        description = "Высокая скорость и приватность",
        dohUrl = "https://1.1.1.1/dns-query",
        primaryIp = "1.1.1.1",
    ),
    GOOGLE(
        displayName = "Google (8.8.8.8)",
        description = "Максимальная надежность и стабильность",
        dohUrl = "https://8.8.8.8/dns-query",
        primaryIp = "8.8.8.8",
    ),
    ADGUARD(
        displayName = "AdGuard DNS",
        description = "Блокировка рекламы, трекеров и фишинга",
        dohUrl = "https://94.140.14.14/dns-query",
        primaryIp = "94.140.14.14",
    ),
    QUAD9(
        displayName = "Quad9 (9.9.9.9)",
        description = "Защита от вредоносных сайтов и атак",
        dohUrl = "https://9.9.9.9/dns-query",
        primaryIp = "9.9.9.9",
    ),
    SYSTEM(
        displayName = "Системный DNS (ISP)",
        description = "DNS вашего текущего провайдера связи",
        dohUrl = "",
        primaryIp = "local",
    ),
    CUSTOM(
        displayName = "Пользовательский DoH / DNS",
        description = "Собственный адрес сервера DNS или DoH URL",
        dohUrl = "",
        primaryIp = "",
    );
}

/**
 * Глобальные настройки приложения и сетевого стека в стиле Amnezia VPN.
 *
 * @param dnsProvider выбранный провайдер DNS
 * @param customDnsUrl URL или IP кастомного DNS при выборе [DnsProvider.CUSTOM]
 * @param bypassLan обход локальной сети (доступ к 192.168.x.x, роутерам, принтерам без VPN)
 * @param bypassRussianTraffic обход сайтов РФ (маршрутизация .ru, .рф, банков и госсервисов напрямую)
 * @param killSwitch блокировка всего трафика при отключении или сбое VPN
 * @param blockIpv6 блокировка IPv6 для предотвращения утечек реального адреса провайдера
 * @param mtu размер максимального блока данных (MTU)
 * @param fakeDns использование FakeDNS для ускорения и защиты запросов
 * @param sniffing перехват и определение протокола соединений
 * @param autoConnect автоматическое подключение при запуске приложения
 */
data class AppSettings(
    val dnsProvider: DnsProvider = DnsProvider.CLOUDFLARE,
    val customDnsUrl: String = "https://dns.google/dns-query",
    val bypassLan: Boolean = true,
    val bypassRussianTraffic: Boolean = true,
    val killSwitch: Boolean = false,
    val blockIpv6: Boolean = true,
    val mtu: Int = 1500,
    val fakeDns: Boolean = true,
    val sniffing: Boolean = true,
    val autoConnect: Boolean = false,
    val autoUpdateSubscriptions: Boolean = true,
    val autoUpdateIntervalHours: Int = 24,
    val rootTethering: Boolean = false,
) {
    /**
     * Получить эффективный адрес DNS для передачи в конфигурацию sing-box.
     */
    fun getEffectiveDnsAddress(): String {
        return when (dnsProvider) {
            DnsProvider.SYSTEM -> "local"
            DnsProvider.CUSTOM -> customDnsUrl.trim().ifEmpty { "https://cloudflare-dns.com/dns-query" }
            else -> dnsProvider.dohUrl
        }
    }
}

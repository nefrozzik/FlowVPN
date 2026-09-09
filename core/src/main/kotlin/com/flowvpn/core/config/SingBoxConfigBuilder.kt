package com.flowvpn.core.config

import com.flowvpn.core.model.ProxyProtocol
import com.flowvpn.core.model.ProxyServerConfig

/**
 * Генератор JSON-конфигурации для sing-box core.
 *
 * Конвертирует [ProxyServerConfig] в полноценный `config.json`,
 * который sing-box может загрузить и исполнить.
 *
 * Структура конфига sing-box:
 * ```json
 * {
 *   "log": { ... },
 *   "dns": { "servers": [...], "rules": [...] },
 *   "inbounds": [{ "type": "tun", ... }],
 *   "outbounds": [{ "type": "<protocol>", ... }, { "type": "direct" }, ...],
 *   "route": { "rules": [...] }
 * }
 * ```
 *
 * TUN inbound настраивается на платформенный режим (platform.http_proxy)
 * и перехватывает весь трафик через маршруты 0.0.0.0/0 и ::/0.
 * auto_route и strict_route обеспечивают корректную маршрутизацию на Android.
 */
object SingBoxConfigBuilder {

    /**
     * Собрать полную конфигурацию sing-box для указанного сервера с настройками сети.
     *
     * @param config конфигурация прокси-сервера
     * @param settings глобальные сетевые настройки в стиле Amnezia VPN
     * @param enabledApps список пакетов для Per-App VPN (include), null = все
     * @param excludedApps список пакетов для исключения из VPN, null = нет
     * @return JSON-строка конфигурации для записи в файл
     */
    fun build(
        config: ProxyServerConfig,
        settings: com.flowvpn.core.model.AppSettings,
        enabledApps: List<String>? = null,
        excludedApps: List<String>? = null,
    ): String {
        val outbound = buildOutbound(config)
        val dns = buildDns(settings)
        val inbound = buildTunInbound(settings, enabledApps, excludedApps)
        val route = buildRoute(settings)

        return """
        {
            "log": {
                "level": "debug",
                "timestamp": true
            },
            $dns,
            "inbounds": [$inbound],
            "outbounds": [
                $outbound,
                {"type": "direct", "tag": "direct"},
                {"type": "block", "tag": "block"},
                {"type": "dns", "tag": "dns-out"}
            ],
            $route
        }
        """.trimIndent()
    }

    /**
     * Перегрузка для обратной совместимости.
     */
    fun build(
        config: ProxyServerConfig,
        enabledApps: List<String>? = null,
        excludedApps: List<String>? = null,
        dnsAddress: String = "https://dns.google/dns-query",
    ): String {
        return build(
            config = config,
            settings = com.flowvpn.core.model.AppSettings(
                customDnsUrl = dnsAddress,
                dnsProvider = if (dnsAddress == "local") com.flowvpn.core.model.DnsProvider.SYSTEM else com.flowvpn.core.model.DnsProvider.CUSTOM,
            ),
            enabledApps = enabledApps,
            excludedApps = excludedApps,
        )
    }

    /**
     * Сгенерировать JSON-блок outbound для конкретного протокола.
     *
     * Каждый протокол имеет свою структуру полей в sing-box config:
     * - VLESS: server, server_port, uuid, flow, tls, transport
     * - VMess: server, server_port, uuid, alter_id, security, tls, transport
     * - Shadowsocks: server, server_port, method, password
     * - Trojan: server, server_port, password, tls, transport
     * - Hysteria2: server, server_port, password, up_mbps, down_mbps, obfs, tls
     * - WireGuard: server, server_port, private_key, peer_public_key, local_address
     */
    private fun buildOutbound(config: ProxyServerConfig): String {
        val base = """
            "type": "${config.protocol.singBoxType}",
            "tag": "proxy",
            "server": "${config.address}",
            "server_port": ${config.port}
        """.trimIndent()

        val protocolFields = when (config.protocol) {
            ProxyProtocol.VLESS -> buildVlessFields(config)
            ProxyProtocol.VMESS -> buildVmessFields(config)
            ProxyProtocol.SHADOWSOCKS -> buildShadowsocksFields(config)
            ProxyProtocol.TROJAN -> buildTrojanFields(config)
            ProxyProtocol.HYSTERIA2 -> buildHysteria2Fields(config)
            ProxyProtocol.TUIC -> buildTuicFields(config)
            ProxyProtocol.WIREGUARD -> buildWireguardFields(config)
            ProxyProtocol.SOCKS5 -> buildSocksFields(config)
            ProxyProtocol.HTTP -> buildHttpFields(config)
        }

        return "{$base,$protocolFields}"
    }

    private fun buildSocksFields(config: ProxyServerConfig): String {
        val parts = mutableListOf<String>()
        config.password?.let { parts += """"password": "$it"""" }
        return parts.joinToString(",\n")
    }

    private fun buildHttpFields(config: ProxyServerConfig): String {
        val parts = mutableListOf<String>()
        config.password?.let { parts += """"password": "$it"""" }
        config.tls?.let { parts += buildTlsBlock(it) }
        return parts.joinToString(",\n")
    }

    private fun buildVlessFields(config: ProxyServerConfig): String {
        val parts = mutableListOf<String>()
        parts += """"uuid": "${config.uuid}""""

        config.flow?.let { parts += """"flow": "$it"""" }

        config.tls?.let { parts += buildTlsBlock(it) }
        config.transport?.let { parts += buildTransportBlock(it) }

        return parts.joinToString(",\n")
    }

    private fun buildVmessFields(config: ProxyServerConfig): String {
        val parts = mutableListOf<String>()
        parts += """"uuid": "${config.uuid}""""
        parts += """"alter_id": ${config.alterId}"""
        parts += """"security": "${config.method ?: "auto"}""""

        config.tls?.let { parts += buildTlsBlock(it) }
        config.transport?.let { parts += buildTransportBlock(it) }

        return parts.joinToString(",\n")
    }

    private fun buildShadowsocksFields(config: ProxyServerConfig): String {
        return """
            "method": "${config.method}",
            "password": "${config.password}"
        """.trimIndent()
    }

    private fun buildTrojanFields(config: ProxyServerConfig): String {
        val parts = mutableListOf<String>()
        parts += """"password": "${config.password}""""

        config.tls?.let { parts += buildTlsBlock(it) }
        config.transport?.let { parts += buildTransportBlock(it) }

        return parts.joinToString(",\n")
    }

    private fun buildHysteria2Fields(config: ProxyServerConfig): String {
        val parts = mutableListOf<String>()
        parts += """"password": "${config.password}""""

        config.upMbps?.let { parts += """"up_mbps": $it""" }
        config.downMbps?.let { parts += """"down_mbps": $it""" }

        config.obfsPassword?.let {
            parts += """
                "obfs": {
                    "type": "salamander",
                    "password": "$it"
                }
            """.trimIndent()
        }

        // Hysteria2 требует TLS
        val tls = config.tls ?: com.flowvpn.core.model.TlsConfig(enabled = true)
        parts += buildTlsBlock(tls)

        return parts.joinToString(",\n")
    }

    private fun buildTuicFields(config: ProxyServerConfig): String {
        val parts = mutableListOf<String>()
        parts += """"uuid": "${config.uuid}""""
        parts += """"password": "${config.password}""""

        config.congestionControl?.let {
            parts += """"congestion_control": "$it""""
        }

        val tls = config.tls ?: com.flowvpn.core.model.TlsConfig(enabled = true)
        parts += buildTlsBlock(tls)

        return parts.joinToString(",\n")
    }

    private fun buildWireguardFields(config: ProxyServerConfig): String {
        val parts = mutableListOf<String>()
        parts += """"private_key": "${config.privateKey}""""
        parts += """"peer_public_key": "${config.peerPublicKey}""""

        config.preSharedKey?.let { parts += """"pre_shared_key": "$it"""" }

        config.localAddresses?.let { addrs ->
            val addrList = addrs.joinToString(",") { "\"$it\"" }
            parts += """"local_address": [$addrList]"""
        }

        config.reserved?.let { bytes ->
            parts += """"reserved": [${bytes.joinToString(",")}]"""
        }

        config.wireguardMtu?.let { parts += """"mtu": $it""" }

        return parts.joinToString(",\n")
    }

    /**
     * Генерация TLS-блока.
     * Поддерживает стандартный TLS, uTLS fingerprinting и Reality.
     */
    private fun buildTlsBlock(tls: com.flowvpn.core.model.TlsConfig): String {
        val fields = mutableListOf<String>()
        fields += """"enabled": ${tls.enabled}"""

        tls.serverName?.let { fields += """"server_name": "$it"""" }

        if (tls.insecure) {
            fields += """"insecure": true"""
        }

        tls.alpn?.let { alpnList ->
            val alpnJson = alpnList.joinToString(",") { "\"$it\"" }
            fields += """"alpn": [$alpnJson]"""
        }

        val effectiveFingerprint = tls.utlsFingerprint ?: if (tls.realityPublicKey != null) "chrome" else null
        effectiveFingerprint?.let {
            fields += """
                "utls": {
                    "enabled": true,
                    "fingerprint": "$it"
                }
            """.trimIndent()
        }

        // Reality — специальный режим TLS для обхода DPI
        if (tls.realityPublicKey != null) {
            val realityFields = mutableListOf<String>()
            realityFields += """"enabled": true"""
            realityFields += """"public_key": "${tls.realityPublicKey}""""
            tls.realityShortId?.let { realityFields += """"short_id": "$it"""" }
            fields += """"reality": {${realityFields.joinToString(",")}}"""
        }

        return """"tls": {${fields.joinToString(",")}}"""
    }

    /**
     * Генерация блока транспортного уровня.
     * sing-box поддерживает: ws, grpc, http, tcp, quic.
     */
    private fun buildTransportBlock(transport: com.flowvpn.core.model.TransportConfig): String {
        val fields = mutableListOf<String>()
        fields += """"type": "${transport.type}""""

        transport.path?.let { fields += """"path": "$it"""" }
        transport.host?.let { fields += """"host": "$it"""" }
        transport.serviceName?.let { fields += """"service_name": "$it"""" }

        transport.headers?.let { headers ->
            val headerPairs = headers.entries.joinToString(",") { (k, v) -> """"$k": "$v"""" }
            fields += """"headers": {$headerPairs}"""
        }

        return """"transport": {${fields.joinToString(",")}}"""
    }

    /**
     * Генерация TUN inbound с учетом настроек MTU, Kill Switch, IPv6 и Sniffing.
     */
    private fun buildTunInbound(
        settings: com.flowvpn.core.model.AppSettings,
        enabledApps: List<String>?,
        excludedApps: List<String>?,
    ): String {
        val platformBlock = buildString {
            append(""""http_proxy": {"enabled": false}""")

            if (!enabledApps.isNullOrEmpty()) {
                val appsList = enabledApps.joinToString(",") { "\"$it\"" }
                append(""","include_package": [$appsList]""")
            } else if (!excludedApps.isNullOrEmpty()) {
                val appsList = excludedApps.joinToString(",") { "\"$it\"" }
                append(""","exclude_package": [$appsList]""")
            }
        }

        val inet6AddressField = if (settings.blockIpv6) {
            ""
        } else {
            """"inet6_address": "fdfe:dcba:9876::1/126","""
        }

        return """
        {
            "type": "tun",
            "tag": "tun-in",
            "inet4_address": "172.19.0.1/30",
            $inet6AddressField
            "mtu": ${settings.mtu},
            "auto_route": true,
            "strict_route": ${settings.killSwitch},
            "sniff": ${settings.sniffing},
            "sniff_override_destination": false,
            "platform": {$platformBlock}
        }
        """.trimIndent()
    }

    /**
     * Генерация DNS-конфигурации с поддержкой DoH пресетов, системного DNS,
     * кастомного DNS, FakeDNS и блокировки IPv6 (strategy: ipv4_only).
     */
    private fun buildDns(settings: com.flowvpn.core.model.AppSettings): String {
        val dnsAddress = settings.getEffectiveDnsAddress()
        val strategy = if (settings.blockIpv6) "ipv4_only" else "prefer_ipv4"

        val directDnsServer = """
            {
                "tag": "direct-dns",
                "address": "local",
                "detour": "direct"
            }
        """.trimIndent()

        val remoteDnsServer = if (dnsAddress == "local") {
            """
            {
                "tag": "remote-dns",
                "address": "local",
                "detour": "direct"
            }
            """.trimIndent()
        } else {
            """
            {
                "tag": "remote-dns",
                "address": "$dnsAddress",
                "address_resolver": "direct-dns",
                "detour": "proxy"
            }
            """.trimIndent()
        }

        val dnsServers = mutableListOf<String>()
        dnsServers += remoteDnsServer
        dnsServers += directDnsServer

        if (settings.fakeDns) {
            dnsServers += """
                {
                    "type": "fakeip",
                    "tag": "fakeip-dns",
                    "inet4_range": "198.18.0.0/15"
                }
            """.trimIndent()
        }

        val dnsRules = mutableListOf<String>()
        dnsRules += """
            {
                "outbound": "direct",
                "server": "direct-dns"
            }
        """.trimIndent()

        // Сайты РФ всегда через direct-dns (системный резолвер устройства)
        dnsRules += """
            {
                "domain_suffix": [
                    ".ru",
                    ".xn--p1ai",
                    ".su",
                    ".by",
                    ".kz"
                ],
                "server": "direct-dns"
            }
        """.trimIndent()

        if (settings.fakeDns) {
            dnsRules += """
                {
                    "query_type": ["A", "AAAA"],
                    "server": "fakeip-dns"
                }
            """.trimIndent()
        }

        val serversJson = dnsServers.joinToString(",\n")
        val rulesJson = dnsRules.joinToString(",\n")

        return """
            "dns": {
                "servers": [
                    $serversJson
                ],
                "rules": [
                    $rulesJson
                ],
                "final": "remote-dns",
                "strategy": "$strategy",
                "independent_cache": true
            }
        """.trimIndent()
    }

    /**
     * Генерация маршрутов.
     *
     * Поддерживает:
     * - Перенаправление DNS-трафика в sing-box DNS (hijack-dns / dns-out)
     * - Блокировку IPv6 (защита от утечек)
     * - Обход локальной сети (Bypass LAN)
     * - Обход сайтов РФ (Российские сервисы напрямую: .ru, .рф, банки, госуслуги)
     */
    private fun buildRoute(settings: com.flowvpn.core.model.AppSettings): String {
        val rules = mutableListOf<String>()

        // 1. DNS трафик — перехват встроенным DNS-модулем
        rules += """
            {
                "protocol": "dns",
                "action": "hijack-dns"
            }
        """.trimIndent()

        // 2. Блокировка IPv6 при включенной защите от утечек
        if (settings.blockIpv6) {
            rules += """
                {
                    "ip_version": 6,
                    "outbound": "block"
                }
            """.trimIndent()
        }

        // 3. Обход локальной сети (Bypass LAN)
        if (settings.bypassLan) {
            rules += """
                {
                    "ip_is_private": true,
                    "outbound": "direct"
                }
            """.trimIndent()
        }

        // 4. Обход сайтов РФ (Bypass Russian traffic) через domain_suffix
        if (settings.bypassRussianTraffic) {
            rules += """
                {
                    "domain_suffix": [
                        ".ru",
                        ".xn--p1ai",
                        ".su",
                        ".by",
                        ".kz"
                    ],
                    "outbound": "direct"
                }
            """.trimIndent()
        }

        val rulesJson = rules.joinToString(",\n")

        return """
            "route": {
                "rules": [
                    $rulesJson
                ],
                "auto_detect_interface": true,
                "default_domain_resolver": "direct-dns",
                "final": "proxy"
            }
        """.trimIndent()
    }

    /**
     * Маппинг [ProxyProtocol] → имя типа outbound в sing-box.
     */
    private val ProxyProtocol.singBoxType: String
        get() = when (this) {
            ProxyProtocol.VLESS -> "vless"
            ProxyProtocol.VMESS -> "vmess"
            ProxyProtocol.SHADOWSOCKS -> "shadowsocks"
            ProxyProtocol.TROJAN -> "trojan"
            ProxyProtocol.HYSTERIA2 -> "hysteria2"
            ProxyProtocol.TUIC -> "tuic"
            ProxyProtocol.WIREGUARD -> "wireguard"
            ProxyProtocol.SOCKS5 -> "socks"
            ProxyProtocol.HTTP -> "http"
        }
}

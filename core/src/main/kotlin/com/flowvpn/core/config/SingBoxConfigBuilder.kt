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
     * Домены транспортных сервисов OpenFlux (Яндекс Документы, OneMe/MAX, VK).
     * Должны ВСЕГДА идти в direct и резолвиться через direct-dns при работе OpenFlux,
     * иначе трафик скрытого туннеля захватится интерфейсом TUN и произойдет вечная петля.
     */
    private val OPENFLUX_CARRIER_DOMAINS = listOf(
        "yandex.ru", "yandex.net", "ya.ru", "doc.yandex.ru", "docs.yandex.ru",
        "disk.yandex.ru", "passport.yandex.ru", "oneme.ru", "vk.com", "vk.me",
        "userapi.com", "vk-portal.net"
    )

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
        outlineBridgePort: Int? = null,
        underlyingProxy: ProxyServerConfig? = null,
    ): String {
        val warpConfig = settings.getWarpConfig()
        val isWarpServer = config.id.startsWith("warp-") ||
                config.protocol == ProxyProtocol.MASQUE ||
                (config.protocol == ProxyProtocol.WIREGUARD &&
                        (config.name.contains("WARP", ignoreCase = true) || config.address.startsWith("162.159.") || config.address.startsWith("188.114.")))

        val isChainedWarp = (settings.enableWarpChaining && warpConfig != null && !isWarpServer) ||
                (isWarpServer && underlyingProxy != null)

        val primaryConfig = if (isWarpServer && underlyingProxy != null) underlyingProxy else config

        val endpoints = mutableListOf<String>()
        val outbounds = mutableListOf<String>()

        if (primaryConfig.protocol == ProxyProtocol.WIREGUARD) {
            endpoints += buildWireguardEndpoint(primaryConfig, tag = "proxy", detour = null, settings = settings)
        } else {
            outbounds += buildOutbound(primaryConfig, outlineBridgePort, settings)
        }

        if (isChainedWarp) {
            val effectiveWarp = if (isWarpServer) {
                warpConfig?.copy(
                    endpointHost = if (config.address.isNotBlank() && config.address != "0.0.0.0") config.address else warpConfig.endpointHost,
                    endpointPort = if (config.port > 0) config.port else warpConfig.endpointPort,
                    warpMode = settings.warpMode
                ) ?: com.flowvpn.core.model.WarpConfig(
                    accountId = config.id.removePrefix("warp-"),
                    accessToken = "",
                    accountType = if (config.name.contains("+")) "warp_plus" else "free",
                    privateKey = config.privateKey ?: "",
                    peerPublicKey = config.peerPublicKey ?: "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
                    endpointHost = config.address,
                    endpointPort = config.port,
                    localAddressV4 = config.localAddresses?.firstOrNull { !it.contains(":") } ?: "172.16.0.2/32",
                    localAddressV6 = config.localAddresses?.firstOrNull { it.contains(":") } ?: "2606:4700:110:8::1/128",
                    reserved = config.reserved ?: listOf(0, 0, 0),
                    mtu = config.wireguardMtu ?: 1280,
                    licenseKey = settings.warpLicenseKey,
                    warpMode = settings.warpMode
                )
            } else {
                warpConfig!!.copy(warpMode = settings.warpMode)
            }
            if (effectiveWarp.warpMode == com.flowvpn.core.model.WarpMode.WIREGUARD) {
                val wgConfig = com.flowvpn.core.warp.WarpManager.toProxyServerConfig(effectiveWarp)
                endpoints += buildWireguardEndpoint(wgConfig, tag = "warp", detour = "proxy", settings = settings)
            } else {
                outbounds += buildWarpOutbound(effectiveWarp)
            }
        }

        val dns = buildDns(primaryConfig, settings, isChainedWarp)
        val inbound = buildTunInbound(settings, enabledApps, excludedApps)
        val route = buildRoute(primaryConfig, settings, isChainedWarp, outlineBridgePort)

        outbounds += """{"type": "direct", "tag": "direct"}"""

        val endpointsBlock = if (endpoints.isNotEmpty()) {
            """"endpoints": [
                ${endpoints.joinToString(",\n                ")}
            ],"""
        } else ""

        val mixedInbound = """
            {
                "type": "mixed",
                "tag": "mixed-in",
                "listen": "127.0.0.1",
                "listen_port": 2080
            }
        """.trimIndent()

        return """
        {
            "log": {
                "level": "debug",
                "timestamp": true
            },
            $dns,
            $endpointsBlock
            "inbounds": [
                $inbound,
                $mixedInbound
            ],
            "outbounds": [
                ${outbounds.joinToString(",\n                ")}
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
    private fun buildOutbound(
        config: ProxyServerConfig,
        outlineBridgePort: Int? = null,
        settings: com.flowvpn.core.model.AppSettings? = null,
    ): String {
        if (outlineBridgePort != null) {
            return """
            {
                "type": "socks",
                "tag": "proxy",
                "server": "127.0.0.1",
                "server_port": $outlineBridgePort,
                "version": "5",
                "network": "tcp"
            }
            """.trimIndent()
        }

        if (config.protocol == ProxyProtocol.MASQUE) {
            return buildMasqueProxyOutbound(config, settings)
        }

        val baseParts = mutableListOf<String>()
        baseParts += """"type": "${config.protocol.singBoxType}""""
        baseParts += """"tag": "proxy""""
        baseParts += """"server": "${config.address}""""
        baseParts += """"server_port": ${config.port}"""
        if (config.protocol != ProxyProtocol.WIREGUARD) {
            baseParts += """"domain_resolver": "direct-dns""""
        }
        val base = baseParts.joinToString(",\n")

        val protocolFields = when (config.protocol) {
            ProxyProtocol.VLESS -> buildVlessFields(config)
            ProxyProtocol.VMESS -> buildVmessFields(config)
            ProxyProtocol.SHADOWSOCKS -> buildShadowsocksFields(config)
            ProxyProtocol.TROJAN -> buildTrojanFields(config)
            ProxyProtocol.HYSTERIA2 -> buildHysteria2Fields(config)
            ProxyProtocol.TUIC -> buildTuicFields(config)
            ProxyProtocol.WIREGUARD -> buildWireguardFields(config, settings)
            ProxyProtocol.SOCKS5 -> buildSocksFields(config)
            ProxyProtocol.HTTP -> buildHttpFields(config)
            ProxyProtocol.OPENFLUX -> buildSocksFields(config)
            ProxyProtocol.MASQUE -> ""
        }

        return "{$base,$protocolFields}"
    }

    /**
     * Сгенерировать JSON-блок outbound для протокола MASQUE (RFC 9484 CONNECT-IP).
     * В shtorm-7/sing-box-extended используется type: "masque".
     * Поддерживает HTTP/2 TCP (с TLS-фрагментацией) или HTTP/3 QUIC (UDP 443).
     */
    private fun buildMasqueProxyOutbound(
        config: ProxyServerConfig,
        settings: com.flowvpn.core.model.AppSettings?,
    ): String {
        val warpConfig = settings?.getWarpConfig()
        val effectiveWarpMode = warpConfig?.warpMode ?: settings?.warpMode ?: com.flowvpn.core.model.WarpMode.MASQUE_H2
        val useHttp2 = effectiveWarpMode == com.flowvpn.core.model.WarpMode.MASQUE_H2
        val licenseKey = settings?.warpLicenseKey?.takeIf { it.isNotBlank() } ?: warpConfig?.licenseKey ?: ""

        val parts = mutableListOf<String>()
        parts += """"type": "masque""""
        parts += """"tag": "proxy""""
        parts += """"use_http2": $useHttp2"""
        val effectiveAddress = if (config.address.isBlank() || config.address == "0.0.0.0") {
            if (useHttp2) "162.159.198.2" else "162.159.192.1"
        } else config.address
        val effectivePort = if (config.port > 0) config.port else 443
        if (effectiveAddress.isNotBlank() && effectiveAddress != "0.0.0.0") {
            parts += """"address": "$effectiveAddress""""
            parts += """"port": $effectivePort"""
        }
        val profileParts = mutableListOf<String>()
        warpConfig?.let { warp ->
            if (warp.accessToken.isNotBlank()) {
                profileParts += """"auth_token": "${escapeJson(warp.accessToken)}""""
            }
            if (warp.accountId.isNotBlank()) {
                profileParts += """"id": "${escapeJson(warp.accountId)}""""
            }
        }
        if (licenseKey.isNotBlank()) {
            profileParts += """"license_key": "${escapeJson(licenseKey)}""""
        }
        if (profileParts.isNotEmpty()) {
            parts += """"profile": {${profileParts.joinToString(",")}}"""
        }
        parts += """
            "tls": {
                "enabled": true,
                "server_name": "consumer-masque.cloudflareclient.com",
                "insecure": true,
                "fragment": true,
                "record_fragment": true
            }
        """.trimIndent()

        return "{\n" + parts.joinToString(",\n") + "\n}"
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
        parts += """"packet_encoding": "xudp""""

        config.tls?.let { parts += buildTlsBlock(it) }
        config.transport?.let {
            val block = buildTransportBlock(it)
            if (block.isNotBlank()) parts += block
        }

        return parts.joinToString(",\n")
    }

    private fun buildVmessFields(config: ProxyServerConfig): String {
        val parts = mutableListOf<String>()
        parts += """"uuid": "${config.uuid}""""
        parts += """"alter_id": ${config.alterId}"""
        parts += """"security": "${config.method ?: "auto"}""""
        parts += """"packet_encoding": "xudp""""

        config.tls?.let { parts += buildTlsBlock(it) }
        config.transport?.let {
            val block = buildTransportBlock(it)
            if (block.isNotBlank()) parts += block
        }

        return parts.joinToString(",\n")
    }

    private fun buildShadowsocksFields(config: ProxyServerConfig): String {
        val parts = mutableListOf<String>()
        val rawMethod = config.method?.trim()?.lowercase() ?: "chacha20-ietf-poly1305"
        val method = when (rawMethod) {
            "chacha20-poly1305", "aead_chacha20_poly1305" -> "chacha20-ietf-poly1305"
            "aead_aes_256_gcm" -> "aes-256-gcm"
            "aead_aes_128_gcm" -> "aes-128-gcm"
            "aead_aes_192_gcm" -> "aes-192-gcm"
            else -> rawMethod
        }
        parts += """"method": "$method""""
        parts += """"password": "${escapeJson(config.password ?: "")}""""
        config.plugin?.let { parts += """"plugin": "${escapeJson(it)}"""" }
        config.pluginOpts?.let { parts += """"plugin_opts": "${escapeJson(it)}"""" }
        return parts.joinToString(",\n")
    }

    private fun buildTrojanFields(config: ProxyServerConfig): String {
        val parts = mutableListOf<String>()
        parts += """"password": "${config.password}""""
        parts += """"packet_encoding": "xudp""""

        config.tls?.let { parts += buildTlsBlock(it) }
        config.transport?.let {
            val block = buildTransportBlock(it)
            if (block.isNotBlank()) parts += block
        }

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

    private fun buildWireguardFields(
        config: ProxyServerConfig,
        settings: com.flowvpn.core.model.AppSettings? = null,
    ): String {
        val warpConfig = settings?.getWarpConfig()
        val privateKey = config.privateKey?.takeIf { it.isNotBlank() } ?: warpConfig?.privateKey ?: ""
        val peerPublicKey = config.peerPublicKey?.takeIf { it.isNotBlank() } ?: warpConfig?.peerPublicKey ?: "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="

        val parts = mutableListOf<String>()
        parts += """"private_key": "$privateKey""""
        parts += """"peer_public_key": "$peerPublicKey""""

        config.preSharedKey?.let { parts += """"pre_shared_key": "$it"""" }

        val effectiveLocalAddrs = config.localAddresses?.takeIf { it.isNotEmpty() }
            ?: warpConfig?.let { listOf(it.localAddressV4, it.localAddressV6) }
            ?: if (config.address.contains("cloudflare") || config.name.contains("WARP", ignoreCase = true) || config.address.startsWith("162.159.") || config.address.startsWith("188.114.")) {
                listOf("172.16.0.2/32", "2606:4700:110:8::1/128")
            } else listOf("172.16.0.2/32")

        val addrList = effectiveLocalAddrs.joinToString(",") { "\"$it\"" }
        parts += """"local_address": [$addrList]"""

        val effectiveReserved = config.reserved?.takeIf { it.isNotEmpty() && it != listOf(0, 0, 0) }
            ?: warpConfig?.reserved?.takeIf { it.isNotEmpty() && it != listOf(0, 0, 0) }
            ?: config.reserved?.takeIf { it.isNotEmpty() }
            ?: listOf(0, 0, 0)

        parts += """"reserved": [${effectiveReserved.joinToString(",")}]"""

        val effectiveMtu = config.wireguardMtu ?: warpConfig?.mtu ?: 1280
        parts += """"mtu": $effectiveMtu"""

        return parts.joinToString(",\n")
    }

    /**
     * Сгенерировать Endpoint-блок для протокола WireGuard (формат sing-box 1.12+ / 1.14+).
     * В sing-box 1.12+ WireGuard перенесен из outbounds в endpoints и использует peers & address.
     */
    private fun buildWireguardEndpoint(
        config: ProxyServerConfig,
        tag: String = "proxy",
        detour: String? = null,
        settings: com.flowvpn.core.model.AppSettings? = null,
    ): String {
        val warpConfig = settings?.getWarpConfig()
        val privateKey = config.privateKey?.takeIf { it.isNotBlank() } ?: warpConfig?.privateKey ?: ""
        val peerPublicKey = config.peerPublicKey?.takeIf { it.isNotBlank() } ?: warpConfig?.peerPublicKey ?: "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="

        val effectiveLocalAddrs = config.localAddresses?.takeIf { it.isNotEmpty() }
            ?: warpConfig?.let { listOf(it.localAddressV4, it.localAddressV6) }
            ?: if (config.address.contains("cloudflare") || config.name.contains("WARP", ignoreCase = true) || config.address.startsWith("162.159.") || config.address.startsWith("188.114.")) {
                listOf("172.16.0.2/32", "2606:4700:110:8::1/128")
            } else listOf("172.16.0.2/32")

        val addrList = effectiveLocalAddrs.joinToString(",") { "\"$it\"" }
        val effectiveMtu = config.wireguardMtu ?: warpConfig?.mtu ?: 1280

        val peerParts = mutableListOf<String>()
        peerParts += """"address": "${config.address}""""
        peerParts += """"port": ${config.port}"""
        peerParts += """"public_key": "$peerPublicKey""""
        peerParts += """"allowed_ips": ["0.0.0.0/0", "::/0"]"""
        peerParts += """"persistent_keepalive_interval": 15"""
        config.preSharedKey?.let { peerParts += """"pre_shared_key": "$it"""" }

        val peersBlock = peerParts.joinToString(",")
        val parts = mutableListOf<String>()
        parts += """"type": "wireguard""""
        parts += """"tag": "$tag""""
        parts += """"address": [$addrList]"""
        parts += """"private_key": "$privateKey""""
        parts += """"peers": [{$peersBlock}]"""
        parts += """"mtu": $effectiveMtu"""
        detour?.let { parts += """"detour": "$it"""" }

        return "{\n" + parts.joinToString(",\n") + "\n}"
    }

    /**
     * Сгенерировать JSON-блок outbound для Cloudflare WARP.
     * В зависимости от warpMode генерирует:
     * - WireGuard (классический UDP)
     * - MASQUE (HTTP/2 TCP или HTTP/3 QUIC)
     * Трафик направляется через detour: "proxy" для реализации цепочки VPN -> WARP.
     */
    private fun buildWarpOutbound(warp: com.flowvpn.core.model.WarpConfig): String {
        return when (warp.warpMode) {
            com.flowvpn.core.model.WarpMode.WIREGUARD -> {
                val parts = mutableListOf<String>()
                parts += """"type": "wireguard""""
                parts += """"tag": "warp""""
                parts += """"server": "${warp.endpointHost}""""
                parts += """"server_port": ${warp.endpointPort}"""
                parts += """"private_key": "${warp.privateKey}""""
                parts += """"peer_public_key": "${warp.peerPublicKey}""""
                parts += """"local_address": ["${warp.localAddressV4}", "${warp.localAddressV6}"]"""
                if (warp.reserved.isNotEmpty()) {
                    parts += """"reserved": [${warp.reserved.joinToString(",")}]"""
                }
                parts += """"persistent_keepalive_interval": 15"""
                parts += """"mtu": ${warp.mtu}"""
                parts += """"detour": "proxy""""

                "{\n" + parts.joinToString(",\n") + "\n}"
            }
            com.flowvpn.core.model.WarpMode.MASQUE_H2,
            com.flowvpn.core.model.WarpMode.MASQUE_H3 -> {
                val useHttp2 = warp.warpMode == com.flowvpn.core.model.WarpMode.MASQUE_H2
                val parts = mutableListOf<String>()
                parts += """"type": "masque""""
                parts += """"tag": "warp""""
                parts += """"detour": "proxy""""
                parts += """"use_http2": $useHttp2"""
                val effectiveAddress = if (warp.endpointHost.isBlank() || warp.endpointHost == "0.0.0.0") {
                    if (useHttp2) "162.159.198.2" else "162.159.192.1"
                } else warp.endpointHost
                val effectivePort = if (warp.endpointPort > 0) warp.endpointPort else 443
                if (effectiveAddress.isNotBlank() && effectiveAddress != "0.0.0.0") {
                    parts += """"address": "$effectiveAddress""""
                    parts += """"port": $effectivePort"""
                }
                val profileParts = mutableListOf<String>()
                profileParts += """"detour": "proxy""""
                if (warp.accessToken.isNotBlank()) {
                    profileParts += """"auth_token": "${escapeJson(warp.accessToken)}""""
                }
                if (warp.accountId.isNotBlank()) {
                    profileParts += """"id": "${escapeJson(warp.accountId)}""""
                }
                if (warp.licenseKey.isNotBlank()) {
                    profileParts += """"license_key": "${escapeJson(warp.licenseKey)}""""
                }
                parts += """"profile": {${profileParts.joinToString(",")}}"""
                parts += """
                    "tls": {
                        "enabled": true,
                        "server_name": "consumer-masque.cloudflareclient.com",
                        "insecure": true,
                        "fragment": true,
                        "record_fragment": true
                    }
                """.trimIndent()

                "{\n" + parts.joinToString(",\n") + "\n}"
            }
        }
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
     * sing-box поддерживает: ws, grpc, http, httpupgrade, quic.
     * Прямой TCP/raw не оборачивается в блок transport.
     */
    private fun buildTransportBlock(transport: com.flowvpn.core.model.TransportConfig): String {
        val lowerType = transport.type.lowercase()
        if (lowerType == "tcp" || lowerType == "raw" || lowerType == "none") {
            return ""
        }
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
     * Проверка, является ли строка IPv4 или IPv6 адресом.
     */
    private fun isIpAddress(host: String): Boolean {
        val trimmed = host.trim()
        return trimmed.matches(Regex("""^(\d{1,3}\.){3}\d{1,3}$""")) || trimmed.contains(":")
    }

    /**
     * Генерация TUN inbound с учетом стека, MTU, автомаршрутизации, IPv6 и фильтрации пакетов.
     *
     * Для Android auto_route = true обеспечивает передачу дефолтного маршрута в VpnService,
     * а strict_route = false предотвращает конфликты с политиками маршрутизации AOSP.
     */
    private fun buildTunInbound(
        settings: com.flowvpn.core.model.AppSettings,
        enabledApps: List<String>?,
        excludedApps: List<String>?,
    ): String {
        val validEnabledApps = enabledApps?.filter { it.isNotBlank() }
        val validExcludedApps = excludedApps?.filter { it.isNotBlank() }

        val packageFilterField = when {
            !validEnabledApps.isNullOrEmpty() -> {
                val appsList = validEnabledApps.joinToString(",") { "\"$it\"" }
                """"include_package": [$appsList],"""
            }
            !validExcludedApps.isNullOrEmpty() -> {
                val appsList = validExcludedApps.joinToString(",") { "\"$it\"" }
                """"exclude_package": [$appsList],"""
            }
            else -> ""
        }

        val addressList = mutableListOf<String>()
        addressList += "\"172.19.0.1/30\""
        if (!settings.blockIpv6) {
            addressList += "\"fdfe:dcba:9876::1/126\""
        }
        val addressField = """"address": [${addressList.joinToString(", ")}],"""

        return """
        {
            "type": "tun",
            "tag": "tun-in",
            $addressField
            "mtu": ${settings.mtu},
            "stack": "gvisor",
            "auto_route": true,
            "strict_route": false,
            $packageFilterField
            "platform": {
                "http_proxy": {
                    "enabled": false
                }
            }
        }
        """.trimIndent()
    }

    /**
     * Генерация DNS-конфигурации с поддержкой DoH пресетов, системного DNS,
     * кастомного DNS, FakeDNS и блокировки IPv6 (strategy: ipv4_only).
     *
     * Домены прокси-серверов всегда резолвятся через direct-dns (local с detour direct)
     * ДО правил FakeDNS, что устраняет циклические дедлоки и потерю связи.
     */
    /**
     * Сформировать объект DNS-сервера в формате sing-box 1.14.0 (каждый сервер имеет свой "type").
     * Форматы "address" устарели в 1.12.0 и удалены в 1.14.0.
     */
    private fun buildDnsServerObject(
        tag: String,
        address: String,
        detour: String,
        domainResolver: String? = null,
    ): String {
        val trimmed = address.trim()
        if (trimmed.isEmpty() || trimmed == "local") {
            return """
                {
                    "tag": "$tag",
                    "type": "local",
                    "detour": "$detour"
                }
            """.trimIndent()
        }

        val fields = mutableListOf<String>()
        fields += """"tag": "$tag""""

        if (trimmed.startsWith("https://", ignoreCase = true)) {
            val uri = java.net.URI(trimmed)
            val host = uri.host ?: trimmed.removePrefix("https://").substringBefore('/')
            val port = if (uri.port > 0) uri.port else 443
            val path = if (!uri.rawPath.isNullOrEmpty()) uri.rawPath else "/dns-query"
            fields += """"type": "https""""
            fields += """"server": "$host""""
            fields += """"server_port": $port"""
            fields += """"path": "$path""""
            if (!domainResolver.isNullOrEmpty() && !isIpAddress(host)) {
                fields += """"domain_resolver": "$domainResolver""""
            }
            fields += """"detour": "$detour""""
            return "{\n                    " + fields.joinToString(",\n                    ") + "\n                }"
        }

        if (trimmed.startsWith("h3://", ignoreCase = true)) {
            val uri = java.net.URI(trimmed)
            val host = uri.host ?: trimmed.removePrefix("h3://").substringBefore('/')
            val port = if (uri.port > 0) uri.port else 443
            val path = if (!uri.rawPath.isNullOrEmpty()) uri.rawPath else "/dns-query"
            fields += """"type": "h3""""
            fields += """"server": "$host""""
            fields += """"server_port": $port"""
            fields += """"path": "$path""""
            if (!domainResolver.isNullOrEmpty() && !isIpAddress(host)) {
                fields += """"domain_resolver": "$domainResolver""""
            }
            fields += """"detour": "$detour""""
            return "{\n                    " + fields.joinToString(",\n                    ") + "\n                }"
        }

        if (trimmed.startsWith("tls://", ignoreCase = true)) {
            val withoutScheme = trimmed.removePrefix("tls://")
            val host = withoutScheme.substringBefore(':')
            val port = withoutScheme.substringAfter(':', "853").toIntOrNull() ?: 853
            fields += """"type": "tls""""
            fields += """"server": "$host""""
            fields += """"server_port": $port"""
            if (!domainResolver.isNullOrEmpty() && !isIpAddress(host)) {
                fields += """"domain_resolver": "$domainResolver""""
            }
            fields += """"detour": "$detour""""
            return "{\n                    " + fields.joinToString(",\n                    ") + "\n                }"
        }

        if (trimmed.startsWith("tcp://", ignoreCase = true)) {
            val withoutScheme = trimmed.removePrefix("tcp://")
            val host = withoutScheme.substringBefore(':')
            val port = withoutScheme.substringAfter(':', "53").toIntOrNull() ?: 53
            fields += """"type": "tcp""""
            fields += """"server": "$host""""
            fields += """"server_port": $port"""
            if (!domainResolver.isNullOrEmpty() && !isIpAddress(host)) {
                fields += """"domain_resolver": "$domainResolver""""
            }
            fields += """"detour": "$detour""""
            return "{\n                    " + fields.joinToString(",\n                    ") + "\n                }"
        }

        val cleanHost = trimmed.removePrefix("udp://")
        val host = cleanHost.substringBefore(':')
        val port = cleanHost.substringAfter(':', "53").toIntOrNull() ?: 53
        fields += """"type": "udp""""
        fields += """"server": "$host""""
        fields += """"server_port": $port"""
        if (!domainResolver.isNullOrEmpty() && !isIpAddress(host)) {
            fields += """"domain_resolver": "$domainResolver""""
        }
        fields += """"detour": "$detour""""
        return "{\n                    " + fields.joinToString(",\n                    ") + "\n                }"
    }

    private fun buildDns(
        config: ProxyServerConfig,
        settings: com.flowvpn.core.model.AppSettings,
        isWarpActive: Boolean = false,
    ): String {
        val dnsAddress = settings.getEffectiveDnsAddress()
        val strategy = if (settings.blockIpv6) "ipv4_only" else "prefer_ipv4"
        val remoteDetour = if (isWarpActive) "warp" else "proxy"

        val directDnsServer = """
            {
                "tag": "direct-dns",
                "type": "local",
                "detour": "direct"
            }
        """.trimIndent()

        val remoteDnsServer = buildDnsServerObject(
            tag = "remote-dns",
            address = dnsAddress,
            detour = remoteDetour,
            domainResolver = "direct-dns",
        )

        val dnsServers = mutableListOf<String>()
        dnsServers += remoteDnsServer
        if (dnsAddress != "local") {
            dnsServers += buildDnsServerObject(
                tag = "remote-dns-google",
                address = "https://8.8.8.8/dns-query",
                detour = remoteDetour,
                domainResolver = "direct-dns",
            )
            dnsServers += buildDnsServerObject(
                tag = "remote-dns-cloudflare",
                address = "https://1.1.1.1/dns-query",
                detour = remoteDetour,
                domainResolver = "direct-dns",
            )
        }
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

        // 1. Домен самого прокси-сервера ВСЕГДА резолвится через direct-dns (локальный DNS устройства напрямую),
        // иначе при запуске прокси возникает дедлок или резолв в Fake IP
        val serverDomains = mutableListOf<String>()
        val serverHost = config.address.trim()
        if (serverHost.isNotEmpty() && !isIpAddress(serverHost)) {
            serverDomains.add(serverHost)
        }
        if (serverDomains.isNotEmpty()) {
            val domainsJson = serverDomains.joinToString(",") { "\"$it\"" }
            dnsRules += """
                {
                    "domain": [$domainsJson],
                    "action": "route",
                    "server": "direct-dns"
                }
            """.trimIndent()
        }

        // 1.1. Домены инфраструктуры Cloudflare (API регистрации и обновления)
        // Всегда направляем через remote-dns (защищенный VPN-туннель),
        // так как в РФ домены api.cloudflareclient.com блокируются ТСПУ на прямом подключении.
        dnsRules += """
            {
                "domain": [
                    "api.cloudflareclient.com",
                    "cloudflareclient.com"
                ],
                "action": "route",
                "server": "remote-dns"
            }
        """.trimIndent()

        // 2. DNS-запросы от прямого трафика — в direct-dns
        dnsRules += """
            {
                "outbound": "direct",
                "action": "route",
                "server": "direct-dns"
            }
        """.trimIndent()

        // 2.1. Защита от зацикливания OpenFlux: домены Яндекса и MAX в direct-dns
        val isOpenFlux = config.protocol == ProxyProtocol.OPENFLUX ||
                (config.protocol == ProxyProtocol.SOCKS5 && (config.address == "127.0.0.1" || config.address == "localhost"))
        if (isOpenFlux) {
            val carrierDomainsJson = OPENFLUX_CARRIER_DOMAINS.joinToString(",") { "\"$it\"" }
            dnsRules += """
                {
                    "domain_suffix": [$carrierDomainsJson],
                    "action": "route",
                    "server": "direct-dns"
                }
            """.trimIndent()
        }

        // 3. Сайты РФ и кастомные домены обхода всегда через direct-dns (системный резолвер устройства)
        if (settings.bypassRussianTraffic || settings.customBypassDomains.isNotEmpty()) {
            val bypassList = mutableListOf<String>()
            if (settings.bypassRussianTraffic) {
                bypassList.addAll(listOf(".ru", ".xn--p1ai", ".su", ".by", ".kz"))
            }
            for (domain in settings.customBypassDomains) {
                val clean = domain.trim().lowercase()
                if (clean.isNotBlank()) bypassList.add(clean)
            }
            val distinctList = bypassList.distinct()
            if (distinctList.isNotEmpty()) {
                val domainsJson = distinctList.joinToString(",") { "\"$it\"" }
                dnsRules += """
                    {
                        "domain_suffix": [
                            $domainsJson
                        ],
                        "action": "route",
                        "server": "direct-dns"
                    }
                """.trimIndent()
            }
        }

        // 4. FakeDNS только для доменов, идущих в прокси (после исключений direct/proxy-server/RU)
        if (settings.fakeDns) {
            dnsRules += """
                {
                    "query_type": ["HTTPS", "SVCB"],
                    "action": "reject"
                }
            """.trimIndent()
            dnsRules += """
                {
                    "query_type": ["A", "AAAA"],
                    "action": "route",
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
     * Генерация маршрутов sing-box.
     *
     * Поддерживает:
     * - Вывод трафика к самому прокси-серверу в direct (защита от зацикливания сокетов)
     * - Перехват DNS-трафика (порт 53 и протокол dns) через hijack-dns
     * - Блокировку IPv6 (защита от утечек)
     * - Обход локальной сети (Bypass LAN / ip_is_private)
     * - Обход сайтов РФ (Российские сервисы напрямую: .ru, .рф, банки, госуслуги)
     */
    private fun buildRoute(
        config: ProxyServerConfig,
        settings: com.flowvpn.core.model.AppSettings,
        isWarpActive: Boolean = false,
        outlineBridgePort: Int? = null,
    ): String {
        val rules = mutableListOf<String>()

        // 1. DNS трафик — немедленный перехват через hijack-dns в первую очередь
        rules += """
            {
                "port": [53],
                "action": "hijack-dns"
            }
        """.trimIndent()
        rules += """
            {
                "protocol": "dns",
                "action": "hijack-dns"
            }
        """.trimIndent()

        // 2. Блокировка Private DNS (DoT, TCP 853) — предотвращает зависание проверок Android
        rules += """
            {
                "port": [853],
                "action": "reject"
            }
        """.trimIndent()

        // 3. Блокировка QUIC (UDP 443) — предотвращает подвисание Chrome и сервисов Google
        rules += """
            {
                "port": [443],
                "network": "udp",
                "action": "reject"
            }
        """.trimIndent()

        // 4. Sniffing входящего трафика для определения доменов (только если включен в настройках)
        if (settings.sniffing && (settings.bypassRussianTraffic || settings.customBypassDomains.isNotEmpty())) {
            rules += """
                {
                    "action": "sniff",
                    "sniffer": ["tls", "http", "quic"],
                    "timeout": "100ms"
                }
            """.trimIndent()
        }

        // 4. Трафик к самому прокси-серверу — ВСЕГДА direct, чтобы исключить петлю маршрутизации
        val serverHost = config.address.trim()
        if (serverHost.isNotEmpty()) {
            if (isIpAddress(serverHost)) {
                val cidr = if (serverHost.contains(":")) "$serverHost/128" else "$serverHost/32"
                rules += """
                    {
                        "ip_cidr": ["$cidr"],
                        "outbound": "direct"
                    }
                """.trimIndent()
            } else {
                rules += """
                    {
                        "domain": ["$serverHost"],
                        "outbound": "direct"
                    }
                """.trimIndent()
            }
        }

        if (config.protocol == ProxyProtocol.MASQUE && !isWarpActive) {
            val masqueHost = config.address.trim()
            val masqueIps = mutableListOf("162.159.198.2/32", "162.159.192.1/32", "162.159.193.1/32")
            if (masqueHost.isNotEmpty() && isIpAddress(masqueHost)) {
                val cidr = if (masqueHost.contains(":")) "$masqueHost/128" else "$masqueHost/32"
                if (!masqueIps.contains(cidr)) masqueIps.add(cidr)
            }
            val ipsJson = masqueIps.joinToString(",") { "\"$it\"" }
            rules += """
                {
                    "ip_cidr": [$ipsJson],
                    "outbound": "direct"
                }
            """.trimIndent()
        }

        // 4.1. Защита от зацикливания OpenFlux и OutlineBridge (локальный туннель к Яндекс/MAX/127.0.0.1)
        val isOpenFlux = config.protocol == ProxyProtocol.OPENFLUX ||
                (config.protocol == ProxyProtocol.SOCKS5 && (config.address == "127.0.0.1" || config.address == "localhost"))
        if (isOpenFlux || outlineBridgePort != null) {
            rules += """
                {
                    "ip_cidr": ["127.0.0.0/8", "::1/128"],
                    "outbound": "direct"
                }
            """.trimIndent()
            if (isOpenFlux) {
                val carrierDomainsJson = OPENFLUX_CARRIER_DOMAINS.joinToString(",") { "\"$it\"" }
                rules += """
                    {
                        "domain_suffix": [$carrierDomainsJson],
                        "outbound": "direct"
                    }
                """.trimIndent()
            }
        }

        // 4.2. API регистрации Cloudflare WARP:
        // Всегда направляется через первичный VPN (proxy) для надежного обхода блокировок ТСПУ в РФ.
        rules += """
            {
                "domain": ["api.cloudflareclient.com", "cloudflareclient.com"],
                "outbound": "proxy"
            }
        """.trimIndent()

        // 5. Блокировка IPv6 при включенной защите от утечек
        if (settings.blockIpv6) {
            rules += """
                {
                    "ip_version": 6,
                    "action": "reject"
                }
            """.trimIndent()
        }

        // 4. Обход локальной сети (Bypass LAN) через встроенный ip_is_private
        if (settings.bypassLan) {
            rules += """
                {
                    "ip_is_private": true,
                    "outbound": "direct"
                }
            """.trimIndent()
        }

        // 5. Обход сайтов РФ и кастомных сайтов (Bypass Russian traffic & custom domains) через domain_suffix
        if (settings.bypassRussianTraffic || settings.customBypassDomains.isNotEmpty()) {
            val bypassList = mutableListOf<String>()
            if (settings.bypassRussianTraffic) {
                bypassList.addAll(listOf(".ru", ".xn--p1ai", ".su", ".by", ".kz"))
            }
            for (domain in settings.customBypassDomains) {
                val clean = domain.trim().lowercase()
                if (clean.isNotBlank()) bypassList.add(clean)
            }
            val distinctList = bypassList.distinct()
            if (distinctList.isNotEmpty()) {
                val domainsJson = distinctList.joinToString(",") { "\"$it\"" }
                rules += """
                    {
                        "domain_suffix": [
                            $domainsJson
                        ],
                        "outbound": "direct"
                    }
                """.trimIndent()
            }
        }

        val rulesJson = rules.joinToString(",\n")
        val finalOutbound = if (isWarpActive) "warp" else "proxy"

        return """
            "route": {
                "rules": [
                    $rulesJson
                ],
                "auto_detect_interface": true,
                "final": "$finalOutbound"
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
            ProxyProtocol.OPENFLUX -> "socks"
            ProxyProtocol.MASQUE -> "masque"
        }

    private fun escapeJson(value: String): String {
        return value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}

package com.flowvpn.core.model

import java.util.UUID

/**
 * Унифицированная модель прокси-сервера для всех поддерживаемых протоколов.
 *
 * Используется как единый формат представления в UI и как входные данные
 * для [com.flowvpn.core.config.SingBoxConfigBuilder], который конвертирует
 * эту модель в JSON-конфигурацию sing-box.
 *
 * Протокол-специфичные поля (uuid, password, method и т.д.) nullable —
 * заполняются только для соответствующих протоколов.
 */
data class ProxyServerConfig(
    /** Уникальный идентификатор конфигурации */
    val id: String = UUID.randomUUID().toString(),

    /** Отображаемое имя сервера в UI (e.g., "🇩🇪 Frankfurt #1") */
    val name: String,

    /** Тип протокола проксирования */
    val protocol: ProxyProtocol,

    /** IP-адрес или домен сервера */
    val address: String,

    /** Порт сервера */
    val port: Int,

    // ─── Протокол-специфичные поля ───

    /** UUID пользователя — для VLESS, VMess */
    val uuid: String? = null,

    /** Пароль — для Shadowsocks, Trojan, Hysteria2 */
    val password: String? = null,

    /** Метод шифрования — для Shadowsocks (e.g., "2022-blake3-aes-128-gcm") */
    val method: String? = null,

    /** Плагин SIP003 для Shadowsocks (e.g., "obfs-local", "v2ray-plugin") */
    val plugin: String? = null,

    /** Параметры плагина SIP003 (e.g., "obfs=http;obfs-host=...") */
    val pluginOpts: String? = null,

    /** Префикс обхода DPI Outline (e.g., "POST / HTTP/1.1\r\n") */
    val prefix: String? = null,

    /** Флаг сервера Outline (Shadowsocks с параметрами Outline) */
    val isOutline: Boolean = false,

    /** Тип безопасности — "tls", "reality", "none" */
    val security: String? = null,

    /** Alterid — для VMess (legacy, обычно 0) */
    val alterId: Int = 0,

    /** Flow — для VLESS (e.g., "xtls-rprx-vision") */
    val flow: String? = null,

    // ─── Транспорт ───

    /** Конфигурация транспортного уровня (WS, gRPC, HTTP/2, TCP) */
    val transport: TransportConfig? = null,

    /** Конфигурация TLS */
    val tls: TlsConfig? = null,

    // ─── Hysteria2 / TUIC ───

    /** Up bandwidth для Hysteria2 (Mbps) */
    val upMbps: Int? = null,

    /** Down bandwidth для Hysteria2 (Mbps) */
    val downMbps: Int? = null,

    /** Obfuscation password для Hysteria2 */
    val obfsPassword: String? = null,

    /** Congestion control — для TUIC (e.g., "bbr", "cubic") */
    val congestionControl: String? = null,

    // ─── WireGuard ───

    /** WireGuard private key */
    val privateKey: String? = null,

    /** WireGuard peer public key */
    val peerPublicKey: String? = null,

    /** WireGuard pre-shared key */
    val preSharedKey: String? = null,

    /** WireGuard local addresses */
    val localAddresses: List<String>? = null,

    /** WireGuard reserved bytes */
    val reserved: List<Int>? = null,

    /** WireGuard MTU */
    val wireguardMtu: Int? = null,

    // ─── OpenFlux (Белые списки) ───

    /** Транспорт OpenFlux: "yandex" (Яндекс Документы), "max" (MAX Messenger), "direct" */
    val openfluxTransport: String? = null,

    /** Ссылка на документ Яндекс Документов */
    val openfluxDocUrl: String? = null,

    /** Токен для MAX Messenger */
    val openfluxToken: String? = null,

    /** User ID / Peer ID для MAX Messenger */
    val openfluxUid: String? = null,

    // ─── Мета-информация ───

    /** ID подписки, из которой получен сервер */
    val subscriptionId: String? = null,

    /** Замеренная задержка в миллисекундах (null = не тестировался) */
    val latencyMs: Int? = null,

    /** Код страны ISO 3166-1 alpha-2 (e.g., "DE", "US") */
    val country: String? = null,
) {
    fun toJson(): org.json.JSONObject {
        return org.json.JSONObject().apply {
            put("id", id)
            put("name", name)
            put("protocol", protocol.name)
            put("address", address)
            put("port", port)
            putOpt("uuid", uuid)
            putOpt("password", password)
            putOpt("method", method)
            putOpt("plugin", plugin)
            putOpt("pluginOpts", pluginOpts)
            putOpt("prefix", prefix)
            put("isOutline", isOutline)
            putOpt("security", security)
            put("alterId", alterId)
            putOpt("flow", flow)
            putOpt("upMbps", upMbps)
            putOpt("downMbps", downMbps)
            putOpt("obfsPassword", obfsPassword)
            putOpt("congestionControl", congestionControl)
            putOpt("privateKey", privateKey)
            putOpt("peerPublicKey", peerPublicKey)
            putOpt("preSharedKey", preSharedKey)
            putOpt("openfluxTransport", openfluxTransport)
            putOpt("openfluxDocUrl", openfluxDocUrl)
            putOpt("openfluxToken", openfluxToken)
            putOpt("openfluxUid", openfluxUid)
            putOpt("subscriptionId", subscriptionId)
            putOpt("latencyMs", latencyMs)
            putOpt("country", country)
            putOpt("wireguardMtu", wireguardMtu)
            localAddresses?.let { addrs ->
                val arr = org.json.JSONArray()
                addrs.forEach { arr.put(it) }
                put("localAddresses", arr)
            }
            reserved?.let { res ->
                val arr = org.json.JSONArray()
                res.forEach { arr.put(it) }
                put("reserved", arr)
            }

            tls?.let { t ->
                put("tls", org.json.JSONObject().apply {
                    put("enabled", t.enabled)
                    putOpt("serverName", t.serverName)
                    put("insecure", t.insecure)
                    putOpt("utlsFingerprint", t.utlsFingerprint)
                    putOpt("realityPublicKey", t.realityPublicKey)
                    putOpt("realityShortId", t.realityShortId)
                    t.alpn?.let { put("alpn", org.json.JSONArray(it)) }
                })
            }

            transport?.let { t ->
                put("transport", org.json.JSONObject().apply {
                    put("type", t.type)
                    putOpt("path", t.path)
                    putOpt("host", t.host)
                    putOpt("serviceName", t.serviceName)
                })
            }
        }
    }

    companion object {
        fun fromJson(sObj: org.json.JSONObject): ProxyServerConfig {
            val protocolStr = sObj.optString("protocol", "VLESS")
            val protocol = try {
                ProxyProtocol.valueOf(protocolStr)
            } catch (_: Exception) {
                ProxyProtocol.VLESS
            }

            val tlsObj = sObj.optJSONObject("tls")
            val tls = tlsObj?.let {
                val alpnList = mutableListOf<String>()
                it.optJSONArray("alpn")?.let { arr ->
                    for (k in 0 until arr.length()) {
                        alpnList.add(arr.getString(k))
                    }
                }
                TlsConfig(
                    enabled = it.optBoolean("enabled", true),
                    serverName = it.optString("serverName").takeIf { s -> s.isNotEmpty() },
                    insecure = it.optBoolean("insecure", false),
                    utlsFingerprint = it.optString("utlsFingerprint").takeIf { s -> s.isNotEmpty() },
                    realityPublicKey = it.optString("realityPublicKey").takeIf { s -> s.isNotEmpty() },
                    realityShortId = it.optString("realityShortId").takeIf { s -> s.isNotEmpty() },
                    alpn = if (alpnList.isNotEmpty()) alpnList else null,
                )
            }

            val tObj = sObj.optJSONObject("transport")
            val transport = tObj?.let {
                TransportConfig(
                    type = it.optString("type", "tcp"),
                    path = it.optString("path").takeIf { s -> s.isNotEmpty() },
                    host = it.optString("host").takeIf { s -> s.isNotEmpty() },
                    serviceName = it.optString("serviceName").takeIf { s -> s.isNotEmpty() },
                )
            }

            val localAddrs = sObj.optJSONArray("localAddresses")?.let { arr ->
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) list.add(arr.getString(i))
                list
            }
            val reservedList = sObj.optJSONArray("reserved")?.let { arr ->
                val list = mutableListOf<Int>()
                for (i in 0 until arr.length()) list.add(arr.getInt(i))
                list
            }
            var port = sObj.optInt("port", 443)
            val address = sObj.optString("address", "127.0.0.1")
            val serverName = sObj.optString("name", "Unknown Server")
            if (protocol == ProxyProtocol.SHADOWSOCKS && address.contains("webdisk.awfulfabo.cyou") && port == 443) {
                port = 47893
            }

            var localAddresses: List<String>? = localAddrs
            var reserved: List<Int>? = reservedList
            var wireguardMtu = if (sObj.has("wireguardMtu")) sObj.getInt("wireguardMtu") else null

            if (protocol == ProxyProtocol.WIREGUARD) {
                if (localAddresses.isNullOrEmpty() && (address.contains("cloudflare") || serverName.contains("WARP", ignoreCase = true) || address.startsWith("162.159.") || address.startsWith("188.114."))) {
                    localAddresses = listOf("172.16.0.2/32", "2606:4700:110:8::1/128")
                }
                if (reserved.isNullOrEmpty() && (address.contains("cloudflare") || serverName.contains("WARP", ignoreCase = true) || address.startsWith("162.159.") || address.startsWith("188.114."))) {
                    reserved = listOf(0, 0, 0)
                }
                if (wireguardMtu == null && (address.contains("cloudflare") || serverName.contains("WARP", ignoreCase = true))) {
                    wireguardMtu = 1280
                }
            }

            return ProxyServerConfig(
                id = sObj.optString("id", UUID.randomUUID().toString()),
                name = serverName,
                protocol = protocol,
                address = address,
                port = port,
                uuid = sObj.optString("uuid").takeIf { it.isNotEmpty() },
                password = sObj.optString("password").takeIf { it.isNotEmpty() },
                method = sObj.optString("method").takeIf { it.isNotEmpty() },
                plugin = sObj.optString("plugin").takeIf { it.isNotEmpty() },
                pluginOpts = sObj.optString("pluginOpts").takeIf { it.isNotEmpty() },
                prefix = sObj.optString("prefix").takeIf { it.isNotEmpty() },
                isOutline = sObj.optBoolean("isOutline", false),
                security = sObj.optString("security").takeIf { it.isNotEmpty() },
                alterId = sObj.optInt("alterId", 0),
                flow = sObj.optString("flow").takeIf { it.isNotEmpty() },
                upMbps = if (sObj.has("upMbps")) sObj.getInt("upMbps") else null,
                downMbps = if (sObj.has("downMbps")) sObj.getInt("downMbps") else null,
                obfsPassword = sObj.optString("obfsPassword").takeIf { it.isNotEmpty() },
                congestionControl = sObj.optString("congestionControl").takeIf { it.isNotEmpty() },
                privateKey = sObj.optString("privateKey").takeIf { it.isNotEmpty() },
                peerPublicKey = sObj.optString("peerPublicKey").takeIf { it.isNotEmpty() },
                preSharedKey = sObj.optString("preSharedKey").takeIf { it.isNotEmpty() },
                localAddresses = localAddresses,
                reserved = reserved,
                wireguardMtu = wireguardMtu,
                openfluxTransport = sObj.optString("openfluxTransport").takeIf { it.isNotEmpty() },
                openfluxDocUrl = sObj.optString("openfluxDocUrl").takeIf { it.isNotEmpty() },
                openfluxToken = sObj.optString("openfluxToken").takeIf { it.isNotEmpty() },
                openfluxUid = sObj.optString("openfluxUid").takeIf { it.isNotEmpty() },
                subscriptionId = sObj.optString("subscriptionId").takeIf { it.isNotEmpty() },
                latencyMs = if (sObj.has("latencyMs")) sObj.getInt("latencyMs") else null,
                country = sObj.optString("country").takeIf { it.isNotEmpty() },
                tls = tls,
                transport = transport,
            )
        }
    }
}

/**
 * Поддерживаемые протоколы проксирования.
 * Каждый протокол имеет свой формат URI-ссылки для парсинга.
 */
enum class ProxyProtocol(val displayName: String, val uriScheme: String) {
    VLESS("VLESS", "vless"),
    VMESS("VMess", "vmess"),
    SHADOWSOCKS("Shadowsocks", "ss"),
    TROJAN("Trojan", "trojan"),
    HYSTERIA2("Hysteria2", "hysteria2"),
    TUIC("TUIC", "tuic"),
    WIREGUARD("WireGuard", "wireguard"),
    SOCKS5("SOCKS5", "socks5"),
    HTTP("HTTP", "http"),
    OPENFLUX("OpenFlux", "openflux"),
    MASQUE("MASQUE", "masque"),
}

/**
 * Конфигурация транспортного уровня.
 * sing-box поддерживает несколько типов транспорта поверх TCP/UDP.
 */
data class TransportConfig(
    /** Тип транспорта: "ws", "grpc", "http", "tcp", "quic" */
    val type: String,

    /** WebSocket path (e.g., "/ws") */
    val path: String? = null,

    /** HTTP Host заголовок / gRPC service name */
    val host: String? = null,

    /** gRPC service name */
    val serviceName: String? = null,

    /** HTTP заголовки */
    val headers: Map<String, String>? = null,
)

/**
 * Конфигурация TLS-уровня.
 * Включает поддержку XTLS Reality.
 */
data class TlsConfig(
    /** Включить TLS */
    val enabled: Boolean = true,

    /** Server Name Indication */
    val serverName: String? = null,

    /** Пропустить проверку сертификата (небезопасно!) */
    val insecure: Boolean = false,

    /** ALPN протоколы (e.g., ["h2", "http/1.1"]) */
    val alpn: List<String>? = null,

    /** Fingerprint для uTLS (e.g., "chrome", "firefox", "random") */
    val utlsFingerprint: String? = null,

    // ─── Reality ───

    /** Reality public key */
    val realityPublicKey: String? = null,

    /** Reality short ID */
    val realityShortId: String? = null,
)

package com.flowvpn.core.parser

import com.flowvpn.core.model.ProxyProtocol
import com.flowvpn.core.model.ProxyServerConfig
import com.flowvpn.core.model.TlsConfig
import com.flowvpn.core.model.TransportConfig

/**
 * Парсер ссылок протокола VLESS.
 *
 * ## Формат URI
 *
 * ```
 * vless://uuid@host:port?type=tcp&security=tls&sni=example.com&fp=chrome&flow=xtls-rprx-vision#Name
 * ```
 *
 * ## Поддерживаемые параметры
 *
 * | Параметр | Описание | Пример |
 * |----------|----------|--------|
 * | `type` | Транспорт: tcp, ws, grpc, http, quic | `type=ws` |
 * | `security` | Шифрование: tls, reality, none | `security=reality` |
 * | `sni` | Server Name Indication | `sni=example.com` |
 * | `fp` | uTLS fingerprint | `fp=chrome` |
 * | `flow` | XTLS flow control | `flow=xtls-rprx-vision` |
 * | `pbk` | Reality public key | `pbk=...` |
 * | `sid` | Reality short ID | `sid=abc123` |
 * | `path` | WebSocket/HTTP path | `path=/ws` |
 * | `host` | WebSocket Host header | `host=cdn.example.com` |
 * | `serviceName` | gRPC service name | `serviceName=vless-grpc` |
 * | `alpn` | ALPN protocols (comma-separated) | `alpn=h2,http/1.1` |
 * | `allowInsecure` | Skip cert verification | `allowInsecure=1` |
 *
 * ## Пример Reality VLESS
 *
 * ```
 * vless://uuid@1.2.3.4:443?type=tcp&security=reality&sni=www.yahoo.com&fp=chrome&pbk=PUBLIC_KEY&sid=SHORT_ID&flow=xtls-rprx-vision#RealityServer
 * ```
 */
object VlessParser {

    fun parse(link: String): ProxyServerConfig? {
        // vless://uuid@host:port?params#fragment
        val withoutScheme = link.removePrefix("vless://").removePrefix("VLESS://")

        // Извлекаем UUID (userinfo до @)
        val atIndex = withoutScheme.indexOf('@')
        if (atIndex < 0) return null
        val uuid = withoutScheme.substring(0, atIndex)

        // Извлекаем host:port (authority между @ и ? или #)
        val authorityEnd = withoutScheme.indexOfFirst { it == '?' || it == '#' }
            .let { if (it < 0) withoutScheme.length else it }
        val authority = withoutScheme.substring(atIndex + 1, authorityEnd)
        val (host, port) = UriParseUtils.extractHostPort(authority)

        // Query параметры
        val params = UriParseUtils.extractQueryParams(link)

        // Fragment → имя сервера
        val name = UriParseUtils.extractFragment(link).ifBlank { "$host:$port" }

        // Транспорт
        val transportType = params["type"] ?: "tcp"
        val transport = buildTransport(transportType, params)

        // TLS / Reality
        val security = params["security"] ?: params["encryption"] ?: "none"
        val tls = buildTls(security, params)

        return ProxyServerConfig(
            name = name,
            protocol = ProxyProtocol.VLESS,
            address = host,
            port = port,
            uuid = uuid,
            flow = params["flow"],
            security = security,
            transport = transport,
            tls = tls,
        )
    }

    private fun buildTransport(type: String, params: Map<String, String>): TransportConfig? {
        if (type == "tcp" && params["headerType"]?.equals("none", true) != false) {
            return null // TCP без обёртки — транспорт не нужен
        }

        return TransportConfig(
            type = type,
            path = params["path"],
            host = params["host"],
            serviceName = params["serviceName"],
            headers = params["host"]?.let { mapOf("Host" to it) },
        )
    }

    private fun buildTls(security: String, params: Map<String, String>): TlsConfig? {
        if (security == "none") return null

        return TlsConfig(
            enabled = true,
            serverName = params["sni"] ?: params["peer"],
            insecure = params["allowInsecure"] == "1" || params["insecure"] == "1",
            alpn = params["alpn"]?.split(",")?.map { it.trim() },
            utlsFingerprint = params["fp"],
            realityPublicKey = params["pbk"],
            realityShortId = params["sid"],
        )
    }
}

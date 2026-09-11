package com.flowvpn.core.parser

import com.flowvpn.core.model.ProxyProtocol
import com.flowvpn.core.model.ProxyServerConfig
import com.flowvpn.core.model.TlsConfig
import com.flowvpn.core.model.TransportConfig

/**
 * Парсер ссылок протокола Trojan.
 *
 * ## Формат URI
 *
 * ```
 * trojan://password@host:port?security=tls&type=ws&path=/path&host=cdn.com&sni=example.com#Name
 * ```
 *
 * Trojan по умолчанию использует TLS (порт 443).
 * URI-формат аналогичен VLESS, но вместо UUID используется пароль.
 *
 * ## Параметры
 *
 * | Параметр | Описание | Default |
 * |----------|----------|---------|
 * | `security` | tls / none | tls |
 * | `type` | tcp / ws / grpc / http | tcp |
 * | `sni` | Server Name Indication | host |
 * | `path` | WebSocket/HTTP path | — |
 * | `host` | WebSocket Host header | — |
 * | `serviceName` | gRPC service name | — |
 * | `fp` | uTLS fingerprint | — |
 * | `alpn` | ALPN protocols | — |
 * | `allowInsecure` | 0/1 | 0 |
 */
object TrojanParser {

    fun parse(link: String): ProxyServerConfig? {
        val withoutScheme = link.removePrefix("trojan://").removePrefix("TROJAN://")

        // password@host:port
        val atIndex = withoutScheme.indexOf('@')
        if (atIndex < 0) return null

        val password = UriParseUtils.safePercentDecode(
            withoutScheme.substring(0, atIndex)
        )

        val authorityEnd = withoutScheme.indexOfFirst { it == '?' || it == '#' }
            .let { if (it < 0) withoutScheme.length else it }
        val authority = withoutScheme.substring(atIndex + 1, authorityEnd)
        val (host, port) = UriParseUtils.extractHostPort(authority)

        val params = UriParseUtils.extractQueryParams(link)
        val name = UriParseUtils.extractFragment(link).ifBlank { "$host:$port" }

        // Транспорт
        val transportType = params["type"] ?: "tcp"
        val transport = if (transportType != "tcp") {
            TransportConfig(
                type = transportType,
                path = params["path"],
                host = params["host"],
                serviceName = params["serviceName"],
            )
        } else null

        // TLS — Trojan по умолчанию использует TLS
        val security = params["security"] ?: "tls"
        val tls = if (security != "none") {
            TlsConfig(
                enabled = true,
                serverName = params["sni"] ?: host,
                insecure = params["allowInsecure"] == "1",
                alpn = params["alpn"]?.split(",")?.map { it.trim() },
                utlsFingerprint = params["fp"],
            )
        } else null

        return ProxyServerConfig(
            name = name,
            protocol = ProxyProtocol.TROJAN,
            address = host,
            port = port,
            password = password,
            security = security,
            transport = transport,
            tls = tls,
        )
    }
}

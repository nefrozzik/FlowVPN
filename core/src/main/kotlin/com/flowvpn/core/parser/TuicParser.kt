package com.flowvpn.core.parser

import com.flowvpn.core.model.ProxyProtocol
import com.flowvpn.core.model.ProxyServerConfig
import com.flowvpn.core.model.TlsConfig

/**
 * Парсер ссылок протокола TUIC v5.
 *
 * ## Формат URI
 *
 * ```
 * tuic://uuid:password@host:port?congestion_control=bbr&alpn=h3&sni=example.com#Name
 * ```
 *
 * ## Особенности TUIC v5
 *
 * - Работает поверх **QUIC** (UDP) — аналогично Hysteria2
 * - Требует UUID + password (двухфакторная аутентификация)
 * - Поддерживает congestion control: bbr, cubic, new_reno
 * - Обязательно TLS (QUIC = TLS 1.3)
 * - Хорошо работает в условиях потери пакетов
 *
 * ## Параметры
 *
 * | Параметр | Описание | Default |
 * |----------|----------|---------|
 * | `congestion_control` | bbr / cubic / new_reno | bbr |
 * | `alpn` | ALPN протоколы (comma-separated) | h3 |
 * | `sni` | Server Name Indication | host |
 * | `udp_relay_mode` | native / quic | native |
 * | `allow_insecure` | 0/1 | 0 |
 */
object TuicParser {

    fun parse(link: String): ProxyServerConfig? {
        val withoutScheme = link.removePrefix("tuic://").removePrefix("TUIC://")

        // uuid:password@host:port
        val atIndex = withoutScheme.indexOf('@')
        if (atIndex < 0) return null

        val credentials = withoutScheme.substring(0, atIndex)
        val colonIndex = credentials.indexOf(':')
        if (colonIndex < 0) return null

        val uuid = credentials.substring(0, colonIndex)
        val password = credentials.substring(colonIndex + 1)

        val authorityEnd = withoutScheme.indexOfFirst { it == '?' || it == '#' }
            .let { if (it < 0) withoutScheme.length else it }
        val authority = withoutScheme.substring(atIndex + 1, authorityEnd)
        val (host, port) = UriParseUtils.extractHostPort(authority)

        val params = UriParseUtils.extractQueryParams(link)
        val name = UriParseUtils.extractFragment(link).ifBlank { "$host:$port" }

        // TLS (обязательно для TUIC/QUIC)
        val tls = TlsConfig(
            enabled = true,
            serverName = params["sni"] ?: host,
            insecure = params["allow_insecure"] == "1" || params["allowInsecure"] == "1",
            alpn = params["alpn"]?.split(",")?.map { it.trim() },
        )

        return ProxyServerConfig(
            name = name,
            protocol = ProxyProtocol.TUIC,
            address = host,
            port = port,
            uuid = uuid,
            password = password,
            security = "tls",
            tls = tls,
            congestionControl = params["congestion_control"] ?: params["congestionControl"] ?: "bbr",
        )
    }
}

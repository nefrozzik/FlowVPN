package com.flowvpn.core.parser

import com.flowvpn.core.model.ProxyProtocol
import com.flowvpn.core.model.ProxyServerConfig
import com.flowvpn.core.model.TlsConfig

/**
 * Парсер ссылок протокола Hysteria 2.
 *
 * ## Формат URI
 *
 * ```
 * hysteria2://password@host:port?obfs=salamander&obfs-password=xxx&sni=example.com&insecure=1#Name
 * hy2://password@host:port?...#Name
 * ```
 *
 * ## Особенности Hysteria 2
 *
 * - Работает поверх **QUIC** (UDP) — обходит TCP-блокировки
 * - Поддерживает **obfuscation** ("salamander") для маскировки UDP-трафика
 * - Обязательно использует TLS (QUIC = TLS 1.3)
 * - Может ограничивать полосу пропускания (up_mbps / down_mbps)
 *
 * ## Параметры
 *
 * | Параметр | Описание |
 * |----------|----------|
 * | `obfs` | Тип обфускации: "salamander" |
 * | `obfs-password` | Пароль обфускации |
 * | `sni` | Server Name Indication |
 * | `insecure` | Пропустить проверку сертификата: 0/1 |
 * | `pinSHA256` | Pin SHA256 hash сертификата |
 * | `up` / `down` | Bandwidth limit (e.g., "100 mbps") |
 */
object Hysteria2Parser {

    fun parse(link: String): ProxyServerConfig? {
        // Нормализуем схему
        val normalized = link
            .replaceFirst("hy2://", "hysteria2://", ignoreCase = true)

        val withoutScheme = normalized
            .removePrefix("hysteria2://")
            .removePrefix("HYSTERIA2://")

        // password@host:port
        val atIndex = withoutScheme.indexOf('@')
        if (atIndex < 0) return null

        val password = java.net.URLDecoder.decode(
            withoutScheme.substring(0, atIndex), "UTF-8"
        )

        val authorityEnd = withoutScheme.indexOfFirst { it == '?' || it == '#' }
            .let { if (it < 0) withoutScheme.length else it }
        val authority = withoutScheme.substring(atIndex + 1, authorityEnd)
        val (host, port) = UriParseUtils.extractHostPort(authority)

        val params = UriParseUtils.extractQueryParams(normalized)
        val name = UriParseUtils.extractFragment(normalized).ifBlank { "$host:$port" }

        // Obfuscation
        val obfsType = params["obfs"]
        val obfsPassword = params["obfs-password"]

        // Bandwidth limits (парсим "100 mbps" → 100)
        val upMbps = parseBandwidth(params["up"])
        val downMbps = parseBandwidth(params["down"])

        // TLS (обязательно для Hysteria2/QUIC)
        val tls = TlsConfig(
            enabled = true,
            serverName = params["sni"] ?: host,
            insecure = params["insecure"] == "1",
        )

        return ProxyServerConfig(
            name = name,
            protocol = ProxyProtocol.HYSTERIA2,
            address = host,
            port = port,
            password = password,
            security = "tls",
            tls = tls,
            obfsPassword = if (obfsType == "salamander") obfsPassword else null,
            upMbps = upMbps,
            downMbps = downMbps,
        )
    }

    /**
     * Парсинг значения bandwidth.
     * Форматы: "100", "100 mbps", "50mbps"
     */
    private fun parseBandwidth(value: String?): Int? {
        if (value == null) return null
        return value
            .replace(Regex("[^0-9]"), "")
            .toIntOrNull()
    }
}

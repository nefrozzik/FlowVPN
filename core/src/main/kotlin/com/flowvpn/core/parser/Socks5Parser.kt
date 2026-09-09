package com.flowvpn.core.parser

import com.flowvpn.core.model.ProxyProtocol
import com.flowvpn.core.model.ProxyServerConfig
import java.util.UUID

/**
 * Парсер ссылок протокола SOCKS5.
 *
 * ## Форматы URI
 *
 * ```
 * socks5://[user:password@]host:port#Name
 * socks://[user:password@]host:port#Name
 * socks5://base64(user:password)@host:port#Name
 * ```
 */
object Socks5Parser {

    fun parse(link: String): ProxyServerConfig? {
        val trimmed = link.trim()
        val withoutScheme = when {
            trimmed.startsWith("socks5://", ignoreCase = true) -> trimmed.substring("socks5://".length)
            trimmed.startsWith("socks://", ignoreCase = true) -> trimmed.substring("socks://".length)
            else -> return null
        }
        if (withoutScheme.isBlank()) return null

        val fragment = UriParseUtils.extractFragment(trimmed)
        val params = UriParseUtils.extractQueryParams(trimmed)

        // Извлекаем userinfo (до @)
        val atIndex = withoutScheme.indexOf('@')
        var username: String? = null
        var password: String? = null

        val authority: String
        if (atIndex >= 0) {
            val rawUserinfo = withoutScheme.substring(0, atIndex)
            val decodedUserinfo = if (!rawUserinfo.contains(':') && rawUserinfo.length % 4 == 0) {
                try { UriParseUtils.decodeBase64(rawUserinfo) } catch (_: Exception) { rawUserinfo }
            } else rawUserinfo

            val credParts = decodedUserinfo.split(':', limit = 2)
            username = credParts.getOrNull(0)
            password = credParts.getOrNull(1)

            val authorityEnd = withoutScheme.indexOfFirst { it == '?' || it == '#' }
                .let { if (it < 0) withoutScheme.length else it }
            authority = withoutScheme.substring(atIndex + 1, authorityEnd)
        } else {
            val authorityEnd = withoutScheme.indexOfFirst { it == '?' || it == '#' }
                .let { if (it < 0) withoutScheme.length else it }
            authority = withoutScheme.substring(0, authorityEnd)
        }

        val (host, port) = UriParseUtils.extractHostPort(authority)
        if (host.isBlank()) return null

        val effectivePassword = password ?: params["password"] ?: params["pass"]
        val name = when {
            fragment.isNotBlank() -> fragment
            host == "127.0.0.1" || host == "localhost" -> "Локальный SOCKS5 ($port)"
            else -> "SOCKS5 ($host:$port)"
        }

        return ProxyServerConfig(
            id = UUID.randomUUID().toString(),
            name = name,
            protocol = ProxyProtocol.SOCKS5,
            address = host,
            port = port,
            password = effectivePassword,
        )
    }
}

package com.flowvpn.core.parser

import com.flowvpn.core.model.ProxyProtocol
import com.flowvpn.core.model.ProxyServerConfig

/**
 * Парсер ссылок протокола Shadowsocks.
 *
 * ## Поддерживаемые форматы
 *
 * ### SIP002 (стандартный)
 *
 * Два варианта кодирования userinfo:
 *
 * **Вариант 1 — Base64 userinfo:**
 * ```
 * ss://BASE64(method:password)@host:port#Name
 * ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpteXBhc3N3b3Jk@1.2.3.4:8388#MyServer
 * ```
 *
 * **Вариант 2 — URL-encoded userinfo:**
 * ```
 * ss://method:password@host:port#Name
 * ss://chacha20-ietf-poly1305:mypassword@1.2.3.4:8388#MyServer
 * ```
 *
 * ### Legacy формат (deprecated, но используется)
 *
 * Всё закодировано в Base64:
 * ```
 * ss://BASE64(method:password@host:port)#Name
 * ```
 *
 * ### Shadowsocks 2022 (AEAD-2022)
 *
 * Использует PSK (Pre-Shared Key) вместо password:
 * ```
 * ss://2022-blake3-aes-128-gcm:BASE64_KEY@host:port#Name
 * ```
 *
 * ## Методы шифрования
 *
 * AEAD (рекомендуемые): chacha20-ietf-poly1305, aes-128-gcm, aes-256-gcm
 * AEAD-2022: 2022-blake3-aes-128-gcm, 2022-blake3-aes-256-gcm, 2022-blake3-chacha20-poly1305
 */
object ShadowsocksParser {

    fun parse(link: String): ProxyServerConfig? {
        var cleanLink = link.trim()
        if (cleanLink.startsWith("outline://", ignoreCase = true)) {
            cleanLink = cleanLink.substring(10).trim()
        } else if (cleanLink.startsWith("outline-vpn://", ignoreCase = true)) {
            cleanLink = cleanLink.substring(14).trim()
        }
        val withoutScheme = cleanLink.removePrefix("ss://").removePrefix("SS://")

        // Извлекаем fragment (имя)
        val name = UriParseUtils.extractFragment(link)

        // Убираем fragment из рабочей строки
        val withoutFragment = withoutScheme.substringBefore('#')

        return if (withoutFragment.contains('@')) {
            // SIP002 формат: userinfo@host:port
            parseSip002(withoutFragment, name)
        } else {
            // Legacy формат: Base64(method:password@host:port)
            parseLegacy(withoutFragment, name)
        }
    }

    /**
     * Парсинг SIP002 формата.
     *
     * userinfo может быть:
     * - Base64(method:password) — нужно декодировать
     * - method:password — plaintext
     * - method:base64key — для SS2022
     */
    private fun parseSip002(input: String, name: String): ProxyServerConfig? {
        val atIndex = input.lastIndexOf('@')
        if (atIndex < 0) return null

        val userinfo = input.substring(0, atIndex)
        val hostPort = input.substring(atIndex + 1).substringBefore('?').substringBefore('#').trimEnd('/')
        val (host, port) = UriParseUtils.extractHostPort(hostPort)

        // Пытаемся определить, закодирован ли userinfo в Base64
        val decoded = decodeUserinfo(userinfo)
        val colonIndex = decoded.indexOf(':')
        if (colonIndex < 0) return null

        val rawMethod = decoded.substring(0, colonIndex)
        val rawPassword = decoded.substring(colonIndex + 1)
        val method = normalizeMethod(rawMethod)
        val password = decodePassword(rawPassword)
        if (method.isBlank() || password.isBlank()) return null

        val displayName = name.ifBlank { "$host:$port" }

        // Парсим параметры из query string (SIP003 + Outline)
        val params = UriParseUtils.extractQueryParams("?${input.substringAfter('?', "")}")
        val plugin = params["plugin"]
        val pluginOpts = params["plugin-opts"] ?: params["plugin_opts"]
        val prefix = params["prefix"]?.let {
            UriParseUtils.safePercentDecode(it)
        }
        val isOutline = params["outline"] == "1" || prefix != null || input.contains("outline", ignoreCase = true) || name.contains("outline", ignoreCase = true)

        return ProxyServerConfig(
            name = displayName,
            protocol = ProxyProtocol.SHADOWSOCKS,
            address = host,
            port = port,
            method = method,
            password = password,
            plugin = plugin,
            pluginOpts = pluginOpts,
            prefix = prefix,
            isOutline = isOutline,
        )
    }

    /**
     * Парсинг legacy формата — вся строка закодирована в Base64.
     *
     * `BASE64(method:password@host:port)`
     */
    private fun parseLegacy(input: String, name: String): ProxyServerConfig? {
        val decoded = try {
            UriParseUtils.decodeBase64(input)
        } catch (_: Exception) {
            return null
        }

        // method:password@host:port
        val atIndex = decoded.lastIndexOf('@')
        if (atIndex < 0) return null

        val methodPassword = decoded.substring(0, atIndex)
        val hostPort = decoded.substring(atIndex + 1)

        val colonIndex = methodPassword.indexOf(':')
        if (colonIndex < 0) return null

        val method = normalizeMethod(methodPassword.substring(0, colonIndex))
        val password = decodePassword(methodPassword.substring(colonIndex + 1))
        if (method.isBlank() || password.isBlank()) return null

        val (host, port) = UriParseUtils.extractHostPort(hostPort)

        val displayName = name.ifBlank { "$host:$port" }
        val isOutline = decoded.contains("outline", ignoreCase = true) || name.contains("outline", ignoreCase = true)

        return ProxyServerConfig(
            name = displayName,
            protocol = ProxyProtocol.SHADOWSOCKS,
            address = host,
            port = port,
            method = method,
            password = password,
            isOutline = isOutline,
        )
    }

    /**
     * Декодировать userinfo — пробуем Base64, если не получается — plaintext.
     */
    private fun decodeUserinfo(userinfo: String): String {
        val clean = UriParseUtils.safePercentDecode(userinfo)

        // Если содержит ':', вероятно это plaintext method:password
        if (clean.contains(':')) return clean

        // Иначе пробуем Base64
        return try {
            UriParseUtils.decodeBase64(clean)
        } catch (_: Exception) {
            clean
        }
    }

    private fun normalizeMethod(method: String): String {
        val lower = method.trim().lowercase()
        return when (lower) {
            "chacha20-poly1305", "aead_chacha20_poly1305" -> "chacha20-ietf-poly1305"
            "aead_aes_256_gcm" -> "aes-256-gcm"
            "aead_aes_128_gcm" -> "aes-128-gcm"
            "aead_aes_192_gcm" -> "aes-192-gcm"
            else -> lower
        }
    }

    private fun decodePassword(password: String): String {
        return UriParseUtils.safePercentDecode(password)
    }
}

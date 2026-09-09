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
        val withoutScheme = link.removePrefix("ss://").removePrefix("SS://")

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
        val hostPort = input.substring(atIndex + 1).substringBefore('?')
        val (host, port) = UriParseUtils.extractHostPort(hostPort)

        // Пытаемся определить, закодирован ли userinfo в Base64
        val decoded = decodeUserinfo(userinfo)
        val colonIndex = decoded.indexOf(':')
        if (colonIndex < 0) return null

        val method = decoded.substring(0, colonIndex)
        val password = decoded.substring(colonIndex + 1)

        val displayName = name.ifBlank { "$host:$port" }

        // Парсим plugin параметры из query string (SIP003)
        val params = UriParseUtils.extractQueryParams("?${input.substringAfter('?', "")}")

        return ProxyServerConfig(
            name = displayName,
            protocol = ProxyProtocol.SHADOWSOCKS,
            address = host,
            port = port,
            method = method,
            password = password,
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

        val method = methodPassword.substring(0, colonIndex)
        val password = methodPassword.substring(colonIndex + 1)
        val (host, port) = UriParseUtils.extractHostPort(hostPort)

        val displayName = name.ifBlank { "$host:$port" }

        return ProxyServerConfig(
            name = displayName,
            protocol = ProxyProtocol.SHADOWSOCKS,
            address = host,
            port = port,
            method = method,
            password = password,
        )
    }

    /**
     * Декодировать userinfo — пробуем Base64, если не получается — plaintext.
     */
    private fun decodeUserinfo(userinfo: String): String {
        // Если содержит ':', вероятно это plaintext method:password
        if (userinfo.contains(':')) return userinfo

        // Иначе пробуем Base64
        return try {
            UriParseUtils.decodeBase64(userinfo)
        } catch (_: Exception) {
            userinfo
        }
    }
}

package com.flowvpn.core.parser

import com.flowvpn.core.model.ProxyServerConfig

/**
 * Главный диспетчер парсеров протокольных ссылок.
 *
 * Определяет тип ссылки по URI-схеме и делегирует парсинг
 * специализированному парсеру. Поддерживает все популярные форматы
 * VPN-ссылок, используемые в v2rayNG, Sing-box, Nekobox, Clash.
 *
 * ## Поддерживаемые форматы
 *
 * | Схема | Протокол | Парсер |
 * |-------|----------|--------|
 * | `vless://` | VLESS (+ Reality, Vision, XTLS) | [VlessParser] |
 * | `vmess://` | VMess (Base64 JSON) | [VmessParser] |
 * | `ss://` | Shadowsocks (SIP002/SIP008) | [ShadowsocksParser] |
 * | `trojan://` | Trojan-Go | [TrojanParser] |
 * | `hysteria2://` / `hy2://` | Hysteria 2 | [Hysteria2Parser] |
 * | `tuic://` | TUIC v5 | [TuicParser] |
 * | `wireguard://` / `wg://` | WireGuard | [WireguardParser] |
 *
 * ## Формат URI (общий шаблон)
 *
 * ```
 * protocol://userinfo@host:port?param1=value1&param2=value2#fragment(name)
 * ```
 *
 * - **userinfo** — uuid, password или base64-credentials (зависит от протокола)
 * - **host:port** — адрес и порт сервера
 * - **query params** — transport, TLS, encryption настройки
 * - **fragment** — отображаемое имя сервера (URL-encoded)
 */
object LinkParser {

    /**
     * Распарсить одну ссылку в конфигурацию сервера.
     *
     * @param link URI-ссылка (e.g., "vless://uuid@host:443?type=ws#Name")
     * @return конфигурация сервера или null если формат не распознан
     */
    fun parse(link: String): ProxyServerConfig? {
        val trimmed = link.trim()
        if (trimmed.isBlank()) return null

        return try {
            when {
                trimmed.startsWith("vless://", ignoreCase = true) ->
                    VlessParser.parse(trimmed)

                trimmed.startsWith("vmess://", ignoreCase = true) ->
                    VmessParser.parse(trimmed)

                trimmed.startsWith("ss://", ignoreCase = true) ->
                    ShadowsocksParser.parse(trimmed)

                trimmed.startsWith("trojan://", ignoreCase = true) ->
                    TrojanParser.parse(trimmed)

                trimmed.startsWith("hysteria2://", ignoreCase = true) ||
                trimmed.startsWith("hy2://", ignoreCase = true) ->
                    Hysteria2Parser.parse(trimmed)

                trimmed.startsWith("tuic://", ignoreCase = true) ->
                    TuicParser.parse(trimmed)

                trimmed.startsWith("wireguard://", ignoreCase = true) ||
                trimmed.startsWith("wg://", ignoreCase = true) ->
                    WireguardParser.parse(trimmed)

                trimmed.startsWith("openflux://", ignoreCase = true) ->
                    OpenFluxParser.parse(trimmed)

                trimmed.startsWith("socks5://", ignoreCase = true) ||
                trimmed.startsWith("socks://", ignoreCase = true) ->
                    Socks5Parser.parse(trimmed)

                else -> null
            }
        } catch (e: Exception) {
            timber.log.Timber.w(e, "LinkParser: Ошибка парсинга ссылки: ${trimmed.take(50)}...")
            null
        }
    }

    /**
     * Распарсить множество ссылок (по одной на строку).
     *
     * Пропускает пустые строки и нераспознанные форматы.
     * Не бросает исключений — ошибки логируются.
     *
     * @param text текст с ссылками (разделитель — перенос строки)
     * @return список успешно распарсенных конфигураций
     */
    fun parseMultiple(text: String): List<ProxyServerConfig> {
        return text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { parse(it) }
    }

    /**
     * Определить, является ли строка VPN-ссылкой.
     */
    fun isProxyLink(text: String): Boolean {
        val t = text.trim().lowercase()
        return t.startsWith("vless://") ||
                t.startsWith("vmess://") ||
                t.startsWith("ss://") ||
                t.startsWith("trojan://") ||
                t.startsWith("hysteria2://") ||
                t.startsWith("hy2://") ||
                t.startsWith("tuic://") ||
                t.startsWith("wireguard://") ||
                t.startsWith("wg://") ||
                t.startsWith("openflux://") ||
                t.startsWith("socks5://") ||
                t.startsWith("socks://")
    }
}

/**
 * Утилиты для парсинга URI-ссылок.
 *
 * Все парсеры используют эти хелперы для извлечения
 * query-параметров, fragment и userinfo из URI.
 */
internal object UriParseUtils {

    /**
     * Извлечь fragment (имя сервера) из URI.
     * Fragment — всё после `#`, URL-decoded.
     *
     * `vless://...#%F0%9F%87%A9%F0%9F%87%AA%20Frankfurt` → "🇩🇪 Frankfurt"
     */
    fun extractFragment(uri: String): String {
        val hashIndex = uri.lastIndexOf('#')
        if (hashIndex < 0 || hashIndex >= uri.length - 1) return ""
        return java.net.URLDecoder.decode(uri.substring(hashIndex + 1), "UTF-8")
    }

    /**
     * Извлечь query-параметры из URI в Map.
     *
     * `?type=ws&path=/ws&host=example.com` → {"type":"ws", "path":"/ws", "host":"example.com"}
     */
    fun extractQueryParams(uri: String): Map<String, String> {
        val queryStart = uri.indexOf('?')
        if (queryStart < 0) return emptyMap()

        val queryEnd = uri.indexOf('#', queryStart).let { if (it < 0) uri.length else it }
        val queryString = uri.substring(queryStart + 1, queryEnd)

        return queryString.split('&')
            .filter { it.contains('=') }
            .associate { param ->
                val eqIndex = param.indexOf('=')
                val key = java.net.URLDecoder.decode(param.substring(0, eqIndex), "UTF-8")
                val value = java.net.URLDecoder.decode(param.substring(eqIndex + 1), "UTF-8")
                key to value
            }
    }

    /**
     * Извлечь host:port из authority части URI.
     *
     * Обрабатывает IPv6 адреса в квадратных скобках: `[::1]:443`
     *
     * @return Pair(host, port)
     */
    fun extractHostPort(authority: String): Pair<String, Int> {
        return if (authority.startsWith("[")) {
            // IPv6: [::1]:443
            val closeBracket = authority.indexOf(']')
            val host = authority.substring(1, closeBracket)
            val port = authority.substring(closeBracket + 2).toIntOrNull() ?: 443
            host to port
        } else {
            val parts = authority.split(':')
            val host = parts[0]
            val port = parts.getOrNull(1)?.toIntOrNull() ?: 443
            host to port
        }
    }

    /**
     * Безопасное декодирование Base64 (стандартный + URL-safe).
     */
    fun decodeBase64(encoded: String): String {
        val padded = when (encoded.length % 4) {
            2 -> "$encoded=="
            3 -> "$encoded="
            else -> encoded
        }

        return try {
            // Пробуем стандартный Base64
            String(java.util.Base64.getDecoder().decode(padded))
        } catch (_: Exception) {
            try {
                // Пробуем URL-safe Base64
                String(java.util.Base64.getUrlDecoder().decode(padded))
            } catch (_: Exception) {
                // Последняя попытка без padding
                String(java.util.Base64.getUrlDecoder().decode(encoded))
            }
        }
    }
}

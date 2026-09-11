package com.flowvpn.core.parser

import com.flowvpn.core.model.ProxyProtocol
import com.flowvpn.core.model.ProxyServerConfig
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.net.URLDecoder

/**
 * Специализированный парсер конфигураций и ключей доступа Outline VPN.
 *
 * ## Поддерживаемые форматы Outline:
 *
 * 1. **Статические ключи доступа (`ss://`)**:
 *    - `ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpwYXNz@1.2.3.4:8388/?outline=1#Frankfurt`
 *    - `ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpwYXNz@1.2.3.4:8388/?outline=1&prefix=POST%20%2F%20HTTP%2F1.1...#Frankfurt`
 *    - Plaintext: `ss://chacha20-ietf-poly1305:mypass@1.2.3.4:8388/?outline=1#Server`
 *    - Префиксы схем: `outline://ss://...`, `outline-vpn://ss://...`, `outline://...`
 *
 * 2. **Динамические ключи доступа (`ssconf://`)**:
 *    - `ssconf://domain.com/path/to/key#Name`
 *
 * 3. **Текстовые приглашения Outline**:
 *    - Сообщения из Telegram/почты с текстом и ссылкой `ss://...` внутри.
 *
 * 4. **JSON-конфигурации**:
 *    - Формат одного сервера: `{"server": "...", "server_port": 8388, "password": "...", "method": "...", "prefix": "..."}`
 *    - Формат API Outline Manager: `{"accessKeys": [{"name": "...", "accessUrl": "ss://..."}]}`
 *    - Формат с транспортом: `{"transport": "ss://..."}`
 */
object OutlineParser {

    private val OUTLINE_URI_REGEX = Regex(
        """(?i)(?:outline(?:-vpn)?://)?(?:ss|ssconf)://[^\s"'<>]+"""
    )

    /**
     * Проверить, относится ли ссылка или текст к Outline.
     */
    fun isOutlineLink(text: String): Boolean {
        val trimmed = text.trim()
        val lower = trimmed.lowercase()
        return lower.startsWith("outline://") ||
                lower.startsWith("outline-vpn://") ||
                lower.startsWith("ssconf://") ||
                (lower.startsWith("ss://") && (lower.contains("outline=1") || lower.contains("prefix=")))
    }

    /**
     * Распарсить одиночный ключ доступа Outline.
     */
    fun parseAccessKey(link: String): ProxyServerConfig? {
        var cleanLink = link.trim()

        // Снимаем внешние схемы outline:// или outline-vpn://
        if (cleanLink.startsWith("outline://", ignoreCase = true)) {
            cleanLink = cleanLink.substring(10).trim()
        } else if (cleanLink.startsWith("outline-vpn://", ignoreCase = true)) {
            cleanLink = cleanLink.substring(14).trim()
        }

        // Если после снятия схемы остался префикс ssconf://
        if (cleanLink.startsWith("ssconf://", ignoreCase = true)) {
            return null // ssconf:// — это динамическая ссылка на профиль, обрабатывается через SubscriptionManager
        }

        if (!cleanLink.startsWith("ss://", ignoreCase = true)) {
            // Если схема отсутствовала, но была outline://
            cleanLink = "ss://$cleanLink"
        }

        return parseSsKey(cleanLink)
    }

    /**
     * Парсинг статического ключа ss://
     */
    private fun parseSsKey(link: String): ProxyServerConfig? {
        val withoutScheme = link.substring(5) // remove "ss://"
        val fragmentName = UriParseUtils.extractFragment(link)
        val withoutFragment = withoutScheme.substringBefore('#')

        if (withoutFragment.contains('@')) {
            // SIP002: userinfo@host:port/?params
            val atIndex = withoutFragment.lastIndexOf('@')
            val userinfo = withoutFragment.substring(0, atIndex)
            val hostPortAndQuery = withoutFragment.substring(atIndex + 1)

            val hostPort = hostPortAndQuery.substringBefore('?').substringBefore('#').trimEnd('/')
            val (host, port) = UriParseUtils.extractHostPort(hostPort)

            val queryParams = if (hostPortAndQuery.contains('?')) {
                UriParseUtils.extractQueryParams("?${hostPortAndQuery.substringAfter('?')}")
            } else {
                emptyMap()
            }

            val decodedUserinfo = decodeUserinfo(userinfo)
            val colonIndex = decodedUserinfo.indexOf(':')
            if (colonIndex < 0) return null

            val rawMethod = decodedUserinfo.substring(0, colonIndex)
            val rawPassword = decodedUserinfo.substring(colonIndex + 1)
            val method = normalizeMethod(rawMethod)
            val password = decodePassword(rawPassword)
            if (method.isBlank() || password.isBlank()) return null

            val prefix = queryParams["prefix"]?.let {
                UriParseUtils.safePercentDecode(it)
            }

            val plugin = queryParams["plugin"]
            val displayName = fragmentName.ifBlank { "Outline ($host:$port)" }

            return ProxyServerConfig(
                name = displayName,
                protocol = ProxyProtocol.SHADOWSOCKS,
                address = host,
                port = port,
                method = method,
                password = password,
                prefix = prefix,
                plugin = plugin,
                isOutline = true,
            )
        } else {
            // Legacy base64
            val decoded = try {
                UriParseUtils.decodeBase64(withoutFragment)
            } catch (_: Exception) {
                return null
            }

            val atIndex = decoded.lastIndexOf('@')
            if (atIndex < 0) return null

            val methodPassword = decoded.substring(0, atIndex)
            val hostPort = decoded.substring(atIndex + 1)

            val colonIndex = methodPassword.indexOf(':')
            if (colonIndex < 0) return null

            val rawMethod = methodPassword.substring(0, colonIndex)
            val rawPassword = methodPassword.substring(colonIndex + 1)
            val method = normalizeMethod(rawMethod)
            val password = decodePassword(rawPassword)
            if (method.isBlank() || password.isBlank()) return null

            val (host, port) = UriParseUtils.extractHostPort(hostPort)
            val displayName = fragmentName.ifBlank { "Outline ($host:$port)" }

            return ProxyServerConfig(
                name = displayName,
                protocol = ProxyProtocol.SHADOWSOCKS,
                address = host,
                port = port,
                method = method,
                password = password,
                isOutline = true,
            )
        }
    }



    /**
     * Парсинг JSON-конфигураций Outline.
     */
    fun parseJson(json: String): List<ProxyServerConfig> {
        val trimmed = json.trim()
        val results = mutableListOf<ProxyServerConfig>()

        try {
            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    parseSingleJsonObject(item)?.let { results.add(it) }
                }
            } else if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)

                // Outline Server Manager API: {"accessKeys": [...]}
                if (obj.has("accessKeys")) {
                    val keysArray = obj.getJSONArray("accessKeys")
                    for (i in 0 until keysArray.length()) {
                        val keyObj = keysArray.getJSONObject(i)
                        val accessUrl = keyObj.optString("accessUrl")
                        val keyName = keyObj.optString("name")

                        if (accessUrl.isNotBlank()) {
                            parseAccessKey(accessUrl)?.let { cfg ->
                                val finalCfg = if (keyName.isNotBlank()) cfg.copy(name = keyName) else cfg
                                results.add(finalCfg)
                            }
                        } else {
                            parseSingleJsonObject(keyObj)?.let { results.add(it) }
                        }
                    }
                }
                // Outline Transport format: {"transport": "ss://..."} или {"transport": {...}}
                else if (obj.has("transport")) {
                    val transportVal = obj.get("transport")
                    if (transportVal is String) {
                        parseAccessKey(transportVal)?.let { results.add(it) }
                    } else if (transportVal is JSONObject) {
                        parseSingleJsonObject(transportVal)?.let { results.add(it) }
                    }
                }
                // Стандартный Outline Shadowsocks JSON: {"server": "...", "server_port": ...}
                else {
                    parseSingleJsonObject(obj)?.let { results.add(it) }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "OutlineParser: Ошибка парсинга JSON")
        }

        return results
    }

    private fun parseSingleJsonObject(obj: JSONObject): ProxyServerConfig? {
        val server = obj.optString("server").takeIf { it.isNotBlank() } ?: return null
        val port = when {
            obj.has("server_port") -> obj.getInt("server_port")
            obj.has("port") -> obj.getInt("port")
            else -> 8388
        }
        val rawPassword = obj.optString("password").takeIf { it.isNotBlank() } ?: return null
        val password = decodePassword(rawPassword)
        val rawMethod = obj.optString("method", "chacha20-ietf-poly1305")
        val method = normalizeMethod(rawMethod)
        if (password.isBlank() || method.isBlank()) return null

        val prefix = obj.optString("prefix").takeIf { it.isNotBlank() }
        val name = obj.optString("name").ifBlank { "Outline ($server:$port)" }

        return ProxyServerConfig(
            name = name,
            protocol = ProxyProtocol.SHADOWSOCKS,
            address = server,
            port = port,
            method = method,
            password = password,
            prefix = prefix,
            isOutline = true,
        )
    }

    /**
     * Найти и извлечь все ссылки Outline (ss://, ssconf://, outline://) из произвольного текста.
     */
    fun extractAccessKeys(text: String): List<String> {
        return OUTLINE_URI_REGEX.findAll(text)
            .map { it.value.trim() }
            .distinct()
            .toList()
    }

    /**
     * Универсальный парсинг любого текста: JSON, ссылки или сообщения-приглашения.
     */
    fun parseMultipleOrText(text: String): List<ProxyServerConfig> {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return emptyList()

        // Проверяем JSON
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val fromJson = parseJson(trimmed)
            if (fromJson.isNotEmpty()) return fromJson
        }

        // Извлекаем ключи из текста
        val extractedKeys = extractAccessKeys(trimmed)
        if (extractedKeys.isNotEmpty()) {
            return extractedKeys.mapNotNull { parseAccessKey(it) }
        }

        // Построчный парсинг
        return trimmed.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { parseAccessKey(it) }
    }

    private fun decodeUserinfo(userinfo: String): String {
        val clean = UriParseUtils.safePercentDecode(userinfo)

        if (clean.contains(':')) return clean
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

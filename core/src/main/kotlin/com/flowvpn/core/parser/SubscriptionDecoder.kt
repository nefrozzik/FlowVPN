package com.flowvpn.core.parser

import com.flowvpn.core.model.ProxyServerConfig

/**
 * Универсальный декодер подписок.
 *
 * Автоматически определяет формат подписки и делегирует парсинг:
 *
 * ## Поддерживаемые форматы
 *
 * ### 1. Base64-лист ссылок (самый распространённый)
 *
 * Текст, закодированный в Base64, содержащий ссылки по одной на строку:
 * ```
 * BASE64(
 *   vless://uuid@host1:443#Server1
 *   vmess://eyJ...#Server2
 *   ss://method:pass@host3:8388#Server3
 * )
 * ```
 * Провайдеры подписок возвращают этот формат в 90%+ случаев.
 *
 * ### 2. Sing-box JSON профиль
 *
 * Полная конфигурация sing-box с массивом outbounds:
 * ```json
 * { "outbounds": [ { "type": "vless", ... }, ... ] }
 * ```
 *
 * ### 3. Clash/Clash.Meta YAML профиль
 *
 * ```yaml
 * proxies:
 *   - name: Server1
 *     type: vless
 *     server: host
 *     port: 443
 *     ...
 * ```
 *
 * ### 4. Plaintext ссылки (без кодирования)
 *
 * Просто список ссылок по одной на строку (вставка из буфера обмена).
 */
object SubscriptionDecoder {

    /**
     * Декодировать содержимое подписки в список серверов.
     *
     * Автоматически определяет формат:
     * 1. Проверяет, является ли текст JSON (начинается с `{`)
     * 2. Проверяет, является ли текст YAML (содержит `proxies:`)
     * 3. Проверяет, содержит ли текст прямые ссылки
     * 4. Пробует декодировать как Base64
     *
     * @param content содержимое подписки (body ответа HTTP или текст из буфера)
     * @return список конфигураций серверов
     */
    fun decode(content: String): List<ProxyServerConfig> {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return emptyList()

        return when (detectFormat(trimmed)) {
            Format.SINGBOX_JSON -> SingBoxProfileParser.parse(trimmed)
            Format.CLASH_YAML -> ClashProfileParser.parse(trimmed)
            Format.PLAIN_LINKS -> LinkParser.parseMultiple(trimmed)
            Format.BASE64_LINKS -> decodeBase64Links(trimmed)
            Format.UNKNOWN -> {
                // Последняя попытка — вдруг это Base64
                tryDecodeAsBase64(trimmed)
            }
        }
    }

    /**
     * Определить формат подписки по содержимому.
     */
    private fun detectFormat(content: String): Format {
        val firstChar = content.trimStart().firstOrNull()

        // JSON: начинается с { или [
        if (firstChar == '{' || firstChar == '[') {
            return if (content.contains("\"outbounds\"") || content.contains("\"inbounds\"")) {
                Format.SINGBOX_JSON
            } else {
                Format.SINGBOX_JSON // Пробуем как JSON в любом случае
            }
        }

        // YAML: содержит ключевые маркеры Clash
        if (content.contains("proxies:") || content.contains("Proxy:")) {
            return Format.CLASH_YAML
        }

        // Plaintext ссылки: первая непустая строка — VPN-ссылка
        val firstLine = content.lines().firstOrNull { it.isNotBlank() } ?: ""
        if (LinkParser.isProxyLink(firstLine)) {
            return Format.PLAIN_LINKS
        }

        // Вероятно Base64 — если строка содержит только Base64 символы
        if (isLikelyBase64(content)) {
            return Format.BASE64_LINKS
        }

        return Format.UNKNOWN
    }

    /**
     * Декодировать Base64-лист ссылок.
     */
    private fun decodeBase64Links(encoded: String): List<ProxyServerConfig> {
        return try {
            // Убираем переносы строк из Base64 (некоторые провайдеры их добавляют)
            val cleaned = encoded.replace(Regex("\\s+"), "")
            val decoded = UriParseUtils.decodeBase64(cleaned)
            LinkParser.parseMultiple(decoded)
        } catch (e: Exception) {
            timber.log.Timber.w(e, "SubscriptionDecoder: Ошибка декодирования Base64")
            emptyList()
        }
    }

    /**
     * Попытка декодирования как Base64 (fallback).
     */
    private fun tryDecodeAsBase64(content: String): List<ProxyServerConfig> {
        return try {
            val cleaned = content.replace(Regex("\\s+"), "")
            val decoded = UriParseUtils.decodeBase64(cleaned)
            val results = LinkParser.parseMultiple(decoded)
            if (results.isNotEmpty()) results
            else LinkParser.parseMultiple(content) // Fallback: пробуем как plaintext
        } catch (_: Exception) {
            LinkParser.parseMultiple(content)
        }
    }

    /**
     * Эвристика: строка похожа на Base64?
     *
     * Base64 содержит только [A-Za-z0-9+/=] (стандартный)
     * или [A-Za-z0-9-_=] (URL-safe).
     * Переносы строк допустимы.
     */
    private fun isLikelyBase64(text: String): Boolean {
        val cleaned = text.replace(Regex("\\s+"), "")
        if (cleaned.length < 4) return false
        return cleaned.matches(Regex("^[A-Za-z0-9+/\\-_]+=*$"))
    }

    private enum class Format {
        SINGBOX_JSON,
        CLASH_YAML,
        PLAIN_LINKS,
        BASE64_LINKS,
        UNKNOWN,
    }
}

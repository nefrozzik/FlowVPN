package com.flowvpn.core.parser

import com.flowvpn.core.model.ProxyProtocol
import com.flowvpn.core.model.ProxyServerConfig
import com.flowvpn.core.model.TlsConfig
import com.flowvpn.core.model.TransportConfig

/**
 * Парсер ссылок протокола VMess.
 *
 * ## Формат URI
 *
 * VMess использует **нестандартный** формат — Base64-encoded JSON объект:
 *
 * ```
 * vmess://eyJ2IjoiMiIsInBzIjoiTmFtZSIsImFkZCI6Imhvc3QiLCJwb3J0IjoiNDQzIiwiaWQiOiJ1dWlkIiwiYWlkIjoiMCIsInNjeSI6ImF1dG8iLCJuZXQiOiJ3cyIsInR5cGUiOiJub25lIiwiaG9zdCI6ImV4YW1wbGUuY29tIiwicGF0aCI6Ii93cyIsInRscyI6InRscyJ9
 * ```
 *
 * ## JSON структура (v2rayN формат, v=2)
 *
 * ```json
 * {
 *   "v": "2",              // Версия формата (всегда "2")
 *   "ps": "Server Name",   // Имя сервера
 *   "add": "1.2.3.4",      // Адрес сервера
 *   "port": "443",         // Порт (строка!)
 *   "id": "uuid",          // UUID пользователя
 *   "aid": "0",            // Alter ID (0 для AEAD)
 *   "scy": "auto",         // Метод шифрования
 *   "net": "ws",           // Сеть/транспорт: tcp, ws, h2, grpc, quic
 *   "type": "none",        // Тип обфускации header (для TCP)
 *   "host": "cdn.com",     // WebSocket Host / HTTP Host
 *   "path": "/path",       // WebSocket/HTTP path
 *   "tls": "tls",          // "tls" или "" (пустая строка = нет TLS)
 *   "sni": "example.com",  // SNI
 *   "alpn": "h2,http/1.1", // ALPN
 *   "fp": "chrome"         // uTLS fingerprint
 * }
 * ```
 *
 * > **Важно**: VMess — единственный протокол, где ссылка содержит
 * > Base64-encoded JSON вместо стандартного URI. Это legacy-формат
 * > от v2rayN, ставший де-факто стандартом.
 */
object VmessParser {

    fun parse(link: String): ProxyServerConfig? {
        // vmess://BASE64_JSON
        val base64Part = link.removePrefix("vmess://").removePrefix("VMess://").trim()

        // Декодируем Base64 → JSON строка
        val jsonString = try {
            UriParseUtils.decodeBase64(base64Part)
        } catch (e: Exception) {
            timber.log.Timber.w(e, "VmessParser: Ошибка декодирования Base64")
            return null
        }

        // Парсим JSON вручную (без зависимости от Moshi — простой формат)
        val fields = parseSimpleJson(jsonString) ?: return null

        val address = fields["add"] ?: fields["address"] ?: return null
        val port = (fields["port"] ?: "443").toIntOrNull() ?: 443
        val uuid = fields["id"] ?: return null
        val alterId = (fields["aid"] ?: "0").toIntOrNull() ?: 0
        val name = fields["ps"] ?: fields["remarks"] ?: "$address:$port"

        // Транспорт
        val netType = fields["net"] ?: fields["network"] ?: "tcp"
        val transport = buildTransport(netType, fields)

        // TLS
        val tlsField = fields["tls"] ?: ""
        val tls = if (tlsField.equals("tls", ignoreCase = true)) {
            TlsConfig(
                enabled = true,
                serverName = fields["sni"] ?: fields["host"],
                alpn = fields["alpn"]?.split(",")?.map { it.trim() },
                utlsFingerprint = fields["fp"],
            )
        } else null

        return ProxyServerConfig(
            name = name,
            protocol = ProxyProtocol.VMESS,
            address = address,
            port = port,
            uuid = uuid,
            alterId = alterId,
            method = fields["scy"] ?: "auto",
            security = if (tls != null) "tls" else "none",
            transport = transport,
            tls = tls,
        )
    }

    private fun buildTransport(type: String, fields: Map<String, String>): TransportConfig? {
        if (type == "tcp" && fields["type"]?.equals("none", true) != false) {
            return null
        }

        val mappedType = when (type) {
            "h2" -> "http"  // sing-box использует "http" для HTTP/2
            else -> type
        }

        return TransportConfig(
            type = mappedType,
            path = fields["path"],
            host = fields["host"],
            serviceName = if (type == "grpc") (fields["path"] ?: fields["serviceName"]) else null,
        )
    }

    /**
     * Простой JSON-парсер для плоского VMess-объекта.
     *
     * Не используем полноценную JSON-библиотеку, т.к.:
     * 1. VMess JSON всегда плоский (нет вложенных объектов/массивов)
     * 2. Все значения — строки
     * 3. Избегаем лишних зависимостей в парсере
     */
    internal fun parseSimpleJson(json: String): Map<String, String>? {
        val result = mutableMapOf<String, String>()

        // Убираем пробелы и фигурные скобки
        val content = json.trim().removeSurrounding("{", "}")

        // Regex для "key": "value" и "key": число
        val pattern = """"(\w+)"\s*:\s*"?([^",}]*)"?""".toRegex()

        pattern.findAll(content).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2].trim().removeSurrounding("\"")
            result[key] = value
        }

        return result.ifEmpty { null }
    }
}

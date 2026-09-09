package com.flowvpn.core.parser

import com.flowvpn.core.model.ProxyProtocol
import com.flowvpn.core.model.ProxyServerConfig
import com.flowvpn.core.model.TlsConfig
import com.flowvpn.core.model.TransportConfig

/**
 * Парсер sing-box JSON профилей.
 *
 * Извлекает конфигурации серверов из массива `outbounds` в sing-box JSON.
 *
 * ## Структура sing-box config
 *
 * ```json
 * {
 *   "outbounds": [
 *     {
 *       "type": "vless",
 *       "tag": "proxy-1",
 *       "server": "1.2.3.4",
 *       "server_port": 443,
 *       "uuid": "...",
 *       "tls": { "enabled": true, "server_name": "..." },
 *       "transport": { "type": "ws", "path": "/ws" }
 *     },
 *     { "type": "direct", "tag": "direct" },
 *     ...
 *   ]
 * }
 * ```
 *
 * Парсер извлекает только прокси-outbounds, пропуская
 * служебные (direct, block, dns).
 */
object SingBoxProfileParser {

    /** Типы outbound, которые являются прокси (не служебные) */
    private val PROXY_TYPES = setOf(
        "vless", "vmess", "shadowsocks", "trojan",
        "hysteria2", "hysteria", "tuic", "wireguard",
    )

    fun parse(json: String): List<ProxyServerConfig> {
        val results = mutableListOf<ProxyServerConfig>()

        // Извлекаем массив outbounds
        val outboundsContent = extractJsonArray(json, "outbounds") ?: return emptyList()

        // Разделяем на отдельные объекты
        val objects = splitJsonObjects(outboundsContent)

        for (obj in objects) {
            val fields = extractJsonFields(obj)
            val type = fields["type"] ?: continue

            // Пропускаем служебные outbounds
            if (type !in PROXY_TYPES) continue

            val config = parseOutbound(type, fields, obj)
            if (config != null) {
                results.add(config)
            }
        }

        return results
    }

    private fun parseOutbound(type: String, fields: Map<String, String>, rawJson: String): ProxyServerConfig? {
        val server = fields["server"] ?: return null
        val port = (fields["server_port"] ?: fields["port"])?.toIntOrNull() ?: return null
        val tag = fields["tag"] ?: "$server:$port"

        val protocol = when (type) {
            "vless" -> ProxyProtocol.VLESS
            "vmess" -> ProxyProtocol.VMESS
            "shadowsocks" -> ProxyProtocol.SHADOWSOCKS
            "trojan" -> ProxyProtocol.TROJAN
            "hysteria2", "hysteria" -> ProxyProtocol.HYSTERIA2
            "tuic" -> ProxyProtocol.TUIC
            "wireguard" -> ProxyProtocol.WIREGUARD
            else -> return null
        }

        // TLS
        val tlsBlock = extractJsonObject(rawJson, "tls")
        val tls = if (tlsBlock != null) {
            val tlsFields = extractJsonFields(tlsBlock)
            if (tlsFields["enabled"] != "false") {
                TlsConfig(
                    enabled = true,
                    serverName = tlsFields["server_name"],
                    insecure = tlsFields["insecure"] == "true",
                    alpn = tlsFields["alpn"]?.removeSurrounding("[", "]")
                        ?.split(",")?.map { it.trim().removeSurrounding("\"") },
                )
            } else null
        } else null

        // Transport
        val transportBlock = extractJsonObject(rawJson, "transport")
        val transport = if (transportBlock != null) {
            val tFields = extractJsonFields(transportBlock)
            TransportConfig(
                type = tFields["type"] ?: "tcp",
                path = tFields["path"],
                host = tFields["host"],
                serviceName = tFields["service_name"],
            )
        } else null

        return ProxyServerConfig(
            name = tag,
            protocol = protocol,
            address = server,
            port = port,
            uuid = fields["uuid"],
            password = fields["password"],
            method = fields["method"],
            flow = fields["flow"],
            alterId = fields["alter_id"]?.toIntOrNull() ?: 0,
            tls = tls,
            transport = transport,
            security = if (tls != null) "tls" else "none",
            // WireGuard
            privateKey = fields["private_key"],
            peerPublicKey = fields["peer_public_key"],
            preSharedKey = fields["pre_shared_key"],
            // Hysteria2
            obfsPassword = fields["obfs_password"],
            upMbps = fields["up_mbps"]?.toIntOrNull(),
            downMbps = fields["down_mbps"]?.toIntOrNull(),
            // TUIC
            congestionControl = fields["congestion_control"],
        )
    }

    // ─── Простые JSON-хелперы (без зависимости от JSON-библиотеки) ───

    /** Извлечь содержимое JSON-массива по ключу */
    internal fun extractJsonArray(json: String, key: String): String? {
        val keyPattern = """"$key"\s*:\s*\["""
        val match = Regex(keyPattern).find(json) ?: return null
        val start = match.range.last + 1
        var depth = 1
        var i = start
        while (i < json.length && depth > 0) {
            when (json[i]) {
                '[' -> depth++
                ']' -> depth--
            }
            i++
        }
        return json.substring(start, i - 1)
    }

    /** Извлечь вложенный JSON-объект по ключу */
    internal fun extractJsonObject(json: String, key: String): String? {
        val keyPattern = """"$key"\s*:\s*\{"""
        val match = Regex(keyPattern).find(json) ?: return null
        val start = match.range.last
        var depth = 1
        var i = start + 1
        while (i < json.length && depth > 0) {
            when (json[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return json.substring(start, i)
    }

    /** Разделить содержимое JSON-массива на отдельные объекты */
    internal fun splitJsonObjects(arrayContent: String): List<String> {
        val objects = mutableListOf<String>()
        var depth = 0
        var start = -1

        for (i in arrayContent.indices) {
            when (arrayContent[i]) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        objects.add(arrayContent.substring(start, i + 1))
                        start = -1
                    }
                }
            }
        }

        return objects
    }

    /** Извлечь плоские string-поля из JSON-объекта */
    internal fun extractJsonFields(json: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        // Ищем "key": "value" и "key": number и "key": true/false
        val pattern = """"(\w+)"\s*:\s*"?([^",}\]]*)"?""".toRegex()
        pattern.findAll(json).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2].trim().removeSurrounding("\"")
            if (value.isNotBlank()) {
                result[key] = value
            }
        }
        return result
    }
}

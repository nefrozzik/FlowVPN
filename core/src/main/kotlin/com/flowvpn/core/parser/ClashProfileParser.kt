package com.flowvpn.core.parser

import com.flowvpn.core.model.ProxyProtocol
import com.flowvpn.core.model.ProxyServerConfig
import com.flowvpn.core.model.TlsConfig
import com.flowvpn.core.model.TransportConfig

/**
 * Парсер Clash / Clash.Meta YAML профилей.
 *
 * ## Формат Clash YAML
 *
 * ```yaml
 * proxies:
 *   - name: "🇩🇪 Frankfurt"
 *     type: vless
 *     server: 1.2.3.4
 *     port: 443
 *     uuid: "..."
 *     tls: true
 *     servername: example.com
 *     network: ws
 *     ws-opts:
 *       path: /ws
 *       headers:
 *         Host: cdn.example.com
 *     flow: xtls-rprx-vision
 *     client-fingerprint: chrome
 *     reality-opts:
 *       public-key: "..."
 *       short-id: "..."
 * ```
 *
 * ## Поддерживаемые типы прокси
 *
 * - `vless` (Clash.Meta / mihomo)
 * - `vmess`
 * - `ss` (Shadowsocks)
 * - `trojan`
 * - `hysteria2` / `hy2`
 * - `tuic`
 * - `wireguard` / `wg`
 *
 * > **Примечание**: Оригинальный Clash не поддерживает VLESS, Reality, Hysteria2.
 * > Эти протоколы доступны только в Clash.Meta (mihomo).
 *
 * ## Реализация
 *
 * Используется построчный парсинг YAML без внешних библиотек,
 * т.к. формат Clash-профилей достаточно предсказуемый и не использует
 * сложные YAML-конструкции (якоря, merge keys и т.д.).
 */
object ClashProfileParser {

    fun parse(yaml: String): List<ProxyServerConfig> {
        val results = mutableListOf<ProxyServerConfig>()

        // Находим секцию proxies:
        val proxiesSection = extractProxiesSection(yaml) ?: return emptyList()

        // Разбиваем на отдельные прокси-блоки (по "- name:" или "- {name:")
        val proxyBlocks = splitProxyBlocks(proxiesSection)

        for (block in proxyBlocks) {
            val config = parseProxyBlock(block)
            if (config != null) {
                results.add(config)
            }
        }

        return results
    }

    /**
     * Извлечь секцию `proxies:` из YAML.
     */
    private fun extractProxiesSection(yaml: String): String? {
        val lines = yaml.lines()
        var startIndex = -1

        // Ищем строку "proxies:" на верхнем уровне (без отступа)
        for (i in lines.indices) {
            val trimmed = lines[i].trimEnd()
            if (trimmed == "proxies:" || trimmed == "Proxy:") {
                startIndex = i + 1
                break
            }
        }
        if (startIndex < 0) return null

        // Собираем содержимое до следующей секции верхнего уровня
        val sectionLines = mutableListOf<String>()
        for (i in startIndex until lines.size) {
            val line = lines[i]
            // Пустые строки и комментарии пропускаем
            if (line.isBlank() || line.trimStart().startsWith("#")) {
                sectionLines.add(line)
                continue
            }
            // Если строка без отступа — это новая секция, стоп
            if (line.isNotBlank() && !line.startsWith(" ") && !line.startsWith("\t") && !line.startsWith("-")) {
                break
            }
            sectionLines.add(line)
        }

        return sectionLines.joinToString("\n")
    }

    /**
     * Разбить секцию proxies на отдельные блоки.
     * Каждый блок начинается с `  - name:` или `  - {name:`
     */
    private fun splitProxyBlocks(section: String): List<String> {
        val blocks = mutableListOf<String>()
        val currentBlock = StringBuilder()

        for (line in section.lines()) {
            val trimmed = line.trimStart()

            // Новый блок начинается с "- name:" или "- {" (inline YAML)
            if (trimmed.startsWith("- name:") || trimmed.startsWith("- {") ||
                trimmed.startsWith("-  name:") || trimmed.startsWith("- \"name\":")
            ) {
                if (currentBlock.isNotBlank()) {
                    blocks.add(currentBlock.toString())
                    currentBlock.clear()
                }
            }

            if (currentBlock.isNotEmpty() || trimmed.startsWith("-")) {
                currentBlock.appendLine(line)
            }
        }

        if (currentBlock.isNotBlank()) {
            blocks.add(currentBlock.toString())
        }

        return blocks
    }

    /**
     * Распарсить один прокси-блок YAML в [ProxyServerConfig].
     */
    private fun parseProxyBlock(block: String): ProxyServerConfig? {
        // Парсим YAML-поля в Map
        val fields = parseYamlFields(block)

        val name = fields["name"] ?: return null
        val type = fields["type"] ?: return null
        val server = fields["server"] ?: return null
        val port = fields["port"]?.toIntOrNull() ?: return null

        val protocol = when (type.lowercase()) {
            "vless" -> ProxyProtocol.VLESS
            "vmess" -> ProxyProtocol.VMESS
            "ss", "shadowsocks" -> ProxyProtocol.SHADOWSOCKS
            "trojan" -> ProxyProtocol.TROJAN
            "hysteria2", "hy2" -> ProxyProtocol.HYSTERIA2
            "tuic" -> ProxyProtocol.TUIC
            "wireguard", "wg" -> ProxyProtocol.WIREGUARD
            else -> return null
        }

        // TLS
        val tls = if (fields["tls"] == "true" || protocol in setOf(ProxyProtocol.TROJAN, ProxyProtocol.HYSTERIA2, ProxyProtocol.TUIC)) {
            TlsConfig(
                enabled = true,
                serverName = fields["servername"] ?: fields["sni"],
                insecure = fields["skip-cert-verify"] == "true",
                alpn = fields["alpn"]?.removeSurrounding("[", "]")
                    ?.split(",")?.map { it.trim().removeSurrounding("\"") },
                utlsFingerprint = fields["client-fingerprint"],
                // Reality
                realityPublicKey = fields["public-key"],
                realityShortId = fields["short-id"],
            )
        } else null

        // Transport
        val network = fields["network"] ?: fields["net"]
        val transport = when (network) {
            "ws" -> TransportConfig(
                type = "ws",
                path = fields["ws-path"] ?: fields["path"],
                host = fields["ws-host"] ?: fields["host"],
            )
            "grpc" -> TransportConfig(
                type = "grpc",
                serviceName = fields["grpc-service-name"] ?: fields["serviceName"],
            )
            "h2" -> TransportConfig(
                type = "http",
                path = fields["h2-path"] ?: fields["path"],
                host = fields["h2-host"] ?: fields["host"],
            )
            else -> null
        }

        return ProxyServerConfig(
            name = name,
            protocol = protocol,
            address = server,
            port = port,
            uuid = fields["uuid"],
            password = fields["password"],
            method = fields["cipher"] ?: fields["method"],
            alterId = fields["alterId"]?.toIntOrNull() ?: 0,
            flow = fields["flow"],
            security = if (tls != null) "tls" else "none",
            tls = tls,
            transport = transport,
            // Hysteria2
            obfsPassword = fields["obfs-password"],
            upMbps = fields["up"]?.replace(Regex("[^0-9]"), "")?.toIntOrNull(),
            downMbps = fields["down"]?.replace(Regex("[^0-9]"), "")?.toIntOrNull(),
            // TUIC
            congestionControl = fields["congestion-control"],
            // WireGuard
            privateKey = fields["private-key"],
            peerPublicKey = fields["public-key"],
            preSharedKey = fields["pre-shared-key"],
            localAddresses = fields["ip"]?.split(",")?.map { it.trim() },
            wireguardMtu = fields["mtu"]?.toIntOrNull(),
        )
    }

    /**
     * Простой парсер YAML-полей из прокси-блока.
     *
     * Обрабатывает:
     * - `key: value`
     * - `key: "quoted value"`
     * - Вложенные поля (ws-opts.path → "ws-path")
     * - Inline JSON: `- {name: x, type: y}`
     */
    internal fun parseYamlFields(block: String): Map<String, String> {
        val result = mutableMapOf<String, String>()

        // Проверяем inline JSON формат: - {name: x, type: y, ...}
        val inlineMatch = Regex("""-\s*\{(.+)}""").find(block.trim())
        if (inlineMatch != null) {
            val pairs = inlineMatch.groupValues[1]
            pairs.split(",").forEach { pair ->
                val kv = pair.split(":", limit = 2)
                if (kv.size == 2) {
                    val key = kv[0].trim().removeSurrounding("\"")
                    val value = kv[1].trim().removeSurrounding("\"")
                    result[key] = value
                }
            }
            return result
        }

        // Стандартный YAML парсинг
        var currentPrefix = ""

        for (line in block.lines()) {
            if (line.isBlank() || line.trimStart().startsWith("#")) continue

            val trimmed = line.trimStart().removePrefix("- ").trimStart()
            val indent = line.length - line.trimStart().length

            // Определяем вложенность
            if (indent <= 2) currentPrefix = ""

            if (trimmed.contains(":")) {
                val colonIndex = trimmed.indexOf(':')
                val key = trimmed.substring(0, colonIndex).trim()
                val value = trimmed.substring(colonIndex + 1).trim()
                    .removeSurrounding("\"")
                    .removeSurrounding("'")

                if (value.isNotBlank()) {
                    val fullKey = if (currentPrefix.isNotBlank()) "$currentPrefix-$key" else key
                    result[fullKey] = value
                } else {
                    // Это родительский ключ для вложенных (e.g., "ws-opts:")
                    currentPrefix = key
                }
            }
        }

        return result
    }
}

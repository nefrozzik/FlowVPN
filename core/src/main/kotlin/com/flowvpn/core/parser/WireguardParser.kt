package com.flowvpn.core.parser

import com.flowvpn.core.model.ProxyProtocol
import com.flowvpn.core.model.ProxyServerConfig

/**
 * Парсер ссылок протокола WireGuard.
 *
 * ## Формат URI
 *
 * Стандартного формата нет, используется де-факто формат из NekoBox/v2rayN:
 *
 * ```
 * wireguard://private_key@host:port?publickey=PEER_PUB_KEY&address=10.0.0.2/32,fd00::2/128&mtu=1280&reserved=1,2,3#Name
 * wg://...
 * ```
 *
 * ## Параметры
 *
 * | Параметр | Описание |
 * |----------|----------|
 * | `publickey` | Public key пира (сервера) |
 * | `presharedkey` | Pre-shared key (опционально) |
 * | `address` | Локальные адреса (comma-separated CIDR) |
 * | `mtu` | MTU (default: 1280) |
 * | `reserved` | Reserved bytes (comma-separated ints) |
 *
 * ## WireGuard в контексте VPN-клиента
 *
 * WireGuard — криптографически быстрый VPN-протокол уровня L3.
 * В отличие от VLESS/VMess (прикладные прокси), WireGuard работает
 * на уровне IP-пакетов. sing-box поддерживает WireGuard как outbound,
 * что позволяет подключаться к Cloudflare WARP и другим WG-серверам.
 */
object WireguardParser {

    fun parse(link: String): ProxyServerConfig? {
        // Нормализуем схему
        val normalized = link
            .replaceFirst("wg://", "wireguard://", ignoreCase = true)

        val withoutScheme = normalized
            .removePrefix("wireguard://")
            .removePrefix("WIREGUARD://")

        // private_key@host:port (или private_key может быть URL-encoded)
        val atIndex = withoutScheme.indexOf('@')
        if (atIndex < 0) return null

        val privateKey = java.net.URLDecoder.decode(
            withoutScheme.substring(0, atIndex), "UTF-8"
        )

        val authorityEnd = withoutScheme.indexOfFirst { it == '?' || it == '#' }
            .let { if (it < 0) withoutScheme.length else it }
        val authority = withoutScheme.substring(atIndex + 1, authorityEnd)
        val (host, port) = UriParseUtils.extractHostPort(authority)

        val params = UriParseUtils.extractQueryParams(normalized)
        val name = UriParseUtils.extractFragment(normalized).ifBlank { "$host:$port" }

        // Локальные адреса
        val localAddresses = params["address"]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }

        // Reserved bytes (e.g., "1,2,3" → [1,2,3])
        val reserved = params["reserved"]
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.isNotEmpty() }

        return ProxyServerConfig(
            name = name,
            protocol = ProxyProtocol.WIREGUARD,
            address = host,
            port = port,
            privateKey = privateKey,
            peerPublicKey = params["publickey"] ?: params["publicKey"],
            preSharedKey = params["presharedkey"] ?: params["presharedKey"],
            localAddresses = localAddresses ?: listOf("10.0.0.2/32"),
            reserved = reserved,
            wireguardMtu = params["mtu"]?.toIntOrNull() ?: 1280,
        )
    }
}

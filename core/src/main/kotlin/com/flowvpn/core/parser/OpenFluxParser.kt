package com.flowvpn.core.parser

import com.flowvpn.core.model.ProxyProtocol
import com.flowvpn.core.model.ProxyServerConfig
import java.util.UUID

/**
 * Парсер ссылок протокола OpenFlux (обход белых списков).
 *
 * OpenFlux организует скрытый туннель поверх разрешенных сервисов (Яндекс Документы, MAX Messenger)
 * и поднимает локальный SOCKS5 интерфейс на клиенте (по умолчанию 127.0.0.1:10808).
 *
 * ## Форматы URI
 *
 * 1. Яндекс Документы:
 * ```
 * openflux://yandex?docUrl=https%3A%2F%2Fdocs.yandex.ru%2Fdocs%2Fview%3Fid%3D...&port=10808#ЯндексДокументы
 * ```
 *
 * 2. MAX Messenger:
 * ```
 * openflux://max?token=TOKEN&uid=USER_ID&port=10808#MAX-OneMe
 * ```
 *
 * 3. Локальный или удаленный SOCKS5 адрес с указанием транспорта:
 * ```
 * openflux://127.0.0.1:10808?transport=yandex&docUrl=...#OpenFlux
 * ```
 */
object OpenFluxParser {

    fun parse(link: String): ProxyServerConfig? {
        val trimmed = link.trim()
        if (!trimmed.startsWith("openflux://", ignoreCase = true)) return null

        val withoutScheme = trimmed.substring("openflux://".length)
        if (withoutScheme.isBlank()) return null

        // 1. Извлекаем userinfo если есть (до @)
        val atIndex = withoutScheme.indexOf('@')
        val userInfo = if (atIndex >= 0) withoutScheme.substring(0, atIndex) else null
        val remainder = if (atIndex >= 0) withoutScheme.substring(atIndex + 1) else withoutScheme

        // 2. Извлекаем authority (до ? или #)
        val authorityEnd = remainder.indexOfFirst { it == '?' || it == '#' }
            .let { if (it < 0) remainder.length else it }
        val authority = remainder.substring(0, authorityEnd)

        // 3. Параметры запроса
        val params = UriParseUtils.extractQueryParams(trimmed)
        val fragment = UriParseUtils.extractFragment(trimmed)

        // 4. Определение транспорта, хоста и порта
        val transportParam = params["transport"]?.lowercase()
        val docUrl = params["docUrl"] ?: params["url"]
        val token = params["token"]
        val uid = params["uid"] ?: params["userId"]

        val host: String
        val port: Int
        val transport: String

        when {
            authority.equals("yandex", ignoreCase = true) -> {
                host = "127.0.0.1"
                port = params["port"]?.toIntOrNull() ?: 10808
                transport = "yandex"
            }
            authority.equals("max", ignoreCase = true) -> {
                host = "127.0.0.1"
                port = params["port"]?.toIntOrNull() ?: 10808
                transport = "max"
            }
            authority.isNotBlank() -> {
                val (extractedHost, extractedPort) = UriParseUtils.extractHostPort(authority)
                host = if (extractedHost.isBlank()) "127.0.0.1" else extractedHost
                port = if (extractedPort != 443) extractedPort else (params["port"]?.toIntOrNull() ?: 10808)
                transport = transportParam ?: if (docUrl != null) "yandex" else if (token != null) "max" else "yandex"
            }
            else -> {
                host = "127.0.0.1"
                port = params["port"]?.toIntOrNull() ?: 10808
                transport = transportParam ?: "yandex"
            }
        }

        val name = when {
            fragment.isNotBlank() -> fragment
            transport == "max" -> "OpenFlux (MAX Messenger)"
            else -> "OpenFlux (Яндекс Документы)"
        }

        return ProxyServerConfig(
            id = UUID.randomUUID().toString(),
            name = name,
            protocol = ProxyProtocol.OPENFLUX,
            address = host,
            port = port,
            password = userInfo ?: params["password"],
            openfluxTransport = transport,
            openfluxDocUrl = docUrl,
            openfluxToken = token,
            openfluxUid = uid,
            country = "RU",
        )
    }

    /**
     * Создать ссылку формата openflux:// для заданного конфига
     */
    fun toUri(config: ProxyServerConfig): String {
        val transport = config.openfluxTransport ?: "yandex"
        val queryParams = mutableListOf<String>()
        queryParams.add("port=${config.port}")
        queryParams.add("transport=$transport")

        config.openfluxDocUrl?.let {
            queryParams.add("docUrl=" + java.net.URLEncoder.encode(it, "UTF-8"))
        }
        config.openfluxToken?.let {
            queryParams.add("token=" + java.net.URLEncoder.encode(it, "UTF-8"))
        }
        config.openfluxUid?.let {
            queryParams.add("uid=" + java.net.URLEncoder.encode(it, "UTF-8"))
        }

        val nameFragment = java.net.URLEncoder.encode(config.name, "UTF-8")
        val queryString = queryParams.joinToString("&")
        return "openflux://${config.address}?$queryString#$nameFragment"
    }
}

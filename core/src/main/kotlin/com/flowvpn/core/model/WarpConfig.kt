package com.flowvpn.core.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Режим работы Cloudflare WARP.
 */
enum class WarpMode(val displayName: String, val wireName: String) {
    MASQUE_H2("MASQUE (HTTP/2 TCP) — обход блокировок и цепочки", "masque_h2"),
    MASQUE_H3("MASQUE (HTTP/3 QUIC) — прямой быстрый UDP", "masque_h3"),
    WIREGUARD("WireGuard (классический)", "wireguard");

    companion object {
        fun fromWireName(name: String?): WarpMode {
            return entries.firstOrNull {
                it.wireName.equals(name, ignoreCase = true) || it.name.equals(name, ignoreCase = true)
            } ?: MASQUE_H2
        }
    }
}

/**
 * Параметры учетной записи и конфигурации Cloudflare WARP (WireGuard / MASQUE).
 */
data class WarpConfig(
    val accountId: String,
    val accessToken: String = "",
    val accountType: String = "free", // "free" или "warp_plus"
    val privateKey: String,
    val peerPublicKey: String,
    val endpointHost: String = "162.159.192.1",
    val endpointPort: Int = 2408,
    val localAddressV4: String = "172.16.0.2/32",
    val localAddressV6: String = "2606:4700:110:8::/128",
    val reserved: List<Int> = listOf(0, 0, 0),
    val mtu: Int = 1280,
    val licenseKey: String = "",
    val warpMode: WarpMode = WarpMode.MASQUE_H2,
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("accountId", accountId)
            put("accessToken", accessToken)
            put("accountType", accountType)
            put("privateKey", privateKey)
            put("peerPublicKey", peerPublicKey)
            put("endpointHost", endpointHost)
            put("endpointPort", endpointPort)
            put("localAddressV4", localAddressV4)
            put("localAddressV6", localAddressV6)
            put("reserved", JSONArray(reserved))
            put("mtu", mtu)
            put("licenseKey", licenseKey)
            put("warpMode", warpMode.wireName)
        }.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): WarpConfig? {
            return try {
                val json = JSONObject(jsonStr)
                val reservedList = mutableListOf<Int>()
                val resArr = json.optJSONArray("reserved")
                if (resArr != null) {
                    for (i in 0 until resArr.length()) {
                        reservedList.add(resArr.optInt(i, 0))
                    }
                }
                WarpConfig(
                    accountId = json.optString("accountId"),
                    accessToken = json.optString("accessToken"),
                    accountType = json.optString("accountType", "free"),
                    privateKey = json.optString("privateKey"),
                    peerPublicKey = json.optString("peerPublicKey", "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="),
                    endpointHost = json.optString("endpointHost", "162.159.192.1"),
                    endpointPort = json.optInt("endpointPort", 2408),
                    localAddressV4 = json.optString("localAddressV4", "172.16.0.2/32"),
                    localAddressV6 = json.optString("localAddressV6", "2606:4700:110:8::/128"),
                    reserved = if (reservedList.isNotEmpty()) reservedList else listOf(0, 0, 0),
                    mtu = json.optInt("mtu", 1280),
                    licenseKey = json.optString("licenseKey", ""),
                    warpMode = WarpMode.fromWireName(json.optString("warpMode", "masque_h2")),
                )
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Парсер конфигураций WireGuard (INI format [Interface]/[Peer] или JSON из wgcf / ботов).
         */
        fun fromWireGuardText(text: String): WarpConfig? {
            val trimmed = text.trim()
            if (trimmed.isBlank()) return null

            // 1. Попытка распарсить JSON
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                try {
                    val json = JSONObject(trimmed)
                    val priv = json.optString("private_key").ifBlank { json.optString("privateKey") }
                    if (priv.isNotBlank()) {
                        val pub = json.optString("peer_public_key").ifBlank {
                            json.optString("peerPublicKey", "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=")
                        }
                        val host = json.optString("server").ifBlank { json.optString("endpointHost", "162.159.192.1") }
                        val port = json.optInt("server_port", json.optInt("endpointPort", 2408))
                        var v4 = "172.16.0.2/32"
                        var v6 = "2606:4700:110:8::1/128"
                        val localArr = json.optJSONArray("local_address") ?: json.optJSONArray("localAddresses")
                        if (localArr != null) {
                            for (i in 0 until localArr.length()) {
                                val a = localArr.getString(i)
                                if (a.contains(":")) v6 = a else v4 = a
                            }
                        }
                        val reserved = mutableListOf<Int>()
                        val resArr = json.optJSONArray("reserved")
                        if (resArr != null) {
                            for (i in 0 until resArr.length()) reserved.add(resArr.getInt(i))
                        }
                        return WarpConfig(
                            accountId = json.optString("account_id", json.optString("accountId", "imported-${java.util.UUID.randomUUID().toString().take(8)}")),
                            accessToken = json.optString("access_token", json.optString("accessToken", "")),
                            accountType = json.optString("account_type", json.optString("accountType", "warp_plus")),
                            privateKey = priv,
                            peerPublicKey = pub,
                            endpointHost = host,
                            endpointPort = port,
                            localAddressV4 = if (v4.contains("/")) v4 else "$v4/32",
                            localAddressV6 = if (v6.contains("/")) v6 else "$v6/128",
                            reserved = if (reserved.isNotEmpty()) reserved else listOf(0, 0, 0),
                            mtu = json.optInt("mtu", 1280),
                            licenseKey = json.optString("licenseKey", "")
                        )
                    }
                } catch (_: Exception) {}
            }

            // 2. Попытка распарсить стандартный WireGuard INI ([Interface] / [Peer])
            var priv = ""
            var pub = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
            var v4 = "172.16.0.2/32"
            var v6 = "2606:4700:110:8::1/128"
            var host = "162.159.192.1"
            var port = 2408
            var reserved = listOf(0, 0, 0)

            for (line in trimmed.lines()) {
                val l = line.trim()
                if (l.contains("=")) {
                    val key = l.substringBefore("=").trim().lowercase()
                    val value = l.substringAfter("=").trim()
                    when (key) {
                        "privatekey" -> priv = value
                        "publickey" -> pub = value
                        "address" -> {
                            val addrs = value.split(",").map { it.trim() }
                            for (a in addrs) {
                                if (a.contains(":")) {
                                    v6 = if (a.contains("/")) a else "$a/128"
                                } else {
                                    v4 = if (a.contains("/")) a else "$a/32"
                                }
                            }
                        }
                        "endpoint" -> {
                            if (value.contains(":")) {
                                host = value.substringBeforeLast(":").removePrefix("[").removeSuffix("]")
                                port = value.substringAfterLast(":").toIntOrNull() ?: 2408
                            } else {
                                host = value
                            }
                        }
                        "reserved" -> {
                            val r = value.split(",").mapNotNull { it.trim().toIntOrNull() }
                            if (r.isNotEmpty()) reserved = r
                        }
                    }
                }
            }

            if (priv.isNotBlank()) {
                return WarpConfig(
                    accountId = "imported-${java.util.UUID.randomUUID().toString().take(8)}",
                    accessToken = "",
                    accountType = "warp_plus",
                    privateKey = priv,
                    peerPublicKey = pub,
                    endpointHost = host,
                    endpointPort = port,
                    localAddressV4 = v4,
                    localAddressV6 = v6,
                    reserved = reserved,
                    mtu = 1280
                )
            }

            return null
        }
    }
}

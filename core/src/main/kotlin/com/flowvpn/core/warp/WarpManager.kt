package com.flowvpn.core.warp

import com.flowvpn.core.logger.CoreLogManager
import com.flowvpn.core.logger.LogLevel
import com.flowvpn.core.model.ProxyProtocol
import com.flowvpn.core.model.ProxyServerConfig
import com.flowvpn.core.model.WarpConfig
import com.flowvpn.core.model.WarpMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Менеджер аккаунтов и конфигураций Cloudflare WARP / WARP+.
 *
 * Предоставляет:
 * 1. Генерацию криптографической ключевой пары X25519 (Curve25519) в чистом Kotlin.
 * 2. Регистрацию устройства в Cloudflare API (`https://api.cloudflareclient.com`).
 * 3. Привязку лицензионного ключа WARP+ (License Key).
 * 4. Преобразование в [ProxyServerConfig] и интеграцию с sing-box.
 */
object WarpManager {

    private const val API_ENDPOINT = "https://api.cloudflareclient.com/v0a4471/reg"
    private const val DEFAULT_PEER_PUBLIC_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
    private const val DEFAULT_ENDPOINT_HOST = "162.159.192.1"
    private const val DEFAULT_ENDPOINT_PORT = 2408

    private const val LOCAL_PROXY_PORT = 2080

    private fun isLocalProxyActive(): Boolean {
        val active = try {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress("127.0.0.1", LOCAL_PROXY_PORT), 300)
            socket.close()
            true
        } catch (_: Exception) {
            false
        }
        CoreLogManager.log(
            "Проверка локального прокси sing-box (127.0.0.1:$LOCAL_PROXY_PORT): ${if (active) "АКТИВЕН (запрос пойдет через защищенный VPN-туннель)" else "НЕ АКТИВЕН (прямой запрос)"}",
            tag = "WARP"
        )
        return active
    }

    private fun openConnection(urlString: String): HttpURLConnection {
        val proxy = if (isLocalProxyActive()) {
            java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", LOCAL_PROXY_PORT))
        } else {
            java.net.Proxy.NO_PROXY
        }

        return (URL(urlString).openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("User-Agent", "okhttp/3.12.1")
            setRequestProperty("CF-Client-Version", "a-6.35-4471")
        }
    }

    private fun parseCloudflareError(rawError: String): String {
        return try {
            val json = JSONObject(rawError)
            val errorsArray = json.optJSONArray("errors")
            if (errorsArray != null && errorsArray.length() > 0) {
                val firstErr = errorsArray.getJSONObject(0)
                val code = firstErr.optInt("code", 0)
                val msg = firstErr.optString("message", "")
                val codeStr = if (code != 0) " (код $code)" else ""
                when {
                    msg.contains("device limit", ignoreCase = true) || code == 10014 ->
                        "Достигнут лимит устройств для ключа$codeStr (максимум 5 устройств на один ключ WARP+). Удалите старые устройства или используйте другой ключ."
                    msg.contains("invalid", ignoreCase = true) || code == 10015 ->
                        "Неверный ключ лицензии WARP+$codeStr. Проверьте правильность введенного ключа."
                    msg.isNotBlank() ->
                        "$msg$codeStr"
                    else -> rawError
                }
            } else {
                val msg = json.optString("message", "")
                if (msg.isNotBlank()) msg else rawError
            }
        } catch (_: Exception) {
            rawError
        }
    }

    /**
     * Зарегистрировать новый аккаунт Cloudflare WARP.
     *
     * @param licenseKey опциональный 26-значный лицензионный ключ WARP+
     * @return [WarpConfig] с ключами и адресами WireGuard
     */
    suspend fun register(licenseKey: String? = null): Result<WarpConfig> = withContext(Dispatchers.IO) {
        val cleanKey = licenseKey?.trim()?.takeIf { it.isNotBlank() }
        val maskedKey = cleanKey?.let { if (it.length > 8) "${it.take(4)}...${it.takeLast(4)}" else it }
        CoreLogManager.log("=== Регистрация нового аккаунта Cloudflare WARP ${if (maskedKey != null) "(с ключом $maskedKey)" else "(бесплатный)"} ===", tag = "WARP")

        try {
            withTimeout(15000L) {
                val keyPair = X25519.generateKeyPair()
                val privKey = keyPair.first
                val pubKey = keyPair.second
                CoreLogManager.log("Сгенерирована пара ключей X25519 (публичный: $pubKey)", tag = "WARP")

                val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val timestamp = dateFormat.format(Date())

                val regPayload = JSONObject().apply {
                    put("key", pubKey)
                    put("install_id", "")
                    put("fcm_token", "")
                    put("tos", timestamp)
                    put("model", "Android")
                    put("serial_number", "unknown")
                    put("locale", "en_US")
                }

                CoreLogManager.log("Отправка запроса POST $API_ENDPOINT...", tag = "WARP")
                val conn = openConnection(API_ENDPOINT).apply {
                    requestMethod = "POST"
                    doOutput = true
                }

                OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                    writer.write(regPayload.toString())
                    writer.flush()
                }

                val statusCode = conn.responseCode
                CoreLogManager.log("Ответ Cloudflare API на регистрацию: HTTP $statusCode", tag = "WARP")

                if (statusCode !in 200..299) {
                    val rawError = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $statusCode"
                    CoreLogManager.log("Ошибка Cloudflare API: $rawError", LogLevel.ERROR, tag = "WARP")
                    val parsed = parseCloudflareError(rawError)
                    return@withTimeout Result.failure(Exception("Cloudflare API ($statusCode): $parsed"))
                }

                val responseJson = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use {
                    JSONObject(it.readText())
                }

                val accountId = responseJson.getString("id")
                val token = responseJson.getString("token")
                val accountObj = responseJson.optJSONObject("account")
                var accountType = accountObj?.optString("account_type", "free") ?: "free"
                val isWarpPlus = accountObj?.optBoolean("warp_plus", false) == true
                if (isWarpPlus) accountType = "warp_plus"

                val configObj = responseJson.optJSONObject("config")
                val peersArr = configObj?.optJSONArray("peers")
                val firstPeer = peersArr?.optJSONObject(0)
                val endpointObj = firstPeer?.optJSONObject("endpoint")
                val endpointHostRaw = endpointObj?.optString("v4")
                val endpointHost = if (!endpointHostRaw.isNullOrBlank()) {
                    endpointHostRaw.split(":").firstOrNull() ?: DEFAULT_ENDPOINT_HOST
                } else {
                    DEFAULT_ENDPOINT_HOST
                }

                val peerPublicKey = firstPeer?.optString("public_key")?.ifBlank { DEFAULT_PEER_PUBLIC_KEY }
                    ?: DEFAULT_PEER_PUBLIC_KEY

                val ifaceObj = configObj?.optJSONObject("interface")
                val addressesObj = ifaceObj?.optJSONObject("addresses")
                val ipv4 = addressesObj?.optString("v4", "172.16.0.2") ?: "172.16.0.2"
                val ipv6 = addressesObj?.optString("v6", "2606:4700:110:8::1") ?: "2606:4700:110:8::1"

                val clientId = configObj?.optString("client_id")
                val reservedBytes = if (!clientId.isNullOrBlank()) {
                    try {
                        val decoded = Base64.getDecoder().decode(clientId)
                        decoded.map { it.toInt() and 0xFF }
                    } catch (_: Exception) {
                        listOf(0, 0, 0)
                    }
                } else {
                    listOf(0, 0, 0)
                }

                CoreLogManager.log("Аккаунт WARP успешно создан! ID: $accountId, IPv4: $ipv4, IPv6: $ipv6, Reserved: $reservedBytes", tag = "WARP")

                var finalConfig = WarpConfig(
                    accountId = accountId,
                    accessToken = token,
                    accountType = accountType,
                    privateKey = privKey,
                    peerPublicKey = peerPublicKey,
                    endpointHost = endpointHost,
                    endpointPort = DEFAULT_ENDPOINT_PORT,
                    localAddressV4 = if (ipv4.contains("/")) ipv4 else "$ipv4/32",
                    localAddressV6 = if (ipv6.contains("/")) ipv6 else "$ipv6/128",
                    reserved = reservedBytes,
                    mtu = 1280,
                    licenseKey = cleanKey ?: "",
                )

                // Если предоставлен лицензионный ключ, привязываем его к созданному аккаунту
                if (cleanKey != null) {
                    CoreLogManager.log("Привязка переданного лицензионного ключа к созданному аккаунту...", tag = "WARP")
                    val bindResult = bindLicense(accountId, token, cleanKey)
                    if (bindResult.isSuccess) {
                        finalConfig = finalConfig.copy(
                            accountType = bindResult.getOrNull() ?: "warp_plus",
                            licenseKey = cleanKey
                        )
                        CoreLogManager.log("Ключ WARP+ успешно привязан! Статус аккаунта: ${finalConfig.accountType}", tag = "WARP")
                    } else {
                        val bindError = bindResult.exceptionOrNull()?.message ?: "Неизвестная ошибка привязки"
                        CoreLogManager.log("Внимание: Аккаунт создан, но ключ не удалось привязать: $bindError", LogLevel.WARN, tag = "WARP")
                        finalConfig = finalConfig.copy(
                            accountType = "free",
                            licenseKey = ""
                        )
                    }
                }

                Result.success(finalConfig)
            }
        } catch (e: Exception) {
            CoreLogManager.log("Исключение при регистрации Cloudflare: ${e.javaClass.simpleName} - ${e.message}", LogLevel.ERROR, tag = "WARP")
            val msg = e.message ?: ""
            val isNetworkBlockOrTimeout = e is TimeoutCancellationException ||
                    e is java.net.SocketTimeoutException ||
                    e is java.net.UnknownHostException ||
                    e is java.net.ConnectException ||
                    e is javax.net.ssl.SSLException ||
                    msg.contains("timed out", ignoreCase = true) ||
                    msg.contains("handshake", ignoreCase = true) ||
                    msg.contains("refused", ignoreCase = true) ||
                    msg.contains("reset", ignoreCase = true)

            val friendlyError = if (isNetworkBlockOrTimeout) {
                "Не удалось связаться с серверами Cloudflare (таймаут/блокировка). В РФ Cloudflare API заблокирован ТСПУ. Пожалуйста, подключитесь к любому VPN-серверу (VLESS, Hysteria) на главном экране и повторите попытку."
            } else {
                e.message ?: "Ошибка подключения к Cloudflare"
            }
            Result.failure(Exception(friendlyError, e))
        }
    }

    /**
     * Привязать лицензионный ключ WARP+ к существующему аккаунту.
     */
    suspend fun bindLicense(
        accountId: String,
        accessToken: String,
        licenseKey: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanKey = licenseKey.trim()
        val maskedKey = if (cleanKey.length > 8) "${cleanKey.take(4)}...${cleanKey.takeLast(4)}" else cleanKey
        CoreLogManager.log("=== Привязка лицензионного ключа WARP+ '$maskedKey' к аккаунту $accountId ===", tag = "WARP")

        try {
            withTimeout(15000L) {
                val url = "$API_ENDPOINT/$accountId/account"
                CoreLogManager.log("Отправка PUT $url...", tag = "WARP")

                val payload = JSONObject().apply {
                    put("license", cleanKey)
                }

                val conn = openConnection(url).apply {
                    requestMethod = "PUT"
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $accessToken")
                }

                OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }

                val statusCode = conn.responseCode
                CoreLogManager.log("Ответ Cloudflare API на привязку ключа: HTTP $statusCode", tag = "WARP")

                if (statusCode !in 200..299) {
                    val rawError = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $statusCode"
                    CoreLogManager.log("Ошибка Cloudflare API: $rawError", LogLevel.ERROR, tag = "WARP")
                    val parsed = parseCloudflareError(rawError)
                    return@withTimeout Result.failure(Exception("Cloudflare ($statusCode): $parsed"))
                }

                val rawResponse = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                CoreLogManager.log("Ответ Cloudflare: $rawResponse", tag = "WARP")
                val responseJson = JSONObject(rawResponse)

                val isWarpPlus = responseJson.optBoolean("warp_plus", false)
                val type = if (isWarpPlus) "warp_plus" else responseJson.optString("account_type", "warp_plus")
                val premiumData = responseJson.optLong("premium_data", 0L)
                val quota = responseJson.optLong("quota", 0L)

                val trafficInfo = when {
                    premiumData > 0 -> "Трафик WARP+: ${premiumData / (1024L * 1024L * 1024L)} ГБ"
                    quota > 0 -> "Квота: ${quota / (1024L * 1024L * 1024L)} ГБ"
                    isWarpPlus -> "Трафик: Безлимитный WARP+"
                    else -> "Тип: $type"
                }
                CoreLogManager.log("Лицензия WARP+ успешно активирована! ($trafficInfo)", tag = "WARP")

                Result.success(type)
            }
        } catch (e: Exception) {
            CoreLogManager.log("Исключение при привязке ключа: ${e.javaClass.simpleName} - ${e.message}", LogLevel.ERROR, tag = "WARP")
            val msg = e.message ?: ""
            val isNetworkBlockOrTimeout = e is TimeoutCancellationException ||
                    e is java.net.SocketTimeoutException ||
                    e is java.net.UnknownHostException ||
                    e is java.net.ConnectException ||
                    e is javax.net.ssl.SSLException ||
                    msg.contains("timed out", ignoreCase = true) ||
                    msg.contains("handshake", ignoreCase = true) ||
                    msg.contains("refused", ignoreCase = true) ||
                    msg.contains("reset", ignoreCase = true)

            val friendlyError = if (isNetworkBlockOrTimeout) {
                "Не удалось связаться с серверами Cloudflare (таймаут/блокировка). Пожалуйста, подключитесь к любому VPN-серверу (VLESS, Hysteria) на главном экране и повторите попытку."
            } else {
                e.message ?: "Ошибка подключения к Cloudflare"
            }
            Result.failure(Exception(friendlyError, e))
        }
    }

    /**
     * Преобразовать [WarpConfig] в конфигурацию [ProxyServerConfig] для списка серверов.
     */
    fun toProxyServerConfig(
        warp: WarpConfig,
        name: String = "Cloudflare WARP" + if (warp.accountType == "warp_plus") "+" else "",
    ): ProxyServerConfig {
        val isMasque = warp.warpMode != WarpMode.WIREGUARD
        val proto = if (isMasque) ProxyProtocol.MASQUE else ProxyProtocol.WIREGUARD
        val displayName = if (isMasque) "Cloudflare MASQUE" + if (warp.accountType == "warp_plus") "+" else "" else name
        val effectiveHost = if (isMasque && warp.warpMode == WarpMode.MASQUE_H2 && (warp.endpointHost.isBlank() || warp.endpointHost == "162.159.192.1")) "162.159.198.2" else warp.endpointHost
        return ProxyServerConfig(
            id = "warp-${warp.accountId}",
            name = displayName,
            protocol = proto,
            address = effectiveHost,
            port = if (isMasque) 443 else warp.endpointPort,
            privateKey = warp.privateKey,
            peerPublicKey = warp.peerPublicKey,
            localAddresses = listOf(warp.localAddressV4, warp.localAddressV6),
            reserved = warp.reserved,
            wireguardMtu = warp.mtu,
            country = "US"
        )
    }

    /**
     * Реализация криптографии Curve25519 (RFC 7748 X25519) в чистом Kotlin через BigInteger.
     */
    object X25519 {
        private val P: BigInteger = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))
        private val A24: BigInteger = BigInteger.valueOf(121665)

        fun clamp(k: ByteArray): ByteArray {
            val copy = k.copyOf()
            copy[0] = (copy[0].toInt() and 248).toByte()
            copy[31] = ((copy[31].toInt() and 127) or 64).toByte()
            return copy
        }

        fun scalarMult(scalar: ByteArray, uPoint: ByteArray): ByteArray {
            val clamped = clamp(scalar)
            val k = BigInteger(1, clamped.reversedArray())
            val x1 = BigInteger(1, uPoint.reversedArray())
            var x2 = BigInteger.ONE
            var z2 = BigInteger.ZERO
            var x3 = x1
            var z3 = BigInteger.ONE
            var swap = 0

            for (t in 254 downTo 0) {
                val kt = if (k.testBit(t)) 1 else 0
                swap = swap xor kt
                if (swap != 0) {
                    var temp = x2; x2 = x3; x3 = temp
                    temp = z2; z2 = z3; z3 = temp
                }
                swap = kt

                val a = x2.add(z2).mod(P)
                val aa = a.multiply(a).mod(P)
                val b = x2.subtract(z2).mod(P)
                val bb = b.multiply(b).mod(P)
                val e = aa.subtract(bb).mod(P)
                val c = x3.add(z3).mod(P)
                val d = x3.subtract(z3).mod(P)
                val da = d.multiply(a).mod(P)
                val cb = c.multiply(b).mod(P)
                x3 = da.add(cb).mod(P).pow(2).mod(P)
                z3 = x1.multiply(da.subtract(cb).mod(P).pow(2)).mod(P)
                x2 = aa.multiply(bb).mod(P)
                z2 = e.multiply(aa.add(A24.multiply(e))).mod(P)
            }

            if (swap != 0) {
                var temp = x2; x2 = x3; x3 = temp
                temp = z2; z2 = z3; z3 = temp
            }

            val result = x2.multiply(z2.modInverse(P)).mod(P)
            val bytes = result.toByteArray()
            val trimmed = if (bytes.isNotEmpty() && bytes[0].toInt() == 0) {
                bytes.copyOfRange(1, bytes.size)
            } else {
                bytes
            }

            val out = ByteArray(32)
            val len = minOf(trimmed.size, 32)
            for (i in 0 until len) {
                out[i] = trimmed[trimmed.size - 1 - i]
            }
            return out
        }

        fun generateKeyPair(): Pair<String, String> {
            val privBytes = ByteArray(32)
            SecureRandom().nextBytes(privBytes)
            val basePoint = ByteArray(32).apply { this[0] = 9 }
            val pubBytes = scalarMult(privBytes, basePoint)
            val privB64 = Base64.getEncoder().encodeToString(privBytes)
            val pubB64 = Base64.getEncoder().encodeToString(pubBytes)
            return Pair(privB64, pubB64)
        }
    }
}

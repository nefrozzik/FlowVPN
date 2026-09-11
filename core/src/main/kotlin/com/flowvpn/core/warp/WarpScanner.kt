package com.flowvpn.core.warp

import com.flowvpn.core.logger.CoreLogManager
import com.flowvpn.core.logger.LogLevel
import com.flowvpn.core.model.WarpConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.digests.Blake2sDigest
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.Base64

/**
 * Результат сканирования отдельного эндпоинта Cloudflare WARP.
 */
data class WarpScanResult(
    val ip: String,
    val port: Int,
    val pingMs: Long,
    val isWireguardConfirmed: Boolean = false,
)

/**
 * Высокопроизводительный сканер чистых Anycast-эндпоинтов Cloudflare WARP.
 *
 * Проверяет доступность эндпоинтов по реальным дейтаграммам WireGuard Handshake Initiation,
 * а также замеряет RTT Anycast-узла Cloudflare (UDP 53 / TCP 443) для выявления незаблокированных ТСПУ путей.
 */
object WarpScanner {

    private val secureRandom = SecureRandom()
    private val DEFAULT_PEER_KEY_BYTES = Base64.getDecoder().decode("bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=")

    /**
     * Отобранный пул Anycast-адресов и портов Cloudflare, наиболее устойчивых к блокировкам в РФ.
     */
    val DEFAULT_CANDIDATES: List<Pair<String, Int>> = listOf(
        // Подсеть 188.114.97.x (порты 500, 4500, 1074, 1701)
        "188.114.97.1" to 500,
        "188.114.97.1" to 4500,
        "188.114.97.1" to 1074,
        "188.114.97.1" to 1701,
        "188.114.97.2" to 500,
        "188.114.97.2" to 4500,
        "188.114.97.3" to 1074,

        // Подсеть 188.114.96.x (порты 500, 4500, 1074, 1701)
        "188.114.96.1" to 500,
        "188.114.96.1" to 4500,
        "188.114.96.1" to 1074,
        "188.114.96.1" to 1701,
        "188.114.96.2" to 500,
        "188.114.96.2" to 4500,

        // Подсеть 162.159.198.x (основные ноды Cloudflare MASQUE H2 & H3 из Aether)
        "162.159.198.2" to 443,
        "162.159.198.2" to 500,
        "162.159.198.2" to 4500,
        "162.159.198.1" to 500,
        "162.159.198.1" to 4500,
        "162.159.198.1" to 1701,

        // Подсеть 162.159.197.x и 162.159.196.x (Zero Trust & MASQUE)
        "162.159.197.1" to 500,
        "162.159.197.1" to 4500,
        "162.159.197.3" to 1701,
        "162.159.196.1" to 500,
        "162.159.196.1" to 4500,
        "162.159.196.1" to 1701,

        // Подсеть 162.159.193.x
        "162.159.193.1" to 500,
        "162.159.193.1" to 4500,
        "162.159.193.1" to 1074,

        // Подсеть 162.159.195.x
        "162.159.195.1" to 500,
        "162.159.195.1" to 4500,

        // Подсети 188.114.98.x и 188.114.99.x
        "188.114.98.1" to 500,
        "188.114.99.1" to 500,

        // Подсеть 172.65.251.x и 141.101.113.x (Anycast CDN Edge)
        "172.65.251.1" to 500,
        "172.65.251.1" to 4500,
        "141.101.113.1" to 500,
        "141.101.113.1" to 4500,

        // DoH подсети Cloudflare (162.159.36.x, 162.159.46.x)
        "162.159.36.1" to 500,
        "162.159.46.1" to 500,

        // Стандартная подсеть 162.159.192.x на портах 500, 4500, 2408
        "162.159.192.1" to 500,
        "162.159.192.1" to 4500,
        "162.159.192.1" to 2408,
    )

    /**
     * Выполнить параллельное сканирование списка эндпоинтов.
     *
     * @param warpConfig текущая конфигурация WARP (для использования персональных ключей и reserved)
     * @param candidates список пар IP:порт для проверки (по умолчанию [DEFAULT_CANDIDATES])
     * @param timeoutMs таймаут ожидания ответа на каждый запрос (по умолчанию 800 мс)
     * @param onProgress коллбэк прогресса (проверено, всего, текущий найденный)
     * @return список успешно ответивших эндпоинтов, отсортированный по возрастанию пинга
     */
    suspend fun scan(
        warpConfig: WarpConfig? = null,
        candidates: List<Pair<String, Int>> = DEFAULT_CANDIDATES,
        timeoutMs: Int = 800,
        onProgress: ((checked: Int, total: Int, latestWorking: WarpScanResult?) -> Unit)? = null
    ): List<WarpScanResult> = withContext(Dispatchers.IO) {
        CoreLogManager.log("=== Запуск сканера чистых Anycast-эндпоинтов Cloudflare WARP ===", tag = "WarpScanner")
        CoreLogManager.log("К проверке: ${candidates.size} эндпоинтов, таймаут: ${timeoutMs} мс", tag = "WarpScanner")

        val privKeyBytes = try {
            val k = warpConfig?.privateKey?.takeIf { it.isNotBlank() }
            if (k != null) Base64.getDecoder().decode(k) else ByteArray(32).also { secureRandom.nextBytes(it) }
        } catch (_: Exception) {
            ByteArray(32).also { secureRandom.nextBytes(it) }
        }

        val peerKeyBytes = try {
            val k = warpConfig?.peerPublicKey?.takeIf { it.isNotBlank() }
            if (k != null) Base64.getDecoder().decode(k) else DEFAULT_PEER_KEY_BYTES
        } catch (_: Exception) {
            DEFAULT_PEER_KEY_BYTES
        }

        val reservedBytes = ByteArray(3).apply {
            val res = warpConfig?.reserved
            if (res != null && res.size >= 3) {
                this[0] = res[0].toByte()
                this[1] = res[1].toByte()
                this[2] = res[2].toByte()
            }
        }

        val handshakePacket = buildWgHandshake(privKeyBytes, peerKeyBytes, reservedBytes)
        val workingResults = mutableListOf<WarpScanResult>()
        var checkedCount = 0
        val total = candidates.size

        // Сканируем чанками по 6 параллельных проверок для стабильности сокетов
        candidates.chunked(6).forEach { batch ->
            val deferred = batch.map { (ip, port) ->
                async {
                    testEndpoint(ip, port, handshakePacket, timeoutMs)
                }
            }
            val batchResults = deferred.awaitAll()
            for (res in batchResults) {
                checkedCount++
                if (res != null) {
                    workingResults.add(res)
                    CoreLogManager.log(
                        "Найден рабочий эндпоинт: ${res.ip}:${res.port} | RTT: ${res.pingMs} мс | " +
                                if (res.isWireguardConfirmed) "WireGuard подтвержден" else "Anycast PoP доступен",
                        tag = "WarpScanner"
                    )
                    onProgress?.invoke(checkedCount, total, res)
                } else {
                    onProgress?.invoke(checkedCount, total, null)
                }
            }
        }

        workingResults.sortWith(
            compareByDescending<WarpScanResult> { it.isWireguardConfirmed }
                .thenBy { it.pingMs }
        )
        Timber.i("WarpScanner: Найдено ${workingResults.size} доступных эндпоинтов из $total")
        if (workingResults.isNotEmpty()) {
            val best = workingResults.first()
            CoreLogManager.log(
                "Сканирование завершено: доступно ${workingResults.size} из $total эндпоинтов. Лучший: ${best.ip}:${best.port} (${best.pingMs} мс)",
                tag = "WarpScanner"
            )
        } else {
            CoreLogManager.log(
                "Сканирование завершено: 0 из $total эндпоинтов ответили. В вашей сети/регионе Cloudflare блокируется провайдером.",
                LogLevel.WARN,
                tag = "WarpScanner"
            )
        }
        workingResults
    }

    /**
     * Проверить один эндпоинт отправкой WireGuard handshake пакета или Anycast DNS RTT.
     */
    private fun testEndpoint(
        ip: String,
        port: Int,
        wgPacket: ByteArray,
        timeoutMs: Int
    ): WarpScanResult? {
        val targetAddr = try {
            InetAddress.getByName(ip)
        } catch (_: Exception) {
            return null
        }

        // 1. Попытка реального WireGuard Handshake Initiation на целевой порт
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                val sendPacket = DatagramPacket(wgPacket, wgPacket.size, targetAddr, port)

                val start = System.currentTimeMillis()
                socket.send(sendPacket)

                val recvBuf = ByteArray(256)
                val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
                socket.receive(recvPacket)

                val elapsed = System.currentTimeMillis() - start
                if (recvPacket.length >= 32) {
                    val msgType = recvBuf[0].toInt() and 0xFF
                    if (msgType == 2 || msgType == 3) {
                        return WarpScanResult(ip = ip, port = port, pingMs = elapsed, isWireguardConfirmed = true)
                    }
                }
            }
        } catch (_: Exception) {
            // Handshake блокируется ТСПУ или таймаут
        }

        // 2. Anycast PoP Reachability Probe (UDP DNS на порт 53 Anycast-узла Cloudflare)
        // Замеряет реальный RTT до ближайшего дата-центра Cloudflare
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                val dnsQuery = byteArrayOf(
                    0xab.toByte(), 0xcd.toByte(), 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x0a, 'c'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte(), 'u'.code.toByte(), 'd'.code.toByte(),
                    'f'.code.toByte(), 'l'.code.toByte(), 'a'.code.toByte(), 'r'.code.toByte(), 'e'.code.toByte(),
                    0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0x00,
                    0x00, 0x01, 0x00, 0x01
                )
                val sendPacket = DatagramPacket(dnsQuery, dnsQuery.size, targetAddr, 53)
                val start = System.currentTimeMillis()
                socket.send(sendPacket)

                val recvBuf = ByteArray(512)
                val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
                socket.receive(recvPacket)
                val elapsed = System.currentTimeMillis() - start

                if (recvPacket.length >= 12) {
                    return WarpScanResult(ip = ip, port = port, pingMs = elapsed, isWireguardConfirmed = false)
                }
            }
        } catch (_: Exception) {
            // Fallback: TCP 443 SYN reachability
            try {
                java.net.Socket().use { tcpSocket ->
                    val start = System.currentTimeMillis()
                    tcpSocket.connect(java.net.InetSocketAddress(targetAddr, 443), timeoutMs)
                    val elapsed = System.currentTimeMillis() - start
                    return WarpScanResult(ip = ip, port = port, pingMs = elapsed, isWireguardConfirmed = false)
                }
            } catch (_: Exception) {
                // Сервер полностью недоступен
            }
        }

        return null
    }

    /**
     * Построить 148-байтный пакет WireGuard Handshake Initiation по стандарту Noise_IK.
     */
    private fun buildWgHandshake(
        privKey: ByteArray,
        peerPublicKey: ByteArray,
        reserved: ByteArray
    ): ByteArray {
        val pubKey = x25519Base(privKey)
        val ePriv = ByteArray(32).also { secureRandom.nextBytes(it) }
        val ePub = x25519Base(ePriv)

        val prologue = "WireGuard v1 zx2c4 Jason@zx2c4.com".toByteArray(Charsets.UTF_8)
        var chainingKey = blake2sHash("Noise_IKpsk2_25519_ChaChaPoly_BLAKE2s".toByteArray(Charsets.UTF_8))
        var hash = blake2sHash(chainingKey + prologue)
        hash = blake2sHash(hash + peerPublicKey)

        chainingKey = blake2sMac(chainingKey, ePub)
        hash = blake2sHash(hash + ePub)

        val es = x25519(ePriv, peerPublicKey)
        val tempK = blake2sMac(chainingKey, es)
        val k1 = blake2sMac(tempK, byteArrayOf(1))
        chainingKey = blake2sMac(tempK, k1 + byteArrayOf(2))

        val nonceZero = ByteArray(12)
        val cS = chacha20Poly1305Encrypt(k1, nonceZero, pubKey, hash)
        hash = blake2sHash(hash + cS)

        val ss = x25519(privKey, peerPublicKey)
        val tempK2 = blake2sMac(chainingKey, ss)
        val k2 = blake2sMac(tempK2, byteArrayOf(1))
        chainingKey = blake2sMac(tempK2, k2 + byteArrayOf(2))

        val nowSecs = System.currentTimeMillis() / 1000L + 0x400000000000000aL
        val nowNanos = ((System.currentTimeMillis() % 1000L) * 1_000_000L)
        val tai64n = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).putLong(nowSecs).putInt(nowNanos.toInt()).array()

        val cT = chacha20Poly1305Encrypt(k2, nonceZero, tai64n, hash)

        val mac1Key = blake2sHash("mac1----".toByteArray(Charsets.UTF_8) + peerPublicKey)
        val packetNoMac = ByteBuffer.allocate(116).apply {
            put(1.toByte()) // Type 1: Handshake Initiation
            put(reserved)   // Reserved (3 bytes, e.g. Cloudflare client ID)
            putInt(0x12345678) // Sender Index (4 bytes)
            put(ePub)       // Ephemeral public key (32 bytes)
            put(cS)         // Encrypted static public key (48 bytes)
            put(cT)         // Encrypted timestamp (28 bytes)
        }.array()

        val mac1 = blake2s16(mac1Key, packetNoMac)
        val mac2 = ByteArray(16) // Zero mac2 unless under cookie challenge

        return packetNoMac + mac1 + mac2
    }

    private fun blake2sHash(data: ByteArray): ByteArray {
        val digest = Blake2sDigest(32)
        digest.update(data, 0, data.size)
        val out = ByteArray(32)
        digest.doFinal(out, 0)
        return out
    }

    private fun blake2sMac(key: ByteArray, data: ByteArray): ByteArray {
        val digest = Blake2sDigest(key, 32, null, null)
        digest.update(data, 0, data.size)
        val out = ByteArray(32)
        digest.doFinal(out, 0)
        return out
    }

    private fun blake2s16(key: ByteArray, data: ByteArray): ByteArray {
        val digest = Blake2sDigest(key, 16, null, null)
        digest.update(data, 0, data.size)
        val out = ByteArray(16)
        digest.doFinal(out, 0)
        return out
    }

    private fun chacha20Poly1305Encrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        val len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        cipher.doFinal(out, len)
        return out
    }

    private fun x25519(scalar: ByteArray, point: ByteArray): ByteArray {
        return WarpManager.X25519.scalarMult(scalar, point)
    }

    private fun x25519Base(scalar: ByteArray): ByteArray {
        val basePoint = ByteArray(32).apply { this[0] = 9 }
        return WarpManager.X25519.scalarMult(scalar, basePoint)
    }
}

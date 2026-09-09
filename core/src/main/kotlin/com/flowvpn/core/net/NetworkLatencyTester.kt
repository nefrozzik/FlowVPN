package com.flowvpn.core.net

import com.flowvpn.core.model.ProxyServerConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Сервис сетевого тестирования задержки серверов.
 *
 * Поддерживает:
 * 1. [testTcpLatency] — быстрый замер TCP Handshake (SYN-ACK RTT) к `address:port` сервера.
 * 2. [testHttp204Latency] — сквозной замер HTTP 204 (Captive Portal Check) к Google / Cloudflare.
 * 3. [testAllParallel] — параллельное тестирование списка серверов с ограничением конкурентности.
 */
class NetworkLatencyTester(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    companion object {
        const val DEFAULT_TIMEOUT_MS = 2500

        val URL_TEST_ENDPOINTS = listOf(
            "http://www.google.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
            "https://www.gstatic.com/generate_204",
        )
    }

    /**
     * Измерение задержки TCP Handshake (в миллисекундах).
     *
     * @return RTT в ms или -1 при тайм-ауте/ошибке соединения
     */
    suspend fun testTcpLatency(
        host: String,
        port: Int,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): Int = withContext(ioDispatcher) {
        val start = System.currentTimeMillis()
        try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            (System.currentTimeMillis() - start).toInt().coerceAtLeast(1)
        } catch (_: Exception) {
            -1
        }
    }

    /**
     * Измерение HTTP RTT через отправку легковесного запроса к Generate 204 URL.
     * Эндпоинты `generate_204` не содержат тела ответа (0 байт), что исключает лишний расход трафика.
     *
     * @param targetUrl URL для тестирования (default: Google generate_204)
     * @param timeoutMs максимальное время ожидания
     * @return время от отправки до получения ответа 204/200 в ms или -1
     */
    suspend fun testHttp204Latency(
        targetUrl: String = URL_TEST_ENDPOINTS.first(),
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): Int = withContext(ioDispatcher) {
        val start = System.currentTimeMillis()
        try {
            val url = URL(targetUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("User-Agent", "Mozilla/5.0")
                setRequestProperty("Connection", "close")
            }

            try {
                connection.connect()
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_NO_CONTENT || code == HttpURLConnection.HTTP_OK) {
                    (System.currentTimeMillis() - start).toInt().coerceAtLeast(1)
                } else {
                    -1
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Многопоточное параллельное тестирование серверов с ограничением конкурентных сокетов.
     *
     * @param servers список серверов для теста
     * @param maxConcurrency максимальное число одновременных запросов (по умолчанию 16)
     * @return Flow с парами (serverId, latencyMs) по мере завершения теста каждого узла
     */
    fun testAllParallel(
        servers: List<ProxyServerConfig>,
        maxConcurrency: Int = 16,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): Flow<Pair<String, Int>> = channelFlow {
        val semaphore = Semaphore(maxConcurrency)

        for (server in servers) {
            launch(ioDispatcher) {
                semaphore.withPermit {
                    val latency = testTcpLatency(server.address, server.port, timeoutMs)
                    send(server.id to latency)
                }
            }
        }
    }
}

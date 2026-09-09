package com.flowvpn.core.subscription

import com.flowvpn.core.model.ProxyServerConfig
import com.flowvpn.core.model.SubscriptionInfo
import com.flowvpn.core.parser.SubscriptionDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Менеджер подписок — загрузка, парсинг и обновление.
 *
 * ## Workflow загрузки подписки
 *
 * ```
 * URL → HTTP GET → Response
 *   ├─ Headers: Subscription-Userinfo, Content-Disposition, Profile-Update-Interval
 *   └─ Body: Base64 / JSON / YAML → SubscriptionDecoder → List<ProxyServerConfig>
 * ```
 *
 * ## Subscription-Userinfo заголовок
 *
 * Стандартный заголовок, возвращаемый провайдерами подписок:
 * ```
 * Subscription-Userinfo: upload=455727941; download=6174315083; total=10737418240; expire=1671197839
 * ```
 *
 * Содержит:
 * - `upload` / `download` — использованный трафик (bytes)
 * - `total` — общий лимит трафика (bytes)
 * - `expire` — Unix timestamp истечения подписки
 *
 * ## User-Agent
 *
 * Многие провайдеры возвращают разный формат подписки в зависимости
 * от User-Agent. Мы используем формат sing-box/Clash для максимальной
 * совместимости.
 */
class SubscriptionManager {

    companion object {
        /** User-Agent для получения sing-box/Clash формата */
        private const val USER_AGENT = "FlowVPN/1.0 (sing-box; Clash)"

        /** Таймаут подключения (мс) */
        private const val CONNECT_TIMEOUT = 15_000

        /** Таймаут чтения (мс) */
        private const val READ_TIMEOUT = 30_000

        /** Максимальный размер ответа (10 MB) — защита от OOM */
        private const val MAX_RESPONSE_SIZE = 10 * 1024 * 1024
    }

    /**
     * Загрузить и распарсить подписку по URL.
     *
     * @param url URL подписки (HTTP/HTTPS)
     * @param existingInfo существующая информация о подписке (для обновления)
     * @return обновлённая [SubscriptionInfo] с серверами и метаданными
     * @throws SubscriptionException при ошибках сети или парсинга
     */
    suspend fun fetchSubscription(
        url: String,
        existingInfo: SubscriptionInfo? = null,
    ): SubscriptionInfo = withContext(Dispatchers.IO) {
        Timber.i("SubscriptionManager: Загрузка подписки: $url")

        val connection = createConnection(url)
        try {
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw SubscriptionException(
                    "HTTP $responseCode: ${connection.responseMessage}",
                    SubscriptionException.Type.HTTP_ERROR,
                )
            }

            // Парсим заголовки метаданных
            val userinfo = parseSubscriptionUserinfo(
                connection.getHeaderField("Subscription-Userinfo")
            )
            val profileName = connection.getHeaderField("Content-Disposition")
                ?.let { extractFilename(it) }
            val updateInterval = connection.getHeaderField("Profile-Update-Interval")
                ?.toIntOrNull() // в часах

            // Читаем body
            val body = readResponseBody(connection)
            if (body.isBlank()) {
                throw SubscriptionException(
                    "Пустой ответ от сервера",
                    SubscriptionException.Type.EMPTY_RESPONSE,
                )
            }

            // Декодируем и парсим серверы
            val servers = SubscriptionDecoder.decode(body)
            if (servers.isEmpty()) {
                throw SubscriptionException(
                    "Не удалось распарсить серверы из подписки",
                    SubscriptionException.Type.PARSE_ERROR,
                )
            }

            Timber.i("SubscriptionManager: Получено ${servers.size} серверов")

            // Формируем обновлённую SubscriptionInfo
            SubscriptionInfo(
                id = existingInfo?.id ?: java.util.UUID.randomUUID().toString(),
                name = profileName ?: existingInfo?.name ?: extractDomain(url),
                url = url,
                lastUpdatedMs = System.currentTimeMillis(),
                servers = servers,
                uploadBytes = userinfo?.upload,
                downloadBytes = userinfo?.download,
                totalBytes = userinfo?.total,
                expireTimestamp = userinfo?.expire,
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Распарсить содержимое из буфера обмена (clipboard) или текстового ввода.
     *
     * Поддерживает:
     * - Одиночные ссылки (vless://, vmess://, ss://, ...)
     * - Несколько ссылок (по одной на строку)
     * - Base64-encoded списки
     * - JSON/YAML профили
     *
     * @param content текст из буфера обмена
     * @return список распарсенных серверов
     */
    fun parseFromClipboard(content: String): List<ProxyServerConfig> {
        return SubscriptionDecoder.decode(content)
    }

    /**
     * Создать HTTP-соединение с правильными заголовками.
     */
    private fun createConnection(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            instanceFollowRedirects = true

            // User-Agent влияет на формат ответа от многих провайдеров
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Encoding", "identity") // Без сжатия для простоты
        }
        return connection
    }

    /**
     * Прочитать body ответа с ограничением размера.
     */
    private fun readResponseBody(connection: HttpURLConnection): String {
        val reader = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8"))
        val sb = StringBuilder()
        val buffer = CharArray(8192)
        var totalRead = 0

        reader.use {
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                totalRead += read
                if (totalRead > MAX_RESPONSE_SIZE) {
                    throw SubscriptionException(
                        "Ответ слишком большой (>${MAX_RESPONSE_SIZE / 1024 / 1024} MB)",
                        SubscriptionException.Type.TOO_LARGE,
                    )
                }
                sb.append(buffer, 0, read)
            }
        }

        return sb.toString()
    }

    /**
     * Парсинг заголовка Subscription-Userinfo.
     *
     * Формат: `upload=123; download=456; total=789; expire=1671197839`
     */
    private fun parseSubscriptionUserinfo(header: String?): UserinfoData? {
        if (header.isNullOrBlank()) return null

        val fields = header.split(";")
            .map { it.trim() }
            .filter { it.contains("=") }
            .associate { field ->
                val parts = field.split("=", limit = 2)
                parts[0].trim() to parts[1].trim()
            }

        return UserinfoData(
            upload = fields["upload"]?.toLongOrNull(),
            download = fields["download"]?.toLongOrNull(),
            total = fields["total"]?.toLongOrNull(),
            expire = fields["expire"]?.toLongOrNull(),
        )
    }

    /**
     * Извлечь имя файла из Content-Disposition заголовка.
     *
     * `attachment; filename="MySubscription"` → "MySubscription"
     */
    private fun extractFilename(contentDisposition: String): String? {
        return Regex("""filename\*?=["']?(?:UTF-8'')?([^"';\s]+)""")
            .find(contentDisposition)
            ?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
    }

    /**
     * Извлечь домен из URL для имени подписки по умолчанию.
     */
    private fun extractDomain(url: String): String {
        return try {
            URL(url).host
        } catch (_: Exception) {
            url.take(30)
        }
    }

    private data class UserinfoData(
        val upload: Long?,
        val download: Long?,
        val total: Long?,
        val expire: Long?,
    )
}

/**
 * Исключения при работе с подписками.
 */
class SubscriptionException(
    message: String,
    val type: Type,
    cause: Throwable? = null,
) : Exception(message, cause) {

    enum class Type {
        HTTP_ERROR,
        PARSE_ERROR,
        EMPTY_RESPONSE,
        TOO_LARGE,
        NETWORK_ERROR,
    }
}

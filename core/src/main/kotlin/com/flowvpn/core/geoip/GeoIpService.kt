package com.flowvpn.core.geoip

import android.content.Context
import android.content.SharedPreferences
import com.flowvpn.core.model.ProxyServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Сервис определения страны сервера по IP-адресу или домену (GeoIP).
 *
 * Особенности:
 * - Кеширование в памяти (ConcurrentHashMap) и на диске (SharedPreferences).
 * - Мгновенное извлечение страны из эмодзи-флага в имени сервера (например, 🇳🇴 -> "🇳🇴 Норвегия").
 * - Асинхронный GeoIP-запрос (ip-api.com с fallback на ipwho.is).
 * - Локализация названия страны на языке пользователя устройства.
 */
object GeoIpService {

    private const val PREFS_NAME = "flow_geoip_cache"
    private const val CONNECT_TIMEOUT_MS = 4000
    private const val READ_TIMEOUT_MS = 4000

    private val memoryCache = ConcurrentHashMap<String, CountryInfo>()
    private var prefs: SharedPreferences? = null

    data class CountryInfo(
        val countryCode: String,
        val countryName: String,
        val flagEmoji: String,
    ) {
        val display: String
            get() = if (flagEmoji.isNotBlank()) "$flagEmoji $countryName" else countryName
    }

    /**
     * Инициализация хранилища кеша.
     */
    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadCacheFromDisk()
        }
    }

    private fun loadCacheFromDisk() {
        val sp = prefs ?: return
        try {
            for ((key, value) in sp.all) {
                if (value is String && value.contains("|")) {
                    val parts = value.split("|")
                    if (parts.size >= 3) {
                        memoryCache[key] = CountryInfo(
                            countryCode = parts[0],
                            countryName = parts[1],
                            flagEmoji = parts[2]
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Timber.d("GeoIpService: Ошибка чтения кеша с диска: ${e.message}")
        }
    }

    private fun saveToDisk(key: String, info: CountryInfo) {
        memoryCache[key] = info
        prefs?.edit()?.putString(key, "${info.countryCode}|${info.countryName}|${info.flagEmoji}")?.apply()
    }

    /**
     * Быстрое получение страны без сетевого запроса:
     * 1. Из кеша по адресу или хосту.
     * 2. Из эмодзи-флага в названии сервера (например, 🇩🇪 -> "🇩🇪 Германия").
     * 3. Из существующего поля [ProxyServerConfig.country].
     */
    fun getFastCountry(server: ProxyServerConfig): String? {
        // 1. Кеш
        memoryCache[server.address]?.let { return it.display }

        // 2. Флаг из названия
        val fromName = extractCountryFromName(server.name)
        if (fromName != null) {
            saveToDisk(server.address, fromName)
            return fromName.display
        }

        // 3. Поле country в сервере
        val rawCode = server.country?.trim()
        if (!rawCode.isNullOrBlank()) {
            val info = countryCodeToInfo(rawCode)
            if (info != null) {
                saveToDisk(server.address, info)
                return info.display
            }
        }

        return null
    }

    /**
     * Асинхронное определение страны по IP сервера через GeoIP API.
     */
    suspend fun resolveCountry(server: ProxyServerConfig): String? = withContext(Dispatchers.IO) {
        // Проверяем быстрый кеш
        val cached = getFastCountry(server)
        if (cached != null && !cached.contains("—")) {
            return@withContext cached
        }

        val address = server.address.trim()
        if (address.isBlank() || isPrivateAddress(address)) {
            return@withContext cached
        }

        // Резолвим IP, если указан домен
        val resolvedIp = try {
            if (isIpAddress(address)) {
                address
            } else {
                InetAddress.getByName(address).hostAddress ?: address
            }
        } catch (e: Exception) {
            address
        }

        // Проверяем кеш по resolvedIp
        memoryCache[resolvedIp]?.let { return@withContext it.display }

        // Запрос к GeoIP API (ip-api.com)
        val info = queryIpApi(resolvedIp) ?: queryIpWhoIs(resolvedIp)
        if (info != null) {
            saveToDisk(address, info)
            if (resolvedIp != address) {
                saveToDisk(resolvedIp, info)
            }
            return@withContext info.display
        }

        return@withContext cached
    }

    private fun queryIpApi(ip: String): CountryInfo? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("http://ip-api.com/json/$ip?fields=status,country,countryCode")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "FlowVPN-Client")
            }
            conn.connect()
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonStr = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val json = JSONObject(jsonStr)
                if (json.optString("status") == "success") {
                    val code = json.optString("countryCode").uppercase()
                    val englishName = json.optString("country")
                    return countryCodeToInfo(code, englishName)
                }
            }
            null
        } catch (e: Exception) {
            Timber.d("GeoIpService: ip-api.com failed for $ip: ${e.message}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun queryIpWhoIs(ip: String): CountryInfo? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("https://ipwho.is/$ip")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "FlowVPN-Client")
            }
            conn.connect()
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonStr = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val json = JSONObject(jsonStr)
                if (json.optBoolean("success", false)) {
                    val code = json.optString("country_code").uppercase()
                    val englishName = json.optString("country")
                    return countryCodeToInfo(code, englishName)
                }
            }
            null
        } catch (e: Exception) {
            Timber.d("GeoIpService: ipwho.is failed for $ip: ${e.message}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * Преобразовать двухбуквенный ISO-код (например, "DE") в [CountryInfo].
     */
    fun countryCodeToInfo(countryCode: String, fallbackName: String = ""): CountryInfo? {
        val cleanCode = countryCode.trim().uppercase()
        if (cleanCode.length != 2 || !cleanCode.all { it in 'A'..'Z' }) return null

        val flag = countryCodeToFlagEmoji(cleanCode)
        val locale = Locale.getDefault()
        val localizedName = Locale("", cleanCode).getDisplayCountry(locale)
            .takeIf { it.isNotBlank() && !it.equals(cleanCode, ignoreCase = true) }
            ?: fallbackName.ifBlank { cleanCode }

        return CountryInfo(
            countryCode = cleanCode,
            countryName = localizedName,
            flagEmoji = flag
        )
    }

    /**
     * Конвертировать ISO 3166-1 alpha-2 код во флаг-эмодзи.
     */
    fun countryCodeToFlagEmoji(countryCode: String): String {
        val code = countryCode.uppercase()
        if (code.length != 2) return ""
        val first = Character.codePointAt(code, 0) - 0x41 + 0x1F1E6
        val second = Character.codePointAt(code, 1) - 0x41 + 0x1F1E6
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    /**
     * Извлечь флаг-эмодзи из названия сервера.
     * Эмодзи-флаги состоят из двух суррогатных символов в диапазоне 0x1F1E6..0x1F1FF.
     */
    private fun extractCountryFromName(name: String): CountryInfo? {
        var i = 0
        while (i < name.length) {
            val cp1 = Character.codePointAt(name, i)
            val charCount1 = Character.charCount(cp1)
            if (cp1 in 0x1F1E6..0x1F1FF && i + charCount1 < name.length) {
                val cp2 = Character.codePointAt(name, i + charCount1)
                if (cp2 in 0x1F1E6..0x1F1FF) {
                    val code1 = ('A'.code + (cp1 - 0x1F1E6)).toChar()
                    val code2 = ('A'.code + (cp2 - 0x1F1E6)).toChar()
                    val isoCode = "$code1$code2"
                    return countryCodeToInfo(isoCode)
                }
            }
            i += charCount1
        }
        return null
    }

    private fun isIpAddress(address: String): Boolean {
        return address.matches(Regex("""^(\d{1,3}\.){3}\d{1,3}$""")) || address.contains(":")
    }

    private fun isPrivateAddress(address: String): Boolean {
        return address == "127.0.0.1" ||
                address == "localhost" ||
                address.startsWith("10.") ||
                address.startsWith("192.168.") ||
                address.startsWith("172.16.") ||
                address.startsWith("172.17.") ||
                address.startsWith("172.18.") ||
                address.startsWith("172.19.") ||
                address.startsWith("172.2") ||
                address.startsWith("172.3")
    }
}

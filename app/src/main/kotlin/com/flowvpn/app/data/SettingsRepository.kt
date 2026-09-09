package com.flowvpn.app.data

import android.content.Context
import com.flowvpn.core.model.AppSettings
import com.flowvpn.core.model.DnsProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Репозиторий сетевых настроек FlowVPN (вдохновлен Amnezia VPN).
 * Сохраняет настройки в filesDir/settings.json и предоставляет реактивный [StateFlow].
 */
class SettingsRepository(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val settingsFile = File(context.filesDir, "settings.json")

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    suspend fun setDnsProvider(provider: DnsProvider) = update {
        it.copy(dnsProvider = provider)
    }

    suspend fun setCustomDnsUrl(url: String) = update {
        it.copy(customDnsUrl = url.trim())
    }

    suspend fun setBypassLan(enabled: Boolean) = update {
        it.copy(bypassLan = enabled)
    }

    suspend fun setBypassRussianTraffic(enabled: Boolean) = update {
        it.copy(bypassRussianTraffic = enabled)
    }

    suspend fun setCustomBypassDomains(domains: List<String>) = update {
        it.copy(customBypassDomains = domains.map { d -> d.trim().lowercase() }.filter { d -> d.isNotBlank() }.distinct())
    }

    suspend fun addCustomBypassDomain(domain: String) = update {
        val clean = domain.trim().lowercase()
            .removePrefix("http://")
            .removePrefix("https://")
            .removePrefix("/")
            .substringBefore("/")
            .trim()
        if (clean.isNotBlank() && !it.customBypassDomains.contains(clean)) {
            it.copy(customBypassDomains = it.customBypassDomains + clean)
        } else {
            it
        }
    }

    suspend fun removeCustomBypassDomain(domain: String) = update {
        it.copy(customBypassDomains = it.customBypassDomains.filter { d -> !d.equals(domain.trim(), ignoreCase = true) })
    }

    suspend fun setKillSwitch(enabled: Boolean) = update {
        it.copy(killSwitch = enabled)
    }

    suspend fun setBlockIpv6(enabled: Boolean) = update {
        it.copy(blockIpv6 = enabled)
    }

    suspend fun setMtu(mtu: Int) = update {
        it.copy(mtu = mtu)
    }

    suspend fun setFakeDns(enabled: Boolean) = update {
        it.copy(fakeDns = enabled)
    }

    suspend fun setSniffing(enabled: Boolean) = update {
        it.copy(sniffing = enabled)
    }

    suspend fun setAutoConnect(enabled: Boolean) = update {
        it.copy(autoConnect = enabled)
    }

    suspend fun setAutoUpdateSubscriptions(enabled: Boolean) = update {
        it.copy(autoUpdateSubscriptions = enabled)
    }

    suspend fun setAutoUpdateIntervalHours(hours: Int) = update {
        it.copy(autoUpdateIntervalHours = hours)
    }

    suspend fun setFileLoggingEnabled(enabled: Boolean) = update {
        it.copy(fileLoggingEnabled = enabled)
    }

    suspend fun resetToDefaults() = update {
        AppSettings()
    }

    private suspend fun update(transform: (AppSettings) -> AppSettings) = withContext(ioDispatcher) {
        val current = _settings.value
        val updated = transform(current)
        _settings.value = updated
        com.flowvpn.core.logger.AppLogManager.isFileLoggingEnabled = updated.fileLoggingEnabled
        saveSettings(updated)
    }

    private fun loadSettings(): AppSettings {
        val sp = context.getSharedPreferences("flowvpn_settings_prefs", Context.MODE_PRIVATE)

        val json = try {
            if (settingsFile.exists()) {
                JSONObject(settingsFile.readText())
            } else {
                val spJson = sp.getString("settings_json", null)
                if (!spJson.isNullOrBlank()) JSONObject(spJson) else null
            }
        } catch (e: Exception) {
            val spJson = sp.getString("settings_json", null)
            if (!spJson.isNullOrBlank()) runCatching { JSONObject(spJson) }.getOrNull() else null
        } ?: return AppSettings()

        return try {
            val dnsProviderName = json.optString("dnsProvider", DnsProvider.CLOUDFLARE.name)
            val dnsProvider = try {
                DnsProvider.valueOf(dnsProviderName)
            } catch (_: Exception) {
                DnsProvider.CLOUDFLARE
            }

            val customDomains = mutableListOf<String>()
            val customDomainsArray = json.optJSONArray("customBypassDomains")
            if (customDomainsArray != null) {
                for (i in 0 until customDomainsArray.length()) {
                    val d = customDomainsArray.optString(i)?.trim()?.lowercase()
                    if (!d.isNullOrBlank()) customDomains.add(d)
                }
            }

            val loaded = AppSettings(
                dnsProvider = dnsProvider,
                customDnsUrl = json.optString("customDnsUrl", "https://dns.google/dns-query"),
                bypassLan = json.optBoolean("bypassLan", true),
                bypassRussianTraffic = json.optBoolean("bypassRussianTraffic", true),
                customBypassDomains = customDomains.distinct(),
                killSwitch = json.optBoolean("killSwitch", false),
                blockIpv6 = json.optBoolean("blockIpv6", true),
                mtu = json.optInt("mtu", 1500),
                fakeDns = json.optBoolean("fakeDns", false),
                sniffing = json.optBoolean("sniffing", true),
                autoConnect = json.optBoolean("autoConnect", false),
                autoUpdateSubscriptions = json.optBoolean("autoUpdateSubscriptions", true),
                autoUpdateIntervalHours = json.optInt("autoUpdateIntervalHours", 24),
                fileLoggingEnabled = json.optBoolean("fileLoggingEnabled", false),
            )
            com.flowvpn.core.logger.AppLogManager.isFileLoggingEnabled = loaded.fileLoggingEnabled
            loaded
        } catch (e: Exception) {
            Timber.e(e, "Ошибка чтения settings.json, используются значения по умолчанию")
            AppSettings()
        }
    }

    private fun saveSettings(settings: AppSettings) {
        try {
            val json = JSONObject().apply {
                put("dnsProvider", settings.dnsProvider.name)
                put("customDnsUrl", settings.customDnsUrl)
                put("bypassLan", settings.bypassLan)
                put("bypassRussianTraffic", settings.bypassRussianTraffic)
                put("customBypassDomains", org.json.JSONArray(settings.customBypassDomains))
                put("killSwitch", settings.killSwitch)
                put("blockIpv6", settings.blockIpv6)
                put("mtu", settings.mtu)
                put("fakeDns", settings.fakeDns)
                put("sniffing", settings.sniffing)
                put("autoConnect", settings.autoConnect)
                put("autoUpdateSubscriptions", settings.autoUpdateSubscriptions)
                put("autoUpdateIntervalHours", settings.autoUpdateIntervalHours)
                put("fileLoggingEnabled", settings.fileLoggingEnabled)
            }
            val jsonString = json.toString(2)
            settingsFile.writeText(jsonString)

            // Дублирование в SharedPreferences для гарантированного сохранения после выхода
            val sp = context.getSharedPreferences("flowvpn_settings_prefs", Context.MODE_PRIVATE)
            sp.edit().putString("settings_json", jsonString).apply()
        } catch (e: Exception) {
            Timber.e(e, "Ошибка сохранения settings.json")
        }
    }
}

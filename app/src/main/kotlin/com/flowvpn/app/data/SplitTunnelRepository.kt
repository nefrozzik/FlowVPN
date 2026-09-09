package com.flowvpn.app.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.flowvpn.app.ui.screens.splittunnel.AppInfo
import com.flowvpn.core.model.SplitTunnelConfig
import com.flowvpn.core.model.SplitTunnelMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Репозиторий для управления настройками Split Tunneling (раздельного туннелирования)
 * и получения списка установленных приложений.
 */
class SplitTunnelRepository(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val configFile = File(context.filesDir, "split_tunnel.json")

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<SplitTunnelConfig> = _config.asStateFlow()

    /**
     * Получить список всех установленных приложений с названиями и иконками.
     */
    suspend fun getInstalledApps(): List<AppInfo> = withContext(ioDispatcher) {
        val pm = context.packageManager
        val myPackageName = context.packageName

        val apps = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка получения установленных приложений")
            emptyList()
        }

        apps.filter { it.packageName != myPackageName } // Исключаем FlowVPN
            .map { appInfo ->
                val label = try {
                    pm.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) {
                    appInfo.packageName
                }
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val icon = try {
                    pm.getApplicationIcon(appInfo)
                } catch (_: Exception) {
                    null
                }

                AppInfo(
                    name = label,
                    packageName = appInfo.packageName,
                    isSystemApp = isSystem,
                    icon = icon
                )
            }
            .sortedWith(compareBy({ it.isSystemApp }, { it.name.lowercase() }))
    }

    suspend fun setMode(mode: SplitTunnelMode) = withContext(ioDispatcher) {
        val updated = _config.value.copy(mode = mode)
        _config.value = updated
        saveConfig(updated)
    }

    suspend fun togglePackage(packageName: String) = withContext(ioDispatcher) {
        val currentPackages = _config.value.selectedPackages.toMutableSet()
        if (currentPackages.contains(packageName)) {
            currentPackages.remove(packageName)
        } else {
            currentPackages.add(packageName)
        }
        val updated = _config.value.copy(selectedPackages = currentPackages)
        _config.value = updated
        saveConfig(updated)
    }

    suspend fun selectAll(packageNames: Collection<String>) = withContext(ioDispatcher) {
        val updated = _config.value.copy(selectedPackages = packageNames.toSet())
        _config.value = updated
        saveConfig(updated)
    }

    suspend fun clearSelection() = withContext(ioDispatcher) {
        val updated = _config.value.copy(selectedPackages = emptySet())
        _config.value = updated
        saveConfig(updated)
    }

    private fun loadConfig(): SplitTunnelConfig {
        return try {
            if (!configFile.exists()) return SplitTunnelConfig()
            val json = JSONObject(configFile.readText())
            val modeStr = json.optString("mode", SplitTunnelMode.DISABLED.name)
            val mode = try { SplitTunnelMode.valueOf(modeStr) } catch (_: Exception) { SplitTunnelMode.DISABLED }

            val pkgsArray = json.optJSONArray("packages") ?: JSONArray()
            val packages = mutableSetOf<String>()
            for (i in 0 until pkgsArray.length()) {
                packages.add(pkgsArray.getString(i))
            }

            SplitTunnelConfig(mode = mode, selectedPackages = packages)
        } catch (e: Exception) {
            Timber.e(e, "Ошибка загрузки split_tunnel.json")
            SplitTunnelConfig()
        }
    }

    private fun saveConfig(config: SplitTunnelConfig) {
        try {
            val json = JSONObject().apply {
                put("mode", config.mode.name)
                val pkgsArray = JSONArray()
                config.selectedPackages.forEach { pkgsArray.put(it) }
                put("packages", pkgsArray)
            }
            configFile.writeText(json.toString(2))
        } catch (e: Exception) {
            Timber.e(e, "Ошибка сохранения split_tunnel.json")
        }
    }
}

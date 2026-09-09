package com.flowvpn.app.di

import android.content.Context
import com.flowvpn.core.model.ProxyServerConfig
import com.flowvpn.core.subscription.FileSubscriptionRepository
import com.flowvpn.core.subscription.SubscriptionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Простой AppContainer (Manual Dependency Injection) для приложения FlowVPN.
 * Хранит общие репозитории и состояние активного выбранного сервера.
 */
class AppContainer(context: Context) {

    private val subscriptionsFile = File(context.filesDir, "subscriptions.json")

    val subscriptionRepository: SubscriptionRepository by lazy {
        FileSubscriptionRepository(storageFile = subscriptionsFile)
    }

    val splitTunnelRepository: com.flowvpn.app.data.SplitTunnelRepository by lazy {
        com.flowvpn.app.data.SplitTunnelRepository(context = context)
    }

    val settingsRepository: com.flowvpn.app.data.SettingsRepository by lazy {
        com.flowvpn.app.data.SettingsRepository(context = context)
    }

    private val selectedServerFile = File(context.filesDir, "selected_server.json")
    private val selectedServerIdFile = File(context.filesDir, "selected_server_id.txt")
    private val selectedCountryFile = File(context.filesDir, "selected_country.txt")
    private val prefs = context.getSharedPreferences("flowvpn_state_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SERVER_JSON = "selected_server_json"
        private const val KEY_SERVER_ID = "selected_server_id"
        private const val KEY_COUNTRY = "selected_country"
    }

    private val _selectedServer = MutableStateFlow<ProxyServerConfig?>(null)
    val selectedServer: StateFlow<ProxyServerConfig?> = _selectedServer.asStateFlow()

    init {
        // Восстановление выбранного сервера из постоянного хранилища при запуске приложения
        try {
            val jsonStr = prefs.getString(KEY_SERVER_JSON, null)
            if (!jsonStr.isNullOrBlank()) {
                val json = org.json.JSONObject(jsonStr)
                val restored = ProxyServerConfig.fromJson(json)
                _selectedServer.value = restored
                com.flowvpn.core.state.VpnStateManager.currentServerName = restored.name
                com.flowvpn.core.state.VpnStateManager.activeServerConfig = restored
                timber.log.Timber.i("AppContainer: Восстановлен сохраненный сервер из SharedPreferences: ${restored.name} (${restored.country})")
            } else if (selectedServerFile.exists()) {
                val json = org.json.JSONObject(selectedServerFile.readText())
                val restored = ProxyServerConfig.fromJson(json)
                _selectedServer.value = restored
                com.flowvpn.core.state.VpnStateManager.currentServerName = restored.name
                com.flowvpn.core.state.VpnStateManager.activeServerConfig = restored
                timber.log.Timber.i("AppContainer: Восстановлен сохраненный сервер: ${restored.name} (${restored.country})")
            }
        } catch (e: Exception) {
            timber.log.Timber.w(e, "AppContainer: Ошибка восстановления сохраненного сервера")
        }
    }

    fun selectServer(server: ProxyServerConfig?) {
        _selectedServer.value = server
        if (server != null) {
            com.flowvpn.core.state.VpnStateManager.currentServerName = server.name
            com.flowvpn.core.state.VpnStateManager.activeServerConfig = server
            try {
                prefs.edit().apply {
                    putString(KEY_SERVER_JSON, server.toJson().toString())
                    putString(KEY_SERVER_ID, server.id)
                    putString(KEY_COUNTRY, server.country ?: "")
                    apply()
                }
                selectedServerFile.writeText(server.toJson().toString(2))
                selectedServerIdFile.writeText(server.id)
                server.country?.let { selectedCountryFile.writeText(it) }
            } catch (e: Exception) {
                timber.log.Timber.w(e, "AppContainer: Ошибка сохранения выбранного сервера на диск")
            }
        } else {
            com.flowvpn.core.state.VpnStateManager.currentServerName = null
            com.flowvpn.core.state.VpnStateManager.activeServerConfig = null
            try {
                prefs.edit().clear().apply()
                if (selectedServerFile.exists()) selectedServerFile.delete()
                if (selectedServerIdFile.exists()) selectedServerIdFile.delete()
                if (selectedCountryFile.exists()) selectedCountryFile.delete()
            } catch (_: Exception) {}
        }
    }

    fun getSavedServerId(): String? {
        val prefId = prefs.getString(KEY_SERVER_ID, null)?.takeIf { it.isNotBlank() }
        if (prefId != null) return prefId
        return try {
            if (selectedServerIdFile.exists()) {
                selectedServerIdFile.readText().trim().takeIf { it.isNotEmpty() }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun getSavedCountry(): String? {
        val prefCountry = prefs.getString(KEY_COUNTRY, null)?.takeIf { it.isNotBlank() }
        if (prefCountry != null) return prefCountry
        return try {
            if (selectedCountryFile.exists()) {
                selectedCountryFile.readText().trim().takeIf { it.isNotEmpty() }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    val vpnState: StateFlow<com.flowvpn.core.model.VpnState> = com.flowvpn.core.state.VpnStateManager.vpnState

    fun updateVpnState(state: com.flowvpn.core.model.VpnState) {
        com.flowvpn.core.state.VpnStateManager.updateState(state)
    }
}

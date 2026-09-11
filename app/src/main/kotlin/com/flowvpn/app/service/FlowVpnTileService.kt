package com.flowvpn.app.service

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.flowvpn.app.FlowVpnApplication
import com.flowvpn.app.FlowVpnApplication.Companion.getConfigDirectory
import com.flowvpn.app.MainActivity
import com.flowvpn.core.config.SingBoxConfigBuilder
import com.flowvpn.core.model.SplitTunnelMode
import com.flowvpn.vpn.FlowVpnService
import timber.log.Timber
import java.io.File

/**
 * Быстрый переключатель в шторке Android (Quick Settings Tile).
 *
 * Позволяет пользователю включать/отключать FlowVPN в одно касание
 * прямо из системной панели уведомлений Android.
 */
@RequiresApi(Build.VERSION_CODES.N)
class FlowVpnTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()

        val tile = qsTile ?: return
        val isCurrentlyActive = tile.state == Tile.STATE_ACTIVE

        if (isCurrentlyActive) {
            // Останавливаем VPN
            stopVpn()
            tile.state = Tile.STATE_INACTIVE
            tile.subtitle = "Отключено"
            tile.updateTile()
        } else {
            // Проверяем наличие разрешения VPN
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                // Разрешение еще не выдано — открываем экран приложения
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pendingIntent = android.app.PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    startActivityAndCollapse(pendingIntent)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(intent)
                }
                return
            }

            // Запускаем VPN с текущим сервером
            val configPath = prepareActiveConfig()
            if (configPath != null) {
                startVpn(configPath)
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "Подключение..."
                tile.updateTile()
            }
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val app = application as? FlowVpnApplication ?: return
        val activeServer = app.container.selectedServer.value

        tile.label = "FlowVPN"
        if (tile.state == Tile.STATE_ACTIVE) {
            tile.subtitle = activeServer?.name ?: "Подключено"
        } else {
            tile.subtitle = activeServer?.name ?: "Отключено"
            tile.state = Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }

    private fun prepareActiveConfig(): String? {
        val app = application as? FlowVpnApplication ?: return null
        val server = app.container.selectedServer.value ?: return null

        return try {
            val splitConfig = app.container.splitTunnelRepository.config.value
            val enabledApps = if (splitConfig.mode == SplitTunnelMode.ALLOW_LIST) {
                splitConfig.selectedPackages.toList()
            } else null
            val excludedApps = if (splitConfig.mode == SplitTunnelMode.DISALLOW_LIST) {
                (splitConfig.selectedPackages + packageName).toList()
            } else {
                listOf(packageName)
            }

            val settings = app.container.settingsRepository.settings.value

            val isWarpServer = server.id.startsWith("warp-") ||
                    server.protocol == com.flowvpn.core.model.ProxyProtocol.MASQUE ||
                    (server.protocol == com.flowvpn.core.model.ProxyProtocol.WIREGUARD &&
                            (server.name.contains("WARP", ignoreCase = true) || server.address.startsWith("162.159.") || server.address.startsWith("188.114.")))

            val underlyingProxy = if (isWarpServer && settings.enableWarpChaining) {
                app.container.subscriptionRepository.getCachedSubscriptions()
                    .flatMap { it.servers }
                    .firstOrNull { candidate ->
                        candidate.id != server.id &&
                        candidate.protocol != com.flowvpn.core.model.ProxyProtocol.WIREGUARD &&
                        candidate.protocol != com.flowvpn.core.model.ProxyProtocol.MASQUE &&
                        (settings.warpMode != com.flowvpn.core.model.WarpMode.WIREGUARD || (!candidate.isOutline && candidate.prefix.isNullOrBlank()))
                    }
            } else null

            val configDir = app.getConfigDirectory()
            val underlyingFile = File(configDir, "underlying_proxy.json")
            if (underlyingProxy != null) {
                underlyingFile.writeText(underlyingProxy.toJson().toString(2))
            } else {
                if (underlyingFile.exists()) underlyingFile.delete()
            }

            val configJson = SingBoxConfigBuilder.build(
                config = server,
                settings = settings,
                enabledApps = enabledApps,
                excludedApps = excludedApps,
                underlyingProxy = underlyingProxy,
            )
            val configFile = File(configDir, "config.json")
            configDir.mkdirs()
            configFile.writeText(configJson)
            configFile.absolutePath
        } catch (e: Exception) {
            Timber.e(e, "TileService: Ошибка подготовки конфигурации")
            null
        }
    }

    private fun startVpn(configPath: String) {
        val intent = Intent(this, FlowVpnService::class.java).apply {
            action = FlowVpnService.ACTION_START
            putExtra(FlowVpnService.EXTRA_CONFIG_PATH, configPath)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopVpn() {
        val intent = Intent(this, FlowVpnService::class.java).apply {
            action = FlowVpnService.ACTION_STOP
        }
        startService(intent)
    }
}

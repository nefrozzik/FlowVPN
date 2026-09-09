package com.flowvpn.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowvpn.app.ui.screens.home.HomeScreen
import com.flowvpn.app.ui.theme.FlowVpnTheme
import com.flowvpn.vpn.FlowVpnService
import timber.log.Timber

/**
 * Единственная Activity приложения (Single Activity Architecture).
 *
 * ## VPN Permission Flow
 *
 * Android требует явное разрешение пользователя перед запуском VPN:
 *
 * 1. `VpnService.prepare(context)` проверяет наличие разрешения
 * 2. Если разрешение не дано — возвращает Intent для системного диалога
 * 3. Пользователь подтверждает → `onActivityResult` → можно запускать VPN
 * 4. Если разрешение уже дано — возвращает null → запускаем сразу
 *
 * Разрешение сохраняется до явного отзыва или запуска другого VPN-приложения.
 */
class MainActivity : ComponentActivity() {

    /**
     * Activity Result API (замена deprecated onActivityResult).
     *
     * Регистрируем launcher для VPN permission dialog.
     * RESULT_OK = пользователь разрешил VPN.
     */
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Timber.i("MainActivity: VPN permission granted")
            com.flowvpn.core.state.VpnStateManager.updateState(com.flowvpn.core.model.VpnState.Connecting)
            startVpnService()
        } else {
            Timber.w("MainActivity: VPN permission denied")
            com.flowvpn.core.state.VpnStateManager.setDisconnected()
            com.flowvpn.core.logger.CoreLogManager.log("Пользователь отклонил запрос на создание VPN-подключения", com.flowvpn.core.logger.LogLevel.WARN)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Timber.i("MainActivity: POST_NOTIFICATIONS разрешено: $isGranted")
    }

    /** Путь к конфигу, ожидающий разрешения VPN */
    private var pendingConfigPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        pendingConfigPath = savedInstanceState?.getString("pending_config_path")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val appPrefs = getSharedPreferences("flowvpn_app_prefs", Context.MODE_PRIVATE)
        val hasPromptedBattery = appPrefs.getBoolean("has_prompted_battery_opt", false)
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isIgnoringBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager?.isIgnoringBatteryOptimizations(packageName) == true
        } else {
            true
        }
        val shouldShowBatteryPrompt = !hasPromptedBattery && !isIgnoringBattery

        setContent {
            FlowVpnTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    var showBatteryDialog by remember { mutableStateOf(shouldShowBatteryPrompt) }

                    com.flowvpn.app.ui.MainScaffold(
                        onConnectClick = { configPath ->
                            requestVpnPermissionAndStart(configPath)
                        },
                        onDisconnectClick = {
                            stopVpnService()
                        },
                    )

                    if (showBatteryDialog) {
                        AlertDialog(
                            onDismissRequest = {
                                appPrefs.edit().putBoolean("has_prompted_battery_opt", true).apply()
                                showBatteryDialog = false
                            },
                            icon = {
                                Icon(
                                    imageVector = Icons.Default.BatteryChargingFull,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                            },
                            title = {
                                Text(
                                    text = "Фоновая работа без ограничений",
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            },
                            text = {
                                Text(
                                    text = "Для стабильной работы VPN без внезапных отключений и разрывов соединения, рекомендуется разрешить приложению FlowVPN работу в фоновом режиме без ограничений батареи.\n\nВ открывшемся окне подтвердите выбор «Без ограничений» или нажмите «Разрешить».",
                                    style = MaterialTheme.typography.bodyMedium,
                                    lineHeight = 20.sp
                                )
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        appPrefs.edit().putBoolean("has_prompted_battery_opt", true).apply()
                                        showBatteryDialog = false
                                        requestIgnoreBatteryOptimizations()
                                    }
                                ) {
                                    Text("Настроить")
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = {
                                        appPrefs.edit().putBoolean("has_prompted_battery_opt", true).apply()
                                        showBatteryDialog = false
                                    }
                                ) {
                                    Text("Позже")
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                } catch (_: Exception) {
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("pending_config_path", pendingConfigPath)
    }

    /**
     * Запросить VPN-разрешение и запустить сервис.
     *
     * VpnService.prepare() — статический метод Android SDK:
     * - Возвращает null если разрешение уже есть → запускаем сразу
     * - Возвращает Intent → показываем системный диалог
     *
     * @param configPath путь к config.json для sing-box
     */
    private fun requestVpnPermissionAndStart(configPath: String) {
        pendingConfigPath = configPath

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            // Разрешение не дано — показываем системный диалог
            Timber.d("MainActivity: Запрос VPN permission")
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            // Разрешение уже есть — запускаем VPN
            Timber.d("MainActivity: VPN permission уже есть")
            startVpnService()
        }
    }

    /**
     * Запустить FlowVpnService через explicit Intent.
     *
     * Используем startForegroundService() (API 26+) — Android требует
     * вызвать startForeground() в сервисе в течение 5 секунд,
     * иначе система бросит ANR.
     */
    private fun startVpnService() {
        val configPath = pendingConfigPath ?: java.io.File(filesDir, "sing-box/config.json").takeIf { it.exists() }?.absolutePath
        if (configPath == null) {
            Timber.e("MainActivity: configPath отсутствует")
            com.flowvpn.core.state.VpnStateManager.setError("Конфигурация не найдена")
            return
        }
        pendingConfigPath = null

        try {
            val intent = Intent(this, FlowVpnService::class.java).apply {
                action = FlowVpnService.ACTION_START
                putExtra(FlowVpnService.EXTRA_CONFIG_PATH, configPath)
            }

            startForegroundService(intent)
            Timber.i("MainActivity: FlowVpnService запущен")
        } catch (t: Throwable) {
            Timber.e(t, "MainActivity: Ошибка запуска FlowVpnService")
            com.flowvpn.core.logger.CoreLogManager.log("Ошибка запуска сервиса: ${t.message}", com.flowvpn.core.logger.LogLevel.ERROR)
            com.flowvpn.core.state.VpnStateManager.setError("Ошибка запуска: ${t.message}")
        }
    }

    /**
     * Остановить FlowVpnService.
     */
    private fun stopVpnService() {
        val intent = Intent(this, FlowVpnService::class.java).apply {
            action = FlowVpnService.ACTION_STOP
        }
        startService(intent)
        com.flowvpn.core.state.VpnStateManager.setDisconnected()
        Timber.i("MainActivity: FlowVpnService остановлен")
    }
}

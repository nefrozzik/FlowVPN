package com.flowvpn.app.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowvpn.app.FlowVpnApplication
import com.flowvpn.core.model.DnsProvider
import com.flowvpn.core.model.SplitTunnelMode
import kotlinx.coroutines.launch

/**
 * Экран расширенных сетевых настроек FlowVPN (в стиле Amnezia VPN).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToSplitTunneling: () -> Unit,
    onNavigateToLogs: () -> Unit = {},
) {
    val context = LocalContext.current
    val container = (context.applicationContext as FlowVpnApplication).container
    val splitRepo = container.splitTunnelRepository
    val settingsRepo = container.settingsRepository

    val splitConfig by splitRepo.config.collectAsState()
    val settings by settingsRepo.settings.collectAsState()
    val scope = rememberCoroutineScope()

    var showDnsDialog by remember { mutableStateOf(false) }
    var showMtuDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showUpdateIntervalDialog by remember { mutableStateOf(false) }
    var isManualUpdating by remember { mutableStateOf(false) }
    var isCheckingRoot by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Настройки", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ─── 1. МАРШРУТИЗАЦИЯ И ТРАФИК ───
            item {
                Text(
                    text = "МАРШРУТИЗАЦИЯ И ТРАФИК",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                )
            }

            // Обход сайтов РФ (Российские сервисы напрямую) — Ключевая фича Amnezia VPN
            item {
                SettingsSwitchCard(
                    icon = Icons.Default.Public,
                    title = "Обход сайтов РФ (Сервисы напрямую)",
                    subtitle = "Госуслуги, банки, Яндекс и маркетплейсы (.ru, .рф) работают в обход VPN",
                    checked = settings.bypassRussianTraffic,
                    onCheckedChange = { scope.launch { settingsRepo.setBypassRussianTraffic(it) } }
                )
            }

            // Обход локальной сети (Bypass LAN)
            item {
                SettingsSwitchCard(
                    icon = Icons.Default.Dns,
                    title = "Обход локальной сети (Bypass LAN)",
                    subtitle = "Прямой доступ к роутерам, принтерам и устройствам домашней сети (192.168.x.x)",
                    checked = settings.bypassLan,
                    onCheckedChange = { scope.launch { settingsRepo.setBypassLan(it) } }
                )
            }

            // Раздельное туннелирование (Per-App Split Tunneling)
            item {
                val splitSubtitle = when (splitConfig.mode) {
                    SplitTunnelMode.DISABLED -> "Выключено (весь трафик через VPN)"
                    SplitTunnelMode.ALLOW_LIST -> "Включено для ${splitConfig.selectedPackages.size} приложений"
                    SplitTunnelMode.DISALLOW_LIST -> "Обход для ${splitConfig.selectedPackages.size} приложений"
                }

                SettingsClickableCard(
                    icon = Icons.AutoMirrored.Filled.AltRoute,
                    title = "Раздельное туннелирование (Per-App)",
                    subtitle = splitSubtitle,
                    onClick = onNavigateToSplitTunneling
                )
            }

            // ─── 2. БЕЗОПАСНОСТЬ И DNS ───
            item {
                Text(
                    text = "БЕЗОПАСНОСТЬ И DNS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                )
            }

            // Выбор DNS-сервера
            item {
                val dnsSubtitle = if (settings.dnsProvider == DnsProvider.CUSTOM) {
                    settings.customDnsUrl.ifEmpty { "Кастомный DoH не задан" }
                } else {
                    "${settings.dnsProvider.displayName} — ${settings.dnsProvider.description}"
                }

                SettingsClickableCard(
                    icon = Icons.Default.Security,
                    title = "DNS-сервер и протокол DoH",
                    subtitle = dnsSubtitle,
                    onClick = { showDnsDialog = true }
                )
            }

            // Kill Switch
            item {
                SettingsSwitchCard(
                    icon = Icons.Default.Lock,
                    title = "Аварийная блокировка (Kill Switch)",
                    subtitle = "Блокировать весь интернет при разрыве или сбое VPN-соединения",
                    checked = settings.killSwitch,
                    onCheckedChange = { scope.launch { settingsRepo.setKillSwitch(it) } }
                )
            }

            // Защита от утечек IPv6
            item {
                SettingsSwitchCard(
                    icon = Icons.Default.Security,
                    title = "Защита от утечек IPv6 (Блокировать IPv6)",
                    subtitle = "Предотвращает обнаружение вашего реального IP-адреса через протокол IPv6",
                    checked = settings.blockIpv6,
                    onCheckedChange = { scope.launch { settingsRepo.setBlockIpv6(it) } }
                )
            }

            // FakeDNS & Sniffing
            item {
                SettingsSwitchCard(
                    icon = Icons.Default.Speed,
                    title = "FakeDNS и перехват доменов (Sniffing)",
                    subtitle = "Мгновенный резолвинг доменов и защита от цензуры DPI на уровне SNI",
                    checked = settings.fakeDns,
                    onCheckedChange = { scope.launch { settingsRepo.setFakeDns(it) } }
                )
            }

            // ─── 3. ОБНОВЛЕНИЕ КОНФИГУРАЦИЙ И ПОДПИСОК ───
            item {
                Text(
                    text = "ОБНОВЛЕНИЕ КОНФИГУРАЦИЙ И ПОДПИСОК",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                )
            }

            // Автообновление при входе
            item {
                SettingsSwitchCard(
                    icon = Icons.Default.Refresh,
                    title = "Автообновление при входе",
                    subtitle = "Проверять и обновлять профили и подписки при запуске приложения",
                    checked = settings.autoUpdateSubscriptions,
                    onCheckedChange = { scope.launch { settingsRepo.setAutoUpdateSubscriptions(it) } }
                )
            }

            // Интервал устаревания
            item {
                val intervalText = when (settings.autoUpdateIntervalHours) {
                    1 -> "Каждый 1 час"
                    6 -> "Каждые 6 часов"
                    12 -> "Каждые 12 часов"
                    24 -> "Каждые 24 часа (Рекомендуется)"
                    48 -> "Каждые 48 часов"
                    else -> "Каждые ${settings.autoUpdateIntervalHours} ч."
                }
                SettingsClickableCard(
                    icon = Icons.Default.Tune,
                    title = "Интервал устаревания конфигураций",
                    subtitle = if (settings.autoUpdateSubscriptions) intervalText else "Отключено (автообновление выключено)",
                    onClick = { showUpdateIntervalDialog = true }
                )
            }

            // Ручное обновление сейчас
            item {
                SettingsClickableCard(
                    icon = Icons.Default.Refresh,
                    title = if (isManualUpdating) "Обновление подписок..." else "Обновить конфигурацию сейчас",
                    subtitle = "Загрузить свежие серверы и правила из удаленных источников",
                    onClick = {
                        if (!isManualUpdating) {
                            scope.launch {
                                isManualUpdating = true
                                try {
                                    val updated = container.subscriptionRepository.updateAllSubscriptions()
                                    val count = updated.sumOf { it.servers.size }
                                    snackbarHostState.showSnackbar("Подписки обновлены: $count серверов доступно")
                                } catch (e: Exception) {
                                    val err = e.message ?: "Ошибка сети"
                                    snackbarHostState.showSnackbar("Ошибка обновления: $err")
                                } finally {
                                    isManualUpdating = false
                                }
                            }
                        }
                    }
                )
            }

            // ─── 4. СЕТЕВОЙ СТЕК И СИСТЕМА ───
            item {
                Text(
                    text = "СЕТЕВОЙ СТЕК И СИСТЕМА",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                )
            }

            // MTU Selector
            item {
                val mtuDesc = when (settings.mtu) {
                    9000 -> "9000 (Рекомендуемый для Sing-box / Скоростной)"
                    1500 -> "1500 (Стандартный Ethernet / Wi-Fi)"
                    1420 -> "1420 (WireGuard / Сотовые сети LTE/5G)"
                    1280 -> "1280 (MSS Safe / Для нестабильных сетей)"
                    else -> "${settings.mtu} (Пользовательский)"
                }

                SettingsClickableCard(
                    icon = Icons.Default.Tune,
                    title = "Размер блока данных (MTU)",
                    subtitle = mtuDesc,
                    onClick = { showMtuDialog = true }
                )
            }

            // Автоподключение
            item {
                SettingsSwitchCard(
                    icon = Icons.Default.Refresh,
                    title = "Автоподключение при старте",
                    subtitle = "Автоматически подключать выбранный сервер при запуске FlowVPN",
                    checked = settings.autoConnect,
                    onCheckedChange = { scope.launch { settingsRepo.setAutoConnect(it) } }
                )
            }

            // Раздача VPN через точку доступа (Root)
            item {
                SettingsSwitchCard(
                    icon = Icons.Default.WifiTethering,
                    title = "Раздача VPN через точку доступа (Root)",
                    subtitle = if (isCheckingRoot) "Проверка root-прав в системе..." else "Маршрутизация трафика точки доступа Wi-Fi (Hotspot) через VPN с помощью iptables и root-прав",
                    checked = settings.rootTethering,
                    enabled = !isCheckingRoot,
                    onCheckedChange = { enable ->
                        if (isCheckingRoot) return@SettingsSwitchCard
                        scope.launch {
                            if (enable) {
                                isCheckingRoot = true
                                val hasRoot = try {
                                    com.flowvpn.vpn.RootTetheringManager.isRootAvailable()
                                } finally {
                                    isCheckingRoot = false
                                }
                                if (!hasRoot) {
                                    snackbarHostState.showSnackbar("Root-доступ не обнаружен. Требуются права суперпользователя (su).")
                                    return@launch
                                }
                            }
                            settingsRepo.setRootTethering(enable)
                            val isConnected = com.flowvpn.core.state.VpnStateManager.vpnState.value is com.flowvpn.core.model.VpnState.Connected
                            if (isConnected) {
                                if (enable) {
                                    val ok = com.flowvpn.vpn.RootTetheringManager.enableTethering()
                                    if (ok) snackbarHostState.showSnackbar("Раздача VPN через точку доступа активирована")
                                    else snackbarHostState.showSnackbar("Не удалось применить правила iptables для раздачи")
                                } else {
                                    com.flowvpn.vpn.RootTetheringManager.disableTethering()
                                    snackbarHostState.showSnackbar("Раздача VPN через точку доступа отключена")
                                }
                            }
                        }
                    }
                )
            }

            // Логи ядра sing-box
            item {
                SettingsClickableCard(
                    icon = Icons.Default.Info,
                    title = "Логи ядра (Core Log Viewer)",
                    subtitle = "Просмотр системных сообщений, маршрутов и событий sing-box",
                    onClick = onNavigateToLogs
                )
            }

            // Подробный сбор логов приложения (Файловый лог)
            item {
                SettingsSwitchCard(
                    icon = Icons.Default.BugReport,
                    title = "Сбор логов приложения в файл",
                    subtitle = "Запись логов интерфейса, сервиса и ядра на диск для детальной отладки (по умолчанию выкл)",
                    checked = settings.fileLoggingEnabled,
                    onCheckedChange = { scope.launch { settingsRepo.setFileLoggingEnabled(it) } }
                )
            }

            // Экспорт логов приложения
            item {
                SettingsClickableCard(
                    icon = Icons.Default.Share,
                    title = "Экспорт логов приложения",
                    subtitle = "Сформировать файл с системной диагностикой, логами и отчетом о вылете приложения",
                    onClick = {
                        com.flowvpn.app.util.LogExportHelper.exportAndShareLogs(context)
                    }
                )
            }

            // Очистка файлов логов
            item {
                SettingsClickableCard(
                    icon = Icons.Default.Delete,
                    title = "Очистить сохраненные логи",
                    subtitle = "Удалить файлы app_debug.log и сохраненные отчеты об ошибках",
                    onClick = {
                        com.flowvpn.core.logger.AppLogManager.clearAllLogs()
                        scope.launch { snackbarHostState.showSnackbar("Файлы логов и отчеты о падениях очищены") }
                    }
                )
            }

            // Оптимизация батареи (Doze Mode)
            item {
                SettingsClickableCard(
                    icon = Icons.Default.BatterySaver,
                    title = "Фоновая работа (Doze Mode)",
                    subtitle = "Отключение энергосбережения для стабильного VPN без отключений",
                    onClick = {
                        try {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    }
                )
            }

            // Сброс настроек
            item {
                SettingsClickableCard(
                    icon = Icons.Default.Refresh,
                    title = "Сбросить настройки сети",
                    subtitle = "Вернуть параметры маршрутизации и DNS к начальным значениям",
                    onClick = { showResetDialog = true }
                )
            }

            // ─── 5. О СИСТЕМЕ ───
            item {
                Text(
                    text = "О СИСТЕМЕ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                )
            }

            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "FlowVPN Client",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Core Engine: sing-box libbox v1.14+\nСтек: Android TUN API 26-35, Material Design 3\nПоддержка: VLESS Reality, VMess, Trojan, Hysteria 2, WireGuard, ShadowSocks\nПравила: Amnezia Routing, Bypass LAN, DoH Resolver, FakeDNS",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }

    // ─── ДИАЛОГ ВЫБОРА DNS ───
    if (showDnsDialog) {
        var selectedProvider by remember { mutableStateOf(settings.dnsProvider) }
        var customUrl by remember { mutableStateOf(settings.customDnsUrl) }

        AlertDialog(
            onDismissRequest = { showDnsDialog = false },
            title = { Text("Выбор DNS-сервера", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DnsProvider.entries.forEach { provider ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedProvider = provider }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = selectedProvider == provider,
                                onClick = { selectedProvider = provider }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = provider.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = provider.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    if (selectedProvider == DnsProvider.CUSTOM) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = customUrl,
                            onValueChange = { customUrl = it },
                            label = { Text("DoH URL или IP") },
                            placeholder = { Text("https://example.com/dns-query") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            settingsRepo.setDnsProvider(selectedProvider)
                            if (selectedProvider == DnsProvider.CUSTOM) {
                                settingsRepo.setCustomDnsUrl(customUrl)
                            }
                        }
                        showDnsDialog = false
                    }
                ) {
                    Text("Применить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDnsDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    // ─── ДИАЛОГ ВЫБОРА MTU ───
    if (showMtuDialog) {
        val mtuOptions = listOf(
            9000 to "9000 — Рекомендуемый (Sing-box)",
            1500 to "1500 — Стандартный (Ethernet / Wi-Fi)",
            1420 to "1420 — WireGuard / Сотовые сети (LTE/5G)",
            1280 to "1280 — MSS Safe (Минимум для IPv6)",
        )
        var selectedMtu by remember { mutableStateOf(settings.mtu) }

        AlertDialog(
            onDismissRequest = { showMtuDialog = false },
            title = { Text("Размер пакета MTU", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    mtuOptions.forEach { (mtuValue, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedMtu = mtuValue }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = selectedMtu == mtuValue,
                                onClick = { selectedMtu = mtuValue }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { settingsRepo.setMtu(selectedMtu) }
                        showMtuDialog = false
                    }
                ) {
                    Text("Применить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMtuDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    // ─── ДИАЛОГ СБРОСА НАСТРОЕК ───
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Сброс настроек сети", fontWeight = FontWeight.Bold) },
            text = {
                Text("Вернуть все параметры маршрутизации, DNS, MTU и безопасности к значениям по умолчанию?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { settingsRepo.resetToDefaults() }
                        showResetDialog = false
                    }
                ) {
                    Text("Сбросить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    // ─── ДИАЛОГ ВЫБОРА ИНТЕРВАЛА АВТООБНОВЛЕНИЯ ───
    if (showUpdateIntervalDialog) {
        val intervalOptions = listOf(
            1 to "Каждый 1 час (Частое обновление)",
            6 to "Каждые 6 часов",
            12 to "Каждые 12 часов",
            24 to "Каждые 24 часа (Рекомендуется)",
            48 to "Каждые 48 часов (Экономия трафика)",
        )
        var selectedInterval by remember { mutableStateOf(settings.autoUpdateIntervalHours) }

        AlertDialog(
            onDismissRequest = { showUpdateIntervalDialog = false },
            title = { Text("Интервал обновления", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    intervalOptions.forEach { (hours, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedInterval = hours }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = selectedInterval == hours,
                                onClick = { selectedInterval = hours }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { settingsRepo.setAutoUpdateIntervalHours(selectedInterval) }
                        showUpdateIntervalDialog = false
                    }
                ) {
                    Text("Применить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateIntervalDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
private fun SettingsClickableCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun SettingsSwitchCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.padding(end = 8.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

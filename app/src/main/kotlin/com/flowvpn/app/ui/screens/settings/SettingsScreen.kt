package com.flowvpn.app.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
    var showBypassDomainsDialog by remember { mutableStateOf(false) }
    var isManualUpdating by remember { mutableStateOf(false) }
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

            // Настройка списка сайтов для обхода РФ
            item {
                val customCount = settings.customBypassDomains.size
                val bypassSubtitle = if (customCount > 0) {
                    "Базовые зоны (.ru, .рф, .su, .by, .kz) + $customCount своих доменов"
                } else {
                    "Базовые зоны (.ru, .рф, .su, .by, .kz). Нажмите для добавления своих сайтов"
                }
                SettingsClickableCard(
                    icon = Icons.AutoMirrored.Filled.AltRoute,
                    title = "Список сайтов для обхода РФ",
                    subtitle = bypassSubtitle,
                    onClick = { showBypassDomainsDialog = true }
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
                            val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = android.net.Uri.parse("package:${context.packageName}")
                                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            try {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }
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
                                text = "FlowVPN Client v${com.flowvpn.app.BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Версия: ${com.flowvpn.app.BuildConfig.VERSION_NAME} (Публичная бета)\nЯдро: sing-box (libbox) v1.14+\nСтек: Android TUN API 26-35, Material Design 3\nПротоколы: VLESS Reality, VMess, Trojan, Hysteria 2, WireGuard, ShadowSocks\nФункции: Обход РФ, Per-App Split Tunneling, DoH, FakeDNS, Doze Mode",
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

    // ─── ДИАЛОГ СПИСКА САЙТОВ ДЛЯ ОБХОДА РФ ───
    if (showBypassDomainsDialog) {
        var newDomainInput by remember { mutableStateOf("") }
        var inputError by remember { mutableStateOf<String?>(null) }
        val baseDomains = listOf(".ru", ".рф (.xn--p1ai)", ".su", ".by", ".kz")
        val scrollState = rememberScrollState()

        AlertDialog(
            onDismissRequest = { showBypassDomainsDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Public,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Обход сайтов РФ", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Базовые доменные зоны:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Весь трафик к сайтам и поддоменам этих зон направляется напрямую мимо VPN:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Чипы базовых зон
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        baseDomains.take(3).forEach { d ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = d,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        baseDomains.drop(3).forEach { d ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = d,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        text = "Свои сайты и домены:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Добавьте домены (например: yandex.net, vk.me, kinopoisk.ru), которые нужно пускать напрямую без VPN:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Поле ввода нового домена
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = newDomainInput,
                            onValueChange = {
                                newDomainInput = it
                                inputError = null
                            },
                            label = { Text("Домен или суффикс") },
                            placeholder = { Text("например: 2gis.ru") },
                            singleLine = true,
                            isError = inputError != null,
                            supportingText = inputError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val clean = newDomainInput.trim().lowercase()
                                    .removePrefix("http://")
                                    .removePrefix("https://")
                                    .removePrefix("/")
                                    .substringBefore("/")
                                    .trim()
                                if (clean.isBlank()) {
                                    inputError = "Введите домен"
                                } else if (settings.customBypassDomains.contains(clean)) {
                                    inputError = "Уже в списке"
                                } else {
                                    scope.launch {
                                        settingsRepo.addCustomBypassDomain(clean)
                                    }
                                    newDomainInput = ""
                                    inputError = null
                                }
                            }
                        ) {
                            Text("Добавить")
                        }
                    }

                    // Список добавленных пользователем доменов
                    if (settings.customBypassDomains.isEmpty()) {
                        Text(
                            text = "Пользовательские домены еще не добавлены.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            settings.customBypassDomains.forEach { domain ->
                                Card(
                                    shape = RoundedCornerShape(8.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = domain,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        IconButton(
                                            onClick = {
                                                scope.launch {
                                                    settingsRepo.removeCustomBypassDomain(domain)
                                                }
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Удалить",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBypassDomainsDialog = false }) {
                    Text("Готово")
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

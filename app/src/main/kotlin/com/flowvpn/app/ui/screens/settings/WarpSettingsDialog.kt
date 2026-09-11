package com.flowvpn.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowvpn.core.model.AppSettings
import com.flowvpn.core.model.ProxyServerConfig
import com.flowvpn.core.model.WarpConfig
import com.flowvpn.core.model.WarpMode
import com.flowvpn.core.warp.WarpManager
import com.flowvpn.core.warp.WarpScanResult
import com.flowvpn.core.warp.WarpScanner
import kotlinx.coroutines.launch

/**
 * Диалог настройки разблокировщика Cloudflare WARP / WARP+ (в стиле Hiddify Chain).
 */
@Composable
fun WarpSettingsDialog(
    onDismiss: () -> Unit,
    settings: AppSettings,
    onSaveSettings: (enableChaining: Boolean, licenseKey: String, warpConfig: WarpConfig?, warpMode: WarpMode) -> Unit,
    onAddServer: (ProxyServerConfig) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val vpnState by com.flowvpn.core.state.VpnStateManager.vpnState.collectAsState()
    val isVpnConnected = vpnState is com.flowvpn.core.model.VpnState.Connected

    var enableChaining by remember { mutableStateOf(settings.enableWarpChaining) }
    var licenseKey by remember { mutableStateOf(settings.warpLicenseKey) }
    var currentWarpConfig by remember { mutableStateOf(settings.getWarpConfig()) }
    var selectedWarpMode by remember { mutableStateOf(currentWarpConfig?.warpMode ?: settings.warpMode) }

    var currentJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var serverAddedMessage by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showVpnWarningDialog by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var importText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }

    fun executeWithVpnCheck(action: () -> Unit) {
        if (!isVpnConnected) {
            pendingAction = action
            showVpnWarningDialog = true
        } else {
            action()
        }
    }

    var isScanning by remember { mutableStateOf(false) }
    var scanCompleted by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var scanResults by remember { mutableStateOf<List<WarpScanResult>>(emptyList()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Cloudflare WARP",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Разблокировщик сайтов (Цепочка VPN ➔ WARP)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Карточка переключателя цепочки
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (enableChaining)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Цепочка: VPN ➔ WARP",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Трафик шифруется и идет через ваш VPN-сервер на Cloudflare WARP. Сайты видят чистый IP Cloudflare (обход банов ChatGPT, Netflix и проверок портов).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp
                            )
                        }
                        Switch(
                            checked = enableChaining,
                            onCheckedChange = { checked ->
                                if (checked && currentWarpConfig == null) {
                                    // Автоматически регистрируем аккаунт, если еще нет
                                    executeWithVpnCheck {
                                        currentJob = scope.launch {
                                            isLoading = true
                                            statusMessage = "Регистрация аккаунта Cloudflare..."
                                            errorMessage = null
                                            val result = WarpManager.register(licenseKey.ifBlank { null })
                                            if (result.isSuccess) {
                                                val cfg = result.getOrNull()
                                                currentWarpConfig = cfg
                                                enableChaining = true
                                                if (licenseKey.isNotBlank() && cfg?.accountType != "warp_plus") {
                                                    statusMessage = "Аккаунт WARP создан (бесплатный). Ключ не привязался (см. Логи)."
                                                } else {
                                                    statusMessage = "Аккаунт WARP успешно создан!"
                                                }
                                            } else {
                                                errorMessage = result.exceptionOrNull()?.message ?: "Ошибка регистрации"
                                            }
                                            isLoading = false
                                        }
                                    }
                                } else {
                                    enableChaining = checked
                                }
                            }
                        )
                    }
                }

                // Статус VPN и подсказка для пользователей из РФ
                if (isVpnConnected) {
                    val serverName = (vpnState as? com.flowvpn.core.model.VpnState.Connected)?.serverName ?: "VPN"
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF2E7D32).copy(alpha = 0.12f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF2E7D32),
                                modifier = Modifier.size(20.dp).padding(top = 2.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "VPN подключен: $serverName",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF2E7D32)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Локальный прокси sing-box активен (127.0.0.1:2080). Создание аккаунта и привязка ключа Cloudflare пойдут в обход ТСПУ через защищенный VPN-туннель!",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    lineHeight = 15.sp,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp).padding(top = 2.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Внимание: VPN отключен!",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "В РФ Cloudflare API заблокирован ТСПУ (вызывает ошибку «Read timed out»). Чтобы зарегистрировать аккаунт или привязать ключ: сначала включите VPN на Главном экране (любой сервер VLESS/Shadowsocks), затем вернитесь сюда! Либо нажмите «Импортировать конфиг» ниже.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    lineHeight = 15.sp,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                // Выбор протокола WARP (WireGuard / MASQUE H2 / MASQUE H3)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Протокол подключения Cloudflare:",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        WarpMode.values().forEach { mode ->
                            val isSelected = selectedWarpMode == mode
                            Surface(
                                color = if (isSelected)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedWarpMode = mode
                                        currentWarpConfig = currentWarpConfig?.copy(warpMode = mode)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.Info,
                                        contentDescription = null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = mode.displayName,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }

                // Статус аккаунта
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val isRegistered = currentWarpConfig != null
                            val isPlus = currentWarpConfig?.accountType == "warp_plus"

                            Icon(
                                imageVector = if (isRegistered) Icons.Default.CheckCircle else Icons.Default.Info,
                                contentDescription = null,
                                tint = if (isRegistered) {
                                    if (isPlus) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
                                } else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when {
                                    !isRegistered -> "Аккаунт: Не зарегистрирован"
                                    isPlus -> "Статус: Cloudflare WARP+ (Безлимитный)"
                                    else -> "Статус: Cloudflare WARP (Бесплатный)"
                                },
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isPlus) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        currentWarpConfig?.let { cfg ->
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                            Text(
                                text = "ID: ${cfg.accountId.take(8)}... | Эндпоинт: ${cfg.endpointHost}:${cfg.endpointPort}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                            Text(
                                text = "IPv4: ${cfg.localAddressV4} | MTU: ${cfg.mtu}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                // ─── Сканер чистых Anycast-эндпоинтов WARP ───
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Сканер чистых эндпоинтов WARP",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Поиск незаблокированных ТСПУ IP Cloudflare",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    isScanning = true
                                    scanCompleted = false
                                    scanResults = emptyList()
                                    scope.launch {
                                        val results = WarpScanner.scan(
                                            warpConfig = currentWarpConfig,
                                            onProgress = { c, t, _ ->
                                                scanProgress = c to t
                                            }
                                        )
                                        scanResults = results
                                        isScanning = false
                                        scanCompleted = true
                                        scanProgress = null
                                    }
                                },
                                enabled = !isScanning,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                if (isScanning) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(14.dp))
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (isScanning) "Поиск..." else "Найти IP", fontSize = 11.sp)
                            }
                        }

                        scanProgress?.let { (checked, total) ->
                            Text(
                                text = "🔍 Проверено: $checked из $total эндпоинтов...",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        if (scanCompleted && scanResults.isEmpty()) {
                            Text(
                                text = "⚠️ Не удалось получить ответ от эндпоинтов. Проверьте интернет-соединение или включите VPN перед сканированием.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 11.sp
                            )
                        }

                        if (scanResults.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Найдено: ${scanResults.size}. Нажмите для выбора:",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                val best = scanResults.first()
                                TextButton(
                                    onClick = {
                                        val cfg = currentWarpConfig
                                        if (cfg != null) {
                                            currentWarpConfig = cfg.copy(
                                                endpointHost = best.ip,
                                                endpointPort = best.port
                                            )
                                            statusMessage = "Применен лучший: ${best.ip}:${best.port} (${best.pingMs} ms)"
                                        } else {
                                            statusMessage = "Выбран ${best.ip}:${best.port}. Создайте аккаунт для сохранения."
                                        }
                                    }
                                ) {
                                    Text("Лучший (${best.pingMs} ms)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                scanResults.take(6).forEach { res ->
                                    val isCurrent = currentWarpConfig?.endpointHost == res.ip &&
                                            currentWarpConfig?.endpointPort == res.port
                                    Surface(
                                        color = if (isCurrent)
                                            MaterialTheme.colorScheme.primaryContainer
                                        else
                                            MaterialTheme.colorScheme.surface,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                val cfg = currentWarpConfig
                                                if (cfg != null) {
                                                    currentWarpConfig = cfg.copy(
                                                        endpointHost = res.ip,
                                                        endpointPort = res.port
                                                    )
                                                    statusMessage = "Выбран эндпоинт: ${res.ip}:${res.port} (${res.pingMs} ms)"
                                                } else {
                                                    statusMessage = "Выбран эндпоинт: ${res.ip}:${res.port}. Создайте аккаунт."
                                                }
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (isCurrent) {
                                                    Icon(
                                                        imageVector = Icons.Default.CheckCircle,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                }
                                                Text(
                                                    text = "${res.ip}:${res.port}",
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 12.sp,
                                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(
                                                            if (res.isWireguardConfirmed)
                                                                Color(0xFF2E7D32).copy(alpha = 0.15f)
                                                            else
                                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                        )
                                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                                ) {
                                                    Text(
                                                        text = if (res.isWireguardConfirmed) "WG" else "Anycast",
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (res.isWireguardConfirmed)
                                                            Color(0xFF2E7D32)
                                                        else
                                                            MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                            Text(
                                                text = "${res.pingMs} ms",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (res.pingMs < 100) Color(0xFF4CAF50) else Color(0xFFFFA000)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Поле ввода ключа лицензии WARP+
                OutlinedTextField(
                    value = licenseKey,
                    onValueChange = { licenseKey = it.trim() },
                    label = { Text("Лицензионный ключ WARP+ (опционально)") },
                    placeholder = { Text("XXXX-XXXX-XXXX") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.VpnKey,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )

                // Сообщения об ошибках или статусе
                if (isLoading) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = statusMessage ?: "Выполнение операции...",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            TextButton(
                                onClick = {
                                    currentJob?.cancel()
                                    isLoading = false
                                    statusMessage = null
                                    errorMessage = "Операция отменена пользователем"
                                },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Отмена", fontSize = 12.sp)
                            }
                        }
                    }
                }

                if (!errorMessage.isNullOrBlank()) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp).padding(top = 2.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = errorMessage ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }

                if (serverAddedMessage) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Сервер Cloudflare WARP добавлен в список серверов!",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF4CAF50)
                        )
                    }
                }

                // Кнопки управления аккаунтом
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            executeWithVpnCheck {
                                currentJob = scope.launch {
                                    isLoading = true
                                    statusMessage = "Регистрация нового аккаунта..."
                                    errorMessage = null
                                    val res = WarpManager.register(licenseKey.ifBlank { null })
                                    if (res.isSuccess) {
                                        val cfg = res.getOrNull()
                                        currentWarpConfig = cfg
                                        if (licenseKey.isNotBlank() && cfg?.accountType != "warp_plus") {
                                            statusMessage = "Аккаунт создан (бесплатный). Ключ не привязался (подробнее см. в Логах)."
                                        } else {
                                            statusMessage = "Аккаунт успешно создан!"
                                        }
                                    } else {
                                        errorMessage = res.exceptionOrNull()?.message ?: "Ошибка регистрации"
                                    }
                                    isLoading = false
                                }
                            }
                        },
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (currentWarpConfig == null) "Создать аккаунт" else "Обновить аккаунт",
                            fontSize = 12.sp
                        )
                    }

                    if (licenseKey.isNotBlank() && currentWarpConfig != null) {
                        OutlinedButton(
                            onClick = {
                                val cfg = currentWarpConfig ?: return@OutlinedButton
                                executeWithVpnCheck {
                                    currentJob = scope.launch {
                                        isLoading = true
                                        statusMessage = "Привязка ключа WARP+..."
                                        errorMessage = null
                                        val res = WarpManager.bindLicense(cfg.accountId, cfg.accessToken, licenseKey.trim())
                                        if (res.isSuccess) {
                                            currentWarpConfig = cfg.copy(
                                                accountType = res.getOrNull() ?: "warp_plus",
                                                licenseKey = licenseKey.trim()
                                            )
                                            statusMessage = "Ключ WARP+ успешно активирован!"
                                        } else {
                                            errorMessage = res.exceptionOrNull()?.message ?: "Ошибка активации ключа"
                                        }
                                        isLoading = false
                                    }
                                }
                            },
                            enabled = !isLoading,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Привязать ключ", fontSize = 12.sp)
                        }
                    }
                }

                // Кнопка импорта готового конфига WireGuard / WARP
                OutlinedButton(
                    onClick = { showImportDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Импортировать конфиг WireGuard / WARP", fontSize = 13.sp)
                }

                // Кнопка добавления WARP в список одиночных серверов
                if (currentWarpConfig != null) {
                    OutlinedButton(
                        onClick = {
                            currentWarpConfig?.let { cfg ->
                                val cfgWithMode = cfg.copy(warpMode = selectedWarpMode)
                                val proxyServer = WarpManager.toProxyServerConfig(cfgWithMode)
                                onAddServer(proxyServer)
                                serverAddedMessage = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddCircleOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Добавить WARP как отдельный сервер", fontSize = 13.sp)
                    }

                    Text(
                        text = "ℹ️ В РФ прямые IP-адреса Cloudflare (162.159.192.x) блокируются ТСПУ. При выборе этого сервера FlowVPN автоматически направит трафик через ваш рабочий VPN (цепочка), либо включите переключатель «Цепочка: VPN ➔ WARP» выше.",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSaveSettings(
                        enableChaining,
                        licenseKey,
                        currentWarpConfig?.copy(warpMode = selectedWarpMode),
                        selectedWarpMode
                    )
                    onDismiss()
                },
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Применить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )

    // Всплывающий диалог вставки WireGuard / WARP конфига
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Импорт WireGuard / WARP конфига", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Вставьте конфигурацию WireGuard ([Interface] ... [Peer] ...) или JSON из бота / wgcf:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = importText,
                        onValueChange = {
                            importText = it
                            importError = null
                        },
                        placeholder = {
                            Text(
                                text = "[Interface]\nPrivateKey = ...\nAddress = 172.16.0.2/32\n\n[Peer]\nPublicKey = ...\nEndpoint = 162.159.192.1:2408",
                                fontSize = 11.sp
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        shape = RoundedCornerShape(8.dp),
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    )
                    if (!importError.isNullOrBlank()) {
                        Text(
                            text = importError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = WarpConfig.fromWireGuardText(importText)
                        if (parsed != null) {
                            currentWarpConfig = parsed
                            enableChaining = true
                            statusMessage = "Конфигурация успешно импортирована!"
                            errorMessage = null
                            showImportDialog = false
                            importText = ""
                        } else {
                            importError = "Не удалось распознать конфиг. Убедитесь, что он содержит PrivateKey и Endpoint/PublicKey."
                        }
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Импортировать")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    // Всплывающий диалог-предупреждение при отключенном VPN для пользователей из РФ
    if (showVpnWarningDialog) {
        AlertDialog(
            onDismissRequest = {
                showVpnWarningDialog = false
                pendingAction = null
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("VPN не подключен", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    text = "В России серверы Cloudflare API (api.cloudflareclient.com) заблокированы ТСПУ провайдеров.\n\n" +
                            "Прямой запрос без VPN гарантированно вызовет ошибку таймаута (Read timed out).\n\n" +
                            "👉 Рекомендуется нажать «Понятно», подключить любой рабочий VPN-сервер на Главном экране, а затем вернуться сюда и нажать кнопку снова — запрос пойдет через защищенный туннель!\n\n" +
                            "Попробовать выполнить запрос напрямую?",
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showVpnWarningDialog = false
                        pendingAction?.invoke()
                        pendingAction = null
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Попробовать напрямую")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showVpnWarningDialog = false
                        pendingAction = null
                    }
                ) {
                    Text("Понятно, включу VPN")
                }
            }
        )
    }
}

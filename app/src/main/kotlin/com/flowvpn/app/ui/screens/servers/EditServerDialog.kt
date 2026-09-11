package com.flowvpn.app.ui.screens.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowvpn.core.model.ProxyProtocol
import com.flowvpn.core.model.ProxyServerConfig
import com.flowvpn.core.model.WarpConfig
import com.flowvpn.core.warp.WarpScanResult
import com.flowvpn.core.warp.WarpScanner
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Диалог редактирования параметров сервера:
 * - Вкладка «Параметры»: визуальные поля ввода (Имя, Хост, Порт, Ключи, SNI, Префикс).
 * - Вкладка «JSON»: экспертный редактор полного конфига JSON.
 * - Для WireGuard/WARP: встроенный сканер чистых Anycast-эндпоинтов Cloudflare.
 */
@Composable
fun EditServerDialog(
    server: ProxyServerConfig,
    onDismiss: () -> Unit,
    onSave: (ProxyServerConfig) -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // ─── Визуальные поля ───
    var name by remember { mutableStateOf(server.name) }
    var address by remember { mutableStateOf(server.address) }
    var portText by remember { mutableStateOf(server.port.toString()) }

    // Специфика WireGuard / WARP
    var privateKey by remember { mutableStateOf(server.privateKey ?: "") }
    var peerPublicKey by remember { mutableStateOf(server.peerPublicKey ?: "") }
    var localAddressesText by remember { mutableStateOf(server.localAddresses?.joinToString(", ") ?: "") }
    var reservedText by remember { mutableStateOf(server.reserved?.joinToString(", ") ?: "") }
    var mtuText by remember { mutableStateOf(server.wireguardMtu?.toString() ?: "") }

    // Специфика Shadowsocks / Outline
    var password by remember { mutableStateOf(server.password ?: "") }
    var method by remember { mutableStateOf(server.method ?: "") }
    var prefix by remember { mutableStateOf(server.prefix ?: "") }

    // Специфика VLESS / VMess
    var uuid by remember { mutableStateOf(server.uuid ?: "") }
    var flow by remember { mutableStateOf(server.flow ?: "") }
    var sni by remember { mutableStateOf(server.tls?.serverName ?: "") }
    var realityPubKey by remember { mutableStateOf(server.tls?.realityPublicKey ?: "") }
    var realityShortId by remember { mutableStateOf(server.tls?.realityShortId ?: "") }

    // ─── Сканер эндпоинтов WARP ───
    val isWarpOrWg = server.protocol == ProxyProtocol.WIREGUARD ||
            server.id.startsWith("warp-") ||
            server.name.contains("WARP", ignoreCase = true)

    var isScanning by remember { mutableStateOf(false) }
    var scanCompleted by remember { mutableStateOf(false) }
    var endpointSelectedMessage by remember { mutableStateOf<String?>(null) }
    var scanProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var scanResults by remember { mutableStateOf<List<WarpScanResult>>(emptyList()) }

    // ─── JSON редактор ───
    var jsonText by remember {
        mutableStateOf(
            try {
                server.toJson().toString(2)
            } catch (_: Exception) {
                "{}"
            }
        )
    }
    var jsonError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Редактирование сервера",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Параметры", fontSize = 13.sp) },
                        icon = { Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = {
                            // Синхронизируем текущие поля в JSON при переходе
                            try {
                                val currentObj = server.copy(
                                    name = name.trim(),
                                    address = address.trim(),
                                    port = portText.toIntOrNull() ?: server.port,
                                    password = password.takeIf { it.isNotBlank() },
                                    method = method.takeIf { it.isNotBlank() },
                                    prefix = prefix.takeIf { it.isNotBlank() },
                                    uuid = uuid.takeIf { it.isNotBlank() },
                                    flow = flow.takeIf { it.isNotBlank() },
                                    privateKey = privateKey.takeIf { it.isNotBlank() },
                                    peerPublicKey = peerPublicKey.takeIf { it.isNotBlank() },
                                    localAddresses = localAddressesText.split(",").map { it.trim() }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() },
                                    reserved = reservedText.split(",").mapNotNull { it.trim().toIntOrNull() }.takeIf { it.isNotEmpty() },
                                    wireguardMtu = mtuText.toIntOrNull(),
                                    tls = if (sni.isNotBlank() || realityPubKey.isNotBlank()) {
                                        (server.tls ?: com.flowvpn.core.model.TlsConfig()).copy(
                                            serverName = sni.takeIf { it.isNotBlank() },
                                            realityPublicKey = realityPubKey.takeIf { it.isNotBlank() },
                                            realityShortId = realityShortId.takeIf { it.isNotBlank() }
                                        )
                                    } else server.tls
                                ).toJson().toString(2)
                                jsonText = currentObj
                                jsonError = null
                            } catch (_: Exception) {}
                            selectedTab = 1
                        },
                        text = { Text("JSON", fontSize = 13.sp) },
                        icon = { Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (selectedTab == 0) {
                        // ─── Основные поля ───
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Имя сервера") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = address,
                                onValueChange = { address = it },
                                label = { Text("Хост / IP-адрес") },
                                modifier = Modifier.weight(0.68f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = portText,
                                onValueChange = { portText = it },
                                label = { Text("Порт") },
                                modifier = Modifier.weight(0.32f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true
                            )
                        }

                        // ─── Сканер эндпоинтов для WARP / WireGuard ───
                        if (isWarpOrWg) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Сканер чистых эндпоинтов WARP",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = "Поиск незаблокированных IP и портов Cloudflare",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                val parsedReserved = reservedText.split(",")
                                                    .mapNotNull { it.trim().toIntOrNull() }
                                                val tempWarp = WarpConfig(
                                                    accountId = server.id,
                                                    accessToken = "",
                                                    accountType = "free",
                                                    privateKey = privateKey.ifBlank { "" },
                                                    peerPublicKey = peerPublicKey.ifBlank { "" },
                                                    reserved = parsedReserved
                                                )
                                                isScanning = true
                                                scanCompleted = false
                                                scanResults = emptyList()
                                                endpointSelectedMessage = null
                                                scope.launch {
                                                    val results = WarpScanner.scan(
                                                        warpConfig = tempWarp,
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

                                    scanProgress?.let { (c, t) ->
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "🔍 Проверено: $c из $t эндпоинтов...",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    if (scanCompleted && scanResults.isEmpty()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "⚠️ Не удалось получить ответ. Проверьте интернет или включите VPN.",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }

                                    endpointSelectedMessage?.let { msg ->
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "✅ $msg",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color(0xFF2E7D32)
                                        )
                                    }

                                    if (scanResults.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = "Найдено: ${scanResults.size}. Нажмите, чтобы подставить:",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            scanResults.take(6).forEach { res ->
                                                val isSelected = address == res.ip && portText == res.port.toString()
                                                Surface(
                                                    color = if (isSelected)
                                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                                    else
                                                        MaterialTheme.colorScheme.surface,
                                                    shape = RoundedCornerShape(6.dp),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            address = res.ip
                                                            portText = res.port.toString()
                                                            endpointSelectedMessage = "Подставлен: ${res.ip}:${res.port}"
                                                        }
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Text(
                                                                text = "${res.ip}:${res.port}",
                                                                fontFamily = FontFamily.Monospace,
                                                                fontSize = 11.sp,
                                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
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
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (res.pingMs < 100) Color(0xFF2E7D32) else Color(0xFFFFA000)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // ─── Протокол-специфичные поля ───
                        when (server.protocol) {
                            ProxyProtocol.WIREGUARD -> {
                                OutlinedTextField(
                                    value = privateKey,
                                    onValueChange = { privateKey = it },
                                    label = { Text("Private Key") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = peerPublicKey,
                                    onValueChange = { peerPublicKey = it },
                                    label = { Text("Peer Public Key") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = localAddressesText,
                                    onValueChange = { localAddressesText = it },
                                    label = { Text("Local Addresses (через запятую)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = reservedText,
                                        onValueChange = { reservedText = it },
                                        label = { Text("Reserved (e.g. 0, 0, 0)") },
                                        modifier = Modifier.weight(0.6f),
                                        singleLine = true
                                    )
                                    OutlinedTextField(
                                        value = mtuText,
                                        onValueChange = { mtuText = it },
                                        label = { Text("MTU") },
                                        modifier = Modifier.weight(0.4f),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true
                                    )
                                }
                            }
                            ProxyProtocol.SHADOWSOCKS -> {
                                OutlinedTextField(
                                    value = method,
                                    onValueChange = { method = it },
                                    label = { Text("Метод шифрования (e.g. chacha20-ietf-poly1305)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    label = { Text("Пароль") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = prefix,
                                    onValueChange = { prefix = it },
                                    label = { Text("Outline префикс (DPI bypass)") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            ProxyProtocol.VLESS, ProxyProtocol.VMESS -> {
                                OutlinedTextField(
                                    value = uuid,
                                    onValueChange = { uuid = it },
                                    label = { Text("UUID пользователя") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = flow,
                                    onValueChange = { flow = it },
                                    label = { Text("Flow (e.g. xtls-rprx-vision)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = sni,
                                    onValueChange = { sni = it },
                                    label = { Text("SNI / Server Name") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = realityPubKey,
                                    onValueChange = { realityPubKey = it },
                                    label = { Text("Reality Public Key (если Reality)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = realityShortId,
                                    onValueChange = { realityShortId = it },
                                    label = { Text("Reality Short ID") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }
                            ProxyProtocol.TROJAN -> {
                                OutlinedTextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    label = { Text("Пароль") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = sni,
                                    onValueChange = { sni = it },
                                    label = { Text("SNI / Server Name") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }
                            ProxyProtocol.HYSTERIA2 -> {
                                OutlinedTextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    label = { Text("Пароль") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = sni,
                                    onValueChange = { sni = it },
                                    label = { Text("SNI / Server Name") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }
                            else -> {}
                        }
                    } else {
                        // ─── Вкладка JSON ───
                        Text(
                            text = "Прямое редактирование параметров сервера в JSON формате:",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = jsonText,
                            onValueChange = {
                                jsonText = it
                                jsonError = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        )
                        if (!jsonError.isNullOrBlank()) {
                            Text(
                                text = jsonError ?: "",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (selectedTab == 0) {
                        // Сохранение из визуальных полей
                        val p = portText.toIntOrNull() ?: server.port
                        val updated = server.copy(
                            name = name.trim().ifBlank { server.name },
                            address = address.trim().ifBlank { server.address },
                            port = p,
                            password = password.trim().takeIf { it.isNotBlank() } ?: server.password,
                            method = method.trim().takeIf { it.isNotBlank() } ?: server.method,
                            prefix = prefix.takeIf { it.isNotBlank() } ?: server.prefix,
                            uuid = uuid.trim().takeIf { it.isNotBlank() } ?: server.uuid,
                            flow = flow.trim().takeIf { it.isNotBlank() } ?: server.flow,
                            privateKey = privateKey.trim().takeIf { it.isNotBlank() } ?: server.privateKey,
                            peerPublicKey = peerPublicKey.trim().takeIf { it.isNotBlank() } ?: server.peerPublicKey,
                            localAddresses = localAddressesText.split(",")
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                                .takeIf { it.isNotEmpty() } ?: server.localAddresses,
                            reserved = reservedText.split(",")
                                .mapNotNull { it.trim().toIntOrNull() }
                                .takeIf { it.isNotEmpty() } ?: server.reserved,
                            wireguardMtu = mtuText.toIntOrNull() ?: server.wireguardMtu,
                            tls = if (sni.isNotBlank() || realityPubKey.isNotBlank()) {
                                (server.tls ?: com.flowvpn.core.model.TlsConfig()).copy(
                                    serverName = sni.trim().takeIf { it.isNotBlank() } ?: server.tls?.serverName,
                                    realityPublicKey = realityPubKey.trim().takeIf { it.isNotBlank() } ?: server.tls?.realityPublicKey,
                                    realityShortId = realityShortId.trim().takeIf { it.isNotBlank() } ?: server.tls?.realityShortId
                                )
                            } else server.tls
                        )
                        onSave(updated)
                        onDismiss()
                    } else {
                        // Сохранение из JSON
                        try {
                            val obj = JSONObject(jsonText)
                            // Сохраняем оригинальный id, если он не был изменен
                            if (!obj.has("id")) obj.put("id", server.id)
                            val parsed = ProxyServerConfig.fromJson(obj)
                            onSave(parsed)
                            onDismiss()
                        } catch (e: Exception) {
                            jsonError = "Ошибка синтаксиса JSON: ${e.message}"
                        }
                    }
                }
            ) {
                Icon(Icons.Default.Done, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

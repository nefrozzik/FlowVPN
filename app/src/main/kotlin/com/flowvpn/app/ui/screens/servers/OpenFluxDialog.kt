package com.flowvpn.app.ui.screens.servers

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowvpn.core.model.ProxyProtocol
import com.flowvpn.core.model.ProxyServerConfig
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun OpenFluxDialog(
    onDismiss: () -> Unit,
    onSaveServer: (ProxyServerConfig) -> Unit,
    onCheckSocket: suspend (String, Int) -> Boolean,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // State для вкладки Яндекс Документы
    var yandexDocUrl by remember { mutableStateOf("") }
    var yandexPort by remember { mutableStateOf("10808") }
    var yandexName by remember { mutableStateOf("OpenFlux (Яндекс Документы)") }

    // State для вкладки MAX Messenger
    var maxToken by remember { mutableStateOf("") }
    var maxUid by remember { mutableStateOf("") }
    var maxPort by remember { mutableStateOf("10808") }
    var maxName by remember { mutableStateOf("OpenFlux (MAX Messenger)") }

    // State для вкладки Локальный туннель
    var localHost by remember { mutableStateOf("127.0.0.1") }
    var localPort by remember { mutableStateOf("10808") }
    var localName by remember { mutableStateOf("OpenFlux (Локальный)") }
    var socketTestResult by remember { mutableStateOf<Boolean?>(null) }
    var isTestingSocket by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
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
                        text = "Обход белых списков",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "OpenFlux: туннель через Яндекс и MAX",
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
                    .verticalScroll(rememberScrollState())
            ) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Яндекс", fontSize = 12.sp) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("MAX", fontSize = 12.sp) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("SOCKS5", fontSize = 12.sp) }
                    )
                    Tab(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        text = { Text("Инфо", fontSize = 12.sp) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (selectedTab) {
                    0 -> {
                        // Вкладка Яндекс Документы
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Трафик шифруется и передается через синхронизацию курсора в совместном Яндекс Документе. Работает при глухих блокировках.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = yandexDocUrl,
                            onValueChange = { yandexDocUrl = it },
                            label = { Text("URL документа Яндекс") },
                            placeholder = { Text("https://docs.yandex.ru/docs/view?id=...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = yandexPort,
                            onValueChange = { yandexPort = it },
                            label = { Text("Локальный порт клиента") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = yandexName,
                            onValueChange = { yandexName = it },
                            label = { Text("Название профиля") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    1 -> {
                        // Вкладка MAX Messenger
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.Message,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Трафик туннелируется через WebRTC DataChannel звонка или сессии мессенджера MAX (OneMe / VK).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = maxToken,
                            onValueChange = { maxToken = it },
                            label = { Text("Токен сессии / звонка") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = maxUid,
                            onValueChange = { maxUid = it },
                            label = { Text("User ID / Peer ID") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = maxPort,
                            onValueChange = { maxPort = it },
                            label = { Text("Локальный порт") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = maxName,
                            onValueChange = { maxName = it },
                            label = { Text("Название профиля") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    2 -> {
                        // Вкладка Локальный туннель (проверка и подключение к OpenFlux)
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Router,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Подключение к уже запущенному демону OpenFlux (в Termux, локальной службе или на компьютере в локальной сети).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = localHost,
                            onValueChange = { localHost = it },
                            label = { Text("Хост / IP") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = localPort,
                            onValueChange = { localPort = it },
                            label = { Text("Порт SOCKS5") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = localName,
                            onValueChange = { localName = it },
                            label = { Text("Название") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Кнопка проверки порта
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        isTestingSocket = true
                                        socketTestResult = null
                                        val port = localPort.toIntOrNull() ?: 10808
                                        val ok = onCheckSocket(localHost.trim(), port)
                                        socketTestResult = ok
                                        isTestingSocket = false
                                    }
                                },
                                enabled = !isTestingSocket
                            ) {
                                if (isTestingSocket) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Проверка...", fontSize = 12.sp)
                                } else {
                                    Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Проверить туннель", fontSize = 12.sp)
                                }
                            }

                            socketTestResult?.let { ok ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.Error,
                                        contentDescription = null,
                                        tint = if (ok) Color(0xFF388E3C) else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (ok) "Порт открыт" else "Не отвечает",
                                        color = if (ok) Color(0xFF388E3C) else MaterialTheme.colorScheme.error,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    3 -> {
                        // Вкладка Инструкция
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Что такое OpenFlux и белые списки?",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "Во время жестких блокировок или режима «белых списков» операторы связи закрывают доступ ко всем зарубежным IP-адресам и блокируют протоколы VPN. При этом внутри РФ продолжают работать одобренные сервисы: Яндекс Документы, VK и MAX Messenger.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Text(
                                text = "Как это работает:",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "1. На зарубежном сервере (VPS) запускается выходная нода OpenFlux (openflux --mode exit).\n" +
                                        "2. На клиенте запускается клиент OpenFlux, подключающийся к совместному документу Яндекса или звонку MAX.\n" +
                                        "3. FlowVPN перенаправляет весь трафик телефона в туннель, автоматически исключая Яндекс и MAX из VPN, чтобы не создавать петлю.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Text(
                                text = "Исходный код репозитория OpenFlux:\ngithub.com/p1neappleXpress/OpenFlux",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (selectedTab in 0..2) {
                Button(
                    onClick = {
                        val server = when (selectedTab) {
                            0 -> ProxyServerConfig(
                                id = UUID.randomUUID().toString(),
                                name = yandexName.ifBlank { "OpenFlux (Яндекс Документы)" },
                                protocol = ProxyProtocol.OPENFLUX,
                                address = "127.0.0.1",
                                port = yandexPort.toIntOrNull() ?: 10808,
                                openfluxTransport = "yandex",
                                openfluxDocUrl = yandexDocUrl.takeIf { it.isNotBlank() },
                                country = "RU"
                            )
                            1 -> ProxyServerConfig(
                                id = UUID.randomUUID().toString(),
                                name = maxName.ifBlank { "OpenFlux (MAX Messenger)" },
                                protocol = ProxyProtocol.OPENFLUX,
                                address = "127.0.0.1",
                                port = maxPort.toIntOrNull() ?: 10808,
                                openfluxTransport = "max",
                                openfluxToken = maxToken.takeIf { it.isNotBlank() },
                                openfluxUid = maxUid.takeIf { it.isNotBlank() },
                                country = "RU"
                            )
                            else -> ProxyServerConfig(
                                id = UUID.randomUUID().toString(),
                                name = localName.ifBlank { "OpenFlux (Локальный)" },
                                protocol = ProxyProtocol.OPENFLUX,
                                address = localHost.ifBlank { "127.0.0.1" },
                                port = localPort.toIntOrNull() ?: 10808,
                                openfluxTransport = "yandex",
                                country = "RU"
                            )
                        }
                        onSaveServer(server)
                        onDismiss()
                    }
                ) {
                    Text("Создать профиль")
                }
            } else {
                Button(onClick = onDismiss) {
                    Text("Понятно")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )
}

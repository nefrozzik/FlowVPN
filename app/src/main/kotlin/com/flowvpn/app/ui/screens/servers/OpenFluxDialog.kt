package com.flowvpn.app.ui.screens.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.ScrollableTabRow
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
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
    var instructionSubTab by remember { mutableIntStateOf(0) }
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
                    .heightIn(max = 540.dp)
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
                        text = { Text("Инструкция", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

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

                        Spacer(modifier = Modifier.height(10.dp))

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Документ должен быть в СТАРОМ редакторе (выключите тумблер «Перейти на новый редактор») и доступен для совместного редактирования.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = yandexDocUrl,
                            onValueChange = { yandexDocUrl = it },
                            label = { Text("URL документа Яндекс") },
                            placeholder = { Text("https://disk.yandex.ru/edit/d/...") },
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

                        Spacer(modifier = Modifier.height(6.dp))

                        TextButton(
                            onClick = {
                                selectedTab = 3
                                instructionSubTab = 1
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Icon(Icons.Default.HelpOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Инструкция по настройке Яндекс", fontSize = 12.sp)
                        }
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
                                    imageVector = Icons.AutoMirrored.Filled.Message,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Трафик туннелируется через WebRTC DataChannel голосового вызова в мессенджере MAX (OneMe / VK).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Нужны 2 аккаунта MAX (web.max.ru): сервер ждёт звонка, клиент совершает вызов и гонит трафик по WebRTC.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = maxToken,
                            onValueChange = { maxToken = it },
                            label = { Text("Токен клиента (web.max.ru)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = maxUid,
                            onValueChange = { maxUid = it },
                            label = { Text("ID сервера (User ID ноды)") },
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

                        Spacer(modifier = Modifier.height(6.dp))

                        TextButton(
                            onClick = {
                                selectedTab = 3
                                instructionSubTab = 2
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Icon(Icons.Default.HelpOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Инструкция по настройке MAX", fontSize = 12.sp)
                        }
                    }

                    2 -> {
                        // Вкладка SOCKS5
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
                                    text = "Подключение к уже запущенному клиенту OpenFlux (в Termux на телефоне или на домашнем ПК в Wi-Fi сети).",
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
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Под-вкладки инструкции
                            ScrollableTabRow(
                                selectedTabIndex = instructionSubTab,
                                edgePadding = 0.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Tab(
                                    selected = instructionSubTab == 0,
                                    onClick = { instructionSubTab = 0 },
                                    text = { Text("Принцип", fontSize = 11.sp) }
                                )
                                Tab(
                                    selected = instructionSubTab == 1,
                                    onClick = { instructionSubTab = 1 },
                                    text = { Text("Яндекс", fontSize = 11.sp) }
                                )
                                Tab(
                                    selected = instructionSubTab == 2,
                                    onClick = { instructionSubTab = 2 },
                                    text = { Text("MAX", fontSize = 11.sp) }
                                )
                                Tab(
                                    selected = instructionSubTab == 3,
                                    onClick = { instructionSubTab = 3 },
                                    text = { Text("Клиент", fontSize = 11.sp) }
                                )
                            }

                            when (instructionSubTab) {
                                0 -> {
                                    // Принцип работы
                                    Text(
                                        text = "Как устроен обход через белые списки?",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        text = "Во время жестких ограничений операторы блокируют протоколы VPN и доступ за пределы РФ, но оставляют доступными одобренные российские сервисы (белый список: Яндекс, MAX, VK).\n\n" +
                                                "OpenFlux не обращается к запрещённым сайтам напрямую. Вместо этого он превращает Яндекс Документ или звонок MAX в «зашифрованную рацию» между телефоном и вашим сервером за рубежом.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text(
                                                text = "Цепочка движения пакетов:",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "1. Телефон (FlowVPN) ➔ отправляет трафик клиенту OpenFlux\n" +
                                                        "2. Клиент прячет пакеты в курсор документа Яндекса или звонок MAX\n" +
                                                        "3. Зарубежный VPS (Exit Node) в реальном времени считывает их\n" +
                                                        "4. Сервер обращается к YouTube/Google и возвращает ответ обратно в документ!",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }

                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Warning,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Без запущенного зарубежного VPS-сервера туннель работать не будет, потому что сам по себе Яндекс в интернет трафик не выпускает.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                    }
                                }

                                1 -> {
                                    // Яндекс Документы
                                    Text(
                                        text = "Настройка через Яндекс Документы",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        text = "1. Зайдите на Яндекс Диск и создайте новый текстовый документ .docx.\n" +
                                                "2. Нажмите «Поделиться» и включите: «Редактировать могут все, у кого есть ссылка».\n" +
                                                "3. Откройте документ в браузере. В правом верхнем углу найдите тумблер «Перейти на новый редактор» и ВЫКЛЮЧИТЕ его (OpenFlux работает только со старым OnlyOffice).\n" +
                                                "4. Скопируйте ссылку редактирования (вида https://disk.yandex.ru/edit/d/...).\n" +
                                                "5. На вашем VPS за границей выполните команды:",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    CodeSnippetCard(
                                        title = "Установка OpenFlux на сервере (Ubuntu/Debian):",
                                        code = "sudo apt update && sudo apt install -y git golang-go iptables\n" +
                                                "git clone https://github.com/p1neappleXpress/OpenFlux.git\n" +
                                                "cd OpenFlux && go mod tidy && go build -o openflux ."
                                    )

                                    CodeSnippetCard(
                                        title = "Запуск Exit Node на сервере:",
                                        code = "sudo iptables -A OUTPUT -p tcp --tcp-flags RST RST -j DROP\n" +
                                                "sudo ./openflux --exit-node --url \"ВАША_ССЫЛКА_НА_ДОКУМЕНТ\" --debug"
                                    )

                                    Text(
                                        text = "6. Вставьте ссылку документа на вкладке «Яндекс» в приложении и создайте профиль.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                2 -> {
                                    // MAX Messenger
                                    Text(
                                        text = "Настройка через MAX Messenger (OneMe)",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        text = "Трафик передаётся через P2P DataChannel WebRTC-звонка в мессенджере MAX, который входит в белые списки РФ.\n\n" +
                                                "1. Зарегистрируйте 2 аккаунта на web.max.ru (один для VPS-сервера, второй для клиента).\n" +
                                                "2. В браузере на web.max.ru нажмите F12 (DevTools) ➔ вкладка Application (Хранилище) ➔ LocalStorage, и скопируйте auth token и user id для каждого аккаунта.\n" +
                                                "3. На зарубежном VPS запустите ноду под токеном первого аккаунта:",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    CodeSnippetCard(
                                        title = "Запуск ноды MAX на сервере:",
                                        code = "sudo iptables -A OUTPUT -p tcp --tcp-flags RST RST -j DROP\n" +
                                                "sudo ./openflux --exit-node --transport oneme --maxToken ТОКЕН_СЕРВЕРА"
                                    )

                                    Text(
                                        text = "4. В приложении FlowVPN на вкладке «MAX» укажите токен клиента и ID сервера (User ID ноды).\n" +
                                                "5. При подключении клиент совершает скрытый вызов к серверу и передаёт TCP-пакеты через WebRTC DataChannel.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                3 -> {
                                    // Клиент OpenFlux
                                    Text(
                                        text = "Как запустить клиент OpenFlux на устройстве",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        text = "FlowVPN маршрутизирует трафик телефона в порт 127.0.0.1:10808. Для связи с документом на устройстве должен работать клиент OpenFlux:",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text(
                                                text = "Вариант А: Запуск на ПК (в том же Wi-Fi)",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "На домашнем компьютере запускается:\n" +
                                                        "./openflux --client --url \"ССЫЛКА\" --socks5 0.0.0.0:10808\n" +
                                                        "В приложении на вкладке «SOCKS5» укажите локальный IP вашего компьютера (например, 192.168.1.50).",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }

                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text(
                                                text = "Вариант Б: Запуск в Termux на Android",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "В приложении Termux на телефоне запускается клиентский бинарник:\n" +
                                                        "./openflux --client --url \"ССЫЛКА\" --socks5 127.0.0.1:10808\n" +
                                                        "FlowVPN перехватит весь трафик приложений телефона и пустит в локальный сокет.",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }

                                    CodeSnippetCard(
                                        title = "Команда запуска клиента:",
                                        code = "./openflux --client --url \"ВАША_ССЫЛКА\" --socks5 127.0.0.1:10808 --debug"
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = "Репозиторий проекта: github.com/p1neappleXpress/OpenFlux",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                                fontSize = 11.sp
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

@Composable
private fun CodeSnippetCard(
    title: String,
    code: String,
) {
    val clipboardManager = LocalClipboardManager.current
    var isCopied by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                TextButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(code))
                        isCopied = true
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = if (isCopied) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (isCopied) Color(0xFF388E3C) else MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isCopied) "Скопировано" else "Копировать",
                        fontSize = 11.sp,
                        color = if (isCopied) Color(0xFF388E3C) else MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF1E1E1E))
                    .padding(8.dp)
            ) {
                Text(
                    text = code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFFE0E0E0),
                    lineHeight = 16.sp
                )
            }
        }
    }
}

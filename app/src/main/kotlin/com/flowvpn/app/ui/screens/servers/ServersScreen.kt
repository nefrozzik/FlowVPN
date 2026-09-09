package com.flowvpn.app.ui.screens.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flowvpn.core.model.ProxyProtocol
import com.flowvpn.core.model.ProxyServerConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    onServerSelected: (ProxyServerConfig) -> Unit = {},
    viewModel: ServersViewModel = viewModel(),
) {
    val servers by viewModel.filteredServers.collectAsState()
    val selectedServer by viewModel.selectedServer.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isPinging by viewModel.isPinging.collectAsState()
    var showOpenFluxDialog by remember { mutableStateOf(false) }

    if (showOpenFluxDialog) {
        OpenFluxDialog(
            onDismiss = { showOpenFluxDialog = false },
            onSaveServer = { newServer ->
                viewModel.addOpenFluxServer(newServer, selectImmediately = true)
                onServerSelected(newServer)
            },
            onCheckSocket = { host, port ->
                viewModel.checkLocalSocket(host, port)
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Серверы", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(
                        onClick = { viewModel.pingAllServers() },
                        enabled = !isPinging && servers.isNotEmpty()
                    ) {
                        if (isPinging) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.NetworkCheck, contentDescription = "Тест пинга")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Поле поиска
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text("Поиск по имени, адресу, протоколу...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Очистить")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )

            // Баннер: Обход белых списков (OpenFlux)
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable { showOpenFluxDialog = true }
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Обход белых списков (OpenFlux)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Скрытый туннель через Яндекс Документы и MAX",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (servers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchQuery.isNotBlank()) "Ничего не найдено" else "Список серверов пуст.\nДобавьте подписку во вкладке \"Подписки\"",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(servers, key = { it.id }) { server ->
                        val isSelected = selectedServer?.id == server.id
                        ServerListItem(
                            server = server,
                            isSelected = isSelected,
                            onSelect = {
                                viewModel.selectServer(server)
                                onServerSelected(server)
                            },
                            onPing = { viewModel.pingServer(server) }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ServerListItem(
    server: ProxyServerConfig,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onPing: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Radio indicator
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Main details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ProtocolBadge(protocol = server.protocol)

                    if (server.protocol == ProxyProtocol.OPENFLUX) {
                        BadgeText(
                            text = if (server.openfluxTransport == "max") "MAX" else "ЯНДЕКС",
                            color = Color(0xFFD32F2F)
                        )
                    }

                    server.transport?.let { t ->
                        BadgeText(text = t.type.uppercase())
                    }

                    if (server.tls?.realityPublicKey != null) {
                        BadgeText(text = "Reality", color = Color(0xFF673AB7))
                    }

                    Text(
                        text = "${server.address}:${server.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Latency indicator
            LatencyIndicator(
                latencyMs = server.latencyMs,
                onClick = onPing
            )
        }
    }
}

@Composable
private fun ProtocolBadge(protocol: ProxyProtocol) {
    val (bg, fg) = when (protocol) {
        ProxyProtocol.VLESS -> Color(0xFF1976D2) to Color.White
        ProxyProtocol.VMESS -> Color(0xFF388E3C) to Color.White
        ProxyProtocol.SHADOWSOCKS -> Color(0xFFF57C00) to Color.White
        ProxyProtocol.TROJAN -> Color(0xFFD32F2F) to Color.White
        ProxyProtocol.HYSTERIA2 -> Color(0xFF7B1FA2) to Color.White
        ProxyProtocol.TUIC -> Color(0xFF0097A7) to Color.White
        ProxyProtocol.WIREGUARD -> Color(0xFF880E4F) to Color.White
        ProxyProtocol.SOCKS5 -> Color(0xFF00796B) to Color.White
        ProxyProtocol.HTTP -> Color(0xFF455A64) to Color.White
        ProxyProtocol.OPENFLUX -> Color(0xFF00897B) to Color.White
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = protocol.displayName,
            color = fg,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun BadgeText(text: String, color: Color = MaterialTheme.colorScheme.secondary) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun LatencyIndicator(
    latencyMs: Int?,
    onClick: () -> Unit
) {
    val (text, color) = when {
        latencyMs == null -> "—" to Color.Gray
        latencyMs < 0 -> "Таймаут" to Color(0xFFD32F2F)
        latencyMs < 120 -> "${latencyMs}ms" to Color(0xFF388E3C)
        latencyMs < 250 -> "${latencyMs}ms" to Color(0xFFFBC02D)
        else -> "${latencyMs}ms" to Color(0xFFE64A19)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

package com.flowvpn.app.ui.screens.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flowvpn.app.ui.theme.VpnColors
import com.flowvpn.core.model.VpnState

import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

/**
 * Главный экран приложения FlowVPN.
 *
 * Содержит:
 * - Большую кнопку Connect/Disconnect с анимацией
 * - Индикатор состояния VPN
 * - Информацию о текущем сервере
 * - Кнопку мгновенного обновления конфигурации
 *
 * @param onConnectClick callback при нажатии Connect (передаёт путь к конфигу)
 * @param onDisconnectClick callback при нажатии Disconnect
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onConnectClick: (configPath: String) -> Unit,
    onDisconnectClick: () -> Unit,
    onNavigateToServers: () -> Unit = {},
    viewModel: HomeViewModel = viewModel(),
) {
    val vpnState by viewModel.vpnState.collectAsState()
    val selectedServer by viewModel.selectedServer.collectAsState()
    val resolvedCountry by viewModel.resolvedCountry.collectAsState()
    val isRefreshing by viewModel.isRefreshingConfig.collectAsState()
    val refreshMessage by viewModel.refreshMessage.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(refreshMessage) {
        refreshMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearRefreshMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "FlowVPN",
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refreshConfiguration() },
                        enabled = !isRefreshing,
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Обновить конфигурацию",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // ─── Статус-текст ───
            StatusText(vpnState = vpnState)

            Spacer(modifier = Modifier.height(48.dp))

            // ─── Большая кнопка Connect/Disconnect ───
            ConnectButton(
                vpnState = vpnState,
                onClick = {
                    when (vpnState) {
                        is VpnState.Disconnected, is VpnState.Error -> {
                            val configPath = viewModel.prepareConfig()
                            if (configPath != null) {
                                viewModel.onConnecting()
                                onConnectClick(configPath)
                            }
                        }
                        is VpnState.Connected -> {
                            onDisconnectClick()
                        }
                        is VpnState.Connecting -> {
                            onDisconnectClick()
                        }
                        else -> { /* Игнорируем */ }
                    }
                },
            )

            Spacer(modifier = Modifier.height(48.dp))

            // ─── Карточка текущего сервера ───
            ServerCard(
                serverName = selectedServer?.name ?: "Выберите сервер",
                protocol = selectedServer?.protocol?.displayName ?: "—",
                country = resolvedCountry,
                onClick = onNavigateToServers,
            )
        }
    }
}

/**
 * Текстовый индикатор состояния VPN.
 */
@Composable
private fun StatusText(vpnState: VpnState) {
    val (statusText, statusColor) = when (vpnState) {
        is VpnState.Disconnected -> "Не подключено" to VpnColors.disconnected
        is VpnState.Connecting -> "Подключение..." to VpnColors.connecting
        is VpnState.Connected -> "Подключено" to VpnColors.connected
        is VpnState.Disconnecting -> "Отключение..." to VpnColors.connecting
        is VpnState.Error -> "Ошибка" to VpnColors.error
    }

    val animatedColor by animateColorAsState(
        targetValue = statusColor,
        animationSpec = tween(500),
        label = "statusColor",
    )

    Text(
        text = statusText,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold,
        color = animatedColor,
    )

    // Показываем сообщение об ошибке
    if (vpnState is VpnState.Error) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = vpnState.message,
            style = MaterialTheme.typography.bodyMedium,
            color = VpnColors.error.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }

    // Показываем имя сервера при подключении
    if (vpnState is VpnState.Connected) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = vpnState.serverName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

/**
 * Большая анимированная кнопка подключения.
 *
 * - Disconnected/Error: серая, иконка Power
 * - Connecting: пульсирующая оранжевая анимация
 * - Connected: зелёная, иконка PowerOff
 */
@Composable
private fun ConnectButton(
    vpnState: VpnState,
    onClick: () -> Unit,
) {
    val isConnecting = vpnState is VpnState.Connecting || vpnState is VpnState.Disconnecting
    val isConnected = vpnState is VpnState.Connected

    // Анимация пульсации при подключении
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    val buttonColor by animateColorAsState(
        targetValue = when {
            isConnected -> VpnColors.connected
            isConnecting -> VpnColors.connecting
            vpnState is VpnState.Error -> VpnColors.error
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(400),
        label = "buttonColor",
    )

    val scale = if (isConnecting) pulseScale else 1f

    Box(
        modifier = Modifier
            .size(160.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(buttonColor)
            .clickable(enabled = vpnState !is com.flowvpn.core.model.VpnState.Disconnecting) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isConnected) Icons.Default.PowerOff else Icons.Default.Power,
            contentDescription = if (isConnected) "Disconnect" else "Connect",
            modifier = Modifier.size(64.dp),
            tint = Color.White,
        )
    }
}

/**
 * Карточка с информацией о текущем/выбранном сервере.
 */
@Composable
private fun ServerCard(
    serverName: String,
    protocol: String,
    country: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Текущий сервер (нажмите для смены)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = serverName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    InfoChip(label = "Протокол", value = protocol)
                    InfoChip(label = "Страна", value = country)
                }
            }

            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Сменить сервер",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Мини-чип с информацией (протокол, страна).
 */
@Composable
private fun InfoChip(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            fontSize = 10.sp,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

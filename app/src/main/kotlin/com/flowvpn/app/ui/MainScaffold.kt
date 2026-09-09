package com.flowvpn.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.flowvpn.app.ui.navigation.Screen
import com.flowvpn.app.ui.screens.home.HomeScreen
import com.flowvpn.app.ui.screens.logs.LogsScreen
import com.flowvpn.app.ui.screens.servers.ServersScreen
import com.flowvpn.app.ui.screens.settings.SettingsScreen
import com.flowvpn.app.ui.screens.splittunnel.SplitTunnelingScreen
import com.flowvpn.app.ui.screens.subscriptions.SubscriptionsScreen

/**
 * Основной каркас приложения с навигационной панелью Material 3 (NavigationBar).
 */
@Composable
fun MainScaffold(
    onConnectClick: (configPath: String) -> Unit,
    onDisconnectClick: () -> Unit,
) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
    var showSplitTunnelingScreen by remember { mutableStateOf(false) }
    var showLogsScreen by remember { mutableStateOf(false) }

    val items = listOf(
        Screen.Home,
        Screen.Servers,
        Screen.Subscriptions,
        Screen.Settings,
    )

    val isSubscreenOpen = showSplitTunnelingScreen || showLogsScreen

    androidx.activity.compose.BackHandler(enabled = isSubscreenOpen || currentScreen != Screen.Home) {
        when {
            showSplitTunnelingScreen -> showSplitTunnelingScreen = false
            showLogsScreen -> showLogsScreen = false
            currentScreen != Screen.Home -> currentScreen = Screen.Home
        }
    }

    Scaffold(
        bottomBar = {
            if (!isSubscreenOpen) {
                NavigationBar {
                    items.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.title) },
                            label = { Text(screen.title) },
                            selected = currentScreen == screen,
                            onClick = {
                                showSplitTunnelingScreen = false
                                showLogsScreen = false
                                currentScreen = screen
                            }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                showSplitTunnelingScreen -> {
                    SplitTunnelingScreen(
                        onBackClick = { showSplitTunnelingScreen = false }
                    )
                }
                showLogsScreen -> {
                    LogsScreen(
                        onBackClick = { showLogsScreen = false }
                    )
                }
                else -> {
                    when (currentScreen) {
                        is Screen.Home -> {
                            HomeScreen(
                                onConnectClick = onConnectClick,
                                onDisconnectClick = onDisconnectClick,
                                onNavigateToServers = { currentScreen = Screen.Servers }
                            )
                        }
                        is Screen.Servers -> {
                            ServersScreen(
                                onServerSelected = {
                                    currentScreen = Screen.Home
                                }
                            )
                        }
                        is Screen.Subscriptions -> {
                            SubscriptionsScreen()
                        }
                        is Screen.Settings -> {
                            SettingsScreen(
                                onNavigateToSplitTunneling = {
                                    showSplitTunnelingScreen = true
                                },
                                onNavigateToLogs = {
                                    showLogsScreen = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

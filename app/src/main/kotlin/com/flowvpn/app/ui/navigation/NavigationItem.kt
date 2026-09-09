package com.flowvpn.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Shield
import androidx.compose.ui.graphics.vector.ImageVector

import androidx.compose.material.icons.filled.Settings

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    data object Home : Screen("home", "Главная", Icons.Default.Shield)
    data object Servers : Screen("servers", "Серверы", Icons.Default.Dns)
    data object Subscriptions : Screen("subscriptions", "Подписки", Icons.Default.ListAlt)
    data object Settings : Screen("settings", "Настройки", Icons.Default.Settings)
}

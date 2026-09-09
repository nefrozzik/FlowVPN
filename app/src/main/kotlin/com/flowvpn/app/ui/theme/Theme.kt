package com.flowvpn.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Material Design 3 тема FlowVPN.
 *
 * На Android 12+ (API 31) используется Dynamic Color —
 * цвета адаптируются под обои пользователя.
 * На более ранних версиях — кастомная палитра.
 */

// Кастомные цвета — оттенки синего/фиолетового для VPN-тематики
private val VpnPrimary = Color(0xFF6750A4)
private val VpnSecondary = Color(0xFF625B71)
private val VpnTertiary = Color(0xFF7D5260)
private val VpnConnected = Color(0xFF2E7D32)    // Зелёный — подключено
private val VpnDisconnected = Color(0xFF757575) // Серый — отключено
private val VpnError = Color(0xFFC62828)         // Красный — ошибка

private val LightColorScheme = lightColorScheme(
    primary = VpnPrimary,
    secondary = VpnSecondary,
    tertiary = VpnTertiary,
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    secondary = Color(0xFFCCC2DC),
    tertiary = Color(0xFFEFB8C8),
)

@Composable
fun FlowVpnTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        // Dynamic Color на Android 12+
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

/**
 * Цвета специфичные для VPN-состояний.
 * Используются в UI для индикации статуса подключения.
 */
object VpnColors {
    val connected = VpnConnected
    val disconnected = VpnDisconnected
    val error = VpnError
    val connecting = Color(0xFFFFA726) // Оранжевый — подключение
}

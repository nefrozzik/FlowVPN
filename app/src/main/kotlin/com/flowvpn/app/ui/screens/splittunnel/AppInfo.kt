package com.flowvpn.app.ui.screens.splittunnel

import android.graphics.drawable.Drawable

/**
 * Информация об установленном Android-приложении.
 */
data class AppInfo(
    val name: String,
    val packageName: String,
    val isSystemApp: Boolean,
    val icon: Drawable? = null,
)

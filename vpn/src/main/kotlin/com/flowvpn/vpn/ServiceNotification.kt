package com.flowvpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Управление persistent notification для VPN ForegroundService.
 *
 * Android 8+ (API 26) требует NotificationChannel для всех уведомлений.
 * ForegroundService обязан показывать постоянное уведомление — без него
 * система убьёт сервис через ~5 секунд.
 *
 * ## Состояния уведомления
 *
 * - **CONNECTING**: "Подключение..." с бесконечным progress bar
 * - **CONNECTED**: "VPN подключён" с кнопкой "Отключить"
 * - **DISCONNECTED**: кратковременное, до stopForeground
 */
object ServiceNotification {

    const val NOTIFICATION_ID = 1
    private const val CHANNEL_ID = "flowvpn_vpn_service"
    private const val CHANNEL_NAME = "VPN Service"

    enum class State {
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
    }

    /**
     * Создать или обновить NotificationChannel.
     *
     * Channel создаётся один раз и переиспользуется.
     * Пользователь может изменить настройки канала (звук, вибрацию)
     * в системных настройках — мы не перезаписываем их.
     */
    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)

        // Проверяем, существует ли канал (не создаём повторно)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW, // LOW = без звука и вибрации
        ).apply {
            description = "Статус VPN-соединения"
            setShowBadge(false) // Не показывать badge на иконке приложения
            lockscreenVisibility = Notification.VISIBILITY_SECRET // Скрыть на lockscreen
        }

        manager.createNotificationChannel(channel)
    }

    /**
     * Создать Notification для ForegroundService.
     *
     * @param context контекст сервиса
     * @param state текущее состояние VPN
     * @param serverName имя сервера (для CONNECTED)
     */
    fun createNotification(
        context: Context,
        state: State,
        serverName: String? = null,
    ): Notification {
        ensureChannel(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID).apply {
            setSmallIcon(android.R.drawable.ic_lock_lock) // TODO: заменить на кастомную иконку
            setOngoing(true) // Пользователь не может свайпнуть
            setSilent(true) // Без звука
            setOnlyAlertOnce(true) // Не повторять alert при обновлении

            when (state) {
                State.CONNECTING -> {
                    setContentTitle("FlowVPN")
                    setContentText("Подключение...")
                    setProgress(0, 0, true) // Бесконечный progress
                }

                State.CONNECTED -> {
                    setContentTitle("FlowVPN — Подключено")
                    setContentText(serverName ?: "VPN активен")

                    // Кнопка "Отключить" в notification
                    val stopIntent = Intent(context, FlowVpnService::class.java).apply {
                        action = FlowVpnService.ACTION_STOP
                    }
                    val stopPendingIntent = PendingIntent.getService(
                        context,
                        0,
                        stopIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    addAction(
                        android.R.drawable.ic_media_pause,
                        "Отключить",
                        stopPendingIntent,
                    )
                }

                State.DISCONNECTED -> {
                    setContentTitle("FlowVPN")
                    setContentText("Отключено")
                    setOngoing(false)
                }
            }

            // Нажатие на notification — открыть MainActivity
            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }

            if (launchIntent != null) {
                val contentIntent = PendingIntent.getActivity(
                    context,
                    0,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                setContentIntent(contentIntent)
            }
        }

        return builder.build()
    }

    /**
     * Обновить существующее уведомление без пересоздания ForegroundService.
     */
    fun update(context: Context, notification: Notification) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
}

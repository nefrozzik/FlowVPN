package com.flowvpn.app.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.flowvpn.app.FlowVpnApplication
import com.flowvpn.core.logger.CoreLogManager
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Фоновый Worker для периодического автообновления подписок через WorkManager.
 *
 * Срабатывает по расписанию в фоновом режиме при наличии подключения к сети
 * и достаточном уровне заряда аккумулятора, обновляя списки серверов и квоты трафика.
 */
class SubscriptionUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        Timber.i("SubscriptionUpdateWorker: Запуск фонового обновления подписок")
        CoreLogManager.log("WorkManager: Запуск периодического обновления подписок")

        val app = applicationContext as? FlowVpnApplication ?: return Result.failure()
        val repository = app.container.subscriptionRepository

        return try {
            val updated = repository.updateAllSubscriptions()
            Timber.i("SubscriptionUpdateWorker: Успешно обновлено ${updated.size} подписок")
            CoreLogManager.log("WorkManager: Успешно обновлено ${updated.size} подписок")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "SubscriptionUpdateWorker: Ошибка обновления подписок")
            CoreLogManager.log("WorkManager: Ошибка обновления — ${e.message}")
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}

/**
 * Планировщик фоновых задач обновления подписок.
 */
object SubscriptionUpdateScheduler {

    private const val UNIQUE_WORK_NAME = "flowvpn_subscription_periodic_update"

    /**
     * Запланировать периодическое обновление подписок.
     *
     * @param context контекст приложения
     * @param intervalHours интервал запуска (по умолчанию 12 часов)
     */
    fun schedule(context: Context, intervalHours: Long = 12) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val periodicRequest = PeriodicWorkRequestBuilder<SubscriptionUpdateWorker>(
            intervalHours, TimeUnit.HOURS,
            1, TimeUnit.HOURS // flex window
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest
        )

        Timber.d("Запланировано автообновление подписок каждые $intervalHours ч.")
    }

    /**
     * Отменить автообновление подписок.
     */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        Timber.d("Автообновление подписок отменено")
    }
}

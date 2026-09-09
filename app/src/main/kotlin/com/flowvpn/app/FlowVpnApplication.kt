package com.flowvpn.app

import android.app.Application
import timber.log.Timber

/**
 * Application class — точка входа жизненного цикла приложения.
 *
 * Инициализирует:
 * - Timber для логирования
 * - Рабочие директории для sing-box конфигурации
 */
class FlowVpnApplication : Application() {

    lateinit var container: com.flowvpn.app.di.AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = com.flowvpn.app.di.AppContainer(this)

        // Инициализируем централизованный файловый логгер и перехватчик сбоев
        com.flowvpn.core.logger.AppLogManager.init(this)
        com.flowvpn.core.logger.AppLogManager.isFileLoggingEnabled =
            container.settingsRepository.settings.value.fileLoggingEnabled

        // Timber — логирование (только в debug-сборках)
        if (com.flowvpn.app.BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Создаём директорию для конфигурационных файлов sing-box
        ensureConfigDirectory()

        // Инициализируем GoMobile контекст для нативного ядра Sing-box (Hiddify Core)
        com.flowvpn.vpn.BoxServiceManager.initApp(this)

        // Планируем периодическое фоновое обновление подписок
        com.flowvpn.app.worker.SubscriptionUpdateScheduler.schedule(this)

        Timber.i("FlowVpnApplication: Initialized")
    }

    /**
     * Обеспечить существование директории для config.json.
     *
     * sing-box core читает конфигурацию из файла на диске.
     * Мы используем app-private storage (filesDir), который:
     * - Не требует runtime permissions
     * - Автоматически удаляется при деинсталляции
     * - Недоступен другим приложениям
     */
    private fun ensureConfigDirectory() {
        val configDir = getConfigDirectory()
        if (!configDir.exists()) {
            configDir.mkdirs()
            Timber.d("Создана директория конфигурации: ${configDir.absolutePath}")
        }
    }

    companion object {
        /** Имя поддиректории для sing-box конфигов */
        private const val CONFIG_DIR_NAME = "sing-box"

        /**
         * Получить директорию для конфигурационных файлов.
         */
        fun Application.getConfigDirectory(): java.io.File {
            return java.io.File(filesDir, CONFIG_DIR_NAME)
        }

        /**
         * Получить полный путь к файлу конфигурации.
         */
        fun Application.getConfigFilePath(): String {
            return java.io.File(getConfigDirectory(), "config.json").absolutePath
        }
    }
}

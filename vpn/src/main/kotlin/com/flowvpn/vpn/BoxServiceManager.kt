package com.flowvpn.vpn

import android.content.Context
import com.flowvpn.core.logger.CoreLogManager
import com.flowvpn.core.logger.LogLevel
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.SystemProxyStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * Менеджер ядра Sing-box (Hiddify Core).
 *
 * Управляет нативным рантаймом Go:
 * - Инициализация Go Runtime (GODEBUG, GOGC, redirectStderr)
 * - Запуск [CommandServer]
 * - Передача JSON-конфигурации в [startOrReloadService]
 * - Остановка ядра и освобождение ресурсов
 */
class BoxServiceManager(
    private val vpnService: FlowVpnService,
    private val platformInterface: FlowPlatformInterface,
) {
    companion object {
        @Volatile
        private var isInitialized = false

        /**
         * Удаление поврежденных файлов кэша sing-box (bbolt DB).
         * sing-box использует bbolt для cache.db и clash.db. При внезапном завершении процесса
         * bbolt может повредить метаданные страниц, что приводит к критическому нативному падению
         * (panic: invalid page type: 4: 10) при следующем запуске сервиса.
         * Очистка этих файлов гарантирует стабильный и бессбойный старт.
         */
        fun cleanupCorruptedCacheDatabases(context: Context) {
            try {
                val rootDirs = listOfNotNull(
                    context.filesDir,
                    File(context.filesDir, "sing-box"),
                    context.cacheDir,
                    context.getExternalFilesDir(null),
                    context.noBackupFilesDir
                )
                for (rootDir in rootDirs) {
                    if (!rootDir.exists()) continue
                    rootDir.walkTopDown().maxDepth(3).forEach { file ->
                        if (file.isFile) {
                            val name = file.name.lowercase()
                            if (name.endsWith(".lock") || name.endsWith("-wal") ||
                                name.endsWith("-shm") || name.endsWith("-journal") ||
                                name.contains("cache.db") || name.contains("clash.db")) {
                                try {
                                    val deleted = file.delete()
                                    Timber.d("cleanupCorruptedCacheDatabases: deleted $deleted for ${file.absolutePath}")
                                } catch (e: Throwable) {
                                    Timber.w(e, "cleanupCorruptedCacheDatabases: failed to delete ${file.absolutePath}")
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Timber.w(t, "cleanupCorruptedCacheDatabases: ошибка при очистке кэша")
            }
        }

        /**
         * Ранняя инициализация контекста GoMobile в Application.onCreate.
         * Необходима для корректной работы JNI-моста gomobile.
         */
        @Synchronized
        fun initApp(context: Context) {
            cleanupCorruptedCacheDatabases(context)
            try {
                System.setProperty("GODEBUG", "stacktraceback=2")
                System.setProperty("GOGC", "100")
                go.Seq.setContext(context.applicationContext)
                Timber.i("BoxServiceManager: go.Seq.setContext успешно инициализирован")
            } catch (t: Throwable) {
                Timber.e(t, "BoxServiceManager: Ошибка при установке go.Seq context")
            }
        }

        @Synchronized
        fun initializeOnce(context: Context) {
            cleanupCorruptedCacheDatabases(context)
            if (isInitialized) return
            try {
                System.setProperty("GODEBUG", "stacktraceback=2")
                System.setProperty("GOGC", "100")
                runCatching { go.Seq.setContext(context.applicationContext) }

                val baseDir = context.filesDir
                val workingDir = context.getExternalFilesDir(null) ?: context.cacheDir
                val tempDir = context.cacheDir
                baseDir.mkdirs()
                workingDir.mkdirs()
                tempDir.mkdirs()

                val setupOptions = SetupOptions().apply {
                    this.basePath = baseDir.absolutePath
                    this.workingPath = workingDir.absolutePath
                    this.tempPath = tempDir.absolutePath
                    this.fixAndroidStack = true
                    this.oomKillerEnabled = true
                }
                runCatching {
                    Libbox.setup(setupOptions)
                }.onFailure {
                    Timber.w(it, "Libbox.setup вернул ошибку (может быть штатно)")
                }

                isInitialized = true
                val ver = runCatching { Libbox.version() }.getOrDefault("sing-box")
                CoreLogManager.log("Sing-box Go Runtime инициализирован (версия ядра: $ver)", LogLevel.INFO, tag = "SingBox")
                Timber.i("BoxServiceManager: Libbox Go runtime initialized successfully (ver: $ver)")
            } catch (t: Throwable) {
                Timber.e(t, "Ошибка инициализации Libbox")
                CoreLogManager.log("Ошибка инициализации Libbox: ${t.message}", LogLevel.ERROR, tag = "SingBox")
            }
        }
    }

    private var commandServer: CommandServer? = null
    private var logJob: kotlinx.coroutines.Job? = null
    private var currentConfigPath: String? = null

    fun start(configPath: String) {
        cleanupCorruptedCacheDatabases(vpnService)
        initializeOnce(vpnService)
        DefaultNetworkMonitor.start(vpnService)
        currentConfigPath = configPath

        val configFile = File(configPath)
        if (!configFile.exists()) {
            throw IllegalArgumentException("Конфигурационный файл не найден: $configPath")
        }
        val configContent = configFile.readText()

        val handler = object : CommandServerHandler {
            override fun connectSSHAgent(): Int = -1

            override fun triggerNativeCrash() {}

            override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()

            override fun serviceReload() {
                CoreLogManager.log("Sing-box: перезагрузка сервиса по требованию ядра", LogLevel.INFO, tag = "SingBox")
                vpnService.serviceScope.launch(Dispatchers.IO) {
                    try {
                        val path = currentConfigPath ?: return@launch
                        val file = File(path)
                        if (file.exists()) {
                            val options = OverrideOptions()
                            commandServer?.startOrReloadService(file.readText(), options)
                            CoreLogManager.log("Sing-box: сервис успешно перезагружен", LogLevel.INFO, tag = "SingBox")
                        }
                    } catch (t: Throwable) {
                        Timber.w(t, "serviceReload error")
                    }
                }
            }

            override fun serviceStop() {
                CoreLogManager.log("Sing-box: остановка сервиса по сигналу ядра", LogLevel.INFO, tag = "SingBox")
                vpnService.serviceScope.launch(Dispatchers.Main) {
                    vpnService.stopVpn()
                }
            }

            override fun setSystemProxyEnabled(enabled: Boolean) {}

            override fun writeDebugMessage(message: String) {
                CoreLogManager.log(message, LogLevel.DEBUG, tag = "SingBox")
                Timber.d("SingBoxCore: $message")
            }
        }

        // Если предыдущий сервер работал — корректно освобождаем
        commandServer?.let { prev ->
            try {
                prev.closeService()
                prev.close()
            } catch (_: Throwable) {}
            commandServer = null
        }

        val server = Libbox.newCommandServer(handler, platformInterface)
        commandServer = server
        try {
            server.start()
            CoreLogManager.log("Запуск службы Sing-box с VLESS Reality/Vision...", LogLevel.INFO, tag = "SingBox")
            val options = OverrideOptions()
            server.startOrReloadService(configContent, options)
        } catch (t: Throwable) {
            Timber.e(t, "BoxServiceManager: Ошибка при запуске CommandServer / сервиса Sing-box")
            stop()
            throw t
        }

        val ver = runCatching { Libbox.version() }.getOrDefault("")
        CoreLogManager.log("Ядро Sing-box успешно запущено $ver. Весь сетевой стек активен.", LogLevel.INFO, tag = "SingBox")

        // Запуск мониторинга singbox_stderr.log с поддержкой UTF-8 для детальных логов
        val workingDir = vpnService.getExternalFilesDir(null) ?: vpnService.cacheDir
        val stderrFile = File(workingDir, "singbox_stderr.log")
        logJob = CoroutineScope(Dispatchers.IO).launch {
            var lastPos = if (stderrFile.exists()) stderrFile.length() else 0L
            while (isActive) {
                if (stderrFile.exists()) {
                    val currentLen = stderrFile.length()
                    if (currentLen > lastPos) {
                        try {
                            java.io.RandomAccessFile(stderrFile, "r").use { raf ->
                                val toRead = (currentLen - lastPos).toInt()
                                if (toRead > 0) {
                                    val bytes = ByteArray(toRead)
                                    raf.seek(lastPos)
                                    raf.readFully(bytes)
                                    val chunk = String(bytes, Charsets.UTF_8)
                                    chunk.lines().forEach { line ->
                                        val text = line.trim()
                                        if (text.isNotEmpty()) {
                                            val level = when {
                                                text.contains("error", ignoreCase = true) || text.contains("fatal", ignoreCase = true) -> LogLevel.ERROR
                                                text.contains("warn", ignoreCase = true) -> LogLevel.WARN
                                                text.contains("info", ignoreCase = true) -> LogLevel.INFO
                                                else -> LogLevel.DEBUG
                                            }
                                            CoreLogManager.log(text, level, tag = "SingBox")
                                        }
                                    }
                                    lastPos = currentLen
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
                kotlinx.coroutines.delay(300)
            }
        }
    }

    fun stop() {
        logJob?.cancel()
        logJob = null
        try {
            commandServer?.closeService()
            commandServer?.close()
        } catch (e: Exception) {
            Timber.w(e, "Ошибка при закрытии CommandServer")
        } finally {
            commandServer = null
            DefaultNetworkMonitor.stop()
        }
    }
}

package com.flowvpn.vpn

import android.content.Context
import com.flowvpn.core.logger.CoreLogManager
import com.flowvpn.core.logger.LogLevel
import com.hiddify.core.libbox.CommandServer
import com.hiddify.core.libbox.CommandServerHandler
import com.hiddify.core.libbox.Libbox
import com.hiddify.core.libbox.SetupOptions
import com.hiddify.core.libbox.SystemProxyStatus
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
         * Ранняя инициализация контекста GoMobile в Application.onCreate.
         * Необходима для корректной работы JNI-моста gomobile.
         */
        @Synchronized
        fun initApp(context: Context) {
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

                // Редирект stderr ДО вызова любых методов ядра
                val stderrFile = File(workingDir, "singbox_stderr.log")
                runCatching {
                    Libbox.redirectStderr(stderrFile.absolutePath)
                }

                val setupOptions = SetupOptions().apply {
                    this.basePath = baseDir.absolutePath
                    this.workingPath = workingDir.absolutePath
                    this.tempPath = tempDir.absolutePath
                    this.fixAndroidStack = true
                }
                runCatching {
                    Libbox.setup(setupOptions)
                }.onFailure {
                    Timber.w(it, "Libbox.setup вернул ошибку (может быть штатно)")
                }

                runCatching {
                    Libbox.setMemoryLimit(true)
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
        initializeOnce(vpnService)
        DefaultNetworkMonitor.start(vpnService)
        currentConfigPath = configPath

        val configFile = File(configPath)
        if (!configFile.exists()) {
            throw IllegalArgumentException("Конфигурационный файл не найден: $configPath")
        }
        val configContent = configFile.readText()

        val handler = object : CommandServerHandler {
            override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()

            override fun serviceReload() {
                CoreLogManager.log("Sing-box: перезагрузка сервиса по требованию ядра", LogLevel.INFO, tag = "SingBox")
                vpnService.serviceScope.launch(Dispatchers.IO) {
                    try {
                        val path = currentConfigPath ?: return@launch
                        val file = File(path)
                        if (file.exists()) {
                            commandServer?.startOrReloadService(file.readText(), null)
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
        server.start()

        CoreLogManager.log("Запуск службы Sing-box с VLESS Reality/Vision...", LogLevel.INFO, tag = "SingBox")
        server.startOrReloadService(configContent, null)
        commandServer = server

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

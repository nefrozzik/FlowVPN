package com.flowvpn.core.logger

import android.content.Context
import android.os.Build
import android.util.Log
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Централизованный менеджер файлового логирования всего приложения и перехвата фатальных сбоев.
 *
 * Возможности:
 * 1. Запись всех событий приложения, служб и Timber в файл `app_debug.log` при включенной настройке.
 * 2. Автоматическая ротация файлов при превышении 2 МБ.
 * 3. Перехват необработанных исключений через [Thread.setDefaultUncaughtExceptionHandler]
 *    с гарантированным сохранением полного стек-трейса и системной информации в `crash.log`.
 * 4. Консолидация всех источников логов (app, sing-box stderr, crash report) для единого экспорта.
 */
object AppLogManager {

    private const val LOG_DIR_NAME = "logs"
    private const val APP_LOG_FILE_NAME = "app_debug.log"
    private const val OLD_APP_LOG_FILE_NAME = "app_debug.log.old"
    private const val CRASH_LOG_FILE_NAME = "crash.log"
    private const val MAX_LOG_FILE_SIZE_BYTES = 2 * 1024 * 1024L // 2 MB

    @Volatile
    var isFileLoggingEnabled: Boolean = false

    private var appContext: Context? = null
    private var logsDir: File? = null
    private var appLogFile: File? = null
    private var crashLogFile: File? = null

    private val writeExecutor = Executors.newSingleThreadExecutor()
    private val writeLock = Any()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private var defaultExceptionHandler: Thread.UncaughtExceptionHandler? = null
    private var isInitialized = false

    /**
     * Инициализация менеджера логов при старте приложения.
     */
    fun init(context: Context) {
        if (isInitialized) return
        val appCtx = context.applicationContext ?: context
        appContext = appCtx

        val dir = File(appCtx.filesDir, LOG_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        logsDir = dir
        appLogFile = File(dir, APP_LOG_FILE_NAME)
        crashLogFile = File(dir, CRASH_LOG_FILE_NAME)

        setupCrashHandler()
        setupTimberTree()

        isInitialized = true
        log("AppLogManager", LogLevel.INFO, "AppLogManager initialized. Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
    }

    /**
     * Запись строки в лог приложения.
     */
    fun log(tag: String, level: LogLevel, message: String) {
        if (!isFileLoggingEnabled && level != LogLevel.ERROR) {
            return
        }

        val timestamp = synchronized(dateFormat) {
            dateFormat.format(Date())
        }
        val formattedLine = "[$timestamp] [${level.name}] [$tag]: $message\n"

        writeExecutor.execute {
            writeLogLine(formattedLine)
        }
    }

    private fun writeLogLine(line: String) {
        val file = appLogFile ?: return
        synchronized(writeLock) {
            try {
                if (file.exists() && file.length() > MAX_LOG_FILE_SIZE_BYTES) {
                    val oldFile = File(file.parentFile, OLD_APP_LOG_FILE_NAME)
                    if (oldFile.exists()) {
                        oldFile.delete()
                    }
                    file.renameTo(oldFile)
                }
                FileWriter(file, true).use { writer ->
                    writer.write(line)
                }
            } catch (t: Throwable) {
                Log.e("AppLogManager", "Failed to write log line", t)
            }
        }
    }

    /**
     * Установка перехватчика фатальных сбоев (UncaughtExceptionHandler).
     */
    private fun setupCrashHandler() {
        defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                handleCrash(thread, throwable)
            } catch (t: Throwable) {
                Log.e("AppLogManager", "Error inside crash handler", t)
            } finally {
                // Передаем управление стандартному системному обработчику
                defaultExceptionHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    /**
     * Синхронная обработка и сохранение краша перед завершением процесса.
     */
    private fun handleCrash(thread: Thread, throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTrace = sw.toString()

        val recentLogs = try {
            CoreLogManager.logs.value.takeLast(30).joinToString("\n") {
                "[${it.formattedTime}] [${it.level.name}] [${it.tag}]: ${it.message}"
            }
        } catch (_: Throwable) {
            "No recent in-memory logs available"
        }

        val report = buildString {
            appendLine("==================== FLOWVPN CRASH REPORT ====================")
            appendLine("Time: $timestamp")
            appendLine("Thread: ${thread.name} (id=${thread.id})")
            appendLine("App Package: ${appContext?.packageName ?: "com.flowvpn.app"}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})")
            appendLine("Android OS: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}, Build ${Build.DISPLAY})")
            appendLine("Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("Logging Enabled at Crash: $isFileLoggingEnabled")
            appendLine()
            appendLine("----------------- FATAL EXCEPTION STACKTRACE -----------------")
            appendLine(stackTrace)
            appendLine("----------------- RECENT IN-MEMORY LOGS ----------------------")
            appendLine(recentLogs)
            appendLine("==============================================================")
        }

        // Сохраняем краш СИНХРОННО, чтобы успеть до завершения процесса
        synchronized(writeLock) {
            try {
                val file = crashLogFile ?: File(appContext?.filesDir, "$LOG_DIR_NAME/$CRASH_LOG_FILE_NAME")
                file.parentFile?.mkdirs()
                file.writeText(report)

                // Также дописываем в основной лог
                val appLog = appLogFile ?: File(appContext?.filesDir, "$LOG_DIR_NAME/$APP_LOG_FILE_NAME")
                FileWriter(appLog, true).use { writer ->
                    writer.write("\n\n$report\n\n")
                }
            } catch (t: Throwable) {
                Log.e("AppLogManager", "Failed to synchronously write crash log", t)
            }
        }
    }

    /**
     * Интеграция с Timber: перехватывает все вызовы Timber.i/d/w/e во всем проекте.
     */
    private fun setupTimberTree() {
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (!isFileLoggingEnabled && priority < Log.ERROR) {
                    return
                }

                val level = when (priority) {
                    Log.VERBOSE, Log.DEBUG -> LogLevel.DEBUG
                    Log.INFO -> LogLevel.INFO
                    Log.WARN -> LogLevel.WARN
                    Log.ERROR, Log.ASSERT -> LogLevel.ERROR
                    else -> LogLevel.INFO
                }

                val fullMessage = if (t != null) {
                    val sw = StringWriter()
                    t.printStackTrace(PrintWriter(sw))
                    "$message\n$sw"
                } else {
                    message
                }

                log(tag ?: "App", level, fullMessage)
            }
        })
    }

    /**
     * Проверить, есть ли сохраненный отчет о падении.
     */
    fun hasCrashReport(): Boolean {
        val file = crashLogFile ?: return false
        return file.exists() && file.length() > 0
    }

    /**
     * Получить текст последнего падения.
     */
    fun getCrashReport(): String? {
        val file = crashLogFile ?: return null
        return if (file.exists() && file.length() > 0) {
            runCatching { file.readText() }.getOrNull()
        } else null
    }

    /**
     * Удалить отчет о падении.
     */
    fun clearCrashReport() {
        crashLogFile?.delete()
    }

    /**
     * Очистить все сохраненные лог-файлы.
     */
    fun clearAllLogs() {
        synchronized(writeLock) {
            runCatching { appLogFile?.delete() }
            runCatching { File(logsDir, OLD_APP_LOG_FILE_NAME).delete() }
            runCatching { crashLogFile?.delete() }
        }
    }

    /**
     * Сформировать единый консолидированный файл диагностического отчета для экспорта.
     */
    fun generateConsolidatedDiagnosticFile(context: Context): File {
        val exportDir = File(context.cacheDir, "exports")
        if (!exportDir.exists()) {
            exportDir.mkdirs()
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val exportFile = File(exportDir, "flowvpn_logs_$timestamp.txt")

        val reportBuilder = StringBuilder()
        reportBuilder.appendLine("================================================================================")
        reportBuilder.appendLine("                     FLOWVPN SYSTEM DIAGNOSTIC REPORT                          ")
        reportBuilder.appendLine("================================================================================")
        reportBuilder.appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        reportBuilder.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})")
        reportBuilder.appendLine("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        reportBuilder.appendLine("Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        reportBuilder.appendLine("File Logging Active: $isFileLoggingEnabled")
        reportBuilder.appendLine("================================================================================")
        reportBuilder.appendLine()

        // 1. Отчет о краше (если есть)
        val crash = getCrashReport()
        if (!crash.isNullOrBlank()) {
            reportBuilder.appendLine("--------------------------------------------------------------------------------")
            reportBuilder.appendLine("                           LATEST CRASH REPORT                                 ")
            reportBuilder.appendLine("--------------------------------------------------------------------------------")
            reportBuilder.appendLine(crash)
            reportBuilder.appendLine()
        }

        // 2. Логи ядра sing-box stderr (Go panic / нативные ошибки)
        val stderrFiles = listOfNotNull(
            context.getExternalFilesDir(null)?.let { File(it, "singbox_stderr.log") },
            File(context.cacheDir, "singbox_stderr.log"),
            File(context.filesDir, "singbox_stderr.log")
        )
        val stderrFile = stderrFiles.firstOrNull { it.exists() && it.length() > 0 }
        if (stderrFile != null) {
            reportBuilder.appendLine("--------------------------------------------------------------------------------")
            reportBuilder.appendLine("                     SING-BOX STDERR LOGS (NATIVE / GO)                        ")
            reportBuilder.appendLine("--------------------------------------------------------------------------------")
            try {
                // Читаем последние 300 КБ stderr
                val maxBytes = 300 * 1024
                val len = stderrFile.length()
                if (len > maxBytes) {
                    java.io.RandomAccessFile(stderrFile, "r").use { raf ->
                        raf.seek(len - maxBytes)
                        val bytes = ByteArray(maxBytes)
                        raf.readFully(bytes)
                        reportBuilder.appendLine(String(bytes, Charsets.UTF_8))
                    }
                } else {
                    reportBuilder.appendLine(stderrFile.readText(Charsets.UTF_8))
                }
            } catch (e: Throwable) {
                reportBuilder.appendLine("Failed to read singbox_stderr.log: ${e.message}")
            }
            reportBuilder.appendLine()
        }

        // 3. Основной лог приложения (из app_debug.log или CoreLogManager)
        reportBuilder.appendLine("--------------------------------------------------------------------------------")
        reportBuilder.appendLine("                         APPLICATION & SERVICE LOGS                             ")
        reportBuilder.appendLine("--------------------------------------------------------------------------------")

        var hasAppLogs = false
        val appLog = appLogFile
        if (appLog != null && appLog.exists() && appLog.length() > 0) {
            try {
                reportBuilder.appendLine(appLog.readText(Charsets.UTF_8))
                hasAppLogs = true
            } catch (e: Throwable) {
                reportBuilder.appendLine("Failed to read app_debug.log: ${e.message}")
            }
        }

        // Если в файле логов не было, добавляем текущие логи из памяти CoreLogManager
        if (!hasAppLogs) {
            val memoryLogs = CoreLogManager.logs.value
            if (memoryLogs.isNotEmpty()) {
                reportBuilder.appendLine("(Logs retrieved from in-memory buffer, since file logging was disabled or empty)")
                memoryLogs.forEach { entry ->
                    reportBuilder.appendLine("[${entry.formattedTime}] [${entry.level.name}] [${entry.tag}]: ${entry.message}")
                }
            } else {
                reportBuilder.appendLine("No application logs recorded.")
            }
        }

        reportBuilder.appendLine("================================================================================")
        reportBuilder.appendLine("                              END OF REPORT                                     ")
        reportBuilder.appendLine("================================================================================")

        exportFile.writeText(reportBuilder.toString())
        return exportFile
    }
}

package com.flowvpn.app.util

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.flowvpn.core.logger.AppLogManager
import timber.log.Timber

/**
 * Утилита для экспорта и отправки логов и отчетов об ошибках через системный диалог Android.
 */
object LogExportHelper {

    /**
     * Сформировать полный диагностический отчет и открыть системный диалог "Поделиться" (Share Sheet).
     */
    fun exportAndShareLogs(context: Context) {
        try {
            val reportFile = AppLogManager.generateConsolidatedDiagnosticFile(context)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                reportFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "FlowVPN Diagnostic Logs & Crash Report")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Отчет о диагностике и логи FlowVPN.\nУстройство: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nAndroid: ${android.os.Build.VERSION.RELEASE}"
                )
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(shareIntent, "Экспорт логов FlowVPN").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(chooser)
            Timber.i("LogExportHelper: Share sheet opened with file ${reportFile.name}")
        } catch (e: Throwable) {
            Timber.e(e, "LogExportHelper: Ошибка при экспорте логов")
            Toast.makeText(context, "Ошибка экспорта логов: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Скопировать весь диагностический отчет в буфер обмена или экспортировать как текст.
     */
    fun getFullReportText(context: Context): String {
        return try {
            val file = AppLogManager.generateConsolidatedDiagnosticFile(context)
            file.readText()
        } catch (e: Throwable) {
            "Ошибка чтения отчета: ${e.message}"
        }
    }
}

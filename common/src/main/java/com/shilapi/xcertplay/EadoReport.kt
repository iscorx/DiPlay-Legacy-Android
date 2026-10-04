package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** No document provider, share target or network connection is required. */
internal object EadoReport {
    fun build(context: Context, inspection: String = ""): String = buildString {
        appendLine("DiPlay Eado Legacy diagnostic report")
        appendLine("Captured: ${Date()}")
        appendLine("Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}")
        appendLine("Hardware: ${Build.MANUFACTURER} ${Build.MODEL}; ${Build.HARDWARE}; ${Build.BOARD}")
        appendLine("Firmware: ${Build.DISPLAY}; kernel: ${System.getProperty("os.version")}")
        appendLine("Session active: ${CarPlayBackgroundSession.active}")
        appendLine("Authentication local load: ${runCatching { DiPlayBootstrap.ensure(context) }.isSuccess}")
        appendLine("USB storage success on the car does not establish iPhone enumeration.")
        appendLine("UNKNOWN means not observed, not failed. No on-car success inferred.")
        appendLine(inspection)
        appendLine(EadoEvents.report())
        appendLine(DisplayDiagnosticSnapshot.report(context))
        for (name in SessionLogFile.REPORT_NAMES) {
            val file = File(context.filesDir, "logs/$name")
            if (file.isFile) {
                appendLine("--- $name (historical; not live stage evidence) ---")
                file.useLines { lines -> lines.forEach { DiagnosticRedactor.redact(it)?.let { safe -> appendLine(safe) } } }
            }
        }
    }

    fun name() = "Eado-DiPlay-Diagnostic-${SimpleDateFormat("yyyy-MM-dd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    fun save(context: Context, report: String): String {
        val name = name()
        val bytes = report.toByteArray(Charsets.UTF_8).size
        val destination = if (Build.VERSION.SDK_INT >= 29) {
            runCatching { DiagnosticExportStore.saveToDownloads(context.contentResolver, name, report).toString() }
                .getOrElse { saveFiles(null, context.getExternalFilesDir(null), context.filesDir, name, report).absolutePath }
        } else {
            @Suppress("DEPRECATION")
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            saveFiles(downloads, context.getExternalFilesDir(null), context.filesDir, name, report).absolutePath
        }
        return "$destination\n$bytes bytes\n${Date()}"
    }

    /** Directory creation/public-storage denial must not prevent private fallback. */
    internal fun saveFiles(publicRoot: File?, externalRoot: File?, privateRoot: File, name: String, report: String): File {
        require(File(name).name == name && name.endsWith(".txt"))
        var last: Exception? = null
        for (root in listOfNotNull(publicRoot, externalRoot, privateRoot).distinct()) {
            try {
                val directory = File(root, "DiPlay")
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create report directory")
                val file = File(directory, name)
                try { file.outputStream().bufferedWriter(Charsets.UTF_8).use { it.write(report) } }
                catch (e: Exception) { file.delete(); throw e }
                return file
            } catch (e: Exception) { last = e }
        }
        throw IOException("All report destinations failed", last)
    }
}

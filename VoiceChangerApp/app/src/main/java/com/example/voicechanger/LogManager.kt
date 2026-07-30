package com.example.voicechanger

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogManager {
    private const val LOG_FILE_NAME = "app_debug.log"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private fun getLogFile(context: Context): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "VoiceChanger")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, LOG_FILE_NAME)
    }

    @Synchronized
    fun log(context: Context, level: String, tag: String, message: String) {
        try {
            val timestamp = dateFormat.format(Date())
            val logLine = "[$timestamp] [$level] [$tag] $message\n"
            val file = getLogFile(context)
            file.appendText(logLine)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun i(context: Context, tag: String, message: String) = log(context, "INFO", tag, message)
    fun d(context: Context, tag: String, message: String) = log(context, "DEBUG", tag, message)
    fun e(context: Context, tag: String, message: String, throwable: Throwable? = null) {
        val fullMsg = if (throwable != null) "$message | Exception: ${throwable.localizedMessage}" else message
        log(context, "ERROR", tag, fullMsg)
    }

    fun getLogContent(context: Context): String {
        return try {
            val file = getLogFile(context)
            if (file.exists()) file.readText() else "No logs recorded yet."
        } catch (e: Exception) {
            "Error reading log file: ${e.localizedMessage}"
        }
    }

    fun clearLogs(context: Context) {
        try {
            val file = getLogFile(context)
            if (file.exists()) file.writeText("")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getLogFileObject(context: Context): File {
        return getLogFile(context)
    }
}

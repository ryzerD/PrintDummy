package co.tekus.printdummy

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Formatter
import java.util.Locale

class PrintLogger(context: Context) {
    private val logDir: String =
        context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.absolutePath + "/print_logs"

    init {
        val logDirectory = File(logDir)
        if (!logDirectory.exists()) {
            logDirectory.mkdirs()
        }
    }

    fun debug(format: String, vararg args: Any) {
        try {
            val date = Date()
            val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
            val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US)

            val logMessage = timeFormat.format(date) + " " + Formatter().format(format, *args)
                .toString() + "\r\n"
            val logFile = "$logDir/${dateFormat.format(date)}.txt"

            addToFile(logMessage, logFile)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun addToFile(message: String, filePath: String) {
        if (message.isEmpty() || filePath.isEmpty()) return

        try {
            val file = File(filePath)
            val parentFile = file.parentFile

            if (!parentFile?.exists()!!) {
                parentFile.mkdirs()
            }

            if (!file.exists()) {
                file.createNewFile()
            }

            val randomAccessFile = RandomAccessFile(file, "rw")
            randomAccessFile.seek(file.length())
            randomAccessFile.write(message.toByteArray())
            randomAccessFile.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
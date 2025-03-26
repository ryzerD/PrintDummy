package co.tekus.printdummy

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Formatter
import java.util.Locale

/**
 * PrintLogger provides file-based logging functionality specifically for printer operations.
 * It creates daily log files in the app's external storage directory to track print activities.
 */
class PrintLogger(context: Context) {
    /**
     * Directory path where log files will be stored.
     * Uses the app's external documents directory plus a "print_logs" subdirectory.
     */
    private val logDir: String =
        context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.absolutePath + "/print_logs"

    /**
     * Initializes the logger by creating the log directory if it doesn't already exist.
     */
    init {
        val logDirectory = File(logDir)
        if (!logDirectory.exists()) {
            logDirectory.mkdirs()
        }
    }

    /**
     * Writes a debug message to the log file with timestamp.
     * Creates a new log file for each day with format YYYYMMDD.txt.
     *
     * @param format String format pattern (similar to printf)
     * @param args Variable arguments to be formatted into the string
     */
    fun debug(format: String, vararg args: Any) {
        try {
            val date = Date()
            // Format for timestamp at beginning of each log entry (HH:mm:ss.SSS)
            val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
            // Format for log filename (YYYYMMDD.txt)
            val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US)

            // Create log entry with timestamp and formatted message
            val logMessage = timeFormat.format(date) + " " + Formatter().format(format, *args)
                .toString() + "\r\n"
            // Construct full path to the log file
            val logFile = "$logDir/${dateFormat.format(date)}.txt"

            // Append the message to the log file
            addToFile(logMessage, logFile)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Appends a message to the specified file.
     * Creates the file and parent directories if they don't exist.
     * Uses RandomAccessFile to append efficiently to the end of the file.
     *
     * @param message The text content to append to the file
     * @param filePath Full path to the log file
     */
    private fun addToFile(message: String, filePath: String) {
        if (message.isEmpty() || filePath.isEmpty()) return

        try {
            val file = File(filePath)
            val parentFile = file.parentFile

            // Create parent directories if they don't exist
            if (!parentFile?.exists()!!) {
                parentFile.mkdirs()
            }

            // Create log file if it doesn't exist
            if (!file.exists()) {
                file.createNewFile()
            }

            // Use RandomAccessFile for efficient appending
            val randomAccessFile = RandomAccessFile(file, "rw")
            // Position pointer at the end of the file
            randomAccessFile.seek(file.length())
            // Write the log message
            randomAccessFile.write(message.toByteArray())
            // Close the file
            randomAccessFile.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
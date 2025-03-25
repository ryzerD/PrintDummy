package co.tekus.printdummy

/**
 * Application constants organized by category
 */
object AppConstants {
    /**
     * USB-related constants
     */
    object Usb {
        // USB Device identification
        const val USB_CLASS_PRINTER = 7
        const val VENDOR_ID_PM801 = 0x0FE6
        const val PRODUCT_ID_PM801 = 0x811E

        // USB Permission handling
        const val ACTION_USB_PERMISSION = "co.tekus.printdummy.USB_PERMISSION"

        // Known printer names
        const val PRINTER_NAME_PRN = "PRN"
        const val PRINTER_NAME_PM801 = "PM-801"
        const val PRINTER_NAME_VIRTUAL_PRN = "Virtual PRN"
    }

    /**
     * ESC/POS printer commands
     */
    object PrinterCommands {
        // Basic printer control
        val CMD_RESET = byteArrayOf(0x1B, 0x40)                   // ESC @ - Reset printer
        val CMD_NORMAL_TEXT = byteArrayOf(0x1B, 0x21, 0x00)       // ESC ! 0 - Normal text
        val CMD_LINE_SPACING = byteArrayOf(0x1B, 0x33, 24)        // ESC 3 n - Set line spacing
        val CMD_CHARSET_PC437 = byteArrayOf(0x1B, 0x74, 0x00)     // ESC t 0 - Character code PC437

        // Line feed and paper cutting
        val CMD_LINE_FEED = byteArrayOf(0x0A)                     // Line feed
        val CMD_MULTIPLE_LINE_FEED = byteArrayOf(0x0A, 0x0A, 0x0A, 0x0A)  // Multiple line feeds
        val CMD_CUT_PAPER = byteArrayOf(0x1D, 0x56, 0x00)         // GS V 0 - Full cut
    }

    /**
     * Timing constants (milliseconds)
     */
    object Timing {
        const val TIMEOUT_COMMAND = 5000
        const val TIMEOUT_RESET = 2000
        const val TIMEOUT_OTHER_COMMANDS = 1000
        const val DELAY_COMMANDS = 300L
        const val DELAY_INIT = 300L
    }

    /**
     * Data constants
     */
    object Data {
        const val CHUNK_SIZE_PM801 = 32
        const val CHUNK_SIZE_GENERIC = 64
    }

    /**
     * Logging tags
     */
    object LogTags {
        const val PRINTER_MANAGER = "PrinterManager"
        const val MAIN_ACTIVITY = "MainActivity"
    }

    /**
     * User-facing messages
     */
    object Messages {
        // Error messages
        const val ERROR_NO_DEVICES = "No hay dispositivos para imprimir"
        const val ERROR_NO_INTERFACE = "Error: No se pudo encontrar una interfaz de impresora"
        const val ERROR_CANNOT_CONNECT = "Error: No se pudo conectar a la impresora"
        const val ERROR_CANNOT_CLAIM = "Error: No se pudo obtener control de la impresora"
        const val ERROR_NO_USB_DEVICES = "No hay dispositivos USB conectados"
        const val ERROR_NO_COMPATIBLE_PRINTER = "No se encontró una impresora compatible"

        // Success messages
        const val MSG_PRINT_SUCCESS = "Impresión completada correctamente"
        const val MSG_PRINT_PARTIAL = "Posible error de impresión"
        const val MSG_PRINT_FAILURE = "Error de impresión"
        const val MSG_PRINT_SENDING = "Enviando a impresora %s..."

        // Debug messages
        const val DEBUG_DEVICES_FOUND = "Found %d USB devices"
        const val DEBUG_NO_DEVICES = "No USB devices found"
        const val DEBUG_PERMISSION_WARNING =
            "Using unprotected broadcast receiver on older Android version"
        const val DEBUG_PRINTER_INIT = "Initializing printer..."
        const val DEBUG_PRINTING_TO = "Attempting to print to: %s"
        const val DEBUG_IS_PM801 = "Is PM-801 printer: %b"
    }

    /**
     * Demo content
     */
    object DemoContent {
        const val RECEIPT_TEMPLATE = """
            Tekus SAS
            ==================

            Test Receipt
            ------------------
            Fecha: %s

            Producto 1        $10.00
            Producto 2        $15.00
            ------------------
            Total:            $25.00

            Gracias por su compra
        """

        const val DATE_FORMAT = "dd/MM/yyyy HH:mm"
    }
}
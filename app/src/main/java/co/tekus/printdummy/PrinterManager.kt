package co.tekus.printdummy

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * PrinterManager handles all USB thermal printer operations including device discovery,
 * printer selection, connection management, and printing functionality.
 * Supports both generic printers and specific PM801 model.
 */
class PrinterManager(context: Context) {
    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    /**
     * Retrieves all connected USB devices.
     * @return List of available USB devices
     */
    fun findAvailablePorts(): List<UsbDevice> {
        return usbManager.deviceList.values.toList()
    }

    /**
     * Identifies a printer device from the list of available USB devices.
     * Search strategy:
     * 1. First by USB_CLASS_PRINTER interface
     * 2. Then by known printer names
     * 3. Finally falls back to the first device if nothing matches
     *
     * @param devices List of USB devices to search
     * @return First compatible printer device or null if none found
     */
    fun findPrinterDevice(devices: List<UsbDevice>): UsbDevice? {
        // First, try to find a device with a printer interface class
        for (device in devices) {
            for (i in 0 until device.interfaceCount) {
                if (device.getInterface(i).interfaceClass == AppConstants.Usb.USB_CLASS_PRINTER) {
                    Log.d(
                        AppConstants.LogTags.PRINTER_MANAGER,
                        "Found printer class device: ${device.deviceName}"
                    )
                    return device
                }
            }
        }

        // Second, try to find a device with a printer name
        for (device in devices) {
            val productName = device.productName ?: ""
            if (productName.contains(AppConstants.Usb.PRINTER_NAME_PRN, ignoreCase = true) ||
                productName.contains(AppConstants.Usb.PRINTER_NAME_PM801, ignoreCase = true) ||
                productName.contains(AppConstants.Usb.PRINTER_NAME_VIRTUAL_PRN, ignoreCase = true)
            ) {
                Log.d(
                    AppConstants.LogTags.PRINTER_MANAGER,
                    "Found printer by name: ${device.deviceName}, ${device.productName}"
                )
                return device
            }
        }

        // Finally, fallback to the first device if available
        return if (devices.isNotEmpty()) devices[0] else null
    }

    /**
     * Sends text to a printer device for printing.
     * Manages the entire print process including:
     * - Finding a suitable printer
     * - Connecting to the printer
     * - Sending initialization commands
     * - Breaking text into appropriate chunks for printing
     * - Notifying completion status
     *
     * @param text The text content to print
     * @param availablePorts List of available USB devices
     * @param onComplete Callback for print completion status (success/failure, message)
     */
    suspend fun printText(
        text: String,
        availablePorts: List<UsbDevice>,
        onComplete: (Boolean, String) -> Unit
    ) {
        if (availablePorts.isEmpty()) {
            handleNoDevicesError(onComplete)
            return
        }

        try {
            val printer = selectPrinter(availablePorts)
            val (usbInterface, endpoint) = findPrinterInterfaceAndEndpoint(printer)
            if (usbInterface == null || endpoint == null) {
                handleNoInterfaceError(onComplete)
                return
            }

            val connection = establishConnection(printer, usbInterface, onComplete) ?: return
            initializePrinter(connection, endpoint)
            val commands = createGenericPrinterCommands(text)
            val (success, message) = sendCommands(connection, usbInterface, endpoint, commands)
            notifyCompletion(success, message, onComplete)
        } catch (e: Exception) {
            handlePrintException(e, onComplete)
        }
    }

    private suspend fun handleNoDevicesError(onComplete: (Boolean, String) -> Unit) {
        Log.e(AppConstants.LogTags.PRINTER_MANAGER, "No devices provided for printing")
        withContext(Dispatchers.Main) {
            onComplete(false, AppConstants.Messages.ERROR_NO_DEVICES)
        }
    }

    private fun selectPrinter(availablePorts: List<UsbDevice>): UsbDevice {
        val printer = findPrinterDevice(availablePorts) ?: availablePorts[0]
        Log.d(
            AppConstants.LogTags.PRINTER_MANAGER,
            String.format(AppConstants.Messages.DEBUG_PRINTING_TO, printer.deviceName)
        )
        debugUsbDevice(printer)
        return printer
    }

    private suspend fun handleNoInterfaceError(onComplete: (Boolean, String) -> Unit) {
        Log.e(AppConstants.LogTags.PRINTER_MANAGER, AppConstants.Messages.ERROR_NO_INTERFACE)
        withContext(Dispatchers.Main) {
            onComplete(false, AppConstants.Messages.ERROR_NO_INTERFACE)
        }
    }

    private suspend fun establishConnection(
        printer: UsbDevice,
        usbInterface: UsbInterface,
        onComplete: (Boolean, String) -> Unit
    ): UsbDeviceConnection? {
        val connection = usbManager.openDevice(printer)
        if (connection == null) {
            handleConnectionError(onComplete)
            return null
        }

        val claimed = connection.claimInterface(usbInterface, true)
        if (!claimed) {
            handleClaimError(connection, onComplete)
            return null
        }

        return connection
    }

    private suspend fun handleConnectionError(onComplete: (Boolean, String) -> Unit) {
        Log.e(AppConstants.LogTags.PRINTER_MANAGER, AppConstants.Messages.ERROR_CANNOT_CONNECT)
        withContext(Dispatchers.Main) {
            onComplete(false, AppConstants.Messages.ERROR_CANNOT_CONNECT)
        }
    }

    private suspend fun handleClaimError(
        connection: UsbDeviceConnection,
        onComplete: (Boolean, String) -> Unit
    ) {
        Log.e(AppConstants.LogTags.PRINTER_MANAGER, AppConstants.Messages.ERROR_CANNOT_CLAIM)
        connection.close()
        withContext(Dispatchers.Main) {
            onComplete(false, AppConstants.Messages.ERROR_CANNOT_CLAIM)
        }
    }

    private suspend fun sendCommands(
        connection: UsbDeviceConnection,
        usbInterface: UsbInterface,
        endpoint: UsbEndpoint,
        commands: List<ByteArray>
    ): Pair<Boolean, String> {
        var successCount = 0
        var failCount = 0

        try {
            for (index in commands.indices) {
                val command = commands[index]
                Log.d(
                    AppConstants.LogTags.PRINTER_MANAGER,
                    "Sending command ${index + 1}/${commands.size}"
                )

                val sent = sendPrinterCommand(
                    connection,
                    endpoint,
                    command,
                    AppConstants.Timing.TIMEOUT_COMMAND
                )

                if (sent < 0) {
                    failCount++
                } else {
                    successCount++
                }

                delay(AppConstants.Timing.DELAY_COMMANDS)
            }
        } finally {
            connection.releaseInterface(usbInterface)
            connection.close()
        }

        val success = failCount == 0
        val message = when {
            success -> AppConstants.Messages.MSG_PRINT_SUCCESS
            successCount > 0 -> AppConstants.Messages.MSG_PRINT_PARTIAL
            else -> AppConstants.Messages.MSG_PRINT_FAILURE
        }

        Log.d(
            AppConstants.LogTags.PRINTER_MANAGER,
            "Print completed with $failCount failures out of ${commands.size} commands"
        )

        return Pair(success, message)
    }

    private suspend fun notifyCompletion(
        success: Boolean,
        message: String,
        onComplete: (Boolean, String) -> Unit
    ) {
        withContext(Dispatchers.Main) {
            onComplete(success, message)
        }
    }

    private suspend fun handlePrintException(e: Exception, onComplete: (Boolean, String) -> Unit) {
        Log.e(AppConstants.LogTags.PRINTER_MANAGER, "Error during printing: ${e.message}", e)
        withContext(Dispatchers.Main) {
            onComplete(false, "Error: ${e.message}")
        }
    }

    /**
     * Initializes the printer with essential setup commands:
     * - Reset printer to clear buffer and set default state
     * - Set line spacing for consistent output
     * - Set character set to PC437 (standard US/European characters)
     *
     * @param connection USB connection to printer
     * @param endpoint Output endpoint for sending commands
     */
    private suspend fun initializePrinter(connection: UsbDeviceConnection, endpoint: UsbEndpoint) {
        try {
            Log.d(AppConstants.LogTags.PRINTER_MANAGER, AppConstants.Messages.DEBUG_PRINTER_INIT)

            // Reset printer to clear buffer and previous settings
            sendPrinterCommand(
                connection, endpoint, AppConstants.PrinterCommands.CMD_RESET,
                AppConstants.Timing.TIMEOUT_RESET
            )
            delay(AppConstants.Timing.DELAY_INIT)

            // Set line spacing for consistent output
            sendPrinterCommand(
                connection, endpoint, AppConstants.PrinterCommands.CMD_LINE_SPACING,
                AppConstants.Timing.TIMEOUT_OTHER_COMMANDS
            )

            // Set character set to PC437 (standard US/European characters)
            sendPrinterCommand(
                connection, endpoint, AppConstants.PrinterCommands.CMD_CHARSET_PC437,
                AppConstants.Timing.TIMEOUT_OTHER_COMMANDS
            )

            // Allow time for printer to process initialization
            delay(AppConstants.Timing.DELAY_INIT)
        } catch (e: Exception) {
            Log.w(
                AppConstants.LogTags.PRINTER_MANAGER,
                "Printer initialization exception: ${e.message}"
            )
        }
    }

    /**
     * Sends a single command to the printer.
     *
     * @param connection USB connection to the printer
     * @param endpoint Endpoint for sending commands
     * @param command Command to send
     * @param timeout Timeout for the operation in milliseconds
     * @return Number of bytes sent or -1 if an error occurs
     */
    private fun sendPrinterCommand(
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        command: ByteArray,
        timeout: Int
    ): Int {
        return connection.bulkTransfer(
            endpoint,
            command,
            command.size,
            timeout
        )
    }

    /**
     * Creates a sequence of printer commands for generic (non-PM801) thermal printers.
     * Includes:
     * - Reset and initialization commands
     * - Text content broken into printer-appropriate chunks
     * - Line feed and paper cut commands
     *
     * @param text Content to print
     * @return List of byte arrays containing printer commands
     */
    private fun createGenericPrinterCommands(text: String): List<ByteArray> {
        val commands = mutableListOf<ByteArray>()

        // Initialize printer with standard commands
        commands.add(AppConstants.PrinterCommands.CMD_RESET)
        commands.add(AppConstants.PrinterCommands.CMD_NORMAL_TEXT)
        commands.add(AppConstants.PrinterCommands.CMD_CHARSET_PC437)

        // Break text into manageable chunks to prevent buffer overflow
        val textBytes = text.toByteArray()
        var position = 0
        while (position < textBytes.size) {
            val remaining = textBytes.size - position
            val size =
                if (remaining > AppConstants.Data.CHUNK_SIZE_GENERIC) AppConstants.Data.CHUNK_SIZE_GENERIC else remaining
            val chunk = textBytes.copyOfRange(position, position + size)
            commands.add(chunk)
            position += size
        }

        // Add finishing commands
        commands.add(AppConstants.PrinterCommands.CMD_MULTIPLE_LINE_FEED)
        commands.add(AppConstants.PrinterCommands.CMD_CUT_PAPER)

        return commands
    }

    /**
     * Locates the appropriate interface and endpoint for printer communication.
     * Search strategy:
     * 1. First by printer class with bulk output endpoint
     * 2. Then any interface with bulk output endpoint
     * 3. Then any interface with any output endpoint
     * 4. Fallback to interface 0, endpoint 0
     *
     * @param device USB device to examine
     * @return Pair of UsbInterface and UsbEndpoint, or null values if none found
     */
    private fun findPrinterInterfaceAndEndpoint(device: UsbDevice): Pair<UsbInterface?, UsbEndpoint?> {
        // Priority 1: Find printer class interface with bulk output endpoint
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)

            if (usbInterface.interfaceClass == AppConstants.Usb.USB_CLASS_PRINTER) {
                Log.d(
                    AppConstants.LogTags.PRINTER_MANAGER,
                    "Found printer class interface at index $i"
                )
                for (j in 0 until usbInterface.endpointCount) {
                    val endpoint = usbInterface.getEndpoint(j)
                    if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && endpoint.direction == UsbConstants.USB_DIR_OUT) {
                        Log.d(
                            AppConstants.LogTags.PRINTER_MANAGER,
                            "Found bulk output endpoint at index $j"
                        )
                        return Pair(usbInterface, endpoint)
                    }
                }
            }
        }

        // Priority 2: Find any interface with bulk output endpoint
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            for (j in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(j)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && endpoint.direction == UsbConstants.USB_DIR_OUT) {
                    Log.d(
                        AppConstants.LogTags.PRINTER_MANAGER,
                        "Found bulk output endpoint at index $j"
                    )
                    return Pair(usbInterface, endpoint)
                }
            }
        }

        // Priority 3: Find any interface with any output endpoint
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            for (j in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(j)
                if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                    Log.d(AppConstants.LogTags.PRINTER_MANAGER, "Found output endpoint at index $j")
                    return Pair(usbInterface, endpoint)
                }
            }
        }

        // Priority 4: Fallback to first interface and endpoint
        if (device.interfaceCount > 0) {
            val usbInterface = device.getInterface(0)
            if (usbInterface.endpointCount > 0) {
                Log.d(
                    AppConstants.LogTags.PRINTER_MANAGER,
                    "Using fallback: interface 0 and endpoint 0"
                )
                return Pair(usbInterface, usbInterface.getEndpoint(0))
            }
        }

        // No suitable interface/endpoint found
        return Pair(null, null)
    }

    /**
     * Logs detailed information about a USB device for debugging purposes.
     * Includes:
     * - Device identifiers and names
     * - Interface details
     * - Endpoint specifications
     *
     * @param device USB device to inspect
     */
    fun debugUsbDevice(device: UsbDevice) {
        Log.d(
            AppConstants.LogTags.PRINTER_MANAGER,
            "USB Device: ${device.deviceName}, Product: ${device.productName ?: "Unknown"}, Vendor ID: 0x${
                device.vendorId.toString(16)
            }, Product ID: 0x${device.productId.toString(16)}"
        )

        for (i in 0 until device.interfaceCount) {
            val `interface` = device.getInterface(i)
            Log.d(
                AppConstants.LogTags.PRINTER_MANAGER,
                "Interface $i: Class ${`interface`.interfaceClass}, Subclass ${`interface`.interfaceSubclass}, Protocol ${`interface`.interfaceProtocol}, Endpoint count: ${`interface`.endpointCount}"
            )

            for (j in 0 until `interface`.endpointCount) {
                val endpoint = `interface`.getEndpoint(j)
                val direction = if (endpoint.direction == UsbConstants.USB_DIR_OUT) "OUT" else "IN"
                val type = when (endpoint.type) {
                    UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                    UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
                    UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
                    UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOCHRONOUS"
                    else -> "UNKNOWN"
                }
                Log.d(
                    AppConstants.LogTags.PRINTER_MANAGER,
                    "  Endpoint $j: Address: ${endpoint.address}, Direction: $direction, Type: $type, Max Packet Size: ${endpoint.maxPacketSize}"
                )
            }
        }
    }
}
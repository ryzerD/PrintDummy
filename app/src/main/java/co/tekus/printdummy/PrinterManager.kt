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
import kotlinx.coroutines.withContext

class PrinterManager(private val context: Context) {
    private val TAG = "PrinterManager"
    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    // Constants for PM-801 printer
    private val VENDOR_ID_PM801 = 0x0FE6  // Updated from logs
    private val PRODUCT_ID_PM801 = 0x811E // Updated from logs

    fun findAvailablePorts(): List<UsbDevice> {
        return usbManager.deviceList.values.toList()
    }

    // Find the printer device in the list
    fun findPrinterDevice(devices: List<UsbDevice>): UsbDevice? {
        // First try to find devices with printer class (7)
        for (device in devices) {
            for (i in 0 until device.interfaceCount) {
                if (device.getInterface(i).interfaceClass == 7) {
                    Log.d(TAG, "Found printer class device: ${device.deviceName}")
                    return device
                }
            }
        }

        // Then try to find Virtual PRN or PM-801 by name
        for (device in devices) {
            val productName = device.productName ?: ""
            if (productName.contains("PRN", ignoreCase = true) ||
                productName.contains("PM-801", ignoreCase = true)
            ) {
                Log.d(TAG, "Found printer by name: ${device.deviceName}, ${device.productName}")
                return device
            }
        }

        // If we still don't have a printer, just return the first device
        return if (devices.isNotEmpty()) devices[0] else null
    }

    suspend fun printText(
        text: String,
        availablePorts: List<UsbDevice>,
        onComplete: (Boolean, String) -> Unit
    ) {
        if (availablePorts.isEmpty()) {
            Log.e(TAG, "No devices provided for printing")
            withContext(Dispatchers.Main) {
                onComplete(false, "No hay dispositivos para imprimir")
            }
            return
        }

        try {
            val printer = findPrinterDevice(availablePorts) ?: availablePorts[0]
            Log.d(TAG, "Attempting to print to: ${printer.deviceName}")
            debugUsbDevice(printer)

            val isPM801 = isPM801Printer(printer)
            Log.d(TAG, "Is PM-801 printer: $isPM801")

            val (usbInterface, endpoint) = findPrinterInterfaceAndEndpoint(printer)
            if (usbInterface == null || endpoint == null) {
                Log.e(TAG, "Could not find suitable interface and endpoint")
                withContext(Dispatchers.Main) {
                    onComplete(false, "Error: No se pudo encontrar una interfaz de impresora")
                }
                return
            }

            Log.d(TAG, "Found interface ${usbInterface.id} and endpoint ${endpoint.address}")

            val connection = usbManager.openDevice(printer)
            if (connection == null) {
                Log.e(TAG, "Could not open connection to device")
                withContext(Dispatchers.Main) {
                    onComplete(false, "Error: No se pudo conectar a la impresora")
                }
                return
            }

            val claimed = connection.claimInterface(usbInterface, true)
            if (!claimed) {
                Log.e(TAG, "Failed to claim interface")
                connection.close()
                withContext(Dispatchers.Main) {
                    onComplete(false, "Error: No se pudo obtener control de la impresora")
                }
                return
            }

            // Initialize the printer with proper delays
            try {
                Log.d(TAG, "Resetting printer...")
                val resetCommand = byteArrayOf(0x1B, 0x40)  // ESC @
                connection.bulkTransfer(endpoint, resetCommand, resetCommand.size, 2000)
                Thread.sleep(500) // Longer delay after reset for virtual printer
            } catch (e: Exception) {
                Log.w(TAG, "Reset exception: ${e.message}, continuing anyway")
            }

            // Choose the appropriate command set
            val commands = if (isPM801) {
                createPM801PrinterCommands(text)
            } else {
                createGenericPrinterCommands(text)
            }

            var successCount = 0
            var failCount = 0

            // Send commands to the printer with longer delays and better error recovery
            for (index in commands.indices) {
                val command = commands[index]
                Log.d(
                    TAG,
                    "Sending command block ${index + 1}/${commands.size}, size: ${command.size} bytes"
                )

                // Increased timeout to 5000ms (5 seconds)
                val sent = connection.bulkTransfer(endpoint, command, command.size, 5000)

                if (sent < 0) {
                    Log.e(TAG, "Failed to send command block ${index + 1}")

                    // Try to recover with a reset and retry
                    if (index == 0) {
                        try {
                            Thread.sleep(500)
                            val resetResult = connection.bulkTransfer(
                                endpoint,
                                byteArrayOf(0x1B, 0x40),
                                2,
                                2000
                            )
                            Log.d(TAG, "Reset attempt result: $resetResult")
                            Thread.sleep(500)

                            // Try again
                            val retryResult =
                                connection.bulkTransfer(endpoint, command, command.size, 5000)
                            if (retryResult >= 0) {
                                Log.d(TAG, "Retry successful, sent $retryResult bytes")
                                successCount++
                                Thread.sleep(300)
                                continue  // Skip the failure count since we succeeded on retry
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Recovery attempt failed: ${e.message}")
                        }
                    }

                    failCount++
                } else {
                    Log.d(TAG, "Successfully sent ${sent} bytes")
                    successCount++
                }

                // Longer delay (300ms) between commands for Virtual PRN
                Thread.sleep(300)
            }

            // Release the interface
            connection.releaseInterface(usbInterface)
            connection.close()

            val success = failCount == 0
            val message = if (success) {
                "Impresión completada correctamente"
            } else if (successCount > 0) {
                "Posible error de impresión"
            } else {
                "Error de impresión"
            }

            Log.d(
                TAG,
                "Print completed with ${failCount} failures out of ${commands.size} commands"
            )

            withContext(Dispatchers.Main) {
                onComplete(success, message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during printing: ${e.message}", e)
            withContext(Dispatchers.Main) {
                onComplete(false, "Error: ${e.message}")
            }
        }
    }

    // Check if this is the PM-801 printer based on device characteristics
    private fun isPM801Printer(device: UsbDevice): Boolean {
        // Match on specific vendor/product IDs or device info from the logs
        if (device.vendorId == VENDOR_ID_PM801 && device.productId == PRODUCT_ID_PM801) {
            return true
        }

        // Name-based fallback detection
        val deviceName = device.deviceName ?: ""
        val productName = device.productName ?: ""

        return deviceName.contains("PM-801", ignoreCase = true) ||
                productName.contains("PM-801", ignoreCase = true) ||
                productName.contains("PRN", ignoreCase = true)
    }

    private fun initializePrinter(connection: UsbDeviceConnection, endpoint: UsbEndpoint) {
        try {
            Log.d(TAG, "Initializing printer...")

            // Reset the printer first
            val resetCommand = byteArrayOf(0x1B, 0x40)  // ESC @
            connection.bulkTransfer(endpoint, resetCommand, resetCommand.size, 2000)

            // Important delay after reset (Virtual PRN requires this)
            Thread.sleep(300)

            // Set the line spacing
            val lineSpacingCommand = byteArrayOf(0x1B, 0x33, 24)  // ESC 3 n
            connection.bulkTransfer(endpoint, lineSpacingCommand, lineSpacingCommand.size, 1000)

            // Set character set
            val charsetCommand = byteArrayOf(0x1B, 0x74, 0)  // ESC t 0 - PC437
            connection.bulkTransfer(endpoint, charsetCommand, charsetCommand.size, 1000)

            Thread.sleep(300)  // Longer delay for Virtual PRN
        } catch (e: Exception) {
            Log.w(TAG, "Printer initialization exception: ${e.message}")
        }
    }

    // Create PM-801 specific printer commands
    private fun createPM801PrinterCommands(text: String): List<ByteArray> {
        val commands = mutableListOf<ByteArray>()

        // Start with these specific init commands for Virtual PRN
        commands.add(byteArrayOf(0x1B, 0x40))  // ESC @ - Reset
        commands.add(byteArrayOf(0x1B, 0x21, 0))  // ESC ! 0 - Normal text

        // Convert text to byte array and split into chunks
        val textBytes = text.toByteArray()
        val CHUNK_SIZE = 32  // Smaller chunks for Virtual PRN

        var position = 0
        while (position < textBytes.size) {
            val remaining = textBytes.size - position
            val size = if (remaining > CHUNK_SIZE) CHUNK_SIZE else remaining
            val chunk = textBytes.copyOfRange(position, position + size)
            commands.add(chunk)
            position += size
        }

        // Add the final feed and cut commands
        commands.add(byteArrayOf(0x0A, 0x0A, 0x0A, 0x0A))  // Line feeds
        commands.add(byteArrayOf(0x1D, 0x56, 0x00))  // GS V 0 - Cut paper

        return commands
    }

    // Create generic ESC/POS commands for other printer models
    private fun createGenericPrinterCommands(text: String): List<ByteArray> {
        val commands = mutableListOf<ByteArray>()

        // Text formatting commands
        val formattingCommands = byteArrayOf(
            0x1B, 0x21, 0x00,         // ESC ! 0 - Normal text
            0x1B, 0x74, 0x00          // ESC t 0 - Character code table: PC437 (USA/Standard Europe)
        )
        commands.add(formattingCommands)

        // Text data in smaller chunks
        val textBytes = text.toByteArray()
        val CHUNK_SIZE = 64  // Smaller chunk size

        var position = 0
        while (position < textBytes.size) {
            val remaining = textBytes.size - position
            val size = if (remaining > CHUNK_SIZE) CHUNK_SIZE else remaining
            val chunk = textBytes.copyOfRange(position, position + size)
            commands.add(chunk)
            position += size
        }

        // Line feeds and cut (generic)
        val finalCommands = byteArrayOf(
            0x0A, 0x0A, 0x0A, 0x0A,   // Line feeds
            0x1D, 0x56, 0x00          // GS V 0 - Full cut
        )
        commands.add(finalCommands)

        return commands
    }

    private fun findPrinterInterfaceAndEndpoint(device: UsbDevice): Pair<UsbInterface?, UsbEndpoint?> {
        // Look through all interfaces
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)

            // Printer class = 7
            if (usbInterface.interfaceClass == 7) {
                Log.d(TAG, "Found printer class interface at index $i")
                // Find a bulk output endpoint
                for (j in 0 until usbInterface.endpointCount) {
                    val endpoint = usbInterface.getEndpoint(j)
                    if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        endpoint.direction == UsbConstants.USB_DIR_OUT
                    ) {
                        Log.d(TAG, "Found bulk output endpoint at index $j")
                        return Pair(usbInterface, endpoint)
                    }
                }
            }
        }

        // Try to find ANY bulk output endpoint if printer class not found
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            for (j in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(j)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    endpoint.direction == UsbConstants.USB_DIR_OUT
                ) {
                    Log.d(TAG, "Found alternative bulk output interface $i and endpoint $j")
                    return Pair(usbInterface, endpoint)
                }
            }
        }

        // Try to find ANY output endpoint if bulk not found
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            for (j in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(j)
                if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                    Log.d(TAG, "Found alternative output interface $i and endpoint $j")
                    return Pair(usbInterface, endpoint)
                }
            }
        }

        // Last resort - just use the first interface and endpoint
        if (device.interfaceCount > 0) {
            val usbInterface = device.getInterface(0)
            if (usbInterface.endpointCount > 0) {
                Log.d(TAG, "Using fallback: interface 0 and endpoint 0")
                return Pair(usbInterface, usbInterface.getEndpoint(0))
            }
        }

        return Pair(null, null)
    }

    fun debugUsbDevice(device: UsbDevice) {
        Log.d(
            TAG,
            "USB Device: ${device.deviceName}, Product: ${device.productName ?: "Unknown"}, " +
                    "Vendor ID: 0x${device.vendorId.toString(16)}, Product ID: 0x${
                        device.productId.toString(
                            16
                        )
                    }"
        )

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            Log.d(
                TAG,
                "Interface $i: Class ${iface.interfaceClass}, Subclass ${iface.interfaceSubclass}, " +
                        "Protocol ${iface.interfaceProtocol}, Endpoint count: ${iface.endpointCount}"
            )

            for (j in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(j)
                val direction = if (endpoint.direction == UsbConstants.USB_DIR_OUT) "OUT" else "IN"
                val type = when (endpoint.type) {
                    UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                    UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
                    UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
                    UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOCHRONOUS"
                    else -> "UNKNOWN"
                }
                Log.d(
                    TAG,
                    "  Endpoint $j: Address: ${endpoint.address}, Direction: $direction, " +
                            "Type: $type, Max Packet Size: ${endpoint.maxPacketSize}"
                )
            }
        }
    }
}
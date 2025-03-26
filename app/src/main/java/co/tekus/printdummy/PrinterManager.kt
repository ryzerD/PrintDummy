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

class PrinterManager(context: Context) {
    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun findAvailablePorts(): List<UsbDevice> {
        return usbManager.deviceList.values.toList()
    }

    fun findPrinterDevice(devices: List<UsbDevice>): UsbDevice? {
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

        return if (devices.isNotEmpty()) devices[0] else null
    }

    suspend fun printText(
        text: String,
        availablePorts: List<UsbDevice>,
        onComplete: (Boolean, String) -> Unit
    ) {
        if (availablePorts.isEmpty()) {
            Log.e(AppConstants.LogTags.PRINTER_MANAGER, "No devices provided for printing")
            withContext(Dispatchers.Main) {
                onComplete(false, AppConstants.Messages.ERROR_NO_DEVICES)
            }
            return
        }

        try {
            val printer = findPrinterDevice(availablePorts) ?: availablePorts[0]
            Log.d(
                AppConstants.LogTags.PRINTER_MANAGER,
                String.format(AppConstants.Messages.DEBUG_PRINTING_TO, printer.deviceName)
            )
            debugUsbDevice(printer)

            val isPM801 = isPM801Printer(printer)
            Log.d(
                AppConstants.LogTags.PRINTER_MANAGER,
                String.format(AppConstants.Messages.DEBUG_IS_PM801, isPM801)
            )

            val (usbInterface, endpoint) = findPrinterInterfaceAndEndpoint(printer)
            if (usbInterface == null || endpoint == null) {
                Log.e(
                    AppConstants.LogTags.PRINTER_MANAGER,
                    AppConstants.Messages.ERROR_NO_INTERFACE
                )
                withContext(Dispatchers.Main) {
                    onComplete(false, AppConstants.Messages.ERROR_NO_INTERFACE)
                }
                return
            }

            Log.d(
                AppConstants.LogTags.PRINTER_MANAGER,
                "Found interface ${usbInterface.id} and endpoint ${endpoint.address}"
            )

            val connection = usbManager.openDevice(printer)
            if (connection == null) {
                Log.e(
                    AppConstants.LogTags.PRINTER_MANAGER,
                    AppConstants.Messages.ERROR_CANNOT_CONNECT
                )
                withContext(Dispatchers.Main) {
                    onComplete(false, AppConstants.Messages.ERROR_CANNOT_CONNECT)
                }
                return
            }

            val claimed = connection.claimInterface(usbInterface, true)
            if (!claimed) {
                Log.e(
                    AppConstants.LogTags.PRINTER_MANAGER,
                    AppConstants.Messages.ERROR_CANNOT_CLAIM
                )
                connection.close()
                withContext(Dispatchers.Main) {
                    onComplete(false, AppConstants.Messages.ERROR_CANNOT_CLAIM)
                }
                return
            }

            initializePrinter(connection, endpoint)

            val commands = if (isPM801) {
                createPM801PrinterCommands(text)
            } else {
                createGenericPrinterCommands(text)
            }

            var successCount = 0
            var failCount = 0

            for (index in commands.indices) {
                val command = commands[index]
                Log.d(
                    AppConstants.LogTags.PRINTER_MANAGER,
                    "Sending command ${index + 1}/${commands.size}"
                )

                val sent = connection.bulkTransfer(
                    endpoint,
                    command,
                    command.size,
                    AppConstants.Timing.TIMEOUT_COMMAND
                )
                if (sent < 0) {
                    failCount++
                } else {
                    successCount++
                }

                Thread.sleep(AppConstants.Timing.DELAY_COMMANDS)
            }

            connection.releaseInterface(usbInterface)
            connection.close()

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

            withContext(Dispatchers.Main) {
                onComplete(success, message)
            }
        } catch (e: Exception) {
            Log.e(AppConstants.LogTags.PRINTER_MANAGER, "Error during printing: ${e.message}", e)
            withContext(Dispatchers.Main) {
                onComplete(false, "Error: ${e.message}")
            }
        }
    }

    private fun isPM801Printer(device: UsbDevice): Boolean {
        if (device.vendorId == AppConstants.Usb.VENDOR_ID_PM801 && device.productId == AppConstants.Usb.PRODUCT_ID_PM801) {
            return true
        }

        val deviceName = device.deviceName
        val productName = device.productName ?: ""

        return deviceName.contains(AppConstants.Usb.PRINTER_NAME_PM801, ignoreCase = true) ||
                productName.contains(AppConstants.Usb.PRINTER_NAME_PM801, ignoreCase = true) ||
                productName.contains(AppConstants.Usb.PRINTER_NAME_PRN, ignoreCase = true) ||
                productName.contains(AppConstants.Usb.PRINTER_NAME_VIRTUAL_PRN, ignoreCase = true)
    }

    private fun initializePrinter(connection: UsbDeviceConnection, endpoint: UsbEndpoint) {
        try {
            Log.d(AppConstants.LogTags.PRINTER_MANAGER, AppConstants.Messages.DEBUG_PRINTER_INIT)

            connection.bulkTransfer(
                endpoint,
                AppConstants.PrinterCommands.CMD_RESET,
                AppConstants.PrinterCommands.CMD_RESET.size,
                AppConstants.Timing.TIMEOUT_RESET
            )
            Thread.sleep(AppConstants.Timing.DELAY_INIT)

            connection.bulkTransfer(
                endpoint,
                AppConstants.PrinterCommands.CMD_LINE_SPACING,
                AppConstants.PrinterCommands.CMD_LINE_SPACING.size,
                AppConstants.Timing.TIMEOUT_OTHER_COMMANDS
            )
            connection.bulkTransfer(
                endpoint,
                AppConstants.PrinterCommands.CMD_CHARSET_PC437,
                AppConstants.PrinterCommands.CMD_CHARSET_PC437.size,
                AppConstants.Timing.TIMEOUT_OTHER_COMMANDS
            )

            Thread.sleep(AppConstants.Timing.DELAY_INIT)
        } catch (e: Exception) {
            Log.w(
                AppConstants.LogTags.PRINTER_MANAGER,
                "Printer initialization exception: ${e.message}"
            )
        }
    }

    private fun createPM801PrinterCommands(text: String): List<ByteArray> {
        val commands = mutableListOf<ByteArray>()

        commands.add(AppConstants.PrinterCommands.CMD_RESET)
        commands.add(AppConstants.PrinterCommands.CMD_NORMAL_TEXT)

        val textBytes = text.toByteArray()

        var position = 0
        while (position < textBytes.size) {
            val remaining = textBytes.size - position
            val size =
                if (remaining > AppConstants.Data.CHUNK_SIZE_PM801) AppConstants.Data.CHUNK_SIZE_PM801 else remaining
            val chunk = textBytes.copyOfRange(position, position + size)
            commands.add(chunk)
            position += size
        }

        commands.add(AppConstants.PrinterCommands.CMD_MULTIPLE_LINE_FEED)
        commands.add(AppConstants.PrinterCommands.CMD_CUT_PAPER)

        return commands
    }

    private fun createGenericPrinterCommands(text: String): List<ByteArray> {
        val commands = mutableListOf<ByteArray>()

        commands.add(AppConstants.PrinterCommands.CMD_RESET)
        commands.add(AppConstants.PrinterCommands.CMD_NORMAL_TEXT)
        commands.add(AppConstants.PrinterCommands.CMD_CHARSET_PC437)

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

        commands.add(AppConstants.PrinterCommands.CMD_MULTIPLE_LINE_FEED)
        commands.add(AppConstants.PrinterCommands.CMD_CUT_PAPER)

        return commands
    }

    private fun findPrinterInterfaceAndEndpoint(device: UsbDevice): Pair<UsbInterface?, UsbEndpoint?> {
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

        return Pair(null, null)
    }

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
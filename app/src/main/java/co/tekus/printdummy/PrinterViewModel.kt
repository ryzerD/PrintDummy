package co.tekus.printdummy

import android.app.Application
import android.hardware.usb.UsbDevice
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import co.tekus.printdummy.model.PrintStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PrinterViewModel(application: Application) : AndroidViewModel(application) {

    private val printerManager = PrinterManager(application)
    private val printLogger = PrintLogger(application)

    private val _usbDevices = MutableStateFlow<List<UsbDevice>>(emptyList())
    val usbDevices: StateFlow<List<UsbDevice>> = _usbDevices.asStateFlow()

    private val _selectedDevice = MutableStateFlow<UsbDevice?>(null)
    val selectedDevice: StateFlow<UsbDevice?> = _selectedDevice.asStateFlow()

    private val _printStatus = MutableStateFlow<PrintStatus?>(null)
    val printStatus: StateFlow<PrintStatus?> = _printStatus.asStateFlow()

    private var usbPermissionHandler: UsbPermissionHandler

    init {
        usbPermissionHandler = UsbPermissionHandler(
            context = application,
            onPermissionResult = { device, granted ->
                val productName = device.productName ?: AppConstants.Messages.UNKOWN
                if (granted) {
                    Log.d(
                        AppConstants.LogTags.PRINTER_VIEW_MODEL,
                        String.format(
                            AppConstants.Messages.USB_PERMISSION_GRANTED,
                            device.deviceName, productName
                        )
                    )
                }
            },
            onScanRequested = this::scanForUsbDevices
        )

        usbPermissionHandler.register()
    }

    override fun onCleared() {
        super.onCleared()
        usbPermissionHandler.unregister()
    }

    fun onDeviceSelected(device: UsbDevice) {
        _selectedDevice.value = device
        Log.d(
            AppConstants.LogTags.MAIN_ACTIVITY,
            "Device selected: ${device.deviceName}, ${device.productName ?: "Unknown"}"
        )
    }

    fun scanForUsbDevices() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val devices = printerManager.findAvailablePorts()
                Log.d(
                    AppConstants.LogTags.MAIN_ACTIVITY,
                    String.format(AppConstants.Messages.DEBUG_DEVICES_FOUND, devices.size)
                )

                // Log detailed information about each device
                devices.forEach { device ->
                    printerManager.debugUsbDevice(device)
                }

                // Request permission for any device that doesn't already have it
                requestUsbPermissions(devices)

                // Update devices state
                _usbDevices.value = devices

                // Update selected device if needed
                if (_selectedDevice.value == null || !devices.contains(_selectedDevice.value)) {
                    _selectedDevice.value = if (devices.isNotEmpty()) devices[0] else null
                }

                // Show error if no devices found
                if (devices.isEmpty()) {
                    Log.d(
                        AppConstants.LogTags.MAIN_ACTIVITY,
                        AppConstants.Messages.DEBUG_NO_DEVICES
                    )
                    _printStatus.value =
                        PrintStatus.Error(AppConstants.Messages.ERROR_NO_USB_DEVICES)
                }
            } catch (e: Exception) {
                Log.e(
                    AppConstants.LogTags.MAIN_ACTIVITY,
                    "Error scanning USB devices: ${e.message}",
                    e
                )
                _printStatus.value = PrintStatus.Error("Error: ${e.message}")
            }
        }
    }

    fun printSample() {
        viewModelScope.launch {
            // Verify devices are available
            if (_usbDevices.value.isEmpty()) {
                _printStatus.value = PrintStatus.Error(AppConstants.Messages.ERROR_NO_USB_DEVICES)
                return@launch
            }

            val printerDevice =
                _selectedDevice.value ?: printerManager.findPrinterDevice(_usbDevices.value)

            if (printerDevice == null) {
                _printStatus.value =
                    PrintStatus.Error(AppConstants.Messages.ERROR_NO_COMPATIBLE_PRINTER)
                return@launch
            }

            // Format date and prepare receipt text
            val deviceName = printerDevice.productName ?: printerDevice.deviceName
            _printStatus.value = PrintStatus.Success(
                String.format(
                    AppConstants.Messages.MSG_PRINT_SENDING,
                    deviceName
                )
            )

            val dateFormatter =
                SimpleDateFormat(AppConstants.DemoContent.DATE_FORMAT, Locale.getDefault())
            val currentDate = dateFormatter.format(Date())
            val textToPrint = String.format(AppConstants.DemoContent.RECEIPT_TEMPLATE, currentDate)

            // Log print attempt
            Log.d(
                AppConstants.LogTags.MAIN_ACTIVITY,
                String.format(AppConstants.Messages.DEBUG_PRINTING_TO, printerDevice.deviceName)
            )
            printLogger.debug("Printing to device: %s", printerDevice.deviceName)

            // Execute printing in background
            withContext(Dispatchers.IO) {
                printerManager.printText(
                    text = textToPrint,
                    availablePorts = _usbDevices.value
                ) { success, message ->
                    viewModelScope.launch {
                        _printStatus.value = if (success) {
                            PrintStatus.Success(message)
                        } else {
                            PrintStatus.Error(message)
                        }
                        printLogger.debug(
                            "Print result: %s - %s",
                            if (success) "SUCCESS" else "FAILURE",
                            message
                        )
                    }
                }
            }
        }
    }

    private fun requestUsbPermissions(devices: List<UsbDevice>) {
        usbPermissionHandler.requestPermissions(devices)
    }
}
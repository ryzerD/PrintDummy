package co.tekus.printdummy.model

import android.hardware.usb.UsbDevice

/**
 * Data model that encapsulates all necessary information for the Printer UI.
 * This simplifies the PrinterDemoUI composable signature and improves maintainability.
 */
data class PrinterUiState(
    val serialPorts: Int = 0,
    val devices: List<UsbDevice> = emptyList(),
    val selectedDevice: UsbDevice? = null,
    val onPrintSample: () -> Unit = {},
    val onScanForUsbDevices: () -> Unit = {},
    val onDeviceSelected: (UsbDevice) -> Unit = {}
)
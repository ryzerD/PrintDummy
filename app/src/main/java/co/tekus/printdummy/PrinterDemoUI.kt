package co.tekus.printdummy

import android.hardware.usb.UsbDevice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import co.tekus.printdummy.model.PrinterUiState

/**
 * Composable that creates the main UI for the printer demo application.
 * Provides a clean interface for interacting with USB printers through:
 * - A button to print a test receipt
 * - A button to scan for connected USB devices
 * - A display showing the count of connected devices
 *
 * This component follows a stateless design, receiving all necessary data
 * and callbacks from its parent.
 *
 * @param serialPorts Count of currently connected USB devices
 * @param printSample Callback function to trigger test receipt printing
 * @param scanForUsbDevices Callback function to trigger USB device scanning
 */
@Composable
fun PrinterDemoUI(uiState: PrinterUiState) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Printer Demo",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            Button(
                onClick = { uiState.onPrintSample() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("Print Test Receipt")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { uiState.onScanForUsbDevices() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("Scan for USB Devices")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Connected devices: ${uiState.serialPorts}",
                style = MaterialTheme.typography.bodyMedium
            )

            // Mostrar el dropdown solo si hay múltiples dispositivos
            if (uiState.devices.size > 1) {
                Spacer(modifier = Modifier.height(16.dp))
                DeviceSelectionDropdown(
                    devices = uiState.devices,
                    selectedDevice = uiState.selectedDevice,
                    onDeviceSelected = uiState.onDeviceSelected
                )
            }
        }
    }
}

@Composable
fun DeviceSelectionDropdown(
    devices: List<UsbDevice>,
    selectedDevice: UsbDevice?,
    onDeviceSelected: (UsbDevice) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Seleccionar impresora:",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = selectedDevice?.let {
                        it.productName ?: it.deviceName
                    } ?: "Seleccione un dispositivo"
                )
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = "Desplegar opciones"
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth(0.9f)
            ) {
                devices.forEach { device ->
                    val deviceName = device.productName ?: device.deviceName
                    DropdownMenuItem(
                        text = { Text(deviceName) },
                        onClick = {
                            onDeviceSelected(device)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
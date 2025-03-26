package co.tekus.printdummy

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import co.tekus.printdummy.model.PrintStatus
import co.tekus.printdummy.model.PrinterUiState
import co.tekus.printdummy.ui.theme.PrintDummyTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: PrinterViewModel by viewModels()

    private lateinit var usbPermissionHandler: UsbPermissionHandler

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usbPermissionHandler = UsbPermissionHandler(
            context = this,
            onPermissionResult = { device, granted ->
                // Manejar permisos si es necesario
            },
            onScanRequested = { viewModel.scanForUsbDevices() }
        )

        usbPermissionHandler.register()



        // Observe print status changes
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.printStatus.collect { status ->
                    status?.let {
                        when (it) {
                            is PrintStatus.Success -> {
                                Toast.makeText(this@MainActivity, it.message, Toast.LENGTH_SHORT)
                                    .show()
                            }

                            is PrintStatus.Error -> {
                                Toast.makeText(this@MainActivity, it.message, Toast.LENGTH_SHORT)
                                    .show()
                            }
                        }
                    }
                }
            }
        }

        // Scan for connected USB devices on startup
        viewModel.scanForUsbDevices()

        // Set up the UI with Jetpack Compose
        setContent {
            val devices = viewModel.usbDevices.collectAsState().value
            val selectedDevice = viewModel.selectedDevice.collectAsState().value

            val printerUiState = PrinterUiState(
                serialPorts = devices.size,
                devices = devices,
                selectedDevice = selectedDevice,
                onPrintSample = viewModel::printSample,
                onScanForUsbDevices = viewModel::scanForUsbDevices,
                onDeviceSelected = viewModel::onDeviceSelected
            )

            PrintDummyTheme {
                PrinterDemoUI(printerUiState)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        usbPermissionHandler.unregister()
    }
}
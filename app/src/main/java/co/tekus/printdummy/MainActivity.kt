package co.tekus.printdummy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import co.tekus.printdummy.ui.theme.PrintDummyTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: PrinterViewModel by viewModels()

    /**
     * BroadcastReceiver that handles USB permission responses.
     */
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (AppConstants.Usb.ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    // Extract USB device from intent, handling API level differences
                    val device: UsbDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                UsbManager.EXTRA_DEVICE,
                                UsbDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        // Permission granted, proceed with scanning
                        device?.let {
                            Log.d(
                                AppConstants.LogTags.MAIN_ACTIVITY,
                                "Permission granted for device: ${it.deviceName}"
                            )
                            viewModel.scanForUsbDevices()
                        }
                    } else {
                        // Permission denied, log the event
                        Log.d(
                            AppConstants.LogTags.MAIN_ACTIVITY,
                            "Permission denied for device: ${device?.deviceName}"
                        )
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register for USB permission broadcasts
        registerUsbReceiver()

        // Observe print status changes
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.printStatus.collect { status ->
                    status?.let {
                        when (it) {
                            is PrinterViewModel.PrintStatus.Success -> {
                                Toast.makeText(this@MainActivity, it.message, Toast.LENGTH_SHORT)
                                    .show()
                            }

                            is PrinterViewModel.PrintStatus.Error -> {
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

            PrintDummyTheme {
                PrinterDemoUI(
                    serialPorts = devices.size,
                    printSample = viewModel::printSample,
                    scanForUsbDevices = viewModel::scanForUsbDevices,
                    devices = devices,
                    selectedDevice = selectedDevice,
                    onDeviceSelected = viewModel::onDeviceSelected
                )
            }
        }
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter(AppConstants.Usb.ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            Log.w(
                AppConstants.LogTags.MAIN_ACTIVITY,
                AppConstants.Messages.DEBUG_PERMISSION_WARNING
            )
            registerReceiver(usbReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            Log.e(AppConstants.LogTags.MAIN_ACTIVITY, "Error unregistering receiver: ${e.message}")
        }
    }
}
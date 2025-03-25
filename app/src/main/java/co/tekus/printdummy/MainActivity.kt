package co.tekus.printdummy

import android.app.PendingIntent
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import co.tekus.printdummy.ui.theme.PrintDummyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val serialPorts = mutableStateOf<List<UsbDevice>>(emptyList())
    private lateinit var printerManager: PrinterManager
    private lateinit var usbManager: UsbManager
    private val mainScope = CoroutineScope(Dispatchers.Main)
    private lateinit var printLogger: PrintLogger

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (AppConstants.Usb.ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
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
                        device?.let {
                            Log.d(
                                AppConstants.LogTags.MAIN_ACTIVITY,
                                "Permission granted for device: ${it.deviceName}"
                            )
                            scanForUsbDevices()
                        }
                    } else {
                        Log.d(
                            AppConstants.LogTags.MAIN_ACTIVITY,
                            "Permission denied for device: ${device?.deviceName}"
                        )
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        printerManager = PrinterManager(this)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        printLogger = PrintLogger(this)

        val filter = IntentFilter(AppConstants.Usb.ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            Log.w(
                AppConstants.LogTags.MAIN_ACTIVITY,
                AppConstants.Messages.DEBUG_PERMISSION_WARNING
            )
            registerReceiver(usbReceiver, filter)
        }

        scanForUsbDevices()

        setContent {
            PrintDummyTheme {
                PrinterDemoUI()
            }
        }
    }

    @Composable
    fun PrinterDemoUI() {
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
                    onClick = { printSample() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text("Print Test Receipt")
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { scanForUsbDevices() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text("Scan for USB Devices")
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Connected devices: ${serialPorts.value.size}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    private fun scanForUsbDevices() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val devices = printerManager.findAvailablePorts()
                Log.d(
                    AppConstants.LogTags.MAIN_ACTIVITY,
                    String.format(AppConstants.Messages.DEBUG_DEVICES_FOUND, devices.size)
                )

                devices.forEach { device ->
                    printerManager.debugUsbDevice(device)
                }

                val permissionIntent = PendingIntent.getBroadcast(
                    this@MainActivity,
                    0,
                    Intent(AppConstants.Usb.ACTION_USB_PERMISSION),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE
                    } else {
                        0
                    }
                )

                devices.forEach { device ->
                    if (!usbManager.hasPermission(device)) {
                        usbManager.requestPermission(device, permissionIntent)
                    }
                }

                mainScope.launch {
                    serialPorts.value = devices
                }

                if (devices.isEmpty()) {
                    Log.d(
                        AppConstants.LogTags.MAIN_ACTIVITY,
                        AppConstants.Messages.DEBUG_NO_DEVICES
                    )
                    mainScope.launch {
                        Toast.makeText(
                            this@MainActivity,
                            AppConstants.Messages.ERROR_NO_USB_DEVICES,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(
                    AppConstants.LogTags.MAIN_ACTIVITY,
                    "Error scanning USB devices: ${e.message}",
                    e
                )
                mainScope.launch {
                    Toast.makeText(
                        this@MainActivity,
                        "Error: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun printSample() {
        if (serialPorts.value.isEmpty()) {
            Toast.makeText(this, AppConstants.Messages.ERROR_NO_USB_DEVICES, Toast.LENGTH_SHORT)
                .show()
            return
        }

        val printerDevice = printerManager.findPrinterDevice(serialPorts.value)

        if (printerDevice == null) {
            Toast.makeText(
                this,
                AppConstants.Messages.ERROR_NO_COMPATIBLE_PRINTER,
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val deviceName = printerDevice.productName ?: printerDevice.deviceName
        Toast.makeText(
            this,
            String.format(AppConstants.Messages.MSG_PRINT_SENDING, deviceName),
            Toast.LENGTH_SHORT
        ).show()

        val dateFormatter =
            SimpleDateFormat(AppConstants.DemoContent.DATE_FORMAT, Locale.getDefault())
        val currentDate = dateFormatter.format(Date())
        val textToPrint = String.format(AppConstants.DemoContent.RECEIPT_TEMPLATE, currentDate)

        Log.d(
            AppConstants.LogTags.MAIN_ACTIVITY,
            String.format(AppConstants.Messages.DEBUG_PRINTING_TO, printerDevice.deviceName)
        )

        printLogger.debug("Printing to device: %s", printerDevice.deviceName)

        CoroutineScope(Dispatchers.IO).launch {
            printerManager.printText(
                text = textToPrint,
                availablePorts = serialPorts.value
            ) { success, message ->
                runOnUiThread {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    printLogger.debug(
                        "Print result: %s - %s",
                        if (success) "SUCCESS" else "FAILURE",
                        message
                    )
                }
            }
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
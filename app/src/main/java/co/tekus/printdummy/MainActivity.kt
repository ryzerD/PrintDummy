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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import co.tekus.printdummy.ui.theme.PrintDummyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val serialPorts = mutableStateOf<List<UsbDevice>>(emptyList())
    private lateinit var printerManager: PrinterManager
    private lateinit var usbManager: UsbManager
    private val TAG = "MainActivity"
    private val ACTION_USB_PERMISSION = "co.tekus.printdummy.USB_PERMISSION"
    private var selectedPrinter: UsbDevice? = null
    private val mainScope = CoroutineScope(Dispatchers.Main)

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
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
                            Log.d(TAG, "Permission granted for device ${it.deviceName}")
                            // Update UI on main thread
                            mainScope.launch {
                                if (!serialPorts.value.contains(it)) {
                                    serialPorts.value = serialPorts.value + it
                                }
                                selectedPrinter = it
                            }
                        }
                    } else {
                        Log.d(TAG, "Permission denied for device ${device?.deviceName}")
                        mainScope.launch {
                            Toast.makeText(
                                context,
                                "Permission denied for USB device",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        printerManager = PrinterManager(this)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        // Register receiver with appropriate flag based on Android version
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // For older Android versions, we need a regular registration but should
            // add a warning about security
            Log.w(TAG, "Using unprotected broadcast receiver on older Android version")
            registerReceiver(usbReceiver, filter)
        }

        // Discover and request permission for USB devices
        scanForUsbDevices()

        setContent {
            PrintDummyTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(16.dp)
                            .fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = "Impresora Serial/USB")
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { printSample() }) {
                            Text(text = "Enviar texto a impresora")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { scanForUsbDevices() }) {
                            Text(text = "Buscar dispositivos USB")
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(text = "Puertos disponibles: ${serialPorts.value.size}")
                        serialPorts.value.forEach { port ->
                            Text(text = "${port.deviceName} - ${port.productName ?: "Unknown"}")
                        }
                    }
                }
            }
        }
    }

    private fun scanForUsbDevices() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val devices = printerManager.findAvailablePorts()
                Log.d(TAG, "Found ${devices.size} USB devices")

                // Debug each device to help troubleshoot
                devices.forEach { device ->
                    printerManager.debugUsbDevice(device)
                }

                val newPorts = mutableListOf<UsbDevice>()

                devices.forEach { device ->
                    if (!usbManager.hasPermission(device)) {
                        Log.d(TAG, "Requesting permission for device ${device.deviceName}")
                        val permissionIntent = PendingIntent.getBroadcast(
                            this@MainActivity,
                            0,
                            Intent(ACTION_USB_PERMISSION),
                            PendingIntent.FLAG_IMMUTABLE
                        )
                        usbManager.requestPermission(device, permissionIntent)
                    } else {
                        Log.d(TAG, "Already have permission for ${device.deviceName}")
                        newPorts.add(device)
                        if (selectedPrinter == null) {
                            selectedPrinter = device
                        }
                    }
                }

                if (newPorts.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        serialPorts.value = newPorts
                    }
                }

                if (devices.isEmpty()) {
                    Log.d(TAG, "No USB devices found")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "No se encontraron dispositivos USB",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning USB devices: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Error escaneando dispositivos: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun printSample() {
        if (serialPorts.value.isEmpty()) {
            Toast.makeText(this, "No hay dispositivos USB conectados", Toast.LENGTH_SHORT).show()
            return
        }

        // This step is critical - you need to find the actual printer device
        val printerDevice = printerManager.findPrinterDevice(serialPorts.value)

        if (printerDevice == null) {
            Toast.makeText(this, "No se encontró una impresora compatible", Toast.LENGTH_SHORT)
                .show()
            return
        }

        // Show printing status to user
        Toast.makeText(
            this,
            "Enviando a impresora ${printerDevice.productName ?: printerDevice.deviceName}...",
            Toast.LENGTH_SHORT
        ).show()

        val textToPrint = """
            Tekus SAS
            ==================
    
            Pablo me la succiona
            ------------------
            Fecha: ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())}
    
            Producto 1        $10.00
            Producto 2        $15.00
            ------------------
            Total:            $25.00
    
            Gracias por su compra
        """.trimIndent()

        Log.d(TAG, "Attempting to print to device: ${printerDevice.deviceName}")

        CoroutineScope(Dispatchers.IO).launch {
            printerManager.printText(
                textToPrint,
                listOf(printerDevice)  // Only pass the printer device
            ) { success, message ->
                Log.d(TAG, "Print result: $message, success: $success")
                mainScope.launch {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver: ${e.message}")
        }
    }
}
package co.tekus.printdummy

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

class UsbPermissionHandler(
    private val context: Context,
    private val onPermissionResult: (device: UsbDevice, granted: Boolean) -> Unit,
    private val onScanRequested: () -> Unit
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

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

                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

                    device?.let {
                        if (granted) {
                            Log.d(
                                AppConstants.LogTags.MAIN_ACTIVITY,
                                "Permission granted for device: ${it.deviceName}"
                            )
                            onPermissionResult(it, true)
                            onScanRequested()
                        } else {
                            Log.d(
                                AppConstants.LogTags.MAIN_ACTIVITY,
                                "Permission denied for device: ${it.deviceName}"
                            )
                            onPermissionResult(it, false)
                        }
                    }
                }
            }
        }
    }

    fun register() {
        val filter = IntentFilter(AppConstants.Usb.ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            Log.w(
                AppConstants.LogTags.MAIN_ACTIVITY,
                AppConstants.Messages.DEBUG_PERMISSION_WARNING
            )
            context.registerReceiver(usbReceiver, filter)
        }
    }

    fun unregister() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            Log.e(AppConstants.LogTags.MAIN_ACTIVITY, "Error unregistering receiver: ${e.message}")
        }
    }

    fun requestPermission(device: UsbDevice) {
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(AppConstants.Usb.ACTION_USB_PERMISSION),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
        )

        if (!usbManager.hasPermission(device)) {
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    fun requestPermissions(devices: List<UsbDevice>) {
        devices.forEach { device ->
            requestPermission(device)
        }
    }

    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }
}
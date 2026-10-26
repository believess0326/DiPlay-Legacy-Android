package com.shilapi.xcertplay.usbbt

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.shilapi.xcertplay.bluetooth.CarPlayBluetoothManager

/**
 * Trampoline Activity registered for USB_DEVICE_ATTACHED with usb_bt_device_filter.xml.
 *
 * This provides the static intent-filter that Android requires in order to persist the
 * "Use by default for this USB device" permission checkbox across app restarts and reboots,
 * while isolating it strictly to Bluetooth dongles so iPhone wired connections are unaffected.
 */
class UsbBtDeviceAttachedActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = intent
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
            }
            Log.i(TAG, "USB Bluetooth dongle attached: device=$device")
            if (device != null) {
                Thread({
                    runCatching {
                        CarPlayBluetoothManager.get(applicationContext).usbDongle?.enable()
                    }
                }, "diplay-usbbt-attached").apply {
                    isDaemon = true
                    start()
                }
            }
        }
        finish()
    }

    private companion object {
        const val TAG = "UsbBtDeviceAttached"
    }
}

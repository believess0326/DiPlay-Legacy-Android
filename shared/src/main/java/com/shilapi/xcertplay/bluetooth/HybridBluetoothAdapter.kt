package com.shilapi.xcertplay.bluetooth

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.usbbt.UsbBtBluetoothAdapter

class HybridBluetoothAdapter(
    val hsae: HsaeBluetoothAdapter,
    val android: AndroidBluetoothAdapter,
    /** USB dongle stack; preferred for data channels when plugged in. */
    val usbDongle: UsbBtBluetoothAdapter? = null,
) : CarPlayBluetooth {

    companion object {
        private const val TAG = "HybridBluetoothAdapter"
    }

    override fun isSupported(): Boolean =
        hsae.isSupported() || android.isSupported() || usbDongle?.isSupported() == true

    override fun isEnabled(): Boolean {
        if (usbDongle?.isEnabled() == true) return true
        if (hsae.isSupported() && hsae.isEnabled()) return true
        return android.isEnabled()
    }

    override fun enable(): Boolean {
        var ok = false
        if (hsae.isSupported()) {
            ok = hsae.enable()
        }
        if (android.enable()) ok = true
        // USB dongle: enable whenever one is plugged in. Previously this only
        // ran when neither HSAE nor Android existed, so on T3 (HSAE present
        // but audio-only) the dongle was never enabled and wireless could
        // never reach it (P0-1). Plugging in a dongle is itself the opt-in.
        if (usbDongle?.isSupported() == true) {
            if (usbDongle.enable()) ok = true
        }
        return ok
    }

    override fun disable() {
        usbDongle?.disable()
    }

    override fun getAdapterName(): String {
        val parts = mutableListOf<String>()
        if (hsae.isSupported()) parts.add("HSAE")
        if (android.isSupported()) parts.add("Android")
        if (usbDongle?.isSupported() == true) parts.add("USB")
        return if (parts.isEmpty()) android.getAdapterName()
        else "Hybrid(${parts.joinToString("+")})"
    }

    override fun getPairedDevices(): List<CarPlayBluetoothDevice> {
        // Merge all stacks, dedupe by MAC. Previously this returned the first
        // non-empty list, so an iPhone paired on the dongle was invisible
        // when HSAE had any (audio-only) paired device (P1-1).
        val seen = HashSet<String>()
        val merged = ArrayList<CarPlayBluetoothDevice>()
        fun addAll(devices: List<CarPlayBluetoothDevice>) {
            for (device in devices) {
                val key = device.address.lowercase()
                if (seen.add(key)) merged.add(device)
            }
        }
        if (hsae.isSupported()) runCatching { addAll(hsae.getPairedDevices()) }
        runCatching { addAll(android.getPairedDevices()) }
        usbDongle?.let { runCatching { addAll(it.getPairedDevices()) } }
        return merged
    }

    override fun getConnectedDevice(): CarPlayBluetoothDevice? {
        // Dongle first: it is the data-capable stack. HSAE may report a
        // connected audio-only device that cannot carry iAP2.
        usbDongle?.let { runCatching { it.getConnectedDevice() } }?.getOrNull()?.let { return it }
        android.getConnectedDevice()?.let { return it }
        if (hsae.isSupported()) {
            runCatching { hsae.getConnectedDevice() }.getOrNull()?.let { return it }
        }
        return null
    }

    override fun localAddress(context: Context): String? {
        // Dongle first: its BD_ADDR is the one the iPhone must pair with for
        // the data channel. Falling back to HSAE's address would pair the
        // iPhone to the audio-only module.
        usbDongle?.let { runCatching { it.localAddress(context) } }?.getOrNull()
            ?.takeIf { !it.isNullOrBlank() }?.let { return it }
        if (hsae.isSupported()) {
            val hsaeAddr = hsae.localAddress(context)
            if (!hsaeAddr.isNullOrBlank()) return hsaeAddr
        }
        android.localAddress(context)?.let { return it }
        return null
    }

    override fun openRfcommStream(deviceAddress: String, uuid: String): BlockingDuplexByteStream {
        // Priority: USB dongle -> Android -> HSAE.
        //
        // However, if the target device is bonded to Android native Bluetooth
        // and NOT bonded to the USB dongle, trying the dongle first would block for
        var lastError: Throwable? = null
        var tried = false
        val dongle = usbDongle
        val dongleActive = dongle != null && dongle.isSupported() && dongle.isEnabled()

        fun tryDongle(): BlockingDuplexByteStream? {
            if (!dongleActive) return null
            tried = true
            com.shilapi.xcertplay.usbbt.UsbBtLog.log(null, TAG, "openRfcommStream: trying USB dongle stack for $deviceAddress")
            val result = runCatching { dongle!!.openRfcommStream(deviceAddress, uuid) }
            val stream = result.getOrNull()
            if (stream != null) {
                com.shilapi.xcertplay.usbbt.UsbBtLog.log(null, TAG, "openRfcommStream: USB dongle stream connected")
                return stream
            }
            lastError = result.exceptionOrNull()
            com.shilapi.xcertplay.usbbt.UsbBtLog.warn(null, TAG, "USB dongle RFCOMM stream failed", lastError)
            return null
        }

        fun tryAndroid(): BlockingDuplexByteStream? {
            if (!android.isSupported() || !android.isEnabled()) return null
            tried = true
            com.shilapi.xcertplay.usbbt.UsbBtLog.log(null, TAG, "openRfcommStream: trying Android Bluetooth stack for $deviceAddress")
            val result = runCatching { android.openRfcommStream(deviceAddress, uuid) }
            val stream = result.getOrNull()
            if (stream != null) {
                com.shilapi.xcertplay.usbbt.UsbBtLog.log(null, TAG, "openRfcommStream: Android Bluetooth stream connected")
                return stream
            }
            lastError = result.exceptionOrNull()
            com.shilapi.xcertplay.usbbt.UsbBtLog.warn(null, TAG, "Android RFCOMM stream failed", lastError)
            return null
        }

        // Priority: When USB dongle is active, ALWAYS prefer USB dongle for CarPlay data paths.
        // On head units like Allwinner T3, the native Android stack is a proprietary GOC/serial
        // hands-free module that cannot transport iAP2 RFCOMM data (channel=-1 close).
        // Car owners frequently have their phones paired with the native system for OEM phone calls;
        // preferring native Android over USB dongle in that case causes fatal connection failures.
        if (dongleActive) {
            tryDongle()?.let { return it }
            tryAndroid()?.let { return it }
        } else {
            tryAndroid()?.let { return it }
            tryDongle()?.let { return it }
        }
        if (hsae.isSupported()) {
            com.shilapi.xcertplay.usbbt.UsbBtLog.log(null, TAG, "openRfcommStream: trying HSAE Bluetooth stack for $deviceAddress")
            return hsae.openRfcommStream(deviceAddress, uuid)
        }
        val error = lastError
        if (tried && error != null) {
            throw java.io.IOException(
                "Bluetooth RFCOMM unavailable: ${error.message ?: error.javaClass.simpleName}",
                error,
            )
        }
        throw java.io.IOException("no Bluetooth stack available")
    }
}

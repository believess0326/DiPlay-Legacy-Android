package com.shilapi.xcertplay.transport

import java.io.File

/**
 * Issue #266: on some vendor ROMs (e.g. Reglink Sofia 3GR) `UsbManager.getDeviceList()`
 * always returns empty even with an iPhone plugged in, so the framework discovery path
 * never sees the phone at all. sysfs is the ground truth the USB service itself reads:
 * every USB device appears as a directory under /sys/bus/usb/devices with plain-text
 * idVendor/idProduct/busnum/devnum attributes that any app may read.
 *
 * This enumerator is the discovery half of the direct usbfs mode. It only READS sysfs;
 * opening the matching /dev/bus/usb node is [DirectUsbAccess]'s job.
 */
data class SysfsUsbDevice(
    val sysfsName: String,
    val vendorId: Int,
    val productId: Int,
    val busNumber: Int,
    val deviceNumber: Int,
    val manufacturer: String?,
    val product: String?,
    val serial: String?,
) {
    /** The usbfs device node for this device, e.g. /dev/bus/usb/001/004. */
    val usbfsPath: String
        get() = "/dev/bus/usb/%03d/%03d".format(busNumber, deviceNumber)
}

class SysfsUsbEnumerator(
    private val sysfsRoot: File = File("/sys/bus/usb/devices"),
) {
    /** All real USB devices currently visible in sysfs (interface entries are skipped). */
    fun enumerate(): List<SysfsUsbDevice> {
        val dirs = sysfsRoot.listFiles() ?: return emptyList()
        return dirs.mapNotNull { dir -> readDevice(dir) }
            .sortedWith(compareBy({ it.busNumber }, { it.deviceNumber }))
    }

    /** Devices whose VID/PID the [matcher] accepts — the iPhone candidates. */
    fun findMatching(matcher: IphoneUsbMatcher): List<SysfsUsbDevice> =
        enumerate().filter { matcher.matches(it.vendorId, it.productId) }

    private fun readDevice(dir: File): SysfsUsbDevice? {
        if (!dir.isDirectory) return null
        // Interface entries are named like "1-2:1.0" and carry no idVendor of their own;
        // root hubs use names like "usb1" and DO carry the attributes.
        if (dir.name.contains(':')) return null
        val vendor = readHex(dir, "idVendor") ?: return null
        val product = readHex(dir, "idProduct") ?: return null
        val bus = readDecimal(dir, "busnum") ?: return null
        val dev = readDecimal(dir, "devnum") ?: return null
        return SysfsUsbDevice(
            sysfsName = dir.name,
            vendorId = vendor,
            productId = product,
            busNumber = bus,
            deviceNumber = dev,
            manufacturer = readText(dir, "manufacturer"),
            product = readText(dir, "product"),
            serial = readText(dir, "serial"),
        )
    }

    private fun readText(dir: File, name: String): String? =
        try {
            File(dir, name).readText().trim().ifEmpty { null }
        } catch (_: Exception) {
            null
        }

    private fun readHex(dir: File, name: String): Int? =
        readText(dir, name)?.toIntOrNull(16)

    private fun readDecimal(dir: File, name: String): Int? =
        readText(dir, name)?.toIntOrNull(10)
}

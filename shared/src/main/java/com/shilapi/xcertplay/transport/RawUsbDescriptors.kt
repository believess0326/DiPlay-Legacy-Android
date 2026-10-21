package com.shilapi.xcertplay.transport

/**
 * Descriptor discovery for the direct usbfs mode (issue #266 phase 2): reads the
 * device and configuration descriptors over a raw [SharedUsbConnection] and parses
 * them with the same pure parser the KitKat path uses, so the resulting
 * [CarPlayUsbConfiguration] model is identical to the framework path's.
 *
 * Every step is logged through [log] — on a unit with a broken UsbManager these
 * lines are the only visibility into where bring-up stops.
 */
internal object RawUsbDescriptors {
    data class DeviceInfo(val vendorId: Int, val productId: Int, val configurationCount: Int)

    private const val USB_DIR_IN = 0x80
    private const val GET_DESCRIPTOR = 6
    private const val DEVICE_DESCRIPTOR = 0x0100
    private const val CONFIG_DESCRIPTOR_BASE = 0x0200
    private const val TIMEOUT_MILLIS = 2_000

    /** Reads the 18-byte device descriptor; null when the device does not answer. */
    fun readDeviceInfo(connection: SharedUsbConnection, log: (String) -> Unit): DeviceInfo? {
        val bytes = ByteArray(18)
        val read = connection.controlTransfer(
            USB_DIR_IN, GET_DESCRIPTOR, DEVICE_DESCRIPTOR, 0, bytes, bytes.size, TIMEOUT_MILLIS,
        )
        if (read < 18) {
            log("direct step: device descriptor read=$read (expected 18)")
            return null
        }
        val info = DeviceInfo(
            vendorId = u16(bytes, 8),
            productId = u16(bytes, 10),
            configurationCount = bytes[17].toInt() and 0xff,
        )
        log(
            "direct step: device descriptor vid=0x${info.vendorId.toString(16)} " +
                "pid=0x${info.productId.toString(16)} configurations=${info.configurationCount}",
        )
        return info
    }

    /**
     * Reads every configuration descriptor in full and parses them into
     * [CarPlayUsbConfiguration]s. Configurations that fail to read or parse are
     * skipped with a log line, mirroring the framework inspector's tolerance.
     */
    fun readConfigurations(
        connection: SharedUsbConnection, configurationCount: Int, log: (String) -> Unit,
    ): List<CarPlayUsbConfiguration> {
        val result = ArrayList<CarPlayUsbConfiguration>()
        for (index in 0 until configurationCount.coerceAtMost(8)) {
            val header = ByteArray(9)
            val headerRead = connection.controlTransfer(
                USB_DIR_IN, GET_DESCRIPTOR, CONFIG_DESCRIPTOR_BASE or index, 0,
                header, header.size, TIMEOUT_MILLIS,
            )
            if (headerRead != 9) {
                log("direct step: config[$index] header read=$headerRead (expected 9); skipped")
                continue
            }
            val total = u16(header, 2)
            if (total !in 9..65535) {
                log("direct step: config[$index] total length $total is implausible; skipped")
                continue
            }
            val blob = ByteArray(total)
            val blobRead = connection.controlTransfer(
                USB_DIR_IN, GET_DESCRIPTOR, CONFIG_DESCRIPTOR_BASE or index, 0,
                blob, blob.size, TIMEOUT_MILLIS,
            )
            if (blobRead != total) {
                log("direct step: config[$index] body read=$blobRead of $total; skipped")
                continue
            }
            val parsed = KitKatUsbDescriptors.parse(blob)
            if (parsed.isEmpty()) {
                log("direct step: config[$index] descriptor blob (${total}B) did not parse; skipped")
                continue
            }
            for (config in parsed) {
                result += CarPlayUsbConfiguration(
                    config.id,
                    config.interfaces.map(KitKatUsbDescriptors::platformInterface),
                )
                log(
                    "direct step: config[$index] parsed id=${config.id} " +
                        "interfaces=${config.interfaces.size}",
                )
            }
        }
        return result
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
}

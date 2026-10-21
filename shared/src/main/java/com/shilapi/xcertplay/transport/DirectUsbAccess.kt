package com.shilapi.xcertplay.transport

import android.os.ParcelFileDescriptor
import android.util.Log
import com.shilapi.xcertplay.compat.realSdkInt
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Direct usbfs access for the direct-USB mode (issue #266): opens a /dev/bus/usb node
 * without UsbManager ever having listed the device.
 *
 * Permission reality on car head units: the device nodes are usually owned by root or
 * the usb group, so a plain open fails with EACCES. Two ways through, in order:
 *  1. the ROM already grants access (common on userdebug/test-keys car firmware);
 *  2. root: `su -c chmod` the node, then open it as the app. The grant lasts until the
 *     device is unplugged (the node is recreated on re-enumeration).
 *
 * Nothing here runs unless the user turned the direct mode on in Settings.
 */
object DirectUsbAccess {
    private const val TAG = "xcertplay-usb"

    sealed class OpenResult {
        /** Open succeeded; [descriptor] owns the node fd and must be closed by the caller. */
        data class Opened(val descriptor: ParcelFileDescriptor, val viaRootGrant: Boolean) : OpenResult()
        /** The node exists but could not be opened; [reason] is for the diagnostic log. */
        data class Denied(val reason: String) : OpenResult()
        object NotPresent : OpenResult()
    }

    /** Cached `su` probe; null until first checked. */
    @Volatile private var rootAvailable: Boolean? = null

    fun rootAvailable(): Boolean {
        rootAvailable?.let { return it }
        val available = runSu(listOf("id"), timeoutMillis = 3_000)
        rootAvailable = available
        return available
    }

    /**
     * Opens [device]'s usbfs node, falling back to a root permission grant when the
     * plain open is denied and [allowRootGrant] is set.
     */
    fun open(device: SysfsUsbDevice, allowRootGrant: Boolean): OpenResult {
        val node = File(device.usbfsPath)
        if (!node.exists()) return OpenResult.NotPresent
        openNode(node)?.let { return OpenResult.Opened(it, viaRootGrant = false) }
        if (!allowRootGrant) {
            return OpenResult.Denied("usbfs node ${device.usbfsPath} not openable and root grant disabled")
        }
        if (!rootAvailable()) {
            return OpenResult.Denied("usbfs node ${device.usbfsPath} not openable and no root (su) available")
        }
        // Grant the app read/write on this node only; the node vanishes on unplug, so the
        // grant cannot outlive the physical connection.
        val granted = runSu(listOf("chmod", "666", device.usbfsPath), timeoutMillis = 5_000)
        if (!granted) {
            return OpenResult.Denied("root chmod failed for ${device.usbfsPath}")
        }
        Log.i(TAG, "direct usbfs: root granted access to ${device.usbfsPath}")
        return openNode(node)?.let { OpenResult.Opened(it, viaRootGrant = true) }
            ?: OpenResult.Denied("usbfs node ${device.usbfsPath} still not openable after root grant")
    }

    private fun openNode(node: File): ParcelFileDescriptor? = try {
        ParcelFileDescriptor.open(node, ParcelFileDescriptor.MODE_READ_WRITE)
    } catch (_: Exception) {
        null
    }

    /**
     * Issue #266: on some kernels the usbfs SET_CONFIGURATION reports success while
     * the kernel keeps the old configuration; writing the same value through sysfs
     * (root shell) is the route that takes effect there.
     */
    fun writeConfigurationViaRoot(sysfsName: String, configurationValue: Int): Boolean =
        runSu(
            listOf("echo $configurationValue > /sys/bus/usb/devices/$sysfsName/bConfigurationValue"),
            timeoutMillis = 5_000,
        )

    /** Runs `su -c <command...>`; true only when it exits 0 within [timeoutMillis]. */
    private fun runSu(command: List<String>, timeoutMillis: Long): Boolean = try {
        val process = ProcessBuilder(listOf("su", "-c", command.joinToString(" ")))
            .redirectErrorStream(true)
            .start()
        waitForProcess(process, timeoutMillis)
    } catch (_: Exception) {
        false
    } catch (_: Error) {
        // Issue #266 field report: a linkage error on an old release (e.g. an API 26
        // Process method resolving on API 22) must degrade to "su unusable", never
        // crash the connection flow that merely probed for root.
        false
    }

    /** Process.waitFor(long, TimeUnit) is API 26+; poll exitValue on older releases. */
    private fun waitForProcess(process: Process, timeoutMillis: Long): Boolean {
        if (realSdkInt() >= 26) {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroy()
                return false
            }
            return process.exitValue() == 0
        }
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (true) {
            try {
                return process.exitValue() == 0
            } catch (_: IllegalThreadStateException) {
                if (System.nanoTime() >= deadline) {
                    process.destroy()
                    return false
                }
                Thread.sleep(50)
            }
        }
    }
}

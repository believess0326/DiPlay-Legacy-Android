package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import android.os.ParcelFileDescriptor
import android.util.Log
import java.nio.ByteBuffer

/**
 * Raw usbfs transport for the direct mode (issue #266 phase 2): a [SharedUsbConnection]
 * over a /dev/bus/usb node fd obtained from [DirectUsbAccess], with every transfer
 * going through [UsbfsIoNative] ioctls instead of the framework.
 *
 * There is no UsbRequest machinery here: reads are synchronous bulk ioctls, which is
 * exactly how the wired stack already runs below API 24. [supportsAsyncRequests] is
 * false so consumers pick their synchronous paths.
 *
 * Reference counting mirrors [SharedUsbDeviceConnection]: the session retains one
 * reference, NCM retains another, and the node fd closes at zero.
 */
internal class RawSharedUsbConnection private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val nodePath: String,
    private val onDiagnostic: (String) -> Unit,
) : SharedUsbConnection {
    override val fileDescriptor: Int = descriptor.fd
    override val frameworkConnection: UsbDeviceConnection? = null
    override val supportsAsyncRequests: Boolean = false

    private var references = 1
    private var closed = false
    private val transferLock = Any()

    override fun controlTransfer(
        requestType: Int, request: Int, value: Int, index: Int,
        buffer: ByteArray?, length: Int, timeoutMillis: Int,
    ): Int {
        if (length < 0) return -1
        val staging = ByteBuffer.allocateDirect(maxOf(length, 1))
        val isIn = requestType and 0x80 != 0
        if (!isIn && buffer != null && length > 0) {
            staging.put(buffer, 0, minOf(length, buffer.size))
            staging.flip()
        }
        val transferred = synchronized(transferLock) {
            UsbfsIoNative.control(fileDescriptor, requestType, request, value, index, staging, length, timeoutMillis)
        }
        if (transferred > 0 && isIn && buffer != null) {
            staging.rewind()
            staging.get(buffer, 0, minOf(transferred, buffer.size))
        }
        return transferred
    }

    override fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeoutMillis: Int): Int =
        bulkTransfer(endpoint, buffer, 0, length, timeoutMillis)

    override fun bulkTransfer(
        endpoint: UsbEndpoint, buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int,
    ): Int {
        if (offset < 0 || length < 0 || offset + length > buffer.size) return -1
        val isIn = endpoint.address and 0x80 != 0
        val staging = ByteBuffer.allocateDirect(maxOf(length, 1))
        if (!isIn && length > 0) {
            staging.put(buffer, offset, length)
            staging.flip()
        }
        val transferred = synchronized(transferLock) {
            UsbfsIoNative.bulk(fileDescriptor, endpoint.address, staging, length, timeoutMillis)
        }
        if (transferred > 0 && isIn) {
            staging.rewind()
            staging.get(buffer, offset, minOf(transferred, length))
        }
        return transferred
    }

    override fun claimInterface(usbInterface: UsbInterface, force: Boolean): Boolean =
        UsbfsIoNative.claimInterface(fileDescriptor, usbInterface.id) == 0

    override fun releaseInterface(usbInterface: UsbInterface): Boolean =
        UsbfsIoNative.releaseInterface(fileDescriptor, usbInterface.id) == 0

    override fun setInterface(usbInterface: UsbInterface): Boolean =
        UsbfsIoNative.setInterface(
            fileDescriptor, usbInterface.id, IphoneCarPlayConfiguration.alternateSetting(usbInterface),
        ) == 0

    override fun queue(request: UsbRequest, buffer: ByteBuffer): Boolean =
        throw UnsupportedOperationException("raw usbfs connection has no UsbRequest path; use synchronous reads")

    override fun await(request: UsbRequest, timeoutMillis: Long): UsbRequest =
        throw UnsupportedOperationException("raw usbfs connection has no UsbRequest path; use synchronous reads")

    override fun forget(request: UsbRequest) = Unit

    @Synchronized
    override fun retain(): RawSharedUsbConnection {
        check(!closed) { "raw USB connection is already closed" }
        references += 1
        return this
    }

    @Synchronized
    override fun release() {
        if (closed) return
        references -= 1
        check(references >= 0) { "raw USB connection released too many times" }
        if (references == 0) {
            closed = true
            Log.i(TAG, "direct usbfs: closing node $nodePath fd=$fileDescriptor")
            onDiagnostic("direct usbfs: closing node $nodePath")
            runCatching { descriptor.close() }
        }
    }

    companion object {
        private const val TAG = "xcertplay-usb"

        /**
         * Opens [device]'s node (with the root grant when needed) and wraps it.
         * Returns null with the reason already logged when the node cannot be opened.
         */
        fun open(device: SysfsUsbDevice, onDiagnostic: (String) -> Unit): RawSharedUsbConnection? =
            when (val result = DirectUsbAccess.open(device, allowRootGrant = true)) {
                is DirectUsbAccess.OpenResult.Opened -> {
                    onDiagnostic(
                        "direct usbfs: node ${device.usbfsPath} opened fd=${result.descriptor.fd} " +
                            "rootGrant=${result.viaRootGrant}",
                    )
                    RawSharedUsbConnection(result.descriptor, device.usbfsPath, onDiagnostic)
                }
                is DirectUsbAccess.OpenResult.Denied -> {
                    onDiagnostic("direct usbfs: open denied: ${result.reason}")
                    null
                }
                DirectUsbAccess.OpenResult.NotPresent -> {
                    onDiagnostic("direct usbfs: node ${device.usbfsPath} not present")
                    null
                }
            }
    }
}

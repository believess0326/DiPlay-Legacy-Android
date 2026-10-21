package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

/**
 * Direct-mode descriptor discovery (issue #266) over a fake seam transport: the same
 * bytes an iPhone answers with must parse into the CarPlay configuration the
 * framework inspector would have chosen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RawUsbDescriptorsTest {

    private class FakeConnection(
        private val deviceDescriptor: ByteArray,
        private val configBlobs: Map<Int, ByteArray>,
    ) : SharedUsbConnection {
        override val fileDescriptor: Int = 42
        override val frameworkConnection: UsbDeviceConnection? = null
        override val supportsAsyncRequests: Boolean = false

        override fun controlTransfer(
            requestType: Int, request: Int, value: Int, index: Int,
            buffer: ByteArray?, length: Int, timeoutMillis: Int,
        ): Int {
            val source = when {
                value == 0x0100 -> deviceDescriptor
                value and 0xff00 == 0x0200 -> configBlobs[value and 0xff] ?: return -1
                else -> return -1
            }
            if (buffer == null) return -1
            val count = minOf(length, source.size)
            System.arraycopy(source, 0, buffer, 0, count)
            return count
        }

        override fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeoutMillis: Int): Int = -1
        override fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int = -1
        override fun claimInterface(usbInterface: UsbInterface, force: Boolean): Boolean = false
        override fun releaseInterface(usbInterface: UsbInterface): Boolean = false
        override fun setInterface(usbInterface: UsbInterface): Boolean = false
        override fun queue(request: UsbRequest, buffer: ByteBuffer): Boolean =
            throw UnsupportedOperationException("fake")
        override fun await(request: UsbRequest, timeoutMillis: Long): UsbRequest =
            throw UnsupportedOperationException("fake")
        override fun forget(request: UsbRequest) = Unit
        override fun retain(): SharedUsbConnection = this
        override fun release() = Unit
    }

    private fun endpoint(address: Int): ByteArray =
        byteArrayOf(7, 5, address.toByte(), 2, 0x00, 0x02, 0)

    private fun iface(number: Int, clazz: Int, sub: Int, proto: Int, endpoints: List<ByteArray>): ByteArray {
        val head = byteArrayOf(
            9, 4, number.toByte(), 0, endpoints.size.toByte(),
            clazz.toByte(), sub.toByte(), proto.toByte(), 0,
        )
        return endpoints.fold(head) { acc, ep -> acc + ep }
    }

    private fun config(id: Int, interfaces: List<ByteArray>): ByteArray {
        val total = 9 + interfaces.sumOf { it.size }
        val head = byteArrayOf(
            9, 2, (total and 0xff).toByte(), ((total shr 8) and 0xff).toByte(),
            interfaces.size.toByte(), id.toByte(), 0, 0xc0.toByte(), 250.toByte(),
        )
        return interfaces.fold(head) { acc, iface -> acc + iface }
    }

    private fun deviceDescriptor(configurationCount: Int): ByteArray = byteArrayOf(
        18, 1, 0x00, 0x02, 0, 0, 0, 64,
        0xac.toByte(), 0x05, 0xa8.toByte(), 0x12,
        0x00, 0x01, 0, 0, 0, configurationCount.toByte(),
    )

    private fun carPlayConfig(): ByteArray = config(
        6,
        listOf(
            iface(0, 0xff, 0xfe, 0x02, listOf(endpoint(0x04), endpoint(0x85))),
            iface(1, 0x02, 0x0d, 0x00, emptyList()),
            iface(2, 0xff, 0xfd, 0x01, emptyList()),
        ),
    )

    @Test fun readsDeviceInfoAndCarPlayConfiguration() {
        val connection = FakeConnection(deviceDescriptor(1), mapOf(0 to carPlayConfig()))
        val logs = mutableListOf<String>()
        val info = RawUsbDescriptors.readDeviceInfo(connection, logs::add)
        assertNotNull(info)
        assertEquals(0x05ac, info!!.vendorId)
        assertEquals(0x12a8, info.productId)
        assertEquals(1, info.configurationCount)

        val configurations = RawUsbDescriptors.readConfigurations(connection, info.configurationCount, logs::add)
        assertEquals(1, configurations.size)
        assertEquals(6, configurations[0].id)

        val chosen = IphoneCarPlayConfiguration.choose(configurations)
        assertNotNull(chosen)
        val mux = IphoneCarPlayConfiguration.usbMuxInterface(chosen!!)
        assertNotNull(mux)
        val endpoints = IphoneCarPlayConfiguration.usbMuxEndpoints(mux!!)
        assertNotNull(endpoints)
        assertEquals(0x04, endpoints!!.first.address)
        assertEquals(0x85, endpoints.second.address)
    }

    @Test fun nonCarPlayConfigurationIsNotChosen() {
        val plain = config(1, listOf(iface(0, 0x08, 0x06, 0x50, listOf(endpoint(0x81), endpoint(0x02)))))
        val connection = FakeConnection(deviceDescriptor(1), mapOf(0 to plain))
        val configurations = RawUsbDescriptors.readConfigurations(connection, 1) {}
        assertEquals(1, configurations.size)
        assertNull(IphoneCarPlayConfiguration.choose(configurations))
    }

    @Test fun unansweringDeviceYieldsNoDeviceInfo() {
        val connection = FakeConnection(ByteArray(0), emptyMap())
        assertNull(RawUsbDescriptors.readDeviceInfo(connection) {})
    }
}

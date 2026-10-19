package com.shilapi.xcertplay.usbbt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RfcommLayerTest {

    // Helper: calculate CRC-8 per TS 07.10
    private fun calcFcs(bytes: ByteArray, len: Int = bytes.size): Byte {
        var crc = 0xFF
        val end = minOf(len, bytes.size)
        for (i in 0 until end) {
            crc = crc xor (bytes[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x01 != 0) (crc ushr 1) xor 0xE0 else crc ushr 1
            }
        }
        return (0xFF - crc).toByte()
    }

    @Test
    fun testFcsUihCalculatedOverAddressAndControlOnly() {
        // DLCI 0, UIH (0xEF)
        val addr = 0x03.toByte() // dlci 0, CR=1, EA=1
        val ctrl = 0xEF.toByte()
        val header = byteArrayOf(addr, ctrl)
        val fcs = calcFcs(header, 2)

        // For UIH, FCS must match regardless of length or data payload
        val fullFrameWithData = byteArrayOf(addr, ctrl, 0x05.toByte(), 0x01, 0x02)
        assertEquals(fcs, calcFcs(fullFrameWithData, 2))
    }

    @Test
    fun testMccTypeEncoding() {
        // TS 07.10: Type octet has Type in bits 3-8, C/R in bit 2, EA in bit 1.
        // PN: Type = 0x20 (32). Command: (32 << 2) | 2 | 1 = 0x83 (131).
        val pnType = 0x20
        val pnCmdByte = (((pnType and 0x3F) shl 2) or 0x02 or 0x01).toByte()
        assertEquals(0x83.toByte(), pnCmdByte)

        // PN Response: (32 << 2) | 0 | 1 = 0x81 (129).
        val pnRspByte = (((pnType and 0x3F) shl 2) or 0x00 or 0x01).toByte()
        assertEquals(0x81.toByte(), pnRspByte)

        // MSC: Type = 0x38 (56). Command: (56 << 2) | 2 | 1 = 0xE3 (-29 / 227).
        val mscType = 0x38
        val mscCmdByte = (((mscType and 0x3F) shl 2) or 0x02 or 0x01).toByte()
        assertEquals(0xE3.toByte(), mscCmdByte)
    }

    @Test
    fun testMccTypeDecoding() {
        val pnWire = 0x83.toByte()
        val type = (pnWire.toInt() and 0xFF) ushr 2
        val isCommand = (pnWire.toInt() and 0x02) != 0
        assertEquals(0x20, type)
        assertTrue(isCommand)
    }

    @Test
    fun testBuildUihFrameWithCredits() {
        val dlci = 2
        val addr = ((dlci shl 2) or 0x02 or 0x01).toByte() // dlci 2, CR=1, EA=1 -> 0x0B
        val ctrl = (0xEF or 0x10).toByte() // UIH with P/F=1 -> 0xFF
        val payload = byteArrayOf(0x10, 0x20, 0x30)
        val len = ((payload.size shl 1) or 0x01).toByte() // 0x07
        val credits = 5

        val header = byteArrayOf(addr, ctrl, len)
        val fcs = calcFcs(header, 2) // UIH FCS is over first 2 bytes
        val frame = header + byteArrayOf(credits.toByte()) + payload + fcs

        // Verify structure
        assertEquals(addr, frame[0])
        assertEquals(ctrl, frame[1])
        assertEquals(len, frame[2])
        assertEquals(credits.toByte(), frame[3])
        assertEquals(0x10.toByte(), frame[4])
        assertEquals(0x20.toByte(), frame[5])
        assertEquals(0x30.toByte(), frame[6])
        assertEquals(fcs, frame[7])
    }
}

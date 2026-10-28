package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmPeakTest {
    @Test fun silenceReadsZero() {
        assertEquals(0, pcm16Peak(ByteArray(8), 0, 8))
    }

    @Test fun fullScalePositiveAndNegative() {
        // 32767 = FF 7F, -32768 = 00 80 (little-endian)
        assertEquals(32767, pcm16Peak(byteArrayOf(0xFF.toByte(), 0x7F.toByte()), 0, 2))
        assertEquals(32768, pcm16Peak(byteArrayOf(0x00, 0x80.toByte()), 0, 2))
    }

    @Test fun peakIsMaxAcrossSamplesAndRespectsOffset() {
        // Samples: 100, -20000 (0xB1E0 -> E0 B1), 3000 (0x0BB8 -> B8 0B)
        val data = byteArrayOf(100, 0, 0xE0.toByte(), 0xB1.toByte(), 0xB8.toByte(), 0x0B.toByte())
        assertEquals(20000, pcm16Peak(data, 0, 6))
        assertEquals(20000, pcm16Peak(data, 2, 4))
        assertEquals(3000, pcm16Peak(data, 4, 2))
    }

    @Test fun trailingOddByteIsIgnored() {
        assertEquals(100, pcm16Peak(byteArrayOf(100, 0, 0x55), 0, 3))
        assertEquals(0, pcm16Peak(byteArrayOf(0x55), 0, 1))
        assertEquals(0, pcm16Peak(ByteArray(0), 0, 0))
    }
}

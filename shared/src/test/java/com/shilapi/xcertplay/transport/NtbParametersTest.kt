package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtbParametersTest {
    /**
     * A healthy iPhone's reply, reconstructed from a field report (issue #307)
     * whose shifted field reads pin down every byte: wLength=28, formats=0x3,
     * dwNtbInMaxSize=16384, IN divisor 4 / remainder 0 / alignment 4,
     * reserved=0x0c00, dwNtbOutMaxSize=32768, OUT divisor 4 / remainder 0 /
     * alignment 4, maxDatagrams=32. The old probe read the OUT block 2 bytes
     * early and reported outMaxBytes=2147221504, outDivisor=0 for this input.
     */
    private fun healthyReply(): ByteArray {
        val bytes = ByteArray(28)
        fun put16(offset: Int, value: Int) {
            bytes[offset] = (value and 0xff).toByte()
            bytes[offset + 1] = ((value shr 8) and 0xff).toByte()
        }
        fun put32(offset: Int, value: Int) {
            put16(offset, value and 0xffff)
            put16(offset + 2, (value shr 16) and 0xffff)
        }
        put16(0, 28)
        put16(2, 0x3)
        put32(4, 16384)
        put16(8, 4)
        put16(10, 0)
        put16(12, 4)
        put16(14, 0x0c00)
        put32(16, 32768)
        put16(20, 4)
        put16(22, 0)
        put16(24, 4)
        put16(26, 32)
        return bytes
    }

    @Test fun healthyReplyParsesTheRealOutFields() {
        val described = NcmUsbBridge.describeNtbParameters(healthyReply(), 28)
        assertTrue(described, described.contains("outMaxBytes=32768"))
        assertTrue(described, described.contains("outDivisor=4"))
        assertTrue(described, described.contains("outRemainder=0"))
        assertTrue(described, described.contains("outAlignment=4"))
        assertTrue(described, described.contains("maxDatagrams=32"))
        assertTrue(described, !described.contains("IMPLAUSIBLE"))
    }

    @Test fun zeroDivisorIsFlaggedImplausible() {
        val bytes = healthyReply()
        bytes[20] = 0
        bytes[21] = 0
        val described = NcmUsbBridge.describeNtbParameters(bytes, 28)
        assertTrue(described, described.contains("outDivisor=0"))
        assertTrue(described, described.contains("IMPLAUSIBLE"))
    }

    @Test fun truncatedReplyIsReportedWithRawBytes() {
        val described = NcmUsbBridge.describeNtbParameters(healthyReply(), 10)
        assertTrue(described, described.startsWith("truncated (transferred=10)"))
        assertTrue(described, described.contains("raw="))
    }

    @Test fun failedTransferIsUnavailable() {
        assertEquals(
            "unavailable (transferred=-1)",
            NcmUsbBridge.describeNtbParameters(ByteArray(28), -1),
        )
    }
}

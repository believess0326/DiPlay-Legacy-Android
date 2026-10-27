package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MicrophoneResampleTest {

    @Test
    fun downsample48To24AveragesSamplePairs() {
        // 4 samples at 48kHz (8 bytes): [1000, 3000, -2000, -4000]
        val input = ByteArray(8)
        fun putShort(arr: ByteArray, idx: Int, v: Short) {
            arr[idx] = (v.toInt() and 0xFF).toByte()
            arr[idx + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
        }
        fun getShort(arr: ByteArray, idx: Int): Short {
            return ((arr[idx].toInt() and 0xFF) or (arr[idx + 1].toInt() shl 8)).toShort()
        }

        putShort(input, 0, 1000)
        putShort(input, 2, 3000)
        putShort(input, 4, -2000)
        putShort(input, 6, -4000)

        val output = ByteArray(4)
        val written = MicrophoneUplink.downsample48To24(input, input.size, output)
        assertEquals(4, written)
        assertEquals(2000.toShort(), getShort(output, 0))
        assertEquals((-3000).toShort(), getShort(output, 2))
    }

    @Test
    fun upsample16To24InterpolatesPairs() {
        // 2 samples at 16kHz (4 bytes): [1000, 3000] -> 3 samples at 24kHz: [1000, 2000, 3000]
        val input = ByteArray(4)
        fun putShort(arr: ByteArray, idx: Int, v: Short) {
            arr[idx] = (v.toInt() and 0xFF).toByte()
            arr[idx + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
        }
        fun getShort(arr: ByteArray, idx: Int): Short {
            return ((arr[idx].toInt() and 0xFF) or (arr[idx + 1].toInt() shl 8)).toShort()
        }

        putShort(input, 0, 1000)
        putShort(input, 2, 3000)

        val output = ByteArray(6)
        val written = MicrophoneUplink.upsample16To24(input, input.size, output)
        assertEquals(6, written)
        assertEquals(1000.toShort(), getShort(output, 0))
        assertEquals(2000.toShort(), getShort(output, 2))
        assertEquals(3000.toShort(), getShort(output, 4))
    }
}

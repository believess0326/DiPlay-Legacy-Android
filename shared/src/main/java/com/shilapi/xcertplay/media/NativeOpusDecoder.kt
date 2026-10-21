package com.shilapi.xcertplay.media

import android.util.Log
import java.io.Closeable

/**
 * Bundled libopus decoder (issue #272): some legacy ROMs expose no Opus decoder
 * through MediaCodec at all — neither a hardware one nor OMX.google — which left
 * phone calls silent end to end. Shares the opus_microphone library with the
 * microphone encoder fallback; only the decode entry points are used here.
 */
internal class NativeOpusDecoder private constructor(private var handle: Long) : Closeable {
    val available: Boolean get() = handle != 0L

    /** Decodes one Opus access unit to PCM16LE bytes; null when undecodable. */
    @Synchronized fun decode(packet: ByteArray): ByteArray? {
        if (!available) return null
        return runCatching { nativeDecode(handle, packet) }
            .onFailure { Log.w(TAG, "Software Opus decode failed", it) }
            .getOrNull()
    }

    @Synchronized override fun close() {
        val old = handle
        handle = 0L
        if (old != 0L) nativeDestroy(old)
    }

    companion object {
        private const val TAG = "xcertplay-usb"
        private val loaded: Boolean by lazy {
            runCatching { System.loadLibrary("opus_microphone"); true }
                .onFailure { Log.w(TAG, "Software Opus library unavailable", it) }
                .getOrDefault(false)
        }

        fun create(sampleRate: Int, channels: Int): NativeOpusDecoder? {
            if (!loaded) return null
            val handle = runCatching { nativeCreate(sampleRate, channels) }
                .onFailure { Log.w(TAG, "Software Opus decoder initialization failed", it) }
                .getOrDefault(0L)
            return if (handle != 0L) NativeOpusDecoder(handle) else null
        }

        @JvmStatic private external fun nativeCreate(sampleRate: Int, channels: Int): Long
        @JvmStatic private external fun nativeDecode(handle: Long, packet: ByteArray): ByteArray?
        @JvmStatic private external fun nativeDestroy(handle: Long)
    }
}

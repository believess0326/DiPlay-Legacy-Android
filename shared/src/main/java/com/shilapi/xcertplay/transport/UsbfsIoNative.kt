package com.shilapi.xcertplay.transport

import java.nio.ByteBuffer

/**
 * JNI bridge for the direct usbfs transfer primitives (issue #266). The native side
 * lives in the existing `legacy_ncm_claim` library alongside the claim helpers.
 *
 * Contract: every call returns a byte count (>= 0) for transfers, 0 for success on
 * configuration/interface calls, or a negative errno / positive errno respectively —
 * never throws for an ioctl failure. A missing library (LinkageError) degrades to
 * [ERRNO_UNAVAILABLE] so callers can fall back cleanly.
 */
internal object UsbfsIoNative {
    const val ERRNO_UNAVAILABLE = -1

    private val libraryReady: Boolean = try {
        System.loadLibrary("legacy_ncm_claim")
        true
    } catch (_: LinkageError) {
        false
    }

    private external fun controlTransfer(
        fd: Int, requestType: Int, request: Int, value: Int, index: Int,
        buffer: ByteBuffer, length: Int, timeoutMillis: Int,
    ): Int

    private external fun bulkTransfer(
        fd: Int, endpoint: Int, buffer: ByteBuffer, length: Int, timeoutMillis: Int,
    ): Int

    private external fun setConfiguration(fd: Int, configuration: Int): Int
    private external fun releaseInterface(fd: Int, interfaceId: Int): Int

    /** Bytes transferred, or a negative errno. [buffer] must be a direct buffer. */
    fun control(fd: Int, requestType: Int, request: Int, value: Int, index: Int,
                buffer: ByteBuffer, length: Int, timeoutMillis: Int): Int =
        if (!libraryReady) ERRNO_UNAVAILABLE
        else try {
            controlTransfer(fd, requestType, request, value, index, buffer, length, timeoutMillis)
        } catch (_: LinkageError) {
            ERRNO_UNAVAILABLE
        }

    /** Bytes transferred, or a negative errno. [buffer] must be a direct buffer. */
    fun bulk(fd: Int, endpoint: Int, buffer: ByteBuffer, length: Int, timeoutMillis: Int): Int =
        if (!libraryReady) ERRNO_UNAVAILABLE
        else try {
            bulkTransfer(fd, endpoint, buffer, length, timeoutMillis)
        } catch (_: LinkageError) {
            ERRNO_UNAVAILABLE
        }

    /** 0 on success, or a positive errno. */
    fun setConfiguration(fd: Int, configuration: Int): Int =
        if (!libraryReady) ERRNO_UNAVAILABLE
        else try {
            setConfiguration(fd, configuration)
        } catch (_: LinkageError) {
            ERRNO_UNAVAILABLE
        }

    /** 0 on success, or a positive errno. */
    fun releaseInterface(fd: Int, interfaceId: Int): Int =
        if (!libraryReady) ERRNO_UNAVAILABLE
        else try {
            releaseInterface(fd, interfaceId)
        } catch (_: LinkageError) {
            ERRNO_UNAVAILABLE
        }
}

package com.shilapi.xcertplay.airplay

import org.bouncycastle.crypto.InvalidCipherTextException
import java.util.concurrent.atomic.AtomicLong

/**
 * Native ChaCha20-Poly1305 for the CarPlay data streams (issue #249).
 *
 * Android 4.2 has no `Cipher.updateAAD(byte[])`, and every screen frame is sealed
 * with the 128-byte header as AAD, so [AirPlayCrypto.platformChacha] can never
 * serve them: each frame fell through to BouncyCastle's Java implementation at
 * roughly 0.2 MB/s, one 18 KB frame taking ~95 ms. Dalvik executes the 64-bit
 * limb arithmetic in Poly1305 as interpreted code and the per-frame allocations
 * add GC pauses, so no amount of Java tuning reaches 30 fps. The same work in C
 * costs a fraction of a millisecond.
 *
 * The library is optional: when it is missing or fails its own RFC 8439
 * self-test, every caller keeps using the JCE/BouncyCastle paths unchanged.
 */
internal object NativeChaCha {
    /** Reported in diagnostics so a log line proves which path decrypted the frame. */
    const val NAME = "native"

    private const val LIBRARY = "xcertplay_chacha"
    private const val TAG_SIZE = 16
    private const val RESULT_AUTH_FAILED = -1

    /** Frames opened by the native path; a stuck zero in the log means the fallback is in use. */
    val opens = AtomicLong(0)

    private val libraryLoaded: Boolean by lazy {
        runCatching { System.loadLibrary(LIBRARY) }.isSuccess
    }

    /**
     * True once the library loaded *and* reproduced the RFC 8439 vector. A vendor ROM
     * with an unusable ABI, or a mistake in the C code, therefore degrades to the
     * previous behaviour instead of breaking video.
     */
    val available: Boolean by lazy { libraryLoaded && selfTest() }

    /**
     * Decrypts [data] in place; it holds the ciphertext followed by its 16-byte tag.
     *
     * @return the plaintext, or null when this call should use another implementation
     *   (library absent, buffer too short, native rejection).
     * @throws InvalidCipherTextException when the tag does not match, matching what the
     *   BouncyCastle fallback throws so [ScreenStream] keeps counting corrupt frames.
     */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, data: ByteArray): ByteArray? {
        val ciphertextLength = data.size - TAG_SIZE
        if (ciphertextLength < 0) return null
        val plaintextLength = try {
            nativeOpen(key, nonce, aad, data, ciphertextLength)
        } catch (_: Throwable) {
            return null
        }
        if (plaintextLength == RESULT_AUTH_FAILED) {
            throw InvalidCipherTextException("mac check in ChaCha20Poly1305 failed")
        }
        if (plaintextLength < 0 || plaintextLength > ciphertextLength) return null
        opens.incrementAndGet()
        return data.copyOf(plaintextLength)
    }

    /**
     * Seals and opens the RFC 8439 §2.8.2 vector once. The expected tag is the one
     * published in the RFC, so a passing run also pins the C code to the standard
     * rather than merely making it self-consistent with BouncyCastle.
     */
    private fun selfTest(): Boolean = try {
        val plaintext = SELF_TEST_PLAINTEXT.toByteArray(Charsets.US_ASCII)
        val sealed = AirPlayCrypto.chachaSealBouncyCastle(SELF_TEST_KEY, SELF_TEST_NONCE, plaintext, SELF_TEST_AAD)
        val tagMatchesRfc = sealed.copyOfRange(sealed.size - TAG_SIZE, sealed.size).toHex() == SELF_TEST_TAG
        val buffer = sealed.copyOf()
        val opened = nativeOpen(SELF_TEST_KEY, SELF_TEST_NONCE, SELF_TEST_AAD, buffer, sealed.size - TAG_SIZE)
        tagMatchesRfc && opened == plaintext.size && buffer.copyOf(opened).contentEquals(plaintext)
    } catch (_: Throwable) {
        false
    }

    /** @return the plaintext length, [RESULT_AUTH_FAILED], or another negative value to fall back. */
    private external fun nativeOpen(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        data: ByteArray,
        ciphertextLength: Int,
    ): Int

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun hex(text: String) = ByteArray(text.length / 2) {
        text.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    // RFC 8439 §2.8.2 "Ladies and Gentlemen" vector, the same one the unit tests use.
    private val SELF_TEST_KEY = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
    private val SELF_TEST_NONCE = hex("070000004041424344454647")
    private val SELF_TEST_AAD = hex("50515253c0c1c2c3c4c5c6c7")
    private val SELF_TEST_TAG = "1ae10b594f09e26a7e902ecbd0600691"
    private const val SELF_TEST_PLAINTEXT =
        "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, " +
            "sunscreen would be it."
}

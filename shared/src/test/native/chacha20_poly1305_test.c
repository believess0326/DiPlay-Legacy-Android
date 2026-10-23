/*
 * Host-side checks for the native ChaCha20-Poly1305 used by the CarPlay screen stream.
 *
 * Build with an Android NDK clang target, then run the executable with adb shell, the
 * same way usb_host_compat_test.c is used:
 *
 *   $NDK/toolchains/llvm/prebuilt/<host>/bin/armv7a-linux-androideabi19-clang \
 *       -O2 -o chacha_test shared/src/test/native/chacha20_poly1305_test.c
 *   adb push chacha_test /data/local/tmp/ && adb shell /data/local/tmp/chacha_test
 */

#include <stdio.h>
#include <string.h>

#include "../../main/jni/chacha20_poly1305.c"

static int failures;

static void check(int condition, const char *message) {
    if (condition) return;
    printf("FAIL %s\n", message);
    failures++;
}

static void from_hex(const char *hex, unsigned char *out, size_t length) {
    size_t index;
    for (index = 0; index < length; index++) {
        unsigned int value = 0;
        sscanf(hex + 2 * index, "%2x", &value);
        out[index] = (unsigned char)value;
    }
}

static int differs(const unsigned char *left, const unsigned char *right, size_t length) {
    size_t index;
    for (index = 0; index < length; index++) {
        if (left[index] != right[index]) return 1;
    }
    return 0;
}

/* RFC 8439 §2.8.2, the vector the Java unit tests also pin. */
static void rfc8439_vector(void) {
    static const char *key_hex = "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f";
    static const char *nonce_hex = "070000004041424344454647";
    static const char *aad_hex = "50515253c0c1c2c3c4c5c6c7";
    static const char *plaintext =
        "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, "
        "sunscreen would be it.";
    static const char *ciphertext_hex =
        "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6"
        "3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36"
        "92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc"
        "3ff4def08e4b7a9de576d26586cec64b6116";
    static const char *tag_hex = "1ae10b594f09e26a7e902ecbd0600691";

    unsigned char key[CPP_KEY_SIZE];
    unsigned char nonce[CPP_NONCE_SIZE];
    unsigned char aad[12];
    unsigned char expected[114];
    unsigned char expected_tag[CPP_TAG_SIZE];
    unsigned char sealed[114 + CPP_TAG_SIZE];
    unsigned char opened[114 + CPP_TAG_SIZE];
    size_t plaintext_length = strlen(plaintext);

    from_hex(key_hex, key, sizeof(key));
    from_hex(nonce_hex, nonce, sizeof(nonce));
    from_hex(aad_hex, aad, sizeof(aad));
    from_hex(ciphertext_hex, expected, sizeof(expected));
    from_hex(tag_hex, expected_tag, sizeof(expected_tag));

    check(plaintext_length == sizeof(expected), "vector plaintext length");
    check(cpp_aead_seal(key, nonce, aad, sizeof(aad),
                        (const unsigned char *)plaintext, plaintext_length, sealed) == 0,
          "seal returns success");
    check(!differs(sealed, expected, sizeof(expected)), "sealed ciphertext matches RFC 8439");
    check(!differs(sealed + plaintext_length, expected_tag, sizeof(expected_tag)), "sealed tag matches RFC 8439");

    memcpy(opened, sealed, sizeof(sealed));
    check(cpp_aead_open(key, nonce, aad, sizeof(aad), opened, sizeof(expected)) == 0, "open returns success");
    check(!differs(opened, (const unsigned char *)plaintext, plaintext_length), "opened plaintext matches");

    /* A changed ciphertext, tag or AAD must fail without touching the buffer. */
    memcpy(opened, sealed, sizeof(sealed));
    opened[7] ^= 0x01;
    check(cpp_aead_open(key, nonce, aad, sizeof(aad), opened, sizeof(expected)) == -1, "changed ciphertext rejected");
    check(opened[7] == (sealed[7] ^ 0x01), "rejected frame is left untouched");

    memcpy(opened, sealed, sizeof(sealed));
    opened[sizeof(expected) + 3] ^= 0x80;
    check(cpp_aead_open(key, nonce, aad, sizeof(aad), opened, sizeof(expected)) == -1, "changed tag rejected");

    memcpy(opened, sealed, sizeof(sealed));
    aad[0] ^= 0x01;
    check(cpp_aead_open(key, nonce, aad, sizeof(aad), opened, sizeof(expected)) == -1, "changed aad rejected");
    aad[0] ^= 0x01;
}

/* Round-trips every length class the screen stream can produce, with and without AAD. */
static void round_trip(void) {
    static const size_t lengths[] = {0, 1, 15, 16, 17, 63, 64, 65, 127, 128, 129, 1400, 18200, 65537};
    unsigned char key[CPP_KEY_SIZE];
    unsigned char nonce[CPP_NONCE_SIZE];
    unsigned char aad[128];
    unsigned char plaintext[65537];
    unsigned char sealed[65537 + CPP_TAG_SIZE];
    unsigned char opened[65537 + CPP_TAG_SIZE];
    size_t index;
    size_t position;
    unsigned int state = 0x12345678u;

    for (position = 0; position < sizeof(key); position++) {
        state = state * 1103515245u + 12345u;
        key[position] = (unsigned char)(state >> 16);
    }
    for (position = 0; position < sizeof(nonce); position++) {
        state = state * 1103515245u + 12345u;
        nonce[position] = (unsigned char)(state >> 16);
    }
    for (position = 0; position < sizeof(aad); position++) {
        state = state * 1103515245u + 12345u;
        aad[position] = (unsigned char)(state >> 16);
    }
    for (position = 0; position < sizeof(plaintext); position++) {
        state = state * 1103515245u + 12345u;
        plaintext[position] = (unsigned char)(state >> 16);
    }

    for (index = 0; index < sizeof(lengths) / sizeof(lengths[0]); index++) {
        const size_t length = lengths[index];
        const size_t aad_length = (index % 2 == 0) ? 0 : 128;
        char message[64];

        snprintf(message, sizeof(message), "round trip %u bytes aad=%u", (unsigned)length, (unsigned)aad_length);
        check(cpp_aead_seal(key, nonce, aad, aad_length, plaintext, length, sealed) == 0, message);
        memcpy(opened, sealed, length + CPP_TAG_SIZE);
        check(cpp_aead_open(key, nonce, aad, aad_length, opened, length) == 0, message);
        check(!differs(opened, plaintext, length), message);
    }
}

int main(void) {
    rfc8439_vector();
    round_trip();
    if (failures != 0) {
        printf("%d check(s) failed\n", failures);
        return 1;
    }
    printf("chacha20_poly1305: all checks passed\n");
    return 0;
}

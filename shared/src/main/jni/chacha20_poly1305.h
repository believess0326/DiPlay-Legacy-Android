#ifndef XCERTPLAY_CHACHA20_POLY1305_H
#define XCERTPLAY_CHACHA20_POLY1305_H

#include <stddef.h>
#include <stdint.h>

#define CPP_KEY_SIZE 32
#define CPP_NONCE_SIZE 12
#define CPP_TAG_SIZE 16

/*
 * ChaCha20-Poly1305 AEAD (RFC 8439), used for every CarPlay video frame.
 *
 * The legacy head units this port targets run Dalvik on Cortex-A7, where the
 * pure-Java BouncyCastle path decrypts at ~0.2 MB/s (issue #249): one 18 KB
 * frame takes ~95 ms, so the screen stutters and A/V drifts. These routines
 * are plain C so the same work costs well under a millisecond.
 *
 * Nothing here touches JNI, so shared/src/test/native can host-test the exact
 * code that ships in the APK.
 */

/* Seals [plaintext] into [out], which must hold plaintext_len + CPP_TAG_SIZE bytes. */
int cpp_aead_seal(const unsigned char key[CPP_KEY_SIZE],
                  const unsigned char nonce[CPP_NONCE_SIZE],
                  const unsigned char *aad, size_t aad_len,
                  const unsigned char *plaintext, size_t plaintext_len,
                  unsigned char *out);

/*
 * Opens [buf] in place; it holds ciphertext_len bytes followed by the 16-byte tag.
 * Returns 0 on success, or -1 when the tag does not match (nothing was decrypted).
 */
int cpp_aead_open(const unsigned char key[CPP_KEY_SIZE],
                  const unsigned char nonce[CPP_NONCE_SIZE],
                  const unsigned char *aad, size_t aad_len,
                  unsigned char *buf, size_t ciphertext_len);

#endif

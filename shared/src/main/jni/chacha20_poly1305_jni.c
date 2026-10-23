/*
 * JNI bridge for chacha20_poly1305.c. Kept apart from the cipher so the same C
 * code can be built and tested on a host without an Android runtime.
 */

#include <jni.h>

#include "chacha20_poly1305.h"

/* CarPlay frame headers are 128 bytes and control AADs are a few bytes. */
#define AAD_STACK_SIZE 1024

#define RESULT_OK 0
#define RESULT_AUTH_FAILED (-1)
#define RESULT_UNAVAILABLE (-2)

/*
 * Decrypts data[0, ciphertextLength) in place and returns the plaintext length.
 * The 16-byte tag must follow the ciphertext. A tag mismatch returns
 * RESULT_AUTH_FAILED and leaves the buffer untouched; anything the native path
 * cannot handle returns RESULT_UNAVAILABLE so the caller falls back to Java.
 */
JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_airplay_NativeChaCha_nativeOpen(JNIEnv *env, jobject self,
                                                           jbyteArray key_array,
                                                           jbyteArray nonce_array,
                                                           jbyteArray aad_array,
                                                           jbyteArray data_array,
                                                           jint ciphertext_length) {
    unsigned char key[CPP_KEY_SIZE];
    unsigned char nonce[CPP_NONCE_SIZE];
    unsigned char aad_stack[AAD_STACK_SIZE];
    unsigned char *aad = aad_stack;
    unsigned char *data;
    jsize aad_length = 0;
    int result;

    (void)self;

    if (key_array == NULL || nonce_array == NULL || data_array == NULL) return RESULT_UNAVAILABLE;
    if ((*env)->GetArrayLength(env, key_array) < CPP_KEY_SIZE) return RESULT_UNAVAILABLE;
    if ((*env)->GetArrayLength(env, nonce_array) < CPP_NONCE_SIZE) return RESULT_UNAVAILABLE;
    if (ciphertext_length < 0) return RESULT_UNAVAILABLE;
    if ((*env)->GetArrayLength(env, data_array) < ciphertext_length + CPP_TAG_SIZE) {
        return RESULT_UNAVAILABLE;
    }

    (*env)->GetByteArrayRegion(env, key_array, 0, CPP_KEY_SIZE, (jbyte *)key);
    (*env)->GetByteArrayRegion(env, nonce_array, 0, CPP_NONCE_SIZE, (jbyte *)nonce);

    if (aad_array != NULL) {
        aad_length = (*env)->GetArrayLength(env, aad_array);
        if (aad_length > AAD_STACK_SIZE) return RESULT_UNAVAILABLE;
        if (aad_length > 0) (*env)->GetByteArrayRegion(env, aad_array, 0, aad_length, (jbyte *)aad);
    }

    data = (unsigned char *)(*env)->GetPrimitiveArrayCritical(env, data_array, NULL);
    if (data == NULL) return RESULT_UNAVAILABLE;
    result = cpp_aead_open(key, nonce, aad, (size_t)aad_length, data, (size_t)ciphertext_length);
    (*env)->ReleasePrimitiveArrayCritical(env, data_array, data, 0);

    return result == 0 ? (jint)ciphertext_length : RESULT_AUTH_FAILED;
}

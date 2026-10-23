/*
 * ChaCha20-Poly1305 (RFC 8439) for the CarPlay data streams.
 *
 * Written for 32-bit ARM cores without a 64x64->128 multiply: Poly1305 uses
 * five 26-bit limbs and 64-bit intermediates, which the NDK's clang turns into
 * ordinary 32x32->64 multiplies. Constant time on the tag comparison; the rest
 * needs no secret-dependent branching because everything is driven by lengths.
 */

#include "chacha20_poly1305.h"

#include <string.h>

#define ROTL32(value, bits) (((value) << (bits)) | ((value) >> (32 - (bits))))

#define QUARTERROUND(a, b, c, d)      \
    do {                              \
        a += b; d ^= a; d = ROTL32(d, 16); \
        c += d; b ^= c; b = ROTL32(b, 12); \
        a += b; d ^= a; d = ROTL32(d, 8);  \
        c += d; b ^= c; b = ROTL32(b, 7);  \
    } while (0)

static uint32_t load32_le(const unsigned char *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static void store32_le(unsigned char *p, uint32_t value) {
    p[0] = (unsigned char)value;
    p[1] = (unsigned char)(value >> 8);
    p[2] = (unsigned char)(value >> 16);
    p[3] = (unsigned char)(value >> 24);
}

static void store64_le(unsigned char *p, uint64_t value) {
    store32_le(p, (uint32_t)value);
    store32_le(p + 4, (uint32_t)(value >> 32));
}

/* ---------------------------------------------------------------- ChaCha20 */

static void chacha20_state(uint32_t state[16], const unsigned char key[CPP_KEY_SIZE],
                           const unsigned char nonce[CPP_NONCE_SIZE], uint32_t counter) {
    int index;
    state[0] = 0x61707865u;
    state[1] = 0x3320646eu;
    state[2] = 0x79622d32u;
    state[3] = 0x6b206574u;
    for (index = 0; index < 8; index++) state[4 + index] = load32_le(key + 4 * index);
    state[12] = counter;
    state[13] = load32_le(nonce + 0);
    state[14] = load32_le(nonce + 4);
    state[15] = load32_le(nonce + 8);
}

static void chacha20_block(unsigned char out[64], const uint32_t input[16]) {
    uint32_t x[16];
    int round;
    int index;
    memcpy(x, input, sizeof(x));
    for (round = 0; round < 10; round++) {
        QUARTERROUND(x[0], x[4], x[8], x[12]);
        QUARTERROUND(x[1], x[5], x[9], x[13]);
        QUARTERROUND(x[2], x[6], x[10], x[14]);
        QUARTERROUND(x[3], x[7], x[11], x[15]);
        QUARTERROUND(x[0], x[5], x[10], x[15]);
        QUARTERROUND(x[1], x[6], x[11], x[12]);
        QUARTERROUND(x[2], x[7], x[8], x[13]);
        QUARTERROUND(x[3], x[4], x[9], x[14]);
    }
    for (index = 0; index < 16; index++) store32_le(out + 4 * index, x[index] + input[index]);
}

/* XORs [length] bytes between [in] and [out], both of which may be the same buffer. */
static void chacha20_xor(unsigned char *out, const unsigned char *in, size_t length,
                         const unsigned char key[CPP_KEY_SIZE],
                         const unsigned char nonce[CPP_NONCE_SIZE], uint32_t counter) {
    unsigned char block[64];
    size_t offset = 0;
    while (offset < length) {
        uint32_t state[16];
        size_t chunk = length - offset;
        size_t index;
        if (chunk > sizeof(block)) chunk = sizeof(block);
        chacha20_state(state, key, nonce, counter++);
        chacha20_block(block, state);
        for (index = 0; index < chunk; index++) out[offset + index] = in[offset + index] ^ block[index];
        offset += chunk;
    }
}

/* --------------------------------------------------------------- Poly1305 */

typedef struct {
    uint32_t r[5];
    uint32_t h[5];
    uint32_t pad[4];
    unsigned char buffer[16];
    size_t leftover;
} poly1305_state;

static void poly1305_init(poly1305_state *ctx, const unsigned char key[32]) {
    ctx->r[0] = load32_le(key + 0) & 0x3ffffffu;
    ctx->r[1] = (load32_le(key + 3) >> 2) & 0x3ffff03u;
    ctx->r[2] = (load32_le(key + 6) >> 4) & 0x3ffc0ffu;
    ctx->r[3] = (load32_le(key + 9) >> 6) & 0x3f03fffu;
    ctx->r[4] = (load32_le(key + 12) >> 8) & 0x00fffffu;

    ctx->h[0] = 0;
    ctx->h[1] = 0;
    ctx->h[2] = 0;
    ctx->h[3] = 0;
    ctx->h[4] = 0;

    ctx->pad[0] = load32_le(key + 16);
    ctx->pad[1] = load32_le(key + 20);
    ctx->pad[2] = load32_le(key + 24);
    ctx->pad[3] = load32_le(key + 28);

    ctx->leftover = 0;
}

static void poly1305_blocks(poly1305_state *ctx, const unsigned char *message, size_t bytes) {
    const uint32_t hibit = (uint32_t)1 << 24; /* 2^128 */
    uint32_t r0 = ctx->r[0], r1 = ctx->r[1], r2 = ctx->r[2], r3 = ctx->r[3], r4 = ctx->r[4];
    uint32_t s1 = r1 * 5, s2 = r2 * 5, s3 = r3 * 5, s4 = r4 * 5;
    uint32_t h0 = ctx->h[0], h1 = ctx->h[1], h2 = ctx->h[2], h3 = ctx->h[3], h4 = ctx->h[4];

    while (bytes >= 16) {
        uint64_t d0, d1, d2, d3, d4;
        uint32_t carry;

        h0 += load32_le(message + 0) & 0x3ffffffu;
        h1 += (load32_le(message + 3) >> 2) & 0x3ffffffu;
        h2 += (load32_le(message + 6) >> 4) & 0x3ffffffu;
        h3 += (load32_le(message + 9) >> 6) & 0x3ffffffu;
        h4 += (load32_le(message + 12) >> 8) | hibit;

        d0 = (uint64_t)h0 * r0 + (uint64_t)h1 * s4 + (uint64_t)h2 * s3 + (uint64_t)h3 * s2 +
             (uint64_t)h4 * s1;
        d1 = (uint64_t)h0 * r1 + (uint64_t)h1 * r0 + (uint64_t)h2 * s4 + (uint64_t)h3 * s3 +
             (uint64_t)h4 * s2;
        d2 = (uint64_t)h0 * r2 + (uint64_t)h1 * r1 + (uint64_t)h2 * r0 + (uint64_t)h3 * s4 +
             (uint64_t)h4 * s3;
        d3 = (uint64_t)h0 * r3 + (uint64_t)h1 * r2 + (uint64_t)h2 * r1 + (uint64_t)h3 * r0 +
             (uint64_t)h4 * s4;
        d4 = (uint64_t)h0 * r4 + (uint64_t)h1 * r3 + (uint64_t)h2 * r2 + (uint64_t)h3 * r1 +
             (uint64_t)h4 * r0;

        carry = (uint32_t)(d0 >> 26);
        h0 = (uint32_t)d0 & 0x3ffffffu;
        d1 += carry;
        carry = (uint32_t)(d1 >> 26);
        h1 = (uint32_t)d1 & 0x3ffffffu;
        d2 += carry;
        carry = (uint32_t)(d2 >> 26);
        h2 = (uint32_t)d2 & 0x3ffffffu;
        d3 += carry;
        carry = (uint32_t)(d3 >> 26);
        h3 = (uint32_t)d3 & 0x3ffffffu;
        d4 += carry;
        carry = (uint32_t)(d4 >> 26);
        h4 = (uint32_t)d4 & 0x3ffffffu;
        h0 += carry * 5;
        carry = h0 >> 26;
        h0 &= 0x3ffffffu;
        h1 += carry;

        message += 16;
        bytes -= 16;
    }

    ctx->h[0] = h0;
    ctx->h[1] = h1;
    ctx->h[2] = h2;
    ctx->h[3] = h3;
    ctx->h[4] = h4;
}

static void poly1305_update(poly1305_state *ctx, const unsigned char *message, size_t bytes) {
    if (ctx->leftover > 0) {
        size_t want = 16 - ctx->leftover;
        if (want > bytes) want = bytes;
        memcpy(ctx->buffer + ctx->leftover, message, want);
        ctx->leftover += want;
        message += want;
        bytes -= want;
        if (ctx->leftover == 16) {
            poly1305_blocks(ctx, ctx->buffer, 16);
            ctx->leftover = 0;
        }
    }
    if (bytes >= 16) {
        size_t whole = bytes & ~(size_t)15;
        poly1305_blocks(ctx, message, whole);
        message += whole;
        bytes -= whole;
    }
    if (bytes > 0) {
        memcpy(ctx->buffer, message, bytes);
        ctx->leftover = bytes;
    }
}

/* Zero padding to the next 16-byte boundary, as the AEAD construction requires. */
static void poly1305_pad16(poly1305_state *ctx, size_t bytes) {
    static const unsigned char zeros[16] = {0};
    size_t remainder = bytes & 15u;
    if (remainder != 0) poly1305_update(ctx, zeros, 16 - remainder);
}

static void poly1305_finish(poly1305_state *ctx, unsigned char mac[16]) {
    uint32_t h0, h1, h2, h3, h4, g0, g1, g2, g3, g4, carry, mask;
    uint64_t sum;

    if (ctx->leftover > 0) {
        size_t index = ctx->leftover;
        ctx->buffer[index++] = 1;
        for (; index < 16; index++) ctx->buffer[index] = 0;
        poly1305_blocks(ctx, ctx->buffer, 16);
    }

    h0 = ctx->h[0];
    h1 = ctx->h[1];
    h2 = ctx->h[2];
    h3 = ctx->h[3];
    h4 = ctx->h[4];

    carry = h1 >> 26;
    h1 &= 0x3ffffffu;
    h2 += carry;
    carry = h2 >> 26;
    h2 &= 0x3ffffffu;
    h3 += carry;
    carry = h3 >> 26;
    h3 &= 0x3ffffffu;
    h4 += carry;
    carry = h4 >> 26;
    h4 &= 0x3ffffffu;
    h0 += carry * 5;
    carry = h0 >> 26;
    h0 &= 0x3ffffffu;
    h1 += carry;

    /* h + -p, then pick whichever of h / h + -p still fits in 128 bits. */
    g0 = h0 + 5;
    carry = g0 >> 26;
    g0 &= 0x3ffffffu;
    g1 = h1 + carry;
    carry = g1 >> 26;
    g1 &= 0x3ffffffu;
    g2 = h2 + carry;
    carry = g2 >> 26;
    g2 &= 0x3ffffffu;
    g3 = h3 + carry;
    carry = g3 >> 26;
    g3 &= 0x3ffffffu;
    g4 = h4 + carry - ((uint32_t)1 << 26);

    mask = (g4 >> 31) - 1;
    g0 &= mask;
    g1 &= mask;
    g2 &= mask;
    g3 &= mask;
    g4 &= mask;
    mask = ~mask;
    h0 = (h0 & mask) | g0;
    h1 = (h1 & mask) | g1;
    h2 = (h2 & mask) | g2;
    h3 = (h3 & mask) | g3;
    h4 = (h4 & mask) | g4;

    /* Repack the 26-bit limbs into four 32-bit words. */
    h0 = (h0 | (h1 << 26)) & 0xffffffffu;
    h1 = ((h1 >> 6) | (h2 << 20)) & 0xffffffffu;
    h2 = ((h2 >> 12) | (h3 << 14)) & 0xffffffffu;
    h3 = ((h3 >> 18) | (h4 << 8)) & 0xffffffffu;

    sum = (uint64_t)h0 + ctx->pad[0];
    h0 = (uint32_t)sum;
    sum = (uint64_t)h1 + ctx->pad[1] + (sum >> 32);
    h1 = (uint32_t)sum;
    sum = (uint64_t)h2 + ctx->pad[2] + (sum >> 32);
    h2 = (uint32_t)sum;
    sum = (uint64_t)h3 + ctx->pad[3] + (sum >> 32);
    h3 = (uint32_t)sum;

    store32_le(mac + 0, h0);
    store32_le(mac + 4, h1);
    store32_le(mac + 8, h2);
    store32_le(mac + 12, h3);
}

/* ------------------------------------------------------------------- AEAD */

/* Derives the Poly1305 key from the first ChaCha20 block, as RFC 8439 §2.6 does. */
static void aead_poly_key(unsigned char poly_key[32], const unsigned char key[CPP_KEY_SIZE],
                          const unsigned char nonce[CPP_NONCE_SIZE]) {
    uint32_t state[16];
    unsigned char block[64];
    chacha20_state(state, key, nonce, 0);
    chacha20_block(block, state);
    memcpy(poly_key, block, 32);
}

static void aead_tag(unsigned char mac[16], const unsigned char poly_key[32],
                     const unsigned char *aad, size_t aad_len,
                     const unsigned char *ciphertext, size_t ciphertext_len) {
    poly1305_state ctx;
    unsigned char lengths[16];
    poly1305_init(&ctx, poly_key);
    poly1305_update(&ctx, aad, aad_len);
    poly1305_pad16(&ctx, aad_len);
    poly1305_update(&ctx, ciphertext, ciphertext_len);
    poly1305_pad16(&ctx, ciphertext_len);
    store64_le(lengths, (uint64_t)aad_len);
    store64_le(lengths + 8, (uint64_t)ciphertext_len);
    poly1305_update(&ctx, lengths, sizeof(lengths));
    poly1305_finish(&ctx, mac);
}

int cpp_aead_seal(const unsigned char key[CPP_KEY_SIZE], const unsigned char nonce[CPP_NONCE_SIZE],
                  const unsigned char *aad, size_t aad_len,
                  const unsigned char *plaintext, size_t plaintext_len,
                  unsigned char *out) {
    unsigned char poly_key[32];
    unsigned char mac[16];
    aead_poly_key(poly_key, key, nonce);
    chacha20_xor(out, plaintext, plaintext_len, key, nonce, 1);
    aead_tag(mac, poly_key, aad, aad_len, out, plaintext_len);
    memcpy(out + plaintext_len, mac, sizeof(mac));
    return 0;
}

int cpp_aead_open(const unsigned char key[CPP_KEY_SIZE], const unsigned char nonce[CPP_NONCE_SIZE],
                  const unsigned char *aad, size_t aad_len,
                  unsigned char *buf, size_t ciphertext_len) {
    unsigned char poly_key[32];
    unsigned char mac[16];
    unsigned int difference = 0;
    size_t index;

    /* The tag covers the ciphertext, so verify before rewriting the buffer. */
    aead_poly_key(poly_key, key, nonce);
    aead_tag(mac, poly_key, aad, aad_len, buf, ciphertext_len);
    for (index = 0; index < sizeof(mac); index++) difference |= (unsigned int)(mac[index] ^ buf[ciphertext_len + index]);
    if (difference != 0) return -1;

    chacha20_xor(buf, buf, ciphertext_len, key, nonce, 1);
    return 0;
}

#define _GNU_SOURCE
#include <stdlib.h>
#include "includes.h"
#include "ssh_key_policy.h"
#include <errno.h>
#include <stdint.h>
#include <string.h>
#include "sshbuf.h"
#include "sshkey.h"
#ifdef malloc
#undef malloc
#endif
#ifdef calloc
#undef calloc
#endif
#ifdef realloc
#undef realloc
#endif
struct policy_slice { const unsigned char *data; size_t length; };
static void wipe_bytes(void *memory, size_t length) {
    volatile unsigned char *bytes = memory;

    if (bytes == NULL) return;
    while (length-- != 0) *bytes++ = 0;
}

struct selection_cursor {
    const unsigned char *position;
    size_t remaining;
};

static int selection_u32(struct selection_cursor *cursor, uint32_t *value) {
    const unsigned char *p = cursor->position;

    if (cursor->remaining < 4U) return -1;
    *value = ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16)
        | ((uint32_t)p[2] << 8) | (uint32_t)p[3];
    cursor->position += 4U;
    cursor->remaining -= 4U;
    return 0;
}

static int selection_field(struct selection_cursor *cursor, struct policy_slice *field) {
    uint32_t length;

    if (selection_u32(cursor, &length) != 0 || (size_t)length > cursor->remaining)
        return -1;
    field->data = cursor->position;
    field->length = length;
    cursor->position += length;
    cursor->remaining -= length;
    return 0;
}

int ctos_ssh_key_software_identity(const struct sshkey *key) {
    if (key == NULL || sshkey_is_cert(key) || sshkey_is_sk(key)
        || (key->flags & SSHKEY_FLAG_EXT) != 0
        || (key->type != KEY_ED25519 && key->type != KEY_ECDSA && key->type != KEY_RSA)) {
        errno = ENOTSUP;
        return -1;
    }
    if (sshkey_check_rsa_length(key, 0) != 0) { errno = EACCES; return -1; }
    return 0;
}

static int next_key_line(struct selection_cursor *cursor, struct policy_slice *line) {
    const unsigned char *end = memchr(cursor->position, '\n', cursor->remaining);
    size_t used = end == NULL ? cursor->remaining : (size_t)(end - cursor->position) + 1U;

    if (used == 0) return -1;
    line->data = cursor->position;
    line->length = end == NULL ? used : used - 1U;
    if (line->length != 0 && line->data[line->length - 1U] == '\r') line->length--;
    cursor->position += used;
    cursor->remaining -= used;
    return 0;
}

static int key_line_equals(struct policy_slice line, const char *text) {
    return line.length == strlen(text) && memcmp(line.data, text, line.length) == 0;
}

static int legacy_pem_policy(const unsigned char *bytes, size_t length) {
    struct selection_cursor cursor = {bytes, length};
    struct policy_slice line;
    const char *end;
    const char *aes128 = "DEK-Info: AES-128-CBC,";
    const char *aes256 = "DEK-Info: AES-256-CBC,";
    size_t prefix_length = strlen(aes128);
    int body = 0;

    if (next_key_line(&cursor, &line) != 0) return -1;
    if (key_line_equals(line, "-----BEGIN RSA PRIVATE KEY-----")) end = "-----END RSA PRIVATE KEY-----";
    else if (key_line_equals(line, "-----BEGIN EC PRIVATE KEY-----")) end = "-----END EC PRIVATE KEY-----";
    else return -1;
    if (next_key_line(&cursor, &line) != 0 || !key_line_equals(line, "Proc-Type: 4,ENCRYPTED")
        || next_key_line(&cursor, &line) != 0 || line.length != prefix_length + 32U
        || (memcmp(line.data, aes128, prefix_length) != 0
            && memcmp(line.data, aes256, prefix_length) != 0)) return -1;
    for (size_t index = prefix_length; index < line.length; index++) {
        unsigned char ch = line.data[index];
        if (!((ch >= '0' && ch <= '9') || (ch >= 'A' && ch <= 'F') || (ch >= 'a' && ch <= 'f'))) return -1;
    }
    if (next_key_line(&cursor, &line) != 0 || line.length != 0) return -1;
    while (next_key_line(&cursor, &line) == 0) {
        if (key_line_equals(line, end)) {
            for (size_t index = 0; index < cursor.remaining; index++)
                if (cursor.position[index] != '\r' && cursor.position[index] != '\n') return -1;
            return body ? 0 : -1;
        }
        if (line.length == 0) return -1;
        for (size_t index = 0; index < line.length; index++) {
            unsigned char ch = line.data[index];
            if (!((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
                || (ch >= '0' && ch <= '9') || ch == '+' || ch == '/' || ch == '=')) return -1;
        }
        body = 1;
    }
    return -1;
}

int ctos_ssh_key_encrypted_policy(const unsigned char *bytes, size_t length) {
    static const char begin[] = "-----BEGIN OPENSSH PRIVATE KEY-----";
    static const char end[] = "-----END OPENSSH PRIVATE KEY-----";
    static const unsigned char magic[] = "openssh-key-v1";
    struct sshbuf *decoded = NULL;
    struct selection_cursor cursor, options;
    struct policy_slice cipher, kdf, kdf_options, salt;
    const unsigned char *ending;
    char *encoded = NULL;
    size_t offset, count = 0;
    uint32_t keys, rounds;
    int result = -1, error = ENOTSUP;

    if (length < sizeof(begin) - 1U || memcmp(bytes, begin, sizeof(begin) - 1U) != 0) {
        /* Legacy encrypted PEM uses a fixed derivation, unlike arbitrary
         * encrypted PKCS8 PBKDF parameters. Only its exact RSA/EC labels pass. */
        if (legacy_pem_policy(bytes, length) == 0) return 0;
        errno = ENOTSUP;
        return -1;
    }
    offset = sizeof(begin) - 1U;
    if (offset < length && bytes[offset] == '\r') offset++;
    if (offset >= length || bytes[offset++] != '\n') { errno = EINVAL; return -1; }
    ending = memmem(bytes + offset, length - offset, end, sizeof(end) - 1U);
    if (ending == NULL) { errno = EINVAL; return -1; }
    for (size_t index = (size_t)(ending - bytes) + sizeof(end) - 1U; index < length; index++) {
        if (bytes[index] != '\r' && bytes[index] != '\n') { errno = EINVAL; return -1; }
    }
    encoded = calloc(1, (size_t)(ending - bytes) - offset + 1U);
    decoded = sshbuf_new();
    if (encoded == NULL || decoded == NULL) { error = ENOMEM; goto out; }
    for (size_t index = offset; index < (size_t)(ending - bytes); index++) {
        unsigned char ch = bytes[index];
        if (ch == '\r' || ch == '\n') continue;
        if (!((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
            || (ch >= '0' && ch <= '9') || ch == '+' || ch == '/' || ch == '=')) {
            error = EINVAL;
            goto out;
        }
        encoded[count++] = (char)ch;
    }
    if (sshbuf_b64tod(decoded, encoded) != 0 || sshbuf_len(decoded) < sizeof(magic)
        || memcmp(sshbuf_ptr(decoded), magic, sizeof(magic)) != 0) { error = EINVAL; goto out; }
    cursor = (struct selection_cursor){sshbuf_ptr(decoded) + sizeof(magic), sshbuf_len(decoded) - sizeof(magic)};
    if (selection_field(&cursor, &cipher) != 0 || selection_field(&cursor, &kdf) != 0
        || selection_field(&cursor, &kdf_options) != 0 || selection_u32(&cursor, &keys) != 0) {
        error = EINVAL;
        goto out;
    }
    if (keys != 1U || cipher.length != 10U || memcmp(cipher.data, "aes256-ctr", 10U) != 0
        || kdf.length != 6U || memcmp(kdf.data, "bcrypt", 6U) != 0) goto out;
    options = (struct selection_cursor){kdf_options.data, kdf_options.length};
    if (selection_field(&options, &salt) != 0 || selection_u32(&options, &rounds) != 0
        || options.remaining != 0) { error = EINVAL; goto out; }
    if (salt.length == 0 || salt.length > 64U || rounds == 0 || rounds > 64U) goto out;
    result = 0;
out:
    wipe_bytes(encoded, count);
    free(encoded);
    sshbuf_free(decoded);
    if (result != 0) errno = error;
    return result;
}


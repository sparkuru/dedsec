#define _GNU_SOURCE
#include <stdlib.h>
#include "includes.h"
#include "ssh_material_inspect.h"
#include "ssh_key_policy.h"
#include "ssh_file_view.h"
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <openssl/crypto.h>
#include <openssl/pem.h>
#include <openssl/rsa.h>
#include "digest.h"
#include "hostfile.h"
#include "sshbuf.h"
#include "ssherr.h"
#include "ssh2.h"
#include "sshkey.h"
#if defined(DEBUG_PK) || defined(DEBUG_PKOK)
#error Secret-dumping OpenSSH debug macros must be disabled
#endif
#ifdef malloc
#undef malloc
#endif
#ifdef calloc
#undef calloc
#endif
#ifdef realloc
#undef realloc
#endif
struct material_result {
    unsigned int status, kind, flags, records, line;
    unsigned char digest[32];
};
static void wipe(void *memory, size_t length) {
    volatile unsigned char *p = memory;
    while (length-- != 0) *p++ = 0;
}
int ctos_ssh_material_deadline(uint64_t deadline) {
    struct timespec now;
    if (clock_gettime(CLOCK_BOOTTIME, &now) != 0) return -1;
    uint64_t millis = (uint64_t)now.tv_sec * 1000U + (uint64_t)now.tv_nsec / 1000000U;
    if (deadline <= millis) { errno = ETIMEDOUT; return -1; }
    if (deadline - millis > 60000U) { errno = EINVAL; return -1; }
    return 0;
}
static int parse_system_error(int error) {
    if (error == SSH_ERR_ALLOC_FAIL) { errno = ENOMEM; return -1; }
    if (error == SSH_ERR_SYSTEM_ERROR || error == SSH_ERR_LIBCRYPTO_ERROR) { errno = EIO; return -1; }
    return 0;
}
static unsigned int key_kind(const struct sshkey *key) {
    switch (sshkey_type_plain(key->type)) {
    case KEY_ED25519: return 1;
    case KEY_ECDSA:
        switch (sshkey_size(key)) { case 256: return 2; case 384: return 3; case 521: return 4; }
        return 0;
    case KEY_RSA: return 5;
    default: return 0;
    }
}
static int key_result(struct sshkey *key, struct material_result *r) {
    unsigned char *blob = NULL;
    size_t length = 0;
    unsigned int kind = key_kind(key);
    if (kind == 0 || sshkey_is_sk(key) || (key->flags & SSHKEY_FLAG_EXT) != 0
        || sshkey_check_rsa_length(key, 0) != 0) {
        r->status = CTOS_MATERIAL_UNSUPPORTED;
        return 0;
    }
    int status = sshkey_to_blob(key, &blob, &length);
    if (status != 0) {
        if (parse_system_error(status) != 0) return -1;
        r->status = CTOS_MATERIAL_MALFORMED;
        return 0;
    }
    int result = ssh_digest_memory(SSH_DIGEST_SHA256, blob, length, r->digest, sizeof(r->digest));
    wipe(blob, length); free(blob);
    if (result != 0) { errno = EIO; return -1; }
    r->status = CTOS_MATERIAL_PARSED; r->kind = kind; r->records = 1; r->line = 0;
    return 0;
}
static int text_bounds(const unsigned char *body, size_t length, struct material_result *r) {
    size_t column = 0;
    unsigned int line = 1;
    for (size_t i = 0; i < length; i++) {
        if (line > CTOS_SSH_MATERIAL_LINES) { errno = E2BIG; return -1; }
        if (body[i] == 0) { r->line = line; return 1; }
        if (body[i] == '\n') { line++; column = 0; }
        else if (++column > CTOS_SSH_MATERIAL_LINE_BYTES) { errno = E2BIG; return -1; }
    }
    return 0;
}
static int line_equal(const unsigned char *p, size_t n, const char *text) {
    if (n != 0 && p[n - 1] == '\r') n--;
    return strlen(text) == n && memcmp(p, text, n) == 0;
}
/* One exact envelope; only CR/LF may follow its end marker. The actual payload
 * remains the pinned parser's responsibility; PEM DER gets a supplemental check. */
static int private_envelope(const unsigned char *body, size_t length, int *encrypted) {
    static const char *const begins[] = {"-----BEGIN OPENSSH PRIVATE KEY-----",
        "-----BEGIN RSA PRIVATE KEY-----", "-----BEGIN EC PRIVATE KEY-----",
        "-----BEGIN PRIVATE KEY-----", "-----BEGIN ENCRYPTED PRIVATE KEY-----"};
    static const char *const ends[] = {"-----END OPENSSH PRIVATE KEY-----",
        "-----END RSA PRIVATE KEY-----", "-----END EC PRIVATE KEY-----",
        "-----END PRIVATE KEY-----", "-----END ENCRYPTED PRIVATE KEY-----"};
    if (length == 0) return -1;
    const unsigned char *nl = memchr(body, '\n', length);
    if (nl == NULL) return -1;
    size_t start = (size_t)(nl - body), selected;
    for (selected = 0; selected < 5; selected++) if (line_equal(body, start, begins[selected])) break;
    if (selected == 5) return -1;
    *encrypted = selected == 4;
    size_t offset = start + 1U;
    int payload = 0;
    while (offset < length) {
        nl = memchr(body + offset, '\n', length - offset);
        size_t n = nl == NULL ? length - offset : (size_t)(nl - body - offset);
        if (line_equal(body + offset, n, ends[selected])) {
            offset += n;
            for (; offset < length; offset++)
                if (body[offset] != '\r' && body[offset] != '\n') return -1;
            return payload ? 0 : -1;
        }
        if (n >= 10 && memcmp(body + offset, "Proc-Type:", 10) == 0) *encrypted = 1;
        if (n >= 5 && memcmp(body + offset, "-----", 5) == 0) return -1;
        if (n != 0) payload = 1;
        offset += n + (nl != NULL ? 1U : 0U);
    }
    return -1;
}
/* Inspect only the OpenSSH encryption header, without decrypting or deriving. */
static int openssh_encryption(const unsigned char *body, size_t length, int *encrypted) {
    static const char begin[] = "-----BEGIN OPENSSH PRIVATE KEY-----";
    static const char end[] = "-----END OPENSSH PRIVATE KEY-----";
    if (length < sizeof(begin) || memcmp(body, begin, sizeof(begin) - 1U) != 0) return 0;
    const unsigned char *first = memchr(body, '\n', length);
    const unsigned char *last = memmem(body, length, end, sizeof(end) - 1U);
    if (first == NULL || last == NULL || last <= first) return 0;
    size_t capacity = (size_t)(last - first);
    char *text = calloc(capacity, 1U), *cipher = NULL, *kdf = NULL;
    struct sshbuf *decoded = sshbuf_new();
    int result = 0;
    if (text == NULL || decoded == NULL) { errno = ENOMEM; result = -1; goto out; }
    size_t count = 0;
    for (const unsigned char *p = first + 1; p < last; p++) {
        if (*p != '\r' && *p != '\n') text[count++] = (char)*p;
    }
    if (sshbuf_b64tod(decoded, text) != 0 || sshbuf_len(decoded) < sizeof("openssh-key-v1")
        || memcmp(sshbuf_ptr(decoded), "openssh-key-v1", sizeof("openssh-key-v1")) != 0
        || sshbuf_consume(decoded, sizeof("openssh-key-v1")) != 0
        || sshbuf_get_cstring(decoded, &cipher, NULL) != 0
        || sshbuf_get_cstring(decoded, &kdf, NULL) != 0) goto out;
    *encrypted = strcmp(cipher, "none") != 0 || strcmp(kdf, "none") != 0;
out:
    free(cipher); free(kdf); sshbuf_free(decoded);
    if (text != NULL) { wipe(text, capacity); free(text); }
    return result;
}
/* OpenSSL's key decoder may ignore bytes after its DER object. Validate the
 * complete decoded single SEQUENCE separately for unencrypted PEM. */
static int pem_complete(const unsigned char *body, size_t length) {
    static const char openssh[] = "-----BEGIN OPENSSH PRIVATE KEY-----";
    if (length >= sizeof(openssh) - 1U && memcmp(body, openssh, sizeof(openssh) - 1U) == 0) return 1;
    BIO *bio = BIO_new_mem_buf(body, (int)length);
    if (bio == NULL) { errno = ENOMEM; return -1; }
    char *name = NULL, *header = NULL;
    unsigned char *der = NULL;
    long decoded_length = 0, allocation_length = 0;
    int result = 0;
    if (PEM_read_bio(bio, &name, &header, &der, &decoded_length) != 1) goto out;
    allocation_length = decoded_length;
    if (decoded_length < 2 || der[0] != 0x30U) goto out;
    size_t content = der[1], offset = 2U, available = (size_t)decoded_length;
    if ((content & 0x80U) != 0) {
        size_t octets = content & 0x7fU;
        if (octets == 0 || octets > sizeof(size_t) || octets > available - offset || der[offset] == 0) goto out;
        content = 0;
        for (size_t i = 0; i < octets; i++) {
            if (content > (SIZE_MAX >> 8)) goto out;
            content = (content << 8) | der[offset++];
        }
        if (content < 128U) goto out;
    }
    result = content == available - offset;
out:
    if (der != NULL) { wipe(der, (size_t)allocation_length); OPENSSL_free(der); }
    OPENSSL_free(name); OPENSSL_free(header); BIO_free(bio);
    return result;
}
static int parse_private(const unsigned char *body, size_t length,
    const unsigned char *secret, size_t secret_length, int strict,
    struct material_result *r, struct sshkey **key_out) {
    struct sshbuf *file = NULL;
    struct sshkey *key = NULL;
    char *passphrase = NULL;
    int encrypted = 0, result = -1, status;
    r->line = 0;
    if (private_envelope(body, length, &encrypted) != 0) return 0;
    if (openssh_encryption(body, length, &encrypted) != 0) return -1;
    static const char openssh[] = "-----BEGIN OPENSSH PRIVATE KEY-----";
    if (strict && ((encrypted && (length < sizeof(openssh) - 1U
            || memcmp(body, openssh, sizeof(openssh) - 1U) != 0))
            || (!encrypted && secret_length != 0))) {
        r->flags |= encrypted ? 1U : 0U;
        r->status = CTOS_MATERIAL_UNSUPPORTED;
        return 0;
    }
    /* Legacy encrypted PEM retains upstream decoded-DER consumption limits. */
    int complete = encrypted ? 1 : pem_complete(body, length);
    if (complete < 0) return -1;
    if (!complete) return 0;
    file = sshbuf_from(body, length);
    if (file == NULL) { errno = ENOMEM; return -1; }
    if (encrypted) {
        r->flags |= 1U;
        if (ctos_ssh_key_encrypted_policy(body, length) != 0) {
            if (errno == ENOMEM) goto out;
            r->status = errno == ENOTSUP ? CTOS_MATERIAL_UNSUPPORTED : CTOS_MATERIAL_MALFORMED;
            result = 0; goto out;
        }
        if (secret_length == 0) { r->status = CTOS_MATERIAL_NEEDS_PASSPHRASE; result = 0; goto out; }
        passphrase = calloc(secret_length + 1U, 1U);
        if (passphrase == NULL) { errno = ENOMEM; goto out; }
        memcpy(passphrase, secret, secret_length);
        status = sshkey_parse_private_fileblob(file, passphrase, &key, NULL);
        wipe(passphrase, secret_length + 1U); free(passphrase); passphrase = NULL;
        if (status == SSH_ERR_KEY_WRONG_PASSPHRASE) {
            r->status = CTOS_MATERIAL_BAD_PASSPHRASE; result = 0; goto out;
        }
    } else status = sshkey_parse_private_fileblob(file, "", &key, NULL);
    if (status != 0) { result = parse_system_error(status); goto out; }
    if (ctos_ssh_key_software_identity(key) != 0) {
        r->status = CTOS_MATERIAL_UNSUPPORTED; result = 0; goto out;
    }
    r->status = CTOS_MATERIAL_PARSED;
    *key_out = key; key = NULL;
    result = 0;
out:
    if (passphrase != NULL) { wipe(passphrase, secret_length + 1U); free(passphrase); }
    sshkey_free(key); sshbuf_free(file);
    return result;
}
static int inspect_private(const unsigned char *body, size_t length,
    const unsigned char *secret, size_t secret_length, struct material_result *r) {
    struct sshkey *key = NULL;
    int result = parse_private(body, length, secret, secret_length, 0, r, &key);
    if (result == 0 && key != NULL) result = key_result(key, r);
    sshkey_free(key);
    return result;
}
static int parse_public(unsigned int type, const unsigned char *body, size_t length,
    struct material_result *r, struct sshkey **key_out) {
    size_t offset = 0;
    unsigned int line = 0;
    struct sshkey *key = NULL;
    int result = 0;
    while (offset < length) {
        const unsigned char *nl = memchr(body + offset, '\n', length - offset);
        size_t n = nl == NULL ? length - offset : (size_t)(nl - body - offset);
        line++;
        if (n != 0 && body[offset + n - 1U] == '\r') n--;
        size_t leading = 0;
        while (leading < n && (body[offset + leading] == ' ' || body[offset + leading] == '\t')) leading++;
        if (leading < n && body[offset + leading] != '#') {
            r->line = line;
            if (key != NULL) { r->status = CTOS_MATERIAL_MALFORMED; goto out; }
            char *text = calloc(n + 1U, 1U);
            if (text == NULL || (key = sshkey_new(KEY_UNSPEC)) == NULL) {
                free(text); errno = ENOMEM; result = -1; goto out;
            }
            memcpy(text, body + offset, n);
            char *cursor = text + leading;
            int status = sshkey_read(key, &cursor);
            wipe(text, n + 1U); free(text);
            if (status != 0) { result = parse_system_error(status); goto out; }
            if ((type == CTOS_SSH_CERTIFICATE) != (sshkey_is_cert(key) != 0)) goto out;
        }
        offset = nl == NULL ? length : (size_t)(nl - body) + 1U;
    }
    if (key != NULL) {
        r->status = CTOS_MATERIAL_PARSED;
        *key_out = key; key = NULL;
    }
out:
    sshkey_free(key);
    return result;
}
static int inspect_public(unsigned int type, const unsigned char *body, size_t length,
    struct material_result *r) {
    struct sshkey *key = NULL;
    int result = parse_public(type, body, length, r, &key);
    if (result == 0 && key != NULL) {
        result = key_result(key, r);
        if (result == 0 && r->status == CTOS_MATERIAL_PARSED && type == CTOS_SSH_CERTIFICATE) r->flags |= 2U;
    }
    sshkey_free(key);
    return result;
}
static int hash_field(const char *p, size_t n) {
    if (n != 28U || p[27] != '=') return 0;
    for (size_t i = 0; i < 27U; i++) {
        unsigned char c = (unsigned char)p[i];
        if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '/')) return 0;
    }
    char text[29]; memcpy(text, p, n); text[n] = 0;
    struct sshbuf *decoded = sshbuf_new();
    if (decoded == NULL) { errno = ENOMEM; return -1; }
    int result = sshbuf_b64tod(decoded, text) == 0 && sshbuf_len(decoded) == 20U;
    sshbuf_free(decoded); wipe(text, sizeof(text));
    return result;
}
static int hash_token(const char *hosts) {
    if (strchr(hosts, '|') == NULL) return 1;
    if (strncmp(hosts, "|1|", 3) != 0) return 0;
    const char *delimiter = strchr(hosts + 3, '|');
    if (delimiter == NULL) return 0;
    int a = hash_field(hosts + 3, (size_t)(delimiter - hosts - 3));
    if (a <= 0) return a;
    return hash_field(delimiter + 1, strlen(delimiter + 1));
}
struct hosts_context { struct material_result *result; int system_error; };
static int host_record(struct hostkey_foreach_line *line, void *opaque) {
    struct hosts_context *ctx = opaque;
    if (line->status == HKF_STATUS_COMMENT) return 0;
    ctx->result->line = (unsigned int)line->linenum;
    if (line->status == HKF_STATUS_INVALID || line->key == NULL || line->hosts == NULL) return 1;
    int hash = hash_token(line->hosts);
    if (hash < 0) { ctx->system_error = errno; return 1; }
    if (hash == 0) return 1;
    ctx->result->records++;
    ctx->result->line = 0;
    return 0;
}
static int inspect_hosts(const unsigned char *body, size_t length, struct material_result *r) {
    if (length == 0) { r->status = CTOS_MATERIAL_PARSED; return 0; }
    FILE *file = fmemopen((void *)body, length, "r");
    if (file == NULL) return -1;
    struct hosts_context ctx = {r, 0};
    int status = hostkeys_foreach_file("ctos-material", file, host_record, &ctx, NULL, NULL, HKF_WANT_PARSE_KEY, 0);
    int io_error = ferror(file);
    if (fclose(file) != 0) io_error = 1;
    if (ctx.system_error != 0) { errno = ctx.system_error; return -1; }
    if (io_error) { errno = EIO; return -1; }
    if (status < 0 && parse_system_error(status) != 0) return -1;
    if (status == 0) { r->status = CTOS_MATERIAL_PARSED; r->line = 0; }
    else r->records = 0;
    return 0;
}
static void put32(unsigned char *out, uint32_t value) {
    out[0] = (unsigned char)(value >> 24); out[1] = (unsigned char)(value >> 16);
    out[2] = (unsigned char)(value >> 8); out[3] = (unsigned char)value;
}
int ctos_ssh_material_inspect(unsigned int type, const unsigned char *body, size_t length,
    const unsigned char *passphrase, size_t passphrase_length, uint64_t deadline,
    unsigned char output[CTOS_SSH_MATERIAL_WIRE_BYTES]) {
    if (output != NULL) wipe(output, CTOS_SSH_MATERIAL_WIRE_BYTES);
    if (length > CTOS_SSH_MATERIAL_BYTES || passphrase_length > CTOS_SSH_MATERIAL_PASSPHRASE_BYTES) { errno = E2BIG; return -1; }
    if (output == NULL || type < CTOS_SSH_PRIVATE_KEY || type > CTOS_SSH_KNOWN_HOSTS
        || (body == NULL && length != 0) || (passphrase == NULL && passphrase_length != 0)
        || (type != CTOS_SSH_PRIVATE_KEY && passphrase_length != 0)
        || (passphrase_length != 0 && memchr(passphrase, 0, passphrase_length) != NULL)) { errno = EINVAL; return -1; }
    if (ctos_ssh_material_deadline(deadline) != 0) return -1;
    struct material_result r = {.status = CTOS_MATERIAL_MALFORMED, .flags = 4U};
    int result = text_bounds(body, length, &r);
    if (result < 0) return -1;
    if (result == 0) {
        if (OPENSSL_init_crypto(OPENSSL_INIT_NO_LOAD_CONFIG, NULL) != 1) { errno = EIO; return -1; }
        if (type == CTOS_SSH_PRIVATE_KEY) result = inspect_private(body, length, passphrase, passphrase_length, &r);
        else if (type == CTOS_SSH_KNOWN_HOSTS) result = inspect_hosts(body, length, &r);
        else result = inspect_public(type, body, length, &r);
    }
    if (type == CTOS_SSH_PRIVATE_KEY) r.line = 0;
    if (result < 0 || ctos_ssh_material_deadline(deadline) != 0) { wipe(&r, sizeof(r)); return -1; }
    put32(output, 0x43534d49U); put32(output + 4, 1); put32(output + 8, r.status);
    put32(output + 12, r.kind); put32(output + 16, r.flags); put32(output + 20, r.records); put32(output + 24, r.line);
    memcpy(output + 28, r.digest, 32); wipe(&r, sizeof(r));
    return 0;
}
/* OpenSSH bounds the RSA modulus on the wire and before signing. PEM can still
 * contain oversized secret/CRT fields, so the new signing path bounds each. */
static int pair_software_key(const struct sshkey *key) {
    if (ctos_ssh_key_software_identity(key) != 0) return 0;
    if (sshkey_type_plain(key->type) != KEY_RSA) return 1;
    const RSA *rsa = EVP_PKEY_get0_RSA(key->pkey);
    if (rsa == NULL || RSA_get_multi_prime_extra_count(rsa) != 0) return 0;
    const BIGNUM *n, *e, *d, *p, *q, *dp, *dq, *iq;
    RSA_get0_key(rsa, &n, &e, &d);
    RSA_get0_factors(rsa, &p, &q);
    RSA_get0_crt_params(rsa, &dp, &dq, &iq);
    const BIGNUM *components[] = {n, e, d, p, q, dp, dq, iq};
    for (size_t i = 0; i < sizeof(components) / sizeof(components[0]); i++)
        if (components[i] != NULL && BN_num_bits(components[i]) > 16384) return 0;
    return 1;
}
static unsigned int pair_material_status(unsigned int status) {
    switch (status) {
    case CTOS_MATERIAL_UNSUPPORTED: return CTOS_PAIR_UNSUPPORTED;
    case CTOS_MATERIAL_NEEDS_PASSPHRASE: return CTOS_PAIR_NEEDS_PASSPHRASE;
    case CTOS_MATERIAL_BAD_PASSPHRASE: return CTOS_PAIR_BAD_PASSPHRASE;
    default: return CTOS_PAIR_MALFORMED;
    }
}
/* Domains are fixed at the two local callsites, never supplied by a caller.
 * Return1 only for a complete proof,0 for material failure,-1 for system/lifetime. */
static int signing_proof(struct sshkey *private_key, struct sshkey *subject,
    const unsigned char *domain, size_t domain_length, uint64_t deadline,
    unsigned char digest[32]) {
    struct material_result material = {.status = CTOS_MATERIAL_MALFORMED};
    unsigned char challenge[64U + 32U] = {0}, *signature = NULL;
    size_t signature_length = 0;
    int result = -1, error, saved;
    if (domain_length > sizeof(challenge) - 32U) { errno = EINVAL; goto done; }
    /* Hash the plain public subject, never the private container or whole cert. */
    result = key_result(subject, &material);
    if (result < 0 || material.status != CTOS_MATERIAL_PARSED) goto done;
    memcpy(challenge, domain, domain_length);
    memcpy(challenge + domain_length, material.digest, sizeof(material.digest));
    const char *algorithm = sshkey_type_plain(subject->type) == KEY_RSA ? "rsa-sha2-256" : sshkey_ssh_name(subject);
    if (ctos_ssh_material_deadline(deadline) != 0) { result = -1; goto done; }
    error = sshkey_sign(private_key, &signature, &signature_length, challenge,
        domain_length + sizeof(material.digest), algorithm, NULL, NULL, 0);
    if (error != 0) { result = parse_system_error(error); goto done; }
    if (ctos_ssh_material_deadline(deadline) != 0) { result = -1; goto done; }
    if (signature == NULL || signature_length == 0 || signature_length > 16384U) { result = 0; goto done; }
    error = sshkey_check_sigtype(signature, signature_length, algorithm);
    if (error == 0) error = sshkey_verify(subject, signature, signature_length, challenge,
        domain_length + sizeof(material.digest), algorithm, 0, NULL);
    if (error != 0) { result = parse_system_error(error); goto done; }
    memcpy(digest, material.digest, sizeof(material.digest));
    result = 1;
done:
    saved = errno;
    if (signature != NULL) { wipe(signature, signature_length); free(signature); }
    wipe(challenge, sizeof(challenge)); wipe(&material, sizeof(material));
    if (result != 1) wipe(digest, 32);
    errno = saved;
    return result;
}
int ctos_ssh_material_verify_pair(unsigned int peer_type,
    const unsigned char *private_body, size_t private_length,
    const unsigned char *passphrase, size_t passphrase_length,
    const unsigned char *peer_body, size_t peer_length, uint64_t deadline,
    unsigned char output[CTOS_SSH_KEY_ASSOCIATION_WIRE_BYTES]) {
    if (output != NULL) wipe(output, CTOS_SSH_KEY_ASSOCIATION_WIRE_BYTES);
    if (private_length > CTOS_SSH_MATERIAL_BYTES || peer_length > CTOS_SSH_MATERIAL_BYTES
        || passphrase_length > CTOS_SSH_MATERIAL_PASSPHRASE_BYTES) { errno = E2BIG; return -1; }
    if (output == NULL || (peer_type != CTOS_SSH_PUBLIC_KEY && peer_type != CTOS_SSH_CERTIFICATE)
        || (private_body == NULL && private_length != 0) || (peer_body == NULL && peer_length != 0)
        || (passphrase == NULL && passphrase_length != 0)
        || (passphrase_length != 0 && memchr(passphrase, 0, passphrase_length) != NULL)) { errno = EINVAL; return -1; }
    if (ctos_ssh_material_deadline(deadline) != 0) return -1;
    struct material_result material = {.status = CTOS_MATERIAL_MALFORMED};
    struct sshkey *private_key = NULL, *peer_key = NULL, *subject = NULL;
    static const unsigned char domain[] = "ctOS SSH key association proof v1";
    unsigned char digest[32] = {0};
    unsigned int status = CTOS_PAIR_MALFORMED, stage = CTOS_PAIR_PRIVATE, flags = 4U, kind = 0;
    int result = text_bounds(private_body, private_length, &material), error, saved;
    if (result < 0) goto done;
    if (result != 0) { result = 0; goto done; }
    if (OPENSSL_init_crypto(OPENSSL_INIT_NO_LOAD_CONFIG, NULL) != 1) { errno = EIO; result = -1; goto done; }
    result = parse_private(private_body, private_length, passphrase, passphrase_length, 1, &material, &private_key);
    flags |= material.flags & 1U;
    if (result < 0) goto done;
    if (ctos_ssh_material_deadline(deadline) != 0) { result = -1; goto done; }
    if (private_key == NULL) { status = pair_material_status(material.status); goto done; }
    if (!pair_software_key(private_key)) { status = CTOS_PAIR_UNSUPPORTED; goto done; }
    stage = CTOS_PAIR_PEER;
    material = (struct material_result){.status = CTOS_MATERIAL_MALFORMED};
    result = text_bounds(peer_body, peer_length, &material);
    if (result < 0) goto done;
    if (result != 0) { result = 0; goto done; }
    result = parse_public(peer_type, peer_body, peer_length, &material, &peer_key);
    if (result < 0) goto done;
    if (ctos_ssh_material_deadline(deadline) != 0) { result = -1; goto done; }
    if (peer_key == NULL) { status = pair_material_status(material.status); goto done; }
    error = sshkey_from_private(peer_key, &subject);
    if (error == 0 && sshkey_is_cert(subject)) error = sshkey_drop_cert(subject);
    if (error != 0) { result = parse_system_error(error); goto done; }
    if (!pair_software_key(subject)) { status = CTOS_PAIR_UNSUPPORTED; goto done; }
    stage = CTOS_PAIR_RELATION;
    if (!sshkey_equal_public(private_key, subject)) { status = CTOS_PAIR_MISMATCH; goto done; }
    stage = CTOS_PAIR_SIGNER;
    result = signing_proof(private_key, subject, domain, sizeof(domain), deadline, digest);
    if (result <= 0) { status = CTOS_PAIR_MISMATCH; goto done; }
    result = 0;
    status = CTOS_PAIR_MATCH; stage = CTOS_PAIR_COMPLETE; flags |= 8U | 16U; kind = key_kind(subject);
done:
    saved = errno;
    sshkey_free(subject); sshkey_free(peer_key); sshkey_free(private_key);
    wipe(&material, sizeof(material));
    errno = saved;
    if (result < 0 || ctos_ssh_material_deadline(deadline) != 0) { wipe(digest, sizeof(digest)); return -1; }
    put32(output, 0x43534b41U); put32(output + 4, 1); put32(output + 8, status);
    put32(output + 12, kind); put32(output + 16, flags); put32(output + 20, stage); put32(output + 24, peer_type);
    if (status == CTOS_PAIR_MATCH) memcpy(output + 28, digest, sizeof(digest));
    wipe(digest, sizeof(digest));
    if (ctos_ssh_material_deadline(deadline) != 0) {
        wipe(output, CTOS_SSH_KEY_ASSOCIATION_WIRE_BYTES); return -1;
    }
    return 0;
}
int ctos_ssh_material_verify_private(const unsigned char *body, size_t length,
    const unsigned char *passphrase, size_t passphrase_length, uint64_t deadline,
    unsigned char output[CTOS_SSH_PRIVATE_MATERIAL_WIRE_BYTES]) {
    if (output != NULL) wipe(output, CTOS_SSH_PRIVATE_MATERIAL_WIRE_BYTES);
    if (length > CTOS_SSH_MATERIAL_BYTES || passphrase_length > CTOS_SSH_MATERIAL_PASSPHRASE_BYTES) { errno = E2BIG; return -1; }
    if (output == NULL || (body == NULL && length != 0) || (passphrase == NULL && passphrase_length != 0)
        || (passphrase_length != 0 && memchr(passphrase, 0, passphrase_length) != NULL)) { errno = EINVAL; return -1; }
    if (ctos_ssh_material_deadline(deadline) != 0) return -1;
    struct material_result material = {.status = CTOS_MATERIAL_MALFORMED};
    struct sshkey *private_key = NULL, *subject = NULL;
    static const unsigned char domain[] = "ctOS SSH private material proof v1";
    unsigned char digest[32] = {0};
    unsigned int status = CTOS_PAIR_MALFORMED, stage = CTOS_PAIR_PRIVATE, flags = 4U, kind = 0;
    int result = text_bounds(body, length, &material), error, saved;
    if (result < 0) goto done;
    if (result != 0) { result = 0; goto done; }
    if (OPENSSL_init_crypto(OPENSSL_INIT_NO_LOAD_CONFIG, NULL) != 1) { errno = EIO; result = -1; goto done; }
    result = parse_private(body, length, passphrase, passphrase_length, 1, &material, &private_key);
    flags |= material.flags & 1U;
    if (result < 0) goto done;
    if (ctos_ssh_material_deadline(deadline) != 0) { result = -1; goto done; }
    if (private_key == NULL) { status = pair_material_status(material.status); goto done; }
    if (!pair_software_key(private_key)) { status = CTOS_PAIR_UNSUPPORTED; goto done; }
    /* This is the parsed private's own public subject, not a fabricated peer. */
    error = sshkey_from_private(private_key, &subject);
    if (error != 0) { result = parse_system_error(error); goto done; }
    stage = CTOS_PAIR_SIGNER;
    result = signing_proof(private_key, subject, domain, sizeof(domain), deadline, digest);
    if (result <= 0) { status = CTOS_PAIR_MISMATCH; goto done; }
    result = 0;
    status = CTOS_PAIR_MATCH; stage = CTOS_PAIR_COMPLETE; flags |= 8U | 16U; kind = key_kind(subject);
done:
    saved = errno;
    sshkey_free(subject); sshkey_free(private_key); wipe(&material, sizeof(material));
    errno = saved;
    if (result < 0 || ctos_ssh_material_deadline(deadline) != 0) { wipe(digest, sizeof(digest)); return -1; }
    put32(output, 0x43534b50U); put32(output + 4, 1); put32(output + 8, status);
    put32(output + 12, kind); put32(output + 16, flags); put32(output + 20, stage); put32(output + 24, CTOS_SSH_PRIVATE_KEY);
    if (status == CTOS_PAIR_MATCH) memcpy(output + 28, digest, sizeof(digest));
    wipe(digest, sizeof(digest));
    if (ctos_ssh_material_deadline(deadline) != 0) {
        wipe(output, CTOS_SSH_PRIVATE_MATERIAL_WIRE_BYTES); return -1;
    }
    return 0;
}
static unsigned int certificate_signature_algorithm(const char *name) {
    static const char *const algorithms[] = {"ssh-ed25519", "ecdsa-sha2-nistp256",
        "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521", "rsa-sha2-256", "rsa-sha2-512"};
    if (name != NULL) for (size_t i = 0; i < sizeof(algorithms) / sizeof(algorithms[0]); i++)
        if (strcmp(name, algorithms[i]) == 0) return (unsigned int)i + 1U;
    return 0;
}
static void put64(unsigned char *out, uint64_t value) {
    put32(out, (uint32_t)(value >> 32)); put32(out + 4, (uint32_t)value);
}
struct certificate_result {
    unsigned int status, type, subject_kind, ca_kind, algorithm, principals, critical, extensions, line;
    uint64_t serial, valid_after, valid_before;
    unsigned char digests[3][32];
};
int ctos_ssh_material_inspect_certificate(const unsigned char *body, size_t length,
    uint64_t deadline, unsigned char output[CTOS_SSH_CERTIFICATE_FACTS_WIRE_BYTES]) {
    if (output != NULL) wipe(output, CTOS_SSH_CERTIFICATE_FACTS_WIRE_BYTES);
    if (length > CTOS_SSH_MATERIAL_BYTES) { errno = E2BIG; return -1; }
    if (output == NULL || (body == NULL && length != 0)) { errno = EINVAL; return -1; }
    if (ctos_ssh_material_deadline(deadline) != 0) return -1;
    struct material_result parsed = {.status = CTOS_MATERIAL_MALFORMED}, hashes[3] = {0};
    struct certificate_result facts = {.status = CTOS_CERTIFICATE_MALFORMED};
    struct sshkey *certificate_key = NULL, *subject = NULL;
    int result = text_bounds(body, length, &parsed), error, saved;
    facts.line = parsed.line;
    if (result < 0) goto done;
    if (result != 0) { result = 0; goto done; }
    if (OPENSSL_init_crypto(OPENSSL_INIT_NO_LOAD_CONFIG, NULL) != 1) { errno = EIO; result = -1; goto done; }
    result = parse_public(CTOS_SSH_CERTIFICATE, body, length, &parsed, &certificate_key);
    facts.line = parsed.line;
    if (result < 0) goto done;
    if (ctos_ssh_material_deadline(deadline) != 0) { result = -1; goto done; }
    if (certificate_key == NULL) goto done;
    struct sshkey_cert *cert = certificate_key->cert;
    if (cert == NULL || cert->signature_key == NULL || cert->critical == NULL || cert->extensions == NULL
        || facts.line == 0 || facts.line > CTOS_SSH_MATERIAL_LINES
        || (cert->type != SSH2_CERT_TYPE_USER && cert->type != SSH2_CERT_TYPE_HOST)
        || cert->nprincipals > SSHKEY_CERT_MAX_PRINCIPALS
        || sshbuf_len(cert->critical) > CTOS_SSH_MATERIAL_BYTES
        || sshbuf_len(cert->extensions) > CTOS_SSH_MATERIAL_BYTES) goto done;
    error = sshkey_from_private(certificate_key, &subject);
    if (error == 0) error = sshkey_drop_cert(subject);
    if (error != 0) { result = parse_system_error(error); goto done; }
    if (!pair_software_key(subject) || !pair_software_key(cert->signature_key)) {
        facts.status = CTOS_CERTIFICATE_UNSUPPORTED; goto done;
    }
    static const char allowed[] = "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,"
        "ecdsa-sha2-nistp521,rsa-sha2-256,rsa-sha2-512";
    unsigned int algorithm = certificate_signature_algorithm(cert->signature_type);
    unsigned int ca_kind = key_kind(cert->signature_key);
    error = sshkey_check_cert_sigtype(certificate_key, allowed);
    if (error != 0) {
        result = parse_system_error(error); facts.status = CTOS_CERTIFICATE_UNSUPPORTED; goto done;
    }
    if (algorithm == 0 || (ca_kind < 5U ? algorithm != ca_kind : algorithm != 5U && algorithm != 6U)) {
        facts.status = CTOS_CERTIFICATE_UNSUPPORTED; goto done;
    }
    struct sshkey *keys[] = {certificate_key, subject, cert->signature_key};
    for (size_t i = 0; i < 3U; i++) {
        if (ctos_ssh_material_deadline(deadline) != 0) { result = -1; goto done; }
        result = key_result(keys[i], &hashes[i]);
        if (result < 0) goto done;
        if (hashes[i].status != CTOS_MATERIAL_PARSED) {
            facts.status = hashes[i].status == CTOS_MATERIAL_UNSUPPORTED
                ? CTOS_CERTIFICATE_UNSUPPORTED : CTOS_CERTIFICATE_MALFORMED;
            goto done;
        }
        if (ctos_ssh_material_deadline(deadline) != 0) { result = -1; goto done; }
        unsigned int bits = 0;
        for (size_t j = 0; j < sizeof(hashes[i].digest); j++) bits |= hashes[i].digest[j];
        if (bits == 0) { errno = EIO; result = -1; goto done; }
    }
    facts.type = cert->type; facts.subject_kind = hashes[1].kind; facts.ca_kind = ca_kind;
    facts.algorithm = algorithm; facts.principals = cert->nprincipals;
    facts.critical = (unsigned int)sshbuf_len(cert->critical);
    facts.extensions = (unsigned int)sshbuf_len(cert->extensions);
    facts.serial = cert->serial; facts.valid_after = cert->valid_after; facts.valid_before = cert->valid_before;
    for (size_t i = 0; i < 3U; i++) memcpy(facts.digests[i], hashes[i].digest, sizeof(facts.digests[i]));
    facts.status = CTOS_CERTIFICATE_FACTS;
done:
    saved = errno;
    sshkey_free(subject); sshkey_free(certificate_key);
    wipe(&parsed, sizeof(parsed)); wipe(hashes, sizeof(hashes));
    errno = saved;
    if (result < 0 || ctos_ssh_material_deadline(deadline) != 0) { wipe(&facts, sizeof(facts)); return -1; }
    put32(output, 0x43534346U); put32(output + 4, 1); put32(output + 8, facts.status);
    put32(output + 12, facts.status == CTOS_CERTIFICATE_FACTS ? 7U : 1U);
    put32(output + 44, facts.line);
    if (facts.status == CTOS_CERTIFICATE_FACTS) {
        put32(output + 16, facts.type); put32(output + 20, facts.subject_kind); put32(output + 24, facts.ca_kind);
        put32(output + 28, facts.algorithm); put32(output + 32, facts.principals);
        put32(output + 36, facts.critical); put32(output + 40, facts.extensions);
        put64(output + 48, facts.serial); put64(output + 56, facts.valid_after); put64(output + 64, facts.valid_before);
        for (size_t i = 0; i < 3U; i++) memcpy(output + 72U + i * 32U, facts.digests[i], 32U);
    }
    wipe(&facts, sizeof(facts));
    if (ctos_ssh_material_deadline(deadline) != 0) {
        wipe(output, CTOS_SSH_CERTIFICATE_FACTS_WIRE_BYTES); return -1;
    }
    return 0;
}

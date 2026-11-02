/* Reuse the real material baseline and its forbidden-effect observers. */
#define main material_baseline_main
#define __wrap_clock_gettime material_clock_observer
#define __wrap_fmemopen material_stream_observer
#define __wrap_ferror material_read_observer
#define __wrap_fclose material_close_observer
#include "ssh_material_inspect_test.c"
#undef main
#undef __wrap_clock_gettime
#undef __wrap_fmemopen
#undef __wrap_ferror
#undef __wrap_fclose
#include "hostfile.h"
#include "ssh_host_trust.h"

static unsigned int trust_expire_at, trust_clock_error_at, trust_fail_stream_at, trust_stream_calls;
static unsigned int trust_read_error_at, trust_close_error_at;
int __wrap_clock_gettime(clockid_t clock, struct timespec *now) {
    int result = material_clock_observer(clock, now);
    if (observe && clock == CLOCK_BOOTTIME && result == 0
        && trust_expire_at != 0 && clock_calls >= trust_expire_at) now->tv_sec += 61;
    if (observe && clock == CLOCK_BOOTTIME && trust_clock_error_at != 0 && clock_calls >= trust_clock_error_at) {
        errno = EIO; return -1;
    }
    return result;
}
FILE *__wrap_fmemopen(void *p, size_t n, const char *m) {
    if (observe && ++trust_stream_calls == trust_fail_stream_at) { errno = ENOMEM; return NULL; }
    return material_stream_observer(p, n, m);
}
int __wrap_ferror(FILE *stream) {
    return observe && trust_read_error_at != 0 && trust_stream_calls == trust_read_error_at
        ? 1 : material_read_observer(stream);
}
int __wrap_fclose(FILE *stream) {
    int result = material_close_observer(stream);
    if (observe && trust_close_error_at != 0 && trust_stream_calls == trust_close_error_at) {
        errno = EIO; return EOF;
    }
    return result;
}
static int trust_inspect(const void *body, size_t length, const char *host, unsigned int port,
    int prepare, unsigned char **wire, size_t *wire_length) {
    unsigned char *copy = malloc(length == 0 ? 1 : length); CHECK(copy != NULL);
    if (length != 0) memcpy(copy, body, length);
    unsigned int before_effects = effects, before_streams = streams, before_closed = closed_streams;
    clock_calls = 0; trust_stream_calls = 0; observe = 1;
    int result = ctos_ssh_host_trust_inspect(body, length, (const unsigned char *)host,
        host == NULL ? 0 : strlen(host), port, prepare, now_ms() + 60000U, wire, wire_length);
    observe = 0;
    CHECK(effects == before_effects && streams - before_streams == closed_streams - before_closed);
    CHECK(length == 0 || memcmp(body, copy, length) == 0); free(copy);
    if (result != 0) { CHECK(wire == NULL || *wire == NULL); CHECK(wire_length == NULL || *wire_length == 0); }
    return result;
}
static void free_trust(unsigned char *wire, size_t length) { if (wire != NULL) { memset(wire, 0, length); free(wire); } }
static void trust_header(const unsigned char *wire, size_t length, unsigned int mode,
    unsigned int flags, unsigned int rows, unsigned int replacement) {
    CHECK(length == CTOS_SSH_HOST_TRUST_HEADER_BYTES + rows * CTOS_SSH_HOST_TRUST_RECORD_BYTES + replacement);
    CHECK(u32(wire) == 0x43534854U && u32(wire + 4) == 1 && u32(wire + 8) == mode);
    CHECK(u32(wire + 12) == flags && u32(wire + 16) == rows && u32(wire + 20) == replacement);
    CHECK(!zero(wire + 24, 32) && ((flags & 2U) != 0) != zero(wire + 56, 32));
}
static const unsigned char *trust_row(const unsigned char *wire, unsigned int index,
    unsigned int line, unsigned int marker, unsigned int selector, unsigned int reason,
    const struct sshkey *key) {
    const unsigned char *p = wire + CTOS_SSH_HOST_TRUST_HEADER_BYTES + index * CTOS_SSH_HOST_TRUST_RECORD_BYTES;
    CHECK(u32(p) == line && u32(p + 4) == marker && u32(p + 8) == selector && u32(p + 12) == reason);
    const char *algorithm = sshkey_ssh_name(key);
    char *fingerprint = sshkey_fingerprint(key, SSH_DIGEST_SHA256, SSH_FP_DEFAULT); CHECK(fingerprint != NULL);
    CHECK(u32(p + 16) == strlen(algorithm) && u32(p + 20) == strlen(fingerprint));
    CHECK(memcmp(p + 24, algorithm, strlen(algorithm)) == 0
        && zero(p + 24 + strlen(algorithm), 128U - strlen(algorithm)));
    CHECK(memcmp(p + 152, fingerprint, strlen(fingerprint)) == 0
        && zero(p + 152 + strlen(fingerprint), 64U - strlen(fingerprint)));
    free(fingerprint); return p;
}
static void append_trust(struct sshbuf *body, const char *selector, const struct sshbuf *pub, const char *suffix) {
    CHECK(sshbuf_put(body, selector, strlen(selector)) == 0 && sshbuf_put(body, " ", 1) == 0
        && sshbuf_putb(body, pub) == 0 && sshbuf_put(body, suffix, strlen(suffix)) == 0);
}
static void trust_digest(const unsigned char *wire, const void *body, size_t length,
    const void *replacement, size_t replacement_length) {
    unsigned char digest[32];
    CHECK(ssh_digest_memory(SSH_DIGEST_SHA256, length == 0 ? "" : body, length, digest, sizeof(digest)) == 0);
    CHECK(memcmp(wire + 24, digest, sizeof(digest)) == 0);
    if ((u32(wire + 12) & 2U) != 0) {
        CHECK(ssh_digest_memory(SSH_DIGEST_SHA256, replacement_length == 0 ? "" : replacement,
            replacement_length, digest, sizeof(digest)) == 0);
        CHECK(memcmp(wire + 56, digest, sizeof(digest)) == 0);
    }
}
static void trust_algorithms(void) {
    const int types[] = {KEY_ED25519, KEY_ECDSA, KEY_ECDSA, KEY_ECDSA, KEY_RSA};
    const unsigned int bits[] = {0, 256, 384, 521, 1024};
    for (size_t i = 0; i < 5; i++) {
        struct sshkey *key = NULL; CHECK(sshkey_generate(types[i], bits[i], &key) == 0);
        struct sshbuf *pub = public_text(key), *body = sshbuf_new(); CHECK(body != NULL);
        append_trust(body, "TRUST.INVALID", pub, "\r\n");
        unsigned char *wire = NULL; size_t length = 0;
        CHECK(trust_inspect(sshbuf_ptr(body), sshbuf_len(body), "Trust.Invalid", 22, 0, &wire, &length) == 0);
        trust_header(wire, length, 0, 1, 1, 0); trust_row(wire, 0, 1, 0, 0, 0, key);
        trust_digest(wire, sshbuf_ptr(body), sshbuf_len(body), NULL, 0); free_trust(wire, length);
        CHECK(trust_inspect(sshbuf_ptr(body), sshbuf_len(body), "trust.invalid", 22, 1, &wire, &length) == 0);
        trust_header(wire, length, 1, 3, 1, 0); trust_digest(wire, sshbuf_ptr(body), sshbuf_len(body), NULL, 0);
        free_trust(wire, length); sshbuf_free(pub); sshbuf_free(body); sshkey_free(key);
    }
}
static void trust_preservation(void) {
    struct sshkey *key = NULL; CHECK(sshkey_generate(KEY_ED25519, 0, &key) == 0);
    struct sshbuf *pub = public_text(key), *body = sshbuf_new(), *expected = sshbuf_new();
    CHECK(body != NULL && expected != NULL);
    const char *prefix = "# untouched CRLF\r\n\n \t\n";
    CHECK(sshbuf_put(body, prefix, strlen(prefix)) == 0 && sshbuf_put(expected, prefix, strlen(prefix)) == 0);
    append_trust(body, "other.invalid", pub, " keep\r\n");
    append_trust(expected, "other.invalid", pub, " keep\r\n");
    append_trust(body, " \ttrust.invalid", pub, " remove first\r\n");
    append_trust(body, "trust.invalid", pub, " remove duplicate\n");
    append_trust(body, "@revoked other.invalid", pub, " protected\r\n");
    append_trust(expected, "@revoked other.invalid", pub, " protected\r\n");
    const char *ending = "# final comment without newline";
    CHECK(sshbuf_put(body, ending, strlen(ending)) == 0 && sshbuf_put(expected, ending, strlen(ending)) == 0);
    unsigned char *wire = NULL; size_t length = 0;
    CHECK(trust_inspect(sshbuf_ptr(body), sshbuf_len(body), "trust.invalid", 22, 1, &wire, &length) == 0);
    trust_header(wire, length, 1, 3, 2, (unsigned int)sshbuf_len(expected));
    trust_row(wire, 0, 5, 0, 0, 0, key); trust_row(wire, 1, 6, 0, 0, 0, key);
    CHECK(memcmp(wire + 88 + 2 * 216, sshbuf_ptr(expected), sshbuf_len(expected)) == 0);
    trust_digest(wire, sshbuf_ptr(body), sshbuf_len(body), sshbuf_ptr(expected), sshbuf_len(expected));
    free_trust(wire, length); sshbuf_reset(body);
    append_trust(body, "trust.invalid", pub, "");
    CHECK(trust_inspect(sshbuf_ptr(body), sshbuf_len(body), "trust.invalid", 22, 1, &wire, &length) == 0);
    trust_header(wire, length, 1, 3, 1, 0); free_trust(wire, length);
    sshbuf_free(pub); sshbuf_free(body); sshbuf_free(expected); sshkey_free(key);
}
static void trust_targets(void) {
    struct sshkey *key = NULL; CHECK(sshkey_generate(KEY_ED25519, 0, &key) == 0);
    struct sshbuf *pub = public_text(key);
    const char *selectors[] = {"trust.invalid", "[trust.invalid]:2200", "127.0.0.1", "[127.0.0.1]:2200",
        "2001:db8::1", "[2001:db8::1]:2200", "[2001:db8::1]", "[[2001:db8::1]]:2200"};
    const char *hosts[] = {"TRUST.INVALID", "trust.invalid", "127.0.0.1", "127.0.0.1",
        "2001:DB8::1", "2001:db8::1", "[2001:DB8::1]", "[2001:db8::1]"};
    for (size_t i = 0; i < sizeof(hosts) / sizeof(hosts[0]); i++) {
        for (int hashed = 0; hashed < 2; hashed++) {
            char *hash = hashed ? host_hash(selectors[i], NULL, 0) : NULL;
            CHECK(!hashed || hash != NULL);
            struct sshbuf *body = sshbuf_new(); CHECK(body != NULL);
            append_trust(body, hashed ? hash : selectors[i], pub, "\n"); free(hash);
            unsigned char *wire = NULL; size_t length = 0;
            unsigned int port = i % 2U == 0 ? 22U : 2200U;
            CHECK(trust_inspect(sshbuf_ptr(body), sshbuf_len(body), hosts[i], port, 1, &wire, &length) == 0);
            trust_header(wire, length, 1, 3, 1, 0); trust_row(wire, 0, 1, 0, (unsigned int)hashed, 0, key);
            free_trust(wire, length);
            CHECK(trust_inspect(sshbuf_ptr(body), sshbuf_len(body), hosts[i], port == 22 ? 2200 : 22,
                1, &wire, &length) == 0);
            trust_header(wire, length, 1, 0, 0, 0); free_trust(wire, length);
            sshbuf_free(body);
        }
    }
    sshbuf_free(pub); sshkey_free(key);
}
static void trust_restrictions(void) {
    struct sshkey *key = NULL, *ca = NULL;
    CHECK(sshkey_generate(KEY_ED25519, 0, &key) == 0 && sshkey_generate(KEY_ED25519, 0, &ca) == 0);
    struct sshbuf *pub = public_text(key);
    const char *selectors[] = {"trust.invalid,other.invalid", "*.invalid", "!excluded.invalid,*.invalid",
        "@revoked trust.invalid", "@cert-authority *.invalid"};
    const unsigned int markers[] = {0, 0, 0, 1, 2};
    for (size_t i = 0; i < sizeof(selectors) / sizeof(selectors[0]); i++) {
        struct sshbuf *body = sshbuf_new(); CHECK(body != NULL);
        append_trust(body, "trust.invalid", pub, "\n"); append_trust(body, selectors[i], pub, "\n");
        unsigned char *wire = NULL; size_t length = 0;
        CHECK(trust_inspect(sshbuf_ptr(body), sshbuf_len(body), "trust.invalid", 22, 1, &wire, &length) == 0);
        trust_header(wire, length, 1, 0, 2, 0); trust_row(wire, 0, 1, 0, 0, 0, key);
        unsigned int selector = i < 3 || i == 4 ? 2 : 0;
        trust_row(wire, 1, 2, markers[i], selector, markers[i] == 0 ? 1 : 2, key);
        free_trust(wire, length); sshbuf_free(body);
    }
    struct sshbuf *body = sshbuf_new(); CHECK(body != NULL);
    append_trust(body, "!trust.invalid,*.invalid", pub, "\n");
    size_t kept_length = sshbuf_len(body);
    append_trust(body, "trust.invalid", pub, "\n");
    unsigned char *wire = NULL; size_t length = 0;
    CHECK(trust_inspect(sshbuf_ptr(body), sshbuf_len(body), "trust.invalid", 22, 1, &wire, &length) == 0);
    trust_header(wire, length, 1, 3, 1, (unsigned int)kept_length);
    trust_row(wire, 0, 2, 0, 0, 0, key); free_trust(wire, length); sshbuf_reset(body);
    struct sshkey *cert = certificate(key, ca); struct sshbuf *cert_pub = public_text(cert);
    append_trust(body, "trust.invalid", cert_pub, "\n");
    CHECK(trust_inspect(sshbuf_ptr(body), sshbuf_len(body), "trust.invalid", 22, 1, &wire, &length) == 0);
    trust_header(wire, length, 1, 0, 1, 0); trust_row(wire, 0, 1, 0, 0, 3, cert); free_trust(wire, length);
    sshbuf_free(cert_pub); sshkey_free(cert); sshbuf_free(body); sshbuf_free(pub); sshkey_free(key); sshkey_free(ca);
}
static void trust_failures(void) {
    unsigned char *wire = NULL; size_t length = 0;
    CHECK(trust_inspect(NULL, 0, "trust.invalid", 22, 1, &wire, &length) == 0);
    trust_header(wire, length, 1, 0, 0, 0); free_trust(wire, length);
    const char *bad[] = {"# ok\nother.invalid ssh-ed25519 AAAA\n", "|1|AA==|AA== ssh-ed25519 AAAA\n", "junk\n"};
    for (size_t i = 0; i < sizeof(bad) / sizeof(bad[0]); i++)
        CHECK(trust_inspect(bad[i], strlen(bad[i]), "trust.invalid", 22, 1, &wire, &length) == -1 && errno == EINVAL);
    const unsigned char nul[] = {'#', 0, '\n'};
    CHECK(trust_inspect(nul, sizeof(nul), "trust.invalid", 22, 0, &wire, &length) == -1 && errno == EINVAL);
    const char *invalid_hosts[] = {"", "*", "a b", "a\nb", "a,b", "!a", "a/../b", "a|b", "a?b"};
    for (size_t i = 0; i < sizeof(invalid_hosts) / sizeof(invalid_hosts[0]); i++)
        CHECK(trust_inspect(NULL, 0, invalid_hosts[i], 22, 0, &wire, &length) == -1 && errno == EINVAL);
    CHECK(trust_inspect(NULL, 0, "host", 0, 0, &wire, &length) == -1 && errno == EINVAL);
    CHECK(trust_inspect(NULL, 0, "host", 65536, 0, &wire, &length) == -1 && errno == EINVAL);
    CHECK(trust_inspect(NULL, 0, "host", 22, 2, &wire, &length) == -1 && errno == EINVAL);
    CHECK(trust_inspect(NULL, 0, "host", 22, 0, NULL, &length) == -1 && errno == EINVAL);
    CHECK(trust_inspect(NULL, 0, "host", 22, 0, &wire, NULL) == -1 && errno == EINVAL);
    char long_host[255]; memset(long_host, 'a', 254); long_host[254] = 0;
    CHECK(trust_inspect(NULL, 0, long_host, 22, 0, &wire, &length) == -1 && errno == EINVAL);
    long_host[253] = 0;
    CHECK(trust_inspect(NULL, 0, long_host, 22, 0, &wire, &length) == 0);
    trust_header(wire, length, 0, 0, 0, 0); free_trust(wire, length);
    unsigned char *large = malloc(CTOS_SSH_MATERIAL_BYTES + 1U); CHECK(large != NULL);
    memset(large, '\n', CTOS_SSH_MATERIAL_BYTES + 1U);
    CHECK(trust_inspect(large, CTOS_SSH_MATERIAL_BYTES + 1U, "host", 22, 0, &wire, &length) == -1 && errno == E2BIG);
    CHECK(trust_inspect(large, CTOS_SSH_MATERIAL_LINES + 1U, "host", 22, 0, &wire, &length) == -1 && errno == E2BIG);
    CHECK(trust_inspect(large, CTOS_SSH_MATERIAL_LINES, "host", 22, 0, &wire, &length) == 0);
    trust_header(wire, length, 0, 0, 0, 0); free_trust(wire, length);
    memset(large, 'a', CTOS_SSH_MATERIAL_LINE_BYTES + 1U);
    CHECK(trust_inspect(large, CTOS_SSH_MATERIAL_LINE_BYTES + 1U, "host", 22, 0, &wire, &length) == -1 && errno == E2BIG);
    memset(large, 'x', CTOS_SSH_MATERIAL_BYTES);
    for (unsigned int i = 0; i < 16; i++) {
        large[i * 65536U] = '#'; large[(i + 1U) * 65536U - 1U] = '\n';
    }
    CHECK(trust_inspect(large, CTOS_SSH_MATERIAL_BYTES, "host", 22, 0, &wire, &length) == 0);
    trust_header(wire, length, 0, 0, 0, 0); free_trust(wire, length);
    free(large);
    struct sshkey *key = NULL; CHECK(sshkey_generate(KEY_ED25519, 0, &key) == 0);
    struct sshbuf *pub = public_text(key), *body = sshbuf_new(); CHECK(body != NULL);
    for (unsigned int i = 0; i < 128; i++) append_trust(body, "trust.invalid", pub, "\n");
    CHECK(trust_inspect(sshbuf_ptr(body), sshbuf_len(body), "trust.invalid", 22, 1, &wire, &length) == 0);
    trust_header(wire, length, 1, 3, 128, 0); free_trust(wire, length);
    append_trust(body, "trust.invalid", pub, "\n");
    CHECK(trust_inspect(sshbuf_ptr(body), sshbuf_len(body), "trust.invalid", 22, 1, &wire, &length) == -1 && errno == E2BIG);
    sshbuf_reset(body); append_trust(body, "trust.invalid", pub, "\n");
    struct sshbuf *bad_hash = sshbuf_new(); CHECK(bad_hash != NULL);
    append_trust(bad_hash, "|1|AA==|AA==", pub, "\n");
    CHECK(trust_inspect(sshbuf_ptr(bad_hash), sshbuf_len(bad_hash), "trust.invalid", 22, 0,
        &wire, &length) == -1 && errno == EINVAL);
    sshbuf_free(bad_hash);
    fail_stream_read = 1;
    CHECK(trust_inspect(sshbuf_ptr(body), sshbuf_len(body), "trust.invalid", 22, 0, &wire, &length) == -1 && errno == EIO);
    fail_stream_read = 0; fail_stream_close = 1;
    CHECK(trust_inspect(sshbuf_ptr(body), sshbuf_len(body), "trust.invalid", 22, 0, &wire, &length) == -1 && errno == EIO);
    fail_stream_close = 0;
    for (unsigned int i = 1; i <= 2; i++) {
        trust_fail_stream_at = i;
        CHECK(trust_inspect(sshbuf_ptr(body), sshbuf_len(body), "trust.invalid", 22, 0, &wire, &length) == -1 && errno == ENOMEM);
    }
    trust_fail_stream_at = 0;
    for (unsigned int i = 1; i <= 2; i++) {
        trust_read_error_at = i;
        CHECK(trust_inspect(sshbuf_ptr(body), sshbuf_len(body), "trust.invalid", 22, 0,
            &wire, &length) == -1 && errno == EIO);
        trust_read_error_at = 0; trust_close_error_at = i;
        CHECK(trust_inspect(sshbuf_ptr(body), sshbuf_len(body), "trust.invalid", 22, 0,
            &wire, &length) == -1 && errno == EIO);
        trust_close_error_at = 0;
    }
    CHECK(trust_inspect(sshbuf_ptr(body), sshbuf_len(body), "trust.invalid", 22, 0, &wire, &length) == 0);
    unsigned int total_clock_calls = clock_calls; free_trust(wire, length);
    CHECK(total_clock_calls >= 5);
    for (unsigned int i = 1; i <= total_clock_calls; i++) {
        trust_expire_at = i;
        CHECK(trust_inspect(sshbuf_ptr(body), sshbuf_len(body), "trust.invalid", 22, 0, &wire, &length) == -1 && errno == ETIMEDOUT);
    }
    trust_expire_at = 0;
    for (unsigned int i = 1; i <= total_clock_calls; i++) {
        trust_clock_error_at = i;
        CHECK(trust_inspect(sshbuf_ptr(body), sshbuf_len(body), "trust.invalid", 22, 0,
            &wire, &length) == -1 && errno == EIO);
    }
    trust_clock_error_at = 0;
    uint64_t deadline = now_ms();
    wire = (unsigned char *)(uintptr_t)1; length = 1337;
    CHECK(ctos_ssh_host_trust_inspect(NULL, 0, (const unsigned char *)"host", 4, 22, 0,
        deadline, &wire, &length) == -1 && errno == ETIMEDOUT && wire == NULL && length == 0);
    CHECK(ctos_ssh_host_trust_inspect(NULL, 0, (const unsigned char *)"host", 4, 22, 0,
        now_ms() + 61000U, &wire, &length) == -1 && errno == EINVAL && wire == NULL && length == 0);
    sshbuf_free(body); sshbuf_free(pub); sshkey_free(key);
}
int main(void) {
    CHECK(material_baseline_main() == 0);
    unsigned int before = fd_count(), before_checks = checks;
    trust_algorithms(); trust_preservation(); trust_targets(); trust_restrictions(); trust_failures();
    CHECK(fd_count() == before && effects == 0 && streams == closed_streams);
    printf("SSH_HOST_TRUST checks=%u baseline_and_trust=%u effects=%u streams=%u cleanup=pass\n",
        checks - before_checks, checks, effects, streams);
    return 0;
}

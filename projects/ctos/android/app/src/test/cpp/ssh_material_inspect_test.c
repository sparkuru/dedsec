#define _GNU_SOURCE
#include <stdlib.h>
#include "includes.h"
#include <sys/stat.h>
#include <sys/socket.h>
#include <netdb.h>
#include <pwd.h>
#include <dirent.h>
#include <ifaddrs.h>
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <openssl/crypto.h>
#include <openssl/pem.h>
#include "digest.h"
#include "log.h"
#include "ssh2.h"
#include "sshbuf.h"
#include "sshkey.h"
#include "ssh_file_view.h"
#include "ssh_key_policy.h"
#include "ssh_material_inspect.h"
#ifdef malloc
#undef malloc
#endif
#ifdef calloc
#undef calloc
#endif
#ifdef realloc
#undef realloc
#endif
#ifdef CTOS_MATERIAL_STATIC_TEST
_Thread_local _Alignas(64) unsigned char ctos_static_test_tls_alignment;
#endif
static unsigned int checks, effects, safe_logs, streams, closed_streams, clock_calls;
static int observe;
static unsigned int private_parses, kdf_calls;
int __real_sshkey_parse_private_fileblob(struct sshbuf *, const char *, struct sshkey **, char **);
int __wrap_sshkey_parse_private_fileblob(struct sshbuf *file, const char *passphrase, struct sshkey **key, char **comment) {
    if (observe) private_parses++;
    return __real_sshkey_parse_private_fileblob(file, passphrase, key, comment);
}
int __real_bcrypt_pbkdf(const char *, size_t, const uint8_t *, size_t, uint8_t *, size_t, unsigned int);
int __wrap_bcrypt_pbkdf(const char *p, size_t pn, const uint8_t *s, size_t sn, uint8_t *k, size_t kn, unsigned int r) {
    if (observe) kdf_calls++;
    return __real_bcrypt_pbkdf(p, pn, s, sn, k, kn, r);
}
static int expire_after_first, fail_stream_read, fail_stream_close;
#define CHECK(c) do { checks++; if (!(c)) { fprintf(stderr, "material failure line=%d errno=%d\n", __LINE__, errno); abort(); } } while (0)
static int deny(void) { if (observe) effects++; errno = EACCES; return -1; }
FILE *__wrap_fopen(const char *p, const char *m) { (void)p; (void)m; deny(); return NULL; }
FILE *__wrap_fopen64(const char *p, const char *m) { return __wrap_fopen(p, m); }
int __wrap_open(const char *p, int f, ...) { (void)p; (void)f; return deny(); }
int __wrap_open64(const char *p, int f, ...) { (void)p; (void)f; return deny(); }
int __wrap_access(const char *p, int f) { (void)p; (void)f; return deny(); }
int __wrap_stat(const char *p, struct stat *s) { (void)p; (void)s; return deny(); }
int __wrap_lstat(const char *p, struct stat *s) { (void)p; (void)s; return deny(); }
DIR *__wrap_opendir(const char *p) { (void)p; deny(); return NULL; }
struct passwd *__wrap_getpwuid(uid_t u) { (void)u; deny(); return NULL; }
int __wrap_gethostname(char *p, size_t n) { (void)p; (void)n; return deny(); }
struct servent *__wrap_getservbyname(const char *p, const char *q) { (void)p; (void)q; deny(); return NULL; }
char *__wrap_getenv(const char *p) { (void)p; deny(); return NULL; }
int __wrap_getifaddrs(struct ifaddrs **p) { (void)p; return deny(); }
int __wrap_getaddrinfo(const char *p, const char *q, const struct addrinfo *h, struct addrinfo **r) {
    (void)p; (void)q; (void)h; (void)r; deny(); return EAI_FAIL;
}
int __wrap_socket(int d, int t, int p) { (void)d; (void)t; (void)p; return deny(); }
int __wrap_connect(int f, const struct sockaddr *s, socklen_t n) { (void)f; (void)s; (void)n; return deny(); }
pid_t __wrap_fork(void) { return (pid_t)deny(); }
pid_t __wrap_vfork(void) { return (pid_t)deny(); }
int __wrap_execv(const char *p, char *const a[]) { (void)p; (void)a; return deny(); }
int __wrap_execve(const char *p, char *const a[], char *const e[]) { (void)p; (void)a; (void)e; return deny(); }
void __wrap_sshlog(const char *file, const char *function, int line, int showfunc,
    LogLevel level, const char *suffix, const char *format, ...) {
    (void)file; (void)function; (void)line; (void)showfunc; (void)level; (void)suffix;
    if (!observe) return;
    char text[512];
    va_list args; va_start(args, format);
    int n = vsnprintf(text, sizeof(text), format, args);
    va_end(args);
    CHECK(n >= 0 && n < (int)sizeof(text));
    int safe = strcmp(format, "invalid marker at %s:%lu") == 0
        || strcmp(format, "truncated line at %s:%lu") == 0
        || strcmp(format, "%s:%ld: truncated before key type") == 0
        || strcmp(format, "%s:%ld: truncated after key type") == 0;
    if (safe) CHECK(strstr(text, "ctos-material") != NULL);
    else safe = strcmp(format, "crypto_sign_ed25519_verify_detached failed: %d") == 0;
    CHECK(safe);
    safe_logs++;
}
int __real_clock_gettime(clockid_t clock, struct timespec *now);
int __wrap_clock_gettime(clockid_t clock, struct timespec *now) {
    int result = __real_clock_gettime(clock, now);
    if (observe && clock == CLOCK_BOOTTIME) {
        clock_calls++;
        if (expire_after_first && clock_calls > 1U && result == 0) now->tv_sec += 61;
    }
    return result;
}
FILE *__real_fmemopen(void *p, size_t n, const char *m);
FILE *__wrap_fmemopen(void *p, size_t n, const char *m) {
    FILE *f = __real_fmemopen(p, n, m);
    if (observe && f != NULL) streams++;
    return f;
}
int __real_fclose(FILE *f);
int __wrap_fclose(FILE *f) {
    int result = __real_fclose(f);
    if (observe) { closed_streams++; if (fail_stream_close) { errno = EIO; return EOF; } }
    return result;
}
int __real_ferror(FILE *f);
int __wrap_ferror(FILE *f) { return observe && fail_stream_read ? 1 : __real_ferror(f); }
static uint64_t now_ms(void) {
    struct timespec now;
    CHECK(__real_clock_gettime(CLOCK_BOOTTIME, &now) == 0);
    return (uint64_t)now.tv_sec * 1000U + (uint64_t)now.tv_nsec / 1000000U;
}
static uint32_t u32(const unsigned char *p) { return (uint32_t)p[0] << 24 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 8 | p[3]; }
static int zero(const unsigned char *p, size_t n) {
    for (size_t i = 0; i < n; i++) if (p[i] != 0) return 0;
    return 1;
}
static int inspect(unsigned int type, const void *body, size_t length, const char *passphrase,
    unsigned char wire[60]) {
    unsigned char *before = malloc(length == 0 ? 1 : length);
    CHECK(before != NULL);
    if (length != 0) memcpy(before, body, length);
    size_t pass_length = passphrase == NULL ? 0 : strlen(passphrase);
    unsigned int effects_before = effects, streams_before = streams, closed_before = closed_streams;
    clock_calls = 0;
    uint64_t deadline = now_ms() + 60000U;
    observe = 1;
    int result = ctos_ssh_material_inspect(type, body, length,
        (const unsigned char *)passphrase, pass_length, deadline, wire);
    observe = 0;
    CHECK(effects == effects_before);
    CHECK(streams - streams_before == closed_streams - closed_before);
    CHECK(length == 0 || memcmp(before, body, length) == 0);
    free(before);
    return result;
}
static void expect(unsigned int type, const void *body, size_t length, const char *passphrase,
    unsigned int status, unsigned int kind, unsigned int flags, unsigned int records, unsigned int line) {
    static unsigned int case_number;
    unsigned char wire[60]; memset(wire, 0xa5, sizeof(wire));
    case_number++;
    CHECK(inspect(type, body, length, passphrase, wire) == 0);
    CHECK(u32(wire) == 0x43534d49U && u32(wire + 4) == 1U);
    if (u32(wire + 8) != status || u32(wire + 12) != kind || u32(wire + 16) != flags
        || u32(wire + 20) != records || u32(wire + 24) != line) {
        fprintf(stderr, "material case=%u type=%u expected=%u,%u,%u,%u,%u actual=%u,%u,%u,%u,%u\n",
            case_number, type, status, kind, flags, records, line,
            u32(wire + 8), u32(wire + 12), u32(wire + 16), u32(wire + 20), u32(wire + 24));
    }
    CHECK(u32(wire + 8) == status && u32(wire + 12) == kind && u32(wire + 16) == flags);
    CHECK(u32(wire + 20) == records && u32(wire + 24) == line);
    CHECK((status == CTOS_MATERIAL_PARSED && type != CTOS_SSH_KNOWN_HOSTS) != zero(wire + 28, 32));
}
static struct sshbuf *public_text(const struct sshkey *key) {
    struct sshbuf *text = sshbuf_new();
    CHECK(text != NULL && sshkey_format_text(key, text) == 0);
    return text;
}
static struct sshbuf *private_file(struct sshkey *key, int format, const char *secret) {
    struct sshbuf *file = sshbuf_new();
    CHECK(file != NULL && sshkey_private_to_fileblob(key, file, secret, "material-test", format, NULL, 1) == 0);
    return file;
}
static void expect_buf(unsigned int type, const struct sshbuf *body, const char *passphrase,
    unsigned int status, unsigned int kind, unsigned int flags, unsigned int records, unsigned int line) {
    expect(type, sshbuf_ptr(body), sshbuf_len(body), passphrase, status, kind, flags, records, line);
}
static void key_digest(const struct sshkey *key, const unsigned char *wire) {
    unsigned char *blob = NULL, digest[32]; size_t length = 0;
    CHECK(sshkey_to_blob(key, &blob, &length) == 0);
    CHECK(ssh_digest_memory(SSH_DIGEST_SHA256, blob, length, digest, sizeof(digest)) == 0);
    CHECK(memcmp(digest, wire + 28, 32) == 0); free(blob);
}
static void software_keys(void) {
    const int types[] = {KEY_ED25519, KEY_ECDSA, KEY_ECDSA, KEY_ECDSA, KEY_RSA};
    const unsigned int bits[] = {0, 256, 384, 521, 1024};
    for (unsigned int algorithm = 0; algorithm < 5; algorithm++) {
        struct sshkey *key = NULL;
        CHECK(sshkey_generate(types[algorithm], bits[algorithm], &key) == 0);
        CHECK(ctos_ssh_key_software_identity(key) == 0);
        struct sshbuf *pub = public_text(key);
        struct sshbuf *trust = sshbuf_new(); CHECK(trust != NULL);
        CHECK(sshbuf_put(trust, "all-types.invalid ", 18) == 0 && sshbuf_putb(trust, pub) == 0);
        expect_buf(5, trust, NULL, 0, 0, 4, 1, 0); sshbuf_free(trust);
        expect_buf(3, pub, NULL, 0, algorithm + 1, 4, 1, 0);
        unsigned char wire[60]; CHECK(inspect(3, sshbuf_ptr(pub), sshbuf_len(pub), NULL, wire) == 0);
        key_digest(key, wire);
        expect_buf(4, pub, NULL, 1, 0, 4, 0, 1);
        for (int format = SSHKEY_PRIVATE_OPENSSH; format <= SSHKEY_PRIVATE_PKCS8; format++) {
            if (types[algorithm] == KEY_ED25519 && format == SSHKEY_PRIVATE_PEM) continue;
            struct sshbuf *file = private_file(key, format, "");
            expect_buf(2, file, NULL, 0, algorithm + 1, 4, 1, 0);
            CHECK(inspect(2, sshbuf_ptr(file), sshbuf_len(file), "ignored-for-plain", wire) == 0);
            key_digest(key, wire);
            /* Exact borrowed extent, no addressable sentinel after its final LF. */
            unsigned char *exact = malloc(sshbuf_len(file)); CHECK(exact != NULL);
            memcpy(exact, sshbuf_ptr(file), sshbuf_len(file));
            expect(2, exact, sshbuf_len(file), NULL, 0, algorithm + 1, 4, 1, 0); free(exact);
            if (format != SSHKEY_PRIVATE_OPENSSH)
                expect(2, sshbuf_ptr(file), sshbuf_len(file) - 1U, NULL, 0, algorithm + 1, 4, 1, 0);
            for (size_t cut = 0; cut + 64U < sshbuf_len(file); cut += 17U) {
                unsigned char rejected[60];
                CHECK(inspect(2, sshbuf_ptr(file), cut, NULL, rejected) == 0 && u32(rejected + 8) != 0);
            }
            struct sshbuf *twice = sshbuf_new(); CHECK(twice != NULL);
            CHECK(sshbuf_putb(twice, file) == 0 && sshbuf_putb(twice, file) == 0);
            expect_buf(2, twice, NULL, 1, 0, 4, 0, 0);
            sshbuf_reset(twice); CHECK(sshbuf_putb(twice, file) == 0 && sshbuf_put(twice, "junk", 4) == 0);
            expect_buf(2, twice, NULL, 1, 0, 4, 0, 0);
            sshbuf_free(twice); sshbuf_free(file);
            file = private_file(key, format, "material-passphrase");
            if (format == SSHKEY_PRIVATE_PKCS8) {
                expect_buf(2, file, NULL, 2, 0, 5, 0, 0);
                expect_buf(2, file, "material-passphrase", 2, 0, 5, 0, 0);
            } else {
                expect_buf(2, file, NULL, 3, 0, 5, 0, 0);
                expect_buf(2, file, "wrong", 4, 0, 5, 0, 0);
                expect_buf(2, file, "material-passphrase", 0, algorithm + 1, 5, 1, 0);
            }
            sshbuf_free(file);
        }
        struct sshbuf *comments = sshbuf_new(); CHECK(comments != NULL);
        CHECK(sshbuf_put(comments, "# leading\n\n \t", 13) == 0 && sshbuf_putb(comments, pub) == 0
            && sshbuf_put(comments, " comment\r\n# trailing\n", 21) == 0);
        expect_buf(3, comments, NULL, 0, algorithm + 1, 4, 1, 0);
        CHECK(sshbuf_putb(comments, pub) == 0);
        expect_buf(3, comments, NULL, 1, 0, 4, 0, 5);
        sshbuf_free(comments); sshbuf_free(pub); sshkey_free(key);
    }
}
static struct sshkey *certificate(struct sshkey *key, struct sshkey *ca) {
    struct sshkey *cert = NULL;
    CHECK(sshkey_from_private(key, &cert) == 0 && sshkey_to_certified(cert) == 0);
    cert->cert->type = SSH2_CERT_TYPE_HOST;
    cert->cert->key_id = strdup("inspection-expired-certificate"); CHECK(cert->cert->key_id != NULL);
    cert->cert->valid_after = 0; cert->cert->valid_before = 1;
    CHECK(sshkey_from_private(ca, &cert->cert->signature_key) == 0);
    CHECK(sshkey_certify(cert, ca, NULL, NULL, NULL) == 0);
    return cert;
}
static void public_certificates(void) {
    struct sshkey *ca = NULL; CHECK(sshkey_generate(KEY_ED25519, 0, &ca) == 0);
    const int types[] = {KEY_ED25519, KEY_ECDSA, KEY_ECDSA, KEY_ECDSA, KEY_RSA};
    const unsigned int bits[] = {0, 256, 384, 521, 1024};
    for (unsigned int i = 0; i < 5; i++) {
        struct sshkey *key = NULL; CHECK(sshkey_generate(types[i], bits[i], &key) == 0);
        struct sshkey *cert = certificate(key, ca);
        struct sshbuf *text = public_text(cert);
        expect_buf(4, text, NULL, 0, i + 1, 6, 1, 0);
        expect_buf(3, text, NULL, 1, 0, 4, 0, 1);
        unsigned char wire[60]; CHECK(inspect(4, sshbuf_ptr(text), sshbuf_len(text), NULL, wire) == 0); key_digest(cert, wire);
        sshbuf_free(text); sshkey_free(cert); sshkey_free(key);
    }
    sshkey_free(ca);
    const char *bad[] = {"", "# only comment\n", "authorized-key-options ssh-ed25519 AAAA", "ssh-ed25519 AAAA", "junk", "ssh-ed25519", "\n\nRSA AAAA"};
    for (size_t i = 0; i < sizeof(bad) / sizeof(bad[0]); i++) {
        unsigned char wire[60]; CHECK(inspect(3, bad[i], strlen(bad[i]), NULL, wire) == 0);
        CHECK(u32(wire + 8) == 1 && u32(wire + 12) == 0 && u32(wire + 20) == 0 && zero(wire + 28, 32));
    }
}
static struct sshbuf *trust_record(const char *hosts, const struct sshbuf *key) {
    struct sshbuf *record = sshbuf_new(); CHECK(record != NULL);
    CHECK(sshbuf_put(record, hosts, strlen(hosts)) == 0 && sshbuf_put(record, " ", 1) == 0
        && sshbuf_putb(record, key) == 0 && sshbuf_put(record, " material-comment\n", 18) == 0);
    return record;
}
static void known_hosts(void) {
    struct sshkey *key = NULL; CHECK(sshkey_generate(KEY_ED25519, 0, &key) == 0);
    struct sshbuf *pub = public_text(key);
    const char *hosts[] = {"example.invalid", "[example.invalid]:2222", "*.invalid,!bad.invalid,other.invalid",
        "@revoked example.invalid", "@cert-authority *.invalid", "|1|AAAAAAAAAAAAAAAAAAAAAAAAAAA=|AQEBAQEBAQEBAQEBAQEBAQEBAQE="};
    struct sshbuf *all = sshbuf_new(); CHECK(all != NULL);
    for (size_t i = 0; i < sizeof(hosts) / sizeof(hosts[0]); i++) {
        struct sshbuf *one = trust_record(hosts[i], pub);
        expect_buf(5, one, NULL, 0, 0, 4, 1, 0);
        CHECK(sshbuf_putb(all, one) == 0); sshbuf_free(one);
    }
    expect_buf(5, all, NULL, 0, 0, 4, 6, 0);
    expect(5, "", 0, NULL, 0, 0, 4, 0, 0);
    expect(5, "# comments\n \t\n", 14, NULL, 0, 0, 4, 0, 0);
    const char *bad_hash[] = {"|2|AAAAAAAAAAAAAAAAAAAAAAAAAAA=|AQEBAQEBAQEBAQEBAQEBAQEBAQE=",
        "|1||AQEBAQEBAQEBAQEBAQEBAQEBAQE=", "|1|AA==|AQEBAQEBAQEBAQEBAQEBAQEBAQE=",
        "|1|AAAAAAAAAAAAAAAAAAAAAAAAAAA=|AA==", "|1|AAAAAAAAAAAAAAAAAAAAAAAAAAA=|AQEBAQEBAQEBAQEBAQEBAQEBAQE=tail",
        "|1|AAAAAAAAAAAAAAAAAAAAAAAAAAA=|AQEBAQEBAQEBAQEBAQEBAQEBAQE=|", "|1|AAAAAAAAAAAAAAAAAAAAAAAAAA!=|AQEBAQEBAQEBAQEBAQEBAQEBAQE=",
        "|1|AAAAAAAAAAAAAAAAAAAAAAAAAAA=|AQEBAQEBAQEBAQEBAQEBAQEBAQF=", "other,|1|bad|bad"};
    for (size_t i = 0; i < sizeof(bad_hash) / sizeof(bad_hash[0]); i++) {
        struct sshbuf *one = trust_record(bad_hash[i], pub);
        expect_buf(5, one, NULL, 1, 0, 4, 0, 1); sshbuf_free(one);
    }
    const char *bad[] = {"@wrong never.invalid ssh-ed25519 AAAA\n", "only-host\n", "host # no key\n", "host ssh-ed25519 AAAA\n",
        "@revoked @cert-authority host ssh-ed25519 AAAA\n"};
    for (size_t i = 0; i < sizeof(bad) / sizeof(bad[0]); i++) {
        struct sshbuf *mixed = sshbuf_new(); CHECK(mixed != NULL);
        CHECK(sshbuf_putb(mixed, all) == 0 && sshbuf_put(mixed, bad[i], strlen(bad[i])) == 0);
        expect_buf(5, mixed, NULL, 1, 0, 4, 0, 7); sshbuf_free(mixed);
    }
    unsigned char wire[60]; fail_stream_read = 1;
    CHECK(inspect(5, sshbuf_ptr(all), sshbuf_len(all), NULL, wire) == -1 && errno == EIO && zero(wire, 60));
    fail_stream_read = 0; fail_stream_close = 1;
    CHECK(inspect(5, sshbuf_ptr(all), sshbuf_len(all), NULL, wire) == -1 && errno == EIO && zero(wire, 60));
    fail_stream_close = 0;
    sshbuf_free(all); sshbuf_free(pub); sshkey_free(key);
}
static struct sshbuf *policy_file(unsigned int rounds, size_t salt_length, unsigned int keys, const char *cipher, const char *kdf) {
    struct sshbuf *raw = sshbuf_new(), *options = sshbuf_new(), *file = sshbuf_new();
    unsigned char salt[65] = {0}; CHECK(raw != NULL && options != NULL && file != NULL && salt_length <= sizeof(salt));
    CHECK(sshbuf_put_string(options, salt, salt_length) == 0 && sshbuf_put_u32(options, rounds) == 0);
    CHECK(sshbuf_put(raw, "openssh-key-v1", sizeof("openssh-key-v1")) == 0
        && sshbuf_put_cstring(raw, cipher) == 0 && sshbuf_put_cstring(raw, kdf) == 0
        && sshbuf_put_stringb(raw, options) == 0 && sshbuf_put_u32(raw, keys) == 0);
    CHECK(sshbuf_put(file, "-----BEGIN OPENSSH PRIVATE KEY-----\n", 36) == 0 && sshbuf_dtob64(raw, file, 1) == 0
        && sshbuf_put(file, "-----END OPENSSH PRIVATE KEY-----\n", 34) == 0);
    sshbuf_free(raw); sshbuf_free(options); return file;
}
static void policy_bounds(void) {
    const unsigned int rounds[] = {0, 1, 64, 65};
    const size_t salts[] = {0, 1, 64, 65};
    for (size_t i = 0; i < 4; i++) for (size_t j = 0; j < 4; j++) {
        struct sshbuf *file = policy_file(rounds[i], salts[j], 1, "aes256-ctr", "bcrypt");
        int allowed = rounds[i] >= 1 && rounds[i] <= 64 && salts[j] >= 1 && salts[j] <= 64;
        CHECK((ctos_ssh_key_encrypted_policy(sshbuf_ptr(file), sshbuf_len(file)) == 0) == allowed);
        unsigned int parses_before = private_parses, kdf_before = kdf_calls;
        expect_buf(2, file, NULL, allowed ? 3 : 2, 0, 5, 0, 0);
        CHECK(private_parses == parses_before && kdf_calls == kdf_before);
        if (!allowed) {
            expect_buf(2, file, "must-not-derive", 2, 0, 5, 0, 0);
            CHECK(private_parses == parses_before && kdf_calls == kdf_before);
        }
        sshbuf_free(file);
    }
    for (unsigned int i = 0; i < 3; i++) {
        struct sshbuf *file = policy_file(1, 1, i == 0 ? 2 : 1, i == 1 ? "aes128-ctr" : "aes256-ctr", i == 2 ? "unknown" : "bcrypt");
        expect_buf(2, file, "not-derived", 2, 0, 5, 0, 0); sshbuf_free(file);
    }
}
static void legacy_aes_and_crlf(void) {
    const int types[] = {KEY_RSA, KEY_ECDSA};
    const unsigned int bits[] = {1024, 256}, kinds[] = {5, 2};
    for (size_t i = 0; i < 2; i++) {
        struct sshkey *key = NULL; CHECK(sshkey_generate(types[i], bits[i], &key) == 0);
        const EVP_CIPHER *ciphers[] = {EVP_aes_128_cbc(), EVP_aes_256_cbc()};
        for (size_t j = 0; j < 2; j++) {
            BIO *bio = BIO_new(BIO_s_mem()); CHECK(bio != NULL);
            CHECK(PEM_write_bio_PrivateKey_traditional(bio, key->pkey, ciphers[j],
                (const unsigned char *)"aes-passphrase", 14, NULL, NULL) == 1);
            char *bytes = NULL; long length = BIO_get_mem_data(bio, &bytes); CHECK(length > 0);
            expect(2, bytes, (size_t)length, "aes-passphrase", 0, kinds[i], 5, 1, 0);
            expect(2, bytes, (size_t)length, NULL, 3, 0, 5, 0, 0);
            expect(2, bytes, (size_t)length, "wrong", 4, 0, 5, 0, 0);
            BIO_free(bio);
        }
        for (int format = SSHKEY_PRIVATE_OPENSSH; format <= SSHKEY_PRIVATE_PEM; format++) {
            struct sshbuf *file = private_file(key, format, "");
            struct sshbuf *crlf = sshbuf_new(); CHECK(crlf != NULL);
            for (size_t k = 0; k < sshbuf_len(file); k++) {
                unsigned char c = sshbuf_ptr(file)[k];
                if (c == '\n') CHECK(sshbuf_put(crlf, "\r", 1) == 0);
                CHECK(sshbuf_put(crlf, &c, 1) == 0);
            }
            if (format == SSHKEY_PRIVATE_OPENSSH) expect_buf(2, crlf, NULL, 1, 0, 4, 0, 0);
            else expect_buf(2, crlf, NULL, 0, kinds[i], 4, 1, 0);
            sshbuf_free(crlf); sshbuf_free(file);
        }
        sshkey_free(key);
    }
    struct sshkey *key = NULL; CHECK(sshkey_generate(KEY_ED25519, 0, &key) == 0);
    char *secret = malloc(8193); CHECK(secret != NULL); memset(secret, 'p', 8192); secret[8192] = 0;
    struct sshbuf *file = private_file(key, SSHKEY_PRIVATE_OPENSSH, secret);
    expect_buf(2, file, secret, 0, 1, 5, 1, 0);
    memset(secret, 0, 8193); free(secret); sshbuf_free(file); sshkey_free(key);
}
static void blob_framing(void) {
    struct sshkey *key = NULL;
    CHECK(sshkey_generate(KEY_ED25519, 0, &key) == 0);
    unsigned char *blob = NULL; size_t length = 0;
    CHECK(sshkey_to_blob(key, &blob, &length) == 0);
    struct sshbuf *raw = sshbuf_new(), *text = sshbuf_new(); CHECK(raw != NULL && text != NULL);
    CHECK(sshbuf_put(raw, blob, length) == 0 && sshbuf_put(raw, "junk", 4) == 0);
    CHECK(sshbuf_put(text, "ssh-ed25519 ", 12) == 0 && sshbuf_dtob64(raw, text, 0) == 0);
    expect_buf(3, text, NULL, 1, 0, 4, 0, 1);
    sshbuf_reset(text); sshbuf_reset(raw);
    CHECK(sshbuf_put_cstring(raw, "sk-ssh-ed25519@openssh.com") == 0
        && sshbuf_put_string(raw, key->ed25519_pk, 32) == 0
        && sshbuf_put_cstring(raw, "ssh:material-test") == 0);
    CHECK(sshbuf_put(text, "sk-ssh-ed25519@openssh.com ", 27) == 0 && sshbuf_dtob64(raw, text, 0) == 0);
    /* The pinned build disables SK, so its parser rejects the unknown type. */
    CHECK(sshkey_type_from_name("sk-ssh-ed25519@openssh.com") == KEY_UNSPEC);
    expect_buf(3, text, NULL, 1, 0, 4, 0, 1);
    free(blob); sshbuf_free(raw); sshbuf_free(text); sshkey_free(key);
}
static void der_full_consumption(void) {
    struct sshkey *key = NULL; CHECK(sshkey_generate(KEY_RSA, 1024, &key) == 0);
    struct sshbuf *file = private_file(key, SSHKEY_PRIVATE_PEM, "");
    BIO *input = BIO_new_mem_buf(sshbuf_ptr(file), (int)sshbuf_len(file)); CHECK(input != NULL);
    char *name = NULL, *header = NULL; unsigned char *der = NULL; long length = 0;
    CHECK(PEM_read_bio(input, &name, &header, &der, &length) == 1);
    struct sshbuf *extra = sshbuf_new(); CHECK(extra != NULL && sshbuf_put(extra, der, (size_t)length) == 0);
    CHECK(sshbuf_put(extra, "junk", 4) == 0);
    BIO *output = BIO_new(BIO_s_mem()); CHECK(output != NULL);
    CHECK(PEM_write_bio(output, name, "", sshbuf_ptr(extra), (long)sshbuf_len(extra)) > 0);
    char *bytes = NULL; long size = BIO_get_mem_data(output, &bytes); CHECK(size > 0);
    expect(2, bytes, (size_t)size, NULL, 1, 0, 4, 0, 0);
    BIO_free(input); BIO_free(output); OPENSSL_free(name); OPENSSL_free(header); OPENSSL_clear_free(der, (size_t)length);
    sshbuf_free(extra); sshbuf_free(file); sshkey_free(key);
}
static void boundaries(void) {
    unsigned char wire[60];
    const unsigned char nul[] = {'#', 'x', '\n', 'a', 0, 'b'};
    expect(5, nul, sizeof(nul), NULL, 1, 0, 4, 0, 2);
    expect(2, nul, sizeof(nul), NULL, 1, 0, 4, 0, 0);
    unsigned char *big = malloc(CTOS_SSH_MATERIAL_BYTES + 1U); CHECK(big != NULL);
    memset(big, ' ', CTOS_SSH_MATERIAL_BYTES + 1U);
    CHECK(ctos_ssh_material_inspect(5, big, CTOS_SSH_MATERIAL_BYTES + 1U, NULL, 0, now_ms() + 60000U, wire) == -1 && errno == E2BIG && zero(wire, 60));
    for (size_t i = 0; i < CTOS_SSH_MATERIAL_BYTES; i += 65536U) {
        big[i] = '#'; big[i + 65535U] = '\n';
    }
    expect(5, big, CTOS_SSH_MATERIAL_BYTES, NULL, 0, 0, 4, 0, 0);
    memset(big, ' ', CTOS_SSH_MATERIAL_BYTES + 1U);
    big[0] = '#'; big[65536] = '\n';
    expect(5, big, 65537, NULL, 0, 0, 4, 0, 0);
    CHECK(inspect(5, big, 65538, NULL, wire) == 0);
    big[65536] = 'x';
    CHECK(inspect(5, big, 65537, NULL, wire) == -1 && errno == E2BIG && zero(wire, 60));
    memset(big, '\n', 16385);
    expect(5, big, 16384, NULL, 0, 0, 4, 0, 0);
    CHECK(inspect(5, big, 16385, NULL, wire) == -1 && errno == E2BIG && zero(wire, 60));
    memset(big, 'p', 8193);
    CHECK(ctos_ssh_material_inspect(2, NULL, 0, big, 8192, now_ms() + 60000U, wire) == 0 && u32(wire + 8) == 1);
    CHECK(ctos_ssh_material_inspect(2, NULL, 0, big, 8193, now_ms() + 60000U, wire) == -1 && errno == E2BIG && zero(wire, 60));
    big[4] = 0;
    CHECK(ctos_ssh_material_inspect(2, NULL, 0, big, 5, now_ms() + 60000U, wire) == -1 && errno == EINVAL && zero(wire, 60));
    CHECK(ctos_ssh_material_inspect(3, NULL, 0, big, 1, now_ms() + 60000U, wire) == -1 && errno == EINVAL);
    for (unsigned int i = 0; i < 7; i++) if (i < 2 || i > 5)
        CHECK(ctos_ssh_material_inspect(i, NULL, 0, NULL, 0, now_ms() + 60000U, wire) == -1 && errno == EINVAL && zero(wire, 60));
    CHECK(ctos_ssh_material_inspect(5, NULL, 1, NULL, 0, now_ms() + 60000U, wire) == -1 && errno == EINVAL);
    CHECK(ctos_ssh_material_inspect(5, NULL, 0, NULL, 0, now_ms(), wire) == -1 && errno == ETIMEDOUT && zero(wire, 60));
    CHECK(ctos_ssh_material_inspect(5, NULL, 0, NULL, 0, now_ms() + 61000, wire) == -1 && errno == EINVAL && zero(wire, 60));
    expire_after_first = 1;
    CHECK(inspect(5, "# late\n", 7, NULL, wire) == -1 && errno == ETIMEDOUT && zero(wire, 60));
    expire_after_first = 0;
    free(big);
}
static unsigned int fd_count(void) {
    unsigned int count = 0;
    for (int fd = 0; fd < 256; fd++) if (fcntl(fd, F_GETFD) >= 0) count++;
    return count;
}
int main(void) {
    CHECK(OPENSSL_init_crypto(OPENSSL_INIT_NO_LOAD_CONFIG, NULL) == 1);
    unsigned int before = fd_count();
    software_keys(); public_certificates(); known_hosts(); policy_bounds(); legacy_aes_and_crlf(); blob_framing(); der_full_consumption(); boundaries();
    CHECK(fd_count() == before && effects == 0 && streams == closed_streams && safe_logs > 0);
    printf("SSH_MATERIAL_INSPECT checks=%u effects=%u safe_logs=%u streams=%u cleanup=pass\n", checks, effects, safe_logs, streams);
    return 0;
}

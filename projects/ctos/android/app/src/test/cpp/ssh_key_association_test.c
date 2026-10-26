/* Reuse the real material fixtures and effect observers, including its full baseline. */
#define main material_baseline_main
#define __wrap_sshkey_parse_private_fileblob baseline_private_parser
#define __wrap_sshlog baseline_log_observer
#define __wrap_clock_gettime baseline_clock_observer
#include "ssh_material_inspect_test.c"
#undef main
#undef __wrap_sshkey_parse_private_fileblob
#undef __wrap_sshlog
#undef __wrap_clock_gettime
#include "ssherr.h"
#include <openssl/rsa.h>

static int sign_error, verify_error, parse_error, oversized_component;
static unsigned int pair_signs, pair_verifies;
static unsigned int expire_at;
static int private_proof_observe;
static int public_export_observe;
int __wrap_clock_gettime(clockid_t clock, struct timespec *now) {
    int result = baseline_clock_observer(clock, now);
    if (observe && clock == CLOCK_BOOTTIME && expire_at != 0 && clock_calls >= expire_at && result == 0) now->tv_sec += 61;
    return result;
}
void __wrap_sshlog(const char *file, const char *function, int line, int showfunc,
    LogLevel level, const char *suffix, const char *format, ...) {
    (void)file; (void)function; (void)line; (void)showfunc; (void)level; (void)suffix;
    if (!observe) return;
    int safe = strcmp(format, "invalid marker at %s:%lu") == 0
        || strcmp(format, "truncated line at %s:%lu") == 0
        || strcmp(format, "%s:%ld: truncated before key type") == 0
        || strcmp(format, "%s:%ld: truncated after key type") == 0
        || strcmp(format, "crypto_sign_ed25519_verify_detached failed: %d") == 0
        || strcmp(format, "EVP_DigestVerify failed") == 0;
    if (!safe) fprintf(stderr, "unexpected pair log format=%s\n", format);
    CHECK(safe); safe_logs++;
}
int __wrap_sshkey_parse_private_fileblob(struct sshbuf *file, const char *phrase,
    struct sshkey **key, char **comment) {
    if (observe && parse_error != 0) return parse_error;
    int result = baseline_private_parser(file, phrase, key, comment);
    if (observe && result == 0 && oversized_component != 0) {
        RSA *rsa = (RSA *)EVP_PKEY_get0_RSA((*key)->pkey);
        CHECK(rsa != NULL);
        BIGNUM *big = BN_new(); CHECK(big != NULL && BN_set_bit(big, 16384) == 1);
        switch (oversized_component) {
        case 1: CHECK(RSA_set0_key(rsa, big, NULL, NULL) == 1); break;
        case 2: CHECK(RSA_set0_key(rsa, NULL, big, NULL) == 1); break;
        case 3: CHECK(RSA_set0_key(rsa, NULL, NULL, big) == 1); break;
        case 4: CHECK(RSA_set0_factors(rsa, big, NULL) == 1); break;
        case 5: CHECK(RSA_set0_factors(rsa, NULL, big) == 1); break;
        case 6: CHECK(RSA_set0_crt_params(rsa, big, NULL, NULL) == 1); break;
        case 7: CHECK(RSA_set0_crt_params(rsa, NULL, big, NULL) == 1); break;
        default: CHECK(RSA_set0_crt_params(rsa, NULL, NULL, big) == 1); break;
        }
    }
    return result;
}
int __real_sshkey_sign(struct sshkey *, unsigned char **, size_t *, const unsigned char *, size_t,
    const char *, const char *, const char *, unsigned int);
int __wrap_sshkey_sign(struct sshkey *key, unsigned char **signature, size_t *length,
    const unsigned char *data, size_t size, const char *algorithm, const char *provider,
    const char *pin, unsigned int compat) {
    if (observe) {
        pair_signs++;
        const char *domain = public_export_observe ? "ctOS SSH public export proof v1" :
            private_proof_observe ? "ctOS SSH private material proof v1" : "ctOS SSH key association proof v1";
        size_t domain_length = strlen(domain) + 1U;
        CHECK(size == domain_length + 32U);
        CHECK(memcmp(data, domain, domain_length) == 0);
        CHECK(provider == NULL && pin == NULL);
        if (sshkey_type_plain(key->type) == KEY_RSA) CHECK(strcmp(algorithm, "rsa-sha2-256") == 0);
        if (sign_error != 0) return sign_error;
    }
    return __real_sshkey_sign(key, signature, length, data, size, algorithm, provider, pin, compat);
}
int __real_sshkey_verify(const struct sshkey *, const unsigned char *, size_t, const unsigned char *,
    size_t, const char *, unsigned int, struct sshkey_sig_details **);
int __wrap_sshkey_verify(const struct sshkey *key, const unsigned char *signature, size_t length,
    const unsigned char *data, size_t size, const char *algorithm, unsigned int compat,
    struct sshkey_sig_details **details) {
    if (observe) {
        pair_verifies++;
        if (verify_error != 0) return verify_error;
    }
    return __real_sshkey_verify(key, signature, length, data, size, algorithm, compat, details);
}
static int verify_pair(unsigned int type, const struct sshbuf *private_body, const char *phrase,
    const struct sshbuf *peer, unsigned char wire[60]) {
    unsigned char *before_private = malloc(sshbuf_len(private_body) + 1U), *before_peer = malloc(sshbuf_len(peer) + 1U);
    CHECK(before_private != NULL && before_peer != NULL);
    memcpy(before_private, sshbuf_ptr(private_body), sshbuf_len(private_body));
    memcpy(before_peer, sshbuf_ptr(peer), sshbuf_len(peer));
    unsigned int before_effects = effects;
    clock_calls = 0;
    uint64_t deadline = now_ms() + 60000U;
    observe = 1;
    int result = ctos_ssh_material_verify_pair(type, sshbuf_ptr(private_body), sshbuf_len(private_body),
        (const unsigned char *)phrase, phrase == NULL ? 0 : strlen(phrase), sshbuf_ptr(peer), sshbuf_len(peer), deadline, wire);
    observe = 0;
    CHECK(effects == before_effects);
    CHECK(memcmp(before_private, sshbuf_ptr(private_body), sshbuf_len(private_body)) == 0);
    CHECK(memcmp(before_peer, sshbuf_ptr(peer), sshbuf_len(peer)) == 0);
    memset(before_private, 0, sshbuf_len(private_body)); free(before_private); free(before_peer);
    return result;
}
static void pair_expect(unsigned int type, const struct sshbuf *private_body, const char *phrase,
    const struct sshbuf *peer, unsigned int status, unsigned int stage, unsigned int kind, int encrypted,
    const struct sshkey *subject) {
    unsigned char wire[60]; memset(wire, 0xa5, sizeof(wire));
    CHECK(verify_pair(type, private_body, phrase, peer, wire) == 0);
    if (u32(wire + 8) != status || u32(wire + 20) != stage) fprintf(stderr,
        "pair expected=%u/%u actual=%u/%u\n", status, stage, u32(wire + 8), u32(wire + 20));
    CHECK(u32(wire) == 0x43534b41U && u32(wire + 4) == 1U);
    CHECK(u32(wire + 8) == status && u32(wire + 12) == kind);
    CHECK(u32(wire + 16) == (4U | (encrypted ? 1U : 0U) | (status == 0 ? 24U : 0U)));
    CHECK(u32(wire + 20) == stage && u32(wire + 24) == type);
    if (status == 0) { CHECK(subject != NULL); key_digest(subject, wire); }
    else CHECK(zero(wire + 28, 32));
}
static void pair_algorithms(void) {
    const int types[] = {KEY_ED25519, KEY_ECDSA, KEY_ECDSA, KEY_ECDSA, KEY_RSA};
    const unsigned int bits[] = {0, 256, 384, 521, 1024};
    struct sshkey *ca = NULL, *other = NULL;
    CHECK(sshkey_generate(KEY_ED25519, 0, &ca) == 0 && sshkey_generate(KEY_ED25519, 0, &other) == 0);
    struct sshbuf *different = public_text(other);
    for (unsigned int i = 0; i < 5; i++) {
        struct sshkey *key = NULL; CHECK(sshkey_generate(types[i], bits[i], &key) == 0);
        struct sshbuf *pub = public_text(key), *clear = private_file(key, SSHKEY_PRIVATE_OPENSSH, "");
        struct sshbuf *encrypted = private_file(key, SSHKEY_PRIVATE_OPENSSH, "pair-phrase");
        unsigned int signs_before = pair_signs, verifies_before = pair_verifies;
        pair_expect(3, clear, NULL, pub, 0, 0, i + 1U, 0, key);
        CHECK(pair_signs == signs_before + 1U && pair_verifies == verifies_before + 1U);
        pair_expect(3, clear, NULL, different, 1, 3, 0, 0, NULL);
        CHECK(pair_signs == signs_before + 1U);
        pair_expect(3, clear, "extra-phrase", pub, 3, 1, 0, 0, NULL);
        pair_expect(3, encrypted, NULL, pub, 4, 1, 0, 1, NULL);
        pair_expect(3, encrypted, "wrong-phrase", pub, 5, 1, 0, 1, NULL);
        pair_expect(3, encrypted, "pair-phrase", pub, 0, 0, i + 1U, 1, key);
        struct sshkey *cert = certificate(key, ca);
        struct sshbuf *cert_text = public_text(cert);
        pair_expect(4, clear, NULL, cert_text, 0, 0, i + 1U, 0, key);
        pair_expect(3, clear, NULL, cert_text, 2, 2, 0, 0, NULL);
        pair_expect(4, clear, NULL, pub, 2, 2, 0, 0, NULL);
        CHECK(sshbuf_put(pub, "\n", 1) == 0 && sshbuf_putb(pub, different) == 0);
        pair_expect(3, clear, NULL, pub, 2, 2, 0, 0, NULL);
        if (i > 0) {
            struct sshbuf *pem = private_file(key, SSHKEY_PRIVATE_PEM, "");
            struct sshbuf *original_pub = public_text(key);
            pair_expect(3, pem, NULL, original_pub, 0, 0, i + 1U, 0, key);
            sshbuf_free(pem);
            pem = private_file(key, SSHKEY_PRIVATE_PEM, "legacy-phrase");
            unsigned int before_parse = private_parses, before_kdf = kdf_calls;
            pair_expect(3, pem, "legacy-phrase", original_pub, 3, 1, 0, 1, NULL);
            pair_expect(3, pem, NULL, original_pub, 3, 1, 0, 1, NULL);
            CHECK(private_parses == before_parse && kdf_calls == before_kdf);
            sshbuf_free(pem); sshbuf_free(original_pub);
        }
        if (i == 4) for (int c = 1; c <= 8; c++) {
            oversized_component = c; signs_before = pair_signs;
            pair_expect(3, clear, NULL, different, 3, 1, 0, 0, NULL);
            CHECK(pair_signs == signs_before);
            oversized_component = 0;
        }
        sshbuf_free(cert_text); sshkey_free(cert); sshbuf_free(clear); sshbuf_free(encrypted);
        sshbuf_free(pub); sshkey_free(key);
    }
    sshbuf_free(different); sshkey_free(other); sshkey_free(ca);
}
static void pair_signer_and_failures(void) {
    struct sshkey *key = NULL; CHECK(sshkey_generate(KEY_ED25519, 0, &key) == 0);
    struct sshbuf *pub = public_text(key), *clear = private_file(key, SSHKEY_PRIVATE_OPENSSH, "");
    key->ed25519_sk[0] ^= 1U;
    struct sshbuf *inconsistent = private_file(key, SSHKEY_PRIVATE_OPENSSH, "");
    pair_expect(3, inconsistent, NULL, pub, 1, 4, 0, 0, NULL);
    unsigned char wire[60];
    const int errors[] = {SSH_ERR_ALLOC_FAIL, SSH_ERR_SYSTEM_ERROR, SSH_ERR_LIBCRYPTO_ERROR};
    for (unsigned int i = 0; i < 3; i++) {
        int expected_errno = i == 0 ? ENOMEM : EIO;
        sign_error = errors[i];
        CHECK(verify_pair(3, clear, NULL, pub, wire) == -1 && errno == expected_errno && zero(wire, 60));
        sign_error = 0; verify_error = errors[i];
        CHECK(verify_pair(3, clear, NULL, pub, wire) == -1 && errno == expected_errno && zero(wire, 60));
        verify_error = 0; parse_error = errors[i];
        CHECK(verify_pair(3, clear, NULL, pub, wire) == -1 && errno == expected_errno && zero(wire, 60));
        parse_error = 0;
    }
    sign_error = SSH_ERR_SIGNATURE_INVALID;
    pair_expect(3, clear, NULL, pub, 1, 4, 0, 0, NULL); sign_error = 0;
    verify_error = SSH_ERR_SIGNATURE_INVALID;
    pair_expect(3, clear, NULL, pub, 1, 4, 0, 0, NULL); verify_error = 0;
    expire_after_first = 1;
    CHECK(verify_pair(3, clear, NULL, pub, wire) == -1 && errno == ETIMEDOUT && zero(wire, 60));
    expire_after_first = 0;
    CHECK(verify_pair(3, clear, NULL, pub, wire) == 0);
    unsigned int boundaries_count = clock_calls;
    for (unsigned int i = 1; i <= boundaries_count; i++) {
        expire_at = i;
        CHECK(verify_pair(3, clear, NULL, pub, wire) == -1 && errno == ETIMEDOUT && zero(wire, 60));
    }
    expire_at = 0;
    for (size_t cut = 0; cut < sshbuf_len(clear); cut++) {
        struct sshbuf *truncated = sshbuf_from(sshbuf_ptr(clear), cut); CHECK(truncated != NULL);
        pair_expect(3, truncated, NULL, pub, 2, 1, 0, 0, NULL); sshbuf_free(truncated);
    }
    struct sshbuf *bad = sshbuf_from("bad\n", 4); CHECK(bad != NULL);
    pair_expect(3, clear, NULL, bad, 2, 2, 0, 0, NULL);
    const unsigned int rounds[] = {0, 65};
    for (unsigned int i = 0; i < 2; i++) {
        struct sshbuf *policy = policy_file(rounds[i], 1, 1, "aes256-ctr", "bcrypt");
        unsigned int before_parse = private_parses, before_kdf = kdf_calls;
        pair_expect(3, policy, "never-derived", pub, 3, 1, 0, 1, NULL);
        CHECK(private_parses == before_parse && kdf_calls == before_kdf); sshbuf_free(policy);
    }
    uint64_t deadline = now_ms() + 60000U;
    CHECK(ctos_ssh_material_verify_pair(2, NULL, 0, NULL, 0, NULL, 0, deadline, wire) == -1 && errno == EINVAL && zero(wire, 60));
    CHECK(ctos_ssh_material_verify_pair(3, NULL, CTOS_SSH_MATERIAL_BYTES + 1U, NULL, 0, NULL, 0, deadline, wire) == -1 && errno == E2BIG && zero(wire, 60));
    CHECK(ctos_ssh_material_verify_pair(3, NULL, 0, (const unsigned char *)"a\0b", 3, NULL, 0, deadline, wire) == -1 && errno == EINVAL && zero(wire, 60));
    CHECK(ctos_ssh_material_verify_pair(3, NULL, 0, NULL, 0, NULL, 0, now_ms(), wire) == -1 && errno == ETIMEDOUT && zero(wire, 60));
    sshbuf_free(bad); sshbuf_free(inconsistent); sshbuf_free(clear); sshbuf_free(pub); sshkey_free(key);
}
static void pair_multiprime(void) {
    RSA *rsa = RSA_new(); BIGNUM *exponent = BN_new();
    CHECK(rsa != NULL && exponent != NULL && BN_set_word(exponent, 65537) == 1);
    CHECK(RSA_generate_multi_prime_key(rsa, 2048, 3, exponent, NULL) == 1);
    struct sshkey *key = sshkey_new(KEY_RSA); CHECK(key != NULL);
    CHECK(EVP_PKEY_set1_RSA(key->pkey, rsa) == 1);
    struct sshbuf *file = private_file(key, SSHKEY_PRIVATE_PEM, ""), *pub = public_text(key);
    unsigned int before = pair_signs;
    pair_expect(3, file, NULL, pub, 3, 1, 0, 0, NULL);
    CHECK(pair_signs == before);
    sshbuf_free(file); sshbuf_free(pub); sshkey_free(key); RSA_free(rsa); BN_free(exponent);
}
static int pair_baseline_main(void) {
    CHECK(material_baseline_main() == 0);
    unsigned int baseline = checks, before = fd_count();
    pair_algorithms(); pair_signer_and_failures(); pair_multiprime();
    CHECK(fd_count() == before && effects == 0 && pair_signs > 0 && pair_verifies > 0);
    printf("SSH_KEY_ASSOCIATION checks=%u baseline=%u signs=%u verifies=%u effects=%u cleanup=pass\n",
        checks, baseline, pair_signs, pair_verifies, effects);
    return 0;
}
#ifndef CTOS_PAIR_TEST_INCLUDED
int main(void) { return pair_baseline_main(); }
#endif

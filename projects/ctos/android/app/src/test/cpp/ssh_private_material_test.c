/* Keep the old real-parser/signing matrix and all effect observers in this run. */
#define CTOS_PAIR_TEST_INCLUDED 1
#include "ssh_key_association_test.c"

static int verify_private(const struct sshbuf *file, const char *phrase, unsigned char wire[60]) {
    size_t length = sshbuf_len(file), phrase_length = phrase == NULL ? 0 : strlen(phrase);
    unsigned char *before = malloc(length + 1U), *phrase_before = malloc(phrase_length + 1U);
    CHECK(before != NULL && phrase_before != NULL);
    memcpy(before, sshbuf_ptr(file), length);
    if (phrase_length != 0) memcpy(phrase_before, phrase, phrase_length);
    unsigned int before_effects = effects;
    clock_calls = 0;
    uint64_t deadline = now_ms() + 60000U;
    private_proof_observe = 1; observe = 1;
    int result = ctos_ssh_material_verify_private(sshbuf_ptr(file), length,
        (const unsigned char *)phrase, phrase_length, deadline, wire);
    observe = 0; private_proof_observe = 0;
    CHECK(effects == before_effects);
    CHECK(memcmp(before, sshbuf_ptr(file), length) == 0);
    CHECK(phrase_length == 0 || memcmp(phrase_before, phrase, phrase_length) == 0);
    memset(before, 0, length + 1U); memset(phrase_before, 0, phrase_length + 1U);
    free(before); free(phrase_before);
    return result;
}
static void private_expect(const struct sshbuf *file, const char *phrase,
    unsigned int status, unsigned int stage, unsigned int kind, int encrypted,
    const struct sshkey *subject) {
    unsigned char wire[60]; memset(wire, 0xa5, sizeof(wire));
    CHECK(verify_private(file, phrase, wire) == 0);
    if (u32(wire + 8) != status || u32(wire + 20) != stage) fprintf(stderr,
        "private expected=%u/%u actual=%u/%u\n", status, stage, u32(wire + 8), u32(wire + 20));
    CHECK(u32(wire) == 0x43534b50U && u32(wire + 4) == 1U);
    CHECK(u32(wire + 8) == status && u32(wire + 12) == kind);
    CHECK(u32(wire + 16) == (4U | (encrypted ? 1U : 0U) | (status == 0 ? 24U : 0U)));
    CHECK(u32(wire + 20) == stage && u32(wire + 24) == CTOS_SSH_PRIVATE_KEY);
    if (status == 0) { CHECK(subject != NULL); key_digest(subject, wire); }
    else CHECK(zero(wire + 28, 32));
}
static void private_algorithms(void) {
    const int types[] = {KEY_ED25519, KEY_ECDSA, KEY_ECDSA, KEY_ECDSA, KEY_RSA};
    const unsigned int bits[] = {0, 256, 384, 521, 1024};
    for (unsigned int i = 0; i < 5; i++) {
        struct sshkey *key = NULL; CHECK(sshkey_generate(types[i], bits[i], &key) == 0);
        struct sshbuf *clear = private_file(key, SSHKEY_PRIVATE_OPENSSH, "");
        struct sshbuf *encrypted = private_file(key, SSHKEY_PRIVATE_OPENSSH, "private-phrase");
        unsigned int signs_before = pair_signs, verifies_before = pair_verifies;
        private_expect(clear, NULL, 0, 0, i + 1U, 0, key);
        CHECK(pair_signs == signs_before + 1U && pair_verifies == verifies_before + 1U);
        signs_before = pair_signs;
        private_expect(clear, "must-not-ignore", 3, 1, 0, 0, NULL);
        CHECK(pair_signs == signs_before);
        unsigned int parses_before = private_parses, kdf_before = kdf_calls;
        private_expect(encrypted, NULL, 4, 1, 0, 1, NULL);
        CHECK(private_parses == parses_before && kdf_calls == kdf_before);
        private_expect(encrypted, "wrong", 5, 1, 0, 1, NULL);
        private_expect(encrypted, "private-phrase", 0, 0, i + 1U, 1, key);
        struct sshbuf *twice = sshbuf_new(); CHECK(twice != NULL);
        CHECK(sshbuf_putb(twice, clear) == 0 && sshbuf_putb(twice, clear) == 0);
        private_expect(twice, NULL, 2, 1, 0, 0, NULL);
        sshbuf_free(twice);
        if (i > 0) {
            struct sshbuf *pem = private_file(key, SSHKEY_PRIVATE_PEM, "");
            private_expect(pem, NULL, 0, 0, i + 1U, 0, key);
            sshbuf_free(pem);
            pem = private_file(key, SSHKEY_PRIVATE_PEM, "legacy-phrase");
            parses_before = private_parses; kdf_before = kdf_calls;
            private_expect(pem, NULL, 3, 1, 0, 1, NULL);
            private_expect(pem, "legacy-phrase", 3, 1, 0, 1, NULL);
            CHECK(private_parses == parses_before && kdf_calls == kdf_before);
            sshbuf_free(pem);
        }
        if (i == 4) for (int component = 1; component <= 8; component++) {
            oversized_component = component; signs_before = pair_signs;
            private_expect(clear, NULL, 3, 1, 0, 0, NULL);
            CHECK(pair_signs == signs_before);
            oversized_component = 0;
        }
        sshbuf_free(clear); sshbuf_free(encrypted); sshkey_free(key);
    }
}
static void private_signer_failures(void) {
    struct sshkey *key = NULL; CHECK(sshkey_generate(KEY_ED25519, 0, &key) == 0);
    struct sshbuf *clear = private_file(key, SSHKEY_PRIVATE_OPENSSH, "");
    key->ed25519_sk[0] ^= 1U;
    struct sshbuf *inconsistent = private_file(key, SSHKEY_PRIVATE_OPENSSH, "");
    private_expect(inconsistent, NULL, 1, 4, 0, 0, NULL);
    unsigned char wire[60];
    const int errors[] = {SSH_ERR_ALLOC_FAIL, SSH_ERR_SYSTEM_ERROR, SSH_ERR_LIBCRYPTO_ERROR};
    for (unsigned int i = 0; i < 3; i++) {
        int expected_errno = i == 0 ? ENOMEM : EIO;
        sign_error = errors[i];
        CHECK(verify_private(clear, NULL, wire) == -1 && errno == expected_errno && zero(wire, 60));
        sign_error = 0; verify_error = errors[i];
        CHECK(verify_private(clear, NULL, wire) == -1 && errno == expected_errno && zero(wire, 60));
        verify_error = 0; parse_error = errors[i];
        CHECK(verify_private(clear, NULL, wire) == -1 && errno == expected_errno && zero(wire, 60));
        parse_error = 0;
    }
    sign_error = SSH_ERR_SIGNATURE_INVALID;
    private_expect(clear, NULL, 1, 4, 0, 0, NULL); sign_error = 0;
    verify_error = SSH_ERR_SIGNATURE_INVALID;
    private_expect(clear, NULL, 1, 4, 0, 0, NULL); verify_error = 0;
    CHECK(verify_private(clear, NULL, wire) == 0);
    unsigned int boundaries_count = clock_calls;
    for (unsigned int i = 1; i <= boundaries_count; i++) {
        expire_at = i;
        CHECK(verify_private(clear, NULL, wire) == -1 && errno == ETIMEDOUT && zero(wire, 60));
    }
    expire_at = 0;
    for (size_t cut = 0; cut < sshbuf_len(clear); cut++) {
        struct sshbuf *truncated = sshbuf_from(sshbuf_ptr(clear), cut); CHECK(truncated != NULL);
        private_expect(truncated, NULL, 2, 1, 0, 0, NULL); sshbuf_free(truncated);
    }
    sshbuf_free(inconsistent); sshbuf_free(clear); sshkey_free(key);
}
static void private_policy_and_bounds(void) {
    const unsigned int rounds[] = {0, 1, 64, 65};
    const size_t salts[] = {0, 1, 64, 65};
    for (unsigned int i = 0; i < 4; i++) for (unsigned int j = 0; j < 4; j++) {
        struct sshbuf *file = policy_file(rounds[i], salts[j], 1, "aes256-ctr", "bcrypt");
        int allowed = rounds[i] >= 1 && rounds[i] <= 64 && salts[j] >= 1 && salts[j] <= 64;
        unsigned int parses_before = private_parses, kdf_before = kdf_calls;
        private_expect(file, NULL, allowed ? 4 : 3, 1, 0, 1, NULL);
        if (!allowed) private_expect(file, "never-derived", 3, 1, 0, 1, NULL);
        CHECK(private_parses == parses_before && kdf_calls == kdf_before); sshbuf_free(file);
    }
    for (unsigned int i = 0; i < 3; i++) {
        struct sshbuf *file = policy_file(1, 1, i == 0 ? 2 : 1,
            i == 1 ? "aes128-ctr" : "aes256-ctr", i == 2 ? "unknown" : "bcrypt");
        unsigned int parses_before = private_parses, kdf_before = kdf_calls;
        private_expect(file, "never-derived", 3, 1, 0, 1, NULL);
        CHECK(private_parses == parses_before && kdf_calls == kdf_before); sshbuf_free(file);
    }
    struct sshkey *key = NULL;
    RSA *rsa = RSA_new(); BIGNUM *exponent = BN_new();
    CHECK(rsa != NULL && exponent != NULL && BN_set_word(exponent, 65537) == 1);
    CHECK(RSA_generate_multi_prime_key(rsa, 2048, 3, exponent, NULL) == 1);
    key = sshkey_new(KEY_RSA); CHECK(key != NULL && EVP_PKEY_set1_RSA(key->pkey, rsa) == 1);
    struct sshbuf *multiprime = private_file(key, SSHKEY_PRIVATE_PEM, "");
    unsigned int before_signs = pair_signs;
    private_expect(multiprime, NULL, 3, 1, 0, 0, NULL);
    CHECK(pair_signs == before_signs);
    sshbuf_free(multiprime); sshkey_free(key); RSA_free(rsa); BN_free(exponent);
    unsigned char wire[60]; uint64_t deadline = now_ms() + 60000U;
    CHECK(ctos_ssh_material_verify_private(NULL, CTOS_SSH_MATERIAL_BYTES + 1U, NULL, 0, deadline, wire) == -1 && errno == E2BIG && zero(wire, 60));
    CHECK(ctos_ssh_material_verify_private(NULL, 1, NULL, 0, deadline, wire) == -1 && errno == EINVAL && zero(wire, 60));
    CHECK(ctos_ssh_material_verify_private(NULL, 0, NULL, 1, deadline, wire) == -1 && errno == EINVAL && zero(wire, 60));
    CHECK(ctos_ssh_material_verify_private(NULL, 0, (const unsigned char *)"a", CTOS_SSH_MATERIAL_PASSPHRASE_BYTES + 1U, deadline, wire) == -1 && errno == E2BIG && zero(wire, 60));
    CHECK(ctos_ssh_material_verify_private(NULL, 0, (const unsigned char *)"a\0b", 3, deadline, wire) == -1 && errno == EINVAL && zero(wire, 60));
    CHECK(ctos_ssh_material_verify_private(NULL, 0, NULL, 0, deadline, NULL) == -1 && errno == EINVAL);
    CHECK(ctos_ssh_material_verify_private(NULL, 0, NULL, 0, now_ms(), wire) == -1 && errno == ETIMEDOUT && zero(wire, 60));
    CHECK(ctos_ssh_material_verify_private(NULL, 0, NULL, 0, now_ms() + 61000U, wire) == -1 && errno == EINVAL && zero(wire, 60));
    struct sshbuf *bad = sshbuf_from("bad\0material\n", 13); CHECK(bad != NULL);
    private_expect(bad, NULL, 2, 1, 0, 0, NULL); sshbuf_free(bad);
    unsigned char *large = malloc(CTOS_SSH_MATERIAL_LINE_BYTES + 1U); CHECK(large != NULL);
    memset(large, 'p', CTOS_SSH_MATERIAL_LINE_BYTES + 1U);
    CHECK(ctos_ssh_material_verify_private(large, CTOS_SSH_MATERIAL_LINE_BYTES + 1U, NULL, 0, deadline, wire) == -1 && errno == E2BIG && zero(wire, 60));
    memset(large, '\n', 16385U);
    CHECK(ctos_ssh_material_verify_private(large, 16385U, NULL, 0, deadline, wire) == -1 && errno == E2BIG && zero(wire, 60));
    memset(large, 0, CTOS_SSH_MATERIAL_LINE_BYTES + 1U); free(large);
}
static void public_exports(void) {
    const int types[] = {KEY_ED25519, KEY_ECDSA, KEY_ECDSA, KEY_ECDSA, KEY_RSA};
    const unsigned int bits[] = {0, 256, 384, 521, 2048};
    for (unsigned int i = 0; i < 5; i++) {
        struct sshkey *key = NULL;
        CHECK(sshkey_generate(types[i], bits[i], &key) == 0);
        struct sshbuf *clear = private_file(key, SSHKEY_PRIVATE_OPENSSH, "");
        struct sshbuf *encrypted = private_file(key, SSHKEY_PRIVATE_OPENSSH, "must-not-derive");
        unsigned char *wire = NULL, *blob = NULL;
        size_t length = 0, blob_length = 0;
        unsigned int before_effects = effects, before_signs = pair_signs, before_verifies = pair_verifies;
        clock_calls = 0; observe = 1; public_export_observe = 1;
        CHECK(ctos_ssh_material_export_public(sshbuf_ptr(clear), sshbuf_len(clear), now_ms() + 60000U,
            &wire, &length) == 0);
        observe = 0; public_export_observe = 0;
        CHECK(effects == before_effects && pair_signs == before_signs + 1 && pair_verifies == before_verifies + 1);
        CHECK(wire != NULL && u32(wire) == 0x43534b45U && u32(wire + 4) == 1);
        CHECK(u32(wire + 8) == 0 && u32(wire + 12) == i + 1U && u32(wire + 16) == 1);
        CHECK(sshkey_to_blob(key, &blob, &blob_length) == 0);
        CHECK(length == CTOS_SSH_KEY_EXPORT_HEADER_BYTES + blob_length && u32(wire + 20) == blob_length);
        CHECK(memcmp(wire + CTOS_SSH_KEY_EXPORT_HEADER_BYTES, blob, blob_length) == 0);
        unsigned char digest[32];
        CHECK(ssh_digest_memory(SSH_DIGEST_SHA256, blob, blob_length, digest, sizeof(digest)) == 0);
        CHECK(memcmp(digest, wire + 24, 32) == 0);
        memset(wire, 0, length); free(wire); free(blob); wire = NULL;
        unsigned int before_kdf = kdf_calls, before_parses = private_parses;
        observe = 1; public_export_observe = 1;
        CHECK(ctos_ssh_material_export_public(sshbuf_ptr(encrypted), sshbuf_len(encrypted), now_ms() + 60000U,
            &wire, &length) == 0);
        observe = 0; public_export_observe = 0;
        CHECK(private_parses == before_parses && kdf_calls == before_kdf);
        CHECK(length == CTOS_SSH_KEY_EXPORT_HEADER_BYTES && u32(wire + 8) == CTOS_MATERIAL_NEEDS_PASSPHRASE);
        CHECK(u32(wire + 12) == 0 && u32(wire + 16) == 2 && u32(wire + 20) == 0 && zero(wire + 24, 32));
        free(wire); wire = NULL;
        if (i == 0) {
            key->ed25519_sk[0] ^= 1;
            struct sshbuf *inconsistent = private_file(key, SSHKEY_PRIVATE_OPENSSH, "");
            observe = 1; public_export_observe = 1;
            CHECK(ctos_ssh_material_export_public(sshbuf_ptr(inconsistent), sshbuf_len(inconsistent), now_ms() + 60000U,
                &wire, &length) == 0);
            observe = 0; public_export_observe = 0;
            CHECK(u32(wire + 8) == CTOS_MATERIAL_MALFORMED && length == CTOS_SSH_KEY_EXPORT_HEADER_BYTES);
            CHECK(u32(wire + 12) == 0 && zero(wire + 24, 32)); free(wire); wire = NULL;
            sshbuf_free(inconsistent);
        }
        observe = 1; public_export_observe = 1; sign_error = SSH_ERR_ALLOC_FAIL;
        CHECK(ctos_ssh_material_export_public(sshbuf_ptr(clear), sshbuf_len(clear), now_ms() + 60000U,
            &wire, &length) == -1 && errno == ENOMEM && wire == NULL && length == 0);
        sign_error = 0; observe = 0; public_export_observe = 0;
        CHECK(ctos_ssh_material_export_public(sshbuf_ptr(clear), sshbuf_len(clear), now_ms(), &wire, &length) == -1
            && errno == ETIMEDOUT && wire == NULL && length == 0);
        CHECK(ctos_ssh_material_export_public(NULL, CTOS_SSH_MATERIAL_BYTES + 1U, now_ms() + 60000U,
            &wire, &length) == -1 && wire == NULL && length == 0);
        sshbuf_free(clear); sshbuf_free(encrypted); sshkey_free(key);
    }
}
static void product_export_expect(const struct sshbuf *file, const char *phrase,
    unsigned int status, unsigned int kind, int encrypted, const struct sshkey *subject) {
    size_t body_length = sshbuf_len(file), phrase_length = phrase == NULL ? 0 : strlen(phrase);
    unsigned char *body_before = malloc(body_length + 1U), *phrase_before = malloc(phrase_length + 1U);
    CHECK(body_before != NULL && phrase_before != NULL);
    memcpy(body_before, sshbuf_ptr(file), body_length);
    if (phrase_length != 0) memcpy(phrase_before, phrase, phrase_length);
    unsigned char *wire = NULL, *blob = NULL;
    size_t length = 0, blob_length = 0;
    unsigned int before_effects = effects, before_signs = pair_signs, before_verifies = pair_verifies;
    unsigned int before_parses = private_parses, before_kdf = kdf_calls;
    clock_calls = 0; observe = 1; public_export_observe = 1;
    CHECK(ctos_ssh_material_export_public_with_passphrase(sshbuf_ptr(file), body_length,
        (const unsigned char *)phrase, phrase_length, now_ms() + 60000U, &wire, &length) == 0);
    observe = 0; public_export_observe = 0;
    CHECK(effects == before_effects && memcmp(body_before, sshbuf_ptr(file), body_length) == 0);
    CHECK(phrase_length == 0 || memcmp(phrase_before, phrase, phrase_length) == 0);
    CHECK(wire != NULL && length >= CTOS_SSH_KEY_EXPORT_HEADER_BYTES);
    CHECK(u32(wire) == 0x43534b45U && u32(wire + 4) == 1);
    if (u32(wire + 8) != status) fprintf(stderr, "product export expected=%u actual=%u\n", status, u32(wire + 8));
    CHECK(u32(wire + 8) == status && u32(wire + 12) == kind);
    CHECK(u32(wire + 16) == ((encrypted ? 2U : 0U) | (status == CTOS_MATERIAL_PARSED ? 1U : 0U)));
    if (status == CTOS_MATERIAL_PARSED) {
        CHECK(subject != NULL && sshkey_to_blob(subject, &blob, &blob_length) == 0);
        CHECK(length == CTOS_SSH_KEY_EXPORT_HEADER_BYTES + blob_length && u32(wire + 20) == blob_length);
        CHECK(memcmp(wire + CTOS_SSH_KEY_EXPORT_HEADER_BYTES, blob, blob_length) == 0);
        unsigned char digest[32];
        CHECK(ssh_digest_memory(SSH_DIGEST_SHA256, blob, blob_length, digest, sizeof(digest)) == 0);
        CHECK(memcmp(digest, wire + 24, sizeof(digest)) == 0);
        CHECK(pair_signs == before_signs + 1U && pair_verifies == before_verifies + 1U);
        memset(digest, 0, sizeof(digest)); free(blob);
    } else {
        CHECK(length == CTOS_SSH_KEY_EXPORT_HEADER_BYTES && u32(wire + 20) == 0 && zero(wire + 24, 32));
        CHECK(pair_signs == before_signs && pair_verifies == before_verifies);
        if (status == CTOS_MATERIAL_NEEDS_PASSPHRASE || status == CTOS_MATERIAL_UNSUPPORTED)
            CHECK(private_parses == before_parses && kdf_calls == before_kdf);
    }
    memset(wire, 0, length); free(wire);
    memset(body_before, 0, body_length + 1U); memset(phrase_before, 0, phrase_length + 1U);
    free(body_before); free(phrase_before);
}
static void product_encrypted_exports(void) {
    const int types[] = {KEY_ED25519, KEY_ECDSA, KEY_ECDSA, KEY_ECDSA, KEY_RSA};
    const unsigned int bits[] = {0, 256, 384, 521, 2048};
    for (unsigned int i = 0; i < 5; i++) {
        struct sshkey *key = NULL; CHECK(sshkey_generate(types[i], bits[i], &key) == 0);
        struct sshbuf *clear = private_file(key, SSHKEY_PRIVATE_OPENSSH, "");
        struct sshbuf *encrypted = private_file(key, SSHKEY_PRIVATE_OPENSSH, "product-phrase");
        product_export_expect(clear, NULL, CTOS_MATERIAL_PARSED, i + 1U, 0, key);
        product_export_expect(clear, "must-not-ignore", CTOS_MATERIAL_UNSUPPORTED, 0, 0, NULL);
        product_export_expect(encrypted, NULL, CTOS_MATERIAL_NEEDS_PASSPHRASE, 0, 1, NULL);
        product_export_expect(encrypted, "wrong", CTOS_MATERIAL_BAD_PASSPHRASE, 0, 1, NULL);
        product_export_expect(encrypted, "product-phrase", CTOS_MATERIAL_PARSED, i + 1U, 1, key);
        if (i > 0) {
            const EVP_CIPHER *ciphers[] = {EVP_aes_128_cbc(), EVP_aes_256_cbc()};
            for (unsigned int j = 0; j < 2; j++) {
                BIO *bio = BIO_new(BIO_s_mem()); CHECK(bio != NULL);
                CHECK(PEM_write_bio_PrivateKey_traditional(bio, key->pkey, ciphers[j],
                    (const unsigned char *)"product-phrase", 14, NULL, NULL) == 1);
                char *bytes = NULL; long length = BIO_get_mem_data(bio, &bytes); CHECK(length > 0);
                struct sshbuf *pem = sshbuf_from(bytes, (size_t)length); CHECK(pem != NULL);
                product_export_expect(pem, NULL, CTOS_MATERIAL_NEEDS_PASSPHRASE, 0, 1, NULL);
                product_export_expect(pem, "wrong", CTOS_MATERIAL_BAD_PASSPHRASE, 0, 1, NULL);
                product_export_expect(pem, "product-phrase", CTOS_MATERIAL_PARSED, i + 1U, 1, key);
                sshbuf_free(pem); BIO_free(bio);
            }
        }
        sshbuf_free(clear); sshbuf_free(encrypted); sshkey_free(key);
    }
}
static int supplied_der(const void *value, unsigned char **output) {
    const struct sshbuf *der = value;
    int length = (int)sshbuf_len(der);
    if (output != NULL && *output != NULL) { memcpy(*output, sshbuf_ptr(der), (size_t)length); *output += length; }
    return length;
}
static void product_pem_consumption(void) {
    struct sshkey *key = NULL; CHECK(sshkey_generate(KEY_RSA, 2048, &key) == 0);
    struct sshbuf *clear = private_file(key, SSHKEY_PRIVATE_PEM, "");
    BIO *input = BIO_new_mem_buf(sshbuf_ptr(clear), (int)sshbuf_len(clear)); CHECK(input != NULL);
    char *name = NULL, *header = NULL; unsigned char *der = NULL; long length = 0;
    CHECK(PEM_read_bio(input, &name, &header, &der, &length) == 1);
    struct sshbuf *extra = sshbuf_new(); CHECK(extra != NULL && sshbuf_put(extra, der, (size_t)length) == 0);
    CHECK(sshbuf_put(extra, "junk", 4) == 0);
    BIO *output = BIO_new(BIO_s_mem()); CHECK(output != NULL);
    CHECK(PEM_ASN1_write_bio(supplied_der, name, output, extra, EVP_aes_256_cbc(),
        (const unsigned char *)"product-phrase", 14, NULL, NULL) == 1);
    char *bytes = NULL; long size = BIO_get_mem_data(output, &bytes); CHECK(size > 0);
    struct sshbuf *suffixed = sshbuf_from(bytes, (size_t)size); CHECK(suffixed != NULL);
    product_export_expect(suffixed, "product-phrase", CTOS_MATERIAL_MALFORMED, 0, 1, NULL);
    BIO_free(output); output = BIO_new(BIO_s_mem()); CHECK(output != NULL);
    CHECK(PEM_write_bio_PKCS8PrivateKey(output, key->pkey, EVP_aes_256_cbc(), "product-phrase", 14, NULL, NULL) == 1);
    size = BIO_get_mem_data(output, &bytes); CHECK(size > 0);
    struct sshbuf *pkcs8 = sshbuf_from(bytes, (size_t)size); CHECK(pkcs8 != NULL);
    product_export_expect(pkcs8, NULL, CTOS_MATERIAL_UNSUPPORTED, 0, 1, NULL);
    product_export_expect(pkcs8, "product-phrase", CTOS_MATERIAL_UNSUPPORTED, 0, 1, NULL);
    BIO_free(input); BIO_free(output); OPENSSL_free(name); OPENSSL_free(header); OPENSSL_clear_free(der, (size_t)length);
    sshbuf_free(extra); sshbuf_free(suffixed); sshbuf_free(pkcs8); sshbuf_free(clear); sshkey_free(key);
}
static void product_export_bounds(void) {
    struct sshkey *key = NULL; CHECK(sshkey_generate(KEY_ED25519, 0, &key) == 0);
    char maximum[CTOS_SSH_MATERIAL_PASSPHRASE_BYTES + 1U];
    memset(maximum, 'p', sizeof(maximum)); maximum[sizeof(maximum) - 1U] = '\0';
    struct sshbuf *encrypted = private_file(key, SSHKEY_PRIVATE_OPENSSH, maximum);
    product_export_expect(encrypted, maximum, CTOS_MATERIAL_PARSED, 1, 1, key);
    unsigned int boundaries_count = clock_calls;
    unsigned char *wire = NULL; size_t length = 0;
    for (unsigned int i = 1; i <= boundaries_count; i++) {
        clock_calls = 0; expire_at = i; observe = 1; public_export_observe = 1;
        CHECK(ctos_ssh_material_export_public_with_passphrase(sshbuf_ptr(encrypted), sshbuf_len(encrypted),
            (const unsigned char *)maximum, strlen(maximum), now_ms() + 60000U, &wire, &length) == -1
            && errno == ETIMEDOUT && wire == NULL && length == 0);
        observe = 0; public_export_observe = 0;
    }
    expire_at = 0;
    uint64_t deadline = now_ms() + 60000U;
    CHECK(ctos_ssh_material_export_public_with_passphrase(NULL, CTOS_SSH_MATERIAL_BYTES + 1U,
        NULL, 0, deadline, &wire, &length) == -1 && errno == E2BIG && wire == NULL && length == 0);
    CHECK(ctos_ssh_material_export_public_with_passphrase(NULL, 1, NULL, 0, deadline,
        &wire, &length) == -1 && errno == EINVAL && wire == NULL && length == 0);
    CHECK(ctos_ssh_material_export_public_with_passphrase(NULL, 0, NULL, 1, deadline,
        &wire, &length) == -1 && errno == EINVAL && wire == NULL && length == 0);
    CHECK(ctos_ssh_material_export_public_with_passphrase(NULL, 0, (const unsigned char *)"a",
        CTOS_SSH_MATERIAL_PASSPHRASE_BYTES + 1U, deadline, &wire, &length) == -1
        && errno == E2BIG && wire == NULL && length == 0);
    CHECK(ctos_ssh_material_export_public_with_passphrase(NULL, 0, (const unsigned char *)"a\0b", 3,
        deadline, &wire, &length) == -1 && errno == EINVAL && wire == NULL && length == 0);
    CHECK(ctos_ssh_material_export_public_with_passphrase(NULL, 0, NULL, 0, deadline,
        NULL, &length) == -1 && errno == EINVAL && length == 0);
    CHECK(ctos_ssh_material_export_public_with_passphrase(NULL, 0, NULL, 0, now_ms(),
        &wire, &length) == -1 && errno == ETIMEDOUT && wire == NULL && length == 0);
    CHECK(ctos_ssh_material_export_public_with_passphrase(NULL, 0, NULL, 0, now_ms() + 61000U,
        &wire, &length) == -1 && errno == EINVAL && wire == NULL && length == 0);
    for (unsigned int rounds = 0; rounds <= 65; rounds += 65) {
        struct sshbuf *policy = policy_file(rounds, 1, 1, "aes256-ctr", "bcrypt");
        product_export_expect(policy, "never-derived", CTOS_MATERIAL_UNSUPPORTED, 0, 1, NULL);
        sshbuf_free(policy);
    }
    memset(maximum, 0, sizeof(maximum)); sshbuf_free(encrypted); sshkey_free(key);
}
static int private_baseline_main(void) {
    CHECK(pair_baseline_main() == 0);
    unsigned int baseline = checks, before = fd_count();
    private_algorithms(); private_signer_failures(); private_policy_and_bounds(); public_exports();
    product_encrypted_exports(); product_pem_consumption(); product_export_bounds();
    CHECK(fd_count() == before && effects == 0 && streams == closed_streams);
    printf("SSH_PRIVATE_MATERIAL checks=%u baseline=%u signs=%u verifies=%u effects=%u cleanup=pass\n",
        checks, baseline, pair_signs, pair_verifies, effects);
    return 0;
}
#ifndef CTOS_PRIVATE_TEST_INCLUDED
int main(void) { return private_baseline_main(); }
#endif

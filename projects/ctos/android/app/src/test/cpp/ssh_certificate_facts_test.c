/* Reuse all prior material/proof fixtures and bounded effect observers. */
#define CTOS_PRIVATE_TEST_INCLUDED 1
#include "ssh_private_material_test.c"

struct certificate_fixture {
    struct sshbuf *blob, *text;
};
static uint64_t certificate_u64(const unsigned char *p) {
    return (uint64_t)u32(p) << 32 | u32(p + 4);
}
static struct sshbuf *serialized_key(const struct sshkey *key) {
    unsigned char *blob = NULL;
    size_t length = 0;
    CHECK(sshkey_to_blob(key, &blob, &length) == 0);
    struct sshbuf *result = sshbuf_from(blob, length);
    CHECK(result != NULL);
    /* sshbuf_from borrows, so move the public bytes into an owned buffer. */
    struct sshbuf *owned = sshbuf_new(); CHECK(owned != NULL && sshbuf_putb(owned, result) == 0);
    sshbuf_free(result); free(blob);
    return owned;
}
static struct sshbuf *certificate_text(const struct sshkey *subject, const struct sshbuf *blob) {
    struct sshkey *type_key = NULL;
    CHECK(sshkey_from_private(subject, &type_key) == 0 && sshkey_to_certified(type_key) == 0);
    const char *name = sshkey_ssh_name(type_key);
    struct sshbuf *text = sshbuf_new(); CHECK(text != NULL);
    CHECK(sshbuf_putf(text, "%s ", name) == 0 && sshbuf_dtob64(blob, text, 0) == 0);
    sshkey_free(type_key);
    return text;
}
/* The fixture encoder signs the exact public wire bytes. It deliberately does
 * not call the production certificate parser to establish expected metadata. */
static struct certificate_fixture make_certificate(struct sshkey *subject, struct sshkey *ca,
    unsigned int type, uint64_t serial, uint64_t after, uint64_t before,
    const struct sshbuf *principals, const struct sshbuf *critical, const struct sshbuf *extensions,
    const char *algorithm, const struct sshbuf *embedded_ca) {
    struct sshbuf *plain = serialized_key(subject), *ca_blob = serialized_key(ca), *blob = sshbuf_new();
    struct sshkey *type_key = NULL;
    char *plain_name = NULL;
    CHECK(blob != NULL && sshbuf_get_cstring(plain, &plain_name, NULL) == 0);
    CHECK(sshkey_from_private(subject, &type_key) == 0 && sshkey_to_certified(type_key) == 0);
    unsigned char nonce[32]; memset(nonce, 0x5a, sizeof(nonce));
    CHECK(sshbuf_put_cstring(blob, sshkey_ssh_name(type_key)) == 0
        && sshbuf_put_string(blob, nonce, sizeof(nonce)) == 0 && sshbuf_putb(blob, plain) == 0
        && sshbuf_put_u64(blob, serial) == 0 && sshbuf_put_u32(blob, type) == 0
        && sshbuf_put_cstring(blob, "certificate-facts-synthetic") == 0
        && sshbuf_put_stringb(blob, principals) == 0
        && sshbuf_put_u64(blob, after) == 0 && sshbuf_put_u64(blob, before) == 0
        && sshbuf_put_stringb(blob, critical) == 0 && sshbuf_put_stringb(blob, extensions) == 0
        && sshbuf_put_string(blob, "reserved\0bytes", 14) == 0
        && sshbuf_put_stringb(blob, embedded_ca == NULL ? ca_blob : embedded_ca) == 0);
    unsigned char *signature = NULL; size_t signature_length = 0;
    CHECK(__real_sshkey_sign(ca, &signature, &signature_length, sshbuf_ptr(blob), sshbuf_len(blob),
        algorithm, NULL, NULL, 0) == 0);
    CHECK(sshbuf_put_string(blob, signature, signature_length) == 0);
    memset(signature, 0, signature_length); free(signature);
    struct certificate_fixture result = {blob, certificate_text(subject, blob)};
    sshkey_free(type_key); free(plain_name); sshbuf_free(plain); sshbuf_free(ca_blob);
    return result;
}
static void free_certificate(struct certificate_fixture *fixture) {
    sshbuf_free(fixture->blob); sshbuf_free(fixture->text);
    fixture->blob = fixture->text = NULL;
}
static int inspect_certificate(const struct sshbuf *text, unsigned char wire[168]) {
    size_t length = sshbuf_len(text);
    unsigned char *before = malloc(length + 1U); CHECK(before != NULL);
    memcpy(before, sshbuf_ptr(text), length);
    unsigned int before_effects = effects, before_signs = pair_signs;
    unsigned int before_parses = private_parses, before_kdf = kdf_calls;
    clock_calls = 0;
    uint64_t deadline = now_ms() + 60000U;
    observe = 1;
    int result = ctos_ssh_material_inspect_certificate(sshbuf_ptr(text), length, deadline, wire);
    observe = 0;
    CHECK(effects == before_effects && pair_signs == before_signs);
    CHECK(private_parses == before_parses && kdf_calls == before_kdf);
    CHECK(memcmp(before, sshbuf_ptr(text), length) == 0);
    memset(before, 0, length + 1U); free(before);
    return result;
}
static void certificate_digest(const struct sshbuf *source, const unsigned char *digest) {
    unsigned char expected[32];
    CHECK(ssh_digest_memory(SSH_DIGEST_SHA256, sshbuf_ptr(source), sshbuf_len(source), expected, 32) == 0);
    CHECK(memcmp(expected, digest, 32) == 0 && !zero(digest, 32));
}
static void certificate_failure(const struct sshbuf *text, unsigned int status, unsigned int line) {
    unsigned char wire[168]; memset(wire, 0xa5, sizeof(wire));
    CHECK(inspect_certificate(text, wire) == 0);
    if (u32(wire + 8) != status || u32(wire + 44) != line)
        fprintf(stderr, "certificate expected=%u/%u actual=%u/%u\n", status, line, u32(wire + 8), u32(wire + 44));
    CHECK(u32(wire) == 0x43534346U && u32(wire + 4) == 1U && u32(wire + 8) == status);
    CHECK(u32(wire + 12) == 1U && u32(wire + 44) == line);
    CHECK(zero(wire + 16, 28) && zero(wire + 48, 120));
}
static void certificate_facts(const struct certificate_fixture *fixture, struct sshkey *subject,
    struct sshkey *ca, unsigned int type, unsigned int subject_kind, unsigned int ca_kind,
    unsigned int algorithm, unsigned int principal_count, size_t critical, size_t extensions,
    uint64_t serial, uint64_t after, uint64_t before, unsigned int line) {
    unsigned char wire[168]; memset(wire, 0xa5, sizeof(wire));
    CHECK(inspect_certificate(fixture->text, wire) == 0);
    if (u32(wire + 8) != 0) fprintf(stderr, "certificate facts failed status=%u line=%u\n", u32(wire + 8), u32(wire + 44));
    CHECK(u32(wire) == 0x43534346U && u32(wire + 4) == 1U && u32(wire + 8) == 0U && u32(wire + 12) == 7U);
    CHECK(u32(wire + 16) == type && u32(wire + 20) == subject_kind && u32(wire + 24) == ca_kind);
    CHECK(u32(wire + 28) == algorithm && u32(wire + 32) == principal_count);
    CHECK(u32(wire + 36) == critical && u32(wire + 40) == extensions && u32(wire + 44) == line);
    CHECK(certificate_u64(wire + 48) == serial && certificate_u64(wire + 56) == after && certificate_u64(wire + 64) == before);
    certificate_digest(fixture->blob, wire + 72);
    struct sshbuf *subject_blob = serialized_key(subject), *ca_blob = serialized_key(ca);
    certificate_digest(subject_blob, wire + 104); certificate_digest(ca_blob, wire + 136);
    CHECK(memcmp(wire + 72, wire + 104, 32) != 0);
    sshbuf_free(subject_blob); sshbuf_free(ca_blob);
}
static void certificate_algorithms(void) {
    const int types[] = {KEY_ED25519, KEY_ECDSA, KEY_ECDSA, KEY_ECDSA, KEY_RSA};
    const unsigned int bits[] = {0, 256, 384, 521, 1024};
    struct sshkey *keys[5] = {0};
    struct sshbuf *empty = sshbuf_new(), *principals = sshbuf_new(); CHECK(empty != NULL && principals != NULL);
    CHECK(sshbuf_put_cstring(principals, "synthetic-principal") == 0);
    for (unsigned int i = 0; i < 5; i++) CHECK(sshkey_generate(types[i], bits[i], &keys[i]) == 0);
    for (unsigned int i = 0; i < 5; i++) for (unsigned int j = 0; j < 5; j++) {
        const char *algorithm = j == 4 ? "rsa-sha2-256" : sshkey_ssh_name(keys[j]);
        unsigned int type = (i + j) % 2U ? SSH2_CERT_TYPE_USER : SSH2_CERT_TYPE_HOST;
        struct certificate_fixture fixture = make_certificate(keys[i], keys[j], type,
            UINT64_C(0x8000000000000000) + i, UINT64_MAX, 0, principals, empty, empty, algorithm, NULL);
        unsigned int before_verifies = pair_verifies;
        certificate_facts(&fixture, keys[i], keys[j], type, i + 1U, j + 1U, j + 1U, 1, 0, 0,
            UINT64_C(0x8000000000000000) + i, UINT64_MAX, 0, 1);
        CHECK(pair_verifies == before_verifies + 1U);
        free_certificate(&fixture);
    }
    struct certificate_fixture sha512 = make_certificate(keys[0], keys[4], SSH2_CERT_TYPE_USER,
        UINT64_MAX, 0, UINT64_MAX, empty, empty, empty, "rsa-sha2-512", NULL);
    certificate_facts(&sha512, keys[0], keys[4], 1, 1, 5, 6, 0, 0, 0, UINT64_MAX, 0, UINT64_MAX, 1);
    free_certificate(&sha512);
    struct certificate_fixture sha1 = make_certificate(keys[0], keys[4], SSH2_CERT_TYPE_HOST,
        0, 0, 1, empty, empty, empty, "ssh-rsa", NULL);
    certificate_failure(sha1.text, CTOS_CERTIFICATE_UNSUPPORTED, 1);
    /* The existing material operation retains its broader signature policy. */
    expect_buf(4, sha1.text, NULL, CTOS_MATERIAL_PARSED, 1, 6, 1, 0);
    free_certificate(&sha1);
    for (unsigned int i = 0; i < 5; i++) sshkey_free(keys[i]);
    sshbuf_free(empty); sshbuf_free(principals);
}
static void certificate_metadata(void) {
    struct sshkey *subject = NULL, *ca = NULL;
    CHECK(sshkey_generate(KEY_ED25519, 0, &subject) == 0 && sshkey_generate(KEY_ED25519, 0, &ca) == 0);
    struct sshbuf *empty = sshbuf_new(), *principals = sshbuf_new(), *critical = sshbuf_new(), *extensions = sshbuf_new();
    CHECK(empty != NULL && principals != NULL && critical != NULL && extensions != NULL);
    const uint64_t values[][3] = {{0, 0, 0}, {UINT64_MAX, 0, UINT64_MAX},
        {UINT64_C(0x8000000000000000), UINT64_MAX, 0}, {42, 1577836800, 1577923200},
        {17, UINT64_MAX - 1U, UINT64_MAX}, {8, UINT64_C(0x8000000000000000), UINT64_C(0x8000000000000000)}};
    for (size_t i = 0; i < sizeof(values) / sizeof(values[0]); i++) {
        struct certificate_fixture fixture = make_certificate(subject, ca, 1U + (unsigned int)i % 2U,
            values[i][0], values[i][1], values[i][2], empty, empty, empty, "ssh-ed25519", NULL);
        certificate_facts(&fixture, subject, ca, 1U + (unsigned int)i % 2U, 1, 1, 1, 0, 0, 0,
            values[i][0], values[i][1], values[i][2], 1);
        free_certificate(&fixture);
    }
    CHECK(sshbuf_put_string(principals, "", 0) == 0 && sshbuf_put_cstring(principals, "same") == 0
        && sshbuf_put_cstring(principals, "same") == 0
        && sshbuf_put_string(principals, "trailing\0", 9) == 0
        && sshbuf_put_string(principals, "\xff", 1) == 0);
    CHECK(sshbuf_put_cstring(critical, "unknown-critical") == 0 && sshbuf_put_cstring(critical, "opaque") == 0
        && sshbuf_put_cstring(critical, "unknown-critical") == 0 && sshbuf_put_string(critical, "", 0) == 0);
    CHECK(sshbuf_put_string(extensions, "", 0) == 0 && sshbuf_put_cstring(extensions, "unknown-extension") == 0
        && sshbuf_put_cstring(extensions, "duplicate") == 0 && sshbuf_put_cstring(extensions, "one") == 0
        && sshbuf_put_cstring(extensions, "duplicate") == 0 && sshbuf_put_cstring(extensions, "two") == 0);
    struct certificate_fixture unusual = make_certificate(subject, ca, 2, 9, 3, 2, principals, critical, extensions, "ssh-ed25519", NULL);
    struct sshbuf *source = unusual.text; unusual.text = sshbuf_new(); CHECK(unusual.text != NULL);
    CHECK(sshbuf_put(unusual.text, "# comment\n\n \t", 13) == 0 && sshbuf_putb(unusual.text, source) == 0
        && sshbuf_put(unusual.text, " comment\r\n# tail\n", sizeof(" comment\r\n# tail\n") - 1U) == 0);
    certificate_facts(&unusual, subject, ca, 2, 1, 1, 1, 5, sshbuf_len(critical), sshbuf_len(extensions), 9, 3, 2, 3);
    sshbuf_free(source); free_certificate(&unusual);
    sshbuf_reset(principals);
    for (unsigned int i = 0; i < 256; i++) CHECK(sshbuf_put_cstring(principals, "p") == 0);
    struct certificate_fixture max = make_certificate(subject, ca, 1, 0, 0, 0, principals, empty, empty, "ssh-ed25519", NULL);
    certificate_facts(&max, subject, ca, 1, 1, 1, 1, 256, 0, 0, 0, 0, 0, 1); free_certificate(&max);
    CHECK(sshbuf_put_cstring(principals, "too-many") == 0);
    max = make_certificate(subject, ca, 1, 0, 0, 0, principals, empty, empty, "ssh-ed25519", NULL);
    certificate_failure(max.text, CTOS_CERTIFICATE_MALFORMED, 1); free_certificate(&max);
    sshbuf_reset(principals); CHECK(sshbuf_put_string(principals, "bad\0interior", 12) == 0);
    max = make_certificate(subject, ca, 1, 0, 0, 0, principals, empty, empty, "ssh-ed25519", NULL);
    certificate_failure(max.text, CTOS_CERTIFICATE_MALFORMED, 1); free_certificate(&max);
    sshbuf_reset(critical); CHECK(sshbuf_put_cstring(critical, "unpaired") == 0);
    max = make_certificate(subject, ca, 1, 0, 0, 0, empty, critical, empty, "ssh-ed25519", NULL);
    certificate_failure(max.text, CTOS_CERTIFICATE_MALFORMED, 1); free_certificate(&max);
    sshbuf_reset(extensions); CHECK(sshbuf_put_cstring(extensions, "unpaired") == 0);
    max = make_certificate(subject, ca, 1, 0, 0, 0, empty, empty, extensions, "ssh-ed25519", NULL);
    certificate_failure(max.text, CTOS_CERTIFICATE_MALFORMED, 1); free_certificate(&max);
    sshbuf_free(empty); sshbuf_free(principals); sshbuf_free(critical); sshbuf_free(extensions);
    sshkey_free(subject); sshkey_free(ca);
}
static void certificate_failures_and_lifetime(void) {
    struct sshkey *subject = NULL, *ca = NULL, *other_ca = NULL;
    CHECK(sshkey_generate(KEY_ED25519, 0, &subject) == 0 && sshkey_generate(KEY_ED25519, 0, &ca) == 0
        && sshkey_generate(KEY_ED25519, 0, &other_ca) == 0);
    struct sshbuf *empty = sshbuf_new(); CHECK(empty != NULL);
    struct certificate_fixture fixture = make_certificate(subject, ca, 1, 0, 0, 0, empty, empty, empty, "ssh-ed25519", NULL);
    struct sshbuf *public_only = public_text(subject);
    certificate_failure(public_only, CTOS_CERTIFICATE_MALFORMED, 1);
    struct certificate_fixture bad_type = make_certificate(subject, ca, 3, 0, 0, 0, empty, empty, empty, "ssh-ed25519", NULL);
    certificate_failure(bad_type.text, CTOS_CERTIFICATE_MALFORMED, 1); free_certificate(&bad_type);
    struct sshkey *nested = certificate(ca, other_ca);
    struct sshbuf *nested_blob = serialized_key(nested), *bad_ca = sshbuf_new(); CHECK(bad_ca != NULL && sshbuf_put(bad_ca, "bad", 3) == 0);
    struct certificate_fixture invalid_ca = make_certificate(subject, ca, 1, 0, 0, 0, empty, empty, empty, "ssh-ed25519", nested_blob);
    certificate_failure(invalid_ca.text, CTOS_CERTIFICATE_MALFORMED, 1); free_certificate(&invalid_ca);
    invalid_ca = make_certificate(subject, ca, 1, 0, 0, 0, empty, empty, empty, "ssh-ed25519", bad_ca);
    certificate_failure(invalid_ca.text, CTOS_CERTIFICATE_MALFORMED, 1); free_certificate(&invalid_ca);
    struct sshbuf *corrupt = sshbuf_new(); CHECK(corrupt != NULL && sshbuf_putb(corrupt, fixture.blob) == 0);
    sshbuf_mutable_ptr(corrupt)[sshbuf_len(corrupt) - 1U] ^= 1U;
    struct sshbuf *corrupt_text = certificate_text(subject, corrupt);
    certificate_failure(corrupt_text, CTOS_CERTIFICATE_MALFORMED, 1);
    sshbuf_free(corrupt_text); sshbuf_free(corrupt);
    struct sshbuf *suffix = sshbuf_new(); CHECK(suffix != NULL && sshbuf_putb(suffix, fixture.blob) == 0 && sshbuf_put(suffix, "junk", 4) == 0);
    corrupt_text = certificate_text(subject, suffix);
    certificate_failure(corrupt_text, CTOS_CERTIFICATE_MALFORMED, 1); sshbuf_free(corrupt_text); sshbuf_free(suffix);
    struct sshbuf *twice = sshbuf_new(); CHECK(twice != NULL && sshbuf_putb(twice, fixture.text) == 0
        && sshbuf_put(twice, "\n", 1) == 0 && sshbuf_putb(twice, fixture.text) == 0);
    certificate_failure(twice, CTOS_CERTIFICATE_MALFORMED, 2); sshbuf_free(twice);
    const char *bad[] = {"", "# only comment\n", "junk", "ssh-ed25519-cert-v01@openssh.com AAAA"};
    for (size_t i = 0; i < sizeof(bad) / sizeof(bad[0]); i++) {
        struct sshbuf *text = sshbuf_from(bad[i], strlen(bad[i])); CHECK(text != NULL);
        certificate_failure(text, CTOS_CERTIFICATE_MALFORMED, i < 2 ? 0 : 1); sshbuf_free(text);
    }
    for (size_t cut = 1; cut + 64U < sshbuf_len(fixture.text); cut += 17U) {
        struct sshbuf *text = sshbuf_from(sshbuf_ptr(fixture.text), cut); CHECK(text != NULL);
        certificate_failure(text, CTOS_CERTIFICATE_MALFORMED, 1); sshbuf_free(text);
    }
    unsigned char wire[168];
    const int errors[] = {SSH_ERR_ALLOC_FAIL, SSH_ERR_SYSTEM_ERROR, SSH_ERR_LIBCRYPTO_ERROR};
    for (unsigned int i = 0; i < 3; i++) {
        verify_error = errors[i];
        CHECK(inspect_certificate(fixture.text, wire) == -1 && errno == (i == 0 ? ENOMEM : EIO) && zero(wire, 168));
        verify_error = 0;
    }
    verify_error = SSH_ERR_SIGNATURE_INVALID;
    certificate_failure(fixture.text, CTOS_CERTIFICATE_MALFORMED, 1); verify_error = 0;
    CHECK(inspect_certificate(fixture.text, wire) == 0);
    unsigned int boundaries_count = clock_calls;
    for (unsigned int i = 1; i <= boundaries_count; i++) {
        expire_at = i;
        CHECK(inspect_certificate(fixture.text, wire) == -1 && errno == ETIMEDOUT && zero(wire, 168));
    }
    expire_at = 0;
    uint64_t deadline = now_ms() + 60000U;
    CHECK(ctos_ssh_material_inspect_certificate(NULL, CTOS_SSH_MATERIAL_BYTES + 1U, deadline, wire) == -1 && errno == E2BIG && zero(wire, 168));
    CHECK(ctos_ssh_material_inspect_certificate(NULL, 1, deadline, wire) == -1 && errno == EINVAL && zero(wire, 168));
    CHECK(ctos_ssh_material_inspect_certificate(NULL, 0, deadline, NULL) == -1 && errno == EINVAL);
    CHECK(ctos_ssh_material_inspect_certificate(NULL, 0, now_ms(), wire) == -1 && errno == ETIMEDOUT && zero(wire, 168));
    CHECK(ctos_ssh_material_inspect_certificate(NULL, 0, now_ms() + 61000U, wire) == -1 && errno == EINVAL && zero(wire, 168));
    struct sshbuf *nul = sshbuf_from("# one\n\0bad", 10); CHECK(nul != NULL);
    certificate_failure(nul, CTOS_CERTIFICATE_MALFORMED, 2); sshbuf_free(nul);
    unsigned char *large = malloc(CTOS_SSH_MATERIAL_LINE_BYTES + 1U); CHECK(large != NULL);
    memset(large, 'p', CTOS_SSH_MATERIAL_LINE_BYTES + 1U);
    CHECK(ctos_ssh_material_inspect_certificate(large, CTOS_SSH_MATERIAL_LINE_BYTES + 1U, deadline, wire) == -1 && errno == E2BIG && zero(wire, 168));
    memset(large, '\n', 16385U);
    CHECK(ctos_ssh_material_inspect_certificate(large, 16385U, deadline, wire) == -1 && errno == E2BIG && zero(wire, 168));
    memset(large, 0, CTOS_SSH_MATERIAL_LINE_BYTES + 1U); free(large);
    sshbuf_free(public_only); sshbuf_free(empty); sshbuf_free(nested_blob); sshbuf_free(bad_ca); sshkey_free(nested);
    free_certificate(&fixture); sshkey_free(subject); sshkey_free(ca); sshkey_free(other_ca);
}
int main(void) {
    CHECK(private_baseline_main() == 0);
    unsigned int baseline = checks, before = fd_count();
    certificate_algorithms(); certificate_metadata(); certificate_failures_and_lifetime();
    CHECK(fd_count() == before && effects == 0 && streams == closed_streams);
    printf("SSH_CERTIFICATE_FACTS checks=%u baseline=%u signs=%u verifies=%u effects=%u cleanup=pass\n",
        checks, baseline, pair_signs, pair_verifies, effects);
    return 0;
}

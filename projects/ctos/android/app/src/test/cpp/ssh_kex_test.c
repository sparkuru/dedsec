#include "includes.h"
#include <errno.h>
#include <stdint.h>
#include <time.h>
#include <unistd.h>
#include <openssl/crypto.h>
#include "ssh_api.h"
#include "sshbuf.h"
#include "ssherr.h"
#include "digest.h"
#include "myproposal.h"
#include "log.h"
#include "dh.h"
#include "ssh_kex_guard.h"
#include "ssh_native_launcher.h"

static unsigned int checks, cases, approvals, signatures, corrupt_signature, reject_host;
static unsigned char nonce[32] = {1}, connection[32] = {2};
static unsigned int issued;
static struct ssh *observed;
struct fixture_binding { struct ssh *client; ctos_ssh_role_token token; };
static struct fixture_binding bindings[8];
#define CHECK(condition) do { checks++; if (!(condition)) { \
    printf("SSH_KEX phase=gate state=error errno=5 value=%u\n", (unsigned int)__LINE__); exit(2); \
} } while (0)
static uint64_t millis(void) {
    struct timespec time;
    CHECK(clock_gettime(CLOCK_BOOTTIME, &time) == 0);
    return (uint64_t)time.tv_sec * 1000 + (uint64_t)time.tv_nsec / 1000000;
}
int __real_sshkey_sign(struct sshkey *, unsigned char **, size_t *,
    const unsigned char *, size_t, const char *, const char *, const char *, unsigned int);
int __wrap_sshkey_sign(struct sshkey *key, unsigned char **sig, size_t *len,
    const unsigned char *data, size_t size, const char *alg, const char *provider,
    const char *pin, unsigned int compat) {
    int result = __real_sshkey_sign(key, sig, len, data, size, alg, provider, pin, compat);
    if (result == 0 && corrupt_signature) { CHECK(*len > 0); (*sig)[*len - 1] ^= 1; }
    return result;
}
int __real_sshkey_verify(const struct sshkey *, const unsigned char *, size_t,
    const unsigned char *, size_t, const char *, unsigned int, struct sshkey_sig_details **);
int __wrap_sshkey_verify(const struct sshkey *key, const unsigned char *sig, size_t len,
    const unsigned char *data, size_t size, const char *alg, unsigned int compat,
    struct sshkey_sig_details **details) {
    int result = __real_sshkey_verify(key, sig, len, data, size, alg, compat, details);
    if (result == 0) {
        CHECK(observed != NULL && ctos_ssh_kex_ready(observed, 0, nonce, connection) == 0);
        signatures++;
    }
    return result;
}
/* The real DH-GEX transcript is exercised with a built-in upstream group;
 * this fixture never reads a host moduli file. */
DH *__wrap_mm_choose_dh(int min, int preferred, int max) {
    (void)preferred;
    CHECK(min <= 2048 && max >= 2048);
    return dh_new_group14();
}
static int verify_host(struct sshkey *key, struct ssh *ssh) {
    (void)key;
    CHECK(ctos_ssh_kex_ready(ssh, 0, nonce, connection) == 0);
    approvals++;
    return reject_host ? -1 : 0;
}
static ctos_ssh_role_token token_for(struct ssh *client) {
    for (unsigned int i = 0; i < sizeof(bindings) / sizeof(bindings[0]); i++)
        if (bindings[i].client == client) return bindings[i].token;
    return 0;
}
static void bind_role(struct ssh *client, struct sshkey *public, unsigned int hop) {
    unsigned char *hash = NULL;
    unsigned char wire[CTOS_SSH_ROLE_WIRE_BYTES];
    unsigned char malformed[CTOS_SSH_ROLE_WIRE_BYTES];
    unsigned char trailing[CTOS_SSH_ROLE_WIRE_BYTES];
    unsigned char wrong_wire[CTOS_SSH_ROLE_WIRE_BYTES];
    size_t length = 0;
    size_t wire_length = 0;
    CHECK(sshkey_fingerprint_raw(public, SSH_DIGEST_SHA256, &hash, &length) == 0 && length == 32);
    issued++; nonce[0] = (unsigned char)issued;
    struct ctos_ssh_role_request request = {
        .program = CTOS_SSH_ROLE_CLIENT,
        .owner_pid = getpid(), .owner_uid = getuid(), .hop = hop, .port = 22,
        .deadline = millis() + 30000,
        .connection_id = "fixture_connection",
        .target_identity = "target:user@example.invalid:22",
        .host = "example.invalid",
        .public_view_id = hop == 0 ? "fixture_view_0" : "fixture_view_1",
        .role_nonce = nonce, .connection_binding = connection
    };
    ctos_ssh_role_token token = 0;
    CHECK(ctos_ssh_role_bootstrap_open(&request, &token) == 0);
    CHECK(ctos_ssh_role_bootstrap_encode(token, wire, sizeof(wire), &wire_length) == 0
        && wire_length > 0 && wire_length < sizeof(wire));
    memcpy(malformed, wire, wire_length);
    malformed[0] ^= 0xff;
    CHECK(ctos_ssh_native_launcher_admit(malformed, wire_length, token, client, hash) != 0);
    memcpy(trailing, wire, wire_length);
    trailing[wire_length] = 0xa5;
    CHECK(ctos_ssh_native_launcher_admit(trailing, wire_length + 1, token, client, hash) != 0);
    memcpy(wrong_wire, wire, wire_length);
    wrong_wire[wire_length - 1] ^= 1;
    CHECK(ctos_ssh_native_launcher_admit(wrong_wire, wire_length, token, client, hash) != 0);
    CHECK(ctos_ssh_native_launcher_admit(wire, wire_length, token, client, hash) == 0);
    CHECK(ctos_ssh_native_launcher_close(client, token + 1) != 0);
    CHECK(ctos_ssh_native_launcher_admit(wire, wire_length, token, client, hash) != 0);
    CHECK(ctos_ssh_kex_ready(client, hop, nonce, connection) == 0);
    for (unsigned int i = 0; i < sizeof(bindings) / sizeof(bindings[0]); i++) {
        if (bindings[i].client == NULL) {
            bindings[i].client = client; bindings[i].token = token; break;
        }
    }
    explicit_bzero(wire, sizeof(wire));
    explicit_bzero(malformed, sizeof(malformed));
    explicit_bzero(trailing, sizeof(trailing));
    explicit_bzero(wrong_wire, sizeof(wrong_wire));
    explicit_bzero(hash, length); free(hash);
}
static void setup(const char *algorithm, struct sshkey *private, struct sshkey *public,
    struct ssh **client, struct ssh **server) {
    struct kex_params params;
    char *defaults[PROPOSAL_MAX] = {KEX_CLIENT};
    memcpy(params.proposal, defaults, sizeof(defaults));
    params.proposal[PROPOSAL_KEX_ALGS] = (char *)algorithm;
    params.proposal[PROPOSAL_ENC_ALGS_CTOS] = "aes128-ctr";
    params.proposal[PROPOSAL_ENC_ALGS_STOC] = "aes128-ctr";
    params.proposal[PROPOSAL_MAC_ALGS_CTOS] = "hmac-sha2-256";
    params.proposal[PROPOSAL_MAC_ALGS_STOC] = "hmac-sha2-256";
    params.proposal[PROPOSAL_SERVER_HOST_KEY_ALGS] = private->type == KEY_RSA ?
        "rsa-sha2-256" : (char *)sshkey_ssh_name(private);
    CHECK(ssh_init(client, 0, &params) == 0 && ssh_init(server, 1, &params) == 0);
    CHECK(ssh_add_hostkey(*server, private) == 0 && ssh_add_hostkey(*client, public) == 0);
    CHECK(ssh_set_verify_host_key_callback(*client, verify_host) == 0);
    observed = *client; approvals = signatures = 0;
}
static int advance(struct ssh *from, struct ssh *to, size_t fragment) {
    unsigned char type = 0;
    size_t length = 0;
    if (!from->kex->server) observed = from;
    int result = ssh_packet_next(from, &type);
    if (result != 0) return result;
    /* ssh_api leaves EXT_INFO to its caller; validate with the real handler. */
    CHECK(type == 0 || type == SSH2_MSG_EXT_INFO);
    if (type == SSH2_MSG_EXT_INFO) CHECK(kex_input_ext_info(type, 0, from) == 0);
    const unsigned char *bytes = ssh_output_ptr(from, &length);
    CHECK(length <= 131072);
    if (length != 0) {
        /* The upstream test API expects complete identification lines. Only
         * fragment binary KEX packets after both banners were exchanged. */
        if (!(from->kex->flags & KEX_INIT_SENT) || !(to->kex->flags & KEX_INIT_SENT)) fragment = 0;
        if (fragment != 0 && length > fragment) length = fragment;
        CHECK(ssh_input_append(to, bytes, length) == 0);
        CHECK(ssh_output_consume(from, length) == 0);
    }
    return 0;
}
static int handshake(struct ssh *client, struct ssh *server, int stop_at_signature) {
    uint64_t deadline = millis() + 20000;
    for (unsigned int attempt = 0; attempt < 20000 && millis() < deadline; attempt++) {
        int result = advance(server, client, stop_at_signature ? 1 : 0);
        if (result == 0) result = advance(client, server, stop_at_signature ? 1 : 0);
        if (result != 0) return result;
        if (stop_at_signature && signatures != 0) return 0;
        if (client->kex->done && server->kex->done) return 0;
    }
    return SSH_ERR_CONN_TIMEOUT;
}
static void dispose(struct ssh *client, struct ssh *server) {
    ctos_ssh_role_token token = token_for(client);
    if (token != 0) {
        errno = 0;
        CHECK(ctos_ssh_native_launcher_close(client, token + 1) != 0);
        CHECK(ctos_ssh_native_launcher_close(client, token) == 0);
        errno = 0;
        CHECK(ctos_ssh_native_launcher_close(client, token) != 0 && errno == ENOENT);
        for (unsigned int i = 0; i < sizeof(bindings) / sizeof(bindings[0]); i++)
            if (bindings[i].client == client) { bindings[i].client = NULL; bindings[i].token = 0; }
    }
    CHECK(ctos_ssh_kex_ready(client, 0, nonce, connection) == 0);
    ssh_free(client); ssh_free(server); observed = NULL;
}
static void positive(const char *algorithm, int type, int bits) {
    struct sshkey *private = NULL, *public = NULL;
    struct ssh *client = NULL, *server = NULL;
    CHECK(sshkey_generate(type, bits, &private) == 0 && sshkey_from_private(private, &public) == 0);
    setup(algorithm, private, public, &client, &server); bind_role(client, public, 0);
    CHECK(ctos_ssh_kex_ready(client, 0, nonce, connection) == 0);
    CHECK(handshake(client, server, 0) == 0 && client->kex->done && server->kex->done);
    CHECK(approvals == 1 && signatures == 1 && ctos_ssh_kex_ready(client, 0, nonce, connection) == 1);
    unsigned char wrong[32] = {254};
    CHECK(ctos_ssh_kex_ready(client, 1, nonce, connection) == 0);
    CHECK(ctos_ssh_kex_ready(client, 0, wrong, connection) == 0);
    CHECK(ctos_ssh_kex_ready(client, 0, nonce, wrong) == 0);
    CHECK(kex_send_kexinit(client) == 0 && ctos_ssh_kex_ready(client, 0, nonce, connection) == 0);
    CHECK(handshake(client, server, 0) == 0 && ctos_ssh_kex_ready(client, 0, nonce, connection) == 2);
    CHECK(kex_send_kexinit(server) == 0);
    CHECK(handshake(client, server, 0) == 0 && ctos_ssh_kex_ready(client, 0, nonce, connection) == 3);
    CHECK(approvals == 3 && signatures == 3);
    dispose(client, server); sshkey_free(public); sshkey_free(private); cases++;
}
static void negative(int mode, int type, int bits) {
    struct sshkey *private = NULL, *public = NULL, *other = NULL;
    struct ssh *client = NULL, *server = NULL;
    unsigned char wire[CTOS_SSH_ROLE_WIRE_BYTES];
    size_t wire_length = 0;
    CHECK(sshkey_generate(type, bits, &private) == 0 && sshkey_from_private(private, &public) == 0);
    setup("curve25519-sha256", private, public, &client, &server);
    if (mode == 2) {
        CHECK(sshkey_generate(type, bits, &other) == 0);
        bind_role(client, other, 0);
    } else if (mode == 5) {
        unsigned char *hash = NULL;
        size_t length = 0;
        CHECK(sshkey_fingerprint_raw(public, SSH_DIGEST_SHA256, &hash, &length) == 0 && length == 32);
        uint64_t expiry = millis() + 10;
        issued++; nonce[0] = (unsigned char)issued;
        struct ctos_ssh_role_request request = {
            .program = CTOS_SSH_ROLE_CLIENT,
            .owner_pid = getpid(), .owner_uid = getuid(), .hop = 0, .port = 22,
            .deadline = expiry,
            .connection_id = "fixture_expiring_connection",
            .target_identity = "target:user@example.invalid:22",
            .host = "example.invalid", .public_view_id = "fixture_expiring_view",
            .role_nonce = nonce, .connection_binding = connection
        };
        ctos_ssh_role_token token = 0;
        CHECK(ctos_ssh_role_bootstrap_open(&request, &token) == 0
            && ctos_ssh_role_bootstrap_encode(token, wire, sizeof(wire), &wire_length) == 0
            && wire_length > 0);
        for (unsigned int attempt = 0; millis() < expiry && attempt < 20; attempt++) {
            struct timespec pause = {.tv_nsec = 1000000};
            CHECK(nanosleep(&pause, NULL) == 0);
        }
        CHECK(millis() >= expiry);
        CHECK(ctos_ssh_native_launcher_admit(wire, wire_length, token, client, hash) != 0);
        CHECK(ctos_ssh_native_launcher_admit(wire, wire_length, token, client, hash) != 0);
        CHECK(ctos_ssh_role_bootstrap_active(token) == 0);
        explicit_bzero(wire, sizeof(wire));
        explicit_bzero(hash, length); free(hash);
    } else if (mode != 3) bind_role(client, public, 0);
    corrupt_signature = mode == 0; reject_host = mode == 1;
    if (mode == 4) {
        CHECK(handshake(client, server, 1) == 0 && approvals == 1 && signatures == 1);
        CHECK(!client->kex->done && ctos_ssh_kex_ready(client, 0, nonce, connection) == 0);
    } else {
        CHECK(handshake(client, server, 0) != 0);
        CHECK(!client->kex->done && ctos_ssh_kex_ready(client, 0, nonce, connection) == 0);
        if (mode == 0) CHECK(approvals == 1 && signatures == 0);
    }
    corrupt_signature = reject_host = 0;
    dispose(client, server); sshkey_free(other); sshkey_free(public); sshkey_free(private); cases++;
}
static void two_hops(void) {
    struct sshkey *private[2] = {NULL}, *public[2] = {NULL};
    struct ssh *client[2] = {NULL}, *server[2] = {NULL};
    unsigned char nonces[2][32];
    for (unsigned int i = 0; i < 2; i++) {
        CHECK(sshkey_generate(KEY_ED25519, 0, &private[i]) == 0);
        CHECK(sshkey_from_private(private[i], &public[i]) == 0);
        setup("curve25519-sha256", private[i], public[i], &client[i], &server[i]);
        bind_role(client[i], public[i], i); memcpy(nonces[i], nonce, 32);
    }
    uint64_t deadline = millis() + 20000;
    unsigned int completed = 0;
    for (unsigned int attempt = 0; attempt < 20000 && millis() < deadline; attempt++) {
        completed = 0;
        for (unsigned int i = 0; i < 2; i++) {
            CHECK(advance(server[i], client[i], 0) == 0 && advance(client[i], server[i], 0) == 0);
            if (client[i]->kex->done && server[i]->kex->done) completed++;
        }
        if (completed == 2) break;
    }
    CHECK(completed == 2);
    for (unsigned int i = 0; i < 2; i++) {
        CHECK(ctos_ssh_kex_ready(client[i], i, nonces[i], connection) == 1);
        CHECK(ctos_ssh_kex_ready(client[i], 1-i, nonces[1-i], connection) == 0);
    }
    ctos_ssh_role_token first_token = token_for(client[0]);
    CHECK(first_token != 0 && ctos_ssh_native_launcher_close(client[0], first_token) == 0);
    for (unsigned int i = 0; i < sizeof(bindings) / sizeof(bindings[0]); i++)
        if (bindings[i].client == client[0]) { bindings[i].client = NULL; bindings[i].token = 0; }
    CHECK(ctos_ssh_kex_ready(client[0], 0, nonces[0], connection) == 0);
    CHECK(ctos_ssh_kex_ready(client[1], 1, nonces[1], connection) == 1);
    for (unsigned int i = 0; i < 2; i++) {
        dispose(client[i], server[i]); sshkey_free(public[i]); sshkey_free(private[i]);
    }
    cases++;
}
int main(int argc, char **argv) {
    if (argc != 2 || strcmp(argv[1], "--synthetic-kex") != 0) return 64;
    log_init("ctos-kex", SYSLOG_LEVEL_QUIET, SYSLOG_FACILITY_USER, 1);
    CHECK(OPENSSL_init_crypto(OPENSSL_INIT_NO_LOAD_CONFIG, NULL) == 1);
    const char *algorithms[] = {"curve25519-sha256", "ecdh-sha2-nistp256",
        "diffie-hellman-group14-sha256", "diffie-hellman-group-exchange-sha256",
        "sntrup761x25519-sha512", "mlkem768x25519-sha256"};
    const int types[] = {KEY_ED25519, KEY_ECDSA, KEY_RSA};
    const int bits[] = {0, 256, 2048};
    for (unsigned int i = 0; i < 3; i++) {
        for (unsigned int a = 0; a < 6; a++) positive(algorithms[a], types[i], bits[i]);
        negative(0, types[i], bits[i]);
    }
    for (int mode = 1; mode <= 5; mode++) negative(mode, KEY_ED25519, 0);
    two_hops();
    CHECK(cases == 27);
    printf("SSH_KEX phase=handshake state=pass errno=0 value=27\n");
    printf("SSH_KEX phase=cleanup state=pass errno=0 value=1\n");
    printf("SSH_KEX phase=gate state=pass errno=0 value=27\n");
    return 0;
}

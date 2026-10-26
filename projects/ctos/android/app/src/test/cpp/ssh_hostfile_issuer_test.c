#define _GNU_SOURCE

#include "ssh_hostfile_issuer.h"

#include <assert.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "ssh_role_bootstrap.h"
#include "hostfile.h"
#include "ssh2.h"
#include "sshkey.h"

static unsigned int cases;

static uint64_t deadline_after(unsigned int milliseconds) {
    struct timespec now;
    assert(clock_gettime(CLOCK_BOOTTIME, &now) == 0);
    return (uint64_t)now.tv_sec * 1000U + (uint64_t)now.tv_nsec / 1000000U
        + milliseconds;
}

struct wire {
    unsigned char bytes[8192];
    size_t length;
};

static void put(struct wire *wire, const void *bytes, size_t length) {
    assert(length <= sizeof(wire->bytes) - wire->length);
    memcpy(wire->bytes + wire->length, bytes, length);
    wire->length += length;
}

static void put_u32(struct wire *wire, uint32_t value) {
    unsigned char bytes[] = {
        (unsigned char)(value >> 24), (unsigned char)(value >> 16),
        (unsigned char)(value >> 8), (unsigned char)value
    };
    put(wire, bytes, sizeof(bytes));
}

static void put_u64(struct wire *wire, uint64_t value) {
    put_u32(wire, (uint32_t)(value >> 32));
    put_u32(wire, (uint32_t)value);
}

static void put_text(struct wire *wire, const char *value) {
    size_t length = strlen(value);
    assert(length <= UINT32_MAX);
    put_u32(wire, (uint32_t)length);
    put(wire, value, length);
}

static void put_object(struct wire *wire, const unsigned char *body, size_t length) {
    unsigned char type = CTOS_SSH_KNOWN_HOSTS;
    put(wire, &type, sizeof(type));
    put_text(wire, "trust");
    put_text(wire, "ssh/known_hosts");
    put_text(wire, "issuer public trust");
    put_u32(wire, (uint32_t)length);
    put_u32(wire, 0);
    put_u32(wire, (uint32_t)length);
    put(wire, body, length);
}

static void put_root_object(struct wire *wire, const char *root_path) {
    unsigned char type = CTOS_SSH_CONFIG;
    put(wire, &type, sizeof(type));
    put_text(wire, "root");
    put_text(wire, root_path);
    put_text(wire, "issuer public config");
    put_u32(wire, 0);
    put_u32(wire, 1);
    put_text(wire, "ssh/known_hosts");
    put_u32(wire, 0);
}

static void make_view_wire(struct wire *wire, const unsigned char *body, size_t length,
    uint64_t deadline, const char *root_path) {
    unsigned char uuid[16] = {1};
    unsigned char binding[32] = {2};
    memset(wire, 0, sizeof(*wire));
    put_u32(wire, 0x43535056U);
    put_u32(wire, 1);
    put(wire, uuid, sizeof(uuid));
    put_u64(wire, 73);
    put_u64(wire, 7);
    put_u64(wire, deadline);
    put_text(wire, "issuer-request");
    put_text(wire, "issuer-test");
    put_text(wire, root_path);
    put(wire, binding, sizeof(binding));
    put_u32(wire, 2);
    put_root_object(wire, root_path);
    put_object(wire, body, length);
}

static struct ctos_ssh_view *load_view(const unsigned char *body, size_t length,
    uint64_t deadline) {
    struct wire wire;
    struct ctos_ssh_view *view;
    make_view_wire(&wire, body, length, deadline, "ssh/config");
    view = calloc(1, sizeof(*view));
    assert(view != NULL);
    assert(ctos_ssh_view_allocate(view, wire.length) == 0);
    memcpy(view->mapping, wire.bytes, wire.length);
    assert(ctos_ssh_view_validate(view) == 0);
    return view;
}

static struct ctos_ssh_view *load_view_with_root(const unsigned char *body,
    size_t length, uint64_t deadline, const char *root_path) {
    struct wire wire;
    struct ctos_ssh_view *view;
    make_view_wire(&wire, body, length, deadline, root_path);
    view = calloc(1, sizeof(*view));
    assert(view != NULL);
    assert(ctos_ssh_view_allocate(view, wire.length) == 0);
    memcpy(view->mapping, wire.bytes, wire.length);
    assert(ctos_ssh_view_validate(view) == 0);
    return view;
}

static void destroy_view(struct ctos_ssh_view *view) {
    assert(view != NULL);
    assert(ctos_ssh_view_destroy(view) == 0);
    free(view);
}

static size_t formatted_endpoint_key(const struct sshkey *key, const char *endpoint,
    unsigned char *output, size_t capacity) {
    FILE *stream = fmemopen(output, capacity, "w+");
    long length;
    assert(stream != NULL);
    assert(ctos_hostfile_write_entry(stream, endpoint, NULL, key, 0) == 1);
    assert(fflush(stream) == 0);
    length = ftell(stream);
    assert(length > 0 && (unsigned long)length < capacity);
    assert(fclose(stream) == 0);
    return (size_t)length;
}

static size_t formatted_key(const struct sshkey *key, unsigned char *output,
    size_t capacity) {
    return formatted_endpoint_key(key, "example.invalid", output, capacity);
}

struct handoff_fixture {
    ctos_ssh_callback_token callback;
    void *environment;
    unsigned int calls;
    unsigned int abort_calls;
    unsigned char body[CTOS_SSH_CALLBACK_MAX_FILE_BYTES];
    size_t body_length;
    unsigned char base_digest[32];
    unsigned char replacement_digest[32];
    int fail_candidate;
};

static int validate_environment(void *context, void *environment) {
    const struct handoff_fixture *fixture = context;
    return fixture != NULL && environment == fixture->environment ? 0 : -1;
}

static int host_key(void *context, void *environment, int classification,
    const char *key_type, const char *fingerprint,
    const unsigned char base_digest[32], const unsigned char replacement_digest[32]) {
    (void)context;
    (void)environment;
    (void)classification;
    (void)key_type;
    (void)fingerprint;
    (void)base_digest;
    (void)replacement_digest;
    return 0;
}

static int candidate(void *context, void *environment,
    const struct ctos_ssh_known_hosts_candidate *candidate) {
    struct handoff_fixture *fixture = context;
    assert(fixture != NULL && environment == fixture->environment);
    assert(candidate != NULL);
    assert(candidate->evidence.classification == CTOS_SSH_CALLBACK_HOST_NEW);
    assert(strcmp(candidate->candidate_id, "candidate-issuer") == 0);
    if (fixture->fail_candidate) return -1;
    assert(candidate->replacement_body_length <= sizeof(fixture->body));
    memcpy(fixture->body, candidate->replacement_body,
        candidate->replacement_body_length);
    fixture->body_length = candidate->replacement_body_length;
    memcpy(fixture->base_digest, candidate->evidence.base_digest,
        sizeof(fixture->base_digest));
    memcpy(fixture->replacement_digest, candidate->evidence.replacement_digest,
        sizeof(fixture->replacement_digest));
    fixture->calls++;
    return 0;
}

static int candidate_abort(void *context, void *environment) {
    struct handoff_fixture *fixture = context;
    assert(fixture != NULL && environment == fixture->environment);
    fixture->abort_calls++;
    return 0;
}

static int kex_complete(void *context, void *environment, uint64_t cycle,
    const unsigned char session_digest[32]) {
    (void)context;
    (void)environment;
    (void)cycle;
    (void)session_digest;
    return 0;
}

static int credential(void *context, void *environment, int kind) {
    (void)context;
    (void)environment;
    (void)kind;
    return 0;
}

static int release(void *context, void *environment) {
    assert(context != NULL && environment == context);
    return 0;
}

static ctos_ssh_role_token open_endpoint_role(uint64_t deadline, const char *host,
    unsigned int port) {
    unsigned char nonce[32] = {3};
    unsigned char binding[32] = {4};
    struct ctos_ssh_role_request request = {
        .program = CTOS_SSH_ROLE_CLIENT,
        .owner_pid = getpid(),
        .owner_uid = getuid(),
        .hop = 0,
        .port = port,
        .deadline = deadline,
        .connection_id = "issuer-test",
        .target_identity = "target:user@example.invalid:22",
        .host = host,
        .public_view_id = "view-issuer",
        .role_nonce = nonce,
        .connection_binding = binding
    };
    ctos_ssh_role_token role = 0;
    assert(ctos_ssh_role_bootstrap_open(&request, &role) == 0);
    return role;
}

static ctos_ssh_role_token open_role(uint64_t deadline) {
    return open_endpoint_role(deadline, "example.invalid", 22U);
}

static struct sshkey *make_host_certificate(struct sshkey **ca_out) {
    struct sshkey *certificate = NULL;
    struct sshkey *ca = NULL;
    assert(ca_out != NULL);
    assert(sshkey_generate(KEY_ED25519, 0, &certificate) == 0);
    assert(sshkey_generate(KEY_ED25519, 0, &ca) == 0);
    assert(sshkey_to_certified(certificate) == 0);
    certificate->cert->type = SSH2_CERT_TYPE_HOST;
    certificate->cert->key_id = strdup("issuer-certificate");
    assert(certificate->cert->key_id != NULL);
    certificate->cert->valid_after = 0;
    certificate->cert->valid_before = UINT64_MAX;
    assert(sshkey_from_private(ca, &certificate->cert->signature_key) == 0);
    assert(sshkey_certify(certificate, ca, NULL, NULL, NULL) == 0);
    *ca_out = ca;
    return certificate;
}

static ctos_ssh_callback_token open_callback(struct handoff_fixture *fixture,
    ctos_ssh_role_token role, uint64_t deadline) {
    struct ctos_ssh_callback_target target = {
        .context = fixture,
        .validate_environment = validate_environment,
        .host_key = host_key,
        .known_hosts_candidate = candidate,
        .known_hosts_candidate_abort = candidate_abort,
        .kex_complete = kex_complete,
        .credential = credential,
        .release = release
    };
    ctos_ssh_callback_token callback = 0;
    fixture->environment = fixture;
    assert(ctos_ssh_callback_open(&target, role, (int)getuid(), deadline,
        fixture->environment, &callback) == 0);
    fixture->callback = callback;
    return callback;
}

static void issue_no_handoff(ctos_ssh_role_token role, uint64_t deadline,
    const unsigned char *body, size_t body_length, const struct sshkey *key,
    int expected) {
    struct ctos_ssh_view *view = load_view(body, body_length, deadline);
    struct ctos_ssh_hostfile_issue issue;
    assert(ctos_ssh_hostfile_issue(view, role, "ssh/known_hosts", "example.invalid", key,
        "candidate-issuer", NULL, NULL, 0, 0, NULL, &issue) == 0);
    assert(issue.classification == expected);
    assert(issue.key_type[0] != '\0' && issue.fingerprint[0] != '\0');
    assert(issue.replacement_body_length == 0 && issue.handed_off == 0);
    assert(memcmp(issue.base_digest, issue.replacement_digest,
        sizeof(issue.base_digest)) == 0);
    destroy_view(view);
    cases++;
}

static size_t issue_endpoint_case(const char *host, unsigned int port,
    const unsigned char *body, size_t body_length, const struct sshkey *key,
    int expected, unsigned char *replacement, size_t capacity) {
    uint64_t deadline = deadline_after(5000U);
    ctos_ssh_role_token role = open_endpoint_role(deadline, host, port);
    struct ctos_ssh_view *view = load_view(body, body_length, deadline);
    struct ctos_ssh_hostfile_issue issue;
    FILE *stream = expected == CTOS_SSH_CALLBACK_HOST_NEW
        ? fmemopen(replacement, capacity, "w+") : NULL;
    assert(expected != CTOS_SSH_CALLBACK_HOST_NEW || stream != NULL);
    assert(ctos_ssh_hostfile_issue(view, role, "ssh/known_hosts", host, key,
        "candidate-issuer", stream, replacement, capacity, 0, NULL, &issue) == 0);
    assert(issue.classification == expected && issue.handed_off == 0);
    struct ctos_ssh_role_snapshot snapshot;
    assert(ctos_ssh_role_bootstrap_snapshot(role, &snapshot) == 0);
    assert(strcmp(snapshot.host, host) == 0 && snapshot.port == port);
    size_t length = issue.replacement_body_length;
    if (expected == CTOS_SSH_CALLBACK_HOST_NEW) {
        char endpoint[300];
        int prefix = port == 22U ? snprintf(endpoint, sizeof(endpoint), "%s ", host)
            : snprintf(endpoint, sizeof(endpoint), "[%s]:%u ", host, port);
        assert(prefix > 0 && (size_t)prefix < sizeof(endpoint));
        for (int index = 0; index < prefix; index++) {
            if (endpoint[index] >= 'A' && endpoint[index] <= 'Z')
                endpoint[index] = (char)(endpoint[index] - 'A' + 'a');
        }
        assert(length > body_length + (size_t)prefix);
        assert(body_length == 0 || memcmp(replacement, body, body_length) == 0);
        assert(memcmp(replacement + body_length, endpoint, (size_t)prefix) == 0);
    } else assert(length == 0);
    if (stream != NULL) assert(fclose(stream) == 0);
    destroy_view(view);
    assert(ctos_ssh_role_bootstrap_close(role) == 0);
    cases++;
    return length;
}

static void test_endpoint_isolation(const struct sshkey *known, const struct sshkey *changed) {
    const char *hosts[] = {"example.invalid", "2001:db8::1"};
    unsigned char raw[4096], qualified[4096];
    unsigned char replacement[CTOS_SSH_CALLBACK_MAX_FILE_BYTES + 1U];
    for (size_t index = 0; index < sizeof(hosts) / sizeof(hosts[0]); index++) {
        char endpoint[300];
        snprintf(endpoint, sizeof(endpoint), "[%s]:2222", hosts[index]);
        size_t raw_length = formatted_endpoint_key(known, hosts[index], raw, sizeof(raw));
        size_t qualified_length = formatted_endpoint_key(known, endpoint, qualified, sizeof(qualified));
        issue_endpoint_case(hosts[index], 22U, raw, raw_length, known,
            CTOS_SSH_CALLBACK_HOST_KNOWN, replacement, sizeof(replacement));
        issue_endpoint_case(hosts[index], 2222U, qualified, qualified_length, known,
            CTOS_SSH_CALLBACK_HOST_KNOWN, replacement, sizeof(replacement));
        issue_endpoint_case(hosts[index], 2222U, qualified, qualified_length, changed,
            CTOS_SSH_CALLBACK_HOST_CHANGED, replacement, sizeof(replacement));
        size_t length = issue_endpoint_case(hosts[index], 2223U, qualified, qualified_length, changed,
            CTOS_SSH_CALLBACK_HOST_NEW, replacement, sizeof(replacement));
        issue_endpoint_case(hosts[index], 2223U, replacement, length, changed,
            CTOS_SSH_CALLBACK_HOST_KNOWN, NULL, 0);
        issue_endpoint_case(hosts[index], 2222U, replacement, length, known,
            CTOS_SSH_CALLBACK_HOST_KNOWN, NULL, 0);
        issue_endpoint_case(hosts[index], 2222U, raw, raw_length, known,
            CTOS_SSH_CALLBACK_HOST_NEW, replacement, sizeof(replacement));
        issue_endpoint_case(hosts[index], 22U, qualified, qualified_length, known,
            CTOS_SSH_CALLBACK_HOST_NEW, replacement, sizeof(replacement));
    }
}

static void test_hashed_endpoint_isolation(const struct sshkey *known, const struct sshkey *changed) {
    const char *host = "example.invalid";
    const char *endpoints[] = {"[example.invalid]:2222", "example.invalid"};
    unsigned char plain[4096], line[4096];
    unsigned char replacement[CTOS_SSH_CALLBACK_MAX_FILE_BYTES + 1U];
    size_t plain_length = formatted_key(known, plain, sizeof(plain));
    unsigned char *key_text = memchr(plain, ' ', plain_length);
    assert(key_text != NULL);
    size_t key_length = plain_length - (size_t)(key_text - plain);
    for (size_t index = 0; index < sizeof(endpoints) / sizeof(endpoints[0]); index++) {
        char *hashed = host_hash(endpoints[index], NULL, 0);
        assert(hashed != NULL && strncmp(hashed, "|1|", 3U) == 0);
        size_t hashed_length = strlen(hashed);
        assert(hashed_length + key_length <= sizeof(line));
        /* Preserve the hash's case-sensitive base64 bytes; plain host writers lowercase. */
        memcpy(line, hashed, hashed_length);
        memcpy(line + hashed_length, key_text, key_length);
        free(hashed);
        unsigned int matching_port = index == 0 ? 2222U : 22U;
        unsigned int other_port = index == 0 ? 2223U : 2222U;
        issue_endpoint_case(host, matching_port, line, hashed_length + key_length, known,
            CTOS_SSH_CALLBACK_HOST_KNOWN, replacement, sizeof(replacement));
        issue_endpoint_case(host, other_port, line, hashed_length + key_length, known,
            CTOS_SSH_CALLBACK_HOST_NEW, replacement, sizeof(replacement));
        if (index == 0) {
            /* Raw role authority keeps its spelling; standard known_hosts tokens do not. */
            issue_endpoint_case("EXAMPLE.invalid", 2222U, line, hashed_length + key_length, known,
                CTOS_SSH_CALLBACK_HOST_KNOWN, replacement, sizeof(replacement));
            issue_endpoint_case("EXAMPLE.invalid", 2222U, line, hashed_length + key_length, changed,
                CTOS_SSH_CALLBACK_HOST_CHANGED, replacement, sizeof(replacement));
            issue_endpoint_case("EXAMPLE.invalid", 2223U, line, hashed_length + key_length, known,
                CTOS_SSH_CALLBACK_HOST_NEW, replacement, sizeof(replacement));
        }
    }
}

int main(void) {
    unsigned char key_line[4096];
    unsigned char revoked_line[4096];
    unsigned char ca_line[4096];
    unsigned char revoked_ca_line[4096];
    unsigned char replacement[CTOS_SSH_CALLBACK_MAX_FILE_BYTES + 1U];
    struct sshkey *known = NULL;
    struct sshkey *changed = NULL;
    struct sshkey *certificate = NULL;
    struct sshkey *ca = NULL;
    struct handoff_fixture fixture = {0};
    uint64_t deadline = deadline_after(5000);
    size_t key_line_length;
    size_t revoked_length;
    size_t ca_line_length;
    size_t revoked_ca_length;
    unsigned int handoff_calls = 0;
    unsigned int rollback_calls = 0;
    ctos_ssh_role_token role;
    ctos_ssh_callback_token callback;

    assert(sshkey_generate(KEY_ED25519, 0, &known) == 0);
    assert(sshkey_generate(KEY_ED25519, 0, &changed) == 0);
    certificate = make_host_certificate(&ca);
    key_line_length = formatted_key(known, key_line, sizeof(key_line));
    memcpy(revoked_line, "@revoked ", strlen("@revoked "));
    memcpy(revoked_line + strlen("@revoked "), key_line, key_line_length);
    revoked_length = strlen("@revoked ") + key_line_length;
    ca_line_length = formatted_key(ca, ca_line, sizeof(ca_line));
    memcpy(revoked_ca_line, "@revoked ", strlen("@revoked "));
    memcpy(revoked_ca_line + strlen("@revoked "), ca_line, ca_line_length);
    revoked_ca_length = strlen("@revoked ") + ca_line_length;

    role = open_role(deadline);
    issue_no_handoff(role, deadline, key_line, key_line_length, known,
        CTOS_SSH_CALLBACK_HOST_KNOWN);
    issue_no_handoff(role, deadline, key_line, key_line_length, changed,
        CTOS_SSH_CALLBACK_HOST_CHANGED);
    issue_no_handoff(role, deadline, revoked_line, revoked_length, known,
        CTOS_SSH_CALLBACK_HOST_REVOKED);
    issue_no_handoff(role, deadline, NULL, 0, certificate,
        CTOS_SSH_CALLBACK_HOST_CERTIFICATE);
    issue_no_handoff(role, deadline, revoked_ca_line, revoked_ca_length, certificate,
        CTOS_SSH_CALLBACK_HOST_REVOKED);

    callback = open_callback(&fixture, role, deadline);
    struct ctos_ssh_view *empty_view = load_view(NULL, 0, deadline);
    FILE *stream = fmemopen(replacement, sizeof(replacement), "w+");
    struct ctos_ssh_hostfile_issue issue;
    assert(stream != NULL);
    assert(ctos_ssh_hostfile_issue(empty_view, role, "ssh/known_hosts",
        "example.invalid", changed, "candidate-issuer", stream, replacement,
        sizeof(replacement), callback, fixture.environment, &issue) == 0);
    assert(issue.classification == CTOS_SSH_CALLBACK_HOST_NEW);
    assert(issue.replacement_body_length == fixture.body_length);
    assert(fixture.calls == 1 && fixture.body_length > 0);
    assert(memcmp(fixture.base_digest, issue.base_digest, sizeof(issue.base_digest)) == 0);
    assert(memcmp(fixture.replacement_digest, issue.replacement_digest,
        sizeof(issue.replacement_digest)) == 0);
    for (size_t index = 0; index < issue.replacement_body_length; index++)
        assert(replacement[index] == 0);
    assert(fclose(stream) == 0);
    destroy_view(empty_view);
    handoff_calls = fixture.calls;
    cases++;

    struct ctos_ssh_view *bad_view = load_view(key_line, key_line_length, deadline);
    errno = EIO;
    assert(ctos_ssh_hostfile_issue(bad_view, role, "ssh/known_hosts", NULL, known,
        "candidate-issuer", NULL, NULL, 0, 0, NULL, &issue) != 0 && errno == EINVAL);
    cases++;
    errno = 0;
    assert(ctos_ssh_hostfile_issue(bad_view, role, "/etc/passwd", "example.invalid", known,
        "candidate-issuer", NULL, NULL, 0, 0, NULL, &issue) != 0 && errno == EACCES);
    destroy_view(bad_view);
    cases++;

    struct ctos_ssh_view *bad_root = load_view_with_root(key_line, key_line_length,
        deadline, "ssh/other");
    errno = 0;
    assert(ctos_ssh_hostfile_issue(bad_root, role, "ssh/known_hosts",
        "example.invalid", known, "candidate-issuer", NULL, NULL, 0, 0, NULL,
        &issue) != 0 && errno == EACCES);
    destroy_view(bad_root);
    cases++;

    assert(ctos_ssh_callback_close(callback, fixture.environment) == 0);
    assert(ctos_ssh_role_bootstrap_close(role) == 0);

    uint64_t failure_deadline = deadline_after(5000);
    fixture = (struct handoff_fixture){0};
    fixture.fail_candidate = 1;
    role = open_role(failure_deadline);
    callback = open_callback(&fixture, role, failure_deadline);
    empty_view = load_view(NULL, 0, failure_deadline);
    memset(replacement, 0xa5, sizeof(replacement));
    stream = fmemopen(replacement, sizeof(replacement), "w+");
    assert(stream != NULL);
    errno = 0;
    assert(ctos_ssh_hostfile_issue(empty_view, role, "ssh/known_hosts",
        "example.invalid", changed, "candidate-issuer", stream, replacement,
        sizeof(replacement), callback, fixture.environment, &issue) != 0);
    assert(fixture.calls == 0 && fixture.abort_calls == 1);
    for (size_t index = 0; index < sizeof(replacement); index++)
        assert(replacement[index] == 0);
    assert(fclose(stream) == 0);
    destroy_view(empty_view);
    rollback_calls = fixture.abort_calls;
    cases++;
    assert(ctos_ssh_callback_close(callback, fixture.environment) == 0);
    assert(ctos_ssh_role_bootstrap_close(role) == 0);
    test_endpoint_isolation(known, changed);
    test_hashed_endpoint_isolation(known, changed);
    sshkey_free(known);
    sshkey_free(changed);
    sshkey_free(certificate);
    sshkey_free(ca);
    printf("SSH_HOSTFILE_ISSUER cases=%u handoff=%u abort=%u gate=pass\n",
        cases, handoff_calls, rollback_calls);
    return 0;
}

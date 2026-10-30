#define _GNU_SOURCE

#include "ssh_native_client.h"

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#include "digest.h"
#include "hostfile.h"
#include "kex.h"
#include "myproposal.h"
#include "packet.h"
#include "ssh_hostfile_issuer.h"
#include "ssh_kex_guard.h"
#include "ssh_role_bootstrap.h"
#include "ssh2.h"
#include "ssh_api.h"
#include "sshbuf.h"
#include "ssherr.h"
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

static unsigned int checks;

#define TEST_STEP_BUDGET 3U
/* Complete QEMU handshakes before deliberately expiring the original deadline. */
#define TEST_EXPIRY_PREPARATION_MS 5000U
#define TEST_EXPIRY_WAIT_ATTEMPTS 6000U

#define CHECK(condition) do { \
    checks++; \
    if (!(condition)) { \
        fprintf(stderr, "SSH_NATIVE_CLIENT failure line=%u errno=%d\\n", \
            (unsigned int)__LINE__, errno); \
        abort(); \
    } \
} while (0)

static uint64_t deadline_after(unsigned int milliseconds) {
    struct timespec now;

    CHECK(clock_gettime(CLOCK_BOOTTIME, &now) == 0);
    return (uint64_t)now.tv_sec * 1000U
        + (uint64_t)now.tv_nsec / 1000000U + milliseconds;
}

static uint32_t read_u32(const unsigned char *bytes) {
    CHECK(bytes != NULL);
    return ((uint32_t)bytes[0] << 24) | ((uint32_t)bytes[1] << 16)
        | ((uint32_t)bytes[2] << 8) | (uint32_t)bytes[3];
}

static void wait_until_deadline_attempts(uint64_t deadline, unsigned int attempts) {
    for (unsigned int attempt = 0; attempt < attempts; attempt++) {
        struct timespec now;
        uint64_t current;
        struct timespec pause = {.tv_sec = 0, .tv_nsec = 1000000L};

        CHECK(clock_gettime(CLOCK_BOOTTIME, &now) == 0);
        current = (uint64_t)now.tv_sec * 1000U
            + (uint64_t)now.tv_nsec / 1000000U;
        if (current >= deadline) return;
        if (nanosleep(&pause, NULL) != 0) CHECK(errno == EINTR);
    }
    {
        struct timespec now;

        CHECK(clock_gettime(CLOCK_BOOTTIME, &now) == 0);
        CHECK((uint64_t)now.tv_sec * 1000U
            + (uint64_t)now.tv_nsec / 1000000U >= deadline);
    }
}

static void wait_until_deadline(uint64_t deadline) {
    wait_until_deadline_attempts(deadline, 1000U);
}

struct wire {
    unsigned char bytes[16384];
    size_t length;
};

static void wire_put(struct wire *wire, const void *bytes, size_t length) {
    CHECK(wire != NULL && length <= sizeof(wire->bytes) - wire->length);
    if (length != 0) memcpy(wire->bytes + wire->length, bytes, length);
    wire->length += length;
}

static void wire_u32(struct wire *wire, uint32_t value) {
    unsigned char bytes[] = {
        (unsigned char)(value >> 24), (unsigned char)(value >> 16),
        (unsigned char)(value >> 8), (unsigned char)value
    };
    wire_put(wire, bytes, sizeof(bytes));
}

static void wire_u64(struct wire *wire, uint64_t value) {
    wire_u32(wire, (uint32_t)(value >> 32));
    wire_u32(wire, (uint32_t)value);
}

static void wire_text(struct wire *wire, const char *value) {
    size_t length = strlen(value);

    CHECK(length <= UINT32_MAX);
    wire_u32(wire, (uint32_t)length);
    wire_put(wire, value, length);
}

static void wire_root_paths(struct wire *wire, const char *root_path,
    const char *known_hosts_path) {
    unsigned char type = CTOS_SSH_CONFIG;

    wire_put(wire, &type, sizeof(type));
    wire_text(wire, "root");
    wire_text(wire, root_path);
    wire_text(wire, "native client managed config");
    wire_u32(wire, 0);
    wire_u32(wire, 1);
    wire_text(wire, known_hosts_path);
    wire_u32(wire, 0);
}

static void wire_known_hosts_path(struct wire *wire, const unsigned char *body,
    size_t length, const char *known_hosts_path) {
    unsigned char type = CTOS_SSH_KNOWN_HOSTS;

    wire_put(wire, &type, sizeof(type));
    wire_text(wire, "trust");
    wire_text(wire, known_hosts_path);
    wire_text(wire, "native client host evidence");
    CHECK(length <= UINT32_MAX);
    wire_u32(wire, (uint32_t)length);
    wire_u32(wire, 0);
    wire_u32(wire, (uint32_t)length);
    wire_put(wire, body, length);
}

static struct ctos_ssh_view *load_view_paths(const unsigned char *body, size_t length,
    uint64_t deadline, const char *root_path, const char *known_hosts_path) {
    struct wire wire = {0};
    unsigned char uuid[16] = {9};
    unsigned char binding[32] = {8};
    struct ctos_ssh_view *view;

    wire_u32(&wire, 0x43535056U);
    wire_u32(&wire, 1);
    wire_put(&wire, uuid, sizeof(uuid));
    wire_u64(&wire, 91);
    wire_u64(&wire, 11);
    wire_u64(&wire, deadline);
    wire_text(&wire, "native-client-request");
    wire_text(&wire, "native-client-connection");
    wire_text(&wire, root_path);
    wire_put(&wire, binding, sizeof(binding));
    wire_u32(&wire, 2);
    wire_root_paths(&wire, root_path, known_hosts_path);
    wire_known_hosts_path(&wire, body, length, known_hosts_path);

    view = calloc(1, sizeof(*view));
    CHECK(view != NULL);
    CHECK(ctos_ssh_view_allocate(view, wire.length) == 0);
    memcpy(view->mapping, wire.bytes, wire.length);
    CHECK(ctos_ssh_view_validate(view) == 0);
    return view;
}

static struct ctos_ssh_view *load_view(const unsigned char *body, size_t length,
    uint64_t deadline) {
    return load_view_paths(body, length, deadline, "ssh/config", "ssh/known_hosts");
}

static void destroy_view(struct ctos_ssh_view *view) {
    CHECK(view != NULL);
    CHECK(ctos_ssh_view_destroy(view) == 0);
    free(view);
}

static struct ctos_ssh_role_request role_request(uint64_t deadline) {
    static const unsigned char nonce[32] = {3};
    static const unsigned char connection[32] = {4};

    return (struct ctos_ssh_role_request){
        .program = CTOS_SSH_ROLE_CLIENT,
        .owner_pid = getpid(),
        .owner_uid = getuid(),
        .hop = 0,
        .port = 22,
        .deadline = deadline,
        .connection_id = "native-client-connection",
        .target_identity = "target:user@example.invalid:22",
        .host = "example.invalid",
        .public_view_id = "native-client-view",
        .role_nonce = nonce,
        .connection_binding = connection
    };
}

struct callback_fixture {
    void *environment;
    unsigned int host_evidence_calls;
    unsigned int candidate_calls;
    unsigned int candidate_abort_calls;
    unsigned int callback_event_order;
    unsigned int candidate_event_order;
    unsigned int host_evidence_event_order;
    unsigned int release_calls;
    unsigned int release_failures_remaining;
    unsigned int credential_calls;
    unsigned int credential_bytes_calls;
    unsigned int selected_calls;
    int selected_body_override;
    const unsigned char *selected_body;
    size_t selected_body_length;
    int selected_kind;
    int selected_action;
    int omit_selected_target;
    unsigned char expected_selection_receipt[32];
    struct ctos_ssh_view *selected_view;
    ctos_ssh_callback_token selected_callback;
    ctos_ssh_role_token selected_role;
    uint64_t selected_deadline;
    unsigned char *caller_output;
    size_t caller_capacity;
    int fail_host_evidence;
    int host_action;
    ctos_ssh_role_token expected_role;
    unsigned int expected_hop;
    unsigned int expected_port;
    ctos_ssh_role_token last_host_role;
    unsigned int last_host_hop;
    unsigned int last_host_port;
    int last_host_classification;
    int last_host_outcome;
    int last_host_cas_status;
    unsigned int kex_complete_calls;
    unsigned int kex_event_order;
    uint64_t last_kex_cycle;
    unsigned char last_kex_digest[32];
    char expected_key_type[CTOS_SSH_CALLBACK_HOST_KEY_TYPE_BYTES];
    char expected_fingerprint[CTOS_SSH_CALLBACK_HOST_FINGERPRINT_BYTES];
    char last_key_type[CTOS_SSH_CALLBACK_HOST_KEY_TYPE_BYTES];
    char last_fingerprint[CTOS_SSH_CALLBACK_HOST_FINGERPRINT_BYTES];
    char last_candidate_id[CTOS_SSH_CALLBACK_CANDIDATE_ID_BYTES];
    ctos_ssh_role_token last_candidate_role;
    unsigned int last_candidate_hop;
    unsigned int last_candidate_port;
    int last_candidate_classification;
    char last_candidate_key_type[CTOS_SSH_CALLBACK_HOST_KEY_TYPE_BYTES];
    char last_candidate_fingerprint[CTOS_SSH_CALLBACK_HOST_FINGERPRINT_BYTES];
    unsigned char candidate_base_digest[32];
    unsigned char candidate_replacement_digest[32];
    unsigned char last_host_base_digest[32];
    unsigned char last_host_replacement_digest[32];
    unsigned char *candidate_body;
    size_t candidate_body_length;
    int release_fd;
    int release_saw_fd_open;
};

static int validate_environment(void *context, void *environment) {
    const struct callback_fixture *fixture = context;
    return fixture != NULL && fixture->environment == environment ? 0 : -1;
}

static int host_evidence(void *context, void *environment,
    const struct ctos_ssh_host_evidence *evidence,
    struct ctos_ssh_host_result *result) {
    struct callback_fixture *fixture = context;

    CHECK(fixture != NULL && fixture->environment == environment);
    CHECK(evidence != NULL && result != NULL);
    CHECK(evidence->classification == CTOS_SSH_CALLBACK_HOST_KNOWN
        || evidence->classification == CTOS_SSH_CALLBACK_HOST_NEW);
    CHECK(evidence->role == fixture->expected_role);
    CHECK(evidence->hop == fixture->expected_hop);
    CHECK(evidence->port == fixture->expected_port);
    CHECK(strcmp(evidence->connection_id, "native-client-connection") == 0);
    CHECK(strcmp(evidence->target_identity,
        "target:user@example.invalid:22") == 0);
    CHECK(strcmp(evidence->host, "example.invalid") == 0);
    CHECK(strcmp(evidence->key_type, fixture->expected_key_type) == 0);
    CHECK(strcmp(evidence->fingerprint, fixture->expected_fingerprint) == 0);
    if (evidence->classification == CTOS_SSH_CALLBACK_HOST_NEW) {
        CHECK(fixture->candidate_calls == 1);
        CHECK(fixture->candidate_event_order == fixture->callback_event_order);
    }
    fixture->host_evidence_calls++;
    fixture->host_evidence_event_order = ++fixture->callback_event_order;
    fixture->last_host_role = evidence->role;
    fixture->last_host_hop = evidence->hop;
    fixture->last_host_port = evidence->port;
    fixture->last_host_classification = evidence->classification;
    strcpy(fixture->last_key_type, evidence->key_type);
    strcpy(fixture->last_fingerprint, evidence->fingerprint);
    memcpy(fixture->last_host_base_digest, evidence->base_digest,
        sizeof(fixture->last_host_base_digest));
    memcpy(fixture->last_host_replacement_digest,
        evidence->replacement_digest,
        sizeof(fixture->last_host_replacement_digest));
    if (fixture->fail_host_evidence) return EACCES;
    memset(result, 0, sizeof(*result));
    result->role = evidence->role;
    result->hop = evidence->hop;
    result->port = evidence->port;
    result->classification = evidence->classification;
    result->outcome = CTOS_SSH_CALLBACK_HOST_RESULT_APPROVED;
    result->cas_status = evidence->classification == CTOS_SSH_CALLBACK_HOST_NEW
        ? CTOS_SSH_CALLBACK_HOST_CAS_COMMITTED
        : CTOS_SSH_CALLBACK_HOST_CAS_NOT_REQUIRED;
    strcpy(result->connection_id, evidence->connection_id);
    strcpy(result->target_identity, evidence->target_identity);
    strcpy(result->host, evidence->host);
    strcpy(result->key_type, evidence->key_type);
    strcpy(result->fingerprint, evidence->fingerprint);
    if (evidence->classification == CTOS_SSH_CALLBACK_HOST_NEW) {
        memset(result->proposal_binding, 'a',
            sizeof(result->proposal_binding) - 1U);
        result->proposal_binding[sizeof(result->proposal_binding) - 1U] = '\0';
    }
    memcpy(result->base_digest, evidence->base_digest,
        sizeof(result->base_digest));
    memcpy(result->replacement_digest, evidence->replacement_digest,
        sizeof(result->replacement_digest));
    fixture->last_host_outcome = result->outcome;
    fixture->last_host_cas_status = result->cas_status;
    if (fixture->host_action == 1) result->replacement_digest[0] ^= 1U;
    if (fixture->host_action == 2) CHECK(ctos_ssh_callback_cancel(fixture->selected_callback) == 0);
    if (fixture->host_action == 3) fixture->selected_view->valid = 0;
    if (fixture->host_action == 4) fixture->selected_view->deadline = deadline_after(0);
    if (fixture->host_action == 5)
        wait_until_deadline_attempts(fixture->selected_deadline, TEST_EXPIRY_WAIT_ATTEMPTS);
    if (fixture->host_action == 6) fixture->environment = NULL;
    if (fixture->host_action == 7) CHECK(ctos_ssh_role_bootstrap_close(fixture->selected_role) == 0);
    if (fixture->host_action == 8) result->cas_status = CTOS_SSH_CALLBACK_HOST_CAS_NOT_REQUIRED;
    return 0;
}

static int known_hosts_candidate(void *context, void *environment,
    const struct ctos_ssh_known_hosts_candidate *candidate) {
    struct callback_fixture *fixture = context;

    CHECK(fixture != NULL && fixture->environment == environment);
    CHECK(candidate != NULL);
    CHECK(candidate->evidence.classification == CTOS_SSH_CALLBACK_HOST_NEW);
    CHECK(candidate->evidence.role == fixture->expected_role);
    CHECK(candidate->evidence.hop == fixture->expected_hop);
    CHECK(candidate->evidence.port == fixture->expected_port);
    CHECK(strcmp(candidate->candidate_id, "native-candidate") == 0);
    CHECK(strcmp(candidate->evidence.connection_id,
        "native-client-connection") == 0);
    CHECK(strcmp(candidate->evidence.target_identity,
        "target:user@example.invalid:22") == 0);
    CHECK(strcmp(candidate->evidence.host, "example.invalid") == 0);
    CHECK(strcmp(candidate->evidence.key_type, fixture->expected_key_type) == 0);
    CHECK(strcmp(candidate->evidence.fingerprint,
        fixture->expected_fingerprint) == 0);
    CHECK(candidate->replacement_body != NULL
        && candidate->replacement_body_length > 0
        && candidate->replacement_body_length <= CTOS_SSH_CALLBACK_MAX_FILE_BYTES);
    CHECK(fixture->candidate_body == NULL);
    fixture->candidate_body = malloc(candidate->replacement_body_length);
    CHECK(fixture->candidate_body != NULL);
    memcpy(fixture->candidate_body, candidate->replacement_body,
        candidate->replacement_body_length);
    fixture->candidate_body_length = candidate->replacement_body_length;
    memcpy(fixture->candidate_base_digest, candidate->evidence.base_digest,
        sizeof(fixture->candidate_base_digest));
    memcpy(fixture->candidate_replacement_digest,
        candidate->evidence.replacement_digest,
        sizeof(fixture->candidate_replacement_digest));
    memcpy(fixture->last_candidate_id, candidate->candidate_id,
        sizeof(fixture->last_candidate_id));
    fixture->last_candidate_role = candidate->evidence.role;
    fixture->last_candidate_hop = candidate->evidence.hop;
    fixture->last_candidate_port = candidate->evidence.port;
    fixture->last_candidate_classification = candidate->evidence.classification;
    strcpy(fixture->last_candidate_key_type, candidate->evidence.key_type);
    strcpy(fixture->last_candidate_fingerprint,
        candidate->evidence.fingerprint);
    fixture->candidate_calls++;
    fixture->candidate_event_order = ++fixture->callback_event_order;
    return 0;
}

static int known_hosts_candidate_abort(void *context, void *environment) {
    struct callback_fixture *fixture = context;

    CHECK(fixture != NULL && fixture->environment == environment);
    CHECK(fixture->candidate_calls == 1);
    CHECK(fixture->host_evidence_calls == 1);
    fixture->candidate_abort_calls++;
    return 0;
}

static int kex_complete(void *context, void *environment, uint64_t cycle,
    const unsigned char session_digest[32]) {
    struct callback_fixture *fixture = context;
    unsigned char combined = 0;

    CHECK(fixture != NULL && fixture->environment == environment);
    CHECK(cycle != 0 && session_digest != NULL);
    CHECK(fixture->host_evidence_calls == 1);
    if (fixture->last_host_classification == CTOS_SSH_CALLBACK_HOST_NEW) {
        CHECK(fixture->candidate_calls == 1);
        CHECK(fixture->candidate_event_order == 1U);
        CHECK(fixture->host_evidence_event_order == 2U);
    } else {
        CHECK(fixture->candidate_calls == 0);
        CHECK(fixture->last_host_classification
            == CTOS_SSH_CALLBACK_HOST_KNOWN);
        CHECK(fixture->host_evidence_event_order == 1U);
    }
    CHECK(fixture->callback_event_order == fixture->host_evidence_event_order);
    for (size_t index = 0; index < sizeof(fixture->last_kex_digest); index++)
        combined |= session_digest[index];
    CHECK(combined != 0);
    fixture->kex_complete_calls++;
    fixture->last_kex_cycle = cycle;
    memcpy(fixture->last_kex_digest, session_digest,
        sizeof(fixture->last_kex_digest));
    fixture->kex_event_order = ++fixture->callback_event_order;
    return 0;
}

static int credential(void *context, void *environment, int kind) {
    struct callback_fixture *fixture = context;

    CHECK(fixture != NULL && fixture->environment == environment);
    (void)kind;
    fixture->credential_calls++;
    return EACCES;
}

static int credential_bytes(void *context, void *environment, int kind,
    unsigned char *output, size_t capacity, size_t *length) {
    struct callback_fixture *fixture = context;

    CHECK(fixture != NULL && fixture->environment == environment);
    (void)kind;
    (void)output;
    (void)capacity;
    *length = 0;
    fixture->credential_bytes_calls++;
    return EACCES;
}

static int credential_bytes_selected(void *context, void *environment, int kind,
    const unsigned char receipt[32], uint64_t cycle, const unsigned char digest[32],
    unsigned char *output, size_t capacity, size_t *length) {
    struct callback_fixture *fixture = context;
    const char *value = kind == CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD
        ? "selected-password" : "selected-private";
    const unsigned char *body;
    size_t body_length;

    CHECK(fixture != NULL && environment == fixture->environment);
    CHECK(kind == fixture->selected_kind);
    CHECK(receipt != NULL && memcmp(receipt, fixture->expected_selection_receipt, 32U) == 0);
    CHECK(cycle == fixture->last_kex_cycle && memcmp(digest, fixture->last_kex_digest, 32U) == 0);
    CHECK(fixture->kex_complete_calls == 1U && fixture->host_evidence_calls == 1U);
    CHECK(output != NULL && output != fixture->caller_output && length != NULL);
    for (size_t index = 0; index < fixture->caller_capacity; index++)
        CHECK(fixture->caller_output[index] == 0);
    fixture->selected_calls++;
    body = fixture->selected_body_override ? fixture->selected_body : (const unsigned char *)value;
    body_length = fixture->selected_body_override ? fixture->selected_body_length : strlen(value);
    if (body_length > capacity) return EOVERFLOW;
    if (body_length != 0) memcpy(output, body, body_length);
    *length = body_length;
    if (fixture->selected_action == 1) return EACCES;
    if (fixture->selected_action == 2) fixture->selected_view->valid = 0;
    if (fixture->selected_action == 3) CHECK(ctos_ssh_callback_cancel(fixture->selected_callback) == 0);
    if (fixture->selected_action == 4) CHECK(ctos_ssh_role_bootstrap_close(fixture->selected_role) == 0);
    if (fixture->selected_action == 5)
        wait_until_deadline_attempts(fixture->selected_deadline, TEST_EXPIRY_WAIT_ATTEMPTS);
    if (fixture->selected_action == 6) *length = capacity + 1U;
    for (size_t index = 0; index < fixture->caller_capacity; index++)
        CHECK(fixture->caller_output[index] == 0);
    return 0;
}

static int release(void *context, void *environment) {
    struct callback_fixture *fixture = context;

    CHECK(fixture != NULL && fixture->environment == environment);
    fixture->release_calls++;
    if (fixture->release_fd >= 0)
        fixture->release_saw_fd_open = fcntl(fixture->release_fd, F_GETFD) >= 0;
    if (fixture->release_failures_remaining != 0U) {
        fixture->release_failures_remaining--;
        return EIO;
    }
    return 0;
}

static ctos_ssh_callback_token open_callback(struct callback_fixture *fixture,
    ctos_ssh_role_token role, uint64_t deadline) {
    struct ctos_ssh_callback_target target = {
        .context = fixture,
        .validate_environment = validate_environment,
        .host_evidence = host_evidence,
        .known_hosts_candidate = known_hosts_candidate,
        .known_hosts_candidate_abort = known_hosts_candidate_abort,
        .kex_complete = kex_complete,
        .credential = credential,
        .credential_bytes = credential_bytes,
        .credential_bytes_selected = fixture->omit_selected_target ? NULL : credential_bytes_selected,
        .release = release
    };
    ctos_ssh_callback_token callback = 0;

    fixture->environment = fixture;
    CHECK(ctos_ssh_callback_open(&target, role, (int)getuid(), deadline,
        fixture->environment, &callback) == 0 && callback != 0);
    return callback;
}

static int expected_fingerprint(const struct sshkey *key,
    unsigned char output[32]) {
    unsigned char *fingerprint = NULL;
    size_t length = 0;
    int result = sshkey_fingerprint_raw(key, SSH_DIGEST_SHA256,
        &fingerprint, &length);

    if (result == 0 && length == 32) memcpy(output, fingerprint, 32);
    if (fingerprint != NULL) {
        volatile unsigned char *bytes = fingerprint;
        for (size_t index = 0; index < length; index++) bytes[index] = 0;
        free(fingerprint);
    }
    return result == 0 && length == 32 ? 0 : -1;
}

static size_t format_host_key(const struct sshkey *key, unsigned char *output,
    size_t capacity) {
    FILE *stream = fmemopen(output, capacity, "w+");
    long length;

    CHECK(stream != NULL);
    CHECK(ctos_hostfile_write_entry(stream, "example.invalid", NULL, key, 0) == 1);
    CHECK(fflush(stream) == 0);
    length = ftell(stream);
    CHECK(length > 0 && (unsigned long)length < capacity);
    CHECK(fclose(stream) == 0);
    return (size_t)length;
}

static void managed_known_hosts_digest(const struct ctos_ssh_view *view,
    unsigned char digest[32]) {
    static const unsigned char known_hosts_path[] = "ssh/known_hosts";
    static const unsigned char empty_body = 0;
    const struct ctos_ssh_file *file;
    const unsigned char *body;

    CHECK(view != NULL && digest != NULL);
    file = ctos_ssh_view_lookup(view, known_hosts_path,
        sizeof(known_hosts_path) - 1U, CTOS_SSH_KNOWN_HOSTS);
    CHECK(file != NULL);
    body = file->body.length == 0 ? &empty_body : file->body.data;
    CHECK(ssh_digest_memory(SSH_DIGEST_SHA256, body, file->body.length,
        digest, 32) == 0);
}

static void assert_issue_digest_metadata(const struct ctos_ssh_view *view,
    const struct ctos_ssh_hostfile_issue *issue) {
    unsigned char expected[32] = {0};

    CHECK(view != NULL && issue != NULL);
    managed_known_hosts_digest(view, expected);
    CHECK(memcmp(issue->base_digest, expected, sizeof(expected)) == 0);
    CHECK(memcmp(issue->replacement_digest, expected, sizeof(expected)) == 0);
}

static struct sshkey *make_host_certificate(struct sshkey **ca_out) {
    struct sshkey *certificate = NULL;
    struct sshkey *ca = NULL;

    CHECK(ca_out != NULL);
    CHECK(sshkey_generate(KEY_ED25519, 0, &certificate) == 0);
    CHECK(sshkey_generate(KEY_ED25519, 0, &ca) == 0);
    CHECK(sshkey_to_certified(certificate) == 0);
    certificate->cert->type = SSH2_CERT_TYPE_HOST;
    certificate->cert->key_id = strdup("native-client-certificate");
    CHECK(certificate->cert->key_id != NULL);
    certificate->cert->principals = calloc(1,
        sizeof(*certificate->cert->principals));
    CHECK(certificate->cert->principals != NULL);
    certificate->cert->principals[0] = strdup("example.invalid");
    CHECK(certificate->cert->principals[0] != NULL);
    certificate->cert->nprincipals = 1;
    certificate->cert->valid_after = 0;
    certificate->cert->valid_before = UINT64_MAX;
    CHECK(sshkey_from_private(ca, &certificate->cert->signature_key) == 0);
    CHECK(sshkey_certify(certificate, ca, NULL, NULL, NULL) == 0);
    {
        const char *reason = NULL;

        CHECK(sshkey_cert_check_host(certificate, "example.invalid", NULL,
            &reason) == 0);
    }
    *ca_out = ca;
    return certificate;
}

static void remember_expected_host_evidence(struct callback_fixture *fixture,
    const struct sshkey *key) {
    const char *key_type;
    char *fingerprint;
    size_t key_type_length;
    size_t fingerprint_length;

    CHECK(fixture != NULL && key != NULL);
    key_type = sshkey_ssh_name(key);
    fingerprint = sshkey_fingerprint(key, SSH_DIGEST_SHA256, SSH_FP_DEFAULT);
    CHECK(key_type != NULL && fingerprint != NULL);
    key_type_length = strlen(key_type);
    fingerprint_length = strlen(fingerprint);
    CHECK(key_type_length < sizeof(fixture->expected_key_type));
    CHECK(fingerprint_length < sizeof(fixture->expected_fingerprint));
    memcpy(fixture->expected_key_type, key_type, key_type_length + 1U);
    memcpy(fixture->expected_fingerprint, fingerprint,
        fingerprint_length + 1U);
    {
        volatile char *bytes = fingerprint;
        for (size_t index = 0; index < fingerprint_length; index++)
            bytes[index] = 0;
    }
    free(fingerprint);
}

enum service_reply {
    SERVICE_REPLY_EXACT,
    SERVICE_REPLY_MISSING,
    SERVICE_REPLY_EMPTY,
    SERVICE_REPLY_WRONG,
    SERVICE_REPLY_TRUNCATED,
    SERVICE_REPLY_TRAILING,
    SERVICE_REPLY_OVERSIZED,
    SERVICE_REPLY_USERAUTH_SUCCESS,
    SERVICE_REPLY_REKEY,
    SERVICE_REPLY_KEX_METHOD
};

enum password_reply {
    PASSWORD_REPLY_SUCCESS,
    PASSWORD_REPLY_REJECTED,
    PASSWORD_REPLY_PARTIAL,
    PASSWORD_REPLY_CHANGE,
    PASSWORD_REPLY_SUCCESS_TRAILING,
    PASSWORD_REPLY_FAILURE_TRUNCATED,
    PASSWORD_REPLY_FAILURE_TRAILING,
    PASSWORD_REPLY_CHANGE_TRUNCATED,
    PASSWORD_REPLY_CHANGE_TRAILING,
    PASSWORD_REPLY_BANNER_TRUNCATED,
    PASSWORD_REPLY_BANNER_TRAILING,
    PASSWORD_REPLY_UNEXPECTED,
    PASSWORD_REPLY_REKEY,
    PASSWORD_REPLY_DISCONNECT,
    PASSWORD_REPLY_DISCONNECT_TRUNCATED,
    PASSWORD_REPLY_EOF,
    PASSWORD_REPLY_HOLD
};

struct fixture {
    struct ctos_ssh_native_client *client;
    struct ssh *server;
    struct ctos_ssh_view *view;
    struct ctos_ssh_view_borrow *view_borrow;
    struct callback_fixture callback;
    struct sshkey *private_key;
    struct sshkey *public_key;
    struct sshkey *host_key;
    struct sshkey *certificate_authority;
    ctos_ssh_role_token role;
    ctos_ssh_callback_token callback_token;
    unsigned char fingerprint[32];
    unsigned char *public_blob;
    size_t public_blob_length;
    uint64_t deadline;
    int pair[2];
    int service_expected;
    int service_reply_sent;
    enum service_reply service_reply;
    unsigned int service_requests;
    int suppress_server_ext_info;
    struct wire selection_wire;
    unsigned char selection_receipt[32];
    int password_expected;
    unsigned int password_requests;
    unsigned int password_pending_banner_writes;
    enum password_reply password_reply;
    const unsigned char *password_username;
    size_t password_username_length;
    const unsigned char *password_methods;
    size_t password_methods_length;
    unsigned int password_banners;
    size_t password_text_length;
    size_t password_language_length;
    int private_expected;
    int encrypted_identity;
    const struct sshkey *identity;
    const unsigned char *identity_blob;
    size_t identity_blob_length;
    const char *identity_algorithm;
    const char *signature_advertisement;
    unsigned int private_requests;
    int shell_expected, shell_stage, shell_reject;
    uint32_t shell_window, shell_maxpacket, shell_credit;
    unsigned int shell_resize_count, shell_eof_count, shell_close_count;
    unsigned int shell_unknown_failures;
    unsigned char shell_input[65536];
    size_t shell_input_length;
};

static void make_kex_params(struct kex_params *params) {
    char *proposals[PROPOSAL_MAX] = {KEX_CLIENT};

    CHECK(params != NULL);
    memset(params, 0, sizeof(*params));
    memcpy(params->proposal, proposals, sizeof(proposals));
}

static void setup_synthetic_server(struct fixture *fixture) {
    struct kex_params params;

    CHECK(fixture != NULL && fixture->server == NULL
        && fixture->private_key != NULL && fixture->pair[1] >= 0);
    make_kex_params(&params);
    CHECK(ssh_init(&fixture->server, 1, &params) == 0
        && fixture->server != NULL);
    CHECK(ssh_packet_set_connection(fixture->server, fixture->pair[1],
        fixture->pair[1]) != NULL);
    ssh_packet_set_nonblocking(fixture->server);
    CHECK(ssh_add_hostkey(fixture->server, fixture->private_key) == 0);
    if (fixture->signature_advertisement != NULL) {
        free(fixture->server->kex->server_sig_algs);
        fixture->server->kex->server_sig_algs = strdup(fixture->signature_advertisement);
        CHECK(fixture->server->kex->server_sig_algs != NULL);
    }
}

static void make_selection_wire(struct wire *wire, const struct ctos_ssh_role_snapshot *role,
    uint32_t kind, const struct ctos_ssh_slice fields[5]) {
    memset(wire, 0, sizeof(*wire));
    wire_u32(wire, 0x43534153U);
    wire_u32(wire, 0x00010000U);
    wire_u64(wire, role->token);
    wire_u32(wire, role->hop);
    wire_put(wire, role->role_nonce, 32U);
    wire_put(wire, role->connection_binding, 32U);
    wire_u32(wire, kind);
    CHECK(wire->length == 88U);
    for (size_t index = 0; index < 5U; index++) {
        CHECK(fields[index].length <= UINT32_MAX);
        wire_u32(wire, (uint32_t)fields[index].length);
        wire_put(wire, fields[index].data, fields[index].length);
    }
}

static void reject_selection_open(struct fixture *fixture,
    const struct ctos_ssh_native_client_config *base, const unsigned char *wire,
    size_t length, int expected_error) {
    struct ctos_ssh_native_client_config config = *base;
    struct ctos_ssh_native_client *client = NULL;

    config.authentication_selection_wire = wire;
    config.authentication_selection_wire_length = length;
    errno = 0;
    CHECK(ctos_ssh_native_client_open(&config, &client) != 0
        && errno == expected_error && client == NULL);
    CHECK(fcntl(fixture->pair[0], F_GETFD) >= 0);
    CHECK(ctos_ssh_view_borrow_live(fixture->view_borrow) == 0);
    CHECK(ctos_ssh_role_bootstrap_active(fixture->role));
    CHECK(fixture->callback.release_calls == 0U
        && fixture->callback.host_evidence_calls == 0U
        && fixture->callback.kex_complete_calls == 0U
        && fixture->callback.credential_calls == 0U
        && fixture->callback.credential_bytes_calls == 0U);
}

static void exercise_selection_admission(struct fixture *fixture,
    const struct ctos_ssh_native_client_config *config,
    const struct ctos_ssh_role_snapshot *snapshot) {
    struct wire changed;
    struct ctos_ssh_slice fields[5] = {
        {(const unsigned char *)" 用户😀 ", sizeof(" 用户😀 ") - 1U},
        {(const unsigned char *)"password_2", sizeof("password_2") - 1U},
        {NULL, 0}, {NULL, 0}, {NULL, 0}
    };
    static const unsigned char bad_utf8[][5] = {
        {0xc0, 0xaf}, {0xe0, 0x80, 0xaf}, {0xed, 0xa0, 0x80},
        {0xf4, 0x90, 0x80, 0x80}, {0xf0, 0x80, 0x80, 0x80},
        {0xe2, 0x82}, {0x80}, {0xc2, 0x85}, {'a', 0, 'b'}, {'a', 0x7f}, {'a', 0x1f}
    };
    static const size_t bad_lengths[] = {2U, 3U, 3U, 4U, 4U, 2U, 1U, 2U, 3U, 2U, 2U};

    for (size_t length = 0; length < fixture->selection_wire.length; length++)
        reject_selection_open(fixture, config, fixture->selection_wire.bytes, length, EINVAL);
    reject_selection_open(fixture, config, NULL, 1U, EINVAL);
    reject_selection_open(fixture, config, fixture->selection_wire.bytes,
        CTOS_SSH_NATIVE_CLIENT_MAX_SELECTION_WIRE_BYTES + 1U, EINVAL);
    changed = fixture->selection_wire;
    wire_put(&changed, "x", 1U);
    reject_selection_open(fixture, config, changed.bytes, changed.length, EINVAL);
    for (size_t offset_index = 0; offset_index < 4U; offset_index++) {
        const size_t offsets[] = {0U, 5U, 7U, 87U};
        changed = fixture->selection_wire;
        changed.bytes[offsets[offset_index]] ^= 0x40U;
        reject_selection_open(fixture, config, changed.bytes, changed.length, EINVAL);
    }
    for (size_t offset_index = 0; offset_index < 4U; offset_index++) {
        const size_t offsets[] = {15U, 19U, 20U, 52U};
        changed = fixture->selection_wire;
        changed.bytes[offsets[offset_index]] ^= 0x40U;
        reject_selection_open(fixture, config, changed.bytes, changed.length, EACCES);
    }
    changed = fixture->selection_wire;
    changed.bytes[8] = 0x80U; /* Outside positive jlong range. */
    reject_selection_open(fixture, config, changed.bytes, changed.length, EINVAL);
    changed = fixture->selection_wire;
    memset(changed.bytes + 8U, 0, 8U);
    reject_selection_open(fixture, config, changed.bytes, changed.length, EINVAL);
    changed = fixture->selection_wire;
    memset(changed.bytes + 88U, 0xff, 4U);
    reject_selection_open(fixture, config, changed.bytes, changed.length, EINVAL);
    for (size_t index = 0; index < sizeof(bad_lengths) / sizeof(bad_lengths[0]); index++) {
        fields[0] = (struct ctos_ssh_slice){bad_utf8[index], bad_lengths[index]};
        make_selection_wire(&changed, snapshot, 1U, fields);
        reject_selection_open(fixture, config, changed.bytes, changed.length, EINVAL);
    }
    unsigned char long_name[256];
    memset(long_name, 'u', sizeof(long_name));
    fields[0] = (struct ctos_ssh_slice){long_name, sizeof(long_name)};
    make_selection_wire(&changed, snapshot, 1U, fields);
    reject_selection_open(fixture, config, changed.bytes, changed.length, EINVAL);
    fields[0] = (struct ctos_ssh_slice){(const unsigned char *)"user", 4U};
    for (unsigned int invalid_shape = 0; invalid_shape < 5U; invalid_shape++) {
        fields[1] = (struct ctos_ssh_slice){(const unsigned char *)"password_2", 10U};
        fields[2] = fields[3] = fields[4] = (struct ctos_ssh_slice){NULL, 0};
        if (invalid_shape == 0U) fields[1].length = 0;
        else if (invalid_shape == 1U) fields[1] = (struct ctos_ssh_slice){(const unsigned char *)"bad.id", 6U};
        else fields[invalid_shape] = (struct ctos_ssh_slice){(const unsigned char *)"x", 1U};
        make_selection_wire(&changed, snapshot, 1U, fields);
        reject_selection_open(fixture, config, changed.bytes, changed.length, EINVAL);
    }
    fields[1] = (struct ctos_ssh_slice){NULL, 0};
    fields[3] = (struct ctos_ssh_slice){(const unsigned char *)"identity", 8U};
    fields[4] = (struct ctos_ssh_slice){(const unsigned char *)"public", 6U};
    const char *invalid_paths[] = {"", "/key", "../key", "a//b", "a/./b", "a\\b", "a:b",
        "~key", "a%b", "k*", "k?", "k[", "a/b/c/d/e/f/g/h/i", "a\xc2\x85"};
    for (size_t index = 0; index < sizeof(invalid_paths) / sizeof(invalid_paths[0]); index++) {
        fields[2] = (struct ctos_ssh_slice){(const unsigned char *)invalid_paths[index], strlen(invalid_paths[index])};
        make_selection_wire(&changed, snapshot, 4U, fields);
        reject_selection_open(fixture, config, changed.bytes, changed.length, EINVAL);
    }
    fields[2] = (struct ctos_ssh_slice){(const unsigned char *)"keys/key", 8U};
    for (unsigned int invalid_shape = 0; invalid_shape < 4U; invalid_shape++) {
        struct ctos_ssh_slice altered[5];
        memcpy(altered, fields, sizeof(altered));
        if (invalid_shape == 0U) altered[1] = (struct ctos_ssh_slice){(const unsigned char *)"password", 8U};
        else if (invalid_shape == 1U) altered[3].length = 0;
        else if (invalid_shape == 2U) altered[3] = (struct ctos_ssh_slice){bad_utf8[7], 2U};
        else altered[4].length = 0;
        make_selection_wire(&changed, snapshot, 4U, altered);
        reject_selection_open(fixture, config, changed.bytes, changed.length, EINVAL);
    }
}

static void finish_fixture_setup_selected_mode(struct fixture *fixture,
    unsigned int lifetime_ms, const struct sshkey *known_hosts_key,
    const char *known_hosts_prefix, int empty_known_hosts,
    int fail_descriptor_duplication, unsigned int selection_kind, int admission_matrix,
    enum ctos_ssh_native_host_key_mode host_key_mode) {
    struct ctos_ssh_role_request request;
    struct ctos_ssh_native_client_config config;
    unsigned char known_hosts_body[4096] = {0};
    size_t known_hosts_length;

    fixture->deadline = deadline_after(lifetime_ms);
    CHECK(fixture->host_key != NULL);
    CHECK(expected_fingerprint(fixture->host_key, fixture->fingerprint) == 0);
    known_hosts_length = 0;
    if (!empty_known_hosts) {
        if (known_hosts_key == NULL) known_hosts_key = fixture->host_key;
        known_hosts_length = format_host_key(known_hosts_key,
            known_hosts_body, sizeof(known_hosts_body));
        if (known_hosts_prefix != NULL) {
            size_t prefix_length = strlen(known_hosts_prefix);

            CHECK(prefix_length <= sizeof(known_hosts_body) - known_hosts_length);
            memmove(known_hosts_body + prefix_length, known_hosts_body,
                known_hosts_length);
            memcpy(known_hosts_body, known_hosts_prefix, prefix_length);
            known_hosts_length += prefix_length;
        }
    }
    fixture->view = load_view(known_hosts_body, known_hosts_length,
        fixture->deadline);
    CHECK(sshkey_to_blob(fixture->host_key, &fixture->public_blob,
        &fixture->public_blob_length) == 0);
    CHECK(ctos_ssh_view_borrow_owned_view(fixture->view,
        &fixture->view_borrow) == 0);
    request = role_request(fixture->deadline);
    CHECK(ctos_ssh_role_bootstrap_open(&request, &fixture->role) == 0);
    fixture->callback_token = open_callback(&fixture->callback, fixture->role,
        fixture->deadline);
    fixture->callback.expected_role = fixture->role;
    fixture->callback.expected_hop = 0;
    fixture->callback.expected_port = 22;
    remember_expected_host_evidence(&fixture->callback, fixture->host_key);
    CHECK(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0,
        fixture->pair) == 0);
    fixture->callback.release_fd = fixture->pair[0];
    config = (struct ctos_ssh_native_client_config){
        .role = fixture->role,
        .socket_fd = fixture->pair[0],
        .owner_uid = getuid(),
        .owns_socket = 1,
        .callback = fixture->callback_token,
        .callback_environment = fixture->callback.environment,
        .deadline = fixture->deadline,
        .step_budget = TEST_STEP_BUDGET,
        .server_host_public_key_blob = fixture->public_blob,
        .server_host_public_key_length = fixture->public_blob_length,
        .expected_host_fingerprint = fixture->fingerprint,
        .managed_view_borrow = fixture->view_borrow,
        .known_hosts_path = "ssh/known_hosts",
        .host = "example.invalid",
        .candidate_id = "native-candidate"
    };
    if (host_key_mode == CTOS_SSH_HOST_KEY_MANAGED) {
        config.host_key_mode = host_key_mode;
        config.server_host_public_key_blob = NULL;
        config.server_host_public_key_length = 0;
        config.expected_host_fingerprint = NULL;
        for (unsigned int invalid = 0; invalid < 7U; invalid++) {
            struct ctos_ssh_native_client_config bad = config;
            if (invalid == 0) bad.host_key_mode = 2;
            if (invalid == 1) bad.server_host_public_key_blob = fixture->public_blob;
            if (invalid == 2) bad.server_host_public_key_length = fixture->public_blob_length;
            if (invalid == 3) bad.expected_host_fingerprint = fixture->fingerprint;
            if (invalid == 4) bad.host_key_mode = CTOS_SSH_HOST_KEY_PINNED;
            if (invalid == 5) {
                bad.host_key_mode = CTOS_SSH_HOST_KEY_PINNED;
                bad.server_host_public_key_blob = fixture->public_blob;
                bad.server_host_public_key_length = fixture->public_blob_length;
            }
            if (invalid == 6) {
                bad.host_key_mode = CTOS_SSH_HOST_KEY_PINNED;
                bad.expected_host_fingerprint = fixture->fingerprint;
            }
            reject_selection_open(fixture, &bad, NULL, 0, EINVAL);
        }
    }
    if (selection_kind != 0U) {
        struct ctos_ssh_role_snapshot snapshot;
        struct ctos_ssh_slice fields[5] = {
            {(const unsigned char *)" 用户😀 ", sizeof(" 用户😀 ") - 1U},
            {NULL, 0}, {NULL, 0}, {NULL, 0}, {NULL, 0}
        };
        unsigned char boundary_username[255];
        memset(boundary_username, 'u', sizeof(boundary_username));
        CHECK(ctos_ssh_role_bootstrap_snapshot(fixture->role, &snapshot) == 0);
        if (selection_kind == 1U) {
            if (!fixture->password_expected)
                fields[0] = (struct ctos_ssh_slice){boundary_username, sizeof(boundary_username)};
            fields[1] = (struct ctos_ssh_slice){(const unsigned char *)"password_2", 10U};
        } else {
            fields[2] = (struct ctos_ssh_slice){(const unsigned char *)"keys/私钥", sizeof("keys/私钥") - 1U};
            fields[3] = (struct ctos_ssh_slice){(const unsigned char *)"身份😀", sizeof("身份😀") - 1U};
            fields[4] = (struct ctos_ssh_slice){(const unsigned char *)"\0public\xff", 8U};
            if (fixture->private_expected)
                fields[4] = (struct ctos_ssh_slice){fixture->identity_blob, fixture->identity_blob_length};
            if (fixture->encrypted_identity)
                fields[1] = (struct ctos_ssh_slice){(const unsigned char *)"passphrase", 10U};
        }
        make_selection_wire(&fixture->selection_wire, &snapshot, selection_kind, fields);
        if (fixture->encrypted_identity) fixture->selection_wire.bytes[5] = 2U;
        if (fixture->password_expected || fixture->private_expected) {
            fixture->password_username = (const unsigned char *)" 用户😀 ";
            fixture->password_username_length = sizeof(" 用户😀 ") - 1U;
        }
        CHECK(ssh_digest_memory(SSH_DIGEST_SHA256, fixture->selection_wire.bytes,
            fixture->selection_wire.length, fixture->selection_receipt, 32U) == 0);
        config.authentication_selection_wire = fixture->selection_wire.bytes;
        config.authentication_selection_wire_length = fixture->selection_wire.length;
        if (admission_matrix) exercise_selection_admission(fixture, &config, &snapshot);
        memset(&snapshot, 0, sizeof(snapshot));
    }
    if (fixture->private_expected) {
        fixture->view->deadline = fixture->deadline - 1U;
        reject_selection_open(fixture, &config, config.authentication_selection_wire,
            config.authentication_selection_wire_length, EACCES);
        fixture->view->deadline = fixture->deadline + 1000U;
    }
    if (fail_descriptor_duplication) {
        struct rlimit original_limit;
        struct rlimit exhausted_limit;
        int open_result;
        int open_error;
        int restore_result;

        CHECK(getrlimit(RLIMIT_NOFILE, &original_limit) == 0);
        CHECK(fcntl(STDIN_FILENO, F_GETFD) >= 0);
        exhausted_limit = original_limit;
        /* minfd=0 must remain in range; occupied fd0 leaves no free slot. */
        exhausted_limit.rlim_cur = 1;
        CHECK(setrlimit(RLIMIT_NOFILE, &exhausted_limit) == 0);
        errno = 0;
        open_result = ctos_ssh_native_client_open(&config, &fixture->client);
        open_error = errno;
        restore_result = setrlimit(RLIMIT_NOFILE, &original_limit);
        CHECK(restore_result == 0);
        CHECK(open_result != 0 && open_error == EMFILE
            && fixture->client == NULL);
        CHECK(fcntl(fixture->pair[0], F_GETFD) >= 0);
        CHECK(fixture->callback.release_calls == 0U);
        CHECK(ctos_ssh_view_borrow_live(fixture->view_borrow) == 0);
        CHECK(ctos_ssh_role_bootstrap_active(fixture->role));
        /* The same ownership tuple must now retry without a stale KEX slot. */
    }
    CHECK(ctos_ssh_native_client_open(&config, &fixture->client) == 0);
    if (host_key_mode == CTOS_SSH_HOST_KEY_MANAGED) {
        CHECK(ctos_ssh_native_client_verify_host_key(fixture->client,
            fixture->public_blob, fixture->public_blob_length) != 0 && errno == EACCES);
        CHECK(fixture->callback.host_evidence_calls == 0U);
    }
}

static void finish_fixture_setup_selected(struct fixture *fixture,
    unsigned int lifetime_ms, const struct sshkey *known_hosts_key,
    const char *known_hosts_prefix, int empty_known_hosts,
    int fail_descriptor_duplication, unsigned int selection_kind, int admission_matrix) {
    finish_fixture_setup_selected_mode(fixture, lifetime_ms, known_hosts_key,
        known_hosts_prefix, empty_known_hosts, fail_descriptor_duplication,
        selection_kind, admission_matrix, CTOS_SSH_HOST_KEY_PINNED);
}

static void finish_fixture_setup(struct fixture *fixture,
    unsigned int lifetime_ms, const struct sshkey *known_hosts_key,
    const char *known_hosts_prefix, int empty_known_hosts, int fail_descriptor_duplication) {
    finish_fixture_setup_selected(fixture, lifetime_ms, known_hosts_key,
        known_hosts_prefix, empty_known_hosts, fail_descriptor_duplication, 0U, 0);
}

static void setup_selected_fixture(struct fixture *fixture, unsigned int lifetime_ms,
    unsigned int kind, int admission_matrix, int fail_descriptor_duplication) {
    memset(fixture, 0, sizeof(*fixture));
    fixture->pair[0] = fixture->pair[1] = -1;
    fixture->callback.release_fd = -1;
    CHECK(sshkey_generate(KEY_ED25519, 0, &fixture->private_key) == 0);
    CHECK(sshkey_from_private(fixture->private_key, &fixture->public_key) == 0);
    fixture->host_key = fixture->public_key;
    finish_fixture_setup_selected(fixture, lifetime_ms, NULL, NULL, 0,
        fail_descriptor_duplication, kind, admission_matrix);
}

static void setup_fixture_with_known_hosts_prefix(struct fixture *fixture,
    unsigned int lifetime_ms, const struct sshkey *known_hosts_key,
    const char *known_hosts_prefix) {
    memset(fixture, 0, sizeof(*fixture));
    fixture->pair[0] = fixture->pair[1] = -1;
    fixture->callback.release_fd = -1;
    CHECK(sshkey_generate(KEY_ED25519, 0, &fixture->private_key) == 0);
    CHECK(sshkey_from_private(fixture->private_key, &fixture->public_key) == 0);
    fixture->host_key = fixture->public_key;
    finish_fixture_setup(fixture, lifetime_ms, known_hosts_key,
        known_hosts_prefix, 0, 0);
}

static void setup_certificate_fixture(struct fixture *fixture,
    unsigned int lifetime_ms) {
    memset(fixture, 0, sizeof(*fixture));
    fixture->pair[0] = fixture->pair[1] = -1;
    fixture->callback.release_fd = -1;
    fixture->host_key = make_host_certificate(&fixture->certificate_authority);
    finish_fixture_setup(fixture, lifetime_ms, NULL, NULL, 0, 0);
}

static void setup_empty_known_hosts_fixture(struct fixture *fixture,
    unsigned int lifetime_ms) {
    memset(fixture, 0, sizeof(*fixture));
    fixture->pair[0] = fixture->pair[1] = -1;
    fixture->callback.release_fd = -1;
    CHECK(sshkey_generate(KEY_ED25519, 0, &fixture->private_key) == 0);
    CHECK(sshkey_from_private(fixture->private_key, &fixture->public_key) == 0);
    fixture->host_key = fixture->public_key;
    finish_fixture_setup(fixture, lifetime_ms, NULL, NULL, 1, 0);
}

static void setup_fixture(struct fixture *fixture, unsigned int lifetime_ms,
    const struct sshkey *known_hosts_key) {
    setup_fixture_with_known_hosts_prefix(fixture, lifetime_ms, known_hosts_key,
        NULL);
}

static void setup_revoked_fixture(struct fixture *fixture,
    unsigned int lifetime_ms) {
    setup_fixture_with_known_hosts_prefix(fixture, lifetime_ms, NULL,
        "@revoked ");
}

static void cleanup_fixture(struct fixture *fixture,
    unsigned int expected_release_calls) {
    CHECK(ctos_ssh_native_client_close(fixture->client) == 0);
    enum ctos_ssh_native_client_password_auth_result auth = CTOS_SSH_PASSWORD_AUTH_SUCCESS;
    errno = 0;
    CHECK(ctos_ssh_native_client_password_auth_result(fixture->client, &auth) != 0
        && errno == ENOENT && auth == CTOS_SSH_PASSWORD_AUTH_NOT_STARTED);
    enum ctos_ssh_native_client_private_key_auth_result key_auth = CTOS_SSH_PRIVATE_KEY_AUTH_SUCCESS;
    CHECK(ctos_ssh_native_client_private_key_auth_result(fixture->client, &key_auth) != 0
        && errno == ENOENT && key_auth == CTOS_SSH_PRIVATE_KEY_AUTH_NOT_STARTED);
    CHECK(ctos_ssh_native_client_userauth_service_ready(fixture->client) == 0);
    errno = 0;
    CHECK(ctos_ssh_native_client_begin_userauth_service(fixture->client) != 0
        && errno == ENOENT);
    fixture->view_borrow = NULL;
    CHECK(fixture->callback.release_calls == expected_release_calls);
    CHECK(fixture->callback.credential_calls == 0
        && fixture->callback.credential_bytes_calls == 0);
    CHECK(fixture->callback.release_saw_fd_open == 1);
    errno = 0;
    CHECK(ctos_ssh_native_client_step(fixture->client,
        &(struct ctos_ssh_native_client_step){0}) != 0 && errno == ENOENT);
    errno = 0;
    CHECK(ctos_ssh_native_client_close(fixture->client) != 0 && errno == ENOENT);
    errno = 0;
    CHECK(fcntl(fixture->pair[0], F_GETFD) < 0 && errno == EBADF);
    errno = 0;
    CHECK(close(fixture->pair[0]) < 0 && errno == EBADF);
    CHECK(ctos_ssh_native_client_destroy(fixture->client) == 0);
    if (fixture->server != NULL) {
        ssh_free(fixture->server);
        fixture->server = NULL;
        errno = 0;
        CHECK(fcntl(fixture->pair[1], F_GETFD) < 0 && errno == EBADF);
        errno = 0;
        CHECK(close(fixture->pair[1]) < 0 && errno == EBADF);
    } else {
        CHECK(fixture->pair[1] >= 0 && close(fixture->pair[1]) == 0);
    }
    fixture->pair[1] = -1;
    if (fixture->role != 0) CHECK(ctos_ssh_role_bootstrap_close(fixture->role) == 0);
    destroy_view(fixture->view);
    sshkey_free(fixture->public_key);
    sshkey_free(fixture->private_key);
    if (fixture->host_key != fixture->public_key)
        sshkey_free(fixture->host_key);
    sshkey_free(fixture->certificate_authority);
    if (fixture->public_blob != NULL) {
        volatile unsigned char *bytes = fixture->public_blob;
        for (size_t index = 0; index < fixture->public_blob_length; index++) bytes[index] = 0;
        free(fixture->public_blob);
    }
    if (fixture->callback.candidate_body != NULL) {
        volatile unsigned char *bytes = fixture->callback.candidate_body;
        for (size_t index = 0; index < fixture->callback.candidate_body_length;
            index++) bytes[index] = 0;
        free(fixture->callback.candidate_body);
        fixture->callback.candidate_body = NULL;
        fixture->callback.candidate_body_length = 0;
    }
}

static void assert_new_candidate_metadata(const struct fixture *fixture) {
    static const unsigned char known_hosts_path[] = "ssh/known_hosts";
    const struct ctos_ssh_file *file;
    unsigned char expected_body[4096] = {0};
    unsigned char managed_digest[32] = {0};
    unsigned char candidate_digest[32] = {0};
    unsigned char managed_combined = 0;
    unsigned char candidate_combined = 0;
    size_t expected_body_length;

    CHECK(fixture != NULL);
    CHECK(fixture->callback.candidate_calls == 1U);
    CHECK(fixture->callback.host_evidence_calls == 1U);
    file = ctos_ssh_view_lookup(fixture->view, known_hosts_path,
        sizeof(known_hosts_path) - 1U, CTOS_SSH_KNOWN_HOSTS);
    CHECK(file != NULL && file->body.length == 0);
    CHECK(fixture->callback.candidate_body != NULL
        && fixture->callback.candidate_body_length > 0);
    expected_body_length = format_host_key(fixture->host_key, expected_body,
        sizeof(expected_body));
    CHECK(fixture->callback.candidate_body_length == expected_body_length);
    CHECK(memcmp(fixture->callback.candidate_body, expected_body,
        expected_body_length) == 0);
    CHECK(fixture->callback.last_candidate_classification
        == CTOS_SSH_CALLBACK_HOST_NEW);
    CHECK(strcmp(fixture->callback.last_candidate_id,
        "native-candidate") == 0);
    CHECK(fixture->callback.last_candidate_role
        == fixture->callback.expected_role);
    CHECK(fixture->callback.last_candidate_hop
        == fixture->callback.expected_hop);
    CHECK(fixture->callback.last_candidate_port
        == fixture->callback.expected_port);
    CHECK(strcmp(fixture->callback.last_candidate_key_type,
        fixture->callback.expected_key_type) == 0);
    CHECK(strcmp(fixture->callback.last_candidate_fingerprint,
        fixture->callback.expected_fingerprint) == 0);
    CHECK(strcmp(fixture->callback.last_key_type,
        fixture->callback.expected_key_type) == 0);
    CHECK(strcmp(fixture->callback.last_fingerprint,
        fixture->callback.expected_fingerprint) == 0);
    managed_known_hosts_digest(fixture->view, managed_digest);
    CHECK(memcmp(fixture->callback.candidate_base_digest, managed_digest,
        sizeof(managed_digest)) == 0);
    CHECK(memcmp(fixture->callback.last_host_base_digest, managed_digest,
        sizeof(managed_digest)) == 0);
    CHECK(ssh_digest_memory(SSH_DIGEST_SHA256, expected_body,
        expected_body_length, candidate_digest,
        sizeof(candidate_digest)) == 0);
    CHECK(memcmp(fixture->callback.candidate_replacement_digest,
        candidate_digest, sizeof(candidate_digest)) == 0);
    CHECK(memcmp(fixture->callback.last_host_replacement_digest,
        candidate_digest, sizeof(candidate_digest)) == 0);
    for (size_t index = 0; index < sizeof(managed_digest); index++) {
        managed_combined |= managed_digest[index];
        candidate_combined |= candidate_digest[index];
    }
    CHECK(managed_combined != 0 && candidate_combined != 0);
}

static void send_service_reply(struct fixture *fixture) {
    int packet_type = SSH2_MSG_SERVICE_ACCEPT;

    if (fixture->service_reply == SERVICE_REPLY_USERAUTH_SUCCESS)
        packet_type = SSH2_MSG_USERAUTH_SUCCESS;
    if (fixture->service_reply == SERVICE_REPLY_REKEY) packet_type = SSH2_MSG_KEXINIT;
    if (fixture->service_reply == SERVICE_REPLY_KEX_METHOD) packet_type = 30;
    CHECK(sshpkt_start(fixture->server, packet_type) == 0);
    switch (fixture->service_reply) {
        case SERVICE_REPLY_EXACT:
            CHECK(sshpkt_put_cstring(fixture->server, "ssh-userauth") == 0);
            break;
        case SERVICE_REPLY_EMPTY:
            CHECK(sshpkt_put_cstring(fixture->server, "") == 0);
            break;
        case SERVICE_REPLY_WRONG:
            CHECK(sshpkt_put_cstring(fixture->server, "ssh-connection") == 0);
            break;
        case SERVICE_REPLY_TRUNCATED:
            CHECK(sshpkt_put_u32(fixture->server, 12U) == 0);
            CHECK(sshpkt_put(fixture->server, "ssh", 3U) == 0);
            break;
        case SERVICE_REPLY_TRAILING:
            CHECK(sshpkt_put_cstring(fixture->server, "ssh-userauth") == 0);
            CHECK(sshpkt_put_u8(fixture->server, 1U) == 0);
            break;
        case SERVICE_REPLY_OVERSIZED:
            CHECK(sshpkt_put_u32(fixture->server, UINT32_MAX) == 0);
            break;
        default:
            break;
    }
    CHECK(sshpkt_send(fixture->server) == 0);
    fixture->service_reply_sent = 1;
}

static void put_password_remote_text(struct fixture *fixture) {
    unsigned char text[8193];
    unsigned char language[129];

    CHECK(fixture->password_text_length <= sizeof(text));
    CHECK(fixture->password_language_length <= sizeof(language));
    memset(text, 't', sizeof(text));
    memset(language, 'l', sizeof(language));
    CHECK(sshpkt_put_string(fixture->server, text, fixture->password_text_length) == 0);
    CHECK(sshpkt_put_string(fixture->server, language, fixture->password_language_length) == 0);
}

static void send_password_reply(struct fixture *fixture) {
    int type = SSH2_MSG_USERAUTH_SUCCESS;

    if (fixture->password_reply == PASSWORD_REPLY_EOF) {
        CHECK(shutdown(fixture->pair[1], SHUT_WR) == 0);
        return;
    }
    if (fixture->password_reply == PASSWORD_REPLY_HOLD) return;
    for (unsigned int index = 0; index < fixture->password_banners; index++) {
        CHECK(sshpkt_start(fixture->server, SSH2_MSG_USERAUTH_BANNER) == 0);
        put_password_remote_text(fixture);
        CHECK(sshpkt_send(fixture->server) == 0);
    }
    switch (fixture->password_reply) {
        case PASSWORD_REPLY_REJECTED:
        case PASSWORD_REPLY_PARTIAL:
        case PASSWORD_REPLY_FAILURE_TRUNCATED:
        case PASSWORD_REPLY_FAILURE_TRAILING:
            type = SSH2_MSG_USERAUTH_FAILURE;
            break;
        case PASSWORD_REPLY_CHANGE:
        case PASSWORD_REPLY_CHANGE_TRUNCATED:
        case PASSWORD_REPLY_CHANGE_TRAILING:
            type = SSH2_MSG_USERAUTH_PASSWD_CHANGEREQ;
            break;
        case PASSWORD_REPLY_BANNER_TRUNCATED:
        case PASSWORD_REPLY_BANNER_TRAILING:
            type = SSH2_MSG_USERAUTH_BANNER;
            break;
        case PASSWORD_REPLY_UNEXPECTED:
            type = SSH2_MSG_SERVICE_ACCEPT;
            break;
        case PASSWORD_REPLY_REKEY:
            type = SSH2_MSG_KEXINIT;
            break;
        case PASSWORD_REPLY_DISCONNECT:
        case PASSWORD_REPLY_DISCONNECT_TRUNCATED:
            type = SSH2_MSG_DISCONNECT;
            break;
        default:
            break;
    }
    CHECK(sshpkt_start(fixture->server, type) == 0);
    if (type == SSH2_MSG_USERAUTH_FAILURE) {
        CHECK(sshpkt_put_string(fixture->server, fixture->password_methods,
            fixture->password_methods_length) == 0);
        if (fixture->password_reply != PASSWORD_REPLY_FAILURE_TRUNCATED)
            CHECK(sshpkt_put_u8(fixture->server,
                fixture->password_reply == PASSWORD_REPLY_PARTIAL ? 255 : 0) == 0);
    }
    if (type == SSH2_MSG_DISCONNECT)
        CHECK(sshpkt_put_u32(fixture->server, SSH2_DISCONNECT_BY_APPLICATION) == 0);
    if (type == SSH2_MSG_USERAUTH_PASSWD_CHANGEREQ || type == SSH2_MSG_USERAUTH_BANNER
        || type == SSH2_MSG_DISCONNECT) {
        if (fixture->password_reply == PASSWORD_REPLY_CHANGE_TRUNCATED
            || fixture->password_reply == PASSWORD_REPLY_BANNER_TRUNCATED
            || fixture->password_reply == PASSWORD_REPLY_DISCONNECT_TRUNCATED) {
            CHECK(sshpkt_put_u32(fixture->server, UINT32_MAX) == 0);
        } else {
            put_password_remote_text(fixture);
        }
    }
    if (fixture->password_reply == PASSWORD_REPLY_SUCCESS_TRAILING
        || fixture->password_reply == PASSWORD_REPLY_FAILURE_TRAILING
        || fixture->password_reply == PASSWORD_REPLY_CHANGE_TRAILING
        || fixture->password_reply == PASSWORD_REPLY_BANNER_TRAILING)
        CHECK(sshpkt_put_u8(fixture->server, 1) == 0);
    CHECK(sshpkt_send(fixture->server) == 0);
}

static void accept_password_request(struct fixture *fixture) {
    const unsigned char *field;
    size_t length;
    u_char change;

    CHECK(sshpkt_get_string_direct(fixture->server, &field, &length) == 0);
    CHECK(length == fixture->password_username_length);
    CHECK(memcmp(field, fixture->password_username, length) == 0);
    CHECK(sshpkt_get_string_direct(fixture->server, &field, &length) == 0);
    CHECK(length == sizeof("ssh-connection") - 1U
        && memcmp(field, "ssh-connection", length) == 0);
    CHECK(sshpkt_get_string_direct(fixture->server, &field, &length) == 0);
    CHECK(length == sizeof("password") - 1U && memcmp(field, "password", length) == 0);
    CHECK(sshpkt_get_u8(fixture->server, &change) == 0 && change == 0);
    CHECK(sshpkt_get_string_direct(fixture->server, &field, &length) == 0);
    if (fixture->callback.selected_body_override) {
        CHECK(length == fixture->callback.selected_body_length);
        CHECK(memcmp(field, fixture->callback.selected_body, length) == 0);
    } else {
        CHECK(length == sizeof("selected-password") - 1U
            && memcmp(field, "selected-password", length) == 0);
    }
    CHECK(sshpkt_get_end(fixture->server) == 0);
    CHECK(++fixture->password_requests == 1U);
    CHECK(fixture->callback.selected_calls == 1U);
    send_password_reply(fixture);
}

static void accept_private_request(struct fixture *fixture) {
    const unsigned char *username, *service, *method, *algorithm, *blob, *signature;
    size_t username_length, service_length, method_length, algorithm_length, blob_length, signature_length;
    size_t offsets[8];
    u_char signed_request;
    struct sshkey *received = NULL;
    struct sshbuf *data = sshbuf_new(), *wrong = sshbuf_new();
    unsigned char digest[32];

    CHECK(data != NULL && wrong != NULL);
    CHECK(sshpkt_get_string_direct(fixture->server, &username, &username_length) == 0);
    CHECK(username_length == fixture->password_username_length
        && memcmp(username, fixture->password_username, username_length) == 0);
    CHECK(sshpkt_get_string_direct(fixture->server, &service, &service_length) == 0);
    CHECK(service_length == 14U && memcmp(service, "ssh-connection", 14U) == 0);
    CHECK(sshpkt_get_string_direct(fixture->server, &method, &method_length) == 0);
    CHECK(method_length == 9U && memcmp(method, "publickey", 9U) == 0);
    CHECK(sshpkt_get_u8(fixture->server, &signed_request) == 0 && signed_request == 1);
    CHECK(sshpkt_get_string_direct(fixture->server, &algorithm, &algorithm_length) == 0);
    CHECK(algorithm_length == strlen(fixture->identity_algorithm)
        && memcmp(algorithm, fixture->identity_algorithm, algorithm_length) == 0);
    CHECK(sshpkt_get_string_direct(fixture->server, &blob, &blob_length) == 0);
    CHECK(sshkey_from_blob(blob, blob_length, &received) == 0);
    CHECK(sshkey_equal_public(received, fixture->identity));
    CHECK(!sshkey_equal_public(received, fixture->host_key));
    CHECK(sshpkt_get_string_direct(fixture->server, &signature, &signature_length) == 0);
    CHECK(sshpkt_get_end(fixture->server) == 0);
    offsets[0] = 4U;
    CHECK(sshbuf_put_stringb(data, fixture->server->kex->session_id) == 0);
    offsets[1] = sshbuf_len(data);
    CHECK(sshbuf_put_u8(data, SSH2_MSG_USERAUTH_REQUEST) == 0);
    offsets[2] = sshbuf_len(data) + 4U;
    CHECK(sshbuf_put_string(data, username, username_length) == 0);
    offsets[3] = sshbuf_len(data) + 4U;
    CHECK(sshbuf_put_string(data, service, service_length) == 0);
    offsets[4] = sshbuf_len(data) + 4U;
    CHECK(sshbuf_put_string(data, method, method_length) == 0);
    offsets[5] = sshbuf_len(data);
    CHECK(sshbuf_put_u8(data, signed_request) == 0);
    offsets[6] = sshbuf_len(data) + 4U;
    CHECK(sshbuf_put_string(data, algorithm, algorithm_length) == 0);
    offsets[7] = sshbuf_len(data) + 4U;
    CHECK(sshbuf_put_string(data, blob, blob_length) == 0);
    CHECK(sshkey_check_sigtype(signature, signature_length, fixture->identity_algorithm) == 0);
    CHECK(sshkey_verify(received, signature, signature_length, sshbuf_ptr(data), sshbuf_len(data),
        fixture->identity_algorithm, 0, NULL) == 0);
    for (size_t index = 0; index < 8U; index++) {
        sshbuf_reset(wrong);
        CHECK(sshbuf_putb(wrong, data) == 0);
        sshbuf_mutable_ptr(wrong)[offsets[index]] ^= 1U;
        CHECK(sshkey_verify(received, signature, signature_length, sshbuf_ptr(wrong), sshbuf_len(wrong),
            fixture->identity_algorithm, 0, NULL) != 0);
    }
    CHECK(ssh_digest_buffer(SSH_DIGEST_SHA256, fixture->server->kex->session_id,
        digest, sizeof(digest)) == 0);
    sshbuf_reset(wrong);
    CHECK(sshbuf_put_string(wrong, digest, sizeof(digest)) == 0);
    CHECK(sshbuf_put(wrong, sshbuf_ptr(data) + 4U + sshbuf_len(fixture->server->kex->session_id),
        sshbuf_len(data) - 4U - sshbuf_len(fixture->server->kex->session_id)) == 0);
    CHECK(sshkey_verify(received, signature, signature_length, sshbuf_ptr(wrong), sshbuf_len(wrong),
        fixture->identity_algorithm, 0, NULL) != 0);
    sshbuf_reset(wrong);
    CHECK(sshbuf_put(wrong, signature, signature_length) == 0);
    sshbuf_mutable_ptr(wrong)[signature_length - 1U] ^= 1U;
    CHECK(sshkey_verify(received, sshbuf_ptr(wrong), sshbuf_len(wrong), sshbuf_ptr(data),
        sshbuf_len(data), fixture->identity_algorithm, 0, NULL) != 0);
    sshbuf_free(data);
    sshbuf_free(wrong);
    sshkey_free(received);
    CHECK(++fixture->private_requests == 1U);
    send_password_reply(fixture);
}

static void peer_channel_packet(struct fixture *fixture, int type) {
    CHECK(sshpkt_start(fixture->server, type) == 0);
    CHECK(sshpkt_put_u32(fixture->server, 0) == 0);
}

static void peer_channel_data(struct fixture *fixture, int extended,
    const unsigned char *bytes, size_t length) {
    peer_channel_packet(fixture, extended ? SSH2_MSG_CHANNEL_EXTENDED_DATA : SSH2_MSG_CHANNEL_DATA);
    if (extended) CHECK(sshpkt_put_u32(fixture->server, 1) == 0);
    CHECK(sshpkt_put_string(fixture->server, bytes, length) == 0);
    CHECK(sshpkt_send(fixture->server) == 0);
}

static void accept_shell_packet(struct fixture *fixture, int type) {
    struct ssh *ssh = fixture->server;
    const unsigned char *text;
    size_t length;
    uint32_t value;
    u_char flag;
    if (type == SSH2_MSG_CHANNEL_OPEN) {
        CHECK(fixture->shell_stage == 0);
        CHECK(sshpkt_get_string_direct(ssh, &text, &length) == 0);
        CHECK(length == 7U && memcmp(text, "session", 7U) == 0);
        CHECK(sshpkt_get_u32(ssh, &value) == 0 && value == 0);
        CHECK(sshpkt_get_u32(ssh, &value) == 0 && value == 65536U);
        CHECK(sshpkt_get_u32(ssh, &value) == 0 && value == 32768U);
        CHECK(sshpkt_get_end(ssh) == 0);
        fixture->shell_stage = 1;
        peer_channel_packet(fixture, fixture->shell_reject == 1
            ? SSH2_MSG_CHANNEL_OPEN_FAILURE : SSH2_MSG_CHANNEL_OPEN_CONFIRMATION);
        if (fixture->shell_reject == 1) {
            CHECK(sshpkt_put_u32(ssh, 1) == 0);
            CHECK(sshpkt_put_cstring(ssh, "refused") == 0);
            CHECK(sshpkt_put_cstring(ssh, "en") == 0);
        } else {
            CHECK(sshpkt_put_u32(ssh, 77) == 0);
            CHECK(sshpkt_put_u32(ssh, fixture->shell_window) == 0);
            CHECK(sshpkt_put_u32(ssh, fixture->shell_maxpacket) == 0);
        }
        CHECK(sshpkt_send(ssh) == 0);
        return;
    }
    CHECK(sshpkt_get_u32(ssh, &value) == 0 && value == 77);
    if (type == SSH2_MSG_CHANNEL_REQUEST) {
        CHECK(sshpkt_get_string_direct(ssh, &text, &length) == 0);
        CHECK(sshpkt_get_u8(ssh, &flag) == 0);
        if (length == 7U && memcmp(text, "pty-req", 7U) == 0) {
            CHECK(fixture->shell_stage == 1 && flag == 1);
            CHECK(sshpkt_get_string_direct(ssh, &text, &length) == 0
                && length == 14U && memcmp(text, "xterm-256color", 14U) == 0);
            CHECK(sshpkt_get_u32(ssh, &value) == 0 && value == 80);
            CHECK(sshpkt_get_u32(ssh, &value) == 0 && value == 24);
            CHECK(sshpkt_get_u32(ssh, &value) == 0 && value == 0);
            CHECK(sshpkt_get_u32(ssh, &value) == 0 && value == 0);
            CHECK(sshpkt_get_string_direct(ssh, &text, &length) == 0 && length == 1U && text[0] == 0);
            fixture->shell_stage = 2;
        } else if (length == 5U && memcmp(text, "shell", 5U) == 0) {
            CHECK(fixture->shell_stage == 2 && flag == 1);
            fixture->shell_stage = 3;
        } else {
            CHECK(length == 13U && memcmp(text, "window-change", 13U) == 0 && flag == 0);
            CHECK(fixture->shell_stage == 3);
            CHECK(sshpkt_get_u32(ssh, &value) == 0 && value == 132);
            CHECK(sshpkt_get_u32(ssh, &value) == 0 && value == 43);
            CHECK(sshpkt_get_u32(ssh, &value) == 0 && value == 0);
            CHECK(sshpkt_get_u32(ssh, &value) == 0 && value == 0);
            fixture->shell_resize_count++;
        }
        CHECK(sshpkt_get_end(ssh) == 0);
        if (flag) {
            peer_channel_packet(fixture, fixture->shell_reject == fixture->shell_stage
                ? SSH2_MSG_CHANNEL_FAILURE : SSH2_MSG_CHANNEL_SUCCESS);
            CHECK(sshpkt_send(ssh) == 0);
        }
    } else if (type == SSH2_MSG_CHANNEL_DATA) {
        CHECK(fixture->shell_stage == 3);
        CHECK(sshpkt_get_string_direct(ssh, &text, &length) == 0);
        CHECK(length <= sizeof(fixture->shell_input) - fixture->shell_input_length);
        memcpy(fixture->shell_input + fixture->shell_input_length, text, length);
        fixture->shell_input_length += length;
        CHECK(sshpkt_get_end(ssh) == 0);
    } else if (type == SSH2_MSG_CHANNEL_WINDOW_ADJUST) {
        CHECK(sshpkt_get_u32(ssh, &value) == 0 && value != 0);
        CHECK(value <= UINT32_MAX - fixture->shell_credit);
        fixture->shell_credit += value;
        CHECK(sshpkt_get_end(ssh) == 0);
    } else if (type == SSH2_MSG_CHANNEL_EOF || type == SSH2_MSG_CHANNEL_CLOSE) {
        CHECK(sshpkt_get_end(ssh) == 0);
        if (type == SSH2_MSG_CHANNEL_EOF) fixture->shell_eof_count++;
        else fixture->shell_close_count++;
    } else if (type == SSH2_MSG_CHANNEL_FAILURE) {
        CHECK(sshpkt_get_end(ssh) == 0);
        fixture->shell_unknown_failures++;
    } else CHECK(0);
}

static int pump_synthetic_server(struct fixture *fixture, size_t chunk,
    int *read_would_block, int *partial_write) {
    unsigned char input[64];
    const unsigned char *output;
    size_t output_length;
    size_t transfer;
    ssize_t received;
    ssize_t sent;
    u_char packet_type = SSH_MSG_NONE;
    int result;

    if (fixture == NULL || fixture->server == NULL || chunk == 0
        || chunk > sizeof(input)) return -1;
    received = recv(fixture->pair[1], input, chunk, MSG_DONTWAIT);
    if (received > 0) {
        if (ssh_input_append(fixture->server, input, (size_t)received) != 0)
            return -1;
    } else if (received == 0) {
        return -1;
    } else if (errno == EAGAIN || errno == EWOULDBLOCK) {
        if (read_would_block != NULL) *read_would_block = 1;
    } else if (errno != EINTR) {
        return -1;
    }
    if (fixture->suppress_server_ext_info) fixture->server->kex->ext_info_c = 0;
    result = ssh_packet_next(fixture->server, &packet_type);
    if (fixture->suppress_server_ext_info) fixture->server->kex->ext_info_c = 0;
    if (result != 0) return result;
    if (packet_type == SSH2_MSG_EXT_INFO
        && kex_input_ext_info(packet_type, 0, fixture->server) != 0) return -1;
    if (packet_type == SSH2_MSG_SERVICE_REQUEST && fixture->service_expected) {
        const unsigned char *service = NULL;
        size_t length;

        CHECK(sshpkt_get_string_direct(fixture->server, &service, &length) == 0);
        CHECK(length == sizeof("ssh-userauth") - 1U);
        CHECK(memcmp(service, "ssh-userauth", length) == 0);
        CHECK(sshpkt_get_end(fixture->server) == 0);
        CHECK(++fixture->service_requests == 1U);
        if (!fixture->service_reply_sent) send_service_reply(fixture);
    } else if (packet_type == SSH2_MSG_USERAUTH_REQUEST && fixture->password_expected) {
        accept_password_request(fixture);
    } else if (packet_type == SSH2_MSG_USERAUTH_REQUEST && fixture->private_expected) {
        accept_private_request(fixture);
    } else if (fixture->shell_expected && packet_type >= SSH2_MSG_CHANNEL_OPEN
        && packet_type <= SSH2_MSG_CHANNEL_FAILURE) {
        accept_shell_packet(fixture, packet_type);
    } else if (packet_type == SSH2_MSG_REQUEST_FAILURE && fixture->shell_expected) {
        CHECK(sshpkt_get_end(fixture->server) == 0);
        fixture->shell_unknown_failures++;
    } else if (packet_type != SSH_MSG_NONE && packet_type != SSH2_MSG_EXT_INFO) {
        return -1;
    }
    output = ssh_output_ptr(fixture->server, &output_length);
    if (output == NULL || output_length == 0) return 0;
    transfer = output_length < chunk ? output_length : chunk;
    sent = send(fixture->pair[1], output, transfer, MSG_NOSIGNAL);
    if (sent > 0) {
        if ((size_t)sent < output_length && partial_write != NULL)
            *partial_write = 1;
        if (ssh_output_consume(fixture->server, (size_t)sent) != 0) return -1;
    } else if (sent < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
        return 0;
    } else if (sent < 0 && errno == EINTR) {
        return 0;
    } else {
        return -1;
    }
    return 0;
}

static void collect_peer_output(const struct fixture *fixture,
    unsigned char *output, size_t *output_length, size_t output_capacity) {
    CHECK(fixture != NULL && output != NULL && output_length != NULL);
    CHECK(*output_length < output_capacity);
    for (;;) {
        ssize_t received = recv(fixture->pair[1], output + *output_length,
            output_capacity - *output_length, MSG_DONTWAIT);

        if (received > 0) {
            *output_length += (size_t)received;
            CHECK(*output_length < output_capacity);
            continue;
        }
        if (received < 0 && errno == EINTR) continue;
        CHECK(received < 0 && (errno == EAGAIN || errno == EWOULDBLOCK));
        return;
    }
}

static void test_failed_open_releases_kex_role(void) {
    struct fixture fixture;

    memset(&fixture, 0, sizeof(fixture));
    fixture.pair[0] = fixture.pair[1] = -1;
    fixture.callback.release_fd = -1;
    CHECK(sshkey_generate(KEY_ED25519, 0, &fixture.private_key) == 0);
    CHECK(sshkey_from_private(fixture.private_key, &fixture.public_key) == 0);
    fixture.host_key = fixture.public_key;
    finish_fixture_setup(&fixture, 15000U, NULL, NULL, 0, 1);
    cleanup_fixture(&fixture, 1);
}

static void test_prepare_tcp_descriptor(void) {
    int pair[2], descriptor;
    CHECK(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, pair) == 0);
    descriptor = dup(pair[0]);
    CHECK(descriptor >= 0 && (fcntl(descriptor, F_GETFD) & FD_CLOEXEC) == 0);
    int original_flags = fcntl(pair[0], F_GETFL);
    CHECK(ctos_ssh_native_client_prepare_tcp_descriptor(descriptor) == 0);
    CHECK((fcntl(descriptor, F_GETFD) & FD_CLOEXEC) != 0);
    CHECK(fcntl(descriptor, F_GETFL) == original_flags && fcntl(pair[0], F_GETFL) == original_flags);
    CHECK(ctos_ssh_native_client_prepare_tcp_descriptor(descriptor) == 0);
    unsigned char byte = 0;
    CHECK(write(descriptor, "x", 1U) == 1 && read(pair[1], &byte, 1U) == 1 && byte == 'x');
    CHECK(close(descriptor) == 0 && close(pair[0]) == 0 && close(pair[1]) == 0);

    for (unsigned int mode = 0; mode < 4U; mode++) {
        if (mode < 2U) {
            CHECK(socketpair(AF_UNIX, mode == 0 ? SOCK_STREAM : SOCK_DGRAM | SOCK_NONBLOCK,
                0, pair) == 0);
            descriptor = pair[0];
        } else descriptor = open("/dev/null", (mode == 2 ? O_RDWR : O_RDONLY) | O_NONBLOCK);
        CHECK(descriptor >= 0);
        int descriptor_flags = fcntl(descriptor, F_GETFD);
        int file_flags = fcntl(descriptor, F_GETFL);
        CHECK(ctos_ssh_native_client_prepare_tcp_descriptor(descriptor) != 0 && errno == EINVAL);
        CHECK(fcntl(descriptor, F_GETFD) == descriptor_flags && fcntl(descriptor, F_GETFL) == file_flags);
        CHECK(close(descriptor) == 0);
        if (mode < 2U) CHECK(close(pair[1]) == 0);
    }
    CHECK(ctos_ssh_native_client_prepare_tcp_descriptor(-1) != 0 && errno == EINVAL);
}

static void test_admission_and_descriptor(void) {
    struct ctos_ssh_role_request request;
    struct ctos_ssh_native_client_config config = {0};
    struct ctos_ssh_native_client *client = (struct ctos_ssh_native_client *)1;
    unsigned char fingerprint[32] = {1};
    static const unsigned char key_blob[] = {1, 2, 3};
    uint64_t expiry = deadline_after(5000);
    ctos_ssh_role_token role = 0;
    int pair[2] = {-1, -1};
    int flags;

    errno = 0;
    CHECK(ctos_ssh_native_client_open(NULL, &client) != 0
        && errno == EINVAL && client == NULL);
    request = role_request(expiry);
    CHECK(ctos_ssh_role_bootstrap_open(&request, &role) == 0);
    CHECK(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0,
        pair) == 0);
    config = (struct ctos_ssh_native_client_config){
        .role = role, .socket_fd = pair[0], .owner_uid = getuid(),
        .owns_socket = 1, .callback = 1, .callback_environment = &config,
        .deadline = expiry, .step_budget = 64,
        .server_host_public_key_blob = key_blob,
        .server_host_public_key_length = sizeof(key_blob),
        .expected_host_fingerprint = fingerprint,
        .managed_view_borrow = NULL, .known_hosts_path = "ssh/known_hosts",
        .host = "example.invalid", .candidate_id = "native-candidate"
    };
    errno = 0;
    CHECK(ctos_ssh_native_client_open(&config, &client) != 0
        && errno == EINVAL && client == NULL);
    CHECK(fcntl(pair[0], F_GETFD) >= 0);

    flags = fcntl(pair[0], F_GETFL);
    CHECK(flags >= 0 && fcntl(pair[0], F_SETFL, flags & ~O_NONBLOCK) == 0);
    errno = 0;
    CHECK(ctos_ssh_native_client_open(&config, &client) != 0
        && errno == EINVAL && client == NULL);
    CHECK(fcntl(pair[0], F_SETFL, flags) == 0);

    /* Admission policy failures must not retain an unrelated caller errno or
     * expose the role registry's ENOENT for an unknown token. */
    for (unsigned int attempt = 0; attempt < 2U; attempt++) {
        int prior_error = attempt == 0 ? 0 : EIO;

        config.owner_uid++;
        errno = prior_error;
        CHECK(ctos_ssh_native_client_open(&config, &client) != 0
            && errno == EACCES && client == NULL);
        CHECK(fcntl(pair[0], F_GETFD) >= 0);
        config.owner_uid = getuid();
        config.role = role + 1U;
        errno = prior_error;
        CHECK(ctos_ssh_native_client_open(&config, &client) != 0
            && errno == EACCES && client == NULL);
        CHECK(fcntl(pair[0], F_GETFD) >= 0);
        config.role = role;
        config.deadline = expiry - 1U;
        errno = prior_error;
        CHECK(ctos_ssh_native_client_open(&config, &client) != 0
            && errno == EACCES && client == NULL);
        CHECK(fcntl(pair[0], F_GETFD) >= 0);
        config.deadline = expiry;
        config.host = "other.invalid";
        errno = prior_error;
        CHECK(ctos_ssh_native_client_open(&config, &client) != 0
            && errno == EACCES && client == NULL);
        CHECK(fcntl(pair[0], F_GETFD) >= 0);
        config.host = "example.invalid";
    }

    CHECK(close(pair[0]) == 0 && close(pair[1]) == 0);
    CHECK(ctos_ssh_role_bootstrap_close(role) == 0);
}

static void test_malformed_view_slice(void) {
    struct fixture fixture;
    struct ctos_ssh_native_client_config config;
    struct ctos_ssh_view *view;
    int pair[2] = {-1, -1};

    memset(&fixture, 0, sizeof(fixture));
    fixture.callback.release_fd = -1;
    fixture.deadline = deadline_after(5000);
    CHECK(sshkey_generate(KEY_ED25519, 0, &fixture.private_key) == 0);
    CHECK(sshkey_from_private(fixture.private_key, &fixture.public_key) == 0);
    CHECK(expected_fingerprint(fixture.public_key, fixture.fingerprint) == 0);
    CHECK(sshkey_to_blob(fixture.public_key, &fixture.public_blob,
        &fixture.public_blob_length) == 0);
    view = load_view(NULL, 0, fixture.deadline);
    fixture.view = view;
    CHECK(ctos_ssh_view_borrow_owned_view(view, &fixture.view_borrow) == 0);
    CHECK(ctos_ssh_role_bootstrap_open(&(struct ctos_ssh_role_request)
        { .program = CTOS_SSH_ROLE_CLIENT, .owner_pid = getpid(),
          .owner_uid = getuid(), .hop = 0, .port = 22,
          .deadline = fixture.deadline, .connection_id = "native-client-connection",
          .target_identity = "target:user@example.invalid:22",
          .host = "example.invalid", .public_view_id = "native-client-view",
          .role_nonce = (const unsigned char[32]){3},
          .connection_binding = (const unsigned char[32]){4} }, &fixture.role) == 0);
    CHECK(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0,
        pair) == 0);
    fixture.callback.release_fd = pair[0];
    fixture.callback_token = open_callback(&fixture.callback, fixture.role,
        fixture.deadline);
    /* The production admission guard must reject this before the generic
     * view lookup walks file paths and calls memcmp. */
    view->connection.data = NULL;
    config = (struct ctos_ssh_native_client_config){
        .role = fixture.role, .socket_fd = pair[0], .owner_uid = getuid(),
        .owns_socket = 1, .callback = fixture.callback_token,
        .callback_environment = fixture.callback.environment,
        .deadline = fixture.deadline, .step_budget = 64,
        .server_host_public_key_blob = fixture.public_blob,
        .server_host_public_key_length = fixture.public_blob_length,
        .expected_host_fingerprint = fixture.fingerprint,
        .managed_view_borrow = fixture.view_borrow,
        .known_hosts_path = "ssh/known_hosts", .host = "example.invalid",
        .candidate_id = "native-candidate"
    };
    errno = 0;
    CHECK(ctos_ssh_native_client_open(&config, &fixture.client) != 0
        && errno == EACCES && fixture.client == NULL);
    /* Open does not consume a pre-opened callback when admission fails. The
     * caller closes that live binding on the original event-loop owner. */
    CHECK(ctos_ssh_callback_close(fixture.callback_token,
        fixture.callback.environment) == 0);
    CHECK(fixture.callback.release_calls == 1);
    CHECK(fixture.callback.release_saw_fd_open == 1);
    CHECK(close(pair[0]) == 0 && close(pair[1]) == 0);
    CHECK(ctos_ssh_role_bootstrap_close(fixture.role) == 0);
    CHECK(ctos_ssh_view_borrow_release(fixture.view_borrow) == 0);
    destroy_view(view);
    sshkey_free(fixture.public_key);
    sshkey_free(fixture.private_key);
    if (fixture.public_blob != NULL) {
        volatile unsigned char *bytes = fixture.public_blob;
        for (size_t index = 0; index < fixture.public_blob_length; index++) bytes[index] = 0;
        free(fixture.public_blob);
    }
}

static void test_actual_view_roots(void) {
    const char *roots[] = {"ssh/config", ".ssh/config", ".ssh/ctos-manual"};

    for (size_t root_index = 0; root_index < sizeof(roots) / sizeof(roots[0]); root_index++) {
        const char *hosts = root_index == 0 ? "ssh/known_hosts" : ".ssh/known_hosts";
        for (unsigned int fault = 0; fault < 8U; fault++) {
            struct fixture fixture = {0};
            struct ctos_ssh_native_client_config config;
            struct ctos_ssh_role_request request;
            unsigned char banner[256] = {0};

            fixture.deadline = deadline_after(5000);
            fixture.view = load_view_paths(NULL, 0, fixture.deadline, roots[root_index], hosts);
            CHECK(ctos_ssh_view_borrow_owned_view(fixture.view, &fixture.view_borrow) == 0);
            request = role_request(fixture.deadline);
            CHECK(ctos_ssh_role_bootstrap_open(&request, &fixture.role) == 0);
            fixture.callback_token = open_callback(&fixture.callback, fixture.role, fixture.deadline);
            CHECK(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, fixture.pair) == 0);
            fixture.callback.release_fd = fixture.pair[0];
            config = (struct ctos_ssh_native_client_config){
                .role = fixture.role, .socket_fd = fixture.pair[0], .owner_uid = getuid(),
                .owns_socket = 1, .callback = fixture.callback_token,
                .callback_environment = fixture.callback.environment,
                .deadline = fixture.deadline, .step_budget = 64U,
                .host_key_mode = CTOS_SSH_HOST_KEY_MANAGED,
                .managed_view_borrow = fixture.view_borrow,
                .known_hosts_path = hosts, .host = "example.invalid", .candidate_id = "actual-root-candidate"
            };
            // Metadata corruption probes retain a real validated/borrowed view and exercise
            // native open's final CONFIG/type/reference/connection admission, before transport.
            if (fault == 1U) fixture.view->root = (struct ctos_ssh_slice){
                (const unsigned char *)".ssh/missing", sizeof(".ssh/missing") - 1U};
            if (fault == 2U) fixture.view->files[0].type = CTOS_SSH_PUBLIC_KEY;
            if (fault == 3U) config.known_hosts_path = ".ssh/missing-known_hosts";
            if (fault == 4U) fixture.view->files[1].type = CTOS_SSH_PUBLIC_KEY;
            if (fault == 5U) fixture.view->files[0].ref_count = 0;
            if (fault == 6U) fixture.view->connection = (struct ctos_ssh_slice){
                (const unsigned char *)"foreign-connection", sizeof("foreign-connection") - 1U};
            if (fault == 7U) fixture.view->root = (struct ctos_ssh_slice){
                (const unsigned char *)"../escape", sizeof("../escape") - 1U};
            errno = 0;
            if (fault == 0U) {
                static const unsigned char peer_banner[] = "SSH-2.0-ctos-root-fixture\r\n";
                size_t length = 0, written = 0;
                CHECK(ctos_ssh_native_client_open(&config, &fixture.client) == 0 && fixture.client != NULL);
                // The production packet reader waits for peer identification before emitting
                // its own identification. Supply only that bounded protocol prerequisite.
                CHECK(send(fixture.pair[1], peer_banner, sizeof(peer_banner) - 1U, MSG_NOSIGNAL)
                    == (ssize_t)(sizeof(peer_banner) - 1U));
                for (unsigned int attempt = 0; attempt < 16U && length < 8U; attempt++) {
                    struct ctos_ssh_native_client_step step = {0};
                    CHECK(ctos_ssh_native_client_step(fixture.client, &step) == 0);
                    written += step.bytes_written;
                    ssize_t received = recv(fixture.pair[1], banner + length, sizeof(banner) - length, MSG_DONTWAIT);
                    if (received > 0) length += (size_t)received;
                    else CHECK(received < 0 && (errno == EAGAIN || errno == EWOULDBLOCK));
                }
                CHECK(written > 0 && fixture.callback.host_evidence_calls == 0);
                CHECK(length >= 8U && memcmp(banner, "SSH-2.0-", 8U) == 0);
                cleanup_fixture(&fixture, 1);
            } else {
                CHECK(ctos_ssh_native_client_open(&config, &fixture.client) != 0 && fixture.client == NULL);
                CHECK(errno == EACCES || (fault == 3U && errno == ENOENT));
                CHECK(fcntl(fixture.pair[0], F_GETFD) >= 0 && fixture.callback.release_calls == 0);
                CHECK(recv(fixture.pair[1], banner, sizeof(banner), MSG_DONTWAIT) < 0
                    && (errno == EAGAIN || errno == EWOULDBLOCK));
                CHECK(fixture.callback.host_evidence_calls == 0 && fixture.callback.kex_complete_calls == 0);
                CHECK(ctos_ssh_view_borrow_live(fixture.view_borrow) == 0);
                CHECK(ctos_ssh_callback_close(fixture.callback_token, fixture.callback.environment) == 0);
                CHECK(fixture.callback.release_calls == 1 && fixture.callback.release_saw_fd_open == 1);
                CHECK(close(fixture.pair[0]) == 0 && close(fixture.pair[1]) == 0);
                CHECK(ctos_ssh_role_bootstrap_close(fixture.role) == 0);
                CHECK(ctos_ssh_view_borrow_release(fixture.view_borrow) == 0);
                destroy_view(fixture.view);
            }
        }
    }
}

static void test_step_and_close(void) {
    struct fixture fixture;
    unsigned char byte = 0;
    struct ctos_ssh_native_client_step step;

    setup_fixture(&fixture, 15000U, NULL);
    step = (struct ctos_ssh_native_client_step){0};
    CHECK(ctos_ssh_native_client_step(fixture.client, &step) == 0);
    CHECK(step.read_would_block == 1 && step.bytes_read == 0
        && step.input_appended == 0);
    step = (struct ctos_ssh_native_client_step){
        .input = &byte, .input_length = 1
    };
    errno = 0;
    CHECK(ctos_ssh_native_client_step(fixture.client, &step) != 0
        && errno == EINVAL && step.input_consumed == 0);
    step = (struct ctos_ssh_native_client_step){.cancelled = 1};
    errno = 0;
    CHECK(ctos_ssh_native_client_step(fixture.client, &step) != 0
        && errno == ECANCELED);
    cleanup_fixture(&fixture, 1);
}

static void test_verify_known_host_key_proof(void) {
    struct fixture fixture;
    struct sshkey *different_private_key = NULL;
    struct sshkey *different_public_key = NULL;
    unsigned char *different_blob = NULL;
    unsigned char different_fingerprint[32];
    size_t different_blob_length = 0;

    setup_fixture(&fixture, 15000U, NULL);
    errno = 0;
    CHECK(ctos_ssh_native_client_verify_host_key(fixture.client,
        fixture.public_blob, fixture.public_blob_length) == 0);
    CHECK(fixture.callback.host_evidence_calls == 1);
    CHECK(fixture.callback.last_host_role == fixture.role);
    CHECK(fixture.callback.last_host_hop == 0U);
    CHECK(fixture.callback.last_host_port == 22U);
    CHECK(fixture.callback.last_host_classification
        == CTOS_SSH_CALLBACK_HOST_KNOWN);
    CHECK(fixture.callback.last_host_outcome
        == CTOS_SSH_CALLBACK_HOST_RESULT_APPROVED);
    CHECK(fixture.callback.last_host_cas_status
        == CTOS_SSH_CALLBACK_HOST_CAS_NOT_REQUIRED);
    CHECK(strcmp(fixture.callback.last_key_type,
        fixture.callback.expected_key_type) == 0);
    CHECK(strcmp(fixture.callback.last_fingerprint,
        fixture.callback.expected_fingerprint) == 0);

    CHECK(sshkey_generate(KEY_ED25519, 0, &different_private_key) == 0);
    CHECK(sshkey_from_private(different_private_key, &different_public_key) == 0);
    CHECK(expected_fingerprint(different_public_key, different_fingerprint) == 0);
    CHECK(memcmp(different_fingerprint, fixture.fingerprint,
        sizeof(different_fingerprint)) != 0);
    CHECK(sshkey_to_blob(different_public_key, &different_blob,
        &different_blob_length) == 0);
    errno = 0;
    CHECK(ctos_ssh_native_client_verify_host_key(fixture.client,
        different_blob, different_blob_length) != 0 && errno == EACCES);
    CHECK(fixture.callback.host_evidence_calls == 1);
    CHECK(fixture.callback.last_host_classification
        == CTOS_SSH_CALLBACK_HOST_KNOWN);
    CHECK(strcmp(fixture.callback.last_fingerprint,
        fixture.callback.expected_fingerprint) == 0);

    cleanup_fixture(&fixture, 1);
    sshkey_free(different_public_key);
    sshkey_free(different_private_key);
    if (different_blob != NULL) {
        volatile unsigned char *bytes = different_blob;
        for (size_t index = 0; index < different_blob_length; index++)
            bytes[index] = 0;
        free(different_blob);
    }
}

static void test_fragmented_identification_is_atomic(void) {
    static const unsigned char identification[] =
        "SSH-2.0-synthetic-fragmented\r\n";
    struct sshkey *private_key = NULL;
    struct sshkey *public_key = NULL;
    unsigned char fingerprint[32];

    CHECK(sshkey_generate(KEY_ED25519, 0, &private_key) == 0);
    CHECK(sshkey_from_private(private_key, &public_key) == 0);
    CHECK(expected_fingerprint(public_key, fingerprint) == 0);
    for (int is_server = 0; is_server <= 1; is_server++) {
        struct ssh *peer = NULL;
        struct sshbuf *remote_version;
        struct kex_params params;
        ctos_ssh_role_token role = 0;
        size_t appended = 0;
        u_char packet_type = SSH_MSG_NONE;

        make_kex_params(&params);
        CHECK(ssh_init(&peer, is_server, &params) == 0 && peer != NULL);
        CHECK(ssh_add_hostkey(peer, is_server ? private_key : public_key) == 0);
        if (!is_server) {
            struct ctos_ssh_role_request request = role_request(deadline_after(5000U));

            CHECK(ctos_ssh_role_bootstrap_open(&request, &role) == 0);
            CHECK(ctos_ssh_kex_bind_role(peer, role, fingerprint) == 0);
        }
        remote_version = is_server ? peer->kex->client_version
            : peer->kex->server_version;
        /* Include a turn ending at CR, then retry without new input. Until LF
         * arrives, the packet API must retain input without publishing a
         * partial remote version as a completed identification exchange. */
        for (size_t index = 0; index < sizeof(identification) - 2U; index++) {
            CHECK(ssh_input_append(peer, identification + index, 1U) == 0);
            appended++;
            CHECK(ssh_packet_next(peer, &packet_type) == 0);
            CHECK(packet_type == SSH_MSG_NONE);
            CHECK(sshbuf_len(remote_version) == 0U);
            CHECK(sshbuf_len(ssh_packet_get_input(peer)) == appended);
        }
        CHECK(ssh_packet_next(peer, &packet_type) == 0);
        CHECK(packet_type == SSH_MSG_NONE && sshbuf_len(remote_version) == 0U);
        CHECK(sshbuf_len(ssh_packet_get_input(peer)) == appended);
        CHECK(ssh_input_append(peer, identification + appended, 1U) == 0);
        CHECK(ssh_packet_next(peer, &packet_type) == 0);
        CHECK(packet_type == SSH_MSG_NONE);
        CHECK(sshbuf_len(remote_version) == sizeof(identification) - 3U);
        CHECK(memcmp(sshbuf_ptr(remote_version), identification,
            sizeof(identification) - 3U) == 0);
        CHECK(sshbuf_len(ssh_packet_get_input(peer)) == 0U);
        if (!is_server) {
            ctos_ssh_kex_unbind(peer);
            CHECK(ctos_ssh_role_bootstrap_close(role) == 0);
        }
        ssh_free(peer);
    }
    sshkey_free(public_key);
    sshkey_free(private_key);
}

static void finish_service_fixture(struct fixture *fixture, int new_host,
    int suppress_ext_info) {
    int completed = 0;

    CHECK(ctos_ssh_native_client_userauth_service_ready(fixture->client) == 0);
    errno = 0;
    CHECK(ctos_ssh_native_client_begin_userauth_service(fixture->client) != 0
        && errno == EACCES);
    setup_synthetic_server(fixture);
    fixture->suppress_server_ext_info = suppress_ext_info;
    for (unsigned int attempt = 0; attempt < 20000U; attempt++) {
        if (!ctos_ssh_native_client_complete(fixture->client)) {
            struct ctos_ssh_native_client_step step = {0};

            CHECK(ctos_ssh_native_client_step(fixture->client, &step) == 0);
            CHECK(step.bytes_read <= TEST_STEP_BUDGET
                && step.bytes_written <= TEST_STEP_BUDGET);
            CHECK(step.bytes_read == step.input_appended
                && step.bytes_written == step.output_consumed);
        }
        CHECK(pump_synthetic_server(fixture, suppress_ext_info ? 2U : 64U,
            NULL, NULL) == 0);
        if (ctos_ssh_native_client_complete(fixture->client)
            && fixture->server->kex->done) {
            completed = 1;
            break;
        }
    }
    CHECK(completed);
    for (unsigned int attempt = 0; attempt < 1000U; attempt++) {
        size_t length;

        CHECK(pump_synthetic_server(fixture, 64U, NULL, NULL) == 0);
        (void)ssh_output_ptr(fixture->server, &length);
        if (length == 0U) break;
        CHECK(attempt < 999U);
    }
    CHECK(fixture->callback.kex_complete_calls == 1U);
    CHECK(fixture->callback.last_kex_cycle == 1U);
    CHECK(fixture->callback.host_evidence_calls == 1U);
    CHECK(fixture->callback.last_host_classification == (new_host
        ? CTOS_SSH_CALLBACK_HOST_NEW : CTOS_SSH_CALLBACK_HOST_KNOWN));
    CHECK(fixture->callback.candidate_calls == (new_host ? 1U : 0U));
    CHECK(fixture->callback.candidate_abort_calls == 0U);
    errno = 0;
    CHECK(ctos_ssh_native_client_step(fixture->client,
        &(struct ctos_ssh_native_client_step){0}) != 0 && errno == EALREADY);
    fixture->service_expected = 1;
}

static void setup_service_fixture(struct fixture *fixture, int new_host,
    unsigned int lifetime_ms, int suppress_ext_info) {
    if (new_host) setup_empty_known_hosts_fixture(fixture, lifetime_ms);
    else setup_fixture(fixture, lifetime_ms, NULL);
    finish_service_fixture(fixture, new_host, suppress_ext_info);
}

static void setup_selected_service_fixture(struct fixture *fixture, int new_host,
    unsigned int kind, unsigned int lifetime_ms, int omit_target) {
    memset(fixture, 0, sizeof(*fixture));
    fixture->pair[0] = fixture->pair[1] = -1;
    fixture->callback.release_fd = -1;
    fixture->callback.omit_selected_target = omit_target;
    CHECK(sshkey_generate(KEY_ED25519, 0, &fixture->private_key) == 0);
    CHECK(sshkey_from_private(fixture->private_key, &fixture->public_key) == 0);
    fixture->host_key = fixture->public_key;
    finish_fixture_setup_selected(fixture, lifetime_ms, NULL, NULL, new_host, 0, kind, 0);
    fixture->callback.selected_kind = (int)kind;
    memcpy(fixture->callback.expected_selection_receipt, fixture->selection_receipt, 32U);
    fixture->callback.selected_view = fixture->view;
    fixture->callback.selected_callback = fixture->callback_token;
    fixture->callback.selected_role = fixture->role;
    fixture->callback.selected_deadline = fixture->deadline;
    unsigned char premature[64];
    size_t premature_length = 99;
    memset(premature, 0xa5, sizeof(premature));
    errno = 0;
    CHECK(ctos_ssh_native_client_selected_credential_bytes(fixture->client,
        premature, sizeof(premature), &premature_length) != 0 && errno == EACCES && premature_length == 0);
    for (size_t index = 0; index < sizeof(premature); index++) CHECK(premature[index] == 0);
    CHECK(fixture->callback.selected_calls == 0U);
    finish_service_fixture(fixture, new_host, 0);
}

static void drive_service(struct fixture *fixture, int expected_error) {
    int finished = 0;
    int saw_partial_write = 0;
    unsigned int steps = 0;

    for (unsigned int attempt = 0; attempt < 4000U; attempt++) {
        struct ctos_ssh_native_client_step step = {0};
        int result;

        errno = 0;
        result = ctos_ssh_native_client_step(fixture->client, &step);
        steps++;
        CHECK(step.bytes_read <= TEST_STEP_BUDGET
            && step.bytes_written <= TEST_STEP_BUDGET);
        CHECK(step.bytes_read == step.input_appended
            && step.bytes_written == step.output_consumed);
        CHECK(step.input_consumed == 0U);
        saw_partial_write |= step.partial_write;
        if (result != 0) {
            CHECK(expected_error != 0 && errno == expected_error);
            finished = 1;
            break;
        }
        if (ctos_ssh_native_client_userauth_service_ready(fixture->client)) {
            CHECK(expected_error == 0);
            finished = 1;
            break;
        }
        CHECK(pump_synthetic_server(fixture, 2U, NULL, NULL) == 0);
    }
    CHECK(finished && steps > 1U);
    if (expected_error == 0) CHECK(saw_partial_write);
    CHECK(ctos_ssh_native_client_userauth_service_ready(fixture->client)
        == (expected_error == 0));
    CHECK(ctos_ssh_native_client_complete(fixture->client) == 1);
    CHECK(fixture->callback.kex_complete_calls == 1U);
    CHECK(fixture->callback.credential_calls == 0U
        && fixture->callback.credential_bytes_calls == 0U);
    errno = 0;
    CHECK(ctos_ssh_native_client_begin_userauth_service(fixture->client) != 0
        && errno == EALREADY);
    errno = 0;
    CHECK(ctos_ssh_native_client_step(fixture->client,
        &(struct ctos_ssh_native_client_step){0}) != 0 && errno == EALREADY);
}

static void test_userauth_service_replies(void) {
    errno = 0;
    CHECK(ctos_ssh_native_client_begin_userauth_service(NULL) != 0 && errno == EINVAL);
    CHECK(ctos_ssh_native_client_userauth_service_ready(NULL) == 0);
    for (int new_host = 0; new_host <= 1; new_host++) {
        for (int reply = SERVICE_REPLY_EXACT; reply <= SERVICE_REPLY_KEX_METHOD; reply++) {
            struct fixture fixture;

            setup_service_fixture(&fixture, new_host, 15000U, 0);
            fixture.service_reply = (enum service_reply)reply;
            CHECK(ctos_ssh_native_client_begin_userauth_service(fixture.client) == 0);
            errno = 0;
            CHECK(ctos_ssh_native_client_begin_userauth_service(fixture.client) != 0
                && errno == EALREADY);
            drive_service(&fixture, reply <= SERVICE_REPLY_EMPTY ? 0 : EPROTO);
            CHECK(fixture.service_requests == 1U);
            if (reply <= SERVICE_REPLY_EMPTY) {
                /* A late duplicate remains unread after the ready milestone. */
                send_service_reply(&fixture);
                CHECK(pump_synthetic_server(&fixture, 64U, NULL, NULL) == 0);
                struct ctos_ssh_native_client_step step = {0};
                errno = 0;
                CHECK(ctos_ssh_native_client_step(fixture.client, &step) != 0
                    && errno == EALREADY && step.bytes_read == 0U
                    && step.bytes_written == 0U);
                CHECK(fixture.service_requests == 1U);
            }
            cleanup_fixture(&fixture, 1);
        }
    }
}

static void test_userauth_service_early_accept(void) {
    struct fixture fixture;

    /* This peer omits optional EXT_INFO so its unsolicited empty accept is
     * the first encrypted post-KEX packet, while our request is still queued. */
    setup_service_fixture(&fixture, 0, 15000U, 1);
    CHECK(fixture.server->kex->ext_info_c == 0);
    fixture.service_reply = SERVICE_REPLY_MISSING;
    send_service_reply(&fixture);
    CHECK(pump_synthetic_server(&fixture, 64U, NULL, NULL) == 0);
    CHECK(ctos_ssh_native_client_begin_userauth_service(fixture.client) == 0);
    drive_service(&fixture, EPROTO);
    CHECK(fixture.service_requests == 0U);
    cleanup_fixture(&fixture, 1);
}

static void test_userauth_service_owner_and_cleanup(void) {
    struct fixture fixture;
    int alien_environment;

    setup_service_fixture(&fixture, 0, 15000U, 0);
    fixture.callback.environment = &alien_environment;
    errno = 0;
    CHECK(ctos_ssh_native_client_begin_userauth_service(fixture.client) != 0
        && errno == EPERM);
    fixture.callback.environment = &fixture.callback;
    CHECK(ctos_ssh_native_client_begin_userauth_service(fixture.client) == 0);
    fixture.callback.environment = &alien_environment;
    struct ctos_ssh_native_client_step step = {.cancelled = 1};
    errno = 0;
    CHECK(ctos_ssh_native_client_step(fixture.client, &step) != 0 && errno == EPERM
        && step.bytes_read == 0U && step.bytes_written == 0U);
    fixture.callback.environment = &fixture.callback;
    drive_service(&fixture, 0);
    CHECK(fixture.service_requests == 1U);
    fixture.callback.release_failures_remaining = 1U;
    errno = 0;
    CHECK(ctos_ssh_native_client_close(fixture.client) != 0 && errno == EIO);
    CHECK(fcntl(fixture.pair[0], F_GETFD) >= 0);
    cleanup_fixture(&fixture, 2);
}

static void test_userauth_service_liveness(void) {
    for (int active_step = 0; active_step <= 1; active_step++) {
        for (unsigned int failure = 0; failure < 5U; failure++) {
            struct fixture fixture;
            int expected_error = EACCES;

            setup_service_fixture(&fixture, 0,
                failure == 4U ? TEST_EXPIRY_PREPARATION_MS : 15000U, 0);
            if (active_step)
                CHECK(ctos_ssh_native_client_begin_userauth_service(fixture.client) == 0);
            switch (failure) {
                case 0:
                    CHECK(ctos_ssh_callback_cancel(fixture.callback_token) == 0);
                    expected_error = ECANCELED;
                    break;
                case 1:
                    CHECK(ctos_ssh_role_bootstrap_close(fixture.role) == 0);
                    fixture.role = 0;
                    expected_error = ECANCELED;
                    break;
                case 2:
                    fixture.view->valid = 0;
                    break;
                case 3:
                    fixture.view->deadline = deadline_after(2U);
                    wait_until_deadline(fixture.view->deadline);
                    expected_error = ETIMEDOUT;
                    break;
                case 4:
                    wait_until_deadline_attempts(fixture.deadline, TEST_EXPIRY_WAIT_ATTEMPTS);
                    expected_error = ETIMEDOUT;
                    break;
            }
            errno = 0;
            if (active_step) {
                struct ctos_ssh_native_client_step step = {0};
                CHECK(ctos_ssh_native_client_step(fixture.client, &step) != 0
                    && errno == expected_error && step.bytes_read == 0U
                    && step.bytes_written == 0U);
            } else {
                CHECK(ctos_ssh_native_client_begin_userauth_service(fixture.client) != 0
                    && errno == expected_error);
            }
            errno = 0;
            CHECK(ctos_ssh_native_client_step(fixture.client,
                &(struct ctos_ssh_native_client_step){0}) != 0 && errno == EALREADY);
            CHECK(!ctos_ssh_native_client_userauth_service_ready(fixture.client));
            CHECK(fixture.service_requests == 0U);
            if (failure == 4U) {
                errno = 0;
                CHECK(ctos_ssh_role_bootstrap_close(fixture.role) != 0 && errno == ENOENT);
                fixture.role = 0;
            }
            cleanup_fixture(&fixture, 1);
        }
    }
    struct fixture cancelled;
    setup_service_fixture(&cancelled, 0, 15000U, 0);
    CHECK(ctos_ssh_native_client_begin_userauth_service(cancelled.client) == 0);
    errno = 0;
    CHECK(ctos_ssh_native_client_step(cancelled.client,
        &(struct ctos_ssh_native_client_step){.cancelled = 1}) != 0 && errno == ECANCELED);
    cleanup_fixture(&cancelled, 1);
}

static void test_kex_completion_host_proof(size_t server_chunk) {
    struct fixture fixture;
    unsigned char managed_digest[32] = {0};
    unsigned char expected_kex_digest[32] = {0};
    unsigned char digest_combined = 0;
    unsigned int step_calls = 0;
    int server_read_would_block = 0;
    int server_partial_write = 0;
    int saw_client_read_would_block = 0;
    int saw_client_partial_write = 0;
    int completed = 0;

    /* The managed view already contains the exact generated public key. The
     * peer below is only an in-process OpenSSH server on the socketpair; it
     * has no endpoint, vault, device or credential dependency. */
    if (server_chunk == 64U) setup_selected_fixture(&fixture, 15000U, 4U, 0, 0);
    else setup_fixture(&fixture, 15000U, NULL);
    setup_synthetic_server(&fixture);
    for (unsigned int attempt = 0; attempt < 20000U; attempt++) {
        struct ctos_ssh_native_client_step step = {0};

        CHECK(ctos_ssh_native_client_step(fixture.client, &step) == 0);
        step_calls++;
        CHECK(step.bytes_read <= TEST_STEP_BUDGET);
        CHECK(step.input_appended == step.bytes_read);
        CHECK(step.input_consumed == 0U);
        CHECK(step.bytes_written <= TEST_STEP_BUDGET);
        CHECK(step.output_consumed == step.bytes_written);
        CHECK(step.packet_type == SSH_MSG_NONE
            || step.packet_type == SSH2_MSG_EXT_INFO);
        saw_client_read_would_block |= step.read_would_block;
        saw_client_partial_write |= step.partial_write;
        if (ctos_ssh_native_client_complete(fixture.client)) {
            CHECK(step.partial_write == 0 && step.write_would_block == 0);
        }
        CHECK(pump_synthetic_server(&fixture, server_chunk, &server_read_would_block,
            &server_partial_write) == 0);
        CHECK(fixture.server != NULL && fixture.server->kex != NULL);

        if (ctos_ssh_native_client_complete(fixture.client)
            && fixture.server->kex->done) {
            completed = 1;
            break;
        }
        /* A client completion must not be followed by another production
         * step: finish any server-side packet/output turn first, then make
         * the explicit post-completion EALREADY assertion below. */
        if (ctos_ssh_native_client_complete(fixture.client)) {
            for (unsigned int server_attempt = 0;
                server_attempt < 20000U && !fixture.server->kex->done;
                server_attempt++) {
                CHECK(pump_synthetic_server(&fixture, server_chunk,
                    &server_read_would_block,
                    &server_partial_write) == 0);
            }
            if (fixture.server->kex->done) completed = 1;
            break;
        }
    }
    CHECK(step_calls > 0U);
    CHECK(completed == 1);
    CHECK(ctos_ssh_native_client_complete(fixture.client) == 1);
    CHECK(fixture.server != NULL && fixture.server->kex != NULL
        && fixture.server->kex->done);
    /* Keep one no-input server turn explicit: the peer pump must remain
     * progress-neutral after a nonblocking EAGAIN, rather than treating the
     * absence of a new fragment as EOF or inventing packet input. */
    CHECK(pump_synthetic_server(&fixture, server_chunk, &server_read_would_block,
        &server_partial_write) == 0);
    CHECK(saw_client_read_would_block == 1);
    CHECK(saw_client_partial_write == 1);
    CHECK(server_read_would_block == 1);
    CHECK(server_partial_write == 1);
    if (server_chunk > TEST_STEP_BUDGET) {
        CHECK(fixture.server->kex->ext_info_received == 1);
    }

    /* The production verifier must have admitted the exact managed key as a
     * complete KNOWN result before the real inbound NEWKEYS completion. */
    CHECK(fixture.callback.host_evidence_calls == 1);
    CHECK(fixture.callback.candidate_calls == 0);
    CHECK(fixture.callback.candidate_abort_calls == 0);
    CHECK(fixture.callback.host_evidence_event_order == 1U);
    CHECK(fixture.callback.kex_complete_calls == 1);
    CHECK(fixture.callback.kex_event_order == 2U);
    CHECK(fixture.callback.callback_event_order == 2U);
    CHECK(fixture.callback.last_host_role == fixture.role);
    CHECK(fixture.callback.last_host_hop == 0U);
    CHECK(fixture.callback.last_host_port == 22U);
    CHECK(fixture.callback.last_host_classification
        == CTOS_SSH_CALLBACK_HOST_KNOWN);
    CHECK(fixture.callback.last_host_outcome
        == CTOS_SSH_CALLBACK_HOST_RESULT_APPROVED);
    CHECK(fixture.callback.last_host_cas_status
        == CTOS_SSH_CALLBACK_HOST_CAS_NOT_REQUIRED);
    CHECK(strcmp(fixture.callback.last_key_type,
        fixture.callback.expected_key_type) == 0);
    CHECK(strcmp(fixture.callback.last_fingerprint,
        fixture.callback.expected_fingerprint) == 0);
    managed_known_hosts_digest(fixture.view, managed_digest);
    CHECK(memcmp(fixture.callback.last_host_base_digest, managed_digest,
        sizeof(managed_digest)) == 0);
    CHECK(memcmp(fixture.callback.last_host_replacement_digest, managed_digest,
        sizeof(managed_digest)) == 0);
    CHECK(fixture.callback.last_kex_cycle == 1U);
    for (size_t index = 0; index < sizeof(fixture.callback.last_kex_digest);
        index++) digest_combined |= fixture.callback.last_kex_digest[index];
    CHECK(digest_combined != 0);
    CHECK(ssh_digest_buffer(SSH_DIGEST_SHA256, fixture.server->kex->session_id,
        expected_kex_digest, sizeof(expected_kex_digest)) == 0);
    CHECK(memcmp(fixture.callback.last_kex_digest, expected_kex_digest,
        sizeof(expected_kex_digest)) == 0);

    errno = 0;
    CHECK(ctos_ssh_native_client_step(fixture.client,
        &(struct ctos_ssh_native_client_step){0}) != 0 && errno == EALREADY);
    CHECK(ctos_ssh_native_client_complete(fixture.client) == 1);
    if (server_chunk == 64U) {
        unsigned char receipt[32];
        CHECK(ctos_ssh_native_client_authentication_selection_receipt(fixture.client, receipt) == 0);
        CHECK(memcmp(receipt, fixture.selection_receipt, 32U) == 0);
        CHECK(fixture.callback.kex_complete_calls == 1U && fixture.callback.host_evidence_calls == 1U);
    }
    cleanup_fixture(&fixture, 1);
}

static void test_kex_completion_new_host_proof(size_t server_chunk) {
    struct fixture fixture;
    unsigned char expected_kex_digest[32] = {0};
    unsigned char digest_combined = 0;
    unsigned int step_calls = 0;
    int server_read_would_block = 0;
    int server_partial_write = 0;
    int saw_client_read_would_block = 0;
    int saw_client_partial_write = 0;
    int completed = 0;

    /* An empty managed known_hosts object forces the real NEW candidate/CAS
     * handoff while the peer remains an in-process nonblocking OpenSSH server. */
    setup_empty_known_hosts_fixture(&fixture, 15000U);
    setup_synthetic_server(&fixture);
    for (unsigned int attempt = 0; attempt < 20000U; attempt++) {
        struct ctos_ssh_native_client_step step = {0};

        CHECK(ctos_ssh_native_client_step(fixture.client, &step) == 0);
        step_calls++;
        CHECK(step.bytes_read <= TEST_STEP_BUDGET);
        CHECK(step.input_appended == step.bytes_read);
        CHECK(step.input_consumed == 0U);
        CHECK(step.bytes_written <= TEST_STEP_BUDGET);
        CHECK(step.output_consumed == step.bytes_written);
        CHECK(step.packet_type == SSH_MSG_NONE
            || step.packet_type == SSH2_MSG_EXT_INFO);
        saw_client_read_would_block |= step.read_would_block;
        saw_client_partial_write |= step.partial_write;
        if (ctos_ssh_native_client_complete(fixture.client)) {
            CHECK(step.partial_write == 0 && step.write_would_block == 0);
        }
        CHECK(pump_synthetic_server(&fixture, server_chunk, &server_read_would_block,
            &server_partial_write) == 0);
        CHECK(fixture.server != NULL && fixture.server->kex != NULL);

        if (ctos_ssh_native_client_complete(fixture.client)
            && fixture.server->kex->done) {
            completed = 1;
            break;
        }
        /* Let the peer finish its own bounded output turn before making the
         * post-completion client EALREADY assertion below. */
        if (ctos_ssh_native_client_complete(fixture.client)) {
            for (unsigned int server_attempt = 0;
                server_attempt < 20000U && !fixture.server->kex->done;
                server_attempt++) {
                CHECK(pump_synthetic_server(&fixture, server_chunk,
                    &server_read_would_block,
                    &server_partial_write) == 0);
            }
            if (fixture.server->kex->done) completed = 1;
            break;
        }
    }
    CHECK(step_calls > 0U);
    CHECK(completed == 1);
    CHECK(ctos_ssh_native_client_complete(fixture.client) == 1);
    CHECK(fixture.server != NULL && fixture.server->kex != NULL
        && fixture.server->kex->done);
    /* An extra server turn must observe EAGAIN without fabricating input;
     * bounded response fragments also force a short write. */
    CHECK(pump_synthetic_server(&fixture, server_chunk, &server_read_would_block,
        &server_partial_write) == 0);
    CHECK(saw_client_read_would_block == 1);
    CHECK(saw_client_partial_write == 1);
    CHECK(server_read_would_block == 1);
    CHECK(server_partial_write == 1);
    if (server_chunk > TEST_STEP_BUDGET) {
        CHECK(fixture.server->kex->ext_info_received == 1);
    }

    /* NEW proof has one exact candidate handoff, one approved/committed
     * evidence result, and one KEX completion in that order. */
    CHECK(fixture.callback.candidate_calls == 1U);
    CHECK(fixture.callback.host_evidence_calls == 1U);
    CHECK(fixture.callback.candidate_abort_calls == 0U);
    CHECK(fixture.callback.last_host_classification
        == CTOS_SSH_CALLBACK_HOST_NEW);
    CHECK(fixture.callback.last_host_outcome
        == CTOS_SSH_CALLBACK_HOST_RESULT_APPROVED);
    CHECK(fixture.callback.last_host_cas_status
        == CTOS_SSH_CALLBACK_HOST_CAS_COMMITTED);
    CHECK(fixture.callback.candidate_event_order == 1U);
    CHECK(fixture.callback.host_evidence_event_order == 2U);
    CHECK(fixture.callback.kex_complete_calls == 1U);
    CHECK(fixture.callback.kex_event_order == 3U);
    CHECK(fixture.callback.callback_event_order == 3U);
    CHECK(fixture.callback.last_host_role == fixture.role);
    CHECK(fixture.callback.last_host_hop == 0U);
    CHECK(fixture.callback.last_host_port == 22U);
    CHECK(fixture.callback.last_candidate_classification
        == CTOS_SSH_CALLBACK_HOST_NEW);
    CHECK(strcmp(fixture.callback.last_candidate_id,
        "native-candidate") == 0);
    CHECK(strcmp(fixture.callback.last_candidate_key_type,
        fixture.callback.expected_key_type) == 0);
    CHECK(strcmp(fixture.callback.last_candidate_fingerprint,
        fixture.callback.expected_fingerprint) == 0);
    assert_new_candidate_metadata(&fixture);

    CHECK(fixture.callback.last_kex_cycle == 1U);
    for (size_t index = 0; index < sizeof(fixture.callback.last_kex_digest);
        index++) digest_combined |= fixture.callback.last_kex_digest[index];
    CHECK(digest_combined != 0);
    CHECK(ssh_digest_buffer(SSH_DIGEST_SHA256, fixture.server->kex->session_id,
        expected_kex_digest, sizeof(expected_kex_digest)) == 0);
    CHECK(memcmp(fixture.callback.last_kex_digest, expected_kex_digest,
        sizeof(expected_kex_digest)) == 0);

    errno = 0;
    CHECK(ctos_ssh_native_client_step(fixture.client,
        &(struct ctos_ssh_native_client_step){0}) != 0 && errno == EALREADY);
    CHECK(ctos_ssh_native_client_complete(fixture.client) == 1);
    cleanup_fixture(&fixture, 1);
}

static void test_verify_new_host_key_candidate(void) {
    struct fixture fixture;

    setup_empty_known_hosts_fixture(&fixture, 15000U);
    errno = 0;
    CHECK(ctos_ssh_native_client_verify_host_key(fixture.client,
        fixture.public_blob, fixture.public_blob_length) == 0);
    CHECK(fixture.callback.candidate_calls == 1);
    CHECK(fixture.callback.host_evidence_calls == 1);
    CHECK(fixture.callback.candidate_abort_calls == 0);
    CHECK(fixture.callback.callback_event_order == 2U);
    CHECK(fixture.callback.candidate_event_order == 1U);
    CHECK(fixture.callback.host_evidence_event_order == 2U);
    CHECK(fixture.callback.last_host_classification
        == CTOS_SSH_CALLBACK_HOST_NEW);
    CHECK(fixture.callback.last_host_cas_status
        == CTOS_SSH_CALLBACK_HOST_CAS_COMMITTED);
    assert_new_candidate_metadata(&fixture);
    cleanup_fixture(&fixture, 1);
}

static void test_verify_new_host_key_candidate_rollback(void) {
    struct fixture fixture;
    unsigned int candidate_calls;
    unsigned int host_evidence_calls;

    setup_empty_known_hosts_fixture(&fixture, 15000U);
    fixture.callback.fail_host_evidence = 1;
    errno = 0;
    CHECK(ctos_ssh_native_client_verify_host_key(fixture.client,
        fixture.public_blob, fixture.public_blob_length) != 0
        && errno == EACCES);
    CHECK(fixture.callback.candidate_calls == 1);
    CHECK(fixture.callback.host_evidence_calls == 1);
    CHECK(fixture.callback.candidate_abort_calls == 1);
    CHECK(fixture.callback.callback_event_order == 2U);
    CHECK(fixture.callback.candidate_event_order == 1U);
    CHECK(fixture.callback.host_evidence_event_order == 2U);
    CHECK(fixture.callback.last_host_classification
        == CTOS_SSH_CALLBACK_HOST_NEW);
    assert_new_candidate_metadata(&fixture);

    /* Host-evidence rejection terminalizes the native client after candidate
     * rollback; no later transport turn may make progress. */
    errno = 0;
    CHECK(ctos_ssh_native_client_step(fixture.client,
        &(struct ctos_ssh_native_client_step){0}) != 0 && errno == EALREADY);

    /* A failed evidence callback leaves the binding failed and the managed
     * candidate already rolled back. A later verifier attempt must not
     * invent another target callback or a second abort. */
    candidate_calls = fixture.callback.candidate_calls;
    host_evidence_calls = fixture.callback.host_evidence_calls;
    errno = 0;
    CHECK(ctos_ssh_native_client_verify_host_key(fixture.client,
        fixture.public_blob, fixture.public_blob_length) != 0);
    CHECK(fixture.callback.candidate_calls == candidate_calls);
    CHECK(fixture.callback.host_evidence_calls == host_evidence_calls);
    CHECK(fixture.callback.candidate_abort_calls == 1);

    /* Exercise the existing close retry contract after rollback. */
    fixture.callback.release_failures_remaining = 1;
    errno = 0;
    CHECK(ctos_ssh_native_client_close(fixture.client) != 0 && errno == EIO);
    CHECK(fixture.callback.release_calls == 1);
    CHECK(fixture.callback.release_saw_fd_open == 1);
    CHECK(fcntl(fixture.pair[0], F_GETFD) >= 0);
    cleanup_fixture(&fixture, 2);
}

static void test_verify_changed_host_key_classification(void) {
    struct fixture fixture;
    struct ctos_ssh_hostfile_issue issue = {0};
    struct sshkey *known_hosts_private_key = NULL;
    struct sshkey *known_hosts_public_key = NULL;
    unsigned char known_hosts_fingerprint[32];

    CHECK(sshkey_generate(KEY_ED25519, 0, &known_hosts_private_key) == 0);
    CHECK(sshkey_from_private(known_hosts_private_key, &known_hosts_public_key) == 0);
    CHECK(expected_fingerprint(known_hosts_public_key, known_hosts_fingerprint) == 0);
    setup_fixture(&fixture, 15000U, known_hosts_public_key);
    CHECK(memcmp(known_hosts_fingerprint, fixture.fingerprint,
        sizeof(known_hosts_fingerprint)) != 0);

    /* Prove the managed view really contains a different key before checking
     * the native verifier. The issuer must classify the offered, pinned key
     * as CHANGED without handing off a candidate or invoking host evidence. */
    errno = 0;
    CHECK(ctos_ssh_hostfile_issue(fixture.view, fixture.role,
        "ssh/known_hosts", "example.invalid", fixture.public_key,
        "native-candidate", NULL, NULL, 0, fixture.callback_token,
        fixture.callback.environment, &issue) == 0);
    CHECK(issue.classification == CTOS_SSH_CALLBACK_HOST_CHANGED);
    CHECK(issue.handed_off == 0 && issue.replacement_body == NULL
        && issue.replacement_body_length == 0);
    CHECK(strcmp(issue.key_type, fixture.callback.expected_key_type) == 0);
    CHECK(strcmp(issue.fingerprint, fixture.callback.expected_fingerprint) == 0);
    assert_issue_digest_metadata(fixture.view, &issue);
    CHECK(fixture.callback.host_evidence_calls == 0);

    /* The opened client still verifies the same offered key whose pinned
     * fingerprint was admitted during setup. Production verification must
     * therefore reach the issuer's CHANGED result and deny before evidence. */
    memset(&issue, 0, sizeof(issue));
    errno = 0;
    CHECK(ctos_ssh_native_client_verify_host_key(fixture.client,
        fixture.public_blob, fixture.public_blob_length) != 0
        && errno == EACCES);
    CHECK(fixture.callback.host_evidence_calls == 0);

    cleanup_fixture(&fixture, 1);
    sshkey_free(known_hosts_public_key);
    sshkey_free(known_hosts_private_key);
}

static void test_verify_revoked_host_key_classification(void) {
    struct fixture fixture;
    struct ctos_ssh_hostfile_issue issue = {0};

    setup_revoked_fixture(&fixture, 15000U);

    /* The managed body marks the same offered, pinned key as revoked. The
     * issuer must classify that policy directly without candidate handoff or
     * host evidence. */
    errno = 0;
    CHECK(ctos_ssh_hostfile_issue(fixture.view, fixture.role,
        "ssh/known_hosts", "example.invalid", fixture.public_key,
        "native-candidate", NULL, NULL, 0, fixture.callback_token,
        fixture.callback.environment, &issue) == 0);
    CHECK(issue.classification == CTOS_SSH_CALLBACK_HOST_REVOKED);
    CHECK(issue.handed_off == 0 && issue.replacement_body == NULL
        && issue.replacement_body_length == 0);
    CHECK(strcmp(issue.key_type, fixture.callback.expected_key_type) == 0);
    CHECK(strcmp(issue.fingerprint, fixture.callback.expected_fingerprint) == 0);
    assert_issue_digest_metadata(fixture.view, &issue);
    CHECK(fixture.callback.host_evidence_calls == 0);

    /* The opened client still presents the same offered key admitted by the
     * pinned fingerprint guard. Production verification must deny the
     * issuer's REVOKED result before invoking host evidence. */
    memset(&issue, 0, sizeof(issue));
    errno = 0;
    CHECK(ctos_ssh_native_client_verify_host_key(fixture.client,
        fixture.public_blob, fixture.public_blob_length) != 0
        && errno == EACCES);
    CHECK(fixture.callback.host_evidence_calls == 0);

    cleanup_fixture(&fixture, 1);
}

static void test_verify_certificate_host_key_classification(void) {
    struct fixture fixture;
    struct ctos_ssh_hostfile_issue issue = {0};

    setup_certificate_fixture(&fixture, 15000U);

    /* The offered key is a valid OpenSSH host certificate. The issuer must
     * expose the project policy denial directly, with bounded metadata and
     * no candidate handoff or host evidence. */
    errno = 0;
    CHECK(ctos_ssh_hostfile_issue(fixture.view, fixture.role,
        "ssh/known_hosts", "example.invalid", fixture.host_key,
        "native-candidate", NULL, NULL, 0, fixture.callback_token,
        fixture.callback.environment, &issue) == 0);
    CHECK(issue.classification == CTOS_SSH_CALLBACK_HOST_CERTIFICATE);
    CHECK(issue.handed_off == 0 && issue.replacement_body == NULL
        && issue.replacement_body_length == 0);
    CHECK(strcmp(issue.key_type, fixture.callback.expected_key_type) == 0);
    CHECK(strcmp(issue.fingerprint, fixture.callback.expected_fingerprint) == 0);
    assert_issue_digest_metadata(fixture.view, &issue);
    CHECK(fixture.callback.host_evidence_calls == 0);

    /* The production verifier must reject the policy classification before
     * invoking the managed host-evidence callback. */
    memset(&issue, 0, sizeof(issue));
    errno = 0;
    CHECK(ctos_ssh_native_client_verify_host_key(fixture.client,
        fixture.public_blob, fixture.public_blob_length) != 0
        && errno == EACCES);
    CHECK(fixture.callback.host_evidence_calls == 0);

    cleanup_fixture(&fixture, 1);
}

static void test_verify_invalid_hostfile_classification(void) {
    static const char invalid_line[] = "example.invalid ssh-ed25519\n";
    struct fixture fixture;
    struct ctos_ssh_hostfile_issue issue = {0};

    /* Keep a valid offered/pinned key after a deliberately truncated managed
     * record. The issuer's preflight must distinguish this parse failure from
     * a legal no-match NEW result. */
    setup_fixture_with_known_hosts_prefix(&fixture, 15000U, NULL,
        invalid_line);
    errno = 0;
    CHECK(ctos_ssh_hostfile_issue(fixture.view, fixture.role,
        "ssh/known_hosts", "example.invalid", fixture.host_key,
        "native-candidate", NULL, NULL, 0, fixture.callback_token,
        fixture.callback.environment, &issue) == 0);
    CHECK(issue.classification == CTOS_SSH_CALLBACK_HOST_INVALID);
    CHECK(issue.handed_off == 0 && issue.replacement_body == NULL
        && issue.replacement_body_length == 0);
    CHECK(strcmp(issue.key_type, fixture.callback.expected_key_type) == 0);
    CHECK(strcmp(issue.fingerprint, fixture.callback.expected_fingerprint) == 0);
    assert_issue_digest_metadata(fixture.view, &issue);
    CHECK(fixture.callback.host_evidence_calls == 0);

    /* INVALID is a project-owned fail-closed classification: production
     * verification must deny before any host-evidence callback. */
    memset(&issue, 0, sizeof(issue));
    errno = 0;
    CHECK(ctos_ssh_native_client_verify_host_key(fixture.client,
        fixture.public_blob, fixture.public_blob_length) != 0
        && errno == EACCES);
    CHECK(fixture.callback.host_evidence_calls == 0);

    cleanup_fixture(&fixture, 1);
}

static void test_step_fragmented_banner_output(void) {
    static const unsigned char server_banner[] =
        "SSH-2.0-synthetic-server\r\n";
    struct fixture fixture;
    unsigned char peer_output[16384] = {0};
    size_t banner_sent = 0;
    size_t banner_received = 0;
    size_t input_appended = 0;
    size_t output_sent = 0;
    size_t output_consumed = 0;
    size_t peer_output_length = 0;
    size_t local_banner_end = 0;
    uint32_t kex_packet_length = 0;
    int kex_packet_complete = 0;
    int saw_partial_write = 0;
    unsigned int banner_steps = 0;

    setup_fixture(&fixture, 15000U, NULL);
    CHECK(sizeof(server_banner) - 1U > TEST_STEP_BUDGET);
    for (unsigned int attempt = 0; attempt < 2000U; attempt++) {
        struct ctos_ssh_native_client_step step = {0};

        if (banner_sent < sizeof(server_banner) - 1U) {
            size_t fragment = sizeof(server_banner) - 1U - banner_sent;
            ssize_t sent;

            if (fragment > TEST_STEP_BUDGET) fragment = TEST_STEP_BUDGET;
            sent = send(fixture.pair[1], server_banner + banner_sent,
                fragment, MSG_NOSIGNAL);
            CHECK(sent == (ssize_t)fragment);
            banner_sent += fragment;
        }
        CHECK(ctos_ssh_native_client_step(fixture.client, &step) == 0);
        CHECK(step.packet_type == SSH_MSG_NONE);
        CHECK(step.bytes_read <= TEST_STEP_BUDGET);
        CHECK(step.input_appended == step.bytes_read);
        CHECK(step.input_consumed == 0U);
        CHECK(banner_received + step.bytes_read <= banner_sent);
        banner_received += step.bytes_read;
        input_appended += step.input_appended;
        if (step.bytes_read != 0U) banner_steps++;
        CHECK(step.bytes_written <= TEST_STEP_BUDGET);
        CHECK(step.output_consumed == step.bytes_written);
        output_sent += step.bytes_written;
        output_consumed += step.output_consumed;
        if (step.bytes_written != 0U) {
            CHECK(banner_received == sizeof(server_banner) - 1U);
        }
        if (step.partial_write != 0) {
            CHECK(step.bytes_written == TEST_STEP_BUDGET);
            saw_partial_write = 1;
        }
        collect_peer_output(&fixture, peer_output, &peer_output_length,
            sizeof(peer_output));
        if (local_banner_end == 0) {
            for (size_t index = 0; index + 1U < peer_output_length; index++) {
                if (peer_output[index] == '\r'
                    && peer_output[index + 1U] == '\n') {
                    local_banner_end = index + 2U;
                    break;
                }
            }
        }
        if (local_banner_end != 0 && !kex_packet_complete
            && peer_output_length >= local_banner_end + 4U) {
            size_t packet_start = local_banner_end;

            CHECK(packet_start <= sizeof(peer_output) - 4U);
            kex_packet_length = read_u32(peer_output + packet_start);
            CHECK(kex_packet_length >= 16U
                && kex_packet_length <= sizeof(peer_output) - packet_start - 4U);
            if (peer_output_length >= packet_start + 4U + kex_packet_length) {
                unsigned int padding_length = peer_output[packet_start + 4U];

                CHECK(padding_length >= 4U && padding_length <= 255U);
                CHECK(kex_packet_length > padding_length + 1U);
                CHECK(peer_output[packet_start + 5U] == SSH2_MSG_KEXINIT);
                kex_packet_complete = 1;
            }
        }
        if (saw_partial_write != 0 && kex_packet_complete) break;
    }
    CHECK(banner_sent == sizeof(server_banner) - 1U);
    CHECK(banner_received == banner_sent);
    CHECK(banner_steps > 1U);
    CHECK(input_appended == banner_received);
    CHECK(output_sent == output_consumed);
    CHECK(saw_partial_write == 1);
    CHECK(kex_packet_complete == 1);
    CHECK(output_sent != 0U && peer_output_length == output_sent);
    CHECK(local_banner_end > sizeof("SSH-2.0-") - 1U);
    CHECK(peer_output_length == local_banner_end + 4U + kex_packet_length);
    CHECK(peer_output_length >= sizeof("SSH-2.0-") - 1U
        && memcmp(peer_output, "SSH-2.0-", sizeof("SSH-2.0-") - 1U) == 0);
    cleanup_fixture(&fixture, 1);
}

static void test_step_half_close(void) {
    struct fixture fixture;
    struct ctos_ssh_native_client_step step;

    setup_fixture(&fixture, 15000U, NULL);
    CHECK(shutdown(fixture.pair[1], SHUT_WR) == 0);
    step = (struct ctos_ssh_native_client_step){0};
    errno = 0;
    CHECK(ctos_ssh_native_client_step(fixture.client, &step) != 0
        && errno == EPIPE && step.bytes_read == 0
        && step.input_appended == 0);
    step = (struct ctos_ssh_native_client_step){0};
    errno = 0;
    CHECK(ctos_ssh_native_client_step(fixture.client, &step) != 0
        && errno == EALREADY);
    cleanup_fixture(&fixture, 1);
}

static void test_step_expired_view_preserves_errno(void) {
    struct fixture fixture;
    struct ctos_ssh_native_client_step step;

    setup_fixture(&fixture, 15000U, NULL);
    /* Admission requires matching deadlines; changing only the synthetic
     * borrowed view after open isolates the live-borrow error from the client
     * deadline check. */
    fixture.view->deadline = deadline_after(2U);
    wait_until_deadline(fixture.view->deadline);
    step = (struct ctos_ssh_native_client_step){0};
    errno = 0;
    CHECK(ctos_ssh_native_client_step(fixture.client, &step) != 0
        && errno == ETIMEDOUT && step.bytes_read == 0
        && step.input_appended == 0);
    step = (struct ctos_ssh_native_client_step){0};
    errno = 0;
    CHECK(ctos_ssh_native_client_step(fixture.client, &step) != 0
        && errno == EALREADY);
    cleanup_fixture(&fixture, 1);
}

static void test_step_deadline(void) {
    struct fixture fixture;
    struct ctos_ssh_native_client_step step;

    setup_fixture(&fixture, 500U, NULL);
    /* Keep the managed view live so this case covers the client deadline
     * branch separately from the expired-view regression above. */
    fixture.view->deadline = deadline_after(5000U);
    wait_until_deadline(fixture.deadline);
    step = (struct ctos_ssh_native_client_step){0};
    errno = 0;
    CHECK(ctos_ssh_native_client_step(fixture.client, &step) != 0
        && errno == ETIMEDOUT && step.bytes_read == 0
        && step.input_appended == 0);
    step = (struct ctos_ssh_native_client_step){0};
    errno = 0;
    CHECK(ctos_ssh_native_client_step(fixture.client, &step) != 0
        && errno == EALREADY);
    cleanup_fixture(&fixture, 1);
}

static void test_step_invalid_view_preserves_errno(void) {
    struct fixture fixture;
    struct ctos_ssh_native_client_step step;

    setup_fixture(&fixture, 15000U, NULL);
    /* This models revoke/invalidity after open while the native borrow still
     * exists; the view borrow must retain EACCES and terminalize the client. */
    fixture.view->valid = 0;
    step = (struct ctos_ssh_native_client_step){0};
    errno = 0;
    CHECK(ctos_ssh_native_client_step(fixture.client, &step) != 0
        && errno == EACCES && step.bytes_read == 0
        && step.input_appended == 0);
    step = (struct ctos_ssh_native_client_step){0};
    errno = 0;
    CHECK(ctos_ssh_native_client_step(fixture.client, &step) != 0
        && errno == EALREADY);
    cleanup_fixture(&fixture, 1);
}

static int zero_receipt(const unsigned char receipt[32]) {
    unsigned char combined = 0;
    for (size_t index = 0; index < 32U; index++) combined |= receipt[index];
    return combined == 0;
}

static void assert_selection_receipt(const struct fixture *fixture) {
    unsigned char receipt[32];
    unsigned int events = fixture->callback.callback_event_order;
    unsigned char byte;

    memset(receipt, 0x55, sizeof(receipt));
    CHECK(ctos_ssh_native_client_authentication_selection_receipt(fixture->client, receipt) == 0);
    CHECK(memcmp(receipt, fixture->selection_receipt, sizeof(receipt)) == 0);
    CHECK(fixture->callback.callback_event_order == events);
    CHECK(fixture->callback.credential_calls == 0U && fixture->callback.credential_bytes_calls == 0U);
    if (fixture->server == NULL) {
        errno = 0;
        CHECK(recv(fixture->pair[1], &byte, 1U, MSG_PEEK | MSG_DONTWAIT) < 0
            && (errno == EAGAIN || errno == EWOULDBLOCK));
    }
}

static void expect_receipt_error(struct fixture *fixture, int expected_error) {
    unsigned char receipt[32];
    unsigned int events = fixture->callback.callback_event_order;
    memset(receipt, 0x55, sizeof(receipt));
    errno = 0;
    CHECK(ctos_ssh_native_client_authentication_selection_receipt(fixture->client, receipt) != 0
        && errno == expected_error && zero_receipt(receipt));
    CHECK(fixture->callback.callback_event_order == events);
    CHECK(fixture->callback.credential_calls == 0U && fixture->callback.credential_bytes_calls == 0U);
}

static void test_selection_admission_and_owned_receipt(void) {
    unsigned char receipt[32];
    memset(receipt, 0x55, sizeof(receipt));
    errno = 0;
    CHECK(ctos_ssh_native_client_authentication_selection_receipt(NULL, receipt) != 0
        && errno == EINVAL && zero_receipt(receipt));
    for (unsigned int kind = 1U; kind <= 4U; kind += 3U) {
        struct fixture fixture;
        setup_selected_fixture(&fixture, 15000U, kind, 1, 1);
        assert_selection_receipt(&fixture);
        /* Every byte in the caller's storage may change after successful open. */
        memset(fixture.selection_wire.bytes, 0x55, fixture.selection_wire.length);
        assert_selection_receipt(&fixture);
        errno = 0;
        CHECK(ctos_ssh_native_client_authentication_selection_receipt(fixture.client, NULL) != 0
            && errno == EINVAL);
        int alien_environment;
        fixture.callback.environment = &alien_environment;
        errno = 0;
        CHECK(ctos_ssh_native_client_close(fixture.client) != 0 && errno == EPERM);
        fixture.callback.environment = &fixture.callback;
        assert_selection_receipt(&fixture);
        fixture.callback.release_failures_remaining = 1U;
        errno = 0;
        CHECK(ctos_ssh_native_client_close(fixture.client) != 0 && errno == EIO);
        /* Failed release cancels the callback. Storage survives, but live reads must reject. */
        expect_receipt_error(&fixture, ECANCELED);
        CHECK(ctos_ssh_native_client_close(fixture.client) == 0);
        fixture.view_borrow = NULL;
        expect_receipt_error(&fixture, ENOENT);
        CHECK(ctos_ssh_native_client_destroy(fixture.client) == 0);
        CHECK(fixture.callback.release_calls == 2U);
        CHECK(close(fixture.pair[1]) == 0);
        CHECK(ctos_ssh_role_bootstrap_close(fixture.role) == 0);
        destroy_view(fixture.view);
        sshkey_free(fixture.public_key);
        sshkey_free(fixture.private_key);
        memset(fixture.public_blob, 0, fixture.public_blob_length);
        free(fixture.public_blob);
    }
    struct fixture legacy;
    setup_fixture(&legacy, 15000U, NULL);
    expect_receipt_error(&legacy, EACCES);
    CHECK(ctos_ssh_native_client_step(legacy.client, &(struct ctos_ssh_native_client_step){0}) == 0);
    cleanup_fixture(&legacy, 1U);
}

static void test_selection_receipt_rejects_read_without_mutating_client(void) {
    struct fixture fixture;
    int alien_environment;
    uint64_t original_view_deadline;

    setup_selected_fixture(&fixture, 15000U, 1U, 0, 0);
    fixture.callback.environment = &alien_environment;
    expect_receipt_error(&fixture, EPERM);
    fixture.callback.environment = &fixture.callback;
    assert_selection_receipt(&fixture);
    fixture.view->valid = 0;
    expect_receipt_error(&fixture, EACCES);
    fixture.view->valid = 1;
    assert_selection_receipt(&fixture);
    original_view_deadline = fixture.view->deadline;
    fixture.view->deadline = deadline_after(2U);
    wait_until_deadline(fixture.view->deadline);
    expect_receipt_error(&fixture, ETIMEDOUT);
    fixture.view->deadline = original_view_deadline;
    assert_selection_receipt(&fixture);
    CHECK(shutdown(fixture.pair[1], SHUT_WR) == 0);
    errno = 0;
    CHECK(ctos_ssh_native_client_step(fixture.client, &(struct ctos_ssh_native_client_step){0}) != 0
        && errno == EPIPE);
    expect_receipt_error(&fixture, EALREADY);
    cleanup_fixture(&fixture, 1U);

    for (unsigned int mode = 0; mode < 3U; mode++) {
        setup_selected_fixture(&fixture, mode == 2U ? 500U : 15000U, 4U, 0, 0);
        int expected_error = ECANCELED;
        if (mode == 0U) CHECK(ctos_ssh_callback_cancel(fixture.callback_token) == 0);
        else if (mode == 1U) {
            CHECK(ctos_ssh_role_bootstrap_close(fixture.role) == 0);
            fixture.role = 0;
        } else {
            wait_until_deadline(fixture.deadline);
            expected_error = ETIMEDOUT;
            fixture.role = 0; /* check_live expires and reclaims this role. */
        }
        expect_receipt_error(&fixture, expected_error);
        /* Callback liveness marks an expired role stale; the client remains unfailed. */
        expect_receipt_error(&fixture, mode == 2U ? ECANCELED : expected_error);
        cleanup_fixture(&fixture, 1U);
    }
}

static void test_selected_handoff_after_real_service(void) {
    unsigned char output[64];
    size_t length = 99;
    memset(output, 0xa5, sizeof(output));
    errno = 0;
    CHECK(ctos_ssh_native_client_selected_credential_bytes(NULL, output, sizeof(output), &length) != 0
        && errno == EINVAL && length == 0);
    for (size_t index = 0; index < sizeof(output); index++) CHECK(output[index] == 0);

    for (int new_host = 0; new_host <= 1; new_host++) {
        for (unsigned int kind = 1U; kind <= 4U; kind += 3U) {
            struct fixture fixture;
            int alien_environment;
            const char *expected = kind == 1U ? "selected-password" : "selected-private";

            setup_selected_service_fixture(&fixture, new_host, kind, 15000U, 0);
            fixture.callback.caller_output = output;
            fixture.callback.caller_capacity = sizeof(output);
            memset(output, 0xa5, sizeof(output));
            errno = 0;
            CHECK(ctos_ssh_native_client_selected_credential_bytes(fixture.client,
                output, sizeof(output), &length) != 0 && errno == EACCES && length == 0);
            CHECK(fixture.callback.selected_calls == 0U);
            CHECK(ctos_ssh_native_client_begin_userauth_service(fixture.client) == 0);
            drive_service(&fixture, 0);
            fixture.callback.environment = &alien_environment;
            memset(output, 0xa5, sizeof(output));
            errno = 0;
            CHECK(ctos_ssh_native_client_selected_credential_bytes(fixture.client,
                output, sizeof(output), &length) != 0 && errno == EPERM && length == 0);
            for (size_t index = 0; index < sizeof(output); index++) CHECK(output[index] == 0);
            fixture.callback.environment = &fixture.callback;
            CHECK(fixture.callback.selected_calls == 0U);
            CHECK(ctos_ssh_native_client_selected_credential_bytes(fixture.client,
                output, sizeof(output), &length) == 0);
            CHECK(length == strlen(expected) && memcmp(output, expected, length) == 0);
            CHECK(fixture.callback.selected_calls == 1U && fixture.service_requests == 1U);
            CHECK(ctos_ssh_native_client_complete(fixture.client)
                && ctos_ssh_native_client_userauth_service_ready(fixture.client));
            CHECK(ctos_ssh_native_client_authentication_selection_receipt(fixture.client,
                (unsigned char[32]){0}) == 0);
            CHECK(pump_synthetic_server(&fixture, 64U, NULL, NULL) == 0);
            CHECK(fixture.service_requests == 1U); /* No USERAUTH packet was queued. */
            memset(output, 0xa5, sizeof(output));
            length = 99;
            errno = 0;
            CHECK(ctos_ssh_native_client_selected_credential_bytes(fixture.client,
                output, sizeof(output), &length) != 0 && errno == EALREADY && length == 0);
            for (size_t index = 0; index < sizeof(output); index++) CHECK(output[index] == 0);
            CHECK(fixture.callback.selected_calls == 1U);
            cleanup_fixture(&fixture, 1U);
        }
    }
    struct fixture unselected;
    setup_service_fixture(&unselected, 0, 15000U, 0);
    CHECK(ctos_ssh_native_client_begin_userauth_service(unselected.client) == 0);
    drive_service(&unselected, 0);
    errno = 0;
    CHECK(ctos_ssh_native_client_selected_credential_bytes(unselected.client,
        output, sizeof(output), &length) != 0 && errno == EACCES && length == 0);
    CHECK(ctos_ssh_native_client_userauth_service_ready(unselected.client));
    CHECK(unselected.callback.selected_calls == 0U);
    cleanup_fixture(&unselected, 1U);
}

static void test_selected_handoff_failures_keep_secret_private(void) {
    for (int mode = 0; mode <= 6; mode++) {
        struct fixture fixture;
        unsigned char output[64];
        size_t length = 99;
        int expected_error = mode == 0 ? ENOSYS : mode == 1 || mode == 2 ? EACCES
            : mode == 5 ? ETIMEDOUT : mode == 6 ? EINVAL : ECANCELED;

        setup_selected_service_fixture(&fixture, mode % 2, 1U,
            mode == 5 ? TEST_EXPIRY_PREPARATION_MS : 15000U, mode == 0);
        CHECK(ctos_ssh_native_client_begin_userauth_service(fixture.client) == 0);
        drive_service(&fixture, 0);
        fixture.callback.selected_action = mode;
        fixture.callback.caller_output = output;
        fixture.callback.caller_capacity = sizeof(output);
        memset(output, 0xa5, sizeof(output));
        errno = 0;
        CHECK(ctos_ssh_native_client_selected_credential_bytes(fixture.client,
            output, sizeof(output), &length) != 0 && errno == expected_error && length == 0);
        for (size_t index = 0; index < sizeof(output); index++) CHECK(output[index] == 0);
        CHECK(fixture.callback.selected_calls == (mode == 0 ? 0U : 1U));
        CHECK(fixture.callback.credential_calls == 0U && fixture.callback.credential_bytes_calls == 0U);
        memset(output, 0xa5, sizeof(output));
        length = 99;
        errno = 0;
        CHECK(ctos_ssh_native_client_selected_credential_bytes(fixture.client,
            output, sizeof(output), &length) != 0 && errno == EALREADY && length == 0);
        for (size_t index = 0; index < sizeof(output); index++) CHECK(output[index] == 0);
        CHECK(!ctos_ssh_native_client_userauth_service_ready(fixture.client));
        if (mode == 4 || mode == 5) fixture.role = 0;
        fixture.callback.release_failures_remaining = 1U;
        errno = 0;
        CHECK(ctos_ssh_native_client_close(fixture.client) != 0 && errno == EIO);
        CHECK(fcntl(fixture.pair[0], F_GETFD) >= 0);
        cleanup_fixture(&fixture, 2U);
    }
}

static void assert_password_result(const struct fixture *fixture,
    enum ctos_ssh_native_client_password_auth_result expected) {
    enum ctos_ssh_native_client_password_auth_result result = CTOS_SSH_PASSWORD_AUTH_SUCCESS;
    unsigned int calls = fixture->callback.selected_calls;

    CHECK(ctos_ssh_native_client_password_auth_result(fixture->client, &result) == 0
        && result == expected);
    CHECK(fixture->callback.selected_calls == calls);
}

static void expect_password_result_error(const struct fixture *fixture, int expected) {
    enum ctos_ssh_native_client_password_auth_result result = CTOS_SSH_PASSWORD_AUTH_SUCCESS;
    unsigned int calls = fixture->callback.selected_calls;

    errno = 0;
    CHECK(ctos_ssh_native_client_password_auth_result(fixture->client, &result) != 0
        && errno == expected && result == CTOS_SSH_PASSWORD_AUTH_NOT_STARTED);
    CHECK(fixture->callback.selected_calls == calls);
}

static void setup_password_fixture_mode(struct fixture *fixture, int new_host,
    unsigned int lifetime_ms, enum ctos_ssh_native_host_key_mode mode) {
    memset(fixture, 0, sizeof(*fixture));
    fixture->password_expected = 1;
    fixture->password_methods = (const unsigned char *)"publickey,password";
    fixture->password_methods_length = sizeof("publickey,password") - 1U;
    fixture->password_text_length = 3U;
    fixture->password_language_length = 2U;
    fixture->pair[0] = fixture->pair[1] = -1;
    fixture->callback.release_fd = -1;
    CHECK(sshkey_generate(KEY_ED25519, 0, &fixture->private_key) == 0);
    CHECK(sshkey_from_private(fixture->private_key, &fixture->public_key) == 0);
    fixture->host_key = fixture->public_key;
    finish_fixture_setup_selected_mode(fixture, lifetime_ms, NULL, NULL, new_host, 0, 1U, 0, mode);
    fixture->callback.selected_kind = CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD;
    memcpy(fixture->callback.expected_selection_receipt, fixture->selection_receipt, 32U);
    fixture->callback.selected_view = fixture->view;
    fixture->callback.selected_callback = fixture->callback_token;
    fixture->callback.selected_role = fixture->role;
    fixture->callback.selected_deadline = fixture->deadline;
    expect_password_result_error(fixture, EACCES);
    errno = 0;
    CHECK(ctos_ssh_native_client_begin_password_auth(fixture->client) != 0 && errno == EACCES);
    finish_service_fixture(fixture, new_host, 0);
    errno = 0;
    CHECK(ctos_ssh_native_client_begin_password_auth(fixture->client) != 0 && errno == EACCES);
    CHECK(fixture->callback.selected_calls == 0U);
    CHECK(ctos_ssh_native_client_begin_userauth_service(fixture->client) == 0);
    drive_service(fixture, 0);
    assert_password_result(fixture, CTOS_SSH_PASSWORD_AUTH_NOT_STARTED);
}

static void setup_password_fixture(struct fixture *fixture, int new_host,
    unsigned int lifetime_ms) {
    setup_password_fixture_mode(fixture, new_host, lifetime_ms, CTOS_SSH_HOST_KEY_PINNED);
}

static void drive_password(struct fixture *fixture,
    enum ctos_ssh_native_client_password_auth_result expected, int expected_error) {
    int finished = 0;
    int read_would_block = 0;
    int partial_write = 0;

    assert_password_result(fixture, CTOS_SSH_PASSWORD_AUTH_PENDING);
    for (unsigned int attempt = 0; attempt < 200000U; attempt++) {
        struct ctos_ssh_native_client_step step = {0};
        enum ctos_ssh_native_client_password_auth_result result;
        int stepped;

        errno = 0;
        stepped = ctos_ssh_native_client_step(fixture->client, &step);
        CHECK(step.bytes_read <= TEST_STEP_BUDGET && step.bytes_written <= TEST_STEP_BUDGET);
        CHECK(step.input_appended == step.bytes_read && step.output_consumed == step.bytes_written);
        CHECK(step.input_consumed == 0U);
        read_would_block |= step.read_would_block;
        partial_write |= step.partial_write;
        if (stepped != 0) {
            CHECK(expected_error != 0 && errno == expected_error);
            expect_password_result_error(fixture, EALREADY);
            finished = 1;
            break;
        }
        if (step.packet_type == SSH2_MSG_USERAUTH_BANNER && step.partial_write
            && step.bytes_written != 0U) {
            CHECK(fixture->password_requests == 0U);
            fixture->password_pending_banner_writes++;
        }
        CHECK(ctos_ssh_native_client_password_auth_result(fixture->client, &result) == 0);
        if (result != CTOS_SSH_PASSWORD_AUTH_PENDING) {
            CHECK(expected_error == 0 && result == expected);
            CHECK(step.partial_write == 0 && step.write_would_block == 0);
            finished = 1;
            break;
        }
        CHECK(pump_synthetic_server(fixture, 64U, NULL, NULL) == 0);
    }
    CHECK(finished && partial_write);
    if (expected_error == 0) {
        CHECK(read_would_block);
        assert_password_result(fixture, expected);
        assert_password_result(fixture, expected);
        CHECK(ctos_ssh_native_client_userauth_service_ready(fixture->client));
    }
    CHECK(fixture->callback.selected_calls == 1U && fixture->callback.kex_complete_calls == 1U);
    CHECK(fixture->callback.credential_calls == 0U && fixture->callback.credential_bytes_calls == 0U);
    errno = 0;
    CHECK(ctos_ssh_native_client_begin_password_auth(fixture->client) != 0 && errno == EALREADY);
    errno = 0;
    CHECK(ctos_ssh_native_client_step(fixture->client,
        &(struct ctos_ssh_native_client_step){0}) != 0 && errno == EALREADY);
}

static void test_password_auth_outcomes(void) {
    for (int new_host = 0; new_host <= 1; new_host++) {
        for (int reply = PASSWORD_REPLY_SUCCESS; reply <= PASSWORD_REPLY_CHANGE; reply++) {
            struct fixture fixture;

            setup_password_fixture(&fixture, new_host, 15000U);
            fixture.password_reply = (enum password_reply)reply;
            /* The packet username and callback receipt must come from the
             * owned CSAS copy, even after caller metadata is overwritten. */
            memset(fixture.selection_wire.bytes, 0x55, fixture.selection_wire.length);
            CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
            unsigned char unused[64];
            size_t unused_length = 99U;
            errno = 0;
            CHECK(ctos_ssh_native_client_selected_credential_bytes(fixture.client,
                unused, sizeof(unused), &unused_length) != 0 && errno == EALREADY
                && unused_length == 0U && fixture.callback.selected_calls == 1U);
            errno = 0;
            CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) != 0 && errno == EALREADY);
            drive_password(&fixture, (enum ctos_ssh_native_client_password_auth_result)
                (CTOS_SSH_PASSWORD_AUTH_SUCCESS + reply), 0);
            CHECK(fixture.password_requests == 1U);
            CHECK(pump_synthetic_server(&fixture, 64U, NULL, NULL) == 0);
            CHECK(fixture.password_requests == 1U);
            cleanup_fixture(&fixture, 1U);
        }
    }
}

static void test_password_auth_banner_while_request_pending(void) {
    struct fixture fixture;
    size_t remaining;

    setup_password_fixture(&fixture, 0, 15000U);
    /* A short encrypted banner arrives before begin queues the longer padded
     * password request. Parsing it must still write a bounded request chunk. */
    CHECK(sshpkt_start(fixture.server, SSH2_MSG_USERAUTH_BANNER) == 0);
    CHECK(sshpkt_put_cstring(fixture.server, "b") == 0);
    CHECK(sshpkt_put_cstring(fixture.server, "") == 0);
    CHECK(sshpkt_send(fixture.server) == 0);
    CHECK(pump_synthetic_server(&fixture, 64U, NULL, NULL) == 0);
    (void)ssh_output_ptr(fixture.server, &remaining);
    CHECK(remaining == 0U);
    CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
    drive_password(&fixture, CTOS_SSH_PASSWORD_AUTH_SUCCESS, 0);
    CHECK(fixture.password_pending_banner_writes == 1U);
    CHECK(fixture.password_requests == 1U && fixture.callback.selected_calls == 1U);
    cleanup_fixture(&fixture, 1U);
}

static void test_password_auth_secret_bounds(void) {
    unsigned char body[8193];

    memset(body, 0xf1, sizeof(body));
    for (unsigned int mode = 0; mode < 7U; mode++) {
        struct fixture fixture;
        size_t length = mode == 0U ? 1U : mode == 1U ? 8192U : mode == 2U ? 8193U
            : mode == 3U ? 0U : 16U;

        setup_password_fixture(&fixture, mode % 2U, 15000U);
        fixture.callback.selected_body_override = 1;
        fixture.callback.selected_body = body;
        fixture.callback.selected_body_length = length;
        if (mode >= 4U) body[mode == 4U ? 0U : mode == 5U ? length - 1U : 7U] = 0;
        errno = 0;
        if (mode < 2U) {
            CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
            drive_password(&fixture, CTOS_SSH_PASSWORD_AUTH_SUCCESS, 0);
            CHECK(fixture.password_requests == 1U);
        } else {
            CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) != 0
                && errno == (mode == 2U ? EOVERFLOW : EINVAL));
            expect_password_result_error(&fixture, EALREADY);
            CHECK(pump_synthetic_server(&fixture, 64U, NULL, NULL) == 0);
            CHECK(fixture.password_requests == 0U && fixture.callback.selected_calls == 1U);
            errno = 0;
            CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) != 0
                && errno == EALREADY);
        }
        cleanup_fixture(&fixture, 1U);
        memset(body, 0xf1, sizeof(body));
    }
}

static void test_password_auth_bad_replies(void) {
    for (int reply = PASSWORD_REPLY_SUCCESS_TRAILING; reply <= PASSWORD_REPLY_EOF; reply++) {
        struct fixture fixture;

        setup_password_fixture(&fixture, reply % 2, 15000U);
        fixture.password_reply = (enum password_reply)reply;
        CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
        drive_password(&fixture, CTOS_SSH_PASSWORD_AUTH_NOT_STARTED,
            reply == PASSWORD_REPLY_DISCONNECT ? ECONNRESET : reply == PASSWORD_REPLY_EOF ? EPIPE : EPROTO);
        CHECK(fixture.password_requests == 1U);
        cleanup_fixture(&fixture, 1U);
    }
    for (int reply = PASSWORD_REPLY_SUCCESS; reply <= PASSWORD_REPLY_CHANGE; reply++) {
        struct fixture fixture;

        setup_password_fixture(&fixture, reply % 2, 15000U);
        fixture.password_reply = (enum password_reply)reply;
        send_password_reply(&fixture);
        CHECK(pump_synthetic_server(&fixture, 64U, NULL, NULL) == 0);
        CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
        drive_password(&fixture, CTOS_SSH_PASSWORD_AUTH_NOT_STARTED, EPROTO);
        CHECK(fixture.password_requests == 0U);
        cleanup_fixture(&fixture, 1U);
    }
}

static void test_password_auth_methods_and_remote_limits(void) {
    static const unsigned char *const invalid_methods[] = {
        (const unsigned char *)",password", (const unsigned char *)"password,",
        (const unsigned char *)"password,,publickey", (const unsigned char *)"pass word",
        (const unsigned char *)"pass\x80", (const unsigned char *)"pass\x01"
    };
    unsigned char methods[1025];
    memset(methods, 'm', sizeof(methods));
    for (unsigned int mode = 0; mode < 10U; mode++) {
        struct fixture fixture;
        int accepted = mode < 3U;

        setup_password_fixture(&fixture, 0, 15000U);
        fixture.password_reply = PASSWORD_REPLY_REJECTED;
        fixture.password_methods = mode < 4U ? methods : invalid_methods[mode - 4U];
        fixture.password_methods_length = mode == 0U ? 0U : mode == 1U ? 1024U
            : mode == 2U ? 1U : mode == 3U ? 1025U : strlen((const char *)fixture.password_methods);
        CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
        drive_password(&fixture, CTOS_SSH_PASSWORD_AUTH_REJECTED, accepted ? 0 : EPROTO);
        cleanup_fixture(&fixture, 1U);
    }
    for (unsigned int mode = 0; mode < 9U; mode++) {
        struct fixture fixture;
        int accepted = mode == 0U || mode == 1U || mode == 2U;

        setup_password_fixture(&fixture, mode % 2U, 15000U);
        fixture.password_reply = mode >= 6U ? PASSWORD_REPLY_CHANGE : PASSWORD_REPLY_SUCCESS;
        fixture.password_banners = mode == 0U ? 16U : mode == 1U ? 1U
            : mode == 2U ? 0U : mode == 3U ? 17U : mode < 6U ? 1U : 0U;
        fixture.password_text_length = mode == 4U || mode == 6U ? 8193U
            : mode == 1U ? 8192U : 0U;
        fixture.password_language_length = mode == 5U || mode == 7U ? 129U
            : mode == 1U ? 128U : 0U;
        if (mode == 8U) {
            fixture.password_reply = PASSWORD_REPLY_CHANGE;
            fixture.password_text_length = 8192U;
            fixture.password_language_length = 128U;
            accepted = 1;
        }
        CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
        drive_password(&fixture, mode >= 6U ? CTOS_SSH_PASSWORD_AUTH_CHANGE_REQUIRED
            : CTOS_SSH_PASSWORD_AUTH_SUCCESS, accepted ? 0 : EPROTO);
        cleanup_fixture(&fixture, 1U);
    }
}

struct password_owner_attempt {
    struct ctos_ssh_native_client *client;
    int active;
    int query_result, query_error, operation_result, operation_error;
    enum ctos_ssh_native_client_password_auth_result outcome;
    size_t bytes_read, bytes_written;
};

static void *password_wrong_thread(void *argument) {
    struct password_owner_attempt *attempt = argument;
    attempt->outcome = CTOS_SSH_PASSWORD_AUTH_SUCCESS;
    errno = 0;
    attempt->query_result = ctos_ssh_native_client_password_auth_result(attempt->client,
        &attempt->outcome);
    attempt->query_error = errno;
    errno = 0;
    if (attempt->active) {
        struct ctos_ssh_native_client_step step = {.cancelled = 1};
        attempt->operation_result = ctos_ssh_native_client_step(attempt->client, &step);
        attempt->operation_error = errno;
        attempt->bytes_read = step.bytes_read;
        attempt->bytes_written = step.bytes_written;
    } else {
        attempt->operation_result = ctos_ssh_native_client_begin_password_auth(attempt->client);
        attempt->operation_error = errno;
    }
    return NULL;
}

static void test_password_auth_owner_status_and_close(void) {
    struct fixture fixture;
    int alien_environment;
    pthread_t thread;
    enum ctos_ssh_native_client_password_auth_result result = CTOS_SSH_PASSWORD_AUTH_SUCCESS;

    errno = 0;
    CHECK(ctos_ssh_native_client_begin_password_auth(NULL) != 0 && errno == EINVAL);
    errno = 0;
    CHECK(ctos_ssh_native_client_password_auth_result(NULL, &result) != 0
        && errno == EINVAL && result == CTOS_SSH_PASSWORD_AUTH_NOT_STARTED);
    setup_password_fixture(&fixture, 0, 15000U);
    errno = 0;
    CHECK(ctos_ssh_native_client_password_auth_result(fixture.client, NULL) != 0 && errno == EINVAL);
    fixture.callback.environment = &alien_environment;
    expect_password_result_error(&fixture, EPERM);
    errno = 0;
    CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) != 0 && errno == EPERM);
    fixture.callback.environment = &fixture.callback;
    struct password_owner_attempt attempt = {.client = fixture.client};
    CHECK(pthread_create(&thread, NULL, password_wrong_thread, &attempt) == 0);
    CHECK(pthread_join(thread, NULL) == 0);
    CHECK(attempt.query_result != 0 && attempt.query_error == EPERM
        && attempt.outcome == CTOS_SSH_PASSWORD_AUTH_NOT_STARTED);
    CHECK(attempt.operation_result != 0 && attempt.operation_error == EPERM);
    fixture.view->valid = 0;
    expect_password_result_error(&fixture, EACCES);
    fixture.view->valid = 1;
    assert_password_result(&fixture, CTOS_SSH_PASSWORD_AUTH_NOT_STARTED);
    CHECK(fixture.callback.selected_calls == 0U);
    CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
    attempt.active = 1;
    CHECK(pthread_create(&thread, NULL, password_wrong_thread, &attempt) == 0);
    CHECK(pthread_join(thread, NULL) == 0);
    CHECK(attempt.query_result != 0 && attempt.query_error == EPERM
        && attempt.outcome == CTOS_SSH_PASSWORD_AUTH_NOT_STARTED);
    CHECK(attempt.operation_result != 0 && attempt.operation_error == EPERM
        && attempt.bytes_read == 0U && attempt.bytes_written == 0U);
    fixture.callback.environment = &alien_environment;
    expect_password_result_error(&fixture, EPERM);
    struct ctos_ssh_native_client_step step = {.cancelled = 1};
    errno = 0;
    CHECK(ctos_ssh_native_client_step(fixture.client, &step) != 0 && errno == EPERM);
    fixture.callback.environment = &fixture.callback;
    assert_password_result(&fixture, CTOS_SSH_PASSWORD_AUTH_PENDING);
    drive_password(&fixture, CTOS_SSH_PASSWORD_AUTH_SUCCESS, 0);
    fixture.callback.release_failures_remaining = 1U;
    errno = 0;
    CHECK(ctos_ssh_native_client_close(fixture.client) != 0 && errno == EIO);
    CHECK(fcntl(fixture.pair[0], F_GETFD) >= 0);
    cleanup_fixture(&fixture, 2U);
}

static void test_password_auth_admission_and_provider_failure(void) {
    for (unsigned int kind = 0U; kind <= 4U; kind += 4U) {
        struct fixture fixture;
        if (kind == 0U) setup_service_fixture(&fixture, 0, 15000U, 0);
        else setup_selected_service_fixture(&fixture, 0, kind, 15000U, 0);
        CHECK(ctos_ssh_native_client_begin_userauth_service(fixture.client) == 0);
        drive_service(&fixture, 0);
        errno = 0;
        CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) != 0 && errno == EACCES);
        expect_password_result_error(&fixture, EACCES);
        CHECK(fixture.callback.selected_calls == 0U);
        cleanup_fixture(&fixture, 1U);
    }
    struct fixture standalone;
    unsigned char secret[64];
    size_t secret_length;
    setup_password_fixture(&standalone, 0, 15000U);
    CHECK(ctos_ssh_native_client_selected_credential_bytes(standalone.client,
        secret, sizeof(secret), &secret_length) == 0);
    memset(secret, 0, sizeof(secret));
    assert_password_result(&standalone, CTOS_SSH_PASSWORD_AUTH_NOT_STARTED);
    errno = 0;
    CHECK(ctos_ssh_native_client_begin_password_auth(standalone.client) != 0 && errno == EALREADY);
    CHECK(pump_synthetic_server(&standalone, 64U, NULL, NULL) == 0);
    CHECK(standalone.password_requests == 0U && standalone.callback.selected_calls == 1U);
    cleanup_fixture(&standalone, 1U);
    for (int action = 1; action <= 6; action++) {
        struct fixture fixture;
        int expected = action == 1 || action == 2 ? EACCES
            : action == 5 ? ETIMEDOUT : action == 6 ? EINVAL : ECANCELED;

        setup_password_fixture(&fixture, action % 2,
            action == 5 ? TEST_EXPIRY_PREPARATION_MS : 15000U);
        fixture.callback.selected_action = action;
        errno = 0;
        CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) != 0 && errno == expected);
        expect_password_result_error(&fixture, EALREADY);
        CHECK(fixture.callback.selected_calls == 1U && fixture.password_requests == 0U);
        CHECK(pump_synthetic_server(&fixture, 64U, NULL, NULL) == 0);
        if (action == 4 || action == 5) fixture.role = 0;
        cleanup_fixture(&fixture, 1U);
    }
}

static void test_password_auth_active_liveness(void) {
    for (unsigned int failure = 0; failure < 6U; failure++) {
        struct fixture fixture;
        int expected = failure == 1U || failure == 2U ? EACCES
            : failure == 4U ? ETIMEDOUT : ECANCELED;

        setup_password_fixture(&fixture, failure % 2U,
            failure == 4U ? TEST_EXPIRY_PREPARATION_MS : 15000U);
        CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
        if (failure == 0U) CHECK(ctos_ssh_callback_cancel(fixture.callback_token) == 0);
        if (failure == 1U) fixture.view->valid = 0;
        if (failure == 2U) {
            fixture.view->deadline = deadline_after(2U);
            wait_until_deadline(fixture.view->deadline);
            expected = ETIMEDOUT;
        }
        if (failure == 3U) {
            CHECK(ctos_ssh_role_bootstrap_close(fixture.role) == 0);
            fixture.role = 0;
        }
        if (failure == 4U)
            wait_until_deadline_attempts(fixture.deadline, TEST_EXPIRY_WAIT_ATTEMPTS);
        struct ctos_ssh_native_client_step step = {.cancelled = failure == 5U};
        errno = 0;
        CHECK(ctos_ssh_native_client_step(fixture.client, &step) != 0 && errno == expected
            && step.bytes_read == 0U && step.bytes_written == 0U);
        expect_password_result_error(&fixture, EALREADY);
        CHECK(fixture.callback.selected_calls == 1U && fixture.password_requests == 0U);
        if (failure == 4U) fixture.role = 0;
        cleanup_fixture(&fixture, 1U);
    }
}

static void test_close_retry_order(void) {
    struct fixture fixture;

    setup_fixture(&fixture, 15000U, NULL);
    fixture.callback.release_failures_remaining = 1;
    errno = 0;
    CHECK(ctos_ssh_native_client_close(fixture.client) != 0 && errno == EIO);
    CHECK(fixture.callback.release_calls == 1);
    CHECK(fixture.callback.release_saw_fd_open == 1);
    CHECK(fcntl(fixture.pair[0], F_GETFD) >= 0);
    cleanup_fixture(&fixture, 2);
}

static void setup_private_fixture_version_mode(struct fixture *fixture, int new_host,
    const struct sshkey *identity, const struct sshbuf *file,
    const unsigned char *expected, size_t expected_length,
    const char *advertisement, int no_advertisement, int version,
    enum ctos_ssh_native_host_key_mode mode) {
    memset(fixture, 0, sizeof(*fixture));
    fixture->private_expected = 1;
    fixture->encrypted_identity = version == 2;
    fixture->identity = identity;
    fixture->identity_blob = expected;
    fixture->identity_blob_length = expected_length;
    fixture->identity_algorithm = identity->type == KEY_RSA
        ? (advertisement != NULL && strcmp(advertisement, "rsa-sha2-256") == 0
            ? "rsa-sha2-256" : "rsa-sha2-512") : sshkey_ssh_name(identity);
    fixture->signature_advertisement = advertisement;
    fixture->password_methods = (const unsigned char *)"publickey,password";
    fixture->password_methods_length = 18U;
    fixture->pair[0] = fixture->pair[1] = -1;
    fixture->callback.release_fd = -1;
    CHECK(sshkey_generate(KEY_ED25519, 0, &fixture->private_key) == 0);
    CHECK(sshkey_from_private(fixture->private_key, &fixture->public_key) == 0);
    fixture->host_key = fixture->public_key;
    finish_fixture_setup_selected_mode(fixture, 15000U, NULL, NULL, new_host, 0, 4U, 0, mode);
    fixture->callback.selected_kind = CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY;
    fixture->callback.selected_body_override = 1;
    fixture->callback.selected_body = sshbuf_ptr(file);
    fixture->callback.selected_body_length = sshbuf_len(file);
    fixture->callback.selected_view = fixture->view;
    fixture->callback.selected_callback = fixture->callback_token;
    fixture->callback.selected_role = fixture->role;
    memcpy(fixture->callback.expected_selection_receipt, fixture->selection_receipt, 32U);
    CHECK(ctos_ssh_native_client_begin_private_key_auth(fixture->client) != 0 && errno == EACCES);
    finish_service_fixture(fixture, new_host, no_advertisement);
    CHECK(ctos_ssh_native_client_begin_userauth_service(fixture->client) == 0);
    drive_service(fixture, 0);
    enum ctos_ssh_native_client_private_key_auth_result result = CTOS_SSH_PRIVATE_KEY_AUTH_SUCCESS;
    CHECK(ctos_ssh_native_client_private_key_auth_result(fixture->client, &result) == 0
        && result == CTOS_SSH_PRIVATE_KEY_AUTH_NOT_STARTED);
    CHECK(ctos_ssh_native_client_password_auth_result(fixture->client,
        &(enum ctos_ssh_native_client_password_auth_result){0}) != 0 && errno == EACCES);
}

static void setup_private_fixture_version(struct fixture *fixture, int new_host,
    const struct sshkey *identity, const struct sshbuf *file,
    const unsigned char *expected, size_t expected_length,
    const char *advertisement, int no_advertisement, int version) {
    setup_private_fixture_version_mode(fixture, new_host, identity, file, expected,
        expected_length, advertisement, no_advertisement, version, CTOS_SSH_HOST_KEY_PINNED);
}

static void setup_private_fixture(struct fixture *fixture, int new_host,
    const struct sshkey *identity, const struct sshbuf *file,
    const unsigned char *expected, size_t expected_length,
    const char *advertisement, int no_advertisement) {
    setup_private_fixture_version(fixture, new_host, identity, file, expected, expected_length,
        advertisement, no_advertisement, 1);
}

static void drive_private(struct fixture *fixture, int outcome, int expected_error) {
    int finished = 0;
    for (unsigned int turn = 0; turn < 100000U; turn++) {
        struct ctos_ssh_native_client_step step = {0};
        enum ctos_ssh_native_client_private_key_auth_result result;
        if (ctos_ssh_native_client_step(fixture->client, &step) != 0) {
            CHECK(expected_error != 0 && errno == expected_error);
            finished = 1;
            break;
        }
        CHECK(step.bytes_read <= TEST_STEP_BUDGET && step.bytes_written <= TEST_STEP_BUDGET);
        CHECK(ctos_ssh_native_client_private_key_auth_result(fixture->client, &result) == 0);
        if (result != CTOS_SSH_PRIVATE_KEY_AUTH_PENDING) {
            CHECK(expected_error == 0 && (int)result == outcome);
            finished = 1;
            break;
        }
        CHECK(pump_synthetic_server(fixture, 64U, NULL, NULL) == 0);
    }
    CHECK(finished && fixture->callback.selected_calls == 1U);
    CHECK(fixture->callback.credential_calls == 0U && fixture->callback.credential_bytes_calls == 0U);
    CHECK(ctos_ssh_native_client_begin_private_key_auth(fixture->client) != 0 && errno == EALREADY);
    CHECK(ctos_ssh_native_client_begin_password_auth(fixture->client) != 0 && errno == EALREADY);
    CHECK(ctos_ssh_native_client_step(fixture->client,
        &(struct ctos_ssh_native_client_step){0}) != 0 && errno == EALREADY);
}

struct private_owner_attempt {
    struct ctos_ssh_native_client *client;
    int query_error;
    int begin_error;
};

static void *private_wrong_thread(void *argument) {
    struct private_owner_attempt *attempt = argument;
    enum ctos_ssh_native_client_private_key_auth_result result = CTOS_SSH_PRIVATE_KEY_AUTH_SUCCESS;
    CHECK(ctos_ssh_native_client_private_key_auth_result(attempt->client, &result) != 0);
    attempt->query_error = errno;
    CHECK(result == CTOS_SSH_PRIVATE_KEY_AUTH_NOT_STARTED);
    CHECK(ctos_ssh_native_client_begin_private_key_auth(attempt->client) != 0);
    attempt->begin_error = errno;
    return NULL;
}

static void test_private_auth(void) {
    const int types[] = {KEY_ED25519, KEY_ECDSA, KEY_ECDSA, KEY_ECDSA, KEY_RSA};
    const unsigned int bits[] = {0, 256, 384, 521, 1024};
    for (size_t algorithm = 0; algorithm < 5U; algorithm++) {
        struct sshkey *key = NULL, *other = NULL;
        unsigned char *blob = NULL;
        size_t blob_length;
        CHECK(sshkey_generate(types[algorithm], bits[algorithm], &key) == 0);
        CHECK(sshkey_to_blob(key, &blob, &blob_length) == 0);
        for (int format = SSHKEY_PRIVATE_OPENSSH; format <= SSHKEY_PRIVATE_PKCS8; format++) {
            struct sshbuf *file = sshbuf_new();
            CHECK(file != NULL);
            if (types[algorithm] == KEY_ED25519 && format == SSHKEY_PRIVATE_PEM) {
                CHECK(sshkey_private_to_fileblob(key, file, "", "synthetic", format, NULL, 0)
                    == SSH_ERR_INVALID_FORMAT);
                sshbuf_free(file);
                continue;
            }
            CHECK(sshkey_private_to_fileblob(key, file, "", "synthetic", format, NULL, 0) == 0);
            for (int new_host = 0; new_host < 2; new_host++) {
                struct fixture fixture;
                setup_private_fixture(&fixture, new_host, key, file, blob, blob_length, NULL, 0);
                fixture.password_reply = (enum password_reply)((format + new_host) % 3);
                memset(fixture.selection_wire.bytes, 0x55, fixture.selection_wire.length);
                CHECK(ctos_ssh_native_client_begin_private_key_auth(fixture.client) == 0);
                drive_private(&fixture, 2 + (format + new_host) % 3, 0);
                CHECK(fixture.private_requests == 1U);
                cleanup_fixture(&fixture, 1U);
            }
            sshbuf_free(file);
        }
        struct sshbuf *file = sshbuf_new();
        CHECK(file != NULL && sshkey_private_to_fileblob(key, file, "", "synthetic",
            SSHKEY_PRIVATE_OPENSSH, NULL, 0) == 0);
        if (types[algorithm] == KEY_RSA) {
            const char *advertisements[] = {"rsa-sha2-256", "rsa-sha2-256,rsa-sha2-512",
                "", "x-rsa-sha2-512-y", "ssh-rsa", "rsa-sha2-512x,rsa-sha2-256x", NULL};
            for (size_t mode = 0; mode < 7U; mode++) {
                struct fixture fixture;
                setup_private_fixture(&fixture, mode % 2U, key, file, blob, blob_length,
                    advertisements[mode], mode == 6U);
                if (mode < 2U) {
                    CHECK(ctos_ssh_native_client_begin_private_key_auth(fixture.client) == 0);
                    drive_private(&fixture, CTOS_SSH_PRIVATE_KEY_AUTH_SUCCESS, 0);
                } else {
                    CHECK(ctos_ssh_native_client_begin_private_key_auth(fixture.client) != 0
                        && errno == ENOTSUP);
                    CHECK(fixture.callback.selected_calls == 0U && fixture.private_requests == 0U);
                }
                cleanup_fixture(&fixture, 1U);
            }
        }
        if (algorithm == 0U) {
            CHECK(sshkey_generate(KEY_ED25519, 0, &other) == 0);
            for (int mode = 0; mode < 10; mode++) {
                struct fixture fixture;
                struct sshbuf *bad = sshbuf_new();
                CHECK(bad != NULL);
                const unsigned char invalid[] = {0, 0, 0, 2, 'x'};
                if (mode == 1) CHECK(sshbuf_put(bad, invalid, sizeof(invalid)) == 0);
                else CHECK(sshkey_private_to_fileblob(mode == 2 ? other : key, bad,
                    mode == 3 ? "synthetic-passphrase" : "", "synthetic",
                    SSHKEY_PRIVATE_OPENSSH, NULL, mode == 3 ? 1 : 0) == 0);
                setup_private_fixture(&fixture, mode % 2, key, bad,
                    mode == 0 ? invalid : blob, mode == 0 ? sizeof(invalid) : blob_length, NULL, 0);
                if (mode < 4) {
                    CHECK(ctos_ssh_native_client_begin_private_key_auth(fixture.client) != 0
                        && errno == (mode < 2 ? EINVAL : mode == 2 ? EACCES : ENOTSUP));
                    CHECK(fixture.callback.selected_calls == (mode == 0 ? 0U : 1U));
                    CHECK(fixture.private_requests == 0U);
                } else if (mode < 7) {
                    fixture.callback.selected_action = mode - 3;
                    CHECK(ctos_ssh_native_client_begin_private_key_auth(fixture.client) != 0);
                    CHECK(fixture.callback.selected_calls == 1U && fixture.private_requests == 0U);
                } else {
                    fixture.password_reply = mode == 7 ? PASSWORD_REPLY_CHANGE
                        : mode == 8 ? PASSWORD_REPLY_SUCCESS_TRAILING : PASSWORD_REPLY_DISCONNECT;
                    CHECK(ctos_ssh_native_client_begin_private_key_auth(fixture.client) == 0);
                    drive_private(&fixture, 0, mode == 9 ? ECONNRESET : EPROTO);
                }
                cleanup_fixture(&fixture, 1U);
                sshbuf_free(bad);
            }
            /* OpenSSH's Ed25519 decoder accepts separately serialized public and
             * secret fields. Mutate only the seed and preserve all public fields. */
            CHECK(key->ed25519_sk != NULL);
            key->ed25519_sk[0] ^= 1U;
            struct sshbuf *corrupt = sshbuf_new();
            CHECK(corrupt != NULL && sshkey_private_to_fileblob(key, corrupt, "", "synthetic",
                SSHKEY_PRIVATE_OPENSSH, NULL, 0) == 0);
            key->ed25519_sk[0] ^= 1U;
            struct fixture inconsistent;
            setup_private_fixture(&inconsistent, 0, key, corrupt, blob, blob_length, NULL, 0);
            CHECK(ctos_ssh_native_client_begin_private_key_auth(inconsistent.client) != 0 && errno == EACCES);
            CHECK(inconsistent.callback.selected_calls == 1U && inconsistent.private_requests == 0U);
            cleanup_fixture(&inconsistent, 1U);
            sshbuf_free(corrupt);
            for (int mode = 0; mode < 6; mode++) {
                struct fixture guarded;
                setup_private_fixture(&guarded, mode % 2, key, file, blob, blob_length, NULL, 0);
                if (mode == 0) {
                    pthread_t thread;
                    struct private_owner_attempt attempt = {.client = guarded.client};
                    CHECK(pthread_create(&thread, NULL, private_wrong_thread, &attempt) == 0);
                    CHECK(pthread_join(thread, NULL) == 0);
                    CHECK(attempt.query_error == EPERM && attempt.begin_error == EPERM);
                    CHECK(guarded.callback.selected_calls == 0U);
                    CHECK(ctos_ssh_native_client_begin_private_key_auth(guarded.client) == 0);
                    drive_private(&guarded, CTOS_SSH_PRIVATE_KEY_AUTH_SUCCESS, 0);
                    guarded.callback.release_failures_remaining = 1U;
                    CHECK(ctos_ssh_native_client_close(guarded.client) != 0 && errno == EIO);
                } else if (mode == 1) {
                    unsigned char output[16384];
                    size_t length;
                    CHECK(ctos_ssh_native_client_selected_credential_bytes(guarded.client,
                        output, sizeof(output), &length) == 0 && length == sshbuf_len(file));
                    memset(output, 0, sizeof(output));
                    CHECK(ctos_ssh_native_client_begin_private_key_auth(guarded.client) != 0 && errno == EALREADY);
                } else {
                    if (mode == 5) {
                        send_password_reply(&guarded);
                        for (int turn = 0; turn < 32; turn++)
                            CHECK(pump_synthetic_server(&guarded, 64U, NULL, NULL) == 0);
                    }
                    CHECK(ctos_ssh_native_client_begin_private_key_auth(guarded.client) == 0);
                    if (mode == 2) CHECK(ctos_ssh_callback_cancel(guarded.callback_token) == 0);
                    if (mode == 3) guarded.view->valid = 0;
                    if (mode == 4) { CHECK(ctos_ssh_role_bootstrap_close(guarded.role) == 0); guarded.role = 0; }
                    if (mode == 5) drive_private(&guarded, 0, EPROTO);
                    else {
                        CHECK(ctos_ssh_native_client_step(guarded.client,
                            &(struct ctos_ssh_native_client_step){0}) != 0);
                        CHECK(guarded.private_requests == 0U);
                    }
                }
                cleanup_fixture(&guarded, mode == 0 ? 2U : 1U);
            }
            struct sshkey *ca = NULL, *certificate = make_host_certificate(&ca);
            unsigned char *certificate_blob = NULL;
            size_t certificate_length;
            CHECK(sshkey_to_blob(certificate, &certificate_blob, &certificate_length) == 0);
            struct sshbuf *sk = sshbuf_new();
            CHECK(sk != NULL && sshbuf_put_cstring(sk, "sk-ssh-ed25519@openssh.com") == 0
                && sshbuf_put_string(sk, key->ed25519_pk, 32U) == 0
                && sshbuf_put_cstring(sk, "ssh:synthetic") == 0);
            for (int mode = 0; mode < 2; mode++) {
                struct fixture unsupported;
                setup_private_fixture(&unsupported, 0, key, file,
                    mode == 0 ? certificate_blob : sshbuf_ptr(sk),
                    mode == 0 ? certificate_length : sshbuf_len(sk), NULL, 0);
                CHECK(ctos_ssh_native_client_begin_private_key_auth(unsupported.client) != 0 && errno == ENOTSUP);
                CHECK(unsupported.callback.selected_calls == 0U && unsupported.private_requests == 0U);
                cleanup_fixture(&unsupported, 1U);
            }
            sshbuf_free(sk);
            free(certificate_blob);
            sshkey_free(certificate);
            sshkey_free(ca);
        }
        sshbuf_free(file);
        sshkey_free(other);
        sshkey_free(key);
        free(blob);
    }
}

static struct sshbuf *private_bundle(const struct sshbuf *file, const char *passphrase) {
    struct sshbuf *bundle = sshbuf_new();
    CHECK(bundle != NULL && sshbuf_put_u32(bundle, 0x43534b42U) == 0
        && sshbuf_put_u32(bundle, 1U) == 0 && sshbuf_put_stringb(bundle, file) == 0
        && sshbuf_put_cstring(bundle, passphrase) == 0);
    return bundle;
}

static struct sshbuf *unsupported_encrypted_file(unsigned int rounds, const char *cipher) {
    struct sshbuf *raw = sshbuf_new(), *options = sshbuf_new(), *file = sshbuf_new();
    CHECK(raw != NULL && options != NULL && file != NULL);
    CHECK(sshbuf_put_string(options, (const unsigned char[16]){1}, 16U) == 0
        && sshbuf_put_u32(options, rounds) == 0);
    CHECK(sshbuf_put(raw, "openssh-key-v1", sizeof("openssh-key-v1")) == 0
        && sshbuf_put_cstring(raw, cipher) == 0 && sshbuf_put_cstring(raw, "bcrypt") == 0
        && sshbuf_put_stringb(raw, options) == 0 && sshbuf_put_u32(raw, 1U) == 0);
    CHECK(sshbuf_put(file, "-----BEGIN OPENSSH PRIVATE KEY-----\n", 36U) == 0
        && sshbuf_dtob64(raw, file, 1) == 0
        && sshbuf_put(file, "-----END OPENSSH PRIVATE KEY-----\n", 34U) == 0);
    sshbuf_free(raw);
    sshbuf_free(options);
    return file;
}

static void test_encrypted_private_auth(void) {
    const int types[] = {KEY_ED25519, KEY_ECDSA, KEY_ECDSA, KEY_ECDSA, KEY_RSA};
    const unsigned int bits[] = {0, 256, 384, 521, 1024};
    for (size_t algorithm = 0; algorithm < 5U; algorithm++) {
        struct sshkey *key = NULL;
        unsigned char *blob = NULL;
        size_t blob_length;
        CHECK(sshkey_generate(types[algorithm], bits[algorithm], &key) == 0);
        CHECK(sshkey_to_blob(key, &blob, &blob_length) == 0);
        for (int format = SSHKEY_PRIVATE_OPENSSH; format <= SSHKEY_PRIVATE_PEM; format++) {
            if (types[algorithm] == KEY_ED25519 && format == SSHKEY_PRIVATE_PEM) continue;
            struct sshbuf *file = sshbuf_new();
            CHECK(file != NULL && sshkey_private_to_fileblob(key, file, "synthetic-passphrase",
                "synthetic", format, NULL, 1) == 0);
            struct sshbuf *bundle = private_bundle(file, "synthetic-passphrase");
            struct fixture fixture;
            setup_private_fixture_version(&fixture, algorithm % 2U, key, bundle, blob, blob_length, NULL, 0, 2);
            CHECK(ctos_ssh_native_client_begin_private_key_auth(fixture.client) == 0);
            drive_private(&fixture, CTOS_SSH_PRIVATE_KEY_AUTH_SUCCESS, 0);
            CHECK(fixture.private_requests == 1U);
            cleanup_fixture(&fixture, 1U);
            sshbuf_free(bundle);
            if (algorithm == 0U || algorithm == 4U) {
                bundle = private_bundle(file, "wrong-passphrase");
                setup_private_fixture_version(&fixture, 0, key, bundle, blob, blob_length, NULL, 0, 2);
                CHECK(ctos_ssh_native_client_begin_private_key_auth(fixture.client) != 0 && errno == EACCES);
                CHECK(fixture.callback.selected_calls == 1U && fixture.private_requests == 0U);
                cleanup_fixture(&fixture, 1U);
                sshbuf_free(bundle);
            }
            if (algorithm == 0U && format == SSHKEY_PRIVATE_OPENSSH) {
                for (int mode = 0; mode < 13; mode++) {
                    struct sshbuf *bad_file = mode == 5 ? unsupported_encrypted_file(65U, "aes256-ctr")
                        : mode == 6 ? unsupported_encrypted_file(1U, "aes128-ctr")
                        : mode == 7 ? unsupported_encrypted_file(0U, "aes256-ctr") : NULL;
                    if (mode == 9 || mode == 10) {
                        struct sshbuf *pkcs8 = sshbuf_new();
                        CHECK(pkcs8 != NULL && sshkey_private_to_fileblob(key, pkcs8,
                            "synthetic-passphrase", "synthetic", SSHKEY_PRIVATE_PKCS8, NULL, 0) == 0);
                        bad_file = sshbuf_new();
                        CHECK(bad_file != NULL);
                        if (mode == 10) CHECK(sshbuf_put(bad_file,
                            "-----BEGIN RSA PRIVATE KEY-----bogus\n",
                            sizeof("-----BEGIN RSA PRIVATE KEY-----bogus\n") - 1U) == 0);
                        CHECK(sshbuf_putb(bad_file, pkcs8) == 0);
                        sshbuf_free(pkcs8);
                    }
                    if (mode == 12) {
                        bad_file = sshbuf_new();
                        CHECK(bad_file != NULL && sshkey_private_to_fileblob(key, bad_file,
                            "", "synthetic", SSHKEY_PRIVATE_OPENSSH, NULL, 0) == 0);
                    }
                    bundle = private_bundle(bad_file == NULL ? file : bad_file,
                        mode == 3 ? "" : "synthetic-passphrase");
                    if (mode == 11) {
                        unsigned char oversized[8193];
                        memset(oversized, 'p', sizeof(oversized));
                        sshbuf_reset(bundle);
                        CHECK(sshbuf_put_u32(bundle, 0x43534b42U) == 0 && sshbuf_put_u32(bundle, 1U) == 0
                            && sshbuf_put_stringb(bundle, file) == 0
                            && sshbuf_put_string(bundle, oversized, sizeof(oversized)) == 0);
                    }
                    if (mode == 0) sshbuf_mutable_ptr(bundle)[0] ^= 1U;
                    if (mode == 1) sshbuf_mutable_ptr(bundle)[7] = 2U;
                    if (mode == 2) CHECK(sshbuf_consume_end(bundle, 1U) == 0);
                    if (mode == 4) sshbuf_mutable_ptr(bundle)[sshbuf_len(bundle) - 1U] = 0;
                    if (mode == 8) CHECK(sshbuf_put_u8(bundle, 0) == 0);
                    setup_private_fixture_version(&fixture, 0, key, bundle, blob, blob_length, NULL, 0, 2);
                    CHECK(ctos_ssh_native_client_begin_private_key_auth(fixture.client) != 0
                        && errno == ((mode >= 5 && mode <= 7) || mode == 9 || mode == 10 || mode == 12
                            ? ENOTSUP : EINVAL));
                    CHECK(fixture.callback.selected_calls == 1U && fixture.private_requests == 0U);
                    CHECK(ctos_ssh_native_client_begin_private_key_auth(fixture.client) != 0 && errno == EALREADY);
                    cleanup_fixture(&fixture, 1U);
                    sshbuf_free(bad_file);
                    sshbuf_free(bundle);
                }
            }
            sshbuf_free(file);
        }
        sshkey_free(key);
        free(blob);
    }
}

static struct ctos_ssh_native_shell_status shell_status(struct fixture *fixture) {
    struct ctos_ssh_native_shell_status status;
    memset(&status, 0xff, sizeof(status));
    CHECK(ctos_ssh_native_client_shell_status(fixture->client, &status) == 0);
    return status;
}

static int shell_turn(struct fixture *fixture) {
    struct ctos_ssh_native_client_step step = {0};
    int result = ctos_ssh_native_client_step(fixture->client, &step);
    CHECK(step.bytes_read <= TEST_STEP_BUDGET && step.bytes_written <= TEST_STEP_BUDGET);
    CHECK(step.input_appended == step.bytes_read && step.output_consumed == step.bytes_written);
    int saved_errno = errno;
    CHECK(pump_synthetic_server(fixture, 64U, NULL, NULL) == 0);
    errno = saved_errno;
    return result;
}

static void drive_shell_phase(struct fixture *fixture, enum ctos_ssh_native_shell_phase phase) {
    for (unsigned int turn = 0; turn < 100000U; turn++) {
        CHECK(shell_turn(fixture) == 0);
        if (shell_status(fixture).phase == phase) return;
    }
    CHECK(0);
}

static void setup_shell_fixture_mode(struct fixture *fixture, int kind, uint32_t window,
    uint32_t maximum, int reject, enum ctos_ssh_native_host_key_mode mode, int new_host) {
    if (kind == 1) {
        setup_password_fixture_mode(fixture, new_host, 60000U, mode);
        CHECK(shell_status(fixture).phase == CTOS_SSH_SHELL_DISABLED);
        CHECK(ctos_ssh_native_client_begin_shell(fixture->client, 80, 24) != 0 && errno == EACCES);
        CHECK(ctos_ssh_native_client_begin_password_auth(fixture->client) == 0);
        drive_password(fixture, CTOS_SSH_PASSWORD_AUTH_SUCCESS, 0);
    } else {
        struct sshkey *key = NULL;
        unsigned char *blob = NULL;
        size_t length = 0;
        struct sshbuf *file = sshbuf_new();
        CHECK(file != NULL && sshkey_generate(KEY_ED25519, 0, &key) == 0);
        CHECK(sshkey_to_blob(key, &blob, &length) == 0);
        CHECK(sshkey_private_to_fileblob(key, file, "", "channel", SSHKEY_PRIVATE_OPENSSH, NULL, 0) == 0);
        setup_private_fixture_version_mode(fixture, new_host, key, file, blob, length, NULL, 0, 1, mode);
        CHECK(ctos_ssh_native_client_begin_private_key_auth(fixture->client) == 0);
        drive_private(fixture, CTOS_SSH_PRIVATE_KEY_AUTH_SUCCESS, 0);
        sshbuf_free(file);
        free(blob);
        sshkey_free(key);
        fixture->identity = NULL;
    }
    fixture->shell_expected = 1;
    fixture->shell_window = window;
    fixture->shell_maxpacket = maximum;
    fixture->shell_reject = reject;
    CHECK(ctos_ssh_native_client_begin_shell(fixture->client, 0, 24) != 0 && errno == EINVAL);
    CHECK(ctos_ssh_native_client_begin_shell(fixture->client, 80, 4097) != 0 && errno == EINVAL);
    CHECK(ctos_ssh_native_client_begin_shell(fixture->client, 80, 24) == 0);
    CHECK(ctos_ssh_native_client_begin_shell(fixture->client, 80, 24) != 0 && errno == EALREADY);
    CHECK(shell_status(fixture).phase == CTOS_SSH_SHELL_OPENING);
}

static void setup_shell_fixture(struct fixture *fixture, int kind, uint32_t window,
    uint32_t maximum, int reject) {
    setup_shell_fixture_mode(fixture, kind, window, maximum, reject, CTOS_SSH_HOST_KEY_PINNED, 0);
}

static void send_channel_exit(struct fixture *fixture, int signalled) {
    peer_channel_packet(fixture, SSH2_MSG_CHANNEL_REQUEST);
    CHECK(sshpkt_put_cstring(fixture->server, signalled ? "exit-signal" : "exit-status") == 0);
    CHECK(sshpkt_put_u8(fixture->server, 0) == 0);
    if (signalled) {
        CHECK(sshpkt_put_cstring(fixture->server, "TERM") == 0);
        CHECK(sshpkt_put_u8(fixture->server, 0) == 0);
        CHECK(sshpkt_put_cstring(fixture->server, "synthetic") == 0);
        CHECK(sshpkt_put_cstring(fixture->server, "en") == 0);
    } else CHECK(sshpkt_put_u32(fixture->server, UINT32_MAX) == 0);
    CHECK(sshpkt_send(fixture->server) == 0);
}

static void send_window(struct fixture *fixture, uint32_t value) {
    peer_channel_packet(fixture, SSH2_MSG_CHANNEL_WINDOW_ADJUST);
    CHECK(sshpkt_put_u32(fixture->server, value) == 0);
    CHECK(sshpkt_send(fixture->server) == 0);
}

static void test_shell_roundtrip(void) {
    for (int scenario = 0; scenario < 6; scenario++) {
        int kind = scenario % 2 + 1;
        struct fixture fixture;
        size_t accepted, length, sent = 0;
        unsigned char output[64];
        static const unsigned char command[] = "printf channel\\n\n";
        setup_shell_fixture_mode(&fixture, kind, 16U, 8U, 0,
            scenario < 2 ? CTOS_SSH_HOST_KEY_PINNED : CTOS_SSH_HOST_KEY_MANAGED,
            scenario >= 4);
        drive_shell_phase(&fixture, CTOS_SSH_SHELL_RUNNING);
        CHECK(fixture.shell_stage == 3);
        CHECK(ctos_ssh_native_client_shell_write(fixture.client, command, sizeof(command) - 1U,
            &accepted) == 0 && accepted == 8U);
        sent += accepted;
        CHECK(ctos_ssh_native_client_shell_write(fixture.client, command + sent,
            sizeof(command) - 1U - sent, &accepted) == 0 && accepted == 8U);
        sent += accepted;
        CHECK(ctos_ssh_native_client_shell_write(fixture.client, command + sent,
            sizeof(command) - 1U - sent, &accepted) == 0 && accepted == 0);
        send_window(&fixture, 32U);
        for (unsigned int turn = 0; turn < 10000U && sent < sizeof(command) - 1U; turn++) {
            CHECK(shell_turn(&fixture) == 0);
            CHECK(ctos_ssh_native_client_shell_write(fixture.client, command + sent,
                sizeof(command) - 1U - sent, &accepted) == 0);
            sent += accepted;
        }
        CHECK(sent == sizeof(command) - 1U);
        CHECK(ctos_ssh_native_client_shell_resize(fixture.client, 132, 43) == 1);
        CHECK(ctos_ssh_native_client_shell_eof(fixture.client) == 1);
        CHECK(ctos_ssh_native_client_shell_eof(fixture.client) != 0 && errno == EACCES);
        CHECK(ctos_ssh_native_client_shell_write(fixture.client, command, 1, &accepted) != 0
            && accepted == 0 && errno == EACCES);
        for (unsigned int turn = 0; turn < 10000U && fixture.shell_eof_count == 0; turn++)
            CHECK(shell_turn(&fixture) == 0);
        CHECK(fixture.shell_input_length == sizeof(command) - 1U
            && memcmp(fixture.shell_input, command, sizeof(command) - 1U) == 0);
        CHECK(fixture.shell_resize_count == 1 && fixture.shell_eof_count == 1);
        peer_channel_data(&fixture, 0, (const unsigned char *)"channel\r\n", 9U);
        peer_channel_data(&fixture, 1, (const unsigned char *)"stderr\n", 7U);
        send_channel_exit(&fixture, kind == 2);
        peer_channel_packet(&fixture, SSH2_MSG_CHANNEL_EOF);
        CHECK(sshpkt_send(fixture.server) == 0);
        peer_channel_packet(&fixture, SSH2_MSG_CHANNEL_CLOSE);
        CHECK(sshpkt_send(fixture.server) == 0);
        drive_shell_phase(&fixture, CTOS_SSH_SHELL_CLOSED);
        struct ctos_ssh_native_shell_status status = shell_status(&fixture);
        CHECK(status.buffered_bytes == 16U && status.remote_eof && status.local_eof);
        CHECK(status.exit_status_known == (kind == 1) && status.exit_signal == (kind == 2));
        CHECK(status.exit_status == (kind == 1 ? UINT32_MAX : 0));
        CHECK(ctos_ssh_native_client_shell_read(fixture.client, output, 4U, &length) == 0 && length == 4U);
        CHECK(memcmp(output, "chan", 4U) == 0);
        CHECK(ctos_ssh_native_client_shell_read(fixture.client, output, sizeof(output), &length) == 0 && length == 12U);
        CHECK(memcmp(output, "nel\r\nstderr\n", 12U) == 0);
        CHECK(ctos_ssh_native_client_shell_read(fixture.client, output, sizeof(output), &length) == 0 && length == 0);
        CHECK(fixture.shell_credit == 0);
        for (unsigned int turn = 0; turn < 1000U && fixture.shell_close_count == 0; turn++)
            CHECK(pump_synthetic_server(&fixture, 64U, NULL, NULL) == 0);
        CHECK(fixture.shell_close_count == 1 && fixture.callback.selected_calls == 1);
        fixture.callback.release_failures_remaining = 1;
        CHECK(ctos_ssh_native_client_close(fixture.client) != 0 && errno == EIO);
        CHECK(fcntl(fixture.pair[0], F_GETFD) >= 0);
        cleanup_fixture(&fixture, 2);
    }
}

static void test_shell_rejection_and_auth_gate(void) {
    for (int reject = 1; reject <= 3; reject++) {
        struct fixture fixture;
        setup_shell_fixture(&fixture, 1, 16U, 8U, reject);
        drive_shell_phase(&fixture, CTOS_SSH_SHELL_REJECTED);
        CHECK(ctos_ssh_native_client_step(fixture.client,
            &(struct ctos_ssh_native_client_step){0}) != 0 && errno == EALREADY);
        for (unsigned int turn = 0; turn < 1000U && reject != 1 && fixture.shell_close_count == 0; turn++)
            CHECK(pump_synthetic_server(&fixture, 64U, NULL, NULL) == 0);
        CHECK(fixture.shell_close_count == (reject == 1 ? 0U : 1U));
        CHECK(fixture.shell_stage == reject);
        cleanup_fixture(&fixture, 1);
    }
    for (int reply = PASSWORD_REPLY_REJECTED; reply <= PASSWORD_REPLY_CHANGE; reply++) {
        struct fixture fixture;
        setup_password_fixture(&fixture, 0, 15000U);
        fixture.password_reply = (enum password_reply)reply;
        CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
        drive_password(&fixture, (enum ctos_ssh_native_client_password_auth_result)(reply + 2), 0);
        CHECK(ctos_ssh_native_client_begin_shell(fixture.client, 80, 24) != 0 && errno == EACCES);
        CHECK(shell_status(&fixture).phase == CTOS_SSH_SHELL_DISABLED);
        cleanup_fixture(&fixture, 1);
    }
}

static void test_shell_window_and_backpressure(void) {
    struct fixture fixture;
    unsigned char bytes[32768], output[32768];
    size_t accepted, length;
    memset(bytes, 'w', sizeof(bytes));
    setup_shell_fixture(&fixture, 1, 0, 32768U, 0);
    drive_shell_phase(&fixture, CTOS_SSH_SHELL_RUNNING);
    CHECK(ctos_ssh_native_client_shell_write(fixture.client, bytes, sizeof(bytes), &accepted) == 0 && accepted == 0);
    send_window(&fixture, UINT32_MAX);
    for (unsigned int turn = 0; turn < 1000U; turn++) CHECK(shell_turn(&fixture) == 0);
    unsigned int queued = 0;
    do {
        CHECK(ctos_ssh_native_client_shell_write(fixture.client, bytes, sizeof(bytes), &accepted) == 0);
        if (accepted != 0) queued++;
    } while (accepted != 0 && queued < 20U);
    CHECK(queued >= 2U && queued < 5U && accepted == 0);
    /* Do not drain the synthetic peer's stdin buffer beyond its test bound. */
    cleanup_fixture(&fixture, 1);
    setup_shell_fixture(&fixture, 1, 16, 8, 0);
    drive_shell_phase(&fixture, CTOS_SSH_SHELL_RUNNING);
    peer_channel_data(&fixture, 0, bytes, sizeof(bytes));
    peer_channel_data(&fixture, 1, bytes, sizeof(bytes));
    for (unsigned int turn = 0; turn < 40000U && shell_status(&fixture).buffered_bytes != 65536U; turn++)
        CHECK(shell_turn(&fixture) == 0);
    CHECK(shell_status(&fixture).buffered_bytes == 65536U && fixture.shell_credit == 0);
    CHECK(ctos_ssh_native_client_shell_read(fixture.client, output, sizeof(output), &length) == 0 && length == sizeof(output));
    CHECK(memcmp(output, bytes, length) == 0);
    for (unsigned int turn = 0; turn < 1000U && fixture.shell_credit == 0; turn++) CHECK(shell_turn(&fixture) == 0);
    CHECK(fixture.shell_credit == 32768U);
    peer_channel_data(&fixture, 0, bytes, sizeof(bytes));
    for (unsigned int turn = 0; turn < 40000U && shell_status(&fixture).buffered_bytes != 65536U; turn++)
        CHECK(shell_turn(&fixture) == 0);
    CHECK(shell_status(&fixture).buffered_bytes == 65536U);
    peer_channel_data(&fixture, 0, bytes, 1U);
    int failed = 0;
    for (unsigned int turn = 0; turn < 1000U; turn++) {
        if (shell_turn(&fixture) != 0) { CHECK(errno == EPROTO); failed = 1; break; }
    }
    CHECK(failed);
    memset(output, 0xaa, sizeof(output));
    length = 99U;
    CHECK(ctos_ssh_native_client_shell_read(fixture.client, output, sizeof(output), &length) != 0 && length == 0);
    for (size_t index = 0; index < sizeof(output); index++) CHECK(output[index] == 0);
    cleanup_fixture(&fixture, 1);
}

static void test_shell_bad_packets(void) {
    for (int mode = 0; mode < 12; mode++) {
        struct fixture fixture;
        setup_shell_fixture(&fixture, 1, mode == 2 ? UINT32_MAX : 16U, 8U, 0);
        drive_shell_phase(&fixture, CTOS_SSH_SHELL_RUNNING);
        if (mode == 0) {
            CHECK(sshpkt_start(fixture.server, SSH2_MSG_CHANNEL_EOF) == 0);
            CHECK(sshpkt_put_u32(fixture.server, 1) == 0);
        } else if (mode == 1 || mode == 2) {
            peer_channel_packet(&fixture, SSH2_MSG_CHANNEL_WINDOW_ADJUST);
            CHECK(sshpkt_put_u32(fixture.server, mode == 1 ? 0 : 1) == 0);
        } else if (mode == 3) {
            peer_channel_packet(&fixture, SSH2_MSG_CHANNEL_EXTENDED_DATA);
            CHECK(sshpkt_put_u32(fixture.server, 2) == 0);
            CHECK(sshpkt_put_cstring(fixture.server, "x") == 0);
        } else if (mode == 4 || mode == 5) {
            peer_channel_packet(&fixture, SSH2_MSG_CHANNEL_EOF);
            CHECK(sshpkt_send(fixture.server) == 0);
            if (mode == 4) peer_channel_packet(&fixture, SSH2_MSG_CHANNEL_EOF);
            else {
                peer_channel_packet(&fixture, SSH2_MSG_CHANNEL_DATA);
                CHECK(sshpkt_put_cstring(fixture.server, "late") == 0);
            }
        } else if (mode == 6 || mode == 7) {
            send_channel_exit(&fixture, 0);
            if (mode == 6) send_channel_exit(&fixture, 0);
            else send_channel_exit(&fixture, 1);
        } else if (mode == 8) {
            peer_channel_packet(&fixture, SSH2_MSG_CHANNEL_SUCCESS);
        } else if (mode == 9) {
            peer_channel_packet(&fixture, SSH2_MSG_CHANNEL_EOF);
            CHECK(sshpkt_put_u8(fixture.server, 0) == 0);
        } else if (mode == 10) {
            peer_channel_packet(&fixture, SSH2_MSG_CHANNEL_REQUEST);
            CHECK(sshpkt_put_cstring(fixture.server, "exit-status") == 0);
            CHECK(sshpkt_put_u8(fixture.server, 1) == 0);
            CHECK(sshpkt_put_u32(fixture.server, 0) == 0);
        } else {
            unsigned char oversized[32769] = {0};
            peer_channel_packet(&fixture, SSH2_MSG_CHANNEL_DATA);
            CHECK(sshpkt_put_string(fixture.server, oversized, sizeof(oversized)) == 0);
        }
        if (mode != 6 && mode != 7) CHECK(sshpkt_send(fixture.server) == 0);
        int failed = 0;
        for (unsigned int turn = 0; turn < 20000U; turn++) {
            int result = shell_turn(&fixture);
            if (result != 0) { CHECK(errno == EPROTO); failed = 1; break; }
        }
        CHECK(failed);
        cleanup_fixture(&fixture, 1);
    }
}

static void test_shell_after_auth_expiry(void) {
    struct fixture fixture;
    struct ctos_ssh_role_snapshot snapshot;
    unsigned char receipt[32], output[32];
    size_t length = 0, accepted = 0;
    setup_password_fixture(&fixture, 0, 3000U);
    CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
    drive_password(&fixture, CTOS_SSH_PASSWORD_AUTH_SUCCESS, 0);
    fixture.shell_expected = 1;
    fixture.shell_window = 16U;
    fixture.shell_maxpacket = 8U;
    CHECK(ctos_ssh_native_client_begin_shell(fixture.client, 80, 24) == 0);
    drive_shell_phase(&fixture, CTOS_SSH_SHELL_RUNNING);
    /* Expiring both clocks cannot renew authentication, credential or file authority. */
    fixture.view->deadline = fixture.deadline;
    wait_until_deadline_attempts(fixture.deadline, 5000U);
    CHECK(ctos_ssh_role_bootstrap_snapshot(fixture.role, &snapshot) != 0 && errno == ETIMEDOUT);
    /* The client consumed/released its borrow at promotion; no auth view may be retained. */
    fixture.view_borrow = NULL;
    CHECK(ctos_ssh_native_client_authentication_selection_receipt(fixture.client, receipt) != 0
        && errno == EACCES);
    CHECK(ctos_ssh_native_client_selected_credential_bytes(fixture.client,
        output, sizeof(output), &length) != 0 && length == 0);
    CHECK(ctos_ssh_native_client_shell_write(fixture.client,
        (const unsigned char *)"x", 1U, &accepted) == 0 && accepted == 1U);
    CHECK(ctos_ssh_native_client_shell_resize(fixture.client, 132, 43) == 1);
    peer_channel_data(&fixture, 0, (const unsigned char *)"alive", 5U);
    for (unsigned int turn = 0; turn < 1000U && shell_status(&fixture).buffered_bytes != 5U; turn++)
        CHECK(shell_turn(&fixture) == 0);
    CHECK(ctos_ssh_native_client_shell_read(fixture.client, output, sizeof(output), &length) == 0
        && length == 5U && memcmp(output, "alive", 5U) == 0);
    CHECK(ctos_ssh_native_client_shell_eof(fixture.client) == 1);
    /* Explicit revocation remains terminal even after natural expiry. */
    CHECK(ctos_ssh_callback_cancel(fixture.callback_token) == 0);
    CHECK(ctos_ssh_native_client_shell_read(fixture.client, output, sizeof(output), &length) != 0
        && length == 0);
    cleanup_fixture(&fixture, 1U);
}

static void test_shell_liveness_and_eof(void) {
    for (int mode = 0; mode < 5; mode++) {
        struct fixture fixture;
        unsigned char output[32];
        size_t length = 99;
        setup_shell_fixture(&fixture, 1, 16U, 8U, 0);
        drive_shell_phase(&fixture, CTOS_SSH_SHELL_RUNNING);
        peer_channel_data(&fixture, 0, (const unsigned char *)"kept", 4U);
        for (unsigned int turn = 0; turn < 1000U && shell_status(&fixture).buffered_bytes != 4U; turn++)
            CHECK(shell_turn(&fixture) == 0);
        CHECK(shell_status(&fixture).buffered_bytes == 4U);
        if (mode == 0) {
            struct ctos_ssh_native_client_step step = {.cancelled = 1};
            CHECK(ctos_ssh_native_client_step(fixture.client, &step) != 0 && errno == ECANCELED);
        } else if (mode == 1) {
            CHECK(ctos_ssh_callback_cancel(fixture.callback_token) == 0);
        } else if (mode == 2) {
            fixture.view->valid = 0;
            CHECK(ctos_ssh_native_client_shell_read(fixture.client, output, sizeof(output), &length) == 0
                && length == 4U && memcmp(output, "kept", 4U) == 0);
            CHECK(ctos_ssh_callback_check_live(fixture.callback_token, &fixture.callback) != 0
                && errno == EACCES);
            CHECK(shell_status(&fixture).phase == CTOS_SSH_SHELL_RUNNING);
            CHECK(ctos_ssh_callback_cancel(fixture.callback_token) == 0);
        } else if (mode == 3) {
            peer_channel_packet(&fixture, SSH2_MSG_CHANNEL_EOF);
            CHECK(sshpkt_send(fixture.server) == 0);
            for (unsigned int turn = 0; turn < 1000U && !shell_status(&fixture).remote_eof; turn++)
                CHECK(shell_turn(&fixture) == 0);
            size_t accepted;
            CHECK(ctos_ssh_native_client_shell_write(fixture.client, (const unsigned char *)"x", 1U, &accepted) == 0 && accepted == 1U);
            CHECK(shell_status(&fixture).exit_status_known == 0);
            CHECK(shutdown(fixture.pair[1], SHUT_WR) == 0);
            int failed = 0;
            for (unsigned int turn = 0; turn < 1000U; turn++) {
                struct ctos_ssh_native_client_step step = {0};
                if (ctos_ssh_native_client_step(fixture.client, &step) != 0) {
                    CHECK(errno == EPIPE); failed = 1; break;
                }
            }
            CHECK(failed);
        } else {
            /* Queue valid data/status/EOF/CLOSE into transport, then half-close
             * before the client can consume its one-packet-per-turn backlog. */
            peer_channel_data(&fixture, 0, (const unsigned char *)"tail", 4U);
            send_channel_exit(&fixture, 0);
            peer_channel_packet(&fixture, SSH2_MSG_CHANNEL_EOF);
            CHECK(sshpkt_send(fixture.server) == 0);
            peer_channel_packet(&fixture, SSH2_MSG_CHANNEL_CLOSE);
            CHECK(sshpkt_send(fixture.server) == 0);
            size_t pending;
            for (unsigned int turn = 0; turn < 1000U; turn++) {
                CHECK(pump_synthetic_server(&fixture, 64U, NULL, NULL) == 0);
                (void)ssh_output_ptr(fixture.server, &pending);
                if (pending == 0) break;
            }
            CHECK(pending == 0 && shutdown(fixture.pair[1], SHUT_WR) == 0);
            for (unsigned int turn = 0; turn < 1000U && shell_status(&fixture).phase != CTOS_SSH_SHELL_CLOSED; turn++) {
                struct ctos_ssh_native_client_step step = {0};
                CHECK(ctos_ssh_native_client_step(fixture.client, &step) == 0);
            }
            CHECK(shell_status(&fixture).phase == CTOS_SSH_SHELL_CLOSED);
            CHECK(ctos_ssh_native_client_shell_read(fixture.client, output, sizeof(output), &length) == 0 && length == 8U);
            CHECK(memcmp(output, "kepttail", 8U) == 0);
            cleanup_fixture(&fixture, 1);
            continue;
        }
        memset(output, 0xaa, sizeof(output));
        CHECK(ctos_ssh_native_client_shell_read(fixture.client, output, sizeof(output), &length) != 0 && length == 0);
        for (size_t index = 0; index < sizeof(output); index++) CHECK(output[index] == 0);
        cleanup_fixture(&fixture, 1);
    }
}

static void test_pending_host_guard(void) {
    struct sshkey *key = NULL, *other = NULL;
    unsigned char fingerprint[32];
    CHECK(sshkey_generate(KEY_ED25519, 0, &key) == 0);
    CHECK(sshkey_generate(KEY_ED25519, 0, &other) == 0);
    CHECK(expected_fingerprint(key, fingerprint) == 0);
    for (unsigned int mode = 0; mode < 9U; mode++) {
        struct ssh *ssh = NULL;
        struct kex_params params;
        ctos_ssh_role_token role = 0;
        struct ctos_ssh_role_request request = role_request(deadline_after(mode == 6 ? 100U : 5000U));
        struct ctos_ssh_role_snapshot snapshot;
        CHECK(ctos_ssh_role_bootstrap_open(&request, &role) == 0);
        CHECK(ctos_ssh_role_bootstrap_snapshot(role, &snapshot) == 0);
        make_kex_params(&params);
        CHECK(ssh_init(&ssh, 0, &params) == 0);
        if (mode == 5) CHECK(ctos_ssh_kex_bind_role(ssh, role, fingerprint) == 0);
        else CHECK(ctos_ssh_kex_bind_pending_host(ssh, role) == 0);
        CHECK(ctos_ssh_kex_bind_pending_host(ssh, role) != 0);
        CHECK(ctos_ssh_kex_bind_role(ssh, role, fingerprint) != 0);
        CHECK(ctos_ssh_kex_ready(ssh, snapshot.hop, snapshot.role_nonce,
            snapshot.connection_binding) == 0);
        CHECK(ctos_ssh_kex_pin_offered_host(ssh, key) != 0);
        CHECK(ctos_ssh_kex_begin(ssh) == 0);
        if (mode == 1) CHECK(ctos_ssh_kex_approved(ssh, key) != 0);
        else if (mode == 2) CHECK(ctos_ssh_kex_signed(ssh, key) != 0);
        else if (mode == 3) CHECK(ctos_ssh_kex_complete(ssh) != 0);
        else if (mode == 4) {
            CHECK(ctos_ssh_kex_pin_offered_host(ssh, key) == 0);
            CHECK(ctos_ssh_kex_pin_offered_host(ssh, other) != 0);
            CHECK(ctos_ssh_kex_approved(ssh, key) == 0);
            CHECK(ctos_ssh_kex_signed(ssh, other) != 0);
        } else if (mode == 5) {
            CHECK(ctos_ssh_kex_pin_offered_host(ssh, other) != 0);
            CHECK(ctos_ssh_kex_approved(ssh, key) == 0);
        } else if (mode == 6) {
            wait_until_deadline(snapshot.deadline);
            CHECK(ctos_ssh_kex_pin_offered_host(ssh, key) != 0);
        } else if (mode == 7) {
            CHECK(ctos_ssh_kex_approved(ssh, key) != 0);
            CHECK(ctos_ssh_kex_begin(ssh) == 0);
            CHECK(ctos_ssh_kex_pin_offered_host(ssh, key) != 0);
        } else {
            CHECK(ctos_ssh_kex_pin_offered_host(ssh, NULL) != 0);
            CHECK(ctos_ssh_kex_pin_offered_host(ssh, key) == 0);
            CHECK(ctos_ssh_kex_approved(ssh, key) == 0);
            CHECK(ctos_ssh_kex_signed(ssh, key) == 0);
            CHECK(ctos_ssh_kex_pin_offered_host(ssh, key) != 0);
        }
        CHECK(ctos_ssh_kex_ready(ssh, snapshot.hop, snapshot.role_nonce,
            snapshot.connection_binding) == 0);
        ctos_ssh_kex_unbind(ssh);
        ssh_free(ssh);
        if (mode == 6) CHECK(ctos_ssh_role_bootstrap_close(role) != 0 && errno == ENOENT);
        else CHECK(ctos_ssh_role_bootstrap_close(role) == 0);
    }
    sshkey_free(other);
    sshkey_free(key);
}

static void test_managed_host_denials(void) {
    for (unsigned int mode = 0; mode < 13U; mode++) {
        struct fixture fixture = {0};
        struct sshkey *different = NULL;
        const struct sshkey *known = NULL;
        const char *prefix = NULL;
        fixture.pair[0] = fixture.pair[1] = -1;
        fixture.callback.release_fd = -1;
        if (mode == 12) {
            fixture.private_key = make_host_certificate(&fixture.certificate_authority);
        } else CHECK(sshkey_generate(KEY_ED25519, 0, &fixture.private_key) == 0);
        CHECK(sshkey_from_private(fixture.private_key, &fixture.public_key) == 0);
        fixture.host_key = fixture.public_key;
        if (mode == 0) {
            CHECK(sshkey_generate(KEY_ED25519, 0, &different) == 0);
            known = different;
        }
        if (mode == 1) prefix = "@revoked ";
        finish_fixture_setup_selected_mode(&fixture,
            mode == 8 ? TEST_EXPIRY_PREPARATION_MS : 15000U,
            known, prefix, mode >= 3 && mode < 12, 0, 1U, 0, CTOS_SSH_HOST_KEY_MANAGED);
        fixture.callback.selected_callback = fixture.callback_token;
        fixture.callback.selected_view = fixture.view;
        fixture.callback.selected_role = fixture.role;
        fixture.callback.selected_deadline = fixture.deadline;
        if (mode == 2) {
            struct ctos_ssh_file *file = &fixture.view->files[1];
            CHECK(file->type == CTOS_SSH_KNOWN_HOSTS && file->body.length > 0);
            memset((void *)file->body.data, '!', file->body.length);
        }
        if (mode == 3) fixture.callback.fail_host_evidence = 1;
        if (mode >= 4 && mode <= 11) fixture.callback.host_action = (int)mode - 3;
        unsigned char output[32];
        size_t length = 99;
        memset(output, 0xaa, sizeof(output));
        CHECK(ctos_ssh_native_client_selected_credential_bytes(fixture.client,
            output, sizeof(output), &length) != 0 && length == 0);
        for (size_t index = 0; index < sizeof(output); index++) CHECK(output[index] == 0);
        setup_synthetic_server(&fixture);
        int failed = 0;
        for (unsigned int turn = 0; turn < 100000U; turn++) {
            if (ctos_ssh_native_client_step(fixture.client,
                    &(struct ctos_ssh_native_client_step){0}) != 0) {
                failed = 1;
                break;
            }
            CHECK(!ctos_ssh_native_client_complete(fixture.client));
            CHECK(pump_synthetic_server(&fixture, 64U, NULL, NULL) == 0);
        }
        CHECK(failed && !ctos_ssh_native_client_complete(fixture.client));
        CHECK(fixture.callback.kex_complete_calls == 0 && fixture.callback.selected_calls == 0);
        CHECK(fixture.callback.host_evidence_calls == (mode >= 3 && mode < 12 ? 1U : 0U));
        CHECK(ctos_ssh_native_client_step(fixture.client,
            &(struct ctos_ssh_native_client_step){0}) != 0 && errno == EALREADY);
        CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) != 0);
        CHECK(fixture.callback.selected_calls == 0);
        fixture.callback.environment = &fixture.callback;
        if (mode == 8 || mode == 10) fixture.role = 0;
        cleanup_fixture(&fixture, 1);
        sshkey_free(different);
    }
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--view-roots-only") == 0) {
        test_actual_view_roots();
        printf("SSH_NATIVE_VIEW_ROOTS checks=%u gate=pass runtime=synthetic\n", checks);
        return 0;
    }
    if (argc == 2 && strcmp(argv[1], "--managed-host-only") == 0) {
        test_pending_host_guard();
        test_managed_host_denials();
        test_shell_roundtrip();
        printf("SSH_NATIVE_MANAGED_HOST checks=%u gate=pass runtime=synthetic\n", checks);
        return 0;
    }
    if (argc == 2 && strcmp(argv[1], "--shell-only") == 0) {
        test_shell_roundtrip();
        test_shell_rejection_and_auth_gate();
        test_shell_window_and_backpressure();
        test_shell_bad_packets();
        test_shell_liveness_and_eof();
        test_shell_after_auth_expiry();
        printf("SSH_NATIVE_SHELL checks=%u gate=pass runtime=synthetic\n", checks);
        return 0;
    }
    CHECK(argc == 1);
    test_pending_host_guard();
    test_managed_host_denials();
    test_admission_and_descriptor();
    test_prepare_tcp_descriptor();
    test_failed_open_releases_kex_role();
    test_malformed_view_slice();
    test_actual_view_roots();
    test_step_and_close();
    test_verify_known_host_key_proof();
    test_fragmented_identification_is_atomic();
    test_kex_completion_host_proof(2U);
    test_kex_completion_host_proof(64U);
    test_kex_completion_new_host_proof(2U);
    test_kex_completion_new_host_proof(64U);
    test_userauth_service_replies();
    test_userauth_service_early_accept();
    test_userauth_service_owner_and_cleanup();
    test_userauth_service_liveness();
    test_verify_new_host_key_candidate();
    test_verify_new_host_key_candidate_rollback();
    test_verify_changed_host_key_classification();
    test_verify_revoked_host_key_classification();
    test_verify_certificate_host_key_classification();
    test_verify_invalid_hostfile_classification();
    test_step_fragmented_banner_output();
    test_step_half_close();
    test_step_expired_view_preserves_errno();
    test_step_deadline();
    test_step_invalid_view_preserves_errno();
    test_close_retry_order();
    test_selected_handoff_after_real_service();
    test_selected_handoff_failures_keep_secret_private();
    test_selection_admission_and_owned_receipt();
    test_selection_receipt_rejects_read_without_mutating_client();
    test_password_auth_outcomes();
    test_password_auth_banner_while_request_pending();
    test_password_auth_secret_bounds();
    test_password_auth_bad_replies();
    test_password_auth_methods_and_remote_limits();
    test_password_auth_owner_status_and_close();
    test_password_auth_admission_and_provider_failure();
    test_password_auth_active_liveness();
    test_private_auth();
    test_encrypted_private_auth();
    test_shell_roundtrip();
    test_shell_rejection_and_auth_gate();
    test_shell_window_and_backpressure();
    test_shell_bad_packets();
    test_shell_liveness_and_eof();
    test_shell_after_auth_expiry();
    printf("SSH_NATIVE_CLIENT checks=%u admission=pass descriptor=pass view=pass close=pass open_failure_ownership=pass runtime=synthetic\n",
        checks);
    return 0;
}

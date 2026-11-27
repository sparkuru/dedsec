#define _GNU_SOURCE

#include "ssh_native_client.h"

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
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
#include "ssh_config_eval.h"
#include "ssh_material_inspect.h"
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
static int child_test_case = -1;
static int fixture_jump_role;
static const char *fixture_chain_identity;
static int fixture_fresh_host_mode;
static ctos_ssh_role_token fixture_existing_role;
static struct ctos_ssh_native_client *fixture_jump_parent;
static struct ctos_ssh_native_jump_relay *fixture_child_relay;
static int fixture_expect_child_no_relay;
static int fixture_check_borrow_transaction;
struct fixture;
static struct fixture *fixture_parent_context;
static struct fixture *fixture_root_context;

struct child_wrong_owner_attempt {
    struct ctos_ssh_native_client *parent, *child;
    struct ctos_ssh_native_jump_relay *relay;
    struct ctos_ssh_native_client_config config;
    int open_result, open_error, step_result, step_error;
    int close_result, close_error, relay_result, relay_error;
};

static void *child_wrong_owner(void *opaque) {
    struct child_wrong_owner_attempt *attempt = opaque;
    struct ctos_ssh_native_client *child = NULL;
    struct ctos_ssh_native_jump_relay *relay = NULL;
    attempt->open_result = ctos_ssh_native_client_open_jump_child(attempt->parent,
        &attempt->config, &child, &relay);
    attempt->open_error = errno;
    if (child != NULL || relay != NULL) attempt->open_result = 0;
    attempt->step_result = ctos_ssh_native_client_step(attempt->child,
        &(struct ctos_ssh_native_client_step){0});
    attempt->step_error = errno;
    attempt->close_result = ctos_ssh_native_client_close(attempt->child);
    attempt->close_error = errno;
    attempt->relay_result = ctos_ssh_native_jump_relay_close(attempt->relay);
    attempt->relay_error = errno;
    return NULL;
}

struct child_retry_owner_attempt {
    struct ctos_ssh_native_client *child;
    struct ctos_ssh_native_jump_relay *relay;
    int close_result, close_error, destroy_result, destroy_error, relay_result, relay_error;
};

static void *child_retry_wrong_owner(void *opaque) {
    struct child_retry_owner_attempt *attempt = opaque;
    attempt->close_result = ctos_ssh_native_client_close(attempt->child);
    attempt->close_error = errno;
    attempt->destroy_result = ctos_ssh_native_client_destroy(attempt->child);
    attempt->destroy_error = errno;
    attempt->relay_result = ctos_ssh_native_jump_relay_close(attempt->relay);
    attempt->relay_error = errno;
    return NULL;
}

#define TEST_STEP_BUDGET 3U
static size_t fixture_step_budget = TEST_STEP_BUDGET;
/* Complete QEMU handshakes before deliberately expiring the original deadline. */
#define TEST_EXPIRY_PREPARATION_MS 5000U
#define TEST_EXPIRY_WAIT_ATTEMPTS 6000U

#define CHECK(condition) do { \
    checks++; \
    if (!(condition)) { \
        fprintf(stderr, "SSH_NATIVE_CLIENT failure line=%u errno=%d child_case=%d\\n", \
            (unsigned int)__LINE__, errno, child_test_case); \
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
    unsigned char bytes[32768];
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

static struct ctos_ssh_view *load_two_host_views(const unsigned char *body, size_t length,
    const unsigned char *other, size_t other_length, uint64_t deadline) {
    struct wire wire = {0};
    unsigned char uuid[16] = {9}, binding[32] = {8}, type = CTOS_SSH_CONFIG;
    struct ctos_ssh_view *view;
    wire_u32(&wire, 0x43535056U); wire_u32(&wire, 1U);
    wire_put(&wire, uuid, sizeof(uuid)); wire_u64(&wire, 91U); wire_u64(&wire, 11U);
    wire_u64(&wire, deadline); wire_text(&wire, "native-client-request");
    wire_text(&wire, "native-client-connection"); wire_text(&wire, "ssh/config");
    wire_put(&wire, binding, sizeof(binding)); wire_u32(&wire, 3U);
    wire_put(&wire, &type, 1U); wire_text(&wire, "root"); wire_text(&wire, "ssh/config");
    wire_text(&wire, "native client managed config"); wire_u32(&wire, 0U); wire_u32(&wire, 2U);
    wire_text(&wire, "ssh/known_hosts"); wire_text(&wire, ".ssh/known_hosts"); wire_u32(&wire, 0U);
    wire_known_hosts_path(&wire, body, length, "ssh/known_hosts");
    type = CTOS_SSH_KNOWN_HOSTS;
    wire_put(&wire, &type, 1U); wire_text(&wire, "other-trust");
    wire_text(&wire, ".ssh/known_hosts"); wire_text(&wire, "other group");
    CHECK(other_length <= UINT32_MAX); wire_u32(&wire, (uint32_t)other_length);
    wire_u32(&wire, 0U); wire_u32(&wire, (uint32_t)other_length); wire_put(&wire, other, other_length);
    view = calloc(1, sizeof(*view));
    CHECK(view != NULL && ctos_ssh_view_allocate(view, wire.length) == 0);
    memcpy(view->mapping, wire.bytes, wire.length);
    CHECK(ctos_ssh_view_validate(view) == 0);
    return view;
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
    unsigned int candidate_abort_failures_remaining;
    int enable_changed;
    int enable_provisional, fail_provisional_candidate, deny_provisional_review;
    int cancel_provisional_review, cancel_provisional_kex;
    int close_provisional_candidate, close_provisional_review, close_provisional_kex;
    unsigned int provisional_candidate_calls, provisional_review_calls, provisional_kex_calls;
    int deny_changed_review;
    int fail_changed_commit;
    int expire_changed_review;
    int cancel_changed_review;
    int invalidate_changed_view;
    unsigned int changed_candidate_calls;
    unsigned int changed_review_calls;
    unsigned int changed_commit_calls;
    unsigned int changed_commit_attempts;
    unsigned char *changed_records;
    size_t changed_records_length;
    unsigned int callback_event_order;
    unsigned int candidate_event_order;
    unsigned int host_evidence_event_order;
    unsigned int release_calls;
    unsigned int release_failures_remaining;
    unsigned int credential_calls;
    unsigned int expected_credential_calls;
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
    int scope_probe;
    ctos_ssh_callback_token scope_token, scope_other;
    unsigned int scope_host_checks, scope_selected_checks, scope_after_invalidation;
    unsigned int scope_candidate_checks, scope_review_checks, scope_kex_checks;
    unsigned int scope_closed_checks;
    void (*scope_close_parent)(void *);
    void *scope_close_parent_context;
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

static int validate_receiver(void *context, void *environment, const void *receiver) {
    return validate_environment(context, environment) == 0 && receiver == context ? 0 : -1;
}

static void check_receiver_scope(struct callback_fixture *fixture, void *environment) {
    int other_receiver;
    CHECK(ctos_ssh_native_client_callback_ancestor_proofs_check_receiver(
        fixture->scope_token, environment, fixture, validate_receiver) == 0);
    CHECK(ctos_ssh_native_client_callback_ancestor_proofs_check_receiver(
        fixture->scope_token, environment, &other_receiver, validate_receiver) != 0 && errno == EACCES);
    CHECK(ctos_ssh_native_client_callback_ancestor_proofs_check_receiver(
        fixture->scope_token, environment, NULL, validate_receiver) != 0 && errno == EINVAL);
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
    if (fixture->scope_probe == 1) {
        int wrong_environment = 0;
        CHECK(ctos_ssh_native_client_callback_ancestor_proofs_check(fixture->scope_token, environment) == 0);
        check_receiver_scope(fixture, environment);
        CHECK(ctos_ssh_native_client_callback_ancestor_proofs_check(fixture->scope_token,
            &wrong_environment) != 0 && errno == EPERM);
        CHECK(ctos_ssh_native_client_callback_ancestor_proofs_check(fixture->scope_other,
            environment) != 0 && errno == EACCES);
        fixture->scope_host_checks++;
        if (fixture->scope_close_parent != NULL) {
            fixture->scope_close_parent(fixture->scope_close_parent_context);
            CHECK(ctos_ssh_native_client_callback_ancestor_proofs_check(fixture->scope_token, environment) == 0);
            fixture->scope_closed_checks++;
        }
    } else if (fixture->scope_probe == 3) {
        CHECK(ctos_ssh_native_client_callback_ancestor_proofs_check(fixture->scope_token, environment) != 0
            && errno == EACCES);
        CHECK(ctos_ssh_native_client_callback_ancestor_proofs_check_receiver(
            fixture->scope_token, environment, fixture, validate_receiver) != 0 && errno == EACCES);
        fixture->scope_host_checks++;
    }
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
    if (fixture->scope_probe == 1 && (fixture->host_action == 2
            || fixture->host_action == 3 || fixture->host_action == 7)) {
        CHECK(ctos_ssh_native_client_callback_ancestor_proofs_check(fixture->scope_token, environment) != 0);
        CHECK(ctos_ssh_native_client_callback_ancestor_proofs_check_receiver(
            fixture->scope_token, environment, fixture, validate_receiver) != 0);
        fixture->scope_after_invalidation++;
    }
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
    if (fixture->scope_probe == 1) {
        CHECK(ctos_ssh_native_client_callback_ancestor_proofs_check(fixture->scope_token, environment) == 0);
        check_receiver_scope(fixture, environment);
        fixture->scope_candidate_checks++;
    }
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
    CHECK(fixture->host_evidence_calls == 1 || fixture->changed_candidate_calls == 1
        || fixture->provisional_candidate_calls == 1);
    fixture->candidate_abort_calls++;
    if (fixture->candidate_abort_failures_remaining != 0) {
        fixture->candidate_abort_failures_remaining--;
        return EIO;
    }
    if (fixture->enable_provisional && fixture->candidate_body != NULL)
        memset(fixture->candidate_body, 0, fixture->candidate_body_length);
    return 0;
}

static int changed_host_candidate(void *context, void *environment,
    const struct ctos_ssh_changed_host_candidate *changed) {
    struct callback_fixture *fixture = context;
    CHECK(fixture != NULL && fixture->environment == environment && changed != NULL);
    const struct ctos_ssh_known_hosts_candidate *candidate = &changed->candidate;
    const struct ctos_ssh_host_evidence *evidence = &candidate->evidence;
    CHECK(evidence->classification == CTOS_SSH_CALLBACK_HOST_CHANGED
        && evidence->role == fixture->expected_role && evidence->hop == fixture->expected_hop
        && evidence->port == fixture->expected_port);
    CHECK(strcmp(evidence->key_type, fixture->expected_key_type) == 0
        && strcmp(evidence->fingerprint, fixture->expected_fingerprint) == 0);
    CHECK(candidate->replacement_body != NULL && candidate->replacement_body_length > 0);
    CHECK(changed->old_records != NULL && changed->old_records_length >= 88U
        && read_u32(changed->old_records) == 0x43534854U
        && read_u32(changed->old_records + 8U) == 0U
        && read_u32(changed->old_records + 12U) == 1U
        && memcmp(changed->old_records + 24U, evidence->base_digest, 32U) == 0);
    if (fixture->scope_probe == 1) {
        CHECK(ctos_ssh_native_client_callback_ancestor_proofs_check(fixture->scope_token, environment) == 0);
        check_receiver_scope(fixture, environment);
        fixture->scope_candidate_checks++;
    }
    CHECK(fixture->candidate_body == NULL && fixture->changed_records == NULL);
    fixture->candidate_body = malloc(candidate->replacement_body_length);
    fixture->changed_records = malloc(changed->old_records_length);
    CHECK(fixture->candidate_body != NULL && fixture->changed_records != NULL);
    memcpy(fixture->candidate_body, candidate->replacement_body, candidate->replacement_body_length);
    memcpy(fixture->changed_records, changed->old_records, changed->old_records_length);
    fixture->candidate_body_length = candidate->replacement_body_length;
    fixture->changed_records_length = changed->old_records_length;
    memcpy(fixture->candidate_base_digest, evidence->base_digest, 32U);
    memcpy(fixture->candidate_replacement_digest, evidence->replacement_digest, 32U);
    fixture->last_candidate_classification = evidence->classification;
    strcpy(fixture->last_candidate_key_type, evidence->key_type);
    strcpy(fixture->last_candidate_fingerprint, evidence->fingerprint);
    fixture->candidate_calls++;
    fixture->changed_candidate_calls++;
    fixture->candidate_event_order = ++fixture->callback_event_order;
    return 0;
}

static int changed_host_review(void *context, void *environment,
    const struct ctos_ssh_host_evidence *evidence) {
    struct callback_fixture *fixture = context;
    CHECK(fixture != NULL && fixture->environment == environment && evidence != NULL);
    CHECK(fixture->changed_candidate_calls == 1 && fixture->changed_review_calls == 0
        && fixture->kex_complete_calls == 0 && fixture->selected_calls == 0);
    CHECK(evidence->classification == CTOS_SSH_CALLBACK_HOST_CHANGED
        && evidence->role == fixture->expected_role
        && strcmp(evidence->fingerprint, fixture->last_candidate_fingerprint) == 0
        && memcmp(evidence->base_digest, fixture->candidate_base_digest, 32U) == 0
        && memcmp(evidence->replacement_digest, fixture->candidate_replacement_digest, 32U) == 0);
    if (fixture->scope_probe == 1) {
        CHECK(ctos_ssh_native_client_callback_ancestor_proofs_check(fixture->scope_token, environment) == 0);
        check_receiver_scope(fixture, environment);
        fixture->scope_review_checks++;
    }
    fixture->changed_review_calls++;
    fixture->host_evidence_event_order = ++fixture->callback_event_order;
    if (fixture->cancel_changed_review)
        CHECK(ctos_ssh_callback_cancel(fixture->selected_callback) == 0);
    if (fixture->invalidate_changed_view) fixture->selected_view->valid = 0;
    if (fixture->expire_changed_review)
        wait_until_deadline_attempts(fixture->selected_deadline, TEST_EXPIRY_WAIT_ATTEMPTS);
    return fixture->deny_changed_review ? EACCES : 0;
}

static int provisional_new_candidate(void *context, void *environment,
    const struct ctos_ssh_known_hosts_candidate *candidate) {
    struct callback_fixture *fixture = context;
    CHECK(known_hosts_candidate(context, environment, candidate) == 0);
    fixture->provisional_candidate_calls++;
    if (fixture->close_provisional_candidate) fixture->scope_close_parent(fixture->scope_close_parent_context);
    return fixture->fail_provisional_candidate ? EACCES : 0;
}

static int provisional_new_review(void *context, void *environment,
    const struct ctos_ssh_host_evidence *evidence) {
    struct callback_fixture *fixture = context;
    CHECK(fixture != NULL && fixture->environment == environment && evidence != NULL
        && fixture->provisional_candidate_calls == 1 && fixture->provisional_review_calls == 0
        && evidence->classification == CTOS_SSH_CALLBACK_HOST_NEW
        && evidence->role == fixture->last_candidate_role && evidence->hop == fixture->last_candidate_hop
        && strcmp(evidence->fingerprint, fixture->last_candidate_fingerprint) == 0
        && memcmp(evidence->base_digest, fixture->candidate_base_digest, 32U) == 0
        && memcmp(evidence->replacement_digest, fixture->candidate_replacement_digest, 32U) == 0);
    CHECK(ctos_ssh_native_client_callback_ancestor_proofs_check(fixture->scope_token, environment) == 0);
    check_receiver_scope(fixture, environment);
    fixture->provisional_review_calls++;
    if (fixture->close_provisional_review) fixture->scope_close_parent(fixture->scope_close_parent_context);
    if (fixture->cancel_provisional_review)
        CHECK(ctos_ssh_callback_cancel(fixture->selected_callback) == 0);
    return fixture->deny_provisional_review ? EACCES : 0;
}

static int provisional_new_kex(void *context, void *environment, uint64_t cycle,
    const unsigned char session_digest[32]) {
    struct callback_fixture *fixture = context;
    CHECK(fixture != NULL && fixture->environment == environment && cycle != 0
        && session_digest != NULL && fixture->provisional_review_calls == 1
        && fixture->provisional_kex_calls == 0 && fixture->kex_complete_calls == 0);
    CHECK(ctos_ssh_native_client_callback_ancestor_proofs_check(fixture->scope_token, environment) == 0);
    check_receiver_scope(fixture, environment);
    fixture->provisional_kex_calls++;
    if (fixture->close_provisional_kex) fixture->scope_close_parent(fixture->scope_close_parent_context);
    if (fixture->cancel_provisional_kex)
        CHECK(ctos_ssh_callback_cancel(fixture->selected_callback) == 0);
    return 0;
}

static int kex_complete(void *context, void *environment, uint64_t cycle,
    const unsigned char session_digest[32]) {
    struct callback_fixture *fixture = context;
    unsigned char combined = 0;

    CHECK(fixture != NULL && fixture->environment == environment);
    CHECK(cycle != 0 && session_digest != NULL);
    if (fixture->scope_probe == 1) {
        CHECK(ctos_ssh_native_client_callback_ancestor_proofs_check(fixture->scope_token, environment) == 0);
        check_receiver_scope(fixture, environment);
        fixture->scope_kex_checks++;
    }
    if (fixture->changed_candidate_calls != 0) {
        CHECK(fixture->changed_candidate_calls == 1 && fixture->changed_review_calls == 1
            && fixture->selected_calls == 0 && fixture->candidate_event_order == 1U
            && fixture->host_evidence_event_order == 2U);
        fixture->changed_commit_attempts++;
        if (fixture->fail_changed_commit) return EIO;
        fixture->changed_commit_calls++;
    } else if (fixture->last_host_classification == CTOS_SSH_CALLBACK_HOST_NEW) {
        CHECK(fixture->host_evidence_calls == 1);
        CHECK(fixture->candidate_calls == 1);
        CHECK(fixture->candidate_event_order == 1U);
        CHECK(fixture->host_evidence_event_order == 2U);
    } else {
        CHECK(fixture->host_evidence_calls == 1);
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
    if (fixture->scope_probe == 2) {
        CHECK(ctos_ssh_native_client_callback_ancestor_proofs_check(fixture->scope_token, environment) != 0
            && errno == EACCES);
        CHECK(ctos_ssh_native_client_callback_ancestor_proofs_check_receiver(
            fixture->scope_token, environment, fixture, validate_receiver) != 0 && errno == EACCES);
        fixture->scope_selected_checks++;
    }
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
    CHECK(fixture->kex_complete_calls == 1U
        && (fixture->host_evidence_calls == 1U || fixture->changed_commit_calls == 1U));
    CHECK(output != NULL && output != fixture->caller_output && length != NULL);
    if (fixture->scope_probe == 1) {
        CHECK(ctos_ssh_native_client_callback_ancestor_proofs_check(fixture->scope_token, environment) == 0);
        check_receiver_scope(fixture, environment);
        fixture->scope_selected_checks++;
    }
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
    if (fixture->scope_probe == 1 && fixture->selected_action == 3) {
        CHECK(ctos_ssh_native_client_callback_ancestor_proofs_check(fixture->scope_token, environment) != 0);
        CHECK(ctos_ssh_native_client_callback_ancestor_proofs_check_receiver(
            fixture->scope_token, environment, fixture, validate_receiver) != 0);
        fixture->scope_after_invalidation++;
    }
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
        .validate_receiver = validate_receiver,
        .host_evidence = host_evidence,
        .known_hosts_candidate = known_hosts_candidate,
        .known_hosts_candidate_abort = known_hosts_candidate_abort,
        .kex_complete = kex_complete,
        .credential = credential,
        .credential_bytes = credential_bytes,
        .credential_bytes_selected = fixture->omit_selected_target ? NULL : credential_bytes_selected,
        .changed_host_candidate = fixture->enable_changed ? changed_host_candidate : NULL,
        .changed_host_review = fixture->enable_changed ? changed_host_review : NULL,
        .provisional_new_candidate = fixture->enable_provisional ? provisional_new_candidate : NULL,
        .provisional_new_review = fixture->enable_provisional ? provisional_new_review : NULL,
        .provisional_new_kex = fixture->enable_provisional ? provisional_new_kex : NULL,
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
    int managed_child;
    int managed_child_fd;
    size_t step_budget;
    struct ssh *server;
    struct ctos_ssh_view *view;
    struct ctos_ssh_view *fresh_host_view;
    struct ctos_ssh_view_borrow *view_borrow;
    struct callback_fixture callback;
    struct sshkey *private_key;
    struct sshkey *public_key;
    struct sshkey *host_key;
    struct sshkey *certificate_authority;
    const unsigned char *known_hosts_override;
    size_t known_hosts_override_length;
    const unsigned char *other_hosts_override;
    size_t other_hosts_override_length;
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
    int force_server_cipher;
    struct wire selection_wire;
    unsigned char selection_receipt[32];
    int password_expected;
    int keyboard_continuation;
    int automatic_selection;
    unsigned int automatic_methods;
    unsigned int password_requests;
    int discovery_expected, discovery_reply, discovery_partial, discovery_trailing;
    unsigned int discovery_requests;
    const char *discovery_methods;
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
    int keyboard_expected;
    unsigned int keyboard_requests, keyboard_responses, keyboard_prompt_count;
    struct ctos_ssh_slice keyboard_answers[CTOS_SSH_NATIVE_CLIENT_KBI_PROMPTS];
    int shell_expected, shell_stage, shell_reject, shell_early_output_stage, shell_early_close_stage, shell_no_pty;
    int jump_expected, jump_preconfirm_data, jump_bridge_enabled, jump_bridge_fd;
    int server_alive_expected, server_alive_reply;
    unsigned int server_alive_requests;
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
    if (fixture->force_server_cipher != 0) {
        /* ssh_init reads only CTOS, then populates both directions. */
        params.proposal[PROPOSAL_ENC_ALGS_CTOS] = fixture->force_server_cipher == 1
            ? "aes128-ctr" : fixture->force_server_cipher == 3
                ? "chacha20-poly1305@openssh.com" : "aes128-gcm@openssh.com";
        params.proposal[PROPOSAL_MAC_ALGS_CTOS] = "hmac-sha2-256";
    }
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

static int (*original_server_sign)(struct ssh *, struct sshkey *, struct sshkey *,
    u_char **, size_t *, const u_char *, size_t, const char *);
static int sign_with_invalid_signature(struct ssh *ssh, struct sshkey *private_key,
    struct sshkey *public_key, u_char **signature, size_t *length,
    const u_char *data, size_t data_length, const char *algorithm) {
    int result = original_server_sign(ssh, private_key, public_key, signature,
        length, data, data_length, algorithm);
    if (result == 0 && signature != NULL && *signature != NULL && length != NULL && *length > 0)
        (*signature)[*length - 1U] ^= 1U;
    return result;
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
    fields[0] = (struct ctos_ssh_slice){(const unsigned char *)"user", 4U};
    fields[1] = fields[2] = fields[3] = fields[4] = (struct ctos_ssh_slice){NULL, 0};
    make_selection_wire(&changed, snapshot, 3U, fields);
    changed.bytes[5] = 2;
    reject_selection_open(fixture, config, changed.bytes, changed.length, EINVAL);
    for (size_t index = 1; index < 5; index++) {
        fields[index] = (struct ctos_ssh_slice){(const unsigned char *)"x", 1U};
        make_selection_wire(&changed, snapshot, 3U, fields);
        reject_selection_open(fixture, config, changed.bytes, changed.length, EINVAL);
        fields[index] = (struct ctos_ssh_slice){NULL, 0};
    }
}

static void finish_fixture_setup_selected_mode(struct fixture *fixture,
    unsigned int lifetime_ms, const struct sshkey *known_hosts_key,
    const char *known_hosts_prefix, int empty_known_hosts,
    int fail_descriptor_duplication, unsigned int selection_kind, int admission_matrix,
    enum ctos_ssh_native_host_key_mode host_key_mode) {
    struct ctos_ssh_role_request request;
    struct ctos_ssh_native_client_config config;
    unsigned char known_hosts_body[16384] = {0};
    size_t known_hosts_length;

    if (fixture_existing_role != 0) {
        struct ctos_ssh_role_snapshot existing;
        CHECK(ctos_ssh_role_bootstrap_snapshot(fixture_existing_role, &existing) == 0);
        fixture->deadline = existing.deadline;
    } else fixture->deadline = deadline_after(lifetime_ms);
    CHECK(fixture->host_key != NULL);
    CHECK(expected_fingerprint(fixture->host_key, fixture->fingerprint) == 0);
    known_hosts_length = 0;
    if (fixture->known_hosts_override != NULL) {
        CHECK(fixture->known_hosts_override_length <= sizeof(known_hosts_body));
        known_hosts_length = fixture->known_hosts_override_length;
        memcpy(known_hosts_body, fixture->known_hosts_override, known_hosts_length);
    } else if (!empty_known_hosts) {
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
    fixture->view = fixture->other_hosts_override != NULL
        ? load_two_host_views(known_hosts_body, known_hosts_length,
            fixture->other_hosts_override, fixture->other_hosts_override_length, fixture->deadline)
        : load_view(known_hosts_body, known_hosts_length, fixture->deadline);
    CHECK(sshkey_to_blob(fixture->host_key, &fixture->public_blob,
        &fixture->public_blob_length) == 0);
    CHECK(ctos_ssh_view_borrow_owned_view(fixture->view,
        &fixture->view_borrow) == 0);
    request = role_request(fixture->deadline);
    if (fixture_jump_role) request.program = CTOS_SSH_ROLE_JUMP;
    request.chain_identity = fixture_chain_identity;
    if (fixture_existing_role != 0) fixture->role = fixture_existing_role;
    else CHECK(ctos_ssh_role_bootstrap_open(&request, &fixture->role) == 0);
    fixture->callback_token = open_callback(&fixture->callback, fixture->role,
        fixture->deadline);
    fixture->callback.expected_role = fixture->role;
    struct ctos_ssh_role_snapshot actual_role;
    CHECK(ctos_ssh_role_bootstrap_snapshot(fixture->role, &actual_role) == 0);
    fixture->callback.expected_hop = actual_role.hop;
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
        .step_budget = fixture_step_budget,
        .server_host_public_key_blob = fixture->public_blob,
        .server_host_public_key_length = fixture->public_blob_length,
        .expected_host_fingerprint = fixture->fingerprint,
        .managed_view_borrow = fixture->view_borrow,
        .known_hosts_path = "ssh/known_hosts",
        .host = "example.invalid",
        .candidate_id = "native-candidate"
    };
    if (fixture_fresh_host_mode != 0) {
        unsigned char fresh_body[4096];
        size_t fresh_length = 0;
        CHECK(fixture_jump_parent != NULL && fixture_parent_context != NULL);
        if (fixture_fresh_host_mode == 1)
            fresh_length = format_host_key(fixture->host_key, fresh_body, sizeof(fresh_body));
        else if (fixture_fresh_host_mode == 3)
            fresh_length = format_host_key(fixture_parent_context->host_key, fresh_body, sizeof(fresh_body));
        fixture->fresh_host_view = load_view(fresh_body, fresh_length, fixture->deadline);
        /* Real trust commits advance the epoch and ciphertext digest without
         * necessarily changing the vault generation. */
        struct ctos_ssh_view *fresh = fixture->fresh_host_view;
        size_t binding_offset = (size_t)(fresh->binding.data - fresh->mapping);
        CHECK(fresh->epoch == 11U && fresh->generation == 91U && binding_offset < fresh->length);
        fresh->mapping[39] = 12U;
        fresh->mapping[binding_offset] ^= 0x40U;
        fresh->valid = 0; fresh->private_count = 0; fresh->public_bytes = 0;
        memset(fresh->files, 0, sizeof(fresh->files));
        CHECK(ctos_ssh_view_validate(fresh) == 0 && fresh->epoch == 12U);
        config.fresh_hostfile_view = fresh;
        memset(fresh_body, 0, sizeof(fresh_body));
    }
    fixture->step_budget = fixture_step_budget;
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
        } else if (selection_kind == 4U) {
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
        if (fixture->keyboard_continuation) {
            fixture->selection_wire.bytes[5] = 3U;
            wire_u32(&fixture->selection_wire, 3U);
        }
        if (fixture->automatic_selection) {
            CHECK(!fixture->keyboard_continuation);
            fixture->selection_wire.bytes[5] = 4U;
            wire_u32(&fixture->selection_wire, fixture->automatic_methods);
        }
        if (fixture->password_expected || fixture->private_expected) {
            fixture->password_username = (const unsigned char *)" 用户😀 ";
            fixture->password_username_length = sizeof(" 用户😀 ") - 1U;
        }
        CHECK(ssh_digest_memory(SSH_DIGEST_SHA256, fixture->selection_wire.bytes,
            fixture->selection_wire.length, fixture->selection_receipt, 32U) == 0);
        config.authentication_selection_wire = fixture->selection_wire.bytes;
        config.authentication_selection_wire_length = fixture->selection_wire.length;
        if (admission_matrix) exercise_selection_admission(fixture, &config, &snapshot);
        if (fixture->keyboard_continuation) {
            for (unsigned int bad = 0; bad < 5U; bad++) {
                struct wire invalid = fixture->selection_wire;
                if (bad == 0U) invalid.bytes[invalid.length - 1U] = 4U;
                if (bad == 1U) invalid.bytes[87] = 3U;
                if (bad == 2U) invalid.bytes[5] = 2U;
                if (bad == 3U) invalid.length--;
                if (bad == 4U) wire_u32(&invalid, 0U);
                reject_selection_open(fixture, &config, invalid.bytes, invalid.length, EINVAL);
            }
        }
        if (fixture->automatic_selection) {
            for (unsigned int bad = 0; bad < (selection_kind == 3U ? 5U : 4U); bad++) {
                struct wire invalid = fixture->selection_wire;
                if (bad == 0U) invalid.bytes[invalid.length - 1U] = 8U;
                if (bad == 1U) invalid.bytes[87] = 1U;
                if (bad == 2U) invalid.length--;
                if (bad == 3U) wire_u32(&invalid, 0U);
                if (bad == 4U) invalid.bytes[invalid.length - 1U] = 1U;
                reject_selection_open(fixture, &config, invalid.bytes, invalid.length, EINVAL);
            }
        }
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
    if (fixture_check_borrow_transaction) {
        int competing_owner = 0;
        struct ctos_ssh_view_borrow_claim *competing = NULL;
        struct ctos_ssh_native_client *denied = NULL;
        struct ctos_ssh_native_jump_relay *denied_relay = NULL;
        struct ctos_ssh_native_client_config bad = config;
        if (fixture_jump_parent == NULL) {
            char invalid_path[514];
            memset(invalid_path, 'x', sizeof(invalid_path));
            invalid_path[sizeof(invalid_path) - 1U] = '\0';
            uint64_t original_deadline = fixture->view->deadline;
            fixture->view->deadline = 1;
            bad.socket_fd = -1;
            errno = 0;
            CHECK(ctos_ssh_native_client_open(&bad, &denied) != 0 && errno == EINVAL && denied == NULL);
            bad = config; bad.role = 0;
            errno = 0;
            CHECK(ctos_ssh_native_client_open(&bad, &denied) != 0 && errno == EINVAL && denied == NULL);
            bad = config; bad.known_hosts_path = invalid_path;
            CHECK(ctos_ssh_native_client_open(&bad, &denied) != 0 && errno == ETIMEDOUT && denied == NULL);
            fixture->view->valid = 0;
            CHECK(ctos_ssh_native_client_open(&bad, &denied) != 0 && errno == EACCES && denied == NULL);
            fixture->view->valid = 1;
            fixture->view->deadline = original_deadline;
            struct ctos_ssh_slice original_uuid = fixture->view->uuid;
            fixture->view->uuid.length = 0;
            CHECK(ctos_ssh_native_client_open(&bad, &denied) != 0 && errno == EACCES && denied == NULL);
            fixture->view->uuid = original_uuid;
            bad = config;
        }
        CHECK(ctos_ssh_view_borrow_claim(fixture->view_borrow, &competing_owner, &competing) == 0);
        if (fixture_jump_parent != NULL) {
            bad.socket_fd = -1;
            CHECK(ctos_ssh_native_client_open_jump_child(fixture_jump_parent, &bad,
                &denied, &denied_relay) != 0 && errno == EBUSY && denied == NULL && denied_relay == NULL);
        } else CHECK(ctos_ssh_native_client_open(&bad, &denied) != 0 && errno == EBUSY && denied == NULL);
        CHECK(ctos_ssh_view_borrow_live(fixture->view_borrow) == 0);
        CHECK(ctos_ssh_view_borrow_release(fixture->view_borrow) != 0 && errno == EBUSY);
        ctos_ssh_view_borrow_claim_abort(competing);
        competing = NULL;
        bad.callback = UINT64_MAX;
        if (fixture_jump_parent != NULL) {
            CHECK(ctos_ssh_native_client_open_jump_child(fixture_jump_parent, &bad,
                &denied, &denied_relay) != 0 && errno == ENOENT && denied == NULL && denied_relay == NULL);
        } else CHECK(ctos_ssh_native_client_open(&bad, &denied) != 0 && errno == ENOENT && denied == NULL);
        CHECK(ctos_ssh_view_borrow_claim(fixture->view_borrow, &competing_owner, &competing) == 0);
        ctos_ssh_view_borrow_claim_abort(competing);
        CHECK(fcntl(fixture->pair[0], F_GETFD) >= 0 && fixture->callback.release_calls == 0);
    }
    if (fixture_jump_parent != NULL) {
        unsigned char was_open[512] = {0};
        int new_fds[3] = {-1, -1, -1};
        size_t new_count = 0;
        CHECK(ctos_ssh_native_client_open(&config, &fixture->client) != 0
            && errno == EACCES && fixture->client == NULL);
        config.socket_fd = -1;
        fixture->managed_child = 1;
        fixture_child_relay = NULL;
        struct ctos_ssh_native_client_config wrong = config;
        struct ctos_ssh_native_client *wrong_child = NULL;
        struct ctos_ssh_native_jump_relay *wrong_relay = NULL;
        wrong.role ^= 1U;
        CHECK(ctos_ssh_native_client_open_jump_child(fixture_jump_parent, &wrong,
            &wrong_child, &wrong_relay) != 0 && errno == EACCES
            && wrong_child == NULL && wrong_relay == NULL);
        CHECK(fixture_parent_context != NULL);
        wrong = config;
        wrong.managed_view_borrow = fixture_parent_context->view_borrow;
        CHECK(ctos_ssh_native_client_open_jump_child(fixture_jump_parent, &wrong,
            &wrong_child, &wrong_relay) != 0 && errno == EBUSY
            && wrong_child == NULL && wrong_relay == NULL);
        if (fixture_root_context != NULL && fixture_root_context != fixture_parent_context) {
            wrong.managed_view_borrow = fixture_root_context->view_borrow;
            CHECK(ctos_ssh_native_client_open_jump_child(fixture_jump_parent, &wrong,
                &wrong_child, &wrong_relay) != 0 && errno == EBUSY
                && wrong_child == NULL && wrong_relay == NULL);
        }
        wrong = config;
        wrong.callback = fixture_parent_context->callback_token;
        wrong.callback_environment = fixture_parent_context->callback.environment;
        CHECK(ctos_ssh_native_client_open_jump_child(fixture_jump_parent, &wrong,
            &wrong_child, &wrong_relay) != 0 && errno == EACCES
            && wrong_child == NULL && wrong_relay == NULL);
        wrong = config;
        wrong.callback = UINT64_MAX;
        CHECK(ctos_ssh_native_client_open_jump_child(fixture_jump_parent, &wrong,
            &wrong_child, &wrong_relay) != 0 && errno == ENOENT
            && wrong_child == NULL && wrong_relay == NULL);
        if (fixture->fresh_host_view != NULL) {
            struct ctos_ssh_view *fresh = fixture->fresh_host_view;
            uint64_t saved_epoch = fresh->epoch;
            size_t uuid_offset = (size_t)(fresh->uuid.data - fresh->mapping);
            size_t binding_offset = (size_t)(fresh->binding.data - fresh->mapping);
            size_t root_id_offset = (size_t)(fresh->files[0].id.data - fresh->mapping);
            CHECK(uuid_offset < fresh->length && binding_offset < fresh->length
                && root_id_offset < fresh->length);
            wrong = config;
            fresh->epoch = fixture->view->epoch;
            CHECK(ctos_ssh_native_client_open_jump_child(fixture_jump_parent, &wrong,
                &wrong_child, &wrong_relay) != 0 && errno == EACCES
                && wrong_child == NULL && wrong_relay == NULL);
            fresh->epoch = saved_epoch;
            fresh->mapping[binding_offset] ^= 0x40U;
            CHECK(ctos_ssh_native_client_open_jump_child(fixture_jump_parent, &wrong,
                &wrong_child, &wrong_relay) != 0 && errno == EACCES
                && wrong_child == NULL && wrong_relay == NULL);
            fresh->mapping[binding_offset] ^= 0x40U;
            fresh->mapping[uuid_offset] ^= 1U;
            CHECK(ctos_ssh_native_client_open_jump_child(fixture_jump_parent, &wrong,
                &wrong_child, &wrong_relay) != 0 && errno == EACCES
                && wrong_child == NULL && wrong_relay == NULL);
            fresh->mapping[uuid_offset] ^= 1U;
            fresh->mapping[root_id_offset] ^= 1U;
            CHECK(ctos_ssh_native_client_open_jump_child(fixture_jump_parent, &wrong,
                &wrong_child, &wrong_relay) != 0 && errno == EACCES
                && wrong_child == NULL && wrong_relay == NULL);
            fresh->mapping[root_id_offset] ^= 1U;
        }
        for (int fd = 0; fd < (int)sizeof(was_open); fd++)
            was_open[fd] = fcntl(fd, F_GETFD) >= 0;
        if (fixture_expect_child_no_relay) {
            CHECK(ctos_ssh_native_client_open_jump_child(fixture_jump_parent, &config,
                &fixture->client, &fixture_child_relay) != 0 && errno == EALREADY);
            CHECK(fixture->client != NULL && fixture_child_relay == NULL);
            CHECK(!ctos_ssh_native_client_complete(fixture->client));
            CHECK(ctos_ssh_native_client_step(fixture->client,
                &(struct ctos_ssh_native_client_step){0}) != 0 && errno == EALREADY);
            CHECK(ctos_ssh_callback_close(fixture->callback_token,
                fixture->callback.environment) != 0 && errno == EBUSY);
            fixture->callback.release_failures_remaining = 1;
            CHECK(ctos_ssh_native_client_close(fixture->client) != 0 && errno == EIO);
            CHECK(ctos_ssh_callback_close(fixture->callback_token,
                fixture->callback.environment) != 0 && errno == EBUSY);
            return;
        }
        CHECK(ctos_ssh_native_client_open_jump_child(fixture_jump_parent, &config,
            &fixture->client, &fixture_child_relay) == 0);
        CHECK(fixture->client != NULL && fixture_child_relay != NULL);
        unsigned char pre_kex_receipt[32];
        CHECK(ctos_ssh_native_client_authentication_selection_receipt(fixture->client,
            pre_kex_receipt) == 0
            && memcmp(pre_kex_receipt, fixture->selection_receipt, sizeof(pre_kex_receipt)) == 0);
        for (int fd = 0; fd < (int)sizeof(was_open); fd++) {
            if (!was_open[fd] && fcntl(fd, F_GETFD) >= 0) {
                CHECK(new_count < sizeof(new_fds) / sizeof(new_fds[0]));
                new_fds[new_count++] = fd;
            }
        }
        CHECK(new_count == 3);
        fixture->managed_child_fd = new_fds[1];
        CHECK(ctos_ssh_callback_client_claim(fixture->callback_token,
            fixture->callback.environment, fixture->role, fixture) != 0 && errno == EBUSY);
        CHECK(ctos_ssh_callback_close(fixture->callback_token,
            fixture->callback.environment) != 0 && errno == EBUSY);
        struct child_wrong_owner_attempt attempt = {
            .parent = fixture_jump_parent, .child = fixture->client,
            .relay = fixture_child_relay, .config = config
        };
        pthread_t thread;
        CHECK(pthread_create(&thread, NULL, child_wrong_owner, &attempt) == 0);
        CHECK(pthread_join(thread, NULL) == 0);
        CHECK(attempt.open_result != 0 && attempt.open_error == EPERM);
        CHECK(attempt.step_result != 0 && attempt.step_error == EPERM);
        CHECK(attempt.close_result != 0 && attempt.close_error == EPERM);
        CHECK(attempt.relay_result != 0 && attempt.relay_error == EPERM);
    } else CHECK(ctos_ssh_native_client_open(&config, &fixture->client) == 0);
    if (fixture_check_borrow_transaction) {
        int competing_owner = 0;
        struct ctos_ssh_view_borrow_claim *competing = NULL;
        CHECK(ctos_ssh_view_borrow_claim(fixture->view_borrow, &competing_owner, &competing) != 0
            && errno == EBUSY && competing == NULL);
        CHECK(ctos_ssh_view_borrow_release(fixture->view_borrow) != 0 && errno == EBUSY);
        CHECK(ctos_ssh_view_borrow_live(fixture->view_borrow) == 0);
    }
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
    /* The child constructor owns a separate private socketpair; this pair is
     * retained solely to observe callback release ordering in the fixture. */
    if (fixture->managed_child) CHECK(close(fixture->pair[0]) == 0);
    enum ctos_ssh_native_host_key_rejection rejection = CTOS_SSH_HOST_REJECTION_CHANGED;
    CHECK(ctos_ssh_native_client_host_key_rejection(fixture->client, &rejection) != 0
        && errno == ENOENT && rejection == CTOS_SSH_HOST_REJECTION_NONE);
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
    CHECK(fixture->callback.credential_calls == fixture->callback.expected_credential_calls
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
    if (fixture->fresh_host_view != NULL) destroy_view(fixture->fresh_host_view);
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
    if (fixture->callback.changed_records != NULL) {
        memset(fixture->callback.changed_records, 0, fixture->callback.changed_records_length);
        free(fixture->callback.changed_records);
        fixture->callback.changed_records = NULL;
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
    CHECK(fixture->callback.selected_calls == (fixture->automatic_selection ? fixture->private_requests : 1U));
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

static void accept_discovery_request(struct fixture *fixture) {
    const unsigned char *text;
    size_t length;
    CHECK(sshpkt_get_string_direct(fixture->server, &text, &length) == 0);
    CHECK(length == sizeof(" 用户😀 ") - 1U && memcmp(text, " 用户😀 ", length) == 0);
    CHECK(sshpkt_get_string_direct(fixture->server, &text, &length) == 0);
    CHECK(length == 14U && memcmp(text, "ssh-connection", 14U) == 0);
    CHECK(sshpkt_get_string_direct(fixture->server, &text, &length) == 0);
    CHECK(length == 4U && memcmp(text, "none", 4U) == 0);
    CHECK(sshpkt_get_end(fixture->server) == 0 && ++fixture->discovery_requests == 1U);
    CHECK(sshpkt_start(fixture->server, fixture->discovery_reply == 1
        ? SSH2_MSG_USERAUTH_SUCCESS : SSH2_MSG_USERAUTH_FAILURE) == 0);
    if (fixture->discovery_reply != 1) {
        CHECK(fixture->discovery_methods != NULL);
        CHECK(sshpkt_put_cstring(fixture->server, fixture->discovery_methods) == 0);
        CHECK(sshpkt_put_u8(fixture->server, (u_char)fixture->discovery_partial) == 0);
    }
    if (fixture->discovery_trailing) CHECK(sshpkt_put_u8(fixture->server, 9) == 0);
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
        if (fixture->jump_expected)
            CHECK(length == 12U && memcmp(text, "direct-tcpip", 12U) == 0);
        else CHECK(length == 7U && memcmp(text, "session", 7U) == 0);
        CHECK(sshpkt_get_u32(ssh, &value) == 0 && value == 0);
        CHECK(sshpkt_get_u32(ssh, &value) == 0 && value == 65536U);
        CHECK(sshpkt_get_u32(ssh, &value) == 0 && value == 32768U);
        if (fixture->jump_expected) {
            CHECK(sshpkt_get_string_direct(ssh, &text, &length) == 0);
            const char *host = fixture->jump_bridge_enabled ? "example.invalid" : "nexthop.example";
            CHECK(length == strlen(host) && memcmp(text, host, length) == 0);
            CHECK(sshpkt_get_u32(ssh, &value) == 0 && value == (fixture->jump_bridge_enabled ? 22U : 2222U));
            CHECK(sshpkt_get_string_direct(ssh, &text, &length) == 0);
            CHECK(length == 9U && memcmp(text, "127.0.0.1", length) == 0);
            CHECK(sshpkt_get_u32(ssh, &value) == 0 && value == 65535U);
        }
        CHECK(sshpkt_get_end(ssh) == 0);
        fixture->shell_stage = 1;
        if (fixture->jump_preconfirm_data)
            peer_channel_data(fixture, 0, (const unsigned char *)"early", 5U);
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
        if (fixture->jump_expected && fixture->shell_reject == 0) fixture->shell_stage = 3;
        return;
    }
    CHECK(sshpkt_get_u32(ssh, &value) == 0 && value == 77);
    if (type == SSH2_MSG_CHANNEL_REQUEST) {
        CHECK(sshpkt_get_string_direct(ssh, &text, &length) == 0);
        CHECK(sshpkt_get_u8(ssh, &flag) == 0);
        if (length == 7U && memcmp(text, "pty-req", 7U) == 0) {
            CHECK(!fixture->shell_no_pty);
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
            CHECK(fixture->shell_stage == (fixture->shell_no_pty ? 1 : 2) && flag == 1);
            fixture->shell_stage = 3;
        } else {
            CHECK(length == 13U && memcmp(text, "window-change", 13U) == 0 && flag == 0);
            CHECK(fixture->shell_stage == 3 && !fixture->shell_no_pty);
            CHECK(sshpkt_get_u32(ssh, &value) == 0 && value == 132);
            CHECK(sshpkt_get_u32(ssh, &value) == 0 && value == 43);
            CHECK(sshpkt_get_u32(ssh, &value) == 0 && value == 0);
            CHECK(sshpkt_get_u32(ssh, &value) == 0 && value == 0);
            fixture->shell_resize_count++;
        }
        CHECK(sshpkt_get_end(ssh) == 0);
        if (flag) {
            if (fixture->shell_early_output_stage == fixture->shell_stage) {
                peer_channel_data(fixture, 0, (const unsigned char *)"early", 5U);
                peer_channel_data(fixture, 1, (const unsigned char *)"stderr", 6U);
            }
            peer_channel_packet(fixture, fixture->shell_early_close_stage == fixture->shell_stage
                ? SSH2_MSG_CHANNEL_CLOSE : fixture->shell_reject == fixture->shell_stage
                    ? SSH2_MSG_CHANNEL_FAILURE : SSH2_MSG_CHANNEL_SUCCESS);
            CHECK(sshpkt_send(ssh) == 0);
        }
    } else if (type == SSH2_MSG_CHANNEL_DATA) {
        CHECK(fixture->shell_stage == 3);
        CHECK(sshpkt_get_string_direct(ssh, &text, &length) == 0);
        if (fixture->jump_bridge_enabled) {
            CHECK(send(fixture->jump_bridge_fd, text, length, MSG_NOSIGNAL | MSG_DONTWAIT) == (ssize_t)length);
            if (length != 0) {
                peer_channel_packet(fixture, SSH2_MSG_CHANNEL_WINDOW_ADJUST);
                CHECK(sshpkt_put_u32(ssh, (uint32_t)length) == 0 && sshpkt_send(ssh) == 0);
            }
        } else {
            CHECK(length <= sizeof(fixture->shell_input) - fixture->shell_input_length);
            memcpy(fixture->shell_input + fixture->shell_input_length, text, length);
            fixture->shell_input_length += length;
        }
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

static void accept_keyboard_packet(struct fixture *fixture, int type) {
    const unsigned char *bytes;
    size_t length;
    uint32_t count;
    if (type == SSH2_MSG_USERAUTH_REQUEST) {
        CHECK(sshpkt_get_string_direct(fixture->server, &bytes, &length) == 0);
        CHECK(length == sizeof(" 用户😀 ") - 1U && memcmp(bytes, " 用户😀 ", length) == 0);
        CHECK(sshpkt_get_string_direct(fixture->server, &bytes, &length) == 0);
        CHECK(length == 14U && memcmp(bytes, "ssh-connection", length) == 0);
        CHECK(sshpkt_get_string_direct(fixture->server, &bytes, &length) == 0);
        CHECK(length == 20U && memcmp(bytes, "keyboard-interactive", length) == 0);
        CHECK(sshpkt_get_string_direct(fixture->server, &bytes, &length) == 0 && length == 0);
        CHECK(sshpkt_get_string_direct(fixture->server, &bytes, &length) == 0 && length == 0);
        CHECK(++fixture->keyboard_requests == 1U);
    } else {
        CHECK(type == SSH2_MSG_USERAUTH_INFO_RESPONSE);
        CHECK(sshpkt_get_u32(fixture->server, &count) == 0 && count == fixture->keyboard_prompt_count);
        for (uint32_t index = 0; index < count; index++) {
            CHECK(sshpkt_get_string_direct(fixture->server, &bytes, &length) == 0);
            CHECK(length == fixture->keyboard_answers[index].length);
            CHECK(length == 0 || memcmp(bytes, fixture->keyboard_answers[index].data, length) == 0);
        }
        fixture->keyboard_responses++;
    }
    CHECK(sshpkt_get_end(fixture->server) == 0);
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
    } else if (packet_type == SSH2_MSG_USERAUTH_REQUEST && fixture->discovery_expected
            && fixture->discovery_requests == 0) {
        accept_discovery_request(fixture);
    } else if (packet_type == SSH2_MSG_USERAUTH_REQUEST && fixture->password_expected) {
        accept_password_request(fixture);
    } else if (packet_type == SSH2_MSG_USERAUTH_REQUEST && fixture->private_expected) {
        accept_private_request(fixture);
    } else if (fixture->keyboard_expected && (packet_type == SSH2_MSG_USERAUTH_REQUEST
        || packet_type == SSH2_MSG_USERAUTH_INFO_RESPONSE)) {
        accept_keyboard_packet(fixture, packet_type);
    } else if (packet_type == SSH2_MSG_GLOBAL_REQUEST && fixture->server_alive_expected) {
        const unsigned char *request;
        size_t length;
        u_char want_reply;
        CHECK(sshpkt_get_string_direct(fixture->server, &request, &length) == 0);
        CHECK(length == sizeof("keepalive@openssh.com") - 1U
            && memcmp(request, "keepalive@openssh.com", length) == 0);
        CHECK(sshpkt_get_u8(fixture->server, &want_reply) == 0 && want_reply == 1);
        CHECK(sshpkt_get_end(fixture->server) == 0);
        fixture->server_alive_requests++;
        if (fixture->server_alive_reply != 0) {
            CHECK(sshpkt_start(fixture->server, fixture->server_alive_reply != 2
                ? SSH2_MSG_REQUEST_SUCCESS : SSH2_MSG_REQUEST_FAILURE) == 0);
            if (fixture->server_alive_reply == 3) CHECK(sshpkt_put_u32(fixture->server, 123U) == 0);
            CHECK(sshpkt_send(fixture->server) == 0);
        }
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
    if (fixture->callback.enable_changed && fixture->callback.changed_candidate_calls != 0U) {
        CHECK(fixture->callback.changed_candidate_calls == 1U
            && fixture->callback.changed_review_calls == 1U
            && fixture->callback.changed_commit_calls == 1U
            && fixture->callback.host_evidence_calls == 0U
            && fixture->callback.candidate_calls == 1U);
    } else {
        CHECK(fixture->callback.host_evidence_calls == 1U);
        CHECK(fixture->callback.last_host_classification == (new_host
            ? CTOS_SSH_CALLBACK_HOST_NEW : CTOS_SSH_CALLBACK_HOST_KNOWN));
        CHECK(fixture->callback.candidate_calls == (new_host ? 1U : 0U));
    }
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
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
    CHECK(ctos_ssh_native_client_test_fail_next_view_release(fixture.client) == 0);
    CHECK(ctos_ssh_native_client_close(fixture.client) != 0 && errno == EIO);
    CHECK(fixture.callback.release_calls == 2);
    CHECK(ctos_ssh_view_borrow_release(fixture.view_borrow) != 0 && errno == EBUSY);
    int competing_owner = 0;
    struct ctos_ssh_view_borrow_claim *competing = NULL;
    CHECK(ctos_ssh_view_borrow_claim(fixture.view_borrow, &competing_owner, &competing) != 0
        && errno == EBUSY && competing == NULL);
    struct child_retry_owner_attempt retry = {.child = fixture.client};
    pthread_t thread;
    CHECK(pthread_create(&thread, NULL, child_retry_wrong_owner, &retry) == 0);
    CHECK(pthread_join(thread, NULL) == 0);
    CHECK(retry.close_result != 0 && retry.close_error == EPERM);
    CHECK(retry.destroy_result != 0 && retry.destroy_error == EPERM);
    CHECK(ctos_ssh_view_borrow_live(fixture.view_borrow) == 0);
#endif
    cleanup_fixture(&fixture, 2);
}

struct host_rejection_thread {
    struct ctos_ssh_native_client *client;
    enum ctos_ssh_native_host_key_rejection rejection;
    int result;
};

static void *host_rejection_wrong_thread(void *opaque) {
    struct host_rejection_thread *attempt = opaque;
    attempt->result = ctos_ssh_native_client_host_key_rejection(attempt->client, &attempt->rejection);
    return NULL;
}

static void assert_host_rejection(struct ctos_ssh_native_client *client,
    enum ctos_ssh_native_host_key_rejection expected) {
    enum ctos_ssh_native_host_key_rejection rejection = CTOS_SSH_HOST_REJECTION_CERTIFICATE;
    CHECK(ctos_ssh_native_client_host_key_rejection(client, &rejection) == 0 && rejection == expected);
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
    assert_host_rejection(fixture.client, CTOS_SSH_HOST_REJECTION_NONE);
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
    assert_host_rejection(fixture.client, CTOS_SSH_HOST_REJECTION_CHANGED);
    struct host_rejection_thread attempt = {fixture.client, CTOS_SSH_HOST_REJECTION_CHANGED, 0};
    pthread_t thread;
    CHECK(pthread_create(&thread, NULL, host_rejection_wrong_thread, &attempt) == 0);
    CHECK(pthread_join(thread, NULL) == 0);
    CHECK(attempt.result != 0 && attempt.rejection == CTOS_SSH_HOST_REJECTION_NONE);
    assert_host_rejection(fixture.client, CTOS_SSH_HOST_REJECTION_CHANGED);
    enum ctos_ssh_native_host_key_rejection invalid = CTOS_SSH_HOST_REJECTION_CHANGED;
    CHECK(ctos_ssh_native_client_host_key_rejection(NULL, &invalid) != 0
        && invalid == CTOS_SSH_HOST_REJECTION_NONE);
    CHECK(ctos_ssh_native_client_host_key_rejection(fixture.client, NULL) != 0);

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
    assert_host_rejection(fixture.client, CTOS_SSH_HOST_REJECTION_REVOKED);

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
    assert_host_rejection(fixture.client, CTOS_SSH_HOST_REJECTION_CERTIFICATE);

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
    assert_host_rejection(fixture.client, CTOS_SSH_HOST_REJECTION_NONE);

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

static void setup_password_fixture_continuation(struct fixture *fixture, int new_host,
    unsigned int lifetime_ms, enum ctos_ssh_native_host_key_mode mode, int continuation) {
    memset(fixture, 0, sizeof(*fixture));
    fixture->password_expected = 1;
    fixture->keyboard_continuation = continuation;
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
    CHECK(ctos_ssh_native_client_begin_auth_discovery(fixture->client) != 0 && errno == EACCES);
    finish_service_fixture(fixture, new_host, 0);
    errno = 0;
    CHECK(ctos_ssh_native_client_begin_password_auth(fixture->client) != 0 && errno == EACCES);
    CHECK(fixture->callback.selected_calls == 0U);
    CHECK(ctos_ssh_native_client_begin_userauth_service(fixture->client) == 0);
    drive_service(fixture, 0);
    assert_password_result(fixture, CTOS_SSH_PASSWORD_AUTH_NOT_STARTED);
}

static void setup_password_fixture_mode(struct fixture *fixture, int new_host,
    unsigned int lifetime_ms, enum ctos_ssh_native_host_key_mode mode) {
    setup_password_fixture_continuation(fixture, new_host, lifetime_ms, mode, 0);
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
    CHECK(fixture->callback.selected_calls == (fixture->automatic_selection && fixture->private_requests == 0 ? 0U : 1U)
        && fixture->callback.kex_complete_calls == 1U);
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

    fixture_check_borrow_transaction = 1;
    setup_fixture(&fixture, 15000U, NULL);
    fixture_check_borrow_transaction = 0;
    fixture.callback.release_failures_remaining = 1;
    errno = 0;
    CHECK(ctos_ssh_native_client_close(fixture.client) != 0 && errno == EIO);
    CHECK(fixture.callback.release_calls == 1);
    CHECK(fixture.callback.release_saw_fd_open == 1);
    CHECK(fcntl(fixture.pair[0], F_GETFD) >= 0);
    cleanup_fixture(&fixture, 2);
}

static void test_callback_ancestor_root_and_forged_owner(void) {
    struct fixture root;
    setup_fixture(&root, 15000U, NULL);
    root.callback.scope_probe = 3;
    root.callback.scope_token = root.callback_token;
    CHECK(ctos_ssh_native_client_callback_ancestor_proofs_check(root.callback_token,
        root.callback.environment) != 0 && errno == EACCES);
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
    const int claim_modes = 3;
#else
    const int claim_modes = 2;
#endif
    for (int claimed = 0; claimed < claim_modes; claimed++) {
        struct callback_fixture other = {0};
        const void *fake_owner = (const void *)(uintptr_t)1U;
        ctos_ssh_callback_token callback = open_callback(&other, root.role, root.deadline);
        other.scope_probe = 2;
        other.scope_token = callback;
        if (claimed == 1)
            CHECK(ctos_ssh_callback_client_claim(callback, other.environment, root.role, fake_owner) == 0);
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
        if (claimed == 2)
            CHECK(ctos_ssh_native_client_test_claim_callback_scope(callback,
                other.environment, root.role, fake_owner) == 0);
#endif
        CHECK(ctos_ssh_callback_credential(callback, other.environment,
            CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD) != 0 && errno == EACCES);
        CHECK(other.scope_selected_checks == 1);
        if (claimed) CHECK(ctos_ssh_callback_client_close(callback, other.environment, fake_owner) == 0);
        else CHECK(ctos_ssh_callback_close(callback, other.environment) == 0);
        CHECK(ctos_ssh_native_client_callback_ancestor_proofs_check(callback, other.environment) != 0
            && errno == ENOENT);
    }
    finish_service_fixture(&root, 0, 0);
    CHECK(root.callback.scope_host_checks == 1);
    cleanup_fixture(&root, 1);
}

static void setup_private_fixture_version_mode(struct fixture *fixture, int new_host,
    const struct sshkey *identity, const struct sshbuf *file,
    const unsigned char *expected, size_t expected_length,
    const char *advertisement, int no_advertisement, int version,
    enum ctos_ssh_native_host_key_mode mode) {
    memset(fixture, 0, sizeof(*fixture));
    fixture->private_expected = 1;
    fixture->encrypted_identity = version == 2 || version == 4;
    fixture->keyboard_continuation = version == 3 || version == 4;
    fixture->automatic_selection = version == 5;
    fixture->automatic_methods = version == 5 ? 7U : 0;
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
    CHECK(step.bytes_read <= fixture->step_budget && step.bytes_written <= fixture->step_budget);
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

struct discovery_owner_attempt {
    struct ctos_ssh_native_client *client;
    int result, error, read_result, read_error, password_result, password_error;
    enum ctos_ssh_native_client_discovery_result outcome;
    enum ctos_ssh_native_client_password_auth_result password_outcome;
    unsigned int methods;
};

static void *discovery_wrong_owner(void *opaque) {
    struct discovery_owner_attempt *attempt = opaque;
    errno = 0;
    attempt->result = ctos_ssh_native_client_begin_auth_discovery(attempt->client);
    attempt->error = errno;
    attempt->outcome = CTOS_SSH_DISCOVERY_NONE_SUCCESS; attempt->methods = 99U;
    errno = 0;
    attempt->read_result = ctos_ssh_native_client_auth_discovery_result(attempt->client,
        &attempt->outcome, &attempt->methods);
    attempt->read_error = errno;
    attempt->password_outcome = CTOS_SSH_PASSWORD_AUTH_SUCCESS;
    errno = 0;
    attempt->password_result = ctos_ssh_native_client_password_auth_result(attempt->client,
        &attempt->password_outcome);
    attempt->password_error = errno;
    return NULL;
}

static void test_auth_discovery(void) {
    const char *lists[] = {"publickey,password,keyboard-interactive,hostbased", "publickey",
        "", "", "password", "password,,publickey", "password", "password",
        "passwordx,keyboard-interactive-extra", "password"};
    const unsigned int masks[] = {15U, 1U, 0U, 0U, 4U, 0U, 0U, 0U, 8U, 0U};
    for (unsigned int scenario = 0; scenario < 10U; scenario++) {
        struct fixture fixture;
        enum ctos_ssh_native_client_discovery_result result = CTOS_SSH_DISCOVERY_NONE_SUCCESS;
        unsigned int methods = 99U;
        unsigned char bytes[32]; size_t length = 99;
        setup_password_fixture(&fixture, 0, 15000U);
        CHECK(ctos_ssh_native_client_auth_discovery_result(fixture.client, &result, &methods) == 0
            && result == CTOS_SSH_DISCOVERY_NOT_STARTED && methods == 0);
        struct discovery_owner_attempt attempt = {.client = fixture.client}; pthread_t thread;
        CHECK(pthread_create(&thread, NULL, discovery_wrong_owner, &attempt) == 0);
        CHECK(pthread_join(thread, NULL) == 0 && attempt.result != 0 && attempt.error == EPERM);
        CHECK(attempt.read_result != 0 && attempt.read_error == EPERM
            && attempt.outcome == CTOS_SSH_DISCOVERY_NOT_STARTED && attempt.methods == 0);
        CHECK(attempt.password_result != 0 && attempt.password_error == EPERM
            && attempt.password_outcome == CTOS_SSH_PASSWORD_AUTH_NOT_STARTED);
        fixture.discovery_expected = 1;
        fixture.discovery_methods = lists[scenario];
        fixture.discovery_reply = scenario == 3U ? 1 : 0;
        fixture.discovery_partial = scenario == 4U ? 255 : 0;
        fixture.discovery_trailing = scenario == 6U;
        CHECK(ctos_ssh_native_client_begin_auth_discovery(fixture.client) == 0);
        CHECK(ctos_ssh_native_client_begin_auth_discovery(fixture.client) != 0 && errno == EALREADY);
        CHECK(ctos_ssh_native_client_auth_discovery_result(fixture.client, &result, &methods) == 0
            && result == CTOS_SSH_DISCOVERY_PENDING && methods == 0);
        CHECK(pthread_create(&thread, NULL, discovery_wrong_owner, &attempt) == 0);
        CHECK(pthread_join(thread, NULL) == 0 && attempt.result != 0 && attempt.error == EPERM);
        CHECK(attempt.read_result != 0 && attempt.read_error == EPERM
            && attempt.outcome == CTOS_SSH_DISCOVERY_NOT_STARTED && attempt.methods == 0);
        CHECK(attempt.password_result != 0 && attempt.password_error == EPERM
            && attempt.password_outcome == CTOS_SSH_PASSWORD_AUTH_NOT_STARTED);
        CHECK(ctos_ssh_native_client_password_auth_result(fixture.client,
            &(enum ctos_ssh_native_client_password_auth_result){0}) != 0 && errno == EACCES);
        memset(bytes, 0xa5, sizeof(bytes));
        CHECK(ctos_ssh_native_client_selected_credential_bytes(fixture.client, bytes, sizeof(bytes), &length) != 0
            && errno == EACCES && length == 0 && fixture.callback.selected_calls == 0);
        for (size_t i = 0; i < sizeof(bytes); i++) CHECK(bytes[i] == 0);
        if (scenario == 7U) {
            CHECK(ctos_ssh_native_client_step(fixture.client,
                &(struct ctos_ssh_native_client_step){.cancelled = 1}) != 0 && errno == ECANCELED);
            CHECK(fixture.discovery_requests == 0 && fixture.callback.selected_calls == 0);
            cleanup_fixture(&fixture, 1); continue;
        }
        if (scenario == 9U) {
            fixture.view->valid = 0;
            CHECK(ctos_ssh_native_client_step(fixture.client,
                &(struct ctos_ssh_native_client_step){0}) != 0 && errno == EACCES);
            fixture.view->valid = 1;
            CHECK(fixture.discovery_requests == 0 && fixture.callback.selected_calls == 0);
            cleanup_fixture(&fixture, 1); continue;
        }
        int finished = 0;
        for (unsigned int turn = 0; turn < 100000U; turn++) {
            if (shell_turn(&fixture) != 0) {
                CHECK((scenario == 5U || scenario == 6U) && errno == EPROTO);
                finished = 1; break;
            }
            CHECK(ctos_ssh_native_client_auth_discovery_result(fixture.client, &result, &methods) == 0);
            if (result != CTOS_SSH_DISCOVERY_PENDING) { finished = 1; break; }
        }
        CHECK(finished && fixture.discovery_requests == 1 && fixture.callback.selected_calls == 0);
        if (scenario == 5U || scenario == 6U) {
            result = CTOS_SSH_DISCOVERY_NONE_SUCCESS; methods = 99U;
            CHECK(ctos_ssh_native_client_auth_discovery_result(fixture.client, &result, &methods) != 0
                && result == CTOS_SSH_DISCOVERY_NOT_STARTED && methods == 0);
        } else if (scenario == 3U) {
            CHECK(result == CTOS_SSH_DISCOVERY_NONE_SUCCESS && methods == 0);
            CHECK(ctos_ssh_native_client_password_auth_result(fixture.client,
                &(enum ctos_ssh_native_client_password_auth_result){0}) != 0 && errno == EACCES);
            CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) != 0 && errno == EALREADY);
            fixture.shell_expected = 1; fixture.shell_window = 65536U; fixture.shell_maxpacket = 32768U;
            CHECK(ctos_ssh_native_client_begin_shell(fixture.client, 80, 24) == 0);
            drive_shell_phase(&fixture, CTOS_SSH_SHELL_RUNNING);
            CHECK(fixture.shell_stage == 3 && fixture.callback.selected_calls == 0);
        } else {
            CHECK(result == (scenario == 4U ? CTOS_SSH_DISCOVERY_PARTIAL : CTOS_SSH_DISCOVERY_METHODS)
                && methods == masks[scenario]);
            CHECK(ctos_ssh_native_client_begin_auth_discovery(fixture.client) != 0 && errno == EALREADY);
            if (scenario == 0U || scenario == 4U) {
                CHECK(ctos_ssh_native_client_selected_credential_bytes(fixture.client, bytes, sizeof(bytes), &length) != 0
                    && errno == EACCES && length == 0 && fixture.callback.selected_calls == 0);
                CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
                drive_password(&fixture, CTOS_SSH_PASSWORD_AUTH_SUCCESS, 0);
                CHECK(fixture.callback.selected_calls == 1 && fixture.password_requests == 1);
            } else {
                CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) != 0 && errno == EACCES);
                CHECK(ctos_ssh_native_client_selected_credential_bytes(fixture.client, bytes, sizeof(bytes), &length) != 0
                    && errno == EACCES && length == 0 && fixture.callback.selected_calls == 0);
            }
        }
        cleanup_fixture(&fixture, 1);
    }
    for (int automatic = 0; automatic <= 1; automatic++) {
        struct fixture fixture;
        struct sshkey *key = NULL; unsigned char *blob = NULL; size_t blob_length = 0;
        struct sshbuf *file = sshbuf_new();
        CHECK(file != NULL && sshkey_generate(KEY_ED25519, 0, &key) == 0);
        CHECK(sshkey_to_blob(key, &blob, &blob_length) == 0);
        CHECK(sshkey_private_to_fileblob(key, file, "", "discovery", SSHKEY_PRIVATE_OPENSSH, NULL, 0) == 0);
        setup_private_fixture_version_mode(&fixture, 0, key, file, blob, blob_length, NULL, 0, automatic ? 5 : 1,
            CTOS_SSH_HOST_KEY_PINNED);
        if (automatic) CHECK(ctos_ssh_native_client_begin_private_key_auth(fixture.client) != 0 && errno == EACCES);
        fixture.discovery_expected = 1; fixture.discovery_methods = "publickey,password";
        CHECK(ctos_ssh_native_client_begin_auth_discovery(fixture.client) == 0);
        enum ctos_ssh_native_client_discovery_result result = CTOS_SSH_DISCOVERY_PENDING;
        unsigned int methods = 0;
        for (unsigned int turn = 0; turn < 100000U && result == CTOS_SSH_DISCOVERY_PENDING; turn++) {
            CHECK(shell_turn(&fixture) == 0);
            CHECK(ctos_ssh_native_client_auth_discovery_result(fixture.client, &result, &methods) == 0);
        }
        CHECK(result == CTOS_SSH_DISCOVERY_METHODS && methods == 5U && fixture.callback.selected_calls == 0);
        CHECK(ctos_ssh_native_client_begin_private_key_auth(fixture.client) == 0);
        drive_private(&fixture, CTOS_SSH_PRIVATE_KEY_AUTH_SUCCESS, 0);
        CHECK(fixture.callback.selected_calls == 1 && fixture.private_requests == 1);
        cleanup_fixture(&fixture, 1); sshbuf_free(file); free(blob); sshkey_free(key);
    }
}

struct no_pty_owner_attempt { struct ctos_ssh_native_client *client; int result, error; };

static void *no_pty_wrong_owner(void *opaque) {
    struct no_pty_owner_attempt *attempt = opaque;
    errno = 0;
    attempt->result = ctos_ssh_native_client_configure_no_pty(attempt->client);
    attempt->error = errno;
    return NULL;
}

static void setup_shell_fixture_mode_with_options(struct fixture *fixture, int kind, uint32_t window,
    uint32_t maximum, int reject, enum ctos_ssh_native_host_key_mode mode, int new_host,
    int configure_alive, unsigned int interval, unsigned int count_max, int no_pty) {
    if (kind == 1) {
        setup_password_fixture_mode(fixture, new_host, 60000U, mode);
        CHECK(shell_status(fixture).phase == CTOS_SSH_SHELL_DISABLED);
        CHECK(ctos_ssh_native_client_begin_shell(fixture->client, 80, 24) != 0 && errno == EACCES);
        CHECK(ctos_ssh_native_client_configure_server_alive(fixture->client, 1, 3) != 0 && errno == EACCES);
        CHECK(ctos_ssh_native_client_configure_no_pty(fixture->client) != 0 && errno == EACCES);
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
    if (configure_alive) {
        CHECK(ctos_ssh_native_client_configure_server_alive(fixture->client, UINT_MAX, count_max) != 0
            && errno == EINVAL);
        CHECK(ctos_ssh_native_client_configure_server_alive(fixture->client, interval, UINT_MAX) != 0
            && errno == EINVAL);
        CHECK(ctos_ssh_native_client_configure_server_alive(fixture->client, interval, count_max) == 0);
        CHECK(ctos_ssh_native_client_configure_server_alive(fixture->client, interval, count_max) != 0
            && errno == EALREADY);
    }
    if (no_pty) {
        struct no_pty_owner_attempt attempt = {.client = fixture->client};
        pthread_t thread;
        CHECK(pthread_create(&thread, NULL, no_pty_wrong_owner, &attempt) == 0);
        CHECK(pthread_join(thread, NULL) == 0);
        CHECK(attempt.result != 0 && attempt.error == EPERM);
        CHECK(ctos_ssh_native_client_configure_no_pty(fixture->client) == 0);
        CHECK(ctos_ssh_native_client_configure_no_pty(fixture->client) != 0 && errno == EALREADY);
    }
    fixture->shell_expected = 1;
    fixture->shell_no_pty = no_pty;
    fixture->shell_window = window;
    fixture->shell_maxpacket = maximum;
    fixture->shell_reject = reject;
    CHECK(ctos_ssh_native_client_begin_shell(fixture->client, 0, 24) != 0 && errno == EINVAL);
    CHECK(ctos_ssh_native_client_begin_shell(fixture->client, 80, 4097) != 0 && errno == EINVAL);
    CHECK(ctos_ssh_native_client_begin_shell(fixture->client, 80, 24) == 0);
    CHECK(ctos_ssh_native_client_begin_shell(fixture->client, 80, 24) != 0 && errno == EALREADY);
    CHECK(shell_status(fixture).phase == CTOS_SSH_SHELL_OPENING);
}

static void setup_shell_fixture_mode_with_alive(struct fixture *fixture, int kind, uint32_t window,
    uint32_t maximum, int reject, enum ctos_ssh_native_host_key_mode mode, int new_host,
    int configure_alive, unsigned int interval, unsigned int count_max) {
    setup_shell_fixture_mode_with_options(fixture, kind, window, maximum, reject, mode, new_host,
        configure_alive, interval, count_max, 0);
}

static void setup_shell_fixture_mode(struct fixture *fixture, int kind, uint32_t window,
    uint32_t maximum, int reject, enum ctos_ssh_native_host_key_mode mode, int new_host) {
    setup_shell_fixture_mode_with_alive(fixture, kind, window, maximum, reject, mode, new_host, 0, 0, 0);
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

static void wait_for_alive_request(struct fixture *fixture, unsigned int expected) {
    for (unsigned int turn = 0; turn < 100000U && fixture->server_alive_requests < expected; turn++)
        CHECK(shell_turn(fixture) == 0);
    CHECK(fixture->server_alive_requests == expected);
}

static void drain_alive_response(struct fixture *fixture) {
    for (unsigned int turn = 0; turn < 200U; turn++) CHECK(shell_turn(fixture) == 0);
}

static void test_server_alive_protocol(void) {
    for (int reply = 1; reply <= 3; reply++) {
        struct fixture fixture;
        setup_shell_fixture_mode_with_alive(&fixture, reply, 65536U, 32768U, 0,
            CTOS_SSH_HOST_KEY_PINNED, 0, 1, 1, 1);
        fixture.server_alive_expected = 1;
        fixture.server_alive_reply = reply;
        drive_shell_phase(&fixture, CTOS_SSH_SHELL_RUNNING);
        drain_alive_response(&fixture);
        for (unsigned int expected = 1; expected <= 2; expected++) {
            wait_until_deadline_attempts(deadline_after(1100U), 2000U);
            wait_for_alive_request(&fixture, expected);
            drain_alive_response(&fixture);
        }
        cleanup_fixture(&fixture, 1U);
    }
    for (int recover = 0; recover <= 1; recover++) {
        struct fixture fixture;
        setup_shell_fixture_mode_with_alive(&fixture, 1, 65536U, 32768U, 0,
            CTOS_SSH_HOST_KEY_PINNED, 0, 1, 1, 2);
        fixture.server_alive_expected = 1;
        drive_shell_phase(&fixture, CTOS_SSH_SHELL_RUNNING);
        drain_alive_response(&fixture);
        for (unsigned int expected = 1; expected <= 2; expected++) {
            wait_until_deadline_attempts(deadline_after(1100U), 2000U);
            wait_for_alive_request(&fixture, expected);
        }
        if (recover) {
            CHECK(sshpkt_start(fixture.server, SSH2_MSG_REQUEST_FAILURE) == 0);
            CHECK(sshpkt_send(fixture.server) == 0);
            drain_alive_response(&fixture);
            wait_until_deadline_attempts(deadline_after(1100U), 2000U);
            wait_for_alive_request(&fixture, 3U);
        } else {
            wait_until_deadline_attempts(deadline_after(1100U), 2000U);
            CHECK(shell_turn(&fixture) == -1 && errno == ETIMEDOUT);
            CHECK(fixture.server_alive_requests == 2U);
        }
        cleanup_fixture(&fixture, 1U);
    }
    for (unsigned int interval = 0; interval <= 1; interval++) {
        struct fixture fixture;
        setup_shell_fixture_mode_with_alive(&fixture, 1, 65536U, 32768U, 0,
            CTOS_SSH_HOST_KEY_PINNED, 0, 1, interval, 0);
        fixture.server_alive_expected = 1;
        drive_shell_phase(&fixture, CTOS_SSH_SHELL_RUNNING);
        drain_alive_response(&fixture);
        wait_until_deadline_attempts(deadline_after(1100U), 2000U);
        if (interval == 0) {
            drain_alive_response(&fixture);
            CHECK(fixture.server_alive_requests == 0U);
        } else {
            CHECK(shell_turn(&fixture) == -1 && errno == ETIMEDOUT);
            CHECK(fixture.server_alive_requests == 0U);
        }
        cleanup_fixture(&fixture, 1U);
    }
    for (int reply = SSH2_MSG_REQUEST_SUCCESS; reply <= SSH2_MSG_REQUEST_FAILURE; reply++) {
        struct fixture fixture;
        setup_shell_fixture_mode_with_alive(&fixture, 1, 65536U, 32768U, 0,
            CTOS_SSH_HOST_KEY_PINNED, 0, 1, 0, 3);
        drive_shell_phase(&fixture, CTOS_SSH_SHELL_RUNNING);
        CHECK(sshpkt_start(fixture.server, reply) == 0 && sshpkt_send(fixture.server) == 0);
        int failed = 0;
        for (unsigned int turn = 0; turn < 10000U && !failed; turn++) {
            if (shell_turn(&fixture) != 0) { CHECK(errno == EPROTO); failed = 1; }
        }
        CHECK(failed);
        cleanup_fixture(&fixture, 1U);
    }
    {
        struct fixture fixture;
        setup_shell_fixture_mode_with_alive(&fixture, 1, 65536U, 32768U, 0,
            CTOS_SSH_HOST_KEY_PINNED, 0, 1, 1, 1);
        fixture.server_alive_expected = 1;
        drive_shell_phase(&fixture, CTOS_SSH_SHELL_RUNNING);
        drain_alive_response(&fixture);
        wait_until_deadline_attempts(deadline_after(1100U), 2000U);
        wait_for_alive_request(&fixture, 1U);
        CHECK(ctos_ssh_native_client_step(fixture.client,
            &(struct ctos_ssh_native_client_step){.cancelled = 1}) != 0 && errno == ECANCELED);
        CHECK(fixture.server_alive_requests == 1U);
        cleanup_fixture(&fixture, 1U);
    }
    {
        struct fixture fixture;
        setup_shell_fixture_mode_with_alive(&fixture, 1, 65536U, 32768U, 0,
            CTOS_SSH_HOST_KEY_PINNED, 0, 1, 1, 2);
        fixture.server_alive_expected = 1;
        fixture.server_alive_reply = 1;
        drive_shell_phase(&fixture, CTOS_SSH_SHELL_RUNNING);
        peer_channel_data(&fixture, 0, (const unsigned char *)"a", 1U);
        for (unsigned int turn = 0; turn < 10000U && shell_status(&fixture).buffered_bytes < 1U; turn++)
            CHECK(shell_turn(&fixture) == 0);
        CHECK(shell_status(&fixture).buffered_bytes == 1U);
        wait_until_deadline_attempts(deadline_after(600U), 1000U);
        peer_channel_data(&fixture, 0, (const unsigned char *)"b", 1U);
        for (unsigned int turn = 0; turn < 10000U && shell_status(&fixture).buffered_bytes < 2U; turn++)
            CHECK(shell_turn(&fixture) == 0);
        CHECK(shell_status(&fixture).buffered_bytes == 2U);
        unsigned int previous = fixture.server_alive_requests;
        wait_until_deadline_attempts(deadline_after(600U), 1000U);
        drain_alive_response(&fixture);
        CHECK(fixture.server_alive_requests == previous);
        wait_until_deadline_attempts(deadline_after(550U), 1000U);
        wait_for_alive_request(&fixture, previous + 1U);
        drain_alive_response(&fixture);
        cleanup_fixture(&fixture, 1U);
    }
}

static void test_shell_no_pty(void) {
    for (int kind = 1; kind <= 2; kind++) for (int outcome = 0; outcome <= 3; outcome++) {
        struct fixture fixture;
        unsigned char output[32];
        size_t length = 0, accepted = 0;
        int saw_pending_output = 0;
        setup_shell_fixture_mode_with_options(&fixture, kind, 65536U, 32768U, outcome == 1 ? 3 : 0,
            CTOS_SSH_HOST_KEY_PINNED, 0, 1, 0, 3, 1);
        fixture.shell_early_output_stage = 3;
        fixture.shell_early_close_stage = outcome == 2 ? 3 : 0;
        for (unsigned int turn = 0; turn < 40000U; turn++) {
            CHECK(shell_turn(&fixture) == 0);
            struct ctos_ssh_native_shell_status status = shell_status(&fixture);
            if (status.buffered_bytes != 0 && status.phase != CTOS_SSH_SHELL_RUNNING) {
                memset(output, 0xaa, sizeof(output)); length = 99U;
                CHECK(status.phase == CTOS_SSH_SHELL_SHELL_PENDING);
                CHECK(ctos_ssh_native_client_shell_read(fixture.client, output, sizeof(output), &length) != 0
                    && errno == EACCES && length == 0);
                CHECK(ctos_ssh_native_client_shell_write(fixture.client, (const unsigned char *)"x", 1,
                    &accepted) != 0 && accepted == 0);
                for (size_t i = 0; i < sizeof(output); i++) CHECK(output[i] == 0);
                saw_pending_output = 1;
                if (outcome == 3) {
                    CHECK(ctos_ssh_native_client_step(fixture.client,
                        &(struct ctos_ssh_native_client_step){.cancelled = 1}) != 0 && errno == ECANCELED);
                    memset(output, 0xaa, sizeof(output)); length = 99;
                    CHECK(ctos_ssh_native_client_shell_read(fixture.client, output, sizeof(output), &length) != 0
                        && length == 0);
                    for (size_t i = 0; i < sizeof(output); i++) CHECK(output[i] == 0);
                    break;
                }
            }
            if (status.phase == CTOS_SSH_SHELL_RUNNING || status.phase == CTOS_SSH_SHELL_REJECTED
                || status.phase == CTOS_SSH_SHELL_CLOSED) break;
        }
        CHECK(saw_pending_output && fixture.shell_stage == 3);
        if (outcome == 3) { cleanup_fixture(&fixture, 1); continue; }
        struct ctos_ssh_native_shell_status status = shell_status(&fixture);
        if (outcome != 0) {
            CHECK(status.phase == (outcome == 1 ? CTOS_SSH_SHELL_REJECTED : CTOS_SSH_SHELL_CLOSED));
            CHECK(status.buffered_bytes == 0);
            CHECK(ctos_ssh_native_client_shell_read(fixture.client, output, sizeof(output), &length) != 0
                && length == 0);
        } else {
            CHECK(status.phase == CTOS_SSH_SHELL_RUNNING && status.buffered_bytes == 11U);
            CHECK(ctos_ssh_native_client_shell_read(fixture.client, output, sizeof(output), &length) == 0
                && length == 11U && memcmp(output, "earlystderr", 11U) == 0);
            CHECK(ctos_ssh_native_client_shell_resize(fixture.client, 0, 43) != 0 && errno == EINVAL);
            CHECK(ctos_ssh_native_client_shell_resize(fixture.client, 132, 43) == 1);
            CHECK(ctos_ssh_native_client_shell_write(fixture.client, (const unsigned char *)"input\n", 6,
                &accepted) == 0 && accepted == 6);
            CHECK(ctos_ssh_native_client_shell_eof(fixture.client) == 1);
            for (unsigned int turn = 0; turn < 10000U && fixture.shell_eof_count == 0; turn++)
                CHECK(shell_turn(&fixture) == 0);
            CHECK(fixture.shell_eof_count == 1 && fixture.shell_resize_count == 0);
            CHECK(fixture.shell_input_length == 6 && memcmp(fixture.shell_input, "input\n", 6) == 0);
            peer_channel_data(&fixture, 0, (const unsigned char *)"output", 6);
            peer_channel_packet(&fixture, SSH2_MSG_CHANNEL_CLOSE);
            CHECK(sshpkt_send(fixture.server) == 0);
            drive_shell_phase(&fixture, CTOS_SSH_SHELL_CLOSED);
            CHECK(ctos_ssh_native_client_shell_read(fixture.client, output, sizeof(output), &length) == 0
                && length == 6 && memcmp(output, "output", 6) == 0);
        }
        cleanup_fixture(&fixture, 1);
    }
    {
        struct fixture fixture;
        setup_shell_fixture_mode_with_options(&fixture, 1, 65536U, 32768U, 1,
            CTOS_SSH_HOST_KEY_PINNED, 0, 0, 0, 0, 1);
        drive_shell_phase(&fixture, CTOS_SSH_SHELL_REJECTED);
        CHECK(fixture.shell_stage == 1 && fixture.shell_resize_count == 0);
        cleanup_fixture(&fixture, 1);
    }
    {
        struct fixture fixture;
        setup_password_fixture(&fixture, 0, 15000U);
        CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
        drive_password(&fixture, CTOS_SSH_PASSWORD_AUTH_SUCCESS, 0);
        fixture.view->valid = 0;
        CHECK(ctos_ssh_native_client_configure_no_pty(fixture.client) != 0 && errno == EACCES);
        fixture.view->valid = 1;
        CHECK(ctos_ssh_native_client_begin_shell(fixture.client, 80, 24) != 0);
        cleanup_fixture(&fixture, 1);
    }
    for (int reply = PASSWORD_REPLY_REJECTED; reply <= PASSWORD_REPLY_CHANGE; reply++) {
        struct fixture fixture;
        setup_password_fixture(&fixture, 0, 15000U);
        fixture.password_reply = (enum password_reply)reply;
        CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
        drive_password(&fixture, (enum ctos_ssh_native_client_password_auth_result)(reply + 2), 0);
        CHECK(ctos_ssh_native_client_configure_no_pty(fixture.client) != 0 && errno == EACCES);
        cleanup_fixture(&fixture, 1);
    }
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

static ctos_ssh_role_token jump_next_role(const struct fixture *fixture, unsigned int variant) {
    static const unsigned char nonce[32] = {9};
    static const unsigned char wrong_binding[32] = {7};
    struct ctos_ssh_role_request next = role_request(fixture->deadline);
    next.hop = variant == 1U ? 2U : 1U;
    next.program = CTOS_SSH_ROLE_CLIENT;
    next.host = "nexthop.example"; next.port = 2222;
    if (variant == 6U) { next.host = "example.invalid"; next.port = 22; }
    if (variant == 7U) {
        next.program = CTOS_SSH_ROLE_JUMP;
        next.host = "example.invalid"; next.port = 22;
    }
    next.public_view_id = "next-view"; next.role_nonce = nonce;
    if (variant == 2U) next.connection_id = "other-connection";
    if (variant == 3U) next.target_identity = "other-target";
    if (variant == 4U) next.connection_binding = wrong_binding;
    if (variant == 5U) next.deadline--;
    ctos_ssh_role_token token = 0;
    CHECK(ctos_ssh_role_bootstrap_open(&next, &token) == 0 && token != 0);
    return token;
}

static void setup_jump_fixture_lifetime(struct fixture *fixture, int jump_role,
    unsigned int lifetime) {
    fixture_jump_role = jump_role;
    setup_password_fixture(fixture, 0, lifetime);
    fixture_jump_role = 0;
    fixture->shell_expected = 1;
    fixture->jump_expected = 1;
    fixture->shell_window = 16U;
    fixture->shell_maxpacket = 8U;
}

static void setup_jump_fixture(struct fixture *fixture, int jump_role) {
    setup_jump_fixture_lifetime(fixture, jump_role, 60000U);
}

static struct ctos_ssh_native_jump_status jump_status(struct fixture *fixture) {
    struct ctos_ssh_native_jump_status status;
    memset(&status, 0xa5, sizeof(status));
    CHECK(ctos_ssh_native_client_jump_status(fixture->client, &status) == 0);
    return status;
}

static void drive_jump_phase(struct fixture *fixture, enum ctos_ssh_native_jump_phase phase) {
    for (unsigned int turn = 0; turn < 100000U; turn++) {
        CHECK(shell_turn(fixture) == 0);
        if (jump_status(fixture).phase == phase) return;
    }
    CHECK(0);
}

struct jump_owner_attempt {
    struct ctos_ssh_native_client *client;
    struct ctos_ssh_native_jump_status status;
    int status_result, status_errno, write_result, write_errno;
    size_t accepted;
};
static void *jump_wrong_owner(void *opaque) {
    struct jump_owner_attempt *attempt = opaque;
    memset(&attempt->status, 0xa5, sizeof(attempt->status));
    attempt->status_result = ctos_ssh_native_client_jump_status(attempt->client, &attempt->status);
    attempt->status_errno = errno;
    attempt->accepted = 99;
    attempt->write_result = ctos_ssh_native_client_jump_write(attempt->client,
        (const unsigned char *)"x", 1, &attempt->accepted);
    attempt->write_errno = errno;
    return NULL;
}

struct jump_retain_owner_attempt {
    struct ctos_ssh_native_client *client;
    int result, error, auth_result, auth_error;
};

static void *jump_retain_wrong_owner(void *opaque) {
    struct jump_retain_owner_attempt *attempt = opaque;
    attempt->result = ctos_ssh_native_client_retain_jump_transport(attempt->client);
    attempt->error = errno;
    attempt->auth_result = ctos_ssh_native_client_begin_auth_discovery(attempt->client);
    attempt->auth_error = errno;
    return NULL;
}

static void test_jump_channel_roundtrip(void) {
    struct fixture fixture;
    unsigned char output[64];
    size_t accepted, length, sent = 0;
    const unsigned char banner[] = "SSH-2.0-next-client\r\n";
    setup_jump_fixture(&fixture, 1);
    ctos_ssh_role_token next = jump_next_role(&fixture, 0);
    CHECK(ctos_ssh_native_client_begin_jump(fixture.client, next) != 0 && errno == EACCES);
    CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
    drive_password(&fixture, CTOS_SSH_PASSWORD_AUTH_SUCCESS, 0);
    CHECK(ctos_ssh_native_client_begin_shell(fixture.client, 80, 24) != 0 && errno == EACCES);
    CHECK(ctos_ssh_native_client_begin_jump(fixture.client, next) == 0);
    CHECK(jump_status(&fixture).phase == CTOS_SSH_JUMP_OPENING);
    memset(output, 0xa5, sizeof(output)); length = 99;
    CHECK(ctos_ssh_native_client_jump_read(fixture.client, output, sizeof(output), &length) != 0
        && errno == EACCES && length == 0);
    for (size_t i = 0; i < sizeof(output); i++) CHECK(output[i] == 0);
    struct ctos_ssh_native_shell_status terminal;
    memset(&terminal, 0xa5, sizeof(terminal));
    CHECK(ctos_ssh_native_client_shell_status(fixture.client, &terminal) != 0 && errno == EACCES);
    CHECK(ctos_ssh_native_client_begin_shell(fixture.client, 80, 24) != 0 && errno == EALREADY);
    drive_jump_phase(&fixture, CTOS_SSH_JUMP_OPEN);
    CHECK(fixture.shell_stage == 3);

    struct jump_owner_attempt attempt = {.client = fixture.client}; pthread_t thread;
    CHECK(pthread_create(&thread, NULL, jump_wrong_owner, &attempt) == 0);
    CHECK(pthread_join(thread, NULL) == 0 && attempt.status_result != 0 && attempt.status_errno == EPERM);
    CHECK(attempt.status.phase == 0 && attempt.write_result != 0 && attempt.write_errno == EPERM && attempt.accepted == 0);
    CHECK(ctos_ssh_native_client_shell_write(fixture.client, banner, 1, &accepted) != 0
        && errno == EACCES && accepted == 0);
    CHECK(ctos_ssh_native_client_shell_resize(fixture.client, 80, 24) != 0 && errno == EACCES);
    CHECK(ctos_ssh_native_client_shell_eof(fixture.client) != 0 && errno == EACCES);
    CHECK(ctos_ssh_native_client_jump_write(fixture.client, banner, sizeof(banner) - 1, &accepted) == 0 && accepted == 8U);
    sent += accepted;
    CHECK(ctos_ssh_native_client_jump_write(fixture.client, banner + sent, sizeof(banner) - 1 - sent, &accepted) == 0 && accepted == 8U);
    sent += accepted;
    CHECK(ctos_ssh_native_client_jump_write(fixture.client, banner + sent, sizeof(banner) - 1 - sent, &accepted) == 0 && accepted == 0);
    send_window(&fixture, 32U);
    for (unsigned int turn = 0; turn < 10000U && sent < sizeof(banner) - 1; turn++) {
        CHECK(shell_turn(&fixture) == 0);
        CHECK(ctos_ssh_native_client_jump_write(fixture.client, banner + sent, sizeof(banner) - 1 - sent, &accepted) == 0);
        sent += accepted;
    }
    CHECK(sent == sizeof(banner) - 1);
    CHECK(ctos_ssh_native_client_jump_eof(fixture.client) == 1);
    CHECK(ctos_ssh_native_client_jump_eof(fixture.client) != 0 && errno == EACCES);
    for (unsigned int turn = 0; turn < 10000U && fixture.shell_eof_count == 0; turn++) CHECK(shell_turn(&fixture) == 0);
    CHECK(fixture.shell_input_length == sizeof(banner) - 1
        && memcmp(fixture.shell_input, banner, sizeof(banner) - 1) == 0);
    CHECK(fixture.shell_resize_count == 0 && fixture.shell_eof_count == 1);

    peer_channel_data(&fixture, 0, (const unsigned char *)"SSH-2.0-server\r\n", 16U);
    peer_channel_packet(&fixture, SSH2_MSG_CHANNEL_EOF); CHECK(sshpkt_send(fixture.server) == 0);
    peer_channel_packet(&fixture, SSH2_MSG_CHANNEL_CLOSE); CHECK(sshpkt_send(fixture.server) == 0);
    drive_jump_phase(&fixture, CTOS_SSH_JUMP_CLOSED);
    struct ctos_ssh_native_jump_status status = jump_status(&fixture);
    CHECK(status.buffered_bytes == 16U && status.remote_eof && status.local_eof);
    CHECK(ctos_ssh_native_client_jump_read(fixture.client, output, sizeof(output), &length) == 0 && length == 16U);
    CHECK(memcmp(output, "SSH-2.0-server\r\n", 16U) == 0);
    CHECK(ctos_ssh_native_client_jump_read(fixture.client, output, sizeof(output), &length) == 0 && length == 0);
    fixture.callback.release_failures_remaining = 1;
    CHECK(ctos_ssh_native_client_close(fixture.client) != 0 && errno == EIO);
    CHECK(fcntl(fixture.pair[0], F_GETFD) >= 0);
    cleanup_fixture(&fixture, 2);
    CHECK(ctos_ssh_role_bootstrap_close(next) == 0);
}

static void test_jump_channel_admission(void) {
    struct fixture fixture;
    setup_jump_fixture(&fixture, 1);
    CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
    drive_password(&fixture, CTOS_SSH_PASSWORD_AUTH_SUCCESS, 0);
    for (unsigned int variant = 1; variant <= 5; variant++) {
        ctos_ssh_role_token next = jump_next_role(&fixture, variant);
        CHECK(ctos_ssh_native_client_begin_jump(fixture.client, next) != 0 && errno == EACCES);
        CHECK(fixture.shell_stage == 0 && fixture.callback.selected_calls == 1);
        CHECK(ctos_ssh_role_bootstrap_close(next) == 0);
    }
    ctos_ssh_role_token next = jump_next_role(&fixture, 0);
    CHECK(ctos_ssh_native_client_begin_jump(fixture.client, next) == 0);
    CHECK(ctos_ssh_native_client_begin_jump(fixture.client, next) != 0 && errno == EALREADY);
    CHECK(ctos_ssh_role_bootstrap_close(next) == 0);
    CHECK(ctos_ssh_native_client_step(fixture.client, &(struct ctos_ssh_native_client_step){0}) != 0);
    unsigned char output[8]; size_t length = 99;
    memset(output, 0xa5, sizeof(output));
    CHECK(ctos_ssh_native_client_jump_read(fixture.client, output, sizeof(output), &length) != 0 && length == 0);
    for (size_t i = 0; i < sizeof(output); i++) CHECK(output[i] == 0);
    CHECK(fixture.shell_stage == 0);
    cleanup_fixture(&fixture, 1);

    setup_jump_fixture(&fixture, 0);
    CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
    drive_password(&fixture, CTOS_SSH_PASSWORD_AUTH_SUCCESS, 0);
    next = jump_next_role(&fixture, 0);
    CHECK(ctos_ssh_native_client_begin_jump(fixture.client, next) != 0 && errno == EACCES);
    CHECK(fixture.shell_stage == 0);
    cleanup_fixture(&fixture, 1);
    CHECK(ctos_ssh_role_bootstrap_close(next) == 0);

    for (int reply = PASSWORD_REPLY_REJECTED; reply <= PASSWORD_REPLY_CHANGE; reply++) {
        setup_jump_fixture(&fixture, 1);
        fixture.password_reply = (enum password_reply)reply;
        CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
        drive_password(&fixture, (enum ctos_ssh_native_client_password_auth_result)(reply + 2), 0);
        next = jump_next_role(&fixture, 0);
        CHECK(ctos_ssh_native_client_begin_jump(fixture.client, next) != 0 && errno == EACCES);
        CHECK(fixture.shell_stage == 0);
        cleanup_fixture(&fixture, 1);
        CHECK(ctos_ssh_role_bootstrap_close(next) == 0);
    }
}

static void test_jump_chain_identity_admission(void) {
    static const unsigned char wrong_binding[32] = {7};
    static const unsigned char next_nonce[32] = {9};
    struct fixture fixture;
    fixture_chain_identity = "route-identity";
    setup_jump_fixture(&fixture, 1);
    fixture_chain_identity = NULL;
    CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
    drive_password(&fixture, CTOS_SSH_PASSWORD_AUTH_SUCCESS, 0);

    for (unsigned int variant = 0; variant < 4; variant++) {
        struct ctos_ssh_role_request request = role_request(fixture.deadline);
        request.hop = 1;
        request.host = "nexthop.example";
        request.port = 2222;
        request.role_nonce = next_nonce;
        request.target_identity = "different-target";
        request.chain_identity = "route-identity";
        if (variant == 0) request.chain_identity = "other-route";
        if (variant == 1) request.connection_id = "other-connection";
        if (variant == 2) {
            request.chain_identity = NULL;
            request.target_identity = "route-identity";
        }
        if (variant == 3) request.connection_binding = wrong_binding;
        ctos_ssh_role_token next = 0;
        CHECK(ctos_ssh_role_bootstrap_open(&request, &next) == 0);
        CHECK(ctos_ssh_native_client_begin_jump(fixture.client, next) != 0 && errno == EACCES);
        CHECK(fixture.shell_stage == 0 && fixture.callback.selected_calls == 1);
        CHECK(ctos_ssh_role_bootstrap_close(next) == 0);
    }

    struct ctos_ssh_role_request request = role_request(fixture.deadline);
    request.hop = 1;
    request.host = "nexthop.example";
    request.port = 2222;
    request.role_nonce = next_nonce;
    request.target_identity = "different-target";
    request.chain_identity = "route-identity";
    ctos_ssh_role_token next = 0;
    CHECK(ctos_ssh_role_bootstrap_open(&request, &next) == 0);
    CHECK(ctos_ssh_native_client_begin_jump(fixture.client, next) == 0);
    cleanup_fixture(&fixture, 1);
    CHECK(ctos_ssh_role_bootstrap_close(next) == 0);

    /* The reciprocal mixed-version direction also fails. */
    setup_jump_fixture(&fixture, 1);
    CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
    drive_password(&fixture, CTOS_SSH_PASSWORD_AUTH_SUCCESS, 0);
    request = role_request(fixture.deadline);
    request.hop = 1;
    request.role_nonce = next_nonce;
    request.chain_identity = request.target_identity;
    next = 0;
    CHECK(ctos_ssh_role_bootstrap_open(&request, &next) == 0);
    CHECK(ctos_ssh_native_client_begin_jump(fixture.client, next) != 0 && errno == EACCES);
    CHECK(fixture.shell_stage == 0);
    CHECK(ctos_ssh_role_bootstrap_close(next) == 0);
    cleanup_fixture(&fixture, 1);
}

static void test_jump_channel_rejection_and_protocol(void) {
    for (unsigned int mode = 0; mode < 6; mode++) {
        struct fixture fixture;
        setup_jump_fixture(&fixture, 1);
        CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
        drive_password(&fixture, CTOS_SSH_PASSWORD_AUTH_SUCCESS, 0);
        ctos_ssh_role_token next = jump_next_role(&fixture, 0);
        fixture.shell_reject = mode == 0 ? 1 : 0;
        fixture.jump_preconfirm_data = mode == 1;
        CHECK(ctos_ssh_native_client_begin_jump(fixture.client, next) == 0);
        if (mode == 0) {
            drive_jump_phase(&fixture, CTOS_SSH_JUMP_REJECTED);
            CHECK(ctos_ssh_native_client_step(fixture.client,
                &(struct ctos_ssh_native_client_step){0}) != 0 && errno == EALREADY);
        } else if (mode == 1) {
            int failed = 0;
            for (unsigned int turn = 0; turn < 10000U; turn++)
                if (shell_turn(&fixture) != 0) { CHECK(errno == EPROTO); failed = 1; break; }
            CHECK(failed);
        } else {
            drive_jump_phase(&fixture, CTOS_SSH_JUMP_OPEN);
            if (mode == 2) peer_channel_data(&fixture, 1, (const unsigned char *)"stderr", 6U);
            if (mode == 3) {
                CHECK(sshpkt_start(fixture.server, SSH2_MSG_CHANNEL_DATA) == 0);
                CHECK(sshpkt_put_u32(fixture.server, 1U) == 0);
                CHECK(sshpkt_put_string(fixture.server, "wrong", 5U) == 0 && sshpkt_send(fixture.server) == 0);
            }
            if (mode == 4) send_window(&fixture, 0);
            if (mode == 5) {
                peer_channel_packet(&fixture, SSH2_MSG_CHANNEL_EOF); CHECK(sshpkt_send(fixture.server) == 0);
                peer_channel_data(&fixture, 0, (const unsigned char *)"after-eof", 9U);
            }
            int failed = 0;
            for (unsigned int turn = 0; turn < 10000U; turn++)
                if (shell_turn(&fixture) != 0) { CHECK(errno == EPROTO); failed = 1; break; }
            CHECK(failed);
        }
        unsigned char output[16]; size_t length = 99;
        memset(output, 0xa5, sizeof(output));
        CHECK(ctos_ssh_native_client_jump_read(fixture.client, output, sizeof(output), &length) != 0 && length == 0);
        for (size_t i = 0; i < sizeof(output); i++) CHECK(output[i] == 0);
        cleanup_fixture(&fixture, 1);
        CHECK(ctos_ssh_role_bootstrap_close(next) == 0);
    }
}

static void test_jump_channel_lifetime(void) {
    for (unsigned int mode = 0; mode < 4; mode++) {
        struct fixture fixture;
        setup_jump_fixture(&fixture, 1);
        CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
        drive_password(&fixture, CTOS_SSH_PASSWORD_AUTH_SUCCESS, 0);
        ctos_ssh_role_token next = jump_next_role(&fixture, 0);
        CHECK(ctos_ssh_native_client_begin_jump(fixture.client, next) == 0);
        drive_jump_phase(&fixture, CTOS_SSH_JUMP_OPEN);
        peer_channel_data(&fixture, 0, (const unsigned char *)"pending", 7U);
        for (unsigned int turn = 0; turn < 10000U && jump_status(&fixture).buffered_bytes == 0; turn++)
            CHECK(shell_turn(&fixture) == 0);
        CHECK(jump_status(&fixture).buffered_bytes == 7U);
        if (mode == 0) { CHECK(ctos_ssh_role_bootstrap_close(next) == 0); next = 0; }
        if (mode == 1) fixture.view->valid = 0;
        if (mode == 2) CHECK(ctos_ssh_callback_cancel(fixture.callback_token) == 0);
        if (mode == 3) CHECK(ctos_ssh_native_client_step(fixture.client,
            &(struct ctos_ssh_native_client_step){.cancelled = 1}) != 0 && errno == ECANCELED);
        unsigned char output[32]; size_t length = 99;
        memset(output, 0xa5, sizeof(output));
        CHECK(ctos_ssh_native_client_jump_read(fixture.client, output, sizeof(output), &length) != 0 && length == 0);
        for (size_t i = 0; i < sizeof(output); i++) CHECK(output[i] == 0);
        struct ctos_ssh_native_jump_status status;
        memset(&status, 0xa5, sizeof(status));
        CHECK(ctos_ssh_native_client_jump_status(fixture.client, &status) != 0 && status.phase == 0);
        fixture.view->valid = 1;
        cleanup_fixture(&fixture, 1);
        if (next != 0) CHECK(ctos_ssh_role_bootstrap_close(next) == 0);
    }
}

static void test_jump_channel_receive_bound(void) {
    struct fixture fixture;
    unsigned char bytes[32768], output[32768]; size_t length = 99;
    memset(bytes, 'x', sizeof(bytes));
    setup_jump_fixture(&fixture, 1);
    CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
    drive_password(&fixture, CTOS_SSH_PASSWORD_AUTH_SUCCESS, 0);
    ctos_ssh_role_token next = jump_next_role(&fixture, 0);
    CHECK(ctos_ssh_native_client_begin_jump(fixture.client, next) == 0);
    drive_jump_phase(&fixture, CTOS_SSH_JUMP_OPEN);
    peer_channel_data(&fixture, 0, bytes, sizeof(bytes));
    peer_channel_data(&fixture, 0, bytes, sizeof(bytes));
    for (unsigned int turn = 0; turn < 40000U && jump_status(&fixture).buffered_bytes != 65536U; turn++)
        CHECK(shell_turn(&fixture) == 0);
    CHECK(jump_status(&fixture).buffered_bytes == 65536U);
    peer_channel_data(&fixture, 0, bytes, 1U);
    int failed = 0;
    for (unsigned int turn = 0; turn < 10000U; turn++)
        if (shell_turn(&fixture) != 0) { CHECK(errno == EPROTO); failed = 1; break; }
    CHECK(failed);
    memset(output, 0xa5, sizeof(output));
    CHECK(ctos_ssh_native_client_jump_read(fixture.client, output, sizeof(output), &length) != 0 && length == 0);
    for (size_t i = 0; i < sizeof(output); i++) CHECK(output[i] == 0);
    cleanup_fixture(&fixture, 1);
    CHECK(ctos_ssh_role_bootstrap_close(next) == 0);
}

struct relay_owner_attempt {
    struct ctos_ssh_native_jump_relay *relay;
    int pump, pump_errno, close_result, close_errno, destroy, destroy_errno;
};
static void *relay_wrong_owner(void *opaque) {
    struct relay_owner_attempt *attempt = opaque;
    attempt->pump = ctos_ssh_native_jump_relay_pump(attempt->relay); attempt->pump_errno = errno;
    attempt->close_result = ctos_ssh_native_jump_relay_close(attempt->relay); attempt->close_errno = errno;
    attempt->destroy = ctos_ssh_native_jump_relay_destroy(attempt->relay); attempt->destroy_errno = errno;
    return NULL;
}

static void test_jump_relay_roundtrip(void) {
    static const unsigned char outgoing[] = "0123456789abcdefghijklmnop";
    static const unsigned char incoming[] = "inner-server-bytes";
    struct fixture fixture;
    struct ctos_ssh_native_jump_relay *relay = NULL, *other = NULL;
    int pair[2], unused[2];
    size_t length, received = 0;
    unsigned char output[64];
    setup_jump_fixture(&fixture, 1);
    ctos_ssh_role_token next = jump_next_role(&fixture, 0);
    CHECK(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, pair) == 0);
    CHECK(ctos_ssh_native_jump_relay_open(fixture.client, pair[0], &relay) != 0 && relay == NULL);
    CHECK(fcntl(pair[0], F_GETFD) >= 0);
    CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
    drive_password(&fixture, CTOS_SSH_PASSWORD_AUTH_SUCCESS, 0);
    CHECK(ctos_ssh_native_client_begin_jump(fixture.client, next) == 0);
    drive_jump_phase(&fixture, CTOS_SSH_JUMP_OPEN);
    int duplicated_transport = fcntl(fixture.pair[0], F_DUPFD_CLOEXEC, 0);
    CHECK(duplicated_transport >= 0);
    CHECK(ctos_ssh_native_jump_relay_open(fixture.client, duplicated_transport, &relay) != 0
        && errno == EINVAL && relay == NULL && fcntl(duplicated_transport, F_GETFD) >= 0);
    CHECK(close(duplicated_transport) == 0);
    int datagram[2];
    CHECK(socketpair(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, datagram) == 0);
    CHECK(ctos_ssh_native_jump_relay_open(fixture.client, datagram[0], &relay) != 0
        && errno == EINVAL && relay == NULL && fcntl(datagram[0], F_GETFD) >= 0);
    CHECK(close(datagram[0]) == 0 && close(datagram[1]) == 0);
    CHECK(ctos_ssh_native_jump_relay_open(fixture.client, pair[0], &relay) == 0 && relay != NULL);
    CHECK(ctos_ssh_native_jump_relay_destroy(relay) != 0 && errno == EBUSY);
    CHECK(ctos_ssh_native_client_close(fixture.client) != 0 && errno == EBUSY);
    CHECK(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, unused) == 0);
    CHECK(ctos_ssh_native_jump_relay_open(fixture.client, unused[0], &other) != 0
        && errno == EALREADY && other == NULL && fcntl(unused[0], F_GETFD) >= 0);
    CHECK(close(unused[0]) == 0 && close(unused[1]) == 0);
    length = 99;
    CHECK(ctos_ssh_native_client_jump_write(fixture.client, outgoing, 1, &length) != 0
        && errno == EBUSY && length == 0);
    memset(output, 0xa5, sizeof(output)); length = 99;
    CHECK(ctos_ssh_native_client_jump_read(fixture.client, output, sizeof(output), &length) != 0
        && errno == EBUSY && length == 0);
    for (size_t i = 0; i < sizeof(output); i++) CHECK(output[i] == 0);
    CHECK(ctos_ssh_native_client_jump_eof(fixture.client) != 0 && errno == EBUSY);
    struct relay_owner_attempt attempt = {.relay = relay}; pthread_t thread;
    CHECK(pthread_create(&thread, NULL, relay_wrong_owner, &attempt) == 0);
    CHECK(pthread_join(thread, NULL) == 0 && attempt.pump != 0 && attempt.pump_errno == EPERM
        && attempt.close_result != 0 && attempt.close_errno == EPERM
        && attempt.destroy != 0 && attempt.destroy_errno == EPERM);
    CHECK(send(pair[1], outgoing, sizeof(outgoing) - 1U, MSG_NOSIGNAL) == (ssize_t)sizeof(outgoing) - 1);
    for (unsigned int turn = 0; turn < 10000U && fixture.shell_input_length < 16U; turn++) {
        CHECK(ctos_ssh_native_jump_relay_pump(relay) == 0);
        CHECK(shell_turn(&fixture) == 0);
    }
    CHECK(fixture.shell_input_length == 16U && memcmp(fixture.shell_input, outgoing, 16U) == 0);
    for (int turn = 0; turn < 5; turn++) CHECK(ctos_ssh_native_jump_relay_pump(relay) == 0);
    send_window(&fixture, 64U);
    for (unsigned int turn = 0; turn < 10000U && fixture.shell_input_length < sizeof(outgoing) - 1U; turn++) {
        CHECK(shell_turn(&fixture) == 0);
        CHECK(ctos_ssh_native_jump_relay_pump(relay) == 0);
    }
    CHECK(fixture.shell_input_length == sizeof(outgoing) - 1U
        && memcmp(fixture.shell_input, outgoing, sizeof(outgoing) - 1U) == 0);
    peer_channel_data(&fixture, 0, incoming, sizeof(incoming) - 1U);
    for (unsigned int turn = 0; turn < 10000U && received < sizeof(incoming) - 1U; turn++) {
        CHECK(shell_turn(&fixture) == 0);
        CHECK(ctos_ssh_native_jump_relay_pump(relay) == 0);
        ssize_t n = recv(pair[1], output + received, sizeof(output) - received, MSG_DONTWAIT);
        if (n > 0) received += (size_t)n;
        else CHECK(n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR));
    }
    CHECK(received == sizeof(incoming) - 1U && memcmp(output, incoming, received) == 0);
    CHECK(shutdown(pair[1], SHUT_WR) == 0);
    for (unsigned int turn = 0; turn < 10000U && fixture.shell_eof_count == 0; turn++) {
        CHECK(ctos_ssh_native_jump_relay_pump(relay) == 0);
        CHECK(shell_turn(&fixture) == 0);
    }
    CHECK(fixture.shell_eof_count == 1);
    peer_channel_data(&fixture, 0, (const unsigned char *)"tail", 4U);
    peer_channel_packet(&fixture, SSH2_MSG_CHANNEL_EOF); CHECK(sshpkt_send(fixture.server) == 0);
    peer_channel_packet(&fixture, SSH2_MSG_CHANNEL_CLOSE); CHECK(sshpkt_send(fixture.server) == 0);
    int saw_eof = 0; received = 0;
    for (unsigned int turn = 0; turn < 10000U && !saw_eof; turn++) {
        if (jump_status(&fixture).phase != CTOS_SSH_JUMP_CLOSED) CHECK(shell_turn(&fixture) == 0);
        CHECK(ctos_ssh_native_jump_relay_pump(relay) == 0);
        ssize_t n = recv(pair[1], output + received, sizeof(output) - received, MSG_DONTWAIT);
        if (n == 0) saw_eof = 1;
        else if (n > 0) received += (size_t)n;
        else CHECK(errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR);
    }
    CHECK(saw_eof && received == 4U && memcmp(output, "tail", 4U) == 0);
    CHECK(ctos_ssh_native_jump_relay_close(relay) == 0);
    CHECK(fcntl(pair[0], F_GETFD) < 0 && errno == EBADF);
    CHECK(ctos_ssh_native_jump_relay_close(relay) != 0 && errno == ENOENT);
    CHECK(ctos_ssh_native_jump_relay_destroy(relay) == 0);
    CHECK(close(pair[1]) == 0);
    fixture.callback.release_failures_remaining = 1;
    CHECK(ctos_ssh_native_client_close(fixture.client) != 0 && errno == EIO);
    cleanup_fixture(&fixture, 2);
    CHECK(ctos_ssh_role_bootstrap_close(next) == 0);
}

static void test_jump_relay_revocation(void) {
    for (unsigned int mode = 0; mode < 3; mode++) {
        struct fixture fixture;
        struct ctos_ssh_native_jump_relay *relay = NULL;
        int pair[2];
        setup_jump_fixture(&fixture, 1);
        CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
        drive_password(&fixture, CTOS_SSH_PASSWORD_AUTH_SUCCESS, 0);
        ctos_ssh_role_token next = jump_next_role(&fixture, 0);
        CHECK(ctos_ssh_native_client_begin_jump(fixture.client, next) == 0);
        drive_jump_phase(&fixture, CTOS_SSH_JUMP_OPEN);
        CHECK(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, pair) == 0);
        CHECK(ctos_ssh_native_jump_relay_open(fixture.client, pair[0], &relay) == 0);
        CHECK(send(pair[1], "queued", 6U, MSG_NOSIGNAL) == 6);
        if (mode == 0) { CHECK(ctos_ssh_role_bootstrap_close(next) == 0); next = 0; }
        if (mode == 1) fixture.view->valid = 0;
        if (mode == 2) CHECK(ctos_ssh_callback_cancel(fixture.callback_token) == 0);
        CHECK(ctos_ssh_native_jump_relay_pump(relay) != 0);
        CHECK(ctos_ssh_native_jump_relay_pump(relay) != 0 && errno == EALREADY);
        CHECK(fixture.shell_input_length == 0);
        CHECK(ctos_ssh_native_client_close(fixture.client) != 0 && errno == EBUSY);
        CHECK(ctos_ssh_native_jump_relay_close(relay) == 0);
        CHECK(ctos_ssh_native_jump_relay_destroy(relay) == 0 && close(pair[1]) == 0);
        fixture.view->valid = 1;
        cleanup_fixture(&fixture, 1);
        if (next != 0) CHECK(ctos_ssh_role_bootstrap_close(next) == 0);
    }
}

static void test_jump_relay_peer_backpressure_case(int cancel_before_unblock) {
    static const unsigned char message[] = "buffered-reply";
    struct fixture fixture;
    struct ctos_ssh_native_jump_relay *relay = NULL;
    unsigned char filler[4096], bytes[4096];
    size_t filled = 0, drained = 0, received = 0;
    int pair[2], send_buffer = 4096;
    memset(filler, 'z', sizeof(filler));
    setup_jump_fixture(&fixture, 1);
    CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
    drive_password(&fixture, CTOS_SSH_PASSWORD_AUTH_SUCCESS, 0);
    ctos_ssh_role_token next = jump_next_role(&fixture, 0);
    CHECK(ctos_ssh_native_client_begin_jump(fixture.client, next) == 0);
    drive_jump_phase(&fixture, CTOS_SSH_JUMP_OPEN);
    CHECK(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, pair) == 0);
    CHECK(setsockopt(pair[0], SOL_SOCKET, SO_SNDBUF, &send_buffer, sizeof(send_buffer)) == 0);
    for (;;) {
        ssize_t n = send(pair[0], filler, sizeof(filler), MSG_DONTWAIT | MSG_NOSIGNAL);
        if (n < 0) { CHECK(errno == EAGAIN || errno == EWOULDBLOCK); break; }
        CHECK(n > 0 && filled < 1024U * 1024U); filled += (size_t)n;
    }
    CHECK(filled != 0 && ctos_ssh_native_jump_relay_open(fixture.client, pair[0], &relay) == 0);
    peer_channel_data(&fixture, 0, message, sizeof(message) - 1U);
    for (unsigned int turn = 0; turn < 10000U && jump_status(&fixture).buffered_bytes == 0; turn++)
        CHECK(shell_turn(&fixture) == 0);
    CHECK(jump_status(&fixture).buffered_bytes == sizeof(message) - 1U);
    CHECK(ctos_ssh_native_jump_relay_pump(relay) == 0);
    CHECK(jump_status(&fixture).buffered_bytes == 0);
    for (int turn = 0; turn < 5; turn++) CHECK(ctos_ssh_native_jump_relay_pump(relay) == 0);
    if (cancel_before_unblock) {
        CHECK(ctos_ssh_callback_cancel(fixture.callback_token) == 0);
        CHECK(ctos_ssh_native_jump_relay_pump(relay) != 0);
    }
    while (drained < filled) {
        size_t capacity = filled - drained < sizeof(bytes) ? filled - drained : sizeof(bytes);
        ssize_t n = recv(pair[1], bytes, capacity, MSG_DONTWAIT);
        CHECK(n > 0);
        for (ssize_t i = 0; i < n; i++) CHECK(bytes[i] == 'z');
        drained += (size_t)n;
    }
    if (cancel_before_unblock) {
        CHECK(recv(pair[1], bytes, sizeof(bytes), MSG_DONTWAIT) < 0
            && (errno == EAGAIN || errno == EWOULDBLOCK));
    } else {
        for (unsigned int turn = 0; turn < 10000U && received < sizeof(message) - 1U; turn++) {
            CHECK(ctos_ssh_native_jump_relay_pump(relay) == 0);
            ssize_t n = recv(pair[1], bytes + received, sizeof(bytes) - received, MSG_DONTWAIT);
            if (n > 0) received += (size_t)n;
            else CHECK(n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR));
        }
        CHECK(received == sizeof(message) - 1U && memcmp(bytes, message, received) == 0);
    }
    CHECK(ctos_ssh_native_jump_relay_close(relay) == 0);
    CHECK(ctos_ssh_native_jump_relay_destroy(relay) == 0 && close(pair[1]) == 0);
    cleanup_fixture(&fixture, 1);
    CHECK(ctos_ssh_role_bootstrap_close(next) == 0);
}

static void test_jump_relay_peer_backpressure(void) {
    test_jump_relay_peer_backpressure_case(0);
    test_jump_relay_peer_backpressure_case(1);
}

static void test_jump_relay_remote_close(void) {
    for (int queued = 0; queued <= 1; queued++) {
        struct fixture fixture;
        struct ctos_ssh_native_jump_relay *relay = NULL;
        int pair[2]; unsigned char output;
        setup_jump_fixture(&fixture, 1);
        CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
        drive_password(&fixture, CTOS_SSH_PASSWORD_AUTH_SUCCESS, 0);
        ctos_ssh_role_token next = jump_next_role(&fixture, 0);
        CHECK(ctos_ssh_native_client_begin_jump(fixture.client, next) == 0);
        drive_jump_phase(&fixture, CTOS_SSH_JUMP_OPEN);
        CHECK(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, pair) == 0);
        CHECK(ctos_ssh_native_jump_relay_open(fixture.client, pair[0], &relay) == 0);
        if (queued) CHECK(send(pair[1], "queued", 6U, MSG_NOSIGNAL) == 6);
        peer_channel_packet(&fixture, SSH2_MSG_CHANNEL_CLOSE); CHECK(sshpkt_send(fixture.server) == 0);
        drive_jump_phase(&fixture, CTOS_SSH_JUMP_CLOSED);
        if (queued) CHECK(ctos_ssh_native_jump_relay_pump(relay) != 0 && errno == EPIPE);
        else {
            CHECK(ctos_ssh_native_jump_relay_pump(relay) == 0);
            CHECK(recv(pair[1], &output, 1U, MSG_DONTWAIT) == 0);
            CHECK(send(pair[1], "x", 1U, MSG_NOSIGNAL) < 0 && errno == EPIPE);
        }
        CHECK(ctos_ssh_native_jump_relay_close(relay) == 0);
        CHECK(ctos_ssh_native_jump_relay_destroy(relay) == 0 && close(pair[1]) == 0);
        cleanup_fixture(&fixture, 1);
        CHECK(ctos_ssh_role_bootstrap_close(next) == 0);
    }
}

struct jump_bridge_fixture {
    int server_fd;
    struct ctos_ssh_native_jump_relay *relay;
    size_t forwarded_to_jump;
};

static void jump_bridge_turn(struct fixture *jump, struct fixture *target,
    struct jump_bridge_fixture *bridge, int step_target) {
    unsigned char bytes[32768];
    if (step_target) CHECK(ctos_ssh_native_client_step(target->client,
        &(struct ctos_ssh_native_client_step){0}) == 0);
    CHECK(ctos_ssh_native_jump_relay_pump(bridge->relay) == 0);
    CHECK(shell_turn(jump) == 0);
    CHECK(pump_synthetic_server(target, 64U, NULL, NULL) == 0);
    ssize_t n = recv(bridge->server_fd, bytes, sizeof(bytes), MSG_DONTWAIT);
    if (n > 0) {
        bridge->forwarded_to_jump += (size_t)n;
        peer_channel_data(jump, 0, bytes, (size_t)n);
    }
    else CHECK(n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR));
    CHECK(shell_turn(jump) == 0);
    CHECK(ctos_ssh_native_jump_relay_pump(bridge->relay) == 0);
}

static void close_parent_during_child_host(void *context) {
    struct fixture *jump = context;
    /* Same-thread C fixture injection, not JNI re-entry: the production JNI
     * session lock is held across callbacks and cannot be re-entered here. */
    peer_channel_packet(jump, SSH2_MSG_CHANNEL_EOF);
    CHECK(sshpkt_send(jump->server) == 0);
    peer_channel_packet(jump, SSH2_MSG_CHANNEL_CLOSE);
    CHECK(sshpkt_send(jump->server) == 0);
    for (unsigned int turn = 0; turn < 200000U; turn++) {
        CHECK(shell_turn(jump) == 0);
        if (jump_status(jump).phase == CTOS_SSH_JUMP_CLOSED) return;
    }
    CHECK(0);
}

static void eof_parent_during_child_kex(void *context) {
    struct fixture *jump = context;
    peer_channel_packet(jump, SSH2_MSG_CHANNEL_EOF);
    CHECK(sshpkt_send(jump->server) == 0);
    for (unsigned int turn = 0; turn < 200000U; turn++) {
        CHECK(shell_turn(jump) == 0);
        if (jump_status(jump).remote_eof) return;
    }
    CHECK(0);
}

enum child_case {
    CHILD_ROUNDTRIP, CHILD_QUEUED_CANCEL, CHILD_HOST_CANCEL, CHILD_HOST_VIEW_REVOKE,
    CHILD_HOST_ROLE_CLOSE, CHILD_CREDENTIAL_CANCEL, CHILD_OUTPUT_CANCEL, CHILD_OWN_CANCEL,
    CHILD_DIRECT_CALLBACK, CHILD_DIRECT_HOST_VERIFY, CHILD_HOST_NEW_APPROVED,
    CHILD_HOST_CHANGED_APPROVED, CHILD_HOST_PARENT_CLOSED,
    CHILD_HOST_CHANGED, CHILD_HOST_REVOKED, CHILD_LOCAL_EOF, CHILD_REMOTE_CLOSE,
    CHILD_KEYBOARD_CANCEL, CHILD_OWN_VIEW_REVOKE,
    CHILD_FRESH_KNOWN, CHILD_FRESH_NEW, CHILD_FRESH_CHANGED, CHILD_FRESH_PARENT_CANCEL,
    CHILD_PROVISIONAL_NEW, CHILD_PROVISIONAL_CANDIDATE_FAIL, CHILD_PROVISIONAL_REVIEW_DENY,
    CHILD_PROVISIONAL_REVIEW_CANCEL, CHILD_PROVISIONAL_KEX_CANCEL, CHILD_PROVISIONAL_KNOWN,
    CHILD_PROVISIONAL_CHANGED, CHILD_PROVISIONAL_REVOKED, CHILD_PROVISIONAL_DIRECT,
    CHILD_PROVISIONAL_BACKPRESSURE, CHILD_PROVISIONAL_CANCEL_PENDING,
    CHILD_PROVISIONAL_REMOTE_EOF, CHILD_PROVISIONAL_REMOTE_CLOSE, CHILD_PROVISIONAL_LOCAL_EOF,
    CHILD_PROVISIONAL_BAD_SIGNATURE, CHILD_PROVISIONAL_CANDIDATE_ABORT_RETRY,
    CHILD_PROVISIONAL_LOCAL_UNPUMPED_EOF,
    CHILD_PROVISIONAL_PARENT_CLOSED_CANDIDATE, CHILD_PROVISIONAL_PARENT_CLOSED_REVIEW,
    CHILD_PROVISIONAL_PARENT_CLOSED_KEX,
    CHILD_PROVISIONAL_VIEW_REVOKE, CHILD_PROVISIONAL_PARENT_VIEW_REVOKE,
    CHILD_PROVISIONAL_CALLBACK_CANCEL, CHILD_PROVISIONAL_PREKEX_EOF,
    CHILD_PROVISIONAL_PREKEX_GUARD_INVALID, CHILD_PROVISIONAL_PREKEX_GUARD_TIMEOUT,
    CHILD_PROVISIONAL_GUARDED_KEX,
    CHILD_PROVISIONAL_POLL_IDLE, CHILD_PROVISIONAL_POLL_EXT_INFO,
    CHILD_PROVISIONAL_POLL_REKEY, CHILD_PROVISIONAL_POLL_USERAUTH_SUCCESS,
    CHILD_PROVISIONAL_POLL_DISCONNECT, CHILD_PROVISIONAL_POLL_PARTIAL,
    CHILD_PROVISIONAL_POLL_BUFFERED, CHILD_PROVISIONAL_POLL_FIRST_BLOCK, CHILD_PROVISIONAL_POLL_AEAD_PARTIAL,
    CHILD_PROVISIONAL_POLL_BACKPRESSURE, CHILD_PROVISIONAL_POLL_PARENT_PENDING, CHILD_PROVISIONAL_POLL_RELAY_PENDING,
    CHILD_PROVISIONAL_POLL_EOF, CHILD_PROVISIONAL_POLL_PEER_EOF, CHILD_PROVISIONAL_POLL_PARENT_EOF,
    CHILD_PROVISIONAL_POLL_CHILD_SOCKET_ERROR, CHILD_PROVISIONAL_POLL_PARENT_SOCKET_ERROR,
    CHILD_PROVISIONAL_POLL_CANCEL,
    CHILD_PROVISIONAL_POLL_REVOKE, CHILD_PROVISIONAL_POLL_INVALIDATE, CHILD_PROVISIONAL_POLL_TIMEOUT,
    CHILD_PROVISIONAL_EXT_IDLE, CHILD_PROVISIONAL_EXT_VALID, CHILD_PROVISIONAL_EXT_FRAGMENTED,
    CHILD_PROVISIONAL_EXT_CHACHA_FRAGMENTED, CHILD_PROVISIONAL_EXT_DUPLICATE,
    CHILD_PROVISIONAL_EXT_BACKLOG, CHILD_PROVISIONAL_EXT_INVALIDATE_AFTER_READ,
    CHILD_PROVISIONAL_EXT_INVALIDATE_AFTER_PARSE,
    CHILD_PROVISIONAL_EXT_TRUNCATED, CHILD_PROVISIONAL_EXT_CTR, CHILD_PROVISIONAL_EXT_CANCEL,
    CHILD_PROVISIONAL_EXT_REVOKE, CHILD_PROVISIONAL_EXT_INVALIDATE, CHILD_PROVISIONAL_EXT_TIMEOUT,
    CHILD_PROVISIONAL_EXT_EOF,
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
    CHILD_KEX_AUTH_INVALID, CHILD_KEX_SHELL_INVALID, CHILD_VIEW_CLOSE_FAILURE,
#endif
    CHILD_CASE_COUNT
};

static void keyboard_challenge_packet(struct fixture *, unsigned int, unsigned int);

struct provisional_wrong_thread {
    struct ctos_ssh_native_client *client;
    int result, error, ready;
};

static void *provisional_wrong_owner(void *context) {
    struct provisional_wrong_thread *attempt = context;
    attempt->ready = 99;
    attempt->result = ctos_ssh_native_client_provisional_new_ready(attempt->client, &attempt->ready);
    attempt->error = errno;
    return NULL;
}

static void *provisional_poll_wrong_owner(void *context) {
    struct provisional_wrong_thread *attempt = context;
    attempt->ready = 99;
    attempt->result = ctos_ssh_native_client_provisional_new_poll_idle(attempt->client, &attempt->ready);
    attempt->error = errno;
    return NULL;
}

static void *provisional_ext_wrong_owner(void *context) {
    struct provisional_wrong_thread *attempt = context;
    attempt->ready = 99;
    attempt->result = ctos_ssh_native_client_provisional_new_poll_ext(attempt->client, &attempt->ready);
    attempt->error = errno;
    return NULL;
}

static void test_provisional_poll(enum child_case mode, struct fixture *jump, struct fixture *target,
    struct jump_bridge_fixture *bridge, uint64_t hop_deadline) {
    int idle = 0;
    for (unsigned int turn = 0; turn < 20000U; turn++) {
        jump_bridge_turn(jump, target, bridge, 0);
        CHECK(ctos_ssh_native_client_provisional_new_poll_idle(target->client, &idle) == 0);
        if (idle) break;
        CHECK(turn + 1U < 20000U);
    }
    CHECK(idle && target->callback.credential_calls == 0 && target->callback.credential_bytes_calls == 0
        && target->callback.selected_calls == 0 && target->service_requests == 0 && target->password_requests == 0);

    if (mode == CHILD_PROVISIONAL_POLL_RELAY_PENDING) {
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
        CHECK(ctos_ssh_native_client_test_provisional_fill_peer(target->client) == 0);
#else
        CHECK(0);
#endif
    }

    if (mode == CHILD_PROVISIONAL_POLL_IDLE) {
        struct provisional_wrong_thread wrong = {.client = target->client};
        pthread_t thread;
        CHECK(pthread_create(&thread, NULL, provisional_poll_wrong_owner, &wrong) == 0);
        CHECK(pthread_join(thread, NULL) == 0);
        CHECK(wrong.result != 0 && wrong.error == EPERM && !wrong.ready);
        CHECK(ctos_ssh_native_client_provisional_new_poll_idle(NULL, &idle) != 0 && !idle && errno == EINVAL);
        CHECK(ctos_ssh_native_client_provisional_new_poll_idle(target->client, NULL) != 0 && errno == EINVAL);
        for (unsigned int turn = 0; turn < 100U; turn++) {
            jump_bridge_turn(jump, target, bridge, 0);
            CHECK(ctos_ssh_native_client_provisional_new_poll_idle(target->client, &idle) == 0 && idle);
        }
        return;
    }
    if (mode == CHILD_PROVISIONAL_POLL_BACKPRESSURE) {
        unsigned char pending = 0x5a;
        CHECK(send(target->managed_child_fd, &pending, 1U, MSG_DONTWAIT | MSG_NOSIGNAL) == 1);
        CHECK(ctos_ssh_native_client_provisional_new_poll_idle(target->client, &idle) == 0 && !idle);
        return;
    }
    if (mode == CHILD_PROVISIONAL_POLL_CANCEL || mode == CHILD_PROVISIONAL_POLL_REVOKE
            || mode == CHILD_PROVISIONAL_POLL_INVALIDATE
            || mode == CHILD_PROVISIONAL_POLL_TIMEOUT || mode == CHILD_PROVISIONAL_POLL_EOF
            || mode == CHILD_PROVISIONAL_POLL_PEER_EOF || mode == CHILD_PROVISIONAL_POLL_PARENT_EOF
            || mode == CHILD_PROVISIONAL_POLL_CHILD_SOCKET_ERROR || mode == CHILD_PROVISIONAL_POLL_PARENT_SOCKET_ERROR) {
        if (mode == CHILD_PROVISIONAL_POLL_CANCEL) CHECK(ctos_ssh_callback_cancel(jump->callback_token) == 0);
        else if (mode == CHILD_PROVISIONAL_POLL_REVOKE) target->view->valid = 0;
        else if (mode == CHILD_PROVISIONAL_POLL_EOF) CHECK(shutdown(target->managed_child_fd, SHUT_RD) == 0);
        else if (mode == CHILD_PROVISIONAL_POLL_PEER_EOF) {
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
            CHECK(ctos_ssh_native_client_test_provisional_peer_eof(target->client) == 0);
#else
            CHECK(0);
#endif
        } else if (mode == CHILD_PROVISIONAL_POLL_PARENT_EOF) CHECK(shutdown(jump->pair[1], SHUT_WR) == 0);
        else if (mode == CHILD_PROVISIONAL_POLL_CHILD_SOCKET_ERROR || mode == CHILD_PROVISIONAL_POLL_PARENT_SOCKET_ERROR) {
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
            CHECK(ctos_ssh_native_client_test_provisional_fail_peek(
                mode == CHILD_PROVISIONAL_POLL_CHILD_SOCKET_ERROR ? 1 : 2) == 0);
#else
            CHECK(0);
#endif
        }
        else if (mode == CHILD_PROVISIONAL_POLL_INVALIDATE) {
            ctos_ssh_effect_invalidate_begin();
            ctos_ssh_effect_invalidate_end();
        }
        else wait_until_deadline_attempts(hop_deadline, 12000U);
        idle = 99;
        CHECK(ctos_ssh_native_client_provisional_new_poll_idle(target->client, &idle) != 0 && !idle);
        if (mode == CHILD_PROVISIONAL_POLL_EOF || mode == CHILD_PROVISIONAL_POLL_PEER_EOF
                || mode == CHILD_PROVISIONAL_POLL_PARENT_EOF) CHECK(errno == EPIPE);
        if (mode == CHILD_PROVISIONAL_POLL_CHILD_SOCKET_ERROR || mode == CHILD_PROVISIONAL_POLL_PARENT_SOCKET_ERROR)
            CHECK(errno == ECONNRESET);
        if (mode == CHILD_PROVISIONAL_POLL_INVALIDATE) CHECK(errno == ECANCELED);
        if (mode == CHILD_PROVISIONAL_POLL_TIMEOUT) CHECK(errno == ETIMEDOUT);
        target->view->valid = 1;
        CHECK(ctos_ssh_native_client_provisional_new_poll_idle(target->client, &idle) != 0 && !idle);
        return;
    }

    int type = SSH2_MSG_EXT_INFO;
    if (mode == CHILD_PROVISIONAL_POLL_REKEY) type = SSH2_MSG_KEXINIT;
    else if (mode == CHILD_PROVISIONAL_POLL_USERAUTH_SUCCESS) type = SSH2_MSG_USERAUTH_SUCCESS;
    else if (mode == CHILD_PROVISIONAL_POLL_DISCONNECT) type = SSH2_MSG_DISCONNECT;
    if (type == SSH2_MSG_KEXINIT) {
        CHECK(kex_start_rekex(target->server) == 0);
    } else {
        CHECK(sshpkt_start(target->server, type) == 0);
        if (type == SSH2_MSG_EXT_INFO) CHECK(sshpkt_put_u32(target->server, 0) == 0);
        if (type == SSH2_MSG_DISCONNECT) {
            CHECK(sshpkt_put_u32(target->server, 11) == 0);
            CHECK(sshpkt_put_cstring(target->server, "closed") == 0);
            CHECK(sshpkt_put_cstring(target->server, "") == 0);
        }
        CHECK(sshpkt_send(target->server) == 0);
    }
    size_t length = 0;
    const unsigned char *encrypted = ssh_output_ptr(target->server, &length);
    CHECK(encrypted != NULL && length > 16U);
    if (mode == CHILD_PROVISIONAL_POLL_BUFFERED || mode == CHILD_PROVISIONAL_POLL_FIRST_BLOCK
            || mode == CHILD_PROVISIONAL_POLL_AEAD_PARTIAL) {
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
        int parse = mode == CHILD_PROVISIONAL_POLL_FIRST_BLOCK ? 1
            : mode == CHILD_PROVISIONAL_POLL_AEAD_PARTIAL ? 2 : 0;
        size_t fragment = parse == 1 ? 16U : parse == 2 ? 8U : 1U;
        CHECK(ctos_ssh_native_client_test_provisional_input(target->client, encrypted, fragment, parse) == 0);
#else
        CHECK(0);
#endif
    } else {
        size_t sent = mode == CHILD_PROVISIONAL_POLL_PARTIAL ? 1U : length;
        peer_channel_data(jump, 0, encrypted, sent);
        CHECK(ssh_output_consume(target->server, length) == 0);
        if (mode == CHILD_PROVISIONAL_POLL_PARENT_PENDING) {
            int queued = 0;
            for (unsigned int turn = 0; turn < 20000U && queued == 0; turn++) {
                CHECK(pump_synthetic_server(jump, 64U, NULL, NULL) == 0);
                CHECK(ioctl(jump->pair[0], FIONREAD, &queued) == 0);
            }
            CHECK(queued > 0);
            CHECK(ctos_ssh_native_client_provisional_new_poll_idle(target->client, &idle) == 0 && !idle);
            for (unsigned int turn = 0; turn < 20000U && jump_status(jump).buffered_bytes == 0; turn++)
                CHECK(shell_turn(jump) == 0);
            CHECK(jump_status(jump).buffered_bytes != 0);
        } else if (mode == CHILD_PROVISIONAL_POLL_RELAY_PENDING) {
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
            for (unsigned int turn = 0; turn < 20000U
                    && ctos_ssh_native_client_test_provisional_peer_buffered(target->client) == 0; turn++) {
                CHECK(shell_turn(jump) == 0);
                CHECK(ctos_ssh_native_jump_relay_pump(bridge->relay) == 0);
            }
            CHECK(ctos_ssh_native_client_test_provisional_peer_buffered(target->client) > 0);
            CHECK(jump_status(jump).buffered_bytes == 0);
            unsigned char discarded[4096];
            ssize_t count;
            do {
                count = recv(target->managed_child_fd, discarded, sizeof(discarded), MSG_DONTWAIT);
                CHECK(count > 0 || (count < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)));
            } while (count > 0);
            int queued = -1;
            CHECK(ioctl(target->managed_child_fd, FIONREAD, &queued) == 0 && queued == 0);
#else
            CHECK(0);
#endif
        } else {
            int queued = 0;
            for (unsigned int turn = 0; turn < 20000U && queued == 0; turn++) {
                CHECK(shell_turn(jump) == 0);
                CHECK(ctos_ssh_native_jump_relay_pump(bridge->relay) == 0);
                CHECK(ioctl(target->managed_child_fd, FIONREAD, &queued) == 0);
            }
            CHECK(queued > 0);
        }
    }
    idle = 99;
    CHECK(ctos_ssh_native_client_provisional_new_poll_idle(target->client, &idle) != 0
        && !idle && errno == EPROTO);
    CHECK(target->callback.candidate_abort_calls == 1 && target->callback.kex_complete_calls == 0
        && target->callback.selected_calls == 0 && target->callback.credential_calls == 0
        && target->service_requests == 0 && target->password_requests == 0);
    CHECK(ctos_ssh_native_client_provisional_new_poll_idle(target->client, &idle) != 0
        && !idle && errno == EALREADY);
}

static void deliver_provisional_extension(struct fixture *jump, struct fixture *target,
    struct jump_bridge_fixture *bridge, const unsigned char *bytes, size_t length) {
    int queued = 0;
    CHECK(length > 0 && length <= 32768U);
    peer_channel_data(jump, 0, bytes, length);
    for (unsigned int turn = 0; turn < 20000U && (size_t)queued < length; turn++) {
        CHECK(shell_turn(jump) == 0);
        CHECK(ctos_ssh_native_jump_relay_pump(bridge->relay) == 0);
        CHECK(ioctl(target->managed_child_fd, FIONREAD, &queued) == 0 && queued >= 0);
    }
    CHECK((size_t)queued >= length);
}

static void queue_provisional_extension(struct fixture *target) {
    CHECK(sshpkt_start(target->server, SSH2_MSG_EXT_INFO) == 0);
    CHECK(sshpkt_put_u32(target->server, 2U) == 0);
    CHECK(sshpkt_put_cstring(target->server, "server-sig-algs") == 0);
    CHECK(sshpkt_put_cstring(target->server, "ssh-ed25519,rsa-sha2-512") == 0);
    CHECK(sshpkt_put_cstring(target->server, "ping@openssh.com") == 0);
    CHECK(sshpkt_put_cstring(target->server, "0") == 0);
    CHECK(sshpkt_send(target->server) == 0);
}

#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
static void invalidate_provisional_generation(void *context) {
    unsigned int *fired = context;
    (*fired)++;
    ctos_ssh_effect_invalidate_begin();
    ctos_ssh_effect_invalidate_end();
}
#endif

static void test_provisional_ext(enum child_case mode, struct fixture *jump, struct fixture *target,
    struct jump_bridge_fixture *bridge, uint64_t hop_deadline, uint32_t generation) {
    int idle = 0;
    for (unsigned int turn = 0; turn < 20000U; turn++) {
        jump_bridge_turn(jump, target, bridge, 0);
        CHECK(ctos_ssh_native_client_provisional_new_poll_idle(target->client, &idle) == 0);
        if (idle) break;
        CHECK(turn + 1U < 20000U);
    }
    CHECK(idle && target->callback.provisional_kex_calls == 1
        && target->callback.credential_calls == 0 && target->service_requests == 0);

    if (mode == CHILD_PROVISIONAL_EXT_CANCEL || mode == CHILD_PROVISIONAL_EXT_REVOKE
        || mode == CHILD_PROVISIONAL_EXT_INVALIDATE || mode == CHILD_PROVISIONAL_EXT_TIMEOUT
        || mode == CHILD_PROVISIONAL_EXT_EOF || mode == CHILD_PROVISIONAL_EXT_CTR) {
        if (mode == CHILD_PROVISIONAL_EXT_CANCEL) CHECK(ctos_ssh_callback_cancel(jump->callback_token) == 0);
        else if (mode == CHILD_PROVISIONAL_EXT_REVOKE) target->view->valid = 0;
        else if (mode == CHILD_PROVISIONAL_EXT_INVALIDATE) {
            ctos_ssh_effect_invalidate_begin();
            ctos_ssh_effect_invalidate_end();
        } else if (mode == CHILD_PROVISIONAL_EXT_TIMEOUT) wait_until_deadline_attempts(hop_deadline, 12000U);
        else if (mode == CHILD_PROVISIONAL_EXT_EOF) {
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
            CHECK(ctos_ssh_native_client_test_provisional_peer_eof(target->client) == 0);
#else
            CHECK(0);
#endif
        }
        idle = 99;
        CHECK(ctos_ssh_native_client_provisional_new_poll_ext(target->client, &idle) != 0 && !idle);
        if (mode == CHILD_PROVISIONAL_EXT_CTR) CHECK(errno == EPROTO);
        if (mode == CHILD_PROVISIONAL_EXT_EOF) CHECK(errno == EPIPE);
        if (mode == CHILD_PROVISIONAL_EXT_TIMEOUT) CHECK(errno == ETIMEDOUT);
        target->view->valid = 1;
        CHECK(ctos_ssh_native_client_provisional_new_poll_ext(target->client, &idle) != 0 && !idle);
        return;
    }

    CHECK(ctos_ssh_native_client_provisional_new_poll_ext(target->client, &idle) == 0 && idle);
    if (mode == CHILD_PROVISIONAL_EXT_IDLE) {
        struct provisional_wrong_thread wrong = {.client = target->client};
        pthread_t thread;
        CHECK(pthread_create(&thread, NULL, provisional_ext_wrong_owner, &wrong) == 0);
        CHECK(pthread_join(thread, NULL) == 0);
        CHECK(wrong.result != 0 && wrong.error == EPERM && !wrong.ready);
        CHECK(ctos_ssh_native_client_provisional_new_poll_ext(NULL, &idle) != 0 && !idle && errno == EINVAL);
        CHECK(ctos_ssh_native_client_provisional_new_poll_ext(target->client, NULL) != 0 && errno == EINVAL);
        return;
    }
    queue_provisional_extension(target);
    size_t first_packet_length = 0;
    CHECK(ssh_output_ptr(target->server, &first_packet_length) != NULL && first_packet_length > 16U);
    if (mode == CHILD_PROVISIONAL_EXT_DUPLICATE || mode == CHILD_PROVISIONAL_EXT_BACKLOG)
        queue_provisional_extension(target);
    size_t length = 0;
    const unsigned char *packet = ssh_output_ptr(target->server, &length);
    CHECK(packet != NULL && length > 16U && length < 32768U);
    unsigned char *copy = malloc(length);
    CHECK(copy != NULL);
    memcpy(copy, packet, length);
    CHECK(ssh_output_consume(target->server, length) == 0);
    size_t first = (mode == CHILD_PROVISIONAL_EXT_FRAGMENTED || mode == CHILD_PROVISIONAL_EXT_TRUNCATED
        || mode == CHILD_PROVISIONAL_EXT_CHACHA_FRAGMENTED || mode == CHILD_PROVISIONAL_EXT_BACKLOG)
        ? length - 1U : length;
    if (mode == CHILD_PROVISIONAL_EXT_BACKLOG) CHECK(first > first_packet_length);
    deliver_provisional_extension(jump, target, bridge, copy, first);
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
    unsigned int invalidation_fired = 0;
    if (mode == CHILD_PROVISIONAL_EXT_INVALIDATE_AFTER_READ
        || mode == CHILD_PROVISIONAL_EXT_INVALIDATE_AFTER_PARSE) {
        CHECK(ctos_ssh_native_client_test_provisional_hook(target->client,
            mode == CHILD_PROVISIONAL_EXT_INVALIDATE_AFTER_READ ? 1 : 2,
            invalidate_provisional_generation, &invalidation_fired) == 0);
        CHECK(ctos_ssh_native_client_provisional_new_poll_ext(target->client, &idle) != 0 && !idle);
        CHECK(invalidation_fired == 1U && target->callback.credential_calls == 0
            && target->service_requests == 0 && target->password_requests == 0);
        free(copy);
        return;
    }
#else
    CHECK(mode != CHILD_PROVISIONAL_EXT_INVALIDATE_AFTER_READ
        && mode != CHILD_PROVISIONAL_EXT_INVALIDATE_AFTER_PARSE);
#endif
    CHECK(ctos_ssh_native_client_provisional_new_poll_ext(target->client, &idle) == 0);
    if (mode == CHILD_PROVISIONAL_EXT_DUPLICATE || mode == CHILD_PROVISIONAL_EXT_BACKLOG) {
        CHECK(!idle);
        if (mode == CHILD_PROVISIONAL_EXT_BACKLOG) {
            CHECK(ctos_ssh_native_client_provisional_new_poll_ext(target->client, &idle) == 0 && !idle);
            deliver_provisional_extension(jump, target, bridge, copy + first, length - first);
        }
        CHECK(ctos_ssh_native_client_provisional_new_poll_ext(target->client, &idle) != 0
            && !idle && errno == EPROTO);
        CHECK(target->callback.candidate_abort_calls == 1 && target->callback.kex_complete_calls == 0
            && target->callback.selected_calls == 0 && target->callback.credential_calls == 0
            && target->service_requests == 0 && target->password_requests == 0);
        free(copy);
        return;
    }
    if (first != length) {
        CHECK(!idle);
        if (mode == CHILD_PROVISIONAL_EXT_FRAGMENTED || mode == CHILD_PROVISIONAL_EXT_CHACHA_FRAGMENTED) {
            deliver_provisional_extension(jump, target, bridge, copy + first, length - first);
            CHECK(ctos_ssh_native_client_provisional_new_poll_ext(target->client, &idle) == 0);
        }
    }
    if (mode != CHILD_PROVISIONAL_EXT_TRUNCATED) {
        for (unsigned int turn = 0; turn < 20000U && !idle; turn++) {
            jump_bridge_turn(jump, target, bridge, 0);
            CHECK(ctos_ssh_native_client_provisional_new_poll_ext(target->client, &idle) == 0);
        }
        CHECK(idle);
    } else CHECK(!idle);
    CHECK(!ctos_ssh_native_client_complete(target->client));
    CHECK(ctos_ssh_native_client_begin_userauth_service_guarded(target->client, generation) != 0
        && errno == EACCES);
    CHECK(target->callback.kex_complete_calls == 0 && target->callback.selected_calls == 0
        && target->callback.credential_calls == 0 && target->callback.credential_bytes_calls == 0
        && target->service_requests == 0 && target->password_requests == 0);
    free(copy);
}

static void test_jump_nested_ssh(enum child_case mode) {
    struct fixture jump, target;
    struct jump_bridge_fixture bridge = {0};
    const int poll_mode = mode >= CHILD_PROVISIONAL_POLL_IDLE && mode <= CHILD_PROVISIONAL_POLL_TIMEOUT;
    const int ext_mode = mode >= CHILD_PROVISIONAL_EXT_IDLE && mode <= CHILD_PROVISIONAL_EXT_EOF;
    const int guarded = mode >= CHILD_PROVISIONAL_PREKEX_GUARD_INVALID
        && mode <= CHILD_PROVISIONAL_EXT_EOF;
    uint64_t hop_deadline = 0;
    uint32_t generation = 0;
    int server_pair[2];
    child_test_case = (int)mode;
    setup_jump_fixture(&jump, 1);
    CHECK(ctos_ssh_native_client_begin_password_auth(jump.client) == 0);
    drive_password(&jump, CTOS_SSH_PASSWORD_AUTH_SUCCESS, 0);
    ctos_ssh_role_token next = jump_next_role(&jump, 6);
    CHECK(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, server_pair) == 0);
    bridge.server_fd = server_pair[0]; jump.jump_bridge_enabled = 1; jump.jump_bridge_fd = bridge.server_fd;
    jump.shell_window = 65536U; jump.shell_maxpacket = 32768U;
    if (guarded) {
        CHECK(ctos_ssh_effect_snapshot(&generation) == 0 && generation != 0);
        hop_deadline = deadline_after(10000U);
        CHECK(hop_deadline < jump.deadline);
        CHECK(ctos_ssh_native_client_begin_jump_guarded(jump.client, next, hop_deadline, generation) == 0);
    } else CHECK(ctos_ssh_native_client_begin_jump(jump.client, next) == 0);
    drive_jump_phase(&jump, CTOS_SSH_JUMP_OPEN);

    memset(&target, 0, sizeof(target));
    target.pair[0] = target.pair[1] = -1; target.callback.release_fd = -1;
    target.callback.enable_changed = mode == CHILD_HOST_CHANGED_APPROVED || mode == CHILD_FRESH_CHANGED;
    target.callback.enable_provisional = mode >= CHILD_PROVISIONAL_NEW && mode <= CHILD_PROVISIONAL_EXT_EOF;
    target.callback.fail_provisional_candidate = mode == CHILD_PROVISIONAL_CANDIDATE_FAIL
        || mode == CHILD_PROVISIONAL_CANDIDATE_ABORT_RETRY;
    target.callback.candidate_abort_failures_remaining = mode == CHILD_PROVISIONAL_CANDIDATE_ABORT_RETRY ? 100U : 0U;
    target.callback.deny_provisional_review = mode == CHILD_PROVISIONAL_REVIEW_DENY;
    target.callback.cancel_provisional_review = mode == CHILD_PROVISIONAL_REVIEW_CANCEL;
    target.callback.cancel_provisional_kex = mode == CHILD_PROVISIONAL_KEX_CANCEL;
    target.callback.close_provisional_candidate = mode == CHILD_PROVISIONAL_PARENT_CLOSED_CANDIDATE;
    target.callback.close_provisional_review = mode == CHILD_PROVISIONAL_PARENT_CLOSED_REVIEW;
    target.callback.close_provisional_kex = mode == CHILD_PROVISIONAL_PARENT_CLOSED_KEX;
    target.password_expected = mode != CHILD_KEYBOARD_CANCEL;
    target.keyboard_expected = mode == CHILD_KEYBOARD_CANCEL;
    target.password_methods = (const unsigned char *)"publickey,password";
    target.password_methods_length = sizeof("publickey,password") - 1U;
    target.password_text_length = 3U; target.password_language_length = 2U;
    CHECK(sshkey_generate(KEY_ED25519, 0, &target.private_key) == 0);
    CHECK(sshkey_from_private(target.private_key, &target.public_key) == 0);
    target.host_key = target.public_key;
    CHECK(!sshkey_equal(jump.public_key, target.public_key));
    fixture_existing_role = next;
    fixture_jump_parent = jump.client;
    fixture_parent_context = &jump;
    fixture_root_context = &jump;
    fixture_check_borrow_transaction = mode == CHILD_ROUNDTRIP;
    fixture_fresh_host_mode = mode == CHILD_FRESH_KNOWN || mode == CHILD_FRESH_PARENT_CANCEL
            || mode == CHILD_PROVISIONAL_KNOWN ? 1
        : mode == CHILD_FRESH_NEW || (mode >= CHILD_PROVISIONAL_NEW && mode <= CHILD_PROVISIONAL_KEX_CANCEL)
            || mode == CHILD_PROVISIONAL_DIRECT || mode == CHILD_PROVISIONAL_BACKPRESSURE
            || mode == CHILD_PROVISIONAL_CANCEL_PENDING || mode == CHILD_PROVISIONAL_REMOTE_EOF
            || mode == CHILD_PROVISIONAL_REMOTE_CLOSE || mode == CHILD_PROVISIONAL_LOCAL_EOF
            || mode == CHILD_PROVISIONAL_BAD_SIGNATURE || mode == CHILD_PROVISIONAL_CANDIDATE_ABORT_RETRY
            || mode == CHILD_PROVISIONAL_LOCAL_UNPUMPED_EOF
            || (mode >= CHILD_PROVISIONAL_PARENT_CLOSED_CANDIDATE && mode <= CHILD_PROVISIONAL_EXT_EOF) ? 2
        : mode == CHILD_FRESH_CHANGED || mode == CHILD_PROVISIONAL_CHANGED ? 3 : 0;
    if (guarded) fixture_step_budget = 4096U;
    finish_fixture_setup_selected_mode(&target, 60000U,
        (mode == CHILD_HOST_CHANGED || mode == CHILD_HOST_CHANGED_APPROVED) ? jump.public_key : NULL,
        mode == CHILD_HOST_REVOKED || mode == CHILD_PROVISIONAL_REVOKED ? "@revoked " : NULL,
        mode == CHILD_HOST_NEW_APPROVED || mode == CHILD_FRESH_KNOWN || mode == CHILD_FRESH_PARENT_CANCEL,
        0, mode == CHILD_KEYBOARD_CANCEL ? 3U : 1U, 0,
        mode == CHILD_HOST_CHANGED_APPROVED || mode == CHILD_PROVISIONAL_REVOKED || fixture_fresh_host_mode != 0
            ? CTOS_SSH_HOST_KEY_MANAGED : CTOS_SSH_HOST_KEY_PINNED);
    if (guarded) fixture_step_budget = TEST_STEP_BUDGET;
    fixture_fresh_host_mode = 0;
    fixture_existing_role = 0;
    fixture_jump_parent = NULL;
    fixture_check_borrow_transaction = 0;
    fixture_parent_context = NULL;
    fixture_root_context = NULL;
    target.callback.selected_kind = CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD;
    memcpy(target.callback.expected_selection_receipt, target.selection_receipt, 32U);
    target.callback.selected_view = target.view; target.callback.selected_callback = target.callback_token;
    target.callback.selected_role = target.role; target.callback.selected_deadline = target.deadline;
    if (mode == CHILD_ROUNDTRIP || mode == CHILD_HOST_CANCEL
        || mode == CHILD_HOST_VIEW_REVOKE || mode == CHILD_HOST_ROLE_CLOSE
        || mode == CHILD_CREDENTIAL_CANCEL || mode == CHILD_HOST_NEW_APPROVED
        || mode == CHILD_HOST_CHANGED_APPROVED || mode == CHILD_HOST_PARENT_CLOSED
        || mode == CHILD_FRESH_KNOWN || mode == CHILD_FRESH_NEW || mode == CHILD_FRESH_CHANGED
        || mode == CHILD_FRESH_PARENT_CANCEL || target.callback.enable_provisional) {
        target.callback.scope_probe = 1;
        target.callback.scope_token = target.callback_token;
        target.callback.scope_other = jump.callback_token;
        CHECK(ctos_ssh_native_client_callback_ancestor_proofs_check(target.callback_token,
            target.callback.environment) != 0 && errno == EACCES);
    }
    bridge.relay = fixture_child_relay;
    fixture_child_relay = NULL;
    CHECK(close(target.pair[1]) == 0);
    target.pair[1] = server_pair[1];
    target.suppress_server_ext_info = poll_mode || ext_mode;
    target.force_server_cipher = mode == CHILD_PROVISIONAL_POLL_FIRST_BLOCK ? 1
        : mode == CHILD_PROVISIONAL_POLL_AEAD_PARTIAL ? 2
        : mode == CHILD_PROVISIONAL_EXT_CTR ? 1
        : mode == CHILD_PROVISIONAL_EXT_CHACHA_FRAGMENTED ? 3 : ext_mode ? 2 : 0;
    setup_synthetic_server(&target);
    if (mode == CHILD_PROVISIONAL_BAD_SIGNATURE) {
        original_server_sign = target.server->kex->sign;
        CHECK(original_server_sign != NULL);
        target.server->kex->sign = sign_with_invalid_signature;
    }
    CHECK(target.callback.expected_hop == 1 && target.callback.selected_calls == 0);
    CHECK(ctos_ssh_native_jump_relay_close(bridge.relay) != 0 && errno == EBUSY);
    CHECK(ctos_ssh_native_client_close(jump.client) != 0 && errno == EBUSY);
    if (mode == CHILD_PROVISIONAL_DIRECT) {
        unsigned char body[] = "forged candidate", digest[32] = {1};
        int handoff_started = 99;
        CHECK(ctos_ssh_callback_provisional_new_candidate(target.callback_token, target.callback.environment,
            "native-candidate", "ssh-ed25519", target.callback.expected_fingerprint,
            digest, digest, body, sizeof(body) - 1U, &handoff_started) != 0 && handoff_started == 0);
        for (size_t i = 0; i < sizeof(body) - 1U; i++) CHECK(body[i] == 0);
        CHECK(target.callback.provisional_candidate_calls == 0 && target.callback.provisional_review_calls == 0);
        goto cleanup;
    }
    if (mode == CHILD_DIRECT_CALLBACK) {
        /* A real claimed child token is insufficient without native dispatch. */
        target.callback.scope_probe = 2;
        target.callback.scope_token = target.callback_token;
        target.callback.expected_credential_calls = 1;
        CHECK(ctos_ssh_callback_credential(target.callback_token, target.callback.environment,
            CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD) != 0 && errno == EACCES);
        CHECK(target.callback.scope_selected_checks == 1);
        goto cleanup;
    }
    if (mode == CHILD_DIRECT_HOST_VERIFY) {
        /* A matching PINNED key can use the public verifier, but its host
         * target is not an engine callback and cannot check ancestors. */
        target.callback.scope_probe = 3;
        target.callback.scope_token = target.callback_token;
        CHECK(ctos_ssh_native_client_verify_host_key(target.client,
            target.public_blob, target.public_blob_length) == 0);
        CHECK(target.callback.host_evidence_calls == 1 && target.callback.scope_host_checks == 1);
        goto cleanup;
    }
    if (mode == CHILD_QUEUED_CANCEL) {
        CHECK(ctos_ssh_native_client_step(target.client, &(struct ctos_ssh_native_client_step){0}) == 0);
        for (unsigned int turn = 0; turn < 200000U && bridge.forwarded_to_jump == 0; turn++)
            jump_bridge_turn(&jump, &target, &bridge, 0);
        CHECK(bridge.forwarded_to_jump != 0);
        for (int turn = 0; turn < 20; turn++) jump_bridge_turn(&jump, &target, &bridge, 0);
        int queued = 0;
        CHECK(ioctl(target.managed_child_fd, FIONREAD, &queued) == 0 && queued > 0);
        CHECK(ctos_ssh_callback_cancel(jump.callback_token) == 0);
        unsigned char receipt[32]; memset(receipt, 0xff, sizeof(receipt));
        CHECK(ctos_ssh_native_client_authentication_selection_receipt(target.client, receipt) != 0);
        for (size_t i = 0; i < sizeof(receipt); i++) CHECK(receipt[i] == 0);
        struct ctos_ssh_native_client_step denied = {0};
        CHECK(ctos_ssh_native_client_step(target.client, &denied) != 0);
        CHECK(denied.bytes_read == 0 && denied.packet_type == 0);
        CHECK(target.callback.host_evidence_calls == 0 && target.callback.kex_complete_calls == 0
            && target.callback.selected_calls == 0 && !ctos_ssh_native_client_complete(target.client));
        goto cleanup;
    }
    if (mode == CHILD_HOST_CANCEL || mode == CHILD_FRESH_PARENT_CANCEL) {
        target.callback.host_action = 2;
        target.callback.selected_callback = jump.callback_token;
    }
    if (mode == CHILD_HOST_VIEW_REVOKE) {
        target.callback.host_action = 3;
        target.callback.selected_view = jump.view;
    }
    if (mode == CHILD_HOST_ROLE_CLOSE) {
        target.callback.host_action = 7;
        target.callback.selected_role = jump.role;
    }
    if (mode == CHILD_HOST_PARENT_CLOSED) {
        target.callback.scope_close_parent = close_parent_during_child_host;
        target.callback.scope_close_parent_context = &jump;
    }
    if (mode >= CHILD_PROVISIONAL_PARENT_CLOSED_CANDIDATE && mode <= CHILD_PROVISIONAL_PARENT_CLOSED_KEX) {
        target.callback.scope_close_parent = mode == CHILD_PROVISIONAL_PARENT_CLOSED_KEX
            ? eof_parent_during_child_kex : close_parent_during_child_host;
        target.callback.scope_close_parent_context = &jump;
    }
    if (mode == CHILD_PROVISIONAL_REVIEW_CANCEL || mode == CHILD_PROVISIONAL_KEX_CANCEL)
        target.callback.selected_callback = jump.callback_token;
    int ready = 0;
    for (unsigned int turn = 0; turn < 200000U; turn++) {
        int complete = ctos_ssh_native_client_complete(target.client);
        int provisional = 0;
        if ((mode == CHILD_PROVISIONAL_NEW || mode == CHILD_PROVISIONAL_BACKPRESSURE
                || mode == CHILD_PROVISIONAL_CANCEL_PENDING || mode == CHILD_PROVISIONAL_REMOTE_EOF
                || mode == CHILD_PROVISIONAL_REMOTE_CLOSE || mode == CHILD_PROVISIONAL_LOCAL_EOF
                || mode == CHILD_PROVISIONAL_LOCAL_UNPUMPED_EOF || mode == CHILD_PROVISIONAL_VIEW_REVOKE
                || mode == CHILD_PROVISIONAL_PARENT_VIEW_REVOKE || mode == CHILD_PROVISIONAL_CALLBACK_CANCEL
                || mode == CHILD_PROVISIONAL_GUARDED_KEX || poll_mode || ext_mode)
                && target.callback.provisional_kex_calls == 1) {
            if (mode == CHILD_PROVISIONAL_BACKPRESSURE) {
                unsigned char queued = 0xa5;
                CHECK(send(target.managed_child_fd, &queued, 1, MSG_DONTWAIT | MSG_NOSIGNAL) == 1);
                CHECK(ctos_ssh_native_client_provisional_new_ready(target.client, &provisional) == 0 && !provisional);
                ready = 1;
                break;
            }
            CHECK(ctos_ssh_native_client_provisional_new_ready(target.client, &provisional) == 0);
            if (provisional && target.server->kex->done) { ready = 1; break; }
            jump_bridge_turn(&jump, &target, &bridge, 0);
            continue;
        }
        if (!complete && ctos_ssh_native_client_step(target.client,
                &(struct ctos_ssh_native_client_step){0}) != 0) {
            CHECK(((mode == CHILD_HOST_CANCEL || mode == CHILD_FRESH_PARENT_CANCEL || mode == CHILD_HOST_VIEW_REVOKE
                    || mode == CHILD_HOST_ROLE_CLOSE)
                    && target.callback.host_evidence_calls == 1)
                || ((mode == CHILD_HOST_CHANGED || mode == CHILD_HOST_REVOKED
                    || mode == CHILD_PROVISIONAL_CHANGED || mode == CHILD_PROVISIONAL_REVOKED)
                    && target.callback.host_evidence_calls == 0)
                || (((mode >= CHILD_PROVISIONAL_CANDIDATE_FAIL && mode <= CHILD_PROVISIONAL_KEX_CANCEL)
                    || mode == CHILD_PROVISIONAL_BAD_SIGNATURE || mode == CHILD_PROVISIONAL_CANDIDATE_ABORT_RETRY
                    || (mode >= CHILD_PROVISIONAL_PARENT_CLOSED_CANDIDATE && mode <= CHILD_PROVISIONAL_PARENT_CLOSED_KEX))
                    && target.callback.host_evidence_calls == 0));
            ready = -1;
            break;
        }
        if (mode == CHILD_PROVISIONAL_PREKEX_EOF && target.callback.provisional_review_calls == 1
                && target.callback.provisional_kex_calls == 0) {
            int provisional = 99;
            CHECK(target.callback.provisional_review_calls == 1 && target.callback.provisional_kex_calls == 0
                && !ctos_ssh_native_client_complete(target.client));
            eof_parent_during_child_kex(&jump);
            CHECK(ctos_ssh_native_client_provisional_new_ready(target.client, &provisional) != 0 && !provisional);
            CHECK(target.callback.candidate_abort_calls == 1 && target.callback.kex_complete_calls == 0
                && target.callback.selected_calls == 0);
            CHECK(target.callback.candidate_body != NULL);
            for (size_t i = 0; i < target.callback.candidate_body_length; i++)
                CHECK(target.callback.candidate_body[i] == 0);
            provisional = 99;
            CHECK(ctos_ssh_native_client_provisional_new_ready(target.client, &provisional) != 0 && !provisional);
            ready = 1;
            break;
        }
        if ((mode == CHILD_PROVISIONAL_PREKEX_GUARD_INVALID || mode == CHILD_PROVISIONAL_PREKEX_GUARD_TIMEOUT)
                && target.callback.provisional_review_calls == 1 && target.callback.provisional_kex_calls == 0) {
            struct provisional_wrong_thread wrong = {.client = target.client};
            pthread_t thread;
            int provisional = 99;
            CHECK(!ctos_ssh_native_client_complete(target.client));
            CHECK(pthread_create(&thread, NULL, provisional_wrong_owner, &wrong) == 0);
            CHECK(pthread_join(thread, NULL) == 0);
            CHECK(wrong.result != 0 && wrong.error == EPERM && !wrong.ready);
            CHECK(ctos_ssh_native_client_provisional_new_ready(target.client, &provisional) == 0 && !provisional);
            CHECK(ctos_ssh_native_client_begin_userauth_service_guarded(target.client, generation) != 0 && errno == EACCES);
            CHECK(ctos_ssh_native_client_begin_auth_discovery_guarded(target.client, generation) != 0 && errno == EACCES);
            CHECK(ctos_ssh_native_client_begin_password_auth_guarded(target.client, generation) != 0 && errno == EACCES);
            CHECK(target.callback.selected_calls == 0 && target.callback.credential_calls == 0
                && target.callback.credential_bytes_calls == 0 && target.callback.candidate_abort_calls == 0);
            if (mode == CHILD_PROVISIONAL_PREKEX_GUARD_INVALID) {
                uint32_t current_generation = 0;
                ctos_ssh_effect_invalidate_begin();
                ctos_ssh_effect_invalidate_end();
                CHECK(ctos_ssh_effect_snapshot(&current_generation) == 0 && current_generation != generation);
            } else wait_until_deadline_attempts(hop_deadline, 12000U);
            provisional = 99;
            CHECK(ctos_ssh_native_client_provisional_new_ready(target.client, &provisional) != 0 && !provisional
                && errno == (mode == CHILD_PROVISIONAL_PREKEX_GUARD_INVALID ? ECANCELED : ETIMEDOUT));
            CHECK(target.callback.candidate_abort_calls == 1 && target.callback.kex_complete_calls == 0
                && target.callback.selected_calls == 0 && target.callback.candidate_body != NULL);
            for (size_t i = 0; i < target.callback.candidate_body_length; i++)
                CHECK(target.callback.candidate_body[i] == 0);
            provisional = 99;
            CHECK(ctos_ssh_native_client_provisional_new_ready(target.client, &provisional) != 0 && !provisional
                && errno == EALREADY);
            CHECK(ctos_ssh_native_client_step(target.client, &(struct ctos_ssh_native_client_step){0}) != 0);
            ready = 1;
            break;
        }
        if (mode == CHILD_HOST_PARENT_CLOSED && target.callback.scope_closed_checks == 1) {
            ready = 1;
            break;
        }
        jump_bridge_turn(&jump, &target, &bridge, 0);
        if (ctos_ssh_native_client_complete(target.client) && target.server->kex->done) { ready = 1; break; }
    }
    if (mode == CHILD_PROVISIONAL_PREKEX_EOF || mode == CHILD_PROVISIONAL_PREKEX_GUARD_INVALID
            || mode == CHILD_PROVISIONAL_PREKEX_GUARD_TIMEOUT) {
        CHECK(ready == 1 && target.callback.provisional_candidate_calls == 1
            && target.callback.provisional_review_calls == 1 && target.callback.provisional_kex_calls == 0
            && !ctos_ssh_native_client_complete(target.client));
        goto cleanup;
    }
    if ((mode >= CHILD_PROVISIONAL_CANDIDATE_FAIL && mode <= CHILD_PROVISIONAL_KEX_CANCEL)
            || mode == CHILD_PROVISIONAL_BAD_SIGNATURE || mode == CHILD_PROVISIONAL_CANDIDATE_ABORT_RETRY
            || (mode >= CHILD_PROVISIONAL_PARENT_CLOSED_CANDIDATE && mode <= CHILD_PROVISIONAL_PARENT_CLOSED_KEX)) {
        CHECK(ready == -1 && target.callback.provisional_candidate_calls == 1
            && (mode == CHILD_PROVISIONAL_CANDIDATE_ABORT_RETRY
                ? target.callback.candidate_abort_calls >= 1U : target.callback.candidate_abort_calls == 1U)
            && target.callback.host_evidence_calls == 0
            && target.callback.kex_complete_calls == 0 && target.callback.selected_calls == 0
            && !ctos_ssh_native_client_complete(target.client));
        if (mode == CHILD_PROVISIONAL_BAD_SIGNATURE)
            CHECK(target.callback.provisional_review_calls == 1 && target.callback.provisional_kex_calls == 0);
        if (mode != CHILD_PROVISIONAL_CANDIDATE_ABORT_RETRY) {
            CHECK(target.callback.candidate_body != NULL);
            for (size_t i = 0; i < target.callback.candidate_body_length; i++)
                CHECK(target.callback.candidate_body[i] == 0);
        }
        if (mode >= CHILD_PROVISIONAL_PARENT_CLOSED_CANDIDATE && mode <= CHILD_PROVISIONAL_PARENT_CLOSED_KEX) {
            CHECK(mode == CHILD_PROVISIONAL_PARENT_CLOSED_KEX
                ? jump_status(&jump).remote_eof : jump_status(&jump).phase == CTOS_SSH_JUMP_CLOSED);
            CHECK(target.callback.provisional_review_calls == (mode == CHILD_PROVISIONAL_PARENT_CLOSED_CANDIDATE ? 0U : 1U));
            CHECK(target.callback.provisional_kex_calls == (mode == CHILD_PROVISIONAL_PARENT_CLOSED_KEX ? 1U : 0U));
        }
        goto cleanup;
    }
    if (mode == CHILD_PROVISIONAL_NEW || mode == CHILD_PROVISIONAL_BACKPRESSURE
            || mode == CHILD_PROVISIONAL_CANCEL_PENDING || mode == CHILD_PROVISIONAL_REMOTE_EOF
            || mode == CHILD_PROVISIONAL_REMOTE_CLOSE || mode == CHILD_PROVISIONAL_LOCAL_EOF
            || mode == CHILD_PROVISIONAL_LOCAL_UNPUMPED_EOF || mode == CHILD_PROVISIONAL_VIEW_REVOKE
            || mode == CHILD_PROVISIONAL_PARENT_VIEW_REVOKE || mode == CHILD_PROVISIONAL_CALLBACK_CANCEL
            || mode == CHILD_PROVISIONAL_GUARDED_KEX || poll_mode || ext_mode) {
        int provisional = 0;
        CHECK(ready == 1 && (mode == CHILD_PROVISIONAL_BACKPRESSURE || target.server->kex->done)
            && ctos_ssh_native_client_provisional_new_ready(target.client, &provisional) == 0
            && provisional == (mode != CHILD_PROVISIONAL_BACKPRESSURE)
            && target.callback.provisional_candidate_calls == 1 && target.callback.provisional_review_calls == 1
            && target.callback.provisional_kex_calls == 1 && target.callback.host_evidence_calls == 0
            && target.callback.kex_complete_calls == 0 && target.callback.selected_calls == 0
            && !ctos_ssh_native_client_complete(target.client));
        if (poll_mode) {
            test_provisional_poll(mode, &jump, &target, &bridge, hop_deadline);
            goto cleanup;
        }
        if (ext_mode) {
            test_provisional_ext(mode, &jump, &target, &bridge, hop_deadline, generation);
            goto cleanup;
        }
        if (mode == CHILD_PROVISIONAL_CANCEL_PENDING) {
            CHECK(ctos_ssh_callback_cancel(jump.callback_token) == 0);
            provisional = 99;
            CHECK(ctos_ssh_native_client_provisional_new_ready(target.client, &provisional) != 0 && !provisional);
            goto cleanup;
        }
        if (mode == CHILD_PROVISIONAL_VIEW_REVOKE || mode == CHILD_PROVISIONAL_PARENT_VIEW_REVOKE
                || mode == CHILD_PROVISIONAL_CALLBACK_CANCEL) {
            if (mode == CHILD_PROVISIONAL_VIEW_REVOKE) target.view->valid = 0;
            else if (mode == CHILD_PROVISIONAL_PARENT_VIEW_REVOKE) jump.view->valid = 0;
            else CHECK(ctos_ssh_callback_cancel(target.callback_token) == 0);
            provisional = 99;
            CHECK(ctos_ssh_native_client_provisional_new_ready(target.client, &provisional) != 0 && !provisional);
            target.view->valid = 1; jump.view->valid = 1;
            provisional = 99;
            CHECK(ctos_ssh_native_client_provisional_new_ready(target.client, &provisional) != 0 && !provisional);
            goto cleanup;
        }
        if (mode == CHILD_PROVISIONAL_REMOTE_EOF || mode == CHILD_PROVISIONAL_REMOTE_CLOSE) {
            peer_channel_packet(&jump, mode == CHILD_PROVISIONAL_REMOTE_EOF
                ? SSH2_MSG_CHANNEL_EOF : SSH2_MSG_CHANNEL_CLOSE);
            CHECK(sshpkt_send(jump.server) == 0);
            for (unsigned int turn = 0; turn < 200000U; turn++) {
                CHECK(shell_turn(&jump) == 0);
                struct ctos_ssh_native_jump_status status = jump_status(&jump);
                if (status.remote_eof || status.phase == CTOS_SSH_JUMP_CLOSED) break;
                CHECK(turn + 1U < 200000U);
            }
            provisional = 99;
            CHECK(ctos_ssh_native_client_provisional_new_ready(target.client, &provisional) != 0 && !provisional);
            CHECK(ctos_ssh_native_client_step(target.client, &(struct ctos_ssh_native_client_step){0}) != 0);
            goto cleanup;
        }
        if (mode == CHILD_PROVISIONAL_LOCAL_EOF || mode == CHILD_PROVISIONAL_LOCAL_UNPUMPED_EOF) {
            CHECK(shutdown(target.managed_child_fd, SHUT_WR) == 0);
            if (mode == CHILD_PROVISIONAL_LOCAL_EOF)
                CHECK(ctos_ssh_native_jump_relay_pump(bridge.relay) == 0);
            provisional = 99;
            CHECK(ctos_ssh_native_client_provisional_new_ready(target.client, &provisional) != 0 && !provisional);
            CHECK(ctos_ssh_native_client_step(target.client, &(struct ctos_ssh_native_client_step){0}) != 0);
            goto cleanup;
        }
        if (mode == CHILD_PROVISIONAL_NEW) {
            struct provisional_wrong_thread wrong = {.client = target.client};
            pthread_t thread;
            CHECK(pthread_create(&thread, NULL, provisional_wrong_owner, &wrong) == 0);
            CHECK(pthread_join(thread, NULL) == 0);
            CHECK(wrong.result != 0 && wrong.error == EPERM && !wrong.ready);
            CHECK(ctos_ssh_native_client_provisional_new_ready(target.client, &provisional) == 0 && provisional);
        }
        if (mode == CHILD_PROVISIONAL_GUARDED_KEX) {
            CHECK(ctos_ssh_native_client_begin_userauth_service_guarded(target.client, generation) != 0 && errno == EACCES);
            CHECK(ctos_ssh_native_client_begin_auth_discovery_guarded(target.client, generation) != 0 && errno == EACCES);
            CHECK(ctos_ssh_native_client_begin_password_auth_guarded(target.client, generation) != 0 && errno == EACCES);
            CHECK(target.callback.selected_calls == 0 && target.callback.credential_calls == 0
                && target.callback.credential_bytes_calls == 0 && target.callback.candidate_abort_calls == 0);
            CHECK(ctos_ssh_native_client_provisional_new_ready(target.client, &provisional) == 0 && provisional);
        }
        CHECK(ctos_ssh_native_client_begin_userauth_service(target.client) != 0);
        CHECK(ctos_ssh_native_client_begin_userauth_service_guarded(target.client, 1U) != 0);
        CHECK(ctos_ssh_native_client_begin_auth_discovery(target.client) != 0);
        CHECK(ctos_ssh_native_client_begin_auth_discovery_guarded(target.client, 1U) != 0);
        CHECK(ctos_ssh_native_client_begin_password_auth(target.client) != 0);
        CHECK(ctos_ssh_native_client_begin_password_auth_guarded(target.client, 1U) != 0);
        CHECK(ctos_ssh_native_client_begin_private_key_auth(target.client) != 0);
        CHECK(ctos_ssh_native_client_begin_keyboard_interactive_auth(target.client) != 0);
        unsigned char plan[32] = {0}, stage[92], secret[64], response[16] = {0};
        size_t secret_length = 99U;
        memset(stage, 0xff, sizeof(stage));
        CHECK(ctos_ssh_native_client_begin_discovered_password(target.client, plan,
            (const unsigned char *)"x", 1U, stage) != 0);
        for (size_t i = 0; i < sizeof(stage); i++) CHECK(stage[i] == 0);
        CHECK(ctos_ssh_native_client_begin_discovered_keyboard(target.client, plan, stage) != 0);
        CHECK(ctos_ssh_native_client_continue_keyboard_interactive_auth(target.client, plan, stage) != 0);
        CHECK(ctos_ssh_native_client_continue_private_key_auth(target.client, plan, stage) != 0);
        CHECK(ctos_ssh_native_client_respond_keyboard_interactive(target.client, response, sizeof(response)) != 0);
        CHECK(ctos_ssh_native_client_poll_auth_prompt(target.client, plan, CTOS_SSH_DISCOVERY_PASSWORD) != 0);
        CHECK(ctos_ssh_native_client_poll_selected_password_prompt(target.client, plan) != 0);
        memset(secret, 0xa5, sizeof(secret));
        CHECK(ctos_ssh_native_client_selected_credential_bytes(target.client, secret, sizeof(secret),
            &secret_length) != 0 && secret_length == 0);
        for (size_t i = 0; i < sizeof(secret); i++) CHECK(secret[i] == 0);
        CHECK(ctos_ssh_native_client_begin_shell(target.client, 80, 24) != 0);
        CHECK(target.callback.selected_calls == 0 && target.callback.credential_calls == 0
            && target.callback.credential_bytes_calls == 0);
        goto cleanup;
    }
    if (mode == CHILD_HOST_CANCEL || mode == CHILD_FRESH_PARENT_CANCEL
            || mode == CHILD_HOST_VIEW_REVOKE || mode == CHILD_HOST_ROLE_CLOSE) {
        CHECK(ready == -1 && target.callback.kex_complete_calls == 0
            && target.callback.selected_calls == 0 && !ctos_ssh_native_client_complete(target.client));
        CHECK(target.callback.scope_host_checks == 1 && target.callback.scope_after_invalidation == 1);
        if (mode == CHILD_HOST_ROLE_CLOSE) jump.role = 0;
        goto cleanup;
    }
    if (mode == CHILD_HOST_CHANGED || mode == CHILD_HOST_REVOKED
            || mode == CHILD_PROVISIONAL_CHANGED || mode == CHILD_PROVISIONAL_REVOKED) {
        CHECK(ready == -1 && target.callback.kex_complete_calls == 0);
        assert_host_rejection(target.client, mode == CHILD_HOST_CHANGED || mode == CHILD_PROVISIONAL_CHANGED
            ? CTOS_SSH_HOST_REJECTION_CHANGED : CTOS_SSH_HOST_REJECTION_REVOKED);
        CHECK(ctos_ssh_callback_cancel(jump.callback_token) == 0);
        enum ctos_ssh_native_host_key_rejection rejection = CTOS_SSH_HOST_REJECTION_CERTIFICATE;
        CHECK(ctos_ssh_native_client_host_key_rejection(target.client, &rejection) != 0
            && rejection == CTOS_SSH_HOST_REJECTION_NONE);
        goto cleanup;
    }
    if (mode == CHILD_HOST_PARENT_CLOSED) {
        CHECK(ready == 1 && jump_status(&jump).phase == CTOS_SSH_JUMP_CLOSED
            && target.callback.scope_host_checks == 1 && target.callback.scope_closed_checks == 1);
        goto cleanup;
    }
    CHECK(ready && jump.callback.kex_complete_calls == 1 && target.callback.kex_complete_calls == 1);
    CHECK(jump.callback.host_evidence_calls == 1);
    if (mode == CHILD_HOST_CHANGED_APPROVED || mode == CHILD_FRESH_CHANGED) {
        CHECK(target.callback.host_evidence_calls == 0 && target.callback.changed_candidate_calls == 1
            && target.callback.changed_review_calls == 1 && target.callback.changed_commit_calls == 1
            && target.callback.scope_candidate_checks == 1 && target.callback.scope_review_checks == 1
            && target.callback.scope_kex_checks == 1);
        goto cleanup;
    }
    CHECK(target.callback.host_evidence_calls == 1);
    if (mode == CHILD_HOST_NEW_APPROVED || mode == CHILD_FRESH_NEW) {
        CHECK(target.callback.last_host_classification == CTOS_SSH_CALLBACK_HOST_NEW
            && target.callback.candidate_calls == 1 && target.callback.scope_candidate_checks == 1
            && target.callback.scope_host_checks == 1 && target.callback.scope_kex_checks == 1);
        goto cleanup;
    }
    if (mode == CHILD_FRESH_KNOWN || mode == CHILD_PROVISIONAL_KNOWN) {
        const struct ctos_ssh_file *fresh = ctos_ssh_view_lookup(target.fresh_host_view,
            (const unsigned char *)"ssh/known_hosts", sizeof("ssh/known_hosts") - 1U,
            CTOS_SSH_KNOWN_HOSTS);
        unsigned char expected[32];
        CHECK(fresh != NULL && fresh->body.length != 0);
        managed_known_hosts_digest(target.fresh_host_view, expected);
        CHECK(target.callback.last_host_classification == CTOS_SSH_CALLBACK_HOST_KNOWN
            && target.callback.candidate_calls == 0
            && memcmp(target.callback.last_host_base_digest, expected, sizeof(expected)) == 0);
        goto cleanup;
    }
    CHECK(target.callback.last_host_hop == 1 && target.callback.selected_calls == 0);
    target.service_expected = 1;
    CHECK(ctos_ssh_native_client_begin_userauth_service(target.client) == 0);
    ready = 0;
    for (unsigned int turn = 0; turn < 200000U; turn++) {
        jump_bridge_turn(&jump, &target, &bridge, 1);
        if (ctos_ssh_native_client_userauth_service_ready(target.client)) { ready = 1; break; }
    }
    CHECK(ready);
    if (mode == CHILD_OWN_CANCEL) {
        CHECK(ctos_ssh_callback_cancel(target.callback_token) == 0);
        CHECK(!ctos_ssh_native_client_complete(target.client)
            && !ctos_ssh_native_client_userauth_service_ready(target.client));
        unsigned char receipt[32]; memset(receipt, 0xff, sizeof(receipt));
        CHECK(ctos_ssh_native_client_authentication_selection_receipt(target.client, receipt) != 0);
        for (size_t i = 0; i < sizeof(receipt); i++) CHECK(receipt[i] == 0);
        goto cleanup;
    }
    if (mode == CHILD_OWN_VIEW_REVOKE) {
        target.view->valid = 0;
        CHECK(!ctos_ssh_native_client_complete(target.client)
            && !ctos_ssh_native_client_userauth_service_ready(target.client));
        unsigned char receipt[32]; memset(receipt, 0xff, sizeof(receipt));
        CHECK(ctos_ssh_native_client_authentication_selection_receipt(target.client, receipt) != 0);
        for (size_t i = 0; i < sizeof(receipt); i++) CHECK(receipt[i] == 0);
        goto cleanup;
    }
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
    if (mode == CHILD_VIEW_CLOSE_FAILURE) goto cleanup;
    if (mode == CHILD_KEX_AUTH_INVALID) {
        CHECK(ctos_ssh_callback_check_live(target.callback_token, target.callback.environment) == 0);
        CHECK(jump_status(&jump).phase == CTOS_SSH_JUMP_OPEN);
        CHECK(ctos_ssh_native_client_complete(target.client)
            && ctos_ssh_native_client_userauth_service_ready(target.client));
        CHECK(ctos_ssh_native_client_test_invalidate_kex(target.client) == 0);
        CHECK(ctos_ssh_callback_check_live(target.callback_token, target.callback.environment) == 0);
        CHECK(jump_status(&jump).phase == CTOS_SSH_JUMP_OPEN);
        CHECK(!ctos_ssh_native_client_complete(target.client)
            && !ctos_ssh_native_client_userauth_service_ready(target.client));
        unsigned char receipt[32]; memset(receipt, 0xff, sizeof(receipt));
        CHECK(ctos_ssh_native_client_authentication_selection_receipt(target.client, receipt) != 0);
        for (size_t i = 0; i < sizeof(receipt); i++) CHECK(receipt[i] == 0);
        goto cleanup;
    }
#endif
    if (mode == CHILD_KEYBOARD_CANCEL) {
        CHECK(ctos_ssh_native_client_begin_keyboard_interactive_auth(target.client) == 0);
        for (unsigned int turn = 0; turn < 200000U && target.keyboard_requests == 0; turn++)
            jump_bridge_turn(&jump, &target, &bridge, 1);
        CHECK(target.keyboard_requests == 1);
        keyboard_challenge_packet(&target, 1U, 0U);
        enum ctos_ssh_native_client_keyboard_interactive_auth_result result = CTOS_SSH_KEYBOARD_AUTH_PENDING;
        for (unsigned int turn = 0; turn < 200000U && result == CTOS_SSH_KEYBOARD_AUTH_PENDING; turn++) {
            jump_bridge_turn(&jump, &target, &bridge, 1);
            CHECK(ctos_ssh_native_client_keyboard_interactive_auth_result(target.client, &result) == 0);
        }
        CHECK(result == CTOS_SSH_KEYBOARD_AUTH_CHALLENGE);
        unsigned char challenge[512]; size_t length = 0;
        CHECK(ctos_ssh_native_client_keyboard_interactive_challenge(target.client,
            challenge, sizeof(challenge), &length) == 0 && length > 0);
        CHECK(ctos_ssh_callback_cancel(target.callback_token) == 0);
        memset(challenge, 0xff, sizeof(challenge)); length = 9;
        CHECK(ctos_ssh_native_client_keyboard_interactive_challenge(target.client,
            challenge, sizeof(challenge), &length) != 0 && length == 0);
        for (size_t i = 0; i < sizeof(challenge); i++) CHECK(challenge[i] == 0);
        goto cleanup;
    }
    if (mode == CHILD_CREDENTIAL_CANCEL) {
        target.callback.selected_callback = jump.callback_token;
        target.callback.selected_action = 3;
        CHECK(ctos_ssh_native_client_begin_password_auth(target.client) != 0);
        CHECK(target.callback.selected_calls == 1 && target.password_requests == 0);
        CHECK(target.callback.scope_host_checks == 1 && target.callback.scope_selected_checks == 1
            && target.callback.scope_after_invalidation == 1);
        goto cleanup;
    }
    CHECK(ctos_ssh_native_client_begin_password_auth(target.client) == 0);
    enum ctos_ssh_native_client_password_auth_result auth = CTOS_SSH_PASSWORD_AUTH_PENDING;
    for (unsigned int turn = 0; turn < 200000U && auth == CTOS_SSH_PASSWORD_AUTH_PENDING; turn++) {
        jump_bridge_turn(&jump, &target, &bridge, 1);
        CHECK(ctos_ssh_native_client_password_auth_result(target.client, &auth) == 0);
    }
    CHECK(auth == CTOS_SSH_PASSWORD_AUTH_SUCCESS && target.callback.selected_calls == 1);
    if (mode == CHILD_ROUNDTRIP) {
        CHECK(target.callback.scope_host_checks == 1 && target.callback.scope_selected_checks == 1);
        CHECK(ctos_ssh_native_client_callback_ancestor_proofs_check(target.callback_token,
            target.callback.environment) != 0 && errno == EACCES);
    }
    target.shell_expected = 1; target.shell_window = 65536U; target.shell_maxpacket = 32768U;
    CHECK(ctos_ssh_native_client_begin_shell(target.client, 80, 24) == 0);
    ready = 0;
    for (unsigned int turn = 0; turn < 200000U; turn++) {
        jump_bridge_turn(&jump, &target, &bridge, 1);
        if (shell_status(&target).phase == CTOS_SSH_SHELL_RUNNING) { ready = 1; break; }
    }
    CHECK(ready && target.shell_stage == 3);
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
    if (mode == CHILD_KEX_SHELL_INVALID) {
        CHECK(ctos_ssh_callback_check_transport(target.callback_token,
            target.callback.environment, target.client) == 0);
        CHECK(ctos_ssh_native_client_complete(target.client)
            && ctos_ssh_native_client_userauth_service_ready(target.client));
        CHECK(ctos_ssh_native_client_test_invalidate_kex(target.client) == 0);
        CHECK(ctos_ssh_callback_check_transport(target.callback_token,
            target.callback.environment, target.client) == 0);
        CHECK(jump_status(&jump).phase == CTOS_SSH_JUMP_OPEN);
        CHECK(!ctos_ssh_native_client_complete(target.client)
            && !ctos_ssh_native_client_userauth_service_ready(target.client));
        struct ctos_ssh_native_shell_status status;
        memset(&status, 0xa5, sizeof(status));
        CHECK(ctos_ssh_native_client_shell_status(target.client, &status) != 0 && status.phase == 0);
        goto cleanup;
    }
#endif
    size_t accepted = 0;
    CHECK(ctos_ssh_native_client_shell_write(target.client, (const unsigned char *)"nested", 6U, &accepted) == 0
        && accepted == 6U);
    for (unsigned int turn = 0; turn < 200000U && target.shell_input_length != 6U; turn++)
        jump_bridge_turn(&jump, &target, &bridge, 1);
    CHECK(target.shell_input_length == 6U && memcmp(target.shell_input, "nested", 6U) == 0);
    if (mode == CHILD_LOCAL_EOF) {
        CHECK(shutdown(target.managed_child_fd, SHUT_WR) == 0);
        for (unsigned int turn = 0; turn < 200000U && !jump_status(&jump).local_eof; turn++)
            jump_bridge_turn(&jump, &target, &bridge, 0);
        CHECK(jump_status(&jump).local_eof);
    }
    if (mode == CHILD_REMOTE_CLOSE) {
        int queued = -1, waiting = -1, settled = 0;
        size_t pending = 0, before = bridge.forwarded_to_jump;
        CHECK(ioctl(target.managed_child_fd, FIONREAD, &queued) == 0 && queued == 0);
        peer_channel_data(&target, 0, (const unsigned char *)"reply", 5U);
        for (unsigned int turn = 0; turn < 200000U; turn++) {
            jump_bridge_turn(&jump, &target, &bridge, 0);
            (void)ssh_output_ptr(target.server, &pending);
            CHECK(ioctl(bridge.server_fd, FIONREAD, &waiting) == 0);
            CHECK(ioctl(target.managed_child_fd, FIONREAD, &queued) == 0);
            if (pending == 0 && waiting == 0 && bridge.forwarded_to_jump > before
                && (size_t)queued == bridge.forwarded_to_jump - before) {
                settled = 1; break;
            }
        }
        CHECK(settled && queued > 0 && shell_status(&target).buffered_bytes == 0);
        peer_channel_packet(&jump, SSH2_MSG_CHANNEL_EOF);
        CHECK(sshpkt_send(jump.server) == 0);
        peer_channel_packet(&jump, SSH2_MSG_CHANNEL_CLOSE);
        CHECK(sshpkt_send(jump.server) == 0);
        for (unsigned int turn = 0; turn < 200000U && jump_status(&jump).phase != CTOS_SSH_JUMP_CLOSED; turn++) {
            CHECK(shell_turn(&jump) == 0);
            CHECK(ctos_ssh_native_jump_relay_pump(bridge.relay) == 0);
        }
        CHECK(jump_status(&jump).phase == CTOS_SSH_JUMP_CLOSED);
        CHECK(ioctl(target.managed_child_fd, FIONREAD, &queued) == 0 && queued > 0);
        for (unsigned int turn = 0; turn < 200000U && shell_status(&target).buffered_bytes != 5U; turn++)
            CHECK(ctos_ssh_native_client_step(target.client, &(struct ctos_ssh_native_client_step){0}) == 0);
        unsigned char output[16]; size_t length = 0;
        CHECK(ctos_ssh_native_client_shell_read(target.client, output, sizeof(output), &length) == 0
            && length == 5U && memcmp(output, "reply", 5U) == 0);
        goto cleanup;
    }
    peer_channel_data(&target, 0, (const unsigned char *)"reply", 5U);
    for (unsigned int turn = 0; turn < 200000U && shell_status(&target).buffered_bytes != 5U; turn++)
        jump_bridge_turn(&jump, &target, &bridge, 1);
    unsigned char output[16]; size_t length = 0;
    if (mode == CHILD_OUTPUT_CANCEL) {
        CHECK(ctos_ssh_callback_cancel(jump.callback_token) == 0);
        memset(output, 0xff, sizeof(output));
        CHECK(ctos_ssh_native_client_shell_read(target.client, output, sizeof(output), &length) != 0
            && length == 0);
        for (size_t i = 0; i < sizeof(output); i++) CHECK(output[i] == 0);
        goto cleanup;
    }
    CHECK(ctos_ssh_native_client_shell_read(target.client, output, sizeof(output), &length) == 0
        && length == 5U && memcmp(output, "reply", 5U) == 0);
cleanup:
    target.callback.release_failures_remaining = 1;
    unsigned int abort_before_close = target.callback.candidate_abort_calls;
    CHECK(ctos_ssh_native_client_close(target.client) != 0 && errno == EIO);
    if (mode == CHILD_PROVISIONAL_CANDIDATE_ABORT_RETRY) {
        CHECK(target.callback.candidate_abort_calls == abort_before_close + 1U
            && target.callback.release_calls == 0U);
        CHECK(ctos_ssh_native_jump_relay_close(bridge.relay) != 0 && errno == EBUSY);
        target.callback.candidate_abort_failures_remaining = 0;
        CHECK(ctos_ssh_native_client_close(target.client) != 0 && errno == EIO);
        CHECK(target.callback.candidate_abort_calls == abort_before_close + 2U
            && target.callback.release_calls == 1U);
        CHECK(target.callback.candidate_body != NULL);
        for (size_t i = 0; i < target.callback.candidate_body_length; i++)
            CHECK(target.callback.candidate_body[i] == 0);
    }
    CHECK(ctos_ssh_native_jump_relay_close(bridge.relay) != 0 && errno == EBUSY);
    CHECK(ctos_ssh_native_client_close(jump.client) != 0 && errno == EBUSY);
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
    if (mode == CHILD_VIEW_CLOSE_FAILURE) {
        CHECK(ctos_ssh_native_client_test_fail_next_view_release(target.client) == 0);
        CHECK(ctos_ssh_native_client_close(target.client) != 0 && errno == EIO);
        CHECK(ctos_ssh_view_borrow_release(target.view_borrow) != 0 && errno == EBUSY);
        CHECK(ctos_ssh_native_jump_relay_close(bridge.relay) != 0 && errno == EBUSY);
        CHECK(ctos_ssh_native_client_close(jump.client) != 0 && errno == EBUSY);
        struct child_retry_owner_attempt view_retry = {.child = target.client, .relay = bridge.relay};
        pthread_t view_thread;
        CHECK(pthread_create(&view_thread, NULL, child_retry_wrong_owner, &view_retry) == 0);
        CHECK(pthread_join(view_thread, NULL) == 0);
        CHECK(view_retry.close_result != 0 && view_retry.close_error == EPERM);
        CHECK(view_retry.destroy_result != 0 && view_retry.destroy_error == EPERM);
        CHECK(view_retry.relay_result != 0 && view_retry.relay_error == EPERM);
    }
#endif
    if (mode == CHILD_ROUNDTRIP) {
        /* A close error after the kernel released the number must not make
         * retry close a new descriptor that reuses the same number. */
        CHECK(close(target.managed_child_fd) == 0);
        CHECK(ctos_ssh_native_client_close(target.client) != 0 && errno == EBADF);
        CHECK(dup2(bridge.server_fd, target.managed_child_fd) == target.managed_child_fd);
        struct child_retry_owner_attempt retry = {.child = target.client, .relay = bridge.relay};
        pthread_t thread;
        CHECK(pthread_create(&thread, NULL, child_retry_wrong_owner, &retry) == 0);
        CHECK(pthread_join(thread, NULL) == 0);
        CHECK(retry.close_result != 0 && retry.close_error == EPERM);
        CHECK(retry.destroy_result != 0 && retry.destroy_error == EPERM);
        CHECK(retry.relay_result != 0 && retry.relay_error == EPERM);
        CHECK(ctos_ssh_native_jump_relay_close(bridge.relay) != 0 && errno == EBUSY);
    }
    cleanup_fixture(&target, 2);
    if (mode == CHILD_ROUNDTRIP) {
        CHECK(fcntl(target.managed_child_fd, F_GETFD) >= 0);
        CHECK(close(target.managed_child_fd) == 0);
    }
    CHECK(ctos_ssh_native_jump_relay_close(bridge.relay) == 0);
    CHECK(ctos_ssh_native_jump_relay_destroy(bridge.relay) == 0);
    CHECK(close(bridge.server_fd) == 0);
    cleanup_fixture(&jump, 1);
    child_test_case = -1;
}

static int fixture_nested_provisional;

static void setup_nested_child(struct fixture *child, struct fixture *parent,
    struct fixture *root, ctos_ssh_role_token role, int server_fd,
    struct ctos_ssh_native_jump_relay **relay) {
    memset(child, 0, sizeof(*child));
    child->pair[0] = child->pair[1] = -1;
    child->callback.release_fd = -1;
    child->callback.enable_provisional = fixture_nested_provisional;
    child->password_expected = 1;
    child->password_methods = (const unsigned char *)"publickey,password";
    child->password_methods_length = sizeof("publickey,password") - 1U;
    child->password_text_length = 3U;
    child->password_language_length = 2U;
    CHECK(sshkey_generate(KEY_ED25519, 0, &child->private_key) == 0);
    CHECK(sshkey_from_private(child->private_key, &child->public_key) == 0);
    child->host_key = child->public_key;
    CHECK(!sshkey_equal(parent->public_key, child->public_key));
    fixture_existing_role = role;
    fixture_jump_parent = parent->client;
    fixture_parent_context = parent;
    fixture_root_context = root;
    finish_fixture_setup_selected_mode(child, 60000U, NULL, NULL, fixture_nested_provisional, 0, 1U, 0,
        fixture_nested_provisional ? CTOS_SSH_HOST_KEY_MANAGED : CTOS_SSH_HOST_KEY_PINNED);
    fixture_existing_role = 0;
    fixture_jump_parent = NULL;
    fixture_parent_context = NULL;
    fixture_root_context = NULL;
    *relay = fixture_child_relay;
    fixture_child_relay = NULL;
    child->callback.selected_kind = CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD;
    memcpy(child->callback.expected_selection_receipt, child->selection_receipt, 32U);
    child->callback.selected_view = child->view;
    child->callback.selected_callback = child->callback_token;
    child->callback.selected_role = child->role;
    child->callback.selected_deadline = child->deadline;
    CHECK(close(child->pair[1]) == 0);
    child->pair[1] = server_fd;
    setup_synthetic_server(child);
}

static void test_jump_child_relay_open_failure(void) {
    struct fixture parent, child;
    struct ctos_ssh_native_jump_relay *relay = NULL;
    int pair[2];
    child_test_case = 101;
    setup_jump_fixture(&parent, 1);
    CHECK(ctos_ssh_native_client_begin_password_auth(parent.client) == 0);
    drive_password(&parent, CTOS_SSH_PASSWORD_AUTH_SUCCESS, 0);
    ctos_ssh_role_token next = jump_next_role(&parent, 6);
    parent.jump_bridge_enabled = 1;
    parent.shell_window = 65536U; parent.shell_maxpacket = 32768U;
    CHECK(ctos_ssh_native_client_begin_jump(parent.client, next) == 0);
    drive_jump_phase(&parent, CTOS_SSH_JUMP_OPEN);
    CHECK(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, pair) == 0);
    CHECK(ctos_ssh_native_jump_relay_open(parent.client, pair[0], &relay) == 0);
    CHECK(ctos_ssh_native_jump_relay_close(relay) == 0);
    CHECK(ctos_ssh_native_jump_relay_destroy(relay) == 0);
    CHECK(close(pair[1]) == 0);
    memset(&child, 0, sizeof(child));
    child.pair[0] = child.pair[1] = -1; child.callback.release_fd = -1;
    child.password_expected = 1;
    CHECK(sshkey_generate(KEY_ED25519, 0, &child.private_key) == 0);
    CHECK(sshkey_from_private(child.private_key, &child.public_key) == 0);
    child.host_key = child.public_key;
    fixture_existing_role = next;
    fixture_jump_parent = parent.client;
    fixture_parent_context = fixture_root_context = &parent;
    fixture_expect_child_no_relay = 1;
    finish_fixture_setup_selected_mode(&child, 60000U, NULL, NULL, 0, 0, 1U, 0,
        CTOS_SSH_HOST_KEY_PINNED);
    fixture_expect_child_no_relay = 0;
    fixture_existing_role = 0;
    fixture_jump_parent = NULL;
    fixture_parent_context = fixture_root_context = NULL;
    CHECK(jump_status(&parent).phase == CTOS_SSH_JUMP_OPEN);
    cleanup_fixture(&child, 2);
    struct ctos_ssh_native_jump_status status;
    memset(&status, 0xa5, sizeof(status));
    CHECK(ctos_ssh_native_client_jump_status(parent.client, &status) != 0
        && status.phase == 0);
    cleanup_fixture(&parent, 1);
    child_test_case = -1;
}

static void three_hop_turn(struct fixture *root, struct fixture *middle,
    struct fixture *target, struct jump_bridge_fixture *first,
    struct jump_bridge_fixture *second, int step_target) {
    jump_bridge_turn(middle, target, second, step_target);
    jump_bridge_turn(root, middle, first, 1);
}

static void probe_retired_authentication(struct ctos_ssh_native_client *client) {
    unsigned char plan[32] = {0}, stage[92], response[16] = {0};
    CHECK(ctos_ssh_native_client_begin_userauth_service(client) != 0);
    CHECK(ctos_ssh_native_client_begin_auth_discovery(client) != 0 && errno == EACCES);
    CHECK(ctos_ssh_native_client_begin_password_auth(client) != 0 && errno == EACCES);
    CHECK(ctos_ssh_native_client_begin_private_key_auth(client) != 0 && errno == EACCES);
    CHECK(ctos_ssh_native_client_begin_keyboard_interactive_auth(client) != 0 && errno == EACCES);
    memset(stage, 0xff, sizeof(stage));
    CHECK(ctos_ssh_native_client_begin_discovered_password(client, plan,
        (const unsigned char *)"x", 1U, stage) != 0 && errno == EACCES);
    for (size_t index = 0; index < sizeof(stage); index++) CHECK(stage[index] == 0);
    CHECK(ctos_ssh_native_client_begin_discovered_keyboard(client, plan, stage) != 0 && errno == EACCES);
    CHECK(ctos_ssh_native_client_continue_keyboard_interactive_auth(client, plan, stage) != 0
        && errno == EACCES);
    CHECK(ctos_ssh_native_client_continue_private_key_auth(client, plan, stage) != 0 && errno == EACCES);
    CHECK(ctos_ssh_native_client_respond_keyboard_interactive(client, response, sizeof(response)) != 0
        && errno == EACCES);
    CHECK(ctos_ssh_native_client_poll_auth_prompt(client, plan, CTOS_SSH_DISCOVERY_PASSWORD) != 0
        && errno == EACCES);
}

#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
static void revoke_view_before_promotion_release(void *opaque) {
    struct fixture *fixture = opaque;
    fixture->view->valid = 0;
}

static void expire_role_before_promotion_release(void *opaque) {
    struct fixture *fixture = opaque;
    wait_until_deadline_attempts(fixture->deadline, TEST_EXPIRY_WAIT_ATTEMPTS);
}

static void test_shell_promotion_view_revocation(void) {
    struct fixture fixture;
    setup_password_fixture(&fixture, 0, 15000U);
    CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
    drive_password(&fixture, CTOS_SSH_PASSWORD_AUTH_SUCCESS, 0);
    CHECK(ctos_ssh_native_client_test_before_promotion_release(fixture.client,
        revoke_view_before_promotion_release, &fixture) == 0);
    CHECK(ctos_ssh_native_client_begin_shell(fixture.client, 80, 24) != 0 && errno == EACCES);
    CHECK(!fixture.view->valid);
    CHECK(ctos_ssh_view_borrow_release(fixture.view_borrow) != 0 && errno == EBUSY);
    CHECK(ctos_ssh_native_client_step(fixture.client,
        &(struct ctos_ssh_native_client_step){0}) != 0 && errno == EALREADY);
    cleanup_fixture(&fixture, 1);
}

static void test_shell_promotion_role_expiry(void) {
    struct fixture fixture;
    setup_password_fixture(&fixture, 0, TEST_EXPIRY_PREPARATION_MS);
    CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
    drive_password(&fixture, CTOS_SSH_PASSWORD_AUTH_SUCCESS, 0);
    /* The file view is intentionally longer-lived than the role. */
    fixture.view->deadline = fixture.deadline + 5000U;
    CHECK(ctos_ssh_native_client_test_before_promotion_release(fixture.client,
        expire_role_before_promotion_release, &fixture) == 0);
    CHECK(ctos_ssh_native_client_begin_shell(fixture.client, 80, 24) != 0 && errno == ETIMEDOUT);
    fixture.view_borrow = NULL;
    CHECK(ctos_ssh_native_client_step(fixture.client,
        &(struct ctos_ssh_native_client_step){0}) != 0 && errno == EALREADY);
    cleanup_fixture(&fixture, 1);
}
#endif

/* 0/1: original cancellation cases; 2: retained expiry; 3/4: retained
 * middle/target cancellation; 5: retained orderly close; 6/7/8: fault hooks;
 * 9: descendant EOF; 10: revoke between view preflight and retirement. */
static void test_jump_three_layer_ancestor_cancel(int mode) {
    static const unsigned char target_nonce[32] = {10};
    struct fixture root, middle, target;
    struct jump_bridge_fixture first = {0}, second = {0};
    struct ctos_ssh_role_request target_request;
    ctos_ssh_role_token middle_role, target_role = 0;
    int first_pair[2], second_pair[2], ready;
    child_test_case = 100 + mode;

    setup_jump_fixture_lifetime(&root, 1, mode == 2 ? 15000U : 60000U);
    CHECK(ctos_ssh_native_client_begin_password_auth(root.client) == 0);
    drive_password(&root, CTOS_SSH_PASSWORD_AUTH_SUCCESS, 0);
    middle_role = jump_next_role(&root, 7);
    CHECK(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, first_pair) == 0);
    first.server_fd = first_pair[0];
    root.jump_bridge_enabled = 1; root.jump_bridge_fd = first.server_fd;
    root.shell_window = 65536U; root.shell_maxpacket = 32768U;
    CHECK(ctos_ssh_native_client_begin_jump(root.client, middle_role) == 0);
    drive_jump_phase(&root, CTOS_SSH_JUMP_OPEN);
    if (mode >= 2) {
        struct jump_retain_owner_attempt wrong = {.client = root.client};
        pthread_t thread;
        CHECK(ctos_ssh_native_client_retain_jump_transport(root.client) != 0 && errno == EACCES);
        CHECK(pthread_create(&thread, NULL, jump_retain_wrong_owner, &wrong) == 0);
        CHECK(pthread_join(thread, NULL) == 0);
        CHECK(wrong.result != 0 && wrong.error == EPERM);
        CHECK(wrong.auth_result != 0 && wrong.auth_error == EPERM);
        CHECK(jump_status(&root).phase == CTOS_SSH_JUMP_OPEN);
    }
    fixture_step_budget = 4096U;
    setup_nested_child(&middle, &root, &root, middle_role, first_pair[1], &first.relay);
    fixture_step_budget = TEST_STEP_BUDGET;
    if (mode >= 2)
        CHECK(ctos_ssh_native_client_retain_jump_transport(root.client) != 0 && errno == EACCES);
    ready = 0;
    for (unsigned int turn = 0; turn < 200000U; turn++) {
        int complete = ctos_ssh_native_client_complete(middle.client);
        jump_bridge_turn(&root, &middle, &first, !complete);
        if (ctos_ssh_native_client_complete(middle.client) && middle.server->kex->done) {
            ready = 1; break;
        }
    }
    CHECK(ready && middle.callback.host_evidence_calls == 1 && middle.callback.last_host_hop == 1);
    middle.service_expected = 1;
    CHECK(ctos_ssh_native_client_begin_userauth_service(middle.client) == 0);
    ready = 0;
    for (unsigned int turn = 0; turn < 200000U; turn++) {
        jump_bridge_turn(&root, &middle, &first, 1);
        if (ctos_ssh_native_client_userauth_service_ready(middle.client)) { ready = 1; break; }
    }
    CHECK(ready && ctos_ssh_native_client_begin_password_auth(middle.client) == 0);
    enum ctos_ssh_native_client_password_auth_result auth = CTOS_SSH_PASSWORD_AUTH_PENDING;
    for (unsigned int turn = 0; turn < 200000U && auth == CTOS_SSH_PASSWORD_AUTH_PENDING; turn++) {
        jump_bridge_turn(&root, &middle, &first, 1);
        CHECK(ctos_ssh_native_client_password_auth_result(middle.client, &auth) == 0);
    }
    CHECK(auth == CTOS_SSH_PASSWORD_AUTH_SUCCESS && middle.callback.selected_calls == 1);
    target_request = role_request(root.deadline);
    target_request.hop = 2;
    target_request.role_nonce = target_nonce;
    target_request.public_view_id = "target-view";
    CHECK(ctos_ssh_role_bootstrap_open(&target_request, &target_role) == 0);
    CHECK(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, second_pair) == 0);
    second.server_fd = second_pair[0];
    middle.shell_expected = middle.jump_expected = middle.jump_bridge_enabled = 1;
    middle.jump_bridge_fd = second.server_fd;
    middle.shell_window = 65536U; middle.shell_maxpacket = 32768U;
    CHECK(ctos_ssh_native_client_begin_jump(middle.client, target_role) == 0);
    for (unsigned int turn = 0; turn < 200000U && jump_status(&middle).phase != CTOS_SSH_JUMP_OPEN; turn++)
        jump_bridge_turn(&root, &middle, &first, 1);
    CHECK(jump_status(&middle).phase == CTOS_SSH_JUMP_OPEN);
    fixture_step_budget = 4096U;
    fixture_nested_provisional = mode == 12;
    setup_nested_child(&target, &middle, &root, target_role, second_pair[1], &second.relay);
    fixture_nested_provisional = 0;
    fixture_step_budget = TEST_STEP_BUDGET;
    if (mode >= 2)
        CHECK(ctos_ssh_native_client_retain_jump_transport(middle.client) != 0 && errno == EACCES);
    target.callback.scope_probe = 1;
    target.callback.scope_token = target.callback_token;
    target.callback.scope_other = middle.callback_token;
    CHECK(ctos_ssh_native_client_callback_ancestor_proofs_check(target.callback_token,
        target.callback.environment) != 0 && errno == EACCES);
    CHECK(!sshkey_equal(root.public_key, target.public_key));
    if (mode == 12) {
        int denied = 0;
        for (unsigned int turn = 0; turn < 200000U; turn++) {
            if (ctos_ssh_native_client_step(target.client, &(struct ctos_ssh_native_client_step){0}) != 0) {
                denied = 1; break;
            }
            three_hop_turn(&root, &middle, &target, &first, &second, 0);
        }
        CHECK(denied && target.callback.provisional_candidate_calls == 0
            && target.callback.provisional_review_calls == 0 && target.callback.provisional_kex_calls == 0
            && target.callback.host_evidence_calls == 0 && target.callback.selected_calls == 0);
        int provisional = 99;
        CHECK(ctos_ssh_native_client_provisional_new_ready(target.client, &provisional) != 0 && !provisional);
        goto cleanup;
    }
    ready = 0;
    for (unsigned int turn = 0; turn < 200000U; turn++) {
        int complete = ctos_ssh_native_client_complete(target.client);
        three_hop_turn(&root, &middle, &target, &first, &second, !complete);
        if (ctos_ssh_native_client_complete(target.client) && target.server->kex->done) {
            ready = 1; break;
        }
    }
    CHECK(ready && root.callback.host_evidence_calls == 1 && middle.callback.host_evidence_calls == 1
        && target.callback.host_evidence_calls == 1 && target.callback.last_host_hop == 2);
    target.service_expected = 1;
    CHECK(ctos_ssh_native_client_begin_userauth_service(target.client) == 0);
    ready = 0;
    for (unsigned int turn = 0; turn < 200000U; turn++) {
        three_hop_turn(&root, &middle, &target, &first, &second, 1);
        if (ctos_ssh_native_client_userauth_service_ready(target.client)) { ready = 1; break; }
    }
    CHECK(ready && target.callback.scope_host_checks == 1);
    if (mode == 1) {
        target.callback.selected_callback = root.callback_token;
        target.callback.selected_action = 3;
        CHECK(ctos_ssh_native_client_begin_password_auth(target.client) != 0);
        CHECK(target.callback.selected_calls == 1 && target.callback.scope_selected_checks == 1
            && target.callback.scope_after_invalidation == 1 && target.password_requests == 0);
        goto cleanup;
    }
    CHECK(ctos_ssh_native_client_begin_password_auth(target.client) == 0);
    auth = CTOS_SSH_PASSWORD_AUTH_PENDING;
    for (unsigned int turn = 0; turn < 200000U && auth == CTOS_SSH_PASSWORD_AUTH_PENDING; turn++) {
        three_hop_turn(&root, &middle, &target, &first, &second, 1);
        CHECK(ctos_ssh_native_client_password_auth_result(target.client, &auth) == 0);
    }
    CHECK(auth == CTOS_SSH_PASSWORD_AUTH_SUCCESS && target.callback.selected_calls == 1
        && target.callback.scope_selected_checks == 1);
    target.shell_expected = 1; target.shell_window = 65536U; target.shell_maxpacket = 32768U;
    CHECK(ctos_ssh_native_client_begin_shell(target.client, 80, 24) == 0);
    if (mode >= 2)
        CHECK(ctos_ssh_native_client_retain_jump_transport(middle.client) != 0 && errno == EACCES);
    ready = 0;
    for (unsigned int turn = 0; turn < 200000U; turn++) {
        three_hop_turn(&root, &middle, &target, &first, &second, 1);
        if (shell_status(&target).phase == CTOS_SSH_SHELL_RUNNING) { ready = 1; break; }
    }
    CHECK(ready);
    if (mode >= 2) {
        CHECK(ctos_ssh_native_client_retain_jump_transport(root.client) != 0 && errno == EACCES);
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
        if (mode == 10) {
            CHECK(ctos_ssh_native_client_test_before_promotion_release(middle.client,
                revoke_view_before_promotion_release, &middle) == 0);
            CHECK(ctos_ssh_native_client_retain_jump_transport(middle.client) != 0 && errno == EACCES);
            CHECK(!middle.view->valid);
            CHECK(ctos_ssh_view_borrow_release(middle.view_borrow) != 0 && errno == EBUSY);
            unsigned char denied[8]; size_t denied_length = 99;
            CHECK(ctos_ssh_native_client_shell_read(target.client, denied, sizeof(denied),
                &denied_length) != 0 && denied_length == 0);
            goto cleanup;
        }
        if (mode == 6) {
            CHECK(ctos_ssh_native_client_test_fail_next_view_release(middle.client) == 0);
            CHECK(ctos_ssh_native_client_retain_jump_transport(middle.client) != 0 && errno == EIO);
            CHECK(ctos_ssh_view_borrow_release(middle.view_borrow) != 0 && errno == EBUSY);
            CHECK(ctos_ssh_native_client_retain_jump_transport(middle.client) != 0 && errno == EALREADY);
            unsigned char denied[8]; size_t denied_length = 99;
            CHECK(ctos_ssh_native_client_shell_read(target.client, denied, sizeof(denied),
                &denied_length) != 0 && denied_length == 0);
            goto cleanup;
        }
        if (mode == 7) {
            CHECK(ctos_ssh_native_client_test_invalidate_kex(target.client) == 0);
            CHECK(ctos_ssh_native_client_retain_jump_transport(middle.client) != 0);
            CHECK(ctos_ssh_view_borrow_live(middle.view_borrow) == 0);
            goto cleanup;
        }
#endif
        CHECK(ctos_ssh_native_client_retain_jump_transport(middle.client) == 0);
        middle.view_borrow = NULL;
        CHECK(ctos_ssh_native_client_retain_jump_transport(middle.client) != 0 && errno == EALREADY);
        if (mode == 9) {
            peer_channel_packet(&target, SSH2_MSG_CHANNEL_EOF);
            CHECK(sshpkt_send(target.server) == 0);
            for (unsigned int turn = 0; turn < 200000U && !shell_status(&target).remote_eof; turn++)
                three_hop_turn(&root, &middle, &target, &first, &second, 1);
            CHECK(shell_status(&target).remote_eof);
            CHECK(ctos_ssh_native_client_retain_jump_transport(root.client) != 0 && errno == EACCES);
            CHECK(ctos_ssh_view_borrow_live(root.view_borrow) == 0);
            CHECK(jump_status(&root).phase == CTOS_SSH_JUMP_OPEN);
            goto cleanup;
        }
        CHECK(ctos_ssh_native_client_retain_jump_transport(root.client) == 0);
        root.view_borrow = NULL;
        target.view_borrow = NULL;
        CHECK(ctos_ssh_native_client_retain_jump_transport(root.client) != 0 && errno == EALREADY);
        struct jump_retain_owner_attempt wrong = {.client = root.client};
        pthread_t thread;
        CHECK(pthread_create(&thread, NULL, jump_retain_wrong_owner, &wrong) == 0);
        CHECK(pthread_join(thread, NULL) == 0);
        CHECK(wrong.result != 0 && wrong.error == EPERM);
        CHECK(wrong.auth_result != 0 && wrong.auth_error == EPERM);
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
        if (mode == 8) {
            CHECK(ctos_ssh_native_client_test_invalidate_kex(target.client) == 0);
            struct ctos_ssh_native_jump_status denied;
            CHECK(ctos_ssh_native_client_jump_status(root.client, &denied) != 0);
            unsigned char output[16]; size_t length = 99;
            CHECK(ctos_ssh_native_client_shell_read(target.client, output, sizeof(output), &length) != 0
                && length == 0);
            goto cleanup;
        }
#endif
        if (mode == 2) {
            struct ctos_ssh_role_snapshot snapshot;
            unsigned char receipt[32], secret[8];
            size_t secret_length = 99, accepted = 0;
            root.view->valid = middle.view->valid = target.view->valid = 0;
            wait_until_deadline_attempts(root.deadline, 20000U);
            CHECK(ctos_ssh_role_bootstrap_snapshot(root.role, &snapshot) != 0 && errno == ETIMEDOUT);
            CHECK(ctos_ssh_role_bootstrap_snapshot(middle.role, &snapshot) != 0 && errno == ETIMEDOUT);
            CHECK(ctos_ssh_role_bootstrap_snapshot(target.role, &snapshot) != 0 && errno == ETIMEDOUT);
            CHECK(ctos_ssh_native_client_authentication_selection_receipt(root.client, receipt) != 0);
            CHECK(ctos_ssh_native_client_authentication_selection_receipt(middle.client, receipt) != 0);
            CHECK(ctos_ssh_native_client_selected_credential_bytes(target.client, secret,
                sizeof(secret), &secret_length) != 0 && secret_length == 0);
            probe_retired_authentication(root.client);
            probe_retired_authentication(middle.client);
            probe_retired_authentication(target.client);
            CHECK(ctos_ssh_native_client_shell_write(target.client,
                (const unsigned char *)"after", 5U, &accepted) == 0 && accepted == 5U);
            for (unsigned int turn = 0; turn < 200000U && target.shell_input_length != 5U; turn++)
                three_hop_turn(&root, &middle, &target, &first, &second, 1);
            CHECK(target.shell_input_length == 5U && memcmp(target.shell_input, "after", 5U) == 0);
        }
    }
    peer_channel_data(&target, 0, (const unsigned char *)"three", 5U);
    for (unsigned int turn = 0; turn < 200000U && shell_status(&target).buffered_bytes != 5U; turn++)
        three_hop_turn(&root, &middle, &target, &first, &second, 1);
    CHECK(shell_status(&target).buffered_bytes == 5U);
    if (mode == 2) {
        unsigned char output[16]; size_t length = 0;
        CHECK(ctos_ssh_native_client_shell_read(target.client, output, sizeof(output), &length) == 0
            && length == 5U && memcmp(output, "three", 5U) == 0);
        peer_channel_data(&target, 0, (const unsigned char *)"stale", 5U);
        for (unsigned int turn = 0; turn < 200000U && shell_status(&target).buffered_bytes != 5U; turn++)
            three_hop_turn(&root, &middle, &target, &first, &second, 1);
        CHECK(shell_status(&target).buffered_bytes == 5U);
    }
    if (mode == 5) {
        send_channel_exit(&target, 0);
        peer_channel_packet(&target, SSH2_MSG_CHANNEL_EOF);
        CHECK(sshpkt_send(target.server) == 0);
        peer_channel_packet(&target, SSH2_MSG_CHANNEL_CLOSE);
        CHECK(sshpkt_send(target.server) == 0);
        for (unsigned int turn = 0; turn < 200000U
                && shell_status(&target).phase != CTOS_SSH_SHELL_CLOSED; turn++)
            three_hop_turn(&root, &middle, &target, &first, &second, 1);
        CHECK(shell_status(&target).phase == CTOS_SSH_SHELL_CLOSED);
        unsigned char output[16]; size_t length = 0;
        CHECK(ctos_ssh_native_client_shell_read(target.client, output, sizeof(output), &length) == 0
            && length == 5U && memcmp(output, "three", 5U) == 0);
        CHECK(jump_status(&middle).phase == CTOS_SSH_JUMP_OPEN);
        goto cleanup;
    }
    CHECK(ctos_ssh_callback_cancel(mode == 3 ? middle.callback_token
        : mode == 4 ? target.callback_token : root.callback_token) == 0);
    unsigned char output[16]; size_t length = 1;
    memset(output, 0xff, sizeof(output));
    CHECK(ctos_ssh_native_client_shell_read(target.client, output, sizeof(output), &length) != 0 && length == 0);
    for (size_t i = 0; i < sizeof(output); i++) CHECK(output[i] == 0);
    struct ctos_ssh_native_jump_status denied;
    CHECK(ctos_ssh_native_client_jump_status(middle.client, &denied) != 0);
    CHECK(ctos_ssh_native_jump_relay_close(second.relay) != 0 && errno == EBUSY);
    CHECK(ctos_ssh_native_client_close(middle.client) != 0 && errno == EBUSY);
cleanup:
    cleanup_fixture(&target, 1);
    CHECK(ctos_ssh_native_jump_relay_close(second.relay) == 0);
    CHECK(ctos_ssh_native_jump_relay_destroy(second.relay) == 0);
    CHECK(close(second.server_fd) == 0);
    cleanup_fixture(&middle, 1);
    CHECK(ctos_ssh_native_jump_relay_close(first.relay) == 0);
    CHECK(ctos_ssh_native_jump_relay_destroy(first.relay) == 0);
    CHECK(close(first.server_fd) == 0);
    cleanup_fixture(&root, 1);
    child_test_case = -1;
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

static void test_shell_early_output(void) {
    for (int stage = 2; stage <= 3; stage++) {
        for (int outcome = 0; outcome <= 2; outcome++) {
            struct fixture fixture;
            unsigned char output[32];
            size_t length = 0;
            int saw_pending_output = 0;
            setup_shell_fixture(&fixture, 1, 65536U, 32768U, outcome == 1 ? stage : 0);
            fixture.shell_early_output_stage = stage;
            fixture.shell_early_close_stage = outcome == 2 ? stage : 0;
            for (unsigned int turn = 0; turn < 40000U; turn++) {
                CHECK(shell_turn(&fixture) == 0);
                struct ctos_ssh_native_shell_status status = shell_status(&fixture);
                if (status.buffered_bytes != 0 && status.phase != CTOS_SSH_SHELL_RUNNING) {
                    memset(output, 0xaa, sizeof(output));
                    length = 99U;
                    CHECK(ctos_ssh_native_client_shell_read(fixture.client, output, sizeof(output), &length) != 0
                        && errno == EACCES && length == 0);
                    for (size_t index = 0; index < sizeof(output); index++) CHECK(output[index] == 0);
                    saw_pending_output = 1;
                }
                if (status.phase == CTOS_SSH_SHELL_RUNNING || status.phase == CTOS_SSH_SHELL_REJECTED
                    || status.phase == CTOS_SSH_SHELL_CLOSED) break;
            }
            CHECK(saw_pending_output);
            struct ctos_ssh_native_shell_status final = shell_status(&fixture);
            if (outcome != 0) {
                CHECK(final.phase == (outcome == 1 ? CTOS_SSH_SHELL_REJECTED : CTOS_SSH_SHELL_CLOSED)
                    && final.buffered_bytes == 0);
                CHECK(ctos_ssh_native_client_shell_read(fixture.client, output, sizeof(output), &length) != 0
                    && length == 0);
            } else {
                CHECK(final.phase == CTOS_SSH_SHELL_RUNNING && final.buffered_bytes == 11U);
                CHECK(ctos_ssh_native_client_shell_read(fixture.client, output, sizeof(output), &length) == 0
                    && length == 11U && memcmp(output, "earlystderr", 11U) == 0);
            }
            cleanup_fixture(&fixture, 1);
        }
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
    probe_retired_authentication(fixture.client);
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

static size_t changed_test_source(unsigned char *body, size_t capacity,
    const struct sshkey *old_one, const struct sshkey *old_two, int shared,
    size_t *other_offset) {
    FILE *stream = fmemopen(body, capacity, "w+");
    CHECK(stream != NULL);
    CHECK(fwrite("# preserved comment\r\n", 1, sizeof("# preserved comment\r\n") - 1U, stream)
        == sizeof("# preserved comment\r\n") - 1U);
    CHECK(ctos_hostfile_write_entry(stream, shared ? "example.invalid,shared.invalid"
        : "example.invalid", NULL, old_one, 0) == 1);
    CHECK(fflush(stream) == 0 && fseek(stream, -1, SEEK_CUR) == 0);
    CHECK(fwrite("\r\n", 1, 2U, stream) == 2U);
    CHECK(ctos_hostfile_write_entry(stream, "example.invalid", NULL, old_two, 0) == 1);
    CHECK(fflush(stream) == 0);
    long offset = ftell(stream);
    CHECK(offset > 0);
    *other_offset = (size_t)offset;
    CHECK(ctos_hostfile_write_entry(stream, "other.invalid", NULL, old_one, 0) == 1);
    CHECK(fflush(stream) == 0);
    long end = ftell(stream);
    CHECK(end > offset && (size_t)end < capacity && body[end - 1] == '\n');
    CHECK(fclose(stream) == 0);
    body[end - 1] = 0;
    return (size_t)end - 1U;
}

static void test_changed_same_connection(void) {
    struct sshkey *old_one = NULL, *old_two = NULL;
    unsigned char source[16384], other_source[4096], expected[8192], offered[4096];
    size_t source_length, other_offset, other_length, offered_length, expected_length;
    CHECK(sshkey_generate(KEY_ED25519, 0, &old_one) == 0);
    CHECK(sshkey_generate(KEY_ECDSA, 256, &old_two) == 0);
    other_length = format_host_key(old_one, other_source, sizeof(other_source));

    /* success, reject, bad KEX signature, failed CAS, protected source,
     * original deadline, explicit cancel, source/view revocation, oversized record set */
    for (unsigned int mode = 0; mode < 9U; mode++) {
        struct fixture fixture = {0};
        memset(source, 0, sizeof(source));
        source_length = changed_test_source(source, sizeof(source), old_one, old_two,
            mode == 4U, &other_offset);
        if (mode == 8U) {
            source_length = 0;
            for (unsigned int row = 0; row < 129U; row++)
                source_length += format_host_key(old_one, source + source_length,
                    sizeof(source) - source_length);
        }
        fixture.known_hosts_override = source;
        fixture.known_hosts_override_length = source_length;
        fixture.other_hosts_override = other_source;
        fixture.other_hosts_override_length = other_length;
        fixture.password_expected = 1;
        fixture.password_methods = (const unsigned char *)"publickey,password";
        fixture.password_methods_length = sizeof("publickey,password") - 1U;
        fixture.password_text_length = 3U;
        fixture.password_language_length = 2U;
        fixture.password_reply = PASSWORD_REPLY_SUCCESS;
        fixture.pair[0] = fixture.pair[1] = -1;
        fixture.callback.release_fd = -1;
        fixture.callback.enable_changed = 1;
        fixture.callback.deny_changed_review = mode == 1U;
        fixture.callback.fail_changed_commit = mode == 3U;
        fixture.callback.expire_changed_review = mode == 5U;
        fixture.callback.cancel_changed_review = mode == 6U;
        fixture.callback.invalidate_changed_view = mode == 7U;
        CHECK(sshkey_generate(KEY_ED25519, 0, &fixture.private_key) == 0);
        CHECK(sshkey_from_private(fixture.private_key, &fixture.public_key) == 0);
        fixture.host_key = fixture.public_key;
        finish_fixture_setup_selected_mode(&fixture, mode == 5U ? TEST_EXPIRY_PREPARATION_MS : 15000U,
            NULL, NULL, 0, 0, 1U, 0, CTOS_SSH_HOST_KEY_MANAGED);
        fixture.callback.selected_kind = CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD;
        memcpy(fixture.callback.expected_selection_receipt, fixture.selection_receipt, 32U);
        fixture.callback.selected_view = fixture.view;
        fixture.callback.selected_callback = fixture.callback_token;
        fixture.callback.selected_role = fixture.role;
        fixture.callback.selected_deadline = fixture.deadline;

        if (mode == 0U) {
            unsigned char material[CTOS_SSH_MATERIAL_WIRE_BYTES];
            finish_service_fixture(&fixture, 0, 0);
            CHECK(read_u32(fixture.callback.changed_records + 16U) == 2U);
            CHECK(read_u32(fixture.callback.changed_records + 88U) == 2U);
            CHECK(read_u32(fixture.callback.changed_records + 88U + 216U) == 3U);
            offered_length = format_host_key(fixture.host_key, offered, sizeof(offered));
            expected_length = sizeof("# preserved comment\r\n") - 1U;
            memcpy(expected, source, expected_length);
            memcpy(expected + expected_length, source + other_offset, source_length - other_offset);
            expected_length += source_length - other_offset;
            expected[expected_length++] = '\n';
            memcpy(expected + expected_length, offered, offered_length);
            expected_length += offered_length;
            CHECK(fixture.callback.candidate_body_length == expected_length
                && memcmp(fixture.callback.candidate_body, expected, expected_length) == 0);
            CHECK(ctos_ssh_material_inspect(CTOS_SSH_KNOWN_HOSTS,
                fixture.callback.candidate_body, fixture.callback.candidate_body_length,
                NULL, 0, fixture.deadline, material) == 0
                && read_u32(material + 8U) == CTOS_MATERIAL_PARSED);
            const struct ctos_ssh_file *other = ctos_ssh_view_lookup(fixture.view,
                (const unsigned char *)".ssh/known_hosts", sizeof(".ssh/known_hosts") - 1U,
                CTOS_SSH_KNOWN_HOSTS);
            CHECK(other != NULL && other->body.length == other_length
                && memcmp(other->body.data, other_source, other_length) == 0);
            CHECK(fixture.callback.selected_calls == 0U && fixture.callback.changed_commit_calls == 1U);
            CHECK(ctos_ssh_native_client_begin_userauth_service(fixture.client) == 0);
            drive_service(&fixture, 0);
            CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
            drive_password(&fixture, CTOS_SSH_PASSWORD_AUTH_SUCCESS, 0);
            CHECK(fixture.callback.selected_calls == 1U && fixture.callback.changed_commit_calls == 1U);
            fixture.shell_expected = 1; fixture.shell_window = 65536U; fixture.shell_maxpacket = 32768U;
            CHECK(ctos_ssh_native_client_begin_shell(fixture.client, 80, 24) == 0);
            for (unsigned int turn = 0; turn < 100000U && fixture.shell_stage != 3; turn++)
                CHECK(shell_turn(&fixture) == 0);
            CHECK(fixture.shell_stage == 3);
        } else {
            setup_synthetic_server(&fixture);
            if (mode == 2U) {
                original_server_sign = fixture.server->kex->sign;
                CHECK(original_server_sign != NULL);
                fixture.server->kex->sign = sign_with_invalid_signature;
            }
            int failed = 0;
            for (unsigned int turn = 0; turn < 100000U; turn++) {
                if (ctos_ssh_native_client_step(fixture.client,
                        &(struct ctos_ssh_native_client_step){0}) != 0) { failed = 1; break; }
                CHECK(!ctos_ssh_native_client_complete(fixture.client));
                CHECK(pump_synthetic_server(&fixture, 64U, NULL, NULL) == 0);
            }
            CHECK(failed && !ctos_ssh_native_client_complete(fixture.client));
            CHECK(fixture.callback.changed_commit_calls == 0U && fixture.callback.selected_calls == 0U);
            if (mode == 4U || mode == 8U) {
                CHECK(fixture.callback.changed_candidate_calls == 0U
                    && fixture.callback.changed_review_calls == 0U);
                assert_host_rejection(fixture.client, CTOS_SSH_HOST_REJECTION_CHANGED_BLOCKED);
                const struct ctos_ssh_file *other = ctos_ssh_view_lookup(fixture.view,
                    (const unsigned char *)".ssh/known_hosts", sizeof(".ssh/known_hosts") - 1U,
                    CTOS_SSH_KNOWN_HOSTS);
                CHECK(other != NULL && other->body.length == other_length
                    && memcmp(other->body.data, other_source, other_length) == 0);
            } else {
                CHECK(fixture.callback.changed_candidate_calls == 1U
                    && fixture.callback.changed_review_calls == 1U
                    && fixture.callback.candidate_abort_calls == 1U);
                CHECK(fixture.callback.changed_commit_attempts == (mode == 3U ? 1U : 0U));
            }
            if (mode == 5U) fixture.role = 0;
        }
        cleanup_fixture(&fixture, 1);
    }
    sshkey_free(old_two);
    sshkey_free(old_one);
}

static void test_changed_stale_source(void) {
    struct fixture fixture = {0};
    struct ctos_ssh_hostfile_issue issue = {0};
    struct sshkey *old_key = NULL;
    unsigned char source[4096], *replacement;
    const size_t capacity = CTOS_SSH_CALLBACK_MAX_FILE_BYTES + 1U;
    size_t source_length;
    FILE *stream;

    CHECK(sshkey_generate(KEY_ED25519, 0, &old_key) == 0);
    memcpy(source, "# original\n", sizeof("# original\n") - 1U);
    source_length = sizeof("# original\n") - 1U;
    source_length += format_host_key(old_key, source + source_length,
        sizeof(source) - source_length);
    fixture.known_hosts_override = source;
    fixture.known_hosts_override_length = source_length;
    fixture.pair[0] = fixture.pair[1] = -1;
    fixture.callback.release_fd = -1;
    fixture.callback.enable_changed = 1;
    CHECK(sshkey_generate(KEY_ED25519, 0, &fixture.private_key) == 0);
    CHECK(sshkey_from_private(fixture.private_key, &fixture.public_key) == 0);
    fixture.host_key = fixture.public_key;
    finish_fixture_setup_selected_mode(&fixture, 15000U, NULL, NULL, 0, 0,
        1U, 0, CTOS_SSH_HOST_KEY_MANAGED);
    CHECK(ctos_ssh_hostfile_issue(fixture.view, fixture.role, "ssh/known_hosts",
        "example.invalid", fixture.host_key, "native-candidate", NULL, NULL,
        0, 0, NULL, &issue) == 0
        && issue.classification == CTOS_SSH_CALLBACK_HOST_CHANGED);
    const struct ctos_ssh_file *file = ctos_ssh_view_lookup(fixture.view,
        (const unsigned char *)"ssh/known_hosts", sizeof("ssh/known_hosts") - 1U,
        CTOS_SSH_KNOWN_HOSTS);
    CHECK(file != NULL && file->body.length == source_length);
    ((unsigned char *)file->body.data)[2] = 'O';
    replacement = calloc(1U, capacity);
    CHECK(replacement != NULL);
    stream = fmemopen(replacement, capacity, "w+");
    CHECK(stream != NULL);
    CHECK(ctos_ssh_hostfile_prepare_changed(fixture.view, fixture.role, "ssh/known_hosts",
        "example.invalid", fixture.host_key, "native-candidate", stream, replacement,
        capacity, fixture.callback_token, fixture.callback.environment, &issue) != 0);
    CHECK(fclose(stream) == 0);
    CHECK(fixture.callback.changed_candidate_calls == 0U
        && fixture.callback.changed_review_calls == 0U && fixture.callback.selected_calls == 0U);
    free(replacement);
    cleanup_fixture(&fixture, 1);
    sshkey_free(old_key);
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

static void setup_keyboard_fixture_before_auth_mode(struct fixture *fixture, int new_host,
    enum ctos_ssh_native_host_key_mode mode, int automatic, unsigned int methods) {
    memset(fixture, 0, sizeof(*fixture));
    fixture->keyboard_expected = 1;
    fixture->automatic_selection = automatic;
    fixture->automatic_methods = methods;
    fixture->pair[0] = fixture->pair[1] = -1;
    fixture->callback.release_fd = -1;
    CHECK(sshkey_generate(KEY_ED25519, 0, &fixture->private_key) == 0);
    CHECK(sshkey_from_private(fixture->private_key, &fixture->public_key) == 0);
    fixture->host_key = fixture->public_key;
    finish_fixture_setup_selected_mode(fixture, 60000U, NULL, NULL, new_host, 0, 3U, 0, mode);
    CHECK(ctos_ssh_native_client_begin_keyboard_interactive_auth(fixture->client) != 0 && errno == EACCES);
    finish_service_fixture(fixture, new_host, 0);
    CHECK(ctos_ssh_native_client_begin_keyboard_interactive_auth(fixture->client) != 0 && errno == EACCES);
    CHECK(ctos_ssh_native_client_begin_userauth_service(fixture->client) == 0);
    drive_service(fixture, 0);
    enum ctos_ssh_native_client_keyboard_interactive_auth_result result;
    CHECK(ctos_ssh_native_client_keyboard_interactive_auth_result(fixture->client, &result) == 0
        && result == CTOS_SSH_KEYBOARD_AUTH_NOT_STARTED);
    CHECK(ctos_ssh_native_client_begin_password_auth(fixture->client) != 0 && errno == EACCES);
    CHECK(ctos_ssh_native_client_begin_private_key_auth(fixture->client) != 0 && errno == EACCES);
}

static void setup_keyboard_fixture_before_auth(struct fixture *fixture, int new_host,
    enum ctos_ssh_native_host_key_mode mode) {
    setup_keyboard_fixture_before_auth_mode(fixture, new_host, mode, 0, 0);
}

static void start_keyboard_fixture(struct fixture *fixture) {
    CHECK(ctos_ssh_native_client_begin_keyboard_interactive_auth(fixture->client) == 0);
    CHECK(ctos_ssh_native_client_begin_keyboard_interactive_auth(fixture->client) != 0 && errno == EALREADY);
    unsigned char output[32]; size_t length = 99;
    CHECK(ctos_ssh_native_client_selected_credential_bytes(fixture->client, output, sizeof(output), &length) != 0
        && errno == (fixture->automatic_selection ? EACCES : EALREADY)
        && length == 0 && fixture->callback.selected_calls == 0);
    for (unsigned int turn = 0; turn < 100000U; turn++) {
        CHECK(shell_turn(fixture) == 0);
        if (fixture->keyboard_requests == 1U) return;
    }
    CHECK(0);
}

static void setup_keyboard_fixture(struct fixture *fixture, int new_host,
    enum ctos_ssh_native_host_key_mode mode) {
    setup_keyboard_fixture_before_auth(fixture, new_host, mode);
    start_keyboard_fixture(fixture);
}

static void keyboard_result(struct fixture *fixture,
    enum ctos_ssh_native_client_keyboard_interactive_auth_result expected, int error) {
    for (unsigned int turn = 0; turn < 200000U; turn++) {
        enum ctos_ssh_native_client_keyboard_interactive_auth_result result = CTOS_SSH_KEYBOARD_AUTH_SUCCESS;
        int status = shell_turn(fixture), saved = errno;
        if (status != 0) {
            CHECK(error != 0 && saved == error);
            CHECK(ctos_ssh_native_client_keyboard_interactive_auth_result(fixture->client, &result) != 0
                && result == CTOS_SSH_KEYBOARD_AUTH_NOT_STARTED);
            return;
        }
        CHECK(ctos_ssh_native_client_keyboard_interactive_auth_result(fixture->client, &result) == 0);
        if (error != 0 && result == CTOS_SSH_KEYBOARD_AUTH_CHALLENGE) continue;
        if (result != CTOS_SSH_KEYBOARD_AUTH_PENDING) {
            CHECK(error == 0 && result == expected);
            CHECK(fixture->callback.selected_calls == (fixture->automatic_selection && fixture->private_requests ? 1U : 0U)
                && fixture->callback.credential_calls == 0
                && fixture->callback.credential_bytes_calls == 0);
            return;
        }
    }
    CHECK(0);
}

static void keyboard_challenge_packet(struct fixture *fixture, unsigned int count, unsigned int variant) {
    unsigned char long_text[4097];
    memset(long_text, 'p', sizeof(long_text));
    CHECK(sshpkt_start(fixture->server, SSH2_MSG_USERAUTH_INFO_REQUEST) == 0);
    if (variant == 1U) CHECK(sshpkt_put_string(fixture->server, "\xc0\xaf", 2U) == 0);
    else if (variant == 5U) CHECK(sshpkt_put_string(fixture->server, long_text, sizeof(long_text)) == 0);
    else CHECK(sshpkt_put_cstring(fixture->server, "服务器\033Name") == 0);
    if (variant != 6U) {
        CHECK(sshpkt_put_cstring(fixture->server, "第一行\n第二行\t\001") == 0);
        CHECK(sshpkt_put_cstring(fixture->server, "") == 0);
        CHECK(sshpkt_put_u32(fixture->server, variant == 4U ? 17U : count) == 0);
        for (unsigned int index = 0; index < count; index++) {
            if (variant == 2U) CHECK(sshpkt_put_cstring(fixture->server, "") == 0);
            else if (variant == 8U) CHECK(sshpkt_put_string(fixture->server, long_text, 4096U) == 0);
            else CHECK(sshpkt_put_cstring(fixture->server, index % 2 ? "Code: " : "Password: ") == 0);
            CHECK(sshpkt_put_u8(fixture->server, variant == 3U ? 2U : variant == 9U ? 255U : index % 2) == 0);
        }
        if (variant == 7U) CHECK(sshpkt_put_u8(fixture->server, 0) == 0);
    }
    CHECK(sshpkt_send(fixture->server) == 0);
    fixture->keyboard_prompt_count = count;
}

static struct wire keyboard_response_wire(struct fixture *fixture, uint32_t round) {
    struct wire wire = {0};
    wire_u32(&wire, 0x43534b52U); wire_u32(&wire, 0x00010000U);
    wire_u32(&wire, round); wire_u32(&wire, fixture->keyboard_prompt_count);
    for (uint32_t index = 0; index < fixture->keyboard_prompt_count; index++) {
        wire_u32(&wire, (uint32_t)fixture->keyboard_answers[index].length);
        wire_put(&wire, fixture->keyboard_answers[index].data, fixture->keyboard_answers[index].length);
    }
    return wire;
}

static void keyboard_answer(struct fixture *fixture, uint32_t round) {
    struct wire wire = keyboard_response_wire(fixture, round);
    unsigned int before = fixture->keyboard_responses;
    CHECK(ctos_ssh_native_client_respond_keyboard_interactive(fixture->client, wire.bytes, wire.length) == 0);
    CHECK(ctos_ssh_native_client_respond_keyboard_interactive(fixture->client, wire.bytes, wire.length) != 0
        && errno == EACCES);
    memset(&wire, 0, sizeof(wire));
    for (unsigned int turn = 0; turn < 100000U; turn++) {
        CHECK(shell_turn(fixture) == 0);
        if (fixture->keyboard_responses == before + 1U) return;
    }
    CHECK(0);
}

static void keyboard_finish_packet(struct fixture *fixture, unsigned int outcome) {
    CHECK(sshpkt_start(fixture->server, outcome == 2U ? SSH2_MSG_USERAUTH_SUCCESS : SSH2_MSG_USERAUTH_FAILURE) == 0);
    if (outcome != 2U) {
        CHECK(sshpkt_put_cstring(fixture->server, "keyboard-interactive") == 0);
        CHECK(sshpkt_put_u8(fixture->server, outcome == 4U) == 0);
    }
    CHECK(sshpkt_send(fixture->server) == 0);
}

static void test_keyboard_auth_discovery_case(int automatic) {
    struct fixture fixture;
    setup_keyboard_fixture_before_auth_mode(&fixture, 0, CTOS_SSH_HOST_KEY_MANAGED, automatic, 6U);
    if (automatic) CHECK(ctos_ssh_native_client_begin_keyboard_interactive_auth(fixture.client) != 0 && errno == EACCES);
    fixture.discovery_expected = 1; fixture.discovery_methods = "keyboard-interactive";
    CHECK(ctos_ssh_native_client_begin_auth_discovery(fixture.client) == 0);
    enum ctos_ssh_native_client_discovery_result result = CTOS_SSH_DISCOVERY_PENDING;
    unsigned int methods = 0;
    for (unsigned int turn = 0; turn < 100000U && result == CTOS_SSH_DISCOVERY_PENDING; turn++) {
        CHECK(shell_turn(&fixture) == 0);
        CHECK(ctos_ssh_native_client_auth_discovery_result(fixture.client, &result, &methods) == 0);
    }
    CHECK(result == CTOS_SSH_DISCOVERY_METHODS && methods == CTOS_SSH_DISCOVERY_KEYBOARD_INTERACTIVE
        && fixture.callback.selected_calls == 0);
    start_keyboard_fixture(&fixture);
    keyboard_challenge_packet(&fixture, 1, 0);
    keyboard_result(&fixture, CTOS_SSH_KEYBOARD_AUTH_CHALLENGE, 0);
    fixture.keyboard_answers[0] = (struct ctos_ssh_slice){(const unsigned char *)"", 0};
    keyboard_answer(&fixture, 1);
    keyboard_finish_packet(&fixture, 2);
    keyboard_result(&fixture, CTOS_SSH_KEYBOARD_AUTH_SUCCESS, 0);
    CHECK(fixture.keyboard_responses == 1 && fixture.callback.selected_calls == 0);
    cleanup_fixture(&fixture, 1);
}

static void test_keyboard_auth_discovery(void) {
    test_keyboard_auth_discovery_case(0);
    test_keyboard_auth_discovery_case(1);
    {
        struct fixture fixture;
        setup_keyboard_fixture_before_auth_mode(&fixture, 0, CTOS_SSH_HOST_KEY_PINNED, 1, 0);
        fixture.discovery_expected = 1; fixture.discovery_reply = 1;
        CHECK(ctos_ssh_native_client_begin_keyboard_interactive_auth(fixture.client) != 0 && errno == EACCES);
        CHECK(ctos_ssh_native_client_begin_auth_discovery(fixture.client) == 0);
        enum ctos_ssh_native_client_discovery_result result = CTOS_SSH_DISCOVERY_PENDING;
        unsigned int methods = 99U;
        for (unsigned int turn = 0; turn < 100000U && result == CTOS_SSH_DISCOVERY_PENDING; turn++) {
            CHECK(shell_turn(&fixture) == 0);
            CHECK(ctos_ssh_native_client_auth_discovery_result(fixture.client, &result, &methods) == 0);
        }
        CHECK(result == CTOS_SSH_DISCOVERY_NONE_SUCCESS && methods == 0 && fixture.callback.selected_calls == 0);
        fixture.shell_expected = 1; fixture.shell_window = 65536U; fixture.shell_maxpacket = 32768U;
        CHECK(ctos_ssh_native_client_begin_shell(fixture.client, 80, 24) == 0);
        drive_shell_phase(&fixture, CTOS_SSH_SHELL_RUNNING);
        CHECK(fixture.shell_stage == 3 && fixture.callback.selected_calls == 0);
        cleanup_fixture(&fixture, 1);
    }
}

static void test_discovered_keyboard_with_selected_key(void) {
    struct fixture fixture;
    struct sshkey *key = NULL; unsigned char *blob = NULL; size_t blob_length = 0;
    struct sshbuf *file = sshbuf_new();
    CHECK(file != NULL && sshkey_generate(KEY_ED25519, 0, &key) == 0);
    CHECK(sshkey_to_blob(key, &blob, &blob_length) == 0);
    CHECK(sshkey_private_to_fileblob(key, file, "", "discovered-keyboard", SSHKEY_PRIVATE_OPENSSH, NULL, 0) == 0);
    setup_private_fixture_version_mode(&fixture, 0, key, file, blob, blob_length, NULL, 0, 5,
        CTOS_SSH_HOST_KEY_PINNED);
    fixture.discovery_expected = 1; fixture.discovery_methods = "keyboard-interactive";
    CHECK(ctos_ssh_native_client_begin_auth_discovery(fixture.client) == 0);
    enum ctos_ssh_native_client_discovery_result result = CTOS_SSH_DISCOVERY_PENDING;
    unsigned int methods = 0;
    for (unsigned int turn = 0; turn < 100000U && result == CTOS_SSH_DISCOVERY_PENDING; turn++) {
        CHECK(shell_turn(&fixture) == 0);
        CHECK(ctos_ssh_native_client_auth_discovery_result(fixture.client, &result, &methods) == 0);
    }
    CHECK(result == CTOS_SSH_DISCOVERY_METHODS && methods == CTOS_SSH_DISCOVERY_KEYBOARD_INTERACTIVE);
    unsigned char plan[32], stage[92]; memcpy(plan, fixture.selection_receipt, sizeof(plan)); plan[0] ^= 1U;
    memset(stage, 0xa5, sizeof(stage));
    CHECK(ctos_ssh_native_client_begin_discovered_keyboard(fixture.client, plan, stage) != 0 && errno == EACCES);
    for (size_t i = 0; i < sizeof(stage); i++) CHECK(stage[i] == 0);
    memcpy(plan, fixture.selection_receipt, sizeof(plan));
    fixture.private_expected = 0; fixture.keyboard_expected = 1;
    CHECK(ctos_ssh_native_client_begin_discovered_keyboard(fixture.client, plan, stage) == 0);
    CHECK(read_u32(stage) == 0x43534150U && read_u32(stage + 4U) == 0x00020000U
        && read_u32(stage + 8U) == 2U && read_u32(stage + 12U) == 4U && read_u32(stage + 16U) == 3U);
    CHECK(memcmp(stage + 20U, plan, 32U) == 0 && fixture.callback.selected_calls == 0);
    CHECK(ctos_ssh_native_client_begin_discovered_keyboard(fixture.client, plan, stage) != 0);
    for (unsigned int turn = 0; turn < 100000U && fixture.keyboard_requests == 0; turn++) CHECK(shell_turn(&fixture) == 0);
    CHECK(fixture.keyboard_requests == 1U && fixture.private_requests == 0 && fixture.callback.selected_calls == 0);
    keyboard_challenge_packet(&fixture, 1, 0);
    keyboard_result(&fixture, CTOS_SSH_KEYBOARD_AUTH_CHALLENGE, 0);
    fixture.keyboard_answers[0] = (struct ctos_ssh_slice){(const unsigned char *)"", 0};
    keyboard_answer(&fixture, 1); keyboard_finish_packet(&fixture, 2);
    keyboard_result(&fixture, CTOS_SSH_KEYBOARD_AUTH_SUCCESS, 0);
    CHECK(fixture.keyboard_responses == 1 && fixture.callback.selected_calls == 0);
    cleanup_fixture(&fixture, 1); sshbuf_free(file); free(blob); sshkey_free(key);
}

struct prompt_wrong_owner_attempt {
    struct ctos_ssh_native_client *client;
    unsigned char plan[32];
    int result, error;
};

static void *prompt_wrong_owner(void *context) {
    struct prompt_wrong_owner_attempt *attempt = context;
    errno = 0;
    attempt->result = ctos_ssh_native_client_poll_auth_prompt(attempt->client, attempt->plan,
        CTOS_SSH_DISCOVERY_PASSWORD);
    attempt->error = errno;
    return NULL;
}

static void test_discovered_password(void) {
    static const unsigned char password[] = "selected-password";
    for (int selected_key = 0; selected_key <= 1; selected_key++) {
        for (int outcome = 0; outcome <= 2; outcome++) {
            struct fixture fixture;
            struct sshkey *key = NULL; unsigned char *blob = NULL; size_t blob_length = 0;
            struct sshbuf *file = NULL;
            if (selected_key) {
                file = sshbuf_new();
                CHECK(file != NULL && sshkey_generate(KEY_ED25519, 0, &key) == 0);
                CHECK(sshkey_to_blob(key, &blob, &blob_length) == 0);
                CHECK(sshkey_private_to_fileblob(key, file, "", "auto-password", SSHKEY_PRIVATE_OPENSSH, NULL, 0) == 0);
                setup_private_fixture_version_mode(&fixture, 0, key, file, blob, blob_length, NULL, 0, 5,
                    CTOS_SSH_HOST_KEY_PINNED);
            } else {
                setup_keyboard_fixture_before_auth_mode(&fixture, 0, CTOS_SSH_HOST_KEY_MANAGED, 1, 6U);
                fixture.password_username = (const unsigned char *)" 用户😀 ";
                fixture.password_username_length = sizeof(" 用户😀 ") - 1U;
            }
            unsigned char plan[32], stage[92];
            memcpy(plan, fixture.selection_receipt, sizeof(plan));
            CHECK(ctos_ssh_native_client_begin_discovered_password(fixture.client, plan, password,
                sizeof(password) - 1U, stage) != 0 && errno == EACCES);
            fixture.discovery_expected = 1; fixture.discovery_methods = "password";
            CHECK(ctos_ssh_native_client_begin_auth_discovery(fixture.client) == 0);
            enum ctos_ssh_native_client_discovery_result discovery = CTOS_SSH_DISCOVERY_PENDING;
            unsigned int methods = 0;
            for (unsigned int turn = 0; turn < 100000U && discovery == CTOS_SSH_DISCOVERY_PENDING; turn++) {
                CHECK(shell_turn(&fixture) == 0);
                CHECK(ctos_ssh_native_client_auth_discovery_result(fixture.client, &discovery, &methods) == 0);
            }
            CHECK(discovery == CTOS_SSH_DISCOVERY_METHODS && methods == CTOS_SSH_DISCOVERY_PASSWORD);
            CHECK(ctos_ssh_native_client_step(fixture.client,
                &(struct ctos_ssh_native_client_step){0}) != 0 && errno == EALREADY);
            struct prompt_wrong_owner_attempt attempt = {.client = fixture.client};
            memcpy(attempt.plan, plan, sizeof(plan)); pthread_t thread;
            CHECK(pthread_create(&thread, NULL, prompt_wrong_owner, &attempt) == 0);
            CHECK(pthread_join(thread, NULL) == 0 && attempt.result != 0 && attempt.error == EPERM);
            CHECK(ctos_ssh_native_client_poll_auth_prompt(fixture.client, plan, CTOS_SSH_DISCOVERY_PUBLICKEY) != 0
                && errno == EACCES);
            for (int turn = 0; turn < 3; turn++)
                CHECK(ctos_ssh_native_client_poll_auth_prompt(fixture.client, plan, CTOS_SSH_DISCOVERY_PASSWORD) == 0);
            CHECK(fixture.callback.selected_calls == 0U);
            plan[0] ^= 1U; memset(stage, 0xa5, sizeof(stage));
            CHECK(ctos_ssh_native_client_begin_discovered_password(fixture.client, plan, password,
                sizeof(password) - 1U, stage) != 0 && errno == EACCES);
            for (size_t i = 0; i < sizeof(stage); i++) CHECK(stage[i] == 0);
            plan[0] ^= 1U;
            CHECK(ctos_ssh_native_client_begin_discovered_password(fixture.client, plan, password, 0, stage) != 0
                && errno == EINVAL);
            fixture.private_expected = 0; fixture.keyboard_expected = 0; fixture.password_expected = 1;
            fixture.callback.selected_body_override = 0;
            fixture.password_methods = (const unsigned char *)"keyboard-interactive";
            fixture.password_methods_length = sizeof("keyboard-interactive") - 1U;
            fixture.password_reply = (enum password_reply)outcome;
            CHECK(ctos_ssh_native_client_begin_discovered_password(fixture.client, plan, password,
                sizeof(password) - 1U, stage) == 0);
            unsigned int next_methods = 99U;
            CHECK(ctos_ssh_native_client_auth_continuation_methods(fixture.client, &next_methods) != 0
                && errno == EACCES && next_methods == 0U);
            CHECK(read_u32(stage) == 0x43534150U && read_u32(stage + 4U) == 0x00020000U
                && read_u32(stage + 8U) == 2U && read_u32(stage + 12U) == (selected_key ? 4U : 3U)
                && read_u32(stage + 16U) == 1U && memcmp(stage + 20U, plan, 32U) == 0);
            CHECK(ctos_ssh_native_client_begin_discovered_password(fixture.client, plan, password,
                sizeof(password) - 1U, stage) != 0);
            enum ctos_ssh_native_client_password_auth_result result = CTOS_SSH_PASSWORD_AUTH_PENDING;
            for (unsigned int turn = 0; turn < 100000U && result == CTOS_SSH_PASSWORD_AUTH_PENDING; turn++) {
                CHECK(shell_turn(&fixture) == 0);
                CHECK(ctos_ssh_native_client_password_auth_result(fixture.client, &result) == 0);
            }
            CHECK(result == (enum ctos_ssh_native_client_password_auth_result)(outcome + 2));
            CHECK(fixture.password_requests == 1U && fixture.callback.selected_calls == 0 && fixture.private_requests == 0);
            next_methods = 99U;
            if (outcome != 0) CHECK(ctos_ssh_native_client_auth_continuation_methods(fixture.client, &next_methods) == 0
                && next_methods == CTOS_SSH_DISCOVERY_KEYBOARD_INTERACTIVE);
            else CHECK(ctos_ssh_native_client_auth_continuation_methods(fixture.client, &next_methods) != 0
                && errno == EACCES && next_methods == 0U);
            if (outcome != 0) CHECK(ctos_ssh_native_client_begin_shell(fixture.client, 80, 24) != 0 && errno == EACCES);
            if (outcome != 0) {
                fixture.password_expected = 0; fixture.keyboard_expected = 1;
                CHECK(ctos_ssh_native_client_continue_keyboard_interactive_auth(fixture.client, plan, stage) == 0);
                CHECK(read_u32(stage + 4U) == 0x00020000U && read_u32(stage + 8U) == 3U
                    && read_u32(stage + 12U) == (selected_key ? 4U : 3U) && read_u32(stage + 16U) == 3U);
                CHECK(ctos_ssh_native_client_continue_keyboard_interactive_auth(fixture.client, plan, stage) != 0);
                for (unsigned int turn = 0; turn < 100000U && fixture.keyboard_requests == 0; turn++) CHECK(shell_turn(&fixture) == 0);
                CHECK(fixture.keyboard_requests == 1U && fixture.callback.selected_calls == 0U);
                keyboard_challenge_packet(&fixture, 1, 0);
                keyboard_result(&fixture, CTOS_SSH_KEYBOARD_AUTH_CHALLENGE, 0);
                fixture.keyboard_answers[0] = (struct ctos_ssh_slice){(const unsigned char *)"", 0};
                keyboard_answer(&fixture, 1); keyboard_finish_packet(&fixture, 2);
                keyboard_result(&fixture, CTOS_SSH_KEYBOARD_AUTH_SUCCESS, 0);
                CHECK(fixture.keyboard_responses == 1U && fixture.callback.selected_calls == 0U);
            } else {
                CHECK(ctos_ssh_native_client_continue_keyboard_interactive_auth(fixture.client, plan, stage) != 0);
            }
            cleanup_fixture(&fixture, 1);
            sshbuf_free(file); free(blob); sshkey_free(key);
        }
    }
}

static void test_discovered_key_then_keyboard(void) {
    struct fixture fixture;
    struct sshkey *key = NULL; unsigned char *blob = NULL; size_t blob_length = 0;
    struct sshbuf *file = sshbuf_new();
    CHECK(file != NULL && sshkey_generate(KEY_ED25519, 0, &key) == 0);
    CHECK(sshkey_to_blob(key, &blob, &blob_length) == 0);
    CHECK(sshkey_private_to_fileblob(key, file, "", "auto-key-mfa", SSHKEY_PRIVATE_OPENSSH, NULL, 0) == 0);
    setup_private_fixture_version_mode(&fixture, 0, key, file, blob, blob_length, NULL, 0, 5,
        CTOS_SSH_HOST_KEY_PINNED);
    fixture.discovery_expected = 1; fixture.discovery_methods = "publickey";
    CHECK(ctos_ssh_native_client_begin_auth_discovery(fixture.client) == 0);
    enum ctos_ssh_native_client_discovery_result discovery = CTOS_SSH_DISCOVERY_PENDING;
    unsigned int methods = 0;
    for (unsigned int turn = 0; turn < 100000U && discovery == CTOS_SSH_DISCOVERY_PENDING; turn++) {
        CHECK(shell_turn(&fixture) == 0);
        CHECK(ctos_ssh_native_client_auth_discovery_result(fixture.client, &discovery, &methods) == 0);
    }
    CHECK(discovery == CTOS_SSH_DISCOVERY_METHODS && methods == CTOS_SSH_DISCOVERY_PUBLICKEY);
    for (int turn = 0; turn < 3; turn++) CHECK(ctos_ssh_native_client_poll_auth_prompt(fixture.client,
        fixture.selection_receipt, CTOS_SSH_DISCOVERY_PUBLICKEY) == 0);
    fixture.password_reply = PASSWORD_REPLY_PARTIAL;
    fixture.password_methods = (const unsigned char *)"keyboard-interactive";
    fixture.password_methods_length = sizeof("keyboard-interactive") - 1U;
    CHECK(ctos_ssh_native_client_begin_private_key_auth(fixture.client) == 0);
    drive_private(&fixture, CTOS_SSH_PRIVATE_KEY_AUTH_PARTIAL, 0);
    unsigned int next_methods = 0;
    CHECK(ctos_ssh_native_client_auth_continuation_methods(fixture.client, &next_methods) == 0
        && next_methods == CTOS_SSH_DISCOVERY_KEYBOARD_INTERACTIVE);
    unsigned char stage[92];
    CHECK(ctos_ssh_native_client_continue_keyboard_interactive_auth(fixture.client, fixture.selection_receipt, stage) == 0);
    CHECK(read_u32(stage + 4U) == 0x00020000U && read_u32(stage + 8U) == 2U
        && read_u32(stage + 12U) == 4U && read_u32(stage + 16U) == 3U);
    fixture.private_expected = 0; fixture.keyboard_expected = 1;
    for (unsigned int turn = 0; turn < 100000U && fixture.keyboard_requests == 0; turn++) CHECK(shell_turn(&fixture) == 0);
    keyboard_challenge_packet(&fixture, 1, 0); keyboard_result(&fixture, CTOS_SSH_KEYBOARD_AUTH_CHALLENGE, 0);
    fixture.keyboard_answers[0] = (struct ctos_ssh_slice){(const unsigned char *)"", 0};
    keyboard_answer(&fixture, 1); keyboard_finish_packet(&fixture, 2);
    keyboard_result(&fixture, CTOS_SSH_KEYBOARD_AUTH_SUCCESS, 0);
    CHECK(fixture.callback.selected_calls == 1U && fixture.keyboard_responses == 1U);
    cleanup_fixture(&fixture, 1); sshbuf_free(file); free(blob); sshkey_free(key);
}

static void test_discovered_rejected_key_alternatives(void) {
    static const unsigned char password[] = "selected-password";
    const char *alternatives[] = {"publickey", "password", "keyboard-interactive"};
    for (int mode = 0; mode < 3; mode++) {
        struct fixture fixture;
        struct sshkey *key = NULL; unsigned char *blob = NULL; size_t blob_length = 0;
        struct sshbuf *file = sshbuf_new();
        CHECK(file != NULL && sshkey_generate(KEY_ED25519, 0, &key) == 0);
        CHECK(sshkey_to_blob(key, &blob, &blob_length) == 0);
        CHECK(sshkey_private_to_fileblob(key, file, "", "rejected-key", SSHKEY_PRIVATE_OPENSSH, NULL, 0) == 0);
        setup_private_fixture_version_mode(&fixture, 0, key, file, blob, blob_length, NULL, 0, 5,
            CTOS_SSH_HOST_KEY_PINNED);
        fixture.discovery_expected = 1; fixture.discovery_methods = "publickey,password,keyboard-interactive";
        CHECK(ctos_ssh_native_client_begin_auth_discovery(fixture.client) == 0);
        enum ctos_ssh_native_client_discovery_result discovery = CTOS_SSH_DISCOVERY_PENDING;
        unsigned int methods = 0;
        for (unsigned int turn = 0; turn < 100000U && discovery == CTOS_SSH_DISCOVERY_PENDING; turn++) {
            CHECK(shell_turn(&fixture) == 0);
            CHECK(ctos_ssh_native_client_auth_discovery_result(fixture.client, &discovery, &methods) == 0);
        }
        CHECK(discovery == CTOS_SSH_DISCOVERY_METHODS && methods == 7U);
        fixture.password_reply = PASSWORD_REPLY_REJECTED;
        fixture.password_methods = (const unsigned char *)alternatives[mode];
        fixture.password_methods_length = strlen(alternatives[mode]);
        CHECK(ctos_ssh_native_client_begin_private_key_auth(fixture.client) == 0);
        drive_private(&fixture, CTOS_SSH_PRIVATE_KEY_AUTH_REJECTED, 0);
        CHECK(ctos_ssh_native_client_auth_continuation_methods(fixture.client, &methods) == 0
            && methods == (mode == 0 ? CTOS_SSH_DISCOVERY_PUBLICKEY : mode == 1
                ? CTOS_SSH_DISCOVERY_PASSWORD : CTOS_SSH_DISCOVERY_KEYBOARD_INTERACTIVE));
        CHECK(ctos_ssh_native_client_begin_shell(fixture.client, 80, 24) != 0 && errno == EACCES);
        unsigned char stage[92];
        CHECK(ctos_ssh_native_client_continue_private_key_auth(fixture.client, fixture.selection_receipt, stage) != 0
            && errno == EACCES);
        if (mode != 1) CHECK(ctos_ssh_native_client_poll_auth_prompt(fixture.client, fixture.selection_receipt,
            CTOS_SSH_DISCOVERY_PASSWORD) != 0 && errno == EACCES);
        if (mode != 2) CHECK(ctos_ssh_native_client_continue_keyboard_interactive_auth(fixture.client,
            fixture.selection_receipt, stage) != 0 && errno == EACCES);
        fixture.private_expected = 0;
        if (mode == 1) {
            CHECK(ctos_ssh_native_client_poll_auth_prompt(fixture.client, fixture.selection_receipt,
                CTOS_SSH_DISCOVERY_PASSWORD) == 0);
            fixture.password_expected = 1; fixture.password_reply = PASSWORD_REPLY_SUCCESS;
            fixture.callback.selected_body_override = 0;
            CHECK(ctos_ssh_native_client_begin_discovered_password(fixture.client, fixture.selection_receipt,
                password, sizeof(password) - 1U, stage) == 0);
            enum ctos_ssh_native_client_password_auth_result result = CTOS_SSH_PASSWORD_AUTH_PENDING;
            for (unsigned int turn = 0; turn < 100000U && result == CTOS_SSH_PASSWORD_AUTH_PENDING; turn++) {
                CHECK(shell_turn(&fixture) == 0);
                CHECK(ctos_ssh_native_client_password_auth_result(fixture.client, &result) == 0);
            }
            CHECK(result == CTOS_SSH_PASSWORD_AUTH_SUCCESS && fixture.password_requests == 1U);
        } else if (mode == 2) {
            fixture.keyboard_expected = 1;
            CHECK(ctos_ssh_native_client_continue_keyboard_interactive_auth(fixture.client,
                fixture.selection_receipt, stage) == 0);
            for (unsigned int turn = 0; turn < 100000U && fixture.keyboard_requests == 0; turn++)
                CHECK(shell_turn(&fixture) == 0);
            keyboard_challenge_packet(&fixture, 1, 0); keyboard_result(&fixture, CTOS_SSH_KEYBOARD_AUTH_CHALLENGE, 0);
            fixture.keyboard_answers[0] = (struct ctos_ssh_slice){(const unsigned char *)"", 0};
            keyboard_answer(&fixture, 1); keyboard_finish_packet(&fixture, 2);
            keyboard_result(&fixture, CTOS_SSH_KEYBOARD_AUTH_SUCCESS, 0);
            CHECK(fixture.keyboard_requests == 1U && fixture.keyboard_responses == 1U);
        }
        CHECK(fixture.callback.selected_calls == 1U && fixture.private_requests == 1U);
        cleanup_fixture(&fixture, 1); sshbuf_free(file); free(blob); sshkey_free(key);
    }
}

static void test_discovered_keyboard_then_password_or_key(void) {
    static const unsigned char password[] = "selected-password";
    for (int mode = 0; mode < 3; mode++) for (int partial = 0; partial <= 1; partial++) {
        int selected_key = mode != 0, next_key = mode == 2;
        struct fixture fixture;
        struct sshkey *key = NULL; unsigned char *blob = NULL; size_t blob_length = 0;
        struct sshbuf *file = NULL;
        if (selected_key) {
            file = sshbuf_new();
            CHECK(file != NULL && sshkey_generate(KEY_ED25519, 0, &key) == 0);
            CHECK(sshkey_to_blob(key, &blob, &blob_length) == 0);
            CHECK(sshkey_private_to_fileblob(key, file, "", "auto-kbi-first", SSHKEY_PRIVATE_OPENSSH, NULL, 0) == 0);
            setup_private_fixture_version_mode(&fixture, 0, key, file, blob, blob_length, NULL, 0, 5,
                CTOS_SSH_HOST_KEY_PINNED);
        } else {
            setup_keyboard_fixture_before_auth_mode(&fixture, 0, CTOS_SSH_HOST_KEY_MANAGED, 1, 6U);
            fixture.password_username = (const unsigned char *)" 用户😀 ";
            fixture.password_username_length = sizeof(" 用户😀 ") - 1U;
        }
        fixture.discovery_expected = 1; fixture.discovery_methods = "keyboard-interactive";
        CHECK(ctos_ssh_native_client_begin_auth_discovery(fixture.client) == 0);
        enum ctos_ssh_native_client_discovery_result discovery = CTOS_SSH_DISCOVERY_PENDING;
        unsigned int methods = 0;
        for (unsigned int turn = 0; turn < 100000U && discovery == CTOS_SSH_DISCOVERY_PENDING; turn++) {
            CHECK(shell_turn(&fixture) == 0);
            CHECK(ctos_ssh_native_client_auth_discovery_result(fixture.client, &discovery, &methods) == 0);
        }
        CHECK(discovery == CTOS_SSH_DISCOVERY_METHODS && methods == CTOS_SSH_DISCOVERY_KEYBOARD_INTERACTIVE);
        unsigned char stage[92];
        fixture.private_expected = 0; fixture.keyboard_expected = 1;
        if (selected_key) CHECK(ctos_ssh_native_client_begin_discovered_keyboard(fixture.client,
            fixture.selection_receipt, stage) == 0);
        else CHECK(ctos_ssh_native_client_begin_keyboard_interactive_auth(fixture.client) == 0);
        for (unsigned int turn = 0; turn < 100000U && fixture.keyboard_requests == 0; turn++) CHECK(shell_turn(&fixture) == 0);
        keyboard_challenge_packet(&fixture, 1, 0); keyboard_result(&fixture, CTOS_SSH_KEYBOARD_AUTH_CHALLENGE, 0);
        fixture.keyboard_answers[0] = (struct ctos_ssh_slice){(const unsigned char *)"", 0};
        keyboard_answer(&fixture, 1);
        CHECK(sshpkt_start(fixture.server, SSH2_MSG_USERAUTH_FAILURE) == 0);
        CHECK(sshpkt_put_cstring(fixture.server, next_key ? "publickey" : "password") == 0);
        CHECK(sshpkt_put_u8(fixture.server, partial) == 0 && sshpkt_send(fixture.server) == 0);
        keyboard_result(&fixture, partial ? CTOS_SSH_KEYBOARD_AUTH_PARTIAL : CTOS_SSH_KEYBOARD_AUTH_REJECTED, 0);
        CHECK(ctos_ssh_native_client_auth_continuation_methods(fixture.client, &methods) == 0
            && methods == (next_key ? CTOS_SSH_DISCOVERY_PUBLICKEY : CTOS_SSH_DISCOVERY_PASSWORD));
        CHECK(ctos_ssh_native_client_step(fixture.client,
            &(struct ctos_ssh_native_client_step){0}) != 0 && errno == EALREADY);
        for (int turn = 0; turn < 3; turn++) CHECK(ctos_ssh_native_client_poll_auth_prompt(fixture.client,
            fixture.selection_receipt, methods) == 0);
        CHECK(ctos_ssh_native_client_begin_shell(fixture.client, 80, 24) != 0 && errno == EACCES);
        fixture.keyboard_expected = 0;
        if (next_key) {
            CHECK(ctos_ssh_native_client_begin_discovered_password(fixture.client, fixture.selection_receipt,
                password, sizeof(password) - 1U, stage) != 0 && errno == EACCES);
            fixture.private_expected = 1;
            CHECK(ctos_ssh_native_client_continue_private_key_auth(fixture.client, fixture.selection_receipt, stage) == 0);
            CHECK(read_u32(stage + 8U) == 3U && read_u32(stage + 12U) == 4U && read_u32(stage + 16U) == 4U);
            drive_private(&fixture, CTOS_SSH_PRIVATE_KEY_AUTH_SUCCESS, 0);
            CHECK(fixture.callback.selected_calls == 1U && fixture.private_requests == 1U);
        } else {
            if (selected_key) CHECK(ctos_ssh_native_client_continue_private_key_auth(fixture.client,
                fixture.selection_receipt, stage) != 0 && errno == EACCES);
            fixture.password_expected = 1; fixture.callback.selected_body_override = 0;
            CHECK(ctos_ssh_native_client_begin_discovered_password(fixture.client, fixture.selection_receipt,
                password, sizeof(password) - 1U, stage) == 0);
            CHECK(read_u32(stage + 8U) == (selected_key ? 3U : 2U) && read_u32(stage + 16U) == 1U);
            drive_password(&fixture, CTOS_SSH_PASSWORD_AUTH_SUCCESS, 0);
            CHECK(fixture.callback.selected_calls == 0U && fixture.password_requests == 1U);
        }
        cleanup_fixture(&fixture, 1); sshbuf_free(file); free(blob); sshkey_free(key);
    }
}

static void test_auth_prompt_transport(void) {
    for (int scenario = 0; scenario < 3; scenario++) {
        struct fixture fixture;
        setup_keyboard_fixture_before_auth_mode(&fixture, 0, CTOS_SSH_HOST_KEY_MANAGED, 1, 6U);
        fixture.discovery_expected = 1; fixture.discovery_methods = "password";
        CHECK(ctos_ssh_native_client_poll_auth_prompt(fixture.client, fixture.selection_receipt,
            CTOS_SSH_DISCOVERY_PASSWORD) != 0 && errno == EACCES);
        CHECK(ctos_ssh_native_client_begin_auth_discovery(fixture.client) == 0);
        enum ctos_ssh_native_client_discovery_result discovery = CTOS_SSH_DISCOVERY_PENDING;
        unsigned int methods = 0;
        for (unsigned int turn = 0; turn < 100000U && discovery == CTOS_SSH_DISCOVERY_PENDING; turn++) {
            CHECK(shell_turn(&fixture) == 0);
            CHECK(ctos_ssh_native_client_auth_discovery_result(fixture.client, &discovery, &methods) == 0);
        }
        CHECK(discovery == CTOS_SSH_DISCOVERY_METHODS && methods == CTOS_SSH_DISCOVERY_PASSWORD);
        unsigned char wrong[32]; memcpy(wrong, fixture.selection_receipt, sizeof(wrong)); wrong[0] ^= 1U;
        CHECK(ctos_ssh_native_client_poll_auth_prompt(fixture.client, wrong, CTOS_SSH_DISCOVERY_PASSWORD) != 0
            && errno == EACCES);
        if (scenario == 2) CHECK(shutdown(fixture.pair[1], SHUT_WR) == 0);
        else {
            CHECK(sshpkt_start(fixture.server, SSH2_MSG_USERAUTH_BANNER) == 0);
            CHECK(sshpkt_put_cstring(fixture.server, "test notice") == 0 && sshpkt_put_cstring(fixture.server, "") == 0);
            CHECK(sshpkt_send(fixture.server) == 0);
            for (int turn = 0; turn < 100; turn++) {
                CHECK(pump_synthetic_server(&fixture, 64U, NULL, NULL) == 0);
                CHECK(ctos_ssh_native_client_poll_auth_prompt(fixture.client, fixture.selection_receipt,
                    CTOS_SSH_DISCOVERY_PASSWORD) == 0);
            }
            CHECK(sshpkt_start(fixture.server, scenario == 0 ? SSH2_MSG_USERAUTH_SUCCESS : SSH2_MSG_DISCONNECT) == 0);
            if (scenario == 1) {
                CHECK(sshpkt_put_u32(fixture.server, 11U) == 0);
                CHECK(sshpkt_put_cstring(fixture.server, "test closure") == 0 && sshpkt_put_cstring(fixture.server, "") == 0);
            }
            CHECK(sshpkt_send(fixture.server) == 0);
        }
        int ended = 0;
        for (int turn = 0; turn < 10000 && !ended; turn++) {
            if (scenario != 2) CHECK(pump_synthetic_server(&fixture, 64U, NULL, NULL) == 0);
            errno = 0;
            if (ctos_ssh_native_client_poll_auth_prompt(fixture.client, fixture.selection_receipt,
                    CTOS_SSH_DISCOVERY_PASSWORD) != 0) {
                CHECK(errno == (scenario == 0 ? EPROTO : scenario == 1 ? ECONNRESET : EPIPE));
                ended = 1;
            }
        }
        CHECK(ended && fixture.callback.selected_calls == 0U && fixture.password_requests == 0U);
        CHECK(ctos_ssh_native_client_begin_shell(fixture.client, 80, 24) != 0);
        cleanup_fixture(&fixture, 1);
    }
}

static void test_discovered_three_methods(void) {
    static const unsigned char password[] = "selected-password";
    for (int keyboard_first = 0; keyboard_first <= 1; keyboard_first++) {
        struct fixture fixture;
        struct sshkey *key = NULL; unsigned char *blob = NULL; size_t blob_length = 0;
        struct sshbuf *file = sshbuf_new();
        CHECK(file != NULL && sshkey_generate(KEY_ED25519, 0, &key) == 0);
        CHECK(sshkey_to_blob(key, &blob, &blob_length) == 0);
        CHECK(sshkey_private_to_fileblob(key, file, "", "auto-three", SSHKEY_PRIVATE_OPENSSH, NULL, 0) == 0);
        setup_private_fixture_version_mode(&fixture, 0, key, file, blob, blob_length, NULL, 0, 5,
            CTOS_SSH_HOST_KEY_PINNED);
        fixture.discovery_expected = 1; fixture.discovery_methods = keyboard_first ? "keyboard-interactive" : "password";
        CHECK(ctos_ssh_native_client_begin_auth_discovery(fixture.client) == 0);
        enum ctos_ssh_native_client_discovery_result discovery = CTOS_SSH_DISCOVERY_PENDING;
        unsigned int methods = 0;
        for (unsigned int turn = 0; turn < 100000U && discovery == CTOS_SSH_DISCOVERY_PENDING; turn++) {
            CHECK(shell_turn(&fixture) == 0);
            CHECK(ctos_ssh_native_client_auth_discovery_result(fixture.client, &discovery, &methods) == 0);
        }
        CHECK(discovery == CTOS_SSH_DISCOVERY_METHODS);
        unsigned char stage[92];
        fixture.private_expected = 0;
        if (keyboard_first) {
            fixture.keyboard_expected = 1;
            CHECK(ctos_ssh_native_client_begin_discovered_keyboard(fixture.client, fixture.selection_receipt, stage) == 0);
            for (unsigned int turn = 0; turn < 100000U && fixture.keyboard_requests == 0; turn++) CHECK(shell_turn(&fixture) == 0);
            keyboard_challenge_packet(&fixture, 1, 0); keyboard_result(&fixture, CTOS_SSH_KEYBOARD_AUTH_CHALLENGE, 0);
            fixture.keyboard_answers[0] = (struct ctos_ssh_slice){(const unsigned char *)"", 0};
            keyboard_answer(&fixture, 1);
            CHECK(sshpkt_start(fixture.server, SSH2_MSG_USERAUTH_FAILURE) == 0);
            CHECK(sshpkt_put_cstring(fixture.server, "password") == 0);
            CHECK(sshpkt_put_u8(fixture.server, 1) == 0 && sshpkt_send(fixture.server) == 0);
            keyboard_result(&fixture, CTOS_SSH_KEYBOARD_AUTH_PARTIAL, 0);
            fixture.keyboard_expected = 0;
        }
        fixture.password_expected = 1; fixture.callback.selected_body_override = 0;
        fixture.password_reply = PASSWORD_REPLY_PARTIAL;
        fixture.password_methods = (const unsigned char *)"publickey";
        fixture.password_methods_length = sizeof("publickey") - 1U;
        CHECK(ctos_ssh_native_client_begin_discovered_password(fixture.client, fixture.selection_receipt,
            password, sizeof(password) - 1U, stage) == 0);
        CHECK(read_u32(stage + 8U) == (keyboard_first ? 3U : 2U));
        drive_password(&fixture, CTOS_SSH_PASSWORD_AUTH_PARTIAL, 0);
        CHECK(ctos_ssh_native_client_auth_continuation_methods(fixture.client, &methods) == 0
            && methods == CTOS_SSH_DISCOVERY_PUBLICKEY);
        fixture.password_expected = 0; fixture.private_expected = 1;
        fixture.callback.selected_body_override = 1; fixture.callback.selected_body = sshbuf_ptr(file);
        fixture.callback.selected_body_length = sshbuf_len(file);
        fixture.password_reply = keyboard_first ? PASSWORD_REPLY_SUCCESS : PASSWORD_REPLY_PARTIAL;
        fixture.password_methods = (const unsigned char *)"keyboard-interactive";
        fixture.password_methods_length = sizeof("keyboard-interactive") - 1U;
        CHECK(ctos_ssh_native_client_continue_private_key_auth(fixture.client, fixture.selection_receipt, stage) == 0);
        CHECK(read_u32(stage + 8U) == (keyboard_first ? 4U : 3U));
        drive_private(&fixture, keyboard_first ? CTOS_SSH_PRIVATE_KEY_AUTH_SUCCESS : CTOS_SSH_PRIVATE_KEY_AUTH_PARTIAL, 0);
        if (!keyboard_first) {
            fixture.private_expected = 0; fixture.keyboard_expected = 1;
            CHECK(ctos_ssh_native_client_continue_keyboard_interactive_auth(fixture.client, fixture.selection_receipt, stage) == 0);
            CHECK(read_u32(stage + 8U) == 4U && read_u32(stage + 16U) == 3U);
            for (unsigned int turn = 0; turn < 100000U && fixture.keyboard_requests == 0; turn++) CHECK(shell_turn(&fixture) == 0);
            keyboard_challenge_packet(&fixture, 1, 0); keyboard_result(&fixture, CTOS_SSH_KEYBOARD_AUTH_CHALLENGE, 0);
            fixture.keyboard_answers[0] = (struct ctos_ssh_slice){(const unsigned char *)"", 0};
            keyboard_answer(&fixture, 1); keyboard_finish_packet(&fixture, 2);
            keyboard_result(&fixture, CTOS_SSH_KEYBOARD_AUTH_SUCCESS, 0);
        }
        fixture.private_expected = fixture.keyboard_expected = 0;
        fixture.shell_expected = 1; fixture.shell_window = 65536U; fixture.shell_maxpacket = 32768U;
        CHECK(ctos_ssh_native_client_begin_shell(fixture.client, 80, 24) == 0);
        drive_shell_phase(&fixture, CTOS_SSH_SHELL_RUNNING);
        CHECK(fixture.callback.selected_calls == 1U && fixture.password_requests == 1U
            && fixture.keyboard_responses == 1U && fixture.private_requests == 1U);
        cleanup_fixture(&fixture, 1); sshbuf_free(file); free(blob); sshkey_free(key);
    }
}

static void test_keyboard_rounds_and_outcomes(void) {
    for (unsigned int mode = 0; mode < 2U; mode++) for (unsigned int outcome = 2; outcome <= 4U; outcome++) {
        struct fixture fixture;
        unsigned char challenge[CTOS_SSH_NATIVE_CLIENT_KBI_WIRE_BYTES];
        size_t length;
        setup_keyboard_fixture(&fixture, mode, (enum ctos_ssh_native_host_key_mode)mode);
        keyboard_challenge_packet(&fixture, 2, 0);
        keyboard_result(&fixture, CTOS_SSH_KEYBOARD_AUTH_CHALLENGE, 0);
        CHECK(ctos_ssh_native_client_keyboard_interactive_challenge(fixture.client, challenge, sizeof(challenge), &length) == 0);
        CHECK(read_u32(challenge) == 0x43534b49U && read_u32(challenge + 4) == 0x00010000U
            && read_u32(challenge + 8) == 1 && read_u32(challenge + 20) == 2);
        size_t name_length = read_u32(challenge + 12), instruction_length = read_u32(challenge + 16);
        CHECK(name_length == sizeof("服务器?Name") - 1U
            && memcmp(challenge + 24, "服务器?Name", name_length) == 0);
        CHECK(instruction_length == sizeof("第一行\n第二行\t?") - 1U
            && memcmp(challenge + 24 + name_length, "第一行\n第二行\t?", instruction_length) == 0);
        const unsigned char *p = challenge + 24 + name_length + instruction_length;
        CHECK(read_u32(p) == 0 && read_u32(p + 4) == 10);
        p += 18;
        CHECK(read_u32(p) == 1 && read_u32(p + 4) == 6 && p + 14 == challenge + length);
        fixture.keyboard_answers[0] = (struct ctos_ssh_slice){(const unsigned char *)"secret😀", sizeof("secret😀") - 1U};
        fixture.keyboard_answers[1] = (struct ctos_ssh_slice){(const unsigned char *)"", 0};
        keyboard_answer(&fixture, 1);
        memset(challenge, 0xa5, sizeof(challenge)); length = 99;
        CHECK(ctos_ssh_native_client_keyboard_interactive_challenge(fixture.client, challenge, sizeof(challenge), &length) != 0
            && length == 0);
        for (size_t index = 0; index < sizeof(challenge); index++) CHECK(challenge[index] == 0);
        keyboard_challenge_packet(&fixture, 0, 0);
        keyboard_result(&fixture, CTOS_SSH_KEYBOARD_AUTH_CHALLENGE, 0);
        keyboard_answer(&fixture, 2);
        keyboard_challenge_packet(&fixture, 1, 0);
        keyboard_result(&fixture, CTOS_SSH_KEYBOARD_AUTH_CHALLENGE, 0);
        fixture.keyboard_answers[0] = (struct ctos_ssh_slice){(const unsigned char *)"a\0b", 3U};
        struct wire stale = keyboard_response_wire(&fixture, 1);
        CHECK(ctos_ssh_native_client_respond_keyboard_interactive(fixture.client, stale.bytes, stale.length) != 0
            && errno == EINVAL);
        keyboard_answer(&fixture, 3);
        keyboard_finish_packet(&fixture, outcome);
        keyboard_result(&fixture, (enum ctos_ssh_native_client_keyboard_interactive_auth_result)outcome, 0);
        CHECK(fixture.keyboard_requests == 1 && fixture.keyboard_responses == 3 && fixture.callback.selected_calls == 0);
        if (outcome != 2U) {
            CHECK(ctos_ssh_native_client_configure_no_pty(fixture.client) != 0 && errno == EACCES);
            CHECK(ctos_ssh_native_client_begin_shell(fixture.client, 80, 24) != 0 && errno == EACCES);
        }
        else {
            fixture.shell_expected = 1; fixture.shell_window = 4096; fixture.shell_maxpacket = 1024;
            if (mode == 1U) {
                CHECK(ctos_ssh_native_client_configure_no_pty(fixture.client) == 0);
                fixture.shell_no_pty = 1;
            }
            CHECK(ctos_ssh_native_client_begin_shell(fixture.client, 80, 24) == 0);
            drive_shell_phase(&fixture, CTOS_SSH_SHELL_RUNNING);
            CHECK(fixture.shell_stage == 3 && fixture.callback.selected_calls == 0);
        }
        cleanup_fixture(&fixture, 1);
    }
    struct fixture direct;
    setup_keyboard_fixture(&direct, 0, CTOS_SSH_HOST_KEY_MANAGED);
    keyboard_finish_packet(&direct, 2);
    keyboard_result(&direct, CTOS_SSH_KEYBOARD_AUTH_SUCCESS, 0);
    CHECK(direct.keyboard_responses == 0 && direct.callback.selected_calls == 0);
    cleanup_fixture(&direct, 1);
}

static void test_keyboard_wire_echo_booleans(void) {
    for (unsigned int mode = 0; mode < 2U; mode++) for (unsigned int variant = 3U; variant <= 9U; variant += 6U) {
        struct fixture fixture;
        unsigned char challenge[CTOS_SSH_NATIVE_CLIENT_KBI_WIRE_BYTES];
        size_t length = 0;
        setup_keyboard_fixture(&fixture, 0, (enum ctos_ssh_native_host_key_mode)mode);
        keyboard_challenge_packet(&fixture, 1, variant);
        keyboard_result(&fixture, CTOS_SSH_KEYBOARD_AUTH_CHALLENGE, 0);
        CHECK(ctos_ssh_native_client_keyboard_interactive_challenge(fixture.client,
            challenge, sizeof(challenge), &length) == 0);
        size_t offset = 24U + read_u32(challenge + 12) + read_u32(challenge + 16);
        CHECK(length >= offset + 8U && read_u32(challenge + 20) == 1U);
        CHECK(read_u32(challenge + offset) == 1U);
        fixture.keyboard_answers[0] = (struct ctos_ssh_slice){(const unsigned char *)"visible-answer", 14U};
        keyboard_answer(&fixture, 1);
        keyboard_finish_packet(&fixture, 2);
        keyboard_result(&fixture, CTOS_SSH_KEYBOARD_AUTH_SUCCESS, 0);
        CHECK(fixture.keyboard_responses == 1U && fixture.callback.selected_calls == 0U);
        cleanup_fixture(&fixture, 1);
    }
}

static void test_keyboard_exact_wire_bounds(void) {
    struct fixture fixture;
    unsigned char text[4096], challenge[CTOS_SSH_NATIVE_CLIENT_KBI_WIRE_BYTES];
    size_t length;
    memset(text, 'p', sizeof(text));
    setup_keyboard_fixture(&fixture, 0, CTOS_SSH_HOST_KEY_MANAGED);
    CHECK(sshpkt_start(fixture.server, SSH2_MSG_USERAUTH_INFO_REQUEST) == 0);
    CHECK(sshpkt_put_cstring(fixture.server, "") == 0);
    CHECK(sshpkt_put_cstring(fixture.server, "") == 0);
    CHECK(sshpkt_put_cstring(fixture.server, "") == 0);
    CHECK(sshpkt_put_u32(fixture.server, 8) == 0);
    for (uint32_t index = 0; index < 8; index++) {
        CHECK(sshpkt_put_string(fixture.server, text, index == 7 ? 4008U : sizeof(text)) == 0);
        CHECK(sshpkt_put_u8(fixture.server, index % 2) == 0);
        fixture.keyboard_answers[index] = (struct ctos_ssh_slice){text, index == 7 ? 4048U : sizeof(text)};
    }
    CHECK(sshpkt_send(fixture.server) == 0);
    fixture.keyboard_prompt_count = 8;
    keyboard_result(&fixture, CTOS_SSH_KEYBOARD_AUTH_CHALLENGE, 0);
    CHECK(ctos_ssh_native_client_keyboard_interactive_challenge(fixture.client, challenge, sizeof(challenge), &length) == 0
        && length == sizeof(challenge) && read_u32(challenge + 12) == 0 && read_u32(challenge + 16) == 0);
    length = 99;
    CHECK(ctos_ssh_native_client_keyboard_interactive_challenge(fixture.client, challenge, sizeof(challenge) - 1, &length) != 0
        && errno == EOVERFLOW && length == 0);
    struct sshbuf *response = sshbuf_new(); CHECK(response != NULL);
    CHECK(sshbuf_put_u32(response, 0x43534b52U) == 0 && sshbuf_put_u32(response, 0x00010000U) == 0
        && sshbuf_put_u32(response, 1) == 0 && sshbuf_put_u32(response, 8) == 0);
    for (uint32_t index = 0; index < 8; index++)
        CHECK(sshbuf_put_string(response, fixture.keyboard_answers[index].data,
            fixture.keyboard_answers[index].length) == 0);
    CHECK(sshbuf_len(response) == CTOS_SSH_NATIVE_CLIENT_KBI_WIRE_BYTES);
    CHECK(sshbuf_put_u8(response, 0) == 0);
    CHECK(ctos_ssh_native_client_respond_keyboard_interactive(fixture.client, sshbuf_ptr(response), sshbuf_len(response)) != 0
        && errno == EINVAL);
    CHECK(sshbuf_consume_end(response, 1) == 0);
    unsigned char *mutable = sshbuf_mutable_ptr(response); CHECK(mutable != NULL);
    mutable[19] = 1;
    CHECK(ctos_ssh_native_client_respond_keyboard_interactive(fixture.client, mutable, sshbuf_len(response)) != 0
        && errno == EINVAL);
    mutable[19] = 0; mutable[20] = 0xff;
    CHECK(ctos_ssh_native_client_respond_keyboard_interactive(fixture.client, mutable, sshbuf_len(response)) != 0
        && errno == EINVAL);
    mutable[20] = 'p';
    CHECK(ctos_ssh_native_client_respond_keyboard_interactive(fixture.client, mutable, sshbuf_len(response)) == 0);
    sshbuf_free(response);
    for (uint32_t turn = 0; turn < 100000; turn++) {
        CHECK(shell_turn(&fixture) == 0);
        if (fixture.keyboard_responses == 1) break;
    }
    CHECK(fixture.keyboard_responses == 1);
    keyboard_finish_packet(&fixture, 2);
    keyboard_result(&fixture, CTOS_SSH_KEYBOARD_AUTH_SUCCESS, 0);
    cleanup_fixture(&fixture, 1);
}

static void test_keyboard_protocol_limits(void) {
    for (unsigned int bad = 1; bad <= 8U; bad++) {
        if (bad == 3U) continue;
        struct fixture fixture;
        setup_keyboard_fixture(&fixture, 0, CTOS_SSH_HOST_KEY_MANAGED);
        keyboard_challenge_packet(&fixture, bad == 8U ? 8U : 1U, bad);
        keyboard_result(&fixture, CTOS_SSH_KEYBOARD_AUTH_NOT_STARTED, EPROTO);
        CHECK(fixture.keyboard_responses == 0 && fixture.callback.selected_calls == 0);
        cleanup_fixture(&fixture, 1);
    }
    struct fixture duplicate;
    setup_keyboard_fixture(&duplicate, 0, CTOS_SSH_HOST_KEY_MANAGED);
    keyboard_challenge_packet(&duplicate, 1, 0);
    keyboard_result(&duplicate, CTOS_SSH_KEYBOARD_AUTH_CHALLENGE, 0);
    keyboard_challenge_packet(&duplicate, 1, 0);
    keyboard_result(&duplicate, CTOS_SSH_KEYBOARD_AUTH_NOT_STARTED, EPROTO);
    CHECK(duplicate.keyboard_responses == 0);
    cleanup_fixture(&duplicate, 1);
    struct fixture rounds;
    setup_keyboard_fixture(&rounds, 0, CTOS_SSH_HOST_KEY_MANAGED);
    for (uint32_t round = 1; round <= CTOS_SSH_NATIVE_CLIENT_KBI_ROUNDS; round++) {
        keyboard_challenge_packet(&rounds, round == 1 ? 16U : 0U, 0);
        keyboard_result(&rounds, CTOS_SSH_KEYBOARD_AUTH_CHALLENGE, 0);
        keyboard_answer(&rounds, round);
    }
    keyboard_challenge_packet(&rounds, 0, 0);
    keyboard_result(&rounds, CTOS_SSH_KEYBOARD_AUTH_NOT_STARTED, EPROTO);
    CHECK(rounds.keyboard_responses == 16 && rounds.callback.selected_calls == 0);
    cleanup_fixture(&rounds, 1);
}

static void test_keyboard_negative_host(void) {
    for (unsigned int revoked = 0; revoked < 2; revoked++) {
        struct fixture fixture = {0};
        struct sshkey *different = NULL;
        fixture.keyboard_expected = 1; fixture.pair[0] = fixture.pair[1] = -1;
        fixture.callback.release_fd = -1;
        CHECK(sshkey_generate(KEY_ED25519, 0, &fixture.private_key) == 0
            && sshkey_from_private(fixture.private_key, &fixture.public_key) == 0);
        fixture.host_key = fixture.public_key;
        CHECK(sshkey_generate(KEY_ED25519, 0, &different) == 0);
        finish_fixture_setup_selected_mode(&fixture, 60000U, revoked ? NULL : different,
            revoked ? "@revoked " : NULL, 0, 0, 3U, 0, CTOS_SSH_HOST_KEY_MANAGED);
        setup_synthetic_server(&fixture);
        int failed = 0;
        for (uint32_t turn = 0; turn < 100000; turn++) {
            if (ctos_ssh_native_client_step(fixture.client, &(struct ctos_ssh_native_client_step){0}) != 0) { failed = 1; break; }
            CHECK(pump_synthetic_server(&fixture, 64, NULL, NULL) == 0);
        }
        CHECK(failed && fixture.callback.host_evidence_calls == 0 && fixture.callback.kex_complete_calls == 0
            && fixture.keyboard_requests == 0 && fixture.keyboard_responses == 0 && fixture.callback.selected_calls == 0);
        CHECK(ctos_ssh_native_client_begin_keyboard_interactive_auth(fixture.client) != 0);
        cleanup_fixture(&fixture, 1);
        sshkey_free(different);
    }
}

struct keyboard_owner_attempt { struct ctos_ssh_native_client *client; struct wire response; };

static void *keyboard_wrong_owner(void *opaque) {
    struct keyboard_owner_attempt *attempt = opaque;
    unsigned char challenge[CTOS_SSH_NATIVE_CLIENT_KBI_WIRE_BYTES];
    size_t length = 99;
    enum ctos_ssh_native_client_keyboard_interactive_auth_result result = CTOS_SSH_KEYBOARD_AUTH_SUCCESS;
    CHECK(ctos_ssh_native_client_keyboard_interactive_auth_result(attempt->client, &result) != 0
        && errno == EPERM && result == CTOS_SSH_KEYBOARD_AUTH_NOT_STARTED);
    memset(challenge, 0xa5, sizeof(challenge));
    CHECK(ctos_ssh_native_client_keyboard_interactive_challenge(attempt->client, challenge, sizeof(challenge), &length) != 0
        && errno == EPERM && length == 0);
    for (size_t index = 0; index < sizeof(challenge); index++) CHECK(challenge[index] == 0);
    CHECK(ctos_ssh_native_client_respond_keyboard_interactive(attempt->client,
        attempt->response.bytes, attempt->response.length) != 0 && errno == EPERM);
    return NULL;
}

static void test_keyboard_answer_ownership(void) {
    for (unsigned int guard = 0; guard < 4U; guard++) {
        struct fixture fixture;
        setup_keyboard_fixture(&fixture, 0, CTOS_SSH_HOST_KEY_MANAGED);
        keyboard_challenge_packet(&fixture, 1, 0);
        keyboard_result(&fixture, CTOS_SSH_KEYBOARD_AUTH_CHALLENGE, 0);
        struct wire response = keyboard_response_wire(&fixture, 1);
        for (size_t length = 0; length < response.length; length++)
            CHECK(ctos_ssh_native_client_respond_keyboard_interactive(fixture.client, response.bytes, length) != 0);
        for (unsigned int bad = 0; bad < 6U; bad++) {
            struct wire invalid = response;
            if (bad < 4) invalid.bytes[bad * 4 + 3] ^= 1;
            else if (bad == 4) wire_put(&invalid, "x", 1);
            else { invalid.bytes[19] = 1; wire_put(&invalid, "\xff", 1); }
            CHECK(ctos_ssh_native_client_respond_keyboard_interactive(fixture.client, invalid.bytes, invalid.length) != 0);
        }
        struct keyboard_owner_attempt attempt = {.client = fixture.client, .response = response};
        pthread_t thread;
        CHECK(pthread_create(&thread, NULL, keyboard_wrong_owner, &attempt) == 0);
        CHECK(pthread_join(thread, NULL) == 0);
        if (guard == 0) { keyboard_answer(&fixture, 1); keyboard_finish_packet(&fixture, 2); keyboard_result(&fixture, CTOS_SSH_KEYBOARD_AUTH_SUCCESS, 0); }
        else {
            if (guard == 1) fixture.view->valid = 0;
            else if (guard == 2) fixture.view->deadline = deadline_after(0);
            if (guard != 3) CHECK(ctos_ssh_native_client_respond_keyboard_interactive(fixture.client, response.bytes, response.length) != 0);
            else CHECK(ctos_ssh_native_client_step(fixture.client,
                &(struct ctos_ssh_native_client_step){.cancelled = 1}) != 0 && errno == ECANCELED);
            CHECK(fixture.keyboard_responses == 0);
        }
        cleanup_fixture(&fixture, 1);
    }
}

static void mfa_keyboard_result(struct fixture *fixture, int expected) {
    for (unsigned int turn = 0; turn < 200000U; turn++) {
        CHECK(shell_turn(fixture) == 0);
        enum ctos_ssh_native_client_keyboard_interactive_auth_result result;
        CHECK(ctos_ssh_native_client_keyboard_interactive_auth_result(fixture->client, &result) == 0);
        if (result != CTOS_SSH_KEYBOARD_AUTH_PENDING) { CHECK((int)result == expected); return; }
    }
    CHECK(0);
}

struct mfa_owner_attempt { struct ctos_ssh_native_client *client; unsigned char plan[32]; };
static void *mfa_wrong_owner(void *opaque) {
    struct mfa_owner_attempt *attempt = opaque;
    unsigned char stage[92]; memset(stage, 0xa5, sizeof(stage));
    CHECK(ctos_ssh_native_client_continue_keyboard_interactive_auth(attempt->client, attempt->plan, stage) != 0
        && errno == EPERM);
    for (size_t i = 0; i < sizeof(stage); i++) CHECK(stage[i] == 0);
    return NULL;
}

static void test_mfa_continuation(void) {
    for (unsigned int kind = 0; kind < 3U; kind++) for (unsigned int scenario = 0; scenario < 13U; scenario++) {
        struct fixture fixture;
        struct sshkey *key = NULL;
        struct sshbuf *file = NULL, *bundle = NULL;
        unsigned char *blob = NULL;
        size_t blob_length = 0;
        if (kind == 0) {
            setup_password_fixture_continuation(&fixture, scenario % 2U, 60000U,
                CTOS_SSH_HOST_KEY_MANAGED, scenario != 10U);
        } else {
            file = sshbuf_new(); CHECK(file != NULL && sshkey_generate(KEY_ED25519, 0, &key) == 0);
            CHECK(sshkey_to_blob(key, &blob, &blob_length) == 0);
            CHECK(sshkey_private_to_fileblob(key, file, kind == 2U ? "synthetic-passphrase" : "",
                "mfa", SSHKEY_PRIVATE_OPENSSH, NULL, 1) == 0);
            if (kind == 2U) bundle = private_bundle(file, "synthetic-passphrase");
            setup_private_fixture_version_mode(&fixture, scenario % 2U, key, bundle == NULL ? file : bundle,
                blob, blob_length, NULL, 0, scenario == 10U ? (kind == 2U ? 2 : 1) : kind == 2U ? 4 : 3,
                CTOS_SSH_HOST_KEY_MANAGED);
        }
        fixture.password_reply = scenario == 5U ? PASSWORD_REPLY_REJECTED
            : scenario == 6U ? PASSWORD_REPLY_SUCCESS : PASSWORD_REPLY_PARTIAL;
        const char *methods = scenario == 3U ? "password,publickey" : scenario == 4U
            ? "xkeyboard-interactivex" : "keyboard-interactive";
        fixture.password_methods = (const unsigned char *)methods;
        fixture.password_methods_length = strlen(methods);
        if (kind == 0) {
            CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
            drive_password(&fixture, scenario == 5U ? CTOS_SSH_PASSWORD_AUTH_REJECTED
                : scenario == 6U ? CTOS_SSH_PASSWORD_AUTH_SUCCESS : CTOS_SSH_PASSWORD_AUTH_PARTIAL, 0);
        } else {
            CHECK(ctos_ssh_native_client_begin_private_key_auth(fixture.client) == 0);
            drive_private(&fixture, scenario == 5U ? CTOS_SSH_PRIVATE_KEY_AUTH_REJECTED
                : scenario == 6U ? CTOS_SSH_PRIVATE_KEY_AUTH_SUCCESS : CTOS_SSH_PRIVATE_KEY_AUTH_PARTIAL, 0);
        }
        CHECK(fixture.callback.selected_calls == 1U && fixture.service_requests == 1U
            && fixture.callback.kex_complete_calls == 1U);
        unsigned char stage[92], plan[32];
        memcpy(plan, fixture.selection_receipt, 32U);
        if (scenario == 7U) {
            plan[0] ^= 1U;
            CHECK(ctos_ssh_native_client_continue_keyboard_interactive_auth(fixture.client, plan, stage) != 0);
            for (size_t i = 0; i < sizeof(stage); i++) CHECK(stage[i] == 0);
            plan[0] ^= 1U;
        }
        if (scenario == 8U) {
            struct mfa_owner_attempt attempt = {.client = fixture.client}; memcpy(attempt.plan, plan, 32U);
            pthread_t thread;
            CHECK(pthread_create(&thread, NULL, mfa_wrong_owner, &attempt) == 0);
            CHECK(pthread_join(thread, NULL) == 0);
        }
        if (scenario == 9U) CHECK(ctos_ssh_callback_cancel(fixture.callback_token) == 0);
        if (scenario == 11U) fixture.view->valid = 0;
        if (scenario == 12U) fixture.view->deadline = deadline_after(0);
        int transitioned = ctos_ssh_native_client_continue_keyboard_interactive_auth(fixture.client, plan, stage);
        if ((scenario >= 3U && scenario <= 6U) || scenario >= 9U) {
            CHECK(transitioned != 0);
            for (size_t i = 0; i < sizeof(stage); i++) CHECK(stage[i] == 0);
            CHECK(fixture.keyboard_requests == 0U);
            if (scenario != 6U) CHECK(ctos_ssh_native_client_begin_shell(fixture.client, 80, 24) != 0);
            else {
                fixture.shell_expected = 1; fixture.shell_window = 65536U; fixture.shell_maxpacket = 32768U;
                CHECK(ctos_ssh_native_client_begin_shell(fixture.client, 80, 24) == 0);
                for (unsigned int turn = 0; turn < 100000U && fixture.shell_stage != 3; turn++) CHECK(shell_turn(&fixture) == 0);
                CHECK(fixture.shell_stage == 3 && fixture.callback.kex_complete_calls == 1U);
            }
        } else {
            CHECK(transitioned == 0 && read_u32(stage) == 0x43534150U
                && read_u32(stage + 4U) == 0x00010000U && read_u32(stage + 8U) == 2U
                && read_u32(stage + 12U) == (kind == 0U ? 1U : 4U) && read_u32(stage + 16U) == 3U);
            CHECK(memcmp(stage + 20U, plan, 32U) == 0
                && read_u32(stage + 52U) == 0U && read_u32(stage + 56U) == fixture.callback.last_kex_cycle
                && memcmp(stage + 60U, fixture.callback.last_kex_digest, 32U) == 0);
            unsigned char secret[32]; size_t secret_length = 99;
            CHECK(ctos_ssh_native_client_selected_credential_bytes(fixture.client, secret, sizeof(secret), &secret_length) != 0
                && secret_length == 0 && fixture.callback.selected_calls == 1U);
            CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) != 0);
            CHECK(ctos_ssh_native_client_begin_private_key_auth(fixture.client) != 0);
            CHECK(ctos_ssh_native_client_begin_keyboard_interactive_auth(fixture.client) != 0);
            CHECK(ctos_ssh_native_client_continue_keyboard_interactive_auth(fixture.client, plan, stage) != 0);
            fixture.password_expected = fixture.private_expected = 0;
            fixture.keyboard_expected = 1;
            for (unsigned int turn = 0; turn < 100000U && fixture.keyboard_requests == 0; turn++) CHECK(shell_turn(&fixture) == 0);
            CHECK(fixture.keyboard_requests == 1U);
            for (unsigned int round = 1; round <= 3U; round++) {
                unsigned int count = round == 1U ? 2U : round == 2U ? 0U : 1U;
                keyboard_challenge_packet(&fixture, count, 0);
                mfa_keyboard_result(&fixture, CTOS_SSH_KEYBOARD_AUTH_CHALLENGE);
                for (unsigned int i = 0; i < count; i++) fixture.keyboard_answers[i] = (struct ctos_ssh_slice){(const unsigned char *)"", 0};
                keyboard_answer(&fixture, round);
            }
            keyboard_finish_packet(&fixture, scenario == 1U ? 3U : scenario == 2U ? 4U : 2U);
            mfa_keyboard_result(&fixture, scenario == 1U ? CTOS_SSH_KEYBOARD_AUTH_REJECTED
                : scenario == 2U ? CTOS_SSH_KEYBOARD_AUTH_PARTIAL : CTOS_SSH_KEYBOARD_AUTH_SUCCESS);
            if (scenario == 1U || scenario == 2U) {
                CHECK(ctos_ssh_native_client_configure_no_pty(fixture.client) != 0);
                CHECK(ctos_ssh_native_client_begin_shell(fixture.client, 80, 24) != 0);
            }
            else {
                fixture.shell_expected = 1; fixture.shell_window = 65536U; fixture.shell_maxpacket = 32768U;
                CHECK(ctos_ssh_native_client_configure_no_pty(fixture.client) == 0);
                fixture.shell_no_pty = 1;
                CHECK(ctos_ssh_native_client_begin_shell(fixture.client, 80, 24) == 0);
                for (unsigned int turn = 0; turn < 100000U && fixture.shell_stage != 3; turn++) CHECK(shell_turn(&fixture) == 0);
                CHECK(fixture.shell_stage == 3 && fixture.callback.kex_complete_calls == 1U);
            }
            CHECK(fixture.callback.selected_calls == 1U && fixture.callback.credential_calls == 0U
                && fixture.callback.credential_bytes_calls == 0U && fixture.keyboard_responses == 3U);
        }
        cleanup_fixture(&fixture, 1);
        sshbuf_free(bundle); sshbuf_free(file); free(blob); sshkey_free(key);
    }
}

static void test_changed_auth_continuation(void) {
    struct sshkey *old_host = NULL, *identity = NULL;
    struct sshbuf *key_file = sshbuf_new();
    unsigned char *identity_blob = NULL;
    unsigned char source[4096], other_source[4096];
    size_t identity_length = 0, source_length, other_length;

    CHECK(key_file != NULL && sshkey_generate(KEY_ED25519, 0, &old_host) == 0);
    CHECK(sshkey_generate(KEY_ED25519, 0, &identity) == 0);
    CHECK(sshkey_to_blob(identity, &identity_blob, &identity_length) == 0);
    CHECK(sshkey_private_to_fileblob(identity, key_file, "", "changed-mfa",
        SSHKEY_PRIVATE_OPENSSH, NULL, 1) == 0);
    source_length = format_host_key(old_host, source, sizeof(source));
    other_length = format_host_key(old_host, other_source, sizeof(other_source));

    /* private SUCCESS, private PARTIAL -> KBI, password PARTIAL -> KBI */
    for (unsigned int scenario = 0; scenario < 3U; scenario++) {
        struct fixture fixture = {0};
        int private_auth = scenario != 2U;
        fixture.known_hosts_override = source;
        fixture.known_hosts_override_length = source_length;
        fixture.other_hosts_override = other_source;
        fixture.other_hosts_override_length = other_length;
        fixture.private_expected = private_auth;
        fixture.password_expected = !private_auth;
        fixture.keyboard_continuation = scenario != 0U;
        fixture.identity = identity;
        fixture.identity_blob = identity_blob;
        fixture.identity_blob_length = identity_length;
        fixture.identity_algorithm = sshkey_ssh_name(identity);
        fixture.password_methods = (const unsigned char *)"keyboard-interactive";
        fixture.password_methods_length = sizeof("keyboard-interactive") - 1U;
        fixture.password_text_length = 3U;
        fixture.password_language_length = 2U;
        fixture.password_reply = scenario == 0U ? PASSWORD_REPLY_SUCCESS : PASSWORD_REPLY_PARTIAL;
        fixture.pair[0] = fixture.pair[1] = -1;
        fixture.callback.release_fd = -1;
        fixture.callback.enable_changed = 1;
        CHECK(sshkey_generate(KEY_ED25519, 0, &fixture.private_key) == 0);
        CHECK(sshkey_from_private(fixture.private_key, &fixture.public_key) == 0);
        fixture.host_key = fixture.public_key;
        finish_fixture_setup_selected_mode(&fixture, 30000U, NULL, NULL, 0, 0,
            private_auth ? 4U : 1U, 0, CTOS_SSH_HOST_KEY_MANAGED);
        fixture.callback.selected_kind = private_auth ? CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY
            : CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD;
        fixture.callback.selected_body_override = private_auth;
        fixture.callback.selected_body = sshbuf_ptr(key_file);
        fixture.callback.selected_body_length = sshbuf_len(key_file);
        fixture.callback.selected_view = fixture.view;
        fixture.callback.selected_callback = fixture.callback_token;
        fixture.callback.selected_role = fixture.role;
        fixture.callback.selected_deadline = fixture.deadline;
        memcpy(fixture.callback.expected_selection_receipt, fixture.selection_receipt, 32U);

        finish_service_fixture(&fixture, 0, 0);
        CHECK(fixture.callback.changed_commit_calls == 1U
            && fixture.callback.selected_calls == 0U && fixture.private_requests == 0U
            && fixture.password_requests == 0U);
        CHECK(ctos_ssh_native_client_begin_userauth_service(fixture.client) == 0);
        drive_service(&fixture, 0);
        if (private_auth) {
            CHECK(ctos_ssh_native_client_begin_private_key_auth(fixture.client) == 0);
            drive_private(&fixture, scenario == 0U ? CTOS_SSH_PRIVATE_KEY_AUTH_SUCCESS
                : CTOS_SSH_PRIVATE_KEY_AUTH_PARTIAL, 0);
            CHECK(fixture.private_requests == 1U && fixture.password_requests == 0U);
        } else {
            CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
            drive_password(&fixture, CTOS_SSH_PASSWORD_AUTH_PARTIAL, 0);
            CHECK(fixture.password_requests == 1U && fixture.private_requests == 0U);
        }
        if (scenario != 0U) {
            unsigned char stage[92];
            CHECK(ctos_ssh_native_client_continue_keyboard_interactive_auth(fixture.client,
                fixture.selection_receipt, stage) == 0);
            CHECK(read_u32(stage) == 0x43534150U
                && memcmp(stage + 20U, fixture.selection_receipt, 32U) == 0
                && read_u32(stage + 56U) == fixture.callback.last_kex_cycle
                && memcmp(stage + 60U, fixture.callback.last_kex_digest, 32U) == 0);
            fixture.private_expected = fixture.password_expected = 0;
            fixture.keyboard_expected = 1;
            for (unsigned int turn = 0; turn < 100000U && fixture.keyboard_requests == 0; turn++)
                CHECK(shell_turn(&fixture) == 0);
            CHECK(fixture.keyboard_requests == 1U);
            keyboard_challenge_packet(&fixture, 1U, 0);
            mfa_keyboard_result(&fixture, CTOS_SSH_KEYBOARD_AUTH_CHALLENGE);
            fixture.keyboard_answers[0] = (struct ctos_ssh_slice){(const unsigned char *)"2468", 4U};
            keyboard_answer(&fixture, 1U);
            keyboard_finish_packet(&fixture, 2U);
            mfa_keyboard_result(&fixture, CTOS_SSH_KEYBOARD_AUTH_SUCCESS);
            CHECK(fixture.keyboard_responses == 1U);
        }
        fixture.shell_expected = 1;
        fixture.shell_window = 65536U;
        fixture.shell_maxpacket = 32768U;
        CHECK(ctos_ssh_native_client_begin_shell(fixture.client, 80, 24) == 0);
        for (unsigned int turn = 0; turn < 100000U && fixture.shell_stage != 3; turn++)
            CHECK(shell_turn(&fixture) == 0);
        CHECK(fixture.shell_stage == 3 && fixture.callback.selected_calls == 1U
            && fixture.callback.changed_commit_calls == 1U);
        cleanup_fixture(&fixture, 1);
    }
    free(identity_blob);
    sshbuf_free(key_file);
    sshkey_free(identity);
    sshkey_free(old_host);
}

static void test_jump_authentication_methods(void) {
    struct fixture fixture;
    struct sshkey *key = NULL; unsigned char *blob = NULL; size_t length = 0;
    struct sshbuf *file = sshbuf_new();
    CHECK(file != NULL && sshkey_generate(KEY_ED25519, 0, &key) == 0);
    CHECK(sshkey_to_blob(key, &blob, &length) == 0);
    CHECK(sshkey_private_to_fileblob(key, file, "", "jump", SSHKEY_PRIVATE_OPENSSH, NULL, 0) == 0);
    fixture_jump_role = 1;
    setup_private_fixture_version_mode(&fixture, 0, key, file, blob, length, NULL, 0, 1, CTOS_SSH_HOST_KEY_PINNED);
    fixture_jump_role = 0;
    CHECK(ctos_ssh_native_client_begin_private_key_auth(fixture.client) == 0);
    drive_private(&fixture, CTOS_SSH_PRIVATE_KEY_AUTH_SUCCESS, 0);
    sshbuf_free(file); free(blob); sshkey_free(key); fixture.identity = NULL;
    fixture.shell_expected = 1; fixture.jump_expected = 1;
    fixture.shell_window = 65536U; fixture.shell_maxpacket = 32768U;
    ctos_ssh_role_token next = jump_next_role(&fixture, 0);
    CHECK(ctos_ssh_native_client_begin_jump(fixture.client, next) == 0);
    drive_jump_phase(&fixture, CTOS_SSH_JUMP_OPEN);
    CHECK(fixture.callback.selected_calls == 1 && fixture.private_requests == 1);
    cleanup_fixture(&fixture, 1); CHECK(ctos_ssh_role_bootstrap_close(next) == 0);

    fixture_jump_role = 1;
    setup_keyboard_fixture(&fixture, 0, CTOS_SSH_HOST_KEY_PINNED);
    fixture_jump_role = 0;
    keyboard_finish_packet(&fixture, 2);
    keyboard_result(&fixture, CTOS_SSH_KEYBOARD_AUTH_SUCCESS, 0);
    fixture.shell_expected = 1; fixture.jump_expected = 1;
    fixture.shell_window = 65536U; fixture.shell_maxpacket = 32768U;
    next = jump_next_role(&fixture, 0);
    CHECK(ctos_ssh_native_client_begin_jump(fixture.client, next) == 0);
    drive_jump_phase(&fixture, CTOS_SSH_JUMP_OPEN);
    CHECK(fixture.keyboard_requests == 1 && fixture.callback.selected_calls == 0);
    cleanup_fixture(&fixture, 1); CHECK(ctos_ssh_role_bootstrap_close(next) == 0);

    fixture_jump_role = 1;
    setup_password_fixture_continuation(&fixture, 0, 60000U, CTOS_SSH_HOST_KEY_PINNED, 1);
    fixture_jump_role = 0;
    fixture.password_reply = PASSWORD_REPLY_PARTIAL;
    fixture.password_methods = (const unsigned char *)"keyboard-interactive";
    fixture.password_methods_length = sizeof("keyboard-interactive") - 1U;
    CHECK(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
    drive_password(&fixture, CTOS_SSH_PASSWORD_AUTH_PARTIAL, 0);
    next = jump_next_role(&fixture, 0);
    CHECK(ctos_ssh_native_client_begin_jump(fixture.client, next) != 0 && errno == EACCES);
    unsigned char stage[92];
    CHECK(ctos_ssh_native_client_continue_keyboard_interactive_auth(fixture.client,
        fixture.selection_receipt, stage) == 0);
    fixture.password_expected = 0; fixture.keyboard_expected = 1;
    for (unsigned int turn = 0; turn < 100000U && fixture.keyboard_requests == 0; turn++)
        CHECK(shell_turn(&fixture) == 0);
    CHECK(fixture.keyboard_requests == 1);
    keyboard_finish_packet(&fixture, 2);
    mfa_keyboard_result(&fixture, CTOS_SSH_KEYBOARD_AUTH_SUCCESS);
    fixture.shell_expected = 1; fixture.jump_expected = 1;
    fixture.shell_window = 65536U; fixture.shell_maxpacket = 32768U;
    CHECK(ctos_ssh_native_client_begin_jump(fixture.client, next) == 0);
    drive_jump_phase(&fixture, CTOS_SSH_JUMP_OPEN);
    CHECK(fixture.callback.selected_calls == 1 && fixture.callback.kex_complete_calls == 1);
    cleanup_fixture(&fixture, 1); CHECK(ctos_ssh_role_bootstrap_close(next) == 0);

    fixture_jump_role = 1;
    setup_keyboard_fixture_before_auth_mode(&fixture, 0, CTOS_SSH_HOST_KEY_PINNED, 1, 0);
    fixture_jump_role = 0;
    fixture.discovery_expected = 1; fixture.discovery_reply = 1;
    CHECK(ctos_ssh_native_client_begin_auth_discovery(fixture.client) == 0);
    enum ctos_ssh_native_client_discovery_result result = CTOS_SSH_DISCOVERY_PENDING;
    unsigned int methods = 99;
    for (unsigned int turn = 0; turn < 100000U && result == CTOS_SSH_DISCOVERY_PENDING; turn++) {
        CHECK(shell_turn(&fixture) == 0);
        CHECK(ctos_ssh_native_client_auth_discovery_result(fixture.client, &result, &methods) == 0);
    }
    CHECK(result == CTOS_SSH_DISCOVERY_NONE_SUCCESS && methods == 0 && fixture.callback.selected_calls == 0);
    fixture.shell_expected = 1; fixture.jump_expected = 1;
    fixture.shell_window = 65536U; fixture.shell_maxpacket = 32768U;
    next = jump_next_role(&fixture, 0);
    CHECK(ctos_ssh_native_client_begin_jump(fixture.client, next) == 0);
    drive_jump_phase(&fixture, CTOS_SSH_JUMP_OPEN);
    cleanup_fixture(&fixture, 1); CHECK(ctos_ssh_role_bootstrap_close(next) == 0);
}

static void test_nine_kex_role_slots(void) {
    struct ssh *clients[CTOS_SSH_ROLE_MAX] = {0};
    ctos_ssh_role_token roles[CTOS_SSH_ROLE_MAX] = {0};
    unsigned char nonces[CTOS_SSH_ROLE_MAX][32];
    struct kex_params params;
    char *proposals[PROPOSAL_MAX] = {KEX_CLIENT};
    uint64_t deadline = deadline_after(30000);
    memcpy(params.proposal, proposals, sizeof(proposals));
    CHECK(CTOS_SSH_ROLE_MAX == CTOS_SSH_CONFIG_JUMPS + 1U);
    CHECK(CTOS_SSH_CALLBACK_MAX == CTOS_SSH_ROLE_MAX);
    for (unsigned int hop = 0; hop < CTOS_SSH_ROLE_MAX; hop++) {
        struct ctos_ssh_role_request request = role_request(deadline);
        memset(nonces[hop], (int)hop + 1, sizeof(nonces[hop]));
        request.hop = hop; request.role_nonce = nonces[hop];
        request.program = hop + 1 == CTOS_SSH_ROLE_MAX ? CTOS_SSH_ROLE_CLIENT : CTOS_SSH_ROLE_JUMP;
        CHECK(ctos_ssh_role_bootstrap_open(&request, &roles[hop]) == 0);
        CHECK(ssh_init(&clients[hop], 0, &params) == 0 && clients[hop] != NULL);
        CHECK(ctos_ssh_kex_bind_pending_host(clients[hop], roles[hop]) == 0);
    }
    CHECK(ctos_ssh_kex_bind_pending_host(clients[8], roles[8]) != 0);
    ctos_ssh_kex_unbind(clients[4]);
    CHECK(ctos_ssh_kex_bind_pending_host(clients[4], roles[4]) == 0);
    CHECK(ctos_ssh_kex_bind_pending_host(clients[8], roles[8]) != 0);
    for (unsigned int hop = 0; hop < CTOS_SSH_ROLE_MAX; hop++) {
        ctos_ssh_kex_unbind(clients[hop]);
        ssh_free(clients[hop]);
        CHECK(ctos_ssh_role_bootstrap_close(roles[hop]) == 0);
    }
}

static void test_provisional_root_uses_normal_new(void) {
    struct fixture root = {0};
    root.pair[0] = root.pair[1] = -1;
    root.callback.release_fd = -1;
    root.callback.enable_provisional = 1;
    CHECK(sshkey_generate(KEY_ED25519, 0, &root.private_key) == 0);
    CHECK(sshkey_from_private(root.private_key, &root.public_key) == 0);
    root.host_key = root.public_key;
    finish_fixture_setup_selected_mode(&root, 60000U, NULL, NULL, 1, 0, 0U, 0,
        CTOS_SSH_HOST_KEY_MANAGED);
    finish_service_fixture(&root, 1, 0);
    CHECK(root.callback.provisional_candidate_calls == 0 && root.callback.provisional_review_calls == 0
        && root.callback.provisional_kex_calls == 0 && root.callback.host_evidence_calls == 1
        && root.callback.kex_complete_calls == 1);
    int provisional = 99;
    CHECK(ctos_ssh_native_client_provisional_new_ready(root.client, &provisional) != 0 && !provisional);
    cleanup_fixture(&root, 1);
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--provisional-ext-only") == 0) {
        for (enum child_case mode = CHILD_PROVISIONAL_EXT_IDLE; mode <= CHILD_PROVISIONAL_EXT_EOF; mode++) {
            fprintf(stderr, "SSH_NATIVE_PROVISIONAL_EXT case=%d start\n", (int)mode);
            test_jump_nested_ssh(mode);
            fprintf(stderr, "SSH_NATIVE_PROVISIONAL_EXT case=%d pass\n", (int)mode);
        }
        printf("SSH_NATIVE_PROVISIONAL_EXT checks=%u gate=pass runtime=synthetic\n", checks);
        return 0;
    }
    if (argc == 2 && strcmp(argv[1], "--provisional-poll-only") == 0) {
        for (enum child_case mode = CHILD_PROVISIONAL_POLL_IDLE; mode <= CHILD_PROVISIONAL_POLL_TIMEOUT; mode++) {
            fprintf(stderr, "SSH_NATIVE_PROVISIONAL_POLL case=%d start\n", (int)mode);
            test_jump_nested_ssh(mode);
            fprintf(stderr, "SSH_NATIVE_PROVISIONAL_POLL case=%d pass\n", (int)mode);
        }
        printf("SSH_NATIVE_PROVISIONAL_POLL checks=%u gate=pass runtime=synthetic\n", checks);
        return 0;
    }
    if (argc == 3 && strcmp(argv[1], "--provisional-new-case") == 0) {
        char *end = NULL;
        long value = strtol(argv[2], &end, 10);
        CHECK(end != NULL && *end == '\0' && value >= CHILD_PROVISIONAL_NEW
            && value <= CHILD_PROVISIONAL_POLL_TIMEOUT);
        fprintf(stderr, "SSH_NATIVE_PROVISIONAL case=%ld start\n", value);
        test_jump_nested_ssh((enum child_case)value);
        fprintf(stderr, "SSH_NATIVE_PROVISIONAL case=%ld pass\n", value);
        return 0;
    }
    if (argc == 2 && strcmp(argv[1], "--provisional-new-only") == 0) {
        fprintf(stderr, "SSH_NATIVE_PROVISIONAL root start\n");
        test_provisional_root_uses_normal_new();
        fprintf(stderr, "SSH_NATIVE_PROVISIONAL root pass\n");
        for (enum child_case mode = CHILD_PROVISIONAL_NEW; mode <= CHILD_PROVISIONAL_POLL_TIMEOUT; mode++) {
            fprintf(stderr, "SSH_NATIVE_PROVISIONAL case=%d start\n", (int)mode);
            test_jump_nested_ssh(mode);
            fprintf(stderr, "SSH_NATIVE_PROVISIONAL case=%d pass\n", (int)mode);
        }
        fprintf(stderr, "SSH_NATIVE_PROVISIONAL three-layer start\n");
        test_jump_three_layer_ancestor_cancel(12);
        fprintf(stderr, "SSH_NATIVE_PROVISIONAL three-layer pass\n");
        printf("SSH_NATIVE_PROVISIONAL_NEW checks=%u gate=pass runtime=synthetic\n", checks);
        return 0;
    }
    if (argc == 2 && strcmp(argv[1], "--jump-retain-only") == 0) {
        for (int mode = 2; mode <= 5; mode++) test_jump_three_layer_ancestor_cancel(mode);
        test_jump_three_layer_ancestor_cancel(9);
        test_jump_three_layer_ancestor_cancel(12);
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
        test_shell_promotion_view_revocation();
        test_shell_promotion_role_expiry();
        test_jump_three_layer_ancestor_cancel(6);
        test_jump_three_layer_ancestor_cancel(7);
        test_jump_three_layer_ancestor_cancel(8);
        test_jump_three_layer_ancestor_cancel(10);
#endif
        printf("SSH_NATIVE_JUMP_RETAIN checks=%u gate=pass runtime=synthetic\n", checks);
        return 0;
    }
    if (argc == 2 && strcmp(argv[1], "--role-capacity-only") == 0) {
        test_nine_kex_role_slots();
        printf("SSH_NATIVE_ROLE_CAPACITY checks=%u gate=pass runtime=synthetic\n", checks);
        return 0;
    }
    if (argc == 2 && strcmp(argv[1], "--jump-only") == 0) {
        test_callback_ancestor_root_and_forged_owner();
        test_jump_channel_roundtrip();
        test_jump_channel_admission();
        test_jump_chain_identity_admission();
        test_jump_channel_rejection_and_protocol();
        test_jump_channel_lifetime();
        test_jump_channel_receive_bound();
        test_jump_relay_roundtrip();
        test_jump_relay_revocation();
        test_jump_relay_peer_backpressure();
        test_jump_relay_remote_close();
        for (enum child_case mode = CHILD_ROUNDTRIP; mode < CHILD_CASE_COUNT; mode++) {
            fprintf(stderr, "SSH_NATIVE_JUMP child=%d start\n", (int)mode);
            test_jump_nested_ssh(mode);
            fprintf(stderr, "SSH_NATIVE_JUMP child=%d pass\n", (int)mode);
        }
        test_jump_three_layer_ancestor_cancel(0);
        test_jump_three_layer_ancestor_cancel(1);
        for (int mode = 2; mode <= 5; mode++) test_jump_three_layer_ancestor_cancel(mode);
        test_jump_three_layer_ancestor_cancel(9);
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
        test_shell_promotion_view_revocation();
        test_shell_promotion_role_expiry();
        test_jump_three_layer_ancestor_cancel(6);
        test_jump_three_layer_ancestor_cancel(7);
        test_jump_three_layer_ancestor_cancel(8);
        test_jump_three_layer_ancestor_cancel(10);
#endif
        test_jump_child_relay_open_failure();
        test_jump_authentication_methods();
        printf("SSH_NATIVE_JUMP checks=%u gate=pass runtime=synthetic\n", checks);
        return 0;
    }
    if (argc == 2 && strcmp(argv[1], "--mfa-only") == 0) {
        test_mfa_continuation();
        printf("SSH_NATIVE_MFA checks=%u gate=pass runtime=synthetic\n", checks);
        return 0;
    }
    if (argc == 2 && strcmp(argv[1], "--keyboard-only") == 0) {
        test_keyboard_rounds_and_outcomes();
        test_keyboard_wire_echo_booleans();
        test_keyboard_exact_wire_bounds();
        test_keyboard_protocol_limits();
        test_keyboard_negative_host();
        test_keyboard_answer_ownership();
        printf("SSH_NATIVE_KEYBOARD checks=%u gate=pass runtime=synthetic\n", checks);
        return 0;
    }
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
    if (argc == 2 && strcmp(argv[1], "--changed-host-only") == 0) {
        test_changed_same_connection();
        test_changed_stale_source();
        test_changed_auth_continuation();
        printf("SSH_NATIVE_CHANGED_HOST checks=%u gate=pass runtime=synthetic\n", checks);
        return 0;
    }
    if (argc == 2 && strcmp(argv[1], "--server-alive-only") == 0) {
        test_server_alive_protocol();
        printf("SSH_NATIVE_SERVER_ALIVE checks=%u gate=pass runtime=synthetic\n", checks);
        return 0;
    }
    if (argc == 2 && strcmp(argv[1], "--shell-early-output-only") == 0) {
        test_shell_early_output();
        printf("SSH_NATIVE_SHELL_EARLY_OUTPUT checks=%u gate=pass runtime=synthetic\n", checks);
        return 0;
    }
    if (argc == 2 && strcmp(argv[1], "--auth-discovery-only") == 0) {
        test_auth_discovery();
        test_keyboard_auth_discovery();
        test_discovered_keyboard_with_selected_key();
        test_discovered_password();
        test_discovered_key_then_keyboard();
        test_discovered_rejected_key_alternatives();
        test_discovered_keyboard_then_password_or_key();
        test_discovered_three_methods();
        test_auth_prompt_transport();
        printf("SSH_NATIVE_AUTH_DISCOVERY checks=%u gate=pass runtime=synthetic\n", checks);
        return 0;
    }
    if (argc == 2 && strcmp(argv[1], "--shell-only") == 0) {
        test_shell_no_pty();
        test_shell_roundtrip();
        test_shell_rejection_and_auth_gate();
        test_shell_early_output();
        test_shell_window_and_backpressure();
        test_shell_bad_packets();
        test_shell_liveness_and_eof();
        test_shell_after_auth_expiry();
        printf("SSH_NATIVE_SHELL checks=%u gate=pass runtime=synthetic\n", checks);
        return 0;
    }
    CHECK(argc == 1);
    test_callback_ancestor_root_and_forged_owner();
    test_nine_kex_role_slots();
    test_jump_channel_roundtrip();
    test_jump_channel_admission();
    test_jump_chain_identity_admission();
    test_jump_channel_rejection_and_protocol();
    test_jump_channel_lifetime();
    test_jump_channel_receive_bound();
    test_jump_relay_roundtrip();
    test_jump_relay_revocation();
    test_jump_relay_peer_backpressure();
    test_jump_relay_remote_close();
    for (enum child_case mode = CHILD_ROUNDTRIP; mode < CHILD_CASE_COUNT; mode++)
        test_jump_nested_ssh(mode);
    test_jump_three_layer_ancestor_cancel(0);
    test_jump_three_layer_ancestor_cancel(1);
    for (int mode = 2; mode <= 5; mode++) test_jump_three_layer_ancestor_cancel(mode);
    test_jump_three_layer_ancestor_cancel(9);
    test_jump_three_layer_ancestor_cancel(12);
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
    test_shell_promotion_view_revocation();
    test_shell_promotion_role_expiry();
    test_jump_three_layer_ancestor_cancel(6);
    test_jump_three_layer_ancestor_cancel(7);
    test_jump_three_layer_ancestor_cancel(8);
    test_jump_three_layer_ancestor_cancel(10);
#endif
    test_jump_child_relay_open_failure();
    test_jump_authentication_methods();
    test_pending_host_guard();
    test_managed_host_denials();
    test_changed_same_connection();
    test_changed_stale_source();
    test_changed_auth_continuation();
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
    test_keyboard_rounds_and_outcomes();
    test_keyboard_wire_echo_booleans();
    test_keyboard_exact_wire_bounds();
    test_keyboard_protocol_limits();
    test_keyboard_negative_host();
    test_keyboard_answer_ownership();
    test_mfa_continuation();
    test_server_alive_protocol();
    test_auth_discovery();
    test_keyboard_auth_discovery();
    test_discovered_keyboard_with_selected_key();
    test_discovered_password();
    test_discovered_key_then_keyboard();
    test_discovered_rejected_key_alternatives();
    test_discovered_keyboard_then_password_or_key();
    test_discovered_three_methods();
    test_auth_prompt_transport();
    test_shell_no_pty();
    test_shell_roundtrip();
    test_shell_rejection_and_auth_gate();
    test_shell_early_output();
    test_shell_window_and_backpressure();
    test_shell_bad_packets();
    test_shell_liveness_and_eof();
    test_shell_after_auth_expiry();
    printf("SSH_NATIVE_CLIENT checks=%u admission=pass descriptor=pass view=pass close=pass open_failure_ownership=pass runtime=synthetic\n",
        checks);
    return 0;
}

#define _GNU_SOURCE

#include "ssh_pinned_client.h"
#include "ssh_hostfile_issuer.h"

#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#include "digest.h"
#include "hostfile.h"
#include "kex.h"
#include "myproposal.h"
#include "packet.h"
#include "ssh2.h"
#include "ssh_api.h"
#include "ssh_role_bootstrap.h"
#include "sshkey.h"

int ctos_hostfile_write_entry(FILE *, const char *, const char *,
    const struct sshkey *, int);

#ifdef malloc
#undef malloc
#endif
#ifdef calloc
#undef calloc
#endif

static unsigned int checks;
static unsigned int cases;

#define CHECK(condition) do { \
    checks++; \
    if (!(condition)) { \
        fprintf(stderr, "SSH_PINNED_CLIENT failure line=%u errno=%d\\n", \
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

static void wire_root(struct wire *wire) {
    unsigned char type = CTOS_SSH_CONFIG;

    wire_put(wire, &type, sizeof(type));
    wire_text(wire, "root");
    wire_text(wire, "ssh/config");
    wire_text(wire, "pinned client managed config");
    wire_u32(wire, 0);
    wire_u32(wire, 1);
    wire_text(wire, "ssh/known_hosts");
    wire_u32(wire, 0);
}

static void wire_known_hosts(struct wire *wire, const unsigned char *body,
    size_t length) {
    unsigned char type = CTOS_SSH_KNOWN_HOSTS;

    wire_put(wire, &type, sizeof(type));
    wire_text(wire, "trust");
    wire_text(wire, "ssh/known_hosts");
    wire_text(wire, "pinned client host evidence");
    wire_u32(wire, (uint32_t)length);
    wire_u32(wire, 0);
    wire_u32(wire, (uint32_t)length);
    wire_put(wire, body, length);
}

static struct ctos_ssh_view *load_view(const unsigned char *body, size_t length,
    uint64_t deadline) {
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
    wire_text(&wire, "pinned-client-request");
    wire_text(&wire, "pinned-client-connection");
    wire_text(&wire, "ssh/config");
    wire_put(&wire, binding, sizeof(binding));
    wire_u32(&wire, 2);
    wire_root(&wire);
    wire_known_hosts(&wire, body, length);

    view = malloc(sizeof(*view));
    CHECK(view != NULL);
    memset(view, 0, sizeof(*view));
    CHECK(ctos_ssh_view_allocate(view, wire.length) == 0);
    memcpy(view->mapping, wire.bytes, wire.length);
    CHECK(ctos_ssh_view_validate(view) == 0);
    return view;
}

static void destroy_view(struct ctos_ssh_view *view) {
    CHECK(view != NULL);
    CHECK(ctos_ssh_view_destroy(view) == 0);
    free(view);
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

static ctos_ssh_role_token open_role(uint64_t deadline) {
    unsigned char nonce[32] = {3};
    unsigned char connection[32] = {4};
    struct ctos_ssh_role_request request = {
        .program = CTOS_SSH_ROLE_CLIENT,
        .owner_pid = getpid(),
        .owner_uid = getuid(),
        .hop = 0,
        .port = 22,
        .deadline = deadline,
        .connection_id = "pinned-client-connection",
        .target_identity = "target:user@example.invalid:22",
        .host = "example.invalid",
        .public_view_id = "pinned-client-view",
        .role_nonce = nonce,
        .connection_binding = connection
    };
    ctos_ssh_role_token role = 0;

    CHECK(ctos_ssh_role_bootstrap_open(&request, &role) == 0 && role != 0);
    return role;
}

struct callback_fixture {
    ctos_ssh_callback_token callback;
    void *environment;
    unsigned int host_evidence_calls;
    unsigned int candidate_calls;
    unsigned int candidate_abort_calls;
    unsigned int kex_calls;
    unsigned int event_order;
    int fail_candidate;
    int fail_host_evidence;
    int fail_protocol;
    int protocol_fd;
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
    CHECK(strcmp(evidence->host, "example.invalid") == 0);
    CHECK(strcmp(evidence->connection_id, "pinned-client-connection") == 0);
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
    fixture->host_evidence_calls++;
    fixture->event_order = fixture->event_order == 0 ? 1U : fixture->event_order;
    return 0;
}

static int candidate(void *context, void *environment,
    const struct ctos_ssh_known_hosts_candidate *candidate) {
    struct callback_fixture *fixture = context;

    CHECK(fixture != NULL && fixture->environment == environment);
    CHECK(candidate != NULL);
    CHECK(candidate->evidence.classification == CTOS_SSH_CALLBACK_HOST_NEW);
    CHECK(candidate->replacement_body != NULL && candidate->replacement_body_length > 0);
    fixture->candidate_calls++;
    fixture->event_order = fixture->event_order == 0 ? 1U : fixture->event_order;
    if (fixture->fail_candidate) return EACCES;
    if (fixture->fail_protocol && fixture->protocol_fd >= 0) {
        CHECK(shutdown(fixture->protocol_fd, SHUT_RDWR) == 0);
        fixture->protocol_fd = -1;
    }
    return 0;
}

static int candidate_abort(void *context, void *environment) {
    struct callback_fixture *fixture = context;

    CHECK(fixture != NULL && fixture->environment == environment);
    fixture->candidate_abort_calls++;
    return 0;
}

static int kex_complete(void *context, void *environment, uint64_t cycle,
    const unsigned char session_digest[32]) {
    struct callback_fixture *fixture = context;
    unsigned char combined = 0;

    CHECK(fixture != NULL && fixture->environment == environment);
    CHECK(cycle != 0 && session_digest != NULL);
    for (size_t index = 0; index < 32; index++) combined |= session_digest[index];
    CHECK(combined != 0);
    CHECK(fixture->event_order == 1U);
    fixture->event_order = 2U;
    fixture->kex_calls++;
    return 0;
}

static int credential(void *context, void *environment, int kind) {
    (void)kind;
    CHECK(context != NULL && environment == context);
    return 0;
}

static int release(void *context, void *environment) {
    CHECK(context != NULL && environment == context);
    return 0;
}

static ctos_ssh_callback_token open_callback(struct callback_fixture *fixture,
    ctos_ssh_role_token role, uint64_t deadline) {
    struct ctos_ssh_callback_target target = {
        .context = fixture,
        .validate_environment = validate_environment,
        .host_evidence = host_evidence,
        .known_hosts_candidate = candidate,
        .known_hosts_candidate_abort = candidate_abort,
        .kex_complete = kex_complete,
        .credential = credential,
        .release = release
    };
    ctos_ssh_callback_token callback = 0;

    fixture->environment = fixture;
    CHECK(ctos_ssh_callback_open(&target, role, (int)getuid(), deadline,
        fixture->environment, &callback) == 0 && callback != 0);
    fixture->callback = callback;
    return callback;
}

static void make_params(struct kex_params *params) {
    char *proposals[PROPOSAL_MAX] = {KEX_CLIENT};

    memset(params, 0, sizeof(*params));
    memcpy(params->proposal, proposals, sizeof(proposals));
}

static int expected_fingerprint(const struct sshkey *key, unsigned char output[32]) {
    unsigned char *fingerprint = NULL;
    size_t length = 0;
    int result = sshkey_fingerprint_raw(key, SSH_DIGEST_SHA256,
        &fingerprint, &length);

    if (result == 0 && length == 32) memcpy(output, fingerprint, 32);
    if (fingerprint != NULL) {
        memset(fingerprint, 0, length);
        free(fingerprint);
    }
    return result == 0 && length == 32 ? 0 : -1;
}

static size_t revoked_body(const struct sshkey *key, unsigned char *output,
    size_t capacity) {
    unsigned char line[4096];
    size_t length = format_host_key(key, line, sizeof(line));
    size_t prefix_length = strlen("@revoked ");

    CHECK(prefix_length + length < capacity);
    memcpy(output, "@revoked ", prefix_length);
    memcpy(output + prefix_length, line, length);
    return prefix_length + length;
}

struct case_state {
    struct ctos_ssh_pinned_client *client;
    struct ssh *server;
    struct ctos_ssh_view *view;
    struct callback_fixture callback;
    ctos_ssh_role_token role;
    struct sshkey *private_key;
    struct sshkey *public_key;
    int pair[2];
};

static void case_setup(struct case_state *state, const unsigned char *body,
    size_t body_length, int fail_candidate, unsigned int lifetime_ms) {
    struct ctos_ssh_pinned_client_config config;
    unsigned char fingerprint[32];
    struct kex_params params;
    uint64_t deadline = deadline_after(lifetime_ms);
    ctos_ssh_callback_token callback;

    memset(state, 0, sizeof(*state));
    state->pair[0] = state->pair[1] = -1;
    CHECK(sshkey_generate(KEY_ED25519, 0, &state->private_key) == 0);
    CHECK(sshkey_from_private(state->private_key, &state->public_key) == 0);
    CHECK(expected_fingerprint(state->public_key, fingerprint) == 0);
    state->view = load_view(body, body_length, deadline);
    state->role = open_role(deadline);
    state->callback.fail_candidate = fail_candidate;
    callback = open_callback(&state->callback, state->role, deadline);
    CHECK(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0,
        state->pair) == 0);
    config = (struct ctos_ssh_pinned_client_config){
        .role = state->role,
        .socket_fd = state->pair[0],
        .owns_socket = 1,
        .deadline = deadline,
        .server_host_public_key = state->public_key,
        .expected_host_fingerprint = fingerprint,
        .managed_view = state->view,
        .known_hosts_path = "ssh/known_hosts",
        .host = "example.invalid",
        .candidate_id = "pinned-candidate",
        .callback = callback,
        .callback_environment = state->callback.environment,
        .read_chunk = 3,
        .write_chunk = 3
    };
    CHECK(ctos_ssh_pinned_client_open(&config, &state->client) == 0);
    make_params(&params);
    CHECK(ssh_init(&state->server, 1, &params) == 0 && state->server != NULL);
    CHECK(ssh_packet_set_connection(state->server, state->pair[1],
        state->pair[1]) != NULL);
    ssh_packet_set_nonblocking(state->server);
    CHECK(ssh_add_hostkey(state->server, state->private_key) == 0);
}

static int pump_server(struct ssh *server, int fd, size_t chunk,
    int *read_would_block, int *partial_write) {
    unsigned char input[64];
    const unsigned char *output;
    size_t output_length;
    size_t transfer;
    ssize_t received;
    ssize_t sent;
    u_char packet_type = SSH_MSG_NONE;
    int result;

    received = recv(fd, input, chunk < sizeof(input) ? chunk : sizeof(input),
        MSG_DONTWAIT);
    if (received > 0) {
        if (ssh_input_append(server, input, (size_t)received) != 0) return -1;
    } else if (received == 0) {
        return -1;
    } else if (errno == EAGAIN || errno == EWOULDBLOCK) {
        if (read_would_block != NULL) *read_would_block = 1;
    } else if (errno != EINTR) {
        return -1;
    }
    result = ssh_packet_next(server, &packet_type);
    if (result != 0) return result;
    if (packet_type == SSH2_MSG_EXT_INFO
        && kex_input_ext_info(packet_type, 0, server) != 0) return -1;
    if (packet_type != SSH_MSG_NONE && packet_type != SSH2_MSG_EXT_INFO) return -1;
    output = ssh_output_ptr(server, &output_length);
    if (output == NULL || output_length == 0) return 0;
    transfer = output_length < chunk ? output_length : chunk;
    sent = send(fd, output, transfer, MSG_NOSIGNAL);
    if (sent > 0) {
        if ((size_t)sent < output_length && partial_write != NULL)
            *partial_write = 1;
        if (ssh_output_consume(server, (size_t)sent) != 0) return -1;
    } else if (sent < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
        return 0;
    } else if (sent < 0 && errno == EINTR) {
        return 0;
    } else {
        return -1;
    }
    return 0;
}

static void case_cleanup(struct case_state *state) {
    int descriptor;

    CHECK(ctos_ssh_pinned_client_close(state->client) == 0);
    errno = 0;
    CHECK(ctos_ssh_pinned_client_step(state->client,
        &(struct ctos_ssh_pinned_client_step){0}) != 0 && errno == ENOENT);
    errno = 0;
    CHECK(ctos_ssh_pinned_client_close(state->client) != 0 && errno == ENOENT);
    descriptor = fcntl(state->pair[0], F_GETFD);
    CHECK(descriptor < 0 && errno == EBADF);
    CHECK(ctos_ssh_pinned_client_destroy(state->client) == 0);
    ssh_free(state->server);
    errno = 0;
    CHECK(fcntl(state->pair[1], F_GETFD) < 0 && errno == EBADF);
    CHECK(ctos_ssh_callback_close(state->callback.callback,
        state->callback.environment) == 0);
    CHECK(ctos_ssh_role_bootstrap_close(state->role) == 0);
    destroy_view(state->view);
    sshkey_free(state->public_key);
    sshkey_free(state->private_key);
}

static void run_known_case(void) {
    struct case_state state;
    unsigned char body[4096];
    size_t body_length;
    int saw_read_would_block = 0;
    int saw_partial_write = 0;
    int server_partial_write = 0;
    int completed = 0;

    /* First create the key and body in a dedicated state, then use the same
     * generated key as the pinned server through the common setup path. */
    memset(&state, 0, sizeof(state));
    state.pair[0] = state.pair[1] = -1;
    CHECK(sshkey_generate(KEY_ED25519, 0, &state.private_key) == 0);
    CHECK(sshkey_from_private(state.private_key, &state.public_key) == 0);
    body_length = format_host_key(state.public_key, body, sizeof(body));
    sshkey_free(state.public_key);
    sshkey_free(state.private_key);
    /* case_setup intentionally owns key generation; known body is regenerated
     * below by setup's server key would not match, so use an empty setup only
     * for lifecycle is not acceptable. Build the complete case inline. */
    memset(&state, 0, sizeof(state));
    state.pair[0] = state.pair[1] = -1;
    CHECK(sshkey_generate(KEY_ED25519, 0, &state.private_key) == 0);
    CHECK(sshkey_from_private(state.private_key, &state.public_key) == 0);
    body_length = format_host_key(state.public_key, body, sizeof(body));
    {
        struct ctos_ssh_pinned_client_config config;
        unsigned char fingerprint[32];
        struct kex_params params;
        uint64_t deadline = deadline_after(15000);
        ctos_ssh_callback_token callback;

        CHECK(expected_fingerprint(state.public_key, fingerprint) == 0);
        state.view = load_view(body, body_length, deadline);
        state.role = open_role(deadline);
        callback = open_callback(&state.callback, state.role, deadline);
        CHECK(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0,
            state.pair) == 0);
        config = (struct ctos_ssh_pinned_client_config){
            .role = state.role, .socket_fd = state.pair[0], .owns_socket = 1,
            .deadline = deadline, .server_host_public_key = state.public_key,
            .expected_host_fingerprint = fingerprint, .managed_view = state.view,
            .known_hosts_path = "ssh/known_hosts", .host = "example.invalid",
            .candidate_id = "pinned-candidate", .callback = callback,
            .callback_environment = state.callback.environment,
            .read_chunk = 3, .write_chunk = 3
        };
        CHECK(ctos_ssh_pinned_client_open(&config, &state.client) == 0);
        make_params(&params);
        CHECK(ssh_init(&state.server, 1, &params) == 0 && state.server != NULL);
        CHECK(ssh_packet_set_connection(state.server, state.pair[1],
            state.pair[1]) != NULL);
        ssh_packet_set_nonblocking(state.server);
        CHECK(ssh_add_hostkey(state.server, state.private_key) == 0);
    }
    for (unsigned int attempt = 0; attempt < 20000; attempt++) {
        struct ctos_ssh_pinned_client_step step = {0};
        CHECK(ctos_ssh_pinned_client_step(state.client, &step) == 0);
        saw_read_would_block |= step.read_would_block;
        saw_partial_write |= step.partial_write;
        CHECK(pump_server(state.server, state.pair[1], 2, NULL,
            &server_partial_write) == 0);
        if (ctos_ssh_pinned_client_complete(state.client)
            && state.server->kex->done) {
            completed = 1;
            break;
        }
    }
    CHECK(completed == 1);
    CHECK(state.callback.host_evidence_calls == 1);
    CHECK(state.callback.candidate_calls == 0);
    CHECK(state.callback.kex_calls == 1);
    CHECK(state.callback.event_order == 2U);
    CHECK(saw_read_would_block == 1);
    CHECK(saw_partial_write == 1 && server_partial_write == 1);
    errno = 0;
    CHECK(ctos_ssh_pinned_client_step(state.client,
        &(struct ctos_ssh_pinned_client_step){0}) != 0 && errno == EALREADY);
    case_cleanup(&state);
    cases++;
}

static void run_rejected_case(int mode) {
    struct case_state state;
    unsigned char body[4096];
    size_t body_length = 0;
    struct sshkey *other = NULL;
    unsigned int attempts;
    int failed = 0;

    memset(&state, 0, sizeof(state));
    state.pair[0] = state.pair[1] = -1;
    /* Generate the server key first so changed/revoked body can be exact. */
    CHECK(sshkey_generate(KEY_ED25519, 0, &state.private_key) == 0);
    CHECK(sshkey_from_private(state.private_key, &state.public_key) == 0);
    if (mode == 1) {
        CHECK(sshkey_generate(KEY_ED25519, 0, &other) == 0);
        body_length = format_host_key(other, body, sizeof(body));
    } else if (mode == 2) {
        body_length = revoked_body(state.public_key, body, sizeof(body));
    }
    {
        struct ctos_ssh_pinned_client_config config;
        unsigned char fingerprint[32];
        struct kex_params params;
        uint64_t deadline = deadline_after(15000);
        ctos_ssh_callback_token callback;

        CHECK(expected_fingerprint(state.public_key, fingerprint) == 0);
        state.view = load_view(body, body_length, deadline);
        state.role = open_role(deadline);
        state.callback.fail_candidate = mode == 3;
        state.callback.fail_host_evidence = mode == 4;
        callback = open_callback(&state.callback, state.role, deadline);
        CHECK(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0,
            state.pair) == 0);
        config = (struct ctos_ssh_pinned_client_config){
            .role = state.role, .socket_fd = state.pair[0], .owns_socket = 1,
            .deadline = deadline, .server_host_public_key = state.public_key,
            .expected_host_fingerprint = fingerprint, .managed_view = state.view,
            .known_hosts_path = "ssh/known_hosts", .host = "example.invalid",
            .candidate_id = "pinned-candidate", .callback = callback,
            .callback_environment = state.callback.environment,
            .read_chunk = 3, .write_chunk = 3
        };
        CHECK(ctos_ssh_pinned_client_open(&config, &state.client) == 0);
        make_params(&params);
        CHECK(ssh_init(&state.server, 1, &params) == 0 && state.server != NULL);
        CHECK(ssh_packet_set_connection(state.server, state.pair[1],
            state.pair[1]) != NULL);
        ssh_packet_set_nonblocking(state.server);
        CHECK(ssh_add_hostkey(state.server, state.private_key) == 0);
    }
    for (attempts = 0; attempts < 20000; attempts++) {
        struct ctos_ssh_pinned_client_step step = {0};
        if (ctos_ssh_pinned_client_step(state.client, &step) != 0) {
            failed = 1;
            break;
        }
        CHECK(pump_server(state.server, state.pair[1], 2, NULL, NULL) == 0);
    }
    CHECK(failed == 1);
    CHECK(!ctos_ssh_pinned_client_complete(state.client));
    CHECK(state.callback.kex_calls == 0);
    if (mode == 3 || mode == 4) {
        CHECK(state.callback.candidate_calls == 1);
        CHECK(state.callback.candidate_abort_calls == 1);
        if (mode == 4) CHECK(state.callback.host_evidence_calls == 0);
    } else {
        CHECK(state.callback.host_evidence_calls == 0);
        CHECK(state.callback.candidate_calls == 0);
    }
    case_cleanup(&state);
    sshkey_free(other);
    cases++;
}

static void run_cancel_eof_deadline(void) {
    struct case_state state;
    struct ctos_ssh_pinned_client_step step;

    case_setup(&state, NULL, 0, 0, 15000);
    step = (struct ctos_ssh_pinned_client_step){.cancelled = 1};
    errno = 0;
    CHECK(ctos_ssh_pinned_client_step(state.client, &step) != 0
        && errno == ECANCELED);
    case_cleanup(&state);
    cases++;

    case_setup(&state, NULL, 0, 0, 15000);
    CHECK(close(state.pair[1]) == 0);
    errno = 0;
    CHECK(ctos_ssh_pinned_client_step(state.client,
        &(struct ctos_ssh_pinned_client_step){0}) != 0 && errno == EPIPE);
    case_cleanup(&state);
    cases++;

    case_setup(&state, NULL, 0, 0, 100);
    {
        struct timespec delay = {.tv_sec = 0, .tv_nsec = 200000000L};
        CHECK(nanosleep(&delay, NULL) == 0);
    }
    errno = 0;
    CHECK(ctos_ssh_pinned_client_step(state.client,
        &(struct ctos_ssh_pinned_client_step){0}) != 0 && errno == ETIMEDOUT);
    case_cleanup(&state);
    cases++;
}

static void run_protocol_rollback_case(void) {
    struct case_state state;
    unsigned int attempts;
    int failed = 0;

    case_setup(&state, NULL, 0, 0, 15000);
    state.callback.fail_protocol = 1;
    state.callback.protocol_fd = state.pair[1];
    for (attempts = 0; attempts < 20000; attempts++) {
        struct ctos_ssh_pinned_client_step step = {0};
        if (ctos_ssh_pinned_client_step(state.client, &step) != 0) {
            failed = 1;
            break;
        }
        if (state.callback.candidate_calls != 0) continue;
        CHECK(pump_server(state.server, state.pair[1], 2, NULL, NULL) == 0);
    }
    CHECK(failed == 1);
    CHECK(state.callback.candidate_calls == 1);
    CHECK(state.callback.host_evidence_calls == 1);
    CHECK(state.callback.candidate_abort_calls == 1);
    CHECK(state.callback.kex_calls == 0);
    CHECK(!ctos_ssh_pinned_client_complete(state.client));
    case_cleanup(&state);
    cases++;
}

int main(void) {
    printf("SSH_PINNED_CLIENT phase=socketpair-kex state=start\\n");
    run_known_case();
    run_rejected_case(1); /* CHANGED */
    run_rejected_case(2); /* REVOKED */
    run_rejected_case(3); /* NEW with failed synchronous candidate */
    run_rejected_case(4); /* NEW with failed host evidence rollback */
    run_cancel_eof_deadline();
    run_protocol_rollback_case();
    printf("SSH_PINNED_CLIENT cases=%u checks=%u host_callback_before_kex=1 gate=pass\\n",
        cases, checks);
    return 0;
}

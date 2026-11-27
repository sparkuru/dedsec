/* Test-only real-sshd driver; the included synthetic matrix is not executed. */
#define main ctos_native_matrix_unused_main
#include "ssh_native_client_test.c"
#undef main

#include <arpa/inet.h>
#include <poll.h>
#include <signal.h>

#define JUMP_INTEROP_PASSWORD_BYTES 48U
#define JUMP_INTEROP_INPUT_BYTES (2U * JUMP_INTEROP_PASSWORD_BYTES)
#define JUMP_INTEROP_LABEL "SSH_JUMP_INTEROP"
#define JUMP_CHAIN_IDENTITY "chain:ctos-jump-fixture"
#define JUMP_ACCOUNT_IDENTITY "target:ctos-jump-account"
#define TARGET_ACCOUNT_IDENTITY "target:ctos-target-account"

struct jump_endpoint {
    struct fixture fixture;
    int descriptor;
};

struct jump_run {
    struct jump_endpoint jump;
    struct jump_endpoint target;
    struct ctos_ssh_view *view;
    struct ctos_ssh_view *fresh_view;
    struct ctos_ssh_native_jump_relay *relay;
    uint64_t deadline;
};

static void jump_wipe(void *memory, size_t length) {
    volatile unsigned char *bytes = memory;
    while (length-- != 0U) *bytes++ = 0;
}

static int jump_read_passwords(unsigned char output[JUMP_INTEROP_INPUT_BYTES + 1U]) {
    size_t length = 0;
    for (;;) {
        ssize_t count = read(STDIN_FILENO, output + length, JUMP_INTEROP_INPUT_BYTES + 1U - length);
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) return -1;
        if (count == 0) break;
        length += (size_t)count;
        if (length > JUMP_INTEROP_INPUT_BYTES) {
            errno = EOVERFLOW;
            return -1;
        }
    }
    if (length != JUMP_INTEROP_INPUT_BYTES) { errno = EINVAL; return -1; }
    for (size_t index = 0; index < length; index++) {
        unsigned char byte = output[index];
        if (!((byte >= '0' && byte <= '9') || (byte >= 'a' && byte <= 'f')
                || (byte >= 'A' && byte <= 'F'))) {
            errno = EINVAL;
            return -1;
        }
    }
    if (memcmp(output, output + JUMP_INTEROP_PASSWORD_BYTES, JUMP_INTEROP_PASSWORD_BYTES) == 0) {
        errno = EINVAL;
        return -1;
    }
    return 0;
}

static int jump_number(const char *text, unsigned int *value) {
    char *end;
    unsigned long number;
    if (*text < '0' || *text > '9') return -1;
    errno = 0;
    number = strtoul(text, &end, 10);
    if (errno != 0 || *end != '\0' || number == 0U || number > 65535U) return -1;
    *value = (unsigned int)number;
    return 0;
}

static struct sshkey *jump_public_key(const char *path) {
    unsigned char body[16384];
    size_t length;
    char *cursor;
    struct sshkey *key = NULL;
    FILE *stream = fopen(path, "rb");
    if (stream == NULL) return NULL;
    length = fread(body, 1, sizeof(body) - 1U, stream);
    int invalid = ferror(stream) || length == 0U || length == sizeof(body) - 1U
        || memchr(body, 0, length) != NULL;
    if (fclose(stream) != 0) invalid = 1;
    if (invalid) { errno = EINVAL; return NULL; }
    body[length] = 0;
    unsigned char *newline = memchr(body, '\n', length);
    if (newline != NULL && newline != body + length - 1U) { errno = EINVAL; return NULL; }
    cursor = (char *)body;
    key = sshkey_new(KEY_UNSPEC);
    if (key == NULL || sshkey_read(key, &cursor) != 0 || sshkey_is_cert(key)) {
        sshkey_free(key);
        errno = EINVAL;
        return NULL;
    }
    return key;
}

/* The pinned callback helper hardcodes example.invalid. This fixture instead
 * verifies each exact loopback host, port, role and generated fingerprint. */
static int jump_host_evidence(void *context, void *environment,
    const struct ctos_ssh_host_evidence *evidence, struct ctos_ssh_host_result *result) {
    struct callback_fixture *fixture = context;
    if (fixture == NULL || fixture->environment != environment || evidence == NULL || result == NULL
        || evidence->classification != CTOS_SSH_CALLBACK_HOST_KNOWN
        || evidence->role != fixture->expected_role || evidence->hop != fixture->expected_hop
        || evidence->port != fixture->expected_port || strcmp(evidence->host, "127.0.0.1") != 0
        || strcmp(evidence->connection_id, "native-client-connection") != 0
        || strcmp(evidence->target_identity, fixture->expected_hop == 0U
            ? JUMP_ACCOUNT_IDENTITY : TARGET_ACCOUNT_IDENTITY) != 0
        || strcmp(evidence->key_type, fixture->expected_key_type) != 0
        || strcmp(evidence->fingerprint, fixture->expected_fingerprint) != 0) return EACCES;
    fixture->host_evidence_calls++;
    fixture->host_evidence_event_order = ++fixture->callback_event_order;
    fixture->last_host_role = evidence->role;
    fixture->last_host_hop = evidence->hop;
    fixture->last_host_port = evidence->port;
    fixture->last_host_classification = evidence->classification;
    memset(result, 0, sizeof(*result));
    result->role = evidence->role;
    result->hop = evidence->hop;
    result->port = evidence->port;
    result->classification = evidence->classification;
    result->outcome = CTOS_SSH_CALLBACK_HOST_RESULT_APPROVED;
    result->cas_status = CTOS_SSH_CALLBACK_HOST_CAS_NOT_REQUIRED;
    strcpy(result->connection_id, evidence->connection_id);
    strcpy(result->target_identity, evidence->target_identity);
    strcpy(result->host, evidence->host);
    strcpy(result->key_type, evidence->key_type);
    strcpy(result->fingerprint, evidence->fingerprint);
    memcpy(result->base_digest, evidence->base_digest, sizeof(result->base_digest));
    memcpy(result->replacement_digest, evidence->replacement_digest, sizeof(result->replacement_digest));
    return 0;
}

/* Refuse the NEW candidate while recording the exact source classification. */
static int jump_unknown_candidate(void *context, void *environment,
    const struct ctos_ssh_known_hosts_candidate *candidate) {
    struct callback_fixture *fixture = context;
    if (fixture == NULL || fixture->environment != environment || candidate == NULL
        || candidate->evidence.classification != CTOS_SSH_CALLBACK_HOST_NEW
        || candidate->evidence.role != fixture->expected_role
        || candidate->evidence.hop != fixture->expected_hop
        || candidate->evidence.port != fixture->expected_port
        || strcmp(candidate->evidence.host, "127.0.0.1") != 0
        || strcmp(candidate->evidence.fingerprint, fixture->expected_fingerprint) != 0)
        return EACCES;
    fixture->candidate_calls++;
    return EACCES;
}

static int jump_unknown_candidate_abort(void *context, void *environment) {
    struct callback_fixture *fixture = context;
    if (fixture == NULL || fixture->environment != environment) return EACCES;
    fixture->candidate_abort_calls++;
    return 0;
}

/* The fixture supplies public wire bytes, not a vault write or CAS receipt. */
static struct ctos_ssh_view *jump_fresh_view(const unsigned char *body,
    size_t length, uint64_t deadline) {
    struct ctos_ssh_view *view = load_view(body, length, deadline);
    size_t binding_offset = (size_t)(view->binding.data - view->mapping);
    CHECK(view->epoch == 11U && view->generation == 91U && binding_offset < view->length);
    view->mapping[39] = 12U;
    view->mapping[binding_offset] ^= 0x40U;
    view->valid = 0;
    view->private_count = 0;
    view->public_bytes = 0;
    memset(view->files, 0, sizeof(view->files));
    CHECK(ctos_ssh_view_validate(view) == 0 && view->epoch == 12U);
    return view;
}

static int jump_connect(unsigned int port, uint64_t deadline) {
    struct sockaddr_in address = {.sin_family = AF_INET, .sin_port = htons((uint16_t)port)};
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) return -1;
    if (connect(fd, (const struct sockaddr *)&address, sizeof(address)) != 0) {
        if (errno != EINPROGRESS) goto fail;
        while (deadline_after(0) < deadline) {
            struct pollfd pending = {.fd = fd, .events = POLLOUT};
            int ready = poll(&pending, 1, 20), error = 0;
            socklen_t size = sizeof(error);
            if (ready < 0 && errno == EINTR) continue;
            if (ready < 0) goto fail;
            if (ready == 0) continue;
            if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &size) != 0) goto fail;
            if (error != 0) { errno = error; goto fail; }
            return fd;
        }
        errno = ETIMEDOUT;
        goto fail;
    }
    return fd;
fail:
    {
        int error = errno;
        close(fd);
        errno = error;
        return -1;
    }
}

static int jump_endpoint_prepare(struct jump_endpoint *endpoint, struct ctos_ssh_view *view,
    uint64_t deadline, unsigned int port, unsigned int hop, const char *username,
    const unsigned char *password, size_t password_length) {
    struct fixture *fixture = &endpoint->fixture;
    struct ctos_ssh_role_request request = role_request(deadline);
    static const unsigned char child_nonce[32] = {9};
    request.program = hop == 0U ? CTOS_SSH_ROLE_JUMP : CTOS_SSH_ROLE_CLIENT;
    request.hop = hop;
    request.host = "127.0.0.1";
    request.port = port;
    request.target_identity = hop == 0U ? JUMP_ACCOUNT_IDENTITY : TARGET_ACCOUNT_IDENTITY;
    request.chain_identity = JUMP_CHAIN_IDENTITY;
    if (hop != 0U) {
        request.public_view_id = "next-view";
        request.role_nonce = child_nonce;
    }
    fixture->deadline = deadline;
    fixture->callback.release_fd = -1;
    if (ctos_ssh_role_bootstrap_open(&request, &fixture->role) != 0) return -1;
    if (ctos_ssh_view_borrow_owned_view(view, &fixture->view_borrow) != 0) return -1;
    fixture->callback.environment = &fixture->callback;
    fixture->callback.expected_role = fixture->role;
    fixture->callback.expected_hop = hop;
    fixture->callback.expected_port = port;
    fixture->callback.selected_kind = CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD;
    fixture->callback.selected_body_override = 1;
    fixture->callback.selected_body = password;
    fixture->callback.selected_body_length = password_length;
    remember_expected_host_evidence(&fixture->callback, fixture->host_key);
    struct ctos_ssh_callback_target callbacks = {
        .context = &fixture->callback, .validate_environment = validate_environment,
        .host_evidence = jump_host_evidence, .known_hosts_candidate = jump_unknown_candidate,
        .known_hosts_candidate_abort = jump_unknown_candidate_abort,
        .kex_complete = kex_complete,
        .credential = credential, .credential_bytes = credential_bytes,
        .credential_bytes_selected = credential_bytes_selected, .release = release
    };
    if (ctos_ssh_callback_open(&callbacks, fixture->role, (int)getuid(), deadline,
            fixture->callback.environment, &fixture->callback_token) != 0) return -1;
    struct ctos_ssh_role_snapshot snapshot;
    if (ctos_ssh_role_bootstrap_snapshot(fixture->role, &snapshot) != 0) return -1;
    if (snapshot.identity_version != CTOS_SSH_ROLE_IDENTITY_V2
        || strcmp(snapshot.chain_identity, JUMP_CHAIN_IDENTITY) != 0
        || strcmp(snapshot.target_identity, request.target_identity) != 0) {
        errno = EACCES;
        return -1;
    }
    struct ctos_ssh_slice fields[5] = {
        {(const unsigned char *)username, strlen(username)},
        {(const unsigned char *)"interop-once", 12U}, {NULL, 0}, {NULL, 0}, {NULL, 0}
    };
    make_selection_wire(&fixture->selection_wire, &snapshot, 1U, fields);
    if (ssh_digest_memory(SSH_DIGEST_SHA256, fixture->selection_wire.bytes,
            fixture->selection_wire.length, fixture->callback.expected_selection_receipt, 32U) != 0)
        return -1;
    return 0;
}

static struct ctos_ssh_native_client_config jump_config(struct jump_endpoint *endpoint,
    uint64_t deadline, int descriptor) {
    struct fixture *fixture = &endpoint->fixture;
    return (struct ctos_ssh_native_client_config){
        .role = fixture->role, .socket_fd = descriptor, .owner_uid = getuid(), .owns_socket = 1,
        .callback = fixture->callback_token, .callback_environment = fixture->callback.environment,
        .deadline = deadline, .step_budget = CTOS_SSH_NATIVE_CLIENT_MAX_STEP_BYTES,
        .host_key_mode = CTOS_SSH_HOST_KEY_MANAGED, .managed_view_borrow = fixture->view_borrow,
        .known_hosts_path = "ssh/known_hosts", .host = "127.0.0.1", .candidate_id = "interop-unused",
        .authentication_selection_wire = fixture->selection_wire.bytes,
        .authentication_selection_wire_length = fixture->selection_wire.length
    };
}

static int jump_turn(struct jump_run *run) {
    struct timespec pause = {.tv_nsec = 1000000L};
    if (deadline_after(0) >= run->deadline) { errno = ETIMEDOUT; return -1; }
    if (ctos_ssh_native_client_step(run->jump.fixture.client,
            &(struct ctos_ssh_native_client_step){0}) != 0) return -1;
    if (run->relay != NULL && ctos_ssh_native_jump_relay_pump(run->relay) != 0) return -1;
    if (run->target.fixture.client != NULL && ctos_ssh_native_client_step(run->target.fixture.client,
            &(struct ctos_ssh_native_client_step){0}) != 0) return -1;
    if (run->relay != NULL && ctos_ssh_native_jump_relay_pump(run->relay) != 0) return -1;
    if (nanosleep(&pause, NULL) != 0 && errno != EINTR) return -1;
    return 0;
}

enum jump_wait_phase { WAIT_KEX, WAIT_SERVICE, WAIT_PASSWORD, WAIT_CHANNEL, WAIT_SHELL };

static int jump_wait(struct jump_run *run, struct fixture *fixture, enum jump_wait_phase phase) {
    for (;;) {
        int ready = 0;
        if (phase == WAIT_KEX) ready = ctos_ssh_native_client_complete(fixture->client);
        if (phase == WAIT_SERVICE) ready = ctos_ssh_native_client_userauth_service_ready(fixture->client);
        if (phase == WAIT_PASSWORD) {
            enum ctos_ssh_native_client_password_auth_result auth;
            if (ctos_ssh_native_client_password_auth_result(fixture->client, &auth) != 0) return -1;
            if (auth != CTOS_SSH_PASSWORD_AUTH_PENDING && auth != CTOS_SSH_PASSWORD_AUTH_SUCCESS) {
                errno = EACCES; return -1;
            }
            ready = auth == CTOS_SSH_PASSWORD_AUTH_SUCCESS;
        }
        if (phase == WAIT_CHANNEL) {
            struct ctos_ssh_native_jump_status status;
            if (ctos_ssh_native_client_jump_status(fixture->client, &status) != 0) return -1;
            if (status.phase == CTOS_SSH_JUMP_REJECTED || status.phase == CTOS_SSH_JUMP_CLOSED) {
                errno = EACCES; return -1;
            }
            ready = status.phase == CTOS_SSH_JUMP_OPEN;
        }
        if (phase == WAIT_SHELL) {
            struct ctos_ssh_native_shell_status status;
            if (ctos_ssh_native_client_shell_status(fixture->client, &status) != 0) return -1;
            if (status.phase == CTOS_SSH_SHELL_REJECTED || status.phase == CTOS_SSH_SHELL_CLOSED) {
                errno = EACCES; return -1;
            }
            ready = status.phase == CTOS_SSH_SHELL_RUNNING;
        }
        if (ready < 0) return -1;
        if (ready) return 0;
        if (jump_turn(run) != 0) return -1;
    }
}

static int jump_authenticate(struct jump_run *run, struct fixture *fixture) {
    if (jump_wait(run, fixture, WAIT_KEX) != 0 || fixture->callback.host_evidence_calls != 1U
        || fixture->callback.kex_complete_calls != 1U
        || fixture->callback.last_host_classification != CTOS_SSH_CALLBACK_HOST_KNOWN) return -1;
    if (ctos_ssh_native_client_begin_userauth_service(fixture->client) != 0
        || jump_wait(run, fixture, WAIT_SERVICE) != 0
        || ctos_ssh_native_client_begin_password_auth(fixture->client) != 0
        || jump_wait(run, fixture, WAIT_PASSWORD) != 0
        || fixture->callback.selected_calls != 1U
        || fixture->callback.credential_calls != 0U || fixture->callback.credential_bytes_calls != 0U)
        return -1;
    return 0;
}

static int jump_marker(struct jump_run *run) {
    static const unsigned char command[] = "printf '\\103\\124\\117\\123\\137\\112\\125\\115\\120\\137\\117\\113\\012'\n";
    static const unsigned char expected[] = "CTOS_JUMP_OK\r\n";
    unsigned char buffer[4096];
    size_t sent = 0U, retained = 0U, total = 0U;
    while (sent < sizeof(command) - 1U) {
        size_t accepted = 0U;
        if (ctos_ssh_native_client_shell_write(run->target.fixture.client,
                command + sent, sizeof(command) - 1U - sent, &accepted) != 0) return -1;
        sent += accepted;
        if (sent < sizeof(command) - 1U && jump_turn(run) != 0) return -1;
    }
    while (total < 1024U * 1024U) {
        size_t length = 0U;
        if (jump_turn(run) != 0 || ctos_ssh_native_client_shell_read(run->target.fixture.client,
                buffer + retained, sizeof(buffer) - retained, &length) != 0) return -1;
        total += length;
        size_t available = retained + length;
        if (memmem(buffer, available, expected, sizeof(expected) - 1U) != NULL) return 0;
        retained = available < sizeof(expected) - 1U ? available : sizeof(expected) - 2U;
        memmove(buffer, buffer + available - retained, retained);
    }
    errno = EOVERFLOW;
    return -1;
}

static int jump_cleanup_endpoint(struct jump_endpoint *endpoint) {
    struct fixture *fixture = &endpoint->fixture;
    if (fixture->client != NULL) {
        if (ctos_ssh_native_client_close(fixture->client) != 0) return -1;
        fixture->callback_token = 0;
        fixture->view_borrow = NULL;
        endpoint->descriptor = -1;
        if (ctos_ssh_native_client_destroy(fixture->client) != 0) return -1;
        fixture->client = NULL;
    } else {
        if (fixture->callback_token != 0) {
            if (ctos_ssh_callback_close(fixture->callback_token, fixture->callback.environment) != 0)
                return -1;
            fixture->callback_token = 0;
        }
        if (fixture->view_borrow != NULL) {
            if (ctos_ssh_view_borrow_release(fixture->view_borrow) != 0) return -1;
            fixture->view_borrow = NULL;
        }
        if (endpoint->descriptor >= 0) {
            int descriptor = endpoint->descriptor;
            endpoint->descriptor = -1;
            if (close(descriptor) != 0) return -1;
        }
    }
    if (fixture->role != 0 && ctos_ssh_role_bootstrap_close(fixture->role) != 0) return -1;
    fixture->role = 0;
    sshkey_free(fixture->host_key);
    fixture->host_key = NULL;
    return 0;
}

int main(int argc, char **argv) {
    struct jump_run run = {.jump.descriptor = -1, .target.descriptor = -1};
    unsigned char passwords[JUMP_INTEROP_INPUT_BYTES + 1U] = {0}, known_hosts[8192];
    unsigned int jump_port = 0, target_port = 0;
    const char *phase = "arguments";
    FILE *stream = NULL;
    int result = 1, saved_error = 0, cleanup_failed = 0;
    int fresh_known = argc == 9 && strcmp(argv[8], "--fresh-known") == 0;
    int stale_reject = argc == 9 && strcmp(argv[8], "--stale-reject") == 0;
    setvbuf(stdout, NULL, _IOLBF, 0);
    signal(SIGPIPE, SIG_IGN);
#define REQUIRE(condition) do { if (!(condition)) goto done; } while (0)
    REQUIRE((argc == 8 || fresh_known || stale_reject) && strcmp(argv[1], "127.0.0.1") == 0
        && jump_number(argv[2], &jump_port) == 0 && jump_number(argv[3], &target_port) == 0
        && jump_port != target_port && strlen(argv[4]) > 0U && strlen(argv[4]) <= 255U
        && strlen(argv[5]) > 0U && strlen(argv[5]) <= 255U && strcmp(argv[4], argv[5]) != 0);
    phase = "password-input";
    REQUIRE(jump_read_passwords(passwords) == 0);
    phase = "known-hosts";
    run.jump.fixture.host_key = jump_public_key(argv[6]);
    run.target.fixture.host_key = jump_public_key(argv[7]);
    REQUIRE(run.jump.fixture.host_key != NULL && run.target.fixture.host_key != NULL
        && !sshkey_equal(run.jump.fixture.host_key, run.target.fixture.host_key));
    stream = fmemopen(known_hosts, sizeof(known_hosts), "w+");
    REQUIRE(stream != NULL);
    char host_pattern[64];
    REQUIRE(snprintf(host_pattern, sizeof(host_pattern), "[127.0.0.1]:%u", jump_port) > 0);
    REQUIRE(ctos_hostfile_write_entry(stream, host_pattern, NULL, run.jump.fixture.host_key, 0) == 1);
    REQUIRE(fflush(stream) == 0);
    long jump_known_length = ftell(stream);
    REQUIRE(jump_known_length > 0 && (size_t)jump_known_length < sizeof(known_hosts));
    REQUIRE(snprintf(host_pattern, sizeof(host_pattern), "[127.0.0.1]:%u", target_port) > 0);
    REQUIRE(ctos_hostfile_write_entry(stream, host_pattern, NULL, run.target.fixture.host_key, 0) == 1);
    REQUIRE(fflush(stream) == 0);
    long known_length = ftell(stream);
    REQUIRE(known_length > 0 && (size_t)known_length < sizeof(known_hosts));
    int stream_closed = fclose(stream);
    stream = NULL;
    REQUIRE(stream_closed == 0);
    run.deadline = deadline_after(60000U);
    run.view = load_view(known_hosts, (size_t)(fresh_known || stale_reject
        ? jump_known_length : known_length), run.deadline);
    if (fresh_known) run.fresh_view = jump_fresh_view(known_hosts, (size_t)known_length, run.deadline);
    phase = "jump-setup";
    REQUIRE(jump_endpoint_prepare(&run.jump, run.view, run.deadline, jump_port, 0U,
        argv[4], passwords, JUMP_INTEROP_PASSWORD_BYTES) == 0);
    REQUIRE(jump_endpoint_prepare(&run.target, run.view, run.deadline, target_port, 1U,
        argv[5], passwords + JUMP_INTEROP_PASSWORD_BYTES, JUMP_INTEROP_PASSWORD_BYTES) == 0);
    printf(JUMP_INTEROP_LABEL " role-identities=v2-distinct chain=shared\n");
    run.jump.descriptor = jump_connect(jump_port, run.deadline);
    REQUIRE(run.jump.descriptor >= 0);
    run.jump.fixture.callback.release_fd = run.jump.descriptor;
    struct ctos_ssh_native_client_config parent = jump_config(&run.jump, run.deadline, run.jump.descriptor);
    REQUIRE(ctos_ssh_native_client_open(&parent, &run.jump.fixture.client) == 0);
    phase = "jump-auth";
    REQUIRE(jump_authenticate(&run, &run.jump.fixture) == 0);
    printf(JUMP_INTEROP_LABEL " phase=jump-auth status=pass host=managed-known\n");
    phase = "direct-tcpip";
    REQUIRE(ctos_ssh_native_client_begin_jump(run.jump.fixture.client, run.target.fixture.role) == 0);
    REQUIRE(jump_wait(&run, &run.jump.fixture, WAIT_CHANNEL) == 0);
    printf(JUMP_INTEROP_LABEL " phase=direct-tcpip status=pass target=next-role\n");
    phase = "child-open";
    struct ctos_ssh_native_client_config child = jump_config(&run.target, run.deadline, -1);
    child.fresh_hostfile_view = run.fresh_view;
    REQUIRE(ctos_ssh_native_client_open_jump_child(run.jump.fixture.client, &child,
        &run.target.fixture.client, &run.relay) == 0);
    phase = "target-auth";
    if (stale_reject) {
        REQUIRE(jump_authenticate(&run, &run.target.fixture) != 0
            && run.target.fixture.callback.candidate_calls == 1U
            && run.target.fixture.callback.kex_complete_calls == 0U
            && run.target.fixture.callback.selected_calls == 0U
            && run.target.fixture.callback.credential_calls == 0U
            && run.target.fixture.callback.credential_bytes_calls == 0U);
        printf(JUMP_INTEROP_LABEL " stale-target-host=unknown credential-release=none\n");
        result = 0;
        goto done;
    }
    REQUIRE(jump_authenticate(&run, &run.target.fixture) == 0);
    jump_wipe(passwords, sizeof(passwords));
    printf(JUMP_INTEROP_LABEL " phase=target-auth status=pass host=managed-known\n");
    if (fresh_known) printf(JUMP_INTEROP_LABEL " fresh-target-host=known previous-view=unknown\n");
    phase = "target-shell";
    REQUIRE(ctos_ssh_native_client_begin_shell(run.target.fixture.client, 80U, 24U) == 0);
    REQUIRE(jump_wait(&run, &run.target.fixture, WAIT_SHELL) == 0);
    REQUIRE(ctos_ssh_native_client_retain_jump_transport(run.jump.fixture.client) == 0);
    REQUIRE(jump_marker(&run) == 0);
    printf(JUMP_INTEROP_LABEL " phase=target-pty-command status=pass retained=pass\n");
    result = 0;
done:
    saved_error = errno;
    jump_wipe(passwords, sizeof(passwords));
    if (result != 0 && run.target.fixture.client != NULL
        && run.target.fixture.callback.selected_calls == 0U
        && run.target.fixture.callback.credential_calls == 0U
        && run.target.fixture.callback.credential_bytes_calls == 0U
        && run.target.fixture.callback.kex_complete_calls == 0U) {
        enum ctos_ssh_native_host_key_rejection rejection = CTOS_SSH_HOST_REJECTION_NONE;
        if (ctos_ssh_native_client_host_key_rejection(run.target.fixture.client, &rejection) == 0
            && (rejection == CTOS_SSH_HOST_REJECTION_CHANGED
                || rejection == CTOS_SSH_HOST_REJECTION_CHANGED_BLOCKED))
            printf(JUMP_INTEROP_LABEL " target-host-key=changed credential-release=none\n");
    }
    if (stream != NULL && fclose(stream) != 0) {
        if (result == 0) { saved_error = errno; phase = "cleanup-known-hosts"; }
        result = 1;
    }
    if (jump_cleanup_endpoint(&run.target) != 0) {
        if (result == 0) { saved_error = errno; phase = "cleanup-target"; }
        result = 1;
        cleanup_failed = 1;
        goto cleanup_done;
    }
    if (run.fresh_view != NULL) {
        if (ctos_ssh_view_destroy(run.fresh_view) != 0) {
            if (result == 0) { saved_error = errno; phase = "cleanup-fresh-view"; }
            result = 1;
            cleanup_failed = 1;
            goto cleanup_done;
        }
        free(run.fresh_view);
        run.fresh_view = NULL;
    }
    if (run.relay != NULL) {
        if (ctos_ssh_native_jump_relay_close(run.relay) != 0
            || ctos_ssh_native_jump_relay_destroy(run.relay) != 0) {
            if (result == 0) { saved_error = errno; phase = "cleanup-relay"; }
            result = 1;
            cleanup_failed = 1;
            goto cleanup_done;
        }
        run.relay = NULL;
    }
    if (jump_cleanup_endpoint(&run.jump) != 0) {
        if (result == 0) { saved_error = errno; phase = "cleanup-jump"; }
        result = 1;
        cleanup_failed = 1;
        goto cleanup_done;
    }
    if (run.view != NULL) {
        if (ctos_ssh_view_destroy(run.view) != 0) {
            if (result == 0) { saved_error = errno; phase = "cleanup-view"; }
            result = 1;
            cleanup_failed = 1;
            goto cleanup_done;
        }
        free(run.view);
    }
cleanup_done:
    if (cleanup_failed) fprintf(stderr, JUMP_INTEROP_LABEL " cleanup=failed remaining=process-owned\n");
    if (result != 0) fprintf(stderr, JUMP_INTEROP_LABEL " phase=%s status=fail errno=%d\n", phase, saved_error);
    else printf(JUMP_INTEROP_LABEL " gate=pass scope=arm64-native-real-sshd java-jni-ui=not-tested cleanup=pass\n");
    return result;
#undef REQUIRE
}

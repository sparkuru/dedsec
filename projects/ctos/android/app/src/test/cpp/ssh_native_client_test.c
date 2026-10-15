#define _GNU_SOURCE

#include "ssh_native_client.h"

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
#include "ssh_role_bootstrap.h"
#include "sshkey.h"

static unsigned int checks;

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
    wire_text(wire, "native client managed config");
    wire_u32(wire, 0);
    wire_u32(wire, 1);
    wire_text(wire, "ssh/known_hosts");
    wire_u32(wire, 0);
}

static void wire_known_hosts(struct wire *wire) {
    unsigned char type = CTOS_SSH_KNOWN_HOSTS;

    wire_put(wire, &type, sizeof(type));
    wire_text(wire, "trust");
    wire_text(wire, "ssh/known_hosts");
    wire_text(wire, "native client host evidence");
    wire_u32(wire, 0);
    wire_u32(wire, 0);
    wire_u32(wire, 0);
}

static struct ctos_ssh_view *load_view(uint64_t deadline) {
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
    wire_text(&wire, "ssh/config");
    wire_put(&wire, binding, sizeof(binding));
    wire_u32(&wire, 2);
    wire_root(&wire);
    wire_known_hosts(&wire);

    view = calloc(1, sizeof(*view));
    CHECK(view != NULL);
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
    unsigned int release_calls;
    unsigned int release_failures_remaining;
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
    (void)context;
    (void)environment;
    (void)evidence;
    (void)result;
    return EIO;
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
        .kex_complete = kex_complete,
        .credential = credential,
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

struct fixture {
    struct ctos_ssh_native_client *client;
    struct ctos_ssh_view *view;
    struct ctos_ssh_view_borrow *view_borrow;
    struct callback_fixture callback;
    struct sshkey *private_key;
    struct sshkey *public_key;
    ctos_ssh_role_token role;
    ctos_ssh_callback_token callback_token;
    unsigned char fingerprint[32];
    unsigned char *public_blob;
    size_t public_blob_length;
    uint64_t deadline;
    int pair[2];
};

static void setup_fixture(struct fixture *fixture) {
    struct ctos_ssh_role_request request;
    struct ctos_ssh_native_client_config config;

    memset(fixture, 0, sizeof(*fixture));
    fixture->pair[0] = fixture->pair[1] = -1;
    fixture->callback.release_fd = -1;
    fixture->deadline = deadline_after(15000);
    CHECK(sshkey_generate(KEY_ED25519, 0, &fixture->private_key) == 0);
    CHECK(sshkey_from_private(fixture->private_key, &fixture->public_key) == 0);
    CHECK(expected_fingerprint(fixture->public_key, fixture->fingerprint) == 0);
    fixture->view = load_view(fixture->deadline);
    CHECK(sshkey_to_blob(fixture->public_key, &fixture->public_blob,
        &fixture->public_blob_length) == 0);
    CHECK(ctos_ssh_view_borrow_owned_view(fixture->view,
        &fixture->view_borrow) == 0);
    request = role_request(fixture->deadline);
    CHECK(ctos_ssh_role_bootstrap_open(&request, &fixture->role) == 0);
    fixture->callback_token = open_callback(&fixture->callback, fixture->role,
        fixture->deadline);
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
        .step_budget = 3,
        .server_host_public_key_blob = fixture->public_blob,
        .server_host_public_key_length = fixture->public_blob_length,
        .expected_host_fingerprint = fixture->fingerprint,
        .managed_view_borrow = fixture->view_borrow,
        .known_hosts_path = "ssh/known_hosts",
        .host = "example.invalid",
        .candidate_id = "native-candidate"
    };
    CHECK(ctos_ssh_native_client_open(&config, &fixture->client) == 0);
}

static void cleanup_fixture(struct fixture *fixture,
    unsigned int expected_release_calls) {
    CHECK(ctos_ssh_native_client_close(fixture->client) == 0);
    fixture->view_borrow = NULL;
    CHECK(fixture->callback.release_calls == expected_release_calls);
    CHECK(fixture->callback.release_saw_fd_open == 1);
    errno = 0;
    CHECK(ctos_ssh_native_client_step(fixture->client,
        &(struct ctos_ssh_native_client_step){0}) != 0 && errno == ENOENT);
    errno = 0;
    CHECK(ctos_ssh_native_client_close(fixture->client) != 0 && errno == ENOENT);
    errno = 0;
    CHECK(fcntl(fixture->pair[0], F_GETFD) < 0 && errno == EBADF);
    CHECK(ctos_ssh_native_client_destroy(fixture->client) == 0);
    CHECK(close(fixture->pair[1]) == 0);
    CHECK(ctos_ssh_role_bootstrap_close(fixture->role) == 0);
    destroy_view(fixture->view);
    sshkey_free(fixture->public_key);
    sshkey_free(fixture->private_key);
    if (fixture->public_blob != NULL) {
        volatile unsigned char *bytes = fixture->public_blob;
        for (size_t index = 0; index < fixture->public_blob_length; index++) bytes[index] = 0;
        free(fixture->public_blob);
    }
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

    config.owner_uid++;
    errno = 0;
    CHECK(ctos_ssh_native_client_open(&config, &client) != 0
        && errno == EACCES && client == NULL);
    config.owner_uid = getuid();
    config.role = role + 1U;
    errno = 0;
    CHECK(ctos_ssh_native_client_open(&config, &client) != 0
        && errno == EACCES && client == NULL);
    config.role = role;
    config.deadline = expiry - 1U;
    errno = 0;
    CHECK(ctos_ssh_native_client_open(&config, &client) != 0
        && errno == EACCES && client == NULL);

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
    view = load_view(fixture.deadline);
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

static void test_step_and_close(void) {
    struct fixture fixture;
    unsigned char byte = 0;
    struct ctos_ssh_native_client_step step;

    setup_fixture(&fixture);
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

static void test_close_retry_order(void) {
    struct fixture fixture;

    setup_fixture(&fixture);
    fixture.callback.release_failures_remaining = 1;
    errno = 0;
    CHECK(ctos_ssh_native_client_close(fixture.client) != 0 && errno == EIO);
    CHECK(fixture.callback.release_calls == 1);
    CHECK(fixture.callback.release_saw_fd_open == 1);
    CHECK(fcntl(fixture.pair[0], F_GETFD) >= 0);
    cleanup_fixture(&fixture, 2);
}

int main(void) {
    test_admission_and_descriptor();
    test_malformed_view_slice();
    test_step_and_close();
    test_close_retry_order();
    printf("SSH_NATIVE_CLIENT checks=%u admission=pass descriptor=pass view=pass close=pass open_failure_ownership=pass runtime=synthetic\n",
        checks);
    return 0;
}

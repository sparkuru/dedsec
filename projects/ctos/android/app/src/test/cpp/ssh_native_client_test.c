#include "ssh_native_client.h"

#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

static unsigned int checks;

#define CHECK(condition) do { \
    checks++; \
    if (!(condition)) { \
        fprintf(stderr, "SSH_NATIVE_CLIENT phase=gate state=error errno=5 value=%u\n", checks); \
        return 2; \
    } \
} while (0)

static uint64_t deadline(unsigned int delta) {
    struct timespec current;
    CHECK(clock_gettime(CLOCK_BOOTTIME, &current) == 0);
    return (uint64_t)current.tv_sec * 1000U
        + (uint64_t)current.tv_nsec / 1000000U + delta;
}

static struct ctos_ssh_role_request role_request(
    const unsigned char nonce[32], const unsigned char binding[32],
    uint64_t expiry) {
    return (struct ctos_ssh_role_request){
        .program = CTOS_SSH_ROLE_CLIENT,
        .owner_pid = getpid(),
        .owner_uid = getuid(),
        .hop = 0,
        .port = 22,
        .deadline = expiry,
        .connection_id = "native_client_connection",
        .target_identity = "target:user@example.invalid:22",
        .host = "example.invalid",
        .public_view_id = "native_client_view",
        .role_nonce = nonce,
        .connection_binding = binding
    };
}

static struct ctos_ssh_native_client_config config(
    ctos_ssh_role_token role, int fd, uint64_t expiry) {
    return (struct ctos_ssh_native_client_config){
        .role = role,
        .socket_fd = fd,
        .owner_uid = getuid(),
        .owns_socket = 1,
        .callback = 1,
        .callback_environment = (void *)&checks,
        .deadline = expiry,
        .step_budget = CTOS_SSH_NATIVE_CLIENT_MAX_STEP_BYTES
    };
}

int main(void) {
    unsigned char nonce[32] = {1};
    unsigned char binding[32] = {2};
    uint64_t expiry = deadline(5000);
    struct ctos_ssh_role_request request = role_request(nonce, binding, expiry);
    ctos_ssh_role_token role = 0;
    int pair[2] = {-1, -1};
    struct ctos_ssh_native_client *client = (void *)0x1;
    struct ctos_ssh_native_client_config valid;
    struct ctos_ssh_native_client_step step = {0};

    CHECK(ctos_ssh_role_bootstrap_open(&request, &role) == 0 && role != 0);
    CHECK(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, pair) == 0);
    valid = config(role, pair[0], expiry);

    errno = 0;
    CHECK(ctos_ssh_native_client_open(NULL, &client) != 0 && errno == EINVAL && client == NULL);
    errno = 0;
    CHECK(ctos_ssh_native_client_open(&valid, NULL) != 0 && errno == EINVAL);

    struct ctos_ssh_native_client_config malformed = valid;
    malformed.owns_socket = 0;
    errno = 0;
    CHECK(ctos_ssh_native_client_open(&malformed, &client) != 0 && errno == EINVAL && client == NULL);

    malformed = valid;
    malformed.role = role + 1;
    errno = 0;
    int wrong_role_result = ctos_ssh_native_client_open(&malformed, &client);
    CHECK(wrong_role_result != 0 && errno == EACCES && client == NULL);

    malformed = valid;
    malformed.deadline = 0;
    errno = 0;
    CHECK(ctos_ssh_native_client_open(&malformed, &client) != 0 && errno == EACCES && client == NULL);

    malformed = valid;
    malformed.owner_uid++;
    errno = 0;
    CHECK(ctos_ssh_native_client_open(&malformed, &client) != 0 && errno == EACCES && client == NULL);

    int nonblocking_fd = pair[1];
    int socket_flags = fcntl(nonblocking_fd, F_GETFL);
    CHECK(socket_flags >= 0 && fcntl(nonblocking_fd, F_SETFL, socket_flags & ~O_NONBLOCK) == 0);
    malformed = valid;
    malformed.socket_fd = nonblocking_fd;
    errno = 0;
    CHECK(ctos_ssh_native_client_open(&malformed, &client) != 0 && errno == EINVAL && client == NULL);
    CHECK(fcntl(nonblocking_fd, F_SETFL, socket_flags) == 0);

    int non_cloexec_fd = dup(pair[0]);
    CHECK(non_cloexec_fd >= 0 && fcntl(non_cloexec_fd, F_GETFD) >= 0);
    int descriptor_flags = fcntl(non_cloexec_fd, F_GETFD);
    CHECK(descriptor_flags >= 0 && fcntl(non_cloexec_fd, F_SETFD, descriptor_flags & ~FD_CLOEXEC) == 0);
    malformed = valid;
    malformed.socket_fd = non_cloexec_fd;
    errno = 0;
    CHECK(ctos_ssh_native_client_open(&malformed, &client) != 0 && errno == EINVAL && client == NULL);
    CHECK(close(non_cloexec_fd) == 0);

    /* Missing provider is stable and does not consume/close the managed FD. */
    errno = 0;
    CHECK(ctos_ssh_native_client_open(&valid, &client) != 0
        && errno == CTOS_SSH_NATIVE_CLIENT_HOST_PROVIDER_MISSING && client == NULL
        && fcntl(pair[0], F_GETFD) >= 0);

    errno = 0;
    CHECK(ctos_ssh_native_client_step(NULL, &step) != 0 && errno == EINVAL);
    errno = 0;
    CHECK(ctos_ssh_native_client_close(NULL) != 0 && errno == ENOENT);
    errno = 0;
    CHECK(ctos_ssh_native_client_close((void *)&checks) != 0 && errno == EINVAL);

    errno = 0;
    CHECK(ctos_ssh_native_client_verify_host_key(NULL, (void *)&checks) != 0
        && errno == EINVAL);
    errno = 0;
    CHECK(ctos_ssh_native_client_verify_host_key((void *)&checks, (void *)&checks) != 0
        && errno == CTOS_SSH_NATIVE_CLIENT_HOST_PROVIDER_MISSING);

    unsigned char input[CTOS_SSH_NATIVE_CLIENT_MAX_STEP_BYTES + 1] = {0};
    step.input = input;
    step.input_length = sizeof(input);
    errno = 0;
    CHECK(ctos_ssh_native_client_step((void *)&checks, &step) != 0 && errno == EINVAL);

    /* A bounded partial buffer is not consumed before the missing-provider gate. */
    step.input_length = 3;
    step.output = input;
    step.output_capacity = 5;
    step.input_consumed = 99;
    step.output_consumed = 99;
    errno = 0;
    CHECK(ctos_ssh_native_client_step((void *)&checks, &step) != 0
        && errno == CTOS_SSH_NATIVE_CLIENT_HOST_PROVIDER_MISSING
        && step.input_consumed == 0 && step.output_consumed == 0);

    step.cancelled = 1;
    step.input_consumed = 99;
    step.output_consumed = 99;
    errno = 0;
    CHECK(ctos_ssh_native_client_step((void *)&checks, &step) != 0 && errno == EINVAL
        && step.input_consumed == 0 && step.output_consumed == 0);

    CHECK(close(pair[0]) == 0 && close(pair[1]) == 0);
    CHECK(ctos_ssh_role_bootstrap_close(role) == 0);
    printf("SSH_NATIVE_CLIENT phase=admission state=pass errno=0 value=%u\n", checks);
    printf("SSH_NATIVE_CLIENT phase=provider state=pass errno=0 value=1\n");
    printf("SSH_NATIVE_CLIENT phase=cleanup state=pass errno=0 value=1\n");
    printf("SSH_NATIVE_CLIENT phase=gate state=pass errno=0 value=%u\n", checks);
    return 0;
}

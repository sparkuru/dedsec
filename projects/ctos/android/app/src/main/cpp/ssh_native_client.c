#include "ssh_native_client.h"

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

static int now_millis(uint64_t *result) {
    struct timespec current;

    if (result == NULL || clock_gettime(CLOCK_BOOTTIME, &current) != 0
        || current.tv_sec < 0 || current.tv_nsec < 0
        || current.tv_nsec >= 1000000000
        || (uint64_t)current.tv_sec > ((uint64_t)INT64_MAX - 999U) / 1000U) {
        errno = EIO;
        return -1;
    }
    *result = (uint64_t)current.tv_sec * 1000U
        + (uint64_t)current.tv_nsec / 1000000U;
    return 0;
}

static int managed_stream_fd(int fd) {
    int descriptor;
    int flags;

    /* The managed network layer owns endpoint/type validation; this seam
     * only admits its already-created CLOEXEC/NONBLOCK descriptor. */
    if (fd < 0 || (descriptor = fcntl(fd, F_GETFD)) < 0
        || (descriptor & FD_CLOEXEC) == 0
        || (flags = fcntl(fd, F_GETFL)) < 0 || (flags & O_NONBLOCK) == 0) {
        errno = EINVAL;
        return -1;
    }
    return 0;
}

static int valid_role(const struct ctos_ssh_native_client_config *config,
    struct ctos_ssh_role_snapshot *snapshot) {
    uint64_t current;

    memset(snapshot, 0, sizeof(*snapshot));
    if (ctos_ssh_role_bootstrap_snapshot(config->role, snapshot) != 0) {
        errno = EACCES;
        return -1;
    }
    if (now_millis(&current) != 0 || snapshot->owner_pid != getpid()
        || snapshot->owner_uid != getuid()
        || config->owner_uid != getuid()
        || snapshot->token != config->role
        || snapshot->deadline != config->deadline
        || config->deadline <= current || config->deadline > INT64_MAX) {
        errno = EACCES;
        return -1;
    }
    return 0;
}

int ctos_ssh_native_client_open(
    const struct ctos_ssh_native_client_config *config,
    struct ctos_ssh_native_client **client) {
    struct ctos_ssh_role_snapshot snapshot;
    int result = -1;

    if (client == NULL) {
        errno = EINVAL;
        return -1;
    }
    *client = NULL;
    memset(&snapshot, 0, sizeof(snapshot));
    if (config == NULL || config->role == 0 || config->socket_fd < 0
        || config->owns_socket != 1
        || config->callback == 0 || config->callback_environment == NULL
        || config->step_budget == 0
        || config->step_budget > CTOS_SSH_NATIVE_CLIENT_MAX_STEP_BYTES) {
        errno = EINVAL;
        goto out;
    }
    if (managed_stream_fd(config->socket_fd) != 0
        || valid_role(config, &snapshot) != 0) goto out;

    /*
     * This is the intentional blocker. There is no native-facing exact
     * known_hosts issuer/CAS contract from which a role-bound host digest and
     * KNOWN/NEW evidence can be obtained. Do not call ssh_init(), attach the
     * descriptor, register a verifier, or enter packet dispatch without it.
     */
    errno = CTOS_SSH_NATIVE_CLIENT_HOST_PROVIDER_MISSING;
out:
    memset(&snapshot, 0, sizeof(snapshot));
    return result;
}

int ctos_ssh_native_client_step(
    struct ctos_ssh_native_client *client,
    struct ctos_ssh_native_client_step *step) {
    if (step != NULL) {
        step->input_consumed = 0;
        step->output_consumed = 0;
    }
    if (client == NULL || step == NULL || step->cancelled
        || step->input_length > CTOS_SSH_NATIVE_CLIENT_MAX_STEP_BYTES
        || step->output_capacity > CTOS_SSH_NATIVE_CLIENT_MAX_STEP_BYTES
        || (step->input_length != 0 && step->input == NULL)
        || (step->output_capacity != 0 && step->output == NULL)) {
        errno = EINVAL;
        return -1;
    }
    errno = CTOS_SSH_NATIVE_CLIENT_HOST_PROVIDER_MISSING;
    return -1;
}

int ctos_ssh_native_client_verify_host_key(
    struct ctos_ssh_native_client *client, const void *opaque_host_key) {
    if (client == NULL || opaque_host_key == NULL) {
        errno = EINVAL;
        return -1;
    }
    /* No fingerprint, classification or proposal is trusted by this seam. */
    errno = CTOS_SSH_NATIVE_CLIENT_HOST_PROVIDER_MISSING;
    return -1;
}

int ctos_ssh_native_client_close(struct ctos_ssh_native_client *client) {
    if (client == NULL) {
        errno = ENOENT;
        return -1;
    }
    /* No client can be created before the provider gate is present. */
    errno = EINVAL;
    return -1;
}

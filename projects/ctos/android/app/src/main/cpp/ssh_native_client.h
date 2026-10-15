#ifndef CTOS_SSH_NATIVE_CLIENT_H
#define CTOS_SSH_NATIVE_CLIENT_H

#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>

#include "ssh_callback_adapter.h"
#include "ssh_role_bootstrap.h"

/*
 * This is deliberately a fail-closed production boundary, not an OpenSSH
 * caller.  The pinned ssh_api objects live only in the opt-in research build;
 * the production known_hosts issuer/CAS API is not available yet.  Until that
 * provider exists, opening a client must not create a KEX object or classify
 * a host from a fingerprint.
 */
#define CTOS_SSH_NATIVE_CLIENT_MAX_STEP_BYTES (64U * 1024U)
#define CTOS_SSH_NATIVE_CLIENT_HOST_PROVIDER_MISSING ENOTSUP

struct ctos_ssh_native_client;

struct ctos_ssh_native_client_config {
    ctos_ssh_role_token role;
    int socket_fd;
    uid_t owner_uid;
    int owns_socket;
    ctos_ssh_callback_token callback;
    void *callback_environment;
    uint64_t deadline;
    size_t step_budget;
};

struct ctos_ssh_native_client_step {
    const unsigned char *input;
    size_t input_length;
    unsigned char *output;
    size_t output_capacity;
    size_t input_consumed;
    size_t output_consumed;
    int cancelled;
};

/*
 * Validates ownership and lifecycle metadata, then returns ENOTSUP until the
 * managed public config/known_hosts exact issuer and CAS writer are available.
 * On every failure `*client` stays NULL and socket ownership stays with the
 * caller; this function never closes a caller-owned descriptor.
 */
int ctos_ssh_native_client_open(
    const struct ctos_ssh_native_client_config *,
    struct ctos_ssh_native_client **);

/* No step is allowed before the provider gate. Payload bytes are never read. */
int ctos_ssh_native_client_step(
    struct ctos_ssh_native_client *,
    struct ctos_ssh_native_client_step *);

/* The verifier bridge has no provider to query and therefore never approves a key. */
int ctos_ssh_native_client_verify_host_key(
    struct ctos_ssh_native_client *, const void *opaque_host_key);

/* A future client will close only its explicitly transferred socket. */
int ctos_ssh_native_client_close(struct ctos_ssh_native_client *);

#endif

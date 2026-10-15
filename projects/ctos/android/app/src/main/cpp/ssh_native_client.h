#ifndef CTOS_SSH_NATIVE_CLIENT_H
#define CTOS_SSH_NATIVE_CLIENT_H

#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>

#include "ssh_callback_adapter.h"
#include "ssh_file_view.h"
#include "ssh_role_bootstrap.h"

/* The normal APK caller owns one same-process OpenSSH event-loop object. */
#define CTOS_SSH_NATIVE_CLIENT_MAX_STEP_BYTES (64U * 1024U)
#define CTOS_SSH_NATIVE_CLIENT_FINGERPRINT_BYTES 32U

struct ctos_ssh_native_client;
struct sshkey;

struct ctos_ssh_native_client_config {
    ctos_ssh_role_token role;
    int socket_fd;
    uid_t owner_uid;
    int owns_socket;
    /* The caller pre-opens this binding on the event-loop thread. It remains
     * caller-owned if open returns -1; the native client attaches a CLOEXEC
     * duplicate only after all admission checks, so setup failure cannot
     * consume the caller descriptor. After a successful open, the client
     * owns both descriptors and close ordering. */
    ctos_ssh_callback_token callback;
    void *callback_environment;
    uint64_t deadline;
    size_t step_budget;
    /* Borrowed managed-provider inputs. They must remain live until close. */
    const struct sshkey *server_host_public_key;
    const unsigned char *expected_host_fingerprint;
    const struct ctos_ssh_view *managed_view;
    const char *known_hosts_path;
    const char *host;
    const char *candidate_id;
};

struct ctos_ssh_native_client_step {
    /* Production owns the attached socket; application-side byte pipes are
     * retained only for ABI clarity and are rejected when non-empty. */
    const unsigned char *input;
    size_t input_length;
    unsigned char *output;
    size_t output_capacity;
    size_t input_consumed;
    size_t output_consumed;
    int cancelled;
    size_t bytes_read;
    size_t bytes_written;
    size_t input_appended;
    int read_would_block;
    int write_would_block;
    int partial_write;
    int packet_type;
};

/* A successful open owns the original descriptor and its attached duplicate.
 * On failure, the original descriptor and pre-opened callback remain
 * caller-owned. */
int ctos_ssh_native_client_open(
    const struct ctos_ssh_native_client_config *,
    struct ctos_ssh_native_client **);

/* One bounded nonblocking socket read/parse/write turn. */
int ctos_ssh_native_client_step(
    struct ctos_ssh_native_client *,
    struct ctos_ssh_native_client_step *);

/* Verifies the exact managed host-key path and synchronously invokes evidence. */
int ctos_ssh_native_client_verify_host_key(
    struct ctos_ssh_native_client *, const void *opaque_host_key);

int ctos_ssh_native_client_complete(
    const struct ctos_ssh_native_client *);

/*
 * Close is owned by the event-loop thread that opened the callback binding and
 * must receive the same attached callback environment. It first retries any
 * candidate rollback, then synchronously closes the callback binding, then
 * unbinds/frees OpenSSH and the owned descriptor. A release failure leaves
 * the whole client open so the caller can retry close with the same
 * thread/environment; external callers must not close this callback token
 * independently while the client exists.
 */
int ctos_ssh_native_client_close(struct ctos_ssh_native_client *);

/* Free only a closed tombstone. */
int ctos_ssh_native_client_destroy(struct ctos_ssh_native_client *);

#endif

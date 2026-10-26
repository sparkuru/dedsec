#ifndef CTOS_SSH_NATIVE_CLIENT_H
#define CTOS_SSH_NATIVE_CLIENT_H

#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>

#include "ssh_callback_adapter.h"
#include "ssh_view_handle.h"
#include "ssh_role_bootstrap.h"

/* The normal APK caller owns one same-process OpenSSH event-loop object. */
#define CTOS_SSH_NATIVE_CLIENT_MAX_STEP_BYTES (64U * 1024U)
#define CTOS_SSH_NATIVE_CLIENT_FINGERPRINT_BYTES 32U
#define CTOS_SSH_NATIVE_CLIENT_MAX_HOST_KEY_BLOB_BYTES (1024U * 1024U)
#define CTOS_SSH_NATIVE_CLIENT_MAX_SELECTION_WIRE_BYTES (1024U * 1024U + 2048U)
#define CTOS_SSH_NATIVE_CLIENT_MAX_PASSWORD_BYTES 8192U

enum ctos_ssh_native_client_password_auth_result {
    CTOS_SSH_PASSWORD_AUTH_NOT_STARTED = 0,
    CTOS_SSH_PASSWORD_AUTH_PENDING = 1,
    CTOS_SSH_PASSWORD_AUTH_SUCCESS = 2,
    CTOS_SSH_PASSWORD_AUTH_REJECTED = 3,
    CTOS_SSH_PASSWORD_AUTH_PARTIAL = 4,
    CTOS_SSH_PASSWORD_AUTH_CHANGE_REQUIRED = 5
};

enum ctos_ssh_native_client_private_key_auth_result {
    CTOS_SSH_PRIVATE_KEY_AUTH_NOT_STARTED = 0,
    CTOS_SSH_PRIVATE_KEY_AUTH_PENDING = 1,
    CTOS_SSH_PRIVATE_KEY_AUTH_SUCCESS = 2,
    CTOS_SSH_PRIVATE_KEY_AUTH_REJECTED = 3,
    CTOS_SSH_PRIVATE_KEY_AUTH_PARTIAL = 4
};

enum ctos_ssh_native_shell_phase {
    CTOS_SSH_SHELL_DISABLED = 0,
    CTOS_SSH_SHELL_OPENING = 1,
    CTOS_SSH_SHELL_PTY_PENDING = 2,
    CTOS_SSH_SHELL_SHELL_PENDING = 3,
    CTOS_SSH_SHELL_RUNNING = 4,
    CTOS_SSH_SHELL_CLOSED = 5,
    CTOS_SSH_SHELL_REJECTED = 6
};

struct ctos_ssh_native_shell_status {
    enum ctos_ssh_native_shell_phase phase;
    int remote_eof;
    int local_eof;
    int exit_status_known;
    uint32_t exit_status;
    int exit_signal;
    size_t buffered_bytes;
};

struct ctos_ssh_native_client;
struct sshkey;

enum ctos_ssh_native_host_key_mode {
    CTOS_SSH_HOST_KEY_PINNED = 0,
    CTOS_SSH_HOST_KEY_MANAGED = 1
};

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
    /* PINNED parses and owns this blob before OpenSSH is attached. The caller
     * may wipe it as soon as open returns; MANAGED requires NULL/0/NULL. */
    const unsigned char *server_host_public_key_blob;
    size_t server_host_public_key_length;
    const unsigned char *expected_host_fingerprint;
    /* Caller-owned on open failure; native client-owned after success. */
    struct ctos_ssh_view_borrow *managed_view_borrow;
    const char *known_hosts_path;
    const char *host;
    const char *candidate_id;
    /* Optional CSAS v1 metadata, borrowed only for open; never credential bytes. */
    const unsigned char *authentication_selection_wire;
    size_t authentication_selection_wire_length;
    /* Explicit opt-in: MANAGED requires absent blob and fingerprint. */
    enum ctos_ssh_native_host_key_mode host_key_mode;
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

/* Prepare a caller-owned stream/RDWR/nonblocking descriptor for handoff.
 * Sets FD_CLOEXEC only; never closes, duplicates or adopts the descriptor. */
int ctos_ssh_native_client_prepare_tcp_descriptor(int);

/* A successful open owns the original descriptor, its attached duplicate,
 * parsed host key and managed-view borrow. On failure, the original
 * descriptor, pre-opened callback and view borrow remain caller-owned. */
int ctos_ssh_native_client_open(
    const struct ctos_ssh_native_client_config *,
    struct ctos_ssh_native_client **);

/* One bounded nonblocking socket read/parse/write turn. */
int ctos_ssh_native_client_step(
    struct ctos_ssh_native_client *,
    struct ctos_ssh_native_client_step *);

/* PINNED-only diagnostic verification through the exact managed trust path.
 * MANAGED accepts actual transport callbacks only, never this manual entry. */
int ctos_ssh_native_client_verify_host_key(
    struct ctos_ssh_native_client *, const void *, size_t);

int ctos_ssh_native_client_complete(
    const struct ctos_ssh_native_client *);

/* Live owner-only metadata fingerprint, permitted before/after KEX. No I/O or
 * credential authority; every error clears a non-null 32-byte output. */
int ctos_ssh_native_client_authentication_selection_receipt(
    const struct ctos_ssh_native_client *, unsigned char receipt[32]);

/* One selected typed handoff after service-ready/current proof. No USERAUTH is
 * sent; output remains private until all post-provider checks pass. */
int ctos_ssh_native_client_selected_credential_bytes(struct ctos_ssh_native_client *,
    unsigned char *output, size_t capacity, size_t *length);

/* One explicit password request using owned selection and the shared selected
 * attempt. No retries or password-change continuation; all remote text is discarded. */
int ctos_ssh_native_client_begin_password_auth(struct ctos_ssh_native_client *);
/* Owner/live-proof-only read. Errors clear out and do not mutate the client. */
int ctos_ssh_native_client_password_auth_result(
    const struct ctos_ssh_native_client *,
    enum ctos_ssh_native_client_password_auth_result *);

/* One direct signed publickey request; expected identity comes only from owned
 * CSAS and private material only from the selected one-shot credential callback. */
int ctos_ssh_native_client_begin_private_key_auth(struct ctos_ssh_native_client *);
int ctos_ssh_native_client_private_key_auth_result(
    const struct ctos_ssh_native_client *,
    enum ctos_ssh_native_client_private_key_auth_result *);

/* Explicitly continue a proved initial KEX into fixed ssh-userauth service
 * negotiation. No username or credential is sent; ready is not authentication.
 * KEX-only callers keep their existing complete()/EALREADY stopping point. */
int ctos_ssh_native_client_begin_userauth_service(struct ctos_ssh_native_client *);
int ctos_ssh_native_client_userauth_service_ready(
    const struct ctos_ssh_native_client *);

int ctos_ssh_native_client_begin_shell(struct ctos_ssh_native_client *, unsigned int, unsigned int);
int ctos_ssh_native_client_shell_status(const struct ctos_ssh_native_client *,
    struct ctos_ssh_native_shell_status *);
int ctos_ssh_native_client_shell_write(struct ctos_ssh_native_client *,
    const unsigned char *, size_t, size_t *);
int ctos_ssh_native_client_shell_read(struct ctos_ssh_native_client *,
    unsigned char *, size_t, size_t *);
int ctos_ssh_native_client_shell_resize(struct ctos_ssh_native_client *, unsigned int, unsigned int);
int ctos_ssh_native_client_shell_eof(struct ctos_ssh_native_client *);

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

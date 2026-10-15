#ifndef CTOS_SSH_PINNED_CLIENT_H
#define CTOS_SSH_PINNED_CLIENT_H

#include <stddef.h>
#include <stdint.h>

#include "ssh_callback_adapter.h"
#include "ssh_file_view.h"
#include "ssh_role_bootstrap.h"

#define CTOS_SSH_PINNED_CLIENT_FINGERPRINT_BYTES 32U
#define CTOS_SSH_PINNED_CLIENT_MAX_STEP_BYTES (64U * 1024U)

struct ctos_ssh_pinned_client;
struct sshkey;

/*
 * This is an opt-in pinned OpenSSH 10.6p1 fixture seam.  The caller transfers
 * ownership of `socket_fd` when `owns_socket` is one; the client closes it
 * through ssh_free() during ctos_ssh_pinned_client_close().  The descriptor
 * must already be a CLOEXEC, NONBLOCK SOCK_STREAM; the caller retains it when
 * open rejects the configuration.  The callback
 * binding, role token, and managed view remain caller-owned and must outlive
 * the client.  No stock Options, pathname, argv, credential, or auth state is
 * accepted here.
 */
struct ctos_ssh_pinned_client_config {
    ctos_ssh_role_token role;
    int socket_fd;
    int owns_socket;
    uint64_t deadline;
    /* Borrowed public key used only to advertise the pinned host-key type. */
    const struct sshkey *server_host_public_key;
    const unsigned char *expected_host_fingerprint;
    const struct ctos_ssh_view *managed_view;
    const char *known_hosts_path;
    const char *host;
    const char *candidate_id;
    ctos_ssh_callback_token callback;
    void *callback_environment;
    size_t read_chunk;
    size_t write_chunk;
};

/* One bounded nonblocking read/parse/write turn. */
struct ctos_ssh_pinned_client_step {
    int cancelled;
    size_t bytes_read;
    size_t bytes_written;
    size_t input_appended;
    size_t output_consumed;
    int read_would_block;
    int write_would_block;
    int partial_write;
    int packet_type;
};

/* Creates the pinned client and transfers the socket only after attachment. */
int ctos_ssh_pinned_client_open(
    const struct ctos_ssh_pinned_client_config *,
    struct ctos_ssh_pinned_client **);

/* Returns zero for an ordinary turn; errors are terminal until close(). */
int ctos_ssh_pinned_client_step(
    struct ctos_ssh_pinned_client *, struct ctos_ssh_pinned_client_step *);

/* Completion is true only after exactly one successful KEX callback. */
int ctos_ssh_pinned_client_complete(const struct ctos_ssh_pinned_client *);

/* Closes the owned OpenSSH/FD state and leaves a tombstone for refusal checks. */
int ctos_ssh_pinned_client_close(struct ctos_ssh_pinned_client *);

/* Releases only a previously closed tombstone. */
int ctos_ssh_pinned_client_destroy(struct ctos_ssh_pinned_client *);

#endif

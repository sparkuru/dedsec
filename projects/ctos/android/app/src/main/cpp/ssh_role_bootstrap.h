#ifndef CTOS_SSH_ROLE_BOOTSTRAP_H
#define CTOS_SSH_ROLE_BOOTSTRAP_H

#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>

#define CTOS_SSH_ROLE_MAX 8U
#define CTOS_SSH_ROLE_MAX_LIFETIME_MS 60000U
#define CTOS_SSH_ROLE_CONNECTION_ID_BYTES 128U
#define CTOS_SSH_ROLE_TARGET_ID_BYTES 512U
#define CTOS_SSH_ROLE_HOST_BYTES 253U
#define CTOS_SSH_ROLE_VIEW_ID_BYTES 128U
#define CTOS_SSH_ROLE_WIRE_BYTES 2048U
#define CTOS_SSH_ROLE_MAGIC 0x43535242U /* CSRB */

enum ctos_ssh_role_program {
    CTOS_SSH_ROLE_CLIENT = 1,
    CTOS_SSH_ROLE_JUMP = 2
};

/* These are identifiers for an already-owned native event loop. They are not
 * an argv, command, pathname, secret, or public-file body transport. */
struct ctos_ssh_role_request {
    unsigned int program;
    pid_t owner_pid;
    uid_t owner_uid;
    unsigned int hop;
    unsigned int port;
    uint64_t deadline;
    const char *connection_id;
    const char *target_identity;
    const char *host;
    const char *public_view_id;
    const unsigned char *role_nonce;
    const unsigned char *connection_binding;
};

typedef uint64_t ctos_ssh_role_token;

/* A snapshot contains only bounded role metadata and opaque bindings. */
struct ctos_ssh_role_snapshot {
    ctos_ssh_role_token token;
    unsigned int program;
    pid_t owner_pid;
    uid_t owner_uid;
    unsigned int hop;
    unsigned int port;
    uint64_t deadline;
    char connection_id[CTOS_SSH_ROLE_CONNECTION_ID_BYTES + 1];
    char target_identity[CTOS_SSH_ROLE_TARGET_ID_BYTES + 1];
    char host[CTOS_SSH_ROLE_HOST_BYTES + 1];
    char public_view_id[CTOS_SSH_ROLE_VIEW_ID_BYTES + 1];
    unsigned char role_nonce[32];
    unsigned char connection_binding[32];
};

/* The caller must be the owned native event-loop process itself. */
int ctos_ssh_role_bootstrap_open(const struct ctos_ssh_role_request *,
    ctos_ssh_role_token *);
int ctos_ssh_role_bootstrap_snapshot(ctos_ssh_role_token,
    struct ctos_ssh_role_snapshot *);
int ctos_ssh_role_bootstrap_active(ctos_ssh_role_token);
int ctos_ssh_role_bootstrap_close(ctos_ssh_role_token);

/* Internal transport continuation retains only revocable metadata. Ordinary
 * snapshot/active/encode remain deadline-bound and grant no renewed authority. */
int ctos_ssh_role_transport_retain(ctos_ssh_role_token);
int ctos_ssh_role_transport_snapshot(ctos_ssh_role_token,
    struct ctos_ssh_role_snapshot *);
int ctos_ssh_role_transport_release(ctos_ssh_role_token);

/* Encode only the snapshot metadata for a future typed Java/native handoff. */
int ctos_ssh_role_bootstrap_encode(ctos_ssh_role_token, unsigned char *,
    size_t, size_t *);

#endif

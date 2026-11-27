#ifndef CTOS_SSH_ROLE_BOOTSTRAP_H
#define CTOS_SSH_ROLE_BOOTSTRAP_H

#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>

/* Eight ProxyJump hosts plus their final target need nine live roles. */
#define CTOS_SSH_ROLE_MAX 9U
#define CTOS_SSH_ROLE_MAX_LIFETIME_MS 60000U
#define CTOS_SSH_ROLE_CONNECTION_ID_BYTES 128U
#define CTOS_SSH_ROLE_TARGET_ID_BYTES 512U
#define CTOS_SSH_ROLE_HOST_BYTES 253U
#define CTOS_SSH_ROLE_VIEW_ID_BYTES 128U
#define CTOS_SSH_ROLE_WIRE_BYTES 2048U
#define CTOS_SSH_ROLE_MAGIC 0x43535242U /* CSRB */
#define CTOS_SSH_ROLE_IDENTITY_V1 1U
#define CTOS_SSH_ROLE_IDENTITY_V2 2U

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
    /* NULL preserves v1: the chain identity is the target identity. A
     * non-NULL value explicitly selects v2, with per-hop target identity. */
    const char *chain_identity;
};

typedef uint64_t ctos_ssh_role_token;

/* Internal shared ordering for a bounded authentication effect.  A snapshot
 * grants no authority on its own: callers must first validate the exact live
 * chain, then acquire only around an operation which cannot wait for an
 * invalidator's locks.  An invalidator blocks new acquisitions and drains
 * already acquired operations before it changes the protected state. */
int ctos_ssh_effect_snapshot(uint32_t *generation);
int ctos_ssh_effect_acquire(uint32_t generation);
void ctos_ssh_effect_release(void);
void ctos_ssh_effect_invalidate_begin(void);
void ctos_ssh_effect_invalidate_end(void);
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
unsigned int ctos_ssh_effect_test_active(void);
#endif

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
    unsigned int identity_version;
    char chain_identity[CTOS_SSH_ROLE_TARGET_ID_BYTES + 1];
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

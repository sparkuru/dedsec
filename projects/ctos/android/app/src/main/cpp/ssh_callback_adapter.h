#ifndef CTOS_SSH_CALLBACK_ADAPTER_H
#define CTOS_SSH_CALLBACK_ADAPTER_H

#include <stddef.h>
#include <stdint.h>

#include "ssh_role_bootstrap.h"

#define CTOS_SSH_CALLBACK_MAX 8U
#define CTOS_SSH_CALLBACK_MAX_CREDENTIAL_BYTES (1024U * 1024U)
#define CTOS_SSH_CALLBACK_MAX_FILE_BYTES (1024U * 1024U)
#define CTOS_SSH_CALLBACK_MAX_HOST_RESULT_BYTES 2048U
#define CTOS_SSH_CALLBACK_HOST_KEY_TYPE_BYTES 65U
#define CTOS_SSH_CALLBACK_HOST_FINGERPRINT_BYTES 64U
#define CTOS_SSH_CALLBACK_HOST_PROPOSAL_BINDING_BYTES 65U
#define CTOS_SSH_CALLBACK_CANDIDATE_ID_BYTES 129U

typedef uint64_t ctos_ssh_callback_token;

/* Explicit tags are part of the native/Java contract; they are not Java enum
 * ordinals and must remain stable if the Java enum is reordered. */
enum ctos_ssh_callback_host_classification {
    CTOS_SSH_CALLBACK_HOST_KNOWN = 1,
    CTOS_SSH_CALLBACK_HOST_NEW = 2,
    CTOS_SSH_CALLBACK_HOST_CHANGED = 3,
    CTOS_SSH_CALLBACK_HOST_REVOKED = 4,
    CTOS_SSH_CALLBACK_HOST_CERTIFICATE = 5,
    CTOS_SSH_CALLBACK_HOST_INVALID = 6
};

enum ctos_ssh_callback_credential_kind {
    CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD = 1,
    CTOS_SSH_CALLBACK_CREDENTIAL_KEY_PASSPHRASE = 2,
    CTOS_SSH_CALLBACK_CREDENTIAL_KEYBOARD_INTERACTIVE = 3,
    CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY = 4
};

enum ctos_ssh_callback_host_cas_status {
    CTOS_SSH_CALLBACK_HOST_CAS_NOT_REQUIRED = 0,
    CTOS_SSH_CALLBACK_HOST_CAS_COMMITTED = 1,
    CTOS_SSH_CALLBACK_HOST_CAS_FAILED = 2
};

enum ctos_ssh_callback_host_result_outcome {
    CTOS_SSH_CALLBACK_HOST_RESULT_APPROVED = 1
};

/* Metadata copied from the live native role and the current host-key event.
 * These structs contain no known_hosts body or credential. */
struct ctos_ssh_host_evidence {
    ctos_ssh_role_token role;
    unsigned int hop;
    unsigned int port;
    int classification;
    char connection_id[CTOS_SSH_ROLE_CONNECTION_ID_BYTES + 1];
    char target_identity[CTOS_SSH_ROLE_TARGET_ID_BYTES + 1];
    char host[CTOS_SSH_ROLE_HOST_BYTES + 1];
    char key_type[CTOS_SSH_CALLBACK_HOST_KEY_TYPE_BYTES];
    char fingerprint[CTOS_SSH_CALLBACK_HOST_FINGERPRINT_BYTES];
    unsigned char base_digest[32];
    unsigned char replacement_digest[32];
};

/* Exact bounded outcome.  A committed NEW result carries only the proposal
 * binding; the replacement body remains in the managed provider. */
struct ctos_ssh_host_result {
    ctos_ssh_role_token role;
    unsigned int hop;
    unsigned int port;
    int classification;
    int outcome;
    int cas_status;
    char connection_id[CTOS_SSH_ROLE_CONNECTION_ID_BYTES + 1];
    char target_identity[CTOS_SSH_ROLE_TARGET_ID_BYTES + 1];
    char host[CTOS_SSH_ROLE_HOST_BYTES + 1];
    char key_type[CTOS_SSH_CALLBACK_HOST_KEY_TYPE_BYTES];
    char fingerprint[CTOS_SSH_CALLBACK_HOST_FINGERPRINT_BYTES];
    char proposal_binding[CTOS_SSH_CALLBACK_HOST_PROPOSAL_BINDING_BYTES];
    unsigned char base_digest[32];
    unsigned char replacement_digest[32];
};

/* One exact NEW-host replacement body.  This is a separate synchronous
 * handoff from CSHR: the body is borrowed only for the call and the native
 * caller relinquishes it for wiping when the call returns. */
struct ctos_ssh_known_hosts_candidate {
    struct ctos_ssh_host_evidence evidence;
    char candidate_id[CTOS_SSH_CALLBACK_CANDIDATE_ID_BYTES];
    unsigned char *replacement_body;
    size_t replacement_body_length;
};

/* The target is called synchronously on the thread that opens the binding.
 * `environment` is deliberately opaque here: the JNI target validates that it
 * is an attached JNIEnv from the same JavaVM before any Java call. */
struct ctos_ssh_callback_target {
    void *context;
    int (*validate_environment)(void *context, void *environment);
    int (*host_key)(void *context, void *environment, int classification,
        const char *key_type, const char *fingerprint,
        const unsigned char base_digest[32],
        const unsigned char replacement_digest[32]);
    int (*host_evidence)(void *context, void *environment,
        const struct ctos_ssh_host_evidence *evidence,
        struct ctos_ssh_host_result *result);
    int (*known_hosts_candidate)(void *context, void *environment,
        const struct ctos_ssh_known_hosts_candidate *candidate);
    /* Rolls back a managed candidate when native detects that the
     * synchronous handoff cannot complete. It must not release unrelated
     * callback or provider objects. */
    int (*known_hosts_candidate_abort)(void *context, void *environment);
    int (*kex_complete)(void *context, void *environment, uint64_t cycle,
        const unsigned char session_digest[32]);
    int (*credential)(void *context, void *environment, int kind);
    int (*release)(void *context, void *environment);
    /* Optional final context destructor. It runs only after the callback slot
     * has been removed; release must leave context valid for a cleanup retry. */
    void (*destroy)(void *context);
    /* Optional bounded credential handoff.  The existing boolean callback is
     * deliberately retained for callers that only need availability. */
    int (*credential_bytes)(void *context, void *environment, int kind,
        unsigned char *output, size_t capacity, size_t *length);
    /* Optional receipt/proof-bound route. Absence never selects a legacy target. */
    int (*credential_bytes_selected)(void *context, void *environment, int kind,
        const unsigned char selection_receipt[32], uint64_t cycle,
        const unsigned char session_digest[32], unsigned char *output,
        size_t capacity, size_t *length);
};

/* Open binds the callback to the calling process, current event-loop thread,
 * live native role and attached environment. It accepts no argv, shell,
 * pathname, descriptor, prompt, terminal output or secret body. */
int ctos_ssh_callback_open(const struct ctos_ssh_callback_target *target,
    ctos_ssh_role_token role, int owner_uid, uint64_t deadline,
    void *environment, ctos_ssh_callback_token *callback);

/* Cancellation may be requested by another owner thread. It only prevents a
 * future callback; close still belongs to the bound event-loop thread. */
int ctos_ssh_callback_cancel(ctos_ssh_callback_token callback);
int ctos_ssh_callback_close(ctos_ssh_callback_token callback, void *environment);

/* Internal same-client continuation, issued only after its fresh real auth
 * proof. This never permits a host/file/credential callback after expiry. */
int ctos_ssh_callback_promote_transport(ctos_ssh_callback_token, void *, const void *);
int ctos_ssh_callback_check_transport(ctos_ssh_callback_token, void *, const void *);

/* Checks owner/environment and live role/cancellation/deadline without calling
 * a host, KEX or credential provider. This grants no secret authority. */
int ctos_ssh_callback_check_live(ctos_ssh_callback_token callback, void *environment);

/* These are the only native-to-Java callback entry points. Each call is
 * synchronous and must supply the currently attached JNIEnv/environment. */
int ctos_ssh_callback_host_key(ctos_ssh_callback_token callback, void *environment,
    int classification, const char *key_type, const char *fingerprint,
    const unsigned char base_digest[32],
    const unsigned char replacement_digest[32]);
int ctos_ssh_callback_host_evidence(ctos_ssh_callback_token callback, void *environment,
    int classification, const char *key_type, const char *fingerprint,
    const unsigned char base_digest[32],
    const unsigned char replacement_digest[32],
    struct ctos_ssh_host_result *result);

/* Transfers one exact replacement body synchronously.  The caller relinquishes
 * `replacement_body`; this function wipes its bounded contents on success and
 * every failure and never retains the pointer. */
int ctos_ssh_callback_known_hosts_candidate(ctos_ssh_callback_token callback,
    void *environment, const char *candidate_id, int classification,
    const char *key_type, const char *fingerprint,
    const unsigned char base_digest[32],
    const unsigned char replacement_digest[32],
    unsigned char *replacement_body, size_t replacement_body_length);
/* Rolls back a candidate that was accepted by the one-shot handoff when a
 * later host-evidence/CAS or protocol admission check fails.  It belongs to
 * the same event-loop thread and is idempotent after a successful rollback. */
int ctos_ssh_callback_known_hosts_candidate_abort(
    ctos_ssh_callback_token callback, void *environment);
int ctos_ssh_callback_kex_complete(ctos_ssh_callback_token callback, void *environment,
    uint64_t cycle, const unsigned char session_digest[32]);
int ctos_ssh_callback_credential(ctos_ssh_callback_token callback, void *environment,
    int kind);

/* Synchronously copies one typed credential into caller-owned memory.  The
 * output is never owned or retained by the callback target. */
int ctos_ssh_callback_credential_bytes(ctos_ssh_callback_token callback,
    void *environment, int kind, unsigned char *output, size_t capacity,
    size_t *length);

int ctos_ssh_callback_credential_bytes_selected(ctos_ssh_callback_token callback,
    void *environment, int kind, const unsigned char selection_receipt[32],
    uint64_t cycle, const unsigned char session_digest[32],
    unsigned char *output, size_t capacity, size_t *length);

#endif

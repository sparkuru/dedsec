#ifndef CTOS_SSH_CALLBACK_ADAPTER_H
#define CTOS_SSH_CALLBACK_ADAPTER_H

#include <stddef.h>
#include <stdint.h>

#include "ssh_role_bootstrap.h"

#define CTOS_SSH_CALLBACK_MAX CTOS_SSH_ROLE_MAX
#define CTOS_SSH_CALLBACK_MAX_CREDENTIAL_BYTES (1024U * 1024U)
#define CTOS_SSH_CALLBACK_MAX_FILE_BYTES (1024U * 1024U)
#define CTOS_SSH_CALLBACK_MAX_HOST_RESULT_BYTES 2048U
#define CTOS_SSH_CALLBACK_HOST_KEY_TYPE_BYTES 65U
#define CTOS_SSH_CALLBACK_HOST_FINGERPRINT_BYTES 64U
#define CTOS_SSH_CALLBACK_HOST_PROPOSAL_BINDING_BYTES 65U
#define CTOS_SSH_CALLBACK_CANDIDATE_ID_BYTES 129U
#define CTOS_SSH_CALLBACK_CHANGED_RECORDS_BYTES (88U + 128U * 216U)

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

enum ctos_ssh_provisional_new_stage {
    CTOS_SSH_PROVISIONAL_NEW_CANDIDATE = 1,
    CTOS_SSH_PROVISIONAL_NEW_REVIEW = 2,
    CTOS_SSH_PROVISIONAL_NEW_KEX = 3
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

/* The old-row projection is the bounded CSHTv1 query form produced from
 * exactly the same source snapshot and base digest as the replacement. */
struct ctos_ssh_changed_host_candidate {
    struct ctos_ssh_known_hosts_candidate candidate;
    unsigned char *old_records;
    size_t old_records_length;
};

/* The target is called synchronously on the thread that opens the binding.
 * `environment` is deliberately opaque here: the JNI target validates that it
 * is an attached JNIEnv from the same JavaVM before any Java call. */
typedef int (*ctos_ssh_callback_receiver_validator)(void *, void *, const void *);
typedef int (*ctos_ssh_callback_effect_admission)(void *, void *);

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
    /* CHANGED is a separate provisional path, never a NEW proof or CAS. */
    int (*changed_host_candidate)(void *context, void *environment,
        const struct ctos_ssh_changed_host_candidate *candidate);
    int (*changed_host_review)(void *context, void *environment,
        const struct ctos_ssh_host_evidence *evidence);
    /* Optional child-only NEW path. These hooks never report a durable CAS
     * or create a host/credential proof. The KEX hook is synchronous and can
     * precede upstream drain: it only records the signature milestone and
     * must not write trust or detach a commit admission. These synchronous
     * hooks must not wait for product UI: a FIN/CLOSE can remain unpumped.
     * All three are required. */
    int (*provisional_new_candidate)(void *context, void *environment,
        const struct ctos_ssh_known_hosts_candidate *candidate);
    int (*provisional_new_review)(void *context, void *environment,
        const struct ctos_ssh_host_evidence *evidence);
    int (*provisional_new_kex)(void *context, void *environment, uint64_t cycle,
        const unsigned char session_digest[32]);
    /* Optional identity check for a receiver entering from the active target.
     * It must not call back into the adapter or retain the receiver. */
    ctos_ssh_callback_receiver_validator validate_receiver;
    /* Lock-free STOP admission; called only while the client owns this target. */
    ctos_ssh_callback_effect_admission admit_effect;
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

/* A native client claims its exact role binding only after all fallible
 * construction steps. Claimed bindings may only be closed by that owner;
 * failed close retains the claim for retry. */
int ctos_ssh_callback_client_claim(ctos_ssh_callback_token, void *,
    ctos_ssh_role_token, const void *);
int ctos_ssh_callback_client_close(ctos_ssh_callback_token, void *, const void *);

/* Internal scope registration is bound to the same exclusive client claim.
 * The registered checker alone receives the owner's private pointer and must
 * validate it before dereferencing. Generic claims have no scoped checker. */
typedef int (*ctos_ssh_callback_scope_checker)(const void *,
    ctos_ssh_callback_token, ctos_ssh_role_token, void *);
int ctos_ssh_callback_client_claim_scoped(ctos_ssh_callback_token, void *,
    ctos_ssh_role_token, const void *, ctos_ssh_callback_scope_checker);
/* The client retains the returned target only for the lifetime of its claim. */
int ctos_ssh_callback_client_claim_scoped_effect(ctos_ssh_callback_token, void *,
    ctos_ssh_role_token, const void *, ctos_ssh_callback_scope_checker,
    ctos_ssh_callback_effect_admission *, void **);
/* The caller supplies only the exact previously registered checker identity;
 * no owner pointer or arbitrary context is returned. This synchronous scope
 * rejects nested targets and recursive checks. Scope success is not itself an
 * ancestor proof, a secret grant or atomicity of an external effect. */
int ctos_ssh_callback_client_scope_check(ctos_ssh_callback_token, void *,
    ctos_ssh_callback_scope_checker);
/* Require the registered validator to match the caller's expected function
 * before invoking it; a native-only target cannot stand in for the JNI target. */
int ctos_ssh_callback_client_scope_check_receiver(ctos_ssh_callback_token, void *,
    const void *receiver, ctos_ssh_callback_receiver_validator,
    ctos_ssh_callback_scope_checker);
/* Validates immutable fields of this thread's pinned target without a lock. */
int ctos_ssh_callback_current_receiver(ctos_ssh_callback_token, void *, const void *,
    ctos_ssh_callback_receiver_validator);

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
int ctos_ssh_callback_changed_host_candidate(ctos_ssh_callback_token callback,
    void *environment, const char *candidate_id, const char *key_type,
    const char *fingerprint, const unsigned char base_digest[32],
    const unsigned char replacement_digest[32], unsigned char *replacement_body,
    size_t replacement_body_length, unsigned char *old_records, size_t old_records_length);
int ctos_ssh_callback_changed_host_review(ctos_ssh_callback_token callback,
    void *environment, const char *key_type, const char *fingerprint,
    const unsigned char base_digest[32], const unsigned char replacement_digest[32]);
/* Returns 1 only when the same live owner implements both provisional hooks. */
int ctos_ssh_callback_supports_changed(ctos_ssh_callback_token callback, void *environment);
/* Native child dispatch only. These functions are not JNI entrypoints and do
 * not grant trust or credential authority. */
int ctos_ssh_callback_supports_provisional_new(ctos_ssh_callback_token, void *);
int ctos_ssh_callback_provisional_new_candidate(ctos_ssh_callback_token, void *,
    const char *, const char *, const char *, const unsigned char[32],
    const unsigned char[32], unsigned char *, size_t, int *);
int ctos_ssh_callback_provisional_new_review(ctos_ssh_callback_token, void *,
    const char *, const char *, const unsigned char[32], const unsigned char[32]);
int ctos_ssh_callback_provisional_new_kex(ctos_ssh_callback_token, void *,
    uint64_t, const unsigned char[32]);
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

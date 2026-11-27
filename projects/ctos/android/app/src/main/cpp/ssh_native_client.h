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
#define CTOS_SSH_NATIVE_CLIENT_KBI_WIRE_BYTES 32768U
#define CTOS_SSH_NATIVE_CLIENT_KBI_TEXT_BYTES 4096U
#define CTOS_SSH_NATIVE_CLIENT_KBI_PROMPTS 16U
#define CTOS_SSH_NATIVE_CLIENT_KBI_ROUNDS 16U

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

enum ctos_ssh_native_client_keyboard_interactive_auth_result {
    CTOS_SSH_KEYBOARD_AUTH_NOT_STARTED = 0,
    CTOS_SSH_KEYBOARD_AUTH_PENDING = 1,
    CTOS_SSH_KEYBOARD_AUTH_SUCCESS = 2,
    CTOS_SSH_KEYBOARD_AUTH_REJECTED = 3,
    CTOS_SSH_KEYBOARD_AUTH_PARTIAL = 4,
    CTOS_SSH_KEYBOARD_AUTH_CHALLENGE = 5
};

enum ctos_ssh_native_client_discovery_result {
    CTOS_SSH_DISCOVERY_NOT_STARTED = 0,
    CTOS_SSH_DISCOVERY_PENDING = 1,
    CTOS_SSH_DISCOVERY_METHODS = 2,
    CTOS_SSH_DISCOVERY_NONE_SUCCESS = 3,
    CTOS_SSH_DISCOVERY_PARTIAL = 4
};

#define CTOS_SSH_DISCOVERY_PUBLICKEY 1U
#define CTOS_SSH_DISCOVERY_KEYBOARD_INTERACTIVE 2U
#define CTOS_SSH_DISCOVERY_PASSWORD 4U
#define CTOS_SSH_DISCOVERY_OTHER 8U

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

enum ctos_ssh_native_jump_phase {
    CTOS_SSH_JUMP_OPENING = 1,
    CTOS_SSH_JUMP_OPEN = 2,
    CTOS_SSH_JUMP_CLOSED = 3,
    CTOS_SSH_JUMP_REJECTED = 4
};

struct ctos_ssh_native_jump_status {
    enum ctos_ssh_native_jump_phase phase;
    int remote_eof, local_eof;
    size_t buffered_bytes;
};

struct ctos_ssh_native_client;
struct ctos_ssh_native_jump_relay;
struct sshkey;

enum ctos_ssh_native_host_key_mode {
    CTOS_SSH_HOST_KEY_PINNED = 0,
    CTOS_SSH_HOST_KEY_MANAGED = 1
};

/* Terminal diagnostics from an actual issuer result, never host-key approval. */
enum ctos_ssh_native_host_key_rejection {
    CTOS_SSH_HOST_REJECTION_NONE = 0,
    CTOS_SSH_HOST_REJECTION_CHANGED = 1,
    CTOS_SSH_HOST_REJECTION_REVOKED = 2,
    CTOS_SSH_HOST_REJECTION_CERTIFICATE = 3,
    CTOS_SSH_HOST_REJECTION_CHANGED_BLOCKED = 4
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
    /* Optional immutable public snapshot used only by a managed jump child
     * for hostfile classification. Its separate owner must retain it until
     * the child is closed; the original managed borrow still fences use. */
    const struct ctos_ssh_view *fresh_hostfile_view;
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

/* Reports a one-jump signed-KEX/drained-output snapshot only. It grants no
 * host proof, trust write, authentication or shell authority; not exposed in
 * JNI. A pending child whose upstream or guarded deadline/generation fails
 * is terminal and cleanup-only. Wrong-thread/non-pending calls are rejected
 * without terminalizing it. A concurrent FIN can remain unobserved until the
 * owner pumps the transport. */
int ctos_ssh_native_client_provisional_new_ready(struct ctos_ssh_native_client *, int *);

/* Guarded hop-1 only: a bounded, non-consuming snapshot for the owner's
 * provisional NEW wait. Existing child bytes in OpenSSH, the relay or child
 * socket (including valid EXT_INFO), EOF, and socket errors fail closed. A
 * parent packet awaiting a pump or undrained outbound data yields idle=0.
 * This never dispatches or writes SSH packets, grants no authority, and says
 * nothing about bytes arriving after the snapshot or events before pending.
 * It is not exposed by JNI; production first-trust remains disabled. */
int ctos_ssh_native_client_provisional_new_poll_idle(struct ctos_ssh_native_client *, int *);

/* Guarded provisional NEW only: bounded AEAD input, at most one validated
 * EXT_INFO, no authentication or outbound SSH packet. Input is discarded;
 * this temporary connection may not continue authentication. Caller pumps
 * the parent/relay separately. This is a snapshot, not write authority, and
 * is not exposed through JNI. */
int ctos_ssh_native_client_provisional_new_poll_ext(struct ctos_ssh_native_client *, int *);

/* Owner/live-role-only metadata, including after a terminal verifier denial.
 * Errors clear out; close retires it. No callback, proof or credential authority. */
int ctos_ssh_native_client_host_key_rejection(const struct ctos_ssh_native_client *,
    enum ctos_ssh_native_host_key_rejection *);

/* Live owner-only metadata fingerprint, permitted before/after KEX. No I/O or
 * credential authority; every error clears a non-null 32-byte output. */
int ctos_ssh_native_client_authentication_selection_receipt(
    const struct ctos_ssh_native_client *, unsigned char receipt[32]);

/* One selected typed handoff after service-ready/current proof. No USERAUTH is
 * sent; output remains private until all post-provider checks pass. */
int ctos_ssh_native_client_selected_credential_bytes(struct ctos_ssh_native_client *,
    unsigned char *output, size_t capacity, size_t *length);

/* One same-owner, credential-free discovery on the existing selected session.
 * The result is a method bitmask, a distinct none success, or partial success. */
int ctos_ssh_native_client_begin_auth_discovery(struct ctos_ssh_native_client *);
int ctos_ssh_native_client_auth_discovery_result(const struct ctos_ssh_native_client *,
    enum ctos_ssh_native_client_discovery_result *, unsigned int *);
/* CSASv4 may read the latest server list after PARTIAL or ordinary rejection. */
int ctos_ssh_native_client_auth_continuation_methods(const struct ctos_ssh_native_client *, unsigned int *);
/* Pump only banners/disconnects while a server-listed password/key prompt is pending. */
int ctos_ssh_native_client_poll_auth_prompt(struct ctos_ssh_native_client *,
    const unsigned char expected_plan_receipt[32], unsigned int method);
/* One explicit password request using owned selection and the shared selected
 * attempt. No retries or password-change continuation; all remote text is discarded. */
int ctos_ssh_native_client_begin_password_auth(struct ctos_ssh_native_client *);
/* CSASv4, same-owner discovery -> one newly supplied password, CSAPv2 proof. */
int ctos_ssh_native_client_begin_discovered_password(struct ctos_ssh_native_client *,
    const unsigned char expected_plan_receipt[32], const unsigned char *, size_t,
    unsigned char stage_receipt[92]);
/* Owner/live-proof-only read. Errors clear out and do not mutate the client. */
int ctos_ssh_native_client_password_auth_result(
    const struct ctos_ssh_native_client *,
    enum ctos_ssh_native_client_password_auth_result *);

/* One direct signed publickey request; expected identity comes only from owned
 * CSAS and private material only from the selected one-shot credential callback. */
int ctos_ssh_native_client_begin_private_key_auth(struct ctos_ssh_native_client *);
int ctos_ssh_native_client_continue_private_key_auth(struct ctos_ssh_native_client *,
    const unsigned char expected_plan_receipt[32], unsigned char stage_receipt[92]);
int ctos_ssh_native_client_private_key_auth_result(
    const struct ctos_ssh_native_client *,
    enum ctos_ssh_native_client_private_key_auth_result *);

/* Explicit keyboard-interactive only; no stored-credential callback. Challenge
 * is a bounded CSKIv1 public copy. CSKRv1 answers consume exactly the current
 * round once, under the original selected role/KEX/view/deadline proof. */
int ctos_ssh_native_client_begin_keyboard_interactive_auth(struct ctos_ssh_native_client *);
/* Server-listed KBI allows the authorized same-owner transition after PARTIAL;
 * CSASv4 also permits an untried KBI alternative after ordinary rejection.
 * CSASv3 receives CSAPv1; CSASv4 receives CSAPv2 with the actual stage number.
 * Both receipts are 92 bytes and bind the original plan and KEX. */
int ctos_ssh_native_client_continue_keyboard_interactive_auth(struct ctos_ssh_native_client *,
    const unsigned char expected_plan_receipt[32], unsigned char stage_receipt[92]);
int ctos_ssh_native_client_begin_discovered_keyboard(struct ctos_ssh_native_client *,
    const unsigned char expected_plan_receipt[32], unsigned char stage_receipt[92]);
int ctos_ssh_native_client_keyboard_interactive_auth_result(
    const struct ctos_ssh_native_client *,
    enum ctos_ssh_native_client_keyboard_interactive_auth_result *);

/* Freeze a configured server-alive policy once after authentication succeeds. */
int ctos_ssh_native_client_configure_server_alive(
    struct ctos_ssh_native_client *, unsigned int interval_seconds, unsigned int count_max);
int ctos_ssh_native_client_keyboard_interactive_challenge(
    const struct ctos_ssh_native_client *, unsigned char *, size_t, size_t *);
int ctos_ssh_native_client_respond_keyboard_interactive(
    struct ctos_ssh_native_client *, const unsigned char *, size_t);

/* Explicitly continue a proved initial KEX into fixed ssh-userauth service
 * negotiation. No username or credential is sent; ready is not authentication.
 * KEX-only callers keep their existing complete()/EALREADY stopping point. */
int ctos_ssh_native_client_begin_userauth_service(struct ctos_ssh_native_client *);
int ctos_ssh_native_client_userauth_service_ready(
    const struct ctos_ssh_native_client *);

int ctos_ssh_native_client_configure_no_pty(struct ctos_ssh_native_client *);
int ctos_ssh_native_client_begin_shell(struct ctos_ssh_native_client *, unsigned int, unsigned int);
int ctos_ssh_native_client_shell_status(const struct ctos_ssh_native_client *,
    struct ctos_ssh_native_shell_status *);
int ctos_ssh_native_client_shell_write(struct ctos_ssh_native_client *,
    const unsigned char *, size_t, size_t *);
int ctos_ssh_native_client_shell_read(struct ctos_ssh_native_client *,
    unsigned char *, size_t, size_t *);
int ctos_ssh_native_client_shell_resize(struct ctos_ssh_native_client *, unsigned int, unsigned int);
int ctos_ssh_native_client_shell_eof(struct ctos_ssh_native_client *);

/* Internal authenticated jump channel. Destination is the next live role,
 * not a hostname supplied by an external caller. This does not expose JNI or
 * authorize a complete ProxyJump route. The original auth/view deadline and
 * both roles remain mandatory until explicit retained-transport promotion. */
int ctos_ssh_native_client_begin_jump(struct ctos_ssh_native_client *, ctos_ssh_role_token next_role);
int ctos_ssh_native_client_begin_jump_guarded(struct ctos_ssh_native_client *,
    ctos_ssh_role_token next_role, uint64_t hop_deadline, uint32_t generation);
/* Owner-thread only. After the exact child is established and running, retire
 * this hop's auth/view authority and retain its transport. Promote nested
 * jumps from the target outwards before the original deadline. A retained hop
 * remains bound to the complete established descendant and ancestor proofs.
 * This primitive does not provide a JNI or product/background owner. */
int ctos_ssh_native_client_retain_jump_transport(struct ctos_ssh_native_client *);
int ctos_ssh_native_client_jump_status(const struct ctos_ssh_native_client *,
    struct ctos_ssh_native_jump_status *);
int ctos_ssh_native_client_jump_write(struct ctos_ssh_native_client *,
    const unsigned char *, size_t, size_t *);
int ctos_ssh_native_client_jump_read(struct ctos_ssh_native_client *,
    unsigned char *, size_t, size_t *);
int ctos_ssh_native_client_jump_eof(struct ctos_ssh_native_client *);

/* Internal transport-only relay. It adopts an already-connected nonblocking
 * AF_UNIX stream on success, and never creates a listener or remote socket.
 * The parent must outlive the relay. Bytes already queued in the peer socket
 * are not revoked by this API: a downstream SSH client still needs its own
 * recursive upstream proof and cancellation guard before authenticating or
 * publishing anything. No JNI or product connection uses this primitive. */
int ctos_ssh_native_jump_relay_open(struct ctos_ssh_native_client *, int,
    struct ctos_ssh_native_jump_relay **);
int ctos_ssh_native_jump_relay_pump(struct ctos_ssh_native_jump_relay *);
int ctos_ssh_native_jump_relay_close(struct ctos_ssh_native_jump_relay *);
int ctos_ssh_native_jump_relay_destroy(struct ctos_ssh_native_jump_relay *);

/* Internal child constructor. The parent must own an open authenticated jump
 * channel bound to config->role; config->socket_fd must be -1. A private local
 * socketpair binds the child to this exact relay before the child is exposed.
 * On success the child owns its callback, view borrow and transport, and must
 * close before the relay. On failure a non-NULL child is cleanup-only and owns
 * its callback and borrow; close/destroy it before closing/destroying any relay.
 * If no child is returned, the callback and view remain caller-owned. This is
 * has no JNI/product or background-session owner. */
int ctos_ssh_native_client_open_jump_child(struct ctos_ssh_native_client *,
    const struct ctos_ssh_native_client_config *, struct ctos_ssh_native_client **,
    struct ctos_ssh_native_jump_relay **);

/* Internal same-thread check, available only during a synchronous callback
 * dispatched by this exact registered child. Host callbacks must originate in
 * packet processing; a direct pinned-key verification cannot acquire a proof.
 * It checks original upstream proofs
 * in sequence, not an atomic chain snapshot or atomicity of a later effect.
 * A parent channel at EOF or CLOSED can still pass to permit draining already
 * received bytes: success does not prove that the transport remains writable.
 * Root/direct clients, directly invoked wrappers and nested callbacks fail.
 * This is not a JNI entry, credential grant or product ProxyJump capability. */
int ctos_ssh_native_client_callback_ancestor_proofs_check(ctos_ssh_callback_token, void *);
int ctos_ssh_native_client_callback_ancestor_proofs_check_receiver(
    ctos_ssh_callback_token, void *, const void *receiver, ctos_ssh_callback_receiver_validator);
int ctos_ssh_native_client_effect_java_copy_begin(void *, const void *,
    ctos_ssh_callback_receiver_validator);
void ctos_ssh_native_client_effect_java_copy_end(void *, const void *,
    ctos_ssh_callback_receiver_validator);
int ctos_ssh_native_client_effect_java_callback_returned(void);
int ctos_ssh_native_client_effect_jni_copy_begin(void *, const void *,
    ctos_ssh_callback_receiver_validator);
void ctos_ssh_native_client_effect_jni_copy_end(void);
int ctos_ssh_native_client_begin_userauth_service_guarded(struct ctos_ssh_native_client *, uint32_t);
int ctos_ssh_native_client_begin_auth_discovery_guarded(struct ctos_ssh_native_client *, uint32_t);
int ctos_ssh_native_client_begin_password_auth_guarded(struct ctos_ssh_native_client *, uint32_t);
int ctos_ssh_native_client_poll_selected_password_prompt(struct ctos_ssh_native_client *, const unsigned char[32]);

#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
uint64_t ctos_ssh_native_client_test_wire_channel_bytes(void);
uint64_t ctos_ssh_native_client_test_early_auth_reply_denials(void);
uint64_t ctos_ssh_native_client_test_pending_kex_drains(void);
/* Test-only: exercises the pinned JNI callback while the product gate is closed. */
int ctos_ssh_native_client_test_arm_child_effect(struct ctos_ssh_native_client *);
int ctos_ssh_native_client_test_abandoned_effect_frame(struct ctos_ssh_native_client *);
int ctos_ssh_native_client_test_before_java_secret_copy(void (*)(void *), void *);
int ctos_ssh_native_client_test_before_native_secret_copy(void (*)(void *), void *);
int ctos_ssh_native_client_test_before_password_packet(void (*)(void *), void *);
int ctos_ssh_native_client_test_before_jump_send(void (*)(void *), void *);
int ctos_ssh_native_client_test_last_password_wiped(void);
int ctos_ssh_native_client_test_before_promotion_release(struct ctos_ssh_native_client *,
    void (*hook)(void *), void *context);
/* Test-only admission of an arbitrary claim to exercise owner registry refusal. */
int ctos_ssh_native_client_test_claim_callback_scope(ctos_ssh_callback_token,
    void *, ctos_ssh_role_token, const void *);
int ctos_ssh_native_client_test_invalidate_kex(struct ctos_ssh_native_client *);
int ctos_ssh_native_client_test_fail_next_view_release(struct ctos_ssh_native_client *);
int ctos_ssh_native_client_test_provisional_input(struct ctos_ssh_native_client *,
    const unsigned char *, size_t, int);
int ctos_ssh_native_client_test_provisional_peer_eof(struct ctos_ssh_native_client *);
int ctos_ssh_native_client_test_provisional_fill_peer(struct ctos_ssh_native_client *);
size_t ctos_ssh_native_client_test_provisional_peer_buffered(struct ctos_ssh_native_client *);
int ctos_ssh_native_client_test_provisional_fail_peek(int);
int ctos_ssh_native_client_test_provisional_hook(struct ctos_ssh_native_client *,
    int, void (*)(void *), void *);
#endif

/* Orders a caller-owned credential input copy against this client's STOP. */
int ctos_ssh_native_client_admit_input_copy(const struct ctos_ssh_native_client *);

/*
 * Close is owned by the event-loop thread that opened the callback binding and
 * must receive the same attached callback environment. It first retries any
 * candidate rollback, then synchronously closes the callback binding, then
 * unbinds/frees OpenSSH and the owned descriptor. Failures retain a cleanup
 * owner for retry on the same thread/environment; some earlier cleanup may
 * already have completed. A close error tombstones the descriptor so a retry
 * never closes a reused descriptor number. External callers must not close
 * this callback token independently while the client exists.
 */
int ctos_ssh_native_client_close(struct ctos_ssh_native_client *);

/* Free only a closed tombstone. */
int ctos_ssh_native_client_destroy(struct ctos_ssh_native_client *);

#endif

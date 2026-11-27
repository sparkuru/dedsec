#define _GNU_SOURCE

#include "ssh_native_client.h"
#include "ssh_hostfile_issuer.h"
#include "ssh_key_policy.h"

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include "digest.h"
#include "includes.h"
#include "dispatch.h"
#include "compat.h"
#include "kex.h"
#include "myproposal.h"
#include "packet.h"
#include "ssh2.h"
#include "ssh_api.h"
#include "ssh_kex_guard.h"
#include "sshbuf.h"
#include "ssherr.h"
#include "sshkey.h"

/* Provided only by the pinned, locally patched packet.c. */
extern int ctos_ssh_packet_read_provisional(struct ssh *, u_char *)
    __attribute__((visibility("hidden")));

#define PROVISIONAL_INPUT_MAX (16U * 1024U)
#define PROVISIONAL_READ_MAX 4096U

/* The pinned generated config may define unavailable allocator shims. The
 * small native owner object and verifier scratch use the platform allocator. */
#ifdef malloc
#undef malloc
#endif
#ifdef calloc
#undef calloc
#endif
#ifdef realloc
#undef realloc
#endif

enum userauth_service_phase {
    USERAUTH_SERVICE_DISABLED,
    USERAUTH_SERVICE_REQUEST_PENDING,
    USERAUTH_SERVICE_AWAIT_ACCEPT,
    USERAUTH_SERVICE_READY
};

enum userauth_phase {
    AUTH_DISABLED,
    AUTH_REQUEST_PENDING,
    AUTH_AWAIT_REPLY,
    AUTH_WAIT_INPUT,
    AUTH_FINISHED
};

enum wire_effect_phase { WIRE_EFFECT_NONE, WIRE_EFFECT_CONNECTING, WIRE_EFFECT_AUTHENTICATING };

struct ctos_ssh_native_client {
    struct ctos_ssh_native_client *scope_registry_next;
    struct ssh *ssh;
    pthread_t owner;
    pid_t pid;
    uid_t uid;
    struct sshkey *server_host_public_key;
    enum ctos_ssh_native_host_key_mode host_key_mode;
    int in_transport_step;
    ctos_ssh_role_token role;
    ctos_ssh_callback_token callback;
    void *callback_environment;
    ctos_ssh_callback_effect_admission admit_effect;
    void *effect_context;
    struct ctos_ssh_view_borrow *managed_view_borrow;
    struct ctos_ssh_view_borrow_claim *view_claim;
    const struct ctos_ssh_view *managed_view;
    const struct ctos_ssh_view *hostfile_view;
    int socket_fd;
    int caller_socket_fd;
    int closed;
    int failed;
    enum ctos_ssh_native_host_key_rejection host_key_rejection;
    int completed;
    /* NEW is provisional until its own signed KEX completes; this is never
     * a host proof or authentication authority. */
    int provisional_new_state;
    int provisional_ext_seen;
    int provisional_ext_advertised;
    int candidate_handed_off;
    int kex_released;
    int callback_closed;
    int view_released;
    unsigned int completion_calls;
    uint64_t completion_cycle;
    unsigned char completion_session_digest[32];
    enum userauth_service_phase service_phase;
    uint64_t deadline;
    unsigned char expected_host_fingerprint[CTOS_SSH_NATIVE_CLIENT_FINGERPRINT_BYTES];
    char known_hosts_path[513];
    char host[CTOS_SSH_ROLE_HOST_BYTES + 1U];
    char candidate_id[CTOS_SSH_CALLBACK_CANDIDATE_ID_BYTES];
    size_t step_budget;
    unsigned char *authentication_selection_wire;
    size_t authentication_selection_wire_length;
    unsigned char authentication_selection_receipt[32];
    int authentication_selection_kind;
    unsigned int authentication_selection_version;
    int active_authentication_kind;
    unsigned int authentication_stage;
    int keyboard_continuation;
    int automatic_selection;
    unsigned int automatic_methods;
    unsigned int automatic_attempted_methods;
    int automatic_continuation_dispatch;
    int authentication_key_bundle;
    char continuation_methods[1025];
    int selected_credential_attempted;
    int discovery_selected_dispatch;
    uint32_t jump_effect_generation;
    uint64_t jump_open_deadline;
    struct ctos_ssh_native_client *wire_effect_parent;
    ctos_ssh_role_token wire_effect_next_role;
    uint32_t wire_effect_generation;
    uint64_t wire_effect_deadline;
    enum wire_effect_phase wire_effect_phase;
    size_t authentication_username_offset;
    size_t authentication_username_length;
    size_t authentication_public_offset;
    size_t authentication_public_length;
    enum userauth_phase auth_phase;
    int auth_result;
    enum ctos_ssh_native_client_discovery_result discovery_result;
    unsigned int discovery_methods;
    unsigned int auth_banners;
    unsigned int keyboard_round, keyboard_prompts;
    unsigned char *keyboard_challenge;
    size_t keyboard_challenge_length;
    int established;
    struct ssh *established_ssh;
    struct sshkey *established_host_key;
    struct ctos_ssh_role_snapshot established_role;
    uint64_t established_cycle;
    unsigned char established_session_digest[32];
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
    void (*test_before_promotion_release)(void *);
    void *test_promotion_release_context;
    void (*test_provisional_hook)(void *);
    void *test_provisional_hook_context;
    int test_provisional_hook_stage;
#endif
    unsigned int server_alive_interval, server_alive_count_max, server_alive_missed, server_alive_pending;
    uint64_t server_alive_last_activity;
    int server_alive_configured;
    int shell_no_pty;
    int jump_mode;
    struct ctos_ssh_native_jump_relay *jump_relay;
    struct ctos_ssh_native_jump_relay *upstream_relay;
    int jump_relay_used;
    struct ctos_ssh_role_snapshot jump_next_role;
    struct ctos_ssh_native_shell_status shell;
    unsigned int shell_columns, shell_rows;
    uint32_t remote_channel, remote_window, remote_maxpacket, local_window, window_credit;
    int shell_request_drained, shell_started, remote_close, local_close, shell_rejected_pending, shell_transport_eof;
    unsigned char shell_buffer[65536];
};

struct ctos_ssh_native_jump_relay {
    struct ctos_ssh_native_client *parent;
    struct ctos_ssh_native_client *child;
    pthread_t owner;
    pid_t pid;
    uid_t uid;
    int fd, failed, closed;
    int peer_eof, channel_eof_sent, peer_read_shutdown, peer_write_shutdown;
    unsigned char to_channel[32768], to_peer[32768];
    size_t channel_offset, channel_length, peer_offset, peer_length;
};

static int shell_proof(const struct ctos_ssh_native_client *client);
static int jump_relay_owner(const struct ctos_ssh_native_jump_relay *relay);
static int view_borrow_is_live(const struct ctos_ssh_view_borrow *borrow);
static int child_publication_live(const struct ctos_ssh_native_client *client);
static int advertised_algorithm(const char *, const char *);

/* A client can be opened, used and destroyed only on its original owner
 * thread. This owner-local list permits comparing an opaque callback claim
 * against real, published clients before dereferencing it. No registry lock
 * is held while traversing callback, role, view or transport state. */
static _Thread_local struct ctos_ssh_native_client *scope_clients;

/* A callback token alone cannot establish that the owning client dispatched
 * it. The owner thread pushes this private frame only around a native client
 * call to the adapter (or to the hostfile routines which call the adapter). */
enum native_callback_kind {
    NATIVE_CALLBACK_HOST_KEY,
    NATIVE_CALLBACK_KEX,
    NATIVE_CALLBACK_CREDENTIAL
};
struct native_callback_dispatch {
    const struct ctos_ssh_native_client *client;
    struct native_callback_dispatch *previous;
    enum native_callback_kind kind;
    int effect_proved;
    int java_copy_state;
    int jni_copy_state;
    int java_copy_invalid;
};
static _Thread_local struct native_callback_dispatch *current_native_callback;
static void wipe_bytes(void *memory, size_t length);
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
static _Thread_local void (*test_before_java_secret_copy)(void *);
static _Thread_local void *test_before_java_secret_copy_context;
static _Thread_local void (*test_before_native_secret_copy)(void *);
static _Thread_local void *test_before_native_secret_copy_context;
static _Thread_local void (*test_before_password_packet)(void *);
static _Thread_local void *test_before_password_packet_context;
static _Thread_local void (*test_before_jump_send)(void *);
static _Thread_local void *test_before_jump_send_context;
static _Thread_local uint64_t test_wire_channel_bytes;
static _Thread_local uint64_t test_early_auth_reply_denials;
static _Thread_local uint64_t test_pending_kex_drains;
static _Thread_local int test_last_password_wiped;
static _Thread_local int test_provisional_peek_failure;
#endif

static void begin_native_callback(struct native_callback_dispatch *frame,
    const struct ctos_ssh_native_client *client, enum native_callback_kind kind) {
    frame->client = client;
    frame->previous = current_native_callback;
    frame->kind = kind;
    frame->effect_proved = 0;
    frame->java_copy_state = 0;
    frame->jni_copy_state = 0;
    frame->java_copy_invalid = 0;
    current_native_callback = frame;
}

static void end_native_callback(struct native_callback_dispatch *frame) {
    /* Last-resort cleanup uses only the frame that acquired each permit.
     * Normal JNI callbacks drain before the adapter can invalidate. */
    if (frame->java_copy_state == 1) {
        frame->java_copy_state = -1;
        ctos_ssh_effect_release();
    }
    if (frame->jni_copy_state == 1) {
        frame->jni_copy_state = -1;
        ctos_ssh_effect_release();
    }
    current_native_callback = frame->previous;
}

static void register_scope_client(struct ctos_ssh_native_client *client) {
    client->scope_registry_next = scope_clients;
    scope_clients = client;
}

static int unregister_scope_client(struct ctos_ssh_native_client *client) {
    struct ctos_ssh_native_client **cursor = &scope_clients;
    while (*cursor != NULL && *cursor != client) cursor = &(*cursor)->scope_registry_next;
    if (*cursor == NULL) { errno = EACCES; return -1; }
    *cursor = client->scope_registry_next;
    client->scope_registry_next = NULL;
    return 0;
}

static int client_owner(const struct ctos_ssh_native_client *client) {
    if (client == NULL) { errno = EINVAL; return -1; }
    if (client->pid != getpid() || client->uid != getuid()
        || !pthread_equal(client->owner, pthread_self())) { errno = EPERM; return -1; }
    return 0;
}

/* The parent and relay are pinned until this child finishes closing.  Calling
 * the parent's proof recursively validates every earlier hop as well. */
static int upstream_proof(const struct ctos_ssh_native_client *client) {
    const struct ctos_ssh_native_jump_relay *relay = client->upstream_relay;
    const struct ctos_ssh_native_client *parent;

    if (relay == NULL) return 0;
    if (jump_relay_owner(relay) != 0) return -1;
    parent = relay->parent;
    if (relay->closed || relay->failed || relay->child != client || parent == NULL
        || parent->jump_relay != relay || !parent->jump_mode
        || parent->jump_next_role.token != client->role
        || (parent->shell.phase != CTOS_SSH_SHELL_RUNNING
            && parent->shell.phase != CTOS_SSH_SHELL_CLOSED)) {
        errno = EACCES;
        return -1;
    }
    return shell_proof(parent);
}

static int child_metadata_live(const struct ctos_ssh_native_client *client) {
    if (client->upstream_relay == NULL) return 0;
    if (ctos_ssh_callback_check_live(client->callback, client->callback_environment) != 0
        || view_borrow_is_live(client->managed_view_borrow) != 0) return -1;
    return upstream_proof(client);
}

static int callback_ancestor_scope_check(const void *owner,
    ctos_ssh_callback_token callback, ctos_ssh_role_token role,
    void *environment) {
    const struct ctos_ssh_native_client *client = scope_clients;
    while (client != NULL && client != owner) client = client->scope_registry_next;
    if (client == NULL) { errno = EACCES; return -1; }
    if (client_owner(client) != 0) return -1;
    if (client->closed || client->failed || client->upstream_relay == NULL
        || current_native_callback == NULL
        || current_native_callback->client != client
        || current_native_callback->previous != NULL
        || (current_native_callback->kind == NATIVE_CALLBACK_HOST_KEY
            && !client->in_transport_step)
        || client->callback != callback || client->role != role
        || client->callback_environment != environment || client->view_claim == NULL
        || client->managed_view_borrow == NULL) {
        errno = EACCES;
        return -1;
    }
    /* The child's callback is already active. Calling its ordinary live check
     * here would poison it as callback re-entry. The adapter validates that
     * slot; only the independent view and actual ancestor chain are checked. */
    if (view_borrow_is_live(client->managed_view_borrow) != 0
        || upstream_proof(client) != 0
        || view_borrow_is_live(client->managed_view_borrow) != 0) return -1;
    return 0;
}

/* Only owner-thread state observations; no callback reentry or terminalization. */
static int provisional_parent_open(const struct ctos_ssh_native_client *child) {
    const struct ctos_ssh_native_jump_relay *relay = child == NULL ? NULL : child->upstream_relay;
    const struct ctos_ssh_native_client *parent = relay == NULL ? NULL : relay->parent;
    if (relay == NULL || parent == NULL || parent->upstream_relay != NULL
        || relay->closed || relay->failed || relay->child != child || parent->jump_relay != relay
        || !parent->jump_mode || parent->jump_next_role.token != child->role) {
        errno = EACCES; return -1;
    }
    if (parent->shell.phase != CTOS_SSH_SHELL_RUNNING || parent->shell.remote_eof
        || parent->shell.local_eof || parent->remote_close || parent->local_close
        || parent->shell_transport_eof || relay->peer_eof || relay->channel_eof_sent
        || relay->peer_read_shutdown || relay->peer_write_shutdown) {
        errno = EPIPE; return -1;
    }
    return 0;
}

/* Called only from the adapter while its exact child target is on the stack.
 * It is deliberately not a JNI entrypoint or a reusable capability. */
__attribute__((visibility("hidden")))
int ctos_ssh_native_client_provisional_scope_check(ctos_ssh_callback_token callback,
    void *environment, enum ctos_ssh_provisional_new_stage stage) {
    struct native_callback_dispatch *frame = current_native_callback;
    const struct ctos_ssh_native_client *client;
    struct ctos_ssh_role_snapshot snapshot = {0};
    if (frame == NULL || frame->previous != NULL || (client = frame->client) == NULL
        || client->upstream_relay == NULL || client->upstream_relay->parent == NULL
        || client->upstream_relay->parent->upstream_relay != NULL || client->callback != callback
        || client->callback_environment != environment || client->closed || client->failed
        || client->completed || client->completion_calls != 0
        || client->ssh == NULL || client->ssh->kex == NULL) {
        errno = EACCES; return -1;
    }
    if (ctos_ssh_role_bootstrap_snapshot(client->role, &snapshot) != 0) return -1;
    int exact_hop = snapshot.token == client->role && snapshot.hop == 1U;
    wipe_bytes(&snapshot, sizeof(snapshot));
    if (!exact_hop) { errno = EACCES; return -1; }
    if (stage == CTOS_SSH_PROVISIONAL_NEW_CANDIDATE) {
        if (frame->kind != NATIVE_CALLBACK_HOST_KEY || !client->in_transport_step || client->candidate_handed_off
            || client->provisional_new_state != 0 || client->ssh->kex->done) {
            errno = EACCES; return -1;
        }
    } else if (stage == CTOS_SSH_PROVISIONAL_NEW_REVIEW) {
        if (frame->kind != NATIVE_CALLBACK_HOST_KEY || !client->in_transport_step || !client->candidate_handed_off
            || client->provisional_new_state != 0 || client->ssh->kex->done) {
            errno = EACCES; return -1;
        }
    } else if (stage == CTOS_SSH_PROVISIONAL_NEW_KEX) {
        if (frame->kind != NATIVE_CALLBACK_KEX || !client->candidate_handed_off
            || client->provisional_new_state != 1 || !client->ssh->kex->done) {
            errno = EACCES; return -1;
        }
    } else { errno = EINVAL; return -1; }
    if (provisional_parent_open(client) != 0
        || ctos_ssh_callback_client_scope_check(callback, environment, callback_ancestor_scope_check) != 0)
        return -1;
    return provisional_parent_open(client);
}

int ctos_ssh_native_client_callback_ancestor_proofs_check(ctos_ssh_callback_token callback,
    void *environment) {
    return ctos_ssh_callback_client_scope_check(callback, environment,
        callback_ancestor_scope_check);
}

int ctos_ssh_native_client_callback_ancestor_proofs_check_receiver(
    ctos_ssh_callback_token callback, void *environment, const void *receiver,
    ctos_ssh_callback_receiver_validator validator) {
    int result = ctos_ssh_callback_client_scope_check_receiver(callback, environment,
        receiver, validator, callback_ancestor_scope_check);
    if (result == 0 && current_native_callback != NULL
        && current_native_callback->kind == NATIVE_CALLBACK_CREDENTIAL
        && current_native_callback->client->jump_effect_generation != 0)
        current_native_callback->effect_proved = 1;
    return result;
}

static int automatic_auth_may_continue(const struct ctos_ssh_native_client *client) {
    return client->automatic_selection && client->auth_phase == AUTH_FINISHED
        && client->authentication_stage < 4U
        && (client->auth_result == CTOS_SSH_PASSWORD_AUTH_PARTIAL
            || client->auth_result == CTOS_SSH_PASSWORD_AUTH_REJECTED);
}

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

/* One guarded jump owns the root and its one child until authentication ends.
 * A short permit covers each nonblocking socket or relay transfer. Admission
 * must happen before the deadline; an admitted call can finish afterwards if
 * the owner is descheduled. Invalidation prevents new permits and drains an
 * already-admitted call before it returns. */
static int stop_effect_admit(const struct ctos_ssh_native_client *client) {
    if (client == NULL || client->callback_closed) { errno = ECANCELED; return -1; }
    /* Native-only fixture targets have no Java STOP owner. Production JNI
     * requires a registered admission method before opening its callback. */
    if (client->admit_effect == NULL) return 0;
    if (client->effect_context == NULL
        || client->admit_effect(client->effect_context, client->callback_environment) != 0) {
        errno = ECANCELED;
        return -1;
    }
    return 0;
}

int ctos_ssh_native_client_admit_input_copy(const struct ctos_ssh_native_client *client) {
    if (client_owner(client) != 0) return -1;
    if (client->closed || client->failed) { errno = ECANCELED; return -1; }
    return stop_effect_admit(client);
}

static int wire_effect_acquire(struct ctos_ssh_native_client *client) {
    uint64_t now;
    if (client->wire_effect_phase == WIRE_EFFECT_NONE)
        return stop_effect_admit(client) == 0 ? 1 : -1;
    if (client->wire_effect_parent == NULL || client->wire_effect_generation == 0
        || now_millis(&now) != 0 || now >= client->wire_effect_deadline) {
        errno = ETIMEDOUT;
        return -1;
    }
    if (ctos_ssh_effect_acquire(client->wire_effect_generation) != 0) return -1;
    if (now_millis(&now) != 0 || now >= client->wire_effect_deadline) {
        ctos_ssh_effect_release();
        errno = ETIMEDOUT;
        return -1;
    }
    if (stop_effect_admit(client) != 0) {
        ctos_ssh_effect_release();
        errno = ECANCELED;
        return -1;
    }
    return 0;
}

static void wire_effect_reset(struct ctos_ssh_native_client *client) {
    client->wire_effect_parent = NULL;
    client->wire_effect_next_role = 0;
    client->wire_effect_generation = 0;
    client->wire_effect_deadline = 0;
    client->wire_effect_phase = WIRE_EFFECT_NONE;
}

static int wire_effect_pair(struct ctos_ssh_native_client *child,
    struct ctos_ssh_native_client **parent_out) {
    struct ctos_ssh_native_jump_relay *relay = child->upstream_relay;
    struct ctos_ssh_native_client *parent = child->wire_effect_parent;
    if (relay == NULL || parent == NULL || parent->upstream_relay != NULL || relay->parent != parent
        || relay->child != child || parent->jump_relay != relay || parent->wire_effect_parent != parent
        || child->wire_effect_phase == WIRE_EFFECT_NONE
        || child->wire_effect_phase != parent->wire_effect_phase
        || child->wire_effect_generation != parent->wire_effect_generation
        || child->wire_effect_deadline != parent->wire_effect_deadline
        || child->wire_effect_next_role != child->role || parent->wire_effect_next_role != child->role) {
        errno = EACCES;
        return -1;
    }
    *parent_out = parent;
    return 0;
}

/* Caller holds the wire permit; all three queues have a single owner. */
static int wire_effect_outbound_drained(struct ctos_ssh_native_client *child,
    struct ctos_ssh_native_client *parent) {
    struct ctos_ssh_native_jump_relay *relay;
    size_t child_output = 0, parent_output = 0;
    unsigned char pending;
    ssize_t peek;
    relay = child->upstream_relay;
    /* The final child KEX bytes first enter its socketpair, then a relay
     * buffer and the parent's SSH output.  Keep the transport deadline
     * until every outbound stage has drained to the parent's socket. */
    (void)ssh_output_ptr(child->ssh, &child_output);
    (void)ssh_output_ptr(parent->ssh, &parent_output);
    if (child_output != 0 || relay->channel_length != 0 || parent_output != 0) return 1;
    peek = recv(relay->fd, &pending, 1U, MSG_PEEK | MSG_DONTWAIT);
    if (peek > 0 || (peek < 0 && errno == EINTR)) return 1;
    if (peek == 0) { errno = EPIPE; return -1; }
    if (errno != EAGAIN && errno != EWOULDBLOCK) return -1;
    return 0;
}

static int wire_effect_promote_kex(struct ctos_ssh_native_client *child) {
    struct ctos_ssh_native_client *parent;
    uint64_t now;
    int result = -1, error = EACCES;
    if (child->wire_effect_phase == WIRE_EFFECT_NONE) return 0;
    if (child->wire_effect_phase != WIRE_EFFECT_CONNECTING || wire_effect_pair(child, &parent) != 0)
        return -1;
    if (wire_effect_acquire(child) != 0) return -1;
    result = wire_effect_outbound_drained(child, parent);
    if (result != 0) {
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
        if (result > 0) test_pending_kex_drains++;
#endif
        if (result < 0) error = errno;
        goto out;
    }
    if (now_millis(&now) != 0 || now >= child->wire_effect_deadline) {
        error = ETIMEDOUT;
        result = -1;
        goto out;
    }
    parent->wire_effect_deadline = child->deadline;
    child->wire_effect_deadline = child->deadline;
    parent->wire_effect_phase = WIRE_EFFECT_AUTHENTICATING;
    child->wire_effect_phase = WIRE_EFFECT_AUTHENTICATING;
    result = 0;
out:
    ctos_ssh_effect_release();
    if (result < 0) errno = error;
    return result;
}

static int wire_effect_authentication_complete(struct ctos_ssh_native_client *child) {
    struct ctos_ssh_native_client *parent;
    int drained, error;
    if (child->wire_effect_phase == WIRE_EFFECT_NONE) return 0;
    if (child->wire_effect_phase != WIRE_EFFECT_AUTHENTICATING || wire_effect_pair(child, &parent) != 0)
        return -1;
    if (wire_effect_acquire(child) != 0) return -1;
    drained = wire_effect_outbound_drained(child, parent);
    error = drained > 0 ? EPROTO : errno;
    if (drained != 0) {
        /* An early response must not retire the scope while the request is
         * still buffered in this process.  Never reuse its parent output. */
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
        test_early_auth_reply_denials++;
#endif
        parent->failed = 1;
        ctos_ssh_effect_release();
        errno = error;
        return -1;
    }
    wire_effect_reset(parent);
    wire_effect_reset(child);
    ctos_ssh_effect_release();
    return 0;
}

/* This may be called only after every needed registry/Java lock is held or
 * released. The bounded operation following it must not acquire one. */
static int effect_acquire_for_client(const struct ctos_ssh_native_client *client) {
    uint64_t now;
    if (client == NULL || client->jump_effect_generation == 0
        || now_millis(&now) != 0 || now >= client->deadline) {
        errno = ECANCELED;
        return -1;
    }
    if (ctos_ssh_effect_acquire(client->jump_effect_generation) != 0) return -1;
    if (now_millis(&now) != 0 || now >= client->deadline) {
        ctos_ssh_effect_release();
        errno = ETIMEDOUT;
        return -1;
    }
    if (stop_effect_admit(client) != 0) {
        ctos_ssh_effect_release();
        errno = ECANCELED;
        return -1;
    }
    return 0;
}

static int effect_callback_frame(void *environment, const void *receiver,
    ctos_ssh_callback_receiver_validator validator) {
    struct native_callback_dispatch *frame = current_native_callback;
    const struct ctos_ssh_native_client *client;
    if (frame == NULL || frame->previous != NULL || frame->kind != NATIVE_CALLBACK_CREDENTIAL
        || (client = frame->client) == NULL || client->upstream_relay == NULL
        || client->callback_environment != environment || client->automatic_selection
        || client->authentication_selection_kind != CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD
        || client->authentication_stage != 1U || !frame->effect_proved
        || ctos_ssh_callback_current_receiver(client->callback, environment, receiver, validator) != 0) {
        errno = EACCES;
        return -1;
    }
    return 0;
}

int ctos_ssh_native_client_effect_java_copy_begin(void *environment, const void *receiver,
    ctos_ssh_callback_receiver_validator validator) {
    struct native_callback_dispatch *frame = current_native_callback;
    if (effect_callback_frame(environment, receiver, validator) != 0
        || frame->java_copy_state != 0 || frame->jni_copy_state != 0) return -1;
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
    if (test_before_java_secret_copy != NULL) {
        void (*hook)(void *) = test_before_java_secret_copy;
        void *context = test_before_java_secret_copy_context;
        test_before_java_secret_copy = NULL;
        test_before_java_secret_copy_context = NULL;
        hook(context);
    }
#endif
    if (effect_acquire_for_client(frame->client) != 0) return -1;
    frame->java_copy_state = 1;
    return 0;
}

void ctos_ssh_native_client_effect_java_copy_end(void *environment, const void *receiver,
    ctos_ssh_callback_receiver_validator validator) {
    struct native_callback_dispatch *frame = current_native_callback;
    if (frame == NULL || frame->java_copy_state != 1) return;
    if (effect_callback_frame(environment, receiver, validator) != 0) {
        /* A foreign receiver cannot release the real receiver's permit. */
        frame->java_copy_invalid = 1;
        return;
    }
    frame->java_copy_state = 2;
    ctos_ssh_effect_release();
}

int ctos_ssh_native_client_effect_java_callback_returned(void) {
    struct native_callback_dispatch *frame = current_native_callback;
    if (frame == NULL || frame->kind != NATIVE_CALLBACK_CREDENTIAL) return 0;
    if (frame->java_copy_state == 1) {
        /* Java has returned completely: no receiver can still be copying.
         * Release before finish_callback attempts any invalidation. */
        frame->java_copy_state = -1;
        ctos_ssh_effect_release();
        errno = ECANCELED;
        return -1;
    }
    if (frame->java_copy_invalid) { errno = ECANCELED; return -1; }
    return 0;
}

int ctos_ssh_native_client_effect_jni_copy_begin(void *environment, const void *receiver,
    ctos_ssh_callback_receiver_validator validator) {
    struct native_callback_dispatch *frame = current_native_callback;
    if (frame == NULL || frame->client == NULL) return 1;
    if (frame->previous != NULL || frame->kind != NATIVE_CALLBACK_CREDENTIAL
        || frame->client->callback_environment != environment
        || ctos_ssh_callback_current_receiver(frame->client->callback, environment, receiver, validator) != 0) {
        errno = EACCES;
        return -1;
    }
    if (frame->client->upstream_relay == NULL || frame->client->jump_effect_generation == 0)
        return stop_effect_admit(frame->client) == 0 ? 1 : -1;
    if (effect_callback_frame(environment, receiver, validator) != 0
        || frame->java_copy_state != 2 || frame->jni_copy_state != 0) return -1;
    if (effect_acquire_for_client(frame->client) != 0) return -1;
    frame->jni_copy_state = 1;
    return 0;
}

void ctos_ssh_native_client_effect_jni_copy_end(void) {
    struct native_callback_dispatch *frame = current_native_callback;
    if (frame != NULL && frame->jni_copy_state == 1) {
        frame->jni_copy_state = 2;
        ctos_ssh_effect_release();
    }
}

static void wipe_bytes(void *memory, size_t length) {
    volatile unsigned char *bytes = memory;

    if (bytes == NULL) return;
    while (length-- != 0) *bytes++ = 0;
}

struct selection_cursor {
    const unsigned char *position;
    size_t remaining;
};

static int selection_u32(struct selection_cursor *cursor, uint32_t *value) {
    const unsigned char *p = cursor->position;

    if (cursor->remaining < 4U) return -1;
    *value = ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16)
        | ((uint32_t)p[2] << 8) | (uint32_t)p[3];
    cursor->position += 4U;
    cursor->remaining -= 4U;
    return 0;
}

static int selection_field(struct selection_cursor *cursor, struct ctos_ssh_slice *field) {
    uint32_t length;

    if (selection_u32(cursor, &length) != 0 || (size_t)length > cursor->remaining)
        return -1;
    field->data = cursor->position;
    field->length = length;
    cursor->position += length;
    cursor->remaining -= length;
    return 0;
}

/* Scalar UTF-8, including C1 ISO controls rejected by the managed encoder. */
static int selection_text(struct ctos_ssh_slice field, size_t maximum) {
    size_t index = 0;

    if (field.length == 0 || field.length > maximum) return -1;
    while (index < field.length) {
        unsigned int ch = field.data[index++];
        unsigned int extra;
        unsigned int scalar;

        if (ch < 0x80U) {
            if (ch < 0x20U || ch == 0x7fU) return -1;
            continue;
        }
        if (ch >= 0xc2U && ch <= 0xdfU) { extra = 1; scalar = ch & 0x1fU; }
        else if (ch >= 0xe0U && ch <= 0xefU) { extra = 2; scalar = ch & 0x0fU; }
        else if (ch >= 0xf0U && ch <= 0xf4U) { extra = 3; scalar = ch & 7U; }
        else return -1;
        if (extra > field.length - index) return -1;
        for (unsigned int part = 0; part < extra; part++) {
            ch = field.data[index++];
            if ((ch & 0xc0U) != 0x80U) return -1;
            scalar = (scalar << 6) | (ch & 0x3fU);
        }
        if ((extra == 1U && scalar < 0x80U) || (extra == 2U && scalar < 0x800U)
            || (extra == 3U && scalar < 0x10000U) || scalar > 0x10ffffU
            || (scalar >= 0xd800U && scalar <= 0xdfffU)
            || (scalar >= 0x7fU && scalar <= 0x9fU)) return -1;
    }
    return 0;
}

static int selection_identifier(struct ctos_ssh_slice field) {
    if (field.length == 0 || field.length > 128U) return -1;
    for (size_t index = 0; index < field.length; index++) {
        unsigned char ch = field.data[index];
        if (!((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
            || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-')) return -1;
    }
    return 0;
}

static int copy_authentication_selection(struct ctos_ssh_native_client *client,
    const struct ctos_ssh_native_client_config *config,
    const struct ctos_ssh_role_snapshot *snapshot) {
    const unsigned char *wire = config->authentication_selection_wire;
    size_t length = config->authentication_selection_wire_length;
    struct selection_cursor cursor;
    struct ctos_ssh_slice fields[5];
    uint32_t magic, version_flags, token_high, token_low, hop, kind, continuation = 0, automatic_methods = 0;
    uint64_t token;

    if (wire == NULL && length == 0) return 0;
    if (wire == NULL || length < 108U
        || length > CTOS_SSH_NATIVE_CLIENT_MAX_SELECTION_WIRE_BYTES) goto invalid;
    cursor = (struct selection_cursor){wire, length};
    if (selection_u32(&cursor, &magic) != 0 || magic != 0x43534153U
        || selection_u32(&cursor, &version_flags) != 0
        || (version_flags != 0x00010000U && version_flags != 0x00020000U
            && version_flags != 0x00030000U && version_flags != 0x00040000U)
        || selection_u32(&cursor, &token_high) != 0
        || selection_u32(&cursor, &token_low) != 0
        || selection_u32(&cursor, &hop) != 0) goto invalid;
    token = ((uint64_t)token_high << 32) | token_low;
    if (token == 0 || token > INT64_MAX) goto invalid;
    if (token != snapshot->token || hop != snapshot->hop
        || memcmp(cursor.position, snapshot->role_nonce, 32U) != 0
        || memcmp(cursor.position + 32U, snapshot->connection_binding, 32U) != 0) {
        errno = EACCES;
        return -1;
    }
    cursor.position += 64U;
    cursor.remaining -= 64U;
    if (selection_u32(&cursor, &kind) != 0 || (kind != 1U && kind != 3U && kind != 4U)) goto invalid;
    for (size_t index = 0; index < 5U; index++) {
        if (selection_field(&cursor, &fields[index]) != 0) goto invalid;
    }
    if (version_flags == 0x00030000U
        && (selection_u32(&cursor, &continuation) != 0 || continuation != 3U || kind == 3U)) goto invalid;
    if (version_flags == 0x00040000U
        && (selection_u32(&cursor, &automatic_methods) != 0 || (automatic_methods & ~7U) != 0
            || kind == 1U || (kind == 3U && (automatic_methods & CTOS_SSH_DISCOVERY_PUBLICKEY) != 0))) goto invalid;
    if (cursor.remaining != 0 || selection_text(fields[0], 255U) != 0) goto invalid;
    if (kind == 1U) {
        if (version_flags == 0x00020000U || selection_identifier(fields[1]) != 0 || fields[2].length != 0
            || fields[3].length != 0 || fields[4].length != 0) goto invalid;
    } else if (kind == 3U) {
        if ((version_flags != 0x00010000U && version_flags != 0x00040000U)
            || fields[1].length != 0 || fields[2].length != 0
            || fields[3].length != 0 || fields[4].length != 0) goto invalid;
    } else if ((version_flags == 0x00010000U ? fields[1].length != 0
            : version_flags == 0x00020000U || fields[1].length != 0 ? selection_identifier(fields[1]) != 0 : 0)
        || selection_text(fields[2], 512U) != 0
        || ctos_ssh_view_path(fields[2].data, fields[2].length, 0) != 0
        || selection_text(fields[3], 512U) != 0 || fields[4].length == 0
        || fields[4].length > CTOS_SSH_VIEW_FILE_BYTES) goto invalid;
    client->authentication_selection_wire = malloc(length);
    if (client->authentication_selection_wire == NULL) { errno = ENOMEM; return -1; }
    client->authentication_selection_wire_length = length;
    memcpy(client->authentication_selection_wire, wire, length);
    if (ssh_digest_memory(SSH_DIGEST_SHA256, client->authentication_selection_wire,
            length, client->authentication_selection_receipt, 32U) != 0) {
        errno = EIO;
        return -1;
    }
    client->authentication_selection_kind = (int)kind;
    client->authentication_selection_version = version_flags >> 16;
    client->active_authentication_kind = (int)kind;
    client->authentication_stage = 1U;
    client->keyboard_continuation = continuation == 3U;
    client->automatic_selection = version_flags == 0x00040000U;
    client->automatic_methods = automatic_methods;
    client->authentication_key_bundle = kind == 4U && fields[1].length != 0;
    client->authentication_username_offset = (size_t)(fields[0].data - wire);
    client->authentication_username_length = fields[0].length;
    client->authentication_public_offset = (size_t)(fields[4].data - wire);
    client->authentication_public_length = fields[4].length;
    return 0;
invalid:
    errno = EINVAL;
    return -1;
}

static void clear_authentication_selection(struct ctos_ssh_native_client *client) {
    wipe_bytes(client->authentication_selection_wire, client->authentication_selection_wire_length);
    free(client->authentication_selection_wire);
    client->authentication_selection_wire = NULL;
    client->authentication_selection_wire_length = 0;
    wipe_bytes(client->authentication_selection_receipt, sizeof(client->authentication_selection_receipt));
    client->authentication_selection_kind = 0;
    client->authentication_selection_version = 0;
    client->active_authentication_kind = 0;
    client->authentication_stage = 0;
    client->keyboard_continuation = 0;
    client->automatic_selection = 0;
    client->automatic_methods = 0;
    client->automatic_attempted_methods = 0;
    client->automatic_continuation_dispatch = 0;
    client->authentication_key_bundle = 0;
    wipe_bytes(client->continuation_methods, sizeof(client->continuation_methods));
    client->authentication_username_offset = 0;
    client->authentication_username_length = 0;
    client->authentication_public_offset = 0;
    client->authentication_public_length = 0;
}

static int bounded_text(const char *value, size_t maximum, size_t *length_out) {
    size_t length;

    if (value == NULL) {
        errno = EINVAL;
        return -1;
    }
    length = strnlen(value, maximum + 1U);
    if (length == 0 || length > maximum) {
        errno = EINVAL;
        return -1;
    }
    for (size_t index = 0; index < length; index++) {
        unsigned char character = (unsigned char)value[index];
        if (character < 0x21U || character > 0x7eU) {
            errno = EINVAL;
            return -1;
        }
    }
    if (length_out != NULL) *length_out = length;
    return 0;
}

static int nonzero32(const unsigned char value[32]) {
    unsigned char combined = 0;

    if (value == NULL) return 0;
    for (size_t index = 0; index < 32; index++) combined |= value[index];
    return combined != 0;
}

static int copy_config_text(char *destination, size_t capacity,
    const char *source, size_t maximum) {
    size_t length;

    if (bounded_text(source, maximum, &length) != 0 || length >= capacity) {
        errno = EINVAL;
        return -1;
    }
    memcpy(destination, source, length + 1U);
    return 0;
}

static int role_is_current(const struct ctos_ssh_native_client_config *config,
    struct ctos_ssh_role_snapshot *snapshot) {
    uint64_t current;
    size_t host_length;

    memset(snapshot, 0, sizeof(*snapshot));
    if (config == NULL || config->host == NULL
        || bounded_text(config->host, CTOS_SSH_ROLE_HOST_BYTES, &host_length) != 0
        || config->role == 0 || config->deadline == 0
        || config->deadline > INT64_MAX) {
        errno = EACCES;
        return -1;
    }
    if (now_millis(&current) != 0) return -1;
    if (config->deadline <= current) {
        errno = EACCES;
        return -1;
    }
    if (ctos_ssh_role_bootstrap_snapshot(config->role, snapshot) != 0) {
        /* A missing or expired role is an admission denial. Keep operational
         * failures distinct from the client's role-policy error contract. */
        if (errno == ENOENT || errno == ETIMEDOUT) errno = EACCES;
        return -1;
    }
    if (snapshot->token != config->role
        || snapshot->owner_pid != getpid() || snapshot->owner_uid != getuid()
        || config->owner_uid != getuid()
        || snapshot->deadline != config->deadline
        || strcmp(snapshot->host, config->host) != 0) {
        errno = EACCES;
        return -1;
    }
    (void)host_length;
    return 0;
}

static int same_slice_text(struct ctos_ssh_slice slice, const char *value,
    size_t maximum) {
    size_t length;

    if (bounded_text(value, maximum, &length) != 0
        || slice.data == NULL
        || slice.length != length
        || memcmp(slice.data, value, length) != 0) {
        errno = EACCES;
        return -1;
    }
    return 0;
}

static int view_slices_are_safe(const struct ctos_ssh_view *view) {
    if (view == NULL || view->count == 0 || view->count > CTOS_SSH_VIEW_FILES
        || view->uuid.data == NULL || view->uuid.length != 16U
        || view->binding.data == NULL || view->binding.length != 32U
        || view->request.data == NULL || view->request.length == 0
        || view->request.length > 128U || view->connection.data == NULL
        || view->connection.length == 0 || view->connection.length > 512U
        || view->root.data == NULL || view->root.length == 0
        || view->root.length > 512U) {
        errno = EACCES;
        return -1;
    }
    for (unsigned int index = 0; index < view->count; index++) {
        const struct ctos_ssh_file *file = &view->files[index];
        if (file->id.data == NULL || file->id.length == 0
            || file->id.length > 128U || file->path.data == NULL
            || file->path.length == 0 || file->path.length > 512U
            || file->identity.data == NULL || file->identity.length == 0
            || file->identity.length > 512U || file->body.length > CTOS_SSH_VIEW_FILE_BYTES
            || (file->body.length != 0 && file->body.data == NULL)
            || file->ref_count > CTOS_SSH_VIEW_FILES) {
            errno = EACCES;
            return -1;
        }
        for (unsigned int ref = 0; ref < file->ref_count; ref++) {
            if (file->refs[ref].data == NULL || file->refs[ref].length == 0
                || file->refs[ref].length > 512U) {
                errno = EACCES;
                return -1;
            }
        }
    }
    return 0;
}

static int view_borrow_is_live(const struct ctos_ssh_view_borrow *borrow) {
    if (borrow == NULL) {
        errno = EACCES;
        return -1;
    }
    if (ctos_ssh_view_borrow_live(borrow) != 0) {
        if (errno == 0) errno = EACCES;
        return -1;
    }
    return 0;
}

static int view_is_current(const struct ctos_ssh_native_client_config *config,
    const struct ctos_ssh_view *view,
    const struct ctos_ssh_role_snapshot *snapshot, size_t path_length) {
    const struct ctos_ssh_file *root;
    const struct ctos_ssh_file *known_hosts;
    int references_known_hosts = 0;

    if (config == NULL || view == NULL || snapshot == NULL
        || view_slices_are_safe(view) != 0
        || ctos_ssh_view_live(view) != 0
        || view->deadline < snapshot->deadline
        || same_slice_text(view->connection,
            snapshot->connection_id, CTOS_SSH_ROLE_CONNECTION_ID_BYTES) != 0
        || ctos_ssh_view_path(view->root.data, view->root.length, 0) != 0) {
        errno = EACCES;
        return -1;
    }
    /* The validated, borrowed view binds its actual selected CONFIG root. Legacy
     * password, manual-key and imported-config roots need no path aliases. */
    root = ctos_ssh_view_lookup(view, view->root.data,
        view->root.length, CTOS_SSH_CONFIG);
    known_hosts = ctos_ssh_view_lookup(view,
        (const unsigned char *)config->known_hosts_path, path_length,
        CTOS_SSH_KNOWN_HOSTS);
    if (root == NULL || known_hosts == NULL
        || known_hosts->body.length > CTOS_SSH_CALLBACK_MAX_FILE_BYTES
        || known_hosts->declared_bytes != known_hosts->body.length) {
        errno = EACCES;
        return -1;
    }
    for (unsigned int index = 0; index < root->ref_count; index++) {
        if (root->refs[index].data != NULL
            && root->refs[index].length == path_length
            && memcmp(root->refs[index].data, config->known_hosts_path,
                path_length) == 0) {
            references_known_hosts = 1;
            break;
        }
    }
    if (!references_known_hosts) {
        errno = EACCES;
        return -1;
    }
    return 0;
}

static int same_view_slice(struct ctos_ssh_slice left, struct ctos_ssh_slice right) {
    return left.length == right.length && (left.length == 0
        || (left.data != NULL && right.data != NULL
            && memcmp(left.data, right.data, left.length) == 0));
}

/* A refreshed trust source must not silently re-plan the already frozen
 * connection. Only known_hosts contents may differ from the original loan. */
static int compatible_hostfile_view(const struct ctos_ssh_view *original,
    const struct ctos_ssh_view *fresh, uint64_t deadline) {
    if (original == NULL || fresh == NULL || original == fresh
        || view_slices_are_safe(original) != 0 || view_slices_are_safe(fresh) != 0
        || ctos_ssh_view_live(original) != 0 || ctos_ssh_view_live(fresh) != 0
        || fresh->deadline != deadline || original->deadline < deadline
        || fresh->generation < original->generation || fresh->epoch < original->epoch
        || !same_view_slice(original->uuid, fresh->uuid)
        || !same_view_slice(original->connection, fresh->connection)
        || !same_view_slice(original->root, fresh->root)
        || (fresh->epoch == original->epoch
            && (fresh->generation != original->generation
                || !same_view_slice(original->binding, fresh->binding)))
        || (fresh->epoch > original->epoch
            && same_view_slice(original->binding, fresh->binding))
        || fresh->count != original->count) {
        errno = EACCES;
        return -1;
    }
    for (unsigned int index = 0; index < original->count; index++) {
        const struct ctos_ssh_file *old = &original->files[index], *next = NULL;
        for (unsigned int candidate = 0; candidate < fresh->count; candidate++) {
            if (same_view_slice(old->path, fresh->files[candidate].path)) {
                if (next != NULL) { errno = EACCES; return -1; }
                next = &fresh->files[candidate];
            }
        }
        if (next == NULL || old->type != next->type
            || !same_view_slice(old->id, next->id)
            || !same_view_slice(old->identity, next->identity)
            || old->ref_count != next->ref_count
            || ((old->type != CTOS_SSH_KNOWN_HOSTS
                || fresh->epoch == original->epoch)
                && (old->declared_bytes != next->declared_bytes
                    || !same_view_slice(old->body, next->body)))) {
            errno = EACCES;
            return -1;
        }
        for (unsigned int reference = 0; reference < old->ref_count; reference++) {
            if (!same_view_slice(old->refs[reference], next->refs[reference])) {
                errno = EACCES;
                return -1;
            }
        }
    }
    if (ctos_ssh_view_live(original) != 0 || ctos_ssh_view_live(fresh) != 0) return -1;
    return 0;
}

static int validate_descriptor_state(int fd, int require_close_on_exec) {
    int descriptor_flags;
    int flags;
    int socket_type;
    socklen_t socket_type_length = sizeof(socket_type);

    if (fd < 0 || (descriptor_flags = fcntl(fd, F_GETFD)) < 0
        || (require_close_on_exec && (descriptor_flags & FD_CLOEXEC) == 0)
        || (flags = fcntl(fd, F_GETFL)) < 0
        || (flags & O_ACCMODE) != O_RDWR || (flags & O_NONBLOCK) == 0
        || getsockopt(fd, SOL_SOCKET, SO_TYPE, &socket_type,
            &socket_type_length) != 0 || socket_type != SOCK_STREAM) {
        errno = EINVAL;
        return -1;
    }
    return 0;
}

static int validate_descriptor(int fd) {
    return validate_descriptor_state(fd, 1);
}

int ctos_ssh_native_client_prepare_tcp_descriptor(int fd) {
    int flags;
    /* The caller still owns this descriptor throughout, including every failure. */
    if (validate_descriptor_state(fd, 0) != 0) return -1;
    if ((flags = fcntl(fd, F_GETFD)) < 0
        || fcntl(fd, F_SETFD, flags | FD_CLOEXEC) != 0) return -1;
    return validate_descriptor(fd);
}

static int fingerprint_matches(struct ctos_ssh_native_client *client,
    const struct sshkey *host_key) {
    unsigned char *actual = NULL;
    size_t length = 0;
    int result;

    if (client == NULL || host_key == NULL
        || sshkey_fingerprint_raw(host_key, SSH_DIGEST_SHA256,
            &actual, &length) != 0 || length != 32) {
        wipe_bytes(actual, length);
        free(actual);
        errno = EACCES;
        return -1;
    }
    result = timingsafe_bcmp(actual, client->expected_host_fingerprint, 32) == 0;
    wipe_bytes(actual, length);
    free(actual);
    if (!result) {
        errno = EACCES;
        return -1;
    }
    return 0;
}

static int host_result_matches_issue(
    const struct ctos_ssh_native_client *client,
    const struct ctos_ssh_hostfile_issue *issue,
    const struct ctos_ssh_host_result *result) {
    struct ctos_ssh_role_snapshot snapshot;
    int expected_cas;
    int matches;

    memset(&snapshot, 0, sizeof(snapshot));
    if (client == NULL || issue == NULL || result == NULL
        || ctos_ssh_role_bootstrap_snapshot(client->role, &snapshot) != 0) {
        wipe_bytes(&snapshot, sizeof(snapshot));
        errno = EACCES;
        return -1;
    }
    expected_cas = issue->classification == CTOS_SSH_CALLBACK_HOST_NEW
        ? CTOS_SSH_CALLBACK_HOST_CAS_COMMITTED
        : CTOS_SSH_CALLBACK_HOST_CAS_NOT_REQUIRED;
    matches = result->role == client->role
        && result->hop == snapshot.hop && result->port == snapshot.port
        && result->classification == issue->classification
        && result->outcome == CTOS_SSH_CALLBACK_HOST_RESULT_APPROVED
        && result->cas_status == expected_cas
        && strcmp(result->connection_id, snapshot.connection_id) == 0
        && strcmp(result->target_identity, snapshot.target_identity) == 0
        && strcmp(result->host, snapshot.host) == 0
        && strcmp(result->host, client->host) == 0
        && strcmp(result->key_type, issue->key_type) == 0
        && strcmp(result->fingerprint, issue->fingerprint) == 0
        && memcmp(result->base_digest, issue->base_digest,
            sizeof(issue->base_digest)) == 0
        && memcmp(result->replacement_digest, issue->replacement_digest,
            sizeof(issue->replacement_digest)) == 0;
    wipe_bytes(&snapshot, sizeof(snapshot));
    if (!matches) {
        errno = EACCES;
        return -1;
    }
    return 0;
}

static int abort_handed_off_candidate(struct ctos_ssh_native_client *client) {
    if (client == NULL || !client->candidate_handed_off) return 0;
    if (ctos_ssh_callback_known_hosts_candidate_abort(
            client->callback, client->callback_environment) != 0) return -1;
    client->candidate_handed_off = 0;
    return 0;
}

/* Capture the exact first KEXINIT sent by this client before NEWKEYS strips
 * extension-signalling names from the reusable proposal. */
static int provisional_capture_ext_signal(struct ctos_ssh_native_client *client) {
    char **proposal = NULL;
    int result = -1;
    if (client->ssh == NULL || client->ssh->kex == NULL
        || (client->ssh->kex->flags & (KEX_INITIAL | KEX_INIT_SENT))
            != (KEX_INITIAL | KEX_INIT_SENT)
        || kex_buf2prop(client->ssh->kex->my, NULL, &proposal) != 0 || proposal == NULL)
        goto out;
    client->provisional_ext_advertised = advertised_algorithm(
        proposal[PROPOSAL_KEX_ALGS], "ext-info-c");
    result = 0;
out:
    kex_prop_free(proposal);
    if (result != 0) errno = EACCES;
    return result;
}

static int offered_host_is_live(struct ctos_ssh_native_client *client) {
    struct ctos_ssh_role_snapshot snapshot = {0};
    uint64_t current;
    int result = -1;
    if (ctos_ssh_callback_check_live(client->callback, client->callback_environment) != 0
        || view_borrow_is_live(client->managed_view_borrow) != 0
        || ctos_ssh_role_bootstrap_snapshot(client->role, &snapshot) != 0) goto out;
    if (!client->in_transport_step || client->closed || client->failed || client->completed
        || client->completion_calls != 0 || client->server_host_public_key != NULL
        || client->ssh == NULL || client->ssh->kex == NULL || client->ssh->kex->done
        || snapshot.token != client->role || snapshot.owner_pid != getpid()
        || snapshot.owner_uid != getuid() || snapshot.deadline != client->deadline
        || strcmp(snapshot.host, client->host) != 0) {
        errno = EACCES;
        goto out;
    }
    if (now_millis(&current) != 0) goto out;
    if (current >= client->deadline) { errno = ETIMEDOUT; goto out; }
    result = 0;
out:
    wipe_bytes(&snapshot, sizeof(snapshot));
    return result;
}

static int verify_host_key(struct sshkey *host_key, struct ssh *ssh) {
    struct ctos_ssh_native_client *client;
    struct native_callback_dispatch dispatch;
    struct ctos_ssh_hostfile_issue issue;
    struct ctos_ssh_host_result result;
    unsigned char *replacement = NULL;
    unsigned char *offered_fingerprint = NULL;
    size_t offered_fingerprint_length = 0;
    struct sshkey *offered_key = NULL;
    FILE *stream = NULL;
    const size_t replacement_capacity = CTOS_SSH_CALLBACK_MAX_FILE_BYTES + 1U;
    int return_code = -1;
    int callback_result;
    int close_result;
    int changed_approved = 0, provisional_approved = 0, provisional_supported = 0, handoff_started = 0;

    memset(&issue, 0, sizeof(issue));
    memset(&result, 0, sizeof(result));
    client = ssh == NULL ? NULL : ssh_get_app_data(ssh);
    if (client == NULL || client->closed || client->failed || client->completed
        || client->managed_view == NULL || client->hostfile_view == NULL
        || view_borrow_is_live(client->managed_view_borrow) != 0 || host_key == NULL
        || (client->host_key_mode == CTOS_SSH_HOST_KEY_PINNED
            && fingerprint_matches(client, host_key) != 0)) {
        errno = EACCES;
        goto out;
    }
    if (upstream_proof(client) != 0) goto out;
    if (client->host_key_mode == CTOS_SSH_HOST_KEY_MANAGED) {
        if (offered_host_is_live(client) != 0) goto out;
        if (sshkey_from_private(host_key, &offered_key) != 0 || offered_key == NULL
            || sshkey_fingerprint_raw(offered_key, SSH_DIGEST_SHA256,
                &offered_fingerprint, &offered_fingerprint_length) != 0
            || offered_fingerprint_length != 32U || !nonzero32(offered_fingerprint)) {
            errno = EACCES;
            goto out;
        }
        if (client->upstream_relay != NULL) {
            provisional_supported = ctos_ssh_callback_supports_provisional_new(
                client->callback, client->callback_environment);
            if (provisional_supported < 0) goto out;
        }
    }
    replacement = malloc(replacement_capacity);
    if (replacement == NULL) {
        errno = ENOMEM;
        goto out;
    }
    memset(replacement, 0, replacement_capacity);
    stream = fmemopen(replacement, replacement_capacity, "w+");
    if (stream == NULL) {
        errno = EIO;
        goto out;
    }
    begin_native_callback(&dispatch, client, NATIVE_CALLBACK_HOST_KEY);
    callback_result = ctos_ssh_hostfile_issue(client->hostfile_view, client->role,
        client->known_hosts_path, client->host, host_key, client->candidate_id,
        stream, replacement, replacement_capacity, provisional_supported ? 0 : client->callback,
        provisional_supported ? NULL : client->callback_environment, &issue);
    end_native_callback(&dispatch);
    close_result = fclose(stream);
    stream = NULL;
    if (issue.handed_off) client->candidate_handed_off = 1;
    if (callback_result != 0) goto out;
    if (upstream_proof(client) != 0) goto out;
    if (close_result != 0) {
        errno = EIO;
        goto out;
    }
    if (provisional_supported && issue.classification == CTOS_SSH_CALLBACK_HOST_NEW) {
        if (issue.handed_off || issue.replacement_body != replacement
            || issue.replacement_body_length == 0) { errno = EACCES; goto out; }
        begin_native_callback(&dispatch, client, NATIVE_CALLBACK_HOST_KEY);
        callback_result = ctos_ssh_callback_provisional_new_candidate(client->callback,
            client->callback_environment, issue.candidate_id,
            issue.key_type, issue.fingerprint, issue.base_digest, issue.replacement_digest,
            replacement, issue.replacement_body_length, &handoff_started);
        end_native_callback(&dispatch);
        if (handoff_started) client->candidate_handed_off = 1;
        if (callback_result != 0) goto out;
        if (upstream_proof(client) != 0 || provisional_parent_open(client) != 0) goto out;
        begin_native_callback(&dispatch, client, NATIVE_CALLBACK_HOST_KEY);
        callback_result = ctos_ssh_callback_provisional_new_review(client->callback,
            client->callback_environment,
            issue.key_type, issue.fingerprint, issue.base_digest, issue.replacement_digest);
        end_native_callback(&dispatch);
        if (callback_result != 0 || upstream_proof(client) != 0
            || provisional_parent_open(client) != 0) goto out;
        provisional_approved = 1;
    }
    if (issue.classification == CTOS_SSH_CALLBACK_HOST_CHANGED
        && client->host_key_mode == CTOS_SSH_HOST_KEY_MANAGED) {
        int supports_changed = ctos_ssh_callback_supports_changed(client->callback,
            client->callback_environment);
        if (supports_changed < 0) goto out;
        if (supports_changed == 0) goto changed_not_approved;
        /* A fresh stream is required because the original classifier's stream
         * has already been closed and owns no CHANGED candidate. */
        stream = fmemopen(replacement, replacement_capacity, "w+");
        if (stream == NULL) { errno = EIO; goto out; }
        begin_native_callback(&dispatch, client, NATIVE_CALLBACK_HOST_KEY);
        callback_result = ctos_ssh_hostfile_prepare_changed(client->hostfile_view,
            client->role, client->known_hosts_path, client->host, host_key,
            client->candidate_id, stream, replacement, replacement_capacity,
            client->callback, client->callback_environment, &issue);
        end_native_callback(&dispatch);
        close_result = fclose(stream);
        stream = NULL;
        if (issue.handed_off) client->candidate_handed_off = 1;
        if (callback_result < 0 || close_result != 0) goto out;
        if (upstream_proof(client) != 0) goto out;
        if (callback_result == 0) {
            begin_native_callback(&dispatch, client, NATIVE_CALLBACK_HOST_KEY);
            callback_result = ctos_ssh_callback_changed_host_review(client->callback,
                    client->callback_environment, issue.key_type, issue.fingerprint,
                    issue.base_digest, issue.replacement_digest);
            end_native_callback(&dispatch);
            if (callback_result != 0) goto out;
            if (upstream_proof(client) != 0) goto out;
            changed_approved = 1;
        } else {
            client->host_key_rejection = CTOS_SSH_HOST_REJECTION_CHANGED_BLOCKED;
            errno = EACCES;
            goto out;
        }
    }
changed_not_approved:
    if (!changed_approved && issue.classification != CTOS_SSH_CALLBACK_HOST_KNOWN
        && issue.classification != CTOS_SSH_CALLBACK_HOST_NEW) {
        if (issue.classification == CTOS_SSH_CALLBACK_HOST_CHANGED)
            client->host_key_rejection = CTOS_SSH_HOST_REJECTION_CHANGED;
        else if (issue.classification == CTOS_SSH_CALLBACK_HOST_REVOKED)
            client->host_key_rejection = CTOS_SSH_HOST_REJECTION_REVOKED;
        else if (issue.classification == CTOS_SSH_CALLBACK_HOST_CERTIFICATE)
            client->host_key_rejection = CTOS_SSH_HOST_REJECTION_CERTIFICATE;
        errno = EACCES;
        goto out;
    }
    if (issue.classification == CTOS_SSH_CALLBACK_HOST_NEW
        && !issue.handed_off && !provisional_approved) {
        errno = EACCES;
        goto out;
    }
    if (!changed_approved && !provisional_approved) {
        begin_native_callback(&dispatch, client, NATIVE_CALLBACK_HOST_KEY);
        callback_result = ctos_ssh_callback_host_evidence(client->callback,
            client->callback_environment, issue.classification, issue.key_type,
            issue.fingerprint, issue.base_digest, issue.replacement_digest,
            &result);
        end_native_callback(&dispatch);
        if (callback_result != 0
            || host_result_matches_issue(client, &issue, &result) != 0) goto out;
        if (upstream_proof(client) != 0) goto out;
    }
    if (client->host_key_mode == CTOS_SSH_HOST_KEY_MANAGED) {
        if (offered_host_is_live(client) != 0
            || ctos_ssh_kex_pin_offered_host(client->ssh, offered_key) != 0) {
            if (errno == 0) errno = EACCES;
            goto out;
        }
        client->server_host_public_key = offered_key;
        offered_key = NULL;
        memcpy(client->expected_host_fingerprint, offered_fingerprint, 32U);
    }
    if (provisional_approved) {
        if (provisional_capture_ext_signal(client) != 0) goto out;
        client->provisional_new_state = 1;
    }
    return_code = 0;
out:
    if (stream != NULL) {
        if (fclose(stream) != 0 && return_code == 0) {
            return_code = -1;
            errno = EIO;
        }
        stream = NULL;
    }
    if (return_code != 0 && client != NULL && client->candidate_handed_off) {
        int saved_errno = errno == 0 ? EIO : errno;
        (void)abort_handed_off_candidate(client);
        errno = saved_errno;
    }
    if (return_code != 0 && client != NULL) client->failed = 1;
    wipe_bytes(&result, sizeof(result));
    wipe_bytes(&issue, sizeof(issue));
    sshkey_free(offered_key);
    wipe_bytes(offered_fingerprint, offered_fingerprint_length);
    free(offered_fingerprint);
    if (replacement != NULL) {
        wipe_bytes(replacement, replacement_capacity);
        free(replacement);
    }
    return return_code;
}

static int completion_digest(struct ctos_ssh_native_client *client) {
    struct ctos_ssh_role_snapshot snapshot;
    struct native_callback_dispatch dispatch;
    unsigned char digest[32];
    uint64_t cycle;
    int result = -1;
    int callback_result;

    memset(&snapshot, 0, sizeof(snapshot));
    memset(digest, 0, sizeof(digest));
    if (client == NULL || client->ssh == NULL || client->ssh->kex == NULL
        || !client->ssh->kex->done || client->completion_calls != 0U
        || ssh_digest_buffer(SSH_DIGEST_SHA256, client->ssh->kex->session_id,
            digest, sizeof(digest)) != 0 || !nonzero32(digest)
        || ctos_ssh_role_bootstrap_snapshot(client->role, &snapshot) != 0
        || (cycle = ctos_ssh_kex_ready(client->ssh, snapshot.hop,
            snapshot.role_nonce, snapshot.connection_binding)) == 0) {
        errno = EIO;
        goto out;
    }
    if (upstream_proof(client) != 0
        || (client->provisional_new_state == 1 && provisional_parent_open(client) != 0)) goto out;
    begin_native_callback(&dispatch, client, NATIVE_CALLBACK_KEX);
    callback_result = client->provisional_new_state == 1
        ? ctos_ssh_callback_provisional_new_kex(client->callback, client->callback_environment, cycle, digest)
        : ctos_ssh_callback_kex_complete(client->callback, client->callback_environment, cycle, digest);
    end_native_callback(&dispatch);
    if (callback_result != 0) {
        errno = EIO;
        goto out;
    }
    if (upstream_proof(client) != 0
        || (client->provisional_new_state == 1 && provisional_parent_open(client) != 0)) goto out;
    if (client->provisional_new_state == 1) {
        client->provisional_new_state = 2;
        result = 0;
        goto out;
    }
    client->completion_calls++;
    if (client->completion_calls != 1U) {
        errno = EALREADY;
        goto out;
    }
    client->completed = 1;
    client->completion_cycle = cycle;
    memcpy(client->completion_session_digest, digest, sizeof(client->completion_session_digest));
    result = 0;
out:
    wipe_bytes(&snapshot, sizeof(snapshot));
    wipe_bytes(digest, sizeof(digest));
    return result;
}

static void clear_keyboard_challenge(struct ctos_ssh_native_client *client) {
    wipe_bytes(client->keyboard_challenge, client->keyboard_challenge_length);
    free(client->keyboard_challenge);
    client->keyboard_challenge = NULL;
    client->keyboard_challenge_length = 0;
    client->keyboard_prompts = 0;
}

static int terminal_error(struct ctos_ssh_native_client *client, int error) {
    if (client != NULL) {
        if (!client->completed && client->candidate_handed_off)
            (void)abort_handed_off_candidate(client);
        client->failed = 1;
        clear_keyboard_challenge(client);
        wipe_bytes(client->continuation_methods, sizeof(client->continuation_methods));
        if ((client->jump_mode || !client->shell_started) && client->shell.buffered_bytes != 0) {
            wipe_bytes(client->shell_buffer, sizeof(client->shell_buffer));
            client->shell.buffered_bytes = 0;
        }
    }
    errno = error;
    return -1;
}

/* Ancestor proof also permits EOF while accepted output drains. New auth
 * effects require every observed forwarding direction still to be open. This
 * is an owner-thread observation, not a permit ordered with cancellation. */
static int auth_effect_upstream_open(struct ctos_ssh_native_client *client) {
    const struct ctos_ssh_native_client *child = client;
    unsigned int hops = 0;
    int error;

    if (client->upstream_relay == NULL) return 0;
    if (upstream_proof(client) != 0) {
        error = errno == 0 ? EACCES : errno;
        return error == EPERM ? -1 : terminal_error(client, error);
    }
    while (child->upstream_relay != NULL) {
        const struct ctos_ssh_native_jump_relay *relay = child->upstream_relay;
        const struct ctos_ssh_native_client *parent = relay->parent;
        if (++hops >= CTOS_SSH_ROLE_MAX || parent == NULL || relay->closed || relay->failed
            || relay->child != child || parent->jump_relay != relay || !parent->jump_mode
            || parent->jump_next_role.token != child->role) return terminal_error(client, EACCES);
        if (parent->shell.phase != CTOS_SSH_SHELL_RUNNING || parent->shell.remote_eof
            || parent->shell.local_eof || parent->remote_close || parent->local_close
            || parent->shell_transport_eof || relay->peer_eof || relay->channel_eof_sent
            || relay->peer_read_shutdown || relay->peer_write_shutdown)
            return terminal_error(client, EPIPE);
        child = parent;
    }
    return 0;
}

/* Authentication authority is deliberately retired at promotion. A later
 * auth API probe must fail without turning a valid transport into a failed
 * session. Check the captured owner even on this non-mutating path. */
static int authentication_callback_live(struct ctos_ssh_native_client *client) {
    int error;
    if (client->established) {
        if (client_owner(client) != 0) return -1;
        errno = EACCES; return -1;
    }
    if (ctos_ssh_callback_check_live(client->callback, client->callback_environment) == 0)
        return 0;
    error = errno;
    return error == EPERM ? -1 : terminal_error(client, error);
}

static int service_is_live(struct ctos_ssh_native_client *client, int cancelled) {
    struct ctos_ssh_role_snapshot snapshot = {0};
    uint64_t current;
    uint64_t cycle;
    int error;

    /* Check ownership first: an alien caller cannot poison the owner's
     * session even when its view is revoked or it requests cancellation. */
    if (ctos_ssh_callback_check_live(client->callback,
            client->callback_environment) != 0) {
        error = errno;
        return error == EPERM ? -1 : terminal_error(client, error);
    }
    if (upstream_proof(client) != 0) {
        error = errno;
        return error == EPERM ? -1 : terminal_error(client, error);
    }
    if (client->upstream_relay != NULL && client->completed
        && child_publication_live(client) != 0) {
        error = errno;
        return error == EPERM ? -1 : terminal_error(client, error);
    }
    if (view_borrow_is_live(client->managed_view_borrow) != 0)
        return terminal_error(client, errno == 0 ? EACCES : errno);
    if (cancelled) return terminal_error(client, ECANCELED);
    if (now_millis(&current) != 0) return terminal_error(client, errno);
    if (current >= client->deadline) return terminal_error(client, ETIMEDOUT);
    if (!client->completed) return 0;
    if (ctos_ssh_role_bootstrap_snapshot(client->role, &snapshot) != 0) {
        error = errno;
        wipe_bytes(&snapshot, sizeof(snapshot));
        return terminal_error(client, error);
    }
    cycle = ctos_ssh_kex_ready(client->ssh, snapshot.hop,
        snapshot.role_nonce, snapshot.connection_binding);
    wipe_bytes(&snapshot, sizeof(snapshot));
    if (client->completion_calls != 1U || client->completion_cycle == 0
        || cycle != client->completion_cycle)
        return terminal_error(client, EACCES);
    return 0;
}

static int discovery_allows(const struct ctos_ssh_native_client *client, int kind) {
    unsigned int method = kind == CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD ? CTOS_SSH_DISCOVERY_PASSWORD
        : kind == CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY ? CTOS_SSH_DISCOVERY_PUBLICKEY
        : kind == CTOS_SSH_CALLBACK_CREDENTIAL_KEYBOARD_INTERACTIVE ? CTOS_SSH_DISCOVERY_KEYBOARD_INTERACTIVE : 0;
    if (client->automatic_selection && (method == 0 || (client->automatic_methods & method) == 0)) {
        errno = EACCES; return -1;
    }
    if (client->automatic_selection && client->automatic_continuation_dispatch
        && kind == CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY) return 0;
    if (client->discovery_result == CTOS_SSH_DISCOVERY_NOT_STARTED) {
        if (!client->automatic_selection) return 0;
        errno = EACCES; return -1;
    }
    if ((client->discovery_result == CTOS_SSH_DISCOVERY_METHODS
            || client->discovery_result == CTOS_SSH_DISCOVERY_PARTIAL)
        && method != 0 && (client->discovery_methods & method) != 0) return 0;
    errno = EACCES;
    return -1;
}

static int selected_credential_is_live(struct ctos_ssh_native_client *client) {
    unsigned char digest[32] = {0};
    int result = -1;

    if (service_is_live(client, 0) != 0 || auth_effect_upstream_open(client) != 0) return -1;
    if (client->failed || !client->completed || client->completion_calls != 1U
        || client->completion_cycle == 0 || client->completion_cycle > INT64_MAX
        || client->service_phase != USERAUTH_SERVICE_READY
        || client->authentication_selection_wire == NULL
        || (client->authentication_stage != 1U
            && !(client->automatic_selection && client->automatic_continuation_dispatch
                && client->authentication_stage <= 4U
                && client->active_authentication_kind == CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY))
        || (client->authentication_selection_kind != CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD
            && client->authentication_selection_kind != CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY)
        || client->ssh == NULL || client->ssh->kex == NULL
        || !client->ssh->kex->done || client->ssh->kex->session_id == NULL
        || !nonzero32(client->completion_session_digest)) {
        return terminal_error(client, EACCES);
    }
    if (ssh_digest_buffer(SSH_DIGEST_SHA256, client->ssh->kex->session_id,
            digest, sizeof(digest)) != 0) {
        result = terminal_error(client, EIO);
    } else if (memcmp(digest, client->completion_session_digest, sizeof(digest)) != 0) {
        result = terminal_error(client, EACCES);
    } else {
        result = 0;
    }
    wipe_bytes(digest, sizeof(digest));
    return result;
}

int ctos_ssh_native_client_selected_credential_bytes(struct ctos_ssh_native_client *client,
    unsigned char *output, size_t capacity, size_t *length) {
    struct native_callback_dispatch dispatch;
    unsigned char *scratch;
    size_t returned_length = 0;
    int error, callback_result;

    if (length != NULL) *length = 0;
    if (output != NULL) wipe_bytes(output, capacity > CTOS_SSH_CALLBACK_MAX_CREDENTIAL_BYTES
        ? CTOS_SSH_CALLBACK_MAX_CREDENTIAL_BYTES : capacity);
    if (client == NULL || output == NULL || length == NULL || capacity == 0) {
        errno = EINVAL;
        return -1;
    }
    if (capacity > CTOS_SSH_CALLBACK_MAX_CREDENTIAL_BYTES) { errno = EOVERFLOW; return -1; }
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed || client->selected_credential_attempted) { errno = EALREADY; return -1; }
    /* Alien callers cannot consume the one-shot or poison the owner's state. */
    if (authentication_callback_live(client) != 0) return -1;
    if (discovery_allows(client, client->authentication_selection_kind) != 0) return -1;
    if (client->discovery_result != CTOS_SSH_DISCOVERY_NOT_STARTED
        && !client->discovery_selected_dispatch) { errno = EACCES; return -1; }
    if (client->authentication_selection_wire == NULL
        || client->service_phase != USERAUTH_SERVICE_READY) { errno = EACCES; return -1; }
    if (selected_credential_is_live(client) != 0) return -1;
    scratch = calloc(1, capacity);
    if (scratch == NULL) { errno = ENOMEM; return -1; }
    client->selected_credential_attempted = 1;
    begin_native_callback(&dispatch, client, NATIVE_CALLBACK_CREDENTIAL);
    callback_result = ctos_ssh_callback_credential_bytes_selected(client->callback,
            client->callback_environment, client->authentication_selection_kind,
            client->authentication_selection_receipt, client->completion_cycle,
            client->completion_session_digest, scratch, capacity, &returned_length);
    end_native_callback(&dispatch);
    if (callback_result != 0) {
        error = errno == 0 ? EIO : errno;
        goto failed;
    }
    if (selected_credential_is_live(client) != 0) {
        error = errno == 0 ? EACCES : errno;
        goto failed;
    }
    if (returned_length == 0 || returned_length > capacity) {
        error = EINVAL;
        goto failed;
    }
    int guarded = client->upstream_relay != NULL && client->jump_effect_generation != 0;
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
    if (guarded && test_before_native_secret_copy != NULL) {
        void (*hook)(void *) = test_before_native_secret_copy;
        void *context = test_before_native_secret_copy_context;
        test_before_native_secret_copy = NULL;
        test_before_native_secret_copy_context = NULL;
        hook(context);
    }
#endif
    if ((guarded ? effect_acquire_for_client(client) : stop_effect_admit(client)) != 0) {
        error = errno == 0 ? EACCES : errno;
        goto failed;
    }
    memcpy(output, scratch, returned_length);
    if (guarded) ctos_ssh_effect_release();
    *length = returned_length;
    if (client->upstream_relay != NULL && selected_credential_is_live(client) != 0) {
        error = errno == 0 ? EACCES : errno;
        goto failed;
    }
    wipe_bytes(scratch, capacity);
    free(scratch);
    return 0;
failed:
    wipe_bytes(scratch, capacity);
    free(scratch);
    wipe_bytes(output, capacity);
    *length = 0;
    return terminal_error(client, error);
}

/* Shared by the const result reader and active auth. No client mutation, I/O
 * or credential/provider call is permitted in this proof check. */
static int auth_proof(const struct ctos_ssh_native_client *client, int kind) {
    struct ctos_ssh_role_snapshot snapshot = {0};
    unsigned char digest[32] = {0};
    uint64_t current;
    int result = -1;
    int error;

    if (ctos_ssh_callback_check_live(client->callback, client->callback_environment) != 0)
        return -1;
    if (upstream_proof(client) != 0) return -1;
    if (view_borrow_is_live(client->managed_view_borrow) != 0) return -1;
    if (now_millis(&current) != 0) return -1;
    if (current >= client->deadline) { errno = ETIMEDOUT; return -1; }
    if (client->active_authentication_kind != kind
        || (client->authentication_stage != 1U && client->authentication_stage != 2U
            && !(client->automatic_selection && client->authentication_stage >= 3U
                && client->authentication_stage <= 4U))
        || (client->authentication_stage == 1U && kind != client->authentication_selection_kind)
        || (client->authentication_stage >= 2U
            && !((client->keyboard_continuation && kind == CTOS_SSH_CALLBACK_CREDENTIAL_KEYBOARD_INTERACTIVE)
                || (client->automatic_selection
                    && ((kind == CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD
                            && (client->automatic_methods & CTOS_SSH_DISCOVERY_PASSWORD) != 0)
                        || (kind == CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY
                            && (client->automatic_methods & CTOS_SSH_DISCOVERY_PUBLICKEY) != 0)))))
        || (kind != CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD
            && kind != CTOS_SSH_CALLBACK_CREDENTIAL_KEYBOARD_INTERACTIVE
            && kind != CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY)
        || client->authentication_selection_wire == NULL
        || client->service_phase != USERAUTH_SERVICE_READY || !client->completed
        || client->completion_calls != 1U || client->completion_cycle == 0
        || client->ssh == NULL || client->ssh->kex == NULL
        || !client->ssh->kex->done || client->ssh->kex->session_id == NULL
        || !nonzero32(client->completion_session_digest)) {
        errno = EACCES;
        return -1;
    }
    if (ctos_ssh_role_bootstrap_snapshot(client->role, &snapshot) != 0) goto out;
    if (snapshot.token != client->role || snapshot.owner_pid != getpid()
        || snapshot.owner_uid != getuid() || snapshot.deadline != client->deadline
        || ctos_ssh_kex_ready(client->ssh, snapshot.hop, snapshot.role_nonce,
            snapshot.connection_binding) != client->completion_cycle) {
        errno = EACCES;
        goto out;
    }
    if (ssh_digest_buffer(SSH_DIGEST_SHA256, client->ssh->kex->session_id,
            digest, sizeof(digest)) != 0) { errno = EIO; goto out; }
    if (memcmp(digest, client->completion_session_digest, sizeof(digest)) != 0) {
        errno = EACCES;
        goto out;
    }
    result = 0;
out:
    error = errno;
    wipe_bytes(&snapshot, sizeof(snapshot));
    wipe_bytes(digest, sizeof(digest));
    errno = error;
    return result;
}

static int auth_is_live(struct ctos_ssh_native_client *client, int cancelled) {
    if (client->established) {
        if (client_owner(client) != 0) return -1;
        errno = EACCES; return -1;
    }
    if (auth_proof(client, client->active_authentication_kind) != 0) {
        int error = errno;
        return error == EPERM ? -1 : terminal_error(client, error);
    }
    if (cancelled) return terminal_error(client, ECANCELED);
    return 0;
}

static int auth_output_is_clear(struct ctos_ssh_native_client *client) {
    size_t length;

    (void)ssh_output_ptr(client->ssh, &length);
    if (length != 0 || ssh_packet_is_rekeying(client->ssh))
        return terminal_error(client, EACCES);
    return 0;
}

/* The Java owner captured the generation before checking its exact chain.
 * Native checks the pinned child again before any protected output. */
static int enter_jump_effect(struct ctos_ssh_native_client *client, uint32_t generation) {
    uint32_t current;
    struct ctos_ssh_native_client *parent;
    if (client_owner(client) != 0) return -1;
    if (client->closed || client->failed || client->upstream_relay == NULL
        || client->jump_effect_generation != 0 || client->automatic_selection
        || client->authentication_selection_kind != CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD
        || client->authentication_stage != 1U || !client->completed
        || client->wire_effect_phase != WIRE_EFFECT_AUTHENTICATING
        || client->wire_effect_generation != generation || wire_effect_pair(client, &parent) != 0
        || client->ssh == NULL || client->ssh->kex == NULL || !client->ssh->kex->done
        || (client->ssh->kex->flags & KEX_INITIAL) != 0
        || ssh_packet_is_rekeying(client->ssh)
        || ctos_ssh_effect_snapshot(&current) != 0 || current != generation) {
        errno = EACCES;
        return -1;
    }
    client->jump_effect_generation = generation;
    return 0;
}

int ctos_ssh_native_client_begin_auth_discovery(struct ctos_ssh_native_client *client) {
    if (client == NULL) { errno = EINVAL; return -1; }
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed) { errno = EALREADY; return -1; }
    if (authentication_callback_live(client) != 0) return -1;
    if (client->discovery_result != CTOS_SSH_DISCOVERY_NOT_STARTED
        || client->auth_phase != AUTH_DISABLED || client->selected_credential_attempted) {
        errno = EALREADY; return -1;
    }
    if (client->service_phase != USERAUTH_SERVICE_READY || client->authentication_selection_wire == NULL) {
        errno = EACCES; return -1;
    }
    if (auth_is_live(client, 0) != 0 || auth_output_is_clear(client) != 0
        || auth_effect_upstream_open(client) != 0) return -1;
    int guarded = client->upstream_relay != NULL && client->jump_effect_generation != 0;
    if ((guarded ? effect_acquire_for_client(client) : stop_effect_admit(client)) != 0) return -1;
    int packet_error = sshpkt_start(client->ssh, SSH2_MSG_USERAUTH_REQUEST) != 0
        || sshpkt_put_string(client->ssh, client->authentication_selection_wire
            + client->authentication_username_offset, client->authentication_username_length) != 0
        || sshpkt_put_cstring(client->ssh, "ssh-connection") != 0
        || sshpkt_put_cstring(client->ssh, "none") != 0 || sshpkt_send(client->ssh) != 0;
    if (guarded) ctos_ssh_effect_release();
    if (packet_error) return terminal_error(client, EIO);
    client->discovery_result = CTOS_SSH_DISCOVERY_PENDING;
    client->auth_phase = AUTH_REQUEST_PENDING;
    client->auth_result = CTOS_SSH_PASSWORD_AUTH_PENDING;
    return 0;
}

int ctos_ssh_native_client_begin_auth_discovery_guarded(struct ctos_ssh_native_client *client,
    uint32_t generation) {
    if (enter_jump_effect(client, generation) != 0) return -1;
    int result = ctos_ssh_native_client_begin_auth_discovery(client);
    client->jump_effect_generation = 0;
    return result;
}

int ctos_ssh_native_client_auth_discovery_result(const struct ctos_ssh_native_client *client,
    enum ctos_ssh_native_client_discovery_result *result, unsigned int *methods) {
    if (result != NULL) *result = CTOS_SSH_DISCOVERY_NOT_STARTED;
    if (methods != NULL) *methods = 0;
    if (client == NULL || result == NULL || methods == NULL) { errno = EINVAL; return -1; }
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed) { errno = EALREADY; return -1; }
    if (auth_proof(client, client->active_authentication_kind) != 0) return -1;
    *result = client->discovery_result;
    *methods = client->discovery_methods;
    if (client->upstream_relay != NULL && auth_proof(client, client->active_authentication_kind) != 0) {
        *result = CTOS_SSH_DISCOVERY_NOT_STARTED; *methods = 0; return -1;
    }
    return 0;
}

static void keyboard_u32(unsigned char **cursor, uint32_t value);

static int send_password_auth_request(struct ctos_ssh_native_client *client,
    const unsigned char *password, size_t length) {
    if (auth_effect_upstream_open(client) != 0) return -1;
    int guarded = client->upstream_relay != NULL && client->jump_effect_generation != 0;
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
    if (guarded && test_before_password_packet != NULL) {
        void (*hook)(void *) = test_before_password_packet;
        void *context = test_before_password_packet_context;
        test_before_password_packet = NULL;
        test_before_password_packet_context = NULL;
        hook(context);
    }
#endif
    if ((guarded ? effect_acquire_for_client(client) : stop_effect_admit(client)) != 0) return -1;
    int packet_error = sshpkt_start(client->ssh, SSH2_MSG_USERAUTH_REQUEST) != 0
        || sshpkt_put_string(client->ssh, client->authentication_selection_wire
            + client->authentication_username_offset, client->authentication_username_length) != 0
        || sshpkt_put_cstring(client->ssh, "ssh-connection") != 0
        || sshpkt_put_cstring(client->ssh, "password") != 0
        || sshpkt_put_u8(client->ssh, 0) != 0
        || sshpkt_put_string(client->ssh, password, length) != 0
        || sshpkt_add_padding(client->ssh, 64) != 0 || sshpkt_send(client->ssh) != 0;
    if (packet_error) {
        /* Do not leave an unfinished plaintext packet queued on failure. */
        (void)sshpkt_start(client->ssh, SSH2_MSG_IGNORE);
    }
    if (guarded) ctos_ssh_effect_release();
    if (packet_error) return terminal_error(client, EIO);
    client->auth_phase = AUTH_REQUEST_PENDING;
    client->auth_result = CTOS_SSH_PASSWORD_AUTH_PENDING;
    return 0;
}

int ctos_ssh_native_client_begin_password_auth(struct ctos_ssh_native_client *client) {
    unsigned char *password;
    size_t length = 0;
    int result = -1;
    int error;

    if (client == NULL) { errno = EINVAL; return -1; }
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed) { errno = EALREADY; return -1; }
    if (authentication_callback_live(client) != 0) return -1;
    if (client->auth_phase != AUTH_DISABLED
        || client->selected_credential_attempted) { errno = EALREADY; return -1; }
    if (client->authentication_selection_kind != CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD
        || client->service_phase != USERAUTH_SERVICE_READY) { errno = EACCES; return -1; }
    if (discovery_allows(client, CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD) != 0) return -1;
    if (auth_is_live(client, 0) != 0 || auth_output_is_clear(client) != 0)
        return -1;
    password = calloc(1, CTOS_SSH_NATIVE_CLIENT_MAX_PASSWORD_BYTES);
    if (password == NULL) { errno = ENOMEM; return -1; }
    client->discovery_selected_dispatch = 1;
    int selected_status = ctos_ssh_native_client_selected_credential_bytes(client, password,
        CTOS_SSH_NATIVE_CLIENT_MAX_PASSWORD_BYTES, &length);
    client->discovery_selected_dispatch = 0;
    if (selected_status != 0) goto out;
    if (length == 0 || length > CTOS_SSH_NATIVE_CLIENT_MAX_PASSWORD_BYTES
        || memchr(password, '\0', length) != NULL) {
        terminal_error(client, EINVAL);
        goto out;
    }
    if (auth_is_live(client, 0) != 0 || auth_output_is_clear(client) != 0)
        goto out;
    result = send_password_auth_request(client, password, length);
out:
    error = errno;
    wipe_bytes(password, CTOS_SSH_NATIVE_CLIENT_MAX_PASSWORD_BYTES);
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
    test_last_password_wiped = 1;
    for (size_t index = 0; index < CTOS_SSH_NATIVE_CLIENT_MAX_PASSWORD_BYTES; index++)
        if (password[index] != 0) test_last_password_wiped = 0;
#endif
    free(password);
    errno = error;
    return result;
}

int ctos_ssh_native_client_begin_password_auth_guarded(struct ctos_ssh_native_client *client,
    uint32_t generation) {
    if (enter_jump_effect(client, generation) != 0) return -1;
    int result = ctos_ssh_native_client_begin_password_auth(client);
    client->jump_effect_generation = 0;
    return result;
}

int ctos_ssh_native_client_begin_discovered_password(struct ctos_ssh_native_client *client,
    const unsigned char expected_plan_receipt[32], const unsigned char *password, size_t length,
    unsigned char stage_receipt[92]) {
    unsigned char *p;
    int initial, continuation;
    if (stage_receipt != NULL) wipe_bytes(stage_receipt, 92U);
    if (client == NULL || expected_plan_receipt == NULL || password == NULL || stage_receipt == NULL
        || length == 0 || length > CTOS_SSH_NATIVE_CLIENT_MAX_PASSWORD_BYTES
        || memchr(password, '\0', length) != NULL) { errno = EINVAL; return -1; }
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed) { errno = EALREADY; return -1; }
    if (authentication_callback_live(client) != 0) return -1;
    initial = client->authentication_stage == 1U && client->auth_phase == AUTH_DISABLED
        && discovery_allows(client, CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD) == 0;
    continuation = automatic_auth_may_continue(client)
        && advertised_algorithm(client->continuation_methods, "password");
    if (!client->automatic_selection || (client->automatic_methods & CTOS_SSH_DISCOVERY_PASSWORD) == 0
        || (client->automatic_attempted_methods & CTOS_SSH_DISCOVERY_PASSWORD) != 0
        || (!initial && !continuation)
        || memcmp(expected_plan_receipt, client->authentication_selection_receipt, 32U) != 0
        || (initial && client->selected_credential_attempted)) {
        errno = EACCES; return -1;
    }
    if (auth_is_live(client, 0) != 0 || auth_output_is_clear(client) != 0
        || auth_effect_upstream_open(client) != 0) return -1;
    client->authentication_stage++;
    client->active_authentication_kind = CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD;
    wipe_bytes(client->continuation_methods, sizeof(client->continuation_methods));
    if (send_password_auth_request(client, password, length) != 0) return -1;
    client->automatic_attempted_methods |= CTOS_SSH_DISCOVERY_PASSWORD;
    p = stage_receipt;
    keyboard_u32(&p, 0x43534150U); keyboard_u32(&p, 0x00020000U);
    keyboard_u32(&p, client->authentication_stage); keyboard_u32(&p, (uint32_t)client->authentication_selection_kind);
    keyboard_u32(&p, CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD);
    memcpy(p, client->authentication_selection_receipt, 32U); p += 32U;
    keyboard_u32(&p, (uint32_t)(client->completion_cycle >> 32));
    keyboard_u32(&p, (uint32_t)client->completion_cycle);
    memcpy(p, client->completion_session_digest, 32U);
    if (client->upstream_relay != NULL && auth_is_live(client, 0) != 0) {
        wipe_bytes(stage_receipt, 92U); return -1;
    }
    return 0;
}

static int packet_bounded_text(struct ssh *ssh, size_t maximum,
    const unsigned char **text, size_t *length) {
    return sshpkt_get_string_direct(ssh, text, length) == 0 && *length <= maximum ? 0 : -1;
}

/* UTF-8 scalar validation is independent of password/config text policy.
 * Answers may be empty and contain any scalar, including an embedded NUL. */
static int keyboard_text(const unsigned char *bytes, size_t length,
    unsigned char *filtered, size_t *filtered_length) {
    size_t index = 0, used = 0;

    if (length > CTOS_SSH_NATIVE_CLIENT_KBI_TEXT_BYTES) return -1;
    while (index < length) {
        size_t start = index;
        unsigned int ch = bytes[index++], scalar = ch, extra = 0;
        if (ch >= 0xc2U && ch <= 0xdfU) { extra = 1; scalar = ch & 0x1fU; }
        else if (ch >= 0xe0U && ch <= 0xefU) { extra = 2; scalar = ch & 0x0fU; }
        else if (ch >= 0xf0U && ch <= 0xf4U) { extra = 3; scalar = ch & 7U; }
        else if (ch >= 0x80U) return -1;
        if (extra > length - index) return -1;
        for (unsigned int part = 0; part < extra; part++) {
            ch = bytes[index++];
            if ((ch & 0xc0U) != 0x80U) return -1;
            scalar = (scalar << 6) | (ch & 0x3fU);
        }
        if ((extra == 1 && scalar < 0x80U) || (extra == 2 && scalar < 0x800U)
            || (extra == 3 && scalar < 0x10000U) || scalar > 0x10ffffU
            || (scalar >= 0xd800U && scalar <= 0xdfffU)) return -1;
        if (filtered != NULL) {
            if ((scalar < 0x20U && scalar != '\n' && scalar != '\t')
                || (scalar >= 0x7fU && scalar <= 0x9fU)) filtered[used++] = '?';
            else { memcpy(filtered + used, bytes + start, index - start); used += index - start; }
        }
    }
    if (filtered_length != NULL) *filtered_length = used;
    return 0;
}

static void keyboard_u32(unsigned char **cursor, uint32_t value) {
    unsigned char *p = *cursor;
    p[0] = (unsigned char)(value >> 24); p[1] = (unsigned char)(value >> 16);
    p[2] = (unsigned char)(value >> 8); p[3] = (unsigned char)value;
    *cursor += 4;
}

static int keyboard_request(struct ctos_ssh_native_client *client) {
    struct ctos_ssh_slice name, instruction, language, prompts[CTOS_SSH_NATIVE_CLIENT_KBI_PROMPTS];
    unsigned char echoes[CTOS_SSH_NATIVE_CLIENT_KBI_PROMPTS];
    unsigned char *wire = NULL, *p;
    uint32_t count;
    size_t total = 24U, name_length = 0, instruction_length = 0;
    int result = -1;

    if (client->keyboard_challenge != NULL
        || client->keyboard_round >= CTOS_SSH_NATIVE_CLIENT_KBI_ROUNDS
        || packet_bounded_text(client->ssh, CTOS_SSH_NATIVE_CLIENT_KBI_TEXT_BYTES,
            &name.data, &name.length) != 0
        || packet_bounded_text(client->ssh, CTOS_SSH_NATIVE_CLIENT_KBI_TEXT_BYTES,
            &instruction.data, &instruction.length) != 0
        || packet_bounded_text(client->ssh, 128U, &language.data, &language.length) != 0
        || sshpkt_get_u32(client->ssh, &count) != 0
        || count > CTOS_SSH_NATIVE_CLIENT_KBI_PROMPTS) goto bad;
    total += name.length + instruction.length;
    for (uint32_t index = 0; index < count; index++) {
        if (packet_bounded_text(client->ssh, CTOS_SSH_NATIVE_CLIENT_KBI_TEXT_BYTES,
                &prompts[index].data, &prompts[index].length) != 0
            || prompts[index].length == 0 || sshpkt_get_u8(client->ssh, &echoes[index]) != 0) goto bad;
        /* RFC 4251 section 5 accepts any nonzero SSH boolean; CSKI stays canonical. */
        echoes[index] = echoes[index] != 0;
        total += 8U + prompts[index].length;
    }
    if (total > CTOS_SSH_NATIVE_CLIENT_KBI_WIRE_BYTES || sshpkt_get_end(client->ssh) != 0) goto bad;
    wire = calloc(1, total);
    if (wire == NULL) return terminal_error(client, ENOMEM);
    p = wire + 24U;
    if (keyboard_text(name.data, name.length, p, &name_length) != 0) goto bad;
    p += name_length;
    if (keyboard_text(instruction.data, instruction.length, p, &instruction_length) != 0) goto bad;
    p += instruction_length;
    for (uint32_t index = 0; index < count; index++) {
        size_t length = 0;
        keyboard_u32(&p, echoes[index]);
        if (keyboard_text(prompts[index].data, prompts[index].length, p + 4U, &length) != 0) goto bad;
        keyboard_u32(&p, (uint32_t)length);
        p += length;
    }
    {
        unsigned char *header = wire;
        keyboard_u32(&header, 0x43534b49U); keyboard_u32(&header, 0x00010000U);
        keyboard_u32(&header, client->keyboard_round + 1U);
        keyboard_u32(&header, (uint32_t)name_length);
        keyboard_u32(&header, (uint32_t)instruction_length); keyboard_u32(&header, count);
    }
    if (auth_is_live(client, 0) != 0 || auth_output_is_clear(client) != 0) goto out;
    client->keyboard_round++;
    client->keyboard_prompts = count;
    client->keyboard_challenge = wire;
    client->keyboard_challenge_length = (size_t)(p - wire);
    client->auth_phase = AUTH_WAIT_INPUT;
    client->auth_result = CTOS_SSH_KEYBOARD_AUTH_CHALLENGE;
    return 0;
bad:
    terminal_error(client, EPROTO);
out:
    if (wire != NULL) { wipe_bytes(wire, total); free(wire); }
    return result;
}

static int send_keyboard_auth_request(struct ctos_ssh_native_client *client) {
    if (auth_effect_upstream_open(client) != 0 || stop_effect_admit(client) != 0) return -1;
    if (sshpkt_start(client->ssh, SSH2_MSG_USERAUTH_REQUEST) != 0
        || sshpkt_put_string(client->ssh, client->authentication_selection_wire
            + client->authentication_username_offset, client->authentication_username_length) != 0
        || sshpkt_put_cstring(client->ssh, "ssh-connection") != 0
        || sshpkt_put_cstring(client->ssh, "keyboard-interactive") != 0
        || sshpkt_put_cstring(client->ssh, "") != 0 || sshpkt_put_cstring(client->ssh, "") != 0
        || sshpkt_send(client->ssh) != 0) {
        (void)sshpkt_start(client->ssh, SSH2_MSG_IGNORE);
        return terminal_error(client, EIO);
    }
    if (!client->automatic_selection) client->selected_credential_attempted = 1;
    else client->automatic_attempted_methods |= CTOS_SSH_DISCOVERY_KEYBOARD_INTERACTIVE;
    client->auth_phase = AUTH_REQUEST_PENDING;
    client->auth_result = CTOS_SSH_KEYBOARD_AUTH_PENDING;
    return 0;
}

int ctos_ssh_native_client_begin_discovered_keyboard(struct ctos_ssh_native_client *client,
    const unsigned char expected_plan_receipt[32], unsigned char stage_receipt[92]) {
    unsigned char *p;
    if (stage_receipt != NULL) wipe_bytes(stage_receipt, 92U);
    if (client == NULL || expected_plan_receipt == NULL || stage_receipt == NULL) { errno = EINVAL; return -1; }
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed) { errno = EALREADY; return -1; }
    if (authentication_callback_live(client) != 0) return -1;
    if (!client->automatic_selection || client->authentication_selection_kind != CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY
        || client->authentication_stage != 1U || client->auth_phase != AUTH_DISABLED
        || client->selected_credential_attempted
        || memcmp(expected_plan_receipt, client->authentication_selection_receipt, 32U) != 0
        || discovery_allows(client, CTOS_SSH_CALLBACK_CREDENTIAL_KEYBOARD_INTERACTIVE) != 0) {
        errno = EACCES; return -1;
    }
    if (auth_is_live(client, 0) != 0 || auth_output_is_clear(client) != 0
        || auth_effect_upstream_open(client) != 0) return -1;
    client->authentication_stage = 2U;
    client->active_authentication_kind = CTOS_SSH_CALLBACK_CREDENTIAL_KEYBOARD_INTERACTIVE;
    client->keyboard_continuation = 1;
    if (send_keyboard_auth_request(client) != 0) return -1;
    p = stage_receipt;
    keyboard_u32(&p, 0x43534150U); keyboard_u32(&p, 0x00020000U);
    keyboard_u32(&p, 2U); keyboard_u32(&p, CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY);
    keyboard_u32(&p, CTOS_SSH_CALLBACK_CREDENTIAL_KEYBOARD_INTERACTIVE);
    memcpy(p, client->authentication_selection_receipt, 32U); p += 32U;
    keyboard_u32(&p, (uint32_t)(client->completion_cycle >> 32));
    keyboard_u32(&p, (uint32_t)client->completion_cycle);
    memcpy(p, client->completion_session_digest, 32U);
    if (client->upstream_relay != NULL && auth_is_live(client, 0) != 0) {
        wipe_bytes(stage_receipt, 92U); return -1;
    }
    return 0;
}

int ctos_ssh_native_client_continue_keyboard_interactive_auth(struct ctos_ssh_native_client *client,
    const unsigned char expected_plan_receipt[32], unsigned char stage_receipt[92]) {
    unsigned char *p;
    int automatic_transition;
    if (stage_receipt != NULL) wipe_bytes(stage_receipt, 92U);
    if (client == NULL || expected_plan_receipt == NULL || stage_receipt == NULL) { errno = EINVAL; return -1; }
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed) { errno = EALREADY; return -1; }
    /* Owner check precedes every state check or mutation. */
    if (authentication_callback_live(client) != 0) return -1;
    automatic_transition = client->automatic_selection
        && (client->automatic_methods & CTOS_SSH_DISCOVERY_KEYBOARD_INTERACTIVE) != 0
        && (client->automatic_attempted_methods & CTOS_SSH_DISCOVERY_KEYBOARD_INTERACTIVE) == 0
        && ((client->authentication_stage <= 3U
                && client->authentication_selection_kind == CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY
                && client->active_authentication_kind == CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY
                && client->selected_credential_attempted)
            || (client->authentication_stage >= 2U && client->authentication_stage <= 3U
                && client->active_authentication_kind == CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD));
    if ((!automatic_transition && (client->automatic_selection || client->authentication_stage != 1U
            || !client->keyboard_continuation
            || (client->authentication_selection_kind != 1 && client->authentication_selection_kind != 4)
            || !client->selected_credential_attempted))
        || client->auth_phase != AUTH_FINISHED
        || (client->auth_result != CTOS_SSH_PASSWORD_AUTH_PARTIAL
            && !(automatic_transition && automatic_auth_may_continue(client)))
        || memcmp(expected_plan_receipt, client->authentication_selection_receipt, 32U) != 0
        || !advertised_algorithm(client->continuation_methods, "keyboard-interactive")) {
        errno = EACCES; return -1;
    }
    if (auth_is_live(client, 0) != 0 || auth_output_is_clear(client) != 0
        || auth_effect_upstream_open(client) != 0) return -1;
    client->authentication_stage++;
    client->active_authentication_kind = CTOS_SSH_CALLBACK_CREDENTIAL_KEYBOARD_INTERACTIVE;
    client->keyboard_continuation = 1;
    wipe_bytes(client->continuation_methods, sizeof(client->continuation_methods));
    if (send_keyboard_auth_request(client) != 0) return -1;
    p = stage_receipt;
    keyboard_u32(&p, 0x43534150U); keyboard_u32(&p, automatic_transition ? 0x00020000U : 0x00010000U);
    keyboard_u32(&p, client->authentication_stage); keyboard_u32(&p, (uint32_t)client->authentication_selection_kind);
    keyboard_u32(&p, 3U);
    memcpy(p, client->authentication_selection_receipt, 32U); p += 32U;
    keyboard_u32(&p, (uint32_t)(client->completion_cycle >> 32));
    keyboard_u32(&p, (uint32_t)client->completion_cycle);
    memcpy(p, client->completion_session_digest, 32U);
    if (client->upstream_relay != NULL && auth_is_live(client, 0) != 0) {
        wipe_bytes(stage_receipt, 92U); return -1;
    }
    return 0;
}

int ctos_ssh_native_client_begin_keyboard_interactive_auth(struct ctos_ssh_native_client *client) {
    if (client == NULL) { errno = EINVAL; return -1; }
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed) { errno = EALREADY; return -1; }
    if (authentication_callback_live(client) != 0) return -1;
    if (client->auth_phase != AUTH_DISABLED || client->selected_credential_attempted) {
        errno = EALREADY; return -1;
    }
    if (client->authentication_selection_kind != CTOS_SSH_CALLBACK_CREDENTIAL_KEYBOARD_INTERACTIVE
        || client->service_phase != USERAUTH_SERVICE_READY) { errno = EACCES; return -1; }
    if (discovery_allows(client, CTOS_SSH_CALLBACK_CREDENTIAL_KEYBOARD_INTERACTIVE) != 0) return -1;
    if (auth_is_live(client, 0) != 0 || auth_output_is_clear(client) != 0) return -1;
    return send_keyboard_auth_request(client);
}

int ctos_ssh_native_client_keyboard_interactive_auth_result(
    const struct ctos_ssh_native_client *client,
    enum ctos_ssh_native_client_keyboard_interactive_auth_result *outcome) {
    if (outcome != NULL) *outcome = CTOS_SSH_KEYBOARD_AUTH_NOT_STARTED;
    if (client == NULL || outcome == NULL) { errno = EINVAL; return -1; }
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed) { errno = EALREADY; return -1; }
    if (auth_proof(client, CTOS_SSH_CALLBACK_CREDENTIAL_KEYBOARD_INTERACTIVE) != 0) return -1;
    if (client->discovery_result == CTOS_SSH_DISCOVERY_PENDING
        || client->discovery_result == CTOS_SSH_DISCOVERY_NONE_SUCCESS) { errno = EACCES; return -1; }
    *outcome = (enum ctos_ssh_native_client_keyboard_interactive_auth_result)client->auth_result;
    if (client->upstream_relay != NULL
        && auth_proof(client, CTOS_SSH_CALLBACK_CREDENTIAL_KEYBOARD_INTERACTIVE) != 0) {
        *outcome = CTOS_SSH_KEYBOARD_AUTH_NOT_STARTED; return -1;
    }
    return 0;
}

int ctos_ssh_native_client_keyboard_interactive_challenge(
    const struct ctos_ssh_native_client *client, unsigned char *output, size_t capacity, size_t *length) {
    enum ctos_ssh_native_client_keyboard_interactive_auth_result outcome;
    if (length != NULL) *length = 0;
    if (output != NULL) wipe_bytes(output, capacity < CTOS_SSH_NATIVE_CLIENT_KBI_WIRE_BYTES
        ? capacity : CTOS_SSH_NATIVE_CLIENT_KBI_WIRE_BYTES);
    if (output == NULL || length == NULL || capacity > CTOS_SSH_NATIVE_CLIENT_KBI_WIRE_BYTES) {
        errno = EINVAL; return -1;
    }
    if (ctos_ssh_native_client_keyboard_interactive_auth_result(client, &outcome) != 0) return -1;
    if (outcome != CTOS_SSH_KEYBOARD_AUTH_CHALLENGE || client->auth_phase != AUTH_WAIT_INPUT
        || client->keyboard_challenge == NULL) { errno = EACCES; return -1; }
    if (capacity < client->keyboard_challenge_length) { errno = EOVERFLOW; return -1; }
    memcpy(output, client->keyboard_challenge, client->keyboard_challenge_length);
    *length = client->keyboard_challenge_length;
    if (client->upstream_relay != NULL
        && auth_proof(client, CTOS_SSH_CALLBACK_CREDENTIAL_KEYBOARD_INTERACTIVE) != 0) {
        wipe_bytes(output, capacity);
        *length = 0;
        return -1;
    }
    return 0;
}

int ctos_ssh_native_client_respond_keyboard_interactive(struct ctos_ssh_native_client *client,
    const unsigned char *wire, size_t length) {
    struct selection_cursor cursor = {wire, length};
    struct ctos_ssh_slice answers[CTOS_SSH_NATIVE_CLIENT_KBI_PROMPTS];
    uint32_t magic, version, round, count;

    if (client == NULL || wire == NULL || length < 16U
        || length > CTOS_SSH_NATIVE_CLIENT_KBI_WIRE_BYTES) { errno = EINVAL; return -1; }
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed) { errno = EALREADY; return -1; }
    if (auth_is_live(client, 0) != 0) return -1;
    if (client->auth_phase != AUTH_WAIT_INPUT || client->keyboard_challenge == NULL
        || client->auth_result != CTOS_SSH_KEYBOARD_AUTH_CHALLENGE
        || client->active_authentication_kind != CTOS_SSH_CALLBACK_CREDENTIAL_KEYBOARD_INTERACTIVE) {
        errno = EACCES; return -1;
    }
    if (selection_u32(&cursor, &magic) != 0 || magic != 0x43534b52U
        || selection_u32(&cursor, &version) != 0 || version != 0x00010000U
        || selection_u32(&cursor, &round) != 0 || round != client->keyboard_round
        || selection_u32(&cursor, &count) != 0 || count != client->keyboard_prompts
        || count > CTOS_SSH_NATIVE_CLIENT_KBI_PROMPTS) { errno = EINVAL; return -1; }
    for (uint32_t index = 0; index < count; index++) {
        if (selection_field(&cursor, &answers[index]) != 0
            || keyboard_text(answers[index].data, answers[index].length, NULL, NULL) != 0) {
            errno = EINVAL; return -1;
        }
    }
    if (cursor.remaining != 0) { errno = EINVAL; return -1; }
    if (auth_is_live(client, 0) != 0 || auth_output_is_clear(client) != 0
        || auth_effect_upstream_open(client) != 0 || stop_effect_admit(client) != 0) return -1;
    if (sshpkt_start(client->ssh, SSH2_MSG_USERAUTH_INFO_RESPONSE) != 0
        || sshpkt_put_u32(client->ssh, count) != 0) goto failed;
    for (uint32_t index = 0; index < count; index++) {
        if (sshpkt_put_string(client->ssh, answers[index].data, answers[index].length) != 0) goto failed;
    }
    if (sshpkt_add_padding(client->ssh, 64) != 0 || sshpkt_send(client->ssh) != 0) goto failed;
    clear_keyboard_challenge(client);
    client->auth_phase = AUTH_REQUEST_PENDING;
    client->auth_result = CTOS_SSH_KEYBOARD_AUTH_PENDING;
    return 0;
failed:
    (void)sshpkt_start(client->ssh, SSH2_MSG_IGNORE);
    return terminal_error(client, EIO);
}

static int auth_method_mask(const unsigned char *text, size_t length, unsigned int *mask) {
    size_t start = 0;
    *mask = 0;
    if (length == 0) return 0;
    for (size_t index = 0; index <= length; index++) {
        if (index != length && text[index] != ',') {
            if (text[index] < 0x21U || text[index] > 0x7eU) return -1;
            continue;
        }
        size_t size = index - start;
        if (size == 0) return -1;
        if (size == 9U && memcmp(text + start, "publickey", size) == 0)
            *mask |= CTOS_SSH_DISCOVERY_PUBLICKEY;
        else if (size == 20U && memcmp(text + start, "keyboard-interactive", size) == 0)
            *mask |= CTOS_SSH_DISCOVERY_KEYBOARD_INTERACTIVE;
        else if (size == 8U && memcmp(text + start, "password", size) == 0)
            *mask |= CTOS_SSH_DISCOVERY_PASSWORD;
        else *mask |= CTOS_SSH_DISCOVERY_OTHER;
        start = index + 1U;
    }
    return 0;
}

static int auth_discovery_packet(struct ctos_ssh_native_client *client, int packet_type) {
    const unsigned char *text;
    size_t length;
    unsigned int methods = 0;
    u_char partial = 0;
    if (client->auth_phase != AUTH_AWAIT_REPLY) return terminal_error(client, EPROTO);
    if (packet_type == SSH2_MSG_USERAUTH_FAILURE) {
        if (packet_bounded_text(client->ssh, 1024U, &text, &length) != 0
            || sshpkt_get_u8(client->ssh, &partial) != 0 || sshpkt_get_end(client->ssh) != 0
            || auth_method_mask(text, length, &methods) != 0) return terminal_error(client, EPROTO);
    } else if (packet_type != SSH2_MSG_USERAUTH_SUCCESS || sshpkt_get_end(client->ssh) != 0) {
        return terminal_error(client, EPROTO);
    }
    if (auth_is_live(client, 0) != 0 || auth_output_is_clear(client) != 0) return -1;
    if (packet_type == SSH2_MSG_USERAUTH_SUCCESS && wire_effect_authentication_complete(client) != 0)
        return terminal_error(client, errno);
    client->discovery_methods = methods;
    if (packet_type == SSH2_MSG_USERAUTH_SUCCESS) {
        client->discovery_result = CTOS_SSH_DISCOVERY_NONE_SUCCESS;
        client->auth_phase = AUTH_FINISHED;
        client->auth_result = CTOS_SSH_PASSWORD_AUTH_SUCCESS;
    } else {
        client->discovery_result = partial ? CTOS_SSH_DISCOVERY_PARTIAL : CTOS_SSH_DISCOVERY_METHODS;
        client->auth_phase = AUTH_DISABLED;
        client->auth_result = CTOS_SSH_PASSWORD_AUTH_NOT_STARTED;
    }
    return 0;
}

static int auth_packet(struct ctos_ssh_native_client *client, int packet_type) {
    const unsigned char *text;
    const unsigned char *language;
    size_t length, language_length;
    u_char partial;
    uint32_t reason;
    enum ctos_ssh_native_client_password_auth_result outcome;

    if (packet_type == SSH2_MSG_USERAUTH_BANNER || packet_type == SSH2_MSG_DISCONNECT) {
        if (packet_type == SSH2_MSG_DISCONNECT && sshpkt_get_u32(client->ssh, &reason) != 0)
            return terminal_error(client, EPROTO);
        if (packet_bounded_text(client->ssh, 8192U, &text, &length) != 0
            || packet_bounded_text(client->ssh, 128U, &language, &language_length) != 0
            || sshpkt_get_end(client->ssh) != 0) return terminal_error(client, EPROTO);
        if (packet_type == SSH2_MSG_DISCONNECT) return terminal_error(client, ECONNRESET);
        if (client->auth_banners >= 16U) return terminal_error(client, EPROTO);
        client->auth_banners++;
        return 0;
    }
    if (client->auth_phase != AUTH_AWAIT_REPLY)
        return terminal_error(client, EPROTO);
    if (client->discovery_result == CTOS_SSH_DISCOVERY_PENDING)
        return auth_discovery_packet(client, packet_type);
    switch (packet_type) {
        case SSH2_MSG_USERAUTH_SUCCESS:
            outcome = CTOS_SSH_PASSWORD_AUTH_SUCCESS;
            break;
        case SSH2_MSG_USERAUTH_FAILURE:
            if (packet_bounded_text(client->ssh, 1024U, &text, &length) != 0
                || sshpkt_get_u8(client->ssh, &partial) != 0)
                return terminal_error(client, EPROTO);
            {
                unsigned int ignored;
                if (auth_method_mask(text, length, &ignored) != 0) return terminal_error(client, EPROTO);
            }
            wipe_bytes(client->continuation_methods, sizeof(client->continuation_methods));
            if ((partial && client->authentication_stage == 1U && client->keyboard_continuation)
                    || (client->automatic_selection && client->authentication_stage <= 3U))
                memcpy(client->continuation_methods, text, length);
            outcome = partial ? CTOS_SSH_PASSWORD_AUTH_PARTIAL : CTOS_SSH_PASSWORD_AUTH_REJECTED;
            break;
        case SSH2_MSG_USERAUTH_PASSWD_CHANGEREQ:
            if (client->active_authentication_kind == CTOS_SSH_CALLBACK_CREDENTIAL_KEYBOARD_INTERACTIVE)
                return keyboard_request(client);
            if (client->active_authentication_kind != CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD)
                return terminal_error(client, EPROTO);
            if (packet_bounded_text(client->ssh, 8192U, &text, &length) != 0
                || packet_bounded_text(client->ssh, 128U, &language, &language_length) != 0)
                return terminal_error(client, EPROTO);
            outcome = CTOS_SSH_PASSWORD_AUTH_CHANGE_REQUIRED;
            break;
        default:
            return terminal_error(client, EPROTO);
    }
    if (sshpkt_get_end(client->ssh) != 0) return terminal_error(client, EPROTO);
    if (auth_is_live(client, 0) != 0 || auth_output_is_clear(client) != 0)
        return -1;
    if (wire_effect_authentication_complete(client) != 0)
        return terminal_error(client, errno);
    client->auth_result = outcome;
    client->auth_phase = AUTH_FINISHED;
    clear_keyboard_challenge(client);
    return 0;
}

static int poll_auth_prompt(struct ctos_ssh_native_client *client,
    const unsigned char expected_plan_receipt[32], unsigned int method, int selected_password) {
    unsigned char input[CTOS_SSH_NATIVE_CLIENT_MAX_STEP_BYTES] = {0};
    const char *name;
    size_t amount;
    ssize_t received;
    u_char packet_type = SSH_MSG_NONE;
    int initial, continuation, result = -1, error;
    if (client == NULL || expected_plan_receipt == NULL
        || (method != CTOS_SSH_DISCOVERY_PUBLICKEY && method != CTOS_SSH_DISCOVERY_PASSWORD)) {
        errno = EINVAL; return -1;
    }
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed) { errno = EALREADY; return -1; }
    if (authentication_callback_live(client) != 0) return -1;
    name = method == CTOS_SSH_DISCOVERY_PUBLICKEY ? "publickey" : "password";
    initial = client->authentication_stage == 1U && client->auth_phase == AUTH_DISABLED
        && (client->discovery_result == CTOS_SSH_DISCOVERY_METHODS
            || client->discovery_result == CTOS_SSH_DISCOVERY_PARTIAL)
        && (client->discovery_methods & method) != 0;
    continuation = automatic_auth_may_continue(client)
        && advertised_algorithm(client->continuation_methods, name);
    if ((selected_password
            ? client->automatic_selection || method != CTOS_SSH_DISCOVERY_PASSWORD
                || client->authentication_selection_kind != CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD
                || client->selected_credential_attempted || !initial
            : !client->automatic_selection || (client->automatic_methods & method) == 0
                || (client->automatic_attempted_methods & method) != 0 || (!initial && !continuation))
        || memcmp(expected_plan_receipt, client->authentication_selection_receipt, 32U) != 0) {
        errno = EACCES; goto out;
    }
    if (auth_is_live(client, 0) != 0 || auth_output_is_clear(client) != 0) goto out;
    amount = client->step_budget < sizeof(input) ? client->step_budget : sizeof(input);
    received = recv(client->socket_fd, input, amount, MSG_DONTWAIT);
    if (received > 0) {
        if (ssh_input_append(client->ssh, input, (size_t)received) != 0) {
            terminal_error(client, EIO); goto out;
        }
    } else if (received == 0) {
        terminal_error(client, EPIPE); goto out;
    } else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
        terminal_error(client, errno == ECONNRESET ? ECONNRESET : EIO); goto out;
    }
    client->in_transport_step = 1;
    int packet_result = ssh_packet_next(client->ssh, &packet_type);
    client->in_transport_step = 0;
    if (packet_result != 0) { terminal_error(client, EIO); goto out; }
    if (packet_type == SSH2_MSG_EXT_INFO) {
        if (kex_input_ext_info(packet_type, 0, client->ssh) != 0) { terminal_error(client, EPROTO); goto out; }
    } else if (packet_type == SSH2_MSG_USERAUTH_BANNER || packet_type == SSH2_MSG_DISCONNECT) {
        if (auth_packet(client, packet_type) != 0) goto out;
    } else if (packet_type != SSH_MSG_NONE) {
        terminal_error(client, EPROTO); goto out;
    }
    if (auth_is_live(client, 0) != 0 || auth_output_is_clear(client) != 0) goto out;
    result = 0;
out:
    error = errno;
    wipe_bytes(input, sizeof(input));
    errno = error;
    return result;
}

int ctos_ssh_native_client_poll_auth_prompt(struct ctos_ssh_native_client *client,
    const unsigned char expected_plan_receipt[32], unsigned int method) {
    return poll_auth_prompt(client, expected_plan_receipt, method, 0);
}

int ctos_ssh_native_client_poll_selected_password_prompt(struct ctos_ssh_native_client *client,
    const unsigned char expected_plan_receipt[32]) {
    return poll_auth_prompt(client, expected_plan_receipt, CTOS_SSH_DISCOVERY_PASSWORD, 1);
}

int ctos_ssh_native_client_auth_continuation_methods(const struct ctos_ssh_native_client *client,
    unsigned int *methods) {
    if (methods != NULL) *methods = 0;
    if (client == NULL || methods == NULL) { errno = EINVAL; return -1; }
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed) { errno = EALREADY; return -1; }
    if (auth_proof(client, client->active_authentication_kind) != 0) return -1;
    if (!automatic_auth_may_continue(client)) { errno = EACCES; return -1; }
    if (auth_method_mask((const unsigned char *)client->continuation_methods,
            strlen(client->continuation_methods), methods) != 0) return -1;
    if (client->upstream_relay != NULL && auth_proof(client, client->active_authentication_kind) != 0) {
        *methods = 0; return -1;
    }
    return 0;
}

int ctos_ssh_native_client_password_auth_result(const struct ctos_ssh_native_client *client,
    enum ctos_ssh_native_client_password_auth_result *outcome) {
    if (outcome != NULL) *outcome = CTOS_SSH_PASSWORD_AUTH_NOT_STARTED;
    if (client == NULL || outcome == NULL) { errno = EINVAL; return -1; }
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed) { errno = EALREADY; return -1; }
    if (auth_proof(client, CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD) != 0) return -1;
    if (client->discovery_result == CTOS_SSH_DISCOVERY_PENDING
        || client->discovery_result == CTOS_SSH_DISCOVERY_NONE_SUCCESS) { errno = EACCES; return -1; }
    *outcome = client->auth_result;
    if (client->upstream_relay != NULL
        && auth_proof(client, CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD) != 0) {
        *outcome = CTOS_SSH_PASSWORD_AUTH_NOT_STARTED; return -1;
    }
    return 0;
}

static int advertised_algorithm(const char *list, const char *algorithm) {
    size_t length = strlen(algorithm);
    const char *end;

    if (list == NULL) return 0;
    while (*list != '\0') {
        end = strchr(list, ',');
        if (end == NULL) end = list + strlen(list);
        if ((size_t)(end - list) == length && memcmp(list, algorithm, length) == 0) return 1;
        list = *end == '\0' ? end : end + 1;
    }
    return 0;
}

static int key_bundle(const unsigned char *bytes, size_t length,
    struct ctos_ssh_slice *key, struct ctos_ssh_slice *passphrase) {
    struct selection_cursor cursor = {bytes, length};
    uint32_t magic, version;

    if (selection_u32(&cursor, &magic) != 0 || magic != 0x43534b42U
        || selection_u32(&cursor, &version) != 0 || version != 1U
        || selection_field(&cursor, key) != 0 || key->length == 0
        || selection_field(&cursor, passphrase) != 0 || cursor.remaining != 0
        || passphrase->length == 0 || passphrase->length > 8192U
        || memchr(passphrase->data, '\0', passphrase->length) != NULL) {
        errno = EINVAL;
        return -1;
    }
    return ctos_ssh_key_encrypted_policy(key->data, key->length);
}

int ctos_ssh_native_client_begin_private_key_auth(struct ctos_ssh_native_client *client) {
    struct sshkey *expected = NULL, *private_key = NULL;
    struct sshbuf *file = NULL, *signed_data = NULL;
    unsigned char *secret = NULL, *public_blob = NULL, *signature = NULL;
    char *passphrase = NULL;
    struct ctos_ssh_slice key_bytes = {0}, passphrase_bytes = {0};
    size_t secret_length = 0, public_length = 0, signature_length = 0;
    const char *algorithm = NULL;
    int result = -1, status, error;

    if (client == NULL) { errno = EINVAL; return -1; }
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed) { errno = EALREADY; return -1; }
    if (authentication_callback_live(client) != 0) return -1;
    if (client->auth_phase != AUTH_DISABLED || client->selected_credential_attempted) {
        errno = EALREADY;
        return -1;
    }
    if (client->authentication_selection_kind != CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY
        || client->service_phase != USERAUTH_SERVICE_READY) { errno = EACCES; return -1; }
    if (discovery_allows(client, CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY) != 0) return -1;
    if (auth_is_live(client, 0) != 0 || auth_output_is_clear(client) != 0) return -1;
    if ((client->ssh->compat & SSH_OLD_SESSIONID) != 0) { errno = ENOTSUP; return -1; }
    if (client->authentication_public_length == 0
        || client->authentication_public_length > 64U * 1024U) { errno = EINVAL; return -1; }
    status = sshkey_from_blob(client->authentication_selection_wire
        + client->authentication_public_offset, client->authentication_public_length, &expected);
    if (status != 0) {
        errno = status == SSH_ERR_ALLOC_FAIL ? ENOMEM
            : status == SSH_ERR_KEY_TYPE_UNKNOWN || status == SSH_ERR_FEATURE_UNSUPPORTED ? ENOTSUP : EINVAL;
        goto out;
    }
    if (ctos_ssh_key_software_identity(expected) != 0) goto out;
    if (expected->type == KEY_RSA) {
        if (advertised_algorithm(client->ssh->kex->server_sig_algs, "rsa-sha2-512"))
            algorithm = "rsa-sha2-512";
        else if (advertised_algorithm(client->ssh->kex->server_sig_algs, "rsa-sha2-256"))
            algorithm = "rsa-sha2-256";
        else { errno = ENOTSUP; goto out; }
    } else algorithm = sshkey_ssh_name(expected);
    if (algorithm == NULL || *algorithm == '\0') { errno = ENOTSUP; goto out; }
    status = sshkey_to_blob(expected, &public_blob, &public_length);
    if (status != 0) { errno = status == SSH_ERR_ALLOC_FAIL ? ENOMEM : EINVAL; goto out; }
    if (public_length == 0 || public_length > 64U * 1024U) { errno = EINVAL; goto out; }
    secret = calloc(1, CTOS_SSH_VIEW_FILE_BYTES);
    signed_data = sshbuf_new();
    if (secret == NULL || signed_data == NULL) { errno = ENOMEM; goto out; }
    client->discovery_selected_dispatch = 1;
    int selected_status = ctos_ssh_native_client_selected_credential_bytes(client, secret,
        CTOS_SSH_VIEW_FILE_BYTES, &secret_length);
    client->discovery_selected_dispatch = 0;
    if (selected_status != 0) goto out;
    key_bytes = (struct ctos_ssh_slice){secret, secret_length};
    if (client->authentication_key_bundle) {
        if (key_bundle(secret, secret_length, &key_bytes, &passphrase_bytes) != 0) {
            terminal_error(client, errno);
            goto out;
        }
        passphrase = calloc(1, passphrase_bytes.length + 1U);
        if (passphrase == NULL) { terminal_error(client, ENOMEM); goto out; }
        if (stop_effect_admit(client) != 0) goto out;
        memcpy(passphrase, passphrase_bytes.data, passphrase_bytes.length);
    }
    if (stop_effect_admit(client) != 0) goto out;
    file = sshbuf_from(key_bytes.data, key_bytes.length);
    if (file != NULL && stop_effect_admit(client) != 0) goto out;
    status = file == NULL ? SSH_ERR_ALLOC_FAIL
        : sshkey_parse_private_fileblob(file, passphrase, &private_key, NULL);
    sshbuf_free(file);
    file = NULL;
    wipe_bytes(secret, CTOS_SSH_VIEW_FILE_BYTES);
    wipe_bytes(passphrase, passphrase_bytes.length + (passphrase == NULL ? 0U : 1U));
    if (status != 0) {
        terminal_error(client, status == SSH_ERR_ALLOC_FAIL ? ENOMEM
            : status == SSH_ERR_KEY_WRONG_PASSPHRASE
                ? (client->authentication_key_bundle ? EACCES : ENOTSUP)
            : status == SSH_ERR_FEATURE_UNSUPPORTED || status == SSH_ERR_KEY_TYPE_UNKNOWN ? ENOTSUP : EINVAL);
        goto out;
    }
    if (ctos_ssh_key_software_identity(private_key) != 0) {
        terminal_error(client, errno);
        goto out;
    }
    if (!sshkey_equal_public(expected, private_key)) {
        terminal_error(client, EACCES);
        goto out;
    }
    /* SSH signs the framed raw initial session ID, not its authorization digest. */
    if (sshbuf_put_stringb(signed_data, client->ssh->kex->session_id) != 0
        || sshbuf_put_u8(signed_data, SSH2_MSG_USERAUTH_REQUEST) != 0
        || sshbuf_put_string(signed_data, client->authentication_selection_wire
            + client->authentication_username_offset, client->authentication_username_length) != 0
        || sshbuf_put_cstring(signed_data, "ssh-connection") != 0
        || sshbuf_put_cstring(signed_data, "publickey") != 0
        || sshbuf_put_u8(signed_data, 1) != 0
        || sshbuf_put_cstring(signed_data, algorithm) != 0
        || sshbuf_put_string(signed_data, public_blob, public_length) != 0
        || sshbuf_len(signed_data) > SSH_KEY_MAX_SIGN_DATA_SIZE) {
        terminal_error(client, EIO);
        goto out;
    }
    if (auth_effect_upstream_open(client) != 0 || stop_effect_admit(client) != 0) goto out;
    status = sshkey_sign(private_key, &signature, &signature_length,
        sshbuf_ptr(signed_data), sshbuf_len(signed_data), algorithm, NULL, NULL, 0);
    if (status != 0 || signature_length == 0 || signature_length > 16U * 1024U
        || sshkey_check_sigtype(signature, signature_length, algorithm) != 0) {
        terminal_error(client, status == SSH_ERR_ALLOC_FAIL ? ENOMEM : EACCES);
        goto out;
    }
    status = sshkey_verify(expected, signature, signature_length, sshbuf_ptr(signed_data),
        sshbuf_len(signed_data), algorithm, 0, NULL);
    if (status != 0) {
        terminal_error(client, status == SSH_ERR_ALLOC_FAIL ? ENOMEM : EACCES);
        goto out;
    }
    if (auth_is_live(client, 0) != 0 || auth_output_is_clear(client) != 0
        || auth_effect_upstream_open(client) != 0 || stop_effect_admit(client) != 0) goto out;
    if (sshpkt_start(client->ssh, SSH2_MSG_USERAUTH_REQUEST) != 0
        || sshpkt_put_string(client->ssh, client->authentication_selection_wire
            + client->authentication_username_offset, client->authentication_username_length) != 0
        || sshpkt_put_cstring(client->ssh, "ssh-connection") != 0
        || sshpkt_put_cstring(client->ssh, "publickey") != 0
        || sshpkt_put_u8(client->ssh, 1) != 0
        || sshpkt_put_cstring(client->ssh, algorithm) != 0
        || sshpkt_put_string(client->ssh, public_blob, public_length) != 0
        || sshpkt_put_string(client->ssh, signature, signature_length) != 0
        || sshpkt_send(client->ssh) != 0) {
        (void)sshpkt_start(client->ssh, SSH2_MSG_IGNORE);
        terminal_error(client, EIO);
        goto out;
    }
    client->auth_phase = AUTH_REQUEST_PENDING;
    client->auth_result = CTOS_SSH_PRIVATE_KEY_AUTH_PENDING;
    if (client->automatic_selection) client->automatic_attempted_methods |= CTOS_SSH_DISCOVERY_PUBLICKEY;
    result = 0;
out:
    error = errno;
    sshbuf_free(file);
    sshbuf_free(signed_data);
    sshkey_free(private_key);
    sshkey_free(expected);
    wipe_bytes(secret, secret == NULL ? 0 : CTOS_SSH_VIEW_FILE_BYTES);
    free(secret);
    wipe_bytes(passphrase, passphrase_bytes.length + (passphrase == NULL ? 0U : 1U));
    free(passphrase);
    wipe_bytes(signature, signature_length);
    free(signature);
    wipe_bytes(public_blob, public_length);
    free(public_blob);
    errno = error;
    return result;
}

int ctos_ssh_native_client_continue_private_key_auth(struct ctos_ssh_native_client *client,
    const unsigned char expected_plan_receipt[32], unsigned char stage_receipt[92]) {
    unsigned char *p;
    if (stage_receipt != NULL) wipe_bytes(stage_receipt, 92U);
    if (client == NULL || expected_plan_receipt == NULL || stage_receipt == NULL) { errno = EINVAL; return -1; }
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed) { errno = EALREADY; return -1; }
    if (authentication_callback_live(client) != 0) return -1;
    if (!client->automatic_selection || client->authentication_selection_kind != CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY
        || (client->automatic_methods & CTOS_SSH_DISCOVERY_PUBLICKEY) == 0
        || (client->automatic_attempted_methods & CTOS_SSH_DISCOVERY_PUBLICKEY) != 0
        || client->selected_credential_attempted || client->authentication_stage >= 4U
        || !automatic_auth_may_continue(client)
        || !advertised_algorithm(client->continuation_methods, "publickey")
        || memcmp(expected_plan_receipt, client->authentication_selection_receipt, 32U) != 0) {
        errno = EACCES; return -1;
    }
    if (auth_is_live(client, 0) != 0 || auth_output_is_clear(client) != 0
        || auth_effect_upstream_open(client) != 0) return -1;
    client->authentication_stage++;
    client->active_authentication_kind = CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY;
    client->auth_phase = AUTH_DISABLED;
    client->automatic_continuation_dispatch = 1;
    wipe_bytes(client->continuation_methods, sizeof(client->continuation_methods));
    int status = ctos_ssh_native_client_begin_private_key_auth(client);
    client->automatic_continuation_dispatch = 0;
    if (status != 0) return terminal_error(client, errno == 0 ? EIO : errno);
    p = stage_receipt;
    keyboard_u32(&p, 0x43534150U); keyboard_u32(&p, 0x00020000U);
    keyboard_u32(&p, client->authentication_stage); keyboard_u32(&p, CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY);
    keyboard_u32(&p, CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY);
    memcpy(p, client->authentication_selection_receipt, 32U); p += 32U;
    keyboard_u32(&p, (uint32_t)(client->completion_cycle >> 32));
    keyboard_u32(&p, (uint32_t)client->completion_cycle);
    memcpy(p, client->completion_session_digest, 32U);
    if (client->upstream_relay != NULL && auth_is_live(client, 0) != 0) {
        wipe_bytes(stage_receipt, 92U); return -1;
    }
    return 0;
}

int ctos_ssh_native_client_private_key_auth_result(const struct ctos_ssh_native_client *client,
    enum ctos_ssh_native_client_private_key_auth_result *outcome) {
    if (outcome != NULL) *outcome = CTOS_SSH_PRIVATE_KEY_AUTH_NOT_STARTED;
    if (client == NULL || outcome == NULL) { errno = EINVAL; return -1; }
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed) { errno = EALREADY; return -1; }
    if (auth_proof(client, CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY) != 0) return -1;
    if (client->discovery_result == CTOS_SSH_DISCOVERY_PENDING
        || client->discovery_result == CTOS_SSH_DISCOVERY_NONE_SUCCESS) { errno = EACCES; return -1; }
    *outcome = (enum ctos_ssh_native_client_private_key_auth_result)client->auth_result;
    if (client->upstream_relay != NULL
        && auth_proof(client, CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY) != 0) {
        *outcome = CTOS_SSH_PRIVATE_KEY_AUTH_NOT_STARTED; return -1;
    }
    return 0;
}

int ctos_ssh_native_client_begin_userauth_service(struct ctos_ssh_native_client *client) {
    if (client == NULL) { errno = EINVAL; return -1; }
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed || client->service_phase != USERAUTH_SERVICE_DISABLED) {
        errno = EALREADY;
        return -1;
    }
    if (service_is_live(client, 0) != 0 || auth_effect_upstream_open(client) != 0) return -1;
    if (!client->completed) { errno = EACCES; return -1; }

    /* Service negotiation cannot silently dispatch a new KEX under old proof. */
    ssh_dispatch_range(client->ssh, SSH2_MSG_KEXINIT,
        SSH2_MSG_TRANSPORT_MAX, NULL);
    int guarded = client->upstream_relay != NULL && client->jump_effect_generation != 0;
    if ((guarded ? effect_acquire_for_client(client) : stop_effect_admit(client)) != 0) return -1;
    int packet_error = sshpkt_start(client->ssh, SSH2_MSG_SERVICE_REQUEST) != 0
        || sshpkt_put_cstring(client->ssh, "ssh-userauth") != 0
        || sshpkt_send(client->ssh) != 0;
    if (guarded) ctos_ssh_effect_release();
    if (packet_error) return terminal_error(client, EIO);
    client->service_phase = USERAUTH_SERVICE_REQUEST_PENDING;
    return 0;
}

int ctos_ssh_native_client_begin_userauth_service_guarded(struct ctos_ssh_native_client *client,
    uint32_t generation) {
    if (enter_jump_effect(client, generation) != 0) return -1;
    int result = ctos_ssh_native_client_begin_userauth_service(client);
    client->jump_effect_generation = 0;
    return result;
}

static int accept_userauth_service(struct ctos_ssh_native_client *client) {
    const unsigned char *service = NULL;
    size_t length;

    if (client->service_phase != USERAUTH_SERVICE_AWAIT_ACCEPT)
        return terminal_error(client, EPROTO);
    (void)sshpkt_ptr(client->ssh, &length);
    if (length != 0
        && (sshpkt_get_string_direct(client->ssh, &service, &length) != 0
            || (length != 0 && (length != sizeof("ssh-userauth") - 1U
                || memcmp(service, "ssh-userauth", length) != 0))))
        return terminal_error(client, EPROTO);
    if (sshpkt_get_end(client->ssh) != 0)
        return terminal_error(client, EPROTO);
    client->service_phase = USERAUTH_SERVICE_READY;
    return 0;
}

#define SHELL_PACKET_MAX 32768U
#define SHELL_OUTPUT_MAX (128U * 1024U)
#define SHELL_INPUT_MAX (256U * 1024U)

/* This part intentionally excludes both upstream and downstream traversal.
 * A retained jump checks its child with this helper: calling the child's full
 * proof there would recurse back through the same parent. */
static int established_self_proof(const struct ctos_ssh_native_client *client) {
    struct ctos_ssh_role_snapshot snapshot = {0};
    unsigned char digest[32] = {0};
    int result = -1;
    int error;

    if (client_owner(client) != 0) return -1;
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed) { errno = EALREADY; return -1; }
    if (ctos_ssh_callback_check_transport(client->callback,
            client->callback_environment, client) != 0) return -1;
    if (!client->established || client->ssh == NULL || client->ssh != client->established_ssh
        || !client->view_released || client->managed_view_borrow != NULL || client->managed_view != NULL
        || client->server_host_public_key == NULL
        || client->server_host_public_key != client->established_host_key
        || client->role != client->established_role.token
        || client->deadline != client->established_role.deadline
        || client->auth_phase != AUTH_FINISHED
        || client->auth_result != CTOS_SSH_PASSWORD_AUTH_SUCCESS
        || !client->completed || client->completion_calls != 1U
        || client->completion_cycle != client->established_cycle
        || client->established_cycle == 0 || client->ssh->kex == NULL
        || !client->ssh->kex->done || client->ssh->kex->session_id == NULL
        || ssh_packet_is_rekeying(client->ssh)
        || memcmp(client->completion_session_digest, client->established_session_digest, 32U) != 0
        || strcmp(client->host, client->established_role.host) != 0) {
        errno = EACCES; return -1;
    }
    if (ctos_ssh_role_transport_snapshot(client->role, &snapshot) != 0) goto out;
    if (memcmp(&snapshot, &client->established_role, sizeof(snapshot)) != 0
        || ctos_ssh_kex_transport_ready(client->ssh, snapshot.hop, snapshot.role_nonce,
                snapshot.connection_binding) != client->established_cycle) {
        errno = EACCES; goto out;
    }
    if (ssh_digest_buffer(SSH_DIGEST_SHA256, client->ssh->kex->session_id,
            digest, sizeof(digest)) != 0) { errno = EIO; goto out; }
    if (memcmp(digest, client->established_session_digest, sizeof(digest)) != 0) {
        errno = EACCES; goto out;
    }
    result = 0;
out:
    error = errno;
    wipe_bytes(&snapshot, sizeof(snapshot));
    wipe_bytes(digest, sizeof(digest));
    errno = error;
    return result;
}

/* Retention is conditional on the exact established descendant chain, not
 * merely on retaining the parent's original role. Check downwards without
 * following the child's upstream pointer back into this proof. */
static int established_downstream_proof(const struct ctos_ssh_native_client *client,
    int require_open) {
    unsigned int hops = 0;
    while (client->jump_mode) {
        const struct ctos_ssh_native_jump_relay *relay = client->jump_relay;
        const struct ctos_ssh_native_client *child;
        if (++hops >= CTOS_SSH_ROLE_MAX || relay == NULL) { errno = EACCES; return -1; }
        if (jump_relay_owner(relay) != 0) return -1;
        child = relay->child;
        if (relay->closed || relay->failed || relay->parent != client || child == NULL
            || child->upstream_relay != relay || !child->established
            || child->role != client->jump_next_role.token
            || memcmp(&child->established_role, &client->jump_next_role,
                sizeof(client->jump_next_role)) != 0
            || (client->shell.phase != CTOS_SSH_SHELL_RUNNING
                && client->shell.phase != CTOS_SSH_SHELL_CLOSED)
            || (child->shell.phase != CTOS_SSH_SHELL_RUNNING
                && child->shell.phase != CTOS_SSH_SHELL_CLOSED)) {
            errno = EACCES; return -1;
        }
        if (require_open && (client->shell.phase != CTOS_SSH_SHELL_RUNNING
                || client->shell.remote_eof || client->shell.local_eof || client->remote_close
                || child->shell.phase != CTOS_SSH_SHELL_RUNNING
                || child->shell.remote_eof || child->shell.local_eof || child->remote_close)) {
            errno = EACCES; return -1;
        }
        if (established_self_proof(child) != 0) return -1;
        client = child;
    }
    return 0;
}

static int established_proof(const struct ctos_ssh_native_client *client) {
    if (established_self_proof(client) != 0 || upstream_proof(client) != 0) return -1;
    return established_downstream_proof(client, 0);
}

static int child_publication_live(const struct ctos_ssh_native_client *client) {
    struct ctos_ssh_role_snapshot snapshot = {0};
    unsigned char digest[32] = {0};
    int result = -1, error;

    if (client->upstream_relay == NULL) return 0;
    if (client->failed) { errno = EALREADY; return -1; }
    if (client->established) return established_proof(client);
    if (ctos_ssh_callback_check_live(client->callback, client->callback_environment) != 0
        || view_borrow_is_live(client->managed_view_borrow) != 0
        || upstream_proof(client) != 0) return -1;
    if (!client->completed || client->completion_calls != 1U || client->completion_cycle == 0
        || client->server_host_public_key == NULL || client->ssh == NULL
        || client->ssh->kex == NULL || !client->ssh->kex->done
        || client->ssh->kex->session_id == NULL || ssh_packet_is_rekeying(client->ssh)
        || !nonzero32(client->completion_session_digest)) {
        errno = EACCES;
        goto out;
    }
    if (ctos_ssh_role_bootstrap_snapshot(client->role, &snapshot) != 0) goto out;
    if (snapshot.token != client->role || snapshot.owner_pid != getpid()
        || snapshot.owner_uid != getuid() || snapshot.deadline != client->deadline
        || strcmp(snapshot.host, client->host) != 0
        || ctos_ssh_kex_ready(client->ssh, snapshot.hop, snapshot.role_nonce,
            snapshot.connection_binding) != client->completion_cycle) {
        errno = EACCES;
        goto out;
    }
    if (ssh_digest_buffer(SSH_DIGEST_SHA256, client->ssh->kex->session_id,
            digest, sizeof(digest)) != 0) { errno = EIO; goto out; }
    if (memcmp(digest, client->completion_session_digest, sizeof(digest)) != 0) {
        errno = EACCES;
        goto out;
    }
    if (upstream_proof(client) != 0) goto out;
    result = 0;
out:
    error = errno;
    wipe_bytes(&snapshot, sizeof(snapshot));
    wipe_bytes(digest, sizeof(digest));
    errno = error;
    return result;
}

static int shell_proof(const struct ctos_ssh_native_client *client) {
    if (client == NULL) { errno = EINVAL; return -1; }
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed) { errno = EALREADY; return -1; }
    if (client->established) return established_proof(client);
    if (auth_proof(client, client->active_authentication_kind) != 0) return -1;
    if (client->jump_mode) {
        struct ctos_ssh_role_snapshot next = {0};
        int result = ctos_ssh_role_bootstrap_snapshot(client->jump_next_role.token, &next);
        if (result == 0 && memcmp(&next, &client->jump_next_role, sizeof(next)) != 0) {
            errno = EACCES; result = -1;
        }
        int failure = errno;
        wipe_bytes(&next, sizeof(next)); errno = failure;
        return result;
    }
    return 0;
}

static int shell_live(struct ctos_ssh_native_client *client) {
    if (shell_proof(client) == 0) return 0;
    if (client == NULL || client->closed || client->failed || errno == EPERM) return -1;
    return terminal_error(client, errno);
}

static int established_live(struct ctos_ssh_native_client *client, int cancelled) {
    if (shell_live(client) != 0) return -1;
    if (cancelled) return terminal_error(client, ECANCELED);
    return 0;
}

static int shell_room(struct ctos_ssh_native_client *client, size_t reserve) {
    size_t length;
    (void)ssh_output_ptr(client->ssh, &length);
    return reserve <= SHELL_OUTPUT_MAX && length <= SHELL_OUTPUT_MAX - reserve;
}

static int shell_send(struct ctos_ssh_native_client *client) {
    size_t length;
    if (sshpkt_send(client->ssh) != 0) return terminal_error(client, EIO);
    (void)ssh_output_ptr(client->ssh, &length);
    if (length > SHELL_OUTPUT_MAX || ssh_packet_is_rekeying(client->ssh))
        return terminal_error(client, EPROTO);
    return 0;
}

static int shell_message(struct ctos_ssh_native_client *client, int type) {
    if (sshpkt_start(client->ssh, type) != 0
        || sshpkt_put_u32(client->ssh, client->remote_channel) != 0)
        return terminal_error(client, EIO);
    return 0;
}

static int shell_close_message(struct ctos_ssh_native_client *client) {
    if (client->local_close) return 0;
    if (!shell_room(client, 256U)) return 0;
    if (shell_message(client, SSH2_MSG_CHANNEL_CLOSE) != 0 || shell_send(client) != 0)
        return -1;
    client->local_close = 1;
    return 0;
}

static int shell_request(struct ctos_ssh_native_client *client, int pty) {
    if (shell_message(client, SSH2_MSG_CHANNEL_REQUEST) != 0
        || sshpkt_put_cstring(client->ssh, pty ? "pty-req" : "shell") != 0
        || sshpkt_put_u8(client->ssh, 1) != 0) return terminal_error(client, EIO);
    if (pty && (sshpkt_put_cstring(client->ssh, "xterm-256color") != 0
        || sshpkt_put_u32(client->ssh, client->shell_columns) != 0
        || sshpkt_put_u32(client->ssh, client->shell_rows) != 0
        || sshpkt_put_u32(client->ssh, 0) != 0 || sshpkt_put_u32(client->ssh, 0) != 0
        || sshpkt_put_string(client->ssh, "", 1) != 0)) return terminal_error(client, EIO);
    client->shell_request_drained = 0;
    client->shell.phase = pty ? CTOS_SSH_SHELL_PTY_PENDING : CTOS_SSH_SHELL_SHELL_PENDING;
    return shell_send(client);
}

int ctos_ssh_native_client_configure_server_alive(struct ctos_ssh_native_client *client,
    unsigned int interval_seconds, unsigned int count_max) {
    if (client == NULL || interval_seconds > INT_MAX || count_max > INT_MAX) { errno = EINVAL; return -1; }
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed) { errno = EALREADY; return -1; }
    if (ctos_ssh_callback_check_live(client->callback, client->callback_environment) != 0) return -1;
    if (client->server_alive_configured || client->established
        || client->shell.phase != CTOS_SSH_SHELL_DISABLED) { errno = EALREADY; return -1; }
    if (client->auth_phase != AUTH_FINISHED || client->auth_result != CTOS_SSH_PASSWORD_AUTH_SUCCESS) {
        errno = EACCES; return -1;
    }
    if (auth_is_live(client, 0) != 0) return -1;
    client->server_alive_interval = interval_seconds;
    client->server_alive_count_max = count_max;
    client->server_alive_configured = 1;
    return 0;
}

int ctos_ssh_native_client_configure_no_pty(struct ctos_ssh_native_client *client) {
    if (client == NULL) { errno = EINVAL; return -1; }
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed) { errno = EALREADY; return -1; }
    if (ctos_ssh_callback_check_live(client->callback, client->callback_environment) != 0) return -1;
    if (client->shell_no_pty || client->established
        || client->shell.phase != CTOS_SSH_SHELL_DISABLED) { errno = EALREADY; return -1; }
    if (client->auth_phase != AUTH_FINISHED || client->auth_result != CTOS_SSH_PASSWORD_AUTH_SUCCESS) {
        errno = EACCES; return -1;
    }
    if (auth_is_live(client, 0) != 0) return -1;
    client->shell_no_pty = 1;
    return 0;
}

static int promote_authenticated_transport(struct ctos_ssh_native_client *client,
    const struct ctos_ssh_role_snapshot *snapshot) {
    uint64_t current;
    if (ctos_ssh_callback_promote_transport(client->callback,
            client->callback_environment, client) != 0)
        return terminal_error(client, errno);
    client->established_role = *snapshot;
    client->established_ssh = client->ssh;
    client->established_host_key = client->server_host_public_key;
    client->established_cycle = client->completion_cycle;
    memcpy(client->established_session_digest, client->completion_session_digest, 32U);
    /* All auth proofs were fresh above. Retire plaintext/config access before retained publication. */
    if (view_borrow_is_live(client->managed_view_borrow) != 0)
        return terminal_error(client, errno);
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
    if (client->test_before_promotion_release != NULL) {
        void (*hook)(void *) = client->test_before_promotion_release;
        void *context = client->test_promotion_release_context;
        client->test_before_promotion_release = NULL;
        client->test_promotion_release_context = NULL;
        hook(context);
    }
#endif
    if (ctos_ssh_view_borrow_claim_release_live(client->view_claim, client) != 0)
        return terminal_error(client, errno);
    client->view_claim = NULL;
    client->managed_view_borrow = NULL;
    client->managed_view = NULL;
    client->hostfile_view = NULL;
    client->view_released = 1;
    clear_authentication_selection(client);
    clear_keyboard_challenge(client);
    client->keyboard_round = 0;
    /* The view may outlive this role. Expiry during retirement must not
     * publish a transport merely because the retained role ignores its clock. */
    if (now_millis(&current) != 0) return terminal_error(client, EIO);
    if (current >= client->deadline) return terminal_error(client, ETIMEDOUT);
    client->established = 1;
    return 0;
}

int ctos_ssh_native_client_begin_shell(struct ctos_ssh_native_client *client,
    unsigned int columns, unsigned int rows) {
    struct ctos_ssh_role_snapshot snapshot = {0};
    if (client == NULL || columns == 0 || columns > 4096U || rows == 0 || rows > 4096U) {
        errno = EINVAL; return -1;
    }
    if (shell_live(client) != 0) return -1;
    if (client->shell.phase != CTOS_SSH_SHELL_DISABLED) { errno = EALREADY; return -1; }
    if (client->auth_phase != AUTH_FINISHED || client->auth_result != CTOS_SSH_PASSWORD_AUTH_SUCCESS) {
        errno = EACCES; return -1;
    }
    if (auth_output_is_clear(client) != 0) return -1;
    if (ctos_ssh_role_bootstrap_snapshot(client->role, &snapshot) != 0)
        return terminal_error(client, errno);
    if (snapshot.program != CTOS_SSH_ROLE_CLIENT) {
        wipe_bytes(&snapshot, sizeof(snapshot)); errno = EACCES; return -1;
    }
    int promotion = promote_authenticated_transport(client, &snapshot);
    int error = errno;
    wipe_bytes(&snapshot, sizeof(snapshot));
    if (promotion != 0) { errno = error; return -1; }
    if (client->server_alive_interval != 0 && now_millis(&client->server_alive_last_activity) != 0)
        return terminal_error(client, EIO);
    if (sshpkt_start(client->ssh, SSH2_MSG_CHANNEL_OPEN) != 0
        || sshpkt_put_cstring(client->ssh, "session") != 0
        || sshpkt_put_u32(client->ssh, 0) != 0
        || sshpkt_put_u32(client->ssh, 65536U) != 0
        || sshpkt_put_u32(client->ssh, SHELL_PACKET_MAX) != 0
        || shell_send(client) != 0) return terminal_error(client, EIO);
    client->shell.phase = CTOS_SSH_SHELL_OPENING;
    client->shell_columns = columns;
    client->shell_rows = rows;
    client->local_window = 65536U;
    return shell_live(client);
}

int ctos_ssh_native_client_retain_jump_transport(struct ctos_ssh_native_client *client) {
    struct ctos_ssh_role_snapshot snapshot = {0};
    struct ctos_ssh_native_jump_relay *relay;
    struct ctos_ssh_native_client *child;
    int result, error;

    if (client_owner(client) != 0) return -1;
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed || client->established) { errno = EALREADY; return -1; }
    if (!client->jump_mode) { errno = EACCES; return -1; }
    if (shell_live(client) != 0) return -1;
    relay = client->jump_relay;
    if (relay == NULL || jump_relay_owner(relay) != 0) {
        if (relay == NULL) errno = EACCES;
        return -1;
    }
    child = relay->child;
    if (relay->closed || relay->failed || relay->parent != client || child == NULL
        || child->upstream_relay != relay || !child->established
        || child->role != client->jump_next_role.token
        || memcmp(&child->established_role, &client->jump_next_role,
            sizeof(client->jump_next_role)) != 0
        || client->shell.phase != CTOS_SSH_SHELL_RUNNING
        || child->shell.phase != CTOS_SSH_SHELL_RUNNING
        || client->shell.remote_eof || client->shell.local_eof || client->remote_close
        || child->shell.remote_eof || child->shell.local_eof || child->remote_close) {
        errno = EACCES; return -1;
    }
    /* This validates the child's entire established suffix and original
     * upstream chain before retiring the parent's authentication authority. */
    if (established_proof(child) != 0 || established_downstream_proof(child, 1) != 0) return -1;
    if (ctos_ssh_role_bootstrap_snapshot(client->role, &snapshot) != 0)
        return terminal_error(client, errno);
    if (snapshot.program != CTOS_SSH_ROLE_JUMP) {
        wipe_bytes(&snapshot, sizeof(snapshot)); errno = EACCES; return -1;
    }
    result = promote_authenticated_transport(client, &snapshot);
    error = errno;
    wipe_bytes(&snapshot, sizeof(snapshot));
    if (result != 0) { errno = error; return -1; }
    return shell_live(client);
}

int ctos_ssh_native_client_begin_jump(struct ctos_ssh_native_client *client,
    ctos_ssh_role_token next_role) {
    struct ctos_ssh_role_snapshot current = {0}, next = {0};
    int error;
    if (client == NULL || next_role == 0) { errno = EINVAL; return -1; }
    if (shell_live(client) != 0) return -1;
    if (client->established || client->jump_mode || client->shell.phase != CTOS_SSH_SHELL_DISABLED) {
        errno = EALREADY; return -1;
    }
    if (client->auth_phase != AUTH_FINISHED || client->auth_result != CTOS_SSH_PASSWORD_AUTH_SUCCESS) {
        errno = EACCES; return -1;
    }
    if (auth_output_is_clear(client) != 0) return -1;
    if (ctos_ssh_role_bootstrap_snapshot(client->role, &current) != 0
        || ctos_ssh_role_bootstrap_snapshot(next_role, &next) != 0) goto failed;
    if (current.program != CTOS_SSH_ROLE_JUMP || next.token != next_role
        || next.owner_pid != current.owner_pid || next.owner_uid != current.owner_uid
        || next.hop != current.hop + 1U || next.deadline != current.deadline
        || strcmp(next.connection_id, current.connection_id) != 0
        || (current.identity_version != CTOS_SSH_ROLE_IDENTITY_V1
            && current.identity_version != CTOS_SSH_ROLE_IDENTITY_V2)
        || next.identity_version != current.identity_version
        || strcmp(next.chain_identity, current.chain_identity) != 0
        || memcmp(next.connection_binding, current.connection_binding, 32U) != 0) {
        errno = EACCES; goto failed;
    }
    if (client->server_alive_interval != 0 && now_millis(&client->server_alive_last_activity) != 0)
        goto failed;
    client->jump_next_role = next;
    client->jump_mode = 1;
    if (shell_live(client) != 0) goto failed;
    int guarded = client->jump_effect_generation != 0;
    uint64_t current_time;
    if (guarded) {
        if (auth_effect_upstream_open(client) != 0 || now_millis(&current_time) != 0) goto failed;
        if (current_time >= client->jump_open_deadline) { errno = ETIMEDOUT; goto failed; }
        if (effect_acquire_for_client(client) != 0) goto failed;
    } else if (stop_effect_admit(client) != 0) goto failed;
    /* No local listener exists; use the pinned client's synthetic origin. */
    int packet_error = sshpkt_start(client->ssh, SSH2_MSG_CHANNEL_OPEN) != 0
        || sshpkt_put_cstring(client->ssh, "direct-tcpip") != 0
        || sshpkt_put_u32(client->ssh, 0) != 0
        || sshpkt_put_u32(client->ssh, 65536U) != 0
        || sshpkt_put_u32(client->ssh, SHELL_PACKET_MAX) != 0
        || sshpkt_put_cstring(client->ssh, next.host) != 0
        || sshpkt_put_u32(client->ssh, next.port) != 0
        || sshpkt_put_cstring(client->ssh, "127.0.0.1") != 0
        || sshpkt_put_u32(client->ssh, 65535U) != 0;
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
    if (guarded && !packet_error && test_before_jump_send != NULL) {
        void (*hook)(void *) = test_before_jump_send;
        void *context = test_before_jump_send_context;
        test_before_jump_send = NULL;
        test_before_jump_send_context = NULL;
        hook(context);
    }
#endif
    int expired = guarded && !packet_error
            && (now_millis(&current_time) != 0 || current_time >= client->jump_open_deadline);
    if (!packet_error && !expired) packet_error = sshpkt_send(client->ssh) != 0;
    size_t output_length = 0;
    if (!packet_error && !expired) {
        (void)ssh_output_ptr(client->ssh, &output_length);
        packet_error = output_length > SHELL_OUTPUT_MAX || ssh_packet_is_rekeying(client->ssh);
    }
    if (expired || packet_error) (void)sshpkt_start(client->ssh, SSH2_MSG_IGNORE);
    if (guarded) ctos_ssh_effect_release();
    if (expired || packet_error) {
        terminal_error(client, expired ? ETIMEDOUT : EIO); goto failed;
    }
    client->shell.phase = CTOS_SSH_SHELL_OPENING;
    client->local_window = 65536U;
    if (shell_live(client) != 0) goto failed;
    wipe_bytes(&current, sizeof(current)); wipe_bytes(&next, sizeof(next)); return 0;
failed:
    error = errno == 0 ? EIO : errno;
    wipe_bytes(&current, sizeof(current)); wipe_bytes(&next, sizeof(next));
    errno = error; return -1;
}

int ctos_ssh_native_client_begin_jump_guarded(struct ctos_ssh_native_client *client,
    ctos_ssh_role_token next_role, uint64_t hop_deadline, uint32_t generation) {
    uint32_t current_generation;
    uint64_t current_time;
    if (client_owner(client) != 0) return -1;
    if (hop_deadline == 0 || hop_deadline > client->deadline || client->jump_effect_generation != 0
        || client->upstream_relay != NULL || client->wire_effect_phase != WIRE_EFFECT_NONE
        || client->closed || client->failed || !client->completed || client->ssh == NULL
        || client->ssh->kex == NULL || !client->ssh->kex->done
        || (client->ssh->kex->flags & KEX_INITIAL) != 0 || ssh_packet_is_rekeying(client->ssh)
        || ctos_ssh_effect_snapshot(&current_generation) != 0 || current_generation != generation
        || now_millis(&current_time) != 0 || current_time >= hop_deadline) {
        errno = ETIMEDOUT;
        return -1;
    }
    client->jump_effect_generation = generation;
    client->jump_open_deadline = hop_deadline;
    client->wire_effect_parent = client;
    client->wire_effect_next_role = next_role;
    client->wire_effect_generation = generation;
    client->wire_effect_deadline = hop_deadline;
    client->wire_effect_phase = WIRE_EFFECT_CONNECTING;
    int result = ctos_ssh_native_client_begin_jump(client, next_role);
    client->jump_effect_generation = 0;
    client->jump_open_deadline = 0;
    if (result != 0) {
        wire_effect_reset(client);
        client->failed = 1;
    }
    return result;
}

int ctos_ssh_native_client_shell_status(const struct ctos_ssh_native_client *client,
    struct ctos_ssh_native_shell_status *status) {
    if (status != NULL) memset(status, 0, sizeof(*status));
    if (status == NULL) { errno = EINVAL; return -1; }
    if (client != NULL && client->jump_mode) { errno = EACCES; return -1; }
    if (shell_proof(client) != 0) return -1;
    *status = client->shell;
    if (client->upstream_relay != NULL && shell_proof(client) != 0) {
        memset(status, 0, sizeof(*status));
        return -1;
    }
    return 0;
}

static int channel_write(struct ctos_ssh_native_client *client,
    const unsigned char *bytes, size_t length, size_t *accepted, int jump) {
    size_t count;
    if (accepted != NULL) *accepted = 0;
    if (bytes == NULL || accepted == NULL || length == 0 || length > SHELL_PACKET_MAX) {
        errno = EINVAL; return -1;
    }
    if (client != NULL && !!client->jump_mode != jump) { errno = EACCES; return -1; }
    if (shell_live(client) != 0) return -1;
    if (client->shell.phase != CTOS_SSH_SHELL_RUNNING || client->shell.local_eof
        || client->remote_close) { errno = EACCES; return -1; }
    count = length < client->remote_window ? length : client->remote_window;
    if (count > client->remote_maxpacket) count = client->remote_maxpacket;
    if (count == 0 || !shell_room(client, count + 256U)) return 0;
    int permit = wire_effect_acquire(client);
    if (permit < 0) return terminal_error(client, errno);
    int packet_error;
    if (permit == 1) {
        packet_error = shell_message(client, SSH2_MSG_CHANNEL_DATA) != 0
            || sshpkt_put_string(client->ssh, bytes, count) != 0 || shell_send(client) != 0;
    } else {
        packet_error = sshpkt_start(client->ssh, SSH2_MSG_CHANNEL_DATA) != 0
            || sshpkt_put_u32(client->ssh, client->remote_channel) != 0
            || sshpkt_put_string(client->ssh, bytes, count) != 0 || sshpkt_send(client->ssh) != 0;
        size_t output_length = 0;
        if (!packet_error) {
            (void)ssh_output_ptr(client->ssh, &output_length);
            packet_error = output_length > SHELL_OUTPUT_MAX || ssh_packet_is_rekeying(client->ssh);
        }
        if (packet_error) (void)sshpkt_start(client->ssh, SSH2_MSG_IGNORE);
        ctos_ssh_effect_release();
    }
    if (packet_error) return terminal_error(client, EIO);
    if (shell_live(client) != 0) return -1;
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
    if (permit == 0) test_wire_channel_bytes += count;
#endif
    client->remote_window -= (uint32_t)count;
    *accepted = count;
    return 0;
}
int ctos_ssh_native_client_shell_write(struct ctos_ssh_native_client *client,
    const unsigned char *bytes, size_t length, size_t *accepted) {
    return channel_write(client, bytes, length, accepted, 0);
}
int ctos_ssh_native_client_jump_write(struct ctos_ssh_native_client *client,
    const unsigned char *bytes, size_t length, size_t *accepted) {
    if (client != NULL && !client->closed && client->jump_relay_used) {
        if (accepted != NULL) *accepted = 0;
        errno = EBUSY; return -1;
    }
    return channel_write(client, bytes, length, accepted, 1);
}

static int channel_read(struct ctos_ssh_native_client *client,
    unsigned char *bytes, size_t capacity, size_t *length, int jump) {
    unsigned char scratch[SHELL_PACKET_MAX];
    size_t count;
    if (length != NULL) *length = 0;
    if (bytes != NULL) wipe_bytes(bytes, capacity > SHELL_PACKET_MAX ? SHELL_PACKET_MAX : capacity);
    if (bytes == NULL || length == NULL || capacity == 0 || capacity > SHELL_PACKET_MAX) {
        errno = EINVAL; return -1;
    }
    if (client != NULL && !!client->jump_mode != jump) { errno = EACCES; return -1; }
    if (shell_live(client) != 0) return -1;
    if (!client->shell_started) { errno = EACCES; return -1; }
    count = capacity < client->shell.buffered_bytes ? capacity : client->shell.buffered_bytes;
    memcpy(scratch, client->shell_buffer, count);
    if (shell_live(client) != 0) { wipe_bytes(scratch, sizeof(scratch)); return -1; }
    memmove(client->shell_buffer, client->shell_buffer + count, client->shell.buffered_bytes - count);
    client->shell.buffered_bytes -= count;
    wipe_bytes(client->shell_buffer + client->shell.buffered_bytes, count);
    if (!client->remote_close) client->window_credit += (uint32_t)count;
    memcpy(bytes, scratch, count);
    *length = count;
    if ((jump || client->upstream_relay != NULL) && shell_live(client) != 0) {
        wipe_bytes(bytes, capacity); *length = 0;
        wipe_bytes(scratch, sizeof(scratch)); return -1;
    }
    wipe_bytes(scratch, sizeof(scratch));
    return 0;
}
int ctos_ssh_native_client_shell_read(struct ctos_ssh_native_client *client,
    unsigned char *bytes, size_t capacity, size_t *length) {
    return channel_read(client, bytes, capacity, length, 0);
}
int ctos_ssh_native_client_jump_read(struct ctos_ssh_native_client *client,
    unsigned char *bytes, size_t capacity, size_t *length) {
    if (client != NULL && !client->closed && client->jump_relay_used) {
        if (length != NULL) *length = 0;
        if (bytes != NULL) wipe_bytes(bytes, capacity > SHELL_PACKET_MAX ? SHELL_PACKET_MAX : capacity);
        errno = EBUSY; return -1;
    }
    return channel_read(client, bytes, capacity, length, 1);
}

int ctos_ssh_native_client_shell_resize(struct ctos_ssh_native_client *client,
    unsigned int columns, unsigned int rows) {
    if (columns == 0 || columns > 4096U || rows == 0 || rows > 4096U) { errno = EINVAL; return -1; }
    if (client != NULL && client->jump_mode) { errno = EACCES; return -1; }
    if (shell_live(client) != 0) return -1;
    if (client->shell.phase != CTOS_SSH_SHELL_RUNNING || client->remote_close) { errno = EACCES; return -1; }
    if (client->shell_no_pty) return 1;
    if (!shell_room(client, 512U)) return 0;
    if (shell_message(client, SSH2_MSG_CHANNEL_REQUEST) != 0
        || sshpkt_put_cstring(client->ssh, "window-change") != 0
        || sshpkt_put_u8(client->ssh, 0) != 0 || sshpkt_put_u32(client->ssh, columns) != 0
        || sshpkt_put_u32(client->ssh, rows) != 0 || sshpkt_put_u32(client->ssh, 0) != 0
        || sshpkt_put_u32(client->ssh, 0) != 0 || shell_send(client) != 0)
        return terminal_error(client, EIO);
    if (shell_live(client) != 0) return -1;
    return 1;
}

static int channel_eof(struct ctos_ssh_native_client *client, int jump) {
    if (client != NULL && !!client->jump_mode != jump) { errno = EACCES; return -1; }
    if (shell_live(client) != 0) return -1;
    if (client->shell.phase != CTOS_SSH_SHELL_RUNNING || client->shell.local_eof
        || client->remote_close) { errno = EACCES; return -1; }
    if (!shell_room(client, 256U)) return 0;
    if (shell_message(client, SSH2_MSG_CHANNEL_EOF) != 0 || shell_send(client) != 0) return -1;
    client->shell.local_eof = 1;
    if (shell_live(client) != 0) return -1;
    return 1;
}
int ctos_ssh_native_client_shell_eof(struct ctos_ssh_native_client *client) { return channel_eof(client, 0); }
int ctos_ssh_native_client_jump_eof(struct ctos_ssh_native_client *client) {
    if (client != NULL && !client->closed && client->jump_relay_used) { errno = EBUSY; return -1; }
    return channel_eof(client, 1);
}

int ctos_ssh_native_client_jump_status(const struct ctos_ssh_native_client *client,
    struct ctos_ssh_native_jump_status *status) {
    if (status != NULL) memset(status, 0, sizeof(*status));
    if (status == NULL) { errno = EINVAL; return -1; }
    if (client != NULL && !client->jump_mode) { errno = EACCES; return -1; }
    if (shell_proof(client) != 0) return -1;
    switch (client->shell.phase) {
        case CTOS_SSH_SHELL_OPENING: status->phase = CTOS_SSH_JUMP_OPENING; break;
        case CTOS_SSH_SHELL_RUNNING: status->phase = CTOS_SSH_JUMP_OPEN; break;
        case CTOS_SSH_SHELL_CLOSED: status->phase = CTOS_SSH_JUMP_CLOSED; break;
        case CTOS_SSH_SHELL_REJECTED: status->phase = CTOS_SSH_JUMP_REJECTED; break;
        default: errno = EPROTO; return -1;
    }
    status->remote_eof = client->shell.remote_eof; status->local_eof = client->shell.local_eof;
    status->buffered_bytes = client->shell.buffered_bytes;
    if (shell_proof(client) != 0) { memset(status, 0, sizeof(*status)); return -1; }
    return 0;
}

static int jump_relay_owner(const struct ctos_ssh_native_jump_relay *relay) {
    if (relay == NULL) { errno = EINVAL; return -1; }
    if (relay->pid != getpid() || relay->uid != getuid()
        || !pthread_equal(relay->owner, pthread_self())) { errno = EPERM; return -1; }
    return 0;
}

static int jump_relay_fail(struct ctos_ssh_native_jump_relay *relay, int error) {
    wipe_bytes(relay->to_channel, sizeof(relay->to_channel));
    wipe_bytes(relay->to_peer, sizeof(relay->to_peer));
    relay->channel_offset = relay->channel_length = relay->peer_offset = relay->peer_length = 0;
    relay->failed = 1;
    errno = error == 0 ? EIO : error;
    return -1;
}

static int jump_relay_live(struct ctos_ssh_native_jump_relay *relay) {
    if (jump_relay_owner(relay) != 0) return -1;
    if (relay->closed) { errno = ENOENT; return -1; }
    if (relay->failed) { errno = EALREADY; return -1; }
    if (relay->parent == NULL || relay->parent->jump_relay != relay
        || !relay->parent->jump_mode) return jump_relay_fail(relay, EACCES);
    if (shell_live(relay->parent) != 0) {
        int error = errno;
        return error == EPERM ? -1 : jump_relay_fail(relay, error);
    }
    return 0;
}

int ctos_ssh_native_jump_relay_open(struct ctos_ssh_native_client *parent, int fd,
    struct ctos_ssh_native_jump_relay **out) {
    struct sockaddr_storage local = {0}, peer = {0};
    struct stat socket_stat, parent_stat;
    socklen_t local_length = sizeof(local), peer_length = sizeof(peer);
    struct ctos_ssh_native_jump_relay *relay;
    if (out == NULL) { errno = EINVAL; return -1; }
    *out = NULL;
    if (parent == NULL || validate_descriptor(fd) != 0
        || getsockname(fd, (struct sockaddr *)&local, &local_length) != 0
        || getpeername(fd, (struct sockaddr *)&peer, &peer_length) != 0
        || fstat(fd, &socket_stat) != 0 || fstat(parent->socket_fd, &parent_stat) != 0
        || (socket_stat.st_dev == parent_stat.st_dev && socket_stat.st_ino == parent_stat.st_ino)
        || local.ss_family != AF_UNIX || peer.ss_family != AF_UNIX) {
        errno = EINVAL; return -1;
    }
    if (shell_live(parent) != 0) return -1;
    if (!parent->jump_mode || parent->shell.phase != CTOS_SSH_SHELL_RUNNING
        || parent->shell.remote_eof || parent->shell.local_eof || parent->remote_close) {
        errno = EACCES; return -1;
    }
    if (parent->jump_relay_used || parent->jump_relay != NULL) { errno = EALREADY; return -1; }
    relay = calloc(1, sizeof(*relay));
    if (relay == NULL) { errno = ENOMEM; return -1; }
    relay->parent = parent; relay->owner = pthread_self(); relay->pid = getpid(); relay->uid = getuid();
    relay->fd = fd;
    parent->jump_relay = relay; parent->jump_relay_used = 1;
    *out = relay;
    return 0;
}

int ctos_ssh_native_jump_relay_pump(struct ctos_ssh_native_jump_relay *relay) {
    struct ctos_ssh_native_client *parent;
    ssize_t count;
    size_t transferred = 0;
    int sent_eof;
    if (jump_relay_live(relay) != 0) return -1;
    parent = relay->parent;
    if (!relay->peer_eof && relay->channel_length == 0 && !parent->remote_close) {
        int permit = wire_effect_acquire(parent);
        if (permit < 0) return jump_relay_fail(relay, errno);
        count = recv(relay->fd, relay->to_channel, sizeof(relay->to_channel), MSG_DONTWAIT);
        int saved_error = errno;
        if (permit == 0) ctos_ssh_effect_release();
        errno = saved_error;
        if (count > 0) { relay->channel_offset = 0; relay->channel_length = (size_t)count; }
        else if (count == 0) relay->peer_eof = 1;
        else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR)
            return jump_relay_fail(relay, errno);
    }
    if (relay->channel_length != 0) {
        if (parent->remote_close) return jump_relay_fail(relay, EPIPE);
        if (channel_write(parent, relay->to_channel + relay->channel_offset,
                relay->channel_length, &transferred, 1) != 0)
            return jump_relay_fail(relay, errno);
        wipe_bytes(relay->to_channel + relay->channel_offset, transferred);
        relay->channel_offset += transferred; relay->channel_length -= transferred;
    }
    if (parent->remote_close && !relay->peer_eof && !relay->peer_read_shutdown) {
        count = recv(relay->fd, relay->to_channel, 1U, MSG_PEEK | MSG_DONTWAIT);
        if (count > 0) return jump_relay_fail(relay, EPIPE);
        if (count == 0) relay->peer_eof = 1;
        else if (errno == EAGAIN || errno == EWOULDBLOCK) {
            if (shutdown(relay->fd, SHUT_RD) != 0) return jump_relay_fail(relay, errno);
            relay->peer_read_shutdown = 1;
        } else if (errno != EINTR) return jump_relay_fail(relay, errno);
    }
    if (relay->peer_eof && relay->channel_length == 0 && !relay->channel_eof_sent
        && !parent->remote_close) {
        sent_eof = channel_eof(parent, 1);
        if (sent_eof < 0) return jump_relay_fail(relay, errno);
        relay->channel_eof_sent = sent_eof;
    }
    if (relay->peer_length == 0) {
        transferred = 0;
        if (channel_read(parent, relay->to_peer, sizeof(relay->to_peer), &transferred, 1) != 0)
            return jump_relay_fail(relay, errno);
        relay->peer_offset = 0; relay->peer_length = transferred;
    }
    if (relay->peer_length != 0) {
        if (jump_relay_live(relay) != 0) return -1;
        int permit = wire_effect_acquire(parent);
        if (permit < 0) return jump_relay_fail(relay, errno);
        count = send(relay->fd, relay->to_peer + relay->peer_offset,
            relay->peer_length, MSG_DONTWAIT | MSG_NOSIGNAL);
        int saved_error = errno;
        if (permit == 0) ctos_ssh_effect_release();
        errno = saved_error;
        if (count > 0) {
            wipe_bytes(relay->to_peer + relay->peer_offset, (size_t)count);
            relay->peer_offset += (size_t)count; relay->peer_length -= (size_t)count;
        } else if (count == 0) return jump_relay_fail(relay, EPIPE);
        else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR)
            return jump_relay_fail(relay, errno);
        if (jump_relay_live(relay) != 0) return -1;
    }
    if (!relay->peer_write_shutdown && relay->peer_length == 0
        && parent->shell.buffered_bytes == 0 && (parent->shell.remote_eof || parent->remote_close)) {
        if (shutdown(relay->fd, SHUT_WR) != 0) return jump_relay_fail(relay, errno);
        relay->peer_write_shutdown = 1;
    }
    return jump_relay_live(relay);
}

int ctos_ssh_native_jump_relay_close(struct ctos_ssh_native_jump_relay *relay) {
    int result, error;
    if (jump_relay_owner(relay) != 0) return -1;
    if (relay->closed) { errno = ENOENT; return -1; }
    if (relay->child != NULL) { errno = EBUSY; return -1; }
    if (relay->parent == NULL || relay->parent->jump_relay != relay) { errno = EACCES; return -1; }
    relay->parent->jump_relay = NULL;
    relay->parent = NULL;
    result = close(relay->fd); error = errno; relay->fd = -1;
    wipe_bytes(relay->to_channel, sizeof(relay->to_channel));
    wipe_bytes(relay->to_peer, sizeof(relay->to_peer));
    relay->channel_offset = relay->channel_length = relay->peer_offset = relay->peer_length = 0;
    relay->closed = 1;
    if (result != 0) { errno = error; return -1; }
    return 0;
}

int ctos_ssh_native_jump_relay_destroy(struct ctos_ssh_native_jump_relay *relay) {
    if (jump_relay_owner(relay) != 0) return -1;
    if (!relay->closed) { errno = EBUSY; return -1; }
    wipe_bytes(relay, sizeof(*relay));
    free(relay);
    return 0;
}

static int shell_text_equals(const unsigned char *bytes, size_t length, const char *text) {
    return strlen(text) == length && memcmp(bytes, text, length) == 0;
}

static int shell_discard_rest(struct ssh *ssh) {
    int remaining = ssh_packet_remaining(ssh);
    if (remaining < 0 || remaining > (int)SHELL_PACKET_MAX
        || sshpkt_get(ssh, NULL, (size_t)remaining) != 0 || sshpkt_get_end(ssh) != 0) return -1;
    return 0;
}

static int shell_packet(struct ctos_ssh_native_client *client, int type) {
    struct ssh *ssh = client->ssh;
    const unsigned char *text;
    size_t length;
    uint32_t recipient, value, maximum;
    u_char flag;
    if (type == SSH2_MSG_IGNORE || type == SSH2_MSG_DEBUG) {
        if (type == SSH2_MSG_DEBUG && sshpkt_get_u8(ssh, &flag) != 0) goto bad;
        if (packet_bounded_text(ssh, 8192U, &text, &length) != 0) goto bad;
        if (type == SSH2_MSG_DEBUG && packet_bounded_text(ssh, 128U, &text, &length) != 0) goto bad;
        if (sshpkt_get_end(ssh) != 0) goto bad;
        return 0;
    }
    if (type == SSH2_MSG_GLOBAL_REQUEST) {
        if (packet_bounded_text(ssh, 128U, &text, &length) != 0
            || sshpkt_get_u8(ssh, &flag) != 0 || flag > 1 || shell_discard_rest(ssh) != 0) goto bad;
        if (flag && (sshpkt_start(ssh, SSH2_MSG_REQUEST_FAILURE) != 0 || shell_send(client) != 0)) return -1;
        return 0;
    }
    if (type == SSH2_MSG_REQUEST_SUCCESS || type == SSH2_MSG_REQUEST_FAILURE) {
        if (client->server_alive_pending == 0
            || (type == SSH2_MSG_REQUEST_FAILURE ? sshpkt_get_end(ssh) : shell_discard_rest(ssh)) != 0)
            goto bad;
        client->server_alive_pending--;
        client->server_alive_missed = 0;
        return 0;
    }
    if (type == SSH2_MSG_CHANNEL_OPEN) {
        if (packet_bounded_text(ssh, 128U, &text, &length) != 0
            || sshpkt_get_u32(ssh, &recipient) != 0 || sshpkt_get_u32(ssh, &value) != 0
            || sshpkt_get_u32(ssh, &maximum) != 0 || shell_discard_rest(ssh) != 0) goto bad;
        if (sshpkt_start(ssh, SSH2_MSG_CHANNEL_OPEN_FAILURE) != 0
            || sshpkt_put_u32(ssh, recipient) != 0 || sshpkt_put_u32(ssh, 1) != 0
            || sshpkt_put_cstring(ssh, "") != 0 || sshpkt_put_cstring(ssh, "") != 0
            || shell_send(client) != 0) return -1;
        return 0;
    }
    if (sshpkt_get_u32(ssh, &recipient) != 0 || recipient != 0) goto bad;
    if (type == SSH2_MSG_CHANNEL_OPEN_CONFIRMATION || type == SSH2_MSG_CHANNEL_OPEN_FAILURE) {
        if (client->shell.phase != CTOS_SSH_SHELL_OPENING || !client->shell_request_drained) goto bad;
        if (type == SSH2_MSG_CHANNEL_OPEN_FAILURE) {
            if (sshpkt_get_u32(ssh, &value) != 0
                || packet_bounded_text(ssh, 8192U, &text, &length) != 0
                || packet_bounded_text(ssh, 128U, &text, &length) != 0 || sshpkt_get_end(ssh) != 0) goto bad;
            client->shell.phase = CTOS_SSH_SHELL_REJECTED;
            return 0;
        }
        if (sshpkt_get_u32(ssh, &client->remote_channel) != 0
            || sshpkt_get_u32(ssh, &client->remote_window) != 0
            || sshpkt_get_u32(ssh, &client->remote_maxpacket) != 0
            || client->remote_maxpacket == 0 || sshpkt_get_end(ssh) != 0) goto bad;
        if (client->jump_mode) {
            client->shell_started = 1;
            client->shell.phase = CTOS_SSH_SHELL_RUNNING;
            return 0;
        }
        return shell_request(client, !client->shell_no_pty);
    }
    if (client->shell.phase == CTOS_SSH_SHELL_OPENING
        || client->shell.phase == CTOS_SSH_SHELL_REJECTED || client->remote_close) goto bad;
    if (client->shell_rejected_pending && type != SSH2_MSG_CHANNEL_CLOSE) goto bad;
    if (type == SSH2_MSG_CHANNEL_SUCCESS || type == SSH2_MSG_CHANNEL_FAILURE) {
        if ((client->shell.phase != CTOS_SSH_SHELL_PTY_PENDING
                && client->shell.phase != CTOS_SSH_SHELL_SHELL_PENDING)
            || !client->shell_request_drained || sshpkt_get_end(ssh) != 0) goto bad;
        if (type == SSH2_MSG_CHANNEL_FAILURE) {
            wipe_bytes(client->shell_buffer, sizeof(client->shell_buffer));
            client->shell.buffered_bytes = 0;
            client->shell_rejected_pending = 1;
            return shell_close_message(client);
        }
        if (client->shell.phase == CTOS_SSH_SHELL_PTY_PENDING) return shell_request(client, 0);
        client->shell_started = 1;
        client->shell.phase = CTOS_SSH_SHELL_RUNNING;
        return 0;
    }
    if (type == SSH2_MSG_CHANNEL_CLOSE) {
        if (sshpkt_get_end(ssh) != 0) goto bad;
        if (!client->shell_started) {
            wipe_bytes(client->shell_buffer, sizeof(client->shell_buffer));
            client->shell.buffered_bytes = 0;
        }
        client->remote_close = 1;
        return shell_close_message(client);
    }
    if (type == SSH2_MSG_CHANNEL_WINDOW_ADJUST) {
        if (sshpkt_get_u32(ssh, &value) != 0 || value == 0
            || value > UINT32_MAX - client->remote_window || sshpkt_get_end(ssh) != 0) goto bad;
        client->remote_window += value;
        return 0;
    }
    if (type == SSH2_MSG_CHANNEL_DATA || type == SSH2_MSG_CHANNEL_EXTENDED_DATA) {
        if (client->shell.remote_eof) goto bad;
        if (client->jump_mode && type == SSH2_MSG_CHANNEL_EXTENDED_DATA) goto bad;
        if (type == SSH2_MSG_CHANNEL_EXTENDED_DATA && (sshpkt_get_u32(ssh, &value) != 0 || value != 1)) goto bad;
        if (packet_bounded_text(ssh, SHELL_PACKET_MAX, &text, &length) != 0
            || length > client->local_window || length > sizeof(client->shell_buffer) - client->shell.buffered_bytes
            || sshpkt_get_end(ssh) != 0) goto bad;
        memcpy(client->shell_buffer + client->shell.buffered_bytes, text, length);
        client->shell.buffered_bytes += length;
        client->local_window -= (uint32_t)length;
        return 0;
    }
    if (client->shell.phase != CTOS_SSH_SHELL_RUNNING) goto bad;
    if (type == SSH2_MSG_CHANNEL_EOF) {
        if (client->shell.remote_eof || sshpkt_get_end(ssh) != 0) goto bad;
        client->shell.remote_eof = 1;
        return 0;
    }
    if (type == SSH2_MSG_CHANNEL_REQUEST) {
        if (packet_bounded_text(ssh, 128U, &text, &length) != 0
            || sshpkt_get_u8(ssh, &flag) != 0 || flag > 1) goto bad;
        if (client->jump_mode) {
            if (shell_discard_rest(ssh) != 0) goto bad;
            if (flag && (shell_message(client, SSH2_MSG_CHANNEL_FAILURE) != 0 || shell_send(client) != 0)) return -1;
            return 0;
        }
        if (shell_text_equals(text, length, "exit-status")) {
            if (flag || client->shell.exit_status_known || client->shell.exit_signal
                || sshpkt_get_u32(ssh, &value) != 0 || sshpkt_get_end(ssh) != 0) goto bad;
            client->shell.exit_status = value;
            client->shell.exit_status_known = 1;
        } else if (shell_text_equals(text, length, "exit-signal")) {
            if (flag || client->shell.exit_status_known || client->shell.exit_signal
                || packet_bounded_text(ssh, 128U, &text, &length) != 0
                || sshpkt_get_u8(ssh, &flag) != 0 || flag > 1
                || packet_bounded_text(ssh, 8192U, &text, &length) != 0
                || packet_bounded_text(ssh, 128U, &text, &length) != 0 || sshpkt_get_end(ssh) != 0) goto bad;
            client->shell.exit_signal = 1;
        } else {
            if (shell_discard_rest(ssh) != 0) goto bad;
            if (flag && (shell_message(client, SSH2_MSG_CHANNEL_FAILURE) != 0 || shell_send(client) != 0)) return -1;
        }
        return 0;
    }
bad:
    return terminal_error(client, EPROTO);
}

static int shell_output(struct ctos_ssh_native_client *client) {
    size_t length;
    (void)ssh_output_ptr(client->ssh, &length);
    if (length > SHELL_OUTPUT_MAX) return terminal_error(client, EPROTO);
    if (client->remote_close || client->shell_rejected_pending
        || client->shell.phase == CTOS_SSH_SHELL_REJECTED) {
        if (client->shell.phase != CTOS_SSH_SHELL_REJECTED || client->remote_maxpacket != 0)
            return shell_close_message(client);
    } else if (client->window_credit != 0 && shell_room(client, 256U)) {
        uint32_t credit = client->window_credit;
        if (shell_message(client, SSH2_MSG_CHANNEL_WINDOW_ADJUST) != 0
            || sshpkt_put_u32(client->ssh, credit) != 0 || shell_send(client) != 0) return -1;
        client->local_window += credit;
        client->window_credit = 0;
    }
    return 0;
}

static int native_client_open(
    const struct ctos_ssh_native_client_config *config,
    struct ctos_ssh_native_client **client_out, int allow_child) {
    struct ctos_ssh_native_client *client = NULL;
    struct ctos_ssh_role_snapshot snapshot;
    struct kex_params params;
    char *proposals[PROPOSAL_MAX] = {KEX_CLIENT};
    size_t path_length;
    size_t candidate_length;
    const struct ctos_ssh_view *managed_view = NULL;
    int attached_socket = -1;
    int connection_attempted = 0;
    int failure;

    if (client_out == NULL) {
        errno = EINVAL;
        return -1;
    }
    *client_out = NULL;
    memset(&snapshot, 0, sizeof(snapshot));
    if (config == NULL || config->role == 0 || config->socket_fd < 0
        || config->owns_socket != 1 || config->callback == 0
        || config->callback_environment == NULL
        || (config->host_key_mode != CTOS_SSH_HOST_KEY_PINNED
            && config->host_key_mode != CTOS_SSH_HOST_KEY_MANAGED)
        || (config->host_key_mode == CTOS_SSH_HOST_KEY_MANAGED
            && (config->server_host_public_key_blob != NULL
                || config->server_host_public_key_length != 0
                || config->expected_host_fingerprint != NULL))
        || (config->host_key_mode == CTOS_SSH_HOST_KEY_PINNED
            && (config->server_host_public_key_blob == NULL
                || config->server_host_public_key_length == 0
                || config->server_host_public_key_length > CTOS_SSH_NATIVE_CLIENT_MAX_HOST_KEY_BLOB_BYTES
                || config->expected_host_fingerprint == NULL
                || !nonzero32(config->expected_host_fingerprint)))
        || config->known_hosts_path == NULL
        || config->host == NULL || config->candidate_id == NULL
        || config->step_budget == 0
        || config->step_budget > CTOS_SSH_NATIVE_CLIENT_MAX_STEP_BYTES
        || validate_descriptor(config->socket_fd) != 0
        || role_is_current(config, &snapshot) != 0
        || config->managed_view_borrow == NULL
        || view_borrow_is_live(config->managed_view_borrow) != 0) {
        if (errno == 0) errno = EINVAL;
        wipe_bytes(&snapshot, sizeof(snapshot));
        return -1;
    }
    client = calloc(1, sizeof(*client));
    if (client == NULL) {
        errno = ENOMEM;
        wipe_bytes(&snapshot, sizeof(snapshot));
        return -1;
    }
    client->owner = pthread_self();
    client->pid = getpid();
    client->uid = getuid();
    client->role = config->role;
    client->callback = config->callback;
    client->callback_environment = config->callback_environment;
    client->managed_view_borrow = config->managed_view_borrow;
    client->socket_fd = -1;
    client->caller_socket_fd = -1;
    client->deadline = config->deadline;
    client->step_budget = config->step_budget;
    client->host_key_mode = config->host_key_mode;
    if (ctos_ssh_view_borrow_claim(client->managed_view_borrow, client, &client->view_claim) != 0)
        goto failed;
    managed_view = ctos_ssh_view_borrow_view(client->managed_view_borrow);
    if (managed_view == NULL || view_borrow_is_live(client->managed_view_borrow) != 0
        || view_slices_are_safe(managed_view) != 0
        || bounded_text(config->known_hosts_path, 512U, &path_length) != 0
        || bounded_text(config->candidate_id,
            CTOS_SSH_CALLBACK_CANDIDATE_ID_BYTES - 1U, &candidate_length) != 0
        || ctos_ssh_view_lookup(managed_view,
            (const unsigned char *)config->known_hosts_path, path_length,
            CTOS_SSH_KNOWN_HOSTS) == NULL
        || view_is_current(config, managed_view, &snapshot, path_length) != 0) {
        if (errno == 0) errno = EINVAL;
        goto failed;
    }
    if (!allow_child && snapshot.hop != 0U) { errno = EACCES; goto failed; }
    (void)candidate_length;
    if (config->fresh_hostfile_view != NULL
        && (!allow_child || config->host_key_mode != CTOS_SSH_HOST_KEY_MANAGED
            || compatible_hostfile_view(managed_view, config->fresh_hostfile_view,
                config->deadline) != 0
            || view_is_current(config, config->fresh_hostfile_view, &snapshot, path_length) != 0
            || view_borrow_is_live(client->managed_view_borrow) != 0)) {
        if (errno == 0) errno = EACCES;
        goto failed;
    }
    client->managed_view = managed_view;
    client->hostfile_view = config->fresh_hostfile_view == NULL
        ? managed_view : config->fresh_hostfile_view;
    if (copy_authentication_selection(client, config, &snapshot) != 0) goto failed;
    if (config->host_key_mode == CTOS_SSH_HOST_KEY_PINNED) {
        memcpy(client->expected_host_fingerprint, config->expected_host_fingerprint,
            sizeof(client->expected_host_fingerprint));
        if (sshkey_from_blob(config->server_host_public_key_blob,
                config->server_host_public_key_length,
                &client->server_host_public_key) != 0
            || client->server_host_public_key == NULL) {
            errno = EINVAL;
            goto failed;
        }
        if (fingerprint_matches(client, client->server_host_public_key) != 0)
            goto failed;
    }
    if (copy_config_text(client->known_hosts_path,
            sizeof(client->known_hosts_path), config->known_hosts_path, 512U) != 0
        || copy_config_text(client->host, sizeof(client->host), config->host,
            CTOS_SSH_ROLE_HOST_BYTES) != 0
        || copy_config_text(client->candidate_id, sizeof(client->candidate_id),
            config->candidate_id, CTOS_SSH_CALLBACK_CANDIDATE_ID_BYTES - 1U) != 0)
        goto failed;
    memset(&params, 0, sizeof(params));
    memcpy(params.proposal, proposals, sizeof(proposals));
    if (ssh_init(&client->ssh, 0, &params) != 0 || client->ssh == NULL)
        goto failed;
    if (config->host_key_mode == CTOS_SSH_HOST_KEY_MANAGED) {
        if (ctos_ssh_kex_bind_pending_host(client->ssh, client->role) != 0) goto failed;
    } else if (ssh_add_hostkey(client->ssh, client->server_host_public_key) != 0
        || ctos_ssh_kex_bind_role(client->ssh, client->role,
            client->expected_host_fingerprint) != 0) goto failed;
    if (ssh_set_verify_host_key_callback(client->ssh, verify_host_key) != 0)
        goto failed;
    attached_socket = fcntl(config->socket_fd, F_DUPFD_CLOEXEC, 0);
    if (attached_socket < 0 || validate_descriptor(attached_socket) != 0)
        goto failed;
    connection_attempted = 1;
    if (ssh_packet_set_connection(client->ssh, attached_socket,
            attached_socket) == NULL) {
        connection_attempted = ssh_packet_get_connection_in(client->ssh)
            == attached_socket;
        goto failed;
    }
    ssh_packet_set_nonblocking(client->ssh);
    ssh_set_app_data(client->ssh, client);
    client->socket_fd = attached_socket;
    client->caller_socket_fd = config->socket_fd;
    attached_socket = -1;
    if (ctos_ssh_callback_client_claim_scoped_effect(client->callback, client->callback_environment,
            client->role, client, callback_ancestor_scope_check,
            &client->admit_effect, &client->effect_context) != 0) goto failed;
    /* Metadata-only publication cannot fail after callback ownership transfer. */
    register_scope_client(client);
    wipe_bytes(&snapshot, sizeof(snapshot));
    *client_out = client;
    return 0;

failed:
    /* The callback binding is borrowed, not consumed by open.  If any
     * admission or OpenSSH setup step fails, the caller still owns the live
     * token and must close it on this event-loop thread/environment. */
    failure = errno == 0 ? EIO : errno;
    if (client != NULL && client->ssh != NULL) {
        ctos_ssh_kex_unbind(client->ssh);
        if (connection_attempted) attached_socket = -1;
        ssh_free(client->ssh);
        client->ssh = NULL;
    }
    if (client != NULL && client->server_host_public_key != NULL) {
        sshkey_free(client->server_host_public_key);
        client->server_host_public_key = NULL;
    }
    if (attached_socket >= 0) close(attached_socket);
    if (client != NULL) {
        clear_authentication_selection(client);
        client->socket_fd = -1;
        client->caller_socket_fd = -1;
        /* The unpublished owner's private token makes this rollback no-fail.
         * It is the last borrow access: release may follow immediately. */
        ctos_ssh_view_borrow_claim_abort(client->view_claim);
        wipe_bytes(client, sizeof(*client));
        free(client);
    }
    wipe_bytes(&snapshot, sizeof(snapshot));
    errno = failure;
    return -1;
}

int ctos_ssh_native_client_open(const struct ctos_ssh_native_client_config *config,
    struct ctos_ssh_native_client **client_out) {
    return native_client_open(config, client_out, 0);
}

int ctos_ssh_native_client_open_jump_child(struct ctos_ssh_native_client *parent,
    const struct ctos_ssh_native_client_config *config,
    struct ctos_ssh_native_client **child_out,
    struct ctos_ssh_native_jump_relay **relay_out) {
    struct ctos_ssh_native_client_config child_config;
    struct ctos_ssh_native_client *child = NULL;
    struct ctos_ssh_native_jump_relay *relay = NULL;
    int pair[2] = {-1, -1};
    const struct ctos_ssh_native_client *ancestor;
    int error;

    if (child_out != NULL) *child_out = NULL;
    if (relay_out != NULL) *relay_out = NULL;
    if (parent == NULL || config == NULL || child_out == NULL || relay_out == NULL
        || config->socket_fd != -1 || config->owns_socket != 1) {
        errno = EINVAL;
        return -1;
    }
    if (shell_live(parent) != 0) return -1;
    if (!parent->jump_mode || parent->jump_next_role.token != config->role
        || parent->shell.phase != CTOS_SSH_SHELL_RUNNING || parent->remote_close
        || parent->shell.remote_eof || parent->shell.local_eof) {
        errno = EACCES;
        return -1;
    }
    for (ancestor = parent; ancestor != NULL;
            ancestor = ancestor->upstream_relay == NULL ? NULL : ancestor->upstream_relay->parent) {
        if (config->managed_view_borrow != NULL
            && config->managed_view_borrow == ancestor->managed_view_borrow) {
            errno = EBUSY;
            return -1;
        }
    }
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, pair) != 0)
        return -1;
    child_config = *config;
    child_config.socket_fd = pair[1];
    if (native_client_open(&child_config, &child, 1) != 0) goto failed;
    pair[1] = -1;
    if (ctos_ssh_native_jump_relay_open(parent, pair[0], &relay) != 0) {
        error = errno == 0 ? EIO : errno;
        (void)close(pair[0]);
        child->failed = 1;
        *child_out = child;
        errno = error;
        return -1;
    }
    pair[0] = -1;
    child->upstream_relay = relay;
    relay->child = child;
    if (parent->wire_effect_phase != WIRE_EFFECT_NONE) {
        if (parent->wire_effect_phase != WIRE_EFFECT_CONNECTING || parent->wire_effect_parent != parent
                || parent->wire_effect_next_role != child->role) {
            error = EACCES;
            child->failed = 1;
            *child_out = child;
            *relay_out = relay;
            errno = error;
            return -1;
        }
        child->wire_effect_parent = parent;
        child->wire_effect_next_role = parent->wire_effect_next_role;
        child->wire_effect_generation = parent->wire_effect_generation;
        child->wire_effect_deadline = parent->wire_effect_deadline;
        child->wire_effect_phase = parent->wire_effect_phase;
    }
    *child_out = child;
    *relay_out = relay;
    if (upstream_proof(child) != 0) {
        error = errno == 0 ? EACCES : errno;
        child->failed = 1;
        errno = error;
        return -1;
    }
    return 0;

failed:
    error = errno == 0 ? EIO : errno;
    if (pair[0] >= 0) (void)close(pair[0]);
    if (pair[1] >= 0) (void)close(pair[1]);
    errno = error;
    return -1;
}

static int server_alive_tick(struct ctos_ssh_native_client *client) {
    uint64_t current, interval_millis;
    if (client->server_alive_interval == 0 || client->remote_close || client->local_close)
        return 0;
    if (now_millis(&current) != 0 || current < client->server_alive_last_activity)
        return terminal_error(client, EIO);
    interval_millis = (uint64_t)client->server_alive_interval * 1000U;
    if (current - client->server_alive_last_activity < interval_millis) return 0;
    if (client->server_alive_missed >= client->server_alive_count_max)
        return terminal_error(client, ETIMEDOUT);
    if (!shell_room(client, 256U) || ssh_packet_is_rekeying(client->ssh)) return 0;
    if (client->server_alive_pending == UINT_MAX) return terminal_error(client, EOVERFLOW);
    if (sshpkt_start(client->ssh, SSH2_MSG_GLOBAL_REQUEST) != 0
        || sshpkt_put_cstring(client->ssh, "keepalive@openssh.com") != 0
        || sshpkt_put_u8(client->ssh, 1) != 0)
        return terminal_error(client, EIO);
    if (shell_send(client) != 0) return -1;
    client->server_alive_missed++;
    client->server_alive_pending++;
    client->server_alive_last_activity = current;
    return 0;
}

int ctos_ssh_native_client_step(
    struct ctos_ssh_native_client *client,
    struct ctos_ssh_native_client_step *step) {
    unsigned char input[CTOS_SSH_NATIVE_CLIENT_MAX_STEP_BYTES];
    const unsigned char *output;
    size_t output_length;
    size_t read_length;
    size_t write_length;
    ssize_t read_result;
    ssize_t write_result;
    uint64_t current;
    u_char packet_type = SSH_MSG_NONE;
    int view_error;
    int cancelled = 0;
    int invalid_pipe = 0;
    int packet_result;
    int result = -1;

    memset(input, 0, sizeof(input));
    if (step != NULL) {
        cancelled = step->cancelled;
        invalid_pipe = step->input_length != 0 || step->output_capacity != 0
            || (step->input_length != 0 && step->input == NULL)
            || (step->output_capacity != 0 && step->output == NULL);
        memset(step, 0, sizeof(*step));
    }
    if (client == NULL || step == NULL || invalid_pipe) {
        errno = EINVAL;
        goto out;
    }
    if (client->closed) {
        errno = ENOENT;
        goto out;
    }
    if (client->failed || client->provisional_new_state == 2 || (client->auth_phase == AUTH_FINISHED
            && client->shell.phase == CTOS_SSH_SHELL_DISABLED)
        || client->shell.phase == CTOS_SSH_SHELL_CLOSED
        || (client->service_phase == USERAUTH_SERVICE_READY
            && client->auth_phase == AUTH_DISABLED)
        || (client->completed && client->service_phase == USERAUTH_SERVICE_DISABLED
            && client->wire_effect_phase != WIRE_EFFECT_CONNECTING)) {
        errno = EALREADY;
        goto out;
    }
    if (client->upstream_relay != NULL) {
        int owner_result = client->established
            ? ctos_ssh_callback_check_transport(client->callback, client->callback_environment, client)
            : ctos_ssh_callback_check_live(client->callback, client->callback_environment);
        if (owner_result != 0 || upstream_proof(client) != 0) {
            int error = errno == 0 ? EACCES : errno;
            if (error != EPERM) (void)terminal_error(client, error);
            goto out;
        }
    }
    if (client->established) {
        if (established_live(client, cancelled) != 0) goto out;
    } else if (client->auth_phase != AUTH_DISABLED) {
        if (auth_is_live(client, cancelled) != 0) goto out;
    } else if (client->service_phase != USERAUTH_SERVICE_DISABLED) {
        if (service_is_live(client, cancelled) != 0) goto out;
    } else {
        if (view_borrow_is_live(client->managed_view_borrow) != 0) {
            view_error = errno == 0 ? EACCES : errno;
            result = terminal_error(client, view_error);
            goto out;
        }
        if (cancelled) {
            result = terminal_error(client, ECANCELED);
            goto out;
        }
        if (now_millis(&current) != 0 || current >= client->deadline) {
            result = terminal_error(client, ETIMEDOUT);
            goto out;
        }
    }
    if (client->completed && client->service_phase == USERAUTH_SERVICE_DISABLED
        && client->wire_effect_phase == WIRE_EFFECT_CONNECTING) {
        int promoted = wire_effect_promote_kex(client);
        if (promoted < 0) {
            result = terminal_error(client, errno == 0 ? EACCES : errno);
            goto out;
        }
        result = 0;
        goto out;
    }
    if (client->jump_mode && shell_live(client) != 0) goto out;
    if (client->shell.phase != CTOS_SSH_SHELL_DISABLED) {
        (void)ssh_output_ptr(client->ssh, &output_length);
        if (client->shell.phase == CTOS_SSH_SHELL_REJECTED && output_length == 0) {
            errno = EALREADY; goto out;
        }
        if (shell_output(client) != 0) goto out;
        if (!shell_room(client, 1024U)) goto transport_write;
        if (sshbuf_len(ssh_packet_get_input(client->ssh)) > SHELL_INPUT_MAX) {
            result = terminal_error(client, EPROTO); goto out;
        }
    }
    read_length = client->step_budget;
    if (client->shell.phase != CTOS_SSH_SHELL_DISABLED) {
        size_t pending = sshbuf_len(ssh_packet_get_input(client->ssh));
        if (read_length > SHELL_INPUT_MAX - pending) read_length = SHELL_INPUT_MAX - pending;
    }
    if (client->shell_transport_eof || read_length == 0) goto parse_packet;
    if (read_length > sizeof(input)) read_length = sizeof(input);
    read_result = recv(client->socket_fd, input, read_length, MSG_DONTWAIT);
    if (read_result > 0) {
        step->bytes_read = (size_t)read_result;
        if ((client->established || client->jump_mode) && client->server_alive_interval != 0
            && now_millis(&client->server_alive_last_activity) != 0) {
            result = terminal_error(client, EIO);
            goto out;
        }
        if (ssh_input_append(client->ssh, input, (size_t)read_result) != 0) {
            result = terminal_error(client, EIO);
            goto out;
        }
        step->input_appended = (size_t)read_result;
    } else if (read_result == 0) {
        if (client->shell.phase != CTOS_SSH_SHELL_DISABLED) client->shell_transport_eof = 1;
        else { result = terminal_error(client, EPIPE); goto out; }
    } else if (errno == EAGAIN || errno == EWOULDBLOCK) {
        step->read_would_block = 1;
    } else if (errno != EINTR) {
        result = terminal_error(client, errno == ECONNRESET ? ECONNRESET : EIO);
        goto out;
    }
parse_packet:
    client->in_transport_step = 1;
    packet_result = ssh_packet_next(client->ssh, &packet_type);
    client->in_transport_step = 0;
    if (upstream_proof(client) != 0) {
        int error = errno == 0 ? EACCES : errno;
        if (error != EPERM) (void)terminal_error(client, error);
        goto out;
    }
    if (packet_result != 0) {
        result = terminal_error(client, EIO);
        goto out;
    }
    step->packet_type = packet_type;
    if (client->shell.phase != CTOS_SSH_SHELL_DISABLED && packet_type == SSH_MSG_NONE) {
        if (client->shell_transport_eof && !client->remote_close) {
            result = terminal_error(client, EPIPE); goto out;
        }
        if (read_length == 0 && !client->shell_transport_eof) {
            result = terminal_error(client, EPROTO); goto out;
        }
    }
    if (packet_type == SSH2_MSG_EXT_INFO
        && kex_input_ext_info(packet_type, 0, client->ssh) != 0) {
        result = terminal_error(client, EPROTO);
        goto out;
    }
    if (client->shell.phase != CTOS_SSH_SHELL_DISABLED
        && packet_type != SSH_MSG_NONE) {
        result = shell_packet(client, packet_type);
        if (result != 0) goto out;
    } else if (client->auth_phase != AUTH_DISABLED
        && packet_type != SSH_MSG_NONE && packet_type != SSH2_MSG_EXT_INFO) {
        result = auth_packet(client, packet_type);
        if (result != 0 || client->auth_phase == AUTH_FINISHED) goto out;
    } else if (packet_type == SSH2_MSG_SERVICE_ACCEPT
        && client->service_phase != USERAUTH_SERVICE_DISABLED) {
        result = accept_userauth_service(client);
        goto out;
    } else if (packet_type != SSH_MSG_NONE && packet_type != SSH2_MSG_EXT_INFO) {
        result = terminal_error(client, EPROTO);
        goto out;
    }
    if (client->shell.phase != CTOS_SSH_SHELL_DISABLED && server_alive_tick(client) != 0)
        goto out;
transport_write:
    if (upstream_proof(client) != 0) {
        int error = errno == 0 ? EACCES : errno;
        if (error != EPERM) (void)terminal_error(client, error);
        goto out;
    }
    if (client->shell.phase != CTOS_SSH_SHELL_DISABLED
        && (established_live(client, cancelled) != 0 || shell_output(client) != 0)) goto out;
    output = ssh_output_ptr(client->ssh, &output_length);
    if (output == NULL || output_length == 0) {
        goto completion;
    }
    if (client->shell.phase != CTOS_SSH_SHELL_DISABLED && established_live(client, cancelled) != 0) goto out;
    write_length = output_length < client->step_budget
        ? output_length : client->step_budget;
    int wire_permit = wire_effect_acquire(client);
    if (wire_permit < 0) { result = terminal_error(client, errno); goto out; }
    write_result = send(client->socket_fd, output, write_length, MSG_DONTWAIT | MSG_NOSIGNAL);
    int saved_write_error = errno;
    if (wire_permit == 0) ctos_ssh_effect_release();
    errno = saved_write_error;
    if (write_result > 0) {
        step->bytes_written = (size_t)write_result;
        if ((size_t)write_result < output_length) step->partial_write = 1;
        if (ssh_output_consume(client->ssh, (size_t)write_result) != 0) {
            result = terminal_error(client, EIO);
            goto out;
        }
        step->output_consumed = (size_t)write_result;
    } else if (write_result < 0
        && (errno == EAGAIN || errno == EWOULDBLOCK)) {
        step->write_would_block = 1;
    } else if (write_result < 0 && errno == EINTR) {
        result = 0;
        goto out;
    } else {
        result = terminal_error(client, write_result == 0 ? EPIPE : EIO);
        goto out;
    }
completion:
    /* Inbound NEWKEYS may precede our queued NEWKEYS/EXT_INFO drain. Keep
     * bounded transport steps available until that output reaches the socket. */
    (void)ssh_output_ptr(client->ssh, &output_length);
    if (client->shell.phase != CTOS_SSH_SHELL_DISABLED && output_length == 0) {
        client->shell_request_drained = 1;
        if (client->shell_rejected_pending && client->local_close)
            client->shell.phase = CTOS_SSH_SHELL_REJECTED;
        else if (client->remote_close && client->local_close)
            client->shell.phase = CTOS_SSH_SHELL_CLOSED;
    }
    if (client->service_phase == USERAUTH_SERVICE_REQUEST_PENDING && output_length == 0)
        client->service_phase = USERAUTH_SERVICE_AWAIT_ACCEPT;
    if (client->auth_phase == AUTH_REQUEST_PENDING && output_length == 0)
        client->auth_phase = AUTH_AWAIT_REPLY;
    if (!client->completed && client->ssh->kex->done && output_length == 0
        && completion_digest(client) != 0) {
        result = terminal_error(client, EIO);
        goto out;
    }
    if (client->completed && client->upstream_relay != NULL
        && client->wire_effect_phase == WIRE_EFFECT_CONNECTING
        && wire_effect_promote_kex(client) < 0) {
        result = terminal_error(client, errno == 0 ? EACCES : errno);
        goto out;
    }
    if (client->shell.phase != CTOS_SSH_SHELL_DISABLED && established_live(client, cancelled) != 0) goto out;
    result = 0;
out:
    if (result == 0 && client != NULL && upstream_proof(client) != 0) {
        int error = errno == 0 ? EACCES : errno;
        if (error != EPERM) (void)terminal_error(client, error);
        result = -1;
    }
    if (result != 0 && client != NULL && client->upstream_relay != NULL && step != NULL)
        memset(step, 0, sizeof(*step));
    wipe_bytes(input, sizeof(input));
    return result;
}

int ctos_ssh_native_client_verify_host_key(
    struct ctos_ssh_native_client *client, const void *host_key_blob,
    size_t host_key_blob_length) {
    struct sshkey *host_key = NULL;
    int result;

    if (client != NULL && client->host_key_mode == CTOS_SSH_HOST_KEY_MANAGED) {
        errno = EACCES;
        return -1;
    }
    if (client == NULL || host_key_blob == NULL || host_key_blob_length == 0
        || host_key_blob_length > CTOS_SSH_NATIVE_CLIENT_MAX_HOST_KEY_BLOB_BYTES
        || sshkey_from_blob(host_key_blob, host_key_blob_length, &host_key) != 0
        || host_key == NULL) {
        errno = EINVAL;
        return -1;
    }
    result = verify_host_key(host_key, client->ssh);
    sshkey_free(host_key);
    return result;
}

int ctos_ssh_native_client_complete(
    const struct ctos_ssh_native_client *client) {
    return client != NULL && !client->closed && client->completed
        && (client->upstream_relay == NULL || client->wire_effect_phase != WIRE_EFFECT_CONNECTING)
        && child_publication_live(client) == 0;
}

int ctos_ssh_native_client_provisional_new_ready(struct ctos_ssh_native_client *client,
    int *ready) {
    uint64_t current;
    int permit, drained, error;
    struct ctos_ssh_role_snapshot snapshot = {0};
    if (ready != NULL) *ready = 0;
    if (client == NULL || ready == NULL) { errno = EINVAL; return -1; }
    if (client_owner(client) != 0) return -1;
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed) { errno = EALREADY; return -1; }
    if (client->provisional_new_state < 1 || client->provisional_new_state > 2) {
        errno = EACCES; return -1;
    }
    if (client->upstream_relay == NULL
        || client->upstream_relay->parent == NULL
        || client->upstream_relay->parent->upstream_relay != NULL
        || client->completed || client->completion_calls != 0
        || !client->candidate_handed_off || client->server_host_public_key == NULL
        || client->ssh == NULL || client->ssh->kex == NULL) return terminal_error(client, EACCES);
    if (ctos_ssh_callback_check_live(client->callback, client->callback_environment) != 0
        || view_borrow_is_live(client->managed_view_borrow) != 0 || upstream_proof(client) != 0) {
        error = errno == 0 ? EACCES : errno;
        return error == EPERM ? -1 : terminal_error(client, error);
    }
    if (ctos_ssh_role_bootstrap_snapshot(client->role, &snapshot) != 0)
        return terminal_error(client, errno == 0 ? EACCES : errno);
    int exact_hop = snapshot.token == client->role && snapshot.hop == 1U;
    wipe_bytes(&snapshot, sizeof(snapshot));
    if (!exact_hop) return terminal_error(client, EACCES);
    if (now_millis(&current) != 0) return terminal_error(client, EIO);
    if (current >= client->deadline) return terminal_error(client, ETIMEDOUT);
    if (auth_effect_upstream_open(client) != 0) return -1;
    if (client->provisional_new_state == 2 && !client->ssh->kex->done)
        return terminal_error(client, EACCES);
    permit = wire_effect_acquire(client);
    if (permit < 0) return terminal_error(client, errno == 0 ? EACCES : errno);
    if (client->provisional_new_state != 2) {
        if (permit == 0) ctos_ssh_effect_release();
        return 0;
    }
    drained = wire_effect_outbound_drained(client, client->upstream_relay->parent);
    error = errno;
    if (permit == 0) ctos_ssh_effect_release();
    /* The permit must be released before terminal_error can abort a managed
     * candidate and enter the callback's lifecycle locks. */
    if (drained < 0) return terminal_error(client, error == 0 ? EIO : error);
    if (ctos_ssh_callback_check_live(client->callback, client->callback_environment) != 0) {
        error = errno == 0 ? EACCES : errno;
        return error == EPERM ? -1 : terminal_error(client, error);
    }
    if (auth_effect_upstream_open(client) != 0) return -1;
    *ready = drained == 0;
    return 0;
}

static ssize_t provisional_peek(int fd, unsigned char *byte, int peer) {
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
    if (test_provisional_peek_failure == peer) {
        test_provisional_peek_failure = 0;
        errno = ECONNRESET;
        return -1;
    }
#else
    (void)peer;
#endif
    return recv(fd, byte, 1U, MSG_PEEK | MSG_DONTWAIT);
}

/* Never use ssh_packet_next/read_poll2 here: upstream may dispatch a rekey,
 * change authentication state, or block writing a malformed-packet reply. */
__attribute__((visibility("hidden")))
int ctos_ssh_native_client_provisional_new_poll_idle(struct ctos_ssh_native_client *client,
    int *idle) {
    struct ctos_ssh_native_client *parent;
    struct ctos_ssh_native_jump_relay *relay;
    struct ctos_ssh_role_snapshot snapshot = {0};
    unsigned char byte;
    ssize_t count;
    int ready = 0, pending_parent = 0, error = 0;

    if (idle != NULL) *idle = 0;
    if (client == NULL || idle == NULL) { errno = EINVAL; return -1; }
    if (client_owner(client) != 0) return -1;
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed) { errno = EALREADY; return -1; }
    if (client->provisional_new_state != 2 || client->wire_effect_phase != WIRE_EFFECT_CONNECTING) {
        errno = EACCES; return -1;
    }
    if (wire_effect_pair(client, &parent) != 0) return terminal_error(client, EACCES);
    relay = client->upstream_relay;
    if (ctos_ssh_native_client_provisional_new_ready(client, &ready) != 0) return -1;
    if (ctos_ssh_role_bootstrap_snapshot(client->role, &snapshot) != 0)
        return terminal_error(client, errno == 0 ? EACCES : errno);
    int valid_kex = snapshot.hop == 1U && snapshot.token == client->role
        && client->ssh != NULL && client->ssh->kex != NULL
        && client->ssh->kex->done && (client->ssh->kex->flags & KEX_INITIAL) == 0
        && !ssh_packet_is_rekeying(client->ssh)
        && ctos_ssh_kex_ready(client->ssh, snapshot.hop,
            snapshot.role_nonce, snapshot.connection_binding) == 1U
        && client->service_phase == USERAUTH_SERVICE_DISABLED && client->auth_phase == AUTH_DISABLED
        && client->shell.phase == CTOS_SSH_SHELL_DISABLED && !client->selected_credential_attempted;
    wipe_bytes(&snapshot, sizeof(snapshot));
    if (!valid_kex) return terminal_error(client, EACCES);

    /* In non-AAD modes OpenSSH consumes the first cipher block before the
     * packet is complete, leaving it in incoming_packet, not input. AAD
     * packets remain in input. Neither buffer may be ignored. */
    if (sshbuf_len(ssh_packet_get_input(client->ssh)) != 0
        || ssh_packet_remaining(client->ssh) != 0 || relay->peer_length != 0
        || parent->shell.buffered_bytes != 0) return terminal_error(client, EPROTO);
    if (sshbuf_len(ssh_packet_get_input(parent->ssh)) != 0
        || ssh_packet_remaining(parent->ssh) != 0) pending_parent = 1;

    if (wire_effect_acquire(client) != 0) return terminal_error(client, errno == 0 ? EACCES : errno);
    count = provisional_peek(client->socket_fd, &byte, 1);
    if (count > 0) error = EPROTO;
    else if (count == 0) error = EPIPE;
    else if (errno != EAGAIN && errno != EWOULDBLOCK) error = errno == 0 ? EIO : errno;
    if (error == 0) {
        count = provisional_peek(parent->socket_fd, &byte, 2);
        if (count > 0) pending_parent = 1;
        else if (count == 0) error = EPIPE;
        else if (errno != EAGAIN && errno != EWOULDBLOCK) error = errno == 0 ? EIO : errno;
    }
    ctos_ssh_effect_release();
    if (error != 0) return terminal_error(client, error);
    /* Recheck revocation and drain after releasing the short native permit.
     * This remains a snapshot, not admission for any later trust write. */
    if (ctos_ssh_native_client_provisional_new_ready(client, &ready) != 0) return -1;
    *idle = ready && !pending_parent;
    return 0;
}

static int provisional_extension_name(const unsigned char *name, size_t length, const char *expected) {
    size_t expected_length = strlen(expected);
    return length == expected_length && memcmp(name, expected, length) == 0;
}

/* The provisional transport is retired after review; do not copy extension
 * values into KEX or authentication state. */
static int provisional_discard_ext_info(struct ctos_ssh_native_client *client) {
    const unsigned char *name, *value;
    size_t name_length, value_length;
    uint32_t count;
    if (!client->provisional_ext_advertised || client->provisional_ext_seen
        || client->ssh->kex->ext_info_received != 0
        || sshpkt_get_u32(client->ssh, &count) != 0 || count >= 1024U) return -1;
    for (uint32_t i = 0; i < count; i++) {
        if (sshpkt_get_string_direct(client->ssh, &name, &name_length) != 0
            || name_length == 0 || memchr(name, '\0', name_length) != NULL) return -1;
        int text_value = provisional_extension_name(name, name_length, "server-sig-algs")
            || provisional_extension_name(name, name_length, "publickey-hostbound@openssh.com")
            || provisional_extension_name(name, name_length, "ping@openssh.com")
            || provisional_extension_name(name, name_length, "agent-forward");
        if (sshpkt_get_string_direct(client->ssh, &value, &value_length) != 0
            || (text_value && memchr(value, '\0', value_length) != NULL)) return -1;
    }
    if (sshpkt_get_end(client->ssh) != 0) return -1;
    client->provisional_ext_seen = 1;
    return 0;
}

#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
static void provisional_test_hook(struct ctos_ssh_native_client *client, int stage) {
    if (client->test_provisional_hook != NULL && client->test_provisional_hook_stage == stage) {
        void (*hook)(void *) = client->test_provisional_hook;
        void *context = client->test_provisional_hook_context;
        client->test_provisional_hook = NULL;
        client->test_provisional_hook_context = NULL;
        client->test_provisional_hook_stage = 0;
        hook(context);
    }
}
#endif

/* This samples a guarded child and consumes at most one authenticated
 * EXT_INFO; the caller must pump the parent/relay separately. */
__attribute__((visibility("hidden")))
int ctos_ssh_native_client_provisional_new_poll_ext(struct ctos_ssh_native_client *client,
    int *idle) {
    struct ctos_ssh_native_client *parent;
    struct ctos_ssh_native_jump_relay *relay;
    struct ctos_ssh_role_snapshot snapshot = {0};
    unsigned char input[PROVISIONAL_READ_MAX], byte;
    u_char type = SSH_MSG_NONE;
    size_t pending, capacity;
    ssize_t count = -1, peek;
    int ready = 0, pending_parent = 0, pending_child = 0, error = 0, result = -1;

    memset(input, 0, sizeof(input));
    if (idle != NULL) *idle = 0;
    if (client == NULL || idle == NULL) { errno = EINVAL; goto out; }
    if (client_owner(client) != 0) goto out;
    if (client->closed) { errno = ENOENT; goto out; }
    if (client->failed) { errno = EALREADY; goto out; }
    if (client->provisional_new_state != 2 || client->wire_effect_phase != WIRE_EFFECT_CONNECTING) {
        errno = EACCES; goto out;
    }
    if (wire_effect_pair(client, &parent) != 0) { result = terminal_error(client, EACCES); goto out; }
    relay = client->upstream_relay;
    if (ctos_ssh_native_client_provisional_new_ready(client, &ready) != 0) goto out;
    if (ctos_ssh_role_bootstrap_snapshot(client->role, &snapshot) != 0) {
        result = terminal_error(client, errno == 0 ? EACCES : errno); goto out;
    }
    int valid_kex = snapshot.hop == 1U && snapshot.token == client->role
        && client->ssh != NULL && client->ssh->kex != NULL
        && client->ssh->kex->done && (client->ssh->kex->flags & KEX_INITIAL) == 0
        && !ssh_packet_is_rekeying(client->ssh)
        && ctos_ssh_kex_ready(client->ssh, snapshot.hop,
            snapshot.role_nonce, snapshot.connection_binding) == 1U
        && client->service_phase == USERAUTH_SERVICE_DISABLED && client->auth_phase == AUTH_DISABLED
        && client->shell.phase == CTOS_SSH_SHELL_DISABLED && !client->selected_credential_attempted;
    wipe_bytes(&snapshot, sizeof(snapshot));
    if (!valid_kex) { result = terminal_error(client, EACCES); goto out; }
    pending = sshbuf_len(ssh_packet_get_input(client->ssh));
    if (pending > PROVISIONAL_INPUT_MAX || ssh_packet_remaining(client->ssh) != 0) {
        result = terminal_error(client, EPROTO); goto out;
    }
    if (relay->peer_length != 0 || parent->shell.buffered_bytes != 0
        || sshbuf_len(ssh_packet_get_input(parent->ssh)) != 0
        || ssh_packet_remaining(parent->ssh) != 0) pending_parent = 1;

    capacity = PROVISIONAL_INPUT_MAX - pending;
    if (capacity > sizeof(input)) capacity = sizeof(input);
    if (capacity > client->step_budget) capacity = client->step_budget;
    if (wire_effect_acquire(client) != 0) {
        result = terminal_error(client, errno == 0 ? EACCES : errno); goto out;
    }
    if (capacity != 0) {
        count = recv(client->socket_fd, input, capacity, MSG_DONTWAIT);
        if (count == 0) error = EPIPE;
        else if (count < 0 && errno != EAGAIN && errno != EWOULDBLOCK)
            error = errno == 0 ? EIO : errno;
    }
    if (error == 0) {
        peek = provisional_peek(parent->socket_fd, &byte, 2);
        if (peek > 0) pending_parent = 1;
        else if (peek == 0) error = EPIPE;
        else if (errno != EAGAIN && errno != EWOULDBLOCK) error = errno == 0 ? EIO : errno;
    }
    ctos_ssh_effect_release();
    if (error != 0) { result = terminal_error(client, error); goto out; }
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
    if (count > 0) provisional_test_hook(client, 1);
#endif
    if (ctos_ssh_native_client_provisional_new_ready(client, &ready) != 0) goto out;
    if (count > 0 && ssh_input_append(client->ssh, input, (size_t)count) != 0) {
        result = terminal_error(client, EIO); goto out;
    }
    int packet_error = ctos_ssh_packet_read_provisional(client->ssh, &type);
    if (packet_error != 0
        || (type == SSH2_MSG_EXT_INFO && provisional_discard_ext_info(client) != 0)
        || (type != SSH_MSG_NONE && type != SSH2_MSG_EXT_INFO)) {
        result = terminal_error(client, EPROTO); goto out;
    }
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
    if (type == SSH2_MSG_EXT_INFO) provisional_test_hook(client, 2);
#endif
    pending = sshbuf_len(ssh_packet_get_input(client->ssh));
    if (pending > PROVISIONAL_INPUT_MAX || (pending == PROVISIONAL_INPUT_MAX && type == SSH_MSG_NONE)
        || ssh_packet_remaining(client->ssh) != 0) {
        result = terminal_error(client, EPROTO); goto out;
    }
    pending_child = pending != 0;
    if (wire_effect_acquire(client) != 0) {
        result = terminal_error(client, errno == 0 ? EACCES : errno); goto out;
    }
    peek = provisional_peek(client->socket_fd, &byte, 1);
    if (peek > 0) pending_child = 1;
    else if (peek == 0) error = EPIPE;
    else if (errno != EAGAIN && errno != EWOULDBLOCK) error = errno == 0 ? EIO : errno;
    if (error == 0) {
        peek = provisional_peek(parent->socket_fd, &byte, 2);
        if (peek > 0) pending_parent = 1;
        else if (peek == 0) error = EPIPE;
        else if (errno != EAGAIN && errno != EWOULDBLOCK) error = errno == 0 ? EIO : errno;
    }
    ctos_ssh_effect_release();
    if (error != 0) { result = terminal_error(client, error); goto out; }
    if (ctos_ssh_native_client_provisional_new_ready(client, &ready) != 0) goto out;
    *idle = ready && !pending_parent && !pending_child;
    result = 0;
out:
    wipe_bytes(&snapshot, sizeof(snapshot));
    wipe_bytes(input, sizeof(input));
    return result;
}

int ctos_ssh_native_client_host_key_rejection(const struct ctos_ssh_native_client *client,
    enum ctos_ssh_native_host_key_rejection *rejection) {
    struct ctos_ssh_role_snapshot snapshot = {0};
    int result, error;

    if (rejection != NULL) *rejection = CTOS_SSH_HOST_REJECTION_NONE;
    if (client == NULL || rejection == NULL) { errno = EINVAL; return -1; }
    if (client->closed) { errno = ENOENT; return -1; }
    if (ctos_ssh_callback_check_live(client->callback, client->callback_environment) != 0)
        return -1;
    if (upstream_proof(client) != 0) return -1;
    result = ctos_ssh_role_bootstrap_snapshot(client->role, &snapshot);
    error = errno;
    if (result == 0 && (snapshot.token != client->role || snapshot.owner_pid != getpid()
        || snapshot.owner_uid != getuid() || snapshot.deadline != client->deadline)) {
        result = -1;
        error = EACCES;
    }
    wipe_bytes(&snapshot, sizeof(snapshot));
    if (result != 0) { errno = error; return -1; }
    if (view_borrow_is_live(client->managed_view_borrow) != 0) return -1;
    if (client->failed) *rejection = client->host_key_rejection;
    if (client->upstream_relay != NULL
        && (ctos_ssh_callback_check_live(client->callback, client->callback_environment) != 0
            || upstream_proof(client) != 0)) {
        *rejection = CTOS_SSH_HOST_REJECTION_NONE; return -1;
    }
    return 0;
}

int ctos_ssh_native_client_authentication_selection_receipt(
    const struct ctos_ssh_native_client *client, unsigned char receipt[32]) {
    struct ctos_ssh_role_snapshot snapshot = {0};
    uint64_t current;
    int result;
    int error;

    if (receipt != NULL) wipe_bytes(receipt, 32U);
    if (client == NULL || receipt == NULL) { errno = EINVAL; return -1; }
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed) { errno = EALREADY; return -1; }
    if (client->authentication_selection_wire == NULL) { errno = EACCES; return -1; }
    if (ctos_ssh_callback_check_live(client->callback, client->callback_environment) != 0)
        return -1;
    if (upstream_proof(client) != 0) return -1;
    result = ctos_ssh_role_bootstrap_snapshot(client->role, &snapshot);
    error = errno;
    if (result == 0 && (snapshot.token != client->role || snapshot.owner_pid != getpid()
        || snapshot.owner_uid != getuid() || snapshot.deadline != client->deadline)) {
        result = -1;
        error = EACCES;
    }
    wipe_bytes(&snapshot, sizeof(snapshot));
    if (result != 0) { errno = error; return -1; }
    if (view_borrow_is_live(client->managed_view_borrow) != 0) return -1;
    if (now_millis(&current) != 0) return -1;
    if (current >= client->deadline) { errno = ETIMEDOUT; return -1; }
    memcpy(receipt, client->authentication_selection_receipt, 32U);
    if ((client->completed ? child_publication_live(client) : child_metadata_live(client)) != 0) {
        wipe_bytes(receipt, 32U); return -1;
    }
    return 0;
}

int ctos_ssh_native_client_userauth_service_ready(
    const struct ctos_ssh_native_client *client) {
    return client != NULL && !client->closed && !client->failed
        && client->service_phase == USERAUTH_SERVICE_READY && child_publication_live(client) == 0;
}

#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
/* The hook runs after the wire permit is released and only in test builds. */
int ctos_ssh_native_client_test_provisional_hook(struct ctos_ssh_native_client *client,
    int stage, void (*hook)(void *), void *context) {
    if (client_owner(client) != 0) return -1;
    if (client->closed || client->failed || client->provisional_new_state != 2
        || (stage != 1 && stage != 2) || hook == NULL || client->test_provisional_hook != NULL) {
        errno = EINVAL; return -1;
    }
    client->test_provisional_hook = hook;
    client->test_provisional_hook_context = context;
    client->test_provisional_hook_stage = stage;
    return 0;
}

/* Fixture only: stage a valid encrypted fragment as if an earlier transport
 * turn had received it. The parse option exercises OpenSSH's partial-block
 * state before the production idle observer is called. */
int ctos_ssh_native_client_test_provisional_input(struct ctos_ssh_native_client *client,
    const unsigned char *bytes, size_t length, int parse) {
    u_char type = SSH_MSG_NONE;
    if (client_owner(client) != 0 || client->provisional_new_state != 2 || client->failed
        || bytes == NULL || length == 0 || length > 64U || parse < 0 || parse > 2
        || sshbuf_len(ssh_packet_get_input(client->ssh)) != 0
        || ssh_packet_remaining(client->ssh) != 0) { errno = EINVAL; return -1; }
    if (ssh_input_append(client->ssh, bytes, length) != 0) return -1;
    if (parse) {
        if (ssh_packet_read_poll2(client->ssh, &type, NULL) != 0 || type != SSH_MSG_NONE) return -1;
        size_t input_length = sshbuf_len(ssh_packet_get_input(client->ssh));
        int incoming_length = ssh_packet_remaining(client->ssh);
        if ((parse == 1 && (input_length != 0 || incoming_length <= 0))
            || (parse == 2 && (input_length != length || incoming_length != 0))) return -1;
    }
    return 0;
}

int ctos_ssh_native_client_test_provisional_peer_eof(struct ctos_ssh_native_client *client) {
    if (client_owner(client) != 0 || client->provisional_new_state != 2 || client->failed
        || client->upstream_relay == NULL || client->upstream_relay->child != client) {
        errno = EINVAL; return -1;
    }
    return shutdown(client->upstream_relay->fd, SHUT_WR);
}

int ctos_ssh_native_client_test_provisional_fill_peer(struct ctos_ssh_native_client *client) {
    unsigned char bytes[4096] = {0};
    int size = 4096;
    if (client_owner(client) != 0 || client->provisional_new_state != 2 || client->failed
        || client->upstream_relay == NULL || client->upstream_relay->child != client
        || setsockopt(client->upstream_relay->fd, SOL_SOCKET, SO_SNDBUF, &size, sizeof(size)) != 0)
        return -1;
    for (unsigned int turn = 0; turn < 1024U; turn++) {
        ssize_t count = send(client->upstream_relay->fd, bytes, sizeof(bytes), MSG_DONTWAIT | MSG_NOSIGNAL);
        if (count < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) return turn > 0 ? 0 : -1;
        if (count <= 0) return -1;
    }
    errno = EOVERFLOW;
    return -1;
}

size_t ctos_ssh_native_client_test_provisional_peer_buffered(struct ctos_ssh_native_client *client) {
    if (client_owner(client) != 0 || client->upstream_relay == NULL
        || client->upstream_relay->child != client) return 0;
    return client->upstream_relay->peer_length;
}

int ctos_ssh_native_client_test_provisional_fail_peek(int peer) {
    if ((peer != 1 && peer != 2) || test_provisional_peek_failure != 0) {
        errno = EINVAL; return -1;
    }
    test_provisional_peek_failure = peer;
    return 0;
}

uint64_t ctos_ssh_native_client_test_wire_channel_bytes(void) {
    return test_wire_channel_bytes;
}

uint64_t ctos_ssh_native_client_test_early_auth_reply_denials(void) {
    return test_early_auth_reply_denials;
}

uint64_t ctos_ssh_native_client_test_pending_kex_drains(void) {
    return test_pending_kex_drains;
}

int ctos_ssh_native_client_test_before_java_secret_copy(void (*hook)(void *), void *context) {
    if (hook == NULL || test_before_java_secret_copy != NULL || current_native_callback != NULL) {
        errno = EINVAL;
        return -1;
    }
    test_before_java_secret_copy = hook;
    test_before_java_secret_copy_context = context;
    test_last_password_wiped = 0;
    return 0;
}

int ctos_ssh_native_client_test_before_native_secret_copy(void (*hook)(void *), void *context) {
    if (hook == NULL || test_before_native_secret_copy != NULL || current_native_callback != NULL) {
        errno = EINVAL;
        return -1;
    }
    test_before_native_secret_copy = hook;
    test_before_native_secret_copy_context = context;
    test_last_password_wiped = 0;
    return 0;
}

int ctos_ssh_native_client_test_before_password_packet(void (*hook)(void *), void *context) {
    if (hook == NULL || test_before_password_packet != NULL || current_native_callback != NULL) {
        errno = EINVAL;
        return -1;
    }
    test_before_password_packet = hook;
    test_before_password_packet_context = context;
    test_last_password_wiped = 0;
    return 0;
}

int ctos_ssh_native_client_test_before_jump_send(void (*hook)(void *), void *context) {
    if (hook == NULL || test_before_jump_send != NULL || current_native_callback != NULL) {
        errno = EINVAL;
        return -1;
    }
    test_before_jump_send = hook;
    test_before_jump_send_context = context;
    return 0;
}

int ctos_ssh_native_client_test_last_password_wiped(void) { return test_last_password_wiped; }

int ctos_ssh_native_client_test_arm_child_effect(struct ctos_ssh_native_client *client) {
    uint32_t generation;
    if (client_owner(client) != 0) return -1;
    if (client->closed || client->failed || client->upstream_relay == NULL
        || client->jump_effect_generation != 0 || ctos_ssh_effect_snapshot(&generation) != 0) {
        errno = EACCES;
        return -1;
    }
    client->jump_effect_generation = generation;
    return 0;
}

int ctos_ssh_native_client_test_abandoned_effect_frame(struct ctos_ssh_native_client *client) {
    struct native_callback_dispatch frame;
    uint32_t generation;
    if (client_owner(client) != 0 || current_native_callback != NULL
        || ctos_ssh_effect_test_active() != 0 || ctos_ssh_effect_snapshot(&generation) != 0
        || ctos_ssh_effect_acquire(generation) != 0) return -1;
    begin_native_callback(&frame, client, NATIVE_CALLBACK_CREDENTIAL);
    frame.java_copy_state = 1;
    end_native_callback(&frame);
    return ctos_ssh_effect_test_active() == 0 ? 0 : -1;
}

int ctos_ssh_native_client_test_before_promotion_release(struct ctos_ssh_native_client *client,
    void (*hook)(void *), void *context) {
    if (client_owner(client) != 0) return -1;
    if (client->closed || client->failed || client->established || hook == NULL
        || client->test_before_promotion_release != NULL) { errno = EACCES; return -1; }
    client->test_before_promotion_release = hook;
    client->test_promotion_release_context = context;
    return 0;
}

int ctos_ssh_native_client_test_claim_callback_scope(ctos_ssh_callback_token callback,
    void *environment, ctos_ssh_role_token role, const void *owner) {
    return ctos_ssh_callback_client_claim_scoped(callback, environment, role,
        owner, callback_ancestor_scope_check);
}

int ctos_ssh_native_client_test_invalidate_kex(struct ctos_ssh_native_client *client) {
    if (client_owner(client) != 0) return -1;
    if (client->closed || client->failed || client->upstream_relay == NULL
        || client->ssh == NULL || !client->completed) { errno = EACCES; return -1; }
    ctos_ssh_kex_unbind(client->ssh);
    return 0;
}

int ctos_ssh_native_client_test_fail_next_view_release(struct ctos_ssh_native_client *client) {
    if (client_owner(client) != 0) return -1;
    if (client->closed || client->view_claim == NULL) { errno = EACCES; return -1; }
    return ctos_ssh_view_borrow_test_fail_next_release(client->view_claim, client);
}
#endif

int ctos_ssh_native_client_close(struct ctos_ssh_native_client *client) {
    if (client_owner(client) != 0) return -1;
    if (client->closed) {
        errno = ENOENT;
        return -1;
    }
    if (client->jump_relay != NULL) { errno = EBUSY; return -1; }
    if (client->wire_effect_phase != WIRE_EFFECT_NONE) {
        struct ctos_ssh_native_client *parent = client->wire_effect_parent;
        if (parent != NULL && parent != client && client->upstream_relay != NULL
                && client->upstream_relay->parent == parent && parent->wire_effect_parent == parent) {
            parent->failed = 1;
            wire_effect_reset(parent);
        }
        client->failed = 1;
        wire_effect_reset(client);
    }
    client->host_key_rejection = CTOS_SSH_HOST_REJECTION_NONE;
    if (!client->completed && client->candidate_handed_off
        && abort_handed_off_candidate(client) != 0) return -1;
    /* The callback binding was opened for this native event-loop owner. Close
     * it while the event-loop environment is still live, before touching the
     * OpenSSH object or owned descriptor. A failed release leaves the whole
     * client intact so the exact close can retry. */
    if (!client->callback_closed
        && ctos_ssh_callback_client_close(client->callback,
            client->callback_environment, client) != 0) return -1;
    client->callback_closed = 1;
    client->admit_effect = NULL;
    client->effect_context = NULL;
    if (!client->kex_released && client->ssh != NULL) {
        ctos_ssh_kex_unbind(client->ssh);
        ssh_free(client->ssh);
        client->ssh = NULL;
        client->kex_released = 1;
    }
    if (!client->kex_released) client->kex_released = 1;
    if (!client->view_released && client->managed_view_borrow != NULL
        && ctos_ssh_view_borrow_claim_release(client->view_claim, client) != 0) return -1;
    client->view_claim = NULL;
    client->managed_view_borrow = NULL;
    client->managed_view = NULL;
    client->hostfile_view = NULL;
    client->view_released = 1;
    if (client->server_host_public_key != NULL) {
        sshkey_free(client->server_host_public_key);
        client->server_host_public_key = NULL;
    }
    if (client->caller_socket_fd >= 0) {
        int owned_fd = client->caller_socket_fd;
        client->caller_socket_fd = -1;
        if (close(owned_fd) != 0) return -1;
    }
    client->socket_fd = -1;
    wipe_bytes(client->shell_buffer, sizeof(client->shell_buffer));
    memset(&client->shell, 0, sizeof(client->shell));
    clear_authentication_selection(client);
    clear_keyboard_challenge(client);
    client->keyboard_round = 0;
    wipe_bytes(client->completion_session_digest, sizeof(client->completion_session_digest));
    wipe_bytes(client->established_session_digest, sizeof(client->established_session_digest));
    wipe_bytes(&client->established_role, sizeof(client->established_role));
    wipe_bytes(&client->jump_next_role, sizeof(client->jump_next_role));
    client->jump_mode = 0;
    client->established = 0;
    client->established_ssh = NULL;
    client->established_host_key = NULL;
    client->auth_phase = AUTH_DISABLED;
    client->auth_result = CTOS_SSH_PASSWORD_AUTH_NOT_STARTED;
    client->discovery_result = CTOS_SSH_DISCOVERY_NOT_STARTED;
    client->discovery_methods = 0;
    client->auth_banners = 0;
    client->closed = 1;
    if (client->upstream_relay != NULL) {
        client->upstream_relay->child = NULL;
        client->upstream_relay = NULL;
    }
    return 0;
}

int ctos_ssh_native_client_destroy(struct ctos_ssh_native_client *client) {
    if (client_owner(client) != 0) return -1;
    if (!client->closed) {
        errno = EBUSY;
        return -1;
    }
    if (unregister_scope_client(client) != 0) return -1;
    wipe_bytes(client, sizeof(*client));
    free(client);
    return 0;
}

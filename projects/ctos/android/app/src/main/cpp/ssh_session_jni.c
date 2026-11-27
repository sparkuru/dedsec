#define _GNU_SOURCE

#include <jni.h>

#include <errno.h>
#include <limits.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "ssh_native_client.h"
#include "ssh_view_handle.h"

#define CTOS_SSH_SESSION_SLOTS 64U
#define CTOS_SSH_SESSION_IO_BYTES 32768U
#define CTOS_SSH_SESSION_BUFFER_BYTES 65536U

struct session_slot {
    uint64_t token;
    pthread_t owner_thread;
    struct ctos_ssh_native_client *client;
    int opening;
    ctos_ssh_view_token view_token;
    uint64_t deadline;
    uint64_t parent_token;
    struct ctos_ssh_native_jump_relay *relay;
    struct ctos_ssh_view_borrow *pending_borrow;
    struct ctos_ssh_view *fresh_hostfile_view;
    int jump_child;
    int jump_state;
    int client_closed;
    int relay_closed;
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
    ctos_ssh_callback_token fixture_callback;
    int fixture_ext_hook_armed;
    int fixture_ext_seen;
#endif
};

enum jump_child_state {
    JUMP_CHILD_RESERVED = 0,
    JUMP_CHILD_FAILED_EMPTY = 1,
    JUMP_CHILD_FAILED_CLIENT = 2,
    JUMP_CHILD_FAILED_RELAY = 3,
    JUMP_CHILD_OPEN = 4
};

static pthread_mutex_t sessions_lock = PTHREAD_MUTEX_INITIALIZER;
static struct session_slot sessions[CTOS_SSH_SESSION_SLOTS];
static uint64_t next_session_token;

static void wipe_bytes(void *memory, size_t length) {
    volatile unsigned char *bytes = memory;

    if (bytes == NULL) return;
    while (length-- != 0) *bytes++ = 0;
}

/* The slot keeps ownership on every failure, including an unmap failure. */
static int release_fresh_hostfile_view(struct session_slot *slot) {
    if (slot->fresh_hostfile_view == NULL) return 0;
    if (ctos_ssh_view_destroy(slot->fresh_hostfile_view) != 0) return -1;
    free(slot->fresh_hostfile_view);
    slot->fresh_hostfile_view = NULL;
    return 0;
}

static int load_fresh_hostfile_view(JNIEnv *env, jbyteArray wire, struct session_slot *slot) {
    if (wire == NULL || slot->fresh_hostfile_view != NULL) { errno = EINVAL; return -1; }
    jsize length = (*env)->GetArrayLength(env, wire);
    if ((*env)->ExceptionCheck(env) || length <= 0 || (size_t)length > CTOS_SSH_VIEW_WIRE_BYTES) {
        errno = EINVAL; return -1;
    }
    slot->fresh_hostfile_view = calloc(1, sizeof(*slot->fresh_hostfile_view));
    if (slot->fresh_hostfile_view == NULL) { errno = ENOMEM; return -1; }
    slot->fresh_hostfile_view->fd = -1;
    if (ctos_ssh_view_allocate(slot->fresh_hostfile_view, (size_t)length) != 0) return -1;
    (*env)->GetByteArrayRegion(env, wire, 0, length,
        (jbyte *)slot->fresh_hostfile_view->mapping);
    if ((*env)->ExceptionCheck(env)) { errno = EIO; return -1; }
    return ctos_ssh_view_validate(slot->fresh_hostfile_view);
}

static void fail(JNIEnv *env) {
    jclass error;

    if (env == NULL || (*env)->ExceptionCheck(env)) return;
    error = (*env)->FindClass(env, "java/io/IOException");
    if (error != NULL) {
        (*env)->ThrowNew(env, error, "SSH session native owner is unavailable");
        (*env)->DeleteLocalRef(env, error);
    }
}

static void fail_step(JNIEnv *env, enum ctos_ssh_native_host_key_rejection rejection) {
    const char *code;
    switch (rejection) {
        case CTOS_SSH_HOST_REJECTION_CHANGED: code = "SSH_HOST_KEY_CHANGED"; break;
        case CTOS_SSH_HOST_REJECTION_REVOKED: code = "SSH_HOST_KEY_REVOKED"; break;
        case CTOS_SSH_HOST_REJECTION_CERTIFICATE: code = "SSH_HOST_KEY_CERTIFICATE"; break;
        case CTOS_SSH_HOST_REJECTION_CHANGED_BLOCKED: code = "SSH_HOST_TRUST_BLOCKED"; break;
        default: fail(env); return;
    }
    if (env == NULL || (*env)->ExceptionCheck(env)) return;
    jclass error = (*env)->FindClass(env, "im/majo/ctos/SshNativeSession$HostKeyRejectedException");
    if (error != NULL) {
        (*env)->ThrowNew(env, error, code);
        (*env)->DeleteLocalRef(env, error);
    }
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_prepareTcpDescriptor(
    JNIEnv *env, jclass type, jint descriptor) {
    (void)type;
    if (ctos_ssh_native_client_prepare_tcp_descriptor(descriptor) != 0) fail(env);
}

static struct session_slot *find_session_locked(uint64_t token) {
    for (size_t index = 0; index < CTOS_SSH_SESSION_SLOTS; index++) {
        if (sessions[index].token == token) return &sessions[index];
    }
    return NULL;
}

static int reserve_session(uint64_t *token_out) {
    struct session_slot *slot = NULL;

    if (token_out == NULL || pthread_mutex_lock(&sessions_lock) != 0) {
        errno = EBUSY;
        return -1;
    }
    for (size_t index = 0; index < CTOS_SSH_SESSION_SLOTS; index++) {
        if (sessions[index].token == 0) {
            slot = &sessions[index];
            break;
        }
    }
    if (slot == NULL || next_session_token >= INT64_MAX) {
        pthread_mutex_unlock(&sessions_lock);
        errno = EBUSY;
        return -1;
    }
    memset(slot, 0, sizeof(*slot));
    slot->token = ++next_session_token;
    slot->owner_thread = pthread_self();
    slot->opening = 1;
    *token_out = slot->token;
    pthread_mutex_unlock(&sessions_lock);
    return 0;
}

static void abort_reserved_session(uint64_t token) {
    if (pthread_mutex_lock(&sessions_lock) != 0) return;
    struct session_slot *slot = find_session_locked(token);
    if (slot != NULL && slot->opening && pthread_equal(slot->owner_thread, pthread_self()))
        memset(slot, 0, sizeof(*slot));
    pthread_mutex_unlock(&sessions_lock);
}

/* Hold this reservation across C open. After C consumes the descriptor,
 * publication is metadata assignment and cannot introduce a new failure. */
static struct session_slot *lock_reserved_session(uint64_t token) {
    struct session_slot *slot;

    if (pthread_mutex_lock(&sessions_lock) != 0) {
        errno = EBUSY;
        return NULL;
    }
    slot = find_session_locked(token);
    if (slot == NULL || !slot->opening
        || !pthread_equal(slot->owner_thread, pthread_self())) {
        pthread_mutex_unlock(&sessions_lock);
        errno = EACCES;
        return NULL;
    }
    return slot;
}

static int has_children_locked(uint64_t token) {
    for (size_t index = 0; index < CTOS_SSH_SESSION_SLOTS; index++) {
        if (sessions[index].token != 0 && sessions[index].parent_token == token) return 1;
    }
    return 0;
}

static struct session_slot *lock_session_for(JNIEnv *env, jlong handle, int cleanup) {
    struct session_slot *slot;

    if (handle <= 0 || pthread_mutex_lock(&sessions_lock) != 0) {
        fail(env);
        return NULL;
    }
    slot = find_session_locked((uint64_t)handle);
    if (slot == NULL || slot->opening || slot->client == NULL
        || (slot->jump_child && slot->jump_state != JUMP_CHILD_OPEN && !cleanup)
        || !pthread_equal(slot->owner_thread, pthread_self())) {
        pthread_mutex_unlock(&sessions_lock);
        fail(env);
        return NULL;
    }
    return slot;
}

static struct session_slot *lock_session(JNIEnv *env, jlong handle) {
    return lock_session_for(env, handle, 0);
}

/* A reserved jump owner exists even before a native client has been created. */
static struct session_slot *lock_jump_child(JNIEnv *env, jlong handle) {
    struct session_slot *slot;
    if (handle <= 0 || pthread_mutex_lock(&sessions_lock) != 0) {
        fail(env);
        return NULL;
    }
    slot = find_session_locked((uint64_t)handle);
    if (slot == NULL || !slot->jump_child
        || !pthread_equal(slot->owner_thread, pthread_self())) {
        pthread_mutex_unlock(&sessions_lock);
        fail(env);
        return NULL;
    }
    return slot;
}

static int copy_array(JNIEnv *env, jbyteArray array, size_t maximum,
    unsigned char **bytes_out, size_t *length_out) {
    jsize length;
    unsigned char *bytes;

    if (env == NULL || array == NULL || bytes_out == NULL || length_out == NULL)
        return -1;
    length = (*env)->GetArrayLength(env, array);
    if ((*env)->ExceptionCheck(env) || length <= 0 || (size_t)length > maximum)
        return -1;
    bytes = calloc(1, (size_t)length);
    if (bytes == NULL) return -1;
    (*env)->GetByteArrayRegion(env, array, 0, length, (jbyte *)bytes);
    if ((*env)->ExceptionCheck(env)) {
        wipe_bytes(bytes, (size_t)length);
        free(bytes);
        return -1;
    }
    *bytes_out = bytes;
    *length_out = (size_t)length;
    return 0;
}

static int copy_fingerprint(JNIEnv *env, jbyteArray array,
    unsigned char output[CTOS_SSH_NATIVE_CLIENT_FINGERPRINT_BYTES]) {
    jsize length;
    unsigned char combined = 0;

    if (env == NULL || array == NULL || output == NULL) return -1;
    length = (*env)->GetArrayLength(env, array);
    if ((*env)->ExceptionCheck(env)
        || length != (jsize)CTOS_SSH_NATIVE_CLIENT_FINGERPRINT_BYTES) return -1;
    (*env)->GetByteArrayRegion(env, array, 0, length, (jbyte *)output);
    if ((*env)->ExceptionCheck(env)) return -1;
    for (size_t index = 0; index < CTOS_SSH_NATIVE_CLIENT_FINGERPRINT_BYTES; index++)
        combined |= output[index];
    return combined == 0 ? -1 : 0;
}

static int copy_string(JNIEnv *env, jstring value, char *output, size_t capacity) {
    const char *text;
    jsize length;

    if (env == NULL || value == NULL || output == NULL || capacity == 0) return -1;
    length = (*env)->GetStringUTFLength(env, value);
    if ((*env)->ExceptionCheck(env) || length <= 0 || (size_t)length >= capacity)
        return -1;
    text = (*env)->GetStringUTFChars(env, value, NULL);
    if (text == NULL) return -1;
    memcpy(output, text, (size_t)length);
    output[length] = '\0';
    (*env)->ReleaseStringUTFChars(env, value, text);
    return (*env)->ExceptionCheck(env) ? -1 : 0;
}

static jlong open_session(
    JNIEnv *env, enum ctos_ssh_native_host_key_mode host_key_mode,
    jlong role, jint owner_uid, jlong callback,
    jlong view, jint socket_fd, jlong deadline, jint step_budget,
    jbyteArray host_key_blob, jbyteArray expected_fingerprint,
    jstring known_hosts_path, jstring host, jstring candidate_id,
    jbyteArray selection_wire) {
    unsigned char *key_blob = NULL;
    size_t key_blob_length = 0;
    unsigned char *selection = NULL;
    size_t selection_length = 0;
    unsigned char fingerprint[CTOS_SSH_NATIVE_CLIENT_FINGERPRINT_BYTES] = {0};
    char known_hosts[513] = {0};
    char host_name[CTOS_SSH_ROLE_HOST_BYTES + 1U] = {0};
    char candidate[CTOS_SSH_CALLBACK_CANDIDATE_ID_BYTES] = {0};
    struct ctos_ssh_view_borrow *borrow = NULL;
    struct ctos_ssh_native_client_config config;
    struct ctos_ssh_native_client *client = NULL;
    struct session_slot *reserved = NULL;
    uint64_t token = 0;
    int success = 0;

    if (env == NULL || role <= 0 || callback <= 0 || view <= 0 || socket_fd < 0
        || owner_uid < 0 || deadline <= 0 || step_budget <= 0
        || (size_t)step_budget > CTOS_SSH_NATIVE_CLIENT_MAX_STEP_BYTES
        || (host_key_mode == CTOS_SSH_HOST_KEY_PINNED
            && (copy_array(env, host_key_blob, CTOS_SSH_NATIVE_CLIENT_MAX_HOST_KEY_BLOB_BYTES,
                &key_blob, &key_blob_length) != 0
                || copy_fingerprint(env, expected_fingerprint, fingerprint) != 0))
        || copy_string(env, known_hosts_path, known_hosts, sizeof(known_hosts)) != 0
        || copy_string(env, host, host_name, sizeof(host_name)) != 0
        || copy_string(env, candidate_id, candidate, sizeof(candidate)) != 0
        || (selection_wire != NULL && copy_array(env, selection_wire,
            CTOS_SSH_NATIVE_CLIENT_MAX_SELECTION_WIRE_BYTES,
            &selection, &selection_length) != 0)
        || reserve_session(&token) != 0
        || ctos_ssh_view_handle_borrow((ctos_ssh_view_token)view, &borrow) != 0)
        goto failed;
    config = (struct ctos_ssh_native_client_config){
        .role = (ctos_ssh_role_token)role,
        .socket_fd = socket_fd,
        .owner_uid = (uid_t)owner_uid,
        .owns_socket = 1,
        .callback = (ctos_ssh_callback_token)callback,
        .callback_environment = env,
        .deadline = (uint64_t)deadline,
        .step_budget = (size_t)step_budget,
        .host_key_mode = host_key_mode,
        .server_host_public_key_blob = key_blob,
        .server_host_public_key_length = key_blob_length,
        .expected_host_fingerprint = host_key_mode == CTOS_SSH_HOST_KEY_PINNED ? fingerprint : NULL,
        .managed_view_borrow = borrow,
        .known_hosts_path = known_hosts,
        .host = host_name,
        .candidate_id = candidate,
        .authentication_selection_wire = selection,
        .authentication_selection_wire_length = selection_length
    };
    reserved = lock_reserved_session(token);
    if (reserved == NULL) goto failed;
    if (ctos_ssh_native_client_open(&config, &client) != 0) {
        memset(reserved, 0, sizeof(*reserved));
        pthread_mutex_unlock(&sessions_lock);
        token = 0;
        goto failed;
    }
    borrow = NULL; /* ownership transferred to the native session */
    reserved->client = client;
    reserved->view_token = (ctos_ssh_view_token)view;
    reserved->deadline = (uint64_t)deadline;
    reserved->opening = 0;
    pthread_mutex_unlock(&sessions_lock);
    success = 1;

failed:
    if (!success && borrow != NULL) (void)ctos_ssh_view_borrow_release(borrow);
    if (!success && token != 0) abort_reserved_session(token);
    if (!success && client != NULL) {
        (void)ctos_ssh_native_client_close(client);
        (void)ctos_ssh_native_client_destroy(client);
    }
    if (key_blob != NULL) {
        wipe_bytes(key_blob, key_blob_length);
        free(key_blob);
    }
    wipe_bytes(selection, selection_length);
    free(selection);
    wipe_bytes(fingerprint, sizeof(fingerprint));
    wipe_bytes(known_hosts, sizeof(known_hosts));
    wipe_bytes(host_name, sizeof(host_name));
    wipe_bytes(candidate, sizeof(candidate));
    if (!success) {
        fail(env);
        return 0;
    }
    return (jlong)token;
}

JNIEXPORT jlong JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_open(
    JNIEnv *env, jclass type, jlong role, jint owner_uid, jlong callback,
    jlong view, jint socket_fd, jlong deadline, jint step_budget,
    jbyteArray host_key_blob, jbyteArray expected_fingerprint,
    jstring known_hosts_path, jstring host, jstring candidate_id,
    jbyteArray selection_wire) {
    (void)type;
    return open_session(env, CTOS_SSH_HOST_KEY_PINNED, role, owner_uid, callback,
        view, socket_fd, deadline, step_budget, host_key_blob, expected_fingerprint,
        known_hosts_path, host, candidate_id, selection_wire);
}

JNIEXPORT jlong JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_openManaged(
    JNIEnv *env, jclass type, jlong role, jint owner_uid, jlong callback,
    jlong view, jint socket_fd, jlong deadline, jint step_budget,
    jstring known_hosts_path, jstring host, jstring candidate_id,
    jbyteArray selection_wire) {
    (void)type;
    return open_session(env, CTOS_SSH_HOST_KEY_MANAGED, role, owner_uid, callback,
        view, socket_fd, deadline, step_budget, NULL, NULL,
        known_hosts_path, host, candidate_id, selection_wire);
}

/* The Java owner receives the reservation before any native child or relay can
 * be created. The token also pins this exact parent until the child is gone. */
JNIEXPORT jlong JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_reserveJumpChild(
    JNIEnv *env, jclass type, jlong parent_handle) {
    struct session_slot *parent, *child = NULL;
    struct ctos_ssh_native_jump_status status;
    uint64_t token;
    (void)type;

    parent = lock_session(env, parent_handle);
    if (parent == NULL) return 0;
    if (parent->client_closed || parent->view_token == 0 || parent->deadline == 0
        || has_children_locked(parent->token)
        || ctos_ssh_native_client_jump_status(parent->client, &status) != 0
        || status.phase != CTOS_SSH_JUMP_OPEN || status.remote_eof || status.local_eof)
        goto failed;
    for (size_t index = 0; index < CTOS_SSH_SESSION_SLOTS; index++) {
        if (sessions[index].token == 0) { child = &sessions[index]; break; }
    }
    if (child == NULL || next_session_token >= INT64_MAX) goto failed;
    token = ++next_session_token;
    memset(child, 0, sizeof(*child));
    child->token = token;
    child->owner_thread = pthread_self();
    child->opening = 1;
    child->jump_child = 1;
    child->jump_state = JUMP_CHILD_RESERVED;
    child->parent_token = parent->token;
    child->view_token = parent->view_token;
    child->deadline = parent->deadline;
    pthread_mutex_unlock(&sessions_lock);
    return (jlong)token;

failed:
    pthread_mutex_unlock(&sessions_lock);
    fail(env);
    return 0;
}

static jboolean open_jump_child(
    JNIEnv *env, jlong handle, jlong role, jint owner_uid,
    jlong callback, jlong view, jlong deadline, jint step_budget,
    jstring known_hosts_path, jstring host, jstring candidate_id,
    jbyteArray selection_wire, jbyteArray fresh_hostfile_wire) {
    struct session_slot *slot, *parent;
    struct ctos_ssh_native_client *client = NULL;
    struct ctos_ssh_native_jump_relay *relay = NULL;
    struct ctos_ssh_native_client_config config;
    unsigned char *selection = NULL;
    size_t selection_length = 0;
    char known_hosts[513] = {0};
    char host_name[CTOS_SSH_ROLE_HOST_BYTES + 1U] = {0};
    char candidate[CTOS_SSH_CALLBACK_CANDIDATE_ID_BYTES] = {0};
    int result = -1;
    slot = lock_jump_child(env, handle);
    if (slot == NULL) return JNI_FALSE;
    if (slot->jump_state != JUMP_CHILD_RESERVED || !slot->opening) goto done;
    /* An attempted open is never silently replayed, even on malformed input. */
    slot->jump_state = JUMP_CHILD_FAILED_EMPTY;
    parent = find_session_locked(slot->parent_token);
    if (parent == NULL || parent->opening || parent->client == NULL || parent->client_closed
        || !pthread_equal(parent->owner_thread, pthread_self())
        || parent->view_token != slot->view_token || parent->deadline != slot->deadline
        || role <= 0 || owner_uid < 0 || callback <= 0 || view <= 0
        || (ctos_ssh_view_token)view != slot->view_token
        || deadline <= 0 || (uint64_t)deadline != slot->deadline
        || step_budget <= 0 || (size_t)step_budget > CTOS_SSH_NATIVE_CLIENT_MAX_STEP_BYTES
        || copy_string(env, known_hosts_path, known_hosts, sizeof(known_hosts)) != 0
        || copy_string(env, host, host_name, sizeof(host_name)) != 0
        || copy_string(env, candidate_id, candidate, sizeof(candidate)) != 0
        || (selection_wire != NULL && copy_array(env, selection_wire,
            CTOS_SSH_NATIVE_CLIENT_MAX_SELECTION_WIRE_BYTES,
            &selection, &selection_length) != 0)
        || ctos_ssh_view_handle_borrow(slot->view_token, &slot->pending_borrow) != 0
        || (fresh_hostfile_wire != NULL
            && load_fresh_hostfile_view(env, fresh_hostfile_wire, slot) != 0))
        goto done;

    config = (struct ctos_ssh_native_client_config){
        .role = (ctos_ssh_role_token)role,
        .socket_fd = -1,
        .owner_uid = (uid_t)owner_uid,
        .owns_socket = 1,
        .callback = (ctos_ssh_callback_token)callback,
        .callback_environment = env,
        .deadline = (uint64_t)deadline,
        .step_budget = (size_t)step_budget,
        .host_key_mode = CTOS_SSH_HOST_KEY_MANAGED,
        .managed_view_borrow = slot->pending_borrow,
        .fresh_hostfile_view = slot->fresh_hostfile_view,
        .known_hosts_path = known_hosts,
        .host = host_name,
        .candidate_id = candidate,
        .authentication_selection_wire = selection,
        .authentication_selection_wire_length = selection_length
    };
    result = ctos_ssh_native_client_open_jump_child(parent->client, &config, &client, &relay);
    /* Preserve every returned owner before any failure is visible to Java. */
    slot->relay = relay;
    if (client != NULL) {
        slot->client = client;
        slot->pending_borrow = NULL;
        slot->opening = 0;
        slot->jump_state = result == 0 && relay != NULL ? JUMP_CHILD_OPEN
            : relay != NULL ? JUMP_CHILD_FAILED_RELAY : JUMP_CHILD_FAILED_CLIENT;
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
        slot->fixture_callback = (ctos_ssh_callback_token)callback;
#endif
    }
    if (result == 0 && (client == NULL || relay == NULL)) result = -1;

done:
    pthread_mutex_unlock(&sessions_lock);
    wipe_bytes(selection, selection_length);
    free(selection);
    wipe_bytes(known_hosts, sizeof(known_hosts));
    wipe_bytes(host_name, sizeof(host_name));
    wipe_bytes(candidate, sizeof(candidate));
    if (result != 0) {
        fail(env);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_openJumpChild(
    JNIEnv *env, jclass type, jlong handle, jlong role, jint owner_uid,
    jlong callback, jlong view, jlong deadline, jint step_budget,
    jstring known_hosts_path, jstring host, jstring candidate_id,
    jbyteArray selection_wire) {
    (void)type;
    return open_jump_child(env, handle, role, owner_uid, callback, view, deadline,
        step_budget, known_hosts_path, host, candidate_id, selection_wire, NULL);
}

JNIEXPORT jboolean JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_openJumpChildFresh(
    JNIEnv *env, jclass type, jlong handle, jlong role, jint owner_uid,
    jlong callback, jlong view, jlong deadline, jint step_budget,
    jstring known_hosts_path, jstring host, jstring candidate_id,
    jbyteArray selection_wire, jbyteArray fresh_hostfile_wire) {
    (void)type;
    if (fresh_hostfile_wire == NULL) { fail(env); return JNI_FALSE; }
    return open_jump_child(env, handle, role, owner_uid, callback, view, deadline,
        step_budget, known_hosts_path, host, candidate_id, selection_wire, fresh_hostfile_wire);
}

JNIEXPORT jint JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_jumpChildState(
    JNIEnv *env, jclass type, jlong handle) {
    struct session_slot *slot = lock_jump_child(env, handle);
    jint state;
    (void)type;
    if (slot == NULL) return -1;
    state = (jint)slot->jump_state;
    pthread_mutex_unlock(&sessions_lock);
    return state;
}

/* On close(fd) failure the relay is already logically closed; destroy probes
 * that state without ever retrying the numeric fd. Preserve an error return. */
static int close_jump_relay_locked(struct session_slot *slot) {
    int error;
    if (slot->relay == NULL) return 0;
    if (!slot->relay_closed) {
        if (ctos_ssh_native_jump_relay_close(slot->relay) != 0) {
            error = errno == 0 ? EIO : errno;
            if (ctos_ssh_native_jump_relay_destroy(slot->relay) == 0) {
                slot->relay = NULL;
                slot->relay_closed = 1;
            }
            errno = error;
            return -1;
        }
        slot->relay_closed = 1;
    }
    if (ctos_ssh_native_jump_relay_destroy(slot->relay) != 0) return -1;
    slot->relay = NULL;
    return 0;
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_abortJumpChild(
    JNIEnv *env, jclass type, jlong handle) {
    struct session_slot *slot = lock_jump_child(env, handle);
    (void)type;
    if (slot == NULL) return;
    if (slot->client != NULL || has_children_locked(slot->token)
        || close_jump_relay_locked(slot) != 0
        || (slot->pending_borrow != NULL
            && ctos_ssh_view_borrow_release(slot->pending_borrow) != 0)) {
        pthread_mutex_unlock(&sessions_lock);
        fail(env);
        return;
    }
    slot->pending_borrow = NULL;
    if (release_fresh_hostfile_view(slot) != 0) {
        pthread_mutex_unlock(&sessions_lock);
        fail(env);
        return;
    }
    memset(slot, 0, sizeof(*slot));
    pthread_mutex_unlock(&sessions_lock);
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_beginJump(
    JNIEnv *env, jclass type, jlong handle, jlong next_role) {
    struct session_slot *slot;
    int result;
    (void)type;
    if (next_role <= 0) { fail(env); return; }
    slot = lock_session(env, handle);
    if (slot == NULL) return;
    result = ctos_ssh_native_client_begin_jump(slot->client, (ctos_ssh_role_token)next_role);
    pthread_mutex_unlock(&sessions_lock);
    if (result != 0) fail(env);
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_beginJumpGuarded(
    JNIEnv *env, jclass type, jlong handle, jlong next_role, jlong deadline, jlong generation) {
    (void)type;
    if (next_role <= 0 || deadline <= 0 || generation <= 0 || (uint64_t)generation >= UINT32_MAX) {
        fail(env); return;
    }
    struct session_slot *slot = lock_session(env, handle);
    if (slot == NULL) return;
    int status = ctos_ssh_native_client_begin_jump_guarded(slot->client,
            (ctos_ssh_role_token)next_role, (uint64_t)deadline, (uint32_t)generation);
    pthread_mutex_unlock(&sessions_lock);
    if (status != 0) fail(env);
}

JNIEXPORT jintArray JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_jumpStatus(
    JNIEnv *env, jclass type, jlong handle) {
    struct session_slot *slot;
    struct ctos_ssh_native_jump_status status;
    jint values[4];
    jintArray result = NULL;
    (void)type;
    slot = lock_session(env, handle);
    if (slot == NULL) return NULL;
    if (ctos_ssh_native_client_jump_status(slot->client, &status) != 0
        || status.buffered_bytes > INT_MAX) {
        pthread_mutex_unlock(&sessions_lock);
        fail(env);
        return NULL;
    }
    values[0] = (jint)status.phase;
    values[1] = (jint)(status.remote_eof != 0);
    values[2] = (jint)(status.local_eof != 0);
    values[3] = (jint)status.buffered_bytes;
    result = (*env)->NewIntArray(env, 4);
    if (result != NULL) (*env)->SetIntArrayRegion(env, result, 0, 4, values);
    pthread_mutex_unlock(&sessions_lock);
    return result;
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_pumpJumpRelay(
    JNIEnv *env, jclass type, jlong handle) {
    struct session_slot *slot = lock_session(env, handle);
    int result;
    (void)type;
    if (slot == NULL) return;
    result = !slot->jump_child || slot->client_closed || slot->relay == NULL
        ? -1 : ctos_ssh_native_jump_relay_pump(slot->relay);
    pthread_mutex_unlock(&sessions_lock);
    if (result != 0) fail(env);
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_retainJumpTransport(
    JNIEnv *env, jclass type, jlong handle) {
    struct session_slot *slot = lock_session(env, handle);
    int result;
    (void)type;
    if (slot == NULL) return;
    result = ctos_ssh_native_client_retain_jump_transport(slot->client);
    if (result == 0 && release_fresh_hostfile_view(slot) != 0) result = -1;
    pthread_mutex_unlock(&sessions_lock);
    if (result != 0) fail(env);
}

JNIEXPORT jbyteArray JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_authenticationSelectionReceipt(
    JNIEnv *env, jclass type, jlong handle) {
    struct session_slot *slot;
    unsigned char receipt[32] = {0};
    jbyteArray result = NULL;

    (void)type;
    slot = lock_session(env, handle);
    if (slot == NULL) return NULL;
    if (ctos_ssh_native_client_authentication_selection_receipt(slot->client, receipt) != 0) {
        fail(env);
    } else {
        result = (*env)->NewByteArray(env, 32);
        if (result != NULL) (*env)->SetByteArrayRegion(env, result, 0, 32, (jbyte *)receipt);
    }
    wipe_bytes(receipt, sizeof(receipt));
    pthread_mutex_unlock(&sessions_lock);
    return result;
}

JNIEXPORT jintArray JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_step(
    JNIEnv *env, jclass type, jlong handle, jboolean cancelled) {
    struct session_slot *slot;
    struct ctos_ssh_native_client_step step = {.cancelled = cancelled != JNI_FALSE};
    jint values[8];
    jintArray result;

    (void)type;
    slot = lock_session(env, handle);
    if (slot == NULL) return NULL;
    if (ctos_ssh_native_client_step(slot->client, &step) != 0) {
        enum ctos_ssh_native_host_key_rejection rejection = CTOS_SSH_HOST_REJECTION_NONE;
        (void)ctos_ssh_native_client_host_key_rejection(slot->client, &rejection);
        pthread_mutex_unlock(&sessions_lock);
        fail_step(env, rejection);
        return NULL;
    }
    values[0] = (jint)step.bytes_read;
    values[1] = (jint)step.bytes_written;
    values[2] = (jint)step.input_appended;
    values[3] = (jint)step.packet_type;
    values[4] = (jint)(step.read_would_block != 0);
    values[5] = (jint)(step.write_would_block != 0);
    values[6] = (jint)(step.partial_write != 0);
    values[7] = (jint)(ctos_ssh_native_client_complete(slot->client) != 0);
    result = (*env)->NewIntArray(env, 8);
    if (result != NULL) (*env)->SetIntArrayRegion(env, result, 0, 8, values);
    pthread_mutex_unlock(&sessions_lock);
    return result;
}

#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
static void fixture_ext_consumed(void *context) {
    struct session_slot *slot = context;
    slot->fixture_ext_seen = 1;
}

static int fixture_child_matches(const struct session_slot *slot, jlong callback) {
    return slot->jump_child && slot->jump_state == JUMP_CHILD_OPEN && !slot->client_closed
        && slot->relay != NULL && !slot->relay_closed && slot->parent_token != 0
        && callback > 0 && slot->fixture_callback == (ctos_ssh_callback_token)callback;
}

/* The session table, not a caller-provided readiness flag, selects the real child. */
JNIEXPORT jboolean JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_provisionalNewFixtureReady(
    JNIEnv *env, jclass type, jlong handle, jlong callback) {
    struct session_slot *slot;
    int ready = 0;
    int result;
    (void)type;
    slot = lock_session(env, handle);
    if (slot == NULL) return JNI_FALSE;
    if (!fixture_child_matches(slot, callback)) {
        pthread_mutex_unlock(&sessions_lock);
        fail(env);
        return JNI_FALSE;
    }
    result = ctos_ssh_native_client_provisional_new_ready(slot->client, &ready);
    pthread_mutex_unlock(&sessions_lock);
    if (result != 0) { fail(env); return JNI_FALSE; }
    return ready ? JNI_TRUE : JNI_FALSE;
}

/* Bit 1 is a fixture observation from the native post-validation hook. */
JNIEXPORT jint JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_provisionalNewFixturePoll(
    JNIEnv *env, jclass type, jlong handle, jlong callback) {
    struct session_slot *slot;
    int idle = 0, result, observed;
    (void)type;
    slot = lock_session(env, handle);
    if (slot == NULL) return 0;
    if (!fixture_child_matches(slot, callback)) {
        pthread_mutex_unlock(&sessions_lock);
        fail(env);
        return 0;
    }
    if (!slot->fixture_ext_hook_armed) {
        if (ctos_ssh_native_client_test_provisional_hook(
                slot->client, 2, fixture_ext_consumed, slot) != 0) {
            pthread_mutex_unlock(&sessions_lock);
            fail(env);
            return 0;
        }
        slot->fixture_ext_hook_armed = 1;
    }
    result = ctos_ssh_native_client_provisional_new_poll_ext(slot->client, &idle);
    observed = (idle ? 1 : 0) | (slot->fixture_ext_seen ? 2 : 0);
    pthread_mutex_unlock(&sessions_lock);
    if (result != 0) { fail(env); return 0; }
    return (jint)observed;
}

/* Read the fixture hook without advancing the child or parsing packets. */
JNIEXPORT jboolean JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_provisionalNewFixtureExtSeen(
    JNIEnv *env, jclass type, jlong handle, jlong callback) {
    struct session_slot *slot;
    int seen;
    (void)type;
    slot = lock_session(env, handle);
    if (slot == NULL) return JNI_FALSE;
    if (!fixture_child_matches(slot, callback)) {
        pthread_mutex_unlock(&sessions_lock);
        fail(env);
        return JNI_FALSE;
    }
    seen = slot->fixture_ext_seen;
    pthread_mutex_unlock(&sessions_lock);
    return seen ? JNI_TRUE : JNI_FALSE;
}
#endif

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_verifyHostKey(
    JNIEnv *env, jclass type, jlong handle, jbyteArray host_key_blob) {
    unsigned char *blob = NULL;
    size_t length = 0;
    struct session_slot *slot;

    (void)type;
    if (copy_array(env, host_key_blob, CTOS_SSH_NATIVE_CLIENT_MAX_HOST_KEY_BLOB_BYTES,
            &blob, &length) != 0) {
        fail(env);
        return;
    }
    slot = lock_session(env, handle);
    if (slot == NULL) goto done;
    if (ctos_ssh_native_client_verify_host_key(slot->client, blob, length) != 0)
        fail(env);
    pthread_mutex_unlock(&sessions_lock);
done:
    wipe_bytes(blob, length);
    free(blob);
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_beginUserauthService(
    JNIEnv *env, jclass type, jlong handle) {
    struct session_slot *slot;
    int result;

    (void)type;
    slot = lock_session(env, handle);
    if (slot == NULL) return;
    result = ctos_ssh_native_client_begin_userauth_service(slot->client);
    pthread_mutex_unlock(&sessions_lock);
    if (result != 0) fail(env);
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_beginUserauthServiceGuarded(
    JNIEnv *env, jclass type, jlong handle, jlong generation) {
    (void)type;
    if (generation <= 0 || (uint64_t)generation >= UINT32_MAX) { fail(env); return; }
    struct session_slot *slot = lock_session(env, handle);
    if (slot == NULL) return;
    int result = ctos_ssh_native_client_begin_userauth_service_guarded(slot->client, (uint32_t)generation);
    pthread_mutex_unlock(&sessions_lock);
    if (result != 0) fail(env);
}

JNIEXPORT jboolean JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_userauthServiceReady(
    JNIEnv *env, jclass type, jlong handle) {
    struct session_slot *slot;
    int ready;

    (void)type;
    slot = lock_session(env, handle);
    if (slot == NULL) return JNI_FALSE;
    ready = ctos_ssh_native_client_userauth_service_ready(slot->client);
    pthread_mutex_unlock(&sessions_lock);
    if (ready != 0 && ready != 1) {
        fail(env);
        return JNI_FALSE;
    }
    return ready == 1 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_beginAuthDiscovery(
    JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    struct session_slot *slot = lock_session(env, handle);
    if (slot == NULL) return;
    int status = ctos_ssh_native_client_begin_auth_discovery(slot->client);
    pthread_mutex_unlock(&sessions_lock);
    if (status != 0) fail(env);
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_beginAuthDiscoveryGuarded(
    JNIEnv *env, jclass type, jlong handle, jlong generation) {
    (void)type;
    if (generation <= 0 || (uint64_t)generation >= UINT32_MAX) { fail(env); return; }
    struct session_slot *slot = lock_session(env, handle);
    if (slot == NULL) return;
    int result = ctos_ssh_native_client_begin_auth_discovery_guarded(slot->client, (uint32_t)generation);
    pthread_mutex_unlock(&sessions_lock);
    if (result != 0) fail(env);
}

JNIEXPORT jintArray JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_authDiscoveryResult(
    JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    struct session_slot *slot = lock_session(env, handle);
    if (slot == NULL) return NULL;
    enum ctos_ssh_native_client_discovery_result outcome = CTOS_SSH_DISCOVERY_NOT_STARTED;
    unsigned int methods = 0;
    int status = ctos_ssh_native_client_auth_discovery_result(slot->client, &outcome, &methods);
    pthread_mutex_unlock(&sessions_lock);
    if (status != 0 || (unsigned int)outcome > 4U || methods > 15U) { fail(env); return NULL; }
    jint values[2] = {(jint)outcome, (jint)methods};
    jintArray result = (*env)->NewIntArray(env, 2);
    if (result != NULL) (*env)->SetIntArrayRegion(env, result, 0, 2, values);
    return result;
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_beginPasswordAuth(
    JNIEnv *env, jclass type, jlong handle) {
    struct session_slot *slot;
    int result;

    (void)type;
    slot = lock_session(env, handle);
    if (slot == NULL) return;
    result = ctos_ssh_native_client_begin_password_auth(slot->client);
    pthread_mutex_unlock(&sessions_lock);
    if (result != 0) fail(env);
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_beginPasswordAuthGuarded(
    JNIEnv *env, jclass type, jlong handle, jlong generation) {
    (void)type;
    if (generation <= 0 || (uint64_t)generation >= UINT32_MAX) { fail(env); return; }
    struct session_slot *slot = lock_session(env, handle);
    if (slot == NULL) return;
    int result = ctos_ssh_native_client_begin_password_auth_guarded(slot->client, (uint32_t)generation);
    pthread_mutex_unlock(&sessions_lock);
    if (result != 0) fail(env);
}

JNIEXPORT jint JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_passwordAuthResult(
    JNIEnv *env, jclass type, jlong handle) {
    struct session_slot *slot;
    enum ctos_ssh_native_client_password_auth_result outcome = CTOS_SSH_PASSWORD_AUTH_NOT_STARTED;
    jint result = -1;

    (void)type;
    slot = lock_session(env, handle);
    if (slot == NULL) return -1;
    if (ctos_ssh_native_client_password_auth_result(slot->client, &outcome) == 0) {
        switch (outcome) {
            case CTOS_SSH_PASSWORD_AUTH_NOT_STARTED: result = 0; break;
            case CTOS_SSH_PASSWORD_AUTH_PENDING: result = 1; break;
            case CTOS_SSH_PASSWORD_AUTH_SUCCESS: result = 2; break;
            case CTOS_SSH_PASSWORD_AUTH_REJECTED: result = 3; break;
            case CTOS_SSH_PASSWORD_AUTH_PARTIAL: result = 4; break;
            case CTOS_SSH_PASSWORD_AUTH_CHANGE_REQUIRED: result = 5; break;
            default: break;
        }
    }
    pthread_mutex_unlock(&sessions_lock);
    if (result < 0) fail(env);
    return result;
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_beginKeyboardInteractiveAuth(
    JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    struct session_slot *slot = lock_session(env, handle);
    if (slot == NULL) return;
    int result = ctos_ssh_native_client_begin_keyboard_interactive_auth(slot->client);
    pthread_mutex_unlock(&sessions_lock);
    if (result != 0) fail(env);
}

JNIEXPORT jint JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_keyboardInteractiveAuthResult(
    JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    struct session_slot *slot = lock_session(env, handle);
    if (slot == NULL) return -1;
    enum ctos_ssh_native_client_keyboard_interactive_auth_result outcome = CTOS_SSH_KEYBOARD_AUTH_NOT_STARTED;
    int status = ctos_ssh_native_client_keyboard_interactive_auth_result(slot->client, &outcome);
    pthread_mutex_unlock(&sessions_lock);
    if (status != 0 || (unsigned int)outcome > 5U) { fail(env); return -1; }
    return (jint)outcome;
}

JNIEXPORT jbyteArray JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_continueKeyboardInteractiveAuth(
    JNIEnv *env, jclass type, jlong handle, jbyteArray expected) {
    (void)type;
    unsigned char plan[32] = {0}, stage[92] = {0};
    jbyteArray result = NULL;
    if (expected == NULL || (*env)->GetArrayLength(env, expected) != 32) { fail(env); return NULL; }
    (*env)->GetByteArrayRegion(env, expected, 0, 32, (jbyte *)plan);
    if ((*env)->ExceptionCheck(env)) goto out;
    struct session_slot *slot = lock_session(env, handle);
    if (slot == NULL) goto out;
    int status = ctos_ssh_native_client_continue_keyboard_interactive_auth(slot->client, plan, stage);
    pthread_mutex_unlock(&sessions_lock);
    if (status != 0) { fail(env); goto out; }
    result = (*env)->NewByteArray(env, 92);
    if (result != NULL) (*env)->SetByteArrayRegion(env, result, 0, 92, (jbyte *)stage);
out:
    wipe_bytes(plan, sizeof(plan)); wipe_bytes(stage, sizeof(stage));
    return result;
}

JNIEXPORT jbyteArray JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_beginDiscoveredKeyboard(
    JNIEnv *env, jclass type, jlong handle, jbyteArray expected) {
    (void)type;
    unsigned char plan[32] = {0}, stage[92] = {0};
    jbyteArray result = NULL;
    if (expected == NULL || (*env)->GetArrayLength(env, expected) != 32) { fail(env); return NULL; }
    (*env)->GetByteArrayRegion(env, expected, 0, 32, (jbyte *)plan);
    if ((*env)->ExceptionCheck(env)) goto out;
    struct session_slot *slot = lock_session(env, handle);
    if (slot == NULL) goto out;
    int status = ctos_ssh_native_client_begin_discovered_keyboard(slot->client, plan, stage);
    pthread_mutex_unlock(&sessions_lock);
    if (status != 0) { fail(env); goto out; }
    result = (*env)->NewByteArray(env, 92);
    if (result != NULL) (*env)->SetByteArrayRegion(env, result, 0, 92, (jbyte *)stage);
out:
    wipe_bytes(plan, sizeof(plan)); wipe_bytes(stage, sizeof(stage));
    return result;
}

JNIEXPORT jbyteArray JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_beginDiscoveredPassword(
    JNIEnv *env, jclass type, jlong handle, jbyteArray expected, jbyteArray input) {
    (void)type;
    unsigned char plan[32] = {0}, stage[92] = {0}, *password = NULL;
    size_t length = 0;
    jbyteArray result = NULL;
    if (expected == NULL || (*env)->GetArrayLength(env, expected) != 32) { fail(env); return NULL; }
    (*env)->GetByteArrayRegion(env, expected, 0, 32, (jbyte *)plan);
    if ((*env)->ExceptionCheck(env)) goto out;
    struct session_slot *slot = lock_session(env, handle);
    if (slot == NULL) goto out;
    if (ctos_ssh_native_client_admit_input_copy(slot->client) != 0
        || copy_array(env, input, CTOS_SSH_NATIVE_CLIENT_MAX_PASSWORD_BYTES, &password, &length) != 0) {
        pthread_mutex_unlock(&sessions_lock);
        fail(env); goto out;
    }
    int status = ctos_ssh_native_client_begin_discovered_password(slot->client, plan, password, length, stage);
    pthread_mutex_unlock(&sessions_lock);
    if (status != 0) { fail(env); goto out; }
    result = (*env)->NewByteArray(env, 92);
    if (result != NULL) (*env)->SetByteArrayRegion(env, result, 0, 92, (jbyte *)stage);
out:
    wipe_bytes(password, length); free(password);
    wipe_bytes(plan, sizeof(plan)); wipe_bytes(stage, sizeof(stage));
    return result;
}

JNIEXPORT jint JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_authContinuationMethods(
    JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    struct session_slot *slot = lock_session(env, handle);
    if (slot == NULL) return -1;
    unsigned int methods = 0;
    int status = ctos_ssh_native_client_auth_continuation_methods(slot->client, &methods);
    pthread_mutex_unlock(&sessions_lock);
    if (status != 0 || methods > 15U) { fail(env); return -1; }
    return (jint)methods;
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_pollAuthPrompt(
    JNIEnv *env, jclass type, jlong handle, jbyteArray expected, jint method) {
    (void)type;
    unsigned char plan[32] = {0};
    if (expected == NULL || (*env)->GetArrayLength(env, expected) != 32 || method < 0) { fail(env); return; }
    (*env)->GetByteArrayRegion(env, expected, 0, 32, (jbyte *)plan);
    if ((*env)->ExceptionCheck(env)) goto out;
    struct session_slot *slot = lock_session(env, handle);
    if (slot == NULL) goto out;
    int status = ctos_ssh_native_client_poll_auth_prompt(slot->client, plan, (unsigned int)method);
    pthread_mutex_unlock(&sessions_lock);
    if (status != 0) fail(env);
out:
    wipe_bytes(plan, sizeof(plan));
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_pollSelectedPasswordPrompt(
    JNIEnv *env, jclass type, jlong handle, jbyteArray expected) {
    (void)type;
    unsigned char plan[32] = {0};
    if (expected == NULL || (*env)->GetArrayLength(env, expected) != 32) { fail(env); return; }
    (*env)->GetByteArrayRegion(env, expected, 0, 32, (jbyte *)plan);
    if ((*env)->ExceptionCheck(env)) goto out;
    struct session_slot *slot = lock_session(env, handle);
    if (slot == NULL) goto out;
    int status = ctos_ssh_native_client_poll_selected_password_prompt(slot->client, plan);
    pthread_mutex_unlock(&sessions_lock);
    if (status != 0) fail(env);
out:
    wipe_bytes(plan, sizeof(plan));
}

JNIEXPORT jbyteArray JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_continuePrivateKeyAuth(
    JNIEnv *env, jclass type, jlong handle, jbyteArray expected) {
    (void)type;
    unsigned char plan[32] = {0}, stage[92] = {0};
    jbyteArray result = NULL;
    if (expected == NULL || (*env)->GetArrayLength(env, expected) != 32) { fail(env); return NULL; }
    (*env)->GetByteArrayRegion(env, expected, 0, 32, (jbyte *)plan);
    if ((*env)->ExceptionCheck(env)) goto out;
    struct session_slot *slot = lock_session(env, handle);
    if (slot == NULL) goto out;
    int status = ctos_ssh_native_client_continue_private_key_auth(slot->client, plan, stage);
    pthread_mutex_unlock(&sessions_lock);
    if (status != 0) { fail(env); goto out; }
    result = (*env)->NewByteArray(env, 92);
    if (result != NULL) (*env)->SetByteArrayRegion(env, result, 0, 92, (jbyte *)stage);
out:
    wipe_bytes(plan, sizeof(plan)); wipe_bytes(stage, sizeof(stage));
    return result;
}

JNIEXPORT jbyteArray JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_keyboardInteractiveChallenge(
    JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    unsigned char *bytes = calloc(1, CTOS_SSH_NATIVE_CLIENT_KBI_WIRE_BYTES);
    size_t length = 0;
    jbyteArray result = NULL;
    if (bytes == NULL) { fail(env); return NULL; }
    struct session_slot *slot = lock_session(env, handle);
    if (slot != NULL) {
        if (ctos_ssh_native_client_keyboard_interactive_challenge(slot->client, bytes,
                CTOS_SSH_NATIVE_CLIENT_KBI_WIRE_BYTES, &length) != 0
            || length < 24U || length > CTOS_SSH_NATIVE_CLIENT_KBI_WIRE_BYTES) fail(env);
        else {
            result = (*env)->NewByteArray(env, (jsize)length);
            if (result != NULL) (*env)->SetByteArrayRegion(env, result, 0, (jsize)length, (jbyte *)bytes);
        }
        pthread_mutex_unlock(&sessions_lock);
    }
    wipe_bytes(bytes, CTOS_SSH_NATIVE_CLIENT_KBI_WIRE_BYTES);
    free(bytes);
    return result;
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_respondKeyboardInteractive(
    JNIEnv *env, jclass type, jlong handle, jbyteArray input) {
    (void)type;
    unsigned char *bytes = NULL;
    size_t length = 0;
    struct session_slot *slot = lock_session(env, handle);
    if (slot != NULL) {
        if (ctos_ssh_native_client_admit_input_copy(slot->client) != 0
            || copy_array(env, input, CTOS_SSH_NATIVE_CLIENT_KBI_WIRE_BYTES, &bytes, &length) != 0
            || ctos_ssh_native_client_respond_keyboard_interactive(slot->client, bytes, length) != 0) fail(env);
        pthread_mutex_unlock(&sessions_lock);
    }
    wipe_bytes(bytes, length);
    free(bytes);
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_beginPrivateKeyAuth(
    JNIEnv *env, jclass type, jlong handle) {
    struct session_slot *slot;
    int result;

    (void)type;
    slot = lock_session(env, handle);
    if (slot == NULL) return;
    result = ctos_ssh_native_client_begin_private_key_auth(slot->client);
    pthread_mutex_unlock(&sessions_lock);
    if (result != 0) fail(env);
}

JNIEXPORT jint JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_privateKeyAuthResult(
    JNIEnv *env, jclass type, jlong handle) {
    struct session_slot *slot;
    enum ctos_ssh_native_client_private_key_auth_result outcome = CTOS_SSH_PRIVATE_KEY_AUTH_NOT_STARTED;
    jint result = -1;

    (void)type;
    slot = lock_session(env, handle);
    if (slot == NULL) return -1;
    if (ctos_ssh_native_client_private_key_auth_result(slot->client, &outcome) == 0) {
        switch (outcome) {
            case CTOS_SSH_PRIVATE_KEY_AUTH_NOT_STARTED: result = 0; break;
            case CTOS_SSH_PRIVATE_KEY_AUTH_PENDING: result = 1; break;
            case CTOS_SSH_PRIVATE_KEY_AUTH_SUCCESS: result = 2; break;
            case CTOS_SSH_PRIVATE_KEY_AUTH_REJECTED: result = 3; break;
            case CTOS_SSH_PRIVATE_KEY_AUTH_PARTIAL: result = 4; break;
            default: break;
        }
    }
    pthread_mutex_unlock(&sessions_lock);
    if (result < 0) fail(env);
    return result;
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_configureServerAlive(
    JNIEnv *env, jclass type, jlong handle, jint interval, jint count_max) {
    struct session_slot *slot;
    int result;

    (void)type;
    if (interval < 0 || count_max < 0) { fail(env); return; }
    slot = lock_session(env, handle);
    if (slot == NULL) return;
    result = ctos_ssh_native_client_configure_server_alive(slot->client,
        (unsigned int)interval, (unsigned int)count_max);
    pthread_mutex_unlock(&sessions_lock);
    if (result != 0) fail(env);
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_configureNoPty(
    JNIEnv *env, jclass type, jlong handle) {
    struct session_slot *slot;
    int result;

    (void)type;
    slot = lock_session(env, handle);
    if (slot == NULL) return;
    result = ctos_ssh_native_client_configure_no_pty(slot->client);
    pthread_mutex_unlock(&sessions_lock);
    if (result != 0) fail(env);
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_beginShell(
    JNIEnv *env, jclass type, jlong handle, jint columns, jint rows) {
    struct session_slot *slot;
    int result;

    (void)type;
    if (columns < 1 || columns > 4096 || rows < 1 || rows > 4096) {
        fail(env);
        return;
    }
    slot = lock_session(env, handle);
    if (slot == NULL) return;
    result = ctos_ssh_native_client_begin_shell(slot->client,
        (unsigned int)columns, (unsigned int)rows);
    if (result == 0 && release_fresh_hostfile_view(slot) != 0) result = -1;
    pthread_mutex_unlock(&sessions_lock);
    if (result != 0) fail(env);
}

JNIEXPORT jintArray JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_shellStatus(
    JNIEnv *env, jclass type, jlong handle) {
    struct session_slot *slot;
    struct ctos_ssh_native_shell_status status = {0};
    jint values[7];
    jintArray result = NULL;

    (void)type;
    slot = lock_session(env, handle);
    if (slot == NULL) return NULL;
    if (ctos_ssh_native_client_shell_status(slot->client, &status) != 0
        || (unsigned int)status.phase > 6U
        || (status.remote_eof != 0 && status.remote_eof != 1)
        || (status.local_eof != 0 && status.local_eof != 1)
        || (status.exit_status_known != 0 && status.exit_status_known != 1)
        || (status.exit_signal != 0 && status.exit_signal != 1)
        || (!status.exit_status_known && status.exit_status != 0)
        || (status.exit_status_known && status.exit_signal)
        || status.buffered_bytes > CTOS_SSH_SESSION_BUFFER_BYTES) {
        fail(env);
    } else {
        values[0] = (jint)status.phase;
        values[1] = (jint)status.remote_eof;
        values[2] = (jint)status.local_eof;
        values[3] = (jint)status.exit_status_known;
        values[4] = (jint)(int32_t)status.exit_status;
        values[5] = (jint)status.exit_signal;
        values[6] = (jint)status.buffered_bytes;
        result = (*env)->NewIntArray(env, 7);
        if (result != NULL) (*env)->SetIntArrayRegion(env, result, 0, 7, values);
    }
    pthread_mutex_unlock(&sessions_lock);
    return result;
}

JNIEXPORT jint JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_writeShell(
    JNIEnv *env, jclass type, jlong handle, jbyteArray input) {
    struct session_slot *slot;
    unsigned char *bytes = NULL;
    size_t length = 0;
    size_t accepted = 0;
    jint result = -1;

    (void)type;
    if (copy_array(env, input, CTOS_SSH_SESSION_IO_BYTES, &bytes, &length) != 0) {
        fail(env);
        return -1;
    }
    slot = lock_session(env, handle);
    if (slot != NULL) {
        if (ctos_ssh_native_client_shell_write(slot->client, bytes, length, &accepted) != 0
            || accepted > length) fail(env);
        else result = (jint)accepted;
        pthread_mutex_unlock(&sessions_lock);
    }
    wipe_bytes(bytes, length);
    free(bytes);
    return result;
}

JNIEXPORT jbyteArray JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_readShell(
    JNIEnv *env, jclass type, jlong handle, jint maximum) {
    struct session_slot *slot;
    unsigned char *bytes;
    size_t length = 0;
    jbyteArray result = NULL;

    (void)type;
    if (maximum <= 0 || (unsigned int)maximum > CTOS_SSH_SESSION_IO_BYTES) {
        fail(env);
        return NULL;
    }
    bytes = calloc(1, (size_t)maximum);
    if (bytes == NULL) {
        fail(env);
        return NULL;
    }
    slot = lock_session(env, handle);
    if (slot != NULL) {
        if (ctos_ssh_native_client_shell_read(slot->client, bytes, (size_t)maximum, &length) != 0
            || length > (size_t)maximum) fail(env);
        else {
            result = (*env)->NewByteArray(env, (jsize)length);
            if (result != NULL && length != 0)
                (*env)->SetByteArrayRegion(env, result, 0, (jsize)length, (jbyte *)bytes);
        }
        pthread_mutex_unlock(&sessions_lock);
    }
    wipe_bytes(bytes, (size_t)maximum);
    free(bytes);
    return result;
}

JNIEXPORT jint JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_resizeShell(
    JNIEnv *env, jclass type, jlong handle, jint columns, jint rows) {
    struct session_slot *slot;
    int result;

    (void)type;
    if (columns < 1 || columns > 4096 || rows < 1 || rows > 4096) {
        fail(env);
        return -1;
    }
    slot = lock_session(env, handle);
    if (slot == NULL) return -1;
    result = ctos_ssh_native_client_shell_resize(slot->client,
        (unsigned int)columns, (unsigned int)rows);
    pthread_mutex_unlock(&sessions_lock);
    if (result != 0 && result != 1) {
        fail(env);
        return -1;
    }
    return (jint)result;
}

JNIEXPORT jint JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_sendShellEof(
    JNIEnv *env, jclass type, jlong handle) {
    struct session_slot *slot;
    int result;

    (void)type;
    slot = lock_session(env, handle);
    if (slot == NULL) return -1;
    result = ctos_ssh_native_client_shell_eof(slot->client);
    pthread_mutex_unlock(&sessions_lock);
    if (result != 0 && result != 1) {
        fail(env);
        return -1;
    }
    return (jint)result;
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_close(
    JNIEnv *env, jclass type, jlong handle) {
    struct session_slot *slot;
    int result;

    (void)type;
    slot = lock_session_for(env, handle, 1);
    if (slot == NULL) return;
    if (has_children_locked(slot->token)) {
        pthread_mutex_unlock(&sessions_lock);
        fail(env);
        return;
    }
    if (slot->jump_child && slot->client_closed) {
        pthread_mutex_unlock(&sessions_lock);
        return;
    }
    result = ctos_ssh_native_client_close(slot->client);
    if (result == 0) slot->client_closed = 1;
    pthread_mutex_unlock(&sessions_lock);
    if (result != 0) fail(env);
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_destroy(
    JNIEnv *env, jclass type, jlong handle) {
    struct session_slot *slot;

    (void)type;
    slot = lock_session_for(env, handle, 1);
    if (slot == NULL) return;
    if (has_children_locked(slot->token)
        || (slot->jump_child && (!slot->client_closed || close_jump_relay_locked(slot) != 0))
        || release_fresh_hostfile_view(slot) != 0
        || ctos_ssh_native_client_destroy(slot->client) != 0) {
        fail(env);
        pthread_mutex_unlock(&sessions_lock);
        return;
    }
    memset(slot, 0, sizeof(*slot));
    pthread_mutex_unlock(&sessions_lock);
}

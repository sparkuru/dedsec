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

struct session_slot {
    uint64_t token;
    pthread_t owner_thread;
    struct ctos_ssh_native_client *client;
    int opening;
};

static pthread_mutex_t sessions_lock = PTHREAD_MUTEX_INITIALIZER;
static struct session_slot sessions[CTOS_SSH_SESSION_SLOTS];
static uint64_t next_session_token;

static void wipe_bytes(void *memory, size_t length) {
    volatile unsigned char *bytes = memory;

    if (bytes == NULL) return;
    while (length-- != 0) *bytes++ = 0;
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
    if (slot == NULL || next_session_token == UINT64_MAX) {
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

static int publish_session(uint64_t token, struct ctos_ssh_native_client *client) {
    struct session_slot *slot;

    if (client == NULL || pthread_mutex_lock(&sessions_lock) != 0) {
        errno = EBUSY;
        return -1;
    }
    slot = find_session_locked(token);
    if (slot == NULL || !slot->opening
        || !pthread_equal(slot->owner_thread, pthread_self())) {
        pthread_mutex_unlock(&sessions_lock);
        errno = EACCES;
        return -1;
    }
    slot->client = client;
    slot->opening = 0;
    pthread_mutex_unlock(&sessions_lock);
    return 0;
}

static struct session_slot *lock_session(JNIEnv *env, jlong handle) {
    struct session_slot *slot;

    if (handle <= 0 || pthread_mutex_lock(&sessions_lock) != 0) {
        fail(env);
        return NULL;
    }
    slot = find_session_locked((uint64_t)handle);
    if (slot == NULL || slot->opening || slot->client == NULL
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

JNIEXPORT jlong JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_open(
    JNIEnv *env, jclass type, jlong role, jint owner_uid, jlong callback,
    jlong view, jint socket_fd, jlong deadline, jint step_budget,
    jbyteArray host_key_blob, jbyteArray expected_fingerprint,
    jstring known_hosts_path, jstring host, jstring candidate_id) {
    unsigned char *key_blob = NULL;
    size_t key_blob_length = 0;
    unsigned char fingerprint[CTOS_SSH_NATIVE_CLIENT_FINGERPRINT_BYTES] = {0};
    char known_hosts[513] = {0};
    char host_name[CTOS_SSH_ROLE_HOST_BYTES + 1U] = {0};
    char candidate[CTOS_SSH_CALLBACK_CANDIDATE_ID_BYTES] = {0};
    struct ctos_ssh_view_borrow *borrow = NULL;
    struct ctos_ssh_native_client_config config;
    struct ctos_ssh_native_client *client = NULL;
    uint64_t token = 0;
    int success = 0;

    (void)type;
    if (env == NULL || role <= 0 || callback <= 0 || view <= 0 || socket_fd < 0
        || owner_uid < 0 || deadline <= 0 || step_budget <= 0
        || (size_t)step_budget > CTOS_SSH_NATIVE_CLIENT_MAX_STEP_BYTES
        || copy_array(env, host_key_blob, CTOS_SSH_NATIVE_CLIENT_MAX_HOST_KEY_BLOB_BYTES,
            &key_blob, &key_blob_length) != 0
        || copy_fingerprint(env, expected_fingerprint, fingerprint) != 0
        || copy_string(env, known_hosts_path, known_hosts, sizeof(known_hosts)) != 0
        || copy_string(env, host, host_name, sizeof(host_name)) != 0
        || copy_string(env, candidate_id, candidate, sizeof(candidate)) != 0
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
        .server_host_public_key_blob = key_blob,
        .server_host_public_key_length = key_blob_length,
        .expected_host_fingerprint = fingerprint,
        .managed_view_borrow = borrow,
        .known_hosts_path = known_hosts,
        .host = host_name,
        .candidate_id = candidate
    };
    if (ctos_ssh_native_client_open(&config, &client) != 0) goto failed;
    borrow = NULL; /* ownership transferred to the native session */
    if (publish_session(token, client) != 0) {
        (void)ctos_ssh_native_client_close(client);
        (void)ctos_ssh_native_client_destroy(client);
        client = NULL;
        goto failed;
    }
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
        pthread_mutex_unlock(&sessions_lock);
        fail(env);
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

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_close(
    JNIEnv *env, jclass type, jlong handle) {
    struct session_slot *slot;

    (void)type;
    slot = lock_session(env, handle);
    if (slot == NULL) return;
    if (ctos_ssh_native_client_close(slot->client) != 0) fail(env);
    pthread_mutex_unlock(&sessions_lock);
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_destroy(
    JNIEnv *env, jclass type, jlong handle) {
    struct session_slot *slot;

    (void)type;
    slot = lock_session(env, handle);
    if (slot == NULL) return;
    if (ctos_ssh_native_client_destroy(slot->client) != 0) {
        fail(env);
        pthread_mutex_unlock(&sessions_lock);
        return;
    }
    memset(slot, 0, sizeof(*slot));
    pthread_mutex_unlock(&sessions_lock);
}

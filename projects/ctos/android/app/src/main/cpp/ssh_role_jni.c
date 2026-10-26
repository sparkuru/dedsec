#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <jni.h>

#include <limits.h>
#include <errno.h>
#include <pwd.h>
#include <stdint.h>
#include <string.h>
#include <sys/types.h>
#include <unistd.h>

#include "ssh_role_bootstrap.h"

enum role_text_kind {
    ROLE_IDENTIFIER,
    ROLE_TARGET,
    ROLE_HOST
};

static void wipe(void *memory, size_t length) {
    volatile unsigned char *bytes = memory;
    while (length-- != 0) *bytes++ = 0;
}

static void fail(JNIEnv *env) {
    if (env == NULL || (*env)->ExceptionCheck(env)) return;
    jclass error = (*env)->FindClass(env, "java/io/IOException");
    if (error != NULL) {
        (*env)->ThrowNew(env, error, "Protected SSH role bootstrap unavailable");
        (*env)->DeleteLocalRef(env, error);
    }
}

static int ascii_letter_or_digit(unsigned char value) {
    return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z')
        || (value >= '0' && value <= '9');
}

static int allowed_text(jchar value, enum role_text_kind kind) {
    if (value > 0x7f) return 0;
    unsigned char ch = (unsigned char)value;
    if (kind == ROLE_IDENTIFIER) return ascii_letter_or_digit(ch) || ch == '_' || ch == '-';
    if (kind == ROLE_TARGET) {
        return ascii_letter_or_digit(ch) || ch == '_' || ch == '-' || ch == '.'
            || ch == ':' || ch == '@' || ch == '+' || ch == '/';
    }
    return ascii_letter_or_digit(ch) || ch == '.' || ch == ':' || ch == '%'
        || ch == '[' || ch == ']' || ch == '-';
}

static int copy_text(JNIEnv *env, jstring value, char *output, size_t capacity,
    enum role_text_kind kind) {
    jsize length;
    const jchar *chars;
    int valid = 1;
    if (env == NULL || value == NULL || output == NULL || capacity == 0) return -1;
    output[0] = '\0';
    length = (*env)->GetStringLength(env, value);
    if ((*env)->ExceptionCheck(env) || length <= 0 || (size_t)length >= capacity) return -1;
    chars = (*env)->GetStringChars(env, value, NULL);
    if (chars == NULL) return -1;
    for (jsize index = 0; index < length; index++) {
        if (!allowed_text(chars[index], kind)) { valid = 0; break; }
    }
    if (valid) {
        for (jsize index = 0; index < length; index++) output[index] = (char)chars[index];
        output[length] = '\0';
    }
    (*env)->ReleaseStringChars(env, value, chars);
    return valid ? 0 : -1;
}

static int copy_binding(JNIEnv *env, jbyteArray value, unsigned char output[32]) {
    if (env == NULL || value == NULL || (*env)->GetArrayLength(env, value) != 32) return -1;
    (*env)->GetByteArrayRegion(env, value, 0, 32, (jbyte *)output);
    if ((*env)->ExceptionCheck(env)) return -1;
    unsigned char combined = 0;
    for (size_t index = 0; index < 32; index++) combined |= output[index];
    return combined == 0 ? -1 : 0;
}

static int current_uid_matches(jint supplied) {
    uint64_t current = (uint64_t)getuid();
    return supplied >= 0 && current <= INT32_MAX && (uint64_t)(uint32_t)supplied == current;
}

static int same_bytes(const unsigned char *left, const unsigned char *right, size_t length) {
    unsigned char difference = 0;
    for (size_t index = 0; index < length; index++) difference |= left[index] ^ right[index];
    return difference == 0;
}

JNIEXPORT jbyteArray JNICALL Java_im_majo_ctos_SshNativeRoleBootstrap_00024Native_open(
    JNIEnv *env, jclass type, jint program, jint hop, jint port, jlong deadline,
    jstring connection_id, jstring target_identity, jstring host, jstring public_view_id,
    jbyteArray role_nonce, jbyteArray connection_binding) {
    (void)type;
    char connection[CTOS_SSH_ROLE_CONNECTION_ID_BYTES + 1] = {0};
    char target[CTOS_SSH_ROLE_TARGET_ID_BYTES + 1] = {0};
    char host_name[CTOS_SSH_ROLE_HOST_BYTES + 1] = {0};
    char view[CTOS_SSH_ROLE_VIEW_ID_BYTES + 1] = {0};
    unsigned char nonce[32] = {0};
    unsigned char binding[32] = {0};
    unsigned char encoded[CTOS_SSH_ROLE_WIRE_BYTES] = {0};
    ctos_ssh_role_token token = 0;
    jbyteArray result = NULL;
    size_t encoded_length = 0;
    int keep_token = 0;

    pid_t owner_pid = getpid();
    uid_t owner_uid = getuid();
    if (program < CTOS_SSH_ROLE_CLIENT || program > CTOS_SSH_ROLE_JUMP
        || hop < 0 || (unsigned int)hop >= CTOS_SSH_ROLE_MAX || port < 1 || port > 65535
        || deadline <= 0 || owner_pid <= 0 || (uint64_t)owner_pid > INT32_MAX
        || (uint64_t)owner_uid > INT32_MAX
        || copy_text(env, connection_id, connection, sizeof(connection), ROLE_IDENTIFIER) != 0
        || copy_text(env, target_identity, target, sizeof(target), ROLE_TARGET) != 0
        || copy_text(env, host, host_name, sizeof(host_name), ROLE_HOST) != 0
        || copy_text(env, public_view_id, view, sizeof(view), ROLE_IDENTIFIER) != 0
        || copy_binding(env, role_nonce, nonce) != 0
        || copy_binding(env, connection_binding, binding) != 0) goto done;

    struct ctos_ssh_role_request request = {
        .program = (unsigned int)program,
        .owner_pid = owner_pid,
        .owner_uid = owner_uid,
        .hop = (unsigned int)hop,
        .port = (unsigned int)port,
        .deadline = (uint64_t)deadline,
        .connection_id = connection,
        .target_identity = target,
        .host = host_name,
        .public_view_id = view,
        .role_nonce = nonce,
        .connection_binding = binding
    };
    if (ctos_ssh_role_bootstrap_open(&request, &token) != 0 || token == 0) goto done;
    if (ctos_ssh_role_bootstrap_encode(token, encoded, sizeof(encoded), &encoded_length) != 0
        || encoded_length == 0 || encoded_length > CTOS_SSH_ROLE_WIRE_BYTES) goto done;

    result = (*env)->NewByteArray(env, (jsize)encoded_length);
    if (result == NULL || (*env)->ExceptionCheck(env)) goto done;
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)encoded_length, (jbyte *)encoded);
    if ((*env)->ExceptionCheck(env)) goto done;
    keep_token = 1;

done:
    if (!keep_token && token != 0) (void)ctos_ssh_role_bootstrap_close(token);
    wipe(connection, sizeof(connection));
    wipe(target, sizeof(target));
    wipe(host_name, sizeof(host_name));
    wipe(view, sizeof(view));
    wipe(nonce, sizeof(nonce));
    wipe(binding, sizeof(binding));
    wipe(encoded, sizeof(encoded));
    if (!keep_token) {
        if (result != NULL) (*env)->DeleteLocalRef(env, result);
        fail(env);
        return NULL;
    }
    return result;
}

JNIEXPORT jboolean JNICALL Java_im_majo_ctos_SshNativeRoleBootstrap_00024Native_owns(
    JNIEnv *env, jclass type, jlong token, jint owner_uid, jbyteArray wire_array) {
    (void)type;
    unsigned char wire[CTOS_SSH_ROLE_WIRE_BYTES] = {0};
    unsigned char canonical[CTOS_SSH_ROLE_WIRE_BYTES] = {0};
    struct ctos_ssh_role_snapshot snapshot = {0};
    size_t canonical_length = 0;
    jsize wire_length;
    int owned = 0;

    if (token <= 0 || !current_uid_matches(owner_uid) || wire_array == NULL) goto done;
    wire_length = (*env)->GetArrayLength(env, wire_array);
    if ((*env)->ExceptionCheck(env) || wire_length <= 0
        || (size_t)wire_length > CTOS_SSH_ROLE_WIRE_BYTES) goto done;
    (*env)->GetByteArrayRegion(env, wire_array, 0, wire_length, (jbyte *)wire);
    if ((*env)->ExceptionCheck(env)) goto done;
    if (ctos_ssh_role_bootstrap_snapshot((ctos_ssh_role_token)token, &snapshot) != 0
        || snapshot.owner_pid != getpid() || snapshot.owner_uid != getuid()) goto done;
    if (ctos_ssh_role_bootstrap_encode((ctos_ssh_role_token)token, canonical,
            sizeof(canonical), &canonical_length) != 0
        || canonical_length != (size_t)wire_length) goto done;
    owned = same_bytes(wire, canonical, canonical_length);

done:
    wipe(&snapshot, sizeof(snapshot));
    wipe(wire, sizeof(wire));
    wipe(canonical, sizeof(canonical));
    return owned ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeRoleBootstrap_00024Native_release(
    JNIEnv *env, jclass type, jlong token, jint owner_uid) {
    (void)type;
    if (token <= 0 || !current_uid_matches(owner_uid)) {
        fail(env);
        return;
    }
    /* A naturally expired unpinned token may already have been reaped. */
    if (ctos_ssh_role_bootstrap_close((ctos_ssh_role_token)token) != 0 && errno != ENOENT)
        fail(env);
}

JNIEXPORT jbyteArray JNICALL Java_im_majo_ctos_SshNativeRoleBootstrap_00024Native_systemIdentity(
    JNIEnv *env, jclass type) {
    (void)type;
    struct passwd account = {0}, *found = NULL;
    char scratch[16384] = {0};
    char host[256] = {0};
    unsigned char wire[514] = {0};
    jbyteArray result = NULL;
    size_t user_length, host_length, length;
    if (getpwuid_r(getuid(), &account, scratch, sizeof(scratch), &found) != 0
        || found == NULL || found->pw_name == NULL
        || gethostname(host, sizeof(host)) != 0) goto done;
    user_length = strnlen(found->pw_name, 256);
    host_length = strnlen(host, sizeof(host));
    if (user_length == 0 || user_length > 255 || host_length == 0 || host_length > 255)
        goto done;
    wire[0] = (unsigned char)(user_length >> 8);
    wire[1] = (unsigned char)user_length;
    memcpy(wire + 2, found->pw_name, user_length);
    wire[user_length + 2] = (unsigned char)(host_length >> 8);
    wire[user_length + 3] = (unsigned char)host_length;
    memcpy(wire + user_length + 4, host, host_length);
    length = user_length + host_length + 4;
    result = (*env)->NewByteArray(env, (jsize)length);
    if (result != NULL)
        (*env)->SetByteArrayRegion(env, result, 0, (jsize)length, (jbyte *)wire);
done:
    wipe(&account, sizeof(account));
    wipe(scratch, sizeof(scratch));
    wipe(host, sizeof(host));
    wipe(wire, sizeof(wire));
    if (result == NULL || (*env)->ExceptionCheck(env)) {
        if (result != NULL) (*env)->DeleteLocalRef(env, result);
        fail(env);
        return NULL;
    }
    return result;
}

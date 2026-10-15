#include <jni.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>

#include "ssh_view_handle.h"

static void wipe_bytes(void *memory, size_t length) {
    volatile unsigned char *bytes = memory;

    if (bytes == NULL) return;
    while (length-- != 0) *bytes++ = 0;
}

static void fail(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass error = (*env)->FindClass(env, "java/io/IOException");
    if (error != NULL) (*env)->ThrowNew(env, error, "Protected SSH file view unavailable");
}

JNIEXPORT jlong JNICALL Java_im_majo_ctos_SshPublicFileLease_00024Native_create(
    JNIEnv *env, jclass type, jbyteArray wire) {
    unsigned char *copy = NULL;
    ctos_ssh_view_token token = 0;
    (void)type;
    if (wire == NULL) { fail(env); return 0; }
    jsize length = (*env)->GetArrayLength(env, wire);
    if (length <= 0 || (uint32_t)length > CTOS_SSH_VIEW_WIRE_BYTES) { fail(env); return 0; }
    copy = calloc(1, (size_t)length);
    if (copy == NULL) { fail(env); return 0; }
    (*env)->GetByteArrayRegion(env, wire, 0, length, (jbyte *)copy);
    if ((*env)->ExceptionCheck(env)
        || ctos_ssh_view_handle_create(copy, (size_t)length, &token) != 0) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        free(copy);
        fail(env);
        return 0;
    }
    wipe_bytes(copy, (size_t)length);
    free(copy);
    return (jlong)token;
}

JNIEXPORT jlongArray JNICALL Java_im_majo_ctos_SshPublicFileLease_00024Native_summary(
    JNIEnv *env, jclass type, jlong token) {
    struct ctos_ssh_view_summary summary;
    (void)type;
    if (token <= 0 || ctos_ssh_view_handle_summary((ctos_ssh_view_token)token,
            &summary) != 0) { fail(env); return NULL; }
    jlong values[] = {summary.count, summary.private_count, summary.public_bytes,
        (jlong)summary.generation, (jlong)summary.epoch, (jlong)summary.backing_bytes,
        summary.mode, summary.close_on_exec};
    jlongArray result = (*env)->NewLongArray(env, 8);
    if (result != NULL) (*env)->SetLongArrayRegion(env, result, 0, 8, values);
    return result;
}

JNIEXPORT jboolean JNICALL Java_im_majo_ctos_SshPublicFileLease_00024Native_destroy(
    JNIEnv *env, jclass type, jlong token) {
    (void)env; (void)type;
    if (token <= 0) return JNI_FALSE;
    return ctos_ssh_view_handle_destroy((ctos_ssh_view_token)token) == 0
        ? JNI_TRUE : JNI_FALSE;
}

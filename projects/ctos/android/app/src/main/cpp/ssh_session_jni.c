#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include <jni.h>

/*
 * The Java session-owner API is intentionally present in the normal JNI
 * library before the unsafe pointer bridge is admitted.  The current C
 * client takes borrowed struct sshkey/ctos_ssh_view pointers; accepting those
 * from Java without a native handle and view-borrow protocol would make
 * close/revoke ordering unverifiable.  Every entry point therefore fails
 * closed with one stable, non-secret exception until that contract exists.
 */
static void unavailable(JNIEnv *env) {
    jclass error;

    if (env == NULL || (*env)->ExceptionCheck(env)) return;
    error = (*env)->FindClass(env, "java/io/IOException");
    if (error != NULL) {
        (*env)->ThrowNew(env, error,
            "SSH session native owner is unavailable");
        (*env)->DeleteLocalRef(env, error);
    }
}

JNIEXPORT jlong JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_open(
    JNIEnv *env, jclass type, jlong role, jint owner_uid, jlong callback,
    jlong view, jint socket_fd, jlong deadline, jint step_budget,
    jbyteArray host_key_blob, jbyteArray expected_fingerprint,
    jstring known_hosts_path, jstring host, jstring candidate_id) {
    (void)type;
    (void)role;
    (void)owner_uid;
    (void)callback;
    (void)view;
    (void)socket_fd;
    (void)deadline;
    (void)step_budget;
    (void)host_key_blob;
    (void)expected_fingerprint;
    (void)known_hosts_path;
    (void)host;
    (void)candidate_id;
    unavailable(env);
    return 0;
}

JNIEXPORT jintArray JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_step(
    JNIEnv *env, jclass type, jlong handle, jboolean cancelled) {
    (void)type;
    (void)handle;
    (void)cancelled;
    unavailable(env);
    return NULL;
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_verifyHostKey(
    JNIEnv *env, jclass type, jlong handle, jbyteArray host_key_blob) {
    (void)type;
    (void)handle;
    (void)host_key_blob;
    unavailable(env);
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_close(
    JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    (void)handle;
    unavailable(env);
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeSession_00024Native_destroy(
    JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    (void)handle;
    unavailable(env);
}

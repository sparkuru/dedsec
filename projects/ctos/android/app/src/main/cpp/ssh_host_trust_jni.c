#include "ssh_host_trust.h"
#include <errno.h>
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

static void wipe(void *memory, size_t length) {
    volatile unsigned char *p = memory;
    while (length-- != 0) *p++ = 0;
}
static void fail(JNIEnv *env, int error) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass type = (*env)->FindClass(env, "java/io/IOException");
    if ((*env)->ExceptionCheck(env)) return;
    if (type != NULL) (*env)->ThrowNew(env, type,
        error == E2BIG ? "SSH_HOST_TRUST_LIMIT" : "SSH host trust inspection failed");
}
static void clear_result(JNIEnv *env, jbyteArray result, unsigned char *wire, size_t length) {
    jthrowable pending = (*env)->ExceptionOccurred(env);
    if (pending != NULL) (*env)->ExceptionClear(env);
    wipe(wire, length);
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)length, (const jbyte *)wire);
    if (pending != NULL) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        (*env)->Throw(env, pending);
    }
}
JNIEXPORT jbyteArray JNICALL Java_im_majo_ctos_SshHostTrustMaterial_nativeInspect(
    JNIEnv *env, jclass owner, jbyteArray input, jbyteArray target, jint port, jboolean prepare, jlong deadline) {
    (void)owner;
    unsigned char *body = NULL, *host = NULL, *wire = NULL;
    size_t wire_length = 0;
    jsize body_length = 0, host_length = 0;
    jbyteArray result = NULL;
    if (input == NULL || target == NULL || port < 1 || port > 65535 || deadline <= 0
        || (prepare != JNI_FALSE && prepare != JNI_TRUE)) { fail(env, EINVAL); goto done; }
    if (ctos_ssh_material_deadline((uint64_t)deadline) != 0) { fail(env, errno); goto done; }
    body_length = (*env)->GetArrayLength(env, input);
    if ((*env)->ExceptionCheck(env)) goto done;
    host_length = (*env)->GetArrayLength(env, target);
    if ((*env)->ExceptionCheck(env)) goto done;
    if ((size_t)body_length > CTOS_SSH_MATERIAL_BYTES) { fail(env, E2BIG); goto done; }
    if (host_length < 1 || (size_t)host_length > CTOS_SSH_HOST_TRUST_HOST_BYTES) { fail(env, EINVAL); goto done; }
    body = calloc((size_t)body_length + 1U, 1U); host = calloc((size_t)host_length + 1U, 1U);
    if (body == NULL || host == NULL) { fail(env, ENOMEM); goto done; }
    (*env)->GetByteArrayRegion(env, input, 0, body_length, (jbyte *)body);
    if ((*env)->ExceptionCheck(env)) goto done;
    (*env)->GetByteArrayRegion(env, target, 0, host_length, (jbyte *)host);
    if ((*env)->ExceptionCheck(env)) goto done;
    if (ctos_ssh_host_trust_inspect(body, (size_t)body_length, host, (size_t)host_length,
        (unsigned int)port, prepare == JNI_TRUE, (uint64_t)deadline, &wire, &wire_length) != 0) {
        fail(env, errno); goto done;
    }
    if (wire == NULL || wire_length > CTOS_SSH_HOST_TRUST_WIRE_BYTES) { fail(env, EIO); goto done; }
    result = (*env)->NewByteArray(env, (jsize)wire_length);
    if ((*env)->ExceptionCheck(env)) { result = NULL; goto done; }
    if (result == NULL) { fail(env, ENOMEM); goto done; }
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)wire_length, (const jbyte *)wire);
    if ((*env)->ExceptionCheck(env)) { clear_result(env, result, wire, wire_length); result = NULL; goto done; }
    if (ctos_ssh_material_deadline((uint64_t)deadline) != 0) {
        int error = errno;
        clear_result(env, result, wire, wire_length); fail(env, error); result = NULL;
    }
done:
    if (body != NULL) { wipe(body, (size_t)body_length + 1U); free(body); }
    if (host != NULL) { wipe(host, (size_t)host_length + 1U); free(host); }
    if (wire != NULL) { wipe(wire, wire_length); free(wire); }
    return result;
}

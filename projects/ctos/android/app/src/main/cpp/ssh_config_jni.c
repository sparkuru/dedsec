#include "ssh_config_handle.h"
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <errno.h>

static void wipe(void *p, size_t n) {
    volatile unsigned char *b = p;
    while (n-- != 0) *b++ = 0;
}
static void fail(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass error = (*env)->FindClass(env, "java/io/IOException");
    if ((*env)->ExceptionCheck(env)) return;
    if (error != NULL) (*env)->ThrowNew(env, error, "SSH configuration operation failed");
}
JNIEXPORT jlong JNICALL Java_im_majo_ctos_SshConfigEvaluator_00024Native_reserveDetached(
    JNIEnv *env, jclass type, jboolean catalog_mode) {
    (void)type;
    ctos_ssh_config_token token = 0;
    if (ctos_ssh_config_handle_reserve_detached(catalog_mode == JNI_TRUE, &token) != 0) { fail(env); return 0; }
    return (jlong)token;
}
JNIEXPORT jlong JNICALL Java_im_majo_ctos_SshConfigEvaluator_00024Native_reserveAudit(
    JNIEnv *env, jclass type) {
    (void)type;
    ctos_ssh_config_token token = 0;
    if (ctos_ssh_config_handle_reserve_audit(&token) != 0) { fail(env); return 0; }
    return (jlong)token;
}
JNIEXPORT jlong JNICALL Java_im_majo_ctos_SshConfigEvaluator_00024Native_reserveDirect(
    JNIEnv *env, jclass type) {
    (void)type;
    ctos_ssh_config_token token = 0;
    if (ctos_ssh_config_handle_reserve_direct(&token) != 0) { fail(env); return 0; }
    return (jlong)token;
}
JNIEXPORT jlong JNICALL Java_im_majo_ctos_SshConfigEvaluator_00024Native_reserveResourceAudit(
    JNIEnv *env, jclass type) {
    (void)type;
    ctos_ssh_config_token token = 0;
    if (ctos_ssh_config_handle_reserve_resource_audit(&token) != 0) { fail(env); return 0; }
    return (jlong)token;
}
struct detached_copy { JNIEnv *env; jbyteArray source, request; };
static int copy_detached(void *opaque, int request, unsigned char *out, size_t length) {
    struct detached_copy *copy = opaque;
    if ((*copy->env)->ExceptionCheck(copy->env)) { errno = EIO; return -1; }
    (*copy->env)->GetByteArrayRegion(copy->env, request ? copy->request : copy->source,
        0, (jsize)length, (jbyte *)out);
    if ((*copy->env)->ExceptionCheck(copy->env)) { errno = EIO; return -1; }
    return 0;
}
JNIEXPORT void JNICALL Java_im_majo_ctos_SshConfigEvaluator_00024Native_loadDetached(
    JNIEnv *env, jclass type, jlong token, jbyteArray source, jint root, jlong deadline, jbyteArray request) {
    (void)type;
    struct detached_copy copy = {env, source, request};
    jsize length = 0, request_length = 0;
    if (source != NULL) length = (*env)->GetArrayLength(env, source);
    if (!(*env)->ExceptionCheck(env) && request != NULL) request_length = (*env)->GetArrayLength(env, request);
    /* The existing token always retains failed-load ownership, including when
     * the VM already has a pending exception. No replacement exception is made. */
    if (ctos_ssh_config_handle_load_detached((ctos_ssh_config_token)token,
        length < 0 ? 0 : (size_t)length, (unsigned int)root, (uint64_t)deadline,
        request != NULL, request_length < 0 ? 0 : (size_t)request_length, copy_detached, &copy) != 0) fail(env);
}
JNIEXPORT jintArray JNICALL Java_im_majo_ctos_SshConfigEvaluator_00024Native_usage(
    JNIEnv *env, jclass type, jlong token) {
    (void)type;
    jintArray result = (*env)->NewIntArray(env, 3);
    if ((*env)->ExceptionCheck(env)) return NULL;
    if (result == NULL) { fail(env); return NULL; }
    uint32_t usage[3] = {0}; jint values[3] = {0};
    if (ctos_ssh_config_handle_usage((ctos_ssh_config_token)token, usage) != 0) { fail(env); return NULL; }
    for (unsigned int i = 0; i < 3; i++) values[i] = (jint)usage[i];
    (*env)->SetIntArrayRegion(env, result, 0, 3, values);
    if ((*env)->ExceptionCheck(env)) return NULL;
    if (ctos_ssh_config_handle_usage((ctos_ssh_config_token)token, usage) != 0) { fail(env); return NULL; }
    return result;
}
JNIEXPORT jlong JNICALL Java_im_majo_ctos_SshConfigEvaluator_00024Native_open(
    JNIEnv *env, jclass type, jlong view, jbyteArray input) {
    (void)type;
    if (input == NULL || view <= 0) { fail(env); return 0; }
    jsize length = (*env)->GetArrayLength(env, input);
    if ((*env)->ExceptionCheck(env)) return 0;
    if (length <= 0 || (size_t)length > CTOS_SSH_CONFIG_REQUEST_BYTES) { fail(env); return 0; }
    unsigned char *wire = calloc(1, (size_t)length);
    if (wire == NULL) { fail(env); return 0; }
    (*env)->GetByteArrayRegion(env, input, 0, length, (jbyte *)wire);
    if ((*env)->ExceptionCheck(env)) { wipe(wire, (size_t)length); free(wire); return 0; }
    ctos_ssh_config_token token = 0;
    int ret = ctos_ssh_config_handle_open((ctos_ssh_view_token)view, wire, (size_t)length, &token);
    wipe(wire, (size_t)length); free(wire);
    if (ret != 0) { fail(env); return 0; }
    return (jlong)token;
}
JNIEXPORT jintArray JNICALL Java_im_majo_ctos_SshConfigEvaluator_00024Native_run(
    JNIEnv *env, jclass type, jlong token) {
    (void)type;
    /* Allocation must precede the one-shot native state transition. */
    jintArray result = (*env)->NewIntArray(env, CTOS_SSH_CONFIG_RUN_VALUES);
    if ((*env)->ExceptionCheck(env)) return NULL;
    if (result == NULL) { fail(env); return NULL; }
    int32_t outcome[CTOS_SSH_CONFIG_RUN_VALUES] = {0};
    if (ctos_ssh_config_handle_run((ctos_ssh_config_token)token, outcome) != 0) { fail(env); return NULL; }
    jint values[CTOS_SSH_CONFIG_RUN_VALUES];
    for (unsigned int i = 0; i < CTOS_SSH_CONFIG_RUN_VALUES; i++) values[i] = (jint)outcome[i];
    (*env)->SetIntArrayRegion(env, result, 0, CTOS_SSH_CONFIG_RUN_VALUES, values);
    if ((*env)->ExceptionCheck(env)) return NULL;
    return result;
}
JNIEXPORT jbyteArray JNICALL Java_im_majo_ctos_SshConfigEvaluator_00024Native_summary(
    JNIEnv *env, jclass type, jlong token) {
    (void)type;
    unsigned char wire[CTOS_SSH_CONFIG_SUMMARY_BYTES] = {0};
    size_t length = 0;
    jbyteArray result = NULL;
    if (ctos_ssh_config_handle_summary((ctos_ssh_config_token)token, wire, &length) != 0) {
        fail(env); goto done;
    }
    result = (*env)->NewByteArray(env, (jsize)length);
    if ((*env)->ExceptionCheck(env)) { result = NULL; goto done; }
    if (result == NULL) { fail(env); goto done; }
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)length, (const jbyte *)wire);
    if ((*env)->ExceptionCheck(env)) { result = NULL; goto done; }
    if (ctos_ssh_config_handle_summary_live((ctos_ssh_config_token)token) != 0) { fail(env); result = NULL; }
done:
    wipe(wire, sizeof(wire));
    return result;
}
JNIEXPORT void JNICALL Java_im_majo_ctos_SshConfigEvaluator_00024Native_cancel(
    JNIEnv *env, jclass type, jlong token) {
    (void)type;
    if (ctos_ssh_config_handle_cancel((ctos_ssh_config_token)token) != 0) fail(env);
}
JNIEXPORT jbyteArray JNICALL Java_im_majo_ctos_SshConfigEvaluator_00024Native_directPlan(
    JNIEnv *env, jclass type, jlong token) {
    (void)type;
    unsigned char *wire = calloc(CTOS_SSH_CONFIG_DIRECT_BYTES, 1);
    size_t length = 0;
    jbyteArray result = NULL;
    if (wire == NULL) { fail(env); return NULL; }
    if (ctos_ssh_config_handle_direct_plan((ctos_ssh_config_token)token, wire, &length) != 0) { fail(env); goto done; }
    result = (*env)->NewByteArray(env, (jsize)length);
    if ((*env)->ExceptionCheck(env)) { result = NULL; goto done; }
    if (result == NULL) { fail(env); goto done; }
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)length, (const jbyte *)wire);
    if ((*env)->ExceptionCheck(env)) { result = NULL; goto done; }
    if (ctos_ssh_config_handle_direct_plan_live((ctos_ssh_config_token)token) != 0) { fail(env); result = NULL; }
done:
    wipe(wire, CTOS_SSH_CONFIG_DIRECT_BYTES); free(wire);
    return result;
}
JNIEXPORT void JNICALL Java_im_majo_ctos_SshConfigEvaluator_00024Native_close(
    JNIEnv *env, jclass type, jlong token) {
    (void)type;
    if (ctos_ssh_config_handle_close((ctos_ssh_config_token)token) != 0) fail(env);
}
JNIEXPORT void JNICALL Java_im_majo_ctos_SshConfigEvaluator_00024Native_destroy(
    JNIEnv *env, jclass type, jlong token) {
    (void)type;
    if (ctos_ssh_config_handle_destroy((ctos_ssh_config_token)token) != 0) fail(env);
}

JNIEXPORT jlong JNICALL Java_im_majo_ctos_SshConfigEvaluator_00024Native_openCatalog(
    JNIEnv *env, jclass type, jlong view) {
    (void)type;
    if (view <= 0) { fail(env); return 0; }
    ctos_ssh_config_token token = 0;
    if (ctos_ssh_config_handle_open_catalog((ctos_ssh_view_token)view, &token) != 0) { fail(env); return 0; }
    return (jlong)token;
}
JNIEXPORT jbyteArray JNICALL Java_im_majo_ctos_SshConfigEvaluator_00024Native_catalog(
    JNIEnv *env, jclass type, jlong token) {
    (void)type;
    unsigned char *wire = calloc(1, CTOS_SSH_CONFIG_CATALOG_BYTES);
    if (wire == NULL) { fail(env); return NULL; }
    size_t length = 0;
    jbyteArray result = NULL;
    if (ctos_ssh_config_handle_catalog((ctos_ssh_config_token)token, wire, &length) != 0) {
        fail(env); goto done;
    }
    result = (*env)->NewByteArray(env, (jsize)length);
    if ((*env)->ExceptionCheck(env)) { result = NULL; goto done; }
    if (result == NULL) { fail(env); goto done; }
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)length, (const jbyte *)wire);
    if ((*env)->ExceptionCheck(env)) { result = NULL; goto done; }
    if (ctos_ssh_config_handle_catalog_live((ctos_ssh_config_token)token) != 0) { fail(env); result = NULL; }
done:
    wipe(wire, CTOS_SSH_CONFIG_CATALOG_BYTES); free(wire);
    return result;
}
JNIEXPORT jbyteArray JNICALL Java_im_majo_ctos_SshConfigEvaluator_00024Native_audit(
    JNIEnv *env, jclass type, jlong token) {
    (void)type;
    unsigned char *wire = calloc(1, CTOS_SSH_CONFIG_AUDIT_BYTES);
    if (wire == NULL) { fail(env); return NULL; }
    size_t length = 0; jbyteArray result = NULL;
    if (ctos_ssh_config_handle_audit((ctos_ssh_config_token)token, wire, &length) != 0) { fail(env); goto done; }
    result = (*env)->NewByteArray(env, (jsize)length);
    if ((*env)->ExceptionCheck(env)) { result = NULL; goto done; }
    if (result == NULL) { fail(env); goto done; }
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)length, (const jbyte *)wire);
    if ((*env)->ExceptionCheck(env)) { result = NULL; goto done; }
    if (ctos_ssh_config_handle_audit_live((ctos_ssh_config_token)token) != 0) { fail(env); result = NULL; }
done:
    wipe(wire, CTOS_SSH_CONFIG_AUDIT_BYTES); free(wire); return result;
}

JNIEXPORT jbyteArray JNICALL Java_im_majo_ctos_SshConfigEvaluator_00024Native_resourceAudit(
    JNIEnv *env, jclass type, jlong token) {
    (void)type;
    unsigned char *wire = calloc(1, CTOS_SSH_CONFIG_RESOURCE_BYTES);
    if (wire == NULL) { fail(env); return NULL; }
    size_t length = 0; jbyteArray result = NULL;
    if (ctos_ssh_config_handle_resource_audit((ctos_ssh_config_token)token, wire, &length) != 0) { fail(env); goto done; }
    result = (*env)->NewByteArray(env, (jsize)length);
    if ((*env)->ExceptionCheck(env)) { result = NULL; goto done; }
    if (result == NULL) { fail(env); goto done; }
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)length, (const jbyte *)wire);
    if ((*env)->ExceptionCheck(env)) { result = NULL; goto done; }
    if (ctos_ssh_config_handle_resource_audit_live((ctos_ssh_config_token)token) != 0) { fail(env); result = NULL; }
done:
    wipe(wire, CTOS_SSH_CONFIG_RESOURCE_BYTES); free(wire); return result;
}

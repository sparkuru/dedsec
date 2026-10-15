#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <jni.h>

#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "ssh_callback_adapter.h"

struct jni_callback_target {
    JavaVM *vm;
    jobject object;
    jmethodID host_key;
    jmethodID host_evidence;
    jmethodID known_hosts_candidate;
    jmethodID known_hosts_candidate_abort;
    jmethodID kex_complete;
    jmethodID credential;
    jmethodID credential_bytes;
};

static void wipe_bytes(unsigned char *bytes, size_t length) {
    if (bytes == NULL) return;
    volatile unsigned char *target = (volatile unsigned char *)bytes;
    for (size_t index = 0; index < length; index++) target[index] = 0;
}

static int zero_java_array(JNIEnv *env, jbyteArray array, jsize length) {
    jbyte zeros[1024] = {0};
    if (env == NULL || array == NULL || length < 0) return -1;
    for (jsize offset = 0; offset < length;) {
        jsize count = length - offset;
        if (count > (jsize)sizeof(zeros)) count = (jsize)sizeof(zeros);
        (*env)->SetByteArrayRegion(env, array, offset, count, zeros);
        if ((*env)->ExceptionCheck(env)) return -1;
        offset += count;
    }
    return 0;
}

static void fail(JNIEnv *env) {
    if (env == NULL || (*env)->ExceptionCheck(env)) return;
    jclass error = (*env)->FindClass(env, "java/io/IOException");
    if (error != NULL) {
        (*env)->ThrowNew(env, error, "Synchronous SSH callback transport unavailable");
        (*env)->DeleteLocalRef(env, error);
    }
}

static int attached(void *context, void *environment) {
    struct jni_callback_target *target = context;
    JNIEnv *env = (JNIEnv *)environment;
    JavaVM *vm = NULL;
    JNIEnv *current = NULL;
    if (target == NULL || env == NULL || (*env)->GetJavaVM(env, &vm) != JNI_OK
        || vm == NULL || vm != target->vm
        || (*vm)->GetEnv(vm, (void **)&current, JNI_VERSION_1_6) != JNI_OK
        || current != env) return -1;
    return 0;
}

static int ascii_field(const char *value, size_t maximum) {
    size_t length;
    if (value == NULL) return -1;
    length = strnlen(value, maximum + 1);
    if (length == 0 || length > maximum) return -1;
    for (size_t index = 0; index < length; index++) {
        unsigned char character = (unsigned char)value[index];
        if (character < 0x21 || character > 0x7e) return -1;
    }
    return 0;
}

static int wire_u8(const unsigned char *wire, size_t length, size_t *offset,
    unsigned int *value) {
    if (wire == NULL || offset == NULL || value == NULL || *offset >= length) return -1;
    *value = wire[(*offset)++];
    return 0;
}

static int wire_u16(const unsigned char *wire, size_t length, size_t *offset,
    unsigned int *value) {
    if (wire == NULL || offset == NULL || value == NULL || *offset > length
        || length - *offset < 2U) return -1;
    *value = ((unsigned int)wire[*offset] << 8)
        | (unsigned int)wire[*offset + 1U];
    *offset += 2U;
    return 0;
}

static int wire_u32(const unsigned char *wire, size_t length, size_t *offset,
    uint32_t *value) {
    if (wire == NULL || offset == NULL || value == NULL || *offset > length
        || length - *offset < 4U) return -1;
    *value = ((uint32_t)wire[*offset] << 24)
        | ((uint32_t)wire[*offset + 1U] << 16)
        | ((uint32_t)wire[*offset + 2U] << 8)
        | (uint32_t)wire[*offset + 3U];
    *offset += 4U;
    return 0;
}

static int wire_u64(const unsigned char *wire, size_t length, size_t *offset,
    uint64_t *value) {
    uint32_t high;
    uint32_t low;
    if (wire_u32(wire, length, offset, &high) != 0
        || wire_u32(wire, length, offset, &low) != 0) return -1;
    *value = ((uint64_t)high << 32) | (uint64_t)low;
    return 0;
}

static int wire_field(const unsigned char *wire, size_t length, size_t *offset,
    char *output, size_t capacity, int allow_empty) {
    unsigned int field_length;
    if (output == NULL || capacity == 0
        || wire_u16(wire, length, offset, &field_length) != 0
        || field_length >= capacity
        || (!allow_empty && field_length == 0)
        || *offset > length || length - *offset < field_length) return -1;
    for (unsigned int index = 0; index < field_length; index++) {
        unsigned char character = wire[*offset + index];
        if (character < 0x21U || character > 0x7eU) return -1;
    }
    memcpy(output, wire + *offset, field_length);
    output[field_length] = '\0';
    *offset += field_length;
    return 0;
}

static int valid_proposal_binding(const char *value, int allow_empty) {
    size_t length;
    if (value == NULL) return -1;
    length = strnlen(value, CTOS_SSH_CALLBACK_HOST_PROPOSAL_BINDING_BYTES);
    if (length == 0 && allow_empty) return 0;
    if (length != 64U) return -1;
    for (size_t index = 0; index < length; index++) {
        unsigned char character = (unsigned char)value[index];
        if (!((character >= '0' && character <= '9')
            || (character >= 'a' && character <= 'f'))) return -1;
    }
    return 0;
}

static int parse_host_result(const unsigned char *wire, size_t length,
    const struct ctos_ssh_host_evidence *evidence,
    struct ctos_ssh_host_result *result) {
    size_t offset = 0;
    unsigned int version;
    unsigned int outcome;
    unsigned int flags;
    uint64_t role_token;
    uint32_t hop;
    uint32_t port;
    uint32_t classification;
    uint32_t cas_status;
    uint32_t magic;
    if (wire == NULL || evidence == NULL || result == NULL
        || length > CTOS_SSH_CALLBACK_MAX_HOST_RESULT_BYTES) return -1;
    if (wire_u32(wire, length, &offset, &magic) != 0
        || magic != 0x43534852U
        || wire_u8(wire, length, &offset, &version) != 0 || version != 1U
        || wire_u8(wire, length, &offset, &outcome) != 0
        || outcome != CTOS_SSH_CALLBACK_HOST_RESULT_APPROVED
        || wire_u16(wire, length, &offset, &flags) != 0 || flags != 0U
        || wire_u64(wire, length, &offset, &role_token) != 0
        || wire_u32(wire, length, &offset, &hop) != 0
        || wire_u32(wire, length, &offset, &port) != 0
        || wire_u32(wire, length, &offset, &classification) != 0
        || wire_u32(wire, length, &offset, &cas_status) != 0) return -1;
    memset(result, 0, sizeof(*result));
    result->role = role_token;
    result->hop = hop;
    result->port = port;
    result->classification = (int)classification;
    result->outcome = (int)outcome;
    result->cas_status = (int)cas_status;
    if (wire_field(wire, length, &offset, result->connection_id,
            sizeof(result->connection_id), 0) != 0
        || wire_field(wire, length, &offset, result->target_identity,
            sizeof(result->target_identity), 0) != 0
        || wire_field(wire, length, &offset, result->host,
            sizeof(result->host), 0) != 0
        || wire_field(wire, length, &offset, result->key_type,
            sizeof(result->key_type), 0) != 0
        || wire_field(wire, length, &offset, result->fingerprint,
            sizeof(result->fingerprint), 0) != 0
        || wire_field(wire, length, &offset, result->proposal_binding,
            sizeof(result->proposal_binding), 1) != 0
        || offset > length || length - offset < 64U) return -1;
    memcpy(result->base_digest, wire + offset, sizeof(result->base_digest));
    offset += sizeof(result->base_digest);
    memcpy(result->replacement_digest, wire + offset, sizeof(result->replacement_digest));
    offset += sizeof(result->replacement_digest);
    if (offset != length || result->role != evidence->role
        || result->hop != evidence->hop || result->port != evidence->port
        || result->classification != evidence->classification
        || result->outcome != CTOS_SSH_CALLBACK_HOST_RESULT_APPROVED
        || strcmp(result->connection_id, evidence->connection_id) != 0
        || strcmp(result->target_identity, evidence->target_identity) != 0
        || strcmp(result->host, evidence->host) != 0
        || strcmp(result->key_type, evidence->key_type) != 0
        || strcmp(result->fingerprint, evidence->fingerprint) != 0
        || valid_proposal_binding(result->proposal_binding,
            evidence->classification != CTOS_SSH_CALLBACK_HOST_NEW) != 0
        || memcmp(result->base_digest, evidence->base_digest,
            sizeof(result->base_digest)) != 0
        || memcmp(result->replacement_digest, evidence->replacement_digest,
            sizeof(result->replacement_digest)) != 0) return -1;
    if (result->cas_status != (evidence->classification == CTOS_SSH_CALLBACK_HOST_NEW
            ? CTOS_SSH_CALLBACK_HOST_CAS_COMMITTED
            : CTOS_SSH_CALLBACK_HOST_CAS_NOT_REQUIRED)) return -1;
    return 0;
}

static int jni_host_key(void *context, void *environment, int classification,
    const char *key_type, const char *fingerprint,
    const unsigned char base_digest[32], const unsigned char replacement_digest[32]) {
    struct jni_callback_target *target = context;
    JNIEnv *env = environment;
    jstring key = NULL, fingerprint_string = NULL;
    jbyteArray base = NULL, replacement = NULL;
    int result = EIO;
    if (attached(target, environment) != 0 || ascii_field(key_type, 64) != 0
        || ascii_field(fingerprint, 52) != 0
        || strncmp(fingerprint, "SHA256:", 7) != 0) return EINVAL;
    key = (*env)->NewStringUTF(env, key_type);
    fingerprint_string = (*env)->NewStringUTF(env, fingerprint);
    base = (*env)->NewByteArray(env, 32);
    replacement = (*env)->NewByteArray(env, 32);
    if (key == NULL || fingerprint_string == NULL || base == NULL || replacement == NULL
        || (*env)->ExceptionCheck(env)) goto done;
    (*env)->SetByteArrayRegion(env, base, 0, 32, (const jbyte *)base_digest);
    (*env)->SetByteArrayRegion(env, replacement, 0, 32, (const jbyte *)replacement_digest);
    if ((*env)->ExceptionCheck(env)) goto done;
    result = (*env)->CallBooleanMethod(env, target->object, target->host_key,
        (jint)classification, key, fingerprint_string, base, replacement) ? 0 : EIO;
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); result = EFAULT; }
done:
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); result = EFAULT; }
    if (key != NULL) (*env)->DeleteLocalRef(env, key);
    if (fingerprint_string != NULL) (*env)->DeleteLocalRef(env, fingerprint_string);
    if (base != NULL) (*env)->DeleteLocalRef(env, base);
    if (replacement != NULL) (*env)->DeleteLocalRef(env, replacement);
    return result;
}

static int jni_host_evidence(void *context, void *environment,
    const struct ctos_ssh_host_evidence *evidence,
    struct ctos_ssh_host_result *result) {
    struct jni_callback_target *target = context;
    JNIEnv *env = environment;
    jstring connection = NULL, target_identity = NULL, host = NULL;
    jstring key = NULL, fingerprint = NULL;
    jbyteArray base = NULL, replacement = NULL, returned = NULL;
    unsigned char wire[CTOS_SSH_CALLBACK_MAX_HOST_RESULT_BYTES];
    jsize returned_length = 0;
    jsize clear_length = 0;
    int callback_result = EIO;

    memset(wire, 0, sizeof(wire));
    if (result != NULL) memset(result, 0, sizeof(*result));
    if (evidence == NULL || result == NULL || attached(target, environment) != 0
        || ascii_field(evidence->connection_id, CTOS_SSH_ROLE_CONNECTION_ID_BYTES) != 0
        || ascii_field(evidence->target_identity, CTOS_SSH_ROLE_TARGET_ID_BYTES) != 0
        || ascii_field(evidence->host, CTOS_SSH_ROLE_HOST_BYTES) != 0
        || ascii_field(evidence->key_type, CTOS_SSH_CALLBACK_HOST_KEY_TYPE_BYTES - 1U) != 0
        || ascii_field(evidence->fingerprint,
            CTOS_SSH_CALLBACK_HOST_FINGERPRINT_BYTES - 1U) != 0
        || strncmp(evidence->fingerprint, "SHA256:", 7U) != 0) {
        return EINVAL;
    }
    connection = (*env)->NewStringUTF(env, evidence->connection_id);
    target_identity = (*env)->NewStringUTF(env, evidence->target_identity);
    host = (*env)->NewStringUTF(env, evidence->host);
    key = (*env)->NewStringUTF(env, evidence->key_type);
    fingerprint = (*env)->NewStringUTF(env, evidence->fingerprint);
    base = (*env)->NewByteArray(env, 32);
    replacement = (*env)->NewByteArray(env, 32);
    if (connection == NULL || target_identity == NULL || host == NULL || key == NULL
        || fingerprint == NULL || base == NULL || replacement == NULL
        || (*env)->ExceptionCheck(env)) goto done;
    (*env)->SetByteArrayRegion(env, base, 0, 32, (const jbyte *)evidence->base_digest);
    (*env)->SetByteArrayRegion(env, replacement, 0, 32,
        (const jbyte *)evidence->replacement_digest);
    if ((*env)->ExceptionCheck(env)) goto done;
    returned = (jbyteArray)(*env)->CallObjectMethod(env, target->object,
        target->host_evidence, (jlong)evidence->role, (jint)evidence->hop,
        (jint)evidence->port, connection, target_identity, host,
        (jint)evidence->classification, key, fingerprint, base, replacement);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        callback_result = EFAULT;
        goto done;
    }
    if (returned == NULL) {
        callback_result = EIO;
        goto done;
    }
    returned_length = (*env)->GetArrayLength(env, returned);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        callback_result = EFAULT;
        goto done;
    }
    if (returned_length <= 0) {
        callback_result = EINVAL;
        goto done;
    }
    if ((size_t)returned_length > sizeof(wire)) {
        callback_result = EOVERFLOW;
        goto done;
    }
    (*env)->GetByteArrayRegion(env, returned, 0, returned_length, (jbyte *)wire);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        callback_result = EFAULT;
        goto done;
    }
    callback_result = parse_host_result(wire, (size_t)returned_length, evidence, result) == 0
        ? 0 : EINVAL;
done:
    clear_length = returned_length;
    if (clear_length > (jsize)sizeof(wire)) clear_length = (jsize)sizeof(wire);
    if (returned != NULL && clear_length > 0
        && zero_java_array(env, returned, clear_length) != 0) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        callback_result = EFAULT;
    }
    if (connection != NULL) (*env)->DeleteLocalRef(env, connection);
    if (target_identity != NULL) (*env)->DeleteLocalRef(env, target_identity);
    if (host != NULL) (*env)->DeleteLocalRef(env, host);
    if (key != NULL) (*env)->DeleteLocalRef(env, key);
    if (fingerprint != NULL) (*env)->DeleteLocalRef(env, fingerprint);
    if (base != NULL) (*env)->DeleteLocalRef(env, base);
    if (replacement != NULL) (*env)->DeleteLocalRef(env, replacement);
    if (returned != NULL) (*env)->DeleteLocalRef(env, returned);
    wipe_bytes(wire, sizeof(wire));
    if (callback_result != 0 && result != NULL) memset(result, 0, sizeof(*result));
    return callback_result;
}

static int jni_known_hosts_candidate(void *context, void *environment,
    const struct ctos_ssh_known_hosts_candidate *candidate) {
    struct jni_callback_target *target = context;
    JNIEnv *env = environment;
    const struct ctos_ssh_host_evidence *evidence;
    jstring connection = NULL, target_identity = NULL, host = NULL;
    jstring key = NULL, fingerprint = NULL, candidate_id = NULL;
    jbyteArray base = NULL, replacement = NULL, body = NULL;
    jsize body_length = 0;
    int callback_result = EIO;

    if (candidate == NULL) return EINVAL;
    evidence = &candidate->evidence;
    if (candidate->replacement_body == NULL
        || candidate->replacement_body_length == 0
        || candidate->replacement_body_length > CTOS_SSH_CALLBACK_MAX_FILE_BYTES
        || candidate->candidate_id[0] == '\0'
        || attached(target, environment) != 0
        || ascii_field(evidence->connection_id, CTOS_SSH_ROLE_CONNECTION_ID_BYTES) != 0
        || ascii_field(evidence->target_identity, CTOS_SSH_ROLE_TARGET_ID_BYTES) != 0
        || ascii_field(evidence->host, CTOS_SSH_ROLE_HOST_BYTES) != 0
        || ascii_field(evidence->key_type, CTOS_SSH_CALLBACK_HOST_KEY_TYPE_BYTES - 1U) != 0
        || ascii_field(evidence->fingerprint,
            CTOS_SSH_CALLBACK_HOST_FINGERPRINT_BYTES - 1U) != 0
        || strncmp(evidence->fingerprint, "SHA256:", 7U) != 0
        || ascii_field(candidate->candidate_id,
            CTOS_SSH_CALLBACK_CANDIDATE_ID_BYTES - 1U) != 0) return EINVAL;
    body_length = (jsize)candidate->replacement_body_length;
    connection = (*env)->NewStringUTF(env, evidence->connection_id);
    target_identity = (*env)->NewStringUTF(env, evidence->target_identity);
    host = (*env)->NewStringUTF(env, evidence->host);
    key = (*env)->NewStringUTF(env, evidence->key_type);
    fingerprint = (*env)->NewStringUTF(env, evidence->fingerprint);
    candidate_id = (*env)->NewStringUTF(env, candidate->candidate_id);
    base = (*env)->NewByteArray(env, 32);
    replacement = (*env)->NewByteArray(env, 32);
    body = (*env)->NewByteArray(env, body_length);
    if (connection == NULL || target_identity == NULL || host == NULL || key == NULL
        || fingerprint == NULL || candidate_id == NULL || base == NULL
        || replacement == NULL || body == NULL || (*env)->ExceptionCheck(env)) goto done;
    (*env)->SetByteArrayRegion(env, base, 0, 32, (const jbyte *)evidence->base_digest);
    (*env)->SetByteArrayRegion(env, replacement, 0, 32,
        (const jbyte *)evidence->replacement_digest);
    (*env)->SetByteArrayRegion(env, body, 0, body_length,
        (const jbyte *)candidate->replacement_body);
    if ((*env)->ExceptionCheck(env)) goto done;
    callback_result = (*env)->CallBooleanMethod(env, target->object,
        target->known_hosts_candidate, (jlong)evidence->role, (jint)evidence->hop,
        (jint)evidence->port, connection, target_identity, host,
        (jint)evidence->classification, key, fingerprint, base, replacement,
        candidate_id, body) ? 0 : EIO;
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        callback_result = EFAULT;
    }
done:
    if (body != NULL && body_length > 0
        && zero_java_array(env, body, body_length) != 0) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        callback_result = EFAULT;
    }
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (connection != NULL) (*env)->DeleteLocalRef(env, connection);
    if (target_identity != NULL) (*env)->DeleteLocalRef(env, target_identity);
    if (host != NULL) (*env)->DeleteLocalRef(env, host);
    if (key != NULL) (*env)->DeleteLocalRef(env, key);
    if (fingerprint != NULL) (*env)->DeleteLocalRef(env, fingerprint);
    if (candidate_id != NULL) (*env)->DeleteLocalRef(env, candidate_id);
    if (base != NULL) (*env)->DeleteLocalRef(env, base);
    if (replacement != NULL) (*env)->DeleteLocalRef(env, replacement);
    if (body != NULL) (*env)->DeleteLocalRef(env, body);
    return callback_result;
}

static int jni_known_hosts_candidate_abort(void *context, void *environment) {
    struct jni_callback_target *target = context;
    JNIEnv *env = environment;
    if (attached(target, environment) != 0
        || target->known_hosts_candidate_abort == NULL) return EPERM;
    (*env)->CallVoidMethod(env, target->object, target->known_hosts_candidate_abort);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return EFAULT;
    }
    return 0;
}

static int jni_kex_complete(void *context, void *environment, uint64_t cycle,
    const unsigned char session_digest[32]) {
    struct jni_callback_target *target = context;
    JNIEnv *env = environment;
    jbyteArray digest = NULL;
    int result = EIO;
    if (attached(target, environment) != 0) return EPERM;
    digest = (*env)->NewByteArray(env, 32);
    if (digest == NULL || (*env)->ExceptionCheck(env)) goto done;
    (*env)->SetByteArrayRegion(env, digest, 0, 32, (const jbyte *)session_digest);
    if ((*env)->ExceptionCheck(env)) goto done;
    result = (*env)->CallBooleanMethod(env, target->object, target->kex_complete,
        (jlong)cycle, digest) ? 0 : EIO;
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); result = EFAULT; }
done:
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); result = EFAULT; }
    if (digest != NULL) (*env)->DeleteLocalRef(env, digest);
    return result;
}

static int jni_credential(void *context, void *environment, int kind) {
    struct jni_callback_target *target = context;
    JNIEnv *env = environment;
    int result;
    if (attached(target, environment) != 0) return EPERM;
    result = (*env)->CallBooleanMethod(env, target->object, target->credential, (jint)kind)
        ? 0 : EIO;
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return EFAULT; }
    return result;
}

static int jni_credential_bytes(void *context, void *environment, int kind,
    unsigned char *output, size_t capacity, size_t *length) {
    struct jni_callback_target *target = context;
    JNIEnv *env = environment;
    jbyteArray returned = NULL;
    jsize returned_length = 0;
    int result = EIO;
    if (length != NULL) *length = 0;
    if (output != NULL) {
        wipe_bytes(output, capacity > CTOS_SSH_CALLBACK_MAX_CREDENTIAL_BYTES
            ? CTOS_SSH_CALLBACK_MAX_CREDENTIAL_BYTES : capacity);
    }
    if (length == NULL || output == NULL || capacity == 0) return EINVAL;
    if (capacity > CTOS_SSH_CALLBACK_MAX_CREDENTIAL_BYTES) return EOVERFLOW;
    if (attached(target, environment) != 0) return EPERM;
    if (target->credential_bytes == NULL) return ENOSYS;

    returned = (jbyteArray)(*env)->CallObjectMethod(env, target->object,
        target->credential_bytes, (jint)kind);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        result = EFAULT;
        goto done;
    }
    if (returned == NULL) {
        result = ENOSYS;
        goto done;
    }
    returned_length = (*env)->GetArrayLength(env, returned);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        result = EFAULT;
        goto done;
    }
    if (returned_length <= 0) {
        result = EINVAL;
        goto done;
    }
    if ((size_t)returned_length > capacity
        || (size_t)returned_length > CTOS_SSH_CALLBACK_MAX_CREDENTIAL_BYTES) {
        result = EOVERFLOW;
        goto done;
    }
    (*env)->GetByteArrayRegion(env, returned, 0, returned_length,
        (jbyte *)output);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        result = EFAULT;
        goto done;
    }
    *length = (size_t)returned_length;
    result = 0;
done:
    /* Zero the managed array before releasing the local reference, including
     * the too-large and provider-failure paths.  No payload is logged. */
    if (returned != NULL && returned_length >= 0
        && zero_java_array(env, returned, returned_length) != 0) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        result = EFAULT;
    }
    if (result != 0) {
        wipe_bytes(output, capacity);
        if (length != NULL) *length = 0;
    }
    if (returned != NULL) (*env)->DeleteLocalRef(env, returned);
    return result;
}

static int jni_release(void *context, void *environment) {
    struct jni_callback_target *target = context;
    if (attached(target, environment) != 0) return -1;
    JNIEnv *env = environment;
    if (target->object != NULL) {
        (*env)->DeleteGlobalRef(env, target->object);
        target->object = NULL;
    }
    return 0;
}

static void jni_destroy(void *context) { free(context); }

static int jni_target_release_on_open_failure(struct jni_callback_target *target, JNIEnv *env) {
    if (target == NULL) return 0;
    if (target->object != NULL) (*env)->DeleteGlobalRef(env, target->object);
    free(target);
    return 0;
}

JNIEXPORT jlong JNICALL Java_im_majo_ctos_SshNativeCallbackAdapter_00024Native_open(
    JNIEnv *env, jclass type, jobject callback_target, jlong role, jint owner_uid,
    jlong deadline) {
    (void)type;
    struct jni_callback_target *context = NULL;
    JavaVM *vm = NULL;
    jclass callback_class = NULL;
    ctos_ssh_callback_token callback = 0;
    struct ctos_ssh_callback_target target;
    memset(&target, 0, sizeof(target));
    if (env == NULL || callback_target == NULL || role <= 0 || owner_uid < 0
        || deadline <= 0 || (*env)->GetJavaVM(env, &vm) != JNI_OK || vm == NULL) goto failed;
    context = calloc(1, sizeof(*context));
    if (context == NULL) goto failed;
    context->vm = vm;
    context->object = (*env)->NewGlobalRef(env, callback_target);
    callback_class = (*env)->GetObjectClass(env, callback_target);
    if (context->object == NULL || callback_class == NULL || (*env)->ExceptionCheck(env)) goto failed;
    context->host_key = (*env)->GetMethodID(env, callback_class, "onNativeHostKey",
        "(ILjava/lang/String;Ljava/lang/String;[B[B)Z");
    context->host_evidence = (*env)->GetMethodID(env, callback_class,
        "onNativeHostEvidence",
        "(JIILjava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;[B[B)[B");
    context->known_hosts_candidate = (*env)->GetMethodID(env, callback_class,
        "onNativeKnownHostsCandidate",
        "(JIILjava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;[B[BLjava/lang/String;[B)Z");
    context->known_hosts_candidate_abort = (*env)->GetMethodID(env, callback_class,
        "abortNativeKnownHostsCandidate", "()V");
    context->kex_complete = (*env)->GetMethodID(env, callback_class, "onNativeKexComplete",
        "(J[B)Z");
    context->credential = (*env)->GetMethodID(env, callback_class, "onNativeCredential", "(I)Z");
    if (context->host_key == NULL || context->host_evidence == NULL
        || context->known_hosts_candidate == NULL
        || context->known_hosts_candidate_abort == NULL
        || context->kex_complete == NULL || context->credential == NULL
        || (*env)->ExceptionCheck(env)) goto failed;
    context->credential_bytes = (*env)->GetMethodID(env, callback_class,
        "onNativeCredentialBytes", "(I)[B");
    if ((*env)->ExceptionCheck(env)) {
        /* The byte handoff is optional at the JNI target boundary.  The
         * existing boolean callback remains usable when it is absent. */
        (*env)->ExceptionClear(env);
        context->credential_bytes = NULL;
    }
    target.context = context;
    target.validate_environment = attached;
    target.host_key = jni_host_key;
    target.host_evidence = jni_host_evidence;
    target.known_hosts_candidate = jni_known_hosts_candidate;
    target.known_hosts_candidate_abort = jni_known_hosts_candidate_abort;
    target.kex_complete = jni_kex_complete;
    target.credential = jni_credential;
    target.release = jni_release;
    target.destroy = jni_destroy;
    target.credential_bytes = jni_credential_bytes;
    if (ctos_ssh_callback_open(&target, (ctos_ssh_role_token)role, owner_uid,
            (uint64_t)deadline, env, &callback) != 0 || callback == 0) goto failed;
    if (callback_class != NULL) (*env)->DeleteLocalRef(env, callback_class);
    return (jlong)callback;
failed:
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (callback_class != NULL) (*env)->DeleteLocalRef(env, callback_class);
    jni_target_release_on_open_failure(context, env);
    fail(env);
    return 0;
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeCallbackAdapter_00024Native_cancel(
    JNIEnv *env, jclass type, jlong callback) {
    (void)type;
    if (callback <= 0 || ctos_ssh_callback_cancel((ctos_ssh_callback_token)callback) != 0) fail(env);
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeCallbackAdapter_00024Native_close(
    JNIEnv *env, jclass type, jlong callback) {
    (void)type;
    if (callback <= 0 || ctos_ssh_callback_close((ctos_ssh_callback_token)callback, env) != 0) fail(env);
}

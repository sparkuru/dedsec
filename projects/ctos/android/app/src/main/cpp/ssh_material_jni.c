#include "ssh_material_inspect.h"
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

static void wipe(void *memory, size_t length) {
    volatile unsigned char *p = memory;
    while (length-- != 0) *p++ = 0;
}
static void fail(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass error = (*env)->FindClass(env, "java/io/IOException");
    if ((*env)->ExceptionCheck(env)) return;
    if (error != NULL) (*env)->ThrowNew(env, error, "SSH material inspection failed");
}
JNIEXPORT jbyteArray JNICALL Java_im_majo_ctos_SshManifestInspector_00024Native_inspect(
    JNIEnv *env, jclass owner, jint type, jbyteArray input, jbyteArray secret, jlong deadline) {
    (void)owner;
    unsigned char *body = NULL, *passphrase = NULL;
    unsigned char wire[CTOS_SSH_MATERIAL_WIRE_BYTES] = {0};
    jbyteArray result = NULL;
    jsize length = 0, secret_length = 0;
    if (input == NULL || type < 2 || type > 5 || deadline <= 0
        || ctos_ssh_material_deadline((uint64_t)deadline) != 0) { fail(env); goto done; }
    length = (*env)->GetArrayLength(env, input);
    if ((*env)->ExceptionCheck(env)) goto done;
    if (secret != NULL) {
        secret_length = (*env)->GetArrayLength(env, secret);
        if ((*env)->ExceptionCheck(env)) goto done;
    }
    if ((size_t)length > CTOS_SSH_MATERIAL_BYTES || (size_t)secret_length > CTOS_SSH_MATERIAL_PASSPHRASE_BYTES
        || (type != 2 && secret_length != 0)) { fail(env); goto done; }
    body = calloc((size_t)length + 1U, 1U);
    passphrase = calloc((size_t)secret_length + 1U, 1U);
    if (body == NULL || passphrase == NULL) { fail(env); goto done; }
    (*env)->GetByteArrayRegion(env, input, 0, length, (jbyte *)body);
    if ((*env)->ExceptionCheck(env)) goto done;
    if (secret_length != 0) {
        (*env)->GetByteArrayRegion(env, secret, 0, secret_length, (jbyte *)passphrase);
        if ((*env)->ExceptionCheck(env)) goto done;
    }
    result = (*env)->NewByteArray(env, CTOS_SSH_MATERIAL_WIRE_BYTES);
    if ((*env)->ExceptionCheck(env)) { result = NULL; goto done; }
    if (result == NULL) { fail(env); goto done; }
    if (ctos_ssh_material_inspect((unsigned int)type, body, (size_t)length,
        passphrase, (size_t)secret_length, (uint64_t)deadline, wire) != 0) {
        fail(env); result = NULL; goto done;
    }
    (*env)->SetByteArrayRegion(env, result, 0, CTOS_SSH_MATERIAL_WIRE_BYTES, (const jbyte *)wire);
    if ((*env)->ExceptionCheck(env)) { result = NULL; goto done; }
    if (ctos_ssh_material_deadline((uint64_t)deadline) != 0) { fail(env); result = NULL; }
done:
    if (body != NULL) { wipe(body, (size_t)length + 1U); free(body); }
    if (passphrase != NULL) { wipe(passphrase, (size_t)secret_length + 1U); free(passphrase); }
    wipe(wire, sizeof(wire));
    return result;
}
JNIEXPORT jbyteArray JNICALL Java_im_majo_ctos_SshManifestKeyAssociationInspector_00024Native_verify(
    JNIEnv *env, jclass owner, jint type, jbyteArray private_input, jbyteArray secret,
    jbyteArray peer_input, jlong deadline) {
    (void)owner;
    unsigned char *private_body = NULL, *peer_body = NULL, *passphrase = NULL;
    unsigned char wire[CTOS_SSH_KEY_ASSOCIATION_WIRE_BYTES] = {0};
    jbyteArray result = NULL;
    jsize private_length = 0, peer_length = 0, secret_length = 0;
    if (private_input == NULL || peer_input == NULL || (type != 3 && type != 4)
        || deadline <= 0 || ctos_ssh_material_deadline((uint64_t)deadline) != 0) { fail(env); goto done; }
    private_length = (*env)->GetArrayLength(env, private_input);
    if ((*env)->ExceptionCheck(env)) goto done;
    peer_length = (*env)->GetArrayLength(env, peer_input);
    if ((*env)->ExceptionCheck(env)) goto done;
    if (secret != NULL) {
        secret_length = (*env)->GetArrayLength(env, secret);
        if ((*env)->ExceptionCheck(env)) goto done;
        if (secret_length == 0) { fail(env); goto done; }
    }
    if ((size_t)private_length > CTOS_SSH_MATERIAL_BYTES || (size_t)peer_length > CTOS_SSH_MATERIAL_BYTES
        || (size_t)secret_length > CTOS_SSH_MATERIAL_PASSPHRASE_BYTES) { fail(env); goto done; }
    private_body = calloc((size_t)private_length + 1U, 1U);
    peer_body = calloc((size_t)peer_length + 1U, 1U);
    passphrase = calloc((size_t)secret_length + 1U, 1U);
    if (private_body == NULL || peer_body == NULL || passphrase == NULL) { fail(env); goto done; }
    (*env)->GetByteArrayRegion(env, private_input, 0, private_length, (jbyte *)private_body);
    if ((*env)->ExceptionCheck(env)) goto done;
    (*env)->GetByteArrayRegion(env, peer_input, 0, peer_length, (jbyte *)peer_body);
    if ((*env)->ExceptionCheck(env)) goto done;
    if (secret_length != 0) {
        (*env)->GetByteArrayRegion(env, secret, 0, secret_length, (jbyte *)passphrase);
        if ((*env)->ExceptionCheck(env)) goto done;
    }
    result = (*env)->NewByteArray(env, CTOS_SSH_KEY_ASSOCIATION_WIRE_BYTES);
    if ((*env)->ExceptionCheck(env)) { result = NULL; goto done; }
    if (result == NULL) { fail(env); goto done; }
    if (ctos_ssh_material_verify_pair((unsigned int)type, private_body, (size_t)private_length,
        passphrase, (size_t)secret_length, peer_body, (size_t)peer_length, (uint64_t)deadline, wire) != 0) {
        fail(env); result = NULL; goto done;
    }
    (*env)->SetByteArrayRegion(env, result, 0, CTOS_SSH_KEY_ASSOCIATION_WIRE_BYTES, (const jbyte *)wire);
    if ((*env)->ExceptionCheck(env)) { result = NULL; goto done; }
    if (ctos_ssh_material_deadline((uint64_t)deadline) != 0) { fail(env); result = NULL; }
done:
    if (private_body != NULL) { wipe(private_body, (size_t)private_length + 1U); free(private_body); }
    if (peer_body != NULL) { wipe(peer_body, (size_t)peer_length + 1U); free(peer_body); }
    if (passphrase != NULL) { wipe(passphrase, (size_t)secret_length + 1U); free(passphrase); }
    wipe(wire, sizeof(wire));
    if (result != NULL && ctos_ssh_material_deadline((uint64_t)deadline) != 0) { fail(env); result = NULL; }
    return result;
}
JNIEXPORT jbyteArray JNICALL Java_im_majo_ctos_SshManifestPrivateMaterialInspector_00024Native_verifyPrivate(
    JNIEnv *env, jclass owner, jbyteArray input, jbyteArray secret, jlong deadline) {
    (void)owner;
    unsigned char *body = NULL, *passphrase = NULL;
    unsigned char wire[CTOS_SSH_PRIVATE_MATERIAL_WIRE_BYTES] = {0};
    jbyteArray result = NULL;
    jsize length = 0, secret_length = 0;
    if (input == NULL || deadline <= 0
        || ctos_ssh_material_deadline((uint64_t)deadline) != 0) { fail(env); goto done; }
    length = (*env)->GetArrayLength(env, input);
    if ((*env)->ExceptionCheck(env)) goto done;
    if (secret != NULL) {
        secret_length = (*env)->GetArrayLength(env, secret);
        if ((*env)->ExceptionCheck(env)) goto done;
        if (secret_length == 0) { fail(env); goto done; }
    }
    if ((size_t)length > CTOS_SSH_MATERIAL_BYTES
        || (size_t)secret_length > CTOS_SSH_MATERIAL_PASSPHRASE_BYTES) { fail(env); goto done; }
    body = calloc((size_t)length + 1U, 1U);
    passphrase = calloc((size_t)secret_length + 1U, 1U);
    if (body == NULL || passphrase == NULL) { fail(env); goto done; }
    (*env)->GetByteArrayRegion(env, input, 0, length, (jbyte *)body);
    if ((*env)->ExceptionCheck(env)) goto done;
    if (secret_length != 0) {
        (*env)->GetByteArrayRegion(env, secret, 0, secret_length, (jbyte *)passphrase);
        if ((*env)->ExceptionCheck(env)) goto done;
    }
    result = (*env)->NewByteArray(env, CTOS_SSH_PRIVATE_MATERIAL_WIRE_BYTES);
    if ((*env)->ExceptionCheck(env)) { result = NULL; goto done; }
    if (result == NULL) { fail(env); goto done; }
    if (ctos_ssh_material_verify_private(body, (size_t)length, passphrase,
        (size_t)secret_length, (uint64_t)deadline, wire) != 0) {
        fail(env); result = NULL; goto done;
    }
    (*env)->SetByteArrayRegion(env, result, 0, CTOS_SSH_PRIVATE_MATERIAL_WIRE_BYTES, (const jbyte *)wire);
    if ((*env)->ExceptionCheck(env)) { result = NULL; goto done; }
    if (ctos_ssh_material_deadline((uint64_t)deadline) != 0) { fail(env); result = NULL; }
done:
    if (body != NULL) { wipe(body, (size_t)length + 1U); free(body); }
    if (passphrase != NULL) { wipe(passphrase, (size_t)secret_length + 1U); free(passphrase); }
    wipe(wire, sizeof(wire));
    if (result != NULL && ctos_ssh_material_deadline((uint64_t)deadline) != 0) { fail(env); result = NULL; }
    return result;
}
JNIEXPORT jbyteArray JNICALL Java_im_majo_ctos_SshManifestCertificateInspector_00024Native_inspectCertificate(
    JNIEnv *env, jclass owner, jbyteArray input, jlong deadline) {
    (void)owner;
    unsigned char *body = NULL;
    unsigned char wire[CTOS_SSH_CERTIFICATE_FACTS_WIRE_BYTES] = {0};
    jbyteArray result = NULL;
    jsize length = 0;
    if (input == NULL || deadline <= 0
        || ctos_ssh_material_deadline((uint64_t)deadline) != 0) { fail(env); goto done; }
    length = (*env)->GetArrayLength(env, input);
    if ((*env)->ExceptionCheck(env)) goto done;
    if ((size_t)length > CTOS_SSH_MATERIAL_BYTES) { fail(env); goto done; }
    body = calloc((size_t)length + 1U, 1U);
    if (body == NULL) { fail(env); goto done; }
    (*env)->GetByteArrayRegion(env, input, 0, length, (jbyte *)body);
    if ((*env)->ExceptionCheck(env)) goto done;
    result = (*env)->NewByteArray(env, CTOS_SSH_CERTIFICATE_FACTS_WIRE_BYTES);
    if ((*env)->ExceptionCheck(env)) { result = NULL; goto done; }
    if (result == NULL) { fail(env); goto done; }
    if (ctos_ssh_material_inspect_certificate(body, (size_t)length, (uint64_t)deadline, wire) != 0) {
        fail(env); result = NULL; goto done;
    }
    (*env)->SetByteArrayRegion(env, result, 0, CTOS_SSH_CERTIFICATE_FACTS_WIRE_BYTES, (const jbyte *)wire);
    if ((*env)->ExceptionCheck(env)) { result = NULL; goto done; }
    if (ctos_ssh_material_deadline((uint64_t)deadline) != 0) { fail(env); result = NULL; }
done:
    if (body != NULL) { wipe(body, (size_t)length + 1U); free(body); }
    wipe(wire, sizeof(wire));
    if (result != NULL && ctos_ssh_material_deadline((uint64_t)deadline) != 0) { fail(env); result = NULL; }
    return result;
}

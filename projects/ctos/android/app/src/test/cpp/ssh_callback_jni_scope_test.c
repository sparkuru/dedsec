#define _GNU_SOURCE
#include <jni.h>

#include <assert.h>
#include <errno.h>
#include <stdint.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "ssh_callback_adapter.h"
#include "ssh_native_client.h"

/* This host fixture replaces the native ancestor checker and denies effect
 * permits. The real multi-hop client is exercised separately. */
struct test_claim {
    ctos_ssh_role_token role;
    ctos_ssh_callback_token callback;
    JNIEnv *environment;
    ctos_ssh_callback_effect_admission admit_effect;
    void *effect_context;
    int mode;
    unsigned int checks;
};

static struct test_claim claims[2];

JNIEXPORT jlong JNICALL Java_im_majo_ctos_SshNativeCallbackAdapter_00024Native_open(
    JNIEnv *, jclass, jobject, jlong, jint, jlong);

static struct test_claim *find_claim(jlong token) {
    for (size_t index = 0; index < sizeof(claims) / sizeof(claims[0]); index++) {
        if (claims[index].callback == (ctos_ssh_callback_token)token && token > 0)
            return &claims[index];
    }
    return NULL;
}

static int fixture_checker(const void *owner, ctos_ssh_callback_token callback,
    ctos_ssh_role_token role, void *environment) {
    struct test_claim *claim = (struct test_claim *)owner;
    assert(claim != NULL && claim->callback == callback && claim->role == role
        && claim->environment == environment);
    claim->checks++;
    if (claim->mode == 1) { errno = EACCES; return -1; }
    if (claim->mode == 2) assert(ctos_ssh_callback_cancel(callback) == 0);
    return 0;
}

int ctos_ssh_native_client_callback_ancestor_proofs_check_receiver(
    ctos_ssh_callback_token callback, void *environment, const void *receiver,
    ctos_ssh_callback_receiver_validator validator) {
    return ctos_ssh_callback_client_scope_check_receiver(callback, environment,
        receiver, validator, fixture_checker);
}

int ctos_ssh_native_client_effect_java_copy_begin(void *environment, const void *receiver,
    ctos_ssh_callback_receiver_validator validator) {
    (void)environment; (void)receiver; (void)validator;
    errno = ENOTSUP;
    return -1;
}

void ctos_ssh_native_client_effect_java_copy_end(void *environment, const void *receiver,
    ctos_ssh_callback_receiver_validator validator) {
    (void)environment; (void)receiver; (void)validator;
    assert(0 && "fixture never grants an effect permit");
}

int ctos_ssh_native_client_effect_java_callback_returned(void) {
    return 0;
}

int ctos_ssh_native_client_effect_jni_copy_begin(void *environment, const void *receiver,
    ctos_ssh_callback_receiver_validator validator) {
    (void)environment; (void)receiver; (void)validator;
    errno = ENOTSUP;
    return -1;
}

void ctos_ssh_native_client_effect_jni_copy_end(void) {
    assert(0 && "fixture never grants an effect permit");
}

JNIEXPORT jlong JNICALL Java_im_majo_ctos_SshNativeCallbackAdapter_fixtureOpen(
    JNIEnv *env, jclass type, jobject target) {
    struct timespec now;
    unsigned char nonce[32], binding[32];
    struct test_claim *claim = NULL;
    for (size_t index = 0; index < sizeof(claims) / sizeof(claims[0]); index++) {
        if (claims[index].callback == 0) { claim = &claims[index]; break; }
    }
    assert(claim != NULL && clock_gettime(CLOCK_BOOTTIME, &now) == 0);
    uint64_t deadline = (uint64_t)now.tv_sec * 1000U + (uint64_t)now.tv_nsec / 1000000U + 30000U;
    memset(nonce, 1 + (int)(claim - claims), sizeof(nonce));
    memset(binding, 3, sizeof(binding));
    struct ctos_ssh_role_request request = {
        .program = CTOS_SSH_ROLE_CLIENT, .owner_pid = getpid(), .owner_uid = getuid(),
        .hop = 1U + (unsigned int)(claim - claims), .port = 22,
        .deadline = deadline, .connection_id = "jni-test",
        .target_identity = "target:user@example.invalid:22", .host = "example.invalid",
        .public_view_id = "view-jni", .role_nonce = nonce, .connection_binding = binding
    };
    assert(ctos_ssh_role_bootstrap_open(&request, &claim->role) == 0);
    jlong callback = Java_im_majo_ctos_SshNativeCallbackAdapter_00024Native_open(
        env, type, target, (jlong)claim->role, (jint)getuid(), (jlong)deadline);
    assert(callback > 0 && !(*env)->ExceptionCheck(env));
    claim->callback = (ctos_ssh_callback_token)callback;
    claim->environment = env;
    assert(ctos_ssh_callback_client_claim_scoped_effect(claim->callback, env, claim->role,
        claim, fixture_checker, &claim->admit_effect, &claim->effect_context) == 0);
    assert(claim->admit_effect != NULL && claim->effect_context != NULL);
    return callback;
}

JNIEXPORT jboolean JNICALL Java_im_majo_ctos_SshNativeCallbackAdapter_fixtureAdmit(
    JNIEnv *env, jclass type, jlong callback) {
    (void)type;
    struct test_claim *claim = find_claim(callback);
    assert(claim != NULL);
    errno = 0;
    int result = claim->admit_effect(claim->effect_context, env);
    assert(!(*env)->ExceptionCheck(env));
    assert(result == 0 || errno == ECANCELED);
    return result == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_im_majo_ctos_SshNativeCallbackAdapter_fixtureDispatch(
    JNIEnv *env, jclass type, jlong callback) {
    (void)type;
    unsigned char digest[32] = {1};
    int result = ctos_ssh_callback_host_key((ctos_ssh_callback_token)callback, env,
        CTOS_SSH_CALLBACK_HOST_KNOWN, "ssh-ed25519",
        "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", digest, digest);
    return result == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeCallbackAdapter_fixtureMode(
    JNIEnv *env, jclass type, jlong callback, jint mode) {
    (void)env; (void)type;
    struct test_claim *claim = find_claim(callback);
    assert(claim != NULL && mode >= 0 && mode <= 2);
    claim->mode = mode;
}

JNIEXPORT jint JNICALL Java_im_majo_ctos_SshNativeCallbackAdapter_fixtureChecks(
    JNIEnv *env, jclass type, jlong callback) {
    (void)env; (void)type;
    struct test_claim *claim = find_claim(callback);
    assert(claim != NULL);
    return (jint)claim->checks;
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeCallbackAdapter_fixtureClose(
    JNIEnv *env, jclass type, jlong callback) {
    (void)type;
    struct test_claim *claim = find_claim(callback);
    assert(claim != NULL);
    assert(ctos_ssh_callback_client_close(claim->callback, env, claim) == 0);
    assert(ctos_ssh_role_bootstrap_close(claim->role) == 0);
    memset(claim, 0, sizeof(*claim));
}

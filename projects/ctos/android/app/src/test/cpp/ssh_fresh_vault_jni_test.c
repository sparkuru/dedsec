#define _GNU_SOURCE
#include <jni.h>

/* Reuse only the synthetic SSH server. Java owns and steps both clients. */
#define main ctos_unused_native_fixture_main
#include "ssh_native_client_test.c"
#undef main

static struct fixture jump_peer;
static struct fixture target_peer;
static struct sshkey *other_key;
static int bridge_fd = -1;
static int fixture_active;
static int pending_input_fixture;
static size_t target_chunk = 64U;
static ctos_ssh_callback_token effect_cancel_token;
static int effect_cancel_triggered;
static int effect_invalidation_kind;
static JNIEnv *effect_java_env;
static jclass effect_java_class;
static jmethodID effect_java_method;
static uint64_t effect_deadline;
static ctos_ssh_callback_token target_callback;
static int target_selected_calls, target_selected_success;
static JNIEnv *provisional_java_env;
static jclass provisional_java_class;
static jmethodID provisional_java_method;
static int shared_test_effect_permit;
static _Thread_local int lease_destroy_fault;
static _Thread_local int lease_destroy_calls;
static _Thread_local JNIEnv *lease_destroy_env;

static void cancel_parent_at_effect(void *unused) {
    (void)unused;
    CHECK(effect_cancel_token != 0);
    if (effect_invalidation_kind == 1)
        CHECK(ctos_ssh_callback_cancel(effect_cancel_token) == 0);
    else if (effect_invalidation_kind == 2)
        CHECK(ctos_ssh_role_bootstrap_close((ctos_ssh_role_token)effect_cancel_token) == 0);
    else if (effect_invalidation_kind == 3)
        CHECK(ctos_ssh_view_handle_destroy((ctos_ssh_view_token)effect_cancel_token) == 0);
    else CHECK(0);
    effect_cancel_triggered = 1;
}

static void java_invalidation_at_effect(void *unused) {
    (void)unused;
    CHECK(effect_java_env != NULL && effect_java_class != NULL && effect_java_method != NULL);
    (*effect_java_env)->CallStaticVoidMethod(effect_java_env, effect_java_class, effect_java_method);
    CHECK(!(*effect_java_env)->ExceptionCheck(effect_java_env));
    (*effect_java_env)->DeleteGlobalRef(effect_java_env, effect_java_class);
    effect_java_env = NULL;
    effect_java_class = NULL;
    effect_java_method = NULL;
    effect_cancel_triggered = 1;
}

static void wait_for_effect_deadline(void *unused) {
    struct timespec now, pause = {.tv_nsec = 1000000};
    (void)unused;
    do {
        CHECK(clock_gettime(CLOCK_BOOTTIME, &now) == 0);
        if ((uint64_t)now.tv_sec * 1000U + (uint64_t)now.tv_nsec / 1000000U >= effect_deadline) break;
        CHECK(nanosleep(&pause, NULL) == 0);
    } while (1);
    effect_cancel_triggered = 1;
}
static int new_candidates, changed_candidates, exact_candidate_keys;
static int capture_child, child_view_fd = -1, child_socket_fds[2] = {-1, -1};

int __real_ctos_ssh_callback_known_hosts_candidate(ctos_ssh_callback_token, void *,
    const char *, int, const char *, const char *, const unsigned char[32],
    const unsigned char[32], unsigned char *, size_t);
int __real_ctos_ssh_callback_changed_host_candidate(ctos_ssh_callback_token, void *,
    const char *, const char *, const char *, const unsigned char[32],
    const unsigned char[32], unsigned char *, size_t, unsigned char *, size_t);
int __real_ctos_ssh_view_allocate(struct ctos_ssh_view *, size_t);
int __real_ctos_ssh_view_handle_destroy(ctos_ssh_view_token);
int __real_socketpair(int, int, int, int[2]);
int ctos_ssh_callback_jni_test_before_selected_jni_copy(void (*)(void *), void *);
int ctos_ssh_callback_jni_test_last_selected_array_wiped(void);
int ctos_ssh_callback_jni_test_enable_provisional_next(void);
int ctos_ssh_callback_jni_test_wrong_provisional_receiver(JNIEnv *, jobject);
int ctos_ssh_callback_jni_test_fail_effect_invalidation_once(void);
int ctos_ssh_callback_jni_test_fail_legacy_effect_invalidation_once(void);
int ctos_ssh_callback_jni_test_fail_cleanup_begin(unsigned int);
int ctos_ssh_callback_jni_test_fail_cleanup_begin_unknown(int);
int ctos_ssh_callback_jni_test_fail_effect_end(int);
int ctos_ssh_callback_jni_test_fail_effect_end_after(int, unsigned int);
void ctos_ssh_callback_jni_test_clear_effect_invalidation(void);
void ctos_ssh_callback_jni_test_clear_provisional_receiver(JNIEnv *);
int ctos_ssh_callback_jni_test_before_provisional(int, void (*)(void *), void *);
void ctos_ssh_callback_jni_test_clear_before_provisional(void);
int __real_ctos_ssh_callback_credential_bytes_selected(ctos_ssh_callback_token, void *, int,
    const unsigned char[32], uint64_t, const unsigned char[32], unsigned char *, size_t, size_t *);

int __wrap_ctos_ssh_callback_credential_bytes_selected(ctos_ssh_callback_token callback,
    void *environment, int kind, const unsigned char receipt[32], uint64_t cycle,
    const unsigned char digest[32], unsigned char *output, size_t capacity, size_t *length) {
    if (callback == target_callback) target_selected_calls++;
    int result = __real_ctos_ssh_callback_credential_bytes_selected(callback, environment, kind,
        receipt, cycle, digest, output, capacity, length);
    if (callback == target_callback && result == 0) {
        target_selected_success++;
        target_peer.callback.selected_calls++;
    }
    return result;
}

int __wrap_ctos_ssh_view_allocate(struct ctos_ssh_view *view, size_t length) {
    int result = __real_ctos_ssh_view_allocate(view, length);
    if (result == 0 && capture_child) child_view_fd = view->fd;
    return result;
}

int __wrap_ctos_ssh_view_handle_destroy(ctos_ssh_view_token token) {
    int mode = lease_destroy_fault;
    lease_destroy_calls++;
    if (mode == 0) return __real_ctos_ssh_view_handle_destroy(token);
    lease_destroy_fault = 0;
    if ((mode == 2 || mode == 4) && __real_ctos_ssh_view_handle_destroy(token) != 0) return -1;
    if (mode == 3 || mode == 4) {
        JNIEnv *env = lease_destroy_env;
        jclass error = (*env)->FindClass(env, "java/lang/UnsatisfiedLinkError");
        if (error != NULL) {
            (*env)->ThrowNew(env, error, "test native lease destroy failure");
            (*env)->DeleteLocalRef(env, error);
        }
    }
    errno = EIO;
    return -1;
}

int __wrap_socketpair(int domain, int type, int protocol, int descriptors[2]) {
    int result = __real_socketpair(domain, type, protocol, descriptors);
    if (result == 0 && capture_child) {
        child_socket_fds[0] = descriptors[0]; child_socket_fds[1] = descriptors[1];
    }
    return result;
}

static void record_candidate(const char *candidate, const char *key_type, const char *fingerprint) {
    char *expected = sshkey_fingerprint(target_peer.private_key, SSH_DIGEST_SHA256, SSH_FP_DEFAULT);
    if (expected != NULL && strcmp(candidate, "candidate-child") == 0
        && strcmp(key_type, "ssh-ed25519") == 0 && strcmp(fingerprint, expected) == 0)
        exact_candidate_keys++;
    free(expected);
}

int __wrap_ctos_ssh_callback_known_hosts_candidate(ctos_ssh_callback_token callback,
    void *environment, const char *candidate, int classification, const char *key_type,
    const char *fingerprint, const unsigned char base[32], const unsigned char replacement[32],
    unsigned char *body, size_t length) {
    if (classification == CTOS_SSH_CALLBACK_HOST_NEW) new_candidates++;
    record_candidate(candidate, key_type, fingerprint);
    return __real_ctos_ssh_callback_known_hosts_candidate(callback, environment, candidate,
        classification, key_type, fingerprint, base, replacement, body, length);
}

int __wrap_ctos_ssh_callback_changed_host_candidate(ctos_ssh_callback_token callback,
    void *environment, const char *candidate, const char *key_type, const char *fingerprint,
    const unsigned char base[32], const unsigned char replacement[32], unsigned char *body,
    size_t length, unsigned char *records, size_t records_length) {
    changed_candidates++;
    record_candidate(candidate, key_type, fingerprint);
    return __real_ctos_ssh_callback_changed_host_candidate(callback, environment, candidate,
        key_type, fingerprint, base, replacement, body, length, records, records_length);
}

static void fixture_error(JNIEnv *env, const char *message) {
    jclass error = (*env)->FindClass(env, "java/io/IOException");
    if (error != NULL) (*env)->ThrowNew(env, error, message);
}

static void consume_provisional_candidate(void *unused) {
    JNIEnv *env = provisional_java_env;
    jclass type = provisional_java_class;
    jmethodID method = provisional_java_method;
    (void)unused;
    provisional_java_env = NULL;
    provisional_java_class = NULL;
    provisional_java_method = NULL;
    if (env == NULL || type == NULL || method == NULL) return;
    (*env)->CallStaticVoidMethod(env, type, method);
    (void)(*env)->ExceptionCheck(env);
    (*env)->DeleteGlobalRef(env, type);
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_armProvisionalConsumption(
    JNIEnv *env, jclass type, jint stage) {
    if (provisional_java_class != NULL) { fixture_error(env, "provisional hook already armed"); return; }
    provisional_java_method = (*env)->GetStaticMethodID(env, type, "consumeProvisionalCandidate", "()V");
    if (provisional_java_method == NULL || (*env)->ExceptionCheck(env)) return;
    provisional_java_class = (*env)->NewGlobalRef(env, type);
    provisional_java_env = env;
    if (provisional_java_class == NULL || ctos_ssh_callback_jni_test_before_provisional(
            stage, consume_provisional_candidate, NULL) != 0) {
        if (provisional_java_class != NULL) (*env)->DeleteGlobalRef(env, provisional_java_class);
        provisional_java_env = NULL; provisional_java_class = NULL; provisional_java_method = NULL;
        fixture_error(env, "provisional consumption hook could not be armed");
    }
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_armProvisionalFixture(
    JNIEnv *env, jclass type, jobject wrong_receiver) {
    (void)type;
    if (ctos_ssh_callback_jni_test_enable_provisional_next() != 0
        || (wrong_receiver != NULL
            && ctos_ssh_callback_jni_test_wrong_provisional_receiver(env, wrong_receiver) != 0))
        fixture_error(env, "provisional fixture could not be armed");
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_armPendingInputFixture(
    JNIEnv *env, jclass type) {
    (void)type;
    if (fixture_active || pending_input_fixture) {
        fixture_error(env, "pending input fixture is already active");
        return;
    }
    pending_input_fixture = 1;
}

JNIEXPORT jint JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_queuePendingFixtureExtension(
    JNIEnv *env, jclass type, jint chunk) {
    size_t length = 0;
    (void)type;
    if (!fixture_active || !pending_input_fixture || target_peer.server == NULL
        || target_peer.server->kex == NULL || !target_peer.server->kex->done
        || chunk <= 0 || chunk > 64) {
        fixture_error(env, "pending extension fixture is unavailable");
        return 0;
    }
    target_chunk = (size_t)chunk;
    queue_provisional_extension(&target_peer);
    if (ssh_output_ptr(target_peer.server, &length) == NULL || length == 0 || length > INT_MAX) {
        fixture_error(env, "pending extension fixture did not produce output");
        return 0;
    }
    return (jint)length;
}

JNIEXPORT jboolean JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_pendingFixturePeerReady(
    JNIEnv *env, jclass type) {
    size_t length = 0;
    (void)type;
    if (!fixture_active || !pending_input_fixture || target_peer.server == NULL
        || target_peer.server->kex == NULL) {
        fixture_error(env, "pending fixture peer is unavailable");
        return JNI_FALSE;
    }
    (void)ssh_output_ptr(target_peer.server, &length);
    return target_peer.server->kex->done && length == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_pendingFixtureBytes(
    JNIEnv *env, jclass type) {
    int available = -1;
    (void)type;
    if (!fixture_active || child_socket_fds[1] < 0
        || ioctl(child_socket_fds[1], FIONREAD, &available) != 0 || available < 0) {
        fixture_error(env, "pending fixture socket is unavailable");
        return 0;
    }
    return (jint)available;
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_armPendingFixturePeekFailure(
    JNIEnv *env, jclass type) {
    (void)type;
    if (ctos_ssh_native_client_test_provisional_fail_peek(1) != 0)
        fixture_error(env, "pending fixture failure could not be armed");
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_armEffectInvalidationFailure(
    JNIEnv *env, jclass type) {
    (void)type;
    if (ctos_ssh_callback_jni_test_fail_effect_invalidation_once() != 0)
        fixture_error(env, "effect invalidation failure could not be armed");
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_armLeaseDestroyFailure(
    JNIEnv *env, jclass type, jint mode) {
    (void)type;
    if (mode < 1 || mode > 4 || lease_destroy_fault != 0) {
        fixture_error(env, "lease destroy failure could not be armed");
        return;
    }
    lease_destroy_fault = mode;
    lease_destroy_calls = 0;
    lease_destroy_env = env;
}

JNIEXPORT jint JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_leaseDestroyCalls(
    JNIEnv *env, jclass type) {
    (void)env; (void)type;
    return lease_destroy_calls;
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_armLegacyEffectInvalidationFailure(
    JNIEnv *env, jclass type) {
    (void)type;
    if (ctos_ssh_callback_jni_test_fail_legacy_effect_invalidation_once() != 0)
        fixture_error(env, "legacy effect invalidation failure could not be armed");
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_armCleanupBeginFailure(
    JNIEnv *env, jclass type, jint countdown) {
    (void)type;
    if (countdown <= 0 || ctos_ssh_callback_jni_test_fail_cleanup_begin((unsigned int)countdown) != 0)
        fixture_error(env, "cleanup begin failure could not be armed");
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_armCleanupEndFailure(
    JNIEnv *env, jclass type, jint when) {
    (void)type;
    if (ctos_ssh_callback_jni_test_fail_effect_end(when) != 0)
        fixture_error(env, "cleanup end failure could not be armed");
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_armCleanupEndFailureAfter(
    JNIEnv *env, jclass type, jint when, jint countdown) {
    (void)type;
    if (countdown <= 0 || ctos_ssh_callback_jni_test_fail_effect_end_after(when, (unsigned int)countdown) != 0)
        fixture_error(env, "delayed cleanup end failure could not be armed");
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_armCleanupBeginUnknown(
    JNIEnv *env, jclass type, jint when) {
    (void)type;
    if (ctos_ssh_callback_jni_test_fail_cleanup_begin_unknown(when) != 0)
        fixture_error(env, "uncertain cleanup begin could not be armed");
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_beginOtherInvalidator(
    JNIEnv *env, jclass type) {
    (void)env; (void)type;
    ctos_ssh_effect_invalidate_begin();
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_endOtherInvalidator(
    JNIEnv *env, jclass type) {
    (void)env; (void)type;
    ctos_ssh_effect_invalidate_end();
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_acquireSharedTestEffect(
    JNIEnv *env, jclass type) {
    uint32_t generation = 0;
    (void)type;
    if (shared_test_effect_permit || ctos_ssh_effect_snapshot(&generation) != 0
            || ctos_ssh_effect_acquire(generation) != 0) {
        fixture_error(env, "shared fixture effect could not be acquired");
        return;
    }
    shared_test_effect_permit = 1;
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_releaseSharedTestEffect(
    JNIEnv *env, jclass type) {
    (void)type;
    if (!shared_test_effect_permit) {
        fixture_error(env, "shared fixture effect was not acquired");
        return;
    }
    shared_test_effect_permit = 0;
    ctos_ssh_effect_release();
}

JNIEXPORT jboolean JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_effectAvailable(
    JNIEnv *env, jclass type) {
    uint32_t generation = 0;
    (void)env; (void)type;
    return ctos_ssh_effect_snapshot(&generation) == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_activeEffects(
    JNIEnv *env, jclass type) {
    (void)env; (void)type;
    return (jint)ctos_ssh_effect_test_active();
}

JNIEXPORT jlong JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_now(
    JNIEnv *env, jclass type) {
    struct timespec now;
    (void)type;
    if (clock_gettime(CLOCK_BOOTTIME, &now) != 0) {
        fixture_error(env, "boottime unavailable"); return 0;
    }
    return (jlong)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

JNIEXPORT jint JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_pid(
    JNIEnv *env, jclass type) { (void)env; (void)type; return (jint)getpid(); }

JNIEXPORT jint JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_openPeers(
    JNIEnv *env, jclass type) {
    int first[2], second[2];
    (void)type;
    if (fixture_active || socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, first) != 0)
        goto failed;
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, second) != 0) {
        close(first[0]); close(first[1]); goto failed;
    }
    memset(&jump_peer, 0, sizeof(jump_peer));
    memset(&target_peer, 0, sizeof(target_peer));
    jump_peer.pair[0] = first[0]; jump_peer.pair[1] = first[1];
    target_peer.pair[0] = -1; target_peer.pair[1] = second[1];
    bridge_fd = second[0];
    if (sshkey_generate(KEY_ED25519, 0, &jump_peer.private_key) != 0
        || sshkey_generate(KEY_ED25519, 0, &target_peer.private_key) != 0
        || sshkey_generate(KEY_ED25519, 0, &other_key) != 0) goto failed_owned;
    if (sshkey_equal(target_peer.private_key, other_key)) goto failed_owned;
    new_candidates = changed_candidates = exact_candidate_keys = 0;
    effect_cancel_token = 0;
    effect_cancel_triggered = 0;
    effect_invalidation_kind = 0;
    target_callback = 0;
    target_selected_calls = target_selected_success = 0;
    if (pending_input_fixture) {
        target_peer.force_server_cipher = 2;
        target_peer.suppress_server_ext_info = 1;
    }
    setup_synthetic_server(&jump_peer);
    setup_synthetic_server(&target_peer);
    target_peer.service_expected = 1;
    target_peer.discovery_expected = 1;
    target_peer.discovery_methods = "password";
    target_peer.password_expected = 1;
    target_peer.password_username = (const unsigned char *)" 用户😀 ";
    target_peer.password_username_length = sizeof(" 用户😀 ") - 1U;
    jump_peer.service_expected = 1;
    jump_peer.discovery_expected = 1;
    jump_peer.discovery_reply = 1;
    jump_peer.shell_expected = 1;
    jump_peer.jump_expected = 1;
    jump_peer.jump_bridge_enabled = 1;
    jump_peer.jump_bridge_fd = bridge_fd;
    jump_peer.shell_window = 65536U;
    jump_peer.shell_maxpacket = 32768U;
    fixture_active = 1;
    return first[0];

failed_owned:
    sshkey_free(jump_peer.private_key); sshkey_free(target_peer.private_key); sshkey_free(other_key);
    other_key = NULL;
    close(first[0]); close(first[1]); close(second[0]); close(second[1]); bridge_fd = -1;
failed:
    fixture_error(env, "unable to create synthetic peers"); return -1;
}

JNIEXPORT jstring JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_targetFingerprint(
    JNIEnv *env, jclass type) {
    char *fingerprint;
    jstring result;
    (void)type;
    if (!fixture_active || (fingerprint = sshkey_fingerprint(target_peer.private_key,
            SSH_DIGEST_SHA256, SSH_FP_DEFAULT)) == NULL) {
        fixture_error(env, "synthetic target fingerprint unavailable"); return NULL;
    }
    result = (*env)->NewStringUTF(env, fingerprint);
    free(fingerprint);
    return result;
}

static size_t write_host(unsigned char *output, size_t capacity, const char *host,
    const struct sshkey *key) {
    FILE *stream = fmemopen(output, capacity, "w+");
    long length;
    if (stream == NULL) return 0;
    if (ctos_hostfile_write_entry(stream, host, NULL, key, 0) != 1
        || fflush(stream) != 0 || (length = ftell(stream)) <= 0
        || (unsigned long)length >= capacity) { fclose(stream); return 0; }
    if (fclose(stream) != 0) return 0;
    return (size_t)length;
}

JNIEXPORT jbyteArray JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_hostfile(
    JNIEnv *env, jclass type, jint mode) {
    unsigned char data[4096];
    size_t length;
    jbyteArray result;
    (void)type;
    if (!fixture_active || mode < 0 || mode > 2) goto failed;
    length = write_host(data, sizeof(data), "jump.invalid", jump_peer.private_key);
    if (length == 0) goto failed;
    if (mode != 1) {
        size_t n = write_host(data + length, sizeof(data) - length, "example.invalid",
            mode == 0 ? target_peer.private_key : other_key);
        if (n == 0) goto failed;
        length += n;
    }
    result = (*env)->NewByteArray(env, (jsize)length);
    if (result == NULL) return NULL;
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)length, (const jbyte *)data);
    memset(data, 0, sizeof(data));
    return result;
failed:
    fixture_error(env, "unable to build synthetic known_hosts"); return NULL;
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_pumpPeers(
    JNIEnv *env, jclass type) {
    unsigned char data[32768];
    ssize_t count;
    (void)type;
    if (!fixture_active || pump_synthetic_server(&jump_peer, 64U, NULL, NULL) != 0) goto failed;
    if (jump_peer.shell_stage != 3) return;
    if (pump_synthetic_server(&target_peer, target_chunk, NULL, NULL) != 0) goto failed;
    count = recv(bridge_fd, data, sizeof(data), MSG_DONTWAIT);
    if (count > 0) peer_channel_data(&jump_peer, 0, data, (size_t)count);
    else if (count == 0 || (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR)) goto failed;
    return;
failed:
    fixture_error(env, "synthetic peer pump failed");
}

JNIEXPORT jintArray JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_counters(
    JNIEnv *env, jclass type) {
    jint values[] = {(jint)jump_peer.service_requests, (jint)jump_peer.discovery_requests,
        (jint)jump_peer.shell_stage, (jint)target_peer.service_requests,
        (jint)target_peer.discovery_requests, (jint)target_peer.password_requests,
        new_candidates, changed_candidates, exact_candidate_keys,
        effect_cancel_triggered, ctos_ssh_native_client_test_last_password_wiped(),
        target_selected_calls, target_selected_success,
        ctos_ssh_callback_jni_test_last_selected_array_wiped()};
    (void)type;
    jintArray result = (*env)->NewIntArray(env, (jsize)(sizeof(values) / sizeof(values[0])));
    if (result != NULL) (*env)->SetIntArrayRegion(env, result, 0,
        (jsize)(sizeof(values) / sizeof(values[0])), values);
    return result;
}

JNIEXPORT jlong JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_wireChannelBytes(
    JNIEnv *env, jclass type) {
    (void)env;
    (void)type;
    return (jlong)ctos_ssh_native_client_test_wire_channel_bytes();
}

JNIEXPORT jlong JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_earlyReplyDenials(
    JNIEnv *env, jclass type) {
    (void)env;
    (void)type;
    return (jlong)ctos_ssh_native_client_test_early_auth_reply_denials();
}

JNIEXPORT jlong JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_pendingKexDrains(
    JNIEnv *env, jclass type) {
    (void)env;
    (void)type;
    return (jlong)ctos_ssh_native_client_test_pending_kex_drains();
}

/* Deliberately dishonest peer: inject an encrypted auth reply into the child
 * side while its request remains buffered in the other direction. */
JNIEXPORT void JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_earlyTargetReply(
    JNIEnv *env, jclass type, jboolean success) {
    const unsigned char *output;
    size_t length = 0;
    ssize_t written;
    (void)type;
    if (!fixture_active || child_socket_fds[0] < 0 || target_peer.server == NULL) goto failed;
    (void)ssh_output_ptr(target_peer.server, &length);
    if (length != 0 || sshpkt_start(target_peer.server,
            success ? SSH2_MSG_USERAUTH_SUCCESS : SSH2_MSG_USERAUTH_FAILURE) != 0)
        goto failed;
    if (!success && (sshpkt_put_cstring(target_peer.server, "password") != 0
            || sshpkt_put_u8(target_peer.server, 0) != 0)) goto failed;
    if (sshpkt_send(target_peer.server) != 0) goto failed;
    output = ssh_output_ptr(target_peer.server, &length);
    if (output == NULL || length == 0) goto failed;
    written = send(child_socket_fds[0], output, length, MSG_DONTWAIT | MSG_NOSIGNAL);
    if (written < 0 || (size_t)written != length
            || ssh_output_consume(target_peer.server, length) != 0) goto failed;
    return;
failed:
    fixture_error(env, "unable to inject the early target reply");
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_trackTargetCallback(
    JNIEnv *env, jclass type, jlong callback) {
    (void)type;
    if (!fixture_active || callback <= 0 || target_callback != 0) {
        fixture_error(env, "invalid target callback");
        return;
    }
    target_callback = (ctos_ssh_callback_token)callback;
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_armEffectCancellation(
    JNIEnv *env, jclass type, jlong callback, jint phase) {
    (void)type;
    if (callback <= 0 || (phase != 1 && phase != 2)) {
        fixture_error(env, "invalid effect cancellation request");
        return;
    }
    effect_cancel_token = (ctos_ssh_callback_token)callback;
    effect_invalidation_kind = 1;
    int result = phase == 1
        ? ctos_ssh_native_client_test_before_java_secret_copy(cancel_parent_at_effect, NULL)
        : ctos_ssh_native_client_test_before_password_packet(cancel_parent_at_effect, NULL);
    if (result != 0) fixture_error(env, "effect cancellation could not be armed");
}

static int arm_effect_hook(int phase, void (*hook)(void *)) {
    if (phase == 1) return ctos_ssh_native_client_test_before_java_secret_copy(hook, NULL);
    if (phase == 2) return ctos_ssh_native_client_test_before_password_packet(hook, NULL);
    if (phase == 3) return ctos_ssh_callback_jni_test_before_selected_jni_copy(hook, NULL);
    if (phase == 4) return ctos_ssh_native_client_test_before_native_secret_copy(hook, NULL);
    return -1;
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_armNativeInvalidation(
    JNIEnv *env, jclass type, jlong token, jint phase, jint kind) {
    (void)type;
    if (token <= 0 || kind < 1 || kind > 3) { fixture_error(env, "invalid native invalidator"); return; }
    effect_cancel_token = (ctos_ssh_callback_token)token;
    effect_invalidation_kind = kind;
    if (arm_effect_hook(phase, cancel_parent_at_effect) != 0)
        fixture_error(env, "native invalidation could not be armed");
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_armJavaInvalidation(
    JNIEnv *env, jclass type, jint phase) {
    effect_java_env = env;
    effect_java_class = (*env)->NewGlobalRef(env, type);
    effect_java_method = (*env)->GetStaticMethodID(env, type, "invalidateAtEffect", "()V");
    if (effect_java_class == NULL || effect_java_method == NULL || (*env)->ExceptionCheck(env)
        || arm_effect_hook(phase, java_invalidation_at_effect) != 0) {
        if (effect_java_class != NULL) (*env)->DeleteGlobalRef(env, effect_java_class);
        effect_java_class = NULL;
        effect_java_env = NULL;
        effect_java_method = NULL;
        fixture_error(env, "Java invalidation could not be armed");
    }
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_armEffectDeadline(
    JNIEnv *env, jclass type, jlong deadline) {
    (void)type;
    if (deadline <= 0) { fixture_error(env, "invalid deadline"); return; }
    effect_deadline = (uint64_t)deadline;
    if (arm_effect_hook(1, wait_for_effect_deadline) != 0)
        fixture_error(env, "deadline hook could not be armed");
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_armJumpDeadline(
    JNIEnv *env, jclass type, jlong deadline) {
    (void)type;
    if (deadline <= 0) { fixture_error(env, "invalid jump deadline"); return; }
    effect_deadline = (uint64_t)deadline;
    if (ctos_ssh_native_client_test_before_jump_send(wait_for_effect_deadline, NULL) != 0)
        fixture_error(env, "jump deadline hook could not be armed");
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_targetNoneSuccess(
    JNIEnv *env, jclass type) {
    (void)type;
    if (!fixture_active) { fixture_error(env, "synthetic target unavailable"); return; }
    target_peer.discovery_reply = 1;
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_captureChildOpen(
    JNIEnv *env, jclass type) {
    (void)type;
    if (!fixture_active || capture_child) { fixture_error(env, "child capture unavailable"); return; }
    child_view_fd = child_socket_fds[0] = child_socket_fds[1] = -1;
    capture_child = 1;
}

JNIEXPORT jintArray JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_finishChildOpen(
    JNIEnv *env, jclass type) {
    jint values[] = {child_view_fd, child_socket_fds[0], child_socket_fds[1]};
    (void)type;
    if (!capture_child || values[0] < 0 || values[1] < 0 || values[2] < 0
        || values[0] == values[1] || values[0] == values[2] || values[1] == values[2]) {
        fixture_error(env, "child descriptors were not captured"); return NULL;
    }
    capture_child = 0;
    jintArray result = (*env)->NewIntArray(env, 3);
    if (result != NULL) (*env)->SetIntArrayRegion(env, result, 0, 3, values);
    return result;
}

JNIEXPORT jboolean JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_fdClosed(
    JNIEnv *env, jclass type, jint fd) {
    (void)env; (void)type;
    errno = 0;
    return fd >= 0 && fcntl(fd, F_GETFD) < 0 && errno == EBADF ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_closeFd(
    JNIEnv *env, jclass type, jint fd) {
    (void)type;
    if (fd < 0 || close(fd) != 0) fixture_error(env, "fixture socket close failed");
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshFreshVaultRealJniTest_closePeers(
    JNIEnv *env, jclass type) {
    (void)type;
    ctos_ssh_callback_jni_test_clear_provisional_receiver(env);
    ctos_ssh_callback_jni_test_clear_before_provisional();
    ctos_ssh_callback_jni_test_clear_effect_invalidation();
    if (provisional_java_class != NULL) (*env)->DeleteGlobalRef(env, provisional_java_class);
    provisional_java_env = NULL; provisional_java_class = NULL; provisional_java_method = NULL;
    if (!fixture_active) { pending_input_fixture = 0; target_chunk = 64U; return; }
    ssh_free(jump_peer.server); jump_peer.server = NULL;
    ssh_free(target_peer.server); target_peer.server = NULL;
    sshkey_free(jump_peer.private_key); jump_peer.private_key = NULL;
    sshkey_free(target_peer.private_key); target_peer.private_key = NULL;
    sshkey_free(other_key); other_key = NULL;
    if (bridge_fd >= 0) close(bridge_fd);
    bridge_fd = -1;
    fixture_active = 0;
    pending_input_fixture = 0;
    target_chunk = 64U;
}

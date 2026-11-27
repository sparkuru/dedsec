#define _GNU_SOURCE
#include <jni.h>
#include <sched.h>
#include <stdio.h>
#include <sys/stat.h>

#include "ssh_callback_adapter.h"
#include "ssh_native_client.h"
#include "ssh_view_handle.h"
#include "sshkey.h"

static int multihop_callback_open(const struct ctos_ssh_callback_target *,
    ctos_ssh_role_token, int, uint64_t, void *, ctos_ssh_callback_token *);
static int multihop_view_borrow(struct ctos_ssh_view *, struct ctos_ssh_view_borrow **);
static int multihop_key_generate(int, unsigned int, struct sshkey **);
static int multihop_open_jump_child(struct ctos_ssh_native_client *,
    const struct ctos_ssh_native_client_config *, struct ctos_ssh_native_client **,
    struct ctos_ssh_native_jump_relay **);
static int multihop_relay_pump(struct ctos_ssh_native_jump_relay *);
static int multihop_jni_native_open_jump_child(struct ctos_ssh_native_client *,
    const struct ctos_ssh_native_client_config *, struct ctos_ssh_native_client **,
    struct ctos_ssh_native_jump_relay **);

/* Reuse the encrypted peer/relay fixture while replacing only the child's
 * callback target with the production JNI adapter. */
#define ctos_ssh_callback_open multihop_callback_open
#define ctos_ssh_view_borrow_owned_view multihop_view_borrow
#define sshkey_generate multihop_key_generate
#define ctos_ssh_native_client_open_jump_child multihop_open_jump_child
#define ctos_ssh_native_jump_relay_pump multihop_relay_pump
#define main ctos_native_fixture_main
#include "ssh_native_client_test.c"
#undef main
#undef ctos_ssh_native_jump_relay_pump
#undef ctos_ssh_native_client_open_jump_child
#undef sshkey_generate
#undef ctos_ssh_view_borrow_owned_view
#undef ctos_ssh_callback_open

/* The test registers the actual child with this real session table so the
 * existing JNI entry points hold their normal nonrecursive session lock. */
#define ctos_ssh_native_client_open_jump_child multihop_jni_native_open_jump_child
#include "ssh_session_jni.c"
#undef ctos_ssh_native_client_open_jump_child

static JNIEnv *multihop_env;
static jobject multihop_receiver;
static ctos_ssh_callback_token multihop_parent;
static int multihop_bind_receiver;
static int shared_fixture;
static ctos_ssh_view_token shared_token;
static unsigned int shared_key_index, shared_borrows;
static struct sshkey *shared_keys[3];
static unsigned char shared_hosts[4096];
static size_t shared_hosts_length;
static size_t shared_parent_hosts_length;
static uint64_t multihop_parent_session, multihop_child_session;
static uint64_t multihop_root_session;
static pthread_t multihop_owner_thread;
void ctos_ssh_callback_jni_test_exception_after_selected_return(void);
static ctos_ssh_role_token multihop_child_role;
static ctos_ssh_callback_token multihop_child_callback;
static struct ctos_ssh_native_jump_relay *multihop_jni_relay;
static int multihop_relay_fd = -1;
static int multihop_fresh_fd = -1;
static int multihop_use_jni_child, multihop_partial_tested;
static int multihop_use_fresh_child;
static int multihop_inject_post_open_failure;
static unsigned int multihop_jni_child_attempts;
static int step_with_session(JNIEnv *, uint64_t);

JNIEXPORT jint JNICALL Java_im_majo_ctos_SshNativeCallbackAdapter_fixtureNativeFreshCases(
    JNIEnv *env, jclass type) {
    (void)env; (void)type;
    checks = 0;
    fixture_step_budget = TEST_STEP_BUDGET;
    test_jump_chain_identity_admission();
    test_jump_nested_ssh(CHILD_FRESH_KNOWN);
    test_jump_nested_ssh(CHILD_FRESH_NEW);
    test_jump_nested_ssh(CHILD_FRESH_CHANGED);
    test_jump_nested_ssh(CHILD_FRESH_PARENT_CANCEL);
    return (jint)checks;
}

/* Test-only sequencing injection: after the real constructor has returned
 * both owners, force its caller to take the JNI cleanup-only path. */
static int multihop_jni_native_open_jump_child(struct ctos_ssh_native_client *parent,
    const struct ctos_ssh_native_client_config *config,
    struct ctos_ssh_native_client **child, struct ctos_ssh_native_jump_relay **relay) {
    multihop_jni_child_attempts++;
    int result = ctos_ssh_native_client_open_jump_child(parent, config, child, relay);
    if (result == 0 && multihop_inject_post_open_failure) {
        multihop_inject_post_open_failure = 0;
        errno = ECANCELED;
        return -1;
    }
    return result;
}

/* Only fixture-generated host keys are substituted. Each peer still has a
 * different key, all present in the one immutable test known_hosts snapshot. */
static int multihop_key_generate(int type, unsigned int bits, struct sshkey **out) {
    if (!shared_fixture) return sshkey_generate(type, bits, out);
    if (type != KEY_ED25519 || bits != 0 || out == NULL || shared_key_index >= 3U
        || shared_keys[shared_key_index] == NULL) { errno = EINVAL; return -1; }
    *out = shared_keys[shared_key_index];
    shared_keys[shared_key_index++] = NULL;
    return 0;
}

/* The fixture's individual views remain caller-owned for its existing C
 * callback assertions. The actual native clients each borrow the same real
 * registry-owned view and never consume those individual fixture views. */
static int multihop_view_borrow(struct ctos_ssh_view *view,
    struct ctos_ssh_view_borrow **out) {
    if (!shared_fixture) return ctos_ssh_view_borrow_owned_view(view, out);
    if (shared_token == 0) {
        struct ctos_ssh_view *snapshot = load_view(shared_hosts,
            multihop_use_fresh_child ? shared_parent_hosts_length : shared_hosts_length, view->deadline);
        int result = ctos_ssh_view_handle_create(snapshot->mapping, snapshot->length, &shared_token);
        int error = errno;
        destroy_view(snapshot);
        if (result != 0) { errno = error; return -1; }
    }
    int result = ctos_ssh_view_handle_borrow(shared_token, out);
    if (result == 0) shared_borrows++;
    return result;
}

static void prepare_shared_fixture(void) {
    CHECK(!shared_fixture && shared_token == 0 && shared_key_index == 0
        && shared_borrows == 0 && shared_hosts_length == 0);
    for (unsigned int index = 0; index < 3U; index++) {
        CHECK(shared_keys[index] == NULL);
        CHECK(sshkey_generate(KEY_ED25519, 0, &shared_keys[index]) == 0);
        for (unsigned int previous = 0; previous < index; previous++)
            CHECK(!sshkey_equal(shared_keys[index], shared_keys[previous]));
        shared_hosts_length += format_host_key(shared_keys[index], shared_hosts + shared_hosts_length,
            sizeof(shared_hosts) - shared_hosts_length);
        if (index == 1U) shared_parent_hosts_length = shared_hosts_length;
    }
    shared_fixture = 1;
}

static void finish_shared_fixture(void) {
    ctos_ssh_view_token replacement = 0;
    CHECK(shared_fixture && shared_token != 0 && shared_key_index == 3U && shared_borrows == 3U);
    CHECK(ctos_ssh_view_handle_destroy(shared_token) == 0);
    for (unsigned int index = 0; index < 3U; index++) CHECK(shared_keys[index] == NULL);
    /* Destroy can report success while a borrow still pins the revoked
     * handle. A new handle proves the singleton was actually released. */
    struct ctos_ssh_view *snapshot = load_view(shared_hosts, shared_hosts_length, deadline_after(5000U));
    CHECK(ctos_ssh_view_handle_create(snapshot->mapping, snapshot->length, &replacement) == 0);
    CHECK(replacement != 0 && replacement != shared_token);
    destroy_view(snapshot);
    CHECK(ctos_ssh_view_handle_destroy(replacement) == 0);
    memset(shared_hosts, 0, sizeof(shared_hosts));
    shared_hosts_length = 0;
    shared_parent_hosts_length = 0;
    shared_token = 0;
    shared_borrows = shared_key_index = 0;
    shared_fixture = 0;
}

JNIEXPORT jlong JNICALL Java_im_majo_ctos_SshNativeCallbackAdapter_00024Native_open(
    JNIEnv *, jclass, jobject, jlong, jint, jlong);
JNIEXPORT jboolean JNICALL Java_im_majo_ctos_SshNativeCallbackAdapter_nativeCallbackAncestorProofsLive(
    JNIEnv *, jobject, jlong);

static void set_long_field(JNIEnv *env, jobject receiver, const char *name, jlong value) {
    jclass type = (*env)->GetObjectClass(env, receiver);
    CHECK(type != NULL && !(*env)->ExceptionCheck(env));
    jfieldID field = (*env)->GetFieldID(env, type, name, "J");
    CHECK(field != NULL && !(*env)->ExceptionCheck(env));
    (*env)->SetLongField(env, receiver, field, value);
    CHECK(!(*env)->ExceptionCheck(env));
    (*env)->DeleteLocalRef(env, type);
}

static jint int_field(JNIEnv *env, jobject receiver, const char *name) {
    jclass type = (*env)->GetObjectClass(env, receiver);
    CHECK(type != NULL && !(*env)->ExceptionCheck(env));
    jfieldID field = (*env)->GetFieldID(env, type, name, "I");
    CHECK(field != NULL && !(*env)->ExceptionCheck(env));
    jint value = (*env)->GetIntField(env, receiver, field);
    CHECK(!(*env)->ExceptionCheck(env));
    (*env)->DeleteLocalRef(env, type);
    return value;
}

static int multihop_callback_open(const struct ctos_ssh_callback_target *target,
    ctos_ssh_role_token role, int uid, uint64_t deadline, void *environment,
    ctos_ssh_callback_token *callback) {
    if (fixture_jump_parent == NULL || multihop_env == NULL || !multihop_bind_receiver)
        return ctos_ssh_callback_open(target, role, uid, deadline, environment, callback);
    struct callback_fixture *fixture = target->context;
    fixture->environment = multihop_env;
    jlong token = Java_im_majo_ctos_SshNativeCallbackAdapter_00024Native_open(
        multihop_env, NULL, multihop_receiver, (jlong)role, (jint)uid, (jlong)deadline);
    if (token <= 0 || (*multihop_env)->ExceptionCheck(multihop_env)) { errno = EIO; return -1; }
    *callback = (ctos_ssh_callback_token)token;
    set_long_field(multihop_env, multihop_receiver, "callback", token);
    if (multihop_use_jni_child) multihop_child_callback = *callback;
    return 0;
}

static jboolean open_jni_child_source(uint64_t token, const struct ctos_ssh_native_client_config *config,
    ctos_ssh_callback_token callback, ctos_ssh_view_token view, uint64_t deadline, jbyteArray fresh) {
    JNIEnv *env = multihop_env;
    jstring path = (*env)->NewStringUTF(env, config->known_hosts_path);
    jstring host = (*env)->NewStringUTF(env, config->host);
    jstring candidate = (*env)->NewStringUTF(env, config->candidate_id);
    jbyteArray selection = NULL;
    CHECK(path != NULL && host != NULL && candidate != NULL && !(*env)->ExceptionCheck(env));
    if (config->authentication_selection_wire != NULL) {
        CHECK(config->authentication_selection_wire_length <= INT_MAX);
        selection = (*env)->NewByteArray(env, (jsize)config->authentication_selection_wire_length);
        CHECK(selection != NULL);
        (*env)->SetByteArrayRegion(env, selection, 0,
            (jsize)config->authentication_selection_wire_length,
            (const jbyte *)config->authentication_selection_wire);
        CHECK(!(*env)->ExceptionCheck(env));
    }
    jboolean result = fresh == NULL
        ? Java_im_majo_ctos_SshNativeSession_00024Native_openJumpChild(
            env, NULL, (jlong)token, (jlong)config->role, (jint)config->owner_uid,
            (jlong)callback, (jlong)view, (jlong)deadline, (jint)config->step_budget,
            path, host, candidate, selection)
        : Java_im_majo_ctos_SshNativeSession_00024Native_openJumpChildFresh(
            env, NULL, (jlong)token, (jlong)config->role, (jint)config->owner_uid,
            (jlong)callback, (jlong)view, (jlong)deadline, (jint)config->step_budget,
            path, host, candidate, selection, fresh);
    if (selection != NULL) (*env)->DeleteLocalRef(env, selection);
    (*env)->DeleteLocalRef(env, candidate);
    (*env)->DeleteLocalRef(env, host);
    (*env)->DeleteLocalRef(env, path);
    return result;
}

static jboolean open_jni_child(uint64_t token, const struct ctos_ssh_native_client_config *config,
    ctos_ssh_callback_token callback, ctos_ssh_view_token view, uint64_t deadline) {
    return open_jni_child_source(token, config, callback, view, deadline, NULL);
}

static void require_jni_failure(JNIEnv *env) {
    CHECK((*env)->ExceptionCheck(env));
    (*env)->ExceptionClear(env);
}

static void clear_jni_wire(JNIEnv *env, jbyteArray wire, size_t length) {
    unsigned char zeroes[1024] = {0};
    for (size_t offset = 0; offset < length; offset += sizeof(zeroes)) {
        size_t remaining = length - offset;
        if (remaining > sizeof(zeroes)) remaining = sizeof(zeroes);
        (*env)->SetByteArrayRegion(env, wire, (jsize)offset, (jsize)remaining,
            (const jbyte *)zeroes);
    }
    CHECK(!(*env)->ExceptionCheck(env));
    (*env)->DeleteLocalRef(env, wire);
}

/* Both failures must occur after copying the Java array into the protected
 * mapping, but before the child constructor can claim the parent callback. */
static void test_fresh_jni_malformed_owner(JNIEnv *env,
    const struct ctos_ssh_native_client_config *config, uint64_t parent_handle,
    const struct ctos_ssh_view *fresh) {
    struct ctos_ssh_view_summary before, after;
    CHECK(fresh->length > 1U && fresh->mapping[0] != 0);
    CHECK(ctos_ssh_view_handle_summary(shared_token, &before) == 0);
    for (int truncated = 0; truncated < 2; truncated++) {
        size_t length = fresh->length - (size_t)truncated;
        jbyteArray wire = (*env)->NewByteArray(env, (jsize)length);
        CHECK(wire != NULL && !(*env)->ExceptionCheck(env));
        (*env)->SetByteArrayRegion(env, wire, 0, (jsize)length,
            (const jbyte *)fresh->mapping);
        if (!truncated) {
            jbyte bad_magic = 0;
            (*env)->SetByteArrayRegion(env, wire, 0, 1, &bad_magic);
        }
        CHECK(!(*env)->ExceptionCheck(env));
        jlong child = Java_im_majo_ctos_SshNativeSession_00024Native_reserveJumpChild(
            env, NULL, (jlong)parent_handle);
        CHECK(child > 0 && !(*env)->ExceptionCheck(env));
        unsigned int attempts = multihop_jni_child_attempts;
        CHECK(open_jni_child_source((uint64_t)child, config, config->callback,
            shared_token, config->deadline, wire) == JNI_FALSE);
        CHECK(multihop_jni_child_attempts == attempts);
        require_jni_failure(env);
        CHECK(Java_im_majo_ctos_SshNativeSession_00024Native_jumpChildState(
            env, NULL, child) == JUMP_CHILD_FAILED_EMPTY);
        struct session_slot *slot = lock_jump_child(env, child);
        CHECK(slot != NULL && slot->opening && slot->client == NULL && slot->relay == NULL
            && slot->pending_borrow != NULL && slot->fresh_hostfile_view != NULL);
        CHECK(slot->parent_token == parent_handle && slot->view_token == shared_token
            && ctos_ssh_view_borrow_live(slot->pending_borrow) == 0);
        const struct ctos_ssh_view *shared = ctos_ssh_view_borrow_view(slot->pending_borrow);
        struct ctos_ssh_view *copied = slot->fresh_hostfile_view;
        CHECK(shared != NULL && copied->locked && !copied->valid && copied->mapping != NULL
            && copied->length == length && copied->fd >= 0 && copied->fd != shared->fd);
        if (truncated) {
            CHECK(memcmp(copied->mapping, fresh->mapping, length) == 0
                && copied->epoch == fresh->epoch && copied->count == fresh->count);
        } else {
            CHECK(copied->mapping[0] == 0 && copied->count == 0
                && memcmp(copied->mapping + 1, fresh->mapping + 1, length - 1U) == 0);
        }
        int fresh_fd = copied->fd, shared_fd = shared->fd;
        int flags = fcntl(fresh_fd, F_GETFD);
        struct stat shape;
        CHECK(flags >= 0 && (flags & FD_CLOEXEC) != 0
            && fstat(fresh_fd, &shape) == 0 && S_ISREG(shape.st_mode)
            && shape.st_uid == getuid() && shape.st_nlink == 0
            && shape.st_size == (off_t)length);
        CHECK(pthread_mutex_unlock(&sessions_lock) == 0);
        Java_im_majo_ctos_SshNativeSession_00024Native_abortJumpChild(env, NULL, child);
        CHECK(!(*env)->ExceptionCheck(env));
        CHECK(fcntl(fresh_fd, F_GETFD) < 0 && errno == EBADF);
        CHECK(fcntl(shared_fd, F_GETFD) >= 0);
        CHECK(ctos_ssh_view_handle_summary(shared_token, &after) == 0
            && after.count == before.count && after.private_count == before.private_count
            && after.public_bytes == before.public_bytes && after.generation == before.generation
            && after.epoch == before.epoch && after.backing_bytes == before.backing_bytes
            && after.mode == before.mode && after.close_on_exec == before.close_on_exec);
        struct ctos_ssh_view_borrow *probe = NULL;
        CHECK(ctos_ssh_view_handle_borrow(shared_token, &probe) == 0 && probe != NULL);
        const struct ctos_ssh_view *original = ctos_ssh_view_borrow_view(probe);
        const char *known_hosts_path = "ssh/known_hosts";
        const struct ctos_ssh_file *known_hosts = ctos_ssh_view_lookup(original,
            (const unsigned char *)known_hosts_path, strlen(known_hosts_path), CTOS_SSH_KNOWN_HOSTS);
        size_t expected = multihop_use_fresh_child ? shared_parent_hosts_length : shared_hosts_length;
        CHECK(known_hosts != NULL && known_hosts->body.length == expected
            && memcmp(known_hosts->body.data, shared_hosts, expected) == 0);
        CHECK(ctos_ssh_view_borrow_release(probe) == 0);
        jintArray status = Java_im_majo_ctos_SshNativeSession_00024Native_jumpStatus(
            env, NULL, (jlong)parent_handle);
        jint values[4];
        CHECK(status != NULL && !(*env)->ExceptionCheck(env)
            && (*env)->GetArrayLength(env, status) == 4);
        (*env)->GetIntArrayRegion(env, status, 0, 4, values);
        CHECK(!(*env)->ExceptionCheck(env) && values[0] == CTOS_SSH_JUMP_OPEN
            && values[1] == 0 && values[2] == 0);
        (*env)->DeleteLocalRef(env, status);
        clear_jni_wire(env, wire, length);
    }
}

/* Exercise the real fresh JNI entry and its cleanup-only owner. The parent
 * callback is deliberately already claimed, so native construction must
 * fail after loading the protected source and keep both loans for abort. */
static void test_fresh_jni_failed_owner(JNIEnv *env,
    const struct ctos_ssh_native_client_config *config, uint64_t parent_handle) {
    struct ctos_ssh_view *fresh = load_view(shared_hosts, shared_hosts_length, config->deadline);
    size_t binding_offset = (size_t)(fresh->binding.data - fresh->mapping);
    CHECK(fresh->epoch == 11U && binding_offset < fresh->length && fresh->length <= INT_MAX);
    fresh->mapping[39] = 12U;
    fresh->mapping[binding_offset] ^= 0x40U;
    fresh->valid = 0; fresh->private_count = 0; fresh->public_bytes = 0;
    memset(fresh->files, 0, sizeof(fresh->files));
    CHECK(ctos_ssh_view_validate(fresh) == 0);
    jbyteArray wire = (*env)->NewByteArray(env, (jsize)fresh->length);
    CHECK(wire != NULL && !(*env)->ExceptionCheck(env));
    (*env)->SetByteArrayRegion(env, wire, 0, (jsize)fresh->length, (const jbyte *)fresh->mapping);
    CHECK(!(*env)->ExceptionCheck(env));
    jlong child = Java_im_majo_ctos_SshNativeSession_00024Native_reserveJumpChild(
        env, NULL, (jlong)parent_handle);
    CHECK(child > 0 && !(*env)->ExceptionCheck(env));
    unsigned int attempts = multihop_jni_child_attempts;
    CHECK(open_jni_child_source((uint64_t)child, config, config->callback,
        shared_token, config->deadline, wire) == JNI_FALSE);
    CHECK(multihop_jni_child_attempts == attempts + 1U);
    require_jni_failure(env);
    CHECK(Java_im_majo_ctos_SshNativeSession_00024Native_jumpChildState(
        env, NULL, child) == JUMP_CHILD_FAILED_EMPTY);
    struct session_slot *slot = lock_jump_child(env, child);
    CHECK(slot != NULL && slot->pending_borrow != NULL && slot->fresh_hostfile_view != NULL);
    CHECK(ctos_ssh_view_borrow_live(slot->pending_borrow) == 0);
    CHECK(slot->fresh_hostfile_view->locked && slot->fresh_hostfile_view->valid
        && slot->fresh_hostfile_view->length == fresh->length
        && memcmp(slot->fresh_hostfile_view->mapping, fresh->mapping, fresh->length) == 0);
    int fresh_fd = slot->fresh_hostfile_view->fd;
    CHECK(pthread_mutex_unlock(&sessions_lock) == 0);
    Java_im_majo_ctos_SshNativeSession_00024Native_abortJumpChild(env, NULL, child);
    CHECK(!(*env)->ExceptionCheck(env));
    CHECK(fcntl(fresh_fd, F_GETFD) < 0 && errno == EBADF);
    struct ctos_ssh_view_summary summary;
    CHECK(ctos_ssh_view_handle_summary(shared_token, &summary) == 0);
    clear_jni_wire(env, wire, fresh->length);
    test_fresh_jni_malformed_owner(env, config, parent_handle, fresh);
    destroy_view(fresh);
}

struct wrong_thread_jni {
    JavaVM *vm;
    uint64_t handle;
    int state, query_rejected, abort_rejected;
};

static void *wrong_thread_jni_access(void *opaque) {
    struct wrong_thread_jni *attempt = opaque;
    JNIEnv *env = NULL;
    if ((*attempt->vm)->AttachCurrentThread(attempt->vm, &env, NULL) != JNI_OK) return NULL;
    attempt->state = Java_im_majo_ctos_SshNativeSession_00024Native_jumpChildState(
        env, NULL, (jlong)attempt->handle);
    attempt->query_rejected = (*env)->ExceptionCheck(env);
    if (attempt->query_rejected) (*env)->ExceptionClear(env);
    Java_im_majo_ctos_SshNativeSession_00024Native_abortJumpChild(env, NULL, (jlong)attempt->handle);
    attempt->abort_rejected = (*env)->ExceptionCheck(env);
    if (attempt->abort_rejected) (*env)->ExceptionClear(env);
    (*attempt->vm)->DetachCurrentThread(attempt->vm);
    return NULL;
}

static int multihop_open_jump_child(struct ctos_ssh_native_client *parent,
    const struct ctos_ssh_native_client_config *config,
    struct ctos_ssh_native_client **child_out, struct ctos_ssh_native_jump_relay **relay_out) {
    if (!multihop_use_jni_child || !multihop_bind_receiver
        || !pthread_equal(multihop_owner_thread, pthread_self()) || config == NULL
        || config->role != multihop_child_role || config->callback != multihop_child_callback
        || (fixture_parent_context != NULL
            && config->managed_view_borrow == fixture_parent_context->view_borrow)
        || (fixture_root_context != NULL
            && config->managed_view_borrow == fixture_root_context->view_borrow))
        return ctos_ssh_native_client_open_jump_child(parent, config, child_out, relay_out);
    jbyteArray fresh = NULL;
    if (multihop_use_fresh_child) {
        const struct ctos_ssh_view *original = ctos_ssh_view_borrow_view(config->managed_view_borrow);
        const struct ctos_ssh_file *original_hosts = ctos_ssh_view_lookup(original,
            (const unsigned char *)"ssh/known_hosts", strlen("ssh/known_hosts"), CTOS_SSH_KNOWN_HOSTS);
        CHECK(original_hosts != NULL && original_hosts->body.length == shared_parent_hosts_length
            && shared_parent_hosts_length < shared_hosts_length
            && memcmp(original_hosts->body.data, shared_hosts, shared_parent_hosts_length) == 0);
        struct ctos_ssh_view *source = load_view(shared_hosts, shared_hosts_length, config->deadline);
        size_t binding = (size_t)(source->binding.data - source->mapping);
        CHECK(source->epoch == 11U && binding < source->length && source->length <= INT_MAX);
        source->mapping[39] = 12U;
        source->mapping[binding] ^= 0x40U;
        source->valid = 0; source->private_count = 0; source->public_bytes = 0;
        memset(source->files, 0, sizeof(source->files));
        CHECK(ctos_ssh_view_validate(source) == 0);
        fresh = (*multihop_env)->NewByteArray(multihop_env, (jsize)source->length);
        CHECK(fresh != NULL);
        (*multihop_env)->SetByteArrayRegion(multihop_env, fresh, 0, (jsize)source->length,
            (const jbyte *)source->mapping);
        CHECK(!(*multihop_env)->ExceptionCheck(multihop_env));
        destroy_view(source);
    }
    unsigned char was_open[512] = {0};
    for (int fd = 0; fd < (int)sizeof(was_open); fd++) was_open[fd] = fcntl(fd, F_GETFD) >= 0;
    CHECK(open_jni_child_source(multihop_child_session, config, config->callback,
        shared_token, config->deadline, fresh) == JNI_TRUE && !(*multihop_env)->ExceptionCheck(multihop_env));
    if (fresh != NULL) (*multihop_env)->DeleteLocalRef(multihop_env, fresh);
    if (multihop_use_fresh_child) {
        /* The underlying C fixture counts only socket FDs below 512. Preserve
         * the fresh memfd while moving it outside that fixture's scan. */
        struct session_slot *fresh_slot = lock_jump_child(multihop_env, (jlong)multihop_child_session);
        CHECK(fresh_slot != NULL && fresh_slot->fresh_hostfile_view != NULL);
        CHECK(fresh_slot->fresh_hostfile_view->locked && fresh_slot->fresh_hostfile_view->valid);
        const struct ctos_ssh_file *fresh_hosts = ctos_ssh_view_lookup(fresh_slot->fresh_hostfile_view,
            (const unsigned char *)"ssh/known_hosts", strlen("ssh/known_hosts"), CTOS_SSH_KNOWN_HOSTS);
        CHECK(fresh_hosts != NULL && fresh_hosts->body.length == shared_hosts_length
            && memcmp(fresh_hosts->body.data, shared_hosts, shared_hosts_length) == 0);
        int old_fd = fresh_slot->fresh_hostfile_view->fd;
        struct stat fresh_shape;
        CHECK(fstat(old_fd, &fresh_shape) == 0 && S_ISREG(fresh_shape.st_mode)
            && fresh_shape.st_nlink == 0 && (fcntl(old_fd, F_GETFD) & FD_CLOEXEC) != 0);
        int held_fd = fcntl(old_fd, F_DUPFD_CLOEXEC, 512);
        CHECK(held_fd >= 512 && close(old_fd) == 0);
        fresh_slot->fresh_hostfile_view->fd = held_fd;
        multihop_fresh_fd = held_fd;
        CHECK(pthread_mutex_unlock(&sessions_lock) == 0);
    }
    int new_fds[3] = {-1, -1, -1};
    size_t new_count = 0;
    for (int fd = 0; fd < (int)sizeof(was_open); fd++) {
        if (!was_open[fd] && fcntl(fd, F_GETFD) >= 0) {
            CHECK(new_count < sizeof(new_fds) / sizeof(new_fds[0]));
            new_fds[new_count++] = fd;
        }
    }
    CHECK(new_count == 3U);
    /* socketpair creates the relay endpoint before its child endpoint and
     * native duplicate; the fixture later injects a real relay close error. */
    multihop_relay_fd = new_fds[0];
    CHECK(Java_im_majo_ctos_SshNativeSession_00024Native_jumpChildState(
        multihop_env, NULL, (jlong)multihop_child_session) == JUMP_CHILD_OPEN);
    struct session_slot *slot = lock_jump_child(multihop_env, (jlong)multihop_child_session);
    CHECK(slot != NULL && slot->client != NULL && slot->relay != NULL);
    *child_out = slot->client;
    *relay_out = slot->relay;
    multihop_jni_relay = slot->relay;
    CHECK(pthread_mutex_unlock(&sessions_lock) == 0);
    return 0;
}

static int multihop_relay_pump(struct ctos_ssh_native_jump_relay *relay) {
    if (!multihop_use_jni_child || !pthread_equal(multihop_owner_thread, pthread_self())
        || relay != multihop_jni_relay)
        return ctos_ssh_native_jump_relay_pump(relay);
    Java_im_majo_ctos_SshNativeSession_00024Native_pumpJumpRelay(
        multihop_env, NULL, (jlong)multihop_child_session);
    return (*multihop_env)->ExceptionCheck(multihop_env) ? -1 : 0;
}

JNIEXPORT jboolean JNICALL Java_im_majo_ctos_SshNativeCallbackAdapter_fixtureSessionLockHeld(
    JNIEnv *env, jclass type) {
    (void)env; (void)type;
    int result = pthread_mutex_trylock(&sessions_lock);
    if (result == 0) {
        pthread_mutex_unlock(&sessions_lock);
        return JNI_FALSE;
    }
    return result == EBUSY ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeCallbackAdapter_fixtureCancelParent(
    JNIEnv *env, jclass type) {
    (void)env; (void)type;
    CHECK(multihop_parent != 0 && ctos_ssh_callback_cancel(multihop_parent) == 0);
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeCallbackAdapter_fixtureRevokeSharedView(
    JNIEnv *env, jclass type) {
    (void)env; (void)type;
    CHECK(shared_fixture && shared_token != 0 && ctos_ssh_view_handle_destroy(shared_token) == 0);
}

JNIEXPORT jint JNICALL Java_im_majo_ctos_SshNativeCallbackAdapter_fixtureEffectActive(
    JNIEnv *env, jclass type) {
    (void)env; (void)type;
    return (jint)ctos_ssh_effect_test_active();
}

JNIEXPORT void JNICALL Java_im_majo_ctos_SshNativeCallbackAdapter_fixtureExceptionAfterReturn(
    JNIEnv *env, jclass type) {
    (void)env; (void)type;
    ctos_ssh_callback_jni_test_exception_after_selected_return();
}

JNIEXPORT jboolean JNICALL Java_im_majo_ctos_SshNativeCallbackAdapter_fixtureCancelParentConcurrent(
    JNIEnv *env, jclass type) {
    (void)env; (void)type;
    return multihop_parent != 0 && ctos_ssh_callback_cancel(multihop_parent) == 0
        ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_im_majo_ctos_SshNativeCallbackAdapter_fixtureAwaitInvalidation(
    JNIEnv *env, jclass type) {
    uint32_t generation;
    uint64_t limit = deadline_after(5000);
    (void)env; (void)type;
    do {
        if (ctos_ssh_effect_snapshot(&generation) != 0) return JNI_TRUE;
        sched_yield();
    } while (deadline_after(0) < limit);
    return JNI_FALSE;
}

static int step_with_session(JNIEnv *env, uint64_t token) {
    jintArray result = Java_im_majo_ctos_SshNativeSession_00024Native_step(
        env, NULL, (jlong)token, JNI_FALSE);
    if (result == NULL || (*env)->ExceptionCheck(env)) {
        if (result != NULL) (*env)->DeleteLocalRef(env, result);
        return -1;
    }
    (*env)->DeleteLocalRef(env, result);
    return 0;
}

static void cleanup_jni_child(struct fixture *child) {
    CHECK(ctos_ssh_native_client_close(child->client) == 0);
    CHECK(close(child->pair[0]) == 0);
    CHECK(ctos_ssh_native_client_destroy(child->client) == 0);
    ssh_free(child->server);
    CHECK(fcntl(child->pair[1], F_GETFD) < 0 && errno == EBADF);
    CHECK(ctos_ssh_role_bootstrap_close(child->role) == 0);
    destroy_view(child->view);
    sshkey_free(child->public_key);
    sshkey_free(child->private_key);
    free(child->public_blob);
}

static void cleanup_jni_child_fixture(struct fixture *child) {
    /* The JNI owner already closed/destroyed its client and its independent
     * borrow. This fixture-owned probe borrow was never handed to it. */
    CHECK(ctos_ssh_view_borrow_release(child->view_borrow) == 0);
    child->view_borrow = NULL;
    CHECK(close(child->pair[0]) == 0);
    ssh_free(child->server);
    CHECK(fcntl(child->pair[1], F_GETFD) < 0 && errno == EBADF);
    CHECK(ctos_ssh_role_bootstrap_close(child->role) == 0);
    destroy_view(child->view);
    sshkey_free(child->public_key);
    sshkey_free(child->private_key);
    free(child->public_blob);
}

static void test_jni_partial_case(JNIEnv *env, int post_open_injection) {
    struct fixture parent;
    struct session_slot *slot;
    struct ctos_ssh_native_jump_relay *used = NULL;
    ctos_ssh_view_token view = 0, replacement = 0;
    uint64_t parent_handle = 0, child_handle;
    int pair[2];

    setup_jump_fixture(&parent, 1);
    CHECK(ctos_ssh_native_client_begin_password_auth(parent.client) == 0);
    drive_password(&parent, CTOS_SSH_PASSWORD_AUTH_SUCCESS, 0);
    ctos_ssh_role_token next = jump_next_role(&parent, 6);
    parent.jump_bridge_enabled = 1;
    parent.shell_window = 65536U;
    parent.shell_maxpacket = 32768U;
    CHECK(ctos_ssh_native_client_begin_jump(parent.client, next) == 0);
    drive_jump_phase(&parent, CTOS_SSH_JUMP_OPEN);
    if (!post_open_injection) {
        CHECK(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, pair) == 0);
        CHECK(ctos_ssh_native_jump_relay_open(parent.client, pair[0], &used) == 0);
        CHECK(ctos_ssh_native_jump_relay_close(used) == 0);
        CHECK(ctos_ssh_native_jump_relay_destroy(used) == 0);
        CHECK(close(pair[1]) == 0);
    }
    /* This parent is test-registered and has its own fixture view. The child
     * gets a real managed duplicate of the same wire for the owner test. */
    CHECK(ctos_ssh_view_handle_create(parent.view->mapping, parent.view->length, &view) == 0);
    CHECK(reserve_session(&parent_handle) == 0);
    slot = lock_reserved_session(parent_handle);
    CHECK(slot != NULL);
    slot->client = parent.client;
    slot->view_token = view;
    slot->deadline = parent.deadline;
    slot->opening = 0;
    CHECK(pthread_mutex_unlock(&sessions_lock) == 0);

    jclass receiver_class = (*env)->GetObjectClass(env, multihop_receiver);
    CHECK(receiver_class != NULL);
    jfieldID other_field = (*env)->GetFieldID(env, receiver_class, "other",
        "Lim/majo/ctos/SshNativeCallbackAdapter;");
    CHECK(other_field != NULL);
    jobject other = (*env)->GetObjectField(env, multihop_receiver, other_field);
    CHECK(other != NULL && !(*env)->ExceptionCheck(env));
    jlong callback = Java_im_majo_ctos_SshNativeCallbackAdapter_00024Native_open(
        env, NULL, other, (jlong)next, (jint)getuid(), (jlong)parent.deadline);
    CHECK(callback > 0 && !(*env)->ExceptionCheck(env));
    child_handle = (uint64_t)Java_im_majo_ctos_SshNativeSession_00024Native_reserveJumpChild(
        env, NULL, (jlong)parent_handle);
    CHECK(child_handle != 0 && !(*env)->ExceptionCheck(env));
    struct ctos_ssh_native_client_config config = {
        .role = next, .owner_uid = getuid(), .callback = (ctos_ssh_callback_token)callback,
        .deadline = parent.deadline, .step_budget = 4096U,
        .known_hosts_path = "ssh/known_hosts", .host = "example.invalid",
        .candidate_id = "native-candidate"
    };
    multihop_inject_post_open_failure = post_open_injection;
    CHECK(open_jni_child(child_handle, &config, config.callback, view, parent.deadline) == JNI_FALSE);
    require_jni_failure(env);
    CHECK(!multihop_inject_post_open_failure);
    CHECK(Java_im_majo_ctos_SshNativeSession_00024Native_jumpChildState(
        env, NULL, (jlong)child_handle) == (post_open_injection
            ? JUMP_CHILD_FAILED_RELAY : JUMP_CHILD_FAILED_CLIENT));
    CHECK(step_with_session(env, child_handle) != 0);
    require_jni_failure(env);
    Java_im_majo_ctos_SshNativeSession_00024Native_pumpJumpRelay(env, NULL, (jlong)child_handle);
    require_jni_failure(env);
    Java_im_majo_ctos_SshNativeSession_00024Native_abortJumpChild(env, NULL, (jlong)child_handle);
    require_jni_failure(env);
    Java_im_majo_ctos_SshNativeSession_00024Native_close(env, NULL, (jlong)parent_handle);
    require_jni_failure(env);
    Java_im_majo_ctos_SshNativeSession_00024Native_close(env, NULL, (jlong)child_handle);
    CHECK(!(*env)->ExceptionCheck(env));
    Java_im_majo_ctos_SshNativeSession_00024Native_destroy(env, NULL, (jlong)child_handle);
    CHECK(!(*env)->ExceptionCheck(env));
    CHECK(Java_im_majo_ctos_SshNativeSession_00024Native_jumpChildState(
        env, NULL, (jlong)child_handle) == -1);
    require_jni_failure(env);
    CHECK(pthread_mutex_lock(&sessions_lock) == 0);
    slot = find_session_locked(parent_handle);
    CHECK(slot != NULL && !has_children_locked(parent_handle));
    memset(slot, 0, sizeof(*slot));
    CHECK(pthread_mutex_unlock(&sessions_lock) == 0);
    CHECK(ctos_ssh_view_handle_destroy(view) == 0);
    CHECK(ctos_ssh_view_handle_create(parent.view->mapping, parent.view->length, &replacement) == 0);
    CHECK(ctos_ssh_view_handle_destroy(replacement) == 0);
    CHECK(ctos_ssh_role_bootstrap_close(next) == 0);
    cleanup_fixture(&parent, 1);
    (*env)->DeleteLocalRef(env, other);
    (*env)->DeleteLocalRef(env, receiver_class);
}

static void prepare_jni_child_owner(JNIEnv *env, struct fixture *parent,
    ctos_ssh_role_token next_role) {
    struct session_slot *slot;
    jint values[4];
    CHECK(reserve_session(&multihop_parent_session) == 0);
    slot = lock_reserved_session(multihop_parent_session);
    CHECK(slot != NULL);
    slot->client = parent->client;
    slot->view_token = shared_token;
    slot->deadline = parent->deadline;
    slot->opening = 0;
    CHECK(pthread_mutex_unlock(&sessions_lock) == 0);
    jintArray status = Java_im_majo_ctos_SshNativeSession_00024Native_jumpStatus(
        env, NULL, (jlong)multihop_parent_session);
    CHECK(status != NULL && !(*env)->ExceptionCheck(env) && (*env)->GetArrayLength(env, status) == 4);
    (*env)->GetIntArrayRegion(env, status, 0, 4, values);
    CHECK(!(*env)->ExceptionCheck(env) && values[0] == CTOS_SSH_JUMP_OPEN
        && values[1] == 0 && values[2] == 0);
    (*env)->DeleteLocalRef(env, status);

    multihop_child_session = (uint64_t)Java_im_majo_ctos_SshNativeSession_00024Native_reserveJumpChild(
        env, NULL, (jlong)multihop_parent_session);
    CHECK(multihop_child_session != 0 && !(*env)->ExceptionCheck(env));
    CHECK(Java_im_majo_ctos_SshNativeSession_00024Native_jumpChildState(
        env, NULL, (jlong)multihop_child_session) == JUMP_CHILD_RESERVED);
    struct wrong_thread_jni attempt = {.handle = multihop_child_session, .state = 99};
    pthread_t wrong_thread;
    CHECK((*env)->GetJavaVM(env, &attempt.vm) == JNI_OK);
    CHECK(pthread_create(&wrong_thread, NULL, wrong_thread_jni_access, &attempt) == 0);
    CHECK(pthread_join(wrong_thread, NULL) == 0);
    CHECK(attempt.state == -1 && attempt.query_rejected && attempt.abort_rejected);
    Java_im_majo_ctos_SshNativeSession_00024Native_close(env, NULL, (jlong)multihop_parent_session);
    require_jni_failure(env);
    CHECK(Java_im_majo_ctos_SshNativeSession_00024Native_reserveJumpChild(
        env, NULL, (jlong)multihop_parent_session) == 0);
    require_jni_failure(env);
    Java_im_majo_ctos_SshNativeSession_00024Native_abortJumpChild(env, NULL,
        (jlong)multihop_child_session);
    CHECK(!(*env)->ExceptionCheck(env));

    struct ctos_ssh_native_client_config invalid = {
        .role = next_role, .owner_uid = getuid(), .callback = parent->callback_token,
        .deadline = parent->deadline, .step_budget = 4096U,
        .known_hosts_path = "ssh/known_hosts", .host = "example.invalid",
        .candidate_id = "native-candidate"
    };
    for (int mismatch = 0; mismatch < 3; mismatch++) {
        multihop_child_session = (uint64_t)Java_im_majo_ctos_SshNativeSession_00024Native_reserveJumpChild(
            env, NULL, (jlong)multihop_parent_session);
        CHECK(multihop_child_session != 0 && !(*env)->ExceptionCheck(env));
        invalid.role = mismatch == 2 ? parent->role : next_role;
        CHECK(open_jni_child(multihop_child_session, &invalid, invalid.callback,
            mismatch == 0 ? shared_token + 1U : shared_token,
            mismatch == 1 ? invalid.deadline + 1U : invalid.deadline) == JNI_FALSE);
        require_jni_failure(env);
        CHECK(Java_im_majo_ctos_SshNativeSession_00024Native_jumpChildState(
            env, NULL, (jlong)multihop_child_session) == JUMP_CHILD_FAILED_EMPTY);
        if (mismatch == 2) {
            /* The valid view was borrowed before the native constructor
             * rejected the wrong next role; abort must release that owner. */
            slot = lock_jump_child(env, (jlong)multihop_child_session);
            CHECK(slot != NULL && slot->pending_borrow != NULL
                && ctos_ssh_view_borrow_live(slot->pending_borrow) == 0);
            CHECK(pthread_mutex_unlock(&sessions_lock) == 0);
        }
        Java_im_majo_ctos_SshNativeSession_00024Native_close(env, NULL, (jlong)multihop_parent_session);
        require_jni_failure(env);
        Java_im_majo_ctos_SshNativeSession_00024Native_abortJumpChild(env, NULL,
            (jlong)multihop_child_session);
        CHECK(!(*env)->ExceptionCheck(env));
    }
    invalid.role = next_role;
    test_fresh_jni_failed_owner(env, &invalid, multihop_parent_session);
    multihop_child_session = (uint64_t)Java_im_majo_ctos_SshNativeSession_00024Native_reserveJumpChild(
        env, NULL, (jlong)multihop_parent_session);
    CHECK(multihop_child_session != 0 && !(*env)->ExceptionCheck(env));
    multihop_child_role = next_role;
    multihop_use_jni_child = 1;
}

static void pump_target(struct fixture *root, struct fixture *middle,
    struct fixture *child, struct jump_bridge_fixture *first,
    struct jump_bridge_fixture *second) {
    if (middle == NULL) jump_bridge_turn(root, child, first, 0);
    else three_hop_turn(root, middle, child, first, second, 0);
}

static void shared_shell_scenario(JNIEnv *env, uint64_t session, int mode,
    struct fixture *root, struct fixture *middle, struct fixture *child,
    struct jump_bridge_fixture *first, struct jump_bridge_fixture *second) {
    struct ctos_ssh_view_summary summary;
    struct ctos_ssh_native_jump_status denied;
    int running = 0;
    CHECK(mode >= 6 && mode <= 8);
    if (mode == 7) {
        CHECK(ctos_ssh_view_handle_destroy(shared_token) == 0);
        Java_im_majo_ctos_SshNativeSession_00024Native_beginShell(env, NULL, (jlong)session, 80, 24);
        CHECK((*env)->ExceptionCheck(env));
        (*env)->ExceptionClear(env);
        CHECK(ctos_ssh_view_borrow_live(root->view_borrow) != 0 && errno == EACCES);
        CHECK(ctos_ssh_view_borrow_live(middle->view_borrow) != 0 && errno == EACCES);
        CHECK(ctos_ssh_native_client_jump_status(middle->client, &denied) != 0);
        return;
    }
    child->shell_expected = 1;
    child->shell_window = 65536U;
    child->shell_maxpacket = 32768U;
    Java_im_majo_ctos_SshNativeSession_00024Native_beginShell(env, NULL, (jlong)session, 80, 24);
    CHECK(!(*env)->ExceptionCheck(env));
    if (multihop_use_fresh_child) {
        CHECK(multihop_fresh_fd >= 512 && fcntl(multihop_fresh_fd, F_GETFD) < 0 && errno == EBADF);
        CHECK(ctos_ssh_view_handle_summary(shared_token, &summary) == 0);
        multihop_fresh_fd = -1;
    }
    if (!multihop_use_jni_child) child->view_borrow = NULL;
    CHECK(ctos_ssh_view_borrow_live(root->view_borrow) == 0);
    CHECK(ctos_ssh_view_borrow_live(middle->view_borrow) == 0);
    CHECK(ctos_ssh_view_handle_summary(shared_token, &summary) == 0);
    for (unsigned int turn = 0; turn < 200000U; turn++) {
        CHECK(step_with_session(env, session) == 0);
        pump_target(root, middle, child, first, second);
        if (shell_status(child).phase == CTOS_SSH_SHELL_RUNNING) { running = 1; break; }
    }
    CHECK(running);
    if (mode == 8) {
        peer_channel_data(child, 0, (const unsigned char *)"fenced", 6U);
        for (unsigned int turn = 0; turn < 200000U && shell_status(child).buffered_bytes != 6U; turn++) {
            CHECK(step_with_session(env, session) == 0);
            pump_target(root, middle, child, first, second);
        }
        CHECK(shell_status(child).buffered_bytes == 6U);
        CHECK(ctos_ssh_view_handle_destroy(shared_token) == 0);
        CHECK(ctos_ssh_view_borrow_live(root->view_borrow) != 0 && errno == EACCES);
        CHECK(ctos_ssh_view_borrow_live(middle->view_borrow) != 0 && errno == EACCES);
        jbyteArray output = Java_im_majo_ctos_SshNativeSession_00024Native_readShell(
            env, NULL, (jlong)session, 16);
        CHECK(output == NULL && (*env)->ExceptionCheck(env));
        (*env)->ExceptionClear(env);
        Java_im_majo_ctos_SshNativeSession_00024Native_retainJumpTransport(env, NULL,
            (jlong)multihop_parent_session);
        require_jni_failure(env);
        return;
    }
    Java_im_majo_ctos_SshNativeSession_00024Native_retainJumpTransport(env, NULL,
        (jlong)multihop_parent_session);
    CHECK(!(*env)->ExceptionCheck(env));
    middle->view_borrow = NULL;
    CHECK(ctos_ssh_view_borrow_live(root->view_borrow) == 0);
    CHECK(ctos_ssh_view_handle_summary(shared_token, &summary) == 0);
    Java_im_majo_ctos_SshNativeSession_00024Native_retainJumpTransport(env, NULL,
        (jlong)multihop_root_session);
    CHECK(!(*env)->ExceptionCheck(env));
    root->view_borrow = NULL;
    CHECK(ctos_ssh_view_handle_summary(shared_token, &summary) == 0);
    CHECK(ctos_ssh_view_handle_destroy(shared_token) == 0);
    CHECK(ctos_ssh_view_handle_summary(shared_token, &summary) != 0);

    jbyteArray input = (*env)->NewByteArray(env, 5);
    CHECK(input != NULL && !(*env)->ExceptionCheck(env));
    (*env)->SetByteArrayRegion(env, input, 0, 5, (const jbyte *)"input");
    CHECK(!(*env)->ExceptionCheck(env));
    CHECK(Java_im_majo_ctos_SshNativeSession_00024Native_writeShell(env, NULL,
        (jlong)session, input) == 5);
    CHECK(!(*env)->ExceptionCheck(env));
    (*env)->DeleteLocalRef(env, input);
    for (unsigned int turn = 0; turn < 200000U && child->shell_input_length != 5U; turn++) {
        CHECK(step_with_session(env, session) == 0);
        pump_target(root, middle, child, first, second);
    }
    CHECK(child->shell_input_length == 5U && memcmp(child->shell_input, "input", 5U) == 0);
    peer_channel_data(child, 0, (const unsigned char *)"shared", 6U);
    for (unsigned int turn = 0; turn < 200000U && shell_status(child).buffered_bytes != 6U; turn++) {
        CHECK(step_with_session(env, session) == 0);
        pump_target(root, middle, child, first, second);
    }
    CHECK(shell_status(child).buffered_bytes == 6U);
    jbyteArray output = Java_im_majo_ctos_SshNativeSession_00024Native_readShell(
        env, NULL, (jlong)session, 16);
    CHECK(output != NULL && !(*env)->ExceptionCheck(env) && (*env)->GetArrayLength(env, output) == 6);
    jbyte bytes[6];
    (*env)->GetByteArrayRegion(env, output, 0, 6, bytes);
    CHECK(!(*env)->ExceptionCheck(env) && memcmp(bytes, "shared", 6U) == 0);
    (*env)->DeleteLocalRef(env, output);
    peer_channel_data(child, 0, (const unsigned char *)"stale", 5U);
    for (unsigned int turn = 0; turn < 200000U && shell_status(child).buffered_bytes != 5U; turn++) {
        CHECK(step_with_session(env, session) == 0);
        pump_target(root, middle, child, first, second);
    }
    CHECK(shell_status(child).buffered_bytes == 5U);
    CHECK(ctos_ssh_callback_cancel(root->callback_token) == 0);
    output = Java_im_majo_ctos_SshNativeSession_00024Native_readShell(
        env, NULL, (jlong)session, 16);
    CHECK(output == NULL && (*env)->ExceptionCheck(env));
    (*env)->ExceptionClear(env);
}

JNIEXPORT jint JNICALL Java_im_majo_ctos_SshNativeCallbackAdapter_fixtureRun(
    JNIEnv *env, jclass type, jobject receiver, jint mode) {
    (void)type;
    static const unsigned char target_nonce[32] = {10};
    struct fixture jump, middle, child;
    struct fixture *parent = &jump;
    struct jump_bridge_fixture first = {0}, second = {0};
    struct jump_bridge_fixture *target_bridge = &first;
    struct session_slot *slot;
    uint64_t session = 0;
    int pair[2], target_server = -1, ready = 0, denied = 0;
    int deep = mode >= 3 && mode <= 10, cancel_host = mode == 1 || mode == 4;
    int shared = mode >= 6 && mode <= 10;
    int cancel_credential = mode == 2 || mode == 5 || mode == 9 || mode >= 12;

    CHECK(env != NULL && receiver != NULL && mode >= 0 && mode <= 17);
    CHECK(multihop_env == NULL && multihop_receiver == NULL
        && multihop_parent == 0 && !multihop_bind_receiver);
    checks = 0;
    fixture_step_budget = TEST_STEP_BUDGET;
    multihop_env = env;
    multihop_receiver = receiver;
    multihop_owner_thread = pthread_self();
    multihop_use_fresh_child = mode == 10;
    if (shared && !multihop_partial_tested) {
        test_jni_partial_case(env, 0);
        test_jni_partial_case(env, 1);
        multihop_partial_tested = 1;
    }
    if (shared) prepare_shared_fixture();
    setup_jump_fixture(&jump, 1);
    CHECK(ctos_ssh_native_client_begin_password_auth(jump.client) == 0);
    drive_password(&jump, CTOS_SSH_PASSWORD_AUTH_SUCCESS, 0);
    ctos_ssh_role_token next = jump_next_role(&jump, deep ? 7U : 6U);
    CHECK(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, pair) == 0);
    first.server_fd = pair[0];
    jump.jump_bridge_enabled = 1;
    jump.jump_bridge_fd = first.server_fd;
    jump.shell_window = 65536U;
    jump.shell_maxpacket = 32768U;
    if (shared) {
        CHECK(reserve_session(&multihop_root_session) == 0);
        slot = lock_reserved_session(multihop_root_session);
        CHECK(slot != NULL);
        slot->client = jump.client;
        slot->view_token = shared_token;
        slot->deadline = jump.deadline;
        slot->opening = 0;
        CHECK(pthread_mutex_unlock(&sessions_lock) == 0);
        Java_im_majo_ctos_SshNativeSession_00024Native_beginJump(env, NULL,
            (jlong)multihop_root_session, (jlong)next);
        CHECK(!(*env)->ExceptionCheck(env));
    } else CHECK(ctos_ssh_native_client_begin_jump(jump.client, next) == 0);
    drive_jump_phase(&jump, CTOS_SSH_JUMP_OPEN);
    multihop_parent = jump.callback_token;
    target_server = pair[1];
    if (deep) {
        fixture_step_budget = 4096U;
        setup_nested_child(&middle, &jump, &jump, next, target_server, &first.relay);
        fixture_step_budget = TEST_STEP_BUDGET;
        for (unsigned int turn = 0; turn < 200000U; turn++) {
            int complete = ctos_ssh_native_client_complete(middle.client);
            jump_bridge_turn(&jump, &middle, &first, !complete);
            if (ctos_ssh_native_client_complete(middle.client) && middle.server->kex->done) {
                ready = 1;
                break;
            }
        }
        CHECK(ready);
        middle.service_expected = 1;
        CHECK(ctos_ssh_native_client_begin_userauth_service(middle.client) == 0);
        ready = 0;
        for (unsigned int turn = 0; turn < 200000U; turn++) {
            jump_bridge_turn(&jump, &middle, &first, 1);
            if (ctos_ssh_native_client_userauth_service_ready(middle.client)) { ready = 1; break; }
        }
        CHECK(ready && ctos_ssh_native_client_begin_password_auth(middle.client) == 0);
        enum ctos_ssh_native_client_password_auth_result auth = CTOS_SSH_PASSWORD_AUTH_PENDING;
        for (unsigned int turn = 0; turn < 200000U && auth == CTOS_SSH_PASSWORD_AUTH_PENDING; turn++) {
            jump_bridge_turn(&jump, &middle, &first, 1);
            CHECK(ctos_ssh_native_client_password_auth_result(middle.client, &auth) == 0);
        }
        CHECK(auth == CTOS_SSH_PASSWORD_AUTH_SUCCESS && middle.callback.selected_calls == 1);
        struct ctos_ssh_role_request request = role_request(jump.deadline);
        request.hop = 2;
        request.role_nonce = target_nonce;
        request.public_view_id = "target-view";
        CHECK(ctos_ssh_role_bootstrap_open(&request, &next) == 0);
        CHECK(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, pair) == 0);
        second.server_fd = pair[0];
        target_server = pair[1];
        middle.shell_expected = middle.jump_expected = middle.jump_bridge_enabled = 1;
        middle.jump_bridge_fd = second.server_fd;
        middle.shell_window = 65536U;
        middle.shell_maxpacket = 32768U;
        CHECK(ctos_ssh_native_client_begin_jump(middle.client, next) == 0);
        for (unsigned int turn = 0; turn < 200000U
                && jump_status(&middle).phase != CTOS_SSH_JUMP_OPEN; turn++)
            jump_bridge_turn(&jump, &middle, &first, 1);
        CHECK(jump_status(&middle).phase == CTOS_SSH_JUMP_OPEN);
        parent = &middle;
        target_bridge = &second;
        ready = 0;
        fixture_step_budget = 4096U;
    }
    if (shared) prepare_jni_child_owner(env, parent, next);
    set_long_field(env, receiver, "parentCallback", (jlong)parent->callback_token);
    multihop_bind_receiver = 1;
    setup_nested_child(&child, parent, &jump, next, target_server, &target_bridge->relay);
    multihop_bind_receiver = 0;
    fixture_step_budget = TEST_STEP_BUDGET;
    if (shared) {
        const struct ctos_ssh_view *view = ctos_ssh_view_borrow_view(jump.view_borrow);
        CHECK(shared_token != 0 && shared_borrows == 3U && view != NULL);
        CHECK(jump.view_borrow != middle.view_borrow && jump.view_borrow != child.view_borrow
            && middle.view_borrow != child.view_borrow);
        CHECK(ctos_ssh_view_borrow_view(middle.view_borrow) == view
            && ctos_ssh_view_borrow_view(child.view_borrow) == view);
    }
    if (shared) {
        session = multihop_child_session;
        CHECK(multihop_jni_relay == target_bridge->relay);
    } else {
        CHECK(reserve_session(&session) == 0);
        slot = lock_reserved_session(session);
        CHECK(slot != NULL);
        slot->client = child.client;
        slot->opening = 0;
        CHECK(pthread_mutex_unlock(&sessions_lock) == 0);
    }
    CHECK(Java_im_majo_ctos_SshNativeCallbackAdapter_nativeCallbackAncestorProofsLive(
        env, receiver, (jlong)child.callback_token) == JNI_FALSE);

    for (unsigned int turn = 0; turn < 200000U; turn++) {
        if (!ctos_ssh_native_client_complete(child.client) && step_with_session(env, session) != 0) {
            CHECK(cancel_host && (*env)->ExceptionCheck(env));
            (*env)->ExceptionClear(env);
            denied = 1;
            break;
        }
        pump_target(&jump, deep ? &middle : NULL, &child, &first, &second);
        if (ctos_ssh_native_client_complete(child.client) && child.server->kex->done) {
            ready = 1;
            break;
        }
    }
    CHECK((cancel_host && denied && !ready) || (!cancel_host && ready && !denied));
    CHECK(int_field(env, receiver, "hostCompleted") == 1);
    if (!cancel_host) {
        CHECK(int_field(env, receiver, "kexCompleted") == 1);
        child.service_expected = 1;
        Java_im_majo_ctos_SshNativeSession_00024Native_beginUserauthService(env, NULL, (jlong)session);
        CHECK(!(*env)->ExceptionCheck(env));
        ready = 0;
        for (unsigned int turn = 0; turn < 200000U; turn++) {
            CHECK(step_with_session(env, session) == 0);
            pump_target(&jump, deep ? &middle : NULL, &child, &first, &second);
            if (Java_im_majo_ctos_SshNativeSession_00024Native_userauthServiceReady(
                    env, NULL, (jlong)session) == JNI_TRUE) { ready = 1; break; }
            CHECK(!(*env)->ExceptionCheck(env));
        }
        CHECK(ready);
        if (mode >= 11) {
            CHECK(ctos_ssh_native_client_test_abandoned_effect_frame(child.client) == 0);
            CHECK(ctos_ssh_native_client_test_arm_child_effect(child.client) == 0);
        }
        Java_im_majo_ctos_SshNativeSession_00024Native_beginPasswordAuth(env, NULL, (jlong)session);
        if (cancel_credential) {
            CHECK((*env)->ExceptionCheck(env));
            (*env)->ExceptionClear(env);
            CHECK(child.password_requests == 0);
            if (shared) {
                struct ctos_ssh_view_summary summary;
                CHECK(ctos_ssh_view_handle_summary(shared_token, &summary) != 0);
                CHECK(ctos_ssh_view_borrow_live(jump.view_borrow) != 0 && errno == EACCES);
                CHECK(ctos_ssh_view_borrow_live(middle.view_borrow) != 0 && errno == EACCES);
                CHECK(ctos_ssh_view_borrow_live(child.view_borrow) != 0 && errno == EACCES);
            }
        } else {
            CHECK(!(*env)->ExceptionCheck(env));
            CHECK(int_field(env, receiver, "credentialCompleted") == 1);
            child.callback.selected_calls = 1;
            jint auth = 1;
            for (unsigned int turn = 0; turn < 200000U && auth == 1; turn++) {
                CHECK(step_with_session(env, session) == 0);
                pump_target(&jump, deep ? &middle : NULL, &child, &first, &second);
                auth = Java_im_majo_ctos_SshNativeSession_00024Native_passwordAuthResult(
                    env, NULL, (jlong)session);
                CHECK(!(*env)->ExceptionCheck(env));
            }
            CHECK(auth == 2 && child.password_requests == 1);
        }
        if (mode >= 11) CHECK(ctos_ssh_effect_test_active() == 0);
        CHECK(int_field(env, receiver, "credentialCompleted") == 1);
        if (shared && !cancel_credential)
            shared_shell_scenario(env, session, mode == 10 ? 6 : mode,
                &jump, &middle, &child, &first, &second);
    }
    CHECK(Java_im_majo_ctos_SshNativeCallbackAdapter_nativeCallbackAncestorProofsLive(
        env, receiver, (jlong)child.callback_token) == JNI_FALSE);
    if (shared) {
        Java_im_majo_ctos_SshNativeSession_00024Native_close(env, NULL, (jlong)session);
        CHECK(!(*env)->ExceptionCheck(env));
        if (mode == 6) {
            /* After the child-owned endpoint closes, only its relay peer
             * among these newly created sockets reaches EOF. Drain anything
             * already queued to verify this is that exact live endpoint. */
            int saw_peer_eof = 0;
            unsigned char queued[1024];
            for (unsigned int attempt = 0; attempt < 128U; attempt++) {
                ssize_t count = recv(multihop_relay_fd, queued, sizeof(queued), MSG_DONTWAIT);
                if (count == 0) { saw_peer_eof = 1; break; }
                CHECK(count > 0 || (count < 0 && errno == EINTR));
            }
            CHECK(saw_peer_eof);
            CHECK(multihop_relay_fd >= 0 && close(multihop_relay_fd) == 0);
            Java_im_majo_ctos_SshNativeSession_00024Native_destroy(env, NULL, (jlong)session);
            require_jni_failure(env);
            /* close(2) reported EBADF after the relay gave up its descriptor.
             * Reuse that number and prove the retry does not close it. */
            CHECK(dup2(target_bridge->server_fd, multihop_relay_fd) == multihop_relay_fd);
            Java_im_majo_ctos_SshNativeSession_00024Native_destroy(env, NULL, (jlong)session);
            CHECK(!(*env)->ExceptionCheck(env));
            CHECK(fcntl(multihop_relay_fd, F_GETFD) >= 0);
            CHECK(close(multihop_relay_fd) == 0);
        } else {
            Java_im_majo_ctos_SshNativeSession_00024Native_destroy(env, NULL, (jlong)session);
            CHECK(!(*env)->ExceptionCheck(env));
        }
        target_bridge->relay = NULL;
        multihop_jni_relay = NULL;
        multihop_relay_fd = -1;
        cleanup_jni_child_fixture(&child);
        CHECK(pthread_mutex_lock(&sessions_lock) == 0);
        slot = find_session_locked(multihop_parent_session);
        CHECK(slot != NULL && slot->client == parent->client && !has_children_locked(slot->token));
        memset(slot, 0, sizeof(*slot));
        CHECK(pthread_mutex_unlock(&sessions_lock) == 0);
        multihop_use_jni_child = 0;
        multihop_parent_session = multihop_child_session = 0;
        multihop_child_role = 0;
        multihop_child_callback = 0;
    } else {
        CHECK(pthread_mutex_lock(&sessions_lock) == 0);
        slot = find_session_locked(session);
        CHECK(slot != NULL && slot->client == child.client);
        memset(slot, 0, sizeof(*slot));
        CHECK(pthread_mutex_unlock(&sessions_lock) == 0);
        cleanup_jni_child(&child);
        CHECK(ctos_ssh_native_jump_relay_close(target_bridge->relay) == 0);
        CHECK(ctos_ssh_native_jump_relay_destroy(target_bridge->relay) == 0);
    }
    CHECK(close(target_bridge->server_fd) == 0);
    if (deep) {
        cleanup_fixture(&middle, 1);
        CHECK(ctos_ssh_native_jump_relay_close(first.relay) == 0);
        CHECK(ctos_ssh_native_jump_relay_destroy(first.relay) == 0);
        CHECK(close(first.server_fd) == 0);
    }
    if (shared) {
        CHECK(pthread_mutex_lock(&sessions_lock) == 0);
        slot = find_session_locked(multihop_root_session);
        CHECK(slot != NULL && slot->client == jump.client && !has_children_locked(slot->token));
        memset(slot, 0, sizeof(*slot));
        CHECK(pthread_mutex_unlock(&sessions_lock) == 0);
        multihop_root_session = 0;
    }
    cleanup_fixture(&jump, 1);
    if (shared) finish_shared_fixture();
    multihop_parent = 0;
    CHECK(multihop_fresh_fd == -1);
    multihop_use_fresh_child = 0;
    multihop_receiver = NULL;
    multihop_env = NULL;
    return (jint)checks;
}

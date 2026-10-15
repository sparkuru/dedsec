#define _GNU_SOURCE

#include "ssh_callback_adapter.h"

#include <assert.h>
#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

static unsigned int cases;

static uint64_t deadline_after(unsigned int milliseconds) {
    struct timespec time;
    assert(clock_gettime(CLOCK_BOOTTIME, &time) == 0);
    return (uint64_t)time.tv_sec * 1000U + (uint64_t)time.tv_nsec / 1000000U
        + milliseconds;
}

struct fixture {
    ctos_ssh_callback_token callback;
    void *environment;
    unsigned int host_calls;
    unsigned int host_evidence_calls;
    unsigned int candidate_calls;
    unsigned int candidate_abort_calls;
    unsigned int kex_calls;
    unsigned int credential_calls;
    unsigned int credential_bytes_calls;
    unsigned int release_calls;
    int reenter;
    int reentry_rejected;
    int release_failure;
    int credential_bytes_failure;
    int host_evidence_failure;
    int host_evidence_bad;
    int candidate_failure;
    int candidate_reenter;
    int candidate_reentry_rejected;
    unsigned int host_delay_millis;
};

static int validate_environment(void *context, void *environment) {
    struct fixture *fixture = context;
    return fixture != NULL && environment == fixture->environment ? 0 : -1;
}

static int host_key(void *context, void *environment, int classification,
    const char *key_type, const char *fingerprint,
    const unsigned char base_digest[32], const unsigned char replacement_digest[32]) {
    struct fixture *fixture = context;
    assert(fixture != NULL && environment == fixture->environment);
    assert(classification == CTOS_SSH_CALLBACK_HOST_KNOWN);
    assert(strcmp(key_type, "ssh-ed25519") == 0);
    assert(strcmp(fingerprint, "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA") == 0);
    assert(base_digest != NULL && replacement_digest != NULL);
    fixture->host_calls++;
    if (fixture->host_delay_millis != 0) {
        struct timespec delay = {
            .tv_sec = (time_t)(fixture->host_delay_millis / 1000U),
            .tv_nsec = (long)(fixture->host_delay_millis % 1000U) * 1000000L
        };
        assert(nanosleep(&delay, NULL) == 0);
    }
    if (fixture->reenter) {
        unsigned char digest[32];
        memset(digest, 7, sizeof(digest));
        fixture->reenter = 0;
        errno = 0;
        int result = ctos_ssh_callback_host_key(fixture->callback, environment,
            CTOS_SSH_CALLBACK_HOST_KNOWN, key_type, fingerprint, digest, digest);
        assert(result != 0 && errno == EALREADY);
        fixture->reentry_rejected = 1;
    }
    return 0;
}

static int host_evidence(void *context, void *environment,
    const struct ctos_ssh_host_evidence *evidence,
    struct ctos_ssh_host_result *result) {
    struct fixture *fixture = context;
    assert(fixture != NULL && environment == fixture->environment);
    assert(evidence != NULL && result != NULL);
    assert(evidence->role > 0 && evidence->hop == 0 && evidence->port == 22);
    assert(strcmp(evidence->connection_id, "callback-test") == 0);
    assert(strcmp(evidence->target_identity, "target:user@example.invalid:22") == 0);
    assert(strcmp(evidence->host, "example.invalid") == 0);
    assert(strcmp(evidence->key_type, "ssh-ed25519") == 0);
    assert(strcmp(evidence->fingerprint,
        "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA") == 0);
    memset(result, 0, sizeof(*result));
    result->role = evidence->role;
    result->hop = evidence->hop;
    result->port = evidence->port;
    result->classification = evidence->classification;
    result->outcome = CTOS_SSH_CALLBACK_HOST_RESULT_APPROVED;
    result->cas_status = evidence->classification == CTOS_SSH_CALLBACK_HOST_NEW
        ? CTOS_SSH_CALLBACK_HOST_CAS_COMMITTED
        : CTOS_SSH_CALLBACK_HOST_CAS_NOT_REQUIRED;
    strcpy(result->connection_id, evidence->connection_id);
    strcpy(result->target_identity, evidence->target_identity);
    strcpy(result->host, evidence->host);
    strcpy(result->key_type, evidence->key_type);
    strcpy(result->fingerprint, evidence->fingerprint);
    if (evidence->classification == CTOS_SSH_CALLBACK_HOST_NEW) {
        memset(result->proposal_binding, 'a', 64);
        result->proposal_binding[64] = '\0';
    }
    memcpy(result->base_digest, evidence->base_digest, sizeof(result->base_digest));
    memcpy(result->replacement_digest, evidence->replacement_digest,
        sizeof(result->replacement_digest));
    fixture->host_evidence_calls++;
    if (fixture->host_evidence_bad) result->replacement_digest[0] ^= 1;
    if (fixture->host_evidence_failure) return EACCES;
    return 0;
}

static int kex_complete(void *context, void *environment, uint64_t cycle,
    const unsigned char session_digest[32]) {
    struct fixture *fixture = context;
    assert(fixture != NULL && environment == fixture->environment);
    assert(cycle > 0 && session_digest != NULL);
    fixture->kex_calls++;
    return 0;
}

static int known_hosts_candidate(void *context, void *environment,
    const struct ctos_ssh_known_hosts_candidate *candidate) {
    struct fixture *fixture = context;
    assert(fixture != NULL && environment == fixture->environment);
    assert(candidate != NULL && candidate->evidence.role > 0);
    assert(candidate->evidence.classification == CTOS_SSH_CALLBACK_HOST_NEW);
    assert(candidate->evidence.hop == 0 && candidate->evidence.port == 22);
    assert(strcmp(candidate->candidate_id, "candidate-1") == 0);
    assert(candidate->replacement_body != NULL && candidate->replacement_body_length > 0);
    if (!fixture->candidate_failure) {
        assert(candidate->replacement_body_length == strlen("known-hosts-body\n")
            && memcmp(candidate->replacement_body, "known-hosts-body\n",
                candidate->replacement_body_length) == 0);
    }
    fixture->candidate_calls++;
    if (fixture->candidate_reenter) {
        unsigned char body[] = "reentrant-body";
        unsigned char digest[32];
        memset(digest, 8, sizeof(digest));
        fixture->candidate_reenter = 0;
        errno = 0;
        assert(ctos_ssh_callback_known_hosts_candidate(fixture->callback,
            environment, "candidate-reentrant", CTOS_SSH_CALLBACK_HOST_NEW,
            "ssh-ed25519",
            "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", digest, digest,
            body, sizeof(body) - 1U) != 0 && errno == EALREADY);
        for (size_t index = 0; index < sizeof(body) - 1U; index++) assert(body[index] == 0);
        fixture->candidate_reentry_rejected = 1;
    }
    if (fixture->candidate_failure) return EACCES;
    return 0;
}

static int known_hosts_candidate_abort(void *context, void *environment) {
    struct fixture *fixture = context;
    assert(fixture != NULL && environment == fixture->environment);
    fixture->candidate_abort_calls++;
    return 0;
}

static int credential(void *context, void *environment, int kind) {
    struct fixture *fixture = context;
    assert(fixture != NULL && environment == fixture->environment);
    assert(kind == CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD);
    fixture->credential_calls++;
    return EACCES;
}

static int credential_bytes(void *context, void *environment, int kind,
    unsigned char *output, size_t capacity, size_t *length) {
    struct fixture *fixture = context;
    const char *value;
    size_t value_length;
    assert(fixture != NULL && environment == fixture->environment);
    assert(output != NULL && capacity > 0 && length != NULL);
    assert(kind == CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD
        || kind == CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY);
    fixture->credential_bytes_calls++;
    value = kind == CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD
        ? "password-bytes" : "private-key-bytes";
    value_length = strlen(value);
    if (value_length > capacity) return EOVERFLOW;
    memcpy(output, value, value_length);
    *length = value_length;
    if (fixture->credential_bytes_failure) return EACCES;
    return 0;
}

static int release(void *context, void *environment) {
    struct fixture *fixture = context;
    assert(fixture != NULL && environment == fixture->environment);
    fixture->release_calls++;
    if (fixture->release_failure) {
        fixture->release_failure = 0;
        return -1;
    }
    return 0;
}

static struct ctos_ssh_callback_target target(struct fixture *fixture) {
    return (struct ctos_ssh_callback_target){
        .context = fixture,
        .validate_environment = validate_environment,
        .host_key = host_key,
        .host_evidence = host_evidence,
        .known_hosts_candidate = known_hosts_candidate,
        .known_hosts_candidate_abort = known_hosts_candidate_abort,
        .kex_complete = kex_complete,
        .credential = credential,
        .release = release,
        .credential_bytes = credential_bytes
    };
}

static ctos_ssh_role_token role_open(uint64_t deadline) {
    unsigned char nonce[32], connection[32];
    memset(nonce, 1, sizeof(nonce));
    memset(connection, 2, sizeof(connection));
    struct ctos_ssh_role_request request = {
        .program = CTOS_SSH_ROLE_CLIENT,
        .owner_pid = getpid(),
        .owner_uid = getuid(),
        .hop = 0,
        .port = 22,
        .deadline = deadline,
        .connection_id = "callback-test",
        .target_identity = "target:user@example.invalid:22",
        .host = "example.invalid",
        .public_view_id = "view-callback",
        .role_nonce = nonce,
        .connection_binding = connection
    };
    ctos_ssh_role_token role = 0;
    assert(ctos_ssh_role_bootstrap_open(&request, &role) == 0 && role != 0);
    return role;
}

static ctos_ssh_callback_token binding(struct fixture *fixture,
    ctos_ssh_role_token role, uint64_t deadline) {
    fixture->environment = fixture;
    ctos_ssh_callback_token callback = 0;
    struct ctos_ssh_callback_target callback_target = target(fixture);
    assert(ctos_ssh_callback_open(&callback_target, role, (int)getuid(), deadline,
        fixture->environment, &callback) == 0 && callback != 0);
    fixture->callback = callback;
    return callback;
}

static ctos_ssh_callback_token binding_without_bytes(struct fixture *fixture,
    ctos_ssh_role_token role, uint64_t deadline) {
    fixture->environment = fixture;
    ctos_ssh_callback_token callback = 0;
    struct ctos_ssh_callback_target callback_target = target(fixture);
    callback_target.credential_bytes = NULL;
    assert(ctos_ssh_callback_open(&callback_target, role, (int)getuid(), deadline,
        fixture->environment, &callback) == 0 && callback != 0);
    fixture->callback = callback;
    return callback;
}

struct cross_thread_args {
    struct fixture *fixture;
    unsigned char digest[32];
    int result;
    int error;
};

struct close_thread_args {
    struct fixture *fixture;
    int result;
    int error;
};

struct cancel_thread_args {
    ctos_ssh_callback_token callback;
    int result;
    int error;
};

static void *cross_thread_callback(void *argument) {
    struct cross_thread_args *args = argument;
    errno = 0;
    args->result = ctos_ssh_callback_host_key(args->fixture->callback,
        args->fixture->environment, CTOS_SSH_CALLBACK_HOST_KNOWN, "ssh-ed25519",
        "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", args->digest, args->digest);
    args->error = errno;
    return NULL;
}

static void *cross_thread_close(void *argument) {
    struct close_thread_args *args = argument;
    errno = 0;
    args->result = ctos_ssh_callback_close(args->fixture->callback,
        args->fixture->environment);
    args->error = errno;
    return NULL;
}

static void *cancel_runner(void *argument) {
    struct cancel_thread_args *request = argument;
    usleep(10000);
    errno = 0;
    request->result = ctos_ssh_callback_cancel(request->callback);
    request->error = errno;
    return NULL;
}

static void test_thread_and_reentry(void) {
    struct fixture fixture = {0};
    uint64_t deadline = deadline_after(3000);
    ctos_ssh_role_token role = role_open(deadline);
    fixture.environment = &fixture;
    struct ctos_ssh_callback_target callback_target = target(&fixture);
    int wrong_environment = 0;
    ctos_ssh_callback_token rejected_callback = 0;
    errno = 0;
    assert(ctos_ssh_callback_open(&callback_target, role, (int)getuid(), deadline,
        &wrong_environment, &rejected_callback) != 0 && errno == EPERM);
    assert(rejected_callback == 0);
    ctos_ssh_callback_token callback = binding(&fixture, role, deadline);
    unsigned char digest[32]; memset(digest, 3, sizeof(digest));
    errno = 0;
    assert(ctos_ssh_callback_host_key(callback, &wrong_environment,
        CTOS_SSH_CALLBACK_HOST_KNOWN, "ssh-ed25519",
        "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", digest, digest) != 0);
    assert(errno == EPERM && fixture.host_calls == 0);
    assert(ctos_ssh_callback_host_key(callback, fixture.environment,
        CTOS_SSH_CALLBACK_HOST_KNOWN, "ssh-ed25519",
        "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", digest, digest) == 0);
    assert(fixture.host_calls == 1);

    struct cross_thread_args args = {.fixture = &fixture};
    memset(args.digest, 4, sizeof(args.digest));
    pthread_t thread;
    assert(pthread_create(&thread, NULL, cross_thread_callback, &args) == 0);
    assert(pthread_join(thread, NULL) == 0);
    assert(args.result != 0 && args.error == EPERM);

    struct close_thread_args close_args = {.fixture = &fixture};
    assert(pthread_create(&thread, NULL, cross_thread_close, &close_args) == 0);
    assert(pthread_join(thread, NULL) == 0);
    assert(close_args.result != 0 && close_args.error == EPERM);

    fixture.reenter = 1;
    errno = 0;
    assert(ctos_ssh_callback_host_key(callback, fixture.environment,
        CTOS_SSH_CALLBACK_HOST_KNOWN, "ssh-ed25519",
        "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", digest, digest) != 0
        && errno == ECANCELED);
    assert(fixture.reentry_rejected == 1 && fixture.host_calls == 2);
    assert(ctos_ssh_callback_close(callback, fixture.environment) == 0);
    assert(fixture.release_calls == 1);
    errno = 0;
    assert(ctos_ssh_callback_host_key(callback, fixture.environment,
        CTOS_SSH_CALLBACK_HOST_KNOWN, "ssh-ed25519",
        "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", digest, digest) != 0);
    assert(errno == ENOENT);
    assert(ctos_ssh_role_bootstrap_close(role) == 0);
    cases++;
}

static void test_cancel_deadline_and_cleanup_failure(void) {
    unsigned char digest[32]; memset(digest, 5, sizeof(digest));
    struct fixture cancelled = {0};
    uint64_t cancel_deadline = deadline_after(3000);
    ctos_ssh_role_token cancel_role = role_open(cancel_deadline);
    ctos_ssh_callback_token cancel_callback = binding(&cancelled, cancel_role, cancel_deadline);
    assert(ctos_ssh_callback_cancel(cancel_callback) == 0);
    errno = 0;
    assert(ctos_ssh_callback_host_key(cancel_callback, cancelled.environment,
        CTOS_SSH_CALLBACK_HOST_KNOWN, "ssh-ed25519",
        "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", digest, digest) != 0);
    assert(errno == ECANCELED);
    assert(ctos_ssh_callback_close(cancel_callback, cancelled.environment) == 0);
    assert(ctos_ssh_role_bootstrap_close(cancel_role) == 0);

    struct fixture expired = {0};
    uint64_t expired_deadline = deadline_after(50);
    ctos_ssh_role_token expired_role = role_open(expired_deadline);
    ctos_ssh_callback_token expired_callback = binding(&expired, expired_role, expired_deadline);
    usleep(100000);
    errno = 0;
    assert(ctos_ssh_callback_host_key(expired_callback, expired.environment,
        CTOS_SSH_CALLBACK_HOST_KNOWN, "ssh-ed25519",
        "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", digest, digest) != 0);
    assert(errno == ETIMEDOUT || errno == ECANCELED);
    assert(ctos_ssh_callback_close(expired_callback, expired.environment) == 0);
    errno = 0;
    assert(ctos_ssh_role_bootstrap_close(expired_role) != 0 && errno == ENOENT);

    struct fixture poisoned = {.release_failure = 1};
    uint64_t poison_deadline = deadline_after(3000);
    ctos_ssh_role_token poison_role = role_open(poison_deadline);
    ctos_ssh_callback_token poison_callback = binding(&poisoned, poison_role, poison_deadline);
    errno = 0;
    assert(ctos_ssh_callback_close(poison_callback, poisoned.environment) != 0 && errno == EIO);
    assert(poisoned.release_calls == 1);
    errno = 0;
    assert(ctos_ssh_callback_host_key(poison_callback, poisoned.environment,
        CTOS_SSH_CALLBACK_HOST_KNOWN, "ssh-ed25519",
        "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", digest, digest) != 0);
    assert(errno == ECANCELED);
    assert(ctos_ssh_callback_close(poison_callback, poisoned.environment) == 0);
    assert(poisoned.release_calls == 2);
    assert(ctos_ssh_role_bootstrap_close(poison_role) == 0);
    cases += 3;

    struct fixture deadline = {.host_delay_millis = 80};
    uint64_t late_deadline = deadline_after(30);
    ctos_ssh_role_token late_role = role_open(late_deadline);
    ctos_ssh_callback_token late_callback = binding(&deadline, late_role, late_deadline);
    errno = 0;
    assert(ctos_ssh_callback_host_key(late_callback, deadline.environment,
        CTOS_SSH_CALLBACK_HOST_KNOWN, "ssh-ed25519",
        "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", digest, digest) != 0);
    assert(errno == ETIMEDOUT);
    assert(ctos_ssh_callback_close(late_callback, deadline.environment) == 0);
    errno = 0;
    assert(ctos_ssh_role_bootstrap_close(late_role) != 0 && errno == ENOENT);

    struct fixture concurrent = {.host_delay_millis = 80};
    uint64_t concurrent_deadline = deadline_after(3000);
    ctos_ssh_role_token concurrent_role = role_open(concurrent_deadline);
    ctos_ssh_callback_token concurrent_callback = binding(&concurrent,
        concurrent_role, concurrent_deadline);
    /* The callback must remain owned by the event-loop thread. A helper that
     * only requests cancellation can race it without invoking the target. */
    struct cancel_thread_args cancel_request = {.callback = concurrent_callback};
    pthread_t cancel_worker;
    assert(pthread_create(&cancel_worker, NULL, cancel_runner, &cancel_request) == 0);
    errno = 0;
    assert(ctos_ssh_callback_host_key(concurrent_callback, concurrent.environment,
        CTOS_SSH_CALLBACK_HOST_KNOWN, "ssh-ed25519",
        "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", digest, digest) != 0);
    assert(errno == ECANCELED);
    assert(pthread_join(cancel_worker, NULL) == 0);
    assert(cancel_request.result == 0 && cancel_request.error == 0);
    assert(ctos_ssh_callback_close(concurrent_callback, concurrent.environment) == 0);
    assert(ctos_ssh_role_bootstrap_close(concurrent_role) == 0);
    cases += 2;
}

static void test_credential_bytes(void) {
    struct fixture fixture = {0};
    uint64_t deadline = deadline_after(3000);
    ctos_ssh_role_token role = role_open(deadline);
    ctos_ssh_callback_token callback = binding(&fixture, role, deadline);
    unsigned char output[64];
    size_t length = 99;

    memset(output, 0xa5, sizeof(output));
    errno = 0;
    assert(ctos_ssh_callback_credential_bytes(callback, fixture.environment,
        99, output, sizeof(output), &length) != 0);
    assert(errno == EINVAL && length == 0);
    for (size_t index = 0; index < sizeof(output); index++) assert(output[index] == 0);

    memset(output, 0xa5, sizeof(output));
    length = 99;
    assert(ctos_ssh_callback_credential_bytes(callback, fixture.environment,
        CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD, output, sizeof(output), &length) == 0);
    assert(length == strlen("password-bytes"));
    assert(memcmp(output, "password-bytes", length) == 0);

    memset(output, 0xa5, sizeof(output));
    length = 99;
    assert(ctos_ssh_callback_credential_bytes(callback, fixture.environment,
        CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY, output, sizeof(output), &length) == 0);
    assert(length == strlen("private-key-bytes"));
    assert(memcmp(output, "private-key-bytes", length) == 0);
    assert(fixture.credential_bytes_calls == 2);
    assert(ctos_ssh_callback_close(callback, fixture.environment) == 0);
    assert(ctos_ssh_role_bootstrap_close(role) == 0);

    struct fixture failed = {.credential_bytes_failure = 1};
    deadline = deadline_after(3000);
    role = role_open(deadline);
    callback = binding(&failed, role, deadline);
    memset(output, 0xa5, sizeof(output));
    length = 99;
    errno = 0;
    assert(ctos_ssh_callback_credential_bytes(callback, failed.environment,
        CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD, output, sizeof(output), &length) != 0);
    assert(errno == EACCES && length == 0);
    for (size_t index = 0; index < sizeof(output); index++) assert(output[index] == 0);
    assert(ctos_ssh_callback_close(callback, failed.environment) == 0);
    assert(ctos_ssh_role_bootstrap_close(role) == 0);

    struct fixture absent = {0};
    deadline = deadline_after(3000);
    role = role_open(deadline);
    callback = binding_without_bytes(&absent, role, deadline);
    memset(output, 0xa5, sizeof(output));
    length = 99;
    errno = 0;
    assert(ctos_ssh_callback_credential_bytes(callback, absent.environment,
        CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD, output, sizeof(output), &length) != 0);
    assert(errno == ENOSYS && length == 0);
    for (size_t index = 0; index < sizeof(output); index++) assert(output[index] == 0);
    assert(ctos_ssh_callback_close(callback, absent.environment) == 0);
    assert(ctos_ssh_role_bootstrap_close(role) == 0);

    struct fixture undersized = {0};
    deadline = deadline_after(3000);
    role = role_open(deadline);
    callback = binding(&undersized, role, deadline);
    unsigned char short_output[4];
    memset(short_output, 0xa5, sizeof(short_output));
    length = 99;
    errno = 0;
    assert(ctos_ssh_callback_credential_bytes(callback, undersized.environment,
        CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD, short_output, sizeof(short_output), &length) != 0);
    assert(errno == EOVERFLOW && length == 0);
    for (size_t index = 0; index < sizeof(short_output); index++) assert(short_output[index] == 0);
    assert(ctos_ssh_callback_close(callback, undersized.environment) == 0);
    assert(ctos_ssh_role_bootstrap_close(role) == 0);
    cases += 4;
    cases++;
}

static void test_host_evidence_contract(void) {
    unsigned char digest[32];
    memset(digest, 5, sizeof(digest));
    struct fixture known = {0};
    uint64_t deadline = deadline_after(3000);
    ctos_ssh_role_token role = role_open(deadline);
    ctos_ssh_callback_token callback = binding(&known, role, deadline);
    struct ctos_ssh_host_result result;
    errno = 0;
    assert(ctos_ssh_callback_host_evidence(callback, known.environment,
        CTOS_SSH_CALLBACK_HOST_KNOWN, "ssh-ed25519",
        "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", digest, digest,
        &result) == 0);
    assert(result.outcome == CTOS_SSH_CALLBACK_HOST_RESULT_APPROVED);
    assert(result.cas_status == CTOS_SSH_CALLBACK_HOST_CAS_NOT_REQUIRED);
    assert(result.proposal_binding[0] == '\0');
    assert(memcmp(result.base_digest, digest, sizeof(digest)) == 0);
    assert(known.host_evidence_calls == 1);
    assert(ctos_ssh_callback_host_evidence(callback, known.environment,
        CTOS_SSH_CALLBACK_HOST_NEW, "ssh-ed25519",
        "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", digest, digest,
        &result) == 0);
    assert(result.cas_status == CTOS_SSH_CALLBACK_HOST_CAS_COMMITTED);
    assert(strlen(result.proposal_binding) == 64);
    assert(ctos_ssh_callback_close(callback, known.environment) == 0);
    assert(ctos_ssh_role_bootstrap_close(role) == 0);
    cases += 2;

    struct fixture malformed = {.host_evidence_bad = 1};
    deadline = deadline_after(3000);
    role = role_open(deadline);
    callback = binding(&malformed, role, deadline);
    memset(&result, 0xa5, sizeof(result));
    errno = 0;
    assert(ctos_ssh_callback_host_evidence(callback, malformed.environment,
        CTOS_SSH_CALLBACK_HOST_KNOWN, "ssh-ed25519",
        "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", digest, digest,
        &result) != 0 && errno == EINVAL);
    for (size_t index = 0; index < sizeof(result); index++)
        assert(((unsigned char *)&result)[index] == 0);
    assert(ctos_ssh_callback_close(callback, malformed.environment) == 0);
    assert(ctos_ssh_role_bootstrap_close(role) == 0);

    struct fixture failed = {.host_evidence_failure = 1};
    deadline = deadline_after(3000);
    role = role_open(deadline);
    callback = binding(&failed, role, deadline);
    errno = 0;
    assert(ctos_ssh_callback_host_evidence(callback, failed.environment,
        CTOS_SSH_CALLBACK_HOST_KNOWN, "ssh-ed25519",
        "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", digest, digest,
        &result) != 0 && errno == EACCES);
    assert(ctos_ssh_callback_close(callback, failed.environment) == 0);
    assert(ctos_ssh_role_bootstrap_close(role) == 0);
    cases += 2;
}

static void test_known_hosts_candidate_handoff(void) {
    unsigned char digest[32];
    memset(digest, 7, sizeof(digest));
    const char *fingerprint = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    struct fixture fixture = {.candidate_reenter = 1};
    uint64_t deadline = deadline_after(3000);
    ctos_ssh_role_token role = role_open(deadline);
    ctos_ssh_callback_token callback = binding(&fixture, role, deadline);
    unsigned char body[] = "known-hosts-body\n";
    errno = 0;
    assert(ctos_ssh_callback_known_hosts_candidate(callback, fixture.environment,
        "candidate-1", CTOS_SSH_CALLBACK_HOST_NEW, "ssh-ed25519", fingerprint,
        digest, digest, body, sizeof(body) - 1U) != 0 && errno == ECANCELED);
    for (size_t index = 0; index < sizeof(body) - 1U; index++) assert(body[index] == 0);
    assert(fixture.candidate_calls == 1 && fixture.candidate_reentry_rejected == 1
        && fixture.candidate_abort_calls == 1);

    unsigned char duplicate[] = "duplicate-body";
    errno = 0;
    assert(ctos_ssh_callback_known_hosts_candidate(callback, fixture.environment,
        "candidate-1", CTOS_SSH_CALLBACK_HOST_NEW, "ssh-ed25519", fingerprint,
        digest, digest, duplicate, sizeof(duplicate) - 1U) != 0 && errno == ECANCELED);
    for (size_t index = 0; index < sizeof(duplicate) - 1U; index++) assert(duplicate[index] == 0);
    assert(ctos_ssh_callback_close(callback, fixture.environment) == 0);
    assert(ctos_ssh_role_bootstrap_close(role) == 0);

    struct fixture failed = {.candidate_failure = 1};
    deadline = deadline_after(3000);
    role = role_open(deadline);
    callback = binding(&failed, role, deadline);
    unsigned char rejected[] = "rejected-body";
    errno = 0;
    assert(ctos_ssh_callback_known_hosts_candidate(callback, failed.environment,
        "candidate-1", CTOS_SSH_CALLBACK_HOST_NEW, "ssh-ed25519", fingerprint,
        digest, digest, rejected, sizeof(rejected) - 1U) != 0 && errno == EACCES);
    for (size_t index = 0; index < sizeof(rejected) - 1U; index++) assert(rejected[index] == 0);
    assert(failed.candidate_abort_calls == 1);
    assert(ctos_ssh_callback_close(callback, failed.environment) == 0);
    assert(ctos_ssh_role_bootstrap_close(role) == 0);

    struct fixture invalid = {0};
    deadline = deadline_after(3000);
    role = role_open(deadline);
    callback = binding(&invalid, role, deadline);
    unsigned char empty[1] = {0xa5};
    errno = 0;
    assert(ctos_ssh_callback_known_hosts_candidate(callback, invalid.environment,
        "candidate-1", CTOS_SSH_CALLBACK_HOST_NEW, "ssh-ed25519", fingerprint,
        digest, digest, empty, 0) != 0 && errno == EINVAL);
    assert(empty[0] == 0xa5); /* no body was supplied to wipe */
    unsigned char oversized[CTOS_SSH_CALLBACK_MAX_FILE_BYTES];
    memset(oversized, 0xa5, sizeof(oversized));
    errno = 0;
    assert(ctos_ssh_callback_known_hosts_candidate(callback, invalid.environment,
        "candidate-1", CTOS_SSH_CALLBACK_HOST_NEW, "ssh-ed25519", fingerprint,
        digest, digest, oversized, sizeof(oversized) + 1U) != 0 && errno == EINVAL);
    for (size_t index = 0; index < sizeof(oversized); index++) assert(oversized[index] == 0);
    assert(ctos_ssh_callback_close(callback, invalid.environment) == 0);
    assert(ctos_ssh_role_bootstrap_close(role) == 0);
    cases += 5;
}

int main(void) {
    test_thread_and_reentry();
    test_cancel_deadline_and_cleanup_failure();
    test_credential_bytes();
    test_host_evidence_contract();
    test_known_hosts_candidate_handoff();
    printf("SSH_CALLBACK_HOST phase=thread_reentry state=pass errno=0 value=1\n");
    printf("SSH_CALLBACK_HOST phase=cancel_deadline_cleanup state=pass errno=0 value=5\n");
    printf("SSH_CALLBACK_HOST phase=credential_bytes state=pass errno=0 value=5\n");
    printf("SSH_CALLBACK_HOST phase=host_evidence state=pass errno=0 value=4\n");
    printf("SSH_CALLBACK_HOST phase=known_hosts_candidate state=pass errno=0 value=5\n");
    printf("SSH_CALLBACK_HOST phase=gate state=pass errno=0 value=%u\n", cases);
    return 0;
}

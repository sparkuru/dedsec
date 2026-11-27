#define _GNU_SOURCE

#include "ssh_view_handle.h"

#include <assert.h>
#include <errno.h>
#include <pthread.h>
#include <sched.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#ifdef CTOS_SSH_VIEW_FAILURE_TEST
#include <sys/mman.h>

static int fail_unmap;
int __real_munmap(void *, size_t);
int __wrap_munmap(void *address, size_t length) {
    if (fail_unmap) { fail_unmap = 0; errno = EIO; return -1; }
    return __real_munmap(address, length);
}
#endif

struct wire {
    unsigned char bytes[4096];
    size_t length;
};

static void put(struct wire *wire, const void *bytes, size_t length) {
    assert(wire != NULL && length <= sizeof(wire->bytes) - wire->length);
    memcpy(wire->bytes + wire->length, bytes, length);
    wire->length += length;
}

static void u32(struct wire *wire, uint32_t value) {
    unsigned char bytes[] = {
        (unsigned char)(value >> 24), (unsigned char)(value >> 16),
        (unsigned char)(value >> 8), (unsigned char)value
    };
    put(wire, bytes, sizeof(bytes));
}

static void u64(struct wire *wire, uint64_t value) {
    u32(wire, (uint32_t)(value >> 32));
    u32(wire, (uint32_t)value);
}

static void text(struct wire *wire, const char *value) {
    size_t length = strlen(value);
    assert(length <= UINT32_MAX);
    u32(wire, (uint32_t)length);
    put(wire, value, length);
}

static void object(struct wire *wire, unsigned int type, const char *id,
    const char *path, const char *identity, const char *reference,
    const unsigned char *body, size_t body_length) {
    unsigned char kind = (unsigned char)type;

    put(wire, &kind, sizeof(kind));
    text(wire, id);
    text(wire, path);
    text(wire, identity);
    u32(wire, (uint32_t)body_length);
    if (reference == NULL) u32(wire, 0);
    else { u32(wire, 1); text(wire, reference); }
    u32(wire, (uint32_t)body_length);
    if (body_length != 0) put(wire, body, body_length);
}

static struct wire make_wire(uint64_t deadline) {
    struct wire wire = {0};
    unsigned char uuid[16] = {1};
    unsigned char binding[32] = {2};
    static const unsigned char hosts[] = "known host";

    u32(&wire, 0x43535056U);
    u32(&wire, 1);
    put(&wire, uuid, sizeof(uuid));
    u64(&wire, 73);
    u64(&wire, 7);
    u64(&wire, deadline);
    text(&wire, "handle-request");
    text(&wire, "handle-connection");
    text(&wire, "ssh/config");
    put(&wire, binding, sizeof(binding));
    u32(&wire, 2);
    object(&wire, CTOS_SSH_CONFIG, "root", "ssh/config", "config",
        "ssh/known_hosts", NULL, 0);
    object(&wire, CTOS_SSH_KNOWN_HOSTS, "trust", "ssh/known_hosts", "trust",
        NULL, hosts, sizeof(hosts) - 1U);
    return wire;
}

static uint64_t deadline_after(unsigned int milliseconds) {
    struct timespec now;

    assert(clock_gettime(CLOCK_BOOTTIME, &now) == 0);
    return (uint64_t)now.tv_sec * 1000U
        + (uint64_t)now.tv_nsec / 1000000U + milliseconds;
}

struct claim_attempt {
    struct ctos_ssh_view_borrow *borrow;
    struct ctos_ssh_view_borrow_claim *claim;
    pthread_barrier_t *start, *observed, *release;
    int result, error, release_result;
};

static void *claim_racer(void *opaque) {
    struct claim_attempt *attempt = opaque;
    (void)pthread_barrier_wait(attempt->start);
    attempt->result = ctos_ssh_view_borrow_claim(attempt->borrow, attempt, &attempt->claim);
    attempt->error = errno;
    (void)pthread_barrier_wait(attempt->observed);
    (void)pthread_barrier_wait(attempt->release);
    if (attempt->result == 0)
        attempt->release_result = ctos_ssh_view_borrow_claim_release(attempt->claim, attempt);
    return NULL;
}

struct release_attempt {
    struct ctos_ssh_view_borrow_claim *claim;
    const void *owner;
    int result, error, live_result, live_error;
};

static void *wrong_thread_release(void *opaque) {
    struct release_attempt *attempt = opaque;
    attempt->result = ctos_ssh_view_borrow_claim_release(attempt->claim, attempt->owner);
    attempt->error = errno;
    attempt->live_result = ctos_ssh_view_borrow_claim_release_live(attempt->claim, attempt->owner);
    attempt->live_error = errno;
    return NULL;
}

static void claim_lifecycle(const struct wire *wire) {
    ctos_ssh_view_token token = 0;
    struct ctos_ssh_view_borrow *borrow = NULL, *other = NULL;
    struct ctos_ssh_view_borrow_claim *first = NULL, *second = NULL;
    int owner_one = 1, owner_two = 2;
    pthread_t thread;

    assert(ctos_ssh_view_handle_create(wire->bytes, wire->length, &token) == 0);
    assert(ctos_ssh_view_handle_borrow(token, &borrow) == 0);
    assert(ctos_ssh_view_borrow_claim(borrow, &owner_one, &first) == 0 && first != NULL);
    assert(ctos_ssh_view_handle_borrow(token, &other) == 0);
    assert(ctos_ssh_view_borrow_claim(other, &owner_two, &second) == 0 && second != NULL);
    assert(ctos_ssh_view_borrow_claim_release(second, &owner_two) == 0);
    second = NULL;
    assert(ctos_ssh_view_borrow_release(borrow) != 0 && errno == EBUSY);
    assert(ctos_ssh_view_borrow_claim(borrow, &owner_two, &second) != 0 && errno == EBUSY && second == NULL);
    assert(ctos_ssh_view_borrow_claim_release(first, &owner_two) != 0 && errno == EBUSY);
    struct release_attempt wrong = {.claim = first, .owner = &owner_one};
    assert(pthread_create(&thread, NULL, wrong_thread_release, &wrong) == 0);
    assert(pthread_join(thread, NULL) == 0);
    assert(wrong.result != 0 && wrong.error == EPERM);
    assert(wrong.live_result != 0 && wrong.live_error == EPERM);
    pid_t child = fork();
    assert(child >= 0);
    if (child == 0) {
        int status = ctos_ssh_view_borrow_claim_release(first, &owner_one);
        _exit(status != 0 && errno == EPERM ? 0 : 1);
    }
    int status = 0;
    assert(waitpid(child, &status, 0) == child);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
    assert(ctos_ssh_view_borrow_live(borrow) == 0);
    ctos_ssh_view_borrow_claim_abort(first);
    assert(ctos_ssh_view_borrow_claim(borrow, &owner_two, &second) == 0 && second != NULL);
    assert(ctos_ssh_view_borrow_release(borrow) != 0 && errno == EBUSY);
    assert(ctos_ssh_view_handle_destroy(token) == 0);
    assert(ctos_ssh_view_borrow_live(borrow) != 0 && errno == EACCES);
    assert(ctos_ssh_view_borrow_release(borrow) != 0 && errno == EBUSY);
    assert(ctos_ssh_view_borrow_claim_release(second, &owner_two) == 0);
    assert(ctos_ssh_view_borrow_live(borrow) != 0 && errno == EACCES);
    assert(ctos_ssh_view_handle_destroy(token) == 0);
}

static void shared_view_borrow_lifecycle(const struct wire *wire) {
    enum { ROOT, MIDDLE, TARGET, HOPS };
    for (int revoke = 0; revoke <= 1; revoke++) {
        ctos_ssh_view_token token = 0;
        struct ctos_ssh_view_borrow *borrows[HOPS] = {0}, *extra = NULL;
        struct ctos_ssh_view_borrow_claim *claims[HOPS] = {0}, *other = NULL;
        const struct ctos_ssh_view *shared = NULL;
        struct ctos_ssh_view_summary summary;
        int owners[HOPS] = {1, 2, 3}, wrong_owner = 4;

        assert(ctos_ssh_view_handle_create(wire->bytes, wire->length, &token) == 0);
        for (int hop = ROOT; hop < HOPS; hop++) {
            assert(ctos_ssh_view_handle_borrow(token, &borrows[hop]) == 0);
            for (int previous = ROOT; previous < hop; previous++)
                assert(borrows[hop] != borrows[previous]);
            assert(ctos_ssh_view_borrow_claim(borrows[hop], &owners[hop], &claims[hop]) == 0);
            const struct ctos_ssh_view *view = ctos_ssh_view_borrow_view(borrows[hop]);
            assert(view != NULL);
            if (shared == NULL) shared = view;
            else assert(view == shared);
            assert(ctos_ssh_view_borrow_claim(borrows[hop], &wrong_owner, &other) != 0
                && errno == EBUSY && other == NULL);
            assert(ctos_ssh_view_borrow_release(borrows[hop]) != 0 && errno == EBUSY);
            assert(ctos_ssh_view_borrow_claim_release_live(claims[hop], &wrong_owner) != 0
                && errno == EBUSY);
            assert(ctos_ssh_view_borrow_live(borrows[hop]) == 0);
        }
        assert(ctos_ssh_view_borrow_claim_release_live(claims[TARGET], &owners[TARGET]) == 0);
        claims[TARGET] = NULL;
        borrows[TARGET] = NULL;
        assert(ctos_ssh_view_borrow_live(borrows[MIDDLE]) == 0);
        assert(ctos_ssh_view_borrow_live(borrows[ROOT]) == 0);
        assert(ctos_ssh_view_handle_summary(token, &summary) == 0);

        if (revoke) {
            assert(ctos_ssh_view_handle_destroy(token) == 0);
            assert(ctos_ssh_view_handle_borrow(token, &extra) != 0 && errno == EACCES && extra == NULL);
            for (int hop = ROOT; hop <= MIDDLE; hop++) {
                assert(ctos_ssh_view_borrow_live(borrows[hop]) != 0 && errno == EACCES);
                assert(ctos_ssh_view_borrow_claim_release_live(claims[hop], &owners[hop]) != 0
                    && errno == EACCES);
                assert(ctos_ssh_view_borrow_release(borrows[hop]) != 0 && errno == EBUSY);
            }
            assert(ctos_ssh_view_borrow_claim_release(claims[MIDDLE], &owners[MIDDLE]) == 0);
            claims[MIDDLE] = NULL;
            borrows[MIDDLE] = NULL;
            assert(ctos_ssh_view_borrow_live(borrows[ROOT]) != 0 && errno == EACCES);
            assert(ctos_ssh_view_borrow_claim_release(claims[ROOT], &owners[ROOT]) == 0);
        } else {
            assert(ctos_ssh_view_borrow_claim_release_live(claims[MIDDLE], &owners[MIDDLE]) == 0);
            claims[MIDDLE] = NULL;
            borrows[MIDDLE] = NULL;
            assert(ctos_ssh_view_borrow_live(borrows[ROOT]) == 0);
            assert(ctos_ssh_view_handle_summary(token, &summary) == 0);
            assert(ctos_ssh_view_borrow_claim_release_live(claims[ROOT], &owners[ROOT]) == 0);
            assert(ctos_ssh_view_handle_summary(token, &summary) == 0);
        }
        claims[ROOT] = NULL;
        borrows[ROOT] = NULL;
        assert(ctos_ssh_view_handle_destroy(token) == 0);
        assert(ctos_ssh_view_handle_summary(token, &summary) != 0);
    }
}

struct revoke_retire_race {
    ctos_ssh_view_token token;
    pthread_barrier_t start;
    int revoke_result;
};

static void *race_revoke_before_retire(void *opaque) {
    struct revoke_retire_race *race = opaque;
    (void)pthread_barrier_wait(&race->start);
    race->revoke_result = ctos_ssh_view_handle_destroy(race->token);
    return NULL;
}

static void live_retirement_ordering(const struct wire *wire) {
    for (unsigned int mode = 0; mode < 66U; mode++) {
        struct revoke_retire_race race = {0};
        struct ctos_ssh_view_borrow *borrow = NULL;
        struct ctos_ssh_view_borrow_claim *claim = NULL;
        int owner = 1, other = 2, result, error;
        pthread_t thread;
        assert(ctos_ssh_view_handle_create(wire->bytes, wire->length, &race.token) == 0);
        assert(ctos_ssh_view_handle_borrow(race.token, &borrow) == 0);
        assert(ctos_ssh_view_borrow_claim(borrow, &owner, &claim) == 0);
        assert(ctos_ssh_view_borrow_claim_release_live(claim, &other) != 0 && errno == EBUSY);
        if (mode == 0) {
            /* Revocation completes after a separate live preflight but before retirement. */
            assert(ctos_ssh_view_borrow_live(borrow) == 0);
            assert(ctos_ssh_view_handle_destroy(race.token) == 0);
        } else if (mode >= 2) {
            assert(pthread_barrier_init(&race.start, NULL, 2) == 0);
            assert(pthread_create(&thread, NULL, race_revoke_before_retire, &race) == 0);
            (void)pthread_barrier_wait(&race.start);
        }
        result = ctos_ssh_view_borrow_claim_release_live(claim, &owner);
        error = errno;
        if (mode >= 2) {
            assert(pthread_join(thread, NULL) == 0 && race.revoke_result == 0);
            assert(pthread_barrier_destroy(&race.start) == 0);
        }
        if (mode == 0) assert(result != 0 && error == EACCES);
        if (mode == 1) assert(result == 0);
        if (result != 0) {
            assert(error == EACCES);
            assert(ctos_ssh_view_borrow_release(borrow) != 0 && errno == EBUSY);
            /* Cleanup is allowed after revocation and owns the same claim. */
            assert(ctos_ssh_view_borrow_claim_release(claim, &owner) == 0);
        }
        assert(ctos_ssh_view_handle_destroy(race.token) == 0);
    }
}

static void expired_live_retirement(void) {
    uint64_t deadline = deadline_after(1000U);
    struct wire wire = make_wire(deadline);
    ctos_ssh_view_token token = 0;
    struct ctos_ssh_view_borrow *borrow = NULL;
    struct ctos_ssh_view_borrow_claim *claim = NULL;
    int owner = 1;
    assert(ctos_ssh_view_handle_create(wire.bytes, wire.length, &token) == 0);
    assert(ctos_ssh_view_handle_borrow(token, &borrow) == 0);
    assert(ctos_ssh_view_borrow_claim(borrow, &owner, &claim) == 0);
    assert(ctos_ssh_view_borrow_live(borrow) == 0);
    for (;;) {
        struct timespec now, pause = {.tv_sec = 0, .tv_nsec = 1000000L};
        assert(clock_gettime(CLOCK_BOOTTIME, &now) == 0);
        if ((uint64_t)now.tv_sec * 1000U + (uint64_t)now.tv_nsec / 1000000U >= deadline) break;
        if (nanosleep(&pause, NULL) != 0) assert(errno == EINTR);
    }
    assert(ctos_ssh_view_borrow_claim_release_live(claim, &owner) != 0 && errno == ETIMEDOUT);
    assert(ctos_ssh_view_borrow_release(borrow) != 0 && errno == EBUSY);
    assert(ctos_ssh_view_borrow_claim_release(claim, &owner) == 0);
    assert(ctos_ssh_view_handle_destroy(token) == 0);
}

static void claim_race(const struct wire *wire) {
    enum { COUNT = 8 };
    ctos_ssh_view_token token = 0;
    struct ctos_ssh_view_borrow *borrow = NULL;
    struct claim_attempt attempts[COUNT] = {0};
    pthread_t threads[COUNT];
    pthread_barrier_t start, observed, release;
    unsigned int winners = 0;

    assert(ctos_ssh_view_handle_create(wire->bytes, wire->length, &token) == 0);
    assert(ctos_ssh_view_handle_borrow(token, &borrow) == 0);
    assert(pthread_barrier_init(&start, NULL, COUNT + 1) == 0);
    assert(pthread_barrier_init(&observed, NULL, COUNT + 1) == 0);
    assert(pthread_barrier_init(&release, NULL, COUNT + 1) == 0);
    for (unsigned int i = 0; i < COUNT; i++) {
        attempts[i] = (struct claim_attempt){.borrow = borrow, .start = &start,
            .observed = &observed, .release = &release};
        assert(pthread_create(&threads[i], NULL, claim_racer, &attempts[i]) == 0);
    }
    (void)pthread_barrier_wait(&start);
    (void)pthread_barrier_wait(&observed);
    for (unsigned int i = 0; i < COUNT; i++) {
        if (attempts[i].result == 0) winners++;
        else assert(attempts[i].error == EBUSY && attempts[i].claim == NULL);
    }
    assert(winners == 1);
    assert(ctos_ssh_view_borrow_release(borrow) != 0 && errno == EBUSY);
    (void)pthread_barrier_wait(&release);
    for (unsigned int i = 0; i < COUNT; i++) {
        assert(pthread_join(threads[i], NULL) == 0);
        if (attempts[i].result == 0) assert(attempts[i].release_result == 0);
    }
    assert(pthread_barrier_destroy(&start) == 0);
    assert(pthread_barrier_destroy(&observed) == 0);
    assert(pthread_barrier_destroy(&release) == 0);
    assert(ctos_ssh_view_handle_destroy(token) == 0);
}

struct claim_release_race {
    struct ctos_ssh_view_borrow *borrow;
    struct ctos_ssh_view_borrow_claim *claim;
    pthread_barrier_t start, observed, finish;
    int claim_result, claim_error, release_result, release_error, owner_release_result;
};

static void *race_claim(void *opaque) {
    struct claim_release_race *race = opaque;
    (void)pthread_barrier_wait(&race->start);
    race->claim_result = ctos_ssh_view_borrow_claim(race->borrow, race, &race->claim);
    race->claim_error = errno;
    (void)pthread_barrier_wait(&race->observed);
    (void)pthread_barrier_wait(&race->finish);
    if (race->claim_result == 0)
        race->owner_release_result = ctos_ssh_view_borrow_claim_release(race->claim, race);
    return NULL;
}

static void *race_release(void *opaque) {
    struct claim_release_race *race = opaque;
    (void)pthread_barrier_wait(&race->start);
    race->release_result = ctos_ssh_view_borrow_release(race->borrow);
    race->release_error = errno;
    (void)pthread_barrier_wait(&race->observed);
    (void)pthread_barrier_wait(&race->finish);
    return NULL;
}

static void claim_release_races(const struct wire *wire) {
    for (unsigned int i = 0; i < 32; i++) {
        ctos_ssh_view_token token = 0;
        struct claim_release_race race = {0};
        pthread_t claim_thread, release_thread;
        assert(ctos_ssh_view_handle_create(wire->bytes, wire->length, &token) == 0);
        assert(ctos_ssh_view_handle_borrow(token, &race.borrow) == 0);
        assert(pthread_barrier_init(&race.start, NULL, 3) == 0);
        assert(pthread_barrier_init(&race.observed, NULL, 3) == 0);
        assert(pthread_barrier_init(&race.finish, NULL, 3) == 0);
        assert(pthread_create(&claim_thread, NULL, race_claim, &race) == 0);
        assert(pthread_create(&release_thread, NULL, race_release, &race) == 0);
        (void)pthread_barrier_wait(&race.start);
        (void)pthread_barrier_wait(&race.observed);
        if (race.claim_result == 0)
            assert(race.claim != NULL && race.release_result != 0 && race.release_error == EBUSY);
        else
            assert(race.claim == NULL && race.claim_error == EACCES && race.release_result == 0);
        (void)pthread_barrier_wait(&race.finish);
        assert(pthread_join(claim_thread, NULL) == 0);
        assert(pthread_join(release_thread, NULL) == 0);
        if (race.claim_result == 0) assert(race.owner_release_result == 0);
        assert(pthread_barrier_destroy(&race.start) == 0);
        assert(pthread_barrier_destroy(&race.observed) == 0);
        assert(pthread_barrier_destroy(&race.finish) == 0);
        assert(ctos_ssh_view_handle_destroy(token) == 0);
    }
}

struct abort_release_race {
    struct ctos_ssh_view_borrow *borrow;
    pthread_barrier_t start;
    int result, error;
};

static void *race_release_after_abort(void *opaque) {
    struct abort_release_race *race = opaque;
    (void)pthread_barrier_wait(&race->start);
    do {
        race->result = ctos_ssh_view_borrow_release(race->borrow);
        race->error = errno;
        if (race->result != 0 && race->error == EBUSY) (void)sched_yield();
    } while (race->result != 0 && race->error == EBUSY);
    return NULL;
}

static void claim_abort_release_races(const struct wire *wire) {
    for (unsigned int i = 0; i < 32; i++) {
        ctos_ssh_view_token token = 0;
        struct ctos_ssh_view_borrow_claim *claim = NULL;
        struct abort_release_race race = {0};
        pthread_t thread;
        assert(ctos_ssh_view_handle_create(wire->bytes, wire->length, &token) == 0);
        assert(ctos_ssh_view_handle_borrow(token, &race.borrow) == 0);
        assert(ctos_ssh_view_borrow_claim(race.borrow, &race, &claim) == 0);
        assert(pthread_barrier_init(&race.start, NULL, 2) == 0);
        assert(pthread_create(&thread, NULL, race_release_after_abort, &race) == 0);
        (void)pthread_barrier_wait(&race.start);
        ctos_ssh_view_borrow_claim_abort(claim);
        assert(pthread_join(thread, NULL) == 0);
        assert(race.result == 0);
        assert(pthread_barrier_destroy(&race.start) == 0);
        assert(ctos_ssh_view_handle_destroy(token) == 0);
    }
}

#ifdef CTOS_SSH_VIEW_FAILURE_TEST
static void retained_destroy_failure(const struct wire *wire) {
    pid_t child = fork();
    assert(child >= 0);
    if (child == 0) {
        ctos_ssh_view_token token = 0;
        struct ctos_ssh_view_borrow *borrow = NULL;
        struct ctos_ssh_view_borrow_claim *claim = NULL, *other = NULL;
        int owner = 0, competitor = 1;
        assert(ctos_ssh_view_handle_create(wire->bytes, wire->length, &token) == 0);
        assert(ctos_ssh_view_handle_borrow(token, &borrow) == 0);
        assert(ctos_ssh_view_borrow_claim(borrow, &owner, &claim) == 0);
        assert(ctos_ssh_view_handle_destroy(token) == 0);
        fail_unmap = 1;
        assert(ctos_ssh_view_borrow_claim_release(claim, &owner) != 0 && errno == EIO);
        assert(ctos_ssh_view_borrow_release(borrow) != 0 && errno == EBUSY);
        assert(ctos_ssh_view_borrow_claim(borrow, &competitor, &other) != 0
            && errno == EBUSY && other == NULL);
        assert(ctos_ssh_view_borrow_claim_release(claim, &owner) != 0 && errno == EIO);
        assert(ctos_ssh_view_borrow_release(borrow) != 0 && errno == EBUSY);
        _exit(0);
    }
    int status = 0;
    assert(waitpid(child, &status, 0) == child);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
}
#endif

int main(void) {
    struct wire wire = make_wire(deadline_after(15000));
    struct ctos_ssh_view_summary summary;
    struct ctos_ssh_view_borrow *borrow = NULL;
    struct ctos_ssh_view_borrow *second = NULL;
    ctos_ssh_view_token token = 0;
    ctos_ssh_view_token replacement = 0;

    assert(ctos_ssh_view_handle_create(wire.bytes, wire.length, &token) == 0);
    assert(token > 0);
    assert(ctos_ssh_view_handle_summary(token, &summary) == 0);
    assert(summary.count == 2 && summary.public_bytes == 10
        && summary.generation == 73 && summary.epoch == 7
        && summary.backing_bytes == wire.length && summary.close_on_exec != 0);
    assert(ctos_ssh_view_handle_borrow(token, &borrow) == 0);
    assert(ctos_ssh_view_borrow_view(borrow) != NULL);
    assert(ctos_ssh_view_borrow_live(borrow) == 0);
    assert(ctos_ssh_view_handle_destroy(token) == 0);
    errno = 0;
    assert(ctos_ssh_view_borrow_live(borrow) != 0 && errno == EACCES);
    errno = 0;
    assert(ctos_ssh_view_handle_borrow(token, &second) != 0 && errno == EACCES);
    assert(ctos_ssh_view_borrow_release(borrow) == 0);
    assert(ctos_ssh_view_handle_destroy(token) == 0);

    assert(ctos_ssh_view_handle_create(wire.bytes, wire.length, &replacement) == 0);
    assert(replacement != token);
    assert(ctos_ssh_view_handle_destroy(token) == 0);
    assert(ctos_ssh_view_handle_summary(replacement, &summary) == 0);
    assert(ctos_ssh_view_handle_destroy(replacement) == 0);
    assert(ctos_ssh_view_handle_summary(replacement, &summary) != 0);
    claim_lifecycle(&wire);
    shared_view_borrow_lifecycle(&wire);
    live_retirement_ordering(&wire);
    expired_live_retirement();
    claim_race(&wire);
    claim_release_races(&wire);
    claim_abort_release_races(&wire);
#ifdef CTOS_SSH_VIEW_FAILURE_TEST
    retained_destroy_failure(&wire);
#endif
    printf("SSH_VIEW_HANDLE_HOST gate=pass borrow=pass revoke=pass stale=pass claim=pass race=pass retire=pass\n");
    return 0;
}

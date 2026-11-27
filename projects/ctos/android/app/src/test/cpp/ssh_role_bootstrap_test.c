#include "ssh_role_bootstrap.h"

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <sched.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static unsigned int checks;
#define CHECK(condition) do { checks++; if (!(condition)) { \
    fprintf(stderr, "SSH_ROLE phase=gate state=error errno=5 value=%u\n", checks); \
    return 2; \
} } while (0)

static uint64_t now_millis(void) {
    struct timespec now;
    CHECK(clock_gettime(CLOCK_BOOTTIME, &now) == 0);
    return (uint64_t)now.tv_sec * 1000U + (uint64_t)now.tv_nsec / 1000000U;
}

static void values(unsigned char *nonce, unsigned char *binding,
    unsigned int nonce_value, unsigned int binding_value) {
    for (unsigned int i = 0; i < 32; i++) {
        nonce[i] = (unsigned char)nonce_value;
        binding[i] = (unsigned char)binding_value;
    }
}

static struct ctos_ssh_role_request request(unsigned int hop,
    const char *connection, const char *view, const unsigned char *nonce,
    const unsigned char *binding, uint64_t deadline) {
    struct ctos_ssh_role_request result = {
        .program = CTOS_SSH_ROLE_CLIENT,
        .owner_pid = getpid(),
        .owner_uid = getuid(),
        .hop = hop,
        .port = 22,
        .deadline = deadline,
        .connection_id = connection,
        .target_identity = "target:user@example.invalid:22",
        .host = "example.invalid",
        .public_view_id = view,
        .role_nonce = nonce,
        .connection_binding = binding
    };
    return result;
}

struct close_race {
    ctos_ssh_role_token token;
    atomic_int done;
    int result;
};

static void *close_during_effect(void *argument) {
    struct close_race *race = argument;
    race->result = ctos_ssh_role_bootstrap_close(race->token);
    atomic_store_explicit(&race->done, 1, memory_order_release);
    return NULL;
}

/* Close reaches the real roles_lock/invalidation path while an effect is
 * held. Admission must stop immediately and close must drain that effect. */
static int close_drains_effect(ctos_ssh_role_token token) {
    uint32_t generation, ignored;
    struct close_race race = {.token = token, .done = ATOMIC_VAR_INIT(0), .result = -1};
    pthread_t thread;
    if (ctos_ssh_effect_snapshot(&generation) != 0 || ctos_ssh_effect_acquire(generation) != 0)
        return -1;
    if (pthread_create(&thread, NULL, close_during_effect, &race) != 0) {
        ctos_ssh_effect_release();
        return -1;
    }
    uint64_t limit = now_millis() + 5000;
    int pending = 0;
    do {
        if (ctos_ssh_effect_snapshot(&ignored) != 0) { pending = 1; break; }
        sched_yield();
    } while (now_millis() < limit);
    int blocked = atomic_load_explicit(&race.done, memory_order_acquire) == 0;
    int rejected = ctos_ssh_effect_acquire(generation) != 0;
    if (!rejected) ctos_ssh_effect_release();
    ctos_ssh_effect_release();
    if (pthread_join(thread, NULL) != 0) return -1;
    if (!pending || !blocked || !rejected || race.result != 0
        || !atomic_load_explicit(&race.done, memory_order_acquire)
        || ctos_ssh_role_bootstrap_active(token) != 0
        || ctos_ssh_effect_acquire(generation) == 0) return -1;
    return ctos_ssh_effect_snapshot(&ignored);
}

static int child_scope(ctos_ssh_role_token parent_token,
    const unsigned char *nonce, const unsigned char *binding) {
    struct ctos_ssh_role_request child = request(3, "child_connection", "child_view",
        nonce, binding, now_millis() + 5000);
    ctos_ssh_role_token token = 0;
    if (ctos_ssh_role_bootstrap_active(parent_token) != 0
        || ctos_ssh_role_bootstrap_open(&child, &token) != 0
        || ctos_ssh_role_bootstrap_active(token) == 0
        || ctos_ssh_role_bootstrap_close(token) != 0) return 1;
    return 0;
}

int main(void) {
    unsigned char nonce[32], binding[32], second_nonce[32], second_binding[32];
    values(nonce, binding, 1, 2);
    values(second_nonce, second_binding, 3, 4);
    uint64_t deadline = now_millis() + 5000;
    struct ctos_ssh_role_request first_request = request(0, "connection_0", "view_0",
        nonce, binding, deadline);
    ctos_ssh_role_token first = 0, second = 0, replacement = 0;
    CHECK(ctos_ssh_role_bootstrap_open(&first_request, &first) == 0 && first != 0);
    CHECK(ctos_ssh_role_bootstrap_active(first) != 0);
    struct ctos_ssh_role_snapshot snapshot;
    CHECK(ctos_ssh_role_bootstrap_snapshot(first, &snapshot) == 0
        && snapshot.owner_pid == getpid() && snapshot.owner_uid == getuid()
        && snapshot.hop == 0 && strcmp(snapshot.public_view_id, "view_0") == 0
        && snapshot.identity_version == CTOS_SSH_ROLE_IDENTITY_V1
        && strcmp(snapshot.chain_identity, snapshot.target_identity) == 0
        && memcmp(snapshot.role_nonce, nonce, 32) == 0);
    unsigned char wire[CTOS_SSH_ROLE_WIRE_BYTES];
    size_t wire_length = 0;
    CHECK(ctos_ssh_role_bootstrap_encode(first, wire, sizeof(wire), &wire_length) == 0
        && wire_length > 0 && wire_length < sizeof(wire)
        && wire[0] == (unsigned char)(CTOS_SSH_ROLE_MAGIC >> 24)
        && wire[4] == 1 && wire[5] == CTOS_SSH_ROLE_CLIENT);
    CHECK(wire_length == 112U + strlen(first_request.connection_id)
        + strlen(first_request.target_identity) + strlen(first_request.host)
        + strlen(first_request.public_view_id));

    errno = 0;
    CHECK(ctos_ssh_role_bootstrap_open(&first_request, &replacement) != 0 && errno == EEXIST);
    struct ctos_ssh_role_request same_hop_different_connection = request(0,
        "connection_other", "view_other", second_nonce, second_binding, deadline);
    errno = 0;
    CHECK(ctos_ssh_role_bootstrap_open(&same_hop_different_connection, &replacement) != 0
        && errno == EEXIST);
    struct ctos_ssh_role_request second_request = request(1, "connection_0", "view_1",
        second_nonce, binding, deadline);
    CHECK(ctos_ssh_role_bootstrap_open(&second_request, &second) == 0 && second != 0);
    CHECK(ctos_ssh_role_bootstrap_active(second) != 0);

    unsigned char chained_nonce[32], chained_binding[32];
    values(chained_nonce, chained_binding, 9, 2);
    char chain_identity[] = "target:final@example.invalid:2200";
    char hop_target[] = "target:jump@example.invalid:2222";
    struct ctos_ssh_role_request chained = request(2, "connection_0", "view_2",
        chained_nonce, chained_binding, deadline);
    chained.chain_identity = chain_identity;
    chained.target_identity = hop_target;
    ctos_ssh_role_token chained_token = 0;
    CHECK(ctos_ssh_role_bootstrap_open(&chained, &chained_token) == 0 && chained_token != 0);
    chain_identity[7] = 'X';
    hop_target[7] = 'Y';
    CHECK(ctos_ssh_role_bootstrap_snapshot(chained_token, &snapshot) == 0
        && snapshot.identity_version == CTOS_SSH_ROLE_IDENTITY_V2
        && strcmp(snapshot.chain_identity, "target:final@example.invalid:2200") == 0
        && strcmp(snapshot.target_identity, "target:jump@example.invalid:2222") == 0);
    wire_length = 0;
    const char *expected_chain = "target:final@example.invalid:2200";
    size_t chain_length = strlen(expected_chain);
    CHECK(ctos_ssh_role_bootstrap_encode(chained_token, wire, sizeof(wire), &wire_length) == 0
        && wire[4] == CTOS_SSH_ROLE_IDENTITY_V2 && wire_length > chain_length + 2U
        && wire[wire_length - chain_length - 2U] == (unsigned char)(chain_length >> 8)
        && wire[wire_length - chain_length - 1U] == (unsigned char)chain_length
        && memcmp(wire + wire_length - chain_length, expected_chain, chain_length) == 0);
    CHECK(ctos_ssh_role_bootstrap_close(chained_token) == 0);
    chained.chain_identity = "";
    CHECK(ctos_ssh_role_bootstrap_open(&chained, &chained_token) != 0
        && errno == EINVAL && chained_token == 0);
    chained.chain_identity = "chain\ninvalid";
    CHECK(ctos_ssh_role_bootstrap_open(&chained, &chained_token) != 0
        && errno == EINVAL && chained_token == 0);
    char too_long_chain[CTOS_SSH_ROLE_TARGET_ID_BYTES + 2U];
    memset(too_long_chain, 'a', sizeof(too_long_chain) - 1U);
    too_long_chain[sizeof(too_long_chain) - 1U] = '\0';
    chained.chain_identity = too_long_chain;
    CHECK(ctos_ssh_role_bootstrap_open(&chained, &chained_token) != 0
        && errno == EINVAL && chained_token == 0);

    struct ctos_ssh_role_request same_nonce_different_hop = request(2,
        "connection_nonce_replay", "view_nonce_replay", nonce, binding, deadline);
    errno = 0;
    CHECK(ctos_ssh_role_bootstrap_open(&same_nonce_different_hop, &replacement) != 0
        && errno == EEXIST);

    struct ctos_ssh_role_request wrong_pid = first_request;
    wrong_pid.owner_pid = getpid() + 1;
    errno = 0;
    CHECK(ctos_ssh_role_bootstrap_open(&wrong_pid, &replacement) != 0 && errno == EACCES);
    struct ctos_ssh_role_request wrong_uid = first_request;
    wrong_uid.owner_uid = getuid() + 1;
    errno = 0;
    CHECK(ctos_ssh_role_bootstrap_open(&wrong_uid, &replacement) != 0 && errno == EACCES);
    struct ctos_ssh_role_request shell = first_request;
    shell.connection_id = "connection;exec";
    replacement = first;
    errno = 0;
    CHECK(ctos_ssh_role_bootstrap_open(&shell, &replacement) != 0 && errno == EINVAL);
    CHECK(replacement == 0);

    errno = 0;
    CHECK(ctos_ssh_role_bootstrap_close(first) == 0
        && ctos_ssh_role_bootstrap_active(first) == 0 && errno == ENOENT);
    CHECK(ctos_ssh_role_bootstrap_active(second) != 0);
    errno = 0;
    CHECK(ctos_ssh_role_bootstrap_close(first) != 0 && errno == ENOENT);

    struct ctos_ssh_role_request stale_replacement_request = request(0,
        "replacement_connection", "replacement_view", nonce, binding, now_millis() + 5000);
    CHECK(ctos_ssh_role_bootstrap_open(&stale_replacement_request, &replacement) == 0
        && replacement != 0 && replacement != first);
    errno = 0;
    CHECK(ctos_ssh_role_bootstrap_close(first) != 0 && errno == ENOENT);
    CHECK(ctos_ssh_role_bootstrap_active(replacement) != 0);
    memset(&snapshot, 0xa5, sizeof(snapshot));
    errno = 0;
    CHECK(ctos_ssh_role_bootstrap_snapshot(first, &snapshot) != 0 && errno == ENOENT);
    for (size_t i = 0; i < sizeof(snapshot); i++)
        CHECK(((const unsigned char *)&snapshot)[i] == 0);
    wire_length = 123;
    errno = 0;
    CHECK(ctos_ssh_role_bootstrap_encode(first, wire, sizeof(wire), &wire_length) != 0
        && errno == ENOENT && wire_length == 0);
    CHECK(close_drains_effect(replacement) == 0);

    pid_t child = fork();
    CHECK(child >= 0);
    if (child == 0) {
        errno = 0;
        int close_ok = ctos_ssh_role_bootstrap_close(second) != 0 && errno == EACCES;
        int scope_ok = child_scope(second, nonce, binding) == 0;
        _exit(close_ok && scope_ok ? 0 : 1);
    }
    int status = 0;
    CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    CHECK(ctos_ssh_role_bootstrap_active(second) != 0);

    struct ctos_ssh_role_request expiring = request(2, "expiring_connection", "view_2",
        nonce, binding, now_millis() + 20);
    /* The first role was closed, so this binding can be reused for a new hop. */
    CHECK(ctos_ssh_role_bootstrap_open(&expiring, &replacement) == 0);
    CHECK(ctos_ssh_role_transport_retain(replacement) == 0);
    struct timespec pause = {.tv_nsec = 25000000};
    CHECK(nanosleep(&pause, NULL) == 0);
    errno = 0;
    CHECK(ctos_ssh_role_bootstrap_active(replacement) == 0 && errno == ETIMEDOUT);
    CHECK(ctos_ssh_role_transport_snapshot(replacement, &snapshot) == 0
        && snapshot.token == replacement && snapshot.deadline == expiring.deadline);
    wire_length = 123;
    CHECK(ctos_ssh_role_bootstrap_encode(replacement, wire, sizeof(wire), &wire_length) != 0
        && errno == ETIMEDOUT && wire_length == 0);
    CHECK(ctos_ssh_role_transport_retain(replacement) != 0 && errno == ETIMEDOUT);
    CHECK(ctos_ssh_role_transport_release(replacement) == 0);
    CHECK(ctos_ssh_role_transport_snapshot(replacement, &snapshot) != 0 && errno == ECANCELED);
    CHECK(ctos_ssh_role_bootstrap_active(replacement) == 0 && errno == ETIMEDOUT);
    CHECK(ctos_ssh_role_bootstrap_close(replacement) != 0 && errno == ENOENT);
    CHECK(ctos_ssh_role_bootstrap_close(second) == 0);

    CHECK(CTOS_SSH_ROLE_MAX == 9U);
    ctos_ssh_role_token chain_tokens[CTOS_SSH_ROLE_MAX] = {0};
    unsigned char chain_nonces[CTOS_SSH_ROLE_MAX][32], chain_binding[32];
    memset(chain_binding, 91, sizeof(chain_binding));
    uint64_t chain_deadline = now_millis() + 30000;
    for (unsigned int i = 0; i < CTOS_SSH_ROLE_MAX; i++) {
        memset(chain_nonces[i], (int)i + 1, sizeof(chain_nonces[i]));
        struct ctos_ssh_role_request hop = request(i, "same-connection", "same-view",
            chain_nonces[i], chain_binding, chain_deadline);
        hop.program = i + 1 == CTOS_SSH_ROLE_MAX ? CTOS_SSH_ROLE_CLIENT : CTOS_SSH_ROLE_JUMP;
        CHECK(ctos_ssh_role_bootstrap_open(&hop, &chain_tokens[i]) == 0);
        CHECK(ctos_ssh_role_bootstrap_snapshot(chain_tokens[i], &snapshot) == 0
            && snapshot.hop == i && snapshot.program == hop.program);
    }
    struct ctos_ssh_role_request too_many = request(CTOS_SSH_ROLE_MAX, "same-connection", "same-view",
        chain_nonces[0], chain_binding, chain_deadline);
    errno = 0; replacement = 99;
    CHECK(ctos_ssh_role_bootstrap_open(&too_many, &replacement) == -1
        && errno == EINVAL && replacement == 0);
    CHECK(ctos_ssh_role_bootstrap_close(chain_tokens[4]) == 0);
    CHECK(ctos_ssh_role_bootstrap_active(chain_tokens[8]) != 0);
    for (unsigned int i = 0; i < CTOS_SSH_ROLE_MAX; i++)
        if (i != 4) CHECK(ctos_ssh_role_bootstrap_close(chain_tokens[i]) == 0);

    char capacity_connections[CTOS_SSH_ROLE_MAX][32];
    char capacity_views[CTOS_SSH_ROLE_MAX][32];
    unsigned char capacity_nonces[CTOS_SSH_ROLE_MAX][32];
    unsigned char capacity_bindings[CTOS_SSH_ROLE_MAX][32];
    ctos_ssh_role_token capacity_token = 0;
    uint64_t capacity_deadline = now_millis() + 100;
    for (unsigned int i = 0; i < CTOS_SSH_ROLE_MAX; i++) {
        CHECK(snprintf(capacity_connections[i], sizeof(capacity_connections[i]),
            "capacity_connection_%u", i) > 0);
        CHECK(snprintf(capacity_views[i], sizeof(capacity_views[i]), "capacity_view_%u", i) > 0);
        values(capacity_nonces[i], capacity_bindings[i], 10 + i, 40 + i);
        struct ctos_ssh_role_request capacity_request = request(i, capacity_connections[i],
            capacity_views[i], capacity_nonces[i], capacity_bindings[i], capacity_deadline);
        CHECK(ctos_ssh_role_bootstrap_open(&capacity_request, &capacity_token) == 0
            && capacity_token != 0);
    }
    struct timespec capacity_pause = {.tv_nsec = 125000000};
    CHECK(nanosleep(&capacity_pause, NULL) == 0);
    struct ctos_ssh_role_request replacement_request = request(0, capacity_connections[0],
        capacity_views[0], capacity_nonces[0], capacity_bindings[0], now_millis() + 5000);
    errno = 0;
    CHECK(ctos_ssh_role_bootstrap_open(&replacement_request, &replacement) == 0
        && replacement != 0);
    CHECK(ctos_ssh_role_bootstrap_active(replacement) != 0);
    CHECK(ctos_ssh_role_bootstrap_close(replacement) == 0);
    printf("SSH_ROLE phase=bootstrap state=pass errno=0 value=%u\n", checks);
    printf("SSH_ROLE phase=cleanup state=pass errno=0 value=1\n");
    printf("SSH_ROLE phase=gate state=pass errno=0 value=%u\n", checks);
    return 0;
}

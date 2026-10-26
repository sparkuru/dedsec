#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "ssh_role_bootstrap.h"

#include <errno.h>
#include <limits.h>
#include <pthread.h>
#include <stdint.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

struct role_slot {
    int live;
    unsigned int transports;
    struct ctos_ssh_role_snapshot snapshot;
};

static pthread_mutex_t roles_lock = PTHREAD_MUTEX_INITIALIZER;
static struct role_slot roles[CTOS_SSH_ROLE_MAX];
static uint64_t next_token;

static void wipe(void *memory, size_t length) {
    volatile unsigned char *bytes = memory;
    while (length-- != 0) *bytes++ = 0;
}

static int nonzero(const unsigned char *bytes) {
    unsigned char value = 0;
    if (bytes == NULL) return 0;
    for (size_t i = 0; i < 32; i++) value |= bytes[i];
    return value != 0;
}

static int now_millis(uint64_t *result) {
    struct timespec now;
    if (result == NULL || clock_gettime(CLOCK_BOOTTIME, &now) != 0
        || now.tv_sec < 0 || now.tv_nsec < 0 || now.tv_nsec >= 1000000000
        || (uint64_t)now.tv_sec > ((uint64_t)INT64_MAX - 999) / 1000) {
        errno = EIO;
        return -1;
    }
    *result = (uint64_t)now.tv_sec * 1000U + (uint64_t)now.tv_nsec / 1000000U;
    return 0;
}

static int bounded_deadline(uint64_t deadline, uint64_t now) {
    if (deadline == 0 || deadline <= now || deadline > INT64_MAX
        || deadline - now > CTOS_SSH_ROLE_MAX_LIFETIME_MS) {
        errno = deadline <= now ? ETIMEDOUT : EINVAL;
        return -1;
    }
    return 0;
}

static int identifier(const char *value, size_t maximum) {
    size_t length;
    if (value == NULL) { errno = EINVAL; return -1; }
    length = strnlen(value, maximum + 1);
    if (length == 0 || length > maximum) { errno = EINVAL; return -1; }
    for (size_t i = 0; i < length; i++) {
        unsigned char ch = (unsigned char)value[i];
        if (!((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
            || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-')) {
            errno = EINVAL;
            return -1;
        }
    }
    return 0;
}

static int target_identity(const char *value) {
    size_t length;
    if (value == NULL) { errno = EINVAL; return -1; }
    length = strnlen(value, CTOS_SSH_ROLE_TARGET_ID_BYTES + 1);
    if (length == 0 || length > CTOS_SSH_ROLE_TARGET_ID_BYTES) { errno = EINVAL; return -1; }
    for (size_t i = 0; i < length; i++) {
        unsigned char ch = (unsigned char)value[i];
        if (!((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
            || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-'
            || ch == '.' || ch == ':' || ch == '@' || ch == '+' || ch == '/')) {
            errno = EINVAL;
            return -1;
        }
    }
    return 0;
}

static int host(const char *value) {
    size_t length;
    if (value == NULL) { errno = EINVAL; return -1; }
    length = strnlen(value, CTOS_SSH_ROLE_HOST_BYTES + 1);
    if (length == 0 || length > CTOS_SSH_ROLE_HOST_BYTES) { errno = EINVAL; return -1; }
    for (size_t i = 0; i < length; i++) {
        unsigned char ch = (unsigned char)value[i];
        if (!((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
            || (ch >= '0' && ch <= '9') || ch == '.' || ch == ':'
            || ch == '%' || ch == '[' || ch == ']' || ch == '-')) {
            errno = EINVAL;
            return -1;
        }
    }
    return 0;
}

static int same32(const unsigned char *left, const unsigned char *right) {
    unsigned char difference = 0;
    for (size_t i = 0; i < 32; i++) difference |= left[i] ^ right[i];
    return difference == 0;
}

static struct role_slot *lookup_locked(ctos_ssh_role_token token) {
    if (token == 0) return NULL;
    for (size_t i = 0; i < CTOS_SSH_ROLE_MAX; i++) {
        if (roles[i].live && roles[i].snapshot.token == token
            && roles[i].snapshot.owner_pid == getpid()
            && roles[i].snapshot.owner_uid == getuid()) return &roles[i];
    }
    return NULL;
}

static int expire_locked(struct role_slot *slot, uint64_t now) {
    if (slot == NULL || !slot->live) { errno = ENOENT; return -1; }
    if (now >= slot->snapshot.deadline) {
        if (slot->transports == 0U) wipe(slot, sizeof(*slot));
        errno = ETIMEDOUT;
        return -1;
    }
    return 0;
}

static void reclaim_expired_locked(uint64_t now) {
    for (size_t i = 0; i < CTOS_SSH_ROLE_MAX; i++) {
        if (roles[i].live && roles[i].transports == 0U
            && now >= roles[i].snapshot.deadline) {
            wipe(&roles[i], sizeof(roles[i]));
        }
    }
}

int ctos_ssh_role_bootstrap_open(const struct ctos_ssh_role_request *request,
    ctos_ssh_role_token *token) {
    uint64_t now;
    if (token == NULL) { errno = EINVAL; return -1; }
    *token = 0;
    if (request == NULL) { errno = EINVAL; return -1; }
    if (request->owner_pid != getpid() || request->owner_uid != getuid()) {
        errno = EACCES;
        return -1;
    }
    if (request->program < CTOS_SSH_ROLE_CLIENT
        || request->program > CTOS_SSH_ROLE_JUMP || request->owner_pid <= 0
        || request->owner_pid > INT32_MAX || request->hop >= CTOS_SSH_ROLE_MAX
        || request->port == 0 || request->port > 65535U
        || !nonzero(request->role_nonce) || !nonzero(request->connection_binding)
        || identifier(request->connection_id, CTOS_SSH_ROLE_CONNECTION_ID_BYTES) != 0
        || target_identity(request->target_identity) != 0
        || host(request->host) != 0
        || identifier(request->public_view_id, CTOS_SSH_ROLE_VIEW_ID_BYTES) != 0
        || now_millis(&now) != 0 || bounded_deadline(request->deadline, now) != 0) {
        if (errno == 0) errno = EINVAL;
        return -1;
    }
    if (pthread_mutex_lock(&roles_lock) != 0) { errno = EBUSY; return -1; }
    /* Re-read the clock after taking the lock so admission and reclamation use
     * the same point in time. A contended lock must not admit an already
     * expired role or leave an expired slot occupying capacity. */
    if (now_millis(&now) != 0 || bounded_deadline(request->deadline, now) != 0) {
        int error = errno;
        pthread_mutex_unlock(&roles_lock);
        errno = error;
        return -1;
    }
    reclaim_expired_locked(now);
    for (size_t i = 0; i < CTOS_SSH_ROLE_MAX; i++) {
        const struct ctos_ssh_role_snapshot *existing = &roles[i].snapshot;
        if (roles[i].live && ((existing->owner_pid == request->owner_pid
                && existing->hop == request->hop)
            || same32(existing->role_nonce, request->role_nonce)
            || (existing->hop == request->hop
                && same32(existing->connection_binding, request->connection_binding)))) {
            pthread_mutex_unlock(&roles_lock);
            errno = EEXIST;
            return -1;
        }
    }
    struct role_slot *slot = NULL;
    for (size_t i = 0; i < CTOS_SSH_ROLE_MAX; i++) {
        if (!roles[i].live) { slot = &roles[i]; break; }
    }
    if (slot == NULL || next_token == UINT64_MAX) {
        pthread_mutex_unlock(&roles_lock);
        errno = EAGAIN;
        return -1;
    }
    memset(slot, 0, sizeof(*slot));
    slot->snapshot.token = ++next_token;
    slot->snapshot.program = request->program;
    slot->snapshot.owner_pid = request->owner_pid;
    slot->snapshot.owner_uid = request->owner_uid;
    slot->snapshot.hop = request->hop;
    slot->snapshot.port = request->port;
    slot->snapshot.deadline = request->deadline;
    memcpy(slot->snapshot.connection_id, request->connection_id, strlen(request->connection_id) + 1);
    memcpy(slot->snapshot.target_identity, request->target_identity, strlen(request->target_identity) + 1);
    memcpy(slot->snapshot.host, request->host, strlen(request->host) + 1);
    memcpy(slot->snapshot.public_view_id, request->public_view_id, strlen(request->public_view_id) + 1);
    memcpy(slot->snapshot.role_nonce, request->role_nonce, 32);
    memcpy(slot->snapshot.connection_binding, request->connection_binding, 32);
    slot->live = 1;
    *token = slot->snapshot.token;
    pthread_mutex_unlock(&roles_lock);
    return 0;
}

int ctos_ssh_role_bootstrap_snapshot(ctos_ssh_role_token token,
    struct ctos_ssh_role_snapshot *snapshot) {
    uint64_t now;
    if (snapshot == NULL) { errno = EINVAL; return -1; }
    memset(snapshot, 0, sizeof(*snapshot));
    if (token == 0) { errno = EINVAL; return -1; }
    if (pthread_mutex_lock(&roles_lock) != 0) { errno = EBUSY; return -1; }
    if (now_millis(&now) != 0) {
        int error = errno;
        pthread_mutex_unlock(&roles_lock);
        errno = error;
        return -1;
    }
    struct role_slot *slot = lookup_locked(token);
    if (expire_locked(slot, now) != 0) {
        pthread_mutex_unlock(&roles_lock);
        return -1;
    }
    *snapshot = slot->snapshot;
    pthread_mutex_unlock(&roles_lock);
    return 0;
}

int ctos_ssh_role_bootstrap_active(ctos_ssh_role_token token) {
    uint64_t now;
    if (token == 0) return 0;
    if (pthread_mutex_lock(&roles_lock) != 0) return 0;
    if (now_millis(&now) != 0) {
        int error = errno;
        pthread_mutex_unlock(&roles_lock);
        errno = error;
        return 0;
    }
    struct role_slot *slot = lookup_locked(token);
    int result = expire_locked(slot, now) == 0;
    pthread_mutex_unlock(&roles_lock);
    return result;
}

int ctos_ssh_role_bootstrap_close(ctos_ssh_role_token token) {
    if (token == 0 || pthread_mutex_lock(&roles_lock) != 0) { errno = EINVAL; return -1; }
    struct role_slot *slot = lookup_locked(token);
    if (slot == NULL) {
        int error = ENOENT;
        for (size_t index = 0; index < CTOS_SSH_ROLE_MAX; index++) {
            if (roles[index].live && roles[index].snapshot.token == token) {
                error = EACCES;
                break;
            }
        }
        pthread_mutex_unlock(&roles_lock);
        errno = error;
        return -1;
    }
    wipe(slot, sizeof(*slot));
    pthread_mutex_unlock(&roles_lock);
    return 0;
}

int ctos_ssh_role_transport_retain(ctos_ssh_role_token token) {
    uint64_t now;
    struct role_slot *slot;
    int error;

    if (token == 0 || pthread_mutex_lock(&roles_lock) != 0) { errno = EINVAL; return -1; }
    slot = lookup_locked(token);
    if (now_millis(&now) != 0 || expire_locked(slot, now) != 0) {
        error = errno;
        pthread_mutex_unlock(&roles_lock);
        errno = error;
        return -1;
    }
    if (slot->transports == UINT_MAX) {
        pthread_mutex_unlock(&roles_lock); errno = EOVERFLOW; return -1;
    }
    slot->transports++;
    pthread_mutex_unlock(&roles_lock);
    return 0;
}

int ctos_ssh_role_transport_snapshot(ctos_ssh_role_token token,
    struct ctos_ssh_role_snapshot *snapshot) {
    struct role_slot *slot;
    if (snapshot == NULL) { errno = EINVAL; return -1; }
    memset(snapshot, 0, sizeof(*snapshot));
    if (token == 0 || pthread_mutex_lock(&roles_lock) != 0) { errno = EINVAL; return -1; }
    slot = lookup_locked(token);
    if (slot == NULL || slot->transports == 0U) {
        pthread_mutex_unlock(&roles_lock); errno = ECANCELED; return -1;
    }
    *snapshot = slot->snapshot;
    pthread_mutex_unlock(&roles_lock);
    return 0;
}

int ctos_ssh_role_transport_release(ctos_ssh_role_token token) {
    struct role_slot *slot;
    if (token == 0 || pthread_mutex_lock(&roles_lock) != 0) { errno = EINVAL; return -1; }
    slot = lookup_locked(token);
    if (slot == NULL) {
        pthread_mutex_unlock(&roles_lock); errno = ENOENT; return -1;
    }
    if (slot->transports == 0U) {
        pthread_mutex_unlock(&roles_lock); errno = EALREADY; return -1;
    }
    slot->transports--;
    pthread_mutex_unlock(&roles_lock);
    return 0;
}

static int put16(unsigned char *output, size_t capacity, size_t *offset, size_t value) {
    if (value > UINT16_MAX || *offset > capacity || capacity - *offset < 2) {
        errno = ENOSPC;
        return -1;
    }
    output[(*offset)++] = (unsigned char)(value >> 8);
    output[(*offset)++] = (unsigned char)value;
    return 0;
}

static int put32(unsigned char *output, size_t capacity, size_t *offset, uint32_t value) {
    if (*offset > capacity || capacity - *offset < 4) { errno = ENOSPC; return -1; }
    output[(*offset)++] = (unsigned char)(value >> 24);
    output[(*offset)++] = (unsigned char)(value >> 16);
    output[(*offset)++] = (unsigned char)(value >> 8);
    output[(*offset)++] = (unsigned char)value;
    return 0;
}

static int put64(unsigned char *output, size_t capacity, size_t *offset, uint64_t value) {
    if (put32(output, capacity, offset, (uint32_t)(value >> 32)) != 0
        || put32(output, capacity, offset, (uint32_t)value) != 0) return -1;
    return 0;
}

static int put_bytes(unsigned char *output, size_t capacity, size_t *offset,
    const unsigned char *value, size_t length) {
    if (*offset > capacity || length > capacity - *offset) { errno = ENOSPC; return -1; }
    memcpy(output + *offset, value, length);
    *offset += length;
    return 0;
}

static int put_text(unsigned char *output, size_t capacity, size_t *offset, const char *value) {
    size_t length = strlen(value);
    return put16(output, capacity, offset, length) == 0
        && put_bytes(output, capacity, offset, (const unsigned char *)value, length) == 0 ? 0 : -1;
}

int ctos_ssh_role_bootstrap_encode(ctos_ssh_role_token token, unsigned char *output,
    size_t capacity, size_t *length) {
    struct ctos_ssh_role_snapshot snapshot;
    size_t offset = 0;
    if (length != NULL) *length = 0;
    if (output == NULL || length == NULL || capacity > CTOS_SSH_ROLE_WIRE_BYTES
        || ctos_ssh_role_bootstrap_snapshot(token, &snapshot) != 0) {
        if (errno == 0) errno = EINVAL;
        return -1;
    }
    if (put32(output, capacity, &offset, CTOS_SSH_ROLE_MAGIC) != 0
        || put_bytes(output, capacity, &offset,
            (const unsigned char[]){1, (unsigned char)snapshot.program, 0, 0}, 4) != 0
        || put64(output, capacity, &offset, snapshot.token) != 0
        || put32(output, capacity, &offset, (uint32_t)snapshot.owner_pid) != 0
        || put32(output, capacity, &offset, (uint32_t)snapshot.owner_uid) != 0
        || put32(output, capacity, &offset, snapshot.hop) != 0
        || put32(output, capacity, &offset, snapshot.port) != 0
        || put64(output, capacity, &offset, snapshot.deadline) != 0
        || put_bytes(output, capacity, &offset, snapshot.role_nonce, 32) != 0
        || put_bytes(output, capacity, &offset, snapshot.connection_binding, 32) != 0
        || put_text(output, capacity, &offset, snapshot.connection_id) != 0
        || put_text(output, capacity, &offset, snapshot.target_identity) != 0
        || put_text(output, capacity, &offset, snapshot.host) != 0
        || put_text(output, capacity, &offset, snapshot.public_view_id) != 0
        || offset > CTOS_SSH_ROLE_WIRE_BYTES) {
        wipe(&snapshot, sizeof(snapshot));
        if (errno == 0) errno = ENOSPC;
        return -1;
    }
    *length = offset;
    wipe(&snapshot, sizeof(snapshot));
    return 0;
}

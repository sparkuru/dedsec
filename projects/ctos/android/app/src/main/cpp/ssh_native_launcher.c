#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include "includes.h"

#include <errno.h>
#include <limits.h>
#include <stdint.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "ssh_api.h"
#include "ssherr.h"
#include "ssh_kex_guard.h"
#include "ssh_native_launcher.h"

struct launcher_slot {
    struct ssh *ssh;
    ctos_ssh_role_token token;
};

static struct launcher_slot slots[CTOS_SSH_NATIVE_LAUNCHER_MAX];

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
        || now.tv_sec < 0 || now.tv_nsec < 0
        || now.tv_nsec >= 1000000000
        || (uint64_t)now.tv_sec > ((uint64_t)INT64_MAX - 999) / 1000) {
        errno = EIO;
        return -1;
    }
    *result = (uint64_t)now.tv_sec * 1000U
        + (uint64_t)now.tv_nsec / 1000000U;
    return 0;
}

static int restricted_text(const char *value, size_t maximum) {
    size_t length;

    if (value == NULL) {
        errno = EINVAL;
        return -1;
    }
    length = strnlen(value, maximum + 1);
    if (length == 0 || length > maximum) {
        errno = EINVAL;
        return -1;
    }
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

static int restricted_target(const char *value) {
    size_t length;

    if (value == NULL) {
        errno = EINVAL;
        return -1;
    }
    length = strnlen(value, CTOS_SSH_ROLE_TARGET_ID_BYTES + 1);
    if (length == 0 || length > CTOS_SSH_ROLE_TARGET_ID_BYTES) {
        errno = EINVAL;
        return -1;
    }
    for (size_t i = 0; i < length; i++) {
        unsigned char ch = (unsigned char)value[i];
        if (!((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
            || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-'
            || ch == '.' || ch == ':' || ch == '@' || ch == '+'
            || ch == '/')) {
            errno = EINVAL;
            return -1;
        }
    }
    return 0;
}

static int restricted_host(const char *value) {
    size_t length;

    if (value == NULL) {
        errno = EINVAL;
        return -1;
    }
    length = strnlen(value, CTOS_SSH_ROLE_HOST_BYTES + 1);
    if (length == 0 || length > CTOS_SSH_ROLE_HOST_BYTES) {
        errno = EINVAL;
        return -1;
    }
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

static int snapshot_is_current(const struct ctos_ssh_role_snapshot *snapshot,
    ctos_ssh_role_token token) {
    uint64_t now;

    errno = 0;
    if (snapshot == NULL || snapshot->token != token
        || snapshot->owner_pid != getpid()
        || snapshot->owner_uid != getuid()
        || (snapshot->program != CTOS_SSH_ROLE_CLIENT
            && snapshot->program != CTOS_SSH_ROLE_JUMP)
        || snapshot->hop >= CTOS_SSH_ROLE_MAX || snapshot->port == 0
        || snapshot->port > 65535U || !nonzero(snapshot->role_nonce)
        || !nonzero(snapshot->connection_binding)
        || restricted_text(snapshot->connection_id,
            CTOS_SSH_ROLE_CONNECTION_ID_BYTES) != 0
        || restricted_target(snapshot->target_identity) != 0
        || restricted_host(snapshot->host) != 0
        || restricted_text(snapshot->public_view_id,
            CTOS_SSH_ROLE_VIEW_ID_BYTES) != 0
        || now_millis(&now) != 0 || snapshot->deadline == 0
        || snapshot->deadline <= now || snapshot->deadline > INT64_MAX) {
        if (errno == 0) errno = EINVAL;
        return -1;
    }
    return 0;
}

static int reject(int error) {
    errno = error == 0 ? EINVAL : error;
    return SSH_ERR_INVALID_ARGUMENT;
}

static int find_exact(struct ssh *ssh, ctos_ssh_role_token token) {
    for (size_t i = 0; i < CTOS_SSH_NATIVE_LAUNCHER_MAX; i++) {
        if (slots[i].ssh == ssh && slots[i].token == token) return (int)i;
    }
    return -1;
}

static int find_conflict(struct ssh *ssh, ctos_ssh_role_token token) {
    for (size_t i = 0; i < CTOS_SSH_NATIVE_LAUNCHER_MAX; i++) {
        if (slots[i].ssh == ssh || slots[i].token == token) return (int)i;
    }
    return -1;
}

int ctos_ssh_native_launcher_admit(const unsigned char *wire,
    size_t wire_length, ctos_ssh_role_token token, struct ssh *ssh,
    const unsigned char expected_host[32]) {
    struct ctos_ssh_role_snapshot snapshot;
    unsigned char canonical[CTOS_SSH_ROLE_WIRE_BYTES];
    unsigned char supplied[CTOS_SSH_ROLE_WIRE_BYTES];
    unsigned char fingerprint[32];
    size_t canonical_length = 0;
    int free_slot = -1;
    int result = SSH_ERR_INVALID_ARGUMENT;

    memset(&snapshot, 0, sizeof(snapshot));
    memset(canonical, 0, sizeof(canonical));
    memset(supplied, 0, sizeof(supplied));
    memset(fingerprint, 0, sizeof(fingerprint));
    if (wire == NULL || wire_length == 0
        || wire_length > sizeof(supplied) || token == 0 || ssh == NULL
        || ssh->kex == NULL || ssh->kex->server
        || ssh->kex->verify_host_key == NULL || expected_host == NULL
        || !nonzero(expected_host)) {
        result = reject(EINVAL);
        goto out;
    }
    memcpy(supplied, wire, wire_length);
    memcpy(fingerprint, expected_host, sizeof(fingerprint));

    if (ctos_ssh_role_bootstrap_snapshot(token, &snapshot) != 0) {
        result = reject(errno);
        goto out;
    }
    if (snapshot_is_current(&snapshot, token) != 0) {
        result = reject(errno);
        goto out;
    }
    if (ctos_ssh_role_bootstrap_encode(token, canonical, sizeof(canonical),
            &canonical_length) != 0) {
        result = reject(errno);
        goto out;
    }
    /* The length comparison is what rejects a trailing byte before memcmp. */
    if (wire_length != canonical_length
        || memcmp(supplied, canonical, canonical_length) != 0) {
        result = reject(EPROTO);
        goto out;
    }
    if (find_conflict(ssh, token) >= 0) {
        result = reject(EEXIST);
        goto out;
    }
    for (size_t i = 0; i < CTOS_SSH_NATIVE_LAUNCHER_MAX; i++) {
        if (slots[i].ssh == NULL) {
            free_slot = (int)i;
            break;
        }
    }
    if (free_slot < 0) {
        result = SSH_ERR_ALLOC_FAIL;
        errno = EAGAIN;
        goto out;
    }
    result = ctos_ssh_kex_bind_role(ssh, token, fingerprint);
    if (result != 0) goto out;
    slots[free_slot].ssh = ssh;
    slots[free_slot].token = token;
out:
    wipe(&snapshot, sizeof(snapshot));
    wipe(canonical, sizeof(canonical));
    wipe(supplied, sizeof(supplied));
    wipe(fingerprint, sizeof(fingerprint));
    return result;
}

int ctos_ssh_native_launcher_close(struct ssh *ssh,
    ctos_ssh_role_token token) {
    int slot;
    int result;

    if (ssh == NULL || token == 0) return reject(EINVAL);
    slot = find_exact(ssh, token);
    if (slot < 0) return reject(find_conflict(ssh, token) >= 0 ? EACCES : ENOENT);

    /* Native role ownership is released first.  Keep this slot and KEX guard
     * intact if the role close fails so the caller can retry safely. */
    result = ctos_ssh_role_bootstrap_close(token);
    if (result != 0) return reject(errno);
    ctos_ssh_kex_unbind(ssh);
    wipe(&slots[slot], sizeof(slots[slot]));
    return 0;
}

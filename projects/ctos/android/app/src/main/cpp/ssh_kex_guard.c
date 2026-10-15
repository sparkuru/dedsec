#include "includes.h"
#include <stdint.h>
#include <time.h>
#include <unistd.h>
#include "ssh_api.h"
#include "sshbuf.h"
#include "digest.h"
#include "ssherr.h"
#include "ssh_kex_guard.h"

/* Single native event-loop owner; eight hops are independent, not eight
 * independently authorized sessions. Broker issuance is still required. */
struct role {
    struct ssh *ssh;
    ctos_ssh_role_token bootstrap;
    pid_t owner;
    unsigned int hop, begun, approved, signed_key, complete;
    uint64_t deadline, cycle;
    unsigned char nonce[32], connection[32], host[32];
};
static struct role roles[8];
static uint64_t now(void) {
    struct timespec time;
    if (clock_gettime(CLOCK_BOOTTIME, &time) != 0 || time.tv_sec < 0) return 0;
    return (uint64_t)time.tv_sec * 1000 + (uint64_t)time.tv_nsec / 1000000;
}
static int nonzero(const unsigned char *bytes) {
    unsigned char value = 0;
    if (bytes == NULL) return 0;
    for (unsigned int i = 0; i < 32; i++) value |= bytes[i];
    return value != 0;
}
static struct role *lookup(struct ssh *ssh) {
    if (ssh == NULL || ssh->kex == NULL || ssh->kex->server) return NULL;
    for (unsigned int i = 0; i < 8; i++)
        if (roles[i].ssh == ssh && roles[i].owner == getpid()
            && ctos_ssh_role_bootstrap_active(roles[i].bootstrap)) return &roles[i];
    return NULL;
}
static int reject(struct role *role) {
    if (role != NULL) {
        role->begun = role->approved = role->signed_key = role->complete = 0;
    }
    return SSH_ERR_SIGNATURE_INVALID;
}
static int matches(struct role *role, const struct sshkey *key) {
    unsigned char *hash = NULL;
    size_t length = 0;
    int result = key != NULL && sshkey_fingerprint_raw(key, SSH_DIGEST_SHA256,
        &hash, &length) == 0 && length == 32 && timingsafe_bcmp(hash, role->host, 32) == 0;
    if (hash != NULL) { explicit_bzero(hash, length); free(hash); }
    return result;
}
int ctos_ssh_kex_bind_role(struct ssh *ssh, ctos_ssh_role_token bootstrap,
    const unsigned char host[32]) {
    struct ctos_ssh_role_snapshot snapshot;
    uint64_t time = now();
    if (ctos_ssh_role_bootstrap_snapshot(bootstrap, &snapshot) != 0
        || ssh == NULL || ssh->kex == NULL || ssh->kex->server || !nonzero(host)
        || time == 0 || snapshot.owner_pid != getpid() || snapshot.owner_uid != getuid()
        || lookup(ssh) != NULL)
        return SSH_ERR_INVALID_ARGUMENT;
    for (unsigned int i = 0; i < 8; i++) {
        if (roles[i].ssh != NULL && (roles[i].ssh == ssh ||
            roles[i].bootstrap == bootstrap ||
            timingsafe_bcmp(roles[i].nonce, snapshot.role_nonce, 32) == 0 ||
            (roles[i].hop == snapshot.hop && timingsafe_bcmp(roles[i].connection,
                snapshot.connection_binding, 32) == 0)))
            return SSH_ERR_INVALID_ARGUMENT;
    }
    for (unsigned int i = 0; i < 8; i++) if (roles[i].ssh == NULL) {
        roles[i].ssh = ssh; roles[i].bootstrap = bootstrap; roles[i].owner = getpid();
        roles[i].hop = snapshot.hop; roles[i].deadline = snapshot.deadline;
        memcpy(roles[i].nonce, snapshot.role_nonce, 32);
        memcpy(roles[i].connection, snapshot.connection_binding, 32);
        memcpy(roles[i].host, host, 32);
        return 0;
    }
    return SSH_ERR_ALLOC_FAIL;
}
void ctos_ssh_kex_unbind(struct ssh *ssh) {
    for (unsigned int i = 0; i < 8; i++)
        if (roles[i].ssh == ssh) explicit_bzero(&roles[i], sizeof(roles[i]));
}
int ctos_ssh_kex_begin(struct ssh *ssh) {
    if (ssh != NULL && ssh->kex != NULL && ssh->kex->server) return 0;
    struct role *role = lookup(ssh);
    uint64_t time = now();
    if (role == NULL || time == 0 || (role->cycle == 0 && time >= role->deadline) ||
        role->cycle == UINT64_MAX || role->begun) return reject(role);
    role->cycle++; role->begun = 1;
    role->approved = role->signed_key = role->complete = 0;
    return 0;
}
int ctos_ssh_kex_approved(struct ssh *ssh, const struct sshkey *key) {
    struct role *role = lookup(ssh);
    if (role == NULL || !role->begun || role->approved || !matches(role, key)) return reject(role);
    role->approved = 1;
    return 0;
}
int ctos_ssh_kex_signed(struct ssh *ssh, const struct sshkey *key) {
    struct role *role = lookup(ssh);
    if (role == NULL || !role->begun || !role->approved || role->signed_key ||
        !matches(role, key)) return reject(role);
    role->signed_key = 1;
    return 0;
}
int ctos_ssh_kex_complete(struct ssh *ssh) {
    if (ssh != NULL && ssh->kex != NULL && ssh->kex->server) return 0;
    struct role *role = lookup(ssh);
    if (role == NULL || !role->begun || !role->approved || !role->signed_key ||
        sshbuf_len(ssh->kex->session_id) < 20 || sshbuf_len(ssh->kex->session_id) > 64)
        return reject(role);
    role->begun = 0; role->complete = 1;
    return 0;
}
uint64_t ctos_ssh_kex_ready(struct ssh *ssh, unsigned int hop,
    const unsigned char nonce[32], const unsigned char connection[32]) {
    struct role *role = lookup(ssh);
    uint64_t time = now();
    if (role == NULL || time == 0 || time >= role->deadline || !role->complete ||
        role->begun || !ssh->kex->done || hop != role->hop ||
        !nonzero(nonce) || !nonzero(connection) ||
        timingsafe_bcmp(role->nonce, nonce, 32) != 0 ||
        timingsafe_bcmp(role->connection, connection, 32) != 0) return 0;
    return role->cycle;
}

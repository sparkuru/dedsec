#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include "ssh_callback_adapter.h"

#include <errno.h>
#include <limits.h>
#include <pthread.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

struct callback_slot {
    int live;
    int in_callback;
    int cancelled;
    int closing;
    int failed;
    int known_hosts_candidate_used;
    int known_hosts_candidate_aborted;
    ctos_ssh_callback_token callback;
    ctos_ssh_role_token role;
    uid_t owner_uid;
    pthread_t event_thread;
    uint64_t deadline;
    const void *transport_owner;
    struct ctos_ssh_callback_target target;
};

static pthread_mutex_t callbacks_lock = PTHREAD_MUTEX_INITIALIZER;
static struct callback_slot callbacks[CTOS_SSH_CALLBACK_MAX];
static uint64_t next_callback;

static void wipe_bytes(unsigned char *bytes, size_t length) {
    if (bytes == NULL) return;
    volatile unsigned char *target = (volatile unsigned char *)bytes;
    for (size_t index = 0; index < length; index++) target[index] = 0;
}

static int now_millis(uint64_t *result) {
    struct timespec current;
    if (result == NULL || clock_gettime(CLOCK_BOOTTIME, &current) != 0
        || current.tv_sec < 0 || current.tv_nsec < 0
        || current.tv_nsec >= 1000000000
        || (uint64_t)current.tv_sec > ((uint64_t)INT64_MAX - 999U) / 1000U) {
        errno = EIO;
        return -1;
    }
    *result = (uint64_t)current.tv_sec * 1000U
        + (uint64_t)current.tv_nsec / 1000000U;
    return 0;
}

static int nonzero32(const unsigned char bytes[32]) {
    unsigned char combined = 0;
    if (bytes == NULL) return 0;
    for (size_t index = 0; index < 32; index++) combined |= bytes[index];
    return combined != 0;
}

static int bounded_ascii(const char *value, size_t maximum, size_t *length_out) {
    size_t length;
    if (value == NULL) { errno = EINVAL; return -1; }
    length = strnlen(value, maximum + 1U);
    if (length == 0 || length > maximum) { errno = EINVAL; return -1; }
    for (size_t index = 0; index < length; index++) {
        unsigned char character = (unsigned char)value[index];
        if (character < 0x21U || character > 0x7eU) {
            errno = EINVAL;
            return -1;
        }
    }
    if (length_out != NULL) *length_out = length;
    return 0;
}

static int bounded_field(const char *value, size_t maximum, size_t *length_out) {
    size_t length;
    if (value == NULL) { errno = EINVAL; return -1; }
    length = strnlen(value, maximum + 1U);
    if (length > maximum) { errno = EINVAL; return -1; }
    for (size_t index = 0; index < length; index++) {
        unsigned char character = (unsigned char)value[index];
        if (character < 0x21U || character > 0x7eU) {
            errno = EINVAL;
            return -1;
        }
    }
    if (length_out != NULL) *length_out = length;
    return 0;
}

static int copy_role_field(char *destination, size_t capacity, const char *source,
    size_t maximum) {
    size_t length;
    if (destination == NULL || capacity == 0 || source == NULL
        || bounded_field(source, maximum, &length) != 0 || length >= capacity) {
        errno = EINVAL;
        return -1;
    }
    memcpy(destination, source, length);
    destination[length] = '\0';
    return 0;
}

static int valid_key_type(const char *value) {
    size_t length;
    if (bounded_ascii(value, CTOS_SSH_CALLBACK_HOST_KEY_TYPE_BYTES - 1U, &length) != 0)
        return -1;
    for (size_t index = 0; index < length; index++) {
        unsigned char character = (unsigned char)value[index];
        if (!((character >= 'A' && character <= 'Z')
            || (character >= 'a' && character <= 'z')
            || (character >= '0' && character <= '9')
            || character == '@' || character == '.' || character == '_'
            || character == '+' || character == '-')) {
            errno = EINVAL;
            return -1;
        }
    }
    return 0;
}

static int valid_fingerprint(const char *value) {
    size_t length;
    if (bounded_ascii(value, CTOS_SSH_CALLBACK_HOST_FINGERPRINT_BYTES - 1U, &length) != 0
        || length != 50U || strncmp(value, "SHA256:", 7U) != 0) {
        errno = EINVAL;
        return -1;
    }
    for (size_t index = 7; index < length; index++) {
        unsigned char character = (unsigned char)value[index];
        if (!((character >= 'A' && character <= 'Z')
            || (character >= 'a' && character <= 'z')
            || (character >= '0' && character <= '9')
            || character == '+' || character == '/')) {
            errno = EINVAL;
            return -1;
        }
    }
    return 0;
}

static int valid_binding(const char *value, int allow_empty) {
    size_t length;
    if (bounded_field(value, CTOS_SSH_CALLBACK_HOST_PROPOSAL_BINDING_BYTES - 1U,
            &length) != 0) return -1;
    if (length == 0 && allow_empty) return 0;
    if (length != 64U) { errno = EINVAL; return -1; }
    for (size_t index = 0; index < length; index++) {
        unsigned char character = (unsigned char)value[index];
        if (!((character >= '0' && character <= '9')
            || (character >= 'a' && character <= 'f'))) {
            errno = EINVAL;
            return -1;
        }
    }
    return 0;
}

static int valid_candidate_id(const char *value) {
    size_t length;
    if (value == NULL) { errno = EINVAL; return -1; }
    length = strnlen(value, CTOS_SSH_CALLBACK_CANDIDATE_ID_BYTES);
    if (length == 0 || length >= CTOS_SSH_CALLBACK_CANDIDATE_ID_BYTES) {
        errno = EINVAL;
        return -1;
    }
    for (size_t index = 0; index < length; index++) {
        unsigned char character = (unsigned char)value[index];
        if (!((character >= 'A' && character <= 'Z')
            || (character >= 'a' && character <= 'z')
            || (character >= '0' && character <= '9')
            || character == '_' || character == '-')) {
            errno = EINVAL;
            return -1;
        }
    }
    return 0;
}

static int same_field(const char *left, const char *right, size_t maximum) {
    size_t left_length;
    size_t right_length;
    if (bounded_field(left, maximum, &left_length) != 0
        || bounded_field(right, maximum, &right_length) != 0
        || left_length != right_length
        || memcmp(left, right, left_length) != 0) {
        errno = EINVAL;
        return -1;
    }
    return 0;
}

static void wipe_snapshot(struct ctos_ssh_role_snapshot *snapshot) {
    if (snapshot == NULL) return;
    volatile unsigned char *bytes = (volatile unsigned char *)snapshot;
    for (size_t index = 0; index < sizeof(*snapshot); index++) bytes[index] = 0;
}

static void wipe_known_hosts_candidate(struct ctos_ssh_known_hosts_candidate *candidate) {
    if (candidate == NULL) return;
    if (candidate->replacement_body != NULL) {
        size_t length = candidate->replacement_body_length;
        if (length > CTOS_SSH_CALLBACK_MAX_FILE_BYTES) {
            length = CTOS_SSH_CALLBACK_MAX_FILE_BYTES;
        }
        wipe_bytes(candidate->replacement_body, length);
    }
    wipe_bytes((unsigned char *)candidate, sizeof(*candidate));
}

/* Return a stable errno-style reason while the callbacks lock is held.  The
 * role registry has its own lock and clock check; taking the snapshot first
 * also lets it wipe an expired role before this binding is marked stale. */
static int role_status(ctos_ssh_callback_token callback,
    const struct callback_slot *slot) {
    struct ctos_ssh_role_snapshot snapshot;
    uint64_t current;
    int error;
    memset(&snapshot, 0, sizeof(snapshot));
    if (slot == NULL || !slot->live || slot->callback != callback
        || slot->owner_uid != getuid() || slot->role == 0) {
        errno = ECANCELED;
        return -1;
    }
    if (ctos_ssh_role_bootstrap_snapshot(slot->role, &snapshot) != 0) {
        error = errno;
        wipe_snapshot(&snapshot);
        errno = error == ETIMEDOUT ? ETIMEDOUT : ECANCELED;
        return -1;
    }
    if (now_millis(&current) != 0) {
        wipe_snapshot(&snapshot);
        return -1;
    }
    if (current >= slot->deadline) {
        wipe_snapshot(&snapshot);
        errno = ETIMEDOUT;
        return -1;
    }
    if (snapshot.token != slot->role
        || snapshot.owner_pid != getpid()
        || snapshot.owner_uid != getuid()
        || snapshot.deadline != slot->deadline) {
        wipe_snapshot(&snapshot);
        errno = ECANCELED;
        return -1;
    }
    wipe_snapshot(&snapshot);
    return 0;
}

static int fill_host_evidence(const struct callback_slot *slot, int classification,
    const char *key_type, const char *fingerprint,
    const unsigned char base_digest[32], const unsigned char replacement_digest[32],
    struct ctos_ssh_host_evidence *evidence) {
    struct ctos_ssh_role_snapshot snapshot;
    int error;

    if (slot == NULL || evidence == NULL || key_type == NULL || fingerprint == NULL
        || base_digest == NULL || replacement_digest == NULL
        || classification < CTOS_SSH_CALLBACK_HOST_KNOWN
        || classification > CTOS_SSH_CALLBACK_HOST_INVALID
        || valid_key_type(key_type) != 0 || valid_fingerprint(fingerprint) != 0) {
        errno = EINVAL;
        return -1;
    }
    memset(&snapshot, 0, sizeof(snapshot));
    memset(evidence, 0, sizeof(*evidence));
    if (ctos_ssh_role_bootstrap_snapshot(slot->role, &snapshot) != 0) {
        error = errno;
        wipe_snapshot(&snapshot);
        errno = error;
        return -1;
    }
    if (snapshot.token != slot->role || snapshot.owner_pid != getpid()
        || snapshot.owner_uid != getuid() || snapshot.deadline != slot->deadline
        || snapshot.hop >= CTOS_SSH_ROLE_MAX || snapshot.port == 0
        || snapshot.port > 65535U
        || copy_role_field(evidence->connection_id, sizeof(evidence->connection_id),
            snapshot.connection_id, CTOS_SSH_ROLE_CONNECTION_ID_BYTES) != 0
        || copy_role_field(evidence->target_identity, sizeof(evidence->target_identity),
            snapshot.target_identity, CTOS_SSH_ROLE_TARGET_ID_BYTES) != 0
        || copy_role_field(evidence->host, sizeof(evidence->host), snapshot.host,
            CTOS_SSH_ROLE_HOST_BYTES) != 0) {
        error = errno == 0 ? EINVAL : errno;
        wipe_snapshot(&snapshot);
        wipe_bytes((unsigned char *)evidence, sizeof(*evidence));
        errno = error;
        return -1;
    }
    evidence->role = snapshot.token;
    evidence->hop = snapshot.hop;
    evidence->port = snapshot.port;
    evidence->classification = classification;
    memcpy(evidence->key_type, key_type, strlen(key_type) + 1U);
    memcpy(evidence->fingerprint, fingerprint, strlen(fingerprint) + 1U);
    memcpy(evidence->base_digest, base_digest, sizeof(evidence->base_digest));
    memcpy(evidence->replacement_digest, replacement_digest,
        sizeof(evidence->replacement_digest));
    wipe_snapshot(&snapshot);
    return 0;
}

static int validate_host_result(const struct ctos_ssh_host_evidence *evidence,
    const struct ctos_ssh_host_result *result) {
    int expected_cas;
    if (evidence == NULL || result == NULL || result->role != evidence->role
        || result->hop != evidence->hop || result->port != evidence->port
        || result->classification != evidence->classification
        || result->outcome != CTOS_SSH_CALLBACK_HOST_RESULT_APPROVED
        || same_field(result->connection_id, evidence->connection_id,
            CTOS_SSH_ROLE_CONNECTION_ID_BYTES) != 0
        || same_field(result->target_identity, evidence->target_identity,
            CTOS_SSH_ROLE_TARGET_ID_BYTES) != 0
        || same_field(result->host, evidence->host, CTOS_SSH_ROLE_HOST_BYTES) != 0
        || same_field(result->key_type, evidence->key_type,
            CTOS_SSH_CALLBACK_HOST_KEY_TYPE_BYTES - 1U) != 0
        || same_field(result->fingerprint, evidence->fingerprint,
            CTOS_SSH_CALLBACK_HOST_FINGERPRINT_BYTES - 1U) != 0
        || valid_binding(result->proposal_binding,
            evidence->classification != CTOS_SSH_CALLBACK_HOST_NEW) != 0
        || memcmp(result->base_digest, evidence->base_digest,
            sizeof(evidence->base_digest)) != 0
        || memcmp(result->replacement_digest, evidence->replacement_digest,
            sizeof(evidence->replacement_digest)) != 0) {
        errno = EINVAL;
        return -1;
    }
    expected_cas = evidence->classification == CTOS_SSH_CALLBACK_HOST_NEW
        ? CTOS_SSH_CALLBACK_HOST_CAS_COMMITTED
        : CTOS_SSH_CALLBACK_HOST_CAS_NOT_REQUIRED;
    if (result->cas_status != expected_cas) {
        errno = EINVAL;
        return -1;
    }
    return 0;
}

static int open_role_valid(ctos_ssh_role_token role, int owner_uid,
    uint64_t deadline) {
    struct ctos_ssh_role_snapshot snapshot;
    uint64_t current;
    int error = EINVAL;
    memset(&snapshot, 0, sizeof(snapshot));
    if (role == 0 || owner_uid < 0
        || (uint64_t)(uint32_t)owner_uid != (uint64_t)getuid()) {
        errno = EACCES;
        return -1;
    }
    if (now_millis(&current) != 0) return -1;
    if (deadline == 0 || deadline <= current) {
        errno = ETIMEDOUT;
        return -1;
    }
    if (ctos_ssh_role_bootstrap_snapshot(role, &snapshot) != 0) {
        error = errno;
        wipe_snapshot(&snapshot);
        errno = error == ETIMEDOUT ? ETIMEDOUT : EACCES;
        return -1;
    }
    if (snapshot.token != role || snapshot.owner_pid != getpid()
        || snapshot.owner_uid != getuid() || snapshot.deadline != deadline) {
        wipe_snapshot(&snapshot);
        errno = EACCES;
        return -1;
    }
    wipe_snapshot(&snapshot);
    return 0;
}

static struct callback_slot *lookup_locked(ctos_ssh_callback_token callback) {
    if (callback == 0) return NULL;
    for (size_t index = 0; index < CTOS_SSH_CALLBACK_MAX; index++) {
        if (callbacks[index].live && callbacks[index].callback == callback)
            return &callbacks[index];
    }
    return NULL;
}

static int valid_target(const struct ctos_ssh_callback_target *target) {
    return target != NULL && target->context != NULL
        && target->validate_environment != NULL
        && (target->host_key != NULL || target->host_evidence != NULL)
        && (target->known_hosts_candidate == NULL
            || target->known_hosts_candidate_abort != NULL)
        && target->kex_complete != NULL && target->credential != NULL
        && target->release != NULL;
}

static int begin_callback(ctos_ssh_callback_token callback, void *environment,
    struct callback_slot **slot_out, struct ctos_ssh_callback_target *target_out) {
    struct callback_slot *slot;
    if (slot_out == NULL || target_out == NULL) { errno = EINVAL; return -1; }
    *slot_out = NULL;
    memset(target_out, 0, sizeof(*target_out));
    if (pthread_mutex_lock(&callbacks_lock) != 0) { errno = EBUSY; return -1; }
    slot = lookup_locked(callback);
    if (slot == NULL) { pthread_mutex_unlock(&callbacks_lock); errno = ENOENT; return -1; }
    if (!pthread_equal(slot->event_thread, pthread_self())) {
        pthread_mutex_unlock(&callbacks_lock); errno = EPERM; return -1;
    }
    if (slot->target.validate_environment(slot->target.context, environment) != 0) {
        pthread_mutex_unlock(&callbacks_lock); errno = EPERM; return -1;
    }
    if (slot->in_callback) {
        slot->cancelled = 1;
        slot->failed = 1;
        pthread_mutex_unlock(&callbacks_lock); errno = EALREADY; return -1;
    }
    if (slot->cancelled || slot->closing || slot->failed) {
        pthread_mutex_unlock(&callbacks_lock); errno = ECANCELED; return -1;
    }
    if (role_status(callback, slot) != 0) {
        int error = errno;
        if (error != ETIMEDOUT || slot->transport_owner == NULL) {
            slot->cancelled = 1;
            slot->failed = 1;
        }
        pthread_mutex_unlock(&callbacks_lock);
        errno = error;
        return -1;
    }
    slot->in_callback = 1;
    *slot_out = slot;
    *target_out = slot->target;
    pthread_mutex_unlock(&callbacks_lock);
    return 0;
}

static int finish_callback(struct callback_slot *slot, int callback_result) {
    int result = callback_result;
    if (pthread_mutex_lock(&callbacks_lock) != 0) { errno = EBUSY; return -1; }
    if (slot == NULL || !slot->live || !slot->in_callback) {
        pthread_mutex_unlock(&callbacks_lock); errno = ECANCELED; return -1;
    }
    slot->in_callback = 0;
    if (result == 0 && (slot->cancelled || slot->closing || slot->failed)) result = ECANCELED;
    if (result == 0 && role_status(slot->callback, slot) != 0) {
        result = errno;
        if (result != ETIMEDOUT || slot->transport_owner == NULL) {
            slot->cancelled = 1;
            slot->failed = 1;
        }
    }
    if (result != 0) {
        if (result != ETIMEDOUT || slot->transport_owner == NULL) {
            slot->cancelled = 1;
            slot->failed = 1;
        }
        if (result < 1 || result > 4095) result = EIO;
        errno = result;
    }
    pthread_mutex_unlock(&callbacks_lock);
    return result == 0 ? 0 : -1;
}

/* Candidate transfer is deliberately one-shot.  Claim it before invoking
 * Java so re-entry or a duplicate call cannot install a second body. */
static int claim_known_hosts_candidate(struct callback_slot *slot) {
    if (slot == NULL) { errno = EINVAL; return -1; }
    if (pthread_mutex_lock(&callbacks_lock) != 0) { errno = EBUSY; return -1; }
    if (!slot->live || !slot->in_callback) {
        pthread_mutex_unlock(&callbacks_lock);
        errno = ECANCELED;
        return -1;
    }
    if (slot->cancelled || slot->closing || slot->failed) {
        slot->in_callback = 0;
        pthread_mutex_unlock(&callbacks_lock);
        errno = ECANCELED;
        return -1;
    }
    if (slot->known_hosts_candidate_used) {
        slot->in_callback = 0;
        slot->cancelled = 1;
        slot->failed = 1;
        pthread_mutex_unlock(&callbacks_lock);
        errno = EALREADY;
        return -1;
    }
    slot->known_hosts_candidate_used = 1;
    pthread_mutex_unlock(&callbacks_lock);
    return 0;
}

static int abort_known_hosts_candidate(struct callback_slot *slot,
    void *environment) {
    struct ctos_ssh_callback_target target;
    int callback_result;

    memset(&target, 0, sizeof(target));
    if (pthread_mutex_lock(&callbacks_lock) != 0) {
        errno = EBUSY;
        return -1;
    }
    if (slot == NULL || !slot->live
        || slot->target.validate_environment(slot->target.context, environment) != 0) {
        pthread_mutex_unlock(&callbacks_lock);
        errno = EPERM;
        return -1;
    }
    if (!pthread_equal(slot->event_thread, pthread_self())) {
        pthread_mutex_unlock(&callbacks_lock);
        errno = EPERM;
        return -1;
    }
    if (slot->in_callback || slot->closing) {
        pthread_mutex_unlock(&callbacks_lock);
        errno = EBUSY;
        return -1;
    }
    if (!slot->known_hosts_candidate_used) {
        pthread_mutex_unlock(&callbacks_lock);
        errno = ENOENT;
        return -1;
    }
    if (slot->known_hosts_candidate_aborted) {
        pthread_mutex_unlock(&callbacks_lock);
        return 0;
    }
    if (slot->target.known_hosts_candidate_abort == NULL) {
        pthread_mutex_unlock(&callbacks_lock);
        errno = ENOSYS;
        return -1;
    }
    slot->known_hosts_candidate_aborted = 1;
    target = slot->target;
    pthread_mutex_unlock(&callbacks_lock);

    callback_result = target.known_hosts_candidate_abort(target.context, environment);
    if (callback_result != 0) {
        if (pthread_mutex_lock(&callbacks_lock) == 0) {
            slot->known_hosts_candidate_aborted = 0;
            pthread_mutex_unlock(&callbacks_lock);
        }
        errno = callback_result >= 1 && callback_result <= 4095
            ? callback_result : EIO;
        return -1;
    }
    return 0;
}

int ctos_ssh_callback_open(const struct ctos_ssh_callback_target *target,
    ctos_ssh_role_token role, int owner_uid, uint64_t deadline,
    void *environment, ctos_ssh_callback_token *callback) {
    if (callback == NULL) { errno = EINVAL; return -1; }
    *callback = 0;
    if (!valid_target(target)) { errno = EINVAL; return -1; }
    if (open_role_valid(role, owner_uid, deadline) != 0) return -1;
    if (target->validate_environment(target->context, environment) != 0) {
        errno = EPERM;
        return -1;
    }

    if (pthread_mutex_lock(&callbacks_lock) != 0) { errno = EBUSY; return -1; }
    /* The first validation can race role close or deadline expiry while the
     * callback table is contended. Revalidate after taking the table lock and
     * before publishing a live binding. */
    if (open_role_valid(role, owner_uid, deadline) != 0) {
        int error = errno;
        pthread_mutex_unlock(&callbacks_lock);
        errno = error;
        return -1;
    }
    size_t free_slot = CTOS_SSH_CALLBACK_MAX;
    for (size_t index = 0; index < CTOS_SSH_CALLBACK_MAX; index++) {
        if (!callbacks[index].live) { free_slot = index; break; }
    }
    if (free_slot == CTOS_SSH_CALLBACK_MAX) {
        pthread_mutex_unlock(&callbacks_lock); errno = EBUSY; return -1;
    }
    if (next_callback == UINT64_MAX) {
        pthread_mutex_unlock(&callbacks_lock); errno = EOVERFLOW; return -1;
    }
    struct callback_slot *slot = &callbacks[free_slot];
    memset(slot, 0, sizeof(*slot));
    slot->live = 1;
    slot->callback = ++next_callback;
    slot->role = role;
    slot->owner_uid = (uid_t)owner_uid;
    slot->event_thread = pthread_self();
    slot->deadline = deadline;
    slot->target = *target;
    *callback = slot->callback;
    pthread_mutex_unlock(&callbacks_lock);
    return 0;
}

int ctos_ssh_callback_cancel(ctos_ssh_callback_token callback) {
    if (pthread_mutex_lock(&callbacks_lock) != 0) { errno = EBUSY; return -1; }
    struct callback_slot *slot = lookup_locked(callback);
    if (slot == NULL) { pthread_mutex_unlock(&callbacks_lock); errno = ENOENT; return -1; }
    slot->cancelled = 1;
    pthread_mutex_unlock(&callbacks_lock);
    return 0;
}

int ctos_ssh_callback_close(ctos_ssh_callback_token callback, void *environment) {
    struct ctos_ssh_callback_target target;
    struct callback_slot *slot;
    if (pthread_mutex_lock(&callbacks_lock) != 0) { errno = EBUSY; return -1; }
    slot = lookup_locked(callback);
    if (slot == NULL) { pthread_mutex_unlock(&callbacks_lock); errno = ENOENT; return -1; }
    if (!pthread_equal(slot->event_thread, pthread_self())
        || slot->target.validate_environment(slot->target.context, environment) != 0) {
        pthread_mutex_unlock(&callbacks_lock); errno = EPERM; return -1;
    }
    if (slot->in_callback || slot->closing) {
        pthread_mutex_unlock(&callbacks_lock); errno = EBUSY; return -1;
    }
    slot->closing = 1;
    slot->cancelled = 1;
    if (slot->transport_owner != NULL) {
        if (ctos_ssh_role_transport_release(slot->role) != 0 && errno != ENOENT) {
            slot->closing = 0;
            slot->failed = 1;
            pthread_mutex_unlock(&callbacks_lock);
            return -1;
        }
        slot->transport_owner = NULL;
    }
    target = slot->target;
    pthread_mutex_unlock(&callbacks_lock);

    if (target.release(target.context, environment) != 0) {
        if (pthread_mutex_lock(&callbacks_lock) == 0) {
            slot->closing = 0;
            slot->failed = 1;
            pthread_mutex_unlock(&callbacks_lock);
        }
        errno = EIO;
        return -1;
    }
    if (pthread_mutex_lock(&callbacks_lock) != 0) { errno = EBUSY; return -1; }
    if (!slot->live || slot->callback != callback || !slot->closing) {
        pthread_mutex_unlock(&callbacks_lock); errno = ECANCELED; return -1;
    }
    target = slot->target;
    memset(slot, 0, sizeof(*slot));
    pthread_mutex_unlock(&callbacks_lock);
    if (target.destroy != NULL) target.destroy(target.context);
    return 0;
}

int ctos_ssh_callback_check_live(ctos_ssh_callback_token callback, void *environment) {
    struct callback_slot *slot;
    struct ctos_ssh_callback_target target;

    if (begin_callback(callback, environment, &slot, &target) != 0) return -1;
    return finish_callback(slot, 0);
}

int ctos_ssh_callback_promote_transport(ctos_ssh_callback_token callback,
    void *environment, const void *transport_owner) {
    struct callback_slot *slot;
    int error;
    if (transport_owner == NULL) { errno = EINVAL; return -1; }
    if (pthread_mutex_lock(&callbacks_lock) != 0) { errno = EBUSY; return -1; }
    slot = lookup_locked(callback);
    if (slot == NULL) { error = ENOENT; goto failed; }
    if (!pthread_equal(slot->event_thread, pthread_self())
        || slot->target.validate_environment(slot->target.context, environment) != 0) {
        error = EPERM; goto failed;
    }
    if (slot->in_callback || slot->transport_owner != NULL) { error = EALREADY; goto failed; }
    if (slot->cancelled || slot->closing || slot->failed) { error = ECANCELED; goto failed; }
    if (role_status(callback, slot) != 0 || ctos_ssh_role_transport_retain(slot->role) != 0) {
        error = errno; goto failed;
    }
    slot->transport_owner = transport_owner;
    pthread_mutex_unlock(&callbacks_lock);
    return 0;
failed:
    pthread_mutex_unlock(&callbacks_lock);
    errno = error;
    return -1;
}

int ctos_ssh_callback_check_transport(ctos_ssh_callback_token callback,
    void *environment, const void *transport_owner) {
    struct callback_slot *slot;
    struct ctos_ssh_role_snapshot snapshot = {0};
    int error;
    if (transport_owner == NULL) { errno = EINVAL; return -1; }
    if (pthread_mutex_lock(&callbacks_lock) != 0) { errno = EBUSY; return -1; }
    slot = lookup_locked(callback);
    if (slot == NULL) { error = ENOENT; goto failed; }
    if (!pthread_equal(slot->event_thread, pthread_self())
        || slot->target.validate_environment(slot->target.context, environment) != 0) {
        error = EPERM; goto failed;
    }
    if (slot->in_callback) { error = EALREADY; goto failed; }
    if (slot->cancelled || slot->closing || slot->failed
        || slot->transport_owner != transport_owner) { error = ECANCELED; goto failed; }
    if (ctos_ssh_role_transport_snapshot(slot->role, &snapshot) != 0) {
        error = errno; goto failed;
    }
    if (snapshot.token != slot->role || snapshot.owner_pid != getpid()
        || snapshot.owner_uid != getuid() || snapshot.deadline != slot->deadline) {
        error = ECANCELED; goto failed;
    }
    wipe_snapshot(&snapshot);
    pthread_mutex_unlock(&callbacks_lock);
    return 0;
failed:
    wipe_snapshot(&snapshot);
    pthread_mutex_unlock(&callbacks_lock);
    errno = error;
    return -1;
}

int ctos_ssh_callback_host_key(ctos_ssh_callback_token callback, void *environment,
    int classification, const char *key_type, const char *fingerprint,
    const unsigned char base_digest[32], const unsigned char replacement_digest[32]) {
    struct callback_slot *slot;
    struct ctos_ssh_callback_target target;
    if (classification < CTOS_SSH_CALLBACK_HOST_KNOWN
        || classification > CTOS_SSH_CALLBACK_HOST_INVALID
        || key_type == NULL || fingerprint == NULL || base_digest == NULL
        || replacement_digest == NULL) { errno = EINVAL; return -1; }
    if (begin_callback(callback, environment, &slot, &target) != 0) return -1;
    if (target.host_key == NULL) return finish_callback(slot, ENOSYS);
    int result = target.host_key(target.context, environment, classification,
        key_type, fingerprint, base_digest, replacement_digest);
    return finish_callback(slot, result);
}

int ctos_ssh_callback_host_evidence(ctos_ssh_callback_token callback, void *environment,
    int classification, const char *key_type, const char *fingerprint,
    const unsigned char base_digest[32], const unsigned char replacement_digest[32],
    struct ctos_ssh_host_result *result) {
    struct callback_slot *slot;
    struct ctos_ssh_callback_target target;
    struct ctos_ssh_host_evidence evidence;
    int callback_result;
    int error;

    if (result == NULL) { errno = EINVAL; return -1; }
    memset(result, 0, sizeof(*result));
    memset(&evidence, 0, sizeof(evidence));
    if (classification < CTOS_SSH_CALLBACK_HOST_KNOWN
        || classification > CTOS_SSH_CALLBACK_HOST_INVALID
        || key_type == NULL || fingerprint == NULL || base_digest == NULL
        || replacement_digest == NULL) {
        errno = EINVAL;
        return -1;
    }
    if (begin_callback(callback, environment, &slot, &target) != 0) return -1;
    if (target.host_evidence == NULL) {
        callback_result = finish_callback(slot, ENOSYS);
        wipe_bytes((unsigned char *)result, sizeof(*result));
        return callback_result;
    }
    if (fill_host_evidence(slot, classification, key_type, fingerprint,
            base_digest, replacement_digest, &evidence) != 0) {
        error = errno == 0 ? EINVAL : errno;
        wipe_bytes((unsigned char *)&evidence, sizeof(evidence));
        callback_result = finish_callback(slot, error);
        wipe_bytes((unsigned char *)result, sizeof(*result));
        return callback_result;
    }
    callback_result = target.host_evidence(target.context, environment, &evidence, result);
    if (callback_result == 0 && validate_host_result(&evidence, result) != 0) {
        callback_result = errno == 0 ? EINVAL : errno;
    }
    wipe_bytes((unsigned char *)&evidence, sizeof(evidence));
    if (callback_result != 0) wipe_bytes((unsigned char *)result, sizeof(*result));
    int finished = finish_callback(slot, callback_result);
    if (finished != 0) wipe_bytes((unsigned char *)result, sizeof(*result));
    return finished;
}

int ctos_ssh_callback_known_hosts_candidate(ctos_ssh_callback_token callback,
    void *environment, const char *candidate_id, int classification,
    const char *key_type, const char *fingerprint,
    const unsigned char base_digest[32],
    const unsigned char replacement_digest[32],
    unsigned char *replacement_body, size_t replacement_body_length) {
    struct callback_slot *slot = NULL;
    struct ctos_ssh_callback_target target;
    struct ctos_ssh_known_hosts_candidate candidate;
    int callback_result = EINVAL;
    int callback_started = 0;
    int error;

    memset(&target, 0, sizeof(target));
    memset(&candidate, 0, sizeof(candidate));
    if (replacement_body == NULL) {
        callback_result = EINVAL;
        goto done;
    }
    if (replacement_body_length == 0
        || replacement_body_length > CTOS_SSH_CALLBACK_MAX_FILE_BYTES
        || classification != CTOS_SSH_CALLBACK_HOST_NEW
        || valid_candidate_id(candidate_id) != 0
        || key_type == NULL || fingerprint == NULL
        || base_digest == NULL || replacement_digest == NULL
        || !nonzero32(base_digest) || !nonzero32(replacement_digest)
        || valid_key_type(key_type) != 0 || valid_fingerprint(fingerprint) != 0) {
        callback_result = errno == 0 ? EINVAL : errno;
        goto done;
    }
    if (begin_callback(callback, environment, &slot, &target) != 0) {
        callback_result = errno == 0 ? ECANCELED : errno;
        goto done;
    }
    callback_started = 1;
    if (claim_known_hosts_candidate(slot) != 0) {
        callback_result = errno == 0 ? EALREADY : errno;
        callback_started = 0; /* claim clears in_callback on rejection */
        goto done;
    }
    if (target.known_hosts_candidate == NULL) {
        callback_result = ENOSYS;
        goto finish;
    }
    if (fill_host_evidence(slot, classification, key_type, fingerprint,
            base_digest, replacement_digest, &candidate.evidence) != 0) {
        callback_result = errno == 0 ? EINVAL : errno;
        goto finish;
    }
    memcpy(candidate.candidate_id, candidate_id, strlen(candidate_id) + 1U);
    candidate.replacement_body = replacement_body;
    candidate.replacement_body_length = replacement_body_length;
    callback_result = target.known_hosts_candidate(target.context, environment, &candidate);
finish:
    if (callback_started) {
        error = callback_result;
        if (finish_callback(slot, callback_result) != 0) {
            int finish_error = errno == 0 ? (error == 0 ? EIO : error) : errno;
            /* The Java/provider side may have accepted the body before a
             * native cancellation, expiry, or close was observed. Release
             * only that managed candidate before wiping the native body. */
            (void)abort_known_hosts_candidate(slot, environment);
            callback_result = finish_error;
        } else {
            callback_result = 0;
        }
    }
done:
    wipe_known_hosts_candidate(&candidate);
    if (callback_result != 0) {
        if (replacement_body != NULL) {
            size_t length = replacement_body_length;
            if (length > CTOS_SSH_CALLBACK_MAX_FILE_BYTES) {
                length = CTOS_SSH_CALLBACK_MAX_FILE_BYTES;
            }
            wipe_bytes(replacement_body, length);
        }
        if (callback_result < 1 || callback_result > 4095) callback_result = EIO;
        errno = callback_result;
        return -1;
    }
    return 0;
}

int ctos_ssh_callback_known_hosts_candidate_abort(
    ctos_ssh_callback_token callback, void *environment) {
    struct callback_slot *slot;

    if (pthread_mutex_lock(&callbacks_lock) != 0) {
        errno = EBUSY;
        return -1;
    }
    slot = lookup_locked(callback);
    pthread_mutex_unlock(&callbacks_lock);
    if (slot == NULL) {
        errno = ENOENT;
        return -1;
    }
    return abort_known_hosts_candidate(slot, environment);
}

int ctos_ssh_callback_kex_complete(ctos_ssh_callback_token callback, void *environment,
    uint64_t cycle, const unsigned char session_digest[32]) {
    struct callback_slot *slot;
    struct ctos_ssh_callback_target target;
    if (cycle == 0 || session_digest == NULL || !nonzero32(session_digest)) {
        errno = EINVAL; return -1;
    }
    if (begin_callback(callback, environment, &slot, &target) != 0) return -1;
    int result = target.kex_complete(target.context, environment, cycle, session_digest);
    return finish_callback(slot, result);
}

int ctos_ssh_callback_credential(ctos_ssh_callback_token callback, void *environment,
    int kind) {
    struct callback_slot *slot;
    struct ctos_ssh_callback_target target;
    if (kind < CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD
        || kind > CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY) { errno = EINVAL; return -1; }
    if (begin_callback(callback, environment, &slot, &target) != 0) return -1;
    int result = target.credential(target.context, environment, kind);
    return finish_callback(slot, result);
}

static int credential_bytes_common(ctos_ssh_callback_token callback,
    void *environment, int kind, const unsigned char selection_receipt[32],
    uint64_t cycle, const unsigned char session_digest[32], int selected,
    unsigned char *output, size_t capacity, size_t *length) {
    struct callback_slot *slot;
    struct ctos_ssh_callback_target target;
    int result;

    if (length != NULL) *length = 0;
    /* A caller-owned buffer is required even when the provider is absent so
     * every failure path can leave no stale bytes behind.  A capacity above
     * the contract limit is not traversed; only a bounded prefix is wiped. */
    if (output != NULL) {
        wipe_bytes(output, capacity > CTOS_SSH_CALLBACK_MAX_CREDENTIAL_BYTES
            ? CTOS_SSH_CALLBACK_MAX_CREDENTIAL_BYTES : capacity);
    }
    if (length == NULL || output == NULL || capacity == 0) {
        errno = EINVAL;
        return -1;
    }
    if (capacity > CTOS_SSH_CALLBACK_MAX_CREDENTIAL_BYTES) {
        errno = EOVERFLOW;
        return -1;
    }
    if (kind < CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD
        || kind > CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY) {
        errno = EINVAL;
        return -1;
    }
    if (selected && ((kind != CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD
            && kind != CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY)
        || selection_receipt == NULL || session_digest == NULL
        || cycle == 0 || cycle > INT64_MAX || !nonzero32(session_digest))) {
        errno = EINVAL;
        return -1;
    }
    if (begin_callback(callback, environment, &slot, &target) != 0) return -1;

    if (selected) {
        result = target.credential_bytes_selected == NULL ? ENOSYS
            : target.credential_bytes_selected(target.context, environment, kind,
                selection_receipt, cycle, session_digest, output, capacity, length);
        if (result == 0 && (*length == 0 || *length > capacity)) result = EINVAL;
    } else if (target.credential_bytes == NULL) {
        result = ENOSYS;
    } else {
        result = target.credential_bytes(target.context, environment, kind,
            output, capacity, length);
        if (result == 0 && (*length == 0 || *length > capacity)) result = EINVAL;
    }
    if (finish_callback(slot, result) != 0) {
        wipe_bytes(output, capacity);
        *length = 0;
        return -1;
    }
    if (result != 0) {
        wipe_bytes(output, capacity);
        *length = 0;
        if (result < 1 || result > 4095) errno = EIO;
        else errno = result;
        return -1;
    }
    return 0;
}

int ctos_ssh_callback_credential_bytes(ctos_ssh_callback_token callback,
    void *environment, int kind, unsigned char *output, size_t capacity,
    size_t *length) {
    return credential_bytes_common(callback, environment, kind, NULL, 0, NULL, 0,
        output, capacity, length);
}

int ctos_ssh_callback_credential_bytes_selected(ctos_ssh_callback_token callback,
    void *environment, int kind, const unsigned char selection_receipt[32],
    uint64_t cycle, const unsigned char session_digest[32], unsigned char *output,
    size_t capacity, size_t *length) {
    return credential_bytes_common(callback, environment, kind, selection_receipt,
        cycle, session_digest, 1, output, capacity, length);
}

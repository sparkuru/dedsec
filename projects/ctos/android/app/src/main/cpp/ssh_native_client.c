#define _GNU_SOURCE

#include "ssh_native_client.h"
#include "ssh_hostfile_issuer.h"
#include "ssh_key_policy.h"

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#include "digest.h"
#include "includes.h"
#include "dispatch.h"
#include "compat.h"
#include "kex.h"
#include "myproposal.h"
#include "packet.h"
#include "ssh2.h"
#include "ssh_api.h"
#include "ssh_kex_guard.h"
#include "sshbuf.h"
#include "ssherr.h"
#include "sshkey.h"

/* The pinned generated config may define unavailable allocator shims. The
 * small native owner object and verifier scratch use the platform allocator. */
#ifdef malloc
#undef malloc
#endif
#ifdef calloc
#undef calloc
#endif
#ifdef realloc
#undef realloc
#endif

enum userauth_service_phase {
    USERAUTH_SERVICE_DISABLED,
    USERAUTH_SERVICE_REQUEST_PENDING,
    USERAUTH_SERVICE_AWAIT_ACCEPT,
    USERAUTH_SERVICE_READY
};

enum userauth_phase {
    AUTH_DISABLED,
    AUTH_REQUEST_PENDING,
    AUTH_AWAIT_REPLY,
    AUTH_FINISHED
};

struct ctos_ssh_native_client {
    struct ssh *ssh;
    struct sshkey *server_host_public_key;
    enum ctos_ssh_native_host_key_mode host_key_mode;
    int in_transport_step;
    ctos_ssh_role_token role;
    ctos_ssh_callback_token callback;
    void *callback_environment;
    struct ctos_ssh_view_borrow *managed_view_borrow;
    const struct ctos_ssh_view *managed_view;
    int socket_fd;
    int caller_socket_fd;
    int closed;
    int failed;
    int completed;
    int candidate_handed_off;
    int kex_released;
    int callback_closed;
    int view_released;
    unsigned int completion_calls;
    uint64_t completion_cycle;
    unsigned char completion_session_digest[32];
    enum userauth_service_phase service_phase;
    uint64_t deadline;
    unsigned char expected_host_fingerprint[CTOS_SSH_NATIVE_CLIENT_FINGERPRINT_BYTES];
    char known_hosts_path[513];
    char host[CTOS_SSH_ROLE_HOST_BYTES + 1U];
    char candidate_id[CTOS_SSH_CALLBACK_CANDIDATE_ID_BYTES];
    size_t step_budget;
    unsigned char *authentication_selection_wire;
    size_t authentication_selection_wire_length;
    unsigned char authentication_selection_receipt[32];
    int authentication_selection_kind;
    unsigned int authentication_selection_version;
    int selected_credential_attempted;
    size_t authentication_username_offset;
    size_t authentication_username_length;
    size_t authentication_public_offset;
    size_t authentication_public_length;
    enum userauth_phase auth_phase;
    int auth_result;
    unsigned int auth_banners;
    int established;
    struct ssh *established_ssh;
    struct sshkey *established_host_key;
    struct ctos_ssh_role_snapshot established_role;
    uint64_t established_cycle;
    unsigned char established_session_digest[32];
    struct ctos_ssh_native_shell_status shell;
    unsigned int shell_columns, shell_rows;
    uint32_t remote_channel, remote_window, remote_maxpacket, local_window, window_credit;
    int shell_request_drained, remote_close, local_close, shell_rejected_pending, shell_transport_eof;
    unsigned char shell_buffer[65536];
};

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

static void wipe_bytes(void *memory, size_t length) {
    volatile unsigned char *bytes = memory;

    if (bytes == NULL) return;
    while (length-- != 0) *bytes++ = 0;
}

struct selection_cursor {
    const unsigned char *position;
    size_t remaining;
};

static int selection_u32(struct selection_cursor *cursor, uint32_t *value) {
    const unsigned char *p = cursor->position;

    if (cursor->remaining < 4U) return -1;
    *value = ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16)
        | ((uint32_t)p[2] << 8) | (uint32_t)p[3];
    cursor->position += 4U;
    cursor->remaining -= 4U;
    return 0;
}

static int selection_field(struct selection_cursor *cursor, struct ctos_ssh_slice *field) {
    uint32_t length;

    if (selection_u32(cursor, &length) != 0 || (size_t)length > cursor->remaining)
        return -1;
    field->data = cursor->position;
    field->length = length;
    cursor->position += length;
    cursor->remaining -= length;
    return 0;
}

/* Scalar UTF-8, including C1 ISO controls rejected by the managed encoder. */
static int selection_text(struct ctos_ssh_slice field, size_t maximum) {
    size_t index = 0;

    if (field.length == 0 || field.length > maximum) return -1;
    while (index < field.length) {
        unsigned int ch = field.data[index++];
        unsigned int extra;
        unsigned int scalar;

        if (ch < 0x80U) {
            if (ch < 0x20U || ch == 0x7fU) return -1;
            continue;
        }
        if (ch >= 0xc2U && ch <= 0xdfU) { extra = 1; scalar = ch & 0x1fU; }
        else if (ch >= 0xe0U && ch <= 0xefU) { extra = 2; scalar = ch & 0x0fU; }
        else if (ch >= 0xf0U && ch <= 0xf4U) { extra = 3; scalar = ch & 7U; }
        else return -1;
        if (extra > field.length - index) return -1;
        for (unsigned int part = 0; part < extra; part++) {
            ch = field.data[index++];
            if ((ch & 0xc0U) != 0x80U) return -1;
            scalar = (scalar << 6) | (ch & 0x3fU);
        }
        if ((extra == 1U && scalar < 0x80U) || (extra == 2U && scalar < 0x800U)
            || (extra == 3U && scalar < 0x10000U) || scalar > 0x10ffffU
            || (scalar >= 0xd800U && scalar <= 0xdfffU)
            || (scalar >= 0x7fU && scalar <= 0x9fU)) return -1;
    }
    return 0;
}

static int selection_identifier(struct ctos_ssh_slice field) {
    if (field.length == 0 || field.length > 128U) return -1;
    for (size_t index = 0; index < field.length; index++) {
        unsigned char ch = field.data[index];
        if (!((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
            || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-')) return -1;
    }
    return 0;
}

static int copy_authentication_selection(struct ctos_ssh_native_client *client,
    const struct ctos_ssh_native_client_config *config,
    const struct ctos_ssh_role_snapshot *snapshot) {
    const unsigned char *wire = config->authentication_selection_wire;
    size_t length = config->authentication_selection_wire_length;
    struct selection_cursor cursor;
    struct ctos_ssh_slice fields[5];
    uint32_t magic, version_flags, token_high, token_low, hop, kind;
    uint64_t token;

    if (wire == NULL && length == 0) return 0;
    if (wire == NULL || length < 108U
        || length > CTOS_SSH_NATIVE_CLIENT_MAX_SELECTION_WIRE_BYTES) goto invalid;
    cursor = (struct selection_cursor){wire, length};
    if (selection_u32(&cursor, &magic) != 0 || magic != 0x43534153U
        || selection_u32(&cursor, &version_flags) != 0
        || (version_flags != 0x00010000U && version_flags != 0x00020000U)
        || selection_u32(&cursor, &token_high) != 0
        || selection_u32(&cursor, &token_low) != 0
        || selection_u32(&cursor, &hop) != 0) goto invalid;
    token = ((uint64_t)token_high << 32) | token_low;
    if (token == 0 || token > INT64_MAX) goto invalid;
    if (token != snapshot->token || hop != snapshot->hop
        || memcmp(cursor.position, snapshot->role_nonce, 32U) != 0
        || memcmp(cursor.position + 32U, snapshot->connection_binding, 32U) != 0) {
        errno = EACCES;
        return -1;
    }
    cursor.position += 64U;
    cursor.remaining -= 64U;
    if (selection_u32(&cursor, &kind) != 0 || (kind != 1U && kind != 4U)) goto invalid;
    for (size_t index = 0; index < 5U; index++) {
        if (selection_field(&cursor, &fields[index]) != 0) goto invalid;
    }
    if (cursor.remaining != 0 || selection_text(fields[0], 255U) != 0) goto invalid;
    if (kind == 1U) {
        if (version_flags != 0x00010000U || selection_identifier(fields[1]) != 0 || fields[2].length != 0
            || fields[3].length != 0 || fields[4].length != 0) goto invalid;
    } else if ((version_flags == 0x00010000U ? fields[1].length != 0 : selection_identifier(fields[1]) != 0)
        || selection_text(fields[2], 512U) != 0
        || ctos_ssh_view_path(fields[2].data, fields[2].length, 0) != 0
        || selection_text(fields[3], 512U) != 0 || fields[4].length == 0
        || fields[4].length > CTOS_SSH_VIEW_FILE_BYTES) goto invalid;
    client->authentication_selection_wire = malloc(length);
    if (client->authentication_selection_wire == NULL) { errno = ENOMEM; return -1; }
    client->authentication_selection_wire_length = length;
    memcpy(client->authentication_selection_wire, wire, length);
    if (ssh_digest_memory(SSH_DIGEST_SHA256, client->authentication_selection_wire,
            length, client->authentication_selection_receipt, 32U) != 0) {
        errno = EIO;
        return -1;
    }
    client->authentication_selection_kind = (int)kind;
    client->authentication_selection_version = version_flags >> 16;
    client->authentication_username_offset = (size_t)(fields[0].data - wire);
    client->authentication_username_length = fields[0].length;
    client->authentication_public_offset = (size_t)(fields[4].data - wire);
    client->authentication_public_length = fields[4].length;
    return 0;
invalid:
    errno = EINVAL;
    return -1;
}

static void clear_authentication_selection(struct ctos_ssh_native_client *client) {
    wipe_bytes(client->authentication_selection_wire, client->authentication_selection_wire_length);
    free(client->authentication_selection_wire);
    client->authentication_selection_wire = NULL;
    client->authentication_selection_wire_length = 0;
    wipe_bytes(client->authentication_selection_receipt, sizeof(client->authentication_selection_receipt));
    client->authentication_selection_kind = 0;
    client->authentication_selection_version = 0;
    client->authentication_username_offset = 0;
    client->authentication_username_length = 0;
    client->authentication_public_offset = 0;
    client->authentication_public_length = 0;
}

static int bounded_text(const char *value, size_t maximum, size_t *length_out) {
    size_t length;

    if (value == NULL) {
        errno = EINVAL;
        return -1;
    }
    length = strnlen(value, maximum + 1U);
    if (length == 0 || length > maximum) {
        errno = EINVAL;
        return -1;
    }
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

static int nonzero32(const unsigned char value[32]) {
    unsigned char combined = 0;

    if (value == NULL) return 0;
    for (size_t index = 0; index < 32; index++) combined |= value[index];
    return combined != 0;
}

static int copy_config_text(char *destination, size_t capacity,
    const char *source, size_t maximum) {
    size_t length;

    if (bounded_text(source, maximum, &length) != 0 || length >= capacity) {
        errno = EINVAL;
        return -1;
    }
    memcpy(destination, source, length + 1U);
    return 0;
}

static int role_is_current(const struct ctos_ssh_native_client_config *config,
    struct ctos_ssh_role_snapshot *snapshot) {
    uint64_t current;
    size_t host_length;

    memset(snapshot, 0, sizeof(*snapshot));
    if (config == NULL || config->host == NULL
        || bounded_text(config->host, CTOS_SSH_ROLE_HOST_BYTES, &host_length) != 0
        || config->role == 0 || config->deadline == 0
        || config->deadline > INT64_MAX) {
        errno = EACCES;
        return -1;
    }
    if (now_millis(&current) != 0) return -1;
    if (config->deadline <= current) {
        errno = EACCES;
        return -1;
    }
    if (ctos_ssh_role_bootstrap_snapshot(config->role, snapshot) != 0) {
        /* A missing or expired role is an admission denial. Keep operational
         * failures distinct from the client's role-policy error contract. */
        if (errno == ENOENT || errno == ETIMEDOUT) errno = EACCES;
        return -1;
    }
    if (snapshot->token != config->role
        || snapshot->owner_pid != getpid() || snapshot->owner_uid != getuid()
        || config->owner_uid != getuid()
        || snapshot->deadline != config->deadline
        || strcmp(snapshot->host, config->host) != 0) {
        errno = EACCES;
        return -1;
    }
    (void)host_length;
    return 0;
}

static int same_slice_text(struct ctos_ssh_slice slice, const char *value,
    size_t maximum) {
    size_t length;

    if (bounded_text(value, maximum, &length) != 0
        || slice.data == NULL
        || slice.length != length
        || memcmp(slice.data, value, length) != 0) {
        errno = EACCES;
        return -1;
    }
    return 0;
}

static int view_slices_are_safe(const struct ctos_ssh_view *view) {
    if (view == NULL || view->count == 0 || view->count > CTOS_SSH_VIEW_FILES
        || view->uuid.data == NULL || view->uuid.length != 16U
        || view->binding.data == NULL || view->binding.length != 32U
        || view->request.data == NULL || view->request.length == 0
        || view->request.length > 128U || view->connection.data == NULL
        || view->connection.length == 0 || view->connection.length > 512U
        || view->root.data == NULL || view->root.length == 0
        || view->root.length > 512U) {
        errno = EACCES;
        return -1;
    }
    for (unsigned int index = 0; index < view->count; index++) {
        const struct ctos_ssh_file *file = &view->files[index];
        if (file->id.data == NULL || file->id.length == 0
            || file->id.length > 128U || file->path.data == NULL
            || file->path.length == 0 || file->path.length > 512U
            || file->identity.data == NULL || file->identity.length == 0
            || file->identity.length > 512U || file->body.length > CTOS_SSH_VIEW_FILE_BYTES
            || (file->body.length != 0 && file->body.data == NULL)
            || file->ref_count > CTOS_SSH_VIEW_FILES) {
            errno = EACCES;
            return -1;
        }
        for (unsigned int ref = 0; ref < file->ref_count; ref++) {
            if (file->refs[ref].data == NULL || file->refs[ref].length == 0
                || file->refs[ref].length > 512U) {
                errno = EACCES;
                return -1;
            }
        }
    }
    return 0;
}

static int view_borrow_is_live(const struct ctos_ssh_view_borrow *borrow) {
    if (borrow == NULL) {
        errno = EACCES;
        return -1;
    }
    if (ctos_ssh_view_borrow_live(borrow) != 0) {
        if (errno == 0) errno = EACCES;
        return -1;
    }
    return 0;
}

static int view_is_current(const struct ctos_ssh_native_client_config *config,
    const struct ctos_ssh_view *view,
    const struct ctos_ssh_role_snapshot *snapshot, size_t path_length) {
    static const unsigned char root_path[] = "ssh/config";
    const struct ctos_ssh_file *root;
    const struct ctos_ssh_file *known_hosts;
    int references_known_hosts = 0;

    if (config == NULL || view == NULL || snapshot == NULL
        || view_slices_are_safe(view) != 0
        || ctos_ssh_view_live(view) != 0
        || view->deadline < snapshot->deadline
        || same_slice_text(view->connection,
            snapshot->connection_id, CTOS_SSH_ROLE_CONNECTION_ID_BYTES) != 0
        || same_slice_text(view->root,
            (const char *)root_path, sizeof(root_path) - 1U) != 0) {
        errno = EACCES;
        return -1;
    }
    root = ctos_ssh_view_lookup(view, root_path,
        sizeof(root_path) - 1U, CTOS_SSH_CONFIG);
    known_hosts = ctos_ssh_view_lookup(view,
        (const unsigned char *)config->known_hosts_path, path_length,
        CTOS_SSH_KNOWN_HOSTS);
    if (root == NULL || known_hosts == NULL
        || known_hosts->body.length > CTOS_SSH_CALLBACK_MAX_FILE_BYTES
        || known_hosts->declared_bytes != known_hosts->body.length) {
        errno = EACCES;
        return -1;
    }
    for (unsigned int index = 0; index < root->ref_count; index++) {
        if (root->refs[index].data != NULL
            && root->refs[index].length == path_length
            && memcmp(root->refs[index].data, config->known_hosts_path,
                path_length) == 0) {
            references_known_hosts = 1;
            break;
        }
    }
    if (!references_known_hosts) {
        errno = EACCES;
        return -1;
    }
    return 0;
}

static int validate_descriptor_state(int fd, int require_close_on_exec) {
    int descriptor_flags;
    int flags;
    int socket_type;
    socklen_t socket_type_length = sizeof(socket_type);

    if (fd < 0 || (descriptor_flags = fcntl(fd, F_GETFD)) < 0
        || (require_close_on_exec && (descriptor_flags & FD_CLOEXEC) == 0)
        || (flags = fcntl(fd, F_GETFL)) < 0
        || (flags & O_ACCMODE) != O_RDWR || (flags & O_NONBLOCK) == 0
        || getsockopt(fd, SOL_SOCKET, SO_TYPE, &socket_type,
            &socket_type_length) != 0 || socket_type != SOCK_STREAM) {
        errno = EINVAL;
        return -1;
    }
    return 0;
}

static int validate_descriptor(int fd) {
    return validate_descriptor_state(fd, 1);
}

int ctos_ssh_native_client_prepare_tcp_descriptor(int fd) {
    int flags;
    /* The caller still owns this descriptor throughout, including every failure. */
    if (validate_descriptor_state(fd, 0) != 0) return -1;
    if ((flags = fcntl(fd, F_GETFD)) < 0
        || fcntl(fd, F_SETFD, flags | FD_CLOEXEC) != 0) return -1;
    return validate_descriptor(fd);
}

static int fingerprint_matches(struct ctos_ssh_native_client *client,
    const struct sshkey *host_key) {
    unsigned char *actual = NULL;
    size_t length = 0;
    int result;

    if (client == NULL || host_key == NULL
        || sshkey_fingerprint_raw(host_key, SSH_DIGEST_SHA256,
            &actual, &length) != 0 || length != 32) {
        wipe_bytes(actual, length);
        free(actual);
        errno = EACCES;
        return -1;
    }
    result = timingsafe_bcmp(actual, client->expected_host_fingerprint, 32) == 0;
    wipe_bytes(actual, length);
    free(actual);
    if (!result) {
        errno = EACCES;
        return -1;
    }
    return 0;
}

static int host_result_matches_issue(
    const struct ctos_ssh_native_client *client,
    const struct ctos_ssh_hostfile_issue *issue,
    const struct ctos_ssh_host_result *result) {
    struct ctos_ssh_role_snapshot snapshot;
    int expected_cas;
    int matches;

    memset(&snapshot, 0, sizeof(snapshot));
    if (client == NULL || issue == NULL || result == NULL
        || ctos_ssh_role_bootstrap_snapshot(client->role, &snapshot) != 0) {
        wipe_bytes(&snapshot, sizeof(snapshot));
        errno = EACCES;
        return -1;
    }
    expected_cas = issue->classification == CTOS_SSH_CALLBACK_HOST_NEW
        ? CTOS_SSH_CALLBACK_HOST_CAS_COMMITTED
        : CTOS_SSH_CALLBACK_HOST_CAS_NOT_REQUIRED;
    matches = result->role == client->role
        && result->hop == snapshot.hop && result->port == snapshot.port
        && result->classification == issue->classification
        && result->outcome == CTOS_SSH_CALLBACK_HOST_RESULT_APPROVED
        && result->cas_status == expected_cas
        && strcmp(result->connection_id, snapshot.connection_id) == 0
        && strcmp(result->target_identity, snapshot.target_identity) == 0
        && strcmp(result->host, snapshot.host) == 0
        && strcmp(result->host, client->host) == 0
        && strcmp(result->key_type, issue->key_type) == 0
        && strcmp(result->fingerprint, issue->fingerprint) == 0
        && memcmp(result->base_digest, issue->base_digest,
            sizeof(issue->base_digest)) == 0
        && memcmp(result->replacement_digest, issue->replacement_digest,
            sizeof(issue->replacement_digest)) == 0;
    wipe_bytes(&snapshot, sizeof(snapshot));
    if (!matches) {
        errno = EACCES;
        return -1;
    }
    return 0;
}

static int abort_handed_off_candidate(struct ctos_ssh_native_client *client) {
    if (client == NULL || !client->candidate_handed_off) return 0;
    if (ctos_ssh_callback_known_hosts_candidate_abort(
            client->callback, client->callback_environment) != 0) return -1;
    client->candidate_handed_off = 0;
    return 0;
}

static int offered_host_is_live(struct ctos_ssh_native_client *client) {
    struct ctos_ssh_role_snapshot snapshot = {0};
    uint64_t current;
    int result = -1;
    if (ctos_ssh_callback_check_live(client->callback, client->callback_environment) != 0
        || view_borrow_is_live(client->managed_view_borrow) != 0
        || ctos_ssh_role_bootstrap_snapshot(client->role, &snapshot) != 0) goto out;
    if (!client->in_transport_step || client->closed || client->failed || client->completed
        || client->completion_calls != 0 || client->server_host_public_key != NULL
        || client->ssh == NULL || client->ssh->kex == NULL || client->ssh->kex->done
        || snapshot.token != client->role || snapshot.owner_pid != getpid()
        || snapshot.owner_uid != getuid() || snapshot.deadline != client->deadline
        || strcmp(snapshot.host, client->host) != 0) {
        errno = EACCES;
        goto out;
    }
    if (now_millis(&current) != 0) goto out;
    if (current >= client->deadline) { errno = ETIMEDOUT; goto out; }
    result = 0;
out:
    wipe_bytes(&snapshot, sizeof(snapshot));
    return result;
}

static int verify_host_key(struct sshkey *host_key, struct ssh *ssh) {
    struct ctos_ssh_native_client *client;
    struct ctos_ssh_hostfile_issue issue;
    struct ctos_ssh_host_result result;
    unsigned char *replacement = NULL;
    unsigned char *offered_fingerprint = NULL;
    size_t offered_fingerprint_length = 0;
    struct sshkey *offered_key = NULL;
    FILE *stream = NULL;
    const size_t replacement_capacity = CTOS_SSH_CALLBACK_MAX_FILE_BYTES + 1U;
    int return_code = -1;
    int callback_result;
    int close_result;

    memset(&issue, 0, sizeof(issue));
    memset(&result, 0, sizeof(result));
    client = ssh == NULL ? NULL : ssh_get_app_data(ssh);
    if (client == NULL || client->closed || client->failed || client->completed
        || client->managed_view == NULL
        || view_borrow_is_live(client->managed_view_borrow) != 0 || host_key == NULL
        || (client->host_key_mode == CTOS_SSH_HOST_KEY_PINNED
            && fingerprint_matches(client, host_key) != 0)) {
        errno = EACCES;
        goto out;
    }
    if (client->host_key_mode == CTOS_SSH_HOST_KEY_MANAGED) {
        if (offered_host_is_live(client) != 0) goto out;
        if (sshkey_from_private(host_key, &offered_key) != 0 || offered_key == NULL
            || sshkey_fingerprint_raw(offered_key, SSH_DIGEST_SHA256,
                &offered_fingerprint, &offered_fingerprint_length) != 0
            || offered_fingerprint_length != 32U || !nonzero32(offered_fingerprint)) {
            errno = EACCES;
            goto out;
        }
    }
    replacement = malloc(replacement_capacity);
    if (replacement == NULL) {
        errno = ENOMEM;
        goto out;
    }
    memset(replacement, 0, replacement_capacity);
    stream = fmemopen(replacement, replacement_capacity, "w+");
    if (stream == NULL) {
        errno = EIO;
        goto out;
    }
    callback_result = ctos_ssh_hostfile_issue(client->managed_view, client->role,
        client->known_hosts_path, client->host, host_key, client->candidate_id,
        stream, replacement, replacement_capacity, client->callback,
        client->callback_environment, &issue);
    close_result = fclose(stream);
    stream = NULL;
    if (issue.handed_off) client->candidate_handed_off = 1;
    if (callback_result != 0) goto out;
    if (close_result != 0) {
        errno = EIO;
        goto out;
    }
    if (issue.classification != CTOS_SSH_CALLBACK_HOST_KNOWN
        && issue.classification != CTOS_SSH_CALLBACK_HOST_NEW) {
        errno = EACCES;
        goto out;
    }
    if (issue.classification == CTOS_SSH_CALLBACK_HOST_NEW
        && !issue.handed_off) {
        errno = EACCES;
        goto out;
    }
    callback_result = ctos_ssh_callback_host_evidence(client->callback,
        client->callback_environment, issue.classification, issue.key_type,
        issue.fingerprint, issue.base_digest, issue.replacement_digest,
        &result);
    if (callback_result != 0
        || host_result_matches_issue(client, &issue, &result) != 0) goto out;
    if (client->host_key_mode == CTOS_SSH_HOST_KEY_MANAGED) {
        if (offered_host_is_live(client) != 0
            || ctos_ssh_kex_pin_offered_host(client->ssh, offered_key) != 0) {
            if (errno == 0) errno = EACCES;
            goto out;
        }
        client->server_host_public_key = offered_key;
        offered_key = NULL;
        memcpy(client->expected_host_fingerprint, offered_fingerprint, 32U);
    }
    return_code = 0;
out:
    if (stream != NULL) {
        if (fclose(stream) != 0 && return_code == 0) {
            return_code = -1;
            errno = EIO;
        }
        stream = NULL;
    }
    if (return_code != 0 && client != NULL && client->candidate_handed_off) {
        int saved_errno = errno == 0 ? EIO : errno;
        (void)abort_handed_off_candidate(client);
        errno = saved_errno;
    }
    if (return_code != 0 && client != NULL) client->failed = 1;
    wipe_bytes(&result, sizeof(result));
    wipe_bytes(&issue, sizeof(issue));
    sshkey_free(offered_key);
    wipe_bytes(offered_fingerprint, offered_fingerprint_length);
    free(offered_fingerprint);
    if (replacement != NULL) {
        wipe_bytes(replacement, replacement_capacity);
        free(replacement);
    }
    return return_code;
}

static int completion_digest(struct ctos_ssh_native_client *client) {
    struct ctos_ssh_role_snapshot snapshot;
    unsigned char digest[32];
    uint64_t cycle;
    int result = -1;

    memset(&snapshot, 0, sizeof(snapshot));
    memset(digest, 0, sizeof(digest));
    if (client == NULL || client->ssh == NULL || client->ssh->kex == NULL
        || !client->ssh->kex->done || client->completion_calls != 0U
        || ssh_digest_buffer(SSH_DIGEST_SHA256, client->ssh->kex->session_id,
            digest, sizeof(digest)) != 0 || !nonzero32(digest)
        || ctos_ssh_role_bootstrap_snapshot(client->role, &snapshot) != 0
        || (cycle = ctos_ssh_kex_ready(client->ssh, snapshot.hop,
            snapshot.role_nonce, snapshot.connection_binding)) == 0) {
        errno = EIO;
        goto out;
    }
    if (ctos_ssh_callback_kex_complete(client->callback,
            client->callback_environment, cycle, digest) != 0) {
        errno = EIO;
        goto out;
    }
    client->completion_calls++;
    if (client->completion_calls != 1U) {
        errno = EALREADY;
        goto out;
    }
    client->completed = 1;
    client->completion_cycle = cycle;
    memcpy(client->completion_session_digest, digest, sizeof(client->completion_session_digest));
    result = 0;
out:
    wipe_bytes(&snapshot, sizeof(snapshot));
    wipe_bytes(digest, sizeof(digest));
    return result;
}

static int terminal_error(struct ctos_ssh_native_client *client, int error) {
    if (client != NULL) {
        if (!client->completed && client->candidate_handed_off)
            (void)abort_handed_off_candidate(client);
        client->failed = 1;
    }
    errno = error;
    return -1;
}

static int service_is_live(struct ctos_ssh_native_client *client, int cancelled) {
    struct ctos_ssh_role_snapshot snapshot = {0};
    uint64_t current;
    uint64_t cycle;
    int error;

    /* Check ownership first: an alien caller cannot poison the owner's
     * session even when its view is revoked or it requests cancellation. */
    if (ctos_ssh_callback_check_live(client->callback,
            client->callback_environment) != 0) {
        error = errno;
        return error == EPERM ? -1 : terminal_error(client, error);
    }
    if (view_borrow_is_live(client->managed_view_borrow) != 0)
        return terminal_error(client, errno == 0 ? EACCES : errno);
    if (cancelled) return terminal_error(client, ECANCELED);
    if (now_millis(&current) != 0) return terminal_error(client, errno);
    if (current >= client->deadline) return terminal_error(client, ETIMEDOUT);
    if (!client->completed) return 0;
    if (ctos_ssh_role_bootstrap_snapshot(client->role, &snapshot) != 0) {
        error = errno;
        wipe_bytes(&snapshot, sizeof(snapshot));
        return terminal_error(client, error);
    }
    cycle = ctos_ssh_kex_ready(client->ssh, snapshot.hop,
        snapshot.role_nonce, snapshot.connection_binding);
    wipe_bytes(&snapshot, sizeof(snapshot));
    if (client->completion_calls != 1U || client->completion_cycle == 0
        || cycle != client->completion_cycle)
        return terminal_error(client, EACCES);
    return 0;
}

static int selected_credential_is_live(struct ctos_ssh_native_client *client) {
    unsigned char digest[32] = {0};
    int result = -1;

    if (service_is_live(client, 0) != 0) return -1;
    if (client->failed || !client->completed || client->completion_calls != 1U
        || client->completion_cycle == 0 || client->completion_cycle > INT64_MAX
        || client->service_phase != USERAUTH_SERVICE_READY
        || client->authentication_selection_wire == NULL
        || (client->authentication_selection_kind != CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD
            && client->authentication_selection_kind != CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY)
        || client->ssh == NULL || client->ssh->kex == NULL
        || !client->ssh->kex->done || client->ssh->kex->session_id == NULL
        || !nonzero32(client->completion_session_digest)) {
        return terminal_error(client, EACCES);
    }
    if (ssh_digest_buffer(SSH_DIGEST_SHA256, client->ssh->kex->session_id,
            digest, sizeof(digest)) != 0) {
        result = terminal_error(client, EIO);
    } else if (memcmp(digest, client->completion_session_digest, sizeof(digest)) != 0) {
        result = terminal_error(client, EACCES);
    } else {
        result = 0;
    }
    wipe_bytes(digest, sizeof(digest));
    return result;
}

int ctos_ssh_native_client_selected_credential_bytes(struct ctos_ssh_native_client *client,
    unsigned char *output, size_t capacity, size_t *length) {
    unsigned char *scratch;
    size_t returned_length = 0;
    int error;

    if (length != NULL) *length = 0;
    if (output != NULL) wipe_bytes(output, capacity > CTOS_SSH_CALLBACK_MAX_CREDENTIAL_BYTES
        ? CTOS_SSH_CALLBACK_MAX_CREDENTIAL_BYTES : capacity);
    if (client == NULL || output == NULL || length == NULL || capacity == 0) {
        errno = EINVAL;
        return -1;
    }
    if (capacity > CTOS_SSH_CALLBACK_MAX_CREDENTIAL_BYTES) { errno = EOVERFLOW; return -1; }
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed || client->selected_credential_attempted) { errno = EALREADY; return -1; }
    /* Alien callers cannot consume the one-shot or poison the owner's state. */
    if (ctos_ssh_callback_check_live(client->callback, client->callback_environment) != 0) {
        error = errno;
        return error == EPERM ? -1 : terminal_error(client, error);
    }
    if (client->authentication_selection_wire == NULL
        || client->service_phase != USERAUTH_SERVICE_READY) { errno = EACCES; return -1; }
    if (selected_credential_is_live(client) != 0) return -1;
    scratch = calloc(1, capacity);
    if (scratch == NULL) { errno = ENOMEM; return -1; }
    client->selected_credential_attempted = 1;
    if (ctos_ssh_callback_credential_bytes_selected(client->callback,
            client->callback_environment, client->authentication_selection_kind,
            client->authentication_selection_receipt, client->completion_cycle,
            client->completion_session_digest, scratch, capacity, &returned_length) != 0) {
        error = errno == 0 ? EIO : errno;
        goto failed;
    }
    if (selected_credential_is_live(client) != 0) {
        error = errno == 0 ? EACCES : errno;
        goto failed;
    }
    if (returned_length == 0 || returned_length > capacity) {
        error = EINVAL;
        goto failed;
    }
    memcpy(output, scratch, returned_length);
    *length = returned_length;
    wipe_bytes(scratch, capacity);
    free(scratch);
    return 0;
failed:
    wipe_bytes(scratch, capacity);
    free(scratch);
    wipe_bytes(output, capacity);
    *length = 0;
    return terminal_error(client, error);
}

/* Shared by the const result reader and active auth. No client mutation, I/O
 * or credential/provider call is permitted in this proof check. */
static int auth_proof(const struct ctos_ssh_native_client *client, int kind) {
    struct ctos_ssh_role_snapshot snapshot = {0};
    unsigned char digest[32] = {0};
    uint64_t current;
    int result = -1;
    int error;

    if (ctos_ssh_callback_check_live(client->callback, client->callback_environment) != 0)
        return -1;
    if (view_borrow_is_live(client->managed_view_borrow) != 0) return -1;
    if (now_millis(&current) != 0) return -1;
    if (current >= client->deadline) { errno = ETIMEDOUT; return -1; }
    if (client->authentication_selection_kind != kind
        || (kind != CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD
            && kind != CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY)
        || client->authentication_selection_wire == NULL
        || client->service_phase != USERAUTH_SERVICE_READY || !client->completed
        || client->completion_calls != 1U || client->completion_cycle == 0
        || client->ssh == NULL || client->ssh->kex == NULL
        || !client->ssh->kex->done || client->ssh->kex->session_id == NULL
        || !nonzero32(client->completion_session_digest)) {
        errno = EACCES;
        return -1;
    }
    if (ctos_ssh_role_bootstrap_snapshot(client->role, &snapshot) != 0) goto out;
    if (snapshot.token != client->role || snapshot.owner_pid != getpid()
        || snapshot.owner_uid != getuid() || snapshot.deadline != client->deadline
        || ctos_ssh_kex_ready(client->ssh, snapshot.hop, snapshot.role_nonce,
            snapshot.connection_binding) != client->completion_cycle) {
        errno = EACCES;
        goto out;
    }
    if (ssh_digest_buffer(SSH_DIGEST_SHA256, client->ssh->kex->session_id,
            digest, sizeof(digest)) != 0) { errno = EIO; goto out; }
    if (memcmp(digest, client->completion_session_digest, sizeof(digest)) != 0) {
        errno = EACCES;
        goto out;
    }
    result = 0;
out:
    error = errno;
    wipe_bytes(&snapshot, sizeof(snapshot));
    wipe_bytes(digest, sizeof(digest));
    errno = error;
    return result;
}

static int auth_is_live(struct ctos_ssh_native_client *client, int cancelled) {
    if (auth_proof(client, client->authentication_selection_kind) != 0) {
        int error = errno;
        return error == EPERM ? -1 : terminal_error(client, error);
    }
    if (cancelled) return terminal_error(client, ECANCELED);
    return 0;
}

static int auth_output_is_clear(struct ctos_ssh_native_client *client) {
    size_t length;

    (void)ssh_output_ptr(client->ssh, &length);
    if (length != 0 || ssh_packet_is_rekeying(client->ssh))
        return terminal_error(client, EACCES);
    return 0;
}

int ctos_ssh_native_client_begin_password_auth(struct ctos_ssh_native_client *client) {
    unsigned char *password;
    size_t length = 0;
    int result = -1;
    int error;

    if (client == NULL) { errno = EINVAL; return -1; }
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed) { errno = EALREADY; return -1; }
    if (ctos_ssh_callback_check_live(client->callback, client->callback_environment) != 0) {
        error = errno;
        return error == EPERM ? -1 : terminal_error(client, error);
    }
    if (client->auth_phase != AUTH_DISABLED
        || client->selected_credential_attempted) { errno = EALREADY; return -1; }
    if (client->authentication_selection_kind != CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD
        || client->service_phase != USERAUTH_SERVICE_READY) { errno = EACCES; return -1; }
    if (auth_is_live(client, 0) != 0 || auth_output_is_clear(client) != 0)
        return -1;
    password = calloc(1, CTOS_SSH_NATIVE_CLIENT_MAX_PASSWORD_BYTES);
    if (password == NULL) { errno = ENOMEM; return -1; }
    if (ctos_ssh_native_client_selected_credential_bytes(client, password,
            CTOS_SSH_NATIVE_CLIENT_MAX_PASSWORD_BYTES, &length) != 0) goto out;
    if (length == 0 || length > CTOS_SSH_NATIVE_CLIENT_MAX_PASSWORD_BYTES
        || memchr(password, '\0', length) != NULL) {
        terminal_error(client, EINVAL);
        goto out;
    }
    if (auth_is_live(client, 0) != 0 || auth_output_is_clear(client) != 0)
        goto out;
    if (sshpkt_start(client->ssh, SSH2_MSG_USERAUTH_REQUEST) != 0
        || sshpkt_put_string(client->ssh, client->authentication_selection_wire
            + client->authentication_username_offset, client->authentication_username_length) != 0
        || sshpkt_put_cstring(client->ssh, "ssh-connection") != 0
        || sshpkt_put_cstring(client->ssh, "password") != 0
        || sshpkt_put_u8(client->ssh, 0) != 0
        || sshpkt_put_string(client->ssh, password, length) != 0
        || sshpkt_add_padding(client->ssh, 64) != 0 || sshpkt_send(client->ssh) != 0) {
        /* Reset/wipe any unfinished outgoing plaintext, without sending the
         * cleanup header. Queued secret packets are prevented before start. */
        (void)sshpkt_start(client->ssh, SSH2_MSG_IGNORE);
        terminal_error(client, EIO);
        goto out;
    }
    client->auth_phase = AUTH_REQUEST_PENDING;
    client->auth_result = CTOS_SSH_PASSWORD_AUTH_PENDING;
    result = 0;
out:
    error = errno;
    wipe_bytes(password, CTOS_SSH_NATIVE_CLIENT_MAX_PASSWORD_BYTES);
    free(password);
    errno = error;
    return result;
}

static int packet_bounded_text(struct ssh *ssh, size_t maximum,
    const unsigned char **text, size_t *length) {
    return sshpkt_get_string_direct(ssh, text, length) == 0 && *length <= maximum ? 0 : -1;
}

static int auth_packet(struct ctos_ssh_native_client *client, int packet_type) {
    const unsigned char *text;
    const unsigned char *language;
    size_t length, language_length;
    u_char partial;
    uint32_t reason;
    enum ctos_ssh_native_client_password_auth_result outcome;

    if (packet_type == SSH2_MSG_USERAUTH_BANNER || packet_type == SSH2_MSG_DISCONNECT) {
        if (packet_type == SSH2_MSG_DISCONNECT && sshpkt_get_u32(client->ssh, &reason) != 0)
            return terminal_error(client, EPROTO);
        if (packet_bounded_text(client->ssh, 8192U, &text, &length) != 0
            || packet_bounded_text(client->ssh, 128U, &language, &language_length) != 0
            || sshpkt_get_end(client->ssh) != 0) return terminal_error(client, EPROTO);
        if (packet_type == SSH2_MSG_DISCONNECT) return terminal_error(client, ECONNRESET);
        if (client->auth_banners >= 16U) return terminal_error(client, EPROTO);
        client->auth_banners++;
        return 0;
    }
    if (client->auth_phase != AUTH_AWAIT_REPLY)
        return terminal_error(client, EPROTO);
    switch (packet_type) {
        case SSH2_MSG_USERAUTH_SUCCESS:
            outcome = CTOS_SSH_PASSWORD_AUTH_SUCCESS;
            break;
        case SSH2_MSG_USERAUTH_FAILURE:
            if (packet_bounded_text(client->ssh, 1024U, &text, &length) != 0
                || sshpkt_get_u8(client->ssh, &partial) != 0)
                return terminal_error(client, EPROTO);
            for (size_t index = 0; index < length; index++) {
                if (text[index] < 0x21U || text[index] > 0x7eU
                    || (text[index] == ',' && (index == 0U || index + 1U == length
                        || text[index - 1U] == ','))) return terminal_error(client, EPROTO);
            }
            outcome = partial ? CTOS_SSH_PASSWORD_AUTH_PARTIAL : CTOS_SSH_PASSWORD_AUTH_REJECTED;
            break;
        case SSH2_MSG_USERAUTH_PASSWD_CHANGEREQ:
            if (client->authentication_selection_kind != CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD)
                return terminal_error(client, EPROTO);
            if (packet_bounded_text(client->ssh, 8192U, &text, &length) != 0
                || packet_bounded_text(client->ssh, 128U, &language, &language_length) != 0)
                return terminal_error(client, EPROTO);
            outcome = CTOS_SSH_PASSWORD_AUTH_CHANGE_REQUIRED;
            break;
        default:
            return terminal_error(client, EPROTO);
    }
    if (sshpkt_get_end(client->ssh) != 0) return terminal_error(client, EPROTO);
    if (auth_is_live(client, 0) != 0 || auth_output_is_clear(client) != 0)
        return -1;
    client->auth_result = outcome;
    client->auth_phase = AUTH_FINISHED;
    return 0;
}

int ctos_ssh_native_client_password_auth_result(const struct ctos_ssh_native_client *client,
    enum ctos_ssh_native_client_password_auth_result *outcome) {
    if (outcome != NULL) *outcome = CTOS_SSH_PASSWORD_AUTH_NOT_STARTED;
    if (client == NULL || outcome == NULL) { errno = EINVAL; return -1; }
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed) { errno = EALREADY; return -1; }
    if (auth_proof(client, CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD) != 0) return -1;
    *outcome = client->auth_result;
    return 0;
}

static int advertised_algorithm(const char *list, const char *algorithm) {
    size_t length = strlen(algorithm);
    const char *end;

    if (list == NULL) return 0;
    while (*list != '\0') {
        end = strchr(list, ',');
        if (end == NULL) end = list + strlen(list);
        if ((size_t)(end - list) == length && memcmp(list, algorithm, length) == 0) return 1;
        list = *end == '\0' ? end : end + 1;
    }
    return 0;
}

static int key_bundle(const unsigned char *bytes, size_t length,
    struct ctos_ssh_slice *key, struct ctos_ssh_slice *passphrase) {
    struct selection_cursor cursor = {bytes, length};
    uint32_t magic, version;

    if (selection_u32(&cursor, &magic) != 0 || magic != 0x43534b42U
        || selection_u32(&cursor, &version) != 0 || version != 1U
        || selection_field(&cursor, key) != 0 || key->length == 0
        || selection_field(&cursor, passphrase) != 0 || cursor.remaining != 0
        || passphrase->length == 0 || passphrase->length > 8192U
        || memchr(passphrase->data, '\0', passphrase->length) != NULL) {
        errno = EINVAL;
        return -1;
    }
    return ctos_ssh_key_encrypted_policy(key->data, key->length);
}

int ctos_ssh_native_client_begin_private_key_auth(struct ctos_ssh_native_client *client) {
    struct sshkey *expected = NULL, *private_key = NULL;
    struct sshbuf *file = NULL, *signed_data = NULL;
    unsigned char *secret = NULL, *public_blob = NULL, *signature = NULL;
    char *passphrase = NULL;
    struct ctos_ssh_slice key_bytes = {0}, passphrase_bytes = {0};
    size_t secret_length = 0, public_length = 0, signature_length = 0;
    const char *algorithm = NULL;
    int result = -1, status, error;

    if (client == NULL) { errno = EINVAL; return -1; }
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed) { errno = EALREADY; return -1; }
    if (ctos_ssh_callback_check_live(client->callback, client->callback_environment) != 0) {
        error = errno;
        return error == EPERM ? -1 : terminal_error(client, error);
    }
    if (client->auth_phase != AUTH_DISABLED || client->selected_credential_attempted) {
        errno = EALREADY;
        return -1;
    }
    if (client->authentication_selection_kind != CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY
        || client->service_phase != USERAUTH_SERVICE_READY) { errno = EACCES; return -1; }
    if (auth_is_live(client, 0) != 0 || auth_output_is_clear(client) != 0) return -1;
    if ((client->ssh->compat & SSH_OLD_SESSIONID) != 0) { errno = ENOTSUP; return -1; }
    if (client->authentication_public_length == 0
        || client->authentication_public_length > 64U * 1024U) { errno = EINVAL; return -1; }
    status = sshkey_from_blob(client->authentication_selection_wire
        + client->authentication_public_offset, client->authentication_public_length, &expected);
    if (status != 0) {
        errno = status == SSH_ERR_ALLOC_FAIL ? ENOMEM
            : status == SSH_ERR_KEY_TYPE_UNKNOWN || status == SSH_ERR_FEATURE_UNSUPPORTED ? ENOTSUP : EINVAL;
        goto out;
    }
    if (ctos_ssh_key_software_identity(expected) != 0) goto out;
    if (expected->type == KEY_RSA) {
        if (advertised_algorithm(client->ssh->kex->server_sig_algs, "rsa-sha2-512"))
            algorithm = "rsa-sha2-512";
        else if (advertised_algorithm(client->ssh->kex->server_sig_algs, "rsa-sha2-256"))
            algorithm = "rsa-sha2-256";
        else { errno = ENOTSUP; goto out; }
    } else algorithm = sshkey_ssh_name(expected);
    if (algorithm == NULL || *algorithm == '\0') { errno = ENOTSUP; goto out; }
    status = sshkey_to_blob(expected, &public_blob, &public_length);
    if (status != 0) { errno = status == SSH_ERR_ALLOC_FAIL ? ENOMEM : EINVAL; goto out; }
    if (public_length == 0 || public_length > 64U * 1024U) { errno = EINVAL; goto out; }
    secret = calloc(1, CTOS_SSH_VIEW_FILE_BYTES);
    signed_data = sshbuf_new();
    if (secret == NULL || signed_data == NULL) { errno = ENOMEM; goto out; }
    if (ctos_ssh_native_client_selected_credential_bytes(client, secret,
            CTOS_SSH_VIEW_FILE_BYTES, &secret_length) != 0) goto out;
    key_bytes = (struct ctos_ssh_slice){secret, secret_length};
    if (client->authentication_selection_version == 2U) {
        if (key_bundle(secret, secret_length, &key_bytes, &passphrase_bytes) != 0) {
            terminal_error(client, errno);
            goto out;
        }
        passphrase = calloc(1, passphrase_bytes.length + 1U);
        if (passphrase == NULL) { terminal_error(client, ENOMEM); goto out; }
        memcpy(passphrase, passphrase_bytes.data, passphrase_bytes.length);
    }
    file = sshbuf_from(key_bytes.data, key_bytes.length);
    status = file == NULL ? SSH_ERR_ALLOC_FAIL
        : sshkey_parse_private_fileblob(file, passphrase, &private_key, NULL);
    sshbuf_free(file);
    file = NULL;
    wipe_bytes(secret, CTOS_SSH_VIEW_FILE_BYTES);
    wipe_bytes(passphrase, passphrase_bytes.length + (passphrase == NULL ? 0U : 1U));
    if (status != 0) {
        terminal_error(client, status == SSH_ERR_ALLOC_FAIL ? ENOMEM
            : status == SSH_ERR_KEY_WRONG_PASSPHRASE
                ? (client->authentication_selection_version == 2U ? EACCES : ENOTSUP)
            : status == SSH_ERR_FEATURE_UNSUPPORTED || status == SSH_ERR_KEY_TYPE_UNKNOWN ? ENOTSUP : EINVAL);
        goto out;
    }
    if (ctos_ssh_key_software_identity(private_key) != 0) {
        terminal_error(client, errno);
        goto out;
    }
    if (!sshkey_equal_public(expected, private_key)) {
        terminal_error(client, EACCES);
        goto out;
    }
    /* SSH signs the framed raw initial session ID, not its authorization digest. */
    if (sshbuf_put_stringb(signed_data, client->ssh->kex->session_id) != 0
        || sshbuf_put_u8(signed_data, SSH2_MSG_USERAUTH_REQUEST) != 0
        || sshbuf_put_string(signed_data, client->authentication_selection_wire
            + client->authentication_username_offset, client->authentication_username_length) != 0
        || sshbuf_put_cstring(signed_data, "ssh-connection") != 0
        || sshbuf_put_cstring(signed_data, "publickey") != 0
        || sshbuf_put_u8(signed_data, 1) != 0
        || sshbuf_put_cstring(signed_data, algorithm) != 0
        || sshbuf_put_string(signed_data, public_blob, public_length) != 0
        || sshbuf_len(signed_data) > SSH_KEY_MAX_SIGN_DATA_SIZE) {
        terminal_error(client, EIO);
        goto out;
    }
    status = sshkey_sign(private_key, &signature, &signature_length,
        sshbuf_ptr(signed_data), sshbuf_len(signed_data), algorithm, NULL, NULL, 0);
    if (status != 0 || signature_length == 0 || signature_length > 16U * 1024U
        || sshkey_check_sigtype(signature, signature_length, algorithm) != 0) {
        terminal_error(client, status == SSH_ERR_ALLOC_FAIL ? ENOMEM : EACCES);
        goto out;
    }
    status = sshkey_verify(expected, signature, signature_length, sshbuf_ptr(signed_data),
        sshbuf_len(signed_data), algorithm, 0, NULL);
    if (status != 0) {
        terminal_error(client, status == SSH_ERR_ALLOC_FAIL ? ENOMEM : EACCES);
        goto out;
    }
    if (auth_is_live(client, 0) != 0 || auth_output_is_clear(client) != 0) goto out;
    if (sshpkt_start(client->ssh, SSH2_MSG_USERAUTH_REQUEST) != 0
        || sshpkt_put_string(client->ssh, client->authentication_selection_wire
            + client->authentication_username_offset, client->authentication_username_length) != 0
        || sshpkt_put_cstring(client->ssh, "ssh-connection") != 0
        || sshpkt_put_cstring(client->ssh, "publickey") != 0
        || sshpkt_put_u8(client->ssh, 1) != 0
        || sshpkt_put_cstring(client->ssh, algorithm) != 0
        || sshpkt_put_string(client->ssh, public_blob, public_length) != 0
        || sshpkt_put_string(client->ssh, signature, signature_length) != 0
        || sshpkt_send(client->ssh) != 0) {
        (void)sshpkt_start(client->ssh, SSH2_MSG_IGNORE);
        terminal_error(client, EIO);
        goto out;
    }
    client->auth_phase = AUTH_REQUEST_PENDING;
    client->auth_result = CTOS_SSH_PRIVATE_KEY_AUTH_PENDING;
    result = 0;
out:
    error = errno;
    sshbuf_free(file);
    sshbuf_free(signed_data);
    sshkey_free(private_key);
    sshkey_free(expected);
    wipe_bytes(secret, secret == NULL ? 0 : CTOS_SSH_VIEW_FILE_BYTES);
    free(secret);
    wipe_bytes(passphrase, passphrase_bytes.length + (passphrase == NULL ? 0U : 1U));
    free(passphrase);
    wipe_bytes(signature, signature_length);
    free(signature);
    wipe_bytes(public_blob, public_length);
    free(public_blob);
    errno = error;
    return result;
}

int ctos_ssh_native_client_private_key_auth_result(const struct ctos_ssh_native_client *client,
    enum ctos_ssh_native_client_private_key_auth_result *outcome) {
    if (outcome != NULL) *outcome = CTOS_SSH_PRIVATE_KEY_AUTH_NOT_STARTED;
    if (client == NULL || outcome == NULL) { errno = EINVAL; return -1; }
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed) { errno = EALREADY; return -1; }
    if (auth_proof(client, CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY) != 0) return -1;
    *outcome = (enum ctos_ssh_native_client_private_key_auth_result)client->auth_result;
    return 0;
}

int ctos_ssh_native_client_begin_userauth_service(struct ctos_ssh_native_client *client) {
    if (client == NULL) { errno = EINVAL; return -1; }
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed || client->service_phase != USERAUTH_SERVICE_DISABLED) {
        errno = EALREADY;
        return -1;
    }
    if (service_is_live(client, 0) != 0) return -1;
    if (!client->completed) { errno = EACCES; return -1; }

    /* Service negotiation cannot silently dispatch a new KEX under old proof. */
    ssh_dispatch_range(client->ssh, SSH2_MSG_KEXINIT,
        SSH2_MSG_TRANSPORT_MAX, NULL);
    if (sshpkt_start(client->ssh, SSH2_MSG_SERVICE_REQUEST) != 0
        || sshpkt_put_cstring(client->ssh, "ssh-userauth") != 0
        || sshpkt_send(client->ssh) != 0) return terminal_error(client, EIO);
    client->service_phase = USERAUTH_SERVICE_REQUEST_PENDING;
    return 0;
}

static int accept_userauth_service(struct ctos_ssh_native_client *client) {
    const unsigned char *service = NULL;
    size_t length;

    if (client->service_phase != USERAUTH_SERVICE_AWAIT_ACCEPT)
        return terminal_error(client, EPROTO);
    (void)sshpkt_ptr(client->ssh, &length);
    if (length != 0
        && (sshpkt_get_string_direct(client->ssh, &service, &length) != 0
            || (length != 0 && (length != sizeof("ssh-userauth") - 1U
                || memcmp(service, "ssh-userauth", length) != 0))))
        return terminal_error(client, EPROTO);
    if (sshpkt_get_end(client->ssh) != 0)
        return terminal_error(client, EPROTO);
    client->service_phase = USERAUTH_SERVICE_READY;
    return 0;
}

#define SHELL_PACKET_MAX 32768U
#define SHELL_OUTPUT_MAX (128U * 1024U)
#define SHELL_INPUT_MAX (256U * 1024U)

static int established_proof(const struct ctos_ssh_native_client *client) {
    struct ctos_ssh_role_snapshot snapshot = {0};
    unsigned char digest[32] = {0};
    int result = -1;
    int error;

    if (ctos_ssh_callback_check_transport(client->callback,
            client->callback_environment, client) != 0) return -1;
    if (ctos_ssh_view_borrow_bound(client->managed_view_borrow) != 0) return -1;
    if (!client->established || client->ssh == NULL || client->ssh != client->established_ssh
        || client->server_host_public_key == NULL
        || client->server_host_public_key != client->established_host_key
        || client->role != client->established_role.token
        || client->deadline != client->established_role.deadline
        || client->auth_phase != AUTH_FINISHED
        || client->auth_result != CTOS_SSH_PASSWORD_AUTH_SUCCESS
        || !client->completed || client->completion_calls != 1U
        || client->completion_cycle != client->established_cycle
        || client->established_cycle == 0 || client->ssh->kex == NULL
        || !client->ssh->kex->done || client->ssh->kex->session_id == NULL
        || ssh_packet_is_rekeying(client->ssh)
        || memcmp(client->completion_session_digest, client->established_session_digest, 32U) != 0
        || strcmp(client->host, client->established_role.host) != 0) {
        errno = EACCES; return -1;
    }
    if (ctos_ssh_role_transport_snapshot(client->role, &snapshot) != 0) goto out;
    if (memcmp(&snapshot, &client->established_role, sizeof(snapshot)) != 0
        || ctos_ssh_kex_transport_ready(client->ssh, snapshot.hop, snapshot.role_nonce,
                snapshot.connection_binding) != client->established_cycle) {
        errno = EACCES; goto out;
    }
    if (ssh_digest_buffer(SSH_DIGEST_SHA256, client->ssh->kex->session_id,
            digest, sizeof(digest)) != 0) { errno = EIO; goto out; }
    if (memcmp(digest, client->established_session_digest, sizeof(digest)) != 0) {
        errno = EACCES; goto out;
    }
    result = 0;
out:
    error = errno;
    wipe_bytes(&snapshot, sizeof(snapshot));
    wipe_bytes(digest, sizeof(digest));
    errno = error;
    return result;
}

static int shell_proof(const struct ctos_ssh_native_client *client) {
    if (client == NULL) { errno = EINVAL; return -1; }
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed) { errno = EALREADY; return -1; }
    return client->established ? established_proof(client)
        : auth_proof(client, client->authentication_selection_kind);
}

static int shell_live(struct ctos_ssh_native_client *client) {
    if (shell_proof(client) == 0) return 0;
    if (client == NULL || client->closed || client->failed || errno == EPERM) return -1;
    return terminal_error(client, errno);
}

static int established_live(struct ctos_ssh_native_client *client, int cancelled) {
    if (shell_live(client) != 0) return -1;
    if (cancelled) return terminal_error(client, ECANCELED);
    return 0;
}

static int shell_room(struct ctos_ssh_native_client *client, size_t reserve) {
    size_t length;
    (void)ssh_output_ptr(client->ssh, &length);
    return reserve <= SHELL_OUTPUT_MAX && length <= SHELL_OUTPUT_MAX - reserve;
}

static int shell_send(struct ctos_ssh_native_client *client) {
    size_t length;
    if (sshpkt_send(client->ssh) != 0) return terminal_error(client, EIO);
    (void)ssh_output_ptr(client->ssh, &length);
    if (length > SHELL_OUTPUT_MAX || ssh_packet_is_rekeying(client->ssh))
        return terminal_error(client, EPROTO);
    return 0;
}

static int shell_message(struct ctos_ssh_native_client *client, int type) {
    if (sshpkt_start(client->ssh, type) != 0
        || sshpkt_put_u32(client->ssh, client->remote_channel) != 0)
        return terminal_error(client, EIO);
    return 0;
}

static int shell_close_message(struct ctos_ssh_native_client *client) {
    if (client->local_close) return 0;
    if (!shell_room(client, 256U)) return 0;
    if (shell_message(client, SSH2_MSG_CHANNEL_CLOSE) != 0 || shell_send(client) != 0)
        return -1;
    client->local_close = 1;
    return 0;
}

static int shell_request(struct ctos_ssh_native_client *client, int pty) {
    if (shell_message(client, SSH2_MSG_CHANNEL_REQUEST) != 0
        || sshpkt_put_cstring(client->ssh, pty ? "pty-req" : "shell") != 0
        || sshpkt_put_u8(client->ssh, 1) != 0) return terminal_error(client, EIO);
    if (pty && (sshpkt_put_cstring(client->ssh, "xterm-256color") != 0
        || sshpkt_put_u32(client->ssh, client->shell_columns) != 0
        || sshpkt_put_u32(client->ssh, client->shell_rows) != 0
        || sshpkt_put_u32(client->ssh, 0) != 0 || sshpkt_put_u32(client->ssh, 0) != 0
        || sshpkt_put_string(client->ssh, "", 1) != 0)) return terminal_error(client, EIO);
    client->shell_request_drained = 0;
    client->shell.phase = pty ? CTOS_SSH_SHELL_PTY_PENDING : CTOS_SSH_SHELL_SHELL_PENDING;
    return shell_send(client);
}

int ctos_ssh_native_client_begin_shell(struct ctos_ssh_native_client *client,
    unsigned int columns, unsigned int rows) {
    struct ctos_ssh_role_snapshot snapshot = {0};
    if (client == NULL || columns == 0 || columns > 4096U || rows == 0 || rows > 4096U) {
        errno = EINVAL; return -1;
    }
    if (shell_live(client) != 0) return -1;
    if (client->shell.phase != CTOS_SSH_SHELL_DISABLED) { errno = EALREADY; return -1; }
    if (client->auth_phase != AUTH_FINISHED || client->auth_result != CTOS_SSH_PASSWORD_AUTH_SUCCESS) {
        errno = EACCES; return -1;
    }
    if (auth_output_is_clear(client) != 0) return -1;
    if (ctos_ssh_role_bootstrap_snapshot(client->role, &snapshot) != 0)
        return terminal_error(client, errno);
    if (ctos_ssh_callback_promote_transport(client->callback,
            client->callback_environment, client) != 0) {
        int error = errno;
        wipe_bytes(&snapshot, sizeof(snapshot));
        return terminal_error(client, error);
    }
    client->established_role = snapshot;
    wipe_bytes(&snapshot, sizeof(snapshot));
    client->established_ssh = client->ssh;
    client->established_host_key = client->server_host_public_key;
    client->established_cycle = client->completion_cycle;
    memcpy(client->established_session_digest, client->completion_session_digest, 32U);
    client->established = 1;
    if (sshpkt_start(client->ssh, SSH2_MSG_CHANNEL_OPEN) != 0
        || sshpkt_put_cstring(client->ssh, "session") != 0
        || sshpkt_put_u32(client->ssh, 0) != 0
        || sshpkt_put_u32(client->ssh, 65536U) != 0
        || sshpkt_put_u32(client->ssh, SHELL_PACKET_MAX) != 0
        || shell_send(client) != 0) return terminal_error(client, EIO);
    client->shell.phase = CTOS_SSH_SHELL_OPENING;
    client->shell_columns = columns;
    client->shell_rows = rows;
    client->local_window = 65536U;
    return shell_live(client);
}

int ctos_ssh_native_client_shell_status(const struct ctos_ssh_native_client *client,
    struct ctos_ssh_native_shell_status *status) {
    if (status != NULL) memset(status, 0, sizeof(*status));
    if (status == NULL) { errno = EINVAL; return -1; }
    if (shell_proof(client) != 0) return -1;
    *status = client->shell;
    return 0;
}

int ctos_ssh_native_client_shell_write(struct ctos_ssh_native_client *client,
    const unsigned char *bytes, size_t length, size_t *accepted) {
    size_t count;
    if (accepted != NULL) *accepted = 0;
    if (bytes == NULL || accepted == NULL || length == 0 || length > SHELL_PACKET_MAX) {
        errno = EINVAL; return -1;
    }
    if (shell_live(client) != 0) return -1;
    if (client->shell.phase != CTOS_SSH_SHELL_RUNNING || client->shell.local_eof
        || client->remote_close) { errno = EACCES; return -1; }
    count = length < client->remote_window ? length : client->remote_window;
    if (count > client->remote_maxpacket) count = client->remote_maxpacket;
    if (count == 0 || !shell_room(client, count + 256U)) return 0;
    if (shell_message(client, SSH2_MSG_CHANNEL_DATA) != 0
        || sshpkt_put_string(client->ssh, bytes, count) != 0 || shell_send(client) != 0)
        return terminal_error(client, EIO);
    if (shell_live(client) != 0) return -1;
    client->remote_window -= (uint32_t)count;
    *accepted = count;
    return 0;
}

int ctos_ssh_native_client_shell_read(struct ctos_ssh_native_client *client,
    unsigned char *bytes, size_t capacity, size_t *length) {
    unsigned char scratch[SHELL_PACKET_MAX];
    size_t count;
    if (length != NULL) *length = 0;
    if (bytes != NULL) wipe_bytes(bytes, capacity > SHELL_PACKET_MAX ? SHELL_PACKET_MAX : capacity);
    if (bytes == NULL || length == NULL || capacity == 0 || capacity > SHELL_PACKET_MAX) {
        errno = EINVAL; return -1;
    }
    if (shell_live(client) != 0) return -1;
    if (client->shell.phase == CTOS_SSH_SHELL_DISABLED) { errno = EACCES; return -1; }
    count = capacity < client->shell.buffered_bytes ? capacity : client->shell.buffered_bytes;
    memcpy(scratch, client->shell_buffer, count);
    if (shell_live(client) != 0) { wipe_bytes(scratch, sizeof(scratch)); return -1; }
    memmove(client->shell_buffer, client->shell_buffer + count, client->shell.buffered_bytes - count);
    client->shell.buffered_bytes -= count;
    wipe_bytes(client->shell_buffer + client->shell.buffered_bytes, count);
    if (!client->remote_close) client->window_credit += (uint32_t)count;
    memcpy(bytes, scratch, count);
    *length = count;
    wipe_bytes(scratch, sizeof(scratch));
    return 0;
}

int ctos_ssh_native_client_shell_resize(struct ctos_ssh_native_client *client,
    unsigned int columns, unsigned int rows) {
    if (columns == 0 || columns > 4096U || rows == 0 || rows > 4096U) { errno = EINVAL; return -1; }
    if (shell_live(client) != 0) return -1;
    if (client->shell.phase != CTOS_SSH_SHELL_RUNNING || client->remote_close) { errno = EACCES; return -1; }
    if (!shell_room(client, 512U)) return 0;
    if (shell_message(client, SSH2_MSG_CHANNEL_REQUEST) != 0
        || sshpkt_put_cstring(client->ssh, "window-change") != 0
        || sshpkt_put_u8(client->ssh, 0) != 0 || sshpkt_put_u32(client->ssh, columns) != 0
        || sshpkt_put_u32(client->ssh, rows) != 0 || sshpkt_put_u32(client->ssh, 0) != 0
        || sshpkt_put_u32(client->ssh, 0) != 0 || shell_send(client) != 0)
        return terminal_error(client, EIO);
    if (shell_live(client) != 0) return -1;
    return 1;
}

int ctos_ssh_native_client_shell_eof(struct ctos_ssh_native_client *client) {
    if (shell_live(client) != 0) return -1;
    if (client->shell.phase != CTOS_SSH_SHELL_RUNNING || client->shell.local_eof
        || client->remote_close) { errno = EACCES; return -1; }
    if (!shell_room(client, 256U)) return 0;
    if (shell_message(client, SSH2_MSG_CHANNEL_EOF) != 0 || shell_send(client) != 0) return -1;
    client->shell.local_eof = 1;
    if (shell_live(client) != 0) return -1;
    return 1;
}

static int shell_text_equals(const unsigned char *bytes, size_t length, const char *text) {
    return strlen(text) == length && memcmp(bytes, text, length) == 0;
}

static int shell_discard_rest(struct ssh *ssh) {
    int remaining = ssh_packet_remaining(ssh);
    if (remaining < 0 || remaining > (int)SHELL_PACKET_MAX
        || sshpkt_get(ssh, NULL, (size_t)remaining) != 0 || sshpkt_get_end(ssh) != 0) return -1;
    return 0;
}

static int shell_packet(struct ctos_ssh_native_client *client, int type) {
    struct ssh *ssh = client->ssh;
    const unsigned char *text;
    size_t length;
    uint32_t recipient, value, maximum;
    u_char flag;
    if (type == SSH2_MSG_IGNORE || type == SSH2_MSG_DEBUG) {
        if (type == SSH2_MSG_DEBUG && sshpkt_get_u8(ssh, &flag) != 0) goto bad;
        if (packet_bounded_text(ssh, 8192U, &text, &length) != 0) goto bad;
        if (type == SSH2_MSG_DEBUG && packet_bounded_text(ssh, 128U, &text, &length) != 0) goto bad;
        if (sshpkt_get_end(ssh) != 0) goto bad;
        return 0;
    }
    if (type == SSH2_MSG_GLOBAL_REQUEST) {
        if (packet_bounded_text(ssh, 128U, &text, &length) != 0
            || sshpkt_get_u8(ssh, &flag) != 0 || flag > 1 || shell_discard_rest(ssh) != 0) goto bad;
        if (flag && (sshpkt_start(ssh, SSH2_MSG_REQUEST_FAILURE) != 0 || shell_send(client) != 0)) return -1;
        return 0;
    }
    if (type == SSH2_MSG_CHANNEL_OPEN) {
        if (packet_bounded_text(ssh, 128U, &text, &length) != 0
            || sshpkt_get_u32(ssh, &recipient) != 0 || sshpkt_get_u32(ssh, &value) != 0
            || sshpkt_get_u32(ssh, &maximum) != 0 || shell_discard_rest(ssh) != 0) goto bad;
        if (sshpkt_start(ssh, SSH2_MSG_CHANNEL_OPEN_FAILURE) != 0
            || sshpkt_put_u32(ssh, recipient) != 0 || sshpkt_put_u32(ssh, 1) != 0
            || sshpkt_put_cstring(ssh, "") != 0 || sshpkt_put_cstring(ssh, "") != 0
            || shell_send(client) != 0) return -1;
        return 0;
    }
    if (sshpkt_get_u32(ssh, &recipient) != 0 || recipient != 0) goto bad;
    if (type == SSH2_MSG_CHANNEL_OPEN_CONFIRMATION || type == SSH2_MSG_CHANNEL_OPEN_FAILURE) {
        if (client->shell.phase != CTOS_SSH_SHELL_OPENING || !client->shell_request_drained) goto bad;
        if (type == SSH2_MSG_CHANNEL_OPEN_FAILURE) {
            if (sshpkt_get_u32(ssh, &value) != 0
                || packet_bounded_text(ssh, 8192U, &text, &length) != 0
                || packet_bounded_text(ssh, 128U, &text, &length) != 0 || sshpkt_get_end(ssh) != 0) goto bad;
            client->shell.phase = CTOS_SSH_SHELL_REJECTED;
            return 0;
        }
        if (sshpkt_get_u32(ssh, &client->remote_channel) != 0
            || sshpkt_get_u32(ssh, &client->remote_window) != 0
            || sshpkt_get_u32(ssh, &client->remote_maxpacket) != 0
            || client->remote_maxpacket == 0 || sshpkt_get_end(ssh) != 0) goto bad;
        return shell_request(client, 1);
    }
    if (client->shell.phase == CTOS_SSH_SHELL_OPENING
        || client->shell.phase == CTOS_SSH_SHELL_REJECTED || client->remote_close) goto bad;
    if (client->shell_rejected_pending && type != SSH2_MSG_CHANNEL_CLOSE) goto bad;
    if (type == SSH2_MSG_CHANNEL_SUCCESS || type == SSH2_MSG_CHANNEL_FAILURE) {
        if ((client->shell.phase != CTOS_SSH_SHELL_PTY_PENDING
                && client->shell.phase != CTOS_SSH_SHELL_SHELL_PENDING)
            || !client->shell_request_drained || sshpkt_get_end(ssh) != 0) goto bad;
        if (type == SSH2_MSG_CHANNEL_FAILURE) {
            client->shell_rejected_pending = 1;
            return shell_close_message(client);
        }
        if (client->shell.phase == CTOS_SSH_SHELL_PTY_PENDING) return shell_request(client, 0);
        client->shell.phase = CTOS_SSH_SHELL_RUNNING;
        return 0;
    }
    if (type == SSH2_MSG_CHANNEL_CLOSE) {
        if (sshpkt_get_end(ssh) != 0) goto bad;
        client->remote_close = 1;
        return shell_close_message(client);
    }
    if (type == SSH2_MSG_CHANNEL_WINDOW_ADJUST) {
        if (sshpkt_get_u32(ssh, &value) != 0 || value == 0
            || value > UINT32_MAX - client->remote_window || sshpkt_get_end(ssh) != 0) goto bad;
        client->remote_window += value;
        return 0;
    }
    if (client->shell.phase != CTOS_SSH_SHELL_RUNNING) goto bad;
    if (type == SSH2_MSG_CHANNEL_DATA || type == SSH2_MSG_CHANNEL_EXTENDED_DATA) {
        if (client->shell.remote_eof) goto bad;
        if (type == SSH2_MSG_CHANNEL_EXTENDED_DATA && (sshpkt_get_u32(ssh, &value) != 0 || value != 1)) goto bad;
        if (packet_bounded_text(ssh, SHELL_PACKET_MAX, &text, &length) != 0
            || length > client->local_window || length > sizeof(client->shell_buffer) - client->shell.buffered_bytes
            || sshpkt_get_end(ssh) != 0) goto bad;
        memcpy(client->shell_buffer + client->shell.buffered_bytes, text, length);
        client->shell.buffered_bytes += length;
        client->local_window -= (uint32_t)length;
        return 0;
    }
    if (type == SSH2_MSG_CHANNEL_EOF) {
        if (client->shell.remote_eof || sshpkt_get_end(ssh) != 0) goto bad;
        client->shell.remote_eof = 1;
        return 0;
    }
    if (type == SSH2_MSG_CHANNEL_REQUEST) {
        if (packet_bounded_text(ssh, 128U, &text, &length) != 0
            || sshpkt_get_u8(ssh, &flag) != 0 || flag > 1) goto bad;
        if (shell_text_equals(text, length, "exit-status")) {
            if (flag || client->shell.exit_status_known || client->shell.exit_signal
                || sshpkt_get_u32(ssh, &value) != 0 || sshpkt_get_end(ssh) != 0) goto bad;
            client->shell.exit_status = value;
            client->shell.exit_status_known = 1;
        } else if (shell_text_equals(text, length, "exit-signal")) {
            if (flag || client->shell.exit_status_known || client->shell.exit_signal
                || packet_bounded_text(ssh, 128U, &text, &length) != 0
                || sshpkt_get_u8(ssh, &flag) != 0 || flag > 1
                || packet_bounded_text(ssh, 8192U, &text, &length) != 0
                || packet_bounded_text(ssh, 128U, &text, &length) != 0 || sshpkt_get_end(ssh) != 0) goto bad;
            client->shell.exit_signal = 1;
        } else {
            if (shell_discard_rest(ssh) != 0) goto bad;
            if (flag && (shell_message(client, SSH2_MSG_CHANNEL_FAILURE) != 0 || shell_send(client) != 0)) return -1;
        }
        return 0;
    }
bad:
    return terminal_error(client, EPROTO);
}

static int shell_output(struct ctos_ssh_native_client *client) {
    size_t length;
    (void)ssh_output_ptr(client->ssh, &length);
    if (length > SHELL_OUTPUT_MAX) return terminal_error(client, EPROTO);
    if (client->remote_close || client->shell_rejected_pending
        || client->shell.phase == CTOS_SSH_SHELL_REJECTED) {
        if (client->shell.phase != CTOS_SSH_SHELL_REJECTED || client->remote_maxpacket != 0)
            return shell_close_message(client);
    } else if (client->window_credit != 0 && shell_room(client, 256U)) {
        uint32_t credit = client->window_credit;
        if (shell_message(client, SSH2_MSG_CHANNEL_WINDOW_ADJUST) != 0
            || sshpkt_put_u32(client->ssh, credit) != 0 || shell_send(client) != 0) return -1;
        client->local_window += credit;
        client->window_credit = 0;
    }
    return 0;
}

int ctos_ssh_native_client_open(
    const struct ctos_ssh_native_client_config *config,
    struct ctos_ssh_native_client **client_out) {
    struct ctos_ssh_native_client *client = NULL;
    struct ctos_ssh_role_snapshot snapshot;
    struct kex_params params;
    char *proposals[PROPOSAL_MAX] = {KEX_CLIENT};
    size_t path_length;
    size_t candidate_length;
    const struct ctos_ssh_view *managed_view = NULL;
    int attached_socket = -1;
    int connection_attempted = 0;
    int failure;

    if (client_out == NULL) {
        errno = EINVAL;
        return -1;
    }
    *client_out = NULL;
    memset(&snapshot, 0, sizeof(snapshot));
    if (config == NULL || config->role == 0 || config->socket_fd < 0
        || config->owns_socket != 1 || config->callback == 0
        || config->callback_environment == NULL
        || (config->host_key_mode != CTOS_SSH_HOST_KEY_PINNED
            && config->host_key_mode != CTOS_SSH_HOST_KEY_MANAGED)
        || (config->host_key_mode == CTOS_SSH_HOST_KEY_MANAGED
            && (config->server_host_public_key_blob != NULL
                || config->server_host_public_key_length != 0
                || config->expected_host_fingerprint != NULL))
        || (config->host_key_mode == CTOS_SSH_HOST_KEY_PINNED
            && (config->server_host_public_key_blob == NULL
                || config->server_host_public_key_length == 0
                || config->server_host_public_key_length > CTOS_SSH_NATIVE_CLIENT_MAX_HOST_KEY_BLOB_BYTES
                || config->expected_host_fingerprint == NULL
                || !nonzero32(config->expected_host_fingerprint)))
        || config->known_hosts_path == NULL
        || config->host == NULL || config->candidate_id == NULL
        || config->step_budget == 0
        || config->step_budget > CTOS_SSH_NATIVE_CLIENT_MAX_STEP_BYTES
        || validate_descriptor(config->socket_fd) != 0
        || role_is_current(config, &snapshot) != 0
        || config->managed_view_borrow == NULL
        || (managed_view = ctos_ssh_view_borrow_view(
            config->managed_view_borrow)) == NULL
        || view_borrow_is_live(config->managed_view_borrow) != 0
        || view_slices_are_safe(managed_view) != 0
        || bounded_text(config->known_hosts_path, 512U, &path_length) != 0
        || bounded_text(config->candidate_id,
            CTOS_SSH_CALLBACK_CANDIDATE_ID_BYTES - 1U, &candidate_length) != 0
        || ctos_ssh_view_lookup(managed_view,
            (const unsigned char *)config->known_hosts_path, path_length,
            CTOS_SSH_KNOWN_HOSTS) == NULL
        || view_is_current(config, managed_view, &snapshot, path_length) != 0) {
        if (errno == 0) errno = EINVAL;
        wipe_bytes(&snapshot, sizeof(snapshot));
        return -1;
    }
    (void)candidate_length;
    client = calloc(1, sizeof(*client));
    if (client == NULL) {
        errno = ENOMEM;
        wipe_bytes(&snapshot, sizeof(snapshot));
        return -1;
    }
    client->role = config->role;
    client->callback = config->callback;
    client->callback_environment = config->callback_environment;
    client->managed_view_borrow = config->managed_view_borrow;
    client->managed_view = managed_view;
    client->socket_fd = -1;
    client->caller_socket_fd = -1;
    client->deadline = config->deadline;
    client->step_budget = config->step_budget;
    client->host_key_mode = config->host_key_mode;
    if (copy_authentication_selection(client, config, &snapshot) != 0) goto failed;
    if (config->host_key_mode == CTOS_SSH_HOST_KEY_PINNED) {
        memcpy(client->expected_host_fingerprint, config->expected_host_fingerprint,
            sizeof(client->expected_host_fingerprint));
        if (sshkey_from_blob(config->server_host_public_key_blob,
                config->server_host_public_key_length,
                &client->server_host_public_key) != 0
            || client->server_host_public_key == NULL) {
            errno = EINVAL;
            goto failed;
        }
        if (fingerprint_matches(client, client->server_host_public_key) != 0)
            goto failed;
    }
    if (copy_config_text(client->known_hosts_path,
            sizeof(client->known_hosts_path), config->known_hosts_path, 512U) != 0
        || copy_config_text(client->host, sizeof(client->host), config->host,
            CTOS_SSH_ROLE_HOST_BYTES) != 0
        || copy_config_text(client->candidate_id, sizeof(client->candidate_id),
            config->candidate_id, CTOS_SSH_CALLBACK_CANDIDATE_ID_BYTES - 1U) != 0)
        goto failed;
    memset(&params, 0, sizeof(params));
    memcpy(params.proposal, proposals, sizeof(proposals));
    if (ssh_init(&client->ssh, 0, &params) != 0 || client->ssh == NULL)
        goto failed;
    if (config->host_key_mode == CTOS_SSH_HOST_KEY_MANAGED) {
        if (ctos_ssh_kex_bind_pending_host(client->ssh, client->role) != 0) goto failed;
    } else if (ssh_add_hostkey(client->ssh, client->server_host_public_key) != 0
        || ctos_ssh_kex_bind_role(client->ssh, client->role,
            client->expected_host_fingerprint) != 0) goto failed;
    if (ssh_set_verify_host_key_callback(client->ssh, verify_host_key) != 0)
        goto failed;
    attached_socket = fcntl(config->socket_fd, F_DUPFD_CLOEXEC, 0);
    if (attached_socket < 0 || validate_descriptor(attached_socket) != 0)
        goto failed;
    connection_attempted = 1;
    if (ssh_packet_set_connection(client->ssh, attached_socket,
            attached_socket) == NULL) {
        connection_attempted = ssh_packet_get_connection_in(client->ssh)
            == attached_socket;
        goto failed;
    }
    ssh_packet_set_nonblocking(client->ssh);
    ssh_set_app_data(client->ssh, client);
    client->socket_fd = attached_socket;
    client->caller_socket_fd = config->socket_fd;
    attached_socket = -1;
    wipe_bytes(&snapshot, sizeof(snapshot));
    *client_out = client;
    return 0;

failed:
    /* The callback binding is borrowed, not consumed by open.  If any
     * admission or OpenSSH setup step fails, the caller still owns the live
     * token and must close it on this event-loop thread/environment. */
    failure = errno == 0 ? EIO : errno;
    if (client != NULL && client->ssh != NULL) {
        ctos_ssh_kex_unbind(client->ssh);
        if (connection_attempted) attached_socket = -1;
        ssh_free(client->ssh);
        client->ssh = NULL;
    }
    if (client != NULL && client->server_host_public_key != NULL) {
        sshkey_free(client->server_host_public_key);
        client->server_host_public_key = NULL;
    }
    if (attached_socket >= 0) close(attached_socket);
    if (client != NULL) {
        clear_authentication_selection(client);
        client->socket_fd = -1;
        client->caller_socket_fd = -1;
        wipe_bytes(client, sizeof(*client));
        free(client);
    }
    wipe_bytes(&snapshot, sizeof(snapshot));
    errno = failure;
    return -1;
}

int ctos_ssh_native_client_step(
    struct ctos_ssh_native_client *client,
    struct ctos_ssh_native_client_step *step) {
    unsigned char input[CTOS_SSH_NATIVE_CLIENT_MAX_STEP_BYTES];
    const unsigned char *output;
    size_t output_length;
    size_t read_length;
    size_t write_length;
    ssize_t read_result;
    ssize_t write_result;
    uint64_t current;
    u_char packet_type = SSH_MSG_NONE;
    int view_error;
    int cancelled = 0;
    int invalid_pipe = 0;
    int packet_result;
    int result = -1;

    memset(input, 0, sizeof(input));
    if (step != NULL) {
        cancelled = step->cancelled;
        invalid_pipe = step->input_length != 0 || step->output_capacity != 0
            || (step->input_length != 0 && step->input == NULL)
            || (step->output_capacity != 0 && step->output == NULL);
        memset(step, 0, sizeof(*step));
    }
    if (client == NULL || step == NULL || invalid_pipe) {
        errno = EINVAL;
        goto out;
    }
    if (client->closed) {
        errno = ENOENT;
        goto out;
    }
    if (client->failed || (client->auth_phase == AUTH_FINISHED
            && client->shell.phase == CTOS_SSH_SHELL_DISABLED)
        || client->shell.phase == CTOS_SSH_SHELL_CLOSED
        || (client->service_phase == USERAUTH_SERVICE_READY
            && client->auth_phase == AUTH_DISABLED)
        || (client->completed && client->service_phase == USERAUTH_SERVICE_DISABLED)) {
        errno = EALREADY;
        goto out;
    }
    if (client->established) {
        if (established_live(client, cancelled) != 0) goto out;
    } else if (client->auth_phase != AUTH_DISABLED) {
        if (auth_is_live(client, cancelled) != 0) goto out;
    } else if (client->service_phase != USERAUTH_SERVICE_DISABLED) {
        if (service_is_live(client, cancelled) != 0) goto out;
    } else {
        if (view_borrow_is_live(client->managed_view_borrow) != 0) {
            view_error = errno == 0 ? EACCES : errno;
            result = terminal_error(client, view_error);
            goto out;
        }
        if (cancelled) {
            result = terminal_error(client, ECANCELED);
            goto out;
        }
        if (now_millis(&current) != 0 || current >= client->deadline) {
            result = terminal_error(client, ETIMEDOUT);
            goto out;
        }
    }
    if (client->shell.phase != CTOS_SSH_SHELL_DISABLED) {
        (void)ssh_output_ptr(client->ssh, &output_length);
        if (client->shell.phase == CTOS_SSH_SHELL_REJECTED && output_length == 0) {
            errno = EALREADY; goto out;
        }
        if (shell_output(client) != 0) goto out;
        if (!shell_room(client, 1024U)) goto transport_write;
        if (sshbuf_len(ssh_packet_get_input(client->ssh)) > SHELL_INPUT_MAX) {
            result = terminal_error(client, EPROTO); goto out;
        }
    }
    read_length = client->step_budget;
    if (client->shell.phase != CTOS_SSH_SHELL_DISABLED) {
        size_t pending = sshbuf_len(ssh_packet_get_input(client->ssh));
        if (read_length > SHELL_INPUT_MAX - pending) read_length = SHELL_INPUT_MAX - pending;
    }
    if (client->shell_transport_eof || read_length == 0) goto parse_packet;
    if (read_length > sizeof(input)) read_length = sizeof(input);
    read_result = recv(client->socket_fd, input, read_length, MSG_DONTWAIT);
    if (read_result > 0) {
        step->bytes_read = (size_t)read_result;
        if (ssh_input_append(client->ssh, input, (size_t)read_result) != 0) {
            result = terminal_error(client, EIO);
            goto out;
        }
        step->input_appended = (size_t)read_result;
    } else if (read_result == 0) {
        if (client->shell.phase != CTOS_SSH_SHELL_DISABLED) client->shell_transport_eof = 1;
        else { result = terminal_error(client, EPIPE); goto out; }
    } else if (errno == EAGAIN || errno == EWOULDBLOCK) {
        step->read_would_block = 1;
    } else if (errno != EINTR) {
        result = terminal_error(client, errno == ECONNRESET ? ECONNRESET : EIO);
        goto out;
    }
parse_packet:
    client->in_transport_step = 1;
    packet_result = ssh_packet_next(client->ssh, &packet_type);
    client->in_transport_step = 0;
    if (packet_result != 0) {
        result = terminal_error(client, EIO);
        goto out;
    }
    step->packet_type = packet_type;
    if (client->shell.phase != CTOS_SSH_SHELL_DISABLED && packet_type == SSH_MSG_NONE) {
        if (client->shell_transport_eof && !client->remote_close) {
            result = terminal_error(client, EPIPE); goto out;
        }
        if (read_length == 0 && !client->shell_transport_eof) {
            result = terminal_error(client, EPROTO); goto out;
        }
    }
    if (packet_type == SSH2_MSG_EXT_INFO
        && kex_input_ext_info(packet_type, 0, client->ssh) != 0) {
        result = terminal_error(client, EPROTO);
        goto out;
    }
    if (client->shell.phase != CTOS_SSH_SHELL_DISABLED
        && packet_type != SSH_MSG_NONE) {
        result = shell_packet(client, packet_type);
        if (result != 0) goto out;
    } else if (client->auth_phase != AUTH_DISABLED
        && packet_type != SSH_MSG_NONE && packet_type != SSH2_MSG_EXT_INFO) {
        result = auth_packet(client, packet_type);
        if (result != 0 || client->auth_phase == AUTH_FINISHED) goto out;
    } else if (packet_type == SSH2_MSG_SERVICE_ACCEPT
        && client->service_phase != USERAUTH_SERVICE_DISABLED) {
        result = accept_userauth_service(client);
        goto out;
    } else if (packet_type != SSH_MSG_NONE && packet_type != SSH2_MSG_EXT_INFO) {
        result = terminal_error(client, EPROTO);
        goto out;
    }
transport_write:
    if (client->shell.phase != CTOS_SSH_SHELL_DISABLED
        && (established_live(client, cancelled) != 0 || shell_output(client) != 0)) goto out;
    output = ssh_output_ptr(client->ssh, &output_length);
    if (output == NULL || output_length == 0) {
        goto completion;
    }
    if (client->shell.phase != CTOS_SSH_SHELL_DISABLED && established_live(client, cancelled) != 0) goto out;
    write_length = output_length < client->step_budget
        ? output_length : client->step_budget;
    write_result = send(client->socket_fd, output, write_length, MSG_NOSIGNAL);
    if (write_result > 0) {
        step->bytes_written = (size_t)write_result;
        if ((size_t)write_result < output_length) step->partial_write = 1;
        if (ssh_output_consume(client->ssh, (size_t)write_result) != 0) {
            result = terminal_error(client, EIO);
            goto out;
        }
        step->output_consumed = (size_t)write_result;
    } else if (write_result < 0
        && (errno == EAGAIN || errno == EWOULDBLOCK)) {
        step->write_would_block = 1;
    } else if (write_result < 0 && errno == EINTR) {
        result = 0;
        goto out;
    } else {
        result = terminal_error(client, write_result == 0 ? EPIPE : EIO);
        goto out;
    }
completion:
    /* Inbound NEWKEYS may precede our queued NEWKEYS/EXT_INFO drain. Keep
     * bounded transport steps available until that output reaches the socket. */
    (void)ssh_output_ptr(client->ssh, &output_length);
    if (client->shell.phase != CTOS_SSH_SHELL_DISABLED && output_length == 0) {
        client->shell_request_drained = 1;
        if (client->shell_rejected_pending && client->local_close)
            client->shell.phase = CTOS_SSH_SHELL_REJECTED;
        else if (client->remote_close && client->local_close)
            client->shell.phase = CTOS_SSH_SHELL_CLOSED;
    }
    if (client->service_phase == USERAUTH_SERVICE_REQUEST_PENDING && output_length == 0)
        client->service_phase = USERAUTH_SERVICE_AWAIT_ACCEPT;
    if (client->auth_phase == AUTH_REQUEST_PENDING && output_length == 0)
        client->auth_phase = AUTH_AWAIT_REPLY;
    if (!client->completed && client->ssh->kex->done && output_length == 0
        && completion_digest(client) != 0) {
        result = terminal_error(client, EIO);
        goto out;
    }
    if (client->shell.phase != CTOS_SSH_SHELL_DISABLED && established_live(client, cancelled) != 0) goto out;
    result = 0;
out:
    wipe_bytes(input, sizeof(input));
    return result;
}

int ctos_ssh_native_client_verify_host_key(
    struct ctos_ssh_native_client *client, const void *host_key_blob,
    size_t host_key_blob_length) {
    struct sshkey *host_key = NULL;
    int result;

    if (client != NULL && client->host_key_mode == CTOS_SSH_HOST_KEY_MANAGED) {
        errno = EACCES;
        return -1;
    }
    if (client == NULL || host_key_blob == NULL || host_key_blob_length == 0
        || host_key_blob_length > CTOS_SSH_NATIVE_CLIENT_MAX_HOST_KEY_BLOB_BYTES
        || sshkey_from_blob(host_key_blob, host_key_blob_length, &host_key) != 0
        || host_key == NULL) {
        errno = EINVAL;
        return -1;
    }
    result = verify_host_key(host_key, client->ssh);
    sshkey_free(host_key);
    return result;
}

int ctos_ssh_native_client_complete(
    const struct ctos_ssh_native_client *client) {
    return client != NULL && !client->closed && client->completed;
}

int ctos_ssh_native_client_authentication_selection_receipt(
    const struct ctos_ssh_native_client *client, unsigned char receipt[32]) {
    struct ctos_ssh_role_snapshot snapshot = {0};
    uint64_t current;
    int result;
    int error;

    if (receipt != NULL) wipe_bytes(receipt, 32U);
    if (client == NULL || receipt == NULL) { errno = EINVAL; return -1; }
    if (client->closed) { errno = ENOENT; return -1; }
    if (client->failed) { errno = EALREADY; return -1; }
    if (client->authentication_selection_wire == NULL) { errno = EACCES; return -1; }
    if (ctos_ssh_callback_check_live(client->callback, client->callback_environment) != 0)
        return -1;
    result = ctos_ssh_role_bootstrap_snapshot(client->role, &snapshot);
    error = errno;
    if (result == 0 && (snapshot.token != client->role || snapshot.owner_pid != getpid()
        || snapshot.owner_uid != getuid() || snapshot.deadline != client->deadline)) {
        result = -1;
        error = EACCES;
    }
    wipe_bytes(&snapshot, sizeof(snapshot));
    if (result != 0) { errno = error; return -1; }
    if (view_borrow_is_live(client->managed_view_borrow) != 0) return -1;
    if (now_millis(&current) != 0) return -1;
    if (current >= client->deadline) { errno = ETIMEDOUT; return -1; }
    memcpy(receipt, client->authentication_selection_receipt, 32U);
    return 0;
}

int ctos_ssh_native_client_userauth_service_ready(
    const struct ctos_ssh_native_client *client) {
    return client != NULL && !client->closed && !client->failed
        && client->service_phase == USERAUTH_SERVICE_READY;
}

int ctos_ssh_native_client_close(struct ctos_ssh_native_client *client) {
    if (client == NULL) {
        errno = EINVAL;
        return -1;
    }
    if (client->closed) {
        errno = ENOENT;
        return -1;
    }
    if (!client->completed && client->candidate_handed_off
        && abort_handed_off_candidate(client) != 0) return -1;
    /* The callback binding was opened for this native event-loop owner. Close
     * it while the event-loop environment is still live, before touching the
     * OpenSSH object or owned descriptor. A failed release leaves the whole
     * client intact so the exact close can retry. */
    if (!client->callback_closed
        && ctos_ssh_callback_close(client->callback,
            client->callback_environment) != 0) return -1;
    client->callback_closed = 1;
    if (!client->kex_released && client->ssh != NULL) {
        ctos_ssh_kex_unbind(client->ssh);
        ssh_free(client->ssh);
        client->ssh = NULL;
        client->kex_released = 1;
    }
    if (!client->kex_released) client->kex_released = 1;
    if (!client->view_released && client->managed_view_borrow != NULL
        && ctos_ssh_view_borrow_release(client->managed_view_borrow) != 0) return -1;
    client->managed_view_borrow = NULL;
    client->managed_view = NULL;
    client->view_released = 1;
    if (client->server_host_public_key != NULL) {
        sshkey_free(client->server_host_public_key);
        client->server_host_public_key = NULL;
    }
    if (client->caller_socket_fd >= 0
        && close(client->caller_socket_fd) != 0) return -1;
    client->caller_socket_fd = -1;
    client->socket_fd = -1;
    wipe_bytes(client->shell_buffer, sizeof(client->shell_buffer));
    memset(&client->shell, 0, sizeof(client->shell));
    clear_authentication_selection(client);
    wipe_bytes(client->completion_session_digest, sizeof(client->completion_session_digest));
    wipe_bytes(client->established_session_digest, sizeof(client->established_session_digest));
    wipe_bytes(&client->established_role, sizeof(client->established_role));
    client->established = 0;
    client->established_ssh = NULL;
    client->established_host_key = NULL;
    client->auth_phase = AUTH_DISABLED;
    client->auth_result = CTOS_SSH_PASSWORD_AUTH_NOT_STARTED;
    client->auth_banners = 0;
    client->closed = 1;
    return 0;
}

int ctos_ssh_native_client_destroy(struct ctos_ssh_native_client *client) {
    if (client == NULL) {
        errno = EINVAL;
        return -1;
    }
    if (!client->closed) {
        errno = EBUSY;
        return -1;
    }
    wipe_bytes(client, sizeof(*client));
    free(client);
    return 0;
}

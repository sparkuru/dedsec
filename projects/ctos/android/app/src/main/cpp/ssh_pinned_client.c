#define _GNU_SOURCE

#include "ssh_pinned_client.h"
#include "ssh_hostfile_issuer.h"

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
#include "kex.h"
#include "myproposal.h"
#include "packet.h"
#include "ssh2.h"
#include "ssh_api.h"
#include "ssh_kex_guard.h"
#include "sshbuf.h"
#include "ssherr.h"
#include "sshkey.h"

/* The pinned generated config maps malloc/calloc to unavailable configure
 * replacements for the stock Android build.  This fixture owns its small
 * wrapper allocation and intentionally uses the platform allocator. */
#ifdef malloc
#undef malloc
#endif
#ifdef calloc
#undef calloc
#endif

struct ctos_ssh_pinned_client {
    struct ssh *ssh;
    ctos_ssh_role_token role;
    ctos_ssh_callback_token callback;
    void *callback_environment;
    const struct ctos_ssh_view *managed_view;
    int socket_fd;
    int closed;
    int failed;
    int completed;
    int candidate_handed_off;
    unsigned int completion_calls;
    uint64_t deadline;
    unsigned char expected_host_fingerprint[CTOS_SSH_PINNED_CLIENT_FINGERPRINT_BYTES];
    char known_hosts_path[513];
    char host[CTOS_SSH_ROLE_HOST_BYTES + 1U];
    char candidate_id[CTOS_SSH_CALLBACK_CANDIDATE_ID_BYTES];
    size_t read_chunk;
    size_t write_chunk;
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

static void wipe_bytes(void *memory, size_t length) {
    volatile unsigned char *bytes = memory;

    if (bytes == NULL) return;
    while (length-- != 0) *bytes++ = 0;
}

static int role_is_current(const struct ctos_ssh_pinned_client_config *config) {
    struct ctos_ssh_role_snapshot snapshot;
    uint64_t current;
    size_t host_length;

    memset(&snapshot, 0, sizeof(snapshot));
    if (config == NULL || config->host == NULL
        || bounded_text(config->host, CTOS_SSH_ROLE_HOST_BYTES, &host_length) != 0
        || config->role == 0 || config->deadline == 0
        || config->deadline > INT64_MAX || now_millis(&current) != 0
        || config->deadline <= current
        || ctos_ssh_role_bootstrap_snapshot(config->role, &snapshot) != 0
        || snapshot.token != config->role || snapshot.owner_pid != getpid()
        || snapshot.owner_uid != getuid() || snapshot.deadline != config->deadline
        || strcmp(snapshot.host, config->host) != 0) {
        wipe_bytes(&snapshot, sizeof(snapshot));
        if (errno == 0) errno = EACCES;
        return -1;
    }
    (void)host_length;
    wipe_bytes(&snapshot, sizeof(snapshot));
    return 0;
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

static int validate_descriptor(int fd) {
    int descriptor_flags;
    int flags;
    int socket_type;
    socklen_t socket_type_length = sizeof(socket_type);

    if (fd < 0 || (descriptor_flags = fcntl(fd, F_GETFD)) < 0
        || (descriptor_flags & FD_CLOEXEC) == 0
        || (flags = fcntl(fd, F_GETFL)) < 0
        || (flags & O_ACCMODE) != O_RDWR || (flags & O_NONBLOCK) == 0
        || getsockopt(fd, SOL_SOCKET, SO_TYPE, &socket_type,
            &socket_type_length) != 0 || socket_type != SOCK_STREAM) {
        errno = EINVAL;
        return -1;
    }
    return 0;
}

static int fingerprint_matches(struct ctos_ssh_pinned_client *client,
    const struct sshkey *host_key) {
    unsigned char *actual = NULL;
    size_t length = 0;
    int result;

    if (client == NULL || host_key == NULL
        || sshkey_fingerprint_raw(host_key, SSH_DIGEST_SHA256,
            &actual, &length) != 0 || length != 32) {
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
    const struct ctos_ssh_pinned_client *client,
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
        && result->hop == snapshot.hop
        && result->port == snapshot.port
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

static int abort_handed_off_candidate(struct ctos_ssh_pinned_client *client) {
    if (client == NULL || !client->candidate_handed_off) return 0;
    if (ctos_ssh_callback_known_hosts_candidate_abort(
            client->callback, client->callback_environment) != 0) return -1;
    client->candidate_handed_off = 0;
    return 0;
}

static int verify_host_key(struct sshkey *host_key, struct ssh *ssh) {
    struct ctos_ssh_pinned_client *client;
    struct ctos_ssh_hostfile_issue issue;
    struct ctos_ssh_host_result result;
    unsigned char *replacement = NULL;
    FILE *stream = NULL;
    const size_t replacement_capacity = CTOS_SSH_CALLBACK_MAX_FILE_BYTES + 1U;
    int return_code = -1;
    int classification;
    int callback_result;
    int close_result;

    memset(&issue, 0, sizeof(issue));
    memset(&result, 0, sizeof(result));
    client = ssh == NULL ? NULL : ssh_get_app_data(ssh);
    if (client == NULL || client->closed || client->failed || client->completed
        || client->managed_view == NULL || host_key == NULL
        || fingerprint_matches(client, host_key) != 0) {
        errno = EACCES;
        goto out;
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
    if (callback_result != 0) {
        goto out;
    }
    if (close_result != 0) {
        errno = EIO;
        goto out;
    }
    classification = issue.classification;
    if (classification == CTOS_SSH_CALLBACK_HOST_KNOWN
        || classification == CTOS_SSH_CALLBACK_HOST_NEW) {
        if (classification == CTOS_SSH_CALLBACK_HOST_NEW && !issue.handed_off) {
            errno = EACCES;
            goto out;
        }
        callback_result = ctos_ssh_callback_host_evidence(client->callback,
            client->callback_environment, issue.classification, issue.key_type,
            issue.fingerprint, issue.base_digest, issue.replacement_digest,
            &result);
        if (callback_result != 0) {
            goto out;
        }
        if (host_result_matches_issue(client, &issue, &result) != 0) {
            goto out;
        }
    } else {
        /* CHANGED, REVOKED, CERTIFICATE and INVALID never enter KEX. */
        errno = EACCES;
        goto out;
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
    wipe_bytes(&result, sizeof(result));
    wipe_bytes(&issue, sizeof(issue));
    if (replacement != NULL) {
        wipe_bytes(replacement, replacement_capacity);
        free(replacement);
    }
    return return_code;
}

static int completion_digest(struct ctos_ssh_pinned_client *client) {
    struct ctos_ssh_role_snapshot snapshot;
    unsigned char digest[32];
    uint64_t cycle;
    int result = -1;

    memset(&snapshot, 0, sizeof(snapshot));
    memset(digest, 0, sizeof(digest));
    if (client == NULL || client->ssh == NULL || client->ssh->kex == NULL
        || !client->ssh->kex->done
        || client->completion_calls != 0U
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
    result = 0;
out:
    wipe_bytes(&snapshot, sizeof(snapshot));
    wipe_bytes(digest, sizeof(digest));
    return result;
}

static int terminal_error(struct ctos_ssh_pinned_client *client, int error) {
    if (client != NULL) {
        if (!client->completed && client->candidate_handed_off)
            (void)abort_handed_off_candidate(client);
        client->failed = 1;
    }
    errno = error;
    return -1;
}

int ctos_ssh_pinned_client_open(
    const struct ctos_ssh_pinned_client_config *config,
    struct ctos_ssh_pinned_client **client_out) {
    struct ctos_ssh_pinned_client *client = NULL;
    struct kex_params params;
    char *proposals[PROPOSAL_MAX] = {KEX_CLIENT};
    size_t path_length;
    size_t candidate_length;
    int attached = 0;
    int flags;

    if (client_out == NULL) {
        errno = EINVAL;
        return -1;
    }
    *client_out = NULL;
    if (config == NULL || config->owns_socket != 1
        || validate_descriptor(config->socket_fd) != 0
        || config->role == 0 || config->callback == 0
        || config->callback_environment == NULL
        || config->server_host_public_key == NULL
        || config->expected_host_fingerprint == NULL
        || !nonzero32(config->expected_host_fingerprint)
        || config->managed_view == NULL
        || config->known_hosts_path == NULL || config->host == NULL
        || config->candidate_id == NULL || role_is_current(config) != 0
        || ctos_ssh_view_live(config->managed_view) != 0
        || bounded_text(config->known_hosts_path, 512U, &path_length) != 0
        || bounded_text(config->candidate_id,
            CTOS_SSH_CALLBACK_CANDIDATE_ID_BYTES - 1U, &candidate_length) != 0
        || ctos_ssh_view_lookup(config->managed_view,
            (const unsigned char *)config->known_hosts_path, path_length,
            CTOS_SSH_KNOWN_HOSTS) == NULL
        || config->read_chunk > CTOS_SSH_PINNED_CLIENT_MAX_STEP_BYTES
        || config->write_chunk > CTOS_SSH_PINNED_CLIENT_MAX_STEP_BYTES) {
        if (errno == 0) errno = EINVAL;
        return -1;
    }
    (void)path_length;
    (void)candidate_length;
    client = malloc(sizeof(*client));
    if (client == NULL) {
        errno = ENOMEM;
        return -1;
    }
    memset(client, 0, sizeof(*client));
    client->role = config->role;
    client->callback = config->callback;
    client->callback_environment = config->callback_environment;
    client->managed_view = config->managed_view;
    client->socket_fd = config->socket_fd;
    client->deadline = config->deadline;
    memcpy(client->expected_host_fingerprint, config->expected_host_fingerprint,
        sizeof(client->expected_host_fingerprint));
    if (copy_config_text(client->known_hosts_path,
            sizeof(client->known_hosts_path), config->known_hosts_path, 512U) != 0
        || copy_config_text(client->host, sizeof(client->host), config->host,
            CTOS_SSH_ROLE_HOST_BYTES) != 0
        || copy_config_text(client->candidate_id, sizeof(client->candidate_id),
            config->candidate_id, CTOS_SSH_CALLBACK_CANDIDATE_ID_BYTES - 1U) != 0) {
        goto failed;
    }
    client->read_chunk = config->read_chunk == 0 ? 4096U : config->read_chunk;
    client->write_chunk = config->write_chunk == 0 ? 4096U : config->write_chunk;
    params = (struct kex_params){.proposal = {0}};
    memcpy(params.proposal, proposals, sizeof(proposals));
    if (ssh_init(&client->ssh, 0, &params) != 0 || client->ssh == NULL) goto failed;
    if (ssh_packet_set_connection(client->ssh, client->socket_fd,
            client->socket_fd) == NULL) goto failed;
    attached = 1;
    ssh_packet_set_nonblocking(client->ssh);
    flags = fcntl(client->socket_fd, F_GETFL);
    if (flags < 0 || (flags & O_NONBLOCK) == 0
        || ssh_add_hostkey(client->ssh,
            (struct sshkey *)config->server_host_public_key) != 0
        || ctos_ssh_kex_bind_role(client->ssh, client->role,
            client->expected_host_fingerprint) != 0) {
        goto failed;
    }
    ssh_set_app_data(client->ssh, client);
    if (ssh_set_verify_host_key_callback(client->ssh, verify_host_key) != 0) {
        goto failed;
    }
    *client_out = client;
    return 0;

failed:
    if (attached && client->ssh != NULL) {
        ctos_ssh_kex_unbind(client->ssh);
        ssh_free(client->ssh);
    } else if (client->ssh != NULL) {
        ssh_free(client->ssh);
    }
    client->ssh = NULL;
    client->socket_fd = -1;
    wipe_bytes(client, sizeof(*client));
    free(client);
    if (errno == 0) errno = EIO;
    return -1;
}

int ctos_ssh_pinned_client_step(
    struct ctos_ssh_pinned_client *client,
    struct ctos_ssh_pinned_client_step *step) {
    unsigned char input[CTOS_SSH_PINNED_CLIENT_MAX_STEP_BYTES];
    const unsigned char *output;
    size_t output_length;
    size_t read_length;
    size_t write_length;
    ssize_t read_result;
    ssize_t write_result;
    uint64_t current;
    u_char packet_type = SSH_MSG_NONE;
    int result;

    int cancelled;

    cancelled = step != NULL && step->cancelled;
    if (step != NULL) memset(step, 0, sizeof(*step));
    if (client == NULL || step == NULL) {
        errno = EINVAL;
        return -1;
    }
    if (client->closed) {
        errno = ENOENT;
        return -1;
    }
    if (client->completed || client->failed) {
        errno = EALREADY;
        return -1;
    }
    if (cancelled) return terminal_error(client, ECANCELED);
    if (now_millis(&current) != 0 || current >= client->deadline)
        return terminal_error(client, ETIMEDOUT);
    read_length = client->read_chunk;
    if (read_length > sizeof(input)) read_length = sizeof(input);
    read_result = recv(client->socket_fd, input, read_length, MSG_DONTWAIT);
    if (read_result > 0) {
        step->bytes_read = (size_t)read_result;
        if (ssh_input_append(client->ssh, input, (size_t)read_result) != 0)
            return terminal_error(client, EIO);
        step->input_appended = (size_t)read_result;
    } else if (read_result == 0) {
        return terminal_error(client, EPIPE);
    } else if (errno == EAGAIN || errno == EWOULDBLOCK) {
        step->read_would_block = 1;
    } else if (errno != EINTR) {
        return terminal_error(client, errno == ECONNRESET ? ECONNRESET : EIO);
    }
    result = ssh_packet_next(client->ssh, &packet_type);
    if (result != 0) return terminal_error(client, EIO);
    step->packet_type = packet_type;
    if (packet_type == SSH2_MSG_EXT_INFO
        && kex_input_ext_info(packet_type, 0, client->ssh) != 0)
        return terminal_error(client, EPROTO);
    if (packet_type != SSH_MSG_NONE && packet_type != SSH2_MSG_EXT_INFO)
        return terminal_error(client, EPROTO);
    if (client->ssh->kex->done && completion_digest(client) != 0)
        return terminal_error(client, EIO);

    output = ssh_output_ptr(client->ssh, &output_length);
    if (output == NULL || output_length == 0) return 0;
    write_length = output_length < client->write_chunk
        ? output_length : client->write_chunk;
    write_result = send(client->socket_fd, output, write_length, MSG_NOSIGNAL);
    if (write_result > 0) {
        step->bytes_written = (size_t)write_result;
        if ((size_t)write_result < output_length) step->partial_write = 1;
        if (ssh_output_consume(client->ssh, (size_t)write_result) != 0)
            return terminal_error(client, EIO);
        step->output_consumed = (size_t)write_result;
    } else if (write_result < 0
        && (errno == EAGAIN || errno == EWOULDBLOCK)) {
        step->write_would_block = 1;
    } else if (write_result < 0 && errno == EINTR) {
        return 0;
    } else {
        return terminal_error(client, write_result == 0 ? EPIPE : EIO);
    }
    return 0;
}

int ctos_ssh_pinned_client_complete(
    const struct ctos_ssh_pinned_client *client) {
    return client != NULL && !client->closed && client->completed;
}

int ctos_ssh_pinned_client_close(struct ctos_ssh_pinned_client *client) {
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
    client->closed = 1;
    if (client->ssh != NULL) {
        ctos_ssh_kex_unbind(client->ssh);
        ssh_free(client->ssh);
        client->ssh = NULL;
    }
    client->socket_fd = -1;
    return 0;
}

int ctos_ssh_pinned_client_destroy(struct ctos_ssh_pinned_client *client) {
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

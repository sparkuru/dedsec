#define _GNU_SOURCE

#include "ssh_hostfile_issuer.h"

#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "digest.h"
#include "hostfile.h"
#include "sshkey.h"

#define CTOS_SSH_HOSTFILE_SYNTHETIC_PATH "ctos-managed-known_hosts"

static int slice_is(struct ctos_ssh_slice value, const char *literal) {
    size_t length = strlen(literal);
    return value.data != NULL && value.length == length
        && memcmp(value.data, literal, length) == 0;
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

static int managed_known_hosts_root(const struct ctos_ssh_view *view) {
    static const unsigned char root_path[] = "ssh/config";
    static const unsigned char known_hosts_path[] = "ssh/known_hosts";
    const struct ctos_ssh_file *root;

    if (view_slices_are_safe(view) != 0
        || !slice_is(view->root, (const char *)root_path)) {
        errno = EACCES;
        return -1;
    }
    root = ctos_ssh_view_lookup(view, root_path, sizeof(root_path) - 1U,
        CTOS_SSH_CONFIG);
    if (root == NULL) return -1;
    for (unsigned int index = 0; index < root->ref_count; index++) {
        if (slice_is(root->refs[index], (const char *)known_hosts_path)) return 0;
    }
    errno = EACCES;
    return -1;
}

static void wipe_bytes(unsigned char *bytes, size_t length) {
    if (bytes == NULL) return;
    volatile unsigned char *target = bytes;
    while (length-- != 0) *target++ = 0;
}

static int bounded_text(const char *value, size_t maximum, size_t *length) {
    size_t actual;
    if (value == NULL) {
        errno = EINVAL;
        return -1;
    }
    actual = strnlen(value, maximum + 1U);
    if (actual == 0 || actual > maximum) {
        errno = EINVAL;
        return -1;
    }
    for (size_t index = 0; index < actual; index++) {
        unsigned char character = (unsigned char)value[index];
        if (character < 0x21U || character > 0x7eU) {
            errno = EINVAL;
            return -1;
        }
    }
    if (length != NULL) *length = actual;
    return 0;
}

static int valid_candidate_id(const char *value) {
    size_t length;
    if (bounded_text(value, CTOS_SSH_CALLBACK_CANDIDATE_ID_BYTES - 1U, &length) != 0)
        return -1;
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

static int same_slice_text(struct ctos_ssh_slice slice, const char *value) {
    size_t length;
    if (value == NULL || bounded_text(value, CTOS_SSH_ROLE_CONNECTION_ID_BYTES, &length) != 0
        || slice.data == NULL || slice.length != length
        || memcmp(slice.data, value, length) != 0) {
        errno = EACCES;
        return -1;
    }
    return 0;
}

static int classify_status(HostStatus status) {
    switch (status) {
    case HOST_OK:
        return CTOS_SSH_CALLBACK_HOST_KNOWN;
    case HOST_NEW:
        return CTOS_SSH_CALLBACK_HOST_NEW;
    case HOST_CHANGED:
        return CTOS_SSH_CALLBACK_HOST_CHANGED;
    case HOST_REVOKED:
        return CTOS_SSH_CALLBACK_HOST_REVOKED;
    default:
        errno = EINVAL;
        return -1;
    }
}

struct hostfile_parse_state {
    int invalid;
};

static int remember_invalid_hostfile_line(struct hostkey_foreach_line *line,
    void *context) {
    struct hostfile_parse_state *state = context;

    if (line == NULL || state == NULL) return -1;
    if (line->status == HKF_STATUS_INVALID) state->invalid = 1;
    return 0;
}

static int digest_body(const unsigned char *body, size_t length,
    unsigned char digest[32]) {
    static const unsigned char empty_body = 0;
    if (body == NULL && length != 0) {
        errno = EINVAL;
        return -1;
    }
    if (ssh_digest_memory(SSH_DIGEST_SHA256,
            length == 0 ? &empty_body : body, length, digest, 32) != 0) {
        errno = EIO;
        return -1;
    }
    return 0;
}

static int copy_replacement(FILE *stream, unsigned char *body, size_t capacity,
    size_t *length) {
    long end;
    size_t actual;
    if (stream == NULL || body == NULL || length == NULL || capacity == 0
        || fflush(stream) != 0 || fseek(stream, 0, SEEK_END) != 0) {
        errno = EIO;
        return -1;
    }
    end = ftell(stream);
    if (end < 0 || (unsigned long)end > CTOS_SSH_CALLBACK_MAX_FILE_BYTES
        || (unsigned long)end >= capacity) {
        errno = EOVERFLOW;
        return -1;
    }
    actual = (size_t)end;
    if (fseek(stream, 0, SEEK_SET) != 0
        || (actual != 0 && fread(body, 1, actual, stream) != actual)) {
        errno = EIO;
        return -1;
    }
    *length = actual;
    return 0;
}

static void wipe_output(unsigned char *body, size_t capacity) {
    if (body == NULL) return;
    if (capacity > CTOS_SSH_CALLBACK_MAX_FILE_BYTES + 1U)
        capacity = CTOS_SSH_CALLBACK_MAX_FILE_BYTES + 1U;
    wipe_bytes(body, capacity);
}

int ctos_ssh_hostfile_issue(const struct ctos_ssh_view *view,
    ctos_ssh_role_token role, const char *known_hosts_path, const char *host,
    const struct sshkey *host_key, const char *candidate_id,
    FILE *replacement_stream, unsigned char *replacement_body,
    size_t replacement_capacity, ctos_ssh_callback_token callback,
    void *environment, struct ctos_ssh_hostfile_issue *issue) {
    struct ctos_ssh_role_snapshot snapshot;
    const struct ctos_ssh_file *file = NULL;
    struct hostkeys *hostkeys = NULL;
    FILE *input = NULL;
    char *fingerprint = NULL;
    const char *key_type = NULL;
    HostStatus status;
    int classification;
    int error = EINVAL;
    int parse_result;
    size_t path_length;
    size_t host_length;
    char lookup_host[CTOS_SSH_ROLE_HOST_BYTES + sizeof("[]:65535")];
    struct hostfile_parse_state parse_state = {0};

    if (issue == NULL) {
        errno = EINVAL;
        return -1;
    }
    memset(issue, 0, sizeof(*issue));
    memset(&snapshot, 0, sizeof(snapshot));
    errno = 0;
    if (view == NULL || role == 0 || known_hosts_path == NULL || host == NULL
        || host_key == NULL || valid_candidate_id(candidate_id) != 0
        || (callback == 0) != (environment == NULL)
        || bounded_text(known_hosts_path, 512U, &path_length) != 0
        || bounded_text(host, CTOS_SSH_ROLE_HOST_BYTES, &host_length) != 0
        || ctos_ssh_role_bootstrap_snapshot(role, &snapshot) != 0
        || snapshot.hop >= CTOS_SSH_ROLE_MAX || snapshot.port == 0
        || strcmp(snapshot.host, host) != 0
        || view->deadline < snapshot.deadline
        || same_slice_text(view->connection, snapshot.connection_id) != 0
        || ctos_ssh_view_live(view) != 0
        || managed_known_hosts_root(view) != 0) {
        error = errno == 0 ? EINVAL : errno;
        goto failed;
    }
    (void)path_length;
    (void)host_length;
    /* Match upstream put_host_port without its fatal allocator path. Role and
     * callback evidence retain the original host and separate port binding. */
    int lookup_length = snapshot.port == 22U
        ? snprintf(lookup_host, sizeof(lookup_host), "%s", host)
        : snprintf(lookup_host, sizeof(lookup_host), "[%s]:%u", host, snapshot.port);
    if (lookup_length <= 0 || (size_t)lookup_length >= sizeof(lookup_host)) {
        error = EOVERFLOW;
        goto failed;
    }
    /* Upstream host entry writers lowercase before hashing. Hashed lookup is
     * case-sensitive, so canonicalize only this internal endpoint identifier. */
    for (size_t index = 0; index < (size_t)lookup_length; index++) {
        if (lookup_host[index] >= 'A' && lookup_host[index] <= 'Z')
            lookup_host[index] = (char)(lookup_host[index] - 'A' + 'a');
    }
    file = ctos_ssh_view_lookup(view, (const unsigned char *)known_hosts_path,
        strlen(known_hosts_path), CTOS_SSH_KNOWN_HOSTS);
    if (file == NULL || file->body.length > CTOS_SSH_CALLBACK_MAX_FILE_BYTES
        || file->declared_bytes != file->body.length) {
        error = errno == 0 ? EACCES : errno;
        goto failed;
    }
    if (digest_body(file->body.data, file->body.length, issue->base_digest) != 0) {
        error = errno;
        goto failed;
    }
    key_type = sshkey_ssh_name(host_key);
    if (key_type == NULL
        || (fingerprint = sshkey_fingerprint(host_key, SSH_DIGEST_SHA256,
            SSH_FP_DEFAULT)) == NULL
        || strlen(key_type) >= sizeof(issue->key_type)
        || strlen(fingerprint) >= sizeof(issue->fingerprint)) {
        error = EIO;
        goto failed;
    }
    strcpy(issue->key_type, key_type);
    strcpy(issue->fingerprint, fingerprint);
    strcpy(issue->candidate_id, candidate_id);

    if (file->body.length != 0) {
        input = fmemopen((void *)file->body.data, file->body.length, "r");
        if (input == NULL) {
            error = errno == 0 ? EIO : errno;
            goto failed;
        }
        /* Keep the bound host in the parser even without HKF_WANT_MATCH: the
         * preflight still visits every line, while upstream can reject a
         * malformed hashed host token instead of silently treating it as a
         * non-match and allowing NEW. */
        parse_result = hostkeys_foreach_file(CTOS_SSH_HOSTFILE_SYNTHETIC_PATH,
            input, remember_invalid_hostfile_line, &parse_state, lookup_host, NULL,
            HKF_WANT_PARSE_KEY, 0);
        if (parse_result != 0) {
            error = EIO;
            goto failed;
        }
        if (parse_state.invalid) {
            issue->classification = CTOS_SSH_CALLBACK_HOST_INVALID;
            memcpy(issue->replacement_digest, issue->base_digest,
                sizeof(issue->replacement_digest));
            fclose(input);
            input = NULL;
            free(fingerprint);
            fingerprint = NULL;
            return 0;
        }
        if (fseek(input, 0, SEEK_SET) != 0) {
            error = EIO;
            goto failed;
        }
    }
    hostkeys = init_hostkeys();
    if (hostkeys == NULL) {
        error = ENOMEM;
        goto failed;
    }
    if (input != NULL) {
        load_hostkeys_file(hostkeys, lookup_host, CTOS_SSH_HOSTFILE_SYNTHETIC_PATH,
            input, 0);
        fclose(input);
        input = NULL;
    }
    status = check_key_in_hostkeys(hostkeys, (struct sshkey *)host_key, NULL);
    if (sshkey_is_cert(host_key) && status != HOST_REVOKED) {
        /* Certificates are not an approved Java trust policy yet. Still use
         * upstream's revocation walk first so a revoked certificate or CA is
         * never hidden behind the generic certificate denial. */
        classification = CTOS_SSH_CALLBACK_HOST_CERTIFICATE;
    } else if ((classification = classify_status(status)) < 0) {
        error = errno == 0 ? EINVAL : errno;
        goto failed;
    }
    issue->classification = classification;
    memcpy(issue->replacement_digest, issue->base_digest,
        sizeof(issue->replacement_digest));

    free_hostkeys(hostkeys);
    hostkeys = NULL;
    free(fingerprint);
    fingerprint = NULL;

    if (classification != CTOS_SSH_CALLBACK_HOST_NEW) return 0;
    if (replacement_stream == NULL || replacement_body == NULL
        || replacement_capacity <= CTOS_SSH_CALLBACK_MAX_FILE_BYTES
        || (callback != 0 && environment == NULL)) {
        error = EINVAL;
        goto failed;
    }
    if (fseek(replacement_stream, 0, SEEK_SET) != 0
        || (file->body.length != 0
            && fwrite(file->body.data, 1, file->body.length, replacement_stream)
                != file->body.length)) {
        error = EIO;
        goto failed;
    }
    if (file->body.length != 0) {
        if (fflush(replacement_stream) != 0
            || fseek(replacement_stream, -1, SEEK_END) != 0) {
            error = EIO;
            goto failed;
        }
        int last = fgetc(replacement_stream);
        if (last == EOF) {
            error = EIO;
            goto failed;
        }
        if (fseek(replacement_stream, 0, SEEK_END) != 0) {
            error = EIO;
            goto failed;
        }
        if (last != '\n' && fputc('\n', replacement_stream) == EOF) {
            error = EIO;
            goto failed;
        }
    }
    if (ctos_hostfile_write_entry(replacement_stream, lookup_host, NULL, host_key, 0) != 1
        || copy_replacement(replacement_stream, replacement_body,
            replacement_capacity, &issue->replacement_body_length) != 0
        || digest_body(replacement_body, issue->replacement_body_length,
            issue->replacement_digest) != 0) {
        error = errno == 0 ? EIO : errno;
        goto failed;
    }
    issue->replacement_body = replacement_body;
    if (callback != 0) {
        if (ctos_ssh_callback_known_hosts_candidate(callback, environment,
                issue->candidate_id, issue->classification, issue->key_type,
                issue->fingerprint, issue->base_digest,
                issue->replacement_digest, replacement_body,
                issue->replacement_body_length) != 0) {
            error = errno == 0 ? EIO : errno;
            goto failed;
        }
        issue->handed_off = 1;
    }
    return 0;

failed:
    if (input != NULL) fclose(input);
    if (hostkeys != NULL) free_hostkeys(hostkeys);
    free(fingerprint);
    wipe_output(replacement_body, replacement_capacity);
    memset(issue, 0, sizeof(*issue));
    errno = error == 0 ? EIO : error;
    return -1;
}

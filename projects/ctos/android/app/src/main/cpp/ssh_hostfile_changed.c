#define _GNU_SOURCE

#include "ssh_hostfile_issuer.h"
#include "ssh_host_trust.h"

#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "digest.h"
#include "hostfile.h"
#include "sshkey.h"

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

static uint32_t read_be32(const unsigned char *bytes) {
    return (uint32_t)bytes[0] << 24 | (uint32_t)bytes[1] << 16
        | (uint32_t)bytes[2] << 8 | (uint32_t)bytes[3];
}

static void write_be32(unsigned char *bytes, uint32_t value) {
    bytes[0] = (unsigned char)(value >> 24);
    bytes[1] = (unsigned char)(value >> 16);
    bytes[2] = (unsigned char)(value >> 8);
    bytes[3] = (unsigned char)value;
}

int ctos_ssh_hostfile_prepare_changed(const struct ctos_ssh_view *view,
    ctos_ssh_role_token role, const char *known_hosts_path, const char *host,
    const struct sshkey *host_key, const char *candidate_id,
    FILE *replacement_stream, unsigned char *replacement_body,
    size_t replacement_capacity, ctos_ssh_callback_token callback,
    void *environment, struct ctos_ssh_hostfile_issue *issue) {
    struct ctos_ssh_role_snapshot snapshot = {0};
    struct ctos_ssh_hostfile_issue verified = {0};
    const struct ctos_ssh_file *file;
    unsigned char *wire = NULL;
    size_t wire_length = 0, host_length = 0, path_length = 0, rows_length, remaining;
    char lookup_host[CTOS_SSH_ROLE_HOST_BYTES + sizeof("[]:65535")] = {0};
    uint32_t count, flags;
    int result = -1, error = EINVAL;

    if (issue == NULL || issue->classification != CTOS_SSH_CALLBACK_HOST_CHANGED
        || issue->handed_off || replacement_stream == NULL || replacement_body == NULL
        || replacement_capacity <= CTOS_SSH_CALLBACK_MAX_FILE_BYTES || callback == 0
        || environment == NULL || bounded_text(host, CTOS_SSH_ROLE_HOST_BYTES, &host_length) != 0
        || bounded_text(known_hosts_path, 512U, &path_length) != 0
        || ctos_ssh_role_bootstrap_snapshot(role, &snapshot) != 0
        || ctos_ssh_hostfile_issue(view, role, known_hosts_path, host, host_key, candidate_id,
            NULL, NULL, 0, 0, NULL, &verified) != 0
        || verified.classification != CTOS_SSH_CALLBACK_HOST_CHANGED
        || strcmp(verified.key_type, issue->key_type) != 0
        || strcmp(verified.fingerprint, issue->fingerprint) != 0
        || strcmp(verified.candidate_id, issue->candidate_id) != 0
        || memcmp(verified.base_digest, issue->base_digest, 32U) != 0) goto done;
    file = ctos_ssh_view_lookup(view, (const unsigned char *)known_hosts_path,
        path_length, CTOS_SSH_KNOWN_HOSTS);
    if (file == NULL || ctos_ssh_view_live(view) != 0) goto done;
    if (ctos_ssh_host_trust_inspect(file->body.data, file->body.length,
            (const unsigned char *)host, host_length, snapshot.port, 1,
            snapshot.deadline, &wire, &wire_length) != 0) {
        error = errno == 0 ? EIO : errno;
        /* A bounded or unrepresentable record set cannot be safely replaced. */
        if (error == E2BIG || error == EOVERFLOW) result = 1;
        goto done;
    }
    if (wire_length < CTOS_SSH_HOST_TRUST_HEADER_BYTES
        || read_be32(wire) != 0x43534854U || read_be32(wire + 4) != 1U
        || read_be32(wire + 8) != 1U
        || memcmp(wire + 24, issue->base_digest, 32U) != 0) goto done;
    flags = read_be32(wire + 12);
    count = read_be32(wire + 16);
    if (count > CTOS_SSH_HOST_TRUST_RECORDS) goto done;
    rows_length = CTOS_SSH_HOST_TRUST_HEADER_BYTES
        + (size_t)count * CTOS_SSH_HOST_TRUST_RECORD_BYTES;
    remaining = read_be32(wire + 20);
    if (rows_length > wire_length || remaining != wire_length - rows_length) goto done;
    if (flags != 3U || count == 0U) {
        /* Report blocked rows without producing a replacement or candidate. */
        if (flags != 0U || remaining != 0U) goto done;
        result = 1;
        goto done;
    }
    int lookup_length = snapshot.port == 22U
        ? snprintf(lookup_host, sizeof(lookup_host), "%s", host)
        : snprintf(lookup_host, sizeof(lookup_host), "[%s]:%u", host, snapshot.port);
    if (lookup_length <= 0 || (size_t)lookup_length >= sizeof(lookup_host)) goto done;
    for (int index = 0; index < lookup_length; index++)
        if (lookup_host[index] >= 'A' && lookup_host[index] <= 'Z')
            lookup_host[index] = (char)(lookup_host[index] - 'A' + 'a');
    if (fseek(replacement_stream, 0, SEEK_SET) != 0
        || (remaining != 0 && fwrite(wire + rows_length, 1, remaining, replacement_stream) != remaining)
        || (remaining != 0 && wire[rows_length + remaining - 1U] != '\n'
            && fputc('\n', replacement_stream) == EOF)
        || ctos_hostfile_write_entry(replacement_stream, lookup_host, NULL, host_key, 0) != 1
        || copy_replacement(replacement_stream, replacement_body, replacement_capacity,
            &issue->replacement_body_length) != 0
        || digest_body(replacement_body, issue->replacement_body_length,
            issue->replacement_digest) != 0) {
        error = errno == 0 ? EIO : errno;
        goto done;
    }
    /* The recipient gets only query rows. The copied replacement was built
     * from the prepare result before this bounded metadata conversion. */
    write_be32(wire + 8, 0U);
    write_be32(wire + 12, 1U);
    write_be32(wire + 20, 0U);
    memset(wire + 56, 0, 32U);
    issue->replacement_body = replacement_body;
    if (ctos_ssh_callback_changed_host_candidate(callback, environment,
            issue->candidate_id, issue->key_type, issue->fingerprint,
            issue->base_digest, issue->replacement_digest, replacement_body,
            issue->replacement_body_length, wire, rows_length) != 0) {
        error = errno == 0 ? EIO : errno;
        goto done;
    }
    issue->handed_off = 1;
    result = 0;
done:
    if (result != 0) wipe_output(replacement_body, replacement_capacity);
    if (wire != NULL) { wipe_bytes(wire, wire_length); free(wire); }
    wipe_bytes((unsigned char *)&verified, sizeof(verified));
    wipe_bytes((unsigned char *)&snapshot, sizeof(snapshot));
    wipe_bytes((unsigned char *)lookup_host, sizeof(lookup_host));
    if (result < 0) errno = error;
    return result;
}

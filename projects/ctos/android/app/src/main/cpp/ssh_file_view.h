#ifndef CTOS_SSH_FILE_VIEW_H
#define CTOS_SSH_FILE_VIEW_H

#include <stddef.h>
#include <stdint.h>

#define CTOS_SSH_VIEW_FILES 128
#define CTOS_SSH_VIEW_FILE_BYTES (1024U * 1024U)
#define CTOS_SSH_VIEW_TOTAL_BYTES (8U * 1024U * 1024U)
#define CTOS_SSH_VIEW_WIRE_BYTES (12U * 1024U * 1024U)

enum ctos_ssh_file_type {
    CTOS_SSH_CONFIG = 1, CTOS_SSH_PRIVATE_KEY, CTOS_SSH_PUBLIC_KEY,
    CTOS_SSH_CERTIFICATE, CTOS_SSH_KNOWN_HOSTS
};
struct ctos_ssh_slice { const unsigned char *data; size_t length; };
struct ctos_ssh_file {
    unsigned int type, declared_bytes, ref_count;
    struct ctos_ssh_slice id, path, identity, body;
    struct ctos_ssh_slice refs[CTOS_SSH_VIEW_FILES];
};
struct ctos_ssh_view {
    int fd, locked, valid, cleanup_failed;
    unsigned char *mapping;
    size_t length;
    uint64_t generation, epoch, deadline;
    struct ctos_ssh_slice uuid, binding, request, connection, root;
    unsigned int count, private_count, public_bytes;
    struct ctos_ssh_file files[CTOS_SSH_VIEW_FILES];
};

/* Caller owns the context. Allocate/lock before copying untrusted wire bytes. */
int ctos_ssh_view_allocate(struct ctos_ssh_view *, size_t);
int ctos_ssh_view_validate(struct ctos_ssh_view *);
int ctos_ssh_view_live(const struct ctos_ssh_view *);
int ctos_ssh_view_path(const unsigned char *, size_t, int);
int ctos_ssh_view_destroy(struct ctos_ssh_view *);
/* Exact virtual vault path and type only; never falls back to a host file. */
const struct ctos_ssh_file *ctos_ssh_view_lookup(const struct ctos_ssh_view *,
    const unsigned char *, size_t, unsigned int);
#endif

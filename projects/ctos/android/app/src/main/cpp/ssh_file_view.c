#define _GNU_SOURCE
#include "ssh_file_view.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/memfd.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

struct cursor { const unsigned char *position, *end; };

static int bytes(struct cursor *c, size_t length, struct ctos_ssh_slice *out) {
    if (length > (size_t)(c->end - c->position)) return -1;
    out->data = c->position; out->length = length; c->position += length;
    return 0;
}

static int u32(struct cursor *c, uint32_t *value) {
    struct ctos_ssh_slice part;
    if (bytes(c, 4, &part) != 0) return -1;
    *value = ((uint32_t)part.data[0] << 24) | ((uint32_t)part.data[1] << 16)
        | ((uint32_t)part.data[2] << 8) | (uint32_t)part.data[3];
    return 0;
}

static int u64(struct cursor *c, uint64_t *value) {
    uint32_t high, low;
    if (u32(c, &high) != 0 || u32(c, &low) != 0) return -1;
    *value = ((uint64_t)high << 32) | low;
    return *value <= INT64_MAX ? 0 : -1;
}

static int utf8(const struct ctos_ssh_slice *s) {
    size_t i = 0;
    while (i < s->length) {
        unsigned int ch = s->data[i++], extra, scalar;
        if (ch < 0x80) { if (ch < 32 || ch == 127) return -1; continue; }
        if (ch >= 0xc2 && ch <= 0xdf) { extra = 1; scalar = ch & 0x1f; }
        else if (ch >= 0xe0 && ch <= 0xef) { extra = 2; scalar = ch & 0xf; }
        else if (ch >= 0xf0 && ch <= 0xf4) { extra = 3; scalar = ch & 7; }
        else return -1;
        if (extra > s->length - i) return -1;
        for (unsigned int j = 0; j < extra; j++) {
            ch = s->data[i++];
            if ((ch & 0xc0) != 0x80) return -1;
            scalar = (scalar << 6) | (ch & 0x3f);
        }
        if ((extra == 1 && scalar < 0x80) || (extra == 2 && scalar < 0x800)
            || (extra == 3 && scalar < 0x10000) || scalar > 0x10ffff
            || (scalar >= 0xd800 && scalar <= 0xdfff)) return -1;
    }
    return 0;
}

static int text(struct cursor *c, size_t maximum, struct ctos_ssh_slice *out) {
    uint32_t length;
    if (u32(c, &length) != 0 || length == 0 || length > maximum
        || bytes(c, length, out) != 0 || utf8(out) != 0) return -1;
    return 0;
}

static int same(struct ctos_ssh_slice a, struct ctos_ssh_slice b) {
    return a.length == b.length && memcmp(a.data, b.data, a.length) == 0;
}

static int path(struct ctos_ssh_slice s, int pattern) {
    if (s.length == 0 || s.length > 512 || utf8(&s) != 0) return -1;
    size_t start = 0;
    unsigned int parts = 0;
    for (size_t i = 0; i <= s.length; i++) {
        unsigned int ch = i == s.length ? '/' : s.data[i];
        if (ch == '\\' || ch == ':' || ch == '%' || ch == '~'
            || (!pattern && (ch == '*' || ch == '?' || ch == '['))) return -1;
        if (ch != '/') continue;
        size_t length = i - start;
        if (length == 0 || ++parts > 8 || (length == 1 && s.data[start] == '.')
            || (length == 2 && s.data[start] == '.' && s.data[start + 1] == '.')) return -1;
        start = i + 1;
    }
    return 0;
}

int ctos_ssh_view_path(const unsigned char *name, size_t length, int pattern) {
    if (name == NULL || (pattern != 0 && pattern != 1)) { errno = EINVAL; return -1; }
    return path((struct ctos_ssh_slice){name, length}, pattern);
}

static int identifier(struct ctos_ssh_slice s) {
    for (size_t i = 0; i < s.length; i++) {
        unsigned int ch = s.data[i];
        if (!((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
            || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-')) return -1;
    }
    return 0;
}

static int find(const struct ctos_ssh_view *v, struct ctos_ssh_slice wanted) {
    for (unsigned int i = 0; i < v->count; i++) if (same(v->files[i].path, wanted)) return (int)i;
    return -1;
}

static int height(const struct ctos_ssh_view *v, unsigned int index,
    unsigned char *state, unsigned char *heights, unsigned int depth) {
    if (depth > 8 || state[index] == 1) return -1;
    if (state[index] == 2) return heights[index];
    state[index] = 1;
    int result = 0;
    const struct ctos_ssh_file *f = &v->files[index];
    for (unsigned int i = 0; i < f->ref_count; i++) {
        int child = find(v, f->refs[i]);
        if (child < 0) return -1;
        int child_height = height(v, (unsigned int)child, state, heights, depth + 1);
        if (child_height < 0 || child_height + 1 > 8) return -1;
        if (child_height + 1 > result) result = child_height + 1;
    }
    state[index] = 2; heights[index] = (unsigned char)result;
    return result;
}

int ctos_ssh_view_validate(struct ctos_ssh_view *v) {
    if (v == NULL || v->fd < 0 || v->mapping == NULL || !v->locked || v->valid) {
        errno = EINVAL; return -1;
    }
    struct cursor c = {v->mapping, v->mapping + v->length};
    uint32_t magic, version, count;
    if (u32(&c, &magic) != 0 || magic != 0x43535056U || u32(&c, &version) != 0 || version != 1
        || bytes(&c, 16, &v->uuid) != 0 || u64(&c, &v->generation) != 0 || v->generation == 0
        || u64(&c, &v->epoch) != 0 || u64(&c, &v->deadline) != 0 || v->deadline == 0
        || text(&c, 128, &v->request) != 0 || text(&c, 512, &v->connection) != 0
        || text(&c, 512, &v->root) != 0 || path(v->root, 0) != 0 || bytes(&c, 32, &v->binding) != 0
        || u32(&c, &count) != 0 || count == 0 || count > CTOS_SSH_VIEW_FILES) goto invalid;
    unsigned int uuid_nonzero = 0;
    for (size_t i = 0; i < v->uuid.length; i++) uuid_nonzero |= v->uuid.data[i];
    if (uuid_nonzero == 0) goto invalid;
    v->count = count;
    uint64_t total = 0;
    for (unsigned int i = 0; i < count; i++) {
        struct ctos_ssh_file *f = &v->files[i];
        struct ctos_ssh_slice type;
        uint32_t declared, refs, body;
        if (bytes(&c, 1, &type) != 0 || type.data[0] < CTOS_SSH_CONFIG
            || type.data[0] > CTOS_SSH_KNOWN_HOSTS) goto invalid;
        f->type = type.data[0];
        if (text(&c, 128, &f->id) != 0 || identifier(f->id) != 0
            || text(&c, 512, &f->path) != 0 || path(f->path, 0) != 0
            || text(&c, 512, &f->identity) != 0 || u32(&c, &declared) != 0
            || declared > CTOS_SSH_VIEW_FILE_BYTES || u32(&c, &refs) != 0
            || refs > CTOS_SSH_VIEW_FILES || (f->type != CTOS_SSH_CONFIG && refs != 0)) goto invalid;
        f->declared_bytes = declared; f->ref_count = refs;
        for (unsigned int j = 0; j < refs; j++) {
            if (text(&c, 512, &f->refs[j]) != 0 || path(f->refs[j], 0) != 0) goto invalid;
            for (unsigned int k = 0; k < j; k++) if (same(f->refs[j], f->refs[k])) goto invalid;
        }
        if (u32(&c, &body) != 0 || (f->type == CTOS_SSH_PRIVATE_KEY ? body != 0 : body != declared)
            || bytes(&c, body, &f->body) != 0) goto invalid;
        if (f->type == CTOS_SSH_PRIVATE_KEY) v->private_count++;
        else v->public_bytes += body;
        total += declared;
        if (total > CTOS_SSH_VIEW_TOTAL_BYTES) goto invalid;
        for (unsigned int j = 0; j < i; j++) {
            if (same(f->id, v->files[j].id) || same(f->path, v->files[j].path)) goto invalid;
            struct ctos_ssh_slice a = f->path, b = v->files[j].path;
            if (a.length > b.length) { struct ctos_ssh_slice swap = a; a = b; b = swap; }
            if (a.length < b.length && b.data[a.length] == '/' && memcmp(a.data, b.data, a.length) == 0) goto invalid;
        }
    }
    if (c.position != c.end) goto invalid;
    int root = find(v, v->root);
    if (root < 0 || v->files[root].type == CTOS_SSH_PRIVATE_KEY) goto invalid;
    unsigned char state[CTOS_SSH_VIEW_FILES] = {0}, heights[CTOS_SSH_VIEW_FILES] = {0};
    if (height(v, (unsigned int)root, state, heights, 0) < 0) goto invalid;
    for (unsigned int i = 0; i < count; i++) if (state[i] != 2) goto invalid;
    v->valid = 1;
    if (ctos_ssh_view_live(v) != 0) { v->valid = 0; return -1; }
    return 0;
invalid:
    errno = EINVAL;
    return -1;
}

int ctos_ssh_view_live(const struct ctos_ssh_view *v) {
    if (v == NULL || !v->valid) { errno = EACCES; return -1; }
    /* Android elapsedRealtime and Linux CLOCK_BOOTTIME both include suspend. */
    struct timespec now;
    if (clock_gettime(CLOCK_BOOTTIME, &now) != 0) return -1;
    if (now.tv_sec < 0 || now.tv_nsec < 0 || now.tv_nsec >= 1000000000
        || (uint64_t)now.tv_sec > ((uint64_t)INT64_MAX - 999) / 1000) { errno = EIO; return -1; }
    uint64_t millis = (uint64_t)now.tv_sec * 1000 + (uint64_t)now.tv_nsec / 1000000;
    if (millis >= v->deadline) { errno = ETIMEDOUT; return -1; }
    return 0;
}

int ctos_ssh_view_allocate(struct ctos_ssh_view *v, size_t length) {
    if (v == NULL || length == 0 || length > CTOS_SSH_VIEW_WIRE_BYTES) { errno = EINVAL; return -1; }
    memset(v, 0, sizeof(*v)); v->fd = -1;
    v->fd = (int)syscall(SYS_memfd_create, "ctos-ssh-public", MFD_CLOEXEC);
    if (v->fd < 0) return -1;
    v->length = length;
    struct stat shape;
    /* This internal memfd has no pathname or exported FD. Android app policy
     * may deny chmod on its inode; validate real anonymous ownership instead. */
    if (ftruncate(v->fd, (off_t)length) != 0 || fstat(v->fd, &shape) != 0) goto failed;
    int flags = fcntl(v->fd, F_GETFD);
    if (flags < 0) goto failed;
    if (!S_ISREG(shape.st_mode) || shape.st_uid != getuid() || shape.st_nlink != 0
        || shape.st_size != (off_t)length || (flags & FD_CLOEXEC) == 0) {
        errno = EACCES; goto failed;
    }
    void *mapping = mmap(NULL, length, PROT_READ | PROT_WRITE, MAP_SHARED, v->fd, 0);
    if (mapping == MAP_FAILED) goto failed;
    v->mapping = mapping;
    if (madvise(mapping, length, MADV_DONTDUMP) != 0 || mlock(mapping, length) != 0) goto failed;
    v->locked = 1;
    return 0;
failed: {
        int failure = errno == 0 ? EIO : errno;
        if (ctos_ssh_view_destroy(v) != 0) failure = EIO;
        errno = failure;
        return -1;
    }
}

int ctos_ssh_view_destroy(struct ctos_ssh_view *v) {
    if (v == NULL) { errno = EINVAL; return -1; }
    int failure = v->cleanup_failed ? EIO : 0;
    v->valid = 0;
    if (v->mapping != NULL) {
        volatile unsigned char *owned = v->mapping;
        for (size_t i = 0; i < v->length; i++) owned[i] = 0;
        for (size_t i = 0; i < v->length; i++) if (owned[i] != 0) { failure = EIO; break; }
        if (v->locked && munlock(v->mapping, v->length) != 0) failure = errno;
        v->locked = 0;
        if (munmap(v->mapping, v->length) != 0) failure = errno;
        else v->mapping = NULL;
    }
    /* A failed unmap still owns its backing file; truncating it would SIGBUS
     * a later wipe. Retain both resources until the unmap is confirmed. */
    if (v->fd >= 0 && v->mapping == NULL) {
        if (ftruncate(v->fd, 0) != 0) failure = errno;
        int fd = v->fd; v->fd = -1;
        /* Linux close consumes the descriptor even on most errors: never retry. */
        if (close(fd) != 0) failure = errno;
    }
    if (failure != 0) { v->cleanup_failed = 1; errno = failure; return -1; }
    memset(v, 0, sizeof(*v)); v->fd = -1;
    return 0;
}

const struct ctos_ssh_file *ctos_ssh_view_lookup(const struct ctos_ssh_view *v,
    const unsigned char *name, size_t length, unsigned int type) {
    if (v == NULL || !v->valid || name == NULL || length == 0 || length > 512
        || type < CTOS_SSH_CONFIG || type > CTOS_SSH_KNOWN_HOSTS) { errno = EINVAL; return NULL; }
    if (ctos_ssh_view_live(v) != 0) return NULL;
    struct ctos_ssh_slice wanted = {name, length};
    if (path(wanted, 0) != 0) { errno = EACCES; return NULL; }
    int index = find(v, wanted);
    if (index < 0) { errno = ENOENT; return NULL; }
    const struct ctos_ssh_file *f = &v->files[index];
    if (f->type != type || type == CTOS_SSH_PRIVATE_KEY) { errno = EACCES; return NULL; }
    return f;
}

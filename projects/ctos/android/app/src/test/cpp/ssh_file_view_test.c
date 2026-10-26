#define _GNU_SOURCE
#include "ssh_file_view.h"
#include "ssh_file_io.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

static unsigned int cases, wipe_checks, expected_wipes, chmod_calls;
static int fault, inspect_wipe, rejected_fd = -1;
enum { FAULT_LOCK = 1, FAULT_ADVISE, FAULT_UNLOCK, FAULT_UNMAP, FAULT_TRUNCATE, FAULT_CLOSE, FAULT_CLOCK,
    FAULT_TYPE, FAULT_UID, FAULT_LINK, FAULT_SIZE, FAULT_CLOEXEC };
int __real_mlock(const void *, size_t);
int __real_madvise(void *, size_t, int);
int __real_munlock(const void *, size_t);
int __real_munmap(void *, size_t);
int __real_ftruncate(int, off_t);
int __real_close(int);
int __real_clock_gettime(clockid_t, struct timespec *);
int __real_fstat(int, struct stat *);
int __real_fcntl(int, int, ...);
static int consume(int wanted) {
    if (fault != wanted) return 0;
    fault = 0; errno = EIO; return 1;
}
int __wrap_fchmod(int fd, mode_t mode) {
    (void)fd; (void)mode; chmod_calls++; errno = EACCES; return -1;
}
int __wrap_fstat(int fd, struct stat *shape) {
    int result = __real_fstat(fd, shape);
    if (result == 0 && fault >= FAULT_TYPE && fault <= FAULT_SIZE) {
        int injected = fault; fault = 0; rejected_fd = fd;
        if (injected == FAULT_TYPE) shape->st_mode = S_IFDIR | (shape->st_mode & 0777);
        if (injected == FAULT_UID) shape->st_uid = getuid() + 1U;
        if (injected == FAULT_LINK) shape->st_nlink = 1;
        if (injected == FAULT_SIZE) shape->st_size++;
    }
    return result;
}
int __wrap_fcntl(int fd, int command, ...) {
    // The production view/reader and this matrix query only this FD property.
    assert(command == F_GETFD);
    int result = __real_fcntl(fd, command);
    if (result >= 0 && fault == FAULT_CLOEXEC) { fault = 0; rejected_fd = fd; result &= ~FD_CLOEXEC; }
    return result;
}
int __wrap_mlock(const void *p, size_t n) { return consume(FAULT_LOCK) ? -1 : __real_mlock(p, n); }
int __wrap_madvise(void *p, size_t n, int advice) {
    return consume(FAULT_ADVISE) ? -1 : __real_madvise(p, n, advice);
}
int __wrap_munlock(const void *p, size_t n) { return consume(FAULT_UNLOCK) ? -1 : __real_munlock(p, n); }
int __wrap_munmap(void *p, size_t n) {
    if (inspect_wipe) {
        const unsigned char *bytes = p;
        for (size_t i = 0; i < n; i++) assert(bytes[i] == 0);
        wipe_checks++;
    }
    return consume(FAULT_UNMAP) ? -1 : __real_munmap(p, n);
}
int __wrap_ftruncate(int fd, off_t n) { return consume(FAULT_TRUNCATE) ? -1 : __real_ftruncate(fd, n); }
int __wrap_close(int fd) {
    int fail = consume(FAULT_CLOSE);
    int result = __real_close(fd);
    if (fail) { errno = EIO; return -1; }
    return result;
}
int __wrap_clock_gettime(clockid_t id, struct timespec *now) {
    return consume(FAULT_CLOCK) ? -1 : __real_clock_gettime(id, now);
}

struct wire { unsigned char data[2 * 1024 * 1024]; size_t length; };
static void put(struct wire *w, const void *p, size_t n) {
    assert(n <= sizeof(w->data) - w->length);
    memcpy(w->data + w->length, p, n); w->length += n;
}
static void integer(struct wire *w, uint32_t n) {
    unsigned char b[] = {(unsigned char)(n >> 24), (unsigned char)(n >> 16),
        (unsigned char)(n >> 8), (unsigned char)n}; put(w, b, 4);
}
static void wide(struct wire *w, uint64_t n) { integer(w, (uint32_t)(n >> 32)); integer(w, (uint32_t)n); }
static void text(struct wire *w, const char *s) { integer(w, (uint32_t)strlen(s)); put(w, s, strlen(s)); }
static void header(struct wire *w, unsigned int count, const char *root) {
    memset(w, 0, sizeof(*w)); integer(w, 0x43535056U); integer(w, 1);
    unsigned char uuid[16] = {1}, binding[32] = {2};
    struct timespec now; assert(clock_gettime(CLOCK_BOOTTIME, &now) == 0);
    put(w, uuid, sizeof(uuid)); wide(w, 73); wide(w, 7); wide(w, (uint64_t)now.tv_sec * 1000 + 999999);
    text(w, "request-1"); text(w, "connection-1"); text(w, root);
    put(w, binding, sizeof(binding)); integer(w, count);
}
static void object(struct wire *w, unsigned int type, const char *id, const char *path,
    unsigned int declared, const char *const *refs, unsigned int ref_count,
    const unsigned char *body, unsigned int length) {
    unsigned char tag = (unsigned char)type; put(w, &tag, 1);
    text(w, id); text(w, path); text(w, "public-synthetic-identity"); integer(w, declared);
    integer(w, ref_count);
    for (unsigned int i = 0; i < ref_count; i++) text(w, refs[i]);
    integer(w, length); put(w, body, length);
}
static void fixture(struct wire *w) {
    header(w, 3, "ssh/config");
    const char *refs[] = {"ssh/key", "ssh/known_hosts"};
    object(w, 1, "root", "ssh/config", 4, refs, 2, (const unsigned char *)"Host", 4);
    object(w, 2, "key", "ssh/key", 32, NULL, 0, (const unsigned char *)"", 0);
    object(w, 5, "trust", "ssh/known_hosts", 7, NULL, 0, (const unsigned char *)"fixture", 7);
}
static struct ctos_ssh_view *load(const struct wire *w, size_t length) {
    struct ctos_ssh_view *v = calloc(1, sizeof(*v)); assert(v != NULL);
    assert(ctos_ssh_view_allocate(v, length) == 0);
    memcpy(v->mapping, w->data, length); return v;
}
static void clear(struct ctos_ssh_view *v) {
    expected_wipes++;
    inspect_wipe = 1; assert(ctos_ssh_view_destroy(v) == 0); inspect_wipe = 0;
    assert(v->mapping == NULL && v->fd == -1 && !v->valid); free(v);
}
static void expect(const struct wire *w, int valid) {
    struct ctos_ssh_view *v = load(w, w->length);
    assert((ctos_ssh_view_validate(v) == 0) == valid);
    clear(v); cases++;
}
static void mutate_byte(struct wire *w, const char *needle, unsigned char value) {
    size_t length = strlen(needle);
    for (size_t i = 0; i + length <= w->length; i++) {
        if (memcmp(w->data + i, needle, length) == 0) { w->data[i] = value; return; }
    }
    assert(0);
}

static void backing_guards(const struct wire *w) {
    for (int injected = FAULT_TYPE; injected <= FAULT_CLOEXEC; injected++) {
        struct ctos_ssh_view *v = calloc(1, sizeof(*v)); assert(v != NULL);
        fault = injected; rejected_fd = -1; errno = 0;
        assert(ctos_ssh_view_allocate(v, w->length) != 0 && errno == EACCES && fault == 0);
        assert(v->fd == -1 && v->mapping == NULL && !v->locked);
        assert(rejected_fd >= 0 && fcntl(rejected_fd, F_GETFD) < 0 && errno == EBADF);
        assert(ctos_ssh_view_destroy(v) == 0); free(v); cases++;

        v = load(w, w->length); assert(ctos_ssh_view_validate(v) == 0);
        struct ctos_ssh_io *io = calloc(1, sizeof(*io));
        assert(io != NULL && ctos_ssh_io_init(io, v, fclose) == 0);
        fault = injected; rejected_fd = -1; errno = 0;
        assert(ctos_ssh_io_open(io, CTOS_SSH_VIRTUAL_HOME "/ssh/config", CTOS_SSH_CONFIG) == NULL
            && errno == EACCES && fault == 0);
        assert(rejected_fd >= 0 && fcntl(rejected_fd, F_GETFD) < 0 && errno == EBADF);
        assert(io->held_bytes == 0 && io->error == EACCES);
        // Rejection cannot copy a CONFIG byte or damage the parent view.
        for (unsigned int i = 0; i < CTOS_SSH_IO_STREAMS; i++)
            assert(io->readers[i].mapping == NULL && !io->readers[i].live);
        assert(ctos_ssh_view_live(v) == 0);
        assert(ctos_ssh_io_destroy(io) != 0); free(io); clear(v); cases++;
    }
}

static void io_checks(struct wire *w) {
    struct ctos_ssh_view *v = load(w, w->length); assert(ctos_ssh_view_validate(v) == 0);
    struct ctos_ssh_io *io = calloc(1, sizeof(*io)); assert(io != NULL);
    assert(ctos_ssh_io_init(io, v, fclose) == 0);
    FILE *a = ctos_ssh_io_open(io, CTOS_SSH_VIRTUAL_HOME "/ssh/config", 1);
    FILE *b = ctos_ssh_io_open(io, CTOS_SSH_VIRTUAL_HOME "/ssh/config", 1);
    assert(a != NULL && b != NULL && a != b && fgetc(a) == 'H' && fgetc(b) == 'H'); cases++;
    struct stat shape;
    assert(fstat(fileno(a), &shape) == 0 && S_ISREG(shape.st_mode) && shape.st_uid == getuid()
        && shape.st_nlink == 0 && shape.st_size == 4 && (fcntl(fileno(a), F_GETFD) & FD_CLOEXEC) != 0); cases++;
    int held = dup(fileno(a)); assert(held >= 0);
    inspect_wipe = 1; expected_wipes++;
    assert(ctos_ssh_io_close(io, a) == 0);
    unsigned char byte; assert(pread(held, &byte, 1, 0) == 0 && close(held) == 0 && fgetc(b) == 'o'); cases++;
    expected_wipes++; assert(ctos_ssh_io_close(io, b) == 0); inspect_wipe = 0;
    assert(ctos_ssh_io_close(io, NULL) == EOF && errno == EACCES); cases++;
    assert(ctos_ssh_io_stat(io, CTOS_SSH_VIRTUAL_HOME "/ssh/", &shape) == 0 && S_ISDIR(shape.st_mode)); cases++;
    assert(ctos_ssh_io_stat(io, CTOS_SSH_VIRTUAL_HOME "/ssh/key", &shape) == 0 && S_ISREG(shape.st_mode) && shape.st_size == 32); cases++;
    char child[256], relative[513];
    assert(ctos_ssh_io_child(io, CTOS_SSH_VIRTUAL_HOME, 0, child) == 1 && strcmp(child, "ssh") == 0
        && ctos_ssh_io_child(io, CTOS_SSH_VIRTUAL_HOME, 1, child) == 0); cases++;
    assert(ctos_ssh_io_child(io, CTOS_SSH_VIRTUAL_HOME "/ssh", 0, child) == 1 && strcmp(child, "known_hosts") == 0
        && ctos_ssh_io_child(io, CTOS_SSH_VIRTUAL_HOME "/ssh", 1, child) == 1 && strcmp(child, "key") == 0
        && ctos_ssh_io_child(io, CTOS_SSH_VIRTUAL_HOME "/ssh", 2, child) == 1 && strcmp(child, "config") == 0
        && ctos_ssh_io_child(io, CTOS_SSH_VIRTUAL_HOME "/ssh", 3, child) == 0); cases++;
    assert(ctos_ssh_io_resolve("~/ssh/*.conf", 1, relative) == 0 && strcmp(relative, "ssh/*.conf") == 0
        && ctos_ssh_io_resolve("~other/ssh/*.conf", 1, relative) != 0
        && ctos_ssh_io_resolve(CTOS_SSH_VIRTUAL_HOME "/../escape", 1, relative) != 0); cases++;
    assert(ctos_ssh_io_destroy(io) == 0 && io->held_bytes == 0); free(io); clear(v);
    const char *denied[] = {CTOS_SSH_VIRTUAL_HOME "/ssh/key", CTOS_SSH_VIRTUAL_HOME "/ssh/missing", "/etc/passwd", "/proc/self/fd/198"};
    for (unsigned int i = 0; i < 4; i++) {
        v = load(w, w->length); assert(ctos_ssh_view_validate(v) == 0);
        io = calloc(1, sizeof(*io)); assert(io != NULL && ctos_ssh_io_init(io, v, fclose) == 0);
        assert(ctos_ssh_io_open(io, denied[i], i == 0 ? 2 : 1) == NULL && io->held_bytes == 0 && io->error != 0);
        assert(ctos_ssh_io_destroy(io) != 0); free(io); clear(v); cases++;
    }
    v = load(w, w->length); assert(ctos_ssh_view_validate(v) == 0);
    io = calloc(1, sizeof(*io)); assert(io != NULL && ctos_ssh_io_init(io, v, fclose) == 0);
    inspect_wipe = 1;
    for (unsigned int i = 0; i < CTOS_SSH_IO_STREAMS; i++) assert(ctos_ssh_io_open(io, CTOS_SSH_VIRTUAL_HOME "/ssh/config", 1) != NULL);
    assert(ctos_ssh_io_open(io, CTOS_SSH_VIRTUAL_HOME "/ssh/config", 1) == NULL && errno == EMFILE);
    expected_wipes += CTOS_SSH_IO_STREAMS;
    assert(ctos_ssh_io_destroy(io) != 0 && io->held_bytes == 0); inspect_wipe = 0; free(io); clear(v); cases++;

    v = load(w, w->length); assert(ctos_ssh_view_validate(v) == 0);
    io = calloc(1, sizeof(*io)); assert(io != NULL && ctos_ssh_io_init(io, v, fclose) == 0);
    inspect_wipe = 1;
    for (unsigned int i = 0; i < CTOS_SSH_IO_OPENS; i++) {
        a = ctos_ssh_io_open(io, CTOS_SSH_VIRTUAL_HOME "/ssh/config", 1); assert(a != NULL);
        expected_wipes++; assert(ctos_ssh_io_close(io, a) == 0);
    }
    assert(ctos_ssh_io_open(io, CTOS_SSH_VIRTUAL_HOME "/ssh/config", 1) == NULL && errno == E2BIG);
    assert(ctos_ssh_io_destroy(io) != 0 && io->held_bytes == 0); inspect_wipe = 0; free(io); clear(v); cases++;

    for (int injected = FAULT_UNLOCK; injected <= FAULT_UNMAP; injected++) {
        v = load(w, w->length); assert(ctos_ssh_view_validate(v) == 0);
        io = calloc(1, sizeof(*io)); assert(io != NULL && ctos_ssh_io_init(io, v, fclose) == 0);
        a = ctos_ssh_io_open(io, CTOS_SSH_VIRTUAL_HOME "/ssh/config", 1); assert(a != NULL);
        fault = injected; inspect_wipe = 1; expected_wipes++;
        assert(ctos_ssh_io_close(io, a) == EOF && io->error != 0);
        if (injected == FAULT_UNMAP) { assert(ctos_ssh_io_owns(io, a)); expected_wipes++; }
        assert(ctos_ssh_io_destroy(io) != 0 && io->held_bytes == 0);
        inspect_wipe = 0; free(io); clear(v); cases++;
    }
    struct wire *large = calloc(1, sizeof(*large));
    unsigned char *payload = malloc(CTOS_SSH_VIEW_FILE_BYTES); assert(large != NULL && payload != NULL);
    memset(payload, 'P', CTOS_SSH_VIEW_FILE_BYTES);
    header(large, 1, "ssh/config");
    object(large, 1, "root", "ssh/config", CTOS_SSH_VIEW_FILE_BYTES, NULL, 0, payload, CTOS_SSH_VIEW_FILE_BYTES);
    v = load(large, large->length); assert(ctos_ssh_view_validate(v) == 0);
    io = calloc(1, sizeof(*io)); assert(io != NULL && ctos_ssh_io_init(io, v, fclose) == 0);
    inspect_wipe = 1;
    for (unsigned int i = 0; i < 64; i++) {
        a = ctos_ssh_io_open(io, CTOS_SSH_VIRTUAL_HOME "/ssh/config", 1);
        assert(a != NULL && fgetc(a) == 'P');
        expected_wipes++; assert(ctos_ssh_io_close(io, a) == 0);
    }
    assert(io->read_bytes == CTOS_SSH_IO_READ_BYTES && io->held_bytes == 0);
    assert(ctos_ssh_io_open(io, CTOS_SSH_VIRTUAL_HOME "/ssh/config", 1) == NULL && errno == E2BIG);
    assert(ctos_ssh_io_destroy(io) != 0 && io->held_bytes == 0);
    inspect_wipe = 0; free(io); clear(v); free(payload); free(large); cases++;
}
int main(void) {
    struct wire w; fixture(&w);
    backing_guards(&w);
    struct ctos_ssh_view *v = load(&w, w.length);
    assert(ctos_ssh_view_validate(v) == 0 && v->count == 3 && v->private_count == 1
        && v->public_bytes == 11 && v->generation == 73 && v->epoch == 7);
    const struct ctos_ssh_file *f = ctos_ssh_view_lookup(v, (const unsigned char *)"ssh/config", 10, 1);
    assert(f != NULL && f->body.length == 4 && memcmp(f->body.data, "Host", 4) == 0);
    assert(ctos_ssh_view_lookup(v, (const unsigned char *)"ssh/key", 7, 2) == NULL && errno == EACCES);
    assert(ctos_ssh_view_lookup(v, (const unsigned char *)"ssh/config", 10, 5) == NULL && errno == EACCES);
    assert(ctos_ssh_view_lookup(v, (const unsigned char *)"/etc/passwd", 11, 1) == NULL && errno == EACCES);
    assert(ctos_ssh_view_lookup(v, (const unsigned char *)"ssh/missing", 11, 1) == NULL && errno == ENOENT);
    int held = dup(v->fd); assert(held >= 0);
    clear(v);
    struct stat shape; unsigned char b;
    assert(fstat(held, &shape) == 0 && shape.st_size == 0 && pread(held, &b, 1, 0) == 0);
    assert(close(held) == 0); cases += 6;

    /* Every truncated prefix must fail, including fields and bodies. */
    for (size_t n = 1; n < w.length; n++) {
        v = load(&w, n); assert(ctos_ssh_view_validate(v) != 0); clear(v); cases++;
    }
    put(&w, "x", 1); expect(&w, 0);
    fixture(&w); w.data[0] = 0; expect(&w, 0);
    fixture(&w); w.data[7] = 2; expect(&w, 0);
    fixture(&w); w.data[24] = 0x80; expect(&w, 0);
    fixture(&w); memset(w.data + 40, 0, 8); w.data[47] = 1; expect(&w, 0);
    fixture(&w); mutate_byte(&w, "connection-1", 0); expect(&w, 0);
    fixture(&w); mutate_byte(&w, "connection-1", 0xc0); expect(&w, 0);
    fixture(&w); mutate_byte(&w, "ssh/config", '/'); expect(&w, 0);
    fixture(&w); mutate_byte(&w, "ssh/config", '~'); expect(&w, 0);
    fixture(&w); mutate_byte(&w, "ssh/config", '%'); expect(&w, 0);
    fixture(&w); mutate_byte(&w, "ssh/config", '*'); expect(&w, 0);
    fixture(&w); mutate_byte(&w, "ssh/key", '/'); expect(&w, 0);
    fixture(&w); mutate_byte(&w, "public-synthetic-identity", '\n'); expect(&w, 0);

    const char *self[] = {"ssh/config"};
    header(&w, 1, "ssh/config"); object(&w, 1, "root", "ssh/config", 0, self, 1, (const unsigned char *)"", 0); expect(&w, 0);
    header(&w, 1, "ssh/config"); object(&w, 1, "root", "ssh/config", 0, NULL, 0, (const unsigned char *)"", 0); expect(&w, 1);
    header(&w, 1, "ssh/config"); object(&w, 2, "root", "ssh/config", 1, NULL, 0, (const unsigned char *)"x", 1); expect(&w, 0);
    header(&w, 1, "ssh/config"); object(&w, 1, "root", "ssh/config", 1, NULL, 0, (const unsigned char *)"", 0); expect(&w, 0);
    header(&w, 1, "ssh/config"); object(&w, 0, "root", "ssh/config", 0, NULL, 0, (const unsigned char *)"", 0); expect(&w, 0);
    header(&w, 1, "ssh/config"); object(&w, 6, "root", "ssh/config", 0, NULL, 0, (const unsigned char *)"", 0); expect(&w, 0);
    header(&w, 1, "ssh/config"); object(&w, 1, "root", "ssh/config", CTOS_SSH_VIEW_FILE_BYTES + 1, NULL, 0, (const unsigned char *)"", 0); expect(&w, 0);
    header(&w, 2, "ssh/config"); object(&w, 1, "root", "ssh/config", 0, NULL, 0, (const unsigned char *)"", 0);
    object(&w, 5, "other", "ssh/other", 0, NULL, 0, (const unsigned char *)"", 0); expect(&w, 0);
    const char *child[] = {"ssh/other"};
    header(&w, 2, "ssh/config"); object(&w, 1, "root", "ssh/config", 0, child, 1, (const unsigned char *)"", 0);
    object(&w, 5, "root", "ssh/other", 0, NULL, 0, (const unsigned char *)"", 0); expect(&w, 0);
    header(&w, 2, "ssh/config"); object(&w, 1, "root", "ssh/config", 0, child, 1, (const unsigned char *)"", 0);
    object(&w, 5, "other", "ssh/config", 0, NULL, 0, (const unsigned char *)"", 0); expect(&w, 0);
    header(&w, 2, "ssh/config"); object(&w, 1, "root", "ssh/config", 0, child, 1, (const unsigned char *)"", 0);
    object(&w, 5, "other", "ssh/other", 0, self, 1, (const unsigned char *)"", 0); expect(&w, 0);
    const char *repeat[] = {"ssh/other", "ssh/other"};
    header(&w, 2, "ssh/config"); object(&w, 1, "root", "ssh/config", 0, repeat, 2, (const unsigned char *)"", 0);
    object(&w, 5, "other", "ssh/other", 0, NULL, 0, (const unsigned char *)"", 0); expect(&w, 0);
    for (unsigned int amount = 8; amount <= 9; amount++) {
        char names[9][24], ids[9][24]; const char *refs[9];
        for (unsigned int i = 0; i < amount; i++) {
            assert(snprintf(names[i], sizeof(names[i]), "ssh/key%u", i) > 0);
            assert(snprintf(ids[i], sizeof(ids[i]), "key%u", i) > 0); refs[i] = names[i];
        }
        header(&w, amount + 1, "ssh/config"); object(&w, 1, "root", "ssh/config", 0, refs, amount, (const unsigned char *)"", 0);
        for (unsigned int i = 0; i < amount; i++)
            object(&w, 2, ids[i], names[i], CTOS_SSH_VIEW_FILE_BYTES, NULL, 0, (const unsigned char *)"", 0);
        expect(&w, amount == 8);
    }
    for (unsigned int count = 128; count <= 129; count++) {
        char names[128][24], ids[128][24]; const char *refs[128];
        for (unsigned int i = 0; i < count - 1; i++) {
            assert(snprintf(names[i], sizeof(names[i]), "ssh/key%u", i) > 0);
            assert(snprintf(ids[i], sizeof(ids[i]), "key%u", i) > 0); refs[i] = names[i];
        }
        header(&w, count, "ssh/config"); object(&w, 1, "root", "ssh/config", 0, refs, count - 1, (const unsigned char *)"", 0);
        for (unsigned int i = 0; i < count - 1; i++)
            object(&w, 2, ids[i], names[i], 0, NULL, 0, (const unsigned char *)"", 0);
        expect(&w, count == 128);
    }
    for (unsigned int depth = 8; depth <= 9; depth++) {
        header(&w, depth + 1, "ssh/n0");
        for (unsigned int i = 0; i <= depth; i++) {
            char id[24], current[24], next[24];
            assert(snprintf(id, sizeof(id), "id%u", i) > 0);
            assert(snprintf(current, sizeof(current), "ssh/n%u", i) > 0);
            assert(snprintf(next, sizeof(next), "ssh/n%u", i + 1) > 0);
            const char *refs[] = {next};
            object(&w, 1, id, current, 0, refs, i < depth ? 1 : 0, (const unsigned char *)"", 0);
        }
        expect(&w, depth == 8);
    }
    const char *nested[] = {"ssh/config/child"};
    header(&w, 2, "ssh/config"); object(&w, 1, "root", "ssh/config", 0, nested, 1, (const unsigned char *)"", 0);
    object(&w, 1, "child", "ssh/config/child", 0, NULL, 0, (const unsigned char *)"", 0); expect(&w, 0);
    fixture(&w);
    io_checks(&w);
    v = load(&w, w.length); fault = FAULT_CLOCK;
    assert(ctos_ssh_view_validate(v) != 0 && fault == 0); clear(v); cases++;
    v = load(&w, w.length); assert(ctos_ssh_view_validate(v) == 0);
    fault = FAULT_CLOCK;
    assert(ctos_ssh_view_lookup(v, (const unsigned char *)"ssh/config", 10, 1) == NULL && fault == 0);
    v->deadline = 1;
    assert(ctos_ssh_view_lookup(v, (const unsigned char *)"ssh/config", 10, 1) == NULL && errno == ETIMEDOUT);
    clear(v); cases += 2;
    for (int injected = FAULT_LOCK; injected <= FAULT_ADVISE; injected++) {
        v = calloc(1, sizeof(*v)); assert(v != NULL);
        fault = injected; inspect_wipe = 1;
        expected_wipes++;
        assert(ctos_ssh_view_allocate(v, w.length) != 0 && fault == 0);
        assert(v->fd == -1 && v->mapping == NULL);
        assert(ctos_ssh_view_destroy(v) == 0); inspect_wipe = 0; free(v); cases++;
    }
    for (int injected = FAULT_UNLOCK; injected <= FAULT_CLOSE; injected++) {
        v = load(&w, w.length); assert(ctos_ssh_view_validate(v) == 0);
        int backing = v->fd;
        fault = injected; inspect_wipe = 1;
        expected_wipes++;
        assert(ctos_ssh_view_destroy(v) != 0 && fault == 0 && v->cleanup_failed);
        if (injected == FAULT_UNMAP) {
            assert(v->mapping != NULL && v->fd == backing);
            assert(fstat(backing, &shape) == 0 && shape.st_size == (off_t)w.length);
            expected_wipes++;
            assert(ctos_ssh_view_destroy(v) != 0 && v->mapping == NULL && v->fd == -1);
        } else assert(v->mapping == NULL && v->fd == -1);
        /* Cleanup uncertainty stays recorded even when retry frees resources. */
        assert(ctos_ssh_view_destroy(v) != 0 && v->cleanup_failed);
        inspect_wipe = 0; free(v); cases++;
    }
    assert(wipe_checks == expected_wipes && chmod_calls == 0);
    printf("SSH_VIEW_HOST cases=%u wipe_checks=%u state=pass\n", cases, wipe_checks);
    return 0;
}

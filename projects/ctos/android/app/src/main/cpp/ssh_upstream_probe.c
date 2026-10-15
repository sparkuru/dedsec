/* Trusted synthetic OpenSSH research only. This is not a production VFS,
 * credential broker or configuration sandbox. Owned file pages are locked;
 * arbitrary upstream heap/stdio copies and cross-process wiping are not claimed.
 * Compile separately as driver, VFS client hooks or baseline closefrom observer. */
#define _GNU_SOURCE
#include "includes.h"
#include <arpa/inet.h>
#include <dirent.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <pwd.h>
#include <signal.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include "openbsd-compat/glob.h"

_Static_assert(ATOMIC_INT_LOCK_FREE == 2, "shared event counters must be lock-free");

enum { BOOT_FD = 198, OBJECT_LIMIT = 16, BYTE_LIMIT = 16384, EVENT_LIMIT = 64,
       MAGIC = 0x43534732, GENERATION = 73, EV_CONFIG = 1, EV_CLEAN, EV_BEFORE, EV_AFTER };
#define ROOT "/ctos-g2/home"
#define SSH_ROOT ROOT "/.ssh"
#define CONFIG ROOT "/root.conf"
#define IDENTITY SSH_ROOT "/keys/synthetic_ed25519"
#define HOSTS SSH_ROOT "/known_hosts"
struct record { char path[256]; uint32_t size; unsigned char bytes[BYTE_LIMIT]; };
struct event { int32_t pid, group; uint32_t generation, kind; int32_t error; };
struct snapshot {
    uint32_t magic, generation, owner, count, revoked;
    _Atomic uint32_t events_used;
    struct event events[EVENT_LIMIT];
    struct record records[OBJECT_LIMIT];
};
static struct snapshot *view;
static int view_locked, failure;
#if defined(SSH_UPSTREAM_DRIVER)
static volatile sig_atomic_t stopping;
#endif
#if !defined(SSH_UPSTREAM_BASELINE)
static void wipe(void *memory, size_t length) {
    volatile unsigned char *bytes = memory;
    while (length-- != 0) *bytes++ = 0;
}
static int is_zero(const void *memory, size_t length) {
    const volatile unsigned char *bytes = memory;
    while (length-- != 0) if (*bytes++ != 0) return 0;
    return 1;
}
#endif
static void event(uint32_t kind, int error) {
    if (view == NULL) return;
    uint32_t index = atomic_fetch_add_explicit(&view->events_used, 1, memory_order_relaxed);
    if (index >= EVENT_LIMIT) { failure = EOVERFLOW; return; }
    view->events[index] = (struct event){(int32_t)getpid(), (int32_t)getpgrp(), GENERATION, kind, error};
    atomic_thread_fence(memory_order_release);
}
static int map_boot(void) {
    if (view != NULL) return 0;
    struct stat info;
    if (fstat(BOOT_FD, &info) != 0) return errno;
    if (!S_ISREG(info.st_mode) || info.st_uid != getuid() || (info.st_mode & 0777) != 0600 || info.st_size != (off_t)sizeof(*view)) return EINVAL;
    void *memory = mmap(NULL, sizeof(*view), PROT_READ | PROT_WRITE, MAP_SHARED, BOOT_FD, 0);
    if (memory == MAP_FAILED) return errno;
    if (mlock(memory, sizeof(*view)) != 0) { int error = errno; munmap(memory, sizeof(*view)); return error; }
    view = memory; view_locked = 1;
    if (view->magic != MAGIC || view->generation != GENERATION || view->owner <= 1 || view->count > OBJECT_LIMIT || view->revoked || atomic_load(&view->events_used) >= EVENT_LIMIT) return EINVAL;
    return 0;
}

#if defined(SSH_UPSTREAM_BASELINE)
extern void __real_closefrom(int);
void __wrap_closefrom(int first) {
    int error = map_boot();
    if (error != 0) { failure = error; __real_closefrom(first); return; }
    event(EV_BEFORE, fcntl(BOOT_FD, F_GETFD) >= 0 ? 0 : EIO);
    __real_closefrom(first);
    errno = 0;
    int closed = fcntl(BOOT_FD, F_GETFD) == -1 && errno == EBADF;
    if (view_locked && munlock(view, sizeof(*view)) != 0) failure = errno;
    event(EV_AFTER, !closed ? EIO : failure);
    if (munmap(view, sizeof(*view)) != 0) { failure = errno; event(EV_AFTER, failure); }
    view = NULL; view_locked = 0;
}
#else

extern int __real_open(const char *, int, ...);
extern FILE *__real_fopen(const char *, const char *);
extern FILE *__real_fdopen(int, const char *);
extern int __real_close(int);
extern int __real_stat(const char *, struct stat *);
extern int __real_access(const char *, int);
extern int __real_mkdir(const char *, mode_t);
extern int __real_mkstemp(char *);
extern int __real_link(const char *, const char *);
extern int __real_rename(const char *, const char *);
extern int __real_unlink(const char *);
extern struct passwd *__real_getpwuid(uid_t);
extern int __real__ssh__compat_glob(const char *, int, int (*)(const char *, int), glob_t *);
extern void __real__ssh__compat_globfree(glob_t *);
extern void __real_closefrom(int);
struct object {
    char path[256]; int fd, live, public_proposal; unsigned char *memory;
    size_t mapped, logical; int locked;
};
struct open_fd { int fd; struct object *object; };
struct stream { struct object *object; size_t offset; int write_allowed, append, fd; };
static struct object objects[OBJECT_LIMIT];
static struct open_fd descriptors[32];
static int initialized;
static pid_t object_owner;

static int mask_cleanup_signals(sigset_t *previous) {
    sigset_t set; sigemptyset(&set); sigaddset(&set, SIGTERM); sigaddset(&set, SIGHUP); sigaddset(&set, SIGINT);
    if (sigprocmask(SIG_BLOCK, &set, previous) != 0) return (failure = errno);
    return 0;
}
static void restore_signals(const sigset_t *previous) { if (sigprocmask(SIG_SETMASK, previous, NULL) != 0) failure = errno; }
static int virtual_path(const char *path) { return strncmp(path, "/ctos-g2", 8) == 0; }
static int canonical(const char *path) {
    size_t size = strnlen(path, 256);
    return size < 256 && strncmp(path, ROOT, strlen(ROOT)) == 0 &&
        (path[strlen(ROOT)] == '/' || path[strlen(ROOT)] == '\0') &&
        strstr(path, "/../") == NULL && strstr(path, "/./") == NULL && strstr(path, "//") == NULL &&
        (size < 3 || strcmp(path + size - 3, "/..") != 0) && (size < 2 || strcmp(path + size - 2, "/.") != 0);
}
static int raw_memfd(const char *label, size_t size) {
#ifdef SYS_memfd_create
    int fd = (int)syscall(SYS_memfd_create, label, 1U);
    if (fd < 0) return -1;
    if (fchmod(fd, 0600) != 0 || ftruncate(fd, (off_t)size) != 0) { int error = errno; __real_close(fd); errno = error; return -1; }
    return fd;
#else
    (void)label; (void)size; errno = ENOSYS; return -1;
#endif
}
static int dispose_object(struct object *object, int revoke_fd) {
    int error = 0;
    if (!object->live) return 0;
    if (object->memory != MAP_FAILED) {
        wipe(object->memory, object->mapped);
        if (!is_zero(object->memory, object->mapped)) error = EIO;
        if (object->locked && munlock(object->memory, object->mapped) != 0 && error == 0) error = errno;
        if (munmap(object->memory, object->mapped) != 0 && error == 0) error = errno;
        object->memory = MAP_FAILED; object->locked = 0;
    }
    if (object->fd >= 0) {
        // The mapping has been removed before truncation; no SIGBUS access.
        if (revoke_fd && ftruncate(object->fd, 0) != 0 && error == 0) error = errno;
        if (__real_close(object->fd) != 0 && error == 0) error = errno;
        object->fd = -1;
    }
    object->live = 0;
    return error;
}
static int cleanup_objects(int revoke_fd) {
    int error = 0;
    if (object_owner != getpid()) return 0; /* A pre-exec fork must not erase its parent's shared pages. */
    sigset_t previous; int mask_error = mask_cleanup_signals(&previous); if (mask_error != 0) return mask_error;
    for (size_t i = 0; i < OBJECT_LIMIT; ++i) {
        int result = dispose_object(&objects[i], revoke_fd);
        if (result != 0 && error == 0) error = result;
    }
    restore_signals(&previous);
    return error;
}
static struct object *new_object(const char *path, const unsigned char *bytes, size_t length, int public_proposal) {
    if (!canonical(path) || length > BYTE_LIMIT || (!public_proposal && length == 0)) { errno = EINVAL; return NULL; }
    struct object *object = NULL;
    for (size_t i = 0; i < OBJECT_LIMIT; ++i) if (!objects[i].live) { object = &objects[i]; break; }
    if (object == NULL) { errno = ENOSPC; return NULL; }
    sigset_t previous; if (mask_cleanup_signals(&previous) != 0) { errno = failure; return NULL; }
    memset(object, 0, sizeof(*object)); object->fd = -1; object->memory = MAP_FAILED; object->live = 1;
    object->mapped = public_proposal ? BYTE_LIMIT : length;
    object->public_proposal = public_proposal; object->logical = length;
    object->fd = raw_memfd("ctos-g2-object", object->mapped);
    int error = object->fd < 0 ? errno : 0;
    if (error == 0) {
        object->memory = mmap(NULL, object->mapped, PROT_READ | PROT_WRITE, MAP_SHARED, object->fd, 0);
        if (object->memory == MAP_FAILED) error = errno;
    }
    if (error == 0) {
        if (mlock(object->memory, object->mapped) != 0) error = errno;
        else object->locked = 1;
    }
    if (error == 0) { if (length != 0) memcpy(object->memory, bytes, length); strcpy(object->path, path); }
    if (error != 0) { (void)dispose_object(object, 1); errno = error; object = NULL; }
    restore_signals(&previous);
    return object;
}
static int initialize(void) {
    if (initialized) return failure;
    initialized = 1; object_owner = getpid();
    for (size_t i = 0; i < 32; ++i) descriptors[i].fd = -1;
    int error = map_boot();
    if (error != 0) return (failure = error);
    for (size_t i = 0; i < view->count; ++i) {
        struct record *record = &view->records[i];
        if (strnlen(record->path, sizeof(record->path)) == sizeof(record->path) || record->size > BYTE_LIMIT || !canonical(record->path)) return (failure = EINVAL);
        for (size_t j = 0; j < i; ++j) if (strcmp(record->path, view->records[j].path) == 0) return (failure = EINVAL);
        if (new_object(record->path, record->bytes, record->size, strcmp(record->path, HOSTS) == 0) == NULL) return (failure = errno);
    }
    return 0;
}
static struct object *lookup(const char *path) {
    int error = initialize();
    if (error != 0) { errno = error; return NULL; }
    if (view->revoked || view->generation != GENERATION) { errno = EACCES; return NULL; }
    if (!canonical(path)) { errno = EINVAL; return NULL; }
    for (size_t i = 0; i < OBJECT_LIMIT; ++i) if (objects[i].live && strcmp(objects[i].path, path) == 0) return &objects[i];
    errno = ENOENT; return NULL;
}
static int track_fd(int fd, struct object *object) {
    for (size_t i = 0; i < 32; ++i) if (descriptors[i].fd < 0) { descriptors[i] = (struct open_fd){fd, object}; return fd; }
    __real_close(fd); errno = EMFILE; return -1;
}
int __wrap_close(int fd) {
    for (size_t i = 0; i < 32; ++i) if (descriptors[i].fd == fd && initialized) descriptors[i].fd = -1;
    return __real_close(fd);
}
static int reopen_object(struct object *object) {
    char path[64]; int size = snprintf(path, sizeof(path), "/proc/self/fd/%d", object->fd);
    if (size < 0 || (size_t)size >= sizeof(path)) { errno = EOVERFLOW; return -1; }
    int fd = __real_open(path, O_RDONLY | O_CLOEXEC);
    return fd < 0 ? -1 : track_fd(fd, object);
}
int __wrap_open(const char *path, int flags, ...) {
    mode_t mode = 0600;
    if ((flags & O_CREAT) != 0) { va_list args; va_start(args, flags); mode = (mode_t)va_arg(args, int); va_end(args); }
    if (!virtual_path(path)) return __real_open(path, flags, mode);
    struct object *object = lookup(path);
    if (object == NULL) return -1;
    if ((flags & O_ACCMODE) != O_RDONLY || (flags & (O_TRUNC | O_CREAT)) != 0 || object->public_proposal) { errno = EACCES; return -1; }
    int fd = reopen_object(object);
    if (fd >= 0 && strcmp(path, CONFIG) == 0) event(EV_CONFIG, 0);
    return fd;
}
int __wrap___open_2(const char *path, int flags) { return __wrap_open(path, flags); }
int __wrap_open64(const char *path, int flags, ...) {
    mode_t mode = 0600;
    if ((flags & O_CREAT) != 0) { va_list args; va_start(args, flags); mode = (mode_t)va_arg(args, int); va_end(args); }
    return __wrap_open(path, flags, mode);
}
static int stream_read(void *opaque, char *bytes, int count) {
    struct stream *stream = opaque; struct object *object = stream->object;
    if (view->revoked || !object->live) { errno = EACCES; return -1; }
    if (count < 0) { errno = EINVAL; return -1; }
    size_t available = stream->offset < object->logical ? object->logical - stream->offset : 0;
    size_t length = (size_t)count < available ? (size_t)count : available;
    memcpy(bytes, object->memory + stream->offset, length); stream->offset += length;
    return (int)length;
}
static int stream_write(void *opaque, const char *bytes, int count) {
    struct stream *stream = opaque; struct object *object = stream->object;
    if (view->revoked || !object->live || !stream->write_allowed) { errno = EACCES; return -1; }
    if (count < 0) { errno = EINVAL; return -1; }
    if (stream->append) stream->offset = object->logical;
    if ((size_t)count > BYTE_LIMIT - stream->offset) { errno = EFBIG; return -1; }
    memcpy(object->memory + stream->offset, bytes, (size_t)count); stream->offset += (size_t)count;
    if (stream->offset > object->logical) object->logical = stream->offset;
    return count;
}
static fpos_t stream_seek(void *opaque, fpos_t offset, int whence) {
    struct stream *stream = opaque;
    if (view->revoked || !stream->object->live) { errno = EACCES; return (fpos_t)-1; }
    int64_t base = whence == SEEK_SET ? 0 : whence == SEEK_CUR ? (int64_t)stream->offset : whence == SEEK_END ? (int64_t)stream->object->logical : -1;
    if (base < 0 || offset < -base || offset > BYTE_LIMIT - base) { errno = EINVAL; return (fpos_t)-1; }
    stream->offset = (size_t)(base + offset); return (fpos_t)stream->offset;
}
static int stream_close(void *opaque) {
    struct stream *stream = opaque;
    int error = stream->fd >= 0 ? __wrap_close(stream->fd) : 0;
    wipe(stream, sizeof(*stream)); free(stream); return error;
}
static FILE *proposal_stream(struct object *object, const char *mode, int fd) {
    if (!object->public_proposal || (strcmp(mode, "r") != 0 && strcmp(mode, "a+") != 0 && strcmp(mode, "w") != 0)) { errno = EINVAL; return NULL; }
    struct stream *stream = calloc(1, sizeof(*stream)); if (stream == NULL) return NULL;
    stream->object = object; stream->write_allowed = mode[0] != 'r'; stream->append = mode[0] == 'a'; stream->fd = fd;
    if (mode[0] == 'w') { wipe(object->memory, object->mapped); object->logical = 0; }
    FILE *file = funopen(stream, stream_read, stream->write_allowed ? stream_write : NULL, stream_seek, stream_close);
    if (file == NULL) free(stream);
    return file;
}
FILE *__wrap_fdopen(int fd, const char *mode) {
    for (size_t i = 0; i < 32; ++i) if (initialized && descriptors[i].fd == fd && descriptors[i].object->public_proposal) return proposal_stream(descriptors[i].object, mode, fd);
    return __real_fdopen(fd, mode);
}
FILE *__wrap_fopen(const char *path, const char *mode) {
    if (!virtual_path(path)) return __real_fopen(path, mode);
    struct object *object = lookup(path); if (object == NULL) return NULL;
    if (object->public_proposal) return proposal_stream(object, mode, -1);
    if (strcmp(mode, "r") != 0) { errno = EACCES; return NULL; }
    int fd = __wrap_open(path, O_RDONLY); if (fd < 0) return NULL;
    FILE *file = __real_fdopen(fd, mode); if (file == NULL) __wrap_close(fd);
    return file;
}
FILE *__wrap_fopen64(const char *path, const char *mode) { return __wrap_fopen(path, mode); }
int __wrap_stat(const char *path, struct stat *result) {
    if (!virtual_path(path)) return __real_stat(path, result);
    int error = initialize(); if (error != 0) { errno = error; return -1; }
    if (view->revoked || !canonical(path)) { errno = view->revoked ? EACCES : EINVAL; return -1; }
    size_t length = strlen(path); while (length > 0 && path[length - 1] == '/') --length;
    for (size_t i = 0; i < OBJECT_LIMIT; ++i) if (objects[i].live && strncmp(objects[i].path, path, length) == 0 && objects[i].path[length] == '/') {
        memset(result, 0, sizeof(*result)); result->st_mode = S_IFDIR | 0700; result->st_uid = getuid(); return 0;
    }
    struct object *object = lookup(path); return object == NULL ? -1 : fstat(object->fd, result);
}
int __wrap_stat64(const char *path, struct stat64 *result) { return __wrap_stat(path, (struct stat *)result); }
int __wrap_access(const char *path, int mode) {
    if (!virtual_path(path)) return __real_access(path, mode);
    return lookup(path) == NULL ? -1 : 0;
}
int __wrap_mkdir(const char *path, mode_t mode) {
    if (!virtual_path(path)) return __real_mkdir(path, mode);
    errno = EACCES; return -1;
}
struct passwd *__wrap_getpwuid(uid_t uid) {
    static struct passwd result;
    struct passwd *original = __real_getpwuid(uid); if (original == NULL) return NULL;
    result = *original; result.pw_dir = ROOT; return &result;
}
struct directory { char names[OBJECT_LIMIT][256]; size_t count, next; struct dirent entry; };
static void *memory_opendir(const char *path) {
    if (initialize() != 0 || view->revoked || !canonical(path)) { errno = EACCES; return NULL; }
    struct directory *directory = calloc(1, sizeof(*directory)); if (directory == NULL) return NULL;
    size_t length = strlen(path); while (length > 0 && path[length - 1] == '/') --length;
    for (size_t i = 0; i < OBJECT_LIMIT; ++i) {
        if (!objects[i].live || strncmp(objects[i].path, path, length) != 0 || objects[i].path[length] != '/') continue;
        const char *name = objects[i].path + length + 1, *slash = strchr(name, '/');
        size_t size = slash == NULL ? strlen(name) : (size_t)(slash - name), j;
        if (size >= 256) { free(directory); errno = ENAMETOOLONG; return NULL; }
        char candidate[256]; memcpy(candidate, name, size); candidate[size] = '\0';
        for (j = 0; j < directory->count; ++j) if (strcmp(directory->names[j], candidate) == 0) break;
        if (j == directory->count) strcpy(directory->names[directory->count++], candidate);
    }
    if (directory->count == 0) { free(directory); errno = ENOENT; return NULL; }
    return directory;
}
static struct dirent *memory_readdir(void *opaque) {
    struct directory *directory = opaque; if (directory->next >= directory->count) return NULL;
    strcpy(directory->entry.d_name, directory->names[directory->next++]); return &directory->entry;
}
static void memory_closedir(void *opaque) { wipe(opaque, sizeof(struct directory)); free(opaque); }
int __wrap__ssh__compat_glob(const char *pattern, int flags, int (*error_fn)(const char *, int), glob_t *result) {
    if (initialize() != 0 || view->revoked || flags != GLOB_TILDE) return GLOB_ABORTED;
    char expanded[512];
    if (strncmp(pattern, "~/", 2) == 0) {
        int length = snprintf(expanded, sizeof(expanded), "%s/%s", ROOT, pattern + 2);
        if (length < 0 || (size_t)length >= sizeof(expanded)) return GLOB_ABORTED;
        pattern = expanded;
    }
    if (!canonical(pattern)) return GLOB_ABORTED;
    result->gl_opendir = memory_opendir; result->gl_readdir = memory_readdir; result->gl_closedir = memory_closedir;
    result->gl_stat = __wrap_stat; result->gl_lstat = __wrap_stat;
    return __real__ssh__compat_glob(pattern, flags | GLOB_ALTDIRFUNC, error_fn, result);
}
void __wrap__ssh__compat_globfree(glob_t *result) { __real__ssh__compat_globfree(result); }
int __wrap_mkstemp(char *path) {
    if (!virtual_path(path)) return __real_mkstemp(path);
    if (initialize() != 0 || view->revoked || strncmp(path, HOSTS ".", strlen(HOSTS) + 1) != 0) { errno = EACCES; return -1; }
    char *suffix = strchr(path, 'X'); if (suffix == NULL || strlen(suffix) != 11 || strspn(suffix, "X") != 11) { errno = EINVAL; return -1; }
    memset(suffix, 'Q', 11);
    if (lookup(path) != NULL || errno != ENOENT) { errno = EEXIST; return -1; }
    struct object *object = new_object(path, NULL, 0, 1); if (object == NULL) return -1;
    int fd = fcntl(object->fd, F_DUPFD_CLOEXEC, 3); return fd < 0 ? -1 : track_fd(fd, object);
}
int __wrap_unlink(const char *path) {
    if (!virtual_path(path)) return __real_unlink(path);
    struct object *object = lookup(path); if (object == NULL) return -1;
    if (!object->public_proposal) { errno = EACCES; return -1; }
    sigset_t previous; if (mask_cleanup_signals(&previous) != 0) { errno = failure; return -1; }
    int error = dispose_object(object, 1); restore_signals(&previous);
    if (error != 0) { failure = error; errno = error; return -1; } return 0;
}
int __wrap_link(const char *source, const char *target) {
    if (!virtual_path(source) && !virtual_path(target)) return __real_link(source, target);
    struct object *object = lookup(source); if (object == NULL) return -1;
    if (!object->public_proposal || strcmp(target, HOSTS ".old") != 0) { errno = EACCES; return -1; }
    if (lookup(target) != NULL || errno != ENOENT) { errno = EEXIST; return -1; }
    // Public proposal backup copy, not a claim of general POSIX hard-link aliasing.
    return new_object(target, object->memory, object->logical, 1) == NULL ? -1 : 0;
}
int __wrap_rename(const char *source, const char *target) {
    if (!virtual_path(source) && !virtual_path(target)) return __real_rename(source, target);
    if (strcmp(target, HOSTS) != 0) { errno = EACCES; return -1; }
    struct object *object = lookup(source); if (object == NULL || !object->public_proposal) return -1;
    if (__wrap_unlink(target) != 0 && errno != ENOENT) return -1;
    strcpy(object->path, target); return 0;
}
void __wrap_closefrom(int first) {
    int error = map_boot(); if (error != 0) { failure = error; __real_closefrom(first); return; }
    if (first <= BOOT_FD) { for (int fd = first; fd < BOOT_FD; ++fd) __real_close(fd); __real_closefrom(BOOT_FD + 1); }
    else __real_closefrom(first);
    if (fcntl(BOOT_FD, F_GETFD) < 0) failure = EIO;
}

#if defined(SSH_UPSTREAM_VFS)
/* The fixed driver handoff owns BOOT_FD at exec. Only a successful close
 * releases ownership; volatile sig_atomic_t is shared with the signal path. */
static volatile sig_atomic_t boot_fd_owned = 1;
static void finish_client(void) {
    if (object_owner != 0 && object_owner != getpid()) return;
    sigset_t previous;
    if (mask_cleanup_signals(&previous) != 0) {
        event(EV_CLEAN, failure);
        syscall(SYS_exit_group, 99);
        for (;;) { }
    }
    int error = cleanup_objects(1); if (failure != 0 && error == 0) error = failure;
    if (view != NULL) {
        if (view_locked && munlock(view, sizeof(*view)) != 0) { if (error == 0) error = errno; }
        else view_locked = 0;
    }
    if (boot_fd_owned) {
        if (__real_close(BOOT_FD) != 0) { if (error == 0) error = errno; }
        else boot_fd_owned = 0;
    }
    /* The boot mapping remains valid after FD close. Publish only after all
     * earlier owned-object/boot cleanup has been attempted. Reap precedes the
     * driver's receipt checks; a failed final unmap adds a failing receipt. */
    event(EV_CLEAN, error);
    if (view != NULL) {
        if (munmap(view, sizeof(*view)) != 0) {
            if (error == 0) error = errno;
            event(EV_CLEAN, error);
        } else view = NULL;
    }
    if (failure == 0) failure = error;
    /* No signal handler can access a mapping between its unmap and NULL store.
     * A restore failure after unmap cannot publish a receipt; the reserved exit
     * code is independently rejected by the driver, including proxy failures. */
    if (sigprocmask(SIG_SETMASK, &previous, NULL) != 0 && error == 0) error = errno;
    if (error != 0) {
        syscall(SYS_exit_group, 99);
        for (;;) { }
    }
}
extern void __real__exit(int) __attribute__((noreturn));
void __wrap__exit(int status) __attribute__((noreturn));
void __wrap__exit(int status) {
    /* OpenSSH cleanup_exit uses _exit, which bypasses ELF destructors. Preserve
     * that real exit after cleaning this adapter's owned resources. The owner
     * guard keeps a pre-exec fork from erasing its parent's mapped objects. */
    finish_client();
    __real__exit(status);
}
static void client_signal(int number) {
    /* Only stable, signal-masked registry fields and owned fixed mappings are
     * touched. No allocation, FILE flushing, JVM callback or payload output. */
    int error = failure;
    if (object_owner == getpid()) for (size_t i = 0; i < OBJECT_LIMIT; ++i) {
        struct object *object = &objects[i]; if (!object->live) continue;
        wipe(object->memory, object->mapped);
        if (!is_zero(object->memory, object->mapped) && error == 0) error = EIO;
        if (object->locked && syscall(SYS_munlock, object->memory, object->mapped) != 0 && error == 0) error = errno;
        if (syscall(SYS_munmap, object->memory, object->mapped) != 0 && error == 0) error = errno;
        if (syscall(SYS_close, object->fd) != 0 && error == 0) error = errno;
        object->live = 0;
    }
    if (view != NULL) {
        if (view_locked && syscall(SYS_munlock, view, sizeof(*view)) != 0) { if (error == 0) error = errno; }
        else view_locked = 0;
    }
    if (boot_fd_owned) {
        if (syscall(SYS_close, BOOT_FD) != 0) { if (error == 0) error = errno; }
        else boot_fd_owned = 0;
    }
    event(EV_CLEAN, error);
    if (view != NULL) {
        if (syscall(SYS_munmap, view, sizeof(*view)) != 0) {
            if (error == 0) error = errno;
            event(EV_CLEAN, error);
        } else view = NULL;
    }
    syscall(SYS_exit_group, error != 0 ? 99 : 128 + number);
    for (;;) { }
}
__attribute__((constructor)) static void prepare_client(void) {
    struct sigaction action = {0}; action.sa_handler = client_signal;
    sigemptyset(&action.sa_mask); sigaddset(&action.sa_mask, SIGTERM); sigaddset(&action.sa_mask, SIGHUP); sigaddset(&action.sa_mask, SIGINT);
    if (sigaction(SIGTERM, &action, NULL) != 0 || sigaction(SIGHUP, &action, NULL) != 0 || sigaction(SIGINT, &action, NULL) != 0) syscall(SYS_exit_group, 91);
    pid_t parent = getppid();
    if (parent <= 1 || prctl(PR_SET_PDEATHSIG, SIGTERM) != 0 || getppid() != parent) client_signal(SIGTERM);
}
__attribute__((destructor)) static void cleanup_client(void) { finish_client(); }
#endif

#if defined(SSH_UPSTREAM_DRIVER)
#include "authfile.h"
#include "digest.h"
#include "hostfile.h"
#include "log.h"
#include "sshbuf.h"
#include "sshkey.h"
#include <openssl/crypto.h>

enum { CAPTURE_LIMIT = 65536 };
static pid_t active_group = -1;
static pid_t active_pid = -1;
static int group_confirmed, active_primary_reaped, active_status, cleanup_signals_sent;
static int64_t now_ms(void) {
    struct timespec now; if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return -1;
    return (int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}
static void driver_signal(int number) { (void)number; stopping = 1; }
static void protocol(const char *phase, int error, uint64_t value, pid_t pid) {
    printf("SSH_G2 phase=%s state=%s errno=%d value=%llu pid=%ld generation=73\n", phase, error == 0 ? "pass" : "error", error, (unsigned long long)value, (long)pid);
    fflush(stdout);
}
static int collect_group(int *status, int *primary_reaped) {
    int error = 0, no_children = 0;
    if (active_pid <= 0) return EINVAL;
    /* Never reap the primary PID anchor until every group signal is complete.
     * Retries after reaping that anchor may only wait for already-killed owned
     * children; they must not signal a potentially reused numeric PGID. */
    if (!cleanup_signals_sent && !active_primary_reaped) {
        pid_t target = group_confirmed ? -active_group : active_pid;
        if (kill(target, SIGTERM) != 0 && errno != ESRCH) error = errno;
        int64_t start = now_ms(); if (start < 0 && error == 0) error = EIO;
        for (unsigned int attempt = 0; attempt < 100; ++attempt) {
            int64_t current = now_ms(); if (current < 0) { if (error == 0) error = EIO; }
            else if (start >= 0 && current - start >= 1000) break;
            struct timespec pause = {0, 10000000}; nanosleep(&pause, NULL);
        }
        if (kill(target, SIGKILL) != 0 && errno != ESRCH && error == 0) error = errno;
        cleanup_signals_sent = 1;
    }
    int64_t start = now_ms(); if (start < 0 && error == 0) error = EIO;
    for (unsigned int attempt = 0; attempt < 200; ++attempt) {
        int child_status;
        pid_t child = waitpid(group_confirmed ? -active_group : active_pid, &child_status, WNOHANG);
        if (child == active_pid) { active_status = child_status; active_primary_reaped = 1; }
        if (child > 0 && WIFEXITED(child_status) && WEXITSTATUS(child_status) == 99 && error == 0) error = EIO;
        if (child < 0 && errno == ECHILD) {
            no_children = 1;
            /* ECHILD for a group never proves that its direct child was reaped. */
            if (!active_primary_reaped) {
                child = waitpid(active_pid, &child_status, WNOHANG);
                if (child == active_pid) { active_status = child_status; active_primary_reaped = 1; }
                else if (child < 0 && errno != EINTR && error == 0) error = errno;
            }
            if (active_primary_reaped) break;
        } else if (child < 0 && errno != EINTR && error == 0) error = errno;
        int64_t current = now_ms(); if (current < 0) { if (error == 0) error = EIO; }
        else if (start >= 0 && current - start >= 2000) break;
        struct timespec pause = {0, 10000000}; nanosleep(&pause, NULL);
    }
    *status = active_status; *primary_reaped = active_primary_reaped;
    if (!no_children || !active_primary_reaped) { if (error == 0) error = ETIMEDOUT; }
    else { active_group = -1; active_pid = -1; group_confirmed = 0; }
    return error;
}
static int listener(uint16_t *port) {
    int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0); if (fd < 0) return -1;
    struct sockaddr_in address = {0}; address.sin_family = AF_INET; address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(fd, (struct sockaddr *)&address, sizeof(address)) != 0 || listen(fd, 2) != 0) { int error = errno; __real_close(fd); errno = error; return -1; }
    socklen_t size = sizeof(address);
    if (getsockname(fd, (struct sockaddr *)&address, &size) != 0 || address.sin_port == 0) { int error = errno == 0 ? EIO : errno; __real_close(fd); errno = error; return -1; }
    *port = ntohs(address.sin_port); return fd;
}
static int reset_events(void) {
    if (active_pid > 0) return EBUSY;
    wipe(view->events, sizeof(view->events)); atomic_store(&view->events_used, 0); return 0;
}
static int launch(const char *executable, int baseline, int jump_listener, int target_listener,
                  int cancel, char *capture, size_t *captured, int *client_exit) {
    if (active_pid > 0 || reset_events() != 0) return EBUSY;
    int pipes[2] = {-1, -1}, start_pipe[2] = {-1, -1}, peer = -1, error = 0, status = 0, primary_reaped = 0, primary_exited = 0;
    if (pipe2(pipes, O_CLOEXEC | O_NONBLOCK) != 0 || pipe2(start_pipe, O_CLOEXEC) != 0) { error = errno; goto done; }
    pid_t owner = getpid(), pid = fork();
    if (pid < 0) { error = errno; goto done; }
    if (pid == 0) {
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != owner || setpgid(0, 0) != 0) syscall(SYS_exit_group, 92);
        __real_close(start_pipe[1]); char start = 0;
        if (read(start_pipe[0], &start, 1) != 1 || start != 1) syscall(SYS_exit_group, 93);
        if (dup2(pipes[1], STDOUT_FILENO) < 0 || dup2(pipes[1], STDERR_FILENO) < 0) syscall(SYS_exit_group, 94);
        int null_fd = __real_open("/dev/null", O_RDONLY | O_CLOEXEC); if (null_fd < 0 || dup2(null_fd, STDIN_FILENO) < 0) syscall(SYS_exit_group, 95);
        for (int fd = 3; fd < BOOT_FD; ++fd) __real_close(fd);
        __real_closefrom(BOOT_FD + 1);
        if (fcntl(BOOT_FD, F_SETFD, 0) != 0) syscall(SYS_exit_group, 96);
        char *const read_args[] = {(char *)executable, "-G", "-F", baseline ? "/proc/self/fd/198" : CONFIG, "probe", NULL};
        char *const proxy_args[] = {(char *)executable, "-v", "-F", CONFIG, "target", NULL};
        char *const environment[] = {"LANG=C", "LC_ALL=C", "SHELL=/system/bin/sh", "PATH=/system/bin", NULL};
        execve(executable, jump_listener >= 0 ? proxy_args : read_args, environment);
        syscall(SYS_exit_group, 97);
    }
    active_pid = pid; active_group = -1; group_confirmed = 0;
    active_primary_reaped = 0; active_status = 0; cleanup_signals_sent = 0;
    int group_result = setpgid(pid, pid);
    int group_error = group_result == 0 ? 0 : errno;
    if (getpgid(pid) != pid) { error = group_error != 0 ? group_error : EIO; goto done; }
    active_group = pid; group_confirmed = 1;
    if (group_error != 0 && group_error != EACCES) { error = group_error; goto done; }
    __real_close(start_pipe[0]); start_pipe[0] = -1; char start = 1;
    if (write(start_pipe[1], &start, 1) != 1) { error = errno == 0 ? EIO : errno; goto done; }
    __real_close(start_pipe[1]); start_pipe[1] = -1; __real_close(pipes[1]); pipes[1] = -1;
    int64_t begin = now_ms(); if (begin < 0) { error = EIO; goto done; }
    int output_eof = 0, identified = jump_listener < 0;
    char greeting[256] = {0}; size_t greeting_used = 0;
    *captured = 0;
    for (unsigned int attempt = 0; attempt < 1200; ++attempt) {
        if (stopping) { error = ECANCELED; break; }
        int64_t current = now_ms(); if (current < 0) { error = EIO; break; }
        if (current - begin >= 12000) { error = ETIMEDOUT; break; }
        struct pollfd fds[4] = {{pipes[0], POLLIN, 0}, {jump_listener, POLLIN, 0}, {target_listener, POLLIN, 0}, {peer, POLLIN, 0}};
        int ready = poll(fds, 4, 10); if (ready < 0 && errno != EINTR) { error = errno; break; }
        if (target_listener >= 0 && (fds[2].revents & POLLIN) != 0) { error = EACCES; break; } /* No direct fallback. */
        if (jump_listener >= 0 && peer < 0 && (fds[1].revents & POLLIN) != 0) {
            if (identified) { error = EPROTO; break; }
            struct sockaddr_in address; socklen_t size = sizeof(address);
            peer = accept4(jump_listener, (struct sockaddr *)&address, &size, SOCK_CLOEXEC | SOCK_NONBLOCK);
            if (peer < 0) { if (errno != EAGAIN && errno != EINTR) { error = errno; break; } }
            else if (address.sin_family != AF_INET || address.sin_addr.s_addr != htonl(INADDR_LOOPBACK)) { error = EACCES; break; }
        }
        if (peer >= 0 && !identified && (fds[3].revents & (POLLIN | POLLHUP)) != 0) {
            ssize_t count = read(peer, greeting + greeting_used, sizeof(greeting) - greeting_used);
            if (count > 0) greeting_used += (size_t)count;
            else if (count == 0 || (errno != EAGAIN && errno != EINTR)) { error = EIO; break; }
            if (greeting_used >= 8) {
                if (memcmp(greeting, "SSH-2.0-", 8) != 0) { error = EPROTO; break; }
                identified = 1;
                if (cancel) { if (kill(-pid, SIGTERM) != 0) { error = errno; break; } }
                else {
                    const char answer[] = "SSH-2.0-ctos-g2-synthetic\r\n";
                    if (send(peer, answer, sizeof(answer) - 1, MSG_NOSIGNAL) != (ssize_t)(sizeof(answer) - 1)) { error = errno == 0 ? EIO : errno; break; }
                }
                __real_close(peer); peer = -1;
            }
        }
        if (!output_eof) {
            size_t available = CAPTURE_LIMIT - *captured - 1;
            if (available == 0) { error = EOVERFLOW; break; }
            ssize_t count = read(pipes[0], capture + *captured, available);
            if (count > 0) *captured += (size_t)count;
            else if (count == 0) output_eof = 1;
            else if (errno != EAGAIN && errno != EINTR) { error = errno; break; }
        }
        if (!primary_exited) {
            siginfo_t info = {0};
            if (waitid(P_PID, (id_t)pid, &info, WEXITED | WNOHANG | WNOWAIT) != 0) {
                if (errno != EINTR) { error = errno; break; }
            } else if (info.si_pid == pid) primary_exited = 1;
        }
        if (primary_exited && output_eof) { if (!identified) error = EIO; break; }
        if (attempt == 1199) error = ETIMEDOUT;
    }
    capture[*captured] = '\0'; wipe(greeting, sizeof(greeting));
done:
    if (peer >= 0) __real_close(peer);
    for (int i = 0; i < 2; ++i) { if (pipes[i] >= 0) __real_close(pipes[i]); if (start_pipe[i] >= 0) __real_close(start_pipe[i]); }
    if (active_pid > 0) { int result = collect_group(&status, &primary_reaped); if (result != 0 && error == 0) error = result; }
    if (target_listener >= 0) { struct pollfd target = {target_listener, POLLIN, 0}; if (poll(&target, 1, 0) > 0 && (target.revents & POLLIN) != 0 && error == 0) error = EACCES; }
    if (primary_reaped) *client_exit = WIFEXITED(status) ? WEXITSTATUS(status) : WIFSIGNALED(status) ? 128 + WTERMSIG(status) : 255;
    if (primary_reaped && WIFEXITED(status) && WEXITSTATUS(status) == 99 && error == 0) error = EIO;
    return error;
}
static int client_evidence(pid_t *primary, pid_t *jump, uint64_t *diagnostic) {
    *primary = -1; *jump = -1; uint32_t count = atomic_load(&view->events_used);
    *diagnostic = 11;
    if (count > EVENT_LIMIT) return EOVERFLOW;
    for (uint32_t i = 0; i < count; ++i) {
        struct event *record = &view->events[i];
        *diagnostic = 12;
        if (record->generation != GENERATION || record->pid <= 1 || record->error != 0) return EPROTO;
        if (record->kind == EV_CONFIG) {
            if (*primary < 0) *primary = record->pid;
            else if (record->pid != *primary) { *diagnostic = 13; if (*jump > 0 && *jump != record->pid) return EPROTO; *jump = record->pid; }
        }
    }
    *diagnostic = 14;
    if (*primary < 0) return EIO;
    *diagnostic = 15;
    for (uint32_t i = 0; i < count; ++i) if (view->events[i].group != *primary) return EPROTO;
    for (int which = 0; which < (*jump > 0 ? 2 : 1); ++which) {
        pid_t pid = which == 0 ? *primary : *jump; int clean = 0;
        for (uint32_t i = 0; i < count; ++i) if (view->events[i].pid == pid && view->events[i].kind == EV_CLEAN && view->events[i].error == 0) clean = 1;
        *diagnostic = which == 0 ? 16 : 17;
        if (!clean) return EIO;
    }
    *diagnostic = 0;
    return 0;
}
static int add_record(const char *path, const void *bytes, size_t length) {
    if (view->count >= OBJECT_LIMIT || length > BYTE_LIMIT || !canonical(path)) return EINVAL;
    struct record *record = &view->records[view->count++]; strcpy(record->path, path); record->size = (uint32_t)length;
    if (length != 0) memcpy(record->bytes, bytes, length); return 0;
}
static int executable_pair(const char *self, const char *client, const char *baseline) {
    if (self[0] != '/' || client[0] != '/' || baseline[0] != '/') return EINVAL;
    const char *slash = strrchr(self, '/'); size_t prefix = (size_t)(slash - self + 1);
    if (strlen(client) < prefix || strlen(baseline) < prefix) return EINVAL;
    if (strncmp(self, client, prefix) != 0 || strncmp(self, baseline, prefix) != 0 ||
        strcmp(client + prefix, "libctos_ssh_upstream_client.so") != 0 || strcmp(baseline + prefix, "libctos_ssh_upstream_baseline.so") != 0) return EINVAL;
    struct stat info;
    if (__real_stat(client, &info) != 0 || !S_ISREG(info.st_mode) || __real_access(client, X_OK) != 0 || __real_stat(baseline, &info) != 0 || !S_ISREG(info.st_mode) || __real_access(baseline, X_OK) != 0) return EACCES;
    return 0;
}
int main(int argc, char **argv) {
    const char *phase = "gate"; int error = 0, jump_listener = -1, target_listener = -1, held = -1, boot_fd = -1;
    uint64_t error_stage = 0;
    char *capture = MAP_FAILED; int capture_locked = 0;
    struct sshkey *key = NULL, *loaded = NULL, *replacement = NULL;
    struct sshbuf *blob = NULL; struct hostkeys *hostkeys = NULL;
    if (argc != 3 || executable_pair(argv[0], argv[1], argv[2]) != 0) { protocol("gate", EINVAL, 0, getpid()); return 1; }
    pid_t parent = getppid();
    if (parent <= 1 || prctl(PR_SET_PDEATHSIG, SIGTERM) != 0 || getppid() != parent || prctl(PR_SET_CHILD_SUBREAPER, 1) != 0) { protocol("gate", ECHILD, 0, getpid()); return 1; }
    struct sigaction action = {0}; action.sa_handler = driver_signal; sigemptyset(&action.sa_mask);
    if (sigaction(SIGTERM, &action, NULL) != 0 || sigaction(SIGINT, &action, NULL) != 0 || signal(SIGPIPE, SIG_IGN) == SIG_ERR) { protocol("gate", EIO, 0, getpid()); return 1; }
    log_init("ctos-g2", SYSLOG_LEVEL_QUIET, SYSLOG_FACILITY_USER, 1);
#define CHECK(condition) do { if (!(condition) || stopping) { error = stopping ? ECANCELED : error != 0 ? error : errno > 0 && errno <= 4095 ? errno : EIO; goto done; } } while (0)
    phase = "capacity";
    capture = mmap(NULL, CAPTURE_LIMIT, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0); CHECK(capture != MAP_FAILED);
    CHECK(mlock(capture, CAPTURE_LIMIT) == 0); capture_locked = 1;
    boot_fd = raw_memfd("ctos-g2-bootstrap", sizeof(struct snapshot)); CHECK(boot_fd >= 0);
    CHECK(dup2(boot_fd, BOOT_FD) == BOOT_FD); if (boot_fd != BOOT_FD) { __real_close(boot_fd); boot_fd = -1; }
    view = mmap(NULL, sizeof(*view), PROT_READ | PROT_WRITE, MAP_SHARED, BOOT_FD, 0); CHECK(view != MAP_FAILED); CHECK(mlock(view, sizeof(*view)) == 0); view_locked = 1;
    wipe(view, sizeof(*view)); view->magic = MAGIC; view->generation = GENERATION; view->owner = (uint32_t)getpid();
    uint16_t jump_port = 0, target_port = 0;
    jump_listener = listener(&jump_port); CHECK(jump_listener >= 0);
    target_listener = listener(&target_port); CHECK(target_listener >= 0 && target_port != jump_port);
    char config[2048]; int config_size = snprintf(config, sizeof(config),
        "Include parts/*.conf\nHost target\n HostName 127.0.0.1\n Port %u\n ProxyJump jump\n"
        "Host jump\n HostName 127.0.0.1\n Port %u\n User synthetic\n"
        "Host *\n IdentityFile " IDENTITY "\n IdentitiesOnly yes\n IdentityAgent none\n"
        " UserKnownHostsFile " HOSTS "\n GlobalKnownHostsFile none\n"
        " BatchMode yes\n StrictHostKeyChecking yes\n UpdateHostKeys no\n ConnectTimeout 2\n", target_port, jump_port);
    CHECK(config_size > 0 && (size_t)config_size < sizeof(config));
    const char first[] = "Host probe\n User first\n Include nested/options.conf\n", second[] = "Host probe\n User second\n", nested[] = "Host probe\n Port 2222\n HostName 127.0.0.1\n";
    CHECK(add_record(CONFIG, config, (size_t)config_size) == 0);
    CHECK(add_record(SSH_ROOT "/parts/20-second.conf", second, sizeof(second) - 1) == 0);
    CHECK(add_record(SSH_ROOT "/parts/10-first.conf", first, sizeof(first) - 1) == 0);
    CHECK(add_record(SSH_ROOT "/nested/options.conf", nested, sizeof(nested) - 1) == 0);
    wipe(config, sizeof(config));
    phase = "crypto_version"; CHECK(OpenSSL_version_num() == OPENSSL_VERSION_NUMBER);
    protocol(phase, 0, OpenSSL_version_num(), getpid());
    CHECK(sshkey_generate(KEY_ED25519, 256, &key) == 0);
    blob = sshbuf_new(); CHECK(blob != NULL && sshbuf_set_max_size(blob, BYTE_LIMIT) == 0);
    CHECK(sshkey_private_to_fileblob(key, blob, "", "synthetic-g2", 0, NULL, 0) == 0);
    CHECK(sshbuf_len(blob) > 0 && sshbuf_len(blob) <= BYTE_LIMIT);
    CHECK(mlock(sshbuf_mutable_ptr(blob), sshbuf_len(blob)) == 0);
    CHECK(add_record(IDENTITY, sshbuf_ptr(blob), sshbuf_len(blob)) == 0);
    wipe(sshbuf_mutable_ptr(blob), sshbuf_len(blob)); CHECK(munlock(sshbuf_mutable_ptr(blob), sshbuf_len(blob)) == 0);
    sshbuf_free(blob); blob = NULL;
    CHECK(add_record(HOSTS, NULL, 0) == 0);
    protocol("capacity", 0, BYTE_LIMIT, getpid()); protocol("object_limit", 0, OBJECT_LIMIT, getpid());
    protocol("proposal_stream", 0, 1, getpid()); protocol("local_scope", 0, 1, getpid());
    size_t captured = 0; int exit_code = 0; pid_t primary, jump;
    phase = "config";
    error = launch(argv[1], 0, -1, -1, 0, capture, &captured, &exit_code); CHECK(error == 0 && exit_code == 0);
    CHECK(strstr(capture, "user first\n") != NULL && strstr(capture, "port 2222\n") != NULL && strstr(capture, "hostname 127.0.0.1\n") != NULL);
    error = client_evidence(&primary, &jump, &error_stage); CHECK(error == 0 && jump < 0); wipe(capture, CAPTURE_LIMIT); protocol(phase, 0, 1, getpid());
    phase = "fd_negative";
    CHECK(fcntl(BOOT_FD, F_GETFD) >= 0);
    error = launch(argv[2], 1, -1, -1, 0, capture, &captured, &exit_code); CHECK(error == 0 && exit_code != 0);
    CHECK(strstr(capture, "Can't open user config file") != NULL && strstr(capture, "/proc/self/fd/198") != NULL && strstr(capture, "No such file or directory") != NULL);
    uint32_t event_count = atomic_load(&view->events_used); CHECK(event_count == 2);
    CHECK(view->events[0].kind == EV_BEFORE && view->events[1].kind == EV_AFTER && view->events[0].error == 0 && view->events[1].error == 0 && view->events[0].pid == view->events[1].pid);
    wipe(capture, CAPTURE_LIMIT); protocol(phase, 0, 1, getpid());
    phase = "identity_hostfile";
    CHECK(initialize() == 0);
    CHECK(sshkey_load_private_type(KEY_UNSPEC, IDENTITY, "", &loaded, NULL) == 0 && sshkey_equal(key, loaded));
    sshkey_free(loaded); loaded = NULL;
    CHECK(add_host_to_hostfile(HOSTS, "synthetic.example.invalid", key, 0) == 1);
    hostkeys = init_hostkeys(); CHECK(hostkeys != NULL); load_hostkeys(hostkeys, "synthetic.example.invalid", HOSTS, 0);
    CHECK(check_key_in_hostkeys(hostkeys, key, NULL) == HOST_OK); free_hostkeys(hostkeys); hostkeys = NULL;
    CHECK(sshkey_generate(KEY_ED25519, 256, &replacement) == 0); struct sshkey *keys[1] = {replacement};
    CHECK(hostfile_replace_entries(HOSTS, "synthetic.example.invalid", NULL, keys, 1, 0, 1, SSH_FP_HASH_DEFAULT) == 0);
    hostkeys = init_hostkeys(); CHECK(hostkeys != NULL); load_hostkeys(hostkeys, "synthetic.example.invalid", HOSTS, 0);
    CHECK(check_key_in_hostkeys(hostkeys, replacement, NULL) == HOST_OK && check_key_in_hostkeys(hostkeys, key, NULL) == HOST_CHANGED);
    free_hostkeys(hostkeys); hostkeys = NULL;
    struct object *proposal = lookup(HOSTS); CHECK(proposal != NULL && proposal->public_proposal && proposal->locked && proposal->logical > 0 && proposal->logical <= BYTE_LIMIT);
    memcpy(capture, proposal->memory, proposal->logical); CHECK(memchr(capture, '\n', proposal->logical) != NULL); wipe(capture, CAPTURE_LIMIT);
    protocol(phase, 0, 1, getpid());
    phase = "proxy_inherit";
    error_stage = 1; error = launch(argv[1], 0, jump_listener, target_listener, 0, capture, &captured, &exit_code); CHECK(error == 0);
    error_stage = 2; if (exit_code == 0) { error = EPROTO; goto done; }
    error = client_evidence(&primary, &jump, &error_stage); CHECK(error == 0);
    error_stage = 3; if (primary <= 0 || jump <= 0 || primary == jump) { error = EPROTO; goto done; }
    protocol("client_parent", 0, 1, primary); protocol("client_jump", 0, 1, jump);
    error_stage = 0; wipe(capture, CAPTURE_LIMIT); protocol(phase, 0, 2, getpid());
    phase = "cancel_reap";
    error_stage = 1; error = launch(argv[1], 0, jump_listener, target_listener, 1, capture, &captured, &exit_code); CHECK(error == 0);
    error_stage = 2; if (exit_code == 0) { error = EPROTO; goto done; }
    error = client_evidence(&primary, &jump, &error_stage); CHECK(error == 0);
    error_stage = 3; if (jump <= 0 || primary == jump || active_pid > 0) { error = EPROTO; goto done; }
    error_stage = 0; wipe(capture, CAPTURE_LIMIT); protocol(phase, 0, 1, getpid());
    phase = "revoke_local";
    errno = 0; CHECK(__wrap_fopen(SSH_ROOT "/missing", "r") == NULL && errno == ENOENT);
    errno = 0; CHECK(__wrap_open(SSH_ROOT "/../outside", O_RDONLY) < 0 && errno == EINVAL);
    held = __wrap_open(IDENTITY, O_RDONLY); CHECK(held >= 0);
    view->revoked = 1;
    errno = 0; CHECK(__wrap_open(IDENTITY, O_RDONLY) < 0 && errno == EACCES);
    CHECK(add_host_to_hostfile(HOSTS, "rejected.example.invalid", key, 0) == 0);
    CHECK(cleanup_objects(1) == 0);
    unsigned char byte = 0; CHECK(pread(held, &byte, 1, 0) == 0); CHECK(__wrap_close(held) == 0); held = -1;
    protocol(phase, 0, 1, getpid());
done:
    if (error != 0) protocol(phase, error, error_stage, getpid());
    if (active_pid > 0) { int status = 0, reaped = 0; int result = collect_group(&status, &reaped); if (result != 0 && error == 0) error = result; }
    if (held >= 0) __wrap_close(held);
    if (jump_listener >= 0 && __real_close(jump_listener) != 0 && error == 0) error = errno;
    if (target_listener >= 0 && __real_close(target_listener) != 0 && error == 0) error = errno;
    if (hostkeys != NULL) free_hostkeys(hostkeys);
    if (loaded != NULL) sshkey_free(loaded);
    if (replacement != NULL) sshkey_free(replacement);
    if (key != NULL) sshkey_free(key);
    if (blob != NULL) { wipe(sshbuf_mutable_ptr(blob), sshbuf_len(blob)); (void)munlock(sshbuf_mutable_ptr(blob), sshbuf_len(blob)); sshbuf_free(blob); }
    int result = cleanup_objects(1); if (result != 0 && error == 0) error = result;
    if (view != NULL && view != MAP_FAILED) {
        wipe(view, sizeof(*view)); if (!is_zero(view, sizeof(*view)) && error == 0) error = EIO;
        if (view_locked && munlock(view, sizeof(*view)) != 0 && error == 0) error = errno;
        if (munmap(view, sizeof(*view)) != 0 && error == 0) error = errno;
        view = NULL;
    }
    if (fcntl(BOOT_FD, F_GETFD) >= 0) { if (ftruncate(BOOT_FD, 0) != 0 && error == 0) error = errno; if (__real_close(BOOT_FD) != 0 && error == 0) error = errno; }
    if (boot_fd >= 0 && boot_fd != BOOT_FD) __real_close(boot_fd);
    if (capture != MAP_FAILED) {
        wipe(capture, CAPTURE_LIMIT); if (!is_zero(capture, CAPTURE_LIMIT) && error == 0) error = EIO;
        if (capture_locked && munlock(capture, CAPTURE_LIMIT) != 0 && error == 0) error = errno;
        if (munmap(capture, CAPTURE_LIMIT) != 0 && error == 0) error = errno;
    }
    if (active_pid > 0 || failure != 0) { if (error == 0) error = failure != 0 ? failure : ETIMEDOUT; }
    protocol("cleanup", error, 0, getpid()); protocol("gate", error, error == 0 ? 6 : 0, getpid());
    return error == 0 ? 0 : 1;
#undef CHECK
}
#endif
#endif

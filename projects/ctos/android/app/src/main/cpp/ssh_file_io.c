#define _GNU_SOURCE
#include "ssh_file_io.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/memfd.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

int ctos_ssh_io_init_source(struct ctos_ssh_io *io, const struct ctos_ssh_file *files,
    unsigned int count, int (*source_live)(const void *), const void *source, int (*close_stream)(FILE *)) {
    if (io == NULL || close_stream == NULL || files == NULL ||
        count > CTOS_SSH_VIEW_FILES || source_live == NULL) { errno = EINVAL; return -1; }
    if (source_live(source) != 0) return -1;
    memset(io, 0, sizeof(*io)); io->files = files; io->count = count;
    io->source_live = source_live; io->source = source;
    io->max_opens = CTOS_SSH_IO_OPENS; io->max_read_bytes = CTOS_SSH_IO_READ_BYTES;
    io->close_stream = close_stream; io->active = 1;
    return 0;
}
static int view_live(const void *view) { return ctos_ssh_view_live(view); }
int ctos_ssh_io_init(struct ctos_ssh_io *io, const struct ctos_ssh_view *view, int (*close_stream)(FILE *)) {
    if (io == NULL || close_stream == NULL) { errno = EINVAL; return -1; }
    if (ctos_ssh_view_live(view) != 0) return -1;
    return ctos_ssh_io_init_source(io, view->files, view->count, view_live, view, close_stream);
}

int ctos_ssh_io_resolve(const char *name, int pattern, char relative[513]) {
    if (name == NULL || relative == NULL) { errno = EACCES; return -1; }
    const char *part;
    if (strncmp(name, CTOS_SSH_VIRTUAL_HOME "/", sizeof(CTOS_SSH_VIRTUAL_HOME)) == 0)
        part = name + sizeof(CTOS_SSH_VIRTUAL_HOME);
    else if (pattern && strncmp(name, "~/", 2) == 0) part = name + 2;
    else { errno = EACCES; return -1; }
    size_t length = strnlen(part, 513);
    if (ctos_ssh_view_path((const unsigned char *)part, length, pattern) != 0) { errno = EACCES; return -1; }
    memcpy(relative, part, length); relative[length] = 0;
    return 0;
}

static int ready(struct ctos_ssh_io *io) {
    if (io == NULL || !io->active || io->error != 0) { errno = EACCES; return -1; }
    if (io->source_live(io->source) != 0) { io->error = errno; return -1; }
    return 0;
}

static int release_reader(struct ctos_ssh_io *io, struct ctos_ssh_reader *r) {
    int failure = r->failed ? EIO : 0;
    if (r->mapping != NULL) {
        volatile unsigned char *bytes = r->mapping;
        for (size_t i = 0; i < r->length; i++) bytes[i] = 0;
        for (size_t i = 0; i < r->length; i++) if (bytes[i] != 0) { failure = EIO; break; }
        if (r->locked && munlock(r->mapping, r->length) != 0) failure = errno;
        r->locked = 0;
        if (munmap(r->mapping, r->length) != 0) failure = errno;
        else r->mapping = NULL;
    }
    if (r->mapping == NULL && r->fd >= 0) {
        if (ftruncate(r->fd, 0) != 0) failure = errno;
        if (r->stream != NULL) {
            FILE *stream = r->stream; r->stream = NULL; r->fd = -1;
            if (io->close_stream(stream) != 0) failure = errno == 0 ? EIO : errno;
        } else {
            int fd = r->fd; r->fd = -1;
            if (close(fd) != 0) failure = errno;
        }
    }
    if (r->mapping == NULL && r->fd < 0 && r->live) {
        io->held_bytes -= (unsigned int)r->length; r->live = 0;
    }
    if (failure != 0) { r->failed = 1; io->error = failure; errno = failure; return -1; }
    return 0;
}

FILE *ctos_ssh_io_open(struct ctos_ssh_io *io, const char *name, unsigned int type) {
    if (ready(io) != 0) return NULL;
    if (io->opens >= io->max_opens) { io->error = E2BIG; errno = E2BIG; return NULL; }
    io->opens++;
    char relative[513];
    if (ctos_ssh_io_resolve(name, 0, relative) != 0) { io->error = errno; return NULL; }
    if (type < CTOS_SSH_CONFIG || type > CTOS_SSH_KNOWN_HOSTS) { io->error = EINVAL; errno = EINVAL; return NULL; }
    const struct ctos_ssh_file *file = NULL;
    size_t path_length = strlen(relative);
    for (unsigned int i = 0; i < io->count; i++) {
        const struct ctos_ssh_file *f = &io->files[i];
        if (f->path.length == path_length && memcmp(f->path.data, relative, path_length) == 0) {
            if (f->type == type && type != CTOS_SSH_PRIVATE_KEY) file = f;
            else { io->error = EACCES; errno = EACCES; return NULL; }
            break;
        }
    }
    if (file == NULL) { io->error = ENOENT; errno = ENOENT; return NULL; }
    size_t length = file->body.length;
    if (length > CTOS_SSH_VIEW_TOTAL_BYTES - io->held_bytes || length > io->max_read_bytes - io->read_bytes) {
        io->error = E2BIG; errno = E2BIG; return NULL;
    }
    struct ctos_ssh_reader *reader = NULL;
    for (unsigned int i = 0; i < CTOS_SSH_IO_STREAMS; i++) if (!io->readers[i].live) { reader = &io->readers[i]; break; }
    if (reader == NULL) { io->error = EMFILE; errno = EMFILE; return NULL; }
    *reader = (struct ctos_ssh_reader){.fd = -1, .length = length, .live = 1};
    io->held_bytes += (unsigned int)length; io->read_bytes += (unsigned int)length;
    reader->fd = (int)syscall(SYS_memfd_create, "ctos-ssh-read", MFD_CLOEXEC);
    if (reader->fd < 0 || ftruncate(reader->fd, (off_t)length) != 0) goto failed;
    struct stat shape;
    int descriptor_flags = fcntl(reader->fd, F_GETFD);
    /* The reader is a private anonymous FD, never a pathname or exported loan.
     * Keep the real inode mode; chmod is denied by ordinary Android app policy. */
    if (descriptor_flags < 0 || fstat(reader->fd, &shape) != 0) goto failed;
    if ((descriptor_flags & FD_CLOEXEC) == 0 || !S_ISREG(shape.st_mode)
        || shape.st_uid != getuid() || shape.st_nlink != 0 || shape.st_size != (off_t)length) {
        errno = EACCES; goto failed;
    }
    if (length != 0) {
        void *mapping = mmap(NULL, length, PROT_READ | PROT_WRITE, MAP_SHARED, reader->fd, 0);
        if (mapping == MAP_FAILED) goto failed;
        reader->mapping = mapping;
        if (madvise(mapping, length, MADV_DONTDUMP) != 0 || mlock(mapping, length) != 0) goto failed;
        reader->locked = 1; memcpy(mapping, file->body.data, length);
    }
    reader->stream = fdopen(reader->fd, "r");
    if (reader->stream == NULL || setvbuf(reader->stream, NULL, _IONBF, 0) != 0) goto failed;
    if (ready(io) != 0) goto failed;
    return reader->stream;
failed: {
        int failure = errno == 0 ? EIO : errno;
        if (release_reader(io, reader) != 0) failure = EIO;
        io->error = failure;
        errno = failure; return NULL;
    }
}

int ctos_ssh_io_owns(const struct ctos_ssh_io *io, FILE *stream) {
    if (io == NULL || stream == NULL) return 0;
    for (unsigned int i = 0; i < CTOS_SSH_IO_STREAMS; i++)
        if (io->readers[i].live && io->readers[i].stream == stream) return 1;
    return 0;
}

int ctos_ssh_io_close(struct ctos_ssh_io *io, FILE *stream) {
    if (!ctos_ssh_io_owns(io, stream)) { errno = EACCES; return EOF; }
    for (unsigned int i = 0; i < CTOS_SSH_IO_STREAMS; i++)
        if (io->readers[i].live && io->readers[i].stream == stream)
            return release_reader(io, &io->readers[i]) == 0 ? 0 : EOF;
    errno = EACCES; return EOF;
}

int ctos_ssh_io_destroy(struct ctos_ssh_io *io) {
    if (io == NULL) { errno = EINVAL; return -1; }
    io->active = 0;
    for (unsigned int i = 0; i < CTOS_SSH_IO_STREAMS; i++)
        if (io->readers[i].live) (void)release_reader(io, &io->readers[i]);
    if (io->error != 0 || io->held_bytes != 0) { errno = io->error == 0 ? EIO : io->error; return -1; }
    return 0;
}

/* stat/opendir allow a single trailing slash on a virtual directory. */
static int directory_name(const char *name, char relative[513]) {
    if (name == NULL) { errno = EACCES; return -1; }
    if (strcmp(name, CTOS_SSH_VIRTUAL_HOME) == 0 || strcmp(name, CTOS_SSH_VIRTUAL_HOME "/") == 0) {
        relative[0] = 0; return 0;
    }
    size_t length = strnlen(name, sizeof(CTOS_SSH_VIRTUAL_HOME) + 514);
    if (length >= sizeof(CTOS_SSH_VIRTUAL_HOME) + 514) { errno = EACCES; return -1; }
    char normalized[sizeof(CTOS_SSH_VIRTUAL_HOME) + 514];
    memcpy(normalized, name, length + 1);
    if (length != 0 && normalized[length - 1] == '/') normalized[length - 1] = 0;
    return ctos_ssh_io_resolve(normalized, 0, relative);
}

int ctos_ssh_io_stat(struct ctos_ssh_io *io, const char *name, struct stat *out) {
    if (out == NULL) { errno = EINVAL; return -1; }
    if (ready(io) != 0) return -1;
    char relative[513]; if (directory_name(name, relative) != 0) return -1;
    size_t length = strlen(relative);
    memset(out, 0, sizeof(*out)); out->st_uid = getuid(); out->st_gid = getgid();
    if (length == 0) { out->st_mode = S_IFDIR | 0700; return 0; }
    for (unsigned int i = 0; i < io->count; i++) {
        const struct ctos_ssh_file *file = &io->files[i];
        if (file->path.length == length && memcmp(file->path.data, relative, length) == 0) {
            /* Logical vault metadata, not the backing anonymous inode's mode. */
            out->st_mode = S_IFREG | 0600; out->st_size = file->declared_bytes; return 0;
        }
        if (file->path.length > length && file->path.data[length] == '/'
            && memcmp(file->path.data, relative, length) == 0) { out->st_mode = S_IFDIR | 0700; return 0; }
    }
    errno = ENOENT; return -1;
}

static struct ctos_ssh_slice child_of(struct ctos_ssh_slice path, const char *directory, size_t length) {
    struct ctos_ssh_slice none = {NULL, 0};
    size_t start = 0;
    if (length != 0) {
        if (path.length <= length || path.data[length] != '/' || memcmp(path.data, directory, length) != 0) return none;
        start = length + 1;
    }
    size_t end = start;
    while (end < path.length && path.data[end] != '/') end++;
    return (struct ctos_ssh_slice){path.data + start, end - start};
}

int ctos_ssh_io_child(struct ctos_ssh_io *io, const char *name, unsigned int ordinal, char out[256]) {
    if (out == NULL || ready(io) != 0) return -1;
    struct stat shape;
    if (ctos_ssh_io_stat(io, name, &shape) != 0 || !S_ISDIR(shape.st_mode)) { errno = ENOTDIR; return -1; }
    char relative[513]; if (directory_name(name, relative) != 0) return -1;
    size_t length = strlen(relative); unsigned int found = 0;
    /* Reverse enumeration exercises the real upstream glob's lexical sort. */
    for (unsigned int i = io->count; i-- != 0;) {
        struct ctos_ssh_slice child = child_of(io->files[i].path, relative, length);
        if (child.length == 0) continue;
        int duplicate = 0;
        for (unsigned int j = io->count; j-- > i + 1;) {
            struct ctos_ssh_slice previous = child_of(io->files[j].path, relative, length);
            if (child.length == previous.length && memcmp(child.data, previous.data, child.length) == 0) { duplicate = 1; break; }
        }
        if (duplicate) continue;
        if (child.length > 255) { io->error = E2BIG; errno = E2BIG; return -1; }
        if (found++ != ordinal) continue;
        memcpy(out, child.data, child.length); out[child.length] = 0; return 1;
    }
    return 0;
}

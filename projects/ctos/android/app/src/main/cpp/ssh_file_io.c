#define _GNU_SOURCE
#include "ssh_file_io.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/memfd.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

int ctos_ssh_io_init(struct ctos_ssh_io *io, const struct ctos_ssh_view *view, int (*close_stream)(FILE *)) {
    if (io == NULL || close_stream == NULL) { errno = EINVAL; return -1; }
    if (ctos_ssh_view_live(view) != 0) return -1;
    memset(io, 0, sizeof(*io)); io->view = view; io->close_stream = close_stream; io->active = 1;
    return 0;
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
    if (ctos_ssh_view_live(io->view) != 0) { io->error = errno; return -1; }
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
    if (++io->opens > CTOS_SSH_IO_OPENS) { io->error = E2BIG; errno = E2BIG; return NULL; }
    char relative[513];
    if (ctos_ssh_io_resolve(name, 0, relative) != 0) { io->error = errno; return NULL; }
    const struct ctos_ssh_file *file = ctos_ssh_view_lookup(io->view,
        (const unsigned char *)relative, strlen(relative), type);
    if (file == NULL) { io->error = errno; return NULL; }
    size_t length = file->body.length;
    if (length > CTOS_SSH_VIEW_TOTAL_BYTES - io->held_bytes || length > CTOS_SSH_IO_READ_BYTES - io->read_bytes) {
        io->error = E2BIG; errno = E2BIG; return NULL;
    }
    struct ctos_ssh_reader *reader = NULL;
    for (unsigned int i = 0; i < CTOS_SSH_IO_STREAMS; i++) if (!io->readers[i].live) { reader = &io->readers[i]; break; }
    if (reader == NULL) { io->error = EMFILE; errno = EMFILE; return NULL; }
    *reader = (struct ctos_ssh_reader){.fd = -1, .length = length, .live = 1};
    io->held_bytes += (unsigned int)length; io->read_bytes += (unsigned int)length;
    reader->fd = (int)syscall(SYS_memfd_create, "ctos-ssh-read", MFD_CLOEXEC);
    if (reader->fd < 0 || fchmod(reader->fd, 0600) != 0 || ftruncate(reader->fd, (off_t)length) != 0) goto failed;
    if (length != 0) {
        void *mapping = mmap(NULL, length, PROT_READ | PROT_WRITE, MAP_SHARED, reader->fd, 0);
        if (mapping == MAP_FAILED) goto failed;
        reader->mapping = mapping;
        if (madvise(mapping, length, MADV_DONTDUMP) != 0 || mlock(mapping, length) != 0) goto failed;
        reader->locked = 1; memcpy(mapping, file->body.data, length);
    }
    reader->stream = fdopen(reader->fd, "r");
    if (reader->stream == NULL || setvbuf(reader->stream, NULL, _IONBF, 0) != 0) goto failed;
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
    for (unsigned int i = 0; i < io->view->count; i++) {
        const struct ctos_ssh_file *file = &io->view->files[i];
        if (file->path.length == length && memcmp(file->path.data, relative, length) == 0) {
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
    for (unsigned int i = io->view->count; i-- != 0;) {
        struct ctos_ssh_slice child = child_of(io->view->files[i].path, relative, length);
        if (child.length == 0) continue;
        int duplicate = 0;
        for (unsigned int j = io->view->count; j-- > i + 1;) {
            struct ctos_ssh_slice previous = child_of(io->view->files[j].path, relative, length);
            if (child.length == previous.length && memcmp(child.data, previous.data, child.length) == 0) { duplicate = 1; break; }
        }
        if (duplicate) continue;
        if (child.length > 255) { io->error = E2BIG; errno = E2BIG; return -1; }
        if (found++ != ordinal) continue;
        memcpy(out, child.data, child.length); out[child.length] = 0; return 1;
    }
    return 0;
}

#define _GNU_SOURCE
#include <android/sharedmem.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

/* Capability experiment only: no OpenSSH, vault, arbitrary argv or data files.
 * setProt never claims to revoke a pre-existing mapping. Revocation below is
 * cooperative, covering only these owned mappings, buffers and descriptors. */
enum { MAGIC = 0x43534730, GENERATION = 73, OBJECT = 1, PAYLOAD = 64 };
enum { READY = 1, DENIED, READ_OK, WIPED, UNMAPPED, EOF_OK, CANCELLED };
struct command { uint32_t magic, generation, object, operation; };
struct reply { uint32_t magic, phase; int32_t error; uint32_t value; };
struct region { int fd; unsigned char *mapping; size_t size; int locked; };
struct child { pid_t pid; int input, output; };
static volatile sig_atomic_t stopping;
/* Failed reap retains an owned PID until a later bounded cleanup or our
 * process death triggers that child's installed PDEATHSIG. */
static pid_t pending_child = -1;
static int reap_clock_failed;

static void stop_signal(int number) { (void) number; stopping = 1; }
static void wipe(void *memory, size_t size) {
    volatile unsigned char *bytes = memory;
    while (size-- != 0) *bytes++ = 0;
}
static int zero(const unsigned char *bytes, size_t size) {
    for (size_t i = 0; i < size; ++i) if (bytes[i] != 0) return 0;
    return 1;
}
static int64_t now_ms(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return -1;
    return (int64_t) now.tv_sec * 1000 + now.tv_nsec / 1000000;
}
static void record(const char *backend, const char *phase, const char *state, int error, uint64_t value) {
    printf("SSH_G0 backend=%s phase=%s state=%s errno=%d value=%llu\n",
           backend, phase, state, error, (unsigned long long) value);
    fflush(stdout);
}
static int transfer(int fd, void *buffer, size_t size, int writing, int timeout_ms) {
    int64_t start = now_ms();
    if (start < 0) return errno == 0 ? EIO : errno;
    size_t done = 0;
    while (done < size) {
        if (stopping) return ECANCELED;
        int64_t current = now_ms();
        if (current < 0) return EIO;
        int64_t remaining = start + timeout_ms - current;
        if (remaining <= 0) return ETIMEDOUT;
        struct pollfd descriptor = { fd, writing ? POLLOUT : POLLIN, 0 };
        int result = poll(&descriptor, 1, (int) remaining);
        if (result < 0) { if (errno == EINTR) continue; return errno; }
        if (result == 0) return ETIMEDOUT;
        ssize_t count = writing ? write(fd, (unsigned char *) buffer + done, size - done)
                                : read(fd, (unsigned char *) buffer + done, size - done);
        if (count < 0) { if (errno == EINTR || errno == EAGAIN) continue; return errno; }
        if (count == 0) return EPIPE;
        done += (size_t) count;
    }
    return 0;
}
static int send_reply(uint32_t phase, int error, uint32_t value) {
    struct reply reply = { MAGIC, phase, error, value };
    return transfer(5, &reply, sizeof(reply), 1, 2500);
}
static int request(struct child *child, unsigned int operation, unsigned int generation,
                   unsigned int object, unsigned int phase, int expected_error, int timeout) {
    struct command command = { MAGIC, generation, object, operation };
    int error = transfer(child->input, &command, sizeof(command), 1, timeout);
    struct reply reply = {0};
    if (error == 0) error = transfer(child->output, &reply, sizeof(reply), 0, timeout);
    if (error == 0 && (reply.magic != MAGIC || reply.phase != phase || reply.error != expected_error || reply.value != 0)) error = EPROTO;
    wipe(&command, sizeof(command)); wipe(&reply, sizeof(reply));
    return error;
}
static int receive_ready(struct child *child) {
    struct reply reply = {0};
    int error = transfer(child->output, &reply, sizeof(reply), 0, 2500);
    if (error == 0 && (reply.magic != MAGIC || reply.phase != READY || reply.error != 0 || reply.value != PAYLOAD)) error = reply.error > 0 ? reply.error : EPROTO;
    wipe(&reply, sizeof(reply));
    return error;
}
static int close_region(struct region *region) {
    int error = 0;
    if (region->mapping != MAP_FAILED) {
        wipe(region->mapping, region->size);
        if (!zero(region->mapping, region->size)) error = EIO;
        if (region->locked && munlock(region->mapping, region->size) != 0 && error == 0) error = errno;
        if (munmap(region->mapping, region->size) != 0 && error == 0) error = errno;
        region->mapping = MAP_FAILED; region->locked = 0;
    }
    if (region->fd >= 0) {
        int fd = region->fd;
        if (close(fd) != 0 && error == 0) error = errno;
        region->fd = -1;
        errno = 0;
        if (fcntl(fd, F_GETFD) != -1 || errno != EBADF) if (error == 0) error = EIO;
    }
    return error;
}
static int reap(struct child *child, int terminate, int expected_exit) {
    int error = 0, status = 0, reaped = 0, no_child = 0;
    if (child->input >= 0) { close(child->input); child->input = -1; }
    if (child->output >= 0) { close(child->output); child->output = -1; }
    if (child->pid <= 0) return 0;
    if (terminate && kill(child->pid, SIGTERM) != 0 && errno != ESRCH) error = errno;
    int64_t started = now_ms();
    if (started < 0) { reap_clock_failed = 1; if (error == 0) error = EIO; }
    // Attempt caps remain effective even if the monotonic clock fails.
    for (unsigned int attempt = 0; started >= 0 && attempt < 100; ++attempt) {
        pid_t result = waitpid(child->pid, &status, WNOHANG);
        if (result == child->pid) { reaped = 1; break; }
        if (result < 0 && errno != EINTR) {
            no_child = errno == ECHILD;
            if (error == 0) error = errno;
            break;
        }
        int64_t current = now_ms();
        if (current < 0) { reap_clock_failed = 1; if (error == 0) error = EIO; break; }
        if (current - started >= 1000) break;
        struct timespec pause = {0, 10000000}; nanosleep(&pause, NULL);
    }
    if (!reaped && !no_child) {
        if (kill(child->pid, SIGKILL) != 0 && errno != ESRCH && error == 0) error = errno;
        started = now_ms();
        if (started < 0) { reap_clock_failed = 1; if (error == 0) error = EIO; }
        for (unsigned int attempt = 0; attempt < 200; ++attempt) {
            pid_t result = waitpid(child->pid, &status, WNOHANG);
            if (result == child->pid) { reaped = 1; break; }
            if (result < 0 && errno != EINTR) {
                no_child = errno == ECHILD;
                if (error == 0) error = errno;
                break;
            }
            int64_t current = now_ms();
            if (current < 0) { reap_clock_failed = 1; if (error == 0) error = EIO; }
            else if (started >= 0 && current - started >= 2000) break;
            struct timespec pause = {0, 10000000}; nanosleep(&pause, NULL);
        }
    }
    if (!reaped && error == 0) error = ETIMEDOUT;
    if (reaped && !terminate && (!WIFEXITED(status) || WEXITSTATUS(status) != expected_exit) && error == 0) error = ECHILD;
    if (reaped || no_child) {
        if (pending_child == child->pid) pending_child = -1;
        child->pid = -1;
    } else pending_child = child->pid;
    return error;
}
static int close_other_fds(void) {
    DIR *directory = opendir("/proc/self/fd");
    if (directory == NULL) return errno;
    int own = dirfd(directory), error = 0;
    struct dirent *entry;
    errno = 0;
    while ((entry = readdir(directory)) != NULL) {
        char *end = NULL; long fd = strtol(entry->d_name, &end, 10);
        if (end == entry->d_name || *end != '\0' || fd < 6 || fd > INT_MAX || fd == own) continue;
        if (close((int) fd) != 0 && errno != EBADF) { error = errno; break; }
        errno = 0;
    }
    if (error == 0 && errno != 0) error = errno;
    if (closedir(directory) != 0 && error == 0) error = errno;
    return error;
}
static int verify_whitelist(void) {
    DIR *directory = opendir("/proc/self/fd");
    if (directory == NULL) return errno;
    int own = dirfd(directory), error = 0;
    struct dirent *entry;
    errno = 0;
    while ((entry = readdir(directory)) != NULL) {
        char *end = NULL; long fd = strtol(entry->d_name, &end, 10);
        if (end == entry->d_name || *end != '\0') continue;
        if (fd < 0 || fd > 5) { if (fd != own) { error = EACCES; break; } }
        errno = 0;
    }
    if (error == 0 && errno != 0) error = errno;
    closedir(directory);
    return error;
}
static int spawn(const char *executable, struct region *region, struct child *child, const char *mode) {
    int incoming[2] = {-1, -1}, outgoing[2] = {-1, -1}, held[3] = {-1, -1, -1};
    int error = 0;
    if (pending_child > 0) return EBUSY;
    if (pipe2(incoming, O_CLOEXEC | O_NONBLOCK) != 0 || pipe2(outgoing, O_CLOEXEC | O_NONBLOCK) != 0) { error = errno; goto done; }
    int sources[3] = {region->fd, incoming[0], outgoing[1]};
    for (int i = 0; i < 3; ++i) if ((held[i] = fcntl(sources[i], F_DUPFD_CLOEXEC, 10)) < 0) { error = errno; goto done; }
    char parent_text[32];
    pid_t parent = getpid();
    if (snprintf(parent_text, sizeof(parent_text), "%ld", (long) parent) >= (int) sizeof(parent_text)) { error = EOVERFLOW; goto done; }
    pid_t pid = fork();
    if (pid < 0) { error = errno; goto done; }
    if (pid == 0) {
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != parent) _exit(90);
        for (int i = 0; i < 3; ++i) if (dup2(held[i], i + 3) != i + 3) _exit(91);
        if (close_other_fds() != 0) _exit(92);
        char *const arguments[] = {(char *) executable, (char *) mode, parent_text, "73", NULL};
        char *const environment[] = {"LANG=C", NULL};
        execve(executable, arguments, environment);
        _exit(93);
    }
    child->pid = pid; child->input = incoming[1]; incoming[1] = -1;
    child->output = outgoing[0]; outgoing[0] = -1;
done:
    for (int i = 0; i < 3; ++i) if (held[i] >= 0) close(held[i]);
    for (int i = 0; i < 2; ++i) { if (incoming[i] >= 0) close(incoming[i]); if (outgoing[i] >= 0) close(outgoing[i]); }
    return error;
}
static int child_main(const char *mode, const char *parent_text, const char *generation_text) {
    char *end = NULL; errno = 0; long expected_parent = strtol(parent_text, &end, 10);
    if (errno != 0 || end == parent_text || *end != '\0' || expected_parent <= 1 || expected_parent > INT_MAX || strcmp(generation_text, "73") != 0) return 80;
    if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != (pid_t) expected_parent) return 81;
    int error = verify_whitelist();
    struct stat info;
    if (error == 0 && (fstat(3, &info) != 0 || info.st_size <= 0 || info.st_size > 16384)) error = errno == 0 ? EINVAL : errno;
    size_t size = error == 0 ? (size_t) info.st_size : 0;
    unsigned char *mapping = MAP_FAILED, parsed[PAYLOAD] = {0};
    int locked = 0, active = 1;
    if (error == 0) { mapping = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, 3, 0); if (mapping == MAP_FAILED) error = errno; }
    if (error == 0) { if (mlock(mapping, size) != 0) error = errno; else locked = 1; }
    if (error == 0) {
        memcpy(parsed, mapping, sizeof(parsed));
        for (size_t i = 0; i < sizeof(parsed); ++i) if (parsed[i] != (unsigned char) (i + 1)) { error = EIO; break; }
    }
    if (send_reply(READY, error, error == 0 ? PAYLOAD : 0) != 0 || error != 0) goto done;
    for (unsigned int requests = 0; requests < 12; ++requests) {
        struct command command = {0};
        error = transfer(4, &command, sizeof(command), 0, 2500);
        if (error != 0) { wipe(&command, sizeof(command)); break; }
        if (command.magic != MAGIC) { error = EPROTO; wipe(&command, sizeof(command)); break; }
        if (command.generation != GENERATION) error = send_reply(DENIED, ESTALE, 0);
        else if (command.object != OBJECT) error = send_reply(DENIED, ENOENT, 0);
        else if (!active && command.operation == 'R') error = send_reply(DENIED, EACCES, 0);
        else if (command.operation == 'R') error = send_reply(READ_OK, memcmp(parsed, mapping, PAYLOAD) == 0 ? 0 : EIO, 0);
        else if (command.operation == 'W') {
            active = 0;
            wipe(parsed, sizeof(parsed));
            if (mapping == MAP_FAILED || !zero(mapping, size)) error = EIO;
            if (mapping != MAP_FAILED) wipe(mapping, size);
            if (!zero(parsed, sizeof(parsed))) error = EIO;
            int sent = send_reply(WIPED, error, 0); if (sent != 0) error = sent;
        } else if (command.operation == 'U') {
            if (active || mapping == MAP_FAILED || !zero(parsed, sizeof(parsed)) || !zero(mapping, size)) error = EACCES;
            if (error == 0 && locked) { if (munlock(mapping, size) != 0) error = errno; else locked = 0; }
            if (error == 0) { if (munmap(mapping, size) != 0) error = errno; else mapping = MAP_FAILED; }
            int sent = send_reply(UNMAPPED, error, 0); if (sent != 0) error = sent;
        } else if (command.operation == 'F') {
            unsigned char byte = 0;
            if (mapping != MAP_FAILED || active || pread(3, &byte, 1, 0) != 0) error = EIO;
            wipe(&byte, sizeof(byte));
            if (close(3) != 0 && error == 0) error = errno;
            int sent = send_reply(EOF_OK, error, 0); if (sent != 0) error = sent;
            wipe(&command, sizeof(command)); goto done;
        } else if (command.operation == 'C' && strcmp(mode, "--child-cancel") == 0) {
            wipe(parsed, sizeof(parsed)); wipe(mapping, size); active = 0;
            error = send_reply(CANCELLED, zero(parsed, sizeof(parsed)) && zero(mapping, size) ? 0 : EIO, 0);
            wipe(&command, sizeof(command)); goto done;
        } else error = EPROTO;
        wipe(&command, sizeof(command));
        if (error != 0) break;
    }
done:
    wipe(parsed, sizeof(parsed));
    if (mapping != MAP_FAILED) { wipe(mapping, size); if (locked && munlock(mapping, size) != 0 && error == 0) error = errno; if (munmap(mapping, size) != 0 && error == 0) error = errno; }
    // FD3 may already have been closed by the EOF phase; no descriptor is reopened.
    close(3); close(4); close(5);
    return error == 0 ? 0 : 82;
}
static int create_region(int raw_memfd, size_t size) {
    if (size == 0 || size > 16384) { errno = EINVAL; return -1; }
    if (!raw_memfd) return ASharedMemory_create("ctos-g0-synthetic", size);
#ifdef SYS_memfd_create
    int fd = (int) syscall(SYS_memfd_create, "ctos-g0-synthetic", 1U /* MFD_CLOEXEC */);
    if (fd >= 0 && ftruncate(fd, (off_t) size) != 0) { int error = errno; close(fd); errno = error; return -1; }
    return fd;
#else
    errno = ENOSYS; return -1;
#endif
}
static int reopen(int fd, int flags) {
    char path[64];
    int count = snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
    if (count < 0 || count >= (int) sizeof(path)) { errno = EOVERFLOW; return -1; }
    return open(path, flags | O_CLOEXEC);
}
static int posix_shape(int fd, size_t size, const char **failed_phase) {
    int a = -1, b = -1, error = 0;
    FILE *stream = NULL;
    unsigned char bytes[8] = {0};
    struct stat info;
    *failed_phase = "fstat";
    if (fstat(fd, &info) != 0) { error = errno; goto done; }
    if (!S_ISREG(info.st_mode) || info.st_size != (off_t) size) { error = ENOTSUP; goto done; }
    *failed_phase = "write";
    const unsigned char initial[4] = {1, 2, 3, 4};
    if (pwrite(fd, initial, sizeof(initial), 0) != (ssize_t) sizeof(initial)) { error = errno == 0 ? EIO : errno; goto done; }
    *failed_phase = "reopen_offsets";
    a = reopen(fd, O_RDONLY); b = reopen(fd, O_RDONLY);
    if (a < 0 || b < 0) { error = errno; goto done; }
    if (read(a, bytes, 2) != 2 || bytes[0] != 1 || bytes[1] != 2 || read(b, bytes, 2) != 2 || bytes[0] != 1 || bytes[1] != 2 || lseek(a, 0, SEEK_CUR) != 2 || lseek(b, 0, SEEK_CUR) != 2 || lseek(fd, 0, SEEK_CUR) != 0) { error = EIO; goto done; }
    close(a); a = -1; close(b); b = -1;
    *failed_phase = "stdio";
    a = reopen(fd, O_RDONLY); if (a < 0) { error = errno; goto done; }
    stream = fdopen(a, "r"); if (stream == NULL) { error = errno; goto done; } a = -1;
    if (fread(bytes, 1, 4, stream) != 4 || memcmp(bytes, initial, 4) != 0 || fseek(stream, 0, SEEK_SET) != 0 || fgetc(stream) != 1) { error = errno == 0 ? EIO : errno; goto done; }
    if (fclose(stream) != 0) { stream = NULL; error = errno; goto done; } stream = NULL;
    *failed_phase = "append_truncate_replace";
    if (ftruncate(fd, 4) != 0) { error = errno; goto done; }
    a = reopen(fd, O_WRONLY | O_APPEND); if (a < 0) { error = errno; goto done; }
    stream = fdopen(a, "a"); if (stream == NULL) { error = errno; goto done; } a = -1;
    if (fwrite(initial, 1, 4, stream) != 4 || fflush(stream) != 0) { error = errno == 0 ? EIO : errno; goto done; }
    if (fclose(stream) != 0) { stream = NULL; error = errno; goto done; } stream = NULL;
    if (fstat(fd, &info) != 0 || info.st_size != 8 || pread(fd, bytes, 8, 0) != 8 || memcmp(bytes, initial, 4) != 0 || memcmp(bytes + 4, initial, 4) != 0) { error = errno == 0 ? EIO : errno; goto done; }
    a = reopen(fd, O_RDWR | O_TRUNC); if (a < 0) { error = errno; goto done; }
    if (fstat(a, &info) != 0 || info.st_size != 0 || write(a, initial, 4) != 4 || ftruncate(a, (off_t) size) != 0 || fstat(fd, &info) != 0 || info.st_size != (off_t) size) { error = errno == 0 ? EIO : errno; goto done; }
done:
    wipe(bytes, sizeof(bytes));
    if (stream != NULL && fclose(stream) != 0 && error == 0) error = errno;
    if (a >= 0) close(a);
    if (b >= 0) close(b);
    return error;
}
static int unsupported(int error) {
    return error == ENOSYS || error == ENOTSUP || error == EINVAL || error == ESPIPE || error == EACCES || error == EPERM || error == ENODEV || error == ENOENT;
}
static int execute_lifecycle(const char *executable, struct region *region, const char **phase) {
    struct child child = {-1, -1, -1};
    int error;
    *phase = "exec_whitelist";
    error = spawn(executable, region, &child, "--child");
    if (error == 0) error = receive_ready(&child);
    if (error == 0) error = request(&child, 'R', GENERATION, OBJECT, READ_OK, 0, 2500);
    *phase = error == 0 ? "stale_missing" : *phase;
    if (error == 0) error = request(&child, 'R', GENERATION - 1, OBJECT, DENIED, ESTALE, 2500);
    if (error == 0) error = request(&child, 'R', GENERATION, OBJECT + 1, DENIED, ENOENT, 2500);
    *phase = error == 0 ? "synchronized_wipe" : *phase;
    if (error == 0) { wipe(region->mapping, region->size); if (!zero(region->mapping, region->size)) error = EIO; }
    if (error == 0) error = request(&child, 'W', GENERATION, OBJECT, WIPED, 0, 2500);
    if (error == 0) error = request(&child, 'R', GENERATION, OBJECT, DENIED, EACCES, 2500);
    if (error == 0) error = request(&child, 'U', GENERATION, OBJECT, UNMAPPED, 0, 2500);
    if (error == 0 && munlock(region->mapping, region->size) != 0) error = errno;
    if (error == 0) { region->locked = 0; if (munmap(region->mapping, region->size) != 0) error = errno; else region->mapping = MAP_FAILED; }
    *phase = error == 0 ? "held_fd_eof" : *phase;
    if (error == 0 && ftruncate(region->fd, 0) != 0) error = errno;
    if (error == 0) error = request(&child, 'F', GENERATION, OBJECT, EOF_OK, 0, 2500);
    int cleanup = reap(&child, error != 0, 0);
    return error != 0 ? error : cleanup;
}
static int cancel_timeout(const char *executable, struct region *region, int timeout, const char **phase) {
    struct child child = {-1, -1, -1};
    *phase = timeout ? "timeout_reap" : "cancel_reap";
    for (size_t i = 0; i < PAYLOAD; ++i) region->mapping[i] = (unsigned char) (i + 1);
    int error = spawn(executable, region, &child, timeout ? "--child-timeout" : "--child-cancel");
    if (error == 0) error = receive_ready(&child);
    if (error == 0 && timeout) {
        struct reply reply = {0};
        int observed = transfer(child.output, &reply, sizeof(reply), 0, 150);
        wipe(&reply, sizeof(reply));
        if (observed != ETIMEDOUT) error = observed == 0 ? EPROTO : observed;
    } else if (error == 0) error = request(&child, 'C', GENERATION, OBJECT, CANCELLED, 0, 2500);
    int cleanup = reap(&child, timeout || error != 0, 0);
    wipe(region->mapping, region->size);
    if (error == 0 && !zero(region->mapping, region->size)) error = EIO;
    return error != 0 ? error : cleanup;
}
static int candidate(const char *executable, int raw_memfd, size_t size) {
    const char *backend = raw_memfd ? "raw_memfd" : "ashared";
    struct region region = {-1, MAP_FAILED, size, 0};
    const char *phase = "invalid_size";
    int error = 0, result = -1, cleanup;
    errno = 0; int invalid = create_region(raw_memfd, 0);
    if (invalid >= 0 || errno != EINVAL) { if (invalid >= 0) close(invalid); error = EIO; goto done; }
    errno = 0; invalid = create_region(raw_memfd, 16385);
    if (invalid >= 0 || errno != EINVAL) { if (invalid >= 0) close(invalid); error = EIO; goto done; }
    record(backend, phase, "pass", 0, 2);
    phase = "create"; region.fd = create_region(raw_memfd, size);
    if (region.fd < 0) { error = errno; result = unsupported(error) ? 0 : -1; goto done; }
    record(backend, phase, "pass", 0, size);
    phase = "map_lock";
    region.mapping = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, region.fd, 0);
    if (region.mapping == MAP_FAILED) { error = errno; goto done; }
    if (mlock(region.mapping, size) != 0) { error = errno; result = unsupported(error) || error == ENOMEM ? 0 : -1; goto done; }
    region.locked = 1; wipe(region.mapping, size);
    record(backend, phase, "pass", 0, size);
    if (!raw_memfd) {
        phase = "prot_existing_mapping";
        if (ASharedMemory_setProt(region.fd, PROT_READ) != 0) { error = errno; goto done; }
        volatile unsigned char *existing = region.mapping;
        existing[0] = 1;
        if (existing[0] != 1) { error = EIO; goto done; }
        wipe(region.mapping, size);
        record(backend, phase, "pass", 0, 1);
        // A new object restores initial permissions; no setProt permission increase.
        error = close_region(&region); if (error != 0) goto done;
        region.fd = create_region(raw_memfd, size); if (region.fd < 0) { error = errno; goto done; }
    } else {
        if (munlock(region.mapping, size) != 0) { error = errno; goto done; }
        region.locked = 0;
        if (munmap(region.mapping, size) != 0) { error = errno; goto done; }
        region.mapping = MAP_FAILED;
    }
    error = posix_shape(region.fd, size, &phase);
    if (error != 0) { result = unsupported(error) ? 0 : -1; goto done; }
    record(backend, "posix_shape", "pass", 0, size);
    phase = "map_lock";
    region.mapping = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, region.fd, 0);
    if (region.mapping == MAP_FAILED) { error = errno; goto done; }
    if (mlock(region.mapping, size) != 0) { error = errno; goto done; }
    region.locked = 1; wipe(region.mapping, size);
    error = cancel_timeout(executable, &region, 0, &phase);
    if (error != 0) goto done;
    record(backend, phase, "pass", 0, 1);
    error = cancel_timeout(executable, &region, 1, &phase);
    if (error != 0) goto done;
    record(backend, phase, "pass", 0, 1);
    for (size_t i = 0; i < PAYLOAD; ++i) region.mapping[i] = (unsigned char) (i + 1);
    error = execute_lifecycle(executable, &region, &phase);
    if (error != 0) goto done;
    record(backend, "exec_whitelist", "pass", 0, GENERATION);
    record(backend, "stale_missing", "pass", 0, 2);
    record(backend, "synchronized_wipe", "pass", 0, size);
    record(backend, "held_fd_eof", "pass", 0, 0);
    result = 1;
done:
    cleanup = close_region(&region);
    if (cleanup == 0 && pending_child > 0) cleanup = ETIMEDOUT;
    if (cleanup != 0) { error = cleanup; phase = "cleanup"; result = -1; }
    if (result != 1) record(backend, phase, result == 0 ? "unsupported" : "error", error, 0);
    record(backend, "cleanup", cleanup == 0 ? "pass" : "error", cleanup, 0);
    record(backend, "candidate", result == 1 ? "pass" : result == 0 ? "unsupported" : "error", error, result == 1 ? 1 : 0);
    return result;
}
int main(int argc, char **argv) {
    struct sigaction action = {0}; action.sa_handler = stop_signal; sigemptyset(&action.sa_mask);
    if (sigaction(SIGTERM, &action, NULL) != 0 || sigaction(SIGINT, &action, NULL) != 0 || signal(SIGPIPE, SIG_IGN) == SIG_ERR) return 1;
    if (argc == 4 && (strcmp(argv[1], "--child") == 0 || strcmp(argv[1], "--child-cancel") == 0 || strcmp(argv[1], "--child-timeout") == 0)) return child_main(argv[1], argv[2], argv[3]);
    if (argc != 2 || strcmp(argv[1], "--probe") != 0 || argv[0][0] != '/') { record("meta", "arguments", "error", EINVAL, 0); return 1; }
    pid_t parent = getppid();
    if (parent <= 1 || prctl(PR_SET_PDEATHSIG, SIGTERM) != 0 || getppid() != parent) { record("meta", "parent_guard", "error", ECHILD, 0); return 1; }
    long page = sysconf(_SC_PAGESIZE);
    struct rlimit limit;
    if (page <= 0 || page > 16384 || getrlimit(RLIMIT_MEMLOCK, &limit) != 0) { record("meta", "limits", "error", errno == 0 ? EINVAL : errno, 0); return 1; }
    record("meta", "page_size", "pass", 0, (uint64_t) page);
    record("meta", "memlock_limit", "pass", 0, limit.rlim_cur == RLIM_INFINITY ? UINT64_MAX : (uint64_t) limit.rlim_cur);
    record("meta", "raw_syscall_candidate", "pass", 0, 1);
    int a = candidate(argv[0], 0, (size_t) page);
    int b = stopping ? -1 : candidate(argv[0], 1, (size_t) page);
    int eligible = (a == 1) + (b == 1);
    int failed = a < 0 || b < 0 || stopping;
    if (pending_child > 0) {
        struct child pending = {pending_child, -1, -1};
        // A previous failed reap stays a failure even if this final retry works.
        (void) reap(&pending, 1, 0);
        failed = 1;
    }
    if (reap_clock_failed) { record("meta", "reap_clock_failure", "error", EIO, 0); failed = 1; }
    record("meta", "gate", failed ? "error" : eligible > 0 ? "pass" : "unsupported", failed ? EIO : eligible > 0 ? 0 : ENOTSUP, (uint64_t) eligible);
    return failed ? 1 : eligible > 0 ? 0 : 2;
}

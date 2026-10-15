/* Synthetic real-parser policy tests; no production provider or filesystem. */
#include "includes.h"
#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <pwd.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include "log.h"
#include "ssh.h"
#include "misc.h"
#include "readconf.h"
#include "sshconnect.h"
#include "ssh_config_policy.h"
#include "ssh_file_io.h"
#include "openbsd-compat/glob.h"

extern Options options;
extern struct sshkey *previous_host_key;
extern int ctos_upstream_main(int, char **);
static unsigned int side_effects, total;
static struct ctos_ssh_policy_error last_error;
static int last_result, last_memory;
static unsigned int last_effects;
static int memory_enabled, memory_error;
static const char *config_paths[] = {
    "/ctos-policy/root.conf", "/ctos-policy/.ssh/child.conf", "/ctos-policy/.ssh/leaf.conf"
};
static const char *config_text[3];
struct memory_file { FILE *file; void *bytes; size_t length; int fd, locked, live; };
static struct memory_file memory_files[16];
static struct ctos_ssh_io *managed_io;
static struct ctos_ssh_view *retained_view;
static struct ctos_ssh_io *retained_io;
extern int __real_fclose(FILE *);
extern int __real__ssh__compat_glob(const char *, int, int (*)(const char *, int), glob_t *);
extern void __real__ssh__compat_globfree(glob_t *);
extern pid_t __real_fork(void);
extern int __real_execv(const char *, char *const []);
static pid_t owned_shell;
static struct passwd identity = {
    .pw_name = "synthetic", .pw_dir = "/ctos-policy", .pw_shell = "/system/bin/sh"
};

/* Any reach into execution, network or ordinary path access fails the probe. */
static int deny_effect(void) { side_effects++; errno = EPERM; return -1; }
pid_t __wrap_fork(void) { return (pid_t)deny_effect(); }
pid_t __wrap_vfork(void) { return (pid_t)deny_effect(); }
int __wrap_execv(const char *p, char *const a[]) { (void)p; (void)a; return deny_effect(); }
int __wrap_execve(const char *p, char *const a[], char *const e[]) {
    (void)p; (void)a; (void)e; return deny_effect();
}
int __wrap_access(const char *p, int m) { (void)p; (void)m; return deny_effect(); }
int __wrap_open(const char *p, int f, ...) { (void)p; (void)f; return deny_effect(); }
int __wrap_open64(const char *p, int f, ...) { (void)p; (void)f; return deny_effect(); }
int __wrap___open_2(const char *p, int f) { (void)p; (void)f; return deny_effect(); }
static void wipe(void *bytes, size_t length) {
    volatile unsigned char *cursor = bytes;
    while (length-- != 0) *cursor++ = 0;
}
static int dispose_memory(struct memory_file *file) {
    int error = 0;
    if (file->bytes != NULL) {
        wipe(file->bytes, file->length);
        if (file->locked && munlock(file->bytes, file->length) != 0) error = errno;
        if (munmap(file->bytes, file->length) != 0) {
            if (error == 0) error = errno;
        } else file->bytes = NULL;
    }
    if (file->file != NULL) {
        if (__real_fclose(file->file) != 0 && error == 0) error = errno;
        file->file = NULL; file->fd = -1;
    } else if (file->fd >= 0) {
        if (syscall(SYS_close, file->fd) != 0 && error == 0) error = errno;
        file->fd = -1;
    }
    if (file->bytes == NULL && file->fd < 0) file->live = 0;
    if (error != 0) memory_error = error;
    return error;
}
FILE *__wrap_fopen(const char *p, const char *m) {
    if (managed_io != NULL) {
        if (strcmp(m, "r") != 0) { deny_effect(); return NULL; }
        return ctos_ssh_io_open(managed_io, p, CTOS_SSH_CONFIG);
    }
    if (!memory_enabled || strcmp(m, "r") != 0) { deny_effect(); return NULL; }
    size_t index;
    for (index = 0; index < 3; index++) if (strcmp(config_paths[index], p) == 0) break;
    if (index == 3) { deny_effect(); return NULL; }
    if (config_text[index] == NULL) { errno = ENOENT; return NULL; }
    size_t length = strlen(config_text[index]);
    if (length == 0 || length > 4096) { memory_error = EINVAL; errno = EINVAL; return NULL; }
    struct memory_file *file = NULL;
    for (size_t i = 0; i < 16; i++) if (!memory_files[i].live) { file = &memory_files[i]; break; }
    if (file == NULL) { memory_error = ENOSPC; errno = ENOSPC; return NULL; }
    *file = (struct memory_file){.fd = -1, .length = length, .live = 1};
    file->fd = (int)syscall(SYS_memfd_create, "ctos-policy-config", 1U);
    int error = file->fd < 0 ? errno : 0;
    if (error == 0 && fchmod(file->fd, 0600) != 0) error = errno;
    if (error == 0 && ftruncate(file->fd, (off_t)length) != 0) error = errno;
    if (error == 0) {
        void *bytes = mmap(NULL, length, PROT_READ | PROT_WRITE, MAP_SHARED, file->fd, 0);
        if (bytes == MAP_FAILED) error = errno;
        else file->bytes = bytes;
    }
    if (error == 0) {
        if (mlock(file->bytes, length) != 0) error = errno;
        else file->locked = 1;
    }
    if (error == 0) {
        memcpy(file->bytes, config_text[index], length);
        file->file = fdopen(file->fd, "r");
        if (file->file == NULL) error = errno;
    }
    if (error != 0) { memory_error = error; dispose_memory(file); errno = error; return NULL; }
    return file->file;
}
FILE *__wrap_fopen64(const char *p, const char *m) { return __wrap_fopen(p, m); }
int __wrap_fclose(FILE *stream) {
    if (ctos_ssh_io_owns(managed_io, stream)) return ctos_ssh_io_close(managed_io, stream);
    for (size_t i = 0; i < 16; i++) if (memory_files[i].live && memory_files[i].file == stream)
        return dispose_memory(&memory_files[i]) == 0 ? 0 : EOF;
    return deny_effect();
}
struct passwd *__wrap_getpwuid(uid_t uid) {
    if (uid != getuid()) { deny_effect(); return NULL; }
    return &identity;
}
struct memory_directory { size_t next; struct dirent entry; char path[sizeof(CTOS_SSH_VIRTUAL_HOME) + 514]; int managed; };
static void *memory_opendir(const char *path) {
    if (managed_io != NULL) {
        struct stat shape;
        if (ctos_ssh_io_stat(managed_io, path, &shape) != 0 || !S_ISDIR(shape.st_mode)) { errno = ENOTDIR; return NULL; }
        struct memory_directory *directory = calloc(1, sizeof(*directory));
        if (directory == NULL) { memory_error = ENOMEM; return NULL; }
        size_t length = strnlen(path, sizeof(directory->path));
        if (length == sizeof(directory->path)) { free(directory); memory_error = E2BIG; return NULL; }
        memcpy(directory->path, path, length + 1); directory->managed = 1;
        return directory;
    }
    if (strcmp(path, "/ctos-policy/.ssh") != 0 && strcmp(path, "/ctos-policy/.ssh/") != 0) {
        errno = ENOENT; return NULL;
    }
    void *directory = calloc(1, sizeof(struct memory_directory));
    if (directory == NULL) memory_error = ENOMEM;
    return directory;
}
static struct dirent *memory_readdir(void *opaque) {
    struct memory_directory *directory = opaque;
    if (directory->managed) {
        int result = ctos_ssh_io_child(managed_io, directory->path, (unsigned int)directory->next++, directory->entry.d_name);
        if (result < 0) memory_error = errno;
        return result == 1 ? &directory->entry : NULL;
    }
    while (directory->next < 2) {
        size_t index = directory->next++ == 0 ? 2 : 1;
        if (config_text[index] == NULL) continue;
        strcpy(directory->entry.d_name, index == 1 ? "child.conf" : "leaf.conf");
        return &directory->entry;
    }
    return NULL;
}
static void memory_closedir(void *opaque) { free(opaque); }
static int memory_stat(const char *path, struct stat *info) {
    if (managed_io != NULL) return ctos_ssh_io_stat(managed_io, path, info);
    memset(info, 0, sizeof(*info)); info->st_uid = getuid();
    if (strcmp(path, "/ctos-policy") == 0 || strcmp(path, "/ctos-policy/") == 0 ||
        strcmp(path, "/ctos-policy/.ssh") == 0 || strcmp(path, "/ctos-policy/.ssh/") == 0) {
        info->st_mode = S_IFDIR | 0700; return 0;
    }
    for (size_t i = 0; i < 3; i++) if (strcmp(path, config_paths[i]) == 0 && config_text[i] != NULL) {
        info->st_mode = S_IFREG | 0600; info->st_size = (off_t)strlen(config_text[i]); return 0;
    }
    errno = ENOENT; return -1;
}
int __wrap__ssh__compat_glob(const char *pattern, int flags,
    int (*error_fn)(const char *, int), glob_t *result) {
    if ((!memory_enabled && managed_io == NULL) || flags != GLOB_TILDE) { deny_effect(); return GLOB_ABORTED; }
    result->gl_opendir = memory_opendir; result->gl_readdir = memory_readdir;
    result->gl_closedir = memory_closedir; result->gl_stat = memory_stat; result->gl_lstat = memory_stat;
    return __real__ssh__compat_glob(pattern, flags | GLOB_ALTDIRFUNC, error_fn, result);
}
void __wrap__ssh__compat_globfree(glob_t *result) { __real__ssh__compat_globfree(result); }
int __wrap_socket(int d, int t, int p) { (void)d; (void)t; (void)p; return deny_effect(); }
int __wrap_connect(int s, const struct sockaddr *a, socklen_t n) {
    (void)s; (void)a; (void)n; return deny_effect();
}

static int parse(const char *text, const char *object, int line, int *active, int flags) {
    char buffer[1024];
    if (strlen(text) >= sizeof(buffer)) return -2;
    memcpy(buffer, text, strlen(text) + 1);
    return process_config_line(&options, &identity, "target", "target", NULL,
        buffer, object, line, active, SSHCONF_USERCONF | flags);
}
static void begin(void) {
    initialize_options(&options);
    ctos_ssh_policy_reset();
    side_effects = 0;
    memory_error = 0;
    memory_enabled = 0;
}
static int finish(int result, int expected, unsigned int reason, unsigned int object, unsigned int line) {
    struct ctos_ssh_policy_error error = ctos_ssh_policy_error();
    int ok = result == expected && side_effects == 0 && error.reason == reason &&
        memory_error == 0 && (reason == 0 || (error.object == object && error.line == line));
    for (size_t i = 0; i < 16; i++) if (memory_files[i].live) {
        ok = 0;
        dispose_memory(&memory_files[i]);
    }
    last_error = error; last_result = result;
    last_memory = memory_error; last_effects = side_effects;
    free_options(&options);
    if (ok) total++;
    return ok ? 0 : -1;
}
static int one(const char *text, int active, int flags, int expected, unsigned int reason) {
    begin();
    if ((flags & SSHCONF_FINAL) != 0) {
        options.hostname = strdup("target");
        if (options.hostname == NULL) return -1;
    }
    int result = parse(text, "/ctos-policy/child.conf", 17, &active, flags);
    return finish(result, expected, reason, 2, 17);
}
static int sequence(const char *first, const char *second, int expected, unsigned int reason) {
    begin();
    int active = 1;
    int result = parse(first, "/ctos-policy/root.conf", 3, &active, 0);
    if (result == 0) result = parse(second, "/ctos-policy/child.conf", 17, &active, 0);
    return finish(result, expected, reason, 2, 17);
}
static int record(const char *phase, unsigned int before, int result) {
    printf("SSH_POLICY phase=%s state=%s errno=%d value=%u\n", phase,
        result == 0 ? "pass" : "error", result == 0 ? 0 : EPROTO, total - before);
    return result;
}
static int fail(const char *phase, unsigned int before) {
    record(phase, before, -1);
    const char *names[] = {"failure_reason", "failure_object", "failure_line",
        "failure_native", "failure_memory", "failure_effects"};
    unsigned int values[] = {last_error.reason, last_error.object, last_error.line,
        (unsigned int)(last_result + 2), (unsigned int)last_memory, last_effects};
    for (size_t i = 0; i < 6; i++)
        printf("SSH_POLICY phase=%s state=error errno=%d value=%u\n", names[i], EPROTO, values[i]);
    return 1;
}
static int closure(const char *root, const char *child, const char *leaf,
    const char *host, int expected, unsigned int reason, unsigned int object, unsigned int line, int port) {
    begin();
    memory_enabled = 1;
    config_text[0] = root; config_text[1] = child; config_text[2] = leaf;
    int want_final = 0;
    int result = read_config_file(config_paths[0], &identity, host, host, NULL,
        &options, SSHCONF_USERCONF, &want_final);
    memory_enabled = 0;
    if (port != 0 && options.port != port) result = -2;
    return finish(result, expected, reason, object, line);
}

struct bridge_wire { unsigned char bytes[16384]; size_t length; int error; };
static void bridge_bytes(struct bridge_wire *w, const void *bytes, size_t length) {
    if (w->error || length > sizeof(w->bytes) - w->length) { w->error = 1; return; }
    memcpy(w->bytes + w->length, bytes, length); w->length += length;
}
static void bridge_u32(struct bridge_wire *w, uint32_t value) {
    unsigned char bytes[] = {(unsigned char)(value >> 24), (unsigned char)(value >> 16),
        (unsigned char)(value >> 8), (unsigned char)value};
    bridge_bytes(w, bytes, 4);
}
static void bridge_u64(struct bridge_wire *w, uint64_t value) {
    bridge_u32(w, (uint32_t)(value >> 32)); bridge_u32(w, (uint32_t)value);
}
static void bridge_text(struct bridge_wire *w, const char *value) {
    bridge_u32(w, (uint32_t)strlen(value)); bridge_bytes(w, value, strlen(value));
}
static void bridge_object(struct bridge_wire *w, unsigned int type, const char *id,
    const char *path, const char *body, int root) {
    unsigned char tag = (unsigned char)type; bridge_bytes(w, &tag, 1);
    bridge_text(w, id); bridge_text(w, path); bridge_text(w, "public-synthetic-identity");
    bridge_u32(w, type == CTOS_SSH_PRIVATE_KEY ? 32 : (uint32_t)strlen(body));
    bridge_u32(w, root ? 3 : 0);
    if (root) {
        bridge_text(w, ".ssh/child.conf"); bridge_text(w, ".ssh/leaf.conf"); bridge_text(w, ".ssh/key");
    }
    bridge_u32(w, (uint32_t)strlen(body)); bridge_bytes(w, body, strlen(body));
}
static int managed_closure(const char *root, const char *child, const char *leaf,
    const char *host, int expected, unsigned int reason, unsigned int object, unsigned int line, int port) {
    begin();
    struct ctos_ssh_view *view = calloc(1, sizeof(*view));
    struct ctos_ssh_io *io = calloc(1, sizeof(*io));
    int result = -2, retained = 0;
    const char *old_home = identity.pw_dir;
    struct bridge_wire wire = {0};
    if (view != NULL) view->fd = -1;
    if (view == NULL || io == NULL || retained_view != NULL || retained_io != NULL) {
        memory_error = ENOMEM; goto done;
    }
    struct timespec time;
    if (clock_gettime(CLOCK_BOOTTIME, &time) != 0 || time.tv_sec < 0) { memory_error = EIO; goto done; }
    unsigned char uuid[16] = {1}, binding[32] = {2};
    bridge_u32(&wire, 0x43535056U); bridge_u32(&wire, 1); bridge_bytes(&wire, uuid, 16);
    bridge_u64(&wire, 73); bridge_u64(&wire, 7);
    bridge_u64(&wire, (uint64_t)time.tv_sec * 1000 + (uint64_t)time.tv_nsec / 1000000 + 120000);
    bridge_text(&wire, "synthetic-request"); bridge_text(&wire, "synthetic-connection"); bridge_text(&wire, ".ssh/config");
    bridge_bytes(&wire, binding, 32); bridge_u32(&wire, 4);
    bridge_object(&wire, CTOS_SSH_CONFIG, "root", ".ssh/config", root, 1);
    bridge_object(&wire, CTOS_SSH_CONFIG, "child", ".ssh/child.conf", child, 0);
    bridge_object(&wire, CTOS_SSH_CONFIG, "leaf", ".ssh/leaf.conf", leaf, 0);
    bridge_object(&wire, CTOS_SSH_PRIVATE_KEY, "key", ".ssh/key", "", 0);
    if (wire.error || ctos_ssh_view_allocate(view, wire.length) != 0) { memory_error = errno == 0 ? EIO : errno; goto done; }
    memcpy(view->mapping, wire.bytes, wire.length);
    if (ctos_ssh_view_validate(view) != 0 || ctos_ssh_io_init(io, view, __real_fclose) != 0) {
        memory_error = errno == 0 ? EIO : errno; goto done;
    }
    managed_io = io; identity.pw_dir = CTOS_SSH_VIRTUAL_HOME;
    ctos_ssh_policy_fixture_view(view);
    int want_final = 0;
    result = read_config_file(CTOS_SSH_VIRTUAL_HOME "/.ssh/config", &identity, host, host,
        NULL, &options, SSHCONF_USERCONF, &want_final);
    if (port != 0 && options.port != port) result = -2;
done:
    wipe(wire.bytes, sizeof(wire.bytes));
    if (io != NULL && io->view != NULL) {
        if (ctos_ssh_io_destroy(io) != 0) memory_error = errno == 0 ? EIO : errno;
        for (unsigned int i = 0; i < CTOS_SSH_IO_STREAMS; i++) if (io->readers[i].live) retained = 1;
    }
    managed_io = NULL; identity.pw_dir = (char *)old_home;
    ctos_ssh_policy_fixture_view(NULL);
    if (!retained && view != NULL) {
        if (ctos_ssh_view_destroy(view) != 0) memory_error = errno == 0 ? EIO : errno;
        if (view->mapping != NULL || view->fd >= 0) retained = 1;
    }
    if (retained) { retained_view = view; retained_io = io; memory_error = EIO; }
    else { free(io); free(view); }
    return finish(result, expected, reason, object, line);
}

static int64_t milliseconds(void) {
    struct timespec time;
    if (clock_gettime(CLOCK_MONOTONIC, &time) != 0) return -1;
    return (int64_t)time.tv_sec * 1000 + time.tv_nsec / 1000000;
}
static int shell_compare(const char *value) {
    if (owned_shell != 0) return -1;
    char *quoted = ctos_ssh_policy_shell_arg(value);
    if (quoted == NULL) return -1;
    char *expanded = percent_expand(quoted, "h", "unwanted", "p", "999", (char *)NULL);
    free(quoted);
    size_t length = strlen(expanded) + 32;
    char *command = malloc(length);
    if (command == NULL) { free(expanded); return -1; }
    snprintf(command, length, "test %s = \"$1\"", expanded);
    free(expanded);
    int null_fd = (int)syscall(SYS_openat, AT_FDCWD, "/dev/null", O_RDWR | O_CLOEXEC, 0);
    struct stat info;
    if (null_fd < 3 || fstat(null_fd, &info) != 0 || !S_ISCHR(info.st_mode)) {
        if (null_fd >= 0) syscall(SYS_close, null_fd);
        free(command); return -1;
    }
    pid_t parent = getpid();
    pid_t pid = __real_fork();
    if (pid == 0) {
        if (setpgid(0, 0) != 0 || prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 ||
            getppid() != parent) _exit(90);
        for (int fd = 0; fd < 3; fd++) if (dup2(null_fd, fd) != fd) _exit(91);
        closefrom(3);
        char *args[] = {"/system/bin/sh", "-c", command, "ctos-policy", (char *)value, NULL};
        __real_execv(args[0], args);
        _exit(92);
    }
    int error = 0, status = 0;
    if (syscall(SYS_close, null_fd) != 0) error = errno;
    free(command);
    if (pid < 0) return -1;
    owned_shell = pid;
    int group = (setpgid(pid, pid) == 0 || errno == EACCES) && getpgid(pid) == pid;
    int64_t start = milliseconds();
    siginfo_t outcome;
    memset(&outcome, 0, sizeof(outcome));
    if (!group || start < 0) error = EIO;
    for (int attempt = 0; error == 0 && attempt < 200; attempt++) {
        memset(&outcome, 0, sizeof(outcome));
        if (waitid(P_PID, (id_t)pid, &outcome, WEXITED | WNOHANG | WNOWAIT) != 0) {
            if (errno != EINTR) error = errno;
        }
        if (outcome.si_pid == pid) break;
        int64_t now = milliseconds();
        if (now < 0 || now - start >= 2000 || attempt == 199) error = ETIMEDOUT;
        else if (poll(NULL, 0, 10) < 0 && errno != EINTR) error = errno;
    }
    if (outcome.si_pid != pid || outcome.si_code != CLD_EXITED || outcome.si_status != 0)
        error = error == 0 ? EPROTO : error;
    /* The primary child remains a zombie ownership anchor until all signals
     * have been issued. No signalling occurs once any group member is reaped. */
    if (group) {
        if (kill(-pid, SIGTERM) != 0 && errno != ESRCH) error = errno;
        if (poll(NULL, 0, 50) < 0 && errno != EINTR) error = errno;
        if (kill(-pid, SIGKILL) != 0 && errno != ESRCH) error = errno;
    } else if (kill(pid, SIGKILL) != 0 && errno != ESRCH) error = errno;
    int primary_reaped = 0, empty = 0;
    for (int attempt = 0; attempt < 100; attempt++) {
        pid_t reaped = waitpid(group ? -pid : pid, &status, WNOHANG);
        if (reaped == pid) primary_reaped = 1;
        else if (reaped < 0 && errno == ECHILD) { empty = 1; break; }
        else if (reaped < 0 && errno != EINTR) { error = errno; break; }
        if (reaped <= 0 && poll(NULL, 0, 10) < 0 && errno != EINTR) { error = errno; break; }
    }
    if (primary_reaped && empty) owned_shell = 0;
    else error = error == 0 ? EBUSY : error;
    return error == 0 ? 0 : -1;
}

static int proxy_format(int full) {
    begin();
    int active = 1;
    if (parse(full ? "ProxyJump ssh://space%20user@first:2221,jump:2222" : "ProxyJump jump",
            "/ctos-policy/root.conf", 1, &active, 0) != 0)
        return finish(-1, 0, 0, 0, 0);
    char *command = ctos_ssh_policy_proxy_command("/ctos-policy/client.so",
        "/ctos-policy/root' file.conf", &options, "target%zone", 22, full ? 2 : 0);
    if (command == NULL) return finish(-1, 0, 0, 0, 0);
    char *expanded = percent_expand(command, "h", "unwanted", "p", "999", (char *)NULL);
    free(command);
    int count = 0;
    char **args = NULL;
    int result = argv_split(expanded, &count, &args, 0);
    free(expanded);
    const char *base[] = {"/ctos-policy/client.so", "-F", "/ctos-policy/root' file.conf",
        "-W", "[target%zone]:22", "--", "jump"};
    const char *multi[] = {"/ctos-policy/client.so", "-F", "/ctos-policy/root' file.conf",
        "-p", "2222", "-J", "ssh://space%20user@first:2221", "-vv",
        "-W", "[target%zone]:22", "--", "jump"};
    const char **expected = full ? multi : base;
    int expected_count = full ? 12 : 7;
    if (result == 0 && count == expected_count) {
        for (int i = 0; i < count; i++) if (strcmp(args[i], expected[i]) != 0) result = -1;
    } else result = -1;
    argv_free(args, count);
    return finish(result, 0, 0, 0, 0);
}

int main(int argc, char **argv) {
    if (argc != 2 || strcmp(argv[1], "--synthetic-policy") != 0) return 64;
    log_init("ctos-policy-fixture", SYSLOG_LEVEL_QUIET, SYSLOG_FACILITY_AUTH, 1);
    identity.pw_uid = getuid();
    struct sigaction child_signal;
    memset(&child_signal, 0, sizeof(child_signal));
    child_signal.sa_handler = SIG_DFL;
    if (sigemptyset(&child_signal.sa_mask) != 0 || sigaction(SIGCHLD, &child_signal, NULL) != 0)
        return 70;
    if (prctl(PR_SET_CHILD_SUBREAPER, 1) != 0) return 70;
    unsigned int before = total;
    const char *exec_cases[] = {
        "Match exec \"none\"", "Match !exec \"none\"", "Match EXEC=none",
        "Match !exec=none", "Match host other exec none",
        "Match exec \"%Q\"", "Match exec \"${CTOS_POLICY_UNSET}\""
    };
    for (size_t i = 0; i < sizeof(exec_cases) / sizeof(exec_cases[0]); i++) {
        if (one(exec_cases[i], 1, 0, -1, CTOS_POLICY_MATCH_EXEC) != 0 ||
            one(exec_cases[i], 0, SSHCONF_NEVERMATCH, -1, CTOS_POLICY_MATCH_EXEC) != 0 ||
            one(exec_cases[i], 1, SSHCONF_FINAL, -1, CTOS_POLICY_MATCH_EXEC) != 0)
            return fail("match_exec", before);
    }
    record("match_exec", before, 0);
    before = total;
    const char *safe_commands[] = {"LocalCommand none", "ProxyCommand NoNe", "KnownHostsCommand none"};
    const char *bad_commands[] = {"LocalCommand no", "ProxyCommand no", "KnownHostsCommand synthetic"};
    for (size_t i = 0; i < 3; i++) {
        if (one(safe_commands[i], 1, 0, 0, 0) != 0 ||
            one(bad_commands[i], 1, 0, -1, CTOS_POLICY_COMMAND) != 0 ||
            one(bad_commands[i], 0, 0, 0, 0) != 0 ||
            sequence(safe_commands[i], bad_commands[i], 0, 0) != 0)
            return fail("commands", before);
    }
    record("commands", before, 0);
    before = total;
    const char *strict_bad[] = {"no", "off", "false", "accept-new"};
    for (size_t i = 0; i < 4; i++) {
        char setting[128]; snprintf(setting, sizeof(setting), "StrictHostKeyChecking %s", strict_bad[i]);
        if (one(setting, 1, 0, -1, CTOS_POLICY_STRICT) != 0 ||
            one(setting, 0, 0, 0, 0) != 0 ||
            sequence("StrictHostKeyChecking yes", setting, 0, 0) != 0)
            return fail("trust_options", before);
    }
    if (one("StrictHostKeyChecking ASK # comment", 1, 0, 0, 0) != 0 ||
        one("StrictHostKeyChecking true", 1, SSHCONF_FINAL, 0, 0) != 0 ||
        one("VerifyHostKeyDNS yes", 1, 0, -1, CTOS_POLICY_DNS) != 0 ||
        one("VerifyHostKeyDNS ask", 1, 0, -1, CTOS_POLICY_DNS) != 0 ||
        one("VerifyHostKeyDNS no", 1, 0, 0, 0) != 0 ||
        one("NoHostAuthenticationForLocalhost yes", 1, 0, -1, CTOS_POLICY_LOCALHOST) != 0 ||
        one("UserKnownHostsFile none", 1, 0, 0, 0) != 0)
        return fail("trust_options", before);
    record("trust_options", before, 0);
    before = total;
    const char *external[] = {
        "ForwardAgent yes", "IdentityAgent synthetic", "AddKeysToAgent confirm",
        "PKCS11Provider synthetic", "SecurityKeyProvider internal", "ControlMaster auto",
        "ControlPersist 60", "ControlPath synthetic", "ForwardX11 yes",
        "ForwardX11Trusted yes", "HostbasedAuthentication yes", "EnableSSHKeysign yes",
        "ForkAfterAuthentication yes", "Tunnel yes", "EnableEscapeCommandline yes",
        "PermitLocalCommand yes", "ProxyUseFdpass yes",
        "GSSAPIAuthentication yes", "GSSAPIDelegateCredentials yes"
    };
    for (size_t i = 0; i < sizeof(external) / sizeof(external[0]); i++) {
        if (one(external[i], 1, 0, -1, CTOS_POLICY_EXTERNAL) != 0 ||
            one(external[i], 0, 0, 0, 0) != 0)
            return fail("external", before);
    }
    if (one("IdentityAgent none", 1, 0, 0, 0) != 0 ||
        one("ControlMaster no", 1, 0, 0, 0) != 0 ||
        one("PermitLocalCommand no", 1, 0, 0, 0) != 0 ||
        one("GSSAPIAuthentication no", 1, 0, 0, 0) != 0)
        return fail("external", before);
    record("external", before, 0);
    before = total;
    if (one("ProxyJump synthetic@jump:2222", 1, 0, 0, 0) != 0 ||
        one("ProxyJump first:2222,synthetic@second:2223", 1, 0, 0, 0) != 0 ||
        one("ProxyJump jump;synthetic", 1, 0, -1, 0) != 0 ||
        one("ProxyJump user;synthetic@jump", 1, 0, -1, 0) != 0 ||
        one("ProxyJump jump;synthetic", 0, 0, -1, 0) != 0)
        return fail("jump_syntax", before);
    record("jump_syntax", before, 0);
    before = total;
    if (closure("Include child.conf\n", "Include leaf.conf\n", "Port 2222\n",
            "target", 1, 0, 0, 0, 2222) != 0 ||
        closure("Host other\nInclude child.conf\nHost target\nPort 22\n", "Match exec none\n", NULL,
            "target", -1, CTOS_POLICY_MATCH_EXEC, 2, 1, 0) != 0 ||
        closure("Include child.conf\n", "Include leaf.conf\n", "LocalCommand no\n",
            "target", -1, CTOS_POLICY_COMMAND, 3, 1, 0) != 0 ||
        closure("Include *.conf\n", "Port 2222\n", "Port 2223\n",
            "target", 1, 0, 0, 0, 2222) != 0 ||
        closure("Include /ctos-policy/.ssh/leaf.conf\n", NULL, "Port 22\n",
            "target", 1, 0, 0, 0, 22) != 0 ||
        closure("Include ~synthetic-other/child.conf\n", "Port 22\n", NULL,
            "target", -1, CTOS_POLICY_PATH, 1, 1, 0) != 0 ||
        closure("Include ../outside.conf\n", "Port 22\n", NULL,
            "target", -1, CTOS_POLICY_PATH, 1, 1, 0) != 0 ||
        closure("Include %d/.ssh/../outside.conf\n", "Port 22\n", NULL,
            "target", -1, CTOS_POLICY_PATH, 1, 1, 0) != 0 ||
        closure("Include %h.conf\n", "Port 22\n", NULL,
            "../outside", -1, CTOS_POLICY_PATH, 1, 1, 0) != 0 ||
        closure("Include /proc/self/fd/198\n", NULL, NULL,
            "target", -1, CTOS_POLICY_PATH, 1, 1, 0) != 0 ||
        closure("Include missing.conf\n", "Port 22\n", NULL,
            "target", -1, CTOS_POLICY_PATH, 1, 1, 0) != 0 ||
        closure("Include child.conf\n", "Include child.conf\n", NULL,
            "target", -1, CTOS_POLICY_PATH, 2, 0, 0) != 0 ||
        closure("Include child.conf\n", "BogusKeyword synthetic\n", NULL,
            "target", -1, 0, 0, 0, 0) != 0 ||
        closure("Host other\nInclude child.conf\nHost target\nPort 22\n", "Match host target\nLocalCommand no\n", NULL,
            "target", 1, 0, 0, 0, 22) != 0)
        return fail("include_closure", before);
    record("include_closure", before, 0);
    before = total;
    const char hop_config[] = "Host target\nStrictHostKeyChecking yes\nHost jump\nStrictHostKeyChecking no\n";
    if (closure(hop_config, NULL, NULL, "target", 1, 0, 0, 0, 0) != 0 ||
        closure(hop_config, NULL, NULL, "jump", -1, CTOS_POLICY_STRICT, 1, 4, 0) != 0)
        return fail("hop_options", before);
    record("hop_options", before, 0);
    before = total;
    if (managed_closure("Include child.conf\n", "Include leaf.conf\n", "Port 2222\n",
            "target", 1, 0, 0, 0, 2222) != 0 ||
        managed_closure("Include *.conf\n", "Port 2222\n", "Port 2223\n",
            "target", 1, 0, 0, 0, 2222) != 0 ||
        managed_closure("Include /ctos-vault/.ssh/leaf.conf\n", "", "Port 22\n",
            "target", 1, 0, 0, 0, 22) != 0 ||
        managed_closure("Include ~/.ssh/leaf.conf\n", "", "Port 22\n",
            "target", 1, 0, 0, 0, 22) != 0 ||
        managed_closure("Include /proc/self/fd/198\n", "", "",
            "target", -1, CTOS_POLICY_PATH, 1, 1, 0) != 0 ||
        managed_closure("Include ../outside\n", "", "",
            "target", -1, CTOS_POLICY_PATH, 1, 1, 0) != 0 ||
        managed_closure("Include /etc/passwd\n", "", "",
            "target", -1, CTOS_POLICY_PATH, 1, 1, 0) != 0 ||
        managed_closure("Include ~other/child.conf\n", "", "",
            "target", -1, CTOS_POLICY_PATH, 1, 1, 0) != 0 ||
        managed_closure("Include missing*.conf\n", "", "",
            "target", -1, CTOS_POLICY_PATH, 1, 1, 0) != 0 ||
        managed_closure("Include key\n", "", "",
            "target", -1, CTOS_POLICY_PATH, 1, 1, 0) != 0 ||
        managed_closure("Include child.conf\n", "BogusKeyword synthetic\n", "",
            "target", -1, 0, 0, 0, 0) != 0 ||
        managed_closure("Include child.conf\n", "LocalCommand no\n", "",
            "target", -1, CTOS_POLICY_COMMAND, 2, 1, 0) != 0 ||
        managed_closure("Include child.conf\n", "Include leaf.conf\n", "StrictHostKeyChecking no\n",
            "target", -1, CTOS_POLICY_STRICT, 3, 1, 0) != 0 ||
        managed_closure("Host other\nInclude child.conf\nHost target\nPort 22\n", "Match exec none\n", "",
            "target", -1, CTOS_POLICY_MATCH_EXEC, 2, 1, 0) != 0 ||
        managed_closure(hop_config, "", "", "target", 1, 0, 0, 0, 0) != 0 ||
        managed_closure(hop_config, "", "", "jump", -1, CTOS_POLICY_STRICT, 1, 4, 0) != 0 ||
        managed_closure("Include %h.conf\n", "", "", "../outside", -1, CTOS_POLICY_PATH, 1, 1, 0) != 0 ||
        managed_closure("Port 2222\nInclude child.conf\n", "Port 2223\n", "", "target", 1, 0, 0, 0, 2222) != 0 ||
        managed_closure("Include child.conf\n", "", "", "target", 1, 0, 0, 0, 0) != 0 ||
        managed_closure("IdentityFile ~/.ssh/key\nPort 22\n", "", "", "target", 1, 0, 0, 0, 22) != 0)
        return fail("managed_files", before);
    record("managed_files", before, 0);
    before = total;
    const char *quote_cases[] = {"", "space user", "'literal", "\"literal",
        "$(exit 99)", "`exit 99`", "%h%%p", "中文'$; value"};
    for (size_t i = 0; i < 8; i++) {
        begin();
        int compared = shell_compare(quote_cases[i]);
        if (finish(compared, 0, 0, 0, 0) != 0) return fail("proxy_format", before);
    }
    if (proxy_format(0) != 0 || proxy_format(1) != 0) return fail("proxy_format", before);
    record("proxy_format", before, 0);
    before = total;
    begin();
    int result = ctos_upstream_main(0, NULL);
    if (finish(result, 255, CTOS_POLICY_PROVIDER, 0, 0) != 0)
        return fail("entry", before);
    record("entry", before, 0);
    before = total;
    begin();
    options.no_host_authentication_for_localhost = 1;
    options.verify_host_key_dns = 1;
    previous_host_key = (struct sshkey *)(uintptr_t)1;
    result = verify_host_key(NULL, NULL, NULL, NULL);
    previous_host_key = NULL;
    if (finish(result, -1, CTOS_POLICY_PROVIDER, 0, 0) != 0)
        return fail("trust_entry", before);
    record("trust_entry", before, 0);
    printf("SSH_POLICY phase=gate state=pass errno=0 value=%u\n", total);
    return 0;
}

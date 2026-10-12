/* Research-only Linux/glibc adapter. Synthetic material, never a product VFS. */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "includes.h"
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <errno.h>
#include <fcntl.h>
#include <dirent.h>
#include "openbsd-compat/glob.h"
#include <pwd.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define BOOTSTRAP_FD 198
#define OBJECT_LIMIT 16
#define BYTE_LIMIT 16384
#define ROOT "/ctos-memory/home"
#define SSH_ROOT ROOT "/.ssh"
#define CONFIG SSH_ROOT "/config"
#define IDENTITY SSH_ROOT "/keys/test_ed25519"
#define HOSTS SSH_ROOT "/known_hosts"
#define MAGIC 0x43544f53U

struct record {
    char path[256];
    uint32_t size;
    unsigned char bytes[BYTE_LIMIT];
};

struct snapshot {
    uint32_t magic;
    uint32_t generation;
    uint32_t revoked;
    uint32_t count;
    struct record records[OBJECT_LIMIT];
};

struct object {
    char path[256];
    int fd;
    unsigned char *memory;
};

static struct snapshot *snapshot;
static struct object objects[OBJECT_LIMIT];
static size_t object_count;
static int initialized;
static size_t config_reads;

extern int __real_open(const char *, int, ...);
extern FILE *__real_fopen(const char *, const char *);
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

static void require(int condition, const char *message)
{
    if (!condition) {
        fprintf(stderr, "FAIL: %s (errno=%d)\n", message, errno);
        exit(1);
    }
}

static int virtual_path(const char *path)
{
    return strncmp(path, "/ctos-memory/", 13) == 0;
}

static int canonical_path(const char *path)
{
    return virtual_path(path) && strstr(path, "/../") == NULL &&
        strstr(path, "/./") == NULL && strstr(path, "//") == NULL &&
        strlen(path) < sizeof(objects[0].path);
}

static int new_object(const char *path, const unsigned char *bytes, size_t size)
{
    struct object *object;
    if (object_count == OBJECT_LIMIT || size > BYTE_LIMIT || !canonical_path(path)) {
        errno = EINVAL;
        return -1;
    }
    object = &objects[object_count];
    object->fd = memfd_create("ctos-probe-object", MFD_CLOEXEC);
    if (object->fd == -1 || fchmod(object->fd, 0600) == -1 ||
        ftruncate(object->fd, BYTE_LIMIT) == -1)
        return -1;
    object->memory = mmap(NULL, BYTE_LIMIT, PROT_READ | PROT_WRITE,
        MAP_SHARED, object->fd, 0);
    if (object->memory == MAP_FAILED || mlock(object->memory, BYTE_LIMIT) == -1)
        return -1;
    if (size != 0)
        memcpy(object->memory, bytes, size);
    if (ftruncate(object->fd, (off_t)size) == -1)
        return -1;
    strcpy(object->path, path);
    return (int)object_count++;
}

static void initialize(void)
{
    size_t i;
    if (initialized)
        return;
    initialized = 1;
    snapshot = mmap(NULL, sizeof(*snapshot), PROT_READ | PROT_WRITE,
        MAP_SHARED, BOOTSTRAP_FD, 0);
    require(snapshot != MAP_FAILED && snapshot->magic == MAGIC,
        "validated anonymous bootstrap");
    require(snapshot->count <= OBJECT_LIMIT, "bounded manifest");
    require(mlock(snapshot, sizeof(*snapshot)) == 0, "locked bootstrap pages");
    for (i = 0; i < snapshot->count; i++) {
        require(new_object(snapshot->records[i].path,
            snapshot->records[i].bytes, snapshot->records[i].size) >= 0,
            "construct anonymous object");
    }
}

static int lookup(const char *path)
{
    size_t i;
    initialize();
    if (snapshot->revoked) {
        errno = EACCES;
        return -1;
    }
    if (!canonical_path(path)) {
        errno = EINVAL;
        return -1;
    }
    for (i = 0; i < object_count; i++) {
        if (strcmp(path, objects[i].path) == 0)
            return (int)i;
    }
    errno = ENOENT;
    return -1;
}

/* Reopen gives each consumer an independent offset; dup() alone would not. */
int __wrap_open(const char *path, int flags, ...)
{
    mode_t mode = 0600;
    char descriptor_path[64];
    int index, fd;
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode = (mode_t)va_arg(args, int);
        va_end(args);
    }
    if (!virtual_path(path))
        return __real_open(path, flags, mode);
    index = lookup(path);
    if (index < 0 && errno == ENOENT && (flags & O_CREAT))
        index = new_object(path, NULL, 0);
    if (index < 0)
        return -1;
    if ((flags & O_ACCMODE) != O_RDONLY && strcmp(path, HOSTS) != 0 &&
        strncmp(path, HOSTS ".", strlen(HOSTS) + 1) != 0) {
        errno = EACCES;
        return -1;
    }
    snprintf(descriptor_path, sizeof(descriptor_path), "/proc/self/fd/%d",
        objects[index].fd);
    fd = __real_open(descriptor_path, flags & ~(O_CREAT | O_EXCL), mode);
    if (fd >= 0 && strcmp(path, CONFIG) == 0) {
        config_reads++;
        fprintf(stderr, "CTOS_VFS config-read pid=%ld generation=%u\n",
            (long)getpid(), snapshot->generation);
    }
    return fd;
}

FILE *__wrap_fopen(const char *path, const char *mode)
{
    int flags, fd;
    FILE *stream;
    if (!virtual_path(path))
        return __real_fopen(path, mode);
    flags = mode[0] == 'r' ? O_RDONLY : O_WRONLY | O_CREAT;
    if (strchr(mode, '+'))
        flags = (flags & ~O_ACCMODE) | O_RDWR;
    if (mode[0] == 'a')
        flags |= O_APPEND;
    if (mode[0] == 'w')
        flags |= O_TRUNC;
    fd = __wrap_open(path, flags, 0600);
    if (fd < 0)
        return NULL;
    stream = fdopen(fd, mode);
    if (stream == NULL)
        close(fd);
    return stream;
}

int __wrap_stat(const char *path, struct stat *result)
{
    int index;
    size_t i, length;
    if (!virtual_path(path))
        return __real_stat(path, result);
    initialize();
    if (snapshot->revoked) {
        errno = EACCES;
        return -1;
    }
    length = strlen(path);
    for (i = 0; i < object_count; i++) {
        if (strncmp(objects[i].path, path, length) != 0 ||
            objects[i].path[length] != '/')
            continue;
        memset(result, 0, sizeof(*result));
        result->st_mode = S_IFDIR | 0700;
        result->st_uid = getuid();
        return 0;
    }
    index = lookup(path);
    return index < 0 ? -1 : fstat(objects[index].fd, result);
}

int __wrap_access(const char *path, int mode)
{
    if (!virtual_path(path))
        return __real_access(path, mode);
    return lookup(path) < 0 ? -1 : 0;
}

int __wrap_mkdir(const char *path, mode_t mode)
{
    if (!virtual_path(path))
        return __real_mkdir(path, mode);
    errno = EACCES;
    return -1;
}

struct passwd *__wrap_getpwuid(uid_t uid)
{
    static struct passwd result;
    struct passwd *original = __real_getpwuid(uid);
    if (original == NULL)
        return NULL;
    result = *original;
    result.pw_dir = ROOT;
    return &result;
}

struct directory {
    char names[OBJECT_LIMIT][256];
    size_t count;
    size_t next;
    struct dirent entry;
};

static void *memory_opendir(const char *path)
{
    struct directory *directory;
    size_t i, j, length, name_length;
    const char *name, *slash;
    char candidate[256];
    initialize();
    if (snapshot->revoked || !canonical_path(path)) {
        errno = EACCES;
        return NULL;
    }
    directory = calloc(1, sizeof(*directory));
    require(directory != NULL, "allocate synthetic directory view");
    length = strlen(path);
    if (length > 0 && path[length - 1] == '/')
        length--;
    for (i = 0; i < object_count; i++) {
        if (strncmp(objects[i].path, path, length) != 0 ||
            objects[i].path[length] != '/')
            continue;
        name = objects[i].path + length + 1;
        slash = strchr(name, '/');
        name_length = slash == NULL ? strlen(name) : (size_t)(slash - name);
        memcpy(candidate, name, name_length);
        candidate[name_length] = '\0';
        for (j = 0; j < directory->count; j++) {
            if (strcmp(directory->names[j], candidate) == 0)
                break;
        }
        if (j == directory->count)
            strcpy(directory->names[directory->count++], candidate);
    }
    return directory;
}

static struct dirent *memory_readdir(void *opaque)
{
    struct directory *directory = opaque;
    if (directory->next == directory->count)
        return NULL;
    strcpy(directory->entry.d_name, directory->names[directory->next++]);
    return &directory->entry;
}

static void memory_closedir(void *opaque)
{
    free(opaque);
}

/* Keep upstream glob matching/sorting, replacing only directory operations. */
int __wrap__ssh__compat_glob(const char *pattern, int flags,
    int (*error_fn)(const char *, int), glob_t *result)
{
    char expanded[512];
    initialize();
    if (snapshot->revoked || flags != GLOB_TILDE)
        return GLOB_ABORTED;
    result->gl_opendir = memory_opendir;
    result->gl_readdir = memory_readdir;
    result->gl_closedir = memory_closedir;
    result->gl_stat = __wrap_stat;
    result->gl_lstat = __wrap_stat;
    if (strncmp(pattern, "~/", 2) == 0) {
        snprintf(expanded, sizeof(expanded), "%s/%s", ROOT, pattern + 2);
        pattern = expanded;
    }
    return __real__ssh__compat_glob(pattern, flags | GLOB_ALTDIRFUNC,
        error_fn, result);
}

void __wrap__ssh__compat_globfree(glob_t *result)
{
    __real__ssh__compat_globfree(result);
}

int __wrap_mkstemp(char *path)
{
    int index;
    char *suffix;
    if (!virtual_path(path))
        return __real_mkstemp(path);
    initialize();
    if (snapshot->revoked) {
        errno = EACCES;
        return -1;
    }
    suffix = strchr(path, 'X');
    if (suffix == NULL || strncmp(path, HOSTS ".", strlen(HOSTS) + 1) != 0) {
        errno = EACCES;
        return -1;
    }
    memset(suffix, 'Q', strlen(suffix));
    index = new_object(path, NULL, 0);
    return index < 0 ? -1 : __wrap_open(path, O_RDWR, 0600);
}

int __wrap_unlink(const char *path)
{
    int index;
    if (!virtual_path(path))
        return __real_unlink(path);
    index = lookup(path);
    if (index < 0)
        return -1;
    close(objects[index].fd);
    munmap(objects[index].memory, BYTE_LIMIT);
    objects[index] = objects[--object_count];
    return 0;
}

int __wrap_link(const char *source, const char *target)
{
    unsigned char bytes[BYTE_LIMIT];
    ssize_t size;
    int index;
    if (!virtual_path(source) && !virtual_path(target))
        return __real_link(source, target);
    index = lookup(source);
    if (index < 0 || !canonical_path(target))
        return -1;
    size = pread(objects[index].fd, bytes, sizeof(bytes), 0);
    if (size < 0)
        return -1;
    index = new_object(target, bytes, (size_t)size);
    explicit_bzero(bytes, sizeof(bytes));
    return index < 0 ? -1 : 0;
}

int __wrap_rename(const char *source, const char *target)
{
    int index;
    if (!virtual_path(source) && !virtual_path(target))
        return __real_rename(source, target);
    if (!canonical_path(target)) {
        errno = EACCES;
        return -1;
    }
    index = lookup(source);
    if (index < 0)
        return -1;
    if (__wrap_unlink(target) == -1 && errno != ENOENT)
        return -1;
    index = lookup(source);
    require(index >= 0, "rename source still present");
    strcpy(objects[index].path, target);
    return 0;
}

/* Research-only descriptor retention: production needs a managed broker API. */
void __wrap_closefrom(int first)
{
    int fd;
    if (first > BOOTSTRAP_FD) {
        __real_closefrom(first);
        return;
    }
    for (fd = first; fd < BOOTSTRAP_FD; fd++)
        close(fd);
    __real_closefrom(BOOTSTRAP_FD + 1);
}

static void wipe_owned_view(void)
{
    size_t i;
    for (i = 0; i < object_count; i++) {
        /* Extend before erasing: reading beyond a shortened memfd is SIGBUS. */
        require(ftruncate(objects[i].fd, BYTE_LIMIT) == 0, "extend for erase");
        explicit_bzero(objects[i].memory, BYTE_LIMIT);
        require(ftruncate(objects[i].fd, 0) == 0, "revoke outstanding descriptors");
        munmap(objects[i].memory, BYTE_LIMIT);
        close(objects[i].fd);
    }
    object_count = 0;
}

__attribute__((destructor)) static void cleanup(void)
{
    if (!initialized)
        return;
    wipe_owned_view();
    munmap(snapshot, sizeof(*snapshot));
}

#ifdef SSH_MEMORY_DRIVER
#include "includes.h"
#include <arpa/inet.h>
#include <netinet/in.h>
#include <signal.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include "sshkey.h"
#include "sshbuf.h"
#include "digest.h"
#include "hostfile.h"
#include "authfile.h"
#include "log.h"

static void fixture(struct snapshot *view, const char *path,
    const void *bytes, size_t size)
{
    struct record *record;
    require(view->count < OBJECT_LIMIT && size <= BYTE_LIMIT,
        "bounded fixture object");
    record = &view->records[view->count++];
    snprintf(record->path, sizeof(record->path), "%s", path);
    record->size = (uint32_t)size;
    memcpy(record->bytes, bytes, size);
}

static int launch(char *const *args, char *output, size_t output_limit,
    int listener, int expect_cancel)
{
    int pipes[2], status, peer;
    ssize_t count;
    size_t used = 0;
    pid_t pid;
    char greeting[256];
    require(pipe(pipes) == 0, "capture native client output");
    pid = fork();
    require(pid >= 0, "fork owned client");
    if (pid == 0) {
        setpgid(0, 0);
        dup2(pipes[1], STDOUT_FILENO);
        dup2(pipes[1], STDERR_FILENO);
        close(pipes[0]);
        close(pipes[1]);
        if (listener >= 0)
            close(listener);
        execv(args[0], args);
        _exit(127);
    }
    setpgid(pid, pid);
    close(pipes[1]);
    if (listener >= 0) {
        peer = accept(listener, NULL, NULL);
        require(peer >= 0, "ProxyJump reached isolated listener");
        count = read(peer, greeting, sizeof(greeting));
        require(count > 0 && memmem(greeting, (size_t)count, "SSH-2.0-", 8),
            "ProxyJump child emitted SSH identification");
        if (expect_cancel)
            require(kill(-pid, SIGTERM) == 0, "cancel owned proxy process group");
        close(peer);
    }
    while ((count = read(pipes[0], output + used, output_limit - used - 1)) > 0) {
        used += (size_t)count;
        require(used < output_limit - 1, "bounded captured output");
    }
    output[used] = '\0';
    close(pipes[0]);
    require(waitpid(pid, &status, 0) == pid, "reap primary client");
    /* Only this probe's owned process group is signalled. */
    kill(-pid, SIGTERM);
    while (waitpid(-1, NULL, 0) > 0) {}
    return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
}

static size_t occurrences(const char *text, const char *needle)
{
    size_t count = 0;
    while ((text = strstr(text, needle)) != NULL) {
        count++;
        text += strlen(needle);
    }
    return count;
}

int main(int argc, char **argv)
{
    int fd, socket_fd, exit_code, held_fd, index;
    socklen_t address_size;
    struct sockaddr_in address;
    struct timeval timeout = { 5, 0 };
    struct snapshot *view;
    struct sshkey *key = NULL, *loaded = NULL, *replacement = NULL;
    struct sshbuf *blob;
    struct hostkeys *hostkeys;
    struct sshkey *keys[1];
    char config[2048], output[65536], known_host[BYTE_LIMIT];
    char *read_args[] = { NULL, "-G", "-F", CONFIG, "probe", NULL };
    char *proxy_args[] = { NULL, "-v", "-F", CONFIG, "target", NULL };
    char *bare_args[] = { NULL, "-G", "-F", "/proc/self/fd/198", "probe", NULL };
    const char first[] = "Host probe\n User first\n Include nested/options.conf\n";
    const char second[] = "Host probe\n User second\n";
    const char nested[] = "Host probe\n Port 2222\n HostName 127.0.0.1\n";
    require(argc == 3, "usage: memory-probe <patched-ssh> <stock-ssh>");
    require(prctl(PR_SET_CHILD_SUBREAPER, 1) == 0, "own/reap proxy descendants");
    log_init("ctos-memory-probe", SYSLOG_LEVEL_ERROR, SYSLOG_FACILITY_USER, 1);
    socket_fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    require(socket_fd >= 0, "create loopback-only fixture listener");
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    require(bind(socket_fd, (struct sockaddr *)&address, sizeof(address)) == 0,
        "bind isolated ephemeral loopback");
    require(listen(socket_fd, 2) == 0, "listen for synthetic handshake");
    setsockopt(socket_fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    address_size = sizeof(address);
    require(getsockname(socket_fd, (struct sockaddr *)&address, &address_size) == 0,
        "read fixture listener port");
    snprintf(config, sizeof(config),
        "Include parts/*.conf\n"
        "Host target\n HostName 127.0.0.1\n Port 1\n ProxyJump jump\n"
        "Host jump\n HostName 127.0.0.1\n Port %u\n User synthetic\n"
        "Host *\n IdentityFile %s\n IdentitiesOnly yes\n IdentityAgent none\n"
        " UserKnownHostsFile %s\n GlobalKnownHostsFile none\n"
        " BatchMode yes\n StrictHostKeyChecking yes\n UpdateHostKeys no\n"
        " ConnectTimeout 2\n", ntohs(address.sin_port), IDENTITY, HOSTS);
    fd = memfd_create("ctos-probe-bootstrap", 0);
    require(fd >= 0 && ftruncate(fd, sizeof(*view)) == 0,
        "allocate anonymous immutable fixture snapshot");
    require(dup2(fd, BOOTSTRAP_FD) == BOOTSTRAP_FD, "install fixed probe descriptor");
    close(fd);
    view = mmap(NULL, sizeof(*view), PROT_READ | PROT_WRITE,
        MAP_SHARED, BOOTSTRAP_FD, 0);
    require(view != MAP_FAILED, "map fixture snapshot");
    require(mlock(view, sizeof(*view)) == 0, "prevent fixture swap while live");
    view->magic = MAGIC;
    view->generation = 7;
    fixture(view, CONFIG, config, strlen(config));
    fixture(view, SSH_ROOT "/parts/20-second.conf", second, sizeof(second) - 1);
    fixture(view, SSH_ROOT "/parts/10-first.conf", first, sizeof(first) - 1);
    fixture(view, SSH_ROOT "/nested/options.conf", nested, sizeof(nested) - 1);
    require(sshkey_generate(KEY_ED25519, 256, &key) == 0, "generate synthetic private key");
    blob = sshbuf_new();
    require(blob != NULL, "allocate native key serialization buffer");
    require(sshkey_private_to_fileblob(key, blob, "", "synthetic", 0, NULL, 0) == 0,
        "serialize OpenSSH private key in memory");
    fixture(view, IDENTITY, sshbuf_ptr(blob), sshbuf_len(blob));
    fixture(view, HOSTS, "", 0);
    sshbuf_free(blob);

    read_args[0] = argv[1];
    require(launch(read_args, output, sizeof(output), -1, 0) == 0,
        "pinned OpenSSH config evaluation");
    if (!strstr(output, "user first\n"))
        fprintf(stderr, "%s", output);
    require(strstr(output, "user first\n") && strstr(output, "port 2222\n") &&
        strstr(output, "hostname 127.0.0.1\n"),
        "lexical glob order and root-relative nested Include");
    puts("PASS pinned OpenSSH -G: config, nested root-relative Include, lexical glob");

    bare_args[0] = argv[2];
    require(launch(bare_args, output, sizeof(output), -1, 0) != 0 &&
        strstr(output, "No such file or directory"),
        "stock startup closes inherited anonymous descriptor");
    puts("PASS negative control: unadapted inherited FD is rejected");

    require(sshkey_load_private_type(KEY_UNSPEC, IDENTITY, "", &loaded, NULL) == 0 &&
        sshkey_equal(key, loaded), "real authfile.c consumes anonymous IdentityFile");
    sshkey_free(loaded);
    require(add_host_to_hostfile(HOSTS, "probe.example.invalid", key, 0) == 1,
        "real hostfile.c appends trust into anonymous object");
    hostkeys = init_hostkeys();
    load_hostkeys(hostkeys, "probe.example.invalid", HOSTS, 0);
    require(check_key_in_hostkeys(hostkeys, key, NULL) == HOST_OK,
        "real hostfile.c reads appended trust");
    free_hostkeys(hostkeys);
    require(sshkey_generate(KEY_ED25519, 256, &replacement) == 0,
        "generate synthetic replacement host key");
    keys[0] = replacement;
    require(hostfile_replace_entries(HOSTS, "probe.example.invalid", NULL,
        keys, 1, 0, 1, SSH_FP_HASH_DEFAULT) == 0,
        "real hostfile.c memory mkstemp/link/rename replacement");
    hostkeys = init_hostkeys();
    load_hostkeys(hostkeys, "probe.example.invalid", HOSTS, 0);
    require(check_key_in_hostkeys(hostkeys, replacement, NULL) == HOST_OK &&
        check_key_in_hostkeys(hostkeys, key, NULL) == HOST_CHANGED,
        "native trust replacement is read back consistently");
    free_hostkeys(hostkeys);
    index = lookup(HOSTS);
    require(index >= 0 && pread(objects[index].fd, known_host,
        sizeof(known_host), 0) > 0, "capture bounded trust proposal for host writeback");
    explicit_bzero(known_host, sizeof(known_host));
    puts("PASS native IdentityFile parse and known_hosts append/read/replace/proposal");

    proxy_args[0] = argv[1];
    exit_code = launch(proxy_args, output, sizeof(output), socket_fd, 0);
    require(exit_code != 0 && occurrences(output, "CTOS_VFS config-read") >= 2,
        "ProxyJump exec child shares anonymous snapshot and fails without fallback");
    puts("PASS ProxyJump parent/exec-child snapshot inheritance and handshake failure cleanup");
    exit_code = launch(proxy_args, output, sizeof(output), socket_fd, 1);
    require(exit_code != 0 && occurrences(output, "CTOS_VFS config-read") >= 2,
        "ProxyJump cancellation reaps owned process group");
    close(socket_fd);
    puts("PASS ProxyJump owned cancellation and descendant reap");

    require(__wrap_fopen(SSH_ROOT "/missing", "r") == NULL && errno == ENOENT,
        "missing object denies access");
    require(__wrap_open(SSH_ROOT "/../outside", O_RDONLY) < 0 && errno == EINVAL,
        "path traversal rejects");
    held_fd = __wrap_open(IDENTITY, O_RDONLY);
    require(held_fd >= 0, "hold outstanding anonymous object descriptor");
    view->revoked = 1;
    require(__wrap_open(IDENTITY, O_RDONLY) < 0 && errno == EACCES,
        "lock denies new secret file access");
    require(add_host_to_hostfile(HOSTS, "blocked.example.invalid", key, 0) == 0,
        "lock rejects late native trust writeback");
    wipe_owned_view();
    require(read(held_fd, output, sizeof(output)) == 0,
        "owner lock wipe invalidates outstanding local descriptor");
    close(held_fd);
    explicit_bzero(view, sizeof(*view));
    munmap(view, sizeof(*view));
    close(BOOTSTRAP_FD);
    sshkey_free(replacement);
    sshkey_free(key);
    puts("PASS missing/traversal failure, lock rejection, local descriptor wipe and cleanup");
    require(config_reads == 0, "driver did not open config outside managed children");
    return 0;
}
#endif

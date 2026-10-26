#include "includes.h"
#include <sys/stat.h>
#include <sys/mman.h>
#include <sys/un.h>
#include <sys/socket.h>
#include <netdb.h>
#include <pwd.h>
#include <dirent.h>
#include <ifaddrs.h>
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include "ssh.h"
#include "log.h"
#include "misc.h"
#include "readconf.h"
#include "ssh_config_eval.h"
#include "ssh_file_io.h"

#ifdef CTOS_CONFIG_STATIC_TEST
_Thread_local _Alignas(64) unsigned char ctos_static_test_tls_alignment;
#endif
static _Atomic unsigned int checks, effects, capabilities;
static unsigned int case_index, wiped_sources;
static int observe, capture_owned, fail_lock, fail_dump, fail_unlock, fail_unmap;
static int expire_lock, expire_unmap, fail_read, read_error, fake_pid, fake_uid;
static uint64_t clock_offset, fixed_boottime;
static void *owned_mapping;
static size_t owned_length;
static struct { void *p; size_t n; } managed[256];
static struct ctos_ssh_config_eval *active_eval, *other_eval;
static int reenter_read, cancel_read, cancel_unmap;
#define CHECK(c) do { checks++; if (!(c)) { fprintf(stderr, "detached failure line=%d case=%u errno=%d\n", __LINE__, case_index, errno); abort(); } } while (0)

static int deny(void) { if (observe) effects++; errno = EACCES; return -1; }
FILE *__wrap_fopen(const char *p, const char *m) { (void)p; (void)m; deny(); return NULL; }
FILE *__wrap_fopen64(const char *p, const char *m) { return __wrap_fopen(p, m); }
int __wrap_open(const char *p, int f, ...) { (void)p; (void)f; return deny(); }
int __wrap_open64(const char *p, int f, ...) { (void)p; (void)f; return deny(); }
int __wrap_access(const char *p, int f) { (void)p; (void)f; return deny(); }
int __wrap_stat(const char *p, struct stat *s) { (void)p; (void)s; return deny(); }
int __wrap_lstat(const char *p, struct stat *s) { (void)p; (void)s; return deny(); }
DIR *__wrap_opendir(const char *p) { (void)p; deny(); return NULL; }
struct passwd *__wrap_getpwuid(uid_t u) { (void)u; deny(); return NULL; }
int __wrap_gethostname(char *p, size_t n) { (void)p; (void)n; return deny(); }
struct servent *__wrap_getservbyname(const char *p, const char *q) { (void)p; (void)q; deny(); return NULL; }
char *__wrap_getenv(const char *p) { (void)p; deny(); return NULL; }
int __wrap_getifaddrs(struct ifaddrs **p) { (void)p; return deny(); }
int __wrap_getaddrinfo(const char *p, const char *q, const struct addrinfo *h, struct addrinfo **r) {
    (void)p; (void)q; (void)h; (void)r; deny(); return EAI_FAIL;
}
int __wrap_socket(int d, int t, int p) { (void)d; (void)t; (void)p; return deny(); }
int __wrap_connect(int f, const struct sockaddr *s, socklen_t n) { (void)f; (void)s; (void)n; return deny(); }
pid_t __wrap_fork(void) { return (pid_t)deny(); }
pid_t __wrap_vfork(void) { return (pid_t)deny(); }
int __wrap_execv(const char *p, char *const a[]) { (void)p; (void)a; return deny(); }
int __wrap_execve(const char *p, char *const a[], char *const e[]) { (void)p; (void)a; (void)e; return deny(); }
void __wrap_sshlog(const char *file, const char *function, int line, int showfunc,
    LogLevel level, const char *suffix, const char *format, ...) {
    (void)file; (void)function; (void)line; (void)showfunc; (void)level; (void)suffix; (void)format;
    if (observe) effects++;
}
int __wrap_ctos_ssh_view_borrow_live(const struct ctos_ssh_view_borrow *b) {
    (void)b; capabilities++; errno = EACCES; return -1;
}
const struct ctos_ssh_view *__wrap_ctos_ssh_view_borrow_view(const struct ctos_ssh_view_borrow *b) {
    (void)b; capabilities++; errno = EACCES; return NULL;
}
int __wrap_ctos_ssh_view_borrow_release(struct ctos_ssh_view_borrow *b) {
    (void)b; capabilities++; errno = EACCES; return -1;
}
int __wrap_ctos_ssh_view_handle_create(const unsigned char *p, size_t n, ctos_ssh_view_token *out) {
    (void)p; (void)n; (void)out; capabilities++; errno = EACCES; return -1;
}
int __wrap_ctos_ssh_view_handle_borrow(ctos_ssh_view_token t, struct ctos_ssh_view_borrow **out) {
    (void)t; (void)out; capabilities++; errno = EACCES; return -1;
}
int __wrap_ctos_ssh_view_borrow_owned_view(struct ctos_ssh_view *v, struct ctos_ssh_view_borrow **out) {
    (void)v; (void)out; capabilities++; errno = EACCES; return -1;
}
pid_t __real_getpid(void);
uid_t __real_getuid(void);
pid_t __wrap_getpid(void) { return __real_getpid() + fake_pid; }
uid_t __wrap_getuid(void) { return __real_getuid() + (uid_t)fake_uid; }
int __real_clock_gettime(clockid_t, struct timespec *);
int __wrap_clock_gettime(clockid_t clock, struct timespec *out) {
    int result = __real_clock_gettime(clock, out);
    if (result == 0 && clock == CLOCK_BOOTTIME) {
        if (fixed_boottime != 0) {
            out->tv_sec = (time_t)(fixed_boottime / 1000);
            out->tv_nsec = (long)(fixed_boottime % 1000) * 1000000;
        }
        out->tv_sec += (time_t)(clock_offset / 1000);
        out->tv_nsec += (long)(clock_offset % 1000) * 1000000;
        if (out->tv_nsec >= 1000000000) { out->tv_sec++; out->tv_nsec -= 1000000000; }
    }
    return result;
}
static void zeroed(const void *p, size_t n) {
    const unsigned char *bytes = p;
    size_t i; for (i = 0; i < n && bytes[i] == 0; i++) {}
    CHECK(i == n);
}
int __real_madvise(void *, size_t, int);
static unsigned int mapping_index(const void *p) {
    unsigned int i;
    for (i = 0; i < 256; i++) if (managed[i].p == p) break;
    return i;
}
int __wrap_madvise(void *p, size_t n, int advice) {
    if (!observe || advice != MADV_DONTDUMP) return __real_madvise(p, n, advice);
    unsigned int i = mapping_index(NULL); CHECK(i < 256);
    managed[i].p = p; managed[i].n = n;
    if (capture_owned) { owned_mapping = p; owned_length = n; capture_owned = 0; }
    if (fail_dump) { fail_dump = 0; errno = EIO; return -1; }
    return __real_madvise(p, n, advice);
}
int __real_mlock(const void *, size_t);
int __wrap_mlock(const void *p, size_t n) {
    if (mapping_index(p) == 256) return __real_mlock(p, n);
    if (fail_lock) { fail_lock = 0; errno = EPERM; return -1; }
    int result = __real_mlock(p, n);
    if (expire_lock) { expire_lock = 0; clock_offset += 60001; }
    return result;
}
int __real_munlock(const void *, size_t);
int __wrap_munlock(const void *p, size_t n) {
    if (mapping_index(p) == 256) return __real_munlock(p, n);
    zeroed(p, n);
    if (fail_unlock) { fail_unlock = 0; errno = EIO; return -1; }
    return __real_munlock(p, n);
}
int __real_munmap(void *, size_t);
int __wrap_munmap(void *p, size_t n) {
    unsigned int index = mapping_index(p);
    if (index == 256) return __real_munmap(p, n);
    CHECK(n == managed[index].n);
    zeroed(p, n);
    if (p == owned_mapping) { CHECK(n == owned_length); wiped_sources++; }
    if (active_eval != NULL && cancel_unmap) { cancel_unmap = 0; ctos_ssh_config_eval_cancel(active_eval); }
    if (expire_unmap) { expire_unmap = 0; clock_offset += 60001; }
    if (fail_unmap) { fail_unmap--; errno = EIO; return -1; }
    int result = __real_munmap(p, n);
    if (result == 0) { managed[index].p = NULL; managed[index].n = 0; }
    if (result == 0 && p == owned_mapping) { owned_mapping = NULL; owned_length = 0; }
    return result;
}
static void *cross_cancel(void *p) {
    struct ctos_ssh_config_eval *e = p;
    struct ctos_ssh_config_error error;
    const struct ctos_ssh_config_catalog *c = (void *)1;
    struct ctos_ssh_config_summary s;
    const Options *o = (void *)1;
    CHECK(ctos_ssh_config_eval_run(e, &error) == -1 && errno == EPERM);
    CHECK(ctos_ssh_config_eval_summary(e, &s) == -1 && errno == EPERM);
    CHECK(ctos_ssh_config_eval_options(e, &o) == -1 && errno == EPERM && o == NULL);
    CHECK(ctos_ssh_config_eval_catalog(e, &c) == -1 && errno == EPERM && c == NULL);
    CHECK(ctos_ssh_config_eval_close(e) == -1 && errno == EPERM);
    CHECK(ctos_ssh_config_eval_destroy(e) == -1 && errno == EPERM);
    ctos_ssh_config_eval_cancel(e); return NULL;
}
size_t __real_fread(void *, size_t, size_t, FILE *);
size_t __wrap_fread(void *p, size_t size, size_t n, FILE *f) {
    if (active_eval != NULL && reenter_read) {
        reenter_read = 0;
        struct ctos_ssh_config_error error;
        CHECK(ctos_ssh_config_eval_run(active_eval, &error) == -1 && errno == EBUSY);
        CHECK(ctos_ssh_config_eval_close(active_eval) == -1 && errno == EBUSY);
        CHECK(ctos_ssh_config_eval_destroy(active_eval) == -1 && errno == EBUSY);
        CHECK(ctos_ssh_config_eval_run(other_eval, &error) == -1 && errno == EBUSY);
    }
    if (active_eval != NULL && cancel_read) {
        cancel_read = 0;
        pthread_t thread; CHECK(pthread_create(&thread, NULL, cross_cancel, active_eval) == 0);
        CHECK(pthread_join(thread, NULL) == 0);
    }
    if (fail_read) { fail_read = 0; read_error = 1; errno = EIO; return 0; }
    return __real_fread(p, size, n, f);
}
int __real_ferror(FILE *);
int __wrap_ferror(FILE *f) { return read_error ? 1 : __real_ferror(f); }
static uint64_t later(void) {
    struct timespec now; CHECK(clock_gettime(CLOCK_BOOTTIME, &now) == 0);
    return (uint64_t)now.tv_sec * 1000 + (uint64_t)now.tv_nsec / 1000000 + 60000;
}
static struct ctos_ssh_slice slice(const char *s) { return (struct ctos_ssh_slice){(const unsigned char *)s, strlen(s)}; }
static struct ctos_ssh_config_file file(unsigned int type, const char *path, const char *body) {
    return (struct ctos_ssh_config_file){type, slice(path), body == NULL ? (struct ctos_ssh_slice){NULL, 0} : slice(body)};
}
static struct ctos_ssh_config_request request(void) {
    return (struct ctos_ssh_config_request){.alias = "alias", .local_user = "local-only",
        .local_hostname = "local.example", .local_uid = 1234};
}
static struct ctos_ssh_config_eval *open_files(const struct ctos_ssh_config_file *files,
    unsigned int count, unsigned int root, const struct ctos_ssh_config_request *r) {
    struct ctos_ssh_config_eval *e = NULL;
    capture_owned = 1;
    int result = r == NULL ? ctos_ssh_config_eval_open_catalog_detached(files, count, root, later(), &e) :
        ctos_ssh_config_eval_open_detached(files, count, root, later(), r, &e);
    CHECK(result == 0 && e != NULL && !capture_owned);
    return e;
}
static struct ctos_ssh_config_eval *open_body(const char *body, int catalog) {
    struct ctos_ssh_config_file f = file(CTOS_SSH_CONFIG, "candidate/root", body);
    struct ctos_ssh_config_request r = request();
    return open_files(&f, 1, 0, catalog ? NULL : &r);
}
static int run(struct ctos_ssh_config_eval *e, struct ctos_ssh_config_error *error) {
    unsigned int before = effects;
    int result = ctos_ssh_config_eval_run(e, error);
    CHECK(effects == before && capabilities == 0); return result;
}
static void finish(struct ctos_ssh_config_eval *e) {
    CHECK(ctos_ssh_config_eval_close(e) == 0);
    CHECK(ctos_ssh_config_eval_close(e) == 0);
    CHECK(ctos_ssh_config_eval_destroy(e) == 0);
}
static const struct ctos_ssh_config_catalog *catalog_ok(struct ctos_ssh_config_eval *e) {
    struct ctos_ssh_config_error error;
    const struct ctos_ssh_config_catalog *c = NULL;
    CHECK(run(e, &error) == 0 && error.reason == CTOS_CONFIG_OK);
    CHECK(ctos_ssh_config_eval_catalog(e, &c) == 0 && c != NULL); return c;
}
static void independent_inputs(void) {
    char path[] = "alternate/input", body[] = "Host original\n";
    struct ctos_ssh_config_file files[] = {
        file(CTOS_SSH_PRIVATE_KEY, "keys/private", NULL),
        file(CTOS_SSH_CONFIG, path, body),
        file(CTOS_SSH_CONFIG, "orphans/input", "Host orphan\n")
    };
    struct ctos_ssh_config_eval *first = open_files(files, 3, 1, NULL);
    memcpy(body, "Host modified\n", sizeof(body));
    struct ctos_ssh_config_eval *second = open_files(files, 3, 1, NULL);
    memset(path, 'x', sizeof(path) - 1); memset(body, '#', sizeof(body) - 1);
    memset(files, 0, sizeof(files));
    const struct ctos_ssh_config_catalog *a = catalog_ok(first), *b = catalog_ok(second);
    CHECK(a->alias_count == 1 && strcmp(a->entries[0].alias, "original") == 0);
    CHECK(b->alias_count == 1 && strcmp(b->entries[0].alias, "modified") == 0);
    CHECK(a->entries[0].object == 2 && b->entries[0].object == 2 && a->scanned_files == 1);
    finish(first); finish(second);
    CHECK(path[0] == 'x' && body[0] == '#');
    struct ctos_ssh_config_file orphan[] = {
        file(CTOS_SSH_CONFIG, "first", "Host primary\n"),
        file(CTOS_SSH_KNOWN_HOSTS, "trust", NULL),
        file(CTOS_SSH_CONFIG, "unreachable", "Host orphan\n")
    };
    struct ctos_ssh_config_eval *e = open_files(orphan, 3, 2, NULL);
    a = catalog_ok(e);
    CHECK(a->alias_count == 1 && a->entries[0].object == 3 && strcmp(a->entries[0].alias, "orphan") == 0);
    finish(e);
    char *heap_path = strdup("freed/config"), *heap_body = strdup("Host freed-input\n");
    CHECK(heap_path != NULL && heap_body != NULL);
    struct ctos_ssh_config_file f = file(CTOS_SSH_CONFIG, heap_path, heap_body);
    e = open_files(&f, 1, 0, NULL); free(heap_path); free(heap_body);
    a = catalog_ok(e); CHECK(strcmp(a->entries[0].alias, "freed-input") == 0); finish(e);
}
static void includes_and_options(void) {
    struct ctos_ssh_config_file files[] = {
        file(CTOS_SSH_PUBLIC_KEY, "keys/public", NULL),
        file(CTOS_SSH_CONFIG, "roots/unusual", "Host root\nInclude conf/*.conf\nInclude %h\nHost last\n"),
        file(CTOS_SSH_CONFIG, ".ssh/conf/z.conf", "Host zed\n"),
        file(CTOS_SSH_CONFIG, ".ssh/conf/a.conf", "Host alpha\nInclude nested\n"),
        file(CTOS_SSH_CONFIG, ".ssh/nested", "Host nested\nInclude ${ENV}\n"),
        file(CTOS_SSH_CONFIG, "orphan", "Host unvisited\n")
    };
    struct ctos_ssh_config_eval *e = open_files(files, 6, 1, NULL);
    const struct ctos_ssh_config_catalog *c = catalog_ok(e);
    CHECK(c->alias_count == 5 && c->scanned_files == 4 && c->deferred_count == 2);
    CHECK(strcmp(c->entries[0].alias, "root") == 0 && c->entries[0].object == 2);
    CHECK(strcmp(c->entries[1].alias, "alpha") == 0 && c->entries[1].object == 4);
    CHECK(strcmp(c->entries[2].alias, "nested") == 0 && c->entries[2].object == 5);
    CHECK(strcmp(c->entries[3].alias, "zed") == 0 && c->entries[3].object == 3);
    CHECK(c->deferred[0].object == 5 && c->deferred[0].line == 2);
    CHECK(c->deferred[1].object == 2 && c->deferred[1].line == 3);
    finish(e);
    files[1].body = slice("Include conf/*.conf\nHostName ACTUAL.EXAMPLE\nUser configured\nPort 2200\nIdentityFile keys/%r\nRemoteCommand echo %h\nLocalForward 3000 target:80\nRequestTTY force\n");
    files[2].body = slice("User late\n");
    files[3].body = slice("Match originalhost alias user override\n Port 2323\nMatch all\n");
    struct ctos_ssh_config_request r = request(); r.remote_user = "override"; r.port = 2424;
    e = open_files(files, 6, 1, &r);
    struct ctos_ssh_config_error error; struct ctos_ssh_config_summary s; const Options *o = NULL;
    CHECK(run(e, &error) == 0);
    CHECK(ctos_ssh_config_eval_summary(e, &s) == 0 && s.port == 2424 && strcmp(s.user, "override") == 0);
    CHECK(strcmp(s.hostname, "actual.example") == 0 && s.local_forwards == 1 && s.has_remote_command && s.has_raw_fields);
    CHECK(ctos_ssh_config_eval_options(e, &o) == 0 && o->request_tty == REQUEST_TTY_FORCE);
    CHECK(strcmp(o->remote_command, "echo %h") == 0 && strcmp(o->identity_files[0], "keys/%r") == 0);
    CHECK(o->local_forwards[0].listen_port == 3000 && strcmp(o->local_forwards[0].connect_host, "target") == 0);
    finish(e);
    files[1].body = slice("User remote\nInclude %r.conf\n");
    files[2].path = slice(".ssh/remote.conf"); files[2].body = slice("Port 2525\n");
    r = request(); e = open_files(files, 6, 1, &r);
    CHECK(run(e, &error) == 0 && ctos_ssh_config_eval_summary(e, &s) == 0 && s.port == 2525);
    finish(e);
    files[1].body = slice("Include conf/*.conf\n"); files[3].body = slice("# origin\nKexAlgorithms ,\n");
    e = open_files(files, 6, 1, &r);
    CHECK(run(e, &error) == -1 && error.object == 4 && error.line == 2 && error.reason == CTOS_CONFIG_DEFAULTS);
    finish(e);
}
static void reject_shape(struct ctos_ssh_config_file *files, unsigned int count, unsigned int root) {
    struct ctos_ssh_config_eval *e = (void *)1;
    CHECK(ctos_ssh_config_eval_open_catalog_detached(files, count, root, later(), &e) == -1 && e == NULL);
}
static void source_shapes(void) {
    struct ctos_ssh_config_file f[] = {file(CTOS_SSH_CONFIG, "root", "Host one\n"), file(CTOS_SSH_CONFIG, "child", "")};
    reject_shape(NULL, 1, 0); reject_shape(f, 0, 0); reject_shape(f, 129, 0); reject_shape(f, 2, 2);
    const char *paths[] = {"", "/absolute", "../escape", "a/../b", "a//b", "a/", "a\\b", "a%b", "a*b", "a\nb"};
    for (size_t i = 0; i < sizeof(paths) / sizeof(paths[0]); i++) { f[1].path = slice(paths[i]); reject_shape(f, 2, 0); }
    f[1].path = (struct ctos_ssh_slice){NULL, 3}; reject_shape(f, 2, 0);
    char long_path[514]; memset(long_path, 'x', sizeof(long_path)); long_path[513] = 0;
    f[1].path = slice(long_path); reject_shape(f, 2, 0);
    f[1].path = slice("root"); reject_shape(f, 2, 0);
    f[1].path = slice("root/child"); reject_shape(f, 2, 0);
    f[0].path = slice("child/deep"); f[1].path = slice("child"); reject_shape(f, 2, 0);
    f[0].path = slice("root");
    for (unsigned int type = CTOS_SSH_PRIVATE_KEY; type <= CTOS_SSH_KNOWN_HOSTS; type++) {
        f[1] = file(type, "metadata", "forbidden-secret"); reject_shape(f, 2, 0);
        f[1].body.length = 0; reject_shape(f, 2, 0);
        f[1].body.data = NULL; reject_shape(f, 2, 1);
        struct ctos_ssh_config_eval *e = open_files(f, 2, 0, NULL); finish(e);
    }
    f[1] = file(0, "child", NULL); reject_shape(f, 2, 0);
    f[1].type = 6; reject_shape(f, 2, 0);
    f[1] = file(CTOS_SSH_CONFIG, "child", ""); f[1].body = (struct ctos_ssh_slice){NULL, 1}; reject_shape(f, 2, 0);
    f[1].body = (struct ctos_ssh_slice){(const unsigned char *)"x", CTOS_SSH_VIEW_FILE_BYTES + 1}; reject_shape(f, 2, 0);
    struct ctos_ssh_config_eval *e = (void *)1;
    CHECK(ctos_ssh_config_eval_open_catalog_detached(f, 1, 0, 1, &e) == -1 && errno == ETIMEDOUT && e == NULL);
    fixed_boottime = 12345000;
    CHECK(ctos_ssh_config_eval_open_catalog_detached(f, 1, 0, fixed_boottime, &e) == -1 && errno == ETIMEDOUT && e == NULL);
    fixed_boottime = 0;
    CHECK(ctos_ssh_config_eval_open_catalog_detached(f, 1, 0, later() + 10000, &e) == -1 && errno == EINVAL && e == NULL);
    CHECK(ctos_ssh_config_eval_open_catalog_detached(f, 1, 0, later(), NULL) == -1 && errno == EINVAL);
    struct ctos_ssh_config_request r = request(); r.port = 65536;
    CHECK(ctos_ssh_config_eval_open_detached(f, 1, 0, later(), &r, &e) == -1 && errno == EINVAL && e == NULL);
    CHECK(ctos_ssh_config_eval_open_detached(f, 1, 0, later(), NULL, &e) == -1 && errno == EINVAL && e == NULL);
    /* This invalid pointer is never a capability: Request validation must run
     * first, before even the rejecting borrow-live probe may be called. */
    struct ctos_ssh_view_borrow *invalid = (void *)(uintptr_t)1;
    CHECK(ctos_ssh_config_eval_open(invalid, NULL, &e) == -1 && errno == EINVAL && e == NULL);
    CHECK(ctos_ssh_config_eval_open(invalid, &r, &e) == -1 && errno == EINVAL && e == NULL);
    CHECK(capabilities == 0);
}
static int collection_live(const void *context) {
    if (*(const int *)context) return 0;
    errno = ECANCELED; return -1;
}
static void collection_io(void) {
    struct ctos_ssh_file files[2] = {0};
    files[0].type = CTOS_SSH_CONFIG; files[0].path = slice(".ssh/config"); files[0].body = slice("Host io\n");
    files[0].declared_bytes = (unsigned int)files[0].body.length;
    files[1].type = CTOS_SSH_PRIVATE_KEY; files[1].path = slice(".ssh/private");
    struct ctos_ssh_io *io = calloc(1, sizeof(*io)); CHECK(io != NULL);
    for (unsigned int mode = 0; mode < 7; mode++) {
        int alive = 1;
        CHECK(ctos_ssh_io_init_source(io, files, 2, collection_live, &alive, fclose) == 0);
        if (mode == 0) {
            CHECK(ctos_ssh_io_open(io, "/ctos-vault/.ssh/missing", CTOS_SSH_PRIVATE_KEY) == NULL && errno == ENOENT);
        } else if (mode == 1) {
            CHECK(ctos_ssh_io_open(io, "/ctos-vault/.ssh/private", CTOS_SSH_PRIVATE_KEY) == NULL && errno == EACCES);
        } else if (mode == 2) {
            CHECK(ctos_ssh_io_open(io, "/ctos-vault/.ssh/private", CTOS_SSH_CONFIG) == NULL && errno == EACCES);
        } else if (mode == 3) {
            CHECK(ctos_ssh_io_open(io, "/ctos-vault/.ssh/config", 0) == NULL && errno == EINVAL);
        } else if (mode == 4) {
            FILE *stream = ctos_ssh_io_open(io, "/ctos-vault/.ssh/config", CTOS_SSH_CONFIG); CHECK(stream != NULL);
            char body[32] = {0}; CHECK(fread(body, 1, sizeof(body), stream) == files[0].body.length);
            CHECK(strcmp(body, "Host io\n") == 0); CHECK(ctos_ssh_io_close(io, stream) == 0);
        } else {
            alive = 0;
            if (mode == 5) {
                struct stat shape;
                CHECK(ctos_ssh_io_stat(io, "/ctos-vault/.ssh/config", &shape) == -1 && errno == ECANCELED);
            } else {
                char child[256];
                CHECK(ctos_ssh_io_child(io, "/ctos-vault/.ssh", 0, child) == -1 && errno == ECANCELED);
            }
        }
        (void)ctos_ssh_io_destroy(io); CHECK(io->held_bytes == 0);
    }
    free(io);
}
static void parser_rejections(void) {
    const char *bad[] = {"Include missing\n", "Include /etc/passwd\n", "Include ../escape\n", "Host '\n",
        "Match exec true\n", "Host ignored\nMatch exec true\n", "LocalCommand unsafe\n", "StrictHostKeyChecking no\n",
        "VerifyHostKeyDNS yes\n", "CanonicalizeHostname yes\n", "Port invalid\n"};
    struct ctos_ssh_config_error error;
    for (size_t i = 0; i < sizeof(bad) / sizeof(bad[0]); i++) {
        case_index = (unsigned int)i;
        struct ctos_ssh_config_eval *e = open_body(bad[i], 0);
        CHECK(run(e, &error) == -1 && error.reason != CTOS_CONFIG_OK && error.object == 1); finish(e);
    }
    struct ctos_ssh_config_file f[] = {file(CTOS_SSH_CONFIG, "roots/selected", "Include child\n"),
        file(CTOS_SSH_PRIVATE_KEY, ".ssh/child", NULL)};
    struct ctos_ssh_config_eval *e = open_files(f, 2, 0, NULL);
    CHECK(run(e, &error) == -1 && error.reason == CTOS_CONFIG_PATH); finish(e);
    f[1] = file(CTOS_SSH_CONFIG, ".ssh/child", "Include /ctos-vault/roots/selected\n");
    e = open_files(f, 2, 0, NULL);
    CHECK(run(e, &error) == -1 && error.error_number == ELOOP && error.object == 2); finish(e);
    f[1].body = (struct ctos_ssh_slice){(const unsigned char *)"Host one\0Host two\n", 18};
    e = open_files(f, 2, 0, NULL);
    CHECK(run(e, &error) == -1 && error.reason == CTOS_CONFIG_INPUT && error.object == 2); finish(e);
}
static void lifetime(void) {
    struct ctos_ssh_config_error error;
    for (unsigned int mode = 0; mode < 13; mode++) {
        case_index = mode;
        struct ctos_ssh_config_eval *e = open_body("Host one\n", 1);
        const struct ctos_ssh_config_catalog *c = (void *)1;
        active_eval = e;
        if (mode == 0) {
            pthread_t thread; CHECK(pthread_create(&thread, NULL, cross_cancel, e) == 0);
            CHECK(pthread_join(thread, NULL) == 0);
            CHECK(run(e, &error) == -1 && error.reason == CTOS_CONFIG_LIFETIME && error.error_number == ECANCELED);
        } else if (mode == 1) {
            clock_offset = 60001;
            CHECK(run(e, &error) == -1 && error.error_number == ETIMEDOUT);
        } else if (mode == 2) {
            cancel_read = 1;
            CHECK(run(e, &error) == -1 && error.reason == CTOS_CONFIG_LIFETIME && error.error_number == ECANCELED);
        } else if (mode == 3) {
            (void)catalog_ok(e); ctos_ssh_config_eval_cancel(e);
            CHECK(ctos_ssh_config_eval_catalog(e, &c) == -1 && errno == ECANCELED && c == NULL);
        } else if (mode == 4) {
            (void)catalog_ok(e); clock_offset = 60001;
            CHECK(ctos_ssh_config_eval_catalog(e, &c) == -1 && errno == ETIMEDOUT && c == NULL);
        } else if (mode == 5 || mode == 6) {
            fake_pid = mode == 5; fake_uid = mode == 6;
            CHECK(run(e, &error) == -1 && errno == EPERM);
            CHECK(ctos_ssh_config_eval_close(e) == -1 && errno == EPERM);
            fake_pid = fake_uid = 0; (void)catalog_ok(e);
        } else if (mode == 7) {
            other_eval = open_body("Host other\n", 1); reenter_read = 1;
            CHECK(run(e, &error) == 0 && !reenter_read); (void)catalog_ok(other_eval);
            finish(other_eval); other_eval = NULL;
        } else if (mode == 8 || mode == 9) {
            cancel_unmap = mode == 8; expire_unmap = mode == 9;
            CHECK(run(e, &error) == -1 && error.reason == CTOS_CONFIG_LIFETIME);
            CHECK(ctos_ssh_config_eval_catalog(e, &c) == -1 && c == NULL);
        } else if (mode == 10) {
            fail_read = 1; CHECK(run(e, &error) == -1 && error.reason == CTOS_CONFIG_IO); read_error = 0;
        } else if (mode == 11) {
            fail_unmap = 2; CHECK(run(e, &error) == -1 && error.reason == CTOS_CONFIG_IO);
            CHECK(ctos_ssh_config_eval_close(e) == -1);
            CHECK(ctos_ssh_config_eval_destroy(e) == -1 && errno == EBUSY);
        } else {
            (void)catalog_ok(e); fail_unlock = 1;
            CHECK(ctos_ssh_config_eval_close(e) == -1 && errno == EIO);
            CHECK(ctos_ssh_config_eval_destroy(e) == -1 && errno == EBUSY);
        }
        active_eval = NULL; finish(e); clock_offset = 0;
    }
    struct ctos_ssh_config_eval *e = open_body("HostName actual\nUser remote\n", 0);
    CHECK(run(e, &error) == 0); clock_offset = 60001;
    struct ctos_ssh_config_summary s; const Options *o = (void *)1;
    CHECK(ctos_ssh_config_eval_summary(e, &s) == -1 && errno == ETIMEDOUT); zeroed(&s, sizeof(s));
    CHECK(ctos_ssh_config_eval_options(e, &o) == -1 && errno == ETIMEDOUT && o == NULL);
    finish(e); clock_offset = 0;
    fixed_boottime = 12345000; e = open_body("Host exact-expiry\n", 1);
    fixed_boottime += 60000;
    CHECK(run(e, &error) == -1 && error.reason == CTOS_CONFIG_LIFETIME && error.error_number == ETIMEDOUT);
    finish(e); fixed_boottime = 0;
}
static void constructor_cleanup(void) {
    char body[] = "Host caller-retained\n";
    struct ctos_ssh_config_file f = file(CTOS_SSH_CONFIG, "root", body);
    for (unsigned int mode = 0; mode < 5; mode++) {
        case_index = mode;
        struct ctos_ssh_config_eval *e = (void *)1;
        capture_owned = 1;
        if (mode == 0 || mode == 2) fail_lock = 1;
        if (mode == 1 || mode == 3) fail_dump = 1;
        if (mode == 2 || mode == 3) fail_unmap = 1;
        if (mode == 4) expire_lock = 1;
        CHECK(ctos_ssh_config_eval_open_catalog_detached(&f, 1, 0, later(), &e) == -1);
        CHECK(strcmp(body, "Host caller-retained\n") == 0);
        capture_owned = 0;
        if (mode == 2 || mode == 3) {
            CHECK(e != NULL && owned_mapping != NULL); zeroed(owned_mapping, owned_length);
            struct ctos_ssh_config_error error;
            CHECK(ctos_ssh_config_eval_run(e, &error) == -1 && errno == EALREADY);
            CHECK(ctos_ssh_config_eval_destroy(e) == -1 && errno == EBUSY); finish(e);
        } else CHECK(e == NULL && owned_mapping == NULL);
        clock_offset = 0;
    }
    struct ctos_ssh_config_eval *e = open_files(&f, 1, 0, NULL);
    (void)catalog_ok(e); fail_unmap = 1;
    CHECK(ctos_ssh_config_eval_close(e) == -1 && owned_mapping != NULL);
    zeroed(owned_mapping, owned_length);
    CHECK(strcmp(body, "Host caller-retained\n") == 0);
    CHECK(ctos_ssh_config_eval_destroy(e) == -1 && errno == EBUSY); finish(e);
    CHECK(strcmp(body, "Host caller-retained\n") == 0);
}
static void capacities(void) {
    case_index = 100;
    struct ctos_ssh_config_file files[CTOS_SSH_VIEW_FILES];
    char paths[CTOS_SSH_VIEW_FILES][32];
    for (unsigned int i = 0; i < CTOS_SSH_VIEW_FILES; i++) {
        snprintf(paths[i], sizeof(paths[i]), "objects/config-%u", i);
        files[i] = file(CTOS_SSH_CONFIG, paths[i], "Host bounded\n");
    }
    struct ctos_ssh_config_eval *e = open_files(files, CTOS_SSH_VIEW_FILES, 127, NULL);
    const struct ctos_ssh_config_catalog *c = catalog_ok(e); CHECK(c->entries[0].object == 128); finish(e);
    char *body = malloc(CTOS_SSH_VIEW_FILE_BYTES); CHECK(body != NULL);
    memset(body, '#', CTOS_SSH_VIEW_FILE_BYTES);
    for (size_t i = 8191; i < CTOS_SSH_VIEW_FILE_BYTES; i += 8192) body[i] = '\n';
    for (unsigned int i = 0; i < 9; i++) files[i].body = (struct ctos_ssh_slice){(unsigned char *)body, CTOS_SSH_VIEW_FILE_BYTES};
    case_index = 101;
    reject_shape(files, 9, 0);
    CHECK(errno == E2BIG);
    /* The host has an 8MiB hard locked-memory cap. Exact 8MiB payload plus
     * owned paths cannot be locked here. Prove it passes shape checks
     * and reaches the protection step, with a deterministic lock failure;
     * never substitute a fake successful lock for this boundary. */
    case_index = 102; fail_lock = 1; capture_owned = 1; e = (void *)1;
    CHECK(ctos_ssh_config_eval_open_catalog_detached(files, 8, 7, later(), &e) == -1 && errno == EPERM);
    CHECK(e == NULL && fail_lock == 0 && !capture_owned && owned_mapping == NULL);
    case_index = 103;
    e = open_files(files, 6, 5, NULL); CHECK(catalog_ok(e)->scanned_files == 1); finish(e);
    CHECK(body[0] == '#' && body[8191] == '\n'); free(body);
    char path[513]; memset(path, 'x', 255); path[255] = '/'; memset(path + 256, 'y', 256); path[512] = 0;
    case_index = 104;
    files[0] = file(CTOS_SSH_CONFIG, path, "Host max-path\n");
    e = open_files(files, 1, 0, NULL); CHECK(catalog_ok(e)->alias_count == 1); finish(e);
}
static unsigned int fd_count(void) {
    unsigned int count = 0;
    for (int fd = 0; fd < 1024; fd++) if (fcntl(fd, F_GETFD) != -1) count++;
    return count;
}
int main(void) {
    unsigned int before = fd_count(); observe = 1;
    independent_inputs(); includes_and_options(); source_shapes(); collection_io(); parser_rejections();
    lifetime(); constructor_cleanup(); capacities();
    CHECK(fd_count() == before && owned_mapping == NULL && effects == 0 && capabilities == 0 && wiped_sources > 0);
    for (unsigned int i = 0; i < 256; i++) CHECK(managed[i].p == NULL);
    observe = 0;
    printf("SSH_CONFIG_DETACHED checks=%u effects=%u capabilities=%u wiped_sources=%u gate=pass\n", checks, effects, capabilities, wiped_sources);
    return 0;
}

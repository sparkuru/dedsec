#include "includes.h"
#include <sys/stat.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <netdb.h>
#include <pwd.h>
#include <dirent.h>
#include <ifaddrs.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
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
#include "ssh_config_handle.h"

#ifdef CTOS_CONFIG_STATIC_TEST
_Thread_local _Alignas(64) unsigned char ctos_static_test_tls_alignment;
#endif
static atomic_uint checks, effects;
static atomic_int observe;
#define CHECK(c) do { atomic_fetch_add(&checks, 1); if (!(c)) { fprintf(stderr, "config handle test failure line=%d errno=%d\n", __LINE__, errno); abort(); } } while (0)

static int deny(void) { if (atomic_load(&observe)) atomic_fetch_add(&effects, 1); errno = EACCES; return -1; }
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
    if (atomic_load(&observe)) atomic_fetch_add(&effects, 1);
}

static int fail_open, fail_close, fail_destroy, fail_release, fail_unmap;
static unsigned int borrow_calls, release_calls;
static _Thread_local int spoof_pid, spoof_uid;
pid_t __real_getpid(void);
uid_t __real_getuid(void);
pid_t __wrap_getpid(void) { return __real_getpid() + spoof_pid; }
uid_t __wrap_getuid(void) { return __real_getuid() + (uid_t)spoof_uid; }
int __real_ctos_ssh_view_handle_borrow(ctos_ssh_view_token, struct ctos_ssh_view_borrow **);
int __wrap_ctos_ssh_view_handle_borrow(ctos_ssh_view_token token, struct ctos_ssh_view_borrow **out) {
    borrow_calls++; return __real_ctos_ssh_view_handle_borrow(token, out);
}
int __real_ctos_ssh_view_borrow_release(struct ctos_ssh_view_borrow *);
int __wrap_ctos_ssh_view_borrow_release(struct ctos_ssh_view_borrow *b) {
    release_calls++;
    if (fail_release) { fail_release--; errno = EIO; return -1; }
    return __real_ctos_ssh_view_borrow_release(b);
}
static struct ctos_ssh_config_eval *last_eval;
int __real_ctos_ssh_config_eval_open(struct ctos_ssh_view_borrow *, const struct ctos_ssh_config_request *, struct ctos_ssh_config_eval **);
int __wrap_ctos_ssh_config_eval_open(struct ctos_ssh_view_borrow *b, const struct ctos_ssh_config_request *r,
    struct ctos_ssh_config_eval **out) {
    if (fail_open) { fail_open--; *out = NULL; errno = ENOMEM; return -1; }
    int ret = __real_ctos_ssh_config_eval_open(b, r, out);
    last_eval = ret == 0 ? *out : NULL;
    return ret;
}
int __real_ctos_ssh_config_eval_close(struct ctos_ssh_config_eval *);
int __wrap_ctos_ssh_config_eval_close(struct ctos_ssh_config_eval *e) {
    if (fail_close) { fail_close--; errno = EIO; return -1; }
    return __real_ctos_ssh_config_eval_close(e);
}
int __real_ctos_ssh_config_eval_destroy(struct ctos_ssh_config_eval *);
int __wrap_ctos_ssh_config_eval_destroy(struct ctos_ssh_config_eval *e) {
    if (fail_destroy) { fail_destroy--; errno = EIO; return -1; }
    return __real_ctos_ssh_config_eval_destroy(e);
}
int __real_munmap(void *, size_t);
int __wrap_munmap(void *p, size_t n) {
    if (fail_unmap) { fail_unmap--; errno = EIO; return -1; }
    return __real_munmap(p, n);
}

static pthread_mutex_t pause_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t pause_condition = PTHREAD_COND_INITIALIZER;
static int pause_read, read_entered, cancel_done, revoke_read, read_error;
static ctos_ssh_config_token paused_handle;
static ctos_ssh_view_token paused_view;
size_t __real_fread(void *, size_t, size_t, FILE *);
int __real_ferror(FILE *);
static void wait_flag(int *flag) {
    struct timespec until;
    CHECK(clock_gettime(CLOCK_REALTIME, &until) == 0); until.tv_sec += 5;
    while (!*flag) CHECK(pthread_cond_timedwait(&pause_condition, &pause_lock, &until) == 0);
}
size_t __wrap_fread(void *p, size_t size, size_t count, FILE *f) {
    if (revoke_read) { revoke_read = 0; CHECK(ctos_ssh_view_handle_destroy(paused_view) == 0); }
    if (pause_read) {
        pause_read = 0;
        int32_t diagnostic[5];
        CHECK(ctos_ssh_config_handle_run(paused_handle, diagnostic) == 0 && diagnostic[0] == 2 && diagnostic[4] == EBUSY);
        CHECK(ctos_ssh_config_handle_close(paused_handle) == -1 && errno == EBUSY);
        CHECK(ctos_ssh_config_handle_destroy(paused_handle) == -1 && errno == EBUSY);
        pthread_mutex_lock(&pause_lock);
        read_entered = 1; pthread_cond_broadcast(&pause_condition);
        wait_flag(&cancel_done);
        pthread_mutex_unlock(&pause_lock);
    }
    return __real_fread(p, size, count, f);
}
int __wrap_ferror(FILE *f) { return read_error ? 1 : __real_ferror(f); }

struct wire { unsigned char bytes[32768]; size_t n; };
static void bytes(struct wire *w, const void *p, size_t n) {
    CHECK(n <= sizeof(w->bytes) - w->n); memcpy(w->bytes + w->n, p, n); w->n += n;
}
static void u32(struct wire *w, uint32_t n) {
    unsigned char b[4] = {(unsigned char)(n >> 24), (unsigned char)(n >> 16), (unsigned char)(n >> 8), (unsigned char)n};
    bytes(w, b, sizeof(b));
}
static void u64(struct wire *w, uint64_t n) { u32(w, (uint32_t)(n >> 32)); u32(w, (uint32_t)n); }
static void text(struct wire *w, const char *s) { u32(w, (uint32_t)strlen(s)); bytes(w, s, strlen(s)); }
static uint64_t deadline(void) {
    struct timespec now; CHECK(clock_gettime(CLOCK_BOOTTIME, &now) == 0);
    return (uint64_t)now.tv_sec * 1000 + (uint64_t)now.tv_nsec / 1000000 + 60000;
}
static void view_file(struct wire *w, const char *id, const char *path, const char *body, const char *reference) {
    unsigned char kind = CTOS_SSH_CONFIG;
    bytes(w, &kind, 1); text(w, id); text(w, path); text(w, id);
    u32(w, (uint32_t)strlen(body)); u32(w, reference == NULL ? 0 : 1);
    if (reference != NULL) text(w, reference);
    text(w, body);
}
static ctos_ssh_view_token view_new(const char *body, const char *included) {
    struct wire w = {0};
    unsigned char uuid[16] = {1}, binding[32] = {2};
    u32(&w, 0x43535056U); u32(&w, 1); bytes(&w, uuid, sizeof(uuid));
    u64(&w, 1); u64(&w, 1); u64(&w, deadline());
    text(&w, "config-request"); text(&w, "config-connection"); text(&w, ".ssh/root");
    bytes(&w, binding, sizeof(binding)); u32(&w, included == NULL ? 1 : 2);
    view_file(&w, "config", ".ssh/root", body, included == NULL ? NULL : ".ssh/child");
    if (included != NULL) view_file(&w, "child", ".ssh/child", included, NULL);
    ctos_ssh_view_token v = 0;
    CHECK(ctos_ssh_view_handle_create(w.bytes, w.n, &v) == 0 && v != 0);
    return v;
}
static struct wire request_fields(const char *alias, const char *user, const char *fallback,
    const char *local_user, const char *host, const char *tag, const char *command, unsigned int port) {
    struct wire w = {0}; u32(&w, 0x43534551U); u32(&w, 1); u32(&w, port); u32(&w, 1234);
    text(&w, alias); text(&w, user); text(&w, fallback); text(&w, local_user); text(&w, host); text(&w, tag); text(&w, command);
    return w;
}
static struct wire request(void) { return request_fields("alias", "", "", "virtual-user", "local.example", "", "", 0); }
static ctos_ssh_config_token open_handle(ctos_ssh_view_token view, struct wire *w) {
    ctos_ssh_config_token h = 0;
    CHECK(ctos_ssh_config_handle_open(view, w->bytes, w->n, &h) == 0 && h != 0 && h <= INT64_MAX);
    return h;
}
static void run_ok(ctos_ssh_config_token h) {
    int32_t result[5]; unsigned int before = atomic_load(&effects);
    atomic_store(&observe, 1);
    CHECK(ctos_ssh_config_handle_run(h, result) == 0);
    atomic_store(&observe, 0);
    CHECK(memcmp(result, (int32_t[5]){0}, sizeof(result)) == 0);
    CHECK(atomic_load(&effects) == before);
}
static void finish(ctos_ssh_config_token h) {
    CHECK(ctos_ssh_config_handle_close(h) == 0);
    CHECK(ctos_ssh_config_handle_close(h) == 0);
    CHECK(ctos_ssh_config_handle_destroy(h) == 0);
}
static uint32_t at(const unsigned char *p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3];
}
static void summary_text(const unsigned char **p, const char *expected) {
    size_t n = strlen(expected); CHECK(at(*p) == n); *p += 4;
    CHECK(memcmp(*p, expected, n) == 0); *p += n;
}
static void good(void) {
    ctos_ssh_view_token v = view_new("Include child\nHost alias\n IdentityFile ~/.ssh/a\n ProxyJump jump@relay:2202\n LocalForward 1000 target:80\n RemoteCommand whoami\n RequestTTY force\n", "User managed\nPort 2255\nHostName TARGET.example\n");
    struct wire r = request(); ctos_ssh_config_token h = open_handle(v, &r);
    CHECK(ctos_ssh_config_handle_destroy(h) == -1 && errno == EBUSY);
    unsigned char summary[CTOS_SSH_CONFIG_SUMMARY_BYTES]; size_t n = 99;
    CHECK(ctos_ssh_config_handle_summary(h, summary, &n) == -1 && n == 0 && errno == EAGAIN);
    run_ok(h);
    CHECK(ctos_ssh_config_handle_summary(h, summary, &n) == 0 && n <= sizeof(summary));
    CHECK(at(summary) == 0x43534553U && at(summary + 4) == 1);
    CHECK(at(summary + 8) == 0 && at(summary + 12) == 2255 && at(summary + 16) == 1);
    CHECK(at(summary + 24) == 1 && at(summary + 28) == 0 && at(summary + 32) == 1 && at(summary + 36) == 1);
    CHECK((int32_t)at(summary + 40) == REQUEST_TTY_FORCE && at(summary + 48) == 1);
    const unsigned char *p = summary + 52;
    summary_text(&p, "alias"); summary_text(&p, "target.example"); summary_text(&p, "managed"); CHECK(p == summary + n);
    const Options *o = NULL;
    CHECK(ctos_ssh_config_eval_options(last_eval, &o) == 0 && o != NULL);
    CHECK(strcmp(o->jump_host, "relay") == 0 && o->jump_port == 2202 && strcmp(o->remote_command, "whoami") == 0);
    int32_t diagnostic[5]; CHECK(ctos_ssh_config_handle_run(h, diagnostic) == 0 && diagnostic[0] == 2 && diagnostic[1] == 0 && diagnostic[4] == EALREADY);
    finish(h);
    struct ctos_ssh_view_summary vs;
    CHECK(ctos_ssh_view_handle_summary(v, &vs) == 0 && vs.count == 2);
    struct ctos_ssh_view_borrow *another = NULL;
    CHECK(ctos_ssh_view_handle_borrow(v, &another) == 0 && ctos_ssh_view_borrow_live(another) == 0);
    CHECK(ctos_ssh_view_borrow_release(another) == 0);
    CHECK(ctos_ssh_config_handle_summary(h, summary, &n) == -1 && errno == ENOENT);
    CHECK(ctos_ssh_view_handle_destroy(v) == 0);
}
static void diagnostic_errors(void) {
    ctos_ssh_view_token v = view_new("Include child\n", "Match exec forbidden\n User nobody\n");
    struct wire r = request(); ctos_ssh_config_token h = open_handle(v, &r);
    int32_t d[5];
    CHECK(ctos_ssh_config_handle_run(h, d) == 0 && d[0] == 1 && d[1] == CTOS_CONFIG_COMMAND && d[2] == 2 && d[3] == 1 && d[4] != 0);
    finish(h); CHECK(ctos_ssh_view_handle_destroy(v) == 0);
    CHECK(ctos_ssh_config_handle_run(h, d) == 0 && d[0] == 2 && d[1] == 0 && d[2] == 0 && d[3] == 0 && d[4] == ENOENT);
    CHECK(ctos_ssh_config_handle_run(0, d) == 0 && d[0] == 2 && d[4] == ENOENT);
    CHECK(ctos_ssh_config_handle_run(UINT64_MAX, d) == 0 && d[0] == 2 && d[4] == ENOENT);
}
static void request_rejections(void) {
    ctos_ssh_view_token v = view_new("User okay\n", NULL);
    struct wire good_request = request(); unsigned int before = borrow_calls;
    for (size_t n = 0; n < good_request.n; n++) {
        ctos_ssh_config_token h = 99;
        CHECK(ctos_ssh_config_handle_open(v, good_request.bytes, n, &h) == -1 && h == 0);
    }
    struct wire bad = good_request; bad.bytes[0] ^= 1;
    ctos_ssh_config_token h = 99;
    CHECK(ctos_ssh_config_handle_open(v, bad.bytes, bad.n, &h) == -1 && h == 0);
    bad = good_request; bad.bytes[7] = 2;
    CHECK(ctos_ssh_config_handle_open(v, bad.bytes, bad.n, &h) == -1 && h == 0);
    bad = good_request; bad.bytes[8] = 1;
    CHECK(ctos_ssh_config_handle_open(v, bad.bytes, bad.n, &h) == -1 && h == 0);
    bad = good_request; bad.bytes[bad.n++] = 0;
    CHECK(ctos_ssh_config_handle_open(v, bad.bytes, bad.n, &h) == -1 && h == 0);
    const char *invalid[] = {"", "-alias", "alias;bad", "bad\n", "bad\177", "bad\302\200", "bad\377", "bad\300\200", "bad\355\240\200", "bad\364\220\200\200"};
    for (unsigned int i = 0; i < sizeof(invalid) / sizeof(invalid[0]); i++) {
        bad = request_fields(invalid[i], "", "", "virtual", "local", "", "", 0);
        CHECK(ctos_ssh_config_handle_open(v, bad.bytes, bad.n, &h) == -1 && h == 0);
    }
    bad = good_request; bad.bytes[20] = 0;
    CHECK(ctos_ssh_config_handle_open(v, bad.bytes, bad.n, &h) == -1 && h == 0);
    char large[8194]; memset(large, 'a', sizeof(large)); large[256] = 0;
    for (unsigned int field = 0; field < 6; field++) {
        const char *parts[] = {"alias", "", "", "virtual", "local", ""}; parts[field] = large;
        bad = request_fields(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5], "", 0);
        CHECK(ctos_ssh_config_handle_open(v, bad.bytes, bad.n, &h) == -1 && h == 0 && errno == E2BIG);
    }
    memset(large, 'a', sizeof(large)); large[8193] = 0;
    bad = request_fields("alias", "", "", "virtual", "local", "", large, 0);
    CHECK(ctos_ssh_config_handle_open(v, bad.bytes, bad.n, &h) == -1 && h == 0 && errno == E2BIG);
    CHECK(borrow_calls == before);
    h = open_handle(v, &good_request); run_ok(h); finish(h);
    CHECK(ctos_ssh_view_handle_destroy(v) == 0);
}
static void request_boundaries(void) {
    ctos_ssh_view_token v = view_new("# fallback\n", NULL);
    char name[256], command[8193]; memset(name, 'a', 255); name[255] = 0; memset(command, 'x', 8192); command[8192] = 0;
    struct wire r = request_fields(name, name, name, name, name, name, command, 65535);
    CHECK(r.n == CTOS_SSH_CONFIG_REQUEST_BYTES);
    ctos_ssh_config_token h = open_handle(v, &r); run_ok(h);
    unsigned char s[CTOS_SSH_CONFIG_SUMMARY_BYTES]; size_t n;
    CHECK(ctos_ssh_config_handle_summary(h, s, &n) == 0 && n == CTOS_SSH_CONFIG_SUMMARY_BYTES);
    const unsigned char *p = s + 52; summary_text(&p, name); summary_text(&p, name); summary_text(&p, name);
    CHECK(at(s + 12) == 65535); finish(h);
    r = request_fields("alias", "", "fallback", "virtual", "local", "\360\237\230\200", "\360\237\230\200", 0);
    h = open_handle(v, &r); run_ok(h);
    CHECK(ctos_ssh_config_handle_summary(h, s, &n) == 0 && at(s + 8) == 1);
    p = s + 52; summary_text(&p, "alias"); summary_text(&p, "alias"); summary_text(&p, "");
    finish(h); CHECK(ctos_ssh_view_handle_destroy(v) == 0);
}
static void failed_open_cleanup(void) {
    ctos_ssh_view_token v = view_new("User okay\n", NULL);
    struct wire r = request(); fail_open = 1;
    unsigned int before = release_calls;
    ctos_ssh_config_token h = open_handle(v, &r); int32_t d[5];
    CHECK(release_calls == before);
    CHECK(ctos_ssh_config_handle_run(h, d) == 0 && d[0] == 2 && d[1] == 0 && d[4] == ENOMEM);
    CHECK(ctos_ssh_config_handle_destroy(h) == -1 && errno == EBUSY);
    CHECK(ctos_ssh_view_handle_destroy(v) == 0);
    fail_release = 1;
    CHECK(ctos_ssh_config_handle_close(h) == -1 && errno == EIO);
    CHECK(ctos_ssh_config_handle_destroy(h) == -1 && errno == EBUSY);
    CHECK(release_calls == before + 1);
    CHECK(ctos_ssh_config_handle_close(h) == 0 && release_calls == before + 2);
    CHECK(ctos_ssh_config_handle_destroy(h) == 0);
    struct ctos_ssh_view_summary vs; CHECK(ctos_ssh_view_handle_summary(v, &vs) == -1);
    v = view_new("User again\n", NULL); fail_open = 1; h = open_handle(v, &r);
    CHECK(ctos_ssh_config_handle_cancel(h) == 0);
    CHECK(ctos_ssh_config_handle_run(h, d) == 0 && d[0] == 2 && d[4] == ECANCELED);
    finish(h); CHECK(ctos_ssh_view_handle_destroy(v) == 0);
}
static void cleanup_retries(void) {
    ctos_ssh_view_token v = view_new("User okay\n", NULL); struct wire r = request();
    ctos_ssh_config_token h = open_handle(v, &r); run_ok(h);
    fail_close = 1; CHECK(ctos_ssh_config_handle_close(h) == -1 && errno == EIO);
    CHECK(ctos_ssh_config_handle_destroy(h) == -1 && errno == EBUSY);
    CHECK(ctos_ssh_config_handle_close(h) == 0);
    fail_destroy = 1; CHECK(ctos_ssh_config_handle_destroy(h) == -1 && errno == EIO);
    CHECK(ctos_ssh_config_handle_close(h) == 0); CHECK(ctos_ssh_config_handle_destroy(h) == 0);
    h = open_handle(v, &r); fail_unmap = 1; int32_t d[5];
    CHECK(ctos_ssh_config_handle_run(h, d) == 0 && d[0] == 1 && d[1] == CTOS_CONFIG_IO);
    fail_unmap = 1; CHECK(ctos_ssh_config_handle_close(h) == -1 && errno == EIO);
    CHECK(ctos_ssh_config_handle_destroy(h) == -1 && errno == EBUSY);
    finish(h); CHECK(ctos_ssh_view_handle_destroy(v) == 0);
}
static void *wrong_thread(void *p) {
    ctos_ssh_config_token h = *(ctos_ssh_config_token *)p;
    int32_t d[5]; unsigned char s[CTOS_SSH_CONFIG_SUMMARY_BYTES]; size_t n;
    CHECK(ctos_ssh_config_handle_run(h, d) == 0 && d[0] == 2 && d[1] == 0 && d[4] == EPERM);
    CHECK(ctos_ssh_config_handle_summary(h, s, &n) == -1 && errno == EPERM);
    CHECK(ctos_ssh_config_handle_close(h) == -1 && errno == EPERM);
    CHECK(ctos_ssh_config_handle_destroy(h) == -1 && errno == EPERM);
    return NULL;
}
static void ownership(void) {
    ctos_ssh_view_token v = view_new("User okay\n", NULL); struct wire r = request();
    ctos_ssh_config_token h = open_handle(v, &r); pthread_t t;
    CHECK(pthread_create(&t, NULL, wrong_thread, &h) == 0); CHECK(pthread_join(t, NULL) == 0);
    for (unsigned int i = 0; i < 2; i++) {
        spoof_pid = i == 0; spoof_uid = i == 1;
        int32_t d[5]; CHECK(ctos_ssh_config_handle_run(h, d) == 0 && d[0] == 2 && d[4] == EPERM);
        CHECK(ctos_ssh_config_handle_cancel(h) == -1 && errno == EPERM);
        CHECK(ctos_ssh_config_handle_close(h) == -1 && errno == EPERM);
        CHECK(ctos_ssh_config_handle_destroy(h) == -1 && errno == EPERM);
        spoof_pid = 0; spoof_uid = 0;
    }
    run_ok(h); finish(h); CHECK(ctos_ssh_view_handle_destroy(v) == 0);
}
static void *canceller(void *unused) {
    (void)unused; pthread_mutex_lock(&pause_lock); wait_flag(&read_entered); pthread_mutex_unlock(&pause_lock);
    CHECK(ctos_ssh_config_handle_cancel(paused_handle) == 0);
    pthread_mutex_lock(&pause_lock); cancel_done = 1; pthread_cond_broadcast(&pause_condition); pthread_mutex_unlock(&pause_lock);
    return NULL;
}
static void cancel_and_revoke(void) {
    struct wire r = request(); int32_t d[5]; unsigned char s[CTOS_SSH_CONFIG_SUMMARY_BYTES]; size_t n;
    ctos_ssh_view_token v = view_new("User okay\n", NULL); ctos_ssh_config_token h = open_handle(v, &r);
    paused_handle = h; pause_read = 1; read_entered = cancel_done = 0;
    pthread_t t; CHECK(pthread_create(&t, NULL, canceller, NULL) == 0);
    CHECK(ctos_ssh_config_handle_run(h, d) == 0 && d[0] == 1 && d[1] == CTOS_CONFIG_LIFETIME && d[4] == ECANCELED);
    CHECK(pthread_join(t, NULL) == 0);
    CHECK(ctos_ssh_config_handle_summary(h, s, &n) == -1 && n == 0 && errno == ECANCELED);
    finish(h);
    h = open_handle(v, &r); run_ok(h);
    CHECK(ctos_ssh_config_handle_cancel(h) == 0);
    CHECK(ctos_ssh_config_handle_summary_live(h) == -1 && errno == ECANCELED); finish(h);
    h = open_handle(v, &r); paused_view = v; revoke_read = 1;
    CHECK(ctos_ssh_config_handle_run(h, d) == 0 && d[0] == 1 && d[1] == CTOS_CONFIG_LIFETIME && d[4] == EACCES);
    finish(h);
    CHECK(ctos_ssh_config_handle_open(v, r.bytes, r.n, &h) == -1 && h == 0 && errno == EACCES);
    v = view_new("User okay\n", NULL); h = open_handle(v, &r); run_ok(h);
    CHECK(ctos_ssh_view_handle_destroy(v) == 0);
    CHECK(ctos_ssh_config_handle_summary_live(h) == -1 && errno == EACCES);
    CHECK(ctos_ssh_config_handle_summary(h, s, &n) == -1 && n == 0);
    finish(h);
}
static void capacity(void) {
    ctos_ssh_view_token v = view_new("User okay\n", NULL); struct wire r = request();
    ctos_ssh_config_token h[CTOS_SSH_CONFIG_HANDLE_SLOTS], overflow;
    for (unsigned int i = 0; i < CTOS_SSH_CONFIG_HANDLE_SLOTS; i++) {
        h[i] = open_handle(v, &r); if (i != 0) CHECK(h[i] > h[i - 1]);
    }
    CHECK(ctos_ssh_config_handle_open(v, r.bytes, r.n, &overflow) == -1 && overflow == 0 && errno == EBUSY);
    ctos_ssh_config_token stale = h[0]; finish(h[0]); h[0] = open_handle(v, &r);
    CHECK(h[0] > h[CTOS_SSH_CONFIG_HANDLE_SLOTS - 1]);
    CHECK(ctos_ssh_config_handle_cancel(stale) == -1 && errno == ENOENT);
    for (unsigned int i = 0; i < CTOS_SSH_CONFIG_HANDLE_SLOTS; i++) finish(h[i]);
    CHECK(ctos_ssh_view_handle_destroy(v) == 0);
}
static void expire_view(ctos_ssh_view_token v) {
    struct ctos_ssh_view_borrow *borrow = NULL;
    CHECK(ctos_ssh_view_handle_borrow(v, &borrow) == 0);
    const struct ctos_ssh_view *view = ctos_ssh_view_borrow_view(borrow);
    CHECK(view != NULL);
    /* Test-only deadline fault; the production handle API exports no view. */
    ((struct ctos_ssh_view *)view)->deadline = 0;
    CHECK(ctos_ssh_view_borrow_release(borrow) == 0);
}
static void deadline_checks(void) {
    struct wire r = request(); int32_t d[5];
    ctos_ssh_view_token v = view_new("User okay\n", NULL);
    ctos_ssh_config_token h = open_handle(v, &r); expire_view(v);
    CHECK(ctos_ssh_config_handle_run(h, d) == 0 && d[0] == 1 && d[1] == CTOS_CONFIG_LIFETIME && d[4] == ETIMEDOUT);
    finish(h); CHECK(ctos_ssh_view_handle_destroy(v) == 0);
    v = view_new("User okay\n", NULL); h = open_handle(v, &r); run_ok(h); expire_view(v);
    CHECK(ctos_ssh_config_handle_summary_live(h) == -1 && errno == ETIMEDOUT);
    finish(h); CHECK(ctos_ssh_view_handle_destroy(v) == 0);
}
static unsigned int descriptor_count(void) {
    unsigned int n = 0;
    for (int fd = 0; fd < 1024; fd++) if (fcntl(fd, F_GETFD) >= 0) n++;
    return n;
}
static void exhaustion(void) {
    ctos_ssh_view_token v = view_new("User okay\n", NULL); struct wire r = request();
    CHECK(ctos_ssh_config_handle_test_seed_next(INT64_MAX - 1) == 0);
    ctos_ssh_config_token h = open_handle(v, &r), overflow = 1; CHECK(h == INT64_MAX);
    CHECK(ctos_ssh_config_handle_open(v, r.bytes, r.n, &overflow) == -1 && overflow == 0 && errno == EBUSY);
    finish(h);
    CHECK(ctos_ssh_config_handle_open(v, r.bytes, r.n, &overflow) == -1 && overflow == 0 && errno == EBUSY);
    CHECK(ctos_ssh_view_handle_destroy(v) == 0);
}
int main(void) {
    unsigned int before = descriptor_count();
    good(); diagnostic_errors(); request_rejections(); request_boundaries(); failed_open_cleanup(); cleanup_retries(); ownership(); cancel_and_revoke(); deadline_checks(); capacity();
    for (unsigned int i = 0; i < 40; i++) good();
    exhaustion(); CHECK(descriptor_count() == before); CHECK(atomic_load(&effects) == 0);
    printf("SSH_CONFIG_HANDLE_TEST checks=%u effects=%u gate=pass\n", atomic_load(&checks), atomic_load(&effects));
    return 0;
}

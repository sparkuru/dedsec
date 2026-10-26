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
#include "ssh_config_handle.h"

#ifdef CTOS_CONFIG_STATIC_TEST
_Thread_local _Alignas(64) unsigned char ctos_static_test_tls_alignment;
#endif
static _Atomic unsigned int checks, effects;
static size_t case_index;
static int fail_read, read_error, fail_catalog_open, fail_release;
static ctos_ssh_config_token cancel_handle;
static struct ctos_ssh_config_eval *cancel_eval;
#define CHECK(c) do { checks++; if (!(c)) { fprintf(stderr, "config test failure line=%d case=%zu errno=%d\n", __LINE__, case_index, errno); abort(); } } while (0)
static void *cross_cancel(void *unused) {
    (void)unused;
    if (cancel_handle != 0) CHECK(ctos_ssh_config_handle_cancel(cancel_handle) == 0);
    else ctos_ssh_config_eval_cancel(cancel_eval);
    return NULL;
}
static int observe;
static struct ctos_ssh_config_eval *intervene_eval;
static struct ctos_ssh_view *intervene_view;
static int fail_unmap, do_cancel, do_revoke, do_reenter;
static unsigned int reentries;
static struct ctos_ssh_config_eval *other_eval;
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
size_t __real_fread(void *, size_t, size_t, FILE *);
int __real_ferror(FILE *);
size_t __wrap_fread(void *p, size_t size, size_t n, FILE *f) {
    if (observe && (cancel_eval != NULL || cancel_handle != 0)) {
        pthread_t t; CHECK(pthread_create(&t, NULL, cross_cancel, NULL) == 0);
        CHECK(pthread_join(t, NULL) == 0); cancel_eval = NULL; cancel_handle = 0;
    }
    if (observe && fail_read) { fail_read = 0; read_error = 1; errno = EIO; return 0; }
    return __real_fread(p, size, n, f);
}
int __wrap_ferror(FILE *f) { return read_error ? 1 : __real_ferror(f); }
void __wrap_sshlog(const char *file, const char *function, int line, int showfunc,
    LogLevel level, const char *suffix, const char *format, ...) {
    (void)file; (void)function; (void)line; (void)showfunc; (void)level; (void)suffix; (void)format;
    if (observe) effects++;
}
int __real_munmap(void *, size_t);
int __wrap_munmap(void *p, size_t n) {
    if (observe && intervene_eval != NULL) {
        if (do_reenter) {
            do_reenter = 0;
            struct ctos_ssh_config_error error;
            CHECK(ctos_ssh_config_eval_run(intervene_eval, &error) == -1);
            CHECK(errno == EBUSY || errno == EALREADY);
            CHECK(ctos_ssh_config_eval_close(intervene_eval) == -1 && errno == EBUSY);
            if (other_eval != NULL) {
                CHECK(ctos_ssh_config_eval_run(other_eval, &error) == -1 && errno == EBUSY);
            }
            reentries++;
        }
        if (do_cancel) { do_cancel = 0; ctos_ssh_config_eval_cancel(intervene_eval); }
        if (do_revoke) { do_revoke = 0; intervene_view->valid = 0; }
    }
    if (fail_unmap > 0) { fail_unmap--; errno = EIO; return -1; }
    return __real_munmap(p, n);
}
static uint64_t later(void) {
    struct timespec now;
    CHECK(clock_gettime(CLOCK_BOOTTIME, &now) == 0);
    return (uint64_t)now.tv_sec * 1000 + (uint64_t)now.tv_nsec / 1000000 + 60000;
}
static struct ctos_ssh_slice slice(const char *s) {
    return (struct ctos_ssh_slice){(const unsigned char *)s, strlen(s)};
}
static struct ctos_ssh_view *view_new(const char *body) {
    struct ctos_ssh_view *v = calloc(1, sizeof(*v));
    CHECK(v != NULL);
    v->fd = -1; v->valid = 1; v->deadline = later(); v->root = slice(".ssh/config"); v->count = 1;
    v->files[0].type = CTOS_SSH_CONFIG; v->files[0].path = v->root;
    v->files[0].body = slice(body); v->files[0].declared_bytes = (unsigned int)strlen(body);
    return v;
}
static void add_file(struct ctos_ssh_view *v, unsigned int type, const char *path, const char *body) {
    CHECK(v->count < CTOS_SSH_VIEW_FILES);
    struct ctos_ssh_file *f = &v->files[v->count++];
    f->type = type; f->path = slice(path); f->body = slice(body); f->declared_bytes = (unsigned int)strlen(body);
}
static struct ctos_ssh_config_request request(void) {
    return (struct ctos_ssh_config_request){.alias = "alias", .local_user = "local-only",
        .local_hostname = "local.example", .local_uid = 1234};
}
static struct ctos_ssh_config_eval *open_eval(struct ctos_ssh_view *v, struct ctos_ssh_config_request *r) {
    struct ctos_ssh_view_borrow *b = NULL;
    struct ctos_ssh_config_eval *e = NULL;
    CHECK(ctos_ssh_view_borrow_owned_view(v, &b) == 0);
    CHECK(ctos_ssh_config_eval_open(b, r, &e) == 0 && e != NULL);
    return e;
}
static int run_eval(struct ctos_ssh_config_eval *e, struct ctos_ssh_config_error *error) {
    unsigned int before = effects;
    observe = 1;
    int result = ctos_ssh_config_eval_run(e, error);
    observe = 0;
    CHECK(effects == before);
    return result;
}
static void finish(struct ctos_ssh_config_eval *e, struct ctos_ssh_view *v) {
    CHECK(ctos_ssh_config_eval_close(e) == 0);
    CHECK(ctos_ssh_config_eval_close(e) == 0);
    CHECK(ctos_ssh_config_eval_destroy(e) == 0);
    free(v);
}

int __real_ctos_ssh_config_eval_open_catalog(struct ctos_ssh_view_borrow *, struct ctos_ssh_config_eval **);
int __wrap_ctos_ssh_config_eval_open_catalog(struct ctos_ssh_view_borrow *b, struct ctos_ssh_config_eval **out) {
    if (fail_catalog_open) { fail_catalog_open = 0; *out = NULL; errno = ENOMEM; return -1; }
    return __real_ctos_ssh_config_eval_open_catalog(b, out);
}
int __real_ctos_ssh_view_borrow_release(struct ctos_ssh_view_borrow *);
int __wrap_ctos_ssh_view_borrow_release(struct ctos_ssh_view_borrow *b) {
    if (fail_release) { fail_release = 0; errno = EIO; return -1; }
    return __real_ctos_ssh_view_borrow_release(b);
}
static struct ctos_ssh_config_eval *open_catalog(struct ctos_ssh_view *v) {
    struct ctos_ssh_view_borrow *b = NULL;
    struct ctos_ssh_config_eval *e = NULL;
    CHECK(ctos_ssh_view_borrow_owned_view(v, &b) == 0);
    CHECK(ctos_ssh_config_eval_open_catalog(b, &e) == 0 && e != NULL);
    return e;
}
static const struct ctos_ssh_config_catalog *catalog_ok(struct ctos_ssh_config_eval *e) {
    struct ctos_ssh_config_error error;
    const struct ctos_ssh_config_catalog *c = NULL;
    CHECK(run_eval(e, &error) == 0 && error.reason == CTOS_CONFIG_OK);
    CHECK(ctos_ssh_config_eval_catalog(e, &c) == 0 && c != NULL);
    return c;
}
static void entry(const struct ctos_ssh_config_catalog *c, unsigned int index,
    const char *alias, unsigned int object, unsigned int line) {
    CHECK(index < c->alias_count);
    CHECK(strcmp(c->entries[index].alias, alias) == 0);
    CHECK(c->entries[index].object == object && c->entries[index].line == line);
}
static void expect_bad_view(struct ctos_ssh_view *v, unsigned int reason, unsigned int object, unsigned int line) {
    struct ctos_ssh_config_eval *e = open_catalog(v);
    struct ctos_ssh_config_error error;
    const struct ctos_ssh_config_catalog *c = (void *)1;
    CHECK(run_eval(e, &error) == -1);
    CHECK(error.reason == reason && error.object == object && error.line == line);
    CHECK(ctos_ssh_config_eval_catalog(e, &c) == -1 && c == NULL);
    finish(e, v);
}
static void declarations(void) {
    struct ctos_ssh_view *v = view_new("Host !one one two * *.example a? [rack] ONE one 'quoted' hash#alias escaped\\ space # ignored\nMatch exec arbitrary\nHost after\nInclude conf/*\nHost root-last\n");
    add_file(v, CTOS_SSH_CONFIG, ".ssh/conf/b", "Host child-b one\nInclude ../escape%h child-dynamic%r\n");
    add_file(v, CTOS_SSH_CONFIG, ".ssh/conf/a", "Host child-a\nMatch localnetwork impossible\nInclude nested\n");
    add_file(v, CTOS_SSH_CONFIG, ".ssh/nested", "Host nested\nInclude ${ENV} %% %r $raw\n");
    add_file(v, CTOS_SSH_CONFIG, ".ssh/orphan", "Host orphan\nUnknownKeyword arbitrary\n");
    struct ctos_ssh_config_eval *e = open_catalog(v);
    const struct ctos_ssh_config_catalog *c = catalog_ok(e);
    CHECK(c->alias_count == 11 && c->scanned_files == 4 && c->has_match && c->omitted_selectors);
    entry(c, 0, "one", 1, 1); entry(c, 1, "two", 1, 1); entry(c, 2, "[rack]", 1, 1);
    entry(c, 3, "ONE", 1, 1); entry(c, 4, "quoted", 1, 1); entry(c, 5, "hash#alias", 1, 1);
    entry(c, 6, "after", 1, 3); entry(c, 7, "child-a", 3, 1); entry(c, 8, "nested", 4, 1);
    entry(c, 9, "child-b", 2, 1); entry(c, 10, "root-last", 1, 5);
    /* Count includes only declaration rows; neither Match arguments nor dynamic paths are aliases. */
    CHECK(c->deferred_count == 2 && c->deferred[0].object == 4 && c->deferred[0].line == 2);
    CHECK(c->deferred[1].object == 2 && c->deferred[1].line == 2);
    struct ctos_ssh_config_summary summary;
    const Options *options = (void *)1;
    CHECK(ctos_ssh_config_eval_summary(e, &summary) == -1 && errno == EINVAL);
    CHECK(ctos_ssh_config_eval_options(e, &options) == -1 && errno == EINVAL && options == NULL);
    finish(e, v);
}
static void static_includes(void) {
    struct ctos_ssh_view *v = view_new("Host !never *\nInclude child\nMatch user missing\nInclude child ~/.ssh/child /ctos-vault/.ssh/child\nHost last\n");
    add_file(v, CTOS_SSH_CONFIG, ".ssh/child", "Host child\nInclude %h %r ${ENV}\n");
    struct ctos_ssh_config_eval *e = open_catalog(v);
    const struct ctos_ssh_config_catalog *c = catalog_ok(e);
    CHECK(c->alias_count == 2 && c->scanned_files == 2 && c->deferred_count == 1);
    entry(c, 0, "child", 2, 1); entry(c, 1, "last", 1, 5);
    finish(e, v);
    v = view_new("Include missing\n"); expect_bad_view(v, CTOS_CONFIG_PATH, 1, 1);
    v = view_new("Include ../escape\n"); expect_bad_view(v, CTOS_CONFIG_PATH, 1, 1);
    v = view_new("Include ~someone/child\n"); expect_bad_view(v, CTOS_CONFIG_PATH, 1, 1);
    v = view_new("Include child\n"); add_file(v, CTOS_SSH_KNOWN_HOSTS, ".ssh/child", "Host nope\n");
    expect_bad_view(v, CTOS_CONFIG_PATH, 1, 1);
    v = view_new("Include child\n"); add_file(v, CTOS_SSH_CONFIG, ".ssh/child", "Include config\n");
    expect_bad_view(v, CTOS_CONFIG_PATH, 2, 1);
}
static void lexical_only(void) {
    const char *bodies[] = {"Host alias\nPort invalid\n", "Host alias\nMatch invalid predicate arguments\n",
        "Match exec forbidden\nHost alias\n", "LocalCommand forbidden\nHost alias\n",
        "ProxyCommand forbidden\nHost alias\n", "Match user\nHost alias\n"};
    for (size_t i = 0; i < sizeof(bodies) / sizeof(bodies[0]); i++) {
        struct ctos_ssh_view *v = view_new(bodies[i]);
        struct ctos_ssh_config_eval *e = open_catalog(v);
        CHECK(catalog_ok(e)->alias_count == 1); finish(e, v);
        v = view_new(bodies[i]); struct ctos_ssh_config_request r = request(); e = open_eval(v, &r);
        struct ctos_ssh_config_error error; CHECK(run_eval(e, &error) == -1 && error.reason != CTOS_CONFIG_OK);
        finish(e, v);
    }
    expect_bad_view(view_new("IgnoreUnknown UnknownKeyword\nUnknownKeyword value\n"), CTOS_CONFIG_INPUT, 1, 2);
    expect_bad_view(view_new("Host 'unterminated\n"), CTOS_CONFIG_INPUT, 1, 1);
    expect_bad_view(view_new("Host a\xff\n"), CTOS_CONFIG_INPUT, 1, 1);
    struct ctos_ssh_view *v = view_new("Host 'bad space' -bad 'a;b' a\302\200b good é [literal]\n");
    struct ctos_ssh_config_eval *e = open_catalog(v); const struct ctos_ssh_config_catalog *c = catalog_ok(e);
    CHECK(c->omitted_selectors && c->alias_count == 3); entry(c, 0, "good", 1, 1);
    entry(c, 1, "é", 1, 1); entry(c, 2, "[literal]", 1, 1); finish(e, v);
    v = view_new("# only comment\n"); e = open_catalog(v); c = catalog_ok(e);
    CHECK(c->alias_count == 0 && c->scanned_files == 1 && !c->has_match && !c->omitted_selectors);
    finish(e, v);
    v = view_new("User remote\n"); struct ctos_ssh_config_request r = request(); e = open_eval(v, &r);
    struct ctos_ssh_config_error error; CHECK(run_eval(e, &error) == 0);
    c = (void *)1; CHECK(ctos_ssh_config_eval_catalog(e, &c) == -1 && errno == EINVAL && c == NULL);
    finish(e, v);
}
static void boundaries(void) {
    char body[32768]; size_t used = 0;
    for (unsigned int i = 0; i < 257; i++) {
        used += (size_t)snprintf(body + used, sizeof(body) - used, "Host item%03u\n", i);
        if (i != 255 && i != 256) continue;
        struct ctos_ssh_view *v = view_new(body);
        if (i == 256) { expect_bad_view(v, CTOS_CONFIG_LIMIT, 1, 257); continue; }
        struct ctos_ssh_config_eval *e = open_catalog(v); const struct ctos_ssh_config_catalog *c = catalog_ok(e);
        CHECK(c->alias_count == 256); entry(c, 255, "item255", 1, 256); finish(e, v);
    }
    memset(body, 'x', 256); body[256] = 0;
    char row[300]; snprintf(row, sizeof(row), "Host %.*s\n", 255, body);
    struct ctos_ssh_view *v = view_new(row); struct ctos_ssh_config_eval *e = open_catalog(v);
    CHECK(strlen(catalog_ok(e)->entries[0].alias) == 255); finish(e, v);
    snprintf(row, sizeof(row), "Host %s\n", body); expect_bad_view(view_new(row), CTOS_CONFIG_LIMIT, 1, 1);
    used = 0; body[0] = 0;
    for (unsigned int i = 0; i < 257; i++) {
        used += (size_t)snprintf(body + used, sizeof(body) - used, "Include %%h\n");
        if (i != 255 && i != 256) continue;
        v = view_new(body);
        if (i == 256) { expect_bad_view(v, CTOS_CONFIG_LIMIT, 1, 257); continue; }
        e = open_catalog(v); CHECK(catalog_ok(e)->deferred_count == 256); finish(e, v);
    }
    char paths[9][32], includes[9][48];
    for (unsigned int i = 0; i < 9; i++) {
        snprintf(paths[i], sizeof(paths[i]), ".ssh/depth%u", i);
        snprintf(includes[i], sizeof(includes[i]), "Include depth%u\n", i + 1);
    }
    for (unsigned int depth = 8; depth <= 9; depth++) {
        v = view_new("Include depth0\n");
        for (unsigned int i = 0; i < depth; i++) add_file(v, CTOS_SSH_CONFIG, paths[i], i + 1 == depth ? "Host leaf\n" : includes[i]);
        if (depth == 9) { expect_bad_view(v, CTOS_CONFIG_LIMIT, 9, 1); continue; }
        e = open_catalog(v); CHECK(catalog_ok(e)->scanned_files == 9); finish(e, v);
    }
    char *rows = malloc(CTOS_SSH_CONFIG_LINES * 2 + 3); CHECK(rows != NULL);
    memset(rows, '#', CTOS_SSH_CONFIG_LINES * 2 + 2);
    for (unsigned int i = 0; i <= CTOS_SSH_CONFIG_LINES; i++) rows[i * 2 + 1] = '\n';
    rows[CTOS_SSH_CONFIG_LINES * 2] = 0;
    v = view_new(rows); e = open_catalog(v); CHECK(catalog_ok(e)->scanned_files == 1); finish(e, v);
    rows[CTOS_SSH_CONFIG_LINES * 2] = '#'; rows[CTOS_SSH_CONFIG_LINES * 2 + 2] = 0;
    expect_bad_view(view_new(rows), CTOS_CONFIG_LIMIT, 1, CTOS_SSH_CONFIG_LINES + 1); free(rows);
    char *line = malloc(CTOS_SSH_CONFIG_LINE_BYTES + 2); CHECK(line != NULL);
    memset(line, '#', CTOS_SSH_CONFIG_LINE_BYTES + 1); line[CTOS_SSH_CONFIG_LINE_BYTES] = 0;
    v = view_new(line); e = open_catalog(v); CHECK(catalog_ok(e)->alias_count == 0); finish(e, v);
    line[CTOS_SSH_CONFIG_LINE_BYTES] = '#'; line[CTOS_SSH_CONFIG_LINE_BYTES + 1] = 0;
    expect_bad_view(view_new(line), CTOS_CONFIG_LIMIT, 1, 1); free(line);
}
static void io_budgets(void) {
    char *body = malloc(16385); CHECK(body != NULL);
    size_t n = 0;
    for (unsigned int i = 0; i < 1024; i++) {
        memcpy(body + n, "Include child\n", 14); n += 14; body[n] = 0;
        if (i != 1022 && i != 1023) continue;
        struct ctos_ssh_view *v = view_new(body); add_file(v, CTOS_SSH_CONFIG, ".ssh/child", "#\n");
        if (i == 1023) { expect_bad_view(v, CTOS_CONFIG_LIMIT, 2, 0); continue; }
        struct ctos_ssh_config_eval *e = open_catalog(v); CHECK(catalog_ok(e)->scanned_files == 2); finish(e, v);
    }
    const size_t child_length = 1024 * 1024;
    char *child = malloc(child_length + 1); CHECK(child != NULL);
    memset(child, '#', child_length); child[child_length] = 0;
    for (size_t i = 8191; i < child_length; i += 8192) child[i] = '\n';
    n = 0; body[0] = 0;
    for (unsigned int i = 0; i < 64; i++) {
        memcpy(body + n, "Include child\n", 14); n += 14; body[n] = 0;
        if (i != 62 && i != 63) continue;
        struct ctos_ssh_view *v = view_new(body); add_file(v, CTOS_SSH_CONFIG, ".ssh/child", child);
        if (i == 63) { expect_bad_view(v, CTOS_CONFIG_LIMIT, 2, 0); continue; }
        struct ctos_ssh_config_eval *e = open_catalog(v); CHECK(catalog_ok(e)->scanned_files == 2); finish(e, v);
    }
    free(child); free(body);
}
static void *wrong_thread(void *arg) {
    struct ctos_ssh_config_eval *e = arg;
    struct ctos_ssh_config_error error; const struct ctos_ssh_config_catalog *c = NULL;
    CHECK(ctos_ssh_config_eval_run(e, &error) == -1 && errno == EPERM);
    CHECK(ctos_ssh_config_eval_catalog(e, &c) == -1 && errno == EPERM);
    CHECK(ctos_ssh_config_eval_close(e) == -1 && errno == EPERM); return NULL;
}
static void lifetime(void) {
    for (int mode = 0; mode < 9; mode++) {
        struct ctos_ssh_view *v = view_new("Host one\n"); struct ctos_ssh_config_eval *e = open_catalog(v);
        struct ctos_ssh_config_error error; const struct ctos_ssh_config_catalog *c = NULL;
        if (mode == 0) {
            pthread_t t; CHECK(pthread_create(&t, NULL, wrong_thread, e) == 0); CHECK(pthread_join(t, NULL) == 0);
            (void)catalog_ok(e); v->valid = 0;
            CHECK(ctos_ssh_config_eval_catalog(e, &c) == -1);
        } else if (mode == 1) {
            v->deadline = 1; CHECK(run_eval(e, &error) == -1 && error.reason == CTOS_CONFIG_LIFETIME);
        } else if (mode == 2) {
            cancel_eval = e; CHECK(run_eval(e, &error) == -1 && error.reason == CTOS_CONFIG_LIFETIME);
        } else if (mode == 3) {
            fail_read = 1; CHECK(run_eval(e, &error) == -1 && error.reason == CTOS_CONFIG_IO); read_error = 0;
        } else if (mode == 4) {
            fail_unmap = 2; CHECK(run_eval(e, &error) == -1 && error.reason == CTOS_CONFIG_IO);
            CHECK(ctos_ssh_config_eval_close(e) == -1); CHECK(ctos_ssh_config_eval_close(e) == 0);
        } else if (mode == 5) {
            struct ctos_ssh_view *other_view = view_new("Host other\n"); other_eval = open_catalog(other_view);
            intervene_eval = e; do_reenter = 1;
            CHECK(run_eval(e, &error) == 0 && reentries > 0); intervene_eval = NULL;
            finish(other_eval, other_view); other_eval = NULL;
        } else if (mode == 6) {
            (void)catalog_ok(e); ctos_ssh_config_eval_cancel(e);
            CHECK(ctos_ssh_config_eval_catalog(e, &c) == -1 && errno == ECANCELED);
        } else {
            /* Revoke/cancel at the last reader release, after all declarations
             * were collected but before run can publish READY. */
            intervene_eval = e; intervene_view = v;
            do_revoke = mode == 7; do_cancel = mode == 8;
            CHECK(run_eval(e, &error) == -1 && error.reason == CTOS_CONFIG_LIFETIME);
            CHECK(!do_revoke && !do_cancel);
            CHECK(ctos_ssh_config_eval_catalog(e, &c) == -1 && c == NULL);
            intervene_eval = NULL; intervene_view = NULL;
        }
        finish(e, v);
    }
}

struct wire { unsigned char bytes[131072]; size_t n; };
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
static ctos_ssh_view_token token_view(const char *body, const char *included) {
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
static uint32_t at(const unsigned char *p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3];
}
static void registry(void) {
    ctos_ssh_view_token v = token_view("Host one two\nInclude child\n", "Host three one\nInclude %h %r\n");
    ctos_ssh_config_token h = 0;
    unsigned char *wire = malloc(CTOS_SSH_CONFIG_CATALOG_BYTES); CHECK(wire != NULL);
    size_t n = 99;
    CHECK(ctos_ssh_config_handle_open_catalog(v, &h) == 0 && h != 0);
    CHECK(ctos_ssh_config_handle_catalog(h, wire, &n) == -1 && errno == EAGAIN && n == 0);
    int32_t result[5]; observe = 1;
    CHECK(ctos_ssh_config_handle_run(h, result) == 0 && result[0] == 0); observe = 0;
    CHECK(ctos_ssh_config_handle_catalog(h, wire, &n) == 0 && n < CTOS_SSH_CONFIG_CATALOG_BYTES);
    CHECK(at(wire) == 0x43534344U && at(wire + 4) == 1 && at(wire + 8) == 9);
    CHECK(at(wire + 12) == 2 && at(wire + 16) == 3 && at(wire + 20) == 1);
    CHECK(at(wire + 24) == 1 && at(wire + 28) == 1 && at(wire + 32) == 3);
    CHECK(memcmp(wire + 36, "one", 3) == 0);
    CHECK(ctos_ssh_config_handle_catalog_live(h) == 0);
    unsigned char summary[CTOS_SSH_CONFIG_SUMMARY_BYTES]; size_t summary_n;
    CHECK(ctos_ssh_config_handle_summary(h, summary, &summary_n) == -1 && errno == EINVAL);
    CHECK(ctos_ssh_config_handle_run(h, result) == 0 && result[0] == 2 && result[4] == EALREADY);
    CHECK(ctos_ssh_view_handle_destroy(v) == 0);
    CHECK(ctos_ssh_config_handle_catalog_live(h) == -1);
    n = 99; memset(wire, 0xff, CTOS_SSH_CONFIG_CATALOG_BYTES);
    CHECK(ctos_ssh_config_handle_catalog(h, wire, &n) == -1 && n == 0);
    for (unsigned int i = 0; i < CTOS_SSH_CONFIG_CATALOG_BYTES; i++) CHECK(wire[i] == 0);
    CHECK(ctos_ssh_config_handle_close(h) == 0 && ctos_ssh_config_handle_destroy(h) == 0);
    CHECK(ctos_ssh_config_handle_catalog_live(h) == -1 && errno == ENOENT);
    CHECK(ctos_ssh_view_handle_destroy(v) == 0);
    v = token_view("Host after\n", NULL);
    fail_catalog_open = 1;
    CHECK(ctos_ssh_config_handle_open_catalog(v, &h) == 0 && h > 0);
    CHECK(ctos_ssh_config_handle_run(h, result) == 0 && result[0] == 2 && result[4] == ENOMEM);
    fail_release = 1;
    CHECK(ctos_ssh_config_handle_close(h) == -1 && errno == EIO);
    CHECK(ctos_ssh_config_handle_destroy(h) == -1 && errno == EBUSY);
    CHECK(ctos_ssh_config_handle_close(h) == 0 && ctos_ssh_config_handle_destroy(h) == 0);
    struct ctos_ssh_view_summary vs; CHECK(ctos_ssh_view_handle_summary(v, &vs) == 0);
    CHECK(ctos_ssh_config_handle_open_catalog(v, &h) == 0);
    cancel_handle = h; observe = 1;
    CHECK(ctos_ssh_config_handle_run(h, result) == 0 && result[0] == 1 && result[1] == CTOS_CONFIG_LIFETIME);
    observe = 0;
    CHECK(ctos_ssh_config_handle_close(h) == 0 && ctos_ssh_config_handle_destroy(h) == 0);
    CHECK(ctos_ssh_view_handle_destroy(v) == 0);
    free(wire);
}
static void maximum_wire(void) {
    char *body = calloc(1, CTOS_SSH_CONFIG_CATALOG_BYTES); CHECK(body != NULL);
    size_t n = 0;
    for (unsigned int i = 0; i < 256; i++) {
        char alias[256]; memset(alias, 'x', 255); alias[255] = 0;
        char prefix[8]; snprintf(prefix, sizeof(prefix), "%03u", i); memcpy(alias, prefix, 3);
        n += (size_t)snprintf(body + n, CTOS_SSH_CONFIG_CATALOG_BYTES - n, "Host %s\n", alias);
    }
    for (unsigned int i = 0; i < 256; i++)
        n += (size_t)snprintf(body + n, CTOS_SSH_CONFIG_CATALOG_BYTES - n, "Include %%h\n");
    CHECK(n < CTOS_SSH_CONFIG_CATALOG_BYTES);
    ctos_ssh_view_token v = token_view(body, NULL); free(body);
    ctos_ssh_config_token h = 0; int32_t result[5];
    CHECK(ctos_ssh_config_handle_open_catalog(v, &h) == 0);
    observe = 1; CHECK(ctos_ssh_config_handle_run(h, result) == 0 && result[0] == 0); observe = 0;
    unsigned char *wire = malloc(CTOS_SSH_CONFIG_CATALOG_BYTES + 16); CHECK(wire != NULL);
    memset(wire, 0x5a, CTOS_SSH_CONFIG_CATALOG_BYTES + 16);
    CHECK(ctos_ssh_config_handle_catalog(h, wire, &n) == 0 && n == CTOS_SSH_CONFIG_CATALOG_BYTES);
    CHECK(at(wire + 16) == 256 && at(wire + 20) == 256);
    CHECK(at(wire + n - 8) == 1 && at(wire + n - 4) == 512);
    for (unsigned int i = 0; i < 16; i++) CHECK(wire[n + i] == 0x5a);
    CHECK(ctos_ssh_config_handle_close(h) == 0 && ctos_ssh_config_handle_destroy(h) == 0);
    CHECK(ctos_ssh_view_handle_destroy(v) == 0); free(wire);
}
static unsigned int fd_count(void) {
    unsigned int count = 0;
    for (int fd = 0; fd < 1024; fd++) if (fcntl(fd, F_GETFD) != -1) count++;
    return count;
}
int main(void) {
    unsigned int before = fd_count();
    declarations(); static_includes(); lexical_only(); boundaries(); io_budgets(); lifetime(); registry(); maximum_wire();
    for (unsigned int i = 0; i < 64; i++) {
        case_index = i;
        struct ctos_ssh_view *v = view_new("Host one\nInclude %h\n");
        struct ctos_ssh_config_eval *e = open_catalog(v);
        CHECK(catalog_ok(e)->alias_count == 1); finish(e, v);
    }
    CHECK(fd_count() == before && effects == 0);
    printf("SSH_CONFIG_CATALOG checks=%u effects=%u gate=pass\n", checks, effects);
    return 0;
}

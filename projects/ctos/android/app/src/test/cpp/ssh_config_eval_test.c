#include "includes.h"
#include <sys/stat.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/un.h>
#include <sys/socket.h>
#include <sys/wait.h>
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
static unsigned int checks, effects;
static size_t case_index;
static int fail_read, read_error;
#define CHECK(c) do { checks++; if (!(c)) { fprintf(stderr, "config test failure line=%d case=%zu errno=%d\n", __LINE__, case_index, errno); abort(); } } while (0)
static int observe;
static struct ctos_ssh_config_eval *intervene_eval;
static struct ctos_ssh_view *intervene_view;
static int fail_unmap, do_cancel, do_revoke, do_reenter, do_reenter_jump;
static unsigned int reentries, jump_reentries;
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
        if (do_reenter_jump) {
            do_reenter_jump = 0;
            struct ctos_ssh_config_jump_declaration jumps, empty = {0};
            memset(&jumps, 0xa5, sizeof(jumps));
            CHECK(ctos_ssh_config_eval_jump_declaration(other_eval, &jumps) == -1 && errno == EBUSY);
            CHECK(memcmp(&jumps, &empty, sizeof(jumps)) == 0);
            jump_reentries++;
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
static int read_jumps(struct ctos_ssh_config_eval *e, struct ctos_ssh_config_jump_declaration *out) {
    unsigned int before = effects;
    observe = 1;
    int result = ctos_ssh_config_eval_jump_declaration(e, out);
    observe = 0;
    CHECK(effects == before);
    return result;
}
static void jumps_zero(const struct ctos_ssh_config_jump_declaration *out) {
    const struct ctos_ssh_config_jump_declaration empty = {0};
    CHECK(memcmp(out, &empty, sizeof(empty)) == 0);
}
static void finish(struct ctos_ssh_config_eval *e, struct ctos_ssh_view *v) {
    CHECK(ctos_ssh_config_eval_close(e) == 0);
    CHECK(ctos_ssh_config_eval_close(e) == 0);
    CHECK(ctos_ssh_config_eval_destroy(e) == 0);
    free(v);
}
static void good(void) {
    struct ctos_ssh_view *v = view_new("Host bad !alias\n User wrong\nHost al* !other\n HostName TARGET.example\n User remote\n Port 2200\n IdentityFile ~/.ssh/a\n IdentityFile ~/.ssh/b\n User ignored\n Port 9999\nMatch final host target.example originalhost alias user remote localuser local-only\n RemoteCommand uname -a\n RequestTTY force\n");
    struct ctos_ssh_config_request r = request();
    struct ctos_ssh_config_eval *e = open_eval(v, &r);
    struct ctos_ssh_config_error error;
    struct ctos_ssh_config_summary s;
    CHECK(ctos_ssh_config_eval_destroy(e) == -1 && errno == EBUSY);
    CHECK(ctos_ssh_config_eval_summary(e, &s) == -1 && s.hostname[0] == 0);
    CHECK(run_eval(e, &error) == 0 && error.reason == CTOS_CONFIG_OK);
    CHECK(ctos_ssh_config_eval_summary(e, &s) == 0);
    CHECK(strcmp(s.hostname, "target.example") == 0 && strcmp(s.user, "remote") == 0);
    CHECK(s.port == 2200 && s.identities == 2 && !s.user_required && s.has_raw_fields);
    const Options *o = NULL;
    CHECK(ctos_ssh_config_eval_options(e, &o) == 0 && o != NULL);
    CHECK(o->remote_command != NULL && strcmp(o->remote_command, "uname -a") == 0 && o->request_tty == REQUEST_TTY_FORCE);
    CHECK(o->num_canonical_domains == 0 && o->canonical_domains == NULL);
    CHECK(o->num_permitted_cnames == 0 && o->permitted_cnames == NULL);
    CHECK(run_eval(e, &error) == -1 && errno == EALREADY);
    finish(e, v);
}
static void expect_bad(const char *body, unsigned int reason) {
    struct ctos_ssh_view *v = view_new(body);
    struct ctos_ssh_config_request r = request();
    struct ctos_ssh_config_eval *e = open_eval(v, &r);
    struct ctos_ssh_config_error error;
    struct ctos_ssh_config_summary s;
    CHECK(run_eval(e, &error) == -1);
    CHECK(error.reason != 0 && (reason == 0 || error.reason == reason));
    if (error.object != 1 || error.line == 0) fprintf(stderr,
        "config diagnostic case=%zu reason=%u object=%u line=%u errno=%d\n",
        case_index, error.reason, error.object, error.line, error.error_number);
    CHECK(error.object == 1 && error.line > 0);
    CHECK(ctos_ssh_config_eval_summary(e, &s) == -1 && s.hostname[0] == 0);
    const Options *o = (const Options *)(uintptr_t)1;
    CHECK(ctos_ssh_config_eval_options(e, &o) == -1 && o == NULL);
    finish(e, v);
}
static void errors(void) {
    const char *cases[] = {
        "Port ssh\n", "Port 70000\n", "ProxyJump user@host:ssh\n", "ProxyJump ssh://user@host:ssh\n",
        "ProxyJump ssh://a%00b@host\n", "ProxyJump ssh://a%0ab@host\n", "ProxyJump -bad\n",
        "HostName %z\n", "HostName %\n", "Include ${HOME}/file\n", "Include %r.conf\n",
        "User %r\n", "User %C\n", "Include %C.conf\n", "Include %q.conf\n", "Include /etc/passwd\n", "Include ../file\n",
        "Include ~other/file\n", "Include missing\n", "Include\n", "Include #comment\n", "Unknown value\n", "Port \"broken\n",
        "PermitRemoteOpen x\n", "PermitRemoteOpen x:ssh\n", "PermitRemoteOpen #comment\n", "Port invalid\nCanonicalDomains unused\n",
        "SendEnv #comment\n", "SetEnv #comment\n", "CanonicalDomains #comment\n",
        "CanonicalizePermittedCNAMEs #comment\n", "RevokedHostKeys #comment\n", "ChannelTimeout bad\n",
        "ChannelTimeout #comment\n", "VersionAddendum\n", "VersionAddendum bad\rdata\n",
        "RefuseConnection denied\n", "KexAlgorithms ,\n", "PubkeyAcceptedAlgorithms ,\n", "KexAlgorithms unsupported-secret\n", "Ciphers unsupported\n",
        "Match user somebody\n User x\n", "Match localnetwork 127.0.0.0/8\n User x\n",
        "Match exec true\n User x\n", "Host nomatch\n Match exec true\n User x\n",
        "LocalCommand echo bad\n", "KnownHostsCommand echo bad\n", "ProxyCommand echo bad\n",
        "StrictHostKeyChecking no\n", "StrictHostKeyChecking accept-new\n", "VerifyHostKeyDNS yes\n",
        "NoHostAuthenticationForLocalhost yes\n", "ForwardAgent yes\n", "ForwardX11 yes\n",
        "IdentityAgent /bad\n", "PKCS11Provider /bad\n", "SecurityKeyProvider /bad\n", "ControlMaster yes\n",
        "ControlPath /bad\n", "PermitLocalCommand yes\n", "HostbasedAuthentication yes\n",
        "EnableSSHKeysign yes\n", "ForkAfterAuthentication yes\n", "Tunnel yes\n",
        "CanonicalizeHostname yes\n", "CanonicalizePermittedCNAMEs *:*\n", "GSSAPIAuthentication yes\n",
        "LocalForward 3000 host:ssh\n", "LocalForward 3000 ${TARGET}:22\n", NULL
    };
    for (size_t i = 0; cases[i]; i++) { case_index = i; expect_bad(cases[i], 0); good(); }
}
static void include(void) {
    struct ctos_ssh_view *v = view_new("Include conf/*.conf\nUser root-late\nHost nonmatching\n Include conditional\nHost alias\n RequestTTY yes\n");
    /* Reverse source order must be sorted by the actual upstream compat glob. */
    add_file(v, CTOS_SSH_CONFIG, ".ssh/conf/z.conf", "User z\n");
    add_file(v, CTOS_SSH_CONFIG, ".ssh/conf/a.conf", "User first\nInclude nested\n");
    add_file(v, CTOS_SSH_CONFIG, ".ssh/nested", "Port 2345\nMatch host notalias\n User not-active\n");
    add_file(v, CTOS_SSH_CONFIG, ".ssh/conditional", "Host alias\nUser forbidden\nPort 9999\n");
    struct ctos_ssh_config_request r = request();
    struct ctos_ssh_config_eval *e = open_eval(v, &r);
    struct ctos_ssh_config_error error;
    struct ctos_ssh_config_summary s;
    CHECK(run_eval(e, &error) == 0);
    CHECK(ctos_ssh_config_eval_summary(e, &s) == 0 && s.port == 2345 && strcmp(s.user, "first") == 0);
    CHECK(s.request_tty == REQUEST_TTY_YES);
    finish(e, v);
    v = view_new("Include ~/.ssh/child\n"); add_file(v, CTOS_SSH_CONFIG, ".ssh/child", "Include config\n");
    e = open_eval(v, &r);
    CHECK(run_eval(e, &error) == -1 && error.error_number == ELOOP && error.object == 2 && error.line == 1);
    finish(e, v);
    v = view_new("Include key\n"); add_file(v, CTOS_SSH_PRIVATE_KEY, ".ssh/key", "");
    e = open_eval(v, &r); CHECK(run_eval(e, &error) == -1 && error.reason == CTOS_CONFIG_PATH); finish(e, v);
    v = view_new("Host never\n Include child\n"); add_file(v, CTOS_SSH_CONFIG, ".ssh/child", "Match exec true\n");
    e = open_eval(v, &r); CHECK(run_eval(e, &error) == -1 && error.reason == CTOS_CONFIG_COMMAND); finish(e, v);
    v = view_new("User remote\nInclude %C.conf\n");
    add_file(v, CTOS_SSH_CONFIG, ".ssh/f88ed53ce73153a5e29227e45da64981dd4ebedd.conf", "Port 2500\n");
    e = open_eval(v, &r); CHECK(run_eval(e, &error) == 0);
    CHECK(ctos_ssh_config_eval_summary(e, &s) == 0 && s.port == 2500); finish(e, v);
    v = view_new("Include child\n"); add_file(v, CTOS_SSH_CONFIG, ".ssh/child", "# origin\nKexAlgorithms ,\n");
    e = open_eval(v, &r); CHECK(run_eval(e, &error) == -1 && error.reason == CTOS_CONFIG_DEFAULTS && error.object == 2 && error.line == 2); finish(e, v);
    v = view_new("Include child\n"); add_file(v, CTOS_SSH_CONFIG, ".ssh/child", "# origin\nPubkeyAcceptedAlgorithms ,\n");
    e = open_eval(v, &r); CHECK(run_eval(e, &error) == -1 && error.reason == CTOS_CONFIG_DEFAULTS && error.object == 2 && error.line == 2); finish(e, v);
    v = view_new("Include %%C.conf\n");
    e = open_eval(v, &r); CHECK(run_eval(e, &error) == -1 && error.reason == CTOS_CONFIG_PATH); finish(e, v);
    v = view_new("Include child\n"); add_file(v, CTOS_SSH_CONFIG, ".ssh/child", "HostName %q\n");
    e = open_eval(v, &r); CHECK(run_eval(e, &error) == -1 && error.reason == CTOS_CONFIG_EXPANSION && error.object == 2 && error.line == 1); finish(e, v);
    v = view_new("Include child\n"); add_file(v, CTOS_SSH_CONFIG, ".ssh/child", "User %r\n");
    e = open_eval(v, &r); CHECK(run_eval(e, &error) == -1 && error.reason == CTOS_CONFIG_USER_REQUIRED && error.object == 2 && error.line == 1); finish(e, v);
    v = view_new("Include /ctos-vault/.ssh/empty\n"); add_file(v, CTOS_SSH_CONFIG, ".ssh/empty", "");
    e = open_eval(v, &r); CHECK(run_eval(e, &error) == 0); finish(e, v);
}
static void retention(void) {
    struct ctos_ssh_view *v = view_new("User configured\nProxyJump first@one:2222,ssh://last@two:3333\nLocalForward 127.0.0.1:3000 target:80\nLocalForward 127.0.0.1:3000 target:80\nRemoteForward 4000 target:90\nDynamicForward 5000\nRemoteCommand echo %h\nRequestTTY force\nSessionType subsystem\nSendEnv LANG\nSetEnv STATIC=value\nChannelTimeout session=5m\nVersionAddendum retained\nTag custom\n");
    struct ctos_ssh_config_request r = request(); r.remote_user = "override";
    struct ctos_ssh_config_eval *e = open_eval(v, &r);
    struct ctos_ssh_config_error error;
    CHECK(run_eval(e, &error) == 0);
    const Options *o = NULL; CHECK(ctos_ssh_config_eval_options(e, &o) == 0);
    CHECK(strcmp(o->user, "override") == 0 && strcmp(o->jump_host, "two") == 0 && strcmp(o->jump_user, "last") == 0);
    CHECK(o->jump_port == 3333 && strcmp(o->jump_extra, "first@one:2222") == 0);
    CHECK(o->num_local_forwards == 2 && o->num_remote_forwards == 1);
    CHECK(o->local_forwards[0].listen_port == 3000 && o->local_forwards[1].listen_port == 5000);
    CHECK(strcmp(o->remote_command, "echo %h") == 0 && o->session_type == SESSION_TYPE_SUBSYSTEM);
    CHECK(o->num_send_env == 1 && o->num_setenv == 1 && o->num_channel_timeouts == 1);
    CHECK(strcmp(o->version_addendum, "retained") == 0 && strcmp(o->tag, "custom") == 0);
    finish(e, v);
    v = view_new("Host *\n Port 22\n"); r = request(); e = open_eval(v, &r);
    CHECK(run_eval(e, &error) == 0);
    struct ctos_ssh_config_summary s; CHECK(ctos_ssh_config_eval_summary(e, &s) == 0 && s.user_required && s.user[0] == 0);
    finish(e, v);
    v = view_new("Match user fallback\n User configured\n"); r.remote_user_fallback = "fallback"; e = open_eval(v, &r);
    CHECK(run_eval(e, &error) == 0); CHECK(ctos_ssh_config_eval_summary(e, &s) == 0 && strcmp(s.user, "configured") == 0); finish(e, v);
}
static void *wrong_owner(void *p) {
    struct ctos_ssh_config_eval *e = p;
    struct ctos_ssh_config_error error;
    struct ctos_ssh_config_summary s;
    CHECK(ctos_ssh_config_eval_run(e, &error) == -1 && errno == EPERM);
    CHECK(ctos_ssh_config_eval_summary(e, &s) == -1 && errno == EPERM);
    CHECK(ctos_ssh_config_eval_close(e) == -1 && errno == EPERM);
    CHECK(ctos_ssh_config_eval_destroy(e) == -1 && errno == EPERM);
    return NULL;
}
static void *cancel_other(void *p) { ctos_ssh_config_eval_cancel(p); return NULL; }
static void lifecycle(void) {
    struct ctos_ssh_config_request r = request();
    struct ctos_ssh_config_error error;
    struct ctos_ssh_config_summary s;
    pthread_t thread;
    struct ctos_ssh_view *v = view_new("User remote\n");
    struct ctos_ssh_config_eval *e = open_eval(v, &r);
    CHECK(pthread_create(&thread, NULL, wrong_owner, e) == 0); CHECK(pthread_join(thread, NULL) == 0);
    CHECK(run_eval(e, &error) == 0);
    v->deadline = 1; CHECK(ctos_ssh_config_eval_summary(e, &s) == -1 && errno == ETIMEDOUT); finish(e, v);
    v = view_new("User remote\n"); e = open_eval(v, &r);
    CHECK(pthread_create(&thread, NULL, cancel_other, e) == 0); CHECK(pthread_join(thread, NULL) == 0);
    CHECK(run_eval(e, &error) == -1 && error.error_number == ECANCELED); finish(e, v);
    for (int action = 0; action < 4; action++) {
        v = view_new("Include child\n"); add_file(v, CTOS_SSH_CONFIG, ".ssh/child", "User remote\n");
        e = open_eval(v, &r); intervene_eval = e; intervene_view = v;
        do_cancel = action == 0; do_revoke = action == 1; do_reenter = action == 2; fail_unmap = action == 3;
        int result = run_eval(e, &error);
        CHECK(result == (action == 2 ? 0 : -1));
        if (action == 3) {
            fail_unmap = 1; CHECK(ctos_ssh_config_eval_close(e) == -1 && errno == EIO);
        }
        intervene_eval = NULL; intervene_view = NULL;
        finish(e, v);
    }
    v = view_new("User remote\n"); e = open_eval(v, &r);
    fail_read = 1; CHECK(run_eval(e, &error) == -1 && error.reason == CTOS_CONFIG_IO && error.object == 1 && error.line == 1);
    read_error = 0; finish(e, v);
    CHECK(reentries == 1);
    struct ctos_ssh_view *other_view = view_new("User other\n");
    other_eval = open_eval(other_view, &r);
    v = view_new("User remote\n"); e = open_eval(v, &r);
    intervene_eval = e; do_reenter = 1;
    CHECK(run_eval(e, &error) == 0); intervene_eval = NULL; finish(e, v);
    e = other_eval; other_eval = NULL;
    CHECK(run_eval(e, &error) == 0); finish(e, other_view);
    v = view_new("User remote\n"); e = open_eval(v, &r);
    CHECK(run_eval(e, &error) == 0); v->valid = 0;
    CHECK(ctos_ssh_config_eval_summary(e, &s) == -1 && errno == EACCES); finish(e, v);
    v = view_new("User remote\n");
    struct ctos_ssh_view_borrow *b = NULL; CHECK(ctos_ssh_view_borrow_owned_view(v, &b) == 0);
    r.alias = "-invalid"; e = NULL; CHECK(ctos_ssh_config_eval_open(b, &r, &e) == -1 && e == NULL);
    CHECK(ctos_ssh_view_borrow_live(b) == 0); CHECK(ctos_ssh_view_borrow_release(b) == 0); free(v);
}
static void limits(void) {
    char *body = malloc(2 * CTOS_SSH_CONFIG_LINE_BYTES + 1);
    CHECK(body != NULL); memset(body, '#', 2 * CTOS_SSH_CONFIG_LINE_BYTES); body[2 * CTOS_SSH_CONFIG_LINE_BYTES] = 0;
    expect_bad(body, CTOS_CONFIG_LIMIT); free(body);
    struct ctos_ssh_config_request r = request(); struct ctos_ssh_config_error error;
    struct ctos_ssh_view *v = view_new("User x\n");
    char *long_second = malloc(CTOS_SSH_CONFIG_LINE_BYTES + 32); CHECK(long_second != NULL);
    memcpy(long_second, "User x\n", 7); memset(long_second + 7, '#', CTOS_SSH_CONFIG_LINE_BYTES + 1);
    long_second[CTOS_SSH_CONFIG_LINE_BYTES + 8] = 0;
    v->files[0].body = slice(long_second);
    struct ctos_ssh_config_eval *line_eval = open_eval(v, &r);
    CHECK(run_eval(line_eval, &error) == -1 && error.line == 2);
    finish(line_eval, v); free(long_second); v = view_new("User x\n");
    static const unsigned char nul[] = {'#','o','k','\n','U','s','e','r',' ','x',0,'y','\n'};
    v->files[0].body = (struct ctos_ssh_slice){nul, sizeof(nul)};
    struct ctos_ssh_config_eval *e = open_eval(v, &r); CHECK(run_eval(e, &error) == -1 && error.reason == CTOS_CONFIG_INPUT && error.line == 2); finish(e, v);
    v = view_new("User x\n"); static const unsigned char invalid[] = {'#','o','k','\n',0xc0,0x80};
    v->files[0].body = (struct ctos_ssh_slice){invalid, sizeof(invalid)};
    e = open_eval(v, &r); CHECK(run_eval(e, &error) == -1 && error.error_number == EILSEQ && error.line == 2); finish(e, v);
    body = calloc(32768, 1); CHECK(body != NULL);
    size_t used = 0;
    for (unsigned int i = 0; i < 300; i++) used += (size_t)snprintf(body + used, 32768 - used, "LocalForward %u host:22\n", 1000 + i);
    expect_bad(body, CTOS_CONFIG_LIMIT); free(body);
    body = calloc(65536, 1); CHECK(body != NULL); used = 0;
    for (unsigned int i = 0; i < 1025; i++) used += (size_t)snprintf(body + used, 65536 - used, "Include empty\n");
    v = view_new(body); add_file(v, CTOS_SSH_CONFIG, ".ssh/empty", ""); e = open_eval(v, &r);
    CHECK(run_eval(e, &error) == -1 && error.reason == CTOS_CONFIG_LIMIT); finish(e, v); free(body);
    char *large = malloc(CTOS_SSH_VIEW_FILE_BYTES + 1); CHECK(large != NULL);
    memset(large, '#', CTOS_SSH_VIEW_FILE_BYTES); large[CTOS_SSH_VIEW_FILE_BYTES] = 0;
    for (size_t i = CTOS_SSH_CONFIG_LINE_BYTES - 1; i < CTOS_SSH_VIEW_FILE_BYTES; i += CTOS_SSH_CONFIG_LINE_BYTES) large[i] = '\n';
    body = calloc(8192, 1); CHECK(body != NULL); used = 0;
    for (unsigned int i = 0; i < 65; i++) used += (size_t)snprintf(body + used, 8192 - used, "Include large\n");
    v = view_new(body); add_file(v, CTOS_SSH_CONFIG, ".ssh/large", large); e = open_eval(v, &r);
    CHECK(run_eval(e, &error) == -1 && error.reason == CTOS_CONFIG_LIMIT); finish(e, v); free(body); free(large);
    body = calloc(65536, 1); CHECK(body != NULL); used = 0;
    for (unsigned int i = 0; i < CTOS_SSH_CONFIG_LIST; i++) used += (size_t)snprintf(body + used, 65536 - used, "SendEnv NAME%u\n", i);
    v = view_new(body); e = open_eval(v, &r); CHECK(run_eval(e, &error) == 0);
    const Options *array_options = NULL;
    CHECK(ctos_ssh_config_eval_options(e, &array_options) == 0 && array_options->num_send_env == CTOS_SSH_CONFIG_LIST);
    finish(e, v);
    snprintf(body + used, 65536 - used, "SendEnv EXTRA\n"); expect_bad(body, CTOS_CONFIG_LIMIT); free(body);
    body = calloc(2 * (CTOS_SSH_CONFIG_LINES + 1) + 1, 1); CHECK(body != NULL);
    for (unsigned int i = 0; i <= CTOS_SSH_CONFIG_LINES; i++) { body[2 * i] = '#'; body[2 * i + 1] = '\n'; }
    expect_bad(body, CTOS_CONFIG_LIMIT); free(body);
    body = calloc(32768, 1); CHECK(body != NULL);
    for (unsigned int kind = 0; kind < 2; kind++) {
        used = 0;
        for (unsigned int i = 0; i <= SSH_MAX_IDENTITY_FILES; i++) used += (size_t)snprintf(body + used, 32768 - used,
            "%s ~/.ssh/key%u\n", kind == 0 ? "IdentityFile" : "CertificateFile", i);
        expect_bad(body, CTOS_CONFIG_INPUT);
    }
    free(body);
    char names[10][32], chain[10][80];
    for (unsigned int i = 0; i < 10; i++) { snprintf(names[i], sizeof(names[i]), ".ssh/n%u", i); snprintf(chain[i], sizeof(chain[i]), "Include n%u\n", i + 1); }
    v = view_new("Include n0\n");
    for (unsigned int i = 0; i < CTOS_SSH_CONFIG_DEPTH; i++) add_file(v, CTOS_SSH_CONFIG, names[i],
        i + 1 == CTOS_SSH_CONFIG_DEPTH ? "User depth-eight\n" : chain[i]);
    e = open_eval(v, &r); CHECK(run_eval(e, &error) == 0); finish(e, v);
    v = view_new("Include n0\n"); for (unsigned int i = 0; i < 10; i++) add_file(v, CTOS_SSH_CONFIG, names[i], chain[i]);
    e = open_eval(v, &r); CHECK(run_eval(e, &error) == -1 && error.reason == CTOS_CONFIG_LIMIT); finish(e, v);
}

static void user_tokens(void) {
    struct ctos_ssh_config_request r = request();
    struct ctos_ssh_config_error error;
    struct ctos_ssh_config_summary summary;
    struct ctos_ssh_view *v = view_new("User configured\n");
    r.remote_user = "literal%h";
    struct ctos_ssh_config_eval *e = open_eval(v, &r);
    CHECK(run_eval(e, &error) == 0);
    CHECK(ctos_ssh_config_eval_summary(e, &summary) == 0 && strcmp(summary.user, "literal%h") == 0);
    finish(e, v);
    v = view_new("HostName destination\nPort 2222\nUser %L-%i-%p-%h-%n-%u\n");
    r = request(); e = open_eval(v, &r);
    CHECK(run_eval(e, &error) == 0);
    CHECK(ctos_ssh_config_eval_summary(e, &summary) == 0 &&
        strcmp(summary.user, "local-1234-2222-destination-alias-local-only") == 0);
    finish(e, v);
    v = view_new("# scoped address\n"); r = request(); r.alias = "FE80::AB%Eth0"; e = open_eval(v, &r);
    CHECK(run_eval(e, &error) == 0);
    CHECK(ctos_ssh_config_eval_summary(e, &summary) == 0 && strcmp(summary.hostname, "FE80::AB%Eth0") == 0);
    finish(e, v);
    v = view_new("Match tagged chosen command whoami localuser local-only version OpenSSH* sessiontype exec\nUser matched\n");
    r = request(); r.tag = "chosen"; r.remote_command = "whoami"; e = open_eval(v, &r);
    CHECK(run_eval(e, &error) == 0);
    CHECK(ctos_ssh_config_eval_summary(e, &summary) == 0 && strcmp(summary.user, "matched") == 0);
    finish(e, v);
}
static void route_bounds(void) {
    char body[8192] = {0};
    size_t used = (size_t)snprintf(body, sizeof(body), "ProxyJump ");
    for (unsigned int i = 0; i < CTOS_SSH_CONFIG_JUMPS; i++) used += (size_t)snprintf(body + used, sizeof(body) - used,
        "%su@hop%u:2222", i == 0 ? "" : ",", i);
    snprintf(body + used, sizeof(body) - used, "\n");
    struct ctos_ssh_config_request r = request();
    struct ctos_ssh_config_error error;
    struct ctos_ssh_view *v = view_new(body);
    struct ctos_ssh_config_eval *e = open_eval(v, &r);
    CHECK(run_eval(e, &error) == 0);
    const Options *o;
    CHECK(ctos_ssh_config_eval_options(e, &o) == 0 && strcmp(o->jump_host, "hop7") == 0 && o->jump_port == 2222);
    CHECK(o->jump_extra != NULL && strstr(o->jump_extra, "hop0") != NULL && strstr(o->jump_extra, "hop6") != NULL);
    struct ctos_ssh_config_jump_declaration jumps;
    CHECK(read_jumps(e, &jumps) == 0 && jumps.count == CTOS_SSH_CONFIG_JUMPS);
    for (unsigned int i = 0; i < jumps.count; i++) {
        char name[16]; snprintf(name, sizeof(name), "hop%u", i);
        CHECK(strcmp(jumps.hops[i].host, name) == 0 && strcmp(jumps.hops[i].user, "u") == 0);
        CHECK(jumps.hops[i].user_specified == 1 && jumps.hops[i].port == 2222);
    }
    finish(e, v);
    snprintf(body + used, sizeof(body) - used, ",u@hop8:2222\n"); expect_bad(body, CTOS_CONFIG_LIMIT);
    used = 0;
    for (unsigned int i = 0; i < CTOS_SSH_CONFIG_FORWARDS - 2; i++) used += (size_t)snprintf(body + used, sizeof(body) - used,
        "LocalForward %u host:22\n", i + 1000);
    used += (size_t)snprintf(body + used, sizeof(body) - used, "RemoteForward 3000 host:22\nDynamicForward 4000\nLocalForward 1000 host:22\n");
    v = view_new(body); e = open_eval(v, &r); CHECK(run_eval(e, &error) == 0);
    CHECK(ctos_ssh_config_eval_options(e, &o) == 0 && o->num_local_forwards == 31 && o->num_remote_forwards == 1);
    finish(e, v);
    snprintf(body + used, sizeof(body) - used, "RemoteForward 5000 host:22\n"); expect_bad(body, CTOS_CONFIG_LIMIT);
}
static void *jump_wrong_owner(void *p) {
    struct ctos_ssh_config_jump_declaration jumps;
    memset(&jumps, 0xa5, sizeof(jumps));
    CHECK(ctos_ssh_config_eval_jump_declaration(p, &jumps) == -1 && errno == EPERM);
    jumps_zero(&jumps);
    return NULL;
}
static void jump_declarations(void) {
    struct ctos_ssh_config_request r = request();
    struct ctos_ssh_config_error error;
    struct ctos_ssh_config_jump_declaration jumps;
    memset(&jumps, 0xa5, sizeof(jumps));
    CHECK(read_jumps(NULL, &jumps) == -1 && errno == EINVAL); jumps_zero(&jumps);
    const char *empty[] = {"User remote\n", "ProxyJump none\n", "ProxyJump NONE\n"};
    for (unsigned int i = 0; i < sizeof(empty) / sizeof(empty[0]); i++) {
        struct ctos_ssh_view *v = view_new(empty[i]);
        struct ctos_ssh_config_eval *e = open_eval(v, &r);
        memset(&jumps, 0xa5, sizeof(jumps));
        CHECK(read_jumps(e, &jumps) == -1); jumps_zero(&jumps);
        CHECK(run_eval(e, &error) == 0);
        CHECK(read_jumps(e, &jumps) == 0 && jumps.count == 0); jumps_zero(&jumps);
        finish(e, v);
    }
    struct ctos_ssh_view *v = view_new("ProxyJump hop\n");
    struct ctos_ssh_config_eval *e = open_eval(v, &r);
    CHECK(run_eval(e, &error) == 0 && read_jumps(e, &jumps) == 0 && jumps.count == 1);
    CHECK(strcmp(jumps.hops[0].host, "hop") == 0 && jumps.hops[0].user[0] == 0);
    CHECK(jumps.hops[0].user_specified == 0 && jumps.hops[0].port == 0);
    pthread_t thread;
    CHECK(pthread_create(&thread, NULL, jump_wrong_owner, e) == 0);
    CHECK(pthread_join(thread, NULL) == 0);
    CHECK(ctos_ssh_config_eval_jump_declaration(e, NULL) == -1 && errno == EINVAL);
    CHECK(ctos_ssh_config_eval_close(e) == 0);
    memset(&jumps, 0xa5, sizeof(jumps));
    CHECK(read_jumps(e, &jumps) == -1); jumps_zero(&jumps);
    finish(e, v);

    v = view_new("ProxyJump first@one:2222,ssh://last@two:3333\n"); e = open_eval(v, &r);
    CHECK(run_eval(e, &error) == 0 && read_jumps(e, &jumps) == 0 && jumps.count == 2);
    CHECK(strcmp(jumps.hops[0].host, "one") == 0 && strcmp(jumps.hops[0].user, "first") == 0);
    CHECK(jumps.hops[0].user_specified == 1 && jumps.hops[0].port == 2222);
    CHECK(strcmp(jumps.hops[1].host, "two") == 0 && strcmp(jumps.hops[1].user, "last") == 0);
    CHECK(jumps.hops[1].user_specified == 1 && jumps.hops[1].port == 3333);
    ctos_ssh_config_eval_cancel(e);
    memset(&jumps, 0xa5, sizeof(jumps));
    CHECK(read_jumps(e, &jumps) == -1 && errno == ECANCELED); jumps_zero(&jumps);
    finish(e, v);

    v = view_new("ProxyJump NONE,bob@[2001:db8::1]:22,carol@none\n"); e = open_eval(v, &r);
    CHECK(run_eval(e, &error) == 0);
    CHECK(read_jumps(e, &jumps) == 0);
    CHECK(jumps.count == 3);
    CHECK(strcmp(jumps.hops[0].host, "NONE") == 0 && jumps.hops[0].port == 0);
    CHECK(strcmp(jumps.hops[1].host, "2001:db8::1") == 0 && strcmp(jumps.hops[1].user, "bob") == 0);
    CHECK(jumps.hops[1].user_specified == 1 && jumps.hops[1].port == 22);
    CHECK(strcmp(jumps.hops[2].host, "none") == 0 && strcmp(jumps.hops[2].user, "carol") == 0);
    v->deadline = 1;
    memset(&jumps, 0xa5, sizeof(jumps));
    CHECK(read_jumps(e, &jumps) == -1 && errno == ETIMEDOUT); jumps_zero(&jumps);
    finish(e, v);

    v = view_new("ProxyJump user@NONE\nUser %j\nInclude pre%j.conf\n");
    add_file(v, CTOS_SSH_CONFIG, ".ssh/pre.conf", "Port 2345\n"); e = open_eval(v, &r);
    struct ctos_ssh_config_summary summary;
    CHECK(run_eval(e, &error) == 0 && ctos_ssh_config_eval_summary(e, &summary) == 0);
    CHECK(strcmp(summary.user, "NONE") == 0 && summary.port == 2345 && summary.has_jump);
    finish(e, v);
    v = view_new("ProxyJump none\nUser x%j\nInclude pre%j.conf\n");
    add_file(v, CTOS_SSH_CONFIG, ".ssh/pre.conf", "Port 2346\n"); e = open_eval(v, &r);
    CHECK(run_eval(e, &error) == 0 && ctos_ssh_config_eval_summary(e, &summary) == 0);
    CHECK(strcmp(summary.user, "x") == 0 && summary.port == 2346 && !summary.has_jump);
    finish(e, v);

    v = view_new("ProxyJump hop\n"); e = open_eval(v, &r);
    CHECK(run_eval(e, &error) == 0); v->valid = 0;
    memset(&jumps, 0xa5, sizeof(jumps));
    CHECK(read_jumps(e, &jumps) == -1); jumps_zero(&jumps);
    finish(e, v);

    v = view_new("ProxyJump hop\n");
    struct ctos_ssh_view_borrow *borrow = NULL;
    CHECK(ctos_ssh_view_borrow_owned_view(v, &borrow) == 0);
    CHECK(ctos_ssh_config_eval_open_catalog(borrow, &e) == 0);
    CHECK(run_eval(e, &error) == 0);
    memset(&jumps, 0xa5, sizeof(jumps));
    CHECK(read_jumps(e, &jumps) == -1 && errno == EINVAL); jumps_zero(&jumps);
    finish(e, v);

    struct ctos_ssh_view *other_view = view_new("ProxyJump first,none\n");
    other_eval = open_eval(other_view, &r); CHECK(run_eval(other_eval, &error) == 0);
    v = view_new("Include child\n"); add_file(v, CTOS_SSH_CONFIG, ".ssh/child", "User remote\n");
    e = open_eval(v, &r); intervene_eval = e; do_reenter_jump = 1;
    CHECK(run_eval(e, &error) == 0); intervene_eval = NULL; finish(e, v);
    CHECK(jump_reentries == 1 && read_jumps(other_eval, &jumps) == 0 && jumps.count == 2);
    CHECK(strcmp(jumps.hops[0].host, "first") == 0 && strcmp(jumps.hops[1].host, "none") == 0);
    e = other_eval; other_eval = NULL; finish(e, other_view);
}
static int open_next_jump(struct ctos_ssh_config_eval *e, struct ctos_ssh_config_eval **out) {
    unsigned int before = effects;
    observe = 1;
    int result = ctos_ssh_config_eval_open_next_jump(e, out);
    observe = 0;
    CHECK(effects == before);
    return result;
}
static void close_jump(struct ctos_ssh_config_eval *e) {
    CHECK(ctos_ssh_config_eval_close(e) == 0);
    CHECK(ctos_ssh_config_eval_destroy(e) == 0);
}
static void *next_jump_wrong_owner(void *p) {
    struct ctos_ssh_config_eval *child = p;
    CHECK(ctos_ssh_config_eval_open_next_jump(p, &child) == -1 && errno == EPERM && child == NULL);
    return NULL;
}
static void jump_effective_config(void) {
    const char *body = "Host alias\n User target-user\n ProxyJump first@one:2201,two\n"
        "Host two\n HostName second.example\n User configured\n Port 2202\n ProxyJump ignored\n"
        "Host one\n HostName first.example\n User ignored-user\n Port 3333\n ProxyJump none\n";
    struct ctos_ssh_config_request r = request();
    r.remote_user_fallback = "target-fallback";
    struct ctos_ssh_view *v = view_new(body);
    struct ctos_ssh_config_eval *root = open_eval(v, &r), *second = root, *first = NULL, *end = NULL;
    struct ctos_ssh_config_error error;
    struct ctos_ssh_config_summary s;
    uint32_t initial[3], after_second[3], after_first[3];
    CHECK(open_next_jump(root, &second) == -1 && second == NULL);
    CHECK(run_eval(root, &error) == 0 && ctos_ssh_config_eval_usage(root, initial) == 0);
    CHECK(ctos_ssh_config_eval_open_next_jump(root, NULL) == -1 && errno == EINVAL);
    pthread_t thread;
    CHECK(pthread_create(&thread, NULL, next_jump_wrong_owner, root) == 0 && pthread_join(thread, NULL) == 0);
    CHECK(open_next_jump(root, &second) == 0 && second != NULL);
    CHECK(open_next_jump(root, &end) == -1 && errno == EBUSY && end == NULL);
    CHECK(ctos_ssh_config_eval_close(root) == -1 && errno == EBUSY);
    CHECK(run_eval(second, &error) == 0 && ctos_ssh_config_eval_summary(second, &s) == 0);
    CHECK(strcmp(s.alias, "two") == 0 && strcmp(s.hostname, "second.example") == 0);
    CHECK(strcmp(s.user, "configured") == 0 && s.port == 2202);
    struct ctos_ssh_config_jump_declaration jumps;
    CHECK(read_jumps(second, &jumps) == 0 && jumps.count == 1);
    CHECK(strcmp(jumps.hops[0].host, "one") == 0 && strcmp(jumps.hops[0].user, "first") == 0 && jumps.hops[0].port == 2201);
    CHECK(ctos_ssh_config_eval_usage(second, after_second) == 0);
    CHECK(after_second[0] > initial[0] && after_second[1] > initial[1] && after_second[2] > initial[2]);
    CHECK(open_next_jump(second, &first) == 0 && first != NULL);
    CHECK(run_eval(first, &error) == 0 && ctos_ssh_config_eval_summary(first, &s) == 0);
    CHECK(strcmp(s.hostname, "first.example") == 0 && strcmp(s.user, "first") == 0 && s.port == 2201);
    CHECK(open_next_jump(first, &end) == 0 && end == NULL);
    CHECK(ctos_ssh_config_eval_usage(root, after_first) == 0);
    CHECK(after_first[0] > after_second[0] && after_first[1] > after_second[1] && after_first[2] > after_second[2]);
    CHECK(ctos_ssh_config_eval_usage(first, after_second) == 0 && memcmp(after_first, after_second, sizeof(after_first)) == 0);
    CHECK(ctos_ssh_config_eval_close(second) == -1 && errno == EBUSY);
    close_jump(first); close_jump(second); finish(root, v);

    body = "Host alias\n ProxyJump two\n User target\nHost two\n ProxyJump nested\n"
        "Host nested\n User nested-user\n ProxyJump none\n";
    v = view_new(body); root = open_eval(v, &r);
    CHECK(run_eval(root, &error) == 0 && open_next_jump(root, &second) == 0 && second != NULL);
    CHECK(run_eval(second, &error) == 0 && ctos_ssh_config_eval_summary(second, &s) == 0);
    CHECK(s.user_required && s.user[0] == 0 && s.port == 22);
    CHECK(open_next_jump(second, &first) == 0 && first != NULL);
    CHECK(run_eval(first, &error) == 0 && ctos_ssh_config_eval_summary(first, &s) == 0);
    CHECK(strcmp(s.alias, "nested") == 0 && strcmp(s.user, "nested-user") == 0);
    close_jump(first); close_jump(second); finish(root, v);

    body = "Host alias\n ProxyJump NONE,two\nHost two\n ProxyJump ignored\nHost NONE\n ProxyJump none\n";
    v = view_new(body); root = open_eval(v, &r);
    CHECK(run_eval(root, &error) == 0 && open_next_jump(root, &second) == 0 && second != NULL);
    CHECK(run_eval(second, &error) == 0 && read_jumps(second, &jumps) == 0);
    CHECK(jumps.count == 1 && strcmp(jumps.hops[0].host, "NONE") == 0);
    CHECK(open_next_jump(second, &first) == 0 && first != NULL);
    CHECK(run_eval(first, &error) == 0 && ctos_ssh_config_eval_summary(first, &s) == 0 && strcmp(s.alias, "NONE") == 0);
    CHECK(open_next_jump(first, &end) == 0 && end == NULL);
    close_jump(first); close_jump(second); finish(root, v);

    char detached_body[] = "Host alias\n ProxyJump one\nHost one\n User from-snapshot\n ProxyJump none\n";
    struct ctos_ssh_config_file file = {CTOS_SSH_CONFIG, slice(".ssh/config"), slice(detached_body)};
    root = NULL;
    CHECK(ctos_ssh_config_eval_open_detached(&file, 1, 0, later(), &r, &root) == 0 && root != NULL);
    memset(detached_body, 'x', strlen(detached_body));
    CHECK(run_eval(root, &error) == 0 && open_next_jump(root, &first) == 0 && first != NULL);
    CHECK(run_eval(first, &error) == 0 && ctos_ssh_config_eval_summary(first, &s) == 0);
    CHECK(strcmp(s.alias, "one") == 0 && strcmp(s.user, "from-snapshot") == 0);
    CHECK(ctos_ssh_config_eval_close(root) == -1 && errno == EBUSY);
    close_jump(first); close_jump(root);
}
static void jump_lifetime_and_limits(void) {
    struct ctos_ssh_config_request r = request();
    struct ctos_ssh_config_error error;
    struct ctos_ssh_config_eval *root, *child = NULL;
    struct ctos_ssh_view *v = view_new("Host alias\n ProxyJump one\nHost one\n ProxyJump ALIAS\n");
    root = open_eval(v, &r);
    CHECK(run_eval(root, &error) == 0 && open_next_jump(root, &child) == 0 && child != NULL);
    CHECK(run_eval(child, &error) == 0);
    struct ctos_ssh_config_eval *extra = child;
    CHECK(open_next_jump(child, &extra) == -1 && errno == ELOOP && extra == NULL);
    close_jump(child); finish(root, v);

    char body[2048];
    size_t n = (size_t)snprintf(body, sizeof(body), "Host alias\n ProxyJump h1\n");
    for (unsigned int i = 1; i <= CTOS_SSH_CONFIG_JUMPS; i++)
        n += (size_t)snprintf(body + n, sizeof(body) - n, "Host h%u\n ProxyJump h%u\n", i, i + 1);
    v = view_new(body); root = open_eval(v, &r); CHECK(run_eval(root, &error) == 0);
    struct ctos_ssh_config_eval *chain[CTOS_SSH_CONFIG_JUMPS + 1] = {root};
    for (unsigned int i = 1; i <= CTOS_SSH_CONFIG_JUMPS; i++) {
        CHECK(open_next_jump(chain[i - 1], &chain[i]) == 0 && chain[i] != NULL);
        CHECK(run_eval(chain[i], &error) == 0);
    }
    extra = root;
    CHECK(open_next_jump(chain[CTOS_SSH_CONFIG_JUMPS], &extra) == -1 && errno == E2BIG && extra == NULL);
    for (unsigned int i = CTOS_SSH_CONFIG_JUMPS; i > 0; i--) close_jump(chain[i]);
    finish(root, v);

    v = view_new("Host alias\n ProxyJump one\nHost one\n ProxyJump none\n"); root = open_eval(v, &r);
    CHECK(run_eval(root, &error) == 0 && open_next_jump(root, &child) == 0 && child != NULL);
    intervene_eval = root; do_cancel = 1;
    CHECK(run_eval(child, &error) == -1 && error.error_number == ECANCELED);
    intervene_eval = NULL;
    CHECK(ctos_ssh_config_eval_close(root) == -1 && errno == EBUSY);
    close_jump(child); finish(root, v);

    v = view_new("Host alias\n ProxyJump one\nHost one\n ProxyJump none\n"); root = open_eval(v, &r);
    CHECK(run_eval(root, &error) == 0 && open_next_jump(root, &child) == 0 && child != NULL);
    intervene_eval = root; do_reenter = 1; fail_unmap = 1;
    CHECK(run_eval(child, &error) == -1); intervene_eval = NULL;
    fail_unmap = 1;
    CHECK(ctos_ssh_config_eval_close(child) == -1 && errno == EIO);
    CHECK(ctos_ssh_config_eval_close(root) == -1 && errno == EBUSY);
    close_jump(child); finish(root, v);

    v = view_new("ProxyJump one\n"); root = open_eval(v, &r); CHECK(run_eval(root, &error) == 0);
    v->valid = 0; child = root;
    CHECK(open_next_jump(root, &child) == -1 && child == NULL);
    finish(root, v);

    v = view_new("ProxyJump one\n"); root = open_eval(v, &r); CHECK(run_eval(root, &error) == 0);
    CHECK(open_next_jump(root, &child) == 0 && child != NULL);
    v->valid = 0;
    CHECK(run_eval(child, &error) == -1 && error.error_number == EACCES);
    close_jump(child); finish(root, v);

    v = view_new("ProxyJump one\n"); root = open_eval(v, &r); CHECK(run_eval(root, &error) == 0);
    CHECK(open_next_jump(root, &child) == 0 && child != NULL);
    v->deadline = 1;
    CHECK(run_eval(child, &error) == -1 && error.error_number == ETIMEDOUT);
    close_jump(child); finish(root, v);
}
static int build_route(struct ctos_ssh_config_eval *root, struct ctos_ssh_config_route **route,
    struct ctos_ssh_config_error *error) {
    unsigned int before = effects;
    observe = 1;
    int result = ctos_ssh_config_route_build(root, route, error);
    observe = 0;
    CHECK(effects == before);
    return result;
}
static void close_route(struct ctos_ssh_config_route *route) {
    CHECK(ctos_ssh_config_route_close(route) == 0);
    CHECK(ctos_ssh_config_route_close(route) == 0);
    CHECK(ctos_ssh_config_route_destroy(route) == 0);
}
static void *route_wrong_owner(void *p) {
    unsigned int count = 99;
    CHECK(ctos_ssh_config_route_count(p, &count) == -1 && errno == EPERM && count == 0);
    CHECK(ctos_ssh_config_route_close(p) == -1 && errno == EPERM);
    return NULL;
}
static void jump_route(void) {
    struct ctos_ssh_config_request r = request();
    struct ctos_ssh_config_error error;
    struct ctos_ssh_config_summary summary, empty = {0};
    struct ctos_ssh_config_route *route = NULL;
    unsigned int count = 0;
    const char *body = "Host alias\n ProxyJump first@one:2201,two\n"
        "Host two\n User second-user\n Port 2202\n ProxyJump ignored\n"
        "Host one\n HostName first.example\n IdentityFile ~/.ssh/first\n"
        " RequestTTY force\n LocalForward 3300 example:22\n ProxyJump none\n";
    struct ctos_ssh_view *v = view_new(body);
    struct ctos_ssh_config_eval *root = open_eval(v, &r);
    CHECK(run_eval(root, &error) == 0 && build_route(root, &route, &error) == 0 && route != NULL);
    CHECK(ctos_ssh_config_route_count(route, &count) == 0 && count == 2);
    CHECK(ctos_ssh_config_route_summary(route, 0, &summary) == 0);
    CHECK(strcmp(summary.alias, "one") == 0 && strcmp(summary.hostname, "first.example") == 0);
    CHECK(strcmp(summary.user, "first") == 0 && summary.port == 2201 && summary.identities == 1);
    CHECK(summary.request_tty == REQUEST_TTY_NO && summary.session_type == SESSION_TYPE_NONE && summary.local_forwards == 0);
    CHECK(ctos_ssh_config_route_summary(route, 1, &summary) == 0);
    CHECK(strcmp(summary.alias, "two") == 0 && strcmp(summary.user, "second-user") == 0 && summary.port == 2202);
    memset(&summary, 0xa5, sizeof(summary));
    CHECK(ctos_ssh_config_route_summary(route, 2, &summary) == -1 && errno == ERANGE);
    CHECK(memcmp(&summary, &empty, sizeof(summary)) == 0);
    const Options *options = NULL;
    CHECK(ctos_ssh_config_route_options(route, 0, &options) == 0 && options != NULL);
    CHECK(options->num_identity_files == 1 && strcmp(options->identity_files[0], "~/.ssh/first") == 0);
    CHECK(strcmp(options->stdio_forward_host, "two") == 0 && options->stdio_forward_port == 2202);
    CHECK(options->exit_on_forward_failure == 1 && options->clear_forwardings == 1);
    CHECK(ctos_ssh_config_route_options(route, 1, &options) == 0 && options != NULL);
    CHECK(strcmp(options->stdio_forward_host, "alias") == 0 && options->stdio_forward_port == 22);
    pthread_t thread;
    CHECK(pthread_create(&thread, NULL, route_wrong_owner, route) == 0 && pthread_join(thread, NULL) == 0);
    CHECK(ctos_ssh_config_route_destroy(route) == -1 && errno == EBUSY);
    CHECK(ctos_ssh_config_eval_close(root) == -1 && errno == EBUSY);
    struct ctos_ssh_config_route *other = route;
    CHECK(build_route(root, &other, &error) == -1 && errno == EBUSY && other == NULL);
    ctos_ssh_config_eval_cancel(root);
    count = 99; CHECK(ctos_ssh_config_route_count(route, &count) == -1 && errno == ECANCELED && count == 0);
    options = (const Options *)root;
    CHECK(ctos_ssh_config_route_options(route, 0, &options) == -1 && errno == ECANCELED && options == NULL);
    close_route(route); finish(root, v);

    body = "Host alias\n ProxyJump bob@[2001:db8::1]:2222,ssh://carol@jump:2233\n"
        "Host jump\n ProxyJump ignored\nHost 2001:db8::1\n ProxyJump none\n";
    v = view_new(body); root = open_eval(v, &r);
    CHECK(run_eval(root, &error) == 0 && build_route(root, &route, &error) == 0 && route != NULL);
    CHECK(ctos_ssh_config_route_count(route, &count) == 0 && count == 2);
    CHECK(ctos_ssh_config_route_summary(route, 0, &summary) == 0);
    CHECK(strcmp(summary.alias, "2001:db8::1") == 0 && strcmp(summary.user, "bob") == 0 && summary.port == 2222);
    CHECK(ctos_ssh_config_route_summary(route, 1, &summary) == 0);
    CHECK(strcmp(summary.alias, "jump") == 0 && strcmp(summary.user, "carol") == 0 && summary.port == 2233);
    close_route(route); finish(root, v);

    body = "Host alias\n ProxyJump same@jump:2201,same@jump:2202\nHost jump\n ProxyJump none\n";
    v = view_new(body); root = open_eval(v, &r);
    CHECK(run_eval(root, &error) == 0 && build_route(root, &route, &error) == 0 && route != NULL);
    CHECK(ctos_ssh_config_route_count(route, &count) == 0 && count == 2);
    CHECK(ctos_ssh_config_route_summary(route, 0, &summary) == 0 && summary.port == 2201);
    CHECK(ctos_ssh_config_route_summary(route, 1, &summary) == 0 && summary.port == 2202);
    close_route(route); finish(root, v);

    v = view_new("ProxyJump none\n"); root = open_eval(v, &r);
    CHECK(run_eval(root, &error) == 0 && build_route(root, &route, &error) == 0 && route != NULL);
    CHECK(ctos_ssh_config_route_count(route, &count) == 0 && count == 0);
    CHECK(ctos_ssh_config_eval_close(root) == -1 && errno == EBUSY);
    CHECK(ctos_ssh_config_route_close(route) == 0);
    count = 99; CHECK(ctos_ssh_config_route_count(route, &count) == -1 && count == 0);
    finish(root, v); CHECK(ctos_ssh_config_route_destroy(route) == 0);

    v = view_new("Host alias\n ProxyJump one\nHost one\n ProxyJump ALIAS\n"); root = open_eval(v, &r);
    CHECK(run_eval(root, &error) == 0); route = NULL;
    CHECK(build_route(root, &route, &error) == -1 && errno == ELOOP && route == NULL);
    CHECK(ctos_ssh_config_eval_summary(root, &summary) == 0);
    finish(root, v);

    v = view_new("Host alias\n ProxyJump one\nHost one\n LocalCommand touch-forbidden\n"); root = open_eval(v, &r);
    CHECK(run_eval(root, &error) == 0); route = NULL;
    CHECK(build_route(root, &route, &error) == -1 && route == NULL && error.reason == CTOS_CONFIG_COMMAND);
    finish(root, v);

    v = view_new("Host alias\n ProxyJump one\nHost one\n ProxyJump none\n"); root = open_eval(v, &r);
    CHECK(run_eval(root, &error) == 0); route = NULL;
    intervene_eval = root; do_cancel = 1;
    CHECK(build_route(root, &route, &error) == -1 && error.error_number == ECANCELED && route == NULL);
    intervene_eval = NULL; finish(root, v);

    v = view_new("Host alias\n ProxyJump one\nHost one\n ProxyJump none\n"); root = open_eval(v, &r);
    CHECK(run_eval(root, &error) == 0); route = NULL; fail_unmap = 2;
    CHECK(build_route(root, &route, &error) == -1 && route != NULL);
    count = 99; CHECK(ctos_ssh_config_route_count(route, &count) == -1 && errno == EAGAIN && count == 0);
    CHECK(ctos_ssh_config_eval_close(root) == -1 && errno == EBUSY);
    close_route(route); finish(root, v);
}

static void profile_jump_route(void) {
    struct ctos_ssh_config_request r = request();
    struct ctos_ssh_config_error error = {0};
    struct ctos_ssh_config_route *route = NULL;
    const struct ctos_ssh_config_direct_plan *plan = NULL;
    unsigned int count = 0;
    const char *body = "Host alias\n HostName target.example\n User target\n ProxyJump first@one:2201,two\n"
        "Host one\n HostName first.example\n IdentityFile ~/.ssh/first\n PasswordAuthentication no\n"
        "Host two\n User second\n Port 2202\n IdentityFile ~/.ssh/second\n"
        "Host *\n UserKnownHostsFile ~/.ssh/known_hosts\n";
    struct ctos_ssh_view *view = view_new(body);
    struct ctos_ssh_config_eval *root = open_eval(view, &r);
    CHECK(ctos_ssh_config_eval_enable_profile_route(root) == 0);
    CHECK(run_eval(root, &error) == 0);
    CHECK(ctos_ssh_config_eval_profile_direct_plan(root, &plan) == -1 && plan == NULL);
    CHECK(build_route(root, &route, &error) == 0 && route != NULL);
    CHECK(ctos_ssh_config_route_count(route, &count) == 0 && count == 2);
    CHECK(ctos_ssh_config_route_profile_plan(route, 0, &plan) == 0);
    CHECK(strcmp(plan->summary.alias, "one") == 0 && strcmp(plan->summary.hostname, "first.example") == 0);
    CHECK(strcmp(plan->summary.user, "first") == 0 && plan->summary.port == 2201);
    CHECK(plan->summary.request_tty == REQUEST_TTY_NO && plan->identity_hint_count == 1);
    CHECK(strcmp(plan->identity_hints[0], "~/.ssh/first") == 0 && !plan->password_authentication);
    CHECK(plan->hostfile_count == 1 && strcmp(plan->user_hostfiles[0], ".ssh/known_hosts") == 0);
    CHECK(ctos_ssh_config_route_profile_plan(route, 1, &plan) == 0);
    CHECK(strcmp(plan->summary.alias, "two") == 0 && strcmp(plan->summary.user, "second") == 0);
    CHECK(plan->summary.port == 2202 && plan->summary.request_tty == REQUEST_TTY_NO);
    CHECK(plan->identity_hint_count == 1 && strcmp(plan->identity_hints[0], "~/.ssh/second") == 0);
    CHECK(ctos_ssh_config_route_profile_plan(route, 2, &plan) == 0);
    CHECK(strcmp(plan->summary.hostname, "target.example") == 0 && strcmp(plan->summary.user, "target") == 0);
    CHECK(plan->summary.port == 22 && plan->summary.request_tty == REQUEST_TTY_AUTO);
    CHECK(ctos_ssh_config_route_profile_plan(route, 3, &plan) == -1 && plan == NULL);
    ctos_ssh_config_eval_cancel(root);
    CHECK(ctos_ssh_config_route_profile_plan(route, 0, &plan) == -1 && plan == NULL);
    close_route(route); finish(root, view);

    view = view_new("Host alias\n ProxyJump one\nHost one\n LocalForward 3300 example:22\nHost *\n UserKnownHostsFile ~/.ssh/known_hosts\n");
    root = open_eval(view, &r); route = NULL;
    CHECK(ctos_ssh_config_eval_enable_profile_route(root) == 0 && run_eval(root, &error) == 0);
    CHECK(build_route(root, &route, &error) == -1 && route == NULL && error.reason == CTOS_CONFIG_UNSUPPORTED);
    finish(root, view);

    view = view_new("ProxyJump none\nUserKnownHostsFile ~/.ssh/known_hosts\n");
    root = open_eval(view, &r); route = NULL;
    CHECK(ctos_ssh_config_eval_enable_profile_route(root) == 0 && run_eval(root, &error) == 0);
    CHECK(build_route(root, &route, &error) == 0 && ctos_ssh_config_route_count(route, &count) == 0 && count == 0);
    CHECK(ctos_ssh_config_route_profile_plan(route, 0, &plan) == 0 && strcmp(plan->summary.alias, "alias") == 0);
    close_route(route); finish(root, view);

    view = view_new("UserKnownHostsFile ~/.ssh/known_hosts\n");
    r.remote_command = "must-not-be-discarded"; root = open_eval(view, &r);
    CHECK(ctos_ssh_config_eval_enable_profile_route(root) == -1 && errno == EINVAL);
    r.remote_command = NULL; finish(root, view);

    view = view_new("Host alias\n HostName bad_host\n UserKnownHostsFile ~/.ssh/known_hosts\n");
    root = open_eval(view, &r);
    CHECK(ctos_ssh_config_eval_enable_profile_route(root) == 0);
    CHECK(run_eval(root, &error) == -1);
    finish(root, view);
}
pid_t __real_fork(void);
static void jump_route_low_memlock(void) {
    struct rlimit before, after;
    CHECK(getrlimit(RLIMIT_MEMLOCK, &before) == 0);
    pid_t child = __real_fork(); CHECK(child >= 0);
    if (child == 0) {
        struct rlimit limit = {.rlim_cur = 65536, .rlim_max = 65536};
        CHECK(setrlimit(RLIMIT_MEMLOCK, &limit) == 0);
        char body[] = "Host alias\n ProxyJump one,two\nHost one\n ProxyJump none\n";
        struct ctos_ssh_config_file file = {CTOS_SSH_CONFIG, slice(".ssh/config"), slice(body)};
        struct ctos_ssh_config_request r = request();
        struct ctos_ssh_config_error error;
        struct ctos_ssh_config_eval *root = NULL;
        struct ctos_ssh_config_route *route = NULL;
        unsigned int count = 0;
        CHECK(ctos_ssh_config_eval_open_detached(&file, 1, 0, later(), &r, &root) == 0);
        CHECK(run_eval(root, &error) == 0 && build_route(root, &route, &error) == 0 && route != NULL);
        CHECK(ctos_ssh_config_route_count(route, &count) == 0 && count == 2);
        close_route(route); close_jump(root);
        printf("SSH_CONFIG_ROUTE_MEMLOCK limit=65536 detached_multi_hop=pass cleanup=pass\n");
        fflush(stdout); _exit(0);
    }
    int status = 0;
    CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    CHECK(getrlimit(RLIMIT_MEMLOCK, &after) == 0 && before.rlim_cur == after.rlim_cur && before.rlim_max == after.rlim_max);
}
static void jump_shared_budgets(void) {
    struct ctos_ssh_config_request r = request();
    struct ctos_ssh_config_error error;
    uint32_t usage[3];
    char *body = malloc(14000); CHECK(body != NULL);
    size_t n = 0;
    for (unsigned int i = 0; i < 5500; i++) { body[n++] = '#'; body[n++] = '\n'; }
    n += (size_t)snprintf(body + n, 14000 - n,
        "Host alias\n ProxyJump one\nHost one\n ProxyJump two\nHost two\n ProxyJump none\n");
    struct ctos_ssh_view *v = view_new(body);
    struct ctos_ssh_config_eval *root = open_eval(v, &r), *one = NULL, *two = NULL;
    CHECK(run_eval(root, &error) == 0 && open_next_jump(root, &one) == 0 && one != NULL);
    CHECK(run_eval(one, &error) == 0 && open_next_jump(one, &two) == 0 && two != NULL);
    CHECK(run_eval(two, &error) == -1 && error.reason == CTOS_CONFIG_LIMIT);
    CHECK(ctos_ssh_config_eval_usage(root, usage) == 0 && usage[0] == 3 && usage[2] == CTOS_SSH_CONFIG_LINES);
    close_jump(two); close_jump(one); finish(root, v);
    v = view_new(body); root = open_eval(v, &r);
    struct ctos_ssh_config_route *route = NULL;
    CHECK(run_eval(root, &error) == 0);
    CHECK(build_route(root, &route, &error) == -1 && route == NULL && error.reason == CTOS_CONFIG_LIMIT);
    CHECK(ctos_ssh_config_eval_usage(root, usage) == 0 && usage[2] == CTOS_SSH_CONFIG_LINES);
    finish(root, v); free(body);

    body = malloc(10000); CHECK(body != NULL); n = 0;
    for (unsigned int i = 0; i < 600; i++) n += (size_t)snprintf(body + n, 10000 - n, "Include child\n");
    snprintf(body + n, 10000 - n, "Host alias\n ProxyJump one\nHost one\n ProxyJump none\n");
    v = view_new(body); add_file(v, CTOS_SSH_CONFIG, ".ssh/child", "# child\n");
    root = open_eval(v, &r);
    CHECK(run_eval(root, &error) == 0 && open_next_jump(root, &one) == 0 && one != NULL);
    CHECK(run_eval(one, &error) == -1 && error.reason == CTOS_CONFIG_LIMIT);
    CHECK(ctos_ssh_config_eval_usage(root, usage) == 0 && usage[0] == 1024);
    close_jump(one);
    CHECK(open_next_jump(root, &one) == 0 && one != NULL);
    CHECK(run_eval(one, &error) == -1 && error.reason == CTOS_CONFIG_LIMIT);
    CHECK(ctos_ssh_config_eval_usage(root, usage) == 0 && usage[0] == 1024);
    close_jump(one); finish(root, v); free(body);

    size_t large_len = 128U * 7000U;
    char *large = malloc(large_len + 1); CHECK(large != NULL);
    memset(large, '#', large_len); large[large_len] = 0;
    for (size_t i = 6999; i < large_len; i += 7000) large[i] = '\n';
    body = malloc(2048); CHECK(body != NULL); n = 0;
    for (unsigned int i = 0; i < 40; i++) n += (size_t)snprintf(body + n, 2048 - n, "Include large\n");
    snprintf(body + n, 2048 - n, "Host alias\n ProxyJump one\nHost one\n ProxyJump none\n");
    v = view_new(body); add_file(v, CTOS_SSH_CONFIG, ".ssh/large", large);
    root = open_eval(v, &r);
    CHECK(run_eval(root, &error) == 0 && ctos_ssh_config_eval_usage(root, usage) == 0);
    CHECK(usage[1] > CTOS_SSH_IO_READ_BYTES / 2);
    CHECK(open_next_jump(root, &one) == 0 && one != NULL);
    CHECK(run_eval(one, &error) == -1 && error.reason == CTOS_CONFIG_LIMIT);
    CHECK(ctos_ssh_config_eval_usage(root, usage) == 0 && usage[1] <= CTOS_SSH_IO_READ_BYTES);
    CHECK(CTOS_SSH_IO_READ_BYTES - usage[1] < large_len);
    close_jump(one); finish(root, v); free(body); free(large);
}
static void jump_components(void) {
    struct ctos_ssh_config_request r = request();
    struct ctos_ssh_config_error error;
    char name[257], body[1024];
    memset(name, 'h', 255); name[255] = 0;
    snprintf(body, sizeof(body), "ProxyJump %s\n", name);
    struct ctos_ssh_view *v = view_new(body);
    struct ctos_ssh_config_eval *e = open_eval(v, &r);
    CHECK(run_eval(e, &error) == 0);
    const Options *o = NULL;
    CHECK(ctos_ssh_config_eval_options(e, &o) == 0 && strlen(o->jump_host) == 255 && strcmp(o->jump_host, name) == 0);
    finish(e, v);
    name[255] = 'h'; name[256] = 0; snprintf(body, sizeof(body), "ProxyJump %s\n", name);
    expect_bad(body, CTOS_CONFIG_LIMIT);
    name[255] = 0; snprintf(body, sizeof(body), "ProxyJump %s@host\n", name);
    v = view_new(body); e = open_eval(v, &r); CHECK(run_eval(e, &error) == 0);
    CHECK(ctos_ssh_config_eval_options(e, &o) == 0 && strlen(o->jump_user) == 255 && strcmp(o->jump_user, name) == 0);
    finish(e, v);
    name[255] = 'h'; name[256] = 0; snprintf(body, sizeof(body), "ProxyJump %s@host\n", name);
    expect_bad(body, CTOS_CONFIG_LIMIT);
    expect_bad("ProxyJump ssh://a%ffb@host\n", CTOS_CONFIG_INPUT);
}
struct config_wire { unsigned char bytes[4096]; size_t n; };
static void wire_bytes(struct config_wire *w, const void *p, size_t n) {
    CHECK(n <= sizeof(w->bytes) - w->n); memcpy(w->bytes + w->n, p, n); w->n += n;
}
static void wire_u32(struct config_wire *w, uint32_t n) {
    unsigned char b[4] = {(unsigned char)(n >> 24), (unsigned char)(n >> 16), (unsigned char)(n >> 8), (unsigned char)n};
    wire_bytes(w, b, sizeof(b));
}
static void wire_u64(struct config_wire *w, uint64_t n) { wire_u32(w, (uint32_t)(n >> 32)); wire_u32(w, (uint32_t)n); }
static void wire_text(struct config_wire *w, const char *s) { wire_u32(w, (uint32_t)strlen(s)); wire_bytes(w, s, strlen(s)); }
static void token_registry(void) {
    struct config_wire w = {0};
    unsigned char uuid[16] = {1}, binding[32] = {2}, kind = CTOS_SSH_CONFIG;
    const char *body = "User managed\nPort 2255\n";
    wire_u32(&w, 0x43535056U); wire_u32(&w, 1); wire_bytes(&w, uuid, sizeof(uuid));
    wire_u64(&w, 1); wire_u64(&w, 1); wire_u64(&w, later());
    wire_text(&w, "config-request"); wire_text(&w, "config-connection"); wire_text(&w, "ssh/root");
    wire_bytes(&w, binding, sizeof(binding)); wire_u32(&w, 1);
    wire_bytes(&w, &kind, 1); wire_text(&w, "config"); wire_text(&w, "ssh/root"); wire_text(&w, "config-identity");
    wire_u32(&w, (uint32_t)strlen(body)); wire_u32(&w, 0); wire_text(&w, body);
    ctos_ssh_view_token token = 0;
    CHECK(ctos_ssh_view_handle_create(w.bytes, w.n, &token) == 0 && token != 0);
    struct ctos_ssh_view_borrow *b = NULL;
    CHECK(ctos_ssh_view_handle_borrow(token, &b) == 0);
    struct ctos_ssh_config_request r = request();
    struct ctos_ssh_config_eval *e = NULL;
    CHECK(ctos_ssh_config_eval_open(b, &r, &e) == 0);
    struct ctos_ssh_config_error error;
    CHECK(run_eval(e, &error) == 0);
    struct ctos_ssh_config_summary summary;
    CHECK(ctos_ssh_config_eval_summary(e, &summary) == 0 && summary.port == 2255 && strcmp(summary.user, "managed") == 0);
    CHECK(ctos_ssh_view_handle_destroy(token) == 0);
    CHECK(ctos_ssh_config_eval_summary(e, &summary) == -1 && errno == EACCES && summary.hostname[0] == 0);
    CHECK(ctos_ssh_config_eval_close(e) == 0);
    CHECK(ctos_ssh_config_eval_destroy(e) == 0);
    struct ctos_ssh_view_summary old;
    CHECK(ctos_ssh_view_handle_summary(token, &old) == -1);
}

static void borrow_exclusivity(void) {
    struct ctos_ssh_view *view = view_new("User managed\nPort 2255\n");
    struct ctos_ssh_view_borrow *borrow = NULL;
    struct ctos_ssh_view_borrow_claim *competing = NULL;
    struct ctos_ssh_config_eval *first = NULL, *second = NULL;
    struct ctos_ssh_config_request r = request();
    struct ctos_ssh_config_error error;
    int other_owner = 0;

    CHECK(ctos_ssh_view_borrow_owned_view(view, &borrow) == 0);
    CHECK(ctos_ssh_view_borrow_claim(borrow, &other_owner, &competing) == 0);
    CHECK(ctos_ssh_config_eval_open(borrow, &r, &first) != 0 && errno == EBUSY && first == NULL);
    CHECK(ctos_ssh_view_borrow_release(borrow) != 0 && errno == EBUSY);
    ctos_ssh_view_borrow_claim_abort(competing);
    competing = NULL;
    struct ctos_ssh_slice original_root = view->root;
    view->root = slice("../bad");
    CHECK(ctos_ssh_config_eval_open(borrow, &r, &first) != 0 && first == NULL);
    view->root = original_root;
    CHECK(ctos_ssh_view_borrow_claim(borrow, &other_owner, &competing) == 0);
    ctos_ssh_view_borrow_claim_abort(competing);
    competing = NULL;
    CHECK(ctos_ssh_config_eval_open(borrow, &r, &first) == 0 && first != NULL);
    CHECK(ctos_ssh_view_borrow_claim(borrow, &other_owner, &competing) != 0
        && errno == EBUSY && competing == NULL);
    CHECK(ctos_ssh_view_borrow_release(borrow) != 0 && errno == EBUSY);
    CHECK(ctos_ssh_config_eval_open(borrow, &r, &second) != 0 && errno == EBUSY && second == NULL);
    CHECK(run_eval(first, &error) == 0);
    struct ctos_ssh_config_summary summary;
    CHECK(ctos_ssh_config_eval_summary(first, &summary) == 0 && summary.port == 2255);
    finish(first, view);
}

static void borrow_error_precedence(void) {
    struct ctos_ssh_view *view = view_new("User managed\n");
    struct ctos_ssh_view_borrow *borrow = NULL;
    struct ctos_ssh_config_eval *eval = NULL;
    struct ctos_ssh_config_request r = request();
    CHECK(ctos_ssh_view_borrow_owned_view(view, &borrow) == 0);
    r.alias = "-invalid";
    view->deadline = 1;
    CHECK(ctos_ssh_config_eval_open(borrow, &r, &eval) != 0 && errno == ETIMEDOUT && eval == NULL);
    CHECK(ctos_ssh_config_eval_open_catalog(borrow, &eval) != 0 && errno == ETIMEDOUT && eval == NULL);
    CHECK(ctos_ssh_config_eval_open(borrow, NULL, &eval) != 0 && errno == EINVAL && eval == NULL);
    r.port = 65536U;
    CHECK(ctos_ssh_config_eval_open(borrow, &r, &eval) != 0 && errno == EINVAL && eval == NULL);
    r.port = 0;
    view->valid = 0;
    CHECK(ctos_ssh_config_eval_open(borrow, &r, &eval) != 0 && errno == EACCES && eval == NULL);
    CHECK(ctos_ssh_config_eval_open_catalog(borrow, &eval) != 0 && errno == EACCES && eval == NULL);
    CHECK(ctos_ssh_config_eval_open(NULL, &r, &eval) != 0 && errno == EINVAL && eval == NULL);
    view->valid = 1;
    view->deadline = later();
    CHECK(ctos_ssh_config_eval_open(borrow, &r, &eval) != 0 && errno == EINVAL && eval == NULL);
    CHECK(ctos_ssh_view_borrow_release(borrow) == 0);
    free(view);
}
static unsigned int descriptor_count(void) {
    unsigned int n = 0;
    for (int fd = 0; fd < 1024; fd++) if (fcntl(fd, F_GETFD) >= 0) n++;
    return n;
}

int main(void) {
    unsigned int before = descriptor_count();
    good(); errors(); include(); retention(); user_tokens(); lifecycle(); limits(); route_bounds(); jump_components(); jump_declarations();
    jump_effective_config(); jump_lifetime_and_limits(); jump_route(); profile_jump_route(); jump_route_low_memlock(); jump_shared_budgets(); token_registry();
    borrow_exclusivity();
    borrow_error_precedence();
    for (unsigned int i = 0; i < 40; i++) good();
    CHECK(descriptor_count() == before);
    CHECK(effects == 0);
    printf("SSH_CONFIG_EVALUATOR_TEST checks=%u effects=%u gate=pass\n", checks, effects);
    return 0;
}

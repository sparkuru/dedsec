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
static unsigned int descriptor_count(void) {
    unsigned int n = 0;
    for (int fd = 0; fd < 1024; fd++) if (fcntl(fd, F_GETFD) >= 0) n++;
    return n;
}

int main(void) {
    unsigned int before = descriptor_count();
    good(); errors(); include(); retention(); user_tokens(); lifecycle(); limits(); route_bounds(); jump_components(); token_registry();
    for (unsigned int i = 0; i < 40; i++) good();
    CHECK(descriptor_count() == before);
    CHECK(effects == 0);
    printf("SSH_CONFIG_EVALUATOR_TEST checks=%u effects=%u gate=pass\n", checks, effects);
    return 0;
}

/* Shared observers and the complete direct-detached regression remain one
 * source of truth. Both files are declared runner inputs. */
#define main ctos_detached_baseline_main
#define __wrap_fread baseline_fread
#include "ssh_config_detached_test.c"
#undef __wrap_fread
#undef main
#include "ssh_config_handle.h"

static ctos_ssh_config_token paused_token, competing_token;
static int pause_cancel, pause_reenter, getter_cancel, getter_expire;
int __real_ctos_ssh_config_eval_audit(const struct ctos_ssh_config_eval *, const struct ctos_ssh_config_audit **);
int __wrap_ctos_ssh_config_eval_audit(const struct ctos_ssh_config_eval *e, const struct ctos_ssh_config_audit **out) {
    int ret = __real_ctos_ssh_config_eval_audit(e, out);
    if (ret == 0 && getter_cancel) { getter_cancel = 0; ctos_ssh_config_eval_cancel((struct ctos_ssh_config_eval *)e); }
    if (ret == 0 && getter_expire) { getter_expire = 0; clock_offset = 60001; }
    return ret;
}
static void *cancel_audit(void *opaque) {
    CHECK(ctos_ssh_config_handle_cancel(*(ctos_ssh_config_token *)opaque) == 0); return NULL;
}
size_t __wrap_fread(void *p, size_t size, size_t n, FILE *f) {
    if (paused_token != 0 && pause_reenter) {
        pause_reenter = 0;
        int32_t result[5]; unsigned char out[CTOS_SSH_CONFIG_AUDIT_BYTES]; size_t length;
        CHECK(ctos_ssh_config_handle_run(paused_token, result) == 0 && result[4] == EBUSY);
        CHECK(ctos_ssh_config_handle_close(paused_token) == -1 && errno == EBUSY);
        CHECK(ctos_ssh_config_handle_destroy(paused_token) == -1 && errno == EBUSY);
        CHECK(ctos_ssh_config_handle_audit(paused_token, out, &length) == -1 && errno == EBUSY);
        CHECK(ctos_ssh_config_handle_run(competing_token, result) == 0 && result[4] == EBUSY);
    }
    if (paused_token != 0 && pause_cancel) {
        pause_cancel = 0; pthread_t t;
        CHECK(pthread_create(&t, NULL, cancel_audit, &paused_token) == 0); CHECK(pthread_join(t, NULL) == 0);
    }
    return baseline_fread(p, size, n, f);
}
struct audit_input { unsigned char *data; size_t length, capacity; };
static void add_bytes(struct audit_input *w, const void *p, size_t n) {
    CHECK(n <= w->capacity - w->length);
    if (n) memcpy(w->data + w->length, p, n); w->length += n;
}
static void add_u32(struct audit_input *w, uint32_t n) {
    unsigned char b[4] = {n >> 24, n >> 16, n >> 8, n}; add_bytes(w, b, 4);
}
static uint32_t u32_at(const unsigned char *p) { return (uint32_t)p[0] << 24 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 8 | p[3]; }
static struct audit_input input_new(unsigned int count) {
    struct audit_input w = {.data = malloc(1024 * 1024), .capacity = 1024 * 1024}; CHECK(w.data != NULL);
    add_u32(&w, 0x43534449); add_u32(&w, 1); add_u32(&w, count);
    add_u32(&w, 1024); add_u32(&w, CTOS_SSH_IO_READ_BYTES); add_u32(&w, CTOS_SSH_CONFIG_LINES); return w;
}
static void input_file(struct audit_input *w, unsigned int type, const char *path, const char *body) {
    add_u32(w, type); add_u32(w, (uint32_t)strlen(path)); add_bytes(w, path, strlen(path));
    size_t n = body == NULL ? 0 : strlen(body); add_u32(w, (uint32_t)n); add_bytes(w, body, n);
}
static struct audit_input input_one(const char *body) {
    struct audit_input w = input_new(1); input_file(&w, CTOS_SSH_CONFIG, "alternate/root", body); return w;
}
static void input_free(struct audit_input *w) { memset(w->data, 0, w->capacity); free(w->data); memset(w, 0, sizeof(*w)); }
struct audit_copy { struct audit_input *source, *request; ctos_ssh_config_token token; int fail, cancel; };
static int copy_input(void *opaque, int request_copy, unsigned char *out, size_t n) {
    struct audit_copy *copy = opaque;
    struct audit_input *w = request_copy ? copy->request : copy->source;
    CHECK(w != NULL && n == w->length && owned_mapping != NULL);
    memcpy(out, w->data, copy->fail ? n / 2 : n);
    if (copy->cancel) {
        pthread_t t; CHECK(pthread_create(&t, NULL, cancel_audit, &copy->token) == 0); CHECK(pthread_join(t, NULL) == 0);
    }
    if (copy->fail) { errno = EIO; return -1; }
    return 0;
}
static ctos_ssh_config_token reserve_audit(void) {
    ctos_ssh_config_token token = 0; CHECK(ctos_ssh_config_handle_reserve_audit(&token) == 0 && token != 0); return token;
}
static int load_input(ctos_ssh_config_token token, struct audit_input *w, unsigned int root, struct audit_input *request,
    struct audit_copy *copy) {
    struct audit_copy local = {0}; if (copy == NULL) copy = &local;
    copy->source = w; copy->request = request; copy->token = token; capture_owned = 1;
    int ret = ctos_ssh_config_handle_load_detached(token, w->length, root, later(), request != NULL,
        request == NULL ? 0 : request->length, copy_input, copy);
    capture_owned = 0; return ret;
}
static ctos_ssh_config_token audit_body(const char *body) {
    struct audit_input w = input_one(body); ctos_ssh_config_token token = reserve_audit();
    CHECK(load_input(token, &w, 0, NULL, NULL) == 0); input_free(&w); return token;
}
static void finish_token(ctos_ssh_config_token token) {
    CHECK(ctos_ssh_config_handle_close(token) == 0); CHECK(ctos_ssh_config_handle_close(token) == 0);
    CHECK(ctos_ssh_config_handle_destroy(token) == 0);
}
static void run_audit_ok(ctos_ssh_config_token token) {
    int32_t result[5]; CHECK(ctos_ssh_config_handle_run(token, result) == 0 && result[0] == 0);
}
static void audit_reject(const char *body, unsigned int reason, unsigned int line) {
    ctos_ssh_config_token token = audit_body(body); int32_t result[5];
    CHECK(ctos_ssh_config_handle_run(token, result) == 0 && result[0] == 1);
    CHECK(result[1] == (int32_t)reason && result[2] == 1 && result[3] == (int32_t)line);
    unsigned char out[CTOS_SSH_CONFIG_AUDIT_BYTES]; size_t n = 999;
    memset(out, 0xa5, sizeof(out)); CHECK(ctos_ssh_config_handle_audit(token, out, &n) == -1 && n == 0); zeroed(out, sizeof(out));
    finish_token(token);
}
static void policy_forms(void) {
    const char *good[] = {
        "ProxyCommand none\nLocalCommand NoNe\nKnownHostsCommand=none\n",
        "PermitLocalCommand no\nProxyUseFdpass false\nForwardAgent NO\nAddKeysToAgent false\n",
        "ForwardX11 no\nForwardX11Trusted false\nHostbasedAuthentication no\nEnableSSHKeysign false\n",
        "ForkAfterAuthentication no\nEnableEscapeCommandline false\nControlMaster false\nControlPersist no\nTunnel false\n",
        "IdentityAgent 'none'\nSecurityKeyProvider none\nControlPath NoNe\n",
        "StrictHostKeyChecking true\nStrictHostKeyChecking ask\nVerifyHostKeyDNS false\nNoHostAuthenticationForLocalhost no\n",
        "CanonicalizeHostname false\nCanonicalizePermittedCNAMEs 'none'\n",
        "Port invalid\nProxyJump user@hop:22\nLocalForward malformed\nRemoteCommand echo anything\nRefuseConnection no-such-context\n",
        "Protocol anything\nCipher obsolete\nIgnoreUnknown *\n"
    };
    for (unsigned int i = 0; i < sizeof(good) / sizeof(good[0]); i++) {
        case_index = 100 + i; ctos_ssh_config_token token = audit_body(good[i]); run_audit_ok(token); finish_token(token);
    }
    struct { const char *body; unsigned int reason; } bad[] = {
        {"ProxyCommand 'none'\n", CTOS_CONFIG_COMMAND}, {"LocalCommand none # comment\n", CTOS_CONFIG_COMMAND},
        {"KnownHostsCommand none extra\n", CTOS_CONFIG_COMMAND}, {"LocalCommand non\\e\n", CTOS_CONFIG_COMMAND},
        {"ForwardAgent none\n", CTOS_CONFIG_EXTERNAL}, {"ForwardAgent /socket\n", CTOS_CONFIG_EXTERNAL},
        {"AddKeysToAgent 0\n", CTOS_CONFIG_EXTERNAL}, {"AddKeysToAgent confirm 5m\n", CTOS_CONFIG_EXTERNAL},
        {"ControlMaster auto\n", CTOS_CONFIG_EXTERNAL}, {"ControlPersist NO\n", CTOS_CONFIG_EXTERNAL},
        {"ControlPersist 0\n", CTOS_CONFIG_EXTERNAL}, {"Tunnel point-to-point\n", CTOS_CONFIG_EXTERNAL},
        {"IdentityAgent ${ENV}\n", CTOS_CONFIG_EXTERNAL}, {"SecurityKeyProvider provider\n", CTOS_CONFIG_EXTERNAL},
        {"StrictHostKeyChecking no\n", CTOS_CONFIG_TRUST}, {"StrictHostKeyChecking off\n", CTOS_CONFIG_TRUST},
        {"StrictHostKeyChecking accept-new\n", CTOS_CONFIG_TRUST}, {"VerifyHostKeyDNS ask\n", CTOS_CONFIG_TRUST},
        {"NoHostAuthenticationForLocalhost yes\n", CTOS_CONFIG_TRUST}, {"CanonicalizeHostname always\n", CTOS_CONFIG_UNSUPPORTED},
        {"CanonicalizePermittedCNAMEs *:*\n", CTOS_CONFIG_UNSUPPORTED}, {"CanonicalizePermittedCNAMEs none extra\n", CTOS_CONFIG_UNSUPPORTED},
        {"PKCS11Provider none\n", CTOS_CONFIG_UNSUPPORTED}, {"GSSAPIAuthentication no\n", CTOS_CONFIG_UNSUPPORTED},
        {"IgnoreUnknown *\nUnknownDirective yes\n", CTOS_CONFIG_INPUT}
    };
    for (unsigned int i = 0; i < sizeof(bad) / sizeof(bad[0]); i++) {
        case_index = 200 + i; audit_reject(bad[i].body, bad[i].reason, i == 24 ? 2 : 1);
    }
    const char *flags[] = {"PermitLocalCommand", "ProxyUseFdpass", "ForwardX11", "ForwardX11Trusted", "HostbasedAuthentication",
        "EnableSSHKeysign", "ForkAfterAuthentication", "EnableEscapeCommandline"};
    for (unsigned int i = 0; i < sizeof(flags) / sizeof(flags[0]); i++) {
        char body[256]; snprintf(body, sizeof(body), "Host ignored\n%s true\n", flags[i]);
        audit_reject(body, CTOS_CONFIG_EXTERNAL, 2);
    }
    audit_reject("ProxyCommand none\nProxyCommand echo forbidden\n", CTOS_CONFIG_COMMAND, 2);
    audit_reject("Host ignored\nLocalCommand echo forbidden\n", CTOS_CONFIG_COMMAND, 2);
    audit_reject("StrictHostKeyChecking ask\nStrictHostKeyChecking no\n", CTOS_CONFIG_TRUST, 2);
    audit_reject("PermitLocalCommand no\nLocalCommand echo forbidden\n", CTOS_CONFIG_COMMAND, 2);
    audit_reject("ForwardX11 maybe\n", CTOS_CONFIG_INPUT, 1);
    audit_reject("StrictHostKeyChecking maybe\n", CTOS_CONFIG_INPUT, 1);
    audit_reject("CanonicalizeHostname maybe\n", CTOS_CONFIG_INPUT, 1);
    audit_reject("IdentityAgent none extra\n", CTOS_CONFIG_INPUT, 1);
    audit_reject("ControlPersist no extra\n", CTOS_CONFIG_INPUT, 1);
    audit_reject("IdentityAgent ''\n", CTOS_CONFIG_INPUT, 1);
}
static void match_grammar(void) {
    const char *good[] = {"Match all\n", "Match final all\n", "Match canonical all\n", "Match host alias all\n",
        "Match !host=alias user somebody originalhost=alias localuser exec version=*\n",
        "Match host exec\n", "Match command \"exec\"\n", "Match command \"\" tagged=\n",
        "Match sessiontype exec\n", "Match !final !canonical\n", "Match host alias host other\n",
        "Match all \"\"\n", "Match final all \"\"\n"};
    for (unsigned int i = 0; i < sizeof(good) / sizeof(good[0]); i++) {
        case_index = 300 + i; ctos_ssh_config_token token = audit_body(good[i]); run_audit_ok(token); finish_token(token);
    }
    struct { const char *body; unsigned int reason; } bad[] = {
        {"Match exec true\n", CTOS_CONFIG_COMMAND}, {"Match !exec=true\n", CTOS_CONFIG_COMMAND},
        {"Match host missing exec=false\n", CTOS_CONFIG_COMMAND}, {"Match !localnetwork=bogus\n", CTOS_CONFIG_UNSUPPORTED},
        {"Match exec\n", CTOS_CONFIG_INPUT}, {"Match exec=\n", CTOS_CONFIG_INPUT},
        {"Match exec true unknown argument\n", CTOS_CONFIG_INPUT}, {"Match localnetwork\n", CTOS_CONFIG_INPUT},
        {"Match user\n", CTOS_CONFIG_INPUT}, {"Match sessiontype=exec\n", CTOS_CONFIG_INPUT},
        {"Match host=\n", CTOS_CONFIG_INPUT}, {"Match all host alias\n", CTOS_CONFIG_INPUT},
        {"Match final canonical all\n", CTOS_CONFIG_INPUT}, {"Match !!exec true\n", CTOS_CONFIG_INPUT},
        {"Match tagged\n", CTOS_CONFIG_INPUT}, {"Match host ''\n", CTOS_CONFIG_INPUT},
        {"Match all \"\" exec foo\n", CTOS_CONFIG_INPUT}, {"Match all \"\" host foo\n", CTOS_CONFIG_INPUT},
        {"Match exec foo all \"\" junk\n", CTOS_CONFIG_INPUT}
    };
    for (unsigned int i = 0; i < sizeof(bad) / sizeof(bad[0]); i++) { case_index = 350 + i; audit_reject(bad[i].body, bad[i].reason, 1); }
}
static void edge(const unsigned char *out, unsigned int i, unsigned int parent, unsigned int line, unsigned int child) {
    const unsigned char *p = out + 20 + u32_at(out + 12) + i * 12;
    CHECK(u32_at(p) == parent && u32_at(p + 4) == line && u32_at(p + 8) == child);
}
static void static_edges(void) {
    struct audit_input w = input_new(6);
    input_file(&w, CTOS_SSH_KNOWN_HOSTS, "trust", NULL);
    input_file(&w, CTOS_SSH_CONFIG, "nondefault/root", "Host no\nInclude conf/* conf/a %h\nInclude conf/a\nHost root\n");
    input_file(&w, CTOS_SSH_CONFIG, ".ssh/conf/z", "Host z\nInclude nested\n");
    input_file(&w, CTOS_SSH_CONFIG, ".ssh/conf/a", "Host a\nInclude nested\n");
    input_file(&w, CTOS_SSH_CONFIG, ".ssh/nested", "Host nested\n");
    input_file(&w, CTOS_SSH_CONFIG, "orphan", "Host orphan\n");
    ctos_ssh_config_token token = reserve_audit(); CHECK(load_input(token, &w, 1, NULL, NULL) == 0); run_audit_ok(token);
    unsigned char out[CTOS_SSH_CONFIG_AUDIT_BYTES]; size_t n;
    CHECK(ctos_ssh_config_handle_audit(token, out, &n) == 0);
    CHECK(u32_at(out) == 0x43534341 && u32_at(out + 4) == 1 && u32_at(out + 8) == 1 && u32_at(out + 16) == 5);
    CHECK(n == 20 + u32_at(out + 12) + 5 * 12 && u32_at(out + 20) == 0x43534344);
    CHECK(u32_at(out + 32) == 4 && u32_at(out + 40) == 1);
    edge(out, 0, 2, 2, 4); edge(out, 1, 4, 2, 5); edge(out, 2, 2, 2, 3); edge(out, 3, 3, 2, 5); edge(out, 4, 2, 3, 4);
    unsigned char catalog[CTOS_SSH_CONFIG_CATALOG_BYTES]; size_t cn;
    ctos_ssh_config_token catalog_token = 0; CHECK(ctos_ssh_config_handle_reserve_detached(1, &catalog_token) == 0);
    CHECK(load_input(catalog_token, &w, 1, NULL, NULL) == 0); run_audit_ok(catalog_token);
    CHECK(ctos_ssh_config_handle_catalog(catalog_token, catalog, &cn) == 0);
    CHECK(cn == u32_at(out + 12) && memcmp(catalog, out + 20, cn) == 0);
    CHECK(ctos_ssh_config_handle_audit(catalog_token, out, &n) == -1 && errno == EINVAL && n == 0); zeroed(out, sizeof(out));
    CHECK(ctos_ssh_config_handle_catalog(token, catalog, &cn) == -1 && errno == EINVAL && cn == 0);
    CHECK(ctos_ssh_config_handle_summary(token, catalog, &cn) == -1 && errno == EINVAL && cn == 0);
    finish_token(catalog_token); finish_token(token);
    token = reserve_audit(); CHECK(load_input(token, &w, 5, NULL, NULL) == 0); run_audit_ok(token);
    CHECK(ctos_ssh_config_handle_audit(token, out, &n) == 0 && u32_at(out + 16) == 0 && u32_at(out + 32) == 1);
    finish_token(token); input_free(&w);
    w = input_new(2); input_file(&w, CTOS_SSH_CONFIG, "root", "Include \"child space\" %h ${ENV} %%\n");
    input_file(&w, CTOS_SSH_CONFIG, ".ssh/child space", "Host child\n");
    token = reserve_audit(); CHECK(load_input(token, &w, 0, NULL, NULL) == 0); run_audit_ok(token);
    CHECK(ctos_ssh_config_handle_audit(token, out, &n) == 0 && u32_at(out + 16) == 1 && u32_at(out + 40) == 1);
    edge(out, 0, 1, 1, 2); finish_token(token); input_free(&w);
}
static void edge_failures(void) {
    audit_reject("Include absent\n", CTOS_CONFIG_PATH, 1); audit_reject("Include ../escape\n", CTOS_CONFIG_PATH, 1);
    audit_reject("Include /etc/passwd\n", CTOS_CONFIG_PATH, 1);
    struct audit_input w = input_new(2); input_file(&w, CTOS_SSH_CONFIG, "root", "Host ignored\nInclude child\n");
    input_file(&w, CTOS_SSH_CONFIG, ".ssh/child", "LocalCommand forbidden\n");
    ctos_ssh_config_token token = reserve_audit(); CHECK(load_input(token, &w, 0, NULL, NULL) == 0);
    int32_t result[5]; CHECK(ctos_ssh_config_handle_run(token, result) == 0 && result[1] == CTOS_CONFIG_COMMAND && result[2] == 2 && result[3] == 1);
    finish_token(token); input_free(&w);
    w = input_new(2); input_file(&w, CTOS_SSH_CONFIG, "root", "Include child\n"); input_file(&w, CTOS_SSH_PRIVATE_KEY, ".ssh/child", NULL);
    token = reserve_audit(); CHECK(load_input(token, &w, 0, NULL, NULL) == 0);
    CHECK(ctos_ssh_config_handle_run(token, result) == 0 && result[1] == CTOS_CONFIG_PATH && result[2] == 1);
    finish_token(token); input_free(&w);
    w = input_new(2); input_file(&w, CTOS_SSH_CONFIG, "root", "Include child\n");
    input_file(&w, CTOS_SSH_CONFIG, ".ssh/child", "Include /ctos-vault/root\n");
    token = reserve_audit(); CHECK(load_input(token, &w, 0, NULL, NULL) == 0);
    CHECK(ctos_ssh_config_handle_run(token, result) == 0 && result[1] == CTOS_CONFIG_PATH && result[4] == ELOOP);
    finish_token(token); input_free(&w);
}
static void old_behavior(void) {
    const char *lexical[] = {"Port invalid\n", "Match nonsense args\n", "LocalCommand forbidden\n", "Match all \"\" exec foo\n"};
    for (unsigned int i = 0; i < sizeof(lexical) / sizeof(lexical[0]); i++) {
        struct ctos_ssh_config_eval *e = open_body(lexical[i], 1); (void)catalog_ok(e); finish(e);
    }
    const char *effective[] = {"ProxyCommand none\nProxyCommand forbidden\nUser remote\n",
        "Host ignored\nLocalCommand forbidden\nHost alias\nUser remote\n", "StrictHostKeyChecking ask\nStrictHostKeyChecking no\nUser remote\n"};
    for (unsigned int i = 0; i < 3; i++) {
        struct ctos_ssh_config_eval *e = open_body(effective[i], 0); struct ctos_ssh_config_error error;
        CHECK(run(e, &error) == 0); finish(e);
    }
    ctos_ssh_config_token token = 99; CHECK(ctos_ssh_config_handle_reserve_detached(2, &token) == -1 && errno == EINVAL && token == 0);
}
static void *wrong_audit_owner(void *opaque) {
    ctos_ssh_config_token token = *(ctos_ssh_config_token *)opaque;
    unsigned char out[CTOS_SSH_CONFIG_AUDIT_BYTES]; size_t n;
    CHECK(ctos_ssh_config_handle_audit(token, out, &n) == -1 && errno == EPERM && n == 0);
    CHECK(ctos_ssh_config_handle_audit_live(token) == -1 && errno == EPERM); return NULL;
}
static void lifecycle(void) {
    for (unsigned int mode = 0; mode < 8; mode++) {
        case_index = 500 + mode; ctos_ssh_config_token token = audit_body("Host audited\n");
        unsigned char out[CTOS_SSH_CONFIG_AUDIT_BYTES]; size_t n; int32_t result[5];
        if (mode == 0) {
            pthread_t t; CHECK(pthread_create(&t, NULL, wrong_audit_owner, &token) == 0); CHECK(pthread_join(t, NULL) == 0);
            run_audit_ok(token);
        } else if (mode == 1) {
            paused_token = token; pause_cancel = 1;
            CHECK(ctos_ssh_config_handle_run(token, result) == 0 && result[1] == CTOS_CONFIG_LIFETIME);
            paused_token = 0;
        } else if (mode == 2) {
            paused_token = token; competing_token = audit_body("Host competing\n"); pause_reenter = 1;
            run_audit_ok(token); run_audit_ok(competing_token); finish_token(competing_token); competing_token = paused_token = 0;
        } else if (mode == 3 || mode == 4) {
            run_audit_ok(token); getter_cancel = mode == 3; getter_expire = mode == 4;
            CHECK(ctos_ssh_config_handle_audit(token, out, &n) == -1 && n == 0); zeroed(out, sizeof(out));
        } else if (mode == 5) {
            fail_unmap = 2;
            CHECK(ctos_ssh_config_handle_run(token, result) == 0 && result[1] == CTOS_CONFIG_IO);
            CHECK(ctos_ssh_config_handle_close(token) == -1);
            CHECK(ctos_ssh_config_handle_destroy(token) == -1 && errno == EBUSY);
        } else {
            run_audit_ok(token); fake_pid = mode == 6; fake_uid = mode == 7;
            CHECK(ctos_ssh_config_handle_audit(token, out, &n) == -1 && errno == EPERM);
            fake_pid = fake_uid = 0;
        }
        finish_token(token); clock_offset = 0;
        CHECK(ctos_ssh_config_handle_audit_live(token) == -1 && errno == ENOENT);
    }
    struct audit_input w = input_one("Host partial-copy\n");
    for (unsigned int mode = 0; mode < 3; mode++) {
        ctos_ssh_config_token token = reserve_audit(); struct audit_copy copy = {.fail = mode == 0, .cancel = mode == 1};
        if (mode == 2) CHECK(ctos_ssh_config_handle_cancel(token) == 0);
        CHECK(load_input(token, &w, 0, NULL, &copy) == -1);
        if (mode == 0) {
            fail_unmap = 1; CHECK(ctos_ssh_config_handle_close(token) == -1);
            int32_t result[5]; CHECK(ctos_ssh_config_handle_run(token, result) == 0 && result[4] == EIO);
            zeroed(owned_mapping, owned_length);
        }
        finish_token(token);
    }
    input_free(&w);
}
static void work_limits(void) {
    char *body = calloc(1, 16384); CHECK(body != NULL);
    size_t length = 0;
    for (unsigned int i = 0; i < 1024; i++) {
        memcpy(body + length, "Include child\n", 14); length += 14;
        if (i != 1022 && i != 1023) continue;
        struct audit_input w = input_new(2); input_file(&w, CTOS_SSH_CONFIG, "root", body); input_file(&w, CTOS_SSH_CONFIG, ".ssh/child", "#\n");
        ctos_ssh_config_token token = reserve_audit(); CHECK(load_input(token, &w, 0, NULL, NULL) == 0);
        int32_t result[5]; CHECK(ctos_ssh_config_handle_run(token, result) == 0);
        if (i == 1022) {
            CHECK(result[0] == 0); unsigned char out[CTOS_SSH_CONFIG_AUDIT_BYTES + 16]; memset(out, 0x5a, sizeof(out)); size_t n;
            CHECK(ctos_ssh_config_handle_audit(token, out, &n) == 0 && u32_at(out + 16) == 1023);
            edge(out, 0, 1, 1, 2); edge(out, 1022, 1, 1023, 2);
            for (unsigned int j = CTOS_SSH_CONFIG_AUDIT_BYTES; j < sizeof(out); j++) CHECK(out[j] == 0x5a);
            uint32_t usage[3]; CHECK(ctos_ssh_config_handle_usage(token, usage) == 0 && usage[0] == 1024 && usage[2] == 2046);
        } else CHECK(result[1] == CTOS_CONFIG_LIMIT);
        finish_token(token); input_free(&w);
    }
    free(body);
}
int main(void) {
    CHECK(ctos_detached_baseline_main() == 0);
    unsigned int before = fd_count(); observe = 1;
    policy_forms(); match_grammar(); static_edges(); edge_failures(); old_behavior(); lifecycle(); work_limits();
    CHECK(fd_count() == before && effects == 0 && capabilities == 0 && owned_mapping == NULL);
    for (unsigned int i = 0; i < 256; i++) CHECK(managed[i].p == NULL);
    observe = 0;
    printf("SSH_CONFIG_AUDIT checks=%u effects=%u capabilities=%u baseline=included gate=pass\n", checks, effects, capabilities); return 0;
}

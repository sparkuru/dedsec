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
int __real_ctos_ssh_config_eval_resource_audit(const struct ctos_ssh_config_eval *, const struct ctos_ssh_config_resource_audit **);
int __wrap_ctos_ssh_config_eval_resource_audit(const struct ctos_ssh_config_eval *e, const struct ctos_ssh_config_resource_audit **out) {
    int ret = __real_ctos_ssh_config_eval_resource_audit(e, out);
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
        int32_t result[5]; unsigned char out[CTOS_SSH_CONFIG_RESOURCE_BYTES]; size_t length;
        CHECK(ctos_ssh_config_handle_run(paused_token, result) == 0 && result[4] == EBUSY);
        CHECK(ctos_ssh_config_handle_close(paused_token) == -1 && errno == EBUSY);
        CHECK(ctos_ssh_config_handle_destroy(paused_token) == -1 && errno == EBUSY);
        CHECK(ctos_ssh_config_handle_resource_audit(paused_token, out, &length) == -1 && errno == EBUSY);
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
    ctos_ssh_config_token token = 0; CHECK(ctos_ssh_config_handle_reserve_resource_audit(&token) == 0 && token != 0); return token;
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
static struct audit_input resource_input(const char *body) {
    struct audit_input w = input_new(11);
    input_file(&w, CTOS_SSH_CONFIG, "alternate/root", body);
    input_file(&w, CTOS_SSH_PRIVATE_KEY, "keys/private", NULL);
    input_file(&w, CTOS_SSH_PUBLIC_KEY, "keys/public", NULL);
    input_file(&w, CTOS_SSH_CERTIFICATE, "keys/cert", NULL);
    input_file(&w, CTOS_SSH_KNOWN_HOSTS, "trust/user", NULL);
    input_file(&w, CTOS_SSH_KNOWN_HOSTS, "trust/global", NULL);
    input_file(&w, CTOS_SSH_PUBLIC_KEY, "trust/revoked", NULL);
    input_file(&w, CTOS_SSH_PRIVATE_KEY, "keys/$literal", NULL);
    input_file(&w, CTOS_SSH_KNOWN_HOSTS, "trust/$literal", NULL);
    input_file(&w, CTOS_SSH_PUBLIC_KEY, "keys/missing.pub", NULL);
    input_file(&w, CTOS_SSH_CERTIFICATE, "keys/missing-cert.pub", NULL);
    return w;
}
static ctos_ssh_config_token resource_body(const char *body) {
    struct audit_input w = resource_input(body); ctos_ssh_config_token token = reserve_audit();
    CHECK(load_input(token, &w, 0, NULL, NULL) == 0); input_free(&w); return token;
}
static void row(const unsigned char *out, unsigned int i, unsigned int kind, unsigned int parent,
    unsigned int line, unsigned int argument, unsigned int state, unsigned int target) {
    const unsigned char *p = out + 20 + u32_at(out + 12) + i * 24;
    CHECK(u32_at(p) == kind && u32_at(p + 4) == parent && u32_at(p + 8) == line &&
        u32_at(p + 12) == argument && u32_at(p + 16) == state && u32_at(p + 20) == target);
}
static void declaration_rows(void) {
    const char *body = "Host ignored\nIdentityFile ~/keys/private\nIdentityFile /ctos-vault/keys/public\n"
        "IdentityFile ~/keys/cert\nCertificateFile ~/keys/cert\nUserKnownHostsFile ~/trust/user ~/trust/user\n"
        "GlobalKnownHostsFile /ctos-vault/trust/global\nRevokedHostKeys ~/trust/revoked\n"
        "IdentityFile '~/keys/$literal'\nGlobalKnownHostsFile '~/trust/$literal'\n"
        "IdentityFile none\nCertificateFile none\nUserKnownHostsFile NoNe\nGlobalKnownHostsFile none\nRevokedHostKeys none\n"
        "IdentityFile keys/private\nUserKnownHostsFile trust/user\n"
        "IdentityFile ~/keys/%r\nCertificateFile ~/keys/${USER}\nUserKnownHostsFile ~/trust/%%\nRevokedHostKeys ~/trust/${KEY}\n"
        "IdentityFile ~/keys/missing\nCertificateFile ~/keys/missing-cert\n";
    ctos_ssh_config_token token = resource_body(body); run_audit_ok(token);
    unsigned char out[CTOS_SSH_CONFIG_RESOURCE_BYTES + 16]; size_t n; memset(out, 0x5a, sizeof(out));
    CHECK(ctos_ssh_config_handle_resource_audit(token, out, &n) == 0);
    CHECK(u32_at(out) == 0x43534352 && u32_at(out + 4) == 1 && u32_at(out + 8) == 1 && u32_at(out + 16) == 23);
    CHECK(n == 20 + u32_at(out + 12) + 23 * 24 && u32_at(out + 20) == 0x43534341);
    row(out, 0, 1, 1, 2, 1, 1, 2); row(out, 1, 1, 1, 3, 1, 1, 3); row(out, 2, 1, 1, 4, 1, 1, 4);
    row(out, 3, 2, 1, 5, 1, 1, 4); row(out, 4, 3, 1, 6, 1, 1, 5); row(out, 5, 3, 1, 6, 2, 1, 5);
    row(out, 6, 4, 1, 7, 1, 1, 6); row(out, 7, 5, 1, 8, 1, 1, 7);
    row(out, 8, 1, 1, 9, 1, 1, 8); row(out, 9, 4, 1, 10, 1, 1, 9);
    row(out, 10, 1, 1, 11, 1, 4, 0); row(out, 11, 2, 1, 12, 1, 3, 0);
    row(out, 12, 3, 1, 13, 1, 4, 0); row(out, 13, 4, 1, 14, 1, 4, 0); row(out, 14, 5, 1, 15, 1, 4, 0);
    row(out, 15, 1, 1, 16, 1, 3, 0); row(out, 16, 3, 1, 17, 1, 3, 0);
    row(out, 17, 1, 1, 18, 1, 2, 0); row(out, 18, 2, 1, 19, 1, 2, 0);
    row(out, 19, 3, 1, 20, 1, 2, 0); row(out, 20, 5, 1, 21, 1, 2, 0);
    row(out, 21, 1, 1, 22, 1, 5, 0); row(out, 22, 2, 1, 23, 1, 5, 0);
    for (unsigned int i = CTOS_SSH_CONFIG_RESOURCE_BYTES; i < sizeof(out); i++) CHECK(out[i] == 0x5a);
    uint32_t usage[3]; CHECK(ctos_ssh_config_handle_usage(token, usage) == 0 && usage[0] == 1 && usage[1] == strlen(body));
    unsigned char prior[CTOS_SSH_CONFIG_AUDIT_BYTES]; size_t prior_length;
    CHECK(ctos_ssh_config_handle_audit(token, prior, &prior_length) == -1 && errno == EINVAL);
    CHECK(ctos_ssh_config_handle_catalog(token, prior, &prior_length) == -1 && errno == EINVAL);
    finish_token(token);
}
static void declaration_errors(void) {
    struct { const char *body; unsigned int reason; } bad[] = {
        {"IdentityFile\n", CTOS_CONFIG_INPUT}, {"CertificateFile\n", CTOS_CONFIG_INPUT},
        {"UserKnownHostsFile\n", CTOS_CONFIG_INPUT}, {"GlobalKnownHostsFile\n", CTOS_CONFIG_INPUT},
        {"RevokedHostKeys\n", CTOS_CONFIG_INPUT}, {"IdentityFile ''\n", CTOS_CONFIG_INPUT},
        {"IdentityFile one two\n", CTOS_CONFIG_INPUT}, {"CertificateFile none extra\n", CTOS_CONFIG_INPUT},
        {"UserKnownHostsFile none extra\n", CTOS_CONFIG_INPUT}, {"RevokedHostKeys extra NONE\n", CTOS_CONFIG_INPUT},
        {"GlobalKnownHostsFile ''\n", CTOS_CONFIG_INPUT}, {"IdentityFile ~/trust/user\n", CTOS_CONFIG_PATH},
        {"CertificateFile ~/keys/private\n", CTOS_CONFIG_PATH}, {"UserKnownHostsFile ~/keys/private\n", CTOS_CONFIG_PATH},
        {"RevokedHostKeys ~/trust/user\n", CTOS_CONFIG_PATH}, {"UserKnownHostsFile ~/missing\n", CTOS_CONFIG_PATH},
        {"RevokedHostKeys ~/missing\n", CTOS_CONFIG_PATH}, {"IdentityFile ~\n", CTOS_CONFIG_PATH},
        {"IdentityFile ~other/key\n", CTOS_CONFIG_PATH}, {"IdentityFile /etc/${KEY}\n", CTOS_CONFIG_PATH},
        {"IdentityFile ~/keys/../missing\n", CTOS_CONFIG_PATH}, {"IdentityFile ~/keys/*\n", CTOS_CONFIG_PATH},
        {"IdentityFile ~/keys/\302\205missing\n", CTOS_CONFIG_PATH}, {"GlobalKnownHostsFile ~/trust/%h\n", CTOS_CONFIG_PATH},
        {"ProxyCommand echo forbidden\n", CTOS_CONFIG_COMMAND}, {"Match exec true\n", CTOS_CONFIG_COMMAND},
        {"StrictHostKeyChecking no\n", CTOS_CONFIG_TRUST}
    };
    for (unsigned int i = 0; i < sizeof(bad) / sizeof(bad[0]); i++) {
        case_index = 1000 + i; struct audit_input w = resource_input(bad[i].body); ctos_ssh_config_token token = reserve_audit();
        CHECK(load_input(token, &w, 0, NULL, NULL) == 0); input_free(&w);
        int32_t result[5]; CHECK(ctos_ssh_config_handle_run(token, result) == 0 && result[0] == 1);
        CHECK(result[1] == (int32_t)bad[i].reason && result[2] == 1 && result[3] == 1);
        unsigned char out[CTOS_SSH_CONFIG_RESOURCE_BYTES]; size_t n = 999;
        CHECK(ctos_ssh_config_handle_resource_audit(token, out, &n) == -1 && n == 0); zeroed(out, sizeof(out)); finish_token(token);
    }
}
static void original_ordinals(void) {
    struct audit_input w = input_new(4);
    input_file(&w, CTOS_SSH_PRIVATE_KEY, "key", NULL);
    input_file(&w, CTOS_SSH_CONFIG, "unusual/root", "Host root\nIdentityFile ~/key\nInclude child child\n");
    input_file(&w, CTOS_SSH_CONFIG, ".ssh/child", "CertificateFile none\nUserKnownHostsFile ~/hosts ~/hosts\n");
    input_file(&w, CTOS_SSH_KNOWN_HOSTS, "hosts", NULL);
    ctos_ssh_config_token token = reserve_audit(); CHECK(load_input(token, &w, 1, NULL, NULL) == 0); run_audit_ok(token);
    unsigned char out[CTOS_SSH_CONFIG_RESOURCE_BYTES], old[CTOS_SSH_CONFIG_AUDIT_BYTES]; size_t n, old_n;
    CHECK(ctos_ssh_config_handle_resource_audit(token, out, &n) == 0 && u32_at(out + 16) == 4);
    row(out, 0, 1, 2, 2, 1, 1, 1); row(out, 1, 2, 3, 1, 1, 3, 0);
    row(out, 2, 3, 3, 2, 1, 1, 4); row(out, 3, 3, 3, 2, 2, 1, 4);
    uint32_t usage[3]; CHECK(ctos_ssh_config_handle_usage(token, usage) == 0 && usage[0] == 3 && usage[2] == 7);
    ctos_ssh_config_token prior; CHECK(ctos_ssh_config_handle_reserve_audit(&prior) == 0);
    CHECK(load_input(prior, &w, 1, NULL, NULL) == 0); run_audit_ok(prior);
    CHECK(ctos_ssh_config_handle_audit(prior, old, &old_n) == 0 && old_n == u32_at(out + 12));
    CHECK(memcmp(out + 20, old, old_n) == 0);
    CHECK(ctos_ssh_config_handle_resource_audit(prior, out, &n) == -1 && errno == EINVAL);
    finish_token(prior); finish_token(token); input_free(&w);
    CHECK(ctos_ssh_config_handle_reserve_audit(&prior) == 0);
    w = input_one("IdentityFile one two\nUserKnownHostsFile ~/missing\n");
    CHECK(load_input(prior, &w, 0, NULL, NULL) == 0); run_audit_ok(prior); finish_token(prior); input_free(&w);
}
static void resource_limits(void) {
    char body[16384];
    for (unsigned int count = 32; count <= 33; count++) {
        strcpy(body, "UserKnownHostsFile");
        for (unsigned int i = 0; i < count; i++) strcat(body, " relative"); strcat(body, "\n");
        ctos_ssh_config_token token = audit_body(body); int32_t result[5]; CHECK(ctos_ssh_config_handle_run(token, result) == 0);
        CHECK(count == 32 ? result[0] == 0 : result[1] == CTOS_CONFIG_LIMIT); finish_token(token);
    }
    size_t length = 0;
    for (unsigned int line = 0; line < 5; line++) {
        length += (size_t)snprintf(body + length, sizeof(body) - length, "RevokedHostKeys");
        for (unsigned int i = 0; i < (line == 4 ? 1U : 256U); i++) length += (size_t)snprintf(body + length, sizeof(body) - length, " r");
        length += (size_t)snprintf(body + length, sizeof(body) - length, "\n");
        if (line != 3 && line != 4) continue;
        ctos_ssh_config_token token = audit_body(body); int32_t result[5]; CHECK(ctos_ssh_config_handle_run(token, result) == 0);
        if (line == 3) {
            CHECK(result[0] == 0); unsigned char out[CTOS_SSH_CONFIG_RESOURCE_BYTES]; size_t n;
            CHECK(ctos_ssh_config_handle_resource_audit(token, out, &n) == 0 && u32_at(out + 16) == 1024);
            row(out, 255, 5, 1, 1, 256, 3, 0); row(out, 256, 5, 1, 2, 1, 3, 0); row(out, 1023, 5, 1, 4, 256, 3, 0);
        } else CHECK(result[0] == 1 && result[1] == CTOS_CONFIG_LIMIT);
        finish_token(token);
    }
}
static void *wrong_audit_owner(void *opaque) {
    ctos_ssh_config_token token = *(ctos_ssh_config_token *)opaque;
    unsigned char out[CTOS_SSH_CONFIG_RESOURCE_BYTES]; size_t n;
    CHECK(ctos_ssh_config_handle_resource_audit(token, out, &n) == -1 && errno == EPERM && n == 0);
    CHECK(ctos_ssh_config_handle_resource_audit_live(token) == -1 && errno == EPERM); return NULL;
}
static void lifecycle(void) {
    for (unsigned int mode = 0; mode < 8; mode++) {
        case_index = 500 + mode; ctos_ssh_config_token token = audit_body("Host audited\n");
        unsigned char out[CTOS_SSH_CONFIG_RESOURCE_BYTES]; size_t n; int32_t result[5];
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
            CHECK(ctos_ssh_config_handle_resource_audit(token, out, &n) == -1 && n == 0); zeroed(out, sizeof(out));
        } else if (mode == 5) {
            fail_unmap = 2;
            CHECK(ctos_ssh_config_handle_run(token, result) == 0 && result[1] == CTOS_CONFIG_IO);
            CHECK(ctos_ssh_config_handle_close(token) == -1);
            CHECK(ctos_ssh_config_handle_destroy(token) == -1 && errno == EBUSY);
        } else {
            run_audit_ok(token); fake_pid = mode == 6; fake_uid = mode == 7;
            CHECK(ctos_ssh_config_handle_resource_audit(token, out, &n) == -1 && errno == EPERM);
            fake_pid = fake_uid = 0;
        }
        finish_token(token); clock_offset = 0;
        CHECK(ctos_ssh_config_handle_resource_audit_live(token) == -1 && errno == ENOENT);
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
int main(void) {
    CHECK(ctos_detached_baseline_main() == 0);
    unsigned int before = fd_count(); observe = 1;
    declaration_rows(); declaration_errors(); original_ordinals(); resource_limits(); lifecycle();
    CHECK(fd_count() == before && effects == 0 && capabilities == 0 && owned_mapping == NULL);
    for (unsigned int i = 0; i < 256; i++) CHECK(managed[i].p == NULL);
    observe = 0;
    printf("SSH_CONFIG_RESOURCE checks=%u effects=%u capabilities=%u baseline=included gate=pass\n", checks, effects, capabilities); return 0;
}

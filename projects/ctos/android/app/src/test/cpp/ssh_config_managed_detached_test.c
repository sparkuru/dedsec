/* Reuse the complete detached baseline and its managed-region/effect observers.
 * Both files are build inputs. The renamed baseline runs before this matrix. */
#define main ctos_detached_baseline_main
#define __wrap_mlock baseline_mlock
#define __wrap_madvise baseline_madvise
#include "ssh_config_detached_test.c"
#undef __wrap_madvise
#undef __wrap_mlock
#undef main
#include "ssh_config_handle.h"

static unsigned int protected_regions, locked_regions;
static ctos_ssh_config_token cancel_during_lock;
static void *cancel_token(void *p) {
    ctos_ssh_config_token token = *(ctos_ssh_config_token *)p;
    CHECK(ctos_ssh_config_handle_cancel(token) == 0); return NULL;
}
int __wrap_madvise(void *p, size_t n, int advice) {
    int result = baseline_madvise(p, n, advice);
    if (observe && advice == MADV_DONTDUMP && result == 0) protected_regions++;
    return result;
}
int __wrap_mlock(const void *p, size_t n) {
    int result = baseline_mlock(p, n);
    if (mapping_index(p) < 256 && result == 0) locked_regions++;
    if (cancel_during_lock != 0) {
        pthread_t t; CHECK(pthread_create(&t, NULL, cancel_token, &cancel_during_lock) == 0);
        CHECK(pthread_join(t, NULL) == 0); cancel_during_lock = 0;
    }
    return result;
}
struct source_wire { unsigned char *p; size_t n, capacity; };
static void append(struct source_wire *w, const void *p, size_t n) {
    CHECK(n <= w->capacity - w->n);
    if (n != 0) memcpy(w->p + w->n, p, n);
    w->n += n;
}
static void number(struct source_wire *w, uint32_t n) {
    unsigned char bytes[] = {n >> 24, n >> 16, n >> 8, n}; append(w, bytes, 4);
}
static uint32_t value(const unsigned char *p) { return (uint32_t)p[0] << 24 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 8 | p[3]; }
static void replace(struct source_wire *w, size_t offset, uint32_t n) {
    CHECK(offset + 4 <= w->n); unsigned char *p = w->p + offset;
    p[0] = n >> 24; p[1] = n >> 16; p[2] = n >> 8; p[3] = n;
}
static struct source_wire wire_new(unsigned int count, unsigned int opens, unsigned int bytes, unsigned int lines) {
    struct source_wire w = {.p = malloc(2 * CTOS_SSH_VIEW_FILE_BYTES), .capacity = 2 * CTOS_SSH_VIEW_FILE_BYTES};
    CHECK(w.p != NULL);
    number(&w, 0x43534449U); number(&w, 1); number(&w, count);
    number(&w, opens); number(&w, bytes); number(&w, lines); return w;
}
static void record(struct source_wire *w, unsigned int type, const char *path, const char *body) {
    size_t n = body == NULL ? 0 : strlen(body);
    number(w, type); number(w, (uint32_t)strlen(path)); append(w, path, strlen(path));
    number(w, (uint32_t)n); append(w, body, n);
}
static struct source_wire one(const char *body) {
    struct source_wire w = wire_new(1, 1024, CTOS_SSH_IO_READ_BYTES, CTOS_SSH_CONFIG_LINES);
    record(&w, CTOS_SSH_CONFIG, "alternate/root", body); return w;
}
static void drop(struct source_wire *w) { memset(w->p, 0, w->capacity); free(w->p); memset(w, 0, sizeof(*w)); }
struct copy_context {
    struct source_wire *source, *request;
    ctos_ssh_config_token token;
    unsigned int source_calls, request_calls;
    int abort_source, abort_request, cancel_source, reenter;
};
static int copy_wire(void *opaque, int request_copy, unsigned char *out, size_t n) {
    struct copy_context *c = opaque;
    CHECK(owned_mapping != NULL && protected_regions > 0 && locked_regions > 0);
    if (request_copy) {
        c->request_calls++; CHECK(c->request != NULL && n == c->request->n);
        if (c->abort_request) { errno = EIO; return -1; }
        memcpy(out, c->request->p, n); return 0;
    }
    c->source_calls++; CHECK(n == c->source->n);
    CHECK(out == (unsigned char *)owned_mapping + CTOS_SSH_VIEW_FILES * sizeof(struct ctos_ssh_file));
    CHECK(owned_length == CTOS_SSH_VIEW_FILES * sizeof(struct ctos_ssh_file) + n);
    if (c->reenter) {
        int32_t result[5]; uint32_t usage[3];
        CHECK(ctos_ssh_config_handle_run(c->token, result) == 0 && result[0] == 2 && result[4] == EBUSY);
        CHECK(ctos_ssh_config_handle_close(c->token) == -1 && errno == EBUSY);
        CHECK(ctos_ssh_config_handle_destroy(c->token) == -1 && errno == EBUSY);
        CHECK(ctos_ssh_config_handle_usage(c->token, usage) == -1 && errno == EBUSY);
        CHECK(ctos_ssh_config_handle_load_detached(c->token, n, 0, later(), 0, 0, copy_wire, c) == -1 && errno == EBUSY);
        /* This reserve would deadlock if allocation/copy held the registry. */
        ctos_ssh_config_token other = 0;
        CHECK(ctos_ssh_config_handle_reserve_detached(1, &other) == 0);
        CHECK(ctos_ssh_config_handle_close(other) == 0 && ctos_ssh_config_handle_destroy(other) == 0);
    }
    memcpy(out, c->source->p, c->abort_source ? n / 2 : n);
    if (c->cancel_source) {
        pthread_t t; CHECK(pthread_create(&t, NULL, cancel_token, &c->token) == 0);
        CHECK(pthread_join(t, NULL) == 0);
    }
    if (c->abort_source) { errno = EIO; return -1; }
    return 0;
}
static ctos_ssh_config_token reserve(int catalog) {
    ctos_ssh_config_token token = 0;
    CHECK(ctos_ssh_config_handle_reserve_detached(catalog, &token) == 0 && token > 0); return token;
}
static int load(ctos_ssh_config_token token, struct source_wire *w, unsigned int root, struct source_wire *request,
    struct copy_context *context) {
    struct copy_context local = {0};
    if (context == NULL) context = &local;
    context->token = token; context->source = w; context->request = request;
    capture_owned = 1;
    int ret = ctos_ssh_config_handle_load_detached(token, w->n, root, later(), request != NULL,
        request == NULL ? 0 : request->n, copy_wire, context);
    capture_owned = 0; return ret;
}
static void finish_handle(ctos_ssh_config_token token) {
    CHECK(ctos_ssh_config_handle_close(token) == 0);
    CHECK(ctos_ssh_config_handle_close(token) == 0);
    CHECK(ctos_ssh_config_handle_destroy(token) == 0);
}
static void run_ok(ctos_ssh_config_token token, uint32_t opens, uint32_t bytes, uint32_t lines) {
    int32_t result[5]; uint32_t usage[3];
    CHECK(ctos_ssh_config_handle_run(token, result) == 0 && result[0] == 0);
    CHECK(ctos_ssh_config_handle_usage(token, usage) == 0);
    CHECK(usage[0] == opens && usage[1] == bytes && usage[2] == lines);
}
static void basic_registry(void) {
    struct source_wire w = wire_new(3, 10, 1000, 10);
    record(&w, CTOS_SSH_PRIVATE_KEY, "keys/private", NULL);
    record(&w, CTOS_SSH_CONFIG, "orphan", "Host orphan\n");
    record(&w, CTOS_SSH_CONFIG, "alternate/root", "Host selected\n");
    ctos_ssh_config_token token = reserve(1);
    int32_t result[5]; uint32_t usage[3] = {1, 2, 3};
    CHECK(ctos_ssh_config_handle_run(token, result) == 0 && result[0] == 2 && result[4] == EAGAIN);
    CHECK(ctos_ssh_config_handle_usage(token, usage) == -1 && errno == EAGAIN); zeroed(usage, sizeof(usage));
    unsigned int protections = protected_regions, locks = locked_regions;
    struct copy_context context = {.reenter = 1};
    CHECK(load(token, &w, 2, NULL, &context) == 0 && context.source_calls == 1);
    CHECK(protected_regions == protections + 1 && locked_regions == locks + 1);
    CHECK(load(token, &w, 2, NULL, NULL) == -1 && errno == EALREADY);
    memset(w.p, '#', w.n);
    run_ok(token, 1, 14, 1);
    unsigned char catalog[CTOS_SSH_CONFIG_CATALOG_BYTES]; size_t n;
    CHECK(ctos_ssh_config_handle_catalog(token, catalog, &n) == 0);
    CHECK(value(catalog + 24) == 3 && value(catalog + 28) == 1 && value(catalog + 32) == 8);
    CHECK(memcmp(catalog + 36, "selected", 8) == 0);
    finish_handle(token); drop(&w);
    CHECK(ctos_ssh_config_handle_usage(token, usage) == -1 && errno == ENOENT);
}
static void expect_bad(struct source_wire *w, unsigned int root, int expected) {
    ctos_ssh_config_token token = reserve(1);
    CHECK(load(token, w, root, NULL, NULL) == -1 && errno == expected);
    int32_t result[5]; uint32_t usage[3];
    CHECK(ctos_ssh_config_handle_run(token, result) == 0 && result[0] == 2 && result[4] == expected);
    CHECK(ctos_ssh_config_handle_usage(token, usage) == -1 && errno == expected);
    CHECK(ctos_ssh_config_handle_run(token, result) == 0 && result[4] == EALREADY);
    finish_handle(token);
}
static void malformed_wire(void) {
    for (unsigned int field = 0; field < 6; field++) {
        struct source_wire w = one("Host one\n");
        uint32_t bad[] = {0, 2, 129, 1025, CTOS_SSH_IO_READ_BYTES + 1, CTOS_SSH_CONFIG_LINES + 1};
        replace(&w, field * 4, bad[field]); expect_bad(&w, 0, EINVAL); drop(&w);
    }
    struct source_wire w = one("Host one\n"); replace(&w, 8, 0); expect_bad(&w, 0, EINVAL); drop(&w);
    w = one(""); expect_bad(&w, 1, EINVAL); w.n--; expect_bad(&w, 0, EINVAL); drop(&w);
    w = one(""); number(&w, 0); expect_bad(&w, 0, EINVAL); drop(&w);
    const char *paths[] = {"", "../escape", "/absolute", "bad%path", "a//b", "a\nb", "a\302\200b", "a\377b"};
    for (unsigned int i = 0; i < sizeof(paths) / sizeof(paths[0]); i++) {
        w = wire_new(1, 10, 1000, 10); record(&w, CTOS_SSH_CONFIG, paths[i], "");
        expect_bad(&w, 0, EINVAL); drop(&w);
    }
    for (unsigned int type = 0; type <= 6; type++) {
        if (type == CTOS_SSH_CONFIG) continue;
        w = wire_new(1, 10, 1000, 10); record(&w, type, "root", "bad-secret");
        expect_bad(&w, 0, EINVAL); drop(&w);
    }
    for (unsigned int mode = 0; mode < 3; mode++) {
        w = wire_new(2, 10, 1000, 10); record(&w, CTOS_SSH_CONFIG, "root", "");
        record(&w, CTOS_SSH_CONFIG, mode == 0 ? "root" : mode == 1 ? "root/child" : "root/child/deep", "");
        expect_bad(&w, 0, EINVAL); drop(&w);
    }
    w = one(""); replace(&w, 24, CTOS_SSH_KNOWN_HOSTS); expect_bad(&w, 0, EINVAL); drop(&w);
    w = one(""); replace(&w, 28, 513); expect_bad(&w, 0, E2BIG); drop(&w);
    w = one(""); replace(&w, w.n - 4, CTOS_SSH_VIEW_FILE_BYTES + 1); expect_bad(&w, 0, E2BIG); drop(&w);
    w = one(""); size_t original = w.n; w.n = CTOS_SSH_CONFIG_SOURCE_BYTES + 1;
    expect_bad(&w, 0, E2BIG); w.n = original; drop(&w);
}
static struct source_wire request_wire(void) {
    struct source_wire w = {.p = malloc(16384), .capacity = 16384}; CHECK(w.p != NULL);
    number(&w, 0x43534551U); number(&w, 1); number(&w, 2424); number(&w, 1234);
    const char *fields[] = {"alias", "override", "", "local-only", "local.example", "", ""};
    for (unsigned int i = 0; i < 7; i++) { number(&w, (uint32_t)strlen(fields[i])); append(&w, fields[i], strlen(fields[i])); }
    return w;
}
static void request_modes(void) {
    struct source_wire w = one("HostName ACTUAL.EXAMPLE\nUser configured\nPort 2323\n"), r = request_wire();
    ctos_ssh_config_token token = reserve(0);
    CHECK(load(token, &w, 0, &r, NULL) == 0);
    /* Effective mode counts every row, independent of the catalog path. */
    run_ok(token, 1, (uint32_t)strlen("HostName ACTUAL.EXAMPLE\nUser configured\nPort 2323\n"), 3);
    unsigned char summary[CTOS_SSH_CONFIG_SUMMARY_BYTES]; size_t n;
    CHECK(ctos_ssh_config_handle_summary(token, summary, &n) == 0 && value(summary + 12) == 2424);
    finish_handle(token);
    token = reserve(1); CHECK(load(token, &w, 0, &r, NULL) == -1 && errno == EINVAL); finish_handle(token);
    token = reserve(0); CHECK(load(token, &w, 0, NULL, NULL) == -1 && errno == EINVAL); finish_handle(token);
    token = reserve(1);
    CHECK(ctos_ssh_config_handle_load_detached(token, w.n, 0, later(), 0, 1, copy_wire, NULL) == -1 && errno == EINVAL);
    finish_handle(token);
    replace(&r, 0, 0); token = reserve(0); CHECK(load(token, &w, 0, &r, NULL) == -1 && errno == EINVAL); finish_handle(token);
    drop(&w); drop(&r);
}
static void budgets(void) {
    const char *root = "Host one\nInclude child\n", *child = "Host two\n";
    size_t bytes = strlen(root) + strlen(child);
    for (unsigned int mode = 0; mode < 7; mode++) {
        struct source_wire w = wire_new(2, mode == 1 ? 1 : mode == 4 ? 0 : 2,
            mode == 2 ? (unsigned int)bytes - 1 : mode == 5 ? 0 : (unsigned int)bytes,
            mode == 3 ? 2 : mode == 6 ? 0 : 3);
        record(&w, CTOS_SSH_CONFIG, "selected", root); record(&w, CTOS_SSH_CONFIG, ".ssh/child", child);
        ctos_ssh_config_token token = reserve(1); CHECK(load(token, &w, 0, NULL, NULL) == 0);
        if (mode == 0) run_ok(token, 2, (uint32_t)bytes, 3);
        else {
            int32_t result[5]; uint32_t usage[3];
            CHECK(ctos_ssh_config_handle_run(token, result) == 0 && result[0] == 1 && result[1] == CTOS_CONFIG_LIMIT);
            CHECK(ctos_ssh_config_handle_usage(token, usage) == -1); zeroed(usage, sizeof(usage));
        }
        finish_handle(token); drop(&w);
    }
    struct source_wire w = wire_new(1, 1, 0, 0); record(&w, CTOS_SSH_CONFIG, "empty", "");
    ctos_ssh_config_token token = reserve(1); CHECK(load(token, &w, 0, NULL, NULL) == 0); run_ok(token, 1, 0, 0); finish_handle(token); drop(&w);
    w = wire_new(1, 1, 9, 1); record(&w, CTOS_SSH_CONFIG, "exact", "Host one\n");
    token = reserve(1); CHECK(load(token, &w, 0, NULL, NULL) == 0); run_ok(token, 1, 9, 1); finish_handle(token); drop(&w);
    /* Reuse actual usage as the remaining allowance for another selected
     * root. Native counters do not silently reset the whole-job allowance. */
    w = wire_new(2, 3, (unsigned int)(bytes + strlen(child)), 4);
    record(&w, CTOS_SSH_CONFIG, "first", root); record(&w, CTOS_SSH_CONFIG, ".ssh/child", child);
    token = reserve(1); CHECK(load(token, &w, 0, NULL, NULL) == 0);
    run_ok(token, 2, (uint32_t)bytes, 3); uint32_t used[3];
    CHECK(ctos_ssh_config_handle_usage(token, used) == 0); finish_handle(token);
    replace(&w, 12, 3 - used[0]); replace(&w, 16, (uint32_t)(bytes + strlen(child)) - used[1]); replace(&w, 20, 4 - used[2]);
    token = reserve(1); CHECK(load(token, &w, 1, NULL, NULL) == 0); run_ok(token, 1, (uint32_t)strlen(child), 1); finish_handle(token);
    replace(&w, 12, 0); replace(&w, 16, 0); replace(&w, 20, 0);
    token = reserve(1); CHECK(load(token, &w, 1, NULL, NULL) == 0);
    int32_t result[5]; CHECK(ctos_ssh_config_handle_run(token, result) == 0 && result[1] == CTOS_CONFIG_LIMIT);
    finish_handle(token); drop(&w);
}
static void staged_declarations(void) {
    struct source_wire w = wire_new(5, 10, 1000, 10);
    record(&w, CTOS_SSH_KNOWN_HOSTS, "trust", NULL);
    record(&w, CTOS_SSH_CONFIG, "unusual/root", "Host root\nInclude conf/*\nInclude %h\n");
    record(&w, CTOS_SSH_CONFIG, ".ssh/conf/z", "Host zed\n");
    record(&w, CTOS_SSH_CONFIG, ".ssh/conf/a", "Host alpha\n");
    record(&w, CTOS_SSH_CONFIG, "orphan", "Host unvisited\n");
    ctos_ssh_config_token token = reserve(1); CHECK(load(token, &w, 1, NULL, NULL) == 0);
    run_ok(token, 3, (uint32_t)strlen("Host root\nInclude conf/*\nInclude %h\nHost zed\nHost alpha\n"), 5);
    unsigned char catalog[CTOS_SSH_CONFIG_CATALOG_BYTES]; size_t n;
    CHECK(ctos_ssh_config_handle_catalog(token, catalog, &n) == 0);
    CHECK(value(catalog + 12) == 3 && value(catalog + 16) == 3 && value(catalog + 20) == 1);
    CHECK(value(catalog + 24) == 2);
    CHECK(value(catalog + 40) == 4 && value(catalog + 57) == 3);
    finish_handle(token); drop(&w);
}
static void failures_and_cancel(void) {
    struct source_wire w = one("Host retained\n"), request = request_wire();
    for (unsigned int mode = 0; mode < 7; mode++) {
        ctos_ssh_config_token token = reserve(mode == 6 ? 0 : 1);
        struct copy_context context = {0}; int expected = mode < 3 ? ECANCELED : EIO;
        if (mode == 0) CHECK(ctos_ssh_config_handle_cancel(token) == 0);
        if (mode == 1) cancel_during_lock = token;
        if (mode == 2) context.cancel_source = 1;
        if (mode == 3 || mode == 4) context.abort_source = 1;
        if (mode == 5) fail_dump = 1;
        if (mode == 6) context.abort_request = 1;
        CHECK(load(token, &w, 0, mode == 6 ? &request : NULL, &context) == -1 && errno == expected);
        CHECK(mode > 1 || context.source_calls == 0);
        if (mode == 4) {
            fail_unmap = 1; CHECK(ctos_ssh_config_handle_close(token) == -1 && errno == EIO);
            CHECK(ctos_ssh_config_handle_destroy(token) == -1 && errno == EBUSY);
            zeroed(owned_mapping, owned_length);
        }
        int32_t result[5];
        CHECK(ctos_ssh_config_handle_run(token, result) == 0 && result[0] == 2 && result[4] == expected);
        CHECK(ctos_ssh_config_handle_run(token, result) == 0 && result[4] == EALREADY);
        finish_handle(token);
    }
    drop(&w); drop(&request);
}
static void *wrong_handle_owner(void *opaque) {
    ctos_ssh_config_token token = *(ctos_ssh_config_token *)opaque;
    uint32_t usage[3]; int32_t result[5];
    CHECK(ctos_ssh_config_handle_usage(token, usage) == -1 && errno == EPERM);
    CHECK(ctos_ssh_config_handle_run(token, result) == 0 && result[4] == EPERM);
    CHECK(ctos_ssh_config_handle_close(token) == -1 && errno == EPERM);
    CHECK(ctos_ssh_config_handle_destroy(token) == -1 && errno == EPERM);
    return NULL;
}
static void result_lifetime(void) {
    for (unsigned int mode = 0; mode < 3; mode++) {
        struct source_wire w = one("Host lifecycle\n"); ctos_ssh_config_token token = reserve(1);
        CHECK(load(token, &w, 0, NULL, NULL) == 0); run_ok(token, 1, 15, 1);
        if (mode == 0) {
            pthread_t t; CHECK(pthread_create(&t, NULL, wrong_handle_owner, &token) == 0); CHECK(pthread_join(t, NULL) == 0);
            CHECK(ctos_ssh_config_handle_cancel(token) == 0);
        } else if (mode == 1) clock_offset = 60001;
        else CHECK(ctos_ssh_config_handle_close(token) == 0);
        uint32_t usage[3] = {1,2,3};
        CHECK(ctos_ssh_config_handle_usage(token, usage) == -1); zeroed(usage, sizeof(usage));
        finish_handle(token); drop(&w); clock_offset = 0;
    }
}
static void reservations(void) {
    ctos_ssh_config_token tokens[CTOS_SSH_CONFIG_HANDLE_SLOTS];
    for (unsigned int i = 0; i < CTOS_SSH_CONFIG_HANDLE_SLOTS; i++) {
        tokens[i] = reserve(1); if (i) CHECK(tokens[i] > tokens[i - 1]);
    }
    ctos_ssh_config_token token = 99;
    CHECK(ctos_ssh_config_handle_reserve_detached(1, &token) == -1 && errno == EBUSY && token == 0);
    for (unsigned int i = 0; i < CTOS_SSH_CONFIG_HANDLE_SLOTS; i++) finish_handle(tokens[i]);
    CHECK(ctos_ssh_config_handle_test_seed_next(INT64_MAX) == 0);
    CHECK(ctos_ssh_config_handle_reserve_detached(1, &token) == -1 && errno == EBUSY && token == 0);
}
int main(void) {
    CHECK(ctos_detached_baseline_main() == 0);
    unsigned int before = fd_count(); observe = 1;
    basic_registry(); malformed_wire(); request_modes(); budgets(); staged_declarations(); failures_and_cancel(); result_lifetime(); reservations();
    CHECK(fd_count() == before && capabilities == 0 && effects == 0 && owned_mapping == NULL);
    for (unsigned int i = 0; i < 256; i++) CHECK(managed[i].p == NULL);
    observe = 0;
    printf("SSH_CONFIG_MANAGED_DETACHED checks=%u effects=%u capabilities=%u baseline=included gate=pass\n", checks, effects, capabilities);
    return 0;
}

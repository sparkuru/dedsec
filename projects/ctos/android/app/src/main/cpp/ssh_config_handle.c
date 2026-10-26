#define _GNU_SOURCE
#include "ssh_config_handle.h"

#include <errno.h>
#include <limits.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* Pure, namespaced pinned readconf predicates; no upstream helper effects. */
int ctos_config_upstream_ssh_valid_hostname(const char *);
int ctos_config_upstream_ssh_valid_ruser(const char *);

struct config_slot {
    ctos_ssh_config_token token;
    pthread_t owner;
    pid_t pid;
    uid_t uid;
    struct ctos_ssh_config_eval *eval;
    struct ctos_ssh_view_borrow *pending_borrow;
    int open_errno, busy, published, closed, cancelled, attempted;
    int detached, load_attempted, loaded;
    enum ctos_ssh_config_mode mode;
};
static pthread_mutex_t config_lock = PTHREAD_MUTEX_INITIALIZER;
static struct config_slot slots[CTOS_SSH_CONFIG_HANDLE_SLOTS];
static uint64_t next_token;

struct decoded_request {
    struct ctos_ssh_config_request value;
    char text[6][CTOS_SSH_CONFIG_NAME_BYTES + 1];
    char command[CTOS_SSH_CONFIG_LINE_BYTES + 1];
};
struct cursor { const unsigned char *p; size_t left; };

static void wipe(void *p, size_t n) {
    volatile unsigned char *b = p;
    while (n-- != 0) *b++ = 0;
}
static int bad(int number) { errno = number; return -1; }
static int lock_registry(void) {
    if (pthread_mutex_lock(&config_lock) != 0) return bad(EBUSY);
    return 0;
}
static struct config_slot *find_locked(ctos_ssh_config_token token) {
    if (token == 0 || token > INT64_MAX) return NULL;
    for (unsigned int i = 0; i < CTOS_SSH_CONFIG_HANDLE_SLOTS; i++)
        if (slots[i].published && slots[i].token == token) return &slots[i];
    return NULL;
}
static int owner_locked(const struct config_slot *s, int cross_thread) {
    if (s->pid != getpid() || s->uid != getuid() ||
        (!cross_thread && !pthread_equal(s->owner, pthread_self()))) return bad(EPERM);
    return 0;
}
static struct config_slot *pin(ctos_ssh_config_token token) {
    if (lock_registry() != 0) return NULL;
    struct config_slot *s = find_locked(token);
    int failure = s == NULL ? ENOENT : 0;
    if (s != NULL && owner_locked(s, 0) != 0) failure = errno;
    if (failure == 0 && s->busy) failure = EBUSY;
    if (failure == 0) s->busy = 1;
    pthread_mutex_unlock(&config_lock);
    if (failure != 0) { errno = failure; return NULL; }
    return s;
}
static void unpin(struct config_slot *s) {
    /* A successful pin owns this slot until this lock is reacquired. */
    pthread_mutex_lock(&config_lock);
    s->busy = 0;
    pthread_mutex_unlock(&config_lock);
}
static int take_u32(struct cursor *c, uint32_t *out) {
    if (c->left < 4) return bad(EINVAL);
    *out = ((uint32_t)c->p[0] << 24) | ((uint32_t)c->p[1] << 16) |
        ((uint32_t)c->p[2] << 8) | c->p[3];
    c->p += 4; c->left -= 4;
    return 0;
}
static int valid_text(const unsigned char *s, size_t n) {
    for (size_t i = 0; i < n;) {
        uint32_t cp = s[i++], minimum;
        unsigned int remaining;
        if (cp < 128) { if (cp < 32 || cp == 127) return bad(EINVAL); continue; }
        if (cp >= 0xc2 && cp <= 0xdf) { remaining = 1; minimum = 0x80; cp &= 31; }
        else if (cp >= 0xe0 && cp <= 0xef) { remaining = 2; minimum = 0x800; cp &= 15; }
        else if (cp >= 0xf0 && cp <= 0xf4) { remaining = 3; minimum = 0x10000; cp &= 7; }
        else return bad(EILSEQ);
        if (remaining > n - i) return bad(EILSEQ);
        while (remaining-- != 0) {
            if ((s[i] & 0xc0) != 0x80) return bad(EILSEQ);
            cp = (cp << 6) | (s[i++] & 63);
        }
        if (cp < minimum || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff)) return bad(EILSEQ);
        if (cp >= 0x80 && cp <= 0x9f) return bad(EINVAL);
    }
    return 0;
}
static int take_text(struct cursor *c, char *out, size_t maximum, int required) {
    uint32_t n;
    if (take_u32(c, &n) != 0) return -1;
    if (n > maximum) return bad(E2BIG);
    if (n > c->left || (required && n == 0)) return bad(EINVAL);
    if (valid_text(c->p, n) != 0) return -1;
    memcpy(out, c->p, n); out[n] = 0;
    c->p += n; c->left -= n;
    return 0;
}
static int decode(const unsigned char *wire, size_t n, struct decoded_request *r) {
    if (wire == NULL || n > CTOS_SSH_CONFIG_REQUEST_BYTES) return bad(EINVAL);
    struct cursor c = {wire, n};
    uint32_t magic, version, port, uid;
    if (take_u32(&c, &magic) != 0 || take_u32(&c, &version) != 0 ||
        take_u32(&c, &port) != 0 || take_u32(&c, &uid) != 0) return -1;
    if (magic != 0x43534551U || version != 1 || port > 65535 || (uint32_t)(uid_t)uid != uid)
        return bad(EINVAL);
    for (unsigned int i = 0; i < 6; i++)
        if (take_text(&c, r->text[i], CTOS_SSH_CONFIG_NAME_BYTES, i == 0 || i == 3 || i == 4) != 0) return -1;
    if (take_text(&c, r->command, CTOS_SSH_CONFIG_LINE_BYTES, 0) != 0) return -1;
    if (c.left != 0) return bad(EINVAL);
    if (!ctos_config_upstream_ssh_valid_hostname(r->text[0]) ||
        (r->text[1][0] && !ctos_config_upstream_ssh_valid_ruser(r->text[1])) ||
        (r->text[2][0] && !ctos_config_upstream_ssh_valid_ruser(r->text[2]))) return bad(EINVAL);
    r->value = (struct ctos_ssh_config_request){.alias = r->text[0],
        .remote_user = r->text[1][0] ? r->text[1] : NULL,
        .remote_user_fallback = r->text[2][0] ? r->text[2] : NULL,
        .local_user = r->text[3], .local_hostname = r->text[4], .tag = r->text[5][0] ? r->text[5] : NULL,
        .remote_command = r->command[0] ? r->command : NULL, .port = port, .local_uid = (uid_t)uid};
    return 0;
}
static int open_handle(ctos_ssh_view_token view, const unsigned char *wire, size_t length,
    int catalog_mode, ctos_ssh_config_token *out) {
    struct decoded_request r = {0};
    struct config_slot *s = NULL;
    struct ctos_ssh_view_borrow *borrow = NULL;
    int failure;
    if (out == NULL) return bad(EINVAL);
    *out = 0;
    if (!catalog_mode && decode(wire, length, &r) != 0) { failure = errno; goto failed; }
    if (lock_registry() != 0) { failure = errno; goto failed; }
    if (next_token < INT64_MAX) for (unsigned int i = 0; i < CTOS_SSH_CONFIG_HANDLE_SLOTS; i++)
        if (slots[i].token == 0) { s = &slots[i]; break; }
    if (s == NULL) { pthread_mutex_unlock(&config_lock); failure = EBUSY; goto failed; }
    *s = (struct config_slot){.token = ++next_token, .owner = pthread_self(),
        .pid = getpid(), .uid = getuid(), .busy = 1,
        .mode = catalog_mode ? CTOS_CONFIG_CATALOG : CTOS_CONFIG_EFFECTIVE};
    pthread_mutex_unlock(&config_lock);
    if (ctos_ssh_view_handle_borrow(view, &borrow) != 0) {
        failure = errno;
        pthread_mutex_lock(&config_lock); memset(s, 0, sizeof(*s)); pthread_mutex_unlock(&config_lock);
        goto failed;
    }
    struct ctos_ssh_config_eval *eval = NULL;
    int opened = catalog_mode ? ctos_ssh_config_eval_open_catalog(borrow, &eval) :
        ctos_ssh_config_eval_open(borrow, &r.value, &eval);
    failure = opened == 0 ? 0 : errno;
    pthread_mutex_lock(&config_lock);
    s->eval = eval;
    if (opened != 0) { s->pending_borrow = borrow; s->open_errno = failure; }
    s->published = 1; s->busy = 0; *out = s->token;
    pthread_mutex_unlock(&config_lock);
    wipe(&r, sizeof(r));
    return 0;
failed:
    wipe(&r, sizeof(r));
    return bad(failure);
}
int ctos_ssh_config_handle_open(ctos_ssh_view_token view, const unsigned char *wire, size_t length,
    ctos_ssh_config_token *out) { return open_handle(view, wire, length, 0, out); }
int ctos_ssh_config_handle_open_catalog(ctos_ssh_view_token view, ctos_ssh_config_token *out) {
    return open_handle(view, NULL, 0, 1, out);
}
static int reserve_mode(enum ctos_ssh_config_mode mode, ctos_ssh_config_token *out) {
    if (out == NULL) return bad(EINVAL);
    *out = 0;
    if (lock_registry() != 0) return -1;
    struct config_slot *s = NULL;
    if (next_token < INT64_MAX) for (unsigned int i = 0; i < CTOS_SSH_CONFIG_HANDLE_SLOTS; i++)
        if (slots[i].token == 0) { s = &slots[i]; break; }
    if (s == NULL) { pthread_mutex_unlock(&config_lock); return bad(EBUSY); }
    *s = (struct config_slot){.token = ++next_token, .owner = pthread_self(), .pid = getpid(),
        .uid = getuid(), .published = 1, .detached = 1, .mode = mode};
    *out = s->token; pthread_mutex_unlock(&config_lock); return 0;
}
int ctos_ssh_config_handle_reserve_detached(int catalog_mode, ctos_ssh_config_token *out) {
    if (catalog_mode != 0 && catalog_mode != 1) { if (out != NULL) *out = 0; return bad(EINVAL); }
    return reserve_mode(catalog_mode ? CTOS_CONFIG_CATALOG : CTOS_CONFIG_EFFECTIVE, out);
}
int ctos_ssh_config_handle_reserve_audit(ctos_ssh_config_token *out) {
    return reserve_mode(CTOS_CONFIG_AUDIT, out);
}
int ctos_ssh_config_handle_reserve_resource_audit(ctos_ssh_config_token *out) {
    return reserve_mode(CTOS_CONFIG_RESOURCE_AUDIT, out);
}
int ctos_ssh_config_handle_load_detached(ctos_ssh_config_token token, size_t length,
    unsigned int root, uint64_t deadline, int request_present, size_t request_length,
    ctos_ssh_config_copy copy, void *context) {
    struct config_slot *s = pin(token);
    if (s == NULL) return -1;
    if (!s->detached || s->load_attempted || s->closed) { unpin(s); return bad(EALREADY); }
    s->load_attempted = 1;
    unsigned char request_wire[CTOS_SSH_CONFIG_REQUEST_BYTES] = {0};
    struct decoded_request request = {0};
    struct ctos_ssh_config_eval *e = NULL;
    int ret = -1, failure = EINVAL;
    if (copy == NULL || (request_present != 0 && request_present != 1) ||
        (!request_present && request_length != 0) ||
        (s->mode != CTOS_CONFIG_EFFECTIVE && request_present) ||
        (s->mode == CTOS_CONFIG_EFFECTIVE && (!request_present || request_length == 0)) ||
        request_length > sizeof(request_wire)) goto done;
    int prepared = s->mode == CTOS_CONFIG_RESOURCE_AUDIT ? ctos_ssh_config_eval_prepare_resource_audit_detached(deadline, &e) :
        s->mode == CTOS_CONFIG_AUDIT ? ctos_ssh_config_eval_prepare_audit_detached(deadline, &e) :
        ctos_ssh_config_eval_prepare_detached(s->mode == CTOS_CONFIG_CATALOG, deadline, &e);
    if (prepared != 0) { failure = errno; goto done; }
    pthread_mutex_lock(&config_lock);
    s->eval = e;
    if (s->cancelled) ctos_ssh_config_eval_cancel(e);
    pthread_mutex_unlock(&config_lock);
    unsigned char *source = NULL;
    if (ctos_ssh_config_eval_detached_buffer(e, length, &source) != 0) { failure = errno; goto done; }
    if (request_present && (copy(context, 1, request_wire, request_length) != 0 ||
        decode(request_wire, request_length, &request) != 0)) { failure = errno; goto done; }
    if (ctos_ssh_config_eval_loading_live(e) != 0 || copy(context, 0, source, length) != 0 ||
        ctos_ssh_config_eval_complete_detached(e, root, s->mode != CTOS_CONFIG_EFFECTIVE ? NULL : &request.value) != 0) { failure = errno; goto done; }
    ret = 0; failure = 0;
done:
    wipe(request_wire, sizeof(request_wire)); wipe(&request, sizeof(request));
    pthread_mutex_lock(&config_lock);
    if (s->cancelled) {
        ctos_ssh_config_eval_cancel(s->eval);
        if (ret == 0) { ret = -1; failure = ECANCELED; }
    }
    s->loaded = ret == 0; s->open_errno = failure == 0 ? 0 : failure;
    s->busy = 0;
    pthread_mutex_unlock(&config_lock);
    return ret == 0 ? 0 : bad(failure);
}
int ctos_ssh_config_handle_run(ctos_ssh_config_token token, int32_t out[CTOS_SSH_CONFIG_RUN_VALUES]) {
    if (out == NULL) return bad(EINVAL);
    memset(out, 0, sizeof(int32_t) * CTOS_SSH_CONFIG_RUN_VALUES);
    struct config_slot *s = pin(token);
    if (s == NULL) { out[0] = 2; out[4] = errno; return 0; }
    struct ctos_ssh_config_error error = {0};
    int failure = 0, ret = -1;
    if (s->closed) failure = EALREADY;
    else if (s->detached && !s->load_attempted) failure = EAGAIN;
    else if (s->open_errno != 0) {
        pthread_mutex_lock(&config_lock);
        failure = s->attempted ? EALREADY : s->cancelled ? ECANCELED : s->open_errno;
        s->attempted = 1;
        pthread_mutex_unlock(&config_lock);
    } else {
        ret = ctos_ssh_config_eval_run(s->eval, &error);
        failure = ret == 0 ? 0 : errno;
    }
    if (ret != 0) {
        out[0] = error.reason == CTOS_CONFIG_OK ? 2 : 1;
        out[1] = (int32_t)error.reason; out[2] = (int32_t)error.object; out[3] = (int32_t)error.line;
        out[4] = error.reason == CTOS_CONFIG_OK ? failure : error.error_number;
    }
    unpin(s);
    return 0;
}
static void put_u32(unsigned char **p, uint32_t n) {
    *(*p)++ = (unsigned char)(n >> 24); *(*p)++ = (unsigned char)(n >> 16);
    *(*p)++ = (unsigned char)(n >> 8); *(*p)++ = (unsigned char)n;
}
static void put_text(unsigned char **p, const char *s) {
    size_t n = strlen(s); put_u32(p, (uint32_t)n); memcpy(*p, s, n); *p += n;
}
static int read_summary(struct config_slot *s, struct ctos_ssh_config_summary *summary) {
    if (s->closed) return bad(EALREADY);
    if (s->detached && !s->loaded) return bad(s->open_errno ? s->open_errno : EAGAIN);
    if (s->eval == NULL) return bad(s->open_errno);
    return ctos_ssh_config_eval_summary(s->eval, summary);
}
int ctos_ssh_config_handle_summary(ctos_ssh_config_token token,
    unsigned char out[CTOS_SSH_CONFIG_SUMMARY_BYTES], size_t *length) {
    if (out == NULL || length == NULL) return bad(EINVAL);
    *length = 0; wipe(out, CTOS_SSH_CONFIG_SUMMARY_BYTES);
    struct config_slot *s = pin(token);
    if (s == NULL) return -1;
    struct ctos_ssh_config_summary summary = {0};
    int ret = read_summary(s, &summary), failure = errno;
    if (ret == 0) {
        unsigned char *p = out;
        put_u32(&p, 0x43534553U); put_u32(&p, 1);
        uint32_t values[] = {summary.user_required, summary.port, summary.identities, summary.certificates,
            summary.local_forwards, summary.remote_forwards, summary.has_jump, summary.has_remote_command,
            (uint32_t)summary.request_tty, (uint32_t)summary.session_type, summary.has_raw_fields};
        for (unsigned int i = 0; i < sizeof(values) / sizeof(values[0]); i++) put_u32(&p, values[i]);
        put_text(&p, summary.alias); put_text(&p, summary.hostname); put_text(&p, summary.user);
        *length = (size_t)(p - out);
        ret = read_summary(s, &summary); failure = errno;
    }
    unpin(s); wipe(&summary, sizeof(summary));
    if (ret != 0) { wipe(out, CTOS_SSH_CONFIG_SUMMARY_BYTES); *length = 0; return bad(failure); }
    return 0;
}
int ctos_ssh_config_handle_summary_live(ctos_ssh_config_token token) {
    struct config_slot *s = pin(token);
    if (s == NULL) return -1;
    struct ctos_ssh_config_summary summary = {0};
    int ret = read_summary(s, &summary), failure = errno;
    wipe(&summary, sizeof(summary)); unpin(s);
    return ret == 0 ? 0 : bad(failure);
}
static int read_catalog(struct config_slot *s, const struct ctos_ssh_config_catalog **catalog) {
    if (s->closed) return bad(EALREADY);
    if (s->detached && !s->loaded) return bad(s->open_errno ? s->open_errno : EAGAIN);
    if (s->eval == NULL) return bad(s->open_errno);
    return ctos_ssh_config_eval_catalog(s->eval, catalog);
}
static size_t write_catalog(unsigned char *out, const struct ctos_ssh_config_catalog *catalog) {
    unsigned char *p = out;
    put_u32(&p, 0x43534344U); put_u32(&p, 1);
    put_u32(&p, 1U | (catalog->omitted_selectors ? 2U : 0U) |
        (catalog->has_match ? 4U : 0U) | (catalog->deferred_count ? 8U : 0U));
    put_u32(&p, catalog->scanned_files); put_u32(&p, catalog->alias_count);
    put_u32(&p, catalog->deferred_count);
    for (unsigned int i = 0; i < catalog->alias_count; i++) {
        put_u32(&p, catalog->entries[i].object); put_u32(&p, catalog->entries[i].line);
        put_text(&p, catalog->entries[i].alias);
    }
    for (unsigned int i = 0; i < catalog->deferred_count; i++) {
        put_u32(&p, catalog->deferred[i].object); put_u32(&p, catalog->deferred[i].line);
    }
    return (size_t)(p - out);
}
int ctos_ssh_config_handle_catalog(ctos_ssh_config_token token,
    unsigned char out[CTOS_SSH_CONFIG_CATALOG_BYTES], size_t *length) {
    if (out == NULL || length == NULL) return bad(EINVAL);
    *length = 0; wipe(out, CTOS_SSH_CONFIG_CATALOG_BYTES);
    struct config_slot *s = pin(token);
    if (s == NULL) return -1;
    const struct ctos_ssh_config_catalog *catalog = NULL;
    int ret = read_catalog(s, &catalog), failure = errno;
    if (ret == 0) {
        *length = write_catalog(out, catalog);
        ret = read_catalog(s, &catalog); failure = errno;
    }
    unpin(s);
    if (ret != 0) { wipe(out, CTOS_SSH_CONFIG_CATALOG_BYTES); *length = 0; return bad(failure); }
    return 0;
}
int ctos_ssh_config_handle_catalog_live(ctos_ssh_config_token token) {
    struct config_slot *s = pin(token);
    if (s == NULL) return -1;
    const struct ctos_ssh_config_catalog *catalog = NULL;
    int ret = read_catalog(s, &catalog), failure = errno;
    unpin(s); return ret == 0 ? 0 : bad(failure);
}
static int read_audit(struct config_slot *s, const struct ctos_ssh_config_audit **audit) {
    if (s->closed) return bad(EALREADY);
    if (s->mode != CTOS_CONFIG_AUDIT) return bad(EINVAL);
    if (!s->loaded || s->eval == NULL) return bad(s->open_errno ? s->open_errno : EAGAIN);
    return ctos_ssh_config_eval_audit(s->eval, audit);
}
static size_t write_audit(unsigned char *out, const struct ctos_ssh_config_audit *audit) {
    size_t catalog_length = write_catalog(out + 20, audit->catalog);
    unsigned char *p = out;
    put_u32(&p, 0x43534341U); put_u32(&p, 1); put_u32(&p, 1);
    put_u32(&p, (uint32_t)catalog_length); put_u32(&p, audit->edge_count);
    p = out + 20 + catalog_length;
    for (unsigned int i = 0; i < audit->edge_count; i++) {
        put_u32(&p, audit->edges[i].parent); put_u32(&p, audit->edges[i].line); put_u32(&p, audit->edges[i].child);
    }
    return (size_t)(p - out);
}
int ctos_ssh_config_handle_audit(ctos_ssh_config_token token,
    unsigned char out[CTOS_SSH_CONFIG_AUDIT_BYTES], size_t *length) {
    if (out == NULL || length == NULL) return bad(EINVAL);
    *length = 0; wipe(out, CTOS_SSH_CONFIG_AUDIT_BYTES);
    struct config_slot *s = pin(token);
    if (s == NULL) return -1;
    const struct ctos_ssh_config_audit *audit = NULL;
    int ret = read_audit(s, &audit), failure = errno;
    if (ret == 0) {
        *length = write_audit(out, audit);
        ret = read_audit(s, &audit); failure = errno;
    }
    unpin(s);
    if (ret != 0) { wipe(out, CTOS_SSH_CONFIG_AUDIT_BYTES); *length = 0; return bad(failure); }
    return 0;
}
int ctos_ssh_config_handle_audit_live(ctos_ssh_config_token token) {
    struct config_slot *s = pin(token);
    if (s == NULL) return -1;
    const struct ctos_ssh_config_audit *audit = NULL;
    int ret = read_audit(s, &audit), failure = errno;
    unpin(s); return ret == 0 ? 0 : bad(failure);
}
static int read_resource_audit(struct config_slot *s, const struct ctos_ssh_config_resource_audit **audit) {
    if (s->closed) return bad(EALREADY);
    if (s->mode != CTOS_CONFIG_RESOURCE_AUDIT) return bad(EINVAL);
    if (!s->loaded || s->eval == NULL) return bad(s->open_errno ? s->open_errno : EAGAIN);
    return ctos_ssh_config_eval_resource_audit(s->eval, audit);
}
int ctos_ssh_config_handle_resource_audit(ctos_ssh_config_token token,
    unsigned char out[CTOS_SSH_CONFIG_RESOURCE_BYTES], size_t *length) {
    if (out == NULL || length == NULL) return bad(EINVAL);
    *length = 0; wipe(out, CTOS_SSH_CONFIG_RESOURCE_BYTES);
    struct config_slot *s = pin(token);
    if (s == NULL) return -1;
    const struct ctos_ssh_config_resource_audit *audit = NULL;
    int ret = read_resource_audit(s, &audit), failure = errno;
    if (ret == 0) {
        size_t audit_length = write_audit(out + 20, audit->audit);
        unsigned char *p = out;
        put_u32(&p, 0x43534352U); put_u32(&p, 1); put_u32(&p, 1);
        put_u32(&p, (uint32_t)audit_length); put_u32(&p, audit->resource_count);
        p = out + 20 + audit_length;
        for (unsigned int i = 0; i < audit->resource_count; i++) {
            const struct ctos_ssh_config_resource *r = &audit->resources[i];
            put_u32(&p, r->kind); put_u32(&p, r->parent); put_u32(&p, r->line);
            put_u32(&p, r->argument); put_u32(&p, r->state); put_u32(&p, r->target);
        }
        *length = (size_t)(p - out);
        ret = read_resource_audit(s, &audit); failure = errno;
    }
    unpin(s);
    if (ret != 0) { wipe(out, CTOS_SSH_CONFIG_RESOURCE_BYTES); *length = 0; return bad(failure); }
    return 0;
}
int ctos_ssh_config_handle_resource_audit_live(ctos_ssh_config_token token) {
    struct config_slot *s = pin(token);
    if (s == NULL) return -1;
    const struct ctos_ssh_config_resource_audit *audit = NULL;
    int ret = read_resource_audit(s, &audit), failure = errno;
    unpin(s); return ret == 0 ? 0 : bad(failure);
}
int ctos_ssh_config_handle_usage(ctos_ssh_config_token token, uint32_t out[3]) {
    if (out == NULL) return bad(EINVAL);
    memset(out, 0, sizeof(uint32_t) * 3);
    struct config_slot *s = pin(token);
    if (s == NULL) return -1;
    int ret;
    if (s->closed) ret = bad(EALREADY);
    else if (s->open_errno != 0) ret = bad(s->open_errno);
    else if (s->eval == NULL || (s->detached && !s->loaded)) ret = bad(EAGAIN);
    else ret = ctos_ssh_config_eval_usage(s->eval, out);
    int failure = errno; unpin(s);
    return ret == 0 ? 0 : bad(failure);
}
int ctos_ssh_config_handle_cancel(ctos_ssh_config_token token) {
    if (lock_registry() != 0) return -1;
    struct config_slot *s = find_locked(token);
    int ret = s == NULL ? bad(ENOENT) : owner_locked(s, 1);
    int failure = errno;
    if (ret == 0 && !s->closed) {
        s->cancelled = 1;
        /* Under this short lock destroy cannot free the atomic target. */
        ctos_ssh_config_eval_cancel(s->eval);
    }
    pthread_mutex_unlock(&config_lock);
    return ret == 0 ? 0 : bad(failure);
}
int ctos_ssh_config_handle_close(ctos_ssh_config_token token) {
    struct config_slot *s = pin(token);
    if (s == NULL) return -1;
    int ret = 0;
    if (!s->closed && s->eval != NULL) ret = ctos_ssh_config_eval_close(s->eval);
    if (!s->closed && s->pending_borrow != NULL) {
        ret = ctos_ssh_view_borrow_release(s->pending_borrow);
        if (ret == 0) s->pending_borrow = NULL;
    }
    int failure = errno;
    pthread_mutex_lock(&config_lock);
    if (ret == 0) s->closed = 1;
    s->busy = 0;
    pthread_mutex_unlock(&config_lock);
    return ret == 0 ? 0 : bad(failure);
}
int ctos_ssh_config_handle_destroy(ctos_ssh_config_token token) {
    struct config_slot *s = pin(token);
    if (s == NULL) return -1;
    /* Destroy only frees a closed, fixed-size tombstone. Keep this short lock
     * across free so cancel cannot load the just-freed atomic target. */
    pthread_mutex_lock(&config_lock);
    int ret = s->closed ? 0 : bad(EBUSY);
    if (ret == 0 && s->eval != NULL) ret = ctos_ssh_config_eval_destroy(s->eval);
    int failure = errno;
    if (ret == 0) memset(s, 0, sizeof(*s));
    else s->busy = 0;
    pthread_mutex_unlock(&config_lock);
    return ret == 0 ? 0 : bad(failure);
}
#ifdef CTOS_CONFIG_HANDLE_TEST
int ctos_ssh_config_handle_test_seed_next(uint64_t n) {
    if (lock_registry() != 0) return -1;
    int ret = 0;
    for (unsigned int i = 0; i < CTOS_SSH_CONFIG_HANDLE_SLOTS; i++)
        if (slots[i].token != 0) ret = -1;
    if (n > INT64_MAX || n < next_token) ret = -1;
    if (ret == 0) next_token = n;
    pthread_mutex_unlock(&config_lock);
    return ret == 0 ? 0 : bad(EBUSY);
}
#endif

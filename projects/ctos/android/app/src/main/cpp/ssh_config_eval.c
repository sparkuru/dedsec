/*
 * Copyright (c) 2000 Markus Friedl.  All rights reserved.
 * Copyright (c) 2005-2020 Damien Miller.  All rights reserved.
 * Copyright (c) 2004 Henning Brauer <henning@openbsd.org>
 *
 * Permission to use, copy, modify, and distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

/* Managed, non-executing adapter for the separately namespaced pinned parser. */
#include "includes.h"
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/mman.h>
#include <dirent.h>
#include <errno.h>
#include <glob.h>
#include <pthread.h>
#include <pwd.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <unistd.h>
#include <time.h>
#include <openssl/crypto.h>
#include "xmalloc.h"
#include "ssh.h"
#include "log.h"
#include "misc.h"
#include "readconf.h"
#include "kex.h"
#include "match.h"
#include "ssh_config_eval.h"
#include "ssh_file_io.h"

extern void ctos_config_upstream_initialize_options(Options *);
extern int ctos_config_upstream_fill_default_options(Options *);
extern void ctos_config_upstream_fill_default_options_for_canonicalization(Options *);
extern void ctos_config_upstream_free_options(Options *);
extern int ctos_config_upstream_read_config_file(const char *, struct passwd *, const char *,
    const char *, const char *, Options *, int, int *);
extern int ctos_config_upstream_ssh_valid_hostname(const char *);
extern int ctos_config_upstream_ssh_valid_ruser(const char *);
extern int ctos_config_upstream_config_has_permitted_cnames(Options *);
extern char *ctos_config_upstream_ssh_connection_hash(const char *, const char *, const char *, const char *, const char *);

struct config_origin { unsigned int object, line; };
struct config_input { unsigned char bytes[4096]; size_t next, length; };
struct ctos_ssh_config_eval {
    pthread_t owner;
    pid_t pid;
    uid_t uid;
    _Atomic int cancelled;
    enum ctos_ssh_config_state state;
    struct ctos_ssh_view_borrow *borrow;
    unsigned char *owned;
    size_t owned_length;
    struct ctos_ssh_file *owned_files;
    size_t owned_file_bytes;
    int owned_locked, detached;
    uint64_t deadline;
    struct ctos_ssh_io io;
    Options options;
    struct ctos_ssh_config_error error;
    struct ctos_ssh_config_summary summary;
    struct ctos_ssh_config_direct_plan *direct_plan;
    struct ctos_ssh_config_request request;
    char alias[256], user[256], fallback[256], local_user[256], local_host[256], tag[256];
    char command[CTOS_SSH_CONFIG_LINE_BYTES + 1];
    char root[sizeof(CTOS_SSH_VIRTUAL_HOME) + 514];
    unsigned int objects[CTOS_SSH_CONFIG_DEPTH + 1], lines[CTOS_SSH_CONFIG_DEPTH + 1];
    unsigned int stack, total_lines, max_lines;
    size_t staged_length;
    struct config_origin host_origin, user_origin, diagnostic_origin, algorithm_origin[6];
    struct config_origin identity_origin[CTOS_SSH_CONFIG_LIST], hostfile_origin[CTOS_SSH_CONFIG_HOSTFILES];
    struct config_input input[CTOS_SSH_CONFIG_DEPTH + 1];
    int options_live;
    enum ctos_ssh_config_mode mode;
    struct ctos_ssh_config_catalog *catalog;
    struct ctos_ssh_config_audit *audit;
    struct ctos_ssh_config_resource_audit *resource_audit;
    unsigned char visited[CTOS_SSH_VIEW_FILES];
};
static pthread_mutex_t parser_lock = PTHREAD_MUTEX_INITIALIZER;
/* Only accessed with parser_lock held. No callbacks or global logger mutation. */
static struct ctos_ssh_config_eval *current;

static int owner(const struct ctos_ssh_config_eval *e) {
    if (e == NULL) { errno = EINVAL; return -1; }
    if (e->pid != getpid() || e->uid != getuid() || !pthread_equal(e->owner, pthread_self())) {
        errno = EPERM; return -1;
    }
    return 0;
}
static int milliseconds(uint64_t *out) {
    struct timespec now;
    if (clock_gettime(CLOCK_BOOTTIME, &now) != 0) return -1;
    if (now.tv_sec < 0 || now.tv_nsec < 0 || now.tv_nsec >= 1000000000 ||
        (uint64_t)now.tv_sec > ((uint64_t)INT64_MAX - 999) / 1000) { errno = EIO; return -1; }
    *out = (uint64_t)now.tv_sec * 1000 + (uint64_t)now.tv_nsec / 1000000;
    return 0;
}
static int live(const struct ctos_ssh_config_eval *e) {
    if (owner(e) != 0) return -1;
    if (atomic_load_explicit(&e->cancelled, memory_order_acquire)) { errno = ECANCELED; return -1; }
    if (!e->detached) return ctos_ssh_view_borrow_live(e->borrow);
    uint64_t now;
    if (milliseconds(&now) != 0) return -1;
    if (now >= e->deadline) { errno = ETIMEDOUT; return -1; }
    return 0;
}
static int source_live(const void *e) { return live(e); }
int ctos_config_reject(unsigned int reason, int error_number) {
    if (current != NULL && current->error.reason == CTOS_CONFIG_OK) {
        current->error.reason = reason;
        current->error.error_number = error_number;
        if (current->stack != 0) {
            current->error.object = current->objects[current->stack - 1] + 1;
            current->error.line = current->lines[current->stack - 1];
        } else {
            current->error.object = current->diagnostic_origin.object;
            current->error.line = current->diagnostic_origin.line;
        }
    }
    errno = error_number;
    return -1;
}
int ctos_config_check(void) {
    if (current == NULL) { errno = EBUSY; return -1; }
    if (current->error.reason != CTOS_CONFIG_OK) { errno = current->error.error_number; return -1; }
    if (live(current) != 0) return ctos_config_reject(CTOS_CONFIG_LIFETIME, errno);
    return 0;
}
void ctos_config_mark_options(const Options *o) {
    if (current == NULL || current->stack == 0) return;
    struct config_origin here = {current->objects[current->stack - 1] + 1,
        current->lines[current->stack - 1]};
    /* First assignment provenance survives Include return and final rereads. */
    if (o->hostname != NULL && current->host_origin.object == 0) current->host_origin = here;
    if (o->user != NULL && current->user[0] == 0 && current->user_origin.object == 0) current->user_origin = here;
    const char *algorithms[6] = {o->ciphers, o->macs, o->kex_algorithms,
        o->hostbased_accepted_algos, o->pubkey_accepted_algos, o->ca_sign_algorithms};
    for (unsigned int i = 0; i < 6; i++) if (algorithms[i] != NULL && current->algorithm_origin[i].object == 0)
        current->algorithm_origin[i] = here;
    if (current->direct_plan != NULL) {
        for (int i = 0; i < o->num_identity_files && i < (int)CTOS_SSH_CONFIG_LIST; i++)
            if (current->identity_origin[i].object == 0) current->identity_origin[i] = here;
        for (unsigned int i = 0; i < o->num_user_hostfiles && i < CTOS_SSH_CONFIG_HOSTFILES; i++)
            if (current->hostfile_origin[i].object == 0) current->hostfile_origin[i] = here;
    }
}
int ctos_config_direct_keyword(const char *keyword, int active, int count, char **args) {
    if (current == NULL || current->direct_plan == NULL || !active) return 0;
    static const char *const accepted[] = {"host", "match", "include", "hostname", "user", "port",
        "identityfile", "identitiesonly", "userknownhostsfile", "stricthostkeychecking", "connecttimeout",
        "requesttty", "pubkeyauthentication", "passwordauthentication"};
    for (size_t i = 0; i < sizeof(accepted) / sizeof(accepted[0]); i++) if (strcmp(keyword, accepted[i]) == 0) {
        if (count == 1 && ((strcmp(keyword, "requesttty") == 0 &&
                (strcasecmp(args[0], "no") == 0 || strcasecmp(args[0], "false") == 0)) ||
                (strcmp(keyword, "pubkeyauthentication") == 0 && strcasecmp(args[0], "host-bound") == 0) ||
                (strcmp(keyword, "userknownhostsfile") == 0 && strcasecmp(args[0], "none") == 0)))
            return ctos_config_reject(CTOS_CONFIG_UNSUPPORTED, ENOTSUP);
        return 0;
    }
    /* Explicit no-op commands/providers are also safe; the existing full Options
     * policy still checks them after the actual pinned value parser. */
    if (count == 1 && strcasecmp(args[0], "none") == 0 &&
            (strcmp(keyword, "proxycommand") == 0 || strcmp(keyword, "localcommand") == 0 ||
             strcmp(keyword, "knownhostscommand") == 0 || strcmp(keyword, "identityagent") == 0 ||
             strcmp(keyword, "globalknownhostsfile") == 0)) return 0;
    if (count == 1 && strcmp(keyword, "preferredauthentications") == 0 && strcmp(args[0], "publickey") == 0)
        return 0;
    return ctos_config_reject(CTOS_CONFIG_UNSUPPORTED, ENOTSUP);
}
void ctos_config_defaults_error(const char *field) {
    static const char *fields[6] = {"ciphers", "macs", "kex_algorithms",
        "hostbased_accepted_algos", "pubkey_accepted_algos", "ca_sign_algorithms"};
    for (unsigned int i = 0; i < 6; i++) if (strcmp(field, fields[i]) == 0) {
        current->diagnostic_origin = current->algorithm_origin[i]; break;
    }
    ctos_config_reject(CTOS_CONFIG_DEFAULTS, EINVAL);
}
void ctos_config_quiet(const char *file, const char *function, int line, int showfunc,
    LogLevel level, const char *suffix, const char *format, ...) {
    (void)file; (void)function; (void)line; (void)showfunc; (void)level; (void)suffix; (void)format;
}
static int utf8(const unsigned char *s, size_t n) {
    for (size_t i = 0; i < n;) {
        uint32_t c = s[i++];
        unsigned int extra;
        uint32_t min;
        if (c < 128) { if (c == 0) return -1; continue; }
        if (c >= 0xc2 && c <= 0xdf) { extra = 1; min = 0x80; c &= 31; }
        else if (c >= 0xe0 && c <= 0xef) { extra = 2; min = 0x800; c &= 15; }
        else if (c >= 0xf0 && c <= 0xf4) { extra = 3; min = 0x10000; c &= 7; }
        else return -1;
        if (extra > n - i) return -1;
        while (extra--) { if ((s[i] & 0xc0) != 0x80) return -1; c = (c << 6) | (s[i++] & 63); }
        if (c < min || c > 0x10ffff || (c >= 0xd800 && c <= 0xdfff)) return -1;
    }
    return 0;
}
int ctos_config_catalog_mode(void) { return current != NULL && current->mode == CTOS_CONFIG_CATALOG; }
int ctos_config_audit_mode(void) { return current != NULL && (current->mode == CTOS_CONFIG_AUDIT || current->mode == CTOS_CONFIG_RESOURCE_AUDIT); }
int ctos_config_scan_mode(void) { return current != NULL && current->mode != CTOS_CONFIG_EFFECTIVE; }
int ctos_config_resource_mode(void) { return current != NULL && current->mode == CTOS_CONFIG_RESOURCE_AUDIT; }
static int resource_type(unsigned int kind, unsigned int type) {
    if (kind == 1) return type == CTOS_SSH_PRIVATE_KEY || type == CTOS_SSH_PUBLIC_KEY || type == CTOS_SSH_CERTIFICATE;
    if (kind == 2) return type == CTOS_SSH_CERTIFICATE;
    if (kind == 3 || kind == 4) return type == CTOS_SSH_KNOWN_HOSTS;
    return kind == 5 && type == CTOS_SSH_PUBLIC_KEY;
}
static int resource_state(unsigned int kind, const char *value, unsigned int *state, unsigned int *target) {
    *target = 0;
    if (kind != 2 && strcasecmp(value, "none") == 0) { *state = 4; return 0; }
    if ((*value == '~' && strncmp(value, "~/", 2) != 0) ||
        (*value == '/' && strncmp(value, CTOS_SSH_VIRTUAL_HOME "/", sizeof(CTOS_SSH_VIRTUAL_HOME)) != 0))
        return ctos_config_reject(CTOS_CONFIG_PATH, EACCES);
    if (kind == 4 && strchr(value, '%') != NULL) return ctos_config_reject(CTOS_CONFIG_PATH, EACCES);
    if (kind != 4 && (strchr(value, '%') != NULL || strstr(value, "${") != NULL)) { *state = 2; return 0; }
    if (*value != '/' && strncmp(value, "~/", 2) != 0) { *state = 3; return 0; }
    char absolute[sizeof(CTOS_SSH_VIRTUAL_HOME) + 514], relative[513];
    const char *path = value;
    if (*value == '~') {
        if (strlen(value + 2) > 512) return ctos_config_reject(CTOS_CONFIG_PATH, EACCES);
        snprintf(absolute, sizeof(absolute), "%s/%s", CTOS_SSH_VIRTUAL_HOME, value + 2); path = absolute;
    }
    if (ctos_ssh_io_resolve(path, 0, relative) != 0) return ctos_config_reject(CTOS_CONFIG_PATH, EACCES);
    size_t n = strlen(relative);
    for (size_t i = 0; i + 1 < n; i++) if ((unsigned char)relative[i] == 0xc2 &&
        (unsigned char)relative[i + 1] >= 0x80 && (unsigned char)relative[i + 1] <= 0x9f)
        return ctos_config_reject(CTOS_CONFIG_PATH, EACCES);
    for (unsigned int i = 0; i < current->io.count; i++) {
        if (ctos_config_check() != 0) return -1;
        const struct ctos_ssh_file *file = &current->io.files[i];
        if (file->path.length != n || memcmp(file->path.data, relative, n) != 0) continue;
        if (!resource_type(kind, file->type)) return ctos_config_reject(CTOS_CONFIG_PATH, EACCES);
        *state = 1; *target = i + 1; return 0;
    }
    if (kind == 1 || kind == 2) { *state = 5; return 0; }
    return ctos_config_reject(CTOS_CONFIG_PATH, ENOENT);
}
int ctos_config_resource_line(unsigned int kind, int count, char **args) {
    if (ctos_config_check() != 0) return -1;
    if (!ctos_config_resource_mode() || kind < 1 || kind > 5 || current->stack == 0)
        return ctos_config_reject(CTOS_CONFIG_INPUT, EINVAL);
    if (count <= 0 || ((kind == 1 || kind == 2) && count != 1))
        return ctos_config_reject(CTOS_CONFIG_INPUT, EINVAL);
    if ((unsigned int)count > ((kind == 3 || kind == 4) ? 32U : CTOS_SSH_CONFIG_LIST))
        return ctos_config_reject(CTOS_CONFIG_LIMIT, E2BIG);
    for (int i = 0; i < count; i++) if (args[i] == NULL || *args[i] == 0 ||
        (kind >= 3 && strcasecmp(args[i], "none") == 0 && count != 1))
        return ctos_config_reject(CTOS_CONFIG_INPUT, EINVAL);
    struct ctos_ssh_config_resource_audit *audit = current->resource_audit;
    for (int i = 0; i < count; i++) {
        if (ctos_config_check() != 0) return -1;
        struct ctos_ssh_config_resource row = {.kind = kind,
            .parent = current->objects[current->stack - 1] + 1,
            .line = current->lines[current->stack - 1], .argument = (unsigned int)i + 1};
        if (resource_state(kind, args[i], &row.state, &row.target) != 0) return -1;
        unsigned int j;
        for (j = 0; j < audit->resource_count; j++) {
            const struct ctos_ssh_config_resource *old = &audit->resources[j];
            if (old->kind != row.kind || old->parent != row.parent || old->line != row.line || old->argument != row.argument) continue;
            if (old->state != row.state || old->target != row.target) return ctos_config_reject(CTOS_CONFIG_INPUT, EINVAL);
            break;
        }
        if (j < audit->resource_count) continue;
        if (audit->resource_count == CTOS_SSH_CONFIG_RESOURCES) return ctos_config_reject(CTOS_CONFIG_LIMIT, E2BIG);
        audit->resources[audit->resource_count++] = row;
    }
    return 0;
}
static int catalog_literal(const char *s) {
    /* getline already validated UTF8; exclude ISO controls that the pinned
     * byte-oriented hostname predicate cannot see as Unicode code points. */
    for (size_t i = 0; s[i]; i++) {
        unsigned char c = (unsigned char)s[i];
        if (c < 32 || c == 127 || (c == 0xc2 &&
            (unsigned char)s[i + 1] >= 0x80 && (unsigned char)s[i + 1] <= 0x9f)) return 0;
    }
    return *s != 0 && ctos_config_upstream_ssh_valid_hostname(s);
}
int ctos_config_catalog_line(int kind, int count, char **args) {
    if (ctos_config_check() != 0) return -1;
    struct ctos_ssh_config_catalog *c = current->catalog;
    if (kind == 2) c->has_match = 1;
    if (kind != 1) return 0;
    for (int i = 0; i < count; i++) {
        const char *s = args[i];
        if (*s == '!' || strchr(s, '*') != NULL || strchr(s, '?') != NULL || !catalog_literal(s)) {
            c->omitted_selectors = 1; continue;
        }
        size_t n = strlen(s);
        if (n > CTOS_SSH_CONFIG_NAME_BYTES) return ctos_config_reject(CTOS_CONFIG_LIMIT, E2BIG);
        unsigned int j;
        for (j = 0; j < c->alias_count; j++) if (strcmp(c->entries[j].alias, s) == 0) break;
        if (j != c->alias_count) continue;
        if (c->alias_count == CTOS_SSH_CONFIG_LIST) return ctos_config_reject(CTOS_CONFIG_LIMIT, E2BIG);
        struct ctos_ssh_config_catalog_entry *entry = &c->entries[c->alias_count++];
        entry->object = current->objects[current->stack - 1] + 1;
        entry->line = current->lines[current->stack - 1];
        memcpy(entry->alias, s, n + 1);
    }
    return 0;
}
int ctos_config_catalog_defer(const char *path) {
    if (!ctos_config_scan_mode() || strpbrk(path, "%$") == NULL) return 0;
    if (ctos_config_check() != 0) return -1;
    struct ctos_ssh_config_catalog *c = current->catalog;
    struct ctos_ssh_config_catalog_site site = {current->objects[current->stack - 1] + 1,
        current->lines[current->stack - 1]};
    for (unsigned int i = 0; i < c->deferred_count; i++)
        if (c->deferred[i].object == site.object && c->deferred[i].line == site.line) return 1;
    if (c->deferred_count == CTOS_SSH_CONFIG_LIST) return ctos_config_reject(CTOS_CONFIG_LIMIT, E2BIG);
    c->deferred[c->deferred_count++] = site;
    return 1;
}
int ctos_config_jump_component(const char *host, const char *user) {
    if (host == NULL || *host == 0) return ctos_config_reject(CTOS_CONFIG_INPUT, EINVAL);
    size_t host_length = strlen(host), user_length = user == NULL ? 0 : strlen(user);
    if (host_length > CTOS_SSH_CONFIG_NAME_BYTES || user_length > CTOS_SSH_CONFIG_NAME_BYTES)
        return ctos_config_reject(CTOS_CONFIG_LIMIT, E2BIG);
    if (utf8((const unsigned char *)host, host_length) != 0 ||
        (user != NULL && utf8((const unsigned char *)user, user_length) != 0))
        return ctos_config_reject(CTOS_CONFIG_INPUT, EILSEQ);
    return 0;
}
static int copy_text(char *out, size_t capacity, const char *in, int optional) {
    if (in == NULL) { if (optional) { out[0] = 0; return 0; } errno = EINVAL; return -1; }
    size_t n = strnlen(in, capacity);
    if (n == capacity || (!optional && n == 0) || utf8((const unsigned char *)in, n) != 0) {
        errno = n == capacity ? E2BIG : EINVAL; return -1;
    }
    for (size_t i = 0; i < n; i++) if ((unsigned char)in[i] < 32 || (unsigned char)in[i] == 127) {
        errno = EINVAL; return -1;
    }
    memcpy(out, in, n + 1); return 0;
}
static int object_index(const char *path, unsigned int *out) {
    char relative[513];
    if (ctos_config_check() != 0) return -1;
    if (ctos_ssh_io_resolve(path, 0, relative) != 0) return ctos_config_reject(CTOS_CONFIG_PATH, EACCES);
    for (unsigned int i = 0; i < current->io.count; i++) {
        const struct ctos_ssh_file *f = &current->io.files[i];
        if (f->path.length == strlen(relative) && memcmp(f->path.data, relative, f->path.length) == 0) {
            if (f->type != CTOS_SSH_CONFIG) return ctos_config_reject(CTOS_CONFIG_PATH, EACCES);
            *out = i; return 0;
        }
    }
    return ctos_config_reject(CTOS_CONFIG_PATH, ENOENT);
}
int ctos_config_enter_file(const char *path, int depth) {
    unsigned int object;
    if (ctos_config_check() != 0) return -1;
    if (depth < 0 || (unsigned int)depth != current->stack || (unsigned int)depth > CTOS_SSH_CONFIG_DEPTH)
        return ctos_config_reject(CTOS_CONFIG_LIMIT, E2BIG);
    if (object_index(path, &object) != 0) return -1;
    for (unsigned int i = 0; i < current->stack; i++) if (current->objects[i] == object)
        return ctos_config_reject(CTOS_CONFIG_PATH, ELOOP);
    if (ctos_config_audit_mode() && current->stack != 0) {
        struct ctos_ssh_config_edge edge = {current->objects[current->stack - 1] + 1,
            current->lines[current->stack - 1], object + 1};
        struct ctos_ssh_config_audit *audit = current->audit;
        unsigned int i;
        for (i = 0; i < audit->edge_count; i++) if (audit->edges[i].parent == edge.parent &&
            audit->edges[i].line == edge.line && audit->edges[i].child == edge.child) break;
        if (i == audit->edge_count) {
            if (i == CTOS_SSH_CONFIG_EDGES) return ctos_config_reject(CTOS_CONFIG_LIMIT, E2BIG);
            audit->edges[audit->edge_count++] = edge;
        }
    }
    if (ctos_config_scan_mode() && !current->visited[object]) {
        current->visited[object] = 1; current->catalog->scanned_files++;
    }
    current->objects[current->stack] = object;
    current->lines[current->stack] = 0;
    memset(&current->input[current->stack++], 0, sizeof(current->input[0]));
    return 0;
}
void ctos_config_leave_file(void) {
    if (current != NULL && current->stack != 0) {
        current->stack--; explicit_bzero(&current->input[current->stack], sizeof(current->input[0]));
    }
}
FILE *ctos_config_fopen(const char *path, const char *mode) {
    if (ctos_config_check() != 0) return NULL;
    if (strcmp(mode, "r") != 0) { ctos_config_reject(CTOS_CONFIG_IO, EACCES); return NULL; }
    FILE *f = ctos_ssh_io_open(&current->io, path, CTOS_SSH_CONFIG);
    if (f == NULL) ctos_config_reject(errno == E2BIG ? CTOS_CONFIG_LIMIT : CTOS_CONFIG_IO, errno);
    return f;
}
int ctos_config_fclose(FILE *f) { return ctos_ssh_io_close(&current->io, f); }
ssize_t ctos_config_getline(char **line, size_t *size, FILE *f) {
    if (ctos_config_check() != 0) return -1;
    /* Bounded reader prevents getline from allocating an entire hostile row. */
    if (*size < CTOS_SSH_CONFIG_LINE_BYTES + 2) {
        char *p = realloc(*line, CTOS_SSH_CONFIG_LINE_BYTES + 2);
        if (p == NULL) { ctos_config_reject(CTOS_CONFIG_IO, ENOMEM); return -1; }
        *line = p; *size = CTOS_SSH_CONFIG_LINE_BYTES + 2;
    }
    size_t n = 0;
    struct config_input *input = &current->input[current->stack - 1];
    /* At the limit, an EOF probe is allowed; no byte of another row may be
     * consumed or parsed. IO already charged the bounded reader copy. */
    for (;;) {
        if (input->next == input->length) {
            if (ctos_config_check() != 0) return -1;
            input->length = fread(input->bytes, 1, sizeof(input->bytes), f); input->next = 0;
            if (input->length == 0) {
                if (ferror(f)) {
                    current->lines[current->stack - 1]++;
                    ctos_config_reject(CTOS_CONFIG_IO, errno == 0 ? EIO : errno);
                    return -1;
                }
                break;
            }
        }
        if (current->total_lines >= current->max_lines) { current->lines[current->stack - 1]++; ctos_config_reject(CTOS_CONFIG_LIMIT, E2BIG); return -1; }
        unsigned char c = input->bytes[input->next++];
        if (n == CTOS_SSH_CONFIG_LINE_BYTES) { current->lines[current->stack - 1]++; ctos_config_reject(CTOS_CONFIG_LIMIT, E2BIG); return -1; }
        (*line)[n++] = (char)c;
        if (c == '\n') break;
    }
    if (n == 0) return -1;
    (*line)[n] = 0;
    current->lines[current->stack - 1]++;
    if (utf8((const unsigned char *)*line, n) != 0) { ctos_config_reject(CTOS_CONFIG_INPUT, EILSEQ); return -1; }
    current->total_lines++;
    return ctos_config_check() == 0 ? (ssize_t)n : -1;
}
struct config_directory { char path[sizeof(CTOS_SSH_VIRTUAL_HOME) + 514]; unsigned int next; struct dirent entry; };
static int config_stat(const char *path, struct stat *st) {
    if (ctos_config_check() != 0) return -1;
    return ctos_ssh_io_stat(&current->io, path, st);
}
static void *config_opendir(const char *path) {
    struct stat st;
    if (config_stat(path, &st) != 0 || !S_ISDIR(st.st_mode)) { errno = ENOTDIR; return NULL; }
    struct config_directory *d = calloc(1, sizeof(*d));
    if (d == NULL) { ctos_config_reject(CTOS_CONFIG_IO, ENOMEM); return NULL; }
    size_t n = strnlen(path, sizeof(d->path));
    if (n == sizeof(d->path)) { free(d); ctos_config_reject(CTOS_CONFIG_LIMIT, E2BIG); return NULL; }
    memcpy(d->path, path, n + 1); return d;
}
static struct dirent *config_readdir(void *opaque) {
    struct config_directory *d = opaque;
    if (ctos_config_check() != 0) return NULL;
    int r = ctos_ssh_io_child(&current->io, d->path, d->next++, d->entry.d_name);
    if (r < 0) ctos_config_reject(CTOS_CONFIG_IO, errno);
    return r == 1 ? &d->entry : NULL;
}
static void config_closedir(void *opaque) { free(opaque); }
int ctos_config_glob(const char *pattern, int flags, int (*err)(const char *, int), glob_t *out) {
    char relative[513], path[sizeof(CTOS_SSH_VIRTUAL_HOME) + 514];
    (void)err;
    if (ctos_config_check() != 0) return GLOB_ABORTED;
    if (ctos_ssh_io_resolve(pattern, 1, relative) != 0) { ctos_config_reject(CTOS_CONFIG_PATH, EACCES); return GLOB_ABORTED; }
    snprintf(path, sizeof(path), "%s/%s", CTOS_SSH_VIRTUAL_HOME, relative);
    out->gl_opendir = config_opendir; out->gl_readdir = config_readdir;
    out->gl_closedir = config_closedir; out->gl_stat = config_stat; out->gl_lstat = config_stat;
    int r = glob(path, (flags & ~GLOB_TILDE) | GLOB_ALTDIRFUNC, NULL, out);
    if (ctos_config_check() != 0) return GLOB_ABORTED;
    if (r != 0) ctos_config_reject(r == GLOB_NOMATCH ? CTOS_CONFIG_PATH : CTOS_CONFIG_IO,
        r == GLOB_NOMATCH ? ENOENT : EIO);
    return r;
}
int ctos_config_port(const char *s) {
    if (s == NULL || *s == 0) { ctos_config_reject(CTOS_CONFIG_INPUT, EINVAL); return -1; }
    unsigned int port = 0;
    for (size_t i = 0; s[i]; i++) {
        if (s[i] < '0' || s[i] > '9' || port > 6553) { ctos_config_reject(CTOS_CONFIG_INPUT, EINVAL); return -1; }
        port = port * 10 + (unsigned int)(s[i] - '0');
        if (port > 65535) { ctos_config_reject(CTOS_CONFIG_INPUT, EINVAL); return -1; }
    }
    return (int)port;
}
char *ctos_config_dollar(int *error, const char *s) {
    *error = 0;
    if (strstr(s, "${") != NULL) { *error = 1; ctos_config_reject(CTOS_CONFIG_UNSUPPORTED, ENOTSUP); return NULL; }
    char *result = strdup(s);
    if (result == NULL) { *error = 1; ctos_config_reject(CTOS_CONFIG_IO, ENOMEM); }
    return result;
}
char *ctos_config_percent(const char *s, ...) {
    const char *values[256] = {0};
    va_list ap;
    va_start(ap, s);
    const char *keys;
    while ((keys = va_arg(ap, const char *)) != NULL) {
        const char *v = va_arg(ap, const char *);
        for (size_t i = 0; keys[i]; i++) values[(unsigned char)keys[i]] = v;
    }
    va_end(ap);
    if (strstr(s, "${") != NULL) { ctos_config_reject(CTOS_CONFIG_UNSUPPORTED, ENOTSUP); return NULL; }
    char *out = malloc(CTOS_SSH_CONFIG_LINE_BYTES + 1);
    if (out == NULL) { ctos_config_reject(CTOS_CONFIG_IO, ENOMEM); return NULL; }
    size_t n = 0;
    for (size_t i = 0; s[i]; i++) {
        const char *v;
        char one[2] = {s[i], 0};
        if (s[i] == '%') {
            unsigned char key = (unsigned char)s[++i];
            if (key == '%') v = "%";
            else if (key == 0 || (v = values[key]) == NULL) {
                free(out); ctos_config_reject(key == 'r' ? CTOS_CONFIG_USER_REQUIRED : CTOS_CONFIG_EXPANSION, EINVAL); return NULL;
            }
        } else v = one;
        size_t size = strlen(v);
        if (size > CTOS_SSH_CONFIG_LINE_BYTES - n) { free(out); ctos_config_reject(CTOS_CONFIG_LIMIT, E2BIG); return NULL; }
        memcpy(out + n, v, size); n += size;
    }
    out[n] = 0; return out;
}
const char *ctos_config_remote_user(const Options *o) {
    return o->user != NULL ? o->user : current->fallback[0] != 0 ? current->fallback : NULL;
}
char *ctos_config_include(const char *path, Options *o, const char *host_arg, const char *original, int final) {
    if (ctos_config_scan_mode()) {
        char *copy = strdup(path);
        if (copy == NULL) ctos_config_reject(CTOS_CONFIG_IO, ENOMEM);
        return copy;
    }
    char *host = final ? strdup(o->hostname) : o->hostname != NULL ?
        ctos_config_percent(o->hostname, "h", host_arg, (char *)NULL) : strdup(host_arg);
    if (host == NULL) return NULL;
    const char *user = ctos_config_remote_user(o);
    char port[8], uid[32], short_host[256];
    snprintf(port, sizeof(port), "%d", o->port <= 0 ? 22 : o->port);
    snprintf(uid, sizeof(uid), "%llu", (unsigned long long)current->request.local_uid);
    memcpy(short_host, current->local_host, sizeof(short_host)); short_host[strcspn(short_host, ".")] = 0;
    const char *jump = o->jump_host == NULL || strcmp(o->jump_host, "none") == 0 ? "" : o->jump_host;
    char *hash = NULL;
    int needs_hash = 0;
    for (size_t i = 0; path[i]; i++) {
        if (path[i] != '%') continue;
        if (path[++i] == 0) break;
        if (path[i] == 'C') needs_hash = 1;
    }
    if (needs_hash) {
        if (user == NULL) { free(host); ctos_config_reject(CTOS_CONFIG_USER_REQUIRED, EINVAL); return NULL; }
        hash = ctos_config_upstream_ssh_connection_hash(current->local_host, host, port, user, jump);
        if (hash == NULL) { free(host); return NULL; }
    }
    char *result = ctos_config_percent(path, "C", hash, "L", short_host, "d", CTOS_SSH_VIRTUAL_HOME,
        "h", host, "k", o->host_key_alias == NULL ? host : o->host_key_alias,
        "l", current->local_host, "n", original, "p", port, "r", user,
        "u", current->local_user, "i", uid, "j", jump, (char *)NULL);
    free(hash); free(host); return result;
}
void ctos_config_array(const char *file, int line, const char *key, char ***array, u_int *n, const char *s) {
    if (*n >= CTOS_SSH_CONFIG_LIST) { ctos_config_reject(CTOS_CONFIG_LIMIT, E2BIG); return; }
    opt_array_append(file, line, key, array, n, s);
}
int ctos_config_kex_valid(const char *names) {
    if (names == NULL || *names == 0) return 0;
    char *copy = strdup(names), *cursor = copy, *name;
    if (copy == NULL) { ctos_config_reject(CTOS_CONFIG_IO, ENOMEM); return 0; }
    int valid = 1;
    /* Same exact-membership rule as pinned kex_names_valid, without logging. */
    while ((name = strsep(&cursor, ",")) != NULL && *name != 0) {
        if (!kex_name_valid(name)) { valid = 0; break; }
    }
    free(copy); return valid;
}
static int clear_or_none(const char *p) { return p == NULL || strcasecmp(p, "none") == 0; }
int ctos_config_options_check(const Options *o) {
    if (ctos_config_check() != 0) return -1;
    if (!clear_or_none(o->local_command) || !clear_or_none(o->proxy_command) || !clear_or_none(o->known_hosts_command))
        return ctos_config_reject(CTOS_CONFIG_COMMAND, EACCES);
    if ((o->strict_host_key_checking != -1 && o->strict_host_key_checking != SSH_STRICT_HOSTKEY_YES &&
        o->strict_host_key_checking != SSH_STRICT_HOSTKEY_ASK) || o->verify_host_key_dns > 0 || o->no_host_authentication_for_localhost > 0)
        return ctos_config_reject(CTOS_CONFIG_TRUST, EACCES);
    if (o->forward_agent > 0 || o->forward_agent_sock_path != NULL || o->add_keys_to_agent > 0 ||
        !clear_or_none(o->identity_agent) || !clear_or_none(o->pkcs11_provider) || !clear_or_none(o->sk_provider) ||
        o->control_master > 0 || o->control_persist > 0 || !clear_or_none(o->control_path) ||
        o->forward_x11 > 0 || o->forward_x11_trusted > 0 || o->hostbased_authentication > 0 ||
        o->enable_ssh_keysign > 0 || o->gss_authentication > 0 || o->gss_deleg_creds > 0 ||
        o->fork_after_authentication > 0 || o->tun_open > 0 || o->enable_escape_commandline > 0 ||
        o->permit_local_command > 0 || o->proxy_use_fdpass > 0)
        return ctos_config_reject(CTOS_CONFIG_EXTERNAL, EACCES);
    if (o->canonicalize_hostname > 0 || ctos_config_upstream_config_has_permitted_cnames((Options *)o))
        return ctos_config_reject(CTOS_CONFIG_UNSUPPORTED, ENOTSUP);
    if ((unsigned int)(o->num_local_forwards + o->num_remote_forwards) > CTOS_SSH_CONFIG_FORWARDS ||
        o->num_send_env > CTOS_SSH_CONFIG_LIST || o->num_setenv > CTOS_SSH_CONFIG_LIST ||
        (unsigned int)o->num_canonical_domains > CTOS_SSH_CONFIG_LIST || (unsigned int)o->num_permitted_cnames > CTOS_SSH_CONFIG_LIST)
        return ctos_config_reject(CTOS_CONFIG_LIMIT, E2BIG);
    return 0;
}
static int set_request(struct ctos_ssh_config_eval *e, const struct ctos_ssh_config_request *r) {
    if (r == NULL || r->port > 65535) { errno = EINVAL; return -1; }
    if (copy_text(e->alias, sizeof(e->alias), r->alias, 0) != 0 ||
        copy_text(e->user, sizeof(e->user), r->remote_user, 1) != 0 ||
        copy_text(e->fallback, sizeof(e->fallback), r->remote_user_fallback, 1) != 0 ||
        copy_text(e->local_user, sizeof(e->local_user), r->local_user, 0) != 0 ||
        copy_text(e->local_host, sizeof(e->local_host), r->local_hostname, 0) != 0 ||
        copy_text(e->tag, sizeof(e->tag), r->tag, 1) != 0 ||
        copy_text(e->command, sizeof(e->command), r->remote_command, 1) != 0) return -1;
    if (!ctos_config_upstream_ssh_valid_hostname(e->alias) ||
        (e->user[0] && !ctos_config_upstream_ssh_valid_ruser(e->user)) ||
        (e->fallback[0] && !ctos_config_upstream_ssh_valid_ruser(e->fallback))) { errno = EINVAL; return -1; }
    e->request = *r;
    e->request.alias = e->alias; e->request.remote_user = e->user[0] ? e->user : NULL;
    e->request.remote_user_fallback = e->fallback[0] ? e->fallback : NULL;
    e->request.local_user = e->local_user; e->request.local_hostname = e->local_host;
    e->request.tag = e->tag[0] ? e->tag : NULL; e->request.remote_command = e->command[0] ? e->command : NULL;
    return 0;
}
static struct ctos_ssh_config_eval *new_eval(const struct ctos_ssh_config_request *r, int catalog_mode) {
    struct ctos_ssh_config_eval *e = calloc(1, sizeof(*e));
    if (e == NULL) return NULL;
    if (!catalog_mode && set_request(e, r) != 0) { explicit_bzero(e, sizeof(*e)); free(e); return NULL; }
    e->owner = pthread_self(); e->pid = getpid(); e->uid = getuid(); atomic_init(&e->cancelled, 0);
    e->max_lines = CTOS_SSH_CONFIG_LINES;
    e->mode = catalog_mode ? CTOS_CONFIG_CATALOG : CTOS_CONFIG_EFFECTIVE;
    if (catalog_mode && (e->catalog = calloc(1, sizeof(*e->catalog))) == NULL) { free(e); return NULL; }
    return e;
}
static int open_mode(struct ctos_ssh_view_borrow *borrow,
    const struct ctos_ssh_config_request *r, int catalog_mode, struct ctos_ssh_config_eval **out) {
    if (out == NULL) { errno = EINVAL; return -1; }
    *out = NULL;
    if (borrow == NULL || (!catalog_mode && (r == NULL || r->port > 65535))) { errno = EINVAL; return -1; }
    if (ctos_ssh_view_borrow_live(borrow) != 0) return -1;
    struct ctos_ssh_config_eval *e = new_eval(r, catalog_mode);
    if (e == NULL) return -1;
    const struct ctos_ssh_view *view = ctos_ssh_view_borrow_view(borrow);
    if (view == NULL || view->root.length > 512 ||
        ctos_ssh_view_path(view->root.data, view->root.length, 0) != 0) { errno = EINVAL; goto failed; }
    snprintf(e->root, sizeof(e->root), "%s/%.*s", CTOS_SSH_VIRTUAL_HOME, (int)view->root.length, view->root.data);
    if (ctos_ssh_view_lookup(view, view->root.data, view->root.length, CTOS_SSH_CONFIG) == NULL ||
        ctos_ssh_io_init(&e->io, view, fclose) != 0 || ctos_ssh_view_borrow_live(borrow) != 0) goto failed;
    e->borrow = borrow; *out = e; return 0;
failed: {
        int failure = errno;
        if (e->catalog != NULL) explicit_bzero(e->catalog, sizeof(*e->catalog));
        free(e->catalog); explicit_bzero(e, sizeof(*e)); free(e); errno = failure; return -1;
    }
}
int ctos_ssh_config_eval_open(struct ctos_ssh_view_borrow *borrow,
    const struct ctos_ssh_config_request *r, struct ctos_ssh_config_eval **out) {
    return open_mode(borrow, r, 0, out);
}
int ctos_ssh_config_eval_open_catalog(struct ctos_ssh_view_borrow *borrow, struct ctos_ssh_config_eval **out) {
    return open_mode(borrow, NULL, 1, out);
}
static int detached_shape(const struct ctos_ssh_config_file *files, unsigned int count,
    unsigned int root, size_t *length) {
    if (files == NULL || count == 0 || count > CTOS_SSH_VIEW_FILES || root >= count) { errno = EINVAL; return -1; }
    *length = 0;
    size_t body_bytes = 0;
    for (unsigned int i = 0; i < count; i++) {
        const struct ctos_ssh_config_file *f = &files[i];
        if (f->type < CTOS_SSH_CONFIG || f->type > CTOS_SSH_KNOWN_HOSTS ||
            ctos_ssh_view_path(f->path.data, f->path.length, 0) != 0 ||
            (f->body.length != 0 && f->body.data == NULL) ||
            (f->type != CTOS_SSH_CONFIG && (f->body.data != NULL || f->body.length != 0))) {
            errno = EINVAL; return -1;
        }
        if (f->body.length > CTOS_SSH_VIEW_FILE_BYTES || f->body.length > CTOS_SSH_VIEW_TOTAL_BYTES - body_bytes) {
            errno = E2BIG; return -1;
        }
        body_bytes += f->body.length;
        *length += f->path.length + f->body.length;
        for (unsigned int j = 0; j < i; j++) {
            struct ctos_ssh_slice a = f->path, b = files[j].path;
            size_t n = a.length < b.length ? a.length : b.length;
            if (memcmp(a.data, b.data, n) == 0 && (a.length == b.length ||
                (a.length > n && a.data[n] == '/') || (b.length > n && b.data[n] == '/'))) {
                errno = EINVAL; return -1;
            }
        }
    }
    if (files[root].type != CTOS_SSH_CONFIG) { errno = EINVAL; return -1; }
    return 0;
}
static int open_detached(const struct ctos_ssh_config_file *files, unsigned int count,
    unsigned int root, uint64_t deadline, const struct ctos_ssh_config_request *r,
    int catalog_mode, struct ctos_ssh_config_eval **out) {
    if (out == NULL) { errno = EINVAL; return -1; }
    *out = NULL;
    uint64_t now;
    if (milliseconds(&now) != 0) return -1;
    if (deadline <= now) { errno = ETIMEDOUT; return -1; }
    if (deadline - now > 60000) { errno = EINVAL; return -1; }
    size_t length;
    if (detached_shape(files, count, root, &length) != 0) return -1;
    struct ctos_ssh_config_eval *e = new_eval(r, catalog_mode);
    if (e == NULL) return -1;
    e->detached = 1; e->deadline = deadline;
    void *mapping = mmap(NULL, length, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (mapping == MAP_FAILED) goto failed;
    /* Record cleanup ownership before any fallible protection or input copy. */
    e->owned = mapping; e->owned_length = length;
    if (madvise(mapping, length, MADV_DONTDUMP) != 0 || mlock(mapping, length) != 0) goto failed;
    e->owned_locked = 1;
    if (live(e) != 0) goto failed;
    /* Only pointer/type/length metadata lives outside protected storage, like
     * the live view's parsed table. Every path and CONFIG byte stays locked. */
    e->owned_file_bytes = count * sizeof(*e->owned_files);
    e->owned_files = calloc(count, sizeof(*e->owned_files));
    if (e->owned_files == NULL) goto failed;
    struct ctos_ssh_file *owned_files = e->owned_files;
    unsigned char *next = e->owned;
    for (unsigned int i = 0; i < count; i++) {
        if (live(e) != 0) goto failed;
        owned_files[i].type = files[i].type;
        owned_files[i].path = (struct ctos_ssh_slice){next, files[i].path.length};
        memcpy(next, files[i].path.data, files[i].path.length); next += files[i].path.length;
        owned_files[i].declared_bytes = (unsigned int)files[i].body.length;
        owned_files[i].body = (struct ctos_ssh_slice){next, files[i].body.length};
        if (files[i].body.length != 0) memcpy(next, files[i].body.data, files[i].body.length);
        next += files[i].body.length;
    }
    snprintf(e->root, sizeof(e->root), "%s/%.*s", CTOS_SSH_VIRTUAL_HOME,
        (int)owned_files[root].path.length, owned_files[root].path.data);
    if (ctos_ssh_io_init_source(&e->io, owned_files, count, source_live, e, fclose) != 0 || live(e) != 0) goto failed;
    *out = e; return 0;
failed: {
        int failure = errno == 0 ? EIO : errno;
        if (ctos_ssh_config_eval_close(e) != 0) *out = e;
        else (void)ctos_ssh_config_eval_destroy(e);
        errno = failure; return -1;
    }
}
int ctos_ssh_config_eval_open_detached(const struct ctos_ssh_config_file *files,
    unsigned int count, unsigned int root, uint64_t deadline, const struct ctos_ssh_config_request *r,
    struct ctos_ssh_config_eval **out) {
    return open_detached(files, count, root, deadline, r, 0, out);
}
int ctos_ssh_config_eval_open_catalog_detached(const struct ctos_ssh_config_file *files,
    unsigned int count, unsigned int root, uint64_t deadline, struct ctos_ssh_config_eval **out) {
    return open_detached(files, count, root, deadline, NULL, 1, out);
}
int ctos_ssh_config_eval_prepare_detached(int catalog_mode, uint64_t deadline,
    struct ctos_ssh_config_eval **out) {
    if (out == NULL) { errno = EINVAL; return -1; }
    *out = NULL;
    uint64_t now;
    if (catalog_mode != 0 && catalog_mode != 1) { errno = EINVAL; return -1; }
    if (milliseconds(&now) != 0) return -1;
    if (deadline <= now) { errno = ETIMEDOUT; return -1; }
    if (deadline - now > 60000) { errno = EINVAL; return -1; }
    struct ctos_ssh_config_eval *e = new_eval(NULL, 1);
    if (e == NULL) return -1;
    if (!catalog_mode) { free(e->catalog); e->catalog = NULL; }
    e->mode = catalog_mode ? CTOS_CONFIG_CATALOG : CTOS_CONFIG_EFFECTIVE; e->detached = 1; e->deadline = deadline;
    e->state = CTOS_CONFIG_LOADING; *out = e; return 0;
}
int ctos_ssh_config_eval_prepare_direct_detached(uint64_t deadline, struct ctos_ssh_config_eval **out) {
    if (ctos_ssh_config_eval_prepare_detached(0, deadline, out) != 0) return -1;
    struct ctos_ssh_config_eval *e = *out;
    e->direct_plan = calloc(1, sizeof(*e->direct_plan));
    if (e->direct_plan == NULL) {
        int failure = errno;
        (void)ctos_ssh_config_eval_close(e); (void)ctos_ssh_config_eval_destroy(e);
        *out = NULL; errno = failure; return -1;
    }
    return 0;
}
int ctos_ssh_config_eval_prepare_audit_detached(uint64_t deadline, struct ctos_ssh_config_eval **out) {
    if (ctos_ssh_config_eval_prepare_detached(1, deadline, out) != 0) return -1;
    struct ctos_ssh_config_eval *e = *out;
    e->audit = calloc(1, sizeof(*e->audit));
    if (e->audit == NULL) {
        int failure = errno;
        (void)ctos_ssh_config_eval_close(e); (void)ctos_ssh_config_eval_destroy(e);
        *out = NULL; errno = failure; return -1;
    }
    e->audit->catalog = e->catalog; e->mode = CTOS_CONFIG_AUDIT; return 0;
}
int ctos_ssh_config_eval_prepare_resource_audit_detached(uint64_t deadline, struct ctos_ssh_config_eval **out) {
    if (ctos_ssh_config_eval_prepare_audit_detached(deadline, out) != 0) return -1;
    struct ctos_ssh_config_eval *e = *out;
    e->resource_audit = calloc(1, sizeof(*e->resource_audit));
    if (e->resource_audit == NULL) {
        int failure = errno;
        (void)ctos_ssh_config_eval_close(e); (void)ctos_ssh_config_eval_destroy(e);
        *out = NULL; errno = failure; return -1;
    }
    e->resource_audit->audit = e->audit; e->mode = CTOS_CONFIG_RESOURCE_AUDIT; return 0;
}
int ctos_ssh_config_eval_detached_buffer(struct ctos_ssh_config_eval *e, size_t length, unsigned char **out) {
    if (out == NULL) { errno = EINVAL; return -1; }
    *out = NULL;
    if (live(e) != 0) return -1;
    if (!e->detached || e->state != CTOS_CONFIG_LOADING || e->owned != NULL) { errno = EALREADY; return -1; }
    if (length < 24 || length > CTOS_SSH_CONFIG_SOURCE_BYTES) { errno = E2BIG; return -1; }
    void *mapping = mmap(NULL, length, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (mapping == MAP_FAILED) return -1;
    e->owned = mapping; e->owned_length = length; e->staged_length = length;
    if (madvise(mapping, e->owned_length, MADV_DONTDUMP) != 0 || mlock(mapping, e->owned_length) != 0) return -1;
    e->owned_locked = 1;
    if (live(e) != 0) return -1;
    *out = e->owned; return 0;
}
int ctos_ssh_config_eval_loading_live(const struct ctos_ssh_config_eval *e) {
    if (live(e) != 0) return -1;
    if (e->state != CTOS_CONFIG_LOADING) { errno = EALREADY; return -1; }
    return 0;
}
struct source_cursor { const unsigned char *p; size_t left; };
static int source_u32(struct source_cursor *c, uint32_t *out) {
    if (c->left < 4) { errno = EINVAL; return -1; }
    *out = (uint32_t)c->p[0] << 24 | (uint32_t)c->p[1] << 16 | (uint32_t)c->p[2] << 8 | c->p[3];
    c->p += 4; c->left -= 4; return 0;
}
static int source_slice(struct source_cursor *c, struct ctos_ssh_slice *out, size_t maximum) {
    uint32_t n;
    if (source_u32(c, &n) != 0) return -1;
    if (n > maximum || n > c->left) { errno = n > maximum ? E2BIG : EINVAL; return -1; }
    *out = (struct ctos_ssh_slice){n ? c->p : NULL, n}; c->p += n; c->left -= n; return 0;
}
int ctos_ssh_config_eval_complete_detached(struct ctos_ssh_config_eval *e, unsigned int root,
    const struct ctos_ssh_config_request *request) {
    if (live(e) != 0) return -1;
    if (e->state != CTOS_CONFIG_LOADING || !e->owned_locked || e->staged_length == 0
        || e->owned_files != NULL) { errno = EALREADY; return -1; }
    if ((e->mode != CTOS_CONFIG_EFFECTIVE && request != NULL) ||
        (e->mode == CTOS_CONFIG_EFFECTIVE && set_request(e, request) != 0)) { errno = EINVAL; return -1; }
    struct source_cursor c = {e->owned, e->staged_length};
    uint32_t magic, version, count, opens, bytes, lines;
    if (source_u32(&c, &magic) != 0 || source_u32(&c, &version) != 0 || source_u32(&c, &count) != 0 ||
        source_u32(&c, &opens) != 0 || source_u32(&c, &bytes) != 0 || source_u32(&c, &lines) != 0) return -1;
    if (magic != 0x43534449U || version != 1 || count == 0 || count > CTOS_SSH_VIEW_FILES || root >= count ||
        opens > CTOS_SSH_IO_OPENS || bytes > CTOS_SSH_IO_READ_BYTES || lines > CTOS_SSH_CONFIG_LINES) { errno = EINVAL; return -1; }
    struct ctos_ssh_config_file files[CTOS_SSH_VIEW_FILES] = {0};
    for (unsigned int i = 0; i < count; i++) {
        if (live(e) != 0 || source_u32(&c, &files[i].type) != 0 ||
            source_slice(&c, &files[i].path, 512) != 0 || source_slice(&c, &files[i].body, CTOS_SSH_VIEW_FILE_BYTES) != 0) return -1;
        /* CSDI explicitly rejects ISO C1 controls as well as the established
         * path policy. The original native detached API retains its policy. */
        for (size_t j = 0; j + 1 < files[i].path.length; j++)
            if (files[i].path.data[j] == 0xc2 && files[i].path.data[j + 1] >= 0x80 && files[i].path.data[j + 1] <= 0x9f) { errno = EINVAL; return -1; }
    }
    size_t unused;
    if (c.left != 0) { errno = EINVAL; return -1; }
    if (detached_shape(files, count, root, &unused) != 0) return -1;
    e->owned_file_bytes = count * sizeof(*e->owned_files);
    e->owned_files = calloc(count, sizeof(*e->owned_files));
    if (e->owned_files == NULL) return -1;
    struct ctos_ssh_file *table = e->owned_files;
    for (unsigned int i = 0; i < count; i++) {
        table[i].type = files[i].type; table[i].path = files[i].path;
        table[i].body = files[i].body; table[i].declared_bytes = (unsigned int)files[i].body.length;
    }
    snprintf(e->root, sizeof(e->root), "%s/%.*s", CTOS_SSH_VIRTUAL_HOME, (int)table[root].path.length, table[root].path.data);
    if (ctos_ssh_io_init_source(&e->io, table, count, source_live, e, fclose) != 0) return -1;
    e->io.max_opens = opens; e->io.max_read_bytes = bytes; e->max_lines = lines;
    if (live(e) != 0) return -1;
    e->state = CTOS_CONFIG_NEW; return 0;
}
static int direct_path(struct ctos_ssh_config_eval *e, const char *raw, unsigned int type,
    struct config_origin origin, char out[513]) {
    e->diagnostic_origin = origin;
    char *expanded = ctos_config_include(raw, &e->options, e->alias, e->alias, 1);
    if (expanded == NULL) return -1;
    char absolute[sizeof(CTOS_SSH_VIRTUAL_HOME) + 514];
    const char *path = expanded;
    if (strncmp(expanded, "~/", 2) == 0) {
        if (strlen(expanded + 2) > 512) { free(expanded); return ctos_config_reject(CTOS_CONFIG_PATH, EACCES); }
        snprintf(absolute, sizeof(absolute), "%s/%s", CTOS_SSH_VIRTUAL_HOME, expanded + 2);
        path = absolute;
    }
    if (ctos_ssh_io_resolve(path, 0, out) != 0) { free(expanded); return ctos_config_reject(CTOS_CONFIG_PATH, EACCES); }
    free(expanded);
    size_t n = strlen(out);
    for (unsigned int i = 0; i < e->io.count; i++) {
        const struct ctos_ssh_file *file = &e->io.files[i];
        if (file->path.length == n && memcmp(file->path.data, out, n) == 0 && file->type != type)
            return ctos_config_reject(CTOS_CONFIG_PATH, EACCES);
    }
    e->diagnostic_origin = (struct config_origin){0};
    return 0;
}
static int finish_direct_plan(struct ctos_ssh_config_eval *e) {
    struct ctos_ssh_config_direct_plan *plan = e->direct_plan;
    if (plan == NULL) return 0;
    const Options *o = &e->options;
    if (o->num_identity_files < 0 || o->num_identity_files > (int)CTOS_SSH_CONFIG_LIST ||
        o->num_user_hostfiles > CTOS_SSH_CONFIG_HOSTFILES || o->request_tty == REQUEST_TTY_NO ||
        o->session_type != SESSION_TYPE_DEFAULT || !clear_or_none(o->jump_host) ||
        !clear_or_none(o->remote_command) || o->num_local_forwards != 0 || o->num_remote_forwards != 0 ||
        o->num_certificate_files != 0 || o->host_key_alias != NULL ||
        (o->strict_host_key_checking != SSH_STRICT_HOSTKEY_ASK && o->strict_host_key_checking != SSH_STRICT_HOSTKEY_YES))
        return ctos_config_reject(CTOS_CONFIG_UNSUPPORTED, ENOTSUP);
    plan->summary = e->summary;
    plan->identities_only = o->identities_only != 0;
    plan->pubkey_authentication = (o->pubkey_authentication & SSH_PUBKEY_AUTH_UNBOUND) != 0;
    plan->password_authentication = o->password_authentication != 0 && o->preferred_authentications == NULL;
    plan->connect_timeout_millis = o->connection_timeout > 0 && o->connection_timeout < 60
        ? (unsigned int)o->connection_timeout * 1000U : 60000U;
    plan->strict_host_key_checking = (unsigned int)o->strict_host_key_checking;
    for (int i = 0; i < o->num_identity_files; i++) {
        if (strcasecmp(o->identity_files[i], "none") == 0) continue;
        if (direct_path(e, o->identity_files[i], CTOS_SSH_PRIVATE_KEY, e->identity_origin[i],
            plan->identity_files[plan->identity_count]) != 0) return -1;
        plan->identity_count++;
    }
    for (unsigned int i = 0; i < o->num_user_hostfiles; i++) {
        if (strcasecmp(o->user_hostfiles[i], "none") == 0) continue;
        if (direct_path(e, o->user_hostfiles[i], CTOS_SSH_KNOWN_HOSTS, e->hostfile_origin[i],
            plan->user_hostfiles[plan->hostfile_count]) != 0) return -1;
        plan->hostfile_count++;
    }
    if (plan->hostfile_count == 0) return ctos_config_reject(CTOS_CONFIG_UNSUPPORTED, ENOTSUP);
    return ctos_config_check();
}
int ctos_ssh_config_eval_run(struct ctos_ssh_config_eval *e, struct ctos_ssh_config_error *error) {
    if (error != NULL) memset(error, 0, sizeof(*error));
    if (owner(e) != 0) return -1;
    if (e->state == CTOS_CONFIG_RUNNING) { errno = EBUSY; return -1; }
    if (e->state != CTOS_CONFIG_NEW) { errno = EALREADY; return -1; }
    if (pthread_mutex_trylock(&parser_lock) != 0) { errno = EBUSY; return -1; }
    current = e; e->state = CTOS_CONFIG_RUNNING;
    int ret = -1, final = 0;
    if (ctos_config_check() != 0) goto done;
    if (e->mode != CTOS_CONFIG_EFFECTIVE) {
        ctos_config_upstream_initialize_options(&e->options); e->options_live = 1;
        /* No Request/passwd or target context exists for declaration scanning. */
        if (ctos_config_upstream_read_config_file(e->root, NULL, NULL, NULL, NULL,
            &e->options, SSHCONF_USERCONF, NULL) == 1 && ctos_config_check() == 0) ret = 0;
        goto done;
    }
    /* Avoid implicit OpenSSL config/environment loading on the first digest. */
    if (OPENSSL_init_crypto(OPENSSL_INIT_NO_LOAD_CONFIG, NULL) != 1) { ctos_config_reject(CTOS_CONFIG_DEFAULTS, EIO); goto done; }
    ctos_config_upstream_initialize_options(&e->options); e->options_live = 1;
    Options *o = &e->options;
    o->host_arg = strdup(e->alias);
    if (e->user[0]) o->user = strdup(e->user);
    if (e->tag[0]) o->tag = strdup(e->tag);
    if (e->request.port) o->port = (int)e->request.port;
    if (o->host_arg == NULL || (e->user[0] && o->user == NULL) || (e->tag[0] && o->tag == NULL)) {
        ctos_config_reject(CTOS_CONFIG_IO, ENOMEM); goto done;
    }
    struct passwd pw = {.pw_name = e->local_user, .pw_dir = CTOS_SSH_VIRTUAL_HOME, .pw_uid = e->request.local_uid};
    if (ctos_config_upstream_read_config_file(e->root, &pw, e->alias, e->alias,
        e->request.remote_command, o, SSHCONF_USERCONF, &final) != 1) goto done;
    ctos_config_upstream_fill_default_options_for_canonicalization(o);
    e->diagnostic_origin = e->host_origin;
    char *hostname = o->hostname == NULL ? strdup(e->alias) : ctos_config_percent(o->hostname, "h", e->alias, (char *)NULL);
    if (hostname == NULL) goto done;
    if (strlen(hostname) > CTOS_SSH_CONFIG_NAME_BYTES || !ctos_config_upstream_ssh_valid_hostname(hostname) || *hostname == 0) {
        free(hostname); ctos_config_reject(CTOS_CONFIG_INPUT, EINVAL); goto done;
    }
    /* Pinned ssh.c is_addr_fast predicate: preserve numeric/scoped address
     * spelling, without entering getaddrinfo or interface-name resolution. */
    int address = strchr(hostname, ':') != NULL || strchr(hostname, '%') != NULL ||
        strspn(hostname, "0123456789.") == strlen(hostname);
    if (!address) for (char *p = hostname; *p; p++) if (*p >= 'A' && *p <= 'Z') *p += 'a' - 'A';
    free(o->hostname); o->hostname = hostname;
    e->diagnostic_origin = (struct config_origin){0};
    if (ctos_config_options_check(o) != 0) goto done;
    if (final && ctos_config_upstream_read_config_file(e->root, &pw, o->hostname, e->alias,
        e->request.remote_command, o, SSHCONF_USERCONF | SSHCONF_FINAL, NULL) != 1) goto done;
    if (ctos_config_options_check(o) != 0) goto done;
    if (o->user != NULL && !e->user[0]) {
        e->diagnostic_origin = e->user_origin;
        char port[8], uid[32], short_host[256];
        snprintf(port, sizeof(port), "%d", o->port > 0 ? o->port : 22);
        snprintf(uid, sizeof(uid), "%llu", (unsigned long long)e->request.local_uid);
        memcpy(short_host, e->local_host, sizeof(short_host)); short_host[strcspn(short_host, ".")] = 0;
        char *user = ctos_config_percent(o->user, "h", o->hostname, "n", e->alias,
            "u", e->local_user, "l", e->local_host, "d", CTOS_SSH_VIRTUAL_HOME,
            "p", port, "i", uid, "L", short_host,
            "k", o->host_key_alias == NULL ? o->hostname : o->host_key_alias,
            "j", clear_or_none(o->jump_host) ? "" : o->jump_host, (char *)NULL);
        if (user == NULL) goto done;
        if (strlen(user) > CTOS_SSH_CONFIG_NAME_BYTES || *user == 0 || !ctos_config_upstream_ssh_valid_ruser(user)) {
            free(user); ctos_config_reject(CTOS_CONFIG_INPUT, EINVAL); goto done;
        }
        free(o->user); o->user = user;
        e->diagnostic_origin = (struct config_origin){0};
    }
    if (ctos_config_upstream_fill_default_options(o) != 0) { ctos_config_reject(CTOS_CONFIG_DEFAULTS, EINVAL); goto done; }
    /* Upstream defaults select implicit agents/providers. No provider is used
     * here; policy checks apply before defaults, to explicit declarations. */
    if (ctos_config_check() != 0) goto done;
    memcpy(e->summary.alias, e->alias, strlen(e->alias) + 1);
    memcpy(e->summary.hostname, o->hostname, strlen(o->hostname) + 1);
    if (o->user) memcpy(e->summary.user, o->user, strlen(o->user) + 1);
    e->summary.user_required = o->user == NULL;
    e->summary.port = o->port > 0 ? (unsigned int)o->port : 22;
    e->summary.identities = (unsigned int)o->num_identity_files;
    e->summary.certificates = (unsigned int)o->num_certificate_files;
    e->summary.local_forwards = (unsigned int)o->num_local_forwards;
    e->summary.remote_forwards = (unsigned int)o->num_remote_forwards;
    e->summary.has_jump = o->jump_host != NULL;
    e->summary.has_remote_command = o->remote_command != NULL;
    e->summary.request_tty = o->request_tty; e->summary.session_type = o->session_type;
    e->summary.has_raw_fields = 1;
    if (finish_direct_plan(e) != 0) goto done;
    if (ctos_config_check() != 0) goto done;
    ret = 0;
 done:
    if (ret != 0 && e->error.reason == CTOS_CONFIG_OK) ctos_config_reject(CTOS_CONFIG_INPUT, EINVAL);
    e->state = ret == 0 ? CTOS_CONFIG_READY : CTOS_CONFIG_FAILED;
    if (error != NULL) *error = e->error;
    current = NULL; pthread_mutex_unlock(&parser_lock);
    if (ret != 0) errno = e->error.error_number;
    return ret;
}
static int result_live(const struct ctos_ssh_config_eval *e) {
    if (live(e) != 0) return -1;
    if (e->state != CTOS_CONFIG_READY) { errno = EAGAIN; return -1; }
    return 0;
}
int ctos_ssh_config_eval_usage(const struct ctos_ssh_config_eval *e, uint32_t out[3]) {
    if (out == NULL) { errno = EINVAL; return -1; }
    memset(out, 0, sizeof(uint32_t) * 3);
    if (result_live(e) != 0) return -1;
    out[0] = e->io.opens; out[1] = e->io.read_bytes; out[2] = e->total_lines;
    return 0;
}
int ctos_ssh_config_eval_summary(const struct ctos_ssh_config_eval *e, struct ctos_ssh_config_summary *out) {
    if (out == NULL) { errno = EINVAL; return -1; }
    memset(out, 0, sizeof(*out));
    if (result_live(e) != 0) return -1;
    if (e->mode != CTOS_CONFIG_EFFECTIVE) { errno = EINVAL; return -1; }
    *out = e->summary; return 0;
}
int ctos_ssh_config_eval_direct_plan(const struct ctos_ssh_config_eval *e,
    const struct ctos_ssh_config_direct_plan **out) {
    if (out == NULL) { errno = EINVAL; return -1; }
    *out = NULL;
    if (result_live(e) != 0) return -1;
    if (e->direct_plan == NULL || e->mode != CTOS_CONFIG_EFFECTIVE) { errno = EINVAL; return -1; }
    *out = e->direct_plan;
    return 0;
}
int ctos_ssh_config_eval_options(const struct ctos_ssh_config_eval *e, const Options **out) {
    if (out == NULL) { errno = EINVAL; return -1; }
    *out = NULL;
    if (result_live(e) != 0) return -1;
    if (e->mode != CTOS_CONFIG_EFFECTIVE) { errno = EINVAL; return -1; }
    *out = &e->options; return 0;
}
int ctos_ssh_config_eval_catalog(const struct ctos_ssh_config_eval *e,
    const struct ctos_ssh_config_catalog **out) {
    if (out == NULL) { errno = EINVAL; return -1; }
    *out = NULL;
    if (result_live(e) != 0) return -1;
    if (e->mode != CTOS_CONFIG_CATALOG) { errno = EINVAL; return -1; }
    *out = e->catalog; return 0;
}
int ctos_ssh_config_eval_audit(const struct ctos_ssh_config_eval *e, const struct ctos_ssh_config_audit **out) {
    if (out == NULL) { errno = EINVAL; return -1; }
    *out = NULL;
    if (result_live(e) != 0) return -1;
    if (e->mode != CTOS_CONFIG_AUDIT) { errno = EINVAL; return -1; }
    *out = e->audit; return 0;
}
int ctos_ssh_config_eval_resource_audit(const struct ctos_ssh_config_eval *e,
    const struct ctos_ssh_config_resource_audit **out) {
    if (out == NULL) { errno = EINVAL; return -1; }
    *out = NULL;
    if (result_live(e) != 0) return -1;
    if (e->mode != CTOS_CONFIG_RESOURCE_AUDIT) { errno = EINVAL; return -1; }
    *out = e->resource_audit; return 0;
}
void ctos_ssh_config_eval_cancel(struct ctos_ssh_config_eval *e) {
    if (e != NULL) atomic_store_explicit(&e->cancelled, 1, memory_order_release);
}
int ctos_ssh_config_eval_close(struct ctos_ssh_config_eval *e) {
    if (owner(e) != 0) return -1;
    if (e->state == CTOS_CONFIG_CLOSED) return 0;
    if (e->state == CTOS_CONFIG_RUNNING) { errno = EBUSY; return -1; }
    e->state = CTOS_CONFIG_CLOSING;
    if (e->resource_audit != NULL) { explicit_bzero(e->resource_audit, sizeof(*e->resource_audit)); free(e->resource_audit); e->resource_audit = NULL; }
    if (e->audit != NULL) { explicit_bzero(e->audit, sizeof(*e->audit)); free(e->audit); e->audit = NULL; }
    if (e->catalog != NULL) { explicit_bzero(e->catalog, sizeof(*e->catalog)); free(e->catalog); e->catalog = NULL; }
    if (e->options_live) { ctos_config_upstream_free_options(&e->options); e->options_live = 0; }
    if (e->direct_plan != NULL) {
        explicit_bzero(e->direct_plan, sizeof(*e->direct_plan)); free(e->direct_plan); e->direct_plan = NULL;
    }
    (void)ctos_ssh_io_destroy(&e->io);
    if (e->owned_files != NULL) {
        explicit_bzero(e->owned_files, e->owned_file_bytes);
        free(e->owned_files); e->owned_files = NULL; e->owned_file_bytes = 0;
        e->io.files = NULL; e->io.count = 0;
    }
    int failure = 0;
    if (e->owned != NULL) {
        explicit_bzero(e->owned, e->owned_length);
        if (e->owned_locked && munlock(e->owned, e->owned_length) != 0) failure = errno;
        else {
            e->owned_locked = 0;
            if (munmap(e->owned, e->owned_length) != 0) failure = errno;
            else { e->owned = NULL; e->owned_length = 0; }
        }
    }
    explicit_bzero(&e->options, sizeof(e->options));
    explicit_bzero(&e->summary, sizeof(e->summary));
    explicit_bzero(&e->request, sizeof(e->request));
    explicit_bzero(e->alias, sizeof(e->alias)); explicit_bzero(e->user, sizeof(e->user));
    explicit_bzero(e->fallback, sizeof(e->fallback)); explicit_bzero(e->local_user, sizeof(e->local_user));
    explicit_bzero(e->local_host, sizeof(e->local_host)); explicit_bzero(e->tag, sizeof(e->tag));
    explicit_bzero(e->command, sizeof(e->command)); explicit_bzero(e->root, sizeof(e->root));
    explicit_bzero(e->input, sizeof(e->input));
    /* io.error is sticky after failed parsing; retain only actual resources. */
    for (unsigned int i = 0; i < CTOS_SSH_IO_STREAMS; i++) if (e->io.readers[i].live || e->io.readers[i].mapping != NULL) {
        failure = e->io.error == 0 ? EIO : e->io.error; break;
    }
    if (failure != 0) { errno = failure; return -1; }
    if (e->borrow != NULL) {
        if (ctos_ssh_view_borrow_release(e->borrow) != 0) return -1;
        e->borrow = NULL;
    }
    e->state = CTOS_CONFIG_CLOSED; return 0;
}
int ctos_ssh_config_eval_destroy(struct ctos_ssh_config_eval *e) {
    if (owner(e) != 0) return -1;
    if (e->state != CTOS_CONFIG_CLOSED) { errno = EBUSY; return -1; }
    explicit_bzero(e, sizeof(*e)); free(e); return 0;
}

/* Adapted from pinned OpenSSH 10.6p1 misc.c, with only parser-local numeric
 * port conversion and exported names changed. Original misc.c copyright and
 * license are retained in the project OpenSSH source notices. */
int
ctos_config_parse_user_host_port(const char *s, char **userp, char **hostp, int *portp)
{
	char *sdup, *cp, *tmp;
	char *user = NULL, *host = NULL;
	int port = -1, ret = -1;

	if (userp != NULL)
		*userp = NULL;
	if (hostp != NULL)
		*hostp = NULL;
	if (portp != NULL)
		*portp = -1;

	if ((sdup = tmp = strdup(s)) == NULL)
		return -1;
	/* Extract optional username */
	if ((cp = strrchr(tmp, '@')) != NULL) {
		*cp = '\0';
		if (*tmp == '\0')
			goto out;
		if ((user = strdup(tmp)) == NULL)
			goto out;
		tmp = cp + 1;
	}
	/* Extract mandatory hostname */
	if ((cp = hpdelim(&tmp)) == NULL || *cp == '\0')
		goto out;
	host = xstrdup(cleanhostname(cp));
	/* Convert and verify optional port */
	if (tmp != NULL && *tmp != '\0') {
		if ((port = ctos_config_port(tmp)) <= 0)
			goto out;
	}
	/* Success */
	if (userp != NULL) {
		*userp = user;
		user = NULL;
	}
	if (hostp != NULL) {
		*hostp = host;
		host = NULL;
	}
	if (portp != NULL)
		*portp = port;
	ret = 0;
 out:
	free(sdup);
	free(user);
	free(host);
	return ret;
}

/*
 * Converts a two-byte hex string to decimal.
 * Returns the decimal value or -1 for invalid input.
 */
static int
hexchar(const char *s)
{
	unsigned char result[2];
	int i;

	for (i = 0; i < 2; i++) {
		if (s[i] >= '0' && s[i] <= '9')
			result[i] = (unsigned char)(s[i] - '0');
		else if (s[i] >= 'a' && s[i] <= 'f')
			result[i] = (unsigned char)(s[i] - 'a') + 10;
		else if (s[i] >= 'A' && s[i] <= 'F')
			result[i] = (unsigned char)(s[i] - 'A') + 10;
		else
			return -1;
	}
	return (result[0] << 4) | result[1];
}

/*
 * Decode an url-encoded string.
 * Returns a newly allocated string on success or NULL on failure.
 */
static char *
urldecode(const char *src)
{
	char *ret, *dst;
	int ch;
	size_t srclen;

	if ((srclen = strlen(src)) >= SIZE_MAX)
		return NULL;
	ret = xmalloc(srclen + 1);
	for (dst = ret; *src != '\0'; src++) {
		switch (*src) {
		case '+':
			*dst++ = ' ';
			break;
		case '%':
			/* note: don't allow \0 characters */
			if (!isxdigit((unsigned char)src[1]) ||
			    !isxdigit((unsigned char)src[2]) ||
			    (ch = hexchar(src + 1)) == -1 || ch == 0) {
				free(ret);
				return NULL;
			}
			*dst++ = ch;
			src += 2;
			break;
		default:
			*dst++ = *src;
			break;
		}
	}
	*dst = '\0';

	return ret;
}

/*
 * Parse an (scp|ssh|sftp)://[user@]host[:port][/path] URI.
 * See https://tools.ietf.org/html/draft-ietf-secsh-scp-sftp-ssh-uri-04
 * Either user or path may be url-encoded (but not host or port).
 * Caller must free returned user, host and path.
 * Any of the pointer return arguments may be NULL (useful for syntax checking)
 * but the scheme must always be specified.
 * If user was not specified then *userp will be set to NULL.
 * If port was not specified then *portp will be -1.
 * If path was not specified then *pathp will be set to NULL.
 * Returns 0 on success, 1 if non-uri/wrong scheme, -1 on error/invalid uri.
 */
int
ctos_config_parse_uri(const char *scheme, const char *uri, char **userp, char **hostp,
    int *portp, char **pathp)
{
	char *uridup, *cp, *tmp, ch;
	char *user = NULL, *host = NULL, *path = NULL;
	int port = -1, ret = -1;
	size_t len;

	len = strlen(scheme);
	if (strncmp(uri, scheme, len) != 0 || strncmp(uri + len, "://", 3) != 0)
		return 1;
	uri += len + 3;

	if (userp != NULL)
		*userp = NULL;
	if (hostp != NULL)
		*hostp = NULL;
	if (portp != NULL)
		*portp = -1;
	if (pathp != NULL)
		*pathp = NULL;

	uridup = tmp = xstrdup(uri);

	/* Extract optional ssh-info (username + connection params) */
	if ((cp = strchr(tmp, '@')) != NULL) {
		char *delim;

		*cp = '\0';
		/* Extract username and connection params */
		if ((delim = strchr(tmp, ';')) != NULL) {
			/* Just ignore connection params for now */
			*delim = '\0';
		}
		if (*tmp == '\0') {
			/* Empty username */
			goto out;
		}
		if ((user = urldecode(tmp)) == NULL)
			goto out;
		tmp = cp + 1;
	}

	/* Extract mandatory hostname */
	if ((cp = hpdelim2(&tmp, &ch)) == NULL || *cp == '\0')
		goto out;
	host = xstrdup(cleanhostname(cp));
	if (!valid_domain(host, 0, NULL))
		goto out;

	if (tmp != NULL && *tmp != '\0') {
		if (ch == ':') {
			/* Convert and verify port. */
			if ((cp = strchr(tmp, '/')) != NULL)
				*cp = '\0';
			if ((port = ctos_config_port(tmp)) <= 0)
				goto out;
			tmp = cp ? cp + 1 : NULL;
		}
		if (tmp != NULL && *tmp != '\0') {
			/* Extract optional path */
			if ((path = urldecode(tmp)) == NULL)
				goto out;
		}
	}

	/* Success */
	if (userp != NULL) {
		*userp = user;
		user = NULL;
	}
	if (hostp != NULL) {
		*hostp = host;
		host = NULL;
	}
	if (portp != NULL)
		*portp = port;
	if (pathp != NULL) {
		*pathp = path;
		path = NULL;
	}
	ret = 0;
 out:
	free(uridup);
	free(user);
	free(host);
	free(path);
	return ret;
}


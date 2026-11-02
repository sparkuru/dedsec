#define _GNU_SOURCE
#include <stdlib.h>
#include "includes.h"
#include "ssh_host_trust.h"
#include "ssh_key_policy.h"
#include "ssh_file_view.h"
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include "digest.h"
#include "hostfile.h"
#include "sshkey.h"
#if defined(DEBUG_PK) || defined(DEBUG_PKOK)
#error Secret-dumping OpenSSH debug macros must be disabled
#endif
#ifdef malloc
#undef malloc
#endif
#ifdef calloc
#undef calloc
#endif
#ifdef realloc
#undef realloc
#endif

struct trust_row {
    unsigned int line, marker, selector, reason;
    size_t begin, end, algorithm_length, fingerprint_length;
    char algorithm[CTOS_SSH_HOST_TRUST_ALGORITHM_BYTES];
    char fingerprint[CTOS_SSH_HOST_TRUST_FINGERPRINT_BYTES];
};
struct trust_context {
    const unsigned char *body;
    size_t length, offset;
    unsigned long physical_line;
    const char *lookup;
    uint64_t deadline;
    struct trust_row *rows;
    unsigned int count;
    int error, blocked;
};
static void wipe(void *memory, size_t length) {
    volatile unsigned char *p = memory;
    while (length-- != 0) *p++ = 0;
}
static void put32(unsigned char *p, uint32_t n) {
    p[0] = (unsigned char)(n >> 24); p[1] = (unsigned char)(n >> 16);
    p[2] = (unsigned char)(n >> 8); p[3] = (unsigned char)n;
}
static uint32_t u32(const unsigned char *p) {
    return (uint32_t)p[0] << 24 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 8 | p[3];
}
static int host_valid(const unsigned char *host, size_t length) {
    if (host == NULL || length == 0 || length > CTOS_SSH_HOST_TRUST_HOST_BYTES) return 0;
    for (size_t i = 0; i < length; i++) {
        unsigned char ch = host[i];
        if (!((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
            || (ch >= '0' && ch <= '9') || ch == '.' || ch == ':' || ch == '%'
            || ch == '[' || ch == ']' || ch == '-')) return 0;
    }
    return 1;
}
static int physical_span(struct trust_context *ctx, unsigned long line, size_t *begin, size_t *end) {
    if (line != ctx->physical_line + 1U || ctx->offset >= ctx->length) return -1;
    *begin = ctx->offset;
    const unsigned char *newline = memchr(ctx->body + ctx->offset, '\n', ctx->length - ctx->offset);
    *end = newline == NULL ? ctx->length : (size_t)(newline - ctx->body) + 1U;
    ctx->offset = *end; ctx->physical_line = line;
    return 0;
}
static int singleton_literal(const char *selector, const char *lookup) {
    if (strlen(selector) != strlen(lookup) || strpbrk(selector, "*?!,") != NULL) return 0;
    for (size_t i = 0; selector[i] != 0; i++) {
        unsigned char ch = (unsigned char)selector[i];
        if (ch >= 'A' && ch <= 'Z') ch = (unsigned char)(ch - 'A' + 'a');
        if (ch != (unsigned char)lookup[i]) return 0;
    }
    return 1;
}
static int trust_record(struct hostkey_foreach_line *line, void *opaque) {
    struct trust_context *ctx = opaque;
    size_t begin, end;
    if (ctos_ssh_material_deadline(ctx->deadline) != 0) { ctx->error = errno; return 1; }
    if (physical_span(ctx, line->linenum, &begin, &end) != 0
        || line->linenum > CTOS_SSH_MATERIAL_LINES) { ctx->error = EINVAL; return 1; }
    if (line->status == HKF_STATUS_COMMENT) return 0;
    if (line->status == HKF_STATUS_INVALID || line->key == NULL || line->hosts == NULL) {
        ctx->error = EINVAL; return 1;
    }
    if ((line->match & HKF_MATCH_HOST) == 0) return 0;
    if (ctx->count >= CTOS_SSH_HOST_TRUST_RECORDS) { ctx->error = E2BIG; return 1; }
    struct trust_row *row = &ctx->rows[ctx->count];
    row->line = (unsigned int)line->linenum; row->begin = begin; row->end = end;
    if (line->marker == MRK_REVOKE) row->marker = 1;
    else if (line->marker == MRK_CA) row->marker = 2;
    else if (line->marker != MRK_NONE) { ctx->error = EINVAL; return 1; }
    if ((line->match & HKF_MATCH_HOST_HASHED) != 0) row->selector = 1;
    else if (!singleton_literal(line->hosts, ctx->lookup)) row->selector = 2;
    if (row->marker != 0) row->reason = 2;
    else if (row->selector == 2) row->reason = 1;
    else if (ctos_ssh_key_software_identity(line->key) != 0) row->reason = 3;
    const char *algorithm = sshkey_ssh_name(line->key);
    char *fingerprint = sshkey_fingerprint(line->key, SSH_DIGEST_SHA256, SSH_FP_DEFAULT);
    if (algorithm == NULL || fingerprint == NULL) {
        free(fingerprint); ctx->error = EIO; return 1;
    }
    row->algorithm_length = strlen(algorithm); row->fingerprint_length = strlen(fingerprint);
    if (row->algorithm_length == 0 || row->algorithm_length > sizeof(row->algorithm)
        || row->fingerprint_length == 0 || row->fingerprint_length > sizeof(row->fingerprint)) {
        wipe(fingerprint, row->fingerprint_length); free(fingerprint); ctx->error = EOVERFLOW; return 1;
    }
    memcpy(row->algorithm, algorithm, row->algorithm_length);
    memcpy(row->fingerprint, fingerprint, row->fingerprint_length);
    wipe(fingerprint, row->fingerprint_length); free(fingerprint);
    if (row->reason != 0) ctx->blocked = 1;
    ctx->count++;
    return 0;
}
int ctos_ssh_host_trust_inspect(const unsigned char *body, size_t length,
    const unsigned char *host, size_t host_length, unsigned int port, int prepare,
    uint64_t deadline, unsigned char **output, size_t *output_length) {
    struct trust_context ctx = {.body = body, .length = length, .deadline = deadline};
    unsigned char material[CTOS_SSH_MATERIAL_WIRE_BYTES] = {0};
    char lookup[CTOS_SSH_HOST_TRUST_HOST_BYTES + sizeof("[]:65535")];
    unsigned char *wire = NULL;
    size_t wire_length = 0;
    FILE *stream = NULL;
    int error = EINVAL, result = -1;
    if (output != NULL) *output = NULL;
    if (output_length != NULL) *output_length = 0;
    if (output == NULL || output_length == NULL || (body == NULL && length != 0)
        || !host_valid(host, host_length) || port == 0 || port > 65535U
        || (prepare != 0 && prepare != 1)) goto done;
    if (length > CTOS_SSH_MATERIAL_BYTES) { error = E2BIG; goto done; }
    if (ctos_ssh_material_deadline(deadline) != 0) { error = errno; goto done; }
    /* Full material validation runs before query matching, so malformed hash
     * tokens never reach upstream's token-containing matching diagnostic. */
    if (ctos_ssh_material_inspect(CTOS_SSH_KNOWN_HOSTS, body, length, NULL, 0,
        deadline, material) != 0) { error = errno; goto done; }
    if (u32(material + 8) != CTOS_MATERIAL_PARSED) goto done;
    int lookup_length = port == 22U ? snprintf(lookup, sizeof(lookup), "%.*s", (int)host_length, (const char *)host)
        : snprintf(lookup, sizeof(lookup), "[%.*s]:%u", (int)host_length, (const char *)host, port);
    if (lookup_length <= 0 || (size_t)lookup_length >= sizeof(lookup)) goto done;
    for (int i = 0; i < lookup_length; i++)
        if (lookup[i] >= 'A' && lookup[i] <= 'Z') lookup[i] = (char)(lookup[i] - 'A' + 'a');
    ctx.lookup = lookup;
    ctx.rows = calloc(CTOS_SSH_HOST_TRUST_RECORDS, sizeof(*ctx.rows));
    if (ctx.rows == NULL) { error = ENOMEM; goto done; }
    if (length != 0) {
        stream = fmemopen((void *)body, length, "r");
        if (stream == NULL) { error = errno; goto done; }
        int parsed = hostkeys_foreach_file("ctos-host-trust", stream, trust_record, &ctx,
            lookup, NULL, HKF_WANT_PARSE_KEY, 0);
        int io_error = ferror(stream);
        if (fclose(stream) != 0) io_error = 1;
        stream = NULL;
        if (ctx.error != 0) { error = ctx.error; goto done; }
        if (io_error) { error = EIO; goto done; }
        if (parsed != 0) { error = parsed < 0 ? EIO : EINVAL; goto done; }
        if (ctx.offset != length) goto done;
    }
    int removable = ctx.count != 0 && !ctx.blocked;
    int replacement = prepare && removable;
    size_t replacement_length = replacement ? length : 0;
    if (replacement) for (unsigned int i = 0; i < ctx.count; i++)
        replacement_length -= ctx.rows[i].end - ctx.rows[i].begin;
    wire_length = CTOS_SSH_HOST_TRUST_HEADER_BYTES + ctx.count * CTOS_SSH_HOST_TRUST_RECORD_BYTES
        + replacement_length;
    wire = calloc(wire_length, 1U);
    if (wire == NULL) { error = ENOMEM; goto done; }
    put32(wire, 0x43534854U); put32(wire + 4, 1); put32(wire + 8, (unsigned int)prepare);
    put32(wire + 12, (removable ? 1U : 0U) | (replacement ? 2U : 0U));
    put32(wire + 16, ctx.count); put32(wire + 20, (uint32_t)replacement_length);
    if (ssh_digest_memory(SSH_DIGEST_SHA256, length == 0 ? (const unsigned char *)"" : body,
        length, wire + 24, 32) != 0) { error = EIO; goto done; }
    for (unsigned int i = 0; i < ctx.count; i++) {
        struct trust_row *row = &ctx.rows[i];
        unsigned char *p = wire + CTOS_SSH_HOST_TRUST_HEADER_BYTES + i * CTOS_SSH_HOST_TRUST_RECORD_BYTES;
        put32(p, row->line); put32(p + 4, row->marker); put32(p + 8, row->selector);
        put32(p + 12, row->reason); put32(p + 16, (uint32_t)row->algorithm_length);
        put32(p + 20, (uint32_t)row->fingerprint_length);
        memcpy(p + 24, row->algorithm, row->algorithm_length);
        memcpy(p + 24 + CTOS_SSH_HOST_TRUST_ALGORITHM_BYTES, row->fingerprint, row->fingerprint_length);
    }
    if (replacement) {
        unsigned char *p = wire + CTOS_SSH_HOST_TRUST_HEADER_BYTES + ctx.count * CTOS_SSH_HOST_TRUST_RECORD_BYTES;
        size_t offset = 0, copied = 0;
        for (unsigned int i = 0; i < ctx.count; i++) {
            size_t kept = ctx.rows[i].begin - offset;
            memcpy(p + copied, body + offset, kept); copied += kept; offset = ctx.rows[i].end;
        }
        memcpy(p + copied, body + offset, length - offset); copied += length - offset;
        if (copied != replacement_length || ssh_digest_memory(SSH_DIGEST_SHA256, p,
            replacement_length, wire + 56, 32) != 0) { error = EIO; goto done; }
    }
    if (ctos_ssh_material_deadline(deadline) != 0) { error = errno; goto done; }
    *output = wire; *output_length = wire_length; wire = NULL; result = 0;
done:
    if (stream != NULL) fclose(stream);
    if (ctx.rows != NULL) { wipe(ctx.rows, CTOS_SSH_HOST_TRUST_RECORDS * sizeof(*ctx.rows)); free(ctx.rows); }
    if (wire != NULL) { wipe(wire, wire_length); free(wire); }
    wipe(material, sizeof(material)); wipe(lookup, sizeof(lookup));
    if (result != 0) errno = error;
    return result;
}

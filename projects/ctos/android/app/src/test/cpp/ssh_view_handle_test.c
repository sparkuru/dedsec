#define _GNU_SOURCE

#include "ssh_view_handle.h"

#include <assert.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

struct wire {
    unsigned char bytes[4096];
    size_t length;
};

static void put(struct wire *wire, const void *bytes, size_t length) {
    assert(wire != NULL && length <= sizeof(wire->bytes) - wire->length);
    memcpy(wire->bytes + wire->length, bytes, length);
    wire->length += length;
}

static void u32(struct wire *wire, uint32_t value) {
    unsigned char bytes[] = {
        (unsigned char)(value >> 24), (unsigned char)(value >> 16),
        (unsigned char)(value >> 8), (unsigned char)value
    };
    put(wire, bytes, sizeof(bytes));
}

static void u64(struct wire *wire, uint64_t value) {
    u32(wire, (uint32_t)(value >> 32));
    u32(wire, (uint32_t)value);
}

static void text(struct wire *wire, const char *value) {
    size_t length = strlen(value);
    assert(length <= UINT32_MAX);
    u32(wire, (uint32_t)length);
    put(wire, value, length);
}

static void object(struct wire *wire, unsigned int type, const char *id,
    const char *path, const char *identity, const char *reference,
    const unsigned char *body, size_t body_length) {
    unsigned char kind = (unsigned char)type;

    put(wire, &kind, sizeof(kind));
    text(wire, id);
    text(wire, path);
    text(wire, identity);
    u32(wire, (uint32_t)body_length);
    if (reference == NULL) u32(wire, 0);
    else { u32(wire, 1); text(wire, reference); }
    u32(wire, (uint32_t)body_length);
    if (body_length != 0) put(wire, body, body_length);
}

static struct wire make_wire(uint64_t deadline) {
    struct wire wire = {0};
    unsigned char uuid[16] = {1};
    unsigned char binding[32] = {2};
    static const unsigned char hosts[] = "known host";

    u32(&wire, 0x43535056U);
    u32(&wire, 1);
    put(&wire, uuid, sizeof(uuid));
    u64(&wire, 73);
    u64(&wire, 7);
    u64(&wire, deadline);
    text(&wire, "handle-request");
    text(&wire, "handle-connection");
    text(&wire, "ssh/config");
    put(&wire, binding, sizeof(binding));
    u32(&wire, 2);
    object(&wire, CTOS_SSH_CONFIG, "root", "ssh/config", "config",
        "ssh/known_hosts", NULL, 0);
    object(&wire, CTOS_SSH_KNOWN_HOSTS, "trust", "ssh/known_hosts", "trust",
        NULL, hosts, sizeof(hosts) - 1U);
    return wire;
}

static uint64_t deadline_after(unsigned int milliseconds) {
    struct timespec now;

    assert(clock_gettime(CLOCK_BOOTTIME, &now) == 0);
    return (uint64_t)now.tv_sec * 1000U
        + (uint64_t)now.tv_nsec / 1000000U + milliseconds;
}

int main(void) {
    struct wire wire = make_wire(deadline_after(15000));
    struct ctos_ssh_view_summary summary;
    struct ctos_ssh_view_borrow *borrow = NULL;
    struct ctos_ssh_view_borrow *second = NULL;
    ctos_ssh_view_token token = 0;
    ctos_ssh_view_token replacement = 0;

    assert(ctos_ssh_view_handle_create(wire.bytes, wire.length, &token) == 0);
    assert(token > 0);
    assert(ctos_ssh_view_handle_summary(token, &summary) == 0);
    assert(summary.count == 2 && summary.public_bytes == 10
        && summary.generation == 73 && summary.epoch == 7
        && summary.backing_bytes == wire.length && summary.close_on_exec != 0);
    assert(ctos_ssh_view_handle_borrow(token, &borrow) == 0);
    assert(ctos_ssh_view_borrow_view(borrow) != NULL);
    assert(ctos_ssh_view_borrow_live(borrow) == 0);
    assert(ctos_ssh_view_handle_destroy(token) == 0);
    errno = 0;
    assert(ctos_ssh_view_borrow_live(borrow) != 0 && errno == EACCES);
    errno = 0;
    assert(ctos_ssh_view_handle_borrow(token, &second) != 0 && errno == EACCES);
    assert(ctos_ssh_view_borrow_release(borrow) == 0);
    assert(ctos_ssh_view_handle_destroy(token) == 0);

    assert(ctos_ssh_view_handle_create(wire.bytes, wire.length, &replacement) == 0);
    assert(replacement != token);
    assert(ctos_ssh_view_handle_destroy(token) == 0);
    assert(ctos_ssh_view_handle_summary(replacement, &summary) == 0);
    assert(ctos_ssh_view_handle_destroy(replacement) == 0);
    assert(ctos_ssh_view_handle_summary(replacement, &summary) != 0);
    printf("SSH_VIEW_HANDLE_HOST gate=pass borrow=pass revoke=pass stale=pass\n");
    return 0;
}

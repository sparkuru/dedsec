#ifndef CTOS_SSH_VIEW_HANDLE_H
#define CTOS_SSH_VIEW_HANDLE_H

#include <stddef.h>
#include <stdint.h>

#include "ssh_file_view.h"

/* Java receives this as an opaque positive long. It is never a C pointer. */
typedef uint64_t ctos_ssh_view_token;

struct ctos_ssh_view_borrow;
struct ctos_ssh_view_borrow_claim;

struct ctos_ssh_view_summary {
    unsigned int count;
    unsigned int private_count;
    unsigned int public_bytes;
    uint64_t generation;
    uint64_t epoch;
    size_t backing_bytes;
    unsigned int mode;
    int close_on_exec;
};

/* The registry owns the view after a successful create. */
int ctos_ssh_view_handle_create(const unsigned char *, size_t,
    ctos_ssh_view_token *);
int ctos_ssh_view_handle_summary(ctos_ssh_view_token,
    struct ctos_ssh_view_summary *);

/* Idempotent revoke. A live native borrow delays final view destruction. */
int ctos_ssh_view_handle_destroy(ctos_ssh_view_token);

/* A token borrow is native-owned until release. Revoke prevents new borrows. */
int ctos_ssh_view_handle_borrow(ctos_ssh_view_token,
    struct ctos_ssh_view_borrow **);
const struct ctos_ssh_view *ctos_ssh_view_borrow_view(
    const struct ctos_ssh_view_borrow *);
int ctos_ssh_view_borrow_live(const struct ctos_ssh_view_borrow *);
/* Established transport revocation only; grants no file lookup/read authority. */
int ctos_ssh_view_borrow_bound(const struct ctos_ssh_view_borrow *);
int ctos_ssh_view_borrow_release(struct ctos_ssh_view_borrow *);

/* A consuming native owner reserves a borrow before dereferencing it. The
 * opaque token remains private to that owner and blocks other consumers and
 * generic release. Abort is only for the same owner during an unpublished
 * failed construction; leaking, reusing or transferring that token to another
 * thread violates its contract. Abort returns the borrow and never fails.
 * Formal close must use release, which checks owner/thread/PID/UID and consumes
 * the token and borrow on success, retaining both on failure. */
int ctos_ssh_view_borrow_claim(struct ctos_ssh_view_borrow *, const void *,
    struct ctos_ssh_view_borrow_claim **);
void ctos_ssh_view_borrow_claim_abort(struct ctos_ssh_view_borrow_claim *);
int ctos_ssh_view_borrow_claim_release(struct ctos_ssh_view_borrow_claim *, const void *);
/* Promotion only: atomically require a live borrow and release its claim in
 * the same registry critical section as managed-view revocation. Failure
 * preserves the original claim for ordinary cleanup, even after revocation. */
int ctos_ssh_view_borrow_claim_release_live(struct ctos_ssh_view_borrow_claim *, const void *);

#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
int ctos_ssh_view_borrow_test_fail_next_release(struct ctos_ssh_view_borrow_claim *, const void *);
#endif

/* Host/native tests may wrap an explicitly caller-owned view. This borrow
 * never destroys that view; it only gives the native client one opaque release
 * point matching the production token path. */
int ctos_ssh_view_borrow_owned_view(struct ctos_ssh_view *,
    struct ctos_ssh_view_borrow **);

#endif

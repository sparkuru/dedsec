#define _GNU_SOURCE

#include "ssh_view_handle.h"

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

/* Standalone file-view consumers do not link the SSH role runtime.  In the
 * complete SSH library these hooks drain bounded credential effects first. */
extern void ctos_ssh_effect_invalidate_begin(void) __attribute__((weak));
extern void ctos_ssh_effect_invalidate_end(void) __attribute__((weak));

struct ctos_ssh_view_handle {
    struct ctos_ssh_view *view;
    ctos_ssh_view_token token;
    unsigned int borrows;
    int revoked;
};

struct ctos_ssh_view_borrow {
    struct ctos_ssh_view_borrow *next;
    struct ctos_ssh_view_handle *owner;
    const struct ctos_ssh_view *owned_view;
    _Atomic(struct ctos_ssh_view_borrow_claim *) claim;
    int released;
};

struct ctos_ssh_view_borrow_claim {
    struct ctos_ssh_view_borrow *borrow;
    const void *owner;
    pthread_t thread;
    pid_t pid;
    uid_t uid;
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
    int fail_next_release;
#endif
};

static pthread_mutex_t handles_lock = PTHREAD_MUTEX_INITIALIZER;
static struct ctos_ssh_view_handle *current;
static struct ctos_ssh_view_borrow *borrows;
static ctos_ssh_view_token next_token;
static int poisoned;

static struct ctos_ssh_view_handle *lookup_locked(ctos_ssh_view_token token) {
    if (current != NULL && token != 0 && current->token == token) return current;
    return NULL;
}

static int lock_handles(void) {
    if (pthread_mutex_lock(&handles_lock) != 0) {
        errno = EBUSY;
        return -1;
    }
    return 0;
}

static struct ctos_ssh_view_borrow *borrow_lookup_locked(const struct ctos_ssh_view_borrow *borrow) {
    for (struct ctos_ssh_view_borrow *item = borrows; item != NULL; item = item->next)
        if (item == borrow) return item;
    return NULL;
}

static void borrow_remove_locked(struct ctos_ssh_view_borrow *borrow) {
    struct ctos_ssh_view_borrow **cursor = &borrows;
    while (*cursor != NULL && *cursor != borrow) cursor = &(*cursor)->next;
    if (*cursor == borrow) *cursor = borrow->next;
}

static int borrow_live_locked(const struct ctos_ssh_view_borrow *borrow) {
    struct ctos_ssh_view_handle *handle;
    if (borrow == NULL || borrow->released) { errno = EACCES; return -1; }
    handle = borrow->owner;
    if (handle == NULL) return ctos_ssh_view_live(borrow->owned_view);
    if (handle != current || handle->view == NULL || handle->revoked) {
        errno = EACCES; return -1;
    }
    return ctos_ssh_view_live(handle->view);
}

static int claim_owner_matches(const struct ctos_ssh_view_borrow_claim *claim, const void *owner) {
    if (claim->owner != owner) {
        errno = EBUSY; return -1;
    }
    if (claim->pid != getpid() || claim->uid != getuid()
        || !pthread_equal(claim->thread, pthread_self())) {
        errno = EPERM; return -1;
    }
    return 0;
}

static int destroy_current_locked(struct ctos_ssh_view_handle *handle) {
    int result;

    if (handle == NULL || handle != current || handle->view == NULL
        || !handle->revoked || handle->borrows != 0U) {
        errno = EBUSY;
        return -1;
    }
    result = ctos_ssh_view_destroy(handle->view);
    if (result != 0) {
        poisoned = 1;
        return -1;
    }
    free(handle->view);
    handle->view = NULL;
    current = NULL;
    free(handle);
    return 0;
}

int ctos_ssh_view_handle_create(const unsigned char *wire, size_t length,
    ctos_ssh_view_token *token_out) {
    struct ctos_ssh_view_handle *handle = NULL;
    struct ctos_ssh_view *view = NULL;
    int failure;

    if (token_out == NULL || wire == NULL || length == 0
        || length > CTOS_SSH_VIEW_WIRE_BYTES) {
        errno = EINVAL;
        return -1;
    }
    *token_out = 0;
    if (lock_handles() != 0) return -1;
    if (poisoned || current != NULL || next_token == UINT64_MAX) {
        pthread_mutex_unlock(&handles_lock);
        errno = poisoned ? EIO : EBUSY;
        return -1;
    }
    handle = calloc(1, sizeof(*handle));
    view = calloc(1, sizeof(*view));
    if (view != NULL) view->fd = -1;
    if (handle == NULL || view == NULL) {
        failure = ENOMEM;
        goto failed;
    }
    if (ctos_ssh_view_allocate(view, length) != 0) {
        failure = errno == 0 ? EIO : errno;
        goto failed;
    }
    memcpy(view->mapping, wire, length);
    if (ctos_ssh_view_validate(view) != 0) {
        failure = errno == 0 ? EINVAL : errno;
        goto failed;
    }
    handle->view = view;
    handle->token = ++next_token;
    current = handle;
    *token_out = handle->token;
    pthread_mutex_unlock(&handles_lock);
    return 0;

failed:
    if (view != NULL) {
        (void)ctos_ssh_view_destroy(view);
        free(view);
    }
    free(handle);
    pthread_mutex_unlock(&handles_lock);
    errno = failure;
    return -1;
}

int ctos_ssh_view_handle_summary(ctos_ssh_view_token token,
    struct ctos_ssh_view_summary *summary) {
    struct ctos_ssh_view_handle *handle;
    struct stat shape;
    int flags;

    if (summary == NULL || lock_handles() != 0) {
        if (summary == NULL) errno = EINVAL;
        return -1;
    }
    handle = lookup_locked(token);
    if (handle == NULL || handle->revoked || handle->view == NULL
        || ctos_ssh_view_live(handle->view) != 0) {
        pthread_mutex_unlock(&handles_lock);
        errno = EACCES;
        return -1;
    }
    flags = fcntl(handle->view->fd, F_GETFD);
    if (flags < 0 || fstat(handle->view->fd, &shape) != 0
        || !S_ISREG(shape.st_mode)) {
        pthread_mutex_unlock(&handles_lock);
        errno = EIO;
        return -1;
    }
    *summary = (struct ctos_ssh_view_summary){
        .count = handle->view->count,
        .private_count = handle->view->private_count,
        .public_bytes = handle->view->public_bytes,
        .generation = handle->view->generation,
        .epoch = handle->view->epoch,
        .backing_bytes = shape.st_size < 0 ? 0U : (size_t)shape.st_size,
        .mode = (unsigned int)(shape.st_mode & 0777),
        .close_on_exec = (flags & FD_CLOEXEC) != 0
    };
    pthread_mutex_unlock(&handles_lock);
    return 0;
}

int ctos_ssh_view_handle_destroy(ctos_ssh_view_token token) {
    struct ctos_ssh_view_handle *handle;
    int result = 0;

    if (lock_handles() != 0) return -1;
    handle = lookup_locked(token);
    if (handle == NULL) {
        /* Stale tokens cannot affect a newer lease. Destruction is idempotent. */
        pthread_mutex_unlock(&handles_lock);
        return poisoned ? -1 : 0;
    }
    if (ctos_ssh_effect_invalidate_begin != NULL) ctos_ssh_effect_invalidate_begin();
    handle->revoked = 1;
    if (handle->borrows == 0U) result = destroy_current_locked(handle);
    if (ctos_ssh_effect_invalidate_end != NULL) ctos_ssh_effect_invalidate_end();
    if (result != 0 && errno == 0) errno = EIO;
    pthread_mutex_unlock(&handles_lock);
    return result;
}

int ctos_ssh_view_handle_borrow(ctos_ssh_view_token token,
    struct ctos_ssh_view_borrow **borrow_out) {
    struct ctos_ssh_view_handle *handle;
    struct ctos_ssh_view_borrow *borrow;

    if (borrow_out == NULL || token == 0) {
        errno = EINVAL;
        return -1;
    }
    *borrow_out = NULL;
    if (lock_handles() != 0) return -1;
    handle = lookup_locked(token);
    if (handle == NULL || handle->revoked || handle->view == NULL
        || ctos_ssh_view_live(handle->view) != 0) {
        pthread_mutex_unlock(&handles_lock);
        errno = EACCES;
        return -1;
    }
    borrow = calloc(1, sizeof(*borrow));
    if (borrow == NULL) {
        pthread_mutex_unlock(&handles_lock);
        errno = ENOMEM;
        return -1;
    }
    borrow->owner = handle;
    atomic_init(&borrow->claim, NULL);
    borrow->next = borrows;
    borrows = borrow;
    handle->borrows++;
    *borrow_out = borrow;
    pthread_mutex_unlock(&handles_lock);
    return 0;
}

int ctos_ssh_view_borrow_owned_view(struct ctos_ssh_view *view,
    struct ctos_ssh_view_borrow **borrow_out) {
    struct ctos_ssh_view_borrow *borrow;

    if (view == NULL || borrow_out == NULL || ctos_ssh_view_live(view) != 0) {
        errno = EACCES;
        return -1;
    }
    *borrow_out = NULL;
    borrow = calloc(1, sizeof(*borrow));
    if (borrow == NULL) {
        errno = ENOMEM;
        return -1;
    }
    if (lock_handles() != 0) { free(borrow); return -1; }
    borrow->owned_view = view;
    atomic_init(&borrow->claim, NULL);
    borrow->next = borrows;
    borrows = borrow;
    *borrow_out = borrow;
    pthread_mutex_unlock(&handles_lock);
    return 0;
}

const struct ctos_ssh_view *ctos_ssh_view_borrow_view(
    const struct ctos_ssh_view_borrow *borrow) {
    const struct ctos_ssh_view *view = NULL;
    if (lock_handles() != 0) return NULL;
    borrow = borrow_lookup_locked(borrow);
    if (borrow == NULL || borrow->released) errno = EINVAL;
    else view = borrow->owner != NULL ? borrow->owner->view : borrow->owned_view;
    pthread_mutex_unlock(&handles_lock);
    return view;
}

int ctos_ssh_view_borrow_live(const struct ctos_ssh_view_borrow *borrow) {
    int result;
    if (lock_handles() != 0) return -1;
    result = borrow_live_locked(borrow_lookup_locked(borrow));
    pthread_mutex_unlock(&handles_lock);
    return result;
}

int ctos_ssh_view_borrow_claim(struct ctos_ssh_view_borrow *borrow, const void *owner,
    struct ctos_ssh_view_borrow_claim **out) {
    struct ctos_ssh_view_borrow_claim *claim;
    int error;
    if (out != NULL) *out = NULL;
    if (borrow == NULL || owner == NULL || out == NULL) { errno = EINVAL; return -1; }
    claim = calloc(1, sizeof(*claim));
    if (claim == NULL) { errno = ENOMEM; return -1; }
    if (lock_handles() != 0) { free(claim); return -1; }
    borrow = borrow_lookup_locked(borrow);
    if (borrow == NULL) { error = EACCES; goto failed; }
    if (atomic_load_explicit(&borrow->claim, memory_order_acquire) != NULL) {
        error = EBUSY; goto failed;
    }
    if (borrow_live_locked(borrow) != 0) { error = errno; goto failed; }
    claim->borrow = borrow;
    claim->owner = owner;
    claim->thread = pthread_self();
    claim->pid = getpid();
    claim->uid = getuid();
    atomic_store_explicit(&borrow->claim, claim, memory_order_release);
    *out = claim;
    pthread_mutex_unlock(&handles_lock);
    return 0;
failed:
    pthread_mutex_unlock(&handles_lock);
    free(claim);
    errno = error;
    return -1;
}

/* Only an unpublished constructing owner may roll back its private token.
 * The atomic transition is its last borrow access: an external release may
 * destroy the object as soon as it observes the NULL owner. */
void ctos_ssh_view_borrow_claim_abort(struct ctos_ssh_view_borrow_claim *claim) {
    if (claim == NULL) return;
    atomic_store_explicit(&claim->borrow->claim, NULL, memory_order_release);
    free(claim);
}

static int borrow_release_locked(struct ctos_ssh_view_borrow *borrow) {
    struct ctos_ssh_view_handle *handle;
    int result = 0;
    if (borrow->owner == NULL) {
        borrow_remove_locked(borrow);
        free(borrow);
        return 0;
    }
    handle = borrow->owner;
    if (handle != current || handle->view == NULL) {
        errno = EACCES;
        return -1;
    }
    if (!borrow->released) {
        if (handle->borrows == 0U) {
            errno = EINVAL;
            return -1;
        }
        handle->borrows--;
        borrow->released = 1;
    }
    if (handle->revoked && handle->borrows == 0U) {
        result = destroy_current_locked(handle);
        if (result == 0) borrow->owner = NULL;
    } else if (result == 0) {
        borrow->owner = NULL;
    }
    if (result == 0 && borrow->released && borrow->owner == NULL) {
        borrow_remove_locked(borrow);
        free(borrow);
        return 0;
    }
    return result;
}

int ctos_ssh_view_borrow_release(struct ctos_ssh_view_borrow *borrow) {
    int result, error;
    if (borrow == NULL) { errno = EINVAL; return -1; }
    if (lock_handles() != 0) return -1;
    borrow = borrow_lookup_locked(borrow);
    if (borrow == NULL) { pthread_mutex_unlock(&handles_lock); errno = EINVAL; return -1; }
    if (atomic_load_explicit(&borrow->claim, memory_order_acquire) != NULL) {
        pthread_mutex_unlock(&handles_lock); errno = EBUSY; return -1;
    }
    result = borrow_release_locked(borrow);
    error = errno;
    pthread_mutex_unlock(&handles_lock);
    errno = error;
    return result;
}

static int claim_release(struct ctos_ssh_view_borrow_claim *claim, const void *owner,
    int require_live) {
    struct ctos_ssh_view_borrow *borrow;
    int result, error;
    if (claim == NULL || owner == NULL) { errno = EINVAL; return -1; }
    if (claim_owner_matches(claim, owner) != 0) return -1;
    if (lock_handles() != 0) return -1;
    borrow = borrow_lookup_locked(claim->borrow);
    if (borrow == NULL || atomic_load_explicit(&borrow->claim, memory_order_acquire) != claim) {
        pthread_mutex_unlock(&handles_lock); errno = EACCES; return -1;
    }
    /* Handle revocation takes this same lock. Do not let it slip between a
     * promotion's final liveness decision and releasing its only borrow. */
    if (require_live && borrow_live_locked(borrow) != 0) {
        error = errno;
        pthread_mutex_unlock(&handles_lock);
        errno = error;
        return -1;
    }
#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
    if (claim->fail_next_release) {
        claim->fail_next_release = 0;
        pthread_mutex_unlock(&handles_lock); errno = EIO; return -1;
    }
#endif
    result = borrow_release_locked(borrow);
    error = errno;
    pthread_mutex_unlock(&handles_lock);
    if (result == 0) free(claim);
    errno = error;
    return result;
}

int ctos_ssh_view_borrow_claim_release(struct ctos_ssh_view_borrow_claim *claim, const void *owner) {
    return claim_release(claim, owner, 0);
}

int ctos_ssh_view_borrow_claim_release_live(struct ctos_ssh_view_borrow_claim *claim, const void *owner) {
    return claim_release(claim, owner, 1);
}

#ifdef CTOS_SSH_NATIVE_TEST_HOOKS
int ctos_ssh_view_borrow_test_fail_next_release(struct ctos_ssh_view_borrow_claim *claim,
    const void *owner) {
    struct ctos_ssh_view_borrow *borrow;
    if (claim == NULL || owner == NULL) { errno = EINVAL; return -1; }
    if (claim_owner_matches(claim, owner) != 0) return -1;
    if (lock_handles() != 0) return -1;
    borrow = borrow_lookup_locked(claim->borrow);
    if (borrow == NULL || atomic_load_explicit(&borrow->claim, memory_order_acquire) != claim) {
        pthread_mutex_unlock(&handles_lock); errno = EACCES; return -1;
    }
    claim->fail_next_release = 1;
    pthread_mutex_unlock(&handles_lock);
    return 0;
}
#endif

int ctos_ssh_view_borrow_bound(const struct ctos_ssh_view_borrow *borrow) {
    struct ctos_ssh_view_handle *handle;
    if (lock_handles() != 0) return -1;
    borrow = borrow_lookup_locked(borrow);
    if (borrow == NULL || borrow->released) {
        pthread_mutex_unlock(&handles_lock); errno = EACCES; return -1;
    }
    if (borrow->owner == NULL) {
        if (borrow->owned_view == NULL || !borrow->owned_view->valid) {
            pthread_mutex_unlock(&handles_lock); errno = EACCES; return -1;
        }
        pthread_mutex_unlock(&handles_lock);
        return 0;
    }
    handle = borrow->owner;
    if (handle != current || handle->view == NULL || !handle->view->valid || handle->revoked) {
        pthread_mutex_unlock(&handles_lock); errno = EACCES; return -1;
    }
    pthread_mutex_unlock(&handles_lock);
    return 0;
}

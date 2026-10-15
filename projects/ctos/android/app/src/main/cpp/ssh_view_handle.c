#define _GNU_SOURCE

#include "ssh_view_handle.h"

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

struct ctos_ssh_view_handle {
    struct ctos_ssh_view *view;
    ctos_ssh_view_token token;
    unsigned int borrows;
    int revoked;
};

struct ctos_ssh_view_borrow {
    struct ctos_ssh_view_handle *owner;
    const struct ctos_ssh_view *owned_view;
    int released;
};

static pthread_mutex_t handles_lock = PTHREAD_MUTEX_INITIALIZER;
static struct ctos_ssh_view_handle *current;
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
    handle->revoked = 1;
    if (handle->borrows == 0U) result = destroy_current_locked(handle);
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
    borrow->owned_view = view;
    *borrow_out = borrow;
    return 0;
}

const struct ctos_ssh_view *ctos_ssh_view_borrow_view(
    const struct ctos_ssh_view_borrow *borrow) {
    if (borrow == NULL || borrow->released) {
        errno = EINVAL;
        return NULL;
    }
    if (borrow->owner != NULL) return borrow->owner->view;
    return borrow->owned_view;
}

int ctos_ssh_view_borrow_live(const struct ctos_ssh_view_borrow *borrow) {
    struct ctos_ssh_view_handle *handle;
    int result;

    if (borrow == NULL || borrow->released) {
        errno = EACCES;
        return -1;
    }
    if (borrow->owner == NULL)
        return ctos_ssh_view_live(borrow->owned_view);
    if (lock_handles() != 0) return -1;
    handle = borrow->owner;
    if (handle != current || handle->view == NULL || handle->revoked) {
        pthread_mutex_unlock(&handles_lock);
        errno = EACCES;
        return -1;
    }
    result = ctos_ssh_view_live(handle->view);
    pthread_mutex_unlock(&handles_lock);
    return result;
}

int ctos_ssh_view_borrow_release(struct ctos_ssh_view_borrow *borrow) {
    struct ctos_ssh_view_handle *handle;
    int result = 0;

    if (borrow == NULL) {
        errno = EINVAL;
        return -1;
    }
    if (borrow->owner == NULL) {
        if (!borrow->released) borrow->released = 1;
        free(borrow);
        return 0;
    }
    if (lock_handles() != 0) return -1;
    handle = borrow->owner;
    if (handle != current || handle->view == NULL) {
        pthread_mutex_unlock(&handles_lock);
        errno = EACCES;
        return -1;
    }
    if (!borrow->released) {
        if (handle->borrows == 0U) {
            pthread_mutex_unlock(&handles_lock);
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
        pthread_mutex_unlock(&handles_lock);
        free(borrow);
        return 0;
    }
    pthread_mutex_unlock(&handles_lock);
    return result;
}

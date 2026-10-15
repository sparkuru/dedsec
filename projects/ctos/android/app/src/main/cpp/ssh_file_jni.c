#include <jni.h>
#include <errno.h>
#include <limits.h>
#include <pthread.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <fcntl.h>
#include "ssh_file_view.h"

static pthread_mutex_t ownership = PTHREAD_MUTEX_INITIALIZER;
static struct ctos_ssh_view *current;
static struct ctos_ssh_view *retained;
static uint64_t next_token, current_token;
static int poisoned;

static void fail(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass error = (*env)->FindClass(env, "java/io/IOException");
    if (error != NULL) (*env)->ThrowNew(env, error, "Protected SSH file view unavailable");
}

static int release(struct ctos_ssh_view *view) {
    int result = ctos_ssh_view_destroy(view);
    if (result != 0) poisoned = 1;
    /* Retain an unconfirmed mapping for process-lifetime ownership. No new loan. */
    if (view->mapping == NULL) free(view);
    else retained = view;
    return result;
}

JNIEXPORT jlong JNICALL Java_im_majo_ctos_SshPublicFileLease_00024Native_create(
    JNIEnv *env, jclass type, jbyteArray wire) {
    (void)type;
    if (wire == NULL) { fail(env); return 0; }
    jsize length = (*env)->GetArrayLength(env, wire);
    if (length <= 0 || (uint32_t)length > CTOS_SSH_VIEW_WIRE_BYTES) { fail(env); return 0; }
    pthread_mutex_lock(&ownership);
    if (poisoned || retained != NULL || current != NULL || next_token == INT64_MAX) {
        pthread_mutex_unlock(&ownership); fail(env); return 0;
    }
    struct ctos_ssh_view *view = calloc(1, sizeof(*view));
    if (view == NULL) { pthread_mutex_unlock(&ownership); fail(env); return 0; }
    view->fd = -1;
    if (ctos_ssh_view_allocate(view, (size_t)length) != 0) goto failed;
    (*env)->GetByteArrayRegion(env, wire, 0, length, (jbyte *)view->mapping);
    if ((*env)->ExceptionCheck(env) || ctos_ssh_view_validate(view) != 0) goto failed;
    current = view; current_token = ++next_token;
    jlong result = (jlong)current_token;
    pthread_mutex_unlock(&ownership);
    return result;
failed:
    (void)release(view);
    pthread_mutex_unlock(&ownership);
    fail(env);
    return 0;
}

JNIEXPORT jlongArray JNICALL Java_im_majo_ctos_SshPublicFileLease_00024Native_summary(
    JNIEnv *env, jclass type, jlong token) {
    (void)type;
    pthread_mutex_lock(&ownership);
    if (current == NULL || token <= 0 || (uint64_t)token != current_token || poisoned
        || ctos_ssh_view_live(current) != 0) {
        pthread_mutex_unlock(&ownership); fail(env); return NULL;
    }
    struct stat shape;
    int flags = fcntl(current->fd, F_GETFD);
    if (fstat(current->fd, &shape) != 0 || !S_ISREG(shape.st_mode) || flags < 0) {
        pthread_mutex_unlock(&ownership); fail(env); return NULL;
    }
    jlong values[] = {current->count, current->private_count, current->public_bytes,
        (jlong)current->generation, (jlong)current->epoch, (jlong)shape.st_size,
        shape.st_mode & 0777, (flags & FD_CLOEXEC) != 0};
    jlongArray result = (*env)->NewLongArray(env, 8);
    if (result != NULL) (*env)->SetLongArrayRegion(env, result, 0, 8, values);
    pthread_mutex_unlock(&ownership);
    return result;
}

JNIEXPORT jboolean JNICALL Java_im_majo_ctos_SshPublicFileLease_00024Native_destroy(
    JNIEnv *env, jclass type, jlong token) {
    (void)env; (void)type;
    pthread_mutex_lock(&ownership);
    /* Unknown old tokens cannot close the currently owned object. */
    int result = 0;
    if (current != NULL && token > 0 && (uint64_t)token == current_token) {
        result = release(current); current = NULL; current_token = 0;
    }
    int failed = poisoned || result != 0;
    pthread_mutex_unlock(&ownership);
    return failed ? JNI_FALSE : JNI_TRUE;
}

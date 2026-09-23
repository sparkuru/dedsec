#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <poll.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static void fail(JNIEnv *env, const char *message) {
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), message);
}

JNIEXPORT jintArray JNICALL Java_im_majo_ctos_Pty_start(
        JNIEnv *env, jclass type, jboolean root, jint columns, jint rows, jstring directory) {
    (void) type;
    int master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0) { fail(env, strerror(errno)); return NULL; }
    if (grantpt(master) || unlockpt(master)) {
        fail(env, strerror(errno)); close(master); return NULL;
    }
    char slave_name[128];
    if (ptsname_r(master, slave_name, sizeof(slave_name))) {
        fail(env, "Cannot locate PTY slave"); close(master); return NULL;
    }
    int slave = open(slave_name, O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (slave < 0) { fail(env, strerror(errno)); close(master); return NULL; }
    struct winsize size = {.ws_row = rows, .ws_col = columns};
    ioctl(slave, TIOCSWINSZ, &size);
    const char *cwd_utf = (*env)->GetStringUTFChars(env, directory, NULL);
    char *cwd = strdup(cwd_utf);
    (*env)->ReleaseStringUTFChars(env, directory, cwd_utf);
    if (!cwd) { close(slave); close(master); fail(env, "Out of memory"); return NULL; }
    // Prepare all strings before fork; the child must not re-enter the Java VM.
    char *const app_args[] = {"/system/bin/sh", "-i", NULL};
    // Interactive su allocates its controlling TTY; -c would bypass job control.
    char *const root_args[] = {"su", NULL};
    char *const environment[] = {"TERM=xterm-256color", "PATH=/product/bin:/system/bin:/system/xbin:/vendor/bin", "LANG=C.UTF-8", NULL};
    pid_t pid = fork();
    if (pid == 0) {
        close(master);
        if (setsid() < 0 || ioctl(slave, TIOCSCTTY, 0) < 0) _exit(126);
        dup2(slave, STDIN_FILENO); dup2(slave, STDOUT_FILENO); dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        chdir(cwd);
        if (root) {
            execve("/product/bin/su", root_args, environment);
            execve("/system/bin/su", root_args, environment);
            execve("/system/xbin/su", root_args, environment);
        } else execve("/system/bin/sh", app_args, environment);
        const char message[] = "ctOS: unable to execute shell\r\n";
        write(STDERR_FILENO, message, sizeof(message) - 1);
        _exit(127);
    }
    free(cwd);
    close(slave);
    if (pid < 0) { close(master); fail(env, strerror(errno)); return NULL; }
    jint values[] = {master, pid};
    jintArray result = (*env)->NewIntArray(env, 2);
    (*env)->SetIntArrayRegion(env, result, 0, 2, values);
    return result;
}

JNIEXPORT jbyteArray JNICALL Java_im_majo_ctos_Pty_read(JNIEnv *env, jclass type, jint fd) {
    (void) type;
    char buffer[8192];
    struct pollfd ready = {.fd = fd, .events = POLLIN};
    int available = poll(&ready, 1, 250);
    if (available == 0 || (available < 0 && errno == EINTR)) return (*env)->NewByteArray(env, 0);
    if (available < 0) return NULL;
    ssize_t count;
    do { count = read(fd, buffer, sizeof(buffer)); } while (count < 0 && errno == EINTR);
    if (count <= 0) return NULL;
    jbyteArray result = (*env)->NewByteArray(env, count);
    (*env)->SetByteArrayRegion(env, result, 0, count, (jbyte *) buffer);
    return result;
}

JNIEXPORT void JNICALL Java_im_majo_ctos_Pty_write(JNIEnv *env, jclass type, jint fd, jbyteArray data) {
    (void) type;
    jsize length = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    ssize_t offset = 0;
    while (offset < length) {
        ssize_t count = write(fd, bytes + offset, length - offset);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { fail(env, strerror(errno)); break; }
        offset += count;
    }
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_im_majo_ctos_Pty_resize(JNIEnv *env, jclass type, jint fd, jint columns, jint rows) {
    (void) type;
    struct winsize size = {.ws_row = rows, .ws_col = columns};
    if (ioctl(fd, TIOCSWINSZ, &size) < 0) fail(env, strerror(errno));
}

JNIEXPORT void JNICALL Java_im_majo_ctos_Pty_close(JNIEnv *env, jclass type, jint fd) {
    (void) env; (void) type;
    close(fd);
}

JNIEXPORT jint JNICALL Java_im_majo_ctos_Pty_waitFor(JNIEnv *env, jclass type, jint pid) {
    (void) env; (void) type;
    int status;
    pid_t result;
    do { result = waitpid(pid, &status, 0); } while (result < 0 && errno == EINTR);
    if (result < 0) return -1;
    return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
}

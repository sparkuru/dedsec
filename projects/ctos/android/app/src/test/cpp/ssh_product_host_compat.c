#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <poll.h>
#include <stdint.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

/* Only the Android APIs needed by the unmodified TCP connector are mapped. */
static int socket_count;
static int transferred_socket = -1;

static void throw_errno(JNIEnv *env, const char *operation, int error) {
    jclass type = (*env)->FindClass(env, "android/system/ErrnoException");
    if (type == NULL) return;
    jmethodID ctor = (*env)->GetMethodID(env, type, "<init>", "(Ljava/lang/String;I)V");
    if (ctor == NULL) return;
    jstring name = (*env)->NewStringUTF(env, operation);
    if (name == NULL) return;
    jobject exception = (*env)->NewObject(env, type, ctor, name, (jint)error);
    if (exception != NULL) (*env)->Throw(env, exception);
}

static jfieldID descriptor_field(JNIEnv *env) {
    jclass type = (*env)->FindClass(env, "java/io/FileDescriptor");
    if (type == NULL) return NULL;
    return (*env)->GetFieldID(env, type, "fd", "I");
}

static int descriptor_value(JNIEnv *env, jobject descriptor) {
    if (descriptor == NULL) {
        throw_errno(env, "descriptor", EBADF);
        return -1;
    }
    jfieldID field = descriptor_field(env);
    if (field == NULL) return -1;
    int fd = (*env)->GetIntField(env, descriptor, field);
    if (fd < 0) throw_errno(env, "descriptor", EBADF);
    return fd;
}

JNIEXPORT jint JNICALL Java_im_majo_ctos_host_HostLinux_pid(JNIEnv *env, jclass type) {
    (void)env; (void)type;
    return (jint)getpid();
}

JNIEXPORT jint JNICALL Java_im_majo_ctos_host_HostLinux_uid(JNIEnv *env, jclass type) {
    (void)env; (void)type;
    return (jint)getuid();
}

JNIEXPORT jlong JNICALL Java_im_majo_ctos_host_HostLinux_elapsedRealtime(JNIEnv *env, jclass type) {
    (void)type;
    struct timespec value;
    if (clock_gettime(CLOCK_BOOTTIME, &value) != 0) {
        jclass exception = (*env)->FindClass(env, "java/lang/IllegalStateException");
        if (exception != NULL) (*env)->ThrowNew(env, exception, "CLOCK_BOOTTIME failed");
        return 0;
    }
    return (jlong)value.tv_sec * 1000 + value.tv_nsec / 1000000;
}

JNIEXPORT jobject JNICALL Java_im_majo_ctos_host_HostLinux_socket(JNIEnv *env, jclass type,
        jint domain, jint kind, jint protocol) {
    (void)type;
    if (domain != AF_INET || kind != (SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK)
            || protocol != IPPROTO_TCP) {
        throw_errno(env, "socket", EAFNOSUPPORT);
        return NULL;
    }
    int fd = socket(domain, kind, protocol);
    if (fd < 0) { throw_errno(env, "socket", errno); return NULL; }
    socket_count++;
    jclass fd_type = (*env)->FindClass(env, "java/io/FileDescriptor");
    if (fd_type == NULL) { close(fd); return NULL; }
    jmethodID ctor = (*env)->GetMethodID(env, fd_type, "<init>", "()V");
    if (ctor == NULL) { close(fd); return NULL; }
    jobject descriptor = (*env)->NewObject(env, fd_type, ctor);
    if (descriptor == NULL) { close(fd); return NULL; }
    jfieldID field = descriptor_field(env);
    if (field == NULL) { close(fd); return NULL; }
    (*env)->SetIntField(env, descriptor, field, fd);
    return descriptor;
}

JNIEXPORT void JNICALL Java_im_majo_ctos_host_HostLinux_connect(JNIEnv *env, jclass type,
        jobject descriptor, jbyteArray address, jint port) {
    (void)type;
    int fd = descriptor_value(env, descriptor);
    if (fd < 0) return;
    if (address == NULL || (*env)->GetArrayLength(env, address) != 4 || port < 1 || port > 65535) {
        throw_errno(env, "connect", EINVAL);
        return;
    }
    jbyte bytes[4];
    (*env)->GetByteArrayRegion(env, address, 0, 4, bytes);
    if ((*env)->ExceptionCheck(env)) return;
    if ((uint8_t)bytes[0] != 127 || bytes[1] != 0 || bytes[2] != 0 || bytes[3] != 1) {
        throw_errno(env, "connect", EACCES);
        return;
    }
    struct sockaddr_in target = {0};
    target.sin_family = AF_INET;
    target.sin_port = htons((uint16_t)port);
    target.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (connect(fd, (struct sockaddr *)&target, sizeof(target)) != 0)
        throw_errno(env, "connect", errno);
}

JNIEXPORT jint JNICALL Java_im_majo_ctos_host_HostLinux_poll(JNIEnv *env, jclass type,
        jobject descriptor, jint events, jint timeout) {
    (void)type;
    int fd = descriptor_value(env, descriptor);
    if (fd < 0) return 0;
    struct pollfd item = { .fd = fd, .events = (short)events, .revents = 0 };
    int result = poll(&item, 1, timeout);
    if (result < 0) { throw_errno(env, "poll", errno); return 0; }
    return item.revents;
}

JNIEXPORT jbyteArray JNICALL Java_im_majo_ctos_host_HostLinux_peer(JNIEnv *env, jclass type,
        jobject descriptor) {
    (void)type;
    int fd = descriptor_value(env, descriptor);
    if (fd < 0) return NULL;
    struct sockaddr_in peer = {0};
    socklen_t size = sizeof(peer);
    if (getpeername(fd, (struct sockaddr *)&peer, &size) != 0) {
        throw_errno(env, "getpeername", errno);
        return NULL;
    }
    if (size != sizeof(peer) || peer.sin_family != AF_INET || ntohl(peer.sin_addr.s_addr) != INADDR_LOOPBACK) {
        throw_errno(env, "getpeername", EACCES);
        return NULL;
    }
    uint16_t port = ntohs(peer.sin_port);
    jbyte bytes[6] = {127, 0, 0, 1, (jbyte)(port >> 8), (jbyte)port};
    jbyteArray result = (*env)->NewByteArray(env, 6);
    if (result == NULL) return NULL;
    (*env)->SetByteArrayRegion(env, result, 0, 6, bytes);
    return result;
}

JNIEXPORT jint JNICALL Java_im_majo_ctos_host_HostLinux_dup(JNIEnv *env, jclass type,
        jobject descriptor) {
    (void)type;
    int fd = descriptor_value(env, descriptor);
    if (fd < 0) return -1;
    int copy = dup(fd);
    if (copy < 0) throw_errno(env, "dup", errno);
    else transferred_socket = copy;
    return copy;
}

JNIEXPORT void JNICALL Java_im_majo_ctos_host_HostLinux_closeDescriptor(JNIEnv *env, jclass type,
        jobject descriptor) {
    (void)type;
    int fd = descriptor_value(env, descriptor);
    if (fd < 0) return;
    jfieldID field = descriptor_field(env);
    if (field == NULL) return;
    (*env)->SetIntField(env, descriptor, field, -1);
    if (close(fd) != 0) throw_errno(env, "close", errno);
}

JNIEXPORT void JNICALL Java_im_majo_ctos_host_HostLinux_closeNumber(JNIEnv *env, jclass type,
        jint fd) {
    (void)type;
    if (close(fd) != 0) throw_errno(env, "close", errno);
}

JNIEXPORT void JNICALL Java_im_majo_ctos_host_HostLinux_resetSocketEvidence(JNIEnv *env, jclass type) {
    (void)env; (void)type;
    socket_count = 0;
    transferred_socket = -1;
}

JNIEXPORT jint JNICALL Java_im_majo_ctos_host_HostLinux_socketCount(JNIEnv *env, jclass type) {
    (void)env; (void)type;
    return socket_count;
}

JNIEXPORT jint JNICALL Java_im_majo_ctos_host_HostLinux_transferredSocket(JNIEnv *env, jclass type) {
    (void)env; (void)type;
    return transferred_socket;
}

JNIEXPORT jboolean JNICALL Java_im_majo_ctos_host_HostLinux_descriptorClosed(JNIEnv *env, jclass type,
        jint fd) {
    (void)env; (void)type;
    if (fd < 0) return JNI_FALSE;
    return fcntl(fd, F_GETFD) == -1 && errno == EBADF ? JNI_TRUE : JNI_FALSE;
}

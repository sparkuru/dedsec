#define _GNU_SOURCE
#include "ssh_control.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <string.h>
#include <sys/random.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

static void wipe(void *memory, size_t length) {
    volatile unsigned char *p = memory;
    while (length-- != 0) *p++ = 0;
}
static int nonzero(const unsigned char *value) {
    unsigned int result = 0;
    for (unsigned int i = 0; i < 32; i++) result |= value[i];
    return result != 0;
}
static uint32_t get32(const unsigned char *p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3];
}
static void put32(unsigned char *p, uint32_t value) {
    p[0] = (unsigned char)(value >> 24); p[1] = (unsigned char)(value >> 16);
    p[2] = (unsigned char)(value >> 8); p[3] = (unsigned char)value;
}
static int kind_valid(unsigned int kind) { return kind >= CTOS_CONTROL_JOIN && kind <= CTOS_CONTROL_FAILURE; }

static int now_millis(uint64_t *out) {
    struct timespec now;
    if (clock_gettime(CLOCK_BOOTTIME, &now) != 0) return -1;
    if (now.tv_sec < 0 || now.tv_nsec < 0 || now.tv_nsec >= 1000000000
        || (uint64_t)now.tv_sec > ((uint64_t)INT64_MAX - 999) / 1000) { errno = EIO; return -1; }
    *out = (uint64_t)now.tv_sec * 1000 + (uint64_t)now.tv_nsec / 1000000;
    return 0;
}
static int remaining(uint64_t deadline) {
    uint64_t now;
    if (now_millis(&now) != 0) return -1;
    if (deadline == 0 || deadline > INT64_MAX || now >= deadline) { errno = ETIMEDOUT; return -1; }
    uint64_t delta = deadline - now;
    return delta > INT_MAX ? INT_MAX : (int)delta;
}
static int wait_ready(int fd, short events, uint64_t deadline) {
    for (unsigned int attempt = 0; attempt < 256; attempt++) {
        int timeout = remaining(deadline); if (timeout < 0) return -1;
        struct pollfd ready = {.fd = fd, .events = events};
        int result = poll(&ready, 1, timeout);
        if (result < 0 && errno == EINTR) continue;
        if (result < 0) return -1;
        if (result == 0) { errno = ETIMEDOUT; return -1; }
        if ((ready.revents & events) != 0) return 0;
        errno = ECONNRESET; return -1;
    }
    errno = EAGAIN; return -1;
}

int ctos_ssh_control_pair(int pair[2]) {
    if (pair == NULL) { errno = EINVAL; return -1; }
    pair[0] = pair[1] = -1;
    int own[2];
    if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, own) != 0) return -1;
    int enabled = 1;
    if (setsockopt(own[0], SOL_SOCKET, SO_PASSCRED, &enabled, sizeof(enabled)) != 0
        || setsockopt(own[1], SOL_SOCKET, SO_PASSCRED, &enabled, sizeof(enabled)) != 0) {
        int failure = errno; close(own[0]); close(own[1]); errno = failure; return -1;
    }
    pair[0] = own[0]; pair[1] = own[1];
    return 0;
}

int ctos_ssh_control_nonce(unsigned char nonce[32]) {
    if (nonce == NULL) { errno = EINVAL; return -1; }
    size_t offset = 0;
    for (unsigned int attempt = 0; attempt < 128 && offset < 32; attempt++) {
        ssize_t count = syscall(SYS_getrandom, nonce + offset, 32 - offset, GRND_NONBLOCK);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { int failure = count == 0 ? EIO : errno; wipe(nonce, 32); errno = failure; return -1; }
        offset += (size_t)count;
    }
    if (offset != 32 || !nonzero(nonce)) { wipe(nonce, 32); errno = EIO; return -1; }
    return 0;
}

int ctos_ssh_control_init(struct ctos_ssh_control_peer *peer, int fd, pid_t pid,
    const unsigned char role[32], const unsigned char connection[32]) {
    if (peer == NULL || fd < 3 || pid <= 0 || role == NULL || connection == NULL
        || !nonzero(role) || !nonzero(connection)) { errno = EINVAL; return -1; }
    int type = 0, family = 0, credentials = 0;
    socklen_t size = sizeof(int);
    if (getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &size) != 0 || size != sizeof(int) || type != SOCK_SEQPACKET) { errno = EINVAL; return -1; }
    size = sizeof(int);
    if (getsockopt(fd, SOL_SOCKET, SO_DOMAIN, &family, &size) != 0 || size != sizeof(int) || family != AF_UNIX) { errno = EINVAL; return -1; }
    size = sizeof(int);
    if (getsockopt(fd, SOL_SOCKET, SO_PASSCRED, &credentials, &size) != 0 || size != sizeof(int) || credentials != 1) { errno = EINVAL; return -1; }
    int descriptor = fcntl(fd, F_GETFD), flags = fcntl(fd, F_GETFL);
    if (descriptor < 0 || flags < 0 || (descriptor & FD_CLOEXEC) == 0 || (flags & O_NONBLOCK) == 0) { errno = EINVAL; return -1; }
    memset(peer, 0, sizeof(*peer)); peer->fd = fd; peer->pid = pid; peer->uid = getuid();
    memcpy(peer->role, role, 32); memcpy(peer->connection, connection, 32);
    return 0;
}

int ctos_ssh_control_close(struct ctos_ssh_control_peer *peer) {
    if (peer == NULL) { errno = EINVAL; return -1; }
    int fd = peer->fd;
    wipe(peer, sizeof(*peer)); peer->fd = -1; peer->failed = 1;
    return fd >= 0 ? close(fd) : 0;
}
static int reject(struct ctos_ssh_control_peer *peer, int failure) {
    if (ctos_ssh_control_close(peer) != 0) failure = EIO;
    errno = failure; return -1;
}
int ctos_ssh_control_frame_close(struct ctos_ssh_control_frame *frame) {
    if (frame == NULL) { errno = EINVAL; return -1; }
    int fd = frame->fd;
    wipe(frame, sizeof(*frame)); frame->fd = -1;
    return fd >= 0 ? close(fd) : 0;
}

static int loan_shape(const unsigned char *payload, size_t length, int fd) {
    if (length != CTOS_SSH_CONTROL_LOAN || fd < 0 || payload[0] < 1 || payload[0] > 5) { errno = EINVAL; return -1; }
    uint32_t bytes = get32(payload + 1);
    uint32_t maximum = payload[0] == 1 ? 12U * 1024U * 1024U : payload[0] == 2 ? 1024U * 1024U : 8192U;
    struct stat shape;
    int descriptor = fcntl(fd, F_GETFD), flags = fcntl(fd, F_GETFL);
    if (bytes > maximum || descriptor < 0 || flags < 0 || (descriptor & FD_CLOEXEC) == 0
        || (flags & O_ACCMODE) != O_RDONLY || fstat(fd, &shape) != 0 || !S_ISREG(shape.st_mode)
        || shape.st_uid != getuid() || (shape.st_mode & 0777) != 0600 || shape.st_nlink != 0
        || shape.st_size != (off_t)bytes || fcntl(fd, F_GET_SEALS) < 0) { errno = EACCES; return -1; }
    return 0;
}

int ctos_ssh_control_send(struct ctos_ssh_control_peer *peer, unsigned int kind,
    const unsigned char *payload, size_t length, int loan_fd, uint64_t deadline) {
    if (peer == NULL || peer->fd < 0 || peer->failed) { errno = EACCES; return -1; }
    if (!kind_valid(kind) || length > CTOS_SSH_CONTROL_PAYLOAD || (length != 0 && payload == NULL)
        || peer->sent == INT64_MAX || (kind == CTOS_CONTROL_FILE_LOAN ? loan_shape(payload, length, loan_fd) != 0 : loan_fd != -1))
        return reject(peer, EINVAL);
    unsigned char header[CTOS_SSH_CONTROL_HEADER] = {0};
    put32(header, 0x43534843U); header[5] = 1; header[7] = (unsigned char)kind;
    put32(header + 12, (uint32_t)length);
    uint64_t sequence = peer->sent + 1;
    put32(header + 16, (uint32_t)(sequence >> 32)); put32(header + 20, (uint32_t)sequence);
    memcpy(header + 24, peer->role, 32); memcpy(header + 56, peer->connection, 32);
    struct iovec vector[] = {{header, sizeof(header)}, {(void *)payload, length}};
    union { struct cmsghdr align; unsigned char bytes[CMSG_SPACE(sizeof(int))]; } control = {0};
    struct msghdr message = {.msg_iov = vector, .msg_iovlen = length == 0 ? 1 : 2};
    if (loan_fd >= 0) {
        message.msg_control = control.bytes; message.msg_controllen = sizeof(control.bytes);
        struct cmsghdr *cmsg = CMSG_FIRSTHDR(&message);
        cmsg->cmsg_level = SOL_SOCKET; cmsg->cmsg_type = SCM_RIGHTS; cmsg->cmsg_len = CMSG_LEN(sizeof(int));
        memcpy(CMSG_DATA(cmsg), &loan_fd, sizeof(int));
    }
    for (unsigned int attempt = 0; attempt < 256; attempt++) {
        if (wait_ready(peer->fd, POLLOUT, deadline) != 0) break;
        ssize_t count = sendmsg(peer->fd, &message, MSG_DONTWAIT | MSG_NOSIGNAL);
        if (count < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) continue;
        if (count != (ssize_t)(sizeof(header) + length)) { if (count >= 0) errno = EIO; break; }
        peer->sent = sequence; wipe(header, sizeof(header)); wipe(&control, sizeof(control)); return 0;
    }
    int failure = errno == 0 ? EIO : errno;
    wipe(header, sizeof(header)); wipe(&control, sizeof(control));
    return reject(peer, failure);
}

int ctos_ssh_control_receive(struct ctos_ssh_control_peer *peer, struct ctos_ssh_control_frame *frame, uint64_t deadline) {
    if (peer == NULL || peer->fd < 0 || peer->failed || frame == NULL) { errno = EACCES; return -1; }
    if (frame->fd >= 0) { errno = EBUSY; return -1; }
    unsigned char bytes[CTOS_SSH_CONTROL_HEADER + CTOS_SSH_CONTROL_PAYLOAD] = {0};
    union { struct cmsghdr align; unsigned char bytes[CMSG_SPACE(sizeof(struct ucred)) + CMSG_SPACE(8 * sizeof(int))]; } control = {0};
    struct iovec vector = {bytes, sizeof(bytes)};
    struct msghdr message = {.msg_iov = &vector, .msg_iovlen = 1, .msg_control = control.bytes};
    ssize_t count = -1;
    for (unsigned int attempt = 0; attempt < 256; attempt++) {
        if (wait_ready(peer->fd, POLLIN, deadline) != 0) break;
        message.msg_controllen = sizeof(control.bytes); message.msg_flags = 0;
        count = recvmsg(peer->fd, &message, MSG_DONTWAIT | MSG_CMSG_CLOEXEC);
        if (count < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) continue;
        break;
    }
    int failure = count < 0 ? (errno == 0 ? EIO : errno) : EPROTO;
    int descriptors[8], descriptor_count = 0, credentials = 0, malformed = 0;
    struct ucred sender = {0};
    if (count >= 0) for (struct cmsghdr *cmsg = CMSG_FIRSTHDR(&message); cmsg != NULL; cmsg = CMSG_NXTHDR(&message, cmsg)) {
        if (cmsg->cmsg_len < CMSG_LEN(0) || cmsg->cmsg_level != SOL_SOCKET) { malformed = 1; continue; }
        size_t length = cmsg->cmsg_len - CMSG_LEN(0);
        if (cmsg->cmsg_type == SCM_RIGHTS) {
            if (length % sizeof(int) != 0) malformed = 1;
            size_t amount = length / sizeof(int);
            for (size_t i = 0; i < amount; i++) {
                int owned; memcpy(&owned, (unsigned char *)CMSG_DATA(cmsg) + i * sizeof(int), sizeof(int));
                if (descriptor_count < 8) descriptors[descriptor_count++] = owned;
                else { if (close(owned) != 0) failure = EIO; malformed = 1; }
            }
        } else if (cmsg->cmsg_type == SCM_CREDENTIALS && length == sizeof(sender)) {
            memcpy(&sender, CMSG_DATA(cmsg), sizeof(sender)); credentials++;
        } else malformed = 1;
    }
    uint64_t sequence = 0; unsigned int kind = 0, length = 0, difference = 0;
    if (count >= (ssize_t)CTOS_SSH_CONTROL_HEADER) {
        sequence = ((uint64_t)get32(bytes + 16) << 32) | get32(bytes + 20);
        kind = ((unsigned int)bytes[6] << 8) | bytes[7]; length = get32(bytes + 12);
        for (unsigned int i = 0; i < 32; i++) difference |= (bytes[24 + i] ^ peer->role[i]) | (bytes[56 + i] ^ peer->connection[i]);
    }
    int valid = count >= (ssize_t)CTOS_SSH_CONTROL_HEADER && !malformed
        && (message.msg_flags & (MSG_TRUNC | MSG_CTRUNC)) == 0 && credentials == 1
        && sender.pid == peer->pid && sender.uid == peer->uid && difference == 0
        && get32(bytes) == 0x43534843U && bytes[4] == 0 && bytes[5] == 1 && get32(bytes + 8) == 0
        && kind_valid(kind) && length <= CTOS_SSH_CONTROL_PAYLOAD
        && count == (ssize_t)(CTOS_SSH_CONTROL_HEADER + length) && peer->received < INT64_MAX
        && sequence == peer->received + 1 && sequence <= INT64_MAX
        && (kind == CTOS_CONTROL_FILE_LOAN ? descriptor_count == 1
            && loan_shape(bytes + CTOS_SSH_CONTROL_HEADER, length, descriptors[0]) == 0 : descriptor_count == 0);
    if (valid) {
        frame->kind = kind; frame->length = length; frame->sequence = sequence;
        memcpy(frame->payload, bytes + CTOS_SSH_CONTROL_HEADER, length);
        frame->fd = descriptor_count == 1 ? descriptors[0] : -1;
        peer->received = sequence;
    } else {
        for (int i = 0; i < descriptor_count; i++) if (close(descriptors[i]) != 0) failure = EIO;
        (void)ctos_ssh_control_frame_close(frame);
    }
    wipe(bytes, sizeof(bytes)); wipe(&control, sizeof(control));
    return valid ? 0 : reject(peer, failure);
}

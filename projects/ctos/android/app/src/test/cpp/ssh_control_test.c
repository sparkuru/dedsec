#define _GNU_SOURCE
#include "ssh_control.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/memfd.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static unsigned int checks, cases;
static pid_t pending_child;
#define CHECK(condition) do { checks++; if (!(condition)) { \
    printf("SSH_CONTROL phase=gate state=error errno=5 value=%u\n", checks); fflush(stdout); _exit(2); \
} } while (0)
static uint64_t deadline(unsigned int millis) {
    struct timespec time; CHECK(clock_gettime(CLOCK_BOOTTIME, &time) == 0);
    return (uint64_t)time.tv_sec * 1000 + (uint64_t)time.tv_nsec / 1000000 + millis;
}
static void wait_owned(pid_t pid) {
    CHECK(pending_child == pid && pid > 0);
    int status = 0;
    for (unsigned int attempt = 0; attempt < 200; attempt++) {
        pid_t result = waitpid(pid, &status, WNOHANG);
        if (result == pid) { pending_child = 0; CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 0); return; }
        if (result < 0 && errno != EINTR) { pending_child = 0; CHECK(0); }
        struct timespec pause = {.tv_nsec = 10000000}; (void)nanosleep(&pause, NULL);
    }
    /* Still unreaped and exclusively owned: terminate before the final reap. */
    CHECK(kill(pid, SIGKILL) == 0 || errno == ESRCH);
    for (unsigned int attempt = 0; attempt < 200; attempt++) {
        pid_t result = waitpid(pid, &status, WNOHANG);
        if (result == pid) { pending_child = 0; CHECK(0); }
        if (result < 0 && errno != EINTR) { pending_child = 0; CHECK(0); }
        struct timespec pause = {.tv_nsec = 10000000}; (void)nanosleep(&pause, NULL);
    }
    CHECK(0);
}
static void child_guard(pid_t parent, int unused) {
    CHECK(close(unused) == 0);
    CHECK(prctl(PR_SET_PDEATHSIG, SIGKILL) == 0 && getppid() == parent);
}
static unsigned int descriptor_count(void) {
    unsigned int count = 0;
    for (int fd = 0; fd < 256; fd++) if (fcntl(fd, F_GETFD) >= 0) count++;
    return count;
}
static void u32(unsigned char *bytes, uint32_t value) {
    bytes[0] = (unsigned char)(value >> 24); bytes[1] = (unsigned char)(value >> 16);
    bytes[2] = (unsigned char)(value >> 8); bytes[3] = (unsigned char)value;
}
static void raw_header(unsigned char *bytes, unsigned int kind, size_t length,
    uint64_t sequence, const unsigned char *role, const unsigned char *connection) {
    memset(bytes, 0, CTOS_SSH_CONTROL_HEADER);
    u32(bytes, 0x43534843U); bytes[5] = 1; bytes[7] = (unsigned char)kind; u32(bytes + 12, (uint32_t)length);
    u32(bytes + 16, (uint32_t)(sequence >> 32)); u32(bytes + 20, (uint32_t)sequence);
    memcpy(bytes + 24, role, 32); memcpy(bytes + 56, connection, 32);
}
static void raw_send(int fd, const unsigned char *bytes, size_t length, const int *fds, size_t count) {
    union { struct cmsghdr align; unsigned char bytes[CMSG_SPACE(12 * sizeof(int))]; } control = {0};
    struct iovec vector = {(void *)bytes, length};
    struct msghdr message = {.msg_iov = &vector, .msg_iovlen = 1};
    if (count != 0) {
        CHECK(count <= 12);
        message.msg_control = control.bytes; message.msg_controllen = CMSG_SPACE(count * sizeof(int));
        struct cmsghdr *cmsg = CMSG_FIRSTHDR(&message);
        cmsg->cmsg_level = SOL_SOCKET; cmsg->cmsg_type = SCM_RIGHTS; cmsg->cmsg_len = CMSG_LEN(count * sizeof(int));
        memcpy(CMSG_DATA(cmsg), fds, count * sizeof(int));
    }
    CHECK(sendmsg(fd, &message, MSG_NOSIGNAL) == (ssize_t)length);
}
static int public_blob(int *writer, void **mapping) {
    *writer = (int)syscall(SYS_memfd_create, "ctos-control-test", MFD_CLOEXEC);
    CHECK(*writer >= 0 && fchmod(*writer, 0600) == 0 && ftruncate(*writer, 7) == 0);
    *mapping = mmap(NULL, 7, PROT_READ | PROT_WRITE, MAP_SHARED, *writer, 0);
    CHECK(*mapping != MAP_FAILED && madvise(*mapping, 7, MADV_DONTDUMP) == 0 && mlock(*mapping, 7) == 0);
    memcpy(*mapping, "public\n", 7);
    char path[64]; CHECK(snprintf(path, sizeof(path), "/proc/self/fd/%d", *writer) > 0);
    int reader = open(path, O_RDONLY | O_CLOEXEC); CHECK(reader >= 0);
    struct stat a, b;
    CHECK(fstat(*writer, &a) == 0 && fstat(reader, &b) == 0 && a.st_dev == b.st_dev && a.st_ino == b.st_ino);
    return reader;
}
static void blob_close(int writer, int reader, void *mapping) {
    volatile unsigned char *bytes = mapping;
    for (size_t i = 0; i < 7; i++) bytes[i] = 0;
    CHECK(munlock(mapping, 7) == 0 && munmap(mapping, 7) == 0 && ftruncate(writer, 0) == 0);
    unsigned char byte; CHECK(pread(reader, &byte, 1, 0) == 0);
    CHECK(close(reader) == 0 && close(writer) == 0);
}

static void roundtrip(const unsigned char *role, const unsigned char *connection) {
    int pair[2], writer; void *mapping;
    int reader = public_blob(&writer, &mapping);
    CHECK(ctos_ssh_control_pair(pair) == 0 && pending_child == 0);
    pid_t parent = getpid(), pid = fork(); CHECK(pid >= 0);
    if (pid == 0) {
        child_guard(parent, pair[0]);
        struct ucred creator; socklen_t length = sizeof(creator);
        CHECK(getsockopt(pair[1], SOL_SOCKET, SO_PEERCRED, &creator, &length) == 0 && creator.pid == parent);
        struct ctos_ssh_control_peer peer;
        CHECK(ctos_ssh_control_init(&peer, pair[1], parent, role, connection) == 0);
        CHECK(ctos_ssh_control_send(&peer, CTOS_CONTROL_JOIN, NULL, 0, -1, deadline(2000)) == 0);
        struct ctos_ssh_control_frame frame = {.fd = -1};
        CHECK(ctos_ssh_control_receive(&peer, &frame, deadline(2000)) == 0 && frame.kind == CTOS_CONTROL_HOST_DECISION && frame.sequence == 1);
        CHECK(frame.length == 4 && memcmp(frame.payload, "test", 4) == 0 && ctos_ssh_control_frame_close(&frame) == 0);
        CHECK(ctos_ssh_control_send(&peer, CTOS_CONTROL_KEX_DONE, NULL, 0, -1, deadline(2000)) == 0);
        CHECK(ctos_ssh_control_receive(&peer, &frame, deadline(2000)) == 0 && frame.kind == CTOS_CONTROL_FILE_LOAN && frame.fd >= 0);
        unsigned char body[7]; CHECK(pread(frame.fd, body, sizeof(body), 0) == 7 && memcmp(body, "public\n", 7) == 0);
        CHECK((fcntl(frame.fd, F_GETFD) & FD_CLOEXEC) != 0);
        CHECK(ctos_ssh_control_frame_close(&frame) == 0);
        CHECK(ctos_ssh_control_send(&peer, CTOS_CONTROL_LOAN_ACK, NULL, 0, -1, deadline(2000)) == 0);
        CHECK(ctos_ssh_control_close(&peer) == 0); _exit(0);
    }
    pending_child = pid; CHECK(close(pair[1]) == 0);
    struct ctos_ssh_control_peer peer;
    CHECK(ctos_ssh_control_init(&peer, pair[0], pid, role, connection) == 0);
    struct ctos_ssh_control_frame frame = {.fd = -1};
    CHECK(ctos_ssh_control_receive(&peer, &frame, deadline(2000)) == 0 && frame.kind == CTOS_CONTROL_JOIN && frame.sequence == 1);
    CHECK(ctos_ssh_control_frame_close(&frame) == 0);
    CHECK(ctos_ssh_control_send(&peer, CTOS_CONTROL_HOST_DECISION, (const unsigned char *)"test", 4, -1, deadline(2000)) == 0);
    CHECK(ctos_ssh_control_receive(&peer, &frame, deadline(2000)) == 0 && frame.kind == CTOS_CONTROL_KEX_DONE && frame.sequence == 2);
    CHECK(ctos_ssh_control_frame_close(&frame) == 0);
    unsigned char loan[CTOS_SSH_CONTROL_LOAN] = {1}; u32(loan + 1, 7); memset(loan + 5, 2, 32);
    CHECK(ctos_ssh_control_send(&peer, CTOS_CONTROL_FILE_LOAN, loan, sizeof(loan), reader, deadline(2000)) == 0);
    CHECK(ctos_ssh_control_receive(&peer, &frame, deadline(2000)) == 0 && frame.kind == CTOS_CONTROL_LOAN_ACK && frame.sequence == 3);
    CHECK(ctos_ssh_control_frame_close(&frame) == 0 && ctos_ssh_control_close(&peer) == 0);
    wait_owned(pid); blob_close(writer, reader, mapping); cases++;
}

static void malformed(unsigned int mutation, const unsigned char *role, const unsigned char *connection) {
    int pair[2], writer; void *mapping;
    int reader = public_blob(&writer, &mapping);
    CHECK(ctos_ssh_control_pair(pair) == 0 && pending_child == 0);
    pid_t parent = getpid(), pid = fork(); CHECK(pid >= 0);
    if (pid == 0) {
        child_guard(parent, pair[0]);
        unsigned char bytes[CTOS_SSH_CONTROL_HEADER + CTOS_SSH_CONTROL_PAYLOAD + 1] = {0};
        unsigned int kind = CTOS_CONTROL_JOIN; size_t payload = 0, count = 0;
        int descriptors[12]; for (unsigned int i = 0; i < 12; i++) descriptors[i] = reader;
        if (mutation >= 12 && mutation <= 18) { kind = CTOS_CONTROL_FILE_LOAN; payload = CTOS_SSH_CONTROL_LOAN; }
        raw_header(bytes, kind, payload, 1, role, connection);
        size_t length = CTOS_SSH_CONTROL_HEADER + payload;
        if (mutation == 0) bytes[0] = 0;
        if (mutation == 1) bytes[5] = 2;
        if (mutation == 2) bytes[7] = 11;
        if (mutation == 3) bytes[11] = 1;
        if (mutation == 4) u32(bytes + 12, 1);
        if (mutation == 5) u32(bytes + 20, 0);
        if (mutation == 6) u32(bytes + 20, 2);
        if (mutation == 7) bytes[24] ^= 1;
        if (mutation == 8) bytes[56] ^= 1;
        if (mutation == 9) length++;
        if (mutation == 10) { u32(bytes + 12, CTOS_SSH_CONTROL_PAYLOAD + 1); length = sizeof(bytes); }
        if (mutation == 11) count = 1;
        if (mutation >= 12 && mutation <= 18) {
            bytes[CTOS_SSH_CONTROL_HEADER] = 1; u32(bytes + CTOS_SSH_CONTROL_HEADER + 1, 7);
            memset(bytes + CTOS_SSH_CONTROL_HEADER + 5, 2, 32);
            count = mutation == 12 ? 0 : mutation == 13 ? 2 : mutation == 14 ? 12 : 1;
            if (mutation == 15) descriptors[0] = writer;
            if (mutation == 16) bytes[CTOS_SSH_CONTROL_HEADER] = 6;
            if (mutation == 17) u32(bytes + CTOS_SSH_CONTROL_HEADER + 1, 8);
            if (mutation == 18) {
                int pipefd[2]; CHECK(pipe2(pipefd, O_CLOEXEC) == 0); descriptors[0] = pipefd[0];
            }
        }
        if (mutation == 21) {
            raw_send(pair[1], bytes, length, NULL, 0);
            raw_send(pair[1], bytes, length, NULL, 0);
        } else raw_send(pair[1], bytes, length, descriptors, count);
        _exit(0);
    }
    pending_child = pid; CHECK(close(pair[1]) == 0);
    struct ctos_ssh_control_peer peer;
    CHECK(ctos_ssh_control_init(&peer, pair[0], mutation == 19 ? parent : pid, role, connection) == 0);
    if (mutation == 20) peer.uid++;
    struct ctos_ssh_control_frame frame = {.fd = -1};
    if (mutation == 21) {
        CHECK(ctos_ssh_control_receive(&peer, &frame, deadline(2000)) == 0);
        CHECK(ctos_ssh_control_frame_close(&frame) == 0);
    }
    unsigned int baseline = descriptor_count();
    CHECK(ctos_ssh_control_receive(&peer, &frame, deadline(2000)) != 0 && peer.fd == -1 && peer.failed && frame.fd == -1);
    CHECK(descriptor_count() + 1 == baseline);
    CHECK(fcntl(reader, F_GETFD) >= 0 && fcntl(writer, F_GETFD) >= 0);
    CHECK(ctos_ssh_control_close(&peer) == 0 && ctos_ssh_control_frame_close(&frame) == 0);
    wait_owned(pid); blob_close(writer, reader, mapping); cases++;
}

int main(int argc, char **argv) {
    if (argc != 2 || strcmp(argv[1], "--synthetic-control") != 0) return 2;
    struct sigaction defaults = {.sa_handler = SIG_DFL}; sigemptyset(&defaults.sa_mask);
    CHECK(sigaction(SIGCHLD, &defaults, NULL) == 0);
    unsigned char role[32], connection[32];
    CHECK(ctos_ssh_control_nonce(role) == 0 && ctos_ssh_control_nonce(connection) == 0 && memcmp(role, connection, 32) != 0);
    unsigned int baseline = descriptor_count();
    roundtrip(role, connection);
    for (unsigned int mutation = 0; mutation < 22; mutation++) malformed(mutation, role, connection);
    int pair[2]; CHECK(ctos_ssh_control_pair(pair) == 0);
    struct ctos_ssh_control_peer peer;
    CHECK(ctos_ssh_control_init(&peer, pair[0], getpid(), role, connection) == 0);
    struct ctos_ssh_control_frame frame = {.fd = -1};
    CHECK(ctos_ssh_control_receive(&peer, &frame, deadline(5)) != 0 && errno == ETIMEDOUT && peer.fd == -1);
    CHECK(close(pair[1]) == 0); cases++;
    CHECK(descriptor_count() == baseline && pending_child == 0);
    printf("SSH_CONTROL phase=transport state=pass errno=0 value=%u\n", cases);
    printf("SSH_CONTROL phase=cleanup state=pass errno=0 value=1\n");
    printf("SSH_CONTROL phase=gate state=pass errno=0 value=%u\n", cases);
    return 0;
}

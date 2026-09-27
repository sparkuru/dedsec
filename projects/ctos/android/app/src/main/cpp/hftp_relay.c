#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>
#ifdef __ANDROID__
#include <android/multinetwork.h>
#elif !defined(CTOS_RELAY_LOCAL_TEST)
#error "Android network binding is required outside explicit local tests"
#endif

#define CONNECTION_LIMIT 4
#define BUFFER_SIZE 65536
#define IDLE_MS 15000
#define HEARTBEAT_MS 10000
#define SESSION_MS (5LL * 60 * 60 * 1000)

typedef struct {
    unsigned char data[BUFFER_SIZE];
    size_t offset, length;
} Buffer;

typedef struct {
    int client, backend, connecting;
    int client_eof, backend_eof, client_shutdown, backend_shutdown;
    Buffer to_client, to_backend;
    int64_t activity;
    char peer[INET_ADDRSTRLEN];
} Connection;

static volatile sig_atomic_t interrupted;

static void interrupt_handler(int signal_number) {
    (void) signal_number;
    interrupted = 1;
}

static int64_t now_ms(void) {
    struct timespec value;
    if (clock_gettime(CLOCK_MONOTONIC, &value)) return -1;
    return (int64_t) value.tv_sec * 1000 + value.tv_nsec / 1000000;
}

static int nonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL);
    return flags < 0 ? -1 : fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

static int number(const char *text, uint64_t minimum, uint64_t maximum, uint64_t *result) {
    if (!text || !*text) return -1;
    for (const char *cursor = text; *cursor; ++cursor) {
        if (*cursor < '0' || *cursor > '9') return -1;
    }
    errno = 0;
    char *end;
    unsigned long long value = strtoull(text, &end, 10);
    if (errno || *end || value < minimum || value > maximum) return -1;
    *result = value;
    return 0;
}

static int permitted_peer(uint32_t peer, uint32_t mask, uint32_t subnet) {
    return (peer >> 24) != 0 && (peer >> 24) != 127 && (peer >> 28) < 14
        && (peer & mask) == subnet && peer != subnet && peer != (subnet | ~mask);
}

/* Records fit PIPE_BUF: a slow log reader cannot stall socket/control handling. */
static void event(const char *kind, const char *peer, const char *reason, int error) {
    char record[256];
    int length = snprintf(record, sizeof(record),
        "{\"event\":\"relay\",\"kind\":\"%s\",\"client\":\"%s\",\"reason\":\"%s\",\"errno\":%d}\n",
        kind, peer ? peer : "", reason ? reason : "", error);
    if (length > 0 && (size_t) length < sizeof(record)) {
        ssize_t written = write(STDOUT_FILENO, record, (size_t) length);
        if ((written < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR)
                || (written >= 0 && written != length)) interrupted = 1;
    }
}

static int fail(const char *reason) {
    int error = errno;
    char record[160];
    int length = snprintf(record, sizeof(record),
        "{\"state\":\"failed\",\"reason\":\"%s\",\"errno\":%d}\n", reason, error);
    if (length > 0 && (size_t) length < sizeof(record) && !nonblocking(STDOUT_FILENO)) {
        ssize_t written = write(STDOUT_FILENO, record, (size_t) length);
        (void) written;
    }
    fprintf(stderr, "ctOS HFTP relay: %s errno=%d\n", reason, error);
    return 1;
}

static int backend_socket(uint16_t port, int *connecting) {
    int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) return -1;
    struct sockaddr_in address = { .sin_family = AF_INET, .sin_port = htons(port) };
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    /* Loopback is intentionally not selected onto the physical Wi-Fi network. */
    if (connect(fd, (struct sockaddr *) &address, sizeof(address)) == 0) {
        *connecting = 0;
        return fd;
    }
    if (errno == EINPROGRESS) {
        *connecting = 1;
        return fd;
    }
    int saved = errno;
    close(fd);
    errno = saved;
    return -1;
}

static int connected(int fd) {
    int error = 0;
    socklen_t length = sizeof(error);
    if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &length)) return -1;
    if (error) { errno = error; return -1; }
    return 0;
}

static void close_connection(Connection *connection, const char *reason, int error) {
    event("closed", connection->peer, reason, error);
    close(connection->client);
    close(connection->backend);
    memset(connection, 0, sizeof(*connection));
    connection->client = connection->backend = -1;
}

static short interest(const Buffer *incoming, const Buffer *outgoing, int eof) {
    short events = 0;
    if (!eof && incoming->length < BUFFER_SIZE) events |= POLLIN;
    if (outgoing->length) events |= POLLOUT;
    return events;
}

static int receive_bytes(int fd, Buffer *buffer, int *eof, int64_t *activity) {
    if (*eof || buffer->length == BUFFER_SIZE) return 0;
    if (buffer->offset && buffer->offset + buffer->length == BUFFER_SIZE) {
        memmove(buffer->data, buffer->data + buffer->offset, buffer->length);
        buffer->offset = 0;
    }
    ssize_t amount = recv(fd, buffer->data + buffer->offset + buffer->length,
        BUFFER_SIZE - buffer->offset - buffer->length, 0);
    if (amount > 0) { buffer->length += (size_t) amount; *activity = now_ms(); }
    else if (amount == 0) *eof = 1;
    else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) return -1;
    return 0;
}

static int send_bytes(int fd, Buffer *buffer, int64_t *activity) {
    if (!buffer->length) return 0;
    ssize_t amount = send(fd, buffer->data + buffer->offset, buffer->length, MSG_NOSIGNAL);
    if (amount > 0) {
        buffer->offset += (size_t) amount;
        buffer->length -= (size_t) amount;
        if (!buffer->length) buffer->offset = 0;
        *activity = now_ms();
    } else if (amount == 0) { errno = EPIPE; return -1; }
    else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) return -1;
    return 0;
}

static int half_close(int fd, int eof, const Buffer *buffer, int *done) {
    if (eof && !buffer->length && !*done) {
        if (shutdown(fd, SHUT_WR)) return -1;
        *done = 1;
    }
    return 0;
}

int main(int argc, char **argv) {
    uint64_t prefix, port, backend_port, network, owner = 0;
    struct in_addr wifi;
    if ((argc != 6 && argc != 7) || inet_pton(AF_INET, argv[1], &wifi) != 1
            || number(argv[2], 1, 30, &prefix) || number(argv[3], 1024, 65535, &port)
            || number(argv[4], 1024, 65535, &backend_port)
            || number(argv[5], 1, UINT64_MAX, &network)
            || (argc == 7 && number(argv[6], 2, INT_MAX, &owner))) {
        errno = EINVAL;
        return fail("invalid_arguments");
    }
    uint32_t ip = ntohl(wifi.s_addr), mask = UINT32_MAX << (32 - prefix);
    uint32_t subnet = ip & mask, broadcast = subnet | ~mask;
    if ((ip >> 24) == 0 || (ip >> 24) == 127 || (ip >> 28) >= 14
            || ip == subnet || ip == broadcast) {
        errno = EINVAL;
        return fail("invalid_address");
    }
#ifdef __ANDROID__
    if (geteuid() != 0) { errno = EPERM; return fail("root_required"); }
#endif
    struct sigaction action = { .sa_handler = interrupt_handler };
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGTERM, &action, NULL) || sigaction(SIGINT, &action, NULL)) return fail("signal_setup");
    signal(SIGPIPE, SIG_IGN);
    if (nonblocking(STDIN_FILENO) || nonblocking(STDOUT_FILENO)) return fail("control_setup");
    int connecting = 0, probe = backend_socket((uint16_t) backend_port, &connecting);
    if (probe < 0) return fail("backend_unavailable");
    if (connecting) {
        struct pollfd probe_poll = { .fd = probe, .events = POLLOUT };
        int polled = poll(&probe_poll, 1, 2000);
        if (polled <= 0 || connected(probe)) {
            if (polled == 0) errno = ETIMEDOUT;
            int saved = errno;
            close(probe);
            errno = saved;
            return fail("backend_unavailable");
        }
    }
    close(probe);
    int listener = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (listener < 0) return fail("listener_socket");
    /* Reuse retired TCP connections, while an active listener remains exclusive. */
    int reuse_address = 1;
    if (setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, &reuse_address, sizeof(reuse_address))) {
        int saved = errno;
        close(listener);
        errno = saved;
        return fail("listener_reuse");
    }
#ifdef __ANDROID__
    if (android_setsocknetwork((net_handle_t) network, listener)) {
        int saved = errno;
        close(listener);
        errno = saved;
        return fail("network_binding");
    }
#else
    (void) network;
    fprintf(stderr, "ctOS HFTP relay: local_test_no_android_binding errno=0\n");
#endif
    struct sockaddr_in address = { .sin_family = AF_INET, .sin_port = htons((uint16_t) port), .sin_addr = wifi };
    if (bind(listener, (struct sockaddr *) &address, sizeof(address)) || listen(listener, CONNECTION_LIMIT)) {
        int saved = errno;
        close(listener);
        errno = saved;
        return fail("listener_bind");
    }
    char ready[256];
    int ready_length = snprintf(ready, sizeof(ready),
        "{\"state\":\"ready\",\"host\":\"%s\",\"port\":%" PRIu64 ",\"backendPort\":%" PRIu64 ",\"pid\":%ld,\"uid\":%lu}\n",
        argv[1], port, backend_port, (long) getpid(), (unsigned long) geteuid());
    struct pollfd stdout_poll = { .fd = STDOUT_FILENO, .events = POLLOUT };
    if (poll(&stdout_poll, 1, 1000) <= 0
            || write(STDOUT_FILENO, ready, (size_t) ready_length) != ready_length) {
        close(listener);
        return fail("ready_output");
    }
    Connection connections[CONNECTION_LIMIT];
    memset(connections, 0, sizeof(connections));
    for (int slot = 0; slot < CONNECTION_LIMIT; ++slot) connections[slot].client = connections[slot].backend = -1;
    int64_t started = now_ms(), heartbeat = started;
    char command[8];
    size_t command_length = 0;
    const char *stop_reason = "signal";
    while (!interrupted) {
        int64_t now = now_ms();
        if (now < 0) { stop_reason = "clock_failed"; break; }
        if (now - started >= SESSION_MS) { stop_reason = "session_timeout"; break; }
        if (owner && kill((pid_t) owner, 0) && errno == ESRCH) { stop_reason = "owner_gone"; break; }
        struct pollfd polls[2 + 2 * CONNECTION_LIMIT];
        int indexes[CONNECTION_LIMIT];
        polls[0] = (struct pollfd) { .fd = STDIN_FILENO, .events = POLLIN };
        polls[1] = (struct pollfd) { .fd = listener, .events = POLLIN };
        nfds_t count = 2;
        for (int slot = 0; slot < CONNECTION_LIMIT; ++slot) {
            Connection *connection = &connections[slot];
            indexes[slot] = -1;
            if (connection->client < 0) continue;
            if (now - connection->activity >= IDLE_MS) {
                close_connection(connection, "idle_timeout", 0);
                continue;
            }
            indexes[slot] = (int) count;
            short client_interest = interest(&connection->to_backend, &connection->to_client, connection->client_eof);
            polls[count++] = (struct pollfd) { .fd = client_interest ? connection->client : -1,
                .events = client_interest };
            short backend_interest = connection->connecting ? POLLOUT
                : interest(&connection->to_client, &connection->to_backend, connection->backend_eof);
            polls[count++] = (struct pollfd) { .fd = backend_interest ? connection->backend : -1,
                .events = backend_interest };
        }
        int result = poll(polls, count, 250);
        if (result < 0) {
            if (errno == EINTR) continue;
            stop_reason = "poll_failed";
            break;
        }
        if (polls[0].revents & (POLLIN | POLLHUP | POLLERR | POLLNVAL)) {
            char input[64];
            ssize_t amount = read(STDIN_FILENO, input, sizeof(input));
            if (amount == 0) { stop_reason = "control_eof"; break; }
            if (amount < 0 && errno != EINTR && errno != EAGAIN) { stop_reason = "control_failed"; break; }
            for (ssize_t position = 0; position < amount; ++position) {
                if (input[position] == '\n') {
                    command[command_length] = '\0';
                    if (!strcmp(command, "PING")) heartbeat = now_ms();
                    else if (!strcmp(command, "STOP")) { stop_reason = "requested"; interrupted = 1; }
                    else { stop_reason = "invalid_control"; interrupted = 1; }
                    command_length = 0;
                } else if (command_length >= sizeof(command) - 1) {
                    stop_reason = "invalid_control";
                    interrupted = 1;
                } else command[command_length++] = input[position];
                if (interrupted) break;
            }
            if (interrupted) break;
        }
        /* A scheduled owner may have queued control while this process paused. */
        now = now_ms();
        if (now < 0) { stop_reason = "clock_failed"; break; }
        if (now - heartbeat >= HEARTBEAT_MS) { stop_reason = "heartbeat_timeout"; break; }
        if (polls[1].revents & (POLLERR | POLLHUP | POLLNVAL)) { stop_reason = "listener_failed"; break; }
        if (polls[1].revents & POLLIN) {
            for (int accepted = 0; accepted < 8; ++accepted) {
                struct sockaddr_in peer;
                socklen_t size = sizeof(peer);
                int client = accept4(listener, (struct sockaddr *) &peer, &size, SOCK_CLOEXEC | SOCK_NONBLOCK);
                if (client < 0) {
                    if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) event("error", NULL, "accept_failed", errno);
                    break;
                }
                char peer_text[INET_ADDRSTRLEN] = "";
                inet_ntop(AF_INET, &peer.sin_addr, peer_text, sizeof(peer_text));
                uint32_t peer_ip = ntohl(peer.sin_addr.s_addr);
                int slot = 0;
                while (slot < CONNECTION_LIMIT && connections[slot].client >= 0) ++slot;
                const char *rejection = NULL;
                if (peer.sin_family != AF_INET || !permitted_peer(peer_ip, mask, subnet)) rejection = "outside_subnet";
                else if (slot == CONNECTION_LIMIT) rejection = "concurrency_limit";
                if (rejection) { event("rejected", peer_text, rejection, 0); close(client); continue; }
                Connection *connection = &connections[slot];
                connection->backend = backend_socket((uint16_t) backend_port, &connection->connecting);
                if (connection->backend < 0) {
                    event("error", peer_text, "backend_connect", errno);
                    close(client);
                    continue;
                }
                connection->client = client;
                connection->activity = now_ms();
                memcpy(connection->peer, peer_text, sizeof(peer_text));
                event("accepted", peer_text, "", 0);
            }
        }
        for (int slot = 0; slot < CONNECTION_LIMIT; ++slot) {
            Connection *connection = &connections[slot];
            int index = indexes[slot];
            if (connection->client < 0 || index < 0) continue;
            short client_events = polls[index].revents, backend_events = polls[index + 1].revents;
            if ((client_events | backend_events) & POLLNVAL) {
                close_connection(connection, "invalid_socket", EBADF);
                continue;
            }
            if (connection->connecting) {
                if (!(backend_events & (POLLOUT | POLLERR | POLLHUP))) continue;
                if (connected(connection->backend)) { close_connection(connection, "backend_connect", errno); continue; }
                connection->connecting = 0;
            }
            if (((client_events & POLLERR) && connected(connection->client))
                    || ((backend_events & POLLERR) && connected(connection->backend))) {
                close_connection(connection, "socket_error", errno);
                continue;
            }
            int failed = 0;
            if (client_events & (POLLIN | POLLHUP | POLLERR))
                failed = receive_bytes(connection->client, &connection->to_backend, &connection->client_eof, &connection->activity);
            if (!failed && backend_events & (POLLIN | POLLHUP | POLLERR))
                failed = receive_bytes(connection->backend, &connection->to_client, &connection->backend_eof, &connection->activity);
            if (!failed && client_events & (POLLOUT | POLLHUP)) failed = send_bytes(connection->client, &connection->to_client, &connection->activity);
            if (!failed && backend_events & (POLLOUT | POLLHUP)) failed = send_bytes(connection->backend, &connection->to_backend, &connection->activity);
            if (!failed) failed = half_close(connection->backend, connection->client_eof, &connection->to_backend, &connection->backend_shutdown);
            if (!failed) failed = half_close(connection->client, connection->backend_eof, &connection->to_client, &connection->client_shutdown);
            if (failed) close_connection(connection, "socket_io", errno);
            else if (connection->client_eof && connection->backend_eof
                    && !connection->to_client.length && !connection->to_backend.length)
                close_connection(connection, "complete", 0);
        }
    }
    close(listener);
    for (int slot = 0; slot < CONNECTION_LIMIT; ++slot) {
        if (connections[slot].client >= 0) close_connection(&connections[slot], "service_stop", 0);
    }
    event("stopped", NULL, stop_reason, 0);
    return 0;
}

/* Test-only TCP driver. The included matrix is never executed here. */
#define main ctos_native_matrix_unused_main
#include "ssh_native_client_test.c"
#undef main

#include <arpa/inet.h>
#include <poll.h>
#include <signal.h>
#include "log.h"
#ifdef CTOS_INTEROP_PRIVATE_KEY
#include "ssh_material_inspect.h"
#define INTEROP_LABEL "SSH_KEY_INTEROP"
#define INTEROP_INPUT_BYTES CTOS_SSH_MATERIAL_BYTES
#else
#define INTEROP_LABEL "SSH_PASSWORD_INTEROP"
#define INTEROP_INPUT_BYTES CTOS_SSH_NATIVE_CLIENT_MAX_PASSWORD_BYTES
#endif

static volatile sig_atomic_t interop_cancelled;

static void interop_cancel(int signal_number) {
    (void)signal_number;
    interop_cancelled = 1;
}

static void interop_wipe(void *memory, size_t length) {
    volatile unsigned char *bytes = memory;
    while (length-- != 0) *bytes++ = 0;
}

static int interop_live(uint64_t deadline) {
    if (interop_cancelled) { errno = ECANCELED; return 0; }
    if (deadline_after(0) >= deadline) { errno = ETIMEDOUT; return 0; }
    return 1;
}

/* No TOFU path: only the actual preconfigured key may reach the shared checker. */
static int interop_host_evidence(void *context, void *environment,
    const struct ctos_ssh_host_evidence *evidence,
    struct ctos_ssh_host_result *result) {
    if (evidence == NULL || evidence->classification != CTOS_SSH_CALLBACK_HOST_KNOWN)
        return EACCES;
    return host_evidence(context, environment, evidence, result);
}

static int interop_password(unsigned char *password, size_t *length) {
    unsigned char input[1024];
    uint64_t deadline = deadline_after(30000U);
    *length = 0;
    while (interop_live(deadline)) {
        struct pollfd descriptor = {.fd = STDIN_FILENO, .events = POLLIN};
        int ready = poll(&descriptor, 1, 20);
        if (ready < 0) { if (errno == EINTR) continue; break; }
        if (ready == 0) continue;
        ssize_t count = read(STDIN_FILENO, input, sizeof(input));
        if (count < 0) { if (errno == EINTR) continue; break; }
        if (count == 0) { interop_wipe(input, sizeof(input)); return 0; }
        if ((size_t)count > INTEROP_INPUT_BYTES - *length
            || memchr(input, 0, (size_t)count) != NULL) { errno = EINVAL; break; }
        memcpy(password + *length, input, (size_t)count);
        *length += (size_t)count;
        interop_wipe(input, sizeof(input));
    }
    interop_wipe(input, sizeof(input));
    return -1;
}

static struct sshkey *interop_public_key(const char *path) {
    unsigned char body[16384];
    struct sshkey *key = NULL;
    FILE *stream = fopen(path, "rb");
    size_t length;
    int invalid;
    char *cursor;
    if (stream == NULL) return NULL;
    length = fread(body, 1, sizeof(body), stream);
    invalid = ferror(stream) || length == 0 || length == sizeof(body)
        || memchr(body, 0, length) != NULL;
    if (fclose(stream) != 0) invalid = 1;
    if (invalid) { errno = EINVAL; return NULL; }
    body[length] = 0;
    /* Accept one public-key line, with an optional trailing line terminator. */
    unsigned char *newline = memchr(body, '\n', length);
    if (newline != NULL && newline != body + length - 1U) { errno = EINVAL; return NULL; }
    key = sshkey_new(KEY_UNSPEC);
    cursor = (char *)body;
    if (key == NULL || sshkey_read(key, &cursor) != 0 || sshkey_is_cert(key)) {
        sshkey_free(key);
        errno = EINVAL;
        return NULL;
    }
    return key;
}

static int interop_connect(const struct sockaddr_in *address, uint64_t deadline) {
    int descriptor = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (descriptor < 0) return -1;
    if (connect(descriptor, (const struct sockaddr *)address, sizeof(*address)) != 0) {
        if (errno != EINPROGRESS) goto fail;
        while (interop_live(deadline)) {
            struct pollfd pending = {.fd = descriptor, .events = POLLOUT};
            int ready = poll(&pending, 1, 20), error = 0;
            socklen_t size = sizeof(error);
            if (ready < 0) { if (errno == EINTR) continue; goto fail; }
            if (ready == 0) continue;
            if (getsockopt(descriptor, SOL_SOCKET, SO_ERROR, &error, &size) != 0) goto fail;
            if (error != 0) { errno = error; goto fail; }
            break;
        }
        if (!interop_live(deadline)) goto fail;
    }
    struct sockaddr_in peer = {0};
    socklen_t size = sizeof(peer);
    if (getpeername(descriptor, (struct sockaddr *)&peer, &size) != 0) goto fail;
    if (size != sizeof(peer) || peer.sin_family != AF_INET
        || peer.sin_addr.s_addr != address->sin_addr.s_addr || peer.sin_port != address->sin_port) {
        errno = EACCES;
        goto fail;
    }
    return descriptor;
fail:
    {
        int saved = errno;
        close(descriptor);
        errno = saved;
        return -1;
    }
}

static int interop_turn(struct ctos_ssh_native_client *client, uint64_t deadline) {
    struct timespec pause = {.tv_nsec = 1000000L};
    if (!interop_live(deadline)) return -1;
    if (ctos_ssh_native_client_step(client, &(struct ctos_ssh_native_client_step){0}) != 0)
        return -1;
    if (nanosleep(&pause, NULL) != 0 && errno != EINTR) return -1;
    return 0;
}

enum interop_wait { INTEROP_KEX, INTEROP_SERVICE, INTEROP_PASSWORD, INTEROP_SHELL, INTEROP_CLOSED };

static int interop_wait_for(struct ctos_ssh_native_client *client,
    enum interop_wait phase, uint64_t deadline) {
    while (interop_live(deadline)) {
        int ready = 0;
        if (phase == INTEROP_KEX) ready = ctos_ssh_native_client_complete(client);
        if (phase == INTEROP_SERVICE) ready = ctos_ssh_native_client_userauth_service_ready(client);
        if (phase == INTEROP_PASSWORD) {
#ifdef CTOS_INTEROP_PRIVATE_KEY
            enum ctos_ssh_native_client_private_key_auth_result status;
            if (ctos_ssh_native_client_private_key_auth_result(client, &status) != 0) return -1;
            if (status != CTOS_SSH_PRIVATE_KEY_AUTH_PENDING && status != CTOS_SSH_PRIVATE_KEY_AUTH_SUCCESS) {
                errno = EACCES; return -1;
            }
            ready = status == CTOS_SSH_PRIVATE_KEY_AUTH_SUCCESS;
#else
            enum ctos_ssh_native_client_password_auth_result status;
            if (ctos_ssh_native_client_password_auth_result(client, &status) != 0) return -1;
            if (status != CTOS_SSH_PASSWORD_AUTH_PENDING && status != CTOS_SSH_PASSWORD_AUTH_SUCCESS) {
                errno = EACCES; return -1;
            }
            ready = status == CTOS_SSH_PASSWORD_AUTH_SUCCESS;
#endif
        }
        if (phase == INTEROP_SHELL || phase == INTEROP_CLOSED) {
            struct ctos_ssh_native_shell_status status;
            if (ctos_ssh_native_client_shell_status(client, &status) != 0) return -1;
            if (status.phase == CTOS_SSH_SHELL_REJECTED) { errno = EACCES; return -1; }
            ready = status.phase == (phase == INTEROP_SHELL ? CTOS_SSH_SHELL_RUNNING : CTOS_SSH_SHELL_CLOSED);
            if (phase == INTEROP_CLOSED) {
                unsigned char discarded[4096];
                size_t length;
                if (ctos_ssh_native_client_shell_read(client, discarded, sizeof(discarded), &length) != 0)
                    return -1;
            }
        }
        if (ready < 0) return -1;
        if (ready) return 0;
        if (interop_turn(client, deadline) != 0) return -1;
    }
    return -1;
}

static int interop_send(struct ctos_ssh_native_client *client, const char *text, uint64_t deadline) {
    size_t sent = 0, length = strlen(text);
    while (sent < length && interop_live(deadline)) {
        size_t accepted = 0;
        if (ctos_ssh_native_client_shell_write(client, (const unsigned char *)text + sent,
                length - sent, &accepted) != 0) return -1;
        sent += accepted;
        if (sent < length && interop_turn(client, deadline) != 0) return -1;
    }
    return sent == length ? 0 : -1;
}

static int interop_expect(struct ctos_ssh_native_client *client, const char *pattern, uint64_t deadline) {
    unsigned char buffer[8192];
    size_t retained = 0, total = 0, pattern_length = strlen(pattern);
    if (pattern_length == 0 || pattern_length > 128U) { errno = EINVAL; return -1; }
    while (interop_live(deadline) && total < 1024U * 1024U) {
        size_t length = 0;
        struct ctos_ssh_native_shell_status status;
        if (interop_turn(client, deadline) != 0
            || ctos_ssh_native_client_shell_read(client, buffer + retained, 4096U, &length) != 0)
            return -1;
        total += length;
        size_t available = retained + length;
        if (memmem(buffer, available, pattern, pattern_length) != NULL) return 0;
        retained = available < pattern_length ? available : pattern_length - 1U;
        memmove(buffer, buffer + available - retained, retained);
        if (ctos_ssh_native_client_shell_status(client, &status) != 0) return -1;
        if (status.phase == CTOS_SSH_SHELL_CLOSED) { errno = EPIPE; return -1; }
    }
    if (total >= 1024U * 1024U) errno = EOVERFLOW;
    return -1;
}

static int interop_marker(struct ctos_ssh_native_client *client, const char *marker, uint64_t deadline) {
    char command[1024] = "printf '", expected[128];
    size_t used = strlen(command), length = strlen(marker);
    if (length > 100U) { errno = EINVAL; return -1; }
    for (size_t index = 0; index < length; index++)
        used += (size_t)snprintf(command + used, sizeof(command) - used, "\\%03o", (unsigned char)marker[index]);
    snprintf(command + used, sizeof(command) - used, "\\012'\n");
    snprintf(expected, sizeof(expected), "%s\r\n", marker);
    /* The literal expected output is absent from the echoed command. */
    if (interop_send(client, command, deadline) != 0) return -1;
    return interop_expect(client, expected, deadline);
}

static int interop_number(const char *text, unsigned long maximum, unsigned long *value) {
    char *end;
    if (*text < '0' || *text > '9') return -1;
    errno = 0;
    *value = strtoul(text, &end, 10);
    return errno == 0 && *end == 0 && *value <= maximum ? 0 : -1;
}

int main(int argc, char **argv) {
    struct fixture fixture = {0};
    unsigned char password[INTEROP_INPUT_BYTES] = {0}, known_hosts[8192];
#ifdef CTOS_INTEROP_PRIVATE_KEY
    unsigned char *public_wire = NULL;
    size_t public_wire_length = 0;
#endif
    size_t password_length = 0;
    unsigned long port = 0, hold_seconds = 0;
    struct sockaddr_in address = {.sin_family = AF_INET};
    const char *phase = "arguments";
    int descriptor = -1, opened = 0, result = 1, saved_error = 0;
    FILE *known_stream = NULL;
    fixture.callback.release_fd = -1;
    setvbuf(stdout, NULL, _IOLBF, 0);
    signal(SIGINT, interop_cancel);
    signal(SIGTERM, interop_cancel);
    signal(SIGPIPE, SIG_IGN);
    /* Isolated test driver only: diagnose negotiation without packet payload dumps. */
    log_init(INTEROP_LABEL, SYSLOG_LEVEL_DEBUG3, SYSLOG_FACILITY_AUTH, 1);
#define INTEROP_REQUIRE(condition) do { if (!(condition)) goto done; } while (0)
    if ((argc != 5 && argc != 7) || interop_number(argv[2], 65535U, &port) != 0 || port == 0
        || (argc == 7 && (strcmp(argv[5], "--hold-seconds") != 0
            || interop_number(argv[6], 120U, &hold_seconds) != 0))
        || inet_pton(AF_INET, argv[1], &address.sin_addr) != 1
        || (ntohl(address.sin_addr.s_addr) >> 24) != 127U
        || strlen(argv[3]) == 0 || strlen(argv[3]) > 255U) {
        fprintf(stderr, "Usage: %s <loopback-ipv4> <port> <username> <server-public-key> [--hold-seconds 0..120]\nAuthentication input: exact bytes on stdin followed by EOF.\n", argv[0]);
        errno = EINVAL;
        goto done;
    }
    address.sin_port = htons((uint16_t)port);
    phase = "password-input";
    INTEROP_REQUIRE(interop_password(password, &password_length) == 0);
#ifdef CTOS_INTEROP_PRIVATE_KEY
    phase = "actual-private-public-proof";
    INTEROP_REQUIRE(ctos_ssh_material_export_public(password, password_length,
        deadline_after(30000U), &public_wire, &public_wire_length) == 0);
    INTEROP_REQUIRE(public_wire != NULL && public_wire_length > CTOS_SSH_KEY_EXPORT_HEADER_BYTES
        && public_wire[8] == 0 && public_wire[9] == 0 && public_wire[10] == 0 && public_wire[11] == 0);
#endif
    phase = "public-key";
    fixture.host_key = interop_public_key(argv[4]);
    INTEROP_REQUIRE(fixture.host_key != NULL);
    known_stream = fmemopen(known_hosts, sizeof(known_hosts), "w+");
    INTEROP_REQUIRE(known_stream != NULL);
    char host_pattern[300];
    if (port == 22U) snprintf(host_pattern, sizeof(host_pattern), "example.invalid");
    else snprintf(host_pattern, sizeof(host_pattern), "[example.invalid]:%lu", port);
    INTEROP_REQUIRE(ctos_hostfile_write_entry(known_stream, host_pattern, NULL, fixture.host_key, 0) == 1);
    INTEROP_REQUIRE(fflush(known_stream) == 0);
    long known_length = ftell(known_stream);
    INTEROP_REQUIRE(known_length > 0 && (size_t)known_length < sizeof(known_hosts));
    int stream_close = fclose(known_stream);
    known_stream = NULL;
    INTEROP_REQUIRE(stream_close == 0);
    fixture.deadline = deadline_after(60000U);
#ifdef CTOS_INTEROP_PRIVATE_KEY
    fixture.view = load_view_paths(known_hosts, (size_t)known_length, fixture.deadline,
        ".ssh/config", ".ssh/known_hosts");
#else
    fixture.view = load_view(known_hosts, (size_t)known_length, fixture.deadline);
#endif
    INTEROP_REQUIRE(ctos_ssh_view_borrow_owned_view(fixture.view, &fixture.view_borrow) == 0);
    struct ctos_ssh_role_request request = role_request(fixture.deadline);
    /* example.invalid is mapped only here to the explicitly supplied loopback peer.
     * target_identity is an opaque fixture ID; request.port is the port authority. */
    request.port = (unsigned int)port;
    INTEROP_REQUIRE(ctos_ssh_role_bootstrap_open(&request, &fixture.role) == 0);
    fixture.callback.environment = &fixture.callback;
    fixture.callback.expected_role = fixture.role;
    fixture.callback.expected_port = (unsigned int)port;
#ifdef CTOS_INTEROP_PRIVATE_KEY
    fixture.callback.selected_kind = CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY;
#else
    fixture.callback.selected_kind = CTOS_SSH_CALLBACK_CREDENTIAL_PASSWORD;
#endif
    fixture.callback.selected_body_override = 1;
    fixture.callback.selected_body = password;
    fixture.callback.selected_body_length = password_length;
    remember_expected_host_evidence(&fixture.callback, fixture.host_key);
    struct ctos_ssh_callback_target callbacks = {
        .context = &fixture.callback, .validate_environment = validate_environment,
        .host_evidence = interop_host_evidence, .kex_complete = kex_complete,
        .credential = credential, .credential_bytes = credential_bytes,
        .credential_bytes_selected = credential_bytes_selected, .release = release
    };
    INTEROP_REQUIRE(ctos_ssh_callback_open(&callbacks, fixture.role, (int)getuid(), fixture.deadline,
        fixture.callback.environment, &fixture.callback_token) == 0);
    struct ctos_ssh_role_snapshot snapshot;
    INTEROP_REQUIRE(ctos_ssh_role_bootstrap_snapshot(fixture.role, &snapshot) == 0);
    struct ctos_ssh_slice fields[5] = {
        {(const unsigned char *)argv[3], strlen(argv[3])},
#ifdef CTOS_INTEROP_PRIVATE_KEY
        {NULL, 0},
        {(const unsigned char *)".ssh/interop-key", sizeof(".ssh/interop-key") - 1U},
        {(const unsigned char *)"interop-unencrypted", sizeof("interop-unencrypted") - 1U},
        {public_wire + CTOS_SSH_KEY_EXPORT_HEADER_BYTES, public_wire_length - CTOS_SSH_KEY_EXPORT_HEADER_BYTES}
#else
        {(const unsigned char *)"interop-once", 12U}, {NULL, 0}, {NULL, 0}, {NULL, 0}
#endif
    };
#ifdef CTOS_INTEROP_PRIVATE_KEY
    make_selection_wire(&fixture.selection_wire, &snapshot, 4U, fields);
#else
    make_selection_wire(&fixture.selection_wire, &snapshot, 1U, fields);
#endif
    INTEROP_REQUIRE(ssh_digest_memory(SSH_DIGEST_SHA256, fixture.selection_wire.bytes,
        fixture.selection_wire.length, fixture.callback.expected_selection_receipt, 32U) == 0);
    phase = "tcp-connect";
    descriptor = interop_connect(&address, fixture.deadline);
    INTEROP_REQUIRE(descriptor >= 0);
    fixture.callback.release_fd = descriptor;
    struct ctos_ssh_native_client_config config = {
        .role = fixture.role, .socket_fd = descriptor, .owner_uid = getuid(), .owns_socket = 1,
        .callback = fixture.callback_token, .callback_environment = fixture.callback.environment,
        .deadline = fixture.deadline, .step_budget = CTOS_SSH_NATIVE_CLIENT_MAX_STEP_BYTES,
        .host_key_mode = CTOS_SSH_HOST_KEY_MANAGED, .managed_view_borrow = fixture.view_borrow,
#ifdef CTOS_INTEROP_PRIVATE_KEY
        .known_hosts_path = ".ssh/known_hosts",
#else
        .known_hosts_path = "ssh/known_hosts",
#endif
        .host = "example.invalid", .candidate_id = "interop-unused",
        .authentication_selection_wire = fixture.selection_wire.bytes,
        .authentication_selection_wire_length = fixture.selection_wire.length
    };
    phase = "native-open";
    INTEROP_REQUIRE(ctos_ssh_native_client_open(&config, &fixture.client) == 0);
    opened = 1;
    phase = "signed-kex-known-host";
    INTEROP_REQUIRE(interop_wait_for(fixture.client, INTEROP_KEX, fixture.deadline) == 0);
    INTEROP_REQUIRE(fixture.callback.host_evidence_calls == 1U && fixture.callback.kex_complete_calls == 1U
        && fixture.callback.last_host_classification == CTOS_SSH_CALLBACK_HOST_KNOWN);
    printf(INTEROP_LABEL " phase=kex status=pass trust=managed-known\n");
    log_change_level(SYSLOG_LEVEL_QUIET);
    phase = "userauth-service";
    INTEROP_REQUIRE(ctos_ssh_native_client_begin_userauth_service(fixture.client) == 0);
    INTEROP_REQUIRE(interop_wait_for(fixture.client, INTEROP_SERVICE, fixture.deadline) == 0);
    phase = "selected-auth";
#ifdef CTOS_INTEROP_PRIVATE_KEY
    INTEROP_REQUIRE(ctos_ssh_native_client_begin_private_key_auth(fixture.client) == 0);
#else
    INTEROP_REQUIRE(ctos_ssh_native_client_begin_password_auth(fixture.client) == 0);
#endif
    interop_wipe(password, sizeof(password));
    INTEROP_REQUIRE(interop_wait_for(fixture.client, INTEROP_PASSWORD, fixture.deadline) == 0);
    INTEROP_REQUIRE(fixture.callback.selected_calls == 1U && fixture.callback.credential_calls == 0U
        && fixture.callback.credential_bytes_calls == 0U);
#ifdef CTOS_INTEROP_PRIVATE_KEY
    printf(INTEROP_LABEL " phase=publickey-auth status=success selected_calls=1\n");
#else
    printf(INTEROP_LABEL " phase=password-auth status=success selected_calls=1\n");
#endif
    phase = "pty-shell";
    INTEROP_REQUIRE(ctos_ssh_native_client_begin_shell(fixture.client, 80U, 24U) == 0);
    uint64_t session_deadline = deadline_after((unsigned int)hold_seconds * 1000U + 30000U);
    INTEROP_REQUIRE(interop_wait_for(fixture.client, INTEROP_SHELL, session_deadline) == 0);
    phase = "command-output";
    INTEROP_REQUIRE(interop_marker(fixture.client, "CTOS_INTEROP_ONE", session_deadline) == 0);
    printf(INTEROP_LABEL " phase=pty-command status=pass\n");
    phase = "resize";
    int resized;
    do {
        resized = ctos_ssh_native_client_shell_resize(fixture.client, 91U, 37U);
        INTEROP_REQUIRE(resized >= 0);
        if (!resized) INTEROP_REQUIRE(interop_turn(fixture.client, session_deadline) == 0);
    } while (!resized);
    INTEROP_REQUIRE(interop_send(fixture.client, "stty size\n", session_deadline) == 0);
    INTEROP_REQUIRE(interop_expect(fixture.client, "\r\n37 91\r\n", session_deadline) == 0);
    printf(INTEROP_LABEL " phase=resize status=pass rows=37 columns=91\n");
    if (hold_seconds != 0U) {
        phase = "post-auth-hold";
        printf(INTEROP_LABEL " phase=hold status=running seconds=%lu\n", hold_seconds);
        uint64_t hold_end = deadline_after((unsigned int)hold_seconds * 1000U);
        while (deadline_after(0) < hold_end)
            INTEROP_REQUIRE(interop_turn(fixture.client, session_deadline) == 0);
        if (deadline_after(0) >= fixture.deadline) {
            INTEROP_REQUIRE(ctos_ssh_role_bootstrap_snapshot(fixture.role, &snapshot) != 0 && errno == ETIMEDOUT);
            INTEROP_REQUIRE(ctos_ssh_view_borrow_live(fixture.view_borrow) != 0);
            printf(INTEROP_LABEL " phase=auth-authority status=expired\n");
        }
        INTEROP_REQUIRE(interop_marker(fixture.client, "CTOS_INTEROP_AFTER_HOLD", session_deadline) == 0);
        INTEROP_REQUIRE(fixture.callback.selected_calls == 1U);
        printf(INTEROP_LABEL " phase=post-auth-traffic status=pass\n");
    }
    phase = "exit-eof-close";
    INTEROP_REQUIRE(interop_send(fixture.client, "exit 7\n", session_deadline) == 0);
    int eof;
    do {
        eof = ctos_ssh_native_client_shell_eof(fixture.client);
        INTEROP_REQUIRE(eof >= 0);
        if (!eof) INTEROP_REQUIRE(interop_turn(fixture.client, session_deadline) == 0);
    } while (!eof);
    INTEROP_REQUIRE(interop_wait_for(fixture.client, INTEROP_CLOSED, session_deadline) == 0);
    struct ctos_ssh_native_shell_status final_status;
    INTEROP_REQUIRE(ctos_ssh_native_client_shell_status(fixture.client, &final_status) == 0);
    INTEROP_REQUIRE(final_status.remote_eof && final_status.local_eof
        && final_status.exit_status_known && final_status.exit_status == 7U && !final_status.exit_signal);
    printf(INTEROP_LABEL " phase=exit-eof-close status=pass exit_status=7\n");
    result = 0;
done:
    saved_error = errno;
    interop_wipe(password, sizeof(password));
#ifdef CTOS_INTEROP_PRIVATE_KEY
    if (public_wire != NULL) { interop_wipe(public_wire, public_wire_length); free(public_wire); }
#endif
    if (known_stream != NULL && fclose(known_stream) != 0) result = 1;
    if (opened) {
        if (ctos_ssh_native_client_close(fixture.client) != 0) result = 1;
        else {
            if (ctos_ssh_native_client_destroy(fixture.client) != 0) result = 1;
            fixture.client = NULL;
            fixture.view_borrow = NULL;
            descriptor = -1;
            fixture.callback_token = 0;
        }
    } else {
        if (fixture.callback_token != 0 && ctos_ssh_callback_close(fixture.callback_token,
                fixture.callback.environment) != 0) result = 1;
        if (descriptor >= 0 && close(descriptor) != 0) result = 1;
        if (fixture.view_borrow != NULL && ctos_ssh_view_borrow_release(fixture.view_borrow) != 0) result = 1;
        fixture.view_borrow = NULL;
    }
    if (fixture.role != 0 && ctos_ssh_role_bootstrap_close(fixture.role) != 0 && errno != ENOENT) result = 1;
    if (fixture.view != NULL && fixture.view_borrow == NULL) destroy_view(fixture.view);
    sshkey_free(fixture.host_key);
    if (opened && (fixture.callback.release_calls != 1U || !fixture.callback.release_saw_fd_open)) result = 1;
    if (result != 0) fprintf(stderr, INTEROP_LABEL " phase=%s status=fail errno=%d\n", phase, saved_error);
    else printf(INTEROP_LABEL " gate=pass scope=c-engine-independent-sshd android_ui=not-tested cleanup=pass\n");
    return result;
#undef INTEROP_REQUIRE
}

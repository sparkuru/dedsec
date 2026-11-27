#define _GNU_SOURCE
#include "ssh_callback_adapter.h"

static int effect_callback_open(const struct ctos_ssh_callback_target *,
    ctos_ssh_role_token, int, uint64_t, void *, ctos_ssh_callback_token *);
#define ctos_ssh_callback_open effect_callback_open
#define main fixture_unused_main
#include "ssh_native_client_test.c"
#undef main
#undef ctos_ssh_callback_open

/* The linker wrapper observes packet construction in the synchronous call,
 * including packets still buffered inside OpenSSH. */
static int trace_auth_packets;
static unsigned int auth_packet_starts;
static unsigned int auth_signatures;
int __real_sshpkt_start(struct ssh *, u_char);
int __wrap_sshpkt_start(struct ssh *ssh, u_char type) {
    if (trace_auth_packets && (type == SSH2_MSG_SERVICE_REQUEST
            || type == SSH2_MSG_USERAUTH_REQUEST || type == SSH2_MSG_USERAUTH_INFO_RESPONSE))
        auth_packet_starts++;
    return __real_sshpkt_start(ssh, type);
}

static struct fixture *inject_eof_during_callback;
static struct fixture *inject_eof_after_parse, *inject_eof_after_sign;

static void observe_peer_eof(struct fixture *parent, int close_channel) {
    peer_channel_packet(parent, SSH2_MSG_CHANNEL_EOF);
    CHECK(sshpkt_send(parent->server) == 0);
    if (close_channel) {
        peer_channel_packet(parent, SSH2_MSG_CHANNEL_CLOSE);
        CHECK(sshpkt_send(parent->server) == 0);
    }
    for (unsigned int turn = 0; turn < 200000U; turn++) {
        CHECK(shell_turn(parent) == 0);
        struct ctos_ssh_native_jump_status status = jump_status(parent);
        if (close_channel ? status.phase == CTOS_SSH_JUMP_CLOSED : status.remote_eof) return;
    }
    CHECK(0);
}

int __real_sshkey_parse_private_fileblob(struct sshbuf *, const char *, struct sshkey **, char **);
int __wrap_sshkey_parse_private_fileblob(struct sshbuf *buffer, const char *passphrase,
    struct sshkey **key, char **comment) {
    int result = __real_sshkey_parse_private_fileblob(buffer, passphrase, key, comment);
    if (result == 0 && inject_eof_after_parse != NULL) {
        struct fixture *parent = inject_eof_after_parse;
        inject_eof_after_parse = NULL;
        observe_peer_eof(parent, 0);
    }
    return result;
}

int __real_sshkey_sign(struct sshkey *, u_char **, size_t *, const u_char *, size_t,
    const char *, const char *, const char *, u_int);
int __wrap_sshkey_sign(struct sshkey *key, u_char **signature, size_t *signature_length,
    const u_char *data, size_t length, const char *algorithm, const char *provider,
    const char *pin, u_int compat) {
    int result = __real_sshkey_sign(key, signature, signature_length, data, length,
        algorithm, provider, pin, compat);
    if (trace_auth_packets) auth_signatures++;
    if (result == 0 && inject_eof_after_sign != NULL) {
        struct fixture *parent = inject_eof_after_sign;
        inject_eof_after_sign = NULL;
        observe_peer_eof(parent, 0);
    }
    return result;
}

static int selected_with_observed_eof(void *context, void *environment, int kind,
    const unsigned char receipt[32], uint64_t cycle, const unsigned char digest[32],
    unsigned char *output, size_t capacity, size_t *length) {
    int result = credential_bytes_selected(context, environment, kind, receipt, cycle,
        digest, output, capacity, length);
    if (result == 0 && inject_eof_during_callback != NULL) {
        struct fixture *parent = inject_eof_during_callback;
        inject_eof_during_callback = NULL;
        /* Deterministic same-thread fixture injection, not JNI re-entry. */
        observe_peer_eof(parent, 0);
    }
    return result;
}

static int effect_callback_open(const struct ctos_ssh_callback_target *target,
    ctos_ssh_role_token role, int uid, uint64_t deadline, void *environment,
    ctos_ssh_callback_token *callback) {
    struct ctos_ssh_callback_target wrapped = *target;
    if (wrapped.credential_bytes_selected != NULL)
        wrapped.credential_bytes_selected = selected_with_observed_eof;
    return ctos_ssh_callback_open(&wrapped, role, uid, deadline, environment, callback);
}

struct test_chain {
    struct fixture root, target;
    struct jump_bridge_fixture bridge;
    struct sshkey *identity;
    struct sshbuf *identity_file;
    unsigned char *identity_blob;
    size_t identity_blob_length;
};

/* The generic fixture's malformed-selection checks use direct open and cannot
 * be used with a jump role. Construct the automatic child through its actual
 * managed relay, without changing or bypassing any production checks. */
static void setup_special_child(struct test_chain *chain, ctos_ssh_role_token role,
    int server_fd, unsigned int kind) {
    struct fixture *target = &chain->target, *root = &chain->root;
    unsigned char known_hosts[16384], was_open[512] = {0};
    int new_fds[3], count = 0;
    struct ctos_ssh_role_snapshot snapshot;
    struct ctos_ssh_slice fields[5] = {
        {(const unsigned char *)" 用户😀 ", sizeof(" 用户😀 ") - 1U},
        {NULL, 0}, {NULL, 0}, {NULL, 0}, {NULL, 0}
    };
    memset(target, 0, sizeof(*target));
    target->pair[0] = target->pair[1] = -1;
    target->callback.release_fd = -1;
    target->keyboard_expected = kind == 3U; target->automatic_selection = kind == 3U;
    target->automatic_methods = kind == 3U ? 6U : 0U; target->deadline = root->deadline;
    if (kind == 4U) {
        chain->identity_file = sshbuf_new();
        CHECK(chain->identity_file != NULL && sshkey_generate(KEY_ED25519, 0, &chain->identity) == 0);
        CHECK(sshkey_to_blob(chain->identity, &chain->identity_blob, &chain->identity_blob_length) == 0);
        CHECK(sshkey_private_to_fileblob(chain->identity, chain->identity_file, "", "chain-key",
            SSHKEY_PRIVATE_OPENSSH, NULL, 0) == 0);
        target->private_expected = 1; target->identity = chain->identity;
        target->identity_blob = chain->identity_blob; target->identity_blob_length = chain->identity_blob_length;
        target->identity_algorithm = sshkey_ssh_name(chain->identity);
        target->password_username = (const unsigned char *)" 用户😀 ";
        target->password_username_length = sizeof(" 用户😀 ") - 1U;
        fields[2] = (struct ctos_ssh_slice){(const unsigned char *)"keys/私钥", sizeof("keys/私钥") - 1U};
        fields[3] = (struct ctos_ssh_slice){(const unsigned char *)"身份😀", sizeof("身份😀") - 1U};
        fields[4] = (struct ctos_ssh_slice){chain->identity_blob, chain->identity_blob_length};
    }
    CHECK(sshkey_generate(KEY_ED25519, 0, &target->private_key) == 0);
    CHECK(sshkey_from_private(target->private_key, &target->public_key) == 0);
    target->host_key = target->public_key;
    CHECK(expected_fingerprint(target->host_key, target->fingerprint) == 0);
    size_t known_hosts_length = format_host_key(target->host_key, known_hosts, sizeof(known_hosts));
    target->view = load_view(known_hosts, known_hosts_length, target->deadline);
    CHECK(ctos_ssh_view_borrow_owned_view(target->view, &target->view_borrow) == 0);
    CHECK(sshkey_to_blob(target->host_key, &target->public_blob, &target->public_blob_length) == 0);
    target->role = role;
    CHECK(ctos_ssh_role_bootstrap_snapshot(role, &snapshot) == 0);
    target->callback_token = open_callback(&target->callback, role, target->deadline);
    target->callback.expected_role = role; target->callback.expected_hop = snapshot.hop;
    target->callback.expected_port = 22;
    remember_expected_host_evidence(&target->callback, target->host_key);
    CHECK(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, target->pair) == 0);
    target->callback.release_fd = target->pair[0];
    make_selection_wire(&target->selection_wire, &snapshot, kind, fields);
    if (kind == 3U) {
        target->selection_wire.bytes[5] = 4U;
        wire_u32(&target->selection_wire, target->automatic_methods);
    }
    CHECK(ssh_digest_memory(SSH_DIGEST_SHA256, target->selection_wire.bytes,
        target->selection_wire.length, target->selection_receipt, 32U) == 0);
    struct ctos_ssh_native_client_config config = {
        .role = role, .socket_fd = -1, .owner_uid = getuid(), .owns_socket = 1,
        .callback = target->callback_token, .callback_environment = target->callback.environment,
        .deadline = target->deadline, .step_budget = fixture_step_budget,
        .server_host_public_key_blob = target->public_blob,
        .server_host_public_key_length = target->public_blob_length,
        .expected_host_fingerprint = target->fingerprint, .managed_view_borrow = target->view_borrow,
        .known_hosts_path = "ssh/known_hosts", .host = "example.invalid", .candidate_id = "native-candidate",
        .authentication_selection_wire = target->selection_wire.bytes,
        .authentication_selection_wire_length = target->selection_wire.length
    };
    for (int fd = 0; fd < (int)sizeof(was_open); fd++) was_open[fd] = fcntl(fd, F_GETFD) >= 0;
    CHECK(ctos_ssh_native_client_open_jump_child(root->client, &config, &target->client, &chain->bridge.relay) == 0);
    for (int fd = 0; fd < (int)sizeof(was_open); fd++) {
        if (!was_open[fd] && fcntl(fd, F_GETFD) >= 0) {
            CHECK(count < 3); new_fds[count++] = fd;
        }
    }
    CHECK(count == 3);
    target->managed_child = 1; target->managed_child_fd = new_fds[1];
    if (kind == 4U) {
        target->callback.selected_kind = CTOS_SSH_CALLBACK_CREDENTIAL_PRIVATE_KEY;
        target->callback.selected_body_override = 1;
        target->callback.selected_body = sshbuf_ptr(chain->identity_file);
        target->callback.selected_body_length = sshbuf_len(chain->identity_file);
        memcpy(target->callback.expected_selection_receipt, target->selection_receipt, 32U);
    }
    CHECK(close(target->pair[1]) == 0); target->pair[1] = server_fd;
    setup_synthetic_server(target);
}

static void setup_chain(struct test_chain *chain, unsigned int kind, int automatic) {
    struct fixture *root = &chain->root, *target = &chain->target;
    int pair[2], ready = 0;
    memset(chain, 0, sizeof(*chain));
    setup_jump_fixture(root, 1);
    CHECK(ctos_ssh_native_client_begin_password_auth(root->client) == 0);
    drive_password(root, CTOS_SSH_PASSWORD_AUTH_SUCCESS, 0);
    ctos_ssh_role_token next = jump_next_role(root, 6);
    CHECK(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, pair) == 0);
    chain->bridge.server_fd = pair[0];
    root->jump_bridge_enabled = 1; root->jump_bridge_fd = pair[0];
    root->shell_window = 65536U; root->shell_maxpacket = 32768U;
    CHECK(ctos_ssh_native_client_begin_jump(root->client, next) == 0);
    drive_jump_phase(root, CTOS_SSH_JUMP_OPEN);
    fixture_step_budget = 4096U;
    if (kind == 1U) {
        setup_nested_child(target, root, root, next, pair[1], &chain->bridge.relay);
    } else if (automatic || kind == 4U) {
        setup_special_child(chain, next, pair[1], kind);
    } else {
        memset(target, 0, sizeof(*target));
        target->pair[0] = target->pair[1] = -1;
        target->callback.release_fd = -1;
        target->keyboard_expected = 1;
        target->automatic_selection = automatic;
        target->automatic_methods = automatic ? 6U : 0U;
        CHECK(sshkey_generate(KEY_ED25519, 0, &target->private_key) == 0);
        CHECK(sshkey_from_private(target->private_key, &target->public_key) == 0);
        target->host_key = target->public_key;
        fixture_existing_role = next; fixture_jump_parent = root->client;
        fixture_parent_context = root; fixture_root_context = root;
        finish_fixture_setup_selected_mode(target, 60000U, NULL, NULL, 0, 0, 3U, 0,
            CTOS_SSH_HOST_KEY_PINNED);
        fixture_existing_role = 0; fixture_jump_parent = NULL;
        fixture_parent_context = NULL; fixture_root_context = NULL;
        chain->bridge.relay = fixture_child_relay; fixture_child_relay = NULL;
        CHECK(close(target->pair[1]) == 0);
        target->pair[1] = pair[1];
        setup_synthetic_server(target);
    }
    fixture_step_budget = TEST_STEP_BUDGET;
    for (unsigned int turn = 0; turn < 200000U; turn++) {
        int complete = ctos_ssh_native_client_complete(target->client);
        jump_bridge_turn(root, target, &chain->bridge, !complete);
        if (ctos_ssh_native_client_complete(target->client) && target->server->kex->done) {
            ready = 1; break;
        }
    }
    CHECK(ready && target->callback.kex_complete_calls == 1U);
}

static void setup_service(struct test_chain *chain) {
    chain->target.service_expected = 1;
    CHECK(ctos_ssh_native_client_begin_userauth_service(chain->target.client) == 0);
    for (unsigned int turn = 0; turn < 200000U; turn++) {
        jump_bridge_turn(&chain->root, &chain->target, &chain->bridge, 1);
        if (ctos_ssh_native_client_userauth_service_ready(chain->target.client)) return;
    }
    CHECK(0);
}

static void cleanup_chain(struct test_chain *chain) {
    inject_eof_during_callback = NULL;
    inject_eof_after_parse = inject_eof_after_sign = NULL;
    cleanup_fixture(&chain->target, 1);
    CHECK(ctos_ssh_native_jump_relay_close(chain->bridge.relay) == 0);
    CHECK(ctos_ssh_native_jump_relay_destroy(chain->bridge.relay) == 0);
    CHECK(close(chain->bridge.server_fd) == 0);
    cleanup_fixture(&chain->root, 1);
    sshbuf_free(chain->identity_file);
    sshkey_free(chain->identity);
    free(chain->identity_blob);
}

static void begin_trace(void) { auth_packet_starts = auth_signatures = 0; trace_auth_packets = 1; }
static void end_trace(unsigned int expected) {
    trace_auth_packets = 0;
    CHECK(auth_packet_starts == expected);
}

static void check_selected_refused(struct test_chain *chain, unsigned int callbacks) {
    unsigned char output[64]; size_t length = 99;
    memset(output, 0xa5, sizeof(output));
    chain->target.callback.caller_output = output;
    chain->target.callback.caller_capacity = sizeof(output);
    begin_trace();
    CHECK(ctos_ssh_native_client_selected_credential_bytes(chain->target.client,
        output, sizeof(output), &length) != 0 && errno == EPIPE && length == 0);
    end_trace(0);
    for (size_t i = 0; i < sizeof(output); i++) CHECK(output[i] == 0);
    CHECK(chain->target.callback.selected_calls == callbacks && chain->target.password_requests == 0);
}

static void test_one_hop(void) {
    for (int mode = 0; mode < 6; mode++) {
        struct test_chain chain;
        child_test_case = 200 + mode;
        setup_chain(&chain, 1U, 0);
        if (mode == 1) {
            observe_peer_eof(&chain.root, 0);
            begin_trace();
            CHECK(ctos_ssh_native_client_begin_userauth_service(chain.target.client) != 0 && errno == EPIPE);
            end_trace(0);
            CHECK(chain.target.service_requests == 0 && chain.target.callback.selected_calls == 0);
            cleanup_chain(&chain); continue;
        }
        setup_service(&chain);
        if (mode == 0) {
            begin_trace();
            CHECK(ctos_ssh_native_client_begin_password_auth(chain.target.client) == 0);
            end_trace(1);
            enum ctos_ssh_native_client_password_auth_result result = CTOS_SSH_PASSWORD_AUTH_PENDING;
            for (unsigned int turn = 0; turn < 200000U && result == CTOS_SSH_PASSWORD_AUTH_PENDING; turn++) {
                jump_bridge_turn(&chain.root, &chain.target, &chain.bridge, 1);
                CHECK(ctos_ssh_native_client_password_auth_result(chain.target.client, &result) == 0);
            }
            CHECK(result == CTOS_SSH_PASSWORD_AUTH_SUCCESS && chain.target.password_requests == 1
                && chain.target.callback.selected_calls == 1);
        } else if (mode == 2 || mode == 3) {
            observe_peer_eof(&chain.root, mode == 3);
            check_selected_refused(&chain, 0);
        } else if (mode == 4) {
            inject_eof_during_callback = &chain.root;
            check_selected_refused(&chain, 1);
        } else {
            CHECK(shutdown(chain.target.managed_child_fd, SHUT_WR) == 0);
            for (unsigned int turn = 0; turn < 200000U && !jump_status(&chain.root).local_eof; turn++)
                jump_bridge_turn(&chain.root, &chain.target, &chain.bridge, 0);
            CHECK(jump_status(&chain.root).local_eof);
            check_selected_refused(&chain, 0);
        }
        cleanup_chain(&chain);
    }
}

static void test_discovery_and_keyboard(void) {
    for (int mode = 0; mode < 3; mode++) {
        struct test_chain chain;
        child_test_case = 220 + mode;
        setup_chain(&chain, 3U, mode != 2);
        setup_service(&chain);
        if (mode != 2) {
            chain.target.discovery_expected = 1;
            chain.target.discovery_methods = "password,keyboard-interactive";
            if (mode == 0) {
                observe_peer_eof(&chain.root, 0);
                begin_trace();
                CHECK(ctos_ssh_native_client_begin_auth_discovery(chain.target.client) != 0 && errno == EPIPE);
                end_trace(0);
                CHECK(chain.target.discovery_requests == 0);
                cleanup_chain(&chain); continue;
            }
            CHECK(ctos_ssh_native_client_begin_auth_discovery(chain.target.client) == 0);
            enum ctos_ssh_native_client_discovery_result result = CTOS_SSH_DISCOVERY_PENDING;
            unsigned int methods = 0;
            for (unsigned int turn = 0; turn < 200000U && result == CTOS_SSH_DISCOVERY_PENDING; turn++) {
                jump_bridge_turn(&chain.root, &chain.target, &chain.bridge, 1);
                CHECK(ctos_ssh_native_client_auth_discovery_result(chain.target.client, &result, &methods) == 0);
            }
            CHECK(result == CTOS_SSH_DISCOVERY_METHODS && (methods & CTOS_SSH_DISCOVERY_PASSWORD));
            observe_peer_eof(&chain.root, 0);
            unsigned char stage[92]; memset(stage, 0xa5, sizeof(stage));
            begin_trace();
            CHECK(ctos_ssh_native_client_begin_discovered_password(chain.target.client,
                chain.target.selection_receipt, (const unsigned char *)"password", 8, stage) != 0 && errno == EPIPE);
            end_trace(0);
            for (size_t i = 0; i < sizeof(stage); i++) CHECK(stage[i] == 0);
            CHECK(chain.target.password_requests == 0 && chain.target.callback.selected_calls == 0);
        } else {
            CHECK(ctos_ssh_native_client_begin_keyboard_interactive_auth(chain.target.client) == 0);
            for (unsigned int turn = 0; turn < 200000U && chain.target.keyboard_requests == 0; turn++)
                jump_bridge_turn(&chain.root, &chain.target, &chain.bridge, 1);
            CHECK(chain.target.keyboard_requests == 1);
            keyboard_challenge_packet(&chain.target, 1U, 0U);
            enum ctos_ssh_native_client_keyboard_interactive_auth_result result = CTOS_SSH_KEYBOARD_AUTH_PENDING;
            for (unsigned int turn = 0; turn < 200000U && result == CTOS_SSH_KEYBOARD_AUTH_PENDING; turn++) {
                jump_bridge_turn(&chain.root, &chain.target, &chain.bridge, 1);
                CHECK(ctos_ssh_native_client_keyboard_interactive_auth_result(chain.target.client, &result) == 0);
            }
            CHECK(result == CTOS_SSH_KEYBOARD_AUTH_CHALLENGE);
            chain.target.keyboard_answers[0] = (struct ctos_ssh_slice){(const unsigned char *)"answer", 6U};
            struct wire response = keyboard_response_wire(&chain.target, 1U);
            observe_peer_eof(&chain.root, 0);
            begin_trace();
            CHECK(ctos_ssh_native_client_respond_keyboard_interactive(chain.target.client,
                response.bytes, response.length) != 0 && errno == EPIPE);
            end_trace(0);
            CHECK(chain.target.keyboard_responses == 0 && chain.target.callback.selected_calls == 0);
            memset(&response, 0, sizeof(response));
        }
        cleanup_chain(&chain);
    }
}

static void test_private_key_effects(void) {
    for (int mode = 0; mode < 4; mode++) {
        struct test_chain chain;
        child_test_case = 230 + mode;
        setup_chain(&chain, 4U, 0);
        setup_service(&chain);
        if (mode == 1) observe_peer_eof(&chain.root, 0);
        if (mode == 2) inject_eof_after_parse = &chain.root;
        if (mode == 3) inject_eof_after_sign = &chain.root;
        begin_trace();
        if (mode == 0) {
            CHECK(ctos_ssh_native_client_begin_private_key_auth(chain.target.client) == 0);
            end_trace(1);
            CHECK(auth_signatures == 1);
            enum ctos_ssh_native_client_private_key_auth_result result = CTOS_SSH_PRIVATE_KEY_AUTH_PENDING;
            for (unsigned int turn = 0; turn < 200000U && result == CTOS_SSH_PRIVATE_KEY_AUTH_PENDING; turn++) {
                jump_bridge_turn(&chain.root, &chain.target, &chain.bridge, 1);
                CHECK(ctos_ssh_native_client_private_key_auth_result(chain.target.client, &result) == 0);
            }
            CHECK(result == CTOS_SSH_PRIVATE_KEY_AUTH_SUCCESS && chain.target.private_requests == 1
                && chain.target.callback.selected_calls == 1);
        } else {
            CHECK(ctos_ssh_native_client_begin_private_key_auth(chain.target.client) != 0 && errno == EPIPE);
            end_trace(0);
            CHECK(auth_signatures == (mode == 3 ? 1U : 0U));
            CHECK(chain.target.callback.selected_calls == (mode == 1 ? 0U : 1U)
                && chain.target.private_requests == 0);
        }
        cleanup_chain(&chain);
    }
}

static void test_zero_window_backpressure(void) {
    struct test_chain chain;
    unsigned char filler[4096];
    size_t sent = 0;
    child_test_case = 239;
    memset(filler, 'x', sizeof(filler));
    setup_chain(&chain, 1U, 0);
    setup_service(&chain);
    for (int turn = 0; turn < 100; turn++)
        jump_bridge_turn(&chain.root, &chain.target, &chain.bridge, 0);
    /* Stop window adjustments at the synthetic peer and consume exactly its
     * advertised 65536-byte window. Filler is kept by that peer and never
     * reaches the synthetic target's encrypted SSH stream. */
    chain.root.jump_bridge_enabled = 0;
    for (unsigned int turn = 0; turn < 200000U && chain.root.shell_input_length < 65536U; turn++) {
        if (sent < 65536U) {
            size_t amount = 65536U - sent < sizeof(filler) ? 65536U - sent : sizeof(filler);
            ssize_t n = send(chain.target.managed_child_fd, filler, amount, MSG_NOSIGNAL | MSG_DONTWAIT);
            if (n > 0) sent += (size_t)n;
            else CHECK(n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR));
        }
        CHECK(ctos_ssh_native_jump_relay_pump(chain.bridge.relay) == 0);
        CHECK(shell_turn(&chain.root) == 0);
    }
    CHECK(sent == 65536U && chain.root.shell_input_length == 65536U);
    CHECK(jump_status(&chain.root).phase == CTOS_SSH_JUMP_OPEN);
    begin_trace();
    CHECK(ctos_ssh_native_client_begin_password_auth(chain.target.client) == 0);
    end_trace(1);
    CHECK(chain.target.callback.selected_calls == 1);
    for (int turn = 0; turn < 100; turn++) {
        CHECK(ctos_ssh_native_client_step(chain.target.client, &(struct ctos_ssh_native_client_step){0}) == 0);
        CHECK(ctos_ssh_native_jump_relay_pump(chain.bridge.relay) == 0);
        CHECK(shell_turn(&chain.root) == 0);
        CHECK(pump_synthetic_server(&chain.target, 64U, NULL, NULL) == 0);
    }
    CHECK(chain.root.shell_input_length == 65536U && chain.target.password_requests == 0);
    cleanup_chain(&chain);
}

static void test_deep_ancestor(void) {
    static const unsigned char target_nonce[32] = {10};
    for (int mode = 0; mode < 3; mode++) {
        struct fixture root, middle, target;
        struct jump_bridge_fixture first = {0}, second = {0};
        int first_pair[2], second_pair[2], ready = 0;
        child_test_case = 240 + mode;
        setup_jump_fixture(&root, 1);
        CHECK(ctos_ssh_native_client_begin_password_auth(root.client) == 0);
        drive_password(&root, CTOS_SSH_PASSWORD_AUTH_SUCCESS, 0);
        ctos_ssh_role_token middle_role = jump_next_role(&root, 7);
        CHECK(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, first_pair) == 0);
        first.server_fd = first_pair[0]; root.jump_bridge_enabled = 1; root.jump_bridge_fd = first.server_fd;
        root.shell_window = 65536U; root.shell_maxpacket = 32768U;
        CHECK(ctos_ssh_native_client_begin_jump(root.client, middle_role) == 0);
        drive_jump_phase(&root, CTOS_SSH_JUMP_OPEN);
        fixture_step_budget = 4096U;
        setup_nested_child(&middle, &root, &root, middle_role, first_pair[1], &first.relay);
        fixture_step_budget = TEST_STEP_BUDGET;
        for (unsigned int turn = 0; turn < 200000U; turn++) {
            int complete = ctos_ssh_native_client_complete(middle.client);
            jump_bridge_turn(&root, &middle, &first, !complete);
            if (ctos_ssh_native_client_complete(middle.client) && middle.server->kex->done) {
                ready = 1; break;
            }
        }
        CHECK(ready);
        middle.service_expected = 1;
        CHECK(ctos_ssh_native_client_begin_userauth_service(middle.client) == 0);
        for (unsigned int turn = 0; turn < 200000U
                && !ctos_ssh_native_client_userauth_service_ready(middle.client); turn++)
            jump_bridge_turn(&root, &middle, &first, 1);
        CHECK(ctos_ssh_native_client_userauth_service_ready(middle.client));
        CHECK(ctos_ssh_native_client_begin_password_auth(middle.client) == 0);
        enum ctos_ssh_native_client_password_auth_result auth = CTOS_SSH_PASSWORD_AUTH_PENDING;
        for (unsigned int turn = 0; turn < 200000U && auth == CTOS_SSH_PASSWORD_AUTH_PENDING; turn++) {
            jump_bridge_turn(&root, &middle, &first, 1);
            CHECK(ctos_ssh_native_client_password_auth_result(middle.client, &auth) == 0);
        }
        CHECK(auth == CTOS_SSH_PASSWORD_AUTH_SUCCESS);
        struct ctos_ssh_role_request request = role_request(root.deadline);
        request.hop = 2; request.role_nonce = target_nonce; request.public_view_id = "target-view";
        ctos_ssh_role_token target_role = 0;
        CHECK(ctos_ssh_role_bootstrap_open(&request, &target_role) == 0);
        CHECK(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, second_pair) == 0);
        second.server_fd = second_pair[0]; middle.shell_expected = middle.jump_expected = 1;
        middle.jump_bridge_enabled = 1; middle.jump_bridge_fd = second.server_fd;
        middle.shell_window = 65536U; middle.shell_maxpacket = 32768U;
        CHECK(ctos_ssh_native_client_begin_jump(middle.client, target_role) == 0);
        for (unsigned int turn = 0; turn < 200000U && jump_status(&middle).phase != CTOS_SSH_JUMP_OPEN; turn++)
            jump_bridge_turn(&root, &middle, &first, 1);
        CHECK(jump_status(&middle).phase == CTOS_SSH_JUMP_OPEN);
        fixture_step_budget = 4096U;
        setup_nested_child(&target, &middle, &root, target_role, second_pair[1], &second.relay);
        fixture_step_budget = TEST_STEP_BUDGET;
        ready = 0;
        for (unsigned int turn = 0; turn < 200000U; turn++) {
            int complete = ctos_ssh_native_client_complete(target.client);
            three_hop_turn(&root, &middle, &target, &first, &second, !complete);
            if (ctos_ssh_native_client_complete(target.client) && target.server->kex->done) {
                ready = 1; break;
            }
        }
        CHECK(ready);
        target.service_expected = 1;
        CHECK(ctos_ssh_native_client_begin_userauth_service(target.client) == 0);
        for (unsigned int turn = 0; turn < 200000U
                && !ctos_ssh_native_client_userauth_service_ready(target.client); turn++)
            three_hop_turn(&root, &middle, &target, &first, &second, 1);
        CHECK(ctos_ssh_native_client_userauth_service_ready(target.client));
        if (mode == 2) inject_eof_during_callback = &root;
        else observe_peer_eof(&root, mode == 1);
        CHECK(jump_status(&middle).phase == CTOS_SSH_JUMP_OPEN);
        unsigned char output[64]; size_t length = 99;
        memset(output, 0xa5, sizeof(output));
        target.callback.caller_output = output; target.callback.caller_capacity = sizeof(output);
        begin_trace();
        CHECK(ctos_ssh_native_client_selected_credential_bytes(target.client,
            output, sizeof(output), &length) != 0 && errno == EPIPE && length == 0);
        end_trace(0);
        for (size_t i = 0; i < sizeof(output); i++) CHECK(output[i] == 0);
        CHECK(target.callback.selected_calls == (mode == 2 ? 1U : 0U) && target.password_requests == 0);
        inject_eof_during_callback = NULL;
        cleanup_fixture(&target, 1);
        CHECK(ctos_ssh_native_jump_relay_close(second.relay) == 0);
        CHECK(ctos_ssh_native_jump_relay_destroy(second.relay) == 0);
        CHECK(close(second.server_fd) == 0);
        cleanup_fixture(&middle, 1);
        CHECK(ctos_ssh_native_jump_relay_close(first.relay) == 0);
        CHECK(ctos_ssh_native_jump_relay_destroy(first.relay) == 0);
        CHECK(close(first.server_fd) == 0);
        cleanup_fixture(&root, 1);
    }
}

int main(void) {
    test_one_hop();
    test_discovery_and_keyboard();
    test_private_key_effects();
    test_zero_window_backpressure();
    test_deep_ancestor();
    test_jump_nested_ssh(CHILD_REMOTE_CLOSE);
    printf("SSH_AUTH_OPEN_CHAIN checks=%u gate=pass runtime=synthetic\n", checks);
    return 0;
}

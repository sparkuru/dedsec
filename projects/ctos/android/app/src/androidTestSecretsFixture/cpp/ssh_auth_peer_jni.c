#define _GNU_SOURCE

#include <jni.h>

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#include "kex.h"
#include "myproposal.h"
#include "packet.h"
#include "ssh2.h"
#include "ssh_api.h"
#include "sshkey.h"
#include "sshbuf.h"

#define PEER_SLOTS 8U
#define PEER_BUDGET 4096U
#define PEER_LIFETIME_MS 30000U

enum peer_mode { MODE_SUCCESS, MODE_REJECTED, MODE_PARTIAL, MODE_CHANGE, MODE_HOLD,
    MODE_MALFORMED_SUCCESS, MODE_WRONG_PASSPHRASE, MODE_SHELL, MODE_PTY_REJECT,
    MODE_SHELL_HOLD, MODE_OPEN_REJECT, MODE_SHELL_REJECT };

struct peer_slot {
    uint64_t token;
    uint64_t deadline;
    pthread_t owner;
    pid_t pid;
    uid_t uid;
    struct ssh *server;
    struct sshkey *private_key;
    struct sshkey *identity;
    int identity_encrypted;
    int fd;
    int mode;
    int failed;
    int service_count;
    int password_count;
    int reply_count;
    int exact_fields;
    int authenticated;
    int channel_phase;
    int channel_open_count;
    int pty_count;
    int shell_count;
    int input_count;
    int resize_count;
    int eof_count;
    int close_count;
    unsigned char command[256];
    size_t command_length;
};

static pthread_mutex_t peers_lock = PTHREAD_MUTEX_INITIALIZER;
static struct peer_slot peers[PEER_SLOTS];
static uint64_t next_token;

static void wipe(void *memory, size_t length) {
    volatile unsigned char *bytes = memory;

    if (bytes == NULL) return;
    while (length-- != 0U) *bytes++ = 0;
}

static void fail(JNIEnv *env) {
    jclass error;

    if ((*env)->ExceptionCheck(env)) return;
    error = (*env)->FindClass(env, "java/io/IOException");
    if (error == NULL) return;
    (*env)->ThrowNew(env, error, "Synthetic SSH peer unavailable");
    (*env)->DeleteLocalRef(env, error);
}

static uint64_t now_ms(void) {
    struct timespec now;

    if (clock_gettime(CLOCK_BOOTTIME, &now) != 0) return 0;
    return (uint64_t)now.tv_sec * 1000U + (uint64_t)now.tv_nsec / 1000000U;
}

static struct peer_slot *lock_peer(JNIEnv *env, jlong handle, int require_live) {
    struct peer_slot *peer = NULL;
    uint64_t now;

    if (handle <= 0 || pthread_mutex_lock(&peers_lock) != 0) {
        fail(env);
        return NULL;
    }
    for (size_t index = 0; index < PEER_SLOTS; index++) {
        if (peers[index].token == (uint64_t)handle) {
            peer = &peers[index];
            break;
        }
    }
    if (peer == NULL || !pthread_equal(peer->owner, pthread_self())
        || peer->pid != getpid() || peer->uid != getuid()) goto rejected;
    if (require_live) {
        now = now_ms();
        if (peer->failed || now == 0 || now >= peer->deadline) goto rejected;
    }
    return peer;

rejected:
    pthread_mutex_unlock(&peers_lock);
    fail(env);
    return NULL;
}

static int duplicate_peer(int borrowed) {
    struct sockaddr_storage address;
    struct ucred credential;
    socklen_t length = sizeof(address);
    int type = 0;
    int flags;
    int fd;

    if (borrowed < 0 || getsockname(borrowed, (struct sockaddr *)&address, &length) != 0
        || address.ss_family != AF_UNIX) return -1;
    length = sizeof(type);
    if (getsockopt(borrowed, SOL_SOCKET, SO_TYPE, &type, &length) != 0
        || length != sizeof(type) || type != SOCK_STREAM) return -1;
    length = sizeof(credential);
    if (getsockopt(borrowed, SOL_SOCKET, SO_PEERCRED, &credential, &length) != 0
        || length != sizeof(credential) || credential.pid != getpid()
        || credential.uid != getuid()) return -1;
    fd = fcntl(borrowed, F_DUPFD_CLOEXEC, 0);
    if (fd < 0) return -1;
    flags = fcntl(fd, F_GETFL);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static void clear_peer(struct peer_slot *peer) {
    if (peer->server != NULL) {
        /* set_connection can retain the fd even when cipher setup fails. */
        if (peer->fd >= 0 && ssh_packet_get_connection_in(peer->server) == peer->fd)
            peer->fd = -1;
        ssh_free(peer->server);
        peer->server = NULL;
    }
    if (peer->fd >= 0) close(peer->fd);
    sshkey_free(peer->private_key);
    sshkey_free(peer->identity);
    wipe(peer, sizeof(*peer));
}

JNIEXPORT jlong JNICALL
Java_im_majo_ctos_SshAuthFixturePeer_openNative(JNIEnv *env, jclass ignored,
    jint borrowed_fd, jint mode, jint identity_kind, jboolean encrypted) {
    struct peer_slot pending = {.fd = -1};
    struct peer_slot *slot = NULL;
    struct kex_params params = {0};
    char *proposals[PROPOSAL_MAX] = {KEX_CLIENT};
    uint64_t now = now_ms();
    jlong token = 0;

    (void)ignored;
    if (mode < MODE_SUCCESS || mode > MODE_SHELL_REJECT
        || identity_kind < 0 || identity_kind > 5 || now == 0
        || (encrypted != JNI_FALSE && identity_kind == 0)
        || (mode == MODE_WRONG_PASSPHRASE && encrypted == JNI_FALSE)
        || now > UINT64_MAX - PEER_LIFETIME_MS) goto rejected;
    pending.fd = duplicate_peer(borrowed_fd);
    if (pending.fd < 0) goto rejected;
    memcpy(params.proposal, proposals, sizeof(proposals));
    if (sshkey_generate(KEY_ED25519, 0, &pending.private_key) != 0
        || ssh_init(&pending.server, 1, &params) != 0 || pending.server == NULL)
        goto rejected;
    if (ssh_packet_set_connection(pending.server, pending.fd, pending.fd) == NULL)
        goto rejected;
    ssh_packet_set_nonblocking(pending.server);
    if (ssh_add_hostkey(pending.server, pending.private_key) != 0) goto rejected;
    if (identity_kind != 0) {
        int type = identity_kind == 1 ? KEY_ED25519 : identity_kind == 5 ? KEY_RSA : KEY_ECDSA;
        unsigned int bits = identity_kind == 2 ? 256 : identity_kind == 3 ? 384
            : identity_kind == 4 ? 521 : identity_kind == 5 ? 2048 : 0;
        if (sshkey_generate(type, bits, &pending.identity) != 0) goto rejected;
        pending.identity_encrypted = encrypted != JNI_FALSE;
    }
    if (pthread_mutex_lock(&peers_lock) != 0) goto rejected;
    for (size_t index = 0; index < PEER_SLOTS; index++) {
        if (peers[index].token == 0) {
            slot = &peers[index];
            break;
        }
    }
    if (slot != NULL && next_token < INT64_MAX) {
        pending.token = ++next_token;
        pending.owner = pthread_self();
        pending.pid = getpid();
        pending.uid = getuid();
        pending.deadline = now + PEER_LIFETIME_MS;
        pending.mode = mode;
        *slot = pending;
        token = (jlong)slot->token;
    }
    pthread_mutex_unlock(&peers_lock);
    if (token != 0) return token;

rejected:
    clear_peer(&pending);
    fail(env);
    return 0;
}

JNIEXPORT jbyteArray JNICALL
Java_im_majo_ctos_SshAuthFixturePeer_hostKeyBlobNative(JNIEnv *env, jclass ignored,
    jlong handle) {
    struct peer_slot *peer = lock_peer(env, handle, 1);
    unsigned char *blob = NULL;
    size_t length = 0;
    jbyteArray result = NULL;

    (void)ignored;
    if (peer == NULL) return NULL;
    if (sshkey_to_blob(peer->private_key, &blob, &length) != 0 || blob == NULL
        || length == 0 || length > 4096U) goto done;
    result = (*env)->NewByteArray(env, (jsize)length);
    if (result == NULL || (*env)->ExceptionCheck(env)) goto done;
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)length, (const jbyte *)blob);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->DeleteLocalRef(env, result);
        result = NULL;
    }
done:
    wipe(blob, length);
    free(blob);
    pthread_mutex_unlock(&peers_lock);
    if (result == NULL) fail(env);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_im_majo_ctos_SshAuthFixturePeer_identityMaterialNative(JNIEnv *env, jclass ignored,
    jlong handle) {
    struct peer_slot *peer = lock_peer(env, handle, 1);
    struct sshbuf *file = NULL;
    unsigned char *public_blob = NULL;
    size_t public_length = 0;
    jobjectArray result = NULL;
    jclass bytes = NULL;
    jbyteArray public_array = NULL, private_array = NULL;

    (void)ignored;
    if (peer == NULL) return NULL;
    file = sshbuf_new();
    if (peer->identity == NULL || file == NULL
        || sshkey_to_blob(peer->identity, &public_blob, &public_length) != 0
        || sshkey_private_to_fileblob(peer->identity, file,
            peer->identity_encrypted ? "fixture-key-passphrase" : "", "synthetic", SSHKEY_PRIVATE_OPENSSH,
            NULL, peer->identity_encrypted ? 1 : 0) != 0
        || public_length > 4096U || sshbuf_len(file) > 16384U) goto done;
    bytes = (*env)->FindClass(env, "[B");
    if (bytes == NULL || (*env)->ExceptionCheck(env)) goto done;
    public_array = (*env)->NewByteArray(env, (jsize)public_length);
    if (public_array == NULL || (*env)->ExceptionCheck(env)) goto done;
    private_array = (*env)->NewByteArray(env, (jsize)sshbuf_len(file));
    if (private_array == NULL || (*env)->ExceptionCheck(env)) goto done;
    (*env)->SetByteArrayRegion(env, public_array, 0, (jsize)public_length, (const jbyte *)public_blob);
    if ((*env)->ExceptionCheck(env)) goto done;
    (*env)->SetByteArrayRegion(env, private_array, 0, (jsize)sshbuf_len(file), (const jbyte *)sshbuf_ptr(file));
    if ((*env)->ExceptionCheck(env)) goto done;
    result = (*env)->NewObjectArray(env, 2, bytes, NULL);
    if (result == NULL || (*env)->ExceptionCheck(env)) goto done;
    (*env)->SetObjectArrayElement(env, result, 0, public_array);
    if ((*env)->ExceptionCheck(env)) goto done;
    (*env)->SetObjectArrayElement(env, result, 1, private_array);
done:
    if ((*env)->ExceptionCheck(env) && result != NULL) {
        (*env)->DeleteLocalRef(env, result);
        result = NULL;
    }
    if (public_array != NULL) (*env)->DeleteLocalRef(env, public_array);
    if (private_array != NULL) (*env)->DeleteLocalRef(env, private_array);
    if (bytes != NULL) (*env)->DeleteLocalRef(env, bytes);
    wipe(public_blob, public_length);
    free(public_blob);
    sshbuf_free(file);
    pthread_mutex_unlock(&peers_lock);
    if (result == NULL) fail(env);
    return result;
}

static int exact_string(struct ssh *server, const char *expected) {
    const unsigned char *field = NULL;
    size_t length = 0;

    return sshpkt_get_string_direct(server, &field, &length) == 0
        && length == strlen(expected) && memcmp(field, expected, length) == 0;
}

static int accept_service(struct peer_slot *peer) {
    if (!peer->server->kex->done || peer->service_count != 0
        || !exact_string(peer->server, "ssh-userauth")
        || sshpkt_get_end(peer->server) != 0) return -1;
    peer->service_count++;
    if (sshpkt_start(peer->server, SSH2_MSG_SERVICE_ACCEPT) != 0
        || sshpkt_put_cstring(peer->server, "ssh-userauth") != 0
        || sshpkt_send(peer->server) != 0) return -1;
    return 0;
}

static int password_reply(struct peer_slot *peer) {
    int type = peer->mode == MODE_REJECTED || peer->mode == MODE_PARTIAL
        ? SSH2_MSG_USERAUTH_FAILURE : peer->mode == MODE_CHANGE
        ? SSH2_MSG_USERAUTH_PASSWD_CHANGEREQ : SSH2_MSG_USERAUTH_SUCCESS;

    if (peer->mode == MODE_HOLD) return 0;
    if (sshpkt_start(peer->server, type) != 0) return -1;
    if (type == SSH2_MSG_USERAUTH_FAILURE
        && (sshpkt_put_cstring(peer->server, "publickey,password") != 0
        || sshpkt_put_u8(peer->server, peer->mode == MODE_PARTIAL ? 1 : 0) != 0)) return -1;
    if (type == SSH2_MSG_USERAUTH_PASSWD_CHANGEREQ
        && (sshpkt_put_cstring(peer->server, "fixture change") != 0
        || sshpkt_put_cstring(peer->server, "") != 0)) return -1;
    if (peer->mode == MODE_MALFORMED_SUCCESS && sshpkt_put_u8(peer->server, 1) != 0)
        return -1;
    if (sshpkt_send(peer->server) != 0) return -1;
    peer->reply_count++;
    peer->authenticated = type == SSH2_MSG_USERAUTH_SUCCESS
        && peer->mode != MODE_MALFORMED_SUCCESS;
    return 0;
}

static int accept_password(struct peer_slot *peer) {
    const unsigned char *password = NULL;
    size_t length = 0;
    u_char change = 1;
    int valid = 0;

    if (peer->service_count != 1 || peer->password_count != 0
        || !exact_string(peer->server, "fixture-user")
        || !exact_string(peer->server, "ssh-connection")
        || !exact_string(peer->server, "password")
        || sshpkt_get_u8(peer->server, &change) != 0 || change != 0
        || sshpkt_get_string_direct(peer->server, &password, &length) != 0) goto done;
    valid = length == sizeof("fixture-password") - 1U
        && memcmp(password, "fixture-password", length) == 0
        && sshpkt_get_end(peer->server) == 0;
done:
    /* The packet owns this mutable decrypted range; do not retain secret bytes. */
    wipe((void *)password, length);
    if (!valid) return -1;
    peer->password_count++;
    peer->exact_fields = 1;
    return password_reply(peer);
}

static int accept_private_key(struct peer_slot *peer) {
    const unsigned char *blob = NULL, *signature = NULL;
    size_t blob_length, signature_length;
    struct sshkey *received = NULL;
    struct sshbuf *data = sshbuf_new();
    const char *algorithm = peer->identity->type == KEY_RSA ? "rsa-sha2-512"
        : sshkey_ssh_name(peer->identity);
    u_char signed_request = 0;
    int result = -1;

    if (data == NULL || peer->service_count != 1 || peer->password_count != 0
        || !exact_string(peer->server, "fixture-user")
        || !exact_string(peer->server, "ssh-connection")
        || !exact_string(peer->server, "publickey")
        || sshpkt_get_u8(peer->server, &signed_request) != 0 || signed_request != 1
        || !exact_string(peer->server, algorithm)
        || sshpkt_get_string_direct(peer->server, &blob, &blob_length) != 0
        || blob_length > 4096U || sshkey_from_blob(blob, blob_length, &received) != 0
        || !sshkey_equal_public(received, peer->identity)
        || sshkey_equal_public(received, peer->private_key)
        || sshpkt_get_string_direct(peer->server, &signature, &signature_length) != 0
        || signature_length > 16384U || sshpkt_get_end(peer->server) != 0) goto done;
    if (sshbuf_put_stringb(data, peer->server->kex->session_id) != 0
        || sshbuf_put_u8(data, SSH2_MSG_USERAUTH_REQUEST) != 0
        || sshbuf_put_cstring(data, "fixture-user") != 0
        || sshbuf_put_cstring(data, "ssh-connection") != 0
        || sshbuf_put_cstring(data, "publickey") != 0
        || sshbuf_put_u8(data, 1) != 0 || sshbuf_put_cstring(data, algorithm) != 0
        || sshbuf_put_string(data, blob, blob_length) != 0
        || sshkey_check_sigtype(signature, signature_length, algorithm) != 0
        || sshkey_verify(received, signature, signature_length, sshbuf_ptr(data), sshbuf_len(data),
            algorithm, 0, NULL) != 0) goto done;
    peer->password_count++;
    peer->exact_fields = 1;
    result = password_reply(peer);
done:
    sshbuf_free(data);
    sshkey_free(received);
    return result;
}

static int channel_reply(struct peer_slot *peer, int type) {
    return sshpkt_start(peer->server, type) != 0
        || sshpkt_put_u32(peer->server, 0) != 0
        || sshpkt_send(peer->server) != 0 ? -1 : 0;
}

/* This peer validates fixed commands; it never executes a shell or arbitrary input. */
static int shell_packet(struct peer_slot *peer, int type) {
    struct ssh *ssh = peer->server;
    const unsigned char *text;
    size_t length;
    uint32_t channel, window, maximum, columns, rows, pixels;
    u_char reply;

    if (!peer->authenticated || peer->mode < MODE_SHELL) return -1;
    if (type == SSH2_MSG_CHANNEL_OPEN) {
        if (peer->channel_phase != 0 || !exact_string(ssh, "session")
            || sshpkt_get_u32(ssh, &channel) != 0 || channel != 0
            || sshpkt_get_u32(ssh, &window) != 0 || window != 65536U
            || sshpkt_get_u32(ssh, &maximum) != 0 || maximum != 32768U
            || sshpkt_get_end(ssh) != 0) return -1;
        peer->channel_open_count++;
        if (peer->mode == MODE_OPEN_REJECT) {
            peer->channel_phase = 6;
            return sshpkt_start(ssh, SSH2_MSG_CHANNEL_OPEN_FAILURE) != 0
                || sshpkt_put_u32(ssh, 0) != 0 || sshpkt_put_u32(ssh, 1) != 0
                || sshpkt_put_cstring(ssh, "synthetic rejection") != 0
                || sshpkt_put_cstring(ssh, "") != 0 || sshpkt_send(ssh) != 0 ? -1 : 0;
        }
        peer->channel_phase = 1;
        return sshpkt_start(ssh, SSH2_MSG_CHANNEL_OPEN_CONFIRMATION) != 0
            || sshpkt_put_u32(ssh, 0) != 0 || sshpkt_put_u32(ssh, 7) != 0
            || sshpkt_put_u32(ssh, 16) != 0 || sshpkt_put_u32(ssh, 8) != 0
            || sshpkt_send(ssh) != 0 ? -1 : 0;
    }
    if (sshpkt_get_u32(ssh, &channel) != 0 || channel != 7) return -1;
    if (type == SSH2_MSG_CHANNEL_REQUEST) {
        if (sshpkt_get_string_direct(ssh, &text, &length) != 0 || length > 128U
            || sshpkt_get_u8(ssh, &reply) != 0) return -1;
        if (length == 7U && memcmp(text, "pty-req", 7) == 0) {
            if (peer->channel_phase != 1 || reply != 1
                || !exact_string(ssh, "xterm-256color")
                || sshpkt_get_u32(ssh, &columns) != 0 || columns != 80
                || sshpkt_get_u32(ssh, &rows) != 0 || rows != 24
                || sshpkt_get_u32(ssh, &pixels) != 0 || pixels != 0
                || sshpkt_get_u32(ssh, &pixels) != 0 || pixels != 0
                || sshpkt_get_string_direct(ssh, &text, &length) != 0
                || length != 1 || text[0] != 0 || sshpkt_get_end(ssh) != 0) return -1;
            peer->pty_count++;
            peer->channel_phase = peer->mode == MODE_PTY_REJECT ? 6 : 2;
            if (peer->mode == MODE_SHELL_HOLD) return 0;
            return channel_reply(peer, peer->mode == MODE_PTY_REJECT
                ? SSH2_MSG_CHANNEL_FAILURE : SSH2_MSG_CHANNEL_SUCCESS);
        }
        if (length == 5U && memcmp(text, "shell", 5) == 0) {
            if (peer->channel_phase != 2 || reply != 1 || sshpkt_get_end(ssh) != 0) return -1;
            peer->shell_count++;
            peer->channel_phase = peer->mode == MODE_SHELL_REJECT ? 6 : 3;
            return channel_reply(peer, peer->mode == MODE_SHELL_REJECT
                ? SSH2_MSG_CHANNEL_FAILURE : SSH2_MSG_CHANNEL_SUCCESS);
        }
        if (length == 13U && memcmp(text, "window-change", 13) == 0) {
            if (peer->channel_phase != 3 || reply != 0
                || sshpkt_get_u32(ssh, &columns) != 0 || columns != 132
                || sshpkt_get_u32(ssh, &rows) != 0 || rows != 43
                || sshpkt_get_u32(ssh, &pixels) != 0 || pixels != 0
                || sshpkt_get_u32(ssh, &pixels) != 0 || pixels != 0
                || sshpkt_get_end(ssh) != 0) return -1;
            peer->resize_count++;
            return 0;
        }
        return -1;
    }
    if (type == SSH2_MSG_CHANNEL_DATA) {
        if (peer->channel_phase != 3 || peer->eof_count != 0
            || sshpkt_get_string_direct(ssh, &text, &length) != 0
            || length == 0 || length > 8U
            || length > sizeof(peer->command) - peer->command_length
            || sshpkt_get_end(ssh) != 0) return -1;
        memcpy(peer->command + peer->command_length, text, length);
        peer->command_length += length;
        peer->input_count += (int)length;
        if (sshpkt_start(ssh, SSH2_MSG_CHANNEL_WINDOW_ADJUST) != 0
            || sshpkt_put_u32(ssh, 0) != 0 || sshpkt_put_u32(ssh, (uint32_t)length) != 0
            || sshpkt_send(ssh) != 0) return -1;
        if (peer->command[peer->command_length - 1U] != '\r') return 0;
        if (peer->command_length == sizeof("printf ctos-shell\r") - 1U
            && memcmp(peer->command, "printf ctos-shell\r", peer->command_length) == 0) {
            peer->command_length = 0;
            return sshpkt_start(ssh, SSH2_MSG_CHANNEL_DATA) != 0
                || sshpkt_put_u32(ssh, 0) != 0
                || sshpkt_put_cstring(ssh, "ctos-shell \xe4\xbd\xa0\xe5\xa5\xbd\r\n") != 0
                || sshpkt_send(ssh) != 0
                || sshpkt_start(ssh, SSH2_MSG_CHANNEL_EXTENDED_DATA) != 0
                || sshpkt_put_u32(ssh, 0) != 0 || sshpkt_put_u32(ssh, 1) != 0
                || sshpkt_put_cstring(ssh, "[session exited: 99]\r\n") != 0
                || sshpkt_send(ssh) != 0 ? -1 : 0;
        }
        if (peer->command_length != sizeof("exit\r") - 1U
            || memcmp(peer->command, "exit\r", peer->command_length) != 0
            || peer->resize_count != 1) return -1;
        peer->command_length = 0;
        peer->channel_phase = 4;
        return sshpkt_start(ssh, SSH2_MSG_CHANNEL_REQUEST) != 0
            || sshpkt_put_u32(ssh, 0) != 0 || sshpkt_put_cstring(ssh, "exit-status") != 0
            || sshpkt_put_u8(ssh, 0) != 0 || sshpkt_put_u32(ssh, 23) != 0
            || sshpkt_send(ssh) != 0 || channel_reply(peer, SSH2_MSG_CHANNEL_EOF) != 0
            || channel_reply(peer, SSH2_MSG_CHANNEL_CLOSE) != 0 ? -1 : 0;
    }
    if (type == SSH2_MSG_CHANNEL_WINDOW_ADJUST) {
        return sshpkt_get_u32(ssh, &window) != 0 || window == 0
            || sshpkt_get_end(ssh) != 0 ? -1 : 0;
    }
    if (type == SSH2_MSG_CHANNEL_EOF) {
        if (peer->eof_count != 0 || sshpkt_get_end(ssh) != 0) return -1;
        peer->eof_count++;
        return 0;
    }
    if (type == SSH2_MSG_CHANNEL_CLOSE) {
        if ((peer->channel_phase != 4 && peer->channel_phase != 6)
            || peer->close_count != 0 || sshpkt_get_end(ssh) != 0) return -1;
        peer->close_count++;
        return peer->channel_phase == 6 ? channel_reply(peer, SSH2_MSG_CHANNEL_CLOSE) : 0;
    }
    return -1;
}

static int pump(struct peer_slot *peer) {
    unsigned char input[PEER_BUDGET];
    const unsigned char *output;
    size_t length = 0;
    ssize_t transferred;
    u_char type = SSH_MSG_NONE;
    int result = -1;

    transferred = recv(peer->fd, input, sizeof(input), MSG_DONTWAIT);
    if (transferred > 0) {
        if (ssh_input_append(peer->server, input, (size_t)transferred) != 0) goto done;
    } else if (transferred == 0 || (errno != EAGAIN && errno != EWOULDBLOCK
        && errno != EINTR)) goto done;
    if (ssh_packet_next(peer->server, &type) != 0) goto done;
    if (type == SSH2_MSG_EXT_INFO) {
        if (kex_input_ext_info(type, 0, peer->server) != 0) goto done;
    } else if (type == SSH2_MSG_SERVICE_REQUEST) {
        if (accept_service(peer) != 0) goto done;
    } else if (type == SSH2_MSG_USERAUTH_REQUEST) {
        if ((peer->identity == NULL ? accept_password(peer) : accept_private_key(peer)) != 0) goto done;
    } else if (type >= SSH2_MSG_CHANNEL_OPEN && type <= SSH2_MSG_CHANNEL_FAILURE) {
        if (shell_packet(peer, type) != 0) goto done;
    } else if (type != SSH_MSG_NONE) goto done;
    output = ssh_output_ptr(peer->server, &length);
    if (output != NULL && length != 0) {
        if (length > PEER_BUDGET) length = PEER_BUDGET;
        transferred = send(peer->fd, output, length, MSG_NOSIGNAL | MSG_DONTWAIT);
        if (transferred > 0) {
            if (ssh_output_consume(peer->server, (size_t)transferred) != 0) goto done;
        } else if (transferred == 0 || (errno != EAGAIN && errno != EWOULDBLOCK
            && errno != EINTR)) goto done;
    }
    result = 0;
done:
    wipe(input, sizeof(input));
    return result;
}

JNIEXPORT void JNICALL
Java_im_majo_ctos_SshAuthFixturePeer_stepNative(JNIEnv *env, jclass ignored, jlong handle) {
    struct peer_slot *peer = lock_peer(env, handle, 1);
    int result;

    (void)ignored;
    if (peer == NULL) return;
    result = pump(peer);
    if (result != 0) peer->failed = 1;
    pthread_mutex_unlock(&peers_lock);
    if (result != 0) fail(env);
}

JNIEXPORT jintArray JNICALL
Java_im_majo_ctos_SshAuthFixturePeer_summaryNative(JNIEnv *env, jclass ignored, jlong handle) {
    struct peer_slot *peer = lock_peer(env, handle, 1);
    jint values[4];
    jintArray result;

    (void)ignored;
    if (peer == NULL) return NULL;
    values[0] = peer->service_count;
    values[1] = peer->password_count;
    values[2] = peer->reply_count;
    values[3] = peer->exact_fields;
    result = (*env)->NewIntArray(env, 4);
    if (result == NULL || (*env)->ExceptionCheck(env)) {
        pthread_mutex_unlock(&peers_lock);
        fail(env);
        return NULL;
    }
    (*env)->SetIntArrayRegion(env, result, 0, 4, values);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->DeleteLocalRef(env, result);
        result = NULL;
    }
    pthread_mutex_unlock(&peers_lock);
    if (result == NULL) fail(env);
    return result;
}

JNIEXPORT void JNICALL
Java_im_majo_ctos_SshAuthFixturePeer_closeNative(JNIEnv *env, jclass ignored, jlong handle) {
    struct peer_slot *peer = lock_peer(env, handle, 0);

    (void)ignored;
    if (peer == NULL) return;
    clear_peer(peer);
    pthread_mutex_unlock(&peers_lock);
}

JNIEXPORT jintArray JNICALL
Java_im_majo_ctos_SshAuthFixturePeer_shellSummaryNative(JNIEnv *env, jclass ignored, jlong handle) {
    struct peer_slot *peer = lock_peer(env, handle, 1);
    jintArray result = NULL;

    (void)ignored;
    if (peer == NULL) return NULL;
    jint values[8] = {peer->channel_open_count, peer->pty_count, peer->shell_count,
        peer->input_count, peer->resize_count, peer->eof_count, peer->close_count,
        peer->exact_fields};
    result = (*env)->NewIntArray(env, 8);
    if (result != NULL && !(*env)->ExceptionCheck(env))
        (*env)->SetIntArrayRegion(env, result, 0, 8, values);
    if ((*env)->ExceptionCheck(env)) {
        if (result != NULL) (*env)->DeleteLocalRef(env, result);
        result = NULL;
    }
    pthread_mutex_unlock(&peers_lock);
    if (result == NULL) fail(env);
    return result;
}

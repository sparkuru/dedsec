#include "includes.h"
#include <string.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <openssl/crypto.h>
#include "ssh.h"
#include "log.h"
#include "misc.h"
#include "readconf.h"
#include "ssh_config_policy.h"
#ifdef CTOS_SSH_POLICY_FIXTURE
#include "ssh_file_io.h"
static const struct ctos_ssh_view *fixture_view;
void ctos_ssh_policy_fixture_view(const struct ctos_ssh_view *view) { fixture_view = view; }
#endif

static struct ctos_ssh_policy_error policy_error;

int ctos_ssh_policy_prepare(void) {
    if (policy_error.reason != CTOS_POLICY_OK) return -1;
    /* Include token expansion hashes through OpenSSL. Its first initialization
     * must not load a host or environment-selected openssl.cnf. */
    if (OPENSSL_init_crypto(OPENSSL_INIT_NO_LOAD_CONFIG, NULL) != 1)
        return ctos_ssh_policy_reject(CTOS_POLICY_CRYPTO, NULL, 0);
    return 0;
}

char *ctos_ssh_policy_shell_arg(const char *value) {
    if (value == NULL) { errno = EINVAL; return NULL; }
    size_t length = strlen(value), size = 3;
    if (length > 4096) { errno = E2BIG; return NULL; }
    for (size_t i = 0; i < length; i++) {
        unsigned char ch = (unsigned char)value[i];
        if (ch < 32 || ch == 127) { errno = EINVAL; return NULL; }
        size += ch == '\'' ? 4 : ch == '%' ? 2 : 1;
    }
    char *quoted = malloc(size);
    if (quoted == NULL) return NULL;
    char *cursor = quoted;
    *cursor++ = '\'';
    for (size_t i = 0; i < length; i++) {
        if (value[i] == '\'') { memcpy(cursor, "'\\''", 4); cursor += 4; }
        else {
            *cursor++ = value[i];
            if (value[i] == '%') *cursor++ = '%';
        }
    }
    *cursor++ = '\''; *cursor = 0;
    return quoted;
}

char *ctos_ssh_policy_proxy_command(const char *program, const char *config,
    const Options *o, const char *target, int target_port, int verbosity) {
    if (program == NULL || program[0] != '/' || config == NULL || config[0] != '/' ||
        o == NULL || o->jump_host == NULL || o->jump_host[0] == 0 ||
        target == NULL || target[0] == 0 || !ssh_valid_hostname(target) ||
        !ssh_valid_hostname(o->jump_host) ||
        (o->jump_user != NULL && !ssh_valid_ruser(o->jump_user)) ||
        target_port <= 0 || target_port > 65535 || o->jump_port > 65535 ||
        verbosity < 0 || verbosity > 3) { errno = EINVAL; return NULL; }
    char destination[1024], port[8];
    int count = snprintf(destination, sizeof(destination), "[%s]:%d", target, target_port);
    if (count < 0 || (size_t)count >= sizeof(destination)) { errno = E2BIG; return NULL; }
    snprintf(port, sizeof(port), "%d", o->jump_port);
    const char *values[6] = {program, config, o->jump_user, o->jump_extra, destination, o->jump_host};
    char *quoted[6] = {NULL};
    char *command = NULL;
    int error = 0;
    for (size_t i = 0; i < 6; i++) {
        if (values[i] == NULL) continue;
        quoted[i] = ctos_ssh_policy_shell_arg(values[i]);
        if (quoted[i] == NULL) { error = errno; goto done; }
    }
    const char *format = "%s -F %s%s%s%s%s%s%s%s -W %s -- %s";
    count = snprintf(NULL, 0, format, quoted[0], quoted[1],
        quoted[2] == NULL ? "" : " -l ", quoted[2] == NULL ? "" : quoted[2],
        o->jump_port <= 0 ? "" : " -p ", o->jump_port <= 0 ? "" : port,
        quoted[3] == NULL ? "" : " -J ", quoted[3] == NULL ? "" : quoted[3],
        verbosity == 0 ? "" : verbosity == 1 ? " -v" : verbosity == 2 ? " -vv" : " -vvv",
        quoted[4], quoted[5]);
    if (count < 0 || count >= 16384) { error = E2BIG; goto done; }
    command = malloc((size_t)count + 1);
    if (command == NULL) { error = errno; goto done; }
    snprintf(command, (size_t)count + 1, format, quoted[0], quoted[1],
        quoted[2] == NULL ? "" : " -l ", quoted[2] == NULL ? "" : quoted[2],
        o->jump_port <= 0 ? "" : " -p ", o->jump_port <= 0 ? "" : port,
        quoted[3] == NULL ? "" : " -J ", quoted[3] == NULL ? "" : quoted[3],
        verbosity == 0 ? "" : verbosity == 1 ? " -v" : verbosity == 2 ? " -vv" : " -vvv",
        quoted[4], quoted[5]);
done:
    for (size_t i = 0; i < 6; i++) free(quoted[i]);
    if (error != 0) errno = error;
    return command;
}

int ctos_ssh_policy_reject(unsigned int reason, const char *object, int line) {
    if (policy_error.reason == CTOS_POLICY_OK) {
        policy_error.reason = reason;
        policy_error.line = line > 0 ? (unsigned int)line : 0;
#ifdef CTOS_SSH_POLICY_FIXTURE
        /* These are synthetic object identities, never user file paths. */
        policy_error.object = object != NULL &&
            strcmp(object, "/ctos-policy/root.conf") == 0 ? 1 :
            object != NULL && (strcmp(object, "/ctos-policy/child.conf") == 0 ||
                strcmp(object, "/ctos-policy/.ssh/child.conf") == 0) ? 2 :
            object != NULL && strcmp(object, "/ctos-policy/.ssh/leaf.conf") == 0 ? 3 : 0;
        if (fixture_view != NULL && object != NULL) {
            char relative[513];
            if (ctos_ssh_io_resolve(object, 0, relative) == 0) {
                for (unsigned int i = 0; i < fixture_view->count; i++) {
                    struct ctos_ssh_slice path = fixture_view->files[i].path;
                    if (path.length == strlen(relative) && memcmp(path.data, relative, path.length) == 0)
                        policy_error.object = i + 1;
                }
            }
        }
#else
        (void)object;
#endif
    }
    return -1;
}

int ctos_ssh_policy_options(const Options *o, const char *object, int line) {
    if (policy_error.reason != CTOS_POLICY_OK) return -1;
    if (o == NULL) return ctos_ssh_policy_reject(CTOS_POLICY_PROVIDER, object, line);
    if (!option_clear_or_none(o->local_command) ||
        !option_clear_or_none(o->proxy_command) ||
        !option_clear_or_none(o->known_hosts_command))
        return ctos_ssh_policy_reject(CTOS_POLICY_COMMAND, object, line);
    if (o->strict_host_key_checking != -1 &&
        o->strict_host_key_checking != SSH_STRICT_HOSTKEY_YES &&
        o->strict_host_key_checking != SSH_STRICT_HOSTKEY_ASK)
        return ctos_ssh_policy_reject(CTOS_POLICY_STRICT, object, line);
    if (o->verify_host_key_dns > 0)
        return ctos_ssh_policy_reject(CTOS_POLICY_DNS, object, line);
    if (o->no_host_authentication_for_localhost > 0)
        return ctos_ssh_policy_reject(CTOS_POLICY_LOCALHOST, object, line);
    if (o->forward_agent > 0 || o->forward_agent_sock_path != NULL ||
        o->add_keys_to_agent > 0 || !option_clear_or_none(o->identity_agent) ||
        !option_clear_or_none(o->pkcs11_provider) || !option_clear_or_none(o->sk_provider) ||
        o->control_master > 0 || o->control_persist > 0 ||
        !option_clear_or_none(o->control_path) || o->forward_x11 > 0 ||
        o->forward_x11_trusted > 0 || o->hostbased_authentication > 0 ||
        o->enable_ssh_keysign > 0 || o->gss_authentication > 0 ||
        o->gss_deleg_creds > 0 || o->fork_after_authentication > 0 ||
        o->tun_open > 0 || o->enable_escape_commandline > 0 ||
        o->permit_local_command > 0 || o->proxy_use_fdpass > 0)
        return ctos_ssh_policy_reject(CTOS_POLICY_EXTERNAL, object, line);
    return 0;
}

int ctos_ssh_policy_entry(void) {
    return ctos_ssh_policy_reject(CTOS_POLICY_PROVIDER, NULL, 0);
}
int ctos_ssh_policy_trust(void) {
    return ctos_ssh_policy_reject(CTOS_POLICY_PROVIDER, NULL, 0);
}

int ctos_ssh_policy_include(const char *path, int pattern, const char *object, int line) {
    if (policy_error.reason != CTOS_POLICY_OK) return -1;
#ifdef CTOS_SSH_POLICY_FIXTURE
    if (fixture_view != NULL) {
        char relative[513];
        if (ctos_ssh_view_live(fixture_view) != 0 || ctos_ssh_io_resolve(path, pattern, relative) != 0)
            return ctos_ssh_policy_reject(CTOS_POLICY_PATH, object, line);
        if (!pattern && ctos_ssh_view_lookup(fixture_view, (const unsigned char *)relative,
                strlen(relative), CTOS_SSH_CONFIG) == NULL)
            return ctos_ssh_policy_reject(CTOS_POLICY_PATH, object, line);
        return 0;
    }
    if (path == NULL) return ctos_ssh_policy_reject(CTOS_POLICY_PATH, object, line);
    const char *relative;
    if (strncmp(path, "~/", 2) == 0) relative = path + 2;
    else if (strncmp(path, "/ctos-policy/", 13) == 0) relative = path + 13;
    else return ctos_ssh_policy_reject(CTOS_POLICY_PATH, object, line);
    size_t length = strlen(relative);
    if (length == 0 || length > 512)
        return ctos_ssh_policy_reject(CTOS_POLICY_PATH, object, line);
    unsigned int depth = 1;
    const char *segment = relative;
    for (const char *cursor = relative; ; cursor++) {
        unsigned char ch = (unsigned char)*cursor;
        if (ch == 0 || ch == '/') {
            size_t size = (size_t)(cursor - segment);
            if (size == 0 || (size == 1 && segment[0] == '.') ||
                (size == 2 && segment[0] == '.' && segment[1] == '.') ||
                depth > 8)
                return ctos_ssh_policy_reject(CTOS_POLICY_PATH, object, line);
            if (ch == 0) break;
            segment = cursor + 1;
            depth++;
        } else if (ch < 32 || ch == 127 || ch == '\\' || ch == ':' ||
            ch == '%' || ch == '~' || (!pattern && strchr("*?[]", ch) != NULL)) {
            return ctos_ssh_policy_reject(CTOS_POLICY_PATH, object, line);
        }
    }
    if (!pattern && strcmp(path, "/ctos-policy/root.conf") != 0 &&
        strcmp(path, "/ctos-policy/.ssh/child.conf") != 0 &&
        strcmp(path, "/ctos-policy/.ssh/leaf.conf") != 0)
        return ctos_ssh_policy_reject(CTOS_POLICY_PATH, object, line);
    return 0;
#else
    (void)path;
    (void)pattern;
    return ctos_ssh_policy_reject(CTOS_POLICY_PROVIDER, object, line);
#endif
}

#ifdef CTOS_SSH_POLICY_FIXTURE
void ctos_ssh_policy_reset(void) {
    memset(&policy_error, 0, sizeof(policy_error));
}
struct ctos_ssh_policy_error ctos_ssh_policy_error(void) {
    return policy_error;
}
#endif

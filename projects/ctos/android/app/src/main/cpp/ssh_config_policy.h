#ifndef CTOS_SSH_CONFIG_POLICY_H
#define CTOS_SSH_CONFIG_POLICY_H

/* First increment only: missing production providers always fail closed. */
enum ctos_ssh_policy_reason {
    CTOS_POLICY_OK = 0,
    CTOS_POLICY_MATCH_EXEC,
    CTOS_POLICY_COMMAND,
    CTOS_POLICY_STRICT,
    CTOS_POLICY_DNS,
    CTOS_POLICY_LOCALHOST,
    CTOS_POLICY_EXTERNAL,
    CTOS_POLICY_PROVIDER,
    CTOS_POLICY_PATH,
    CTOS_POLICY_CRYPTO
};
struct ctos_ssh_policy_error {
    unsigned int reason, object, line;
};
int ctos_ssh_policy_reject(unsigned int, const char *, int);
int ctos_ssh_policy_options(const Options *, const char *, int);
int ctos_ssh_policy_entry(void);
int ctos_ssh_policy_trust(void);
int ctos_ssh_policy_include(const char *, int, const char *, int);
int ctos_ssh_policy_prepare(void);
char *ctos_ssh_policy_shell_arg(const char *);
char *ctos_ssh_policy_proxy_command(const char *, const char *, const Options *, const char *, int, int);

#ifdef CTOS_SSH_POLICY_FIXTURE
struct ctos_ssh_view;
void ctos_ssh_policy_fixture_view(const struct ctos_ssh_view *);
void ctos_ssh_policy_reset(void);
struct ctos_ssh_policy_error ctos_ssh_policy_error(void);
#endif
#endif

#ifndef CTOS_SSH_KEX_GUARD_H
#define CTOS_SSH_KEX_GUARD_H
#include <stdint.h>
#include "ssh_role_bootstrap.h"
struct ssh;
struct sshkey;
/* Native launcher-owned registration only. The live bootstrap is the only
 * source of PID, hop, nonce, connection and public-view ownership. */
int ctos_ssh_kex_bind_role(struct ssh *, ctos_ssh_role_token,
    const unsigned char [32]);
void ctos_ssh_kex_unbind(struct ssh *);
/* These hooks are called by patched upstream KEX code, never by packet dispatch
 * in the application broker. Approval alone cannot manufacture completion. */
int ctos_ssh_kex_begin(struct ssh *);
int ctos_ssh_kex_approved(struct ssh *, const struct sshkey *);
int ctos_ssh_kex_signed(struct ssh *, const struct sshkey *);
int ctos_ssh_kex_complete(struct ssh *);
/* Returns a completion counter only for this live connection/role/hop. This is
 * one prerequisite for the future AppSecrets guard, not a private-key grant. */
uint64_t ctos_ssh_kex_ready(struct ssh *, unsigned int,
    const unsigned char [32], const unsigned char [32]);
#endif

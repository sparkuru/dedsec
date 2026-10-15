#ifndef CTOS_SSH_NATIVE_LAUNCHER_H
#define CTOS_SSH_NATIVE_LAUNCHER_H

#include <stddef.h>

#include "ssh_role_bootstrap.h"

struct ssh;

/*
 * This is a same-process KEX fixture boundary.  The wire is the canonical
 * CSRB emitted by ctos_ssh_role_bootstrap_encode(); the launcher does not
 * accept argv, paths, descriptors, callbacks, credentials, or endpoints.
 */
#define CTOS_SSH_NATIVE_LAUNCHER_MAX 8U

int ctos_ssh_native_launcher_admit(const unsigned char *, size_t,
    ctos_ssh_role_token, struct ssh *, const unsigned char [32]);
int ctos_ssh_native_launcher_close(struct ssh *, ctos_ssh_role_token);

#endif

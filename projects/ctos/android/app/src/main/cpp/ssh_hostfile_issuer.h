#ifndef CTOS_SSH_HOSTFILE_ISSUER_H
#define CTOS_SSH_HOSTFILE_ISSUER_H

#include <stddef.h>
#include <stdio.h>

#include "ssh_callback_adapter.h"
#include "ssh_file_view.h"

struct sshkey;

/* The issuer is deliberately a host/fixture seam until its pinned OpenSSH
 * closure is proven for the Android production library.  The output body is
 * caller-owned; a successful NEW handoff wipes it through the callback
 * contract and does not transfer a second native ownership. */
struct ctos_ssh_hostfile_issue {
    int classification;
    char key_type[CTOS_SSH_CALLBACK_HOST_KEY_TYPE_BYTES];
    char fingerprint[CTOS_SSH_CALLBACK_HOST_FINGERPRINT_BYTES];
    char candidate_id[CTOS_SSH_CALLBACK_CANDIDATE_ID_BYTES];
    unsigned char base_digest[32];
    unsigned char replacement_digest[32];
    unsigned char *replacement_body;
    size_t replacement_body_length;
    int handed_off;
};

/*
 * Classify one offered host key against the exact public KNOWN_HOSTS object in
 * `view`. Its actual selected root must be a canonical managed CONFIG object
 * directly referencing this exact `known_hosts_path` and KNOWN_HOSTS type.
 * Legacy and imported roots share these checks. `known_hosts_path` is only a
 * virtual-object selector; it is never opened or passed to OpenSSH.
 * `replacement_stream` must be a fresh,
 * caller-owned seekable in-memory stream backed by `replacement_body` (with
 * room for one terminating byte when using fmemopen).  The pinned upstream
 * formatter writes to that stream for NEW; no pathname writer is reachable.
 *
 * A non-zero callback invokes the existing one-shot native-to-managed
 * candidate handoff for NEW.  The callback consumes/wipes `replacement_body`;
 * the metadata result remains bounded and contains only digests and identity.
 */
int ctos_ssh_hostfile_issue(const struct ctos_ssh_view *view,
    ctos_ssh_role_token role, const char *known_hosts_path, const char *host,
    const struct sshkey *host_key, const char *candidate_id,
    FILE *replacement_stream, unsigned char *replacement_body,
    size_t replacement_capacity, ctos_ssh_callback_token callback,
    void *environment, struct ctos_ssh_hostfile_issue *issue);

#endif

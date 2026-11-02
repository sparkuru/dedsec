#ifndef CTOS_SSH_HOST_TRUST_H
#define CTOS_SSH_HOST_TRUST_H
#include <stddef.h>
#include <stdint.h>
#include "ssh_material_inspect.h"

#define CTOS_SSH_HOST_TRUST_RECORDS 128U
#define CTOS_SSH_HOST_TRUST_HEADER_BYTES 88U
#define CTOS_SSH_HOST_TRUST_RECORD_BYTES 216U
#define CTOS_SSH_HOST_TRUST_HOST_BYTES 253U
#define CTOS_SSH_HOST_TRUST_ALGORITHM_BYTES 128U
#define CTOS_SSH_HOST_TRUST_FINGERPRINT_BYTES 64U
#define CTOS_SSH_HOST_TRUST_WIRE_BYTES (CTOS_SSH_HOST_TRUST_HEADER_BYTES \
    + CTOS_SSH_HOST_TRUST_RECORDS * CTOS_SSH_HOST_TRUST_RECORD_BYTES + CTOS_SSH_MATERIAL_BYTES)

/* Supplied-body management projection only: no live role, trust approval or IO.
 * Caller owns and wipes/free()s CSHTv1. Output pointers clear on every failure.
 * Header88: magic/version/mode/flags/count/replacementLength, SHA256 base32,
 * replacement32 (zero when absent). Records216: line/marker/selector/reason,
 * algorithmLength/fingerprintLength, zero-padded algorithm128/fingerprint64.
 * All integers are BE u32. Flags1 canForget,2 replacementPresent; mode0 query,
 * 1 prepare. Marker0 plain/1 revoked/2 CA, selector0 literal/1 hash/2 shared,
 * reason0 removable/1 shared/2 policy/3 unsupported. Replacement follows rows.
 * Empty replacement is distinct from absent replacement via flag2. */
int ctos_ssh_host_trust_inspect(const unsigned char *body, size_t length,
    const unsigned char *host, size_t host_length, unsigned int port, int prepare,
    uint64_t deadline, unsigned char **output, size_t *output_length);
#endif

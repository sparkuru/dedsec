#ifndef CTOS_SSH_MATERIAL_INSPECT_H
#define CTOS_SSH_MATERIAL_INSPECT_H
#include <stddef.h>
#include <stdint.h>
#define CTOS_SSH_MATERIAL_BYTES (1024U * 1024U)
#define CTOS_SSH_MATERIAL_PASSPHRASE_BYTES 8192U
#define CTOS_SSH_MATERIAL_LINE_BYTES 65536U
#define CTOS_SSH_MATERIAL_LINES 16384U
#define CTOS_SSH_MATERIAL_WIRE_BYTES 60U
#define CTOS_SSH_KEY_ASSOCIATION_WIRE_BYTES 60U
#define CTOS_SSH_PRIVATE_MATERIAL_WIRE_BYTES 60U
#define CTOS_SSH_CERTIFICATE_FACTS_WIRE_BYTES 168U
enum ctos_ssh_certificate_status {
    CTOS_CERTIFICATE_FACTS, CTOS_CERTIFICATE_MALFORMED, CTOS_CERTIFICATE_UNSUPPORTED
};
enum ctos_ssh_pair_status {
    CTOS_PAIR_MATCH, CTOS_PAIR_MISMATCH, CTOS_PAIR_MALFORMED, CTOS_PAIR_UNSUPPORTED,
    CTOS_PAIR_NEEDS_PASSPHRASE, CTOS_PAIR_BAD_PASSPHRASE
};
enum ctos_ssh_pair_stage {
    CTOS_PAIR_COMPLETE, CTOS_PAIR_PRIVATE, CTOS_PAIR_PEER, CTOS_PAIR_RELATION, CTOS_PAIR_SIGNER
};
enum ctos_ssh_material_status {
    CTOS_MATERIAL_PARSED, CTOS_MATERIAL_MALFORMED, CTOS_MATERIAL_UNSUPPORTED,
    CTOS_MATERIAL_NEEDS_PASSPHRASE, CTOS_MATERIAL_BAD_PASSPHRASE
};
/* Absolute CLOCK_BOOTTIME milliseconds; admission and publication use one deadline. */
int ctos_ssh_material_deadline(uint64_t deadline);
/* Synchronous borrowed slices; output is cleared on lifecycle/system failure.
 * CSMIv1 is historical content-only metadata, never an admission capability. */
int ctos_ssh_material_inspect(unsigned int type, const unsigned char *body, size_t length,
    const unsigned char *passphrase, size_t passphrase_length, uint64_t deadline,
    unsigned char output[CTOS_SSH_MATERIAL_WIRE_BYTES]);
/* Explicit supplied pair only, not certificate trust or whole-manifest admission.
 * No caller signing payload, returned signature, persistent owner or capability. */
int ctos_ssh_material_verify_pair(unsigned int peer_type,
    const unsigned char *private_body, size_t private_length,
    const unsigned char *passphrase, size_t passphrase_length,
    const unsigned char *peer_body, size_t peer_length, uint64_t deadline,
    unsigned char output[CTOS_SSH_KEY_ASSOCIATION_WIRE_BYTES]);
/* Fixed local signing proof against this private container's own public fields.
 * CSKPv1 does not establish an external pair, trust or credential ownership. */
int ctos_ssh_material_verify_private(const unsigned char *body, size_t length,
    const unsigned char *passphrase, size_t passphrase_length, uint64_t deadline,
    unsigned char output[CTOS_SSH_PRIVATE_MATERIAL_WIRE_BYTES]);
/* Public certificate facts and embedded signature policy only; no CA trust,
 * current validity, principal applicability, revocation or admission claim. */
int ctos_ssh_material_inspect_certificate(const unsigned char *body, size_t length,
    uint64_t deadline, unsigned char output[CTOS_SSH_CERTIFICATE_FACTS_WIRE_BYTES]);
#endif

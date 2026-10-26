#ifndef CTOS_SSH_KEY_POLICY_H
#define CTOS_SSH_KEY_POLICY_H
#include <stddef.h>
struct sshkey;
/* Shared authentication policy. No decryption, signing or external providers. */
int ctos_ssh_key_software_identity(const struct sshkey *key);
int ctos_ssh_key_encrypted_policy(const unsigned char *bytes, size_t length);
#endif

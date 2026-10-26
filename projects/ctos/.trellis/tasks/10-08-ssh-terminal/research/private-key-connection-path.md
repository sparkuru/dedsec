# Research: Production private-key SSH connection path

- Query: Which existing Java/JNI/native capabilities can support a real key-only SSH login, and what must be added before config/private-key UI can use them?
- Scope: internal; source inspection only
- Date: 2026-10-26
- Active task: `.trellis/tasks/10-08-ssh-terminal`
- Trigger: the user reports that `wkyuu@192.168.9.5` rejects password authentication. No connection to that host was made, and no personal SSH files were inspected.

## Findings

### 1. The actual private-key authentication engine already exists

`android/app/src/main/java/im/majo/ctos/SshAuthenticationSelection.java:41` has
`privateKey(role, username, path, keyIdentity, publicKeyBlob, clock)` and an overload at
`:47` taking `passphraseEntryId`. The selection owns copies of the exact username and
raw public-key blob and binds the same role object. Its metadata is encoded as CSAS v1
for an unencrypted key (empty phrase entry ID), or v2 for a nonempty selected phrase ID
(`:64–96`). This is raw SSH public-key binary, not an authorized_keys text line, SHA256
digest, filename, or certificate.

`android/app/src/main/java/im/majo/ctos/SshNativeSession.java:739` exposes
`beginPrivateKeyAuth()` after actual KEX and `ssh-userauth` service readiness.
`privateKeyAuthResult()` at `:762` yields NOT_STARTED/PENDING/SUCCESS/REJECTED/PARTIAL;
`beginShell()` at `:797` accepts private-key SUCCESS as well as password SUCCESS.
The existing terminal owner/PTY/shell path can therefore be reused.

`android/app/src/main/cpp/ssh_native_client.c:1095` implements real signed USERAUTH.
It parses the selected expected public blob (`:1122`), chooses the actual algorithm
(RSA SHA2 exact server advertisement intersection, `:1130`), requests private material
through the selected proof-bound provider once (`:1144`), parses the actual private key
in memory (`:1156`), compares actual public identity (`:1174`), signs the framed raw
initial SSH session ID (`:1178`), verifies its own signature (`:1199`), then serializes
the actual SSH USERAUTH request (`:1205`). Owned key, phrase, signature and scratch
buffers are wiped/freed on all exits (`:1223–1237`).

No new native USERAUTH transport or second SSH client implementation is needed.
No password attempt should precede a deliberately selected key-only connection.

### 2. The production connector is still exclusively a password connector

`android/app/src/main/java/im/majo/ctos/SshPasswordConnector.java` constructs a password
selection and unconditionally calls `session.beginPasswordAuth()` around `:198`.
`SshPasswordConnectBridge.java:274` constructs that connector from a password-only
channel contract. The existing saved-password work is useful shared infrastructure
but does not enable key-only authentication by itself.

The minimum real key connection can reuse its TCP/FD, exact NEW/KNOWN host-key mailbox,
CAS continuation, original validator/deadline pin, role issuance, cancellation and
terminal handoff, while parameterizing the selected identity and auth-begin/result
step. Copying those lifecycle/trust sections into a divergent second connector would
increase review burden and risk drift.

### 3. Typed private-key reads already enforce the actual host/KEX boundary

`android/app/src/main/java/im/majo/ctos/SshCredentialBridge.java:151` chooses a plain
private-key read if the selected phrase ID is empty, or a private-key bundle read if
it is nonempty. `openSelection()` at `:178` dispatches immutable private selections.
The release guard at `:222` checks original request, exact selection object, role,
path, key identity, phrase entry ID, host proof and current signed KEX before copy.
The final release calls are at `:254–264`.

`android/app/src/main/java/im/majo/ctos/SshManagedProvider.java:182` accepts the selected
identity only after host/KEX proof. Its ordinary selected private-key path uses
`credentialBridge.openSelection(...)` and clones the bounded borrowed bytes only
inside the guarded receiver (`:196–233`). It must remain separate from legacy
kind-only credential selection.

`android/app/src/main/java/im/majo/ctos/AppSecrets.java:2977` has `beginSshPrivateKey`
and `:2983` has its release. The encrypted bundle APIs are at `:2995` and `:3011`.
`requirePrivateKeyBundle()` at `:3002` checks actual PRIVATE_KEY type/path/identity and
the exact SSH_KEY_PASSPHRASE entry's `identity == "key:" + keyIdentity`.

The existing bundle wire is CSKB v1, not CSPK: magic `0x43534b42`, version1,
u32 key length+key bytes, u32 phrase length+phrase bytes. AppSecrets constructs it at
`:2653–2665`; native validates exact consumption and encrypted policy at
`ssh_native_client.c:1078–1092`. Phrase must be nonempty, <=8192 bytes and contain no
NUL; the total packed material must fit `MAX_FILE_BYTES`.

### 4. Public view deliberately never contains private-key bytes

`android/app/src/main/java/im/majo/ctos/SshFileSnapshot.java:38–54` projects PRIVATE_KEY
metadata into a file closure, but omits private bodies from `publicFiles`.
`SshPublicFileLease.java:60–115` serializes private metadata and a zero body length.
Adding real private bytes to that view would break the established boundary.

The imported CONFIG must directly reference the selected private-key logical path
so the public closure contains the correct private metadata. Actual bytes should
still come exclusively from the post-KEX typed private getter.

### 5. Missing production primitive: return an actual raw public blob

The three current material interfaces all expose fixed public metadata/digests:

- `SshManifestInspector.java:44–108`: `publicBlobDigest`, fixed60-byte CSMI record.
- `SshManifestKeyAssociationInspector.java:57–105`: `publicSubjectDigest`, fixed60-byte CSKA record.
- `SshManifestPrivateMaterialInspector.java`: fixed CSKP self-signing proof metadata.

None is a raw public-key export API. The native implementation does know the key:
`ssh_material_inspect.c:193` has internal `parse_private(...)`, and `:514–538` derives
its own public subject and performs a real local signing proof. `sshkey_to_blob` is
already used internally at `:73`.

A narrowly scoped import/selection helper should reuse those pinned parser/policy
functions, derive the public subject from the actual supplied private key, and return
the canonical bounded raw public blob plus typed format/outcome metadata. JNI must
copy from exclusive input buffers, retain original BOOTTIME deadline/cancel checks,
and wipe all private/passphrase copies before returning. A public blob from an
actual supplied `.pub` can also be parsed and mathematically proved against the
private key, but requiring a separate `.pub` should not be the only way to import a
normal private-key file.

Do not fabricate an expected key, substitute a hash for the blob, or claim that
Base64 decoding an OpenSSH private envelope proves its internal private/public match.
Even after extraction, the actual connection's native equality/sign/local-verify
checks remain mandatory.

### 6. Missing production primitive: one-use private-key passphrase

Current ephemeral secret support is only
`android/app/src/main/java/im/majo/ctos/SshTransientPassword.java:23–93`, routed through
`SshCredentialBridge.hasTransientPassword()` and `SshManagedProvider.java:192–195`.
There is no transient private-key/passphrase source in the inspected production code.
The current encrypted-key selection assumes a persisted phrase entry and v2 CSKB
bundle. Requiring users to persist their phrase to use an encrypted private key
would violate the existing default-only-this-use product contract.

A host-owned one-use phrase source can follow the original deadline/cancel, exact
role/selection/path/key identity, host/KEX proof and final guarded-copy pattern of
the transient password. It would combine the actual typed vault private-key bytes
with the exact selected transient phrase inside the guarded native handoff, using
the same CSKB format and native policy. This requires an explicit source mechanism;
it cannot pretend a transient phrase ID is a persisted AppSecrets entry. An
alternative is a host-owned one-use private-material bundle lease; it must not
publish private or phrase bytes through Flutter or the public file view.

### 7. Production admission and account binding need real extension

The current `SshPasswordProfile.checkShape()` at `:135–151` accepts only its two fixed
CONFIG/KNOWN_HOSTS files, while `SshSavedPassword.checkAccounts` only admits canonical
PASSWORD Accounts. `SshPasswordManifestValidator.java:57–68` expects exactly those
two native material outcomes. Adding an import button alone will therefore fail
public read, writes and unrelated TOTP operations once private material is present.
Do not replace this with an empty validator or promote the partial inspectors into
whole-graph admission.

The sole model already has the required shape:
`VaultSshMetadata.java:13–36` defines KeyBinding(private file, optional public file,
certificate IDs, optional phrase entry), and `:39–65` defines Account(host, port,
username, KEY auth, credentialId=KeyBinding ID). Structural reference/type/owner
checks exist at `:81–106`; they do not establish material identity or connection
authority. Imported files, KeyBinding and KEY Account must be committed together
under the existing original-context/candidate/effect lifecycle. Existing
`SshBundleProposal.apply` calls `previous.withObjects(...)`, preserving existing
metadata but not adding new KeyBinding/Account records by itself.

The new selected key consumer should enforce actual Account tuple + KeyBinding +
selected path/identity/public subject/phrase ownership at admission and final
release, analogous to the new saved-password read context. Existing generic
private reads bind path/keyIdentity/target/host proof but do not currently validate
the latest KEY Account against role host/port and selection username.

Encrypted keys without a persisted phrase need an explicit admissibility contract:
the static whole-vault validator cannot repeatedly decrypt them during unrelated
reads. Separate static file/config/key/account obligations from the actual selected
connection's mandatory runtime decrypt/sign proof; a partial inspector report or
persistent frontend `validated` flag must not silently waive either obligation.

### 8. Native formats and current caveats

`ssh_key_policy.c:55–63` allows plain software Ed25519/ECDSA/RSA, rejects cert/SK/EXT
and enforces the pinned RSA minimum. RSA auth requires SHA2 server-signature
advertisement; no SHA1 fallback.

`ssh_key_policy.c:83–119` recognizes bounded legacy encrypted RSA/EC PEM with exact
AES-128-CBC/AES-256-CBC headers; `:122–177` recognizes OpenSSH aes256-ctr/bcrypt,
one key, salt<=64 and rounds1..64. Arbitrary encrypted PKCS8 remains rejected.

Important: `ssh_material_inspect.c:203–209` in strict self-proof/pair mode currently
rejects encrypted non-OpenSSH containers even though auth policy accepts bounded
legacy encrypted PEM. The weak content inspector permits those PEM forms but does
not certify full decoded-DER consumption. A new robust public-export/import helper
must resolve this distinction, or report that material form explicitly unsupported;
do not infer all authentication-supported formats already have full import proof.

Auth is one selected method/key attempt per session. Rejected/partial/wrong phrase
is terminal for that session (`ssh_native_client.c:1112–1115`, spec around `:945`);
retries must create a new attempt. Multiple IdentityFile fallback and method
negotiation are additional product behavior, not already implemented automatically.

### 9. Existing Android evidence is useful but synthetic

`android/app/src/androidTestSecretsFixture/java/im/majo/ctos/SshPasswordAuthPlatformTest.java:234–240`
obtains identityPublic/private from `peer.identityMaterial()`; that is why the
fixture already has the required raw public blob. Its selection is built at
`:309–313`; do not call its peer from production.

The fixture includes unencrypted all-five-key outcomes (`:675–688`), encrypted
all-five-key success/wrong-phrase rejection (`:691–710`), NEW host CAS continuation
with private auth (`:713–744`), and shell round trips. The all-five matrix represents
Ed25519, ECDSA p256/p384/p521 and RSA. It proves the real JNI/protocol path with
synthetic peer inputs, not the missing SAF/config/key importer or an independent
key-only sshd and UI login.

For the new path, add an independent key-only server fixture and actual product
bridge/UI flow: import selected key/config, show correct effective account/key
fingerprint, actual NEW review, key USERAUTH SUCCESS, PTY command/resize/close,
KNOWN reconnect; encrypted key should cover right/wrong/cancelled transient phrase.
Keep source/fixture/APK hashes and runtime cleanup evidence separate from JVM mocks.

### 10. Suggested minimum coherent implementation order

1. Add bounded native public extraction/proof from actual supplied key bytes and a
   Java owner wrapper. Preserve existing fixed CSMI/CSKA/CSKP protocols unchanged.
2. Define imported file/KeyBinding/KEY Account admission, atomic mutation and final
   selected source binding on the sole manifest. Preserve all saved-password/TOTP/
   known_hosts data and original request guards.
3. Generalize the existing connector's auth selection step and reuse its exact
   managed host/TCP/session lifetime. OpenSSH public-key authentication is already
   present; do not add a second transport.
4. Add the one-use encrypted-key phrase path and explicit optional save, keeping
   phrase data out of metadata, logs, public view and frontend responses.
5. Connect actual config identity resolution/manual identity choice and SAF import,
   then independent key-only interop and authorised Android acceptance.

## Files Found

| Path | Role |
| --- | --- |
| `android/app/src/main/java/im/majo/ctos/SshAuthenticationSelection.java` | Exact role-owned auth metadata and public blob |
| `android/app/src/main/java/im/majo/ctos/SshNativeSession.java` | JNI private auth begin/result and SUCCESS-only shell |
| `android/app/src/main/cpp/ssh_native_client.c` | Real direct signed publickey USERAUTH |
| `android/app/src/main/cpp/ssh_material_inspect.c` | Existing private parse, subject digest and self-sign proof |
| `android/app/src/main/cpp/ssh_key_policy.c` | Shared software key and bounded encrypted-container policy |
| `android/app/src/main/java/im/majo/ctos/SshCredentialBridge.java` | Exact selected typed key/phrase release |
| `android/app/src/main/java/im/majo/ctos/SshManagedProvider.java` | Host/KEX-bound credential scratch handoff |
| `android/app/src/main/java/im/majo/ctos/SshFileSnapshot.java` | Private metadata-only public closure |
| `android/app/src/main/java/im/majo/ctos/SshPublicFileLease.java` | Native public view serialization/ownership |
| `android/app/src/main/java/im/majo/ctos/SshTransientPassword.java` | Existing ephemeral secret lifecycle pattern |
| `android/app/src/main/java/im/majo/ctos/VaultSshMetadata.java` | Sole-model KeyBinding and KEY Account structural contracts |
| `android/app/src/main/java/im/majo/ctos/SshPasswordProfile.java` | Current password-only manifest shape restriction |
| `android/app/src/main/java/im/majo/ctos/SshPasswordManifestValidator.java` | Current exact-two-public-file native admission |
| `android/app/src/main/java/im/majo/ctos/AppSecrets.java` | Original guarded reads/mutations and CSKB wire construction |
| `android/app/src/androidTestSecretsFixture/java/im/majo/ctos/SshPasswordAuthPlatformTest.java` | Existing synthetic real JNI/key-auth/NEW/shell matrix |

## Related Specs

- `.trellis/spec/backend/ssh-native.md`: actual private auth and selected-key proof contract; retain later encrypted-key additions rather than interpreting older no-private statements as current.
- `.trellis/spec/backend/ssh-manifest.md`: one latest model/writer, explicit metadata, actual-content restore review.
- `.trellis/spec/backend/app-secrets.md`: original request/deadline/cancel/validator registration, detached loans, final guarded copy and atomic/uncertain effects.
- `.trellis/spec/backend/ssh-private-material.md` and `ssh-key-association.md`: fixed self-sign/pair proofs are partial and do not grant graph admission.
- `.trellis/spec/backend/ssh-password-connect.md`: managed trust/TCP/owner/own-CAS/handoff mechanics to preserve.

## External References

No external web sources were needed for this source-backed investigation. The
repository pins OpenSSH 10.6p1 and OpenSSL 3.5.9; existing native code and project
specs were treated as implementation evidence, not a claim about newer upstream
versions or server configuration.

## Caveats / Not Found

- No production raw-public-blob derivation/export API was found.
- No production one-use private-key passphrase provider was found.
- No product SAF key/config import or effective private-key connector is enabled by
  the current password form.
- `SshConfigEvaluator.Summary` currently exposes identity/certificate counts and
  endpoint fields, not the effective IdentityFile path list (`:176–205`); a config
  connector must obtain that authoritative list from native Options rather than
  invent a Dart/Java config evaluator.
- No builds, tests, network/server/device access, personal credentials, commits or
  product edits were performed by this research agent.

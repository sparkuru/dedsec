# Research: receipt-bound private-key USERAUTH

- Query: Continue immediately after Android/JNI password orchestration with a complete, locally testable private-key authentication design; identify pinned APIs, algorithms, proof/secret lifetimes, passphrase blockers, and acceptance evidence.
- Scope: internal; read-only production/pinned source inspection, no build, network, device, real vault or account access.
- Date: 2026-10-19

## Findings

### Recommendation and decisions needed

Implement an explicit one-attempt **direct signed publickey request** using the frozen CSAS PRIVATE_KEY selection. The first implementation can cover unencrypted OpenSSH Ed25519/ECDSA/RSA plus the applicable unencrypted PEM combinations, retaining fail-closed encrypted-key handling until an independently bound passphrase capability exists. This is a useful implementation stage, not a reduction of the approved product target: `.trellis/tasks/10-08-ssh-terminal/prd.md:29` and `design.md:34,49` require RSA/Ed25519/ECDSA, encrypted keys, applicable PEM, and keyboard-interactive overall.

Main must freeze three policy choices before implementation:

1. RSA negotiation: recommend SHA2 only, prefer `rsa-sha2-512` then `rsa-sha2-256`, require a matching exact token in advertised `server_sig_algs`; reject absent/empty/no-mutual advertisements without releasing the key. This intentionally does not copy stock legacy SHA1 fallback or OpenSSH 7.4 workaround, so document the compatibility boundary. An alternative explicit SHA2 default for servers without the extension needs its own approved contract and tests.
2. RSA minimum: upstream minimum is **1024**, not 2048 (`sshkey.h:47`, `sshkey.c:1455-1468`). Either preserve pinned upstream minimum or explicitly choose a stronger product minimum and test it; do not silently claim a 2048 upstream rule.
3. Result API: separate `begin_private_key_auth` / const `private_key_auth_result` preserves current password API, or generalize to a method-tagged auth result with compatibility wrappers. Either way, one shared attempt must prevent password and private-key crossover; no retries/method fallback. Suggested private-key outcomes are NOT_STARTED/PENDING/SUCCESS/REJECTED/PARTIAL; local parse/key mismatch/unsupported/passphrase-required are precise local errors or separately typed local status, never remote REJECTED or SUCCESS. Message 60 is PK_OK only in a probe phase; direct signed mode must reject it as unexpected, never interpret it as password-change-required.

### Files found

All upstream short paths below are relative to the actually present pinned source root `build/ssh-native-g1/stock-run.lOHF9Oai/sources/openssh-10.6p1/`.

| Path | Purpose |
| --- | --- |
| `sshkey.c`, `sshkey.h` | Bounded in-memory public/private parsing, comparison, signing/verification, owned key cleanup |
| `sshconnect2.c` | Reference-only stock publickey request construction, algorithm selection and PK_OK handling; excluded high-level caller |
| `ssh-rsa.c`, `ssh-ecdsa.c`, `ssh-ed25519.c` | Actual algorithm sign/verify implementations and cleanup |
| `sshbuf.c` | Owned writable buffer zeroing versus readonly borrowed buffers |
| `kex.c`, `kex.h` | `server-sig-algs` storage and upstream EXT_INFO handlers |
| `android/app/src/main/cpp/ssh_native_client.c` | Current owned CSAS, one-shot selected handoff, password packet/result state machine |
| `android/app/src/test/cpp/ssh_native_client_test.c` | Real encrypted OpenSSH socketpair peer and password reply/fragmentation/lifecycle matrix |
| `android/app/src/main/java/im/majo/ctos/SshAuthenticationSelection.java` | Frozen role/username/path/keyIdentity/expected-public declaration |
| `android/app/src/main/java/im/majo/ctos/SshCredentialBridge.java` | Exact selected resource plus host/KEX/vault final effect guard |
| `android/app/src/main/java/im/majo/ctos/AppSecrets.java` | Current private-key capability and target-bound generic credential capability |
| `android/app/src/main/java/im/majo/ctos/VaultManifest.java` | Key-passphrase entry identity is `key:` plus private-file identity |
| `.trellis/tasks/10-08-ssh-terminal-client/research/android-production-openssh-build.sh` | Production PIC archive closure, source/hash checks, excludes stock caller |
| `android/app/build.gradle:158-246,335-357` | Current production archive inputs and native host test link closure |

### Existing selection/proof boundary

`ssh_native_client.c:186-239` validates all five CSAS length-prefixed fields and copies/hash-owns the exact wire. PRIVATE_KEY uses empty entry ID, bounded path and keyIdentity, and 1..1MiB expected public blob. Only username offset/length are retained currently (`:237-238`); implementation must safely retain public-blob offset/length at successful admission, or reparse owned CSAS with the same strict cursor. Never read the original caller buffer. The role token/hop/nonce/binding, kind and all fields are part of the receipt.

`selected_credential_is_live` (`:698-724`) checks READY, completed KEX, one completion, current raw session ID digest and service liveness. `selected_credential_bytes` (`:726-779`) uses private scratch, consumes once immediately before selected callback, checks proofs after callback, copies to caller only after checks, and clears scratch on every outcome. Generic handoff remains an existing explicit stopping point; once it has consumed the shared flag, private-key auth must return EALREADY without another release. Allocations or invalid owner preflight before consumption keep the existing retry/non-poison rules.

`password_auth_proof` (`:783-825`) also verifies current role owner/deadline/view, `ctos_ssh_kex_ready` against same hop/nonce/binding, completion cycle, and SHA256(raw session ID) against saved digest. Its PASSWORD-only predicate must be generalized or privately duplicated with an exact PRIVATE_KEY predicate. Every active auth step and const result read needs the same checks; do not bypass it by treating earlier selected callback success as durable authorization. `password_output_is_clear` (`:837-843`) requires no queued output/no rekey before auth construction. Preserve it for private-key construction and verify again immediately before queueing.

The callback receipt and SHA256 session digest are authorization metadata. **Neither is the SSH signature input session ID.** Use the actual `ssh->kex->session_id` bytes after checking their digest against the accepted completion proof, and repeat proof/liveness after parse/sign and immediately before packet enqueue/public outcome. KEX 20..49 remains disabled in this slice; no rekey or fresh authentication proof is silently admitted.

### Pinned parser/comparison APIs

- `sshkey_from_blob(const u_char *,size_t,struct sshkey **)` (`sshkey.c:2080-2089`) wraps the borrowed blob and rejects trailing content through `sshkey_from_blob_internal:2063-2065`. It accepts certificates unless the caller rejects them. Parse the expected public declaration **before requesting a private secret**, check allowed plain key type, supported curve/RSA minimum and signature negotiation first.
- `sshkey_parse_private_fileblob(struct sshbuf *,const char *passphrase,struct sshkey **,char **commentp)` (`sshkey.c:3702-3711`) is fully in memory. `parse_private_fileblob_type:3679-3698` first parses OpenSSH format and falls back to PEM only on INVALID_FORMAT. Pass `NULL` or `""` for the unencrypted stage and `NULL` for unused comment output. Do not call stock `load_identity_file`, `sshkey_load_private_type`, `stat`, prompt, agent, or pathname-based APIs.
- OpenSSH parsing checks one key (`private2_decrypt:3086-3089`), rejects extra decoded binary data (`:3164-3169`), verifies checkints/padding and internal envelope public/private agreement (`parse_private2:3230-3242`). Its text uudecoder finds the ending marker; do not claim that arbitrary text appended after an end marker is universally rejected by this API (`private2_uudecode:2998-3031`). If product strict outer-file framing is desired, define it separately rather than claiming upstream provides it.
- Empty passphrase plus encrypted OpenSSH KDF returns `SSH_ERR_KEY_WRONG_PASSPHRASE` without bcrypt (`:3108-3112`). PEM uses the noninteractive `pem_passphrase_cb` (`:3524-3535`) and maps crypto errors (`:3506-3520,3559-3572`). Wrong passphrase and certain corrupted formats are not a reliable universal encrypted-format detector: report conservative local failure; do not automatically ask for a password from a prompt/error string.
- `sshkey_equal_public(expected,private)` (`sshkey.c:822-831`) compares only public material and intentionally permits certificate/plain comparisons. Explicitly reject certificates/security-key/external types **before** it. Exact keyIdentity/path alone is not cryptographic agreement. After parse, enforce plain KEY_ED25519, KEY_ECDSA or KEY_RSA, reject `SSHKEY_FLAG_EXT`/`sshkey_is_sk` and certificates, check RSA minimum and `equal_public`, then sign. Public envelope inconsistency is already rejected upstream; the external CSAS expected-public match is an additional mandatory check.
- Unencrypted PEM parsing supports RSA/EC and conditionally ED25519 under `OPENSSL_HAS_ED25519` (`sshkey.c:3574-3658`). The source conditional is not build/runtime evidence for PEM Ed25519. Verify each advertised combination against this production closure before claiming it supported.

### Algorithms and exact signed wire

`sshkey_sign(key,&signature,&length,data,datalen,alg,NULL,NULL,compat)` (`sshkey.c:2212-2247`) delegates to built-in algorithms, or SK/PKCS11 if not excluded first. `sshkey_check_sigtype(signature,length,alg)` (`:2193-2208`) validates the returned signature algorithm; use it even though local built-in signing is expected to match. Recommend mandatory local `sshkey_verify` against the expected public key before queueing, while the synthetic server must independently verify the real packet. In particular, `ssh-ed25519.c:121-139` deserializes the public field and 64-byte private field with length checks but does not recompute the public key from the seed: envelope equality plus `equal_public` alone is not a mathematical private/public consistency proof for deliberately inconsistent serialized fields. A local signature verification detects this case without sending an unusable authentication packet; add a mutated-secret-field fixture. Both signing and verification bound input to 1MiB (`sshkey.h:48`; `sshkey.c:2225,2264`). Bound serialized public blob/signed request accordingly; the loose 1MiB CSAS declaration cap does not mean a 1MiB total signed input is automatically available after framing.

| Plain key | Proposed first-stage signature algorithm | Pinned support |
| --- | --- | --- |
| Ed25519 | `ssh-ed25519` | `ssh-ed25519.c:146-178`; private component required; temporary stack signature erased |
| ECDSA p256/p384/p521 | `ecdsa-sha2-nistp256`, `ecdsa-sha2-nistp384`, `ecdsa-sha2-nistp521` from exact key curve | `ssh-ecdsa.c:48-52,103-108,314-356` |
| RSA | explicitly selected `rsa-sha2-512` or `rsa-sha2-256`; public blob still `ssh-rsa` | `ssh-rsa.c:314-336,400-449`; NULL/empty alg silently chooses SHA1 at `:417-420`, so NEVER pass NULL for RSA |

Stock `key_sig_algorithm` (`sshconnect2.c:1166-1211`) uses the base algorithm for non-RSA, for absent extension, and for one certificate compatibility case. For RSA with advertisement it intersects caller-configured allowed algorithms against `ssh->kex->server_sig_algs`, with a 7.4 workaround. The ctOS direct implementation must choose an explicit allowlist; do not invoke this static helper or introduce stock `options`/high-level caller closure. `kex.c:465-473` accepts and stores `server-sig-algs` after rejecting NUL; exact comma token matching still belongs to selection policy. Snapshot the chosen algorithm before private release and never renegotiate/fallback after the shared attempt is consumed. Unknown or empty tokens cannot confer algorithm support. No certificate, FIDO/SK, agent, PKCS11, hostbased, DSA, RSA/SHA1, hostbound-v00, or legacy non-string session ID is implicitly supported.

Reference wire in `sshconnect2.c:1413-1429,1465-1479`:

```
string raw_initial_session_id
byte   SSH2_MSG_USERAUTH_REQUEST (=50)
string exact_owned_CSAS_username
string "ssh-connection"
string "publickey"
boolean TRUE
string chosen_signature_algorithm
string canonical_public_key_blob
```

Sign exactly these bytes; append an SSH string containing the **complete OpenSSH signature blob** returned by `sshkey_sign`, not raw Ed25519/RSA bytes. Packet wire is that buffer after removing session-ID framing and request-type byte, sent using `sshpkt_start(50)/sshpkt_putb/sshpkt_send`. The serialized public blob can be generated canonically from the validated expected public key or parsed private key after public equality; use the same exact bytes in signature input and packet. The private key itself/passphrase is never put in a packet.

Stock supports `SSH_OLD_SESSIONID` raw unframed session ID (`sshconnect2.c:1413-1419`) and hostbound signatures (`:1334-1338,1431-1437`). First stage should explicitly reject the old-session-ID compatibility mode and use ordinary `publickey`; documenting this is more honest than copying one wire shape and claiming all stock compatibility.

### Probe choice and state/result isolation

Direct signed request is already a pinned stock path for identities without a loaded public key (`sshconnect2.c:2024-2030`) and avoids a new multi-message pre-secret probe state. It still requires verified host/current KEX and explicit frozen selection before releasing the key. Only one signed USERAUTH request and one credential callback occur; every post-consumption failure is terminal, including parse failure, mismatch, unsupported encrypted key, sign failure, cancellation, expiry, or malformed response.

If later adding an unsigned probe to avoid decrypting an unaccepted key, use a separate PROBE_PENDING/AWAIT_PK_OK/SIGNED_PENDING/AWAIT_RESULT state machine. Stock probe is `send_pubkey_test:1494-1530`; `input_userauth_pk_ok:695-721` strictly parses algorithm/blob/end, checks algorithm allowlist and decoded type, then matches an outstanding identity (`:729-745`). ctOS has only one selected identity, so require **exact offered algorithm and offered public blob**, not any equal key or any allowed algorithm. PK_OK neither authenticates nor grants credentials absent current proofs. FAILURE before PK_OK ends rejected/partial without secret release; SUCCESS before signed request must fail closed. Do not reuse password message-60 decoding.

For direct mode retain request-output drain before accepting final SUCCESS/FAILURE, bounded BANNER/DISCONNECT parsing, strict empty SUCCESS payload and strict FAILURE framing/method-list bounds. Reuse current password bounds and partial result semantics (`ssh_native_client.c:906-955`) without conflating password CHANGE_REQUIRED with private-key PK_OK. Wrong/early/duplicate message60, INFO_REQUEST, rekey and SERVICE_ACCEPT are EPROTO. Existing `complete()` remains initial KEX, service-ready remains service milestone, auth success is only the explicit method result; channel readiness is still a separate later concern. Do not infer packet-level authenticated/compression transitions: current C password slice does not call `ssh_packet_set_authenticated`; channel/compression lifecycle needs a shared auth-completion design before broadening beyond this stopping point.

### Secret and proof lifetime

Recommended bounded local sequence:

1. Owner/env/closed/failed/method/READY/shared-attempt preflight; parse/validate owned expected public metadata, select algorithm, allocate key scratch and writable bounded buffers. Failures before provider consumption do not consume key grant; resource errors can retry according to existing contract.
2. Call existing receipt-bound selected PRIVATE_KEY handoff once with max 1MiB scratch. Never return a private key through a Java session API and never use public-file views for secret material.
3. Wrap scratch with `sshbuf_from` (readonly borrowed) or copy into a writable owned sshbuf. Parse privately, immediately wipe/free original raw scratch and any writable copied raw buffer once parsing is done; `sshbuf_free` does **not** erase external memory wrapped by `sshbuf_from` (`sshbuf.c:190-192`). Avoid keeping both representations longer than needed.
4. Reject unsupported key forms, enforce public equality, recheck accepted raw-session digest/current cycle/owner/view/deadline/cancel. Construct bounded signed-data buffer; sign once with explicit algorithm, check signature type and verify locally against the expected public key. Repeat proof and output-clear guard after signing/verification; queue only after all checks pass. A synchronous sign can overrun a deadline, so the post-sign deadline check matters; no claim of interruptible CPU signing.
5. On every exit free expected/private `sshkey`, freezero signature, free writable sshbuf, wipe/free remaining scratch, zero lengths/metadata scratch; preserve precise error. Private key object should be freed **before begin returns**, rather than kept while waiting for network response. Signature and public signed-data are reusable authentication material and should still get bounded cleanup, although only the private key/passphrase are secret.

Pinned cleanup: writable `sshbuf_free` uses `freezero` and reset uses explicit wipe (`sshbuf.c:161-215`); `sshkey_free` invokes type cleanup then freezero struct (`sshkey.c:767-788`). Ed25519 private storage uses freezero (`ssh-ed25519.c:35-38`); RSA/ECDSA cleanup delegates to `EVP_PKEY_free` (`ssh-rsa.c:54-56`, `ssh-ecdsa.c:115-117`). This is source-level ownership/cleanup evidence, not dynamic proof that every upstream/OpenSSL allocation is wiped. OpenSSH parser itself makes temporary decoded/decrypted buffers and frees them (`sshkey.c:3255-3260`). No logs of private bytes, passphrase, full signed packet, signature or unbounded remote text.

### Encrypted-key/passphrase capability blocker

The current selected wire kind is PASSWORD or PRIVATE_KEY only, and selected callback shares one grant flag. `SshCredentialBridge.openSelection:142-149` has no selected passphrase field/callback. Reusing its old KEY_PASSPHRASE kind would violate both frozen selection and one-shot constraints.

More concretely, `VaultManifest.java:213-217` requires key-passphrase entry identity `"key:" + private_file.identity`. Generic `AppSecrets.beginSshCredential:682-689` and `sshCredential:698-705` require `entry.identity == target`; bridge passes the actual remote `role.targetIdentity` (`SshCredentialBridge.java:193-196`). Passing `key:identity` as the operation target would lose target/role binding; changing the manifest identity to remote target would violate manifest key association. This is a real contract mismatch, not a native parser limitation. `beginSshPrivateKey/sshPrivateKey` already separately bind path+keyIdentity, remote target and connection (`AppSecrets.java:770-790`).

The encrypted stage needs an explicit, separately designed capability binding **both** key identity and remote target/role/current selection, plus an immutable optional passphrase-entry selection and a new canonical wire version/receipt or equivalent receipt-bound metadata. It needs two distinct one-shot typed effects (private file and its associated passphrase) or one explicitly reviewed atomic composite effect; do not reset the current shared selected-attempt flag. A new AppSecrets purpose/API should verify exact passphrase entry type, `key:identity` association, private-file identity, target/connection, epoch/generation, current role/host/KEX, operation consumption and final effect guard. The user may authorize no saved passphrase; cancellation/missing association must fail closed, never use remote prompt text or automatically fall back to a password entry. Passphrase bytes require explicit bound/no embedded NUL before forming a temporary NUL-terminated C string because pinned parser uses strlen (`sshkey.c:3108,3138-3139,3529`). KDF work must remain bounded, with deadline checks after parse; upstream allows up to 2^20 rounds (`sshkey.c:76,3134`), which is a ceiling and not a measured mobile latency guarantee.

### Production archive and synthetic validation path

No stock `sshconnect2.o` or `ssh_userauth2` is required. Current production evidence member list `build/app/generated/sshProduction/arm64-v8a/libctos_ssh_production.a.evidence.archive-members:5,63,65,68,75,78` already contains authfile/ecdsa/ed25519/rsa/sshbuf/sshkey. The fixed production builder rebuilds all baseline libssh PIC members and adds PKCS11/SK link stubs (`android-production-openssh-build.sh:252-317,344-358`); existing normal native test link adds libopenbsd-compat and pinned libcrypto (`android/app/build.gradle:335-357`). This source/member evidence makes reuse plausible without archive membership changes, but **no link or runtime was performed in this research**. Keep archive=83, forbidden caller definitions=0 and package contents audits; actual new symbol closure must be built by main.

Reuse real `fixture.server` and `pump_synthetic_server` (`ssh_native_client_test.c:1293-1337`), KNOWN and NEW/CAS service fixture, small step budget and existing callback event/proof counters. Current PRIVATE_KEY setup deliberately supplies invalid placeholder `"\\0public\\xff"` (`:919-921`), so add a new purpose-built auth fixture without breaking metadata/handoff tests. Generate fresh synthetic identity independently of host key using `sshkey_generate`, derive public with `sshkey_from_private`, serialize private with `sshkey_private_to_fileblob` (`sshkey.c:3414`; formats via `sshkey.h:290`); provider returns only synthetic bytes. Encode actual expected public blob into CSAS before open. Keep identity key distinct from server host key to catch accidental key reuse.

Server must parse/decrypt the actual USERAUTH packet, require exact Unicode/space-preserving selected username, service/method/TRUE/algorithm/public blob and no trailing data, reconstruct signed input using **server raw session ID**, then independently call `sshkey_verify(expected_identity,signature,...,chosen_alg,...)`. Assert wrong username, wrong session ID, wrong public key, changed boolean/method/algorithm and mutated signature all fail verification; callback digest-as-session-ID should explicitly fail. Send SUCCESS only after verification, and count exactly one request and one selected release. Actual server-side verification makes the test discriminate from an implementation-mirroring serialization check.

Minimum matrix:

- Ed25519, all 3 ECDSA curves, RSA SHA512 and SHA256 advertisements; tested OpenSSH and applicable PEM formats; KNOWN and NEW/CAS at least per algorithm family; fragmented packets, EAGAIN, partial writes and final request drain.
- SUCCESS, remote REJECTED, PARTIAL; bounded BANNER before/after drain; malformed/truncated/trailing/early SUCCESS/FAILURE, unsolicited PK_OK/INFO_REQUEST, disconnect and rekey; no implicit retry/next method.
- Parse failure, mismatched valid private/public, encrypted unsupported file, malformed expected-public before callback, certificate/SK/external/unknown type; RSA too-small and no/empty/no-mutual/misleading substring advertisements; no NULL alg/SHA1 fallback.
- Every earlier shared-attempt consumer/crossover rejected; duplicate begin/outcome reads; wrong thread/environment preflight does not consume or poison; callback cancel/reentry/deadline/role/view revocation and every post-provider outcome wipe; active-step/const-result invalidation and retryable close.
- No private bytes in encrypted request plaintext beyond test-side explicitly controlled raw fixture storage; packet contains public blob and signature only. Cleanup/source review and available fault injection should distinguish proven dynamic branches from untested ENOMEM/sign failure/internal drift cases.

After native matrix, add JNI begin/result and Java immutable typed result using the password orchestration pattern main is currently implementing. Maintain owner thread/claim/epoch/sticky cancellation/closing guards, avoid secret-returning session methods, and cover fake binding argument/result rejection on JVM. Production JNI runtime on Android remains a separate test: source compile/QEMU C packet tests do not prove Java→JNI execution. A full local synthetic peer can prove authentication without real account/device access; device fixture installation/testing needs current scoped authorization and subsequent cleanup.

### Related specs

- `.trellis/spec/backend/ssh-native.md`: explicit C password protocol, selected one-shot callback, CSAS v1, managed selection, current host/KEX/role and cleanup invariants.
- `.trellis/spec/backend/app-secrets.md`: exact typed capability, generation/epoch and final protected effect contract; required before designing the passphrase extension.
- `.trellis/spec/backend/host-bridge.md`, `quality-guidelines.md`, `logging-guidelines.md`: owner/native lifetime, build versus runtime evidence, bounded secret-safe diagnostics.
- `design/constraints.md`: current device authorization, scope, evidence and unified design landing.

## External references (pinned local artifacts)

OpenSSH portable **10.6p1** source root above; source archive SHA256 recorded by the existing build-input research is `a9dc9565dffe8640f64d863cd29a32bc4a3dbdec0566a7fc44c5d6ee767d5f39`. Production builder revalidates baseline/source/patch hashes. No external documentation was fetched and no claim about another/latest OpenSSH release is made. libcrypto/NDK versions and build provenance remain the existing pinned dependency/build records; this report does not revalidate them.

## Caveats / Not Found

- Research did not build, execute tests, inspect a real credential, connect to the board, or change code/spec/task metadata. Current Java/JNI password changes may be in flight; all cited local source lines describe the inspected snapshot and can move.
- The current source provides the necessary low-level key and signature APIs, but actual new link closure, algorithm-format matrix, latency and cleanup fault paths remain implementation/validation work.
- Encrypted private-key and KBI support remain approved product requirements. The initial unencrypted direct-signed stage must not be labeled full private-key/product authentication completion.
- No existing selected passphrase capability or safe generic-target workaround was found. It requires a reviewed cross-layer extension; refuse a workaround that weakens AppSecrets or changes operation target identity.

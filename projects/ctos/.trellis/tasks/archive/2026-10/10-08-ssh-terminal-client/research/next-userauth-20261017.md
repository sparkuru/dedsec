# Research: next local production slice toward typed userauth

- Query: Find an independently implementable production increment after the current KEX-only caller, with supported pinned upstream APIs, credential identity/proof requirements, ownership/errors, and meaningful local validation.
- Scope: internal; pinned OpenSSH 10.6p1 source, current production C/Java contracts, task design and specs. Read-only investigation; no builds, adb, Docker, credentials, accounts or production changes.
- Date: 2026-10-17

2026-10-18 status update: the recommended explicit C service-negotiation slice is
implemented and passes encrypted ARM QEMU/Bionic synthetic tests. See
[`design/verification.md`](../../../../../../design/verification.md#2026-10-18--native-post-kex-userauth-service-negotiation)
for actual evidence and limits. The findings below remain the original research;
typed auth username/immutable selection binding is still the next open contract.

Later 2026-10-18: the managed immutable username/resource-selection contract is
implemented and passes JVM synthetic tests. The remaining gap is native receipt/
wire/current-KEX/service binding, real private/public key matching and actual auth;
see the same verification document's managed-authentication-selection section.

## Findings

### Recommendation

The next coherent production increment is **explicit post-KEX `ssh-userauth` service negotiation**, driven by the existing bounded native socket step, without releasing credentials yet. This adds real encrypted `SERVICE_REQUEST`/`SERVICE_ACCEPT` protocol behavior to production code. It should be opt-in through a new native C transition so existing KEX-only Java/JNI behavior and regression contracts remain intact until the next integration step.

Do not link stock `ssh_userauth2` or pretend the existing `Kind`-only callback already binds a native wire username and a selected private key. Full typed password/public-key authentication needs the account/selection binding described below first. The service negotiation increment requires neither a username nor a secret, and avoids inventing that missing contract.

This recommendation is for the next implementation dispatch, not a report of implementation. The main session is independently assessing a newly discovered host `qemu-aarch64` plus minimally copied Android runtime. Obtain that baseline runtime evidence first when feasible; this researcher did not run or verify that experiment.

### Files found

| File | Role |
| --- | --- |
| `android/app/src/main/cpp/ssh_native_client.[ch]` | Production owned socket/OpenSSH client; currently terminal at first KEX completion |
| `android/app/src/main/cpp/ssh_callback_adapter.[ch]` | Same-thread/environment, role/deadline-bound typed byte handoff |
| `android/app/src/main/java/im/majo/ctos/SshNativeCallbackAdapter.java` | Host proof then KEX proof then managed typed credential selection |
| `android/app/src/main/java/im/majo/ctos/SshManagedProvider.java` | Selects and releases one proof-bound vault request |
| `android/app/src/main/java/im/majo/ctos/SshCredentialBridge.java` | Target/connection/entry or path/keyIdentity and final AppSecrets effect guard |
| `android/app/src/main/java/im/majo/ctos/SshRoleContext.java` | Immutable role metadata/binding; no explicit SSH username |
| `android/app/src/test/cpp/ssh_native_client_test.c` | Real synthetic packet/KEX loop, KNOWN/NEW proof ordering, terminal/lifetime tests |
| `android/app/build.gradle:173` | Production source list and cross-link/test targets |
| `.trellis/tasks/10-08-ssh-terminal-client/research/android-production-openssh-build.sh:239` | Complete source-recompiled PIC archive closure and pin checks |
| `build/ssh-native-g1/stock-run.lOHF9Oai/sources/openssh-10.6p1/` | Pinned upstream source used for all API statements below |

### Current stopping behavior is intentional and observable

`ssh_native_client.c:437-470` hashes the real `kex->session_id`, requires `kex->done`, reads the live role, obtains a nonzero guard cycle, invokes `ctos_ssh_callback_kex_complete` exactly once, and sets `client->completed=1`. `step` rejects `completed || failed` with `EALREADY` at `:658-660`.

`step` only permits `SSH_MSG_NONE` and explicitly handled `SSH2_MSG_EXT_INFO`; any other returned packet is `EPROTO` (`:694-707`). Therefore merely sending userauth packets from a new helper cannot work: a server's `SERVICE_ACCEPT` will be rejected, and no post-completion transport progress is possible. `complete()` reports the KEX milestone (`:762-765`), not user authentication.

The existing test explicitly depends on that stopping contract (`ssh_native_client_test.c:1081-1190`, `:1194-1301`), and the spec requires it (`.trellis/spec/backend/ssh-native.md:253-254,661`). A new explicit opt-in transition is preferable to silently redefining every current Java `StepResult.complete` and caller's stop condition.

`close` first retries candidate rollback when applicable, synchronously releases the callback, frees/unbinds OpenSSH, releases the managed-view borrow, frees the parsed public key, closes the original FD, then marks a tombstone (`ssh_native_client.c:767-807`). Any new phase must preserve retryable close and that ownership order. `completed` also currently controls candidate rollback (`:475-476,776-777`); do not reuse this flag as “authenticated” without auditing those branches.

### Supported upstream APIs and object closure

All line numbers in this subsection refer to the pinned upstream directory above.

| Need | Actual supported symbol/source | Constraint |
| --- | --- | --- |
| Incremental decrypted packets | `ssh_packet_next`, `ssh_api.h:88`, `ssh_api.c:253-290` | Dispatches KEX packet types 20–49 only; returns other packet types for the caller to handle |
| Encode service/userauth packet | `sshpkt_start`, `sshpkt_put_cstring`, `sshpkt_put_u8`, `sshpkt_put_string`, `sshpkt_send`, `packet.h:173-189` | Enqueues via existing OpenSSH transport; keep current output write/consume loop |
| Parse bounded reply fields | `sshpkt_get_string_direct`, `sshpkt_get_cstring`, `sshpkt_get_u8`, `sshpkt_get_end`, `packet.h:196-206` | Direct pointers belong to current packet; never retain after the next packet call |
| Alternative complete payload API | `ssh_packet_payload`, `ssh_packet_put`, `ssh_api.h:95-103` | Use one coherent style; do not hand-encode transport framing/encryption |
| In-memory private key parser | `sshkey_parse_private_fileblob[_type]`, `sshkey.h:293-296` | Takes `sshbuf` plus C-string passphrase; no plaintext pathname needed |
| Public identity match | `sshkey_equal_public`, `sshkey.h:186`; `sshkey_to_blob`, `sshkey.h` | Compare parsed key with an approved selected public key, not only a descriptive identity label |
| Sign request | `sshkey_sign`, `sshkey_check_sigtype`, `sshkey.h:267-271` | Must choose an allowed signature algorithm; no agent/provider fallback implied |

`Makefile.in:80-111` places `ssh_api.o`, `sshkey.o`, `authfile.o`, `packet.o`, sshbuf and key algorithm objects in the library. `sshconnect2.o` belongs to `SSHOBJS` at `:117-118`, outside that library closure. The existing production runner recompiles the complete pinned library to PIC, verifies the member set against baseline, and replaces project-patched hostfile/KEX objects (`android-production-openssh-build.sh:239-270`). Service negotiation and in-memory software-key primitives therefore do **not** require importing a new stock high-level object. Retain the archive and forbidden stock-caller symbol checks; source inspection is not a new link/runtime result.

Stock `ssh_userauth2` (`sshconnect2.c:424-465`) installs a stack auth context, reads global `Options`, calls fatal helpers and runs blocking dispatch until success. Its service/userauth handlers and password/public-key helpers are `static` (`:351-378,491-522,1041-1075,1322,1494`). `userauth_passwd` invokes `read_passphrase` (`:1056-1057`). They are reference behavior, not reusable supported nonblocking production entrypoints.

The service request sequence is explicit at `sshconnect2.c:456-459`: type `SSH2_MSG_SERVICE_REQUEST`, string `ssh-userauth`, send. The stock accept handler parses an optional string, tolerates an empty reply for buggy servers, checks trailing bytes, then calls `userauth_none` (`:492-518`). Thus “require exact nonempty ssh-userauth reply” would be a deliberate stricter compatibility choice, not a claim that stock does this. Recommended narrow local default: support exact `ssh-userauth`; explicitly record whether legacy empty reply compatibility is retained before implementation.

### Proposed production/API/error/lifetime contract

Keep this slice in **three relevant implementation/test files**: `ssh_native_client.h`, `ssh_native_client.c`, and `ssh_native_client_test.c`. They are already registered as build inputs; no new OpenSSH source or JNI method is needed. Main session owns task design/spec/history updates.

Proposed API names (new, not currently existing):

```c
int ctos_ssh_native_client_begin_userauth_service(struct ctos_ssh_native_client *);
int ctos_ssh_native_client_userauth_service_ready(const struct ctos_ssh_native_client *);
```

- `begin_userauth_service` runs on the same event-loop owner, with live original callback environment, native role, view borrow and deadline. Check these before enqueuing anything. No credentials or externally supplied service name.
- Require the exact completed initial KEX, one successful managed KEX callback and no terminal error/close. Recheck `ctos_ssh_kex_ready` against the current role before transition. A new internal phase distinguishes KEX-only stop, waiting-for-service and service-ready; retain `complete()` as the existing KEX milestone.
- Before completed KEX: fail `EACCES` (or a specifically documented `EAGAIN`) without emitting packets or accessing credentials. Invalid pointer: `EINVAL`; closed: `ENOENT`; failed or duplicate transition: `EALREADY`; expired: terminal `ETIMEDOUT`; stale role/revoked view: fail closed with the existing error. Choose and freeze the pre-KEX code in the main-session design.
- Once explicitly started, the existing bounded `step` may continue despite KEX `completed`. Emit exactly one service request, accept one correctly framed service accept, then stop at service-ready. Do not emit `USERAUTH_REQUEST` or call either credential callback in this slice. `complete()` alone cannot distinguish these phases; use the new ready accessor in C tests/future caller.
- Preserve actual byte accounting, partial writes, `EAGAIN`, EOF, cancellation and deadline handling. Reject out-of-phase `USERAUTH_*`, duplicate/malformed/wrong service accepts and trailing payload as terminal `EPROTO`; later step returns `EALREADY`, close remains retryable.
- Handle EXT_INFO with the existing upstream routine, without silently allowing arbitrary packet types. Define/reject rekey during this initial narrow service phase before the upstream guard/proof can be mistaken for reusable authorization; full rekey is not implemented merely by deleting the current completed check.
- Existing KEX-only callers never invoke the transition, continue receiving post-KEX `EALREADY`, and never request secrets. New service-ready is a protocol milestone, never an authenticated result or channel permission.
- Service string/reply spans are bounded and borrowed only while parsing. No new plaintext secret allocation or lifetime. No change to callback release/FD/view tombstone ownership.

### Typed password/private-key contract still needed after that slice

The approved direction already permits typed managed handoff; a new product-level architecture decision is unnecessary for the service increment. However the following **technical identity binding is absent today** and must be recorded before real credential transmission:

1. `SshRoleContext` and native role snapshots contain opaque `targetIdentity`, host/port/hop/connection and nonce/binding, but **no explicit wire username**, entry ID, selected private-key path, key identity or selected public-key fingerprint (`SshRoleContext.java:22-64,139-148`; `ssh_role_bootstrap.h:24-55`). Do not parse an arbitrary `targetIdentity` as `user@host`; no canonical serialization contract was found.
2. Native typed callback carries only `kind` (`ssh_callback_adapter.h:121-122,170-172`). Java `CredentialRequestSource.open(bridge, role, hostProof, kind)` selects the request; provider checks the returned kind, then releases through the bridge (`SshManagedProvider.java:36-39,147-192`). This verifies managed request ownership/proofs, but gives native no exact selected-entry/key receipt with which to validate the username/key placed on the wire.
3. Password request binds exact vault `entryId`, role target/connection and expected entry type (`SshCredentialBridge.java:72-94`). Private-key request binds path plus keyIdentity, same target/connection (`:107-127`). Final guarded release rechecks role, host proof, KEX proof and operation immediately at AppSecrets receiver use (`:130-159`). `AppSecrets.java:770-787` verifies managed PRIVATE_KEY type and declared identity. Preserve all these checks.
4. Adapter rejects a missing host or KEX proof before any provider release (`SshNativeCallbackAdapter.java:560-603`); JNI/native buffers are bounded, and C wipes output on all callback failures (`ssh_callback_adapter.c:817-866`). Native userauth must additionally require its **current** accepted host/KEX cycle/session proof, not only “kex happened at some earlier time.”
5. Freeze one immutable per-hop auth selection/plan (wire username + target/connection binding + typed entry or key path/identity + approved public key/digest) on the managed side, bind its identity to the native request, and recheck the returned selection and parsed public key. This is a proposed contract, not an existing API. Main should decide its narrow representation and transport; no account or vault access is needed to implement it locally with synthetic inputs.

Full password implementation can use bytes with `sshpkt_put_string` (stock uses C-string `password`); define text encoding/NUL behavior and limits first, avoid Java `String` for secrets, retain stock password padding (`sshconnect2.c:1058-1065`), and distinguish failure/partial success/password-change rather than auto-success. Do not implement keyboard-interactive by treating remote prompts as password authority.

Full private-key implementation must parse the typed bounded bytes in memory with the typed passphrase for the same selected identity, compare the parsed public key with the immutable selected public identity, construct signed data using **raw** SSH `session_id` rather than the callback's SHA-256 digest, and use the exact username/service/method/algorithm/key. The upstream canonical construction is `sshconnect2.c:1413-1429`, signing at `:1439-1441`, signature append/enqueue at `:1465-1479`. Its `SSH_OLD_SESSIONID` and optional hostbound method branches are explicit compatibility concerns. RSA signature negotiation (`key_sig_algorithm`) is static stock policy, not automatically solved by calling `sshkey_sign`.

Raw secret bytes, passphrase scratch, `sshbuf`, parsed private key and generated signatures require explicit short ownership and wipe/free on success, cancel, parse/sign/packet failure and close. Upstream `sshbuf_free` zeroes its owned buffer (`sshbuf.c:161-192`); inspect packet plaintext buffers too before claiming complete secret cleanup. Parsed public key/identity labels are not proof that every temporary secret copy has been erased.

### Meaningful local validation

For the proposed service slice, extend the **existing real production client + pinned OpenSSH synthetic server** tests rather than a mock state-only test:

1. Complete real KNOWN and NEW/CAS KEX first, assert host/candidate/KEX callback ordering and exactly one KEX proof; enter the new transition.
2. Decrypt/parse on the upstream server and assert exactly one `SERVICE_REQUEST` with exact `ssh-userauth`; send real encrypted `SERVICE_ACCEPT`, fragmented across small budgets; client reaches service-ready with exact read/write accounting.
3. Assert credential callback count stays **zero** in every path, including successful service negotiation. Reject early `USERAUTH_SUCCESS`, wrong/empty/trailing service payload according to the recorded compatibility policy, duplicate transition/reply, EOF, stale role/view, cancel and deadline. Verify terminal follow-up and retryable cleanup.
4. Preserve the old KEX-only post-completion `EALREADY` tests unchanged. Test premature transition does not disturb a live KEX; callback/proof failure cannot enqueue service traffic.
5. Rebuild strict `testSshNativeClientHost` and normal `compileSshFiles`; confirm no extra upstream member/high-level stock symbol, and run the C binary where a verified emulator runtime is available. Existing Gradle output hardcodes `runtime=not-run` (`android/app/build.gradle:318-357`); a separate actual emulator command/output is needed to claim runtime.
6. Normal JUnit/lint/Release checks remain appropriate for production C integration. No board installation, real vault, external endpoint, PTY, forwarding, service/UI or account action follows from these local checks.

### Approval and runtime boundary

Current PRD explicitly says the task is approved and in progress, with the user-confirmed same-process event loop, synchronous attached JNI callback and managed typed handoff (`prd.md:110` onward; task design `:295-305,358-361`). The missing ARM fixture execution is a **validation gap**, not a blanket prohibition on local production design/implementation. Preserve `implementation_complete=false`, report actual not-run gates, and do not mark parent AC fulfilled.

The current narrow KEX-only stopping rule **is** a reviewed implementation contract. Extend it explicitly in task design/spec before dispatching code; do not silently replace it or claim authorization for new external effects. The task's current board `RUNNING_LOCKED` and the user's “retain local result” choice do not authorize bypassing Android unlock or using real credentials/accounts. The service-only increment requires neither. If runtime establishes a KEX defect, fix that defect before extending the protocol.

## Related specs

- `.trellis/spec/backend/ssh-native.md:3-25,628-665`: current production closure, KEX-only terminal contract and honest runtime reporting.
- `.trellis/spec/backend/ssh-native.md:1575-1615,1973-1990`: frozen callback tags, attached-thread environment, proof-before-secret and cleanup behavior.
- `.trellis/tasks/10-08-ssh-terminal-client/prd.md`: approved parent scope and current narrower validation boundary.
- `.trellis/tasks/10-08-ssh-terminal-client/design.md:295-305,358-361`: confirmed topology, managed typed provider direction and common vault semantics.
- `.trellis/tasks/10-08-ssh-terminal-client/research/openssh-10.6p1-production-kex-source-map-20261015.md`: prior upstream/KEX map; historical missing-caller statements superseded by current code.

## External references

- Pinned source corresponds to OpenSSH portable `V_10_6_P1`: <https://github.com/openssh/openssh-portable/tree/V_10_6_P1>. All findings here came from the local pinned source, not a live branch or web lookup. Archive provenance is recorded in the previous source-map and local G1 evidence.

## Caveats / Not Found

- No execution or new link evidence was produced by this research. Main session's emulator experiment is independent and its result was not available while writing.
- No canonical targetIdentity-to-SSH-username contract or immutable native credential-selection receipt was found in the reviewed files. An arbitrary callback kind must not become a new signing/password authority.
- Legacy empty SERVICE_ACCEPT compatibility and initial rekey behavior need narrow design statements before implementation. They are implementation compatibility choices; do not misrepresent strict rejection as unchanged upstream behavior.
- A service-ready milestone is neither successful user authentication nor proof of password/public-key/keyboard-interactive support.

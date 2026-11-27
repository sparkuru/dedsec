# SSH server-driven authentication

## Scope

The automatic connector sends one `none` USERAUTH request on a same-owner
session after actual host proof, KEX completion and userauth service acceptance.
The user supplies only target and an optional selected key before connecting.
The [design](../../../design/ssh-server-driven-authentication.md) separates the
local implementation from Android/real-server evidence and remaining work.

## Contracts

`ctos_ssh_native_client_begin_auth_discovery(client)` starts once;
`ctos_ssh_native_client_auth_discovery_result(client, result, methods)` reports
NOT_STARTED=0, PENDING=1, METHODS=2, NONE_SUCCESS=3 or PARTIAL=4. The bounded
method mask is publickey=1, keyboard-interactive=2, password=4 and other=8.
Only exact server name tokens are matched; empty lists are allowed; malformed,
oversized, unexpected or trailing protocol data fail the session. A nonzero
SSH partial boolean remains partial, never final success.

Discovery uses the immutable connection plan's actual username, original
role/view/deadline, callback owner, host proof, KEX cycle and session digest.
It sends no password or signature and cannot invoke a selected credential
callback. During discovery and after NONE_SUCCESS, credential-result getters
and selected credential reads are denied. CSASv4 holds an explicit optional key
and an allowed-method mask, never a login password. After METHODS/PARTIAL, an
allowed method can begin only if the server actually listed it; direct selected
credential reads are denied outside the admitted method start. A real NONE_SUCCESS
may promote that same session without calling a credential callback; it must
never be reported as password, key or KBI success. Repeated discovery, wrong
owner, cancellation, expiration and revoked views cannot create new authority.
Callers that never start discovery retain existing behavior.

JNI exposes `beginAuthDiscovery(long)` and `authDiscoveryResult(long)->int[2]`.
Java strictly checks tags, mask bounds, state transitions and result stability;
NONE_SUCCESS is separately tracked when authorizing shell start. On an actual
PARTIAL or ordinary method rejection, `authContinuationMethods` exposes only
the latest bounded server list; the next method must also be in the frozen
configured order and not attempted. Rejection is never counted as a completed
factor, and a failed credential is never automatically replayed. If the
selected key is rejected but password or KBI is still offered and permitted,
the same session can ask for that alternative; key-only rejection remains a
clear failure. Legacy explicit modes retain their existing rejection behavior.
CSAPv2 receipts bind each transition to the original selection, KEX cycle and
digest. At most the three supported methods are attempted, with at most four
native stages because a changed first method occupies stage 2.

Password and key-passphrase prompts are separate from KBI and use a random,
one-shot ticket tied to the same role, phase, host/KEX proofs, foreground guard,
and original deadline. Only the native owner consumes the response. Clear all
owned copies on handoff, cancellation, timeout and failure; reject stale tickets.
An encrypted selected key receives a deferred transient phrase only if the
server actually asks for publickey. Never fill a KBI answer from a stored secret.
An idle, rejected or PARTIAL authentication owner deliberately rejects ordinary `step()`;
while waiting for a local prompt use the bounded same-owner prompt poll instead.
It revalidates the original plan and currently offered method, handles banners,
disconnect/EOF and unexpected packets, and never starts or retries a credential.
Revalidate on return from the prompt before submitting its one-use input.
Keep standalone KBI until automatic product and real-environment acceptance is
complete.

## Saved credentials in automatic prompts

### Scope

Use the shared encrypted vault for an automatic password or selected encrypted
key prompt. A KBI challenge collects only the answers the user enters for that
challenge.

### Signatures

- `passwordRequest` and `keyPassphraseRequest` events carry the required `host`, `port`,
  `username` and `hop` of the exact callback-registered authentication selection and
  role, alongside `requestId`, `ticketId`, `binding` and `remainingMillis`. Missing,
  partial or malformed endpoint/hop data is rejected; hop is 0–8. `savedAvailable` is
  optional for wire compatibility and defaults to unavailable. The optional
  `savedOnly` flag defaults to false; it can be true only for a password request
  with `savedAvailable: true`. A saved-only ticket permits exactly the explicit
  `useSaved: true`, `save: false`, empty-byte response. The mailbox enforces this
  independently of the Flutter parser and never accepts typed or save responses
  for that ticket. The flag alone does not authorize a vault read or open the
  product ProxyJump gate.
- `ssh.passwordResponse` accepts the original fields and optionally the pair
  `useSaved: bool` and `save: bool`. The original four fields select one-use input.
  The saved choice uses `useSaved: true`, `save: false`, and empty `password` bytes.
  Typed UTF-8 input uses `useSaved: false` and an explicit save choice.

### Contracts

The native owner checks host proof, KEX, the admitted method and the original
prompt stage before querying availability and again before consuming a saved
choice. Passwords use the canonical resolved host, port and username, and key
passphrases use the exact selected managed binding and account. Publish only
availability. The shared vault validates the original view, current ciphertext
and guard before the owner copies secret bytes. The existing prompt response and
owner cleanup contracts apply to transient bytes.

An explicit save candidate qualifies after its own SUCCESS or PARTIAL and is
committed only after final authentication and confirmed shell RUNNING, before
terminal publication. Use the canonical shared-vault entry mutations with the
original request and deadline. If both factors qualify, verify each exact own
write before proceeding to the next mutation. Wipe pending input on terminal
rejection, stop, lock, or any failure.

### Validation and errors

Malformed flags or a saved selection carrying typed bytes produce PARAMETERS.
An unavailable, answered or stale ticket cannot authorize a saved read. Report
the fixed missing-credential error when an advertised entry is unavailable at
the guarded read. Deliver a tracked write with an invalidated receipt through
the existing VAULT_COMMIT_UNCERTAIN outcome.

### Representative cases

- A saved password for an exact tuple is offered as an explicit choice after the
  server offers password and after host verification.
- Typed input with the unchecked save control lives only for that attempt.
- A rejected credential loses its save candidate even if a later method opens a
  shell. An abandoned or rejected shell cannot trigger the save.

### Verification

Check exact tuple and key, wrong proof, original view, lock, reply validation,
accepted and rejected factors, deferred write and terminal publication timing.
Flutter checks should exercise choice, dismissal and small-screen layout.

### Enforcement

Require an active prompt ticket for reuse and keep all durable credential
effects on the confirmed owner path. A successful fallback authorizes only the
factors that actually passed.

## Required verification

### 1. Scope / Trigger

Use the isolated OpenSSH/PAM interoperability fixture for actual server-method discovery, KBI-first or password PARTIAL → KBI. Explicit config KBI can select KBI ahead of a configured password preference; do not remove that entry as redundant without a deliberate product decision or an equivalent bounded method-selection flow.

### 2. Signatures

`android-password-client-build.sh --output <new-build-dir> --auto-interop` builds the ARM64/Bionic driver. In a disposable container, `openssh-auto-interop-fixture.sh [test|serve]` runs the native cases or starts the two loopback listeners. `openssh-auto-loopback-relay.py <22222|22223>` forwards one stdio stream to the corresponding listener.

### 3. Contracts

Require a Docker container with only loopback, a fresh test account and temporary keys. The native test mode requires read-only QEMU and driver mounts; serve mode stops after 900 seconds. Transfer generated credentials through protected stdin/files, never logs or process arguments. Each live-device request uses the explicitly authorized target, exact package ownership checks and task-owned ADB reverse mappings.

### 4. Validation & Error Matrix

Non-container execution, a non-loopback interface, invalid mode, missing driver or preexisting fixture account must abort. A rejected KBI answer must not open a shell. A password PARTIAL must not open a shell until actual KBI success; cleanup acts only on resources proven to belong to the test.

### 5. Good / Base / Bad Cases

Good: KBI-first and password→KBI each finish through one real session and PTY. Base: a real PAM exchange can include a zero-prompt round requiring explicit UI continuation. Bad: interpreting a compiled driver, a mock UI or a native-only pass as an Android end-to-end result.

### 6. Tests Required

Assert the actual advertised method, prompt text/echo, successive rounds, PARTIAL and final result, server-side connection identity, shell output, rejection and cleanup. Report native/QEMU separately from the [Android UI/JNI result](../../../design/verification.md#20261118-ssh-openssh-android-ui).

### 7. Wrong vs Correct

Wrong: expose sshd on a LAN address or assume an empty denied socket listing proves cleanup. Correct: use loopback-only isolation, verify owned resources and distinguish no listener from transient TCP state.

Use an encrypted synthetic peer for empty, exact and unknown methods, malformed
lists/trailing bytes, partial and actual none SUCCESS, selected-password/key/KBI
continuation, rejected key followed by password/KBI or key-only rejection,
no credential replay or premature reads, cancellation, invalid view and wrong owner.
Retain full native/JVM regressions and strictly separate JNI symbol compilation
from actual Android runtime, real server and UI evidence.

# Research: production launcher native prerequisites

- Query: admit the actual SSH server host key without a caller pre-pin; preserve managed trust/CAS, current-cycle signature proof, owner thread, cancellation and original deadline. Locate a reusable managed OpenSSH config evaluator and choose a local next increment.
- Scope: internal, pinned-source inspection only; no network, device, builds or product edits.
- Date: 2026-10-19
- Active task: `.trellis/tasks/10-08-ssh-terminal-client`; preserve the existing uncommitted authentication/PTY result. No commit or device authorization inferred from “继续”.

## Findings

### Existing boundaries and files

| File | Role / relevant lines |
| --- | --- |
| `design/handoff-ssh-terminal-client.md:3` | Current effective summary: authentication and PTY implemented locally; production profile/config/connector/key-discovery launcher remains open. |
| `.trellis/tasks/10-08-ssh-terminal/design.md:32` | Parent Include semantics, per-hop ProxyJump and rejection of command execution; old fork/exec ideas are superseded by child same-process decision. |
| `.trellis/tasks/10-08-ssh-terminal-client/prd.md:1` | Current requirement/verification boundary; original parent requirements and completed auth baseline remain authoritative. |
| `android/app/src/main/cpp/ssh_native_client.h:68` | Production config currently requires a host public blob plus SHA256 expected fingerprint, an already managed socket/view/callback and exact host/candidate. |
| `android/app/src/main/cpp/ssh_native_client.c:1658` | Open validates/preparses the caller key, fingerprints it, adds it to `ssh_api`, binds the same expected hash into KEX guard. |
| `android/app/src/main/cpp/ssh_native_client.c:550` | Transport verifier first compares offered key fingerprint to the pre-pin, then invokes managed hostfile issuer and exact evidence/CAS callback. |
| `android/app/src/main/cpp/ssh_hostfile_issuer.c:209` | Native pinned parser classifies actual key against typed managed known_hosts; NEW builds exact replacement and one-shot candidate handoff. |
| `android/app/src/main/cpp/ssh_kex_guard.c:61` | Role guard currently requires a nonzero preknown host hash at registration; tracks independent begun/approved/signed/complete flags and monotonically increasing cycle. |
| `android/app/src/main/java/im/majo/ctos/SshNativeSession.java:371` | Java open has the same caller-key dependency, owned native claim, view ownership and thread-wide native reentry protection. |
| `android/app/src/main/java/im/majo/ctos/SshNativeCallbackAdapter.java:382` | Exact role-bound host evidence synchronously obtains NEW proposal, verifies approval, writes CAS, encodes bounded CSHR, then final-checks invocation/liveness. |
| `android/app/src/main/java/im/majo/ctos/SshHostKeyAuthority.java:16` | `UnknownKeyIssuer.requireApproved(Review)` is synchronous; default always rejects unknown keys. Review binds role/target/hop/host/port/key/both digests. |
| `android/app/src/main/java/im/majo/ctos/SshRoleContext.java:12` | Original role maximum lifetime is 60 seconds; neither UI wait nor new launcher may renew it. |
| `android/app/src/main/cpp/ssh_config_policy.c:125` | Existing effective Options policy rejects commands/external providers and trust bypasses; ProxyJump fields deliberately retained. |
| `android/app/src/main/cpp/ssh_config_policy_probe.c:301` | Existing real `read_config_file` evaluation against managed memory/file-I/O view and virtual home. |

### Live discovery is feasible with the existing pinned SSH API

Pinned local source root: `build/ssh-native-g1/stock-run.zPRULpSC/sources/openssh-10.6p1/` (OpenSSH 10.6p1). `ssh_api.c:235` replaces the host verifier. `_ssh_verify_host_key` at line 512 uses added keys only for the default verifier. `_ssh_order_hostkeyalgs` at line 527 leaves the default proposal unchanged if no registered keys match; an empty public_keys list therefore does not prevent negotiation. A discovery client can omit `ssh_add_hostkey` and install the existing project verifier, subject to explicit new opt-in admission.

Guarded KEX source inspected at `build/ssh-native-g3/run.ARfL9zQq/upstream/guarded/`:

- `kexgen.c:174` invokes host verifier on the actual parsed server key from the KEX reply.
- `kex.c:1238` invokes project `ctos_ssh_kex_approved` only after verifier success.
- `kexgen.c:235` cryptographically verifies the server signature, then at line 240 invokes `ctos_ssh_kex_signed`.
- Existing NEWKEYS guard and `ssh_native_client.c:629` completion digest require real KEX completion, one current role/hop cycle and SHA256(raw session ID). User approval alone cannot manufacture these facts.

Thus removing only the open pre-pin is insufficient: the guard registration must support an initially unbound offered host, and may bind it exactly once only after the existing native issuer + managed exact approval/CAS have succeeded. It must remain impossible to approve/signed/complete while unbound.

### Smallest independently implementable local increment

Recommendation: first add C-only explicit live-host discovery, with real socketpair encrypted KNOWN and NEW/CAS regressions. Keep profile evaluation, Java launcher/UI and device work separate. This removes a concrete production blocker without pretending to ship a complete launcher.

Proposed technical contract to freeze before implementation:

1. Preserve existing `ctos_ssh_native_client_open` semantics and its required pre-pin. Add a distinct `ctos_ssh_native_client_open_discover_host` entry point (name illustrative), sharing validated setup internally. Discovery rejects nonempty caller key/fingerprint fields rather than interpreting malformed legacy inputs as opt-in. Legacy defaults/fixtures remain byte-for-byte behavior compatible.
2. Add explicit `ctos_ssh_kex_bind_role_discover` registration with the same role/owner/hop/nonce/connection/deadline/duplicate-slot checks as old bind. Guard stores explicit discovery + unbound state, not a sentinel fingerprint accepted by old matching code.
3. Existing verifier remains the sole trust decision. Discovery obtains actual key from `ssh_api` KEX callback; after `ctos_ssh_hostfile_issue` + exact host-result comparison return KNOWN/NOT_REQUIRED or NEW/COMMITTED, compute its SHA256 and install one-time guard binding. Require begun initial cycle, still live role/deadline, no existing approval/sign/completion/binding. Recheck client cancellation/callback liveness, original role deadline and managed-view borrow immediately after callback and before the latch; do not rely on the earlier verifier-entry check. Failed install terminalizes and runs existing candidate abort cleanup; no provider or userauth completion. Candidate abort is not an undo of an already committed trust CAS; do not invent a post-CAS rollback that can erase a user-approved managed trust record.
4. `ctos_ssh_kex_approved`, signed and complete require bound state; all later key mismatches reject. Keep existing crypto verification hook order and completion digest. Rekey remains unsupported by current client; do not broaden it here.
5. Disable public/manual `verify_host_key(blob)` for discovery sessions: it must not bind an injected caller key or manufacture a transport-derived host decision. Legacy diagnostic verify remains unchanged. Require an internal transport-callback phase/guard state so approval cannot occur before actual initial KEX.
6. All allocations/registration remain before socket/view/callback ownership transfer. Failure keeps original FD and owner resources caller-owned; close preserves rollback, callback close, unbind, descriptor/view release and tombstone retry order. Store only key metadata/copies needed for lifecycle; no unmanaged public files.

Expected code ownership for that increment: `ssh_native_client.[ch]`, `ssh_kex_guard.[ch]`, `android/app/src/test/cpp/ssh_native_client_test.c` (five files). Upstream KEX patch and production archive member set should remain unchanged if the verifier installs binding before returning to the existing approved hook. Existing native build already compiles the guard/client; only adjust runner if new test inputs truly require it.

An enum named `MANAGED_OFFERED` versus legacy `PINNED` is also viable if selected by a distinct open function or explicit validated field; exact names are implementation choices. Avoid a generic “allow unknown” boolean, wildcard fingerprint, auto-accept callback, or a null-input inference in legacy open. Preserve existing proposed algorithm defaults; with no added keys `_ssh_order_hostkeyalgs` keeps that complete default list, including algorithms which policy may later reject (e.g. unsupported certificates). No-key admission is not an algorithm/trust bypass and must not broaden certificate acceptance.

Acceptance matrix: legacy pinned cases unchanged; discovery KNOWN success with no caller key; NEW exact candidate → COMMITTED → actual signed KEX → unique completion; CHANGED/REVOKED/CERTIFICATE/INVALID denial; malformed discovery config/legacy missing key rejected; wrong role/hop/cycle, second binding, pre-KEX/manual verify, signature tamper, deadline/cancel/view revoke, evidence/CAS reject or stale digest never produce completion/credential; abort/close retry and both socket descriptors reclaimed. Reuse actual ephemeral server keys generated independently in existing encrypted peer tests. These are implementation suggestions, not claims of executed tests.

Follow-up Java/JNI wiring should use a named `SshNativeSession.openDiscoverHost` and a distinct NativeBinding default method that fails closed when unimplemented; preserve existing `open` and old fake bindings. New JNI entry may share bounded parsing/setup with old entry but must explicitly select discovery; never infer discovery from a null Java array in legacy open. CSAS remains from the adapter's frozen selection, and host/KEX proofs remain prerequisites for the existing one-shot credential handoff.

### Feasible asynchronous NEW review without rewriting KEX

The current callback is synchronous down the entire chain. Returning `-1` or a purported pending code is not a resumable pause: `ssh_api.c:253` has already dispatched the consumed packet, `kexgen.c:162` has replaced its dispatch with protocol-error, and the handler frees its local key/shared-secret state on return. `kex_verify_host_key` specifically treats `-1` as error; other arbitrary returns risk accidental approval. Do not invent an EAGAIN handshake by re-running `step` or replaying consumed packets.

A minimal technically feasible UI flow preserves synchronous native callbacks while UI interaction is asynchronous:

1. Owner verifier installs candidate and obtains exact `Review`; a review broker publishes only bounded safe metadata plus session generation and an unpredictable one-use ticket. The callback owner waits on a dedicated monitor/mailbox until exact approval, rejection, cancellation or its original monotonic role deadline. It releases broker locks while waiting; it must never run on the UI thread or wait while holding the adapter state lock/AppSecrets store lock.
2. UI decision completes that mailbox directly from another thread. It does not enqueue approval on the blocked owner executor, call session JNI, construct Proof/CSHR, or commit CAS. Match exact ticket/generation/review binding; duplicate/stale/wrong-session decisions reject. A review notification failure also rejects.
3. Cancellation first fences publication and wakes the mailbox, calls existing callback cancel from its allowed separate cancellation path, then owner unwinds/aborts candidate and closes/retries resources. Late approval cannot resurrect it. View/role/manual-lock revocation also needs a wake signal or short bounded timed waits that recheck the same current liveness; no indefinite wait and no deadline renewal.
4. After mailbox approval, the original owner executes `UnknownKeyIssuer.requireApproved` return → Proof → existing managed `writeBack` exact guarded CAS → final callback checks → native exact CSHR check → one-time host binding → crypto signature/newkeys/completion. A user decision alone never grants KEX or credentials. Existing own-exact-CAS public-loan preservation remains mandatory; use no fake CAS/liveness.

Material limitation: `ssh_session_jni.c:110` locks a global `sessions_lock`; `Native.step` holds it throughout native KEX/callback and only unlocks at line 316. Waiting for UI therefore serializes other sessions' JNI calls for up to the role's remaining lifetime. `ctos_ssh_callback_cancel` (`ssh_callback_adapter.c:585`) uses a distinct short-held callbacks mutex, so callback cancellation can still proceed, but any proposed broker must prove wake/unwind independently and avoid global-session native reentry. A mailbox wait changes step wall-time behavior even though byte budgets stay bounded; document this explicitly or defer UI until a deliberate resumable-KEX/registry-lock refactor. That larger refactor should not be hidden in first discovery increment.

Alternative true nonblocking native pause requires pinned KEX continuation state + explicit resume/cancel ownership and upstream patch changes; it is a larger contract, not a Java-only adapter. Reject-on-NEW until a broker exists is safe for an intermediate internal API, but is not completion of the product unknown-host AC.

### Config evaluator capability and promotion work

There is real upstream parser reuse already; do not write a Java/Dart line parser. The opt-in `android-policy-build.sh:112` compiles patched `readconf.c`, `ssh.c`, `sshconnect.c` under `CTOS_SSH_CONFIG_POLICY` **and** `CTOS_SSH_POLICY_FIXTURE`; the probe wraps fopen/stat/glob/getpwuid and execution/network entrypoints. `managed_closure` (`ssh_config_policy_probe.c:301`) supplies a typed managed view and `/ctos-vault` virtual passwd home, then invokes the native parser. Existing cases cover nested/glob/conditional Include, first-value precedence, missing/invalid paths, cycles and forbidden child statements.

`readconf.h:29` defines upstream Options; it includes hostname, port/user, host key alias/algorithms, identity lists, forwards, canonicalization, and jump_user/jump_host/jump_port/jump_extra (lines 175–178). The policy patch preserves `parse_jump` rather than treating it as custom command execution. `ssh_config_policy.c:54` assembles a shell ProxyJump command for historical fixture high-level paths; that is not an authorized same-process production connector and must not be reused as fork/exec production launch.

Production gaps are explicit: `ssh_config_policy.c:173` entry/trust guards reject provider, Include's nonfixture branch rejects provider; policy diagnostics have fixture-specific object mapping and process-global state. Production OpenSSH builder `android-production-openssh-build.sh:246` builds PIC `libssh` plus approved patched members; it does not promote `readconf.o`/stock ssh/sshconnect into normal client. Backend spec lines 2489–2508 explicitly mark parser adapters opt-in only.

Promotion needs a project-owned evaluator API with input borrow+frozen alias/request context; typed managed FILE/stat/glob access, virtual identity/home without process-environment mutation; scoped Options/diagnostics/I/O budgets and cleanup; complete upstream first/Match-final passes and token-expansion semantics; bounded immutable route/auth/trust/forward result (or explicit unsupported diagnostic). Preserve all jump and forwarding fields even when the current runtime has not implemented them: selecting such a plan must fail explicitly, never silently direct-connect or discard fields. Library fatal/exit paths and global parser/policy state need a reviewed failure/reentry strategy before use inside the app process. The probe's wrapper/effect assertions are reusable evidence patterns, not a production provider.

### Technical decisions versus product choices

Technical decisions available within existing authorization: explicit discovery API preserving old open; one-shot post-CAS binding; reuse pinned parser and Include adapter; fixed owner thread and bounded cancel mailbox; exact immutable plan/diagnostics; reject unsupported effective settings; no direct fallback for ProxyJump; all existing proof/60-second deadline/thread constraints intact.

Existing product decisions already settled: same-process engine, Include and up to eight managed hops, per-hop trust/auth, no custom local commands, default strict ask/changed denial, foreground service lifecycle, loopback/external-forward confirmation, no real accounts/device in this continuation. Do not ask user to decide these again. The current 60-second role cap is a known product-lifecycle limitation; changing it or allowing established SSH to survive public-view revocation is a separate security/lifecycle contract, not an incidental launcher adjustment. UI wording/navigation may follow existing app conventions without a new architecture vote.

## External References / Versions

- No external access performed per dispatch. OpenSSH 10.6p1 local pinned sources and project patches inspected directly, using source-path citations above.
- Existing parser-policy patch: `.trellis/tasks/10-08-ssh-terminal-client/research/openssh-10.6p1-config-policy.patch` (Match exec refusal, Include guard before glob and each match, forced policy validation, bounded recursion/nonfatal config errors).
- Existing KEX-patch implementation inspected from guarded build source; implementation should use the authoritative patch/build recipe and re-run its pinned hash/patch guards, not edit generated build sources.

## Related Specs

- `.trellis/spec/backend/ssh-native.md`: current shell/auth contracts; own exact NEW/CAS loan (line 259), public FILE/Include evaluator bounds (line 2489), typed evidence and native owner lifecycle.
- `design/ssh-session-channel.md`: preserve only authenticated SUCCESS channel admission and cleanup.
- `design/ssh-terminal.md`, parent/child PRD/design/implement: production launch is still open and parent remains in progress.

## Caveats / Not Found

- No production config evaluator JNI, profile picker/connector, cancellable UI review broker or full same-process ProxyJump engine was found in inspected files. Existing terminal factory requires an already authenticated owner session.
- No local tests/builds/devices run by this research. Recommendations are source-grounded design, not runtime evidence.
- Minimal C discovery is meaningful but is not one-click SSH completion. JNI exposure, real config plan, connector, unknown-host UI, lifecycle service and per-hop forwarding remain independently testable follow-ups.
- No product/spec/task-state edits made; only this research file was written. Prior authentication and PTY workspace retained.

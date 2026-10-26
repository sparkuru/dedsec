# Research: authenticated SSH session to existing terminal UI

- Query: Identify the current terminal UI/Flutter→Java bridge, managed SSH startup/config/selection capabilities, and the smallest safe integration of an authenticated SshNativeSession.
- Scope: internal; source inspection only. No implementation, build, device operation, real vault/account access, or git operation.
- Date: 2026-10-19
- Task: `.trellis/tasks/10-08-ssh-terminal-client`; authentication baseline supplied by parent/user: `251b0f3`, handoff `b4ed4e2`. This research does not repeat authentication work.

## Findings

### Current terminal path is reusable presentation, with a local-PTY-only host owner

| File | Existing role and evidence |
| --- | --- |
| `lib/main.dart:51` | Widget owns one `Terminal(maxLines:3000)`, one input focus/key, per-session history and generation (`:53-84`). |
| `lib/main.dart:113` | Subscribes to `ctos/terminal` raw byte-list events, stream-decodes UTF-8 across chunks, and calls `terminal.write` (`:113-132`). |
| `lib/main.dart:134` | xterm resize invokes `terminalResize` with columns/rows; dimensions currently omit pixel width/height (`:134-140`). |
| `lib/main.dart:295` | `startTerminal(bool privileged)` invokes `terminalStart({root,columns,rows})`; start/exit race has `terminalExitedDuringStart` (`:295-324`). |
| `lib/main.dart:327` | Stop waits for serialized writes then invokes `terminalStop`; `sendTerminal` has captured generation checks (`:327-359`). |
| `lib/main.dart:1599` | Session picker contains only App Shell and Root PTY entries. Active session shares read-only renderer, bottom IME input, shortcuts and selection page (`:1599-1777`). |
| `lib/terminal_interaction.dart:9` | `TerminalCommandInput` owns committed input reconciliation, flush/send and edit diff behavior; reusable for SSH PTY bytes. History and output snapshot are independent helpers (`:195`, `:315`). |
| `android/app/src/main/java/im/majo/ctos/MainActivity.java:24` | Single-thread `terminalWorker`; `ctos/terminal` event sink at `:50-54`; method dispatch sends `terminal*` work to that worker (`:170`). |
| `android/app/src/main/java/im/majo/ctos/MainActivity.java:215` | `terminalStart/Write/Resize/Stop` are explicit cases but directly use `terminalFd`/`Pty` (`:215-234`). |
| `android/app/src/main/java/im/majo/ctos/MainActivity.java:261` | Start creates local `Pty.start`, reads via separate `ctos-terminal-output` thread, posts bytes to main thread, closes FD and waits child (`:261-299`). |
| `android/app/src/main/java/im/majo/ctos/Pty.java:3` | Local process/JNI FD API only: start/read/write/resize/close/waitFor; it cannot stand in for an SSH channel. |

The renderer/input features are usable without a new terminal package. Existing terminal writes serialize the whole submitted Dart string; the SSH adapter must preserve that visible contract even if native stdin accepts only a bounded prefix per turn. It needs a bounded host queue and a meaningful full-queue error/deferral policy, never silent truncation or partial acceptance reported as complete.

### Lifecycle framing must change before remote output is attached

`lib/main.dart:123-130` infers exit from any decoded output containing `[session exited:`. `MainActivity.java:295-297` emits that same text as local exit notification. A remote SSH stdout/stderr stream can contain this text, so direct reuse lets remote output close UI state. The marker can also be split across UTF-8 events and missed.

Use structured host-generated lifecycle events carrying a terminal generation/session ID and typed reason/optional exit status, distinct from output bytes. The user may still see a readable exit line rendered by the UI. Never derive lifecycle from server-controlled output. Keep UTF-8 decoding incremental per session and reset its decoder when a session ends; a later session must not inherit a pending multibyte prefix.

A small compatible migration can keep existing raw-byte `ctos/terminal` data events and add a dedicated status EventChannel, or introduce a typed envelope and update both App/Root and SSH together. Contract choice belongs to main; it must be tested for late old events, immediate exit during start, and output-marker text.

### SSH managed startup is a set of safe seams, not a product connection flow

| File/API | Available behavior |
| --- | --- |
| `SshNativeSession.java:279-288` | Production `open(callback, publicLease, socketFd, hostKeyBlob, expectedFingerprint, candidateId, stepBudget)` checks exact callback-owned view; raw socket ownership transfers only on success. The caller currently supplies expected host key/fingerprint. |
| `SshNativeSession.java:27-52` | Explicit password/private-key results and phases; only SUCCESS is continuation authority. Current phase FINISHED includes both success and final non-success results. |
| `SshNativeSession.java:102-112` | Existing StepResult is bounded transport accounting, not application stdout or a channel pipe. `complete` means initial KEX only. |
| `SshNativeSession.java:362-375` | Cancel is the sole cross-thread operation; every native operation belongs to opening thread. |
| `SshNativeSession.java:378-410` | Legacy explicit service/auth progression; current FINISHED blocks step. New shell phases must explicitly extend this without weakening existing milestones. |
| `SshNativeSession.java:597-605` | Complete-capable live/proof guard exists via `requireProtocolLive`; native failure preserves lifecycle precedence. |
| `SshNativeSession.java:695-739` | Close retry sequence: native close → callback bookkeeping → role release → public lease close → native tombstone destroy. |
| `SshNativeCallbackAdapter.java:240-261` | Binds issued role/registry/authority/provider/candidate handoff; requires the same provider owns NEW proposal and CAS handoff. Adapter is created on event-loop owner. |
| `SshAuthenticationSelection.java:37-62` | Fixed strict username plus exact password entry or private-key path/identity/public blob and optional passphrase entry. Select before any native claim. |
| `SshManagedProvider.java:162-179` | Selected credentials use exact selection and current host/KEX proof; no kind-only fallback. Provider is not a UI/terminal provider (`:3-6`). |
| `SshVaultAdapter.java:65-80` | Real managed file request/public-view creation; private bodies excluded by `SshPublicFileLease.java:59-88`. |
| `SshNativeRoleBootstrap.java:162-180` | Production role open accepts bounded metadata, native supplies PID/UID; no argv/shell/secret. |
| `SshRoleContext.java:105-119` | View must stay active; monotonic deadline remains enforced; exact view object ownership. |
| `AppSecretsOwner.java:6-14` | Process-owned AppSecrets instance is already obtainable by host components; no need to expose the private vault through Dart or make AppSecretsBridge's vault public. |

No production caller outside `SshNativeSession` itself invokes `SshNativeSession.open` in `android/app/src/main/java` (checked by scoped `rg`). The complete current assembly example is the isolated synthetic fixture, not a launcher:

1. `SshPasswordAuthPlatformTest.java:214-217`: opens socketpair/peer and gets peer-generated host key/fingerprint.
2. `:228-236`: obtains managed public view and issues capped role.
3. `:238-271`: exact NEW review and concrete managed CAS provider when enabled.
4. `:272-283`: proof-bound bridge/provider/adapter and one frozen authentication selection.
5. `:287-290`: opens production session and marks socket transferred only on success.
6. `:329` onward: bounded peer/session pumping, service, then auth.

Do not copy fixture knowledge of peer host key, fixed synthetic credentials, or auto-approved NEW review into a production connect operation.

### Missing product dependencies (must remain explicit)

- There is no Dart SSH profile/config list, SSH connect form, typed SSH API or selected profile model. `lib/main.dart:1599-1657` has only local terminals.
- `AppSecretsBridge.java:31-36` deliberately has no secret/key enumeration, private vault member and no SSH business routes. `:737` exposes `sshBackupImportAvailable=false`; `lib/secrets/settings_page.dart:360-361` accurately reflects that. Native adapters exist, but this is not an existing product config editor/selector.
- Scoped source search found no managed config→resolved endpoint/username/credential-selection pipeline. `ssh_config_policy.c:125-150` checks `Options` policy; it is not a Flutter/Java profile resolver. Existing pinned policy/probe/ProxyJump research must not be promoted to production routing.
- Production open requires an already connected nonblocking FD plus host key/fingerprint; DNS/connect/timeout and discovery of the live host key are not currently connected to a product launcher. Normal JNI tests supplying fixture keys do not prove an arbitrary server launch path.
- A user-facing NEW-host review needs the existing exact proposal/CAS fields, approval lifetime and owner-thread resume contract. The current native callback is synchronous; adding a blocking main-thread dialog inside its owner loop would require a separately frozen design.
- Role/view constraints still cap this shell increment. Backend spec `ssh-native.md:72-76` records view120s/role≤60s; `SshRoleContext.java:110-113` checks the original deadline. An interactive terminal must not silently mint replacement roles or detach/reopen views to stay alive. Long-lived terminal sessions and foreground service are later contracts unless explicitly changed.

### Smallest coherent integration proposal

This is an implementation proposal, not existing behavior:

1. First complete native→JNI→Java session-channel APIs and synthetic input/output/resize/exit/cancel verification as main is researching. Keep both auth APIs and require their final SUCCESS in C and Java before session open. Preserve original proofs, cancel, view and deadlines after authentication.
2. Add a Java host terminal controller (e.g. `SshTerminalSession`) receiving an already-authenticated `SshNativeSession` on its original event-loop executor, plus a bounded main-thread output/status sink. It owns pumping, stdin pending bytes, resize coalescing, output bounds and close retry. It must not move a live session to MainActivity's unrelated executor/read thread. A controller can be created on `terminalWorker` only if role/callback/session were also opened there.
3. Unify terminal ownership at MainActivity's domain boundary: one active local/SSH terminal, generation-bound data/status publication; `terminalWrite/Resize/Stop` delegate to current terminal owner. SSH native handles/FDs and credentials remain Java/native only. UI sends bytes/dimensions and receives bytes/public status, never selection receipts as authority.
4. For this increment, a host-only authenticated-session attachment seam plus isolated synthetic fixture can prove routing through existing terminal presentation. It is valid to mark the adapter implemented while product profile/connect remains open. A visible production SSH start entry should appear only when a real host launch factory/profile descriptor exists. An always-success fake launcher or an entry that cannot start a connection is not product acceptance.
5. Reuse `TerminalCommandInput`, history, Tab line synchronization, read-only `TerminalView`, snapshot selection and all existing shortcut semantics. Show a public connection label from the accepted host descriptor, never infer mode from output. Output taps remain non-keyboard, single input owner.
6. On Stop/Activity destroy/event stream cancellation as chosen by the frozen lifecycle, request cross-thread cancel immediately, then schedule/retry close on the owner before shutting down its executor. Do not use `shutdownNow` as a substitute for owner-thread native cleanup. AppSecrets lifecycle revocation remains authoritative; a service/background survival feature is out of this increment.

If main chooses to include end-user production connection startup in this increment, the missing descriptor/resolver, socket connector, host-key admission and exact NEW-review workflow must be separately designed before implementation; the existing authenticated-session attachment does not solve them.

### Concrete owner/factory/controller design for the subsequent UI step

Use one `ScheduledExecutorService` or equivalent serialized owner command loop per SSH terminal controller. The factory and controller share this exact executor for their whole lifetime; a live SshNativeSession is never transferred between threads.

- `start(LaunchDescriptor, Dimensions, Sink)` accepts only bounded public launch identifiers/dimensions from the UI. It allocates a host-generated generation and schedules factory work on the owner executor. Factory code assembles role/view/provider/callback/selection, opens session, cooperatively pumps KEX and service/auth, and returns the actual SshNativeSession to the controller **within that owner thread** only after final password/private-key SUCCESS. It must close/retry partially-created resources on every unsuccessful branch. The async UI result carries only public generation/label/state, never the session, native token, proofs, or secret material.
- Owner state is explicit: `STARTING → AUTHENTICATED → CHANNEL_STARTING → RUNNING → CLOSING → CLOSED/FAILED`. `AUTHENTICATED` is an internal milestone; UI input is attached/enabled only after the session channel, PTY and shell requests have reached the frozen RUNNING state. REJECTED/PARTIAL/CHANGE_REQUIRED produce typed failure, no channel attempt. Start result and RUNNING event must use the same generation so an immediate close cannot be overwritten by late start completion.
- The owner pump executes one bounded native turn then yields back to its executor. It reschedules while work remains (with a bounded idle wait/readiness strategy); it must not spin forever in one executor task, which would starve queued write/resize/close commands. The native step byte budget does not alone bound wall time of all factory work; connect/auth deadlines and cancellation need explicit ownership too.
- `write(generation, byte[])` schedules a bounded copy on owner, checks RUNNING/current generation/current live contract, appends only within the frozen input queue limit, pumps partial acceptance, and returns a truthful acceptance result. `resize(generation, dims)` validates at host and schedules/coalesces only the most recent unsent dimensions. Stop can invoke the existing cross-thread `session.cancel()` and sets controller cancellation immediately, then queues cleanup on owner. Before a session exists, controller cancellation prevents factory publication and disposes its partial resources on owner.
- On output read, controller emits bounded copied byte data through the main Handler with the captured generation and checks that generation again at publication. Owner-side liveness checks precede data extraction; publication must drop stale/closed generations. Native read and status calls stay on owner. No output reader thread calls session methods.
- Suggested typed `ctos/terminal` envelopes: `{type:'data', generation:int, bytes:Uint8List}` and `{type:'state', generation:int, state:'running'|'closed'|'failed', mode:String?, exitStatus:int?, reason:String?}`. A distinct typed status channel retaining existing data bytes is also feasible, but data still needs generation fencing and per-session decoder reset. Update App/Root producers and Dart parser together if migrating the existing channel; tests should validate a strict shape and ignore stale generations before decoding or mutating UI state.
- Close completion is emitted only when required owner cleanup is confirmed; a failed close retains the controller/owner executor and exposes a bounded retry state rather than claiming resources released. Activity teardown requests cancel, schedules owner close, and shuts the executor down only after cleanup completion or an explicitly documented failure/retry owner has taken responsibility. The current MainActivity `shutdownNow` sequence is not sufficient for a newly attached SSH owner.

This design can be independently verified with a factory that creates the real synthetic session on the same owner worker, then routes command/output/resize/exit through the real controller. It does not authorize using a fake SUCCESS session, replacing the actual provider, or shipping fixture credentials/start entries in normal APK. Native/JNI/Java synthetic shell completion comes first; controller/UI implementation follows after that gate.

### Related specs and local validation

Read: `.trellis/workflow.md`, `design/README.md`, `design/plan.md`, `design/constraints.md`, current handoff, task PRD, frontend index/terminal-interaction/state-management/type-safety/quality, backend index/host-bridge/ssh-native/quality. `design/` remains the durable design landing; this file is task-scoped research only.

Tests to extend:

- `android/app/src/test/java/im/majo/ctos/SshNativeSessionTest.java`: shell cannot start after NOT_STARTED/PENDING/REJECTED/PARTIAL/CHANGE_REQUIRED; correct result choice for private key; owner/reentry/direct cancel/view/deadline before/after native; bounded data/resize/status and retry cleanup.
- New focused host controller JVM tests: queue saturation/backpressure, partial native input acceptance without duplicate/silent loss, stale events across terminal generations, cancel while queued, normal EOF/exit/close, failed cleanup preserves pending owner state.
- `android/app/src/test/cpp/ssh_native_client_test.c`: actual encrypted peer transcript, output and extended-data, resize, normal exit/EOF/close, malformed/out-of-order channel messages and cleanup.
- Existing `SshPasswordAuthPlatformTest.java`/`SshAuthFixturePeer.java` opt-in peer can be extended to actual session acceptance; no device run claimed here.
- `test/terminal_interaction_test.dart:389-700`: existing UI channel mock and `emitTerminal` helper (`:431`); extend to SSH selected mode, exact writes/resize, typed lifecycle, marker-like output treated as text, stale exit/start races and narrow layout. Keep App/Root regression checks.

Supported commands (not run by this research):

```sh
./hako flutter analyze
./hako flutter test test/terminal_interaction_test.dart
./hako flutter test
./hako bash -lc 'cd android && ./gradlew :app:testSshNativeClientHost :app:testSshCallbackAdapterHost :app:testReleaseUnitTest :app:compileSshFiles :app:lintRelease :app:assembleRelease --console=plain'
```

Fresh unit reports need timestamps; UP-TO-DATE is not a new run. QEMU/Bionic execution is separate from Gradle cross-link; existing reproducible runtime form is `timeout 60s qemu-aarch64 -L <public-API30-runtime> -E LD_LIBRARY_PATH=/system/lib64 <fresh-native-client-test>` (`design/verification.md:196`). Independent Android fixture build/test requires exact opt-in `ctosSecretsUiFixture=true`, `ctosSshAuthFixture=true`, current device authorization and separate package cleanup; normal APK must continue excluding peer library/helper. No external dependency is necessary for terminal rendering integration.

## External References

No external browsing was needed for this internal integration investigation. Versions are project source/spec evidence: pinned OpenSSH10.6p1; Android fixture API30/arm64. RFC/native channel semantics are being researched by main and are intentionally not duplicated here.

## Caveats / Not Found

- Read-only investigation only; commands above are recommendations, not pass claims.
- Findings describe files as inspected on 2026-10-19. Other agents may edit native/Java contracts concurrently; reconcile line references with final implementation.
- No production SSH profile resolver/launcher or host-key discovery callsite found. This does not mean pinned upstream lacks these capabilities; they are not connected through current Java/Flutter production code.
- Product UI start acceptance and attachment-seam acceptance must be reported separately. Do not claim a complete real-server client from synthetic controller/UI tests.

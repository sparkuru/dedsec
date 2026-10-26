# Research: Java/JNI password orchestration and Android synthetic encrypted peer

- Query: Expose explicit existing native service/password operations through Java/JNI and validate the full production callback chain against an in-process encrypted Android peer in an isolated fixture.
- Scope: internal; current task artifacts/specs, Java session/callback/provider, JNI registry, native client API, instrumentation fixture, Gradle and read-only inspection of existing shared-library symbols. Only this report is writable. No builds, device/network, real vault/account/credentials or subagent action.
- Date: 2026-10-19

## Findings

### Recommended scope and order

Implement the Java/JNI protocol API and JVM state/ownership tests first, while implementing a separately opted-in fixture peer in parallel under distinct file ownership. Then main builds normal and isolated fixture APKs, proves packaging separation, and runs only currently authorized device methods after checking target identity and unlock state. Do not stop after another C-only protocol increment: this is the direct path from existing C password implementation to Android Java→JNI→C→Java provider→C encrypted request/result runtime.

The first Android fixture should use **KNOWN host trust with a real native-revocable AppSecrets public-file lease**, and test SUCCESS/REJECTED/PARTIAL/CHANGE_REQUIRED plus cancellation/error cleanup. NEW/CAS is a separate meaningful integration issue described below; do not substitute a fake CAS/public view to claim it passed.

### Files found and reusable patterns

| File / location | Finding |
| --- | --- |
| `SshNativeSession.java:34-54,108-165` | NativeBinding default fail-closed extension pattern, ProductionNativeBinding and six unique Native methods. |
| `SshNativeSession.java:279-313,436-439` | complete boolean is initial KEX; current Java guard blocks every later step and needs explicit protocol phases. |
| `SshNativeSession.java:315-355` | Receipt getter already checks cancellation/view/lifecycle before and after native return; useful pattern for new reads. |
| `SshNativeSession.java:392-425` | Native close→callback bookkeeping→role release→view release→destroy, each retryable. |
| `ssh_session_jni.c:88-124,279-390` | Owner-bound token registry; each JNI currently holds global sessions_lock across C operation. |
| `ssh_native_client.h:19-26,104-122` | Numeric password enums0..5 and explicit begin/result + service begin/ready APIs already implemented. |
| `SshNativeSessionTest.java:91-208,241-369` | Extensible fake binding, selected open transport, metadata read lifecycle and failed-open/close ownership tests. |
| `SshNativeSessionPlatformTest.java:36-99,101-167,305-395` | Existing socketpair/real role/public lease and independent fixture guard; only fragmented banner/KEXINIT/EOF/direct KNOWN verification today, no encrypted server. |
| `build.gradle:6-60,90-118` | Strict true-only probe options, independent secretsFixture UID/test UID and opt-in source sets. |
| `build.gradle:208-248,385-426` | Production shared library/pinned archive link flags and fixture-only generated jniLibs/task dependency pattern. |
| `SshMemoryPlatformTest.java:50-73` | Existing guard checks exact fixture/test UID/package, production UID separation, Root auto-start disabled, no persistent URI grants/vault state. |
| `SshProductionBridgeTest.java:72-115` | In-memory AppSecrets.Store/envelope/unlock pattern without real files or accounts. |
| `SshVaultAdapter.java:63-81`, `AppSecrets.java:742-755` | Real public-file lease creation and attachment to vault-session revocation. |

Java source paths are under `android/app/src/main/java/im/majo/ctos`; JVM tests under `src/test/java/im/majo/ctos`; instrumentation classes under `src/androidTestSecretsFixture/java/im/majo/ctos`; C under `src/main/cpp`.

### Proposed explicit Java and binding APIs

```java
void beginUserauthService() throws VaultException;
boolean userauthServiceReady() throws VaultException;
void beginPasswordAuth() throws VaultException;
PasswordAuthResult passwordAuthResult() throws VaultException;

enum PasswordAuthResult {
    NOT_STARTED, PENDING, SUCCESS, REJECTED, PARTIAL, CHANGE_REQUIRED
}
```

Use stable explicit mapping0..5 matching C, never ordinal-based trust. NativeBinding adds default methods throwing IOException for all four; old fakes compile and cannot silently claim support or success. ProductionNativeBinding delegates to four uniquely named Native methods. New C JNI exports use existing owner token locking and call existing C functions; no username/password/receipt data is newly accepted or returned. Session export count becomes **ten**, from six+four; update packaging expectations.

Suggested JNI return shapes: begin methods void, ready boolean, password result int. JNI result initializes enum to NOT_STARTED before C accessor and throws IOException on error; Java accepts only0..5. No secret-returning Java session method. Service-ready is a protocol milestone, never a credential authorization by itself; beginPasswordAuth's C entrypoint remains authoritative for current role/KEX/service/one-shot proof.

### Java phases and KEX compatibility

Keep complete true once initial KEX completes, and retain StepResult.complete's meaning. Do not rename it authenticated or clear it during service/password continuation. Add a distinct owner-controlled phase, such as KEX / KEX_COMPLETE / SERVICE_PENDING / SERVICE_READY / PASSWORD_PENDING / PASSWORD_FINISHED.

- Unextended callers stop at complete as before. Explicit service begin requires completed KEX and no previous service transition, plus owner/live state; set SERVICE_PENDING only after successful native call.
- step permits I/O while SERVICE_PENDING or PASSWORD_PENDING even when result.complete remains true. Existing Java requireOpenLocked must be split into closed/live guard and per-operation phase guard; merely removing complete check everywhere would also reopen old verify/step behavior.
- Synchronize service milestone after native step through the ready query or an explicit public getter; synchronize password milestone through result query. Recommended step implementation queries its active milestone after successful native step so next Java step cannot accidentally hit C EALREADY only because Java phase lagged. Internal raw binding queries must avoid recursively entering public Java methods/entry guards.
- beginPasswordAuth requires SERVICE_READY, selected PASSWORD metadata and no previous auth begin; native C rejects already consumed standalone getter and checks current proofs. Session may need a non-secret callback metadata helper to know selected kind; do not reparse CSAS in Java or introduce another caller plan.
- Valid SUCCESS/REJECTED/PARTIAL/CHANGE_REQUIRED moves PASSWORD_FINISHED, with all four stopping later step/begin without automatic method retry/change. Result getter remains usable on live finished session. NOT_STARTED is valid after service-ready before begin; PENDING is expected while active; unknown integer or impossible phase regression terminalizes as malformed binding result.
- Keep receipt reads usable after KEX, service and auth milestones. Keep legacy verifyHostKey stopping rule unless explicitly documented otherwise. Metadata/result reads make no provider call.

Choose precise local phase codes before implementation (for example SSH_SESSION_PHASE for premature/wrong operation and SSH_SESSION_AUTH_COMPLETE for any finished password outcome). These are separate from transport failure codes; a local premature call should not mutate valid session state or call native provider. Distinguish typed enum negative outcomes from exceptions.

### Lifecycle, error and callback reentry

All operations are owner-thread-only except cancel. Use the stronger metadata-read lifecycle helper before and after new native operations: session cancelled flag **and direct adapter cancellation/closing**, role deadline/view active, session closed/terminal. Prefer cancellation/expiry discovered during native return over a generic native error. Existing sessionFailureCode maps role expiry to SSH_SESSION_EXPIRED; retain it. Protocol native IOException may map to fixed operation code (SSH_SESSION_SERVICE / SSH_SESSION_AUTH / SSH_SESSION_AUTH_RESULT); current JNI generic IOException does not preserve errno, so do not claim Java observed EACCES/EPROTO precisely.

Wrong owner and local phase errors should not poison owner state. JNI/native failure after a real begin is fail-closed/terminal until cleanup; success never transfers another socket or changes open/close ownership. Close/destroy cleanup must remain callable after cancel/terminal/protocol outcomes. Error/LinkageError behavior must not leave entry bookkeeping stuck.

**Global JNI registry reentry risk:** ssh_session_jni holds sessions_lock while C step/verify/new password begin calls synchronous Java callbacks. A provider reentering any session JNI on the same owner thread deadlocks before callback-level guards run. A per-instance Java flag is insufficient: A's callback may call session B.

Recommended smallest fix for this slice: a static ThreadLocal native-entry guard in SshNativeSession, spanning **every NativeBinding call**: static open, step, verify, receipt, close, destroy and all four new methods/queries. Main froze entry at the start of each owner session operation, before any managed session claim, phase or cleanup-stage mutation, and removal in finally, including RuntimeException, LinkageError or Error. Guard the whole owner operation, not merely its eventual JNI call; a reentrant static open must not first consume a managed callback claim. Reentry on the same thread into A or B rejects SSH_SESSION_REENTRANT with zero nested binding call and no nested session mutation. cancel remains allowed because it uses callback cancellation rather than session JNI mutex. Do not hold Java stateLock for the duration of native provider calls.

For close, one guard spans the whole owner cleanup operation, including native close and destroy plus intermediate bookkeeping; preserve its independently retryable stages. For step+internal milestone query, keep one entry span and use private raw queries rather than calling public guarded methods recursively. Handled nested rejection may allow the outer provider operation to finish; an unhandled rejection propagates through existing provider failure/wipe. ThreadLocal must be cleared in finally so unrelated subsequent owner operations work.

A C busy-slot lease refactor could release global registry lock before calling C and reject reentry using slot busy state, but it changes slot lifetime/destruction and all exception/cleanup paths. It is a heavier alternative and unnecessary if every session Java binding call is guarded now. Future native clients bypassing Java wrappers would need a separate native lease solution; do not claim ThreadLocal solves arbitrary C reentry.

### Android encrypted peer without production contamination

Use a new narrowly opted-in fixture library, e.g. `libctos_ssh_password_peer.so`, and a new instrumentation class/method rather than changing the old exact transport method's scope. Suggested paths:

- `android/app/src/androidTestSecretsFixture/cpp/ssh_password_peer_jni.c`
- `android/app/src/androidTestSecretsFixture/java/im/majo/ctos/SshPasswordAuthPlatformTest.java`
- `android/app/build.gradle` for strict option and isolated compile/source-set task.

New `ctosSshPasswordProbe=true` must require `ctosSecretsUiFixture=true`, reject any value other than true and be mutually exclusive with existing broad SSH probe modes. Put output under a dedicated generated directory included **only** in secretsFixture.jniLibs when option present. Do not add source or generated peer path to main source set/compileSshFiles. Depends on production compileSshFiles; normal APK never packages stale peer output.

A useful simpler link strategy is available: current production libctos_ssh_files.so already exports ssh_init, ssh_add_hostkey, sshkey_generate/to_blob/free, ssh_packet_next, ssh_input_append, ssh_output_ptr/consume, sshpkt helpers and ssh_free. Read-only `readelf --dyn-syms` inspection confirmed GLOBAL exports in the existing artifact. Build peer with pinned headers and `-L<generated/jniLibs/arm64-v8a> -lctos_ssh_files`, --no-undefined, PIC/shared/API28/16KiB page size and existing warning flags. DT_NEEDED must be basename libctos_ssh_files.so, not an absolute build path. Ensure production library is loaded before peer from the fixture's fixed target native-library directory; guard package/UID before loading. Validate resulting dependency in ELF packaging checks.

This reuses exactly the production pinned OpenSSH implementation and avoids a second OpenSSH/role-global copy. If instead statically linking the production archive, patched KEX references need guard symbols and duplicate exports/globals need isolation; it is a heavier route. Do not link duplicate role/callback/native client objects just to satisfy those symbols. A production-shared dependency with exported existing symbols is the minimal recommendation, without expanding its export set.

Suggested concrete helper APIs: peerOpen(borrowedServerFd, fixedOutcomeMode), peerHostKeyBlob(handle), peerPump(handle), peerSummary(handle) returning a small fixed int array, and peerClose(handle). Peer API exposes only fixture-owned handle creation, public host-key blob, one bounded pump, fixed numeric counters/result and close. It has no shell, endpoint, connect/listen, file path, arbitrary argv, secret extraction or external account. Use one AF_UNIX socketpair. Let Java own the pair and peer internally duplicate the borrowed server FD with CLOEXEC/nonblocking; original server descriptor stays Java-owned. Close both original+duplicate for EOF tests. Native peer must validate descriptor/socket type and fixed outcome mode, keep owner thread/deadline/slot bounds, and reject duplicate/foreign handles.

Generate ephemeral Ed25519 server host key, return only public blob; initialize ssh_api server mode with same pinned proposal helpers as C fixture. KEX guard server hooks already return0 without granting a client role. Pump consumes/emits at most a fixed small budget, parses exact fixed ssh-userauth request and password USERAUTH fields, compares fixed synthetic username/password bytes and sends a fixed chosen reply. Server private key and borrowed parsed payloads never escape; free/wipe on close. No background thread is needed: Java alternates production session.step with bounded peer.pump on the same owner thread.

Peer modes can cover SUCCESS, FAILURE false, FAILURE partial, CHANGEREQ, malformed success, delayed reply/EOF. Inputs are numeric scenario modes, never arbitrary server text/credentials. Assert peer request count1/service count1/exact field checks via bounded counters, without returning plaintext password to instrumentation diagnostics. Existing native C synthetic peer code supplies packet-loop structure; copy the bounded minimum rather than compiling its main/full suite into APK.

### Synthetic AppSecrets provider and real callback chain

Construct an in-memory AppSecrets.Store in the new test, using fixed fixture-only password/envelope material and a manifest with one SSH_PASSWORD entry, config and matching KNOWN_HOSTS generated from peer public blob. Follow SshProductionBridgeTest memory-store setup; the store read returns cloned memory, commit updates only owned memory, quickMaterial is empty. No Android vault path/preferences/SAF is used. Wipe store/envelope/password material and lock vault in finally.

Set foreground on this isolated AppSecrets session and unlock fixed synthetic material before starting short SSH deadline. Open a real public lease with SshVaultAdapter.fileRequest/openPublicFiles, issue real native role against it, bind production SshNativeCallbackAdapter with real SshManagedProvider/SshCredentialBridge, and freeze PASSWORD selection before any callback/claim. Legacy CredentialRequestSource should count then throw, proving selected path never downgrades. Known trust needs no fake CAS or trust approval.

Open production SshNativeSession, verify CSAS receipt read, drive real KEX until complete, assert host/KEX proof exists and selected source count0, begin service and drive ready, begin password and drive enum. This executes full production JNI selected callback and final AppSecrets guard on Android. Assert the peer saw exact username/password request once; never log bytes. Cleanup production session first, then any retained callback/role/view on failed open, peer/FDs, registry and in-memory vault. Assert fixture guard remains fresh and no files/packages/processes remain after main's cleanup.

### NEW/CAS integration gap: do not hide it

A true NEW path needs SshManagedKnownHostsProvider.snapshotFor/bindForNativeHandoff and its exact native candidate channel. Current production adapter.bind(...provider) lacks candidateHandoff parameter; only the injected-binding full overload can carry it (`SshNativeCallbackAdapter.java:199-252`). A production forwarding overload would be needed for a real Android NEW/CAS test.

There is also a substantive view-lifetime interaction: `AppSecrets.commitManifest:627-647` commits trust and calls `session.unlockIfEpoch`; `VaultSession.unlockIfEpoch:33-39` calls lock, and `lock:67-74` revokes the attached SshPublicFileLease. A role issued against that lease then fails requireActive, potentially aborting native KEX immediately after real CAS. Existing JVM known-hosts tests use a fake PublicView and do not prove this real native lease interaction. A fixture using a synthetic separately created/unattached view to pass NEW would conceal this gap.

Therefore recommend first Android password runtime explicitly KNOWN. NEW/CAS+native revocable lease needs a separately frozen policy for trusted CAS versus lock/epoch/public-snapshot lifetime and actual regression. Do not weaken revocation or fake COMMITTED to force this gate. Local C NEW/CAS packet tests remain useful but are not Android real-AppSecrets CAS evidence.

### Minimal implementation ownership and gates

1. Production Java worker owns SshNativeSession.java and SshNativeSessionTest.java: APIs, strict phases/enums/default failures, ThreadLocal covering all session binding calls, lifecycle/error/cleanup tests. Main owns contract/spec/doc integration.
2. Production JNI worker owns ssh_session_jni.c: four unique owner-token wrappers, C enum mapping, JNI exception/local-ref/lock hygiene. It must follow frozen Java names, not change C protocol state or callback API.
3. Fixture worker owns new test Java and peer JNI plus narrowly assigned build.gradle probe sections; if same file would conflict, main integrates Gradle changes after peer task supplies exact compile requirements. Test helpers stay in fixture source set. No worker builds or touches device; main serializes execution.

JVM matrix: old KEX-only stop; explicit service/auth pending step; strict enum0..5 and impossible result; defaults fail closed; phase/precondition no-call behavior; wrong owner, direct adapter.cancel, view/role expiry before/after each binding; negative typed results versus errors; close retry after every outcome. Add fake binding hooks that reenter A and B from A.step/beginPasswordAuth into all session operations, including static open/close/destroy; prove zero nested binding calls and ThreadLocal reset after exceptions/Error. cancel must remain usable inside a callback.

Local build gate: focused JVM + lint, strict native/JNI/normal Release; normal archive remains83 and forbidden stock caller symbols0, production session JNI exports10. Then isolated fixture assemble/test assemble/lint/signatures/manifest UID, peer needed libs and JNI checks, and normal APK exclusion even when fixture outputs exist. Actual C QEMU remains useful but cannot replace Android JNI.

This research does not grant, renew or exercise device authorization; no board action was performed. Device gate belongs to main only: confirm current explicit authorization covers independent synthetic install/run/cleanup for 192.168.9.14:5555, exact device identity/API/ABI and unlocked user. Use strict exact class#method and true opt-in, bounded instrumentation timeout, fixture/test APK hashes/signatures, formal package baseline hash and final uninstall/process cleanup. Current ongoing-session authorization may already cover this scope; do not ask again based solely on historical date if user grant still applies, but do not infer authorization from old verification records alone. No external server or real account is needed.

## Related specs

- `.trellis/spec/backend/ssh-native.md`: completed C password result, selected callback proof/final effect guard, CSAS/JNI ownership and fixture isolation contracts.
- Current task PRD/design/implementation top sections: C-only password completed, Android Java/JNI still absent.
- `design/constraints.md`: exact current target/authorization, unchanged production app and honest per-environment runtime evidence.
- Prior `research/password-userauth-20261019.md` and `research/selected-credential-callback-20261019.md`: retain protocol and secret lifetime contracts rather than reimplementing auth in Java/fixture.

## External references

None. All findings derive from current local code/configuration and read-only exported-symbol inspection. No source/library/version assumptions required a live web lookup.

## Caveats / Not Found

- This report contains no implementation/build/device result.
- Current fixture never completes real KEX and therefore cannot validate selected credential JNI or authentication; a real encrypted peer is required.
- C session registry's global lock requires guarding same-thread cross-session Java reentry, not merely same-instance recursion.
- serviceReady is not a fresh authorization proof; native begin/password result remains responsible for current proof checking.
- Real Android NEW/CAS and public-view epoch/revocation interaction is a separate unresolved integration gap; first KNOWN runtime should not claim it.
- Private-key parsing/signing/auth, selected passphrase/KBI capability, channels/PTY and product UI remain subsequent work; exposing password protocol APIs and testing Android is now directly implementable.

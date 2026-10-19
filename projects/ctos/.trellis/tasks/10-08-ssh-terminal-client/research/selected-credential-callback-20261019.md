# Research: receipt-bound native selected credential callback

- Query: Connect retained CSAS selection and current proved KEX/service state to one bounded typed secret callback, without USERAUTH packets, private-key parsing, a Java session secret API or real credential access.
- Scope: internal; current callback C/JNI/Java pipeline, managed provider/bridge, native client CSAS/KEX/service state and synthetic C/JVM tests. Only this report is writable; no build, Docker, device, network, real vault/account/credentials or subagent action.
- Date: 2026-10-19

## Findings

Implementation decision after research: the low-level C callback remains a shared
transport and receives no separate callback-slot one-shot flag. The native client
owns its attempt flag; Java selected routes share their own flag. The frozen
contract in `ssh-native.md` takes precedence over the optional slot-claim advice
below. No runtime claims are made by the research proposals; actual results live
in `design/verification.md`.

### Recommended smallest production connection

Add one new optional selected-byte target/API to `ssh_callback_adapter.[ch]`, one unique Java callback name cached by `ssh_callback_jni.c`, and one native client entrypoint deriving all request metadata from its retained selection and accepted KEX. Preserve the legacy callback API for unselected callers. Do not fall back from the selected path to kind-only byte/boolean callbacks.

Main selected the following direction during this investigation:

- C callback transport carries kind, receipt32, cycle and sessionDigest32 plus bounded caller-owned output/capacity/length.
- Java callback uses unique `onNativeSelectedCredentialBytes(int, byte[], long, byte[])`, JNI signature `(I[BJ[B)[B`.
- Native client entrypoint accepts only client/output/capacity/length; callers cannot supply kind, receipt, cycle or digest.
- Require selection, SERVICE_READY, one successful completion report, exact current guard cycle, live owner/environment/role/view/deadline and matching current session digest.
- Wrong owner/environment and pre-ready probes do not consume the one-shot attempt. Consume after valid authorization but before provider entry; provider failure or uncertain completion cannot be retried.
- All Java selected byte routes, including the old managed object-receipt overload, share the selected-attempt flag; unselected legacy byte routes retain their old behavior. Once a selected native-session claim is first issued, sticky-reject the old managed object overload, even if open fails. The new native callback requires nativeSessionOwned.
- C client obtains bytes into bounded owned scratch and only copies to caller output after post-provider proof/liveness validation. Wipe/free scratch on every exit; failure wipes caller output and sets length zero.
- New provider/bridge overloads carry a final EffectGuard from the native selected callback: check same invocation/selection/hostProof/kexProof/native arguments, cancellation/closing and clock immediately before the AppSecrets receiver, and again before return.
- Scratch allocation is preflight and may fail without consuming; mark the attempt immediately before selected callback invocation. Any later error or guard failure is terminal and cannot retry.
- No Java `SshNativeSession` method returns secrets; session JNI export count remains six. No authentication packets, key parsing or actual vault changes.

### Files found and current patterns

| File / location | Relevant current behavior |
| --- | --- |
| `ssh_callback_adapter.h:96-123,169-177` | Target contains optional legacy bounded byte function and public API; append selected function at end for designated-initializer compatibility. |
| `ssh_callback_adapter.c:382-448` | Required target checks and shared begin/finish enforce owner pthread, exact environment, reentry, cancel, live role and deadline. |
| `ssh_callback_adapter.c:452-480` | Existing one-shot candidate claim is a useful callback-slot lock/claim pattern, but selected attempt must have its own bit. |
| `ssh_callback_adapter.c:825-881` | Legacy byte API clears output/length before validation, bounds capacity and wipes on provider/finish failures. |
| `ssh_callback_jni.c:13-23,464-529` | Cached method IDs and bounded byte-return copy/array wipe pattern to extend for selected metadata arguments. |
| `ssh_callback_jni.c:570-607` | Current byte method `(I)[B` is optional; selected method can be independently optional, with no fallback. |
| `SshNativeCallbackAdapter.java:517-534,590-654` | Managed host/KEX proof state and shared byte pipeline; presently object-selected route may release repeatedly. |
| `SshNativeCallbackAdapter.java:814-828` | Frozen selection can encode canonical CSAS using the issued token. Public adapter export helper rejects inCallback; the new callback must not recursively call that helper. |
| `SshManagedProvider.java:164-179` | Selected overload directly opens bridge selection and bypasses legacy CredentialRequestSource. |
| `SshCredentialBridge.java:151-209` | Captured selection, host/KEX proof and role/operation checked both initially and at final AppSecrets effect guard; request always closed. |
| `SshKexProof.java:14-15,47-56` | Positive cycle and defensive digest accessor; existing requireMatches checks role/host but not caller-supplied expected cycle/digest. |
| `ssh_native_client.c:171-232` | Validated retained canonical CSAS and receipt; kind currently exists only in retained wire, not a separate field. |
| `ssh_native_client.c:596-629` | Successful completion reports digest then retains cycle/count, but does not yet retain that exact successful digest. |
| `ssh_native_client.c:643-674` | Service liveness checks callback owner first, view/deadline, exact current KEX-ready cycle and unique completion count. |
| `ssh_native_client.c:1014-1041,1050-1104` | Read-only receipt liveness checks and cleanup; close retains storage on failure and wipes on successful close. |
| `ssh_kex_guard.h:8-21` | Current ready proof is bound to native ssh/role/hop/nonce/connection binding; no arbitrary cycle setter or proof injection is available. |

Java filenames are under `android/app/src/main/java/im/majo/ctos`; C files under `android/app/src/main/cpp`. Existing source-set registration should cover these changes without new objects or build-script edits.

### Proposed C transport signatures and behavior

```c
/* Optional target function; absence must never fall back. */
int (*credential_bytes_selected)(void *context, void *environment, int kind,
    const unsigned char receipt[32], uint64_t cycle,
    const unsigned char session_digest[32],
    unsigned char *output, size_t capacity, size_t *length);

int ctos_ssh_callback_credential_bytes_selected(ctos_ssh_callback_token,
    void *environment, int kind, const unsigned char receipt[32], uint64_t cycle,
    const unsigned char session_digest[32],
    unsigned char *output, size_t capacity, size_t *length);

int ctos_ssh_native_client_selected_credential_bytes(
    struct ctos_ssh_native_client *, unsigned char *output,
    size_t capacity, size_t *length);
```

The callback layer is a typed synchronous transport, not independently a SERVICE_READY authority. Its arguments are supplied by the client, and the Java target verifies the retained managed selection/KEX claim. Full service/current native proof gating resides in the native client entrypoint.

- Initialize length zero and wipe any valid bounded output prefix before other failures, matching existing byte contract. Null output/length or zero capacity → EINVAL; over-limit capacity → EOVERFLOW, wiping only the contract-bounded prefix. Supported selected kind is only PASSWORD=1 or PRIVATE_KEY=4.
- Receipt pointer/digest pointer must be non-null, cycle in 1..INT64_MAX and digest nonzero32. Receipt is exact32 metadata: Java comparison to the canonical expected digest is authoritative, not a caller Boolean or merely a nonzero check.
- Run shared begin_callback before marking an attempt. Wrong owner/environment → EPERM without consuming or poisoning original owner; malformed structural arguments reject before provider entry. A duplicate claimed selected attempt → EALREADY, zero output, no target call.
- A callback-slot selected-attempt flag is advisable as defense for callers of the transport API outside native client. Claim under existing callback mutex after begin checks and before invoking target. Missing selected target → ENOSYS, consumed/failed with no old callback invocation. Preserve legacy valid_target requirements; making the new selected target mandatory would break old fixture/legacy users unnecessarily.
- Validate provider output nonempty and ≤capacity and MAX_CREDENTIAL_BYTES. Finish_callback must run on every begun target path; finish cancellation/role expiry supersedes a claimed success. Failure wipes output and length. Keep original precise provider failure errno where possible instead of obscuring it with a later generic cancellation read.

### Native client authorization and proof lifetime

Retain `completion_session_digest[32]` only after the original KEX callback succeeds and completion count/cycle are accepted. Wipe it during successful close/destroy. Recompute current `ssh->kex->session_id` SHA-256 during each selected authorization check and compare to this retained digest. A nonzero cycle alone does not bind the exact session bytes supplied to managed proof.

Store the already validated selection kind while parsing CSAS, or derive it using the known retained canonical header; a validated field is simpler and avoids a second ad hoc parser. No caller metadata is accepted. Receipt is the retained SHA-256 of the full validated record.

Suggested entry sequence:

1. Validate output/length/capacity; zero caller state. Reject null/closed/failed/previous attempt with the frozen error convention.
2. Check owner/environment via no-provider check_live before any mutable client error transition. Reject wrong owner with EPERM, retaining valid owner's attempt.
3. Check selection exists and service phase exactly READY; pre-ready/legacy absence → EACCES with no target/provider call or attempt consumption. Do not terminalize an otherwise usable KEX-only/pre-service session merely for this premature probe.
4. Reuse or factor current service liveness check for view, role, deadline, completion_calls==1, nonzero saved cycle and exact `ctos_ssh_kex_ready`. Verify ssh/kex exist, kex done and freshly computed session digest matches the successful completion digest. State/proof invalidity is fail-closed, terminal and cleanup remains retryable.
5. Allocate bounded scratch as main-selected preflight: ENOMEM does not consume because no provider call has begun. Once the claim is made immediately before callback invocation, every target/provider outcome consumes. Do not accidentally reset the bit on error or close retry.
6. Mark native client attempt used before crossing callbacks. Pass derived kind/receipt/saved cycle/digest to new API. Never call the old kind-only API, even when the selected target is missing.
7. If callback succeeded, repeat no-provider owner/cancel/role/view/deadline/current guard and recomputed-session-digest checks before copying scratch to caller output. Recheck the service phase and selected identity as internal invariants. Reject late cancellation, view revoke, role expiry, proof/session drift and reentry; wipe scratch/output, length zero, terminal client.
8. On success copy only returned length to the already zeroed caller output, publish length, wipe/free full scratch. Do not queue a USERAUTH packet, set authenticated/channel state or change KEX complete/service-ready semantics. Caller owns successful output and must promptly wipe it after use.

A successful return is linearized at final validation/copy. Concurrent cancellation can always arrive after that success boundary; do not claim it retroactively erases bytes already returned to the authorized owner. Deterministic mid-provider cancellation must be caught before bytes leave this entrypoint.

### Java selected callback and one-shot compatibility

Use `onNativeSelectedCredentialBytes(int kind, byte[] receipt, long cycle, byte[] sessionDigest)` with JNI signature `(I[BJ[B)[B`. Run existing begin/finish, not another lifecycle state machine. Snapshot/clone bounded 32-byte metadata inputs at entry to avoid direct Java callers mutating claims during provider work; wipe scratch after all exits.

Before provider entry:

- Require nativeSessionOwned, frozen selection, stable supported kind and exact selection role. Re-encode immutable frozen CSAS using `issued.token` directly (or compare cached expected digest). Do not call public `authenticationSelectionWire()` from inside a callback; it deliberately rejects inCallback.
- Compare receipt to SHA-256 of that full exact record; wipe temporary wire/digest.
- Require live current hostProof and kexProof, their existing role/host matches, and supplied positive cycle equal to current kexProof.cycle; compare supplied digest32 to a cloned kexProof.sessionIdDigest(), wiping it afterward.
- Capture exact frozen selection/hostProof/kexProof object references. After all checks, atomically claim shared selected-attempt flag before provider. Duplicate → SSH_AUTH_SELECTION (or an explicit frozen duplicate code), no provider.
- Use existing selected provider overload, never CredentialRequestSource. Unsupported selected passphrase/KBI still reject.

After provider returns, repeat role/live cancellation checks and compare the current adapter frozen selection/hostProof/kexProof against captured references plus expected cycle/digest; then existing finish serial/lifecycle check. Wipe provider result on any mismatch/exception/failed finish. A missing/invalid/null/oversized result and a provider exception consume the attempt and cancel/terminalize exactly as current finish failure handling does.

All selected paths share the attempt flag, including `onNativeCredentialBytes(kind, selection)`: old→new and new→old must not release twice. The old object path remains available only for managed-only compatibility. Main decided that the first selected native-session claim permanently locks that route out, including after failed open; the new native method requires nativeSessionOwned. Selected kind-only callback still rejects entirely, unselected legacy behavior remains unchanged. A shared one-shot does not itself give an old managed call native SERVICE_READY/current-cycle authority.

**No repeated selected-success test dependency was found.** `SshProductionBridgeTest.java:711-725` creates a new SelectedSetup for each key/KNOWN-or-NEW case and releases once. Setup test around `:658-682` also releases once. In contrast, legacy unselected test `:255-310` intentionally returns password then private key on the same adapter; preserve it.

### Effect guard and TOCTOU boundary

Current selected bridge final guard repeats exact selection object/kind/entry/path/key identity, live role, captured host/KEX proof match and vault operation binding (`SshCredentialBridge.java:167-190`). Preserve it. It checks the captured immutable KEX object, not adapter current state/cancel flag. Existing same-owner/reentry guards prevent a legitimate concurrent owner from replacing current proof while provider is running; cross-thread cancel is permitted and is caught by outer finish and native post-checks.

Main chose the stronger pre-receiver guarantee: add provider/bridge overloads accepting an optional final EffectGuard, preserving old callers with null. The new native selected callback supplies a guard checking its same live invocation, frozen selection, current host/KEX proof references, exact native receipt/cycle/digest, native-session ownership, cancellation/closing and current clock. Execute it inside the existing bridge final guard immediately before the AppSecrets receiver, and repeat after provider before finish. Do not weaken or replace existing vault/selection/operation checks. AppSecrets/VaultOperation need no changes for PASSWORD/PRIVATE_KEY.

The guard must recognize its own already-consumed authorized attempt rather than reject merely because the one-shot bit is now true. It must not recursively begin another callback. Avoid holding adapter stateLock across the whole vault call; take it only for small state snapshots/validation. Reentry/cancel must be able to invalidate the pending invocation, which then wipes any handoff bytes.

### JNI copying and cleanup

- Add cached selected jmethodID and target function at the end of current context/target setup. Missing Java selected method leaves selected handoff unavailable (ENOSYS), without substituting old `(I)[B` or Boolean routes.
- Validate attached JavaVM/JNIEnv and signed cycle bound before any Java call. Allocate exact32 arrays for receipt/digest, populate, call unique Java method, and process result using existing nonempty/cap/maximum checks.
- Clear pending Java exception as existing contract does and map to EFAULT; null result → unavailable/failure; preserve bounded output wiping.
- On every path wipe returned Java secret array before dropping local reference, including malformed/oversized output and copying/Java exception; reset output length and wipe C buffer on error. Wipe metadata arrays/scratch if retained, and delete all local references regardless of result. Do not log payload, username or resource.
- Java returned array is a fresh provider-owned handoff copy. JNI wiping cannot erase every upstream or vault copy; each existing layer retains its own wipe responsibility. No method added to session Native class, no new session JNI export, no C session secret getter callable from UI.

### Test matrix and reusable fixtures

**Native callback host:** extend `ssh_callback_adapter_test.c:test_credential_bytes`/target builders with selected target counters and exact receipt/cycle/digest assertions. Cover optional target absence/no fallback, supported and unsupported kinds, null/bad bounds/output/cap, wrong env/thread then correct owner success, exactly one target invocation on duplicate, partial write then failure, malformed returned length, too-small output, post-target cancellation/deadline/reentry and cleanup retry. Existing callback delay/cancel-thread fixtures around `:390-449,505-538` are directly reusable. Legacy tests must still allow their two unselected releases.

**Native real packet client:** extend CSAS fixtures and current synthetic encrypted service fixture to complete KNOWN and NEW/CAS KEX and SERVICE_ACCEPT, then call new entrypoint once for PASSWORD and PRIVATE_KEY declarations. Assert target receives the retained receipt, cycle1 and SHA-256 of actual peer session ID; expected fixed synthetic bytes only, no old credential target call, no USERAUTH packet or authentication state. Mutating caller wire after open must not change selected request. Callback test counters currently expect zero credential calls globally in cleanup; split new selected counter/expectation per test without weakening legacy no-credential assertions.

Probe pre-KEX, completed-but-service-disabled, request-pending/awaiting and legacy null selection: EACCES, zero output/length, no provider/attempt; then drive same valid selected fixture to READY and prove first attempt succeeds. Wrong-thread probes also leave valid owner attempt available. Test duplicate success, provider failure/missing target/oversize/zero output, mid-provider callback cancel/view revoke/role expiry and delayed deadline; post-check rejects bytes and later attempt cannot retry. Native callback can mutate the fixture's own role/view via existing handles; no need to expose internal ssh pointer solely to test a guard mismatch. Dynamic proof/session drift not reachable through a public fixture hook should be stated as source-reviewed rather than fabricated runtime coverage.

**JVM:** extend SelectedSetup in `SshProductionBridgeTest.java:625-653`. Compute exact CSAS digest before callbacks, prove KNOWN/NEW KEX, claim/finish a fake native session as owned, then use new unique callback for both selected kinds. Preserve cleanup bookkeeping so the fake claim is released through the current owner contract. Same-kind alternate fixture resources and legacy source counter already prove fixed selection. Add receipt null/short/long/changed/other selection, cycle0/negative/wrong, digest null/short/zero/wrong, no host/KEX, foreign role and unsupported kind. Each rejected selection must leave source/provider effect count zero. New success returns exact selected secret and caller wipes it.

Explicitly cover new→new, old object→native claim/new and new→old object duplicates, old object refusal after failed native claim, new native callback refusal without nativeSessionOwned, provider failure then retry, wrong-thread then owner success, cancellation/expiry/reentry during provider/result handoff, returned array wipe on failure, role/view revoke, final guarded request closure. Current `selectedFinalEffectGuardRepeatsRoleLivenessAfterInitialGuard` (`:825-842`) supplies a clock-triggered final-guard pattern; use meaningful synchronization/hooks, not sleeps for JVM races. Keep native session tests/source unchanged unless a shared interface actually changes; no Java session secret API is needed.

Main runs strict native/callback build, JVM focused gate, actual ARM QEMU/Bionic service/client suite and appropriate normal lint/Release/package verification. These separate JVM and C executions plus JNI compile/link do not establish a full Java→JNI→native selected secret runtime on Android; no device action is needed or authorized by this research.

## Related specs

- `.trellis/spec/backend/ssh-native.md`: CSAS v1 wire/admission/receipt, immutable managed selection, callback owner/finish, native service/current-cycle and retryable close contracts.
- `.trellis/spec/backend/app-secrets.md`: typed consumer and final protected effect/one-shot vault operation semantics.
- Current task PRD/design/implementation plan; `design/README.md`, `design/plan.md`, `design/constraints.md` confirm this is the next local slice and full userauth remains open.
- Prior `research/auth-selection-20261018.md` and `research/native-auth-selection-20261018.md` remain valid for selected passphrase limits, blob-only declaration and exact role binding.

## External references

None. Current local code supplies this transport/ownership research; upstream authentication internals were not revisited.

## Caveats / Not Found

- No implementation, build, protocol or runtime result is produced by this report.
- Current native completion saves cycle but not the successfully reported session digest; retaining and comparing it is required for the new proof-bound request.
- The low-level callback API is transport. Only native client can require SERVICE_READY; matching metadata alone must never manufacture that state.
- New shared one-shot state narrows selected compatibility behavior while preserving intentionally repeatable unselected legacy tests.
- Current bridge final guard is not yet an adapter-current-state guard; main selected explicit provider/bridge final EffectGuard plumbing for the new native route, as described above.
- Actual USERAUTH, private/public-key parsing/match, selected passphrase/KBI capability and full Android selected callback runtime remain open.

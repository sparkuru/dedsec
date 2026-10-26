# Research: Managed config evaluator JNI/Java ownership seam

- Query: Safely expose the implemented native readconf evaluator to Java while retaining full native Options, revocation, bounded diagnostics, retryable cleanup and immediate cross-thread cancellation.
- Scope: internal; read-only source investigation, with this research file as the sole write.
- Date: 2026-10-19

## Findings

### Files found

- `android/app/src/main/java/im/majo/ctos/SshPublicFileLease.java`: shared public-view lease and opaque native token; closing it revokes the entire view.
- `android/app/src/main/cpp/ssh_view_handle.[ch]`: one-current-view registry, native borrows, deferred destruction and retryable borrow release.
- `android/app/src/main/cpp/ssh_config_eval.[ch]`: complete native evaluator lifecycle, copied request, structured diagnostic, bounded summary and native-only full Options getter.
- `android/app/src/main/cpp/ssh_session_jni.c`: existing bounded token table, owner-thread lookup, JNI exception and array-copy patterns; cleanup caveats below.
- `android/app/src/main/java/im/majo/ctos/SshNativeSession.java`: Java owner-thread/native-entry guard, injectable NativeBinding and staged cleanup pattern.
- `android/app/src/main/java/im/majo/ctos/VaultCodec.java`: strict UTF-8 decoder and buffer wiping helpers.
- `android/app/src/main/java/im/majo/ctos/SshAuthenticationSelection.java`: strict ordinary UTF-8 encoder with malformed/unmappable reporting.
- `android/app/src/test/java/im/majo/ctos/SshNativeSessionTest.java`: injectable ownership/failure/reentry test pattern.
- `android/app/src/test/cpp/ssh_view_handle_test.c`: real CSPV registry borrow/revoke/stale-token test pattern.
- `android/app/src/test/cpp/ssh_config_eval_test.c`: real pinned-parser and view-lifecycle regression base.
- `android/app/build.gradle`: independent parser objects, production shared-library link and host verification tasks.

### Existing contracts, with source evidence

1. Java receives an opaque positive token, not a pointer (`ssh_view_handle.h:9-10`). `SshPublicFileLease.nativeToken()` only checks Java active/token state (`SshPublicFileLease.java:133-136`); native borrow independently checks current view, revoke and original deadline (`ssh_view_handle.c:173-200`). Native validation is mandatory because revocation can race after the Java check.
2. A borrowed view prevents destruction, but not revocation. Destroy marks revoked and postpones destruction until borrows reach zero (`ssh_view_handle.c:155-170`); borrowed-live checks reject revoke (`ssh_view_handle.c:233-252`). Stale-token destruction cannot affect a newer view (`ssh_view_handle.c:160-164`).
3. **The evaluator must borrow the caller's lease and never close/revoke it.** Java `close()` calls `revoke()` (`SshPublicFileLease.java:142-149`), which destroys the shared native token (`SshPublicFileLease.java:154-165`). Copying `SshNativeSession.close`'s final `view.close()` stage (`SshNativeSession.java:1015-1017`) would incorrectly revoke the caller's still-useful view. The new Java ViewBinding needs only token()/active(), without close().
4. Evaluator open takes ownership only at successful return (`ssh_config_eval.c:407-438`). Before that, it validates/copies text, root, IO and a final borrow-live check. Failure leaves the borrow with the caller. Close releases Options/readers first and only then the borrow; a failed reader unmap or borrow release leaves the object retryable (`ssh_config_eval.c:546-561`). Destroy requires CLOSED (`ssh_config_eval.c:563-566`).
5. Borrow release itself can fail: it preserves a released borrow with its owner if final view destruction fails (`ssh_view_handle.c:255-296`). Dropping that pointer on failed-open rollback loses the cleanup retry point. Existing session JNI ignores rollback failures and clears its slot (`ssh_session_jni.c:241-254`); **do not copy that rollback behavior into this seam**.
6. Evaluator is owner pthread/PID/UID bound (`ssh_config_eval.c:86-96`); all summary/Options getters check owner, cancel and borrowed-view lifetime, then READY (`ssh_config_eval.c:526-541`). Cross-thread cancel is an atomic store only (`ssh_config_eval.c:543-545`). Parser run uses a dedicated trylock, releases it on completion and does not hold session/callback locks (`ssh_config_eval.c:440-445,518-524`).
7. The existing JNI session registry has 64 slots, reservation before native ownership transfer, and owner checks (`ssh_session_jni.c:15-28,55-125`). Its unsigned counter stops at UINT64_MAX (`ssh_session_jni.c:68`), which is unsuitable for a new ABI promising positive Java long: the new counter must stop at INT64_MAX, never reuse a prior token and return EBUSY when slots/exhaustion prevent admission.
8. Existing `copy_string` uses JNI modified UTF-8 (`ssh_session_jni.c:166-179`). It is not suitable for ordinary UTF-8 evaluator input: supplementary characters and U+0000 do not have ordinary UTF-8 representations there. The evaluator validates ordinary UTF-8 and rejects NUL/control bytes (`ssh_config_eval.c:143-178`). Java should use a REPORT encoder as in `SshAuthenticationSelection.java:110-121`, then byte arrays/wire; output should use a REPORT decoder as in `VaultCodec.java:30-39`. Do not use GetStringUTFChars/NewStringUTF.
9. Native run pre-clears its diagnostic, then may fail before parser entry for owner, repeated run or parser-busy admission (`ssh_config_eval.c:440-445`). Those failures have errno but diagnostic.reason=OK. Actual evaluator failures populate a stable reason/object/line/errno and transition FAILED (`ssh_config_eval.c:98-111,518-524`). The bridge must distinguish these categories, not replace all failures with INPUT or a generic IOException.
10. Summary is a bounded copy with alias/hostname/user, flags/counts and `has_raw_fields=1` (`ssh_config_eval.h:40-49`, `ssh_config_eval.c:503-515`). Full Options remains evaluator-owned and native-only (`ssh_config_eval.h:56-59`). A Java snapshot confers no launch capability or continued freshness.

### Recommended bounded API

Use separate `ssh_config_handle.[ch]` for the independently testable registry, thin `ssh_config_jni.c`, and package-private `SshConfigEvaluator` with Request, Summary, Diagnostic and injectable NativeBinding. No MethodChannel, catalog/import, validator, socket connector, role or credential changes are needed for this increment.

- `open(viewToken, requestBytes) -> positive handle`: copy/validate all JNI input before borrowing. Registry slot owns either evaluator or pending borrow, never an exported pointer. Request includes alias, optional explicit remote user and fallback, numeric port, explicit virtual local user/hostname/UID, optional tag/remote-command context; preserve native optional-empty semantics and exact byte limits (255 ordinary text; 8192 remote command).
- `run(handle) -> bounded versioned diagnostic`: one evaluation, owner only. Keep complete Options native for future native consumers. Do not add raw-options serialization/getters or a generic token-to-pointer resolver to Java.
- `summary(handle) -> versioned bounded summary`: owner only, native guarded getter; serialize ordinary UTF-8 and fixed integer/boolean fields. Java independently rejects wrong version/length, trailing bytes, invalid UTF-8/NUL, unknown enum values, invalid counts/ports and inconsistent userRequired/user fields. Require hasRawFields=true for this version.
- `cancel(handle)`: the sole cross-thread operation, bounded short registry lock followed by atomic evaluator cancellation under lifetime protection.
- `close(handle)`: owner only, idempotent, retains every failed stage for retry; releases evaluator/pending borrow only. It must remain permitted after cancel/revoke/expiry. Never renew a deadline.
- `destroy(handle)`: owner only, accepts only CLOSED tombstone; remove slot only after successful native destroy. Java retains handle until success and makes public close retry failed close/destroy stages.

**Safe failed-open ownership:** reserve the bounded slot and token before borrow. All operations before borrow may fail with no retained native resource. After borrow, if evaluator open fails, retain `{pending_borrow, open_errno, failed_open}` in that slot and return its positive handle; run reports the retained open failure, close retries release, destroy requires cleanup success. Do not turn this positive handle into an exception that discards it. Preallocate the Java wrapper before calling native open, so publishing its handle does not require another Java allocation. A two-stage reserve/create-token then initialize API is an acceptable alternative if the main design prefers explicit open failure; it must preserve the same cleanup-capable handle. JNI must not allocate a return array after acquiring ownership in the one-step open path.

**Registry locking:** reserve/find under mutex, mark slot busy/pinned, copy evaluator pointer and release registry mutex before run. While busy, close/destroy/other owner operations return EBUSY without touching the pointer; cancel may briefly lock and atomically cancel the retained evaluator. Completion reacquires mutex and clears busy. Never hold the registry mutex across parser work or Java calls, and never release a pointer pin before the corresponding operation finishes. Separate owner validation for cancel from run/summary/cleanup; only cancel bypasses owner pthread, while PID/UID remain checked. A cancel received in a pending-open state must be retained and applied before any later evaluation.

### Recommended error representation

Main selected a fixed five-integer JNI diagnostic `[outcome, reason, objectIndex, line, errno]`, with the exact shape tied to this native Java binding (a future external serialized format would need a version):

- outcome=0 success: remaining fields zero.
- outcome=1 evaluator failure: reason in the existing named nonzero CTOS_CONFIG_* range; object/line are bounded, zero means no location. Carry the native error_number.
- outcome=2 operation/admission/open failure: reason/object/line zero and preserved nonzero errno (e.g. EBUSY, EALREADY, EPERM, ENOENT, EACCES, ETIMEDOUT). This avoids pretending a zero diagnostic from an early run failure is success.

The important contract is a separate outcome from evaluator reason, exact shape validation, and no config/path/command text in errors. Capture errno immediately after the failing C call, before cleanup, JNI allocation or unlocking. Preserve pending JNI exceptions; `ssh_session_jni.c:37-45` provides the better fail helper pattern. Do not copy `ssh_file_jni.c:33-36`, which clears a pending JNI exception. Standard JNI allocation/region failures should propagate the pending exception without performing ordinary JNI work, while all already-acquired native resources remain associated with the known handle.

Allocate run/summary response containers before changing evaluator state when practical. Allocation failure after a successful run must leave READY and permit a fresh summary call; never rerun the parser to recover a failed Java response allocation. A diagnostic for expected config rejection is data, not an exception with raw parser text. Java may wrap it in a typed domain failure at a higher layer, retaining the evaluator until close succeeds.

### Focused acceptance tests

1. Real registry + CSPV token + parser: open/run/summary/close/destroy; Include target result; full Options remains present internally; caller view summary and another borrow still work after evaluator close.
2. Caller lease revoked between Java admission/native borrow, during run and before summary; original deadline expiry; no success publication or resurrection. Cleanup still succeeds/retries after revoke/cancel/expiry.
3. Wrong pthread and synthetic PID/UID rejection for owner operations; cross-thread cancel while parser is busy completes without waiting for run to release registry lock; close/destroy while pinned return EBUSY without use-after-free. Cancel racing destroy cannot access freed evaluator.
4. Fault-inject eval_open failure after real borrow plus borrow_release failure. Assert positive cleanup handle remains, slot cannot be reused, retry releases it and view memory/FD only once. Inject evaluator close failure and destroy failure separately.
5. 64-slot admission, release/reuse with new token, stale/negative/zero tokens, signed token exhaustion (test-only seam if appropriate), repeated run, summary before run and after close, destroy before close; no pointer casting.
6. Java fake NativeBinding: UTF-8 multibyte/supplementary roundtrip, unpaired surrogate/NUL/control rejection, exact byte limits, all request fields preserved, buffers wiped, malformed response version/length/booleans/counts/diagnostic combinations rejected.
7. Java cleanup stages: caller lease is never closed, native close retry does not rerun, destroy retry does not repeat completed close, expected config failure still leaves a closeable object, reentry fails closed, cancellation does not enter owner-only APIs.
8. JNI strict build/link and symbol/package audit; focused JVM + real ARM registry/parser tests. Treat JVM fakes and QEMU as distinct from actual Android/JNI runtime; no device execution or installation is included in this scope.

### Related specs and references

- `.trellis/spec/backend/ssh-native.md:3-62`: current evaluator signatures, owner/lifetime, structured reasons, limits, full Options and no implied JNI/socket authority.
- `.trellis/spec/backend/host-bridge.md:16-20`: validate the boundary and run blocking work on its owning executor; no UI-thread parse work.
- `.trellis/spec/backend/error-handling.md:3-17`: explicit failure categories and resource cleanup.
- `.trellis/spec/backend/logging-guidelines.md:14-17`: no raw paths, secrets or unfiltered exception text in logs.
- `design/ssh-config-evaluator.md`: frozen native contract, pinned OpenSSH 10.6p1, independent objects, no effects and remaining connector finalization.
- External references: none newly fetched; findings rely on pinned local OpenSSH/native contracts and existing Java/JNI source. No updated platform claims are made.

## Caveats / Not Found

- No research-only jsonl exists in this task. Role isolation forbids loading implement.jsonl/check.jsonl; task prd/design/implement and relevant source/specs were read directly.
- This investigation does not modify code/specs, run builds/tests, access a device/vault/account, or perform git operations. Proposed API/wire names above are recommendations pending the main session's design freeze.
- Borrow-release failure is a real API ownership edge but was not dynamically injected during this research. Existing view registry allows only one current view; 64 evaluator slots do not imply 64 independently active public leases.
- Native system xalloc/OOM termination remains a documented upstream limitation; JNI OOM handling does not remove it.
- A copied Java Summary can outlive native revocation as historical data. Future catalog/UI publication and connector consumption need explicit fresh lifecycle checks; this seam alone is not final launch validation.

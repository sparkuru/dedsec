# Research: managed detached configuration orchestration

- Query: define the smallest safe Java/JNI path from an actual authenticated supplied VaultManifest to all-CONFIG declaration reports using completed detached native APIs.
- Scope: internal; read-only source/design investigation and this research file only. No builds, executions, network, device/account actions or git operations.
- Date: 2026-10-23
- Active task: `.trellis/tasks/10-08-ssh-terminal-client`.

## Findings

### Recommended increment

Add staged managed detached acquisition in the existing config registry, a detached branch in the existing owner-thread `SshConfigEvaluator` facade, and an owner-thread `AutoCloseable` all-CONFIG inspector. Inspect every actual CONFIG root in original manifest order using catalog mode only. Keep an explicit ordinary selected-root API for a caller-supplied real Request, but never derive Requests from aliases to simulate semantic coverage. Reuse unchanged CSEQ/CSES/CSCD codecs and the fixed readconf. Reports remain declarationsOnly=true/admissionComplete=false, with semantic/reference/identity/credential/trust obligations explicitly pending.

The main session's proposed managed limits are coherent: original <=60s absolute deadline; aggregate remaining opens/readBytes/lines, CONFIG-copy amplification <=64MiB across roots, retained catalog wire <=1MiB and <=4096 alias+deferred rows. These are proposed contract values to freeze, not currently implemented limits.

### Files and concrete seams

| File | Relevant ownership/API |
| --- | --- |
| `android/app/src/main/cpp/ssh_config_eval.h:61-85` | `ctos_ssh_config_file {type,path,body}`, zero-based real root, CONFIG-only body, detached constructors and exceptional CLOSING cleanup owner. |
| `android/app/src/main/cpp/ssh_config_eval.c:533-614` | Detached shape checks, independently locked/DONTDUMP owned source, failure close/destroy and retained owner. |
| `android/app/src/main/cpp/ssh_config_eval.c:62-87`, `:108-117` | Eval owner/cancel/deadline, original line count, file IO, owned mapping. |
| `android/app/src/main/cpp/ssh_config_eval.c:739-779` | Retryable reader/input cleanup; destroy only CLOSED tombstone. |
| `android/app/src/main/cpp/ssh_file_io.c:75-108` | Opens/read-byte charges and reader-copy budgets; currently constant limits per evaluation. |
| `android/app/src/main/cpp/ssh_config_handle.c:130-194` | Shared 64-slot reservation, busy pin, live pending-borrow failure path, status/errno reporting. |
| `android/app/src/main/cpp/ssh_config_handle.c:280-321` | Atomic cross-thread cancel under registry lock; close outside registry lock; destroy synchronized against cancel. |
| `android/app/src/main/cpp/ssh_config_jni.c:16-46`, `:83-109` | Current live opens, native-owned request scratch, preallocated run output and final catalog liveness check. |
| `android/app/src/main/java/im/majo/ctos/SshConfigEvaluator.java:25-64` | NativeBinding defaults can extend fail closed without breaking old test bindings; production native entry list. |
| `android/app/src/main/java/im/majo/ctos/SshConfigEvaluator.java:108-235` | Reusable strict diagnostics and immutable Summary/Catalog codecs. Constructors are currently private; sibling inspector needs a deliberate package-private decoder/accessor seam, not duplicate codecs. |
| `android/app/src/main/java/im/majo/ctos/SshConfigEvaluator.java:239-396` | Owner/reentry/cancel/two-stage close; currently hard-requires ViewBinding and must get a separate detached lifetime path. |
| `android/app/src/main/java/im/majo/ctos/SshManifestInspector.java:122-188` | Cleanup slots before copies, ThreadLocal reentry guard, original deadline/final cleanup fence. Its synchronous native call has no persistent owner, unlike detached config. |
| `android/app/src/main/java/im/majo/ctos/VaultManifest.java:22-27`, `:52-77`, `:173-219` | Original file order/type/path/body, authenticated structural format; arrays mutable until close; metadata/references are not config semantic proof. |

New Java responsibilities can be one `SshManifestConfigInspector.java` with nested owned input/report/cancellation, plus the existing evaluator facade. Native responsibilities are registry/JNI plus a controlled staged-storage and budget extension in eval/IO. Main owns Gradle/runners/docs. This is multiple coordinated implementation files; it should not be mislabeled a low-risk mechanical three-file task.

### Compatible CSDI v1 proposal

Use standard big-endian integer lengths, strict UTF-8 paths, original record order, native type tags1..5. Do not use Java FileType.ordinal (0..4). Original file ID/path/identity metadata can be kept in Java immutable report descriptors; native parsing only needs type/path/body. Do not serialize entries/secrets, references, UUID/epoch/generation/connection/request binding, or synthetic CSPV fields.

```text
u32 magic = 0x43534449       // CSDI
u32 version = 1
u32 count                  // 1..128 for a native acquisition
u32 maxOpens               // remaining job allowance, no more than1024
u32 maxReadBytes           // remaining job allowance, no more than64MiB
u32 maxLines               // remaining job allowance, no more than16384
repeat count times:
    u32 type               // native CONFIG1, PRIVATE2, PUBLIC3, CERT4, KNOWN5
    u32 pathLength         // 1..512 UTF-8 bytes
    byte[pathLength] path
    u32 bodyLength         // CONFIG0..1MiB, all other types exactly0
    byte[bodyLength] body
```

Maximum wire is `24 + 128*(12+512) + 8MiB = 8,455,704` bytes. Require exact frame consumption, checked subtraction/size arithmetic, independent count/type/path/duplicate/file-directory-prefix validation, CONFIG total<=8MiB, selected root in range and actually CONFIG. Non-CONFIG slices must decode as `{NULL,0}`, not a nonnull cursor with zero length: native detached shape checks both pointer and length (`ssh_config_eval.c:543`). CONFIG empty files are valid. Root and original deadline remain separate primitive arguments, request is a separate optional CSEQ byte array required only for ordinary mode.

Path policy must be explicitly frozen. Existing VaultManifest/native path rules reject ASCII controls but do not reject every C1 control; existing evaluator Request helper rejects ISO controls. Reusing its string helper blindly would introduce a new path restriction. Require valid standard UTF-8/unpaired-surrogate rejection and apply the established managed-path contract consistently in Java/native; if C1 rejection is chosen, document it as an explicit tightening. Neither normalize arbitrary paths nor consult a host filesystem.

Do not extend old CSEQ/CSES/CSCD versions or reinterpret old view tokens. Extend `NativeBinding` with default fail-closed methods for detached reserve/load/usage; existing bindings and APIs retain original behavior. No fabricated constant-active ViewBinding is needed: a distinct detached mode uses original clock/cancellation and native liveness.

### Critical JNI scratch ownership decision

A single `openDetached(byte[] input)` that reserves/publishes its token only after copying is unsafe under the combined fault: GetByteArrayRegion throws, then mapping cleanup fails. Java cannot receive a positive token while a pending JNI exception is preserved. Returning zero/throwing would orphan the cleanup owner. Reserving a slot internally before copy is insufficient unless Java has already received it.

Use two managed stages (names illustrative):

```java
long reserveDetached(int root, long deadline, boolean catalogMode, byte[] request);
void loadDetached(long handle, byte[] input);
// Existing run/summary/catalog/cancel/close/destroy, plus bounded usage(handle).
```

Reserve does fixed-size registry metadata only, and validates/copies bounded request input before reserving. It returns a positive token to an already allocated retained Java owner. It must not acquire a mapping or any fallible cleanup resource after the last possible throwing JNI operation. The Java facade must retain/attach that evaluator before invoking load; do not combine stages into a factory which throws and discards the owner on failed load.

Load pins the slot, releases the registry mutex, establishes a native cleanup owner before protection/copy, then copies directly with GetByteArrayRegion into an owned DONTDUMP+mlock mapping. Prefer parsing CSDI in-place and adopting that one mapping as evaluator storage, with bounded file-record table in the same mapping. A small controlled staged core constructor/finish API is needed; current `open_detached` always allocates and copies a second owned region, so it cannot simply adopt wire storage.

Keep staged input incapable of run/getters until full frame validation and final original deadline/cancel check finish. A slot may hold an incomplete evaluator in CLOSING/loading state (or explicit owner field), but must record that stage and original load failure. Failed load remains closable through the token even when JNI preserves an original IOException/OOM/Error. No untrusted native address crosses JNI. Check pending exception immediately and stop ordinary JNI calls; native cleanup/state recording may still run.

Do not allocate a full8MiB unlocked native malloc scratch. A separate fully locked wire scratch followed by current detached constructor doubles locked data and predictably exceeds the existing8MiB lock quota for ordinary >4MiB bodies. `GetByteArrayElements` may silently copy and does not guarantee protected storage; GetPrimitiveArrayCritical across allocation/locking/parser work is not a safe substitute. Small bounded header/record scratch can be stack-owned and wiped, but the payload requires explicit ownership/protection. Resource-limit failure must fail closed, never disable locking.

Native staging is the substantive extra scope needed for a safe managed bridge. If it cannot be made bounded now, stop at a reviewed staged-owner contract rather than accepting unpublished scratch or an unlocked fallback.

### Registry failure and cleanup behavior

Current `run` recognizes failed open only when `pending_borrow != NULL` (`ssh_config_handle.c:179-187`). Detached failed construction may have `eval != NULL` in CLOSING and no pending borrow. Add an explicit acquired/loaded/open_failed state or use `open_errno != 0` **before** dispatching eval_run. Return existing `[2,0,0,0,original_errno]` on its first run, ECANCELED if canceled first and EALREADY on repeated run, preserving old precedence. Never run a CLOSING owner merely because eval is nonnull. Getters reject open_failed/loading; close cleans retained eval/scratch and destroy only consumes confirmed closed state.

Keep reserve/cancel/destroy registry locks short. Mapping/protection/copy/validation/parser/cleanup run while busy-pinned outside the lock. Mark slot.cancelled atomically under the existing short registry lock; cancel tolerates eval not yet attached, and load propagates cancellation when attaching an evaluator and before publishing loaded. Destroy remains forbidden while busy. Distinct live/detached slots consume the same64 capacity and monotonic token identity; no second singleton and no token reuse.

The high-level inspector must retain any close/destroy-failed low-level evaluator in a private pending field and expose owner-thread retry `close()`. Reject a fresh inspect while cleanup is pending. A one-shot local try-with-resources that throws and loses its resource reference is insufficient. Catching Error must leave cleanup ownership reachable while preserving the original Error; cleanup failure must not silently replace it. The same owner must survive a failed load before run.

### Cancellation including acquisition races

Use a one-way AtomicBoolean cancellation plus one current cancellation target attached under a short metadata handshake. Set the bit before observing the target. Invoke native cancel outside the metadata monitor. Detach must use exact target identity, so cleanup of old root N cannot detach/cancel root N+1 accidentally. No session/role/core lookup belongs here.

Attach the preallocated evaluator before reserve where possible; reserve may return after cancel observed handle0. After assigning every positive token to the retained evaluator, recheck the cancellation bit and forward native cancel before load/run. Also recheck after load, run, usage, catalog decoding and cleanup, and immediately before report return. No future root may start after cancel. The slot must likewise propagate its cancel bit when load attaches an eval; otherwise cancel during copying could be lost.

Cancellation during close/destroy may race a stale native token snapshot. Native registry already protects against UAF. Java must distinguish a target that has finished from an active target cancellation failure; a stale cancel cannot revive a result or clear a newer target. `cancel()` should remain bounded and never perform wipe/unmap of worker-owned buffers. It may mark a fixed cancellation error for worker rejection instead of exposing input-bearing exceptions. Native acquisition/protection/copy is not forcibly preemptible while no parser eval exists; document that cancel returns promptly and blocks subsequent parsing/publication, not that it interrupts libc immediately.

### All-root Java ownership, budget and coverage

1. Establish ThreadLocal reentry guard before any job allocation; allocate cleanup slots first. Copy type/path and report metadata for every original file, but independently copy **only CONFIG bodies**. Never call manifest.encode(): it contains all credentials/private/TOTP material. Do not read/copy non-CONFIG bodies to calculate config input. Retain immutable original descriptors; caller supplies an exclusive authenticated loan and inspector never mutates/closes it.
2. Prepare one owned CSDI input from these copies with exact-size allocation. Updating remaining limit header for a later root is safe only while no JNI operation borrows that array; input and native copies are synchronous. Wipe every CONFIG copy, encoded path/wire/output/usage/diagnostic array on success/failure/cancel/Error. Do not retain raw wire or bodies in Report.
3. Before any native root, count CONFIG roots and sum CONFIG bytes, validate overflow-safe `rootCount*configBytes <=64MiB`. Metadata amplification is separately bounded by128*128*512 bytes plus record/table overhead. No-CONFIG/empty manifest yields explicit zero-root report without native reserve/load/catalog and without fabricated CSCD scannedFiles=0; check original deadline/cancel both before and after cleanup even for empty input.
4. Iterate every original CONFIG ordinal, including isolated/orphan and previously-included files. Native root is zero-based original index; returned object/source is one-based original index. Do not filter the native table down to CONFIGs or invent a super-root. Keep per-root Catalog, preserving existing first-alias provenance within that root rather than merging aliases across roots and losing source/coverage.
5. A complete all-root declaration scan requires all roots processed. Abort the entire report on failed parser/JNI/lifecycle/limit/codec/cleanup; do not publish a successful prefix. Mark dynamic Include sites and omitted selectors/Match as historical incompleteness. Running every root does not establish Include-reference binding or all-context semantics.
6. Add controlled native NEW-only remaining work limits and a numeric usage getter. Counters currently private are `io.opens/read_bytes` and `eval.total_lines`; no accessor exists. Defaults for old opens stay1024/64MiB/16384. Tightened remaining limits enforce failure **before** excess operation/copy/line work, not merely accounting after full parse. Reusing the native stats avoids a Java SSH lexer or estimated Include cost.
7. Suggested usage wire is exact `int[3] {opens, readBytes, lines}`, success values each bounded by allowance; remaining allowances subtract without overflow before next root. Getter is owner/mode/stage/lifetime guarded and copied before close. If FAILED usage is exposed for diagnostics, explicitly define rejected-attempt counts (opens/lines may be limit+1); the high-level job aborts rather than continuing after failure. No old run int[5] format changes.
8. Charge actual retained CSCD bytes before decode/retention, <=1MiB total, and aliases+deferred rows <=4096 across roots. Limit root result count to actual CONFIG count<=128. Validate CSCD source object against supplied file count **and CONFIG type**, and scannedFiles<=CONFIG count; generic codec bounds1..128 alone are insufficient. Ensure per-root metadata/ordinal matches the selected real root.
9. Immutable report can contain original root ordinal/id/path/identity, Catalog and counters/processedRootCount. Flags must distinguish declarationsOnly from admissionComplete=false and state unresolved config/reference/identity/credential/trust obligations; do not set semanticsComplete or infer whole-manifest safety from empty/no-CONFIG results. Material Report remains unchanged and partial-only.

### Meaningful tests

Native real-parser tests should exercise the **shared registry entry points**, not only C open_detached: valid CSDI plus real source B while live A persists; original ordinals with leading keys/orphan roots; actual Request ordinary mode and pure catalog mode; truncated/overflow/trailing/bad UTF8/path/type/body frames; mode separation/default old opens; capacity/stale token; loading/busy/owner/PID/UID; failure before and after mapping/copy, CLOSING owner and original errno; JNI-copy failure simulation plus failed cleanup; cancellation before/while eval attach; retry close/destroy; budget exact/excess across roots; effects0 and tracked owned mapping/FD wipe. Preserve existing evaluator/catalog/handle/detached tests and the8MiB quota caveat.

JVM tests independently verify candidate B instead of current A, CONFIG-only deep copies and caller immutability, all roots/original ordinals, no native calls for zero CONFIG, immutable partial-only reports, malicious CSCD source/type/count, exact frame length/surrogates, cumulative copy/output/work failure with no partial success, original deadline through acquisition/final cleanup, cancellation before reserve return and between attach/load/run/getter/next root, prompt paused native cancel, no target detachment crossover, IOException/LinkageError/Error cleanup, retained owner retry and reentry guard release. Assert old NativeBinding without defaults fails closed. Existing live tests stay unchanged and rerun.

An independent fixture exact JNI method should compile using real public input only; main owns actual strict builds/QEMU/full JVM/lint/fixture normal assembly and export audit. JNI execution on Android remains unrun without new authorization. Test-link wrappers remain excluded from production. Normal config JNI count deliberately grows only for frozen reserve/load/usage entry points; existing eight signatures and material/session entries must stay intact.

### References, specs and existing evidence

- Related specs: `.trellis/spec/backend/ssh-detached-config.md`, `ssh-native.md` managed/catalog sections, `app-secrets.md` actual loan/deadline/cancellation boundaries, `quality-guidelines.md`, `error-handling.md`.
- Design: `design/ssh-detached-config.md`, `ssh-config-managed.md`, `ssh-config-catalog.md`, `ssh-manifest-inspection.md`, `ssh-validation-lifecycle.md`.
- Local pinned OpenSSH10.6p1/NDK27.0.12077973 and API28 baseline only; no external sources accessed.
- Main-provided previous native evidence directory `/tmp/ctos-ssh-detached-config-20261023.mUF8aSF0` exists and contains final logs/manifests. This research did not execute or revalidate those artifacts. Source final7/protected272 is main-session provenance, not a new verification claim.

## Caveats / Not Found

- Staged managed JNI, CSDI wire, aggregate budgets/usage and Java all-root inspector are proposals; none exists in inspected source yet. Main must freeze signatures/ownership before implementers proceed.
- Protected single-copy adoption is additional native work beyond thin wrapping. Full-wire unlocked scratch or a pending-exception cleanup-owner loss would violate the established ownership/protection model.
- Complete Include edge/reference policy, all-branch semantics, private/public/certificate mathematical links, password/passphrase target policy, trust/restore review and actual connection plan remain pending. Keep production validator/reviewer unregistered and restore gates closed.
- Wrote only this file; no implementation, tests, scripts, design/spec or sibling research was modified.

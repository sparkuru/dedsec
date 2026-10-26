# Managed detached SSH configuration inspection

2026-10-23: the later [CONFIG audit spec](ssh-config-audit.md) adds explicit AUDIT/CSCA, finite all-branch policy and actual static Include direct-reference checks. Original effective/catalog/input contracts below remain; audit is partial-only and does not open semantic provider/restore. [Evidence](../../../design/verification.md#20261023-config-audit).

## 1. Scope / Trigger

Use [the managed contract](../../../design/ssh-detached-config-managed.md) for actual supplied
VaultManifest CONFIG inspection. This extends [native detached input](ssh-detached-config.md)
through Java/JNI, while retaining declaration-only results. It does not register a production
validator/reviewer, certify Include references or identity/credential/trust semantics, open
SSH restore, or generate final connection plans.

## 2. Signatures

```java
SshConfigEvaluator.prepareDetached(DetachedGuard guard, boolean catalogMode);
evaluator.loadDetached(byte[] source, int root, long deadline, Request request);
evaluator.usage(); // immutable opens/readBytes/lines
evaluator.catalog(int maxBytes, int maxRows); // remaining retention budget
inspector.inspect(VaultManifest manifest, long deadline, Cancellation cancellation);
inspector.close(); // retry while hasPendingCleanup()
```

The caller retains an owner-thread, one-shot SshManifestConfigInspector before inspect.
Native `reserveDetached(boolean):long`, `loadDetached(long,byte[],int,long,byte[]):void` and
`usage(long):int[3]` add three JNI methods; existing eight config methods remain. Native C
reserve/load use the existing 64-slot registry. The synchronous trusted C copy function is
internal to JNI, not an arbitrary Java/configuration callback.

## 3. Contracts

Reserve owns only a slot; preallocated Java facade saves the positive token without a
post-reserve failure fence that could lose it. Load pins before attaching eval, releases
registry lock before allocation/copy/parse, records mapping ownership before DONTDUMP/mlock
and GetArrayRegion. One native CSDI copy is adopted in place after a fixed128-file-table
prefix. No whole-source unowned malloc scratch, fake vault tuple/view or unlocked fallback.

CSDI v1 big-endian header is six u32: magic0x43534449, version1, fileCount, remaining opens,
readBytes, lines. Each original-order record is type/pathLength/path/bodyLength/CONFIG-only
body. Count1..128, type1..5, strict UTF8 relative managed paths1..512bytes without ISO
controls/duplicates/prefix collision, body<=1MiB each/8MiB total, nonCONFIG body0. Wire cap
8455704bytes. Root zero-based and CONFIG. Catalog request is null; effective mode needs
actual CSEQ. Absent request with nonzero length is invalid even in the C seam.

One exclusive synchronous manifest loan; caller retains its ownership. Copy only paths,
types and CONFIG bodies into cleanup slots established before copying. No entries/passwords/
passphrases/TOTP or nonCONFIG bodies in CSDI. Preflight CONFIGcount*totalCONFIGbytes<=64MiB.
Every CONFIG, including orphan, is a root in original order; zero CONFIG yields explicit
zero-root partial report without JNI. No synthetic alias/user/Request/super-root.

One original future BOOTTIME deadline<=60s and one Cancellation per job, including retained
cleanup. Before each root pass remaining1024opens/64MiBreads/16384lines; native checks before
the extra open/read/line. Strict READY/owner/lifetime usage and JNI postwrite fence. Aggregate
retained CSCD<=1MiB, aliases+deferred<=4096; check header quotas before list/string decoding.
Catalog source object must index an actual CONFIG and scannedFiles<=CONFIGcount. Immutable
roots retain original file metadata/ordinal; declarationsOnly=true/admissionComplete=false.

Cancel sets its bit before reading active facade, attach/postcheck closes reserve race.
No monitor across native. IOException forwarding failure poisons only the exact still-active
owner; RuntimeException/Error propagate. Cleanup remains on worker, never races array wipe.
Close/destroy failure retains exact facade; retry close on inspector. Final publication occurs
only after native cleanup, Java copy wipe and original lifetime check, including zero CONFIG.

## 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Run before load, duplicate load, concurrent load/run/close/destroy | Reject with no result |
| JNI copy exception/partial-copy abort, malformed CSDI/CSEQ, protection failure | Retain issued owner; preserve pending JNI exception; close/destroy retry |
| Cancel before attachment or during copy/parser/getter/cleanup | Inherit cancellation, deny report; worker owns cleanup |
| Deadline, remaining IO/line or aggregate retention exhausted | Fail whole job without truncation or deadline renewal |
| Wrong thread/PID/UID, reentry, invalid catalog source | Reject; no semantic/admission capability |
| Cleanup throws IOException/RuntimeException/Error | Keep owner reachable and wipe Java copies; no report |
| No CONFIG | Zero roots, no native reservation, same final cancellation/deadline fence |

## 5. Good / Base / Bad Cases

Good: inspect candidate B while live A remains untouched, scanning two roots with the second
receiving the first's actual remaining budget. Base: an orphan CONFIG at original ordinal3
is explicitly reported. Bad: multiply1024 opens by128 roots or decode an extra root after
aggregate retention is exhausted. Bare `Include child` still resolves `.ssh/child` under
the existing user Include anchor; a fixture must supply that exact managed path.

## 6. Tests Required

Run actual parser ARM staged and old eval/catalog/handle/direct-detached/host IO matrices.
Assert single protected copy adoption, no view capabilities/host effects, reserve/load busy,
cancel attachment, mode/wire/size limits, sequential remaining budgets, actual usage and
retryable partial-copy/close/destroy failures. JVM tests cover actual loan/copy exclusivity,
all/orphan/noCONFIG roots, source ordinals, preflight/retention quotas before decoding,
path/UTF8/C1 limits, reserve cancellation race, paused load/run, reentry/Throwable cleanup,
stale cancellation forwarding and final publication fences. Compile isolated JNI fixture;
Android execution is a separate currently unrun gate. Full JVM/lint/normal and fixture APK
builds, three APK signature/isolation/export/production archive audits and source hashes.
See [actual results](../../../design/verification.md#20261023-detached-managed).

## 7. Wrong vs Correct

Wrong: copy JNI source before a reachable token owns its cleanup, or auto-close once and
drop a facade when close fails. Correct: caller retains inspector, reserve returns facade,
load attaches protected storage, failure keeps the exact owner for explicit retry close.
Wrong: require every supplied config path to start `ssh/`. Correct: reuse managed relative
path policy plus strict UTF8/ISO checks, preserving existing Include anchoring semantics.

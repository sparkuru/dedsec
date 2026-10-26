# SSH Native Gates

2026-10-25: [foreground password connection](ssh-password-connect.md) now connects the
production UI, supported whole-manifest admission, actual TCP and managed host-key approval.
The current session JNI surface has 20 exports: the previous 19 plus caller-owned TCP
descriptor preparation for API28. Known-host matching/writing binds the actual port and
lowercases only the internal lookup identifier, including hashed entries. Only fresh selected
SUCCESS may promote that same client to shell-only lifetime; original auth/file/secret budgets
remain unchanged. Historical factory-only/no-provider statements below are superseded only
for this complete narrow password subset. Android runtime evidence remains a separate gate.

2026-10-24：[证书事实规范](ssh-certificate-facts.md)新增全CERT/CSCF168公开事实和嵌入签名政策；CA/time/principal/revocation仍未验证，不改变生产证书拒绝/完整provider/恢复门。

2026-10-23：[全部private与凭据归属](ssh-private-material.md)新增CSKP实际self-proof及v1元数据唯一owner扫描；不改变v1/生产provider/恢复，完整graph/password account/trust仍待完成。

2026-10-23：新增[显式key pair规范](ssh-key-association.md)，精确supplied IDs/口令与plain subject的固定本地签名证明；旧CSMI/auth不变，不作为完整validator/信任/恢复准入。

2026-10-23: [explicit resource auditing](ssh-config-resources.md) adds fourth-mode CSCR with typed literal-base/direct-parent references and explicit context/cwd/key-resolution deferrals. Existing modes below stay unchanged; no material/math/trust/admission proof. [Evidence](../../../design/verification.md#20261023-config-resources).

2026-10-23: the later [CONFIG audit spec](ssh-config-audit.md) adds explicit AUDIT/CSCA, finite all-branch policy and actual static Include direct-reference checks. Original effective/catalog/input contracts below remain; audit is partial-only and does not open semantic provider/restore. [Evidence](../../../design/verification.md#20261023-config-audit).

2026-10-23 managed detached configuration inspection: [focused spec](ssh-detached-config-managed.md) extends Java/JNI actual-manifest input with reserve/load owner, single protected CSDI copy, remaining native IO/line budgets and all CONFIG declaration catalogs. Original cancellation/deadline and retryable cleanup govern final publication; report remains partial-only. No full semantic provider/restore/connector. [Evidence](../../../design/verification.md#20261023-detached-managed).

2026-10-23 detached native configuration input: use the focused [API/ownership spec](ssh-detached-config.md) and [contract](../../../design/ssh-detached-config.md). Own CONFIG copies without synthetic live view/tuple, retain original root ordinal/deadline/cancel, preserve live borrow and errno precedence. Failed construction can return a CLOSING cleanup owner. Current input is C-only; Include-edge/full-manifest semantics and production provider/restore remain pending. [Actual evidence](../../../design/verification.md#20261023-detached-config).

2026-10-22 typed-secret lifecycle: [focused contract](../../../design/ssh-secret-read-lifecycle.md)
preserves original role deadline/view, selected host/KEX/receipt guard through detached admission
and release. Actual managed copy is bounded and fenced stateLock→request→publication→session→
operation→role→view; `SshRoleGuard.withRole` separates inner role fencing from outer publish.
Public snapshot uses the same inner role order, while native attach retains short operation
binding and immediate cancellation after bind. Do not execute native calls or reenter core
inside a secret receiver. Already completed managed copies cannot be retracted; later JNI/
native checks and scratch wipe remain required. Host JVM evidence is not Android execution.

2026-10-22 public-file lifecycle: see [focused contract](../../../design/ssh-public-read-lifecycle.md)
and the App Secrets detached SSH public-read scenario. SSH_FILES now separates synchronous
public loan claims from provisional native create/final attach. Existing-role known_hosts
snapshotFor uses its exact role/view and original role.deadline. This adds no host/KEX/secret
authority and no permanent old-epoch lease revoker: exact own-NEW-CAS continuation below
retains its existing operation/session ownership and original deadlines. Other secret reads
and trust/mutation implementation remain separate; production semantic/restore gates stay closed.

## Supplied-manifest material inspection (2026-10-19)

### 1. Scope / Trigger

Use [the inspection contract](../../../design/ssh-manifest-inspection.md) to inspect actual
source/target/candidate file bytes. Never reopen current AppSecrets/session/public lease or
fabricate a vault capability tuple. Report is always contentOnly=true/admissionComplete=false;
CONFIG, reference/identity/credential semantics, certificate authority and trust admission
are pending. Do not register this as SshContentValidator or open restore admission.

### 2. Signatures

Java `new SshManifestInspector().inspect(manifest, deadline, cancellation)` returns immutable
Report/Entry metadata; injected `(Clock, NativeBinding)` constructor is for host tests.
`Cancellation.cancel()` is one-way atomic. JNI adds only
`SshManifestInspector.Native.inspect(int type, byte[] body, byte[] passphrase, long deadline):byte[]`.
C `ctos_ssh_material_inspect(type, body, length, passphrase, passphrase_length, deadline, output60)`
returns0 for a material outcome, -1/errno for API/system/deadline failure and clears output.
No persistent registry, view, role, session, descriptor or credential-provider callback.

### 3. Contracts

Exclusive caller manifest loan; independently owned arrays for every body and relevant exact
`SSH_KEY_PASSPHRASE.identity == "key:" + file.identity` entry before the first native call.
Never copy unrelated TOTP/password secrets or close/mutate the caller manifest. Cleanup slots
exist before sensitive copies; wipe every success/IO/Linkage/Error/cancel path. Multiple
matching passphrase entries yield AMBIGUOUS_PASSPHRASE, CONFIG yields CONFIG_DEFERRED.
No filename-based public/private association. Global ThreadLocal reentry guard; establish
before job state, finally remove. One original BOOTTIME deadline, <=60s at admission,
checked across copy/native/cleanup/publication. Cancel blocks later work/publication but
does not preempt a synchronous bounded crypto/KDF call.

Extracted `ssh_key_policy.[ch]` is shared with original authentication, preserving software
Ed25519/EC/RSA and pinned RSA floor1024, OpenSSH aes256-ctr/bcrypt salt/rounds1..64 and bounded
traditional RSA/EC AES128/256 PEM. Known encrypted headers must pass this policy BEFORE any
private parser call; absent passphrase returns NEEDS without parsing/decrypting payload.
Reuse sshkey parsing, one complete outer private envelope, unencrypted PEM single top-level
DER SEQUENCE, one physical public/cert record plus standard comments, and public blob SHA256.
Traditional encrypted PEM decoded-DER full consumption and private/public mathematical
consistency are not certified. No signing, cert validity/principal or remote trust approval.

Known_hosts uses hostkeys_foreach_file with NULL host/IP, PARSE_KEY only and fixed source
label. Reject every invalid row; supplement skipped hash checks with exact |1| salt/digest
Base64 decode20 bytes each, no fake host/HMAC. Ordinary host patterns remain opaque parsed
fields. Check stream read/close failure. Fixed safe diagnostics are counted separately from
file/network/env/exec effects; do not mutate global logger or enable DEBUG_PK/DEBUG_PKOK.

### 4. Validation & Error Matrix

| Condition | Required result |
| --- | --- |
| Body/passphrase/rows | <=1MiB / <=8192 no-NUL bytes / <=16384 rows of <=65536 bytes; limits fail, no truncation |
| Type | Native tags2 PRIVATE,3 PUBLIC,4 CERT,5 KNOWN_HOSTS; other API tags fail |
| Material status |0 PARSED,1 MALFORMED,2 UNSUPPORTED,3 NEEDS_PASSPHRASE,4 BAD_PASSPHRASE; last is upstream class and may mean corruption |
| Text framing | NUL rejects; second physical public key or private armor/nonempty suffix rejects; same-line public suffix is a comment |
| OpenSSH private CRLF | Pinned exact LF marker rejects; do not normalize because outer framing recognizes CRLF |
| SK public input | Current pinned config disables SK, upstream cannot recognize type: MALFORMED, no hardware/provider invocation |
| Cancel/deadline/invalid JNI/crypto IO | Fixed IOException; no partial Report/output capability |
| Failed material parse | Metadata outcome only, zero kind/records/digest, bounded source line; no partial parsed success |

CSMIv1 is exactly60 big-endian bytes: magic0x43534d49/version1/status/keyKind/flags/records/line,
then32 digest bytes. keyKind0 none/1Ed25519/2EC256/3EC384/4EC521/5RSA. Flags1 encrypted only
PRIVATE,2 certificate only PARSED CERT,4 contentOnly mandatory; no other bits. PARSED key
kind1..5/records1/nonzero digest/line0; PARSED KNOWN kind/digest0,records0..16384,line0. Other
statuses kind/records/digest0,line0..16384; NEEDS/BAD only PRIVATE+encrypted. Java checks all
typed combinations, length/magic/version/enums/counts and trailing. JNI preallocates result,
copies into owned scratch, wipes it and rechecks deadline after array writes.

### 5. Good / Base / Bad Cases

Good: a distinct candidate's actual private bytes and exact unique passphrase are inspected.
Base: CONFIG or an encrypted key without a phrase remains explicitly pending. Bad: equating
PARSED certificate or known_hosts with trusted identity or restore approval.

### 6. Tests Required

Real parser matrices cover supported algorithms/formats/AES/KDF bounds, exact8192 phrase,
public/cert type mismatch and record framing, DER suffix, all known_hosts records/markers/
hashes, body/row/NUL bounds, API/stream/deadline failures, cleanup/FD and forbidden effects.
Count fixed safe logs separately. Prove rejected/missing encrypted input invokes no private
parser/KDF. JVM independently tests supplied candidates/deep copies/all files/ambiguity,
strict wire, cleanup/Error/reentry/cancel/final publication. Compile exact opt-in platform
method; Android execution is separate. Rerun full auth/PTY ARM after shared helper extraction.
Keep original config/evaluator/catalog regressions and archive83/configJNI8/sessionJNI19;
only one material JNI added. Production must exclude wrap observers/test seed/fixture peer.

### 7. Wrong vs Correct

Wrong: inspect a current live lease in the candidate validator, trial-decrypt every secret,
or publish native private buffers as metadata. Correct: actual supplied exclusive loan,
exact ambiguity handling, scoped copies/final fences and explicit partial-only outcomes.
In new C adapters, include platform stdlib before pinned includes then undef allocator
shims; do not suppress undeclared allocator errors. Expiry fixtures must reach the intended
callback before deliberately waiting for expiry: synthetic preparation budget may differ
from production lifetime, but every original expiry/evidence assertion must remain.
[Actual evidence and limits](../../../design/verification.md#20261019-manifest-inspection).

## Context-free declaration catalog (2026-10-19)

### 1. Scope / Trigger

Use [the catalog contract](../../../design/ssh-config-catalog.md) to discover literal Host
declarations from a public lease before a real target/user Request exists. Every result is
declarationsOnly. Catalog is lexical discovery; semantic validation, effective profile,
private/trust validation, final launch plan and AppSecrets restore admission remain separate.

### 2. Signatures

`ctos_ssh_config_eval_open_catalog(borrow, &eval)` and guarded
`ctos_ssh_config_eval_catalog(eval, &catalog)` share the evaluator lifecycle. The opaque
handle layer adds open_catalog/catalog/catalog_live. Java uses
`SshConfigEvaluator.openCatalog(lease)` then run/catalog; Native adds
`openCatalog(long view):long` and `catalog(long handle):byte[]` to the original six methods.
NativeBinding defaults fail closed. No Request, synthetic hostname, passwd or user is supplied.

### 3. Contracts

Hook the same namespaced readconf after keyword/argv parsing and before Host matching.
Inspect the full Host token vector; skip leading !, * or ?, invalid hostname/UTF8/control
selectors, marking omittedSelectors. Square brackets are literal in pinned Host matching.
Deduplicate exact alias bytes, retaining first object/line and depth-first Include order.
Traverse root-reachable static managed Include regardless of branch activation, CONFIG only;
never scan orphan view entries. Any % or $ Include is deferred before expansion, with unique
object/line and includesIncomplete. Match sets a flag without predicate evaluation; other
known directives are lexically consumed. IgnoreUnknown does not hide unknown keywords.

Use the original parser trylock, owner/PID/UID, deadline/cancel, borrow and reader retry rules.
Catalog owns and wipes its heap metadata. Summary/Options reject catalog mode; catalog getter
rejects ordinary mode. Recheck lifetime after final reader cleanup and JNI array publication.
Java immutable copied metadata is historical; fresh getters still check native lifetime.

### 4. Validation & Error Matrix

| Condition | Required result |
| --- | --- |
| Alias or deferred capacity | At most256 each; a257th distinct item is LIMIT, no partial catalog |
| Literal alias length |1..255 standard UTF8 bytes; longer positive literal is LIMIT, no truncation |
| Static Include | Existing path/type/missing/cycle/depth/IO diagnostics; no environment or host FS lookup |
| Dynamic Include | Skip token; unique located deferredSite, includesIncomplete iff deferredCount>0 |
| Bad recognized Port/Match arguments | May pass lexical catalog; effective evaluation must reject |
| Unknown keyword, including after IgnoreUnknown | Reject |
| Wrong-mode getter, cancel/revoke/expiry | Reject, publish no catalog pointer or wire |
| Failed post-borrow open/cleanup | Retain positive cleanup handle and original retry point |

CSCDv1 is big-endian magic0x43534344,version1,flags,scannedFiles,aliasCount,deferredCount,
then alias object/line/byteLength/bytes and deferred object/line. Only flags1/2/4/8 are valid;
1 is mandatory,8 iff deferredCount>0. Maximum70424 bytes. scannedFiles1..128, object1..128,
line1..16384. Java rejects duplicate aliases/sites, bad UTF8/C1/NUL/controls, length/count/flags
and trailing bytes. Existing view128/1MiB/8MiB, IO1024/64MiB, depth8, line8192/total16384 apply.

### 5. Good / Base / Bad Cases

Good: static Include under an inactive branch still contributes a located declaration.
Base: wildcard-only config yields empty aliases plus omittedSelectors. Bad: turning a
successful catalog with an invalid Port into a usable connection profile or restore approval.

### 6. Tests Required

Real parser tests cover full Host argv/negation/comments/quotes, []/UTF8, first-source order,
static branches/glob/orphan/path/type/cycle, deferred-site dedup, semantic non-certification,
all budgets and exact70424-byte frame. Include EBUSY/wrong-thread/cancel/revoke/expiry,
deterministic late reader-close revoke/cancel, retryable cleanup and FD/effect0 checks.
Rerun ordinary evaluator and registry parser tests. JVM separately tests codec/immutable
lists/mode separation/default binding and lifecycle. Compile the exact opt-in independent
platform method; Android execution is separate evidence. Production exports eight config
JNI and19 session JNI, archive83, no test observers/seed/peer or stock parser exports.

### 7. Wrong vs Correct

Wrong: invent an alias/user to scan, use Host matcher short-circuit results as a declaration
index, expand dynamic Include with fake context, or attach catalog as SshContentValidator.
Correct: shared lexer/full tokens, conservative managed static traversal, located deferred
sites, and a later real Request plus whole-manifest validation. Current runtime results:
[verification](../../../design/verification.md#20261019-config-catalog).

## Managed config JNI / Java (2026-10-19)

### 1. Scope / Trigger

Use `SshConfigEvaluator` for the production Java/native evaluation boundary described in
[the managed contract](../../../design/ssh-config-managed.md). It borrows the caller lease;
no catalog/validator/connector, role, secret or socket authority is created.

### 2. Signatures

Six `SshConfigEvaluator.Native` entries: `open(long view, byte[] request):long`,
`run(long):int[5]`, `summary(long):byte[]`, `cancel(long)`, `close(long)`, `destroy(long)`.
All native methods throw fixed-text IOException on JNI/binding failures. `run()` in the Java
facade throws `EvaluationException` retaining immutable Diagnostic on a nonzero native status.
`ssh_config_handle.h` owns registry/wire limits and declarations; no JNI Options-pointer API.

### 3. Contracts

64 slots, positive signed64 monotonically increasing tokens, never pointer casts or reuse.
Reserve/copy/decode before borrow. A post-borrow eval_open failure still returns a positive
cleanup handle retaining pendingBorrow/open_errno; Java preallocates its wrapper and cannot
discard that handle via a failing post-open lease check. Close releases only its borrow,
then destroy clears the tombstone; each failure retains its retry point. Never lease.close().
Owner pthread/PID/UID for ordinary methods, PID/UID for cross-thread cancel. Busy pin protects
the slot while the registry mutex is released across parser/IO; cancel atomically touches
the retained evaluator under a short lock, shared with destroy. Do not hold Java's monitor
across run. Java ThreadLocal entry guards reject reentry; establish them before inFlight so
an Error during setup cannot leave the object uncloseable. Finally clears guards after Error.

### 4. Validation & Error Matrix

| Condition | Required result |
| --- | --- |
| Request | CSEQv1 big-endian,7 length-prefixed standard UTF8 texts,9766 bytes maximum; explicit u32 virtual UID/numeric port; no NUL/surrogate/ISO controls/trailing bytes |
| Summary | CSESv1,11 fixed ints+alias/host/user texts,829 bytes maximum; flags/counts/TTY0..3/session0..2 validated; hasRawFields=1 |
| Successful run | Exactly `[0,0,0,0,0]` |
| Configuration rejection | `[1,reason,object,line,errno]`, reason1..13, object0..128, line0..16385; rejected budget line16385 is valid |
| Admission/lifecycle error | `[2,0,0,0,errno]`; preserve original errno, never fabricate INPUT for EBUSY/EALREADY/EPERM |
| Getter after cancel/revoke/expiry | Fail; no cached Java summary bypass |
| Pre-borrow open failure | No handle or borrow retained |
| Post-borrow open failure | Positive handle, run reports error, close retries pendingBorrow release |
| JNI allocation/region exception | Preserve pending exception, stop normal JNI calls; keep known handle/resources |

JNI uses byte arrays, never modifiedUTF8/NewStringUTF. Preallocate run's Java array before
one-shot parsing. Recheck native summary lifetime after JNI array writes; wipe temporary
wire copies. A returned Summary is historical metadata, not a live capability or final plan;
full raw Options stays native and future consumers must finalize every retained field.

### 5. Good / Base / Bad Cases

Good: real managed Include yields UTF8 target/user metadata; evaluator close leaves caller
lease usable. Base: User absent yields userRequired and empty user. Bad: included command
policy failure preserves reason/object/line and fixed message; later close still succeeds.

### 6. Tests Required

Independent real native registry/parser tests: CSEQ/CSES exact limits, malformed frames,
retained full Options, failed-open cleanup handle, injected release/close/destroy/unmap retry,
64 slots/stale tokens/INT64 exhaustion, wrong pthread/PID/UID, expiry/revoke, FD baseline and
deterministic fread-paused cross-thread cancel with busy destroy rejection. Effect count0.
JVM tests separately verify request/summary/diagnostic codecs, owner/reentry/cancel races and
Error cleanup. The exact opt-in platform fixture must compile; actual Android/JNI execution
needs its own evidence and current scope. Production exports six config JNI and19 session
JNI, excludes test token seed/wrap observers/fixture peer, and keeps archive83.

### 7. Wrong vs Correct

Wrong: release then discard a failed cleanup borrow, close the shared lease, lock around
the entire parser, or cache a Summary as authority. Correct: cleanup-capable opaque slot,
borrow-only release, busy pin with short locks, and fresh guarded getters. Actual local
results and limitations: [verification](../../../design/verification.md#20261019-config-jni).

## Managed native config evaluation (2026-10-19)

### 1. Scope / Trigger

Production native prerequisite, implemented against [the evaluator contract](../../../design/ssh-config-evaluator.md).
Pinned readconf has translation-unit-local effect hooks and namespaced exports; independent
objects link into the existing shared library. Keep the original 83-member archive intact.
No new JNI/catalog/validator/socket authority is implied.

### 2. Signatures

`ctos_ssh_config_eval_open(borrow, request, &eval)` transfers borrow only on success;
`run(eval, &error)` runs once; `summary(eval, &summary)` and native-internal
`options(eval, &const_options)` require READY; `cancel(eval)` alone permits cross-thread use;
`close(eval)` is retryable and `destroy(eval)` requires a closed tombstone.
Exact declarations and named limits live in `ssh_config_eval.h`.

### 3. Contracts

Owner pthread/PID/UID, original view deadline/revocation and atomic cancellation guard
every read and result publication. A dedicated trylock protects parser caches/current context.
Preserve full owned Options and managed Include first/final semantics. `remote_user` is a
literal override; `remote_user_fallback` supplies Match/%r context without overriding User.
`summary.has_raw_fields=1`: paths/session/forward fields require later finalization; consumers
must implement or explicitly reject jump/forward behavior. Never treat this as a launch plan.
No host FS/env/NSS/DNS/exec/network/log effects or global logger/environment mutation.

### 4. Validation & Error Matrix

| Condition | Required outcome |
|---|---|
| Relative/~/Include glob | Resolve only under virtual /ctos-vault, lexical order, no GLOB_TILDE env lookup |
| Path escape, missing object, cycle | Located PATH rejection, no host fallback |
| Input-triggered fatal/helper failure | Structured reason/object/line/errno with allocation cleanup |
| Algorithm assembly failure | DEFAULTS with first-assignment source, including included files |
| Missing remote username needed by expansion | USER_REQUIRED; never derive remote user from local UID |
| >8 jumps, >32 combined forwards, >256 other list items | LIMIT, never truncate |
| Host/user (including decoded jump fields) >255 bytes / invalid UTF-8 | LIMIT/E2BIG / INPUT/EILSEQ |
| Active canonicalization/localnetwork/host environment effects | Explicit UNSUPPORTED or EXPANSION rejection |
| Reentry/concurrent parser use | EBUSY |
| Failed reader unmap | Preserve mapped reader and borrow for retryable close |

Upstream xalloc system-OOM behavior remains terminating; input limits are not an OOM excuse.
Initialize both canonical-domain/permitted-CNAME pointer containers to NULL before complete
cleanup: pinned initialize_options otherwise leaves poison pointers for zero-length arrays.
Free all owned arrays/strings and inactive/duplicate forward temporaries.

### 5. Good / Base / Bad Cases

Good: nested managed Include with Match final and additive identities preserves full Options.
Base: no User yields user_required, ordinary target defaults remain available.
Bad: Match exec, external provider, trust bypass, service-name port, malformed token or URI
fails without effects; the following independent valid evaluation must still succeed.

### 6. Tests Required

Run real pinned parser matrices, exact list/line/depth/jump boundaries, independent %C vector,
root/included diagnostic provenance, cancel/revoke/deadline, real CSPV registry ownership,
wrong pthread/reentry, injected read/unmap errors and repeated FD/reader cleanup. Test-link
effect observers must report zero; they do not replace scoped production hooks. Build strict
dynamic/static ARM tests and shared library; record actual ARM execution separately from
Android/JNI and unavailable dynamic runtime. Audit original archive and JNI exports.

### 7. Wrong vs Correct

Wrong: final-link global `--wrap`, setjmp/longjmp across allocations, dropping failed munmap
state, or exposing only HostName/User/Port as a complete plan.
Correct: scoped parser hooks, returning cleanup paths, retained retry state and full owned
Options with guarded getters. Never retain the Options pointer after close or bypass getters
after revoke. Evidence: [local verification](../../../design/verification.md#20261019-config-evaluator).

## Actual offered host key and exact NEW review (2026-10-19)

Frozen contract: [launcher prerequisite](../../../design/ssh-launcher.md).
This increment does not provide production config evaluation, catalog or socket startup.

### 1. Scope / Trigger

Use explicit MANAGED mode when the caller does not already possess the server key.
Existing PINNED callers and authentication/PTY contracts remain unchanged.

### 2. Signatures

Native config appends `host_key_mode`: PINNED=0 requires the original key/fingerprint;
MANAGED=1 requires NULL key, length0, NULL fingerprint. Mixed/unknown modes fail before
socket transfer. Java `openManaged(callback, lease, fd, candidateId, stepBudget)` uses
its own JNI entry (19 session exports total), shares admission/claims/view ownership,
and has no key argument. NativeBinding default fails closed. No key/secret getter.

### 3. Contracts

`ctos_ssh_kex_bind_pending_host` reserves the exact role/route slot without approval.
`ctos_ssh_kex_pin_offered_host` can bind once only during begun initial cycle1 before
approval/signature/completion. Unbound guards cannot approve, sign, complete or become
ready. Actual transport verifier alone classifies the offered key through the existing
issuer/candidate/exact managed evidence path. Only KNOWN or approved CAS_COMMITTED NEW
may pin after renewed owner/env/role/view/cancel/deadline checks. Manual verifyHostKey
is forbidden in MANAGED mode; Java refusal is local/nonterminal. Upstream signature and
NEWKEYS still determine KEX; final selected SUCCESS alone authorizes a channel.

SshHostKeyReviewMailbox binds the exact Review and role objects, immutable public
metadata and unique ticket identity. One current decision only; approval still requires
the caller's exact proposal/CAS. Original role deadline and a hard30s wall ceiling bound
wait, with <=50ms slices and repeated live checks. UI resolve/cancel uses its independent
monitor without owner queue or session JNI; late/cross/reused tickets fail. Missing UI,
reject, interrupt, cancel or lost liveness cannot produce approval. Sink present/dismiss
must publish without blocking; the ceiling cannot constrain arbitrary sink code.

JNI still holds its global sessions_lock during the callback, so waiting blocks other
session JNI. Do not promise concurrent-hop progress. Cross-thread cancellation wakes the
mailbox directly; owner performs cleanup afterward. Do not extend role/loan deadlines.
Failed connection cleanup does not undo an already committed user-approved trust write.

### 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Mixed mode, unknown mode, invalid PINNED inputs | reject before FD ownership transfer |
| Manual MANAGED verify, unbound/duplicate/late pin | no new host authority |
| CHANGED/REVOKED/CERTIFICATE/INVALID or absent exact CAS | terminal KEX, no credential |
| Post-callback cancellation/expiry/view/env drift | terminal, no pin/credential |
| Stale, duplicate, cross-role or cross-mailbox ticket | no decision/proof |
| Waiting owner loses role/view/callback liveness | wake/poll failure, ticket invalid |

### 5. Good / Base / Bad Cases

Good: actual offered key→exact KNOWN or reviewed NEW/CAS→signature/NEWKEYS→selected auth
SUCCESS→shell. Base: PINNED stops and fixtures remain supported. Bad: select an arbitrary
known_hosts key before KEX, auto-approve NEW, or enqueue approval behind its waiting owner.

### 6. Tests Required

Encrypted native positive/negative/callback-liveness and guard matrices; JVM explicit
open/claim/reentry/cleanup plus mailbox decision/wait/reentry/liveness; strict JNI build
and isolated Android fixture compile. Native QEMU and JVM are not Android JNI runtime.

If a temporary dynamic Bionic runtime is gone, preserve the rebuilt dynamic test as
not-run; do not fetch device libraries without current authorization. A separately linked
NDK/Bionic static test may supply additional synthetic evidence only: record its own
source/link adaptations, binary hash, counts and failed attempts. Never substitute that
result for dynamic loader/Android JNI/device validation or modify the production archive
to make the alternative test link. See the [static fallback evidence](../../../design/verification.md#20261019-managed-host).

### 7. Wrong vs Correct

Wrong: `session.open(..., null, null)` silently selects discovery, or UI calls session
JNI to approve. Correct: explicit openManaged plus exact independent review mailbox;
the existing candidate/CAS and transport verifier alone mint authority.

## One session channel / PTY / shell (2026-10-19)

The 2026-10-19 implementation contract is [session channel](../../../design/ssh-session-channel.md).
Only final selected authentication SUCCESS permits beginShell. Preserve current proof,
revocation, original deadlines, owner environment and thread-wide reentry checks.
Bounded native channel parsing/flow control precedes JNI/Java synthetic validation,
then existing terminal integration. Actual local/runtime evidence is recorded in
[verification](../../../design/verification.md#20261019-ssh-shell). Do not infer that
the product launcher exists.

### 1. Scope / Trigger

Use this contract when continuing authenticated sessions into one interactive PTY,
or routing that session through the existing terminal renderer/owner.

### 2. Signatures

Six owner-only native APIs `begin_shell/shell_status/shell_write/shell_read/shell_resize/shell_eof`
and corresponding Java `beginShell/shellStatus/writeShell/readShell/resizeShell/sendShellEof`
use the exact frozen signatures/status tags0..6 in the linked design. JNI status is int[7];
exit u32 is Java unsigned long. NativeBinding defaults fail closed, step's eight-int wire
and initial-KEX complete remain unchanged. Shell增量为18 exports；后续显式MANAGED open后共19，见上节。

```java
void beginShell(int columns, int rows) throws VaultException;
ShellStatus shellStatus() throws VaultException;
int writeShell(byte[] bytes) throws VaultException;
byte[] readShell(int maximum) throws VaultException;
boolean resizeShell(int columns, int rows) throws VaultException;
boolean sendShellEof() throws VaultException;
```

### 3. Contracts

Only selected final SUCCESS2 can begin once. Current exact auth_proof remains mandatory;
original role deadline is not renewed. Sequential open→PTY→shell confirms only after
request ciphertext drain. One local channel0, window65536/maxpacket32768; peer IDs and
windows are validated. read consumes bounded merged stdout/stderr and earns window
credit; write returns accepted prefix/zero backpressure, never silently retries prefix.
Transport input<=256KiB, encrypted output<=128KiB. Normal CLOSE and rejection drain
before public terminal phase; process buffered packets before treating socket EOF as
failure. Unknown exit status stays unknown; exit-signal never creates status0.

Java retains saved selected-auth result and separate shell phase; owner and thread-wide
reentry precede binding calls, pre/post live guards precede publication. Sticky accepted
local EOF prevents a lying/regressing binding from reopening input. Native failure/Error
keeps terminal uncertainty and cleanup responsibility; read arrays are wiped on failure.
`shellOutputPublicationLive()` is a read-only cross-thread drop guard for already-read
UI output, grants no native entry, and observes cancel/closed/view/role/original clock.

SshTerminalSession owns cooperative pump, input<=65536, latest resize and one<=32768
inflight output with acknowledgement. MainActivity factory executes on same scheduled
terminalWorker; no live session transfer between threads. Factory receives a per-launch
cancel token reserved before queueing, owns partial/extra resources only once invoked,
and must close idempotently for retry. Controller closes session then factory; identity
fences protect delayed release, and executor release waits outstanding output ack.
Error paths retain owner retry before propagation. onDestroy schedules an owner barrier
before shutdown; Stop fences cancel immediately and queues owner cleanup. No new
foreground service, resolver, credential UI or external connector is implied.

### 4. Validation & Error Matrix

| Condition | Contract |
| --- | --- |
| KEX/service only, rejected/partial/change auth | no channel/provider, local phase or EACCES |
| Wrong owner/environment or same-thread reentry | precise existing refusal, no alien mutation |
| Malformed/early/wrong-channel/window overflow | terminal protocol error; no successful status |
| No window/encrypted output capacity | accepted0/false; caller retains suffix/latest resize |
| Cancel/view revoke/deadline/proof drift | terminal protected effect; no UI byte publication |
| Peer CLOSE with buffered output | drain/read/ack before cleanup; optional exit remains typed |
| Cleanup failure/Error or late ack | retain exact owner/resources until confirmed; no premature shutdown |

### 5. Good / Base / Bad Cases

Good: real encrypted synthetic auth→channel/PTY/shell→exact command/output→resize→u32
exit/EOF/CLOSE, then retryable complete cleanup. Base: auth-only callers retain their
old stops. Bad: allow PARTIAL, treat server text as lifecycle, move session to a reader
thread, use infinite Handler queue as flow control, or clear a newer owner in old cleanup.

### 6. Tests Required

Required local tests cover native encrypted positive/negative/window/EOF/cleanup, JVM
phase/liveness/reentry/read wiping, controller queue/ack/Error retries, Flutter typed
generation/UTF8/SSH arrows and App/Root regression. Device JNI fixture execution and
product launch acceptance are separate evidence, never inferred from compile/JVM.

### 7. Wrong vs Correct

Wrong: `if (output.contains("[session exited:")) session = false;` or resetting one
global cancellation boolean inside a queued launch. Correct: parse typed host state
with generation fencing, and reserve an exact cancellation token before queueing.

## Current scope

The production pinned OpenSSH10.6p1 PIC archive (83 members, stock high-level
callers excluded) now drives native KEX, fixed userauth service, one password
request or direct-signed private-key request, then one session channel / PTY / shell
through19 session JNI exports (including explicit managed-host open).
Java owns explicit phases and a thread-wide reentry guard. CSASv1 unencrypted
keys and CSASv2 exact key/passphrase bundles use the same selected one-attempt
receipt and current role/view/KEX proof. No Java session secret getter exists.

2026-10-19 actual API30/arm64 independent Android fixtures passed full KNOWN
password and unencrypted/encrypted private-key chains, plus real NEW candidate,
approval and in-memory AppSecrets CAS followed by authentication. The peer
DT_NEEDED-links the same production shared library, verifies every request and
signature, and uses generated synthetic identities only. Host QEMU/Bionic and
JVM evidence are recorded separately in design/verification.md. Real accounts
and the production vault were not used; fixture packages were uninstalled.

This remains an incomplete product client: forwarding, profile/connector launcher,
KBI/MFA continuation, rekey/compression compatibility and real-account acceptance
are open. Missing providers continue to fail closed. Encrypted PKCS8 is rejected;
exact supported encryption/KDF limits follow below. Earlier dated/base contracts
remain useful for compatibility, not as claims that newer APIs are absent.

## Java/JNI password orchestration and isolated Android peer

### 1. Scope / Trigger

Connect the existing C service/password protocol to SshNativeSession, then validate
real Java→JNI→native KEX/service/password→JNI credential callback→in-memory
AppSecrets paths with an opt-in Android fixture. Continue into private-key work
without requiring another user “continue”. Device operations wait for a concrete
fixture and current AGENTS authorization; no real account is a development dependency.

### 2. Signatures

```java
void beginUserauthService() throws VaultException;
boolean userauthServiceReady() throws VaultException;
void beginPasswordAuth() throws VaultException;
PasswordAuthResult passwordAuthResult() throws VaultException;
// explicit tags: NOT_STARTED0 PENDING1 SUCCESS2 REJECTED3 PARTIAL4 CHANGE_REQUIRED5
```

Append NativeBinding defaults throwing IOException for all four methods; production
Native adds unique long-handle entries with void/boolean/void/int returns. No secret
return API. Total session JNI exports becomes10. Keep step's existing eight-int wire
and StepResult.complete as initial KEX, not authentication.

Fixture-only helper SshAuthFixturePeer uses its own opaque owner-thread registry:
open(borrowed raw AF_UNIX peer FD, response enum), hostKeyBlob(handle), step(handle),
summary(handle), close(handle). Fixed peer username/password are fixture constants;
no arbitrary credentials are accepted. Native duplicates the borrowed descriptor;
Java retains its original descriptor on success and failure. Close wipes generated host
private key. Its JNI library DT_NEEDED-links libctos_ssh_files.so, sharing the exact
production OpenSSH implementation rather than statically copying another instance.
No role/callback/client objects are reimplemented in the helper.

### 3. Contracts

- One static ThreadLocal native-entry guard covers ALL SshNativeSession operations
  that can call a NativeBinding, including static open, legacy step/verify/receipt,
  close/destroy and new methods. Claim after owner validation, before managed claim/
  phase/liveness work; finally remove on every return/exception/Error. Reentry on
  the same thread into the same OR another session is SSH_SESSION_REENTRANT, no
  nested binding call or inner state mutation. This prevents deadlock on the global
  JNI sessions_lock held across synchronous Java callbacks. If a provider explicitly
  handles the refusal, the authorized outer operation may continue. cancel remains
  cross-thread allowed and is not a guarded session JNI entry. Local observations
  such as closed/cancellation state may remain readable.
- Real AppSecrets file snapshots last120s while native roles are capped60s. Session
  admission requires view.deadline >= role.deadline, while config.deadline equals
  the role deadline exactly. This only permits a shorter role within its existing
  view loan. Both view and role deadlines/revocation are still checked on every
  protected effect; never extend a role or accept a view expiring before it.
- Preserve complete()'s old KEX-only stop when no explicit service operation occurred.
  Java tracks KEX_ONLY/SERVICE_PENDING/SERVICE_READY/PASSWORD_PENDING/FINISHED; KEX
  complete is retained separately. Begin service requires completed KEX/KEX_ONLY;
  begin password requires SERVICE_READY plus retained selection receipt. Wrong local
  phase is nonterminal SSH_SESSION_PHASE, no binding/provider call. Missing selection
  is nonterminal SSH_AUTH_SELECTION. C remains authority for exact PASSWORD kind,
  current proof, READY and one-shot; native failures terminalize managed protocol.
- New operations check owner/open/nonterminal, direct callback cancellation/closing,
  view and role/clock before and after native calls, permitting completed KEX. Reuse
  the selection read's complete-capable live guard, with SESSION_CANCELLED mapping.
  Native IOException/LinkageError/RuntimeException maps SSH_SESSION_SERVICE or
  SSH_SESSION_AUTH (query errors AUTH_RESULT); mark terminal, preserving cancellation/
  expiry precedence. Escaping Error still propagates and always releases entry guard;
  mutating protocol calls with uncertain native completion mark terminal before escape.
- step is allowed after KEX only in SERVICE_PENDING/PASSWORD_PENDING. After successful
  step in these explicit modes, query the corresponding native readiness/result
  inside the same entry guard, validate it, and update Java phase; no extra queries
  for legacy KEX-only steps. This prevents a hidden completed C phase from causing
  accidental next-step EALREADY. SERVICE_READY/FINISHED step calls reject locally
  with SSH_SESSION_PHASE and no binding call, while new auth or live result reads
  remain permitted as appropriate. Existing verify's KEX-complete stop is preserved.
- Service-ready false is allowed only while SERVICE_PENDING; true advances to READY
  but does not authenticate. A regression to false after READY is a terminal service
  result failure. passwordAuthResult before begin permits only NOT_STARTED in READY;
  after begin permit PENDING or one final; after final require the same final value.
  Unknown tags or result regression are terminal SSH_SESSION_AUTH_RESULT. Enums use
  explicit tags, never ordinal-based native protocol assumptions. Final negative/
  partial/change results do not imply generic transport failure or grant continuation.
- JNI entries reuse lock_session owner/opaque token checks and existing fail helper.
  No credentials/username/native pointers cross the new calls. C functions receive
  only retained client; errors throw generic payload-free IOException, status must
  not return successful-looking data while an exception is absent. No logging.
- Fixture build requires -PctosSecretsUiFixture=true -PctosSshAuthFixture=true, rejects
  false values or mixing other SSH probe options, and includes peer.so ONLY in that
  flavor. Normal APK must exclude peer library/class/native exports. Compile peer
  -Wall/-Wextra/-Werror, no-undefined, arm64/API28/16KiB alignment against generated
  production include/header and shared library; no second archive/crypto linkage.
  Helper is SshAuthFixturePeer in secretsFixture Java, library libctos_ssh_auth_peer.so;
  summary is four ints: service count, password count, reply count, exact-fields flag.
- Exact opt-in instrumentation selector plus allowSshAuthFixture=true is required.
  Independent fixture UID/fresh-state guard, AF_UNIX socketpair only, no listener,
  fixed synthetic password/username and generated ephemeral Ed25519 host key. Use
  real AppSecrets MemoryStore/envelope/typed request, production public lease/role/
  callback/session; no fake proof, KEX or credential provider success. Password bytes
  reach only native peer validation; logs contain numeric outcomes/counts only.
- Android KNOWN matrix covers four results, cancellation and no duplicate release.
  NEW/CAS additionally follows the exact own-CAS continuation contract below and
  uses the original attached public loan; fake CAS or detached views cannot
  establish Android NEW acceptance.

### 4. Validation & Error Matrix

| Condition | Required result |
| --- | --- |
| Wrong owner/closed/cancel/expired/view revoked | existing precise lifecycle errors; no new provider grant |
| Same-thread same/other-session reentry | SSH_SESSION_REENTRANT before inner state/binding; no deadlock |
| Premature/duplicate/after-final transition | SSH_SESSION_PHASE, no binding/provider |
| Password begin without retained selection | SSH_AUTH_SELECTION, no binding |
| Native missing/default method or I/O/runtime failure | terminal SERVICE/AUTH/AUTH_RESULT, no downgrade |
| Unknown/regressing status or service readiness | terminal typed-result failure, no success |
| Valid service/password progress | preserve KEX complete, explicit phase progresses, exact typed result |
| Fixture missing opt-in/normal flavor | test skipped or property rejected; no fixture native library packaged |
| Real NEW/CAS revokes lease | fail closed; never replace with fake approval/liveness |

### 5. Good / Base / Bad Cases

Good: same production session reaches KEX, service and password result with a real
selected callback into synthetic in-memory AppSecrets. Base: legacy unselected fake
bindings still compile and retain KEX-only behavior. Bad: remove complete guards
without explicit phases, or add only per-instance reentry guard around a global lock.

### 6. Tests Required

JVM: explicit phases/four outcomes/old stops/default fail-closed/owner/cancel/view/
expiry/native failure/unknown and regressing status, no credentials returned; same
and other-session nested calls across every entry, guard cleared after exception/
Error, outer handled refusal, cancel allowed and close retry. Strict JNI link and
10 exports; peer DT_NEEDED and absent normal APK fixture artifacts. Build independent
fixture main/test, verify separate package/target/signer/opt-in and exact test selector;
run on currently authorized board only after concrete artifact review. Record local
build, host JVM/native and Android runtime independently.

### 7. Wrong vs Correct

Wrong: call the global-lock JNI while a callback is active, or link a second static
OpenSSH into the peer and call that Android end-to-end proof. Correct: thread-wide
entry guard with finally cleanup; fixture peer depends on the exact production
shared library and only drives an isolated synthetic encrypted server.

## Own approved NEW-host CAS preserves its exact bounded public loan

### 1. Scope / Trigger

Real NEW-host trust commit currently relocks the vault and revokes the very public
loan that issued the authenticating role. Preserve only that exact attached loan
for the same approved CAS; all generic mutations/unlock/lock behavior still revokes.

### 2. Signatures

VaultOperation stores the exact role pointer at successful bindTrust and exposes a
package-private boundTrustRole() only for that purpose. VaultSession supplies
attachedRevocation(expectedEpoch) and advanceTrustEpoch(expectedEpoch, exactOwned).
Add a production callback bind overload with the owned known_hosts candidate handoff.
No channel API, deadline change, generic preserve flag, new key or new native proof.

### 3. Contracts

AppSecrets.updateTrust alone may derive a continuation from an already bound exact
trust operation and the currently attached revocation owned by that same role.
It still replaces only the exact known_hosts object after CAS base/object/proposal
checks; key/config/private-file/entry state remains unchanged. Final guard checks
active role, exact attached loan and operation epoch in addition to existing exact
snapshot/evidence/proposal checks. After durable commit, advance epoch only if key,
foreground, expected epoch and identical active attached revocation still match.
Keep the current vault key and original public-loan deadline, invalidate all old
operations/previews, and never restore a locked/replaced/revoked loan. Failure after
commit yields no COMMITTED host receipt and no authentication grant. Legacy unbound
trust operations and every other mutation retain normal unlock/revoke behavior.
Manual cancel of the old public-file request still revokes its attached loan even
after its operation was removed from the active map. The preserved frozen view may
contain the old trust body: only the accepted exact replacement proof authorizes
this initial KEX; no rekey or new-session trust shortcut is granted.

### 4. Validation & Error Matrix

Exact approved own CAS → epoch advances and same loan stays active; wrong loan/role/
epoch or lock/revoke/background race → fail closed, no resurrection/host receipt;
generic mutation/re-unlock/manual lock/old-file cancellation → revoke as before;
failed/uncertain store effect → no COMMITTED receipt or credential release.

### 5. Good / Base / Bad Cases

Good: approved NEW KEX continues with its exact original public loan and new vault
epoch. Base: fake/unattached JVM views retain old behavior. Bad: preserve every
lease on every write or keep an old epoch valid to avoid proper invalidation.

### 6. Tests Required

VaultSession exact identity/epoch/foreground/key/revocation/no-resurrection and
normal subsequent revocation; real managed AppSecrets CAS attached-view success,
late lock/revoke/uncertain effects and old operation cancellation. Isolated Android
production NEW candidate→exact approval→real store CAS→KEX/service/auth, for password
and encrypted private key, with metadata/callback/epoch and cleanup assertions.

### 7. Wrong vs Correct

Wrong: use fake CAS success or detached public view for Android NEW acceptance.
Correct: preserve one exact bounded existing loan only across its own approved CAS,
with fresh epoch and all original liveness checks.

## Selected encrypted private-key capability

### 1. Scope / Trigger

Complete encrypted-key authentication without treating key identity as the remote
target or granting a second kind-only credential callback. Only synthetic fixtures
are used. Preserve all existing unencrypted CSASv1 behavior and one-attempt rules.

### 2. Signatures

Overload `SshAuthenticationSelection.privateKey(..., publicBlob, passphraseEntryId,
clock)`. Nonempty identifier selects CSASv2 (version2/flags0), PRIVATE_KEY only;
the existing five fields remain username/entryId/path/keyIdentity/publicBlob.
v1 private entryId remains empty; v2 requires an exact nonempty identifier.

Add AppSecrets purpose SSH_PRIVATE_KEY_BUNDLE, begin/use methods binding resource
`path + NUL + keyIdentity + NUL + passphraseEntryId`, actual remote target and
connection. One receiver gets CSKBv1: u32 magic0x43534b42, u32 version1,
SSH-string privateFile, SSH-string passphrase. Total <=1MiB, private nonempty,
passphrase1..8192 with no NUL. Existing selected PRIVATE_KEY opcode4/receipt and
JNI method remain unchanged; no Java session secret API or second release.

### 3. Contracts

- Before request and at final effect, require exact PRIVATE_KEY file/path/identity
  plus exact SSH_KEY_PASSPHRASE entry whose identity is `key:` + keyIdentity. Keep
  operation target equal to role.targetIdentity. One operation owns both resources
  in the same authenticated envelope snapshot, epoch and deadline; typed purpose
  prevents generic key/credential or wrong entry crossover. No fallback or lookup
  by prompt/error text. Request selection identity/entryId is rechecked by bridge.
- Bundle is built in bounded owned memory and wiped after receiver or any throw.
  Host/KEX/role/selected callback final guard runs immediately after protectedEffect
  (which can read the store), immediately before receiver. Retain earlier checks.
  Apply this final ordering to existing credential/private-key releases too.
- Native v2 parses strict CSKB framing/count/size/end, copies passphrase into a
  separately NUL-terminated bounded scratch, and wipes raw bundle and passphrase
  immediately after in-memory parser returns. Parse/sign/match/verify/fresh proof,
  output reset, one-shot and all cleanup contracts remain those of direct-signed auth.
- Supported encrypted input: standard armored OpenSSH bcrypt/aes256-ctr with
  one key, salt1..64bytes and rounds1..64, inspected before running its KDF;
  legacy encrypted RSA/EC PEM with fixed PEM encryption header and AES-128-CBC or
  AES-256-CBC. Reject encrypted PKCS8 and unsupported cipher/KDF/rounds ENOTSUP:
  attacker-controlled PBKDF parameters must never block the owner event loop
  without a bound. OpenSSH armor inspection accepts standard LF/CRLF and bounded
  base64 only. No filesystem, agent, prompt or default passphrase selection.
- Wrong selected passphrase is terminal EACCES, malformed bundle/file EINVAL,
  unsupported bounded policy ENOTSUP; no retry/alternate key/passphrase grant.
  Version1 encrypted input remains fail-closed ENOTSUP with no automatic upgrade.

### 4. Validation & Error Matrix

v2 PASSWORD or empty private entry → invalid selection before ownership transfer;
wrong purpose/path/key/entry/remote/connection/snapshot → vault denial, no receiver;
cancel/lock/expiry/view/KEX drift at final store read → no receiver or wire request;
malformed CSKB/NUL/oversize → consumed terminal EINVAL; wrong passphrase → EACCES;
unbounded KDF/unsupported encrypted format → consumed terminal ENOTSUP.

### 5. Good / Base / Bad Cases

Good: exact key+passphrase capability becomes one signed request after local verify.
Base: v1 unencrypted request and every password path remain compatible. Bad: change
the operation target to `key:identity`, release kind2 after kind4, or call bcrypt
with an unchecked imported rounds value.

### 6. Tests Required

JVM canonical v2/immutable entry validation and real in-memory AppSecrets exact
purpose/resource/entry-type/key-identity/remote/connection/one-shot/final-effect
cancel/cleanup. Native actual encrypted key algorithm/format matrix, wrong passphrase,
malformed bundle, unknown versions, NUL, oversized fields, KDF rounds65/unsupported
cipher/PKCS8 fail-closed without signing, while v1 remains unchanged. Extend isolated
Android generated encrypted identities and passphrase entry; actual peer verifies
all five algorithm signatures and negatives before declaring encrypted Android pass.

### 7. Wrong vs Correct

Wrong: use the generic passphrase credential API with a key identity as target.
Correct: atomic typed resource bundle bound to both exact key identity and real
remote role/connection, sealed into CSASv2 and one selected receipt.

## Java/JNI private-key orchestration

### 1. Scope / Trigger

Expose native direct-signed authentication through the same session owner and
synthetic Android peer. Both v1 unencrypted keys and explicit v2 key/passphrase
bundles use this API; own-CAS continuation follows its separate exact contract.

### 2. Signatures

`beginPrivateKeyAuth()` and `privateKeyAuthResult()` return no secret. Explicit
PrivateKeyAuthResult tags are NOT_STARTED0/PENDING1/SUCCESS2/REJECTED3/PARTIAL4.
Append two fail-closed NativeBinding defaults and unique long-handle JNI wrappers;
total session JNI exports12. Keep password tags and eight-int step wire unchanged.

### 3. Contracts

Use the existing thread-wide guard and owner/live/selection checks. Append
PRIVATE_KEY_PENDING; a successful begin marks the selected method. Final reads
must use that same method, and password/private crossover refuses locally with
SSH_SESSION_PHASE before native calls. READY permits NOT_STARTED only; pending
permits PENDING or one final, final requires stable same result. step queries the
pending method inside its entry guard. Unknown/regressing results terminalize with
AUTH_RESULT; begin errors use AUTH and preserve cancellation/expiry/view precedence.
Escaping Error releases the guard; uncertain mutating calls terminalize. Native
remains authority for actual PRIVATE_KEY selection and cryptographic checks.

### 4. Validation & Error Matrix

Wrong phase/crossover → nonterminal PHASE; missing selection → AUTH_SELECTION;
native begin failure → terminal AUTH; bad result → terminal AUTH_RESULT; owner,
cancel, expiry and view failures retain existing precise codes. JNI accepts only
opaque owner handle, maps exact0..4, and unlocks on every path.

### 5. Good / Base / Bad Cases

Good: current native key request reaches one stable typed result; base: all password
and legacy KEX behavior retained. Bad: accept password CHANGE_REQUIRED as private-key
success, or let final reads cross methods.

### 6. Tests Required

JVM final outcomes, step progression, crossover, unknown/regression, default failures,
lifecycle/native error and same/other session reentry. Strict12-JNI build; ARM actual
encrypted native peer verifies signatures. Extend isolated Android peer with generated
ephemeral identity distinct from host, real synthetic AppSecrets private-file selection,
exact peer signature verification, result and cleanup assertions before claiming Android.

### 7. Wrong vs Correct

Wrong: expose key bytes through Java session APIs or infer authentication from KEX.
Correct: owner-only method API with explicit result and proof-bound native release.

## Native direct-signed private-key USERAUTH

### 1. Scope / Trigger

Continue the Android/JNI stage with one direct-signed native publickey request from
owned CSAS PRIVATE_KEY selection. CSASv1 parses unencrypted OpenSSH
Ed25519, ECDSA p256/p384/p521 and RSA plus actually supported unencrypted PEM
combinations. CSASv2 uses the separately bound key/passphrase capability above.
Neither route grants KBI/MFA continuation or completes the remaining product AC.

### 2. Signatures

```c
enum ctos_ssh_native_client_private_key_auth_result {
    CTOS_SSH_PRIVATE_KEY_AUTH_NOT_STARTED = 0,
    CTOS_SSH_PRIVATE_KEY_AUTH_PENDING = 1,
    CTOS_SSH_PRIVATE_KEY_AUTH_SUCCESS = 2,
    CTOS_SSH_PRIVATE_KEY_AUTH_REJECTED = 3,
    CTOS_SSH_PRIVATE_KEY_AUTH_PARTIAL = 4
};
int ctos_ssh_native_client_begin_private_key_auth(struct ctos_ssh_native_client *);
int ctos_ssh_native_client_private_key_auth_result(
    const struct ctos_ssh_native_client *,
    enum ctos_ssh_native_client_private_key_auth_result *);
```

Retain public-blob offset/length at the existing CSAS decode, just as username.
No caller username/public/private key/algorithm/signature input. Preserve existing
password C signatures and result tags. Share internal phase/response/proof machinery
with a method tag instead of two divergent transport loops. Shared selected attempt
permits only one method/key release per session.

### 3. Contracts

- Same owner/env/role/view/deadline/READY/unique current KEX cycle and fresh raw-session
  SHA proof as password, before provider and after parse/sign/verify/before enqueue.
  Const typed result checks current proof without I/O/provider/client mutation and
  clears output on error. Wrong owner EPERM does not poison/consume; wrong selection
  kind/premature is EACCES no provider; consumed or any active/final attempt EALREADY.
- Before private release, parse the owned expected public blob using sshkey_from_blob
  with exact end. Reject certificates/SK/external/DSA/unsupported plain types. RSA
  minimum is pinned upstream1024 (SSH_RSA_MINIMUM_MODULUS_SIZE), not invented2048.
  Canonical public wire must be <=64KiB; signed input is bounded by upstream
  SSH_KEY_MAX_SIGN_DATA_SIZE. Reject invalid metadata/policy before consuming secrets;
  pre-attempt ENOMEM may retry. No output/rekey is allowed before release/serialization.
- Ed25519 algorithm is ssh-ed25519; ECDSA uses the exact supported curve algorithm.
  RSA requires exact token intersection with server-sig-algs, prefers rsa-sha2-512
  then rsa-sha2-256. Missing/empty/no-mutual/substring-only advertisement is ENOTSUP
  before provider; never NULL/empty algorithm or SHA1 fallback. Other types never
  invoke hardware/agent/PKCS11/SK providers. Reject SSH_OLD_SESSIONID compatibility;
  no hostbound method or unsigned probe is implemented in direct mode.
- Obtain <=1MiB PRIVATE_KEY bytes through the existing receipt-bound selected getter
  once; no public-view private-content read. Parse in memory via
  sshkey_parse_private_fileblob(...,NULL,...,NULL), without path/prompt. Empty/malformed
  file errors map EINVAL, unsupported encrypted/passphrase-required errors ENOTSUP;
  conservative undecodable PEM failure may be EINVAL. After consumption every error
  is terminal, no retry/alternate key/passphrase request. Encrypted input must never
  trigger interactive or unbounded default prompting. Wipe raw scratch immediately
  after parsing; sshbuf_from's borrowed memory is not erased by sshbuf_free.
- Parsed private key must be allowed plain software type with no EXT/SK/cert flags,
  satisfy the same size policy and sshkey_equal_public against expected public.
  Mismatch is terminal EACCES. Build signed data with string RAW initial session ID,
  byte50, string exact owned username, ssh-connection, publickey, booleanTRUE,
  string chosen algorithm and string canonical public blob. Never sign callback
  digest/receipt, unframed legacy session ID or caller-built packet.
- Sign once with explicit algorithm, check returned signature type, and locally
  verify using the expected public key before queueing. This is mandatory even after
  equal_public: Ed25519 serialized private/public fields may be internally inconsistent
  without the upstream parser deriving public from seed. Signature/local verification
  failure is EACCES (allocation ENOMEM), terminal and no auth packet. Signature blob
  <=16KiB; exact signed input/packet bounds are checked, never silently truncated.
- After fresh proof/no-rekey/empty-output checks, enqueue exactly one type50 packet
  with the same request fields and complete OpenSSH signature blob as SSH string.
  Private key/passphrase bytes are never on the wire. Wipe/free raw scratch, signature,
  owned buffers and parsed expected/private keys on every exit, before begin returns.
  Serialization errors map EIO; reset outgoing plaintext with start(IGNORE) without
  sending cleanup header as password does. Document source-only upstream wipe claims.
- Reuse request drain→AWAIT_REPLY and bounded BANNER/FAILURE/SUCCESS/DISCONNECT logic
  and liveness checks. Direct-signed accepts SUCCESS/REJECTED/PARTIAL only; type60
  PK_OK/INFO_REQUEST is unexpected EPROTO, never password CHANGE_REQUIRED or permission
  to release another key. Old password behavior remains unchanged. Generic KEX
  complete/service-ready remain milestones. All final results stop step and begin;
  no next method/channel authorization, rekey or compression compatibility claim.

### 4. Validation & Error Matrix

| Condition | Required result |
| --- | --- |
| Null/closed/failed or consumed | EINVAL/ENOENT/EALREADY, no second secret |
| Wrong owner/env, premature or wrong kind | EPERM nonpoisoning / EACCES no provider |
| Invalid/unsupported expected key or RSA no exact SHA2 policy | EINVAL/ENOTSUP/EACCES before provider, no consumption |
| Parse/encrypted/key mismatch/signature inconsistency after provider | precise local error, consumed/terminal, no request |
| Valid signed request drains and exact final | SUCCESS/REJECTED/PARTIAL, no retry |
| Type60/early/malformed/out-of-phase/rekey | EPROTO terminal, no success |
| Cancel/expiry/view/role/current-proof changes | existing exact errors, terminal runtime; readonly status clears output |

### 5. Good / Base / Bad Cases

Good: independent peer reconstructs signed data using its raw session ID and verifies
with the chosen immutable identity before SUCCESS. Base: unchanged password and
standalone getter suites pass. Bad: public-field equality alone accepts a corrupted
Ed25519 secret, or passing NULL algorithm silently selects RSA/SHA1.

### 6. Tests Required

Reuse actual encrypted KNOWN/NEW peer; generate identity independently from host key.
Cover Ed25519, all ECDSA curves, RSA SHA512/SHA256 exact advertisements and supported
OpenSSH/PEM format combinations. Peer verifies exact full request/signature and
independently rejects altered username/sessionID/method/boolean/algorithm/key/signature
and digest-as-session-ID. Local corrupted Ed25519 private-field case must fail before
wire. Test invalid expected public/no secret, unsupported cert/SK/DSA/external, RSA
size/no-advertisement/empty/misleading substring/no mutual, encrypted/malformed file,
valid mismatched private key, one-shot crossover, owner/env recovery, provider/active
liveness failures, response matrix/type60/earlyfinal, status no mutation and close
retry. Keep matrices bounded/reuse generated algorithm fixtures where appropriate.
Record ENOMEM/hash/sign/opaque drift/reset/upstream cleanup branches not injected.
Strict link/QEMU, normal archive83/forbidden0,12 Java/JNI entries and independent
Android peer verification are required and recorded as separate evidence.

### 7. Wrong vs Correct

Wrong: treat CSAS public blob as parsed private-key proof, sign a session digest,
reuse a password-change handler for PK_OK, or add a second credential attempt.
Correct: expected-public parse before release, private parse/equal_public, exact raw
session signing plus local verify, shared attempt, method-specific terminal result.

## Explicit C password USERAUTH protocol

### 1. Scope / Trigger

Extend the completed CSAS/current-KEX/service-ready selected handoff into one real
password USERAUTH_REQUEST and bounded response handling. This slice is C-only;
Java/JNI retain their current KEX-only session API. No private-key auth, password
change, keyboard-interactive, MFA continuation, channel, device or real account use.
Existing complete() stays the initial KEX milestone. Service-only and standalone
selected-byte callers retain their stopping points unless they explicitly begin
password auth.

### 2. Signatures

```c
#define CTOS_SSH_NATIVE_CLIENT_MAX_PASSWORD_BYTES 8192U
enum ctos_ssh_native_client_password_auth_result {
    CTOS_SSH_PASSWORD_AUTH_NOT_STARTED = 0,
    CTOS_SSH_PASSWORD_AUTH_PENDING = 1,
    CTOS_SSH_PASSWORD_AUTH_SUCCESS = 2,
    CTOS_SSH_PASSWORD_AUTH_REJECTED = 3,
    CTOS_SSH_PASSWORD_AUTH_PARTIAL = 4,
    CTOS_SSH_PASSWORD_AUTH_CHANGE_REQUIRED = 5
};
int ctos_ssh_native_client_begin_password_auth(struct ctos_ssh_native_client *);
int ctos_ssh_native_client_password_auth_result(
    const struct ctos_ssh_native_client *,
    enum ctos_ssh_native_client_password_auth_result *);
```

Internal REQUEST_PENDING and AWAIT_REPLY both map to PENDING. No caller username,
password, receipt or proof arguments. The validated CSAS decoder retains username
length/offset into owned canonical wire; use that exact slice on the wire, without
normalizing, parsing targetIdentity or duplicating the record parser.

### 3. Contracts

- Begin checks owner/environment before mutable failure transitions. Require live
  role/view/deadline, PASSWORD selection, READY, unique accepted completion/current
  guard cycle and fresh raw-session SHA matching the saved completion digest.
  Premature/unselected/private-key calls return EACCES without provider/attempt
  consumption. Duplicate auth or an already consumed standalone selected handoff
  returns EALREADY. Wrong-owner/environment EPERM leaves the owner state intact.
- Before secrets, require no queued output and no rekey in progress; an unexpected
  invariant fails terminal EACCES. Use the existing selected-byte entrypoint for
  exact receipt/current-proof gates and the same selected attempt. Allocate outer
  bounded password scratch before that call (ENOMEM may retry if not consumed).
  Never reopen the attempt after a callback/uncertain outcome.
- Accept 1..8192 bytes, matching existing VaultManifest SSH secret bounds. Reject
  ANY NUL, including trailing NUL, with terminal EINVAL after handoff; never silently
  truncate. Other bytes are copied exactly via sshpkt_put_string, without a native
  String/Unicode normalization contract. This deliberately narrows generic 1MiB
  typed handoff to a bounded interoperable password packet.
- Recheck current proof after the callback and immediately before construction.
  Enqueue exactly type50 USERAUTH_REQUEST, string exact selected username, string
  ssh-connection, string password, boolean0, string exact password; add64 padding
  via sshpkt_add_padding then sshpkt_send. No none probe, retries or other methods.
  Wipe/free outer password scratch on every path. Success send resets/wipes owned
  outgoing plaintext in pinned packet/sshbuf. On partial construction/send failure,
  call sshpkt_start(SSH2_MSG_IGNORE) ONLY to reset/wipe the outgoing plaintext,
  never send the cleanup header and terminalize. Pinned packet serialization APIs
  return SSH error codes rather than reliable errno; map those failures to EIO as
  existing client packet calls do, without letting cleanup replace that error. No upstream
  patch/member is required. Reject rekey before construction so password payload
  cannot enter an unexamined rekey queue.
- Explicit auth enables bounded step despite READY. Run full current-proof/liveness
  checks before I/O and before accepting a final result. Preserve byte accounting,
  budget, partial writes/EAGAIN, EOF/cancel/deadline and retryable close. Transition
  REQUEST_PENDING→AWAIT_REPLY only after queued request output fully drains.
  A final reply before that transition is terminal EPROTO, never authenticated.
- While pending/awaiting, parse and discard at most16 USERAUTH_BANNER packets,
  each text<=8192 and language<=128, exact two strings/end. No display/log or prompt
  authority. Continue normal output drain after a banner. EXT_INFO retains existing
  handling; rekey20..49 remains disabled and all unexpected packet types fail closed.
  This retains the narrow transport contract: IGNORE/DEBUG/UNIMPLEMENTED are not
  silently consumed by ssh_api and remain unsupported here, despite being legitimate
  SSH transport messages. Do not claim full stock transport interoperability.
- Only AWAIT_REPLY accepts final messages: SUCCESS52 has zero payload; FAILURE51
  has methods string<=1024 and boolean byte, exact end. Accept an empty method list;
  nonempty list is printable non-whitespace ASCII tokens separated by single commas, no empty
  token. Nonzero boolean means PARTIAL, otherwise REJECTED. CHANGEREQ60 has prompt
  <=8192 and language<=128, exact end, yielding CHANGE_REQUIRED. Discard all remote
  strings, retain only enum. There is no follow-up provider or request for partial/
  change outcomes. Malformed/trailing/oversized/out-of-phase replies are terminal
  EPROTO. DISCONNECT1 parses reason u32, description<=8192 and language<=128 strings
  plus exact end, discards text and terminalizes ECONNRESET; malformed disconnect
  is EPROTO. EOF remains EPIPE. Never log remote strings or treat them as prompts.
- Valid final results stop step with later EALREADY, including negative/partial/
  change outcomes, but are not generic client-failed states. SUCCESS is published
  only after fresh proof checks and drained request. Pinned packet reading may
  enable delayed compression on SUCCESS before our parser checks it; malformed/
  early success therefore MUST terminalize, never recover. The pinned receive path
  already sets after_authentication and enables delayed compression; do not call
  ssh_packet_set_authenticated as a substitute or duplicate. This is not a complete
  compression/rekey/channel runtime claim. No manual stock auth context is added.
- Result accessor starts *out=NOT_STARTED and returns0 or-1. Null arguments EINVAL,
  closed ENOENT, failed EALREADY. Require live owner/env/role/view/deadline, PASSWORD
  selection, READY and exact current completion cycle/session digest. It performs
  no I/O/provider or client mutation; errors leave output NOT_STARTED. Permit a
  standalone selected handoff to report NOT_STARTED, without implying authentication.
  Successful close wipes new fields; callback release failure preserves retry state.

### 4. Validation & Error Matrix

| Condition | Required result |
| --- | --- |
| Begin null/closed/failed | EINVAL/ENOENT/EALREADY |
| Begin wrong owner/env | EPERM, no consumption/client mutation |
| Before READY, absent/wrong-kind selection | EACCES, no provider/attempt consumption |
| Standalone handoff consumed or auth already started/final | EALREADY, no second provider/packet |
| Pre-attempt scratch allocation failure | ENOMEM, retry allowed |
| NUL/oversized/empty credential after provider | EINVAL or existing bounded overflow, consumed/terminal, no request |
| Valid exact request, output partly written | PENDING, bounded progress, no final status |
| Valid SUCCESS / FAILURE false / FAILURE true / CHANGEREQ | SUCCESS / REJECTED / PARTIAL / CHANGE_REQUIRED, then step EALREADY |
| Early/malformed/trailing/unexpected reply or banner limit | EPROTO, terminal, no successful result |
| Cancel/view/role/deadline/proof failure | precise existing error, terminal step/begin, retryable close |
| Result accessor lifecycle/proof failure | precise error, NOT_STARTED output, no client mutation/provider |

### 5. Good / Base / Bad Cases

Good: real KNOWN/NEW encrypted peer receives exact selected Unicode username and
one synthetic password, then exact SUCCESS yields SUCCESS only after drain.
Base: old service-only/selected-byte/KEX callers remain unchanged.
Bad: assume service-ready or partial success grants channel permission; truncate a
password at NUL; retry another selected credential after rejection.

### 6. Tests Required

Use existing real synthetic OpenSSH socketpair fixture, both KNOWN and NEW/CAS,
small budgets/fragmented packets. Decrypt and assert every request field, one callback,
exact selected receipt/cycle/digest, password bytes/padding where observable, no old
provider. Cover caps1/8192/8193 and NUL, non-ASCII username, pending/early final,
valid four outcomes, malformed/trailing replies/methods/prompts/banners/limits,
EXT_INFO/rekey/unexpected/EOF/cancel/view/role/deadline, post-provider rejection with
no request, duplicate/standalone-consumed/unsupported kind and original caller stops.
Accessor owner/env/liveness/no-provider/zero-on-error and close retry need assertions.
Do not expose opaque ssh internals solely for fault injection; record source-only
branches. Run strict native, actual ARM QEMU, existing callback/JVM/lint/Release,
archive83/six-session-JNI/forbidden-stock-symbol/package checks. No Android runtime
claim follows from separate C/JVM tests.

Executed 2026-10-19: 67 new encrypted synthetic connection scenarios pass, including
the pending-banner positive that must continue output drain. Final focused7 tasks /
13s passes strict native/callback35; formal checker finds no defect and makes no
edits. Normal94 tasks /19s (23 executed/71 up-to-date) passes strict C/JNI, lint,
Release/v2, archive83/six session JNI/forbidden0. JVM221/0/0/0 is an UP-TO-DATE
retained report, not a fresh run. Final actual QEMU/Bionic785537 checks /exit0.
ENOMEM/hash/opaque proof faults, serialization-failure reset, padding/upstream wipe
and exact post-parse cancellation are source-reviewed; missing-target behavior
reuses the earlier selected-getter regression. See `design/verification.md` for
paired executable/APK hashes and explicit transport/Android coverage limits.

### 7. Wrong vs Correct

Wrong: set completed=authenticated, release credentials again after auth failure, or
call a blocking stock ssh_userauth2. Correct: explicit auth phase, exact owned
selection with one attempt, bounded packet step, proof-bound typed result and wiped
plaintext lifetime. Keep complete() as the KEX milestone and product AC open.

## Receipt-bound selected credential handoff (no USERAUTH)

### 1. Scope / Trigger

Connect the retained CSAS selection to a bounded one-shot typed native→managed
credential callback, after completed current KEX and explicit service-ready. No
USERAUTH packet, private-key parsing/signing, JavaSession secret getter, device or
real account access is added. Legacy unselected callbacks retain their contract.

### 2. Signatures

```c
int ctos_ssh_callback_credential_bytes_selected(
    ctos_ssh_callback_token, void *environment, int kind,
    const unsigned char selection_receipt[32], uint64_t cycle,
    const unsigned char session_digest[32],
    unsigned char *output, size_t capacity, size_t *length);
int ctos_ssh_native_client_selected_credential_bytes(
    struct ctos_ssh_native_client *, unsigned char *output,
    size_t capacity, size_t *length);
```

Append optional target.credential_bytes_selected with context/environment plus the
same arguments. Never fall back to credential_bytes or boolean credential.

```java
byte[] onNativeSelectedCredentialBytes(int kind, byte[] receipt, long cycle, byte[] digest);
provider.credentialBytes(role, host, kex, kind, selection, finalEffectGuard);
bridge.release(request, kex, selection, finalEffectGuard, receiver);
```

JNI looks up the unique `(I[BJ[B)[B` method, copies both32-byte metadata inputs,
and uses the existing bounded result copy/wipe discipline. Session JNI exports
remain six; there is no new Java session API returning secrets.

### 3. Contracts

- Native client derives kind/receipt from owned CSAS; caller cannot supply another
  kind or selection. Require live owner/environment/role/view/deadline, READY service,
  exactly one successful completion callback, same nonzero guard cycle and exact
  SHA256 of current raw session_id matching the digest successfully reported at KEX.
  Store that successful completion digest; check cycle/digest before and after provider.
- Invalid caller buffers are bounded-wiped and length reset. Wrong-owner/environment
  preflight EPERM does not consume or poison owner state. Unselected/pre-READY is
  EACCES without provider effects or attempt consumption. Failed/closed/repeated
  attempts are EALREADY/ENOENT/EALREADY respectively.
- Allocate bounded private scratch before consuming the attempt (ENOMEM may retry).
  Mark one-shot immediately before invoking the selected callback. Every error after
  that point consumes the attempt and terminalizes the client, with no automatic
  retry. Revalidate service/liveness/current proof after the callback and only then
  copy scratch to caller. Wipe/free scratch on all paths; output/length zero on error.
  Success does not authenticate the client or resume packet processing.
- C callback adapter reuses owner/environment/role/cancel/reentry/deadline begin/
  finish checks and bounds. Only PASSWORD=1/PRIVATE_KEY=4, receipt32 and positive
  cycle<=INT64_MAX with nonzero32 session digest are accepted. Absent new target is
  ENOSYS, never legacy downgrade; output/length are wiped/reset on target error,
  bad length, finish-time cancellation/expiry or validation error.
- Java unique native method requires frozen selection, an owned native session,
  exact SHA256 CSAS receipt, same kind and exact current host/KEX proof object plus
  cycle/session digest. Copy bounded input metadata to private scratch and wipe it.
  Native claim issuance sticky-disables the old object-selection byte overload,
  including failed opens; unbound managed compatibility remains. All selected byte
  paths share a one-shot attempt flag, consumed after validation and before provider.
  Unselected legacy password/key callbacks remain repeatable as before.
- Reuse the existing Java callback begin/finish and bounded result wiping. Selected
  native provider receives a final EffectGuard that checks current invocation/owner,
  selection/host/KEX identities and native bindings, cancel/closing/closed and clock.
  Run it in the bridge's existing final guard immediately before AppSecrets receiver
  use and repeat before callback finish. It must not re-enter vault/provider code.
  Preserve existing request/operation/role/host/KEX checks, exact resource and cleanup.
- JNI metadata arrays and secret result are wiped/deleted on success and failure;
  pending Java exceptions fail closed without payload logging. Missing selected
  method is unavailable, never a route to old callbacks. No secret cache is retained.
  Check each NewByteArray/SetByteArrayRegion immediately before making another
  JNI call that is invalid with an exception pending; clear before wipe/delete cleanup.
- Keep inCallback set through finish-time role/clock checks, then recheck cancellation
  and reentry before publishing success. Every exceptional byte-callback exit must
  wipe an unhanded result and release invocation state so owner cleanup can complete.
  Non-Linkage Error must still propagate; cleanup is not permission to swallow it.

### 4. Validation & Error Matrix

| Condition | Required result |
| --- | --- |
| Null/zero/over-limit buffer or invalid metadata/kind | EINVAL/EOVERFLOW, bounded wipe and length0 |
| Client unselected or before service READY | EACCES, no attempt/provider effect |
| Preflight wrong owner/environment | EPERM, no consumption/client mutation |
| Missing selected target | ENOSYS, no legacy call; client attempt consumed/terminal |
| Duplicate attempt | EALREADY; Java selected duplicate SSH_AUTH_SELECTION; no provider |
| Receipt/proof/kind mismatch or old selected route after native claim | Java SSH_AUTH_SELECTION or SSH_KEX_REQUIRED, no receiver effect |
| Cancel/view/expiry/current-proof change during handoff | precise failure, no returned bytes, client terminal, close retry retained |
| Valid READY selected handoff | exactly one bounded selected secret, scratch wiped, no authentication packet |

### 5. Good / Base / Bad Cases

Good: encrypted KNOWN/NEW KEX plus service accept, exact CSAS and current proof
release only selected synthetic bytes once. Base: unselected legacy managed tests
retain password then key behavior. Bad: fallback from missing selected JNI method
or trust the receipt without current KEX/service gates.

### 6. Tests Required

C callback host: selected arguments, unavailable target/no downgrade, buffer wiping,
wrong env/thread, cancel/expiry/reentry and bad lengths. ARM client: both typed CSAS,
real service-ready, premature/missing/duplicate denial, exactreceipt/cycle/digest,
private scratch/post-provider cancel/view/deadline/proof rejection and close retry.
Java: real synthetic AppSecrets selected native-route success, receipt/kind/cycle/
digest/owned-session rejection, selected object-route downgrade and one-shot,
final-guard cancellation/proof change before receiver, finish-time wipe, legacy tests.
Run JVM, host callback, strict JNI/native, actual QEMU and normal Release/package
gates. JNI execution on Android remains a separate not-run boundary.

Executed 2026-10-19: final focused JVM66/0/0/0, callback35 (selected11), strict
C/JNI and lint pass. Formal review fixed JNI immediate exception checks, finish
clock cancellation/reentry and escaping-Error invocation cleanup. Normal94 tasks /
66s freshly executes JVM221/0/0/0 (15 reports) and passes Release/v2, archive83,
six session JNI exports and forbidden stock callers0. Final host QEMU/Bionic run
passes323726 checks / exit0. Native cycle/raw-session drift and scratch ENOMEM/
hash faults are not injected; JNI exception cleanup and internal adapter result
wipe on Error are source-reviewed. Android selected JNI remains not-run. See
`design/verification.md` for paired executable/APK hashes and evidence paths.

### 7. Wrong vs Correct

Wrong: put provider bytes in caller output before post-check, or check only the
captured KEX object while adapter state can change. Correct: private scratch, exact
current adapter guard at AppSecrets effect, post-provider native proof recheck and
one selected attempt without downgrade.

## Native authentication-selection metadata transport (CSAS v1 details)

### 1. Scope / Trigger

This original metadata increment extends immutable managed selection into bounded
native session-owned metadata. It added no credential callback, private-key parser,
USERAUTH packet or authentication state; selected handoff is specified separately
above. The receipt is a SHA-256 fingerprint, never host/KEX/secret authority.

### 2. Signatures / Wire

Java callback exports the already frozen selection to session.open; no open caller
may supply an independent plan. NativeBinding keeps its old open plus a default
overload with nullable byte[] selectionWire: null delegates legacy open, non-null
throws unless explicitly implemented. Production uses one Native.open declaration
with the appended nullable array, avoiding overloaded JNI short names.

```c
/* New borrowed config fields; copied before open ownership transfer. */
const unsigned char *authentication_selection_wire;
size_t authentication_selection_wire_length;
int ctos_ssh_native_client_authentication_selection_receipt(
    const struct ctos_ssh_native_client *, unsigned char receipt[32]);
```

Java session/NativeBinding and JNI add `authenticationSelectionReceipt(handle)`;
Java session method has no handle argument and checks the returned digest against
its retained expected digest. It is an explicit read after successful open, never
a fallible post-open step disguised as an ownership-preserving open failure.

Canonical big-endian CSAS v1, with no padding/trailing bytes:

| Field | Encoding |
| --- | --- |
| Magic/version/flags | u32 0x43534153, u16 1, u16 0 |
| Exact role | u64 native role token (1..INT64_MAX), u32 hop, 32-byte roleNonce, 32-byte connectionBinding |
| Kind | u32 stable tag PASSWORD=1 or PRIVATE_KEY=4 |
| Five fields in order | each u32 byte length + bytes: username, entryId, path, keyIdentity, publicKeyBlob |

Max wire is MAX_FILE_BYTES+2048 (1,050,624 bytes). Receipt is SHA256 of the exact
complete canonical wire. No new production object/archive member is required.

### 3. Contracts

- Encoder runs on the adapter owner, after selectAuthentication and before the
  issued native-session claim; freezes the same plan and uses exact issued role
  metadata. Unselected returns null. Session clones/wipes transient wire/digest
  copies on failed or successful open, preserving existing FD/view/callback rules.
- Native checks length before reading, complete framing, magic/version/zero flags,
  exact live role token/hop/nonce/binding, stable kind and typed field shape before
  allocating/copying retained metadata and before socket ownership transfer.
- Enforce managed text bounds and strict UTF-8 scalar decoding: no overlong,
  surrogate, >U+10FFFF or ISO controls (U+0000–001F, U+007F–009F). Username1–255,
  password entry identifier1–128, private path/keyIdentity1–512; reuse view-path
  lexical validation after strict text validation. Password has empty path/key/blob;
  private key has empty entry and nonempty public blob <=MAX_FILE_BYTES.
- Retain copied canonical wire and receipt only after validation/hash success.
  New selection-admission failures consume no caller FD/callback/view. Close failure keeps metadata
  for retry; successful close wipes/frees it before tombstone destroy. Blob remains
  an expected declaration, not parsed-key proof.
- Native receipt read checks owner/environment via no-provider check_live, role,
  view and deadline; permits both pre-KEX and completed live sessions. It performs
  no I/O/provider call or client state mutation. Zero the full output on every error.
- Java read enforces owner, selected/open/nonterminal/cancel/live state before and
  after native return; allows complete KEX. Require exactly32 matching bytes; return
  caller-owned copy and wipe transient result. Mismatch/native error terminalizes
  Java session with SSH_SESSION_SELECTION; missing selection is nonterminal
  SSH_AUTH_SELECTION. Wrong owner/closed/cancel/expiry retain existing errors.
- The metadata API itself grants no credential access. The selected handoff above
  compares the native receipt against frozen managed selection and current accepted
  KEX/service state before synthetic secret release. Signing remains future work.

### 4. Validation & Error Matrix

| Native condition | Required result |
| --- | --- |
| null/zero wire | legacy unselected; non-null/zero or null/nonzero invalid |
| malformed/truncated/trailing/oversized/version/shape/UTF8 input | EINVAL, no ownership transfer |
| role token/hop/nonce/binding mismatch | EACCES, no ownership transfer |
| allocation/hash failure | ENOMEM/EIO, cleanup without ownership transfer |
| receipt NULL/client NULL, closed, failed, unselected | EINVAL, ENOENT, EALREADY, EACCES respectively; output zero when non-null |
| receipt wrong owner/environment or canceled/expired/revoked | precise existing error; zero output, no client mutation |

### 5. Good / Base / Bad Cases

Good: selected Unicode username/password or key metadata survives Java→native copy,
input mutation and KEX, with exact receipt. Base: null wire preserves legacy callers.
Bad: receipt alone authorizes a credential, or post-open digest failure is reported
as though native never consumed the socket.

### 6. Tests Required

JVM: exact framing/digest, selected open transport, non-null default-binding rejection,
transient wiping, wrong/missing/length receipt, complete-KEX read, thread/cancel/
expiry/close, legacy behavior. ARM synthetic C: both typed records, copy independence,
framing/UTF8/control/shape/role rejection and same ownership retry, no provider calls,
receipt errors/output-zero, post-KEX read and retryable cleanup. Run strict native,
actual QEMU, full JVM/lint/Release and six-session-JNI-symbol/package checks. JNI
runtime on Android is not established by these separate JVM/C tests.

### 7. Wrong vs Correct

Wrong: native retains a JNI array pointer or trusts a kind/username without role
binding. Correct: validate/copy canonical bytes before transfer, retain one digest,
and expose only a live owner-checked metadata receipt until real auth is implemented.
For new Java metadata reads, a legacy role-only session helper is insufficient:
check adapter cancellation/closing before and after the native call, including
direct adapter.cancel(), and map cancellation before a generic receipt error.

## Managed authentication selection (pre-wire contract, managed contract details)

This managed contract was introduced before CSAS. Native metadata and the separately
specified receipt-bound callback above now extend it; actual authentication remains
open.

### 1. Scope / Trigger

Before native userauth is implemented, bind an explicit wire username and exact
managed password entry or private-key selection to one live role. This slice is
Java metadata/provider integration, without C/JNI transport, authentication packets,
real accounts or credential access outside synthetic tests. The selected public
key is an expected identity declaration, not proof of a parsed private-key match.

### 2. Signatures

```java
SshAuthenticationSelection.password(role, username, entryId, clock)
SshAuthenticationSelection.privateKey(role, username, path, keyIdentity, publicKeyBlob, clock)
adapter.selectAuthentication(selection)
adapter.onNativeCredentialBytes(kind, selection) // managed overload, no new JNI method
bridge.openSelection(role, hostProof, selection)
bridge.release(request, kexProof, selection, receiver)
provider.credentialBytes(role, hostProof, kexProof, kind, selection)
selection.usernameBytes() / selection.publicKeyBlob() // defensive copies
```

The package-private immutable selection stores the exact role object, explicit
username, kind and fixed entry/path/key identity. Byte-array inputs/getters use
defensive copies. Exact selection object identity is the managed receipt; this
does not define a serialized native receipt or a persistent storage format.

### 3. Contracts

- Require the exact live role object, not just `role.binding()` (which omits its
  nonces). Existing host/KEX/vault operation guards remain mandatory before open
  and at the final AppSecrets receiver guard. Selection alone grants no authority.
- Username is explicit strict UTF-8, 1–255 bytes, no NUL/ISO controls or unpaired
  surrogate; do not parse targetIdentity, trim or normalize. Entry ID uses manifest
  identifier syntax `[A-Za-z0-9_-]{1,128}`. Reuse `VaultManifest.path` for private
  paths, plus strict UTF-8/control validation. Key identity is 1–512 UTF-8 bytes
  without controls. Expected public blob is nonempty and at most MAX_FILE_BYTES.
- Only PASSWORD and PRIVATE_KEY are selected methods. No passphrase field or
  KEY_PASSPHRASE/KEYBOARD_INTERACTIVE selected release. Manifest key-passphrase
  identities are `key:<identity>` while current vault role operations bind the
  target; extending that capability is a separate future contract.
- Select once on the owner thread before any callback begins or any native
  session claim is issued. A failed session-open attempt does not reopen selection.
  Do not replace a selection after opt-in. Setup validation failures release no
  secret and do not mutate the existing selection.
- A selected adapter requires the exact stored selection in its typed-byte
  overload; the legacy kind-only path fails closed. Unselected adapters retain
  their legacy contract. Both paths use the existing begin/finish cancellation,
  reentry, thread and cleanup rules; do not add a parallel callback state machine.
- Selected provider opens the exact request directly through bridge.openSelection,
  never through legacy CredentialRequestSource. Request stores selection once;
  validate its exact identity, kind/entry/path/key identity at the final guard.
  Retain existing bounded secret copy, wiping and one-shot request close behavior.
  The legacy release overload must reject a selected Request and still close it;
  a legacy Request cannot be upgraded merely by passing a selection receipt.
- Public-key blob remains only expected metadata. Native parsing/equal_public and
  actual username wire use remain future work. Receipt-bound callbacks and service/
  current-cycle enforcement belong to the separate handoff contract above.

### 4. Validation & Error Matrix

| Condition | Required result |
| --- | --- |
| Invalid/unsupported selection, different role/selection/kind, duplicate or late opt-in | SSH_AUTH_SELECTION, no credential release |
| Wrong owner during selection setup | SSH_CALLBACK_THREAD, no selection mutation |
| Closed/canceled/closing adapter | Existing lifecycle error, no selection/release |
| Revoked view or expired role | Existing SSH_FILE_VIEW / SSH_ROLE_EXPIRED |
| Missing/wrong host or KEX proof | Existing proof error before request open/release |
| Selected adapter called by legacy kind-only JNI callback | Reject; no fallback to CredentialRequestSource |
| Matching synthetic selection + live proofs | Exactly selected bounded bytes, final guarded release, request closed and transient copies wiped |

### 5. Good / Base / Bad Cases

Good: explicit Unicode username and exact password entry survive metadata copying;
only that entry is released after matching proofs. Base: unselected legacy provider
tests retain their contract. Bad: infer username from targetIdentity, accept a
same-fields replacement receipt, or claim expected public bytes prove private-key identity.

### 6. Tests Required

Test UTF-8 byte boundaries/malformed surrogates/control rejection and no normalization;
public byte defensive copying; password/private-key request selection; same-fields
different role/receipt rejection; no legacy-source invocation in selected mode;
one-shot/late/failed-session-open/thread restrictions; missing proofs, expiry,
revocation, cancel/reentry and final-guard races with no receiver release; request
close/copy wiping. Run JVM tests, lint and production Release/native packaging checks.
Do not report a new Android/JNI/native auth runtime result from JVM tests.

### 7. Wrong vs Correct

Wrong: a native credential kind can choose a new entry each time, or a username is
recovered from an opaque target string. Correct: an explicit immutable per-role
selection is frozen before callbacks, selected release opens only its exact request,
and future native auth must validate that same selection against actual wire/key use.

## Post-KEX userauth service negotiation (C only)

### 1. Scope / Trigger

Explicitly extend a successfully completed initial KEX into fixed ssh-userauth
service negotiation using the same native owner/socket. Java/JNI remains KEX-only;
no username, credential, USERAUTH_REQUEST, channel or authenticated milestone.

### 2. Signatures

```c
int ctos_ssh_native_client_begin_userauth_service(struct ctos_ssh_native_client *);
int ctos_ssh_native_client_userauth_service_ready(const struct ctos_ssh_native_client *);
int ctos_ssh_callback_check_live(ctos_ssh_callback_token, void *environment);
```

### 3. Contracts

- `complete()` keeps the initial KEX milestone. Old callers stop with EALREADY.
  Only explicit begin enables bounded step progress toward service-ready.
- Validate live callback owner/environment, role/view/deadline and the exact
  successful KEX callback/current guard cycle before queuing the one fixed request.
  check_live reuses callback validation without invoking host/KEX/credential targets;
  it is a liveness check, never provider availability or secret authority.
- Distinguish KEX-only, request pending, await accept and ready; request output must
  drain before accept. Use upstream sshpkt/ssh_api and retain step accounting.
- Before parsing service traffic, disable KEX 20–49 dispatch so new KEX/NEWKEYS
  cannot be silently processed with old authorization. Reject rekey in this phase.
- Accept missing payload or an empty string for legacy empty-reply compatibility;
  any nonempty service string must exactly match ssh-userauth, with no trailing
  bytes. This string match is intentionally stricter than stock's arbitrary string.
- Ready stops the C slice: later begin/step EALREADY; late duplicate replies are
  not parsed and cannot grant a second milestone. EXT_INFO uses the upstream handler.
- Keep close/destroy, original FD/view/callback and candidate ownership unchanged.

### 4. Validation & Error Matrix

| Condition | Required result |
| --- | --- |
| NULL / closed / failed-or-already-started | EINVAL / ENOENT / EALREADY |
| begin before valid completed KEX | EACCES, no queued request, current KEX remains usable |
| Wrong callback owner thread or environment | EPERM, no transport/state mutation |
| Canceled/stale role or callback, expired deadline, revoked view | precise cancellation/expiry/view error, terminal; close retry remains possible |
| Current KEX cycle differs from the one proved | EACCES, terminal, no request |
| Accepted exact or legacy-empty SERVICE_ACCEPT after request drain | ready, no USERAUTH_REQUEST or credential callback |
| Wrong/truncated/trailing service payload, early accept, USERAUTH packet or rekey | EPROTO, terminal; later step EALREADY |

### 5. Good / Base / Bad Cases

Good: peer decrypts one service request after real KNOWN or NEW/CAS KEX and the
client reaches ready from fragmented encrypted accept. Base: no opt-in, old KEX-only
behavior persists. Bad: callback kind/proof alone is treated as user authentication.

### 6. Tests Required

Keep old KEX-only tests. Test both host policies, exact/missing/empty accepted payloads,
one request, no credential calls, malformed/out-of-phase/rekey packets, premature and
duplicate begin, liveness/owner/expiry/revoke/cancel and retryable close. Verify actual
ARM QEMU execution separately from cross-link and normal build/JVM/lint/package gates.
No Android/JNI service runtime claim follows from the C emulator gate.
Current-cycle mismatch is source-reviewed without a direct mutation test: keep
the ssh/guard pointer opaque rather than exposing mutable production state just
to reach that branch. Record this coverage limit with the runtime result.

### 7. Wrong vs Correct

Wrong: delete the completed guard, call credential to probe liveness, or silently
allow KEX dispatch while reusing old proof. Correct: explicit bounded phase transition,
no-secret check_live, exact current-cycle validation and fail-closed service-only parser.

## ARM runtime: fragmented identification is atomic

### 1. Scope / Trigger

Production uses pinned OpenSSH 10.6p1 `ssh_api` on a nonblocking socket. A partial
banner must not become a published KEX version or binary packet input. Apply the
project's fixed-hash `openssh-10.6p1-banner-atomic.patch` only to a scratch source
copy; the immutable G1 baseline remains unchanged.

The separate native ARM regression may run on a host QEMU with a recorded Bionic
runtime even when hako lacks QEMU. Keep emulator evidence separate from Android
instrumentation, and record executable/runtime hashes and the actual exit status.

### 2. Signatures

`_ssh_read_banner(struct ssh *, struct sshbuf *)` and `ssh_packet_next` keep their
pinned upstream signatures. `compileSshProductionObjects` takes the new patch as
an explicit Gradle input; the production builder verifies its SHA256 before use.

### 3. Contracts

If input ends before the banner newline, reset the version buffer before the
incomplete return and leave input unconsumed for the next turn. Preserve upstream
version, length and pre-banner checks. Compile patched `ssh_api.c` to PIC and
replace `ssh_api.o` in the existing 83-member archive; record patch/source/object
hashes. Do not add a parallel banner parser or change the fixture to avoid splits.

### 4. Validation & Error Matrix

| Condition | Required result |
| --- | --- |
| Partial identification, including a trailing CR | zero published remote version, input retained, no early binary parsing |
| Repeated call without more input | same incomplete state, no invented progress |
| Final LF completes valid identification | exact remote version published; input consumed once; normal KEX may begin |
| Patch hash drift or patch failure | production builder fails before archive/APK link |
| Host sandbox denies `getsockopt(SO_TYPE)` | environment failure; no code/test weakening; use reviewed execution permission for the same local synthetic binary |

### 5. Good / Base / Bad Cases

Good: one-byte client and server identification fragments remain incomplete until
LF, then the real fragmented KNOWN and NEW/CAS KEX regressions run. Base: strict
cross-link only, runtime not-run. Bad: nonempty partial version is treated as a
complete banner, or larger test fragments hide the defect.

### 6. Tests Required

Run direct client/server fragmentation assertions and the entire production
native synthetic suite under the recorded ARM runtime, with an external timeout
and core dumps disabled. Check admission, host evidence/CAS, KEX digest ordering,
I/O, deadline, terminal state and retryable cleanup; retain the normal production
build/symbol/archive and JVM/lint gates. Android JNI acceptance remains separate.

### 7. Wrong vs Correct

Wrong: return incomplete while `sshbuf_len(remote_version) != 0`. Correct: call
`sshbuf_reset(banner)` before that return, retaining unconsumed input for retry.

## G3.8 Android ARM/JNI KNOWN host-proof boundary

### 1. Scope / Trigger

- Trigger: the production Java/JNI session bridge now needs one real ARM
  execution point for the already host-tested `KNOWN` verifier.
- Scope: the opt-in
  `SshNativeSessionPlatformTest#syntheticNativeSessionHandle` fixture only.
  It opens the production native session over an owned local socketpair, calls
  one bounded `step` against an empty peer, verifies one generated matching key, then
  exercises the existing revoke and close cleanup. It does not complete KEX, userauth, a network endpoint,
  a real vault, or credentials.

The 2026-10-17 extension keeps this exact opt-in method and adds two independent
production sessions: fragmented server identification / initial KEXINIT output,
then peer half-close / terminal cleanup. Both use a 3-byte step budget and a
nonblocking socketpair peer; networking and full KEX are outside this gate.

### 2. Signatures

- Fixture helper: `view(long deadline, byte[] hostKeyBlob)` creates the
  managed `ssh/known_hosts` body from the same bounded SSH wire blob passed to
  `SshNativeSession.open`.
- Production call: `SshNativeSession.verifyHostKey(byte[] hostKeyBlob)`
  invokes `Native.verifyHostKey` on the owner thread; the native bridge then
  calls `ctos_ssh_native_client_verify_host_key`.
- Production step: `SshNativeSession.step() -> StepResult` invokes
  `Native.step` on the same owner thread and returns bounded scalar I/O flags.
- Device command must target only the current authorized board and the exact
  opt-in method:
  `adb -s 192.168.9.14:5555 shell am instrument -w -r -e
  class im.majo.ctos.SshNativeSessionPlatformTest#syntheticNativeSessionHandle
  -e ctosSshNativeSessionPlatformProbe true
  im.majo.ctos.secretsfixture.test/androidx.test.runner.AndroidJUnitRunner`.

### 3. Contracts

- The managed line is exactly
  `127.0.0.1 ssh-ed25519 <Base64(no-wrap, hostKeyBlob)>\n`; the blob is
  the OpenSSH wire-encoded public key, not a Java object or a fingerprint
  string. The key remains alive until the view is created, verification
  returns, and the final cleanup.
- `verifyHostKey` must run before `view.revoke()`. A successful call is
  evidence that the production Java → JNI → native host-file issuer → KNOWN
  callback path accepted the matching key. Revoke, native close, callback
  release, view release, and socket cleanup remain the existing owner order.
- Before verification, an empty nonblocking peer must yield `bytesRead == 0`,
  `inputAppended == 0` and `readWouldBlock == true`. The fixture deliberately
  does not require `bytesWritten == 0`, because the same bounded turn may emit the
  client identification/banner output.
- The fixture is synthetic: socketpair only, no DNS/LAN/SSH server, account,
  password, private key, or AppSecrets mutation.
- The fragmented transport loop is bounded to 2000 turns. Each read/write is
  at most the step budget; `inputAppended == bytesRead`, and accumulated
  `bytesWritten` must equal the bytes actually read from the peer. Java exposes
  no `outputConsumed` field, so do not invent that assertion. Parse the complete
  bounded initial SSH packet, including padding and message type 20 (KEXINIT).
- `Os.shutdown(peer, SHUT_WR)` triggers EOF in a separate session. JNI throws a
  generic `IOException`, which public `SshNativeSession.step()` maps to
  `VaultException` code `SSH_SESSION_STEP`; the next public step keeps that code.
  This does not expose C `EPIPE` / `EALREADY` or an IOException cause to Java.
  The view stays live until owner close; close releases it and is idempotent.

### 4. Validation & Error Matrix

| Condition | Required result |
| --- | --- |
| Exact wire blob is formatted into managed known_hosts | production `verifyHostKey` returns normally and the instrumentation test passes |
| Empty peer is stepped once through production JNI | `bytesRead=0`, `inputAppended=0`, `readWouldBlock=true`; any bounded write remains implementation output |
| Fragmented server identification with budget 3 | multiple read turns; bounded scalar accounting; actual peer output contains client identification plus complete initial KEXINIT |
| Peer half-close then two public steps | both throw `SSH_SESSION_STEP`; view remains live until close, close releases view and native tombstone |
| Old `AAAA`/malformed placeholder is retained | host-file issuer rejects the record; instrumentation fails rather than proving KNOWN |
| Host key is wiped before view creation or verification | fixture must not claim a host-proof pass; build/runtime failure is visible |
| Revoke follows successful verification | existing native borrow/revoke/close cleanup remains observable |
| Device package/process cleanup after the test | both owned fixture packages are absent; no process remains |
| ActivityManager rejects startup and user is `RUNNING_LOCKED` | runtime not-run, no assertion pass/fail; user manually unlocks before a newly authorized retry |

### 5. Good / Base / Bad Cases

- Good: a generated wire blob is Base64-encoded once into the managed line,
  production `verifyHostKey` succeeds on ARM, and both fixture packages are
  removed afterward.
- Base: host/JNI compile, lint, and APK packaging pass without device runtime.
- Bad: a host-only counter, test callback, fake `KNOWN` result, or a stale
  historical device result is used instead of the production call.

### 6. Tests Required

- Compile the fixture test Java source and run fixture lint.
- Run one production `step()` against the empty peer and assert only the stable
  bounded read/append/EAGAIN fields.
- Exercise fragmented identification / initial KEXINIT output with actual
  nonblocking peer readback, then independently exercise EOF / terminal cleanup.
- Install only the freshly built fixture main/test APKs on
  `192.168.9.14:5555`, run the exact opt-in method, and assert
  `OK (1 test)` / instrumentation status 0.
- Uninstall both owned fixture packages and read back `pm path`/process
  absence. Record the fixture APK hashes and board identity.
- Bound the host wait for `am instrument -w` (for example, `timeout 90s adb ...`):
  rejected process startup can otherwise leave the shell command waiting forever.
  A successful APK install is not a runtime pass. For `RUNNING_LOCKED`, retain
  local results and clean the owned packages / command process; never bypass the
  gate by changing lockscreen settings or marking the fixture direct-boot aware.
- Keep the host cross-link and normal APK evidence separate: this runtime
  result proves JNI synthetic transport / KNOWN proof / EOF cleanup. The
  2026-10-18 rebuilt fixture passed on API30 arm64; full Android KEX remains open.

### 7. Wrong vs Correct

#### Wrong

`"127.0.0.1 ssh-ed25519 AAAA\n"` plus a direct Java callback assertion.

#### Correct

`HOST + " ssh-ed25519 " + Base64.getEncoder().encodeToString(hostKeyBlob)
+ "\n"`, followed by `session.verifyHostKey(hostKeyBlob)` before revoke.

For EOF, assert public `VaultException.code == "SSH_SESSION_STEP"` twice and
successful close. Claiming Java directly observed `EPIPE` or `EALREADY` is wrong;
those errno assertions belong to the native C regression.

## G3.8: bounded step, terminal/error, and host-evidence classification semantics

### 1. Scope / Trigger

- Trigger: the native-owned session bridge exposes a real production `step`,
  and its first host regression must distinguish an expired managed view from
  a revoked/invalid view without hiding the original terminal error.
- Scope: `ctos_ssh_native_client_step` and its synthetic host socketpair tests.
  This is a narrow production error-propagation and regression slice; it does
  not authorize a real endpoint, credentials, vault mutation, or full SSH
  protocol claim.

### 2. Contract

- A live-borrow failure is terminal. Preserve its source errno: an expired
  managed view/deadline remains `ETIMEDOUT`; revoke, invalidity, or an unknown
  view remains `EACCES` (or the precise lower-level error). Later `step` calls
  return `EALREADY` until the owner closes the client.
- A nonblocking empty peer produces an ordinary successful step with
  `read_would_block=1`, zero `bytes_read`, and zero `input_appended`. It must
  not fabricate input progress or a terminal error.
- A peer read of zero bytes is terminal `EPIPE` with zero read/input progress;
  cancellation and expired client deadlines remain terminal errors. Cleanup
  remains retryable and owns the peer close exactly once in the fixture.
- Once `ssh_packet_next` completes a fragmented peer identification banner, the
  same step may queue the native identification banner and KEX output. The
  transport write is capped at `step_budget`; `bytes_written` must equal
  `output_consumed`, and `partial_write=1` means OpenSSH output remains queued
  after that bounded write. Peer output is observed only through the attached
  synthetic socket, never through the rejected application byte-pipe fields.
- `ctos_ssh_native_client_verify_host_key` must be covered against a synthetic
  managed `ssh/known_hosts` object containing the exact generated public key.
  The production host-file issuer must classify that key as `KNOWN`; the
  synchronous host-evidence callback must return the complete role/identity,
  key/fingerprint, digest, approved, and `CAS_NOT_REQUIRED` result; and the
  production API must return success. A different public key blob must fail
  with `EACCES` at the pinned-fingerprint guard without invoking host evidence
  a second time.
- A second host-policy case must keep the offered key's pinned fingerprint
  valid while putting a different generated key for the same host in the
  managed object. The issuer must be directly observed returning
  `CTOS_SSH_CALLBACK_HOST_CHANGED`; production verification must then fail
  closed with `EACCES` without invoking host evidence or handing off a
  candidate. This direct issuer assertion prevents an unrelated early guard
  from satisfying the classification regression.
- A revoked host-policy case must prepend `@revoked` to the matching managed
  key entry. The issuer must be directly observed returning
  `CTOS_SSH_CALLBACK_HOST_REVOKED` with the offered key/fingerprint and equal
  base/replacement digests, without candidate handoff; production verification
  must fail closed with `EACCES` before host evidence is invoked.
- A valid offered OpenSSH host certificate is a separate project policy result:
  the issuer must directly return `CTOS_SSH_CALLBACK_HOST_CERTIFICATE` with
  bounded key/fingerprint and equal base/replacement digests, without candidate
  handoff; production verification must fail closed with `EACCES` before host
  evidence. A certificate or CA marked `@revoked` remains `HOST_REVOKED` before
  the generic certificate policy result.
- `CTOS_SSH_CALLBACK_HOST_INVALID` is project-owned, not an invented upstream
  `HostStatus` value. Before calling `load_hostkeys_file()`, the in-memory body
  is scanned with pinned `hostkeys_foreach_file(..., HKF_WANT_PARSE_KEY, ...)`.
  Any `HKF_STATUS_INVALID` line returns `HOST_INVALID` with the offered
  key/fingerprint, equal base/replacement digests, no replacement body and no
  candidate handoff. A syntactically valid body with no matching key remains
  upstream `HOST_NEW`. Production verification rejects `HOST_INVALID` with
  `EACCES` before host evidence.
- A syntactically valid managed body with no matching key is a real `NEW` path,
  not an early rejection. The issuer appends exactly one upstream-formatted
  entry to the bounded replacement body, hands that body off once with the
  exact candidate ID/key/fingerprint/base/replacement digests, and only then
  may production verification ask for host evidence. A successful `NEW`
  evidence result must be approved with `CAS_COMMITTED`; `CAS_NOT_REQUIRED` is
  invalid for `NEW`.
- If `NEW` host evidence or any later host-result binding check fails, the
  native verifier aborts the handed-off candidate exactly once, terminalizes
  the client, and preserves close retryability. A later `step` returns
  `EALREADY`; it must not make transport progress or invoke a second candidate
  or host-evidence callback. A successful verifier call keeps the candidate
  committed for the subsequent protocol completion/close contract.
- A synthetic OpenSSH client/server socketpair must drive the production client
  through the real KEX completion boundary. The matching managed `KNOWN` key
  produces exactly one host-evidence callback before exactly one KEX-complete
  callback; the latter carries a nonzero cycle and nonzero SHA-256 session
  digest matching the peer's session ID. Completion sets the client complete,
  and a later production `step` returns `EALREADY`.
- The same boundary must be exercised with an empty managed `known_hosts` body.
  The real host-key callback must emit exactly one candidate, then exactly one
  `NEW` evidence result with `CAS_COMMITTED`, before exactly one KEX-complete
  callback. Candidate/evidence route fields, replacement body and both digests
  remain bound to the same role/host/key; completion and post-completion
  `EALREADY` semantics are unchanged.

### 3. Validation / Error Matrix

| Condition | Required result |
| --- | --- |
| Empty nonblocking peer | `0`; `read_would_block=1`; zero read/input progress |
| Managed view expired before step | `-1`, `ETIMEDOUT`; client terminal; zero progress |
| Managed view revoked/invalid before step | `-1`, `EACCES` or exact lower-level error; client terminal |
| Peer EOF after successful open | `-1`, `EPIPE`; later step `-1`, `EALREADY` |
| Fragmented peer banner reaches a complete identification | each step reads/appends at most `step_budget`; output write is at most `step_budget`; `bytes_written == output_consumed`; pending banner/KEX output sets `partial_write=1` |
| Offered pinned key differs from managed known_hosts key | issuer classification is `CHANGED`; production verifier returns `EACCES`; no host-evidence callback or candidate handoff |
| Matching managed key is marked `@revoked` | issuer classification is `REVOKED` with bounded key/fingerprint/digest metadata; production verifier returns `EACCES`; no host-evidence callback or candidate handoff |
| Valid offered host certificate | issuer classification is `CERTIFICATE` with bounded metadata and no handoff; production verifier returns `EACCES`; no host-evidence callback |
| Managed known_hosts body contains a parser-invalid line | project-owned issuer classification is `INVALID` with equal digests and no handoff; production verifier returns `EACCES`; no host-evidence callback |
| Valid managed body has no offered-key match | issuer classification is `NEW`; one exact candidate handoff precedes one approved `NEW` evidence result with `CAS_COMMITTED`; replacement digest binds the exact body |
| `NEW` evidence rejects or result binding mismatches | candidate abort exactly once; verifier fails closed and terminalizes client; later `step` returns `EALREADY`; close remains retryable |
| Synthetic client/server KEX reaches `kex->done` | one `KNOWN` evidence callback precedes one KEX-complete callback; cycle and session digest are nonzero and digest-bound; client completes; later `step` returns `EALREADY` |
| Synthetic client/server KEX with empty managed known_hosts | one candidate precedes one approved `NEW` evidence result with `CAS_COMMITTED`, which precedes one KEX-complete callback; exact body/digests and route fields bind; later `step` returns `EALREADY` |

### 4. Tests Required

- `testSshNativeClientHost` must compile the production caller with strict
  C11/Werror flags and include empty-peer EAGAIN, half-close EOF, and bounded
  BOOTTIME deadline cases using only the existing synthetic socketpair.
- `test_step_fragmented_banner_output` must feed a synthetic server banner in
  bounded fragments, observe the production client's local banner plus KEX
  bytes on the peer, and assert bounded input/output accounting and
  `partial_write`; this is a cross-link regression until an ARM runtime is
  available.
- `test_verify_known_host_key_proof` must format the generated key into the
  synthetic managed object, assert one successful `KNOWN` evidence callback,
  then submit a different generated public key and assert `EACCES` with no
  additional callback. The callback result must be populated from the
  evidence rather than accepted by a tautological counter assertion.
- `test_verify_changed_host_key_classification` must use a different managed
  key while retaining a valid offered-key fingerprint, directly assert the
  issuer's `CHANGED` classification and bounded issue metadata, then assert
  production `EACCES` with zero host-evidence calls.
- `test_verify_revoked_host_key_classification` must mark the matching managed
  entry `@revoked`, directly assert the issuer's `REVOKED` classification and
  bounded issue metadata, then assert production `EACCES` before host evidence
  with no candidate handoff.
- `test_verify_certificate_host_key_classification` must use a generated,
  signed host certificate, directly assert `CERTIFICATE` metadata/no handoff,
  then assert production `EACCES` before host evidence.
- `test_verify_invalid_hostfile_classification` must combine a truncated
  managed record with a valid offered-key record, directly assert project-owned
  `INVALID` metadata/no handoff, then assert production `EACCES` before host
  evidence. The test must not claim that upstream `HostStatus` has an
  `INVALID` member.
- `test_verify_new_host_key_candidate` must use an empty managed body and assert
  the exact replacement body, candidate ID/classification/key/fingerprint,
  candidate-before-evidence ordering, base/replacement digest binding, and
  `CAS_COMMITTED` before production verification succeeds.
- `test_verify_new_host_key_candidate_rollback` must reject host evidence after
  the candidate handoff, assert one abort and no duplicate callbacks, then
  assert `step` is terminal (`EALREADY`) and native close can still retry.
- `test_kex_completion_host_proof` must pump a synthetic nonblocking OpenSSH
  server over the socketpair until both client completion and peer `kex->done`;
  it must assert bounded partial I/O, exactly one `KNOWN` evidence callback,
  evidence-before-KEX-complete ordering, `cycle == 1`, a nonzero callback digest
  matching the peer session ID, and post-completion `EALREADY`.
- `test_kex_completion_new_host_proof` must repeat that packet loop with an
  empty managed body and assert candidate → `NEW` evidence (`CAS_COMMITTED`) →
  KEX-complete exactly-once ordering, exact replacement/body/digest/route
  binding, peer-bound session digest, bounded I/O/EAGAIN/short write and
  post-completion `EALREADY`.
- The host task is an AArch64 cross-link. Without qemu, report
  `runtime=not-run`; compilation is not an ARM runtime result.
- A future protocol gate still needs broader packet/KEX exchange and runtime
  behavior, complete managed-CAS/provider runtime and ARM host-proof
  runtime, KEX completion, auth, JNI/Java runtime and device evidence; these
  host-only regressions are not that gate.

## G3.7: native-owned session handle, view borrow, and JNI runtime seam

### 1. Scope / Trigger

- Trigger: replace the G3.6 fail-closed JNI placeholder only after native code
  can own the parsed host key and the managed public view for the complete
  native session lifetime.
- Scope: `ssh_view_handle.[ch]`, production `ssh_native_client`,
  `ssh_session_jni.c`, `SshPublicFileLease` revoke/retry behavior, host
  handle tests, and the explicitly opted-in synthetic Android session test.
- This section authorizes no real SSH account, credential, vault mutation,
  external endpoint, or claim that the full OpenSSH protocol is complete.

### 2. Signatures

Java production binding:

```java
Native.open(long role, int ownerUid, long callback, long view, int socketFd,
        long deadline, int stepBudget, byte[] hostKeyBlob,
        byte[] expectedFingerprint, String knownHostsPath, String host,
        String candidateId) -> long;
Native.step(long handle, boolean cancelled) -> int[8];
Native.verifyHostKey(long handle, byte[] hostKeyBlob) -> void;
Native.close(long handle) -> void;
Native.destroy(long handle) -> void;
```

Native view/session boundary:

```c
int ctos_ssh_view_handle_create(const unsigned char *, size_t,
    ctos_ssh_view_token *);
int ctos_ssh_view_handle_summary(ctos_ssh_view_token,
    struct ctos_ssh_view_summary *);
int ctos_ssh_view_handle_destroy(ctos_ssh_view_token);
int ctos_ssh_view_handle_borrow(ctos_ssh_view_token,
    struct ctos_ssh_view_borrow **);
int ctos_ssh_view_borrow_live(const struct ctos_ssh_view_borrow *);
int ctos_ssh_view_borrow_release(struct ctos_ssh_view_borrow *);

int ctos_ssh_native_client_open(
    const struct ctos_ssh_native_client_config *,
    struct ctos_ssh_native_client **);
int ctos_ssh_native_client_step(
    struct ctos_ssh_native_client *,
    struct ctos_ssh_native_client_step *);
int ctos_ssh_native_client_verify_host_key(
    struct ctos_ssh_native_client *, const void *, size_t);
int ctos_ssh_native_client_close(struct ctos_ssh_native_client *);
int ctos_ssh_native_client_destroy(struct ctos_ssh_native_client *);
```

The native config includes a caller-owned `ctos_ssh_view_borrow *`, bounded
host-key blob/length, and a 32-byte expected fingerprint. A positive Java
`long` is an opaque registry token, never an address or `struct sshkey *`.

### 3. Contracts

- `ctos_ssh_view_handle_create` transfers the validated wire into a native
  registry. `borrow` increments the handle lifetime; `destroy` is idempotent
  revoke, blocks new borrows, and defers final `ctos_ssh_view` destruction
  until the last borrow releases. Stale tokens do not identify a new view.
- Native open validates the live role, descriptor, view slices/root/reference,
  deadline and bounded text before OpenSSH setup. It parses the supplied host
  key into a native-owned `struct sshkey`, checks the exact SHA-256 fingerprint,
  and owns the parsed key plus view borrow only after successful open. On
  failure, the caller retains the original FD and borrow; JNI releases its
  temporary borrow and copied inputs.
- A successful session owns the attached `F_DUPFD_CLOEXEC` socket, original
  caller FD, parsed host key, and managed-view borrow. Java arrays/strings are
  copied into native bounded storage and wiped after the binding returns.
- JNI session slots are monotonic opaque tokens with an owner `pthread_t`.
  Every open/step/verify/receipt/close/destroy call checks the owner thread; Java
  cancellation remains the only cross-thread request. The native bridge may
  synchronously invoke the callback while the owner call is active; callback
  code must not re-enter the session JNI registry.
- Native close is event-loop ordered and retryable:
  candidate rollback if needed → callback close → KEX unbind/OpenSSH free →
  managed-view borrow release → parsed host-key free → original FD close.
  Any failed stage remains retryable. Destroy accepts only a closed tombstone;
  Java then completes claim/role/view bookkeeping according to its staged
  owner contract.
- `step` returns exactly eight bounded scalar values. Its live-borrow check
  preserves `ETIMEDOUT` versus `EACCES` and terminalizes the client before the
  transport turn. This G3.7/G3.8 gate covers handle/lifetime plumbing and
  bounded terminal/error cases; it does not turn the socket turn into a
  complete protocol/auth gate.

### 4. Validation / Error Matrix

| Condition | Required result |
| --- | --- |
| Non-positive/stale/revoked view token | borrow fails; no client is opened |
| Revoke with active borrow | new borrow fails; existing borrow may be released; final view destruction is deferred |
| Invalid wire, view slices, role, deadline, descriptor, or bounded key input | `-1`; caller FD and caller borrow remain available; JNI throws `IOException` |
| Malformed host-key blob | `-1`/`EINVAL`; parsed key is not retained |
| Fingerprint mismatch | `-1`/`EACCES`; no OpenSSH attach and no FD consumption |
| Successful native open | native owns parsed key, original/attached FD, callback lifetime and view borrow |
| Wrong JNI owner thread or unknown session token | no C client call; `IOException` |
| Callback, view-borrow, or original-FD close failure | return failure; retain retryable session state |
| Destroy before native close | `-1`/`EBUSY`; slot and resources remain |
| AArch64 cross-link without qemu/device protocol coverage | record `runtime=not-run`; never report full Android SSH pass |

### 5. Good / Base / Bad

- Good: native token is opaque; revoke prevents new borrow while an existing
  session safely finishes; malformed/mismatched host keys fail before attach;
  close can retry; Android synthetic test passes and removes its fixture.
- Base: the original G3.7 APK exposed five JNI symbols; CSAS now adds the sixth
  receipt entry. The historical lifecycle-only device test proves only its
  socketpair path; current independent ARM/JVM evidence is recorded above.
- Bad: cast a Java `long` to `ctos_ssh_view *`, keep a borrowed Java array as
  the parsed key, release the view before native close, destroy an open slot,
  or infer userauth/host-proof/EOF/PTY success from the lifecycle test.

### 6. Tests Required

- `testSshViewHandleHost`: create/summary, borrow, revoke, stale token,
  deferred final destruction and replacement-token separation.
- `testSshNativeClientHost`: valid host-key blob parsing/ownership,
  fingerprint admission, managed-view borrow, close order and malformed/error
  paths; strict native warnings remain enabled.
- `SshNativeSessionPlatformTest#syntheticNativeSessionHandle`: exact opt-in
  instrumentation method on the authorized `.14` fixture; use synthetic
  socketpair and cleanup assertions only.
- `SshFileViewPlatformTest#syntheticProtectedFileViews`: retain the existing
  public-file view matrix and run it separately from the new session method.
- Normal gate must retain the full current JUnit/callback/native regression suite,
  production archive=83, six session JNI exports, fixture exclusion, Release ABIs, lint and
  APK scans. A future protocol gate must add partial I/O/EAGAIN/EOF/deadline,
  host proof, auth and close-retry evidence before claiming full runtime.

### 7. Wrong vs Correct

#### Wrong

```c
struct ctos_ssh_view *view = (struct ctos_ssh_view *)(uintptr_t)view_token;
client->server_host_public_key = (struct sshkey *)java_array;
```

#### Correct

```text
opaque Java token -> native registry -> borrow
bounded Java blob -> sshkey_from_blob -> native-owned sshkey
revoke -> block new borrows -> native close releases borrow -> destroy tombstone
```

## G3.6: Java/JNI session-owner fail-closed seam

### 1. Scope / Trigger

- Trigger: add the Java owner contract above the G3.5 same-process native C
  boundary without pretending that Java opaque tokens are borrowed C pointers.
- Scope: `SshNativeSession`, its callback claim, the public-view token access,
  normal JNI symbol closure, and JVM ownership tests. This section does not
  authorize Android runtime, device, credentials, or external SSH.
- The current JNI implementation is intentionally a fail-closed placeholder:
  every production entrypoint throws `java.io.IOException` until the native
  handle/borrow contract below is implemented.

### 2. Signatures

Java owner seam:

```java
static SshNativeSession open(
    SshNativeCallbackAdapter callback,
    SshPublicFileLease view,
    int socketFd,
    byte[] hostKeyBlob,
    byte[] expectedFingerprint,
    String candidateId,
    int stepBudget) throws VaultException;

void cancel() throws VaultException; // the only cross-thread operation
StepResult step() throws VaultException; // owner thread only
void verifyHostKey(byte[] hostKeyBlob) throws VaultException; // owner thread only
byte[] authenticationSelectionReceipt() throws VaultException; // selected/live owner; allows KEX complete
void close() throws VaultException; // owner thread only, retryable
```

JNI symbols in `ssh_session_jni.c`:

```text
Native.open(...) -> long
Native.step(...) -> int[8]
Native.verifyHostKey(...) -> void
Native.authenticationSelectionReceipt(...) -> byte[32]
Native.close(...) -> void
Native.destroy(...) -> void
```

The original G3.6 implementation made its five methods fail closed with
`IOException`; that historical state is superseded by the native-owned bridge
and CSAS contracts above. `NativeSessionClaim` is the package-private
capability pairing one callback adapter with one opening/owned session.

### 3. Contracts

- The thread that opens the callback is the session owner. Only it may call
  native open, step, host-key verification, close, or destroy. `cancel` may be
  requested by another thread; it sets cancellation and the owner thread
  passes it into the next native step.
- `beginNativeSessionOpen()` rejects closed, closing, cancelled, wrong-thread,
  expired, or already-opening/owned bindings. A successful begin creates one
  `NativeSessionClaim`; success changes it to owned, while every failed open
  clears it.
- Open validates the live role, active exact public view, socket and bounded
  inputs before calling the binding. The native binding receives cloned host-key
  and fingerprint arrays; Java wipes both in `finally`. A failed binding open
  must leave the caller's original FD, callback and view available.
- Close is staged and retryable:
  `nativeBinding.close` (native C callback/KEX/socket close) →
  `callback.nativeClientClosed(claim)` bookkeeping →
  `callback.releaseRoleAfterNativeClient()` → `view.close()` →
  `nativeBinding.destroy()`. A completed stage is not repeated; an uncompleted
  stage remains pending after an exception.
- `verifyHostKey` and `step` re-check the live role/deadline and fail closed on
  cancellation, expiry, malformed result, native error or linkage failure.
  `StepResult` has exactly eight bounded scalar fields; application byte pipes
  do not cross this seam.
- Production JNI may be enabled only after native code owns a session handle,
  owns or safely clones the parsed host key, and pins a view snapshot/borrow
  until native close releases it. Java token values must never be cast to
  `struct sshkey *` or `ctos_ssh_view *` pointers.

### 4. Validation & Error Matrix

| Condition | Required result |
| --- | --- |
| Wrong thread for open/step/verify/close/destroy | `SSH_SESSION_THREAD`; no native call |
| Cross-thread cancel | callback cancellation is recorded; next owner step returns `SSH_SESSION_CANCELLED` |
| Expired/closed role or revoked view | `SSH_SESSION_EXPIRED`/typed vault error; no native step/verify |
| Second session on one callback | `SSH_CALLBACK_SESSION`; first claim remains owner |
| Failed native open | `SSH_SESSION_NATIVE`; claim is released; caller FD/callback/view remain caller-owned |
| Invalid key/fingerprint/identifier/budget | `SSH_SESSION_REQUIRED`; no native call |
| Native close or role/view/destroy failure | `SSH_SESSION_CLEANUP` or underlying typed error; completed stages remain done and later stages are retryable |
| JNI bridge sees no native-owned handle/view borrow contract | `IOException` from every JNI entrypoint; never dereference a Java token |
| AArch64 cross-link without qemu/device | record `runtime=not-run`; never report Android runtime pass |

### 5. Good / Base / Bad Cases

- Good: fake binding observes owner-thread calls, one claim, wiped key inputs,
  failed-open resource preservation, deadline/cancel rejection and ordered
  close retries; production JNI remains visibly fail-closed.
- Base: normal APK contains the five JNI symbols and links the production
  OpenSSH archive, but JVM-only session tests use a fake binding and native
  runtime is `not-run`.
- Bad: reinterpret a `long viewToken` as `ctos_ssh_view *`, let a worker thread
  call native close, release the Java role before native callback/KEX cleanup,
  or call a Java runtime pass based only on cross-link/fake binding evidence.

### 6. Tests Required

- `SshNativeSessionTest`: owner-thread enforcement, cross-thread cancellation,
  one-claim exclusivity, failed-open ownership preservation, host-key/fingerprint
  wipe, deadline before step/verify, native close retry, view failure retry and
  exact close ordering.
- `:app:testReleaseUnitTest` must report 0 skipped/failures/errors, including
  the twelve session-owner tests; `testSshCallbackAdapterHost` must retain
  callback gate=20.
- `compileSshFiles`, production source/object gate, all Release ABIs, lint and
  APK ZIP/symbol scans must show the session JNI symbols while excluding fixture
  caller objects and forbidden stock high-level symbols.
- Before replacing the fail-closed JNI methods, add an authorized Android ARM
  test that proves native handle ownership, view revoke/borrow lifetime,
  partial I/O/EAGAIN/EOF/cancel/deadline, host proof and close/destroy retry.

### 7. Wrong vs Correct

#### Wrong

```java
Native.open(roleToken, viewToken, socketFd, hostKeyBlob);
// C casts viewToken to struct ctos_ssh_view * and retains borrowed Java input.
```

#### Correct

```text
Java claim -> native-owned session handle + pinned view snapshot/borrow
           -> owner-thread native close releases callback/KEX/socket
           -> Java releases claim, role, view, then destroys tombstone
```

## G3.5: production source/object closure and native C caller

### 1. Scope / Trigger

- Trigger: `compileSshFiles` now links a real pinned OpenSSH caller and the
  hostfile/KEX provider seam into the normal APK.
- Scope: only the same-process native boundary and normal-link closure are
  implemented. `ssh_pinned_client` and other opt-in fixture callers remain
  excluded from the APK.

### 2. Signatures

```c
int ctos_ssh_native_client_open(
    const struct ctos_ssh_native_client_config *,
    struct ctos_ssh_native_client **);
int ctos_ssh_native_client_step(
    struct ctos_ssh_native_client *,
    struct ctos_ssh_native_client_step *);
int ctos_ssh_native_client_verify_host_key(
    struct ctos_ssh_native_client *, const void *opaque_host_key);
int ctos_ssh_native_client_close(struct ctos_ssh_native_client *);
int ctos_ssh_native_client_destroy(struct ctos_ssh_native_client *);
```

The fixed production builder is
`.trellis/tasks/10-08-ssh-terminal-client/research/android-production-openssh-build.sh`.
Gradle must run `compileSshProductionObjects` before `compileSshFiles` and
must consume only its generated archive/include output.

### 3. Contracts

- `config` binds live role, current PID/UID, future BOOTTIME deadline,
  `CLOEXEC|O_NONBLOCK|O_RDWR|SOCK_STREAM` descriptor, same-thread callback
  token/environment, managed view, `ssh/known_hosts` path, host/candidate
  identity, host public key/fingerprint and a step budget no larger than
  `CTOS_SSH_NATIVE_CLIENT_MAX_STEP_BYTES`.
- All admission/setup checks run before OpenSSH attaches a descriptor. A
  successful open attaches a `F_DUPFD_CLOEXEC` duplicate and the native client
  owns both the duplicate and original descriptor. A failed open leaves the
  original descriptor and pre-opened callback caller-owned. If KEX bind already
  succeeded, failed-open must unbind before freeing ssh even when descriptor
  duplication/attachment never completed; the same ownership tuple can retry.
- One `step` performs a bounded nonblocking socket read, OpenSSH input/packet
  processing, and bounded output write/consume. Non-empty application input or
  output pipes are rejected; OpenSSH owns transport bytes.
- Host verification consumes the managed public view through the pinned
  hostfile issuer, rechecks HostEvidence/CAS binding, and rolls back a handed
  off NEW candidate on later failure. KEX completion emits one session digest
  callback only, after `kex->done` and the local OpenSSH output queue is fully
  consumed. Before that drain, later bounded steps must remain available;
  only then may completion make subsequent steps return `EALREADY`.
- `close` is event-loop-owner synchronous: candidate abort, callback release,
  KEX unbind/OpenSSH free, then original descriptor close. A release/close
  failure leaves a retryable non-tombstone. `destroy` accepts only a closed
  tombstone.
- The production archive must be source-recompiled PIC, contain the complete
  pinned `libssh` member set plus PIC `ssh-pkcs11.o`/`ssh-sk-client.o`, and
  define no `ssh_connect`, `ssh_login`, `ssh_userauth2`, or `ssh_proxy_*` stock
  caller.

### 4. Validation & Error Matrix

| Condition | Required result |
| --- | --- |
| Null/malformed config, stale role/view, wrong owner/deadline, bad socket or missing trust input | `-1`; `*client == NULL`; original FD and callback remain caller-owned |
| Role admission: unknown/expired role, UID/PID/host/deadline mismatch | deterministic `EACCES`, independent of incoming `errno`; original FD retained. Clock/registry operational failures keep their actual error |
| Non-empty step input/output pipe | `-1`, `EINVAL`, zero progress |
| Cancel, expired deadline, EOF, protocol/I/O failure | terminal error; no further progress; candidate rollback if needed |
| Step after close | `-1`, `ENOENT` |
| Step after terminal completion/failure | `-1`, `EALREADY` |
| Peer NEWKEYS arrives while local output is pending | continue bounded output drain; do not publish completion or block the next step prematurely |
| Callback release failure | `-1`; client remains retryable with OpenSSH and original FD still attached |
| FD duplication fails after successful KEX bind | preserve `EMFILE`, NULL client and caller ownership; unbind before free; identical config may retry after resource recovery |
| Original-FD close failure | `-1`; client remains retryable with the original FD handle retained for another close attempt |
| Baseline hash/tree/patch drift, non-PIC or incomplete archive, stock caller symbol | production builder fails before APK link |
| Missing qemu/Android runtime | report `runtime=not-run`; never count cross-link as runtime pass |

### 5. Good / Base / Bad Cases

- Good: fixed source/tree hashes, 83-member PIC archive, explicit PKCS#11/SK
  closure, no stock high-level symbols, normal APK shared link succeeds.
- Base: AArch64 host cross-link and JVM/build gates pass; ARM execution and
  Android/JNI session orchestration remain unverified.
- Bad: copy G1 `-fPIE` objects into a shared `.so`, omit the explicit
  `ssh-pkcs11`/`ssh-sk-client` definitions, or wire `ssh_pinned_client` into
  `compileSshFiles`.

### 6. Tests Required

- Run the fixed production builder and inspect baseline/source/object/member/
  symbol evidence; assert PIC archive build and high-level caller exclusion.
- Run `:app:testSshNativeClientHost` as an AArch64 cross-link and assert the
  explicit `runtime=not-run` boundary when qemu is unavailable.
- Run callback host, JVM, strict `compileSshFiles`, all Release ABIs, lint,
  assemble, APK ZIP scan and `git diff --check`; assert 0 JVM failures/errors/
  skips and no fixture caller objects.
- The current host regression covers empty-peer EAGAIN, half-close EOF,
  cancel, and managed-view deadline error propagation. Before claiming
  runtime, add an authorized Android ARM execution test for partial packet/
  write I/O, host proof, KEX completion, close retry and JNI/Java session
  ownership. Build success alone is insufficient.

### 7. Wrong vs Correct

Wrong: extract the ignored G1 `libssh.a`/fixture objects and link them directly
into `libctos_ssh_files.so`, or close the callback after `ssh_free`.

Correct: rebuild the pinned source closure with `-fPIC`, add the measured
PKCS#11/SK PIC objects, reject stock high-level symbols, and close the same
thread callback before KEX unbind/OpenSSH free and descriptor release.

## G0: synthetic Android memory and managed exec

The first probe is opt-in and independent of production data. Compile for
API28/arm64 with the pinned NDK, strict warnings and 16KiB ELF alignment.
Only `ctosSshMemoryProbe=true` together with `ctosSecretsUiFixture=true` may
package it. Use a separate generated jniLibs directory included only by that
fixture variant; a later normal build must not pick up stale probe output.
Never register the executable as a Portable tool or expose a general SSH CLI.

Use fixed package/test identities and verify distinct UID, no shared UID,
fixture Application and its own Root auto-start disabled before execution.
Select only the explicit probe instrumentation method/opt-in. Execute the
fixed absolute `nativeLibraryDir` binary through ProcessBuilder, without
Shell, Root, arbitrary argv/path, activity launch, user vault, personal SSH
files, network listeners, clipboard or ordinary payload files.

Probe anonymous memory candidates separately. API26 ASharedMemory is a public
NDK entry; the libc memfd_create declaration is API30. A raw syscall candidate
at API28 may only be a clearly labelled capability experiment, not a silent
fallback or proof of API28/29 device support. Record actual backend/errno and
retain unsupported candidates. At least one complete eligible candidate is
required; zero eligible is a failure, not a skipped or successful gate.

An eligible file candidate needs actual map/mlock and the upstream-required
POSIX file shape: independent offsets on reopen, fstat, stdio, append/truncate
and owned held-FD behavior. mmap alone is insufficient. Restricting shared
memory protection or closing a parent FD does not revoke an existing child
mapping or parsed secret. Use explicit bounded parent/child synchronization,
own-buffer wiping and verifiable held-FD cleanup. This is evidence only for
probe-owned resources, not arbitrary same-UID isolation or a production broker.

Native exec inherits only a deliberate owned FD whitelist and fixed synthetic
generation. Use finite poll/wait deadlines, owned child PID/process group,
death-race checks and reap on success, cancellation and failure. Native child
code must never re-enter the JVM. Java must drain bounded output and close
its exact Process/executor streams on every path. Report only fixed protocol
stages/statuses and numeric metadata; never dump payload, arbitrary paths,
environment, raw exception messages or unfiltered child output.

## Gate progression and validation

G0 compile/packaging is not a device pass. A current App UID/SELinux execution
is required; adb-shell execution cannot substitute. API30/4KiB evidence does
not prove API28/29 or a real 16KiB device. Device install needs current SSH-task
authorization; earlier TOTP authorization is not inherited.

G1 pins official OpenSSH/OpenSSL inputs, hashes/licenses and Bionic builds.
G2 uses real same-version upstream config/glob/authfile/hostfile functions and
parent/ProxyJump behavior, including no local command or trust bypass effects.
G3 integrates typed host capabilities, per-hop host/identity approval, bounded
credential release, encrypted trust proposal CAS and actual synthetic protocol
authentication/PTY/forwarding/service lifecycle. Do not replace upstream
semantics with a simplified parser, or quietly write plaintext temporary files
when a gate fails. Return to the approved design to resolve a failed premise.

Keep actual results and gaps in design/verification.md and the existing task.

## Executable contract: G0 and Android build baseline

### 1. Scope / trigger

Applies to the Java-to-native synthetic probe and the G1 research builder.
Actual 2026-10-13 G0 evidence is API30/4KiB/Permissive, App UID10167:
raw memfd is eligible; ASharedMemory maps/locks but fails regular-file shape
with errno95. Do not promote this to Enforcing, API28/29 or OpenSSH behavior.

### 2. Signatures

```sh
./hako bash -lc 'cd android && ./gradlew -PctosSecretsUiFixture=true -PctosSshMemoryProbe=true -Ptarget-platform=android-arm64 :app:assembleSecretsFixtureRelease :app:assembleSecretsFixtureReleaseAndroidTest :app:lintSecretsFixtureRelease'
adb -s <current-authorized-serial> shell am instrument -w -r -e class 'im.majo.ctos.SshMemoryPlatformTest#syntheticAnonymousMemoryAndManagedExec' -e ctosSshMemoryPlatformProbe true im.majo.ctos.secretsfixture.test/androidx.test.runner.AndroidJUnitRunner
./hako bash /app/.trellis/tasks/10-08-ssh-terminal-client/research/android-stock-build.sh /app/build/ssh-native-g1/<verified-input-directory>
```

Native entry is the fixed APK `libctos_ssh_memory_probe.so --probe`;
only its owned self-exec modes may receive fixed FDs3/4/5 and synthetic
generation73. No Shell or caller-supplied file, argv, payload or environment.

### 3. Contracts

Protocol records are `SSH_G0 backend=<enum> phase=<allowlist> state=pass|unsupported|error errno=<0..4095> value=<u64>`.
Backends are `meta`, `ashared`, `raw_memfd`; Java rejects unknown records,
non-ASCII, more than16384 bytes/80 records/200 chars per record. Pass requires
errno0, no mandatory error, complete candidate phases, final gate/count/exit
agreement and at least one eligible backend. Diagnostics alone do not pass.

Native reap uses exact owned PID, WNOHANG, finite monotonic deadlines plus
100/200 attempt caps. Clock failure forces error; unconfirmed PID ownership
persists, prevents another spawn and receives a bounded final retry.
`cleanup=pass` requires region cleanup and no pending child. Java cleanup
has an 8-second wait budget, tries each of three streams independently, keeps
terminating/reaping/executor cleanup through interruption and restores the
interrupt flag afterwards. Cleanup failure fails the test.

G1 only accepts canonical nonsymlink `/app/build/ssh-native-g1/` inputs inside
hako, verifies the two fixed official payload hashes before fresh mktemp and
again after copy, then extracts. Configure/make use `env -i`, pinned NDK/API28,
private static PIC libcrypto, public NDK zlib and stage timeouts. Preserve
Compression (`WITH_ZLIB=1`), OpenSSL/header checks, config logs, complete source
notices and failed runs; do not execute ARM artifacts or install to production.
Applied compatibility patches must pin original sources and patch SHA, use
exact hunks, preserve before/after evidence and identify a patched ABI baseline.

### 4. Validation and error matrix

| Condition | Required result |
| --- | --- |
| Probe property false/unknown or true without fixture | Gradle configuration failure |
| Missing instrumentation opt-in | No execution; never count as gate pass |
| Wrong method, package/UID, reused data or invalid executable | Fail before execution |
| ASharedMemory maps but lacks regular POSIX shape | Unsupported candidate; retain errno |
| Zero complete candidates, clock failure, unconfirmed reap or cleanup failure | Gate/test failure |
| Changed source/patch hash, symlink/escaped input, cross-build error | Stop and retain evidence |
| Native/provider or crypto/DNS behavior unverified | Preserve open gate; no production pass |

### 5. Good / base / bad cases

Good: complete raw memfd lifecycle passes while unsupported ASharedMemory
remains explicit. Base: source/build/ELF only, device gate still open.
Bad: using mmap success, skipped JUnit, adb exit0, patched compile or a
diagnostic-complete log as proof of production SSH.

### 6. Tests required

Build and sign exact fixture artifacts; verify frozen source hashes, arm64 ELF,
interpreter/DT_NEEDED/PT_LOAD and normal APK exclusion after stale probe output.
Run only the explicit authorized method; assert JUnit1/pass/no skip and fixed
candidate/gate protocol. Check owned UID process cleanup, precise uninstall and
production path/hash preservation. Future G1 crypto/runtime and G2 upstream
file/each-hop tests remain required. Fault-injection claims need actual tests;
reviewing clock/interruption code cannot replace them.

### 7. Wrong vs correct: Bionic compatibility

Wrong: fake HAVE_BZERO, skip its fallback because Bionic has a callable macro,
disable crypto/header checks, or call a patched build unmodified stock.
Correct: parse Bionic unistd.h before fallback attribute macros; preserve the
real `(bzero)(...)` definition/prototype for upstream `explicit_bzero`'s
volatile function pointer, and keep that wipe implementation byte-identical.
Resolver compatibility must retain actual DNS/security behavior; never invent
an opaque `_res` structure or silently disable DNSSEC to get a green build.
The current research-only public resolver branch additionally requires both
`__BIONIC__` and `CTOS_ANDROID_ABI_BASELINE_ONLY`; without that flag the upstream
path remains intact. Public res_init/res_query and preserved AD parsing do not
prove EDNS0/DO control or trusted DNSSEC validation. Do not run/package this
baseline as a production trust provider. Native constrained G2 must reject
VerifyHostKeyDNS paths that could accept before the required human host-key
approval; ordinary getaddrinfo target DNS is a separate capability.

## G2 fixture contract

Actual 2026-10-13 evidence: API30/4KiB/Permissive App UID10171,
six groups and real parent/jump exec inheritance passed in 1 test/4.496s.
Both fixture packages were removed and the production APK hash stayed equal.
This is trusted-synthetic evidence, not the production policy/provider gate.

OpenSSH cleanup_exit uses _exit and skips ELF destructors. The VFS-only
wrapper must release owned resources before real _exit without changing its
status; driver/baseline and signal teardown do not use this hook. Hold the
primary WNOWAIT zombie anchor through every group signal, then reap; never
signal a group after its ownership anchor has been reaped. A cleanup receipt
must include bootstrap close/unlock errors and cannot hide later unmap errors.
Mask cancellation through normal teardown until shared view ownership is gone.

Build input collection must include every verified regular member (including
.gitignore): Gradle FileTree default exclusions are insufficient. Direct
Files.walk without FOLLOW_LINKS plus canonical/member checks freezes the
complete 1240-file baseline input list; compare its aggregate before/after build.

This stage uses only fixed trusted synthetic inputs. Its six upstream-file
groups do not establish the production command/trust policy. Native source,
build, packaged artifacts and current App UID execution each require evidence;
Java/Gradle review or successful configuration alone cannot pass this stage.

The fixture property is `ctosSshUpstreamProbe=true` with
`ctosSecretsUiFixture=true` and an explicit canonical nonsymlink baseline
`ctosSshUpstreamBaseline=/app/build/ssh-native-g1/stock-run.<id>`.
Before Gradle snapshots inputs, inspect every descendant of its input trees
without following links, check selected files again, and freeze the File list.
Fixture-only generated JNI and license assets must remain excluded from a
later normal APK even with stale generated output.

Java executes only `libctos_ssh_upstream_probe.so` with two absolute fixed
sibling executables, `libctos_ssh_upstream_client.so` and
`libctos_ssh_upstream_baseline.so`. The explicit test method is
`im.majo.ctos.SshUpstreamPlatformTest#syntheticUpstreamMemoryFilesAndProxyJump`
with `ctosSshUpstreamPlatformProbe=true`. Preserve G0's package/UID/fresh-data/
Root-off guard and bounded drain/cleanup. Java waits at most60 seconds for
the native process, then3 seconds for its reader, with a shared8-second cleanup
wait budget; do not describe these as a total61-second deadline.

Records must match exactly:

```text
SSH_G2 phase=<allowlist> state=pass|error errno=<0..4095> value=<u64> pid=<positive-int32> generation=73
```

Require unique allowlisted complete ASCII records within16384 bytes,
80 records and200 chars per record. `pass` requires errno0 and `error` requires
nonzero errno. Require all six groups `config`, `fd_negative`,
`identity_hostfile`, `proxy_inherit`, `cancel_reap`, `revoke_local`, plus
`cleanup`, distinct verified `client_parent`/`client_jump` PIDs, and fixed
boundary records `capacity=16384`, `object_limit=16`, `proposal_stream=1`,
`local_scope=1`. Both actor records have value1 and precede `proxy_inherit`
with value at least2. A final `gate=pass value=6` and process exit0 must agree;
missing metadata, errors or incomplete cleanup must fail. Optional
`crypto_version` must be positive if emitted; absence proves no runtime
version or algorithm result. Never echo arbitrary child output on failure.

Immutable private payloads need true-length locked memfd-backed access.
Mutable public known_hosts proposals may use bounded NDK funopen streams over
locked anonymous storage with explicit logical length, without claiming full
POSIX compatibility or locking/erasing every upstream heap copy. Each group
must call actual pinned upstream readconf/glob/authfile/hostfile functions.
Only owned random127.0.0.1 listeners are permitted for the synthetic jump and
target. Require actual distinct parent/jump exec reads, no direct-target
fallback, bounded full owned-chain reap and explicit local cleanup evidence.
The baseline negative observer must preserve upstream FD-closing behavior;
startup/linker/getpwuid failures cannot substitute for observing that boundary.

Before device execution, build and review exact artifacts/ABI/signatures/
licenses, test strict configuration guards, and check normal APK exclusion.
Apply the current user scope for this particular loopback fixture rather than
expanding a prior no-network G0 method approval. Never call trusted synthetic
success production policy success: local command hooks, native bypasses,
malicious paths, per-hop capabilities, human host-key approval and encrypted
trust CAS require separate mandatory gates before real-vault UI integration.

## Native configuration policy increment

### 1. Scope / trigger

After frozen G2, copy the pinned source to a fresh policy build. Never mutate
the G1 baseline or claim this increment completes the production broker.
The first increment covers native parser restrictions and missing-provider
entry/trust rejection; managed paths, approved contexts, shell quoting and
per-hop capabilities remain mandatory before product integration.

### 2. Signatures

`ctos_ssh_policy_options(const Options *, const char *object, int line)` checks
actual upstream option values after successful parsing. Rejection records only
fixed reason, numeric synthetic object ID and line. `Match exec` is rejected
inside its original parser branch before expansion, irrespective of activep.
`ctos_ssh_policy_entry()` and `ctos_ssh_policy_trust()` reject without a real
provider; no test boolean may make a production key trusted.

### 3. Contracts

Preserve original Host/Match, first-value and additive semantics. Nonexecuting
inactive directives and overwritten values do not become effective policy
violations; Match exec/!exec/exec= always rejects, including NEVERMATCH.
Use original option_clear_or_none for command/provider neutral strings and
strict parse_jump validation for configuration as well as CLI. Reject effective
strict no/off/false/accept-new, DNS yes/ask and localhost bypass. DNS ask is an
unsupported capability, not the same automatic-accept behavior as DNS yes.
Every policy rejection is sticky until the synthetic evaluation is reset.

### 4. Validation and errors

Require actual parser positives and negatives for none/no, case, comments,
first values, inactive/final/NEVERMATCH and strict jump strings. Observe native
fork/access/file/network calls with fail-closed test wrappers; a rejected
Match must never reach execution or expansion. Native entry and verify_host_key
must reject before their original file/key/cache/DNS operations.

### 5. Good / base / bad

Good: safe first value shadows a later dangerous value exactly as upstream;
Match exec "none" rejects. Base: pinned patch builds but device untested.
Bad: simplified config parser, ignoring inactive Match exec, trusting arbitrary
ProxyCommand or treating a parser-only result as full path/provider approval.

### 6. Tests required

Compile the real patched readconf/ssh/sshconnect objects with pinned baseline
libraries; compile owned C with strict warnings. Retain original source hashes,
patch, fresh output and ELF provenance. Device evidence needs an independently
packaged explicit method, numeric bounded records and exact cleanup under the
current authorized synthetic scope. No ordinary plaintext payload files.

### 7. Wrong vs correct

Wrong: inspect only activep before execute_in_shell or mark jump_host nonnull
as a trusted shell command. Correct: refuse execution in the recognized Match
branch before expansion; validate configuration jumps with original strict
parser, then require separate managed-chain binding and safe generated argv.

### Next increment: real Include closure

Actual API30/4KiB/Permissive evidence: 117 cases/1 test/0.189s,
fresh UID10179, fixtures removed and production APK preserved.
The two earlier expanded runs failed and remain recorded. Prepare OpenSSL with
OPENSSL_INIT_NO_LOAD_CONFIG before either public parser entry: Include expands
connection hashes, so lazy crypto initialization can otherwise attempt loading
an external configuration. Keep the deny observer; do not mask an extra access
by accepting it or setting OPENSSL_CONF to a plaintext pathname.

Exercise read_config_file_depth with locked anonymous synthetic config objects
and original compat glob using in-memory directory callbacks. Do not delegate
unknown paths to the ordinary filesystem. Check expanded Include patterns
before glob, then actual returned object paths before fopen; reject escaping,
other-user home, proc-FD paths, missing members and depth over eight. Keep
user Include relative to ~/.ssh; no including-file-directory substitution.
Return a bounded validation failure after closing streams instead of terminating
the validation worker inside readconf. Production path/provider is still absent
and must reject; synthetic fixed object IDs do not authorize real files.

### Managed ProxyJump formatting increment

Use a native helper from the real ssh.c ProxyJump generator. Quote each program,
user, jump chain, config and destination argument, and double literal percent
characters for OpenSSH's later single percent_expand pass. Build -W from the
already resolved target and port, rather than placing an unquoted replacement
inside shell syntax. Never fall back to a system ssh executable. Formatting
does not create a trusted role: missing bootstrap/provider still rejects.

Test quoted values through actual percent_expand and a fixed owned system
shell builtin, with the expected value provided as a separate exec argv item.
Do not compare two strings produced by the same escaping helper. Bound exact
child/group lifetime, hold ownership through signalling/reap, reject cleanup
failure and keep all child output away from the fixed numeric protocol.

## Public file view bridge (implementation gate, no production session yet)

The synchronous AppSecrets file loan includes closed-graph metadata for private
keys, but their bodies have no representation in the public native view. Encode
explicit wire tags (not enum ordinals), snapshot identity, root, connection and
request bindings, lengths and references. Revalidate in native code: strict
UTF-8, canonical vault paths, unique IDs/paths, exact types/body lengths, full
reachable closure, no cycles and depth <=8. Bound128 objects/1MiB each/8MiB total
declared contents and the complete view to12MiB. Reject trailing bytes.
Native admission and lookup check CLOCK_BOOTTIME against the exact
elapsedRealtime request deadline, including suspend; clock failure denies access.

Copy directly from the bounded JNI byte array into an owned0600 raw memfd,
mapped/locked with MADV_DONTDUMP before copying. Wipe the Java encoding in a
finally block; do not expose native pointers, FDs, bodies or arbitrary filenames
over MethodChannel. One live opaque native token is allowed; stale close must
never affect a later token. No fallback to disk or unlocked memory. Cleanup
wipes and verifies its owned mapping before munlock/unmap, truncates the held
file to zero and closes once. Any cleanup uncertainty poisons new admission.

Register the returned lease against the exact VaultSession epoch before
publication, then repeat the operation's cancellation/snapshot/deadline guard.
Explicit lock, timeout, replacement unlock and direct session lock revoke it.
Session revocation must not call AppSecrets or acquire its publication lock.
Explicit lease close must not acquire the session lock while holding the lease
lock. Revoke only this bootstrap file view: established SSH transport and its
already approved public host-key state remain the separate R8 service owner.
The owning VaultOperation also binds the lease before publication: cancellation
after a successful return revokes that exact view, while an old request cannot
rebind to or revoke a newer operation's view. Drop the operation monitor before
invoking revocation; cancelled-before-bind rollback remains caller-owned.

This public-file bridge alone does not create a trusted native role, register the
production restore validator, or launch SSH. The separate Java role/host/KEX/
credential contract below may release a typed secret only when its caller has
supplied matching proofs; native launcher and protocol integration are still
required before this becomes a production session.

## Java production-facing bridge contract (native integration gate)

### 1. Scope / Trigger

This contract was added after the role, host-key, signed-KEX and typed-secret
implementation increment. It is the Java-side boundary between the public
`AppSecrets` vault and a future owned native OpenSSH launcher. It is not a JNI
or MethodChannel authority, does not parse prompts/terminal output, and does
not itself prove that a native callback came from the real OpenSSH event loop.

### 2. Signatures

- `SshRoleRegistry.issue(SshRoleContext.Bootstrap, PublicView)` issues one
  package-private role for `CLIENT` or `JUMP`; `release(role)` revokes it.
- `SshHostKeyAuthority.requireVerified(role, Evidence, SshTrustProposal)`
  returns a role-bound `Proof` or throws a stable `SSH_HOST_KEY_*` error.
- `SshKexProof.fromNative(role, hostProof, cycle, sessionDigest)` creates the
  proof only for an approved `KNOWN`/reviewed `NEW` host key.
- `SshCredentialBridge.openCredential(role, evidence, proposal, entryId,
  Kind)` and `openPrivateKey(role, evidence, proposal, path, keyIdentity)`
  create a once-used `Request`.
- `SshCredentialBridge.release(request, kexProof, Receiver)` performs the
  final guarded callback and closes/revokes the request in all post-validation
  paths.

### 3. Contracts

- A bootstrap carries `ownerPid`, `hop`, `connectionId`, target/host/port,
  `publicViewId`, 32-byte role nonce, 32-byte connection binding and a
  `CLOCK`-compatible deadline. It contains no argv, shell text or secret body.
- Evidence is role-bound and carries key type, `SHA256:` fingerprint, host-key
  classification, known-hosts base/replacement digests and native proof data.
  A `NEW` key needs an exact immutable proposal/review; `KNOWN` cannot rewrite
  known-hosts; changed, revoked, certificate and invalid classifications deny.
- A KEX proof binds the same role, host proof, positive cycle and non-zero
  32-byte session digest. It is a readiness proof, not a cached credential.
- Credential `Kind` is one of `PASSWORD`, `KEY_PASSPHRASE`,
  `KEYBOARD_INTERACTIVE` or `PRIVATE_KEY`; the vault manifest type is checked
  before any receiver callback. Requests bind role, target, connection and
  the originating vault operation.
- `release` repeats role deadline, public-view, host proof, KEX proof,
  operation binding and cancellation checks immediately before vault use.
  Missing KEX, stale role, wrong hop or closed operation must never reach the
  receiver; the request is then closed and its operation revoked.

### 4. Validation & Error Matrix

| Condition | Required result |
| --- | --- |
| Duplicate owner/hop, nonce or same-hop binding | `SSH_ROLE_REPLAY`; no second role |
| More than eight live hops | `SSH_ROLE_BUSY` |
| Missing/expired/closed role or revoked public view | `SSH_ROLE_REQUIRED`, `SSH_ROLE_EXPIRED` or `SSH_FILE_VIEW` |
| NEW key without exact role/target/digest review | `SSH_HOST_KEY_REVIEW` |
| Changed/revoked/certificate/invalid host evidence | `SSH_HOST_KEY_CHANGED`, `SSH_HOST_KEY_REVOKED`, `SSH_HOST_KEY_CERTIFICATE` or `SSH_HOST_KEY_INVALID` |
| Missing or cross-hop KEX proof | `SSH_KEX_REQUIRED` or `SSH_HOST_KEY_CHANGED` |
| Missing receiver, invalid request or manifest-kind mismatch | `PARAMETERS` or the existing vault operation error |
| Failure after opening a request | close the request and revoke its exact operation; do not rebind it |

### 5. Good / Base / Bad Cases

- Good: a distinct hop on one connection receives a distinct binding, a
  known/reviewed host proof and a matching signed KEX+NEWKEYS proof; only its
  typed vault entry reaches the receiver.
- Base: a request is opened and then cancelled, locked, expired or released
  without KEX; the receiver is untouched and the operation is revoked.
- Bad: a UI boolean, prompt text, control frame, cached host fingerprint or
  same connection ID is used to release a secret; reject it and keep the
  public/native provider gates closed.

### 6. Tests Required

- `SshProductionBridgeTest`: replay and distinct-hop binding, all host-key
  classifications, exact NEW-key review, kind mismatch, missing-KEX closure,
  successful typed password/private-key release, expiry and cross-hop proof.
- `SshRoleRegistry` regression: distinct hops on one connection must be
  allowed while same-hop binding/nonce reuse is rejected.
- `:app:testReleaseUnitTest`, `lintRelease`, and the host native view/control
  tests must pass before changing native provider or launcher gates. These are
  local contract checks and do not substitute for device or SSH endpoint AC.

### 7. Wrong vs Correct

#### Wrong

```java
if (uiConfirmed || prompt.contains("password")) {
    receiver.accept(secret);
}
```

#### Correct

```java
bridge.release(request, signedKexAndNewkeysProof, receiver);
```

The correct call is still allowed only through the package-private bridge and
its repeated role/host/operation guards; native production wiring must supply
the proof from the real event loop.

## Native role bootstrap seam (bounded registry; KEX fixture integration)

The role registry is bounded and internal, not a production launcher. Its KEX
integration is compiled only by the opt-in KEX fixture and by the host-only
`testSshRoleBootstrapHost` task; the normal APK does not package the KEX probe
or its OpenSSH objects. The same registry implementation is now compiled into
the normal `libctos_ssh_files.so` only to support the production JNI
ownership/open seam described below. The C API is bounded and internal:

- `ctos_ssh_role_bootstrap_open(request, &token)` accepts only `CLIENT` or
  `JUMP`, the calling process's exact PID/UID, hop `< 8`, valid port, non-zero
  32-byte nonce/binding, restricted identifiers and a BOOTTIME deadline within
  60 seconds. Same owner PID/hop, nonce, or same-hop binding is replay; a
  distinct hop on the same connection is allowed.
- `ctos_ssh_role_bootstrap_snapshot/active/close(token)` require the token's
  owner PID/UID to still match the caller. Expiry, close, or revoked public
  view makes the record unavailable and wipes its bounded snapshot.
- `ctos_ssh_role_bootstrap_encode(token, output, capacity, &length)` emits a
  versioned `CSRB` record of role metadata only. It never accepts argv,
  command text, paths, descriptors, terminal output or secret/file bodies.
- Admission rereads `CLOCK_BOOTTIME` after taking the role mutex; that sample
  validates the deadline and drives expired-slot reclamation before duplicate
  and capacity checks.
- `SshNativeRoleBootstrap.issue(wire, registry, view, clock, NativeOwnership)`
  rejects malformed/trailing/non-ASCII/expired records, and has no default
  ownership callback. The callback must bind the exact native token/UID and
  metadata before the Java registry issues an existing role; the returned
  `Issued` object retains token, UID, and its creating registry for later native
  lifecycle binding. Release validates that exact live Java role before native
  cleanup; cleanup/linkage/unchecked callback failure leaves the Java role
  registered, and only a successful native close deregisters it.

The host task currently reports `bootstrap1198/cleanup1/gate1198` after the
stale-output, post-lock-clock, and stale-token regression checks. The KEX runner
links the role object into the existing fixture library, but this remains
synthetic native evidence: no OpenSSH launcher, real OpenSSH callback, device
execution or external endpoint is implied.

## Production JNI role ownership/open seam

`ssh_role_jni.c` is compiled into the normal `libctos_ssh_files.so` alongside
the public-file view. Its JNI `open` obtains the current native PID/UID itself,
accepts only the bounded role metadata, and returns the canonical `CSRB` wire;
it never accepts argv, shell text, paths, descriptors, file bodies or secrets.
Its JNI `owns` requires the current UID, a live token owned by the current
process, and byte-for-byte equality with a fresh native canonical re-encoding.
Malformed, oversized, expired, mismatched, or unavailable records fail closed.

`SshNativeRoleBootstrap.productionOwnership()` is the only production Java
ownership factory. `open(Bootstrap)` is a typed metadata wrapper and does not
start a process or an SSH connection. The decoded wire is retained as an
immutable copy for the native proof; no default/test callback is substituted in
production. `SshNativeRoleBootstrap.productionRelease()` is the only
production release callback: JNI accepts only the issued token and bound UID,
then the native registry additionally requires the current process's live
token. `release(issued, registry, callback)` validates the exact live Java role,
calls native cleanup first, and deregisters the Java role only after a
successful native close; `IOException`, linkage, or unchecked callback failure
leaves the Java role registered for retry. The release seam is not yet called
by a production event loop and does not implement the launcher, host-key
callback, protocol authentication, PTY/forward/service, or UI.

### Native role lifecycle release contract

#### 1. Scope / Trigger

This contract applies when an already-issued role is explicitly torn down. It
is a lifecycle seam for a future owned event loop, not a launcher, process
supervisor, or proof that an OpenSSH connection exists.

#### 2. Signatures

- JNI: `Native.release(long token, int ownerUid) throws IOException`.
- Java test/production seam: `NativeRelease.release(long token, int ownerUid)`.
- Java lifecycle: `SshNativeRoleBootstrap.release(Issued, SshRoleRegistry,
  NativeRelease) throws VaultException`.
- `Issued` retains the opaque token, bound UID, creating registry, and Java
  role; callers cannot replace these with an argv, pathname, descriptor, or
  secret.

#### 3. Contracts

The JNI boundary requires a positive token and a supplied UID equal to the
current process UID. The native registry then looks up the live token owned by
the current PID and UID; tokens are monotonic and stale close cannot affect a
later role. Java validates the exact `Issued`/registry pair and an active role,
calls native cleanup first, and deregisters the Java role only after native
close succeeds. `IOException`, linkage, or unchecked callback failure leaves
the Java role registered so the owner can retry; no permissive fallback is
allowed.

#### 4. Validation & Error Matrix

| Condition | Required result |
| --- | --- |
| Non-positive token, negative UID, null record/registry/callback | `SSH_ROLE_REQUIRED`; native callback is not called |
| Issued role belongs to another registry or is inactive | `SSH_ROLE_REQUIRED`; both native and Java role remain unchanged |
| UID mismatch, wrong process, stale/expired token, or missing native slot | Native `IOException`; Java role remains registered |
| Successful native close | Java registry releases exactly that role |
| Repeated release after successful Java close | `SSH_ROLE_REQUIRED`; no second native call |

#### 5. Good / Base / Bad Cases

- Good: the event-loop owner calls `release(issued, registry,
  productionRelease())`, native close succeeds, then the matching Java role is
  deregistered.
- Base: native cleanup is temporarily unavailable; the Java role stays live
  and the caller records/retries the bounded failure.
- Bad: a UI boolean, arbitrary token/UID pair, stale callback, or different
  registry is allowed to close or deregister a role.

#### 6. Tests Required

- Host role test: assert stale-token, cross-process, UID and rejected-output
  behavior, with strict `-Wall -Wextra -Werror` compilation.
- `SshNativeRoleBootstrapTest`: assert native-first ordering, exact registry
  binding, malformed input, IOException/linkage/runtime failure retention,
  retry, and post-success inactive role.
- Release build: assert `open`, `owns`, and `release` JNI exports and that the
  normal APK contains only the production SSH library, not fixture probes.

#### 7. Wrong vs Correct

Wrong:

```java
registry.release(issued.role); // native ownership may remain live
```

Correct:

```java
SshNativeRoleBootstrap.release(issued, registry,
        SshNativeRoleBootstrap.productionRelease());
```

The correct call still does not prove launcher/event-loop ownership or a live
SSH protocol; those gates remain separate.

### Typed public FILE access

Resolve only the fixed virtual home `/ctos-vault` into canonical vault paths;
reject file/directory prefix collisions. Scope original compat glob callbacks to
this closed metadata graph, with no ordinary stat/opendir fallback. Check the
expanded pattern before glob and each actual match's CONFIG type before reading.
Private metadata may support stat, but cannot produce a readable FILE.

For each required public read create a fresh0600/CLOEXEC memfd and lock its
true-length mapping before copying. Empty files have no payload mapping. FILE
offsets are independent and stdio buffering is disabled. Close verifies the
wipe, confirms unmap before truncating, and makes held descriptors EOF. Uncertain
cleanup keeps ownership and blocks further reads; teardown never masks it.
Close every reader before destroying its parent view.

Bound one validation evaluation to128 simultaneous readers,1024 open attempts,
8MiB held reader payloads and64MiB cumulative copied reads. Exceeding these
work budgets rejects the complete evaluation rather than truncating Include
semantics. Keep this budget separate from the128-object/8MiB source manifest.
These parser adapters currently run only in the opt-in policy fixture; production
main/trust entry guards remain closed and no restore provider is registered.

## Native control transport increment (not a host-approval authority)

Use an owned AF_UNIX SOCK_SEQPACKET socketpair with CLOEXEC/NONBLOCK and
SO_PASSCRED enabled on both ends before fork. SO_PEERCRED on a pre-fork pair
reports credentials captured at pair creation; authenticate each received
packet's SCM_CREDENTIALS against the launcher's exact owned PID and App UID.
See [Linux unix(7)](https://man7.org/linux/man-pages/man7/unix.7.html).

Bound the binary frame to88 header bytes plus32768 payload bytes: fixed magic,
version, allowlisted kind, zero flags, exact length, positive monotonic sequence,
32-byte issued role nonce and32-byte connection binding. Reject replay, gaps,
wrong peer/binding, truncation, extra or missing ancillary messages and unexpected
FDs. Only the LOAN kind may carry one readonly, CLOEXEC,0600 anonymous regular
descriptor with exact declared length and purpose-specific bounds. The owned
blob registry and later parser must separately prove provenance and contents;
file shape is not a secret-release grant.

Use recvmsg MSG_CMSG_CLOEXEC; close every actually received descriptor on every
rejection, never a number parsed from payload. Fail and close the channel on
protocol/peer failure. No log may contain frame bytes, role nonce, body hash,
host-key blob, request metadata or exception text. Poll/IO retry work uses a
finite CLOCK_BOOTTIME deadline and independent attempt cap. Forked native tests
own and reap their exact children; no JVM fork or external endpoints.

This transport does not classify host keys or authorize saved credentials.
Native production role issuance, real KEX completion wiring, each-hop host
trust callback, final AppSecrets guards and bounded private FD handoff remain
subsequent mandatory steps. The Java typed-request contract exists, but it is
not a native provider and must never be bypassed from terminal output or a UI
boolean.

## Real KEX completion guard increment

`ssh_kex_guard` binds up to eight live client `struct ssh` objects to an owned
native PID, distinct issued role nonce, connection binding, hop index, expected
SHA256 public host-key fingerprint and a bounded BOOTTIME admission deadline.
Only the native event-loop launcher may issue these records; there is no JNI,
MethodChannel, terminal-output or control-frame setter. The current fixture
issues synthetic known keys. Java host classification/review and typed release
contracts now exist, but native product host issuance, callback wiring,
certificate/revocation provider integration and broker role issuance remain
unimplemented.

Patch the pinned original `kex.c`, `kexgen.c` and `kexgexc.c` in a fresh tree:
begin invalidates the previous completion; host approval records the exact
expected key; the signed hook follows a successful real `sshkey_verify` in
both generic and DH-GEX client paths; completion runs only after the actual
incoming NEWKEYS packet was parsed and inbound keys installed. Require the
same approved/signed key, valid session ID and current cycle. Missing context,
wrong process/key/phase, nonce/connection/hop mismatch and expiry deny readiness.
Client- and server-initiated rekey must repeat signed KEX; a cached public host
key does not require vault unlock. Credential readiness still expires and is
only a prerequisite for the future final AppSecrets effect guard.

The control fixture opt-in also packages the separately generated KEX executable
and source notices; neither belongs to the normal APK. Execute only its fixed
`--synthetic-kex` mode with generated in-memory keys and paired real in-memory
packet streams, no network or ordinary payload files. The actual DH-GEX transcript
uses an upstream built-in group through a test-only group selector, not a host
moduli file. Validate EXT_INFO with the real handler; the upstream test API
expects complete identification lines, so fragmentation starts at binary KEX.
Require exact numeric handshake/cleanup/gate records and native exit agreement.
These results do not prove user authentication, private release, PTY, forwarding,
foreground service or a production HostKeyGate.

## G3.2 same-process KEX-only launcher contract (fixture-only)

This is an executable contract for the current narrow increment, not a
production launcher contract. It applies only to the opt-in
`ctosSshControlProbe=true` fixture and its host/KEX build. It must not be
enabled by the normal APK, JNI production library, Java event loop, device
instrumentation, external SSH endpoint, vault provider or user credential.

### 1. Scope / trigger

The fixture trigger is the fixed `--synthetic-kex` test mode in the separately
generated KEX artifact. The only admission entry point is a same-process call
before the fixture KEX loop:

```c
int ctos_ssh_native_launcher_admit(const unsigned char *wire,
    size_t wire_length, ctos_ssh_role_token token, struct ssh *ssh,
    const unsigned char expected_host[32]);
int ctos_ssh_native_launcher_close(struct ssh *ssh,
    ctos_ssh_role_token token);
```

It accepts no `argc/argv`, shell or command text, executable/config pathname,
ordinary file, descriptor, network endpoint, callback transport or secret. The
`struct ssh` is an already-created client KEX fixture object; the launcher does
not call stock `main`, `getopt`, `closefrom`, config parsing, file access,
`exec`, userauth, PTY, forwarding or remote service code.

### 2. Signatures

`wire` is a bounded versioned `CSRB` byte string emitted by
`ctos_ssh_role_bootstrap_encode(token, ...)`; the accepted maximum is
`CTOS_SSH_ROLE_WIRE_BYTES` (2048). `token` is a positive live native role
token owned by the calling PID/UID. `ssh` must be non-null, client-side, have a
client KEX object and a non-null host-key callback. `expected_host` is exactly
32 non-zero bytes containing the launcher-owned expected SHA-256 host-key
fingerprint. No caller-supplied host-key classification or credential body is
accepted.

### 3. Contracts

Admission takes a fresh native role snapshot and rejects a missing, closed,
stale or expired role. The snapshot must still match the current `getpid()` and
`getuid()`, an allowed role program/hop/port and restricted metadata; its
`CLOCK_BOOTTIME` deadline must be strictly in the future. The launcher then
re-encodes the live token and requires both exact byte equality and exact length
equality. Therefore malformed bytes, non-canonical bytes and every trailing
byte are rejected before KEX binding.

The launcher rejects a duplicate `struct ssh` or token and binds at most eight
live fixture objects. Only after all checks pass does it call
`ctos_ssh_kex_bind_role`; the patched KEX sequence must remain host approval,
real signature verification, inbound NEWKEYS installation, then completion.
Generic, DH-GEX and rekey checks remain guarded by the same role/hop/nonce,
connection-binding, host-fingerprint and deadline state.

Close requires the exact `(ssh, token)` pair. A mismatched pair has no effect;
a repeated close after successful teardown has no effect. Successful teardown
is native-first: close the native role, then unbind the KEX object and wipe the
launcher slot. If native role close fails, return failure and retain the KEX
binding/slot as a poisoned retry boundary; never unbind or report Java
deregistration success on that path. Admission wipes its local snapshot,
canonical wire, supplied wire and fingerprint copies on every exit; successful
close wipes the slot.

### 4. Validation and error matrix

| Condition | Required fixture result |
| --- | --- |
| Null/zero/oversized wire, token, SSH object or fingerprint | Reject before KEX bind |
| Server KEX object or missing host callback | Reject before KEX bind |
| Missing/closed/stale role, wrong current PID/UID, invalid role metadata or expired deadline | Reject; no slot or KEX binding |
| Canonical length mismatch, malformed CSRB or any trailing byte | Reject; no partial prefix acceptance |
| Existing token/SSH binding or launcher capacity exhausted | Reject; existing bindings unchanged |
| Wrong host/hop/nonce/connection binding or KEX phase | KEX guard rejects; no completion proof |
| Mismatched close | Reject with binding retained |
| Duplicate close after successful close | Reject with no second native/KEX action |
| Native role close failure | Return failure and retain/poison native-first binding |
| Clock/read/packet/signature/NEWKEYS failure | Fail closed; no credential, userauth or service effect |

### 5. Good / base / bad cases

Good is a live canonical CSRB plus a client KEX object completing the fixed
synthetic loop, including fragmented binary packets, generic and DH-GEX paths,
rekey, exact host-key approval/signature ordering and successful native-first
close. Base is a strict source/build/host result with the ARM artifact not
executed. Bad is accepting a prefix with trailing bytes, using a stale or
expired snapshot, allowing a duplicate/mismatched close to affect another
role, unbinding before native close, retaining local copies, or treating this
fixture as a production trust/credential/client result.

### 6. Tests required

The fixture test must cover malformed, trailing, wrong-wire, duplicate,
expired/stale, missing-role, wrong-host, rejected-host, signature failure,
pre-NEWKEYS, generic/DH-GEX, rekey, two-hop isolation, mismatched close and
duplicate close. Its output is fixed allowlisted numeric protocol records and
must agree with native exit status. The runner must use the pinned OpenSSH
inputs, verify the complete before/after baseline manifest, compile launcher
and test C with `-Wall -Wextra -Werror`, and keep the artifact in the opt-in
fixture output directory. Host execution is allowed only for the synthetic
test; the cross-built ARM artifact is not executed.

The normal `:app:testReleaseUnitTest`, `:app:lintRelease` and
`:app:assembleRelease` checks must pass after fixture output exists. Inspect the
normal APK ZIP and require only intentional production libraries; no KEX,
control, policy, upstream, notice or stale generated fixture output may enter
it. No device, external account/endpoint, vault mutation, installation or
production event-loop result is a pass condition for this contract.

### 7. Wrong vs correct

Wrong:

```c
memcmp(wire, canonical, canonical_length) == 0; /* accepts trailing bytes */
ctos_ssh_kex_unbind(ssh);                         /* before native close */
```

Correct:

```c
if (wire_length != canonical_length ||
    memcmp(wire, canonical, canonical_length) != 0)
	return reject(EPROTO);
if (ctos_ssh_role_bootstrap_close(token) != 0)
	return reject(errno); /* keep slot and KEX binding for retry/poison */
ctos_ssh_kex_unbind(ssh);
```

The correct fixture path still proves only a same-process KEX admission and
teardown seam. It does not authorize production OpenSSH, host-key trust CAS,
typed credentials, PTY, forwarding, service lifecycle, Java callback transport,
device execution or the parent task's complete client acceptance criteria.

### Post-G3.2 production integration gate

After this fixture gate passes, no new production launcher, event loop, or
OpenSSH object may be added to the normal APK until the task design records
process topology/ownership, native-to-Java callback transport, and the managed
host-trust/file/private-key provider. The first two are now recorded as
same-process ownership and attached synchronous calls on the event-loop thread;
the bounded managed-provider handoff is recorded, and the managed AppSecrets
known_hosts CAS writer is now implemented; the native-facing public
config/known-hosts issuer/handoff and real caller remain open and block real
protocol integration. The current PID-bound role token does not cross
`fork()` by assumption, and the
fixture `ssh_api` memory buffers and `ssh_control.[ch]` framing are not
production transports.

Until those decisions and their contracts exist, the only architecture-neutral
follow-up is evidence maintenance: rerun the fixture, strict native build,
normal APK exclusion, and source/hash checks. Do not relabel that evidence as
production event-loop, callback, trust-provider, device, or external-SSH
validation. The decision matrix and required evidence live in the task's
`research/next-production-launcher-20261013.md`.

## Synchronous attached JNI callback adapter and managed-provider handoff contract (production seam; no OpenSSH caller)

This contract records the user-confirmed callback choice. The adapter is
compiled into the normal `libctos_ssh_files.so`, but no real OpenSSH event loop
currently calls it. The bounded Java managed-provider seam now supplies an
exact role/hop-bound NEW-host proposal and a typed, caller-owned credential
byte handoff; the managed AppSecrets known_hosts CAS writer is implemented,
but its native-facing public config/known-hosts issuer/handoff and the real
caller remain open. This section makes that boundary explicit without
promoting fixture control framing or memory buffers to a product transport.

### 1. Scope / trigger

The trigger is binding an already-issued `SshNativeRoleBootstrap.Issued` role to
the same-process native event-loop thread. `SshNativeCallbackAdapter.bind`
captures the calling thread and the JNI environment supplied by production
JNI. The callback is synchronous: native waits for the Java method result on
that same thread. Cancellation may be requested by another owner thread, but
close and all callbacks remain event-loop-thread operations.

### 2. Signatures

Native C entry points:

```c
int ctos_ssh_callback_open(const struct ctos_ssh_callback_target *target,
    ctos_ssh_role_token role, int owner_uid, uint64_t deadline,
    void *environment, ctos_ssh_callback_token *callback);
int ctos_ssh_callback_cancel(ctos_ssh_callback_token callback);
int ctos_ssh_callback_close(ctos_ssh_callback_token callback, void *environment);
int ctos_ssh_callback_host_key(ctos_ssh_callback_token callback, void *environment,
    int classification, const char *key_type, const char *fingerprint,
    const unsigned char base_digest[32],
    const unsigned char replacement_digest[32]);
int ctos_ssh_callback_kex_complete(ctos_ssh_callback_token callback,
    void *environment, uint64_t cycle, const unsigned char session_digest[32]);
int ctos_ssh_callback_credential(ctos_ssh_callback_token callback,
    void *environment, int kind);
int ctos_ssh_callback_credential_bytes(ctos_ssh_callback_token callback,
    void *environment, int kind, unsigned char *output, size_t capacity,
    size_t *length);
```

Java/JNI entry points are `Native.open(target, role, ownerUid, deadline)`,
`Native.cancel(callback)` and `Native.close(callback)`, plus the synchronous
package-private callback methods:

- `onNativeHostKey(int, String, String, byte[32], byte[32]) -> boolean`;
- `onNativeKexComplete(long, byte[32]) -> boolean`;
- `onNativeCredential(int) -> boolean` (legacy availability callback);
- `onNativeCredentialBytes(int) -> byte[]` (bounded typed handoff).

The Java seam is `SshManagedProvider`: its trust source must return a
role/hop-bound `SshTrustProposal`, and its credential source must open the
proof-bound `SshCredentialBridge` request matching the native `Kind`.

Host classification tags are stable `KNOWN=1`, `NEW=2`, `CHANGED=3`,
`REVOKED=4`, `CERTIFICATE=5`, `INVALID=6`; credential tags are stable
`PASSWORD=1`, `KEY_PASSPHRASE=2`, `KEYBOARD_INTERACTIVE=3`,
`PRIVATE_KEY=4`. They are explicit wire values, not Java enum ordinals.

### 3. Contracts

- `open` accepts only the live role token, current UID, strictly future
  BOOTTIME deadline and an attached `JNIEnv` from the same `JavaVM`. It accepts
  no argv, shell, pathname, descriptor, prompt, terminal output or secret
  body. The native table is bounded to eight live callback bindings.
- Each callback requires the exact event-loop `pthread`, the original attached
  environment, a live same-PID/UID role and a non-expired deadline. A callback
  is marked in-flight before releasing the table lock; re-entry is rejected and
  poisons the binding. Native cancellation, close, late role expiry and Java
  failure cannot be converted into success after the Java method returns.
- Host evidence is passed to `SshHostKeyAuthority`. `KNOWN` can produce a
  proof without a proposal; `NEW` needs an exact role/target/hop/fingerprint/
  base-digest/replacement-digest review. The managed provider creates the
  proposal from an exact role-bound source, the authority compares it, and the
  adapter closes it after the synchronous callback. Production JNI supplies no
  proposal over the wire; an absent provider or an unbound/wrong-hop proposal
  remains `SSH_HOST_KEY_REVIEW`. Changed, revoked, certificate and invalid
  classifications remain denied.
- KEX completion requires the prior host proof and a positive cycle with a
  non-zero 32-byte session digest. It produces `SshKexProof` only after the
  native caller has asserted signed KEX and inbound `NEWKEYS`; the adapter
  itself does not parse the SSH packet stream.
- The legacy boolean credential callback remains provider-less. The bounded
  byte callback requires the matching host and KEX proofs, then asks the
  managed provider for one matching `SshCredentialBridge.Request`. The bridge
  repeats role, host, KEX, vault-operation and cancellation guards immediately
  before use; a request-kind mismatch fails closed. Java returns at most
  `VaultManifest.MAX_FILE_BYTES`; JNI copies into caller-owned storage and
  wipes the Java array, while native wipes the caller buffer on failure. No
  prompt, UI, log, ordinary disk staging or cached secret is introduced.
- The native target's optional `destroy` runs only after its callback slot is
  removed. `release` leaves its context valid for a cleanup retry, so a
  post-release mutex/finalization failure cannot leave a dangling JNI context.
- `close` releases the native callback binding before releasing the Java role.
  A native or role cleanup failure leaves the live binding/role for an explicit
  retry; Java deregistration is never reported before native cleanup succeeds.
  Successful cleanup wipes callback proof state and rejects late callbacks.

### 3a. Dedicated one-shot known_hosts candidate handoff

`HostEvidence` and its `CSHR` result remain metadata-only. A future pinned
OpenSSH hostfile issuer uses this separate synchronous entry point to transfer
one exact replacement body; the body is never added to the existing evidence
wire:

```c
int ctos_ssh_callback_known_hosts_candidate(
    ctos_ssh_callback_token callback, void *environment,
    const char *candidate_id, int classification,
    const char *key_type, const char *fingerprint,
    const unsigned char base_digest[32],
    const unsigned char replacement_digest[32],
    unsigned char *replacement_body, size_t replacement_body_length);
```

```java
boolean onNativeKnownHostsCandidate(long nativeRoleToken, int nativeHop,
    int nativePort, String nativeConnectionId, String nativeTargetIdentity,
    String nativeHost, int classification, String keyType, String fingerprint,
    byte[] baseDigest, byte[] replacementDigest, String candidateId,
    byte[] replacementBody);
```

The C handoff accepts only `NEW`, a non-empty body of at most
`CTOS_SSH_CALLBACK_MAX_FILE_BYTES`/`VaultManifest.MAX_FILE_BYTES`, a candidate
identity matching `[A-Za-z0-9_-]{1,128}`, valid key/fingerprint fields, and
non-zero 32-byte digests. Before Java is called it re-reads the live role, so
the candidate carries the exact role token, hop, port, connection, target,
host, key type, fingerprint, base digest and replacement digest. The native
callback slot claims a one-shot bit before invoking Java; duplicate or
re-entrant calls are rejected and poison the slot.

`SshKnownHostsCandidateHandoff` is the explicit managed receiver. It accepts
one `Evidence` plus the issuer candidate ID and body, requires the same
provider-owned `SnapshotReceipt`, verifies the body digest against the
replacement digest, and installs a private provider clone only after all
checks pass. `SshManagedProvider.ownsNativeCandidateProvider` requires that
receiver, proposal source and CAS writer are the same concrete
`SshManagedKnownHostsProvider`; an arbitrary provider, path, FD or Java object
cannot be retained by the native seam. The receiver is consumed on the first
attempt (success or failure), and `cancel`, `close`, expiry, duplicate, wrong
role/hop/evidence, empty/oversize/mismatched body or digest all fail closed.

The call is synchronous. Native wipes its bounded temporary/body on success
and every failure; JNI wipes the managed `byte[]` before releasing it; the
managed receiver wipes its input array in a `finally` block. For an over-limit
length, native wipes only the bounded maximum prefix because the API has no
unchecked capacity argument; an issuer must keep any bytes beyond that bound
in its own bounded scratch and wipe them before retrying. No payload, digest,
path or candidate body is logged.

This handoff acknowledges candidate installation only. It never returns
`COMMITTED`: the existing metadata-only HostEvidence path must still build the
exact proposal and complete the guarded `AppSecrets` update, and the managed
writer constructs `CasResult.COMMITTED` only after that update succeeds. A
failed or uncertain CAS closes the provider and returns no committed result.
Until a pinned hostfile issuer and its source/object manifest are linked into
the production caller, `ssh_native_client` remains fail-closed with `ENOTSUP`;
fixture OpenSSH objects stay out of the normal APK.

### 3b. Pinned hostfile issuer seam (fixture/host-only until caller closure)

This is the reviewed boundary for `ssh_hostfile_issuer.[ch]`. It is a
compile/link-tested issuer seam, not permission to add OpenSSH objects to the
normal APK or to claim a production SSH caller.

#### 1. Scope / trigger

Use it only when a pinned OpenSSH 10.6p1 hostfile parser must consume a
validated managed public view and produce an exact candidate for the dedicated
one-shot handoff. The source/object closure is built by
`research/android-hostfile-issuer-build.sh`; its output is opt-in fixture
material and must remain outside `compileSshFiles`.

#### 2. Signatures

```c
int ctos_ssh_hostfile_issue(const struct ctos_ssh_view *view,
    ctos_ssh_role_token role, const char *known_hosts_path, const char *host,
    const struct sshkey *host_key, const char *candidate_id,
    FILE *replacement_stream, unsigned char *replacement_body,
    size_t replacement_capacity, ctos_ssh_callback_token callback,
    void *environment, struct ctos_ssh_hostfile_issue *issue);
```

The pinned source-owned stream hook is
`ctos_hostfile_write_entry(FILE *, const char *, const char *, const struct
sshkey *, int)`. It wraps the upstream formatter only; it is not a pathname
writer.

#### 3. Contracts

- The view must be live, role/deadline/connection/host bound, rooted at the
  `CTOS_SSH_CONFIG` object `ssh/config`, and that root must explicitly refer to
  the `CTOS_SSH_KNOWN_HOSTS` object `ssh/known_hosts`. The issuer selects the
  object only through `ctos_ssh_view_lookup`; it never opens a vault path.
- Parsing uses pinned `load_hostkeys_file()` over a caller-owned memory stream;
  writing uses the exact upstream formatter through the project-owned stream
  hook. `add_host_to_hostfile`, `hostfile_replace_entries` and
  `hostfile_create_user_ssh_dir` are forbidden from the issuer.
- Non-certificate keys map upstream `HOST_OK/NEW/CHANGED/REVOKED` to stable
  `KNOWN/NEW/CHANGED/REVOKED`. Certificates remain project-policy
  `CERTIFICATE` (fail-closed), but `check_key_in_hostkeys()` must run first so
  a revoked certificate or revoked CA maps to `REVOKED` and cannot be hidden by
  the generic certificate denial.
- Before `load_hostkeys_file()`, run pinned `hostkeys_foreach_file()` on the
  same in-memory body with `HKF_WANT_PARSE_KEY` and record any
  `HKF_STATUS_INVALID` line. This is the project-owned `INVALID` result; it is
  not an upstream `HostStatus` member. Return bounded offered-key metadata and
  equal digests with no replacement body or handoff. A valid body with no match
  remains the upstream `NEW` result.
- `NEW` appends one upstream-formatted entry to a copy of the exact public
  body, computes base/replacement SHA-256, and calls the existing one-shot
  candidate handoff. The handoff owns wiping after the synchronous call;
  issuer failure wipes its bounded scratch and returns no issue.
- The runner pins the approved G1 archive/configuration/object hashes, the
  stream-writer patch, required PKCS#11/SK closure objects and complete input
  manifest. It must show an AArch64 PIE link and must not wire the fixture into
  the normal APK.

#### 4. Validation & error matrix

| Condition | Required behavior |
| --- | --- |
| View root is not `ssh/config`, wrong type, or does not refer to `ssh/known_hosts` | Reject `EACCES`; do not parse or hand off |
| View/role deadline, connection, host or candidate identity mismatch | Reject; wipe issue/body |
| Managed known_hosts body contains a parser-invalid line | Return project-owned `INVALID` with offered-key metadata, equal digests and no handoff; production caller rejects before host evidence |
| Certificate is not revoked | `CERTIFICATE`; fail closed by managed authority |
| Certificate or its signing CA is revoked | `REVOKED`; never downgrade to `CERTIFICATE` |
| Issuer attempts a pathname writer | Build/source check fails; no vault or host path is opened |
| Candidate callback rejects, cancels or expires | Abort the exact managed candidate, wipe native scratch and return failure; never report `COMMITTED` |
| Pinned manifest, object hash or AArch64 link closure differs | Runner fails before publishing a fixture result; normal APK remains unchanged |

#### 5. Good / Base / Bad cases

- Good: a live rooted public view, exact role metadata and pinned host key are
  classified from an in-memory `FILE *`; `NEW` formatting matches upstream and
  the one-shot receiver accepts the exact body.
- Base: the runner compiles and links the issuer as an AArch64 PIE and records
  `runtime=not-run` when no Android runtime mapping is available; this proves
  the source/object seam, not a live SSH session.
- Bad: use `/etc/passwd` or a vault pathname as parser input, accept a view
  whose root is unrelated to known_hosts, format a line in Java, classify a
  revoked certificate as generic `CERTIFICATE`, or link the issuer into the
  normal APK. All fail closed.

#### 6. Tests required

- `ssh_hostfile_issuer_test.c`: valid root graph, KNOWN/NEW/CHANGED/REVOKED,
  certificate and revoked-CA classification, root/path rejection, exact body
  digest, one-shot handoff wipe and callback-failure rollback. The production
  native-client host test additionally covers signed CERTIFICATE and malformed
  body INVALID classification/no-handoff with fail-closed EACCES.
- `android-hostfile-issuer-build.sh`: `bash -n`, fixed archive/patch/tree and
  object hashes, patched source application, strict C11/Werror compilation,
  AArch64 PIE link, required symbol checks, pathname-writer exclusion and
  normal-APK exclusion. A missing `qemu-aarch64` is recorded as
  `runtime=not-run`, never as a runtime pass.
- The production caller still needs a separate partial-I/O/EAGAIN/EOF,
  deadline/cancel/close-retry, KEX/NEWKEYS ordering and device/external-SSH
  gate before `ssh_native_client` may leave `ENOTSUP`.

#### 7. Wrong vs correct

Wrong:

```c
add_host_to_hostfile(vault_path, host, ip, host_key, 0);
```

Correct:

```c
FILE *memory = fmemopen(public_body, public_length, "r");
load_hostkeys_file(hostkeys, host, synthetic_name, memory, 0);
ctos_hostfile_write_entry(replacement, host, NULL, host_key, 0);
```

The correct path keeps the public view and replacement stream caller-owned,
uses upstream parsing/formatting, binds the root and role before classification,
and hands only the bounded exact body to the existing managed receiver.

### 3c. Pinned caller fixture seam (G3.4; not a production APK contract)

This contract applies only to the opt-in AArch64 source/object fixture that
combines the issuer, callback handoff and real pinned ssh_api packet loop.
It does not authorize adding OpenSSH objects to compileSshFiles, and it
does not clear the production ssh_native_client ENOTSUP gate.

#### 1. Scope / trigger

Use it to prove the caller ordering and ownership seam after the managed
known_hosts issuer/CAS contracts exist. The caller is host/fixture-only,
uses a same-process socketpair, and has no stock Options, pathname, argv,
ssh_connect, fork, exec, userauth, PTY, forwarding or service state. The
fixed runner links the issuer and caller as separate AArch64 PIE test
binaries against the approved OpenSSH 10.6p1 ABI baseline.

#### 2. Signatures

~~~c
int ctos_ssh_pinned_client_open(
    const struct ctos_ssh_pinned_client_config *,
    struct ctos_ssh_pinned_client **);
int ctos_ssh_pinned_client_step(
    struct ctos_ssh_pinned_client *,
    struct ctos_ssh_pinned_client_step *);
int ctos_ssh_pinned_client_complete(
    const struct ctos_ssh_pinned_client *);
int ctos_ssh_pinned_client_close(struct ctos_ssh_pinned_client *);
int ctos_ssh_pinned_client_destroy(struct ctos_ssh_pinned_client *);

int ctos_ssh_callback_known_hosts_candidate_abort(
    ctos_ssh_callback_token, void *environment);
~~~

The config binds a live role, owned SOCK_STREAM FD, future BOOTTIME deadline,
borrowed expected host public key/fingerprint, managed view and virtual
known_hosts selector, host/candidate identity, callback token/environment,
and bounded read/write chunks. The step result reports read/write bytes,
appended/consumed bytes, partial write and read/write would-block states.

#### 3. Contracts

- open revalidates role/PID/UID/deadline/host and view liveness, requires a
  valid owned descriptor and exact ssh/config to ssh/known_hosts view, then
  creates ssh_init, attaches the FD, sets nonblocking mode, binds the KEX
  guard and registers the verifier. Ownership begins only after attachment.
- Each step uses bounded recv(MSG_DONTWAIT) -> ssh_input_append ->
  ssh_packet_next, accepts only the fixture's expected packet boundary,
  and sends at most the configured output chunk with MSG_NOSIGNAL before
  calling ssh_output_consume. EAGAIN is progress-neutral; partial writes
  are observable; EOF, cancellation, clock failure and expired deadline are
  terminal failures.
- The verifier first calls the pinned issuer. Only KNOWN and NEW may continue
  to HostEvidence. The caller must compare the returned result with the
  issuer issue and current role snapshot for role/hop/port/classification/
  outcome/CAS/connection/target/host/key/fingerprint/base/replacement
  binding. CHANGED, REVOKED, CERTIFICATE and INVALID never enter KEX.
- If NEW was handed off and a later evidence, binding or protocol check fails,
  ctos_ssh_callback_known_hosts_candidate_abort releases that exact managed
  candidate. Abort is same-thread/environment restricted and idempotent.
  The temporary fmemopen is closed on both issuer success and failure;
  native issue/result/replacement storage is wiped on every return path. The
  bounded replacement scratch must not be an event-loop stack allocation, and
  the owned socket admission requires `FD_CLOEXEC|O_NONBLOCK|SOCK_STREAM`.
- After inbound NEWKEYS makes kex->done, the caller derives a non-zero
  32-byte session digest and invokes the KEX callback exactly once. close
  unbinds the guard before ssh_free; destroy accepts only a closed tombstone.
- The runner must pin the approved baseline/tree/object hashes plus stream
  writer SHA256
  a786566e1842996c0282647457f4dd63558cde00bc79ae1ba6bcd552bf30484a and
  KEX guard SHA256
  50a59606000dc871f1b29e33eeb6d77fb5da456f40526d2871357758b61f8785.
  It must check no undefined symbols, AArch64 PIE headers, required fixture
  symbols, forbidden stock caller symbols and normal APK exclusion; the
  exclusion check must inspect the formal `compileSshFiles` input closure
  rather than reject the separately guarded opt-in KEX fixture branch in the
  same Gradle file.

#### 4. Validation & error matrix

| Condition | Required result |
| --- | --- |
| Null/malformed config, stale role, wrong PID/UID/host/deadline/view or unowned FD | Reject; output client remains null and caller FD remains open |
| Blocking/non-owned/non-O_RDWR/non-CLOEXEC/non-SOCK_STREAM descriptor | Reject before OpenSSH attach |
| Read/write EAGAIN or bounded partial transfer | Return ordinary step with exact counters; never fabricate full progress |
| EOF, cancellation, clock failure or expired deadline | Terminal failure; later step is refused until close |
| Host classification CHANGED/REVOKED/CERTIFICATE/INVALID | Reject before KEX/callback completion |
| Host result role/hop/port/identity/key/fingerprint/digest/CAS mismatch | Reject; if NEW was handed off, abort exact candidate |
| Accepted NEW candidate followed by evidence/protocol failure | Idempotent candidate abort; no COMMITTED claim |
| KEX callback duplicate or zero session digest | Fail closed; completion is not successful |
| fmemopen/issuer failure | Close stream, wipe bounded scratch, return failure |
| Missing qemu-aarch64 or Android runtime | Record runtime=not-run; never report fixture runtime pass |

#### 5. Good / Base / Bad cases

Good: a real same-process socketpair completes pinned KEX after exact KNOWN
evidence, records a partial write and EAGAIN, and invokes one digest
callback. Base: the fixed runner proves strict compile/link/symbol/source
closure and records SSH_HOSTFILE_CALLER_BUILD gate=pass runtime=not-run
normal_apk=not-wired. Bad: link the fixture into the normal APK, trust only
the fingerprint, ignore the managed result binding, leave a candidate after a
later failure, or leak the temporary FILE on issuer error.

#### 6. Tests required

- The pinned caller test must use a real nonblocking socketpair and cover
  KNOWN success, CHANGED/REVOKED rejection, NEW candidate callback failure,
  NEW evidence failure with rollback, partial read/write, EAGAIN, EOF,
  cancellation, deadline, completion-once and close/destroy tombstone order.
- The callback host test must retain candidate abort coverage and the fixed
  SSH_CALLBACK_HOST gate value; the normal JVM/compile/lint/Release gate
  must still pass with no fixture object in libctos_ssh_files.so.
- The fixed runner must revalidate manifest/patch/object hashes, apply both
  source patches, compile with C11/Werror, link AArch64 PIE/no-undefined and
  preserve evidence when runtime is unavailable. Device, external SSH and
  production event-loop tests remain separate mandatory gates.

#### 7. Wrong vs correct

Wrong:

~~~c
if (fingerprint_matches(host_key))
    return ctos_ssh_kex_approved(ssh, fingerprint); /* no issuer/evidence */
~~~

Correct:

~~~c
issue = pinned_hostfile_issue(view, role, host_key, candidate_id);
result = ctos_ssh_callback_host_evidence(callback, environment, issue, &result);
if (!matches_issue_and_role(issue, result)) {
    if (issue.handed_off)
        ctos_ssh_callback_known_hosts_candidate_abort(callback, environment);
    return reject();
}
~~~

The correct shape is fixture evidence only until the same source/object
closure, production provider, Android runtime and complete protocol lifecycle
are separately approved and wired.

### 4. Validation & error matrix

| Condition | Required result |
| --- | --- |
| Null target, non-live role, wrong UID/PID, invalid deadline/environment | Open rejects; no live binding |
| Callback from another thread or another `JNIEnv`/`JavaVM` | Native `EPERM`; Java `SSH_CALLBACK_THREAD`/fail closed |
| Re-enter callback while one is in flight | Native `EALREADY`; Java `SSH_CALLBACK_REENTRANT`; binding poisoned |
| Cancelled, closing, closed, stale or expired role | Reject; no proof or credential effect |
| `NEW` without exact managed proposal/provider | `SSH_HOST_KEY_REVIEW` |
| `NEW` proposal lacks the exact role/hop binding | `SSH_HOST_KEY_REVIEW` |
| Changed/revoked/certificate/invalid host classification | Stable `SSH_HOST_KEY_CHANGED`/`REVOKED`/`CERTIFICATE`/`INVALID` |
| KEX before host proof or empty/wrong session binding | `SSH_KEX_REQUIRED`; no KEX proof |
| Credential before KEX / after KEX without provider | `SSH_KEX_REQUIRED` / `SSH_CREDENTIAL_PROVIDER` |
| Invalid credential kind, output pointer, length or capacity | Native `EINVAL`/`EOVERFLOW`; length zero and any valid bounded output wiped (an over-limit request only wipes the bounded maximum prefix) |
| Managed provider returns a different typed request | `SSH_CREDENTIAL_PROVIDER`; request closed; no receiver |
| Candidate handoff has wrong role/hop/evidence, identity, classification or digest | Reject with `EINVAL`/`SSH_HOST_KEY_REVIEW`; native and managed bodies are wiped |
| Candidate handoff is empty, oversize, duplicate, re-entrant, cancelled, late or expired | Reject; one-shot receiver/slot is poisoned; no provider candidate remains |
| Candidate accepted but guarded AppSecrets CAS fails or is uncertain | Close provider; return typed CAS failure; never report `COMMITTED` |
| Java exception or native release failure | Stable generic failure; no exception text/secret disclosure; cleanup retry remains possible |
| Successful native close followed by role release | Role deregisters exactly once; late callback rejects |

### 5. Good / Base / Bad cases

- Good: an already-issued role invokes known-host evidence and KEX completion
  synchronously on its bound event-loop thread, then obtains one matching
  typed credential through the managed provider and closes native state and
  the role in order.
- Base: the normal library compiles the adapter and bounded provider seam, the
  host state-machine/JVM tests pass, and the concrete file-view/CAS provider
  and real OpenSSH caller remain absent.
- Bad: invoking from a worker thread, using a control frame or terminal prompt
  as a callback broker, accepting a cached fingerprint, passing an unknown-key
  proposal through an unreviewed path, or handing a credential directly to a
  receiver/UI. All must fail closed.

### 6. Tests required

- Host `testSshCallbackAdapterHost`: strict `-Wall -Wextra -Werror` compile and
  assertions for thread/environment ownership, re-entry, cancellation,
  deadline expiry, concurrent cancel, cross-thread close, bounded credential
  output wiping, dedicated candidate transfer/duplicate/re-entry/oversize
  cleanup and cleanup retry; fixed output must include
  `SSH_CALLBACK_HOST ... gate=pass value=20`.
- `SshNativeCallbackAdapterTest` and `SshProductionBridgeTest`: known-host→KEX
  proof, every rejected host classification, exact role/hop-bound NEW review,
  cross-thread callback/close, re-entry, cancel/late callback, stable Java
  exception mapping, absent-provider fail-closed behavior, successful typed
  password/private-key bytes, wrong-kind/null-role rejection and native
  cleanup retry. Assert no receiver/vault path is touched on failure.
- `SshManagedKnownHostsProviderTest`: positive one-shot native candidate
  transfer, body wiping, wrong identity/base digest, duplicate/closed/expired
  lifecycle rejection, and failed/uncertain guarded CAS with no committed
  receipt.
- `:app:testReleaseUnitTest`, `:app:lintRelease`,
  `:app:assembleRelease` and strict `compileSshFiles` must pass. Inspect the
  normal APK and require the intentional production SSH library only; no
  KEX/control/probe/launcher fixture library or notice may enter it.
- These tests do not substitute for real event-loop, provider, device,
  external-SSH, authentication, PTY, forwarding or service validation.

### 7. Wrong vs correct

Wrong:

```java
if (uiConfirmed || promptText.contains("password")) {
    receiver.accept(secret);
}
```

Correct:

```java
nativeCallback.onNativeKexComplete(cycle, sessionDigest);
byte[] key = nativeCallback.onNativeCredentialBytes(CREDENTIAL_PRIVATE_KEY);
try { usePrivateKeyBytes(key); }
finally { VaultCodec.wipe(key); }
```

The bounded handoff is still only a prerequisite: its source must be the
managed public config/known-hosts view and exact CAS workflow, and the real
OpenSSH event loop must supply the host/KEX evidence before production
userauth. The normal APK does not yet contain that caller or full client.

## Role/hop-bound HostEvidence/CAS contract

This is a bounded native↔Java provider seam, not a production OpenSSH caller.
The native callback supplies the live role token, hop and target metadata
together with host classification, key type, SHA256 fingerprint, and 32-byte
base/replacement digests. Java reconstructs
`SshHostKeyAuthority.Evidence` only after matching that metadata to the issued
role.

### 1. Scope / trigger

Use this contract when a native SSH host-key event needs a managed trust
decision. It covers `ctos_ssh_callback_host_evidence`, the JNI callback
`onNativeHostEvidence`, `SshHostKeyAuthority`, and the
`SshManagedProvider.TrustCasWriter` seam. It does not authorize a concrete
known_hosts file view, a CAS implementation, user-interface approval, or an
OpenSSH event loop.

### 2. Signatures

```c
int ctos_ssh_callback_host_evidence(
    ctos_ssh_callback_token callback, void *environment, int classification,
    const char *key_type, const char *fingerprint,
    const unsigned char base_digest[32],
    const unsigned char replacement_digest[32],
    struct ctos_ssh_host_result *result);

int (*host_evidence)(void *context, void *environment,
    const struct ctos_ssh_host_evidence *evidence,
    struct ctos_ssh_host_result *result);
```

```java
byte[] onNativeHostEvidence(long nativeRoleToken, int nativeHop, int nativePort,
    String nativeConnectionId, String nativeTargetIdentity, String nativeHost,
    int classification, String keyType, String fingerprint,
    byte[] baseDigest, byte[] replacementDigest);

SshTrustProposal.CasResult write(
    SshHostKeyAuthority.Evidence evidence, SshTrustProposal proposal)
    throws VaultException;
```

`CasResult` has the bounded statuses `NOT_REQUIRED`, `COMMITTED`, and
`FAILED`; only `KNOWN` may produce `NOT_REQUIRED`, and only `NEW` may produce
the proposal-bound `COMMITTED` result consumed by the native callback.

### 3. Contracts

- The request is bound to a positive role token, active hop, port,
  connection ID, target identity, host, classification `KNOWN` through
  `INVALID`, key type, exact `SHA256:<43 base64 characters>` fingerprint, and
  two 32-byte digests. Native re-reads the live role snapshot and checks UID,
  PID, deadline, hop and port before invoking Java.
- The Java result is a big-endian, version-1 record with magic `CSHR`, approved
  outcome, zero flags, role/hop/port/classification/CAS status, six bounded
  ASCII length-prefixed fields, and the two exact digests. The total result is
  at most 2048 bytes. Only the proposal binding crosses JNI; proposal and
  known_hosts bodies remain in the managed provider.
- `KNOWN` returns an empty proposal binding and `CAS_NOT_REQUIRED`; it never
  accepts a proposal or writes trust. `NEW` requires an exact role/hop/target/
  key-type-bound proposal, explicit `UnknownKeyIssuer` approval, and an exact
  `CAS_COMMITTED` receipt from `TrustCasWriter`.
- C and JNI revalidate every returned field, including classification,
  key type, binding, status and digests. Cancellation, deadline races,
  callback errors and rejected results clear the result buffer.

### 4. Validation and error matrix

| Condition | Required behavior |
| --- | --- |
| Null/invalid input, malformed key type or fingerprint | Reject before Java/native provider use; `EINVAL` or a typed Java failure |
| Stale role, wrong UID/PID, inactive hop, expired deadline or metadata substitution | Reject closed; no host result is accepted |
| `KNOWN` with a proposal, non-empty binding or non-`NOT_REQUIRED` CAS | Reject as an invalid result |
| `NEW` without provider, exact proposal, explicit approval or CAS writer | Reject with `SSH_HOST_KEY_REVIEW`/`SSH_HOST_KEY_CAS` |
| Changed/revoked/certificate/invalid classification | Reject; `NOT_REQUIRED` is not a general bypass |
| Wrong classification, key type, hop, role, target, fingerprint or digest in the returned result | Reject with invalid/CAS failure and zero the result |
| Callback cancellation, deadline race or Java exception | Fail closed, clear native/JNI result bytes and retain no proposal |

### 5. Good / base / bad cases

Good: an active `KNOWN` hop returns exact metadata, both digests and
`CAS_NOT_REQUIRED`; an approved `NEW` hop returns the exact proposal binding
and `CAS_COMMITTED`. Base: the callback seam compiles and rejects missing
providers while no concrete file/CAS implementation is available. Bad: reuse a
proposal from another hop, change only the key type or classification, accept
approval without a committed CAS receipt, or send the known_hosts body through
JNI.

### 6. Tests required

Host C tests must cover role re-entry, cancellation/deadline cleanup,
credential-byte cleanup, `KNOWN`, approved `NEW`, malformed result fields and
callback failure; the current host gate is 15. JVM tests must assert the
bounded KNOWN/NEW wire, wrong hop, digest mismatch,
classification/key-type substitution, missing provider/approval, and exact
CAS failure; the current JVM gate is 166 tests. A normal Release build must
compile the production library, pass lint and all three ABIs while excluding
boundary/fixture OpenSSH objects from the APK.

### 7. Wrong vs correct

Wrong: treat a boolean host-key approval as trust persistence, derive a new
proposal from Java-side host text, or let native consume a result whose
metadata was not compared with the live event.

Correct: bind evidence to the issued role and hop, require the exact
key-type/fingerprint/digest-bound proposal and explicit approval for `NEW`,
validate the bounded CAS receipt in Java, then revalidate the same metadata and
digests in JNI and C before returning success.

This HostEvidence section is only the bounded callback contract; by itself it
does not prove a concrete provider or production caller. The managed
`SshManagedKnownHostsProvider`/AppSecrets CAS implementation is documented and
tested in the later managed-provider section, but it still does not provide the native hostfile
issuer/candidate handoff or the production OpenSSH caller.

## Production native-client boundary while the host provider is missing

This section records the 2026-10-15 fail-closed boundary. It is a production
facing admission contract, not a production OpenSSH caller: the source map has
identified the future `ssh_api` sequence, but there is still no compilable
native-facing hostfile issuer/candidate handoff for the managed
`config/known_hosts` view. The managed Java/AppSecrets provider and CAS writer
already exist, but they require caller-supplied exact replacement bytes. The
normal APK therefore must not construct a KEX client until the native-facing
issuer/handoff contract is available.

### 1. Scope / trigger

The host-only `testSshNativeClientHost` target compiles
`ssh_native_client.[ch]` with the role bootstrap and strict C warnings. It is
not part of `compileSshFiles`, does not link any OpenSSH object and is not a
transport fixture. The future production caller may only open after a live
role, an explicitly transferred nonblocking/CLOEXEC socket, a synchronous
callback binding and a strictly future deadline have all been admitted.

### 2. Signatures

```c
struct ctos_ssh_native_client_config {
    ctos_ssh_role_token role;
    int socket_fd;
    uid_t owner_uid;
    int owns_socket;
    ctos_ssh_callback_token callback;
    void *callback_environment;
    uint64_t deadline;
    size_t step_budget;
};

struct ctos_ssh_native_client_step {
    const unsigned char *input;
    size_t input_length;
    unsigned char *output;
    size_t output_capacity;
    size_t input_consumed;
    size_t output_consumed;
    int cancelled;
};

int ctos_ssh_native_client_open(
    const struct ctos_ssh_native_client_config *,
    struct ctos_ssh_native_client **);
int ctos_ssh_native_client_step(
    struct ctos_ssh_native_client *,
    struct ctos_ssh_native_client_step *);
int ctos_ssh_native_client_verify_host_key(
    struct ctos_ssh_native_client *, const void *opaque_host_key);
int ctos_ssh_native_client_close(struct ctos_ssh_native_client *);
```

`CTOS_SSH_NATIVE_CLIENT_MAX_STEP_BYTES` is 64 KiB. `owns_socket == 1` means
the future client may receive ownership only after the provider gate is
implemented; the current missing-provider path never consumes or closes the
descriptor. `callback_environment` is opaque and must remain the exact
environment captured by the synchronous attached JNI adapter.

### 3. Contracts

- `open` starts with `*client = NULL`, rejects null/zero/malformed metadata,
  verifies the live role against the current PID/UID and passed owner UID,
  requires exact role/deadline binding, and admits only `FD_CLOEXEC|O_NONBLOCK`.
- A valid admission is still stopped before `ssh_init`, FD attach,
  `ssh_packet_set_nonblocking`, verifier registration or packet dispatch when
  the provider is absent. It returns `-1` with `errno == ENOTSUP` and leaves
  the caller descriptor open.
- `step` validates cancellation, bounded input/output lengths and non-null
  buffers, clears both consumed counters, then returns the same provider-missing
  error without reading input or writing output. No fake partial-I/O result is
  allowed.
- `verify_host_key` never derives trust from the opaque key or its fingerprint;
  until a role-bound `KNOWN`/`NEW` provider result and exact CAS contract exist,
  it returns `ENOTSUP`. `close` cannot report success for a client that was
  never created.
- This boundary does not call `ctos_ssh_kex_*`, callback/JNI functions or any
  credential release path. It does not classify host keys, issue proposals or
  mutate the vault.

### 4. Validation and error matrix

| Condition | Required result |
| --- | --- |
| Null config/output, zero role/callback, missing environment, non-owned socket or invalid budget | `EINVAL`, output client remains `NULL` |
| Non-CLOEXEC or blocking socket | `EINVAL`, descriptor remains caller-owned |
| Wrong role, owner UID, PID/UID or deadline binding | `EACCES`, no client and no descriptor close |
| Valid admission while provider/CAS is unavailable | `ENOTSUP`, no OpenSSH/KEX/JNI/credential call |
| Null/cancelled step, null bounded buffer or over-limit length | `EINVAL`, no payload access |
| Bounded step after provider gate | `ENOTSUP`, consumed counters reset to zero |
| Host verifier without a provider | `ENOTSUP`, no host classification or trust |
| Close before a client exists | `ENOENT`/`EINVAL`, no native release |

### 5. Good / Base / Bad cases

Good is a strict admission rejection with stable `ENOTSUP`, a null client,
zero consumed counters and the caller socket still open. Base is the host
gate only: `SSH_NATIVE_CLIENT` admission 27, provider 1, cleanup 1 and gate
27. Bad is constructing `struct ssh` before a provider exists, accepting a
fingerprint as `KNOWN`, inventing `NEW` proposal/base/replacement digests,
calling blocking stock `ssh_connect`/`ssh_login`, or closing a caller FD on
the missing-provider path.

### 6. Tests required

`testSshNativeClientHost` must continue to compile with
`-std=c11 -Wall -Wextra -Werror -pthread` and cover null/ownership/role/
PID-UID/deadline/socket-flag/step-boundary/cancel/provider-missing and
non-consumption cleanup cases. The fixed output must retain the four
`SSH_NATIVE_CLIENT` records and gate value 27. The normal build must also
pass `testReleaseUnitTest`, `lintRelease`, `assembleRelease`, strict
`compileSshFiles`, and APK inspection must show no boundary or fixture
OpenSSH object.

After the provider contract is frozen, add separate real-socket regression
for partial input/output, EAGAIN, EOF, BOOTTIME deadline, cancellation,
close-retry, inbound NEWKEYS/session digest and synchronous JNI proof. Until
then, absence of those tests is an open gate, not a skipped pass.

### 7. Wrong vs correct

Wrong:

```c
fingerprint = sshkey_fingerprint_raw(host_key);
return ctos_ssh_kex_approved(ssh, fingerprint); /* guessed trust */
```

Correct:

```c
if (!managed_provider_has_exact_host_evidence(role, host_key, &proposal))
    return reject(ENOTSUP);
return ctos_ssh_callback_host_key(callback, environment,
    proposal.classification, proposal.key_type, proposal.fingerprint,
    proposal.base_digest, proposal.replacement_digest);
```

The second snippet is a future contract shape, not an existing callable
provider. It must be implemented only after the managed file view and CAS
writer provide exact role/hop-bound evidence; the current boundary deliberately
stops before it.

## Managed known_hosts provider and AppSecrets CAS contract

This section is the concrete managed-vault increment layered on the
HostEvidence/CAS callback above. It provides a real AppSecrets writer, but it
still does not provide the native OpenSSH hostfile parser/issuer or a caller
that can produce exact replacement bytes.

### 1. Scope / trigger

Use this contract when a managed KNOWN_HOSTS object must be reviewed and
updated for one active SSH role/hop. It covers
`SshManagedKnownHostsProvider`, `SshVaultAdapter`, `VaultOperation`, and the
`AppSecrets` trust CAS. It is not a prompt/terminal-output parser, a
fingerprint-to-file formatter, or an authorization to pass a proposal body
through JNI.

### 2. Signatures

```java
static SnapshotReceipt snapshotFor(SshVaultAdapter adapter, SshRoleContext role,
    SshRoleContext.Clock clock, String rootPath, String path, String objectId,
    String objectIdentity) throws VaultException;
static KnownHostsCandidate candidateFor(SnapshotReceipt snapshot,
    SshHostKeyAuthority.Evidence evidence, byte[] exactReplacement)
    throws VaultException;
static SshManagedKnownHostsProvider bind(SshVaultAdapter adapter,
    SnapshotReceipt snapshot, KnownHostsCandidate candidate) throws VaultException;

SshTrustProposal proposalFor(SshHostKeyAuthority.Evidence evidence,
    SshHostKeyAuthority.Proof proof) throws VaultException;
SshTrustProposal.CasResult write(SshHostKeyAuthority.Evidence evidence,
    SshHostKeyAuthority.Proof proof, SshTrustProposal proposal)
    throws VaultException;

VaultOperation trustRequest(SshRoleContext role,
    SshHostKeyAuthority.Evidence evidence, SshHostKeyAuthority.Proof proof,
    SnapshotReceipt snapshot, SshTrustProposal proposal) throws VaultException;
void updateTrust(VaultOperation request, SshRoleContext role,
    SshHostKeyAuthority.Evidence evidence, SshHostKeyAuthority.Proof proof,
    SnapshotReceipt snapshot, SshTrustProposal proposal,
    AppSecrets.EffectGuard guard) throws VaultException;
```

`exactReplacement` is bounded by the manifest file limit. The public
`CasResult` contains only a status and exact evidence/proposal binding;
replacement bytes remain managed and are never a JNI payload.

### 3. Contracts

- `snapshotFor` selects exactly one public `KNOWN_HOSTS` object matching
  path, object ID and identity. It retains only metadata, the vault UUID,
  generation, session epoch, deadline, snapshot binding and base digest.
  The consumed `SSH_FILES` request is cancelled after the synchronous copy.
- `KnownHostsCandidate` owns one clone of caller-supplied exact replacement
  bytes. Its replacement digest must equal the NEW evidence digest and its
  binding must match the snapshot, role, hop, target, host, port, key type
  and fingerprint. It is consumed once when `proposalFor` builds the exact
  object-bound proposal.
- The proof-aware writer must reject the legacy unbound overload. It creates
  a new AppSecrets trust operation, binds role and trust proof, checks the
  current vault UUID/generation/epoch/binding, and invokes the final guarded
  `AppSecrets.updateTrust`. `COMMITTED` may be constructed only after that
  method returns successfully.
- `SshManagedProvider.writeBack` must validate `CasResult.matches(evidence,
  proposal, COMMITTED)` while the proposal is still open, then close the
  proposal in `finally`. Closing first wipes the proposal binding, so even a
  successful guarded CAS becomes an incorrectly rejected/expired receipt.
- `AppSecrets.updateTrust` rechecks file type, ID, path, identity and the
  base digest against the current manifest before committing the replacement.
  Approval and the SSH validator are checked at admission and in the final
  effect guard.
- Cancellation must not wait for the vault monitor while `Store.commit` is
  blocked. The operation table is concurrent; `cancelOperation` revokes the
  operation first and then removes the exact map entry.

### 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Missing/ambiguous object, wrong type, ID, path or identity | Reject with `VAULT_OPERATION`/`VAULT_CONTENT`; retain old vault bytes |
| Closed, expired, cancelled or inactive role/snapshot/candidate | Reject fail-closed; wipe owned digests/body |
| Candidate bytes exceed manifest limit or digest differs from evidence | Reject `SSH_HOST_KEY_REVIEW`; do not create proposal |
| Wrong hop/role/target/host/port/key type/fingerprint or object binding | Reject `SSH_HOST_KEY_REVIEW`/`VAULT_OPERATION` |
| Missing approval, validator or proof | Reject `VAULT_TRUST_REVIEW`/`SSH_HOST_KEY_CAS`; no commit |
| Vault UUID, generation, epoch, snapshot binding or old base digest changed | Reject `VAULT_CHANGED`/`SSH_HOST_KEY_CAS`; preserve old record |
| Store commit fails or returns uncertain | Do not report `COMMITTED`; preserve uncertainty/lock semantics |
| Cancel races with a store commit | Revoke without waiting for the vault monitor; never deadlock the caller |

### 5. Good / Base / Bad Cases

Good: one exact managed KNOWN_HOSTS object is snapshotted, an exact candidate
matches the NEW evidence digest, approval is repeated, and the guarded CAS
returns `COMMITTED`.

Base: an exact snapshot/candidate/proposal can be tested with an in-memory
caller-supplied replacement; this proves the vault writer seam, not an
OpenSSH hostfile writer.

Bad: derive replacement bytes from a fingerprint, choose the first file with
the right path but wrong ID/identity, return `COMMITTED` after approval alone,
retain a consumed SSH_FILES operation, or take the AppSecrets monitor while
waiting for a store commit.

### 6. Tests Required

- Provider JVM tests must assert exact object selection, base/replacement
  digest binding, one-shot candidate/proposal consumption, wrong role/hop/key
  type/classification, stale generation/epoch, missing approval/validator,
  missing candidate/proof and cleanup after failure.
- AppSecrets/VaultOperation tests must assert old bytes remain on every
  rejected or failed CAS, successful commits replace only the exact object,
  and after-claim cancellation can return without waiting for a blocked
  store callback while uncertain-commit semantics remain intact.
- The latest managed known_hosts/candidate handoff gate is 177 tests with zero
  failure/error/skip; callback host is 20 and the native-client
  missing-provider gate remains 27. The earlier provider-only 174/15 gate is
  historical. Release compile, lint, all three ABIs and APK inspection must
  show no host-only or fixture OpenSSH object.

### 7. Wrong vs Correct

#### Wrong

```java
// Approval is not a write, and a fingerprint is not a candidate body.
return CasResult.committed(evidence, proposal);
```

#### Correct

```java
VaultOperation request = adapter.trustRequest(role, evidence, proof, snapshot, proposal);
adapter.updateTrust(request, role, evidence, proof, snapshot, proposal, guard);
return CasResult.committed(evidence, proposal); // only after updateTrust returns
```

The correct path keeps body bytes in the managed provider, binds the operation
to the current vault snapshot, and makes cancellation independent of the
AppSecrets monitor. It still must not be called a production OpenSSH caller
until the real hostfile/parser path supplies the exact candidate.

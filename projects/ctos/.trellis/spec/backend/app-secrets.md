# App secrets host contract

2026-10-25 当前用户决定：[一个正式SSH模型与writer](ssh-manifest.md)。所有构造/create/
重新编码使用最新metadata模型，无instance格式选择。旧empty/纯TOTP输入只读适配同一
模型，保留raw认证来源；rekey/import单独编码并wipe，export/quick wrapper可保留原cipher。
恢复逐侧auth后/validator前检查实际关系/账号metadata审阅缺口，bind亦防御，纯TOTP/
empty不按tag拒绝。原source-first/provider/guard/所有权不变；覆盖下方历史格式政策。

2026-10-24：[证书事实规范](ssh-certificate-facts.md)新增全CERT/CSCF168公开事实和嵌入签名政策；CA/time/principal/revocation仍未验证，不改变生产证书拒绝/完整provider/恢复门。

2026-10-23：[全部private与凭据归属](ssh-private-material.md)新增CSKP实际self-proof及v1元数据唯一owner扫描；不改变v1/生产provider/恢复，完整graph/password account/trust仍待完成。

2026-10-23：新增[显式key pair规范](ssh-key-association.md)，精确supplied IDs/口令与plain subject的固定本地签名证明；旧CSMI/auth不变，不作为完整validator/信任/恢复准入。

## TOTP mutation and preview origin (2026-10-23)

### 1. Scope / Trigger

Follow [TOTP mutation lifecycle](../../../design/totp-mutation-lifecycle.md) for all TOTP
write operations and host preview handoff. Focused196/full483 JVM tests pass; see [dated evidence](../../../design/verification.md#20261023-totp-mutation).
The scope includes Bridge request timing and opaque pending publication, not parser/UI changes.

### 2. Signatures

`TotpMutationOrigin` captures prequeue owner/epoch/deadline/original guard/validator registration.
Each admission uses its own child context and opaque `VaultOperation` state. Original capture
and up to16 candidates share the first authenticated source binding/revision/mixed fact.
`checkTotpMutationOrigin(origin)` validates authority before manager replacement.
`bindTotpPreview(origin,ticket,candidates)` binds exact issued operations;
`publishTotpPending(origin,ticket,transport)` publishes that opaque group, not snapshot scalars.

### 3. Contracts

Source/candidate/validator loans own separate arrays; slow work runs outside core/session and
preview-manager locks. Pure TOTP has no SSH validator dependency; mixed checks the original
registration through source, candidate, pending publication and effect. Add-only absence is
checked against the same authenticated effect source; rename preserves identity and seed.
The original Request guard applies until successful preview handoff. Retained ticket authority
survives that Request finishing, preserves its budget, and can be cancelled independently.
The new commit Request supplies the only durable effect.beforeCommit claim. No extra phase.

### 4. Validation & Error Matrix

Wrong binding/replay is rejected; valid BUSY does not consume the original operation.
Cancel/expiry/stale raw snapshot or mixed registration rejects before effect/publication.
Failed preview transport revokes its exact retained ticket; late workers cannot replace or
cancel newer tickets. Before replacement, reject foreign owner and stale epoch/pinned revision;
manager comparisons use safely published pin metadata and never acquire session.
Uncertain disk commit locks; no safe-retry promise or rollback claim.

### 5. Good / Base / Bad Cases

Good: publish metadata, finish original Request, then confirm a selected candidate with a new
Request before the original deadline. Base: rename pure TOTP without a registered SSH validator.
Bad: fresh120second candidate budget after image return, or binding effect to old Request.active.

### 6. Tests Required

Paused pure/mixed source and candidate stages with cancellation/lock/expiry/registration/store
changes; owned arrays/Throwable cleanup; add-only and namespace checks; exact handoff and
transport failure; pending no-storage publication; capture cleanup versus group cancellation;
unique claim, wrappers and uncertain handling. Host tests do not prove Android Activity runtime.

### 7. Wrong vs Correct

Wrong: call core validation while holding TotpPreviews, or take publication after a guard already
holds session. Correct: reserve/detach briefly, keep worker-owned seeds, and publish with original
phase→publication→session ordering; synchronous metadata invalidation precedes queued cleanup.


## Trust CAS lifecycle (2026-10-22)

### 1. Scope / Trigger

Follow the [trust CAS contract](../../../design/ssh-trust-cas-lifecycle.md) for exact NEW
known_hosts updates. Focused202/full469 JVM pass; see the
[dated evidence](../../../design/verification.md#20261022-ssh-trust-cas). No production semantic provider or
restore gate is enabled by this lifecycle migration.

### 2. Signatures

`SshTrustContext` pins owner, proposal identity, epoch, deadline and validator registration.
`beginTrustUpdate(proposal, context, authorization)` issues the once-use trust operation;
`updateTrust(operation, proposal, effectGuard)` retains the original authority.

### 3. Contracts

The production context precedes external proposal review and inherits the minimum original
role/snapshot deadline. Provider monitors cover reservation/detachment only, not validators.
Source, candidate and callback loans own independent arrays. Authentication, validation and
crypto run outside core; final raw equality/wrapper reads precede the Store transaction.
Store guards remain bounded metadata checks; original effect.beforeCommit is the sole claim.
Only the exact attached public view may survive own-CAS; never restore a revoked loan/key.
Validation authorization linearizes at the final core continuation fence. Later receipt checks
delivery liveness; later registration changes alone do not retroactively undo that commit.

### 4. Validation & Error Matrix

Wrong binding/replay is rejected; accepted effect consumes once, valid BUSY does not consume.
Cancellation before admission issuance must invalidate the pre-review context. After confirmed
persistence, failed continuation or receipt delivery locks/revokes and reports
VAULT_COMMIT_UNCERTAIN. This never promises a safe retry or rollback.

### 5. Good / Base / Bad Cases

Good: the approved base is replaced while preserving unrelated entries and the exact view.
Base: legacy source-only admission still compares the actual base during effect.
Bad: renewing the budget after review, shallow candidates, or resetting the own-CAS session.

### 6. Tests Required

Paused validators with close/lock/expiry/registration invalidation, proposal identity, loan
mutation/Throwable cleanup, once consumption/BUSY, exact view continuation, bounded commit
guards, wrapper preservation, and persisted-but-undeliverable receipts. Actual AtomicFile
runtime behavior is not established by MemoryStore tests.

### 7. Wrong vs Correct

Wrong: an external guard after the final metadata check but before epoch continuation.
Correct: run external checks first, then check authoritative metadata and advance under the
same session fence, accepting only this commit's own revision change. Postcommit failure is
uncertain; precommit failure retains its original error. Existing Store Error/RuntimeException
rollback limitations remain outside this increment.


## Ordinary SSH entry mutation lifecycle (2026-10-22)

### 1. Scope / Trigger

SSH_SAVE/SSH_DELETE follow the [entry mutation contract](../../../design/ssh-entry-mutation-lifecycle.md).
Focused152/full455 JVM and local build gates pass; see
[dated evidence](../../../design/verification.md#20261022-ssh-entry-mutation).
TOTP mutation still uses its existing helpers; trust CAS follows the newer section above;
own-NEW-CAS continuation must not be replaced with ordinary mutation's session reset.

### 2. Signatures

`newSshEntryMutationContext(deadline, guard)` creates original metadata authority;
`beginSshSave(id,target,connection,context,authorization)` and
`beginSshDelete(id,target,context)` retain it in opaque `VaultOperation.sshEntryMutationState()`.
`SshVaultAdapter.mutationContext(deadline,guard)` and explicit save/delete request overloads
let future callers preserve pre-queue budgets; default save context precedes host approval.

### 3. Contracts

Original owner/epoch/deadline/registration/context guard and admission host authorization remain
bound. Save capability approves id/target/connection, not a future secret/label/type supplied
only at effect. Source and complete candidate use independent canonical validator loans;
candidate owns all arrays, including retained TOTP/file objects. Pure-TOTP source skips SSH
source validation, but first/last-SSH candidates always require the registered validator.
Capture is short; authenticate/validate/candidate/encrypt/readback are outside core. Final core
serializes quick-wrapper capture, raw equality and the existing Store transaction. Store guards
never reopen storage. Context is continuous check/publication only; effect.beforeCommit is the
sole durable claim, exactly once. Preserve old id/generation/salt, wrapper and success/uncertain
session behavior. No late postcommit snapshot check against the revision just advanced by self.

### 4. Validation & Error Matrix

- Wrong owner/purpose/id/target/connection or valid effect BUSY: no consumption.
- Accepted effect: consume/remove once; failures cannot replay.
- Cancel/expiry/registration/revision/raw-store changes: reject before durable claim.
- Cross-type or wrong-target overwrite/delete: reject; original state retained.
- Candidate/loan modification or validation failure: wipe temporary owners, no commit.
- `VAULT_COMMIT_UNCERTAIN`: preserve lock/operation/preview invalidation, no safe-retry claim.

### 5. Good / Base / Bad Cases

Good: replace a password while preserving unrelated TOTP/files and wrapper bytes.
Base: remove the final SSH entry and validate the resulting pure-TOTP candidate.
Bad: shallow candidate aliases, reusing a fake bundle operation, or validating only source.

### 6. Tests Required

Paused source/candidate invalidation, host-gate budget, independent loan/Throwable wipe,
BUSY/reentry/replay, first/last SSH, cross-type/target refusal, final-read/claim seams,
transaction reread prohibition, wrapper preservation and uncertain-state locking.
JVM MemoryStore results do not establish Android AtomicFile runtime behavior.

### 7. Wrong vs Correct

Wrong: `guard.check()` instead of `beforeCommit()`, or `protectedEffect()` inside Store.commit.
Correct: delegate the effect's original beforeCommit once, surround it with metadata checks,
and perform raw equality/quickMaterial reads before entering the transaction. Existing
VaultStore RuntimeException/Error failWrite limitations remain outside this scoped migration.

## SSH typed secret read lifecycle (2026-10-22)

### 1. Scope / Trigger

Credential, private-key and encrypted-key-bundle reads follow the
[secret-read contract](../../../design/ssh-secret-read-lifecycle.md). This scenario supersedes
legacy synchronized secret-read descriptions below; mutations/trust CAS remain separate.
Focused196/full444 JVM and local build gates pass; verification and platform limits are recorded
in [the dated evidence](../../../design/verification.md#20261022-ssh-secret-read).

### 2. Signatures

`newSshSecretReadContext(deadline, guard)` creates metadata-only original authority;
the typed `beginSshCredential`, `beginSshPrivateKey`, `beginSshPrivateKeyBundle` overloads
accept it. `VaultOperation.sshSecretReadState()` carries opaque owner/context/revision/type.
`SshCredentialBridge.openSelection(role, host, selection, kex, effectGuard)` receives the
production callback's original guard before admission. `SshRoleGuard.withRole(publication)`
separates the final role/view fence from outer `EffectGuard.publish`.

### 3. Contracts

Capture real ciphertext/key/revision under short core/session fences; authenticate, decode,
independently validate canonical loan and prepare secret bytes outside core. Original role
deadline and view never renew. One accepted effect consumes/removes its exact operation;
cleanup retains the single in-flight slot until all work buffers are wiped. Last raw store
equality precedes publication; revision/registration/epoch/deadline are rechecked inside it.
Final bounded copy order is original callback guard → request → publication → session →
operation → role → actual view. No core reentry, native work or blocking receiver in this
fence. Preserve `publish`, not just `check`. Public snapshot role fencing uses the same
inner ordering but its public receiver remains outside locks. Cleanup stays outside fences.
Public native attach holds operation only for `attachRevocation`, before inner role checks;
never across lease liveness/session attachment, so cancellation can revoke immediately.
All five operation registration sites share the publication-fenced capacity check/put;
bundle registration takes publication before session to avoid inversion.

### 4. Validation & Error Matrix

- Cancelled context/operation/request → cancellation error, no new receiver.
- Expired original budget or closed role/view → expiry/view rejection, no fresh budget.
- Registration/revision/raw ciphertext changes → `VAULT_CHANGED`.
- Wrong owner/purpose/resource/target/connection/type → operation denial without consumption.
- Valid effect while another secret read owns the slot → `BUSY` without consumption.
- Accepted failed effect or duplicate delivery → no replay; guard/loan/receiver errors wipe buffers.

### 5. Good / Base / Bad Cases

Good: selected encrypted key binds exact path/identity/passphrase entry/remote and original KEX.
Base: legacy native-only typed entry uses one default budget and still requires release guard.
Bad: using a public loan claim to release secrets later, or treating a completed clone as
proof that a subsequent JNI/native observation remains authorized.

### 6. Tests Required

Paused admission/effect invalidation, loan mutation/Error cleanup, wrong bindings/BUSY/replay,
last-store/publication gap, final receiver ordering against cancel/lock/role/view, exact
selected proof changes, provider clone cleanup, and public/known_hosts own-CAS regressions.
Host JVM fences do not establish Android JNI execution.

### 7. Wrong vs Correct

Wrong: wrap the original guard as `() -> parent.check()` or hold role before publication.
Correct: preserve outer `parent.publish`, then enter role/view via `withRole` after
publication/session/operation; perform only the trusted bounded copy there.

2026-10-22: [SSH public reads](../../../design/ssh-public-read-lifecycle.md) use original
metadata contexts and detached authenticated source/validator loans/closure. Synchronous public
receivers run outside locks after one metadata claim; native leases use a separate prepare/create/
final attach fence. Existing-role snapshots retain the exact role/view and original role deadline.
Published leases keep existing exact operation/session revocation and own-NEW-CAS continuation;
The typed secret scenario above supersedes legacy secret reads; mutations/trust and other
sessionManifest consumers remain separate.

2026-10-22: [TOTP typed reads](../../../design/totp-read-lifecycle.md) now use original
metadata-only contexts, detached authenticated source/validator loans/generation and opaque
once-reserved read receipts. Pure TOTP does not depend on SSH providers; mixed reads retain
their immutable registration through actual disclosure. Read-specific phase→publication→session
ordering preserves the existing guarded list/clipboard disclosure boundary. Management previews,
mutations and SSH typed/trust paths remain separate; no production semantic hook is registered.

2026-10-22: [Bundle lifecycle](../../../design/ssh-bundle-lifecycle.md) now detaches bundle
admission/effect validation and crypto from core. One original cancellable BundleContext,
immutable validator registration, independent candidate/loan arrays and once-only accepted
effect capture bind the final store. In-transaction guards must never reopen AtomicFile.
The scenario below defines the new API; other typed/sessionManifest/trust paths remain legacy.

2026-10-21: [Preview validation lifecycle](../../../design/ssh-validation-lifecycle.md)
moves only import-preview authentication, supplied-input validator loans, restore review and
canonical binding outside the core monitor. Immutable validator registrations, original
admission deadline, direct request invalidation and one-shot metadata delivery claims bind
preview publication/commit. See the focused scenario below. Other typed/bundle/sessionManifest
callbacks other than the subsequently migrated bundle still hold the core monitor. No production semantic validator/reviewer is registered;
SSH restore remains closed. Earlier whole-callback lock descriptions remain true for those
unconverted paths, not the new preview path.

2026-10-19: [Offline material inspection](../../../design/ssh-manifest-inspection.md) examines
the actual supplied manifest with owned temporary copies. It never enters current vault,
creates a lease or implements SshContentValidator; CONFIG/reference/identity/trust semantics
remain pending and Report.admissionComplete is always false. The legacy callback remains
synchronous under the core monitor for unmigrated typed paths; preview and bundle fence their
callbacks outside that monitor. Full integration still requires all caller migrations and
complete semantic/restore proof. Missing phrase/ambiguous association or
PARSED material is not whole-manifest admission. Production restore gate remains closed.

2026-10-19: [SSH declaration catalog](../../../design/ssh-config-catalog.md) is lexical
metadata from a current public lease. It is not SshContentValidator and cannot certify a
different source/target/candidate manifest, private key, trust or reference binding.
Production attachSshValidator is still absent; SSH import/restore admission remains closed.
Unlock alone does not perform SSH semantic validation. An active public lease makes a
second attachRevocation fail BUSY; it does not replace/revoke that active lease. Snapshot
closure follows declared manifest references, not newly discovered parser Include paths.

2026-10-19: exact role-bound NEW known_hosts CAS may advance epoch while retaining
only its same already attached active public loan; lock, arbitrary mutation,
re-unlock and original file-operation cancellation still revoke. No deadline or
key is extended/restored. See the seven-part own-CAS contract in ssh-native.md.
Selected encrypted private keys use one SSH_PRIVATE_KEY_BUNDLE operation binding
path/key identity/passphrase entry/actual remote/connection and CSASv2 receipt;
CSKBv1 total <=1MiB. Final host/KEX/role guards run after the last store read and
immediately before any credential, key or bundle receiver. Generic passphrase
target identity is never substituted for the actual remote target.

The SSH public file loan now has a native-only SshPublicFileLease: no private
body in its explicit bounded wire, opaque singleton native ownership, and
revocation bound to both the exact VaultSession epoch and originating
VaultOperation. Cancel after publication revokes that view; an old operation
cannot attach a replacement view. Revocation callbacks must not re-enter
AppSecrets or acquire session locks while holding lease/operation locks.
The managed KNOWN_HOSTS provider/CAS writer accepts the exact native hostfile
candidate through the owned handoff. Production native KEX/service and password/
private-key authentication now call these guarded providers. The isolated Android
fixture uses actual AppSecrets encrypted MemoryStore, native loan, role and JNI;
this is separate from production-vault, account, UI or full-client acceptance.
See [SSH native contract](ssh-native.md) and dated design evidence.

## 1. Scope / Trigger

Use for `AppSecrets*`, `AppAuthentication`, `Vault*`, TOTP/SSH adapters and their
MethodChannel consumers. Product authority is [design/app-authentication.md](../../../design/app-authentication.md).
This describes the 2026-10-10 implementation, not completed device acceptance.
No exported cross-App API, Root access or plaintext disk staging is introduced.

## 2. Signatures

`MainActivity` forwards `secrets.*` on `ctos/native` to `AppSecretsBridge`.
`AppSecretsOwner.acquire(applicationContext)` owns one process-wide core; an
Activity owns only its UI/auth bridge. Service consumers must reuse that core.
The package-private `AppSecretsBridge(Activity, AppSecrets, SharedPreferences,
VaultExportJournal)` overload accepts scoped dependencies for native integration
checks. It runs the same lifecycle/request implementation. Only the production
single-argument constructor acquires the production owner/settings/journal and
queues startup `cleanupDisabledKeys()`; this seam is not an exported API or an
alternative vault format.

| Method | Arguments |
| --- | --- |
| `secrets.status`, `secrets.lock` | none |
| `secrets.settings` | integer `backgroundTimeoutSeconds` |
| `secrets.create` | `requestId`, `password`, `confirmation` |
| `secrets.unlock` | `requestId`, `password` |
| `secrets.changePassword` | `requestId`, `currentPassword`, `newPassword`, `confirmation` |
| `secrets.quickUnlock` | `requestId`, `action=enable/unlock/disable`; password for enable/disable |
| `secrets.exportPreview` | `requestId`, `password`, bounded `fileName`; owned SAF tree-picker flow |
| `secrets.exportCommit` | new `requestId`, opaque `exportId`, Boolean `overwrite` |
| `secrets.exportCancel` | new `requestId`, `exportId`; returns terminal cleanup receipt |
| `secrets.exportRecover` | new `requestId`, `recoveryId`; explicit user recovery and same-tree reauthorization |
| `secrets.importPreview` | `requestId`, `sourcePassword`, existing target's `targetPassword`; owned SAF flow |
| `secrets.importCommit` | new `requestId`, `previewId`, `reviewBinding`, Boolean `confirmTrust` |
| `secrets.importCancel` | `previewId` |
| `secrets.cancel` | `requestId` |
| `secrets.protect` | `action=acquire`, or `action=release` plus opaque `owner` |

Native-only `VaultOperation` binds `Consumer`, `Purpose`, resource, actual target,
connection ID, vault UUID/material generation, session epoch, monotonic deadline
and ciphertext snapshot digest. It is once-used, cannot be constructed through
the channel, and is checked again at the actual release/store boundary.

`SshVaultAdapter.useFileSnapshot(operation, rootPath, connectionId, receiver)`
loans the requested file closure synchronously. `SshFileSnapshot.metadata`
contains immutable type/ID/path/identity/length/references for every selected
object, including private-key metadata. `publicFiles` contains only CONFIG,
PUBLIC_KEY, CERTIFICATE and KNOWN_HOSTS bodies. Both lists follow the same
snapshot/epoch/deadline/connection/once-use checks as useFiles; all borrowed
Java content is wiped on callback return or throw. The synchronous receiver may make
managed owned public copies with their own lifecycle; production native leases instead use
the separate exact prepare/create/final-attach path below. A generic receiver is not the
lease-publication boundary. Metadata and
the ciphertext binding are descriptions, never private-key release authority.
Private-key bodies still require privateKeyRequest/usePrivateKey and the final
actual-hop host guard. The bounded native callback seam may use only the
proof-bound `SshCredentialBridge` overloads; it must recheck the requested kind,
copy the transient bytes into caller-owned memory and wipe both managed and
native buffers on failure. Do not expose this snapshot through MethodChannel or
treat these JVM-only boundary checks as registration of the production SSH
config/known-hosts provider.

SSH file graph writes use `SshVaultAdapter.bundleRequest(SshBundleProposal)` (or the explicit
original BundleContext overload described below) and
`commitBundle(VaultOperation, SshBundleProposal, EffectGuard)`, with
`Purpose.SSH_BUNDLE_WRITE`. Proposal `Change.add/replace/delete` records full
old/new object bindings (type/ID/path/identity/references/content digest). The
proposal owns bounded deep copies, canonical path-sorted whole-change digest,
nonsecret `summary()` and only typed `knownHostsContent(path)` for the native
trust reviewer. No generic private-key getter. Owners close inputs/proposals and
wipe returned trust clones. Closed proposals cannot request or commit.
For the managed known_hosts CAS path, receipt validation occurs while the
proposal is still open; `writeBack` closes it in `finally` on both success and
rejection, and no `COMMITTED` receipt is exposed before the guarded AppSecrets
CAS succeeds.

Native restore hooks are `AppSecrets.attachSshRestoreReviewer(id, version,
reviewer, approval)`, `SshRestoreReviewer.review(source, authenticatedTarget)`
and `ExactRestoreApproval.requireApproved(review, RestoreConsent)`.
The core assigns a fresh registration revision, owns consent construction and
wipes temporary authenticated manifest loans after review. Provider parsers
run before the final request mutex; approval must be a bounded metadata check,
with no parser/disk/system UI work. `importCommit(previewId, reviewBinding,
confirmTrust, EffectGuard)` rechecks the same binding at storage boundaries.

## 3. Contracts

- Status: `state`, `exists`, `generation`, `quickUnlockEnabled`,
  `quickUnlockAvailable`, `backgroundTimeoutSeconds`,
  `sshBackupImportAvailable`, bounded metadata-only `exportRecovery`, optional
  stable-code-only `exportRecoveryError`. Secrets/keys
  are absent. State is missing/locked/unlocking/unlocked/locking/failed.
- Import preview: opaque `previewId`, counts `totpEntries`, `sshEntries`, `files`,
  `trustRecords`, Boolean `overwrite`, string list `trustSources`,
  `reviewBinding` and nullable `restoreReview`. Keep the same fully authenticated
  immutable source and target snapshot through commit. Pure TOTP source and
  target use empty binding/null review; any SSH on either side requires review.
- Export preview: opaque `exportId`, `fileName`, `locationLabel`, Boolean
  `collision`/`overwriteAvailable`, optional existing name/size/modified metadata.
  Receipt: `status=exported/cancelled/failed/recovery_required`, `phase`,
  `fileName`, `locationLabel`, optional `errorCode`, bounded `remaining` rows
  (`role`, `name`, `location`, `state=present/unknown`). A completed export may
  still require cleanup; status alone must not hide remaining rows.
- Bridge management request IDs: single-use `[A-Za-z0-9_-]{1,128}`, one active UI operation,
  maximum 4096 admitted IDs per bridge, 120-second timeout. `secrets.totpList`
  requires the permanently read-only `read_` prefix with a separate rolling128
  replay set; every management method rejects that prefix even after eviction.
  Old evicted read IDs only admit a fresh authenticated read, never a write. Native capabilities
  are separate; a UI ID or Boolean trust consent never authenticates a password.
- Envelope v1: `CTOSVLT1`, 88-byte authenticated header; Argon2id v0x13,
  65536 KiB/3 iterations/4 lanes/32-byte key; AES-256-GCM, 16-byte tag,
  random 16-byte salt and fresh 12-byte nonce. Reject unsupported/oversized
  headers before KDF. Plaintext bound 12 MiB; authenticate before parsing.
- Manifest bounds: 1024 entries, 128 files, 1 MiB/file, 8 MiB total files;
  root-relative unique logical paths, authenticated object IDs/types/digests,
  complete references, no cycles, maximum reference depth 8. No source FD,
  absolute path or SAF grant is adopted as a usable target resource.
- `binding()` identifies a whole ciphertext snapshot. `materialBinding()` binds
  key epoch/header/salt while excluding fresh content nonce; ordinary typed
  edits preserve optional wrapper binding but invalidate old snapshot operations.
  Rekey/import changes material generation and disables old wrappers.
- For authentication-bound GCM, `AppAuthentication.cipher(Request)` only creates
  and initializes the Cipher. Submit `updateAAD(envelope.materialBinding())` in
  `perform(Request)`, after system authentication succeeds and the request,
  epoch and deadline guards for the crypto step pass, immediately before `doFinal`. API30 must use
  the same Cipher supplied in CryptoObject; API28/29 create their Cipher after
  authentication. AAD can advance an Android Keystore operation and cache an
  unauthenticated-operation exception even if `updateAAD` returns normally.
  Never treat it as passive setup or remove AAD/authentication to avoid failure.
- A single `VaultStore` AtomicFile stores complete envelope plus optional device
  wrapper in no-backup private storage. Guard before durable effect, verify the
  persisted record afterwards. Never publish an unlocked session after a newer
  lock: use `unlockIfEpoch(expectedEpoch, ...)` atomically.
- Typed manifest mutations recheck `clock.now() < operation.deadline` in the
  final store guard, after caller guards. An expired operation returns
  `VAULT_EXPIRED`, leaves ciphertext and shortcut material unchanged, and
  remains consumed; an earlier `protectedEffect()` check is insufficient.
- Native credential/key release passes an `EffectGuard hostKeyGuard` to
  `AppSecrets.sshCredential` / `sshPrivateKey`. Inside the core, clone the
  selected bytes, call the final host-key guard, then `protectedEffect`, then
  the receiver; wipe the clone in `finally`. A host-key guard that locks,
  cancels or exhausts the deadline must prevent receiver invocation.
- `EffectGuard.beforeCommit()` claims a `VaultRequestPhase` after the last
  permission/snapshot check and before AtomicFile finalization. `boundGuard`
  forwards this hook through core guards; test stores must call it too.
  Cancellation orders against that claim with a tiny mutex, without disk/crypto
  work under it. Interrupted requests after the claim report
  `VAULT_COMMIT_UNCERTAIN` and lock; after export tag claim they report
  `VAULT_EXPORT_UNCERTAIN`. Cancel, timeout, background and close share the same
  terminal mapping. Before a claim, cancellation prevents the durable effect.
- Management always freshly derives and authenticated-decrypts the supplied
  password; existing session, system success and password string comparison
  cannot substitute. Disable persists first, cleans its own key afterwards.
- Background timeout is 0..86400 seconds, default 30. Monotonic original background time controls expiry;
  manual/screen lock and restart lock immediately, increasing timeout cannot
  revive a locked session. Crypto/storage/status run on workers; lock invalidates
  fast without waiting behind crypto on the main thread.
- When background entry synchronously expires the session (including timeout
  zero), queue `cleanupInvalidatedContexts()` even if the session is already
  locked. Revoke only requests/prepared exports bound to an older epoch; do not
  cancel a fresh management/system-picker request merely because it began with
  a locked vault. Cleanup must wipe an invalidated import preview's verified
  source key/plaintext and remove old typed operations, not just the session key.
- Bridge close is idempotent and immediately makes its session nonforeground
  and locked. Invalidate its background callback version and remove its specific
  pending lock Runnable before shutting down the worker. A closed bridge's
  `onStop` and delayed callback cannot change a newly resumed context or submit
  to the closed executor. Remove only owned callbacks; do not clear unrelated
  authentication/result work from the Handler.
- API 28/29 uses credential confirmation plus actual auth-bound Cipher;
  API 30+ uses per-use strong-biometric/device-credential CryptoObject.
  A callback Boolean alone grants no key. Keep Java collection APIs minSDK 28
  compatible (`VaultCollections`), not `Map.of`/`List.copyOf`.
- TOTP produces only SHA1/6-digit/30-second codes, never returns seeds to Flutter.
  SSH public views exclude private keys; key release rechecks the actual hop and
  key identity. Trust CAS requires a distinct approved immutable exact proposal
  (target/port/fingerprint/base+replacement digest); host-key success is not
  consent to rewrite arbitrary known_hosts content.
- Bundle approval is a separate default-deny `ExactBundleApproval` for the exact
  whole proposal at admission/effect/final storage guard; host-key success never
  replaces bundle/trust approval. Entire resulting manifest validates and
  commits via the detached bundle preparation and guarded store path, preserving unrelated entries/files and optional
  key wrapper. Reject shared dangling references/associated key-passphrase
  removal; never silently cascade. Replace/delete require expected whole-object
  CAS, a different proposal or stale snapshot cannot reuse the capability.
  A real SSH validator is required even when deleting the final SSH object.
- Restore is independent from ordinary `SshContentValidator`: a versioned
  `SshRestoreReviewer` produces a bounded immutable metadata-only
  `SshRestoreReview`; a separate default-deny `ExactRestoreApproval` checks
  exact native consent at admission/effect/final guard. Source or authenticated
  target SSH requires both hooks and the validator, including pure TOTP source
  overwriting an SSH target. Core verifies full SSH file/credential coverage
  and computes binding from both ciphertext digests, provider version/revision
  and whole projection. Re-registration/revocation invalidates old previews.
  Do not expose raw private keys/config/known_hosts contents in this projection.
- `restoreReview` includes provider ID/version/revision, source/target cipher
  digests, `coverage` (side/kind/type/objectId/path/identity/objectBinding),
  `trustChanges` (status/sourceObjectId/targetObjectId/host/port/keyType/
  sourceFingerprint/targetFingerprint/marker) and `conflicts`
  (kind/status/sourceObjectId/targetObjectId). Unknown mappings remain visible;
  incomplete/oversized reviews reject instead of silently truncating risk.
  Legacy Boolean-only import commit is allowed only for pure TOTP on both sides.
  Never publish a direct digest of SSH password/passphrase content: after core
  coverage verification, use per-preview random-key HMAC opaque credential
  bindings in the displayed projection. Wipe the temporary key; the public
  review digest binds this actual opaque projection, without a low-entropy
  secret guessing oracle.
- Production SSH semantic validation is currently absent: mixed SSH imports
  and semantic mutations fail closed, `sshBackupImportAvailable=false`. Future
  consumers must use the common typed bundle transaction and real SSH validator,
  preserving unrelated entries. Real restore review/approval providers and
  trust semantics remain required before enabling mixed import.
  Do not implement a reduced OpenSSH parser or separate KDF/persistence.
- `SensitiveClipboard.copyCode(code)` is an internal effect helper invoked only
  by explicit `secrets.totpCopy`: detached worker generation/current snapshot then
  `AppSecrets.publishTotpRead` checks its opaque read receipt/request/epoch/revision/deadline and the current
  wall-clock window at the actual clipboard effect. Never call it with Flutter
  supplied codes or without the native publication guard. It writes the
  sensitive hint. User owns cleanup. No read/listener/clear call,
  copy ID, timer, cleanup status/preference or cross-app clipboard probe remains;
  this is the user's 2026-10-10 scope decision. Do not reintroduce them.

## 4. Validation & Error Matrix

| Condition | Result |
| --- | --- |
| Wrong password, authenticated ciphertext tamper | `VAULT_AUTHENTICATION` |
| Unsupported/truncated/oversized envelope | `VAULT_FORMAT` |
| Invalid manifest/path/reference | `VAULT_CONTENT` |
| Missing real SSH semantics provider | `VAULT_SSH_VALIDATION_REQUIRED` |
| Missing/locked/stale/expired state | `VAULT_MISSING` / `VAULT_LOCKED` / `VAULT_CHANGED` / `VAULT_EXPIRED` |
| UI cancellation before effect | `VAULT_CANCELLED`; no late unlock |
| Repeated bridge close / closed bridge lifecycle or delayed lock callback | No effect; no executor submission or newer-context invalidation |
| Active operation / bad bounds | `BUSY` / `PARAMETERS` |
| Post-commit persistence cannot be determined | `VAULT_COMMIT_UNCERTAIN`; lock and recover from persisted state |
| SAF export cleanup/restoration failed or mutation effect unknown | terminal receipt with precise remaining roles/locations; never claim clean rollback |
| Invalid export journal | report `exportRecoveryError`, refuse export/recovery; keep ordinary vault status/unlock usable |

The user's 2026-10-10 export decision replaces the earlier single create-document
flow: same-name confirmation, cleanup of only this transaction's created parts,
and protection of existing files. `VaultExportTransaction` stages/reads back a
new random document; confirmed old content is never opened for write. A random
rollback rename protects old bytes, and final is created as a new document whose
ID does not alias any currently existing document. Path-based providers may
reuse the historical original path ID after the old object moves to a distinct
rollback ID; this does not authorize writing the old object. Admit that final
reuse only with consistent phase/name/old-rename/rollback metadata. Reject
aliases between current stage/final/rollback roles. Before final verification,
compensate only exact owned IDs and restore old
name without overwriting a competing file. After final verification, retain
final even if old cleanup fails. Keep rename-returned ID chains and explicit
roles; ambiguous mutation effects never grant ownership by filename/prefix.

SAF direct-child listing is bounded/complete, not a deep scan. No broad storage
permission or persistent tree grant. `VaultExportJournal` stores minimal phase
and object metadata, never passwords/plaintext/cipher snapshots. Restart reports
pending recovery without directory access; recovery needs explicit user action
and same-tree reauthorization. Cancellation waits for owned worker cleanup and
terminal receipt; no provider I/O runs under the request phase mutex or main
thread. Conforming-provider contracts permit compensating transactions, not
arbitrary-provider multi-document atomicity or remote-cloud durability claims.
Journal writes verify bounded persisted bytes after `AtomicFile.finishWrite`;
do not call `failWrite` after finalization when readback fails, because legacy
Android implementations can delete the newly committed base. Track whether
finalization has been invoked and preserve uncertain persisted state.
After an exact query confirms a created role is absent, retire its reference
and confirm the journal transition before restoring the old name. If that
transition is unconfirmed, keep the evidence and report uncertainty. A restored
old object retains its actual returned ID/version until its metadata transition
is confirmed; never revert to the superseded rollback URI. Once verified final
cleanup confirms the old rollback is absent, consume the old historical roles
together. Never retire or delete a verified final during postcommit cleanup.
An invalid journal blocks new export/recovery, not unrelated vault usage.
Status may report only a stable recovery error code; do not dump corrupt bytes,
discard the ledger, guess document IDs or overwrite it with a new transaction.
The 120-second deadline bounds admission/commit authority, not arbitrary provider
I/O completion. Cancellation signals/owned-stream closure are best effort;
do not release the operation lease or report ordinary cancellation before the
worker determines its cleanup outcome. No new service or persistent grant is
added to keep cleanup alive.

## 5. Good / Base / Bad Cases

- Good: fresh password authentication, bounded complete mixed graph, immutable
  preview, final snapshot/epoch guard, checked whole-record commit.
- Base: TOTP-only backup works without SSH provider; disabled/unavailable quick
  unlock retains the password path; locked ordinary pages remain usable.
- Good lifecycle: an old bridge closes, a new bridge resumes the shared core,
  and old `onStop`/lock callbacks leave the new context unchanged.
- Bad: Flutter `trusted=true`, alias-only SSH matching, reading a seed over the
  channel, partial graph writes, exporting live Keystore material, clearing any
  current clip, or catching uncertain persistence as ordinary cancellation.

## 6. Tests Required

JVM tests exercise KDF vectors/format bounds/AAD tamper, mixed manifest and
actual core mutations, consumer/target/connection/once-use/cancel guards, trust
proposal CAS, wrapper/material generation, post-persist lock races, monotonic
timeouts and export uncertainty. `VaultStoreTest` uses only a unique owned test
file; interrupted writes and forced rename failure require actual Android runs.
Compiling instrumentation is not running it. Verify actual Keystore, API 28/29
and 30+, foreground/system picker lifecycle, screenshot/recents and arbitrary
SAF providers on authorized devices. Linux OpenSSH memory probe is research,
not Android/native client acceptance; see the task research report.

`VaultTest.typedMutationExpiryAtFinalCommitRetainsCiphertextAndShortcut`
advances the injected clock exactly to the capability deadline inside the
store, before its final guard, and asserts unchanged ciphertext/wrapper and
replay refusal. `finalHostKeyGateInvalidationCannotReleaseCredentialOrPrivateKey`
exercises lock, cancel and exact-deadline invalidation from the last host-key
gate for both credential and private-key paths; neither receiver may run.

Android crypto fixtures may inject `AppSecrets(ownStore, clock)` using unique
owned directories, never the process production owner or preferences. Injecting
an own store does not isolate Android Keystore's UID namespace:
`AppAuthentication.cleanupDisabledKeys()` and successful enable completion sweep
the production module alias prefix. Avoid those paths in isolated fixtures;
unavailable-before-key-creation is safe to check. Any actual Keystore probe owns
and deletes only its unique test alias. A device without secure lock can verify
unavailable/refusal, not successful credential/biometric CryptoObject release.
Launching the real `MainActivity` also constructs its production bridge and
queues normal `cleanupDisabledKeys()` maintenance. That launch is not a
Keystore-free fixture: it can enumerate module aliases and remove orphaned
ones. Scope unavailable-before-key-creation claims to the isolated
`AppAuthentication` instance; record normal application startup separately.

Bridge integration fixtures use their own UUID Store, preferences and journal;
never call `AppSecretsOwner.acquire` for fixture data. If real MainActivity is
the host, forward only its exact Activity lifecycle events to the independent
bridge and record the normal production startup separately. Do not claim that
direct method calls, reconstructed cores or synthetic SSH gates prove system
event delivery, actual process death or production SSH semantics. Check grace
and expiry using real monotonic time/Handler, late request completion exactly
once with unchanged persisted bytes, shared typed-consumer revocation, and
close followed by the old deadline/late `onStop`/repeated close. Flush workers
before deleting only the fixture's marked files and preferences. Injected
dependencies alone do not isolate successful quick/rekey/import key cleanup;
avoid those paths unless a separate UID/alias isolation design is verified.
The opt-in release package `im.majo.ctos.secretsfixture` supplies that separate
UID for `AppSecretsUiFlowTest#freshFixtureManualSecureQuickAuthenticationFlow`.
Require `ctosManualSecureQuickAuth=true`, an already-secure device, empty vault
and journal (including `.bak`/`.new`), no old marker and no own module aliases
before launching MainActivity. Missing opt-in is a skip, not a pass; explicit
opt-in on an insecure device is a failure. The human completes both system
prompts; while awaiting them the driver polls only its own wrapper/session and
does not request accessibility roots or log/input credential fields. Verify
the real process session key authenticates unchanged empty-manifest content,
disable/cancel/wrong-password preservation, wrapper removal and bounded
asynchronous own-alias cleanup before password fallback. Preserve failure
state for reconciliation; this empty-library test cannot prove nonempty
business data, biometric-specific success or cross-device restore.

`SecretsQuickAuthDiagnosticsTest` is also fixed to that independent fixture UID
and `SecretsFixtureApplication`, with its own Root auto-start already disabled.
All methods require `ctosQuickAuthDiagnostics=true`. Missing opt-in is a skip;
invalid explicit values/package/shared UID/own process mismatch fail before
effects. `readPublicQuickAuthenticationCapabilities` reads only public services,
features and Keyguard/Biometric flags. `probeOwnAuthenticationKeyInitialization`
additionally requires `ctosQuickAuthKeyProbe=true`; it checks preabsence, owns
only `ctos.fixture.authprobe.<runId>`, uses the production AES/GCM/auth policy,
records initialization stages and public KeyInfo, then deletes that exact alias
in finally and asserts absence. It never prompts or calls doFinal. A JUnit pass
means diagnostics completed; inspect `keyInitSupported` separately, and
`authenticationTested=false` must never become successful-authentication evidence.
Log at most eight exception class names, no messages, hidden-API reflection,
stack traces, passwords or key bytes.

`readOwnFixtureReconciliationMetadata` additionally requires
`ctosQuickAuthOwnState=true`, decimal `ctosQuickAuthExpectedUid` and
`ctosQuickAuthExpectedTestUid` matching the current installed inventory. It reads
presence of the known vault/journal base/.bak/.new and fixed owned marker, wrapper
presence, persisted-grant count and public lock flags. If vault .bak/.new exists,
skip `VaultStore.quickMaterial()` because AtomicFile.openRead may recover/mutate
files; otherwise wipe its wrapper clone and assert all known presences unchanged.
Optional `ctosQuickAuthOwnAlias` accepts exactly one known
`ctos.vault.quick.<lowercase UUID>` and only containsAlias; it never enumerates or
deletes aliases or accepts arbitrary paths/URI arguments. No production Owner,
private content or Main launch. Absent/unreadable wrapper/alias is not proof that
all effects are gone; failed reads block automatic reconciliation. Gate fixture
reset/uninstall on marked ownership, actual journal/grant/alias/process/system-UI
facts. Never overwrite a failed fixture's main APK before checking those facts.

API30 credential regression evidence on 2026-10-12: the unchanged real release
manual E2E failed enrollment before the AAD fix, then passed real wrapper
persistence, session-key authenticated decryption, disable cancel/wrong-password
protection, correct disable/key cleanup and master fallback after moving only
AAD timing. Require this full protected effect when modifying auth Cipher flow;
callback success or initialization-only probes do not cover the bug. Biometric
and API28/29 branches remain separate required platform gates; see
`design/verification.md` for versioned results.
Timeout-zero tests must retain an own pure-TOTP preview reference, deliver real
background entry, drain the owned worker, and assert both its verified key and
plaintext were wiped, the preview/old operations were removed, and the persisted
record stayed unchanged. Include a fresh already-locked request as a negative
control against cancelling all system UI whenever no session key is present.

`SshBundleTest` exercises real shared-core complete graph add/replace/delete,
unrelated secret preservation, fresh nonce/stable material binding/wrapper,
deep-copy/close, path/size/ref/cycle/associated passphrase rejection, exact gate
and validator (including final deletion), replay/purpose/cancel/epoch/deadline
and persistence-effect hook chains. Its fixture validator is explicitly
synthetic and never substitutes for production OpenSSH semantics.

Export fault tests exercise reused existing IDs, changed rename IDs/names,
collisions/metadata changes, write/close/EOF readback failures, cancel around
old rename/final verification, delete-then-throw, cleanup/restore failure and
restart recovery. Journal finalization must verify the persisted record before
irreversible old deletion; a stale precommit journal must never cause recovery
to delete the sole verified final. Recovered ownership requires exact role and
version/content evidence, not an ID or name prefix alone. Android journal/provider
fixtures compile locally and require separately authorized platform execution.

The test-only `DocumentsProvider` must retain framework-required
`MANAGE_DOCUMENTS` protection. Its cold owner process must bootstrap before the
target UID can acquire it and use the fixed fixture-tree grant. Use target
context for target-process activity/resolver calls; instrumentation test context
package attribution can differ from its actual Binder UID. Bootstrap accepts no
arbitrary package/URI and grants only the fixed test root/target. Never add a
production privilege or persistent user-directory grant to make a fixture pass.
Enter cleanup scope as soon as creation returns an owned document ID, before
opening/writing/closing it or constructing a transaction. Attempt every exact
owned role ID independently and preserve the primary failure when collecting
cleanup failures. Never replace failed cleanup with a name-prefix scan.

Restore tests use explicitly synthetic providers to cover source/target SSH,
complete coverage/size/alias bounds, projection binding, provider re-registration
or revocation, legacy Boolean-only rejection, wrong consent and final guard
cancel/epoch/deadline races. They prove public orchestration, not production
OpenSSH parsing, trust freshness or cross-device connections.

## 7. Wrong vs Correct

Wrong: initialize an auth-bound Cipher, call updateAAD before BiometricPrompt,
then assume a successful callback or nonthrowing initialization proves doFinal
will succeed. Correct: init only before API30 prompt; after authenticated final
guards for the crypto step, submit the unchanged AAD on the same Cipher, doFinal, authenticate the
vault content and verify persisted effects. API28/29 must likewise submit AAD
only after their credential success. Keep all original auth and snapshot guards.

```java
// Wrong: the gate can invalidate the session after the final core check.
protectedEffect(operation);
hostKeyGuard.check();
receiver.receive(credential);
// Correct: recheck core authority after the final host-key gate.
hostKeyGuard.check();
protectedEffect(operation);
receiver.receive(credential);
```

```java
// Wrong: persistence may finish after the user locks; this reopens the vault.
session.unlock(key);
// Correct: the newer lock wins even if the complete record was committed.
session.unlockIfEpoch(expectedEpoch, key);
```

```java
// Wrong: an old Activity timer may reach an already shut-down worker.
main.postDelayed(() -> invalidateVault(), delay);
// Correct: own and revoke the callback; also guard already-delivered callbacks.
if (closed || version != backgroundVersion) return;
```

Do not convert `VAULT_COMMIT_UNCERTAIN` to “previous data retained”, or a SAF
flush failure after the final tag to “no backup created”. Preserve uncertainty
as a first-class outcome and require recovery against actual persisted state.

## Scenario: independent UID release UI verification

### 1. Scope / Trigger

Use the opt-in fixture to verify real Flutter management controls and system
SAF without creating a production vault. It shares production Main/Flutter/core
sources; its Application supplies only fixture startup isolation.

### 2. Signatures

`./hako bash -lc 'cd android && ./gradlew -PctosSecretsUiFixture=true :app:assembleSecretsFixtureRelease :app:assembleSecretsFixtureReleaseAndroidTest :app:lintSecretsFixtureRelease --console=plain'`.
Runner: `im.majo.ctos.secretsfixture.test/androidx.test.runner.AndroidJUnitRunner`;
select one `AppSecretsUiFlowTest` method explicitly. The normal build omits the
property and retains its original application/test IDs and manifest.

### 3. Contracts

The only allowed option value is `true`; other supplied values fail configuration.
App/test IDs are fixed to `im.majo.ctos.secretsfixture` and `.test`, with no shared
UID. `SecretsFixtureApplication` checks its package before synchronously setting
only its own `root_access/auto_start=false`; failure aborts startup. Fixture test
sources/manifest exclude the ordinary fixed-authority/grant helpers. UiAutomation
does not suppress existing accessibility services, change system settings or
disable secure-window protection to capture secrets. Management-only UI checks
do not touch the clipboard. Physical TOTP copy checks default to disabled and
require specific current approval to replace the global clipboard, plus the
exact `ctosTotpClipboardCopy=true` instrumentation opt-in. General install/test
authorization does not override an automatic approval rejection. An approved
check may write a synthetic current code and verify only that own write, never inspect
prior user content or restore/clear/listen to it. Cipher reads are scoped fixture oracles,
not substitutes for the tested UI actions.

`TotpUiFlowTest#freshFixtureTotpUiFlow` additionally requires
`ctosTotpUiFixture=true`; `ctosTotpImageUi=true` opts into an API29+ exact owned
UUID MediaStore PNG. Select only its exact filename in foreground DocumentsUI.
Search editors bind `android:id/search_src_text` or the actual picker package's
`:id/search_src_text`, an editable enabled visible EditText in the active window
with advertised SET_TEXT. Never fall back to arbitrary editable nodes or coordinates.
Cross-app search submission uses the existing UiAutomation session, not
Instrumentation's own-app input API. Before Enter DOWN, refresh the exact
focused active-picker editor and own UUID query. After that accepted DOWN,
paired UP refreshes the same editor/query in the original picker window; focus
may have cleared during submission. Other role/resource/owner/visibility guards
remain; require both injections to succeed. Do not request INJECT_EVENTS, Root
or system settings changes.
Selecting same-UID owned media proves real picker/decode flow, not cross-UID
temporary grant enforcement. Cleanup verifies exact URI ownership and deletion.
Image-grid layouts can hide titles. Use only the known active picker's native
`:id/sub_menu_list` action and verify its inverse `:id/sub_menu_grid` in the
same window before strict visible `android:id/title`/UUID selection; never
replace the exact filename with a thumbnail, coordinates or arbitrary row.
Failed fixture ciphertext is reconciled read-only against the recorded UUID,
marker and synthetic profile before uninstalling the two owned packages.
Image-commit failure reconciliation accepts only that recorded run's baseline
and at most its one explicitly selected public candidate. Authenticate the
full profile and require unchanged ciphertext, no sidecars/journal/wrapper or
persisted grants before cleanup; unknown entries or pending transactions stop
cleanup. This inspector may neither launch UI nor unlock/mutate the session,
and is not evidence of a completed UI flow. Fixed-label diagnostics expose only
booleans/counts, never raw accessibility trees, user inputs or seed-bearing text.

Bind distinct editable nodes by their exact field labels before input; do not
reuse breadth-first indexes after a semantics rebuild. Refresh the same bound
node and verify input updates before submitting the form's directly actionable
Button role. Flutter can expose validation in `getHintText()` along with the
field label, separated by a newline or Android's `, ` label separator. Match
complete semantic components, including actual status prefixes and punctuation,
across text/description/hint; do not silently relax a failed validator check.
Diagnostics report only bounded role/action/bounds, lengths and comparison
booleans, never password text. Teardown uses `finally` so observation failure
does not prevent finishing the owned Activity.

All fixture actions must belong to the current scope package and active window.
Business controls use their own advertised CLICK and exact Button semantics;
only fixed navigation/confirmation controls may use explicitly allowed roles.
Do not climb arbitrary ancestors or click editable settings while searching for
an action. Scroll only a noneditable page ScrollView or an evidenced collection
container in that active window with the requested advertised direction; rebind after scrolling. Flutter may retain `android.view.View` while publishing collection
metadata for a ListView; class name alone is insufficient. A collection fallback
requires explicit collection metadata, advertised scrolling and bounded visible
container bounds, never an arbitrary View or editable field. Verify complete
terminal notices and bring status into view instead of equating offscreen status
with an unsuccessful operation. An unrelated active window causes wait/failure,
not a guess at a background fixture control.

Lazy lists can detach internal status/button anchors after scrolling. Reacquire
the unique evidenced collection using the exact known noneditable AppBar title
above its viewport in the same active window; reject ambiguous candidates.
The current fixture reaches controls by collection scrolling, without keyboard
Back events. Historical keyboard experiments are recorded separately.

Re-click the exact owned editor for each input/retry and wait for input focus plus its advertised
`ACTION_SET_TEXT` before calling that action. Flutter's Android bridge may return
success and predictively update cached text even when the Dart editor has not
advertised a setter; cached equality alone is not proof of controller input.
An old Android `isFocused=true` can survive Dart submit/unfocus and does not
replace this handshake. Wait for enabled/editable/clickable state after the
terminal UI message before re-acquiring focus.
Confirm values survive a subsequent focus/semantics rebuild, then use actual
validator and cipher outcomes as the oracle.
After retained-field checks and ensureVisible/keyboard layout updates, wait a
bounded interval for that original exact node to be enabled/editable/focused with
advertised SET_TEXT in the active scope/window. Dispatch once in that ready
branch; do not retry an already dispatched effect.

System picker helpers must also use the actual active DocumentsUI window,
including resource lookup, breadcrumbs, bounded ancestor navigation and consent.
Refresh the same node before an effect and recheck package/window, visibility,
enabled state and advertised CLICK. Matching a background window's package is
insufficient.

Do not assume a roots navigation button exists: the installed API30 DocumentsUI
locks its drawer when at most one selectable root exists. On configuration B,
the installed ExternalStorageProvider primary-emulated branch uses
`Settings.Global.getString(resolver, "device_name")` as its root title, with its
own localized `root_internal_storage` fallback. The fixture checks installed
provider identity/version and public primary/emulated storage metadata, then
compares the actual picker breadcrumb exactly with that source-derived title.
Build.MODEL, title length and StorageVolume description are not identity proofs.
The read-only mapping probe passed separately; navigating and granting the
exact new `Download/ctos/<UUID leaf>` still requires live breadcrumb and full
localized consent evidence. Do not add privileged provider queries, settings
writes or broad storage access to resolve a missing proof.

The installed DocumentsUI create-directory callback automatically opens the new
container. Its final breadcrumb deliberately has no CLICK action. After one
create confirmation, wait a bounded interval for the complete exact active
breadcrumb; do not interpret one transitional frame as a request to click the
new folder again. Existing-folder navigation still requires its exact row action
and the resulting full breadcrumb before any consent.

Import file titles may be six ancestors below the actionable native document
row. On configuration B, a read-only action diagnostic proved the exact filename
at `android:id/title`, no CLICK at depths 0–5, and advertised CLICK at depth 6
`DocumentsUI:id/item_root` despite its ordinary clickable flag being false.
Bind the exact filename and full active breadcrumb, then locate only that
evidenced row role/resource within a bounded parent chain. Refresh the title and
row, require matching active package/window and row bounds containing the title,
then rewalk the refreshed exact title to prove it still belongs to that same row,
recheck row role/resource/bounds and current breadcrumb, and dispatch its
advertised CLICK once. Stop at the row; never climb into
`dir_list` or another screen container. MIME mismatch must be measured rather
than inferred from a failed click; this backup reported `application/octet-stream`
and matched the production import filter.

### 4. Validation & Error Matrix

Wrong target/UID/Application, existing unknown fixture, nonfresh data or failed
Root-startup isolation -> stop before effects. Missing exact picker path or full
localized consent evidence -> stop before creating a folder/granting access.
Failure after effects -> retain fixture, journal, temporary grant and precise
owned location for reconciliation; do not clear data before recovering evidence.
Before resetting a failed pre-export fixture, diagnose own vault/journal recovery
variants and grants from the verified target UID, then corroborate exact grant
projection and the stages actually reached. A public "missing" label or a
permission-denied shell read is insufficient evidence that no journal exists.
System Back inside a nested tree picker can navigate up instead of cancelling.
After an explicitly authorized cancellation, prove the exact owned picker left;
if more Back actions are needed, recheck its active task/caller and breadcrumb
before each bounded action, and stop as soon as another window takes over.
Sending Back alone never proves cancellation or authorizes deleting its directory.

### 5. Good / Base / Bad Cases

Good: actual controls plus own ciphertext authentication, exact
`Download/ctos/<UUID leaf>` picker consent and terminal cleanup. Base: input and
cancel probe with no vault or picker effects. Bad: injected channel calls posing
as UI, granting a parent because a leaf name is merely visible, or passing an
own Store to a production-UID auth sweep and calling it Keystore isolation.

### 6. Tests Required

Check APK identity/signature/installed hash and distinct UID before running.
Probe field actions/cancel without creating a vault. Management checks wrong
password/cancel byte preservation and rekey; SAF checks new export, collision
cancel, explicit overwrite, distinct source/target passwords and import preview/
confirmation. Empty-library checks do not prove nonempty/mixed SSH restore.
Assert exact tree grant with no parent/persisted grant, terminal receipts before
deleting only returned owned documents and the empty UUID leaf; retain `ctos`
parent. Uninstall only the freshly owned fixture packages and verify production
status/artifact unchanged. Missing proof is not a pass or a reason for broader
permissions.

Successful import is not a ciphertext-copy operation: `AppSecrets.importCommit`
encrypts the authenticated source plaintext under a fresh local UUID/generation 1
and nonce, retaining the source salt/password material and removing the device
wrapper. Authenticate both source and imported envelopes, decode both complete
manifests and compare their full plaintext in memory; wipe keys/plaintext and
close decoded manifests. Check fresh identity/material binding, generation,
nonce, wrapper absence and locked UI, then reject the previous target password
and unlock through the actual UI with the source password. Use the captured
imported ciphertext as the unchanged baseline for a failed unlock; the external
backup remains the original backup bytes. Wrong passwords and cancelled previews
must still preserve the pre-import destination ciphertext byte for byte.

### 7. Wrong vs Correct

Wrong: `new AppSecrets(ownStore, clock)` alone prevents production alias cleanup.
Correct: use a verified independent package/UID and minimal fixture manifest;
record normal production-UID startup effects separately in lifecycle fixtures.

Wrong: successful restore must have `Arrays.equals(backup, localCiphertext)`.
Correct: require equal complete authenticated manifests and the fresh local
envelope contract; keep byte equality for failed/cancelled operations and for
the unchanged external backup.

## Scenario: outside-core import-preview validation

### 1. Scope / Trigger

Use when changing AppSecrets.preview, SSH validator/reviewer registration, Bridge import
preview/cancellation/delivery, or VaultRequestPhase publication. This is lifecycle protection,
not a production semantic validator. The detailed [contract](../../../design/ssh-validation-lifecycle.md)
defines pending configuration/reference/identity/credential/trust and final-plan obligations.

### 2. Signatures

```java
AppSecrets.preview(byte[] source, char[] sourcePassword, char[] targetPassword,
    String requestId, long deadline, EffectGuard guard);
AppSecrets.invalidatePreviewRequest(String requestId);
AppSecrets.deliverPreview(ImportPreview pending, EffectGuard owner, EffectGuard delivery);
EffectGuard.publish(EffectGuard publication);
VaultRequestPhase.publish(AppSecrets.EffectGuard publication);
```

Legacy preview overload captures one admission deadline at entry. Functional validator and
reviewer signatures remain compatible with existing synthetic fixtures. Every validator
attach/detach, including same-instance reattachment, creates a different immutable registration.

### 3. Contracts

- One in-flight PreviewWork per core; reject concurrent/reentrant work with BUSY. Keep that
  slot until all owned source/target/loan/review cleanup finishes, including Error paths.
- Capture target ciphertext, epoch, validator and reviewer registrations before expensive
  work. Validate actual authenticated source/target through independent decoded loans;
  reject changed loan encodings and wipe on return/throw. Never pass retained Verified.manifest.
- The preview worker's KDF, parser, review/canonical binding and callback-loan/temporary-input
  cleanup run outside core/request/session locks. Existing registration/cancelPreview retirement
  can still wipe under core, after releasing session; no wiping runs under the phase fence.
  Bridge passes its existing absolute Request.deadline unchanged; legacy callbacks can only
  be fenced before/after, not forcibly interrupted. Preview's separate 120-second consent
  lifetime starts at final publication after the last target store read.
- Final capture publication orders core → request phase → session. Last store.read is before
  the phase fence; recheck exact work/registration/cancel/epoch/foreground/deadline afterward.
  Assignment and metadata checks only inside the phase fence. boundGuard.publish must forward
  through parent.publish so composition retains the parent's cancellation ordering. An epoch assertion can lazily
  expire/revoke a session: while holding session, reject background before assertEpoch, so
  this fence cannot invoke attached native cleanup. Do not use unlocked() as a metadata check.
- UI delivery takes no core lock or store/parser path. Its phase → session claim is once-only;
  external result/cleanup runs after releasing locks. Invalidation before the claim rejects;
  invalidation after a winning claim may coexist with claimed summary delivery, but revokes
  commit. Do not claim external delivery is atomic with later lock/cancel.
- Bridge maps each admitted Request to a private unique preview owner token; it passes that
  token to core and resolves cancel/timeout only through its own active Request. Identical
  external request IDs from two Bridges never share preview cancellation authority. Retained
  previews keep exact previewId/importCancel cleanup. Generic failed/BUSY cleanup must not
  invalidate another request merely by user-visible id.
- Direct invalidatePreviewRequest marks only matching work/preview under session; it neither
  waits for the parser's worker queue nor wipes arrays still borrowed by that worker. Actual
  cancelRequest/owner cleanup retires only its own preview. Never acquire core while holding
  session/phase. Keep existing restore reviewer/exact consent/source/target/final store guards,
  and recheck the same validator registration through importCommit's final guard.
- Pure TOTP source/target needs no SSH providers and locked-start management import still
  uses fresh passwords. SSH on either side requires the original providers. Failed new work
  preserves unrelated old preview unless explicit lock/registration invalidation applies.

### 4. Validation & Error Matrix

| Trigger | Required result |
| --- | --- |
| Callback modifies a loan or supplies partial/incorrect canonical review | VAULT_CONTENT; wipe loans, retain storage |
| Missing validator/reviewer with SSH on either side | VAULT_SSH_VALIDATION_REQUIRED / VAULT_SSH_REVIEW_REQUIRED |
| Original deadline reached / request cancelled | VAULT_EXPIRED / VAULT_CANCELLED; Bridge retains its VAULT_TIMED_OUT guard |
| Validator/reviewer registration, epoch or target changed | VAULT_CHANGED or already-invalid preview VAULT_EXPIRED; no commit |
| Background publication / concurrent admission | VAULT_FOREGROUND_REQUIRED / BUSY |
| Invalid/duplicate/expired delivery claim | Reject without external success or clearing a newer preview |

### 5. Good / Base / Bad Cases

Good: pause target validator; lock/cancel/re-register on another thread returns; worker
eventually rejects, wipes its own loan and preserves original ciphertext. Base: locked-start
pure TOTP import keeps its password-only path. Bad: infer whole-core responsiveness from
preview-only migration, or use contentOnly inspection/catalog as semantic admission.

### 6. Tests Required

SshPreviewValidationTest covers actual distinct inputs, mutation/body-secret wiping, Error and
slot release, paused source/target/reviewer with concurrent invalidation, bounded direct cancel,
BUSY/reentry, original/final-read deadlines, separate review lifetime, same-instance registration,
final commit guards and late/once-only/after-claim delivery. Assert callbacks and cleanup are
outside tiny locks and exact background expiry cannot revoke under phase. Keep SshRestoreTest,
SshBundleTest and VaultSessionTest regressions; fresh full JVM/lint/fixture compilation separately
from actual Android runtime. No production provider registration or restore opening is implied.

### 7. Wrong vs Correct

Wrong: remove validateBusiness's synchronized and assume synchronized callers stop holding
core; validate a mutable retained manifest; check UI validity then call it atomic delivery.
Correct: owned capture → isolated callback loans outside core → exact final fenced publication;
explicit once-only delivery claim with external callbacks outside locks. Migrate each remaining
typed entry separately before registering the full production semantic validator. Bundle has
since migrated under the next scenario; this historical preview boundary is not whole-core completion.

## Scenario: outside-core bundle validation

### 1. Scope / Trigger

Use for native-only SSH bundle admission/effect, SshBundleProposal ownership, VaultOperation
bundle bindings and final Store guards. This protects lifecycle/transactions with synthetic
validators; it does not complete OpenSSH semantics or register production SSH restore hooks.

### 2. Signatures

```java
AppSecrets.BundleContext newBundleContext(long deadline, EffectGuard originalGuard);
AppSecrets.BundleContext newBundleContext(EffectGuard originalGuard);
void AppSecrets.BundleContext.cancel();
void AppSecrets.cancelBundleContext(BundleContext context);
VaultOperation SshVaultAdapter.bundleRequest(SshBundleProposal proposal, BundleContext context);
void SshVaultAdapter.commitBundle(VaultOperation operation, SshBundleProposal proposal, EffectGuard guard);
```

The default adapter overload creates a context before exact approval. Callers with prior
queue/approval work must create the explicit context at origin. No channel constructor,
serialized token, native handle or public secret getter is added.

### 3. Contracts

- Context binds its owning core, original epoch/absolute deadline/guard and immutable validator
  registration. Explicit deadline cannot exceed now+120000; earlier deadlines are preserved.
  Expired deadlines reject at use. Each admission attempt consumes its context once, including
  BUSY; an issued operation inherits that same context/deadline and exact proposal metadata identity.
- Context.cancel orders with issuance under session metadata; original publication uses
  phase → session. It never wipes worker arrays. Guard checks persist after issuance; effect's
  caller owns its beforeCommit durable claim. No second phase or renewed deadline is fabricated.
- One bundle-work slot persists through cleanup. Capture ciphertext/key under core; parse,
  decrypt, source validation, apply, candidate validation, encode/encrypt/readback and temporary
  cleanup run outside core/session/phase. Avoid sessionManifest/commitManifest on this path.
- Each provider receives a separate decoded loan; compare encoding to canonical authenticated
  bytes, reject modifications and wipe on return/Throwable. Candidate owns full Entry and
  FileObject arrays, including retained unrelated secrets. Both close orders must be independent.
  Candidate validation is unconditional, even after deleting the last SSH object.
- Issuance rechecks last ciphertext read and original metadata/registration. Its publication
  callback only checks metadata and inserts the operation; exact approval runs outside the
  phase/session fence. Recheck approval/context/proposal after publication; on Throwable remove
  only that exact provisional operation and cancel it. No orphan capability may remain.
- Initial adapter approval denial/wrong proposal remain nonconsuming. Once matching effect
  capture is accepted, consume/remove before detached work; no parse/provider/IO failure restores
  it. Already-consumed replay returns VAULT_OPERATION, rather than a stale-session error from
  an earlier adapter context precheck. Concurrent work may return BUSY until cleanup finishes.
- Recheck original registration, cancellation, exact proposal, epoch/foreground/deadline after
  final Store.read/quickMaterial and after caller.beforeCommit. Final snapshot read is BEFORE
  Store.commit. Inside an AtomicFile transaction, guards check metadata/approval only: never
  call Store.read/quickMaterial/openRead, which can discard the in-progress file. Core serializes
  writes. Keep envelope/wrapper transaction, fresh nonce, generation/material binding,
  contentRevision, uncertainty mapping and unlockIfEpoch; trust loan continuation stays separate.

### 4. Validation & Error Matrix

| Trigger | Required result |
| --- | --- |
| Foreign/reused context, wrong/replayed operation or proposal identity | VAULT_OPERATION; no unrelated operation consumption |
| Context/operation cancellation or exact deadline | VAULT_CANCELLED / VAULT_EXPIRED; no later stage/write |
| Missing validator / changed registration, including same-instance reattach | VAULT_SSH_VALIDATION_REQUIRED / VAULT_CHANGED |
| Changed ciphertext/epoch or modified callback loan | VAULT_CHANGED / VAULT_CONTENT; preserve persisted data |
| Exact approval withdrawn / concurrent or reentrant work | VAULT_TRUST_REVIEW / BUSY |
| Store failure before claim / unconfirmed persistence after finalization | VAULT_IO / existing VAULT_COMMIT_UNCERTAIN handling |

### 5. Good / Base / Bad Cases

Good: cancel original context on another thread while candidate validator is paused; it
returns promptly, worker rejects and wipes its own copies. Base: complete bundle preserves
unrelated TOTP/password/passphrase objects and wrapper. Bad: renew deadline after admission,
close a shallow candidate over original secrets or reopen AtomicFile in beforeCommit.

### 6. Tests Required

SshBundleValidationTest pauses admission/effect source/candidate callbacks across cancellation,
lock, foreground, same/new registration and exact deadline. Assert validators lack core/session/
phase locks and mutator acquires core before callback resumes. Cover original approval budget,
cross-core/reused context, original guard publication, provisional capability cleanup, loan
mutation/wiping, checked/runtime/Error cleanup across both phases/stages, IO/replay, both close
orders, final read/quick/beforeCommit metadata invalidation and BUSY/reentry. Store fixture
must reject read while commit is active, so memory-only tests cannot hide recursive AtomicFile
reads. Keep SshBundleTest's precise approvals/last-SSH deletion/unrelated data/after-claim race,
preview/restore/VaultOperation/VaultSession and fresh full JVM/lint/fixture compilation gates.
Real AtomicFile persistence, Android lifecycle and native semantics remain separate runtime gates.

### 7. Wrong vs Correct

Wrong: use old synchronized sessionManifest/commitManifest after moving only bundle's signature;
read Store from beforeCommit to strengthen CAS; approve a mutable retained candidate.
Correct: origin context → isolated authenticated capture/loans/candidate → once-used effect
→ pre-transaction snapshot read → metadata-only final guard and original durable claim.

## Scenario: detached TOTP list/code reads

### 1. Scope / Trigger

Use for TOTP_CODE/TOTP_LIST, their Bridge queue/admission/delivery, TotpVaultAdapter read methods
and AppSecrets.TotpReadState attached to VaultOperation. Full [contract](../../../design/totp-read-lifecycle.md) preserves
actual code disclosure ordering. This migration does not cover TOTP_SAVE management previews,
ordinary mutations, native SSH secret/trust consumers or whole-manifest semantic completeness.

### 2. Signatures

```java
AppSecrets.TotpReadContext newTotpReadContext(long epoch, long deadline, EffectGuard originalGuard);
VaultOperation beginTotp(String entryId, TotpReadContext context);
VaultOperation beginTotpList(TotpReadContext context);
Map<String, Object> totpList(VaultOperation operation, long unixSeconds);
String totpCode(VaultOperation operation, String entryId, long unixSeconds);
void publishTotpRead(VaultOperation operation, EffectGuard actualEffect);
void publishTotpRead(VaultOperation operation, LongSupplier unixSeconds, EffectGuard actualEffect);
void AppSecrets.TotpReadContext.cancel();
```

Legacy read overloads create one local context at entry. Bridge must supply the original
Request.epoch/deadline before queueing. `totpPublication(operation)` now accepts only the legacy
TOTP_SAVE preview path; scalar `publishTotp(...)` remains that path's separate helper, not read
receipt authority. Wire maps, read_ admission, TOTP profile and clipboard write-only policy stay.

### 3. Contracts

- Origin construction takes no core lock, key or store. Context binds core owner, original
  epoch/deadline/guard/immutable registration, cancellation and one admission attempt (BUSY
  consumes that context). Deadline <=now+120000, never renewed. Its exact issued-op reference
  closes cancellation-before-Request.totpRead-assignment: cancel marks under session, then
  cancels/removes that operation outside session. No external revocation under this owner lock.
- One read-work slot through all owner cleanup. Capture raw ciphertext/key/revision under core;
  parse/decrypt/decode/validator/generation and owned work/Verified/loan cleanup outside core/phase/pub/session.
  The final raw Store.read comparison buffer is still wiped under the existing core snapshot
  check, before any phase/publication/session fence; this is opaque ciphertext, not a parser loan.
  Never use legacy sessionManifest or a fabricated bundle operation. Validators borrow independent
  decoded canonical loans; reject changed encoding and wipe every return/checked/runtime/Error path.
- Require/compare original registration only after actual authenticated hasSsh is known. Pure
  TOTP needs no validator and tolerates provider churn. Mixed missing provider rejects; same
  instance reattach cannot revive old admission/operation/receipt. Snapshot binds mixed/pure fact.
- Shape/owner/purpose/resource mismatch does not consume an unrelated capability. Accepted matching
  effect consumes/removes before detached work; failures never restore it. Cancel-first/effect
  replay VAULT_OPERATION priorities remain. Last worker raw ciphertext/revision check precedes
  metadata publication, with metadata recheck after store seam. Whole completion catches Throwable,
  including a publication callback that marks complete then throws, and revokes that exact op.
- Read-specific Bridge guard.check reads only bounded Request fields (owner/cancel/resumed/time),
  publish takes original phase ONLY. Core then acquires publicationLock→session for issuance,
  successful-read completion and once receipt reservation. Do not nest legacy requestGuard.publish
  (phase→session) around publicationLock. Foreground precedes lazy session getters in these fences.
- Opaque state attaches to the exact issued object and binds context, source revision/mixed fact,
  successful-read status, native generation window and once reservation. No code/seed/array owner
  is retained there. Provisional issuance failure removes exact op; final publication has no
  core/store/crypto/provider or plaintext cleanup. No arbitrary callbacks in tiny metadata claims.
- After reservation releases phase, actual bounded list transport or clipboard write runs under
  inherited publicationLock→session, with original metadata/registration/revision rechecks and a
  fresh wall sample compared to native generation window. Recheck metadata after the bounded clock
  supplier. Reservation-to-effect cancellation/lock/window changes still reject; do not import
  preview's post-claim summary permission into code disclosure. No retry after attempted/failed
  reservation/effect. Core historical unixSeconds vectors remain valid until current delivery is asked.
- All cleanup and copied:true Result run outside locks. List Result.success is the explicit
  inherited transport-only/nonreentrant exception under pub/session; it cannot call core. Its
  throwing transport still retires the completed Request. Actual Binder/transport can delay
  session.lock/context.cancel; phase.cancel remains free. Arbitrary Result reentry lock cycles,
  Binder preemption and immediate Bridge UI completion on worker Error are NOT solved here.
- Request cancel/cleanup uses its private context and exact read operation, never public ID
  lookup in another Bridge. Clipboard Error/Runtime paths clean owned Request without duplicate
  disclosure; no clipboard read/listen/clear, raw seed channel or actual device probe is added.

### 4. Validation & Error Matrix

| Trigger | Result |
| --- | --- |
| Foreign/reused context, wrong resource/purpose, consumed read or immediate duplicate receipt | VAULT_OPERATION; no unrelated consumption |
| Cancelled context/operation / exact deadline | VAULT_CANCELLED / VAULT_EXPIRED (Bridge original guard retains VAULT_TIMED_OUT) |
| Missing original mixed provider / registration replacement | VAULT_SSH_VALIDATION_REQUIRED / VAULT_CHANGED |
| Changed raw snapshot/revision/epoch or modified canonical loan | VAULT_CHANGED / VAULT_CONTENT |
| Foreground lost / concurrent or recursive read | VAULT_FOREGROUND_REQUIRED / BUSY |
| Negative generation time, including empty list / crossed current code window | PARAMETERS / TOTP_TIME_CHANGED |

### 5. Good / Base / Bad Cases

Good: cancel an original queued/mixed read while validator is paused; another thread obtains
core, then worker rejects and wipes owned loan/key/cipher. Base: pure TOTP works with absent or
changing SSH registration. Bad: mint +120s after parse, expose source manifest to provider,
or copy a code after its once-reserved receipt became stale before the actual effect.

### 6. Tests Required

TotpReadValidationTest exercises list/code × admission/effect paused mixed invalidations, pure
absence/churn, original queue budget/epoch, wrong owner/shape/replay, BUSY/context reuse, owned
key/cipher/loan wiping, mutation/close/checked/runtime/Error recovery, final raw snapshot mismatch
and metadata seams, provisional/completion callback-then-throw cleanup, once receipt and actual
effect lock topology/window/clock metadata invalidations. Assert no store/crypto in final claim,
blocking background revocation cannot seize phase, actual effect has no phase/core and inherited
session blocking is explicit. TotpTest retains vectors/read_ namespace/projection and legacy save
preview; VaultTest arms the same registered provider to test lock during effect rather than only
new-registration rejection. Keep preview/bundle/VaultOperation/VaultSession and fresh full JVM/
lint/Release/fixture gates. JVM stubs are not actual Android Result/Binder/clipboard or lifecycle.

### 7. Wrong vs Correct

Wrong: create origin context on worker after queue, always require SSH validator, call old
sessionManifest, then publish a freely supplied epoch/revision scalar as read authority.
Correct: original metadata context → independent authenticated source/loan outside core →
once-used effect with exact receipt → phase-only reservation → guarded current actual disclosure.

## Scenario: detached SSH public-file reads

### 1. Scope / Trigger

Use for SSH_FILES admission, sshFiles/sshFileSnapshot synchronous borrowed public bodies,
sshPublicFiles native bootstrap and SshManagedKnownHostsProvider.snapshotFor. The old
synchronized sessionManifest helper remains for excluded credential/private-key/mutation/trust
paths. No MethodChannel, full semantic provider registration or production restore opening.

### 2. Signatures

```java
AppSecrets.SshPublicReadContext newSshPublicReadContext(
    long epoch, long deadline, EffectGuard guard) throws VaultException;
AppSecrets.SshPublicReadContext newSshPublicReadContext(
    long deadline, EffectGuard guard) throws VaultException;
VaultOperation beginSshFiles(String rootPath, String connectionId,
    SshPublicReadContext context) throws VaultException;
SshPublicReadPreparation prepareSshPublicRead(VaultOperation operation,
    String rootPath, String connectionId) throws VaultException;
void attachSshPublicRead(SshPublicReadPreparation prepared,
    VaultSession.Revocation exactLocalLease) throws VaultException;
```

SshVaultAdapter supplies fileContext(deadline,guard) and fileRequest(root,connection,context).
SshPublicReadContext.cancel invalidates directly and cancels its exact issued operation.
Opaque AppSecrets.SshPublicReadState belongs to the exact VaultOperation. Preparation owns
one native-mode consumed work/snapshot through close; it is not a reusable scalar receipt.

### 3. Contracts

- Construct context before queueing: owner/epoch/original absolute deadline/metadata guard/
  immutable validator registration/once admission. No core acquisition, store read or key copy.
  Immediate bootstrap default captures one120s budget before admission. Existing-role
  snapshotFor uses the original role.deadline, exact role/clock/view guard and current epoch;
  role-first metadata publish cannot take session before publicationLock. Default context
  foreground+epoch, capture metadata+session.key, and pre-ownership foreground+attachedRevocation
  getter each share one session monitor. External role guards stay outside the capture session
  fence; this prevents a background transition from triggering lazy revoke under core without
  adding a session→role lock inversion.
- One public-read work slot lasts through cleanup. Matching accepted effect consumes/removes
  before detached work; BUSY preserves a valid effect, wrong owner/purpose/root/connection
  does not consume, replay is VAULT_OPERATION with cancellation checked first.
- Capture actual cipher/key/revision under core. Parse/decrypt/decode, independent canonical
  validator loan, unchanged-loan check and closure/snapshot preparation run outside core/
  phase/publication/session. Same-instance provider reattachment invalidates the context.
  Require an original SSH validator; attaching one later cannot rescue an old missing provider.
- Private-key bodies never enter publicFiles or native public wire. Preserve metadata/declared
  references/root/connection/vault/generation/epoch/deadline/binding. Keep Verified alive through
  the synchronous loan; worker finally wipes all owned arrays on checked/runtime/Error paths.
  Do not concurrently wipe a receiver's borrowed arrays on the cancellation thread.
- Final raw source equality occurs under core BEFORE phase publication, with a metadata recheck
  after the store seam. Only its temporary opaque comparison buffer wipes under core. Issuance
  and claims use original guard→publicationLock→session; foreground first prevents lazy native
  revocation under the phase fence. No parser/store/receiver/native create/close or loan cleanup
  in those tiny fences. A publish hook that invokes its callback then throws must roll back.
- Synchronous public loan: claim once, release ALL locks, invoke receiver, then recheck original
  metadata before returning success. Claim-before-invalidation rejects; invalidation after claim
  may allow already-started public copying, but cannot return a successful stale receipt.
  Borrowed arrays may not be retained; managed owned public copies need their own lifecycle.
  Already observed/copied public bytes cannot be retracted and never grant host/KEX/trust/secret
  authority. This claim policy does not apply to credential/private-key/code disclosure.
- Native lease: prepare native-mode snapshot, encode/create outside locks, then final raw store
  equality, release core, then exact-state attach under the publication fence. The revision
  recheck rejects a commit in that gap. Reserve one attach attempt so replay cannot revoke an
  already-published view; a rejected pre-ownership lease remains caller-owned. Require a genuinely local-owned
  provisional lease; reject the current session's already attached identical view before taking
  ownership. The helper is not an arbitrary foreign-view ownership transfer API. Attach operation FIRST, same session epoch SECOND, check
  context/operation and active lease before acknowledging, and recheck before return. Reject
  inactive/foreign/closed/delivered preparations, repeated attach or an existing active session
  view. Never replace another active view. Every failure closes/revokes the local lease outside
  locks on the production path; rejected pre-ownership helper parameters remain caller-owned.
  An inactive failed attachment may remain replaceable, never active and unreturned.
- Native preparation and synchronous delivered state are separate: a general receiver cannot
  take its consumed operation and publish an arbitrary second revocation. Tests may use a local
  fake Revocation through the same opaque preparation; no mutable global native factory.
- Context checks end at successful publication; do not add a permanent old-epoch/registration
  revoker. Exact own-NEW-CAS may advance epoch retaining only its same attached active view,
  with no deadline renewal; old operation/context cancellation still revokes that exact loan.
  snapshotFor's separate consumed request must not own/cancel the role's original view.

### 4. Validation & Error Matrix

| Condition | Required result |
| --- | --- |
| Wrong core, purpose, root, connection, replay/foreign preparation | VAULT_OPERATION; mismatches nonconsuming |
| Accepted work already active | BUSY; no valid effect consumption or slot replacement |
| Original context/operation cancelled | VAULT_CANCELLED; no successful issuance/return/active unreturned lease |
| Deadline/epoch/background/revision/ciphertext/registration changed | Explicit expired/changed/foreground rejection; no fresh budget |
| Original SSH validator absent | VAULT_SSH_VALIDATION_REQUIRED; no later-provider rescue |
| Validator loan mutated/closed or callback throws | Reject, wipe owned arrays, keep original store unchanged |
| Claim succeeds then invalidation during receiver | Let active public loan finish; postcheck rejects and worker wipes |
| Existing active view or inactive provisional native lease | BUSY/changed; never replace/revoke other owner's view |
| Guard throws after issuance/attach callback | Cancel exact provisional operation/local lease outside locks |

### 5. Good / Base / Bad Cases

Good: one original snapshot yields a bounded public closure; native creation happens outside
core and its final attach orders with cancellation and lock. Base: JVM fake revocation proves
attachment ordering, not actual JNI lifecycle. Bad: create a fresh120s context at effect time,
run validator against retained manifest, call JNI under core, or continuously reject a preserved
own-CAS public view because its originating epoch changed.

### 6. Tests Required

Pause admission/effect validators and verify core access plus direct cancel/lock/background/
original deadline/same-new registration invalidations; retained independent loans/key/cipher
wipe including Error. Cover mutation, final raw read seams, wrong bindings/replay/BUSY/reentry,
private-body exclusion, all-lock-out receiver and post-claim invalidation. Fake native prepare/
attach tests cover cancel/lock/BUSY/inactive/repeated/foreign work and post-callback guard throw,
with exact local cleanup and other view preservation. Role deadline/close/view revocation and
existing exact own-CAS retention/old-operation cancel regressions must pass. Real JNI/platform
execution is independent of JVM tests and APK compilation.

### 7. Wrong vs Correct

Wrong: sshPublicFiles calls a generic claimed receiver and attaches later, or a receiver/validator
retains authenticated manifest arrays. Correct: separate opaque native preparation, lock-out
create, final exact attachment; independent validator loan and worker-owned synchronous cleanup.

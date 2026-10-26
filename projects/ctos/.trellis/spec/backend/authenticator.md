# Authenticator host contract

## 1. Scope / Trigger

Applies to `Totp*`, `VaultRequestAdmission`, TOTP core methods and the
`secrets.totp*` bridge. Product authority is [design/authenticator.md](../../../design/authenticator.md).
Reuse [App Secrets](app-secrets.md); no second storage/KDF, Root/Python consumer,
raw-seed channel or clipboard cleanup. Source implementation and platform
acceptance are separate.

## 2. Signatures

All calls use `ctos/native` with a fresh `requestId`, except `totpCancel`.

| Method after `secrets.` | Arguments / result |
| --- | --- |
| `totpList` | `read_` ID; `{entries:[{entryId,identity,label,code}],unixSeconds}` |
| `totpManualPreview` | `issuer/account/secret` or `uri`; preview below |
| `totpImagePreview`, `totpCameraPreview` | explicit user picker/scanner; preview below |
| `totpCommit` | `previewId/candidateId`; `{entryId}` |
| `totpCancel` | `previewId`; void, only that preview |
| `totpRename` | `entryId/label`; common status |
| `totpDelete` | `entryId`; common status |
| `totpCopy` | `entryId`; `{copied:true}`, no code argument |

Preview: `{previewId,candidates:[{candidateId,identity,label,algorithm,digits,period}],invalidCodes}`.
`invalidCodes` is an integer0..16, not a raw-URI list. The `ctos/secrets`
EventChannel emits `{epoch:int,unlocked:bool,foreground:bool}` only.

Native `TotpPreviews` owns candidate bytes and `TOTP_SAVE` capabilities. Production uses
original `TotpMutationOrigin` plus exact opaque ticket publication; scalar
`totpPublication(operation)`/`publishTotp(...)` remains compatibility-only. Capture cleanup
does not revoke a successfully handed-off preview; commit retains the original deadline
and supplies a new effect guard. See [mutation contract](../../../design/totp-mutation-lifecycle.md).
TOTP_CODE/TOTP_LIST use original `TotpReadContext` before Bridge queueing, detached
source/loan/generation and opaque operation-bound state; `publishTotpRead(operation,effect)`
requires that successful once-used read, current revision/registration/window and once reservation.
See [typed-read contract](../../../design/totp-read-lifecycle.md) and App Secrets scenario.

## 3. Contracts

- Only TOTP SHA1/6digits/30seconds. Strict URI type, duplicate/unknown parameters,
  issuer-prefix equality, UTF8 percent encoding and ASCII Base32 are checked.
  Unicode case folding must never make a non-ASCII seed valid. Metadata512UTF8
  bytes; reject control/format characters and malformed surrogates.
- URI8192characters, seed10..128bytes, at most16QR candidates; manifest remains
  capped1024entries. Images10MiB/8192side/16millionpixels; camera decoder bounds
 2048side/4millionpixels, in addition to its crop. Reject oversized input;
  never truncate a secret or substitute default parameters after rejection.
- Each add gets a new native UUID and never overwrites an entry. Rename retains
  seed, identity and unrelated entries/files. Delete cannot enter SSH namespace.
- One host-only preview,120second monotonic lifetime, queued expiry wipe.
  Bind every candidate to its original epoch/snapshot/deadline. Cancel/lock/
  mutation/timeout retires it; explicit selected-candidate confirmation is
  mandatory. Resources/captures are cleaned outside publication locks.
- `VaultRequestAdmission` keeps4096permanent management IDs and128rolling read
  IDs; `read_` can only call `totpList`, forever. No write replay is admitted by
  evicting a read ID; no unbounded replay history or mutation queue.
- All core commits bump contentRevision before the durable change, after full
  guards and outside I/O in the tiny publication lock. Worker validates disk
  snapshots; main checks only lightweight owner/session/revision. Copy rejects
  a crossed wall-clock window. Clipboard helper never reads/listens/clears.
- Read origin checks only metadata and does not acquire core. Its phase-only
  guard orders phase→publicationLock→session; actual code disclosure follows after
  phase release under inherited pub/session with final window/metadata rechecks.
  Pure TOTP has no SSH provider dependency; mixed reads keep the original immutable
  registration. All cleanup and copied:true Result are outside locks. List Result
  is bounded transport-only/nonreentrant under pub/session; arbitrary callback
  reentry and Binder/session blocking remain explicit inherited limits.
- Own nonexported scanner/reader use FLAG_SECURE and carry only opaque request
  IDs to the parent. Image URI/transient grant belongs to the short-lived reader
  Activity, no persistent grant or parent raw URI/seed extras. Original image
  stays unchanged. CancellationSignal/timeout can retire UI and late decode;
  do not promise arbitrary provider I/O termination by a deadline.
- Scanner actual start/stop visibility owns foreground; only the exact pending
  camera launch defers Main pause's foreground transition to stop. No timed
  exemption or timeout extension; Home/screenoff/expired epochs cannot revive.
  External picker background obeys the configured timeout, including zero.
- Missing production SSH semantic/review hooks remain default-deny for mixed
  backup/restore; no empty validator to make integration tests pass.

## 4. Validation & Error Matrix

| Condition | Stable result |
| --- | --- |
| invalid/unsupported URI, Base32, metadata | `TOTP_INPUT` |
| byte/pixel/candidate/frame limit | `TOTP_LIMIT` |
| no supported QR | `TOTP_NO_CODES` |
| damaged/unavailable selected provider | `TOTP_IMAGE` |
| denied/unavailable camera | `TOTP_CAMERA_PERMISSION` / `TOTP_CAMERA` |
| delayed code crosses30s window | `TOTP_TIME_CHANGED` |
| stale/locked/cancelled request | common `VAULT_*`, no add/publication |
| write after durable claim becomes uncertain | `VAULT_COMMIT_UNCERTAIN`, lock and acknowledge |

## 5. Good / Base / Bad Cases

Good: choose one metadata-only candidate, guarded new-ID commit, fresh native
copy. Base: camera denied leaves image/manual available. Bad: send seed/URI to
Flutter, return old codes after lock, read a provider on main, reuse an evicted
read ID for a write, or retain a grant on the parent Activity.

## 6. Tests Required

Verify supported RFC vectors, leading zero/window/2038+, duplicate parameters,
bad UTF8/Unicode Base32, rename preservation, foreign IDs, add-not-overwrite,
expiry/cancel/mutation seed wipe, revision invalidation/no-store main publish,
thousands of reads/eviction/write rejection, actual generated multi-QR decode,
image limits. JVM tests do not establish real Bitmap/provider/permission/
camera/lifecycle/clipboard behavior; compile scoped Android tests and record
real-device and mixed-restoration gates separately.

## 7. Wrong vs Correct

Wrong: make `read_` mutable after forgetting its128-entry replay record.
Correct: permanently reserve that namespace to authenticated read admission.

Wrong: final publication calls a synchronized core/preview validator on main.
Correct: worker checks the snapshot; final lightweight revision/epoch guard
publishes, then capture/resource cleanup runs after its locks are released.

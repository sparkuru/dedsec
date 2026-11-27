# SSH shared view cancellation ownership

## 1. Scope / Trigger

An SSH jump chain shares one managed public-file lease across independently owned session bindings. Its Java shared owner must reject new use and retain the exact lease when its own cancellation, underlying revoke, or close cannot be confirmed. This contract does not itself resolve per-session cancellation or partially constructed native sessions.

## 2. Signatures

`SshSharedPublicView.cancel()` may be called across threads. `issue()`, `seal()` and `close()` retain their original owner-thread rules. The checked native invalidation begin returns `-1` for confirmed no begin, `0` for no active fence, and `1` for a begun invalidation; a thrown exception does not establish its native outcome.

## 3. Contracts

Before invoking native invalidation, mark the shared owner sealed and locally cancelled under its existing monitor. Existing bindings must then report inactive and new issuance must fail. Perform native begin/end and the following underlying lease revoke outside that monitor; the native begin can wait for already admitted effects, so holding the shared monitor while it drains can block their completion. Track these operations separately. A `-1` result is explicitly pre-begin and permits the same owner to retry cancellation. A thrown begin/end exception or invalid return is unknown: retain the original owner and never repeat that uncertain native operation. A revoke exception is independently unknown and must not be retried or treated as a completed revoke.

`close()` keeps the original pending-binding and owner checks, additionally rejecting while cancellation or revoke is pending, retryable or unknown. Mark close admission under the same monitor before calling the source outside the lock. A cancellation arriving after close admission may mark local cancellation and report the in-progress close; that earlier close may physically finish afterwards. If it fails with a typed `VaultException`, its existing same-owner retry remains available after any pending cancellation is resolved. An unchecked close failure has an unknown result and cannot be silently retried. Cancellation after a confirmed close is idempotent.

Binding retirement and failed unadopted-open abort remain independently possible after local cancellation; they do not prove that native borrow destruction or the shared lease has completed. The lock-order test concerns this shared monitor and a fake source; it does not prove that every lock inside the real lease or an independently invoked lease revocation is safe. Do not equate the local Java flag or this owner with a successful native revoke, an ancestor-chain effect fence or a trust-write authorization.

## 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Cancellation begins | Existing bindings become locally inactive and new issuance is denied |
| Checked begin returns `-1` | Keep lease; permit cancellation retry; deny close until resolved |
| Begin/end throws or returns an invalid status | Keep original lease; do not retry the uncertain operation or close |
| Underlying revoke is in progress or throws | Reject close; do not invoke the same uncertain revoke twice |
| Close was admitted before cancellation | Report cancellation as pending; let that admitted close finish without a concurrent revoke |
| Normal cancellation and all bindings retired | Revoke once, then permit exact owner close |
| Typed source-close failure with no unresolved cancellation | Preserve existing same-owner close retry |

## 5. Good / Base / Bad Cases

Good: a checked pre-begin failure fences local bindings, then the original owner retries and revokes the lease. Base: normal cancellation revokes once and waits for every binding to retire. Bad: an unknown JNI result is treated as no begin and a later close destroys the original lease.

## 6. Tests Required

Use controlled Java barriers for revoke/close and close/cancel ordering, local binding liveness, idempotence and typed close retry. With real host test JNI in isolated JVMs inject checked pre-begin failure, unknown begin before/after, end before/after and underlying revoke failure; check no duplicate unknown invalidator, no source close or release, and valid same-owner retry for the explicit pre-begin case. Existing terminal, known-host jump, full JVM and release checks remain separate from Android device validation.

## 7. Wrong vs Correct

Wrong: treat a thrown JNI call as proof no native effect occurred, or release a lease merely because its Java flag was cleared. Correct: fence local use first, distinguish explicitly retryable from unknown effects, and retain the exact shared owner until its operations are confirmed.

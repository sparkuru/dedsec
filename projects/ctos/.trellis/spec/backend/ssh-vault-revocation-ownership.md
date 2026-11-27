# Vault and SSH file-view revocation failures

## 1. Scope / Trigger

A vault lock, background transition or trust epoch change can fail while the shared native effect guard or an attached SSH public-file view is being revoked. Preserve Java key/epoch invalidation and the exact original owner without treating an unknown JNI outcome as successful native cleanup. This contract does not establish full SSH chain cancellation or MethodChannel result delivery.

## 2. Signatures

`VaultSession.lock()`, `foreground(false)` and `advanceTrustEpoch(...)` use the checked effect begin. `VaultSession.Revocation.revocationConfirmed()` defaults to false; the real `SshPublicFileLease` returns true only after the native handle accepts revocation. `SshPublicFileLease.revoke()` propagates unconfirmed cleanup; `close()` retains its typed `SSH_FILE_VIEW` result for native destroy failure.

## 3. Contracts

On the normal path, keep the existing session monitor and native effect ordering: a successful begin drains already admitted effects before the protected change; pair only a confirmed begin with one end. If begin, end, or the exact attached revocation fails, erase the session key and advance the epoch once for that failure, retain the original revocation, and deny unlocking or replacement while unresolved. An explicit checked outer `-1` means begin did not occur and permits retry by the same session. An exception or invalid status is unknown; do not repeat that begin/end or the unconfirmed owner revoke. A nested failure must still end any separately confirmed outer begin exactly once.

Background-transition failure also records the background state. Trust-epoch failure locks locally rather than leaving the old key usable. A native lease revokes Java use locally, retains its token on unconfirmed begin/end/destroy, and reports destroy failure to shared owners; its own checked `-1` is currently conservatively non-retryable. `revocationConfirmed()` waits for a concurrent real lease revoke on the existing session-to-lease lock order. This wait distinguishes an in-flight revoke from a returned unknown result; do not infer a broader lock-order proof.

Native destroy acceptance prevents new borrows but can leave existing borrows holding the backing. It is not proof of physical release. When the outer vault begin itself fails, the original lease may remain locally active and native effects already admitted or not ordered by this Java lock can still complete; separate role/view and chain admission is required.

If an actual store reports `VAULT_COMMIT_UNCERTAIN`, keep that result as the primary error even when session lock or independent preview cleanup throws. Retain the operation registry when lock is unconfirmed, and do not retry unknown native cleanup while preserving the receipt.

For an uncertain SSH result, `AppSecrets.lockForUncertainSshResult()` captures the exact published preview under the session monitor, calls the existing lock and marks that preview invalid even when lock throws. It releases the session monitor before acquiring the AppSecrets monitor and closes only that same preview. An import already using it retains cleanup ownership until its own `finally`; all preview-retirement entrances defer physical close while that import is in use. Synchronous nested preview or import of that in-use preview returns `BUSY`. Unpublished preview work retains and eventually clears its own verified material; a later replacement must not be retired by an earlier lock. Waiting for native drain or an in-use import can delay the result.

`cleanupInvalidatedContexts()` orders its epoch snapshot, unconfirmed invalidation check and stale-operation removal under the session monitor. Unknown invalidation and explicitly unstarted invalidation awaiting the original owner's retry both retain operations. The helper does not establish native release, and explicit `cancelOperation` or other registry-clearing paths remain separate obligations.

## 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Outer checked begin returns `-1` | Erase key, retain owner and permit same-session explicit retry |
| Begin/end throws or has invalid result | Erase key, retain owner and prohibit repeated uncertain JNI |
| Attached revoke throws or lacks confirmation | Preserve exact lease and reject unlock/replacement |
| Lease revoke is still running | Order confirmation behind that revoke; do not infer unknown from its transient inactive state |
| Native handle destroy returns false or throws | Report failure to the shared owner, retain the token, and do not repeat the unknown destroy |
| Store reports uncertain while lock fails | Preserve uncertain receipt and its cleanup cause; retain registered operations |
| Revocation accepted with outstanding borrows | Do not claim physical backing is already released |

## 5. Good / Base / Bad Cases

Good: checked begin explicitly did not run, the original session retries, and the same lease confirms revocation. Base: a normal lock drains the admitted native effect and invalidates key and view. Bad: a JNI exception is assumed to mean no native mutation, so the caller retries it or discards its original owner.

## 6. Tests Required

Use controlled JVM barriers for a concurrent revoke versus lock and replacement, retryable outer begin, unknown begin/end, nested failures, foreground and trust-epoch failures, and retention of the exact lease and operation registry. Run actual host JNI in isolated JVMs for begin/end before and after native mutation and for native destroy before/after failure; assert the original key is wiped, unknown calls are not repeated, shared cancellation cannot report success, and the token/owner is retained. Include a native effect drain and a successful real lease reopen with no outstanding borrow. Exercise a real encrypted vault with an explicitly identified memory store for the uncertain receipt. Keep fake view/store, host JNI, Android lifecycle and real-server evidence separate.

## 7. Wrong vs Correct

Wrong: `active == false`, an idempotent destroy call, or a caught JNI error proves physical lease destruction. Correct: distinguish local denial, confirmed native revocation, outstanding borrows and unknown ownership; retain each exact owner until the relevant operation is confirmed.

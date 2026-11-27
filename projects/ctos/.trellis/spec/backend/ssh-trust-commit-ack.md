# SSH host-trust commit acknowledgement

## 1. Scope / Trigger

The managed direct SSH connection can persist a user-approved NEW or CHANGED known-hosts candidate while the native KEX and its Java owner are still completing. This contract preserves the outcome of a possibly durable trust write across cancellation, screen lock, service rejection and cleanup failure. Internal per-hop receipts are available for future chain assembly; they do not grant ProxyJump or implement an ancestor-chain effect guard.

## 2. Signatures

```java
SshSessionOwner.Factory.mayWriteTrust() -> boolean
SshSessionOwner.Factory.trustCancellation() -> SshSessionOwner.Cancellation
SshSessionOwner.Cancellation.trustReceipt(scope) -> one-shot TrustReceipt
SshSessionOwner.Cancellation.confirmTrust(receipt, scope) -> boolean
SshNativeCallbackAdapter.confirmNewNativeCompletion() throws VaultException
SshNativeCallbackAdapter.confirmChangedNativeCompletion() throws VaultException
SshPasswordConnector.trustPublicationUncertain() -> boolean
```

The production password connector shares one cancellation/confirmation ordering point with its session owner. A synchronous factory that may write trust declares that fixed capability before creation begins.

## 3. Contracts

Record NEW and CHANGED writes separately from native completion. The exact native callback/role still owns the host and signed KEX proofs; after its native step completes, the original owner checks the original guard and deadline before confirming the pending write. The confirmation uses `role.publishBound` to order its metadata-only update against closure of that exact role and revocation of its exact view. While holding role/view locks, do not call the vault, session, native code or a blocking callback; invalidate and lock the vault only after unwinding those locks. NEW and CHANGED invalidation each mark their provider state before releasing its lock, so confirmation cannot overtake them. Do not permit selected credentials while a trust acknowledgement remains pending. Owner/connector cancellation and acceptance of that trust result have one atomic ordering point: a stop request first leaves the write unconfirmed even if native invalidation is still draining or throws before publishing its cancelled bit; confirmation first preserves that confirmation when cancellation follows. This ordering does not itself protect the earlier write or establish native invalidation completion.

The ordering point is shared by the whole owner, but confirmation is consumed per receipt and its exact scope, not once for the entire connection. The production `TrustCommitState` binds its exact issued role, and `bindProvider(exact, role)` permits only one provider to claim that role's receipt; a second provider or duplicate claim fails. The provider's own `commitObserver` must be that exact state object, so an unrelated state cannot claim a real provider and a no-op observer cannot be substituted for the child's ledger. A next-hop observer shares the same cancellation and aggregates all preceding uncertain receipts, while checking the same registry, original view, connection, chain binding, owner, deadline and adjacent hop. It additionally consumes the previous session's one-shot [JumpAdmission](ssh-proxyjump-child.md#java--jni-child-session-ownership), which requires observed authentication success and checks the exact Java ancestor chain at issuance and consumption. This is an assembly check, not an atomic native ancestor/cancellation effect guard; native `beginJump` still checks the real proof. One provider covers one initial trust write for that hop; another write requires a separately designed KEX-bound transaction. If constructing a connector-owned provider fails before ownership transfers, close its original snapshot receipt while rethrowing the original failure; a successful provider owns that receipt.

Screen lock immediately records cancellation and publishes the existing pending state. Factory admission and lock are ordered by the owner; if a trust-writing synchronous `create` has already entered, do not complete the connection result before it returns or throws and reports its durable-write status. A lock before admission can complete normally because that factory cannot enter. Service-start rejection follows the same rule for an entered trust-writing factory. This capability is independent of later automatic-credential-save tracking. Snapshot that tracking at screen lock using the Factory's non-blocking metadata getter: preserve the pre-existing credential-save failure if it was already tracked, or retain the screen-lock result if the tracking flag changes later. If screen lock and service rejection both defer an otherwise immediate result, retain the first; also handle a factory that returns a session after cancellation and keep its cleanup owner.

If a NEW or CHANGED write may have committed but did not receive native acknowledgement, invalidate its pending proof, lock the vault and return `VAULT_COMMIT_UNCERTAIN`; native cancel/close failure must not hide that receipt. A lock with no possible write retains `SSH_SCREEN_LOCKED`, and ordinary cancellation after confirmed trust remains ordinary. Preserve the actual written state; never assert the durable effect was rolled back.

## 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Real store may have committed, native acknowledgement absent | Lock vault and report `VAULT_COMMIT_UNCERTAIN` |
| Screen lock during an entered trust-writing factory | Revoke promptly; wait for its synchronous write outcome before completing the connection result |
| Screen lock before factory admission or before any write | No new trust effect; preserve `SSH_SCREEN_LOCKED` |
| Cancellation wins the atomic ordering before trust confirmation | Reject confirmation and retain uncertain status for a possibly committed write |
| Stop is requested while native invalidation drains or throws | Reject later confirmation; `getAsBoolean()` reports Stop independently of whether native invalidation completed |
| Role/view revocation or provider invalidation wins before confirmation | Reject confirmation and retain uncertain status for a possibly committed write |
| Native confirmation wins before subsequent cancellation | Preserve confirmed trust; use ordinary cancellation semantics |
| Selected credential requested while NEW or CHANGED confirmation is pending | Deny it until native completion and owner acknowledgement |
| Different provider, role, receipt scope or duplicate confirmation | Reject without consuming another receipt or claiming a completed trust result |
| Later hop has committed but is unconfirmed | Keep the whole owner's result uncertain even when earlier receipts were confirmed |
| Native callback cancel/close fails after possible commit | Preserve uncertainty and retain the existing retryable cleanup owner |
| A credential save was already tracked when screen lock occurred | Retain its prior failure mapping rather than replacing every error with screen lock |
| Screen lock and service rejection defer results in the same `create` | Preserve their first applicable result, including when `create` returns a session |

## 5. Good / Base / Bad Cases

Good: a real NEW candidate is written, its exact native KEX returns, and the owner confirms before credential use. Base: screen lock before admission stops the attempt without inventing a write. Bad: publish `SSH_SCREEN_LOCKED` while a store commit is still in flight and later silently lose its uncertain receipt.

## 6. Tests Required

Use real `AppSecrets` encryption and trust-write logic with controlled barriers after the test store commits and before the provider records its result; identify an in-memory test store as such rather than claiming an actual AtomicFile commit. Drive screen lock through the actual owner and final bridge mapping. Test both cancellation-before-confirmation and confirmation-before-cancellation for NEW and CHANGED, no-write and pre-admission locks, service rejection, and native callback cleanup failures. Inject real host JNI invalidation failure before the cancelled bit and hold an admitted native effect while invalidation drains; check that later synthetic receipt confirmation fails without presenting it as a real store write. Exercise Java session orchestration with clearly identified fake native bindings separately from JNI, Android and real-server tests.

## 7. Wrong vs Correct

Wrong: infer no durable effect from a cancelled callback or a not-yet-set write flag. Correct: order admission and final result, record the real store outcome, and keep the receipt pending until that exact native KEX is acknowledged.

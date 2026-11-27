# SSH native view-borrow ownership

## 1. Scope / Trigger

A live native client or live CONFIG evaluator consumes a `ctos_ssh_view_borrow` while reading a protected file view. These owners must not consume or release the same borrow concurrently, including across otherwise unrelated sessions. Detached CONFIG copies do not consume a live borrow. This is an internal linear-ownership contract, not a public generational handle API.

## 2. Signatures

```c
int ctos_ssh_view_borrow_claim(struct ctos_ssh_view_borrow *, const void *owner,
    struct ctos_ssh_view_borrow_claim **);
void ctos_ssh_view_borrow_claim_abort(struct ctos_ssh_view_borrow_claim *);
int ctos_ssh_view_borrow_claim_release(struct ctos_ssh_view_borrow_claim *, const void *owner);
int ctos_ssh_view_borrow_claim_release_live(struct ctos_ssh_view_borrow_claim *, const void *owner);
```

The claim token is private to its constructing owner. The existing live check is a short, registry-locked preflight; actual view acquisition and dereference by a consuming owner happen only after a successful claim.

## 3. Contracts

One registered borrow can have at most one client or live-evaluator claim. A second claim and ordinary release of a claimed borrow fail without consuming it. A route evaluator's children share and pin its root evaluator; they do not independently claim the root borrow. A constructor that fails before publishing its owner may abort its private claim and return the borrow to the caller. Abort is the constructor's last access to the borrow and cannot fail; the token must not escape, be transferred, or be reused. Once an owner is published, release only through its claim and the original owner, thread, PID and UID. Preserve the claim and owner if release fails so the same owner can retry; the underlying view can instead become poisoned after a real destroy failure, so retry is not guaranteed to succeed.

One live managed handle can issue distinct borrows to different consumers; each borrow still requires its own exclusive claim. Retiring one claimed borrow does not revoke the common handle or the others. Handle revocation fences every remaining borrow and may return successfully while those borrows still pin destruction. To establish complete release, prove a new handle can be created after all original owners have cleaned up; an idempotent destroy return alone is insufficient. Native sharing alone does not provide a product chain owner, fresh trust snapshot or cross-hop effect authority.

Promotion uses `claim_release_live`, which checks the exact claimed borrow's liveness and releases it under the same registry lock used by managed-view revocation. A prior live preflight followed by ordinary release has a revocation gap. If revocation or expiry wins the lock, promotion fails and the claim remains held for ordinary cleanup; if release wins, later revocation does not restore authentication authority. Ordinary cleanup still uses `claim_release`, which must remain possible after revocation. The caller-owned test-view form does not provide a synchronized external revocation primitive and retains its existing linear-lifetime restriction.

Caller ownership is linear: after transfer or successful release, the old pointer and claim must not be used. The registry does not provide generation protection if a freed address is later reused, nor does `borrow_view` independently authenticate a reader. A caller-owned view must outlive its borrow. No ownership claim grants an SSH host proof, authentication, or transport authority.

## 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Same live borrow requested by a second consuming owner | Reject with `EBUSY`; original owner retains its resources |
| Separate borrows from the same live managed handle | Permit independent claims; retirement of one does not revoke the rest |
| Shared handle revoked with surviving borrows | Fence each borrow; keep cleanup owners until the last release permits handle destruction |
| Generic release while a claim exists | Reject with `EBUSY`; do not disturb the claim |
| Wrong owner, thread, PID or UID on claimed release | Reject without changing the original ownership |
| Constructor fails before publication | Abort its private claim, leaving the borrow with the caller |
| Release or view destruction fails | Retain cleanup state and report the error; do not claim recovery when the view is poisoned |
| Managed view revoked or expired before promotion's locked live release | Reject promotion, retain claim, and permit same-owner ordinary cleanup |
| Managed view revoked after successful locked live release | The view stays revoked; the retired borrow is gone and no new auth/file rights are created |
| Borrow expired or revoked before construction | Preserve existing live/error precedence over later alias and text validation |
| Allocation failure and malformed non-null view fields or paths occur together | Allocation may now fail first because the view must not be dereferenced before claim; do not promise the old OOM precedence |
| Released raw pointer, replayed claim or externally destroyed caller-owned view | Outside this internal contract; do not claim ABA or dangling-pointer protection |

## 5. Good / Base / Bad Cases

Good: a client claims its borrow, reads the view, and releases on its original owner; a separate evaluator uses a separate borrow. Base: a failed unpublished construction aborts the claim and the caller remains responsible for the borrow. Bad: pass a published owner's borrow to another client, release it behind the owner, or treat a raw address as a reusable capability.

## 6. Tests Required

Exercise real client and evaluator collision paths plus the common claim race, invalid-owner checks, unpublished abort, claimed-release failure and retry, revoked/expired preflight precedence, and real view-destruction failure injection. Verify both deterministic revoke/release orders and simultaneous races on an actual managed handle. Exercise three distinct borrows and verify their individual retirement, common revocation and creation of a new handle after cleanup. In the native promotion fixture, inject revocation after its preflight and before locked retirement; both a direct shell and a jump must fail cleanup-only. Test live and detached CONFIG paths and their side-effect/capability counters. Run actual fixed-parser binaries as well as compile tasks; distinguish host sanitizer, ARM64/QEMU, JNI and Android execution. A race without address reuse does not test ABA protection.

## 7. Wrong vs Correct

Wrong: assert that callbacks alone make every borrow exclusive, or claim a pointer registry safely accepts replayed stale addresses. Correct: reserve the live borrow once for each consuming native owner, keep its opaque claim private, and preserve the original ownership until cleanup actually succeeds.

## Java shared view owner

The shared owner's own cancellation and close uncertainty is specified separately in [shared-view cancellation](ssh-shared-view-cancellation.md); its local flag alone does not prove native lease revocation or resolve independent lease operations.

### 1. Scope / Trigger

Use when more than one Java native-session owner must retain the same `SshPublicFileLease`. The internal entry is not yet called by the product ProxyJump connector and does not own config evaluators or relay resources.

### 2. Signatures

```java
SshSharedPublicView(SshPublicFileLease lease);
void seal() throws VaultException;
void cancel();
void close() throws VaultException;
SshNativeSession.openManaged(callback, sharedView, socket, knownHostsPath, candidateId, stepBudget);
```

### 3. Contracts

The production session entry accepts only an owner constructed from an actual lease, and each callback must own that exact object as its role view. It issues at most one binding per callback and at most `SshRoleContext.MAX_HOPS` bindings. A binding tracks one session's lifecycle; closing the binding does not close the common lease. Target `beginShell` retires its binding only after native promotion succeeds; normal session cleanup retires it only after native close and role cleanup. The owner must be sealed before normal close, and every issued binding must be retired. Cancellation seals and requests revocation of the original lease even with pending bindings; `VaultSession.lock` and original-operation cancellation continue to revoke that same lease directly. A failed or unconfirmed invalidation must retain the owner and cannot be reported as a confirmed native revoke; see [vault and lease failure ordering](ssh-vault-revocation-ownership.md). Normal close, issuance and binding retirement are restricted to the creating owner thread; cancellation may come from another thread.

If open fails before a positive native handle, abort its unadopted binding and allow retry. Once socket adoption has also completed, a later failed session cleanup retains the original socket/session owner and the binding until that owner successfully retries. Source-close failure is retryable. This bookkeeping does not prove native claim release or cover independent native consumers: each owner must separately complete and verify its own cleanup. During future JNI child integration, preserve both session/binding and socket ownership on every exception after a positive handle, including any new reentrant or fallible adoption step.

### 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Production owner is not backed by the exact managed lease | Reject before native open |
| Callback belongs to another view, is duplicated, or owner is sealed | Reject issuance; existing owners remain intact |
| Normal close before seal or while any binding is pending | `SSH_SESSION_CLEANUP`; preserve the original lease |
| Cancel or vault lock with pending bindings | Request original lease revocation and keep cleanup owners; an unconfirmed failure is not a confirmed revoke |
| Failed pre-adoption open | Retire the unadopted binding and permit retry |
| Post-adoption cleanup or lease close failure | Preserve ownership and retry on the original owner thread |

### 5. Good / Base / Bad Cases

Good: the target retires its binding while an ancestor still uses the lease, then the ancestor retires and the sealed owner closes it. Base: cancellation revokes the lease immediately and each native owner still closes independently. Bad: pass a different lease token under a role's view identity or close the shared lease when only the target has retired.

### 6. Tests Required

Exercise `SshNativeSession` with two session owners and fake native bindings, wrong lease/callback, repeated or late issuance, cancellation, pre- and post-adoption failure, and retry after close failure. Separately exercise the real Java lease and native handle with actual independent borrows and actual `VaultSession.lock`; identify manually driven session-adoption and fake callbacks. Before product enablement test the real JNI parent/child chain, real claim retirement, reverse cleanup, Android, cross-hop effects and actual servers.

### 7. Wrong vs Correct

Wrong: treat a Java binding count as proof every native claim has gone away. Correct: account for each confirmed session retirement and separately retain and clean up all other native owners.

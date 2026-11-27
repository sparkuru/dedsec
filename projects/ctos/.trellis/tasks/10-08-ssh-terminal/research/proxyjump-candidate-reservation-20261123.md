# Research: ProxyJump signed candidate reservation

- Query: Can an approved signed-NEW fixture review reserve the exact managed candidate against competing consumption and cleanup without granting a trust write?
- Scope: internal source inspection only; no implementation, dynamic tests, device, account or network operations.
- Date: 2026-11-23

## Files found

- `android/app/src/main/java/im/majo/ctos/SshManagedKnownHostsProvider.java`: snapshot and candidate ownership, synchronized proposal consumption and retryable close.
- `android/app/src/main/java/im/majo/ctos/SshKnownHostsCandidateHandoff.java`: single-use native-body acceptance and provider abort ownership.
- `android/app/src/main/java/im/majo/ctos/SshNativeCallbackAdapter.java`: signed fixture pending state, review issuance, liveness checks and cancellation.
- `android/app/src/main/java/im/majo/ctos/SshHostKeyReviewMailbox.java`: exact UI ticket and bounded owner polling before returning a decision.
- `android/app/src/main/java/im/majo/ctos/SshNativeSession.java`: original child/session/parent/callback binding and original hop deadline.
- `android/app/src/main/java/im/majo/ctos/SshSessionOwner.java` and `SshRoleContext.java`: cancellation, role/view revocation and native invalidation entry.
- `android/app/src/test/jni-vault-chain/im/majo/ctos/SshFreshVaultRealJniTest.java`: real Java/JNI fixture, reflective candidate replacement, source closure and review-race scenarios.

## Findings

### A claimed review is not proof of approval

`SignedNewFixtureReview` holds the exact callback, pending evidence, observation and three independent digests. `claim(role)` sets `claimed = true` before the mailbox presents a ticket (`SshNativeCallbackAdapter.java:209-267`; `SshHostKeyReviewMailbox.java:173-193`). A caller must not interpret `claimed` as user approval. The mailbox checks the final decision and live/deadline state, then retires the ticket and dismisses the sink in `awaitDecision`'s `finally` (`SshHostKeyReviewMailbox.java:233-308`). It currently returns `void`; neither claimed review nor a signed-KEX or idle observation is a one-shot approval receipt.

If a later fixture must reserve only *after* approval, the approval's exact ticket/review/child/owner must be conveyed by a separate one-shot result created only on the successful decision path. Missing UI, rejection, cancellation, expired budget, stale source or a throwing dismissal must not publish it. Wrong role/session/thread attempts must not consume another owner's valid review. This result is at most non-authorizing metadata: it must not be a `SshHostKeyAuthority.Proof`, a candidate body, a trust CAS receipt or permission to authenticate.

### What the provider can atomically order

`requireProvisionalFixtureCandidate` holds the provider monitor and checks exact source receipt/adapter, candidate object identity, candidate ID, NEW evidence and matching digests, but explicitly does not reserve the candidate (`SshManagedKnownHostsProvider.java:602-615`). `proposalFor` and `changedProposalFor` hold the same provider monitor while `proposalForExact` checks, clones and consumes the candidate, creates a proposal, then closes and clears the candidate (`:755-813`). `acceptsNativeCandidate` also checks and installs under that monitor (`:592-600`). Therefore one provider-monitor critical section that checks the exact same state and installs a private, one-shot marker can linearly order **these provider-mediated** proposal/installation operations: either the earlier consumption wins and reservation fails, or the reservation wins and later ordinary proposal creation rejects without consuming it.

The candidate need not be detached or copied for this milestone. Keep the original body owned by the existing provider and make the marker carry only opaque owner/candidate identities. The provider must check the marker before every candidate-consuming or candidate-replacing provider method, not just in the new reservation method. Reuse, a foreign provider/source/role/session/review or a different candidate identity must fail without releasing another owner's marker. A release or failed final live recheck must either revoke the exact marker and leave a precisely specified cleanup-only state or close through the established handoff; do not silently fall back to an ordinary NEW proposal.

`failClosed()` first takes `cleanupLock`, then the provider monitor, marks the provider and snapshot closing, performs a checked invalidation and later closes the candidate and snapshot (`SshManagedKnownHostsProvider.java:976-1033`). `SshKnownHostsCandidateHandoff.close()` sets `closing` before invoking that abort and marks closed only after it succeeds (`SshKnownHostsCandidateHandoff.java:81-90`). Reservation must not prevent this path from revoking and wiping the candidate. At the provider's `closed` transition, revoke any active marker under the same provider monitor; if invalidation or dependent close fails, retain the existing exact handoff/child/parent owner and uncertainty for retry. A reservation object must not own an independent body whose lifetime outlives that cleanup.

### Candidate aliases limit a provider-only guarantee

`KnownHostsCandidate.consume()` and `close()` have their own candidate monitor (`SshManagedKnownHostsProvider.java:485-500`); neither currently checks a provider reservation. Package-level `candidateFor`/`bind` can leave the producer with an object reference, although source search finds their direct `bind` usage in tests; the ordinary production native handoff creates the candidate locally, passes it into the provider and drops its local reference (`SshKnownHostsCandidateHandoff.java:54-71`). Thus a provider-only marker prevents the actual provider-mediated proposal race, but cannot claim to prevent direct `consume()` or `close()` by a holder of a leaked candidate alias. A stricter candidate-level invariant requires identity-checked reservation/consume on the candidate monitor or limiting direct access, while allowing close to revoke and erase. Reflection in fixture modes 62/63 deliberately replaces the private field and closes the old candidate (`SshFreshVaultRealJniTest.java:265-290`); it is test instrumentation, not evidence that an untrusted product caller can do this.

The existing lock nesting used by `requireProvisionalFixtureCandidate` and `proposalForExact` is provider → candidate → snapshot → role/public view; candidate matching calls `SnapshotReceipt.requireLive` and role liveness (`SshManagedKnownHostsProvider.java:135-142,437-482,605-615`). Callback review checks release `stateLock` before session, source and provider checks (`SshNativeCallbackAdapter.java:1120-1144`). Preserve this separation: do not introduce provider → callback/stateLock or acquire provider/vault/session/callback monitors after taking a short native effect permit, and do not perform I/O or invoke UI under those monitors. No existing source inspection establishes that a proposed new nested lock order is deadlock-free.

### Cancellation and deadline are not automatically ordered by the marker

Callback `cancel()` records its own cancellation under `stateLock`, then drops that lock before discarding the pending fixture and aborting the handoff (`SshNativeCallbackAdapter.java:1962-1974`). Provider close has a separate monitor and may happen later. A reservation can consequently race in the interval after callback cancellation starts but before provider `closed` is set. Conversely, a provider reservation can linearize first and then be revoked by close before its caller resumes. Pre/post `requireLive` checks reject many observed cases but cannot prove an atomic order across these distinct transitions; a marker with no body and no effect authority limits the consequences but does not remove the race.

`SshSessionOwner.Cancellation.cancel()` begins the native invalidation while holding its monitor (`SshSessionOwner.java:40-47`); `SshRoleContext.close()` likewise begins invalidation while holding the role monitor (`SshRoleContext.java:191-195`). An invalidator waits for active native effects, so a holder of an effect permit must not then wait for those monitors or provider/vault/session locks. Native effect generation detects invalidations but does not encode time (`ssh_role_bootstrap.c:43-67`); the child session separately tracks its original guarded hop deadline and rechecks owner/source/role liveness (`SshNativeSession.java:1424-1453,1511-1534`). A provider reservation can honestly say only that its own candidate transition was ordered; it does not prove cancellation, role/view revocation or deadline could not win concurrently, nor authorize a later persistent write. Future admission needs its own proven ordering and uncertain-outcome contract.

### Deterministic validation candidates

1. Use barriers on the actual provider monitor to order `proposalForExact` versus reserve in both directions; assert exactly one provider-mediated operation wins, no unrelated candidate is consumed, and ordinary `proposalFor` remains unchanged when no marker exists.
2. Verify no marker before actual mailbox approval, after refusal, missing sink, cancellation, deadline expiry or throwing sink/dismiss; reject duplicate receipt/marker, wrong callback, child, role, source, candidate ID/identity, cycle or owner thread without destroying another owner's valid object.
3. Race reserve with provider abort/close and with the existing checked invalidation failures. Check retained child/claim/parent on unconfirmed cleanup, independent digest wipe, and no duplicate native invalidation. Separately demonstrate callback cancellation/source closure before and after provider-marker installation; label any outcome not jointly ordered as a snapshot rather than success after cancellation.
4. If the chosen contract claims protection against a candidate alias, exercise direct candidate `consume` and `close` with barriers as well as provider-mediated proposal; otherwise document the narrower owner-controlled native handoff boundary. Do not treat reflective field substitution as a public product attack.
5. Use real Java/JNI and the encrypted in-memory vault fixture to obtain signed review, user decision and the real candidate. Assert no `Proof`, proposal/CAS, write, credential request or shell is produced; keep fake-JVM, native, Android and real-server evidence distinct. Existing source-change/consume and cleanup modes 60–78 are regressions, not proof of the new operation.

## External references

None. Only local source, existing project research and specifications were examined.

## Related specs

- `.trellis/spec/backend/ssh-proxyjump-child.md`: signed-only review, bounded input, owner and cleanup contracts.
- `.trellis/spec/backend/ssh-trust-commit-ack.md`: direct-path write and acknowledgement/uncertainty contract.
- `.trellis/tasks/10-08-ssh-terminal/research/proxyjump-new-write-boundaries-20261123.md`: broader candidate, persistence and reconnect boundaries.

## Caveats / Not Found

- No code change or test was made. The proposed marker is a design candidate, not an existing facility or a proven lock-order solution.
- A signed fixture decision and provider reservation do not create an atomic cross-hop cancellation/deadline admission, durable trust-write authority, a detached post-close capability or a confirmed persistence receipt.
- A successful snapshot cannot prove that a later server disconnect has not occurred. No packet, remote host, device or account was examined in this research.

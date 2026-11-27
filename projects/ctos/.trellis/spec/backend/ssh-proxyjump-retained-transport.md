# SSH ProxyJump retained native transport

## 1. Scope / Trigger

Use when changing native lifetime or proof traversal for a managed ProxyJump
chain. This primitive alone is not a Java chain owner, an atomic effect guard,
a complete product connection, or Android background-session integration.

## 2. Signature

```c
int ctos_ssh_native_client_retain_jump_transport(struct ctos_ssh_native_client *client);
```

## 3. Contract

Before explicit promotion, the original deadline, callback, role and view
proofs continue to apply. Only the original owner thread/PID/UID may promote.
The JUMP itself and its exact linked relay must be live; the exact next role
must already own a successfully authenticated, established, running child.
The whole descendant suffix must already be retained, valid, running and free
of EOF/close, and the original ancestor chain must still be valid. Promote from the target outwards,
while all original role deadlines are live. A missing child, an opening or
unauthenticated child, a parent-first attempt, EOF, or an unrelated owner must
not grant retained authority or consume the caller's existing borrow.

On success retain the JUMP callback/role/KEX/session and original owner, retire
its own view borrow and credential selection, and keep its channel and exact
child/relay linkage. Recheck after promotion before reporting success. A partial
failure terminalizes the affected client and preserves its existing cleanup
owner; retry close on the same thread rather than pretending rollback succeeded.
The final live-view decision and borrow retirement are one operation under the
managed handle's revocation lock; an earlier liveness preflight is not enough.
Recheck the original role deadline before publishing, even if the view has a
later deadline; expiry after retirement makes the client cleanup-only.
Never retain or renew config, credential, trust, or callback authority. Repeated
authentication API probes after promotion must be rejected without poisoning a
still-live established transport; a later I/O proof still detects cancellation.

Established proof validates both the recursive upstream chain and the exact
retained descendant suffix. The downward walk must use self-only proofs so it
cannot recurse child -> parent -> child. Exact retained role snapshots,
callback ownership/cancellation, KEX, SSH/session binding and live relay
ownership remain mandatory. Ordinary EOF/CLOSED remains distinct from
cancellation and accepted bytes may drain under valid proofs. Child/relay and
parent cleanup remains reverse-ordered and retryable.

The direct Java session closes its lease when the target shell is promoted;
the restricted product single-jump chain uses the shared-view and JNI child
owners. The ARM64 fixture uses
separate owned views; the host-JNI multi-hop fixture also exercises three real
borrows of one managed native handle. The latter uses synthetic Java callbacks
and a manually registered child, not the production shared Java lease. A trust update
changes the encrypted store while the original view is a snapshot. A basic
encrypted-vault fixture confirms a prior request is invalidated and a newly
issued request using the old digest fails CAS; it uses the legacy test seam and
an in-memory store, not a product shared-view chain. Do not reuse the old
snapshot for later hop CAS without an explicit design and real-chain tests.
The initial product path supports known keys and protected saved-password effects;
it does not authorize trust writes. Multiple jumps, NEW/CHANGED trust publication,
background service ownership and Android device interoperability remain open.

## 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| No child, descendant opening/unauthenticated/EOF, parent-first promotion | Reject without taking away the original view |
| Expiry or original view revocation before promotion | Reject under existing original-lifetime rules |
| Wrong thread/PID/UID | Reject without changing the original owner's session |
| Invalid child KEX/callback/role or unlinked relay | Reject promotion or fence retained chain I/O |
| View release fails after callback retention | Client becomes cleanup-only, claim remains owned for same-owner retry |
| Original deadline/view ends after full promotion | Established data can continue, while new auth and secrets remain denied |
| View revocation interleaves after preflight and before locked retirement | Reject and retain cleanup ownership; no established transport is published |
| Role expires during retirement while its view remains live | Reject as cleanup-only even if the borrow was already released |
| Any ancestor or descendant cancellation | Fence dependent output and relay use; never publish stale buffered data |
| Normal EOF/close | Permit already accepted data to drain while proofs remain live |

## 5. Good / Base / Bad Cases

Good: the target reaches a live shell, each exact parent is promoted in
deepest-first order, and data continues after the original deadline while new
authentication is denied. Base: the child is still opening and the parent's
promotion attempt leaves its original proof and cleanup owner intact. Bad:
promote a JUMP because its channel opened, or reuse the retained transport as
authority to access secrets or commit trust.

## 6. Tests Required

Use real pinned native packet engines and synthetic multi-layer peers. Exercise
deepest-first promotion, child not ready and wrong owner, actual deadline
expiry, post-promotion view revocation, both directions of data, auth-probe
rejection without transport failure, cancellation at every depth, child KEX
loss, release failure/cleanup, and ordinary closed-channel drain. Run the direct
shell expiry and existing pre-promotion/multi-hop regressions too. Distinguish
loop-inclusive assertion counts from test-case counts, and separately record
host JNI, Android, production Java, real store, device and real-server evidence.

## 7. Wrong vs Correct

Wrong: run the child's full recursive proof from the parent's downstream proof,
creating child-to-parent recursion. Correct: walk exact retained descendants
with self-only proofs, while independently checking the recursive upstream
chain and keeping all owners for reverse-order cleanup.

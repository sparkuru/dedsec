# SSH ProxyJump native child ownership

## 1. Scope / Trigger

The internal native child constructor binds an authenticated JUMP channel, its local relay, and the immediately following SSH client. It begins under the original lifetime; the separate [retained-transport primitive](ssh-proxyjump-retained-transport.md) may later promote established jumps. The restricted single-jump product connector uses these Java/JNI APIs with an additional effect fence for saved-password delivery. Neither the constructor nor a sequential ancestor check alone makes callback-internal effects or asynchronous Java publication atomic with cancellation; NEW/CHANGED trust writes are not enabled by the current product path.

## 2. Signatures

```c
int ctos_ssh_native_client_open_jump_child(struct ctos_ssh_native_client *parent,
    const struct ctos_ssh_native_client_config *config,
    struct ctos_ssh_native_client **child,
    struct ctos_ssh_native_jump_relay **relay);
int ctos_ssh_callback_client_claim(ctos_ssh_callback_token, void *,
    ctos_ssh_role_token, const void *owner);
int ctos_ssh_callback_client_close(ctos_ssh_callback_token, void *, const void *owner);
```

The parent already owns an authenticated `direct-tcpip` channel bound to the exact next role. Child config requires `socket_fd == -1` and `owns_socket == 1`; the constructor creates its own nonblocking, close-on-exec Unix socketpair and supplies the corresponding ends to the child and relay. The ordinary native open accepts only hop zero; a nonzero hop must use the guarded constructor.

## 3. Contracts

All roles retain their original connection, identity, binding, deadline and owner. The child receives its own callback, view borrow and SSH proof; the callback must be live, belong to the exact child role, and be exclusively claimed by that client. A claimed callback can be closed only by its client, and a failed release retains the claim. The child cannot reuse any ancestor's exact borrow object. Native clients and live CONFIG evaluators also reserve each borrow exclusively across unrelated owners; its private claim and linear-pointer limits are specified in [view-borrow ownership](ssh-view-borrow-ownership.md).

The child and every earlier relay/client remain linked until child close succeeds. Child KEX, host verification, selected credentials, authentication results, channel operations and output use native pre/post checks of the live ancestor chain. Before promotion each ancestor must retain its original authenticated role, KEX/session proof, callback and view. A successfully promoted ancestor instead keeps the exact retained transport and descendant proofs described in the retained-transport contract. Parent or relay invalidation fences later child use even when encrypted bytes are already queued on the child socket. Normal channel EOF and close are not themselves cancellation: while the proof and relay remain valid, previously accepted encrypted bytes may drain.

Constructor failure must be inspected through **both** output pointers. If child is returned, it is terminal and cleanup-only, and owns its callback, view and socket; if neither owner is returned those resources remain with the caller. A returned relay without a child is a cleanup tombstone. Do not retry a failed construction while discarding a returned owner. A child blocks relay close; a relay blocks parent close. Close children before their relays and ancestors, preserving every incomplete owner for retry on its opening thread/PID/UID.

Callback release, view release and descriptor close may complete on different attempts. On Linux/Android, mark an owned fd relinquished immediately when calling `close`, even if `close` reports an error; a subsequent retry must never close a reused descriptor number. Close and destroy validate the client's captured thread, PID and UID independently of whether its callback has already closed.

Pre/post checks cannot undo a host-trust CAS, secret read or other effect performed inside a synchronous callback. The saved-password product path uses separate effect permits and coordinated cancellation; these do not authorize NEW/CHANGED trust writes. Additional trust effects and background ownership require their own design and tests. The original role lifetime is unchanged for all authentication and unpromoted hops.

The internal [callback-scoped ancestor check](ssh-proxyjump-callback-proof.md) permits the exact native-dispatched child callback to sample the original upstream proofs without reacquiring the session JNI table. It is sequential, does not imply a writable channel after EOF, and does not make a later effect atomic.

## 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Wrong or already claimed callback, wrong next role, ancestor borrow alias | Reject without consuming the parent's relay/channel or another owner's callback/view |
| Relay setup fails after child construction | Return a terminal cleanup-only child; retain its claimed callback, view and fd until owner cleanup succeeds |
| Wrong thread, PID or UID, including after partial close | Reject without changing the original owner's resources |
| Ancestor cancel, pre-promotion expiry/view revocation, or transport proof mismatch | Fence child KEX, authentication and publication, clear returned buffers/lengths, retain cleanup owners |
| Ordinary upstream EOF/close with accepted buffered bytes | Retain valid proof and allow accepted bytes to drain; do not equate EOF with cancellation |
| Callback or view cleanup fails | Keep child and parent pinned for retry; do not close a callback belonging to another client |
| Owned fd close reports an error | Report the error, tombstone the fd, and let the same owner finish cleanup without closing a reused number |

## 5. Good / Base / Bad Cases

Good: the parent opens a child with an exact, distinct callback and borrow; the child independently verifies its own host, authenticates and exchanges bytes while every ancestor remains valid. Base: a failed open returns a cleanup-only owner that the same event loop releases before the parent. Bad: attach an already-running ordinary child to an arbitrary socket, reuse an ancestor borrow, or treat a parent proof as the child's own host proof.

## 6. Tests Required

Exercise real pinned SSH packet engines and synthetic peers for at least two encrypted layers and a three-layer ancestor cancellation. Assert independent host proofs and authentication, exact socket/role/callback binding, queued encrypted bytes on cancellation and normal EOF, zeroed output, wrong owner, invalid/reused callback, ancestor borrow alias, callback-release failure, fd close error and descriptor reuse, and cleanup-only child after relay failure. Preserve existing direct, authentication, callback and shell regressions under ARM64/QEMU; distinguish compile-only, JVM, Android/JNI and real-server results. Fault tests must identify the actual injected error: an `EBADF` test does not prove an injected `EINTR` or `EIO`.

For a real-server check, the project-owned [local Docker fixture](../../../design/ssh-proxyjump.md#本机隔离-openssh-跳板测试环境) runs a stock jump and target sshd on container loopback and can execute the ARM64 native driver under QEMU. Assert each driver's own `direct-tcpip` event and distinguish an actual changed-host rejection before credential release from an arbitrary connection failure. The test-owned public view and callbacks do not establish Java/JNI/vault, first-trust, product, device or background behavior. User authorization to create local task-scoped fixtures is recorded in the [project policy](../trellis-plus/index.md#docker-development-entry-point); Docker sandbox approvals still apply.

The `--client` static test runner alone defines `CTOS_SSH_NATIVE_TEST_HOOKS` for narrow KEX invalidation, release failure and promotion-revocation tests. Production builds and other fixture modes must not define the macro or expose these hooks; verify the release artifact separately. KEX revocation before and after shell promotion must change an initially true getter to false while the child's callback and ancestors remain valid.

## 7. Wrong vs Correct

Wrong: infer full ProxyJump support from a local relay and native ancestor checks, or say a native check made a Java CAS atomic. Correct: enable only paths backed by the required proof/effect and publication guards, chain ownership and applicable interoperability tests; treat trust changes, multi-hop and background behavior as separate work.

## Java / JNI child-session ownership

### 1. Scope / Trigger

Use for the JNI-backed parent/child path. The product connector uses it for the restricted single-jump path; production Java/JNI, real vault effects, Android and real servers still need separately scoped evidence.

### 2. Signatures

```java
// Fake-view, fake-native JVM fixture only:
SshNativeSession.JumpChildOwner prepareJumpChild(parent, callback, shared, path, candidateId, stepBudget, fixtureBinding);
SshNativeSession.JumpChildOwner prepareJumpChildFresh(parent, callback, shared, path, candidateId, stepBudget, source, childTrustState);
JumpAdmission issueJumpAdmission(previousIssued, nextIssued, shared, originalView);
void beginJump(JumpChildOwner next);
SshNativeSession JumpChildOwner.open();
JumpStatus jumpStatus();
void pumpJumpRelay();
void retainJumpTransport();
void JumpChildOwner.close();
```

The private JNI `Native` exposes `reserveJumpChild`, `openJumpChild`, `openJumpChildFresh`, `jumpChildState`, `abortJumpChild`, `beginJump`, `jumpStatus`, `pumpJumpRelay` and `retainJumpTransport`. Its status tuple is `[phase, remoteEof, localEof, bufferedBytes]`, with phases OPENING=1, OPEN=2, CLOSED=3, REJECTED=4 and booleans encoded as 0/1.

### 3. Contracts

Create and retain the Java child owner before reserving a native token. The native reservation pins the exact parent and opening thread. Open uses the same view token and original deadline as that parent; it stores every returned client, relay or unclaimed borrow before reporting failure. State 0 is reserved/not attempted, 1 failed with no client, 2 failed with a cleanup-only client, 3 failed with a cleanup-only client and relay, and 4 is the open client and relay. State 0/1 is aborted; state 2/3/4 is closed then destroyed. A thrown open does not imply state 0/1: even state 4 must be retained and cleaned up if the Java call failed after native ownership transfer. If state query or cleanup fails, keep the same owner, callback claim, view binding and token for an owner-thread retry. Cleanup-only sessions cannot step or pump. Parents cannot close while a child owner or reservation remains.

The fresh constructor requires the child provider's exact `ChildTrustSnapshot`: its receipt and one-shot wire come from the same authenticated vault read. Bind its role, original view, exact known-hosts path, connection, deadline, epoch and revision before opening. The callback checks native host evidence against that receipt's base digest, including KNOWN, NEW and CHANGED candidate/review paths. Wipe the transferred Java wire on every exit and retain native snapshot ownership through cleanup. Before an actual ancestor-effect guard exists, fresh children reject NEW/CHANGED trust effects and all authentication, selected-credential, KBI and discovered-password paths with `SSH_JUMP_EFFECT_GUARD`, after preserving applicable lifecycle failures. KNOWN classification/KEX can still be tested. The older source-free `prepareJumpChild` remains only as a fake-view, fake-native JVM fixture seam: it rejects a managed lease or the production JNI binding with `SSH_JUMP_EFFECT_GUARD`. It has no source-bound effect fence and must not be used to construct a product chain.

`JumpAdmission` is a one-shot Java assembly check issued by the exact parent session only after its current authentication result is SUCCESS, on its owner thread. It binds that issued parent and exact next issued role, shared view and original view; the role registry, v2 chain, owner, binding, deadline and adjacent hop must match. Nonzero-hop parents must be actual children of the same Java owner chain. Issuance and consumption walk that exact chain and sequentially check each ancestor's current liveness, without JNI reentry. A wrong object cannot consume another admission; a consumed admission cannot be reused. Per-hop trust-state construction consumes the admission outside its ledger lock and rechecks cancellation and pending writes. Production fresh child preparation requires that exact admission, the parent's actual callback/provider and its trust state, the child's exclusively bound state/provider and same-read snapshot, and the same `AppSecrets` owner. The provider must actually notify that exact state as its commit observer; passing an unrelated ledger to `bindProvider` does not bind it. A failed attempt before adoption leaves source and callback ownership with the caller; if a shared binding was issued, retire that unadopted binding, while the callback can remain bound to the original source and must be closed by its caller. Fake-view/fake-native fixture seams cannot take a managed lease or production JNI binding. Native `beginJump` independently checks actual USERAUTH_SUCCESS and ancestor transport. Neither Java admission nor sequential ancestor checks authorize a trust/credential effect or make cancellation atomic; same-vault identity and current revision alone do not prove all intervening vault changes came from this chain.

Destroy cleans the relay only after native child close. A relay `close(fd)` error may leave it logically closed; preserve the error and retry destruction without retrying that fd number. Only state 4 with post-open checks publishes the child session. A target shell can be RUNNING before its ancestors are retained; defer its confirmation action until every exact ancestor has been promoted, and do not hand the target to the product as connected until the chain is ready. Java ancestor liveness checks are sequential snapshots, not atomic effect or output admission. Do not call ordinary session JNI from a native callback while `sessions_lock` is held.

### 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Wrong parent, owner thread, view, original deadline or next role | Reject without leaking a reservation or borrow; preserve cleanup ownership |
| Parent still opening its jump channel | Reject child open without consuming the Java owner; retry when OPEN |
| Open fails after creating a child or relay, or returns an exception after state 4 | Fence use; retain and retry exact child then relay cleanup |
| JNI state query or child/relay cleanup fails | Preserve owner and token; allow the same thread to retry |
| Relay fd close reports an error and its number is reused | Never close that number on retry; finish destruction safely |
| Target runs before all ancestors are retained | Defer shell confirmation and connected publication |
| Ancestor cancels near final output or secret effect | Sequential Java checks alone do not provide atomic ordering; apply the separately bound output or effect admission and preserve cleanup owners |
| Fresh source, NEW/CHANGED trust effect or authentication before a real chain guard | Reject with `SSH_JUMP_EFFECT_GUARD`; preserve prior lifecycle error and perform no trust write or credential handoff |
| Legacy source-free child with a managed lease or production JNI binding | Reject with `SSH_JUMP_EFFECT_GUARD` before claiming a child; leave existing owners available for cleanup |
| Wrong/reused admission, unconfirmed prior trust, wrong owner/role/view or cancelled ancestor | Do not issue the next-hop trust state; retain the exact original owners for cleanup |
| Parent provider's real observer is another ledger, or child provider uses a no-op/foreign observer | Reject the binding; do not consume a valid parent admission or claim that later trust writes are tracked |

### 5. Good / Base / Bad Cases

Good: reserve, open the exact child, run it, retain parents from inside out, then close child/relay/parents in reverse. Base: a failed open yields state 2 and the same owner retries cleanup. Bad: discard an exception's native owner, or treat target RUNNING as proof the complete chain is ready.

### 6. Tests Required

Run Java session state-machine tests with fake native bindings for early-open retry, states 1–4, query/close/destroy failure, cancellation, wrong binding, two-level ancestor confirmation and reverse cleanup. Separately use real encrypted synthetic peers and the production JNI session table to test reservation, child open/pump/retention, wrong thread/view/deadline/role, pending borrow release, cleanup-only states and relay fd reuse. Label any deliberately injected state-3 result; do not call it a measured concurrency race. Build the Android JNI artifact and distinguish host JNI from ART, product Java, real encrypted vault and actual servers.

### 7. Wrong vs Correct

Wrong: interpret an exception as proof native construction produced no resources. Correct: inspect the reserved owner's state and retain every partial owner until its exact cleanup succeeds.

## Scenario: provisional NEW child key, native-only

### 1. Scope / Trigger

Use for the native precondition to eventual first-use trust on one jump. This
is a signed-KEX milestone, not a product trust flow. Production JNI currently
does not register the optional hooks. The synchronous hooks cannot wait for UI
or publish a trust write: a remote FIN/CLOSE may remain unobserved until the
owner returns and pumps the parent.

### 2. Signatures

```c
/* ctos_ssh_callback_target: all three optional hooks plus candidate abort. */
int (*provisional_new_candidate)(void *, void *, const struct ctos_ssh_known_hosts_candidate *);
int (*provisional_new_review)(void *, void *, const struct ctos_ssh_host_evidence *);
int (*provisional_new_kex)(void *, void *, uint64_t cycle, const unsigned char digest[32]);
int ctos_ssh_callback_provisional_new_candidate(ctos_ssh_callback_token, void *,
    const char *, const char *, const char *, const unsigned char[32],
    const unsigned char[32], unsigned char *, size_t, int *handoff_started);
int ctos_ssh_native_client_provisional_new_ready(struct ctos_ssh_native_client *, int *ready);
```

The review and KEX dispatch entry points are internal to native callback scope;
they are not production JNI entry points. A host JNI fixture registers the
three hooks and an exact receiver/stage checker only under
`CTOS_SSH_NATIVE_TEST_HOOKS`; production JNI registers none of them. The C
target structure has no size/version and must be fully initialized and rebuilt
together with its callers; it is not a cross-version binary ABI.

### 3. Contracts

Only a real hop-1 child of a root parent may enter the provisional NEW path;
direct calls, another callback/role, a deeper chain or absent/partial hooks fail
closed. Bind the candidate ID, exact role/endpoint, key and source digests to
the one-shot candidate, review and KEX sequence. Once the real target hook is
about to run, `handoff_started` transfers possible candidate cleanup to the
native owner even if that hook or its first abort fails. Retain child, callback,
relay and parent until the same owner confirms abort and can close in order.

The test-only Java receiver binds its pending state to the exact callback,
child role, source receipt, candidate ID, private opaque candidate identity,
evidence and invocation serials. Recheck the candidate and source before the
review/KEX stages; the checks are point-in-time observations, not an atomic
admission against a concurrent consumer or revocation. A fixture-only native
receiver/stage check must fail closed when its JNI symbol is absent. Ordinary
or replayed callbacks are rejected while pending; their pending data may be
retired on subsequent owner close rather than synchronously on the bad call.
`SshKnownHostsCandidateHandoff` enters `closing` before attempting provider
abort: no new `accept` is permitted after that point, even if abort throws;
only successful abort sets `closed`, and a failed abort remains retryable.

The KEX hook records only the same-key signature milestone and can run before
upstream output drains. It is not a durable host proof, CAS permission or
credential selection. Keep `completed` false; reject ordinary host/KEX proof,
credential, userauth, shell and transport promotion. `ready` is an owner-thread
snapshot after signed KEX and direct parent/socketpair/relay output drain, not
an admission against future cancellation. Pending liveness, guarded deadline,
generation or observed parent EOF/close failure makes the child cleanup-only;
release any short wire permit before aborting a candidate or entering callback
locks. Invalid arguments, wrong thread and non-pending calls are non-destructive.

### 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| No/partial hook set, direct/root/deeper child, forged or replayed dispatch | Deny provisional authority; never call a foreign target |
| Candidate hook begins then fails, abort fails | Preserve handoff and owner; retry exact abort before callback release |
| Wrong signature, rejection, cancellation, revoked view, timeout or observed EOF | No ready/proof/credential; retain cleanup ownership and wipe target-owned copies on successful abort |
| Signed KEX with relay/socketpair/parent output still buffered | `ready=0`; do not call this a trust-commit admission |
| Guarded hop deadline or generation expires before KEX | Deny and terminalize pending child; no later restored view can revive it |
| KNOWN, CHANGED, REVOKED with hooks configured | Preserve their ordinary classification and policy; do not treat them as provisional NEW |

### 5. Good / Base / Bad Cases

Good: the actual child reviews an exact NEW candidate, verifies the signature,
drains its one-hop output and reports only a live milestone. Base: output is
still buffered, so `ready=0`. Bad: treat the hook or `ready` as `CAS_COMMITTED`,
an ordinary host proof, permission to release a password, or evidence that an
unpumped network connection has not closed.

### 6. Tests Required

Exercise real encrypted child peers under ARM64/QEMU for valid/invalid
signatures, each callback failure, duplicate/forged scope, guarded deadline and
generation, buffered output, EOF/close, wrong thread, root/deeper chain, and
all authentication/transport APIs. Inject abort failure across close attempts
and verify exact-owner retry and candidate cleanup. Preserve KNOWN/CHANGED/
REVOKED and existing jump regressions. Test JNI receiver, real encrypted vault,
user review, CAS, restart and Android service lifecycle separately when added.

### 7. Wrong vs Correct

Wrong: reuse `SshHostKeyAuthority.Proof` for a pending NEW key or hold a native
permit while writing the vault. Correct: keep the pending milestone distinct,
then design and verify separate one-shot trust-write admission and retry.

## Scenario: provisional NEW idle snapshot and review waiting

### 1. Scope / Trigger

Apply to the internal hop-1 signed-NEW experiment and to an owner-driven review wait. Neither primitive connects the signed candidate to a product review or grants authority to write trust or release credentials.

### 2. Signatures

```c
int ctos_ssh_native_client_provisional_new_poll_idle(
    struct ctos_ssh_native_client *client, int *idle);
```

```java
void SshHostKeyReviewMailbox.requireApproved(
    SshHostKeyAuthority.Review review, long transportDeadline, PendingPoll poll)
    throws VaultException;
```

### 3. Contracts

The native snapshot is owner-thread-only and limited to the guarded one-child pending state, the original signed KEX cycle, role, view, parent, effect generation and deadlines. It checks the child OpenSSH input and partially decoded packet, inbound relay and parent channel buffers, plus the child and parent sockets. Observed child data or EOF fails closed; parent input awaiting dispatch or pending outbound drain returns `idle=0` so its owner can pump and retry. The function does not parse or write packets and releases the short wire permit before any failure cleanup or callback locks. It rejects even legitimate child `EXT_INFO` arriving during the wait; it is not a general SSH receive loop or a production JNI entry.

The observations are sequential snapshots. They cannot establish that a packet or close has not arrived immediately afterwards, that no event occurred before pending, or that a later write is authorized. In the pinned OpenSSH version `ssh_packet_next` can dispatch a rekey, and `ssh_packet_read_poll2` can change authentication state, initiate rekey, or block while handling malformed packets. Neither is an acceptable substitute for this primitive.

The mailbox's poll overload retains the minimum of the original role deadline, supplied transport deadline and 30-second ceiling, including its monotonic wall-clock budget. Only the owner calls `poll`, and all polling and sink calls occur outside the mailbox monitor; polling must be bounded and enforce its own effect guards. Check the live role and ticket before presenting, and again after polling and before returning an exact one-shot approval. Cancellation, expiration or poll failure retires the ticket. These checks do not atomically order a UI publication or a later effect with cancellation.

This overload accepts the ordinary `Review` type. Constructing that review through `SshHostKeyAuthority.requireVerified` yields an ordinary NEW `Proof` that can permit credentials; it must not be used to manufacture the signed-only candidate. The separate signed-review fixture described below connects its own native input poll, but independently ordered write admission remains future work. Existing direct NEW and CHANGED waits retain their own contracts.

### 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Wrong owner, wrong state, wrong role, expired deadline or revoked generation | No idle result or new authority; preserve the applicable cleanup ownership |
| Observed child bytes, incomplete buffered packet, child/parent EOF or socket error | Fail closed; no packet dispatch, trust write, authentication or shell |
| Parent traffic or outgoing KEX bytes still need a pump | `idle=0`; recheck after the exact owner advances the chain |
| Matching immediate approval or decision during poll | Poll and recheck role, exact ticket, deadline and cancellation before returning |
| Review expired, cancelled, refused or polling fails | Retire the ticket; an unpresented ticket has no dismissal event |

### 5. Good / Base / Bad Cases

Good: an owner samples a signed, drained, otherwise idle child, waits outside JNI and treats the result only as a temporary observation. Base: a parent packet is waiting and the caller pumps before sampling again. Bad: decode arbitrary child packets during review, infer durable trust from `idle`, or use an ordinary NEW proof as a provisional review.

### 6. Tests Required

Exercise encrypted child packets, fragmented input and partial cipher blocks, buffered relay and parent data, observed EOF, cancellation, role/view revocation, generation and both deadlines. Assert no credential, userauth, shell or trust effect and retryable owner cleanup. Separately test mailbox owner/monitor ordering, exact one-shot tickets, immediate approval, refusal, timeout and cancellation during polling. Distinguish local socket shutdown from remote FIN, JNI fixtures from the product entry, and synthetic peers from Android/device evidence.

### 7. Wrong vs Correct

Wrong: `idle` or a UI decision is a host proof or atomic CAS permit. Correct: independently bind the signed candidate and decision, establish effect admission against cancellation and revocation, and report uncertain persistence honestly.

## Scenario: signed NEW fixture review and bounded extension input

### 1. Scope / Trigger

Use for the test-only Java/JNI review of a real provisional child, joined to owner-driven bounded input and parent/relay progression. The JNI readiness query alone checks signed KEX and output drain; the later poll handles observed inbound child data. This is not connected to the product first-trust flow and cannot promise that a future EOF or packet will be observed. The temporary child is retired after review; no result authorizes credentials or a trust write.

### 2. Signatures

```c
int ctos_ssh_native_client_provisional_new_poll_ext(
    struct ctos_ssh_native_client *client, int *idle);
```

```java
SshNativeCallbackAdapter.SignedNewFixtureReview SshNativeSession.signedNewFixtureReview();
int SshNativeSession.pollSignedNewFixture(SshNativeCallbackAdapter.SignedNewFixtureReview review);
SshManagedKnownHostsProvider.FixtureCandidateReservation
    SshHostKeyReviewMailbox.requireSignedNewFixtureApproved(
    SshNativeCallbackAdapter.SignedNewFixtureReview review, SshNativeSession child);
```

The packet helper and native poll have hidden visibility. The JNI readiness, fixture poll and read-only extension-observation entries, together with provisional callback registration, exist only under `CTOS_SSH_NATIVE_TEST_HOOKS`; a missing fixture JNI entry fails closed. The poll observation uses bit 0 for the current idle snapshot and bit 1 for an extension validated by the test hook. Preserve the ordinary OpenSSH packet reader's existing behavior and keep fixture symbols out of production exports.

### 3. Contracts

After native `step()` returns, the exact child owner may issue a one-shot review tied to the real callback, pending candidate identity and ID, role/endpoint, source epoch/revision, first signed KEX cycle, session digest and original hop deadline. Query native readiness without holding a Java session, callback or vault monitor. Recheck the owner, parent, view, role, source and candidate after returning from JNI and around owner-side presentation, polling and approval. This is a sequence of observations, not atomic permission for a later effect.

The signed mailbox entry takes the exact child session, checks its identity before taking responsibility for review cleanup, and constructs its own real poll; callers cannot supply an idle result. Each owner turn pumps the child relay, steps its exact parent, pumps the relay again, and calls the child-specific fixture JNI poll outside the mailbox and Java lifecycle/vault monitors. A test-only hook may advance or observe a synthetic peer but cannot replace the native idle result. After approval, a non-idle observation means another bounded turn is required: wait at most the existing 50 ms slice, and recheck cancellation and the original deadline. The successful fixture wait now returns the non-authorizing candidate reservation described below. Refusal or a poll failure cannot authorize a later effect. The ordinary `Review` overload retains its existing polling contract.

The UI side touches only the mailbox and the ticket's bounded wall-clock deadline; it must not wait for session/vault locks, enter JNI or mutate the owner chain. A source or role may become stale between UI observations, so an apparent or recorded decision is not authority: the owner must reject it on recheck. Wrong-thread or wrong-role attempts must not consume another owner's ticket. The review contains no candidate bytes, ordinary host proof, credential, CAS receipt or trust-write authority.

`SshConnectionMaterial.hex(byte[])` wipes its input. When computing a review binding, pass disposable copies rather than the digest arrays retained for later matching; wipe the retained arrays when their review is retired. Fixture cleanup assertions must not mask an earlier exception or require a digest slot that was never populated.

The owner-thread native poll retains the original guarded role, view, effect generation, signature cycle, deadline and parent/relay checks. Each turn reads no more than 4096 bytes and its step budget, parses at most one packet, and limits buffered encrypted input and total packet size to 16 KiB including authentication overhead. Only an unauthenticated, uncompressed AEAD receive state is accepted. Exactly one valid `EXT_INFO` may be validated and discarded, and only if the actual initial client KEX proposal advertised the exact `ext-info-c` token and OpenSSH has not already processed it. Do not copy extension values to authentication state; do not dispatch messages, start rekey, write a disconnect, authenticate or open a shell. Unsupported modes, invalid messages, duplicate extension or observed EOF fail closed. Partial data and pending parent work require another bounded owner turn and parent/relay pumping.

The pinned OpenSSH normal handshake can process an extension before the child reaches provisional state 2; its normal `kex_input_ext_info` may update KEX extension state. The restricted poll's discard contract begins only after that state, and a subsequent extension is rejected if the normal path already processed one. Tests claiming the joined poll consumed an extension must arrange delivery after signed pending and observe the post-validation hook without another poll causing the result.

Release the short native wire permit before parsing failure cleanup or entering callback locks, and check the generation again after receiving and parsing. `idle` is only a snapshot; it cannot authorize publication, a later vault CAS, or claim the peer has stayed connected.

### 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Wrong owner, callback, role, candidate, source, cycle or deadline; absent fixture JNI | Reject the fixture review and preserve the correct owner's cleanup |
| Wrong child session attempts to claim the review | Reject before taking cleanup responsibility; the exact owner can still use its ticket |
| UI decision races source or role invalidation | Owner recheck rejects it; no trust write or credentials |
| Valid first advertised `EXT_INFO`, supported AEAD and bounded packet | Authenticate, validate and discard one packet; never copy it to auth state |
| Partial input or parent/relay work | Return non-idle and allow the exact owner to pump and retry within the original deadline |
| Approval arrives while input is non-idle | Do not complete the wait until a later real poll observes idle, cancellation occurs, or the original budget expires |
| Unsupported cipher/compression, malformed or repeated input, observed EOF or revoked generation | Fail closed, without dispatch or blocking disconnect; keep cleanup ownership |
| Cleanup throws while a fixture callback is active | End or fence that invocation consistently and preserve unconfirmed cleanup for the same owner |

### 5. Good / Base / Bad Cases

Good: an owner obtains the exact signed fixture review after JNI returns and independently discards one valid advertised extension in bounded native turns. Base: input is fragmented and the poll reports non-idle. Bad: treat the UI decision, signed milestone or idle snapshot as an ordinary NEW proof or a trust CAS permit.

### 6. Tests Required

Exercise the actual Java/JNI child and encrypted vault with exact-candidate/source, replay, deadline, wrong-owner, UI/owner ordering and checked versus unknown cleanup-failure cases. Separately use encrypted native peers for AES-GCM and ChaChaPoly fragmentation, duplicate and truncated packets, EOF, cancellation and generation invalidation. Add direct parser coverage for bad authenticated padding/tag, unknown message, oversized declaration, compression and absent or similar `ext-info-c` negotiation before treating those boundaries as dynamically verified. Regress ordinary connections and verify release symbol visibility. Distinguish host fixtures, QEMU and Android/device evidence.

### 7. Wrong vs Correct

Wrong: combine point-in-time readiness and UI approval to infer atomic trust admission. Correct: preserve their non-authorizing scope, then separately design and verify effect ordering, uncertain persistence and cleanup before rebuilding a new connection.

## Scenario: approved fixture candidate reservation

### 1. Scope / Trigger

Use only after the exact signed NEW fixture's mailbox has accepted the user's decision and its real child poll observed idle. This is a provider-owned candidate reservation for the existing temporary connection, not a product approval, durable trust receipt, detached capability or permission to write, authenticate or release credentials.

### 2. Signatures

```java
SshManagedKnownHostsProvider.FixtureCandidateReservation
    SshHostKeyReviewMailbox.requireSignedNewFixtureApproved(
        SshNativeCallbackAdapter.SignedNewFixtureReview review, SshNativeSession child);
void FixtureCandidateReservation.requireHeldFor(SshNativeSession child) throws VaultException;
void FixtureCandidateReservation.close();
```

The mailbox mints a private `SignedFixtureApproval` only after the exact wait and sink dismissal return successfully. Its private one-shot `Claim` binds the exact review, child, source, evidence, candidate ID and opaque candidate identity; the provider requires that claim and atomically consumes it. Neither the approval nor the reservation exposes candidate bytes or creates an ordinary host proof or proposal.

### 3. Contracts

Under the provider monitor, verify the exact current source and candidate, require the bound one-shot claim, and install the reservation on the candidate under its own monitor. Provider-mediated proposal creation and direct candidate `consume()` both reject while reserved, without consuming or wiping the held candidate. If consumption or close wins first, the attempted reservation fails. Candidate alias `close()`, review close and provider cleanup may revoke the reservation and wipe their own material; a revoked reservation stays cleanup-only and must not silently reopen the candidate for another proposal.

Keep the existing provider → candidate → snapshot ordering. Matching and consuming the claim while holding the provider monitor may read immutable identities and use its atomic flag, but must not acquire the mailbox, callback, session or vault monitor or enter JNI. Do not hold a native effect permit over any of those locks or storage I/O. Wrong owner/session and foreign identities cannot consume a valid claim or use the reservation. The reservation's owner-only `close()` retires it; lifecycle owners may revoke it from another thread, which only fails closed.

After installing the reservation, perform the original role/source/review/deadline checks outside the mailbox monitor. The mailbox then uses a short final lock to reject its own earlier cancellation, expired wall budget or an already-revoked reservation. This is the mailbox's local completion point, not an atomic order against all callback/session/view revocations, hop deadline passage, network events or a later CAS. A mailbox `close()` after that completed decision does not itself revoke the reservation; closing the review, candidate or provider does. Any failed post-check revokes the reservation and leaves existing owner/handoff cleanup in charge.

### 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| No actual approval, wrong owner/session/source/candidate or consumed claim | Reject; do not change another candidate or issue a reservation |
| Ordinary provider proposal or candidate alias consume races reservation | Earlier successful consumption makes reservation fail; after reservation both consumption paths reject |
| Candidate or provider close wins, or mailbox cancellation occurs before its final completion point | Reject or revoke the reservation and preserve the normal cleanup owner |
| Mailbox closes after its completed decision | Do not infer connection cancellation; exact review/candidate/provider cleanup still revokes the reservation |
| Sink dismissal fails or review, source or deadline is stale | Do not deliver an approval/reservation; retire independent review material |
| Reservation is closed or later revoked | Reject its use; retain cleanup-only candidate state until its existing owner clears it |

### 5. Good / Base / Bad Cases

Good: the exact owner approves the displayed signed review, obtains the exact candidate reservation and later closes the temporary connection. Base: the candidate was consumed before reservation and the attempt is rejected. Bad: infer that a claimed review, a reservation or an idle observation is permission to persist trust or hand out a password.

### 6. Tests Required

Exercise a real signed child and encrypted fixture with actual approval, refusal, missing/throwing dismissal, foreign owner/session, replay and absent claim. Use deterministic barriers for provider proposal and candidate-alias consumption on both sides of reservation, close/revocation and cancellation before the mailbox completion point; verify candidate and digest ownership and zero trust/credential effects. Test ordinary provider and known-host regressions. State explicitly which races are only sequential observations and separate host JNI, Android and real-server results.

### 7. Wrong vs Correct

Wrong: treat the provider marker or a native generation sample as an atomic admission for a future vault write. Correct: use this only to preserve the exact fixture candidate while the original owner remains live; the fixture transfer below also remains non-authorizing. Effect ordering, persistence uncertainty and fresh reconnection require separate work.

The separately maintained [fixture candidate transfer contract](ssh-proxyjump-fixture-transfer.md) describes the subsequent non-authorizing ownership move.

## Scenario: retryable child trust cleanup

### 1. Scope / Trigger

Use when the managed child provider, source, receipt or native session is closed, including a failed close and a child already destroyed by the terminal chain. This contract covers these owners' cleanup invalidations; it does not extend the same exception guarantee to every legacy invalidation caller.

### 2. Signatures

```java
int SshNativeCallbackAdapter.beginJumpCleanupInvalidation();
void SshKnownHostsCandidateHandoff.close();
void SshNativeSession.JumpChildOwner.close() throws VaultException;
```

The checked cleanup begin uses production JNI. Its `-1` result means it did not start, `0` means no jump fence was enabled, and `1` means the invalidation began and drained admitted effects. Test-only fault injection and registrations must not be exported in the release library.

### 3. Contracts

Mark the provider/source/receipt and exact child owner closing before a fallible invalidation or native close. A closing child and its closing parent link reject ordinary effect, protocol and shell entry while allowing the same owner thread to retry cleanup. Mark the receipt closing while holding the source-to-receipt lock order; a closing flag alone does not prove earlier native effects have drained.

Each nested invalidation has its own state. Retry an explicitly unstarted begin; an exception during begin or end leaves that particular result unconfirmed, and neither begin nor end may be repeated on that assumption. Keep the exact owner, parent and effect-fence registration while cleanup is unconfirmed. The provider serializes its cleanup, does not hold its monitor across external cancel/close calls, attempts independent candidate/proposal and receipt cleanup even when an earlier cleanup fails, and preserves the original failure and unresolved ownership.

If callback cancellation's legacy invalidation begin or end throws with an unknown result, record that uncertainty, reject owner release, and do not repeat the uncertain operation. Candidate abort rechecks the latch after its handoff cleanup and refuses to return Java success if uncertainty is already recorded at that point. This is a Java completion point, not an atomic order with a later cancellation or the subsequent C-side abort flag update. Retire and wipe independently owned provisional review digests without entering the handoff or provider; do not infer that the underlying candidate has or has not already been cleaned. A fixture invocation must leave `inCallback` even if candidate cleanup throws, while preserving any unconfirmed cancellation state.

An unadopted owner closes its exact callback/handoff before releasing its source; an adopted child's logical callback `closed` state is separate from completed handoff cleanup and native claim release. Do not repeat native close when retrying callback cleanup. A destroyed child's next `close()` must reach its original owner's unfinished cleanup outside the child state lock. Role release requires completed callback cleanup.

### 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Checked begin explicitly reports it never started | Deny new use; preserve the owner and permit the same owner to retry |
| Begin or end throws with native result unknown | Preserve the unconfirmed state, owner and fence; never blindly repeat begin/end |
| Child native close fails before destruction | Deny subsequent parent/child effects; retry the exact close on the owner thread |
| Candidate or request cleanup fails | Try independent remaining sensitive cleanup; keep unresolved ownership and failure |
| Callback handoff cleanup fails after native close | Retain the native claim and role; retry callback cleanup without another native close |
| All required cleanup is confirmed | Release the child source and effect fence, then detach the exact parent |

### 5. Good / Base / Bad Cases

Good: a confirmed pre-begin failure is retried by the original owner and all owners are closed in order. Base: a close is already complete and is idempotent. Bad: set `closed` before a fallible handoff and treat that flag as proof that cleanup or global invalidation finished.

### 6. Tests Required

Use fake native close failures for both direct child and owner close before and after shell promotion; assert no later native entry and exact-owner retry. With real JNI and an encrypted fixture, exercise unadopted and adopted owners, source/receipt/provider begin/end faults, concurrent use, terminal-chain retry after native destruction, and independent sensitive-buffer wiping. Run unknown begin/end faults in separate processes and prove another active invalidator is not ended; test release JNI symbol isolation separately.

### 7. Wrong vs Correct

Wrong: infer from a JNI exception whether a global invalidator count changed. Correct: retain an explicit unconfirmed state and its exact owner; retry only when the native interface explicitly confirms that begin never occurred.

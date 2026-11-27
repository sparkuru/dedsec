# SSH ProxyJump callback-scoped ancestor checks

## 1. Scope / Trigger

An internal native jump child can ask about its actual ancestor proofs from inside its own synchronous native-dispatched callback. Ordinary session JNI reentry during that callback would reacquire a non-recursive session-table mutex. A private, receiver-bound callback JNI entry now exposes the same sequential check; it is not a Java effect guard or product ProxyJump authorization.

## 2. Signatures

```c
int ctos_ssh_callback_client_claim_scoped(ctos_ssh_callback_token, void *,
    ctos_ssh_role_token, const void *owner, ctos_ssh_callback_scope_checker);
int ctos_ssh_callback_client_scope_check(ctos_ssh_callback_token, void *,
    ctos_ssh_callback_scope_checker);
int ctos_ssh_native_client_callback_ancestor_proofs_check(
    ctos_ssh_callback_token, void *environment);
int ctos_ssh_native_client_callback_ancestor_proofs_check_receiver(
    ctos_ssh_callback_token, void *environment, const void *receiver,
    ctos_ssh_callback_receiver_validator);
```

The ordinary callback claim has no scope checker. A successful generic scope check proves only the registered callback scope; only the native client's private checker evaluates its actual ancestor chain.

## 3. Contracts

Bind the scope checker to the original exclusive callback claim. Reject a different checker before it sees the private owner, and do not return an owner pointer or accept arbitrary caller context. The native private checker first compares the opaque owner against published clients in the current owner's thread-local registry, then dereferences the matching client and checks the exact callback, role, environment, PID, UID, thread, live view and upstream relay.

Require both the current callback target scope and the private native dispatch frame for that exact child. Direct calls to callback wrappers, unrelated tokens, nested target callbacks, recursive scope checks, root/direct clients and cleanup callbacks cannot obtain this proof. Host dispatch also requires the actual packet-engine step: the public PINNED `verify_host_key` accessor shares the routine but must not grant host callback scope. The selected-credential accessor is a separate, intentionally callable one-shot native-client operation after actual KEX, service readiness and exact selection proof; do not claim it runs only inside packet processing. Push and restore dispatch frames around actual hostfile/candidate, host evidence, changed review, KEX and selected-credential dispatches, including failure paths. Client publication and TLS registration are metadata-only after callback claim; destroy unregisters on the original owner, and a live child continues to pin its relay and ancestors.

Inspect callback metadata under its lock, release that lock before traversing ancestor callback/role/view proof, then recheck the original callback slot. Never call the child's normal `callback_check_live` from inside its active callback; that is forbidden reentry and poisons the slot. Each ancestor check is sampled in sequence: success is not an atomic snapshot of the entire chain, a promise that it stays valid after return, or ordering of cancellation against a subsequent trust write or secret copy.

The private instance JNI method `nativeCallbackAncestorProofsLive(long)` takes the callback token and compares its actual Java receiver with the registered target's global reference using `IsSameObject`. The receiver check runs only after the original active callback, exclusive checker, thread and environment checks; the registered validator must be the exact private JNI validator supplied by this entry. An absent or substituted validator, or a different receiver, fails closed without invoking a substituted function. JNI returns false on rejection and never enters the session registry. The receiver validator must not reenter the callback adapter, retain the supplied receiver, or perform blocking work while its metadata lock is held. The result does not bind the Java `Invocation` serial and cannot be saved or replayed as authority in a later callback. The product selected-child path uses this sequential proof as one prerequisite, followed by separate native generation and [shared STOP effect admission](ssh-native-stop-admission.md); the proof itself is not that admission.

Existing EOF/close behavior allows already received bytes to drain while retained ancestor proof remains valid. A successful proof in that state does not establish that the forwarding channel is currently writable.

## 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Actual child target callback within its own native dispatch, valid original ancestors | Check real registered child and the upstream proofs in sequence |
| Callback invoked directly despite a valid child claim, wrong checker/token/environment, or generic claim | Reject without treating another owner as a client or leaking its pointer |
| Root/direct client, nested target, recursive scope or callback outside target | Reject; no ancestor capability is produced |
| Wrong PID/UID/thread, expired or revoked role/view, cancelled ancestor | Reject under the existing owner and lifetime contracts |
| Missing or substituted receiver validator, wrong Java receiver, token or thread, nested or completed callback | Receiver-bound JNI check returns false without granting a scoped proof |
| Checker fails or callback cleanup must retry | Restore scope/dispatch frames; retain the correct cleanup owner |
| Parent EOF/closed with valid retained proof | Permit existing accepted-byte drain; never infer present channel writability |
| Cancellation after a successful check | Prior check supplies no effect atomicity; revalidation and future shared ordering remain necessary |

## 5. Good / Base / Bad Cases

Good: the exact child receives a callback from its own native packet engine, verifies its registered scope and checks the original parent and deeper ancestors. Base: a callback arrives after an ordinary EOF and the retained proof supports draining without granting a new write. Bad: call a callback wrapper manually, substitute a role/token, or treat one successful sequential check as a durable Java credential/trust grant.

## 6. Tests Required

Use real encrypted multi-layer native peers and actual child callback targets, including NEW candidate/evidence/KEX, CHANGED candidate/review/KEX and selected credentials, plus a deeper ancestor. Test caller-forged checker/owner, real-child wrapper invoked outside native dispatch, unrelated callback, original PID/UID/thread, expiry, role close, view revoke, callback cancellation, nested callbacks, cleanup retry and frame restoration after failure. The receiver-bound route also needs an actual JVM/JNI callback test with exact and wrong receivers, token/thread mismatch, nested scope and cancellation; identify any replacement ancestor checker as a fixture. Preserve normal EOF drain and state clearly when any EOF transition is driven by C test code inside a callback rather than real JNI reentry. Run host callback checks and ARM64/QEMU; separately identify compile-only tasks, synthetic peers, host JNI/Android and real-server evidence.

The host-only `android/ssh/host-jni-multihop-test.sh` combines the actual native child and production JNI callback/session code with an isolated JVM receiver and synthetic encrypted peers. It registers the child in the real JNI session table using a test fixture, then drives the actual session step and password-auth entry with the session mutex held. Check KNOWN host evidence, KEX and selected password callbacks for exact receiver, token, thread, live ancestor and non-reentrant lock behavior. Cover a direct parent and a deeper ancestor canceled synchronously from host-evidence and selected-credential callbacks; verify the returned Java password array is wiped on success and cancellation. These tests use a test-owned Java receiver and trust response, C-managed ancestors and host OpenSSL, not a product Java session, vault/CAS integration, concurrent revocation or Android runtime. A successful native/JNI recheck remains a sequential observation, not an effect permit.

## 7. Wrong vs Correct

Wrong: call the ordinary session registry from synchronous Java, or claim a point-in-time test makes an irreversible callback effect atomic. Correct: use the exact native-dispatched owner scope for sequential ancestor checks, retain all original owners, and build a separate revocation/commit ordering before exposing any product jump path.

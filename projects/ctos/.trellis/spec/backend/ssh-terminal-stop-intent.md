# SSH terminal stop intent

## 1. Scope / Trigger

A Stop, screen lock, connector cancellation or terminal failure can encounter a JNI effect-invalidation exception before the native invalidation is confirmed. This contract orders local terminal I/O, connection-result publication and unattached owner cleanup against the stop request. It does not establish safe cleanup inside a partially constructed native session, every per-hop owner or a shared view.

## 2. Signatures

`SshSessionOwner.Cancellation.stopRequested()` and `getAsBoolean()` report the monotonic local stop request; neither proves native invalidation or resource release. `admitOutput()`, `admitTerminalIo()` and `admitFactoryStart()` order local admissions against that request. `admitUnattachedCleanup()` separately orders outer owner cleanup against the same request and the confirmed end of its native invalidation. `SshTerminalSession.requestCancel()` signals the exact terminal port; `stop()` is the owner's cleanup turn and does not repeat an already requested cancellation.

## 3. Contracts

Publish the stop request atomically before attempting native invalidation. A new local terminal begin, status, write, resize, step, read, output event or replay snapshot must win an admission against that request. An action admitted first may physically finish later; do not hold the admission primitive across JNI, port callbacks, scheduling or listeners. Queued input not yet delivered is wiped by the original terminal owner.

A native begin or end exception leaves its outcome unknown. Retain that fact independently of the cancellation bit, do not repeat the uncertain begin/end, and deny new local admissions. A terminal close that observes its own shared invalidation or port cancellation as unconfirmed must retain the port and owner. A close already admitted by the owner before the stop request may physically finish afterwards; this increment does not drain or reorder that earlier close. The native port attempts initial cancellation once per terminal. A chain result proven to contain only exact-session checked pre-begin refusals and optionally callback-only failures permits up to three failed retry rounds on the original owner after the shared invalidation completes; each retry touches only the sessions whose begin explicitly did not occur. If another cancellation or close subsequently runs for one of those selected sessions, wait at 100 ms intervals without consuming that budget. An initial cancellation that instead collides with an already admitted close retains the prior pending/unknown behavior; this retry path does not resolve it. After exhaustion retain the owner and report cleanup pending; a later `requestCancel()` may start a new bounded round. The owner's queued `stop()` for an already cancelled terminal must not reset the budget, because it is part of the same external Stop. Any thrown or mixed unknown result remains unconfirmed and is never retried. The actual native port may continue with its independently retryable owner-thread close when every remaining cancellation failure is solely `SSH_CALLBACK_NATIVE` from callback cancellation, with no nested cause or other suppressed failure; that outcome is cleanup-only, not confirmed cancellation. A successful or cleanup-only asynchronous cancellation schedules an owner-thread close after the cancellation call completes.

An unattached cleanup admitted before Stop may finish; a later attempt must wait for confirmed invalidation and must retain the original session and factory on an unknown result. A pending cleanup state is published at most once per owner and is not replaced by a later failed/screen-lock state while cleanup is pending. A queued pending event that observes release is discarded; an event already admitted may finish afterwards. If shared cancellation throws, schedule the terminal's owner-thread cleanup check without repeating unknown JNI. Snapshot screen-lock/service-start result ordering before that call and retain a possible trust-write result until the factory returns.

The shared state records the first ordinary Stop, screen lock or service rejection together with Stop itself. A factory that never obtained admission can complete the connection result without waiting for unconfirmed cleanup. When synchronous shell confirmation returns a fixed credential-save failure, retain it separately from cleanup-pending UI; preserve an explicit `VAULT_COMMIT_UNCERTAIN` even without tracked automatic saving. A native invalidation still draining is not an unknown failure: wait or schedule an owner-thread recheck. If its outcome becomes unconfirmed and cleanup cannot safely continue, report the retained error or applicable stop reason while preserving the owner. A normally retryable close retains its existing result until that close finishes. Preserve the existing screen/service deferred-result contract, including its tracked-save ordering.

Before completing a running connection future and before the connector hands off a live shell, each path takes a fresh local admission against Stop. The connector also orders its own unstarted create against Stop and retirement; an already admitted outer owner remains separately responsible for cleanup. See [connector retirement](ssh-connector-unstarted-retirement.md). An admission that wins may physically finish after Stop; these checks do not drain in-flight operations. Trust confirmation also rejects a preceding stop request; see the separate [trust acknowledgement contract](ssh-trust-commit-ack.md). The session's internal creation failure cleanup, independently invoked callback/lease cancellation, native effects already in flight and Android service lifecycle retain separate unresolved obligations. Vault invalidation failure now wipes its local key and retains unconfirmed ownership, but can still leave an independent native lease active; see [vault revocation](ssh-vault-revocation-ownership.md). The bridge catches its result-path cancellation and lock exceptions before a single reply attempt, whose delivery is not guaranteed; see [connection result delivery](ssh-connection-result-delivery.md). Do not use local admissions as trust-write or first-use authorization.

## 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Stop wins before a terminal I/O or output admission | Deny that admission; wipe queued terminal input |
| I/O admission wins before Stop | That one call may finish; the next admission must be denied |
| Native begin or end throws before terminal close is admitted | Record unknown once; repeated cancel does not repeat JNI; terminal port and owner stay retained |
| Stop wins before unattached cleanup or connection/handoff admission | Retain an unconfirmed original owner; reject later connection success |
| Earlier unattached close wins before Stop | That close may finish; if it fails, its next attempt must respect the unconfirmed Stop |
| A cleanup-pending publication precedes screen lock or a late failure | Keep one pending event until a confirmed release; a queued event that observes release is discarded |
| Synchronous credential-save confirmation has exited with an error | Preserve its fixed error, including uncertain commit; finish only at confirmed cleanup or an unconfirmed failure boundary |
| Native port reports only callback-native cancellation failures | Permit the separate retryable native close; do not call cancellation confirmed |
| Native port reports only checked pre-begin refusals, optionally with callback-only failures | Retry the exact refused sessions on the owner; if a selected session has a later operation in flight, wait without spending budget |
| Three retry rounds again report checked refusal | Keep the owner; a later external Stop can request a new bounded round |
| Owner's queued `stop()` follows the same external Stop | Continue cleanup without resetting that Stop's retry budget |
| Port cancellation has another or mixed failure | Do not release the terminal owner or repeat an uncertain cancellation |
| Stop and port cancellation succeed | Close on the original owner and release only after normal close/acknowledgement |
| No cancellation and normal remote close | Preserve the existing drain, close and release behavior |

## 5. Good / Base / Bad Cases

Good: a stop wins between an admitted write and the next step; the write may complete and the step is denied. Base: a normal stop cancels the port, then the owner closes and releases it. Bad: an exception in native begin leaves a queued write runnable or causes an unconfirmed port to be closed.

## 6. Tests Required

Use the actual terminal controller and shared cancellation state with a fake port and controlled scheduling. Cover queued input, resize, output/snapshot admission, start, running/future handoff on both sides of Stop, a write admitted before stop, cleanup, ordered screen-lock events and the no-stop path. Use the real host test JNI in isolated JVMs to inject legacy begin failure and begin/end uncertainty; assert no repeated uncertain JNI, no premature factory close or owner release, and no new fake-port effects. Exercise a returned real native session separately from the fake factory. These checks are not Android ART or remote-server evidence.

For checked pre-begin retry, cover the exact root/child session, three failed owner retry rounds, a subsequent external request, owner `stop()` following the same request, a separately in-flight cancellation, and multi-hop mixtures with unknown and callback-only outcomes. Assert the original owner and descriptors remain until confirmed reverse cleanup; a fault-injected `-1` or callback token is not evidence that production JNI generated it. The [local results](../../../design/verification.md#20261127-ssh-terminal-session-cancel-retry) distinguish the synthetic host fixture from Android Service and device execution.

## 7. Wrong vs Correct

Wrong: set a local cancelled bit after the JNI call and treat an exception or a subsequent no-op as confirmed native cleanup. Correct: order local admission against a prior monotonic stop intent and retain the exact terminal owner whenever cancellation is unconfirmed.

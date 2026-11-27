# SSH native session cancellation ownership

## 1. Scope / Trigger

Each direct or jump SSH session owns its native client and callback. A cancellation JNI error, concurrent callback cancellation or already admitted close must not be mistaken for confirmed invalidation or permission to destroy that client. This contract concerns an existing Java session; it does not make the connector's partially constructed session or trust write atomic with its outer cancellation.

## 2. Signatures

`SshNativeSession.cancel()` is cross-thread. `close()`, internal child-owner close, and `TerminalChain.retryCancellationForTerminal()` remain on the original session owner. Checked native effect begin returns `-1` only when no invalidation began, `0` when no fence exists, and `1` when begin completed; an exception is unknown. The current production JNI implementation returns `1`; the explicit `-1` is exercised by test hooks and is not an observed production failure.

## 3. Contracts

Under the session state monitor, mark cancellation requested before starting native invalidation and reserve the cancellation attempt. Deny normal session I/O immediately through the existing cancellation checks. Run the native begin/end and callback cancellation without holding that monitor; another cancel or close must not enter the same unfinished attempt. A checked `-1` is retryable by the same session and blocks a fresh close until resolved. Its private typed failure identifies the exact session; the terminal chain may retry only the sessions captured by that checked outcome, after atomically reserving each attempt. If an independent cancellation or already admitted close is still in progress, wait without consuming a failed-retry budget; recheck the session's result before doing anything else. A thrown begin/end, invalid status, or unconfirmed callback cleanup is sticky: do not repeat the uncertain operation, close the native client, release its callback/view/role, or report destruction.

When cancellation remains in progress, explicitly unstarted, unknown, or is cleanup-only after callback native failure, `step()` must return the fixed cancellation error before entering JNI: the native cancelled-step path can itself abort a handed-off candidate. After confirmed cancellation, the existing cancelled-step behavior remains available.

An isolated `SSH_CALLBACK_NATIVE` from the actual callback cancellation, without a cause or other suppressed errors, allows the independent owner-thread close path. It does not establish successful callback cancellation. A confirmed cancellation is idempotent.

Close must reserve its attempt under the same monitor before calling native or callback cleanup outside the monitor. If a cancellation arrives after that reservation, mark local cancellation and report pending; the already admitted close can finish. If it fails while the native client remains open, require cancellation to be resolved before a new close attempt. Normal close failures without such a race remain retryable on the same owner. Native close, callback/role release and view retirement remain ordered and independently tracked as before.

This tracks callback cancellation invoked through this session; a separate direct callback cancellation is not atomically joined to the session close admission. The shared stop request is now locally visible before native begin, but that does not prove this session's own invalidation or release. This contract does not automatically revoke the shared public lease, drain an already admitted native effect, prove Android lifecycle, or authorize trust persistence. The chain owner must still preserve its own child/relay and parent ownership.

## 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Cancel begins before session close | Mark local cancel; deny later session I/O and close until its attempt resolves |
| Checked begin returns `-1` | Retain the client and allow same-session cancellation retry |
| Terminal chain receives checked refusals for multiple sessions | Retry only those exact sessions on their owner; any mixed unknown failure prevents terminal retry and retains the chain |
| A separate cancellation is still running for a session already selected by checked `-1` | Wait for its result without consuming a failed-retry round or releasing the owner |
| Initial terminal cancellation instead encounters an already admitted close | Preserve the existing pending/unknown cleanup behavior; this retry contract does not settle that race |
| Begin/end or callback cleanup is unknown | Retain the exact client, callback, role and view; never repeat the uncertain call |
| Only callback native cancellation reports failure | Permit separately retryable native close; do not claim cancellation succeeded |
| Close wins before cancel | Report pending cancellation; permit that admitted close to finish |
| Earlier close fails with native client still open | Require the pending cancellation before retrying close |
| No cancellation and ordinary close failure | Preserve original same-owner cleanup retry |

## 5. Good / Base / Bad Cases

Good: an explicitly unstarted invalidation is retried by the same session, then the client closes. Base: callback-native cancellation failure keeps the ordinary close path. Bad: retry a native begin whose prior result is unknown and then free its client as if cancellation were confirmed.

## 6. Tests Required

Use controlled JVM barriers for callback cancel versus close and close versus cancel, including a failed earlier close and existing reverse cleanup. Inject checked pre-begin, unknown begin before/after, end before/after and callback invalidation faults through real host JNI with an actual session in isolated JVMs; assert local cancellation, no uncertain retry, exact descriptor/owner retention, and successful retry only for explicitly unstarted begin. Keep Android and real-server claims separate.

For terminal-mediated retry, cover the original owner, the three-round budget and later external Stop, a previously selected session whose independent cancellation is still draining, and mixed per-hop outcomes. A typed checked refusal must never turn an exception or another hop's unknown outcome into retry authority. The [local results](../../../design/verification.md#20261127-ssh-terminal-session-cancel-retry) describe which failures are test-injected and do not claim device execution.

## 7. Wrong vs Correct

Wrong: a thrown JNI invalidation means no native mutation, or an outer terminal flag proves each hop is safe to destroy. Correct: track each session's own attempt and close admission, distinguish callback-only failures from unknown native state, and retain its original owner until cleanup is safe.

# SSH terminal output cancellation ordering

## 1. Scope / Trigger

An SSH owner can cancel while a previously read terminal chunk is queued for the main dispatcher, or while a new subscriber asks for its replay snapshot. A last liveness check followed by a separate state update leaves a cancellation window. This contract orders current-owner cancellation against Java output admission; it does not add ancestor-chain or independently revoked role/view authority.

**Known unresolved gap (2026-11-25):** if legacy native effect invalidation throws before begin, `Cancellation.getAsBoolean()` can still remain false. A separate [local stop-intent contract](ssh-terminal-stop-intent.md) denies new terminal I/O and output admission in that case and retains an unconfirmed terminal owner. Individual [sessions](ssh-session-cancellation-ownership.md) and [shared views](ssh-shared-view-cancellation.md) now track their own operations, but this does not establish safe pre-auth creation cleanup, trust-write cancellation, all independently invoked invalidations or Android lifecycle behavior; see the [original failure analysis](../../tasks/10-08-ssh-terminal/research/ssh-cancel-invalidation-failure-20261125.md).

## 2. Signatures

```java
SshSessionOwner.Cancellation.admitOutput() -> boolean
SshTerminalState.begin(int generation, String mode, Cancellation cancellation)
SshTerminalState.data(int generation, byte[] bytes)
SshTerminalState.snapshot() -> Map<String, Object>
SshTerminalSession.requestCancel()
```

The real service owner binds its exact shared `Cancellation` once when it begins a generation. Its connector and terminal controller use that same object. The two-argument `begin` remains available for standalone state tests that do not have an SSH owner.

## 3. Contracts

In the state monitor, after checking generation and running state, order each new data admission against owner cancellation with the shared atomic state before modifying the replay buffer or publishing the event. A snapshot containing bytes uses the same ordered admission before copying them. A cancelled generation's new subscriber can receive its real current state and generation with an empty byte array while the queued failure/closed state has not run; do not invent a completed close. The ordinary failed/closed transition still clears and wipes retained replay, and the terminal owner stays alive until an in-flight dispatch acknowledges and wipes its buffer.

Cancellation wins before admission: no new data event, replay addition, or nonempty replay snapshot is published. Admission wins first: that publication is logically before cancellation; its listener may physically begin or finish after cancellation returns. `requestCancel`, owner Stop/screen lock and the connector's cancellation use the same atomic state. A stale generation never admits data into a replacement generation.

The existing state monitor still calls listeners synchronously. Do not add an owner or cancellation lock around listener invocation; service and reentrant callbacks have their own lock order. This contract does not guarantee that already admitted deliveries physically finish before cancellation returns, or that role, public-view, native session or ancestor failure is atomic with Java admission.

## 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Owner cancel wins after the early liveness check but before state admission | Drop the chunk; acknowledge and wipe its original in-flight buffer |
| Cancel wins before a subscription replay snapshot | Keep the actual state/generation, but return no replay bytes |
| Output or snapshot admission wins before cancel | Preserve its logical ordering; do not claim its listener has physically finished |
| Old generation or replaced owner | Do not append to or authorize the new generation's output |
| Terminal controller fails or stops internally | Cancel the same owner-bound admission state before further publication |
| Listener reenters state or requests cancel | Preserve existing state-monitor behavior without holding a new owner/fence lock around it |
| View, role or ancestor becomes invalid independently of owner cancellation | Existing checks still apply; this CAS alone is not a cross-hop proof |

## 5. Good / Base / Bad Cases

Good: a queued chunk passes the early check, then owner cancellation wins the state admission and the chunk is dropped and wiped. Base: a snapshot admitted before cancellation can complete and is treated as earlier. Bad: a subscriber receives old replay bytes after cancellation won merely because the failed state is still queued.

## 6. Tests Required

Drive the real owner, queued dispatcher, state and terminal controller with a clearly identified fake native session. Use barriers to force cancellation after the early check but before the state lock, and the opposite order while a listener is in flight; assert data event, replay bytes, snapshot state/generation, acknowledgment and original-buffer wipe. Cover old/new generations, existing replay before cancellation, listener reentry, internal terminal failure, original-owner cleanup and prior trust/cancellation regression tests. JVM fake bindings are not Android/JNI or real-server execution.

## 7. Wrong vs Correct

Wrong: treat an early `publicationLive()` check or a posted failure event as an atomic final-publication fence. Correct: bind the current generation to its real owner cancellation, order data and snapshot admission at the state boundary, and state precisely which already admitted deliveries may finish later.

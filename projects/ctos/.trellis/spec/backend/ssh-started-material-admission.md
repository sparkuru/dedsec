# SSH started-connector material admission

## 1. Scope / Trigger

After connector creation has entered, STOP can race the installation of transient credentials, password or keyboard responses, and selected credential-saving contexts. The installer and the shared STOP need one local ordering point even when native invalidation is still draining or has an unknown outcome.

## 2. Signatures

`SshPasswordConnector` installs automatic-save copies, transient passwords, transient keys, config-password/key-phrase mutation contexts and the automatic-save bundle context under its own monitor. A deferred `SshTransientKey.deferred(...)` and production `SshPasswordMailbox` / `SshKeyboardMailbox` receive the originating `SshSessionOwner.Cancellation`. Mailbox ticket installation, response installation and response transfer to the owner each admit under the mailbox monitor. The nonblocking admission uses `Cancellation.admitTerminalIo()` and does not call JNI.

## 3. Contracts

Construct or clone each candidate before taking the installation monitor. Check local cancellation and perform the shared STOP admission inside the same monitor immediately before transferring the candidate to the destination field. If STOP wins, reject and erase or close only that candidate on the installing thread, outside the monitor. If admission wins, the original connector/key owns the candidate and a previously admitted installation may physically finish after STOP.

Do not call native invalidation, vault/session methods, external guards or callbacks while holding the installation monitor. Do not infer that an already admitted secret or native resource has been erased, closed or released when a later STOP or JNI error occurs. The private-key deferred phrase must use the connector's original shared cancellation rather than a new independent cancellation.

The prompt owner takes an accepted answer out of the ticket, rechecks the original live and time guards, and admits its transfer separately. A denied transfer wipes that owner-local answer; a rejected response candidate is wiped by the responding thread. STOP prevents new pending results but does not retract an already admitted presentation or handoff. Standalone mailbox constructors create independent cancellation state for existing isolated callers; the product connector must pass its exact shared cancellation.

A newly created mutation or automatic-save context is either admitted under the connector monitor or cancelled by its installing thread outside that monitor. When cancellation reaches context cleanup, it takes a stable snapshot of installed contexts under the same monitor. Automatic-save bytes are taken by their owner only after another shared admission; STOP winning that handoff wipes the still-queued bytes, and an admitted owner wipes its detached bytes in `finally`. The bundle context is cleared only after its exact cancellation. If any connector cancellation step throws, attempt to cancel prompt mailboxes and erase still-queued connector-owned arrays; retain the original failure and keep any in-flight native or operation owners. If that local cleanup also throws, attach it to the original failure rather than claiming it completed. Never retry an unknown native invalidation.

This covers these specified local sites, not earlier temporary clones, every mutation/operation path, native credential copying, packet construction, socket output or complete owner cleanup. Host-review decision admission is described in [host-trust management](ssh-host-trust-management.md#scenario-order-local-host-review-against-stop). The [native permit counterexample](../../tasks/10-08-ssh-terminal/research/ssh-stop-native-permit-window-20261127.md) records why a native generation check alone does not establish the Java STOP ordering.

## 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| STOP wins before a specified installation | Reject; installing thread wipes or closes its candidate |
| Installation admission wins first | Preserve the transferred owner; the physical assignment may complete after STOP |
| Native invalidation is pending or unknown | Installation does not wait for that native drain and does not claim native cleanup |
| Deferred phrase races key close | The key monitor orders close and installation; rejected copies are wiped |
| Automatic-save field is already occupied | Preserve the existing owner, reject and wipe the new candidate |
| STOP wins a prompt response or owner handoff | Deny the transfer; the thread still holding the candidate wipes it |
| STOP wins mutation/context installation | Cancel the uninstalled candidate outside the connector monitor |
| STOP wins before automatic-save bytes are detached | Erase the queued connector-owned bytes; cancel the exact bundle context |
| A cancellation step throws | Preserve that failure and unconfirmed native ownership while attempting to cancel mailboxes and wipe only still-queued connector arrays |

## 5. Good / Base / Bad Cases

Good: STOP wins while native invalidation is pending and the installer independently wipes its rejected clone. Base: an admitted candidate transfers to its exact connector/key owner. Bad: a final pre-lock liveness check is treated as an atomic installation or a later cancellation thread wipes a copy already in use by another owner.

## 6. Tests Required

Use actual connector, mailbox and key methods with controlled STOP, install, handoff, close and guard barriers. Inspect exact candidate arrays and prior owners; cover normal, pending and unknown native invalidation and verify no external guard or JNI call under the relevant monitor. Host-only JNI scenarios can exercise the actual shared cancellation and installation helper without claiming a real SSH session or Android runtime. End-to-end native credential effects require separate evidence. The user authorized local verification on 2026-11-27; report only the checks actually run.

## 7. Wrong vs Correct

Wrong: clone after a live check and assign later without ordering the assignment against shared STOP. Correct: perform the nonblocking shared admission in the installation monitor and leave cleanup with the exact current owner.

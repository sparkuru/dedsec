# Bug Analysis: HFTP stop, restart and log state

2026-09-27. This record distinguishes proven defects from the unresolved
provenance of the user's immediate-stop error. Final device regression is
recorded separately; prior single-cycle success remains historical evidence.

## 1. Root Cause Category

- **B / E — cross-layer contract and implicit assumption:** Bridge returned
  from asynchronous stop before Service had fenced ready/error/EOF callbacks.
  Failed state also implied availability even while owned resources remained.
  Explicit stop intent and resource cleanup ownership need separate guards.
  Reviewer also found a cold/unowned STOP path treating null token equality
  as ownership, which could leave stopping without a current instance;
  explicit non-null ownership guarding and a regression were added.
- **D / E — test gap and TCP assumption:** no live helper was assumed to imply
  a reusable port. Real server-side TIME_WAIT survived completed transfers and
  helper STOP. The old binary reproduced errno98; checked SO_REUSEADDR fixed
  twenty corrected-version restarts, without sharing an active listener.
- **D / B — widget persistence contract:** ExpansionTile bool and unkeyed
  nested log scroll double shared PageStorage identity. Both directions of
  type corruption were reproduced before editing: double->bool on rebuild,
  bool->double after collapsed clear/refill. The release gray area matches
  this defect, though the device's first stack had already been evicted.
- **D / E — CPU lifetime:** a visible foreground service is not an owned
  CPU wake lock. Queued-PING processing fixes a proven scheduling race, but
  the corrected168cedec phone still loses the heartbeat after screen-off.
  Do not treat host SIGSTOP as Android suspend simulation. App partial CPU
  ownership scoped to explicit Root transport is being implemented and must
  pass actual screen-off transfer and stop/failure release tests.

## 2. Why previous validation missed it

First new device run found two further issues after static checks passed:
rejected fresh FGS stopped before promotion and Android terminated the process;
the occupied-port fixture could listen on ::1 while Python used 127.0.0.1 and
therefore failed to force the intended error. Preserve foreground triggering,
fulfill the platform deadline, and pin test address family. Device execution
is required evidence for these platform contracts; compilation alone did not
establish them. See restart-device-check.md for the failed run, retained log
and subsequent validation.

Previous native/device checks proved one transfer/stop cycle and precise
cleanup, but did not immediately restart the same endpoint after graceful TCP
close. UI tests rendered and updated logs without retaining the same bucket
across scrolling, collapsed replacement and page reconstruction. Callback
ownership tests rejected another session but lacked an explicit stop-intent
boundary before onDestroy. These were coverage gaps, not evidence that the
previous single-cycle results were false.

Current retained logs show an actual startup failure at 18:46:52. They do not
establish that the user's Stop action triggered a new Start. The restart fix
is independently necessary; it cannot be the only explanation of an
immediate-stop report. No system VPN/socket mutation is used to force a pass.

## 3. Prevention Mechanisms

| Priority | Mechanism | Specific action | Status |
| --- | --- | --- | --- |
| P0 | Ownership boundary | Fence explicit stop before asynchronous destruction; retain occupied instance through cleanup | Implementation/review |
| P0 | Absent ownership | Reject null session state publication on cold/unowned STOP | Added; device check pending |
| P0 | TCP regression | Real transfers, graceful EOF, STOP/reap, 20 same-port restarts; reject active second owner | Passed locally |
| P0 | Widget regression | Same bucket, log scroll, reconstruction, collapsed clear/refill; independent PageStorage paths | Pre-edit failures reproduced |
| P0 | Current device proof | Repeat isolated Root LAN transfer/Stop/Start and log interactions with unchanged VPN | Pending final build |
| P1 | Executable spec | Backend stop/TCP and frontend PageStorage requirements | Updated |
| P0 | Power boundary | Root adapter App partial lock, bounded5h, no renewal, every constructor/close failure releases | Pending device verification |
| P0 | Diagnostic proof | Closed events expose fixed reason/errno; preserve failed download separately from later heartbeat failure | Implemented/reviewed |

## 4. Systematic Expansion

- **Similar issues:** startup timeout, Wi-Fi loss, relay EOF and Python exit
  callbacks all require the same live-and-not-stopping guard. Failed resource
  cleanup must also prevent start/config changes; do not special-case only the
  Stop button.
- **Design improvement:** distinct operation intent, actual resource ownership
  and final state prevent old callbacks or closing instances from publishing
  readiness. Separate PageStorage identity expresses different value types.
- **Process improvement:** lifecycle acceptance covers transitions and reuse,
  not just snapshots or process disappearance. Client CLOSE_WAIT is not
  listener/TIME_WAIT evidence; inspect the exact local endpoint.

## 5. Knowledge Capture

- [x] PRD R21–R23 and implementation/design notes appended.
- [x] Backend Python-workbench spec updated with stop/TCP contracts.
- [x] Frontend workbench spec updated with PageStorage and stopping contracts.
- [x] Final review/build/device results synchronized to design/verification.

2026-09-28 outcome: final10device methods9.077s and three awake LAN cycles
including31MiB passed; repeated App Stop/owned cleanup/same-port restart and
log interactions passed. App CPU ownership/cleanup does not prove effective
idle wakefulness: screen-off still refused. User explicitly chose to keep
the stop fix and defer Root power expansion; preserve this limitation, do
not silently implement the candidate or label screen-off accepted.

ctOS has no `src/templates/markdown/spec/` mirror. No adjacent repository or
Trellis template is modified. Product review and commit remain pending under
the project's existing workflow; this feedback does not trigger an early
spec-only commit.

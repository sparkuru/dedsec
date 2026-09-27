# HFTP stop/restart review

2026-09-27. Trellis checker reviewed task R21–R23, PRD/design/implementation,
frontend/backend contracts and project validation policy. Native/UI owners
froze source, backend then froze source; all hako checks ran sequentially.
No device, current-APK replacement, install, commit or archive by this checker.

## Findings fixed during review

- `HftpService.beginStop`: an unowned Service has `session == null`, while a
  fresh log buffer has `owner == null`. Equality alone accepted that as an
  owner. An absent-service notification STOP followed by onDestroy could
  publish stopping without any current instance able to complete it. Require
  a non-null session before publishing stop or appending diagnostics.
- `PortableToolsTest`: detached replacement coverage originally exercised
  normal stop only. Expanded the existing method to also inject a controlled
  failure on the main thread, delay owned reaping, reject a new Android Service,
  preserve the old failed reason, release cleanup and explicitly clear the
  failure through stop. This is controlled failure-boundary coverage; the
  separate EOF test uses a real App Python process exit.
- `PortableToolsTest.unownedStopCannotPublishStateOrAppendDiagnostics`:
  temporarily models the initial null log owner, invokes the real stop fence
  on an unowned instance, checks exact unchanged state/log snapshot and restores
  the previous owner. It does not clear existing diagnostics or start a process.
- `hftp_log_test.dart`: strengthened the retained-bucket scroll/reconstruction
  regression to collapse, reconstruct again with the same bucket, assert the
  collapse remains visible, then expand successfully. A first checker edit
  attempted to reference private ExpansionTileState and failed test compilation;
  replaced it with public widget visibility assertions before final checks.

## Reviewed behavior

- Bridge stop fences callbacks synchronously before Android destruction.
  Notification stop uses the same fence. `live` requires owned session,
  process identity and neither stopping, failure-requested nor destroyed.
- Current Service ownership survives asynchronous cleanup, including failed
  state. Bridge claims and direct duplicate Service starts cannot replace it.
  A rejected unowned Service's destruction cannot publish state or release
  another instance's ownership. `closing` stays true until owned cleanup ends.
- Cleanup detaches resources under the instance monitor, then closes them on
  its own thread. The class monitor does not enter an instance monitor; adapter
  failure callbacks are posted and adapter log callbacks execute after the
  Service resource lock is released during closing. No lock-order cycle found.
- Normal stop clears reason and keeps logs; spontaneous failure keeps reason
  through cleanup, until explicit stop or a new session. UI stopping/closing
  disables start, repeated stop and configuration, while diagnostic actions
  remain available. Poll/clear revisions preserve newer state.
- Native checked SO_REUSEADDR reports `listener_reuse`, which the Java fixed
  reason allowlist accepts. No SO_REUSEPORT, active-listener takeover or global
  network changes. Independent tile/outer-scroll/selectable-text/error-text
  PageStorage paths protect bool and double values.
- Main synchronized backend/frontend specs. No SDK, Python or template changes
  required for this iteration; unchanged Python suites were not rerun.

## Verification before device-feedback iteration

- `./hako dart format test/hftp_log_test.dart`: passed, zero formatting edits.
- `./hako flutter analyze`: passed, no issues (1.6s final run).
- `./hako flutter test --reporter expanded`: **73/73 passed**, 6s final run.
- `./hako bash -lc 'cd android && ./gradlew :app:lintRelease
  :app:assembleReleaseAndroidTest --console=plain'`: **BUILD SUCCESSFUL, 13s**
  after checker edits. Lint XML has **0 errors / 5 existing warnings**. Release
  Java and instrumentation compile passed. An earlier pre-review build also
  passed in 28s; it does not replace the final rebuild.
- Then-current test APK `build/app/outputs/apk/androidTest/release/app-release-androidTest.apk`
  SHA256: `50f9fd35ae171eea5e8667382881e935f6919fd28adb83deb0f68aa4a089a4e9`.
- `git diff --check`: passed after edits.
- Native evidence verified against actual temporary logs: **5 focused tests**
  (0.229s), including twenty actual transfer/STOP/restart cycles and active
  listener/legacy TIME_WAIT/failing-setsockopt boundaries; **15 existing tests**
  (29.165s). Source SHA256 matches native record:
  `0153f3ad5b0f873b606a34a50e9032de7cd6f9a5df8499fa9699f8306bc2a30b`.
  These were owner-run local fixtures, not new checker device evidence.

## Device handoff

Compiled instrumentation methods for main's authorized TARGET-PHONE check:

1. `hftpRemainsVisibleInBackgroundAndStopsItsOwnedListener`
2. `stopRequestFencesRealProcessExitBeforeAndroidDestroy`
3. `replacementDuringDetachedCleanupCannotOwnTheOldSession` (both paths)
4. `explicitStopClearsAnAlreadyFailedStartupWithoutClearingLogs`
5. `unownedStopCannotPublishStateOrAppendDiagnostics`
6. `hftpLogsAreBoundedSanitizedAndOwnedByOneSession`
7. `nativeRootRelayRejectsAppUidBeforeNetworkEffects`

Lifecycle tests refuse an active user service, use ephemeral loopback App-mode
listeners and direct start intents that do not persist the user's configuration.
Owned private test files are uniquely named and precisely removed. Controlled
reflection is confined to instrumentation. Actual device pass remains pending,
as do new current package and repeated isolated Root LAN transfers under the
user's unchanged VPN conditions. Inspect instrumentation result text: shell
exit zero alone does not establish JUnit success.

No remaining blocking source/spec review findings. The retained startup
`listener_bind` evidence still does not trace the user's immediate Stop event;
do not equate the independent TIME_WAIT restart defect with that report.

## Device-feedback iteration: rejected foreground Service

Main's actual seven-method TARGET-PHONE run is **not a pass**. The recorded
`${EVIDENCE_DIR}/ctos-hftp-restart-instrumentation-20260927.log` first shows
`explicitStopClearsAnAlreadyFailedStartupWithoutClearingLogs` timing out while
waiting for failed. Four progress dots then precede a process crash in
`replacementDuringDetachedCleanupCannotOwnTheOldSession`, with
ForegroundServiceDidNotStartInTimeException pointing to the direct
startForegroundService call. Progress dots and shell exit zero do not establish
a completed suite or final individual passes.

The production rejection branch called stopSelf(startId) on a newly created
Service without startForeground. This is an actual Android contract violation.
[Android's foreground-service troubleshooting](https://developer.android.com/develop/background-work/services/fgs/troubleshooting)
requires newly created foreground services to promote promptly.
[AOSP Android 16 ActiveServices.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/am/ActiveServices.java#L5962)
has a more specific path: bringDownServiceLocked finds fgRequired still true
and enqueues SERVICE_FOREGROUND_CRASH_MSG when tearing down the service before
foreground promotion. Therefore stopping itself can trigger the crash directly;
this is not merely a later startup deadline expiring. The same source's
startForeground path clears fgRequired/fgWaiting.

Checker fix: a rejected **new** instance first uses the same channel,
notification and API29+ DATA_SYNC foreground promotion as normal startup, with
brief closing copy, then calls stopSelf(startId). It owns no session/backend,
does not change the previous owner, and its onDestroy removes the transient
notification. Shared promote(text) keeps normal promotion consistent. The
real startForegroundService regression is retained, exercising both normal
stop and failed cleanup; it also waits at most two seconds for asynchronous
notification removal and asserts that no HFTP notification remains.

The collision fixture used InetAddress.getLoopbackAddress while Python binds
127.0.0.1. [The official InetAddress contract](https://developer.android.com/reference/java/net/InetAddress#getLoopbackAddress())
permits IPv4 **or** IPv6. The prior timeout is consistent with an IPv6-only
fixture that does not conflict with Python's IPv4 listener; the log does not
capture the chosen address, so this remains a mechanism inference. The test
now binds and asserts exactly 127.0.0.1 before starting Python, ensuring an
actual same-address collision.

Only HftpService.java and PortableToolsTest.java changed after the 73-test Dart
pass. No Flutter/Python/native rerun needed; Android checks and a complete new
device suite are required. No claim that this independently traces the user's
original Stop event. Main owns updated spec/design, final current package and
the new full seven-method/device LAN regression.

Post-feedback verification: Android lintRelease and assembleReleaseAndroidTest
passed after the final test assertion, **BUILD SUCCESSFUL in 11s**, with
**0 errors / 5 existing warnings**. Production Java and instrumentation compile
passed; `git diff --check` passed. Previous production-fix build also passed
in 14s; the 11s run includes the bounded notification-removal assertion.

Final compiled test APK SHA256:
`05519d20e5734b9ec10b2faaa5291195dc08905fbe57d1da6a6521fd52008fd8`.
HftpService.java SHA256:
`a262fc87c0a6bcc0b00150588952b2ca392e4570e543e51ed4cde5310ff171aa`.
PortableToolsTest.java SHA256:
`bbe975b751ba12d3fa2bec73c247fef648d1d1a4e453bebbb608312a33317eb3`.

Checker source freeze and hako release reported to main. No post-fix device
pass asserted here; main must repeat the complete seven-method suite.

## 2026-09-28 commit privacy review

Reviewed the exact 72 candidates in commit-plan.md after the authorized
generalize-content pass. No private LAN literals, local account/device
identifiers, host home paths, MAC addresses, private-key blocks or recognized
credential tokens remain in that scope. Upstream API/package identifiers,
loopback/wildcard binding, artifact hashes and success/failure provenance stay
intact. The two changed test fixtures use TEST-NET addresses: an invalid bind
host and a mock log/client plus its expected copy; neither adds real network
access. Production source behavior is unchanged by sanitization.

Fixed a duplicated EVIDENCE_DIR placeholder in PRD and clarified the validation
profile's user-supplied path, authorized ADB target and LAN-role placeholders.
Those roles deliberately omit exact identities and are not discovered current
configuration. Existing temporary evidence needs recreating before replay.

Focused post-sanitization checks, sequential through the existing hako:

- `./hako flutter test test/hftp_log_test.dart --reporter expanded`: 17/17
  passed, 3s.
- `./hako flutter analyze`: no issues, 1.5s.
- `./hako bash -lc 'cd android && ./gradlew
  :app:compileReleaseAndroidTestJavaWithJavac --console=plain'`: BUILD SUCCESSFUL,
  8s; instrumentation compile only, no install or phone execution.
- Candidate JSON/JSONL parsing, relative Markdown targets and `git diff --check`
  passed. The first Docker invocation was sandbox-denied; the same project hako
  check ran with scoped escalation without policy/configuration changes.

Earlier full-suite/device evidence remains historical. App lock ownership and
cleanup passed; effective screen-off persistence failed and remains a known
limitation. The user explicitly retained the Stop fix and deferred any expanded
Root power capability, then authorized this commit and privacy pass. No new
device operations, Root power controls, stage/commit/archive or unrelated
history edits by this checker.

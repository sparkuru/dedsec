# HFTP relay transfer and queued-heartbeat regression

2026-09-27 session. Implementer ownership: `hftp_relay.c`, the specifically
coordinated closed-event diagnostic in `RootOperationAdapter.java`, temporary
host fixtures and this record. No device commands, APK publication/install,
user files, Root execution or system/network configuration changes here.

## Actual-device inputs and limits

Main reported a Root LAN cycle with exact 1,376,257-byte PUT/GET and Stop/reap,
then a subsequent GET that returned status 200 but timed out during its body.
Python had logged download completion, and the relay logged closure about
15 seconds later. Existing closed-event logs omitted the fixed reason/errno;
that timestamp alone did not establish either missing bytes or idle timeout.
Separately, after the device display went off, main observed a later
`heartbeat_timeout` and cleanup. These events must not be conflated.

Main's later awake GET-only probes of its existing fixture succeeded both
with `read1` chunks and with the original `response.read(payload_size + 1)`.
Python HTTPResponse clips that amount to a known Content-Length. There is
no evidence that asking for one extra byte caused the original stall.
Main also reproduced refusal while the screen was off and saw heartbeat
failure after waking. Device power behavior remains main's acceptance work.

## Host transfer evidence

The current native Buffer receive/send/EOF paths did not yield a proven
payload-loss defect. The fixture uses an HTTP/1.0 loopback backend and the
host's already-existing `192.0.2.1/24` interface for the relay and same-subnet
client. It changes only socket-local test send/receive buffer sizes; no
interfaces, routes, firewall or VPN settings are changed.

Both before and after the heartbeat fix, exact-byte PUT/GET passed:

- 20 cycles at 1,376,257 bytes, digest
  `4c6680a5013c134df0a962e150faac87e3bda53e47e71053d88c64ca3ad01100`.
- Four cycles at 34,603,008 bytes (33 MiB), digest
  `59d8b2aea4b5a749b078f93edbc00cb8fae0a7d85bc232ed5848dbea509905c7`.
- GET alternates bulk `read(Content-Length + 1)` with `read1(7919)` and
  short pauses. Backend writes 49,157-byte fragments with a 4 KiB send buffer;
  client has a 64 KiB receive buffer. These exercise partial sends/receives
  and backpressure beyond the relay's 64 KiB buffers.
- Each cycle STOPs/reaps its relay and immediately reuses the same LAN port.

An initial 8 KiB receive-window run was deliberately interrupted because it
made the complete test unnecessarily slow; it is not counted as a complete
pass. The original broader 15-test suite belonged to the earlier restart
check and was not rerun here because those temporary fixtures no longer exist.

## Deterministic heartbeat defect and minimal fix

Previously the event loop checked its 10-second heartbeat deadline before
polling/reading control. A valid PING already queued by a live owner could
therefore be ignored if the relay was descheduled just before that check.
A simple pause while already inside poll can survive, so it is insufficient
as a deterministic reproduction of this boundary.

The temporary wrapper `pause_before_deadline.c` substitutes clock_gettime
only to SIGSTOP before the loop's first clock read, then uses the real clock.
After queuing PING and waiting 10.3 seconds, SIGCONT yields:

- Reconstructed exact pre-fix source (SHA256 `0153f3ad...`): exits with
  `heartbeat_timeout`, despite queued valid PING. Independent reproduction
  exits successfully after asserting this incorrect old behavior.
- Corrected source: consumes queued PING and remains alive for transfer/STOP.

The production change moves the unchanged 10-second check after bounded
control processing and rereads CLOCK_MONOTONIC. STOP, invalid commands, EOF,
owner loss and session boundaries retain their existing behavior. No wake
lock, scheduler change, timeout extension or removed watchdog was added.

[Linux clock_gettime documentation](https://man7.org/linux/man-pages/man2/clock_gettime.2.html)
states that CLOCK_MONOTONIC excludes system suspend. SIGSTOP descheduling is
therefore a test of scheduling delay, not a simulation or proof of Android
deep sleep. The proven queued-control race alone does not establish the cause
or resolution of the phone's screen-off failure.

The adapter now displays existing allowlisted reason/errno on `closed` events,
as it already did for error/rejected/stopped. This makes complete, idle_timeout
and socket failures distinguishable without adding untrusted output or paths.

## Checks and replay

Completed commands:

```text
python3 -u ${EVIDENCE_DIR}/ctos-hftp-transfer-20260927/transfer_check.py
python3 -u ${EVIDENCE_DIR}/ctos-hftp-transfer-20260927/control_check.py
python3 ${EVIDENCE_DIR}/ctos-hftp-transfer-20260927/reproduce_old.py
cc -std=c11 -O2 -Wall -Wextra -Werror -DCTOS_RELAY_LOCAL_TEST android/app/src/main/cpp/hftp_relay.c -o ${EVIDENCE_DIR}/ctos-hftp-transfer-20260927/relay
.devhome/android-sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang -std=c11 -O2 -Wall -Wextra -Werror -fPIE -pie -Wl,-z,max-page-size=16384 android/app/src/main/cpp/hftp_relay.c -landroid -o ${EVIDENCE_DIR}/ctos-hftp-transfer-20260927/relay-android
git diff --check
```

All completed successfully. Nine additional control/exclusivity assertions:
actual no-PING deadline 10.032 seconds; ordinary STOP/invalid/EOF; paused
without PING still times out; queued STOP/invalid/EOF after 10.3 seconds are
honored; another listener fails with errno98 while the original remains
usable for an exact 2 MiB transfer. Queued-PING survival is an additional
assertion in the transfer fixture. All owned fixture helpers were reaped;
the final process probe found none remaining.

Heartbeat-stage frozen hashes (before the following CPU-ownership iteration):

- Native source: `30c2e8de23fe84506555a83f54e646b1b09c52dc7eba195d7ba3e81e16c0491b`.
- Root adapter: `5d53a75f6b25e898ca015ff973e97fec17ed9991117a7593670072e765c8a174`.
- Strict API28 ELF: `e7edba2d1beff184e9a536f098e02ec23f8c602af19c4117b173c01400162ff3`.
- Transfer fixture: `31776f58328ab923e37763816bce8534323dc62561f7cb8144aeb3e4d37bff45`.
- Control fixture: `1a9e15ee582016b4981ca17ec931e9006d54bc898931e01a3eb4b57603d761de`.

Android lint/package review and final TARGET-PHONE awake/screen-off repeated LAN
acceptance are coordinated separately by main/check agent. Host local mode
does not prove Android physical-network selection or privileged behavior.

## R25: App-owned CPU lease for explicit Root transport

After the preceding heartbeat-only correction, main installed 168cedec… and
again observed exact awake transfers but refusal after roughly 40 seconds
with the display off. The actual HftpService was foreground/dataSync with
notification1602. A scoped power observation showed Dozing while both full
and light device-idle flags were false, and the owned App/Python/su/helper
processes were initially alive; waking later exposed heartbeat_timeout and
cleanup. This confirms the need to validate CPU ownership beyond foreground
service status; it does not prove vendor freezing or that a lock alone fixes
every power/network state.

The authorized follow-up adds App `WAKE_LOCK` permission and one
`PARTIAL_WAKE_LOCK`, fixed tag `im.majo.ctos:RootHftpRelay`, in the existing
RootOperationAdapter. Declaration/network/port/helper validation precedes
PowerManager, lock acquisition and process effects. Ordinary App service does
not construct the adapter and acquires no such lock. The lock is acquired
before su startup, is not reference-counted, has one non-renewed timeout of
five hours (the existing session bound), and does not keep the screen on.
There are no battery exemptions, settings, Root power commands or network
policy changes.

The small CpuLease resource releases if acquisition diagnostics throw. Every
subsequent constructor failure closes any created executor/process and the
lease. Failed readiness immediately closes the adapter. Explicit close uses
nested finally boundaries so executor shutdown or interrupted reap cannot
skip CPU release. Failure reporting also closes in finally even when its
callback rejects the error. Cleanup/event/stderr diagnostic callbacks contain
ordinary runtime exceptions so they cannot skip HftpService's remaining
Python/document cleanup or terminate stream draining.

This follows the official Android
[bounded acquisition guidance](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock/set)
and [release guidance](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock/release),
which describe PARTIAL_WAKE_LOCK, timed acquire, and explicit release on all
work and exception paths. Hardware behavior remains a device acceptance.

Three meaningful device tests were added and **compiled, not run by this
implementer**:

- `rootRelayCpuLeaseReleasesAfterCloseAndConstructorFailure`: real App lock
  held/released, repeated close, acquisition diagnostic failure, cleanup
  diagnostic failure. No Root operation.
- `rootRelayConstructorFailureReleasesItsAppWakeLock`: full adapter constructor
  with a valid declaration/network, observes the actual App-owned lock then
  throws before process startup; confirms the lock is no longer active. The
  initial shell probe attempted to filter active rows via a command pipe;
  the device feedback and test-only correction below supersede that probe.
- `rootRelayCpuLeaseTracksActualReadyFailureAndClose`: guarded by explicit
  instrumentation `hftpRootWakeLock=true`; ready/held and normal close,
  interrupted reap, throwing failure and cleanup callbacks, and unavailable
  backend readiness. The callback case asserts both lock release and the
  reflected owned process is no longer alive. Only owned loopback sockets and
  fixed Root network helpers; no file requests or user configuration changes.

Final `./hako bash -lc 'cd android && ./gradlew :app:lintRelease
:app:assembleReleaseAndroidTest --console=plain'` completed successfully in
9 seconds, lint **0 errors / 5 existing warnings**, and `git diff --check`
passed. An initial test compile incorrectly attempted the hidden Network(int)
constructor and failed; it was corrected to select the actual Wi-Fi through
the existing API before the final successful check. This is not a device pass.

Final frozen hashes for this CPU-ownership stage:

- Adapter: `ecb365a16864cad5646063097864a5aff79b934cc5570dc32795d5be3dbe145e`.
- Manifest: `a6d3fc8b2e4a7ca2738df8c4e843727c089155b73ed86b7d3f6da940fe819082`.
- Device tests: `b93278a3951fb15c67a34b401e9ebc05a69e240ba798f35507c7f8d63546c08a`.
- Test APK: `de71fee259435a008edf20db3e7003c631671dfdee9567fa61d4febf6b5b9203`.

The native heartbeat source remains `30c2e8de…`. Main/checker coordinate
final Flutter/package checks, installation, the ten focused instrumentation
tests, screen-off exact-byte reads, repeated actual App Stop/restart and
active-lock/PID/port recovery. This appendix does not report those as passed.

## Device diagnostic false-positive and test-only correction

Main's ten targeted TARGET-PHONE tests finished in 8.774 seconds: nine passed and
`rootRelayConstructorFailureReleasesItsAppWakeLock` failed its initial
no-existing-lock assertion. A subsequent actual adb power diagnostic reported
`Wake Locks: size=0`, with no active PARTIAL_WAKE_LOCK row for the exact Root
tag and no owned relay process. That is evidence of a faulty probe, not a
proved leaked production wake lock. Do not report the whole batch as passed.

UiAutomation executes the supplied command directly rather than invoking a
shell, as confirmed by the
[Android16 AOSP implementation](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/android/app/UiAutomationConnection.java#L512).
The earlier `dumpsys power | grep ...` therefore passed pipe/filter
arguments to dumpsys and treated any output byte as a positive lock result.
The corrected helper executes only `dumpsys power` and parses locally:
trimmed line must start `PARTIAL_WAKE_LOCK ` and contain the exact quoted
`'im.majo.ctos:RootHftpRelay'` tag. It discards all output, closes the owned
descriptor/reader, caps each stored line at 4,096 characters and the full scan
at 2 Mi characters; oversized lines cannot match. OEM historical ACQ/REL,
headers and unrelated lock names cannot produce a positive result.

Protocol cases were added inside the existing CPU-lease test: empty lock
header, historical partial record, unrelated active lock, similar test tag
are negative; exact active production row with indentation is positive.
The existing real constructor test still supplies the actual OS-level
positive/negative lifecycle acceptance.

Only PortableToolsTest.java and this record changed. Adapter/Manifest/native
source and current APK remain untouched. Main reruns the affected device
test(s); success is pending until that actual result is received.

Final test-only build `./hako bash -lc 'cd android && ./gradlew
:app:assembleReleaseAndroidTest --console=plain'` exited successfully in
8 seconds, and `git diff --check` passed. The test source is
`f3817f92e97f2ad1403343838d86ae5ae0a41652602e2733c0d51ec5e8dd2781`;
the rebuilt test APK is
`3cc68b0823d1cbde047484ab0cb088b82e5f2dde7ac048cbb6714dcb10319be1`.
Production adapter remains `ecb365a1…` and Manifest `a6d3fc8b…`.

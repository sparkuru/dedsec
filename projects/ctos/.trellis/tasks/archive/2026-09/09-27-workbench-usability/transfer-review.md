# HFTP queued-control and close-diagnostic review

2026-09-27. Reviewer scope: frozen `hftp_relay.c` and
`RootOperationAdapter.java`, local temporary evidence and Android checks.
No device actions, current APK publication, installation, configuration,
user files, docs/spec or commit changes were performed by this reviewer.

## Findings (fixed)

- `${EVIDENCE_DIR}/ctos-hftp-transfer-20260927/transfer_check.py` initially printed
  queued-PING survival without asserting it. The implementer added an
  explicit assertion after collecting failure diagnostics, on reviewer
  request. The reviewer inspected the assertion and independently reran the
  complete fixture successfully. A diagnostic boolean alone was insufficient
  as regression acceptance. No production-source self-fix was needed.

## Source review

The relay now performs bounded control reads and updates the PING timestamp
before evaluating the unchanged 10-second heartbeat deadline, with a fresh
CLOCK_MONOTONIC read. STOP, invalid/overlong control, EOF, owner loss, session
limit and signal exit retain their existing paths. Four connections, 64 KiB
directional buffers, 15-second idle limit, Root UID guard, selected network,
subnet checks and owned socket cleanup remain intact. SO_REUSEADDR remains
checked and does not add SO_REUSEPORT or permit stealing an active listener.

Adapter `closed` events use the existing fixed reason allowlist and bounded
errno formatting. They cannot add raw subprocess diagnostics, request bodies
or file paths. This distinguishes complete/idle/socket closure without
changing capability, readiness or privilege boundaries.

The deterministic old/fixed comparison in `transfer-check.md` uses a fixture
that pauses before the loop clock read. Inspection confirms that this is a
scheduling-delay regression, not proof of Android deep-sleep behavior. No
source evidence establishes a native payload-loss defect: transfers also
passed before this change. The previous device download timeout and
screen-off heartbeat failure remain distinct pending device scenarios.

## Verification

- `git diff --check`: pass.
- Strict host compile: pass with `cc -std=c11 -O2 -Wall -Wextra -Werror
  -DCTOS_RELAY_LOCAL_TEST`.
- Strict API28 Android arm64 compile: pass with the project's NDK,
  `-O2 -Wall -Wextra -Werror -fPIE -pie -Wl,-z,max-page-size=16384 -landroid`.
  Reviewer ELF matches implementer ELF exactly:
  `e7edba2d1beff184e9a536f098e02ec23f8c602af19c4117b173c01400162ff3`.
- `./hako bash -lc 'cd android && ./gradlew :app:lintRelease
  :app:assembleReleaseAndroidTest --console=plain'`: pass, BUILD SUCCESSFUL
  in 13s; lint 0 errors / 5 existing warnings. Java/native compilation is
  the applicable static/type check for these files. Log:
  `${EVIDENCE_DIR}/ctos-hftp-transfer-20260927/android-review.log`.
- Independent reviewer `python3 -u
  ${EVIDENCE_DIR}/ctos-hftp-transfer-20260927/transfer_check.py`: pass, exit 0.
  Twenty 1,376,257-byte and four 34,603,008-byte exact PUT/GET cycles,
  alternating bulk/fragmented reads, STOP and immediate same-port restart;
  queued PING after the 10.3-second pause survives and is explicitly asserted.
  Digests match the implementer's record. Log:
  `${EVIDENCE_DIR}/ctos-hftp-transfer-20260927/transfer-review-run.log`.
- Inspected implementer control fixture and exact reconstructed old source
  hashes. Nine control/exclusivity checks and the independent old-behavior
  assertion are implementer results recorded in `transfer-check.md`, not
  additional reviewer reruns. Earlier broader native fixtures no longer
  exist and were not rerun. Unchanged Flutter's 73-test result belongs to
  the preceding check, not this two-file review.

Initial sandbox attempts to use Docker and create sockets were blocked;
the scoped approved reruns above completed. No automatic-review rejection
occurred and no check remains blocked by sandbox permissions.

Frozen source hashes, unchanged after checks:

- Native: `30c2e8de23fe84506555a83f54e646b1b09c52dc7eba195d7ba3e81e16c0491b`.
- Adapter: `5d53a75f6b25e898ca015ff973e97fec17ed9991117a7593670072e765c8a174`.
- Test APK: `05519d20e5734b9ec10b2faaa5291195dc08905fbe57d1da6a6521fd52008fd8`.
- Asserted fixture: `31776f58328ab923e37763816bce8534323dc62561f7cb8144aeb3e4d37bff45`.

## Findings (not fixed) / acceptance boundary

No blocking source finding in the reviewed files. Main owns final package,
TARGET-PHONE awake/screen-off repeated LAN validation, and spec/design sync for
R24. The spec should preserve bounded-control-before-heartbeat ordering and
fixed `closed` reasons; phone power behavior and download timeout cannot be
marked resolved from host tests alone. Build window was released to main.

## R25 CPU lease and final full-scope source review

Main's subsequent `168cedec…` phone package still failed after screen-off,
despite queued-control ordering. R25 therefore adds an App PARTIAL_WAKE_LOCK
owned only by the declared Root network adapter. This section supersedes
the earlier adapter/test hashes for the R25 candidate; the native hash is
unchanged. Device outcomes remain main's separately recorded acceptance.

### Findings fixed on review

- `RootOperationAdapter`: throwing failure callbacks could terminate ping or
  event threads without closing the adapter/CPU lease; release diagnostics
  could escape close and interrupt the Service's remaining Python/broker
  cleanup. The implementer, on review request, added `reportFailure` with
  finally-close, contained diagnostic RuntimeExceptions, and nested finally
  fences so scheduler shutdown cannot skip child and CPU cleanup. Failure
  callbacks remain outside the ping monitor. Acquisition callback failure
  still propagates after releasing its acquired lock.
- `PortableToolsTest`: strengthened the real adapter callback regression to
  assert both CPU release and owned Process.isAlive == false. The diagnostic
  lock probe now filters active uppercase PARTIAL_WAKE_LOCK and the exact
  quoted own tag, excluding the phone's historical ACQ/REL `(partial)` rows
  reported by main. No other power data is returned by the probe.
- Main also owns the reviewed UI copy correction: `服务异常` accurately covers
  startup and runtime failures, and the Root checkbox mentions increased
  power use. These are actionable state/permission/effect text under the
  frontend copy contract; they add no demo or architecture description.

### Boundary and lifecycle conclusions

The adapter checks the allowlisted Root declaration, network parameters and
trusted helper before acquiring the lock. Missing/mismatched capability and
ordinary HFTP cannot acquire it. Acquisition uses the App PowerManager,
fixed `im.majo.ctos:RootHftpRelay` tag, non-reference-counted ownership and
one non-renewing five-hour acquire timeout. No screen-on lock, battery
exemption, Root power command or VPN/system policy modification was added.

Constructor failures after acquisition close any owned process/executor and
release CPU ownership. Readiness failures close immediately. Repeated close,
interrupted reaping and rejected diagnostic/failure callbacks preserve CPU
cleanup. Service stop, failure, Wi-Fi loss and session timeout retain their
owned adapter cleanup paths. The final reviewed adapter no longer propagates
ordinary callback exceptions from close into Service cleanup. Tests compile
for actual acquisition/repeated close/controlled constructor failure and,
with explicit `hftpRootWakeLock=true`, actual Root readiness, close,
interrupted reap, rejected callbacks and closed-backend startup failure.
Compilation is not execution of these new instrumentation tests.

The full worktree review also covered frontend/backend/trellis-plus indexes,
contracts and prior task review records. Code paths inspected include
strict/default Root booleans and saved config, declaration-before-effects,
App Python/SAF ownership, provider containment and exclusive create/owned
partial cleanup, readiness/log stream framing and bounds, stop/current
ownership/closing states, independent polling/clear revisions, PageStorage
isolation, precise SDK form payloads, ordinary masked IME semantics and
artifact/password export behavior. No unresolved blocking source/spec drift
was found. Scope remains ctOS; no protected Trellis templates or neighboring
projects need changes. Main owns final design/verification updates.

### Final check evidence and remaining acceptance

- Implementer's final coordinated Android lintRelease/test APK build: exit
  0, BUILD SUCCESSFUL in 9s. Reviewer independently inspected the resulting
  lint report (0 errors / 5 existing warnings), compiled APK hash and frozen
  source hashes. No redundant Android build was run after that final freeze.
- `git diff --check`: reviewer pass after R25 changes.
- Adapter: `ecb365a16864cad5646063097864a5aff79b934cc5570dc32795d5be3dbe145e`.
- Manifest: `a6d3fc8b2e4a7ca2738df8c4e843727c089155b73ed86b7d3f6da940fe819082`.
- Instrumentation source: `b93278a3951fb15c67a34b401e9ebc05a69e240ba798f35507c7f8d63546c08a`.
- Test APK: `de71fee259435a008edf20db3e7003c631671dfdee9567fa61d4febf6b5b9203`.

The preceding 25 Python tests and seven corrected phone lifecycle tests are
recorded historical passes for their unchanged paths. Native R24 source is
unchanged from the review's independent strict builds and 24-cycle fixture.
Main then reran Flutter after its copy edits: **73/73 tests passed and analyze
reported no issues**, verified from
`${EVIDENCE_DIR}/ctos-hftp-cpu-flutter-20260927.log`. No additional UI behavior changed.
The new three CPU-lease
instrumentation methods, final package, phone screen-off exact transfer,
repeated stop/restart, actual active lock release and any remaining download
stall need main's device validation. No new phone pass, physical non-Root,
cross-ROM/Android11/16KiB-page or five-hour duration pass is claimed here.

Source review is green for final packaging and scoped device acceptance;
the overall product task is not marked complete by this review.

## Actual-device feedback: test-only power probe correction

Main's first ten-method CPU-lease phone suite **did not pass**: nine methods
passed, while `rootRelayConstructorFailureReleasesItsAppWakeLock` failed its
initial no-existing-lock assertion. Main's immediate scoped ADB observation
showed `Wake Locks: size=0`, no active owned PARTIAL_WAKE_LOCK and no owned
relay process. Do not label this ten-method run a pass or interpret its probe
failure as a demonstrated production CPU-lock leak.

The previous probe passed `dumpsys power | grep ...` to UiAutomation and
treated any first byte as a match. Review of the primary
[AOSP Android16 UiAutomationConnection implementation](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/android/app/UiAutomationConnection.java#L512)
confirms that executeShellCommandWithStderr calls Runtime.exec(command)
directly. It does not invoke a shell to interpret pipes; those tokens become
arguments to dumpsys. The intended active/history grep distinction in the
preceding review therefore did not establish a valid executed filter.

The implementer corrected **instrumentation only**: execute the fixed command
`dumpsys power`, parse its stream in Java, accept only a trimmed active
PARTIAL_WAKE_LOCK row with the exact quoted owned tag, and discard other
rows without logging them. Parsing is bounded to 4096 characters per row and
2 Mi characters total; oversized rows cannot produce a match. EOF completes
the final row. Oversized total output fails explicitly instead of silently
claiming no lock. Reader/PFD ownership uses try-with-resources.

On reviewer request, the existing CPU-lease method now includes five row
assertions: empty-lock header, historical ACQ row, unrelated active owner,
similar `RootHftpRelayTest` tag, and the exact active own-tag row. Test count
remains ten. The real constructor test still requires a positive active match
after acquisition and negative match after its controlled pre-process failure.
This protects against both false-positive baseline and false-negative parsing.

Source review of this correction is green. Production adapter, manifest and
native remain at the R25 hashes above; no production APK rebuild is required
for this test-only correction. New test source SHA256:
`f3817f92e97f2ad1403343838d86ae5ae0a41652602e2733c0d51ec5e8dd2781`.
Implementer's final test-only `assembleReleaseAndroidTest` completed with
exit 0 / BUILD SUCCESSFUL in 8s; reviewer verified final source/APK hashes
and `git diff --check`. Test APK SHA256:
`3cc68b0823d1cbde047484ab0cb088b82e5f2dde7ac048cbb6714dcb10319be1`.
The corrected phone ten-method rerun remains pending; the initial nine-of-ten
result remains historical evidence. Main owns the actual screen-off/multi-cycle
checks and final acceptance. Reviewer performed no device action.

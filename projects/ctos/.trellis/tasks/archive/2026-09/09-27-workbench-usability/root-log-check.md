# Root separation and HFTP logs review

Date: 2026-09-27. Review covers the current WIP against PRD R1–R20, task
design/implementation notes, frontend/backend specs and the common capability
security reference. The reviewer performed no device operation, Root invocation,
APK installation, current release creation, commit or task archival.

## Findings fixed

- `HftpBridge`: an omitted `rootRelay` start argument previously inherited a
  saved true preference, permitting an implicit privileged start. The reviewer
  changed startup to `rootRequested(Object)`: null/false select App mode, only
  Boolean true selects Root, and other types fail before service effects.
  Configuration still restores the user's checkbox; Flutter sends the explicit
  boolean. Added regression assertions to the existing capability test.
- `RootOperationAdapter`: reviewer identified that heartbeat PING writes could
  interleave with STOP/EOF. The backend owner fixed both paths under the same
  monitor and moved heartbeat failure callbacks outside it. Final review also
  confirmed fixed-delay scheduling, removing the new Android lint warning.
- `PortableToolsTest`: reviewer added
  `nativeRootRelayRejectsAppUidBeforeNetworkEffects`. It directly executes the
  fixed packaged helper under App UID with syntactically valid parameters,
  expects first stdout `failed/root_required/EPERM`, exit 1 and no subsequent
  READY/network events. It does not request Root or open a service. This test
  compiled successfully; actual device execution belongs to main.

## Reviewed boundaries

- Host `ToolExecutionContext` declares allowlisted operations and APP/ROOT
  requirements. Missing, mismatched and unknown requests fail before Android,
  helper or `su` effects. The declaration itself grants no authority. Ordinary
  HFTP does not construct or probe the Root adapter, and Python/SAF retain App
  process ownership. Collector and PTY remain separate existing boundaries.
- Root adapter launches only the trusted extracted APK executable through a
  quoted fixed path and validated numeric/IP arguments. Production native code
  requires effective UID0 before opening sockets; the adapter validates READY
  UID0 and exact host/ports/PID. The only backend is loopback. No arbitrary
  shell, target, HTTP/file API, system network-policy mutation or Root file I/O
  was introduced.
- Native listener uses the supplied public Android Network handle and physical
  Wi-Fi IPv4. Subnet validation, four connections, fixed buffers, idle/session
  limits, partial sends, half-close, PING/STOP/EOF and owner/watchdog cleanup
  were reviewed. Service owns relay/backend/broker and stops on Wi-Fi loss or
  address/capability changes, with no silent fallback.
- Python keeps readiness as the first stdout record and emits allowlisted
  structured diagnostics afterwards; CLI logs remain readable on stderr.
  Java session ownership excludes stale streams/callbacks. Logs are bounded by
  line count, line length and actual serialized bytes, sanitized, in memory,
  reset on start, retained on stop/failure and independently cleared. Published
  state excludes duplicate retained log arrays.
- Flutter uses exact strict/default Root bools, locks configuration while active,
  preserves picker drafts and clears Root on loopback selection. Independent
  status/log revisions reject stale polling/clear responses. Copy sends exact
  lines; clear does not stop the service or alter directories.
- Existing SAF/private-file paths retain authorized-tree containment, bounded
  streaming, no-overwrite publication and cleanup of owned partials only.
  Broader WIP form/result code retains exact SDK payloads, ordinary masked IME,
  useful direct-copy/export actions and masked raw password output.
- Frontend spec now contains concise product-copy rules and good/base/bad
  examples. New Root/log text explains actual permission/effect/retention rather
  than implementation milestones. Necessary HTTP exposure and recovery text
  remains visible. Backend spec records current capability/protocol contracts.

## Verification

Commands actually run by this reviewer:

- `./hako flutter analyze`: exit 0, no issues.
- `./hako flutter test --reporter expanded`: exit 0, **68/68 passed**.
- Final `./hako bash -lc 'cd android && ./gradlew :app:lintRelease
  :app:assembleReleaseAndroidTest --console=plain'`: exit 0, 13s; final lint
  report **0 errors / 5 existing warnings**. Java/test sources and strict NDK
  relay compilation passed. An earlier intermediate check saw the heartbeat
  scheduling warning; it was fixed before this final check.
- `git diff --check`: passed.

Independent worker evidence inspected (not re-executed by this reviewer):

- Python final **25/25**, 10.32s, existing isolated uv workspace
  `${EVIDENCE_DIR}/ctos-feedback-service-check`; tests `test_hftp_logs.py`,
  `test_hftp_feedback.py`, `test_stream_failure.py`. Output:
  `${EVIDENCE_DIR}/ctos-hftp-log-python-tests.log`.
- Native **15** host socket fixtures and **3** targeted final READY/stream
  checks passed. Source SHA256 matches worker evidence:
  `50e81375901786f0ccf4249acea464484c53b914206edc9c0d36a8cd38be32c7`.
  Full native limitations and artifacts are in `root-native-check.md`.

## Not yet established

No remaining source-review blocker was identified. Packaged extraction,
App-UID rejection instrumentation, actual Root relay start/stop/reap, unchanged
VPN/lockdown LAN transfers and Windows browser behavior require main's
authorized device acceptance. A Root-capable phone and host mocks do not prove
complete acceptance on a physical non-Root device. No five-hour runtime test
or cross-ROM/Android 11 validation is claimed.

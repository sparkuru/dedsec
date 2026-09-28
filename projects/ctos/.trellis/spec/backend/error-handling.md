# Runtime Error Handling

Represent unavailable data and failed operations explicitly at the layer that
observes the failure. Do not turn permission denial, unsupported APIs, parse
errors, timeouts, cancellation, or process exit into valid zero/empty results.

- Android device snapshots return per-section `state`, `source`, `capturedAt`,
  `data`, and optional `reason`. Preserve distinctions such as
  `permission_denied`, `unsupported`, and `failed`; see `DeviceSnapshot.section`.
- MethodChannel handlers reject bad arguments with a stable error code and a
  short recovery-relevant message. Return on the main thread. Keep diagnostic
  internals out of user-facing messages when they can contain paths or secrets.
- The Python workbench returns its documented result envelope for completed,
  failed, cancelled, and timed-out short jobs. Preserve stderr separately from
  the JSON stdout protocol; do not report a cancelled job as success.
- Always release claims, temporary files, descriptors, and owned child
  processes in `finally`/close paths, including parse and startup failure.
  Cancellation must match the owning task/session ID.
- HFTP readiness is a protocol record. A failed listener or Root relay must
  publish a bounded failure state and close partial resources; it must not
  claim `running` until every selected backend is ready.
- At the Dart boundary, parse the returned shape once and preserve `source`,
  time, partial status, and the last successful snapshot when refresh fails.

Reference files: `DeviceSnapshot.java`, `NetworkSnapshot.java`,
`PythonRunner.java`, `HftpService.java`, `ctos_sdk.py`, and
`lib/connection_state.dart`.

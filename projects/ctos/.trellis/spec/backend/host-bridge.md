# Android Host Bridge

Use the Android host as the sole owner of Android APIs, Root operations,
foreground services, URI grants, subprocess lifetime, and JNI handles. Flutter
requests named operations through `ctos/native`; it does not call platform
APIs directly.

## MethodChannel boundary

- `MainActivity.call` routes service operations to `HftpBridge`, file
  operations to `ToolFiles`, short Python jobs to `PythonRunner`, and snapshot
  or PTY methods to explicit cases. Add new methods at this boundary and their
  typed Dart wrapper in `lib/workbench/api.dart` or the corresponding domain
  API.
- Validate arguments and state at the host boundary. Use stable error codes
  (`PARAMETERS`, `BUSY`, `PYTHON`, `FILES`, `CTOS`) for rejected calls; do not
  convert invalid input into a successful empty value.
- Run blocking work on its owning executor. Complete Flutter
  `MethodChannel.Result` callbacks on the main thread. Do not block the UI
  thread with process, file, network, or Root work.
- Keep the JSON strings used by the Python workbench protocol distinct from
  maps used by ordinary MethodChannel methods; update both sides and tests when
  changing a wire shape.

## Capability and lifetime ownership

- Declare a fixed operation and requirement with `ToolExecutionContext` before
  using privileged behavior. A declaration is not authorization. The current
  Root adapter is only for `hftp.networkRelay`; never pass Python a generic
  Root handle, shell, or filesystem access.
- `PythonRunner` owns one short job, its task ID, cancellation, output bounds,
  and process cleanup. Persistent HFTP is owned by `HftpService`, not by a
  Python workbench job.
- `HftpService` owns only the process, relay, network callback, locks, and
  notifications it created. Stop and failure paths must release/reap those
  resources; do not kill by port or process name.
- PTY is a separate user-started App or Root session. `Pty` owns the JNI
  descriptors/process pair; preserve serialization and close/reap behavior.
  In `pty.c`, prepare child inputs before `fork` and do not call back into the
  Java VM from the child.
- `HftpTreeBroker` and `HftpDocuments` use system-issued SAF grants. Do not
  infer filesystem paths from a URI or elevate file operations through Root.

Reference files: `MainActivity.java`, `ToolExecutionContext.java`,
`RootOperationAdapter.java`, `PythonRunner.java`, `HftpService.java`,
`Pty.java`, and `pty.c`.

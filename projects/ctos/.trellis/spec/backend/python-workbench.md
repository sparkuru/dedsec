# Python workbench and bundled portable packages

The implementation and extension contract live in
[design/portable-workbench.md](../../../design/portable-workbench.md).

- Use `PortablePackages` for APK-bundled manifest discovery and logical mounts.
  Native executables live in PackageManager's extracted native directory;
  ZIP assets contain data and modules. Keep legacy JNI packaging enabled.
- Treat bundled code as trusted application code. A subprocess is not a
  security sandbox. Do not extend the loader to external ZIPs, runtime code,
  Root handles or installation hooks without an independently designed contract.
- Shell adapters use validated names, quoted strings and subshell-scoped `export`
  (installed executable paths contain `=`); native runs use
  argument arrays. Never concatenate user parameters into Shell source.
- Workbench startup uses `-P -S` with the managed SDK PYTHONPATH. The writable
  task directory must not shadow bundled modules through Python's default
  current-directory import path. Interactive terminal scripts retain normal
  Python import behavior.
- `ctos_scripts.registry()` is the shared script catalogue and execution
  allowlist. The SDK revalidates parameter names, types, required values and
  length. Context receives task-local App snapshots and a private workdir.
- `PythonRunner` claims one task before queueing, starts one native Python
  subprocess, drains stderr separately from the JSON stdout protocol, and
  bounds both streams. A lifecycle cancellation must match the task ID.
- Cancellation and timeout destroy the subprocess; the claim is released even
  when parameters, process startup or JSON decoding fail. Scripts must not
  spawn detached processes or services.
- Root PTY retains its independent lifetime and permissions. `su -p` preserves
  the interactive `ENV` adapter. An unavailable bundled environment must still
  allow the system Shell to start and report the actual failure.
- Check Dart analyze/tests, Android lint/test APK, native ELF contents and
  actual Python runtime startup. Record QEMU and device results separately.
  Root/PTY environment, SELinux and lifecycle results require a current device
  test, not historical evidence.

## Portable tools and service boundary

The SDK v2/file/service contract is documented in
[design/portable-tools.md](../../../design/portable-tools.md).
Keep core tools separate from SDK adapters and CLI dispatch. Only the crypto
entry loads its fixed dependency module. Workbench file parameters are opaque
picker tokens; selected inputs and atomic outputs stay in ToolFiles' private
store, with explicit SAF export and bounded cleanup. Never overwrite or delete
the user's source on an error.

Publish completed tool outputs through `ctos_tools.files.commit_exclusive`.
Android App hard links may be denied even in private storage; use the shared
renameat2 no-replace primitive, preserve existing entries including symlinks,
and fail explicitly if the filesystem cannot support exclusive commit. Keep
the temporary file on the destination filesystem and clean it on failure.

HFTP is an explicitly started host foreground service, outside PythonRunner's
short-task lifecycle. The registered short-task handler cannot start it.
HftpService owns its process and notification; require enabled notifications,
implement stop/timeout/destroy, default to loopback and never kill unrelated
port owners. Its authenticated private library must reject traversal and
symlinks, bound uploads/concurrency/quota and preserve existing files.

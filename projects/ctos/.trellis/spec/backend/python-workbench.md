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
implement stop/timeout/destroy and never kill unrelated port owners. App
defaults to LAN/7888 without authentication per explicit picture feedback;
CLI retains loopback/8080 with credentials. Both reject traversal and
symlinks, bound uploads/concurrency/quota and preserve existing files.

## HFTP local directory/configuration contract

### Scope and trigger

HFTP now exposes persisted configuration and an Android-authorized local tree
alongside the existing private library. Python remains the HTTP service;
Java owns Android lifecycle, grants and SAF streaming operations.

### Signatures

`hftpConfig` returns config JSON. `hftpPickDirectory` returns config JSON or
null on cancellation; `hftpUseDefaultDirectory` returns config JSON.
`hftpStart({host, port, maxUploadMiB, treeUri})` returns starting config/state.
`FileServer(address, root: Path | PathStorage | SafStorage, password=None,
max_upload_bytes=32MiB)` and `serve(...)` share optional CLI authentication.

### Contracts

Channel config fields are strings: host, port, maxUploadMiB, treeUri,
directoryName. Status adds state/reason/urls/startedAt/maxSessionHours;
the App has no password/user fields. Bounds: host 127.0.0.1 or 0.0.0.0,
port 1024–65535, upload 1–1024 MiB. Private quota max(128MiB,4*upload),
normal ToolFiles remains 32/128 MiB, download separately bounded to 1GiB.

Only `com.android.externalstorage.documents` and
`com.android.providers.downloads.documents` ACTION_OPEN_DOCUMENT_TREE grants
are accepted with persisted read/write permissions. Service owns a random
abstract socket, verifies peer UID, and accepts bounded JSON-line operations
stat/list/read/write/mkdir followed by streaming bodies. Python never resolves
content URIs into guessed paths. No Root, all-files permission or new dependency.
API29+ checks `isChildDocument`; API28 checks canonical `findDocumentPath`.
Downloads IDs are opaque (raw/MediaStore/numeric); the additional document-ID
prefix check applies only to ExternalStorage and never replaces canonical
provider containment. The initial ExternalStorage Download URI is only a
picker location hint; the local Downloads shortcut is also accepted.
SAF metadata does not identify physical symlinks; tree containment and regular
download descriptors remain required, while PathStorage explicitly rejects
symlinks/special files. Do not claim inode-level SAF symlink detection.
Native bridge claims one preparing operation; Activity disposal and stale
callbacks cannot clear another owner's claim, mutate config or revoke its grant.

SAF lacks rename-no-replace: AOSP provider rename can race after choosing a
unique destination. Receive into an owned hidden document; create a new exact
target, reject provider auto-renaming, then copy the complete content. Reserve
the target name before creation and its document ID during copying, shielding
in-progress targets from HFTP reads/listing. Check interruption during copying,
and on failure delete only documents this request created. Other local apps
may see a target during final copying; do not claim SAF atomic publication.
No startup cleanup scan of user directories. Private PathStorage retains
renameat2 exclusive commits and its private partial cleanup.

After sending a successful streaming response header, a read failure closes
the connection; never append a second JSON/HTTP error response to file bytes.

### Validation and error matrix

| Condition | Outcome |
| --- | --- |
| Non-local tree/provider or missing persisted read/write grant | Explicit failure |
| Configuration change while preparing/running | Reject duplicate operation |
| Traversal, hidden name, control char, invalid depth | Reject/omit; no escape |
| Path symlink/special file or SAF document outside canonical tree | Reject/omit; no escape |
| Same-name file/directory or provider-generated alternate name | 409; preserve existing file |
| Upload over configured cap/quota | 413; no new committed file |
| Incomplete/disconnected upload or stop interrupt | Remove only owned target/partial |
| File above independent download cap | Reject download |
| CLI password supplied, request lacks credentials | 401 |
| App no-login request | No 401/login challenge |

### Good, base and bad cases

Good: user grants one local directory; App shares it without login and stops
its owned process/socket/workers. Base: default private library remains
unchanged until explicit start. Bad: guess a raw SAF path, clear the user's
directory, use provider rename as an exclusive atomic commit, or reuse
ToolFiles' 32MiB cap after selecting a larger HFTP upload limit.

### Required tests

Check unauthenticated App and authenticated CLI, configured cap above32MiB,
oversize rejection, exact bytes, traversal/symlink/special-file rejection,
mkdir, no overwrite and owned cleanup. Cover SAF protocol framing, bounded
responses, revoked permission and native configuration validation. Run
Android lint/test APK and actual isolated SAF tree + LAN transfer checks;
loopback browser checks do not establish device grant/lifecycle behavior.

### Wrong versus correct

Wrong: `renameDocument(temporary, requestedName)` followed by only a returned
name check. Correct: create a new target exclusively, verify its exact name,
copy the completed partial and delete only owned documents on failure.

## Host capability declarations, optional Root relay and HFTP logs

### 1. Scope / trigger

HFTP's explicitly selected LAN transport may require Root while Python HTTP,
private files and SAF remain App-owned. This is the first tool integration of
the host capability boundary; collector and interactive PTY retain their own
contracts. New privileged tools must declare a concrete operation and use a
ctOS adapter rather than receive a generic Root handle in Python context.

### 2. Signatures

`ToolExecutionContext.declare(String id, Requirement requirement)` creates an
immutable host declaration. Current allowlist: `hftp` / `APP` and
`hftp.networkRelay` / `ROOT`. `RootOperationAdapter(Context, execution,
WifiTarget, port, backendPort, logCallback, failureCallback)` validates the
Root declaration before effects; `awaitReady()` and `close()` own readiness
and cleanup. A declaration describes need, not permission or authorization.

`hftpStart({host, port, maxUploadMiB, treeUri, rootRelay})` adds a boolean mode;
`hftpConfig` includes that bool. `hftpStatus` and parameterless `hftpClearLogs`
return a complete status JSON string containing `logs: Array<String>`.

### 3. Contracts

- `rootRelay` defaults false. Config may restore the user's checkbox, but an
  omitted start argument must not elevate from saved config; only bool true
  selects Root. Host must be `0.0.0.0` in Root mode. Ordinary HFTP must never
  construct/probe the Root adapter or depend on `su` availability.
- Service creates an App execution context, starts Python on owned
  `127.0.0.1:0`, then passes the actual backend port and selected non-VPN Wi-Fi
  IPv4/prefix/Network handle to the Root adapter. Only report running after
  both backends are ready; loss/change of that Wi-Fi fails and closes the
  session, with no directory/mode fallback.
- Adapter executes only the fixed extracted APK `libctos_hftp_relay.so` with
  validated arguments and quoted trusted path. Root helper has no HTTP,
  files, arbitrary target or command API. Listener uses public
  `android_setsocknetwork`; loopback upstream is not Wi-Fi-bound. No changes
  to VPN, Clash, global binding, firewall, routes or SELinux.
- Native argv: `WiFiIPv4 prefix LANport backendPort networkHandle ownerPID`.
  Production helper rejects a nonzero effective UID before network effects;
  adapter verifies `uid == 0` in readiness. Explicit local native test mode
  skips that guard and Android binding and cannot prove privileged behavior.
  First stdout record is `{state:"ready",host,port,backendPort,pid,uid}` or a
  bounded `{state:"failed",reason,errno}`. Later relay events contain only
  fixed kinds/reasons and numeric peer/errno. Control is `PING\n`, `STOP\n`
  or EOF; serialize writers. Four peers maximum, same IPv4 subnet, 64KiB per
  direction, 15s idle, 10s heartbeat, 5h session; owner loss/stop closes owned
  sockets. Never reuse collector su or broadly kill port owners.
  Drain bounded queued control before evaluating the unchanged heartbeat
  deadline, then reread CLOCK_MONOTONIC. A queued valid PING must survive
  owner scheduling delay; queued STOP/EOF/invalid control still terminates.
  Closed events use the same fixed reason/errno allowlist as error/stopped;
  never pass raw subprocess text or paths into the diagnostic stream.
  Only the declared Root adapter may acquire an App PARTIAL_WAKE_LOCK with
  fixed tag and non-renewing five-hour maximum. Validate capability, network
  and bundled helper before acquisition; ordinary App mode acquires none.
  Constructor failure and close must release it even when process launch,
  executor creation, callback or reaping fails. This requests App CPU
  ownership during the manually started visible service; it is not a
  battery exemption or Root/system power change. isHeld() and a registered
  power-manager row prove ownership, not CPU scheduling: Android idle/ROM
  policy can ignore App locks. Never claim screen-off acceptance from those
  checks; only actual device transfer establishes it.
- Python stdout first line remains readiness JSON; later structured log
  events are consumed separately. CLI has readable stderr logs. Java caches
  at most 200 lines, 512 characters each and 32KiB serialized logs in memory;
  no Authorization/body/password/query/full SAF URI/private raw path. Dynamic
  controls are sanitized and unknown/raw subprocess diagnostics suppressed.
- Session tokens govern streams, callbacks and status publication. New
  start resets logs, stop/failure retains them, clear only empties logs. UI
  status and log revisions independently reject stale polls/clear responses.

### 4. Validation & error matrix

| Condition | Outcome |
| --- | --- |
| Missing/unknown/mismatched capability | Reject before Android/helper/su effects |
| Start without rootRelay or with false | App mode, no Root dependency |
| Non-boolean rootRelay or Root plus loopback host | Reject configuration |
| Root unavailable/denied, helper missing, network/bind failure | Explicit failed session; ordinary mode remains selectable |
| Wi-Fi missing/lost/address changed | Fail and close owned relay/backend; no fallback |
| Old session output, unsupported event, raw stderr | Ignore or bounded generic warning |
| Stop/control EOF/heartbeat expiry | Close owned listener/connections and reap owned child |
| Deadline reached with queued valid PING | Consume PING first; retain the 10s watchdog |
| Deadline reached without valid queued PING | Stop with heartbeat_timeout |
| Root adapter construction fails after CPU lock acquisition | Reap owned child/shutdown executor/release lock before propagating |
| Stop/failure/close interrupted or repeated | Release owned CPU lock once; no renewal |

### 5. Good / base / bad cases

Good: explicitly select Root LAN transport for one SAF tree; Root only moves
bytes, App handles directory grants and HTTP. Base: ordinary HFTP and other
App tools work without Root. Bad: run Python as Root, infer elevation from
saved config, give scripts a generic Root shell, or change system policy.
Correct CPU ownership: acquire a bounded partial lock after declared-Root
validation and release in cleanup finally. Wrong: assume foreground service
keeps CPU running, acquire in ordinary mode, renew forever, or let a logging
exception prevent owned process/CPU cleanup.

### 6. Required tests

Assert declaration denial before effects and ordinary-service independence;
strict/default bool and exact Channel payload; real ready/bind failures,
same-subnet/concurrency/backpressure/half-close/control and owned cleanup.
Assert delayed-loop queued PING survival and no-PING expiry independently;
also assert queued STOP/invalid/EOF and active-listener exclusion. Host
descheduling tests cannot establish Android screen-off behavior; require
device awake/screen-off exact-byte transfer and immediate same-port restart.
Assert actual App lock held only in declared Root sessions, released on
normal/failure cleanup, rejected input has no acquisition, and ordinary
HFTP remains independent. Five-hour timeout is a code bound until a real
five-hour device test is run; do not claim long-duration acceptance.
Check Python log events/ready framing/redaction, Java byte/line limits and old
owner rejection, UI clear/poll/start races and narrow layouts. Device tests
must assert actual log strings from `JSONArray.getString` rather than matching
unescaped paths in serialized JSON: Android may escape `/` as `\/`. Retain
serialized JSON for the byte-cap assertion.
Device tests
must establish App UID/file ownership, unchanged VPN settings and actual LAN
transfer; mocked native binding, loopback and ADB are separate evidence. A
Root-capable phone does not establish a complete non-Root-device acceptance.

### 7. Wrong versus correct

Wrong: `su -c python -m ctos_tools.hftp` or a saved true implicitly elevating
an omitted start field. Correct: explicit Root operation through ctOS adapter
for the fixed relay, App context for Python, false default for ordinary start.
Wrong: every stdout line replaces ready state. Correct: parse readiness once,
then accept only allowlisted bounded events belonging to the live session.

## HFTP explicit stop and same-port restart regression

Explicit Bridge/notification stop must fence callbacks before asynchronous
Android onDestroy. Maintain a stop-request flag and publish stopping with
empty reason; ready, process EOF, relay failures and startup timeout callbacks
must require a live session that is not stopping. Keep the current owned
instance occupied through cleanup, including failed state, so a new start
cannot overwrite process/relay/broker fields. Publish strict boolean `closing`
while stopping/failed cleanup owns resources, and clear it after cleanup so
the UI locks configuration/start even when the visible state is failed.
Cleanup outside the main thread
retains that ownership until sockets/children close; normal completion reports
stopped with empty reason and retained logs. Explicit stop of an already
failed/absent service clears its current error without erasing diagnostics.
An absent session is never a live owner: a cold notification STOP or rejected
empty Service must not publish stopping or mutate another instance. Guard
null session tokens explicitly; null identity equality does not establish an
owned session. Include cold/unowned Stop in the lifecycle regression.

A newly created Service invoked through startForegroundService must fulfill
foreground promotion even when it rejects a replacement during old cleanup.
Do not assume stopSelf cancels that startup deadline: the actual Android16
replacement regression crashed with ForegroundServiceDidNotStartInTimeException.
Keep the foreground-start trigger in the regression. Use a brief owned visible
notification before rejecting, without creating a new session/backend or
changing old ownership. See [Android foreground-service troubleshooting](https://developer.android.com/develop/background-work/services/fgs/troubleshooting).
Pin occupied-loopback fixtures to 127.0.0.1 when the production Python server
binds IPv4; a Java default ::1 socket does not prove a port conflict with IPv4.

Before native bind, check SO_REUSEADDR and report fixed listener_reuse on
failure, closing the newly owned socket. Do not use SO_REUSEPORT or kill/change
unrelated port owners. Real accepted TCP connections may retain server-side
TIME_WAIT after STOP even with no helper process. Corrected versions support
immediate same-port restarts, but legacy sockets without reuse may still need
natural expiry. Client-side CLOSE_WAIT is not proof of a listener or server
TIME_WAIT; interpret exact local endpoint and socket state.

Required regression: deterministic stop-before-ready/error/EOF and duplicate
start-during-failed-cleanup tests; actual upload/download, graceful close,
STOP/reap and immediate same-port restart in multiple rounds. Confirm a second
owner still fails while the first listener remains active and that failed
setsockopt closes its socket. Device acceptance must repeat transfer/stop/start
under the user's existing VPN conditions, rather than only one cycle. A bind
failure is a startup event; do not claim it explains an immediate-stop report
without tracing the operation/session that produced it.

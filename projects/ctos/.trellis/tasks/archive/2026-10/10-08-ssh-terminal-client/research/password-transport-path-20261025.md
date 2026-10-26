# Research: Password SSH transport and established-session readiness

- Query: Find the smallest production transport path from an explicit password endpoint to the existing managed-host KEX/authentication/PTY engine, including a usable established-session lifetime.
- Scope: internal source and project contracts; read-only. No builds, target execution, sockets, devices, real accounts, network requests or product edits.
- Date: 2026-10-25

## Findings

### The protocol engine is not restricted to the synthetic peer transport

`ssh_native_client.c:462` accepts an already supplied descriptor when it is readable/writable, `FD_CLOEXEC`, `O_NONBLOCK`, and `SO_TYPE=SOCK_STREAM`. It does not require AF_UNIX and contains no `socket()`, `connect()` or resolver. `ctos_ssh_native_client_open` validates the exact role/public-view/callback before attaching it (`:1601`–`:1636`), then uses that stream with `ssh_packet_set_connection` (`:1687`–`:1703`). The normal `recv(MSG_DONTWAIT)`/`send(MSG_NOSIGNAL)` loop is at `:1814`/`:1879`. Thus TCP is a compatible transport input; a production TCP acquisition/connection owner is missing, rather than an AF_UNIX-only protocol engine needing replacement.

The C tests use an in-process OpenSSH `ssh_api` server and nonblocking socketpair (`ssh_native_client_test.c:736`, `:2217`, `:2342`), and the instrumentation fixture uses `Os.socketpair`/`ParcelFileDescriptor` (`SshPasswordAuthPlatformTest.java:99`–`:119`). These exercise real encrypted protocol traffic but do not establish interoperability with an independent `sshd` or a real TCP endpoint. No production SSH connector or resolver was found among the inspected main Java/C files.

Production JNI is already wired: `SshNativeSession.Native.openManaged` is declared at `SshNativeSession.java:320`, and `ssh_session_jni.c:285` dispatches managed open to the same native client. Existing `android.permission.INTERNET` is declared in `AndroidManifest.xml:2`; minSdk is28 (`android/app/build.gradle:80`). No stock `ssh` executable or new SSH library is needed for the minimum path.

### Exact native entry contract and socket ownership

Use `SshNativeSession.openManaged(callback, exactPublicLease, rawFd, candidateId, stepBudget)` (`SshNativeSession.java:416`), on the callback's original owner thread. It obtains the selection wire from the adapter, claims the callback, and opens with no caller key/fingerprint (`:438`–`:499`). A role/view with unrelated identity is rejected. `stepBudget` is positive and at most65536 (`:21`, `:444`–`:449`).

The current native path additionally requires root `ssh/config`, known-hosts `ssh/known_hosts`, and the root's declared reference to that known-hosts file. These are not optional merely because the new UI has an explicit hostname (`ssh_native_client.c:416`–`:459`; Java constant at `SshNativeSession.java:26`). A launcher must provide a genuinely validated managed view for its chosen scope; it cannot pass an arbitrary empty FD or invent a fake public lease.

The C ownership contract is precise: all checks/setup precede consuming the caller descriptor; open attaches a CLOEXEC duplicate (`ssh_native_client.c:1687`) and on successful return owns both the original and its duplicate (`:1699`–`:1703`; documented `ssh_native_client.h:69`–`:73`). Failure inside C open closes only its temporary duplicate, leaving the original to the caller (`:1706`–`:1731`). Normal close releases callback, KEX/OpenSSH, view borrow, and original descriptor with retryable stages (`:1988`–`:2035`). Java repeats this failure-ownership guarantee in `SshNativeSession.java:9`–`:18` and performs native-close → Java-role → public-view → tombstone-destroy (`:981`–`:1025`).

There is a narrow exceptional JNI handoff edge to freeze/fix before a Java connector blindly closes on every open exception: after successful C open, `open_session` calls fallible `publish_session` (`ssh_session_jni.c:239`–`:245`). Its failure closes the native client, consuming the original descriptor, then reports the same generic open failure (`:266`–`:268`). The normal reserved slot should still exist, but `publish_session` can report mutex/slot/owner errors (`:90`–`:107`). A generic Java exception therefore does not itself prove that the numeric FD remains caller-owned in this exceptional path. Prefer a publication design that cannot fail after C ownership transfer, or an explicit consumed-FD result contract; do not solve this with an unsafe close of a possibly reused descriptor number. Holding/claiming the pre-reserved publication state across open is a candidate, subject to existing lock-order review, not a change performed here.

### Actual host-key discovery and selected-password flow already exist

Managed open creates the same initial transport KEX with a pending actual-host guard. The verifier receives the key offered by that transport (`ssh_native_client.c:577`), checks exact role/view/deadline/initial cycle, classifies the supplied managed known-hosts record, and compares exact result/host/port/connection/target/fingerprints/CAS digests (`:518`–`:541`). KNOWN is accepted without CAS; NEW requires the existing exact approved candidate and COMMITTED result. CHANGED/REVOKED/certificate/invalid cases do not become password releases. `design/ssh-launcher.md` documents the already implemented mailbox; calling `verifyHostKey` manually on a managed session is explicitly rejected (`SshNativeSession.java:952`–`:954`).

The owner can drive the existing stages:

1. Call managed open and yield bounded `session.step()` turns until `StepResult.complete` (`SshNativeSession.java:545`–`:557`).
2. `beginUserauthService()` requires that real KEX milestone, then pump until `userauthServiceReady()` (`:573`–`:601`).
3. `beginPasswordAuth()` requires SERVICE_READY and the selection receipt; pump to an explicit result (`:604`–`:635`). The native password request obtains the selected credential once through the existing callback (`ssh_native_client.c:821`–`:849`, `:919` onward), rather than a new raw-password JNI getter.
4. Only selected SUCCESS opens a shell (`SshNativeSession.java:736`–`:746`); PARTIAL, REJECTED and CHANGE_REQUIRED cannot be shown as connected (`:29`–`:38`).
5. Attach the actual session to existing `SshTerminalSession` on the SAME serial owner. `SshTerminalSession.java:101` documents this restriction; its pump publishes running only after channel RUNNING (`:169` onward), yields after a bounded step (`:193`), and has backpressure/acknowledgement and retryable close (`:218`–`:263`).

The mailbox waits synchronously within the native verifier, currently while the global JNI sessions mutex is held (`ssh_session_jni.c:110`–`:124` and step at`:316`). UI decision/cancel must use the independent mailbox/callback cancellation path, never another session JNI call queued behind that blocked lock. `design/ssh-launcher.md` already limits this wait to at most30seconds/original role deadline. This remains a single-session practical limitation, not an invitation to fabricate an asynchronous verifier return code.

### Effective configuration is not yet a consumable launch plan

The client initializes fixed `KEX_CLIENT` proposals (`ssh_native_client.c:1587`, `:1676`–`:1678`); it does not consume `SshConfigEvaluator` or full Options. The native evaluator explicitly states that retained full Options are not a launch plan (`ssh_config_eval.h:43`–`:52`), and its full-Options getter is native-only (`:118`–`:121`). Java exposes a bounded Summary (`SshConfigEvaluator.java:176`, `:641`) with host/user/port and counts/flags; this is not permission to ignore all other effective fields.

For the minimum explicit manual-password path, the main session can freeze an explicit supported plan with account host/port/user/password selection and the existing fixed transport/PTY behavior. This must be an intentional supported request shape on the one formal mainline. Imported config aliases cannot be silently reduced to Summary.host/user/port: effective ProxyJump, forwards, remote command/session mode, algorithm/path/default expansion fields must either be fully consumed or explicitly rejected. Do not graft a Java config parser or a shell-spawn ProxyCommand shortcut onto this path. The existing root/view and full supplied-manifest validation requirements still need a real owned construction/validation path; a new connect form alone supplies none of that authority.

### The current60second role deadline also kills an established shell

This is a substantive practical-use blocker, not only a connection timeout:

- Java role creation caps deadline at60seconds (`SshRoleContext.java:12`, `:95`); `requireActive` enforces it (`:110`).
- Native `shell_proof` calls `auth_proof` (`ssh_native_client.c:1275`–`:1279`), and all shell operations use `shell_live` (`:1282`). `auth_proof` checks callback, view, original deadline, bootstrap snapshot and `ctos_ssh_kex_ready` (`:854`–`:891`). `step` uses that auth guard even after shell begins (`:1777`, `:1876`, `:1918`).
- `ctos_ssh_callback_check_live` enters `begin_callback` and `role_status` (`ssh_callback_adapter.c:632`, `:392`–`:426`, `:217`–`:251`), which query an unexpired bootstrap role. Expired role registry slots are wiped/reaped (`ssh_role_bootstrap.c:119`–`:143`, `:217`–`:235`).
- `ctos_ssh_kex_ready` itself enforces the original deadline (`ssh_kex_guard.c:146`–`:155`).
- The native public view also expires (`ssh_file_view.c:185`); borrow liveness composes that expiry with handle revocation (`ssh_view_handle.c:233`–`:252`).
- Java shell operations route through `requireProtocolLive` → `requireSelectionReadLive` → callback `requireAuthenticationSelectionLive` → `role.requireActive` and `view.active()` (`SshNativeSession.java:852`–`:884`, `:928`–`:935`; `SshNativeCallbackAdapter.java:970`–`:975`). Eventual output publication repeats the callback check (`SshNativeSession.java:1029`–`:1033`).

Increasing only a Java timeout, or renewing a role every60seconds, would leave other layers expiring and would incorrectly renew credential/file/KEX authority. An ordinary foreground shell needs an explicitly separate established-session transport capability.

### Smallest narrow established-session transition

Recommended design to freeze in the main contract, not an implemented feature:

1. Perform a one-way promotion inside native `begin_shell` after the existing fresh `shell_live`/`auth_proof` and selected AUTH_FINISHED+SUCCESS checks (`ssh_native_client.c:1333` onward), while the ORIGINAL authentication deadline is still valid. Do not allow unauthenticated callers to ask for a longer role, and do not promote PARTIAL/CHANGE_REQUIRED/REJECTED results. Java must latch the corresponding state only after native success.
2. Latch immutable current transport identity: same `struct ssh *`, owner/PID/UID/session registry slot, actual approved initial host key, connection/hop/nonce binding, unique accepted KEX cycle and actual session-id digest. A later transport/host/KEX change is terminal. Preserve rekey rejection: post-KEX dispatch is disabled at `ssh_native_client.c:1243`–`:1245`, and `shell_send` rejects rekeying (`:1294`–`:1299`). The promotion grants only channel open/PTY/shell/data/resize/EOF/close on THAT encrypted transport, not new KEX/credential/file/host-approval calls.
3. Split authentication liveness from established shell liveness. Keep `auth_proof`, `ctos_ssh_kex_ready`, selected credential callbacks, file I/O and role bootstrap deadlines unchanged. Shell-only `step`/`shell_proof`/read/write/status/resize/EOF may use the latched established proof, comparing the current native session-id/KEX state against the saved immutable values without querying the expired role registry. Do not globally remove deadline checks from existing proof functions.
4. Preserve active cancellation/revocation independently of authentication expiry. Native needs a narrow promoted-callback owner/environment/cancel/closing/failed check that does not re-enter normal role-status validation; its permission must be tied to the client that actually promoted, and ordinary host/credential callback entry must remain deadline-bound/disabled. `ctos_ssh_callback_cancel` already only sets a flag under a separate mutex (`ssh_callback_adapter.c:585`–`:591`), and `close` checks owner/environment without demanding a live role (`:594`–`:629`).
5. Public-view ownership may be retained until close for retry-safe cleanup without retaining long-lived file-use authority. For shell revocation only, a narrow borrow query can check unreleased borrow, exact current handle, nonnull view and `handle->revoked` without calling deadline-based `ctos_ssh_view_live`; the handle already stores a distinct revoked flag (`ssh_view_handle.c:14`–`:24`, `:245`). All existing file getters continue using their normal expiry. Owned-test-view borrows also need explicit valid/revoked handling; do not treat a stale pointer as live merely because there is no registry owner. Alternative is a separately registered transport revoker, but do not accidentally make a closing old view revoke the new connection immediately.
6. Java already has `SshRoleContext.requireBound()` (`:105`–`:108`) which checks explicit role/view revocation without a clock. A new established-only adapter check can use this, plus existing cancel/closing/owner/session-claim checks, behind the one-way promotion. Split `SshNativeSession` shell-entry/step/output-publication checks from authentication/selection reads; keep original auth-read checks for all privilege-bearing APIs. A blanket change to `requireAuthenticationSelectionLive` would be too broad.
7. Retain the current original-owner close order, callback cancel wakeup, terminal output fencing and no credential retry. Explicit foreground/lifecycle transport revocation and any separate inactivity/absolute connection limit need a main-session product choice. Surviving authentication TTL must not silently mean surviving explicit Stop, revoked view, manual lock/session revocation, Activity destruction or host-change policy. Existing background/service work remains a separate stated feature.

This is the smallest reuse-oriented boundary: a post-SUCCESS continuation of the same transport, not a second client/version or a refreshed authentication capability. It still requires coordinated Java/C tests; changing only native `shell_proof` is insufficient because Java and callback/view guards independently expire.

### Smallest connector ownership and cancellation shape

An owned connector object should accept only the immutable authorized endpoint plan and original connect deadline, never a raw Flutter FD or shell argv. Use off-UI resolution/connection; keep one bounded resolver attempt and bounded address/connection attempts, cancellation token and late-result publication fence. DNS is a separate blocking boundary: `InetAddress.getAllByName` is not proven here to be cancellable, and minSdk28 means a newer platform cancellation API cannot be assumed on every supported device. Freeze a platform-supported resolver strategy or document a bounded nonpreemptible resolver owner/late discard; do not claim an interrupt guarantees DNS cancellation. Literal-address support can be explicit but must not silently change the original hostname/trust identity.

For each candidate address, create a CLOEXEC nonblocking stream, connect/poll with remaining budget and check `SO_ERROR`; close each failed/cancelled attempt. Bind successful peer address/port to the selected attempt while preserving the logical host spelling used for trust. Native currently checks descriptor shape, not `getpeername`, so the connector owns this endpoint-match responsibility. Check foreground/request/plan liveness after DNS, connect, and immediately before fd transfer. Keep cancellation directly able to revoke/close the still-connector-owned pending socket; after native transfer, use callback/session cancellation and owner cleanup instead of closing the numeric fd from another owner.

Before successful open the connector owns its descriptor; after successful open it must not close/adopt it a second time. Freeze the exceptional JNI handoff edge described above. Retain the same serial owner through role/callback creation, managed open, KEX/auth pump and `SshTerminalSession`; do not create the session on a transient worker then move it to another executor.

## Files Found

- `android/app/src/main/cpp/ssh_native_client.[ch]` — existing generic stream engine, actual-host verification, selected authentication, channel implementation and current TTL coupling.
- `android/app/src/main/cpp/ssh_session_jni.c` — opaque owner-thread session registry, fd open/transfer,19 session JNI APIs and global step lock.
- `android/app/src/main/cpp/ssh_callback_adapter.[ch]` — native callback owner/environment/cancellation/role-deadline guards and close protocol.
- `android/app/src/main/cpp/ssh_role_bootstrap.c` / `ssh_kex_guard.c` — original authentication role lifetime and KEX proof scope.
- `android/app/src/main/cpp/ssh_view_handle.c` / `ssh_file_view.c` — distinct explicit revocation and expiry checks, borrowed view cleanup.
- `android/app/src/main/java/im/majo/ctos/SshNativeSession.java` — typed KEX/service/password/SUCCESS/shell APIs and owner/final-output guard composition.
- `android/app/src/main/java/im/majo/ctos/SshTerminalSession.java` — reusable bounded pump, input backpressure, output acknowledgement and retryable cleanup.
- `android/app/src/main/java/im/majo/ctos/SshNativeCallbackAdapter.java` / `SshRoleContext.java` — Java authentication liveness versus existing bound-only metadata predicate.
- `android/app/src/main/java/im/majo/ctos/SshConfigEvaluator.java` / `android/app/src/main/cpp/ssh_config_eval.h` — effective summary versus native full Options, neither a finalized launch plan.
- `android/app/src/test/cpp/ssh_native_client_test.c` / `android/app/src/androidTestSecretsFixture/java/im/majo/ctos/SshPasswordAuthPlatformTest.java` — reusable encrypted synthetic-peer and JNI fixtures; no independent TCP server interop claim.

## Mandatory Gates for an Implementation

- Connected AF_INET/AF_INET6 stream acceptance, fd flags/type and ownership transfer; every connect/DNS/open/cancel failure closes only owned resources, no fd leak or reused-fd double close; specifically test JNI post-open publication failures.
- Same owner through open/auth/attach/close; direct Stop during pending DNS/connect/NEW approval/auth/pump; late resolver/connect results discarded and no wrong-attempt fd publication.
- Real managed actual offered key: known and approved NEW succeed; reject/cancel/changed/revoked/certificate/forged KEX signatures never request credentials or publish running.
- Password SUCCESS only; PARTIAL/CHANGE_REQUIRED/REJECTED fail closed; exactly selected target/user/credential receipt, no retries or second secret release.
- Promotion before original auth TTL, one attempt, real initial KEX/auth proof, same transport; expiry before promotion denies. After promotion, shell continues beyond original deadline while every new credential/file/host/KEX action stays denied/expired.
- Cancel, explicit view/role/session/foreground revocation, wrong owner/environment, changed session-id/KEX/host, close/reopen/replay and output-in-flight races still terminate/fence correctly after TTL.
- PTY/input/output/resize/EOF/window backpressure/exit/close on independent local `sshd`, followed by separately authorized Android runtime. Existing same-library synthetic peer alone is not practical server interoperability evidence.
- Imported config selecting unsupported jump/forward/session/algorithm/path behavior yields an explicit unsupported result rather than silently launching a direct default connection.

## Related Specs

`.trellis/spec/backend/ssh-native.md`, `.trellis/spec/backend/app-secrets.md`, `design/ssh-launcher.md`, `design/ssh-session-channel.md`, `design/ssh-config-managed.md`, `design/ssh-manifest.md`.

## External References

No external requests. Pinned OpenSSH10.6p1 `ssh_api.c`/Options contracts are present locally and referenced by the project documents; no new external library/API behavior was assumed as verified.

## Caveats / Not Found

No code or runtime change was made. No production resolver/TCP connector or full effective-Options-to-client plan consumer was found in the inspected source. Long-lived established-session promotion is a proposed contract, not existing support. DNS cancellation and connection-lifetime policy require an explicit implementation choice; this report does not claim that increasing a timeout or interrupting a thread implements either. Historical device authorization/runtime and synthetic crypto results do not grant or prove a new real endpoint connection.

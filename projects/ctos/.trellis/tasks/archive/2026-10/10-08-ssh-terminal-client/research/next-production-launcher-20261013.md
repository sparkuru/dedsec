# Research: post-fixture production increment and architecture decision matrix

- Query: After the same-process KEX-only launcher fixture completed, determine whether a secure independently verifiable increment remains without authorizing process topology, native-to-Java callback transport, or the production host-trust/file provider; if not, provide the minimum decision matrix and ordering.
- Scope: mixed (local ctOS sources, pinned OpenSSH 10.6p1 source, existing build/fixture evidence)
- Date: 2026-10-14

## Findings

### Post-fixture conclusion (current; supersedes the historical recommendation below)

**Implemented/measured:** the previously proposed narrow launcher seam is now complete, but only as an independent opt-in fixture. `implement.md:3-17` and `check.md:3-17,37-43` record canonical CSRB admission, current-PID/UID and deadline checks, fixed public host fingerprint admission, real pinned OpenSSH KEX through NEWKEYS, native-first close, duplicate/expired/stale retry coverage, strict fixture compilation, and successful normal JVM/lint/Release checks. `android/app/build.gradle:220-245` keeps the KEX library and assets out of the normal source set; the normal APK still contains only `libctos_ssh_files.so` (`build.gradle:157-173`).

The fixture is not a production transport. `ssh_kex_test.c:141-151` advances packets through `ssh_api` bounded in-memory input/output buffers, and `ssh_api.h:74-134` exposes those buffers directly. There is no socketpair, loopback connection, Java callback, host provider, private-key provider, userauth, PTY, forwarding, service, or production event loop in this evidence. Treating `ssh_api` memory streams or the fixture `ssh_control.[ch]` framing as the product transport would silently choose an architecture that the task has not authorized.

**Recommended:** there is no remaining production implementation slice that is both security-meaningful and independent of the three unresolved boundaries. Adding OpenSSH/KEX to the normal library would choose event-loop ownership and process topology; adding a native callback would choose synchronous JNI versus an authenticated broker; enabling `readconf.c`/`hostfile.c`/private-key access would choose the managed file, trust, and secret-provider semantics. The only architecture-neutral bounded action is evidence maintenance (source/build/package exclusion checks for the completed fixture), not a new product increment.

**Not verified:** any production startup, actual transport, native host-key issuer, Java callback path, trust/file provider, credential release, child ownership/reap, device runtime, external SSH endpoint, or vault operation remains open. No prior fixture or historical device result supplies authorization for those choices.

### Three boundary assessments

1. **Production launcher process topology — unresolved.** The current role token is process-bound: `ssh_role_bootstrap.c:142-214,217-267` records and rechecks the current `getpid()`/UID, while `ssh_role_jni.c:92-200` opens/releases in that process. The completed `ssh_native_launcher.c:172-260` is explicitly a same-process KEX fixture seam. A same-process production loop can preserve that owner model, but a child handoff requires a new authenticated post-fork transfer and exact child ownership; the current token cannot be assumed to cross `fork()`. Pinned OpenSSH `sshconnect.c:225-313` also forks a shell for `ProxyCommand` and only sends SIGHUP during cleanup, so stock proxy/process behavior cannot be adopted as the product ownership contract.

2. **Native-to-Java callback transport — unresolved.** `SshHostKeyAuthority.java:36-162`, `SshKexProof.java:28-54`, and `SshCredentialBridge.java:58-124` define guarded Java contracts, not a native callback mechanism. The pinned host callback is synchronous inside KEX (`sshconnect2.c:91-106`; `kex.c:1227-1242`), while authentication uses native prompt/private-file paths (`sshconnect2.c:1041-1076,1534-1609,2042-2151`). The fixture `ssh_control.[ch]` is an experiment whose spec explicitly says it is not a host-approval authority (`ssh-native.md:633-662`); it does not decide JNI threading, reentrancy, timeout, cancellation, peer authentication, or late-response handling.

3. **Production host trust/file provider — unresolved.** `SshPublicFileLease.java:58-142` and `ssh_file_view.c:124-260` provide bounded public config/known-host file views and deliberately omit private bodies. `ssh_config_policy.c:154-208` and the related spec fail closed when a real provider is absent. The pinned config/hostfile/authfile paths use ordinary file/config/private-key semantics (`readconf.c`, `hostfile.c`, `sshconnect2.c:1534-1609`), so enabling them would decide Include closure, host-key evidence/review/CAS, private-key handoff, and path policy. No real provider exists today.

### Minimum decision matrix

| Boundary | Choice | Files/contracts that change | Independent verification after the choice |
| --- | --- | --- | --- |
| Process topology | Same-process native event loop | Add a production launcher/event-loop target and its JNI owner lifecycle; adapt the pinned KEX/packet objects; preserve current-PID role semantics. Do not use stock `main`/`getopt`/`closefrom`. | Pinned client/server KEX over an actual socketpair or loopback; fragmented input, timeout/cancel, rekey, native-first close, and no `fork`/`exec` side effect; normal APK symbol/object audit. |
| Process topology | Child handoff | Define post-fork role transfer, child PID/UID/nonce binding, supervisor/control channel, process-group ownership, and exact bounded reap. `ssh_control.[ch]` cannot be promoted without a production contract. | Peer credentials, PID-reuse/death races, TERM/KILL/reap, FD/view cleanup, callback quiescence, and no `waitpid(-1)`; actual child transport and failure injection. |
| Native-to-Java callback | Synchronous JNI/attached loop thread | Add a native callback adapter and Java executor/guard entry points; specify thread attachment, lock/reentrancy rules, deadlines, cancellation, exception mapping, and late-call rejection. | Host classifications and exact NEW review; timeout/cancel/Java exception; no credential request before signed KEX+NEWKEYS; proof/role/operation checks on every callback. |
| Native-to-Java callback | Authenticated broker/channel | Define a production frame/identity/sequence protocol, Java broker lifecycle, peer authentication, response deadlines, and FD/secret policy. It may be needed for a child but is not implied by the fixture channel. | Malformed/replayed/out-of-order/late frames, wrong PID/UID/hop/connection, peer death, descriptor closure, broker restart, and fail-closed cleanup. |
| Host trust/file provider | Managed public view + trust proposal/CAS + typed private-key handoff | Add native adapters around real config/known-host/private-key call sites and Java provider/issuer plumbing; keep private material out of the public `FILE` view; patch/guard Include, hostfile, and authfile paths. | Full Include closure, public-file boundary, KNOWN/NEW/CHANGED/REVOKED/CERTIFICATE/INVALID decisions, exact review/CAS, no DNS/local-host bypass, and typed private-key/passphrase requests only after KEX. |
| Host trust/file provider | Keep provider closed | No production OpenSSH integration; retain existing fail-closed guards and fixture-only tests. | Negative tests prove missing provider cannot start a session or release credentials. This is the current state, not a product feature. |

### Recommended order

1. Decide **process topology** first. It determines whether the current PID-bound CSRB can remain valid, where the event loop owns the connection, and whether child transfer/reap is required.
2. Decide **native-to-Java callback transport** within that topology. Specify callback thread, reentrancy, deadline/cancel, peer identity, and response ownership before connecting `SshHostKeyAuthority` or `SshCredentialBridge`.
3. Decide the **production host trust/file provider**. The product-aligned candidate is a managed public config/known-host view plus an exact trust proposal/CAS and typed private-key/passphrase path, but that is a recommendation, not an implemented or authorized provider.
4. Only after those decisions, implement a production KEX-only slice over the selected real transport. Stop after signed KEX and inbound NEWKEYS; defer userauth/PTY/forwarding/service/UI. Its validation must use an actual transport and a real provider contract, never relabel the current memory fixture.

The recommended order is itself not an authorization to choose any option or edit production code. Until the first three decisions are made, the secure answer is **no new bounded production slice**.

### Current status and files found

Implemented/measured:

- `.trellis/tasks/10-08-ssh-terminal-client/prd.md:3`, `design.md:3`, and `implement.md:3` record the completed Java role/host-trust/KEX/typed-secret contracts, native role `open/owns/release` JNI, 150 JVM tests, lint, host bootstrap, fixed KEX build, and normal Release build. They explicitly leave the production launcher, OpenSSH callbacks, protocol authentication, PTY/forward/service/UI, device runtime, and external account open.
- `.trellis/tasks/10-08-ssh-terminal-client/check.md:224-230` is the current next-step contract: connect the role registry, host authority, KEX proof, and typed bridge to an owned launcher; use a fixed APK program, independent nonce/connection/hop/owned PID, managed public-view reference, per-hop channel, and bounded cancellation/reap; reject missing context/arbitrary CLI before `getopt`, `closefrom`, file, or network effects.
- `.trellis/tasks/10-08-ssh-terminal-client/research/android-kex-build.sh:4-9,28-29` pins the completed fixture to the `lOHF9Oai` baseline tree and NDK `27.0.12077973`, and explicitly says no ARM execution or production packaging. `android-upstream-build.sh:7-19` records the corresponding OpenSSH/OpenSSL/archive/patch/tree hashes for the earlier upstream fixtures.
- `android/app/src/main/cpp/ssh_role_bootstrap.[ch]:8-68,112-341` is a bounded metadata-only role registry. It validates current PID/UID, hop, port, nonce, binding, identifiers, and `CLOCK_BOOTTIME`; it emits only the versioned `CSRB` metadata wire. It does not carry argv, shell, path, FD, file body, or secret.
- `android/app/src/main/cpp/ssh_role_jni.c:92-200` and `android/app/src/main/java/im/majo/ctos/SshNativeRoleBootstrap.java:133-216` provide native ownership/open/release proof and native-first Java release. This is a same-process JNI seam, not a launcher or event loop.
- `android/app/src/main/cpp/ssh_file_view.[ch]`, `ssh_file_io.[ch]`, `ssh_file_jni.c`, and `SshPublicFileLease.java` are the reusable managed public-view bridge. The native view bounds and validates the closed graph, uses locked anonymous storage, exposes only public FILE bodies, and denies private bodies; it is not a host-key or credential provider (`ssh_file_view.c:124-260`, `ssh_file_io.c:31-190`, `SshPublicFileLease.java:58-142`).
- `android/app/src/main/java/im/majo/ctos/SshHostKeyAuthority.java:36-162`, `SshKexProof.java:28-54`, `SshCredentialBridge.java:58-124`, and `VaultOperation.java:48-70` are the reusable Java contracts. They require role-bound host evidence, exact NEW-key review, signed-KEX/NEWKEYS proof, typed manifest matching, and a repeated final role/host/KEX/operation guard.
- `android/app/build.gradle:157-217` shows the normal `libctos_ssh_files.so` currently contains only public-file and role JNI sources. OpenSSH, the KEX guard, config policy, control transport, and all probes are separate opt-in/host targets (`build.gradle:220-355`). The latest normal artifact evidence is in `check.md:3-17,37-43`.

Open / not implemented:

- There is no production native issuer for host-key evidence, no Java/native callback adapter for `SshHostKeyAuthority`, no production KEX proof issuer, no typed credential callback into `SshCredentialBridge`, and no event-loop call to `SshNativeRoleBootstrap.release()` (`check.md:224-230`; `ssh-native.md:510-532,664-696`).
- The production config and trust guards remain intentionally closed when a real provider is absent (`android/app/src/main/cpp/ssh_config_policy.c:154-208`; `check.md:119-125`). The existing policy parser/file callbacks therefore cannot authorize a real session.

### Pinned OpenSSH 10.6p1 entry points and required ordering

The pinned source root is `build/ssh-native-g1/stock-run.lOHF9Oai/sources/openssh-10.6p1`. The KEX patch is `.trellis/tasks/10-08-ssh-terminal-client/research/openssh-10.6p1-kex-guard.patch`; its hook order is the authoritative planned native event order.

1. **Launcher admission must precede stock `main`.** `ssh.c:617-646` sanitises standard FDs and calls `closefrom(STDERR_FILENO + 1)` before option parsing; `ssh.c:704-853` parses argv and `-i`; `ssh.c:1040-1045` processes `-o`; `ssh.c:1186-1187` reads config; `ssh.c:1275-1329` can construct a shell `ProxyCommand`; and `ssh.c:2499-2510` has a global `waitpid(-1, WNOHANG)` SIGCHLD handler. A production entry point must therefore accept a typed native launch object, validate role/wire/view/owner/deadline, and reject argv, shell, executable/config paths, arbitrary destinations, and inherited descriptors before any of those operations. Calling `ssh`'s ordinary `main` is not a safe admission boundary.

2. **Connection/banner and KEX start.** The stock sequence is `ssh_login()` in `sshconnect.c:1627-1670`: `kex_exchange_identification()` at `:1643-1646`, `ssh_packet_set_nonblocking()` at `:1653-1654`, then `ssh_kex2()` at `:1659`. In `ssh_kex2()`, `sshconnect2.c:218-281`, the connection context is installed, KEX is configured, `verify_host_key_callback` is registered at `:279`, and `ssh_dispatch_run_fatal(..., &ssh->kex->done)` runs the KEX dispatch loop at `:281`.

3. **Host-key callback and approval.** `verify_host_key_callback()` at `sshconnect2.c:90-106` checks RSA size/algorithm and calls `verify_host_key()` at `:102-104`. The real KEX path calls `kex_verify_host_key()` at `kex.c:1227-1241`; it rejects a missing callback/type mismatch and invokes the callback before returning success. The patched `kex.c` calls `ctos_ssh_kex_approved(ssh, server_host_key)` immediately after the successful callback (`openssh-10.6p1-kex-guard.patch:35-45`). The native provider must produce role-bound evidence for this exact key and then Java must apply `SshHostKeyAuthority.requireVerified()`; missing provider, NEW without exact review, CHANGED/REVOKED/CERTIFICATE/INVALID, local-host bypass, or DNS early acceptance must fail before any credential request.

4. **Host signature and KEX completion.** In the pinned generic client path, `kexgen.c:145-172` parses the host key and calls `kex_verify_host_key`; `kexgen.c:217-237` computes/verifies the exchange hash and calls `kex_derive_keys()`/`kex_send_newkeys()`. The patched hook is after successful `sshkey_verify()` and before key derivation/NewKeys (`openssh-10.6p1-kex-guard.patch:60-70`). The DH-GEX path has the same ordering at `kexgexc.c:143-171,186-210` and the corresponding hook at patch lines `83-93`. The incoming `SSH2_MSG_NEWKEYS` handler is `kex_input_newkeys()` at `kex.c:561-605`; it parses the packet and installs inbound keys at `:573-576`, then the patch calls `ctos_ssh_kex_complete(ssh)` before upstream sets `kex->done = 1` (`openssh-10.6p1-kex-guard.patch:13-22`). Only this completed, same-cycle, same-role/same-hop/same-key/session-bound proof may satisfy `SshKexProof.fromNative()`; it is not itself a credential grant.

5. **Typed credentials must be hooked by authentication method, never by prompt/output.** After KEX, `ssh_userauth2()` in `sshconnect2.c:423-480` installs the auth dispatch loop. Public-key authentication first offers public metadata at `:1995-2040`; the private-file branch calls `load_identity_file()` at `:2025-2034`. That function performs `stat()`, calls `read_passphrase()` and `sshkey_load_private_type()` at `sshconnect2.c:1534-1609`, so the production adapter must replace/refactor this path to obtain a typed `PRIVATE_KEY`/`KEY_PASSPHRASE` request and use a bounded native handoff, not a filename or prompt. `sign_and_send_pubkey()` then signs and queues the request (`:1322-1490`). Password auth is `userauth_passwd()` at `:1041-1076`, where stock code reads a prompt at `:1056-1057`; keyboard-interactive is `userauth_kbdint()`/`input_userauth_info_req()` at `:2042-2072,2075-2151`, where arbitrary server prompts are read at `:2122-2132`. These must become explicit `PASSWORD`, `KEY_PASSPHRASE`, and `KEYBOARD_INTERACTIVE` callbacks, each gated by the already completed host/KEX proof and closed on every error/cancel path. Hostbased/agent/PKCS#11/GSSAPI and `ssh_keysign()` must remain disabled for this increment.

6. **Role release follows complete event-loop and child teardown.** The stock client continues from `ssh_login()` into `ssh_session2()`/`client_loop()` and later frees the connection and calls `ssh_kill_proxy_command()` (`ssh.c:1791-1846`). `ssh_free()` closes packet state and releases key lists (`ssh_api.c:161-185`). The upstream proxy cleanup only sends SIGHUP and explicitly does not wait (`sshconnect.c:304-313`); it is not sufficient for an owned role. The production order should be: stop/cancel event loop; prevent new callbacks; close typed credential request and public view; close SSH packet/connection state; terminate only the launcher-owned process group if one exists; boundedly reap the exact owned PID/children; verify no owned FD/view remains; call native role close; only after native close succeeds deregister the Java role. Any cleanup uncertainty retains/poisons the role and suppresses future credential release. This ordering is proposed; no production implementation currently enforces it.

### Launcher/event-loop API choice

The pinned tree contains a useful embedded packet API but not a complete product client. `ssh_api.h:32-47` exposes `ssh_init()`/`ssh_free()`; `:66-88` exposes a host-key callback and `ssh_packet_next()`; `:106-134` exposes bounded input/output buffers. `ssh_api.c:86-159` initializes a client `struct ssh`, KEX proposal, and client KEX handlers without argv/config parsing. `packet.h:92-127` and `packet.c:300-335,490-500,1991-2012` expose connection assignment, nonblocking mode, and incremental FD reads. `dispatch.h:43-47` and `dispatch.c:87-134` expose dispatch, but `ssh_dispatch_run_fatal()` is fatal-on-error and unsuitable as the sole Android lifecycle boundary.

Recommendation: build a fixed native launcher core around the embedded API/real patched KEX objects, with a caller-owned bounded poll loop that drives `ssh_packet_process_read()`, `ssh_packet_next()`/dispatch, and `ssh_packet_write_poll()`. Do not call the stock `main`, `ssh_connect()` proxy path, or `ssh_userauth2()` until their static/global option and prompt/file effects have been refactored behind the typed provider. The exact API sequence for starting a client KEX from `ssh_api` rather than `ssh_kex2()` remains open and needs a compile/test proof; the available header does not itself expose every internal setup function.

### Reuse split

Reusable production contracts or low-level components, subject to the missing provider work:

- `ssh_role_bootstrap.[ch]` and `ssh_role_jni.c`: bounded token, owner/deadline checks, canonical wire, native-first close. They need an explicit decision for child ownership before being used by a forked launcher.
- `ssh_file_view.[ch]`, `ssh_file_io.[ch]`, `ssh_file_jni.c`, `SshPublicFileLease.java`: managed public config/known-host metadata view and bounded public FILE access. They cannot provide private-key bodies or hostfile mutation by themselves.
- `SshRoleContext`, `SshRoleRegistry`, `SshNativeRoleBootstrap`, `SshHostKeyAuthority`, `SshKexProof`, `SshCredentialBridge`, `VaultOperation`, and the typed `AppSecrets` methods: reusable validation/effect contracts, but not native callback implementations.
- `ssh_kex_guard.[ch]` plus the pinned KEX patch: reusable hook locations and fail-closed state machine. Current fixture issuance of synthetic known keys is not a production host-key issuer.
- Pinned OpenSSH source and `ssh_api.[ch]`: reusable upstream protocol code and packet API after a fresh, hash-pinned build with the Android compatibility patches and production provider hooks.

Fixture-only or research-only:

- `android/app/src/main/cpp/ssh_upstream_probe.c`: its header says it is synthetic research only; its `main()` requires fixed argv/executables and it launches child programs (`:1-2,710-815`). It proves upstream config/authfile/hostfile/proxy behavior in a fixture, not a product launcher.
- `ssh_memory_probe.c`, `ssh_config_policy_probe.c`, `ssh_control.[ch]`, `ssh_control_test.c`, `ssh_kex_test.c`, `ssh_role_bootstrap_test.c`, the G0/G2/policy/KEX build scripts, generated probe executables, and fixture notices: retain as evidence/regression fixtures. Do not expose their CLI, shell, synthetic issuer, `waitpid(-1)`, or control frame as a production authority.
- `ssh_config_policy.c`'s fixture view and proxy formatter are not production launchers. In particular, the formatter still builds a shell command (`ssh_config_policy.c:53-95`), while the production design requires fixed generated argv/no shell and separate role binding. The production `ctos_ssh_policy_entry()`/`trust()` stubs correctly reject missing providers (`:154-159`).
- The existing control transport is a useful framing/credential-ancillary-data experiment, not a host approval authority (`ssh-native.md:633-662`). Reuse would require a new production ownership and rollback contract.

### Historical pre-fixture recommendation (completed only as fixture)

Before the 2026-10-14 fixture result, the bounded recommendation was to implement a **KEX-only fixed launcher core** first. That recommendation is now satisfied only by the opt-in fixture described above; the following records the intended shape and the evidence that is still required before calling anything production. It must not be read as authorization to package this core or choose an unresolved boundary:

1. A native entry function accepts only a typed role record/canonical `CSRB` wire, a native-owned connection handle created by the launcher, and fixed target/hop metadata. It rechecks current PID/UID, nonce, binding, hop, connection, target, deadline, public-view identity, and owner before creating `struct ssh`; it has no `argc/argv`, shell, config pathname, executable pathname, plaintext private file, arbitrary command, or Java-supplied FD.
2. Use the pinned 10.6p1 client packet/KEX code and patched `ctos_ssh_kex_begin` → host callback/`ctos_ssh_kex_approved` → real `sshkey_verify`/`ctos_ssh_kex_signed` → inbound-key install/`ctos_ssh_kex_complete` sequence. Stop immediately after the KEX completion receipt; do not start userauth, PTY, forwarding, service, or remote command.
3. Keep the transport choice explicit. The current fixture uses the `ssh_api`
   bounded in-process input/output buffers with a synthetic peer; those memory
   streams are not a loopback connection or a socketpair. A future production
   launcher may require a separately approved loopback/socketpair design, but
   that topology is unresolved and is not implemented by this task. The current
   host test still needs fragmented identification/binary packets, no external
   SSH account/endpoint, device install, arbitrary shell/argv, plaintext file,
   or real-vault mutation, and must call the role/KEX guard only through the
   launcher admission path rather than relabeling the existing test as
   production.
4. Keep the core in a separate native build target until the process topology and Java callback transport are resolved. If the core is added to the normal APK, `android/app/build.gradle` must explicitly compile/link the pinned OpenSSH objects and preserve the existing production/fixture exclusion boundary; no stale probe output may be packaged.

Concrete implementation touchpoints are a new `android/app/src/main/cpp/ssh_native_launcher.[ch]`, a fresh pinned OpenSSH adapter/build target, and a host launcher/KEX test. Existing `ssh_role_bootstrap.[ch]`, `ssh_kex_guard.[ch]`, the KEX patch, `ssh_api.[ch]`, `android/app/build.gradle`, and Java contract tests are the integration points. Credential callback and host provider files should not be enabled until their native-to-Java transport and provider semantics are specified.

### Fail-closed conditions

Admission or protocol must fail before side effects for: malformed/trailing/non-ASCII wire; token/UID/PID mismatch; stale/expired/closed role; wrong hop/connection/nonce/binding/target/public-view; missing provider/callback; arbitrary CLI/shell/config/executable/command; unowned descriptor or child; `getopt`/`closefrom`/filesystem/network fallback; unknown host without exact review; changed/revoked/certificate/invalid host key; signature/key/session/cycle mismatch; KEX completion before inbound NEWKEYS installation; missing KEX proof; timeout/EOF/partial-I/O/protocol error; credential kind/manifest mismatch; cancellation or vault epoch/operation mismatch; cleanup, close, or exact-reap uncertainty. Do not use `read_passphrase`, terminal output, a UI boolean, a control frame, cached fingerprint, or `waitpid(-1)` as an authorization source.

### Required evidence for the next implementation

- **Source/build:** frozen OpenSSH 10.6p1 source and patch hashes; complete object/input manifest; strict `-Wall -Wextra -Werror` host build; Android ABI/16-KiB alignment/link/DT_NEEDED evidence if packaged; symbol/export audit proving only the intended fixed launcher/JNI surface.
- **Admission tests:** malformed wire, stale token, wrong process/UID, wrong hop/connection/binding, expired deadline, duplicate/replay, revoked view, arbitrary CLI/path/shell/config, invalid/closed FD, and missing callback/provider. Every case must show no file/network/credential side effect.
- **Real event-loop tests:** pinned real OpenSSH client/server packet exchange over socketpair/loopback; complete banners; fragmented input; generic KEX and DH-GEX paths; exact event trace proving host approval precedes signature proof and completion follows inbound key installation; rekey repeats the same guard. Current `ssh_kex_test.c`/27 cases are a baseline, not this evidence.
- **Credential/release tests:** assert no typed request before KEX completion; assert missing/expired/cross-hop proof never reaches a receiver; exercise cancellation and close; verify role remains live when native cleanup fails and deregisters only after successful native close. Do not open or mutate the real vault for this increment.
- **Ownership/teardown:** if a child is retained, test exact PID/process-group ownership, bounded TERM/KILL/reap, FD closure, public-view revoke, callback quiescence, and PID-reuse/death races. The current same-process JNI token cannot prove forked-child ownership.
- **Packaging:** normal Release/lint/JVM/native host tests; normal APK must contain only intentional production objects and no G0/G2/policy/control/KEX probes, notices, or stale generated libraries. No device install or external endpoint result is required or authorized for this increment; such evidence must remain explicitly open.

### Blockers / choices requiring confirmation

1. **Process topology is unresolved.** `ssh_role_bootstrap.c` binds a token to the current `getpid()`/UID, and JNI `open()` runs in the current app process, while `SshRoleContext.java:21` describes metadata supplied after a native launcher owns a child. A forked OpenSSH child cannot use the current token without a new authenticated post-fork issuance/transfer protocol. Choose same-process native event loop, or approve a separate child handoff design; do not silently assume the existing token crosses `fork()`.
2. **Native-to-Java callback transport is unresolved.** The Java contracts are package-private guards, not JNI authority. Decide whether host classification/review and typed credential callbacks run synchronously in the same JNI-attached process/thread or through a bounded authenticated broker channel. `ssh_control.[ch]` is fixture-only and cannot be assumed to be that broker.
3. **Production file/trust provider is unresolved.** Real `readconf.c`/`hostfile.c` semantics need managed Include/known-hosts providers and an encrypted proposal/CAS path; the current public view has no private body and the policy entry/trust hooks reject without a provider. Therefore the KEX-only increment should remain no-config/no-auth until this is designed.
4. **Embedded API versus source refactor is unresolved.** `ssh_api` provides packet/KEX primitives, but real `ssh_kex2`, `ssh_userauth2`, private-key loading, and prompt methods use static functions/global `Options`. Confirm whether the production branch will add a new fixed adapter around `ssh_api` or refactor/patch those client functions. The safest immediate choice is the fixed KEX-only adapter; full auth/PTY/forwarding must wait.

## Related specs

- `.trellis/spec/backend/ssh-native.md:57-64,239-332,334-370,372-470,472-610,633-696` — G1/G2/G3 boundaries, managed public view, Java bridge, native role lifecycle, control transport, and KEX guard.
- `.trellis/spec/backend/quality-guidelines.md:15-27` — strict native warnings and separation of host/device evidence.
- `.trellis/spec/backend/error-handling.md:1-18` — unsupported, timeout, cancellation, and cleanup failures remain explicit failures.
- `.trellis/spec/backend/logging-guidelines.md` — fixed/bounded diagnostics; no secret, nonce, host-key blob, path, or raw child output.
- `design/README.md`, `design/plan.md`, `design/constraints.md`, and `design/ssh-terminal.md:1-25` — design landing, current approved scope, and the 2026-10-13 SSH status.
- `.trellis/tasks/10-08-ssh-terminal-client/check.md:224-230` and `research/android-g2-source-map-20261013.md:32-130` — current next-step contract and prior pinned source map.

## External references

- Pinned local upstream/build inputs: OpenSSH `10.6p1` under `build/ssh-native-g1/stock-run.lOHF9Oai/sources/openssh-10.6p1`, Android NDK `27.0.12077973`, and the hashes recorded by `research/android-kex-build.sh:4-9` and `android-upstream-build.sh:7-19`; no new network lookup was needed.
- Existing design reference for owned Unix control transport: `.trellis/spec/backend/ssh-native.md:633-655` cites Linux `unix(7)` for `SO_PASSCRED`/`SCM_CREDENTIALS`; this is not a production host-approval authority.

## Caveats / Not Found

- No code was changed and no task/spec/status file was changed. This note is a read-only mapping; the fixture launcher exists, but production launcher, callback, and provider integration files do not yet exist.
- No external SSH account/endpoint, Android device install/runtime, arbitrary command, plaintext private-key file, or real-vault mutation was used or authorized. The proposed loopback/socketpair KEX test would be synthetic protocol evidence only.
- The exact production startup sequence using `ssh_api` in place of `ssh_kex2()` is not exposed by the public header and needs a compile-time source experiment. It is marked open rather than inferred.
- The current KEX patch hooks prove callback placement in the pinned source, but current fixture role issuance still uses synthetic known host keys and does not prove a production host-key issuer, Java callback transport, authentication, PTY, forwarding, or role teardown.
- Historical device/fixture successes remain scoped to their named opt-in methods and artifacts; they do not authorize a new device or external-network test.

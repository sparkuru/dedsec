# Research: production Java/vault/JNI fresh-child integration

- Query: What is the shortest test that joins the production Java callback/session, encrypted `AppSecrets`, production child JNI/native code, and an SSH peer without weakening the fresh effect fence?
- Scope: internal source inspection; no build, execution, device, real account, or external source.
- Date: 2026-11-23

## Findings

### Existing evidence and files

| Path | Role and present limitation |
| --- | --- |
| `android/ssh/host-jni-multihop-test.sh:73-116` | Builds pinned host OpenSSH, production callback/native code and the test fixture, then compiles **only** a test Java class. |
| `android/app/src/test/jni-multihop/im/majo/ctos/SshNativeCallbackAdapter.java:9-26` | Test-only receiver has the **same FQCN** as the production adapter, so adding production classes to its classpath does not test the production callback. |
| `android/app/src/test/cpp/ssh_callback_jni_multihop_test.c:189-235,411-448,851-980` | Real JNI/session code with C-created roles, a manually registered parent/child and C-generated fresh wire. Those must not be claimed as Java/vault construction. |
| `android/app/src/test/cpp/ssh_native_client_test.c:937-1033,2186-2277` | Reusable synthetic encrypted peer, host key, socket pair, service and packet pump; `discovery_reply == 1` sends a real protocol `USERAUTH_SUCCESS` for the test `none` request (`:2030-2044`). |
| `android/app/src/test/java/im/majo/ctos/SshNativeSessionTest.java:1106-1148,1633-1688` | Real encrypted `AppSecrets` and fresh receipt pattern, but test view, test native binding and direct Java invocation stand in for JNI. |
| `android/ssh/host-config-route-jni-test.sh:12,86-92,114-154` | Example of freezing existing production release classes, creating only required host stubs, and running `-Xcheck:jni` against host-compiled JNI. |
| `android/ssh/docker-jump-test.sh:46-65` | Existing disposable Docker fixture provides actual sshd, but the mounted client is an ARM64 C driver, not host Java/JNI. |

### Recommended smallest vertical integration

1. Add a **separate** package-local Java driver, e.g. `android/app/src/test/jni-integration/im/majo/ctos/SshFreshVaultRealJniTest.java`; do not compile or load the test-only class named `SshNativeCallbackAdapter`. Use real production release classes, `AppSecrets`, `VaultEnvelope`, `VaultManifest`, `SshVaultAdapter`, `SshPublicFileLease`, `SshNativeRoleBootstrap`, `SshNativeCallbackAdapter`, `SshManagedKnownHostsProvider`, `SshNativeSession` and `SshSharedPublicView`. Implement the Store as an encrypted-envelope in-memory fixture with a commit counter, synthetic test passwords, and real key derivation/unlock. Adapt the pattern in `SshNativeSessionTest.java:1107-1148`; attach the real native `SshPublicFileLease` by `SshVaultAdapter.openPublicFiles`, not a fake `PublicView` or reflective token.
2. Add a test-only host C JNI helper, e.g. `android/app/src/test/cpp/ssh_fresh_vault_jni_test.c`, reusing `ssh_native_client_test.c` with its `main` renamed. Generate distinct public test host keys, return public known_hosts records and a CLOEXEC/nonblocking `socketpair` endpoint to Java, and retain only server-side state. Initialize root and child peers via `setup_synthetic_server`; set root `service_expected`, `discovery_expected`, and `discovery_reply=1`, and use `jump_bridge_enabled` to forward the jump's `direct-tcpip` channel to the second peer. Expose bounded same-thread JNI helpers to pump **server peers and bridge bytes only**, with counters/cleanup; let Java invoke each production `SshNativeSession.step()` and child `pumpJumpRelay()`. Existing `jump_bridge_turn` (`ssh_native_client_test.c:5924-5940`) calls `shell_turn`, which steps the native root directly; copying it unchanged would bypass the Java session state machine.
3. In Java, unlock the vault before starting a short-lived role; use a shared `CLOCK_BOOTTIME`-compatible clock, one actual attached lease, one connection binding, common deadline, adjacent v2 JUMP/CLIENT roles and exact callback selection. The planned assembly order is: bind parent callback's `TrustCommitState` exclusively to its provider; drive its actual `beginUserauthService`, `beginAuthDiscovery`, and peer pumps until `NONE_SUCCESS`; issue `JumpAdmission`; let `parentState.forNextHop` consume it and yield `childState`; obtain `ChildTrustSnapshot` from the same `AppSecrets` and exclusively bind `childState` to its provider; create child callback; call production `prepareJumpChildFresh(..., source, childState)`, `beginJump`, `JumpChildOwner.open`, child relay pump, and child `step`. Use root known-host trust and an automatic `keyboardInteractive(...).withAutomaticDiscovery(...)` selection. `NONE_SUCCESS` here is a synthetic no-credential success, not password authentication. Signatures may change during concurrent implementation; coordinate with that agent and use its final verified API.
4. Use **distinct endpoint names** for root and target when constructing hostfile rows (the existing `format_host_key` hardcodes `example.invalid`, `ssh_native_client_test.c:807-818`). For the first KNOWN case pre-populate both keys; obtain both original lease and child fresh wire from the same real vault and verify native host evidence/base digest, actual child KEX completion and `SSH_JUMP_EFFECT_GUARD` from Java before auth. The same epoch/binding and same content are a valid fresh snapshot control; they do **not** prove refresh after a prior trust CAS.
5. Repeat with only the root key for NEW and a wrong target key for CHANGED. Check the actual production callback's guard error where observable, no `Store.commit`, no target auth/password requests and retained reverse cleanup. NEW/CHANGED may be rejected at candidate handoff (`SshNativeCallbackAdapter.java:594-617,644-665`) before host evidence/KEX, so do not require an event that the actual path never emits. Assert a positive KNOWN control to avoid accepting unrelated peer, native or setup failures as correct refusal.
6. Close child owner/session before parent, then seal and close shared view, close providers/roles/vault, and clean each server/socket owner. Validate a new managed handle can be allocated after all native borrows retire, rather than treating one destroy return as proof (`ssh-view-borrow-ownership.md`). If a native open transfers ownership then fails, retain its owner and exercise exact abort/close retry rather than double-closing a descriptor.

### Build/run and practical constraints

- New script, e.g. `android/ssh/host-fresh-vault-jni-test.sh`, can reuse the pinned inputs and flags of `host-jni-multihop-test.sh`, plus production `ssh_session_jni.c`, `ssh_file_jni.c`, and `ssh_role_jni.c` alongside its existing sources. Name the host shared library `libctos_ssh_files.so` for production `System.loadLibrary`, compile the C fixture with required test hooks, `-Wall -Wextra -Werror` and `-Wl,--no-undefined`, and run a separate JVM with `-Xcheck:jni` and a bounded timeout. Do not compile `ssh_session_jni.c` twice: current multihop C fixture includes it directly (`ssh_callback_jni_multihop_test.c:38-45`), whereas the new helper can link it once normally.
- Compile/freeze the current production Java classes first, then copy and hash them before the host test as in `host-config-route-jni-test.sh:86-92`; otherwise simultaneous Gradle runs or ongoing agent edits can mix binaries and source. Put the separate driver and minimal Android stubs first on the classpath, never a substitute for `AppSecrets`, role, view, callback or session. Reconfirm any stubs actually used and report them. The Java clock and native role deadline must share a time base; a small test JNI `clock_gettime(CLOCK_BOOTTIME)` helper avoids assuming `System.nanoTime` exactly matches across suspend.
- The source fixture's static peer utilities assume `example.invalid`, key/selection layout, synthetic `pair[1]`, and C-side client ownership in multiple `CHECK`s. Reuse server-only routines or split them; do not call whole `setup_jump_fixture`/`cleanup_fixture` because those create/close their own native clients and callback/role owners (`ssh_native_client_test.c:1229-1261,1661-1710,5346-5358`). Owner-thread restriction applies to Java and JNI; let a single Java thread drive all session and peer turns.
- Native `compatible_hostfile_view` requires same vault UUID, connection, root, exact deadline and metadata; same epoch also requires identical generation/binding and non-hostfile contents (`ssh_native_client.c:684-720`). Choose distinct key rows/ports correctly for KNOWN/NEW/CHANGED, and use the exact root/known_hosts IDs and paths in both Java snapshots. Prepare encrypted inputs before spending the role's 60-second lifetime. Keep 64 KiB memlock in the test to expose its real capacity boundary.
- A starting order of magnitude is 250–500 lines of dedicated fixture code plus integration debugging; this is an estimate, not a measured implementation duration. Run the new focused host test once after inputs stabilize, then appropriate Java/Android checks. The current shell script rebuilds pinned host OpenSSH from source; avoid rerunning it for every Java-only assertion change.

### What this does and does not prove

This would first join real production Java callback + session + fresh-source receipt/wire + encrypted in-memory vault + real native JNI + encrypted **synthetic** SSH peer in the same process. It can prove KNOWN KEX and fail-closed NEW/CHANGED/auth denial before cross-hop effect authorization exists. It cannot establish child credential delivery, authenticated target PTY, durable trust success, actual sshd interoperability for this Java chain, Android ART/device behavior, or a product factory connection. Those require the real effect guard and separate integration against the already authorized isolated Docker sshd fixture; do not remove the fence to make a test pass.

## Related specs

- `.trellis/spec/backend/ssh-proxyjump-child.md` — fresh source, JNI owner, admission and disabled effect paths.
- `.trellis/spec/backend/ssh-proxyjump-callback-proof.md` — exact native callback scope and non-atomic ancestor checks.
- `.trellis/spec/backend/ssh-view-borrow-ownership.md` — lease, borrow and retryable cleanup ownership.
- `.trellis/spec/backend/quality-guidelines.md` — distinguish host, device and actual-server evidence.

## External references

None. The plan is inferred from checked-in source and existing project fixtures; it has not been implemented or run.

## Caveats / Not Found

No existing test already performs the requested joint Java/vault/JNI/SSH chain. Current `prepareJumpChildFresh` admission/provider details may change while the parallel implementation agent works; the new driver must use the final API and must not fake the admission or alter production guards. The Docker sshd fixture is a separate later integration step, not a substitute for this host JVM test.

# Research: Minimum usable password SSH host path

- Query: Identify the Android production wiring needed to turn the existing SSH protocol/session components into an operable password connection with real offered-key confirmation, default transient password, and the single formal manifest model.
- Scope: internal source and task/spec evidence only; focused read-only investigation. No builds, devices, real accounts, network, target execution or product edits.
- Date: 2026-10-25

## Findings

### The terminal already has a usable attachment seam, but nothing invokes it from the product

`MainActivity.java:57` defines `AuthenticatedSshFactory.create(BooleanSupplier)` and retryable `close`. `:64` exposes package-private `attachAuthenticatedSsh(factory, mode, columns, rows)`. It reserves the shared terminal, calls the factory on the original serial `terminalWorker` (`:76`–`:90`), attaches the actual returned `SshNativeSession` to `SshTerminalSession` (`:93`), and completes only after the shell reports RUNNING (`:103`–`:109`). Partial resources remain owned until close confirms cleanup (`:138`–`:169`). This is the production seam to call, not a fixture substitute or another terminal implementation.

There is no production caller or SSH MethodChannel entry for that seam. `MainActivity.call` only routes `terminalStart` to the local App/Root `startTerminal` (`MainActivity.java:368`, `:465`). The existing write/resize/stop cases already branch to `sshTerminal` (`:373`–`:404`). `terminalStop` immediately sets cancellation before queueing owner cleanup (`:304`–`:315`), and terminal generation checking prevents stale UI commands from reaching a later session. A new SSH start/request bridge must asynchronously complete the `attachAuthenticatedSsh` future, not block the main thread or wait on the same terminal executor.

`SshTerminalSession.java:27`–`:66` adapts actual native shell methods. Its pump publishes RUNNING only after the channel does (`:174`–`:189`), handles bounded partial input, resize, one step per turn and acknowledged output (`:176`–`:226`), and retains handles on incomplete cleanup (`:246`–`:269`). Reuse this and the existing `ctos/terminal` event envelope. The Flutter terminal already knows `remote:true` semantics; it lacks a connect/profile/host-review entrypoint, not a remote output renderer.

### There is a complete *synthetic assembly example*, not a production connection factory

`SshPasswordAuthPlatformTest.java:225`–`:322` builds a synthetic vault, opens an AF_UNIX socket-pair peer, gets a real public-file lease, issues a role, creates exact host approval/CAS dependencies, chooses authentication, and opens the native session. That sequence is useful assembly evidence. Its MemoryStore, test validator, fixture host key, auto-response thread and socket pair are not production providers.

The reusable production APIs are:

1. `SshVaultAdapter.fileContext`/`fileRequest`/`openPublicFiles` (`SshVaultAdapter.java:73`–`:97`) to obtain a real source-bound public lease where managed files are used.
2. `SshNativeRoleBootstrap.open` then `issue` with `SshRoleRegistry` (`SshNativeRoleBootstrap.java:162`, `:183`), with exact owner UID/PID, connection/target/view/nonces/deadline. Do not call a permissive fake role issuer.
3. `SshHostKeyAuthority` and `SshHostKeyReviewMailbox` for exact real-offer approval; `SshManagedKnownHostsProvider.bindForNativeHandoff`/`nativeCandidateHandoff` for actual managed trust writes (`SshManagedKnownHostsProvider.java:321`, `:326`).
4. `SshCredentialBridge`/`SshManagedProvider`, `SshNativeCallbackAdapter.bind`, `SshAuthenticationSelection.password(role, username, entryId, clock)`, then `callback.selectAuthentication` (`SshNativeCallbackAdapter.java:211`–`:225`, `SshAuthenticationSelection.java:33`).
5. `SshNativeSession.openManaged` (`SshNativeSession.java:416`) with the actual connected FD, no guessed pre-known host key. Pump `step` to complete KEX; `beginUserauthService` (`:573`), pump to `userauthServiceReady`, then `beginPasswordAuth` (`:605`) and pump to `PasswordAuthResult.SUCCESS`. Returning the actual authenticated session to the Activity factory lets the existing controller call `beginShell` and enforce SUCCESS again.

The factory must stay on `terminalWorker` for the whole sequence. Cross-thread cancellation is already permitted, but moving a session to a new network/read executor is rejected. Managed open must receive an explicitly owned, connected, read/write, stream FD with `FD_CLOEXEC` and `O_NONBLOCK`; native `validate_descriptor` checks all of these (`ssh_native_client.c:460`–`:477`). Native takes ownership only after successful open (`:1687`–`:1700`) and retains the caller FD for close (`:2023`–`:2025`). A connector needs tested success/failure/duplicate/detach ownership, timeout/DNS/cancel handling and final current-request checks. There is no SSH TCP connector among current Java sources.

### Shared vault access and saved-account consumption are missing

`MainActivity.java:183` constructs `AppSecretsBridge`, whose production constructor obtains the process-owned vault using `AppSecretsOwner.acquire(activity)` (`AppSecretsBridge.java:137`–`:142`). `AppSecretsOwner.java:11`–`:15` is the existing shared owner. A production SSH bridge should consume this same owner/lifecycle rather than instantiate a second encrypted or fake in-memory vault. Authentication should stay with the existing common initialization/unlock UI; its guards and foreground/background behavior must be reused.

`VaultSshMetadata.Account` is currently consumed only by the manifest codec/structural validation and the restore metadata-content gate. There is no account list/create/edit/delete API, no persisted-account selection capability and no live mapping from `Account.host/port/username/authKind/credentialId` to the role/request/selection. Do not infer host/user from opaque `targetIdentity` or trust structure alone as remote-account authority.

`AppSecrets.attachSshValidator` is an attachment seam (`AppSecrets.java:527`), and production bridge setup does not attach a complete validator. The fixture does (`SshPasswordAuthPlatformTest.java:340`). Public-file and credential entrypoints require the registered validation context; a new path must implement its actual supported obligations, reject unsupported material/config/feature inputs and preserve all existing guard/lifetime checks. Do not attach `manifest -> {}` or promote partial reports to full admission.

### Default transient password cannot be implemented through the present saved-entry API

The current requirement explicitly says login passwords are not saved by default and may be used once or saved by an explicit user choice (`prd.md:379`). `SshAuthenticationSelection.password` requires an entry ID. `SshCredentialBridge.openSelection` calls `openCredential` (`SshCredentialBridge.java:163`–`:173`), which issues a typed AppSecrets saved-entry operation (`:98`–`:111`). `SshManagedProvider.credentialBytes(..., selection, ...)` directly uses that path, not the older injectable `CredentialRequestSource`. Supplying a different legacy credential source therefore does not enable the official selected-auth path.

A coherent minimum product path needs a host-owned, one-attempt transient password lease/request that shares the existing actual role/host/KEX/selection/connection/epoch/cancellation and final guarded-copy semantics. It must be wipeable on every failure, reject reentry/replay/late use and never be a MethodChannel/native-token getter. A temporarily persisted entry followed by deletion is not "use once"; a new MemoryStore AppSecrets is a second fake vault and should not be introduced. A saved-password-only slice is technically smaller but does not meet the existing default-not-saved requirement and should not be called the complete minimum product flow.

### Offered-key approval and trust-once for an actually empty vault

**The existing managed provider cannot support no-write trust-once merely by substituting a Java approval callback.** Its proof is deliberately tied to real managed trust data:

- `ssh_native_client.c:418`–`:455` hard-codes public view root `ssh/config`, requires an actual CONFIG object and an actual KNOWN_HOSTS object, and requires the root to directly reference that known_hosts path. A lease rooted at only known_hosts, an empty view, or a fabricated scalar view ID is insufficient.
- `SshPublicFileLease.java:70` rejects an empty snapshot. Its source format has real vault/snapshot provenance, and AppSecrets public-read admission selects an actual stored closure (`AppSecrets.java:2640`–`:2646`). An empty real shared vault currently has no such closure.
- `SshManagedKnownHostsProvider.snapshotFor` loans an actual AppSecrets snapshot and searches for the exact KNOWN_HOSTS type/ID/path/identity (`:369`–`:390`). It cannot fabricate a receipt from a display fingerprint.
- `SshHostKeyAuthority.requireVerified` permits NEW only with an exact proposal and approval (`SshHostKeyAuthority.java:183`–`:189`). `SshNativeCallbackAdapter.verifyHost` then unconditionally requires the managed NEW write-back (`SshNativeCallbackAdapter.java:518`–`:527`).
- Native `host_result_matches_issue` requires every NEW result to carry CAS_COMMITTED (`ssh_native_client.c:519`–`:520`), not simply a successful UI decision. A memory update reported as the existing durable CAS receipt would misstate its authority.

Consequently there are two *implementation options*, not two product versions:

1. Keep the current managed trust protocol and create/use real CONFIG/KNOWN_HOSTS objects in the actual common vault, then perform the existing real exact CAS after approval. The password can still be transient once that missing source is implemented. This writes public connection/trust state and must be an explicit operation with genuine AppSecrets admission/atomic effects, not a hidden fake vault/scaffold or fake validator. It also requires a supported, clearly defined source for direct endpoint settings; it cannot reinterpret arbitrary imported config as a Java field subset.
2. Add an explicit one-connection/direct-request trust and public-view authority seam for the empty-vault, no-trust-write case. It should be based on the actual user connection request and shared-vault/session foreground authority, not invented vault snapshot IDs. Continue using the same pinned SSH implementation, actual transport offered key, canonical key digest/software policy, exact role/request ticket, native signature verification and accepted KEX cycle. Record a distinct **approved-for-this-connection** result with no persisted-KNOWN/CAS claim, and let it authorize only that exact native session/role/key. Do not fabricate KNOWN, use guessed PINNED material, permit certificates without authority, ignore an existing applicable CHANGED/REVOKED result, or let approval itself substitute for signed KEX. The Java authority, callback result wire, native verifier and view/role admission must agree; this is a new narrow contract, not a ready existing overload.

For the user's minimal default-transient goal, option 2 is the clean no-write path, but it needs actual cross-layer work. Option 1 is the existing trust machinery with a real public-state write. The main session must choose/freeze the product semantics before implementation; this report does not claim either extension already exists.

`SshHostKeyReviewMailbox` itself is reusable for both once the evidence/approval contract is made explicit: exact public ticket, cross-thread `resolve`, independent cancellation and no token/secret export (`SshHostKeyReviewMailbox.java:21`–`:50`, `:148`). UI decisions must bypass the blocked terminal-owner queue. Its 30-second wall maximum and original role deadline remain relevant; `Sink.present`/`dismiss` must only enqueue publication, never wait.

### The current 60-second authentication role is also a shell lifetime limit

`SshRoleContext.MAX_LIFETIME_MILLIS=60_000` (`SshRoleContext.java:11`) bounds every issued role. Native bootstrap independently imposes the same bound (`ssh_role_bootstrap.c:47`–`:54`). Shell methods call `requireProtocolLive` (`SshNativeSession.java:736`–`:882`) and native checks `client->deadline` during protocol/session operations (`ssh_native_client.c:570`, `:753`, `:865`, `:1977`). Existing public-view/session revocation further ties shell liveness to the vault/session capability.

Thus attaching the present assembly gives a bounded protocol demonstration, not a normal long-running shell. The PRD separately requires that locking the vault deny new secret operations while established SSH follows the foreground-service/background-retention/manual-stop contract (`prd.md:380`). Extending a scalar MAX lifetime or silently renewing secret authority is not a sound fix. A practical connection milestone needs a separately specified post-SUCCESS session authority that can perform shell I/O/close without reopening credential or trust rights, plus actual app lifecycle/Stop behavior. Rekey is also currently unsupported by the channel contract; do not promise full long-session compatibility from a successful short command.

## Proposed implementation ownership and acceptance

The smallest coherent production ownership split after the main session freezes the narrow direct-connection contract is:

- Host request/credential owner: shared AppSecrets consumer context, immutable direct endpoint/optional selected-account capture, one-attempt transient secret source and exact cancellation/publication binding; add saved account mutation/selection later only through the same formal model.
- Native connection/trust owner: connected-FD admission/ownership, actual offered-key confirmation with the selected real-managed or explicit connection-once authority, original owner-thread KEX/service/password sequence and SUCCESS-only shell authority handoff. Reuse existing OpenSSH and callbacks, not a second client or fixture transport.
- Android/Flutter entry owner: bridge start/status/stop/review decisions, sanitized public profile/review metadata, explicit password/save choice, async `attachAuthenticatedSsh` completion and existing terminal routing; reuse the common unlock/sensitive-page lifecycle.

Focused tests must exercise real production orchestration with injectable boundaries, not merely construct a pre-authenticated fixture: transient secret not stored/replayed/released before real proof; exact account/role/username mismatch; cancel/lock/DNS/connect/FD-transfer/cleanup failures; known/new/changed/revoked and actual key mismatch; UI late/duplicate/rejected review with Stop while awaiting approval; RUNNING only after SUCCESS+PTY+shell; input/resize/output/exit/Stop; post-auth lifetime distinct from secret authority. A scoped isolated SSH server plus the real product connector is needed for practical end-to-end evidence when authorized. Existing JVM/ARM synthetic matrices cannot establish that the new UI route actually connects to an endpoint.

## Files Found

- `android/app/src/main/java/im/majo/ctos/MainActivity.java` — host-only authenticated factory seam and current local-only terminal dispatch.
- `android/app/src/main/java/im/majo/ctos/SshTerminalSession.java` — serial shell pump, bounded queues, output acknowledgment and retryable close.
- `android/app/src/main/java/im/majo/ctos/SshNativeSession.java` — real managed open and KEX/service/auth/SUCCESS shell state machine.
- `android/app/src/main/java/im/majo/ctos/SshRoleContext.java`, `SshNativeRoleBootstrap.java` — exact role/view/owner/deadline bootstrap.
- `android/app/src/main/java/im/majo/ctos/SshCredentialBridge.java`, `SshManagedProvider.java` — saved-entry typed credential pipeline and missing transient source.
- `android/app/src/main/java/im/majo/ctos/SshHostKeyAuthority.java`, `SshHostKeyReviewMailbox.java` — role/key-bound evidence, NEW proposal approval and public review ticket.
- `android/app/src/main/java/im/majo/ctos/SshManagedKnownHostsProvider.java`, `SshVaultAdapter.java` — real snapshot/candidate/exact managed CAS and protected public-file access.
- `android/app/src/main/java/im/majo/ctos/AppSecrets.java`, `AppSecretsOwner.java`, `AppSecretsBridge.java` — common vault/session/lifecycle/admission ownership.
- `android/app/src/main/cpp/ssh_native_client.c` — descriptor admission, hard managed-file prerequisites, NEW-CAS result and protocol lifetime gates.
- `android/app/src/androidTestSecretsFixture/java/im/majo/ctos/SshPasswordAuthPlatformTest.java` — complete synthetic component assembly; not a production launcher.
- `android/app/src/test/java/im/majo/ctos/SshTerminalSessionTest.java`, `SshNativeSessionTest.java`, `SshHostKeyReviewMailboxTest.java`, `SshManagedKnownHostsProviderTest.java` — focused reusable state/lifetime/provider regression suites.

## Related Specs

`.trellis/spec/backend/host-bridge.md`, `app-secrets.md`, `ssh-native.md`, `ssh-manifest.md`; `.trellis/spec/frontend/terminal-interaction.md`; `design/ssh-launcher.md`, `ssh-session-channel.md`, `ssh-manifest.md`, and the original task requirements. Latest user single-mainline/minimum-password direction supersedes historical sequencing that postponed every connector behind all optional certificate/restore work; it does not authorize fake proofs or relaxed trust checks.

## External References

None needed. This report describes local code and recorded requirements. Any Android socket/FD API selected for implementation should be checked against its actual platform ownership contract and tested; this research does not assume a particular Java Socket-to-FD duplication behavior.

## Caveats / Not Found

- No production SSH connector, SSH-start MethodChannel dispatch, account UI/API, transient credential source, empty-vault trust-once source, or post-auth long-session authority was found in the reviewed source.
- No devices, real vaults, accounts or current network endpoints were inspected. No current device/runtime authorization was inferred.
- Line numbers reflect source observed on 2026-10-25 before concurrent connector implementation. Proposed seams above are recommendations requiring an implementation contract, not implemented behavior.

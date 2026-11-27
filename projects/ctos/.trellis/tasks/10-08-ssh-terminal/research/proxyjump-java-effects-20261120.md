# Research: ProxyJump Java trust and credential effects

This research predates the private receiver-bound JNI point-in-time check added later on 2026-11-20. The new check does not supply a durable chain capability, effect ordering, JNI child construction, or a product connection; its current contract is in `.trellis/spec/backend/ssh-proxyjump-callback-proof.md`.

- Query: Where can a real, revocable ancestor-chain proof guard NEW/CHANGED trust persistence and selected credentials, and what ordering and ownership does that require?
- Scope: internal source and local task specs; no device, real account, service, execution test, or internet access.
- Date: 2026-11-20

## Findings

### Files found

| File | Relevant responsibility |
| --- | --- |
| `android/app/src/main/cpp/ssh_native_client.c` | Exact parent/relay/child linkage, recursive upstream checks, real SSH KEX and credential callback boundaries. |
| `android/app/src/main/cpp/ssh_callback_adapter.c` | Native callback slot ownership, cancellation, callback reentrancy and before/after callback checks. |
| `android/app/src/main/cpp/ssh_session_jni.c` and `ssh_callback_jni.c` | Session registry and synchronous JNI-to-Java calls; no child-session entry point was found. |
| `android/app/src/main/java/im/majo/ctos/SshNativeCallbackAdapter.java` | Current-hop JNI evidence, selected-auth callback, KEX and trust acknowledgement. |
| `android/app/src/main/java/im/majo/ctos/SshManagedKnownHostsProvider.java` and `SshVaultAdapter.java` | Exact trust candidate, approved source and NEW/CHANGED CAS routing. |
| `android/app/src/main/java/im/majo/ctos/AppSecrets.java` and `VaultStore.java` | Final bounded secret copy and actual AtomicFile commit protocol. |
| `android/app/src/main/java/im/majo/ctos/SshCredentialBridge.java`, `SshManagedProvider.java`, `SshTransientPassword.java`, `SshTransientKey.java` | Actual selected vault-backed and one-use password/key paths. |
| `android/app/src/main/java/im/majo/ctos/SshRoleContext.java`, `SshPublicFileLease.java`, `VaultSession.java` | Current-hop role/view locking and the single active vault revocation lease. |
| `android/app/src/main/java/im/majo/ctos/SshNativeSession.java`, `SshPasswordConnector.java`, `SshSessionOwner.java` | Owner-thread native entry, direct connection assembly, cancellation and trust result ordering. |

### Current native/Java boundary

1. Native `upstream_proof` checks the exact relay owner, live parent, parent-to-relay and relay-to-child identities, next-role token and parent's authenticated shell proof, recursively (`ssh_native_client.c:161-185`, `:2224-2240`). It runs before and after the host evidence and KEX callbacks (`:738`, `:821-828`, `:885-891`) and around selected credential handoff/copy (`:1025-1084`). These are temporal checks; they cannot undo an effect already executed inside Java.
2. The JNI evidence call passes only the current role token, hop, port, connection, target, host and host evidence (`ssh_callback_jni.c:254-296`); selected credentials pass current selection receipt, cycle and digest (`:580-614`). Java verifies those against the current role and exact callback-owned host/KEX proof (`SshNativeCallbackAdapter.java:768-783`, `:1015-1091`). No parent client, pinned relay chain, ancestor proof or chain owner is present in these callback parameters. Java session `NativeBinding.openManaged` likewise supplies only the current role/callback/view/socket (`SshNativeSession.java:160-176`, `:653-708`). Search found `ctos_ssh_native_client_open_jump_child` in native C but no JNI jump-child entry in `ssh_session_jni.c` or Java `SshNativeSession`.
3. A regular JNI session recheck from the synchronous callback is unsafe: `Native_step` holds the non-recursive global `sessions_lock` from `lock_session` until the native call returns (`ssh_session_jni.c:26`, `:132-146`, `:343-370`), while native can call Java during that call. Java `enterNative` also explicitly rejects reentry (`SshNativeSession.java:749-753`, `:1860-1862`). A hypothetical callback-scoped native read must use a separately designed mechanism, not the ordinary session registry entry.
4. Calling `child_publication_live` inside that same child's callback also is not a callback-safe proof primitive: it calls `ctos_ssh_callback_check_live(child)` (`ssh_native_client.c:2180-2190`), which re-enters `begin_callback`; `in_callback` then marks the slot cancelled/failed (`ssh_callback_adapter.c:456-475`, `:769-774`). The existing callback dispatcher releases `callbacks_lock` before calling Java (`:493-496`), and cancellation can set the slot while Java runs (`:672-678`). A check of `upstream_proof` alone would still not serialize any resulting Java effect with later cancellation.
5. Independent review during implementation found that the PINNED public `ctos_ssh_native_client_verify_host_key` calls the same host-verification routine as the SSH packet engine. A private callback dispatch frame alone therefore cannot prove that the host callback came from the actual packet step; host-scope admission also needs the engine's `in_transport_step`. In contrast, the existing one-shot `ctos_ssh_native_client_selected_credential_bytes` is intentionally callable as a native client accessor after genuine KEX, service-ready and exact selection proof, so its valid native-client dispatch must not be misdescribed as limited to packet processing. `completion_digest` is file-private and called by the real client step after completed KEX and output drain. These are source observations, not JNI or product-chain verification.

### Actual trust persistence path

1. NEW `onNativeHostEvidence` invokes `verifyHost`, which calls `managedProvider.writeBack` (`SshNativeCallbackAdapter.java:482-507`, `:752-765`). CHANGED runs `changedProvider.writeChanged` after signed KEX evidence (`:786-806`). Both carry exact current-hop candidate/review/source bindings, but not a chain proof.
2. `SshManagedKnownHostsProvider.proposalForExact` builds its trust context with a guard for this provider's current active proposal (`:522-547`). Its NEW and CHANGED writers call `adapter.updateTrust` / `updateChangedTrust` with a no-op extra guard (`:573-604`, `:658-683`). `SshVaultAdapter` revalidates the exact current-role/source/proposal binding in a bound guard (`SshVaultAdapter.java:195-245`, `:266-289`); neither call adds ancestor authority.
3. `AppSecrets.updateTrust` checks context and authorization before constructing and validating the encrypted candidate, then serializes final trust work and calls `commitEncoded` (`AppSecrets.java:1961-2003`, `:2163-2208`). The production store writes and syncs bytes, calls `guard.beforeCommit()`, then invokes `AtomicFile.finishWrite` and readback (`VaultStore.java:46-81`). A cancellation/revocation can occur after the final guard returned; a committed effect cannot be described as rolled back. `AppSecrets` reports `VAULT_COMMIT_UNCERTAIN` when an already-persisted trust operation later fails, and provider receipt/invalidation preserves this (`AppSecrets.java:2183-2207`, `SshManagedKnownHostsProvider.java:599-629`, `:679-705`). Existing owner deferral and native confirmation solve current direct-connection receipt windows, not ancestor invalidation.

### Actual secret-copy path and lock order

1. Current-hop native selected callback creates an invocation-specific `EffectGuard`; its `publish` holds callback `stateLock` during publication (`SshNativeCallbackAdapter.java:1015-1091`, `:1133-1153`). `SshCredentialBridge` binds the request, current host/KEX/selection and creates an explicit `SshRoleGuard` (`SshCredentialBridge.java:203-216`, `:228-265`).
2. The decisive vault-backed copy is inside `AppSecrets.releaseSshSecret`: original guard publication → release guard publication → `publicationLock` → vault session → operation → `withSshRole(releaseGuard)` → bounded `receiver.receive(borrowed)` (`AppSecrets.java:2785-2814`); the managed provider receiver clones the credential and wipes it on a subsequent failure (`SshManagedProvider.java:204-237`). Transient password uses `guard.publish` and `role.publishBound` around its clone (`AppSecrets.java:686-700`, `SshTransientPassword.java:62-92`). Transient key uses a role-preserving final guard and bounded bundle copy (`SshTransientKey.java:100-145`). A native post-callback check that wipes its own scratch cannot retroactively make the Java copy atomic with a prior ancestor cancellation.
3. `AppSecrets.boundGuard` returns a plain `EffectGuard` (`AppSecrets.java:24-40`); `withSshRole` dispatches only when the exact passed object implements `SshRoleGuard` (`:42-49`). If composing a new chain guard at a final release seam, preserve the role-specific type and its `withRole` behavior rather than silently wrapping it away. This does not mean every use of `boundGuard` is wrong: its `publish` delegates to its parent; it specifically cannot itself satisfy the `instanceof SshRoleGuard` dispatch.
4. `SshRoleContext.publishBound` holds that exact role and exact public-view monitors and allows only bounded metadata/copy, no core/native calls or waits (`SshRoleContext.java:107-135`). `VaultSession.lock` holds the session monitor when it invokes the lease's synchronized `revoke` (`VaultSession.java:68-74`, `SshPublicFileLease.java:139-145`). Calling a new JNI entry, acquiring session/vault locks, blocking, or doing durable I/O while holding the role/view monitors can invert this order. Cancellation must not wait under these monitors for a potentially long filesystem operation or user review.

### Lifecycle and identity limitations

* `VaultSession.attachRevocation` permits only one active revocation object (`VaultSession.java:77-84`), while `SshNativeSession.beginShell` closes its Java view after the current session is promoted (`SshNativeSession.java:1463-1478`). Independent per-hop leases or reusing direct-session promotion unchanged would invalidate active ancestors or get `BUSY`; a chain owner/shared lease with explicit final retirement needs separate design and tests.
* Current transient password and key `bind` demand `Program.CLIENT` and hop zero (`SshTransientPassword.java:45-57`, `SshTransientKey.java:72-87`). They cannot authorize jumps merely by changing one condition; each hop needs its own exact selected credential identity, host/port/user, original lifetime and proof.
* The existing `SshSessionOwner.Cancellation` only orders current direct trust confirmation with local cancellation (`SshSessionOwner.java:26-38`, `:184-206`); it is not an actual ancestor transport proof. Connector cancellation signals this and performs other cleanups (`SshPasswordConnector.java:327-350`); native callback cancellation, role close/expiry, view revoke, parent KEX/proof failure, relay failure and original deadlines can have independent paths.

### Integration implications and candidate checks

The following is a design inference, not implemented proof: a usable cross-hop guard needs to originate from the actual owning native chain, be bound to the exact child callback invocation plus the chain's live parents/relays/roles/KEX/deadlines, retain those owners until release, and be invalidated or ordered against every relevant cancellation, close, revoke, expiry and proof/relay loss. Its callback entry cannot reacquire `sessions_lock`, call `child_publication_live` recursively, or let arbitrary Java callers forge it. Merely passing a `BooleanSupplier`, snapshot role list, copied digest or before/after `upstream_proof` checks does not satisfy these requirements.

For bounded secret copies, the final guard's short publish permit and cancellation need a common ordering that covers the actual receiver clone, while retaining the existing callback → publication/session/operation → role/view discipline. For trust persistence, a short entry/receipt protocol must account for the gap between `beforeCommit` and `finishWrite`: if cancellation wins before entry, no write; if the commit entered first and cancellation occurs before confirmed native acknowledgement, stop further authority and report/retain uncertain completion. If the desired rule instead forbids physical disk commit after cancellation returns, it additionally needs a carefully designed cancellation drain or equivalent ordering; a short precheck alone cannot prove that rule. No filesystem I/O should be run under role/view monitors.

Meaningful future tests should drive the real managed NEW and CHANGED callback/CAS code with encrypted `AppSecrets` and explicitly identified in-memory `Store`, using deterministic barriers before entry, immediately after final guard, after the store switch and before native confirmation; verify no committed effect in the first case and uncertain/locked vault where the outcome may have committed. Drive saved credential, transient password and transient key receiver copies for immediate parent and deeper-ancestor invalidation, wrong child/role/connection/owner, token replay, original deadline, cancellation before and after permit, no duplicate credential, actual output wiping and cleanup retry. Test the callback-scoped JNI route and absence of reentrant deadlock with an actual JNI harness separately from Java fake bindings, then test the encrypted multi-hop native protocol and device/real-server interoperability separately. No test was executed by this research pass.

## Related specs

* `.trellis/spec/backend/ssh-proxyjump-child.md` — original-lifetime native child/proof and retryable ownership; explicitly excludes callback-internal atomicity.
* `.trellis/spec/backend/ssh-trust-commit-ack.md` — direct NEW/CHANGED durable receipt, owner cancellation and screen-lock ordering.
* `.trellis/spec/backend/ssh-password-connect.md` and `ssh-background-session.md` — proof-bound selected reads and established-session retirement.
* `design/ssh-proxyjump.md` — current implementation, product refusal and outstanding chain lifecycle.

## External references

None consulted. All findings above derive from the project source and local specs.

## Caveats / Not Found

No genuine Java/JNI ancestor-chain capability, product jump-child bridge, guarded cross-hop store/copy publication, retained-chain background owner, or end-to-end real-server proof was found. The source observations are from this worktree on 2026-11-20; concurrent implementers may change native code after this note. A proposed permit model is a constraint to verify, not a completed implementation or a claim that the product gate may be opened.

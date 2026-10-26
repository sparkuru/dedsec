# Research: SSH public-read lifecycle

- Query: Migrate SSH public snapshot reads out of the core monitor while preserving original request authority, synchronous loan ownership, public-view publication and exact own-NEW-CAS continuation.
- Scope: internal; AppSecrets/SshVaultAdapter public reads and managed known_hosts snapshot consumer only. Credential/private-key release, ordinary mutations and trust CAS implementation excluded.
- Date: 2026-10-22

## Findings

### Current call graph and ownership

This is a pre-implementation source snapshot. Line references describe the code inspected before the next public-read increment.

| File | Relevant current behavior |
| --- | --- |
| android/app/src/main/java/im/majo/ctos/AppSecrets.java | Public read admission/effect/lease creation at 1382–1429, shared legacy sessionManifest at 1549–1559, detached TOTP precedent at 712–930. |
| android/app/src/main/java/im/majo/ctos/SshVaultAdapter.java | Native-only fileRequest/useFiles/useFileSnapshot/openPublicFiles facade at 72–87; no MethodChannel exposure. |
| android/app/src/main/java/im/majo/ctos/SshFileSnapshot.java | Immutable metadata and borrowed public bodies, private-body exclusion at 38–54. |
| android/app/src/main/java/im/majo/ctos/SshPublicFileLease.java | Bounded encoding, native creation and wire wipe at 27–36/60–120; active/revoke/retryable native cleanup at 123–165. |
| android/app/src/main/java/im/majo/ctos/VaultOperation.java | Exact operation/epoch/deadline/binding metadata; operation-owned revocation at 74–78 and cancellation at 216–220. |
| android/app/src/main/java/im/majo/ctos/VaultSession.java | Exact epoch attachment/BUSY at 77–89; own-CAS epoch advancement at 92–98. |
| android/app/src/main/java/im/majo/ctos/SshManagedKnownHostsProvider.java | Existing-role snapshotFor at 350–394; exact metadata/base digest receipt at 32–67. |
| android/app/src/main/java/im/majo/ctos/SshRoleContext.java | Immutable original publicView/deadline; requireActive and exact identity checks at 105–119. |
| android/app/src/test/java/im/majo/ctos/VaultTest.java | Public closure/metadata/once-use/cleanup/cancellation coverage at 499–512/527–600/618–630. |
| android/app/src/test/java/im/majo/ctos/SshManagedKnownHostsProviderTest.java | Real synthetic managed trust/CAS; exact attached-loan continuation at 554–600. |
| android/app/src/test/java/im/majo/ctos/TotpReadValidationTest.java | Actual encrypted-source detached lifecycle tests, concurrency/registration/cancel/once-use/raw-store/fence patterns. |

There is no current Java method named sshReadPublic. The exact production names are beginSshFiles, sshFiles, sshFileSnapshot and sshPublicFiles.

beginSshFiles validates root path/connection, calls sessionManifest, rejects a PRIVATE_KEY root and issues SSH_FILES with the authenticated envelope (AppSecrets.java:1382–1389). Its existing operation() helper creates a fresh 120s deadline after expensive admission (1506–1513), so origin time and validator registration are not retained.

sshFileSnapshot consumes the exact SSH_FILES resource/connection capability through verifyOperation, reopens sessionManifest, computes the requested declared-reference closure, creates SshFileSnapshot, then calls protectedEffect and the synchronous receiver (1397–1409). All of these methods currently hold the core monitor. verifyOperation consumes/removes a matching operation at 1528–1529; wrong-purpose/root/connection rejects before consumption (1518–1521). Current cancel-first error priority is explicit. protectedEffect reopens current envelope, then checks epoch/foreground/deadline/cancel (1532–1540).

sessionManifest clones key, parses current ciphertext, decrypts and decodes, then calls validateBusiness with that same retained manifest (1549–1559). validateBusiness is itself synchronized and calls the current validator on the retained manifest (283–288). Removing only synchronized from sessionManifest does not detach callers, isolate validator mutation, bind registration identity or clean all Throwable exits. Keep the helper unchanged for excluded legacy secret/mutation paths; migrate public read callers away from it.

SshFileSnapshot publicFiles aliases the read's Verified manifest bodies; private keys appear only in metadata (SshFileSnapshot.java:38–54). Verified.close wipes key/plaintext/manifest bodies (AppSecrets.java:174–191). Keeping Verified alive through receiver return preserves synchronous borrowed-body validity, including receiver throw. Do not close/wipe those bodies from a concurrent cancel path.

sshPublicFiles currently invokes sshFileSnapshot and, inside its receiver, performs native creation, operation attachment, session attachment and another protectedEffect (1412–1429). Native creation/encoding and cleanup therefore occupy core. This path needs a specialized final attach after detached creation; a generic public delivery claim before Native.create cannot be the final active-view publication fence.

### Consumers have two distinct origin shapes

1. Bootstrap public view: SshVaultAdapter.fileRequest followed by openPublicFiles; a role is issued only after the lease exists. The isolated auth fixture follows exactly this order (SshPasswordAuthPlatformTest.java:244–252). An admission context must therefore work without an existing role and may use the original 120s request ceiling.
2. Existing-role public snapshot: SshManagedKnownHostsProvider.snapshotFor first checks the exact role, then creates a second SSH_FILES request, synchronously selects an exact KNOWN_HOSTS object and retains metadata plus digest (350–394). It does not create or attach another lease. Its originating role already owns its immutable public view. The new read context should capture the original role.deadline (or a smaller already-existing caller deadline), exact role/clock guard and current epoch before admission, rather than acquire a fresh 120s effect allowance.

SshRoleContext.publicView is final, requireBound checks that exact object's active flag, and ownsPublicView is reference equality (SshRoleContext.java:64/79/105–119). A role-bound read should retain this exact role, never issue a replacement role, attach a second native view, or detach the original view. Existing SnapshotReceipt.requireLive already checks role and snapshot deadlines (SshManagedKnownHostsProvider.java:61–67). Shorten the actual snapshot deadline to original role.deadline so queued/detached work cannot outlive the role.

No current AppSecretsBridge MethodChannel path uses SSH_FILES. AppSecretsBridge changes are unnecessary in this increment. A native-only explicit context overload still matters for a future queued connector: construct it before queueing; do not manufacture a fresh context at effect time.

### Smallest coherent migration

Recommend a public-read-specific metadata-only original context and private owned work/state following detached TOTP patterns, without extracting a generic all-typed framework yet:

- Context stores exact core owner, captured epoch/deadline, original bounded guard/publication authority, immutable ValidatorRegistration identity, single admission flag, direct cancelled flag and exact issued operation pointer.
- Provide default immediate native context creation and explicit original epoch/deadline/guard factory plus fileRequest overload. Default deadline is captured before any expensive admission. Context creation must not acquire core/read storage/clone key; it may read session metadata. Context cancellation closes the cleanup-before-outer-assignment race by retaining the exact issued operation under the session fence, then cancels outside session.
- Opaque public-read state attached to the exact operation binds context and contentRevision. Do not accept freely reconstructed scalar read receipts or replay an SSH_FILES operation after its one accepted effect.
- One public-read work slot bounds owned ciphertext/key/plaintext work. Context admission is once-only; a BUSY admission may consume its original admission context consistently with TOTP. Matching effect BUSY should remain nonconsuming; mismatched owner/type/purpose/root/connection must remain nonconsuming. Accepted effect consumes/removes before detached work and stays consumed on any later failure.
- Capture actual ciphertext/key/revision under core; parse/decrypt/decode/select closure and validator work outside core, publication, session and original-phase monitors. Validator receives an independent decoded canonical loan, never the retained read manifest; check registration before/after and reject modified loan. Wipe work/loan/Verified on VaultException, RuntimeException and Error.
- Public reads always require SSH presence to select a valid file; pin the original validator identity for their whole admission/effect/final-publication lifecycle. Missing registration cannot be rescued by attaching one later; same callback reattachment is still a different registration.
- Final raw ciphertext equality belongs under core before the tiny publication fence; raw comparison buffer can wipe there, but owned work/Verified/loan/native cleanup must be outside tiny locks. No store parse/read, native create/destroy, receiver, digest or arbitrary cleanup inside the original phase/publication/session claim.

It is reasonable to copy the focused TOTP capture/open/unchanged/finalize structure now, keeping distinct slot/state/purpose and exact operation validation. A broad refactor of TOTP/bundle/preview helpers would enlarge the review without being required.

### Synchronous public receiver claim

Recommended semantics match main's proposed contract:

1. Construct SshFileSnapshot from the authenticated canonical read outside locks.
2. Under core, recheck raw source equality; then the original guard's publish takes its short phase authority followed by publicationLock and session for only metadata checks and a once delivery flag.
3. Release every lock and call the synchronous public receiver while Verified stays alive.
4. Check original operation/context/registration/revision/role/deadline again before successful return; any failure remains consumed, no receipt/lease is returned by the owning helper. In all paths wipe the loan after callback termination.

Claim-before-cancel rejects; claim-after-success has authorized one transient copy of public bytes. Cancellation cannot retract public bytes a receiver already observed or copied, and must not race-wipe its borrowed arrays mid-use. Late invalidation can make the method fail and downstream authority unusable; it cannot promise preemption or erasure of already copied public content. This policy is for public bodies/metadata only and cannot be applied to credential/private-key/code disclosure.

A custom receiver may run arbitrary code or reenter core because it is outside locks; its retained Java copies are caller-owned public data. Returning from this callback must not itself grant role/host/KEX/trust/secret permissions. For snapshotFor, close a provisionally constructed SnapshotReceipt on any post-callback failure and run result.requireLive before returning it.

For role-bound delivery, an EffectGuard.publish override can synchronize the exact role, run requireActive(clock), and invoke only the metadata publication callback while that role fence is held. role.close is synchronized and performs no native/core/session work (SshRoleContext.java:137), so it can linearize before/after the short claim. Do not hold the role monitor across receiver/digest/cleanup.

### Detached lease create and final atomic attachment

Prepare the canonical snapshot and create the provisional SshPublicFileLease outside core/phase/publication/session. Its native token must remain local, unpublished, and caller-owned until final attachment. Native.create may fail BUSY due to an existing singleton view; do not close or replace that existing view.

After native creation, take core to compare actual ciphertext again, then original guard publication -> publicationLock -> session to recheck metadata and attach:

1. Recheck cancellation/owner/once-state/epoch/foreground/deadline/revision/registration.
2. Attach exact provisional lease to the originating operation FIRST.
3. Attach that same lease to the exact session epoch.
4. Recheck context/operation cancellation and lease.active before acknowledging publication.

Operation attachment internally takes only the operation monitor and rejects cancelled or already-attached (VaultOperation.java:74–78). cancel() marks cancelled and captures that exact revocation under the operation monitor, then invokes revoke outside it (216–220). Thus cancellation before bind rejects attachment; cancellation after bind revokes this precise local lease, including the gap before session attachment. A concurrent session.lock is ordered by the held session monitor. If cancellation wins during attachment, return no active lease; if it wins after the final claim/return, the published lease becomes inactive by ordinary revocation. No promise can make cancellation after a successful return impossible.

Session.attachRevocation currently does not check owned.active. Therefore the new final helper should explicitly reject an inactive provisional lease and recheck after attachment. Failure may leave a session reference to an already inactive exact lease; it must never leave an active unreturned view. A later normal attachment already replaces inactive session revocation. Close local lease outside all tiny locks on every Throwable, preserving cleanup uncertainty and retry behavior. Do not detach/revoke an unrelated view.

A package-private final-publication helper accepting this request's local Revocation plus a metadata-only active test can support meaningful JVM race coverage with fake revocations. Avoid a mutable global native factory solely for tests. Its state must require the exact consumed operation/work and one attach, preventing helper replay from attaching a second view. Native create/wire correctness remain existing platform/native tests; JVM fake attachment does not establish JNI runtime.

### Exact own-NEW-CAS continuation must remain unchanged

Existing contract in ssh-native.md:655–714 and implementation in AppSecrets.java:1308–1315/VaultSession.java:92–98 preserve one exact attached public loan while advancing session epoch after its own approved trust write. Other old operations/previews invalidate, key and original loan deadline stay unchanged; original public-file request cancellation still revokes its exact attached view.

Do not make the new public read context a continuing autonomous revocation source that reacts to its old epoch/revision/registration after successful lease publication. Context/state validates admission, transient loan delivery and final attach; after publication the existing operation-owned and session-owned exact lease revocation govern lifetime. In particular:

- Keep the original operation reference and its attached revocation even after consumed/removal from the map.
- Context.cancel must still cancel that exact operation and revoke its attached lease, including after own-CAS advanced epoch.
- Do not refresh deadline, re-admit old context, issue a replacement lease, mark old capability reusable or accept an old epoch merely to pass own-CAS.
- snapshotFor's separately consumed operation must not become owner of or cancel the role's original view. Its finally adapter.cancel(request) is correct and must remain isolated.
- updateTrust/advanceTrustEpoch and credential/role proof checks need no implementation change for this public-read increment.

## Proposed touched files and tests

Required production files: AppSecrets.java (context/state/work/public read methods/final attach), VaultOperation.java (opaque public-read state), SshVaultAdapter.java (explicit original context overload), SshManagedKnownHostsProvider.java (role-deadline context/guard and final receipt liveness). SshFileSnapshot.java and SshPublicFileLease.java can likely remain unchanged; no JNI/C/Flutter/Bridge change required.

Tests: new SshPublicReadValidationTest.java, small VaultTest.java updates, focused SshManagedKnownHostsProviderTest.java assertions. Existing tests that reattach a new validator after issuing SSH_FILES to trigger a callback lock/cancel must instead arm the same original registered validator; otherwise the migrated code correctly rejects changed registration before executing the intended callback (VaultTest.java:591–599 and 625–627).

Meaningful coverage:

- Latch-block admission and effect validators independently. Prove another thread can obtain core, replace/detach validator, directly cancel context/operation and lock session without waiting; assert expensive callbacks and loan cleanup hold none of core/phase/pub/session locks.
- Actual encrypted source with mixed SSH+TOTP; retained validator arrays wipe after success/checked exception/runtime/Error. Mutation of validator loan rejects and does not change canonical receiver bytes or stored ciphertext.
- Replace/detach/same-instance reattach before/during admission/effect and at final raw-read hook; missing original validator cannot be rescued. Source ciphertext same UUID/generation but changed bytes fails final equality.
- Original deadline/cancel/epoch survives admission-to-effect delay; explicit context creation does not read Store or wait for held core. Foreign owner/root/connection/purpose rejects nonconsuming, matching BUSY nonconsuming, successful or failed accepted effect consumes once; context admission cannot be reused.
- Requested reachable closure only, private metadata but no private bodies, immutable metadata/public list, wipe on callback success/VaultException/runtime/Error, callback outside locks, reentry allowed, claim-before-cancel rejects and claim-after-cancel postcheck rejects without racing borrowed-body cleanup.
- Fake exact revocation final-attach races: cancel before operation bind, after operation bind, after session bind and after successful publication; concurrent lock/foreground change, BUSY existing attachment and post-claim guard throw never leave an active unreturned local lease or revoke another owner's lease.
- snapshotFor original role.deadline/close/view-revoke during either validation and before claim; failed provisional SnapshotReceipt closes; no new attached lease or original-view replacement. Existing real own-CAS test still advances epoch, retains exact original view, and old public request cancellation revokes it.
- Retain existing SshPublicFileLease encoding, VaultSession exact identity/no-resurrection and managed trust regressions. Run focused JVM, fresh full/lint/normal/fixture builds under main ownership; no device runtime is implied.

## Related specs

- .trellis/spec/backend/app-secrets.md — original opaque typed authority, synchronous borrowed public closure, native lease ownership, detached TOTP and bundle precedents.
- .trellis/spec/backend/ssh-native.md:655–714 — exact own-CAS continuation; :2607–2643 — public file view JNI/lease and cancellation contract.
- .trellis/spec/backend/host-bridge.md — host ownership and worker boundaries; there is no public-read MethodChannel change.
- .trellis/spec/backend/quality-guidelines.md — layer-specific checks and current device authorization.
- design/totp-read-lifecycle.md — detached authenticated read/loan/receipt precedent; its code disclosure lock exception is not needed for public bytes.
- design/ssh-validation-lifecycle.md — whole semantic obligation remains pending; no production validator/restore opening.
- design/ssh-bundle-lifecycle.md — original context, raw source comparison and registration precedent.

## External references

None used. This topic is based on current repository source/specs, not an external API/version recommendation. Fixed OpenSSH/native wire/role behavior is unchanged in the proposed scope.

## Caveats / Not Found

- No implementation, build, device, real account/vault/clipboard, commit or git operation performed. Only this research file is written.
- No production full semantic validator registration is introduced or certified. Content-only parser/catalog evidence is not whole-manifest admission; SSH restore remains closed.
- Caller-provided EffectGuard must honor the bounded metadata-only publish contract. An arbitrary guard that invokes its callback and then throws requires provisional operation/lease rollback; do not assume callbacks never throw after publication.
- Cancellation is bounded invalidation and final rejection, not preemption of arbitrary validator/receiver code or erasure of copied public bytes.
- Fresh real Android JNI/role/lease runtime and whole client acceptance remain independent from JVM synthetic attachment tests and compilation.

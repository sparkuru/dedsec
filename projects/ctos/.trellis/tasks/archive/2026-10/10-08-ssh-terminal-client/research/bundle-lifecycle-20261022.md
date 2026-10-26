# Research: SSH bundle capture, detached work and guarded effect

- Query: Safely migrate beginSshBundle/commitSshBundle outside the AppSecrets monitor while retaining original context, independent ownership, once-use consumption and mandatory final-empty validation.
- Scope: internal; AppSecrets, SshBundleProposal, VaultOperation, SshVaultAdapter and focused JVM tests. Read-only investigation, no builds, devices, network or git operations.
- Date: 2026-10-22

## Findings

### Current exact boundaries

Paths below are relative to `android/app/src/main/java/im/majo/ctos/` unless stated otherwise. Line references describe the source read during this investigation, before implementation.

| File and lines | Behavior relevant to migration |
| --- | --- |
| `AppSecrets.java:744-752` | Synchronized admission checks proposal/provider, calls `sessionManifest`, applies proposal, directly validates candidate even if no SSH remains, then issues operation. |
| `AppSecrets.java:755-768` | Synchronized effect checks proposal/provider and consumes operation before current-manifest decode/validation and candidate validation. |
| `AppSecrets.java:771-800` | Shared commitManifest validates candidate again, encodes/encrypts/readbacks, checks guards and snapshot, writes original wrapper, clears operations/preview and calls unlockIfEpoch. Trust continuation is a separate branch. Reusing this unchanged would reintroduce callback/crypto under core. |
| `AppSecrets.java:990-997` | Existing operation factory starts a fresh `clock.now()+120000` only after admission validation. Bundle must use a dedicated issuance path with captured epoch/deadline, leaving other unmigrated purposes explicit. |
| `AppSecrets.java:1000-1014` | Existing verifyOperation validates map object identity, purpose/resource/target/connection, epoch/unlocked/foreground/deadline and exact ciphertext, then sets consumed/removes. It is safe today because callers hold core. |
| `AppSecrets.java:1016-1030` | Protected effect checks snapshot, epoch, foreground, deadline/cancel; cancellation is immediate via volatile operation.cancelled and concurrent map removal, without core. |
| `AppSecrets.java:1033-1043` | sessionManifest copies session key, reads/decrypts/decodes then invokes synchronized validateBusiness. It cannot be used for detached bundle work. Its catches cover VaultException, not arbitrary provider RuntimeException/Error. |
| `AppSecrets.java:50-64,158,172-177` | Immutable validator registration object already exists for preview; every attach creates a new identity, including same-instance reattachment. Bundle currently checks only presence, does not retain this identity. |
| `AppSecrets.java:96-113` | Verified owns key, authenticated plaintext and decoded manifest; close wipes all three. Ownership cannot be shared with another independently closed candidate. |
| `SshBundleProposal.java:64-101,122-142` | Proposal deep-copies changes, canonicalizes paths and binds old/new full-object changes. |
| `SshBundleProposal.java:145-197` | Proposal open/apply/close synchronize on proposal. applyTo clones retained/replacement FileObjects but passes previous.entries directly into new VaultManifest at line 187. Closing candidate therefore wipes previous Entry.secret. |
| `VaultManifest.java:39-49,60-77,256-258` | Entry/FileObject constructors clone their byte arrays; manifest constructor copies lists only; close wipes every body. Deep-copy each Entry, not merely the list. |
| `VaultOperation.java:11-23,51-63` | Capability already has final deadline/epoch/snapshot; consumed is guarded by core, cancelled volatile. No original admission context or validator registration is retained. |
| `VaultOperation.java:200-204` | Cancel marks state under operation monitor and revokes outside it. Do not perform worker-owned plaintext cleanup on cancellation thread. |
| `SshVaultAdapter.java:37-50` | Approval runs before core admission; effect approval runs before core consumption, with final proposal-open/approval checks bound to final guard. Original context must exist before first approval, rather than be created inside begin after approval. |
| `VaultRequestPhase.java:9-28` | Tiny cancellation/commit/publication fence; no file, parser, crypto, body wiping or arbitrary provider work belongs under this mutex. |
| `VaultStore.java:46-81` | AtomicFile writes/flushes/fsyncs, calls guard.beforeCommit, then finishWrite and persisted verification. Failure after finalization is uncertain; preserve original wrapper and this protocol. |

### Proposed API and authority

Use an actual `AppSecrets.BundleContext` established at transaction origin, before adapter approval, queueing or expensive work. Main-session coordination agrees this direction. Suggested package-private surface:

```java
AppSecrets.BundleContext newBundleContext(long deadline, EffectGuard originalGuard);
VaultOperation SshVaultAdapter.bundleRequest(SshBundleProposal proposal,
        AppSecrets.BundleContext context);
void AppSecrets.cancelBundleContext(AppSecrets.BundleContext context);
```

Creation captures unique private owner identity, this core owner, original epoch, absolute deadline, original guard/ordering authority and exact validator registration. Context has an immediate volatile cancellation bit and a single-use admission state; it is not a VaultOperation and grants no store/file/credential capability. Deadline must be positive and bounded according to the existing 120-second policy; explicit earlier caller deadlines are preserved. A direct-call default overload creates one context at its own earliest adapter entry, before approval, with one bounded deadline. It cannot recover an earlier queue/SAF deadline that its caller failed to supply. Real callers with earlier work must use the explicit overload.

Operation retains the same context and deadline after issuance. It must not acquire a new 120-second budget. Context cancellation must remain effective after issuance, including while an effect has already removed the operation from the map. Cancelling by private context identity must not cancel another request that happens to reuse a public string ID. A failed or successful admission cannot reuse that context for another admission. Context reuse and callback reentry fail before recursion/provider invocation. No fake operation should be constructed to supply pre-issuance cancellation/deadline.

Keep public callback compatibility initially: the existing one-argument validator can receive detached loans with before/after context checks. It cannot poll or be forcibly interrupted. A future contextual validator may poll the same original context; do not fabricate a separate per-file deadline. The production semantic provider remains absent.

### Capture → work → guarded issuance

1. **Pre-adapter checks:** check context before/after exact proposal approval, including original deadline and cancellation. Approval remains separate from host-key success. Close/cancel during approval prevents admission.
2. **Admission capture:** briefly hold core, consume context's admission-use permission once, verify current owner/epoch/foreground/unlocked/deadline/registration, capture authenticated encrypted envelope/snapshot and a unique worker-owned session-key clone. Establish bounded in-flight ownership before releasing core. A unique bundle-work slot is a simple safe limit; busy/reentrant attempts cannot replace its owner. If another limit is chosen, every owner still needs independent resources and bounded admission.
3. **Detached work:** outside core/session/request phase, decrypt/decode actual captured bytes into a Verified owner; validate current SSH content with the captured registration using an independent decoded loan. Construct independently owned candidate, encode once to authoritative candidate plaintext, validate an independent decoded candidate loan, compare loan encoding to authoritative bytes and wipe the loan. Candidate validator is mandatory even when candidate has zero SSH objects; do not use hasSsh as a skip condition for this step. Preserve full structural validation/CAS/reference checks in applyTo.
4. **Fences:** check original context before/after decrypt, decode, proposal application, each provider call, encoding and loan-integrity comparison. Recheck original proposal open state and exact registration. A later provider attach cannot rescue missing original provider or revive work from a previous registration.
5. **Issuance:** under core, re-read current ciphertext and check exact snapshot; then repeat context/registration checks because store seams may reenter. Use original publication ordering authority with session metadata to issue/register only the exact capability bound to captured snapshot, epoch, proposal binding, original deadline and context. No parsing, store read, crypto, callback-loan wiping or user/provider approval under phase/session publication fence. A plain check-then-put is not atomic against cancellation/direct session.lock.
6. **Cleanup:** worker finally owns all key/plaintext/candidate/loan cleanup. Every checked exception, RuntimeException and Error releases its work slot and wipes owned material, without masking the primary failure. No durable effect, unlock or unrelated preview cleanup occurs on admission failure.

Proposal can be applied under its own existing short data-copy lock outside global locks. Concurrent close then either precedes apply (reject) or follows a completed independent copy; final original-proposal-open checks still reject closed transactions. Do not hold proposal monitor during validator callbacks. A detached proposal snapshot is optional if implementation needs it, but it must be a private independent owner; do not change original proposal close into a no-op or replace exact approval with an unrelated clone identity.

### Effect consumption and final storage

1. Preserve pre-core adapter approval behavior: approval denial, wrong proposal/purpose, foreign operation or BUSY before accepted capture must not accidentally consume an unrelated valid capability. In particular `SshBundleTest:154-167` retries original a after wrong b and `:201-215` retries after initial adapter approval denial.
2. Under core, match exact operation map object, purpose/resource/target/connection and retained original context. Check original cancellation/deadline/epoch/current snapshot/registration and proposal/provider gates. **Once this matching effect is accepted, set consumed/remove exactly once before releasing core for work.** A concurrent or same-thread reentrant commit sees no available capability; no failure path reinserts it. Keep consumed/map transition protected by core; ConcurrentHashMap alone does not make a get-check-remove sequence once-only.
3. Capture fresh encrypted bytes and an independent key clone under this accepted capture, then perform current/candidate validation, encode/encrypt and verifyReadback outside core. Every provider gets a disposable loan, never retained candidate or Verified.manifest. Do not call legacy sessionManifest/commitManifest from the detached path. A bundle-specific prepared mutation or a shared preparation helper separated from final storage is appropriate; leave trustContinuation and other callers unchanged.
4. Reenter core for final write serialization. Check exact ciphertext and captured registration after the last store read and after quickMaterial retrieval; reentrant store/guard seams can change registration, cancellation, proposal or clock. Use same material id/generation/salt/key, fresh nonce, unchanged optional wrapper and existing commit/readback/uncertainty contract. Candidate preparation does not unlock session.
5. Final bound guard must repeat original-context cancel/deadline/epoch/unlocked/foreground, operation.cancelled, captured registration, original proposal-open and exact bundle approval, including checks **after** caller.beforeCommit. Preserve forwarding of beforeCommit (`AppSecrets.boundGuard:29-39`) and contentRevision increment (`AppSecrets:1112-1123`). No extra validation callback may run here. Exact approval is required to be a bounded metadata/receipt check; parser or system UI approval needs to happen before this stage.
6. The original context's cancellation authority and actual durable effect claim need one documented ordering. Prefer retaining the same transaction authority through admission/effect. If caller effect guard and original context expose distinct phase mutexes, establish a fixed lock order and keep their nested work metadata-only; do not nest an arbitrary guard/provider callback while holding a tiny phase mutex. Cancellation winning before the final claim prevents durable write; interruption after claim uses existing VAULT_COMMIT_UNCERTAIN/lock behavior. Do not claim that an extra metadata issuance claim is already a durable commit claim. Tests must drive this boundary, including original-context cancellation without operation-map membership.
7. On confirmed commit retain operations.clear, preview invalidation and unlockIfEpoch behavior. A newer direct session.lock must not be undone by retained worker key. Keep all worker body wiping outside request-phase/session fences; original session/native loan invalidation remains the existing lifecycle responsibility.

Do not call lazy-expiring session.assertEpoch/unlocked while holding a request-phase fence unless foreground/expiry metadata has already ruled out expiry. `VaultSession:101-104` can invoke lock/revocation and therefore blocking external cleanup. Evaluate heavyweight/lazy checks before the tiny fence; inside it use a metadata-only epoch/foreground/deadline check ordered against session.lock. Preview helper naming does not prove that it is suitable for bundle effect claims.

### Independent candidate ownership repair

`SshBundleProposal.applyTo` must clone all Entry instances using their full metadata plus cloned secret via Entry constructor, as it already clones FileObjects. On failure constructing the manifest, wipe every newly owned Entry.secret and FileObject.content, including RuntimeException/Error after partial allocation. Do not close shallow wrappers around previous.entries. Both close orders must be safe: candidate before previous, previous before candidate. Unchanged entries and unrelated files must survive byte-for-byte in committed ciphertext. This repair is necessary even before allowing concurrent detached work: current candidate close aliases previous secret arrays.

### Errors and existing tests requiring deliberate adjustment

- Preserve VAULT_CONTENT for malformed/changed loan or semantic rejection; VAULT_CANCELLED for explicit cancellation; VAULT_EXPIRED for exact original deadline; VAULT_CHANGED for epoch/snapshot/registration replacement; VAULT_OPERATION for replay/purpose/resource/foreign owner mismatch; VAULT_TRUST_REVIEW for missing exact approval; VAULT_SSH_VALIDATION_REQUIRED for absent required validator. Missing-provider presence check can retain its stable error before comparing registration; reattach still cannot revive old capability.
- Keep original Bridge guard error (e.g. VAULT_TIMED_OUT) when it rejects first; do not remap all late results to content failure. Do not swallow RuntimeException/Error merely to produce a stable business code.
- `SshBundleTest:219-246` currently detaches and reattaches validators then retries the **same** operation. Registration-bound capability intentionally changes that authority: retain the missing-provider assertion; issue a fresh operation under the new rejecting registration to prove the empty final graph itself is passed to validator. A changed registration should separately reject old capability, including same-instance detach/reattach.
- No retry after accepted effect capture, even if decode/provider/encode/store preclaim fails. Initial adapter denial before capture and wrong proposal/purpose remain non-consuming. Tests should distinguish these points.

### Required focused tests

| Area | Observable assertion |
| --- | --- |
| Original context | Create explicit context before simulated queue/approval; advance clock there and in current/candidate validator to exact original deadline. No later stage/operation/write; issued operation.deadline equals original deadline. Admission callback cannot receive a fresh budget. |
| Pre-issuance cancel | Latch-block approval/current/candidate validator; cancel explicit context from another thread before operation exists. Cancellation returns promptly, work cleans after callback returns, no operation issued. Reusing same/foreign context rejects. |
| Outside locks | Assert Thread.holdsLock(core/session/phase) false in validators and crypto test seams; blocked callback permits another thread to acquire core, reattach provider and call session.lock. Same-thread reentry alone is insufficient evidence. |
| Once-only | Block effect validator after accepted consumption; simultaneous/reentrant commit same operation rejects without another callback. After VaultException/RuntimeException/Error/IO failure, retry rejects; no map resurrection. Denied adapter approval/wrong proposal still allow correct original operation. |
| Registration | Replace/revoke/reattach same provider during either admission/effect callback, after admission and in final store-read/quickMaterial/beforeCommit seams. No stale issuance/write; old operation never revives. |
| Candidate ownership | Exercise both previous/candidate close orders and partial applyTo failure. Retained unrelated TOTP/password/passphrase arrays remain independent; all owned candidate/loan arrays wiped on all outcomes. |
| Loan integrity | Provider retains or mutates current/candidate Entry.secret or FileObject.content, closes loan, throws checked/runtime/Error. Canonical candidate/source remain intact; modification rejects; loans wiped; slot freed. |
| Final empty graph | Use TOTP plus one SSH file; candidate callback must observe no SSH and may reject it in admission and effect. Missing provider still rejects deletion. Use fresh operation per registration. |
| Snapshot/epoch | Mutate store or lock/background during work and final read. No overwrite or session reopen. Final ciphertext read that advances clock/cancels/replaces registration must be followed by fresh metadata checks. |
| Claim ordering | Original-context or operation cancellation before durable claim leaves old ciphertext/wrapper; after claim preserves uncertainty/locked-session behavior. No parser, wipe or I/O in tiny claim. Same original cancellation works after op map removal. |
| Compatibility | Existing unrelated object preservation, nonce/material generation/wrapper, graph limits/CAS/cycles/depth/passphrase no-cascade, default-deny approval and stale TOTP invalidation continue to pass. |

## Files found

- `design/ssh-validation-lifecycle.md` — preview-only migrated contract, explicit deferred bundle/typed paths and final-empty obligation.
- `.trellis/tasks/10-08-ssh-terminal-client/research/validation-boundary-20261021.md` — prior lifecycle and shallow Entry-sharing investigation; line numbers there precede preview migration.
- `android/app/src/main/java/im/majo/ctos/AppSecrets.java` — bundle/core/validator registration/Verified/final store guard implementation.
- `android/app/src/main/java/im/majo/ctos/SshBundleProposal.java` — exact proposal/CAS and candidate ownership.
- `android/app/src/main/java/im/majo/ctos/VaultOperation.java` — once-use capability, original snapshot/epoch/deadline and immediate cancellation.
- `android/app/src/main/java/im/majo/ctos/SshVaultAdapter.java` — admission/effect/final exact approval gates; bundle has no production caller found beyond adapter/tests.
- `android/app/src/main/java/im/majo/ctos/VaultManifest.java` — constructors, structural validation and body cleanup ownership.
- `android/app/src/main/java/im/majo/ctos/VaultSession.java` — session key cloning, direct lock/epoch, lazy expiry and guarded unlock.
- `android/app/src/main/java/im/majo/ctos/VaultRequestPhase.java` and `VaultStore.java` — tiny cancellation/commit claim and AtomicFile boundary.
- `android/app/src/test/java/im/majo/ctos/SshBundleTest.java:75-108,110-248,250-415` — existing synthetic transaction, ownership, approval, graph, replay and uncertainty regressions.
- `android/app/src/test/java/im/majo/ctos/VaultOperationTest.java:21-42` — operation cancellation revokes exact owned lease once, and no replacement ownership.

## Related specs

- `.trellis/spec/backend/app-secrets.md:89-121,194-208,444-449,728-817` — native capability, bundle proposal, default-deny/final-empty, synthetic tests and preview migration boundary.
- `.trellis/spec/backend/host-bridge.md` — worker-owned blocking work and host-only authority.
- `.trellis/spec/backend/quality-guidelines.md` — layer-appropriate checks and current device authority.
- `.trellis/workflow.md` — persist research and freeze lifecycle before implementation.

## External references / versions

No external lookup or dependency/version change is needed for this internal lifecycle investigation. No claim about current external parser behavior is made.

## Caveats / Not Found

- This is a proposed migration and test matrix, not implemented behavior or passed verification. Only this research file was written.
- Complete CONFIG/identity/credential/trust semantic validation and production registration remain pending. Lifecycle completion cannot authorize production SSH import/restore or imply OpenSSH semantic proof.
- Typed reads/writes, trust CAS and sessionManifest remain separate migrations; do not accidentally broaden the patch through shared commitManifest behavior.
- Arbitrary synchronous parser/validator execution is not preemptible. Cancel/lock must be responsive and late result rejected; worker-owned plaintext is wiped only after callback unwinds.
- Two independent caller/origin cancellation phase authorities require explicit fixed ordering and uncertainty semantics before code. Context deadline/cancel fields alone do not prove atomic capability issuance or durable effect ordering.

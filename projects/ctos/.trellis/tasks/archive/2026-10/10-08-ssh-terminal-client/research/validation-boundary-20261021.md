# Research: supplied-manifest validation deadline, cancellation and core monitor boundary

- Query: Find the smallest coherent next increment for AppSecrets supplied-manifest semantic validation; assess a preview-only migration versus a complete outside-monitor redesign.
- Scope: internal; source investigation only, no implementation, builds, device, real account or git operations.
- Date: 2026-10-21

## Findings

### Recommendation and scope

Freeze the lifecycle contract first, as the current handoff requires. A complete semantic validator, complete whole-core outside-monitor migration, and SSH restore admission are not safe small changes. Their input/context/identity/trust semantics remain incomplete.

After the freeze, a **preview-only capture / validate / review / publish migration is a coherent first code increment**. It can remove expensive semantic/reviewer callbacks from the AppSecrets core monitor for this path without rewriting every typed operation or changing VaultSession/VaultOperation. It must expressly leave `sessionManifest`, typed reads/writes and bundle validation on their existing legacy path. This is useful lifecycle work with synthetic providers, not merely an unused context helper and not permission to attach a production validator.

A document-only increment is the safest immediate stopping point if the implementation cannot cover all of preview capture, provider-registration invalidation, owned callback loans, original request context, publication races and failure cleanup. It is not necessary to stop at documents solely because the eventual all-operation migration is large. Parent session must freeze the concrete preview contract and acceptance matrix before dispatching implementation.

### Current exact synchronous entry points

All paths below use `android/app/src/main/java/im/majo/ctos/AppSecrets.java`.

| Entry | Synchronous behavior and evidence |
| --- | --- |
| `attachSshValidator` | Line 148 stores a mutable callback with no revision or preview invalidation. Its interface at line 38 receives only a mutable `VaultManifest`, no original deadline/cancel/guard. |
| `validateBusiness` | Lines 170-175 acquire the core monitor and invoke the callback on any SSH content. TOTP-only skips it. |
| `preview` | Lines 266-280 authenticate source and target, then call synchronized `validateBusiness` twice. Lines 282-311 hold the core monitor across restore reviewer callback, canonical decoding and review binding, guards and publication. |
| `sessionManifest` | Lines 900-910 clone the session key, read/decrypt/decode the current vault, then call `validateBusiness`. Almost all callers are themselves synchronized, so removing only the helper's `synchronized` cannot free the monitor. |
| TOTP operations | `beginTotp` 430, `beginTotpList` 437, list 440, rename admission 453 / effect 457, add 486, save admission 494 / effect 501, delete admission 497 / effect 509, code 670. Mixed SSH vaults trigger whole-manifest validation even for TOTP. |
| SSH credential mutations | `beginSshSave` 512 / `saveSsh` 516, delete admission 525 / effect 531 call `beginMutation` 535 or `mutateEntry` 541 and ultimately `sessionManifest`. |
| Trust | `beginTrustUpdate` 559 and `updateTrust` 572 validate current/candidate manifests while synchronized; the latter has exact role/attached-loan continuation that cannot be replaced by generic unlock publication. |
| Bundles | `beginSshBundle` 611 calls current-manifest validation, applies proposal and directly validates candidate at 616; only then creates the operation at 617. `commitSshBundle` 622 validates candidate at 629, then `commitManifest` validates again at 645. Deleting the final SSH object deliberately still calls the validator at 616/629. |
| SSH releases | Credential admission/effect 699/703/714/719; file admission/effect/public lease 733/743/748/763; private-key admission/effect 791/802; key-bundle admission/effect 815/834. Each synchronized call eventually validates current manifest before guarded release. |

Unlock (210-223) and quick unlock (411-420) authenticate/decode without semantic validation. Preserve that distinction; no new automatic validation/restore guarantee should be inferred from unlock.

The current `lock` at 423 and `cancelRequest` at 938 wait for the core monitor. However, the production Bridge's immediate invalidation at `AppSecretsBridge.java:910-914` calls `vault.session.lock()` directly, then queues core cleanup. It does **not** wait for AppSecrets. Do not overstate the current bug as “all manual/screen locking is blocked by the core monitor.” A validator holding the core monitor still retains its own decoded plaintext until return, and core cleanup can wait behind it.

`cancelOperation` at 894-898 avoids the core monitor and sets `VaultOperation.cancelled`. `VaultOperation.java:200-204` releases its own monitor before revoking the owned lease. But an admission callback has no VaultOperation yet, so this does not supply admission cancellation.

### Request and lock evidence

- `AppSecretsBridge.java:35` has a single worker per Bridge, not a process-wide lock. Multiple Bridges/consumers share one core through AppSecretsOwner.
- Bridge `Request.deadline` is created at line 82 as elapsedRealtime +120000. `guard` at 427-431 checks active identity, cancellation, original deadline, epoch and foreground. `requestGuard` at 516-522 wraps this opaque guard; the core currently cannot extract the original deadline.
- Bridge cancellation at 181-187 marks `Request.phase` immediately, queues `vault.cancelRequest`, then can complete the user request before the worker returns. A queued `cancelRequest` cannot interrupt work already occupying that same worker.
- `VaultRequestPhase.java:3-23` is the tiny cancellation/effect-claim mutex; parser, KDF, file work and wiping must not run while it is held. Current commit ordering includes core -> request phase -> session (AppSecrets commit / Bridge requestGuard / Bridge guard). A new phase -> core nesting would conflict with that order.
- `VaultSession.java:20-21,41-50,53-75` serializes epoch/key/foreground/lock using the session monitor, independent of AppSecrets. An epoch check followed by unprotected preview assignment is not atomic with direct `session.lock()`.
- `preview` currently starts its distinct retained-preview lifetime at publication: AppSecrets line 308 uses clock.now()+120000. Do not silently replace that lifetime with the management Request deadline. Validation authority and retained-preview/commit authority are separate clocks.
- `SshManifestInspector.java:151-188` already has detached supplied-input ownership, a single original deadline, pre/post callback cancellation and wiping. Its deadline accepts at most 60 seconds, whereas Bridge requests last 120 seconds; future integration must define a min(original request deadline, one captured phase ceiling), never reset the ceiling for each file. It remains content-only, not the semantic callback.

### Minimum preview contract to freeze

1. **Capture before expensive work.** On entry capture the original management request deadline/cancel authority, epoch, current target ciphertext snapshot and one immutable validator registration. If either authenticated side contains SSH, require the same captured validator and restore registration. A provider attached later cannot rescue an invocation that captured no provider. Capture can happen before authentication or at a precisely documented post-authentication point, but must not permit source and target to use different registrations.
2. **Deadline semantics.** Deadline originates before authentication/validation; check before/after each KDF/callback/decode stage and before publication. Preserve Bridge `VAULT_TIMED_OUT` and cancellation mapping. A direct native/core call may use an explicit original context with a defined expiry code, not a post-validation +120s deadline fabricated by a compatibility wrapper. Existing retained-preview +120s starts at successful publication unless the approved contract changes.
3. **Cancellation semantics.** Cancellation is one-way and visible without taking the core monitor or queuing onto the blocked worker. Prevent further stages, preview publication and later commit. Cooperative callbacks may poll context. Arbitrary callback/third-party KDF execution cannot be forcibly stopped; return/wipe may wait for its bounded call to finish. Do not describe this as immediate crypto interruption. Decide whether direct `cancelRequest(id)` must revoke an in-flight context as well as a published preview; if yes, a bounded in-flight registration is required rather than only the present published-preview field.
4. **Captured registration.** Use an immutable identity/revision wrapper even if the callback itself is unchanged. Every attach, identical reattach and detach advances identity/revision. Invalidate published previews depending on that registration; recheck it after callbacks, before publication and at import effect/final store guards. Keep the same revision in ImportPreview. Reviewer registration already has this model (AppSecrets 150-159,359-373); validator currently does not.
5. **Owned callback loans.** Never give validators the retained Verified.manifest used as an authenticated authority. Decode disposable source/target views from authenticated owned plaintext, separately for callback loans; wipe on success, VaultException, RuntimeException and Error. Restore reviewer already gets disposable views at AppSecrets 294-296 and canonical independent views at 298-301. Preserve canonical full coverage binding. Callback mutation cannot change the eventual imported plaintext or certify a different authenticated source. For the existing mutable-manifest/void-return validator, separately reject changed loan content before accepting success (canonical internal digest comparison or equivalent); a disposable clone alone prevents corruption but does not prove the callback validated the canonical bytes. Never expose these credential-bearing comparison digests publicly.
6. **Work outside monitors.** Authentication KDF, semantic callback, restore reviewer, canonical manifest decoding, full coverage hashing/HMAC binding, and callback-loan wiping happen outside core, session and request-phase monitors. A reviewer may block while core registration, direct lock and cancellation remain responsive. Its own local review object's monitor is distinct from these global locks.
7. **Publication.** Construct the new ImportPreview and review before tiny locks. Under core recheck target snapshot and current registrations, then atomically order request cancellation and session epoch/foreground against assignment. A safe candidate ordering consistent with existing commit is core -> request phase -> session. Under the tiny phase/session portion do only permission/epoch/foreground/deadline/registration identity checks and assignment; do no store read, parser, digest or old-preview.close/wiping. Keep core across final store snapshot check and assignment, since official store mutations use core. Wipe old/rejected preview outside the tiny phase/session locks. For direct core callers, freeze the equivalent publication-ordering authority; a plain arbitrary EffectGuard check is not an atomic cancellation claim.
8. **Failure.** Before publication, no new preview, ciphertext write, session unlock, role, file view or socket is created. Rejected registration/snapshot/epoch reports VAULT_CHANGED; cancellation/timeout retain their existing mappings; missing validator/reviewer retains VAULT_SSH_VALIDATION_REQUIRED / VAULT_SSH_REVIEW_REQUIRED. Do not convert failures into empty successful review or admissionComplete. Failed new work should not erase an unrelated prior preview unless existing explicit invalidation/registration/lock semantics require it.
9. **Commit binding.** ImportPreview keeps validator registration in addition to restore provider, original source envelope/plaintext digests, target binding, epoch and distinct preview deadline. `checkRestoreApproval`/final storage guard must reject validator replacement/revocation just as they reject restore-provider replacement. A later callback attach must never silently upgrade old preview authority.
10. **UI delivery.** Core publication is not final UI publication. `AppSecretsBridge.java:766-796` guards TOTP publication specially but import preview goes through finishUnchecked with no fresh guard. A queued success must be checked for active request/epoch/deadline and exact still-current preview before delivery; invalidated success must discard only its own preview and avoid exposing closed/stale projection. Do not introduce store I/O on the main thread to check this. A summary may be prebuilt on the worker and paired with a tiny publication identity/revision check, or use an equivalent reviewed scheme.

A one-argument fixture validator can still be called outside core with context checks before/after; that proves responsiveness and rejects late publication, but cannot poll within the callback. A contextual callback API is preferable for future real parsers. If introduced now, define explicit compatibility for legacy typed/bundle callers, and do not claim those callbacks have migrated. Avoid an unused parallel “preview validator” with different semantic policy, or fabricating plausible contexts for unconverted admissions. Production registration remains absent either way.

### Why all-path migration is larger

The outside-monitor primitive is straightforward; integration is a cross-module concurrency redesign, not removing a keyword. Existing admission methods return an operation only after validation and assign its deadline at AppSecrets 857-864. Effect methods consume/remove it before work at 867-880. Dropping the core monitor therefore requires owned captures, preserving once-use consumption on failures, immutable registrations, final ciphertext/epoch/request rechecks, resource/target/connection bindings and candidate ownership across all these phases. `SshVaultAdapter.bundleRequest` at 37-40 has no pre-admission context parameter. Its complete-graph approval also runs before core admission. TOTP adapters and release/publication paths have separate guards. Trust CAS intentionally retains only an exact attached loan after its own epoch transition (AppSecrets 572-607,661-665), so generic unlock-after-commit is not interchangeable.

`VaultManifest.java:74-77` copies only the lists, while Entry/FileObject bodies are mutable arrays; `close` at 256-258 wipes them. `SshBundleProposal.java:164-190` clones file objects but shares existing entries in the resulting manifest. A detached candidate owner must not shallow-copy and close these arrays while another phase still relies on them. This reinforces the case for preview first, where already authenticated plaintext can be independently decoded.

Thus full migration spans core, Bridge, adapters/consumers, registrations, operation admission and tests. It need not all happen in one patch. Preview-only is safe if its boundary is complete and unconverted callers remain explicit; production attachment must wait for the full chosen semantic and lifecycle contracts.

### Suggested acceptance tests

Use actual encrypted synthetic source/target snapshots and injected monotonic clock; keep synthetic semantic/review providers clearly labelled. Tests below are proposed, not executed by this research.

| Area | Required observable result |
| --- | --- |
| Monitor ownership | Latch-block source validator, target validator and reviewer separately. On another thread, acquire core/replace registration/cancelRequest and perform direct session.lock without waiting for callback release. Assert Thread.holdsLock(core/session/phase) is false inside each expensive callback; thread/latch tests supplement this, since same-thread monitor reentry alone proves nothing. |
| Original deadline | Advance clock to exactly original request deadline inside source/target validation/reviewer; no next stage or preview publishes. A callback does not receive a new full budget after source KDF. Preserve separate retained-preview +120s lifetime on an otherwise successful invocation. |
| Cancellation | Cancel before start, during each callback and in the final publication barrier. No success preview or commit; caller-owned and retained temporary buffers wiped after callback unwinds. Existing one-arg callback cannot be promised interrupted. If direct in-flight cancelRequest support is claimed, test that without a Bridge guard too. |
| Session race | Lock/background during each callback and immediately before final assignment; no stale preview accepted. Locked-start import is still allowed when epoch/foreground remain valid: management fresh authentication must not require an already-unlocked session. |
| Registration | Replace/detach/re-attach same validator and reviewer while a callback is blocked; source and target cannot use mixed revisions. Replacing validator after publication invalidates old preview at commit and final store hook. Replacement after target verification but before publication rejects. |
| Target snapshot | Another allowed core mutation/create during callback causes VAULT_CHANGED, including target originally absent. Optional shortcut bytes and old ciphertext unchanged on rejection. |
| Loan integrity | Retain and mutate source/target callback arrays; original authenticated source plaintext remains unchanged, canonical reviewer mismatch rejects, callback arrays wiped on success/checked failure/RuntimeException/Error. No caller shallow-copy close. |
| Coverage and consent | Existing partial/duplicate/wrong object coverage tests, review HMAC credential redaction, exact consent binding and source immutable digest tests remain valid. |
| Publication and UI | Cancel/lock/expire/replace provider between worker preview creation and queued main-thread result. Deliver no stale success, remove only its owned preview, preserve another request's preview. No I/O or wipe runs under tiny publication mutex. |
| Failure preservation | A failed new preview does not delete an unrelated prior live preview unless lock/provider invalidation legitimately does so. No durable mutation or session unlock. Concurrent requests do not erase each other's cleanup target. |
| Legacy boundaries | Existing TOTP, bundle and trust-release fixture regressions remain unchanged. Final SSH deletion still requires a validator. Production has no attachSshValidator call; SSH restore gate stays closed; SshManifestInspector report remains contentOnly=true/admissionComplete=false. |
| Claim ordering | Retain VaultRequestPhase commit-before/after-cancel and VAULT_COMMIT_UNCERTAIN regressions. Validation/preview must not claim a durable commit phase simply to get a mutex. |

Existing regression evidence to retain: `SshRestoreTest.java:100-145` missing hooks/fresh target auth/loan wiping, 191-217 immutable projection/reviewer mutation, 231-279 provider replacement/final guard/lock/deadline/snapshot, 335-358 source binding and credential token isolation. `SshBundleTest.java:312-415` exercises replay/cancel/epoch/deadline/commit uncertainty. `VaultOperationTest.java:21-42` covers owned revocation; `VaultSessionTest.java` covers direct lock, original background expiry and own trust continuation. These are orchestration fixtures, not actual OpenSSH semantic proof.

## Files found

- `design/handoff-ssh-terminal-client.md:5-30` — current authority, next action explicitly freezes lock/cancel/failure/restore contract first.
- `.trellis/tasks/10-08-ssh-terminal-client/research/manifest-validator-20261019.md` — prior supplied-manifest call graph, ownership and semantic gaps.
- `android/app/src/main/java/im/majo/ctos/AppSecrets.java` — semantic callback, all capture/effect/storage paths, mutable registration and preview ownership.
- `android/app/src/main/java/im/majo/ctos/AppSecretsBridge.java` — original request deadline, cancellation, final result delivery, fast session invalidation.
- `android/app/src/main/java/im/majo/ctos/VaultSession.java` — synchronized epoch/foreground/key/loan lifecycle.
- `android/app/src/main/java/im/majo/ctos/VaultOperation.java` — post-admission authority, cancellation and exact role/trust binding.
- `android/app/src/main/java/im/majo/ctos/VaultRequestPhase.java` — tiny cancellation/effect claim ordering.
- `android/app/src/main/java/im/majo/ctos/SshManifestInspector.java` — detached partial parser lifecycle; cannot serve as validator.
- `android/app/src/main/java/im/majo/ctos/SshRestoreReview.java:168-196` — canonical coverage verification and public credential HMAC projection.
- `android/app/src/main/java/im/majo/ctos/VaultManifest.java` and `SshBundleProposal.java` — mutable body ownership and shallow candidate-entry sharing.
- `android/app/src/main/java/im/majo/ctos/SshVaultAdapter.java` — adapter admission without pre-operation context and approval chain.
- `android/app/src/test/java/im/majo/ctos/SshRestoreTest.java`, `SshBundleTest.java`, `VaultOperationTest.java`, `VaultSessionTest.java` — existing synthetic regression contracts.

## External references / versions

No external lookup or version change is needed for this internal lifecycle investigation. No claim about current external software behavior is made. Native detached parsing policy remains the locally pinned dependency and the existing inspection contract.

## Related specs

- `.trellis/workflow.md` — contract-first incremental workflow; research-only role output.
- `.trellis/spec/backend/app-secrets.md:3-17,112-121,171-231,245-250` — detached partial-only status, restore parser before final request mutex, original deadlines, once-use/effect guards, default-deny production semantic gates.
- `.trellis/spec/backend/host-bridge.md` — worker-owned blocking work, main-thread MethodChannel completion, host-only authority.
- `.trellis/spec/backend/quality-guidelines.md` — layer-specific local validation; device needs independent authority.
- `.trellis/spec/guides/cross-layer-thinking-guide.md` — explicitly map producer/context/consumer boundaries.
- `design/app-authentication.md` — fresh source/target password authentication, whole backup/trust review, cancellation/lock and atomic commit product semantics.
- `design/ssh-manifest-inspection.md` — content-only report, original deadline and nonpreemptible bounded parser limitation.

## Caveats / Not Found

- This document is a recommendation and proposed acceptance matrix, not an implemented API, frozen main-session design or passed test report.
- No code, build, device, account or git action was performed. Only this research file was written.
- A complete semantic policy for CONFIG context/Include/reference/credential target/private-public association/certificate/trust remains absent. Lifecycle work does not settle or weaken it.
- No production validator registration was found in the inspected current code; no admission path is opened by this research.
- Exact future context API compatibility and direct in-flight cancelRequest policy must be settled before code. They cannot be hidden behind a fake operation or context-free “responsive cancellation” claim.
- RuntimeException/Error cleanup and final queued-UI publication deserve explicit negative tests; present success-path tests do not prove those races.

# App secrets host contract

The SSH public file loan now has a native-only SshPublicFileLease: no private
body in its explicit bounded wire, opaque singleton native ownership, and
revocation bound to both the exact VaultSession epoch and originating
VaultOperation. Cancel after publication revokes that view; an old operation
cannot attach a replacement view. Revocation callbacks must not re-enter
AppSecrets or acquire session locks while holding lease/operation locks.
The managed `KNOWN_HOSTS` provider/CAS writer is implemented, but it accepts
only a caller-supplied exact replacement body. The native-facing hostfile
issuer/candidate handoff, real OpenSSH transport/caller and restore validation
remain absent; the bounded role/KEX/typed-byte handoff is specified separately
and is not itself a production caller. See [SSH native contract](ssh-native.md)
and dated design evidence.

## 1. Scope / Trigger

Use for `AppSecrets*`, `AppAuthentication`, `Vault*`, TOTP/SSH adapters and their
MethodChannel consumers. Product authority is [design/app-authentication.md](../../../design/app-authentication.md).
This describes the 2026-10-10 implementation, not completed device acceptance.
No exported cross-App API, Root access or plaintext disk staging is introduced.

## 2. Signatures

`MainActivity` forwards `secrets.*` on `ctos/native` to `AppSecretsBridge`.
`AppSecretsOwner.acquire(applicationContext)` owns one process-wide core; an
Activity owns only its UI/auth bridge. Service consumers must reuse that core.
The package-private `AppSecretsBridge(Activity, AppSecrets, SharedPreferences,
VaultExportJournal)` overload accepts scoped dependencies for native integration
checks. It runs the same lifecycle/request implementation. Only the production
single-argument constructor acquires the production owner/settings/journal and
queues startup `cleanupDisabledKeys()`; this seam is not an exported API or an
alternative vault format.

| Method | Arguments |
| --- | --- |
| `secrets.status`, `secrets.lock` | none |
| `secrets.settings` | integer `backgroundTimeoutSeconds` |
| `secrets.create` | `requestId`, `password`, `confirmation` |
| `secrets.unlock` | `requestId`, `password` |
| `secrets.changePassword` | `requestId`, `currentPassword`, `newPassword`, `confirmation` |
| `secrets.quickUnlock` | `requestId`, `action=enable/unlock/disable`; password for enable/disable |
| `secrets.exportPreview` | `requestId`, `password`, bounded `fileName`; owned SAF tree-picker flow |
| `secrets.exportCommit` | new `requestId`, opaque `exportId`, Boolean `overwrite` |
| `secrets.exportCancel` | new `requestId`, `exportId`; returns terminal cleanup receipt |
| `secrets.exportRecover` | new `requestId`, `recoveryId`; explicit user recovery and same-tree reauthorization |
| `secrets.importPreview` | `requestId`, `sourcePassword`, existing target's `targetPassword`; owned SAF flow |
| `secrets.importCommit` | new `requestId`, `previewId`, `reviewBinding`, Boolean `confirmTrust` |
| `secrets.importCancel` | `previewId` |
| `secrets.cancel` | `requestId` |
| `secrets.protect` | `action=acquire`, or `action=release` plus opaque `owner` |

Native-only `VaultOperation` binds `Consumer`, `Purpose`, resource, actual target,
connection ID, vault UUID/material generation, session epoch, monotonic deadline
and ciphertext snapshot digest. It is once-used, cannot be constructed through
the channel, and is checked again at the actual release/store boundary.

`SshVaultAdapter.useFileSnapshot(operation, rootPath, connectionId, receiver)`
loans the requested file closure synchronously. `SshFileSnapshot.metadata`
contains immutable type/ID/path/identity/length/references for every selected
object, including private-key metadata. `publicFiles` contains only CONFIG,
PUBLIC_KEY, CERTIFICATE and KNOWN_HOSTS bodies. Both lists follow the same
snapshot/epoch/deadline/connection/once-use checks as useFiles; all borrowed
Java content is wiped on callback return or throw. The receiver copies into
its request-owned native view and revokes it on cancellation/lock. Metadata and
the ciphertext binding are descriptions, never private-key release authority.
Private-key bodies still require privateKeyRequest/usePrivateKey and the final
actual-hop host guard. The bounded native callback seam may use only the
proof-bound `SshCredentialBridge` overloads; it must recheck the requested kind,
copy the transient bytes into caller-owned memory and wipe both managed and
native buffers on failure. Do not expose this snapshot through MethodChannel or
treat these JVM-only boundary checks as registration of the production SSH
config/known-hosts provider.

SSH file graph writes use `SshVaultAdapter.bundleRequest(SshBundleProposal)` and
`commitBundle(VaultOperation, SshBundleProposal, EffectGuard)`, with
`Purpose.SSH_BUNDLE_WRITE`. Proposal `Change.add/replace/delete` records full
old/new object bindings (type/ID/path/identity/references/content digest). The
proposal owns bounded deep copies, canonical path-sorted whole-change digest,
nonsecret `summary()` and only typed `knownHostsContent(path)` for the native
trust reviewer. No generic private-key getter. Owners close inputs/proposals and
wipe returned trust clones. Closed proposals cannot request or commit.
For the managed known_hosts CAS path, receipt validation occurs while the
proposal is still open; `writeBack` closes it in `finally` on both success and
rejection, and no `COMMITTED` receipt is exposed before the guarded AppSecrets
CAS succeeds.

Native restore hooks are `AppSecrets.attachSshRestoreReviewer(id, version,
reviewer, approval)`, `SshRestoreReviewer.review(source, authenticatedTarget)`
and `ExactRestoreApproval.requireApproved(review, RestoreConsent)`.
The core assigns a fresh registration revision, owns consent construction and
wipes temporary authenticated manifest loans after review. Provider parsers
run before the final request mutex; approval must be a bounded metadata check,
with no parser/disk/system UI work. `importCommit(previewId, reviewBinding,
confirmTrust, EffectGuard)` rechecks the same binding at storage boundaries.

## 3. Contracts

- Status: `state`, `exists`, `generation`, `quickUnlockEnabled`,
  `quickUnlockAvailable`, `backgroundTimeoutSeconds`,
  `sshBackupImportAvailable`, bounded metadata-only `exportRecovery`, optional
  stable-code-only `exportRecoveryError`. Secrets/keys
  are absent. State is missing/locked/unlocking/unlocked/locking/failed.
- Import preview: opaque `previewId`, counts `totpEntries`, `sshEntries`, `files`,
  `trustRecords`, Boolean `overwrite`, string list `trustSources`,
  `reviewBinding` and nullable `restoreReview`. Keep the same fully authenticated
  immutable source and target snapshot through commit. Pure TOTP source and
  target use empty binding/null review; any SSH on either side requires review.
- Export preview: opaque `exportId`, `fileName`, `locationLabel`, Boolean
  `collision`/`overwriteAvailable`, optional existing name/size/modified metadata.
  Receipt: `status=exported/cancelled/failed/recovery_required`, `phase`,
  `fileName`, `locationLabel`, optional `errorCode`, bounded `remaining` rows
  (`role`, `name`, `location`, `state=present/unknown`). A completed export may
  still require cleanup; status alone must not hide remaining rows.
- Bridge management request IDs: single-use `[A-Za-z0-9_-]{1,128}`, one active UI operation,
  maximum 4096 admitted IDs per bridge, 120-second timeout. `secrets.totpList`
  requires the permanently read-only `read_` prefix with a separate rolling128
  replay set; every management method rejects that prefix even after eviction.
  Old evicted read IDs only admit a fresh authenticated read, never a write. Native capabilities
  are separate; a UI ID or Boolean trust consent never authenticates a password.
- Envelope v1: `CTOSVLT1`, 88-byte authenticated header; Argon2id v0x13,
  65536 KiB/3 iterations/4 lanes/32-byte key; AES-256-GCM, 16-byte tag,
  random 16-byte salt and fresh 12-byte nonce. Reject unsupported/oversized
  headers before KDF. Plaintext bound 12 MiB; authenticate before parsing.
- Manifest bounds: 1024 entries, 128 files, 1 MiB/file, 8 MiB total files;
  root-relative unique logical paths, authenticated object IDs/types/digests,
  complete references, no cycles, maximum reference depth 8. No source FD,
  absolute path or SAF grant is adopted as a usable target resource.
- `binding()` identifies a whole ciphertext snapshot. `materialBinding()` binds
  key epoch/header/salt while excluding fresh content nonce; ordinary typed
  edits preserve optional wrapper binding but invalidate old snapshot operations.
  Rekey/import changes material generation and disables old wrappers.
- For authentication-bound GCM, `AppAuthentication.cipher(Request)` only creates
  and initializes the Cipher. Submit `updateAAD(envelope.materialBinding())` in
  `perform(Request)`, after system authentication succeeds and the request,
  epoch and deadline guards for the crypto step pass, immediately before `doFinal`. API30 must use
  the same Cipher supplied in CryptoObject; API28/29 create their Cipher after
  authentication. AAD can advance an Android Keystore operation and cache an
  unauthenticated-operation exception even if `updateAAD` returns normally.
  Never treat it as passive setup or remove AAD/authentication to avoid failure.
- A single `VaultStore` AtomicFile stores complete envelope plus optional device
  wrapper in no-backup private storage. Guard before durable effect, verify the
  persisted record afterwards. Never publish an unlocked session after a newer
  lock: use `unlockIfEpoch(expectedEpoch, ...)` atomically.
- Typed manifest mutations recheck `clock.now() < operation.deadline` in the
  final store guard, after caller guards. An expired operation returns
  `VAULT_EXPIRED`, leaves ciphertext and shortcut material unchanged, and
  remains consumed; an earlier `protectedEffect()` check is insufficient.
- Native credential/key release passes an `EffectGuard hostKeyGuard` to
  `AppSecrets.sshCredential` / `sshPrivateKey`. Inside the core, clone the
  selected bytes, call the final host-key guard, then `protectedEffect`, then
  the receiver; wipe the clone in `finally`. A host-key guard that locks,
  cancels or exhausts the deadline must prevent receiver invocation.
- `EffectGuard.beforeCommit()` claims a `VaultRequestPhase` after the last
  permission/snapshot check and before AtomicFile finalization. `boundGuard`
  forwards this hook through core guards; test stores must call it too.
  Cancellation orders against that claim with a tiny mutex, without disk/crypto
  work under it. Interrupted requests after the claim report
  `VAULT_COMMIT_UNCERTAIN` and lock; after export tag claim they report
  `VAULT_EXPORT_UNCERTAIN`. Cancel, timeout, background and close share the same
  terminal mapping. Before a claim, cancellation prevents the durable effect.
- Management always freshly derives and authenticated-decrypts the supplied
  password; existing session, system success and password string comparison
  cannot substitute. Disable persists first, cleans its own key afterwards.
- Background timeout is 0..86400 seconds, default 30. Monotonic original background time controls expiry;
  manual/screen lock and restart lock immediately, increasing timeout cannot
  revive a locked session. Crypto/storage/status run on workers; lock invalidates
  fast without waiting behind crypto on the main thread.
- When background entry synchronously expires the session (including timeout
  zero), queue `cleanupInvalidatedContexts()` even if the session is already
  locked. Revoke only requests/prepared exports bound to an older epoch; do not
  cancel a fresh management/system-picker request merely because it began with
  a locked vault. Cleanup must wipe an invalidated import preview's verified
  source key/plaintext and remove old typed operations, not just the session key.
- Bridge close is idempotent and immediately makes its session nonforeground
  and locked. Invalidate its background callback version and remove its specific
  pending lock Runnable before shutting down the worker. A closed bridge's
  `onStop` and delayed callback cannot change a newly resumed context or submit
  to the closed executor. Remove only owned callbacks; do not clear unrelated
  authentication/result work from the Handler.
- API 28/29 uses credential confirmation plus actual auth-bound Cipher;
  API 30+ uses per-use strong-biometric/device-credential CryptoObject.
  A callback Boolean alone grants no key. Keep Java collection APIs minSDK 28
  compatible (`VaultCollections`), not `Map.of`/`List.copyOf`.
- TOTP produces only SHA1/6-digit/30-second codes, never returns seeds to Flutter.
  SSH public views exclude private keys; key release rechecks the actual hop and
  key identity. Trust CAS requires a distinct approved immutable exact proposal
  (target/port/fingerprint/base+replacement digest); host-key success is not
  consent to rewrite arbitrary known_hosts content.
- Bundle approval is a separate default-deny `ExactBundleApproval` for the exact
  whole proposal at admission/effect/final storage guard; host-key success never
  replaces bundle/trust approval. Entire resulting manifest validates and
  commits via `commitManifest`, preserving unrelated entries/files and optional
  key wrapper. Reject shared dangling references/associated key-passphrase
  removal; never silently cascade. Replace/delete require expected whole-object
  CAS, a different proposal or stale snapshot cannot reuse the capability.
  A real SSH validator is required even when deleting the final SSH object.
- Restore is independent from ordinary `SshContentValidator`: a versioned
  `SshRestoreReviewer` produces a bounded immutable metadata-only
  `SshRestoreReview`; a separate default-deny `ExactRestoreApproval` checks
  exact native consent at admission/effect/final guard. Source or authenticated
  target SSH requires both hooks and the validator, including pure TOTP source
  overwriting an SSH target. Core verifies full SSH file/credential coverage
  and computes binding from both ciphertext digests, provider version/revision
  and whole projection. Re-registration/revocation invalidates old previews.
  Do not expose raw private keys/config/known_hosts contents in this projection.
- `restoreReview` includes provider ID/version/revision, source/target cipher
  digests, `coverage` (side/kind/type/objectId/path/identity/objectBinding),
  `trustChanges` (status/sourceObjectId/targetObjectId/host/port/keyType/
  sourceFingerprint/targetFingerprint/marker) and `conflicts`
  (kind/status/sourceObjectId/targetObjectId). Unknown mappings remain visible;
  incomplete/oversized reviews reject instead of silently truncating risk.
  Legacy Boolean-only import commit is allowed only for pure TOTP on both sides.
  Never publish a direct digest of SSH password/passphrase content: after core
  coverage verification, use per-preview random-key HMAC opaque credential
  bindings in the displayed projection. Wipe the temporary key; the public
  review digest binds this actual opaque projection, without a low-entropy
  secret guessing oracle.
- Production SSH semantic validation is currently absent: mixed SSH imports
  and semantic mutations fail closed, `sshBackupImportAvailable=false`. Future
  consumers must use the common typed bundle transaction and real SSH validator,
  preserving unrelated entries. Real restore review/approval providers and
  trust semantics remain required before enabling mixed import.
  Do not implement a reduced OpenSSH parser or separate KDF/persistence.
- `SensitiveClipboard.copyCode(code)` is an internal effect helper invoked only
  by explicit `secrets.totpCopy`: worker generation/current snapshot then
  `AppSecrets.publishTotp` checks request/epoch/revision/deadline and the current
  wall-clock window at the actual clipboard effect. Never call it with Flutter
  supplied codes or without the native publication guard. It writes the
  sensitive hint. User owns cleanup. No read/listener/clear call,
  copy ID, timer, cleanup status/preference or cross-app clipboard probe remains;
  this is the user's 2026-10-10 scope decision. Do not reintroduce them.

## 4. Validation & Error Matrix

| Condition | Result |
| --- | --- |
| Wrong password, authenticated ciphertext tamper | `VAULT_AUTHENTICATION` |
| Unsupported/truncated/oversized envelope | `VAULT_FORMAT` |
| Invalid manifest/path/reference | `VAULT_CONTENT` |
| Missing real SSH semantics provider | `VAULT_SSH_VALIDATION_REQUIRED` |
| Missing/locked/stale/expired state | `VAULT_MISSING` / `VAULT_LOCKED` / `VAULT_CHANGED` / `VAULT_EXPIRED` |
| UI cancellation before effect | `VAULT_CANCELLED`; no late unlock |
| Repeated bridge close / closed bridge lifecycle or delayed lock callback | No effect; no executor submission or newer-context invalidation |
| Active operation / bad bounds | `BUSY` / `PARAMETERS` |
| Post-commit persistence cannot be determined | `VAULT_COMMIT_UNCERTAIN`; lock and recover from persisted state |
| SAF export cleanup/restoration failed or mutation effect unknown | terminal receipt with precise remaining roles/locations; never claim clean rollback |
| Invalid export journal | report `exportRecoveryError`, refuse export/recovery; keep ordinary vault status/unlock usable |

The user's 2026-10-10 export decision replaces the earlier single create-document
flow: same-name confirmation, cleanup of only this transaction's created parts,
and protection of existing files. `VaultExportTransaction` stages/reads back a
new random document; confirmed old content is never opened for write. A random
rollback rename protects old bytes, and final is created as a new document whose
ID does not alias any currently existing document. Path-based providers may
reuse the historical original path ID after the old object moves to a distinct
rollback ID; this does not authorize writing the old object. Admit that final
reuse only with consistent phase/name/old-rename/rollback metadata. Reject
aliases between current stage/final/rollback roles. Before final verification,
compensate only exact owned IDs and restore old
name without overwriting a competing file. After final verification, retain
final even if old cleanup fails. Keep rename-returned ID chains and explicit
roles; ambiguous mutation effects never grant ownership by filename/prefix.

SAF direct-child listing is bounded/complete, not a deep scan. No broad storage
permission or persistent tree grant. `VaultExportJournal` stores minimal phase
and object metadata, never passwords/plaintext/cipher snapshots. Restart reports
pending recovery without directory access; recovery needs explicit user action
and same-tree reauthorization. Cancellation waits for owned worker cleanup and
terminal receipt; no provider I/O runs under the request phase mutex or main
thread. Conforming-provider contracts permit compensating transactions, not
arbitrary-provider multi-document atomicity or remote-cloud durability claims.
Journal writes verify bounded persisted bytes after `AtomicFile.finishWrite`;
do not call `failWrite` after finalization when readback fails, because legacy
Android implementations can delete the newly committed base. Track whether
finalization has been invoked and preserve uncertain persisted state.
After an exact query confirms a created role is absent, retire its reference
and confirm the journal transition before restoring the old name. If that
transition is unconfirmed, keep the evidence and report uncertainty. A restored
old object retains its actual returned ID/version until its metadata transition
is confirmed; never revert to the superseded rollback URI. Once verified final
cleanup confirms the old rollback is absent, consume the old historical roles
together. Never retire or delete a verified final during postcommit cleanup.
An invalid journal blocks new export/recovery, not unrelated vault usage.
Status may report only a stable recovery error code; do not dump corrupt bytes,
discard the ledger, guess document IDs or overwrite it with a new transaction.
The 120-second deadline bounds admission/commit authority, not arbitrary provider
I/O completion. Cancellation signals/owned-stream closure are best effort;
do not release the operation lease or report ordinary cancellation before the
worker determines its cleanup outcome. No new service or persistent grant is
added to keep cleanup alive.

## 5. Good / Base / Bad Cases

- Good: fresh password authentication, bounded complete mixed graph, immutable
  preview, final snapshot/epoch guard, checked whole-record commit.
- Base: TOTP-only backup works without SSH provider; disabled/unavailable quick
  unlock retains the password path; locked ordinary pages remain usable.
- Good lifecycle: an old bridge closes, a new bridge resumes the shared core,
  and old `onStop`/lock callbacks leave the new context unchanged.
- Bad: Flutter `trusted=true`, alias-only SSH matching, reading a seed over the
  channel, partial graph writes, exporting live Keystore material, clearing any
  current clip, or catching uncertain persistence as ordinary cancellation.

## 6. Tests Required

JVM tests exercise KDF vectors/format bounds/AAD tamper, mixed manifest and
actual core mutations, consumer/target/connection/once-use/cancel guards, trust
proposal CAS, wrapper/material generation, post-persist lock races, monotonic
timeouts and export uncertainty. `VaultStoreTest` uses only a unique owned test
file; interrupted writes and forced rename failure require actual Android runs.
Compiling instrumentation is not running it. Verify actual Keystore, API 28/29
and 30+, foreground/system picker lifecycle, screenshot/recents and arbitrary
SAF providers on authorized devices. Linux OpenSSH memory probe is research,
not Android/native client acceptance; see the task research report.

`VaultTest.typedMutationExpiryAtFinalCommitRetainsCiphertextAndShortcut`
advances the injected clock exactly to the capability deadline inside the
store, before its final guard, and asserts unchanged ciphertext/wrapper and
replay refusal. `finalHostKeyGateInvalidationCannotReleaseCredentialOrPrivateKey`
exercises lock, cancel and exact-deadline invalidation from the last host-key
gate for both credential and private-key paths; neither receiver may run.

Android crypto fixtures may inject `AppSecrets(ownStore, clock)` using unique
owned directories, never the process production owner or preferences. Injecting
an own store does not isolate Android Keystore's UID namespace:
`AppAuthentication.cleanupDisabledKeys()` and successful enable completion sweep
the production module alias prefix. Avoid those paths in isolated fixtures;
unavailable-before-key-creation is safe to check. Any actual Keystore probe owns
and deletes only its unique test alias. A device without secure lock can verify
unavailable/refusal, not successful credential/biometric CryptoObject release.
Launching the real `MainActivity` also constructs its production bridge and
queues normal `cleanupDisabledKeys()` maintenance. That launch is not a
Keystore-free fixture: it can enumerate module aliases and remove orphaned
ones. Scope unavailable-before-key-creation claims to the isolated
`AppAuthentication` instance; record normal application startup separately.

Bridge integration fixtures use their own UUID Store, preferences and journal;
never call `AppSecretsOwner.acquire` for fixture data. If real MainActivity is
the host, forward only its exact Activity lifecycle events to the independent
bridge and record the normal production startup separately. Do not claim that
direct method calls, reconstructed cores or synthetic SSH gates prove system
event delivery, actual process death or production SSH semantics. Check grace
and expiry using real monotonic time/Handler, late request completion exactly
once with unchanged persisted bytes, shared typed-consumer revocation, and
close followed by the old deadline/late `onStop`/repeated close. Flush workers
before deleting only the fixture's marked files and preferences. Injected
dependencies alone do not isolate successful quick/rekey/import key cleanup;
avoid those paths unless a separate UID/alias isolation design is verified.
The opt-in release package `im.majo.ctos.secretsfixture` supplies that separate
UID for `AppSecretsUiFlowTest#freshFixtureManualSecureQuickAuthenticationFlow`.
Require `ctosManualSecureQuickAuth=true`, an already-secure device, empty vault
and journal (including `.bak`/`.new`), no old marker and no own module aliases
before launching MainActivity. Missing opt-in is a skip, not a pass; explicit
opt-in on an insecure device is a failure. The human completes both system
prompts; while awaiting them the driver polls only its own wrapper/session and
does not request accessibility roots or log/input credential fields. Verify
the real process session key authenticates unchanged empty-manifest content,
disable/cancel/wrong-password preservation, wrapper removal and bounded
asynchronous own-alias cleanup before password fallback. Preserve failure
state for reconciliation; this empty-library test cannot prove nonempty
business data, biometric-specific success or cross-device restore.

`SecretsQuickAuthDiagnosticsTest` is also fixed to that independent fixture UID
and `SecretsFixtureApplication`, with its own Root auto-start already disabled.
All methods require `ctosQuickAuthDiagnostics=true`. Missing opt-in is a skip;
invalid explicit values/package/shared UID/own process mismatch fail before
effects. `readPublicQuickAuthenticationCapabilities` reads only public services,
features and Keyguard/Biometric flags. `probeOwnAuthenticationKeyInitialization`
additionally requires `ctosQuickAuthKeyProbe=true`; it checks preabsence, owns
only `ctos.fixture.authprobe.<runId>`, uses the production AES/GCM/auth policy,
records initialization stages and public KeyInfo, then deletes that exact alias
in finally and asserts absence. It never prompts or calls doFinal. A JUnit pass
means diagnostics completed; inspect `keyInitSupported` separately, and
`authenticationTested=false` must never become successful-authentication evidence.
Log at most eight exception class names, no messages, hidden-API reflection,
stack traces, passwords or key bytes.

`readOwnFixtureReconciliationMetadata` additionally requires
`ctosQuickAuthOwnState=true`, decimal `ctosQuickAuthExpectedUid` and
`ctosQuickAuthExpectedTestUid` matching the current installed inventory. It reads
presence of the known vault/journal base/.bak/.new and fixed owned marker, wrapper
presence, persisted-grant count and public lock flags. If vault .bak/.new exists,
skip `VaultStore.quickMaterial()` because AtomicFile.openRead may recover/mutate
files; otherwise wipe its wrapper clone and assert all known presences unchanged.
Optional `ctosQuickAuthOwnAlias` accepts exactly one known
`ctos.vault.quick.<lowercase UUID>` and only containsAlias; it never enumerates or
deletes aliases or accepts arbitrary paths/URI arguments. No production Owner,
private content or Main launch. Absent/unreadable wrapper/alias is not proof that
all effects are gone; failed reads block automatic reconciliation. Gate fixture
reset/uninstall on marked ownership, actual journal/grant/alias/process/system-UI
facts. Never overwrite a failed fixture's main APK before checking those facts.

API30 credential regression evidence on 2026-10-12: the unchanged real release
manual E2E failed enrollment before the AAD fix, then passed real wrapper
persistence, session-key authenticated decryption, disable cancel/wrong-password
protection, correct disable/key cleanup and master fallback after moving only
AAD timing. Require this full protected effect when modifying auth Cipher flow;
callback success or initialization-only probes do not cover the bug. Biometric
and API28/29 branches remain separate required platform gates; see
`design/verification.md` for versioned results.
Timeout-zero tests must retain an own pure-TOTP preview reference, deliver real
background entry, drain the owned worker, and assert both its verified key and
plaintext were wiped, the preview/old operations were removed, and the persisted
record stayed unchanged. Include a fresh already-locked request as a negative
control against cancelling all system UI whenever no session key is present.

`SshBundleTest` exercises real shared-core complete graph add/replace/delete,
unrelated secret preservation, fresh nonce/stable material binding/wrapper,
deep-copy/close, path/size/ref/cycle/associated passphrase rejection, exact gate
and validator (including final deletion), replay/purpose/cancel/epoch/deadline
and persistence-effect hook chains. Its fixture validator is explicitly
synthetic and never substitutes for production OpenSSH semantics.

Export fault tests exercise reused existing IDs, changed rename IDs/names,
collisions/metadata changes, write/close/EOF readback failures, cancel around
old rename/final verification, delete-then-throw, cleanup/restore failure and
restart recovery. Journal finalization must verify the persisted record before
irreversible old deletion; a stale precommit journal must never cause recovery
to delete the sole verified final. Recovered ownership requires exact role and
version/content evidence, not an ID or name prefix alone. Android journal/provider
fixtures compile locally and require separately authorized platform execution.

The test-only `DocumentsProvider` must retain framework-required
`MANAGE_DOCUMENTS` protection. Its cold owner process must bootstrap before the
target UID can acquire it and use the fixed fixture-tree grant. Use target
context for target-process activity/resolver calls; instrumentation test context
package attribution can differ from its actual Binder UID. Bootstrap accepts no
arbitrary package/URI and grants only the fixed test root/target. Never add a
production privilege or persistent user-directory grant to make a fixture pass.
Enter cleanup scope as soon as creation returns an owned document ID, before
opening/writing/closing it or constructing a transaction. Attempt every exact
owned role ID independently and preserve the primary failure when collecting
cleanup failures. Never replace failed cleanup with a name-prefix scan.

Restore tests use explicitly synthetic providers to cover source/target SSH,
complete coverage/size/alias bounds, projection binding, provider re-registration
or revocation, legacy Boolean-only rejection, wrong consent and final guard
cancel/epoch/deadline races. They prove public orchestration, not production
OpenSSH parsing, trust freshness or cross-device connections.

## 7. Wrong vs Correct

Wrong: initialize an auth-bound Cipher, call updateAAD before BiometricPrompt,
then assume a successful callback or nonthrowing initialization proves doFinal
will succeed. Correct: init only before API30 prompt; after authenticated final
guards for the crypto step, submit the unchanged AAD on the same Cipher, doFinal, authenticate the
vault content and verify persisted effects. API28/29 must likewise submit AAD
only after their credential success. Keep all original auth and snapshot guards.

```java
// Wrong: the gate can invalidate the session after the final core check.
protectedEffect(operation);
hostKeyGuard.check();
receiver.receive(credential);
// Correct: recheck core authority after the final host-key gate.
hostKeyGuard.check();
protectedEffect(operation);
receiver.receive(credential);
```

```java
// Wrong: persistence may finish after the user locks; this reopens the vault.
session.unlock(key);
// Correct: the newer lock wins even if the complete record was committed.
session.unlockIfEpoch(expectedEpoch, key);
```

```java
// Wrong: an old Activity timer may reach an already shut-down worker.
main.postDelayed(() -> invalidateVault(), delay);
// Correct: own and revoke the callback; also guard already-delivered callbacks.
if (closed || version != backgroundVersion) return;
```

Do not convert `VAULT_COMMIT_UNCERTAIN` to “previous data retained”, or a SAF
flush failure after the final tag to “no backup created”. Preserve uncertainty
as a first-class outcome and require recovery against actual persisted state.

## Scenario: independent UID release UI verification

### 1. Scope / Trigger

Use the opt-in fixture to verify real Flutter management controls and system
SAF without creating a production vault. It shares production Main/Flutter/core
sources; its Application supplies only fixture startup isolation.

### 2. Signatures

`./hako bash -lc 'cd android && ./gradlew -PctosSecretsUiFixture=true :app:assembleSecretsFixtureRelease :app:assembleSecretsFixtureReleaseAndroidTest :app:lintSecretsFixtureRelease --console=plain'`.
Runner: `im.majo.ctos.secretsfixture.test/androidx.test.runner.AndroidJUnitRunner`;
select one `AppSecretsUiFlowTest` method explicitly. The normal build omits the
property and retains its original application/test IDs and manifest.

### 3. Contracts

The only allowed option value is `true`; other supplied values fail configuration.
App/test IDs are fixed to `im.majo.ctos.secretsfixture` and `.test`, with no shared
UID. `SecretsFixtureApplication` checks its package before synchronously setting
only its own `root_access/auto_start=false`; failure aborts startup. Fixture test
sources/manifest exclude the ordinary fixed-authority/grant helpers. UiAutomation
does not suppress existing accessibility services, change system settings or
disable secure-window protection to capture secrets. Management-only UI checks
do not touch the clipboard. Physical TOTP copy checks default to disabled and
require specific current approval to replace the global clipboard, plus the
exact `ctosTotpClipboardCopy=true` instrumentation opt-in. General install/test
authorization does not override an automatic approval rejection. An approved
check may write a synthetic current code and verify only that own write, never inspect
prior user content or restore/clear/listen to it. Cipher reads are scoped fixture oracles,
not substitutes for the tested UI actions.

`TotpUiFlowTest#freshFixtureTotpUiFlow` additionally requires
`ctosTotpUiFixture=true`; `ctosTotpImageUi=true` opts into an API29+ exact owned
UUID MediaStore PNG. Select only its exact filename in foreground DocumentsUI.
Search editors bind `android:id/search_src_text` or the actual picker package's
`:id/search_src_text`, an editable enabled visible EditText in the active window
with advertised SET_TEXT. Never fall back to arbitrary editable nodes or coordinates.
Cross-app search submission uses the existing UiAutomation session, not
Instrumentation's own-app input API. Before Enter DOWN, refresh the exact
focused active-picker editor and own UUID query. After that accepted DOWN,
paired UP refreshes the same editor/query in the original picker window; focus
may have cleared during submission. Other role/resource/owner/visibility guards
remain; require both injections to succeed. Do not request INJECT_EVENTS, Root
or system settings changes.
Selecting same-UID owned media proves real picker/decode flow, not cross-UID
temporary grant enforcement. Cleanup verifies exact URI ownership and deletion.
Image-grid layouts can hide titles. Use only the known active picker's native
`:id/sub_menu_list` action and verify its inverse `:id/sub_menu_grid` in the
same window before strict visible `android:id/title`/UUID selection; never
replace the exact filename with a thumbnail, coordinates or arbitrary row.
Failed fixture ciphertext is reconciled read-only against the recorded UUID,
marker and synthetic profile before uninstalling the two owned packages.
Image-commit failure reconciliation accepts only that recorded run's baseline
and at most its one explicitly selected public candidate. Authenticate the
full profile and require unchanged ciphertext, no sidecars/journal/wrapper or
persisted grants before cleanup; unknown entries or pending transactions stop
cleanup. This inspector may neither launch UI nor unlock/mutate the session,
and is not evidence of a completed UI flow. Fixed-label diagnostics expose only
booleans/counts, never raw accessibility trees, user inputs or seed-bearing text.

Bind distinct editable nodes by their exact field labels before input; do not
reuse breadth-first indexes after a semantics rebuild. Refresh the same bound
node and verify input updates before submitting the form's directly actionable
Button role. Flutter can expose validation in `getHintText()` along with the
field label, separated by a newline or Android's `, ` label separator. Match
complete semantic components, including actual status prefixes and punctuation,
across text/description/hint; do not silently relax a failed validator check.
Diagnostics report only bounded role/action/bounds, lengths and comparison
booleans, never password text. Teardown uses `finally` so observation failure
does not prevent finishing the owned Activity.

All fixture actions must belong to the current scope package and active window.
Business controls use their own advertised CLICK and exact Button semantics;
only fixed navigation/confirmation controls may use explicitly allowed roles.
Do not climb arbitrary ancestors or click editable settings while searching for
an action. Scroll only a noneditable page ScrollView or an evidenced collection
container in that active window with the requested advertised direction; rebind after scrolling. Flutter may retain `android.view.View` while publishing collection
metadata for a ListView; class name alone is insufficient. A collection fallback
requires explicit collection metadata, advertised scrolling and bounded visible
container bounds, never an arbitrary View or editable field. Verify complete
terminal notices and bring status into view instead of equating offscreen status
with an unsuccessful operation. An unrelated active window causes wait/failure,
not a guess at a background fixture control.

Lazy lists can detach internal status/button anchors after scrolling. Reacquire
the unique evidenced collection using the exact known noneditable AppBar title
above its viewport in the same active window; reject ambiguous candidates.
The current fixture reaches controls by collection scrolling, without keyboard
Back events. Historical keyboard experiments are recorded separately.

Re-click the exact owned editor for each input/retry and wait for input focus plus its advertised
`ACTION_SET_TEXT` before calling that action. Flutter's Android bridge may return
success and predictively update cached text even when the Dart editor has not
advertised a setter; cached equality alone is not proof of controller input.
An old Android `isFocused=true` can survive Dart submit/unfocus and does not
replace this handshake. Wait for enabled/editable/clickable state after the
terminal UI message before re-acquiring focus.
Confirm values survive a subsequent focus/semantics rebuild, then use actual
validator and cipher outcomes as the oracle.
After retained-field checks and ensureVisible/keyboard layout updates, wait a
bounded interval for that original exact node to be enabled/editable/focused with
advertised SET_TEXT in the active scope/window. Dispatch once in that ready
branch; do not retry an already dispatched effect.

System picker helpers must also use the actual active DocumentsUI window,
including resource lookup, breadcrumbs, bounded ancestor navigation and consent.
Refresh the same node before an effect and recheck package/window, visibility,
enabled state and advertised CLICK. Matching a background window's package is
insufficient.

Do not assume a roots navigation button exists: the installed API30 DocumentsUI
locks its drawer when at most one selectable root exists. On configuration B,
the installed ExternalStorageProvider primary-emulated branch uses
`Settings.Global.getString(resolver, "device_name")` as its root title, with its
own localized `root_internal_storage` fallback. The fixture checks installed
provider identity/version and public primary/emulated storage metadata, then
compares the actual picker breadcrumb exactly with that source-derived title.
Build.MODEL, title length and StorageVolume description are not identity proofs.
The read-only mapping probe passed separately; navigating and granting the
exact new `Download/ctos/<UUID leaf>` still requires live breadcrumb and full
localized consent evidence. Do not add privileged provider queries, settings
writes or broad storage access to resolve a missing proof.

The installed DocumentsUI create-directory callback automatically opens the new
container. Its final breadcrumb deliberately has no CLICK action. After one
create confirmation, wait a bounded interval for the complete exact active
breadcrumb; do not interpret one transitional frame as a request to click the
new folder again. Existing-folder navigation still requires its exact row action
and the resulting full breadcrumb before any consent.

Import file titles may be six ancestors below the actionable native document
row. On configuration B, a read-only action diagnostic proved the exact filename
at `android:id/title`, no CLICK at depths 0–5, and advertised CLICK at depth 6
`DocumentsUI:id/item_root` despite its ordinary clickable flag being false.
Bind the exact filename and full active breadcrumb, then locate only that
evidenced row role/resource within a bounded parent chain. Refresh the title and
row, require matching active package/window and row bounds containing the title,
then rewalk the refreshed exact title to prove it still belongs to that same row,
recheck row role/resource/bounds and current breadcrumb, and dispatch its
advertised CLICK once. Stop at the row; never climb into
`dir_list` or another screen container. MIME mismatch must be measured rather
than inferred from a failed click; this backup reported `application/octet-stream`
and matched the production import filter.

### 4. Validation & Error Matrix

Wrong target/UID/Application, existing unknown fixture, nonfresh data or failed
Root-startup isolation -> stop before effects. Missing exact picker path or full
localized consent evidence -> stop before creating a folder/granting access.
Failure after effects -> retain fixture, journal, temporary grant and precise
owned location for reconciliation; do not clear data before recovering evidence.
Before resetting a failed pre-export fixture, diagnose own vault/journal recovery
variants and grants from the verified target UID, then corroborate exact grant
projection and the stages actually reached. A public "missing" label or a
permission-denied shell read is insufficient evidence that no journal exists.
System Back inside a nested tree picker can navigate up instead of cancelling.
After an explicitly authorized cancellation, prove the exact owned picker left;
if more Back actions are needed, recheck its active task/caller and breadcrumb
before each bounded action, and stop as soon as another window takes over.
Sending Back alone never proves cancellation or authorizes deleting its directory.

### 5. Good / Base / Bad Cases

Good: actual controls plus own ciphertext authentication, exact
`Download/ctos/<UUID leaf>` picker consent and terminal cleanup. Base: input and
cancel probe with no vault or picker effects. Bad: injected channel calls posing
as UI, granting a parent because a leaf name is merely visible, or passing an
own Store to a production-UID auth sweep and calling it Keystore isolation.

### 6. Tests Required

Check APK identity/signature/installed hash and distinct UID before running.
Probe field actions/cancel without creating a vault. Management checks wrong
password/cancel byte preservation and rekey; SAF checks new export, collision
cancel, explicit overwrite, distinct source/target passwords and import preview/
confirmation. Empty-library checks do not prove nonempty/mixed SSH restore.
Assert exact tree grant with no parent/persisted grant, terminal receipts before
deleting only returned owned documents and the empty UUID leaf; retain `ctos`
parent. Uninstall only the freshly owned fixture packages and verify production
status/artifact unchanged. Missing proof is not a pass or a reason for broader
permissions.

Successful import is not a ciphertext-copy operation: `AppSecrets.importCommit`
encrypts the authenticated source plaintext under a fresh local UUID/generation 1
and nonce, retaining the source salt/password material and removing the device
wrapper. Authenticate both source and imported envelopes, decode both complete
manifests and compare their full plaintext in memory; wipe keys/plaintext and
close decoded manifests. Check fresh identity/material binding, generation,
nonce, wrapper absence and locked UI, then reject the previous target password
and unlock through the actual UI with the source password. Use the captured
imported ciphertext as the unchanged baseline for a failed unlock; the external
backup remains the original backup bytes. Wrong passwords and cancelled previews
must still preserve the pre-import destination ciphertext byte for byte.

### 7. Wrong vs Correct

Wrong: `new AppSecrets(ownStore, clock)` alone prevents production alias cleanup.
Correct: use a verified independent package/UID and minimal fixture manifest;
record normal production-UID startup effects separately in lifecycle fixtures.

Wrong: successful restore must have `Arrays.equals(backup, localCiphertext)`.
Correct: require equal complete authenticated manifests and the fresh local
envelope contract; keep byte equality for failed/cancelled operations and for
the unchanged external backup.

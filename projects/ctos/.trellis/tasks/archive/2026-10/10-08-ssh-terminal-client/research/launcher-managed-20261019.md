# Research: managed production SSH launch integration

- Query: existing managed SSH catalog/config/credential/import capabilities and the smallest coherent local increment after authenticated PTY/shell.
- Scope: internal source investigation; production launch path, shared vault, managed file import, Flutter routes and ownership. No implementation, builds, git operations, device, network or real account access.
- Date: 2026-10-19
- Active task: `.trellis/tasks/10-08-ssh-terminal-client`; parent remains in progress. Preserve the existing uncommitted shell increment and the user's local-only/no-commit/device boundary.

## Findings

### 1. Product contract already decides the major choices

The parent PRD is authoritative: config is the connection source of truth; enumerate explicit positive Host aliases, display alias plus resolved target, keep wildcard rules for matching rather than inventing host rows (`../10-08-ssh-terminal/prd.md:25`). First release includes Include and single/multiple ProxyJump, native OpenSSH matching/first-value semantics, additive IdentityFile, major connection options, non-executing Match, and config forwarding (`:28`). Private managed import through SAF is already selected (`:29`). NEW review and changed-key refusal cannot be disabled by config (`:27`).

Parent design fixes active import into the public encrypted vault, no persistent synchronization grants, no plaintext staging, Include resolution relative to the user SSH config directory, complete closure import, conflict and trust review, external source preservation (`../10-08-ssh-terminal/design.md:137`, `:140`, `:149`, `:152`). It also requires source/line diagnostics and rejection of local command execution before any execution (`:167`, `:184`). SSH entry and host-list placement are decided (`:197`). Bounds are 128 files, 1 MiB/file, 8 MiB total, depth 8 and at most 8 jump hops (`:207`).

Consequently, a new form containing arbitrary hostname/user/password, a Java/Dart reduced config parser, silently direct-connecting a ProxyJump host, or a fixture-only picker is not an acceptable production launcher increment. Temporarily unsupported *effective* config must produce an explicit blocking diagnostic; a later increment must implement the already-approved behavior. Do not reclassify approved product semantics as permanently out of scope.

### 2. Existing shared secret core is capable but not a product SSH catalog

Files found and roles:

| File | Existing responsibility |
| --- | --- |
| `android/app/src/main/java/im/majo/ctos/AppSecretsOwner.java` | One process-owned `AppSecrets` core shared by Activity and future service (`:6`, `:11`). |
| `AppSecrets.java` | Typed once-use SSH file/key/credential/trust/bundle operations plus authenticated vault mutation. |
| `VaultManifest.java` | Bounded encrypted typed file/entry graph, logical paths and identities, not a config evaluator. |
| `SshVaultAdapter.java` | Native-only typed use operations and exact bundle/trust guards. |
| `SshFileSnapshot.java` | Immutable metadata for complete requested closure and borrowed public bodies only. |
| `SshPublicFileLease.java` | Bounded native-owned public-only view with revocation. |
| `SshBundleProposal.java` | Exact old/new managed object changes, proposal digest, summary and guarded atomic graph update. |
| `AppSecretsBridge.java` | Current public-vault/TOTP MethodChannel bridge; no SSH business methods. |

Concrete existing operations:

- `beginSshFiles(rootPath, connectionId)` / `sshFileSnapshot` / `sshPublicFiles` select the graph reachable from the caller-supplied root and loan it under once-use snapshot, epoch, operation and deadline checks (`AppSecrets.java:733`, `:748`, `:763`, `:783`). The graph is taken from authenticated `FileObject.references`; it is not rediscovered by parsing config text at this boundary.
- `SshFileSnapshot.metadata` includes private-key metadata (type/id/path/identity/length/references), but `publicFiles` excludes private-key bodies (`SshFileSnapshot.java:8`, `:44`). `SshPublicFileLease.encode` independently checks every body against metadata and serializes no private content (`SshPublicFileLease.java:59`).
- SSH password save/delete and request/use exist (`AppSecrets.java:512`, `:525`, `:699`, `:714`); private key and exact private-key/passphrase bundle exist (`:791`, `:815`, `:834`). `SshVaultAdapter.credentialRequest/useCredential` requires host verification (`SshVaultAdapter.java:53`); proof-bound production calls go through `SshCredentialBridge`, not a generic secret getter.
- Bundle request/commit requires exact approval twice, including the final store guard (`SshVaultAdapter.java:37`, `:43`). AppSecrets also requires a registered semantic validator (`AppSecrets.java:148`, `:166`, `:611`, `:625`). There is no production registration in `AppSecretsOwner.acquire` (`AppSecretsOwner.java:11`) or the current bridge constructor (`AppSecretsBridge.java:130`). This is why enabling an import UI alone cannot safely populate production SSH state.
- Manifest bounds are executable (`VaultManifest.java:22`); entries are `TOTP`, `SSH_PASSWORD`, `SSH_KEY_PASSPHRASE`, files are CONFIG/PRIVATE_KEY/PUBLIC_KEY/CERTIFICATE/KNOWN_HOSTS (`:26`). Paths forbid absolute, parent traversal, glob syntax, `~`, `%`, URI/device-path shapes; complete acyclic references and depth 8 are validated (`:190`, `:235`). Imported logical object paths and config references/expansions are distinct layers: do not strip config tokens and pretend that preserves semantics.

Missing typed capabilities:

- No `beginSshCatalog`/`sshCatalog`, root selector, host enumeration, resolved host descriptor, or metadata-only credential selector exists. The only read-list pattern in core is TOTP (`AppSecrets.java:437`, `:440`). Add a bounded purpose-specific projection; do not expose the manifest or raw private material through Flutter.
- No root registry is represented in the manifest. Existing tests use logical `ssh/config`; callers must still explicitly bind selected root + closure snapshot. Any choice of multiple supported roots needs a schema/selection contract, not an alias-only cache.
- No product association resolver maps actual `user@host:port` to exact password entry, or IdentityFile + key identity + public blob + optional passphrase entry. Multiple matches require an explicit selection; never choose by label or alias. The immutable selection class already enforces exact role and fixed resource: password requires entry ID; private key requires path/key identity/public blob/passphrase entry (`SshAuthenticationSelection.java:37`, `:50`). Public expected blob is still verified against parsed private key by the existing native auth path; metadata is not proof of private-key possession.
- The manifest allows public-key/certificate objects but supplies no separate imported-key inspector or native public-key derivation product API. A production importer must establish the public association needed by immutable private-key selection, without prematurely releasing a private key through a public view. Unsupported certificate authentication remains a diagnostic, not silent fallback.

### 3. SAF import exists for complete encrypted vault backups, not SSH source trees

`AppSecretsBridge` admits only public secret-management and TOTP methods (`AppSecretsBridge.java:228`). `openPicker` uses ACTION_OPEN_DOCUMENT for encrypted backup import and ACTION_OPEN_DOCUMENT_TREE for export (`:525`); input then goes to `vault.preview` as an encrypted source (`:573`). This cannot import an OpenSSH source directory.

Status explicitly reports `sshBackupImportAvailable=false` (`AppSecretsBridge.java:737`). Existing backup preview projects SSH entry/file counts and optional restore review (`:707`), but this is not a working SSH config importer/catalog. Never flip this flag merely because source-tree import or host-list parsing was added: mixed-backup semantic validation/trust reviewer is a separate contract.

Needed source-import path: native-owned SAF picker/result owner; bounded source-tree traversal and typed object discovery; explicit relative path mapping; pinned native config/identity/trust validation; exact old/new bundle review; guarded existing `SshBundleProposal` commit; cancellation/late picker/uncertain write outcomes; wiping borrowed source buffers. The SSH semantic validator must be the same policy authority used at import, restore and launch. Do not reuse HFTP root/network adapters, source URI as a runtime file path, generic file manager tokens as vault objects, or a lasting SAF grant for automatic synchronization.

### 4. Pinned native config policy is currently an isolated probe

Normal `compileSshFiles` builds public file IO, role, callback, session, hostfile issuer, KEX and native client (`android/app/build.gradle:226`, `:254`). It does not list `ssh_config_policy.c` or `readconf` evaluator integration. Config policy's production entry and trust hooks return PROVIDER rejection (`ssh_config_policy.c:154`), and Include has only fixture implementation; nonfixture rejects (`:161`, `:204`). Existing source already rejects local/proxy/known-host commands, weak trust settings, external agents/providers/control/X11/GSS/etc (`:125`).

Managed catalog and launch must consume a pinned native evaluator over the same immutable public lease, including Include glob order/conditional scope, case/quoting/first-value rules, additive identities and per-hop effective config. Promote the probe deliberately with production provider/IO hooks and diagnostics; its successful fixture result does not register a provider. Alias enumeration can collect native parser Host tokens but must not become a competing semantics implementation.

### 5. The existing terminal attachment is real but still needs a launcher

`MainActivity.AuthenticatedSshFactory.create(cancelled)` executes on the serial `terminalWorker` and must return an actual authenticated `SshNativeSession`; factory owns every partial resource until retryable close confirms (`MainActivity.java:50`). `attachAuthenticatedSsh` reserves a per-launch cancellation flag before scheduling, invokes the factory on that original owner, and only completes on RUNNING (`:64`, `:90`, `:107`). Controller closes the session first, then factory extras (`SshTerminalSession.java:64`); unattached failures have retained owner cleanup retries (`MainActivity.java:138`). Use this seam, do not create a session on an import/resolver worker and transfer it.

Current Flutter start only invokes local App/Root terminal (`lib/main.dart:302`), and the picker has only those two entries (`:1629`). Terminal data/state generations, remote arrows, resize and stop are already present and should be reused. Add a typed SSH domain API and host-list route; retain main terminal surface for RUNNING.

Public vault bootstrap already has the required returning-consumer pattern: `ensureSecretVaultUnlocked` and protected route scope (`lib/authenticator/page.dart:23`, `:31`), typed API/request cancellation (`lib/secrets/api.dart:148`, `:193`) and current-event hiding/late-result revision fencing (`lib/authenticator/page.dart:74`, `:95`). Reuse the shared unlock UI; a successful unlock is not authority to release a selected SSH credential. SSH transient password/private-key-passphrase UI requires existing sensitive-route protection before rendering, default no-save, no output/history/log path, and a typed native request bound to current launch/hop. This product UI is currently absent.

### 6. Real host-key discovery and asynchronous exact NEW approval are blockers

`SshNativeSession.open` currently requires hostKeyBlob and expectedFingerprint from its caller (`SshNativeSession.java:370`); JNI signature includes both (`:308`). Native open requires nonzero expected fingerprint, parses supplied key, compares it and installs a KEX guard before transport (`ssh_native_client.c:1685`, `:1735`, `:1750`). Actual verifier compares the peer key to that pre-supplied fingerprint (`:550`, `:567`).

The independent fixture knows it because it calls the synthetic peer's `hostKeyBlob()` before open (`SshPasswordAuthPlatformTest.java:218`, `:220`, `:291`). Production may not substitute an arbitrary known_hosts record, rely on alias, run an unauthenticated preliminary connection and reuse that as trust, or simply disable comparisons. The actual peer key from this connection's KEX must feed native classification and exact evidence, then signature verification/NEWKEYS before credentials. Main session must freeze the revised dynamic-key KEX/proof contract before implementation.

`SshHostKeyAuthority.UnknownKeyIssuer.requireApproved(Review)` is synchronous (`SshHostKeyAuthority.java:14`); NEW creates exact review and invokes it during verification (`:176`). Review binds role/hop/connection/target/host/port/algorithm/fingerprint/base/replacement digests (`:67`). The native callback is owner-thread-only. A Flutter dialog cannot synchronously block the Android main thread or reenter native callback on another worker. Introduce an explicit pending/review/resume design or an equally evidenced owner-safe protocol, preserve original deadline and exact binding, and prevent polling from producing duplicate candidates/CAS. UI Boolean alone does not authorize trust or persistence. Existing production `SshManagedKnownHostsProvider` + `SshVaultAdapter` guarded CAS should remain the writer; KNOWN needs no write, NEW needs actual COMMITTED receipt, changed/revoked/certificate/invalid stay closed.

### 7. Lifecycle is a real contract dependency, not just a UI detail

Current role max lifetime is 60,000 ms (`SshRoleContext.java:12`, `:94`); `requireActive` also requires the exact active public view (`:105`, `:110`). Existing shell proof checks still use the original deadline/view (`ssh_native_client.c:800`, `:1356`, `:1359`; `SshNativeSession.shellOutputPublicationLive`). Thus a production launcher wrapped around today's controller will still stop after role expiry or vault-view revocation. Parent design expects already authenticated connections to survive vault lock and uses a foreground service for background/reattach (`../10-08-ssh-terminal/design.md:234`; child design `:585`, `:587`).

The previous shell increment intentionally preserved this stricter baseline. Do not extend deadline, detach vault revocation or silently call it a persistent production terminal. A future authenticated-session capability/lifetime contract must make post-auth transport ownership distinct from secret access, revoke credential operations, and maintain cancel/owner/rekey proof constraints. This is architecture work for main, with foreground service/reattach and ProxyJump chain ownership; it is not permission to weaken current controls in this research increment.

## Recommended next local increment and acceptance

These are source-grounded implementation recommendations for the main session to freeze, not a replacement product contract.

**Immediate dependency slice selected by the coordinating main session: actual offered-key discovery + exact bounded review mailbox.** Add an explicit managed-open API whose caller does not supply a key/fingerprint; the key offered by this session's actual KEX must go through the existing native issuer/classification, exact NEW approval and guarded COMMITTED CAS before authentication. Preserve the old pinned-open behavior unchanged. A Java mailbox may publish the exact immutable Review to a future UI/off-owner consumer and return only an exact decision to the waiting original owner; off-owner code may not enter native, change proof, authorize another Review or reset the role deadline. Keep waiting/cancel/expiry bounded and ensure cancellation can wake the waiter even when queued owner work cannot yet run. Validate no main-thread waits, duplicate/reentrant review, stale/mismatched decision, close/cancel races, late approval, wrong connection/hop/key/digests, no credentials before approved signature/NEWKEYS, and retryable cleanup. This is a production launcher dependency, not completed catalog/connect/UI acceptance. Readconf promotion research should finish before committing to whole catalog/config/connector architecture.

**Smallest whole product-facing local slice after those native contracts: managed source import → pinned config catalog → immutable resolved descriptor.** This yields actual user-configurable durable input and a production host list/diagnostics without pretending that a network launcher already exists. It is a stronger prerequisite than adding a demo connect button or a launcher that has no way to acquire production SSH files.

1. Register a real native-backed SSH semantic validator/provider; bind active root + ciphertext/epoch/snapshot into a bounded catalog operation. Add native-only metadata selection and typed projection, no generic manifest/secret channel.
2. Implement SAF source tree preview + exact bundle commit using current vault transactions, complete Include/reference closure, identities/public associations, old/new conflicts, and imported known_hosts trust review. Provide an empty-state real import action.
3. Promote pinned config evaluation over production public lease. Enumerate explicit aliases, resolve effective target/user/port/identities/known-host semantics/jump graph/forward exposure/requested session behavior into a host-owned immutable descriptor with source diagnostics. No network, listeners, DNS execution hooks, secrets or local command execution during list/resolve. Bind resulting descriptor to opaque one-use launch ID and original snapshot; UI gets only bounded metadata.
4. UI uses shared vault bootstrap, hosts/diagnostics/import-preview and exact approval. Every config field affecting target/trust/exec/forward must be retained in descriptor and implemented at connection time or explicitly block launch; no silent field discard.

Local acceptance for that slice:

- Native pinned parser golden comparisons: Host multi-alias/wildcard/negation, case/quotes, first-value, additive IdentityFile, inactive/final passes as supported, Include relative base/nesting/glob lexical order and refusal cases; one/multiple ProxyJump resolved per-hop config. Command/trust-bypass diagnostics include object/line and occur before execution, including inactive Match exec policy.
- Real AppSecrets encrypted MemoryStore import→proposal→commit→new snapshot→catalog/descriptor tests; no private bytes in public lease/channel. Missing referenced file/type/identity, cycle/depth/size/duplicate, stale preview, concurrent config mutation, cancel/revoke/lock/expiry/late result, uncertain commit and mixed TOTP preservation protect or report the actual old/new state correctly.
- Typed MethodChannel/Flutter tests for empty host list, import preview/conflicts/trust denial, native diagnostics, valid aliases/resolved display, missing User, ambiguous credential selection, unlock cancellation, route replacement, generation/revision fencing and protected transient input. No fake account, fixture picker or claim of socket acceptance.

**Following full launch slice** must take only the immutable resolved descriptor and selected exact credential metadata; own cancellable DNS/connect/FD transfer on the original serial terminal owner; issue exact per-hop roles/providers; obtain actual KEX key and exact NEW review/CAS; run current selected one-attempt auth; hand only SUCCESS to existing factory/controller; then verify command/output/resize/exit/stop/FD-role-view cleanup using local synthetic encrypted peer. Retain descriptor-driven ProxyJump graph and implement its actual transport (direct-tcpip channels/tunnel plumbing is presently absent); an effective jump may never degrade to direct TCP. Config forwarding, RemoteCommand/RequestTTY, keepalive/timeouts and service/long-session are separate required behavior dependencies, with visible refusal until implemented and parent AC left open.

## Related specs

- `.trellis/spec/backend/app-secrets.md`: native-only once-use operations/public file loans/graph updates/restore review, no raw channel data.
- `.trellis/spec/backend/ssh-native.md`: existing auth/SUCCESS-only shell, proof/owner/deadline/revocation and synthetic-vs-device separation.
- `.trellis/spec/backend/host-bridge.md`: Android owns SAF, network/service/JNI handles, bounded named methods, executor/main-thread boundary, no generic Root handle.
- `.trellis/spec/frontend/app-secrets.md`: shared returning-consumer unlock flow, sensitive windows, cancelled/stale route guards.
- `.trellis/spec/frontend/terminal-interaction.md`: existing typed terminal generation/remote input/controller seam; no fixture SSH picker.
- `design/ssh-session-channel.md:78`: production resolver/socket/key-discovery explicitly remained open in previous increment.

## External references (docs, versions)

No external retrieval was needed or performed for this read-only code-capability audit. Existing project contracts pin OpenSSH 10.6p1 and OpenSSL 3.5.9 (`android/app/build.gradle:203`); existing parent design links upstream manuals. Native parser integration should use the locally pinned source/patch manifest rather than live master. This report does not claim new upstream/Android runtime verification.

## Caveats / Not Found

- No new test/build/runtime was run. Existing passing shell/auth counts are historical evidence in `design/verification.md`, not results of this investigation.
- No generic production TCP connector, SSH foreground service/reattach owner, managed SSH catalog/import UI, private key inspector, dynamic KEX key discovery/resumable review, direct-tcpip jump chain or config-forward runtime was found among current production files. This is source absence evidence, not a new permission or assertion that future implementation may omit them.
- No new user-owned product decision is required to begin the recommended local import/evaluator/catalog slice: active import, shared vault, Include/ProxyJump semantics, trust policy, UI placement and bounds are already decided. Main must freeze technical contracts, especially public key association, exact transient credential/save flow, review suspension and post-auth lifetime. If multiple-root product management is desired beyond existing `ssh/config`, ask only then; using the established logical root for the initial implementation is a narrow technical choice.
- Actual source/device/account access remains governed by current authorization. User chose no commit and local-only; continue does not by itself authorize device installation or real account access.

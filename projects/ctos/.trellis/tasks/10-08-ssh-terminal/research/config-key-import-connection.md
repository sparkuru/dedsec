# Research: SSH config and key import to a real connection

- Query: What is already available for importing SSH config/Include/key material into the shared encrypted vault, listing actual Host aliases, and connecting with the effective config/private key?
- Scope: internal, read-only source/spec investigation; writes confined to this research file
- Date: 2026-10-26

## Findings

The user's observation is accurate: production has a generated password profile, not user-config import or a config-driven key connector. Native configuration parsing/catalog/audit and private-key authentication foundations exist, but several production contracts are missing. This is more than exposing an existing hidden UI.

### Files found

| File | Role |
| --- | --- |
| `android/app/src/main/java/im/majo/ctos/SshConfigEvaluator.java` | Owned Java/JNI facade for effective evaluation, declaration catalog, all-branch policy audit and static resource audit. |
| `android/app/src/main/cpp/ssh_config_eval.{h,c}` | Pinned readconf integration, virtual-home Include/file access, complete retained upstream Options and bounded summary. |
| `android/app/src/main/cpp/ssh_config_handle.{h,c}` | Opaque token registry and lifetime/post-publication fences; currently exposes summary/catalog/audit, not a finalized connection plan. |
| `android/app/src/main/cpp/ssh_config_jni.c` | NativeBindings implementation for the Java facade. |
| `android/app/src/main/java/im/majo/ctos/SshManifestConfigInspector.java` | Whole supplied-manifest declaration/audit traversal with actual file ordinals, original deadlines and cumulative budgets. |
| `android/app/src/main/java/im/majo/ctos/SshPasswordProfile.java` | Exact generated CONFIG/KNOWN_HOSTS shape; rejects arbitrary imported SSH files. |
| `android/app/src/main/java/im/majo/ctos/SshPasswordManifestValidator.java` | Current complete admission only for that generated password subset. |
| `android/app/src/main/java/im/majo/ctos/AppSecretsOwner.java` | Registers the password-only validator as the actual process-owned production validator. |
| `android/app/src/main/java/im/majo/ctos/SshBundleProposal.java` | Exact immutable file-only bundle changes with old-object CAS; preserves current metadata, cannot add KeyBinding/Account metadata. |
| `android/app/src/main/java/im/majo/ctos/AppSecrets.java` | Existing original-context locked metadata/lock-free source/candidate validation and atomic encrypted bundle transaction. |
| `android/app/src/main/java/im/majo/ctos/VaultManifest.java` | Sole current manifest, typed files plus KeyBinding/Account lists and size/path/reference limits. |
| `android/app/src/main/java/im/majo/ctos/AppSecretsBridge.java` | Suitable picker/request/lifecycle pattern for bounded in-memory SAF reads, not an existing SSH plaintext importer. |
| `android/app/src/main/java/im/majo/ctos/ToolFiles.java` | Generic workbench import stages ordinary plaintext files; unsuitable for SSH private/config material. |
| `android/app/src/main/java/im/majo/ctos/HftpBridge.java` | Directory picker and DocumentsContract/URI grant examples; its persistent read/write grant policy is not the SSH copy-only policy. |
| `android/app/src/main/java/im/majo/ctos/MainActivity.java` | Existing activity-result routing must gain SSH importer routing. |
| `android/app/src/main/java/im/majo/ctos/VaultUiRequests.java` | Process-wide never-reused picker request codes, useful for stale result isolation. |

### 1. Native parser/catalog is reusable; Java cannot yet obtain an effective key selection

- `SshConfigEvaluator.Request` (`:100`) already carries alias, explicit remote user, fallback user, actual local identity, tag, remote command context, port override and local UID. It should be constructed from the real connection request and `SshNativeRoleBootstrap.systemIdentity()`, never fake local identity.
- `Summary` (`:176-206`) returns alias, evaluated hostname/user/port, `userRequired`, identity/certificate counts, jump/command flags, forward counts, requestTTY/sessionType and `hasRawFields`. **It does not return actual effective IdentityFile/CertificateFile/UserKnownHostsFile/HostKeyAlias paths, algorithm preferences, timeout/keepalive values, or other retained options.**
- `Catalog` (`:233-279`) returns deduplicated literal alias + original object/line metadata, omitted selectors, Match presence and deferred Include sites. It remains `declarationsOnly=true`; it supplies list candidates, not final connection authority.
- `ctos_ssh_config_eval_options()` exists only behind upstream `READCONF_H` at `ssh_config_eval.h:118-123`, with implementation in `ssh_config_eval.c:926`; it returns a borrowed full Options pointer that cannot survive owner close. The opaque handle/JNI/Java facade has no equivalent getter.
- Header `ssh_config_eval.h:50-52` and backend `ssh-native.md:311-314` explicitly say full Options retain raw expansions and are **not** a launch plan. A connector must finalize or reject every relevant retained field. Reading Summary alone and hardcoding the remaining options would silently change config semantics.

Required production seam: a new bounded typed effective-plan projection from the existing native Options, through the existing handle/JNI owner/lifetime checks. It must distinguish declared/effective identity ordering and fields requiring later context/path expansion. It must not return a native pointer or convert ResourceAudit rows into an effective identity choice.

### 2. Declaration audits solve different obligations from an effective connection

- `SshManifestConfigInspector.inspect/inspectAudit/inspectResourceAudit` (`:182-192`) are available and one-shot. It visits **every actual CONFIG root**, including orphans (`:215-260`), using cumulative opens/read/line/output budgets and the original deadline.
- `Report` (`:94-118`) is always `admissionComplete=false`. `ResourceAudit.Resource` (`SshConfigEvaluator.java:341-374`) reports only kind/parent/line/argument/state/target ordinal, not raw path strings.
- Resource states are STATIC, DEFERRED_CONTEXT, DEFERRED_CWD, DISABLED and DEFERRED_KEY_RESOLUTION. These are all-branch source declarations; inactive branch resources can be present. They cannot tell the connector which effective IdentityFile was selected for a Host.
- Include edges can verify actual parent direct references; config/resource audits must remain independent from material parsing, private/public mathematical association and host trust.
- Existing policy rejects Match exec/local commands/custom proxy/known-host commands, trust bypass and external providers before effects (`ssh_config_eval.c:547-567`). It does **not** globally prohibit ordinary ProxyJump/RemoteCommand/forwards; the connector must implement them or visibly reject a selected unsupported plan, never silently ignore them or fall back direct.

### 3. Logical path mapping is deliberate and not an Android filesystem path

- Virtual home is `/ctos-vault` (`ssh_file_io.h:7`). `ctos_ssh_io_resolve` (`ssh_file_io.c:29-39`) accepts paths under that virtual home; it never opens arbitrary host filesystem paths.
- Upstream readconf receives passwd home `/ctos-vault` (`ssh_config_eval.c:841`). Include expansion uses actual request context and only managed files (`:498-529`), with user-config relative Include anchored to virtual `~/.ssh`, not to the including file's directory (backend `ssh-native.md:2624-2627`).
- Therefore importing an SSH directory as logical `.ssh/...` preserves ordinary `IdentityFile ~/.ssh/id_ed25519` and relative Include semantics without rewriting those bytes. Root selection and relative-layout preservation must be explicit.
- Static resource paths beginning `/home/...`, `/Users/...`, `~otheruser/...` reject (`ssh_config_eval.c:194-209`); bare relative identity paths are explicitly deferred as CWD-dependent, and absent static key bases are deferred as key-loader obligations.
- Current ResourceAudit returns no raw targeted argv, so it cannot itself drive a raw-path rewrite editor. Desktop absolute paths need explicit user config editing or a new pinned-lexer-backed mapping projection. Do not regex/line-parse the config to rewrite paths and do not silently turn every relative path into `~/.ssh/`.
- Imported file typing is also a real design obligation. Filename suffixes alone do not prove private/public/certificate/config types. Avoid calling the all-roots inspector with every imported file labelled CONFIG, which would parse private keys as orphan configs. A directory import needs explicit root/discovered Include roles plus material parsing, or an explicit staged file-role UI.

### 4. Actual production admission must be expanded; file bundle API cannot add relationship metadata

- `AppSecretsOwner.acquire` (`:11-16`) hard-registers `new SshPasswordManifestValidator()` in the real shared owner.
- `SshPasswordProfile.checkShape` (`:134 onward`) admits only the exact generated `ssh/config` and `ssh/known_hosts` pair with fixed IDs/identity/references, plus TOTP and the new canonical saved-password Account records. Any imported CONFIG, PRIVATE_KEY, PUBLIC_KEY or KeyBinding currently rejects.
- `SshPasswordManifestValidator.validate` (`:39`) invokes that exact shape and then pinned config/material checks. It cannot merely be replaced by a partial inspector or `manifest -> {}`.
- `SshBundleProposal.Change` accepts typed file objects; immutable binding includes actual replacement bytes/digest and expected old-object binding (`:55-94`). `applyTo` (`:166-201`) copies entries and changed files, then calls `previous.withObjects(entries, files)`, so **it preserves existing keyBindings/accounts and cannot atomically create new ones**.
- `VaultManifest.withObjects` (`:89-91`) likewise preserves metadata. Sole current constructor (`:79-86`) can create all four lists; limits are 128 files/KeyBindings, 1024 entries/accounts, 1MiB per file/8MiB total (`:19-24`).
- `VaultSshMetadata.KeyBinding` (`:20-35`) already models exact private/public/certificate IDs and optional passphrase Entry; `Account` (`:48-65`) binds target, real host/port/user, auth kind and credential ID. KEY Account credential ID refers to a KeyBinding (`:99-106`). Declaration structure does not prove actual key material association or runtime authority.

Required transaction seam: extend the exact proposal/candidate contract to explicitly approved immutable metadata changes, or add an equally bounded host-owned import transaction on the same original-context pipeline. File/private/keyBinding/account changes must commit together. Do not append KeyBinding/Account after a separate file write or silently erase metadata to reuse the password validator.

Reuse `SshVaultAdapter.bundleRequest(proposal, context)`/`commitBundle` (`:41-57`) and `AppSecrets.beginSshBundle/commitSshBundle` (`:2275-2361`): both source and candidate get actual validators, fresh ciphertext/readback, old snapshot checks, exact approval, one commit claim, cancellation and uncertain outcome rules. New import approval must bind the exact objects/metadata/replacement choices and trust review.

### 5. SAF picker is reusable; plaintext ToolFiles import is not

- Good pattern: `AppSecretsBridge.openPicker` (`:595-616`) allocates a never-reused `VaultUiRequests` code, launches ACTION_OPEN_DOCUMENT/TREE, binds one original request and guards Activity state. `finishPickerResult` (`:632-656`) reads ContentResolver InputStream on a worker into bounded owned bytes and hands them into the vault transaction. No normal plaintext file is needed.
- Wrong reuse: `ToolFiles.importFile` (`:141-169`) copies a chosen URI into `.partial` and persistent workbench `.input`/library files. Its persistent plaintext staging violates the SSH design for configs/private keys.
- `HftpBridge` (`:65-74`, `:116-140`) demonstrates directory selection and URI capabilities, but it persists read/write permissions for a running file service. SSH copy-only import should not inherit that persistence/write policy or keep external synchronization.
- `MainActivity.onActivityResult` (`:527-532`) currently routes only AppSecrets/HFTP/ToolFiles; there is no SSH import broker. A dedicated SSH import request must route here, fence stale result IDs and retain original cancellation/epoch/deadline/registration across picker/background/worker transitions. SAF cancellation before commit must retain the existing library; postcommit cancellation must produce the actual receipt.
- Directory traversal needs read-only DocumentsContract enumeration, bounded count/depth/size/paths and exact selected-root-relative names, no URI-to-local-path conversion, no root/all-files grant. Private bytes must never return through Flutter metadata or logs. Every buffer/candidate/pending request has explicit wipe ownership.

### Recommended bounded implementation sequence within the existing full task

1. Deliver an actual direct private-key path using the existing native key authentication and the same generated profile/trust/PTY machinery, while persisting selected private/public material and validated KeyBinding in the same encrypted manifest. This independently resolves the user's password-disabled direct server blocker; it is not a claim that config support is done.
2. In the same planned increment, add real config import/edit/alias selection backed by native catalog/evaluation and a typed effective-plan getter. A useful first configured path is Host/HostName/User/Port/IdentityFile with managed static Include, retaining unresolved diagnostics and all existing local-effect/trust policy checks. Preserve the complete R3/R6/R7 goals in the parent; do not mark jump/forward/complex path behavior complete merely because a direct path works.
3. Evolve the production validator on the same model into a complete explicitly supported subset: actual supplied CONFIG closure, typed resource references, actual private/public association, approved key/passphrase/account bindings and real known_hosts. Do not register partial reports as universal semantic validation or open complex restore.
4. Prepare actual key-only SSH interop and Android bridge/UI cases: user-selected import, host review, publickey SUCCESS/PTY, invalid/wrong passphrase/rejected key, config alias resolves exact endpoint/key, no fallback to password/direct/jump omission, cancellation and config replacement/late callbacks, TOTP/saved-password/trust preservation. Synthetic/JVM results do not prove the user's actual server or installed app.

This sequence is an engineering recommendation, not a reduction of the approved source requirements. If the deliverable is intended to cover arbitrary existing config, the currently retained full Options/unsupported connection capabilities make that substantially larger than adding a form field; the selected runtime surface must be explicit.

## External references

No internet or external service accessed. Findings are grounded in the repository's pinned OpenSSH 10.6p1 readconf integration, C/Java contracts and actual source. No personal `~/.ssh`, device, target server, real account or key was read or contacted.

## Related specs

- `.trellis/spec/backend/ssh-native.md` (config evaluator, catalog, virtual path and lifetime contracts)
- `.trellis/spec/backend/ssh-detached-config-managed.md`
- `.trellis/spec/backend/ssh-config-audit.md`
- `.trellis/spec/backend/ssh-config-resources.md`
- `.trellis/spec/backend/ssh-manifest.md`
- `.trellis/spec/backend/ssh-password-connect.md`
- `.trellis/spec/backend/app-secrets.md`
- `design/ssh-terminal.md`, `design/ssh-manifest.md`, `design/constraints.md`

## Caveats / Not Found

- No existing production SSH SAF importer, directory snapshot broker, config editor/alias-list page, Java finalized Options plan, or atomic file+new-KeyBinding metadata proposal was found.
- Exact private/public blob extraction and encrypted private-key/passphrase API reuse are being investigated by the parallel key-auth researcher; this note deliberately does not invent those APIs.
- The current user's password-disabled server behavior was supplied by the user; no network reproduction or authentication attempted.
- Existing uncommitted saved-password implementation was read and preserved. No product/spec/build/device changes or git operations were performed.

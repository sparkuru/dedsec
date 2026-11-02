# Research: import connection entries and select an independent managed key

- Query: Trace why desktop IdentityFile paths reject config import and identify the smallest product-specific parser/metadata/connector boundary for config entries that require an explicitly selected imported key at connection time.
- Scope: internal source and existing-test inspection only. No user config, device, private key, account, external network, build or git access; no implementation/spec edits.
- Date: 2026-11-02
- Authorization update during research: user explicitly approved “先导入连接项，连接时选择私钥”. Basic per-connection selection is now the requested behavior; no new saved host-to-key preference feature is required. Preserve PubkeyAuthentication and all nonIdentityFile trust/command/config constraints.

## Findings

### Root cause occurs at several independent layers

The supplied user-file observations were provided by the main agent; this researcher did not access that file. Source confirms that a quoted POSIX desktop absolute IdentityFile reaches `resource_state(kind=1, value, ...)`, which rejects any `/...` outside `/ctos-vault/` **before** returning a deferred row (`android/app/src/main/cpp/ssh_config_eval.c:226`–`:254`). Quoting does not bypass the actual parsed path policy. A correctly located `.ssh/config:<line>` PATH error therefore does not imply malformed config or a wrong file extension.

Changing a single import check is insufficient:

1. `SshImportProposal.create(config)` constructs a temporary CONFIG preserving `body`, then calls `SshConnectionMaterial.references` (`SshImportProposal.java:83`–`:94`). That prepares RESOURCE_AUDIT, runs the real parser and derives direct metadata references (`SshConnectionMaterial.java:317`). Strict import rejects every resource state except STATIC/DISABLED across the entire traversed Include closure (`:349`–`:356` in this checkout; look for the `Strict import covers` comment).
2. The complete **stored source and candidate validator** independently runs `SshManifestConfigInspector.inspectResourceAudit`, then verifies expected direct references for every `.ssh/` CONFIG (`SshPasswordManifestValidator.java:56`–`:104`). Its current `storedReferences` relaxes only a missing static IdentityFile for deletion, not arbitrary external/CWD/context paths.
3. The library computes a real native `DirectPlan`, rejects missing identities, and exposes only current managed keys whose path is among `plan.identityFiles` (`SshProfilesBridge.java:243`–`:263`). Any missing identity currently disables the host even if another imported key exists. A key-free config currently cannot be imported first and later become independently selectable through this path.
4. The Flutter host picker refuses `error`/`missingIdentityPaths`, intersects with `host.keyIds`, and autochooses one eligible key (`lib/ssh/library_page.dart:872`–`:922`). Both eligibility and missing-key copy embody the old path-binding behavior.
5. `SshPasswordConnector.prepareKeyPhrase` re-evaluates the plan, calls `requireIdentities`, and checks selected key path membership **before** decrypting/saving a phrase (`:367`–`:407`). `prepareKey` repeats these checks before building current account/trust state (`:310`–`:338`). Both gates must adopt the explicit independent selection together.
6. The native direct-plan finalizer calls `direct_path` for every effective IdentityFile (`ssh_config_eval.c:866`–`:910`). This expands/resolves actual paths and fails on desktop/CWD values independently of Java. Removing Java membership checks alone still cannot produce the direct target plan.

### Narrow native boundary: an explicit product identity-hint policy

The current generic resource inspector and generic direct planner have useful strict contracts and large independent regression suites. A product-specific mode/flag can preserve those APIs while enabling **only imported-config IdentityFile hints**, keeping the original CONFIG bytes. This is a feasibility recommendation for the main agent to freeze, not an implemented API.

- Add an explicit product parser policy at native handle preparation; do not let Dart supply a broad permissive validator flag. The original RESOURCE_AUDIT/direct APIs retain strict behavior. Relevant seams are `SshConfigEvaluator.prepareDetachedResourceAudit` / `prepareDirectDetached`, native `reserve_mode` / `reserveDirect` (`ssh_config_handle.c:174`–`:229`), and the evaluator's mode/direct-plan state.
- In product resource audit, a syntactically valid unavailable IdentityFile can yield a **non-authoritative hint** row with kind=1 and target=0 instead of a path error. This must not make external paths valid managed paths or send them into native IO. Other resource kinds, Include expansion/globs/cycles, unsafe command/trust policy and original limits remain strict.
- Keep native grammar validation of argument count, empty values, quoting and source location. The existing shared keyword/argv hook already has kind, CONFIG object, line and argument ordinal before `resource_state` (`ssh_config_eval.c:256`–`:283`; project readconf patch around `ctos_config_resource_line`). Hint text must be a bounded actual parsed argument; no filename-based key matching, trusted UI flag or fabricated graph target.
- In the product direct-plan path, retain actual effective Host/Match/first-value/additive rules and all current direct-option checks, but return explicit original IdentityFile hints without calling `direct_path` for their credential authority. Continue resolving `userKnownHostsFiles` through the strict `direct_path` path. Do not erase IdentityFile source text or reconstruct a config from HostName/User/Port alone.
- `ctos_config_direct_keyword` at `ssh_config_eval.c:165` preserves the current supported direct option allowlist. `finish_direct_plan:889` rejects unsupported jump/session/command/forward/certificate/HostKeyAlias and weak trust choices. `PubkeyAuthentication no` must still disable the selected-key connection; independent key choice does not override it. Existing `PreferredAuthentications publickey` handling should remain; supporting other methods/orderings is separate work.

Current `Resource` wire rows only contain six integers (`SshConfigEvaluator.java:385`–`:407`); current DirectPlan identity strings are all decoded as `VaultManifest.path` (`:241`–`:246`). Therefore desktop hint text cannot be put into the current `identityFiles` list without an explicit decoder/wire/API contract change. Use a distinctly typed bounded hint field/version or a separate product result; do not weaken `VaultManifest.path` globally. Preserve source object/line and argument ordinal if displayed/reviewed hints need provenance. Actual direct `Options.identity_files` and `identity_origin` already provide effective strings plus source file/line; generated default identities lack a real source origin and must not be falsely presented as explicit source lines.

Do not confuse a syntactically accepted original hint with a resolved file. The material graph must not contain `/home/...` in `FileObject.path` or `references`. Hint strings are protected public metadata (potentially containing personal path names), not a new file-read authority and not log/analytics content. Bound output size and reject over-limit values without silent truncation.

### Existing graph compatibility needs deliberate handling

Old valid CONFIG objects may already have static IdentityFile graph references. `SshManifestConfigInspector.validateResources` verifies every static target is typed and present in the parent's stored `references` (`:379`–`:388`), while the product validator compares expected reference sets exactly. An implementation that simply drops **all** identity reference edges from its new result will reject existing valid libraries, including generated `.ssh/ctos-manual`.

Two feasible compatibility shapes need one explicit choice in the design:

- Preserve provable existing STATIC identity edges as metadata, use target=0 only for unavailable/unusable identity hints, and stop using those metadata edges to choose credentials. This minimizes changes to existing valid source graph admission. An IdentityFile that happens to name the wrong managed type must be classified under the product hint policy rather than granting that wrong object authority.
- Alternatively derive a deliberately updated product reference model and migrate metadata in an authenticated same-bundle change. This is broader: source admission cannot require the new model before allowing the migration. Never disable all reference checking to get through it.

Static Include/trust/revocation/certificate obligations remain separate. Existing reference-refresh/import/private-key-delete paths must use the same chosen product policy or subsequent key import/deletion will make a previously accepted config unreadable. Original strict generic reports remain available for their own contracts.

### Existing explicit key capability and authentication can be reused

`ssh.configConnect` already receives exact `alias`, current config `binding`, `keyId`, username override and optional phrase flags (`SshPasswordConnectBridge.java:342`–`:371`; `lib/ssh/library_api.dart:458`). The host admits the request and looks up the managed key by ID under a fresh authenticated snapshot; it does not need a new frontend-controlled private path.

For independent selection:

- Library host rows can remain visible after a valid config-only import, with current target facts and actual hint metadata. “No imported keys yet” should explain the next action without claiming the config import failed. On a connection action, show the current imported keys with actual algorithm/fingerprint and require user selection; never guess a key from a basename, desktop path, key ordering or a previous Account declaration. Root has fixed this as per-connection selection, not a new persistent preference.
- Keep the exact `configBinding` freshness and original epoch/revision/deadline checks. Validate the explicit selected key exists and is currently supported, `plan.pubkeyAuthentication` is true, and username/host/trust/direct policy are valid **before phrase save, TCP or credentials**. Both connector preparation methods above need the same rule.
- `connectionProposal(... manual=false)` already leaves the imported CONFIG bytes untouched and only creates necessary host trust/account state (`SshConnectionMaterial.java:410`–`:444`). Keep its current KeyAccount record as credential-ownership evidence, not an automatic saved UI preference. Its identity binds actual host/port/username/key ID and actual key identity (`:82`–`:103`). No new host-key-selection preference table is necessary.
- `prepareKeyPhrase` already proves an encrypted selected key's actual public blob before phrase save, and `prepareKey` rechecks the source afterward. Retain saved/transient phrase, replacement invalidation and selected-key deletion behavior.
- `SshAuthenticationSelection.privateKey` freezes selected managed path, key identity, public blob and phrase entry under the actual role (`SshAuthenticationSelection.java:42`–`:59`). `AppSecrets` verifies the matching KeyBinding/KeyAccount/public blob under current target/connection (`AppSecrets.java:2768`–`:2784`, `SshConnectionMaterial.requireSelection`). The callback freezes selection before TCP (`SshPasswordConnector.java:248`–`:263`). Actual native key auth uses the selected public blob and guarded typed credential callback (`ssh_native_client.c:1095`–`:1145`), not a private-key path read from config.
- Public CONFIG/Include/trust views continue deriving only the stored graph (`AppSecrets.java:3012`–`:3022`, `:3119`–`:3124`). The native session requires its selected managed trust object in this view (`ssh_native_client.c:1715`–`:1728`). Selected private-key retrieval is a separate typed capability, so a nonresolved IdentityFile hint need not be made into a fake CONFIG reference just to authenticate.
- Preserve real host-key KNOWN/NEW/CHANGED/REVOKED policy, strict yes behavior, exact NEW writeback, actual offered key/KEX binding, explicit no-fallback key auth, PTY and service ownership. No USERAUTH algorithm change is required by this product behavior.

### Focused required verification

1. **Native product parsing:** config with no keys, multiple Host blocks and quoted desktop absolute IdentityFile imports as hints; actual effective host/user/port correct; `~/`, relative, foreign absolute, duplicate and none identity forms bounded and non-authoritative; old generic audit/direct still reject as before. Use synthetic paths only.
2. **Negative policy:** missing/wrong-type/escaping Include still fails; missing/nonmanaged UserKnownHostsFile and revoked-key dependencies still fail; inactive/nested unsafe commands and weak trust options remain rejected; PubkeyAuthentication no cannot send a key request. No file/network/exec effects during audit/preview.
3. **Graph compatibility:** existing generated manual and imported portable configs retain usable source admission; reimport/private-key addition/deletion and metadata refresh remain consistent; unrelated key/account/password/TOTP/trust bytes preserved; no source/config-body rewrite.
4. **Explicit selection:** config imported before key remains listable; after any imported key, user must select the intended key independent of source hint; mismatched/old/missing/replaced IDs and stale config bindings reject before phrase save/TCP; two keys and multiple aliases do not silently cross-select.
5. **Actual authentication:** selected clear/encrypted key succeeds through the existing host/KEX/CSAS path against a synthetic key-only endpoint; wrong key gives authentication failure without trying other keys/password; saved phrase belongs only to selected actual identity.
6. **Lifecycle/UI:** narrow/large-text hint display, no keys, picker cancel, background/lock/expiry/late result, explicit selection and guarded mutation receipt. Do not mark Android/JNI or real DocumentsUI passed from JVM/widget results.

Existing starting seams: `ssh_config_resource_test.c`, `ssh_config_eval_test.c`, `SshManifestConfigInspectorTest.java`, `SshConnectionRelationsTest.java`, `SshKeyDeletionTest.java`, key/config Flutter tests and the explicit Android synthetic SSH fixture. This investigation ran none of them.

## Files found

- `SshImportProposal.java`, `SshConnectionMaterial.java`, `SshPasswordManifestValidator.java`, `SshManifestConfigInspector.java` — import/source/candidate/reference refresh and exact credential relationship guards.
- `ssh_config_eval.c`, `ssh_config_handle.c`, `ssh_config_eval.h`, `SshConfigEvaluator.java`, readconf evaluator patch — native resource/direct modes, typed result contracts and parser provenance.
- `SshProfilesBridge.java`, `lib/ssh/library_api.dart`, `lib/ssh/library_page.dart` — current path-derived eligibility and explicit selection UI/API.
- `SshPasswordConnectBridge.java`, `SshPasswordConnector.java`, `SshAuthenticationSelection.java`, `SshNativeCallbackAdapter.java`, `AppSecrets.java`, `ssh_native_client.c` — exact key request, pre-save/pre-TCP checks and proof-bound existing authentication.

## Related specs

`.trellis/spec/backend/ssh-key-config-connect.md`, `ssh-config-resources.md`, `ssh-key-deletion.md`, `ssh-encrypted-key-connect.md`, `app-secrets.md`; frontend `app-secrets.md`, `type-safety.md`, `terminal-interaction.md`. The new product policy must explicitly supersede old path-eligibility rules without weakening generic native contracts or unrelated trust/dependency rules.

## External references

None consulted; all claims are based on the current checkout and its fixed local parser source. No user file or account was accessed.

## Caveats / Decisions

- Basic behavior and per-connection choice are now explicitly approved; do not ask again merely because this report was initially commissioned before approval.
- Preserve existing supported authentication-method policy. A future config password flow, keyboard-interactive/MFA, alternative PreferredAuthentications order or persistent default key would be separate scope; not prerequisites for this selected-key change.
- Existing reconnect targets retain a RAM-only key ID (`SshReconnectTarget.java:5`–`:33`). The main design should state how explicit user reconnect intent presents/refreshed selection while preserving existing lock/reconnect behavior; do not invent a new saved preference.
- Exact product mode names/wire version/hint limits and old graph compatibility strategy remain implementation-design choices for root. No implementation, test or platform success is claimed here; concurrent source edits may move line numbers.

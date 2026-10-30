# Research: managed SSH private-key deletion

- Query: Delete a selected managed private key, its paired public file, saved passphrase and dependent KEY Accounts in one reviewed transaction; preserve external originals and user CONFIG bytes, including Include files; keep other vault operations usable and reject affected connections precisely.
- Scope: internal source investigation only; no implementation, build, device, network or git operations.
- Date: 2026-10-30

## Findings

### Main-session contract decision

The main session confirmed the following during this research: tolerate only stored kind1/state5 while retaining strict config import/edit dependency rejection; block any effective plan with a missing identity; refresh all CONFIG references on key reimport using one actual aggregate native audit and original budgets; preserve all user CONFIG bytes; delete generated MANUAL only when owned by this key and unreferenced by retained CONFIG; use exact-source typed preview and once-only commit for atomic private/public/phrase/KEY Account deletion. Preview should list affected user CONFIG paths, with generated-manual removal separate. This resolves the policy choice noted below; implementation and verification remain with the main/implement agent.

### Existing graph and exact deletion mechanism

- `android/app/src/main/java/im/majo/ctos/SshBundleProposal.java:15` already defines DELETE; `:125` constructs an exact old-object-bound deletion. `:18` supports source-bound complete KeyBinding/Account replacement, and `:147` accepts exact removed passphrase entry IDs. `:197` rechecks original relationship and object bindings; `:215` removes only selected SSH_KEY_PASSPHRASE entries and independently copies retained objects. `:230` constructs and validates the complete resulting manifest. No store format or extra secret mutation API is required.
- `android/app/src/main/java/im/majo/ctos/VaultManifest.java:281` requires every persisted CONFIG reference path to exist. `:288` also rejects orphan phrase entries; `VaultSshMetadata.java:81` requires actual private/public files for every binding and `:100` rejects KEY Accounts whose credential binding disappeared. Thus removing only files is invalid.
- `android/app/src/main/java/im/majo/ctos/SshConnectionMaterial.java:124` narrows the production graph further: each binding has one unique private path, one unique paired public object at `private.path + ".pub"`, and no certificates. Generic metadata permits more, but production shape rejects public sharing (`:132`). Remove the selected binding and its exact pair, not arbitrary files with matching public identity.
- `SshConnectionMaterial.java:152` validates runtime KEY Accounts against the actual binding; delete every KEY Account with `credentialId == selectedBinding.id`. Preserve PASSWORD Accounts and all other KEY Accounts. `SshKeyPassphrase.java:17` derives the phrase entry ID from the binding ID, while `:38` checks exact ownership. Two files at different paths can share public identity but have separate binding/phrase IDs: delete only the selected binding's phrase entry.
- The key ID binds path plus public identity (`SshConnectionMaterial.java:387`), and config selection binds source/epoch/revision/alias (`:388`). Revision/source guards must still reject stale tickets. Deleting and later importing exactly the same identity at the same path reproduces the key ID; IDs are locators, not historical once-only tokens.

### Preserving CONFIG content requires a narrowly representable missing identity

- `SshPasswordManifestValidator.java:93` runs actual native resource audit for all CONFIG roots; `:99` compares each direct reference set exactly with stored metadata. `SshConnectionMaterial.java:301` computes Include and STATIC resource references but currently rejects every unresolved state except DISABLED. Therefore deleting a referenced key and merely pruning metadata currently causes whole-vault admission failure.
- Native `android/app/src/main/cpp/ssh_config_eval.c:226` already distinguishes valid absent static identity bases: it applies virtual-home, expansion, CWD, canonical path, control-byte and type policy before returning state5 at `:253`. Only IdentityFile/CertificateFile kinds can have that state. Other absent resources reject. Existing Java `SshConfigEvaluator.java:386` calls it `DEFERRED_KEY_RESOLUTION`; `:389` exposes only kind/parent/line/argument/state/target. There is no MISSING state or canonical-path field. State5 always has target0.
- A minimal viable stored-graph policy can tolerate **only kind1 IdentityFile + DEFERRED_KEY_RESOLUTION**, omit that nonexistent resource from references, and retain all other rejection behavior. This represents an incomplete stored identity declaration; it grants no connection/material admission. Continue rejecting kind2 certificates, DEFERRED_CONTEXT, DEFERRED_CWD, missing Include/known_hosts/revocation, wrong existing types and invalid paths.
- Deletion can replace every retained CONFIG that directly references the private or paired public path with identical type/id/path/identity/**content bytes** and a reference list excluding deleted paths. This is metadata adjustment, not a CONFIG text rewrite. Native source audit before deletion can identify each exact physical declaration by STATIC target plus source CONFIG path/line/kind/argument, including inactive branches and included/orphan CONFIG files. This is sufficient for a review showing affected references without introducing a Java parser or regex editing.
- Important policy limit: state5 does not retain prior type, canonical path or deletion history. Without a new tombstone/schema/wire it is impossible for future source validation to prove that an absent static IdentityFile was *formerly deleted*. Accepting kind1/state5 therefore makes bounded incomplete stored CONFIG representable in general. If existing import must continue refusing missing dependencies, keep separate strict import checking and tolerant stored-graph checking. Do not pretend a general missing declaration is proven to be a historical private/public object.

### Actual connection must reject every missing effective identity

- Native direct evaluation already returns canonical effective identity paths even if they are absent: `ssh_config_eval.c:866` resolves paths and rejects wrong **existing** types, but `:886` succeeds without proving existence. `:907` emits every effective identity. Java `SshConfigEvaluator.DirectPlan` decodes that actual ordered list at `:241`.
- Current `SshProfilesBridge.java:231` intersects that list with existing keys; it only adds SSH_KEY_MISSING when no eligible key remains (`:239`). `SshPasswordConnector.java:324` and `:404` only require the selected key to be a member. With A+B in config and A deleted, choosing B would silently bypass missing A unless these checks change.
- Add one shared Java helper that derives bounded missing identity paths from the **actual native DirectPlan**, requiring every effective path to resolve to a current supported private KeyBinding. The library should expose SSH_KEY_MISSING and the missing managed paths and disable that alias's key selection if any are missing; the connector must independently enforce the same predicate before save/TCP/credential use. A single error code is insufficient for a precise library explanation unless missing paths are also exposed to the UI. Do not parse CONFIG again in Java/Dart or fall back to password/manual mode.
- Active missing paths can come from Host/Match/Include/first-value processing and differ from physical declaration impacts. Use source audit for deletion preview and effective plan for per-alias connection refusal. An inactive missing identity need not block an unrelated alias whose actual plan does not consume it.
- `SshConnectionMaterial.plan(...):240` is the common native-plan wrapper used by library and connector. A helper adjacent to this wrapper minimizes drift; callers can retain the raw plan when they need to display its missing-path list. Connector checks must cover both prepareKey (`SshPasswordConnector.java:310`) and encrypted prepareKeyPhrase (`:366`), since the latter can persist an explicitly saved phrase before the later prepareKey pass.

### Reimport and generated manual configuration

- Restoring a deleted path changes its declarations from state5 back to STATIC. Any CONFIG whose reference metadata was pruned must have those references refreshed in the same key import proposal, or `SshPasswordManifestValidator.java:99` rejects the import. Current private import `SshImportProposal.java:42` changes only private/public/relations; only the CONFIG branch computes new references (`:83`). Refresh actual native direct-reference metadata for affected/all CONFIG objects under the original budget when adding material, preserving content exactly.
- Avoid rebuilding that refresh candidate from raw source entries plus new keys if replacing an encrypted key: `SshImportProposal.java:67` removes old phrase links/entries through its proposal, while its existing `candidate(...):107` copies all entries. Prefer applying a preliminary exact proposal including phrase removals, then auditing this owned candidate and appending CONFIG metadata replacements. This preserves source/candidate validity and avoids orphan old phrase failures.
- `.ssh/ctos-manual` is generated exclusively by `SshConnectionMaterial.connectionProposal(...):348`, with exact known-host/private-key references and fixed generated bytes. Import rejects this reserved path (`SshImportProposal.java:29`; `SshProfilesBridge.java:612`). It can be deleted when it belongs to the selected key **and no retained CONFIG has an inbound reference to it**. If a user config Includes it, deleting it breaks Include traversal; preserve its bytes and prune only deleted identity metadata instead. A simpler safe option is always retaining it as a missing-key config. Do not remove a generated manual config for another key.

### Minimal review/commit interfaces and lifecycle

- Suggested typed host interfaces: `ssh.keyDeletePreview(requestId,keyId)` -> metadata-only deletion preview; `ssh.keyDeleteCommit(requestId,ticketId,binding)` -> terminal `{deleted:true}` receipt; `ssh.keyDeleteCancel(ticketId)` -> cancel original preview. Names are suggestions, not existing APIs. Preview should carry selected key metadata, exact files/phrase-presence/account count, affected CONFIG declaration locations and whether generated manual config is deleted. No private bytes or passphrase/digest enters Dart.
- Reuse existing `SshProfilesBridge.Request` source/epoch/revision/deadline/validator context checks (`:62`), native-owned exact SshBundleProposal and worker. Adapt/generalize Ticket ownership instead of fabricating an import. The deletion preview must retain its original Request/context/proposal and hand authority off exactly once; commit gets a fresh request ID but inherits the original deadline and still checks the original owner.
- Existing import commit (`SshProfilesBridge.java:457`) supplies the blueprint: exact ticket/binding, original owner, unique CAS effect claim (`:477`), exact proposal identity (`:483`), existing adapter bundleRequest/commitBundle (`:486`), cleanup on every exit and final original epoch+1/revision+1 checks. After confirmed write with canceled/expired delivery it locks and returns VAULT_COMMIT_UNCERTAIN (`:511`). Preserve that distinction for deletion.
- `AppSecrets.java:2400` validates actual source and complete candidate, including final-SSH deletion (`:2412`), through the current registration and source-bound bundle machinery. Do not bypass this or add a separate direct store write.
- Cancel/lock/background/expiry/source change close the preview's retained proposal/context. Existing ticket cancellation (`SshProfilesBridge.java:540`) keeps commit-owned cleanup alive. Flutter should follow the existing awaited mutation/acknowledgement pattern in `lib/ssh/library_page.dart:581` and `:650`, with preview confirmation before commit and protected terminal receipt even after late cancellation. Do not dismiss the protected route while durable outcome remains unresolved.

### Authenticated session boundary

- `.trellis/spec/backend/ssh-background-session.md:30` requires actual SUCCESS then retirement of auth providers/view/secret ownership before established shell publication. An established shell is independent of the old vault lease (`:38`); deletion of stored material is not a remote session revoke or remote authorized_keys edit.
- New/pending connections must be fenced by deletion's epoch/revision change and exact selection checks. Existing bridge connecting guard is `active != null || mutating != null` (`SshPasswordConnectBridge.java:65`); preserve the current guard unless implementing a separately reviewed change. A successfully authenticated, retired service session may continue under existing service ownership; any later reconnect resolves the current library and fails/requires reselect if the key is absent. No automatic Stop, reconnect, or command replay is required.

### Minimum verification cases for implementation

1. Unreferenced clear/encrypted key removal; exact paired public/binding/phrase/KEY Accounts removed; password/TOTP/other keys/trust retained.
2. Direct and Include CONFIG references: bytes unchanged, metadata pruned, actual native source validation still admits unrelated vault use. Preview includes inactive and included declarations.
3. A+B effective identities with A deleted: alias visibly SSH_KEY_MISSING with A's managed path, B cannot silently connect. Unaffected aliases still resolve normally.
4. Generated manual for deleted key with/without inbound Include, manual for another key retained.
5. Reimport same/different public identity at formerly deleted path repairs direct references atomically; old phrase and Accounts never resurrect. Same-public key at another path remains untouched.
6. Wrong/stale/replayed ticket or source/key replacement; original deadline/registration/foreground cancellation; preview decline leaves data untouched; once claim; confirmed versus uncertain late write.
7. Malformed/wrong-type/dynamic/CWD/certificate/missing Include/known_hosts paths still reject; only bounded absent static IdentityFile gets tolerant stored representation.
8. Pending connection is invalidated by source change; established session behavior remains the existing retired-auth service contract.

## Files found and related specs

- `.trellis/workflow.md` — persistent task research and package-spec workflow.
- `design/README.md`, `design/plan.md`, `design/constraints.md` — current 1008/encrypted-key progress, scope, authorization and evidence boundaries.
- `.trellis/spec/backend/ssh-config-resources.md` — exact CSCRv1 states, static path policy, physical declaration audit, no guessed suffix/source lookup.
- `.trellis/spec/backend/ssh-key-config-connect.md` — exact library/config selection, single supported graph, actual direct plan, guarded bundle and no fallback.
- `.trellis/spec/backend/ssh-encrypted-key-connect.md` — exact phrase ownership, replacement removal, original tickets, save/forget and uncertainty.
- `.trellis/spec/backend/ssh-background-session.md` — established transport ownership and authentication retirement.
- `.trellis/spec/backend/app-secrets.md`, `.trellis/spec/frontend/app-secrets.md` — original owner/guard/budget/once effect and protected terminal receipts.
- Java/C/Flutter source paths and entry points are cited above; these source files were read only.

## External references

None consulted. This is bounded internal source research against the current checkout; native behavior described above is source evidence, not a fresh execution result.

## Caveats / Not Found

- No production changes, tests, builds or device operations were performed for this research.
- Native resource rows do not expose missing canonical paths or deletion provenance. Do not document such data as available.
- Permitting state5 in stored validation needs an explicit narrow product contract update by the main/implement agent; existing strict import behavior can be retained separately. Rejecting referenced deletion is the alternative if representable incomplete stored identity declarations are declined.
- This research does not authorize deleting any actual user key, external original, remote key or existing device data. User-facing deletion must be an exact reviewed library transaction.
- The existing checkout contains uncommitted encrypted-key work; all source/spec changes were left untouched. Referenced line numbers reflect this read and may move during concurrent implementation.

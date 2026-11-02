# Research: next approved SSH increment after private-key management

- Query: After committing the accepted encrypted-key/passphrase/deletion/UI delivery, identify one coherent next increment already covered by the 1008 parent requirements; compare directory import, direct-config extensions, trust recovery and encrypted recovery.
- Scope: internal source and project-contract research only; no builds, device access, git operations or production changes.
- Date: 2026-10-30

## Findings

### Recommendation for root decision

Recommend **SAF directory batch import of the currently supported direct-connection material set**, completed through one immutable review and one atomic bundle commit. It should preserve relative CONFIG/Include/key structure and actually leave the resulting aliases connectable through the existing connector. A folder picker or an uncommittable file list alone is not delivery.

This is an incremental R7 delivery, not a claim that all R7 formats or the whole parent are complete. Keep the current connection/trust policy: supported software private keys (including the now-delivered encrypted containers), their actual derived public companions and CONFIG/Include files; preserve existing trust and other vault data. Incoming known_hosts, certificates and unsupported objects must be explicitly shown and refused or excluded by the user's reviewed selection, never silently imported, ignored or made trusted. Root must freeze that selection/role policy in design before implementation. If the intended next delivery must accept every object in an arbitrary `.ssh` tree including trust/certificates, this recommendation is too narrow and should not be labelled complete whole-directory support.

Why this first: R7 explicitly approves whole-directory intake and preserved reference structure (`prd.md:96`), while the shipped UI still has only one-file private/config imports. The new encrypted-key and exact-bundle mechanisms make a supported batch closure a direct continuation of accepted user-facing work. It avoids changing native transport/auth/session behavior or inventing a new trust record grammar. There is no current explicit ordering among these remaining increments in the latest plan: older milestone paragraphs are historical and not a fresh sequencing decision.

### Current directory-import gap and reusable mechanisms

- `android/app/src/main/java/im/majo/ctos/SshProfilesBridge.java:143` exposes only library, one-file import/unlock/commit/cancel, config edit and key deletion methods. `:300` accepts only `privateKey`/`config`; `:307` launches `ACTION_OPEN_DOCUMENT`, not a tree picker. `:343` reads one display name and one stream into a bounded owned buffer; `:632` enforces the per-file bound and original request checks.
- `lib/ssh/library_page.dart:1014` offers `导入私钥` and `:1022` offers `导入配置 / Include`; `lib/ssh/library_api.dart:355` accepts one kind/path. No directory/batch model is currently exposed.
- `SshImportProposal.java:33` enforces managed `.ssh/` paths; `:34` refuses reserved HOSTS/MANUAL. `:51` performs actual phrase-aware native derivation; `:64` creates PRIVATE plus canonical PUBLIC; `:69` updates exact bindings, removes the replaced key's saved phrase and removes dependent KEY Accounts when identity changes. Reuse these semantics, not a second independent key format path.
- `SshImportProposal.java:83` validates a CONFIG against its prospective supplied collection with actual native reference audit. Sequentially committing individual files is the wrong directory implementation: root/Include dependencies can be incomplete between writes, and cancellation would leave a half-imported set.
- `SshImportProposal.java:99` already refreshes CONFIG references after material changes through a prospective audit. `SshConnectionMaterial.java:121` validates the supported graph, exact private/public pairing and fixed managed trust object; `:144` permits the one canonical `.ssh/known_hosts` object, not arbitrary extra trust paths.
- `SshBundleProposal.java:118`/`:121`/`:125` provide exact ADD/REPLACE/DELETE changes. `SshProfilesBridge.java:505` demonstrates source-bound ticket commit with original owner/deadline, kind separation, once effect and uncertain-write handling. Directory import needs one owned batch proposal under the same lifecycle, not a loop of `importCommit` calls or a live tree URI reread after review.

### Smallest coherent acceptance boundary

1. A user-selected tree is mapped to stable managed relative paths under an explicitly described root mapping. No continued synchronization or persistent URI authority is needed. Enumerate/query/read under bounded counts/depth/bytes and original cancellation/deadline; reject duplicate/escaping/ambiguous paths and provider failures without modifying the vault. Provider calls remain synchronous: post-return checks do not promise hard interruption of a stuck provider.
2. The reviewed selected set includes a valid actual CONFIG/Include/private-key closure; missing dependencies, unsupported declarations and unsupported selected object types prevent commit. Do not classify private/config authority from filename suffix alone. Supplied public files require actual parsed equality with the derived identity or explicit refusal; they cannot silently replace the canonical proof.
3. Encrypted keys unlock through host-owned snapshot bytes, per-key exact identity/ticket binding and the original finite review lifetime. Import phrases remain transient; do not renew authority on retry or persist them implicitly. Root should explicitly choose the bounded multi-key review budget rather than accidentally inherit/renew one-file budgets per key.
4. One preview lists paths, types, actual public fingerprints, add/replace/unchanged/conflicts and explicitly excluded unsupported/trust objects. Replacements require explicit confirmation. Existing known_hosts, TOTP, unrelated keys/accounts/passwords and external originals remain unchanged. Reimport must apply the already-approved exact phrase/account replacement rules; truly unchanged objects should not be presented as harmless while secretly resetting saved phrases.
5. One final source-bound SshBundleProposal validates the entire candidate with native aggregate audit and common quotas (128 managed files, per-file 1 MiB, total 8 MiB, complete manifest 12 MiB). Derived public files, existing retained files and generated trust must count toward the candidate limits. Commit once, or leave the original vault intact; stale/cancel/background/expiry/late-write outcomes preserve the established lifecycle and protected receipt.
6. Real validation: nested Include + multiple keys + encrypted key, root/child conflict and malformed/missing/over-limit cases; cancel/stale/replay/cross-kind/no partial write; source provider changing after review does not change the owned snapshot; actual native candidate and subsequent direct alias login. Flutter covers reviewed selection, per-key unlock, conflicts, final receipt and narrow/large-text access. Real DocumentsUI/tree-provider/device and synthetic SSH execution require their own concrete current execution scope; local implementation/research does not authorize real accounts.

### Comparison with per-host trust management / changed-host recovery

There is an immediate R5 gap worth doing, but it is **not an existing ready-made delete operation** and need not be a prerequisite for a directory subset that preserves current trust.

- `prd.md:94` already requires fingerprint-change refusal and a verification/recovery path. `SshHostKeyAuthority.java:175` returns KNOWN proof, `:179` allows only exact NEW approval, `:185`/`:187` unconditionally refuse CHANGED/REVOKED. `lib/ssh/api.dart:389`/`:390` only projects fixed changed/revoked refusal messages. The library APIs above expose no trust list/forget/recovery UI.
- App-generated normal trust writes are literal rows from the real host key: `android/app/src/main/cpp/ssh_hostfile_issuer.c:383` uses upstream `ctos_hostfile_write_entry(..., lookup_host, ..., host_key, 0)`. However **stored admission is broader than generated literal rows**: `SshPasswordManifestValidator.java:60` accepts PARSED KNOWN_HOSTS; `ssh_material_inspect.c:347` explicitly accepts valid `|1|` hashed tokens, and `:357`/`:374` delegates row syntax/key parsing to upstream `hostkeys_foreach_file`. There is no production shape restriction proving all accepted rows are one generated literal endpoint. Wildcard/shared-selector/marker semantics therefore cannot be dismissed as impossible.
- Existing material wire is aggregate: `SshManifestInspector.java:93` checks known-host kind/count, `:106` publishes record count, and `:108` deliberately exposes no key digest for a KNOWN_HOSTS aggregate. No inspected production API enumerates per-row endpoint/algorithm/fingerprint/marker or returns an exact row-removal candidate.
- Existing managed CAS is bound to a live role and **NEW** KEX evidence: `SshTrustProposal.java:77`, `SshManagedProvider.java:78`, and `SshVaultAdapter.java:217` require exact NEW evidence/host proof/snapshot. Never fabricate NEW evidence or repurpose that path as an offline trust editor.
- A coherent trust slice would be a protected public trust review plus **explicit forget of the exact old endpoint's ordinary trust records**, preserving all unrelated selectors/comments and all revocation records, then a fresh normal connection/actual NEW fingerprint approval. It should never resume or approve the previously failed connection and should never directly replace a CHANGED key from an unverified string.
- That trust slice needs a new native parsed record projection/rewrite or equivalent pinned-parser-backed candidate operation, plus a guarded management transaction/ticket. A Java line split/regex delete is insufficient: shared host lists or wildcards can affect other endpoints, hashes cannot reveal a hostname, and deleting an `@revoked` row would weaken a distinct policy. For unsupported broad/shared/marker cases it must explicitly refuse the management operation, not silently remove an entire matching line. Root owns the precise supported management scope and whether to prioritize this instead.

### Why not the other remaining increments first

- **R6 major direct options / ProxyJump / forwarding:** `ssh_config_eval.c:889` finalizes only the strict direct plan; `:895`–`:898` refuses jump, RemoteCommand, forwarding, certificates and HostKeyAlias. `SshPasswordManifestValidator.java:124` separately rejects jumps/commands/forwards in the password-generated path. These features need new consumed-plan/transport/channel/lifetime/exposure-confirmation contracts; merely removing a refusal is unsafe and exceeds the nearest bounded management continuation.
- **R10 full encrypted SSH recovery:** `AppSecrets.java:854`–`:858` requires both an actual validator and registered SSH restore provider when source or target contains SSH. `:703` defines provider registration, but source search found only that definition, no production registration call. Existing supported graph admission is not full trust/account/certificate restore approval. This is broader than directory intake and should not be opened by attaching a permissive reviewer.

## Files found

- `design/plan.md`, `design/README.md`, `design/constraints.md` — milestone status, landing and execution boundary; no newly selected explicit next-feature ordering.
- `.trellis/tasks/10-08-ssh-terminal/prd.md` — approved R5/R6/R7/R9/R10 scope and still-open joint acceptance.
- `.trellis/tasks/10-08-ssh-terminal/design.md` — managed-source plan and common bounds (`:268`), native/forwarding/trust boundaries.
- `.trellis/tasks/10-08-ssh-terminal/implement.md` — completed recent increments plus historical sequence; not proof remaining parent ACs are complete.
- `SshProfilesBridge.java`, `SshImportProposal.java`, `SshBundleProposal.java`, `SshConnectionMaterial.java` — existing owned import/proposal/candidate mechanisms.
- `lib/ssh/library_api.dart`, `lib/ssh/library_page.dart` — current one-file UI/API and strict public metadata boundary.
- `SshHostKeyAuthority.java`, `SshTrustProposal.java`, `SshManagedProvider.java`, `SshManagedKnownHostsProvider.java`, `SshVaultAdapter.java` — actual NEW-only trust evidence/CAS; no standalone row editor found.
- `ssh_material_inspect.c`, `ssh_hostfile_issuer.c`, `SshManifestInspector.java` — real known-host parser and aggregate-only public material report.
- `ssh_config_eval.c`, `SshPasswordManifestValidator.java`, `AppSecrets.java` — current direct refusal and recovery registration gates.

## Related specs

- `.trellis/spec/backend/ssh-key-config-connect.md` — native single source, explicit import kinds, no URI reread/plaintext staging or silent trust/fallback.
- `.trellis/spec/backend/ssh-encrypted-key-connect.md` — actual encrypted import proof, original-ticket unlock and phrase replacement rules.
- `.trellis/spec/backend/ssh-key-deletion.md` — exact graph edits, stored incomplete identities vs strict new-config closure, metadata refresh and ticket/uncertain outcomes.
- `.trellis/spec/backend/app-secrets.md`, `.trellis/spec/frontend/app-secrets.md` — original source/registration/budget, once mutation and protected terminal receipts.
- `.trellis/spec/backend/ssh-config-resources.md`, `.trellis/spec/backend/ssh-detached-config-managed.md` — actual resource paths and aggregate native audit budgets.
- `.trellis/spec/frontend/type-safety.md`, `.trellis/spec/frontend/quality-guidelines.md` — typed batch preview boundary and representative UI vs actual Android evidence.

## External references

None consulted. This is current-checkout research; existing project links to Android SAF and the pinned OpenSSH design are not claimed as newly verified online documentation. No new library or API recommendation relies on an unstable external version.

## Caveats / Not Found

- Root retains the product/architecture decision. The recommendation is an approved-scope incremental subset, not authority to silently narrow the full R7 objective or overwrite user trust.
- No explicit next-feature order was found after the current accepted increment. User acceptance/commit updates may be written by the main session concurrently; older pending-human-review text is historical during this research.
- No new code, tests, builds, device/network or git operations were performed. The source line references describe the current read and may move.
- Current user continuation supports choosing/implementing an approved local slice; it does not independently authorize personal SSH accounts, arbitrary provider data, remote trust changes or external services.

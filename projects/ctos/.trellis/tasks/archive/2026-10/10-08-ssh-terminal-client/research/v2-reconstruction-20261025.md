# Research: persisted v2 metadata reconstruction and restore closure

- Query: Locate every production reconstruction/loan/transport path affected by appended exact-ID key-binding and account records; recommend the smallest compatible schema foundation and determine restore review coverage requirements.
- Scope: internal, read-only source investigation. Only this research file is written. No builds, runtime, network, devices, accounts, git operations or delegation.
- Date: 2026-10-25
- Active task: `.trellis/tasks/10-08-ssh-terminal-client`.

## Findings

### Files and existing contract

| File | Purpose and relevant anchors |
| --- | --- |
| `android/app/src/main/java/im/majo/ctos/VaultManifest.java` | Entire authenticated plaintext schema, two-list construction, decode, encode, validation and owned-secret cleanup (`:21-259`). |
| `android/app/src/main/java/im/majo/ctos/AppSecrets.java` | Source/candidate/reviewer loans, four mutation families, restore preview/commit, export, rekey, verification and readback. |
| `android/app/src/main/java/im/majo/ctos/SshBundleProposal.java` | File-only edits, exact old-object bindings and complete candidate reconstruction (`:165-198`, `:221`). |
| `android/app/src/main/java/im/majo/ctos/SshRestoreReview.java` | FILE/CREDENTIAL/TRUST review projection, exact old-row coverage, canonical review binding and private credential display tokens (`:17-267`). |
| `android/app/src/main/java/im/majo/ctos/VaultOperation.java` | Opaque capability with envelope identity/generation/snapshot binding (`:7-78`); contains no manifest copy or schema serializer. |
| `android/app/src/main/java/im/majo/ctos/VaultCodec.java` | Bounded text/byte framing, strict UTF-8 reading and wipeable serialization buffer (`:17-49`). |
| `android/app/src/main/java/im/majo/ctos/VaultEnvelope.java` | Independent envelope version, authenticated encryption and plaintext size cap (`:18-26`, `:46-65`, `:85-108`). |
| `.trellis/tasks/10-08-ssh-terminal-client/research/identity-authority-model-20261023.md` | Prior source-grounded distinction between v1 phrase/target declarations and absent persistent graph/account authority. |

The workflow, current task artifact tops, project design landing/plan/constraints, backend persistence/app-secrets indexes and the prior authority research were consulted. This report records source evidence and recommendations, not implemented behavior or validation results.

### Existing v1 framing and compatible v2 minimum

`VaultManifest.java:74-80` has only the entries/files constructor and `empty()`. Decode requires leading int1 (`:87`), accepts entries then files in order, rejects any trailing bytes (`:124`), and reconstructs through that constructor (`:125`). Encode always writes int1 (`:139`) followed by the same ordered objects (`:140-164`). Existing entry/file enum ordinals must remain unchanged.

The minimum compatible design is:

1. Retain the two-list constructor and `empty()` as v1. Introduce explicit v2 construction and a retained encoded-version field. Never upgrade on decode, unlock, read, export, loan, mutation or rekey.
2. Encode v1 exactly as today, without appended empty counts. Encode v2 with the existing ordered entry/file sections followed by bounded, ordered key-binding and account sections. Decode either exact version and reject unknown versions, malformed/extra sections, trailing data and excessive total plaintext. Preserve order on round trip; do not sort or deduplicate silently.
3. Introduce immutable metadata-only records: key binding ID plus exact PRIVATE_KEY file ID and optional exact PUBLIC_KEY, CERTIFICATE and SSH_KEY_PASSPHRASE IDs; account ID plus opaque targetIdentity, separately declared actual host/port/username, and a closed auth kind selecting exactly one SSH_PASSWORD entry ID or key-binding ID. Empty optional fields need one canonical representation. Do not encode a secret again in metadata.
4. Add a source-preserving reconstruction method such as `source.withObjects(entries, files)`. It must retain encoded version and both metadata lists, revalidate against new objects, and leave ownership/cleanup of cloned secret arrays with existing callers. It must not close the source or share newly mutable secret arrays accidentally.
5. Explicitly bound new list counts and field byte lengths before allocation/iteration. The existing caps are 1024 entries, 128 files, 1MiB/file and 8MiB total file data (`:22-25`); final v2 caps are a main-session contract decision. Overall envelope plaintext cap must continue to apply. `VaultCodec.readText:29-39` rejects malformed UTF-8/NUL, but `writeText:47-49` uses Java replacement encoding. Validate new Java-created text with strict UTF-8 encoding rather than accepting an encode/decode semantic change.

Structural checks should resolve typed IDs, reject duplicate record IDs, dangling references and invalid auth cardinality. Use one global ID namespace for entries/files/new records, or explicitly change all composite-key contracts; extending the current global uniqueness rule is simpler (`VaultManifest.java:175-178`, `:196`). Decide explicit one-private/one-phrase ownership/cardinality before coding: a small initial rule of at most one binding per private and at most one selected owner per phrase avoids recreating v1 ambiguous ownership. Optional public/cert objects can remain unbound; a foundation must not claim exhaustive relationship coverage merely because its declared edges resolve. Account host/username are independent declared fields; validate syntax/size without DNS/network/canonicalization or parsing a v1 target suffix.

Current phrase validation requires only *some* matching private identity (`VaultManifest.java:212-217`); duplicate private identities/phrases remain valid v1. Preserve v1 validation unchanged. If v2 adds exact-ID authority, define whether existing `key:` phrase identity is also required to agree; retaining that legacy consistency check is conservative, but it is not mathematical proof. Do not infer account host/port/username from strings such as `target:user@host:22`. The common nonTOTP regex permits both `target:` and `key:` today (`:185-187`), so tightening v1 password namespace would break backward compatibility.

Reference resolution proves declared structure only. It does not prove private/public/cert mathematical agreement, successful phrase decryption, password validity, account reachability, trusted host keys, certificate principal/time/revocation or whole-library admission. Do not register a provider, open restoration or change the live connector from this foundation.

### Exact production reconstruction sites requiring edits

All production constructor calls were searched under `android/app/src/main/java`. Only these four mutation returns reconstruct existing objects through the two-list constructor:

| Site | Existing behavior | Required preservation behavior |
| --- | --- | --- |
| `AppSecrets.java:1367-1390`, return `:1385` (`totpCandidate`) | TOTP save/add/delete/rename clones retained entries and all files, then constructs two lists; catch wipes clones. | Preserve version and graph/accounts, including pure-TOTP v2. Keep existing Throwable cleanup and independent secret ownership. |
| `AppSecrets.java:1678-1699`, return `:1694` (`sshEntryCandidate`) | Replaces/removes selected exact credential ID/target; clones other entries/files. | Preserve version/metadata and validate new exact references. Deleting a referenced password/phrase must fail until an atomic metadata edit exists. Replacing same-ID data does not prove new secret correctness. |
| `AppSecrets.java:1952-1974`, return `:1969` (`trustCandidate`) | Clones all entries/files and replaces only exact managed known_hosts body after base-digest check. | Preserve version/metadata without changing trust CAS lifecycle or caller cleanup. |
| `SshBundleProposal.java:165-198`, return `:193` (`applyTo`) | File-only add/replace/delete, clones all credentials and retained files, then validates complete candidate. | Preserve version/metadata. Deleting/retyping/reidentifying a graph-referenced object must reject dangling/type-invalid edges; never prune metadata, select another file or downgrade to v1 silently. |

The other production constructor calls are `VaultManifest.empty():80` and decoder `:125`. No constructor/reconstruction exists in VaultOperation, VaultCodec, SshRestoreReview or the secretsFixture application sources. The decoder must select the retained version explicitly; keeping its current generic two-list return would discard v2 metadata.

`SshBundleProposal.objectBinding:221` binds old file fields/content only. This is appropriate for a file-only proposal if complete source ciphertext/candidate validation retains the graph; it must not be described as a graph edit approval. A future graph edit needs its own exact request/binding rather than overloading CONFIG path references.

### Every production decode/loan path and unchanged-byte gates

All direct `VaultManifest.decode` callsites are in `VaultManifest` itself and `AppSecrets`:

| AppSecrets anchor | Path |
| --- | --- |
| `:688-689`, `:696-697` | Restore reviewer source/target loans, then fresh canonical manifests for binding. |
| `:757` | Preview validator loan. |
| `:894` | Session manifest read/decode validity. |
| `:1047`, `:1051` | TOTP read source and validator loan. |
| `:1245`, `:1269` | TOTP mutation source and candidate/source validation loan. |
| `:1566`, `:1579` | SSH credential mutation source and validation loan. |
| `:1827`, `:1839` | Trust CAS source and validation loan. |
| `:2085`, `:2098` | Bundle source and validation loan. |
| `:2373`, `:2375` | Typed SSH secret read source and validator loan. |
| `:2589`, `:2591` | SSH public-file source and validator loan. |
| `:2949`, `:2959` | Password verification/Verified owner and encrypted candidate readback. |

These paths need no independent graph copying when decode faithfully preserves v2. `requireUnchangedLoan:766-769` re-encodes the loan and compares exact bytes with authenticated plaintext. Consequently version/order/metadata preservation is a security requirement for every loan, not just serializer aesthetics. New metadata collections must be immutable or tampering must alter encoding and fail this gate. Existing `Verified:448-465` owns and wipes key/plaintext/manifest; new records should contain no second secret buffers.

Candidate encode sites are `AppSecrets.java:1343` (TOTP), `:1644` (SSH entry), `:1910` (trust), `:2136` and `:2183` (bundle preview/commit). All flow through the four candidate builders above. Complete source/candidate validation remains necessary; schema validation alone cannot replace providers. `beginSshBundle:2132-2140` and `commitSshBundle:2179-2189` decode source, apply proposal, encode complete candidate and validate its exact loan.

Non-reconstructing persistence paths:

- Create (`AppSecrets.java:575`) calls v1 `empty().encode()`; keep it v1 absent explicit product migration.
- Export (`:605-617`, especially `:615`) returns the verified existing envelope bytes. Preserve exact v2 envelope just as v1.
- Rekey (`:620-640`, especially `:631-633`) encrypts exact authenticated `verified.plaintext` under fresh credentials; no manifest reconstruction.
- Import commit (`:797-824`, especially `:819-821`) encrypts exact pending source plaintext. This preserves metadata bytes but does not compensate for incomplete review semantics.
- Readback (`:2957-2961`) decrypts, structurally decodes and compares exact expected plaintext. V2 support belongs in decode, not a lossy projection.
- VaultOperation (`:59-78`) binds the complete envelope, so appended authenticated metadata is already included indirectly in its snapshot identity; adding unrelated record fields to this opaque capability is unnecessary for storage preservation.

### Restore MUST remain closed for v2 in this foundation

`SshRestoreReview.Kind` has only FILE/CREDENTIAL/TRUST (`:21`). Its expected coverage (`:218-222`) enumerates all files and nonTOTP entries, never plaintext version, key bindings or accounts. `bind:168-179` enforces exact equality only for those old rows. Coverage keys are side+objectId (`:52`), which reinforces the simplest globally unique new-ID rule for any eventual extension. Current MAX_COVERAGE is only `2*(MAX_ENTRIES+MAX_FILES)` (`:17`).

Canonical review binding (`SshRestoreReview.java:233-257`, domain `ctos-ssh-restore-v1`) includes old coverage/trust/conflicts and source/target cipher digests. Those cipher digests bind all encrypted metadata against substitution, but do not show the user the account/key-edge effects. A legacy reviewer can return identical rows for manifests differing only in appended metadata and pass existing coverage checks. Thus byte authentication and old-row exactness do not establish a complete review of v2 meaning.

Smallest safe contract: explicitly deny restore preview for **either source v2 or existing target v2**, including empty/pure-TOTP v2, and defensively reject v2 in `SshRestoreReview.bind`. Place AppSecrets denial before the `hasSsh` branch (`AppSecrets.java:682`) and before invoking validators/reviewers on v2. Current `hasSsh:550-551` sees only files/nonTOTP entries; `validatePreviewLoan:755` also early-returns for pure TOTP. A bind-only denial misses no-SSH paths. Target-only denial matters because replace import removes target metadata even if source is v1.

Opening v2 restore later requires explicit key/account review kinds and full canonical metadata row bindings/projection, source and target coverage/removals/conflicts, revised bounds/domain/version and consent tests. That work exceeds a storage-schema foundation. Existing absence of a production SSH reviewer is useful defense but must not be the only reason v2 appears safe: tests and future registrations can otherwise exercise an incomplete review.

### Tests and fixtures: preserve legacy intent; add v2 preservation vectors

Production-style derived constructors in tests deserve special attention: `SshManagedKnownHostsProviderTest.java:789` reconstructs `verified.manifest.entries` with changed files; it would lose v2 if reused for v2 fixtures. `SshRestoreTest.java:106` intentionally projects credentials-only from an existing manifest; keep that legacy-v1 test intentional rather than mechanically replacing every constructor. `SshBundleTest.java:231` similarly builds a minimal legacy projection.

The exhaustive direct-constructor search found these unit-test files (paths below are under `android/app/src/test/java/im/majo/ctos/`):

- Core/lifecycle: `VaultTest.java:358-372,707,720`; `TotpTest.java:47`; `TotpReadValidationTest.java:71`; `TotpMutationValidationTest.java:40`; `SshEntryMutationValidationTest.java:80`; `SshTrustValidationTest.java:50,275`; `SshPublicReadValidationTest.java:63`; `SshSecretReadValidationTest.java:66`.
- Restore/bundle: `SshRestoreTest.java:106,392`; `SshPreviewValidationTest.java:474`; `SshBundleTest.java:56,231`; `SshBundleValidationTest.java:438`; `SshManagedKnownHostsProviderTest.java:789,821`; `SshProductionBridgeTest.java:1353`.
- Inspection/config fixture factories: `SshManifestConfigInspectorTest.java:107,146,191,295`; `SshManifestConfigAuditTest.java:34,223`; `SshManifestConfigResourceAuditTest.java:35`; `SshManifestInspectorTest.java:74` and its assorted malformed/ownership fixtures; `SshManifestKeyAssociationInspectorTest.java:70` and pair/source/candidate fixture constructors; `SshManifestPrivateMaterialInspectorTest.java:71` and ambiguity/limits fixtures; `SshManifestCertificateInspectorTest.java:58` and mixed/limits fixtures.

Android direct-constructor fixtures (package root `im/majo/ctos`): `androidTest/AppSecretsLifecycleTest.java:61,65`; `androidTestSecretsFixture/SshPasswordAuthPlatformTest.java:505`; `SshFileViewPlatformTest.java:72`; `SshManifestConfigInspectorPlatformTest.java:39`; `SshManifestConfigAuditPlatformTest.java:41`; `SshManifestConfigResourceAuditPlatformTest.java:53`; `SshManifestInspectorPlatformTest.java:44`; `SshManifestKeyAssociationPlatformTest.java:55`; `SshManifestPrivateMaterialPlatformTest.java:55`; `SshManifestCertificatePlatformTest.java:32`; `TotpPlatformTest.java:90`. Existing two-list constructor should keep all of these v1; no mechanical migration is needed.

Other decode/empty consumers include `VaultExportWriterTest`, `VaultStoreTest`, `SshPublicFileLeaseTest`, UI-flow/platform config tests and current-manifest helpers. They inherit decoder support and should retain their old v1 fixture baselines. `SshRestoreTest.coverage/addCoverage:89-100` and its synthetic reviewer `:66-87` cover only old object kinds; do not extend that helper to pretend metadata review is implemented.

High-value new tests are: independent known v1 bytes remain identical; v2 round trip/version/ordering/strict framing; each new count/reference/type/auth/cardinality bound; default constructor/create remain v1; decoded metadata cannot be mutated; exact loan checks include metadata; all four mutation families preserve v2 and unrelated records; referenced deletion/retype fails without store effect; unreferenced legacy changes still work; rekey/export preserve exact plaintext/version; source-v2 and target-v2 restore reject before callback/store for mixed, pure-TOTP and empty variants. Verify the denial and existing v1 restore success/error/lifecycle tests together.

### Minimal implementation ownership partitions

1. Schema owner: `VaultManifest.java`, an optional metadata-only companion, focused pure schema tests. Own the final constructor/factory/rebuild API and encoded-format contract first.
2. Mutation/lifecycle owner: `AppSecrets.java` plus `SshBundleProposal.java`, and focused v2 preservation tests. This owner must also place the early source/target restore denial to avoid overlapping edits to AppSecrets.
3. Restore owner, sequential with the AppSecrets owner: `SshRestoreReview.java` defensive version check and focused restore tests. Do not implement metadata review semantics in this increment.
4. Main session owns final cardinalities, naming, compatibility/limitations docs and checks. These are proposed boundaries, not permission to spawn or change files from this research role.

### Related specs and references

- `.trellis/spec/backend/persistence.md`: durable-state scope and bounded host storage.
- `.trellis/spec/backend/app-secrets.md`: actual exclusive loans, original context budgets, exact source/candidate validation, guarded effects, restore/provider closure.
- `.trellis/spec/backend/ssh-key-association.md`, `ssh-private-material.md`, `ssh-certificate-facts.md`: existing proofs are narrower than persisted relationship/account authority.
- `design/ssh-validation-lifecycle.md`, `design/ssh-private-material-ownership.md`, `design/ssh-certificate-facts.md`: remaining semantic/restore obligations and partial-only output.
- External references: none fetched. No external version or runtime behavior is asserted.

## Caveats / Not Found

- No existing persistent key-binding/account model or v1 target grammar was found; no migration can infer them reliably. The primary session must freeze final v2 cardinality/optional-field/namespace rules before implementation.
- Existing source line numbers describe the inspected snapshot and may shift as the primary session edits. Four production mutating constructor sites are the complete direct-constructor result at research time.
- Supporting v2 decode while keeping default creation v1 means v2 is an explicit construction foundation, not a product migration or restored-production capability.
- This report ran no tests/builds and provides no device/account/runtime evidence. Existing verification records are historical context only.

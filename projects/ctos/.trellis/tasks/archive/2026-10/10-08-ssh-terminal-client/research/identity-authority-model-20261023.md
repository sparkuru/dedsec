# Research: v1 credential ownership and complete private-material inspection

- Query: Identify the smallest next implementation after explicit selected-pair proof; distinguish complete actual-private/credential coverage from a nonexistent authoritative persisted key-pair/account graph.
- Scope: internal, read-only source investigation. This file is the only write. No builds, target execution, network, devices, accounts or git operations.
- Date: 2026-10-23
- Active task: `.trellis/tasks/10-08-ssh-terminal-client`. Research role isolation applies; implement/check JSONL were not loaded. Workflow, task artifact tops and relevant specs/contracts were read directly.

## Findings

### Source inventory and existing authority

| File | Relevant behavior |
| --- | --- |
| `android/app/src/main/java/im/majo/ctos/VaultManifest.java` | Authenticated plaintext version1; typed entry/file metadata, globally unique IDs, CONFIG-only references and weak phrase existence constraint. |
| `android/app/src/main/java/im/majo/ctos/SshAuthenticationSelection.java` | Live exact username/password entry or private path/identity/public declaration/optional phrase ID, bound to a role. |
| `android/app/src/main/java/im/majo/ctos/SshRoleContext.java` | Separately provided opaque targetIdentity, host, port, hop, connection/view binding and deadline. |
| `android/app/src/main/java/im/majo/ctos/SshCredentialBridge.java` | Exact live role/KEX/host proof gates; not an offline account model. |
| `android/app/src/main/java/im/majo/ctos/AppSecrets.java` | Actual source/target loans, registration/lifecycle gates, exact existing entry identity checks and all mutation/restore paths. |
| `android/app/src/main/java/im/majo/ctos/SshBundleProposal.java` | File-only proposal and complete candidate reconstruction; does not persist key-pair/target associations. |
| `android/app/src/main/java/im/majo/ctos/SshRestoreReview.java` | Exact file/credential coverage and source/target ciphertext binding; no association-graph coverage kind. |
| `android/app/src/main/java/im/majo/ctos/VaultEnvelope.java` | Independent portable authenticated envelope version1; plaintext bytes remain opaque until GCM verification. |
| `android/app/src/main/java/im/majo/ctos/SshManifestKeyAssociationInspector.java` | Already implemented explicit three-ID selected pairs; intentionally not graph authority or whole-manifest admission. |

1. `VaultManifest.java:29-37` stores entry type/id/identity/label/TOTP parameters/secret. `:52-58` stores file type/id/path/identity/body/references. There is **no** private-public/certificate edge, target/account record or username field.
2. IDs are globally unique (`VaultManifest.java:175-178`, `:196`), but file identities are not. Same-identity private files are structurally legal. References must resolve to paths (`:204-207`) and are forbidden on nonCONFIG objects (`:209`); they cannot be repurposed as private-peer edges without changing the current contract.
3. `VaultManifest.java:212-217` only requires each phrase entry to have *some* PRIVATE_KEY with `entry.identity == "key:" + file.identity`. It neither requires a unique private owner nor a unique phrase for that owner. Two private files sharing identity, or two distinct phrase IDs sharing identity, are valid v1 objects today.
4. The common nonTOTP identity regex accepts either `target:` or `key:` (`VaultManifest.java:185-187`). A PASSWORD with `key:x` is structurally legal. A phrase with `target:x` passes that regex but fails the subsequent `key:` existence rule, so it is not constructible as a valid full v1 manifest.
5. `AppSecrets.java:1444-1449` stores the caller's `target` verbatim as entry.identity and chooses type from `keyPassphrase`. Credential release requires exact identity equality with the separately supplied role target (`:2467-2472`). Private bundle release requires exact path/identity/phrase ID plus `key:` identity equality (`:2829-2835`), not uniqueness across the library.
6. `SshRoleContext.java:22-33`, `:85-91` carry/check targetIdentity separately from host/port. It is validated only as bounded noncontrol text, not parsed/canonicalized (`:175-181`). No production `SshTarget` class exists. `SshAuthenticationSelection.java:37-62` separately receives username and exact entry/path/public declaration; its comment explicitly says public bytes are not parsed-key proof (`:9`). Test strings such as `target:test@example.invalid:22` are examples, not a persisted grammar.

### Recommended next bounded increment

The main session's proposed **all-private signing/material verification plus whole-v1 credential-owner audit** is a coherent next increment. It supplies exhaustive coverage of existing inputs and existing `key:` ownership metadata, without pretending a complete private-peer/account authority model exists. It should not mutate or migrate v1, attach a production provider, or replace existing explicit-pair semantics.

Suggested shape, with final names/wire left to the reviewed implementation contract:

```java
Report inspect(VaultManifest actualExclusiveLoan, long originalDeadline,
               Cancellation cancellation);
```

- Enumerate every PRIVATE_KEY from the actual supplied loan, including orphan/nonCONFIG-referenced files. No caller-provided list controls coverage.
- Native strict private-only operation parses once, applies the new strict pair material/software/RSA policies, copies its parsed public fields into an independent plain public key, and verifies a fixed local challenge signed by the private key. This catches the already demonstrated inconsistent Ed25519 seed even without an external peer. Name the guarantee **private signing/public-field consistency**, never intended external-peer matching.
- Reuse the owned-key parser and factor the existing fixed-purpose signing helper; do not copy another parser or construct a fake caller-supplied peer file. Preserve CSKA60's peerType3/4 closure. A separate private-proof wire/API is appropriate; do not reinterpret old CSMI/CSKA results.
- Enumerate every SSH_KEY_PASSPHRASE metadata row. A phrase resolves only if exactly one PRIVATE_KEY has matching identity **and** that private has exactly one matching phrase entry. Do not compare/copy secret bytes to collapse duplicates; two IDs remain ambiguous even if their secrets happen to be equal.
- A private with zero matching phrase entries may attempt strict verification with no phrase; encrypted input then returns NEEDS, clear input may verify. A private whose relevant owner/phrase mapping is ambiguous should have explicit deferred-ownership state and no phrase copied/native derivation attempted for that ambiguous selection.
- Duplicate private identities **without any phrase referring to them** need not be rejected: v1 permits them and no phrase owner is being chosen. Each private can independently take its no-phrase verification path. Avoid a global new identity-uniqueness requirement.
- Enumerate every SSH_PASSWORD row, check the `target:` namespace and nonempty suffix under existing v1 text bounds, retain suffix as opaque metadata. Never parse user@host:port, infer an account, or copy password secrets. A structurally legal v1 PASSWORD `key:x` is an explicit namespace defect in this new audit, not a silent change to v1 decoding.
- TOTP and nonPRIVATE file bodies are not copied. File/public/certificate pair graph, password target/account validity, cert trust and final Request consumption remain deferred.

### Suggested result closure and budget pitfalls

Use separate dimensions rather than one overloaded success status:

| Result dimension | Suggested states/meaning |
| --- | --- |
| Private material row | VERIFIED, MALFORMED, UNSUPPORTED, NEEDS_PASSPHRASE, BAD_PASSPHRASE, DEFERRED_OWNER_AMBIGUITY. The deferred state is Java metadata, not native fake success. |
| Phrase ownership row | UNIQUE_OWNER, AMBIGUOUS_PRIVATE_OWNER, AMBIGUOUS_PHRASE_SELECTION; missing owner is a defensive defect if accepting an independently assembled input, though valid v1 construction already rejects it. |
| Password row | TARGET_NAMESPACE_ONLY or INVALID_NAMESPACE; always actual target/account unchecked. |

`allPrivateMaterialsVerified` quantifies every actual PRIVATE_KEY and is false for any deferred/failure row. `allPassphraseOwnersResolved` quantifies actual phrase entries only: a zero-phrase manifest may have true vacuously; it does **not** mean every private has or needs a phrase. Add counts/hasPrivateKeys if callers need to distinguish empty coverage. `passwordTargetsChecked=false`, `keyAssociationsChecked=false`, `trustChecked=false`, `admissionComplete=false` remain explicit even when both limited aggregate booleans are true.

Bounds are already finite: files<=128, entries<=1024, each private body<=1MiB, all file bodies<=8MiB (`VaultManifest.java:22-25`). At most128 private parses/sign/verify operations, not128 caller pairs/256 key parses. Each selected unique phrase is <=8192 bytes and belongs to one private, so at most128 selected phrase copies. Preserve one original<=60s deadline and cancellation/final cleanup fence; single crypto/KDF remains nonpreemptible. No per-row deadline renewal.

Do **not** emit all private×phrase candidate edges:128×1024 metadata rows can amplify report size unnecessarily. Counts plus original ordinals/exact IDs and one unique ordinal or sentinel are sufficient. Report all1024 nonTOTP entry rows and128 private rows at most; enforce a closed bounded result budget. Metadata loops should check original cancellation/deadline, with cleanup slots established before the first sensitive clone.

### Concrete acceptance vectors

1. One valid clear private, no phrase: actual self-sign verified; no public/cert peer required and no caller selection list.
2. Ed25519 private with matching serialized public fields but wrong secret seed: actual private-only SIGNER failure, never VERIFIED.
3. Encrypted OpenSSH + one matching private + one phrase: exact sole phrase used and wiped; wrong phrase yields BAD, omission yields NEEDS, oversized KDF is rejected before deriving.
4. Two private files share identity + one referring phrase: both owner mapping and phrase row explicitly ambiguous; no phrase bytes copied/derived. The same two files with zero phrases may each independently verify without a phrase.
5. One private + two distinct matching phrase IDs, including equal secret byte contents: ambiguous; no first-item or secret-equality tie-break.
6. PASSWORD `target:opaque` yields namespace-only/deferred target guarantee; PASSWORD `key:x` yields namespace defect. TOTP/password/public/cert bodies are not copied; caller-owned originals, including all private bodies, remain unchanged.
7. Orphan actual private file appears in the report, all1024 credentials covered, zero-private case still audits password rows, no synthetic role/current-store lookup/provider registration.
8. Strict legacy encrypted PEM refusal, clear-private extra phrase, RSA component/multiprime bounds, all software algorithms retain prior policy. Original material7784/explicit-pair tests remain unchanged.
9. Actual source/candidate with identical metadata/different body yields independent private results; lifecycle/IOException/Linkage/Runtime/Error wipes all selected clones/wire and returns no prefix report; final publication runs after cleanup.

### What a real persisted authority model would require later

An authoritative exact-ID private/peer/phrase graph and explicit credential account/target records need new authenticated metadata, not a caller coverage list or a reinterpretation of v1 names. Plaintext decode accepts only int1 and rejects trailing bytes (`VaultManifest.java:87`, `:124`), and encode always emits1 (`:137-142`). New fields therefore require an explicit plaintext version2 or equally explicit authenticated file-type extension with defined ownership; the latter still needs purpose/coverage semantics and is not a shortcut.

The envelope's `CTOSVLT1`/version1/KDF/AAD is independent (`VaultEnvelope.java:18-26`, `:46-65`, `:85-108`); it can in principle encrypt a version2 plaintext without changing cryptography, but compatibility must be documented because old clients reject the new plaintext. No migration facility is implemented now.

Every reconstruction must retain such metadata: TOTP candidate (`AppSecrets.java:1385`), SSH entry candidate (`:1694`), trust candidate (`:1969`) and bundle (`SshBundleProposal.java:165-193`) currently call the two-list constructor, which would drop a newly added graph unless updated. Rekey preserves authenticated plaintext exactly (`AppSecrets.java:631-633`); export returns the authenticated existing envelope (`:605-615`); import reencrypts exact source plaintext (`:818-821`). Validator/reviewer loans require exact encode equality with authenticated bytes (`:754-769`), so a decoder cannot silently normalize version1 into version2 on loan encode.

Restore coverage presently enumerates FILE/CREDENTIAL objects only (`SshRestoreReview.java:21-23`, `:172-179`, `:218-222`). A new graph must be covered in the review/binding and mutation invariants, not merely hidden in encrypted storage. Source/target review is separately required and remains gated (`AppSecrets.java:681-700`).

Routine defaults do not require user choice: keep v1 round trips, no automatic migration, no name/suffix inference, exact raw ownership metadata, partial-only new audit and no provider opening. Future product choices requiring an explicit design decision are target/account canonical representation, whether stored but unused public/cert objects need a purpose declaration, and how legacy ambiguous relationships are repaired/approved. They need not block this bounded all-private/credential audit.

### Related specs / references

- `.trellis/spec/backend/app-secrets.md`: actual supplied loans, validator/reviewer registration/lifetime and production restore closure.
- `.trellis/spec/backend/ssh-key-association.md`: strict material policy, fixed local proof, explicit selected-pair limited guarantees.
- `design/ssh-key-association.md`, `design/ssh-config-resources.md`, `design/ssh-validation-lifecycle.md`: current partial proofs and remaining whole-manifest obligations.
- External references: none fetched. All claims above derive from current project source and the existing pinned-source research. No current network version/recommendation claims are made.

## Caveats / Not Found

- This research proposes a next contract, not implementation or runtime evidence. Exact result names/wire/status aggregation require the main session's reviewed contract.
- No authoritative persisted private/public/certificate graph, normalized target/account model or automatic legacy migration was found. A complete relationship guarantee is impossible from the current v1 fields alone without changing their stated meaning.
- Private-only self-verification proves a usable signing relationship to parsed public fields, not intended pair identity, remote acceptance, password correctness or trusted certificate semantics.
- The existing metadata phrase rule states owner intent but is not proof that the secret decrypts that private. Both owner resolution and actual material verification must be reported independently.

# Research: Managed catalog and vault semantic-validation seam

- Query: What can follow the native/JNI configuration evaluator without accidentally authorizing incomplete SSH imports or restores?
- Scope: internal; focused Java vault/lease/evaluator APIs, existing transaction tests and original product decisions.
- Date: 2026-10-19

## Final contract decision

This research preceded the frozen contract. The implemented discovery API is context-free:
no Request or fabricated alias/user/passwd. It conservatively traverses root-reachable static
Include and records dynamic sites without expansion. Selected-alias effective evaluation
remains a separate Request operation. The recommendation below is historical input, superseded
by [the final contract](../../../../../../design/ssh-config-catalog.md).

## Findings

### Recommended next increment

Implement a bounded **catalog over a caller-owned live public lease**, with explicit request context and native parser alias discovery. Keep `AppSecrets.attachSshValidator` unregistered and `sshBackupImportAvailable=false`. This is a useful production dependency, but does not yet make SSH files importable in the real app. Catalog metadata is not a connection plan, an authorization capability or proof that all manifest branches and private objects are valid.

The safe API boundary is a catalog owner borrowing the same `SshPublicFileLease` used for configuration evaluation. It may collect explicit positive literal Host aliases and evaluate a selected alias under an explicit `SshConfigEvaluator.Request`. It must preserve object/line provenance, fail on bounds instead of truncating, and maintain the existing cancellation, thread, deadline and cleanup rules. It may not request passwords/private keys, create roles, touch network/DNS, persist or open arbitrary host paths.

Do not implement a Java/Dart SSH lexer or reduced field parser to produce the catalog. Discover aliases at a pinned native parser token boundary; use the existing real parser to resolve each explicit context. Conditional Include and Match make the discovered list contextual, not an exhaustive proof of every possible configuration. Main must freeze exactly whether discovery follows only the current parser traversal or separately explores static Include closure, before claiming a complete list.

### Files found

| File | Responsibility |
| --- | --- |
| `android/app/src/main/java/im/majo/ctos/AppSecrets.java` | Manifest-only semantic hook, authenticated source/target restore, exact bundle transactions and typed public file operations. |
| `android/app/src/main/java/im/majo/ctos/VaultManifest.java` | Bounded structural graph; not a config/key/trust semantic parser. |
| `android/app/src/main/java/im/majo/ctos/SshFileSnapshot.java` | Synchronous manifest-body loan, full selected metadata, public bodies only. |
| `android/app/src/main/java/im/majo/ctos/SshPublicFileLease.java` | Owned native public view, opaque token, revocation and retryable destroy. |
| `android/app/src/main/java/im/majo/ctos/SshConfigEvaluator.java` | Borrow-only one-run owner-thread facade, explicit context, structured failure and metadata summary. |
| `android/app/src/main/java/im/majo/ctos/SshVaultAdapter.java` | Typed adapter; distinct host, bundle and trust approval gates. |
| `android/app/src/main/java/im/majo/ctos/SshBundleProposal.java` | Exact copied file deltas, old/new bindings, whole-manifest candidate application. |
| `android/app/src/main/java/im/majo/ctos/SshRestoreReview.java` | Bounded source/target coverage and trust/conflict projection, not a semantic parser. |
| `android/app/src/main/java/im/majo/ctos/VaultSession.java` | One active public-view attachment per session, lock revocation. |
| `android/app/src/test/java/im/majo/ctos/SshBundleTest.java` | Independent synthetic validator and atomic graph mutation tests. |
| `android/app/src/test/java/im/majo/ctos/SshRestoreTest.java` | Synthetic review coverage and exact source/target restore orchestration tests. |
| `.trellis/tasks/10-08-ssh-terminal/{prd,design}.md` | Original Host indexing, config, managed import and trust decisions. |

### The semantic hook validates manifests, not a live user's selected connection

`AppSecrets.SshContentValidator.validate(VaultManifest)` accepts only the supplied manifest (`AppSecrets.java:38`). There is no root, alias, remote user, local user/hostname, tag, command, selected identity, timeout or cancellation argument. Registration is a plain synchronized setter (`:148`). No production main-source registration was found; all `attachSshValidator` callers other than the setter are tests or Android fixtures.

Hook call sites and implications:

| Call site | Exact input and implications |
| --- | --- |
| `validateBusiness`, `AppSecrets.java:170` | Invokes hook for any files or any non-TOTP credential, including credential-only SSH libraries. Missing hook rejects. |
| `preview`, `:276` / `:280` | Separately validates decrypted source and authenticated current target. A pure-TOTP source replacing an SSH target still requires SSH validation and restore review. |
| `beginSshBundle`, `:611` / `:616` | Applies proposal to current full manifest, validates the candidate even if it removes the final SSH object, then admits the operation. |
| `commitSshBundle`, `:623` / `:629` | Applies and validates candidate again before commit; proposal and validator presence rechecked in final guard. |
| `commitManifest`, `:643` / `:645` | Business validation applies to other mutations as well, including a TOTP edit that preserves an SSH library. |
| `sessionManifest`, `:901` / `:907` | Typed access to an existing SSH library also invokes validation. `beginSshFiles` and file-snapshot access cannot bypass it. |

`unlock` (`:210`) and the static password/decryption `verify` helper (`:979`) perform authentication and structural decode; they do not themselves run `validateBusiness`. Do not claim the hook blocks raw vault unlock. Typed subsequent operations remain fail closed.

Calling `vault.beginSshFiles/openPublicFiles` from the validator would be wrong: it validates the current store, not the supplied source/candidate, recursively enters `sessionManifest` and the same validator, and requires a foreground unlocked live session which source restore need not have. A fake live operation, invented role or synthetic session cannot repair that mismatch.

The existing bridge explicitly returns `sshBackupImportAvailable=false` (`AppSecretsBridge.java:737`). Do not flip it merely because a catalog can run against a synthetic/live lease.

### Restore has another independent gate

`preview` authenticates the target using the separately supplied target password before validation (`AppSecrets.java:278`). If either side has SSH, a versioned reviewer and exact approval provider are required (`:288`). Reviewer receives temporary manifest loans, then core decodes canonical source/target again and binds the result to both ciphertexts/provider identity/version/revision (`:294`–`:305`). Temporary bytes are wiped after use. Restoring source credentials/files is not a statement that any remote host is trustworthy.

`SshRestoreReview` coverage has FILE/CREDENTIAL kinds and SOURCE/TARGET sides (`SshRestoreReview.java:18`–`:24`), while trust rows include imported/same/changed/target-removed/unknown-mapping (`:19`). The core requires full SSH coverage and a distinct exact review binding; existing backend spec explicitly requires both source and authenticated target coverage (`.trellis/spec/backend/app-secrets.md:208`). Catalog alias success cannot substitute for these checks or the native known_hosts parsing/mapping, certificate policy and explicit trust acceptance.

### Snapshot and lease ownership

`beginSshFiles` accepts an explicit logical root and nonempty connection ID, rejects a private-key root, and binds a once-use operation to the current envelope (`AppSecrets.java:733`). It does not insist root is CONFIG. The actual native evaluator does require CONFIG (`ssh_config_eval.c:429`); catalog should make this boundary explicit.

`sshFileSnapshot` recursively selects only `FileObject.references`, uses a visited set and retains order of that traversal (`AppSecrets.java:748`, `:783`). It does not discover Include targets from config text. The imported graph must already cover required files. Native glob/Include cannot reach unrelated files outside the supplied closure just because they exist elsewhere in the vault.

`SshFileSnapshot` binds request ID, root, connection ID, vault UUID, generation, epoch, deadline and ciphertext snapshot digest (`SshFileSnapshot.java:27`–`:46`). Metadata covers all selected types, including private-key identity/path/length/references; public bodies omit PRIVATE_KEY (`:49`–`:54`). `publicFiles` contains borrowed `FileObject.content` arrays owned by the verified manifest. The immutable list does not extend byte-array lifetime: consumers must not retain raw bodies beyond the synchronous receiver. Private content must never be copied into a public catalog wire.

`SshPublicFileLease.encode` validates metadata/body agreement, content and wire budgets, serializes private metadata with a zero-length body (`SshPublicFileLease.java:60`, `:107`–`:114`); temporary wire is wiped after native creation (`:27`). `sshPublicFiles` creates the lease within the live callback, attaches it to operation and session, rechecks the effect and closes on any failure (`AppSecrets.java:763`).

`VaultSession.attachRevocation` rejects **BUSY** if an active view is already attached (`VaultSession.java:77`–`:83`); it does not replace or revoke that view. Session lock increments epoch and revokes its attached view (`:68`). A catalog must borrow the supplied caller lease; hidden secondary lease creation would collide with this single-view contract.

The existing `SshConfigEvaluator.open` already borrows the caller lease (`SshConfigEvaluator.java:174`). All operations except `cancel` are owner-thread only (`:270`); `run` is one attempt (`:208`); every summary fetch invokes the guarded native getter and rechecks the lease rather than returning a cached authority (`:230`). `close` retains separate close/destroy stages so failures can be retried (`:255`). It does not close the caller's lease. A future catalog must preserve the same cleanup ownership; opening several child evaluators and dropping a failed-close child is a resource leak.

The copied Summary is historical metadata. Its `hasRawFields` flag must remain true and it must not be relabeled a complete launch configuration. Native Options includes fields not exposed in Summary. Session/forward/path/jump finalization is separate work; metadata cannot silently reduce such a configuration to direct host/user/port.

### What structural graph validation does not establish

`VaultManifest.validate` enforces types, unique IDs/paths, bounded bytes and references, complete acyclic depth<=8, and key-passphrase identity association (`VaultManifest.java:170`–`:232`). It does not parse the bytes according to CONFIG/PRIVATE_KEY/PUBLIC_KEY/CERTIFICATE/KNOWN_HOSTS or prove metadata identity matches a parsed key. Thus a manifest with graph-valid arbitrary bytes can pass structural validation; synthetic fixture validators deliberately permit this.

An evaluator sees public config/key/cert/trust bodies and private metadata only. It cannot prove that a private key decodes, matches its declared identity/public key, or decrypts with a separately saved passphrase. Encrypted-key structural inspection may be possible without authentication, but actual private-key match needs a separate explicitly designed candidate inspector. Reusing the existing post-host-verification private-key access API for offline source validation would violate its purpose.

One alias does not establish all Host wildcard/negative branches, contexts of Match user/localuser/tagged/command/sessiontype, or conditional Include closure. Missing User is a user-required outcome, not permission to infer Android UID as remote username or pick an arbitrary fallback. A supplied local context is part of the evaluation input and must not be silently derived from environment/NSS.

### Original product decisions to preserve

- Parent PRD R3 (`.trellis/tasks/10-08-ssh-terminal/prd.md:25`) makes config authoritative and lists explicit Host aliases plus resolved target. Wildcard rules participate in matching but do not become invented device rows.
- Parent design (`.trellis/tasks/10-08-ssh-terminal/design.md:136`) requires de-duplication, multi-alias/wildcard/negation/first-value/additive identity fixtures; missing User is completion/error, never Android UID fallback.
- Managed authoritative config is stored as encrypted file objects, with preserved logical directory structure (`design.md:113`, `:153`). Existing file content is not replaced by a reduced reconstructed HostName/User/Port table. No separate durable alias-authority store is justified by current schema.
- Parent design `:138`–`:141` requires explicit unsupported-effective-option errors and Include's SSH-directory relative base, lexical order, bounds and missing-file diagnosis. It forbids silently ignoring ProxyJump, forwarding, remote command or trust semantics.
- SAF source import is explicit and bounded, uses memory/encrypted staging and exact common-library transactions; cancel/failure preserves old content; sources are neither deleted nor continuously synchronized (`:153`–`:156`). Imported trust is separately reviewed and not automatically accepted.

### Offline candidate validation is a later, different capability

A correct future candidate-manifest validator should receive the **actual caller-supplied authenticated source/candidate manifest loan**, inspect all object formats and associations, and build independent bounded public-only native evaluation input. It must not borrow the current vault's live lease or acquire a session/host/credential capability. Keep lifetime/cancel/deadline as validator-owned job context, separate from live connection roles; whether to evolve the current manifest-only interface is an architectural decision.

It needs explicit policy on multiple CONFIG roots (none are registered in the current manifest schema), conservative conditional Include discovery, unknown remote-user contexts, all context-dependent references and unsupported formats/options. Import-level structural/policy certification and selected-connection effective evaluation should remain distinguishable so neither claims to prove what it cannot. Trust restore reviewer and approval remain separate regardless of any future validator success.

### Meaningful next-increment tests

1. Real pinned parser discovery over managed closure: two literal aliases and `Host *`; multi-alias, duplicate declarations, negated tokens, case/quotes and literal candidates from nested/glob Include with lexical provenance. Assert wildcard/negative tokens produce no rows and no candidate truncation on a limit error.
2. Resolve the same selected alias with distinct explicit user/local/tag/command contexts; assert Match-dependent target differences and unknown-user outcomes are preserved. Conditional Include discovery scope must be asserted, not assumed exhaustive.
3. Missing closure Include versus an unrelated vault object outside closure; reject unexpected raw path access; no private body leaves the public view. A graph-valid private metadata placeholder must never be reported as key-validated.
4. Caller lease survives catalog close and failed child evaluation; catalog cannot return a stale live result after cancellation, original deadline, revoke or replacement epoch. Cross-thread cancel remains prompt; wrong-thread run/getter/close reject; retain retryable failed close/destroy owners.
5. Failed alias evaluation remains an exact structured object/line diagnostic with fixed safe error text. Summary metadata with jump/forward/RemoteCommand still marks raw fields; no role, password, key request, listener, DNS or socket effect occurs.
6. Guard regression: missing semantic validator still rejects typed mixed-SSH access and candidate bundle admission/commit, including deletion of final SSH object. Mixed-source or SSH-target restore remains closed without a real versioned reviewer and exact approval; pure-TOTP path remains unaffected. These are assertions of retained gates, not tests using a permissive fake validator to claim semantic import acceptance.

Existing useful fixtures: `SshBundleTest` explicitly calls itself an independent bounded fixture validator (`:16`), covers validator removal/last-object deletion (`:219`), graph/reference/dangling constraints (`:284`), CAS/approval and uncertain effects. `SshRestoreTest` explicitly uses synthetic projections (`:16`), tests missing hooks on either SSH side (`:100`) and temporary source/target loans (`:130`). `SshPublicFileLeaseTest:28` proves private metadata/no private body. `SshConfigEvaluatorTest:340` onward covers retained cleanup, thread/cancel/revoke/reentry. Reuse their orchestration patterns but do not upgrade fixture coverage to real SSH semantic acceptance.

## Related specs and references

- `.trellis/spec/backend/app-secrets.md:207`–`:233`: mandatory validator, whole resulting graph, independent exact restore provider/approval and production fail-closed status.
- `.trellis/spec/backend/ssh-native.md`: native evaluator/JNI borrow-only contract, no new role/secret/socket authority.
- `design/ssh-config-evaluator.md`, `design/ssh-config-managed.md`: pinned OpenSSH 10.6p1 parser, full Options retained, live-view result and unsupported-effect boundaries.
- `design/ssh-launcher.md:104`–`:107`: evaluator then catalog/validator/final plan, and no unverified validator registration.
- `.trellis/tasks/10-08-ssh-terminal-client/research/launcher-managed-20261019.md`: earlier original product and source-import inventory. Its pre-evaluator/pre-managed-open state sections are historical; current source supersedes them.
- No external research was needed. No external sources were fetched; this report relies on local authoritative project code/design and the pinned parser integration.

## Caveats / Not Found

- No production semantic validator registration, source-tree importer, root registry, catalog API or complete connection-plan API was found.
- Exact native alias-token collection mechanism was not designed here; another focused parser investigation should determine hook placement/conditional traversal without competing parsing.
- Existing main-tree source was inspected only; no builds, tests, device or actual vault/account access were performed by this researcher. Test suggestions are not run results.
- Work is confined to this research file; concurrent implementation and documentation edits are preserved.

# Research: supplied-manifest SSH validation and content-inspection prerequisite

- Query: How can production checks inspect the actual source/target/candidate VaultManifest without recursively opening the current vault or fabricating a live lease, and what is the smallest useful next increment?
- Scope: internal; source inspection only, no code changes, builds, device operations or external accounts.
- Date: 2026-10-19

## Findings

### 1. The supplied manifest is already the correct semantic-validator input

`AppSecrets.SshContentValidator.validate(VaultManifest)` is a synchronous, void/throw callback (`android/app/src/main/java/im/majo/ctos/AppSecrets.java:38`). `attachSshValidator` only stores the callback (:148); no production registration was found. `validateBusiness` requires it whenever there is any file or non-TOTP entry (:162-175). It does not derive SSH semantics from decrypt success.

Call sites use distinct authenticated or constructed manifests:

| Path | Actual input and ordering | Source |
| --- | --- | --- |
| Typed reads/admission | Current ciphertext is decrypted/decoded; `validateBusiness(manifest)` executes before returning Verified | AppSecrets.java:900-910 |
| Import source | Authenticated source is verified, then validated before preview construction | AppSecrets.java:271-276 |
| Import existing target | Target must be freshly authenticated with target password, then separately validated | AppSecrets.java:277-280 |
| Bundle admission | Current Verified manifest, proposal.applyTo(current), then explicit validator call even if final SSH namespace is empty | AppSecrets.java:611-619 |
| Bundle effect | Recompute candidate from current ciphertext, validate, then commitManifest validates again | AppSecrets.java:622-645 |
| Other typed mutations/trust CAS | Whole resulting manifest passes validateBusiness before encode/encrypt/guarded storage | AppSecrets.java:638-667 |

Consequently a validator must inspect **its supplied object**. Calling `SshVaultAdapter.filesRequest`, AppSecrets begin/use files, or current `sessionManifest` from the callback repeats `sessionManifest -> validateBusiness -> callback`, recurses, and cannot inspect a different import source/candidate. Current ciphertext binding is also not a valid binding for a supplied candidate.

Unlock (`AppSecrets.java:210-223`) and quick unlock (:411-420) authenticate/decode but do not invoke validateBusiness. A successful unlock therefore says nothing about SSH semantics. Pure-TOTP reads in a mixed SSH vault currently pass through whole-manifest business validation too; a slow callback affects unrelated reads.

### 2. Current callback cannot promise responsive cancellation

There is no operation, deadline, guard or cancellation argument in `SshContentValidator` (:38). `validateBusiness` is synchronized (:170), and typed callers such as beginSshFiles are themselves synchronized (:733). `AppSecrets.lock` (:423) and cancelRequest (:938) also acquire the core monitor. Even preview's apparently external validateBusiness call acquires that monitor. A parser/KDF under the present callback can therefore block those core methods.

`cancelOperation` (:894-898) can set an operation's cancellation without that monitor, but no reference reaches the validator and bundle admission may not yet have constructed its operation. Do not claim that using a native atomic cancellation flag by itself connects existing AppSecrets cancellation to semantic validation.

For eventual full integration, the main session must freeze a validation-context API and call/lock boundary: capture an authenticated owned input and original caller deadline, run expensive parsers outside the request/core monitor, then recheck validator registration, source/candidate binding, epoch/foreground/operation and original deadline before publishing or storage. This is a future core change, not something the present hook already provides.

### 3. Manifest structure validation is necessary but not SSH content validation

`VaultManifest.java:22-27` defines 1024 entries,128 files,1MiB/file,8MiB files total; entry types TOTP/SSH_PASSWORD/SSH_KEY_PASSPHRASE and file types CONFIG/PRIVATE_KEY/PUBLIC_KEY/CERTIFICATE/KNOWN_HOSTS.

`VaultManifest.validate` (:173-220) checks global IDs, bounded metadata/secret lengths, path uniqueness, complete declared references, CONFIG-only references, cycle/depth and existence of a PRIVATE_KEY matching each passphrase's `key:<identity>`. Decode additionally authenticates per-body digest and bounds total content (:82-134). It **does not parse config, key, certificate, trust or SSH target semantics**. Empty file bodies are structurally allowed (:196). Both SSH credential subtypes accept either `target:` or `key:` identity syntax (:185-186); only passphrase existence gets a later relation check (:212-217). Credential byte arrays can contain NUL at this layer.

The private-key/passphrase production operation binds exact file path, file identity and explicit passphrase entry ID; entry.identity must equal `key:` + keyIdentity (`AppSecrets.java:815-853`). There is no schema-level private/public/certificate pairing rule or exact one-passphrase-per-identity rule. Duplicate identities are structurally allowed. Generic CONFIG references cannot safely be reinterpreted as key/public equality. Do not invent filename suffix pairing, arbitrary identity-string meaning, or implicit first-match passphrase selection.

### 4. Caller ownership is a real constraint

Entry and FileObject constructors clone input secret/body arrays (`VaultManifest.java:39-48`, :60-67), but the **manifest constructor copies the lists only**, retaining supplied Entry/FileObject instances (:74-77). Their content arrays remain package-visible and mutable. `close` wipes all those arrays (:256-258). A validation helper must never close/mutate the caller's manifest or construct a shallow child manifest and close it.

`SshBundleProposal.applyTo` clones file objects but passes previous.entries into the resulting manifest (`SshBundleProposal.java:164-190`). This reinforces the need for explicit helper ownership rather than assuming every manifest is deeply isolated. This research did not change existing bundle ownership behavior or run a bug reproduction.

A detached inspection owner should deep-copy each Entry and FileObject it retains, wipe all copied arrays on close/failure/Error, and leave caller arrays untouched. Full encode/decode cloning creates another plaintext copy containing TOTP and passwords; avoid it if the increment only needs per-file bodies plus selected exact passphrase bytes. Never expose private bytes, passphrases or credential digests in reports/logs.

### 5. Current public lease is the wrong transport for detached candidates

`SshFileSnapshot` is specifically a live operation loan: its constructor consumes VaultOperation request/root/connection/UUID/generation/epoch/deadline/snapshot binding (`SshFileSnapshot.java:27-58`), and omits private bodies while retaining metadata (:49-52).

`SshPublicFileLease.encode` serializes that whole live tuple and public bodies (`SshPublicFileLease.java:60-120`). `create` calls the production native singleton (:27-37). Native `ctos_ssh_view_handle_create` rejects `current != NULL` with EBUSY (`ssh_view_handle.c:65-81`); it cannot silently coexist with an active live public lease. Java `VaultSession.attachRevocation` independently rejects a second active revocation without replacing/revoking the old one (`VaultSession.java:77-84`).

Actual file selection follows **declared references only** (`AppSecrets.java:748-788`), not discovered parser Include targets. An existing lease neither contains arbitrary source/candidate objects nor private bodies. Do not fabricate vault UUID/generation/epoch/requestId/connectionId to stuff a candidate into CSPV. A separate inspection input must have explicit inspection-only ownership and no role/session/provider use.

### 6. Recommended smallest useful increment: inspect real candidate file content

The next increment should reject previously unchecked **actual supplied bytes**. A Java-only owner that merely repackages the catalog would add little beyond the completed declaration feature. A production native content inspector is a useful reusable prerequisite even while whole semantic validation stays closed:

- PRIVATE_KEY: parse full supplied body, with an explicitly supplied matching passphrase entry when applicable; enforce bounded encrypted format/KDF policy before decryption; derive its real public metadata; reject invalid/unsupported/ambiguous inputs explicitly.
- PUBLIC_KEY: parse exactly the supported file shape and enforce the declared non-certificate type; do not accept a valid prefix followed by another key or arbitrary content as a full-file success.
- CERTIFICATE: parse real certificate content and enforce certificate type; parse success does not mean policy acceptance, validity or allowed login use.
- KNOWN_HOSTS: parse every nonblank/comment line, preserving marker/host-selector/key information needed by later restore review; a single target lookup that ignores malformed/unmatched lines cannot certify the whole candidate file. Hashed/pattern selectors must remain explicit, not fabricated concrete hosts.
- CONFIG: explicitly leave effective semantics pending; the completed catalog can still be reused when a future detached config input exists. Do not label lexical catalog coverage as semantic coverage.

The exact pinned native full-file parser/KDF APIs and strictness need the parallel native research. Existing production authentication already parses and compares actual private/public bytes (`ssh_native_client.c:1230-1268`), and encrypted-key policy bounds OpenSSH aes256-ctr/bcrypt, one key, salt1..64, rounds1..64, with exact supported legacy PEM alternatives (:1125-1186). Those bounds must be shared or deliberately matched instead of making import accept an unbounded KDF that login later rejects. Do not refactor authentication incidentally in this increment.

Suggested callable shape, **proposal, not existing API**:

```java
SshManifestContentInspection.open(VaultManifest supplied, InspectionContext context)
    -> request-owned AutoCloseable inspection
inspection.run() -> immutable ContentReport
inspection.cancel() // safe cross-thread, local atomic cancellation
inspection.close() // owner thread, retry if native cleanup not acknowledged
```

`InspectionContext` supplies a real monotonic clock/original absolute deadline and a local atomic cancellation source, without vault/session/role identifiers. Open deep-copies required supplied bytes and metadata and admits bounded work; run invokes a native **direct typed-body inspection API**, not create/borrow of the live public lease. The native owner needs a cleanup handle before expensive work, independently checked owner/PID/UID and monotonic deadline/cancel, no caller pointers retained, and wiped temporary inputs/results. A one-shot small-file native call is also possible if cancellation limitations are explicit and KDF work is bounded; do not claim interruption inside an opaque upstream KDF.

An even narrower first deliverable can expose the native typed-body inspector plus real parser tests, then add whole-manifest orchestration. It is useful because it checks actual non-CONFIG contents that are currently entirely unchecked, and its result can become a required phase of eventual `validate(supplied, context)`.

Input-to-result binding should cover actual file metadata/type/content digest internally, and exact passphrase selection where used. Existing `SshBundleProposal.objectBinding(FileObject)` (:214-229) is suitable nonsecret file binding machinery. Do not publicly export a digest incorporating a low-entropy passphrase/password: restore coverage deliberately replaces credential bindings with unlinkable HMAC tokens (`SshRestoreReview.java:168-196`, :261-268).

Report fields must distinguish content parsed, unsupported, malformed and not inspected. No generic `valid`, `approved` or `complete` Boolean. A successful content report is **staged inspection only**, not permission to register `AppSecrets.SshContentValidator`, import/restore, sign a plan, allocate a role or connect. A caller later adding a semantic validator must require all remaining phases rather than treating missing coverage as empty success.

Error boundaries: caller/input structural errors -> existing VAULT_CONTENT or specific bounded inspection diagnostic; parser malformed/unsupported distinguish stable codes; deadline/cancel reject without publishing a partial success report; native unavailable/EBUSY/cleanup failure remain lifecycle failures. Return object identity/line where meaningful, never parser text containing private bytes. Recheck deadline/cancel after the last parse and immediately before report publication. Preserve failures and cleanup retry state rather than leaking/forgetting a native owner on exceptional JNI allocation.

### 7. What a complete semantic approval would additionally require

1. Every actual CONFIG body/reference closure and actual Include semantics, including dynamic Include requiring an actual execution context. Catalog's omitted selectors/deferred sites cannot be assumed covered.
2. Real per-target Request context for Host/Match/User and effective Options; no invented alias/username/passwd. Wildcard/negation selectors are not finite target declarations. Unsupported context-dependent policies must reject or stay pending.
3. Complete effective path/type/reference/key/certificate/trust association, resource budgets and local-effect policy; all retained raw options accounted for at final launch.
4. Exact credential selection and actual private/public equality, not an identity-string assertion. Any ambiguous passphrase or absent key pairing remains explicit until a schema/selection contract exists.
5. Actual trust content projection and separate exact source/target restore review/approval. Parse validity alone never approves changed trust.
6. Context-bound publication/final storage rechecks and responsive cancellation contract, including a provider replacement/revision strategy; existing attachSshValidator alone has no revision invalidation protocol unlike restore provider registration.

Unknown User/Match/dynamic Include must not be defaulted merely to make import pass. Whether semantic admission rejects such content until explicitly contextualized, or accepts only content-format validity plus deferred connection-time checks, is a product/security contract to freeze; these are different guarantees.

### 8. Existing bundle/restore tests are orchestration fixtures

`SshBundleTest.java:18` explicitly disclaims OpenSSH semantics; its fixture validator only checks identity startsWith fixture (:418-420), and files contain literal `fixture <path>` (:438-450), including purported private/public/cert/trust. Replacing its fixture hook globally with a real parser would invalidate the intended synthetic transaction tests rather than prove a regression.

`SshRestoreTest.java:17`, :54-57 likewise disclaims real SSH content. syntheticReview fabricates trust host/type/fingerprint (:65-87, :402-404). It tests full coverage/type/identity/body binding (:147-164), authenticated temporary loans and wiping (:131-145), mutation/immutable projection (:191-217), exact binding/consent/provider revisions/final guards (:220-267), cancellation/expiry/current snapshot changes (:269-280), and guarded commit behavior. Preserve these fixtures; add new actual-content tests separately.

Restore preview first semantic-validates source/target then requires reviewer (:276-296); reviewer receives decoded temporary loans and canonical bind checks exact whole coverage (:294-303). `SshRestoreReview.bind` validates all file and non-TOTP credential coverage (:168-180, :218-223). importCommit checks authenticated source envelope/plaintext digest (:333, :376-380), exact review binding/consent (:334-339), approval again at storage boundary (:348-354), and provider identity/epoch/deadline (:359-373). A content inspector does not replace any of those gates.

## Suggested Test Matrix

| Area | Concrete acceptance evidence |
| --- | --- |
| Supplied object isolation | Current live vault A and detached candidate B differ; inspector observes B only; no store/session/adapter calls; candidate source remains untouched and usable after inspection close |
| Body ownership | Mutate caller body after open; result stays bound to owned copy; retained copy wiped on success/parse failure/cancel/Error; original arrays never zeroed by helper |
| Full coverage | Inspect orphan non-CONFIG files too; every submitted non-CONFIG type has a result; CONFIG and credential target semantics explicitly pending; empty/TOTP-only input does not invent an SSH root |
| Keys | Real Ed25519/ECDSA/RSA public/private; malformed/empty/NUL/truncated/second-object/trailing data; public versus certificate type confusion; actual mismatch detectable when explicit expected public is supplied |
| Encryption | Correct/wrong/missing passphrase; unsupported encrypted PKCS8; excessive bcrypt rounds/salt/multiple keys rejected before KDF; two matching passphrase IDs require explicit selection or fail ambiguous |
| Trust | Empty and comments-only supported policy; real multiple keys/selectors/markers, revoked/CA/hashed/pattern cases; malformed unmatched line must fail whole-file format inspection; unknown mapping explicit |
| Lifetime | Wrong owner/reentry/PID/UID; pre/post cancel and exact deadline; bounded KDF limitation stated; live public lease remains active and usable; no singleton registry mutation |
| JNI | Standard UTF8 metadata, bounded lengths/counts/trailing validation; input arrays wiped only when owned; pending exception preserved; result allocation failure cleans native state or retains retry handle |
| Admission separation | Parsed content cannot serve as `SshContentValidator`; unregistered production gate still rejects source/target SSH, including deleting final SSH file; real lexical catalog with invalid Port remains nonsemantic |
| Existing contracts | Rerun separate bundle/restore fixture regressions if orchestrator touches core; do not rewrite fixture content to accidentally conflate orchestration and parser coverage |

## Files Found

- `android/app/src/main/java/im/majo/ctos/AppSecrets.java` — actual callback, typed/read/candidate/restore call graph and storage guards.
- `android/app/src/main/java/im/majo/ctos/VaultManifest.java` — bounded authenticated structural format, mutable body ownership and generic references.
- `android/app/src/main/java/im/majo/ctos/VaultSession.java` — live key/epoch/revocation and second-active-loan BUSY rule.
- `android/app/src/main/java/im/majo/ctos/SshFileSnapshot.java` — live operation-only public-body projection.
- `android/app/src/main/java/im/majo/ctos/SshPublicFileLease.java` — CSPV encoding/native singleton owner, excludes private body.
- `android/app/src/main/cpp/ssh_view_handle.c` — singleton view admission and managed borrow release.
- `android/app/src/main/java/im/majo/ctos/SshBundleProposal.java` — exact file changes, object binding, candidate construction.
- `android/app/src/main/java/im/majo/ctos/SshRestoreReview.java` — bounded full coverage/projection binding and credential HMAC redaction.
- `android/app/src/main/java/im/majo/ctos/SshConfigEvaluator.java` — explicit Request user/local context and declaration-only mode.
- `android/app/src/main/cpp/ssh_native_client.c` — current actual private/public check and encrypted-key preflight bounds.
- `android/app/src/test/java/im/majo/ctos/SshBundleTest.java` — synthetic encrypted transaction/CAS/approval boundaries.
- `android/app/src/test/java/im/majo/ctos/SshRestoreTest.java` — synthetic coverage/consent/provider-revision/source-binding regressions.

## External References / Versions

No internet lookup needed for this internal call-graph investigation. Current native dependency is locally pinned OpenSSH10.6p1; parser-specific strictness belongs to the parallel native source investigation. No dependency/version change is proposed.

## Related Specs

- `.trellis/spec/backend/app-secrets.md` — whole resulting manifest, private release, exact bundle/restore approval, original deadlines, snapshot and actual-effect guards.
- `.trellis/spec/backend/ssh-native.md` — focused declaration catalog and managed evaluator sections; declarationsOnly and no fake Request.
- `design/ssh-config-catalog.md` — completed lexical catalog, explicit remaining private/trust/reference and whole-manifest gaps.
- `design/ssh-config-evaluator.md`, `design/ssh-config-managed.md`, `design/ssh-launcher.md` — actual-context effective Options and remaining final-plan/connector contract.
- `design/handoff-ssh-terminal-client.md` — next action explicitly asks for supplied source/target/candidate semantic boundary, no recursive current vault.

## Caveats / Not Found

- This is research, not a completed semantic-validator design or code implementation. API shapes above are proposals.
- No production `attachSshValidator` registration or complete real SSH restore reviewer was found; original gate must remain closed.
- No authoritative private/public/certificate pairing or finite wildcard/Match contextual validation strategy exists in current manifest schema.
- Current validator callback lacks original request context/cancellation and registration revisions; integrating expensive native checks requires explicit core lifecycle work.
- Full-file native parser behavior/KDF interruption and resource limits were not executed here. Main/native researcher must confirm before freezing implementation scope.
- Research role intentionally did not load implement.jsonl/check.jsonl; relevant task PRD/design/implementation and specs were read directly. Only this research file was written.

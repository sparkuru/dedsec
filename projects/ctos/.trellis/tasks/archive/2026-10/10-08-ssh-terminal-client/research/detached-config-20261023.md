# Research: detached supplied-manifest SSH configuration input

- Query: identify the next bounded increment toward complete detached SSH manifest semantics after lifecycle migrations, without live session/role/public-view authority.
- Scope: internal, focused read-only source/document investigation; research output only.
- Date: 2026-10-23
- Active task: `.trellis/tasks/10-08-ssh-terminal-client`.

## Findings

### Recommendation

Implement a **native detached configuration source/input prerequisite** first. Separate the immutable file table and its liveness/cleanup from the live public-view capability. Adapt existing file IO and evaluator to that source, preserving the existing live-view adapter and the same pinned readconf. Provide independently owned detached input and selected-CONFIG-root constructors for ordinary evaluation and catalog. Do not register a validator/reviewer, change restore admission, or call this increment complete manifest semantics.

This is a coherent native increment with explicit real-parser, ownership and no-effects acceptance. A Java/JNI supplied-`VaultManifest` owner can follow without changing parser semantics; include it now only if the main session accepts the additional registry/wire/cancellation scope. Native-only completion must be described as a prerequisite, not a Java provider available to AppSecrets.

### Files found

| File | Responsibility |
| --- | --- |
| `android/app/src/main/java/im/majo/ctos/VaultManifest.java` | Authenticated decoded format, structural bounds/references; owns mutable body/secret arrays until close. |
| `android/app/src/main/java/im/majo/ctos/SshFileSnapshot.java` | Synchronous live-operation closure loan; copies authority tuple and metadata, retains public file bodies. |
| `android/app/src/main/java/im/majo/ctos/SshPublicFileLease.java` | CSPV live-view serialization and native lease ownership/revocation. |
| `android/app/src/main/java/im/majo/ctos/SshConfigEvaluator.java` | Borrow-only live Java facade, CSEQ/CSES/CSCD codecs, owner/cancel/close rules. Catalog is nested `Catalog`, not a separate production `SshConfigCatalog` class. |
| `android/app/src/main/java/im/majo/ctos/SshManifestInspector.java` | Actual-manifest material copies and bounded inspection; CONFIG explicitly deferred. |
| `android/app/src/main/cpp/ssh_file_view.[ch]` | CSPV allocation/validation, fixed file records, graph closure, deadline, guarded public lookup. |
| `android/app/src/main/cpp/ssh_view_handle.[ch]` | One global current public lease, token borrow/revoke/deferred cleanup. |
| `android/app/src/main/cpp/ssh_file_io.[ch]` | Virtual path resolution, managed FILE readers, directory enumeration and budgets; currently tied to `ctos_ssh_view`. |
| `android/app/src/main/cpp/ssh_config_eval.[ch]` | Shared readconf context/hooks, full Options ownership, ordinary/catalog modes, guarded results. |
| `android/app/src/main/cpp/ssh_config_handle.[ch]`, `ssh_config_jni.c` | 64-slot evaluation registry, opaque token/failed-open cleanup ownership, current live-view JNI opens. |
| `.trellis/tasks/10-08-ssh-terminal-client/research/openssh-10.6p1-config-evaluator.patch` | Production namespaced readconf adaptation and catalog early branch; sole SSH lexer/parser implementation. |
| `.trellis/tasks/10-08-ssh-terminal-client/research/android-config-evaluator-build.sh` | Pinned strict dynamic/static ARM evaluator, handle and catalog builds/effect observers. |
| `android/app/build.gradle` | Production native source/link lists and test tasks; no CMake file in this tree. |

### Existing ownership and API boundaries

1. `VaultManifest` supplies the right data boundary. `Entry` and `FileObject` clone secret/body arrays at construction (`VaultManifest.java:39`, `:60`); lists are immutable copies but arrays remain mutable and `close()` wipes them (`:256`). `decode()` authenticates file digests as part of decoded-format checks (`:111-122`); the outer vault authentication remains the caller's responsibility. Detached inspection must accept the actual exclusive authenticated loan, independently copy required arrays, and never close/mutate that loan.
2. Structural validation is already bounded to 128 files/1 MiB each/8 MiB total (`VaultManifest.java:22-27`). References exist, are unique, occur only on CONFIG, and form a depth<=8 DAG (`:193-210`, `:223-232`). It does not require one CONFIG root to reach every file, so independent/orphan CONFIG is valid manifest structure. Non-TOTP identity regex permits either `target:` or `key:` for both SSH entry kinds (`:185-187`); type-specific credential meaning is not established here. Passphrases must have at least one matching private identity (`:212-217`), but duplicate credentials/multiple keys sharing identity remain possible.
3. `SshFileSnapshot` is unsuitable as detached input: its only constructor takes `VaultOperation`, copying request/root/connection/vault UUID/generation/epoch/deadline/binding (`SshFileSnapshot.java:38-46`). Its `publicFiles` retain the caller's original objects (`:49-54`), so it is a synchronous loan, not a detached owned snapshot.
4. CSPV serialization includes that authority tuple (`SshPublicFileLease.java:97-115`), and native validation requires nonzero UUID/generation, deadline, request/connection/root/binding (`ssh_file_view.c:130-138`). It requires **all** file records reachable from the selected root (`:172-176`). Constructing dummy tuple fields or a synthetic validated `ctos_ssh_view` would disguise a detached parser source as live capability and is prohibited by the task contract.
5. Native public-view registry is actually a singleton: `static ... *current` (`ssh_view_handle.c:27-34`), and creation returns EBUSY while any current view remains (`:77-81`). Even native `borrow_owned_view` is documented as a host/test seam (`ssh_view_handle.h`) and still demands `ctos_ssh_view_live` (`ssh_view_handle.c:204-219`). Neither is a production detached-input solution.
6. Ordinary/catalog Java opens use `ViewBinding.token()/active()` and production always maps a real `SshPublicFileLease` (`SshConfigEvaluator.java:253-277`); all successful results subsequently call `liveView` (`:305-352`, `:390-396`). Do not feed detached tokens through this live seam or substitute a constant-true view.
7. Existing native evaluator transfers its borrow **only on successful open** (`ssh_config_eval.c:463-496`), owns full Options/catalog/readers, and releases only its borrow on retryable close (`:628-644`). Existing handle retains a `pending_borrow` if post-borrow open fails (`ssh_config_handle.c:15-23`). Any detached API must specify the same failure-ownership principle and retain cleanup owners if mapping/reader cleanup fails.
8. `SshManifestInspector.inspect(manifest, deadline, cancellation)` is the reusable Java ownership pattern (`SshManifestInspector.java:151-188`): ThreadLocal reentry rejection, one original <=60 s deadline, cleanup slots before copies, native calls outside AppSecrets, final cancellation/expiry check after wiping. Its report is permanently contentOnly/admissionComplete=false (`:112-119`), and CONFIG remains CONFIG_DEFERRED (`:165-166`). Exact passphrase matching rejects multiple entries instead of picking the first (`:199-213`, `:167`). Do not rewrite it into an admission provider as part of the source refactor.

### Exact native coupling to separate

- `ctos_ssh_io_init`, `ready`, `open`, `stat`, and `child` depend on `ctos_ssh_view_live`, `ctos_ssh_view_lookup`, and `io->view->files/count` (`ssh_file_io.c:11-15`, `:31-34`, `:65-72`, `:147-153`, `:177-183`). These are the full IO seams; changing only evaluator open is insufficient.
- Evaluator stores `borrow` plus `view` (`ssh_config_eval.c:66-68`); `live()` calls borrow_live (`:95-98`), `object_index` enumerates view files (`:228-239`), and open reads root/lookup/IO from view (`:481-486`). Close releases borrow after readers (`:635-642`). Those accesses need a common source abstraction.
- Keep `parser_lock/current` (`ssh_config_eval.c:84-86`, `:510-511`) as nonblocking parser serialization. This is distinct from the public lease singleton: detached/live inputs can coexist without occupying a live-view slot, but simultaneous parser runs may still return EBUSY safely.
- Catalog/effective getters retain READY/owner/deadline/cancel/mode guards (`ssh_config_eval.c:598-623`). Summary still has raw fields (`:587`) and Options stays native internal. Detached input must not turn either into a connection capability.

### Suggested interface shape (design proposal, names not frozen)

Use a small native internal source carrying a bounded read-only `files/count` table plus a lifetime context/check. File records may reuse the neutral `ctos_ssh_file`/slice representation; the source must have no UUID/epoch/role/connection/root capability fields. Keep original manifest ordinals or an explicit ordinal mapping for diagnostics.

```c
struct ctos_ssh_file_source {
    const struct ctos_ssh_file *files;
    unsigned int count;
    void *context;                    /* trusted native lifetime owner */
    int (*check)(void *context);      /* original deadline/cancel/revoke */
};
/* Proposed source-aware counterparts; retain legacy wrappers. */
ctos_ssh_io_init_source(io, source, close_stream);
ctos_ssh_config_eval_open_source(source, root_ordinal, request, &evaluation);
ctos_ssh_config_eval_open_catalog_source(source, root_ordinal, &evaluation);
```

The exact ownership API needs to be chosen before implementation: source retain/release tied to eval lifetime, or a clearly owner-borrowed source that cannot be closed before all evaluations close. Prefer an opaque detached-input owner with retained evaluation references if Java will later expose tokens. Preserve legacy open(borrow) success-transfer and retryable release via a live adapter; do not expose arbitrary callbacks over JNI or introduce Java callbacks during parser/file operations.

Detached input should independently own CONFIG body bytes plus **all file metadata/type/references**, not private bodies, passphrases, password or TOTP secrets. Metadata for non-CONFIG files is needed to distinguish wrong-type Include from absence and to preserve original ordinals. Non-CONFIG bodies are not read by config parsing. It may contain disconnected files and select each real CONFIG root independently; no fabricated super-root or invented alias/user. An empty/no-CONFIG input must have explicit behavior, not a fabricated catalog with scannedFiles=0 (existing CSCD requires >=1).

Its constructor validates neutral table/path/type/count/size/duplicate/file-vs-directory/reference invariants independently, copies into bounded owned storage, and establishes cleanup ownership before copying. Reuse existing path/record validation helpers where practical without relaxing CSPV validation. Liveness uses original BOOTTIME deadline and atomic cancellation; no deadline renewal per root/Include/evaluation. Cancellation must remain usable while parser is paused. Owner/PID/UID, no-effects and reader wipe/close retries remain unchanged. Native input close must retain any still-owned mapping/FD on failed cleanup.

Do not silently reset total job budget when processing up to 128 roots. Per-evaluation line/IO limits alone can multiply manifest work. A later whole-manifest orchestrator needs one original deadline and explicit aggregate work/coverage accounting. The first native increment can document per-evaluation work limits without claiming whole-manifest coverage.

### Policy and semantic gaps after source detachment

| Gap | Evidence and required later proof |
| --- | --- |
| All CONFIG files | Live catalog visits only selected-root static includes. Whole-manifest orchestration must process every CONFIG including orphan/disconnected ones; preserve per-file results and incomplete obligations. |
| Actual Include edges vs declared references | `object_index` checks path/type only (`ssh_config_eval.c:228-239`); file IO looks up any file in the table (`ssh_file_io.c:70`). Neither verifies current parent CONFIG references the actual included child. Graph reachability from some generic reference is not proof of this Include edge. A future graph-validation mode must compare actual resolved static edges with authenticated references using parent/child provenance. Do not invent exact-reference-set equality: references can also name key/trust resources, so that policy needs explicit typed semantic classification. |
| Context-free forbidden effects | Catalog patch's early branch lexes known directives but skips every non-Include semantic switch (`openssh-10.6p1-config-evaluator.patch:385-392`). `Match` only sets a flag. It can accept invalid Port/Match values and forbidden directives. Need shared native context-free policy traversal over all branches, not fake-request evaluation of literal rows or a Java lexer. |
| Dynamic Include/Host/Match/User | Existing catalog records %/$ Include sites as incomplete; ordinary evaluator requires real context. Neither omission flags nor sampled aliases enumerate all possible requests. Full provider must represent unresolved required obligations and reject admission until its contractual obligations are proved. |
| Keys/certificates | Material PARSED is not private/public mathematical correspondence, cert association/CA/principal/time validity, or complete traditional encrypted PEM decrypted-DER consumption. No filename suffix/same identity/generic CONFIG reference can supply those proofs. |
| Credentials | Structure does not establish exact password target+username or unique passphrase→actual-private-key relation. Inspector catches duplicate matching passphrases for inspected private files but does not validate password entries or all entry-target meaning. Do not emit password/passphrase digests. |
| Trust/restore review | Parsing every known_hosts record/hash/marker does not implement selector semantics, CA/revocation/principal policy or real source/target trust differences. Independent exact restore reviewer remains required. |
| Connection plan | Summary hasRawFields and raw Options are historical parsed state. Actual Request binding and every path/jump/forward/session field still need explicit final enforcement before any connector. |

Keep existing `VAULT_SSH_VALIDATION_REQUIRED`/review gates. No partial report may be presented as admissionComplete; full validator completion requires CONFIG/Include, every non-TOTP credential, key/cert association, trust and source/target review obligations, including deletion of the last SSH object.

### Smallest implementation scope and meaningful tests

Native prerequisite ownership: new internal file-source/detached-input module(s), `ssh_file_io.[ch]`, `ssh_config_eval.[ch]`; adapt build source lists/runner and add focused C tests. Existing `ssh_file_view` schema and singleton registry remain live-only. Avoid modifying AppSecrets, Java role/view APIs, material semantics or parser patch in the source-only increment. Shared helper extraction is acceptable if legacy validation is preserved by tests.

1. Actual supplied source B produces B's alias/port/Include results while a distinct live A exists. A's token, borrow count/liveness and later evaluation remain unchanged. Construct source/target/candidate C independently and preserve source ordinals including leading non-CONFIG files.
2. Detached input constructor deep-copies before parser use; mutation/wipe of caller arrays after construction cannot change evaluation. Native source sees CONFIG bodies only; no private/password/TOTP copied. All input/Options/readers are wiped and released on successful close, rejected parse, Error-equivalent native failure, cancel and expiry.
3. Exact root selection, nested/static glob lexical Include, independent orphan CONFIG and wrong-type target; retain existing missing/path/cycle/depth diagnostics. If graph-edge enforcement is outside scope, add a documented negative non-certification example rather than implying it was checked.
4. Ordinary evaluation preserves full Options and required User errors; catalog remains declarationsOnly with deferred sites, permits its documented invalid Port/Match examples, and rejects wrong-mode getters. Do not loosen legacy CSCD frame constraints.
5. Pause reader/parser deterministically; cross-thread cancellation returns without parser/core lock, result publication fails, getter cannot revive it. Exact deadline before copy/open/read/getter and after cleanup, no renewal. Wrong thread/PID/UID, reentry and parser EBUSY remain rejected.
6. Inject reader/unmap/owner-release failures; close retains still-owned resources and retry succeeds. A failed detached open cannot leak its owned input. Preserve live post-borrow failed-open/pendingBorrow tests and stale-token/registry capacity tests if handle code changes.
7. Zero host FS/env/NSS/DNS/socket/connect/exec effects using existing wrapped test observers; ordinary managed memfd operations remain allowed. Check FD baseline and effects0 separately from safe fixed diagnostics.
8. Rerun existing real evaluator/catalog/handle and file-view/client tests after IO adaptation. If Java/JNI is added, add supplied-manifest deep-copy/codec/final publication/reentry/Error/cleanup tests and independent fixture compilation; JVM mocks do not prove native or Android execution.

### Build commands and integration points

Existing commands below are source-verified proposals, **not run during this research**. Run through hako locally with no device/account/network. Gradle static test tasks build binaries; they do not execute ARM tests.

```sh
./hako bash -lc 'cd android && ./gradlew :app:testSshConfigEvaluatorHost :app:testSshConfigEvaluatorStaticHost :app:testSshConfigHandleStaticHost :app:testSshConfigCatalogStaticHost :app:compileSshFiles --offline --console=plain'
timeout 180s qemu-aarch64 build/app/verification/sshConfigEvaluatorStaticHost/ctos_ssh_config_eval_test
timeout 180s qemu-aarch64 build/app/verification/sshConfigHandleStaticHost/ctos_ssh_config_handle_test
timeout 180s qemu-aarch64 build/app/verification/sshConfigCatalogStaticHost/ctos_ssh_config_catalog_test
./hako bash -lc 'cd android && ./gradlew :app:testSshViewHandleHost :app:testSshNativeClientHost :app:testSshCallbackAdapterHost :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease --offline --console=plain'
```

After adding dedicated detached tests, wire a matching strict ARM/static target into the existing runner and execute its produced binary separately. The runner accepts a **new** `/app/build/...` directory and hard-pins readconf patch/source/NDK/archive inputs (`android-config-evaluator-build.sh:15`, `:97-118`); update declared inputs when adding files. Do not reuse historical `/tmp` binaries as current evidence.

- `android/app/build.gradle:225-298`: evaluator/source and test task definitions.
- `android/app/build.gradle:320-365`: shared-library inputs and explicit compilation/link source list. Add the neutral source implementation here and in the runner's standalone test link (`android-config-evaluator-build.sh:178-182`), otherwise standalone/production diverge.
- Preserve original 83-member OpenSSH archive, namespaced readconf, eight existing config JNI and 19 session JNI exports unless intentionally extending the Java/JNI increment. Recheck all current exports including material inspect separately; do not assert the eight config entries are all library JNI.
- If Java/JNI follows, focused existing tests: `:app:testReleaseUnitTest --tests im.majo.ctos.SshConfigCatalogTest --tests im.majo.ctos.SshConfigEvaluatorTest --tests im.majo.ctos.SshManifestInspectorTest`, then fresh full JVM/lint/normal Release and isolated fixture compilation with the existing properties. No Flutter change means Flutter/old device evidence cannot be promoted to new native validation.
- `./hako current` updates dist and is unnecessary for this analysis/prerequisite; use assembleRelease for routine build validation.

### External references and versions

No external access. Local pinned OpenSSH **10.6p1**, NDK **27.0.12077973**, Android API **28** native build baseline are recorded in the existing runner and design evidence. This research does not independently verify upstream changes or claim compatibility beyond pinned source.

### Related specs and design

- `.trellis/spec/backend/ssh-native.md:20-338`: material partial-only reports, catalog/effective separation, owner/borrow/IO/no-effects and strict parser tests.
- `.trellis/spec/backend/app-secrets.md:981-1077`: actual authenticated source/target loans outside locks, original deadline/cancel/registration/publication fences and closed production gates; subsequent scenarios cover later lifecycle migrations.
- `.trellis/spec/backend/quality-guidelines.md`, `error-handling.md`: correct layer checks, explicit failure/unsupported state and retained cleanup ownership.
- `design/ssh-validation-lifecycle.md`: complete offline obligations and final real-Request enforcement; its preview-only migration status paragraphs are historical, not evidence later lifecycle work is still missing.
- `design/ssh-config-{evaluator,managed,catalog}.md`, `design/ssh-manifest-inspection.md`: precise current parser, Java/native and partial-material contracts.
- `design/README.md` current 2026-10-23 entry: TOTP mutation lifecycle complete locally, semantic provider/restore/connector next.

## Caveats / Not Found

- No source modifications, builds, binary executions, network/device/account actions or git operations were performed. Only this research file was written.
- Interface names above are proposals, not existing callable APIs. Source/input ownership and aggregate whole-manifest budget must be frozen by implementation design before exposing Java tokens.
- Native-source refactoring has multiple IO callers, so it is not a three-file low-risk mechanical change. Preserve legacy adapters and run real regression/effect tests; do not treat static inspection as proof.
- Whole-manifest config policy, typed Include/key/trust graph semantics, key mathematical association, credentials, complete trust/restore review and connection plan are still incomplete after the proposed increment. Keep their obligations explicit and admission closed.

# Research: exact endpoint host-trust review and forgetting

- Query: Identify the pinned parser, owned native projection/rewrite and guarded management transaction needed for R5 host-key-change recovery, without accepting a changed key or weakening revocations.
- Scope: internal source investigation only; no builds, device/network access, git mutations or product-code changes.
- Date: 2026-11-02

## Findings

### Existing authority and a significant two-object boundary

`SshHostKeyAuthority.requireVerified` accepts KNOWN only without a rewrite proposal, accepts NEW only with exact approved evidence, and unconditionally refuses CHANGED/REVOKED/CERTIFICATE (`android/app/src/main/java/im/majo/ctos/SshHostKeyAuthority.java:171`). This is the authority to preserve. An offline management operation must not construct NEW evidence, invoke the live trust-CAS path, approve a replacement string, or resume a failed connection. A subsequent user-started connection must run fresh KEX and the ordinary NEW review; remaining records can still cause KNOWN/CHANGED/REVOKED, so forgetting alone never guarantees NEW.

There are **two currently valid managed trust objects**, and both can exist in one source manifest:

- `ssh/known_hosts`, ID `ctos-password-hosts`, identity `ctos-password-trust`, belonging to the fixed generated password profile (`SshPasswordProfile.java:13`, `:136`).
- `.ssh/known_hosts`, deterministic `id("hosts", path)` and `id("hosts-owner", path)`, belonging to direct key/config plans (`SshConnectionMaterial.java:18`, `:121`, `:144`).

`SshConnectionMaterial.checkShape` explicitly skips the old password paths at `:137`; `SshPasswordProfile.checkShape` checks the old pair and skips `.ssh/` at `:143`. Managing only `.ssh/known_hosts` would leave manual password trust recovery unavailable. Root should either cover both exact object identities or explicitly scope the increment to one. A public path alone is insufficient object selection.

Current production material validation accepts every KNOWN_HOSTS object whose actual inspection status is PARSED (`SshPasswordManifestValidator.java:56`). The material parser admits ordinary selectors, shared host lists, wildcards, `@revoked`, `@cert-authority`, and valid hashed tokens (`ssh_material_inspect.c:331`–`:383`; test `ssh_material_inspect_test.c:279`). Therefore ordinary literal singleton rows are not an established invariant of stored input, even though application-generated new rows are narrow. Do not justify a regex/line-split editor with “the application only writes literal rows.”

### Pinned parser APIs are present locally

The local fixed source is `build/ssh-native-g1/stock-run.lOHF9Oai/sources/openssh-10.6p1/`; Gradle names this baseline at `android/app/build.gradle:191`. It is ignored build input and must not be edited. Public declarations in its `hostfile.h:59`–`:119` expose:

- `hostkeys_foreach_file(path, FILE*, callback, ctx, host, ip, options, note)`: streams an in-memory file, optionally parses keys and computes target matching. `HKF_WANT_PARSE_KEY` asks for parsed keys; omitting `HKF_WANT_MATCH` keeps nonmatches, comments and invalid rows visible.
- `hostkey_foreach_line`: `linenum`, status, matching flags, marker, hosts token, raw key/comment and parsed `sshkey*`. Its pointers are callback-lifetime borrows; copy bounded metadata during the callback. Key ownership can be stolen by setting `key=NULL`, but that is unnecessary for bounded public projection.
- `HKF_STATUS_OK/INVALID/COMMENT/MATCHED`, `HKF_MATCH_HOST/HOST_HASHED`, `MRK_NONE/CA/REVOKE` and `host_hash(host, original_token, token_length)`.

Pinned `hostfile.c:770` uses `host_hash` for a leading `|` selector and `match_hostname` otherwise; malformed hashes produce INVALID when a query host is supplied. `hostfile.c:849`–`:914` provides the actual host token, marker, matching result and parsed key. Run the entire file without the match-only flag: invalid rows elsewhere must not disappear from management admission. Existing supplemental `hash_token` validation in `ssh_material_inspect.c:331` checks canonical 20-byte hash components even for context-free inspection; preserve this validation instead of weakening it during listing.

For actual key fingerprints, existing `ssh_hostfile_issuer.c:282` uses `sshkey_ssh_name` plus `sshkey_fingerprint(key, SSH_DIGEST_SHA256, SSH_FP_DEFAULT)`. Its lookup construction at `:249` uses the role host unbracketed at port 22, otherwise `[host]:port`, followed by ASCII lowercasing of the **internal lookup identifier only**. Reuse those exact semantics, including existing bracket spelling, rather than independently normalizing IPv6 or lowercasing the user-facing role identity. Existing issuer regressions cover uppercase and port/hash isolation (`ssh_hostfile_issuer_test.c:372`) and both valid trust paths (`:409`).

**Do not call stock `hostfile_replace_entries` to forget.** `hostfile.c:579` removes an entire matching ordinary row regardless of shared/wildcard selector breadth; it retains marker rows but reconstructs every retained row with `fprintf("%s\n", ...)` at `:618`. Its wrapper also owns filesystem rewrite/rename behavior. This violates an exact old-endpoint-only, no-plaintext-file and unrelated-byte-preserving management contract.

### Minimal native operation shape for root to freeze

A stateless supplied-body operation can use the same mechanism as material inspection: bounded owned Java/JNI copy, in-memory parser and public result, without a role/public-view/session handle or real network. The host owns source selection and proposed replacement bytes. Suggested **contract choices**, not implemented APIs:

1. Parse the actual selected trust object's body and project bounded records with line identity, public algorithm/fingerprint, marker and selector classification. A row ID should bind original object/body digest plus line ordinal and exact bytes; a Dart-provided selector/fingerprint is never authority.
2. For a user-entered or actual effective-plan `host + port`, invoke the parser with the canonical lookup identifier. A list cannot reverse hashed hostnames. Either show hashed records honestly as unlabelled hashes and require a target query before forgetting, or make the page target-query-driven. Root owns that UI/scope choice.
3. Select removable rows only when the actual parser matches the endpoint, marker is NONE, key is an approved ordinary software public key, and the selector is an exact singleton literal endpoint or a verified singleton hashed target. For literal singleton equality, use actual string equality against the issuer-compatible lookup; `[`/`]` occur in valid bracketed endpoints and should not be rejected as wildcard syntax. OpenSSH hostname patterns here use `*`, `?`, comma lists and negation; a broad selector is not proven narrow merely because it matched one query.
4. Refuse an endpoint-forget candidate if removing a matching shared/wildcard/unsupported/certificate ordinary row would be necessary to complete the requested endpoint recovery. Do not remove a whole shared row or leave an undisclosed matching ordinary record and claim the endpoint was forgotten. Preserve **every** revocation and CA record. The final product contract must state whether matched marker records block the operation or remain visible as a preserved independent policy; either way never offer this as a REVOKED bypass.
5. Produce the replacement by copying original input byte spans and omitting only the selected removable physical lines. The upstream parser sets `line[strcspn(line,"\n")]=0` at `hostfile.c:808` and exposes a duplicate at `:815`, so its `line` is not a byte-preserving serialization source. Track original physical line offsets independently and correlate by `linenum`; preserve indentation, comments, CRLF, blank lines, unrelated rows and final missing newline exactly. This span bookkeeping is framing, not a replacement host-key grammar.
6. Return an owned replacement plus exact base/replacement digests and reviewed removed-record metadata to Java only; MethodChannel receives bounded public metadata and an opaque ticket/binding, never body bytes or caller-writeable replacement content. No incoming key is part of this management write.

Keep current one-file 1 MiB, line 65,536-byte and 16,384-line limits (`ssh_material_inspect.h:5`). Existing `ctos_ssh_material_deadline` admits at most 60 seconds and uses CLOCK_BOOTTIME (`ssh_material_inspect.c:41`); a management Request can retain the established 120-second original bundle deadline while each native call receives `min(original, now+60000)`, with no request renewal. Check original cancellation before/after synchronous calls and native deadline during row callbacks; do not claim Java cancellation preempts any arbitrary synchronous parser call. Explicit row/output bounds are required before allocating a public list: native input line bound is much larger than the existing 128-file UI bound.

The existing parser uses upstream xalloc paths (`hostfile.c:815`) just like the shipped material inspector; this research does not establish new OOM recovery. Keep fixed generic errors and bounded logs; a query with a malformed hashed row can reach an upstream debug message containing the initial hash token (`:856`). Audit the chosen logging linkage rather than assuming all parser errors are data-free.

### Java/JNI and guarded bundle integration

The clean Java seam is a new supplied-body `NativeBinding` wrapper analogous to `SshKeyMaterial`: injected clock/binding for pure JVM tests, strict versioned bounded wire decoding, original deadline and independently owned arrays, finally wipe (`SshKeyMaterial.java:18`, `:69`–`:105`). A focused new JNI export/helper can follow `ssh_material_jni.c:16`–`:56`: validate Java arrays, bounded native copies, preserve pending exception, final deadline check and wipe on every exit. `SshManifestInspector` currently exposes only aggregate known-host record count, not rows (`ssh_material_inspect.c:355` and `SshManifestInspector`); extending its historical fixed 60-byte CSMI format implicitly would break its contract. Prefer a distinct wire/API or an explicit new operation.

For persistence, use a single `SshBundleProposal.Change.replace(old, replacement)` that retains FileType/ID/path/identity/references and changes only actual known-host bytes (`SshBundleProposal.java:118`). Other files, metadata relationships, credentials and TOTP are unchanged. Even an empty resulting body retains the known-host object because configs reference it; deleting the object is a different and unsupported operation. Source and candidate both pass the production validator.

The reusable management pattern is `SshKeyDeletion.create` (`SshKeyDeletion.java:25`) plus `SshProfilesBridge.deletePreview` (`:300`):

- Capture original `Request`/BundleContext/epoch/revision/deadline, obtain owned `vault.sshLibrarySnapshot(context)`, prepare a source-bound exact proposal, apply and `validateSshCandidate`, publish a metadata ticket after final checks.
- `AppSecrets.sshLibrarySnapshot` authenticates and validates the real source, pins its ciphertext digest to `context.snapshotBinding`, checks revision and fences publication (`AppSecrets.java:617`). Do not reopen a different current source later while retaining the same review.
- Commit uses a distinct admitted request ID and **original** ticket owner/context/deadline; current `SshProfilesBridge.commit:509` checks kind/binding, creates an adapter bound to the exact proposal, and calls `bundleRequest`/`commitBundle` under `SshPasswordManifestValidator.within(origin.deadline)`. Its final source/epoch checks and after-write failed-delivery handling produce `VAULT_COMMIT_UNCERTAIN` and lock (`:565`). Current code uses a boolean deletion argument and `selected.deleting != null` to distinguish import from keyDelete; directory code and the earlier numeric kind scheme were removed. Add an explicit third host-trust kind without reviving directory behavior; cross-kind consumption must remain impossible.
- Busy/connection/pending ticket checks, original consumer/foreground/session checks (`Request.check:68`), immediate context cancellation, commit ownership and cleanup must apply to trust preview/commit too. An old changed-connection event is only explanatory context, never a fresh management or reconnect capability.
- Actual bundle admission validates whole source and candidate before one registered operation (`AppSecrets.java:2400`); preserving the existing source-bound guard avoids inventing a weaker offline path through NEW-only `SshTrustProposal`/`updateTrust`.

### Focused validation and likely changed files

Native tests should prove actual parser/key fingerprints and endpoint matching; two ports; uppercase; IPv4/IPv6/bracket spelling; exact literal and hash rows; duplicate ordinary keys/algorithms; nonmatching and matching shared/wildcard/negation selectors; marker/certificate/unsupported rows; malformed hash/line/NUL/limits; byte-for-byte nonremoved content with CRLF and missing final newline; timeout/system failures, output clearing and zero file/network/exec effects. Reuse `ssh_material_inspect_test.c:55`–`:145` wrapped-effect, stream cleanup and clock-injection patterns, and issuer endpoint fixtures above. New APIs need a separate test mode/runner or a deliberately extended existing material test target; no such trust-management command exists yet.

JVM tests should exercise strict wire parsing and ownership using an injected native binding, exact object selection across both legal paths, reviewed replacement-only graph changes, missing/stale/source-changed object, ticket replay/cross-kind/cancel/expiry, and uncertain terminal delivery with the existing real AppSecrets fake-store seams (`SshKeyDeletionTest.java:210`, `SshBundleValidationTest.java`, `SshBundleTest.java`). Do not use fake parser results as evidence the native selector policy is correct.

Flutter needs typed trust records and review tickets, host+port/path context, old actual fingerprints, clear distinction between forgetting and trusting a new key, explicit confirmation and guarded late/background/locked outcomes. Actual renders and human review remain separate from widget assertions. The current fixed error copy lives at `lib/ssh/api.dart:390`; any recovery CTA must preserve refusal and navigate to an independent protected management flow.

Likely implementation files: focused new native trust helper/header and test, JNI wrapper, Java native wrapper and owned proposal class, `SshProfilesBridge.java`, a typed Dart API/page plus library entry, related JVM/widget/platform fixtures and project design/spec documents. Build wiring is explicit: production helper/JNI sources must be added to `android/app/build.gradle:380`/`:456` and the strict compile command at `:486` if separate files are introduced. The current material runner `android/ssh/android-material-inspector-build.sh:125`–`:168` compiles tests against the fixed production archive and wraps effects; keep that pinned closure unchanged. A C-only runtime, Java fake seam, Android JNI execution and real fresh KEX/NEW approval are distinct evidence gates.

## Files found

- `android/app/src/main/cpp/ssh_hostfile_issuer.c` — current exact endpoint lookup, actual key facts and NEW-only candidate generation.
- `android/app/src/main/cpp/ssh_material_inspect.[ch]`, `ssh_material_jni.c` — bounded actual body parser, hash validation and native/JNI lifetime patterns.
- `build/ssh-native-g1/stock-run.lOHF9Oai/sources/openssh-10.6p1/hostfile.[ch]` — available pinned parser, matching and unsuitable stock filesystem rewrite.
- `SshHostKeyAuthority.java`, `SshPasswordProfile.java`, `SshConnectionMaterial.java` — preserved connection trust authority and the two valid exact trust objects.
- `SshProfilesBridge.java`, `SshKeyDeletion.java`, `SshBundleProposal.java`, `AppSecrets.java` — protected management preview, source-bound exact replacement and one guarded mutation.
- Native material/issuer tests, JVM deletion/bundle tests, `android/app/build.gradle`, material runner — reusable verification seams and explicit source wiring.

## Related specs

- `.trellis/spec/backend/app-secrets.md` — original bundle context, owned candidate, one effect, cancellation and uncertain outcomes.
- `.trellis/spec/backend/ssh-native.md` — actual host evidence and NEW-only trust CAS; fixed parser/build inputs.
- `.trellis/spec/backend/ssh-key-deletion.md` — exact protected management tickets and preservation across a graph mutation.
- `.trellis/spec/backend/ssh-key-config-connect.md` — one native configuration source and actual direct target.
- `.trellis/spec/frontend/app-secrets.md`, `type-safety.md`, `terminal-interaction.md` — protected routes, typed payloads and refused connection behavior.

## External references

None consulted. All parser claims refer to the fixed local OpenSSH 10.6p1 source and current checkout, not an external latest-version assertion.

## Caveats / Not Found

- No host-trust row list/forget native API, Java management proposal or production UI was found. This is a new bounded operation, not reuse of a ready-made safe delete function.
- The contract in `design/ssh-host-trust-management.md` now covers both objects with target-query-driven groups, refuses a group forget when matching marker/shared/unsupported rows remain, and caps public matches at128 per object. The user subsequently approved implementation with “开始”; current local implementation/validation is recorded in the contract and verification index. This original source research itself is not runtime evidence or a claim that full R5/trust import/certificates/recovery are complete.
- No tests, compilation, devices, real SSH accounts or source trust files were accessed. All proposed interfaces and validation are future work; source line references can move with concurrent edits.

## Continuation refresh against b1064c7

Focused read-only recheck confirmed both exact object identities (`SshPasswordProfile.java:13`,
`:147`; `SshConnectionMaterial.java:144`, `:442`, `:490`), issuer lookup (`ssh_hostfile_issuer.c:248`),
and KNOWN/NEW/CHANGED/REVOKED/CERTIFICATE authority (`SshHostKeyAuthority.java:171`).
The parser bounds/deadline and upstream span caveat remain unchanged. Current supplemental hash
validation is at `ssh_material_inspect.c:334`, parser at `:357`–`:382`; old starts at331 are approximate.
`SshProfilesBridge.deletePreview` is now at274, commit at509, uncertain at565; no directory methods remain.
Gradle pinned baseline is at192, material source list at381, strict compile list at494. Older line anchors
above describe their original read and are not authority to revive removed code. No build/test/device
or network operation was performed in this refresh.

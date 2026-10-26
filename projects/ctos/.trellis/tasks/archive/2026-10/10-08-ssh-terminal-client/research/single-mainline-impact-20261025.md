# Research: Single current SSH manifest mainline and existing TOTP data

- Query: Collapse the just-added parallel SSH manifest variants into the latest single formal model without deleting or invalidating previously delivered TOTP data.
- Scope: internal source and recorded project evidence only; read-only investigation, no builds/devices/network or product edits.
- Date: 2026-10-25

## Findings

### Existing TOTP is a different compatibility concern from unused SSH

`design/authenticator.md:13` records user acceptance/archiving on 2026-10-13, and `:15` records an installed APK and device verification. These are historical delivery records, not current runtime evidence or authorization. TOTP and SSH use the same vault (`design/authenticator.md:37`, `:39`). Removing all old plaintext decoding would therefore risk an already delivered feature, even though the user explicitly does not require preservation of an unused SSH implementation variant.

The crypto envelope is independent: `AppSecrets.create` uses `VaultManifest.empty().encode()` (`AppSecrets.java:575`), then constructs the existing envelope (`:577`). Changing the sole manifest writer need not change CTOSVLT1, crypto/KDF, UUID, generation or AAD. Wire discriminators are internal format markers, not a reason to expose multiple product modes.

### Where the dual-mainline policy currently lives

- `VaultManifest.java:69`, `:79`, `:90`, `:97`: instance `formatVersion`, private version-selected constructor, `version2` factory, and version-preserving `withObjects`.
- `VaultManifest.java:108`, `:154`, `:185`: decoder chooses legacy/current layout and instantiates that corresponding model.
- `VaultManifest.java:201`, `:224`, `:255`, `:302`: writer and validation branch on instance format.
- `SshRestoreReview.java:220`: `requireSupportedFormat` rejects every model with format!=1, including empty/pure-TOTP models, rather than checking the actual unreviewed relationship/account metadata.
- `AppSecrets.java:673`, `:680` and `SshRestoreReview.java:171`: the two preview gates and defensive bind call that helper.

Small coherent replacement: one immutable model with the four lists, a two-list convenience constructor that supplies empty relationship/account lists, a four-list constructor, no version-selected public/package API or per-instance format field, and an always-current encoder. `withObjects` always retains both metadata lists. Decode current layout into that same model. Keep any old TOTP decoding inside the serialization boundary as an input adapter only, rather than a second model/writer/business flow.

### Pure TOTP legacy input is the narrow useful adapter

If retaining an old-input adapter, the narrow evidence-backed scope is legacy plaintext with no files and only TOTP entries, including an empty vault. After strict legacy parsing, reject any file or non-TOTP entry; then create the current model with empty relationship/account lists. Do not infer SSH bindings or accounts from legacy identities. This makes deprecated SSH plaintext explicitly unsupported while preserving delivered TOTP data. Old-input acceptance is a contract choice for the main session; no actual device vault contents were inspected here.

Pure TOTP common paths do not enter an SSH validator: `hasSsh` is defined at `AppSecrets.java:550`, preview returns before a loan at `:757`, TOTP read checks `work.mixed` at `:1052`, and TOTP mutation does the same at `:1262`. TOTP mutations already build a candidate and encode it (`:1344`–`:1350`), so the first successful normal content mutation can naturally persist the current sole format without a separate migration operation or reset. Keep original ciphertext binding/epoch/effect guards; decode/normalization alone must not write storage.

### Authenticated bytes and normalized model bytes must not be confused

`Verified.plaintext` is the exact decrypted buffer (`AppSecrets.java:448`–`:464`, `:2945`–`:2955`). `ImportPreview` hashes that raw plaintext (`:489`); `checkImportSource` rechecks it (`:858`–`:862`). Preserve these raw authenticated bindings. Do not silently replace that field with canonical current bytes while leaving source cipher provenance unchanged.

`requireUnchangedLoan` encodes a callback loan and compares against the original authenticated buffer (`:768`–`:772`). If decode normalizes old input to the sole current model, its encoding differs by the format marker and new empty-list counts even when untouched. Every old-input path that actually reaches this helper would falsely report changed content unless the expected baseline is normalized too. Examples of unconditional SSH loan validation are `openSshPublicRead` (`:2591`–`:2597`) and `openSshSecretRead` (`:2375`–`:2381`); a narrow pure-TOTP adapter normally has nothing valid for those APIs, but testing must not assume every possible call skips the helper.

Two sound options:

1. Generate an independent current canonical baseline from the authenticated original model, compare the callback loan against that baseline, and wipe both temporary encodings. Keep raw plaintext/source cipher bindings separately. Centralizing this in the one helper avoids changing every caller. It must still detect every changed secret/file/metadata field, and must never derive the baseline from the possibly mutated loan.
2. For a legacy-pure-TOTP-only adapter, explicitly prove all admitted old-TOTP workflows skip SSH callback loans and retain raw-equality behavior for current SSH data. Add rejection/error tests for attempts to use an SSH-only API against legacy pure-TOTP data. This is smaller but requires checking those unconditional SSH paths and documenting their error behavior.

Do not simply remove `requireUnchangedLoan` or compare a loan with its own second encoding.

### Operations that otherwise continue writing old plaintext

- `export` authenticates and returns the original envelope bytes (`AppSecrets.java:605`–`:615`). Preserving an existing backup as an exact snapshot is useful and does not require a second writer/model; it should remain explicit in the contract.
- `changePassword` currently re-encrypts `verified.plaintext` verbatim (`:630`–`:633`). A sole-current durable-content policy should encode `verified.manifest` into a separate owned canonical buffer for the new envelope/readback, while retaining the raw source buffer for authentication/provenance and preserving UUID/generation/salt/nonce semantics and guards. Existing current-layout data can remain byte-exact; old TOTP data is semantically equal after normalization.
- `importCommit` also encrypts raw `pending.source.plaintext` (`:820`–`:823`). To make restored durable content current, encode the source model into an independent temporary buffer, verify/readback that exact new buffer, and retain `checkImportSource`'s checks over the original authenticated input. Include finally-wipe on all exits and keep old preview/target-binding/error/effect rules.
- `setQuick` commits the existing envelope unchanged with new shortcut material (`:884`–`:890`). This is wrapper-only storage, not a reason to normalize plaintext or lose a valid old vault. `quickUnlock` only decrypts/decodes (`:893`–`:902`) and must continue accepting preserved old TOTP input without writing.

### Restore should be gated by missing semantics, not an implementation version

Current `SshRestoreReview.expectedCoverage` covers FILE/CREDENTIAL only (`SshRestoreReview.java:226`–`:230`); relationship/account rows are not represented. Replace the per-format helper with an actual-content precondition, e.g. reject a manifest when `!keyBindings.isEmpty() || !accounts.isEmpty()` until graph-aware review is implemented. Empty/pure-TOTP current data should no longer fail merely because it uses the latest writer. Modern models containing SSH objects but no relationship/account metadata can retain the existing validator/reviewer coverage path; production registration requirements still enforce their existing closure. Do not erase metadata to pass review.

### Tests and affected classes

The only production classes using `formatVersion`/`version2` are `VaultManifest` and `SshRestoreReview`; preview call sites are in `AppSecrets`. `VaultSshMetadata` already expresses the latest immutable structural model and need not be version-selected. Four candidate reconstruction sites already call `withObjects` and can retain that API.

The two just-added tests are intentionally version-policy-heavy and should be renamed/reframed around the sole current schema and content-review gates:

- `VaultManifestV2Test.java:18` and `:28`: literal old mixed-SSH and new golden bytes; `:105` validates old default bytes; `:158` tests parallel empty variants; `:406` loops both versions. Its old mixed-SSH golden should become a deprecated-input rejection vector or a separately constructed current golden base, not a production writer branch. Keep independent exact current wire, structural/malformed/ownership limits.
- `VaultManifestV2LifecycleTest.java:59`–`:94`: both versions of all three fixture shapes; `:340` expects normal create to remain old; restore matrix asserts per-format rejection. Reframe to current no-metadata vs actual key/account metadata, plus dedicated raw legacy-pure-TOTP inputs. Preserve four mutation families, no-effect reference failures, original callback priority, cancellation, old preview and explicit metadata defense.
- `VaultTest.java:62` constructs most fixtures using the default constructor and will naturally follow the new sole writer; its password-change exact-plaintext regression (`:376`–`:386`) remains appropriate for current input. Add a separate legacy TOTP semantic-preservation case. The corruption offset at `:369` should be checked after changing tail framing rather than assumed to mean the same old field.
- Existing native/JNI material wire versions and envelope version1 are separate protocols. Hits such as `SshManifestPrivateMaterialPlatformTest.java:49`, CSMI/CSKA/CSKP/CSCF test `putInt(1)` are not dual SSH product modes and must not be changed by a global v1/v2 text replacement.

Useful dedicated regression matrix: raw legacy empty/TOTP unlock/list/code/export/quick-unlock/no read-side writes; first successful TOTP mutation/current rekey/current restore preserve every seed/metadata and remain atomic; wrong password/cancel/malformed legacy input keep ciphertext and shortcuts unchanged; legacy mixed-SSH input rejects; untouched canonical loans pass while mutation still rejects; all constructors/default create/withObjects write the current layout; content-review gates depend on metadata, including both source and target and direct bind.

## Files Found

- `android/app/src/main/java/im/majo/ctos/VaultManifest.java` — typed model, old/current wire branching and candidate projection.
- `android/app/src/main/java/im/majo/ctos/AppSecrets.java` — authentication, original byte bindings, callback loans, durable crypto writes and all TOTP/SSH operations.
- `android/app/src/main/java/im/majo/ctos/SshRestoreReview.java` — per-format rejection and current review coverage gap.
- `android/app/src/main/java/im/majo/ctos/VaultSshMetadata.java` — current immutable relation/account model independent of crypto envelope.
- `android/app/src/test/java/im/majo/ctos/VaultManifestV2Test.java` — schema golden and two-format policy tests.
- `android/app/src/test/java/im/majo/ctos/VaultManifestV2LifecycleTest.java` — actual mutation families and two-format restore policy fixtures.
- `android/app/src/test/java/im/majo/ctos/VaultTest.java` — default-built fixtures and core backup/rekey behavior.
- `design/authenticator.md` — historical acceptance/installation and shared-vault scope.

## Related Specs

`.trellis/spec/backend/app-secrets.md`, `.trellis/spec/backend/ssh-manifest.md`, and `design/ssh-manifest.md` describe the just-added parallel policy and must be superseded by the user's latest single-mainline direction. `design/authenticator.md` and `design/app-authentication.md` establish the delivered shared TOTP data boundary. Historical verification must remain historical rather than be rewritten as if the new policy had been tested then.

## External References

None needed: this investigation concerns local implementation and recorded delivery evidence; no external protocol behavior was inferred.

## Caveats / Not Found

No actual vault contents, connected devices or accounts were inspected. No build/test/runtime was run. The exact legacy adapter scope and whether password change/import always normalize must be frozen by the main session; recommendations above identify consequences rather than claim those changes exist. Line numbers refer to the source observed before concurrent single-mainline implementation begins.

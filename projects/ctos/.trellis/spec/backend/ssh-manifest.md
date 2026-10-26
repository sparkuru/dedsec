# One formal SSH manifest model

## 1. Scope / Trigger

The latest user direction supersedes the parallel-version policy. Follow
[the sole contract](../../../design/ssh-manifest.md). Maintain one latest model/writer and
continue toward practical SSH use. Preserve delivered TOTP data through a narrow input adapter.

## 2. Signatures

`new VaultManifest(entries, files, keyBindings, accounts)`; two-list convenience means empty
metadata, not a legacy mode. No instance formatVersion/version2 factory. `source.withObjects`
retains metadata and revalidates references. Sole encoding uses internal wire tag2.

## 3. Contracts

Keep strict immutable metadata, exact global IDs/types/owner/target/reference rules and
all limits from the contract. Validate metadata for every manifest. No inferred relationships,
normalization or authority. Wire tag is serialization framing, never a product mode.
Envelope CTOSVLT1/KDF/AAD/generation/12MiB, other native protocols and APK signatures unchanged.

Legacy tag1 input is accepted only for empty/pureTOTP with no files; reject old SSH objects.
Decode maps directly to the sole model, no old writer or read-side storage effects. Raw
Verified plaintext/envelope bindings remain original. Export returns exact envelope;
normal content mutation, rekey and restore encode current model with independent buffer/wipe
and exact readback. Restore derives its encoding independently from authenticated original
plaintext, not mutable retained preview arrays. Wrapper-only commits may retain original ciphertext.

Unchanged-loan raw equality stays fast. If normalization is required, derive an independent
expected encoding from authenticated ORIGINAL, never the loan; compare and wipe both buffers.
All four candidate builders keep metadata and existing exclusive clones/Throwable cleanup.
Decode failures wipe provisional owned arrays; encode never wipes caller/source arrays.

## 4. Validation & Error Matrix

Fixed invalid-content errors for malformed counts/text/IDs/enums/refs/duplicates/trailing data.
Restore checks actual nonempty keyBindings/accounts, not format. `requireReviewedMetadata`
runs after each side authentication and before its validator, plus bind defense; throws fixed
VAULT_SSH_REVIEW_REQUIRED until graph-aware projection exists. Empty/pureTOTP latest data
works normally. Keep source-first errors, original validator/reviewer requirements, guarded
effects/cancellation/old preview/uncertain handling. Do not register incomplete providers.

## 5. Good / Base / Bad Cases

Good: all constructors/create emit the latest layout, and referenced object edits preserve metadata.
Base: old delivered TOTP opens without writes and rekeys into the same latest model.
Bad: expose a legacy writer, block pureTOTP restore solely by wire tag, erase a vault, or
silently drop metadata to pass a review that does not display it.

## 6. Tests Required

Independent current golden, malformed/strict text/count/reference/owner/limit/immutability
vectors; four actual mutation families/loans/wipes/no-effect rejection. Default current create,
current export/rekey exactness, metadata-content restore gates and existing error precedence.
Dedicated raw legacy TOTP read/no-write/export/quick unlock/mutation/rekey/restore preservation,
wrong password/cancel failures, old SSH rejection and independent unchanged-loan mutation detection.
Rename versioned suites to VaultManifestTest/VaultManifestLifecycleTest. Main serializes hako
checks; no device/Android runtime inferred. Record actual results and practical SSH work remaining.

## 7. Wrong vs Correct

Wrong: maintain two business mainlines or erase deployed TOTP compatibility to simplify SSH.
Correct: one formal model/writer, an isolated input adapter, content-based admission and real
progress toward the usable connection path.

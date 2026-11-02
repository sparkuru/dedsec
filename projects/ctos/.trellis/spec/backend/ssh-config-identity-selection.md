# Imported config entries and explicit managed-key selection

## 1. Scope / Trigger

Use the [approved product contract](../../../design/ssh-config-identity-selection.md) when
importing/evaluating `.ssh/` user CONFIG. IdentityFile is a bounded public hint; the actual
credential is explicitly selected by managed keyId for this connection. No source rewrite,
directory scanning, path migration, basename/default-key guess or password fallback.

## 2. Signatures

`SshConfigEvaluator.prepareProfileDirectDetached(guard)` / `profileDirectPlan()` return
`ProfilePlan` with separate `identityHints`; its inherited managed `identityFiles` is empty.
Native reserve/getter names are `reserveProfileDirect` / `profileDirectPlan`.
`prepareDetachedProfileResourceAudit(guard)` / `profileResourceAudit()` use native
`reserveProfileResourceAudit` / `profileResourceAudit` and the separate native
`CTOS_CONFIG_PROFILE_RESOURCE_AUDIT` policy. Old direct/resource prepare/getters remain strict.

Profile plan CSPP: magic0x43535050/version1; same ordered scalar header, tuple and hostfile
strings as strict CSCP, with hint_count in the identity_count slot. Max149433 bytes,
hint_count<=256, each hint<=512 UTF8 bytes; hostfile strings remain managed paths.
Profile resource CSCR: magic0x43534352/version2/flags3, original nested CSCA and six-u32
resource rows/caps107328 unchanged. New state6 IDENTITY_HINT only kind1/target0. Original
CSCRv1/flags1 and CSCPv1 reject profile modes/frames; profile getters reject generic frames.

Public `ssh.library` host adds optional `identityHints:List<String>` default empty. Keep existing
alias/binding/host/port/username/keyIds fields; configConnect wire remains exact alias/binding/
keyId/username/size/phrase fields with no caller-controlled permissive policy flag.

## 3. Contracts

Profile resource policy applies only to user `.ssh/` CONFIG roots, excluding generated
`.ssh/ctos-manual`; legacy password and generated manual keep generic strict checks. Preserve
provable STATIC identity targets/direct edges for old source-graph compatibility; unavailable,
external, relative or context-dependent IdentityFile arguments yield non-authoritative hints,
never foreign FileObject paths, fake reference targets or IO. Other resources and Includes
remain strict. Do not call a failing old resolver then swallow its already-latched diagnostic.

Profile direct preserves native effective Host/Match/first-value/list semantics and every other
consumed/refused option. Only actual explicit effective IdentityFile origins become hint text;
defaults cannot masquerade as explicit source declarations, and none is not a key file. Hint
text is never expanded into a file lookup. Keep actual known-host resolution and trust policy.
PubkeyAuthentication no still prevents key auth; independent selection cannot override it.

Import/edit, stored source/candidate validator and reference refresh after key add/replace/delete
must agree on this product policy under original budgets/cancel/owner/effect. Never disable
whole-manifest/reference validation or migrate CONFIG bytes. State6 is a completed classification
as non-resource hint metadata, not a pending path-resolution obligation: hasDeferred and report
resourcesIncomplete keep their state2/3/5 meaning. Profile policy/state6/target0 explicitly mark
non-authority; the inspector remains declarationsOnly=true/admissionComplete=false. Neither a
classified hint nor preserved STATIC proof authorizes a private-key release.

Library lists current supported managed keys for a valid pubkey profile; config-first with no
keys remains listable and is not a config import error. Hints<=256/host, <=512 UTF8 bytes each,
no controls, aggregate<=4096 entries/1MiB. Reject excess; never truncate. Treat desktop paths as
protected public text, not `_sshManagedPath`, logging content or a read capability.

Both connector pre-phrase-save and pre-TCP preparation re-evaluate the current profile, key,
configBinding/epoch/revision/pubkey/target/trust. Remove only IdentityFile membership authority.
Existing Account ownership, actual key/public match, CSAS/host/KEX and final typed release remain.
Wrong selected key fails auth without trying another key/password. No new saved default-key table.

## 4. Validation & Error Matrix

| Condition | Required outcome |
| --- | --- |
| No keys / desktop IdentityFile | Preserve config bytes and list profile/hints; require explicit imported-key selection |
| Current selected key has different managed basename | Use exactly that key after normal proof; no config rewrite |
| Wrong wire/mode/state-kind-target/UTF8/control/limit | Reject entire result and wipe temporary buffers |
| Missing/wrong Include or trust resource / unsafe config | Existing strict refusal, no hint exception |
| Pubkey disabled / stale binding / missing or replaced key | Refuse before phrase save/TCP/secret release |
| Cancel/expiry/lock/late durable outcome | Original lifetime/cleanup/uncertain receipt rules |

## 5. Good / Base / Bad Cases

Good: import six synthetic Hosts with two quoted `/home/source/...` key hints before any key,
then select an actual unrelated-path managed key for one alias. Base: old portable STATIC edges,
generated manual and legacy password remain valid. Bad: coerce the foreign hint into a managed
path, choose the first key, drop other options or trust an imported known_hosts silently.

## 6. Tests Required

Actual native strict/profile mode separation, tuple/hints/explicit origins and zero effects;
foreign/relative/dynamic/none/duplicates/control/oversize values; wrong-mode getters; negative
Include/trust/unsafe/Pubkey cases. JVM typed frame and owner cleanup, old source compatibility,
config-first/key addition/deletion, both pre-save/pre-TCP guards and exact CSAS key selection.
Flutter no-key profile list, explicit singleton/two-key choice, original hints, cancel/background/
stale response, no auto fallback and accessible narrow/large-font dialogs. Real synthetic auth,
Android JNI/SAF and human experience are distinct gates; report only checks actually run.

## 7. Wrong vs Correct

Wrong: relax generic VaultManifest.path, skip all resource audits, or remove all old identity
edges and make old vaults fail validation. Correct: explicit profile policy and typed hint result,
preserve existing provable STATIC metadata and all nonIdentityFile/credential proof boundaries.

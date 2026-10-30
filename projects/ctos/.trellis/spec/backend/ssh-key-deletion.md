# Managed SSH private-key deletion

## 1. Scope / Trigger

Extend the existing single model and guarded bundle lifecycle per
[the product contract](../../../design/ssh-key-deletion.md). Delete only the reviewed managed
identity; preserve external originals and unrelated vault data. This is not remote revocation.

## 2. Signatures

`ssh.keyDeletePreview(requestId,keyId)` returns exact public metadata and an owned ticket;
`ssh.keyDeleteCommit(requestId,ticketId,binding)` returns `{deleted:true}`;
`ssh.keyDeleteCancel(ticketId)` returns `{cancelled:bool}`. See the product contract for exact
payloads. Library host rows expose bounded unique `missingIdentityPaths`, default empty.

## 3. Contracts

Use the existing source-bound SshBundleProposal for selected PRIVATE, canonical paired PUBLIC,
KeyBinding, exact linked SSH_KEY_PASSPHRASE entry and all dependent KEY Accounts atomically.
Preserve password Accounts, other keys/phrases, TOTP and trust. No shared-public identity guess.
Preserve user CONFIG bytes; prune only removed references. Remove reserved generated MANUAL
only for this identity and only without an inbound retained CONFIG reference. Otherwise retain
its bytes and remove the missing identity edge.

Preview owns original Request/context/proposal, epoch/revision/registration/deadline/cancel.
Commit uses a fresh admitted request ID without renewed authority or budget; require exact
ticket kind/binding, handoff and once effect. Keep existing uncertain durable-write locking
and terminal receipt semantics. Cancel/expiry/background/lock/source change close owned data;
do not free a commit-owned proposal before its write outcome is known.

Stored graph admission may omit only kind1 IdentityFile/state5 DEFERRED_KEY_RESOLUTION.
It represents a safe absent static identity, not deletion provenance or connection approval.
CONFIG import/edit remains strict for missing dependencies. No relaxation for certificates,
dynamic/CWD paths, missing Include/trust/revocation or existing wrong types.
Strict `SshConnectionMaterial.references(...)` checks unresolved resources across the entire
actual Include traversal, then derives only the requested parent's direct references. Checking
only the edited root misses an absent key in a retained included child; regress Include-only
root import. `storedReferences(...)` remains the separate narrow stored-graph policy.

Derive all references from the actual native aggregate ResourceAudit under original guards
and cumulative budgets. A prospective reference-refresh inspection may skip equality with
old metadata solely to derive new references; it must not claim verified persisted metadata
or bypass final candidate validation. Do not fabricate superset references or parse CONFIG
in Java/Dart. Reimport refreshes retained CONFIG metadata in the same proposal, preserving
content and removed phrase/account state.

Require every effective native DirectPlan identity path to resolve to a current supported
managed identity. Library disables affected rows and exposes missing paths. Both connector
key preparation paths enforce this before phrase save/TCP/secret release, even if a different
listed identity still exists. Inactive declarations affect deletion review but need not block
unrelated effective plans. Existing authenticated/retired shell authority remains independent;
pending selections and future reconnects use the new vault epoch/current contents.
Library error rows need a mutable local map while optional error fields are added, then an
immutable published snapshot. `VaultCollections.map(...)` already returns an unmodifiable
map; mutating it makes missing-key library reads fail generically. The actual Android deletion
fixture must read the library after commit, not merely inspect the durable graph.

## 4. Error Matrix

Stale/foreign/replayed/cross-kind ticket -> reject without write; missing selected key ->
SSH_KEY_MISSING; missing effective path -> SSH_KEY_MISSING without fallback; durable write
with failed terminal delivery -> existing VAULT_COMMIT_UNCERTAIN and lock. Return fixed public
errors only; no private bytes, phrase or raw exception text crosses MethodChannel.

## 5. Good / Base / Bad Cases

Good: review exact path/fingerprint/impact, atomically remove selected identity, retained
config reports missing key, same-path reimport repairs references. Base: unrelated passwords,
TOTP/trust/keys and established shell behavior remain. Bad: silently choose B after A is
deleted, rewrite user config text, remove an Included generated file, accept Dart impact
metadata as authority, or resurrect old phrase/accounts on reimport.

## 6. Required Verification

Pure proposal/graph cleanup and preservation; actual native candidate/config admission and
reimport; direct/Include/inactive/multiple identities; generated manual with/without inbound
edges; strict missing-config import; original lifecycle, once effect and uncertain outcome.
Typed Flutter payloads/confirm/cancel/late/background/narrow/large text and actual renders.
JVM, Flutter, device isolation and formal install evidence must remain distinct.

## 7. Wrong vs Correct

Wrong: forgetting a phrase deletes a key, or omission from the library proves durable cleanup.
Correct: inspect the committed manifest and real native validation, while separately testing
the protected UI and exact installed artifact. Record only checks actually run.

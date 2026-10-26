# Explicit CONFIG resource references

## 1. Scope / Trigger

Use [the resource contract](../../../design/ssh-config-resources.md) for explicit IdentityFile,
CertificateFile, user/global known-host and RevokedHostKeys declaration metadata. RESOURCE_AUDIT
is an opt-in fourth mode, preserving existing EFFECTIVE/CATALOG/AUDIT bytes and behavior.
Static base/type/direct-reference evidence never proves suffix loading, key association,
materials, trust or full admission. No production provider/reviewer/restore/connector opening.

## 2. Signatures

```java
static native long reserveResourceAudit() throws IOException;
static native byte[] resourceAudit(long token) throws IOException;
SshConfigEvaluator.prepareDetachedResourceAudit(guard, binding);
evaluator.loadDetached(sourceCSDI, root, originalDeadline, null);
evaluator.run();
evaluator.resourceAudit(remainingBytes, remainingRows);
inspector.inspectResourceAudit(manifest, originalDeadline, cancellation);
inspector.close(); // retry while cleanup remains pending
```

Reuse the original 64-slot/staged protected input owner, load/run/usage/cancel/close/destroy.
Only resourceAudit getter accepts RESOURCE_AUDIT; old getters reject it and vice versa. New
JNI methods add two exports (config15/session19/material1); source CSDI v1 is unchanged.

## 3. Contracts

Use the same pinned keyword/argv lexer, before Host/Match/first-value selection, with shared
finite policy/Match syntax-only/Include traversal. Collect explicit physical declarations,
including inactive/shadowed/orphan; no scratch Options/defaults/Java lexer/fake Request.
Never open/copy nonCONFIG resource bodies or query host paths, user, cwd, environment or glob.

Kinds1..5 are identity/certificate/user-known/global-known/revoked. Identity/certificate need
one nonempty argv; Known lists1..32, Revoked1..256. Exclusive none disables kinds1/3/4/5;
Certificate none is a literal relative filename. Preserve distinct argument positions and
declarations; repeated traversal deduplicates locator(kind,parent,line,argument), not whole
row or target. Conflicting duplicate state/target rejects. Locator argument256 needs9bits.

States STATIC1/DEFERRED_CONTEXT2/DEFERRED_CWD3/DISABLED4/DEFERRED_KEY_RESOLUTION5 are closed.
NonGlobal paths containing percent or `${` conservatively defer; bare `$name` remains literal.
Global performs no such expansion, including literal `${...}`; dollar paths can bind, percent
cannot be represented by managed path policy. Reject bare~/other-user home and outside-virtual
absolute paths, never consult NSS. Relative paths stay CWD even when same-named metadata exists.
Only ~/ and /ctos-vault/ map to the established virtual home; no Include .ssh/config-parent
anchor. Strict nonpattern suffix validation must reject ISO C1 in addition to C0/DEL, before
missing-key deferral. No wildcard expansion, traversal cleanup or unlocked host fallback.

STATIC target types: identity PRIVATE/PUBLIC/CERT, certificate CERT, known-hosts KNOWN_HOSTS,
revoked PUBLIC_KEY-only representable text subset. Missing exact static identity/cert base
defers KEY_RESOLUTION (only those kinds, target0); wrong existing type rejects PATH. Missing
Known/Revoked rejects PATH. Never probe/authorize .pub/-cert or infer mathematical association.
KRL/multi-key revocation and final reader behavior remain pending. Defaults are not declarations.

CSCRv1: big-endian magic0x43534352/version1/flags1/auditLength/resourceCount, exact original
CSCAv1, then six-u32 rows(kind,parent,line,argument,state,target). Length64..107328, nested
audit44..82732, count0..1024, exact20+auditLength+24*count with checked arithmetic/no trailing.
parent/STATIC target1..128 and distinct, line1..16384, argument1..256 (kind1/2 exactly1,
kind3/4<=32). NonSTATIC target0; DISABLED only1/3/4/5 argument1; state5 only1/2; Global no
state2. Check remaining bytes/rows before allocations, reserve resource rows before nested
Audit then edge rows before Catalog. Nested copies and outer wire always wiped in finally.

Inspector reuses original CONFIG-only deep copies, all-root loop, original<=60s BOOTTIME and
Cancellation/owner/cleanup/final Java wipe+lifetime fence. Aggregate1MiB wholeCSCR counted once,
4096 aliases+Include-deferred+Include-edges+resources,1024opens/64MiBreads/16384lines and64MiB
copy amplification. Metadata lookup adds zero IO; check cancellation during bounded scans.

Validate actual parent CONFIG, static target type and target.path in that parent's direct
references. Generic root/sibling references, identity/name/suffix are insufficient. Missing
reference throws ResourceReferenceException with fixed message and five numbers only.
References can include other obligations; do not enforce exact equality with resource rows.
Root.resourceAudit contains inner Audit, resources and hasDeferred(states2/3/5); share inner
objects with Root.audit/catalog. Report staticResourceReferencesVerified only new mode true,
resourcesIncomplete separate from Include incomplete; always declarationsOnly=true/admissionComplete=false.
Zero CONFIG has no JNI and the same final fence. Failed root denies whole report; failed
cleanup retains exact owner for explicit retry, successful cleanup destroys normally.

## 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Bad targeted arity/empty/mixed none list | INPUT, no successful partial rows |
| Known>32, generic argv/row/work/output exhausted | LIMIT, no truncation or budget renewal |
| Invalid static path/type or missing Known/Revoked | PATH, no host lookup/body access |
| Valid static key base absent | Explicit state5 after path checks, no guessed suffix target |
| Relative/dynamic resource | Visible unresolved state, no invented cwd/context/permission |
| Bad CSCR/nested frame/locator/state/source/type/quota | Reject; wipe temporary outputs and preserve cleanup |
| Cancel/expiry/wrong owner/busy/cleanup failure | Original lifecycle diagnostics and retryable owner, no report |

## 5. Good / Base / Bad Cases

Good: CertificateFile ~/cert points to actual CERT metadata directly referenced by that
CONFIG; mathematical private linkage remains pending. Base: CertificateFile none is CWD,
IdentityFile none disables that entry. Bad: use another CONFIG's reference, interpret relative
key as ~/.ssh/key, treat Global dollar as expansion, or silently approve base.pub siblings.

## 6. Tests Required

Real parser/handle matrix: five-family argv/none/limits, allowed/wrong types and missing-base,
bare dollar/percent/${/Global literal, original ordinals/line/arg256/locator repeats, inactive/
orphan/all roots, nested exact old CSCA, old mode compatibility, no resource body read, owner/
cancel/busy/getter/cleanup and effects0/capabilities0. Rerun old audit/effective/catalog/handle/
direct/staged/host IO. JVM strict three-layer decoder/preallocation quotas/wipe/state/locator,
actual parent/type/direct-reference/no fabricated target, partial flags, cumulative quotas,
cancel/Error/retry/final fence. Full JVM/lint/normal+fixture/APK signature/isolation/export/
archive/source correlation. Platform compile is not Android execution.
[Actual local results](../../../design/verification.md#20261023-config-resources).

## 7. Wrong vs Correct

Wrong: generic none handling for all paths or generic percent/dollar expansion for Global.
Correct: exact directive semantics and explicit unresolved states. Wrong: STATIC cert/resource
metadata means ready for authentication/trust. Correct: typed literal-base reference only,
with independent material/mathematical/final-consumption obligations and admission always false.

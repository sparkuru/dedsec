# All-CONFIG static Include and local-effects audit

2026-10-23: [explicit resource auditing](ssh-config-resources.md) adds fourth-mode CSCR with typed literal-base/direct-parent references and explicit context/cwd/key-resolution deferrals. Existing modes below stay unchanged; no material/math/trust/admission proof. [Evidence](../../../design/verification.md#20261023-config-resources).

## 1. Scope / Trigger

Use [the frozen contract](../../../design/ssh-config-audit.md) when checking actual supplied
CONFIG static Include references and the finite local-effects/host-trust policy. AUDIT is a
third explicit mode, separate from EFFECTIVE and CATALOG. This is still a partial inspection,
not complete config values/typed key/trust/reference semantics or production admission.
Keep provider/reviewer registration, restore and connector gates closed.

## 2. Signatures

```java
static native long reserveAudit() throws IOException;
static native byte[] audit(long token) throws IOException;
static SshConfigEvaluator prepareDetachedAudit(DetachedGuard guard) throws IOException;
static SshConfigEvaluator prepareDetachedAudit(DetachedGuard guard, NativeBinding binding)
    throws IOException;
evaluator.loadDetached(sourceCSDI, root, originalDeadline, null);
evaluator.run();
evaluator.audit(remainingBytes, remainingRows);
inspector.inspectAudit(actualExclusiveManifestLoan, originalDeadline, cancellation);
inspector.close(); // retry if cleanup remains pending
```

Native audit reservation uses the same 64-slot registry and staged load/run/usage/cancel/
close/destroy lifecycle as [managed detached input](ssh-detached-config-managed.md). Existing
boolean reserveDetached accepts only0/1; never smuggle AUDIT through a JNI boolean. Audit
getter rejects other modes; summary/Options/catalog getters reject AUDIT. Native array output
needs a READY/owner/original-lifetime recheck after JNI allocation/write.

## 3. Contracts

Use the sole pinned readconf keyword/argv lexer. Audit checks each physical directive before
Host/Match filtering and first-value behavior, then reuses static managed Include recursion.
Never pass a fake Request/passwd/host/user to the normal matcher or parse all directives into
active scratch Options. Match syntax-only must consume all original argv, including the pinned all/empty-lookahead
case; keep old matcher/all rules. Its handling must precede context preparation and skip
every predicate, expansion, NSS/env/interface/DNS effect. Preserve pinned Match grammar,
including actual all combinations and separated-only sessiontype.

Finite policy is versioned by CSCA v1 and the linked contract: raw command families accept
only the exact raw none form, not quoted/escaped/commented lookalikes; provider/path families
use upstream argv none semantics. Reuse upstream multistate tables for flag/strict/CNAME
policy, with ControlPersist's original lowercase no/false exception. Strict YES/ASK includes
true. Match exec is COMMAND; localnetwork and active canonicalization/CNAME maps UNSUPPORTED.
Invalid pure multistate values/scalar arity reject INPUT. Complex enabled ForwardAgent paths,
all AddKeys forms except missing first value or a single explicit no/false, and non-disabled
ControlPersist may reject EXTERNAL without
claiming full value syntax; raw command multi-token rejects COMMAND.
Compile-disabled oUnsupported rejects even disabled-looking values. Unknown/ignored-unknown
reject; oIgnore/oDeprecated preserve pinned no-effect behavior. This is not every directive's
value validator; RemoteCommand/ProxyJump/TCP forwards are not new blanket prohibitions.

Record actual `(parentObject, sourceLine, childObject)` at typed child entry before stack
push, after path/type/depth/cycle checks. Root emits no edge. Deduplicate exact triples,
preserve first DFS/glob order, cap1024; repeated reads still charge actual IO/line budgets.
Static missing/type/path/cycle behavior and virtual `.ssh/` Include anchor stay unchanged.
Dynamic %/$ paths remain deferred; do not expand or invent edges/context.

CSCA v1 big-endian: five u32 `magic0x43534341, version1, flags1, catalogLength, edgeCount`,
then exact original CSCD bytes followed by edge triples (three u32 each). catalogLength
24..70424; edgeCount0..1024; max82732bytes. Checked exact length is20+catalogLength+12*edgeCount.
Validate header, byte quota and aliases+deferred+edges<=remainingRows before object/string
allocation; subtract edgeCount before nested Catalog decoding. Reuse original strict CSCD
codec, exact nested consumption; wipe outer wire and any temporary nested copy in finally.
Edges parent/child1..128, distinct, line1..16384, unique triples, no trailing bytes.

Shared inspector loops every original CONFIG, including orphan, with one original<=60s
BOOTTIME/cancellation/owner. Same CONFIG-only input copy/protected native storage, retryable
cleanup and final Java wipe+lifetime fence. Remaining1024opens/64MiBreads/16384lines and
64MiB copy-amplification preflight stay; retained CSCA<=1MiB and aliases+deferred+edges<=4096
for the whole job. Count embedded CSCD only once. Zero CONFIG performs no JNI, same fence.

For every returned edge, both endpoints must be actual CONFIG ordinals; require child.path
in that actual parent's direct references. Root reachability, another parent's references,
same identity/name are insufficient. Do not require equality with all references: key/trust
and dynamic/non-Include semantics remain pending. Missing direct reference throws fixed
ReferenceException with parent/line/child integers only; no path/raw body/secret diagnostic.
Native policy failures preserve EvaluationException/Diagnostic. Failed root denies whole
report; completed cleanup destroys normally, failed cleanup keeps exact owner for retry.

Root.catalog remains available from audit's embedded frame; Root.audit is audit metadata.
Report staticIncludeReferencesVerified/localEffectsPolicyChecked are true only for successful
explicit inspectAudit (empty CONFIG set is vacuous), false for original inspect. Always
declarationsOnly=true/admissionComplete=false. Deferred sites remain visibly unresolved.

## 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Inactive/shadowed forbidden directive | Same finite policy rejection with actual source location |
| Match keyword as criterion value vs execution attribute | Preserve safe value; reject execution attribute after grammar/arity check |
| Quoted/commented raw command none vs provider quoted none | Respect distinct upstream raw/argv meanings |
| Unknown/unsupported, malformed audited scalar/Match | Reject; no IgnoreUnknown or disabled-spelling fallback |
| Unbound actual Include edge | ReferenceException with bounded original numeric provenance |
| Invalid nested CSCD, edge source/type/self/duplicate/length, exhausted quotas | Reject before excess decoded retention; no prefix report |
| Cancel/expiry/wrong owner/parser busy/getter after close | No result; original lifetime and cleanup rules |
| Close/destroy throws | Wipe Java copies and retain exact cleanup owner until retry succeeds |

## 5. Good / Base / Bad Cases

Good: actual root at ordinal2 includes CONFIG3 at line4, and that exact parent's references
contains child.path; another non-Include key reference is allowed to remain unclassified.
Base: no Include edges, or dynamic-only sites, preserves explicit partial coverage. Bad:
root references leaf while intermediate parent does not, or synthesize aliases to sample
branches and claim all-branch policy coverage.

## 6. Tests Required

Real ARM shared audit reserve/load/run/getter/usage tests: safe same-source nested CSCD equals
old CATALOG, mode isolation, all policy families and disabled spellings, raw-none distinction,
Match grammar/values/attribute position, inactive and first-value-shadowed forbidden branches,
actual edge ordinals/lines/DFS/glob/dedup, static/dynamic mixed Include, bounds, cancellation/
expiry/busy/fault/cleanup/effects0/capabilities0. Rerun old effective/catalog/handle/direct/
staged/IO matrices. Keep invalid nonpolicy value acceptance explicitly partial-only.

JVM strict CSCA malformed/quota/immutable/wipe tests and actual manifest parent-specific
reference tests, including nonCONFIG/malicious source, all/orphan/zero CONFIG, old inspect
flags/mode compatibility, original cumulative budgets, cancellation/Error/cleanup retry/final
publication fence. Main owns serialized builds/runtime, full JVM/lint/normal+fixture compile,
APK signatures/isolation/JNI13/session19/material1/archive83 and source/artifact correlation.
Platform fixture compilation does not establish Android/JNI execution.
[Actual local results and limits](../../../design/verification.md#20261023-config-audit).

## 7. Wrong vs Correct

Wrong: search argv for the word exec, or treat argv[0]==none as a disabled raw ProxyCommand.
Correct: consume Match grammar in attribute positions without predicates; match raw commands
against the exact original stored remainder. Wrong: a successful native audit means references
were authenticated. Correct: CSDI carries no references; Java must compare each observed edge
to the supplied parent's direct references, and still preserve admissionComplete=false.

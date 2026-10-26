# Research: native context-free CONFIG audit and actual static Include edges

- Query: define an independent native audit mode that discovers actual typed static Include edges and checks forbidden local effects in every supplied CONFIG branch, while preserving catalog/effective behavior and existing detached ownership/budgets.
- Scope: internal, read-only pinned readconf/patch/hooks/evaluator/IO/managed contract investigation. Research output only; no builds, target execution, network/device/account action or git operation.
- Date: 2026-10-23
- Active task: `.trellis/tasks/10-08-ssh-terminal-client`.
- Context: supplied task path; no research.jsonl was found. Research role did not read implement.jsonl/check.jsonl. Read current task artifacts, managed contract/spec and relevant native code. Current managed increment is locally completed according to task artifacts; this research does not repeat its validation.

## Findings

### Recommendation and scope

Add a distinct **AUDIT** mode alongside EFFECTIVE and CATALOG. Audit reuses the exact keyword/argv lexer, existing declaration collection and managed static Include/glob recursion, then additionally checks an explicit finite table of prohibited local effects and records actual static CONFIG edges. No Request, passwd, target/user synthesis, Host matching, Match predicate evaluation, Options application/defaulting or socket/credential authority exists in audit mode.

Keep existing CSEQ/CSES/CSCD wire and effective/catalog results unchanged. Audit gets a distinct reserve/getter path and CSCA wire embedding an exact CSCD frame plus edge triples. Existing `inspect` remains declaration-only; a new explicit `inspectAudit` can reuse its input/owner/budget/cleanup logic. Full CONFIG value semantics, dynamic Include resolution, identity/credential/trust semantics and production admission remain pending. A policy result is only evidence for the frozen finite policy revision, never a whole-manifest admission certificate.

### File map and concrete seams

| File | Evidence / purpose |
| --- | --- |
| `build/ssh-native-g1/stock-run.lOHF9Oai/sources/openssh-10.6p1/readconf.c:1183-1212` | Original strdelim/keyword lowercase/parse_token/argv_split entry before opcode switch. |
| Same `readconf.c:700-922` | Match grammar and predicates currently mixed; context preparation happens before attribute loop. |
| Same `readconf.c:989-1126` | Shared multistate value tables/parser; reuse these for audit policy values. |
| Same `readconf.c:1535-1550`, `:2056-2066` | Local command opcodes store **raw remainder**, not reassembled argv. |
| Same `readconf.c:2072-2158` | Shared Include argv, user-directory anchoring, glob, recursive read and parent activity restore. |
| `.trellis/tasks/10-08-ssh-terminal-client/research/openssh-10.6p1-config-evaluator.patch:385-392` | Existing catalog-only early branch after lexer; skips all non-Include semantic switch cases. |
| Same patch around `@@ -2635` | Each read enters `ctos_config_enter_file` before fopen and always leaves; syntax/IO failures return with cleanup. |
| `android/app/src/main/cpp/ssh_config_eval.c:180-224` | Catalog metadata/deferred-site collection; audit can reuse under explicit mode routing. |
| `ssh_config_eval.c:247-279` | Typed object lookup, parent object/line stack, recursion/cycle/depth checks and unique scanned-file count. |
| `ssh_config_eval.c:329-362`, `:413-444` | Managed glob and Include expansion; context-free mode must use existing static-copy/defer path. |
| `ssh_config_eval.c:462-483` | Current effective Options policy fields and error categories; use as the finite audit policy baseline, not as a stateful audit implementation. |
| `ssh_config_eval.c:625-705`, `:804-831` | Staged protected CSDI loading, original per-root remaining limits, usage and guarded result getters. |
| `android/app/src/main/cpp/ssh_file_io.[ch]` | Managed-only source lookup/readers, precise budgets and lifetime checks; no new IO implementation needed. |
| `android/app/src/main/cpp/ssh_config_handle.c:172-219`, `:349-389` | Issued owner before load, cancellation inheritance, retained failed owner and retryable cleanup. |
| `android/app/src/main/cpp/ssh_config_eval_hooks.h` | New audit predicate/policy reporting hooks must remain parser-TU scoped. |
| `design/ssh-detached-config-managed.md`, `.trellis/spec/backend/ssh-detached-config-managed.md` | Existing all-root source order, aggregate budgets, one original deadline, protected single copy and owner/cancel contracts. |

Pinned source paths/line numbers above refer to original local OpenSSH10.6p1; patch hunks change generated parser line numbers. Never modify the pinned source tree in place.

### Minimal native mode/API proposal

Represent mode explicitly internally; do not rely on nonzero `catalog_mode` as mode identity. Existing bool reserve/load APIs accept only0/1 and must keep that contract. Add a separate audit reserve entry, rather than passing2 through a boolean JNI argument or quietly converting existing catalog to audit.

```c
/* Names illustrative; freeze in main contract. */
ctos_ssh_config_handle_reserve_audit(&token);
ctos_ssh_config_eval_prepare_audit_detached(deadline, &evaluation);
ctos_ssh_config_eval_audit(evaluation, &const_audit);
ctos_ssh_config_handle_audit(token, output82732, &length);
ctos_ssh_config_handle_audit_live(token);
```

Audit token reuses current staged CSDI load with null Request, run, usage, atomic cancel, close and destroy. Existing input wire/maxima do not need references or a new format: native reports actual edges, Java compares them against the actual authenticated manifest. Minimal new JNI is reserveAudit and audit; existing reserveDetached/loadDetached/usage and old eight config signatures stay compatible. Direct C audit-open convenience is optional; staged handle tests can exercise the production path without inventing another acquisition lifecycle.

`ctos_config_catalog_mode()` should remain true only for actual CATALOG if its name retains current meaning. Add an explicit audit predicate/context-free scan predicate. Where static Include copy/defer/declaration collection is intentionally common, dispatch to the correct owned result object. Catalog/summary/Options getters must reject AUDIT; audit getter rejects other modes. No cached result skips native owner/READY/original deadline/cancel checks. Keep parser trylock/current serialization and current registry pin/destroy rules.

### Same lexer and finite policy checks

Insert audit dispatch at the existing post-argv point, before Host matching can consume argv and before any first-value assignment. Handle every physical directive independently, including inactive/shadowed/later overrides and Include descendants. Do not use one accumulated Options object: first-value semantics could hide a later forbidden declaration. Do not force fake active context and parse all normal directives into scratch Options; that would execute context-sensitive paths, turn RefuseConnection into a spurious audit refusal, and blur the promised finite policy scope.

Prefer an audit policy helper **inside the patched parser TU**, where OpCodes and static multistate tables already exist. Inputs are opcode, argv count/vector, and raw remainder needed by command cases. This is a bounded policy decoder on top of the sole upstream SSH lexer, not a second config parser. Unknown/oIgnoredUnknownOption must reject; audit must not apply IgnoreUnknown to conceal unknown directives. Explicitly reject oUnsupported, because compile-time-disabled keywords such as GSS can map there instead of to their named opcode. Obsolete oIgnore directives need an explicit documented treatment; they do not execute in the pinned parser.

Baseline finite policy table, derived from `ctos_config_options_check`:

| Directive family | Allowed disabled / required form | Rejection |
| --- | --- | --- |
| ProxyCommand, LocalCommand, KnownHostsCommand | Only exact raw remainder `none` after the same leading WHITESPACE/`=` skip; case-insensitive as current clear_or_none | COMMAND |
| StrictHostKeyChecking | Parsed strict-hostkey table value YES or ASK: yes/true/ask | TRUST for no/false/off/accept-new |
| VerifyHostKeyDNS | Parsed yes/no/ask table value0 only: no/false | TRUST for yes/true/ask |
| NoHostAuthenticationForLocalhost | Flag table value0: no/false | TRUST |
| ForwardAgent | Flag value0 only; nonflag socket/env path is enabling, not `none` disabling | EXTERNAL |
| IdentityAgent, PKCS11Provider, SecurityKeyProvider, ControlPath | Single argv string `none`, case-insensitive; no expansion/provider lookup | EXTERNAL otherwise |
| ForwardX11, ForwardX11Trusted, HostbasedAuthentication, EnableSSHKeysign, supported GSS auth/delegation, ForkAfterAuthentication, EnableEscapeCommandline, PermitLocalCommand, ProxyUseFdpass | Shared flag table value0 | EXTERNAL |
| ControlMaster | Shared controlmaster table value0 (no/false) | EXTERNAL for yes/true/auto/ask/autoask |
| Tunnel | Shared tunnel table NO (no/false) | EXTERNAL for yes/true/point-to-point/ethernet |
| AddKeysToAgent | Original yes/no/ask/confirm grammar; only value0 and no second arg | EXTERNAL for enabled values/time forms |
| ControlPersist | Original dedicated grammar; lowercase no/false only | EXTERNAL for yes/true/time, including `0` |
| CanonicalizeHostname | Shared canonicalize table valueNO (no/false) | UNSUPPORTED for yes/true/always |
| CanonicalizePermittedCNAMEs | Preserve disabled `none` form; nonempty CNAME policy would require unsupported canonicalization | UNSUPPORTED otherwise |
| Match exec / !exec / exec= / !exec= | Never allowed, even behind prior selectors | COMMAND |
| Match localnetwork (incl negation/equals grammar) | Never probe interfaces | UNSUPPORTED |

For audited scalar flags, validate arity and table syntax, then reject enabled values. Do not silently accept arbitrary spellings as disabled. Command raw handling is critical: argv_split strips quotes/escapes/comments but `parse_command` stores raw remainder. `ProxyCommand "none"` and `ProxyCommand none #comment` produce argv seeming disabled while effective `clear_or_none(raw)` rejects them. Audit should reject them too. String/provider options use argv semantics, so quoted `"none"` may legitimately disable those; do not apply raw command rules indiscriminately.

Most tables use strcasecmp (`readconf.c:1111-1125`); current `clear_or_none` is case-insensitive. ControlPersist instead uses strcmp (`:2011-2016`), so uppercase NO/FALSE is invalid under fixed syntax, not silently enabled/disabled. `ControlPersist 0` and `AddKeysToAgent 0` are enabled time forms, not false. `ForwardAgent none` is a path, not a disabled flag. Additional scalar args should be INPUT; valid but forbidden enabled values use policy category. For complex enabled forms it is enough to fail policy without claiming full value validity; malformed-input diagnostics still cannot become a successful audit.

Allow recognized nonpolicy directives lexically without claiming their argument semantics. In particular RemoteCommand is remote behavior, ProxyJump remains managed jump metadata, ordinary TCP forwarding stays allowed by this finite audit, and UserKnownHostsFile/GlobalKnownHostsFile/RevokedHostKeys/UpdateHostKeys are not automatically new prohibitions. Their path/trust semantics remain pending for Java/full provider/actual plan. A local-effects audit is not proof that invalid Port, algorithms, forwarding values or RefuseConnection branches are globally valid.

### Match syntax without predicate evaluation

Do not call current `match_cfg_line` unchanged with NULL context: it prepares ruser/host before its attribute loop (`readconf.c:709-725`), then uses hostname/user/pw/remote command and predicates. A substring scan for `exec` is also wrong: `Match command exec` and `Match user exec` are harmless selector values, not local commands.

Recommended reuse is a same-TU shared next-criterion/arity helper extracted from the original matcher; effective matching consumes its parsed criterion while audit consumes only the structural result. An alternative explicit syntax-only branch must run **before** context preparation and demonstrably skip every predicate/expansion. Do not change effective Match acceptance while extracting grammar.

Preserve fixed details:

- One optional leading `!`, case-insensitive criterion names.
- all/final/canonical have no criteria argument. Preserve actual fixed all-combination behavior rather than rewriting according to a comment or guessed OpenSSH rules.
- Inline `name=value` recognition is the original explicit prefix list: host/originalhost/user/localuser/localnetwork/version/tagged/command/exec. Fixed code does not include sessiontype in that inline list; separated sessiontype remains supported. Do not accidentally broaden sessiontype= syntax without a deliberate compatibility decision.
- Other criteria consume exactly the next token. Empty criteria allowed only for tagged and command. Missing/unknown attributes reject INPUT. Comment handling stays upstream argv behavior.
- Audit recognizes forbidden exec only in **attribute position**, including negated/inline forms; no expansion or invocation. localnetwork rejects without CIDR/interface probing. Safe criteria receive no truth value, do not update activep, and do not trigger a final evaluation pass.

### Actual static Include edge collection

Use `ctos_config_enter_file` after typed object resolution and recursion/depth checks but before pushing child (`ssh_config_eval.c:260-273`). At that moment stack>0 gives the actual parent `objects[stack-1]+1`, its current Include line `lines[stack-1]`, and resolved typed child object+1. Root has stack0 and contributes no edge. Child resolution already uses exact managed path/type, so this records real files chosen by shared glob, not Java guesses or declared-reference enumeration.

Deduplicate exact `(parent,line,child)` triples while preserving first depth-first/glob encounter order. Duplicate Include tokens or repeated entry from another parent still consume real parser/open/line budget; do not skip parsing visited files to save work or mask cycles. Different source lines remain distinct edges. Nested child's line attribution must use its own stack frame; after child returns the parent's Include line remains intact for subsequent glob matches.

No successful result is returned on missing/wrong type/path escape/depth/cycle/IO/limit failure, so an edge tentatively collected before a later failure is not partial evidence to publish. Self-edge is a cycle and fails. Policy/path/type diagnostics use the current parent Include source when rejection happens before child push. Input ordinals stay original1-based including nonCONFIG records.

Preserve Include behavior exactly:

- Same argv_split quoting/escaped spaces/comments and multi-token order; no Java tokenization.
- Relative `Include child` anchors under virtual `~/.ssh/child`, **not selected root's parent**. `~/child` uses virtual home, absolute paths only within `/ctos-vault`; ~other-user and traversal never consult NSS/host FS.
- Same managed compat glob/lexical sort via ALTDIRFUNC; no GLOB_TILDE environment lookup. `Include none` is a literal path, not a disable keyword.
- Static GLOB_NOMATCH remains PATH/ENOENT failure in this project's patched semantics. A pattern matching any wrong-type/nonCONFIG item fails, rather than silently filtering to CONFIG or ignoring directories.
- Same current-stack cycle test/depth8/line8192/file128/byte/read limits; visited is metadata only.
- Any `%` or `$` in Include remains located deferred before expansion, including `%%` and `$` forms that might later be literals. Record one deferred site per original object/line, process other static tokens on the same line, and do not invent a target/user/environment. Dynamic path syntax/reference/cycle coverage remains unresolved.

Native CSCA proves only actual static edge observations. Java can require every edge child.path be directly in **that actual parent CONFIG's authenticated references**. It must not require all references equal the Include edge set: refs can name keys/trust/other resources. A generic root reference or sibling's reference cannot authorize a parent's edge. CSDI deliberately does not carry references, so native cannot claim this authenticated comparison itself.

### CSCA v1 candidate and guards

The main session's nested-frame proposal is minimal and preserves the existing codec:

```text
u32 magic=0x43534341, version=1
u32 flags=1                     // localEffectsPolicyChecked only
u32 catalogLength
u32 edgeCount
byte[catalogLength] exact-CSCD-v1-frame
repeat edgeCount: u32 parentObject, u32 sourceLine, u32 childObject
```

Per-eval edgeCount<=1024, catalogLength24..70424, total<=`20+70424+1024*12 = 82732` bytes. Unique actual edges in a successful run are also bounded by actual opens minus the root, but the fixed capacity can remain1024. Nested CSCD keeps original aliases, first sources, hasMatch, omittedSelectors and deferred sites. Audit may collect same declaration metadata but ordinary catalog getter must reject audit mode.

Require exact outer length/flags/version/no trailing bytes; nested frame consumes **exactly** catalogLength and itself has no trailing bytes. Validate edge parent/child1..original files count, both CONFIG, distinct, line1..16384 and unique triple. Existing CSCD sites likewise must map original CONFIG types. Do not return paths/raw commands/predicate text, references, secrets, descriptors or pointers.

Charge whole CSCA bytes against the existing1MiB retained-report cap. Charge aliases+deferred+edges against aggregate4096 rows. Check outer edge count against remaining rows **before** nested catalog list/string decoding, and parse nested with `remainingRows-edgeCount`; otherwise a malicious outer frame can cause excess allocations before final rejection. Original catalog-only maxBytes/maxRows behavior stays unchanged. No bit means admissionComplete; Java report remains permanently false even when static refs/policy fields are true.

### Lifetime, budgets, failures and cleanup

Reuse one original <=60s BOOTTIME deadline across Java copy, all audit roots, getter/usage, cleanup and publication. Pass remaining1024opens/64MiBreads/16384lines in the same CSDI header; do not reset at mode switch, per root or when reading an embedded catalog. Keep CONFIGcount*CONFIGbytes<=64MiB preflight and all source-order/zeroCONFIG behavior. Audit source is still one protected native mapping adopted after issued token ownership; no temporary live view or second full copy.

Reuse existing numeric reasons and run int[5]: COMMAND/TRUST/EXTERNAL/UNSUPPORTED for policy categories, INPUT for audited grammar, PATH for type/missing/escape/cycle, LIMIT for capacities/work, IO/LIFETIME as before. Do not extend reason enum/wire just to report a keyword. Preserve stable original object/line and fixed messages without raw input. First error is sticky; failed scan produces no CSCA/cached prefix.

Audit report/getter uses READY/owner/PID/UID/deadline/cancel/mode checks. Registry getter and JNI recheck after allocation/write, wipe temporary full CSCA wire. Cancel remains only atomic/short-lock, inherits through reserve/load/attach, and cannot race destruction of its target. Failed load/open/reader cleanup keeps exact issued owner; close/destroy retry and Java inspector pending-owner behavior remain intact. Audit-owned metadata must be wiped/freed along with catalog/Options at close and cannot outlive eval. No global logger/parser/env mutation or fatal/longjmp addition.

### Meaningful native regression matrix

1. **Mode compatibility:** catalog still accepts documented invalid Port/Match/local-command declarations as lexical metadata; effective still returns old precise results. Audit rejects prohibited declarations but accepts safe policy forms without pretending ordinary values were checked. Wrong-mode getters reject. Old bool reserve accepts only0/1. CSCD bytes for safe same source match audit's nested CSCD exactly.
2. **Branch policy:** dangerous directive before/after safe disable, safe first-value shadow followed by dangerous value, inactive Host/Match and nested includes, duplicate blocks, aliases/uppercase keywords. PermitLocalCommand no does not excuse actual LocalCommand; ProxyJump/RemoteCommand/ordinary forwards are not mislabeled local execution.
3. **Raw command grammar:** exact none/NoNe vs quoted none, escapes, trailing comment, whitespace/equals, command with multiple tokens; provider quoted none vs flags `none`; scalar extra args; ControlPersist0/NO and AddKeysToAgent0/confirm-time behavior.
4. **Match grammar:** exec/!exec/exec=/!exec=, earlier safe/false selector, criterion values containing exec, command/tagged empty, sessiontype separated/inline parity, final/canonical/all combinations, malformed/unknown/duplicate criteria and quotes; observers prove no match predicate, NSS/env/expansion/interface/exec invocation.
5. **Actual edges:** original ordinals with leading nonCONFIG, nondefault/orphan roots, parent-specific line, nested/glob order, same child repeated on one vs different lines, repeated traversal across roots, root contributes no edge, escaped-space path. Missing/wrong type/path/prefix collision/cycle/depth failures publish nothing.
6. **Deferred paths:** dynamic-only and mixed static/dynamic tokens, %/$/%%, repeated deferred source dedup, unknown dynamic context; static tokens still produce edges, no expansion/fake context, dynamic incompleteness stays visible.
7. **Budget/codec:** exact edge/deferred/catalog/whole-frame maxima; overflow/truncation/trailing/flags/duplicate edge/wrong source types; aggregate rows include edges before nested allocations. Every repeated read still charged, remaining budget failure across all roots, exact expiry after final getter/cleanup.
8. **Owner/fault/effects:** paused read cancellation, reserve/load attachment cancellation, parser busy/reentry/wrong owner/PID/UID; retained CLOSING owners, injected allocation/read/unmap/release failure/retry; metadata wipe/FD baseline; forbidden host FS/env/NSS/DNS/socket/exec effects0. Existing allowed managed memfd/DONTDUMP operations remain available.

Rerun current actual evaluator/catalog/handle/direct-detached/managed-detached and affected host IO tests. Main owns runner patch hash/input list changes, strict ARM builds/QEMU, JVM/lint/normal/fixture/export/archive audits. Production still has one pinned parser and83-member archive; test observers never enter release. New JNI/CSCA fixture compile does not prove Android execution. No test/build was run by this research agent.

### Related specs, references and versions

- `.trellis/spec/backend/ssh-detached-config-managed.md`: current input/owner/budget/cancel/cleanup contracts.
- `.trellis/spec/backend/ssh-detached-config.md`, `ssh-native.md`: native source and catalog/effective separation, fixed parser/no-effects, real test gates.
- `design/ssh-config-{catalog,evaluator,managed}.md`, `ssh-detached-config-managed.md`, `ssh-validation-lifecycle.md`: explicit partial obligations and closed production gates.
- Local OpenSSH10.6p1 readconf/misc and current production parser patch only; no external lookup. Existing NDK27.0.12077973/API28 remains the native build baseline; no fresh version verification.

### Frozen-draft cross-check

Read the newly written `design/ssh-config-audit.md` after completing this investigation. Its explicit audit reserve, staged CSDI reuse, raw-command rules, shared Match grammar, actual parent-line-child edges, embedded CSCD/CSCA82732 maximum, StrictHostKeyChecking YES/ASK including true, predecode edge-row subtraction, Java direct-reference comparison, original inspect compatibility, zeroCONFIG flags and unchanged owner/budget protocols agree with the recommendation. No blocking contract correction was found.

One scope choice remains explicit: the research table above includes CanonicalizePermittedCNAMEs because current effective options_check tests it, while the draft finite list names CanonicalizeHostname only. Main may leave CanonicalizePermittedCNAMEs semantics pending or add its disabled/unsupported rule; implementers must follow the frozen contract and must not silently expand the finite policy. Do not investigate or implement unrelated directive semantics under this increment.

## Caveats / Not Found

- AUDIT APIs/CSCA/policy parsing are proposals, not implemented source. Main must freeze precise policy and report meanings before dispatch.
- The finite policy is intentionally not every directive's value validator. A successful audit cannot certify all context-dependent configuration semantics or all references; dynamic obligations remain explicit.
- Disabled spelling/command raw behavior has subtle upstream differences. Use shared tables/raw handling and exact tests rather than a generic boolean/none helper or broadening effective syntax.
- Only this research file was written. No implementation/spec/design/script/task-state or existing research was changed.

Main contract decision after independent review: design/ssh-config-audit.md now freezes CanonicalizePermittedCNAMEs as solitary none/otherwise UNSUPPORTED; no pending scope decision remains. Compile-disabled oUnsupported takes precedence even for disabled-looking values, while oIgnore/oDeprecated preserve pinned no-effect behavior. Strict YES/ASK includes true, ControlPersist retains lowercase no/false, and exact outer length plus predecode row reservation are explicit.

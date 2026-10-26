# Research: explicit native CONFIG resource declaration audit

- Query: inspect exact pinned IdentityFile/CertificateFile/UserKnownHostsFile/GlobalKnownHostsFile/RevokedHostKeys declaration and consumption semantics; recommend an explicit resource-audit input/result mode without altering EFFECTIVE/CATALOG/AUDIT or interpreting SSH in Java.
- Scope: internal, read-only local source/design/spec/task investigation. No network, target execution, build, device/account action or git operation.
- Date: 2026-10-23
- Active task: `.trellis/tasks/10-08-ssh-terminal-client`.
- Context: supplied active path fallback. No research/current/context JSONL was found; research role did not read implement.jsonl/check.jsonl. Read task top sections and newest `ssh-config-audit` plus managed detached contract/spec. Previous source15/protected307/local results are main/task provenance, not rerun evidence.

## Findings

### Main recommendation

Add explicit **RESOURCE_AUDIT** opt-in, reusing the existing protected CSDI source, reservation/load/run/usage/cancel/close lifecycle and full current audit behavior. Collect the five resource directive families from the same post-keyword/argv readconf branch, before branch/first-value filtering. Each declaration token becomes an immutable numeric row with actual CONFIG parent/line/kind/argument ordinal and either a metadata-only static target, an exact disabled sentinel, or an explicit unresolved obligation. Do not open any resource body, infer private/public/certificate associations, expand host/user/environment context, select cwd, run glob or create fake Request/home/capabilities.

Keep old CSEQ/CSES/CSCD/CSCAv1 bytes and mode semantics untouched. A distinct CSCRv1 can embed exact complete CSCAv1 and resource rows. Java checks static target type and its actual CONFIG parent's direct references against the supplied authenticated manifest, but does not parse token strings. Report remains admissionComplete=false; static resource-reference proof is not complete path consumption, key resolution or trust semantics.

### Source map

All pinned upstream paths below use `UP=build/ssh-native-g1/stock-run.lOHF9Oai/sources/openssh-10.6p1`.

| File / lines | Fact |
| --- | --- |
| `UP/readconf.c:1183-1212` | One shared keyword/argv lexer; normal switch receives quote/escape/comment-decoded tokens. |
| `UP/readconf.c:1404-1442` | IdentityFile and CertificateFile consume one nonempty argv token; global trailing-argument check rejects extra tokens. |
| `UP/readconf.c:1462-1501` | User/GlobalKnownHostsFile consume a token vector; exclusive case-insensitive none; first active list wins. |
| `UP/readconf.c:2334-2365` | RevokedHostKeys consumes a token vector, exclusive none, first active list wins; production patch converts missing-list fatal into normal failure. |
| `UP/readconf.c:453-505` | Identity/cert additions deduplicate exact unexpanded string plus userprovided flag. They do not canonicalize paths. |
| `UP/readconf.c:2930-2965` | Implicit default identity/system/user-known-host paths are inserted only later; audit does not execute defaults. |
| `UP/readconf.c:3079-3097` | RevokedHostKeys exclusive none is cleared by fill_default_options. |
| `UP/ssh.c:1485-1491` | RevokedHostKeys gets tilde then percent/dollar expansion with real connection info. |
| `UP/ssh.c:1516-1545` | Global/User known-host exclusive none cleared; only UserKnownHostsFile gets percent/dollar expansion here. |
| `UP/ssh.c:1791-1794` | Both system/user known-host paths get tilde expansion. GlobalKnownHostsFile does not receive default_client_percent_dollar_expand in this pinned path. |
| `UP/ssh.c:2403-2451` | IdentityFile none skipped case-insensitively, tilde/percent/dollar expansion, public loader and implicit certificate probe if no explicit certificate list. |
| `UP/ssh.c:2454-2481` | CertificateFile paths expanded/loaded and required to parse as certificate; **no none sentinel skip here**. |
| `UP/authfile.c:259-290` | sshkey_load_public tries exact filename, then `.pub`, then public envelope extraction from the original private-key file. |
| `UP/authfile.c:438-460`, `UP/sshconnect.c:1545-1563` | Revocation input is a KRL or flat public-key list; error checking it rejects host authentication. |
| `UP/sshconnect.h:55-71` | Actual expansion tokens depend on local/remote host/user/home/UID/port/jump/keyalias/hash. |
| `android/app/src/main/cpp/ssh_config_eval.c:182-228`, `:264-290` | Current scan/audit mode predicates, catalog/deferred metadata and actual Include edge collection. |
| `.trellis/tasks/10-08-ssh-terminal-client/research/openssh-10.6p1-config-evaluator.patch` | Current finite audit policy and Match syntax-only path; resource declarations currently fall through as lexical-only. |
| `android/app/src/main/cpp/ssh_config_eval.[ch]`, `ssh_config_handle.[ch]`, `ssh_config_jni.c` | New explicit mode/getter can reuse staged owner and protected input without changing IO. |
| `android/app/src/main/java/im/majo/ctos/SshManifestConfigInspector.java:123-140`, `:314-326` | CONFIG-only input loan and current direct Include-reference comparison; resource checks should extend the same owner loop. |

### Exact original declaration facts

| Family | argv / repetition / none |
| --- | --- |
| IdentityFile | Exactly one nonempty token per directive. Repeated directives are additive in effective Options; exact text duplicates with same userprovided flag are dropped by add_identity_file. case-insensitive `none` is an identity entry skipped later by ssh.c and suppresses default identity insertion when it is the only configured entry; it does not erase other IdentityFile declarations. |
| CertificateFile | Exactly one nonempty token per directive. Additive with exact text+userprovided duplicate suppression. The pinned consumption path does not special-case `none`: it remains a literal filename passed to sshkey_load_public, even though presence of an explicit certificate list suppresses implicit identity certificate probing. Do not label it DISABLED without an explicit new project rule. |
| UserKnownHostsFile | One or more nonempty tokens, exclusive case-insensitive none. First active list wins; later lines are still syntax-checked but do not replace populated effective list. Duplicate paths within a list are not deduplicated by this parser. |
| GlobalKnownHostsFile | Same list/none/first-value behavior as UserKnownHostsFile. Expansion differs at consumption, below. |
| RevokedHostKeys | One or more nonempty tokens, exclusive case-insensitive none; first active list wins. none is later cleared by readconf's CLEAR_ON_NONE_ARRAY, so absence of an sshconnect none branch does not mean it is unsupported. No filename-based known_hosts format assumption is justified. |

Current original hostfile list cap is SSH_MAX_HOSTS_FILES=32 (`UP/readconf.h:21`); identity/cert Options arrays each cap100 (`UP/ssh.h:22,28`). Project's generic lexer/token cap is256. Resource audit should freeze whether per-declaration host lists enforce32 while RevokedHostKeys remains generic256; do not claim first-value aggregate/active counts were checked in a context-free declaration pass. Identity/certificate extra arguments are malformed even if same target; quoted spaces/comments/basic escapes use exact shared argv_split behavior. An empty quoted token is invalid for all five families. Bare missing arguments already fail shared readconf entry; a comment-only argv after a keyword must not bypass targeted arity checks.

Audit records all physical declarations/arguments independently of effective duplicates/first-value/Host/Match. This is intentional all-branch metadata, not a reconstruction of Options. It must preserve duplicate tokens with **different argument ordinal**, repeated directives on different lines, and declaration provenance even if the eventual effective parser would discard them.

### Consumption/path facts and unresolved boundaries

1. These resource consumers do **not glob**. There is no corresponding glob call in inspected ssh.c/authfile.c resource paths. A `*`, `?` or bracket-looking token is a literal filename, not an Include-like pattern to expand/filter. Current managed manifest paths forbid those characters, so such a static virtual path is unrepresentable/rejected rather than silently globbed.
2. Relative resource paths are not anchored under `~/.ssh`, selected CONFIG parent or virtual home by readconf. Stock consumers carry them to ordinary filesystem operations, whose relative resolution depends on process cwd. No relevant chdir/cwd contract was found here. Offline resource audit must return NEEDS_CWD for a relative token even if a same-spelled manifest path exists; do not guess a cwd.
3. Identity/Certificate/UserKnownHosts/RevokedHostKeys use tilde then percent/dollar expansion in ssh.c. Conservatively mark any `%`/`$` token for these families NEEDS_EXPANSION, including escaped `%%`, without trying shared fatal expansion helpers or obtaining environment/user/host values. This represents unresolved actual Request obligations, not malformed syntax or successful target resolution.
4. GlobalKnownHostsFile only has tilde expansion in the pinned path. Its `%`/`$` must not be falsely described as supported stock interpolation. **Main has adopted directive-specific literal handling:** a `$` is a valid managed path character and can statically bind (`~/trust/$literal` is STATIC); `%` is forbidden by managed path policy and rejects. Do not defer global `$`, label it PATH, or invoke percent/dollar helpers for global paths. Other four families retain conservative expansion deferral.
5. `~/name` may normalize to existing project virtual home `/ctos-vault/name` using the **already established virtual path namespace**, then lookup exact metadata. This does not infer a host user's real home. `/ctos-vault/name` is explicit virtual-absolute. Strip only those canonical prefixes and validate suffix with managed path rules; do not call tilde_expand_filename/getpw/getenv/getcwd, use `pattern=1`, normalize `..`, or reuse Include's `.ssh/` anchor.
6. Bare `~`, `~other/...` or similarly unbound home forms require explicit NEEDS_HOME/unsupported state or rejection; freeze that choice. No host/NSS lookup or made-up user/home is permitted. Paths outside `/ctos-vault/` are unsupported managed paths, not host lookup candidates. Dynamic/home/cwd classification should precede metadata resolution, and all text still obeys line/UTF8/control bounds.
7. Defaults are not declarations. Do not emit synthetic resources for OpenSSH's default identities/system known-host files or invent config source lines for them. Missing directives leave default/final-plan obligations unresolved.
8. IdentityFile may point to a public/certificate record, not just a private-key body. sshkey_load_public probes exact path, `.pub`, then private envelope. An implicit `<identity>-cert` probe (itself using public loader/.pub fallback) happens only if no explicit CertificateFile list exists. CertificateFile uses the same public loader but accepts only certificate material. **Do not auto-associate or authorize `.pub`/`-cert` siblings** from suffixes, same identity or generic references.
9. Therefore a STATIC resource row can certify only the declared literal base mapping/type/direct reference. It cannot prove that stock final consumption touches only that object, mathematical key association or an executable authentication plan. **Main has adopted KEY_RESOLUTION state5** for Identity/Certificate explicit virtual-absolute or ~/ base with no exact metadata. Do not probe base.pub/-cert or approve suffix siblings. Exact-base wrong type still rejects PATH as the new opt-in typed-binding boundary (not a claim stock parsing must fail); User/GlobalKnownHosts and Revoked static missing targets reject PATH. A relative Identity/Certificate remains NEEDS_CWD, not KEY_RESOLUTION.

### Typed metadata sets: what can currently be proved

Native input already contains all original file type/path metadata but only CONFIG bodies. Search metadata directly; **do not use ctos_ssh_io_open** for these resources, and do not use public-view lookup which intentionally rejects private access. Looking up a PRIVATE_KEY's path/type metadata is safe; opening its body is outside scope.

| Resource kind | Defensible current metadata subset | What remains unproved |
| --- | --- | --- |
| IdentityFile | PRIVATE_KEY / PUBLIC_KEY / CERTIFICATE union, if the literal path exists and is directly referenced | Exact private key selection, public/cert linkage, suffix probes, mathematical consistency, available signer and actual final path consumption. Narrowing to PRIVATE_KEY would silently reject existing upstream forms. |
| CertificateFile | CERTIFICATE for an explicit static target | Public-loader fallback, cert/private association, validity/principals/CA/revocation and actual Request suitability. |
| UserKnownHostsFile / GlobalKnownHostsFile | KNOWN_HOSTS | Selector/marker/CA/revocation semantics, full trust and real source/target review. |
| RevokedHostKeys | Narrow explicit flat **single PUBLIC_KEY** record is representable. A CERTIFICATE record can also be read by sshkey_read, but whether to support that subset must be frozen; do not infer it automatically. | KRL has no dedicated manifest type/parser; multi-record flat key list is not the existing PUBLIC_KEY inspector contract (which accepts one record). KNOWN_HOSTS is a different host-pattern record format and must not stand in for revocation input. Complete revocation and CA semantics remain pending. |

An existing metadata object typed PUBLIC_KEY is not proof its body is a valid KRL/list or even a key until material inspection succeeds; this resource pass never reads it. For wrong-type/missing static targets, use existing PATH/type diagnostics or explicit unresolved-key-resolution state according to the frozen case above. Do not accept every type and mark the typed check complete. Do not introduce a new KRL/list type in this bounded increment.

Java must recheck the target type union and exact `target.path in actualParent.references`, not root reachability/sibling references/name suffix/identity. It must not demand references equal the resource list: Include/key/cert/trust/dynamic references can coexist. Resource-reference proof must be named **static** and remain separate from complete resource resolution/material/identity/trust proof.

### Suggested explicit API/mode/wire

Add `CTOS_CONFIG_RESOURCE_AUDIT` and distinct `reserveResourceAudit` / `resourceAudit` / postwrite-live methods. Keep existing reserveDetached boolean and reserveAudit unchanged. New mode allocates existing audit/catalog plus bounded resource rows; uses shared scan/policy/Match syntax-only/Include-edge behavior, but old audit/catalog getters reject it. Internal predicates should deliberately distinguish exact old mode from shared policy-scan behavior; do not accidentally skip finite policy/Match syntax-only/Include edges in the new mode. No new full input wire or second protected copy is needed.

The main session's proposed numeric row fits this boundary:

```text
CSCRv1:
    u32 magic=0x43534352, version=1, flags=1,
    u32 auditLength, resourceCount
    byte[auditLength] exactOriginalCSCAv1
    repeat resourceCount:
        u32 kind, parentObject, sourceLine, argumentOrdinal, state, targetObject
```

Suggested kinds1..5: identity/certificate/userKnown/globalKnown/revoked. Main has adopted the state set STATIC/DISABLED/NEEDS_EXPANSION/NEEDS_CWD/KEY_RESOLUTION, with KEY_RESOLUTION=5 restricted to Identity/Certificate and only the explicit virtual-static missing-base case above. The other state numeric values remain for the main contract to freeze. Additional home forms should reject or use a separately frozen extension; do not silently add a sixth state. State enum must be closed and type-checked. STATIC has target1..128; every nonstatic state has target0. Parent must be actual CONFIG; sourceLine1..16384; argOrdinal1..256 (Identity/Certificate exactly1). DISABLED valid only for the kinds whose upstream sentinel is actually supported; CertificateFile none must not sneak into it. No raw token/path/secret/native pointer is returned.

Per-eval resourceCount<=1024 is a reasonable proposed cap, yielding max `20 + 82732 + 1024*24 = 107328` bytes. Preserve each distinct declaration `(parent,line,kind,argOrdinal)` in first DFS order. Deduplicate only repeated traversal of **the same declaration** across Include visits; do not collapse distinct arguments/declarations referring to the same target. Duplicate provenance with conflicting state/target is invalid rather than first-wins. This source identity is meaningful without leaking raw tokens.

Require checked exact `20+auditLength+24*resourceCount == length`, flags/version, exact nested CSCA consumption and no trailing bytes. Charge whole CSCR once to1MiB retained report; aliases+Include-deferred+Include-edges+resourceRows all count against4096 aggregate rows. Subtract resourceCount **before** nested CSCA decode/allocation, then let existing CSCA subtract its edge rows before nested CSCD. No double decode or double retention. The chosen row cap must fail with LIMIT before allocating beyond it.

### Smallest native implementation seams

1. Inside patched parser TU, add a narrow resource opcode switch after shared argv parsing/finite audit policy and before non-Include early return. Reuse exact argv; pass kind/token ordinal/value to evaluator hook. Identity/Certificate require one nonempty token. List families validate nonempty list and exclusive none; no branch/Options state. Do not add Java lexer or parse all directives into scratch Options.
2. Hook uses current actual stack's object/line, bounded original input table and existing original deadline/cancel. Classify sentinel/context/cwd/home/explicit virtual-static according to frozen rules. Use exact metadata table lookup and shared managed suffix/path validation; no filename loading, stat, glob or resource body copy. Reject static wrong type only under the selected typed/fallback policy.
3. Keep existing Include recursion/deferred logic intact; resource deferred rows are separate from Include deferredSites and cannot silently inflate/alter CSCD flags. Repeated parser visits still charge real reads/lines despite declaration-row dedup.
4. Reuse already issued owner before load, single locked CSDI adoption, pending-open error handling, 64 slots, parser trylock, owner/PID/UID, cancel inheritance, getter liveness/JNI postwrite checks, metadata wipe/close retry and Java pending-owner protocol. New resource arrays must have cleanup ownership before fallible allocations; release on every Error/cancel/failure path.
5. All roots share original1024opens/64MiBreads/16384lines, CONFIGcopy-amplification<=64MiB,1MiB retained bytes and4096 rows. Metadata lookup itself opens zero files. Cap row collection before quadratic table work, check cancellation inside per-token/table loops, and preserve original root/object ordinals including leading nonCONFIG files.

### Main decisions and remaining contract choices

- Exact typed union for IdentityFile, optional CERTIFICATE subset for RevokedHostKeys, and wording of unresolved KRL/multi-key-list/material obligations.
- CertificateFile none literal behavior versus an explicit project-specific extension; upstream code does not justify silently treating it as DISABLED.
- Main has adopted relative NEEDS_CWD, ~/ explicit virtual-home mapping, kind-specific expansion deferral and GlobalKnownHostsFile literal `$` / rejected `%`; other-home/bare-home rejection details still belong in the contract.
- Main has adopted KEY_RESOLUTION state5 for missing exact virtual-static Identity/Certificate bases; exact wrong type rejects as an opt-in typed-binding rule, and missing static known-host/revoked targets reject. No inferred sibling authority.
- Targeted per-line arity/list cap rules, row dedup/order/cap/state enums and exact result flags. Resource audit does not claim effective first-value/additive aggregate limits across all targets.

### Meaningful tests

- Preserve exact old EFFECTIVE/CATALOG/AUDIT CSCA bytes/results on representative sources; old modes do not acquire new static-resource failures/rows. Wrong-mode getter rejection and opt-in defaults fail closed.
- Shared-parser exact argv: quoted spaces/none, comments, escapes, empty/comment-only/missing/extra args, single vs multi-value directives, mixed none+path and repeated paths, upper/lowercase sentinels. Certificate none distinction and Revoked none clearing evidence must remain regression cases.
- Resource declarations in inactive/shadowed Host/Match, nested/orphan/all roots; real parent/line/argOrdinal, duplicate tokens preserved, repeated traversal dedup without skipping read charges.
- Every supported static type union and wrong-type/missing/unsupported subset; only metadata accessed, private/nonCONFIG body copy/open observer stays zero. Identity PUBLIC/CERT not mislabeled private; KnownHosts cannot masquerade as revocation input; no suffix association.
- Relative same-spelling manifest object stays NEEDS_CWD; virtual ~/ vs absolute mapping; no Include `.ssh/` or CONFIG-parent anchor; ~other/bare~, traversal/outside virtual root; directive-specific %/$/%% and GlobalKnownHosts literal handling; wildcard tokens never glob.
- Native/Java strict CSCR length/count/flags/closed state combinations/source/type/duplicate-provenance/target constraints, aggregate rows reserved before nested decode and whole-wire byte accounting, immutable partial-only reports and static direct-reference failures.
- Original deadline/cancel during copy/scan/metadata lookup/getter/cleanup, acquisition race, parser busy/owner/PID/UID, pending cleanup retry, allocation/Error/JNI copy/postwrite failure; effects0/capabilities0 and all existing real matrices/host IO regressions.
- Explicit examples where resource row is STATIC but math/private linkage/KRL/trust/implicit default or dynamic/final Request obligations remain pending; no provider registration or restore gate change.

Main owns implementation dispatch, contract/spec/build runner/patch-hash updates, strict ARM/QEMU/JVM/lint/normal/fixture and archive/export/artifact verification. This agent performed source research only; compile/platform execution claims require their own current evidence.

### References and related specs

- Local pinned OpenSSH10.6p1 readconf.c/ssh.c/authfile.c/sshconnect.c/sshconnect.h/krl.c only; no external lookup.
- `.trellis/spec/backend/ssh-config-audit.md`, `ssh-detached-config-managed.md`, `ssh-detached-config.md`, `ssh-native.md`; exact old audit/CSDI owner/usage/material boundaries.
- `design/ssh-config-audit.md`, `ssh-detached-config-managed.md`, `ssh-config-evaluator.md`, `ssh-manifest-inspection.md`, `ssh-validation-lifecycle.md`; finite policy and admission obligations.
- Current audit contract now explicitly freezes CanonicalizePermittedCNAMEs exclusive none, original lowercase ControlPersist no/false and exact outer CSCA arithmetic; earlier research scope-choice discussion is historical and these decisions are no longer open.

## Caveats / Not Found

- No project contract defining a cwd for non-Include resources was found. Do not invent it from Include anchoring or current host process state.
- Complete suffix/file probing, private/public/cert association, KRL/multi-record revocation support and trust semantics cannot be proved from metadata-only input. They remain explicit obligations even with valid static references.
- Proposed resource mode/CSCR/states/type subsets are not implemented or frozen by this report. Distinguish original pinned behavior from deliberate project restrictions/deferred coverage.
- Only this research file was written; no implementation/design/spec/script/task state or sibling research modified.

Main frozen contract after recovery: design/ssh-config-resources.md fixes states STATIC1/CONTEXT2/CWD3/DISABLED4/KEY_RESOLUTION5; other-home/bare~ and outside virtual absolute reject PATH; Identity/Cert single, Known32, Revoked256, Revoked PUBLIC_KEY-only. Dynamic detection for nonGlobal families is percent or `${`; bare `$name` remains literal per pinned helper, superseding the earlier conservative-any-dollar recommendation. Global treats all tokens literally; valid dollar paths bind, percent paths fail managed representation. See contract for exact precedence, missing-key deferral and bounds.

# Research: same-process managed OpenSSH configuration evaluator

- Query: identify the smallest safe production evaluator using the pinned OpenSSH parser, managed FILE view and original Include semantics; identify input-fatal, transitive effects, ownership and symbol isolation requirements.
- Scope: internal, source and existing object-symbol inspection only. No builds, execution of target artifacts, network, device, accounts, git operations or production edits.
- Date: 2026-10-19
- Active task: `.trellis/tasks/10-08-ssh-terminal-client`.
- This is implementation research, not evidence that an evaluator exists or passes tests. It supplements `launcher-native-20261019.md` without repeating that gap audit.

## Findings

### Source map and inspected artifacts

The line references below use `UP=build/ssh-native-g1/stock-run.zPRULpSC/sources/openssh-10.6p1`. Production currently pins the equivalent source under `stock-run.lOHF9Oai` in `android/app/build.gradle:187`; implementations must use that authoritative production baseline and its existing hash checks, not mutate either generated tree.

| File | Purpose |
| --- | --- |
| `UP/readconf.c`, `UP/readconf.h` | Actual 10.6p1 Options parser, matching, Include, defaults, forward and jump parsing. |
| `UP/misc.c` | Expansion, numeric/service ports, URI and jump helpers, array append, argument splitting and forward equality. |
| `UP/openbsd-compat/glob.c:364` | Original glob; tilde handling reads process HOME or passwd. |
| `UP/kex-names.c:160` | Algorithm validation includes logging; name assembly returns errors. |
| `android/app/src/main/cpp/ssh_config_policy.[ch]` | Existing sticky policy checks; global fixture state and fixture-only Include provider. |
| `android/app/src/main/cpp/ssh_config_policy_probe.c:94` | Existing FILE, directory/stat/glob adapters and denial observers; evidence patterns, not production code to promote wholesale. |
| `android/app/src/main/cpp/ssh_file_io.[ch]` | Existing closed-graph memfd readers, budgets and retryable cleanup. |
| `android/app/src/main/cpp/ssh_file_view.h:7` | Manifest bounds and CONFIG/private/public/known-hosts type separation. |
| `android/app/src/main/cpp/ssh_view_handle.[ch]` | Existing view borrow and revoke ownership pattern to retain. |
| `research/openssh-10.6p1-config-policy.patch` | Current parser policy patch; ssh.c/sshconnect.c hunks remain fixture-only. |
| `research/android-policy-build.sh:112` | Fixture links readconf plus stock ssh/sshconnect/high-level objects with final-link wrapping. Do not copy this closure into production. |
| `research/android-production-openssh-build.sh:246` | Current full PIC libssh closure; readconf is absent. |
| `android/app/build.gradle:225` | Production shared-library link already compiles ssh_file_io and links existing PIC libssh/compat/crypto. |

Existing-object inspection used NDK `llvm-nm` on `build/ssh-native-policy/run.YDuVYoaN/policy-readconf.o` and `build/app/generated/sshProduction/arm64-v8a/libctos_ssh_production.a`. The parser's undefined closure contains helpers supplied by existing libssh/misc, crypto, libc and compat glob; it does not need symbols from ssh.c, sshconnect.c or clientloop.c. This is source/object evidence, not a fresh link test.

### Recommended API and ownership

Use one opaque evaluation object, retaining full upstream Options internally. This avoids losing effective fields in an initial hand-maintained DTO. It is a configuration result, not permission or evidence that a connector implemented those fields.

Suggested API names and signatures (freeze exact names in the contract before implementation):

```c
int ctos_ssh_config_eval_open(struct ctos_ssh_view_borrow *borrow,
    const struct ctos_ssh_config_request *request,
    struct ctos_ssh_config_eval **out);
int ctos_ssh_config_eval_run(struct ctos_ssh_config_eval *,
    struct ctos_ssh_config_error *out);
int ctos_ssh_config_eval_summary(const struct ctos_ssh_config_eval *,
    struct ctos_ssh_config_summary *out);
/* Private C header only; pointer valid until close, never mutable/public/JNI. */
int ctos_ssh_config_eval_options(const struct ctos_ssh_config_eval *,
    const Options **out);
void ctos_ssh_config_eval_cancel(struct ctos_ssh_config_eval *);
int ctos_ssh_config_eval_close(struct ctos_ssh_config_eval *);
int ctos_ssh_config_eval_destroy(struct ctos_ssh_config_eval *);
```

- `open`: copy and validate bounded request, admit live exact borrow, record PID/UID/owner thread/original deadline. Transfer borrow only after every fallible admission/setup succeeds. On failure `*out=NULL`, borrow remains caller-owned. No role issuance, native session, socket, callback or secret capability is created.
- `run`: owner-only, nonreentrant and one-shot OPEN→RUNNING→READY or FAILED. Trylock parser context serialization; busy/reentrant refusal before mutation need not consume run. All input/policy/runtime errors return normally. The caller always retains the handle after an unsuccessful run, including uncertain FILE cleanup.
- READY reads check owner, cancellation, live original borrow and deadline before publication. Clear output on failure. Never publish partial Options after failure. The internal `const Options *` is a borrowed pointer; prohibit storing it beyond close or mutating/freeing its children.
- `cancel` only atomically fences the evaluator; it performs no parser/FILE/borrow cleanup or JNI. Owner loop checks cancellation, deadline and view liveness before each line/open/glob operation and final publication. Atomic cancellation alone cannot interrupt arbitrary library calls, so line/token/glob budgets remain mandatory.
- `close` stages Options cleanup, all io reader cleanup, then borrow release; record progress and retain uncertain resources for retry. Release the parent borrow only after every child FILE reader has been reclaimed. A sticky io parse error is not itself proof of an outstanding reader: distinguish operation status from cleanup completion by actual reader ownership fields. `destroy` requires a closed tombstone.
- Parser serialization covers only run, never arbitrary user callbacks. A single guarded current-context pointer is adequate if every hook is private and only entered while holding that guard. Do not install/reset global log handlers or alter process environment. Separate immutable plans can coexist after serialization ends.

The lifecycle is justified by `ssh_file_io.c:37-62,117-123`: munmap can fail, a live reader then remains owned, and io_destroy's error is sticky. A stack-only evaluator that returns and forgets its readers is unsafe.

Request context should explicitly contain root identity/path from the view, original host alias, optional explicit remote-user/port overrides, optional remote-user fallback (distinct from local identity), local username/UID, fixed virtual home, local hostname, and optional match tag/remote command context. Do not obtain these from getpwuid/gethostname/getenv or a stock command line. Use only flags owned by the evaluator; never accept arbitrary SSHCONF bits from a public caller.

Remote user needs special handling: `readconf.c:654,713` falls back to `pw_name` before a User has been set. Preserve a distinct request-supplied remote fallback for Match user and `%r`, or report unresolved-user context when such an evaluation needs it. Do not seed Options.user with a fallback before parsing because that would shadow config User. After defaults do not invent a remote username from the Android local pw_name. An explicit remote override may seed Options.user with normal command-line precedence.

### First pass, final pass and expansion order

Mirror the relevant upstream sequence (`ssh.c:1186-1272`) without invoking ssh.c:

1. `initialize_options`, set host_arg and explicit override fields only; parse exact managed root with SSHCONF_USERCONF and wanted-final output.
2. Fill only canonicalization defaults. Expand HostName with `%h` using the original alias (not the later broad token table); lowercase nonnumeric hostname with original rules. Store resolved HostName before a final pass.
3. Diagnose active CanonicalizeHostname other than no, and active permitted-CNAME behavior requiring resolution, as unsupported before attempting DNS. No resolver is allowed here. It is incorrect to report a no-op canonicalization success. `Match canonical` alone follows upstream final-pass behavior and does not itself authorize a resolver.
4. If `want_final_pass`, re-read the SAME Options (do not reset), using effective host as the host match context, original alias as original_host, and SSHCONF_FINAL. Recheck unsupported canonicalization values after this pass too. Preserve scalar first-set and original additive/first-array behavior.
5. `fill_default_options` returns an error on algorithm assembly failure; capture a structured DEFAULTS diagnostic and clean up. Resolve effective port0 to fixed22 in the summary or represent upstream port0 plus effective22 explicitly; never use getservbyname.
6. Carry out required postparse pure expansion explicitly and with returning errors: remote User has its restricted token table (no `%r`/`%C`), HostKeyAlias lowercasing, and identity/known-host path expansion have their own upstream token sets/order. Raw fields not yet expanded must be marked raw/unresolved, never presented as ready-to-connect values. Preserve RemoteCommand, jump fields, forward fields and algorithm settings even when this increment cannot apply them.

`ssh.c:1403-1467` shows that later user/full connection expansion is outside readconf; calling read_config_file twice and fill_default_options alone is not a complete ssh -G equivalent. Broad remote command/session/identity finalization can remain deferred behind explicit unresolved/capability diagnostics, provided no connector treats the result as an executable plan.

Include preserves upstream anchoring: relative paths live under virtual `~/.ssh`, not the including file's directory (`readconf.c:2094-2113`). Keep each recursive parse's active-state restore and NEVERMATCH semantics (`2133-2154`). The depth cap is8 (root depth0), with complete failure on missing matches/invalid typed members as already required by project policy.

### Exact parser fatal and error paths

The existing config-policy patch converts only include-depth admission and bad-options aggregation. The following stock sites still need deliberate conversion or documented unreachability. Line references are unpatched UP/readconf.c:

| Site | Classification and recommended change |
| --- | --- |
| 358, ssh_connection_hash | Digest/provider failure, not input syntax; return NULL, free digest context, propagate CRYPTO through Include expansion. No fatal or global catch. |
| 459/485/491, add_certificate_file/add_identity_file | Input count/path limits. Parser branches already check file counts at1413/1433; make production helper variants return errors or record sticky limit and return with caller propagation. The default six identity insertions are bounded. Free path on a too-long combined default path. |
| 536/547/564/568; 875/887 | Match exec implementation; production preprocessor must REMOVE execution body (or make it a returning deny stub), not merely add a branch followed by unreachable shell code. Existing Match exec policy rejection must remain unconditional including inactive/NEVERMATCH/negation. |
| 665 | Hostname acquisition failure. Replace OS lookup with bounded request context; return a context error if absent. |
| 1797/1808/1819 | PermitRemoteOpen malformed host/port/no specifications. Record INPUT at current object/line and goto common out, freeing current arg2 first. |
| 1957/1980 | SendEnv/SetEnv empty argument list (e.g. comment-only remainder). INPUT + goto out. |
| 2240/2287/2357 | CanonicalDomains, CanonicalizePermittedCNAMEs, RevokedHostKeys empty list. INPUT + goto out, retaining existing strs/cnames cleanup. |
| 2526/2533 | ChannelTimeout invalid clause/empty list. INPUT + goto out. |
| 2546/2550 | VersionAddendum missing argument/carriage return. INPUT + goto out. |
| 2574 | Active RefuseConnection is a deliberate config refusal. Return REFUSED with object/line, never success, never echo arbitrary refusal text. |
| 2642 | Recursive includes. Existing depth8 path should remain returning LIMIT/PATH; never reach stock depth16 fatal. |
| 2651/2654 | Managed FILE fstat/permission failure. Returning IO/PERMISSION path which closes the stream; do not simply return before cleanup. |
| 2680 | Aggregated bad options. Existing returning branch can be retained; stop on the first rejected line to bound work and retain exact first diagnostic. |
| 3753 | dump_client_config algorithm failure. Do not expose/call dump_client_config in the evaluator; avoid printf output entirely. |

Structured syntax diagnostics need parser context BEFORE parsing each line. Several errors return before common out (`1199`, `1205`), and helpers may return failure without policy_error. At the read loop, convert any nonzero line result without an existing reason into INPUT at exact current object/line. Do not classify arbitrary error-level logs as rejection: stock oUnsupported at2585 logs but returns success. Production must explicitly reject an effective unsupported directive or retain a diagnostic; never silently accept it as applied. IgnoreUnknown and deprecated/ignored cases should be an explicit policy decision, not accidentally reclassified by a logging shim.

File reads need `ferror` and fclose result checks; stock `getline` EOF handling at2664 and unchecked fclose2678 can otherwise produce a successful partial parse. Enforce no embedded NUL and bounded line length before strlen-based parsing. The current view is byte-oriented and does not make every CONFIG body safe for C-string parsing.

### Inherited helper hazards and exact isolation strategy

Renaming functions in readconf.c alone does not rename references INTERNAL to already compiled misc.c or compat glob. Important transitive paths:

| Existing helper | Hazard | Production isolation |
| --- | --- | --- |
| `misc.c:1496/1515`, percent[_dollar]_expand | Malformed/trailing/unknown percent tokens call fatal1506/1525 after buffer allocation. `${...}` consults getenv1416. | Private returning bounded expander with explicit request token table, no environment lookup; or narrowly adapted pinned implementation with parseerror returned. Every call site checks NULL and cleanup. Never rename fatal to a returning function declared noreturn. |
| `misc.c:1477`, dollar_expand | Returns syntax errors but may getenv; used by readconf parse_agent_path2441 AND parse_forward3313, even inactive lines. | TU-only dollar_expand hook returns literal copy when no `${...}`, otherwise explicit ENV_UNSUPPORTED without getenv; ensure enclosing parser treats failure as rejection (Include must not merely continue at2089). |
| `misc.c:592`, a2port | Invalid/non-numeric input falls through to getservbyname601 (ordinary service database access). | TU-only numeric a2port for direct parser/forward/PermitRemoteOpen sites. Diagnose named service unsupported; preserve0 only where forward semantics permit. |
| `misc.c:982` parse_user_host_port;1110 parse_uri | Both internally call stock a2port; readconf parse_jump3502/3503 reaches them. Direct a2port rename in readconf is insufficient. | Private narrowly copied pinned helper closure (URI requires static hexchar/urldecode) with numeric conversion; or verify exact optional port via original hpdelim helpers BEFORE calling shared helpers. Retain original URI/user/IPv6 semantics; do not write a new jump parser. |
| `readconf.c:509`, default_ssh_port | Static cached getservbyname. | Private fixed22 implementation or request-owned default; no global service lookup. |
| `readconf.c:583`, Match localnetwork | getifaddrs/getnameinfo, OS network state and potentially logging. | Explicit UNSUPPORTED_MATCH before this branch; do not fabricate false. If future support is needed, use a frozen request-supplied numeric network context. |
| `glob.c:395` globtilde | getenv(HOME) before getpwuid; ALTDIRFUNC does not prevent it. | Resolve allowed `~/` to fixed `/ctos-vault/` BEFORE glob, reject other-user home, clear GLOB_TILDE; use original compat glob with GLOB_ALTDIRFUNC only and managed dir/stat callbacks. No HOME mutation or final-link passwd wrapping. |
| `misc.c:2756` opt_array_append2 | Input INT_MAX limit calls fatal; xrecallocarray uses terminating allocation convention. | Finite work/entry budgets well below INT_MAX enforced before every parser append (local helper if needed). Preserve xalloc OOM convention; do not use whole-library fatal interception. |
| `kex-names.c:160` kex_names_valid | Error/debug logging includes supplied algorithm names. | Private quiet pinned validator (or same semantics via kex_alg_list + exact name membership); avoid modifying global logger. Algorithm enumeration/assembly have no network/filesystem effects. |
| `addrmatch.c:106` addr_match_cidr_list | Logs invalid supplied network data. | Unreachable after early localnetwork unsupported guard; do not let this helper run solely for validation. |

Stock readconf itself logs full filenames/config lines/commands at many debug/error sites. Supply a TU-local sshlog replacement after headers (or compile-time scoped renamed sshlog symbol) that discards payload and relies on explicit parser reason/object/line state. Do NOT call set_log_handler or log_change_level: those mutate existing production session logging. Helper functions compiled elsewhere still use real sshlog, so all configuration-dependent transitive logging must be removed/isolated as above. OOM/invariant diagnostics in existing xalloc remain library conventions; this is not a promise to recover from process-wide allocation failure.

`argv_split` (`misc.c:2143`) has returning invalid-quotes cleanup and otherwise xalloc OOM; parse_pattern_interval2636 is returning and pure. Existing `kex_assemble_names` (`kex-names.c:240-333`) returns format/allocation errors. There is no reason to add setjmp/longjmp across these objects. The safe statement is that bounded configuration-triggered errors return normally; a general OpenSSH allocation/invariant failure retains its original terminating convention.

### Minimal source, object and build changes

1. Add a dedicated production readconf patch/guard distinct from fixture behavior, applied to a fresh copied readconf.c only. Keep current policy fixture unchanged initially. Guard production hooks with a distinct macro (e.g. CTOS_SSH_CONFIG_EVALUATOR), scope their include AFTER standard/upstream headers, and namespace all global parser exports to ctos_config_upstream_*.
2. New project `ssh_config_evaluator.[ch]` (opaque lifecycle, bounded request/summary/diagnostics/private Options accessor) and internal hook header/source containing managed FILE/glob/context/quiet helpers. A separate internal header containing upstream Options includes prevents leaking its ABI to public callers/JNI.
3. Add exactly one PIC readconf object to the existing production closure; link adapter/hooks against current libssh, libopenbsd-compat, crypto. If narrowly copied URI/quiet helpers are separate, add one project helper object; no stock ssh.o/sshconnect.o/clientloop.o, no duplicate libssh instance or fixture globals. Use original compat glob from existing compat archive.
4. Change production builder/Gradle declared inputs, source/patch hashes, source notices and expected object/symbol audit. Archive expected count changes from83 to84 only if readconf becomes an archive member; adapter object count depends on existing source-link convention. Do not copy the fixture POLICY_BASE object set or final-link --wrap flags.

Global parser export names found in existing policy-readconf.o (namespace them all, not only read_config_file): kex_default_pk_alg, ssh_connection_hash, add_local_forward, add_remote_forward, add_certificate_file, add_identity_file, default_ssh_port, process_config_line, read_config_file, option_clear_or_none, config_has_permitted_cnames, initialize_options, fill_default_options_for_canonicalization, fill_default_options, free_options, parse_forward, parse_jump, parse_ssh_uri, ssh_valid_hostname, ssh_valid_ruser, dump_client_config. Existing production archive currently defines none of these parser globals, but exposes sshlog/sshfatal and forward_equals; do not override those. Namespacing protects future high-level/fixture links and limits accidental API exposure; hidden visibility is desirable in addition.

Global state in the existing policy is policy_error and fixture_view; neither belongs in production. Use evaluator-owned diagnostic/view/io/context with first-error stickiness. Parser caches include kex_default_pk_alg.pkalgs and default_ssh_port.port; fixed default port and avoiding dump may make them unreachable, but simple trylock serialization still gives an enforceable hook-context/reentry rule.

### Complete Options retention and cleanup defects

Opaque full Options ownership is preferable to a DTO claiming completeness. It automatically preserves all jump fields (`readconf.h:175`), all `Forward` host/path/port fields and forwarding options, RemoteCommand/TTY/session type, auth method toggles, identities/certificates, host aliases/trust paths, algorithms, bind/timeout/rekey/compression/environment flags. Public summary may expose bounded subsets/counts and required capabilities. Future launch MUST deliberately apply or diagnose every effective behavior it consumes; the existence of Options is not evidence that ProxyJump/forward/RemoteCommand is implemented. In particular jump_extra is the preceding chain and jump_host is the final jump, not the first (`readconf.c:3494-3539`). Preserve order and explicit user/port distinctions; never drop jump_extra and direct-connect.

Pinned free_options3138-3216 omits allocated host_arg, tag, canonical_domains container, permitted_cnames container, known_hosts_command, channel_timeouts (elements/container), and version_addendum. Patch the production namespaced free_options to release them exactly once, or detach/free them in the adapter BEFORE calling stock free_options (which zeroes Options). Do not free default string literals not owned by Options.

Temporary forwarding ownership also needs repair: parse_forward allocates fwd strings, but inactive branches at1769 drop them, and add_*_forward returns early on duplicates369/398 without consuming/freeing newfwd. Patch the production parser to explicitly transfer/reset or free temporary fields on every path. Similarly audit Include p/arg2/gl cleanup on glob error and early policy rejection, PermitRemoteOpen arg2 on malformed input, and match_cfg_line criteria on a newly returning error. New fatal→goto conversions must preserve local allocation ownership rather than relying solely on free_options.

Beware defaults versus policy: fill_default_options adds system known-host paths2944, default identities2930, SK provider3025, update-hostkeys2950 and enabled optional auth methods. A blind second call to current policy_options rejects default `$SSH_SK_PROVIDER`, so do not use that unchanged as final plan validation. Retain origin/explicitness or set approved evaluator-specific neutral defaults BEFORE fill only where the contract explicitly requires them. No arbitrary path should be read just because defaults mention it. Default optional KBI support is not itself a demand to execute KBI; future selected method negotiation must diagnose unsupported use.

### Bounds and diagnostics to freeze

Existing hard bounds remain authoritative:128 manifest objects,1MiB per public file,8MiB manifest public payload;128 simultaneous readers,1024 open attempts,8MiB held reader bytes,64MiB cumulative reader copies;512-byte canonical paths, depth8. Additional suggested evaluator bounds (technical choices, not existing implemented constants):

- alias/effective host up to existing role host limit; username up to existing auth255-byte limit; local context strings similarly explicit and no controls/NUL;
- line16KiB,256 tokens per line, aggregate line/token work bounded across both passes; reject before argv_split (which allocates based on remainder lengths);
- at most64 local and64 remote forwards,128 entries for each unbounded string array, explicit total retained string bytes (e.g.8MiB) accounted BEFORE append/copy, not only after parsing;
- no truncation of any accepted string/list; summary fits fixed fields/counts with a version; large strings stay in the plan and copy accessors return a size/error;
- diagnostics fixed numeric reason, managed object ordinal/stable identity reference, line, pass, operation/keyword enum, and errno category; no config text/path/command/environment bytes. Preserve first causal error, retain separate cleanup-required status.

Suggested reasons: INPUT, POLICY_COMMAND, POLICY_TRUST, POLICY_EXTERNAL, PATH_OR_TYPE, MISSING_INCLUDE, LIMIT, ENV_UNSUPPORTED, MATCH_UNSUPPORTED, CANONICALIZATION_UNSUPPORTED, CONTEXT_REQUIRED, REFUSED, IO, CRYPTO, DEFAULTS, CANCELLED, EXPIRED, REVOKED, OWNER, REENTRANT/BUSY, PHASE, CLEANUP. Do not return READY or a nonempty summary after any error.

### Test matrix for the implementation/check agents

| Group | Minimum meaningful coverage |
| --- | --- |
| Upstream semantics | Scalar first value, inactive/shadowed dangerous directives, additive identities/forwards, first-list arrays, Host negation/wildcards, Match host/originalhost/user/localuser/tagged/command/sessiontype/version, explicit remote fallback distinction, final pass with HostName substitution and original alias, NEVERMATCH. |
| Include | Exact root, nested/glob lexical order, multi-pattern, relative to ~/.ssh, ~/ and virtual absolute forms, tokens, empty file, unmatched/missing/type mismatch, other-user/absolute/proc/traversal escape, child Match active restore, inactive child exec refusal, depth8/9, cycles/open/read/line work exhaustion. |
| Fatal regression | Each formerly fatal directive path above, comment-only lists, invalid ChannelTimeout, CR VersionAddendum, active RefuseConnection, fstat/permission/stream read-close failure, malformed `%`/`${}` tokens; next independent evaluation succeeds after failure. |
| Effects | Observer rejects every ordinary open/stat/opendir/getpw/gethostname/getservbyname/getenv/getifaddrs/resolver/socket/connect/fork/exec reached by evaluator; malformed named port inside both plain and ssh:// jump forms; HOME/SHELL/SSH_SK_PROVIDER changes cannot influence evaluation. Original compat glob observes only managed callbacks. |
| Preservation | Multi-hop jump user/host/port/extra, every forward type including explicit external/listen paths/remote-dynamic unsupported form, ClearAllForwardings, GatewayPorts, ExitOnForwardFailure, RemoteCommand, TTY/session/env/bind/crypto settings retained or precise unsupported diagnostic; no silent direct fallback. |
| Ownership | Failed-open borrow unchanged; failed-run handle retained; cancellation/deadline/revoke before/during/after Include; exact owner/thread/PID/UID; concurrent trylock busy; reentry; repeated run; READY getter after revoke; close retry with failed munmap/borrow release; destroy preclose refusal; no leaked temporary fwd/Include/Options strings. |
| Regression/build | Existing native client auth/KEX/PTY tests and policy fixture unchanged; strict compilation, PIC/shared no-undefined, namespaced parser symbol audit, no final-link wrappers/stock caller/fixture class in normal APK, source/patch notices and exact membership evidence. Host synthetic tests do not replace Android JNI/device acceptance. |

## External References / Versions

- No external access was performed, per dispatch scope. OpenSSH10.6p1 pinned local sources and existing object symbols are the authority for this report.
- Preserve upstream copyright notices when adapting narrow helper source; new production patch and local change notice belong in the existing dependency evidence path.

## Related Specs

- `.trellis/spec/backend/ssh-native.md`: configuration policy increment, real Include closure, typed public FILE access, current native owner/borrow/liveness contracts.
- `.trellis/spec/backend/error-handling.md`: explicit unsupported/permission/parse state and retained cleanup ownership.
- `.trellis/spec/backend/quality-guidelines.md`: layer-specific checks and separate local/device evidence.
- `design/ssh-launcher.md`, `design/constraints.md`, parent/child task PRD/design/implement: same-process execution, managed Include/ProxyJump scope, no local commands, no implicit device/account authorization.

## Caveats / Not Found

- This report deliberately does not claim every allocation failure can be recovered. Existing xalloc and internal invariant failures remain OpenSSH conventions; only configuration-triggered syntax/policy/limit/context/IO errors are made returning.
- No fresh compile/link, test execution, network or device action was performed. Helper closure/source-copy recommendations require implementation and symbol/effect tests before production promotion.
- Full Options retention provides completeness of stored parser state, not completeness of ssh.c finalization or runtime application. Canonicalization/localnetwork/process-environment behavior must return explicit unsupported/context errors; jumps/forwards/remote command must remain present and require a future capable connector.
- Research-only write: this file. The existing uncommitted production/auth/PTY work is preserved.

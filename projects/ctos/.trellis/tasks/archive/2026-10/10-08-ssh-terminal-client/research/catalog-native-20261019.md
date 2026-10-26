# Research: Native SSH declaration catalog and validation boundary

- Query: Extend the existing pinned readconf evaluator into a production alias catalog and a future semantic validator without a second Java SSH parser, invented host/user, effects, or private-body access.
- Scope: internal; source inspection only, no build/runtime/device/git operations.
- Date: 2026-10-19
- Active task: `.trellis/tasks/10-08-ssh-terminal-client`

## Findings

### Recommendation

Implement a **declaration catalog scan** in the separately namespaced pinned readconf translation unit, reusing its keyword extraction and `argv_split` boundary. Keep this distinct from ordinary effective evaluation and from whole-library semantic validation. Do not call the evaluator with a dummy alias, username, hostname, or passwd to discover aliases. A finite declaration catalog cannot represent all destinations admitted by wildcard Host and Match conditions.

For the smallest defensible increment, traverse only static Include paths from the real root and expose a bounded declaration list with source object/line. A scan either (a) explicitly rejects context-dependent Includes, or (b) returns an explicitly incomplete result and located unresolved-include records. It must not claim complete discovery while silently skipping them. Main session owns this product/API choice. A stricter first increment can reject all percent/environment Include expansion, while ordinary effective evaluation continues to support its existing request-scoped expansion.

Preserve the normal evaluator unchanged in semantics: after the user selects a real alias, evaluate with real explicit context and report USER_REQUIRED where appropriate. The catalog is historical metadata, not a launch plan or an authority after revocation.

### Relevant files and code patterns

The pinned baseline abbreviation below is `build/ssh-native-g1/stock-run.lOHF9Oai/sources/openssh-10.6p1/`. The build patch creates separate parser objects; the generated `build/app/generated/sshConfigEval/` contains objects and manifests, not a persistent readconf.c copy. Cite the pinned baseline together with the patch where behavior differs.

| File | Role and evidence |
| --- | --- |
| pinned `readconf.c:1174-1209` | Shared SSH lexical boundary: trailing whitespace, `strdelim`, lowercase keyword, `parse_token`, and `argv_split(..., terminate_on_comment=1)` before opcode dispatch. |
| pinned `misc.c:2143-2208` | The actual argv grammar removes recognized quoting/escapes, stops comments at token start, and rejects unterminated quotes. Reuse this rather than split-on-space/regex in Java. |
| pinned `readconf.c:1833-1872` | Host loop consumes positive/negated patterns and mutates active state. NEVERMATCH consumes remaining arguments immediately at 1847; a matching negated token also consumes remaining arguments at 1855-1862. |
| pinned `match.c:78-150` | Host `match_pattern` has only `*` and `?` wildcard semantics. Square brackets are literal here; do not use glob's metacharacter rules to classify Host tokens. |
| pinned `match.c:155-210` | Comma-separated patterns are a distinct helper used by Match. Host dispatch does not split a token on commas. |
| pinned `readconf.c:1874-1888` | Match parser runs even under NEVERMATCH; only the resulting active state is then forced off. NEVERMATCH is not a context-free scan mode. |
| pinned `readconf.c:726-806` | Existing Match grammar handles negation, all, final/canonical, key=value forms and required arguments. A new scan mode should share this grammar rather than reimplement a second accepted syntax. |
| pinned `readconf.c:2072-2158` | Include is parsed even in inactive blocks, expanded before traversal, sorted by glob, and reads children with NEVERMATCH when parent inactive. It restores parent activation after each child. Relative paths are based at `~/.ssh`, not the including file's directory. |
| pinned `readconf.c:3440-3451` | Existing hostname admission helper rejects shell metacharacters/control/whitespace and leading minus, but does not itself exclude Host wildcards. Catalog literal classification needs explicit `*`/`?` and leading `!` handling first. |
| `android/app/src/main/cpp/ssh_config_eval.c:98-137` | Stable first-error object/line/errno and assignment provenance infrastructure. |
| `android/app/src/main/cpp/ssh_config_eval.c:187-224` | Managed CONFIG-only object lookup, depth equality/limit, recursive-stack cycle detection, bounded IO, cleanup. |
| `android/app/src/main/cpp/ssh_config_eval.c:276-288` | Managed glob with ALTDIRFUNC and GLOB_TILDE removed; existing lexical ordering and no host filesystem fallback. |
| `android/app/src/main/cpp/ssh_config_eval.c:337-366` | Remote user comes only from Options/explicit fallback; Include expansion depends on host, original alias, user, port, jump and connection hash. |
| `android/app/src/main/cpp/ssh_config_eval.c:440-522` | Actual first/final parsing and full Options finalization. This requires an admitted alias and explicit local context; it is not declaration discovery. |
| `android/app/src/main/cpp/ssh_config_eval.c:526-566` | Fresh guarded getters, atomic cancellation and retryable cleanup to preserve in the catalog lifecycle. |
| `android/app/src/main/cpp/ssh_config_eval_hooks.h:29-64` | Parser-TU-only effects interposition and namespaced exports. Extend here without global linker wrapping. |
| `.trellis/tasks/10-08-ssh-terminal-client/research/openssh-10.6p1-config-evaluator.patch:296-337` | Patched Match user rejects absent remote context and localnetwork is explicitly unsupported. A dummy scan through normal Match is therefore unsound even before host-dependent Include. |
| same patch `:479-518` | Missing Include/glob or child read is an explicit project PATH/parse rejection instead of upstream no-match tolerance. Preserve this policy in static scan. |
| `android/app/src/main/cpp/ssh_file_view.c:145-176` | Public view shape/size/type/ref graph validation, private body must be zero, all declared objects must be structurally reachable. This is supplied metadata closure, not proof of parsed SSH references. |
| `android/app/src/main/cpp/ssh_file_view.c:249-260` | Typed lookup explicitly denies PRIVATE_KEY body access. |
| `android/app/src/main/cpp/ssh_file_io.c:65-92` | CONFIG readers are only borrowed public body memfds with cumulative open/byte limits. |
| `android/app/src/main/java/im/majo/ctos/AppSecrets.java:38,148,166-175` | Existing `SshContentValidator` callback, unavailable-by-default admission. A catalog does not fulfill this semantic contract by itself. |
| same Java file `:611-630` | Validator runs against the proposed whole manifest before both begin and commit. Do not attach a partial catalog implementation to this callback as complete validation. |
| same Java file `:783-788` | Current public snapshot closure follows manifest `references`, not parsed Include or IdentityFile text. |
| `android/app/src/main/java/im/majo/ctos/SshFileSnapshot.java:38-54` | Snapshot exposes metadata for all selected objects, bodies only for non-private objects. |

### Host collection and identity

Collect from the complete token vector before the normal Host matcher consumes any tokens. Hooking only inside the current Host argument loop loses aliases after NEVERMATCH and after an early negated match. A dedicated scan path immediately after shared lexing is straightforward; it must not mutate ordinary Options into a pretend effective plan.

- Positive tokens with neither `*` nor `?` are literal declaration candidates. Leading `!` tokens and wildcard patterns do not become selectable literal aliases. Do not expand wildcards or split commas. Reject unsupported/non-admitted literal candidates with a located diagnostic rather than silently truncating or making them launchable.
- `Host one two` yields both candidates, even if this declaration follows an inactive Match/Host or occurs in an included file parsed only for syntax. The output describes declarations, not whether that block would activate for each candidate.
- `Host !one one` can list `one` as a positive declaration while normal evaluation correctly rejects that block's activation. This illustrates why declaration membership is not effective-profile validation.
- Preserve source object/line for first occurrence and input byte spelling. Exact-byte stable first-seen deduplication is the least surprising policy relative to the current evaluator; do not introduce silent locale casefold/canonicalization. If case-insensitive logical deduplication is desired, freeze it explicitly and test current evaluator alias matching first.
- Match predicates name conditions, not declarations. `Match host=foo` does not invent a `foo` entry. Match final is likewise not an instruction to run the declaration scan twice.

### Include closure and completeness

There are three different closures:

1. **Manifest reference closure**: metadata selected before native read; currently validated structurally by VaultManifest/public view. These references might include private keys, public keys and trust files as well as configs.
2. **Static parsed Include closure**: actual CONFIG children named by literal/glob Includes in scanned CONFIG bodies. This can include inactive branches. It is discoverable without a target.
3. **Effective closure**: real Include paths after request-context expansion and first/final options, plus effective identity/certificate/trust/jump references. This requires the selected alias and real context, and is what later semantic validation/connector must resolve.

Do not conflate (1) with (2)/(3). Scanning every CONFIG object in manifest closure can be offered as a separate “all supplied config declarations” definition, but it is an overapproximation and must not be labeled root Include discovery.

For static traversal, allow existing relative/`~/`/virtual absolute paths and managed glob rules, same depth8, per-line8192, cumulative line16384, opens1024/copies64MiB, CONFIG-only lookup, missing/cycle rejection and lexical child order. Actual dynamic expansion `%h/%n/%r/%C/%p/%j/%k` depends on values not known during catalog scan. `${...}` remains forbidden. Even static-looking `%u/%l/%i` needs an explicit local context contract; the simplest context-free scan rejects all `%` expansion rather than making assumptions. `%%` is literal percent and managed path admission currently rejects literal `%` (`ssh_file_view.c:76`), so this is not a valid escape to a managed filename.

Inactive Includes cannot simply be ignored: upstream expands and parses them too, with NEVERMATCH only suppressing activation. A child can itself contain nested Includes and Host declarations. Scan each traversal in lexical order (or memoize only with a documented context-free mode) and count repeated work against budgets. Do not global-deduplicate visits before recursive-stack cycle/depth accounting.

### Bounded implementation surface to freeze

Following the main session's preference for a context-free declaration bootstrap, the minimum feasible implementation is the existing `process_config_line_depth` boundary **after opcode/argv_split and the current token-count check, before the ordinary opcode switch**. Keep the existing parser lock/current invocation and add a scan mode to its owned context. Dispatch Host's complete argv to a collector; Include to context-free managed traversal; all remaining opcodes to recognized/lexed consumption. Do not invoke `match_cfg_line`, apply option values, call defaults, expand host/user, or run a final pass. This is not full Match grammar or per-directive value validation. An incomplete result can retain useful declarations while separately reporting each skipped contextual Include with source provenance.

Static Includes inside any Host/Match are safe to read conservatively from CONFIG-only public view. Their aliases may be listed even though no real alias/context would activate that Include: declaration reachability is unknown by design. Match existence alone does not make discovery of **static declarations** incomplete; contextual Include omission does. A `has_conditions` flag can separately communicate that effective behavior needs real evaluation. Do not interpret a generic complete flag as “all possible contexts covered”; prefer an explicit `has_unresolved_includes`/`static_scan_complete` meaning.

Keyword recognition is also a deliberate contract detail. `parse_token` uses `options->ignored_unknown` (pinned readconf.c:959-969). A scanner that never applies Options will not apply `IgnoreUnknown` either. Either keep strict recognized-keyword admission and document it, or reuse a carefully scoped scan-local ignored-unknown state with upstream parsing. Do not silently claim equivalence to ordinary `IgnoreUnknown` semantics if it is omitted. Lexed but unapplied command/provider/trust options likewise cannot be described as policy-validated; they remain ordinary evaluation's responsibility before any use.

Suggested native API shape, names provisional:

```c
ctos_ssh_config_catalog_open(borrow, &catalog);  /* transfer only on success */
ctos_ssh_config_catalog_run(catalog, &diagnostic);
ctos_ssh_config_catalog_count(catalog, &count);
ctos_ssh_config_catalog_entry(catalog, index, &entry);
ctos_ssh_config_catalog_cancel(catalog);
ctos_ssh_config_catalog_close(catalog);
ctos_ssh_config_catalog_destroy(catalog);
```

`entry={alias[256], object, line}`; propose at most256 distinct candidates, no truncation. Reuse native error reasons, owner pthread/PID/UID, shared parser trylock, fresh lifetime checks, atomic cancel and retained cleanup ownership. Full catalog publication is all-or-error for the strict static-only contract. A permissive incomplete contract additionally needs an explicit completeness bit and bounded unresolved-site list; it must not publish the same READY state as a complete catalog without that distinction. Decide whether those extra semantics are justified before implementation.

Inside pinned readconf, introduce a namespaced scan entry using the exact same `strdelim/parse_token/argv_split` lexical boundary, then a mode-aware dispatch for Host/Include and syntax-only Match. For Match, factor validation of attribute names, negation, argument arity and special all/final/canonical handling from predicate evaluation inside the existing function; no `ctos_config_remote_user`, hostname expansion, condition effects or final pass is necessary for a declaration scan. Keep explicit `Match exec` and `localnetwork` policy failures if scanning validates those declarations; never execute them. Other directives may either continue through a rigorously inactive parser mode or be lexically consumed as declarations, but the latter must be called **lexical/declaration scan**, never a full syntax validator. Never use an all-active pass: it would reject inactive policy that ordinary first-value evaluation legitimately ignores and produce order-dependent options from unrelated profiles.

The absolute smallest implementation can share lexer/Host/Include parsing and treat Match/other lines as lexically checked declarations only; in that case malformed Match grammar/invalid Port could remain undetected until ordinary evaluation, which must be explicit in API/docs/tests. A more complete syntax-only mode requires careful sharing of the existing Match grammar and inactive directive parsers and is a larger change.

Only after this surface is frozen should JNI/Java gain their own typed bounded catalog wire/registry. Reusing evaluation CSEQ with a fabricated alias or exposing Options pointers through Java is not an acceptable shortcut.

### Tests worth requiring

- Real parser Host vectors: multi-alias, quoted words, comments, escaped whitespace/quotes, invalid quotes, case/spelling, `*`/`?`, square brackets as Host literals, leading negation, duplicate first source, `Host !x x y`, invalid literal admission, empty Host, alias255/256, count256/257.
- Static Include vectors: nested/glob lexical order, relative `~/.ssh` basis, inactive blocks and NEVERMATCH children, parent activation restoration in unchanged eval, duplicate traversal, root vs orphan supplied CONFIG, missing/escape/wrong type/private object/cycle, exact depth and row budgets.
- Dynamic Include: located explicit refusal or explicit incomplete status for `%h/%n/%r/%C`, no invented user/target, `${...}` refusal, `%%` literal path rejection. A known-alias normal eval still succeeds for supported actual `%C` paths.
- Match: no aliases synthesized from `Match host`; `Match user` with no user cannot force fake fallback; shared grammar checks if promised; exec/localnetwork never cause effects; first/final repeated evaluation baseline unchanged.
- Lifetimes/resources: actual CSPV borrow/token, no private body access, cancel/revoke/expiry during scan/publication/getter, wrong thread/PID/UID, parser EBUSY, failed open/close/unmap retry, repeated FD baseline, zero host FS/env/NSS/DNS/exec/network/log effect observers.
- Integration: no new stock caller/crypto, original archive83 and19 session+6 existing config JNI unchanged unless separately authorized API extension; normal Release/lint/JVM and independent ARM parser execution recorded separately from Android JNI runtime.

### Validation guarantees unavailable from catalog/evaluator alone

Do not claim that catalog success proves: every possible wildcard/manual destination is enumerable; complete dynamic Include closure; all context-dependent Match branches are semantically valid; all effective references match declared metadata; private-key bodies parse/decrypt or match public identity; key passphrase associations are valid; known_hosts lines/fingerprints/conflicts were reviewed; target DNS/reachability/credentials work; every retained raw Options field is finalized/supported; ProxyJump routing or any forward/service/PTY behavior is implemented; full restore review/atomic authorization is complete. The existing evaluator's `has_raw_fields=1` is an intentional guard against such claims.

In particular, a public CONFIG-only scan cannot be attached as the sole AppSecrets whole-manifest validator: the callback also gates import/restore and key/trust association semantics. Private cryptographic validation requires its own deliberately authorized secret-processing surface; ordinary public view must stay private-body-free.

## External references and related specs

- No network research required; authoritative implementation is the locally pinned OpenSSH10.6p1 source and project evaluator patch. No newer upstream behavior is assumed.
- `.trellis/spec/backend/ssh-native.md`: scoped parser effects, full raw Options, borrow-only JNI, limits and separate runtime evidence.
- `.trellis/spec/backend/app-secrets.md`: semantic consumer validation, proposed/committed manifest binding and restore review requirements.
- `design/ssh-config-evaluator.md`, `design/ssh-config-managed.md`: implemented evaluator/JNI contract and raw-result limits.
- `design/ssh-launcher.md`, `design/constraints.md`: catalog/validator/connector ordering and no current device/account authority.

## Caveats / Not Found

- This file is research/recommendation, not a frozen API or implementation claim. Main session must choose strict static closure versus explicit incomplete catalog and choose lexical-only versus full inactive syntax checking.
- No standalone production `SshContentValidator.java` exists; it is nested inside AppSecrets. Existing callback attachment is a seam, not evidence of a complete production validator.
- No existing native catalog API or parser observer for Host token vectors was found in current evaluator/hook headers. No build or runtime validation was performed for these recommendations.
- Code is shared and may change after this source snapshot. Citations refer to the inspected source state and pinned baseline; subsequent implementation should refresh affected line references.

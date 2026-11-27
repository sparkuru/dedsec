# SSH ProxyJump Declaration

## 1. Scope / Trigger

Native effective-config inspection needs the explicitly declared `ProxyJump` components in source order. This internal getter is a declaration, not a route or permission to connect. Direct/profile evaluation accepts only the pinned parser's exact disabling `none` argument and continues to reject actual jump declarations.

## 2. Signatures

```c
struct ctos_ssh_config_jump_hop {
    char host[CTOS_SSH_CONFIG_NAME_BYTES + 1];
    char user[CTOS_SSH_CONFIG_NAME_BYTES + 1];
    unsigned int user_specified, port;
};
struct ctos_ssh_config_jump_declaration {
    unsigned int count;
    struct ctos_ssh_config_jump_hop hops[CTOS_SSH_CONFIG_JUMPS];
};
int ctos_ssh_config_eval_jump_declaration(struct ctos_ssh_config_eval *,
    struct ctos_ssh_config_jump_declaration *);
```

## 3. Contracts

Call only on a READY non-direct effective evaluator and on its owning PID/UID/thread. The caller owns the copied result. At most eight hops appear from first to last; host and optional user fit the existing 255-byte UTF-8 limit. `user_specified == 0` means no explicit user. Port 0 means omitted, distinct from explicitly supplied 22. No hop `Host` block, nested jump, default username/port, key, trust or transport is resolved here.

The pinned parser stores the final hop in `jump_host`/`jump_user`/`jump_port` and its prefix in `jump_extra`. Reparse prefixes using the same bounded parser and reverse the results. When a prefix consists only of `none`, use an equivalent URI because this spelling denotes a hostname inside a multi-hop list but denotes the disabling directive when parsed alone. OpenSSH represents the disabling directive with `jump_host="none"`, `jump_port=0`, and no `jump_user` before normalization; `user@none`, `none:22`, `ssh://none`, and `other,none` name actual hosts. Include's `%j` expansion retains the pinned readconf `option_clear_or_none` behavior; final `User` expansion and the defensive direct-plan gate must distinguish the actual host.

The direct keyword hook receives the exact raw argument supplied to the pinned `parse_jump`. It allows an active directive only when that raw argument equals `none`, ignoring case; it must not decide from tokenized or dequoted arguments. In particular `none # comment` is a real host according to the pinned parser. Independently retain the final `Options` `jump_configured` check, which refuses any real jump even if the keyword hook is bypassed. Continue checking every active directive, including Include, repeated entries and Match passes.

Use the existing parser trylock, original owner, deadline, cancellation, view validity and parser diagnostics. Validate liveness around processing and publication. Do not extend the deadline, access host files, environment, network or commands, or publish a partial result. Zero non-null output before validation and again on late invalidation. Preserve the parent evaluation and diagnostic state; wipe the temporary output arrays and owned prefix copies. The upstream parser's own intermediate allocations use its existing cleanup rules.

## 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| No jump or disabling `ProxyJump none` | Success, entire declaration zero |
| Host named `none` within an actual jump | Preserve as a hop, including prefix position |
| Missing user or port | Empty user with flag 0; port 0 |
| Null output, wrong mode, direct/profile plan | Reject; zero non-null output |
| Wrong owner, cancelled, expired, revoked or closed | Reject; zero non-null output |
| Parser lock busy, invalid component or allocation failure | Reject; zero non-null output; leave original owner usable |
| More than eight declared hops | Existing evaluator rejects; never truncate |

## 5. Good / Base / Bad Cases

Good: `none,bob@[2001:db8::1]:22,carol@none` yields three ordered declarations. Base: `ProxyJump none` yields zero declarations. Bad: treat `jump_host` alone as the complete route, infer a user's identity or port, or permit a connection from this getter.

## 6. Tests Required

Run the real pinned parser with side-effect wrappers. Assert single/multiple/eight hops, order, URI and IPv6 forms, omitted versus explicit user/port, the `none` ambiguities, and actual User/Include expansion. Assert zero output for null/NEW/wrong mode/owner/closed/cancelled/expired/revoked and parser-lock contention; the latter must leave the READY owner usable. Run the managed direct and profile rejection suite and the existing evaluator regression under ARM64/QEMU; distinguish compilation, QEMU execution, Android build, device execution and real network interop. Test cancellation during parsing explicitly if future code introduces callbacks or concurrent publication.

## 7. Wrong vs Correct

Wrong: switch off the direct gate because an ordered list exists. Correct: first add bounded per-hop effective configuration over the same protected snapshot, precedence/cycle handling, and separately authorized per-hop transport, trust, authentication and cleanup, then prove those through end-to-end tests before changing product behavior.

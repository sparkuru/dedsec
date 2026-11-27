# SSH ProxyJump effective configuration chain

## 1. Scope / Trigger

The configuration inspector can resolve each declared or nested jump against the same protected CONFIG snapshot. A separate strict profile-route mode exports immutable per-hop connection metadata through native handles and JNI to Java. The product profile list evaluates the whole route and exposes only a direct connection or one jump; longer routes produce `SSH_JUMP_CHAIN_UNSUPPORTED`. The connector independently enforces the same pre-socket limit. Its initial jump path accepts already-known host keys and automatic authentication using either server `none` success or a separately confirmed saved password for each hop. New or changed keys, selected keys, keyboard-interactive and newly entered jump-path passwords remain unsupported. Method selection respects the effective per-hop authentication order; do not silently skip a preferred offered method. Route metadata alone is not authority to open a socket or release a secret.

## 2. Signatures

```c
int ctos_ssh_config_eval_open_next_jump(struct ctos_ssh_config_eval *parent,
    struct ctos_ssh_config_eval **child);
int ctos_ssh_config_route_build(struct ctos_ssh_config_eval *root,
    struct ctos_ssh_config_route **route, struct ctos_ssh_config_error *error);
int ctos_ssh_config_route_count(const struct ctos_ssh_config_route *, unsigned int *);
int ctos_ssh_config_route_summary(const struct ctos_ssh_config_route *, unsigned int,
    struct ctos_ssh_config_summary *);
int ctos_ssh_config_route_options(const struct ctos_ssh_config_route *, unsigned int,
    const Options **);
int ctos_ssh_config_route_close(struct ctos_ssh_config_route *);
int ctos_ssh_config_route_destroy(struct ctos_ssh_config_route *);
```

`Options` is the internal pinned readconf type; the typed getter is declared only when its header is already included. A successful next-hop open with a null child means no jump. A route's indexes run from the first jump to the last, and exclude the final target.

`SshConnectionMaterial.profileRoute(manifest, root, alias, user, deadline, guard)` uses the same protected detached source and returns `SshConfigEvaluator.ProfileRoute`; its entries run from first jump through the final target. The handle also supports a live-view route. The Java route is copied immutable metadata; the factory must bind it to the exact current CONFIG source, vault revision/epoch, owner and per-hop native roles before effects.

## 3. Contracts

Only a READY ordinary effective evaluator or the explicitly selected strict profile-route evaluator may be a parent; existing direct and profile-direct evaluators still reject jumps. Child evaluators retain the original parent, PID/UID/thread, absolute deadline, cancellation and live-view or detached source. They borrow the same source metadata and CONFIG bytes; they neither copy the snapshot nor create new lifetime or IO budgets. The parent cannot close while a child or route owns it. A child that cannot be closed remains reachable and blocks its parent's close until retry succeeds. Destroy only after close. Cancelling the root invalidates all descendants; cancelling one child does not implicitly cancel its ancestors.

The root's opens, bytes and line counters include all descendant attempts, including failed ones; reopening a child never resets them. Each child inherits the remaining original maxima before parsing and returns actual consumption to the root before the attempt finishes. The source's lifetime and the owner's cancellation are checked during parsing and around publication.

The immediate upstream jump is evaluated first. Its explicit user and port override its own Host configuration only when present; target user, target fallback, target tag and target command are not inherited. An explicit preceding jump list is applied before the child's Host configuration, whereas an absent prefix leaves that Host's `ProxyJump` eligible. A bare `none` inside an explicit list denotes a host; represent this single-item prefix as `ssh://none` to avoid a silent downgrade to the pinned command-line parser's disabling `-J none` interpretation. This is an intentional fail-safe distinction from that literal upstream command-line spelling.

Before parsing a jump, model the pinned `-W` configuration context: the immediately following hop's effective hostname and port become the stdio-forward destination, and `RequestTTY no` and session type `none` override config. The pinned defaulting code then applies its `-W` defaults for clearing ordinary forwards and exit-on-forward-failure, subject to explicit config. These fields describe raw configuration only; no channel or transport is created. Other raw `Options` fields still require connection-specific validation and expansion.

Strict profile-route mode validates each hop's actual `-W` context and the final target under the existing supported connection-policy rules, permits the declared `ProxyJump` itself and rejects unsupported execution or forwarding options. It rejects a requested remote command, unsupported endpoint or an invalid member as a whole; there is no partial plan. Its bounded outer frame contains one current v5 `ProfilePlan` frame per entry. Java rejects earlier plan versions in a route, malformed framing and unrepresentable hosts rather than silently dropping authentication order, keepalive or other required policy.

An exact ancestor tuple of case-insensitive alias, explicit user and explicit port is rejected with `ELOOP`; unspecified and explicit values remain distinct. This is a conservative syntactic check and is not proof that differently spelled aliases or resolved endpoints are loop-free. More than eight total jumps, including nested declarations, is rejected with `E2BIG`. Do not infer real reachability or complete endpoint cycle detection from this configuration result.

`route_build` publishes no hop information until all nested evaluations succeed. On ordinary failure it cleans the children and returns a null route. If cleanup itself cannot finish, it returns failure and a non-null **cleanup-only** route, preserves the original error, and pins the root; getters reject this owner. Retry `route_close` until successful, then destroy it. An already successful route retains all child Options and pins the root until closed; borrowed Options pointers cannot outlive that route or be used after cancellation or source invalidation.

## 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| No jump / `ProxyJump none` | Completed route with zero jumps; root stays pinned until route close |
| Explicit multi-hop prefix and per-hop `ProxyJump` | Prefix wins; without it the child's configured jump is evaluated |
| Same alias with different explicit user or port | Do not reject solely for the repeated alias |
| Repeated ancestor tuple / ninth nested hop | Fail with `ELOOP` / `E2BIG`, no usable partial route |
| Original budget exhausted during any child | Fail the entire build; retain actual cumulative usage |
| Cancel, expiry, revoked source, wrong owner or unsupported directive | Fail without a usable route; do not reopen or extend the source lifetime |
| Cleanup fails on a child | Return a cleanup-only owner; keep all dependent parents alive for retry |
| Closed route, invalid index or null output | Reject and clear non-null getter output |

## 5. Good / Base / Bad Cases

Good: `ProxyJump alice@one:2201,two` uses `two`'s configured user and port, ignores `two`'s own jump in favor of `one`, then applies Alice and port 2201 to `one`. Base: a target without a jump returns an empty route. Bad: expose the first evaluated hop after a later nested hop fails, or reload the config with a fresh timeout or budget.

## 6. Tests Required

Exercise the pinned parser under ARM64/QEMU with side-effect observers. Check per-hop effective user, port, hostname, IPv6, URI, `none`, nested precedence, `-W` defaults, explicit overrides, eight-jump limit and exact tuple cycle; include the same alias at distinct explicit ports. Independently exhaust aggregate opens, bytes and lines, and prove retries cannot renew them. Test detached snapshot independence, cancellation, timeout, revocation, wrong owner, reentry, incomplete child cleanup, cleanup-only routes and root pinning, including a real 64 KiB memlock subprocess. Run the existing managed direct/profile rejection and native evaluator regressions. Android compilation, JNI runtime, device behavior and actual jump-server interop are separate evidence.

## 7. Wrong vs Correct

Wrong: consider a resolved Options object a connection grant or discard a cleanup owner when a later hop fails. Correct: retain the same protected owner, deny partial routes, finish retryable cleanup, and separately validate every transport, host trust, authentication and session effect. Do not generalize the restricted single-jump path to multiple jumps or unsupported authentication and trust changes.

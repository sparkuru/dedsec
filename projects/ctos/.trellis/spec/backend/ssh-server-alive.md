# SSH server-alive configuration and transport

## 1. Scope / Trigger

`ServerAliveInterval` and `ServerAliveCountMax` are consumed from the actual selected
OpenSSH config for password, private-key and standalone keyboard-interactive connections.
They extend the protected direct plan and the existing established SSH owner; they do
not implement TCPKeepAlive, ProxyJump, forwarding, automatic authentication discovery,
or a promise that Android will run while its UID is frozen. Device evidence is separate.

## 2. Signatures

```text
ctos_ssh_native_client_configure_server_alive(client, uint interval_seconds, uint count_max) -> 0 | -1
SshNativeSession.configureServerAlive(int interval, int countMax) -> void
CSCPv4 / CSPPv4 -> effective interval and count, alongside existing direct-plan fields
```

The setter is owner-bound, once per authenticated pre-shell session. It does not release
credentials or start a socket. JNI exposes only nonnegative signed-int values; native also
rejects values greater than `INT_MAX`.

## 3. Contracts

The pinned Options parser owns Host/Include precedence, time units and defaults: interval
0, count 3. The v4 direct/profile header preserves v3's KBI and chain flags and inserts
two big-endian u32 values, interval seconds then count, before connect timeout. v1–v3
decode to 0/3; v4 rejects negative signed values, truncated or trailing frames. The
configured direct plan comes from the original current vault/config binding and is compared
across preparation passes. A new connection reevaluates config; no per-session policy is
persisted in reconnect hints. Manual and effective interval-zero connections send no probe.

After actual authentication SUCCESS and before shell promotion, the Java owner configures
the same native session when interval is nonzero. The normal bounded shell pump sends an
encrypted `keepalive@openssh.com` global request with want-reply set after the configured
interval without server input. Incoming socket data restarts the interval. Each matching
REQUEST_SUCCESS or REQUEST_FAILURE consumes one pending reply and clears the consecutive
unanswered count. A success payload is bounded and discarded; failure must have no payload.
If the next interval expires when the unanswered count already equals countMax, terminate
with `ETIMEDOUT`; countMax 0 therefore times out at the first idle interval. The current
public established-session failure path reports `SSH_SESSION_STEP`. A configured policy
cannot bypass existing host/KEX/auth proof, cancellation, lock, Stop or close ownership.

No other client-originated reply-requiring global request currently exists. A reply without
an outstanding probe is a protocol error; future forwarding must introduce one ordered,
shared global-request confirmation queue and must not attribute unrelated replies to
keepalive. Requests and replies do not become terminal output or credential material.

## 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Missing option, old v1–v3 plan, or interval 0 | No probe; count defaults to 3 when omitted. |
| Valid Host/Include options | Actual first effective values reach the original session. |
| Invalid syntax, negative/overflow config or wire | Reject without silently changing the value. |
| Snapshot changes the policy between protected passes | `VAULT_CHANGED`; do not connect with a mixed plan. |
| Setter before authentication, duplicate, wrong owner, or after shell | Refuse without granting authentication or another setter. |
| Matching success/failure reply | Consume one reply and reset missed count, retain session. |
| No reply beyond configured count | `ETIMEDOUT`, terminal failure and existing cleanup. |
| Unsolicited/malformed reply | `EPROTO`, terminal failure and existing cleanup. |
| Cancel/Stop/lock | Existing cancellation and cleanup take precedence; no later probe. |

## 5. Good / Base / Bad Cases

Good: actual config says interval 15/count 3; the selected session sends a genuine
encrypted request only after 15 seconds without server input and accepts a real reply.
Base: manual and old plans stay disabled and existing KBI/MFA policies remain unchanged.
Bad: merely accept and ignore the config option, use TCP keepalive as a substitute,
reconstruct config in Flutter, or classify any unsolicited reply as a successful probe.

## 6. Tests Required

Exercise real pinned config evaluation and direct/profile v4 encoding with defaults,
zero, bounds, bad syntax, Host/Include precedence, and old frame compatibility. Test
policy freezing/mismatch, password/key/KBI auth gates, owner/lifecycle and JNI mapping.
Run an encrypted synthetic peer through both global reply types, delayed replies,
unsolicited replies, input-based postponement, count boundaries and cancellation;
retain broader native/JVM/Flutter regressions. JNI-on-Android, actual application
integration and user/device behavior require their own current evidence.

## 7. Wrong vs Correct

Wrong: accept `ServerAliveInterval` in the config gate without making the established
session send and account for the actual protocol request.

Correct: bind the parsed value to the current owner, use the encrypted session pump,
match ordered replies and close through the existing lifecycle on timeout.

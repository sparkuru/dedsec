# SSH ProxyJump native channel

## 1. Scope / Trigger

The internal native client can open one `direct-tcpip` channel after a jump host's own SSH authentication succeeds. It starts under the original lifetime; a separate primitive can retain an established descendant chain. Internal Java/JNI exposes begin and status, but no product connector or Flutter entry consumes them. Neither is a product ProxyJump connection or a general local/remote forwarding API.

## 2. Signatures

```c
int ctos_ssh_native_client_begin_jump(struct ctos_ssh_native_client *, ctos_ssh_role_token next_role);
int ctos_ssh_native_client_jump_status(const struct ctos_ssh_native_client *,
    struct ctos_ssh_native_jump_status *);
int ctos_ssh_native_client_jump_write(struct ctos_ssh_native_client *, const unsigned char *, size_t, size_t *);
int ctos_ssh_native_client_jump_read(struct ctos_ssh_native_client *, unsigned char *, size_t, size_t *);
int ctos_ssh_native_client_jump_eof(struct ctos_ssh_native_client *);
```

Status reports `OPENING`, `OPEN`, `CLOSED` or `REJECTED`, local/remote EOF and bounded buffered bytes. The destination comes from `next_role.host` and `.port`; the API does not take an arbitrary host or command.

## 3. Contracts

The current client must have the same owner thread/PID/UID, live original callback/view/deadline, completed host-key proof and actual `USERAUTH_SUCCESS`; rejected, partial, challenge and unstarted authentication grant no channel. Its role must be `JUMP`. The next live role must have the same owner PID/UID, connection ID, identity version, chain identity, connection binding and absolute deadline, with exactly the next hop index. In v1 the chain identity is the target identity, so the targets must still match. In v2 each role has the same explicit chain identity but its own credential target identity, allowing distinct accounts. Reject mixed v1/v2 roles even if the chain strings match. A common chain identity does not itself validate a route or grant access to any credential: the future connector must bind it to the frozen configuration and issue each target role through the native owner. Freeze full role metadata and recheck it together with the original authentication proof before stepping or publishing channel data. Closing or expiring either role, revoking the original view or cancelling the callback fences an unpromoted channel. `CLIENT` roles alone may start the final session shell; a `JUMP` role cannot start it.

Build a single client channel zero with the real pinned packet API: `direct-tcpip`, initial receive window 65536, max packet 32768, frozen next-role host and port, and synthetic origin `127.0.0.1:65535` matching the pinned client's unknown-origin convention. A real `CHANNEL_OPEN_CONFIRMATION` is required before reads or writes. An `OPEN_FAILURE` remains a rejection; it never triggers a direct connection. Do not create a PTY, shell request, local listener or endpoint socket in this API.

Channel traffic shares the existing nonblocking encrypted packet pump, bounded output and window accounting. Normal data is an opaque byte stream; `EXTENDED_DATA`, wrong recipients, out-of-window data, invalid window adjustment and data after EOF are protocol failures. Unknown channel requests are rejected if they request a reply. Backpressure returns zero accepted bytes without silently dropping data. Output buffers and returned lengths are cleared on failed reads, and terminal failure wipes pending jump bytes. Local EOF is one-shot; remote EOF and close can be drained using the same bounded rules. Existing owner and callback cleanup remain retryable.

Channel opening does not promote the jump: original authentication/view deadlines remain in force until the explicitly guarded [retained-transport promotion](ssh-proxyjump-retained-transport.md). The next role's existence and channel confirmation are not proof of that peer's host key or authentication. The caller must separately complete the next client's own KEX, trust and authentication.

The configuration limit of eight jumps excludes the final destination. Native bootstrap, KEX and callback tables and the Java role registry therefore allow at most nine simultaneous role positions, indexed 0 through 8. Their uniqueness, owner, deadline, nonce, connection and host-proof checks remain unchanged; a tenth role/index is refused. Role host validation currently remains the existing ASCII/253-byte policy, which is narrower than the raw configuration inspector and must be accounted for when integrating a route.

## 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| No authentication, `PARTIAL`, rejection, non-JUMP source | Reject before a channel packet or any credential retry |
| Wrong next hop, connection, identity version, chain identity, binding or deadline | Reject without opening a channel; leave the original authenticated owner usable. V1 additionally requires identical targets; v2 permits distinct per-hop targets |
| Next role closed, pre-promotion view revoked or cancellation during channel lifetime | Deny subsequent I/O and publication; clear pending output and retain cleanup owner |
| Peer rejects channel open | `REJECTED`; no downstream stream or direct fallback |
| Packet before confirmation, extended data, wrong recipient, invalid window, data after EOF | Fail the current native session without output publication |
| Channel window full / output queue full | Return zero accepted bytes; caller drives bounded steps and retries |
| Attempt shell operations on a jump channel | Reject and clear non-null output; never send PTY or shell requests |
| Eight jumps and final target | Nine distinct role/callback/KEX positions may coexist; preserve exact per-role binding |

## 5. Good / Base / Bad Cases

Good: a JUMP role authenticates, opens a channel to its exact live next role, and a separately verified next SSH client exchanges encrypted handshake bytes through it. Base: a rejected channel leaves no downstream authority. Bad: use a user-provided hostname as a generic forward destination, treat channel open as next-hop trust, or continue reading after the bound next role is revoked.

## 6. Tests Required

Use the actual pinned packet engine and an encrypted synthetic peer to cover SUCCESS-only admission for password, private key, KBI, password-PARTIAL-to-KBI and server `none` success, role type and exact next-role tuple, rejected and malformed opens, wrong owner, read clearing, window limits, ordered bytes, EOF/close, revocation/cancellation and retryable callback close. Cover nine simultaneous native/Java role positions and KEX/callback slots plus index 9 rejection. A two-layer socketpair fixture must verify independent host-key/KEX proof, target authentication and target PTY shell bytes over the jump channel; label its relay as test-owned. Re-run existing shell, auth and full native-client regressions under ARM64/QEMU, normal JVM tests, Android lint and APK build. Real JNI execution, physical devices and server interop require separate evidence.

## 7. Wrong vs Correct

Wrong: expose this primitive as a completed product ProxyJump feature or let a child keep using queued bytes after an upstream owner is cancelled. Correct: the [bounded byte relay](ssh-proxyjump-relay.md), [child constructor](ssh-proxyjump-child.md) and retained promotion add native proofs and ownership; each product path also needs its own callback effects and whole-chain lifecycle, and background ownership remains separate.

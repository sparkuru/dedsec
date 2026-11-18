# SSH RequestTTY configuration and session channel

## Scope and boundary

The pinned OpenSSH parser owns the actual `RequestTTY` value and Host/Include
precedence. Its values are auto=0, no/false=1, yes/true=2 and force=3. Direct and
profile plans carry the existing field in CSCP/CSPP versions 1–4; reject all
other values without changing the wire format. Continue rejecting unconsumed
configuration, including ProxyJump, RemoteCommand, forwarding and HostKeyAlias.

The connector must freeze the exact effective value across every protected config
snapshot, for password, key and standalone keyboard-interactive paths. A changed
value fails the connection. Manual connections and values auto/yes/force retain
the established application PTY behavior; no/false opt out. Reconnection must
evaluate the current configuration, not reuse an old policy from a session hint.

## Ownership and protocol

After full authentication SUCCESS, the original Java owner can call
`SshNativeSession.configureNoPty()` once on the same native client before shell
promotion; JNI calls `ctos_ssh_native_client_configure_no_pty(client)`. The setter
requires live callback/authentication/selection proof, refuses duplicates and
later promotion, and must not release or acquire credentials. All other sessions
default to PTY. A setter error or cancellation prevents session promotion.

On session channel confirmation, no-PTY requests `shell` directly, with reply;
all other sessions request and confirm `pty-req` before `shell` as before. Real
shell SUCCESS still gates input and publishing buffered early output; rejection,
early close and cancellation preserve the existing bounded buffer and cleanup
rules. A valid resize on a running no-PTY channel succeeds as a no-op and sends
no window-change; invalid dimensions and non-running channels still fail. The
remote may not echo input, display a prompt, process control bytes as terminal
signals, or provide terminal dimensions without a PTY.

## Verification

Test pinned real-Options parsing, supported aliases, invalid values, Host/Include
first-value precedence and both plan kinds; strict legacy/new wire decoding and
snapshot mismatch; authenticated owner, error/cancel and KBI gate; and encrypted
synthetic peers for shell request order, success, rejection, early output, close,
ordinary bytes, EOF and no resize request. Preserve PTY and keepalive regressions.
Compilation, QEMU synthetic execution, Android JNI/device and real server behavior
are separate evidence; never label one as another.

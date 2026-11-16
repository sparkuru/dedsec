# SSH channel output before shell confirmation

## 1. Scope / Trigger

An authenticated server can send session-channel output after channel open confirmation
while a PTY or shell request is still awaiting its reply. The client must handle this
ordering without treating output as proof that the requested shell started. This contract
does not change host-key, authentication, KEX, owner, lock, or cancellation authority.

## 2. Signatures

```text
ctos_ssh_native_client_shell_status(client, status) -> phase, buffered_bytes
ctos_ssh_native_client_shell_read(client, bytes, capacity, length) -> 0 | -1 / errno
```

The existing session owner and packet pump receive channel data. There is no new public
wire format, callback, credential source, or session capability.

## 3. Contracts

After the session channel is confirmed, normal data and supported stderr extended data
may be accepted during `PTY_PENDING`, `SHELL_PENDING`, and `RUNNING`. Apply the same
packet, window, and bounded buffer limits in each state and preserve arrival order.
Before the actual shell request succeeds, buffer bytes only: `shell_read` must fail
with `EACCES`, zero its returned length and caller buffer, and publish nothing to the
terminal. Do not treat PTY success or server data as shell success.

On real shell success, the owner may read the buffered bytes once, in order, using the
existing window-credit accounting. On request failure, peer channel close, or terminal
error before shell success, wipe and discard pending output. After a started shell closes,
the existing bounded drain remains available. Invalid packets, unsupported extended-data
types, window overrun, data after EOF, and existing proof or owner failures remain errors.

RFC 4254 sections 5.2 and 5.4 define channel flow control and channel requests/replies;
allowing this ordering is a compatibility interpretation of those rules, not a claim
that the RFC requires a server to send early output.

## 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Data before PTY reply or shell reply | Bounded, ordered internal buffer; no terminal publication. |
| Read before actual shell success | `EACCES`, zero length and cleared output buffer. |
| Shell success after early data | Deliver exact buffered bytes once, in arrival order. |
| PTY/shell rejection or early channel close | Wipe and discard early bytes; no output publication. |
| Malformed, over-window, after-EOF or unsupported data | Fail through the existing protocol and lifecycle gates. |
| Cancel, lock, owner/proof failure | Preserve existing cancellation and cleanup precedence. |

## 5. Good / Base / Bad Cases

Good: stderr and stdout precede a real success; the terminal receives them only after it.
Base: a server sends data after shell success and the existing running/close drain works.
Bad: disconnect solely because data preceded the request reply, or publish it before
the shell actually succeeds.

## 6. Tests Required

Use an encrypted synthetic peer to send normal and extended data before both PTY and shell
replies. Cover success, explicit rejection, and peer close at both stages; prove pre-success
read denial and output clearing, exact ordered successful delivery, and discarded failure
buffers. Retain shell backpressure, malformed packets, existing authentication/proof,
session lifecycle and server-alive regression suites. Android/JNI and real servers need
separate evidence; a synthetic protocol pass is not a device acceptance result.

## 7. Wrong vs Correct

Wrong: infer shell readiness from data, or require the peer to delay channel data until
after a reply despite an open channel.

Correct: accept bounded channel data once the channel opens, independently require actual
shell success before publication, and clear unconfirmed output on every failure path.

# SSH ProxyJump native byte relay

## 1. Scope / Trigger

The native client's authenticated `direct-tcpip` channel can own a bounded relay to an already-connected local Unix stream. This relay alone is a transport primitive, not a child SSH connection, route orchestrator, trust/credential grant, background session or product ProxyJump feature. The separate [child constructor and internal JNI owner](ssh-proxyjump-child.md) create an exactly bound socketpair and provide recursive native checks; no product connector or Flutter caller uses the new entry points.

## 2. Signatures

```c
int ctos_ssh_native_jump_relay_open(struct ctos_ssh_native_client *, int fd,
    struct ctos_ssh_native_jump_relay **);
int ctos_ssh_native_jump_relay_pump(struct ctos_ssh_native_jump_relay *);
int ctos_ssh_native_jump_relay_close(struct ctos_ssh_native_jump_relay *);
int ctos_ssh_native_jump_relay_destroy(struct ctos_ssh_native_jump_relay *);
```

`fd` is a connected, `CLOEXEC`, nonblocking, read/write AF_UNIX stream. It is caller-owned on failure and relay-owned after a successful open. The relay never creates a listener, resolves a host or opens a remote socket. The downstream peer remains externally owned. `pump` does one bounded nonblocking transfer turn; the caller separately steps the authenticated parent SSH packet engine.

## 3. Contracts

Open only on the exact live, authenticated JUMP channel after `OPEN_CONFIRMATION`, while both roles and the original view, callback and deadline remain valid and neither end has ended. Reject the parent's own transport descriptor, including duplicates of the same socket. One relay may ever consume a jump channel: direct jump reads, writes and EOF remain blocked even after relay close. Parent close is blocked while a relay exists; close and destroy the relay first. Release is bound to its opening thread, PID and UID and remains possible after callback cancellation or view revocation.

Each direction retains at most 32768 bytes and respects the existing channel window, maximum packet and output limits. Zero-progress writes and nonblocking socket backpressure retain bytes in order. Local socket EOF sends one channel EOF after pending outgoing bytes. Remote EOF or channel close drains accepted incoming bytes before shutting down the socket write side. On remote close, an unsent buffered or already queued local message fails with `EPIPE`; otherwise the relay shuts down its socket read side so later peer writes fail. A terminal failure wipes both relay buffers, refuses later pumps and retains the owner for close. The owner gives up its descriptor on close; on Linux a reported close error still leaves a closed tombstone for destruction.

Recheck parent proof around relay transfers. Bytes already accepted into the kernel peer socket cannot be revoked by a later upstream failure; therefore do not wire an ordinary child client into the product using this relay alone. The separate child constructor checks native ancestor proofs and retains cleanup owners. Optional [retained promotion](ssh-proxyjump-retained-transport.md) checks the established descendant chain; Java callback effects, cancellation and background ownership still need end-to-end guards and lifecycle ownership. The syntactic route loop check is not actual endpoint-cycle detection.

## 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| No authentication or channel confirmation, wrong endpoint type or parent transport fd | Reject; leave fd and output owner untouched / null |
| Relay exists, wrong thread or repeated attachment | Preserve owners; reject close/pump, replacement and direct byte operations |
| Both socket and channel windows block | Retain bounded bytes and retry without loss or reordering |
| Peer EOF or remote EOF/close | Forward EOF only after pending data; fail unsent data on remote close |
| Role close, cancelled callback, or pre-promotion expired deadline/revoked view | Deny later pumps and clear relay-owned pending data; cleanup remains available |
| Parent close or callback release fails | Relay pins parent until relay close; existing callback release remains retryable |

## 5. Good / Base / Bad Cases

Good: an owner pumps a local stream through its role-bound channel and independently drives both packet engines. Base: backpressure accepts no more bytes until the receiver drains them. Bad: treat relay opening, buffered socket data or an adjacent role as proof that a child SSH host or credential is authorized.

## 6. Tests Required

Use actual encrypted synthetic SSH peers, the production relay and a test-owned downstream server forwarder. Exercise bounded traffic in both directions, channel and Unix-socket backpressure, pending-buffer cancellation, local and remote EOF, remote close with and without unsent data, descriptor rejection and ownership, wrong-thread attempts and callback-close retry. Separately test parent/next-role revocation and the [child constructor's](ssh-proxyjump-child.md) recursive proof and ownership. Run native client regression, Android build/lint and JVM checks; real JNI, Android execution, server interoperability and user experience are separate.

## 7. Wrong vs Correct

Wrong: use local relay success alone to claim ProxyJump support or let a child trust already queued bytes after an upstream cancellation. Correct: enable only routes for which the child and every ancestor share guarded proof, cancellation, owned cleanup and the established-session lifecycle.

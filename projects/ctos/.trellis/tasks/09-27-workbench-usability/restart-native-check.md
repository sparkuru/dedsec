# HFTP native immediate-restart regression

2026-09-27. Scope: `hftp_relay.c`, temporary host fixtures, this check record.
No device, Root, VPN, interface, routing, firewall or SELinux changes performed.

## Evidence and scope boundary

Main observed `Root relay startup failed: listener_bind (errno 98)` on TARGET-PHONE.
The user subsequently clarified that the error appeared upon tapping Stop,
without another manual Start. The native defect below reproduces **a subsequent
immediate Start failing**; it does not independently explain a startup failure
surfacing during Stop. Java/session/state investigation remains separate.

The original relay did not set `SO_REUSEADDR`. A real established TCP transfer
with server-initiated graceful close leaves the accepted LAN endpoint in
TIME_WAIT after the helper exits. Binding the same address/port immediately
then fails with EADDRINUSE even though no helper process remains.

Official mechanism: [Linux socket(7)](https://man7.org/linux/man-pages/man7/socket.7.html),
SO_REUSEADDR and Notes. Address reuse remains incompatible with an active
listener, and Linux requires reuse enabled on both previous and new sockets.
SO_REUSEPORT has different shared-listener semantics and is not used.

## Minimal change

After creating the listener, before Android network binding or TCP bind, enable
`setsockopt(SOL_SOCKET, SO_REUSEADDR, 1)`. Check failure, close the newly owned
socket, preserve errno, emit fixed structured `listener_reuse` startup error.
No changes to peer policy, Root guard, target, process/control lifetime or Java.

This supports repeated starts between corrected versions. TIME_WAIT from the
old non-reuse listener can still prevent immediate startup of the new binary;
it must expire naturally. The implementation never kills another port owner,
edits kernel state, selects another exposed port or silently falls back.

## Reproduction before editing

Saved exact original source to temporary
`${EVIDENCE_DIR}/ctos-hftp-relay-test-20260927/hftp_relay-before-reuse.c` and compiled it as
`${EVIDENCE_DIR}/ctos-hftp-relay-before-reuse` with explicit host-local test mode.

The test starts a loopback backend and LAN-side fixture listener on the existing
host virtual interface `192.0.2.1`. It transfers 1,376,257 public upload bytes,
verifies returned SHA256 plus 122,880 public download bytes, reads server EOF,
gracefully closes the client, sends STOP and reaps the helper. A read-only exact
fixture endpoint lookup in `/proc/net/tcp` confirms state `06` (TIME_WAIT).
The immediately launched second original helper exits 1 with exact JSON:

```json
{"state":"failed","reason":"listener_bind","errno":98}
```

This single pre-edit reproduction passed in 0.011s. No host interfaces or
system settings were created or modified.

## Verification after editing

- Host compiler and project NDK 27.0.12077973 API28 compiler passed
  `-O2 -Wall -Wextra -Werror`. Android build uses PIE, `-landroid` and
  `-Wl,-z,max-page-size=16384` as before.
- **Five focused tests passed in 0.229s**:
  1. Original binary reproduces TIME_WAIT + EADDRINUSE as above.
  2. Corrected binary performs **20 immediate same-address/port restarts**;
     each cycle completes exact upload/download, client graceful close,
     helper STOP/reap and observes remaining TIME_WAIT before the next cycle.
  3. Starting a second corrected helper while the first still listens returns
     EADDRINUSE; the first remains alive and transfers successfully afterward.
  4. Corrected binary cannot reuse legacy non-reuse TIME_WAIT. It explicitly
     returns EADDRINUSE, confirming the upgrade limitation without mutation.
  5. Temporary compile-time syscall injection makes SO_REUSEADDR return
     ENOPROTOOPT. The helper returns `listener_reuse` / errno92, exits 1, and
     another ordinary socket can bind/listen on the same fixture endpoint.
- **All 15 existing streaming/control/subnet/concurrency regressions passed**
  in 29.165s after the correction.
- Scoped `git diff --check` passed.

Replays: `${EVIDENCE_DIR}/ctos-hftp-relay-test-20260927/test_restart.py`,
`restart-before.log`, `restart-after.log`, `regression-after-reuse.log`,
`reuse_failure.c`. The syscall injection exists only in this temporary fixture;
production source has no injection option. Tests require scoped sandbox network
execution permission and prove local TCP behavior, not Android VPN binding.
All fixture helpers/backends/sockets are closed by owned-object cleanup.

Final native source SHA256:
`0153f3ad5b0f873b606a34a50e9032de7cd6f9a5df8499fa9699f8306bc2a30b`.
Temporary Android ELF SHA256:
`23fe22d71fa4109883502fdccd4c10ef38994fa83e7d479ccb7ad030cccbc730`.

## Remaining acceptance

Main coordinates Java Stop/session error provenance review, Android quality
checks/package build and TARGET-PHONE repeated upload/download/Stop/Start under the
unchanged VPN conditions. This record does not claim the user's Stop-time
symptom fully resolved or substitute host tests for current-device acceptance.

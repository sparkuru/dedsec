# HFTP Root relay native implementation check

Date: 2026-09-27. This record covers native source and local fixtures only; no
phone, `su`, VPN, firewall, route, interface or SELinux operation was performed
by this implementer. Device checks belong to the main session.

## Changed files and interface

- `android/app/src/main/cpp/hftp_relay.c`: single-threaded, bounded TCP byte relay.
- `android/app/build.gradle`: `compileHftpRelay` generates extracted executable
  `generated/jniLibs/arm64-v8a/libctos_hftp_relay.so`, following the existing
  Python executable packaging pattern. `preBuild` depends on it.

Arguments: `helper WiFiIPv4 prefixLength LANport loopbackBackendPort
NetworkHandle [ownerAppPID]`. Prefix is 1–30, both ports 1024–65535, the public
Android network handle is a nonzero decimal uint64, optional owner PID is
2–INT_MAX. The Java adapter always provides its own PID. Wildcard, loopback,
multicast, network and broadcast listener addresses are rejected. Accepted
clients must be unicast IPv4 in the chosen subnet; loopback, network,
broadcast and outside-subnet clients are rejected. The listener address can
equal a local peer address for diagnostics; it is still subnet constrained.

Android requires effective UID0 and binds the listener using public
`android_setsocknetwork`. Accepted sockets inherit the listener's socket
configuration. The sole upstream is `127.0.0.1:<backendPort>`; this socket is
intentionally not bound to physical Wi-Fi because its destination is loopback.
There is no raw fwmark, HTTP parser, file API, remote control protocol, shell
execution, arbitrary upstream or daemonization. Python and SAF remain App-owned.
Wi-Fi selection/loss checks are the Java adapter's responsibility.

Readiness is emitted only after successful backend TCP probe, Android binding,
listener bind and listen:

```json
{"state":"ready","host":"PHONE-LAN-IP","port":7888,"backendPort":12345,"pid":1234,"uid":0}
```

Startup failures emit a bounded first stdout line
`{"state":"failed","reason":"<fixed-class>","errno":N}` and fixed-class
stderr. Later events are newline-delimited
`{"event":"relay","kind":"accepted|closed|rejected|error|stopped",
"client":"<IPv4-or-empty>","reason":"<fixed-class-or-empty>","errno":N}`.
No user path, HTTP body/header/query or shell string enters native logs. Records
fit PIPE_BUF; after ready, nonblocking output drops records when the consumer
is full rather than blocking transport/control. Ready must be emitted completely
within one second or startup fails.

Android non-Root execution fails with `root_required` / EPERM before opening any
socket. The Java adapter must additionally validate READY `uid==0`. Explicit
host-local test mode reports its actual UID and does not make a Root claim.

Only stdin `PING\n` and `STOP\n` are accepted. Partial messages are bounded to
seven bytes; unknown/overlong messages stop the service. EOF, ten seconds without
a complete PING, owner PID disappearance, SIGTERM/SIGINT, five-hour session cap
and explicit STOP close listener plus all owned sockets. Four connections at
most, 64KiB per direction per connection, 15-second activity idle timeout.
Short writes are retained; EOF propagates SHUT_WR only after buffered bytes
drain. Poll disables completed idle directions to avoid HUP spinning. Network
bytes are never interpreted as control commands.

## Checks actually run

1. Host `cc -DCTOS_RELAY_LOCAL_TEST -O2 -Wall -Wextra -Werror` succeeded.
   Android binding cannot be compiled out of a production non-Android build
   without the explicit local-test macro; the test binary emits
   `local_test_no_android_binding`. Android builds always use the real API.
2. Existing project NDK 27.0.12077973 compiler
   `aarch64-linux-android28-clang -fPIE -pie -O2 -Wall -Wextra -Werror
   -Wl,-z,max-page-size=16384 ... -landroid` succeeded after the final Android
   effective-UID check. ELF is AArch64 PIE, interpreter `/system/bin/linker64`,
   LOAD segments aligned to `0x4000`, and depends on `libandroid.so`.
3. Temporary C assertions passed numeric uint64 overflow/shell-shaped values,
   subnet/unicast/broadcast/loopback exclusions and forced partial sends via
   small socket buffers.
4. Temporary Python socket integration suite: **15 tests passed in 29.194s**.
   Tests: invalid args; backend-not-ready; ~4.5MiB exact bidirectional stream and
   client half-close; server half-close/slow reader; loopback/different subnet
   rejection; fifth-client rejection; control EOF closing active clients and
   listener; STOP/SIGTERM/SIGINT; split PING/invalid message; ten-second heartbeat;
   15-second client idle despite PING; saturated stdout consumer; owner PID loss;
   same port on distinct Wi-Fi/loopback addresses; overlong control message.
5. After final READY `uid` addition, host and NDK strict recompilation succeeded,
   and three targeted stream/same-port/backend-failure tests passed in 0.207s.

Host integration uses **pre-existing** virtual interfaces `TEST-LAN-INTERFACE-A=192.0.2.1/24`
and `TEST-LAN-INTERFACE-B=198.51.100.1/24`, read-only confirmed using `ip -4`. It creates only
fixture sockets/processes and public synthetic bytes. No interfaces or routes
are added/changed. It does not prove Android network binding, privileged socket
policy, SELinux execution, unchanged-VPN LAN access or Windows browser access.

Replay artifacts remain in `${EVIDENCE_DIR}/ctos-hftp-relay-test-20260927/`:
`boundaries.c`, `boundaries`, `test_relay.py`, `test.log`, `final-ready-test.log`. Host executable is
`${EVIDENCE_DIR}/ctos-hftp-relay-local-test`; NDK executable is
`${EVIDENCE_DIR}/libctos_hftp_relay.so`. Socket tests require the scoped sandbox escalation
because the default sandbox has only a loopback network namespace and limits
socket options. All owned fixture processes/sockets exited.

Final native source SHA256:
`50e81375901786f0ccf4249acea464484c53b914206edc9c0d36a8cd38be32c7`.
Temporary NDK ELF SHA256:
`d368d45b3f856387b61de331dd2fdcc4e5fd6f58218cb05068d585cdbd393d00`.

## Remaining verification

Java/Flutter integration and Gradle lint/test-APK checks are coordinated by the
other implementers/main session. A current packaged APK must confirm helper
extraction/execution and exact stop/reap. TARGET-PHONE Root listener binding, selected
Wi-Fi network, same-subnet LAN upload/download and unchanged VPN/Clash/lockdown
remain device acceptance requirements. Session cap is source-reviewed; a real
five-hour execution was not run. No full Windows client result is claimed.

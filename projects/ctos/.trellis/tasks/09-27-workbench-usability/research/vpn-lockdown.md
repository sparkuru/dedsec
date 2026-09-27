# Research: HFTP LAN access under Android VPN lockdown

- Query: Can ctOS serve Windows WINDOWS-LAN-IP at TARGET-PHONE PHONE-LAN-IP:7888 while Clash remains active, allowBypass=false, and Android blocks non-VPN traffic? Can wildcard listeners, binding APIs or a scoped Root relay satisfy this?
- Scope: mixed; source inspection and official Android/AOSP references only. No device operations, Root commands or system changes performed by this researcher.
- Date: 2026-09-27

## Findings

### Conclusion and evidence boundary

An ordinary App cannot use an Android-supported API to exempt its LAN HTTP socket from the stated policy. Listening on every IPv4 address is already implemented; changing the listener address does not change UID network policy. The user wants the settings unchanged. A privileged, explicitly authorized transport relay is a possible separate design, not an already validated fix. It preserves the settings but deliberately makes this one transport exception to the effective non-VPN policy.

The parent reports actual read-only dumpsys observations: allowBypass=false, tun0, and ctOS UID included in the VPN UID range; phone self-LAN and ADB forwarding succeeded, external LAN timed out. These observations support the VPN-isolation diagnosis. This researcher did not inspect the device's BPF maps or capture SYN packets, so it cannot assert the exact OEM drop point from evidence alone.

### Existing code and patterns

| File / location | Finding |
| --- | --- |
| `android/app/src/main/java/im/majo/ctos/HftpConfig.java:37` | Default host is already `0.0.0.0`, port 7888. |
| `android/app/src/main/python/ctos_tools/hftp.py:20` | FileServer subclasses ThreadingHTTPServer; ordinary App sockets and bounded request workers. |
| `android/app/src/main/python/ctos_tools/hftp.py:219` | Serve accepts loopback or IPv4 wildcard and constructs FileServer with that address. |
| `android/app/src/main/java/im/majo/ctos/HftpService.java:100` | Java starts bundled Python through an ordinary App ProcessBuilder; no `su`. |
| `android/app/src/main/java/im/majo/ctos/HftpService.java:118` | Running status derives from Python readiness, not proof of external reachability. |
| `android/app/src/main/java/im/majo/ctos/HftpService.java:135` | URL enumeration excludes VPN interfaces; displayed Wi-Fi URL is not a reachability guarantee. |
| `android/app/src/main/java/im/majo/ctos/RootSession.java:14` | Existing Root shell is explicitly authorized for fixed collection, with separate timeout/lifecycle. It is not a privileged HFTP transport. |
| `android/app/build.gradle:12` | minSdk28 / targetSdk36; bind APIs are available, but availability grants no bypass privilege. |

### Official ordinary-App contracts

Android's [VPN developer guide](https://developer.android.com/develop/connectivity/vpn?hl=en), “Bypassing the VPN” section (lines 292–296 when inspected), states that VPN bypass requires the VPN owner's Builder.allowBypass; apps bound to a specific network lose connectivity when traffic outside VPN is blocked. It also documents that only one VPN is active per user/profile. Therefore binding the whole App process or just the listener to Wi-Fi cannot satisfy this condition. Starting ctOS's own VPN would replace the existing one, violating the user's constraint.

[VpnService.protect(int)](https://developer.android.com/reference/android/net/VpnService#protect(int)) keeps a socket outside VPN but fails if the calling application is not prepared or was revoked. ctOS is not the current prepared VPN owner; constructing a VpnService object or invoking JNI does not grant its privilege. The active VPN owner is a different case: its protected upstream sockets are necessary to maintain the VPN tunnel.

[Network.bindSocket(FileDescriptor)](https://developer.android.com/reference/android/net/Network#bindSocket(java.io.FileDescriptor)) exists since API23 and operates on an unconnected socket; accepted connected sockets cannot simply be rebound afterward. [NDK android_setsocknetwork](https://developer.android.com/ndk/reference/group/networking#android_setsocknetwork) also exists since API23 and is equivalent to Network.bindSocket. JNI/NDK avoids Java plumbing, not system access checks.

### AOSP enforcement distinguishes ingress from routing

[AOSP NetworkController.cpp](https://android.googlesource.com/platform/system/netd/+/refs/heads/main/server/NetworkController.cpp), inspected blob `b2362428e1dff189b39c69f7d05f8ac69af0a2b1`, has two relevant checks:

- `checkUserNetworkAccessLocked`, lines 940–945: an ordinary UID under a secure/non-bypassable VPN cannot choose a physical network unless protectable; returns EPERM.
- `canProtectLocked`, lines 729–733: only system-network permission or an explicitly protectable UID qualifies; `getPermissionForUserLocked`, lines 911–916, treats UID below FIRST_APPLICATION_UID as system permission.

[AOSP FwmarkServer.cpp](https://android.googlesource.com/platform/system/netd/+/master/server/FwmarkServer.cpp), lines 245–269, runs these checks for SELECT_NETWORK and PROTECT_FROM_VPN. This rejects the proposed ordinary socket binding before a claimed “Wi-Fi bypass” can exist. A raw SO_MARK magic number is neither a supported ordinary-App API nor a correct cross-version design.

[AOSP RouteController.cpp](https://android.googlesource.com/platform/system/netd/+/refs/heads/main/server/RouteController.cpp), lines 519–546, uses secure VPN UID rules ahead of ordinary explicitly selected network rules; lines 1077–1086 install a UID-specific FR_ACT_PROHIBIT rule for non-protected traffic. Lines 480–495 describe ingress marks that preserve replies on the incoming network. Those reply marks solve routing in isolation; they do not cancel an independent UID ingress filter.

[AOSP Connectivity BPF netd.c](https://android.googlesource.com/platform/packages/modules/Connectivity/+/a60572f8441e5799da6d09f5ced03fa6ad42ed18/bpf/progs/netd.c), lines 398–430, checks socket UID. System UIDs pass early. Ordinary UID ingress from a non-loopback interface is rejected if IIF_MATCH requires a different interface, or LOCKDOWN_VPN_MATCH applies without a valid ingress interface. This can discard a LAN TCP SYN before Python accepts a request, explaining why HTTP access logs alone may contain no failed Windows connection. The filter applies to IP traffic independently of an IPv4/IPv6 wildcard bind. IPv6 cannot make Windows's requested IPv4 endpoint reachable or grant an exemption.

[AOSP Vpn.java](https://android.googlesource.com/platform/frameworks/base/+/b8e604452076/services/core/java/com/android/server/connectivity/Vpn.java), lines 1669–1752, builds lockdown UID ranges excluding VPN owner/allowlisted packages, converts them to setRequireVpnForUids, and intentionally excludes UID0 to allow kernel IPSec traffic. Lines 1064–1068 authorize the VPN owner to protect sockets. These are framework-managed exceptions; ctOS cannot claim another package's UID.

### Reviewable privileged relay proposal — requires separate authorization

This is a proposed scope for user review, not implementation or successful device validation:

1. Keep Python HTTP, upload limits, no-overwrite logic, directory grants and all file I/O under the App UID. Launch Python on an owned ephemeral `127.0.0.1` port. Do not run HFTP/Python or the SAF broker as Root.
2. Add a small trusted native Root helper whose only job is bounded byte relay: listen on the selected Wi-Fi address/7888 and connect to the exact loopback backend. It has no HTTP parser, filesystem access, remote command facility or arbitrary target selection. Initial validation can restrict the remote peer to WINDOWS-LAN-IP; production LAN scope must be explicit to the user.
3. Select the existing physical Wi-Fi network using public `android_setsocknetwork` with the Network handle provided by Java, checking every return and errno. AOSP UID0 has system network permission and is exempt from the cited owner ingress filtering and lockdown prohibit range. This is the evidence-based reason a Root-created socket may work; do not hardcode fwmarks, use shell firewall rules or assume connect success.
4. Confirm backend readiness before helper launch; only report relay ready after listener bind and loopback target validation. Bound connections, buffers and idle/session time; reject non-selected interfaces/peers; treat Wi-Fi loss or address change as stop/fail unless a safe restart contract exists.
5. Make HftpService own both children and their exact lifecycle. Helper receives a dedicated control pipe/heartbeat and exits on EOF, parent loss, timeout or explicit stop. Stop must close active sockets and reap only owned children. Do not use broad pkill, port-owner killing, daemonization or collector RootSession for a long-running relay.
6. Preserve Android always-on/lockdown and Clash settings exactly. Do not edit iptables/nftables/BPF maps, routes, VPN exclusion lists, SELinux policy or global process network binding. Expose an explicit “Root LAN relay” mode/status and record helper start/stop/errors plus ordinary HTTP logs. Never silently fall back from failed Root relay to default-directory exposure.

The main remaining technical risks are OEM UID filtering, `su` domain/capabilities, execution of the APK helper under that domain, physical Network-handle access, native binding failure, and orphan cleanup. [Android SELinux documentation](https://source.android.com/docs/security/features/selinux?hl=en) confirms mandatory access control applies even to Root processes. None of these can be resolved by disabling SELinux for acceptance. Actual selected-directory LAN transfer with unchanged VPN settings is the required proof.

Why separate authorization is required: existing `design/constraints.md:53` and `.trellis/spec/backend/python-workbench.md` explicitly define HFTP as App-only, without Root. Existing Root grants cover collection/PTY and are not a new task authorization. The proposed helper adds a privileged externally reachable network endpoint and deliberately bypasses effective VPN enforcement for that endpoint. The user requested unchanged settings and an App change, but has not explicitly selected this changed privilege boundary. Main can finish ordinary logs/diagnostics and prepare this concrete design without running a Root listener; acceptance must name the scope before activation.

### Alternative respecting policy without privileged LAN bypass

Existing ADB forwarding is a concrete transport alternative: Windows uses authorized ADB to forward its local port to phone loopback and browses `http://127.0.0.1:<local-port>/`. It preserves VPN settings and does not expose App UID sockets on physical LAN, but requires ADB and changes the browser endpoint. Direct `PHONE-LAN-IP:7888` cannot be promised under ordinary-App constraints. A VPN-carried application relay would require a reachable VPN-side server/tunnel configuration and authentication; it is not a small listener change or an available current feature.

## Related specs

- `.trellis/spec/backend/python-workbench.md`: App-only HFTP lifecycle, SAF and storage boundaries.
- `.trellis/spec/frontend/workbench.md`: running status and explicit stop.
- `design/constraints.md`: current authorization, no other-app/system changes, explicit privileged boundaries.
- `design/portable-tools.md` and task PRD: current LAN wildcard defaults do not establish external reachability.

## Caveats / Not Found

- Current AOSP source references are mechanism evidence, not a claim that this target phone ROM uses byte-identical rules. Android16-release URLs failed via browser cache for some files; stable inspected commits and public API contracts are identified above instead.
- No device/root/filter mutations, packet capture, helper prototype, install or LAN service activation was performed by this research agent.
- No public ordinary-App API permitting direct physical-LAN HTTP under both secure VPN and lockdown was found. Root feasibility remains a proposal needing authorization and actual device checks.
- Distinguish HTTP-level errors (logged by server) from pre-accept firewall/isolation drops (not visible to HTTP logger). Add start/listen/network-context logs and explicitly label reachability unverified; do not turn zero request logs into certainty about which firewall rule dropped a packet.

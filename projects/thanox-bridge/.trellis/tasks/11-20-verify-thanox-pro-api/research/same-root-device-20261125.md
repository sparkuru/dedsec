# Same-root connection and shared-UID validation

## Scope and device

On 2026-11-25 the designated `product:rk3568_firefly_aioj` board reported Android API 30, user 0, and installed Thanox `9.6-prc` (`versionCode=3404474`). The product's verified service connection reported Thanox 9.6. The Android 16 phone profile was not retested on the phone during this continuation.

The product now creates a fresh verified root connection for each detail read or write, and obtains the IOps child and each exact-UID query interface from that same connection. This narrows the separate-root connection boundary. It does not by itself prove equivalent server-side resolution, attest that the root Binder comes from the installed APK, detect every service replacement, or make the Android identity checks atomic with a write. The available upstream checkout does not include the relevant service implementations; see `uid-resolver-source-20261125.md`. Later static inspection of the board's installed APK found a common named UID method on the reached AppOps paths and related initial service wiring; the precise lifetime, provenance and other limits are in `uid-resolver-bytecode-20261125.md`.

## Controlled Binder checks

The opt-in instrumentation executes in-process fake Binders before the device fixture test. It verifies that IOps and UID queries are routed through the same root, rejects missing or mismatched child descriptors, and simulates a failure on each of the first three UID verifications around a guarded write. The first failure prevents the fake write; the later failures occur after the fake write and are reported as uncertain. These are controlled checks of routing and failure sequencing, not proof of installed Thanox server behavior.

## Shared-UID rejection on the board

Two initially absent, same-signed, code-free, target-SDK-35 fixtures `im.majo.thanoxbridge.test.shareduida20261123` and `im.majo.thanoxbridge.test.shareduidb20261123` were installed for user 0 and shared UID 10231; a shell count found two packages for that UID. Their APK SHA-256 values were `6e822406d1e081e2e383fcc8ea4f942b2955ce0c3e79859d3ea4e2794c49bed9` and `4ae7183dc1445b49affcf909f896282e97cc3c427f8842f0c5a0cf3c71b3feb8`. The temporary signing certificate SHA-256 was `9a668bbae9d449c7c2ef1ab35f7303273998e52189d0b14f42d063c3c48db582`.

Both fixtures initially had CAMERA granted and default AppOps mode `allow`. Revoking CAMERA for A established denied permissions and UID AppOps `ignore` for both A and B. The opt-in runner verified the board profile, exact fixture names, user, UID, signer, shared-user declaration, target SDK, non-system/code-free status, CAMERA declaration and denied state. It also checked that CAMERA `ALLOW_ALWAYS` was allowed by the board profile apart from the shared-UID restriction, so an unrelated unsupported-mode rejection would not count as success. It then called the real `PermissionRepository.setOpMode` for A and required the shared-UID rejection.

The final board-tested product APK SHA-256 was `70b130e0e1e970cbb63a36fec0b578dcb231ac2a015508fdc285246263b0775b`; the opt-in instrumentation APK SHA-256 was `f48b5e9534889f16661546cf1f24e511c3fa824a530f698edd3be03437a6a8c5`. Installed hashes matched. The runner reported `Same-root Binder safety: PASS`, `Shared UID safety: PASS` and `INSTRUMENTATION_CODE: -1`. Independent Android reads after the attempt still showed CAMERA denied and UID AppOps `ignore` for both fixtures. Before uninstall, granting CAMERA for A restored effective allow and default AppOps `allow` for both. The earlier opt-in APK was also run successfully before the connection adjustment; only the final APK produced the same-root Binder result.

## Independent target and product UI

A previously absent, code-free target `im.majo.thanoxbridge.test.board20261123.target`, target SDK 35 and UID 10234, was installed from an APK with SHA-256 `aba1b805d070826523f8dfde7a967aa1374fbfe4f2aaf2504fc5f04120b87586`. Its initial CAMERA, RECORD_AUDIO and VIBRATE permissions were granted with default AppOps `allow`.

An exact package lookup in the final product opened this target without first reading the full app list. The product showed the Thanox 9.6 detail and allowed CAMERA states. Setting CAMERA to `DENY` through the UI was reported as the read-back result; independent Android reads showed CAMERA denied and UID/package AppOps `ignore`. Setting `ALLOW_ALWAYS` through the UI was likewise read back; Android then showed CAMERA granted and default UID AppOps `allow`. The other two permissions remained granted with default allow. CAMERA initially had a `USER_SET` flag that was absent after restoration, so exact flag restoration was not established.

## Cleanup and limits

The debug product, opt-in test package and three disposable targets were uninstalled. Package-manager queries including uninstalled entries returned no matching task or product packages, and the explicitly created device installation files were absent. The task-owned host scratch directory, temporary APKs, signing key and password were removed. The targets contained no code and did not exercise physical camera, microphone or vibration hardware. Existing app permissions were not changed. This device record is not itself a security review; the later user-directed sub-agent review is in `security-review-agent-20261125.md`.

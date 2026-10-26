# P0 device validation record

Status: **agent device checks not executed**. On 2026-10-26 the user reported successful APK installation and no useful scope selection in Vector. No complete device environment or injection/authentication result was supplied. No device was modified by the agent. Build, widget and JVM results cannot satisfy the remaining checks.

## Environment to record at execution

Device model, ROM build, Android API/security patch, active Android user, root/Zygisk/Vector versions, installed manager and fixture APK hashes, signing-certificate digests and source revision. Use only the controlled `dev.tacchi.probe` fixture, with no payment-app scope or actual payment data.

Current manager is `im.majo.tacchi`; the former `dev.tacchi.app` can coexist as a separate installation. Disable the old module in Vector before testing the new matched manager/fixture pair, then restart the fixture. Verify editable scope appears for the new module; selecting another app must not imply an implemented adapter. The agent's2026-10-26 readonly `adb devices -l` found no connected devices.

## Required observations

| Scenario | Required observation |
| --- | --- |
| Manager without framework | Manager opens; framework unavailable and host unconfirmed remain distinct; no crash from compile-only module classes. |
| Framework connected, fixture stopped | Connection/API is visible; host is not inferred loaded. |
| Scope and restart | With only the fixture in scope, restart fixture and obtain fresh module acknowledgement. Unscoped package/process receives no probe. |
| Authorized transport | Manager and fixture resolve the expected package, current user and signing identity; valid bounded requests work. |
| Wrong identity | Other package, same-name wrong signature, wrong Android user and ambiguous shared UID are denied. Do not replace user applications to perform this test. |
| Protocol abuse | Wrong target/revision, expired request, duplicate completion and request replay cannot publish success or start another operation. |
| Process restart | Restart either side; prior in-memory authorization is invalid and status becomes unconfirmed/stale until a new exchange. |
| Hardware round trip | Actual key information proves hardware protection and per-operation biometric policy. Two cipher-bound operations complete using host-local synthetic bytes; only non-sensitive outcome reaches manager. |
| Unsupported authentication | No enrolled/available strong biometric or software-only key protection produces unsupported/failure, with no device-credential or software-key fallback. |
| Cancellation/lifecycle | Cancel each prompt, background/rotate/destroy the fixture, expire/revise a request, and deliver a late callback. No successful result survives cancellation or stale state. |
| Cleanup | Only this probe's candidate alias is removed; test buffers are cleared on terminal paths. No stored payment data is touched. |
| UI/accessibility | Small portrait, landscape, large text, dark/light theme and TalkBack retain readable status and reachable controls; errors do not rely on color. |
| Offline/privacy | Inspect packaged manifest and runtime module behavior, including in the injected process; no module-originated networking, raw IPC payload, secret or host-page text appears in diagnostics. |

Record command/action, expected and actual outcome for each scenario. Keep logs bounded to package/version/state/error codes; omit credentials, biometric data, full view trees and unrelated device logs. A skipped scenario stays unverified. P0 remains open until its host/hardware acceptance is demonstrated.

# Native Android contracts

## 1. Scope and trigger

Applies to native diagnostic UI, package/IPC identity, libxposed entry metadata and optimized builds. The user selected Kotlin platform Views and `im.majo.tacchi` on2026-10-26. No Flutter/Compose/WebView runtime is part of this contract.

## 2. Signatures

- Build: `./hako bash -c 'cd android && ./gradlew :app:assembleRelease --dependency-verification=strict --no-daemon'`.
- `Diagnostics.decode(fields: Map<String, Any?>): Diagnostics` validates internal status snapshots.
- `DiagnosticsSession.resume()/refresh(): Long?` starts a generation; `complete(token, diagnostics, readAtMillis)/fail(token): Boolean` accepts only its current foreground result. `pause()/destroy()` revoke it.
- `FrameworkStatus.snapshot()` and `NativeDiagnostics.snapshot(context)` supply manager status directly. No Dart channel is retained.
- Host IPC: authority `im.majo.tacchi.probe`, operations `loaded`, `begin`, `check`, `finish`; identity remains system-derived and certificate-pinned. The allowed host is `dev.tacchi.probe`.

## 3. Contracts

| Boundary | Required behavior |
| --- | --- |
| UI snapshot | String fields `frameworkState`, `hostState`, `probeState`; nullable positive Int `frameworkApi`; nullable allowlisted `errorCode`. Never forward raw exceptions or secrets. |
| Framework state | `connected` requires API≥101; `unsupported` requires API<101. Connection does not imply host loading. |
| Diagnostics read | Run outside the UI thread; timeout after5 seconds; publish timestamped snapshots only. Reset evidence on refresh, failure or foreground loss; reject late/duplicate results. |
| Native views | Scrollable single page, system insets,48dp controls, text wrapping/scaling, system back, day/night theme and meaningful native semantics. No remote fonts/assets. |
| Identity | Main application/namespace `im.majo.tacchi`; fixture `dev.tacchi.probe`; fixture queries and mutual checks match. Old manager package is not a fallback. |
| Scope | `minApiVersion=101`, `targetApiVersion=101`, `staticScope=false`. `scope.list` recommends the fixture; hook still checks exact package and process. User-selectable scope is not implemented payment-app support. |
| R8 | Keep exact entry `im.majo.tacchi.module.TacchiModule`, its public constructor and both API101 lifecycle callbacks named in `android/app/proguard-rules.pro`. Keep API compile-only. |
| Tooling | Caller-owned cache under `.devhome/`, pinned SDK/JDK image, no device/socket mounts. Normal Gradle builds enforce checksum metadata; do not delete verification controls to resolve dependency changes. |

## 4. Validation and error matrix

| Condition | Result |
| --- | --- |
| Missing/malformed enum or incompatible API | Decode rejects; UI shows bounded read failure with unknown evidence. |
| Timeout/background/destroy | Cancel queued work where possible, revoke generation and ignore late callback. Binder cancellation alone is not proof the remote work stopped. |
| Wrong user/UID/package/certificate or ambiguous shared UID | IPC denies without returning tickets or raw data. |
| Wrong revision, expired/replayed ticket | No second execution or success publication. |
| Framework unavailable | Manager still opens without loading framework-only entry classes. |
| User selects an unsupported app in Vector | No host hook/probe initialization; exact runtime allowlist still applies. |

## 5. Good, base and bad cases

- Good: manager shows `connected/API101` and `host unknown` independently; a separately verified fixture result updates the latter.
- Base: framework missing, user refreshes; readable unavailable/unknown states remain, with no authentication or payment actions.
- Bad: an old read completes after pause/timeout, or an untrusted caller claims the fixture package. Both must be discarded/denied.

## 6. Required tests and inspection

Run native diagnostic/session, framework, identity and ticket JVM tests. Compile Activity and IPC instrumentation tests; execute on Android for actual View layout, font scaling, insets and Binder evidence. Record unexecuted cases rather than reusing old Flutter test results.

Inspect the final optimized manifest/DEX: entry constructor/callbacks present, API class definitions absent, correct provider/query identities, runtime license notices retained, no Flutter/Dart assets or native-engine libraries, and no `INTERNET`. Measure APK bytes from the actual file. With no native libraries, a single APK covers architectures; ABI splitting adds no value.

## 7. Wrong versus correct

Wrong: set `staticScope=true` with only an uninstalled test host, then claim users can select arbitrary apps. Correct: editable metadata plus a fixture recommendation, while explaining the narrower implemented host support.

Wrong: trust a successful build or former Flutter screenshot as native UI/device acceptance. Correct: separate JVM/build/static evidence from executed Android UI, Vector, Binder and biometric tests.

Wrong: leave both old and new modules enabled for the same fixture. Correct: disable the old module before testing the new matched manager/fixture pair; package-name migration does not automatically uninstall the old app.

# Installed-build feedback results — 2026-10-26

## Delivered behavior

The user reported successful installation, excessive APK size and no useful Vector scope selection, then selected Kotlin platform Views and main identity `im.majo.tacchi`. The single diagnostic page now uses a platform Activity and Views. Flutter/Dart source, runtime, method channel and Gradle plugin have been removed from the active project. Hook, authenticated IPC and hardware-probe responsibilities remain Kotlin.

The native page preserves independent framework/host/probe status, timestamped snapshots, loading/error/refresh,5-second timeout and generation-based cancellation. It includes day/night resources, system/cutout insets, scrollable text,48dp controls and native accessibility semantics. JVM tests validate state and resource contrast; actual View rendering is not claimed from source inspection or the former Flutter screenshot.

## Size evidence and BBZQ comparison

| Artifact | APK bytes | Interpretation |
| --- | ---: | --- |
| Original installed-build baseline | 48,345,323 | Three ABI copies; Flutter/app native libraries total41,595,892 bytes. Preserved in ignored `build/size-baseline-2026-10-26/`. |
| Intermediate optimized Flutter universal | 42,318,125 | R8/resource/icon shrinking helped; superseded when user selected native Views. |
| Current Kotlin native release | 92,229 | About90.07KiB,99.8092% smaller than original. No `lib/` libraries, so one APK covers supported architectures. |

BBZQ v1.5.1 source uses platform Views and R8/resource shrinking; GitHub asset metadata reports its universal APK as3,050,694 bytes. See `research/size-scope-feedback.md` for immutable source and release links. BBZQ code was not copied or executed, and its behavior/security is not inherited. These are APK-file sizes, not installed-storage or memory measurements; no bitwise-reproducibility claim is made.

## Scope and identity

Original `staticScope=true` plus fixture-only `scope.list` restricted Vector's visible/selectable targets. Packaged metadata now sets `staticScope=false`, retains API101 min/target and recommends `dev.tacchi.probe`. The hook still accepts only that package and process. Editable Vector selection does not add page detection or password input for other apps; real payment adapters remain outside P0.

Main namespace, applicationId, module entry, manifest components/provider authority, peer identity and fixture package-visibility queries now consistently use `im.majo.tacchi`. Provider authority is `im.majo.tacchi.probe`. No `dev.tacchi.app` fallback remains. The fixture identity stays `dev.tacchi.probe`.

Android treats the new main package as a separate app. Before device acceptance, disable the old module in Vector, enable the new one, and update the matching fixture. Two module versions hooking the same fixture can interfere. No automatic uninstall, data migration or device installation was performed.

## Verification

- Native full build:152 tasks successful; `/tmp/tacchi-native-build.log`.
- Final non-writing strict build:151 tasks successful; `/tmp/tacchi-native-strict.log`. Command: `./hako bash -c 'cd android && ./gradlew :app:assembleRelease :probe:assembleDebug :app:testDebugUnitTest :app:lintDebug :probe:lintDebug :app:assembleDebugAndroidTest --dependency-verification=strict --console=plain --no-daemon'`.
-29 JVM tests, zero failures/errors:6 framework,7 ledger,5 identity,10 diagnostic state/decoder,1 day/night palette contrast. XML: `build/app/test-results/testDebugUnitTest/`.
- App lint:0 errors,3 known advisory warnings (pinned Gradle/test-runner versions and deliberately exported caller-authenticated provider). Fixture lint: no issues.
- Activity and IPC instrumentation APK compiled; not executed. `adb devices -l` returned no connected devices. No native layout/TalkBack, live Vector/Binder or hardware-authentication acceptance was performed.
- Shell syntax, ShellCheck, shfmt and tracked whitespace pass. Upstream Gradle launchers and applicable Kotlin notice bytes were compared exactly. Runtime Kotlin/service notices are packaged; former Flutter notices are retained only as historical provenance.
- Independent read-only `trellis-check` reviewed new identity/scope, Activity lifecycle/insets/state, R8 retention and final package evidence; no concrete implementation blocker remains.
- Coordinator inspected APK names, permissions and matching signing certificates in a network-disabled container. Main declares no permissions; fixture declares `USE_BIOMETRIC`; neither declares `INTERNET`.
- Final ZIP/DEX evidence: no Flutter/Dart assets/classes, no native libraries, no defined libxposed API classes or old manager classes. Exact module entry constructor and both API101 lifecycle methods survive R8. All three `META-INF/xposed/` files and runtime notices are present. Evidence includes `build/native-release-manifest.xml`, `build/native-release-dex.txt`, `build/native-module-smali.txt` and independent checker output under `/tmp/tacchi-native-review-*`.

## Final local artifacts

| Path | Bytes | SHA-256 |
| --- | ---: | --- |
| `build/app/outputs/apk/release/app-release.apk` | 92,229 | `7686a3f0edfbf37e44b4dff4cd58f7da9c6285d396913edd09422894e92cc10d` |
| `build/probe/outputs/apk/debug/probe-debug.apk` | 2,359,190 | `72816cfc71a67a34e75f4390db3479f76d69c10bf3bdd773e4f0c3500b44f4a0` |

Both use the existing local development certificate SHA-256 `80e7d879cd2f911f44ed0d06330b5965c0bf0999e837c5c833ce8680739d9d6d`. These are development-signed artifacts. Earlier intermediate hashes are superseded by this final strict build.

## Remaining acceptance

`human-required`: actual native UI/Vector scope behavior, host injection, Binder identity rejection and hardware biometric/lifecycle checks remain in `device-validation.md`. The P0 task stays in progress. On2026-10-26, after receiving these limitations and the final artifact results, the user requested a work snapshot commit (“提交一版”). This authorizes the current implementation commit while retaining incomplete device acceptance. No archive or later payment phase is authorized.

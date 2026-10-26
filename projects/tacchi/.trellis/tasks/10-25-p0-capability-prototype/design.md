# P0 design: Vector API 101 foundation

## Approved direction and implementation boundary

Kotlin platform Views for the single native configuration page plus Kotlin Android integration, modern libxposed API 101 minimum, Vector's own API/service documentation and pinned revisions. The user's 2026-10-26 native Views selection supersedes Flutter. Device model/ROM collection does not gate engineering. The original design's host proof obligations remain open until actual execution.

Deliver a minimal diagnostic APK and development-only controlled host fixture, not a payment adapter. Keep one product APK; the fixture is a test artifact. The user selected main application identity/Kotlin namespace `im.majo.tacchi` on 2026-10-26, superseding the original development identity `dev.tacchi.app`. The fixture remains `dev.tacchi.probe`. Production signing/publication remain undecided.

## Build and API boundary

Use the image digest and tool versions verified in `research/toolchain.md`. Read `research/vector-contract.md` for exact Vector/API/service revision selection and contracts before coding. Baseline: Vector v2.1, module API `101.0.1` and service `101.0.0`. Their AARs require compileSdk36, so prepare SDK36 and use AGP8.10.1 with Gradle8.12, Kotlin2.1.0 and the image JDK21. Pin dependency versions or immutable source artifacts; no dynamic versions or unverified mutable snapshots. Target API 101 and exclude API 102-only features, hot reload and legacy API 82 compatibility.

Proposed Android minimum is API 28, allowing platform BiometricPrompt for the probe without importing host AndroidX/Flutter activity requirements. Reconcile this technical baseline with pinned API requirements before generating manifests. Compile/target SDK must be supported by the selected artifacts and actual installed tools, with missing SDK/NDK downloads recorded explicitly.

Keep the manager Activity separate from API-dependent module entry classes. The manager must launch when Vector is unavailable: do not load module API classes from manager initialization. The hook entry must not depend on manager Activity resources in the host. Package modern module metadata under META-INF/xposed according to the pinned contract. Filter both target package and process before installing any probe hook; no system scope or wildcard target.

## Components and data flow

- The native Kotlin Activity presents framework availability/API, host acknowledgement and probe result separately. Read bounded status directly from existing Kotlin owners on a background executor; no Dart method channel remains.
- Kotlin manager connects to Vector's service using its supported discovery/lifecycle contract. Compare remote preference/file features against this prototype's needs before using them; service connection is not evidence that a host loaded the module.
- Module entry runs in the controlled host and reports a non-sensitive load/probe result. Use one documented, explicit manager IPC endpoint for host acknowledgements if the pinned service API has no suitable authenticated host-to-manager mechanism.
- Caller authentication derives UID/user/package/signature from Binder and PackageManager, never request-provided identity. P0 permits only the controlled test host's known package and expected certificate; reject ambiguous/shared UID and wrong signatures. Do not accept arbitrary installed packages or TOFU-pair silently. Production target pairing remains separate work.
- Probe messages contain an explicit operation, random request ID, revision, deadline and bounded status/error codes. No credential bytes, ciphertext, cipher objects or secret-bearing exceptions cross process boundaries. Handle stale/replayed/canceled requests in one serialized lifecycle.

## Host authentication probe

Use synthetic test bytes generated inside the controlled host process. Create a non-exportable hardware-backed Android Keystore AES-GCM key, inspect protection properties, require per-operation strong biometric authorization and bind the actual cipher to the prompt. Refuse unsupported protection; no software key or device-credential fallback. Successful round-trip reports only a boolean outcome and capability/error codes, never decrypted content.

Keep candidate probe key/cipher/cleartext owned by a single foreground probe session. Cancel and invalidate on background, destruction, duplicate callbacks or revision changes; a late callback cannot publish success. Clean up only keys/buffers created for this probe. These tests exercise architecture with disposable data, not real payment functionality. Exact platform API choices must be verified against Android documentation during implementation; unavailable physical hardware is recorded as unverified.

## Diagnostic UI

Use the original decisions in `research/ui.md`: minimal Material status screen, local assets, separate unknown/disconnected/unsupported/host-unconfirmed/passed/failed states and a refresh action. No fake successful default, payment-password field or production enable switch. Support small portrait, landscape, large text, light/dark contrast, semantics and reduced motion. No browser preview or Playwright substitution.

## Verification and operational limits

Use an ephemeral non-privileged Docker wrapper with caller ownership, repository-local ignored caches, no published ports and no device/socket passthrough. Build inputs may download; the resulting APK must remain offline. Do not modify global tools or auto-install APKs to a device.

Run Kotlin logic tests, Android lint/build and final package inspection; compile native UI/instrumentation tests and execute them only when Android is available. Check API classes are compile-only, registration/resources exist, the package contains no Flutter runtime/INTERNET permission or online assets, and status does not confuse framework connection with host acknowledgement. Controlled Android instrumentation validates UI/caller identity/IPC when a suitable emulator/device is available. Unit tests are not substitutes for Android rendering, Binder or hardware proof.

Device checklist remains part of P0 acceptance: actual Vector injection, strong-biometric hardware round trip, cancellation/no output, invalid identity rejection and configuration/host lifecycle behavior. Do not close P0 as fully verified while these remain unavailable. No real transactions or payment-app scope are needed for the controlled fixture.

## Changes and rollback

### Installed-build feedback, 2026-10-26

Enable editable Vector scope metadata after verifying the pinned contract, while retaining fixture-only runtime filtering and recommended scope. Scope selection grants no additional adapter support. Rename main namespace/applicationId, entry registration, provider authority, mutual identity checks, package queries and Dart/Kotlin channel together.

The user explicitly selected Kotlin platform Views after the BBZQ comparison. Replace the Flutter Activity, Dart code/tests/pub manifests and Flutter Gradle integration with one platform Activity, native resources and testable Kotlin diagnostic state. Preserve all existing Kotlin IPC/Hook/security behavior. Maintain 48dp controls, readable text/contrast, scrollable narrow/large-font layouts, day/night theme, system insets and explicit snapshot time. Read asynchronously; clear evidence on failure/background and reject late callbacks. Compile native UI tests and record unavailable actual Android rendering/accessibility execution.

Use R8/resource shrinking with explicit retention of the libxposed entry and reflective contracts. Inspect final minimized DEX/metadata/manifest and record real APK size. Keep the earlier Flutter APK only as an ignored size baseline. With no bundled native libraries, deliver one architecture-independent APK rather than pointless ABI variants. No Compose/WebView or replacement runtime is introduced.

Expected ordinary project changes: remove this task's superseded Flutter source/config/tests, update Android module/resources/tests, development-only host fixture and hako/config/ignore files, and retain exact applicable third-party notices. Update project-owned specs with real native commands after they run. Preserve protected Trellis/runtime, reference repositories and original archived design. Revert only this task's code/config on failure; do not reset unrelated untracked files or remove user data/images.

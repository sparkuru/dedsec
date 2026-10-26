# P0 implementation checks

Current results are in [the2026-10-26 native migration record](feedback-results.md). The original Flutter checks, screenshots and artifacts below are historical2026-10-25 evidence and do not validate the replacement native UI.

## Status

P0 implementation and local automated verification are complete. No device checks have run. This task remains in progress and is not ready for archive or a hardware-capability claim. No application changes have been committed or installed on a device.

## Independent native review

The initial read-only `trellis-check` pass inspected mutual identity policy, the serialized request ledger, hook-only fixture and host-local crypto boundary. It found two failure-path concerns, both addressed by the native implementer and re-read by the checker:

- Catch synchronous authentication startup failures in both encryption and posted decryption phases, routing through cleanup.
- Guard module cancellation and clear Activity/probe references unconditionally.

The final independent read-only check found no remaining concrete implementation blocker. It reviewed identity/protocol, widget state handling, shell boundaries, package contents and strict dependency verification. Android instrumentation now uses a declared AndroidX test-only runner and its APK compiles. The injected host implementation does not use AndroidX activity classes.

## Automated evidence

- `./hako dart format --output=none --set-exit-if-changed lib test`: pass; `/tmp/tacchi-p0-dart-format.log`.
- `./hako flutter analyze`: no issues; `/tmp/tacchi-p0-analyze-final.log`.
- `./hako flutter test`: 22 passed; optional font-dependent diagnostic capture is a separate run. `/tmp/tacchi-p0-flutter-test.log`.
- `:app:testDebugUnitTest`: 18 passed, zero failures/errors (6 framework, 7 ledger, 5 identity); XML in `build/app/test-results/testDebugUnitTest/`. Fixture has no separate JVM tests, not an additional passing test suite.
- Full metadata-generation build: 206 tasks, success; `/tmp/tacchi-p0-gradle-final.log`. This bootstraps hashes/locks, not their enforcement proof.
- `./hako flutter build apk --release`: pass without metadata-writing flags; `/tmp/tacchi-p0-release.log`.
- `./hako bash -c 'cd android && ./gradlew :app:assembleRelease :probe:assembleDebug :app:lintDebug :probe:lintDebug :app:assembleDebugAndroidTest --dependency-verification=strict --console=plain --no-daemon'`: pass, 179 tasks; `/tmp/tacchi-p0-verified-build.log`. This verifies recorded hashes in normal mode.
- Fixture-only rebuild after explicit `fullBackupContent=false`: pass, 48 tasks; `/tmp/tacchi-p0-fixture-final.log`. The app remains unchanged. Final lint has no errors; app retains three warnings (pinned tool/test-library versions and deliberately exported authenticated provider). The fixture lint was explicitly rerun to discard its stale report and now reports no issues; `/tmp/tacchi-p0-fixture-lint-final.log`.
- `bash -n`, ShellCheck and shfmt checks on `hako`, `tooling/container-entry.sh`, `tooling/prepare-android.sh`: pass, as reported by implementer and independently reviewed for shell syntax.
- Trellis contexts validate with ten real entries each. Tracked `git diff --check` and whitespace inspection of new source/task files pass.
- Diagnostic rendering `build/diagnostics-375.png` inspected by coordinator and checker: readable text, Material icons, separate status cards and refresh action. Widget tests cover portrait/landscape, scale 1.0/2.5, semantics and contrast. This is not Android/TalkBack evidence.

## Final artifacts and package inspection

| Artifact | SHA-256 |
| --- | --- |
| `build/app/outputs/flutter-apk/app-release.apk` | `544690e259cc5b63bd334bb1e48e2cec468b97b0fb1207b1489319da07f0b7b8` |
| `build/probe/outputs/apk/debug/probe-debug.apk` | `961d1495c997680d62357f404792d125824a79a8f2e9e7a9b84a94ea0c5d63be` |

These are local development-signed P0 artifacts, not production releases. The earlier fixture hash `8e36fd018766218ed69d4016df649da3996501b6dd8249ab299a18934814458a` is superseded by its backup-attribute rebuild.

Coordinator inspected packages using `HAKO_NETWORK=none`, `apkanalyzer`, `apksigner` and ZIP listing. Both share development certificate SHA-256 `80e7d879cd2f911f44ed0d06330b5965c0bf0999e837c5c833ce8680739d9d6d`. Neither declares `INTERNET`: manager lists the generated signature-protected receiver permission, fixture lists `USE_BIOMETRIC`.

Release contains all three `META-INF/xposed/` registration files, API101 min/target, fixture-only scope, Flutter `NOTICES.Z`, and the exact service license asset. `apkanalyzer dex packages --defined-only` output in `build/p0-defined-dex.txt` shows the module entry and no defined `io.github.libxposed.api` classes. Service classes are intentionally bundled. Release contains AOT libraries rather than a debug kernel blob. These static checks do not establish actual injected-process network behavior or hardware safety.

## Resolved failures

Initial build exposed incompatible Flutter resource-shrinking/minification defaults; configuration was reconciled. Lint needed an explicitly checked host runtime biometric permission plus a narrowly documented annotation because the code is packaged in the manager. Widget failures exposed an exact-type finder mismatch and late semantics-handle disposal; keys and `try/finally` corrected the tests while retaining action/semantic assertions. An incremental Android resource merge retained stale state after removing an obsolete qualifier; cleaning only generated app output restored a successful build.

## Residual review classification

`human-required`: authentication/IPC behavior and actual mobile/Vector/hardware checks require device evidence. Complete available automated checks first. See device-validation.md for the specific expected observations. This classification does not require renewed approval for already-authorized implementation and does not authorize installation, commit or archive.

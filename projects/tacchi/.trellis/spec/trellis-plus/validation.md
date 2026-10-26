# Validation and review

## Current validation profile

For policy/task changes, read the changed documents against the task PRD and source requirements, validate every context path and local Markdown link, check exact notice copies against their recorded sources, inspect the explicit changed-file list, and run:

```sh
python3 .trellis/scripts/task.py validate "$TASK_DIR"
git diff --check
```

Set `TASK_DIR` to the actual task. Because initialization files may be untracked, also inspect those new files for whitespace, unresolved placeholders and accidental secrets; a clean tracked diff alone is insufficient. UUPM setup verification follows [ui.md](ui.md).

The P0 scaffold establishes these application checks, all from the repository root:

```sh
./hako bash -c 'cd android && ./gradlew :app:testDebugUnitTest :app:lintDebug :probe:lintDebug :app:assembleDebugAndroidTest :probe:assembleDebug --no-daemon'
./hako bash -c 'cd android && ./gradlew :app:assembleRelease --dependency-verification=strict --no-daemon'
```

Use [the development entry](../../../tooling/development.md) for SDK preparation and disposable signing. Release remains development-signed. Inspect `build/app/outputs/apk/release/app-release.apk`, `build/probe/outputs/apk/debug/probe-debug.apk` and the separately compiled AndroidTest APK. Keep normal Gradle checksum verification enabled; metadata-generation builds alone do not prove enforcement.

Native JVM checks cover diagnostic decoding/refresh/lifecycle, framework death, identity decisions and request lifecycle. Native Activity/device tests cover accessible controls, refresh and layout configurations when actually executed. Compiling them is not Android rendering or Binder evidence. Prior Flutter widget tests and screenshots do not validate the replacement native UI. Manager-UID impersonation tests do not cover an independently signed hostile APK. Real Vector loading, hardware protection and TalkBack still need device evidence.

Inspect APK permission lists, signer equality for this controlled pair, `META-INF/xposed/{java_init.list,module.prop,scope.list}`, compile-only API exclusion, minimized entry retention and retained runtime notices. No `INTERNET` permission is necessary in either APK. The fixture declares `USE_BIOMETRIC`; the native manager no longer needs Flutter's generated receiver permission. Check that Flutter/Dart/native-engine libraries are absent. The host-only biometric call retains a runtime permission check; its narrow `MissingPermission` annotation documents the different runtime identity and must not become a global lint bypass.

Do not run the reference repository's checks as tacchi validation. CI and actual connected-device execution have not been established by these local commands.

## Native product checks

For native configuration UI work, test pure Kotlin state transitions and Android Activity behavior. Define loading, empty, unknown-host, pending, failure, disabled and success states before implementation. Assert actions/results, semantic labels, focus, touch access, font scaling, system insets and narrow-device behavior. Use diagnostic screenshots unless a controlled visual baseline has been approved. Record emulator and physical-device outcomes separately.

For IPC/session/credential changes, cover caller identity, wrong signatures/users/targets, replay/expiry, configuration revision, canceled/late callbacks, foreground loss, replacement failure and deletion acknowledgement. Use controlled test credentials and no real transfer. Hardware-backed key protection, per-use authentication, biometric changes, ROM lifecycle and Vector loading require actual target-device evidence; unit tests or a successful APK build cannot replace it. Record the full device/ROM/Android/Root/Zygisk/Vector/app-version/scenario tuple, commands, safe logs and remaining gaps in the task.

Inspect final Manifest, dependencies and DEX for the offline/privacy contract when an APK exists. Never include payment secrets, account identifiers, raw view trees or sensitive screen content in test artifacts.

## Browser and mobile applicability

Current classification: `playwright-not-effective` for native Android behavior; no browser UI exists. Browser setup/base URL/projects and desktop browser coverage are not applicable. Flutter web emulation must not stand in for native authentication or IPC. No Playwright runner is installed by this policy.

If an approved task adds a browser-accessible surface, classify it as `playwright-required`, `playwright-existing-equivalent`, `playwright-not-effective`, or `playwright-unavailable` from evidence. Create one project-level Playwright Validation Profile here with actual execution mode, install/browser bootstrap, readiness/base URL, focused/CI commands, config/tests, browser/viewports, fixtures, accessibility/visual policy and failure-artifact paths. Use reproducible flow assertions before asking for manual review. Missing dependencies are unavailability, not ineffectiveness.

Native UI work is `mobile-required`. Initialization changes no UI, so it performs no mobile acceptance test. Future UI tasks must record the selected device sizes/settings and interactions; desktop success does not close mobile checks. Preserve safe failure logs/screenshots and runner traces where supported. Review intentional baseline changes rather than auto-accepting them.

## Trellis Plus: Submit-Ready Human Review Gate

Before staging, committing, completing or archiving, compare the PRD, final diff and actual check evidence. Record one classification:

- `human-required`: a material check is unavailable or failed; required device/mobile coverage is missing; behavior affects authentication, payment, credentials, permissions, deletion/migration, security, deployment/CI, generated assets or irreversible actions; or unresolved product, accessibility, visual or private-environment judgment remains. Complete all runnable checks first, then stop before completion/commit for that specific residual decision.
- `human-optional`: relevant checks pass and only a low-risk preference or additional smoke signal remains. Say that feedback does not block work already authorized.
- `human-not-needed`: mechanical/documentation changes or focused acceptance checks leave no meaningful human-only question. Record the concrete reason.

A request must state what changed, exact checks and results, the remaining scenario to exercise, and useful pass/fail feedback with safe logs, expected/actual behavior and environment details. Do not ask for a generic smoke test to replace runnable automation. Reuse prior approvals; review classification neither grants commit authority nor forces reapproval of settled decisions. Installing this policy alone does not complete a task.

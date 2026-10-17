# Validation and Submit-Ready Review

## Current Validation Profile

Initialization changes policy and notices, not application behavior. Required current checks: resolve relative Markdown links, verify all referenced repository sources, run `python3 .trellis/scripts/get_context.py --mode packages`, inspect `python3 .trellis/scripts/task.py list`, compare protected-file hashes before/after, check local integration ignores, and run `git diff --check`. As new files are untracked in a parent Git repository, also check their whitespace directly; an empty Git diff alone does not validate them.

There is no new app build, test runner, Flask dependency manifest or CI command. The archived Android build inputs declare AGP/Kotlin and an `app` module, but contain no Gradle wrapper. Global Gradle is absent. Do not describe `./gradlew`, pytest, lint, instrumentation or APK builds as runnable/passing for the new app. Record exact commands and report paths after the first build scaffold is created.

## Acceptance Checks for Product Work

Before a protocol change, establish deterministic frame fixtures and response expectations from `application.md` and its sources. Cover escaping, checksums, length validation, chunking, duplicate DATA, ACK sequencing, request timeout, notifications and unsupported capabilities as applicable. Cross-language fixtures must compare actual bytes, not merely round-trip each implementation against itself.

For a Flask route, exercise input validation and observable success/failure with a controlled transport and explicitly state whether hardware was used. Before introducing a route, establish the shared API shape and backend role. Never use real device identifiers or personal credentials as committed fixtures.

For Android UI changes classify `mobile-required`; use the implemented native unit/instrumented test setup and an emulator/device with recorded API level, screen configuration and test command. Exercise permission denied/granted, Bluetooth off, no paired target, loading/connecting, disconnected/reconnect, unsupported features and confirmed state after interaction. Test relevant narrow layouts, font scaling, touch targets, TalkBack labels, light/dark and reduced-motion behavior. Keep screenshots/logcat/test results at the actual runner's report paths and redact device identifiers. Native interaction is `playwright-not-effective`; browser emulation does not verify an APK or Bluetooth.

Desktop UI testing is currently not applicable: no desktop/browser product UI exists. Flask alone does not establish a browser UI. If browser pages are later approved, classify `playwright-required`, `playwright-existing-equivalent`, `playwright-not-effective`, or `playwright-unavailable` from the actual surface. Create one Playwright Validation Profile here before implementing those changes. Record execution mode, dependency/browser setup, startup/readiness/base URL, exact focused and CI commands, config/test locations, browser and desktop/narrow-mobile viewports, fixtures, accessibility/snapshot policy and failure artifact paths. Run focused interactions and final-state assertions on relevant desktop/mobile profiles; use semantic locators and controlled fixtures, preserve traces/screenshots/console-network logs on failure, and review snapshot changes. Missing tools are unavailable evidence, not an exemption or a pass.

Bluetooth acceptance requires a real selected phone/headphone/firmware combination. Record model, protocol, firmware, permissions, operation, response/readback and result, redacting unique identifiers. Archived §10–12 observations are candidate fixtures, not current hardware verification. Emulator/fake-transport passes do not close pairing, background/tile lifecycle, notification synchronization or physical control checks. Never send device writes as an incidental validation step without a scoped device-control request.

## Trellis Plus: Submit-Ready Human Review Gate

Before staging/committing or marking complete/archiving, compare scope, diff and actual validation. Choose and record one result:

- `human-required`: unresolved product/visual judgment; unrun material check; required mobile/hardware/private environment evidence; security/auth/permissions, deletion/migration, deployment/CI or irreversible effects. Complete all runnable automation first, then stop the dependent completion/commit until the specific residual issue is resolved or explicitly accepted within existing authority.
- `human-optional`: automated acceptance passes and only low-risk preference judgment remains. State that feedback does not block authorized work.
- `human-not-needed`: mechanical/documentation-only changes or focused behavioral checks leave no meaningful judgment for the user. State the evidence-based reason; do not add a generic smoke-test approval step.

When review is needed, provide the changed behavior, exact checks/results, smallest remaining scenario to test, expected result and requested pass/fail feedback; request environment details and redacted logs/screenshots/reproduction steps only for failures. Distinguish desktop, emulated mobile and real-device evidence. Reuse approvals already granted. Review classification does not itself grant commit/archive authority or change task status.

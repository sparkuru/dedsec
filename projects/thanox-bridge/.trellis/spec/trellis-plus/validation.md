# Validation and review

## Available local checks

Trellis context and task records can be inspected with `python3 .trellis/scripts/get_context.py` and validated with `python3 .trellis/scripts/task.py validate <task-directory>`. Codex-local UUPM can be checked with `python3 .codex/skills/ui-ux-pro-max/scripts/search.py --help`; these commands do not validate the application. The Flutter analysis, widget tests, Android policy tests and debug build commands are recorded in [development.md](development.md). They are local checks and do not establish device-side Binder or permission behavior.

## Product verification

Exercise the actual requested operation and final state. Use unit or controlled-fixture tests for profile resolution, app/user targeting, error and partial-result handling, and backend contracts. A mocked Binder or Root path does not prove device behavior. For affected Android operations, use the selected PLR110 or a documented emulator/device, record Android/Thanox versions and activation, and distinguish simulation from physical-device evidence.

Use dedicated disposable test apps for cross-app changes. Record initial state, the effective caller and target user, perform only the authorized operation, independently read the result, restore initial state even on failure, and verify cleanup. Never bulk-change the user's existing apps or permissions merely to test. Test denied Root, unavailable Thanox, unsupported permission and partial batch results where they are relevant. Permission-use tests must record whether the target identity/context was actually achieved.

For native Flutter UI tasks, verify the changed interaction and states on Android using the established Flutter/device test path. Web Playwright is not a substitute for native Android behavior. If a browser-accessible product is later introduced, its first UI task must record a Playwright or existing-equivalent profile with startup/readiness, exact focused and CI commands, supported desktop/mobile viewports, fixtures, accessibility/visual policy and failure artifacts before submit-ready.

## Submit-ready review

Before commit or task completion, compare the implemented diff, acceptance criteria and real checks; classify human review as required, optional or unnecessary. Changes involving Root, Android permissions, security boundaries, destructive operations or unrun material checks require targeted human review of the residual risk. A purely mechanical, adequately verified documentation change needs no generic review. Reuse authorization already given, identify specific unverified behavior rather than asking for an undirected smoke test, and do not label blocked checks as passed.

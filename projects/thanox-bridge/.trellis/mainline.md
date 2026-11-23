# Trellis Mainline

## Initiative

- Title: Thanox Bridge.
- Objective: an on-demand Flutter Android application with a Kotlin integration layer that wraps Thanox and uses explicitly granted Root access for auxiliary operations.
- Android application ID: `im.majo.thanox_bridge` (the user-selected valid spelling of `im.majo.thanox-bridge`).
- Requirement authority: the user's 2026-11-20 requirements, preserved in `.trellis/tasks/archive/2026-11/00-bootstrap-guidelines/product-requirements.md`; those requirements are current, but do not themselves start product implementation.
- Parent task: none. Bootstrap is archived at `.trellis/tasks/archive/2026-11/00-bootstrap-guidelines/`; its archive commit is `dab839ffd4f2cb322330efe69759237cb99e6d0f`. That commit was independently exported and its task contexts, policy links and required files were verified without local platform state. The existing device-verification task remains separate.

## Current scope and acceptance

1. The app performs requested work while opened, without depending on a persistent background process or keepalive.
2. It can inspect one selected application's individual or full permission set and batch changes, distinguishing Android permissions, AppOps and any other supported controls.
3. It can apply reusable permission configurations across selected application groups, including mixed allow/deny and bulk-deny scenarios.
4. It supports Root authorization for auxiliary actions and provides permission-use tests that attempt to act in the target application's identity/context. The implementation must establish which identities and operations can actually be reproduced; unsupported or inconclusive tests must not be reported as successful simulations.
5. It provides one-click application of a template to a newly installed application when requested, while investigating reuse of Thanox's existing template behavior; automatic background monitoring is not assumed.
6. It provides extension points for later capabilities without presupposing a dynamic third-party loader. Flutter and Kotlin are selected; polished frontend design is deferred.
7. Acceptance for each feature requires demonstrated behavior on the applicable Android/Thanox versions, clear per-item outcomes for bulk work, explicit privilege identity, and recovery of task-created device changes.

## Evidence and boundaries

- `.trellis/tasks/11-20-verify-thanox-pro-api/research/device-proof.md` records an ordinary Android app reading Thanox interfaces and changing/restoring a separate test app's component on Android 16 + Thanox Pro 9.1-row via Xposed.
- That experiment does not establish complete API coverage, stable third-party support, Root impersonation, template reuse, other versions, or native plugin loading. Thanox's internal Binder and Android hidden-API behavior require compatibility checks.
- The first Flutter/Kotlin permission view has local analysis, tests and a debug APK build. On Android 16 + Thanox 9.1, separately signed ordinary-app probes verified selected CAMERA, RECORD_AUDIO and VIBRATE `IOps` modes, and the product UI changed each against disposable targets with independent Android readback and verified cleanup. CAMERA and RECORD_AUDIO `ALLOW_ALWAYS` were observed as foreground-only. The app list uses Android enumeration and exact-package lookup rather than an automatic Thanox catalog fallback, and its first read is user-initiated. On the designated device the user chose Allow in a separate app-list prompt and the product listed a dedicated target; the prompt owner and full catalog coverage remain unproven. The user-0 Thanox exact UID call was verified on a disposable target and an absent package. Details and limits are in `.trellis/tasks/11-20-verify-thanox-pro-api/research/permission-device.md` and `research/app-list-access-controls.md`.
- This is a new, unreleased project with an initial Flutter/Kotlin implementation but no web preview or established user-data migration contract. The designated phone is a real device, not disposable test data. Only a narrow device/version/target combination has been tested. Package fingerprints detected a same-UID update in a dedicated test; Android/Thanox exact UID checks add a further guard but neither check is atomic with a permission write. The service's exact UID and IOps resolvers have not been proven equivalent.

## Open decisions

- Which permission classes and observable access tests are supported first, and how target UID, process, SELinux domain and attribution are faithfully reproduced.
- How profiles, conflicts, partial failures, storage and recovery behave; these decisions must preserve the requested batch outcomes.
- Which Thanox template operations are available to the installed Pro version and how the wrapper's templates compose with them.
- Plugin boundary and packaging, minimum supported Android/Thanox versions, SDK versions and state management remain to be designed against evidence.

## Continuation

- Mode: `guided`. The user authorized Docker initialization and its commit, then selected application permission viewing and individual operations as the first product feature. The existing verification task is being reused for this work.
- On 2026-11-23, after the residual permission and identity risks were described, the user authorized committing the current work and recording its status. Targeted human review remains outstanding; keep the verification task in progress until that review is completed.
- The remaining profile, template, Root identity-test and extension work has no user-selected delivery order. Push remains outside the current authorization.
- Update this record when user-approved scope changes, after real validation, and when tasks are archived; do not equate source declarations or mocks with verified device behavior.

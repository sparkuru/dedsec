# Trellis Mainline

## Initiative

- Title: Thanox Bridge.
- Objective: an on-demand Flutter Android application with a Kotlin integration layer that wraps Thanox and uses explicitly granted Root access for auxiliary operations.
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
- This is a new, unreleased project with no product code, build manifest, UI, preview service, or established user-data migration contract. The designated phone is a real device, not disposable test data.

## Open decisions

- Which permission classes and observable access tests are supported first, and how target UID, process, SELinux domain and attribution are faithfully reproduced.
- How profiles, conflicts, partial failures, storage and recovery behave; these decisions must preserve the requested batch outcomes.
- Which Thanox template operations are available to the installed Pro version and how the wrapper's templates compose with them.
- Plugin boundary and packaging, minimum supported Android/Thanox versions, SDK versions and state management remain to be designed against evidence.

## Continuation

- Mode: `guided`; serial implementation authorization: none.
- The user authorized Trellis Plus bootstrap, local UUPM setup and a project-scoped delivery commit. Product implementation and push remain outside that authorization.
- The next product task is chosen by the user after initialization and normal planning; no order among permission, profile, template and extension work is invented here.
- Update this record when user-approved scope changes, after real validation, and when tasks are archived; do not equate source declarations or mocks with verified device behavior.

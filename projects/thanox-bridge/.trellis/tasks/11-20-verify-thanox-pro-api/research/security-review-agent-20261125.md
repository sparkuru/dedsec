# Independent agent security review

## Scope

On 2026-11-25 the user requested an independent sub-agent security review in place of human review for this task and authorized the main agent to act on its findings. This review was performed by a `trellis-check` sub-agent, not a human; the task-specific instruction does not change the project-wide review policy.

The review traced the current Flutter permission flow, Android method channel, package identity and UID validation, supported-operation policy, Thanox service connection and parcel handling. It considered the manifest, build configuration, tests and the recorded phone/board experiments, including the board APK bytecode and shared-UID evidence. It did not execute an exploit, inspect unrelated applications, contact a device or independently attest the code running in Thanox's Binder.

## Finding fixed: phone compatibility was broader than its evidence

- Severity: medium. Before the fix, any Android API 36 product with a service reporting `9.1` could select the phone permission-write profile; the prior selector did not require the phone product or installed package metadata. This could expose verified write modes on an untested ROM or a different Thanox package that happens to report the same service version.
- Evidence: the observed phone was `product:PLR110`, with `github.tornaco.android.thanos.pro` `9.1-row` (`versionCode=3380725`) and service `9.1`; see `device-proof.md` and `permission-device.md`. Those observations do not establish identical permission effects on other devices or versions.
- Fix: `ThanoxCompatibility.kt` now requires that product, Android API, service version, package name, version name and version code; `ThanoxService.kt` reads the selected package metadata, and `AndroidManifest.xml` explicitly declares the Pro package query. Missing or mismatched metadata rejects the connection. The Android 11 board profile retains its prior matching conditions. `ThanoxCompatibilityTest.kt` covers both accepted combinations and absent or incorrect phone/board metadata, products and service versions.
- Boundary: the phone was not reconnected in this review. The new phone package-visibility and connection path therefore has unit coverage but has not itself been confirmed on that device. Version metadata and a self-reported Binder descriptor/version/identity do not establish cryptographic Binder provenance.

## Remaining boundaries

- The checked package identity and the Android/Thanox UIDs are compared before and after the write, but the checks and the later service operation cannot be atomic. The board APK uses a mutable per-user UID cache and some permission helpers precede the AppOps UID lookup. A race or later failure may leave partial effects; the bridge reports post-attempt exceptions as uncertain rather than claiming rollback. No actual service replacement or UID race was observed. See `uid-resolver-bytecode-20261125.md` and `PermissionRepository.kt` / `PermissionPolicy.kt`.
- Declared shared-UID packages are read-only and are rejected in the native write path, including identity rechecks. This is conservative because a filtered package list cannot prove sole UID ownership. The previous phone experiment observed another shared-UID target change; the board instrumentation checked rejection with disposable fixtures. See `shared-uid-proof.md` and `same-root-device-20261125.md`.
- A particular inspected Thanox 9.6-prc write path has an untimed latch wait; a live hung transaction has not been observed. The bridge's single background worker can be held by a Binder call that does not return. A client timeout alone would not cancel or prove the absence of a delayed mutation, and safe cancellation/recovery was not established.
- `PermInfo` and exact-UID replies are checked for the expected shape, while operation code, name, permission, capability flags, target SDK, system-app status and selected mode are independently constrained. The checks depend on the selected service's behavior and do not establish identity or permission-use effects on untested Android/Thanox combinations.

## Checks

- Offline container Android unit tests: 33 passed.
- Offline container Android `lintDebug`: 0 errors, 4 warnings. The warnings concern the existing private `ServiceManager` reflection, Android backup-rule recommendation and obsolete resource qualifier.
- Offline container Flutter analysis: no issues; Flutter tests: 35 passed.
- The review did not run a new physical-device test. The main agent is separately responsible for any subsequent board validation and its evidence.

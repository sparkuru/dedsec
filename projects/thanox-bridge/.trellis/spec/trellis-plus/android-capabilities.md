# Android capability contracts

## Lifecycle and boundaries

The requested client works on demand while open; do not introduce a persistent app service, background keepalive or automatic app-install monitor as a prerequisite. Model Flutter-facing commands and results separately from Kotlin's Android, Thanox and optional Root interactions. Use a small extension seam justified by the requested future capabilities; plugin packaging and dynamic loading are not yet decided.

Treat direct Android APIs, Thanox Binder and Root as different authorities. Report which one performed an operation. A missing service, unsupported method, denied Root request, incompatible interface, partial batch or dead Binder is an explicit outcome; do not conceal it behind a success-shaped default or an unannounced privileged fallback. Do not expose an unrestricted Root or privileged Binder passthrough to other applications.

The first permission bridge checks the Android and Thanox versions and Binder descriptors before accessing `IOps`. A source declaration or successful component change does not establish permission-write behavior. Keep each operation/state pair disabled in both the UI and native bridge until the designated device has demonstrated a reversible write and independent readback for that pair; retain the evidence with the task. On failed writes or mismatched readback, report the observed result rather than a success-shaped default.

## Identity and permissions

Identify an app by package and Android user/profile, not package name alone; retain UID and effective identity where relevant. Permission enumeration and changes must distinguish declared/runtime permissions, AppOps and other platform controls rather than treating their state names as interchangeable. Determine actual availability per Android/ROM/Thanox version; list unsupported operations separately.

Root does not by itself prove that a probe has the target app's UID, SELinux domain, process context, attribution or effective permission. For each supported access test, establish the actual execution identity and observable action, compare expected and observed outcomes, and report unsupported, denied or inconclusive conditions. Do not equate a successful Root action, permission flag, mock or `checkSelfPermission` result with proof that the target application can exercise the underlying capability.

## Groups, templates and changes

Permission profiles describe selected apps and desired allow/deny states. Preserve the distinction between these user-requested batch presets and Thanox's event-driven Profile engine until an implementation is actually verified. Capture selected targets and prior states before mutation where the backend supports it; return per-app/per-permission success, failure and skipped results, including rollback failures. Do not claim atomicity or full reversibility without evidence.

One-click new-app templates are explicitly invoked; investigate Thanox's existing template APIs before duplicating behavior, and document version/capability gaps. Future extension features must state their authority, inputs, per-user targeting and failure semantics rather than gaining arbitrary ambient Root access.

## Observed baseline

`.trellis/tasks/11-20-verify-thanox-pro-api/research/device-proof.md` demonstrates read and reversible cross-app component changes from a separate ordinary UID on Android 16 with Thanox Pro 9.1-row and Xposed. This observation does not guarantee a stable SDK, full permissions coverage, other devices, Root identity simulation or native plugin loading. The local `archive/thanox-repo` is a reference checkout, not a tracked product dependency.

On the previously designated phone, an additional user-facing app-list prompt appeared even though Android's `QUERY_ALL_PACKAGES` check reported granted. One disposable caller without a recorded choice saw one entry; after reinstall under a different UID and a user-selected Allow, it saw 567 and the dedicated test target. The comparison did not isolate the control as the sole cause or prove complete coverage. The prompt's owner is unknown. See `.trellis/tasks/11-20-verify-thanox-pro-api/research/app-list-access-controls.md`.

## Scenario: first permission bridge

### 1. Scope / Trigger

This cross-layer contract applies to the Flutter permission view and Kotlin channel implementation. The observed operation and device limits are recorded in `.trellis/tasks/11-20-verify-thanox-pro-api/research/permission-device.md`.

### 2. Signatures

The `im.majo.thanox_bridge/permissions` method channel accepts `listApps()`, `findApp(packageName)`, `getAppDetails(packageName, userId, uid)` and `setOpMode(packageName, userId, uid, identityToken, code, mode)`.

The service connection resolves a verified compatibility profile. The phone profile requires Android API 36, product `PLR110`, service `9.1`, and installed `github.tornaco.android.thanos.pro` version name `9.1-row` and version code `3380725`. The development-board profile requires Android API 30, product `rk3568_firefly_aioj`, service `9.6`, and installed `github.tornaco.android.thanos` version name `9.6-prc` and version code `3404474`. Missing or mismatched package metadata fails closed. Explicitly declare both known Thanox packages in Android `<queries>` so checking their versions does not depend on reading the installed-app list. The tightened phone profile is supported by earlier device observations but has not itself been retested on that phone.

### 3. Contracts

Package names, modes and identity tokens are nonempty strings; user ID, UID and operation code are integers. `listApps` returns an `apps` array and coverage `notice` using only Android's current-user package enumeration; each app carries `label`, `packageName`, `uid`, `userId` and `sources`. Do not automatically supplement an empty, filtered or failed Android list using a privileged Thanox catalog: the user may have made a separate app-list access decision that the Android permission check cannot reveal. Do not infer such a decision or list completeness from the count. The notice can explain that device-specific access settings affect coverage. `findApp` performs an explicit exact Android package lookup and returns one current-user app or null. `getAppDetails` returns the identity token, Android declared-permission grant states, Thanox operations, and any service error or coverage note. Each operation includes its name, code, permission, observed state, runtime flag and allowed states. A write returns the requested state, observed state and whether they match; a returned match is based on Thanox readback, not a separate capability-use test.

The UI keeps exact lookup available when the list fails or when a different listed package only partially matches the entered package name. Display the list failure separately, and discard lookup results for superseded search text. After a write, if a fresh detail read has a changed or missing identity token, a missing operation, or a state different from the immediate readback, replace a success message with an uncertain outcome. A manual refresh clears stale success messages while retaining failure or uncertainty messages.

On first entry to the app-list screen, explain that reading the list can trigger a device access prompt and wait for the user's read-list action or explicit refresh before invoking `listApps`. Keep explicit exact-package lookup available before the list is loaded. Starting the read is not proof that the device prompt was allowed.

The Android manifest declares `QUERY_ALL_PACKAGES` because the feature lets the user choose among arbitrary installed apps; a fixed package `<queries>` declaration cannot represent that selection. Keep any `QueryAllPackagesPermission` lint exception scoped to this declaration and explain the product need in the manifest. Such a lint exception does not establish eligibility for a particular distribution channel's policy.

The identity token fingerprints package/user/UID, first-install and last-update times, version, source path and the set of current APK signing certificates. The fields are obtained with Android `PackageManager.GET_SIGNING_CERTIFICATES`; see the official [`PackageInfo`](https://developer.android.com/reference/android/content/pm/PackageInfo#signingInfo) and [`SigningInfo`](https://developer.android.com/reference/android/content/pm/SigningInfo#getApkContentsSigners()) contracts. Missing required metadata disables writes. The token is a stale-selection guard, not an independent authority or proof of atomicity.

A package declaring a shared user ID via `PackageInfo.sharedUserId` remains readable but has no writable Thanox modes. Native writes must reject that declaration before the Binder transaction and during identity rechecks; detail coverage text must identify the shared-UID restriction rather than mislabel it as missing signing metadata. Do not infer sole UID ownership from a potentially filtered package list. On the previously designated phone, changing CAMERA for one disposable package also changed another sharing its UID; see `.trellis/tasks/11-20-verify-thanox-pro-api/research/shared-uid-proof.md`.

For verified user 0, the native bridge also reads Thanox `IPkgManager.getUidForPkgName(Pkg)` using the checked descriptor and transaction 2; its reply after `readException` must contain exactly one integer. Compare this UID with Android before and after reading Thanox operations, and immediately before, after and following the readback of a write. Reject negative, wrong-user, mismatched, unavailable or malformed results. A failure after a write is attempted is uncertain. For other users, retain Android details but do not expose Thanox operations or writes.

Each logical details read or write uses one freshly verified Thanox root connection for both the IOps child and all its exact-UID queries. Do not substitute a separately connected root based only on a matching version/profile or cache a connection across independent requests. Check child descriptors as before. A child-interface or identity failure before a write prevents the mutation; after a write has been attempted, report uncertainty. The common root does not establish equivalent server-side resolvers, cryptographic service provenance or atomicity with Android package checks.

All four channel methods share a process-scoped serial coordinator. Normal overlapping requests wait in order, with at most 32 requests waiting behind the active one. The active request has a 60-second uptime deadline measured from worker execution, including any delay before the main thread handles its start notification. Main-thread blockage can delay delivery and device deep sleep pauses the uptime clock. On timeout, reply once with `operation_timeout`, reject waiting and new requests with `operation_busy`, and keep the active operation's gate closed until it actually returns. Waiting requests rejected this way must not run later. Discard late results. A write timeout must state that the outcome is uncertain, that the operation may still complete, and that restarting the client cannot prove cancellation; do not treat a subsequent read as proof that a delayed service-side mutation is impossible.

Destroying an Activity removes its unstarted work and its response references without claiming cancellation of a running call; recreating it in the same process must not bypass an active gate. An execution `Error` closes the coordinator for the remainder of the process, rejects unstarted and later requests, and reports a write as uncertain even if the `Error` arrives after the timeout. The recovery message must explain how to stop the application in system settings and must not imply that stopping it cancels a remote write. These are same-process protections; process termination does not establish the state of a service-side Binder call.

On the verified API 36 device, `PermInfo` is decoded as a 0/1 presence integer followed, when present, by a non-null state string, three 0/1 integer slots for capability flags, and a non-null access-summary string. Reject invalid markers, truncated or trailing data rather than enabling a mode from an incompatible reply. Individual unreadable operations remain unavailable; if all reads fail, report the service error.

### 4. Validation & Error Matrix

| Condition | Result |
| --- | --- |
| Other user, invalid UID, changed identity token, undeclared permission or unsupported state | Omit an invalid list entry or reject the write |
| Android/device/service/package combination outside a verified profile, incompatible descriptor, or failed Binder call | Report an error; retain separately readable Android permissions |
| Android list retrieval fails | Report the error; do not substitute another authority's list |
| Android returns an empty or filtered list | Show its result and coverage guidance; do not infer whether access was denied or silently fetch a privileged catalog |
| App-list screen has just opened | Show the read-list action; do not enumerate apps until the user starts it or refreshes |
| Thanox exact UID is missing, invalid, disagrees with Android, or applies to an unverified user | Keep Android details readable where possible; do not expose or write Thanox operations |
| Signing/install identity is missing | Keep details readable but expose no write modes |
| Package declares a shared user ID | Keep Android and Thanox details readable, identify the restriction, expose no write modes and reject a direct write |
| Identity changes after a Binder write is sent | Report an uncertain outcome and require a fresh state check |
| A required child interface or UID query fails before / after a write | Reject before mutation / report an uncertain outcome after an attempted mutation |
| Active read / write exceeds the response deadline | Return `operation_timeout`; for a write explicitly report an uncertain, potentially delayed result |
| Request waits behind a timed-out operation or the bounded queue is full | Return `operation_busy` and do not execute the rejected request |
| Activity is destroyed before its queued request starts | Return `operation_detached` where a reply remains available and do not execute it; a running request may continue |
| Active operation throws an execution `Error`, including after timeout | Return `operation_fatal` if a reply is still available; reject queued and later work for the rest of the process |
| Missing post-write state or mismatch | Report failure or the actual mismatch, not success |
| A later detail refresh changes the target identity, omits the operation, or disagrees with immediate write readback | Report the discrepancy or uncertain result, not stale success |
| List retrieval fails or only another package partially matches | Keep the error or partial matches visible and offer an independent exact lookup |
| Unverified operation, state, target SDK or system target | Show read-only; do not fall back to Root |

The bridge checks package/user/UID and the identity token before the write and again around readback. An update or replacement that reproduces every captured field may still evade the check; no check is atomic with the Binder transaction. Static inspection of the board's specific 9.6-prc APK found that its exact-UID interface and reached `IOps` AppOps paths call the same named cached resolver, with related initial service wiring. This does not prove the same object or cached value persists, that all permission helpers use it, or that the active Binder loaded this APK; it does not extend to other versions. Some runtime-permission actions precede the AppOps lookup. See `.trellis/tasks/11-20-verify-thanox-pro-api/research/uid-resolver-bytecode-20261125.md`.

### 5. Good / Base / Bad Cases

On the verified device for user 0, a non-system target-SDK-35 app declaring CAMERA or RECORD_AUDIO may request the proven foreground, ask, ignore and deny states when operation, permission and capability fields match. VIBRATE may request allow or ignore. Other operations remain visible where returned but read-only. CAMERA and RECORD_AUDIO `ALLOW_ALWAYS` requests are rejected: on the tested device Thanox returned foreground-only for those requests.

The preceding writable cases apply only when the package does not declare a shared user ID. A shared-UID package is read-only even if Android currently reports only one visible member; a direct channel request must not bypass this restriction.

For the verified API 30 board profile, only target SDK 30 and 35 have been tested. CAMERA and RECORD_AUDIO with flags background=false, runtime=true and oneTime=true may request `ALLOW_ALWAYS`, `ASK`, `IGNORE` and `DENY`; `ALLOW_FOREGROUND_ONLY` remains unavailable. VIBRATE with all three flags false may request `ALLOW_ALWAYS` and `IGNORE`. For both profiles, the operation code, reported operation name (`CAMERA`, `RECORD_AUDIO` or `VIBRATE`), permission and capability flags must all match the verified combination before exposing or accepting writes; a missing or mismatched name remains read-only. These are state/readback observations on the stated development board, not proof of physical camera, microphone or vibrator use. Other target SDK values, profiles or capability combinations remain read-only or unsupported.

### 6. Tests Required

Test that opening the screen does not enumerate, that starting or refreshing it does, and that exact lookup remains usable beforehand. Test Android enumeration, empty results, invalid/other-user entries and failures without a Thanox list fallback; exact-lookup races and partial-match collisions; independent lookup when the list fails; channel errors; post-write identity or state discrepancies; loading/disabled states; strict `PermInfo` decoding; UID mismatch and failed-write sequencing. Test native allowlist and metadata guards. For newly enabled operation/state pairs, use a disposable target on a designated device compatible with the affected profile, independently read Android permission and AppOps effects, restore where possible, and verify cleanup. The currently designated board verifies its own profile; earlier phone results do not establish board behavior or vice versa. Mocks alone do not establish device behavior.

Test that a shared-user declaration removes write modes while preserving readable details, that a direct write is rejected before mutation, and that a changed declaration during a guard recheck cannot be reported as confirmed success. On the designated device, verify the read-only presentation with the task's disposable shared-UID targets and independently verify their cleanup.

Test profile selection for both accepted combinations and rejection of wrong product, service version, missing or mismatched Thanox package/version name or version code, including the phone profile. Assert the two profiles' different CAMERA/RECORD_AUDIO mode sets, permit only target SDK 30 and 35 for the board, and reject missing or mismatched operation names, mismatched capability flags and unverified SDK values. On the board, verify product UI reads and individual writes against disposable targets with independent Android permission and AppOps readback; clean up the targets and probes. Unit selection tests do not establish that the tightened phone path can retrieve its package metadata on the physical phone.

Test that one logical request derives IOps and UID checks from the same root connection and handles child/UID failures on both sides of a write without reporting stale success. Exercise the resulting product and shared-UID write rejection on the designated board with independently verified disposable targets; these tests cannot prove server resolver equivalence or atomicity.

Test normal queue ordering and capacity, an active timeout with queued writes, delayed or racing start/completion notifications, a late result, Activity destruction before and after execution begins, failures delivering replies, ordinary execution failures, and an `Error` both before and after a timeout. Check that each live caller receives at most one result, that rejected writes never start, that a late fatal error keeps the gate closed, and that the Flutter interface retains uncertainty without exposing stale write controls while the coordinator is busy. A controlled test of an unresponsive call does not prove that a live Thanox service can be canceled.

For Android runtime coverage, the opt-in `-PrequestCoordinatorTest=true` instrumentation runner exercises the coordinator with a real main-looper `Handler`, worker thread and uptime clock, using in-process blocked actions and a short test-only deadline. The default runner and `-PsharedUidSafetyTest=true` runner must remain selectable, and enabling both opt-in runners must fail configuration. Verify both PASS markers and the instrumentation result code; a successful ADB process exit alone is insufficient. Do not treat this fixture as proof of a real Binder hang, full Flutter channel behavior, actual Activity destruction or cross-process cancellation.

### 7. Wrong vs Correct

Wrong: treat a successful Binder transaction as proof that Android granted the requested capability. Correct: read and display the actual Thanox state, keep Android's grant state separate, and report differences.

Wrong: when Android returns a small list, assume permission was denied and silently use Thanox to fill it. Correct: retain Android's visible result, give neutral coverage guidance, and leave any separate device access choice to the user.

Wrong: assume a package-specific request affects only the selected app because its UID was verified. Correct: reject writes for a declared shared UID until shared effects and a user-facing group operation are explicitly supported.

Wrong: acquire the IOps and UID-query interfaces from independently connected root services and treat matching version strings as proof of a common instance. Correct: derive both from the verified root held for the current logical request, without claiming that this verifies the child implementations.

Wrong: interpret a client timeout as cancellation and immediately run a queued permission write. Correct: report uncertainty, reject unstarted writes until the outstanding call returns, and preserve the service-side and cross-process limitations.

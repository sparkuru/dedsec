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

On the designated device, an additional user-facing app-list prompt appeared even though Android's `QUERY_ALL_PACKAGES` check reported granted. One disposable caller without a recorded choice saw one entry; after reinstall under a different UID and a user-selected Allow, it saw 567 and the dedicated test target. The comparison did not isolate the control as the sole cause or prove complete coverage. The prompt's owner is unknown. See `.trellis/tasks/11-20-verify-thanox-pro-api/research/app-list-access-controls.md`.

## Scenario: first permission bridge

### 1. Scope / Trigger

This cross-layer contract applies to the Flutter permission view and Kotlin channel implementation. The observed operation and device limits are recorded in `.trellis/tasks/11-20-verify-thanox-pro-api/research/permission-device.md`.

### 2. Signatures

The `im.majo.thanox_bridge/permissions` method channel accepts `listApps()`, `findApp(packageName)`, `getAppDetails(packageName, userId, uid)` and `setOpMode(packageName, userId, uid, identityToken, code, mode)`.

### 3. Contracts

Package names, modes and identity tokens are nonempty strings; user ID, UID and operation code are integers. `listApps` returns an `apps` array and coverage `notice` using only Android's current-user package enumeration; each app carries `label`, `packageName`, `uid`, `userId` and `sources`. Do not automatically supplement an empty, filtered or failed Android list using a privileged Thanox catalog: the user may have made a separate app-list access decision that the Android permission check cannot reveal. Do not infer such a decision or list completeness from the count. The notice can explain that device-specific access settings affect coverage. `findApp` performs an explicit exact Android package lookup and returns one current-user app or null. `getAppDetails` returns the identity token, Android declared-permission grant states, Thanox operations, and any service error or coverage note. Each operation includes its name, code, permission, observed state, runtime flag and allowed states. A write returns the requested state, observed state and whether they match; a returned match is based on Thanox readback, not a separate capability-use test.

The UI keeps exact lookup available when the list fails or when a different listed package only partially matches the entered package name. Display the list failure separately, and discard lookup results for superseded search text. After a write, if a fresh detail read has a changed or missing identity token, a missing operation, or a state different from the immediate readback, replace a success message with an uncertain outcome. A manual refresh clears stale success messages while retaining failure or uncertainty messages.

On first entry to the app-list screen, explain that reading the list can trigger a device access prompt and wait for the user's read-list action or explicit refresh before invoking `listApps`. Keep explicit exact-package lookup available before the list is loaded. Starting the read is not proof that the device prompt was allowed.

The Android manifest declares `QUERY_ALL_PACKAGES` because the feature lets the user choose among arbitrary installed apps; a fixed package `<queries>` declaration cannot represent that selection. Keep any `QueryAllPackagesPermission` lint exception scoped to this declaration and explain the product need in the manifest. Such a lint exception does not establish eligibility for a particular distribution channel's policy.

The identity token fingerprints package/user/UID, first-install and last-update times, version, source path and the set of current APK signing certificates. The fields are obtained with Android `PackageManager.GET_SIGNING_CERTIFICATES`; see the official [`PackageInfo`](https://developer.android.com/reference/android/content/pm/PackageInfo#signingInfo) and [`SigningInfo`](https://developer.android.com/reference/android/content/pm/SigningInfo#getApkContentsSigners()) contracts. Missing required metadata disables writes. The token is a stale-selection guard, not an independent authority or proof of atomicity.

For verified user 0, the native bridge also reads Thanox `IPkgManager.getUidForPkgName(Pkg)` using the checked descriptor and transaction 2; its reply after `readException` must contain exactly one integer. Compare this UID with Android before and after reading Thanox operations, and immediately before, after and following the readback of a write. Reject negative, wrong-user, mismatched, unavailable or malformed results. A failure after a write is attempted is uncertain. For other users, retain Android details but do not expose Thanox operations or writes.

On the verified API 36 device, `PermInfo` is decoded as a 0/1 presence integer followed, when present, by a non-null state string, three 0/1 integer slots for capability flags, and a non-null access-summary string. Reject invalid markers, truncated or trailing data rather than enabling a mode from an incompatible reply. Individual unreadable operations remain unavailable; if all reads fail, report the service error.

### 4. Validation & Error Matrix

| Condition | Result |
| --- | --- |
| Other user, invalid UID, changed identity token, undeclared permission or unsupported state | Omit an invalid list entry or reject the write |
| Android version other than API 36, incompatible service/descriptor/version, or failed Binder call | Report an error; retain separately readable Android permissions |
| Android list retrieval fails | Report the error; do not substitute another authority's list |
| Android returns an empty or filtered list | Show its result and coverage guidance; do not infer whether access was denied or silently fetch a privileged catalog |
| App-list screen has just opened | Show the read-list action; do not enumerate apps until the user starts it or refreshes |
| Thanox exact UID is missing, invalid, disagrees with Android, or applies to an unverified user | Keep Android details readable where possible; do not expose or write Thanox operations |
| Signing/install identity is missing | Keep details readable but expose no write modes |
| Identity changes after a Binder write is sent | Report an uncertain outcome and require a fresh state check |
| Missing post-write state or mismatch | Report failure or the actual mismatch, not success |
| A later detail refresh changes the target identity, omits the operation, or disagrees with immediate write readback | Report the discrepancy or uncertain result, not stale success |
| List retrieval fails or only another package partially matches | Keep the error or partial matches visible and offer an independent exact lookup |
| Unverified operation, state, target SDK or system target | Show read-only; do not fall back to Root |

The bridge checks package/user/UID and the identity token before the write and again around readback. An update or replacement that reproduces every captured field may still evade the check; no check is atomic with the Binder transaction. The service's exact UID lookup and `IOps` resolver have not been shown to share an implementation.

### 5. Good / Base / Bad Cases

On the verified device for user 0, a non-system target-SDK-35 app declaring CAMERA or RECORD_AUDIO may request the proven foreground, ask, ignore and deny states when operation, permission and capability fields match. VIBRATE may request allow or ignore. Other operations remain visible where returned but read-only. CAMERA and RECORD_AUDIO `ALLOW_ALWAYS` requests are rejected: on the tested device Thanox returned foreground-only for those requests.

### 6. Tests Required

Test that opening the screen does not enumerate, that starting or refreshing it does, and that exact lookup remains usable beforehand. Test Android enumeration, empty results, invalid/other-user entries and failures without a Thanox list fallback; exact-lookup races and partial-match collisions; independent lookup when the list fails; channel errors; post-write identity or state discrepancies; loading/disabled states; strict `PermInfo` decoding; UID mismatch and failed-write sequencing. Test native allowlist and metadata guards. For newly enabled operation/state pairs, use a disposable target on the designated phone, independently read Android permission and AppOps effects, restore where possible, and verify cleanup. Mocks alone do not establish device behavior.

### 7. Wrong vs Correct

Wrong: treat a successful Binder transaction as proof that Android granted the requested capability. Correct: read and display the actual Thanox state, keep Android's grant state separate, and report differences.

Wrong: when Android returns a small list, assume permission was denied and silently use Thanox to fill it. Correct: retain Android's visible result, give neutral coverage guidance, and leave any separate device access choice to the user.

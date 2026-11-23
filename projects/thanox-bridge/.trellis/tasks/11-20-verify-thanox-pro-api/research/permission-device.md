# Permission API device verification

On 2026-11-20 the designated `product:PLR110` reported Android API 36 and Thanox Pro `9.1-row` (`versionCode=3380725`); the `tv_input` service returned version `9.1`. The previous device proof established ordinary-app Binder access but did not establish the behavior of `IOps` permission reads or writes.

The reference source at `053888f56b8d07b964e848ce1ccb5f2b979bc969` declares `IThanos.getOpsManager`, `IOps.getPackagePermInfo`, `IOps.setMode`, the `Pkg` parcel fields and the `PermInfo` state/capability fields. The reference application enumerates codes 0–120 and derives available modes from the returned capability flags. These source observations are not a successful device mutation test.

Two separately signed, disposable packages were built offline for the device experiment: `dev.thanoxbridge.probe.perm20261120` and `dev.thanoxbridge.target.perm20261120`. Both were globally absent before the test. Incremental and streaming ADB installations timed out without a reported successful install. A subsequent push to the task-specific device path `/data/local/tmp/thanoxbridge-perm-target-20261120.apk` also timed out, after which the phone's wireless address had no network route. No test activity or permission mutation was executed. The ADB server initially reported a stale `device` entry and later stopped listing PLR110 entirely.

The probe and target manifests and probe source are retained in `permission-probe/`; the generated APKs and temporary signing keys are not retained. Build them in an isolated Android SDK container with `javac`, `d8`, `aapt`, `zipalign` and separate disposable signing keys. Place each manifest in a separate directory named `AndroidManifest.xml` for `aapt`. The probe is deliberately bound to the exact temporary target package and Android user 0.

The subsequent sections record the checks and experiments performed after connectivity was restored.

## First IOps observation after reconnection

After reconnection, both prior package names and the temporary APK path were absent. The device's app installer intercepted ADB installation and required a separate tap on `INSTALL` for each test APK; the automatic-install checkbox was not enabled. The two installed APK hashes matched the task-generated files. The probe ran as UID 10511 and the separately signed target as UID 10512 on Android user 0.

For operation 26, Thanox reported `CAMERA` and `android.permission.CAMERA`. Initially `PermInfo` was `NOT_SET`, reported runtime/background/one-time support, and Android reported the permission denied. `DENY` produced `DENY` and Android denied. A request for `ALLOW_ALWAYS` produced `ALLOW_FOREGROUND_ONLY` while Android reported the permission granted. A subsequent `DENY` produced `DENY` and Android denied. The probe's fallback restoration for initial `NOT_SET` was `DENY`; Android's grant returned to denied, but its `USER_SET`/`USER_FIXED` flags differed from the original state. The target was disposable and was uninstalled, so its package-specific permission state was removed rather than claiming exact flag restoration.

Both test packages were subsequently uninstalled and exact-package checks reported them absent. Both task-specific `/data/local/tmp/thanoxbridge-perm-*-20261120.apk` paths were absent. This establishes the observed calls and their mismatch on this device, not a guarantee for other operations, modes, apps, Android releases or Thanox versions.

## Staged runtime-permission and AppOps observations

A second probe used the separately signed target with target SDK 35 and declared CAMERA and VIBRATE permissions. The probe ran as UID 10514 and the target as UID 10513. Its APK SHA-256 was `44641c14129c69ea2ef1afdd5275d051fc9166bb1714bd8ad43a8fe4fb224581`; the target APK SHA-256 was `66610fb0a622afb995fb5e055cda1abbc4a2111bd67a83038dd8502d1b1b8e8f`. Each state change was followed by both the ordinary-app Android package manager's `checkPermission` and independent ADB reads of package permission flags and AppOps state.

| Operation and requested state | Thanox-reported state | Android permission / AppOps observation |
| --- | --- | --- |
| CAMERA initial | `NOT_SET` | Permission denied; UID mode `ignore` |
| CAMERA `ALLOW_FOREGROUND_ONLY` | `ALLOW_FOREGROUND_ONLY` | Permission granted; UID mode `foreground` |
| CAMERA `DENY` | `DENY` | Permission denied; UID mode `ignore` |
| CAMERA `ALLOW_ALWAYS` | `ALLOW_FOREGROUND_ONLY` | Permission granted; UID mode `foreground` |
| CAMERA `IGNORE` | `IGNORE` | Permission still granted with `REVOKED_COMPAT`; UID mode `ignore` |
| CAMERA `ASK` | `ASK` | Permission denied with `ONE_TIME`; UID mode `ignore` |
| VIBRATE initial | `ALLOW_ALWAYS` | Normal permission granted; default AppOps mode `allow` |
| VIBRATE `IGNORE` | `IGNORE` | Android permission remained granted; AppOps mode `ignore` |
| VIBRATE `ALLOW_ALWAYS` | `ALLOW_ALWAYS` | Android permission remained granted; AppOps mode `allow` |

The original CAMERA `NOT_SET` flags were not reconstructed exactly; the test ended with `DENY` and the disposable target was uninstalled. VIBRATE returned to effective `allow`, although the final explicit AppOps entry differed from the original default entry; uninstall removed the fixture's state. Both exact packages and both task-specific temporary paths were independently checked absent after cleanup. The observed modes apply to this target, its declared permissions and target SDK, and the stated Android/Thanox versions; other combinations remain unverified.

## Product flow on the designated phone

The debug product, then named `dev.thanoxbridge.thanox_bridge`, and the separately signed target were installed for user 0 after checking that their exact package names were absent. The target ran as UID 10515. The product searched for the exact target package and displayed Android CAMERA and VIBRATE grants separately from the Thanox operation states. Through the product UI, CAMERA was changed from denied to `ALLOW_FOREGROUND_ONLY` and then back to `DENY`. Independent `dumpsys package` and `cmd appops get --user 0` reads showed, respectively, CAMERA granted with UID mode `foreground`, then CAMERA denied with UID mode `ignore`. The initial `NOT_SET` permission flags were not exactly reconstructed; uninstalling the disposable target removed its package-specific state. The application ID was subsequently changed to the user-selected `im.majo.thanox_bridge`.

The updated debug APK, SHA-256 `04cc6386b6893bf77875f0be5ccf8dc63dae955761a13fa47788af2819e4faa2`, was independently matched to the installed APK. Its UI placed the two editable operations before read-only operations. VIBRATE was changed from `ALLOW_ALWAYS` to `IGNORE` and back via the product: the UI reported each observed state, and independent AppOps reads showed `ignore` and then `allow`. Android continued reporting VIBRATE granted; `REVOKED_COMPAT` appeared while it was ignored. CAMERA was denied at cleanup. As in the probe, effective VIBRATE `allow` after restoration did not establish restoration of the original default AppOps entry.

The exact target, product and probe package names were checked with `dumpsys package` after uninstall and all reported absent. The two task-specific `/data/local/tmp/thanoxbridge-perm-*-20261120.apk` paths were also absent. No pre-existing application was selected for a write. These observations apply to this disposable target and device combination; they do not prove every target app or any other Android/Thanox release.

After the application ID change, `aapt` identified the rebuilt debug APK as `im.majo.thanox_bridge`, with launcher `im.majo.thanox_bridge.MainActivity`. Its SHA-256 was `ad28e587b3dc5cdb9ff9fcc5a2d2ff3adf190b3d26a4d4bd6354166e9cae000d`, and the installed APK matched that hash. This package was absent before the test. On PLR110 it launched as user 0 / UID 10517, listed itself, and displayed its own Android permissions and Thanox AppOps. This post-rename check was read-only; the prior cross-app writes used the old debug package identity. The new package was uninstalled, and an exact-package check reported it absent.

## Identity and catalog continuation

For a same-UID update test, a new disposable target `im.majo.thanoxbridge.test.identity20261120` was built as versions 1 and 2 with the same temporary signing key. Version 1 ran as user 0 / UID 10519. A temporary product build explicitly exposed only this target in its list because the standard product's Android enumeration did not show it. With the version-1 details open, the product set VIBRATE to `IGNORE`, independently read `ignore`, and restored effective `allow`. The target was then updated to version 2; UID remained 10519 and the update time changed. From the still-open version-1 page, an `IGNORE` request was rejected with an identity-change message, and an independent AppOps read remained `allow`. The exact original default AppOps entry was not reconstructed; uninstall removed the fixture state. The temporary build's explicit query and list injection were removed from the product source after the experiment.

The standard product's `QUERY_ALL_PACKAGES` was reported granted, yet its Android application enumeration omitted the installed test target. Direct Android package lookup of the same target succeeded. A request to set `QUERY_ALL_PACKAGES` AppOps for the disposable product package failed because shell UID 2000 lacked `MANAGE_APP_OPS_MODES`; no mode change was made. The cause of the differing enumeration remains unknown.

An independently signed ordinary-app probe, `im.majo.thanoxbridge.test.catalogprobe20261120`, compared only counts and the exact disposable target, without logging other package names. As UID 10521 it observed 567 Android entries and the target; the verified Thanox 9.1 `IPkgManager.getInstalledPkgs(255)` returned 580 entries and the same user-0 target UID 10520. The totals have different possible scopes and do not prove complete coverage. Probe code and manifest are in `catalog-probe/`.

The normal product build subsequently merged the sources, displayed the target with source `Thanox`, rechecked UID 10520 through Android, and opened its permission details. VIBRATE was set to `IGNORE` and restored to effective `ALLOW_ALWAYS`; independent AppOps reads showed `ignore` then `allow`. The tested APK SHA-256 was `c59374f23ce25481e9739d130d16d3ce5b29a79de2049209c24e422fbcd07ff0` and matched the installed file. After the test, a UI overflow fix and earlier rejection of non-current-user catalog entries were made and separately tested; the recorded device hash is not claimed to represent those later source changes. The target, catalog probe and product were uninstalled and exact-package checks reported all three absent. Temporary signing keys, APK fixtures and task screenshots were removed from the host.

## Microphone operation continuation

On the same Android 16 / Thanox Pro 9.1-row device, a new disposable non-system target, `im.majo.thanoxbridge.test.microphone20261120`, declared only `android.permission.RECORD_AUDIO` and targeted SDK 35. It contained no executable code. Its separately signed ordinary-app probe, `im.majo.thanoxbridge.test.audioprobe20261120`, called only operation 27 for that exact target after checking the service, operation name, permission, Android version, user and target UID. The caller was user 0 / UID 10524 and the target was user 0 / UID 10523. Probe and manifest sources are in `microphone-probe/`. The installed target APK SHA-256 was `74f5557834e1e7b3d998636537dfa892ac36dd87ee05f31ec39987f283e5448b`; the probe APK SHA-256 was `e8c6a998d9eaccf69c128b3fa234d25b0b9fc099f1c8b8dc5010ed290b6f9939`.

The operation reported `RECORD_AUDIO`, permission `android.permission.RECORD_AUDIO`, and true for each of the runtime, background and one-time capability fields. Following each staged request, the probe read Thanox's state and Android's `checkPermission`; ADB independently read the package permission and AppOps. `RECORD_AUDIO` is documented by Android as a [dangerous permission](https://developer.android.com/reference/android/Manifest.permission#RECORD_AUDIO); an AppOps [`MODE_FOREGROUND`](https://developer.android.com/reference/android/app/AppOpsManager#MODE_FOREGROUND) result is a separate control.

| Requested stage | Thanox state | Android permission / AppOps observation |
| --- | --- | --- |
| Initial read | `NOT_SET` | Permission denied; UID mode `ignore` |
| `ALLOW_FOREGROUND_ONLY` | `ALLOW_FOREGROUND_ONLY` | Permission granted; UID mode `foreground` |
| `DENY` | `DENY` | Permission denied; UID mode `ignore` |
| `ASK` | `ASK` | Permission denied with `ONE_TIME`; UID mode `ignore` |
| `IGNORE` after foreground allow | `IGNORE` | Permission still granted with `REVOKED_COMPAT`; UID mode `ignore` |
| `ALLOW_ALWAYS` | `ALLOW_FOREGROUND_ONLY` | Permission granted; UID mode `foreground` |
| Final `DENY` | `DENY` | Permission denied; UID mode `ignore` |

The original `NOT_SET` flags were not reconstructed exactly. The table reports state and permission checks only: the target had no code and did not attempt microphone capture. It does not establish behavior for other target apps, devices, Android releases or Thanox versions.

The final debug product APK, SHA-256 `c6a75659e1557d75b38f8c9879bd2a565f25e7dc8ca1c80cd5d428f715d959c2`, matched the installed APK. Its app list found the exact target via Thanox; the detail page displayed the Android permission and Thanox state separately and offered only foreground, ask, ignore and deny. The product changed RECORD_AUDIO from `DENY` to `ALLOW_FOREGROUND_ONLY` and back to `DENY`, displaying each readback. Independent Android package/AppOps reads showed, respectively, granted with UID mode `foreground`, then denied with UID mode `ignore`. These reads do not prove actual audio capture or non-capture.

Before this test, the exact target, probe and `im.majo.thanox_bridge` product package names were absent. After testing, all three task-installed packages were uninstalled and exact-package checks reported them absent. Uninstall removed the target's package-specific state; it was not a reconstruction of the initial permission flags. Task-specific temporary APKs, signing keys and screenshots were also removed. Flutter formatting and analysis, 16 widget tests, 10 Android unit tests, and the Android debug APK build passed through `./hako`.

## Package visibility comparison

On the same designated device, a separately signed, task-created target `im.majo.thanoxbridge.test.visibletarget20261120` initially had no code or permissions; a second task-created app used UID 10527 to query target UID 10526 for user 0. The probes and target were absent before installation. Their exact APK hashes matched the installed files. Each probe enumerated Android applications and packages before the direct target lookup and logged only counts, its own UID, the dedicated target's UID, and visibility results. Sources are in `visibility-probe/`.

| Same probe UID and signer | Android application count / target listed | Android package count / target listed | Direct target UID | `QUERY_ALL_PACKAGES` grant |
| --- | --- | --- | --- | --- |
| Version 1: broad permission, no explicit target query, debuggable | 1 / no | 1 / no | 10526 | granted |
| Version 2: add explicit target query, debuggable | 1 / no | 1 / no | 10526 | granted |
| Version 3: explicit target query, not debuggable | 1 / no | 1 / no | 10526 | granted |

Android documentation describes [package visibility filtering](https://developer.android.com/training/package-visibility) and the intended [broad query permission](https://developer.android.com/reference/android/Manifest.permission#QUERY_ALL_PACKAGES). These three observations show a difference between enumeration and exact lookup for this target, not why this ROM produced it or whether another app, process, device or moment behaves the same. The earlier `catalog-probe/` result observed 567 Android entries with a different caller and explicit target query; these tests do not isolate the reason for that difference. A read-only AppOps query for this probe showed no recorded operation and default mode `default`; no setting was changed.

After the comparison, the same task-owned target was updated to version 2, adding only VIBRATE, for product UI refresh validation. Its initial Android grant was true and AppOps was default `allow`. The final product APK SHA-256 was `adbc90e8f87e87ee38ab97a233d10335721d870b180b61bd4be7b10b231452ab` and matched the installed file. The product found the target via Thanox, verified its UID, decoded the real VIBRATE operation, changed it to `IGNORE`, and displayed the readback; independent AppOps reads showed `ignore`. Manual refresh preserved the current state and removed the previous success notice. The product then restored `ALLOW_ALWAYS`, with AppOps independently reading `allow`. This restored the effective mode, not necessarily the original default AppOps entry. A lookup of the independently absent test package produced the explicit not-found result.

The task target, probe and product were checked as installed for user 0 and not installed for the observed user 999, then uninstalled. Exact-package checks reported all three absent. Temporary signing keys, fixture APKs and screenshots were removed from the host; uninstall removed the target's package-specific state. No actual UID conflict was created, and the target did not exercise vibration hardware.

## Thanox exact UID comparison

The same separately signed probe was updated to version 4, preserving user 0 / UID 10527 and checking only the task-owned target and an independently confirmed absent test package. It checked Android API 36, the Thanox root and package-manager descriptors, version `9.1` and service identity. `IPkgManager.getUidForPkgName(Pkg)` transaction 2 returned exactly one integer: for the installed target, both Android and Thanox returned UID 10526; for the absent test package, Android returned `NameNotFoundException` and Thanox returned `-1`. The installed probe APK SHA-256 was `1c71e4ac5ef35ff82244782884677960fcfc0b7504be4226a7458a7da8fe5277` and matched the built file. Its source and manifest are `visibility-probe/UidProbeActivity.java` and `visibility-probe/UidProbeManifest.xml`.

This verifies that one ordinary caller on the designated device can read those two user-0 outcomes. It does not demonstrate a real conflicting target, other Android users or versions, or prove that Thanox `IOps` uses the same server-side resolver. Any resulting comparison is a non-atomic consistency check rather than an authorization or atomicity guarantee.

## Runtime operation decoder check

A separately signed, code-free target `im.majo.thanoxbridge.test.runtimedecode20261120` declared only CAMERA and RECORD_AUDIO and used target SDK 35. Its UID was 10529 for user 0. Its APK SHA-256 `a4b18f8961bd4116889c8358ea3525c234c77431a702f781a703af9d42ce5560` and the final product APK hash above matched the installed files. The product displayed both Android permissions as denied and decoded both corresponding Thanox operations as `NOT_SET`, with the verified operation controls available. This was read-only; independent package and AppOps checks showed both permissions denied and UID modes `ignore` before and after the UI check. It does not prove camera or microphone use, or the rejection of an actual malformed Binder reply on the device.

Both task-installed packages were uninstalled and exact-package checks reported them absent. The task-specific APK, temporary signing key and screenshots were removed from the host. For the sources at that point, Flutter format and analysis, 26 widget tests, 20 Android JVM tests and the debug APK build passed through `./hako`.

## App-list access prompt and Android-only product list

On 2026-11-23 the user supplied a screenshot of an app-specific “Read your app list” Allow/Decline prompt. A disposable diagnostic's Android permission check was granted; one pre-choice installation returned one enumeration entry, whereas a reinstalled caller with a different UID returned 567 and matched the dedicated target after the user selected Allow. This is not a same-UID deny/allow comparison and the prompt's provider and complete coverage remain unknown. The diagnostic was removed; see `app-list-access-controls.md` for identities and limits.

The updated product APK `d7462f6d796f96e3cd04c3d36bcf2e78357b80ef81a7bb80140d720b013e8f73` was installed temporarily as `im.majo.thanox_bridge` for user 0 / UID 10535, after confirming that exact package was absent. Its installed APK hash matched the build. The user reported that its first app-list prompt disappeared before a choice and did not recur on refresh; this is not evidence of an explicit denial. After filtering the product UI to the separately installed task target `im.majo.thanoxbridge.test.appaccesstarget20261123` (UID 10532), the list showed the neutral Android coverage notice and no matching visible list entry. An explicit exact-package lookup then displayed that target with its correct user 0, UID and Android source. UI inspection was filtered on-device to the search control, neutral notice and dedicated target; no other package names were recorded. This was read-only and did not call a Thanox catalog fallback.

The product was subsequently removed from user 0 and reinstalled under UID 10536 so that the user could open it and choose the first-run prompt. The user again reported that no choice was made because the prompt did not appear or disappeared too quickly. That product build triggered `listApps` during screen construction, whereas the disposable diagnostic started its query on a user tap.

The product was rebuilt to wait for an explicit read-list action. Its APK SHA-256 `cbb8ec199918f4b46de47092e377ce4dccb9adc054c5b5f3411548870261d25b` matched the installed file; it was reinstalled as user 0 / UID 10537. The user reported tapping the read action and selecting Allow. Filtering the product's Android list to the dedicated target showed its label, exact package, user 0 and UID 10532 directly, with source Android and the neutral coverage notice; no exact-lookup fallback was used. Opening that target showed its matching identity and Thanox AppOps detail. No permission write was made. This confirms the exercised flow for this caller and choice, not complete list coverage or the prompt provider.

For the latest sources, `./hako flutter analyze`, 35 Flutter tests, 20 Android JVM tests and the debug APK build passed. The later Flutter test-only changes did not alter the product source used for the device-tested APK. Android `lintDebug` then passed with zero errors and four warnings after a scoped, documented `QueryAllPackagesPermission` lint exception for the arbitrary-app management use case; the warnings concern non-SDK API access, backup extraction rules and a redundant resource qualifier. This exception does not assert distribution-policy eligibility.

The final post-lint debug APK SHA-256 is `9718e7515a8c9de5d2eba3f7fca27721ff536e90cfa3827a27bbb88d3b999cfb`; it was not separately installed on the phone. Compared with the device-tested `cbb8ec…` build, aapt manifest trees were identical after removing source line numbers, the permission sets matched, and `classes*.dex`, the Flutter kernel blob and the arm64 Flutter library had identical entry hashes. This comparison does not assert that every APK entry was identical.

The task-installed product, target and diagnostic were uninstalled from user 0. Exact `dumpsys package` checks subsequently reported all three absent. Task-specific host build fixtures and temporary screenshots were removed. No claim is made about whether a separate device controller retains historical decisions after uninstall.

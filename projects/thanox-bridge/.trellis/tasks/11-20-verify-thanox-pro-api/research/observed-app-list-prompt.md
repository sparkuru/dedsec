# Research: Observed app-list permission prompt

- Query: Identify the reported PLR110 app-list prompt and its settings, and design a comparison confined to the disposable diagnostic app.
- Scope: mixed; local source and records plus official Android and ColorOS documentation. This research did not access or change the device.
- Date: 2026-11-23

## Findings

### Local evidence

- The parent supplied the screenshot's transcription: the prompt names `Thanox App List Diagnostic`, requests “Read your app list”, gives a service-recommendation explanation, and offers Decline / Allow. This researcher did not independently inspect the screenshot. It shows an access request for the diagnostic label, but does not identify the window-owning package, a specific implementation, which option was selected, or its effective enforcement state.
- `.trellis/tasks/11-20-verify-thanox-pro-api/research/app-list-access-probe/ProbeManifest.xml:7` declares `QUERY_ALL_PACKAGES` and `:11` supplies that diagnostic label. `AppListAccessActivity.java:96` runs the check on a background thread; `:132` and `:150` enumerate applications and packages, `:170` looks up the fixed task-owned target, and `:177` checks the Android permission. The checked-in source is a simple diagnostic and does not implement a recommendation feature. The prompt's claimed purpose must not be presented as a finding that this diagnostic recommends services, or as proof of who authored the prompt.
- Device observations and the exact tested APK identities are recorded in `app-list-access-controls.md`. An older installed build yielded one entry for each Android enumeration; its `false` target match did not rule out a redacted/negative UID. After reinstalling the corrected build under a new UID, the user chose Allow and both enumeration calls returned 567 entries and matched the dedicated target UID; a repeated query under that UID gave the same result. An earlier unresolved prompt is not evidence of an explicit Decline, and reinstall and UID changes prevent treating the two results as a single-variable comparison.
- `archive/thanox-repo/android/android_framework/base/src/main/java/github/tornaco/android/thanos/core/secure/ops/AppOpsManager.java:551` defines Thanox's separate custom installed-packages operation, and the archived Chinese label is at `archive/thanox-repo/android/android_framework/res/src/main/res/values-zh-rCN/app_strings.xml:742`. No evidence links it to this particular prompt or proves it is active here; the screenshot alone cannot attribute the prompt to Thanox, ColorOS, or another controller.

### Official references and limits

- Android documents [`QUERY_ALL_PACKAGES`](https://developer.android.com/reference/android/Manifest.permission#QUERY_ALL_PACKAGES) as a **normal** permission. A granted result from Android's `checkSelfPermission` does not establish the state or effect of an additional vendor or third-party control. Android documents [package visibility filtering](https://developer.android.com/training/package-visibility) for apps targeting API 30 or later.
- The official [ColorOS 13 example](https://www.coloros.com/instruction?id=858&version=ColorOS%2013) uses “设置 > 权限与隐私 > 权限管理” for a location permission; the [ColorOS 7 guide](https://www.coloros.com/instruction?id=258&version=ColorOS%207) describes selecting an application or permission and changing its handling. These establish generic entry points on those releases only. They do not prove that PLR110's Android 16 build exposes this particular control there or that the screen is the prompt's provider. Inspect the visible UI, select only the diagnostic app and stop if the matching control cannot be identified.
- BrowserOS neo was used for the public-document search; neither the official materials examined nor the local source identify the exact owner of the provided prompt or a confirmed per-package setting for this device.

### Privacy-preserving same-identity comparison

1. Fix the exact installed diagnostic APK hash, signer, UID and Android user; use the same independently identified task-created target. Do not clear app data, uninstall/reinstall, change the APK, or alter other permissions between measurements. If the corrected probe APK is installed as an in-place update first, repeat the baseline with it before changing the permission; do not compare target-match semantics across the two builds as if they were identical.
2. Record whether a prompt is still awaiting a choice and whether the current background query has already returned. An unselected or automatically dismissed prompt is **unknown**, not Decline. With the user's agreement for the temporary diagnostic only, explicitly choose one visible state, rerun the button after the state is settled, and record the button choice, Android grant, counts, unknown-UID count and task-target match. If an initial query completed before a choice, retain it only as the pre-choice observation.
3. Only if the matching control can be identified for that same diagnostic package, and the user agrees to the temporary change, set the opposite state through its visible per-app prompt or settings and rerun the same measurements. A repeat of the first state, when available, strengthens the comparison. If the setting or second state cannot be found, report the observed before/after case as inconclusive for a full Decline/Allow comparison; do not reset or work around the restriction to force one.
4. `getInstalledApplications` / `getInstalledPackages` can return the user's broader app list to the diagnostic in memory if access is allowed. The test should disclose this and keep its existing output limited to totals and the separately created target's UID match; never persist unrelated package names, collect broad logs, enable package-visibility logging, or query a privileged Thanox catalog to bypass the choice. An authorization for this diagnostic is not authorization for the product.
5. A result changing with the one identified setting supports an effect on this caller under the tested conditions, not proof of complete coverage. Verify the same UID and APK for both sides. After the authorized comparison, uninstall only task-created apps and remove only task-specific files, verifying their exact absence; do not claim to restore unrelated device state.

To identify the window provider, any follow-up inspection should be limited to the visible prompt's owning package or resource identifiers, avoid retaining unrelated window content, and treat ambiguous overlay ownership as inconclusive. No unverified hidden service interface is required for the comparison.

### Related specs

`.trellis/spec/trellis-plus/android-capabilities.md:13` distinguishes permissions, operations and Android user identity. `.trellis/spec/trellis-plus/validation.md:9` requires device evidence and disposable test applications for changes.

## Caveats / Not Found

- Exact prompt owner, underlying permission/operation, persistence semantics, available settings entry and causal relationship to the one-entry enumeration remain unverified on PLR110.
- The described experiment has not been performed as part of this research. The supplied screenshot transcription and existing device observations are not a completed explicit Decline/Allow comparison.

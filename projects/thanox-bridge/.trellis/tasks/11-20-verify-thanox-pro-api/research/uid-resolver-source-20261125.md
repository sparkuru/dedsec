# Research: Thanox package UID and operation resolver boundaries

- Query: Can the local Thanox source establish that the board's exact-UID query and permission reads/writes resolve the same package, user and UID, and what does it show about UID-scoped effects?
- Scope: internal source and existing device records; no new device actions or remote research.
- Date: 2026-11-25.

## Files found

- `archive/thanox-repo/android/android_framework/base/src/main/java/github/tornaco/android/thanos/core/pm/Pkg.java` — parcelable package-name/user-ID input used by both interfaces.
- `archive/thanox-repo/android/android_framework/base/src/main/java/github/tornaco/android/thanos/core/pm/IPkgManager.aidl` — declares the per-package UID query.
- `archive/thanox-repo/android/android_framework/base/src/main/java/github/tornaco/android/thanos/core/ops/IOps.aidl` — declares package permission reads and writes.
- `archive/thanox-repo/android/android_framework/base/src/main/java/github/tornaco/android/thanos/core/ops/OpsManager.kt` and `core/pm/PackageManager.java` — client-side forwarding wrappers.
- `archive/thanox-repo/android/settings.gradle.kts` and `.gitmodules` — identify the separate internal source location for the service modules.
- `android/app/src/main/kotlin/im/majo/thanox_bridge/{ThanoxService,ThanoxOps,ThanoxPackageIdentity,PermissionRepository,PermissionPolicy}.kt` — this bridge's connection, write and identity guards.
- `shared-uid-proof.md` and `board-compatibility.md` — earlier, separately scoped observations on the phone and development board.

## Findings

### Interface input is common, but server resolution is not visible

`Pkg` stores only `pkgName` and `userId`, serializes them in that order, and `Pkg.fromAppInfo` selects the app's package name and user ID (`Pkg.java:13–25,44–47,87–88`). `IPkgManager.getUidForPkgName` consumes a `Pkg` (`IPkgManager.aidl:10–12`), and both `IOps.getPackagePermInfo` and `IOps.setMode` also consume a `Pkg` (`IOps.aidl:3–5`). Therefore the IOps request does not itself carry a caller-supplied target UID. The interface declarations alone do not reveal how the server interprets the package/user pair, whether it looks up a UID, or whether that lookup matches `getUidForPkgName`.

The public `PackageManager.getUidForPkgName(Pkg)` forwards to its remote manager (`core/pm/PackageManager.java:72–75`), and `OpsManager` forwards both operation methods to `ops` (`core/ops/OpsManager.kt:59–65`). The Thanox UI likewise calls these wrappers using `Pkg.fromAppInfo` for reads and writes (`android/app/src/main/java/github/tornaco/thanos/android/ops2/byapp/AppOpsListViewModel.kt:71–75,105–107`). These are client-side paths, not the implementation of the receiving service.

Searching the available local checkout, including ignored/hidden files but excluding Git internals, found no implementation of `IPkgManager.Stub` or `IOps.Stub` and no implementations of the target methods beyond the shown interfaces, wrappers and callers. `android/settings.gradle.kts:66–103` lists `android_framework:services` and version-specific service modules as internal projects and resolves them under `android/internal/Thanox-Internal`; `.gitmodules:1–3` declares that location as a submodule, and its directory is empty in this checkout. This is evidence that the necessary implementation was not found in the provided files, not proof of where every shipped implementation resides or that it has a particular behavior.

### What the bridge actually checks

The bridge sends the package name and user ID separately to the UID and IOps interfaces (`ThanoxPackageIdentity.kt:22–33`; `ThanoxOps.kt:21–34,51–55`). It checks Android package identity and shared-user declaration before a write, and repeats its guards around the operation and readback (`PermissionRepository.kt:110–143`; `PermissionPolicy.kt:73–74,82–101`). These are valuable client-side checks, but they cannot make the later service operation atomic with the Android check or prove that its internal resolver uses the same lookup.

Before this continuation, `ThanoxOps.connect` obtained the IOps child from one `ThanoxService.connect` result while each exact-UID query connected again and only compared its compatibility profile. Matching versions/profiles did not show that both connections referenced the same root Binder. No service replacement was observed in the existing device records.

The continuation now retains one validated `ThanoxService.Connection` for a logical details or write operation and derives both the IOps and package-manager child interfaces from that root Binder, preserving descriptor checks and treating later Binder failure as uncertain where a write might already have occurred. Controlled Binder checks and board results are recorded in `same-root-device-20261125.md`. This narrows the distinct-root-connection problem; it does not prove that child services implement equivalent UID resolution, bind the returned children cryptographically to the installed APK, eliminate the package-change race, or detect every service replacement.

### UID-scoped behavior and observed evidence

The checkout also declares a different, legacy `IAppOpsService.setMode(code, uid, packageName, mode)` (`core/secure/ops/IAppOpsService.aidl:7–12`) and a direct forwarding wrapper (`core/secure/ops/AppOpsManager.java:1240–1257`). This is a separate interface from `IOps`; its presence must not be used to infer that `IOps.setMode` invokes it or uses a particular per-UID Android API.

The prior phone experiment independently observed that a CAMERA change naming fixture A also affected fixture B with the same UID (`shared-uid-proof.md:5–17`). The earlier board probe independently observed UID AppOps readback for single-owner fixtures (`board-compatibility.md:17–34`), while the shared-UID board UI test only checked the read-only presentation (`board-compatibility.md:44–50`). These observations justify the existing conservative shared-UID guard but do not establish the missing server resolver implementation or behavior for every device and operation.

## External references and related specifications

- The earlier shared-UID evidence cites Android's [`AppOpsManager` source](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/android/app/AppOpsManager.java) and [`PackageInfo.sharedUserId` reference](https://developer.android.com/reference/android/content/pm/PackageInfo#sharedUserId). Those references were not newly retrieved in this investigation; the Android 16 source is not by itself board-specific proof.
- `.trellis/spec/trellis-plus/android-capabilities.md` requires verified operation behavior, distinct authorities, shared-UID rejection and explicit non-atomic identity limits.
- `.trellis/tasks/11-20-verify-thanox-pro-api/design.md` and `research/permission-review-packet.md` record the pending permission/identity review and the remaining resolver, provenance and atomicity limitations.

## Caveats / Not Found

The pre-existing board record identifies the public checkout as commit `053888f56b8d07b964e848ce1ccb5f2b979bc969` and says its inspected interface definitions match tag `v9.6` (`board-compatibility.md:5–11`). This source-focused investigation did not inspect the installed service bytecode, retrieve the missing internal submodule, verify a service signature, or operate a device. It does not establish resolver equivalence or a real conflicting service instance, and it is not the required human security review. A subsequent static inspection of the installed board APK and its narrower, qualified conclusions are recorded in `uid-resolver-bytecode-20261125.md`.

## Branch and submodule follow-up

On 2026-11-25, the [upstream branch list](https://api.github.com/repos/Tornaco/Thanox/branches?per_page=100) contained 38 branches, all matching the locally available remote references by name and commit. Reading their trees without changing the checkout showed that every branch except the documentation-only branch refers to `android/internal/Thanox-Internal` as a Git submodule. Searching all branch tips for `getUidForPkgName` and `getPackagePermInfo` found declarations, generated interface code, client wrappers and UI callers, but no concrete implementation of these service methods.

The upstream [`v9.6` tree](https://github.com/Tornaco/Thanox/tree/v9.6/android/internal) pins the internal submodule to `1fb155d276554b861fa4469269562fa80638a703`; local `master` pins it to `f6bb07a71d7e4f3532662045001e848c7f16965b`. The tag's [`settings.gradle.kts`](https://github.com/Tornaco/Thanox/blob/v9.6/android/settings.gradle.kts#L66-L103) locates the service modules under that submodule. The local checkout has neither an initialized submodule nor its object store. The GitHub repository page/API returned 404 for the current browser, and a non-interactive SSH `ls-remote` using the current system configuration and GitHub's published host key failed public-key authentication. These observations do not establish whether the submodule is private, deleted, or available through another authorized account; an accessible copy at the relevant commit would be needed to inspect its source.

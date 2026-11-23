# Research: Android and Thanox target UID consistency

- Query: Can a catalog UID conflict be bypassed by exact lookup, and is there a narrower Thanox per-user identity query for guarding a permission write?
- Scope: Internal product source, task evidence, and the local Thanox reference checkout.
- Date: 2026-11-20

## Files found

- `android/app/src/main/kotlin/im/majo/thanox_bridge/ThanoxCatalog.kt` — catalog merge, conflict suppression, and current-user filtering of the Thanox list.
- `android/app/src/main/kotlin/im/majo/thanox_bridge/PermissionRepository.kt` — list and exact lookup plus detail/read/write identity checks.
- `android/app/src/main/kotlin/im/majo/thanox_bridge/ThanoxOps.kt` — raw IOps transactions and `Pkg` parcel layout.
- `android/app/src/main/kotlin/im/majo/thanox_bridge/ThanoxService.kt` — version and Binder descriptor checks, shared transaction helper.
- `android/app/src/main/kotlin/im/majo/thanox_bridge/MainActivity.kt` — method-channel dispatch, using one repository instance and a single-thread executor.
- `android/app/src/test/kotlin/im/majo/thanox_bridge/CatalogMergerTest.kt` — list-merge conflict tests, without a find/detail/write conflict path.
- `archive/thanox-repo/android/android_framework/base/src/main/java/github/tornaco/android/thanos/core/pm/IPkgManager.aidl` — reference per-package and per-user package manager methods.
- `archive/thanox-repo/android/android_framework/base/src/main/java/github/tornaco/android/thanos/core/pm/Pkg.java` and `AppInfo.java` — reference Parcelable fields for `Pkg` and `AppInfo`.
- `archive/thanox-repo/android/android_framework/base/src/main/java/github/tornaco/android/thanos/core/ops/IOps.aidl` and `OpsManager.kt` — permission call signatures and client-side forwarding.
- `.trellis/tasks/11-20-verify-thanox-pro-api/research/device-proof.md` and `permission-device.md` — prior device observations and limits.

## Findings

### Current product path

`PermissionRepository.listApps` (`PermissionRepository.kt:16`–`57`) combines Android and Thanox entries. `CatalogMerger.merge` (`ThanoxCatalog.kt:18`–`36`) removes a package when two returned entries for that package have differing UIDs and records only a count. The UI receives the count in the coverage notice; it does not receive the package names of conflicts. The merge also rejects invalid and non-current-user entries before comparing UIDs.

`findApp` (`PermissionRepository.kt:60`–`78`) instead calls Android `getApplicationInfo` for the exact name and validates Android's package name and user/UID. It neither consults the merge result nor compares with a Thanox UID. Thus an exact lookup can present a package that the preceding list omitted for a UID conflict; the same can happen after the list fails or is stale. This is a reachable consistency gap in the source; no actual conflicting device package was created or observed in this research.

`appDetails` (`PermissionRepository.kt:80`–`116`) checks Android package metadata, records an Android identity fingerprint, and then reads Thanox ops using the Android-selected package name and user. `setOpMode` (`PermissionRepository.kt:131`–`167`) checks the Android package, current user, UID, signing/install fingerprint, declared permissions and verified-mode policy, rechecks Android identity, sends the IOps call, and rechecks Android identity around readback. It does not compare a Thanox-resolved UID. `MainActivity.kt:21`–`46` dispatches all four methods on one executor, but this does not make Android checks and a remote Binder write atomic.

`ThanoxOps.kt:17`–`39` sends the operation with a `Pkg(packageName,userId)`, not the Android UID or fingerprint; `ThanoxOps.kt:56`–`60` writes a non-null marker, package string and user integer. The reference `Pkg.java:13`–`25` and `:44`–`47` agree with this field order, and reference `IOps.aidl:3`–`5` declares the corresponding methods. Because the effective target of the remote service is not established by the client alone, this gap is a reason to reject an observed disagreement; it is not proof that the service would actually write another UID.

### Candidate exact per-user Binder queries

Reference `IPkgManager.aidl:10`–`15` declares `int getUidForPkgName(in Pkg pkg)` as its second method, followed by the already-used `getInstalledPkgs(int flags)`. In the same source `Pkg` contains both package name and user. Source-order AIDL numbering therefore suggests transaction 2, using the same non-null `Pkg` parcel layout, followed by `readException()` and an integer reply. The existing product and prior probe validate the nearby package-manager Binder and list transaction 3, but they do not establish transaction 2 or this method's result on the installed service. `PackageManager.java:63`–`75` forwards its `Pkg` overload without showing service-side behavior.

Reference `IPkgManager.aidl:116` also declares `AppInfo getAppInfoForUser(String pkgName, int userId)`; source declaration order suggests transaction 70. `AppInfo.java:45`–`69` contains package, UID, user and dummy fields and `:87`–`105` shows parcel reading order. A returned object could supply more fields but expands the raw wire contract and requires validation of null/dummy results, returned package/user/UID, full parcel shape, compatibility and privacy. Neither exact per-user method has a task-recorded ordinary-app device proof; do not promote source-order transaction numbers to verified contracts without that proof.

The checkout is at `053888f56b8d07b964e848ce1ccb5f2b979bc969`. Searches for the method names found AIDL and client wrappers but no `IPkgManager` or `IOps` service implementation. The exact server-side relationship between `getUidForPkgName`, catalog entries and `IOps.setMode` is therefore unknown. The official reference UI uses `Pkg.fromAppInfo` for an IOps read (`AppOpsListViewModel.kt:62`–`75`), and its wrapper simply forwards (`OpsManager.kt:59`–`64`); neither establishes the server's target-resolution algorithm.

### Narrow mitigation and proof options

1. Preserve list-conflict information within a current snapshot and prevent the exact lookup from presenting a known conflicting entry as ordinary. This addresses the demonstrated UI bypass, but a cached conflict set alone cannot protect direct detail/write calls, unseen conflicts, refreshes, process restart, or changes after the snapshot.
2. Independently compare the selected Android identity against a freshly obtained Thanox identity at the native detail/write boundary. If a cross-source comparison reports a mismatch, unavailable/invalid result, service exception, or an unsupported wire format, fail closed for the write and explain the reason; Android-only viewing can remain available. Keep exact-package lookup usable when catalog enumeration fails without treating that alone as proof that writes are safe. Validate expected current user and nonnegative UID, not merely equality with an unchecked value.
3. A direct verified `getUidForPkgName(Pkg)` is a smaller read than downloading the catalog, but first establish its real descriptor, transaction, parcel and failure behavior. Alternatively compare a fresh current-user Thanox catalog entry, recognizing its potentially incomplete coverage, larger Binder reply and other-profile privacy boundary. A missing catalog entry must not be silently treated as a match. Both options add a check and do not prove atomicity or that IOps uses the same server-side resolver.

Suggested validation: unit-test equal UID, mismatched UID, absent/negative/invalid user results, thrown Binder errors and stale/replaced package identities; assert that mismatch/unavailable results do not reach IOps. Exercise the intended exact-query wire call from a separate ordinary UID on the designated device against only a separately signed task-created target and a nonexistent task-owned package name, compare its returned UID to Android, record the negative/error behavior and clean up. A controlled fake conflict can test rejection without manufacturing or mutating a real user's UID. After any implementation, independently verify a permitted operation against the disposable target; the real-device tests must not assert that a conflicting UID or actual capability use was tested unless it was.

## Related specs and external references

- `.trellis/spec/trellis-plus/android-capabilities.md` requires current-user targeting, conflict omission or write rejection, separate authority and explicit failure behavior; its identity checks are not atomic.
- `.trellis/spec/trellis-plus/validation.md` requires dedicated disposable targets and independent device readback for Android permission changes.
- Reference material is the user-provided official Thanox checkout above. This investigation did not use network sources or access the device.

## Caveats / Not Found

At the source-only research stage, no real UID conflict, per-user UID-query transaction, invalid-package result, cross-user lookup, or server-side IOps resolution was exercised. The research does not establish that normal Android and Thanox targets disagree, nor does it establish that a new UID cross-check is equivalent to a fingerprint or an atomic write. The historical catalog probe observed one target with matching Android/Thanox UIDs; its result does not prove either complete catalog coverage or exact-lookup wire behavior.

## Subsequent user-0 probe

After the source investigation, a separate ordinary-app probe on the designated device exercised the candidate transaction for the dedicated target and an absent test package. Android and Thanox both returned UID 10526 for the target; the absent package returned Android `NameNotFoundException` and Thanox `-1`. Full caller, Binder, APK and cleanup evidence is recorded in `permission-device.md`. No actual conflict, other Android user, server-side IOps resolver or atomicity was tested.

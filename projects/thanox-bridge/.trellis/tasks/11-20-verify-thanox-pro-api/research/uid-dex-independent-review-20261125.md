# Research: Independent review of Thanox UID resolver bytecode

- Query: Do the supplied DEX disassembly excerpts support a common package UID resolver for the Thanox package-manager and operation interfaces, and what are the limits of that conclusion?
- Scope: internal; review of the two task-generated text evidence files and local task context only. No device access or direct inspection or execution of the APK or DEX was performed in this review.
- Date: 2026-11-25.

## Files found

- Selected `dexdump` method bodies, code bytes and DEX byte offsets; SHA-256 `d369a26860648f267144e8dead7a8b23d9ce2beba26208d680d56cd2aed89838`.
- Selected radare2 disassembly for cross-checking; SHA-256 `c1ce094685a0c5b5c7fbba1cb689ed5b83d46f9e7e7279377c5c255c59b2a97a`. Both supplied excerpts are retained as hash-named material in the temporary export described by `uid-resolver-bytecode-20261125.md`.
- `.trellis/tasks/11-20-verify-thanox-pro-api/research/uid-resolver-source-20261125.md` — earlier local-source and interface findings, including their scope limits.
- `.trellis/mainline.md` and `.trellis/spec/trellis-plus/android-capabilities.md` — task baseline and identity, shared-UID, provenance and non-atomicity contracts.

The task context attributes these excerpts to installed Thanox `9.6-prc` (`3404474`), APK SHA-256 `9a5ea71f300f32dc76c4107b01c269cd94508f4e0ea72674bee5c15bb21806b3`, and extracted `classes2.dex` SHA-256 `09830789758cae00c63994ec3e58972230a7941d529b424194ccaa86d0253bca`. Those APK and DEX hashes and the extraction provenance were supplied by the task and were not independently reverified here. All addresses below are file offsets in the selected DEX disassembly, not runtime addresses. Obfuscated classes are under `mqoaqz.gbpw.cxhc.yhyir`.

## Findings

### Root and child construction

The instructions support a concrete construction path for both child interfaces:

1. `uj9.<init>` creates `zr6(this)` and stores it in `uj9.OooOO0O` at `0x4dbdde`, `0x4dbde2`, `0x4dbde8`; it creates `jb6(this)` and stores it in `uj9.Oooo00O` at `0x4dbf44`, `0x4dbf48`, `0x4dbf4e`. `jb6.<init>` calls `tl9.<init>(uj9)` at `0x44ccd6`; that constructor saves the `uj9` reference in `tl9.OooO` at `0x4e2854`.
2. `uj9.OooOOo0` loads its `jb6` at `0x4dbd1c`/`0x4dbd30`, loads its `zr6` at `0x4dbd34`, and passes both to `vj9.<init>` at `0x4dbd9c`. The constructor invokes `IThanos.Stub.<init>` at `0x4db936` and saves `zr6` and `jb6` at `0x4db948` and `0x4db9a8`, respectively.
3. `vj9.getPkgManager` creates `as6` with the stored `zr6` at `0x4db5f8`–`0x4db600`; `as6.<init>` invokes `IPkgManager.Stub.<init>` and stores that `zr6` at `0x467a06`/`0x467a0c`. `vj9.getOpsManager` creates `ob6` with the stored `jb6` at `0x4db5b8`–`0x4db5c0`; `ob6.<init>` invokes `IOps.Stub.<init>` and stores that `jb6` at `0x44c29a`/`0x44c2a0`.

Thus, for the shown constructor path, the root captures a `zr6` initially obtained from the same `uj9` whose `jb6` retains a reference to that `uj9`. This is stronger than merely observing that two interfaces accept the same `Pkg`. It is conditional: the root saves the `zr6` reference once, while later `jb6` calls reread `uj9.OooOO0O`; these excerpts do not establish that the latter field is never changed or that later calls necessarily receive the identical instance. Even a common instance can return different cached values at different times.

The excerpts also contain a conditional publication path: `T.serviceInstallName` returns `tv_input` at `0x26cd08`; `uj9.OooOOOo` obtains a new `vj9` at `0x4dc292`–`0x4dc29a`, conditionally calls `sl9.OooO00o` at `0x4dc390`–`0x4dc394`, and that helper invokes `ServiceManager.addService` at `0x4df6aa`. The branch skips this call when the computed TV-feature flag is nonzero. This establishes an intended bytecode publication path, not which code currently owns a live Binder.

### UID query and operation read

`as6.getUidForPkgName` loads its stored `zr6` and calls `zr6.getUidForPkgName(Pkg)` at `0x467740`/`0x467744`. `ob6.getPackagePermInfo` forwards the original `Pkg` and operation to `jb6` at `0x44c1f0`/`0x44c1f4`. On the shown read path, `jb6.getPackagePermInfo` invokes `jb6.OooOo` at `0x44c362`, which follows `tl9.OooO` to `uj9.OooOO0O` and calls the same named `zr6.getUidForPkgName(Pkg)` at `0x44c530`–`0x44c538`. Its returned integer is passed to Android `IAppOpsService.getUidOps` at `0x44c550` and `getOpsForPackage` at `0x44c57e`.

This applies only when execution reaches that portion: the outer read can return null before calling `OooOo` (`0x44c34a`–`0x44c358`) and catches exceptions from it (`0x44c372`–`0x44c384`); `OooOo` can return null when the mapped permission is not declared (`0x44c3d6`–`0x44c406`). Before the UID lookup, the read also invokes `PkgUtils.getAllDeclaredPermissions(Context, packageName)` at `0x44c3de`–`0x44c3e6` and `jb6.getPermissionFlags` at `0x44c474`. The latter helper's body is not included in these excerpts. Therefore the shown AppOps reads use this UID resolver; that does not prove that every part of permission detail resolution does.

### Operation write and switch payloads

`ob6.setMode` forwards to `jb6.setMode` at `0x44c2b8`–`0x44c2bc`. The latter constructs `hb6` at `0x44cd38`/`0x44cd3c`, wraps it in `t41` with discriminator 1 at `0x44cd42`–`0x44cd48`, calls `o41.Oooo0o` at `0x44cd56`, `o41.Oooo0OO` at `0x44cd6a`, and `fd0.OooO00o` at `0x44cd70`.

The switch payloads matter. `t41.<init>` stores discriminator 1 at `0x37cf10`; the `packed-switch` at `0x37cf34` has its payload at `0x37d008`. Its first key is 0, and its second target is the signed code-unit offset `0x10` from the switch, so key 1 reaches `0x37cf54`; that branch casts the stored object to `Runnable` and invokes `run` at `0x37cf68`–`0x37cf70`. The selected `hb6` constructor stores discriminator 0 at `0x44bcf8`/`0x44bcfa`. Its `packed-switch` at `0x44bd5c` has a payload at `0x44be9c` with key 0 and target offset `0x8f` code units from the switch: `0x44be7a`. That branch invokes `jb6.OooOoOO` at `0x44be92`.

Inside `jb6.OooOoOO`, the operation is normalized by `AppOpsManager.opToSwitch` at `0x44ce5c`. On a path reaching the AppOps phase, the method loads `tl9.OooO` and `uj9.OooOO0O`, calls `zr6.getUidForPkgName(Pkg)` at `0x44cf88`–`0x44cf90`, and passes the resulting UID to Android `AppOpsManager.setUidMode` at `0x44cfa8` followed by `AppOpsManager.setMode` at `0x44cfc2`. The package name also goes to the latter call. No local negative-UID guard appears between this lookup and those calls in the shown method.

This is evidence of the intended write path and of its two AppOps call instructions, conditional on the preceding calls, scheduler dispatch, and branch execution succeeding. The provided exports do not contain the bodies of `o41.Oooo0o`, `o41.Oooo0OO`, or `fd0.OooO00o`; those excerpts alone do not strictly prove dispatch, blocking/await semantics, completion, exception propagation, or a successful Android effect.

Subsequent inspection by the primary agent examined those additional methods and found an untimed latch await and success/error callbacks. That later inspection, its separate evidence and its remaining scheduling limits are recorded in `uid-resolver-bytecode-20261125.md`; it was outside this independent review's supplied excerpts.

The write is not solely an AppOps mutation. Before the UID lookup, state-dependent branches call `jb6.OooOoo` at `0x44cf06`/`0x44cf40`, `jb6.OooOoo0` at `0x44cf24`/`0x44cf5a`/`0x44cf72`, `jb6.getPermissionFlags` at `0x44cf0c`, `0x44cf2a`, `0x44cf46`, `0x44cf60`, or `0x44cf78`, and `jb6.OooOooO` at `0x44cf1c`, `0x44cf38`, `0x44cf52`, `0x44cf6a`, or `0x44cf82`. The included helper bodies show:

- `jb6.OooOoo` conditionally calls Android `PackageManager.revokeRuntimePermission(packageName, permission, UserHandle)` at `0x44cdf8`.
- `jb6.OooOoo0` conditionally calls `PackageManager.grantRuntimePermission(packageName, permission, UserHandle)` at `0x44cdb4`.
- `jb6.OooOooO` calls `PackageManager.updatePermissionFlags(permission, packageName, mask, values, UserHandle)` at `0x44ce42`.

These helpers obtain package name and user ID from `Pkg` and use Android package-manager APIs, not the later returned UID directly. Their execution depends on the mapped permission, state and branch conditions; not every write performs each helper. The `getPermissionFlags` body is absent from the selected evidence. Because some permission mutations precede UID lookup and the two AppOps calls occur sequentially, a later failure can potentially leave partial effects; the excerpts do not show an all-or-nothing transaction or rollback.

### Per-user cache and missing entries

`zr6.getUidForPkgName` reads `Pkg.getUserId()` at `0x46408c`, then `zr6.Oooo0OO` selects `ss6` from `zr6.Oooo00o`, a `HashMap` keyed by the boxed user ID (`0x463e9c`–`0x463eb4`). A missing pool returns `-1` (`0x4640a4`–`0x4640c4`). For a present pool, `0x4640d2`–`0x4640de` looks up `Pkg.getPkgName()` in `ss6.OooOOo`, a `ConcurrentHashMap`; a missing entry also returns `-1` at `0x4640ea`–`0x4640ee`, otherwise the stored integer is returned at `0x4640f0`–`0x4640f8`.

The map is populated with `AppInfo.getPkgName()` and `AppInfo.getUid()` at `0x4688c2`–`0x4688e8`, and its entry is removed by package name at `0x4689da`/`0x4689de`. This establishes a mutable, per-user package-to-UID cache in the selected code, not its completeness, freshness or atomic consistency with Android package state. A cache miss does not by itself prove the package is absent from Android. The write method does not locally test for `-1` after this lookup; the consequences of passing it to Android APIs are not established here.

### Tool interpretation and external references

The `dexdump` output correctly reports the signed `const/4` at `0x4640a4` as `-1`; radare2 renders the encoded nibble as `0xf`. The radare2 output also disassembles switch payload data as instructions and labels the `hb6` and `t41` payloads eight bytes late (`0x44bea4` and `0x37d010`). The `dexdump` payload bytes and DEX switch-relative code-unit offsets give the switch targets described above. These are disassembler-format caveats, not evidence of different control flow.

No external references were newly retrieved. The earlier source research cites Android and Thanox references, but those source declarations alone do not prove this installed version's service behavior.

## Caveats / Not Found

- The reviewer used only selected text exports. The full APK, other DEX files, complete application call graph, field declarations and all field writes were not inspected; the supplied APK/DEX hashes and extraction chain were not independently verified.
- The reviewer did not observe the current development-board Binder, test it, or identify its loaded code. A Binder name, descriptor, version or self-reported identity, and matching installed-package bytes, do not by themselves attest that the live Binder loaded the reviewed DEX or exclude replacement, hooking, or another implementation. The conditional publication path does not close that provenance gap.
- The findings support a shared method and the shown initial object wiring, under the stated path and lifetime conditions. They do not prove that every permission helper uses that resolver, that cache values remain stable between independent calls, that Android identity and service state are atomically checked with mutation, or that any actual conflicting resolver, replacement or race occurred.
- This review does not establish effects for untested Android/Thanox versions, all operation/state pairs, permission-use capability or shared-UID behavior beyond existing separately scoped device evidence. It is not a human security review.

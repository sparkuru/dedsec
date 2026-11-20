# Thanox Pro device verification

## Scope and environment

- Date: 2026-11-20; test device selected by `product:PLR110`.
- Android 16, API 36, arm64; installed package `github.tornaco.android.thanos.pro`, version `9.1-row`, version code `3380725`.
- The local Thanox source checkout is `archive/thanox-repo`, commit `053888f56b8d07b964e848ce1ccb5f2b979bc969`. Its relevant contracts are `android/android_framework/base/src/main/java/github/tornaco/android/thanos/core/IThanos.aidl`, `core/pm/IPkgManager.aidl`, `core/app/ThanosManagerNative.java`, and `core/T.kt`.
- Only the designated device was used. The two temporary applications declared no Android permissions, used different signing certificates, and had separate UIDs. Their source and manifests are in `research/probe/`.
- The probe was built offline with the existing `luna-security-android-sdk:20261022` image, with networking disabled and only a task-owned temporary directory mounted. Probe APK SHA-256: `7c23bef67bb4ebd3d796ccf421a1ea5489d2b98f68fbb666c61bf0f0abd7b9ce`; target APK SHA-256: `f852702f6d5b97fb2405d7a6906c0159062124f4dd7307eb9558c16fcaf9d833`.
- The probe signing certificate SHA-256 was `e089cc5fa5592e3a231e23d9dc5ccea50dad305913a4221a2b03d3db1ba62fa3`; the target signing certificate SHA-256 was `17a26fe5b2616a45b82edd7c03530454ef1b37f5966324a713dbdc9d0077433d`.

## Shell-level discovery

`adb shell service list` reported `tv_input: [github.tornaco.android.thanos.core.IThanos]`. With the source AIDL transaction order, read-only calls to transaction 19 and 20 returned `9.1` and `I am Thanox!!!`. Transaction 24 returned false for `hasFrameworkInitializeError`; transaction 27 returned `Xposed` as the patching source. Shell `id` reported UID 2000, which is distinct from an ordinary app UID.

## Ordinary application read and cross-app write

The app `dev.thanoxbridge.probe.verify20261120` ran as UID 10508 with target SDK 35. It used reflection to call `android.os.ServiceManager.getService("tv_input")`, then sent AIDL-compatible read-only transactions. It obtained `IThanos`, `IPkgManager`, `IActivityManager`, and `IProfileManager` and queried its own package and component state.

The separately signed, code-free target `dev.thanoxbridge.target.verify20261120` was assigned UID 10509. Before the write, both Thanox and Android reported its test receiver state as `0` (`COMPONENT_ENABLED_STATE_DEFAULT`). The probe's direct Android `PackageManager.setComponentEnabledSetting` call for this other UID, requesting the existing state, raised `SecurityException`. The probe then used Thanox `IPkgManager.setComponentEnabledSetting` with `DONT_KILL_APP` to set that receiver to `2` (`COMPONENT_ENABLED_STATE_DISABLED`), checked the state through both Thanox and Android, and restored state `0` in a `finally` block. Both APIs reported the restored value.

Observed probe output, captured from its dedicated `ThanoxProbe` log tag:

```text
uid=10508;targetSdk=35;descriptor=github.tornaco.android.thanos.core.IThanos;version=9.1;identity=I am Thanox!!!;pkgDescriptor=github.tornaco.android.thanos.core.pm.IPkgManager;ownUidPackages=[dev.thanoxbridge.probe.verify20261120];appEnabled=true;smartFreeze=false;componentState=0;targetUid=10509;targetBefore=0;androidBefore=0;androidDirect=SecurityException;targetDuring=2;androidDuring=2;targetRestored=0;androidRestored=0;activityDescriptor=github.tornaco.android.thanos.core.app.IActivityManager;appRunning=true;startBlocked=true;backgroundRestricted=false;profileDescriptor=github.tornaco.android.thanos.core.profile.IProfileManager;profileEnabled=false
```

An initial explicit broadcast to the probe was blocked by the device's existing intent firewall policy. The test used a short-lived foreground activity instead; no firewall setting was changed.

## Cleanup and limitations

- Both temporary packages were uninstalled successfully; exact-package `cmd package list packages -U` checks returned no results afterwards.
- The target component was restored before uninstall. Post-cleanup shell calls still returned Thanox version `9.1` and no reported framework initialization error.
- The task-owned temporary build directory, including the generated signing keys, APKs, and installation screenshots, was removed after preserving the probe source. The recorded APK hashes identify this run and will differ when rebuilt with newly generated signing keys.
- This demonstrates the tested methods and caller on this device. It does not establish an officially supported third-party SDK, a complete authorization model, behavior of all methods, compatibility with other Android/Thanox versions or activation modes, or support for loading third-party plugins.
- `ServiceManager` is an Android internal API. Reflection and the copied AIDL transaction layout are compatibility dependencies even though they worked in this experiment.

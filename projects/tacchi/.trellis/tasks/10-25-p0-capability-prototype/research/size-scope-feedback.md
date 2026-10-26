# Research: APK size and editable Vector scope feedback

- Query: Compare user-supplied BBZQ packaging evidence, diagnose fixture-only Vector scope, and identify bounded changes alongside the requested `im.majo.tacchi` package rename.
- Scope: mixed; local source and immutable official repository sources.
- Date: 2026-10-26

## Findings

### BBZQ evidence

The official [v1.5.1 release](https://github.com/HSSkyBoy/BBZQ/releases/tag/v1.5.1) resolves via GitHub commits API to `e8531fc58da125a3c6e0c6899ccd7b8ad90a131e`. HEAD was separately observed at `4e60006379a77ee2426c89abaf124afb0c4ff469`; all source conclusions below were rechecked against the release revision.

GitHub's release assets API reported these exact download byte sizes:

| Asset | Bytes |
| --- | ---: |
| bbzq_v1.5.1-356-arm64-v8a.apk | 2,373,575 |
| bbzq_v1.5.1-356-armv7a.apk | 2,245,453 |
| bbzq_v1.5.1-356-x86_64.apk | 2,395,188 |
| bbzq_v1.5.1-356-uni.apk | 3,050,694 |

These are asset metadata sizes, not APK download/signature/content validation. The [release workflow at lines 40–53](https://github.com/HSSkyBoy/BBZQ/blob/e8531fc58da125a3c6e0c6899ccd7b8ad90a131e/.github/workflows/ci.yml#L40) defines separate ABI variants and a universal `arm64-v8a,armeabi-v7a,x86_64` variant; line 130 supplies those filters to Gradle.

The [release build file](https://github.com/HSSkyBoy/BBZQ/blob/e8531fc58da125a3c6e0c6899ccd7b8ad90a131e/app/build.gradle.kts#L119) enables R8 minification and resource shrinking (121–123). Runtime dependencies at lines 170–178 are libxposed service, DexKit, OkHttp and Shizuku; API is compile-only. No Flutter or Compose dependency appears in this app build. The [settings activity](https://github.com/HSSkyBoy/BBZQ/blob/e8531fc58da125a3c6e0c6899ccd7b8ad90a131e/app/src/main/java/io/github/bbzq/SettingsActivity.kt#L21) imports Android platform widgets and extends platform `Activity` (line 34). Thus the observed settings toolkit is Kotlin plus platform Views, not Compose or Flutter. Version-catalog entries alone are not evidence of packaged dependencies: the catalog includes unused UI library declarations. Its [catalog lines 4–5](https://github.com/HSSkyBoy/BBZQ/blob/e8531fc58da125a3c6e0c6899ccd7b8ad90a131e/gradle/libs.versions.toml#L4) selects libxposed 102.0.0; that does not authorize changing tacchi's pinned API101 baseline.

### Size implications for tacchi

The coordinating agent supplied a local APK measurement of 48,345,323 bytes and three ABI copies of Flutter native/app libraries totaling approximately 41.6 MB uncompressed. Those are local inspection results reported by the coordinator, not independently remeasured by this research agent. Compressed APK total and uncompressed ZIP-entry totals must not be subtracted to promise an exact resulting APK size.

| Direction | Defensible expectation | Limitation |
| --- | --- | --- |
| Retain Flutter, deliver per-ABI release APKs | Removes irrelevant architecture copies from each download | Flutter engine and Dart app remain for the selected ABI; not evidence of a 4 MB APK |
| Kotlin with platform Views | Removes Flutter engine/Dart assets and avoids adding Compose runtime; BBZQ demonstrates a small native package is feasible | Actual tacchi size depends on dependencies, resources, signing, shrinking and UI implementation; measure a release build |
| Kotlin with Compose | Removes Flutter runtime but adds Compose libraries compiled into the APK | Size cannot be equated with platform Views or promised from source inspection |

If the user selects a native rewrite to prioritize package size, platform Views are the closest evidence-backed analogue to BBZQ. A layout rewrite does not require copying BBZQ implementation, SDK/toolchain choices, networking dependencies or behavior. Preserve tacchi's offline contract and record before/after APK totals, ABI contents, DEX and resource contributions. BBZQ's small release provides no evidence of safety, feature parity or suitability of its hook logic.

### Vector scope behavior and recommended fix

Local files inspected:

- `android/app/src/main/resources/META-INF/xposed/module.prop:3` currently says `staticScope=true`.
- `android/app/src/main/resources/META-INF/xposed/scope.list:1` contains only `dev.tacchi.probe`.
- `android/app/src/main/kotlin/dev/tacchi/app/module/TacchiModule.kt:15` restricts process name to `PeerIdentity.HOST`; line 19 additionally checks package and first-package status; line 20 loads the fixture activity.

At Vector v2.1 commit `b09a9787218082e4bccf404be38961027c224db3`:

- [ModuleDetection.kt:242–246](https://github.com/JingMatrix/Vector/blob/b09a9787218082e4bccf404be38961027c224db3/manager/src/main/kotlin/org/matrix/vector/manager/data/model/ModuleDetection.kt#L242) explains that static scope limits which apps are listed, while users can select any subset. It does **not** mean every listed checkbox is disabled.
- [ScopeViewModel.kt:261–265](https://github.com/JingMatrix/Vector/blob/b09a9787218082e4bccf404be38961027c224db3/manager/src/main/kotlin/org/matrix/vector/manager/ui/screens/modules/ScopeViewModel.kt#L261) narrows the visible list to the fixed set.
- [ModuleDatabase.kt:250–257](https://github.com/JingMatrix/Vector/blob/b09a9787218082e4bccf404be38961027c224db3/daemon/src/main/kotlin/org/matrix/vector/daemon/data/ModuleDatabase.kt#L250) rejects scope writes containing targets outside that set, including manager, CLI, restore and module-requested writes.
- The API101 [package manual source:35–47](https://github.com/libxposed/api/blob/edeb8379c067b16b91af3cb526f5f04db25c06b6/api/src/main/java/io/github/libxposed/api/package-info.java#L35) documents static scope and requires runtime package/process filtering. The current [Vector online manual](https://jingmatrix.github.io/libxposed-api/io/github/libxposed/api/package-summary.html) is a moving API102 reference; use the pinned source for 101 details.

Recommendation: change metadata to `staticScope=false`, retain the fixture as a recommendation in `scope.list`, and preserve the internal package/process allowlist. This lets Vector expose broader user selection without making tacchi operate on arbitrary selected apps. Clearly distinguish “selectable in Vector” from “implemented host support.” With only an uninstalled fixture in a static list, absence of useful selectable hosts is consistent with the source evidence; the actual user's device state has not been inspected.

The requested manager application ID is `im.majo.tacchi`. Review its namespace/class registration, own-app authorities, manifest and fixture's peer-package checks together. It does not implicitly rename or broaden the controlled `dev.tacchi.probe` host. Fresh package identity means an old installed package is a separate app, not an in-place upgrade; no migration or deletion is authorized by this research.

### Related specs

- `.trellis/workflow.md`: keep research separate from implementation and record scope changes.
- `.trellis/spec/trellis-plus/development.md`: UI/native stack decisions and source-backed dependencies.
- `.trellis/spec/trellis-plus/validation.md`: merged manifest/dependency inspection, real Vector acceptance, native mobile behavior, IPC identity negative cases and no sensitive test artifacts.
- `research/vector-contract.md`: fixed Vector/API101 baseline and framework-service versus host IPC distinction.

## Caveats / Not Found

- No BBZQ code was executed or copied; release APK content and signing were not audited.
- No precise replacement APK size is established before building the selected toolkit; a sub-4-MB target is a measurement goal, not a guarantee.
- Scope metadata changes need rebuilt-APK inspection and Vector refresh/reinstallation acceptance. The existing user device state and manager cache were not examined.
- Application ID migration and native UI implementation are outside this research agent's write scope.

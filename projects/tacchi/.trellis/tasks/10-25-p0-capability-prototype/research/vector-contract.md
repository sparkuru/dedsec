# Research: Vector API 101 contract

- Query: Pin Vector v2.1 and its API/service dependencies; establish lifecycle, SDK and transport contracts for the P0 Flutter/Kotlin scaffold.
- Scope: mixed; repository policies and immutable official upstream sources.
- Date: 2026-10-25

## Findings

### Immutable dependency baseline

The [Vector v2.1 release](https://github.com/JingMatrix/Vector/releases/tag/v2.1) resolves to commit `b09a9787218082e4bccf404be38961027c224db3`. Its recursive Git tree was queried through the GitHub REST API and mode `160000` entries inspected:

| Vector path | Immutable repository revision | Consumer dependency |
| --- | --- | --- |
| `xposed/libxposed` | [libxposed/api at edeb8379c067b16b91af3cb526f5f04db25c06b6](https://github.com/libxposed/api/tree/edeb8379c067b16b91af3cb526f5f04db25c06b6) | `compileOnly("io.github.libxposed:api:101.0.1")` |
| `services/libxposed` | [libxposed/service at 11f8945de4e24efc0eb0e2e87a2dd8284d8f7b66](https://github.com/libxposed/service/tree/11f8945de4e24efc0eb0e2e87a2dd8284d8f7b66) | `implementation("io.github.libxposed:service:101.0.0")` |

These are upstream **libxposed** repository links at v2.1. The moving Vector master `.gitmodules` references to JingMatrix forks recorded in `research/libxposed.md:25` must not be substituted for this immutable release's gitlinks. API and service version numbers differ intentionally.

Retrieval cross-check: a main-session web-tool request reported 404 for the pinned API Gradle raw URL. Repeating the exact request with network-authorized `curl -L` returned HTTP 200 and the expected `101.0.1` publication script. The [immutable Vector `.gitmodules`](https://raw.githubusercontent.com/JingMatrix/Vector/b09a9787218082e4bccf404be38961027c224db3/.gitmodules) independently returned HTTP 200 and explicitly maps `xposed/libxposed` to `https://github.com/libxposed/api.git` and `services/libxposed` to `https://github.com/libxposed/service.git`. The [API commit REST endpoint](https://api.github.com/repos/libxposed/api/commits/edeb8379c067b16b91af3cb526f5f04db25c06b6) also returned HTTP 200, matching the full SHA and commit message `Bump to 101.0.1`. The conflicting web-tool result does not establish a missing source or a different repository mapping. Exact accessible raw build source: <https://raw.githubusercontent.com/libxposed/api/edeb8379c067b16b91af3cb526f5f04db25c06b6/api/build.gradle.kts>.

Pinned `api/build.gradle.kts` and `service/build.gradle.kts` publish the coordinates above, set minSdk 26, compileSdk 36, buildToolsVersion 36.1.0 and Java source/target 17. The [service README](https://github.com/libxposed/service/blob/11f8945de4e24efc0eb0e2e87a2dd8284d8f7b66/README.md#L12-L25) confirms the consumer dependency and distinguishes framework-only `interface:101.0.0` integration.

Both Maven Central AARs returned HTTP 200 and were inspected in memory, without installation or filesystem extraction:

- [api-101.0.1.aar](https://repo.maven.apache.org/maven2/io/github/libxposed/api/101.0.1/api-101.0.1.aar): 12,630 bytes.
- [service-101.0.0.aar](https://repo.maven.apache.org/maven2/io/github/libxposed/service/101.0.0/service-101.0.0.aar): 15,094 bytes.

Both `META-INF/com/android/build/gradle/aar-metadata.properties` files specify `minCompileSdk=36`, `minCompileSdkExtension=0`, `minAndroidGradlePluginVersion=1.0.0`, and `coreLibraryDesugaringEnabled=false`. **SDK 35 alone cannot consume these unchanged published dependencies.** Prepare compileSdk 36; do not merely suppress metadata checks. The upstream build-tools selection is not evidence that a consumer must use exactly that build-tools version. The metadata AGP minimum alone is also not evidence that an arbitrary older AGP officially supports SDK 36; toolchain research must establish that separately. Runtime minSdk and compileSdk are distinct; a product minSdk 28 is compatible with the libraries' source-declared minimum 26.

### API 101 lifecycle and registration

The [Vector-hosted manual](https://jingmatrix.github.io/libxposed-api/io/github/libxposed/api/package-summary.html) currently identifies API **102.0.0**. It is useful navigation, not the frozen 101 contract. The immutable API source below is authoritative for this scaffold:

- [`api/src/main/java/io/github/libxposed/api/package-info.java:10`](https://github.com/libxposed/api/blob/edeb8379c067b16b91af3cb526f5f04db25c06b6/api/src/main/java/io/github/libxposed/api/package-info.java#L10): extend `XposedModule`; framework attaches the implementation; defer initialization until `onModuleLoaded`.
- Same file, lines 18–38: register the Kotlin/JVM entry's fully qualified class name in `src/main/resources/META-INF/xposed/java_init.list`; `module.prop` requires `minApiVersion` and `targetApiVersion`. Set both to `101` for this baseline. `staticScope` and `exceptionMode` are optional. Module label/description use Android resources.
- Same file, lines 43–47: `scope.list` controls injection, but callbacks can still include additional packages; explicitly filter package and process.
- [`XposedModule.java:5`](https://github.com/libxposed/api/blob/edeb8379c067b16b91af3cb526f5f04db25c06b6/api/src/main/java/io/github/libxposed/api/XposedModule.java#L5): entry instantiated once per process. No obsolete API-100 constructor pattern is needed.
- [`XposedModuleInterface.java:108`](https://github.com/libxposed/api/blob/edeb8379c067b16b91af3cb526f5f04db25c06b6/api/src/main/java/io/github/libxposed/api/XposedModuleInterface.java#L108): `onModuleLoaded` once per process. `onPackageLoaded` (line 133) requires Android Q; `onPackageReady` (line 152) is the package classloader-ready callback without that Q annotation. For an API-28 fixture baseline, prefer the latter and its `getClassLoader()` rather than unguarded API-29-only `getDefaultClassLoader()`.
- [`XposedInterface.java:254`](https://github.com/libxposed/api/blob/edeb8379c067b16b91af3cb526f5f04db25c06b6/api/src/main/java/io/github/libxposed/api/XposedInterface.java#L254): use interceptor-chain `hook(method).intercept { chain -> ... }`; preserve the original result with `chain.proceed()` for a passive capability probe. Chain objects cannot be reused outside interception or shared across threads (line 171).
- Same file, lines 35–54 and 350: runtime API and framework properties are available. Check actual runtime version and remote capability rather than assuming every 101 implementation has remote storage. Do not adopt API-102-only hot reload or process enumeration.

### Framework service versus host IPC

The pinned service is **framework ↔ module application** integration, not a general bidirectional manager ↔ hooked-host RPC service.

- [`service/src/main/AndroidManifest.xml:5`](https://github.com/libxposed/service/blob/11f8945de4e24efc0eb0e2e87a2dd8284d8f7b66/service/src/main/AndroidManifest.xml#L5): library manifest contributes exported `XposedProvider` with `${applicationId}.XposedService` authority. Include this intentional provider in merged-manifest review; do not confuse it with the product's own authenticated IPC endpoint.
- [`XposedServiceHelper.java:53`](https://github.com/libxposed/service/blob/11f8945de4e24efc0eb0e2e87a2dd8284d8f7b66/service/src/main/java/io/github/libxposed/service/XposedServiceHelper.java#L53): register the service listener once; handle bind and death. Multiple framework instances may bind (lines 19–20). This can drive manager framework status, but a binding alone is not evidence that a target host loaded the module.
- [`XposedService.java:97`](https://github.com/libxposed/service/blob/11f8945de4e24efc0eb0e2e87a2dd8284d8f7b66/service/src/main/java/io/github/libxposed/service/XposedService.java#L97): manager can query framework/API identity, properties and scope, request scope changes, edit remote preferences, and manage shared files. Handle service death and unsupported capability explicitly.
- [`interface/src/main/aidl/io/github/libxposed/service/IXposedService.aidl:27`](https://github.com/libxposed/service/blob/11f8945de4e24efc0eb0e2e87a2dd8284d8f7b66/interface/src/main/aidl/io/github/libxposed/service/IXposedService.aidl#L27): entire API101 transport surface is framework details, scope, preferences and files. `getRunningTargets` is commented out as API102 roadmap, not a callable 101 method.
- [`XposedInterface.java:473`](https://github.com/libxposed/api/blob/edeb8379c067b16b91af3cb526f5f04db25c06b6/api/src/main/java/io/github/libxposed/api/XposedInterface.java#L473): hooked apps read remote preferences; `openRemoteFile` (line 493) also opens read-only. These cannot provide host-to-manager status uploads.

Planning implication: reuse the service for framework identity/scope and any suitable manager-written public configuration. Retain a separately specified, caller-identity-checked host-to-manager channel for live capability/status/revision acknowledgement. Do not place credentials in either channel. A manager `service bound` state, a requested scope, a fixture callback and an acknowledged configuration revision are separate observable facts.

### Local files and related specs

- `.trellis/workflow.md`: phase and research workflow; task activation/implementation is owned by the main agent.
- `.trellis/tasks/10-25-p0-capability-prototype/research/libxposed.md:3`: user selected 101+ without device collection blocking scaffold work; lines 25 and 29 contain the earlier mutable-source note and service boundary concern refined here.
- `.trellis/spec/trellis-plus/development.md`: approved Flutter UI/Kotlin native boundary and prerequisite planning conventions.
- `.trellis/spec/trellis-plus/validation.md`: real device evidence required for Vector loading/authentication; caller identity, expiry/replay/revision and safe artifacts are required for IPC/security changes.
- `.trellis/spec/backend/index.md`, `directory-structure.md`, `error-handling.md`: currently placeholders; no established Kotlin application implementation pattern can be inferred from them.

## Caveats / Not Found

- This research does not certify an APK build, merged manifest, dependency checksum lock, source-to-binary reproducibility or actual device behavior. Maven AAR metadata was read, not executed.
- No P0 host identity protocol or authenticated IPC implementation exists in this evidence; its design and negative tests remain implementation work. The service's provider export is not an authorization model for that independent channel.
- Actual framework loading, capability flags, service delivery/death/rebind and host behavior require target-device tests. Device versions are acceptance evidence, not prerequisites for creating the scaffold.
- API102 current documentation, Vector master and example repository HEAD must not silently change this immutable 101 baseline. Future upgrades need an explicit dependency/contract review.

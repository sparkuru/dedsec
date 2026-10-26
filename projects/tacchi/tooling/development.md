# Native Android P0 build commands

The host needs Docker. `hako` reuses the existing pinned Cirrus image for JDK 21 and Android SDK tooling; the application no longer builds with or packages Flutter. Only the Android SDK is copied into ignored `.devhome/` for caller-owned downloads. Existing unrelated cached tools are not deleted. No ports, devices or Docker socket are mounted. Build downloads are separate from the offline APK runtime; `HAKO_NETWORK=none` disables networking after inputs are cached.

The build pins Gradle 8.12, AGP 8.10.1, Kotlin plugin 2.1.0, compile/target SDK 36 and minimum SDK 28. This platform-Views application has no NDK or Dart build requirement.

From the repository root:

```sh
./hako java -version
./hako ./tooling/prepare-android.sh
./hako bash -c 'cd android && ./gradlew :app:testDebugUnitTest :app:lintDebug :probe:lintDebug :app:assembleDebugAndroidTest --dependency-verification=strict --no-daemon'
./hako bash -c 'cd android && ./gradlew :app:assembleRelease :probe:assembleDebug --dependency-verification=strict --no-daemon'
```

Outputs:

- `build/app/outputs/apk/release/app-release.apk`: main application, `im.majo.tacchi`.
- `build/probe/outputs/apk/debug/probe-debug.apk`: controlled development-only host, `dev.tacchi.probe`.
- `build/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk`: device test package; building it does not execute its tests.

The main APK contains platform Views and Kotlin/JVM code, without ABI-specific native libraries, so one small APK works across supported device architectures. Release builds enable R8 and resource shrinking. `android/app/proguard-rules.pro` preserves the framework-loaded module entry and lifecycle callbacks. Inspect the final release DEX, module metadata and manifest after any shrinker change; a successful build alone does not prove injection.

The prepare command downloads Android platform 36 and build-tools 35.0.0, and creates `.devhome/tacchi-development.p12` only when absent. This unique disposable P0 development identity uses the conventional development password `android`; it is not a production signing key. Main release and fixture debug APKs use the same existing certificate. The compiled expected signer digest must match both. Changing that key requires rebuilding both packages and may require removing old development installations; no script installs or uninstalls anything automatically.

The main identity changed from the previous prototype package to `im.majo.tacchi`, which Android treats as a separate application. Rebuild the fixture too because its manager query and authority changed. Vector scope is now editable, while module code supports only `dev.tacchi.probe` and its main process. Selecting another app does not add a payment adapter. Device installation, live Binder tests, Vector scope UI, TalkBack and hardware authentication remain separate acceptance steps.

Gradle manifests, module lockfiles and `android/gradle/verification-metadata.xml` pin the dependencies. Normal builds enforce recorded checksums. The native migration updates these records in place; historical checksums or unused former build-configuration entries are not runtime dependencies. Inspect `:app:dependencies --configuration releaseRuntimeClasspath` to see the actual runtime graph. Do not delete integrity records or disable verification to make a build pass.

The previous Flutter APKs and source snapshots remain only in ignored `build/size-baseline-2026-10-26/` for size comparison. They are historical evidence, not current deliverables. Old Flutter widget screenshots do not validate the native screen.

Shell checks:

```sh
bash -n hako tooling/container-entry.sh tooling/prepare-android.sh
shellcheck hako tooling/container-entry.sh tooling/prepare-android.sh
shfmt -d -i 2 hako tooling/container-entry.sh tooling/prepare-android.sh
sh -n android/gradlew
```

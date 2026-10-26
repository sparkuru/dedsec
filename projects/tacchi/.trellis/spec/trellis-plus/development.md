# Development environment

## Current evidence

The P0 task contains a Kotlin Android application, development-only `:probe` fixture and `hako` Docker entry. The user's2026-10-26 native Views choice supersedes Flutter. Build/test results live in `.trellis/tasks/10-25-p0-capability-prototype/check-results.md`; file existence alone does not prove a successful build. `archive/refer-repo/` remains a separate reference repository, not tacchi's toolchain.

Approved target: Kotlin platform Views and Kotlin Android/IPC/Hook integration in one offline APK, main identity `im.majo.tacchi`. The runtime contains no Flutter/Compose engine, Flask server, WebView bridge or network configuration service. `preview: not applicable` to this native project; no `preview.sh`, service lifecycle, published ports or `.env` consumer is required.

## Before-development checkpoint

The first product scaffold task must complete this checkpoint before claiming a usable build environment:

1. Use the user-selected modern libxposed API 101 minimum contract (101+ framework target). Use [Vector's API manual](https://jingmatrix.github.io/libxposed-api/io/github/libxposed/api/package-summary.html) and the module/service API gitlinks of a verified Vector revision to pin compatible artifacts; never use a dynamic `101+` dependency. Select the Android minimum SDK from the chosen API/toolchain requirements. Device/ROM/Vector versions are collected for actual device validation, not a prerequisite to scaffold work. Record development applicationId and signing responsibilities; do not guess production signing values.
2. Select and pin compatible JDK, Gradle/Android plugin, Kotlin and Android SDK versions. Record their real manifests, lockfiles and verification data. Inspect existing tooling again before creating anything.
3. Prefer a project-owned ephemeral Docker command wrapper for build/lint/unit tests when the selected toolchain supports it. Before generating it, use the available `dev-it-in-docker` and shell coding procedures. Record image identity, dependency preparation, non-root ownership, cache/home paths, exact commands and cleanup scope. A wrapper must not change sandbox authority or mount unrelated host secrets.
4. Treat emulator/ADB/device execution separately. Do not silently introduce privileged containers, device passthrough or remote ADB. Record the approved host/device route and its prerequisites; explain an evidenced native-tooling exception to Docker when necessary.
5. Add only configuration actually consumed by the build/runtime. Verify a clean build plus selected lint/unit checks and update [validation](validation.md) with exact commands, output paths and remaining device limitations.

The native build uses JDK21, Gradle8.12, AGP8.10.1, Kotlin plugin2.1.0, compile/target SDK36 and minSdk28. `hako` reuses the pinned cached Cirrus image only for JDK/Android SDK tooling; its image name does not imply Flutter is in the build or APK. It copies writable Android SDK/cache state into ignored `.devhome/` under caller ownership. `./hako ./tooling/prepare-android.sh` prepares the SDK and unique local development signing identity. No ports, device or Docker socket are mounted. `HAKO_NETWORK=none` disables build-container networking once required inputs are cached.

Build the development-signed optimized APK with `./hako bash -c 'cd android && ./gradlew :app:assembleRelease --dependency-verification=strict --no-daemon'`. Product and fixture use the same project-local disposable signing certificate, with its digest baked into the module for mutual peer checks. Preserve that local key when rebuilding an installed pair, or rebuild/reinstall both artifacts intentionally. Do not commit the key or treat it as production signing. Distribution and real-device installation remain separate work. Record actual check outcomes rather than declaring commands passing by configuration alone.

`im.majo.tacchi` is a different Android installation from the former `dev.tacchi.app`. Disable the old module before testing the new installation against the fixture; both hooking the same fixture can invalidate results. Rebuild/update the matching fixture as well. No automatic uninstall or data migration is implemented. See [native Android contracts](native-android.md).

## Configuration changes

There is currently no tacchi `.env` loader or key set. Do not create an unused environment mechanism. When a task introduces local configuration, document the real loader, key consumers, precedence and rebuild/restart action together with a secret-free example. Keep signing material, SDK-local paths and credentials local and ignored.

Only initialize a local file when absent and authorized. On later changes append missing keys only; retain existing values, empty values, comments, quoting and line endings. Report duplicate/renamed/deleted keys rather than guessing replacement values. Never execute dotenv text as shell code or print secret values/diffs. Check missing-file, custom-value, empty-value and repeated-update behavior in temporary fixtures if synchronization logic is introduced.

Offline is the APK runtime contract. Build dependency downloads are a separate capability: disclose them, use pinned inputs, and honor any explicit no-install/offline execution constraint. No rule here authorizes a network or sandbox escalation.

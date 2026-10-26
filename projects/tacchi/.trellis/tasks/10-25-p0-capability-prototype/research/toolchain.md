# P0 build environment evidence

Inspected 2026-10-25. Host has Docker 26.1.5 and Java 21; no host Flutter command was found. Docker daemon access requires the existing sandbox escalation mechanism; an approved read-only probe succeeded. No project wrapper or application exists yet.

An existing `ghcr.io/cirruslabs/flutter:3.35.7` image was inspected through disposable `--network none` containers with no mounts. Observed digest: `ghcr.io/cirruslabs/flutter@sha256:d271a49ddd8ce1be6c7954f7eabe0e03766c8a1bf1430f0dce780888a21ed408`; image ID `sha256:46015d89c9a3acc1814ac512fd725cc8fedc51e3ebc66b0ba1a9aa9c44781a71`.

- Flutter 3.35.7, framework revision adc9010625; Dart 3.9.2.
- JDK 21.0.6.
- Android SDK path `/opt/android-sdk-linux`, platform android-35, build-tools 35.0.0.
- No NDK directory observed. Do not assume a complete offline Android build cache.
- Bundled Flutter template constants: Gradle 8.12, Android Gradle Plugin 8.9.1, Kotlin plugin 2.1.0.

Proposed baseline: reuse this pinned image for P0, derive actual application build configuration from its templates and verified Vector API requirements, and download only missing pinned dependencies during implementation. The project's requested offline property applies to APK runtime, not developer dependency resolution. No host Flutter installation, privileged container, device mount or network service is needed for compilation/unit tests.

The current Flutter public archive describes release channels but does not prove the content of this cached image. A request to the historical Google Storage release-metadata endpoint returned HTTP 404 after network access was granted; no latest-version claim is made. The fixed image was actually executed for version inspection, not for a tacchi build. API dependency bytecode and any AAR minimum requirements must still be checked against this toolchain before claiming compatibility.

Future hako entry: ephemeral container, caller UID/GID, repository-scoped cache/home, no ports, no Docker socket or host secrets mounted. Reuse built-in SDK locations; keep writable Flutter cache changes in project-owned cache/volume rather than changing the immutable image or broadening permissions. Implementation must verify this against the real image and shell standards. Prefer a prepared source-development image; do not create a service lifecycle for a native APK.

## Compatibility adjustment after AAR inspection

Published API 101.0.1 and service 101.0.0 AAR metadata require minCompileSdk 36 (see vector-contract.md). The cached platform 35 is insufficient. Official AGP 8.9 notes cap support at SDK35, while AGP8.10 supports SDK36 and requires Gradle8.11.1+ and JDK17+. Therefore the proposed P0 toolchain keeps Flutter3.35.7/Dart3.9.2/JDK21/Gradle8.12/Kotlin2.1.0, pins AGP8.10.1, and prepares Android platform36. Build tools35.0.0 are AGP8.10's documented default; the API libraries' own build-tools36.1.0 is not automatically a consumer requirement. Pin/download NDK27.0.12077973 only when the generated Flutter build requires it; no NDK was present in the inspected image.

Primary compatibility sources:
- https://developer.android.com/build/releases/agp-8-9-0-release-notes
- https://developer.android.com/build/releases/agp-8-10-0-release-notes

These are an evidence-backed build plan, not a claim that the assembled application has already built. Do not suppress compileSdk warnings or edit upstream AAR metadata to force SDK35.

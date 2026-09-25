# Dependency provenance

仅在本项目记录依赖，不修改顶层 third_party。

| Dependency | Version / source | License / use |
| --- | --- | --- |
| Flutter | 3.35.7, official tag adc90106255, https://github.com/flutter/flutter | BSD-3-Clause, UI and engine |
| Docker build image | `ghcr.io/cirruslabs/flutter:3.35.7`, manifest SHA-256 `d271a49ddd8ce1be6c7954f7eabe0e03766c8a1bf1430f0dce780888a21ed408`, https://github.com/cirruslabs/docker-images-flutter | MIT, temporary build container; copied writable tools stay in ignored `.devhome/` |
| xterm | 4.0.0, https://pub.dev/packages/xterm | MIT, terminal emulator |
| Xposed API | 82, https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar | Apache-2.0, compile-only, not packaged into APK |
| Gradle wrapper | Existing repository wrapper, targets Gradle 8.14.3 | Apache-2.0 |
| Android Gradle Plugin | 8.13.0, Google Maven | Apache-2.0 |
| AndroidX Core | 1.15.0, Google Maven | Apache-2.0, exported receiver compatibility |
| AndroidX Test runner / JUnit | 1.6.2 / 4.13.2 | Apache-2.0 / EPL-1.0, test APK only |

Xposed API jar SHA-256: `f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25`.

Flutter source archive SHA-256: `33db09dbcc934ddb5edc4622518bbc5e23098a00a432e780c6f645aee34cb9ba`.

Flutter transitive packages are pinned in pubspec.lock. Vector and the root manager are device-installed dependencies; their binaries are not distributed with ctOS. PTY native code is project-owned and compiled with Android NDK 27.0.12077973.

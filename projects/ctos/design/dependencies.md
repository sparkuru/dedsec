# Dependency provenance

仅在本项目记录依赖，不修改顶层 third_party。

| Dependency | Version / source | License / use |
| --- | --- | --- |
| Flutter | 3.35.7, official tag adc90106255, https://github.com/flutter/flutter | BSD-3-Clause, UI and engine |
| Docker build image | `ghcr.io/cirruslabs/flutter:3.35.7`, manifest SHA-256 `d271a49ddd8ce1be6c7954f7eabe0e03766c8a1bf1430f0dce780888a21ed408`, https://github.com/cirruslabs/docker-images-flutter | MIT, temporary build container; copied writable tools stay in ignored `.devhome/` |
| xterm | 4.0.0, https://pub.dev/packages/xterm | MIT, terminal emulator |
| Chaquopy | 17.0.0, https://chaquo.com/chaquopy/doc/current/ | MIT, Gradle 打包 Android Python 运行时与标准库；未使用 Java 嵌入解释器 |
| CPython Android | 3.13.9-0, `com.chaquo.python:target` / arm64-v8a | PSF-2.0 及 CPython 所附声明，工作台和终端共用；源码来源 https://github.com/python/cpython/tree/v3.13.9 |
| Python native dependencies | 打包的 OpenSSL 3.0.18、SQLite 3.50.4（本地 ARM64 自检版本） | OpenSSL Apache-2.0 / SQLite public domain；CA 文件随 Chaquopy runtime 提供 |
| Gradle wrapper | Existing repository wrapper, targets Gradle 8.14.3 | Apache-2.0 |
| Android Gradle Plugin | 8.13.0, Google Maven | Apache-2.0 |
| AndroidX Test runner / JUnit | 1.6.2 / 4.13.2 | Apache-2.0 / EPL-1.0, test APK only |

2026-09-26 移除直接的 Xposed API 与 AndroidX Core 接收器依赖，删除本地 Xposed API jar。Flutter Android 插件仍可能传递依赖 AndroidX Core；不再供系统桥接使用。

2026-09-26 Python 包通过 Chaquopy 17.0.0 固定 runtime 3.13.9-0；初始工作台无 pip requirements，当前五项工具依赖见下表。自有 NDK PIE 启动器通过 `Py_BytesMain` 启动 CPython。完整 Python、Chaquopy、OpenSSL 许可证保留在 `android/app/src/main/assets/portable/python/licenses/`，随 APK 分发。追加原生工具必须独立记录来源、版本、哈希、许可证、Bionic/ABI 兼容与实测结果，见 [运行包契约](portable-workbench.md)。

Flutter source archive SHA-256: `33db09dbcc934ddb5edc4622518bbc5e23098a00a432e780c6f645aee34cb9ba`.

Flutter transitive packages are pinned in pubspec.lock. 增强采集和 Root PTY 需要设备提供可用 su / Root 管理器；其二进制不随 ctOS 分发。ctOS 不再依赖 Vector/Xposed。PTY native code is project-owned and compiled with Android NDK 27.0.12077973.

## Portable 工具依赖（2026-09-26）

| 依赖 | 固定版本 / 实际构建 | 来源 / 许可证 |
| --- | --- | --- |
| cryptography | 42.0.8，`42.0.8-1-cp313-cp313-android_24_arm64_v8a.whl` | [Chaquopy 官方 wheel](https://chaquo.com/pypi-13.1/cryptography/)，Apache-2.0 / BSD-3-Clause |
| cffi | 1.17.1，`1.17.1-0-cp313-cp313-android_24_arm64_v8a.whl` | Chaquopy 官方 wheel，MIT |
| pycparser | 3.0，py3-none-any | PyPI，BSD-3-Clause |
| chaquopy-libffi | 3.3，`3.3-3-py3-none-android_24_arm64_v8a.whl` | Chaquopy 官方 wheel，libffi MIT；附 build-tools 声明 |
| 构建 CPython | 3.13.7 / python-build-standalone 20250902 / x86_64 GNU | [官方 release](https://github.com/astral-sh/python-build-standalone/releases/tag/20250902)，PSF 及所附依赖声明；仅项目 .devhome |

构建 Python archive SHA-256：`6a8280f4b08d75428eea83955678c51da00c585bb411562cd53f510680becf00`，hako bootstrap 自动校验；不创建宿主全局命令。APK requirements 的 dist-info LICENSE 随资源包保留。加密所需 OpenSSL 及 libffi 仅在受管 LD_LIBRARY_PATH 中加载，参见 [工具设计](portable-tools.md)。

Playwright 1.55.0 仅安装到 /tmp 的验证环境，复用已有 Chromium 1228 可执行文件；不是 App 或项目生产依赖。

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
| Bouncy Castle lightweight crypto | `org.bouncycastle:bcprov-jdk18on:1.86`, Maven Central / [官方项目](https://www.bouncycastle.org/download/bouncy-castle-java/) | Bouncy Castle MIT-style license；公共秘密库 Argon2id 实现，不替换全局 JCA provider；随 APK 保留 `assets/licenses/BouncyCastle-LICENSE.html` |
| ZXing core | `com.google.zxing:core:3.5.4`, Maven Central / [固定源码](https://github.com/zxing/zxing/tree/zxing-3.5.4) | Apache-2.0及上游NOTICE；宿主离线图片QR解码，无云模型或首次下载 |
| ZXing Android Embedded | `com.journeyapps:zxing-android-embedded:4.3.0`, Maven Central / [固定源码](https://github.com/journeyapps/zxing-android-embedded/tree/v4.3.0) | Apache-2.0；自有非exported Activity中的BarcodeView，不用外部扫码App或默认CaptureActivity传种子 |

2026-10-13 Authenticator 依赖实际解析：直接core3.5.4覆盖embedded的传递3.4.1，最终runtime报告见临时 `/tmp/ctos-totp-dependencies.log`。实际core JAR SHA-256 `71de5d89341b5fcf5dd89da7f44e84d825d0e084cdf3ec77c9abe26b0f0ceb13`、embedded AAR `4ab03353127c34e55cbb80fbc9a34031decbcfb1ec5e8f991944d2d494621a33`，主会话已独立核对缓存文件。完整固定上游资源随APK保留：`assets/licenses/ZXing-LICENSE.txt`（[来源](https://raw.githubusercontent.com/zxing/zxing/zxing-3.5.4/LICENSE)，SHA256 `3f62881f0566227a24b12e5a754cc79f39aaa94883038e95c94812e1f50af42f`）、`ZXing-NOTICE.txt`（[来源](https://raw.githubusercontent.com/zxing/zxing/zxing-3.5.4/NOTICE)，`4db4b60aace29ec6c5c4f13ca9cac1930d3c0c54bcc67dceead0c10771120e66`）及`ZXingEmbedded-LICENSE.txt`（[来源](https://raw.githubusercontent.com/journeyapps/zxing-android-embedded/v4.3.0/COPYING)，`c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4`）。仅项目私有Gradle缓存，未修改顶层third_party或全局工具链。APK中资源及最终构建结果另见 [验证](verification.md)。

2026-10-10 公共秘密库依赖：固定为官方当前稳定 1.86；实际 Gradle 下载的 `bcprov-jdk18on-1.86.jar` SHA-256 为 `2af190b300cbb0b35e248ccf5f4a06b6072030aeb3da7a98ec73abe5b4cb371f`；完整许可证资源 SHA-256 为 `57339d5c1ec182e43aadb829882987c3eab8fd2cf8d74f2db0ee23c6acdcf6be`，来源为 [官方固定版本许可证](https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/LICENSE.html)。AES-256-GCM 使用 Android/JVM JCA，Argon2id 使用上述轻量 API。JUnit 4.13.2 同时用于新增宿主 JVM 测试，仍不是运行时依赖。兼容性及实际验收另记，不从依赖解析推定设备通过。

SSH 内存文件研究另固定 OpenSSH 10.0p2 官方源码及 hash，见 [探针来源与复现](../.trellis/tasks/10-08-app-authentication/research/ssh-memory-results.md)。该依赖只在 `/tmp` 的 Linux/glibc 研究构建使用，未打入 APK，不作为 Android 原生客户端选版或兼容性通过的结论。

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

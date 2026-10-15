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

## 2026-10-13 SSH Android G1 构建输入

以下是本轮独立 stock 构建研究的固定输入，尚未打入正式 APK，不覆盖上方历史 Linux pin 或 Python 自带 OpenSSL。main 实际下载并核对归档 payload、内含版本和许可证；只校验 SHA256，**未做 PGP 验签**。

| 输入 | 官方来源与实际 SHA256 | 内含版本及许可证核对 |
| --- | --- | --- |
| OpenSSH10.6p1 发布归档 | [官方归档](https://cdn.openbsd.org/pub/OpenBSD/OpenSSH/portable/openssh-10.6p1.tar.gz)，2,355,244 B；`a9dc9565dffe8640f64d863cd29a32bc4a3dbdec0566a7fc44c5d6ee767d5f39`，匹配[发布说明](https://www.openssh.org/txt/release-10.6)的 SHA256 | `version.h` 实际 SSH_VERSION=OpenSSH_10.6 / SSH_PORTABLE=p1；完整多组成 `LICENCE` 21,140 B，SHA `5bb5b160726ef5756e4f32fe95b35249c294962419650f48d05134b486d27ccb`，不能简写成单一 BSD 许可证 |
| OpenSSL3.5.9 LTS 发布归档 | [官方 release asset](https://github.com/openssl/openssl/releases/download/openssl-3.5.9/openssl-3.5.9.tar.gz)，53,279,637 B；`603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a`，与实际取得的[官方 checksum 附件](https://github.com/openssl/openssl/releases/download/openssl-3.5.9/openssl-3.5.9.tar.gz.sha256)一致 | `VERSION.dat` 实际 MAJOR=3/MINOR=5/PATCH=9，release=29 Sep 2026；完整 Apache2.0 `LICENSE.txt` 10,175 B，SHA `7d5450cb2d142651b8afa315b5f238efc805dad827d91ba367d8516bc9d49e7a` |

归档成员已核对为对应单一根目录的普通文件/目录，无绝对路径、`..` 或特殊/link 成员。临时输入位于 ignored `build/ssh-native-g1/input-Y6E5C3fM/`；归档、工具链及后续构建的事实边界见 [阶段检查](../.trellis/tasks/10-08-ssh-terminal-client/check.md) 与 [验证记录](verification.md)。随产品分发前必须保留实际编译组成的完整许可证/声明，静态 libcrypto 也需独立登记，不能据现有 Python 许可宣称已覆盖。

本轮实际兼容基线 `stock-run.lOHF9Oai` 已编译通过，使用NDK27/API28、静态PIC libcrypto及公开NDK zlib。兼容patch SHA256 `ea81738f191e7d9fb70ce0943b45ea06b07c4057d3d0128a694331b3a32f8b89` 修复Bionic头文件顺序、保留真实bzero fallback，并在显式研究守卫下使用公开resolver API；原explicit_bzero实现未改。完整source notices、patch前后hash、配置及失败run均保留。该基线未执行或打入正式APK，不能宣称DNSSEC/主机信任、算法组合或完整客户端通过；G2独立夹具的实际打包/运行另行记录。

2026-10-15 G3.4 fixture caller 继续复用上述 OpenSSH 10.6p1/OpenSSL 3.5.9/NDK27/API28
baseline，不新增生产第三方依赖。hostfile stream-writer patch SHA256 为
`a786566e1842996c0282647457f4dd63558cde00bc79ae1ba6bcd552bf30484a`，KEX guard patch
SHA256 为 `50a59606000dc871f1b29e33eeb6d77fb5da456f40526d2871357758b61f8785`；两者仅供
`android-hostfile-issuer-build.sh` 的 opt-in AArch64 PIE fixture，未进入 normal APK。
runner 的完整 source notices、manifest、object closure 与 patch evidence 保留在
`build/ssh-native-g3/run.3PZSbIzM/`；hako 无 `qemu-aarch64`，ARM runtime 未执行。随产品
接入前仍需按真实生产 source/object closure 重新核对上游许可证与 notices。

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

2026-10-13真实KEX增量继续使用冻结OpenSSH10.6p1/OpenSSL3.5.9输入与完整许可证；独立control fixture额外保留`assets/licenses/ssh-kex/`原始source-notices、局部patch、输入与基线manifest。正常APK排除此目录和KEX程序，不新增生产第三方依赖。

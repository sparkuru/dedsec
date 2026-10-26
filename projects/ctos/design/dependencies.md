# Dependency provenance

2026-10-26 当前[config/无口令私钥路径](ssh-key-config-connect.md)沿固定 OpenSSH10.6p1、
OpenSSL3.5.9 与 NDK27，没有新增运行依赖。项目自有 readconf hook 增加严格 direct-plan
模式，仍用同一 pinned lexer；当前补丁 SHA256 为
`1aaa7df21678bc522a550ddb708e64deb2557327c6f148e2b85d3d9b781780cf`，构建器固定值同步。
私钥公开导出复用真实解析及固定 domain 自签/验签，不引入其他 SSH 库。
隔离互通继续只用既有缓存镜像 `d42d0d9d40e02c441870792bd3f679b446715cee38f81354366a33c9b0d84573`，
关闭密码、临时生成身份并清理；不打包测试服务或测试密钥。

2026-10-26归档收尾：9份固定SSH构建/测试runner与6份patch迁入`android/ssh/`，Gradle
和runner不再依赖活动任务目录。补丁字节、固定source/archive SHA、baseline/no-symlink/
forbidden检查及脚本权限保留；没有新增依赖或产品运行代码变化。原研究记录保留历史
调用路径，当前执行入口以稳定目录为准；Markdown文件链接同步指向当前实际文件。

2026-10-25：[密码连接](ssh-password-connect.md)复用固定OpenSSH/crypto、Android公开
Os/PFD和现有Flutter组件，无新增运行依赖。独立互操作测试使用已有缓存的
linuxserver/openssh-server镜像`d42d0d9d40e02c441870792bd3f679b446715cee38f81354366a33c9b0d84573`
（OpenSSH_10.3p1/OpenSSL3.5.7），仅作临时测试服务，不打包进产品；与同库合成peer和
Android测试分别记录。[本轮证据](verification.md#20261025-password-connect)。

2026-10-25 用户更正：[SSH采用唯一正式模型](ssh-manifest.md)，去除双格式业务分支。
不增加依赖或修改native协议/crypto来源，四份既有cached wheel与source SHA/ZIP本轮重核；
[本轮构建与检查](verification.md#20261025-ssh-mainline)。下方为历史记录。

2026-10-25：[v2清单基础](ssh-manifest.md)复用Java集合/现有codec和认证envelope，
没有新增依赖、parser补丁、JNI或native/Flutter源码变化。四份既有cached wheel及原source
SHA/ZIP完整性本轮重核，临时offline init显式no-index；构建与工件记录见
[验证](verification.md#20261025-manifest-v2)。历史ARM/Android运行证据不作为本轮重验。

2026-10-24：[全部证书事实与签名政策](ssh-certificate-facts.md)已实现CSCF168、三份公开摘要/unsigned数值/实际record来源及现代CA签名政策，保留trust/time/principal/revocation未验证。ARM22239与旧材料矩阵、定向145/全量638 JVM、normal100全重跑/fixture111及三APK审计通过；[实测](verification.md#20261024-certificate-facts)。持久graph/账号、完整信任/reviewer/provider/恢复/connector与父AC仍开放；本地未提交，无设备/真实库账号。

2026-10-23：[全部私钥材料与凭据归属](ssh-private-material-ownership.md)已实现全PRIVATE自签/验签、v1口令唯一owner/phrase审计和password namespace/deferred target。ARM19976/独立pair13812/material7784、定向120/全量613 JVM、normal98全重跑/fixture111及三APK审计通过；[实测](verification.md#20261023-private-material)。仍不提供持久关系图/真实密码账号/完整信任/provider/恢复/connector准入，父AC开放；本地未提交，无设备/真实库账号。

2026-10-23：[显式密钥配对证明](ssh-key-association.md)已实现精确ID/所选口令、证书plain subject及固定domain实签验签，拒绝仅公开字段相等的伪配对。ARM13814/独立材料7784/fresh认证1748333、定向80/全量586 JVM、normal96全重跑/fixture111与三APK审计通过；[验证](verification.md#20261023-key-association)。报告只覆盖明确所选pairs；全库关联authority/覆盖、凭据目标、完整信任/reviewer/provider/恢复/connector与父AC仍开放。本地未提交，无设备/真实库账号。

## 2026-10-23 — CONFIG 显式资源引用

沿用OpenSSH10.6p1/OpenSSL3.5.9/NDK27与83成员archive，无新第三方依赖。namespaced readconf同lexer新增显式资源hook，补丁SHA256为`7afd7a9a4ed9c17096b1bb4a3cd20d41ec9ed2d5720df5c552da93384d60fd5e`，runner一致、固定G1/上游源树未改、原LICENCE继续复制。新增两JNI后config15/session19/material1。既有四wheel重新SHA/ZIP核验并临时显式no-index复用，依赖声明不改。[证据](verification.md#20261023-config-resources)。

## 2026-10-23 — CONFIG 静态引用与本地效果审计

沿用固定OpenSSH10.6p1/OpenSSL3.5.9/NDK27与83成员archive，无新第三方版本或生产依赖。项目自有AUDIT模式复用同一readconf lexer/Match grammar/Include递归，有限政策helper留在namespaced parser TU。parser补丁SHA256更新为`72f09e8a4cb7766c80e1ebb3123b34a61ac6e5d361bcaa611b36156a2a75f17b`，runner固定值一致；原G1/上游源树hash保持、构建复制原LICENCE。新增reserveAudit/audit两JNI后config13/session19/material1。四既有wheel重新SHA/ZIP检查，临时显式no-index/find-links继续复用，依赖声明未改。[证据](verification.md#20261023-config-audit)。

## 2026-10-23 — 实际清单受管配置检查

沿用固定OpenSSH10.6p1/OpenSSL3.5.9/NDK27、原readconf补丁与83成员production archive，无新第三方依赖。项目新增CSDI两阶段owner、usage与全CONFIG inspector；configJNI由8增至11，session19/material1保持。临时Gradle init复用已核验四既有wheel，明确no-index/find-links，生产依赖声明未改。[实际验证](verification.md#20261023-detached-managed)。

## 2026-10-23 独立 native 配置输入

沿用固定OpenSSH10.6p1/OpenSSL3.5.9/NDK27及原readconf补丁/83成员archive，无新生产依赖或JNI。新增项目自有文件集合/liveness适配与CONFIG独占输入。构建源站失败后，只在忽略build目录复制既有四wheel，以临时Gradle init显式no-index/find-links验证；依赖版本/声明未改。[证据与hash](verification.md#20261023-detached-config)。

## 2026-10-19 实际清单材料检查

继续使用固定OpenSSH10.6p1/OpenSSL3.5.9/NDK27，无版本变更或新第三方依赖。项目新增
ssh_key_policy共享原认证策略、ssh_material_inspect/JNI与Java离线owner；复用固定
sshkey/hostkeys_foreach_file，不另建known_hosts parser，不改原83成员archive或G1源树。
无目标hostfile遍历的hash盐/digest检查由窄framing补查完成，避免fake host/HMAC。
新增一个material JNI，原八config/十九session JNI保持；原声明parser补丁不改。
SK在固定config中禁用，不能以测试期望推断运行支持。构建器固定原输入hash/树校验和
LICENCE，static compat/TLS只用于独立测试。[本轮证据](verification.md#20261019-manifest-inspection)。

## 2026-10-19 声明catalog

沿用固定OpenSSH10.6p1/OpenSSL3.5.9/NDK27，没有新依赖。独立readconf补丁新增同一词法
入口后的catalog分支、静态Include模式和展开前动态token延后；原G1不改。补丁SHA256
`fc52b0d65d9bbd6e5b659a16b3a25b0d269bb59ecaa241009c15c7968d26ba07`
与构建器固定值一致。83成员production archive保持，config JNI增为八个，session JNI仍19。
最终输入与工件对应见[验证](verification.md#20261019-config-catalog)。

## 2026-10-19 配置JNI/Java边界

沿用同一固定OpenSSH/OpenSSL/NDK，无新增第三方依赖；增加项目自有opaque handle/wire/JNI
及Java facade。原83成员archive闭包、parser patch、session/KEX源不改，六个config JNI独立
于原19session JNI。生产runner临时路径会影响工件hash；本轮冻结编译输入后打包，normal/
fixture生产库字节一致，静态回归使用同一最终archive输入。[证据](verification.md#20261019-config-jni)。

## 2026-10-19 受管配置求值器（本地实施）

继续使用固定OpenSSH10.6p1/OpenSSL3.5.9/NDK27，没有新第三方版本。新增
[独立readconf补丁](../android/ssh/openssh-10.6p1-config-evaluator.patch)
及[构建器](../android/ssh/android-config-evaluator-build.sh)，
只在临时目录复制/patch已校验的parser，原G1源码及83成员production archive不改；
readconf.o/config_eval.o独立链接到既有生产库，不引入stock ssh/sshconnect或第二套crypto。
窄复制misc helper在适配器保留原版权/许可，构建输出附固定源码LICENCE与输入hash。
具体合同和验证状态分别见[求值器](ssh-config-evaluator.md)与[验证](verification.md)。

## 2026-10-19 Android/JNI authentication integration

继续复用 pinned OpenSSH10.6p1/OpenSSL3.5.9、NDK27；没有新依赖、上游补丁或archive成员。
生产archive83，session JNI扩为12。fixture peer仅在显式两个Gradle opt-in下动态链接生产
libctos_ssh_files，不引入第二份OpenSSH/crypto；普通APK排除peer库和类。宿主QEMU/Bionic
证据与Android API30实测分开，格式/KDF边界见[本轮验证](verification.md#20261019-android-auth)。

仅在本项目记录依赖，不修改顶层 third_party。

2026-10-19 C-only password USERAUTH 复用 pinned OpenSSH packet/string/padding、digest与
现有selected callback，无新增依赖、上游补丁、archive成员或JNI入口。archive83/sixJNI
保持；正常send的plaintext擦除与构包失败reset-only的边界见[验证](verification.md#2026-10-19--explicit-native-password-userauth-protocol)。

2026-10-19 selected credential handoff 复用现有 callback/JNI、OpenSSH digest 和
AppSecrets EffectGuard；无新增依赖、上游补丁或 archive 成员。session JNI 仍为6个，
archive83；QEMU/Bionic 只作本地合成验证，[证据与限制](verification.md#2026-10-19--receipt-bound-selected-credential-handoff)。

2026-10-19 CSASv1复用现有Java编码/SHA256、native OpenSSH digest与view-path校验，无
新增依赖、上游patch或archive成员。只读receipt新增一个sessionJNIexport（共6）；
production archive仍83，publicblob保持声明而非解析证明。见[验证](verification.md#2026-10-19--csas-native-selection-metadata-and-receipt)。

2026-10-18 managed authentication selection 仅使用现有 Java UTF-8/ByteBuffer 和 vault
合同，无新增依赖、native object/patch 或凭据持久格式。C/JNI源码未改；既有production
builder每次强制重建归档，生成物hash变化与sourceidentity分列，见[验证](verification.md#2026-10-18--managed-immutable-ssh-authentication-selection)。

2026-10-18 post-KEX service 切片沿用现有 pinned OpenSSH sshpkt/dispatch API 和 83-member
production closure；未添加上游对象、源码补丁或生产依赖。QEMU/Bionic 继续仅作本地
合成测试工具；新执行证据见[验证](verification.md#2026-10-18--native-post-kex-userauth-service-negotiation)。

## 2026-10-17 production OpenSSH banner patch / local ARM runtime

在既有固定 OpenSSH 10.6p1 基线上新增项目补丁
[`openssh-10.6p1-banner-atomic.patch`](../android/ssh/openssh-10.6p1-banner-atomic.patch)，
SHA256 `ba61f35303233ebd2bf1c39d167774667766b3461721657f8af0483e51258a18`。
它仅让 `_ssh_read_banner` 在输入不足完整行时清空未发布的 version buffer，保留 input
供下次重试。生产 builder 校验固定 hash、在 scratch 中应用、编译 PIC `ssh_api.o` 替换
原 archive 成员；G1 source/tree hash 和 83-member closure 不变。patched `ssh_api.c`
SHA256 为 `ea50ae877474623fcd369fa85ac2c69b5ab1ba231bc25ec2117cefe81e83c4bf`。
沿用已有完整上游许可证，不修改相邻目录或引入新生产依赖。

宿主已有 `qemu-aarch64` 10.0.13（Debian 1:10.0.13+ds-0+deb13u1）仅用于本地验证，
未加入 APK。临时 runtime 只读来自当前 `.14` API30 板的公开 linker64 与
libc/libdl/libm/libz；文件及 hash 保留于 `/tmp` 证据目录，未入仓库或安装到全局路径。
这是 QEMU/Bionic synthetic ARM 执行，与 Android JNI 实机验证分开记录，详见
[验证](verification.md)。

## 现有依赖

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

归档成员已核对为对应单一根目录的普通文件/目录，无绝对路径、`..` 或特殊/link 成员。临时输入位于 ignored `build/ssh-native-g1/input-Y6E5C3fM/`；归档、工具链及后续构建的事实边界见 [阶段检查](../.trellis/tasks/archive/2026-10/10-08-ssh-terminal-client/check.md) 与 [验证记录](verification.md)。随产品分发前必须保留实际编译组成的完整许可证/声明，静态 libcrypto 也需独立登记，不能据现有 Python 许可宣称已覆盖。

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

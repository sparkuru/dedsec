# Verification

## 2026-10-10 — APK 编译预览入口

- 新增 `./preview.sh`（默认或 `build`），复用 `hako` 的 arm64 release 编译；不安装设备、不替换 `dist/`。用户仅要求这个原生 APK 入口，Trellis Plus 服务生命周期、监听地址和环境文件增强不适用，其他增强未执行。
- `bash -n`、ShellCheck、`shfmt -d` 通过。`/tmp` 隔离桩覆盖默认/build 参数、其他工作目录、路径含空格、帮助无副作用、非法参数、缺少 wrapper/Docker、缺失/空 APK，以及构建失败且存在旧 APK 时不打印成功结果。
- 从 `/tmp` 使用脚本绝对路径实际执行，退出 0；Gradle 14.2 秒，末尾 APK 路径和 `adb install` 命令与文件一致。产物 `build/app/outputs/flutter-apk/app-release.apk`，32,209,925 字节，SHA-256 `14fc814678f005d4614a885b971a6060ba89c0058db4ff1cbe3259959c204850`；日志 `/tmp/ctos-preview-build-20261010.log`。`dist/ctos-current-arm64.apk` 及 `SHA256SUMS` 哈希保持不变。
- 沙箱首次执行因 Docker socket 访问受限以 126 退出，没有打印成功路径；随后仅对该编译命令按工具权限机制重试并成功。没有运行 ADB 或重新执行产品验收；构建不改变秘密库任务仍在进行的状态。
- 该 APK 来自包含尚未提交秘密库实现的当前工作区；此处哈希不作为本次仅提交编译入口后独立 checkout 的产物保证。

## 2026-10-06 两处显示文案精简

按用户要求不建 task，测试并提交用户在 `lib/main.dart` 的两处修改：顶部移除 `SYSTEM OBSERVATORY`，概览标签由 `DEVICE / 设备观测` 简化为 `DEVICE`。

- 使用既有 `./hako`：Flutter 3.35.7 / Dart 3.9.2。
- `./hako flutter analyze` 无问题；`./hako flutter test --reporter expanded` **103 项全部通过**，包含概览 320/375/800/1200 dp、双倍文字和减少动画的现有组件检查。
- `./hako dart format --output=none --set-exit-if-changed lib/main.dart` 通过，0 文件需格式化；`git diff --check` 通过。
- 初次提交只验证源码，未重建 APK；随后用户明确要求 build，基于提交 `3e0833e` 执行 `./hako current`：arm64 release 构建成功（约 26.3 MB），签名校验通过，并原子更新 `dist/ctos-current-arm64.apk` 与 `dist/SHA256SUMS`，校验和检查通过。当前 APK SHA-256：**`b7e67386f6b0058f0898320f654aaea856669ded78fc0432c516409fb76d9d7b`**。
- 本轮未安装到设备、未执行设备操作或截图视觉验收。上次实机结论仍只对应其原包。

## 2026-10-05 全 Flutter 品质优化：最终代码与视觉复验

用户批准整个 Flutter 实施和配置 A 验证，设备短暂离线后回复“已恢复，继续”。本轮最终包 `dist/ctos-current-arm64.apk` SHA-256 **`37d226009771ef8711196d91542f9b2c8b95d600ec238c35bf84709b97843c01`**；证书 SHA-256 `8d36c8be418174cb4cda0b897ff3e32b2da6f84032819726e8e5671872df52ef` 与旧安装一致。`adb install -r` 成功，最终设备 base.apk 哈希一致；旧包备份后覆盖，保留 App 数据。

- **本地最终检查**：`./hako flutter analyze` 无问题；`./hako flutter test --reporter expanded` **103 项全部通过**；`./hako current` arm64 release 构建、签名与 `dist/SHA256SUMS` 校验通过。日志 `build/flutter-polish-evidence/{analyze-final,test-final,release-final2}.log`。Dart 源码由实现/检查 agent 格式化，最终 diff 另查；没有 native/Python 或依赖变更，未重复运行其他层全量测试。
- **真实 Flutter 渲染**：`./hako flutter test build/flutter-polish-evidence/preview_test.dart --reporter expanded` **1 项通过**，107 场景、127 张矩阵图片，错误数组均为空。范围为 320×568、375×812、900×450、1200×900 dp，正常/2 倍文字与减少动画；八个核心画面、九项工具表单/结果、五种 HFTP 状态、文件/历史/导出/终端输出、受限/失败/取消/超时及三种清理弹窗，含下方滚动内容。当前 catalogue、公开响应夹具和显式 CJK/Roboto/monospace/MaterialIcons；22 文件源清单最终哈希匹配，不用 HTML 代替 Flutter。
- **视觉迭代**：完整页面审查后收紧概览/目录/设备字段密度、降低历史/日志权重、提高必要输入边界对比；全矩阵复渲染后再修复原始时间戳、结果短字段、大字 IP 标签与横屏清理影响说明。Trellis check 及主 agent 最终图片复验未发现已证实的明显残余项。八维发现/修复/复验见 [任务检查](../.trellis/tasks/archive/2026-10/10-05-flutter-experience-polish/check.md)。不将测试绿灯单独当作视觉验收。
- **IP 补充复验**：长矩阵一张 IP 结果图的首数字未绘出；独立挂载真实组件，在主页/详情路由 400 ms 和稳定帧均完整显示三个 `198.51.100.8`，文本与首字符位置断言通过。原图保留限制，有效复验图 `build/flutter-polish-evidence/ip-probe-true-settled.png`；未据此改产品代码。
- **配置 A 当前设备**：Android 15 / API 35、Xiaomi 22041216UC、1080×2460 px、当前 378 dpi、文字缩放 1.0。每条 ADB 显式指定当前目标，serial 不提交。此次结论仅属于最终包及该手机，不沿用历史开发板/Root/仪器测试结论。
- **实机已完成路径**：概览/设备/网络/接口/连接/工作台入口；密码工具使用公开测试种子，本机运行完成、16 字符结果默认遮罩、复制进入 Android 剪贴板、历史页可返回。App Shell 普通键盘输入 `id` 返回 App 身份，`pyth` 经 Tab 补为 `python3`，历史键恢复 `id`；输出快照搜索 `uid` 找到 1 处、复制全部、返回保持原会话；最终关闭本轮 PTY。键盘出现时底栏让位，控件与输入保持可达。`whoam` 没有可补命令，未误判为 Tab 故障。
- **导出/HFTP 与最终页面**：导出未选择时主按钮禁用；选择网络快照后实际打开 Android SAF 保存界面，返回显示“文件选择已取消，未创建文件”，未创建外部文件。HFTP 实屏显示已停止、启动主动作、既有局域网/7888/32 MiB 配置与目录选择；只读查看，不启动/变更服务。最终重采概览/设备并回到概览，安装 hash 对应全部这些截图。19.76 秒本地录屏及逐秒接触表确认页面切换与实际数据刷新，无持续闪烁；抽样不代表逐帧性能或所有路由动效验收。
- **状态/动效边界**：减少动画、失败/受限/停止竞态/取消和清理效果以确定性 widget 测试/渲染验证；实机交互证明关键动作可执行，不声称量化帧率。Root 自动恢复属于既有启动路径，未改 Root 授权、其他应用、网络、系统字体/旋转配置。此轮未重测 Root PTY、服务后台/熄屏/LAN 传输或全部 ROM；既有 HFTP 熄屏限制仍有效。

原始 APK 备份、截图及控件树在 `/tmp/ctos-flutter-polish-20261005/device/`，含设备信息，仅本地临时保留、不提交。公开组件证据在忽略的 `build/flutter-polish-evidence/`；若临时目录被清除，需按当前授权重新生成。提交前归类 `human-required` 的观感审阅已于 2026-10-06 通过：用户回复“观感通过，按计划提交”。自动化、代理图片审查与设备交互结论仍按上述实际范围解释。

## 2026-10-05 全 Flutter 品质优化：规划与改版前基线

以下是批准前历史：用户当时仅同意创建任务/规划，产品代码未改动。后续批准与最终结果见本文件上方，设计及矩阵见 [全 Flutter 界面品质优化](flutter-experience.md)。

- `./hako flutter --version`：Flutter 3.35.7 / Dart 3.9.2；`./hako flutter test`：当前源码 **79 项全部通过**。未运行改版后检查，也未重建/替换 APK。
- 配置 A 当前在线目标的只读探测确认 Android 15、1080×2460、当前 378 dpi。打开已安装 `im.majo.ctos` 并截图，显示旧 Vector/旧导航；这是旧包视觉参考，不能作为当前源码功能验收。启动运行了该旧包的既有生命周期，未执行终端命令或调整 Root/系统配置。
- 当前仓库 APK SHA-256 `ab13ceac96a7a8958659cb9bb12ab298dd65d9fa22b3ec729d2f5d26e30e014f`。此次未覆盖安装、未清数据、未修改网络/其他应用。
- 原始截图 `/tmp/ctos-flutter-polish-20261005/baseline/overview.png` 为临时证据，不提交；若目录被清理，需重新确认设备并重采。目标 serial 按既有脱敏约定不写入项目文档。
- Trellis implement/check 上下文校验通过。视觉、宽度矩阵、减少动画、完整新包设备验收均待实施，79 项基线不能代替这些结果。
- 规划补充：复用已检查的 `build/workbench-usability-previews/preview_test.dart`，执行 `./hako flutter test build/workbench-usability-previews/preview_test.dart --reporter expanded`，**1 项通过**；生成并逐张查看六张当前源码组件基线图。采用公开模拟数据、旧目录元数据和显式字体，不是设备/真实执行证据；HFTP 图缺少配置响应，只能表示未就绪布局。夹具哈希、图片和具体发现见 [基线检查](../.trellis/tasks/archive/2026-10/10-05-flutter-experience-polish/check.md)。

## 2026-09-28 界面与终端可用性整理（P2）

本 task 在用户另行授权后，向 `TARGET-BOARD`（AIO-3568J、Android 11 / API 30）安装 release 包 `im.majo.ctos`。本地产物 `dist/ctos-current-arm64.apk` SHA-256 `ab13ceac96a7a8958659cb9bb12ab298dd65d9fa22b3ec729d2f5d26e30e014f` 与安装前核验值一致，`adb install -r` 返回 Success；未卸载既有包或清除 App 数据。ADB serial 依本文件的证据脱敏约定不记录。

- `./hako dart format` 完成；`./hako flutter analyze` 无问题。定向测试 `test/terminal_interaction_test.dart test/state_ui_test.dart` **12 项通过**；全量 `./hako flutter test` **79 项通过**。`./hako current` arm64 release 构建和签名校验成功，`dist/SHA256SUMS` 校验通过。
- 启动 ctOS 时运行了既有 Root 自动恢复流程，App 报告 Root 在线；没有单独读取 `auto_start` 偏好值。
- 实机 App Shell 输入框可唤起 Android 普通软键盘。快捷栏折叠后五个按钮从 UI 控件树移除，重新展开后恢复。执行只读 `id` 返回非 Root 应用身份；执行 `sleep 60` 后使用快捷栏 Ctrl-C，终端出现 `^C` 并回到 `$` 提示符。
- 从当前终端打开输出选择页，搜索 `sleep` 显示 **找到 2 处**并高亮两处，点击“复制全部输出”显示**已复制全部输出**。返回后原 App Shell 的历史输出与提示符仍在，PTY 未重启。
- 用户另行授权 Root PTY 的只读身份检查，输出 `uid=0(root)`。随后关闭 Root PTY；后续复核用 App Shell 会话也已关闭。未运行 Root instrumentation tests，没有改系统/VPN 配置、清除 App 数据或重启。
- 屏幕空闲进入休眠时，截图会显示时钟而非应用；触屏唤醒后前台 Activity 和完整终端输出恢复。没有修改设备休眠设置。截图与 UI hierarchy dump 仅用于本轮即时检查，没有作为项目产物保留。

## 2026-09-28 只读任务闭环验收

在用户指定并授权的 `TARGET-BOARD`（Android 11 / API 30、arm64-v8a）完成本任务验收。通过 `adb install -r` 覆盖安装 release APK 和匹配的 release test APK，保留原 App 数据；设备上两个 APK 的哈希分别与本地产物 `56b3ca3c580c5df97fdd5d5502782f2fad90bd53e89730ef38209947e82963d2` 和 `aee3475d38d2948128c488fb3fd3934bcaeb0be84df2776d4acd504bbe00239f` 一致。

- `./hako dart format` 覆盖本轮修改的 Dart 源码和测试；`./hako flutter analyze` 无问题，`./hako flutter test` **76/76 通过**。Python fake Context 边界检查通过：接口状态、不可用计数器、非法或消失接口保持各自语义。
- Android `:app:lintRelease`、`:app:compileReleaseAndroidTestJavaWithJavac`、`:app:assembleRelease` 和 `:app:assembleReleaseAndroidTest` 均 **BUILD SUCCESSFUL**。
- 指定仪器测试通过：`TaskHistoryStoreTest` **OK (3 tests)**，覆盖 allowlist、文件重载和清理、20 条/5 MiB 淘汰、失败/取消/超时状态及损坏恢复；仅运行 `DeviceTest#interfaceDiagnosisUsesExactFreshAppSnapshotAndCanBeStored`，**OK (1 test)**，确认使用新鲜 App 快照、只传单接口投影并持久化结果。没有运行其他 DeviceTest、Root 或 PTY 测试。
- 手动界面检查：Root 列表中一个接口不在新鲜 App 快照内，按预期得到失败并保留失败状态；另一个 App 可见接口诊断成功，来源显示为 App。执行 `am force-stop` 并重新启动后，历史页仍显示上述成功与失败两条记录。
- 导出预览中仅勾选成功的历史记录，网络和设备快照未勾选。通过 SAF 保存到唯一测试名后读回 JSON，顶层仅含 `exportedAt` 和 `taskHistory`，其中恰有一条记录且没有 `networkSnapshot` / `deviceSnapshot`。按返回键取消另一唯一测试名的保存，目标文件不存在；随后在历史页确认清除两条记录，页面显示空状态。已从 Downloads 精确删除本轮创建的导出文件；取消路径始终未创建。
- 按用户授权启动 ctOS，运行既有启动时 Root 自动恢复流程；没有运行 Root 测试。未修改系统或 VPN 配置，未清除 App 数据。临时截图和回读 JSON 验收后已从 `/tmp` 删除，未作为项目产物提交。

`git diff --check` 与 `python3 ./.trellis/scripts/task.py validate 09-25-read-only-task-loop` 通过。设备标识按本项目脱敏约定记为 `TARGET-BOARD`。

## 2026-09-28 Firefly 开发板当前包定向验收

`adb devices -l` 本轮仅列出 `BOARD_ADB_SERIAL`，设备自报 AIO-3568J、Android 11 / API 30、arm64-v8a。显式指定该序列号读取 `pm path`、`dumpsys package` 和已安装 `base.apk` 的 SHA-256；ctOS 已安装，但安装时间为 2026-09-24，包哈希为 `22ab383fbaf02024b59f68ef958224a9215625aca58a87b6dd23b724511c2bcd`。本地 `dist/ctos-current-arm64.apk` 为 26,193,940 bytes、SHA-256 `9ca71e316525e5b5ba6a771f855f3df2d3b52342eb75162594e7972c3518c2e3`，与板上包不同。现有测试 APK 哈希为 `3cc68b0823d1cbde047484ab0cb088b82e5f2dde7ac048cbb6714dcb10319be1`。

经用户明确授权，仅向这块板 `adb -s "$BOARD_ADB_SERIAL" install -r` 覆盖安装上述当前 APK 和测试 APK，保留 App 数据；两次安装均为 `Success`，设备上两个 `base.apk` 的 SHA-256 分别与本地产物 `9ca71e31…` 和 `3cc68b08…` 完全一致。安装前 ctOS 服务列表为空；设备为 SELinux Permissive。

指定七项非 Root 仪器测试一次合跑 **OK (7 tests), 30.17 s**：Python 3.13.9/SDK 目录、自检与未知脚本拒绝，运行中取消/超时及后台任务回收，App 网络快照，文件输出不覆盖、加解密认证篡改拒绝与编码产物，HFTP 在 `127.0.0.1` 随机端口的后台通知、上传/下载、限额/不覆盖/路径拒绝及显式停止。测试自有文件由测试 `finally` 清理；结束后 `dumpsys activity services im.majo.ctos` 为空。安装与测试均未清除 App 数据、改系统配置或开启 LAN 服务。

本轮没有运行需要 Root 授权的采集或 Root PTY 测试，也未做 SAF 手操、真实 IP 查询、熄屏/LAN 连通、通知拒绝、API 28/29 或原生 16 KiB 页验证。结论仅覆盖当前包在这块 Android 11 板上的上述七项。具体目标地址以本轮确认的 `BOARD_ADB_SERIAL` 为准，不能把项目文档中的默认地址当作当前连接状态。

2026-09-28 提交决定：用户已在当前实测结果和已知熄屏限制基础上要求提交，并要求本轮内容脱敏。脱敏复核与工作提交已完成，当前专项任务按收敛范围归档并记录 journal；无新增设备操作或 Root 电源授权。下文各阶段“未提交/待测”保留为历史状态，以首项最终记录为当前结果。

## 证据脱敏约定

`TARGET-PHONE` / `TARGET-BOARD` 表示当次确认的设备；ADB 序列号、设备产品标识、局域网地址、接口名、App UID 与 owned PID 均以角色占位符替代。PHONE / WINDOWS / TEST-CLIENT 地址仍表示同一局域网中的不同对象；文档中的 TEST-NET 地址仅为已脱敏示例，不表示重新执行的真实网络。已安装产物哈希、Android/API、权限条件、传输大小、时间与成功/失败结论保留。

重放命令前需设置 `PROJECT_ROOT`（项目绝对路径）、`EVIDENCE_DIR`（独立临时证据目录）及当前授权的 `PHONE_ADB_SERIAL` / `BOARD_ADB_SERIAL`；`${PHONE_DOWNLOAD_DIR}` 表示授权设备的测试下载目录。证据文件名用于关联历史产物，不保证临时文件仍存在；需重建夹具后才能重放，不能将占位符直接视为可达地址。

2026-09-28 提交脱敏只修改文档/任务信息和两处测试地址字面量，生产源码未改：Java 的非法监听地址与 Dart 的 mock 日志/期望改用 TEST-NET。下方已安装 APK、测试 APK 和实机结果属于此前实际产物，不宣称脱敏后的测试 APK 已安装或重复了手机验收。

## 2026-09-27 HFTP 停止错误追加回归（进行中）

2026-09-28最终记录：当前9ca71e…APK安装哈希一致、14份Python/native来源核对、73项Flutter/analyze、Androidlint0errors/5既有warnings通过；修正后十项TARGET-PHONE定向检查9.077s全部通过。三轮LAN PUT201/GET200精确（两轮1,376,257B、一轮31MiB）、真实App停止/进程端口CPU锁回收及同7888重启通过，清空日志后31MiB再读回/返回重进正常。默认私有目录/LAN7888/32MiB/Roottrue恢复，五个owned文件及空隔离目录精确删除，服务停止，VPN lockdown1/Clash bypassablefalse保持。**熄屏仍拒绝连接**：登记App锁不等同于有效保活，ROM记录REL/后续lightIdle；候选的具体退出日志因锁屏未取得，不写成已直接证明的Root退出原因。Root临时CPU租约候选另见任务design，用户已选择暂不扩大Root范围，候选未实现/未触发，熄屏问题作为已知限制保留；暂不提交/归档。完整实测与初次测试误报保留在[实机记录](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/restart-device-check.md)。

同设备ADB已改为PHONE-ADB-SERIAL。最终Service修复候选5d4e5c…安装哈希一致，七项定向手机测试通过（7.135s）；第一Root LAN1,376,257B精确读写/停止/三个owned PID与端口回收/7888重启通过。第二轮上传201但下载读回超时；同文件醒屏后两种读取方式均精确成功。屏幕关闭后GET拒绝，Root日志heartbeat_timeout；追加控制队列/期限竞态与closed原因诊断回归，新版休眠及连续流程待测。见[追加实机记录](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/restart-device-check.md)。

控制队列修复本地24轮精确传输/重启、queuedPING存活与9项控制/独占检查、严格host/NDK和Androidlint/testAPK通过，独立复核通过。168cedec…安装哈希一致、亮屏GET精确且closed reason=complete；熄屏仍两次拒绝/heartbeat_timeout，实际前台服务已确认。正在追加Root adapter App CPU锁R25，不能把上述局部通过写成最终验收。参见[传输复核](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/transfer-review.md)。

用户确认点击 App「停止」后立即报错。TARGET-PHONE 当前无存活 HFTP Service/owned 子进程；重新进入页面后保留六条日志显示 18:46:52 一次 Root 启动在 Python ready 后 `listener_bind (errno 98)`，随后精确关闭。该证据不能证明用户停止操作主动重启了服务。原页面日志/config 区有灰色 ErrorWidget，ctOS PID OWNED-PID 每两秒报 Flutter DiagnosticsProperty 异常，首次栈已不在缓冲区；同 PID 返回再进入恢复日志。追加 R21–R23 回归正在处理，不将此前单周期通过视作本次通过。

本地 native 已复现真实 TCP TIME_WAIT 后同端口重启 errno98；checked SO_REUSEADDR 后 20 轮实际传输/STOP/即时重启通过，活跃监听器仍拒绝第二 owner，原15项回归/严格 NDK 编译通过。旧无 reuse 版本遗留 TIME_WAIT 的复用边界另记，未改系统 TCP/VPN/路由；新最终包实机连续重启待测。见[本地记录](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/restart-native-check.md)。

## 2026-09-27 HFTP Root分离与日志最终验收

记录：[Root实机检查](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/root-device-check.md)、[全范围审查](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/root-log-check.md)。用户明确授权Root网络中继，仅TARGET-PHONE `PHONE-ADB-SERIAL`、Android16。

- 最终APK SHA256 `0575e00d5d7fb732d02ec4923563e4dfd68e0008eae17480791e753ad29eaf1b`，26,189,072 bytes；构建/签名/校验和及安装base.apk逐字节一致。14份Python源码与packaged relay来源核对通过。
- Flutter68/68、analyze无问题；Python25/25；Android lint0 errors/5既有warnings/testAPK通过；native15项本机网络夹具+最终3项定向复验/NDK API28严格编译及16KiB ELF对齐通过。Browser loopback九项复验通过。
- 六项手机定向检查最终通过，含能力缺失/未知/不匹配效果前拒绝、App UID执行helper被EPERM拒绝、日志脱敏/上限/会话、普通App服务后台/停止。首次日志断言误匹配序列化JSON，改查实际字符串后单项复验通过；App包未改。
- 保持VPN不可bypass、always_on_vpn_lockdown=1：WindowsWINDOWS-LAN-IP用户确认可以打开，日志记录其GET/200；LANTEST-CLIENT-LAN-IP实际33MiB上传201/下载200/哈希一致，409不覆盖/413限额/后台读回通过。Python UID APP-UID，Root helper UID0；Root仅网络，文件/SAF仍App权限。
- UI清空后不停止且继续写日志；停止后保留。owned Python/su代理/helper三个PID均退出，LAN端口ConnectionRefused。默认私有目录/LAN7888/32MiB/Root关闭及停止状态恢复，三个公开夹具与空隔离目录精确清理；没有读取/清理原有共享库，没有改变VPN/路由/防火墙/SELinux。
- 运行截图`${EVIDENCE_DIR}/ctos-feedback-hftp-root-running-20260927.png`、停止/恢复截图见详细记录；这些是临时证据，设备检查结果已摘要保存在本项目。

物理无Root设备完整流程、其他ROM/Android11、真实16KiB页、Wi-Fi真实切换/丢失和五小时持续运行未测，不从mock/QEMU/同一手机推定。页面产品审阅/提交仍待确认，任务保留in_progress；先前d43f52ed…版本LAN超时为历史结果，本版已实测修复指定Root模式。

## 2026-09-27 九张图片反馈：最终源码与 TARGET-PHONE

最终 `d43f52edb9f90b083079579ef6b14cf99ce5dccd70c132a858ddb6f276288e4c` APK（26,135,519 bytes）构建/签名/发布校验通过，覆盖安装 TARGET-PHONE `PHONE-ADB-SERIAL` 后 base.apk 哈希一致；APK 内14份 Python 源码逐字节匹配。没有转换成 Java：工具与HTTP仍为Python，Java承担Android权限/生命周期/SAF流式访问。详见 [反馈源码复核](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/feedback-check.md)、[反馈实机记录](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/feedback-device-check.md)。

- analyze 无问题、Flutter **52/52**、隔离 Python **21/21**、Android lint **0 errors/5既有warnings**、测试APK编译、隔离loopback浏览器9项通过。检查服务协议、CLI认证兼容、大文件/短写/中断/无覆盖/路径边界，以及UI准确参数、宽度、普通IME配置及过期轮询保护。
- 最终手机公开seed输入圆点遮挡、EditorInfo **inputType=0x1**、原有普通键盘生效；没有调整系统键盘。首次中间包 false suggestions 被引擎转为VISIBLE_PASSWORD，已修为 enableSuggestions=true；普通键盘可能显示建议。
- 原生最终4项组合 **OK (4 tests),1.687s**，含真实Downloads授权、配置/路径、请求owner保护与loopback前台通知/后台/上传/停止；另1项 **OK (1 test),0.009s** 确认ExternalStorage授权。通过系统picker选择自建隔离目录，不猜路径或申请全文件/Root权限。
- UI LAN/7888/64MiB启动后，经本任务ADB转发 **33MiB 上传201、下载200、34,603,008 bytes SHA完全一致**；同名409后内容不变，超过64MiB返回413；子目录建立/上传、切后台后34B读回正确。ExternalStorage能读回原ADB文件；Downloads MediaStore视图不显示未索引的原ADB文件，服务遵守provider可见文档边界。
- **跨设备LAN未通过**：电脑直连3秒超时；只读路由为LAN-INTERFACE/源TEST-CLIENT-LAN-IP，手机shell访问自己的LAN地址200，ADB转发也可用，证明HTTP监听/目录传输正常。外部端口受阻的具体原因尚未确认，没有改VPN/防火墙，也没有把免登录视为原Firefox8080故障的确定修复。
- 服务已停止（services为空）、本任务forward移除；恢复LAN/7888/32MiB及默认私有目录，精确删除自建外部文件/空目录，不清理旧共享库。中间选择失败后曾短暂启动默认私有目录服务，发现后立即停止，主会话未从该次服务列出/读取私有数据。

本任务保留in_progress与未提交状态；跨设备LAN及用户视觉判断尚待复核，不用局部通过替代全部验收。Android11/API28、其他ROM、TalkBack与原生16KiB页未测。截图、命令和日志见任务记录；下方98f44069及更早结果仍按各自版本解释。

## 2026-09-27 工作台新版 TARGET-PHONE 定向验收

用户在具体 APK 与安装/临时文件验收请求后回复“允许”。重新确认 TARGET-PHONE / Android16、`PHONE-ADB-SERIAL` 后覆盖安装成功，实际安装哈希与 **`98f44069c9fc6da80d082dd591b161cb9c1d43b34e88d522879af75e6148bab7`** 完全一致。数据保留，既有 Root 自动会话恢复，没有手动授权或改配置。详见 [手机验收记录](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/device-check.md)。

- 五项工具在首屏完整可见，其他四项及文件管理可达。密码中文基础/高级表单、长度数字键盘、折叠盐值参与计算通过；主结果和展开原始 JSON 默认遮挡，直接复制后粘贴核对为密码本身。
- 编码文本/文件切换保留草稿，文件为空阻止运行，实际系统选择器取消后保持空选择。公开20B文本 `ctos-ui-saf-20260927` 的 Base64 预览正确；唯一临时产物通过 SAF 导出读回，**28 bytes 逐字节正确**，重新导入后名称/大小显示正确。
- 文件哈希匹配编码28B内容；保留文件切回文本后，哈希匹配原20B文本，旧 token 不覆盖活动来源。哈希方向隐藏、逐项结果与复制控件正常。横屏、键盘滚动、加解密空表单和 HFTP 停止页未见布局问题；未启动 HFTP 或执行加解密。
- 已恢复原旋转 `lock 0`，回到工作台，精确删除外部测试文件及设备/宿主 UI dump；未清理共享库或既有文件。App 管理的28B产物和28B导入副本保留在工具临时存储，没有整库清理或 Root 私有文件操作。

实机截图在 `${EVIDENCE_DIR}/ctos-usability-*-20260927.png`（确切名单见手机记录），公开产物读回在 `${EVIDENCE_DIR}/ctos-usability-saf-export-20260927.txt`。仅此定向验收通过；TalkBack、服务后台/通知、IP在线/离线、其他ROM/Android11/原生16KiB页未在新版重测，大字号/减少动画仍为组件证据。本轮无源码修复，不重复构建或测试；下方本地检查适用同一 APK。源码未提交/任务未归档，实际截图供用户产品审阅。

## 2026-09-27 工作台体验优化：源码、本地预览与最终 APK

用户批准 TARGET-PHONE 界面审阅的六项建议并要求规划/实现。九项入口、中文与分层参数、编码来源投影、专用结果和 HFTP 状态分区已实现；未改 Python、SDK、原生或依赖。详见 [实现证据](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/implementation-check.md)、[复核](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/check.md) 和 [构建/审阅计划](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/build-review.md)。

- `./hako flutter analyze` 无问题；`./hako flutter test --reporter expanded` **43/43** 通过，含 12 项新增体验测试。覆盖准确参数/枚举、隐藏值、文本/文件草稿、文件必选、目标复制/完整 JSON 与原 token 导出、密码及转义日志遮挡、HFTP 显式启动停止/退出页面/清理确认、375dp/横屏/2倍字号/减少动画。日志在 `${EVIDENCE_DIR}/ctos-workbench-{analyze,full-tests}.log`，属本机临时证据。
- `./hako bash -lc 'cd android && ./gradlew :app:lintRelease --console=plain'` 成功，**0 errors / 5 既有 warnings**；报告 `build/app/reports/lint-results-release.{txt,xml}`。
- `./hako flutter test build/workbench-usability-previews/preview_test.dart --reporter expanded` **1/1** 通过。主会话检查列表、密码/编码表单、密码/哈希结果及停止状态 HFTP 六张预览，无裁切或文字溢出。预览为 375×812dp、3倍像素的 Flutter 固定公开数据组件，模拟 native channel，保留项目主题；测试字体补齐 CJK、Roboto、图标及等宽字形。它们不是真机截图，不建立执行/文件/服务结果。临时产物在忽略目录 `build/workbench-usability-previews/`，包含 `list.png`、`password-form.png`、`encoder-form.png`、`password-result.png`、`hash-result.png`、`hftp-stopped.png`。
- `./hako current` 成功，APK 签名校验、发布副本比对及 SHA256SUMS 检查通过。新版 `dist/ctos-current-arm64.apk` 为 **26,107,591 bytes**，SHA-256 **`98f44069c9fc6da80d082dd591b161cb9c1d43b34e88d522879af75e6148bab7`**。APK 内 13 份 Python 源码与当前源码逐字节一致。旧包临时备份 `${EVIDENCE_DIR}/ctos-workbench-baseline-f928af66.apk` 保持 `f928af66…`。
- 最终文档本地链接检查与 `git diff --check` 通过。审阅分类 `human-required`：新版视觉、键盘、真实 SAF 和辅助功能仍需设备验收，源码暂未提交/任务未归档。

构建阶段先使用 TARGET-PHONE `PHONE-ADB-SERIAL` 查看既有包界面，没有运行工具或启动 HFTP；只读取得 Noto CJK 字体供预览。当时新版未安装，原报告要求本任务安装授权；后续用户授权及新版定向设备结果见上方，不借用历史验收。其他 ROM、Android 11 和原生 16KiB 页仍未验证。

## 2026-09-27 当前进度提交检查

本轮按用户要求确认 ADB 连接并提交 ctOS 当前进度，没有重新安装、运行设备测试、申请权限或启动文件服务。

- `adb connect "$BOARD_ADB_SERIAL"` 返回 `No route to host`，默认开发板本轮未连接成功。
- `adb devices -l` 显示 `PHONE-ADB-SERIAL` 在线，product/model 为 TARGET-PHONE、device 为 PHONE-PRODUCT-ID；显式指定该 serial 的 `get-state` 和 `getprop` 确认为 device / TARGET-PHONE / Android 16。
- 现有 current APK 的 SHA-256 仍为 `f928af6611f014206597321b2f35a094aef53b11eeb404ea7bbf72e23ee8a73b`，其 13 份 Python 源码与当前工作树逐字节一致。此检查不代表重新构建或本轮设备安装哈希验证。
- 本轮 `./hako flutter analyze` 无问题，`./hako flutter test` 31/31 通过；`./hako bash -lc 'cd android && ./gradlew :app:lintRelease --console=plain'` 构建成功，0 error / 5 个既有 warning。`hako-env.sh` 的 Bash 语法、ShellCheck、shfmt 检查及 13 份 Python 源码语法解析通过。
- 根 README、design 和 spec 的本地 Markdown 链接检查无缺失目标，`git diff --check` 通过。本地代码复核与检查结果见 [提交检查](../.trellis/tasks/archive/2026-09/09-26-portable-tools/commit-check-2026-09-27.md)。

下方 2026-09-26 的安装、SAF、通知、Root、PTY 及网络结果保留为历史实测；本轮不扩展设备验收范围。任务状态保留，未归档。

## 2026-09-26 Portable 五项工具：最终 APK 与 TARGET-PHONE 验收

当前 APK `dist/ctos-current-arm64.apk`，26,106,495 bytes，SHA-256 `f928af6611f014206597321b2f35a094aef53b11eeb404ea7bbf72e23ee8a73b`，签名与 SHA256SUMS 通过，设备安装哈希一致。测试 APK SHA-256 `be024d92e5cc2a1d76c9d46ff8cf970dc04bb086304c2a2d4c57e2c59344c9c6`。用户明确授权本轮 APK/测试 APK 覆盖安装、通知授权及本机 HFTP/App与Root Python 验收；目标仅 TARGET-PHONE `PHONE-ADB-SERIAL` / Android16 / 页大小4096 / SELinux Enforcing。

- Flutter analyze 无问题，31/31 测试通过；Android lint 0 error / 5 个既有 warning，最终测试 APK 编译通过。五项集成113项、编码核心142项、CLI12次调用、真实 Chromium HFTP上传/下载/特殊目录/不覆盖/CSP/375px均通过。依赖及构建环境仅 APK、项目 .devhome 和 ${EVIDENCE_DIR}。
- 手机首轮 HFTP 上传400，新增 App Python 测试确认硬链接 EACCES。改为 SDK/CLI/HFTP 共用 `renameat2(RENAME_NOREPLACE)` 原子提交，已有文件/符号链接拒绝覆盖，失败清理临时文件；16个并发写入只有一个成功。不更改 Root 授权或 SELinux。
- 最终指定七项组合 **OK (7 tests), 6.392 s**：加密/转换及源文件保留、认证篡改拒绝、终端文件输出不覆盖、HFTP后台通知/认证/上传下载/路径拒绝/停止监听、SDK目录及未知脚本拒绝、App与Root python3、运行中取消/超时/后台取消及重跑。先前合并运行的 Activity 等待超时已通过测试 CLEAR_TASK 隔离修正，失败日志仍保留。最终日志 `device-seven-api-final-results.txt`。
- 真机 SAF：公开文本产物导出读回正确，重新选择该文件显示私有导入副本，取消选择及取消系统选择器后为空 token。HFTP 实际页面启动 loopback，切桌面通知保持；点通知“停止”后通知消失，服务列表为空。精确清理本轮三个私有测试文件、唯一 SAF 文件及临时 UI dump；未清空 App 或删除既有目录/文档。
- 13个实际 APK Python 源文件逐字节匹配；最终 APK 解包后 ARM64 QEMU通过 SDK2/九项/原子不覆盖/AES-GCM，新动态库LOAD对齐16KiB。QEMU linkerconfig/tzdata提示仍属模拟环境，不能推定原生16KiB设备通过。

- 09 原脚本接口已迁移，按官方文档改为 `https://free.freeipapi.com/api/v1/json`。宿主和实际手机工作台明确指定公共示例1.1.1.1的查询均通过，返回source/ipAddress正确；没有查询自身公网IP。固定目标/8秒超时/离线错误继续有mock证据。

详细命令、失败→修复证据与临时日志见 [父 task 检查](../.trellis/tasks/archive/2026-09/09-26-portable-tools/check.md)。Android11、原生16KiB页设备、API28/29 syscall分支、全新通知拒绝及真实离线设备路径未验证。实现及本轮验收完成，保留 WIP 待审阅/提交，不自动归档。下方 Python工作台/Root-only 及末尾安装前预检均为历史阶段记录。

## 2026-09-26 Portable Python 工作台：构建与 TARGET-PHONE 验收

本轮 current APK：`dist/ctos-current-arm64.apk`，SHA-256 `00c19e0ca2ecc84b1fea32fafaec29708b152710a3f5a3b8ece64717a26d97c6`，约 24.4 MB。原命令页改为工作台、原工作台改为概览；系统负载展示及 procfs 采集删除。加入 APK 内置 CPython 3.13.9、终端 python3、四项脚本二级页、SDK 和 portable 清单。框架契约见 [portable-workbench.md](portable-workbench.md)。

- `./hako flutter analyze`：无问题。`./hako flutter test --reporter expanded`：**28/28 通过**，包含新工作台懒加载/失败重试、二级导航、Unicode 参数校验、取消/超时后重跑、小屏/横屏/2 倍文字与低动画布局，以及已有网络和 PTY 组件回归。
- `./hako bash -lc 'cd android && ./gradlew :app:lintRelease :app:assembleReleaseAndroidTest --console=plain'`：最终原生实现通过 lint，设备测试 APK 构建成功。新增三项 Python SDK/非法脚本、取消/超时、Activity 后台回收检查，已有 App/Root PTY 测试追加 python3、标准库与 Python Ctrl-C 检查。
- `./hako current`：arm64 release 构建、签名验证及 SHA256SUMS 通过。最终 APK 检查包含 PIE 启动器、libpython3.13、标准库、三份 SDK 源文件、portable 清单和三份版权声明；无宿主 pyc。merged manifest 确认为 `extractNativeLibs=true`；启动器为 ARM64 PIE、`/system/bin/linker64` interpreter、16 KiB LOAD 对齐。
- 安装前通过显式 serial 的 getprop 确认 TARGET-PHONE / Android 16（`PHONE-ADB-SERIAL`），复制公共 Android 链接器及库到 `${EVIDENCE_DIR}` 供 QEMU。用户随后明确授权覆盖安装 current APK 和测试 APK并验收。两个 APK 均安装成功，设备 base.apk SHA-256 与最终 current 一致。SELinux 为 Enforcing；未更改 Magisk/Vector 配置、重启或清除应用数据。
- 本地 `qemu-aarch64` 执行构建期间 APK 提取的 ARM64 启动器、libpython 和 SDK，`--version` 返回 Python 3.13.9；四个内置脚本通过。自检为 OpenSSL 3.0.18、SQLite 3.50.4，SQLite 内存查询和 SHA-256 校验正常；CLI 导入 SSL/SQLite/SDK 成功。非法参数、未知脚本失败；主机 SDK 的有界日志/结果及 SystemExit 结果封装检查通过。固定 `-P -S` 路径在工作目录存在恶意同名模块时仍加载内置目录。
- 真机首轮发现 Android 安装目录含 `=`，Toybox `env` 将启动器路径误当环境赋值；改为子 Shell 中 `export` 后直接执行路径，App/Root 均可调用 Python。Root PTY 返回额外 CR，测试断言沿用已有读取函数的 CR 规范化。最终完整 `DeviceTest` **OK 8/8，6.233 秒**，覆盖 SDK、自检/Unicode 文本/设备/内存、未知脚本后恢复、运行中真实进程取消和重跑、1 ms 超时回收、Activity 移到后台后取消待运行任务并释放槽位，以及 App/Root PTY 的 Python 3.13.9、SSL/SQLite/SDK 导入、Python sleep 的 Ctrl-C 与原有网络/Root 回归。日志：`${EVIDENCE_DIR}/ctos-python-device-tests-20260926.log`。
- 真机 UI：概览/信息/工作台/终端导航正确，设备页不再显示系统负载；工作台四个 item 和 Python 二级页正常。点击运行自检显示 App、已完成、75 ms、退出码 0，Python 3.13.9 / aarch64 / SDK 1、OpenSSL 3.0.18、SQLite 3.50.4 和内存查询成功。通过系统文件选择器另存 `Download/ctos-python-selftest-20260926.json`，拉回后 JSON 与屏幕结果一致，stdout/stderr 为空。截图：`${EVIDENCE_DIR}/ctos-python-{overview,workbench,detail,selftest,device-info}-20260926.png`；读回文件 `${EVIDENCE_DIR}/ctos-python-selftest-20260926.json`。以上均为本机临时证据。
- Shell 模板通过语法、ShellCheck 和 shfmt 检查；最终生成函数在两种 Android PTY 中实测通过。`git diff --check` 通过。
- 临时证据：`${EVIDENCE_DIR}/ctos-sdk-validation/`（测试脚本与 Shell 模板）、`${EVIDENCE_DIR}/ctos-python-qemu/`（公共 Android 库及提取的运行包）。QEMU 树没有设备 linkerconfig/tzdata，启动产生对应诊断；stderr 与 JSON stdout 分离，脚本协议检查不受影响。这些目录仅是本机临时证据。

**边界**：Android 11、16 KiB 页面设备、其他 Root 管理器未测。参数表单的 Unicode/小屏/失败状态以组件及 SDK 检查为主，未逐项手操；后台检查验证真实 Activity.onStop 取消待运行任务，运行中进程销毁另有独立测试。curl 为扩展文档示例，未打包；外部 ZIP 导入、包缓存管理、运行时 pip、历史与后台调度未实现。

以下记录属于各自历史 APK；其中“当前包”以该条记录日期和哈希为准。

## 2026-09-26 Root-only：移除 Vector 并验收

当前 APK SHA-256：`be800549c2ddf6509fe9ff0de23556d58b9401b67fee39f63fa5eb361752ab93`。设备重新由 `adb devices -l` 确认：`PHONE-ADB-SERIAL`，TARGET-PHONE，Android 16 / API 36，1272×2800，SELinux Enforcing。全部设备命令显式指定该 serial。本轮只覆盖安装 ctOS 和匹配测试 APK、执行固定只读采集/PTY 测试及页面检查；未修改 Vector/Magisk 配置、未重启、未完整卸载应用。

- 原因核查：Root `id` 返回 uid=0，uptime 约 40.7 天。现存 Vector 日志只记录模块在 ctOS App 进程/UID APP-UID 中加载；系统广播列表只有 App 的 RESULT 接收器，没有系统 QUERY 接收器。旧模块只对 android / android 生效且在 AMS.systemReady 后注册服务，因此 App 加载或勾选作用域不等于 system_server 桥接在线。未读取 Vector 作用域数据库、未重启验证，不能将“未重启”认定为唯一原因。完整边界见 [决定](decisions/2026-09-26-root-only.md)。
- `./hako bash -lc 'dart format test/observatory_test.dart && flutter analyze && flutter test --reporter expanded'`：分析无问题，**22/22 通过**。随后仅删除终端测试 fixture 的遗留 moduleActive 字段，`./hako flutter test test/terminal_interaction_test.dart --reporter expanded` **7/7 通过**。网络组件测试直接读取 App networks，Root 已连接、未连接和会话失败状态保持可见，Vector 文案消失。
- `./hako bash -lc 'cd android && ./gradlew :app:lintRelease :app:assembleReleaseAndroidTest --console=plain'` 成功。lint 无错误，5 项 warning：包可见性、Gradle/测试 runner 可升级、缺 ChromeOS x86_64 ABI、旧系统 backup 配置提示；未通过修改规则隐藏警告。
- `./hako current` 构建 arm64 release、验证签名、原子更新 current APK 与 SHA256SUMS。`aapt2 dump xmltree` 确认 APK 无 Xposed 元数据及旧 QUERY 权限；ZIP 列表无 xposed_init，源码无模块/桥接广播引用。AndroidX Core 仍由 Flutter 传递依赖，与移除直接接收器依赖不矛盾。
- `adb -s "$PHONE_ADB_SERIAL" install -r` 分别安装 current 和匹配的 `app-release-androidTest.apk` 成功；设备 base.apk 哈希与 current 相同。
- 完整 `DeviceTest`：**OK 5/5，3.914 秒**，包含 App API 网络快照、Root 接口/连接、Root 采集会话复用/超时不自动重开、App/Root PTY 的 UID/TTY/尺寸/Ctrl-C，以及分身用户序列号/别名解析。日志：`${EVIDENCE_DIR}/ctos-root-only-device-tests-20260926.log`。
- 安装后启动自动恢复 Root，工作台显示 APP 可用、ROOT 在线、4 个网络、33 个接口、root / procfs + ip，只有 App/Root 能力。截图 `${EVIDENCE_DIR}/ctos-root-only-workbench-20260926.png`。网络页语义和截图显示蜂窝、VPN/tun0 默认网络、Wi-Fi/wlan0 以及 IP/DNS/Private DNS 等字段，无 Vector 状态或重启提示；截图 `${EVIDENCE_DIR}/ctos-root-only-network-20260926.png`。这些文件是本机临时证据。
- 本轮没有在 Android 11 开发板、其他 ROM/Root 管理器上重测；未验证全新安装弹窗、真实 live 分身别名的界面筛选、全部终端 IME 操作或完整系统文件选择器导出。Root 失败回退和无 Root 部分连接状态由组件测试覆盖，未更改设备授权模拟拒绝。

以下均为较早 APK 的历史记录；Vector 已移除，不再作为当前包待验收项。

## 2026-09-26 终端输入同步修正

用户发现输入 `i` 后按 Tab，Shell 上方已有补全或候选文字，底部输入框却清空。原因是底部编辑值与 Shell 对当前行的重绘未同步；此前另观察到直接向这台设备的 App Shell 发送原始 ↑/↓ 时，长命令历史会被 Shell 截断重绘。当前实现保留底部已输入内容直到 Tab 返回，读取终端已渲染的可编辑行并同步到输入框；↑/↓改为浏览当前 PTY 会话通过底部提交的命令，再经同一 PTY 编辑路径恢复，不读取会话外的 Shell 历史。

- 最终源码 `./hako flutter analyze` 无问题，`./hako flutter test` **22/22 通过**，`git diff --check` 通过。`./hako current` 构建 arm64 release APK、验证签名与 `dist/SHA256SUMS`。当前 APK SHA-256 为 `1a610e33fc018d20f4b99161d6eb5e91f4eb594930f0d0033e3eea5c873245ae`；在 `PHONE-ADB-SERIAL` 覆盖安装，设备 `base.apk` 哈希一致。
- 该包在 TARGET-PHONE 的 App Shell 中输入 `ec` 后按 Tab，Shell 与输入框都显示 `echo`（`${EVIDENCE_DIR}/ctos-terminal-1a610e-tab-20260926.png`）。按用户原步骤输入 `i` 后按 Tab，Shell 列出 `if`、`in`、`integer` 等多个候选，上下都保留 `i`；继续从底部输入 `d`，上下成为 `id`，点键盘发送后返回应用 UID `10497`（`${EVIDENCE_DIR}/ctos-terminal-1a610e-i-tab-20260926.png`、`${EVIDENCE_DIR}/ctos-terminal-1a610e-i-tab-d-20260926.png`、`${EVIDENCE_DIR}/ctos-terminal-1a610e-i-tab-id-20260926.png`）。执行 `echo hello` 后按 ↑，上下均恢复完整命令；按 ↓，上下均回到空草稿（`${EVIDENCE_DIR}/ctos-terminal-1a610e-history-up-20260926.png`、`${EVIDENCE_DIR}/ctos-terminal-1a610e-history-down-20260926.png`）。这些图片是本机临时证据。
- 当前包顶部“选择输出”打开了独立的“当前输出”页面，显示已渲染的命令、结果与提示符（`${EVIDENCE_DIR}/ctos-terminal-1a610e-selection-20260926.png`）。选择复制、中文候选、普通文本 IME 类型、Root PTY 的 `id` 与 Ctrl-C 设备检查来自下节的较早构建或此修正前的中间包；当前包没有重新逐项实测这些操作。Android 11 开发板、其他输入法/ROM、当前包 Vector 桥接、全新安装授权弹窗、重启后的模块加载及导出未在本轮验收。

## 2026-09-26 终端流程与文案收敛（前一构建）

目标设备由 `adb devices -l` 重新确认：`PHONE-ADB-SERIAL`，TARGET-PHONE，Android 16/API 36，1272×2800，SELinux Enforcing；所有设备操作显式带 `-s "$PHONE_ADB_SERIAL"`。本轮仅覆盖安装 ctOS 并操作其 App/Root PTY，未更改 Magisk 或 Vector 配置。

- 该阶段源码执行 `./hako flutter analyze` 无问题，`./hako flutter test` **19/19 通过**，包含 IME 组合与候选替换、空字段退格、发送/回车、双入口与快捷键、输出快照、320dp/1.5 倍文字及 300px 软键盘内边距。`git diff --check` 通过。`./hako current` 构建 arm64 release APK、验证签名并生成 `dist/SHA256SUMS`；从 `dist/` 执行 `sha256sum --check SHA256SUMS` 通过。该阶段 APK SHA-256 为 `da530c09343b416f8dc4f41aebaacf80b87fe10838b2a84f9f81ed8a17376586`；覆盖安装成功，设备 `base.apk` 哈希一致。
- 该阶段包打开终端时显示“应用 Shell”“Root PTY”两个 item；会话顶部依次为“换用”“选择输出”和停止按钮。输入框无可见标签或占位说明；五个快捷键只有 Ctrl-C、Tab、Esc、↑、↓。上方输出区点击不唤起键盘（截图 `${EVIDENCE_DIR}/ctos-terminal-final-output-tap2-20260926.png`），底部输入框唤起百度/Oplus 输入法。聚焦时 `dumpsys input_method` 显示 `inputType=0x1`、`imeOptions=0x2000004`，即普通文本与发送动作；输入法仍采用厂商自带的蓝色主题，应用不控制其皮肤。
- App Shell 中，中文拼音 `ni` 处于组合态时 Shell 行保持原样；点首个“你”候选后 Shell 显示“你”，键盘保持打开。软键盘退格删除该汉字；随后输入 `id` 并点键盘发送键，仅执行一次，结果为 `uid=APP-UID(APP-USER)`。输入 `ec` 后点 Tab，Shell 补全为 `echo` 且键盘保持打开。打开顶部“选择输出”后可看到 `id` 结果和当前 Shell 行；长按出现系统 Copy 菜单，复制并返回后 App PTY 仍在。截图：`${EVIDENCE_DIR}/ctos-terminal-final-composing2-20260926.png`、`${EVIDENCE_DIR}/ctos-terminal-final-candidate2-20260926.png`、`${EVIDENCE_DIR}/ctos-terminal-final-before-send-20260926.png`、`${EVIDENCE_DIR}/ctos-terminal-final-id2-20260926.png`、`${EVIDENCE_DIR}/ctos-terminal-final-tab2-20260926.png`、`${EVIDENCE_DIR}/ctos-terminal-final-selected2-20260926.png`、`${EVIDENCE_DIR}/ctos-terminal-final-return2-20260926.png`（本机临时证据）。
- 停止 App PTY 后重新出现双入口；用设备已有 ctOS Root 授权开启 Root PTY，输入并执行 `id` 得到 `uid=0(root)`、`context=u:r:magisk:s0`，随后关闭测试会话。截图：`${EVIDENCE_DIR}/ctos-terminal-final-after-stop2-20260926.png`、`${EVIDENCE_DIR}/ctos-terminal-final-root-id2-20260926.png`。此前中间包另核实了 ↑/↓ 历史与 `sleep 30` 的 Ctrl-C 中断；这些检查均不视为当前 `1a610e33…` 包的实测结果。
- 工作台该阶段包截图 `${EVIDENCE_DIR}/ctos-terminal-final2-20260926.png` 显示已删重复介绍而能力、采集时间与 Vector 未响应信息仍可见。设备、网络、命令和连接页的静态文案由源码审查与组件测试覆盖，本次未逐页拍摄该阶段包截图。Android 11 开发板、其他输入法/ROM、Vector 桥接、全新安装授权弹窗、重启后的模块加载及导出未在该阶段验收。

中间构建 `2c95af59…` 在同一手机上曾观察到百度输入法候选替换异常；当时输入组件过早重置编辑值。`da530c09…` 构建改为保留 IME 编辑状态并按 Unicode 差量同步到 PTY，以上中文真机检查针对该包。

## 2026-09-25 QQ 新连接与 `CLONE-ALIAS` 分身别名

用户在应用图标视觉评审通过后报告：打开 QQ 再在 ctOS 搜索 `qq` 没有结果；随后要求支持名为 `CLONE-ALIAS` 的 QQ 分身。授权 TARGET-PHONE `PHONE-ADB-SERIAL` 上，QQ 主应用正在运行且只读 `ss -tunape` 有 13 条 UID TEST-APP-UID 记录，ctOS 旧快照中筛选 `qq` 为 0 条，手动刷新后显示 12 条及真实 QQ 图标。原因是连接页从后台返回、重新进入已有缓存时没有重新采集。现改为这两种进入方式刷新；没有匹配时明确提示快照范围及刷新入口。用户已通过分身卡片视觉评审。

同一手机存在 `UserInfo{999:MultiApp}` 分身用户；`cmd package list packages -U --user 999` 给 QQ 分身 UID `99910377`，只读 `ss -tunape` 在打开该分身后出现 19 条对应连接。原生包列表的 `uid:10377,99910377` 过去只解析首个 UID。现逐个映射；Oplus 桌面受保护收藏项将该包的用户序列号 10 对应标题记为 `CLONE-ALIAS`，由已有 Root 会话的固定只读查询获取，无法读取时回退到原名/包名/用户 ID。该 OEM 数据源不代表其他 ROM 已兼容。

- 当前 `dist/ctos-current-arm64.apk` SHA-256 为 `19b8922bce927066dcec7fd9a7d45ac5caf148f32943d8c1c44090738560791d`，`./hako current` 签名和 `SHA256SUMS` 校验通过；TARGET-PHONE 覆盖安装后设备 `base.apk` 哈希一致。`./hako flutter analyze` 无问题、`./hako flutter test` **15 项通过**，最后一次 Java 修正后 Android `:app:lintRelease :app:assembleReleaseAndroidTest` **BUILD SUCCESSFUL**。
- `ef6e9981…` 中间包仪器测试全部 5 项合跑时，Vector 桥接因该机仍未响应而失败。最终 `19b8922b…` 包只跑分身序列号解析与三项 Root/PTY 检查，**OK 4/4（4.673 秒）**。这不构成 Vector 通过。Android 11 开发板未连接，当前包未在该板验收。
- 前一轮 `d9429780…` 安装包实机显示 UID `99910377` 的 QQ 企鹅图标、别名 `CLONE-ALIAS`、原名 QQ、用户 999 与包名；打开分身再返回 ctOS 后自动刷新并显示 18 条该 UID 连接，截图 `${EVIDENCE_DIR}/ctos-clone-uid-filter.png`。最终包随后增加完整应用名/别名优先匹配，以及对可直接可见分身应用的别名映射；前者由 Flutter 单元/组件测试覆盖，后者由 Android 构建和 Root 连接仪器测试覆盖。最终包未重新取得解锁后的界面截图。实机主 QQ 旧快照 0 条→手动刷新 12 条的对照截图在 `${EVIDENCE_DIR}/ctos-qq-before-refresh.png`、`${EVIDENCE_DIR}/ctos-qq-after-refresh.png`。
- 中间构建 `890d235d…` 的用户资料解析正则曾抛异常，修正后的中间构建恢复了连接采集；最终构建的解析器另有 Android 仪器测试覆盖。一次中间包覆盖安装后 Root 未自动恢复，手动点击工作台“授权 Root”后成功；未据此推断最终包的启动授权结果。

## 2026-09-25 连接应用图标与名称检索

用户追加应用图标及“应用显示名、应用自身名称、包名”检索。原生 `Collector.connections` 在原有 `output`、`partial` 上添加 `apps` 映射，只为此次 `ss -tunape` 输出中出现的 UID 收集可访问应用的显示名、包名、`ApplicationInfo.name`、进程名与 72px PNG 图标；Flutter 解析后缓存。对不可见或图标读取失败的包保留可检索身份和通用占位图标，同 UID 多包可展开查看，不宣称单条连接唯一归属某个包。

- `./hako dart format`、`./hako flutter analyze` 通过，`./hako flutter test` **14 项通过**；新增测试覆盖多种名称检索、图标 Base64 解析失败回退、连接 UI，以及导出不包含图标数据。`./hako bash -lc 'cd android && ./gradlew :app:lintRelease :app:assembleReleaseAndroidTest --console=plain'` 在最后一次 Java 改动后 **BUILD SUCCESSFUL**。
- `./hako current` 构建并校验签名、`SHA256SUMS`。该版 `dist/ctos-current-arm64.apk` SHA-256 为 `78d7af8db4474970cd4bbcb7a9dc16c3dc9333fc13a4a3de455473ab2393537f`；授权 TARGET-PHONE `PHONE-ADB-SERIAL` 覆盖安装成功，设备 `base.apk` 哈希一致。配套 Android 测试 APK 重新构建、安装，该版安装包的 Root/PTY 三项仪器测试 **OK 3/3（3.099 秒）**。
- 该版安装包实机“信息 → 连接”显示 Quick Connect 的真实图标、显示名与 `com.heytap.accessory` 包名。输入显示名 `Quick Connect` 后匹配 11 条连接；截图 `${EVIDENCE_DIR}/ctos-connection-app-icons-final.png`、`${EVIDENCE_DIR}/ctos-connection-app-name-filter-final.png` 来自上述 `78d7af8d…` APK（本机临时证据）。`ApplicationInfo.name`、进程名与同 UID 多包筛选由模拟数据测试覆盖，未在此手机逐项实测；图标可见性取决于 Android 包可见范围。Android 11 与 Vector 对该版仍待验收。

## 2026-09-25 连接检索左对齐修正

用户在连接列表视觉评审中指出标题和说明居中。原因是连接页顶部 `Column` 默认按横轴居中，而 `heading` 宽度只包住文字；改为横向撑满后，标题、说明与搜索框左缘对齐。组件测试增加左缘坐标断言；`./hako flutter analyze` 无问题，`./hako flutter test` 13 项通过。

`./hako current` 重建并验证签名与校验和，该版 APK SHA-256 为 `78392858a4ee90cf9727a2219918ef058fd30333f6ec9c7b90c366db23604be8`。在同一授权 TARGET-PHONE 上覆盖安装成功，设备 `base.apk` 哈希一致；实机截图 `${EVIDENCE_DIR}/ctos-connection-items-left-aligned.png` 和 `${EVIDENCE_DIR}/ctos-connection-items-left-aligned-filtered.png` 确认左对齐、逐条 item 及包名筛选。该次采集有 115 条连接，包名筛选匹配 11 条；数量随采集时间变化。该版安装包的三项 Root/PTY 仪器测试 **OK 3/3（2.832 秒）**。Android 11 与 Vector 仍未在此版验证。

## 2026-09-25 连接 item wrapper（用户追加）

在下节所述第一阶段验证后，用户追加“把原生 netstat 输出转换成一条条 item”。现有采集实际调用 `ss -tunape`；本次保留 Java 返回合同，在 Flutter 层解析协议、状态、队列、本地/远端、UID 和应用归属，保留权限/格式诊断及可展开原文。

- 授权设备仍为 `adb devices -l` 所列 `PHONE-ADB-SERIAL`（TARGET-PHONE，Android 16/API 36，SELinux Enforcing），所有设备命令均用 `adb -s`。只读 `ss -tunape` 样本含 IPv4/IPv6、UID、定时器和 `Cannot open netlink socket: Permission denied` 提示；解析测试覆盖这些格式与异常行。
- `./hako dart format`、`./hako flutter analyze` 通过；`./hako flutter test` **13 项通过**。`./hako current` 构建并验证签名与 `SHA256SUMS`；连接 wrapper 首版 APK SHA-256 为 `1f6e6833359517229c091328d07d0918ad7222e8aca89603a25e489fbee133ce`。覆盖安装成功，设备 `base.apk` 哈希一致。
- 实机该版的“信息 → 连接”显示 126 条结构化连接；包名 `com.heytap.accessory` 筛选显示 11 条匹配，卡片保留 UID 和应用名，旧快照提示仍显示。截图：`${EVIDENCE_DIR}/ctos-connection-items-final.png`、`${EVIDENCE_DIR}/ctos-connection-items-final-filtered.png`。曾在中间构建中观察到 128 条连接，数量随采集时刻变化；截图仅作为本机临时证据。
- 本轮仅改 Flutter 和测试，未改 Java 采集、Root 会话、Vector 或 PTY。该版覆盖安装后，三项现有 Root/PTY 仪器测试再次合跑 **OK 3/3（2.827 秒）**，覆盖接口/连接、会话复用与超时后不自动重开、App/Root PTY。Android 11 开发板及 Vector 仍待该版验收。

## 2026-09-25 可信状态与工作台第一阶段

目标设备由当次 `adb devices -l` 列出并复核：`PHONE-ADB-SERIAL`，TARGET-PHONE，Android 16/API 36，arm64，SELinux Enforcing。全部设备命令显式指定此序列号；Android 11 开发板当次未在线。本次未清除数据、改动 Magisk/Vector 配置或重启。

- `./hako dart format` 格式化改动的 Dart 文件；`./hako flutter analyze` 无问题，`./hako flutter test` 11 项通过。状态单元测试与组件测试覆盖连接状态转换、360dp/1.5 倍文字、受限负载，以及 1024dp 下的 Root 恢复入口。
- `./hako current` 成功构建、验证签名并发布 `dist/ctos-current-arm64.apk`；`dist/SHA256SUMS` 校验通过。APK SHA-256：`4e26b346eb457e23d22e17bf45d655ff58262cda0cc5b358c7e678b333c0a9a9`。`adb -s "$PHONE_ADB_SERIAL" install -r` 覆盖安装成功，设备 `base.apk` 哈希与新包一致。
- 实机工作台显示独立的 APP、ROOT、VECTOR 状态、设备摘要、恢复说明和常用操作；现有 ctOS Root 授权下 ROOT 在线，该手机的 VECTOR 未响应。连接页显示当前结果和采集时间；等待超过 30 秒后显示旧快照，手动刷新后更新采集时间。系统负载在该机被拒绝读取时显示易懂提示，原始 `EACCES` 默认收起，内存数据仍可查看。实机截图在 `${EVIDENCE_DIR}/ctos-phase1-wake.png`、`${EVIDENCE_DIR}/ctos-phase1-device.png`、`${EVIDENCE_DIR}/ctos-phase1-connections.png`、`${EVIDENCE_DIR}/ctos-phase1-connections-stale.png`、`${EVIDENCE_DIR}/ctos-phase1-connections-refreshed.png`，属于本机临时证据。
- `./hako bash -lc 'cd android && ./gradlew :app:assembleReleaseAndroidTest --console=plain'` 成功，测试 APK 覆盖安装成功。首次合跑三项 Root/PTY 仪器测试时，`rootCollectorReusesSessionAndNeverAutoReopens` 的超时断言一次收到读取线程中断异常，其余两项通过；随后单独重跑该项 **OK 1/1**，再合跑三项 **OK 3/3**。当前无法稳定复现首次失败，未改动 Root 会话实现；此波动需在后续设备回归中留意。
- 此手机的 Vector 桥接在前次检查中未响应，本次未重跑 Vector 仪器测试，也未验证全新安装授权弹窗或导出文件的实机读回。Android 11 开发板及其 Vector、PTY、导出路径对本版 APK 均待复验。Flutter 测试覆盖的刷新失败与大屏布局是模拟结果，不作为实机结论。

## 2026-09-25 Trellis Plus configuration

The project-owned policy is `.trellis/spec/trellis-plus/index.md`, with exact
ctOS checks in `validation.md`. The existing Docker wrapper, Flutter manifest,
widget tests, Trellis state and local Codex configuration were inspected.
This was workflow configuration only: no Flutter, APK or device validation was
run, and no device connection or authorization is implied. The UUPM CLI
initialization succeeded for Codex, and its local `scripts/search.py --help`
completed successfully. Documentation links, `git diff --check`, and file
classification were checked after the edit. The current Flutter UI has no
browser validation path, so no Playwright test was added.

## 2026-09-25 current APK on Android 16

Target: user-selected `PHONE-ADB-SERIAL`, confirmed as TARGET-PHONE, Android 16/API 36, `arm64-v8a`, SELinux Enforcing. All device commands explicitly selected this serial.

- The initial `adb install -r dist/ctos-current-arm64.apk` succeeded over the 2026-09-23 ctOS install, without uninstalling it. Its installed `base.apk` SHA-256 matched that day's initial artifact: `22ab383fbaf02024b59f68ef958224a9215625aca58a87b6dd23b724511c2bcd`.
- The matching Android test APK, SHA-256 `3efd86d076953baf267e1e937e9115f3b3bcec0ee4eb48c6699984e8c0cedf48`, installed successfully.
- Current ctOS launched on the phone's 1272×2800 screen. The workbench showed TARGET-PHONE, Android 16, four networks and 11 interfaces from `app / TrafficStats`; the device information page showed system and memory data. `/proc/loadavg` was denied under SELinux Enforcing and its CPU/load section explicitly reported `EACCES` as unavailable without permission.
- `DeviceTest#vectorBridgeReturnsSystemIdentity` **failed** after its 8-second wait: the system bridge did not answer. The app showed VECTOR pending activation. No Vector settings or system scope were changed and the phone was not rebooted.
- After the user authorized temporary screen control, the workbench, network and interface pages were checked. The phone's Magisk ctOS Root switch was already on; it was not changed. The three Root instrumented tests passed (**OK 3/3, 3.762 seconds**) on the initial artifact.
- To implement startup Root authorization, `rootAuto` now tries once on the first app launch, persists successful startup authorization in app preferences, and restores its collector session after subsequent launches or in-place installs. A failed or denied attempt disables automatic retries until the workbench's manual Root action succeeds. Collector polling still never opens another `su` session after a session failure.
- `./hako flutter analyze` passed; `./hako flutter test` passed all six tests, including the startup channel call; `./hako current` rebuilt the sole arm64 APK and verified its signature; Android `:app:assembleReleaseAndroidTest :app:lintRelease` passed. `dist/` contains one APK and `SHA256SUMS` matches it. The new artifact and device-installed `base.apk` have SHA-256 `b777fe91b2dc3292c487977dbda10c44059ef2c9afc8c94095f977fa88b95d80`.
- The new APK installed in place. Without tapping the Root button, the first launch showed ROOT online, four networks and 33 interfaces from `root / procfs + ip`. After `am force-stop im.majo.ctos`, the next launch again showed ROOT online without a tap. Reinstalling the same APK with `adb install -r` and launching again produced the same result.
- The three Root instrumented tests were rerun against the new target APK: **OK 3/3, 3.017 seconds** (`rootCountersAndConnections`, `rootCollectorReusesSessionAndNeverAutoReopens`, `appAndRootPtyAreInteractive`). The Vector test was not rerun after its bridge failure; Vector remains unverified on this phone.
- A genuinely fresh install's Magisk prompt was not reproduced: this phone already had ctOS approved, and clearing app data or revoking that preexisting grant was outside this check. The automatic request path is exercised by the startup channel/widget test and by automatic session restoration on the preapproved phone. The preexisting Magisk ctOS grant is preserved.

## 2026-09-24 former current APK

On 2026-09-24, `./hako current` rebuilt the arm64 release APK, verified its signature and replaced the old version-named package. `dist/` then contained exactly one APK, `ctos-current-arm64.apk` (22,844,406 bytes), SHA-256 `22ab383fbaf02024b59f68ef958224a9215625aca58a87b6dd23b724511c2bcd`. `dist/SHA256SUMS` named only this APK and `sha256sum --check` passed. Running `./hako current` a second time also passed and kept one APK. Flutter's `build/` copy is an ignored intermediate. The 2026-09-25 build above replaced this artifact.

The current APK was installed and tested on the Android 11 development board on 2026-09-24, as recorded below. Android 16 and a fresh system-module load after reboot remain untested. The 2026-09-23 results belong to the superseded artifact with SHA-256 `7869f197e8abd4ee07a5ad97933a0f9b8fea0c65b48d8a2464ff0e7145402ca6`.

## 2026-09-24 current APK on Android 11

Target: `TEST-BOARD-ADB-SERIAL`, confirmed as TARGET-BOARD, Android 11/API 30, `arm64-v8a`, SELinux Permissive. This is the user-selected address for this run; commands explicitly targeted that serial.

- The previous `im.majo.ctos` and `im.majo.ctos.test` installs had incompatible development signatures. Both old packages were uninstalled, then the current app and freshly built test APK were installed successfully. Uninstalling ctOS cleared its app-private data; the previously exported `Download/ctos-snapshot.json` remained present. No other package or Vector configuration was changed, and the device was not rebooted.
- The installed app's `base.apk` SHA-256 was `22ab383fbaf02024b59f68ef958224a9215625aca58a87b6dd23b724511c2bcd`, identical to `dist/ctos-current-arm64.apk`. The test APK was built with `./hako bash -lc 'cd android && ./gradlew :app:assembleReleaseAndroidTest --console=plain'` and has SHA-256 `3efd86d076953baf267e1e937e9115f3b3bcec0ee4eb48c6699984e8c0cedf48`.
- `DeviceTest#vectorBridgeReturnsSystemIdentity`: **OK (1 test)**. The existing system bridge answered with UID 1000, `Vector / system_server`, and network data. This did not require a module change or reboot.
- `MainActivity` launched and remained foreground. Visual checks passed for the workbench, device-information page (model, Android/API, kernel, architecture and uptime), network page, interface list (11 interfaces, `Vector / procfs`), and connections page. Without Root, network collection showed its authorization prompt and connections explicitly marked partial results.
- Both App-permission read-only commands ran on the device: `device.info` returned manufacturer/model, Android/API, kernel, architectures and uptime; `memory.snapshot` returned total/available bytes and low-memory state. Both displayed `available`.
- App-permission PTY started from the terminal page; its `id` shortcut returned UID APP-UID (`APP-USER`). The test session was closed. The recent ctOS process error log contained no entries.
- JSON export through the system file picker was saved under a new temporary name, read back, and parsed with `jq`. It reported `moduleActive=true`, system module UID 1000, 11 module interfaces, and `available` states for system, memory, CPU load, battery, storage and the last `device.info` command. The temporary device export was removed after verification; the older `Download/ctos-snapshot.json` was left intact.
- The new install initially showed Root ungranted. After the user explicitly authorized Root testing, Magisk's timed prompt expired and marked ctOS denied. Only the ctOS item in Magisk's Superuser list was then enabled; Android System and Shell entries were left unchanged.
- Full `DeviceTest` run: **OK (4 tests), 4.846 seconds**. This covered the Vector bridge, Root interface/route/connection collection, Root collector session reuse and refusal to reopen after timeout/close, and both App and Root PTY interaction including resize and Ctrl-C. The workbench then showed ROOT online, `root / procfs + ip`, one network and 11 interfaces after its own authorization action.
- The ctOS process was force-stopped to close its Root session, the ctOS Magisk switch was returned to off, and ctOS was relaunched. Its final workbench showed VECTOR online and APP without ROOT. No other Magisk grant was changed. The `im.majo.ctos.test` package was removed after testing; `im.majo.ctos` remains installed.

Screenshots and the read-back export for this run are in ignored `build/ctos-current-*` locally and are not committed. Vector displayed a pending framework-restart notice after the APK replacement; “Later” was selected and the device was not rebooted. The bridge test used the module already loaded in `system_server`; a fresh module load after reboot was not tested. Android 16 acceptance also remains pending.

## 2026-09-24 Docker build wrapper

`hako` uses `ghcr.io/cirruslabs/flutter:3.35.7` at manifest SHA-256 `d271a49ddd8ce1be6c7954f7eabe0e03766c8a1bf1430f0dce780888a21ed408`. It copies Flutter and Android SDK into ignored `.devhome/` for the mapped host UID, then installs Android SDK 36, Build Tools 36.0.0 and NDK 27.0.12077973. No host port is published. The sandbox initially denied direct Docker socket access; scoped Docker execution was then granted for this validation.

Checks run on 2026-09-24:

- `bash -n hako hako-env.sh`, `shellcheck hako hako-env.sh`, `shfmt -d hako hako-env.sh`, and `git diff --check`: passed.
- `./hako flutter --version`: Flutter 3.35.7 / Dart 3.9.2.
- `./hako flutter pub get`: passed.
- `./hako flutter analyze`: no issues.
- `./hako flutter test`: 6 tests passed.
- `./hako flutter build apk --release --target-platform android-arm64`: passed after clearing older host-path build caches with `./hako flutter clean` and rerunning pub get. A prior attempt failed because an ignored Flutter build cache still pointed to `HOST-BUILD-CACHE` outside the container.
- `./hako bash -lc 'cd android && ./gradlew :app:lintRelease --console=plain'`: passed; Android lint reports 0 errors, 6 warnings.
- Container `apksigner verify --verbose` on the new output: passed with APK Signature Scheme v2 and one signer.

The first successful container build produced `build/app/outputs/flutter-apk/app-release.apk`, 22,844,406 bytes, SHA-256 `27a473e1d5df19000ee47d68ff573a3004e958307bbcc0de1db28ac4c636f5cc`. The later `./hako current` rebuild has the hash recorded above. The current APK was subsequently installed and tested on Android 11 as recorded above; Android 16 acceptance remains pending.

## 2026-09-24 source change status

The new device information and navigation source is built and passes host-side checks through Docker. It was installed and tested on Android 11 as above. Flutter and Dart remain absent from the host PATH; the previous `${EVIDENCE_DIR}/build/` environment is gone. The 2026-09-23 device checks below apply only to the superseded artifact identified by its hash. Android 16 acceptance and a fresh system-module load for the current APK remain pending.

## Superseded 2026-09-23 artifact

Former path: `dist/ctos-0.1.0-arm64.apk` (removed when current APK replaced it).

SHA-256: `7869f197e8abd4ee07a5ad97933a0f9b8fea0c65b48d8a2464ff0e7145402ca6`

Release-mode Flutter AOT, arm64 only, development debug certificate. APK v2 signature verification passed. Includes `libapp.so`, `libflutter.so`, `libctos_pty.so`, and `assets/xposed_init`.

## Passed

- `flutter analyze`: no issues.
- `flutter test`: 5 tests passed, covering phone-width layout and interface filtering; elapsed-time rates; VPN separation; counter reset/recreation; unsupported counters; bounded history.
- Gradle `:app:assembleRelease :app:assembleReleaseAndroidTest :app:lintRelease`: passed.
- Android lint: 0 errors, 6 warnings (pinned dependency versions, arm64-only ABI, package visibility acknowledged by root package lookup fallback, pre-Android-12 backup declaration).
- Initial APK installed and launched on TARGET-PHONE / Android 16.
- Initial Flutter network cards matched the observed Wi-Fi and Clash VPN configuration. ROOT status was visible.
- Vector detected ctOS; scope query returned `system / 0`. Official revision 8a156495 legacy source confirms system callbacks use package/process `android / android`.

## Development board acceptance

### Root notification regression fix

The initial acceptance missed an interaction defect: polling ran `su -c` every two seconds, causing repeated Magisk grant notifications. The superseded artifact replaced that with one explicitly opened collector shell shared by fixed queries. Timeout/closed sessions never reopen automatically; periodic collection falls back to Vector/app data and requires a user action to regain Root.

Rebuilt release/test APKs and ran Android lint successfully. Installed the replacement and reran **all 4 DeviceTest tests: OK, 7.071 seconds**. The new regression checks eight consecutive interface collections with stable parent identity, subshell exit-code isolation, timeout, and refusal to reopen a closed/timed-out session. System bridge, root connections and both interactive PTYs still pass. System module code did not change, so no reboot was needed.

Foreground observation after one grant showed the same collector `su` PID OWNED-PID under ctOS PID OWNED-PID across successive polls, continuing traffic updates, and no recurring grant toast in the later screenshot. Evidence: `${EVIDENCE_DIR}/build/root-session-tests.txt`, `root-fix-processes-{first,second}.txt`, `root-fix-{first,second}.png`. APK signature verified; installed SHA-256 matches the artifact above. A system Shell ANR dialog appeared after instrumentation; selecting Wait dismissed it and UI validation proceeded. No cause was established for that separate system dialog.

### Initial feature acceptance

Device: TARGET-BOARD, Android 11/API 30, arm64, SELinux Permissive, Magisk 30.7, Vector 2.2/3111. User authorized exclusive ADB UI testing and reboot. Only ctOS's module/scope and Root grant were changed.

- Then-current APK installed and launched; installed base.apk SHA-256 matched the historical artifact above.
- Rebooted after installing the updated system code. Vector logs show `system_server entry loaded` and `authenticated network bridge ready`.
- Full `DeviceTest` run: **OK (3 tests), 2.822 seconds**. System bridge returned UID 1000; root interface/route/connection collection passed; App and Root PTYs passed identity, controlling TTY, resize to 83 columns × 27 rows, interrupting `sleep 30` with Ctrl-C, and exit checks.
- Root PTY uses interactive `su`: Magisk's command mode did not provide working job control. Tests wait for the first prompt because Magisk initializes another PTY and flushes early input; they accept Magisk's mounted `/pts/` path and normalize CR for output matching.
- UI: VECTOR online and ROOT badges; default interface automatically eth1; filtering `eth1` returns its live counters; filtering connections by `5555` shows ADB sockets mapped to Shell (`com.android.shell`, UID 2000); Root terminal `id` displays UID 0.
- JSON saved through ACTION_CREATE_DOCUMENT to `Download/ctos-snapshot.json`, pulled and parsed successfully. Before enabling app Root collection, export showed `moduleActive=true`, module UID 1000, `Vector / procfs`, and 11 interfaces including eth1 with nonzero traffic. This independently verifies the Android 11 system-module counter fallback.
- Terminal test session closed and app returned to overview after inspection.

Raw logs, UI dumps, screenshots and the read-back export are under `${EVIDENCE_DIR}/build/` and are not committed. The export remains in the device's Downloads directory. Android 16 current-build acceptance remains unverified after the earlier phone disconnected; no claim of universal ROM compatibility is made.

## Repeat commands

For future device validation, run from this project directory after connecting an authorized device. Rebuild the Android test APK from the current source before running instrumentation; the test APK used on 2026-09-24 was built with the Docker wrapper:

```sh
adb -s "$DEVICE_SERIAL" install -r dist/ctos-current-arm64.apk
adb -s "$DEVICE_SERIAL" install -r build/app/outputs/apk/androidTest/release/app-release-androidTest.apk
adb -s "$DEVICE_SERIAL" shell am instrument -w -r \
  -e class im.majo.ctos.DeviceTest \
  im.majo.ctos.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$DEVICE_SERIAL" shell am start -n im.majo.ctos/.MainActivity
```

Check current Vector enabled state/scope first. Only ctOS is in scope for changes; do not alter other modules. Subsequent APK changes to system module classes require reloading that process. Device tests expect an active network, a preapproved ctOS Root grant, and an enabled system module loaded after reboot.

## Historical temporary build environment

SDK/JDK/Gradle and official Flutter checkout: `${EVIDENCE_DIR}/build/`.

The host already has a Flutter global Android SDK setting pointing at another SDK. To avoid changing it, this session builds through Gradle after writing the project's ignored `android/local.properties` to point at the temporary SDK and Flutter checkout. Flutter analyze/test may rewrite that file; set it immediately before Gradle.

Build invocation used:

```sh
JAVA_HOME=${EVIDENCE_DIR}/build/jdk \
GRADLE_USER_HOME=${EVIDENCE_DIR}/build/gradle-home \
./gradlew :app:assembleRelease :app:assembleReleaseAndroidTest :app:lintRelease \
  -Ptarget-platform=android-arm64 -Ptarget=lib/main.dart --console=plain
```

Run the above in `android/`. Native PTY compilation currently targets a Linux x86_64 build host.

## 2026-09-26 Portable 五项工具本地预检（安装前历史）

父 task：[portable-tools 检查](../.trellis/tasks/archive/2026-09/09-26-portable-tools/check.md)。具体行为见 [工具设计](portable-tools.md)。

- Flutter analyze 无问题，完整 Flutter 测试 31/31；Android lintRelease 0 error / 5 已知 warning，含 PortableToolsTest 的 release 测试 APK 编译通过，尚未执行本轮 instrumentation。
- ${EVIDENCE_DIR} uv：26 核心142项，五项集成113项，CLI12次调用，均通过。包括原08对照、原02实际CBC产物、新加密认证/篡改/源文件保留、token/二进制、09离线mock/有界响应/不跟随重定向、HFTP认证/上传下载/拒绝覆盖/穿越/符号链接。没有自动查询真实IP提供方。
- HFTP 真实浏览器 loopback smoke：上传、特殊编码目录、嵌套上传、下载、409不覆盖、375px布局及无JS错误通过。截图 `${EVIDENCE_DIR}/ctos-tools-check/hftp-browser-phone.png` 已查看；独立 Android 通知/后台生命周期不能由该结果推定。
- 全新私有 Python bootstrap 在 ${EVIDENCE_DIR} 下载、官方固定SHA-256、解包/3.13.7启动、第二次复用通过；bash -n / ShellCheck / shfmt 均通过。
- 最终 APK 26,105,995 bytes，SHA-256 `28a7fb5a2bb287b0aae5fb71fb37fce8167204cedebd52c3a51f916cadadcbe2`，构建/签名/SHA256SUMS通过。13个Python源码逐字节匹配；APK解出的 SDK2 九项目录、08/26/AES-GCM在ARM64 QEMU运行通过（cryptography OpenSSL3.0.18）；新动态库LOAD均16KiB对齐。QEMU linkerconfig/tzdata提示属于模拟环境。
- 测试 APK SHA-256 `a51a6f4478a3b6ee5eee51c88565d1b9796659fc7b3b83aa7ef1a64bf1ae555f`。产物及复验入口见父 task 检查，所有包测试/浏览器运行器保存在 ${EVIDENCE_DIR}，没有全局依赖安装。
- 当前只读确认 `PHONE-ADB-SERIAL` 在线、TARGET-PHONE/Android16。本 task 尚未获安装/通知授权；SAF选择/取消/导出、通知拒绝/允许、FGS后台/返回/停止、App/Root终端以及IP实网/离线仍待本轮设备验收，不借用上一版结果。Android11/原生16KiB页设备未验证。

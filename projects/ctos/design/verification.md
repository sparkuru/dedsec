# Verification

## 2026-09-26 终端输入同步修正

用户发现输入 `i` 后按 Tab，Shell 上方已有补全或候选文字，底部输入框却清空。原因是底部编辑值与 Shell 对当前行的重绘未同步；此前另观察到直接向这台设备的 App Shell 发送原始 ↑/↓ 时，长命令历史会被 Shell 截断重绘。当前实现保留底部已输入内容直到 Tab 返回，读取终端已渲染的可编辑行并同步到输入框；↑/↓改为浏览当前 PTY 会话通过底部提交的命令，再经同一 PTY 编辑路径恢复，不读取会话外的 Shell 历史。

- 最终源码 `./hako flutter analyze` 无问题，`./hako flutter test` **22/22 通过**，`git diff --check` 通过。`./hako current` 构建 arm64 release APK、验证签名与 `dist/SHA256SUMS`。当前 APK SHA-256 为 `1a610e33fc018d20f4b99161d6eb5e91f4eb594930f0d0033e3eea5c873245ae`；在 `192.168.9.9:45075` 覆盖安装，设备 `base.apk` 哈希一致。
- 该包在 PLR110 的 App Shell 中输入 `ec` 后按 Tab，Shell 与输入框都显示 `echo`（`/tmp/ctos-terminal-1a610e-tab-20260926.png`）。按用户原步骤输入 `i` 后按 Tab，Shell 列出 `if`、`in`、`integer` 等多个候选，上下都保留 `i`；继续从底部输入 `d`，上下成为 `id`，点键盘发送后返回应用 UID `10497`（`/tmp/ctos-terminal-1a610e-i-tab-20260926.png`、`/tmp/ctos-terminal-1a610e-i-tab-d-20260926.png`、`/tmp/ctos-terminal-1a610e-i-tab-id-20260926.png`）。执行 `echo hello` 后按 ↑，上下均恢复完整命令；按 ↓，上下均回到空草稿（`/tmp/ctos-terminal-1a610e-history-up-20260926.png`、`/tmp/ctos-terminal-1a610e-history-down-20260926.png`）。这些图片是本机临时证据。
- 当前包顶部“选择输出”打开了独立的“当前输出”页面，显示已渲染的命令、结果与提示符（`/tmp/ctos-terminal-1a610e-selection-20260926.png`）。选择复制、中文候选、普通文本 IME 类型、Root PTY 的 `id` 与 Ctrl-C 设备检查来自下节的较早构建或此修正前的中间包；当前包没有重新逐项实测这些操作。Android 11 开发板、其他输入法/ROM、当前包 Vector 桥接、全新安装授权弹窗、重启后的模块加载及导出未在本轮验收。

## 2026-09-26 终端流程与文案收敛（前一构建）

目标设备由 `adb devices -l` 重新确认：`192.168.9.9:45075`，OnePlus PLR110 / `OP6117L1`，Android 16/API 36，1272×2800，SELinux Enforcing；所有设备操作显式带 `-s 192.168.9.9:45075`。本轮仅覆盖安装 ctOS 并操作其 App/Root PTY，未更改 Magisk 或 Vector 配置。

- 该阶段源码执行 `./hako flutter analyze` 无问题，`./hako flutter test` **19/19 通过**，包含 IME 组合与候选替换、空字段退格、发送/回车、双入口与快捷键、输出快照、320dp/1.5 倍文字及 300px 软键盘内边距。`git diff --check` 通过。`./hako current` 构建 arm64 release APK、验证签名并生成 `dist/SHA256SUMS`；从 `dist/` 执行 `sha256sum --check SHA256SUMS` 通过。该阶段 APK SHA-256 为 `da530c09343b416f8dc4f41aebaacf80b87fe10838b2a84f9f81ed8a17376586`；覆盖安装成功，设备 `base.apk` 哈希一致。
- 该阶段包打开终端时显示“应用 Shell”“Root PTY”两个 item；会话顶部依次为“换用”“选择输出”和停止按钮。输入框无可见标签或占位说明；五个快捷键只有 Ctrl-C、Tab、Esc、↑、↓。上方输出区点击不唤起键盘（截图 `/tmp/ctos-terminal-final-output-tap2-20260926.png`），底部输入框唤起百度/Oplus 输入法。聚焦时 `dumpsys input_method` 显示 `inputType=0x1`、`imeOptions=0x2000004`，即普通文本与发送动作；输入法仍采用厂商自带的蓝色主题，应用不控制其皮肤。
- App Shell 中，中文拼音 `ni` 处于组合态时 Shell 行保持原样；点首个“你”候选后 Shell 显示“你”，键盘保持打开。软键盘退格删除该汉字；随后输入 `id` 并点键盘发送键，仅执行一次，结果为 `uid=10497(u0_a497)`。输入 `ec` 后点 Tab，Shell 补全为 `echo` 且键盘保持打开。打开顶部“选择输出”后可看到 `id` 结果和当前 Shell 行；长按出现系统 Copy 菜单，复制并返回后 App PTY 仍在。截图：`/tmp/ctos-terminal-final-composing2-20260926.png`、`/tmp/ctos-terminal-final-candidate2-20260926.png`、`/tmp/ctos-terminal-final-before-send-20260926.png`、`/tmp/ctos-terminal-final-id2-20260926.png`、`/tmp/ctos-terminal-final-tab2-20260926.png`、`/tmp/ctos-terminal-final-selected2-20260926.png`、`/tmp/ctos-terminal-final-return2-20260926.png`（本机临时证据）。
- 停止 App PTY 后重新出现双入口；用设备已有 ctOS Root 授权开启 Root PTY，输入并执行 `id` 得到 `uid=0(root)`、`context=u:r:magisk:s0`，随后关闭测试会话。截图：`/tmp/ctos-terminal-final-after-stop2-20260926.png`、`/tmp/ctos-terminal-final-root-id2-20260926.png`。此前中间包另核实了 ↑/↓ 历史与 `sleep 30` 的 Ctrl-C 中断；这些检查均不视为当前 `1a610e33…` 包的实测结果。
- 工作台该阶段包截图 `/tmp/ctos-terminal-final2-20260926.png` 显示已删重复介绍而能力、采集时间与 Vector 未响应信息仍可见。设备、网络、命令和连接页的静态文案由源码审查与组件测试覆盖，本次未逐页拍摄该阶段包截图。Android 11 开发板、其他输入法/ROM、Vector 桥接、全新安装授权弹窗、重启后的模块加载及导出未在该阶段验收。

中间构建 `2c95af59…` 在同一手机上曾观察到百度输入法候选替换异常；当时输入组件过早重置编辑值。`da530c09…` 构建改为保留 IME 编辑状态并按 Unicode 差量同步到 PTY，以上中文真机检查针对该包。

## 2026-09-25 QQ 新连接与 `tim` 分身别名

用户在应用图标视觉评审通过后报告：打开 QQ 再在 ctOS 搜索 `qq` 没有结果；随后要求支持名为 `tim` 的 QQ 分身。授权 PLR110 `192.168.9.9:45797` 上，QQ 主应用正在运行且只读 `ss -tunape` 有 13 条 UID 10377 记录，ctOS 旧快照中筛选 `qq` 为 0 条，手动刷新后显示 12 条及真实 QQ 图标。原因是连接页从后台返回、重新进入已有缓存时没有重新采集。现改为这两种进入方式刷新；没有匹配时明确提示快照范围及刷新入口。用户已通过分身卡片视觉评审。

同一手机存在 `UserInfo{999:MultiApp}` 分身用户；`cmd package list packages -U --user 999` 给 QQ 分身 UID `99910377`，只读 `ss -tunape` 在打开该分身后出现 19 条对应连接。原生包列表的 `uid:10377,99910377` 过去只解析首个 UID。现逐个映射；Oplus 桌面受保护收藏项将该包的用户序列号 10 对应标题记为 `tim`，由已有 Root 会话的固定只读查询获取，无法读取时回退到原名/包名/用户 ID。该 OEM 数据源不代表其他 ROM 已兼容。

- 当前 `dist/ctos-current-arm64.apk` SHA-256 为 `19b8922bce927066dcec7fd9a7d45ac5caf148f32943d8c1c44090738560791d`，`./hako current` 签名和 `SHA256SUMS` 校验通过；PLR110 覆盖安装后设备 `base.apk` 哈希一致。`./hako flutter analyze` 无问题、`./hako flutter test` **15 项通过**，最后一次 Java 修正后 Android `:app:lintRelease :app:assembleReleaseAndroidTest` **BUILD SUCCESSFUL**。
- `ef6e9981…` 中间包仪器测试全部 5 项合跑时，Vector 桥接因该机仍未响应而失败。最终 `19b8922b…` 包只跑分身序列号解析与三项 Root/PTY 检查，**OK 4/4（4.673 秒）**。这不构成 Vector 通过。Android 11 开发板未连接，当前包未在该板验收。
- 前一轮 `d9429780…` 安装包实机显示 UID `99910377` 的 QQ 企鹅图标、别名 `tim`、原名 QQ、用户 999 与包名；打开分身再返回 ctOS 后自动刷新并显示 18 条该 UID 连接，截图 `/tmp/ctos-clone-uid-filter.png`。最终包随后增加完整应用名/别名优先匹配，以及对可直接可见分身应用的别名映射；前者由 Flutter 单元/组件测试覆盖，后者由 Android 构建和 Root 连接仪器测试覆盖。最终包未重新取得解锁后的界面截图。实机主 QQ 旧快照 0 条→手动刷新 12 条的对照截图在 `/tmp/ctos-qq-before-refresh.png`、`/tmp/ctos-qq-after-refresh.png`。
- 中间构建 `890d235d…` 的用户资料解析正则曾抛异常，修正后的中间构建恢复了连接采集；最终构建的解析器另有 Android 仪器测试覆盖。一次中间包覆盖安装后 Root 未自动恢复，手动点击工作台“授权 Root”后成功；未据此推断最终包的启动授权结果。

## 2026-09-25 连接应用图标与名称检索

用户追加应用图标及“应用显示名、应用自身名称、包名”检索。原生 `Collector.connections` 在原有 `output`、`partial` 上添加 `apps` 映射，只为此次 `ss -tunape` 输出中出现的 UID 收集可访问应用的显示名、包名、`ApplicationInfo.name`、进程名与 72px PNG 图标；Flutter 解析后缓存。对不可见或图标读取失败的包保留可检索身份和通用占位图标，同 UID 多包可展开查看，不宣称单条连接唯一归属某个包。

- `./hako dart format`、`./hako flutter analyze` 通过，`./hako flutter test` **14 项通过**；新增测试覆盖多种名称检索、图标 Base64 解析失败回退、连接 UI，以及导出不包含图标数据。`./hako bash -lc 'cd android && ./gradlew :app:lintRelease :app:assembleReleaseAndroidTest --console=plain'` 在最后一次 Java 改动后 **BUILD SUCCESSFUL**。
- `./hako current` 构建并校验签名、`SHA256SUMS`。该版 `dist/ctos-current-arm64.apk` SHA-256 为 `78d7af8db4474970cd4bbcb7a9dc16c3dc9333fc13a4a3de455473ab2393537f`；授权 PLR110 `192.168.9.9:45797` 覆盖安装成功，设备 `base.apk` 哈希一致。配套 Android 测试 APK 重新构建、安装，该版安装包的 Root/PTY 三项仪器测试 **OK 3/3（3.099 秒）**。
- 该版安装包实机“信息 → 连接”显示 Quick Connect 的真实图标、显示名与 `com.heytap.accessory` 包名。输入显示名 `Quick Connect` 后匹配 11 条连接；截图 `/tmp/ctos-connection-app-icons-final.png`、`/tmp/ctos-connection-app-name-filter-final.png` 来自上述 `78d7af8d…` APK（本机临时证据）。`ApplicationInfo.name`、进程名与同 UID 多包筛选由模拟数据测试覆盖，未在此手机逐项实测；图标可见性取决于 Android 包可见范围。Android 11 与 Vector 对该版仍待验收。

## 2026-09-25 连接检索左对齐修正

用户在连接列表视觉评审中指出标题和说明居中。原因是连接页顶部 `Column` 默认按横轴居中，而 `heading` 宽度只包住文字；改为横向撑满后，标题、说明与搜索框左缘对齐。组件测试增加左缘坐标断言；`./hako flutter analyze` 无问题，`./hako flutter test` 13 项通过。

`./hako current` 重建并验证签名与校验和，该版 APK SHA-256 为 `78392858a4ee90cf9727a2219918ef058fd30333f6ec9c7b90c366db23604be8`。在同一授权 PLR110 上覆盖安装成功，设备 `base.apk` 哈希一致；实机截图 `/tmp/ctos-connection-items-left-aligned.png` 和 `/tmp/ctos-connection-items-left-aligned-filtered.png` 确认左对齐、逐条 item 及包名筛选。该次采集有 115 条连接，包名筛选匹配 11 条；数量随采集时间变化。该版安装包的三项 Root/PTY 仪器测试 **OK 3/3（2.832 秒）**。Android 11 与 Vector 仍未在此版验证。

## 2026-09-25 连接 item wrapper（用户追加）

在下节所述第一阶段验证后，用户追加“把原生 netstat 输出转换成一条条 item”。现有采集实际调用 `ss -tunape`；本次保留 Java 返回合同，在 Flutter 层解析协议、状态、队列、本地/远端、UID 和应用归属，保留权限/格式诊断及可展开原文。

- 授权设备仍为 `adb devices -l` 所列 `192.168.9.9:45797`（PLR110，Android 16/API 36，SELinux Enforcing），所有设备命令均用 `adb -s`。只读 `ss -tunape` 样本含 IPv4/IPv6、UID、定时器和 `Cannot open netlink socket: Permission denied` 提示；解析测试覆盖这些格式与异常行。
- `./hako dart format`、`./hako flutter analyze` 通过；`./hako flutter test` **13 项通过**。`./hako current` 构建并验证签名与 `SHA256SUMS`；连接 wrapper 首版 APK SHA-256 为 `1f6e6833359517229c091328d07d0918ad7222e8aca89603a25e489fbee133ce`。覆盖安装成功，设备 `base.apk` 哈希一致。
- 实机该版的“信息 → 连接”显示 126 条结构化连接；包名 `com.heytap.accessory` 筛选显示 11 条匹配，卡片保留 UID 和应用名，旧快照提示仍显示。截图：`/tmp/ctos-connection-items-final.png`、`/tmp/ctos-connection-items-final-filtered.png`。曾在中间构建中观察到 128 条连接，数量随采集时刻变化；截图仅作为本机临时证据。
- 本轮仅改 Flutter 和测试，未改 Java 采集、Root 会话、Vector 或 PTY。该版覆盖安装后，三项现有 Root/PTY 仪器测试再次合跑 **OK 3/3（2.827 秒）**，覆盖接口/连接、会话复用与超时后不自动重开、App/Root PTY。Android 11 开发板及 Vector 仍待该版验收。

## 2026-09-25 可信状态与工作台第一阶段

目标设备由当次 `adb devices -l` 列出并复核：`192.168.9.9:45797`，OnePlus PLR110 / `OP6117L1`，Android 16/API 36，arm64，SELinux Enforcing。全部设备命令显式指定此序列号；Android 11 开发板当次未在线。本次未清除数据、改动 Magisk/Vector 配置或重启。

- `./hako dart format` 格式化改动的 Dart 文件；`./hako flutter analyze` 无问题，`./hako flutter test` 11 项通过。状态单元测试与组件测试覆盖连接状态转换、360dp/1.5 倍文字、受限负载，以及 1024dp 下的 Root 恢复入口。
- `./hako current` 成功构建、验证签名并发布 `dist/ctos-current-arm64.apk`；`dist/SHA256SUMS` 校验通过。APK SHA-256：`4e26b346eb457e23d22e17bf45d655ff58262cda0cc5b358c7e678b333c0a9a9`。`adb -s 192.168.9.9:45797 install -r` 覆盖安装成功，设备 `base.apk` 哈希与新包一致。
- 实机工作台显示独立的 APP、ROOT、VECTOR 状态、设备摘要、恢复说明和常用操作；现有 ctOS Root 授权下 ROOT 在线，该手机的 VECTOR 未响应。连接页显示当前结果和采集时间；等待超过 30 秒后显示旧快照，手动刷新后更新采集时间。系统负载在该机被拒绝读取时显示易懂提示，原始 `EACCES` 默认收起，内存数据仍可查看。实机截图在 `/tmp/ctos-phase1-wake.png`、`/tmp/ctos-phase1-device.png`、`/tmp/ctos-phase1-connections.png`、`/tmp/ctos-phase1-connections-stale.png`、`/tmp/ctos-phase1-connections-refreshed.png`，属于本机临时证据。
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

Target: user-selected `192.168.9.9:45797`, confirmed as OnePlus PLR110 / `OP6117L1`, Android 16/API 36, `arm64-v8a`, SELinux Enforcing. All device commands explicitly selected this serial.

- The initial `adb install -r dist/ctos-current-arm64.apk` succeeded over the 2026-09-23 ctOS install, without uninstalling it. Its installed `base.apk` SHA-256 matched that day's initial artifact: `22ab383fbaf02024b59f68ef958224a9215625aca58a87b6dd23b724511c2bcd`.
- The matching Android test APK, SHA-256 `3efd86d076953baf267e1e937e9115f3b3bcec0ee4eb48c6699984e8c0cedf48`, installed successfully.
- Current ctOS launched on the phone's 1272×2800 screen. The workbench showed OnePlus PLR110, Android 16, four networks and 11 interfaces from `app / TrafficStats`; the device information page showed system and memory data. `/proc/loadavg` was denied under SELinux Enforcing and its CPU/load section explicitly reported `EACCES` as unavailable without permission.
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

Target: `192.168.9.14:5555`, confirmed as T-CHIP AIO-3568J / `rk3568_firefly_aioj`, Android 11/API 30, `arm64-v8a`, SELinux Permissive. This is the user-selected address for this run; commands explicitly targeted that serial.

- The previous `im.majo.ctos` and `im.majo.ctos.test` installs had incompatible development signatures. Both old packages were uninstalled, then the current app and freshly built test APK were installed successfully. Uninstalling ctOS cleared its app-private data; the previously exported `Download/ctos-snapshot.json` remained present. No other package or Vector configuration was changed, and the device was not rebooted.
- The installed app's `base.apk` SHA-256 was `22ab383fbaf02024b59f68ef958224a9215625aca58a87b6dd23b724511c2bcd`, identical to `dist/ctos-current-arm64.apk`. The test APK was built with `./hako bash -lc 'cd android && ./gradlew :app:assembleReleaseAndroidTest --console=plain'` and has SHA-256 `3efd86d076953baf267e1e937e9115f3b3bcec0ee4eb48c6699984e8c0cedf48`.
- `DeviceTest#vectorBridgeReturnsSystemIdentity`: **OK (1 test)**. The existing system bridge answered with UID 1000, `Vector / system_server`, and network data. This did not require a module change or reboot.
- `MainActivity` launched and remained foreground. Visual checks passed for the workbench, device-information page (model, Android/API, kernel, architecture and uptime), network page, interface list (11 interfaces, `Vector / procfs`), and connections page. Without Root, network collection showed its authorization prompt and connections explicitly marked partial results.
- Both App-permission read-only commands ran on the device: `device.info` returned manufacturer/model, Android/API, kernel, architectures and uptime; `memory.snapshot` returned total/available bytes and low-memory state. Both displayed `available`.
- App-permission PTY started from the terminal page; its `id` shortcut returned UID 10131 (`u0_a131`). The test session was closed. The recent ctOS process error log contained no entries.
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
- `./hako flutter build apk --release --target-platform android-arm64`: passed after clearing older host-path build caches with `./hako flutter clean` and rerunning pub get. A prior attempt failed because an ignored Flutter build cache still pointed to `/home/wkyuu/...` outside the container.
- `./hako bash -lc 'cd android && ./gradlew :app:lintRelease --console=plain'`: passed; Android lint reports 0 errors, 6 warnings.
- Container `apksigner verify --verbose` on the new output: passed with APK Signature Scheme v2 and one signer.

The first successful container build produced `build/app/outputs/flutter-apk/app-release.apk`, 22,844,406 bytes, SHA-256 `27a473e1d5df19000ee47d68ff573a3004e958307bbcc0de1db28ac4c636f5cc`. The later `./hako current` rebuild has the hash recorded above. The current APK was subsequently installed and tested on Android 11 as recorded above; Android 16 acceptance remains pending.

## 2026-09-24 source change status

The new device information and navigation source is built and passes host-side checks through Docker. It was installed and tested on Android 11 as above. Flutter and Dart remain absent from the host PATH; the previous `/tmp/dedsec-build/` environment is gone. The 2026-09-23 device checks below apply only to the superseded artifact identified by its hash. Android 16 acceptance and a fresh system-module load for the current APK remain pending.

## Superseded 2026-09-23 artifact

Former path: `dist/ctos-0.1.0-arm64.apk` (removed when current APK replaced it).

SHA-256: `7869f197e8abd4ee07a5ad97933a0f9b8fea0c65b48d8a2464ff0e7145402ca6`

Release-mode Flutter AOT, arm64 only, development debug certificate. APK v2 signature verification passed. Includes `libapp.so`, `libflutter.so`, `libctos_pty.so`, and `assets/xposed_init`.

## Passed

- `flutter analyze`: no issues.
- `flutter test`: 5 tests passed, covering phone-width layout and interface filtering; elapsed-time rates; VPN separation; counter reset/recreation; unsupported counters; bounded history.
- Gradle `:app:assembleRelease :app:assembleReleaseAndroidTest :app:lintRelease`: passed.
- Android lint: 0 errors, 6 warnings (pinned dependency versions, arm64-only ABI, package visibility acknowledged by root package lookup fallback, pre-Android-12 backup declaration).
- Initial APK installed and launched on OnePlus PLR110 / Android 16.
- Initial Flutter network cards matched the observed Wi-Fi and Clash VPN configuration. ROOT status was visible.
- Vector detected ctOS; scope query returned `system / 0`. Official revision 8a156495 legacy source confirms system callbacks use package/process `android / android`.

## Development board acceptance

### Root notification regression fix

The initial acceptance missed an interaction defect: polling ran `su -c` every two seconds, causing repeated Magisk grant notifications. The superseded artifact replaced that with one explicitly opened collector shell shared by fixed queries. Timeout/closed sessions never reopen automatically; periodic collection falls back to Vector/app data and requires a user action to regain Root.

Rebuilt release/test APKs and ran Android lint successfully. Installed the replacement and reran **all 4 DeviceTest tests: OK, 7.071 seconds**. The new regression checks eight consecutive interface collections with stable parent identity, subshell exit-code isolation, timeout, and refusal to reopen a closed/timed-out session. System bridge, root connections and both interactive PTYs still pass. System module code did not change, so no reboot was needed.

Foreground observation after one grant showed the same collector `su` PID 9430 under ctOS PID 9214 across successive polls, continuing traffic updates, and no recurring grant toast in the later screenshot. Evidence: `/tmp/dedsec-build/root-session-tests.txt`, `root-fix-processes-{first,second}.txt`, `root-fix-{first,second}.png`. APK signature verified; installed SHA-256 matches the artifact above. A system Shell ANR dialog appeared after instrumentation; selecting Wait dismissed it and UI validation proceeded. No cause was established for that separate system dialog.

### Initial feature acceptance

Device: T-CHIP / Firefly AIO-3568J, Android 11/API 30, arm64, SELinux Permissive, Magisk 30.7, Vector 2.2/3111. User authorized exclusive ADB UI testing and reboot. Only ctOS's module/scope and Root grant were changed.

- Then-current APK installed and launched; installed base.apk SHA-256 matched the historical artifact above.
- Rebooted after installing the updated system code. Vector logs show `system_server entry loaded` and `authenticated network bridge ready`.
- Full `DeviceTest` run: **OK (3 tests), 2.822 seconds**. System bridge returned UID 1000; root interface/route/connection collection passed; App and Root PTYs passed identity, controlling TTY, resize to 83 columns × 27 rows, interrupting `sleep 30` with Ctrl-C, and exit checks.
- Root PTY uses interactive `su`: Magisk's command mode did not provide working job control. Tests wait for the first prompt because Magisk initializes another PTY and flushes early input; they accept Magisk's mounted `/pts/` path and normalize CR for output matching.
- UI: VECTOR online and ROOT badges; default interface automatically eth1; filtering `eth1` returns its live counters; filtering connections by `5555` shows ADB sockets mapped to Shell (`com.android.shell`, UID 2000); Root terminal `id` displays UID 0.
- JSON saved through ACTION_CREATE_DOCUMENT to `Download/ctos-snapshot.json`, pulled and parsed successfully. Before enabling app Root collection, export showed `moduleActive=true`, module UID 1000, `Vector / procfs`, and 11 interfaces including eth1 with nonzero traffic. This independently verifies the Android 11 system-module counter fallback.
- Terminal test session closed and app returned to overview after inspection.

Raw logs, UI dumps, screenshots and the read-back export are under `/tmp/dedsec-build/` and are not committed. The export remains in the device's Downloads directory. Android 16 current-build acceptance remains unverified after the earlier phone disconnected; no claim of universal ROM compatibility is made.

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

SDK/JDK/Gradle and official Flutter checkout: `/tmp/dedsec-build/`.

The host already has a Flutter global Android SDK setting pointing at another SDK. To avoid changing it, this session builds through Gradle after writing the project's ignored `android/local.properties` to point at the temporary SDK and Flutter checkout. Flutter analyze/test may rewrite that file; set it immediately before Gradle.

Build invocation used:

```sh
JAVA_HOME=/tmp/dedsec-build/jdk \
GRADLE_USER_HOME=/tmp/dedsec-build/gradle-home \
./gradlew :app:assembleRelease :app:assembleReleaseAndroidTest :app:lintRelease \
  -Ptarget-platform=android-arm64 -Ptarget=lib/main.dart --console=plain
```

Run the above in `android/`. Native PTY compilation currently targets a Linux x86_64 build host.

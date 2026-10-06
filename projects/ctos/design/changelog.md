# Changelog

## 2026-10-06 — 显示文案精简

- 顶部移除 `SYSTEM OBSERVATORY`，概览标签由 `DEVICE / 设备观测` 简化为 `DEVICE`。
- 静态分析、103 项 Flutter 测试及修改文件格式检查通过；未重建 APK 或复验设备。详见 [验证记录](verification.md#2026-10-06-两处显示文案精简)。

## 2026-10-05 — 全 Flutter 界面与交互优化

- 新增共享语义主题和展示组件；统一中文/数字排版、状态色、间距、48 dp 主要触控及控件边界对比。900 dp 起侧边导航保留已有内容状态，概览指标按宽度与文字缩放适配。
- 完善设备、网络、接口、连接、全部工具参数/结果、文件管理、HFTP、历史、导出及终端页面。工具目录采用紧凑分组，结果输出优先；时间仅改善展示，原始数据不变。清理影响说明、保存取消和复制失败反馈更明确。
- 终端键盘空间与快捷栏换行适配，统一输出页样式；普通过渡 200 ms，减少动画时取消非必要过渡。未修改原生/Python/协议/权限/服务生命周期，也未增加依赖。
- 最终 analyze、103 项 Flutter 测试和 release 构建/签名通过；107 场景、127 张实际 Flutter 图片补足宽度、大字和状态复验。APK `37d22600…` 与配置 A 最终安装哈希一致；具体实机范围、视觉迭代及限制见 [验证记录](verification.md)、[视觉设计](flutter-experience.md)。2026-10-06 用户确认观感通过并批准按计划提交收尾。

## 2026-09-28 — 界面与终端整理、产品机会集成

- 统一 Material 3 Card 表面、标题与能力状态字级；概览和信息阅读列宽至多 840 dp，终端画布保留整页宽度。
- 终端五个快捷键可收起/展开；输出快照增加搜索、高亮、匹配数和复制全部，保留 App/Root PTY 与原输入路径。
- `./hako flutter analyze`、79 项 Flutter 测试、release APK 构建/签名/校验通过；APK SHA-256 `ab13ceac96a7a8958659cb9bb12ab298dd65d9fa22b3ec729d2f5d26e30e014f`。另行授权的 `TARGET-BOARD` Android 11 App/Root PTY 定向验收见 [验证记录](verification.md) 与 [子任务检查](../.trellis/tasks/archive/2026-09/09-25-interface-terminal-polish/check.md)。不代表其他 ROM 或完整 Root 回归通过。
- 历史可信状态子任务补齐了逐项验收依据；当时的 Vector 条件已被后续移除决定取代。只读任务与界面终端子任务均已归档，父任务只负责文档和证据集成。

2026-09-28：用户要求的当前工作台/HFTP 保留成果脱敏提交已完成，专项任务按收敛范围归档；停止/亮屏传输/同端口重启修复已有实测，熄屏失败作为已知限制保留，Root 电源候选未实现。脱敏仅整理本次提交的设备、网络及本机环境标识，不改变协议或实现行为。以下未提交描述为对应历史阶段状态，最终结果见 [验证记录](verification.md)。

2026-09-28：完成 Trellis bootstrap 规范整理：按实际 Flutter、Android 宿主、JNI/C 与 Python 运行层重写指南，移除不适用的 React hooks 与 ORM 模板，并补充持久化、宿主桥接及状态生命周期约定。文档检查结果见 [bootstrap task](../.trellis/tasks/archive/2026-09/00-bootstrap-guidelines/check.md)。

2026-09-28：接入 `network.interface_diagnose` App-only 只读任务，接口详情携带并锁定接口名；Python 只收到新鲜快照中的单接口投影，保持实际缺失、失败、取消和超时状态。新增 Android host 版本化原子任务历史（仅三个只读 ID，20 条 / 5 MiB、淘汰最旧、手动清理）与工作台历史页面。总导出改为逐项预览选择网络、设备、连接快照及用户勾选的历史记录，保存仍走 SAF。Flutter、Python、Android 仪器和指定开发板的历史重启、单项 SAF 导出/取消及清理验收均通过，见[验证记录](verification.md)。

## 2026-09-27至28 HFTP停止与连续重启反馈（停止通过，熄屏未解决）

- 显式stop-request边界和stopping/closing状态，回调失效与后台精确关闭；失败清理期间保留owner，阻止新实例覆盖资源。
- 监听器使用checked SO_REUSEADDR，支持修正版传输后立即同端口重启，保留活跃端口独占，不改系统/VPN策略。
- 修复日志折叠bool/滚动double共用PageStorage的类型冲突，追加保留bucket的滚动/折叠/清空/重建回归。
- 修复先处理queued PING再检查原10s期限的竞态，closed日志增加固定原因；Root adapter持有有界App CPU锁，所有失败/停止路径释放，普通App不依赖它。错误标题改为服务异常，Root选项提示实际耗电。
- 9ca71e…包安装一致，73项Flutter、25项既有Python、24轮native传输+9项控制、10项最终手机检查通过；三轮实际LAN读写/停止/同端口重启含31MiB及日志操作通过。App锁仍未解决熄屏断连；用户选择暂不扩大Root范围，Root电源候选未实现。默认配置恢复、临时文件精确清理；不提交/归档。见[验证记录](verification.md)。

## 2026-09-27 HFTP日志与Root能力分离

- ToolExecutionContext显式声明APP/ROOT需求；ctOS RootOperationAdapter只承载已登记网络中继，缺失/未知/不匹配在效果前拒绝，普通App路径不依赖Root。Python HTTP/文件/SAF不提权；collector/PTY保留既有入口。
- HFTP可选Root LAN中继，UID0 helper只转发指定Wi-Fi同子网到App loopback后端；有界并发、心跳、生命周期与精确回收，不修改VPN/Clash/系统网络规则。启动必须显式Root true，缺省false。
- 实时有界服务日志、复制/清空/停止保留/旧会话保护；spec追加页面只保留必要操作说明、避免demo和实现细节堆叠。
- 最终0575e00d…包安装哈希一致；Windows与LAN33MiB读写、无覆盖/限额/后台/停止验收通过，App UID及Root UID实测。恢复默认配置并清理隔离夹具；未提交/归档。完整证据和未测范围见[验证记录](verification.md)。

## 2026-09-27 九张工作台图片反馈

- 工具标题去编号，删除script ID技术详情，文本操作与文件按钮全宽，下拉弹窗对齐；普通秘密输入保持遮挡并使用原有IME。
- App HFTP默认LAN/7888/免登录，上传1–1024MiB（默认32），恢复配置与SAF本机目录选择；保留原私有库。用户目录不提供清理，停止回收Python进程与原生目录broker，普通工具/SDK/CLI认证兼容保持。
- 修正TARGET-PHONE安全键盘触发条件，兼容Downloads本机provider；加强写入目标屏蔽、流式错误边界与请求owner保护。工具/HTTP仍是Python，Java只承担Android桥接。
- 最终d43f52ed…安装及普通IME、两个provider授权、33MiB精确传输、无覆盖/限额/后台/停止通过；电脑LAN直连仍超时，未宣称已修复原Firefox故障。服务停止与临时文件清理完成，未提交或归档；见 [验证记录](verification.md)。

## 2026-09-27 工作台体验优化

- 五项常用工具前置，其他脚本、环境和临时文件管理降为次级；保留深色/薄荷绿与全部九项入口。
- 中文参数、密码高级项折叠、长度数字校验、编码文本/文件来源及草稿保留、哈希方向隐藏、文件左对齐。
- 密码/哈希直接复制，IP 与设备摘要、文件预览及产物保存；密码默认遮挡涵盖原始详情和日志，完整 JSON 导出保持原协议。
- HFTP 状态和启动/停止主操作前置，共享库清理区分并保留确认；未改变服务生命周期、算法、SDK 或依赖。
- Flutter analyze、43 项测试及组件预览通过；Android lint、最终 APK 和新版设备验证范围见 [验证记录](verification.md)。本轮实现尚未提交或归档。
- 用户随后明确允许安装，TARGET-PHONE 新版安装哈希一致；界面、数字键盘、密码复制/遮挡、来源投影、SAF 导出28B读回/导入/取消及横屏定向验收通过。恢复旋转并精确删除外部测试文件，未运行 HFTP 或改权限。

## 2026-09-27 当前项目进度提交

按用户要求提交此前 Root-only、Python 工作台与五项 Portable 工具的源码、测试、设计和任务记录。README 的当前版本说明更新为最终五项工具包；本轮 ADB 连接和提交前本地检查见 [验证记录](verification.md)。保留任务状态，不归档；昨日设备验收记录仍按各自日期和包哈希解释。

## 2026-09-26 Portable 工具手机验收与原子提交

五项工具完成 TARGET-PHONE 安装与七项组合回归；SAF 导入/取消/产物导出读回、HFTP 常驻通知及通知停止通过。修复 Android App 硬链接 EACCES：SDK 产物、CLI 与 HFTP 共用不覆盖的原子重命名。测试显式隔离 Activity，消除 singleTop 后台任务复用带来的等待。09按官方文档使用现行免费HTTPS接口，指定公共IP实际查询通过。当前 APK `f928af66…` 与安装包哈希一致，测试服务/文件已精确清理。完整证据见 [验证记录](verification.md)，保留既有 WIP，未提交/归档。

## 2026-09-26 — Python 工作台与 Portable 包框架

- 移除系统负载及 `/proc/loadavg` 采集；四入口改为概览、信息、工作台、终端。
- APK 内置 CPython 3.13.9，工作台和 App/Root 终端共用原生启动器与标准库，提供 python3 的交互和文件执行入口。
- 四个脚本 item 进入二级页，具备参数校验、运行/取消、退出码/耗时、输出、复制及单结果 JSON 保存；SDK 统一注册表、Context 和结果。
- 新增 Portable v1 清单、APK ZIP/资源逻辑挂载、工具映射与环境生成，保留 curl 等 Android 原生工具的构建时接入方式。当前只内置 Python，不导入外部 ZIP。
- 工作台单子进程、15 秒超时、有界输入输出、后台回收；固定 -P -S 避免工作目录覆盖 SDK。内置环境失败时仍保留系统 Shell。
- Flutter 28 项、Android lint/测试包构建、本地 ARM64 QEMU 及 TARGET-PHONE 真机 8 项检查通过；App/Root 终端 python3、取消/超时/后台回收和工作台自检 JSON 保存已验收。修正 Android 安装目录含 `=` 时的工具启动兼容问题。详见 [验证记录](verification.md)。

## 2026-09-26 — 移除 Vector，使用 App/Root 采集

- 删除系统模块、Xposed API jar/入口/元数据、查询权限与 App 广播客户端；快照与 JSON 导出不再包含 module 字段。
- 删除 Vector 状态与启用/重启提示，保留 App/Root 能力、来源与失败恢复。网络配置由 App API 提供，增强接口/路由/连接及 PTY 通过已有 Root 会话工作。
- 已构建并覆盖安装当前包，Flutter 22 项、Android lint 与五项设备测试通过；当前包范围见 [验证记录](verification.md)，原因及能力取舍见 [决定](decisions/2026-09-26-root-only.md)。

## 2026-09-26 — 终端输入与输出选择

- 终端改为“应用 Shell”“Root PTY”两个会话入口；输出区只读，底部普通文本输入框显示当前输入并实时写入 PTY，键盘发送键执行回车。输入法组合文字提交后再发送，Tab 交给 Shell 自动补全并将补全结果同步到下方；↑/↓浏览本次会话从底部执行过的命令，选中命令通过同一 PTY 编辑路径显示在上下两处。
- 快捷栏保留 Ctrl-C、Tab、Esc、↑、↓；新增独立“选择输出”页，可滚动、选择和复制当前缓冲区快照，返回后会话继续。
- 清空终端输入框提示，将“选择输出”移到顶部“换用”右侧；去除终端欢迎语及工作台、设备、网络、命令、连接页的重复介绍，保留有用的权限、来源、采样和失败状态。
- 检查及当前设备的实际覆盖范围见 [验证记录](verification.md)。

## 2026-09-25 — 可信状态与工作台第一阶段

- 连接页区分首次读取、采集中、部分结果、无匹配、旧快照和刷新失败；显示独立采集时间，失败时保留上次数据。30 秒后提示重新确认。
- 连接输出增加 Flutter 解析层，将 `ss -tunape` 文本展示为逐条 item；保留无法解析的诊断与可展开原文，并支持按地址、端口、UID、应用筛选。
- 修正连接检索标题和说明居中的排版，使其与搜索框左缘对齐，并增加窄屏组件断言。
- 连接 item 加入可访问应用的真实图标、显示名与包名；按显示名、应用自身名称、进程名和包名检索，同 UID 多包可展开查看。未取得图标时显示占位图标。
- 打开 QQ 后返回 ctOS 或重新进入连接页会更新连接快照；无匹配时提示可刷新。按完整应用名、别名和包名筛选时优先精确匹配，避免短名称命中过多其他应用。
- 识别应用分身的独立 UID；在已验证的 Oplus 设备上读取桌面自定义别名，连接卡片可区分主 QQ 与名为 `CLONE-ALIAS` 的分身。别名不可取得时保留原名、包名和用户 ID。
- 图标 Base64 数据只用于界面，不写入 JSON 导出；保留导出中原有的连接文本与状态。
- 工作台分别呈现 App、Root、Vector 状态及手动恢复入口，按设备摘要、能力与恢复、常用操作排列；系统负载注明不是 CPU 使用率，权限受限时提供简明说明与可展开详情。
- 当前 APK 已在 Android 16 手机上覆盖安装并定向检查；Flutter 分析及 15 项测试、Android lint、四项适用设备测试通过。该手机的 Vector 桥接仍未响应；实机范围和一次未复现的旧包设备测试波动见 [verification.md](verification.md)。

## 2026-09-25 — Trellis Plus 初始化

- 新增项目自有的 Trellis Plus 共享规则与 ctOS 验证配置，覆盖提交前人工评审、Codex 归属、`hako` 复用、原生 UI 的 UUPM 使用及主线续接边界。
- 为 Codex 安装项目本地 UUPM 技能；生成文件位于被忽略的 `.codex/skills/`，未作为共享配置提交。现有 Trellis 受保护模板及产品代码未改动。
- 本轮未新建 Trellis 任务、未执行设备操作；检查结果见 [verification.md](verification.md)。

## 2026-09-25 — startup Root authorization and Android 16 check

- Request Root once on app startup, retain automatic startup after a successful grant, and restore the collector session after later launches or in-place installs. Failed grants or broken sessions require a manual retry; polling never reopens `su`.
- Rebuild the single current APK through `hako`, verify its signature, and install it on the user-selected TARGET-PHONE. Automatic restoration worked after a force-stop and an in-place reinstall; all three Root device tests passed. Vector bridge remained inactive on this phone.

## 2026-09-24 — Android 11 current APK check

- Install the current APK and matching Android test APK on the user-selected `TEST-BOARD-ADB-SERIAL` development board after replacing incompatible, differently signed ctOS packages.
- Verify the installed APK hash, all four Android instrumented tests, device information and navigation pages, both read-only commands, JSON export, and the App-permission terminal. Restore ctOS's Magisk Root switch to off after testing. Record pending Android 16 and fresh module-load checks in [verification.md](verification.md).

## 2026-09-24 — single current APK

- Replace the old version-named APK with `dist/ctos-current-arm64.apk` and update `dist/SHA256SUMS`.
- Add `./hako current` to rebuild, verify signing, and atomically replace the single current APK. Keep Flutter's `build/` output as an ignored intermediate.
- At this build stage, device acceptance was pending; the subsequent Android 11 check is recorded above.


## 2026-09-24 — containerized build wrapper

- Add `hako` and its container bootstrap for Flutter/Android build, analysis and tests without a host toolchain.
- Keep Flutter and SDK caches in ignored `.devhome/`; mount a container-specific `local.properties` without editing the host copy.
- No development service or host port is needed. Container analysis, six Flutter tests, APK build, signature verification and Android lint passed; device acceptance remains pending.


## 2026-09-24 — device information source changes

- Add App-permission device snapshots for system, load average, memory, battery and `/data` storage, with per-section source, timestamp, state and error.
- Add workbench, information and two read-only structured query entry points; keep network and terminal paths in the navigation.
- Limit periodic network sampling to foreground workbench/network views; device readings are requested on demand.
- Update narrow-screen widget coverage and add device snapshot parsing coverage. The Docker wrapper now builds and checks this source; device validation remains pending.


## 2026-09-24 — planning and documentation

- Establish `design/` as the project information landing, with product plan, constraints and index.
- Move architecture, compatibility, verification, dependency provenance and changelog into `design/`.
- Add project AGENTS.md to route future work and documentation to the shared landing.
- Documentation only; no new product features or device verification in this update.

## 0.1.0 — in development

- Fix repeated Magisk notifications: reuse one explicitly authorized Root collector session instead of starting su on each poll; never auto-reopen a failed session.
- Flutter network observatory, searchable interfaces and connections, JSON export.
- Vector system network snapshot bridge.
- Built-in PTY with app and root shells, resize and interrupt support.

## 2026-09-26 Portable 五项工具源码与本地预检

- 建立 portable-tools 父 task 与六个子 task，仅接入 08、26、09、02、16-HFTP；核心、SDK adapters、终端 CLI 与 UI/原生宿主分层。
- SDK v2 追加 choice/secret/file；独立私有文件导入/产物导出/空间清理，失败保留源和已有输出。
- 02 加入固定 APK 加密依赖、新 AES-GCM 认证格式和明确旧 CBC 解密；08 去自动 UUID 盐，26 保留二进制转换/哈希，09 改为有界标准库 HTTPS。
- HFTP 独立 Android 前台服务、常驻通知及停止入口，后台继续、默认本机、独立认证共享库及配额；保留浏览/建目录/上传/下载。
- 构建 Python 限于 .devhome，离线/浏览器测试限于 ${EVIDENCE_DIR}；当前手机验收状态见 verification，不复用历史结果。

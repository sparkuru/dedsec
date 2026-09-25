# Changelog

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
- 识别应用分身的独立 UID；在已验证的 Oplus 设备上读取桌面自定义别名，连接卡片可区分主 QQ 与名为 `tim` 的分身。别名不可取得时保留原名、包名和用户 ID。
- 图标 Base64 数据只用于界面，不写入 JSON 导出；保留导出中原有的连接文本与状态。
- 工作台分别呈现 App、Root、Vector 状态及手动恢复入口，按设备摘要、能力与恢复、常用操作排列；系统负载注明不是 CPU 使用率，权限受限时提供简明说明与可展开详情。
- 当前 APK 已在 Android 16 手机上覆盖安装并定向检查；Flutter 分析及 15 项测试、Android lint、四项适用设备测试通过。该手机的 Vector 桥接仍未响应；实机范围和一次未复现的旧包设备测试波动见 [verification.md](verification.md)。

## 2026-09-25 — Trellis Plus 初始化

- 新增项目自有的 Trellis Plus 共享规则与 ctOS 验证配置，覆盖提交前人工评审、Codex 归属、`hako` 复用、原生 UI 的 UUPM 使用及主线续接边界。
- 为 Codex 安装项目本地 UUPM 技能；生成文件位于被忽略的 `.codex/skills/`，未作为共享配置提交。现有 Trellis 受保护模板及产品代码未改动。
- 本轮未新建 Trellis 任务、未执行设备操作；检查结果见 [verification.md](verification.md)。

## 2026-09-25 — startup Root authorization and Android 16 check

- Request Root once on app startup, retain automatic startup after a successful grant, and restore the collector session after later launches or in-place installs. Failed grants or broken sessions require a manual retry; polling never reopens `su`.
- Rebuild the single current APK through `hako`, verify its signature, and install it on the user-selected OnePlus PLR110. Automatic restoration worked after a force-stop and an in-place reinstall; all three Root device tests passed. Vector bridge remained inactive on this phone.

## 2026-09-24 — Android 11 current APK check

- Install the current APK and matching Android test APK on the user-selected `192.168.9.14:5555` development board after replacing incompatible, differently signed ctOS packages.
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

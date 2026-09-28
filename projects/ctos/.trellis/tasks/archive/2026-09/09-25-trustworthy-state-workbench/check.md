# 第一阶段检查记录（2026-09-25）

## 实现与验收证据

- 连接状态模型保存独立采集时间，30 秒过期；刷新失败保留旧数据。组件测试检查部分结果、无匹配、过期、刷新失败、前后台恢复；单元测试检查首次读取、空结果和状态转换。
- 工作台分列 App、Root、Vector，提供 Root 手动恢复；组件测试覆盖 360dp/1.5 倍文字和 1024dp 宽度，并断言启动自动 Root 调用次数为一次。Android 16 实机截图见 `/tmp/ctos-phase1-wake.png`。
- 系统负载说明和权限受限原因已在 Android 16 实机检查；技术异常默认收起，内存仍显示。截图见 `/tmp/ctos-phase1-device.png`。
- `./hako flutter analyze` 无问题；`./hako flutter test` 11 项通过；`./hako current` 构建、签名、校验通过。该版 APK SHA-256 为 `4e26b346eb457e23d22e17bf45d655ff58262cda0cc5b358c7e678b333c0a9a9`，设备安装包哈希一致。
- 设备 `192.168.9.9:45797`（PLR110，Android 16/API 36，SELinux Enforcing）连接页当前→30 秒旧快照→刷新后当前实机流程通过。三项 Root/PTY 仪器测试首次合跑有一次超时断言波动，单项复跑 1/1 与再次合跑 3/3 均通过，未改 Java 会话策略。
- `git diff --check` 与 `task.py validate` 通过。文档已同步到 `design/`；Android 11、新包 Vector、全新安装授权弹窗、实机导出读回尚未在本次验证。

## 评审分类与提交计划

`human-required`：本阶段改变首屏视觉和状态措辞，且 Android 11 / Vector 的当前包行为未在本次设备上核实。提交前请用户查看工作台、连接和受限负载截图或在授权设备上走同样路径，反馈通过/不通过及具体问题。保留现有无关工作区变更；如评审通过，仅按明确路径暂存本任务源码、测试与 `design/` / Trellis 任务资料。由于 `lib/main.dart` 在开始时已有用户改动，提交前需逐块核对其归属。

## 追加连接 item 验证

用户在首次视觉评审通过后追加连接 wrapper，原提交计划因此失效。`ConnectionReport` 将现有 `ss -tunape` 输出解析成 item，保留诊断和原文；`./hako flutter analyze` 通过，该版 `./hako flutter test` 13 项通过。连接 item 首版 APK SHA-256 为 `1f6e6833359517229c091328d07d0918ad7222e8aca89603a25e489fbee133ce`，PLR110 覆盖安装与设备哈希匹配。实机该版显示 126 条 item，以 `com.heytap.accessory` 筛出 11 条；截图见 `/tmp/ctos-connection-items-final.png`、`/tmp/ctos-connection-items-final-filtered.png`。该版三项 Root/PTY 仪器测试合跑通过。新增连接列表也需人工视觉评审后再拟提交。

## 连接检索左对齐修正

用户视觉评审指出标题/说明居中。将连接页顶部 `Column` 横向撑满，并在 360dp 组件测试中断言标题与搜索框左缘坐标相等。`./hako flutter analyze`、`./hako flutter test`（13 项）、`./hako current` 通过。该版 APK SHA-256 为 `78392858a4ee90cf9727a2219918ef058fd30333f6ec9c7b90c366db23604be8`；PLR110 覆盖安装、哈希匹配，实机左对齐和包名筛选见 `/tmp/ctos-connection-items-left-aligned.png`、`/tmp/ctos-connection-items-left-aligned-filtered.png`；该版包三项 Root/PTY 仪器测试合跑通过。用户在视觉复核时追加应用图标及名称检索。

## 应用图标与名称检索

用户在左对齐评审时追加真实应用图标及显示名/自身名称/包名检索。`Collector.connections` 为本次出现的 UID 附可访问应用元数据与小尺寸图标，按 UID+包名去重；Flutter 解析缓存并展示，同 UID 多包可展开。导出保留原有连接文本/状态，不包含图标数据，组件测试覆盖。`./hako flutter analyze`、`./hako flutter test`（14 项）、Android `:app:lintRelease :app:assembleReleaseAndroidTest`、`./hako current` 通过。该版 APK SHA-256 `78d7af8db4474970cd4bbcb7a9dc16c3dc9333fc13a4a3de455473ab2393537f`，PLR110 覆盖安装与设备哈希匹配，配套测试包三项 Root/PTY 仪器测试通过。该版实机 Quick Connect 真实图标与显示名筛选见 `/tmp/ctos-connection-app-icons-final.png`、`/tmp/ctos-connection-app-name-filter-final.png`；内部名称/进程名与多包情形由测试覆盖。用户通过该视觉评审后报告 QQ 新连接缺失并追加分身别名需求。

## QQ 新连接与 `tim` 分身别名

用户通过图标版视觉评审后指出，打开 QQ 再搜索 `qq` 为 0；PLR110 上旧快照为 0，手动刷新后 12 条，确认为未在后台返回及重新进入已有缓存的连接页时刷新。现两种路径均重新采集，失败保留旧数据；无匹配空态提示刷新。设备有 `UserInfo{999:MultiApp}`，QQ 分身 UID 99910377，在打开分身后 `ss` 有 19 条对应记录。原生侧把包列表的逗号分隔 UID 全部映射，并通过 Oplus 桌面受保护收藏项读取别名 `tim`；Flutter 对完整别名/应用名优先精确匹配，避免 runtime 等子串干扰。分身卡片中间包实机证据见 `/tmp/ctos-clone-uid-filter.png`，包含图标、`tim`、QQ、用户 999 与包名。

`./hako flutter analyze`、15 项 Flutter 测试、Android lint 与测试包构建通过。当前 APK SHA-256 `19b8922bce927066dcec7fd9a7d45ac5caf148f32943d8c1c44090738560791d`；PLR110 覆盖安装与设备包哈希一致。`ef6e9981…` 中间包全量 5 项合跑时 Vector 仍失败；最终包分身解析加三项 Root/PTY 定向重跑 **OK 4/4（4.673 秒）**。中间包 `890d235d…` 曾因用户行正则抛异常，已修复并增加解析仪器测试。最终包的 `tim` 精确筛选由模拟测试覆盖，实机截图来自前一轮 `d9429780…` 版本；Android 11 和其他 ROM 别名来源待验收。用户已通过分身卡片视觉评审。

## 归档验收追补核对（2026-09-28）

归档时 `task.json` 已是 `completed`，但 PRD 的八个验收框未同步勾选。本次只按上文和 [项目验证记录](../../../../../design/verification.md) 中已有证据补齐记录，没有重跑测试、构建或访问设备。以下编号对应 [PRD 验收标准](prd.md#acceptance-criteria)。

| 项 | 已保存的验收依据 | 适用边界 |
| --- | --- | --- |
| 1 | 连接状态模型及 widget/单元测试覆盖首次读取、部分结果、无匹配、过期、刷新失败、前后台恢复；Android 16 实机检查当前→旧快照→刷新。 | 状态转移主要由模拟测试覆盖。 |
| 2 | 工作台分别展示 App、Root、Vector 状态和手动恢复；启动自动 Root 调用次数由组件测试断言为一次。 | Vector 无响应时保留 App 数据属于当时版本；Vector 后来已移除，未宣称桥接在线。 |
| 3 | Android 16 设备页实测受限负载说明、可展开异常及仍可见的内存信息。 | 后续版本已移除系统负载采集。 |
| 4 | 360dp/1.5 倍字号和 1024dp 宽度组件测试通过，工作台实机截图已记录。 | 大屏为组件测试，不是平板实机验收。 |
| 5 | Flutter analyze、最终 15 项 Flutter 测试、Android lint 与最终包定向 Root/PTY/分身解析检查记录在上文；APK 身份和 Android 16 设备结果可追溯。 | Android 11 在该版未验收；Vector 仪器测试在中间包失败，最终包未重跑。 |
| 6 | `ConnectionReport` 解析列表并保留诊断/原文；连接 item、筛选和左对齐经组件与 Android 16 实机检查。 | 原生输出依设备权限和时点变化。 |
| 7 | Android 16 实机确认真实图标、显示名与包名；其他名称字段和同 UID 多包由模拟测试覆盖，缺失图标保留占位。 | 不能把模拟字段覆盖写成全部实机覆盖。 |
| 8 | QQ 打开后旧快照为 0、刷新后 12 条的实机复现定位了刷新问题；分身独立 UID、`tim` 别名及卡片截图有记录。最终包的别名精确筛选由模拟测试覆盖。 | 分身截图来自最终包之前的构建；非 Oplus 的别名回退为实现/测试结论，未做跨 ROM 实机验收。 |

历史验收完整性指每项均有对应范围内的证据，不表示该子任务全部功能在后续 APK 或其他 ROM 上重新实测。最终包 `19b8922b…` 的设备定向检查仅为分身解析和三项 Root/PTY，不能用中间包截图扩大其覆盖。

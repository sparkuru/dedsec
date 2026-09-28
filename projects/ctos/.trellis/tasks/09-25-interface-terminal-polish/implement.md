# 实施计划：界面与终端可用性整理

## 前置条件

- 在本设计获用户审阅并明确批准后，才运行 `task.py start` 和修改产品代码。
- 开始实现前重新读取本 task 的 PRD、design、research、frontend spec 和 Trellis Plus validation profile。
- P1 的设备授权不自动覆盖本 task。2026-09-28 用户另行授权在 `TARGET-BOARD` 安装本 task release APK 并完成 App Shell、键盘、快捷栏、Ctrl-C、输出搜索/复制、返回 PTY 和 Root PTY 只读 `id` 验收；按授权边界不运行 Root instrumentation tests，不改系统/VPN 配置、不清数据或重启。

## 顺序

1. **主题与阅读宽度**：为 `Card` 明确共享 surface、圆角、色调和间距；将主页面自定义卡片接入同一主题；限制概览/信息页与输出正文宽度，保持终端画布完整宽度。
2. **终端快捷栏**：添加可访问的展开/收起状态和控制按钮；以可换行布局显示原 Ctrl-C、Tab、Esc、↑、↓ 控件；确认折叠/切换不写入 PTY、不抢夺唯一输入焦点。
3. **输出快照操作**：在现有选择页面添加本地搜索、高亮/结果数、复制完整快照和反馈；保留空状态、原文选择与返回会话行为。
4. **widget 回归**：在现有 `state_ui_test.dart`、`terminal_interaction_test.dart` 中补充/更新断言：连接状态仍可识别；共享宽度在大屏受限；终端五键可折叠且按键序列不变；搜索命中/无命中/清除、复制文本精确一致、空快照状态；320 dp 加键盘 inset 与 1.5 倍字号无异常。
5. **本地验证**：按顺序运行 `./hako flutter analyze`、`./hako flutter test`、`./hako current`。失败时先判断是否由本 task 引起；不要通过放宽或删除既有断言掩盖失败。
6. **设备验证（已获本 task 单独授权并完成）**：通过用户确认的 ADB serial 显式操作 `TARGET-BOARD`，核对 AIO-3568J / Android 11 / API 30 后安装当前 APK。检查 App Shell 普通软键盘、快捷栏折叠/恢复、运行中的 `sleep 60` 收到 Ctrl-C、输出选择/搜索/复制和返回后原 PTY 存活；按单独授权经 Root PTY 执行只读 `id`，确认 `uid=0(root)` 后关闭 Root 会话。记录在 `check.md` 与 `design/verification.md`；未运行 Root instrumentation tests，未改系统/VPN 配置、清除数据或重启。
7. **收尾**：复核 R1–R4、窄屏和大字号、文档/验证记录；检查 diff 与 `git diff --check`，更新稳定 spec；随后单独呈交明确的代码提交计划，按用户回复提交，再完成 Trellis archive/journal 流程。

## 主要风险与回退点

- `main.dart` 包含多个页面和终端状态，主题/宽度封装不要重排 Root、连接快照或 PTY 生命周期。
- 搜索高亮只处理输出页收到的快照；剪贴板写入必须复制原始字符串，不复制高亮 markup 或搜索状态文案。
- 大字号下顶部操作与底部导航可能挤压终端视口；保持纵向可用空间，并通过窄屏带键盘测试发现问题。
- 本任务不改 Android 原生层。若发现必须变更 Java/JNI 或系统配置才能满足标准，先停下并更新设计/征求范围决定。

## 完成证据

- 本地命令、widget 覆盖和 APK 构建在 check 记录中填写实际结果。
- 设备检查仅记载本 task 授权的场景和当前设备；未运行的 Root、Android 11/其他 ROM 场景不能标成通过。

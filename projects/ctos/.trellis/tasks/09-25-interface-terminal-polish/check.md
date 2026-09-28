# 实现与检查记录（2026-09-28）

## 已实现

- 以 Material 3 `CardThemeData` 统一面板底色、圆角、色调和外边距；主页面自定义面板改用主题 Card，设备页标题接入同一标题字级，能力状态字号从 10 dp 提升到 12 dp。
- 概览与信息阅读列居中并限制在 840 dp；工作台沿用原有宽度约束。交互终端仍使用整页宽度；输出快照阅读页限制在 840 dp。
- 终端快捷键收起/展开动作放入现有会话操作栏，不增加一行固定高度；五个原控制仍以至少 48 dp 高的可换行按钮提供，切换状态只改变 UI。
- 当前输出快照页增加大小写不敏感搜索、高亮、匹配数和整段复制；保留原文选择、空态和返回 PTY 行为。
- 新增宽屏内容列、输出搜索/复制/空输出，以及窄屏带键盘时快捷栏折叠的 widget 覆盖。

## 本地验证

- `./hako dart format lib/main.dart lib/device_page.dart lib/terminal_interaction.dart test/state_ui_test.dart test/terminal_interaction_test.dart`：格式化通过；最终定向测试断言修正后再次格式化，未产生格式变更。
- `./hako flutter test test/terminal_interaction_test.dart test/state_ui_test.dart`：12 项全部通过。
- `./hako flutter analyze`：通过，无问题。
- `./hako flutter test`：79 项全部通过。
- `./hako current`：arm64 release APK 构建与 apksigner 校验通过；产物 `dist/ctos-current-arm64.apk`，SHA-256 `ab13ceac96a7a8958659cb9bb12ab298dd65d9fa22b3ec729d2f5d26e30e014f`，`dist/SHA256SUMS` 校验通过。
- 首轮测试发现高亮断言需要将 `InlineSpan` 转为 `TextSpan` 才能检查子 span；也发现 320 dp 带键盘时单独占行的折叠按钮造成溢出。修正类型断言并将折叠按钮移入会话操作栏后，定向和全量测试通过。
- 稳定 spec 无需更新：实现沿用现有 Material 3 主题、终端输入与快照交互边界，没有形成新的跨任务约定。

## 实机验收（2026-09-28）

- 目标：用户确认的 `TARGET-BOARD`（AIO-3568J，Android 11 / API 30）。所有设备操作均显式指定用户给出的 ADB serial；serial 按项目证据脱敏约定不写入此记录。
- `dist/ctos-current-arm64.apk` SHA-256 为 `ab13ceac96a7a8958659cb9bb12ab298dd65d9fa22b3ec729d2f5d26e30e014f`，安装前校验与授权值一致；`adb install -r` 安装 `im.majo.ctos` 成功，未卸载旧包或清除应用数据。
- 启动 ctOS 时运行了既有 Root 自动恢复流程，随后 Root 状态在线；未单独读取 `auto_start` 偏好值。App Shell 与普通 Android 软键盘可正常使用；快捷键工具栏实机收起/展开，收起时五个按键离开控件树，展开后恢复。320 dp、300 dp 键盘 inset 和 1.5 倍字号的布局由 widget 测试覆盖且无布局异常。
- App Shell 只读 `id` 返回应用沙盒身份；提交 `sleep 60` 后按 Ctrl-C，PTY 显示 `^C` 并回到 `$`。打开“选择输出”后，搜索 `sleep` 显示“找到 2 处”且命中高亮；“复制全部输出”显示“已复制全部输出”。返回后原 App Shell 历史和提示符仍在，确认 PTY 存活。
- 按用户单独授权启动 Root PTY，仅执行只读 `id`，返回 `uid=0(root)`；随后关闭 Root PTY。复核 App Shell 后也已关闭临时会话。未运行 Root instrumentation tests，没有更改系统/VPN 配置、清除数据或重启设备。
- 设备进入屏幕休眠时截图只显示时钟；通过触屏输入唤醒后，Activity 仍在前台且终端内容完整，未调整屏幕休眠设置。这不影响已完成的亮屏场景验收。

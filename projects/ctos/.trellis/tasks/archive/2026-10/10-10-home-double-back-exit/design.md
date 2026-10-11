# 主页双返回退出设计

2026-10-11，用户已要求开始并确认四个主入口 / 2 秒。

## 交互与边界

- 根路由首次返回保持当前内容，用遵循现有 Snackbar 主题的局部 Material 短提示显示“再按一次返回键退出”；第二次在 2 秒内调用 Flutter 标准平台退出能力，沿用 Android Activity 生命周期。
- 四入口共用一个局部状态所有者。切换入口、路由被覆盖、键盘打开、应用离开 resumed、销毁时清除计时与本任务提示；超时后的返回重新提示。确认只在根路由当前且无键盘/弹窗时有效。
- 二级页和弹窗优先正常返回。Android 通常先由 IME 消费返回；若 Flutter 收到键盘仍可见的返回，只收起键盘，不累计退出确认。实机行为留待授权验证。
- 不更改 Android/秘密库/PTY/HFTP 生命周期，不强杀进程，不主动停止后台服务。清除提示只操作本任务持有的短提示，不误关闭其他反馈。

## 技术形状

优先独立 Flutter guard 包装 Observatory 的 Scaffold，采用 PopScope 和局部 State，避免给大型主页增加退出状态细节。路由覆盖通知使用 Navigator observer / RouteAware，范围包括 PopupRoute，不能仅观察 PageRoute。局部计时在测试虚拟时间下可验证，所有 Timer/observer 在 dispose 释放。核对 pinned Flutter 3.35.7 的本地 PopScope/平台退出实现后编码；不使用废弃 WillPopScope。

实施研究发现共享 ScaffoldMessenger 不能任意移除尚未显示的排队 Snackbar；会推迟首次退出提示，且重置后的旧提示可能迟到。因此本任务使用独立的局部 Material 提示，立即显示、随状态精确移除；沿用 Snackbar theme，使用 Semantics liveRegion、IgnorePointer、SafeArea 与阅读宽度约束，不清理/重排其他 Snackbar。不增加第二套主题或嵌套透明 Scaffold。

初轮独立复核发现底部 body 提示可能被 Scaffold 绘制的浮动 Snackbar 遮住；首版临时放在 body 顶部、AppBar 下方，并用实际布局矩形不重叠的测试补足仅查找文字无法发现的遮挡问题。用户随后报告实机验收通过，要求提示在下方出现。

当前调整：由 HomeExitGuard builder 向主页 Scaffold 提供局部短提示，利用公共 floatingActionButton / centerFloat 布局槽协调底部导航、SafeArea 与 Snackbar；这是不可点击的退出提示，不增加操作按钮。采用 Flutter 自身布局规则避让浮动 Snackbar，不猜导航高度、测量私有队列或清理其他消息。继续保留普通反馈即时可见与不重叠断言，并检查底部提示位置、窄屏/横屏/大字及 production rail。

## UX 依据

已应用项目 UI/UX Pro Max：本地 Flutter 搜索 `navigation back snackbar state lifecycle` 给出 PopScope 建议；项目已有 Flutter 主题、Snackbar 与 widget-owned state 约定优先。反馈保持文本可读、支持大字与减少动效，不增加退出按钮、弹窗或设置，也不重新设计导航。第三方搜索只作为参考，不建立新设计系统。

## 验证与限制

Widget 测试覆盖各入口、第一次不退出/第二次退出的实际平台调用、超时边界、路由/弹窗/键盘/后台重置、切换入口及销毁。运行 analyze、全量 Flutter 测试和 arm64 release 编译，检查 diff 与源码/产物一致性。实际 Android IME、硬件返回/返回手势、桌面和重开仍需当轮授权设备验收，不能从平台 mock 推定通过。

## 回退

仅回退本任务 guard、主页接线与测试，保留其他任务未提交改动；不回退公共秘密库成果或替换 dist。

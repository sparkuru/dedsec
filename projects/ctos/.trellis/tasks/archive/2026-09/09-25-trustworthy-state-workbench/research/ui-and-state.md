# 规划调研：状态与工作台

日期：2026-09-25。依据：`lib/main.dart`、`lib/device_page.dart`、`android/app/src/main/java/im/majo/ctos/MainActivity.java`、`design/opportunities.md`、`design/verification.md`、本地历史截图，以及 `.codex/skills/ui-ux-pro-max/scripts/search.py` 的离线结果。

## 已确认

- 连接页仅在缓存为空时进入自动采集，手动刷新可重读；当前没有连接采集时间、单独失败态或旧数据标识。
- native 快照已提供 `rootError`、`moduleActive` 与 `root`；Root 失败不自动重启会话。
- Android 16 手机上 `/proc/loadavg` 返回 EACCES，设备页显示原始 Java 异常；这来自 2026-09-25 已记录的实测，不是本轮复验。
- 本轮 `adb devices -l` 只列出 `192.168.9.9:45797`、`model:PLR110`。未执行安装或设备配置变更。

## UUPM 检索结论与本项目取舍

检索词 `Android system observability dashboard terminal dark compact trustworthy` 返回深色运维面板、状态色、可扫读指标的建议；`mobile dashboard status freshness permission recovery accessible typography` 强调错误恢复、加载反馈、可访问名称与对比度；`Flutter Android responsive dashboard accessibility` 建议 `LayoutBuilder`、`Semantics` 与设备屏幕阅读器检查。工具还给出网页落地页和 CSS 建议，它们不适用于本机 Flutter 页面。

本项目决定保留现有暗底/薄荷主色，不复制工具生成的调色表。优先保证真实状态文案、约 48dp 触控面积、动态字号下布局、受限信息的恢复动作和大屏阅读宽度；不新增装饰动画或外部图标包。

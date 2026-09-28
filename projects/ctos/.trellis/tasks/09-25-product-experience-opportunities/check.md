# 父任务集成核对（2026-09-28）

本轮只核对已保存的任务、源码和验证记录，并修复文档；未运行 Flutter/Android 检查、构建或设备命令。当前产品代码无改动。三个子任务的设备授权和结果只适用于各自记录的 APK、设备与场景。

## 验收矩阵

| 父 PRD 条件 | 证据与集成结论 | 边界 |
| --- | --- | --- |
| 1. 子任务记录及跨任务契约 | [可信状态 PRD](../archive/2026-09/09-25-trustworthy-state-workbench/prd.md) 和 [追补核对](../archive/2026-09/09-25-trustworthy-state-workbench/check.md#归档验收追补核对2026-09-28)、[只读任务 PRD](../archive/2026-09/09-25-read-only-task-loop/prd.md) 与 [实现记录](../archive/2026-09/09-25-read-only-task-loop/implement.md)、[界面终端 PRD](../archive/2026-09/09-25-interface-terminal-polish/prd.md) 与 [检查记录](../archive/2026-09/09-25-interface-terminal-polish/check.md) 均有对应验收证据，`task.json` 为 `completed`。当前连接使用同一 `ConnectionSnapshotState`；设备字段来源/权限状态在 App 基础路径表达。`TaskHistoryStore` 仅允许三个只读 ID、20 条/5 MiB 且淘汰最旧；接口诊断只接收新鲜 App 网络快照中的精确接口投影。导出先选择、预览再由 SAF 保存；PTY 与工作台任务分开。 | P0 勾选为归档后按当时证据追补；Vector 与系统负载是历史版本要求，现行产品已移除。P1 的 Root 自动恢复仅在正常启动发生，未在 P1 执行 Root 测试。 |
| 2. 检查、APK 与设备对应 | P0 代码提交 `90024df`，归档 `d00f11d`；P1 代码 `fc254a4`，归档 `9c78cfe`；P2 代码 `ca04b04`，归档 `949a0f6`。P0 最终包 `19b8922b…` 的 15 项 Flutter、Android lint 与 4 项定向设备测试；P1 包 `56b3ca3c…` 的 76 项 Flutter、Android release 检查与 4 项定向仪器测试；P2 包 `ab13ceac…` 的 79 项 Flutter、release 签名/校验及 Android 11 App/Root PTY 手动场景，均在 [验证记录](../../../design/verification.md) 与子任务文件中列明。 | P0 手机为 Android 16；P1/P2 开发板为 Android 11，且两个任务使用不同 APK。P2 未运行 Root 仪器测试；P0 最终包的 `tim` 精确筛选是模拟测试，中间包实机截图不能当作最终包同场景复验。父任务未访问设备或重跑检查。 |
| 3. `design/` 当前状态 | [索引](../../../design/README.md) 标出三个已归档切片与当前 P2 产物；[规划](../../../design/plan.md) 区分阶段目标与已交付切片；[架构](../../../design/architecture.md) 记录现行 App/Root、历史、导出和终端呈现；[变更历史](../../../design/changelog.md) 增补 P2 与历史 P0 追补；[机会](../../../design/opportunities.md) 保留探索时建议并标明已交付范围。[验证记录](../../../design/verification.md) 已分别记载任务、APK、设备和未测范围，无需复制日志。 | 完整应用/进程/日志关联、收藏、多会话、持续任务及熄屏 HFTP 保活均不因本任务完成；其他 ROM、物理无 Root 设备和全新安装流程未由这些记录验收。 |
| 4. 归档后相对链接 | 对 `design/` 与本任务及三个子任务文档共 29 份 Markdown 检查 168 个本地相对链接，缺失目标为 0。修复了 `design/README.md` 的 P1、`design/changelog.md` 的 bootstrap，以及 P1 归档文件中的三条链接。父 PRD 的三条子任务链接已在规划时修复。 | 检查文件目标存在；未把未保存的临时截图当作长期证据。 |

## 文档检查

- `python3 .trellis/scripts/task.py list`：仅父任务活动，三个子任务已归档（3/3 done）。
- `python3 .trellis/scripts/task.py validate 09-25-product-experience-opportunities`：通过，两个上下文清单各有 2 个有效条目。
- `git diff --check`：通过。
- 29 份相关 Markdown、168 个本地相对链接：0 个缺失目标。
- 未运行 Flutter、Android、ADB 或 APK 操作；引用的通过结果属于子任务历史验证，不是本轮重测。

## Spec 复核

本轮修复的是归档验收勾选和路径漂移，并澄清当前文档与历史证据的关系。现有 [Trellis Plus 规则](../../spec/trellis-plus/index.md) 已要求按任务记录证据、检查文档链接、区分设备验收范围；无需新增共享规范。

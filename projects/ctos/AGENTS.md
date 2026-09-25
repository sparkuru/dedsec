<!-- TRELLIS:START -->
# Trellis Instructions

These instructions are for AI assistants working in this project.

This project is managed by Trellis. The working knowledge you need lives under `.trellis/`:

- `.trellis/workflow.md` — development phases, when to create tasks, skill routing
- `.trellis/spec/` — package- and layer-scoped coding guidelines (read before writing code in a given layer)
- `.trellis/workspace/` — per-developer journals and session traces
- `.trellis/tasks/` — active and archived tasks (PRDs, research, jsonl context)

If a Trellis command is available on your platform (e.g. `/trellis:finish-work`, `/trellis:continue`), prefer it over manual steps. Not every platform exposes every command.

If you're using Codex or another agent-capable tool, additional project-scoped helpers may live in:
- `.agents/skills/` — reusable Trellis skills
- `.codex/agents/` — optional custom subagents

Managed by Trellis. Edits outside this block are preserved; edits inside may be overwritten by a future `trellis update`.

<!-- TRELLIS:END -->

# ctOS 工作约定

- 工作范围限于 `projects/ctos`，遵守用户当前授权，不修改相邻项目或仓库顶层资源。
- 开始工作先读 [design/README.md](design/README.md)、[规划](design/plan.md)、[约束](design/constraints.md)，再读相关领域文档。
- `design/` 是 ctOS 项目各种信息的统一 landing。规划、设计、决策、兼容性、验证、依赖和变更历史均在此维护，并更新索引；不在其他目录建立平行文档体系。
- 根 README 保留简介和使用入口；本文件保留 agent 执行约定。实现变更后同步相关设计、变更历史及实际验证结果。
- 严格区分规划、已实现行为和实测结果。历史设备连接及授权不视为当前已验证或已授权。
- 开发板入口为 `adb connect 192.168.9.13:5555`；设备操作须符合当前任务范围并显式指定目标。

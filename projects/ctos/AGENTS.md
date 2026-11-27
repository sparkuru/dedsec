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
- 开发板入口为 `adb connect 192.168.9.14:5555`（用户于2026-10-13更正）；设备操作须符合当前任务范围并显式指定目标。

## Agent 间测试交接

- 用户当前安排其他 agent／session 执行产品测试，主 Codex 负责任务编排、实现、报告审核和项目状态；不得把主 Codex 自己运行的检查冒充独立测试。校验测试单的 JSON、路径和文档属于交接文件检查，不等于产品测试。用户明确另行指定时按新指令执行。
- `test/codex-wont-do/` 是共享文件系统上的逻辑 `socks_dir`，不是网络或 Unix socket。测试执行方先读该目录的 `template.json`、具体测试单、本文件和相关设计／规范，再核对前置条件与授权。文件命名、字段、状态、证据和交接操作以模板为准；详见 [测试交接设计](design/agent-test-handoff.md)。
- 测试单由创建方记录目标、资源、测试内容、通过标准与 Codex 审核项；执行方只按授权范围运行，记录实际命令、源码快照、结果和可审计证据，不修改通过标准、擅自修改产品源码或审批自己的报告。无法执行时如实标记阻塞，失败和不确定结果不得写成通过。
- 协作方更新同一任务前应以原子 `mkdir` 获取任务锁及测试单声明的资源锁，按模板在同目录写临时文件并原子替换；占锁时不抢占，过期锁须确认原执行者和子进程均已停止后，由项目管理者记录处理依据。此机制依赖协作，不提供身份认证；源码或产物在测试期间改变时应如实标记结论范围。
- 只有与执行方独立的 Codex 审核者可以修改审核决定，核对证据和源码身份后再更新 `design/`、Trellis 项目状态；报告被接受不代表测试通过，也不自动完成项目任务。交接文件及日志不得包含真实凭据、私钥或令牌，设备／真实账号仍需当前明确授权。
- Codex 建立和分发测试需求、跟踪依赖、独立审核报告及将结果用于后续开发和项目状态时，遵循 [Codex 测试分发与成果使用](design/codex-test-management.md)。

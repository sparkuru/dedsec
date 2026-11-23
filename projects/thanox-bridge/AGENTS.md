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

## 测试设备说明

1. 使用 `adb devices -l` 中的 `product:rk3568_firefly_aioj` 项测试, 上面安装了 thanox (pro 激活)
2. `archive/thanox-repo` 是 `https://github.com/Tornaco/Thanox.git` 的官方源码, 供查阅
3. thanox 文档可见 `https://tornaco.github.io/Thanox-Docs/en/`
4. 优先使用 `$browseros-neo` skill + 对应 mcp 展开网络检索, 若不可用再退选 agent 自身的 search 工具

## 项目主线与共享规则

开始或恢复项目任务、准备提交或归档前，读取 `.trellis/mainline.md` 和 `.trellis/spec/trellis-plus/index.md`，再按索引读取适用细则。创建或恢复需要委派的任务时，通过 `python3 .trellis/scripts/task.py add-context` 将主线、共享索引和适用细则分别加入该任务的 implement 与 check 上下文；不要假设加载器会自动展开 Markdown 链接。保持上面的 Trellis 托管区块不变。

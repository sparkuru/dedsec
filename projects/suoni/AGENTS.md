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

## Suoni project context

At session start and before implementation, checking, commit or archive, read
`.trellis/mainline.md` and `.trellis/spec/trellis-plus/index.md`, then the detail
files selected by that index. These project-owned files contain the Sony/Kotlin/
Flask boundaries, environment checkpoint and validation/review rules. For
authorized tasks, register applicable details explicitly in both implement and
check context; spec discovery alone does not inject them. Honor the user's
current task choice; the 2026-10-17 initialization creates no new task.

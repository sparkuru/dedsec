# Trellis Plus Project Policy

- ownership: project-shared
- source: project-authored
- tracking: track this index and its project-owned detail files; platform settings remain local

This is tacchi's shared development policy. Selected requirements are in [mainline](../../mainline.md), derived from [the design baseline](../../../archive/design.md) and subsequent approved decisions. P0 now uses explicitly selected Kotlin native Views; local build/logic checks pass and actual native UI/Vector/hardware acceptance remains open. Generated backend/frontend guidelines remain unfilled scaffolds, not evidence of tacchi coding conventions.

## Read before work

| When | Required detail |
| --- | --- |
| Every task and resumed session | [Development principles](development-principles.md) |
| Toolchain, build, configuration or runtime work | [Development environment](development.md) |
| UI planning or implementation | [UI workflow](ui.md) and [validation](validation.md) |
| Native Activity, identity/IPC, scope or release shrinking | [Native Android contracts](native-android.md) |
| Checking, staging, commit or archive | [Validation](validation.md) and [completion](completion.md) |
| Retaining or distributing third-party material | [Notice inventory](../../../third_party/index.md) |

Read the current task PRD, design and execution plan before editing. Resolve conflicts against explicit user decisions rather than silently changing acceptance. The application is an offline Android APK, not a browser service; do not invent Flask, WebView or a web preview to satisfy a generic scaffold.

## Ownership and loading

Write original shared rules here and task evidence in the normal `.trellis/tasks/` tree. Treat Trellis workflow, scripts, agents, config, metadata, managed AGENTS blocks and generated platform integration as protected. Do not patch those files to install policy. Preserve exact upstream notices; a directory location does not change authorship or license.

The installed Codex hook reads the active task's `implement.jsonl` or `check.jsonl` and task artifacts. It does not recursively load Markdown links. Native implement-context injection was observed during initialization; new shared files still need explicit registration and checking. Directory discovery alone does not load these rules into every future main session.

At task start, after substantial interruption and before completion, the coordinator must read this index and `.trellis/mainline.md`. For each relevant detail, register its path in **both** manifests using the existing interface, preserving and deduplicating existing entries:

```sh
python3 .trellis/scripts/task.py add-context "$TASK_DIR" implement .trellis/spec/trellis-plus/index.md 'Shared tacchi policy'
python3 .trellis/scripts/task.py add-context "$TASK_DIR" check .trellis/spec/trellis-plus/index.md 'Check shared tacchi policy'
python3 .trellis/scripts/task.py validate "$TASK_DIR"
```

Repeat the two `add-context` calls for each required detail and relevant task research, substituting its repository-relative path. Set `TASK_DIR` to the actual task directory first. Verify manifest contents and the resolved worker input. Without native injection, workers read each listed file and the task artifacts directly. This manual entry is required for future tasks until an existing non-protected startup extension is verified; no automatic enforcement is claimed.

After a Trellis update, recheck installed version, template metadata, policy links, contexts and local UUPM paths. Keep these rules outside template targets; do not restore them into protected files or change update exclusions. An update survival claim requires an actual update test, not merely this layout.

## Trellis Plus: Mainline Continuity

Maintain `.trellis/mainline.md` on approved requirement changes, task activation, checks and archive. Distinguish implemented, verified and proposed work. Preserve source documents and approval precedence. For a project-related no-task request or after archive, read task/history/git evidence and report the current objective, completed evidence, blocker, one ready candidate when unique and the next permitted action.

Default to `guided`: recommend the next task and wait for the user's selection. `paused` permits status reporting only. `serial` requires an explicit bounded list/order and stops on ambiguity, scope change, risk or unmet dependencies; it does not waive checks, review or commit authority. Do not create a parallel scheduler. The coordinator owns task lifecycle, mainline and commits; workers implement or check their assigned scope and return evidence.

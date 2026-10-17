# Trellis Plus Project Policy

- ownership: project-shared
- source: project-authored
- tracking: commit this file and its referenced project-owned detail files
- stage: development; no release or external compatibility contract is established

## Pre-Development Checklist

Read [mainline](../../mainline.md), this index, and every applicable detail below at session start, after a substantial interruption, and before completion or archive. The user explicitly declined task creation for the 2026-10-17 initialization. Do not create or activate a task to apply these rules.

| Detail | Read when |
| --- | --- |
| [Development principles](development-principles.md) | Every change, diagnosis, or review |
| [Sony and application boundaries](application.md) | Kotlin, Python, protocol, or feature work |
| [Development environment](development.md) | Before the first implementation, dependency/configuration changes, or preview work |
| [Validation and review](validation.md) | Planning acceptance, implementation, checking, or submit-ready decisions |
| [UI design](ui-design.md) | User-visible Android or future browser UI work |
| [Commit and archive](commit-policy.md) | Staging, committing, journaling, or archiving |
| [Third-party inventory](../../../third_party/index.md) | Copying, bundling, or distributing retained material |

## Policy Loading

The project-owned section of root `AGENTS.md` points here and to mainline; its Trellis-managed block remains unchanged. `python3 .trellis/scripts/get_context.py --mode packages` discovers this spec layer, but discovery lists paths rather than injecting the detail files. Markdown links alone do not load worker context.

For a future authorized task, explicitly add this index, `development-principles.md`, and each applicable detail to both manifests using `python3 .trellis/scripts/task.py add-context <task-directory> implement <file> "<reason>"` and the equivalent `check` command. Run `python3 .trellis/scripts/task.py validate <task-directory>` and inspect `list-context <task-directory>` before dispatch. Include the approved task design and relevant research. Keep existing entries and deduplicate additions. Native Codex context injection is preferred; child-side loading is the fallback. Never alter protected loaders to enforce this policy.

There is no active task in this initialization, so task dispatch and full start → check → archive context loading have not been exercised. Future sessions must read the linked details explicitly; do not equate this index's existence with automatic enforcement.

## Mainline Continuity

Default to `guided`: for relevant status/continuation requests, read mainline, source evidence, task records, validation and git state; report the objective, completed evidence, blockers, one ready candidate if determined, and permitted next action. This Project Pulse is read-only. A proposed feature does not authorize implementation or task creation. `paused` permits reporting only. `serial` requires a recorded, bounded user-authorized work list and order; stop for ambiguity, risk, scope changes or unmet dependencies. Reuse the normal Trellis task system when task use is authorized.

The main session owns scope, approvals, context curation, mainline updates, commits and archives. Delegated workers own only their assigned work and report evidence; they do not choose subsequent work or archive independently. Update mainline on approved scope changes, starts/resumes, actual check results and archives. Never mark implemented-but-unverified behavior as accepted.

## Ownership and Update Boundary

Keep shared policy here; local platform settings adapt execution only and remain ignored/untracked. Read Trellis workflow, scripts, agents, config and version/hash metadata without modifying them. Do not copy tool source into project-authored policy. Revalidate this layer, mainline, links, commands and future task manifests after `trellis update`; never restore custom rules into upstream files or add update skips to avoid review. No update or backup restore was performed during initialization.

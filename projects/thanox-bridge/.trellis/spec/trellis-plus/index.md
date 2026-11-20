# Trellis Plus Project Policy

- ownership: project-shared
- source: project-authored
- tracking: include this file and the referenced project-owned details in the project delivery snapshot; platform-local integration files remain untracked.

## Read and load

At project task start, after a material interruption, and before commit/archive, read `.trellis/mainline.md`, the current task and this index. Read the applicable detail files below. For each new or resumed delegated task, explicitly register this index, the applicable details and mainline in both `implement.jsonl` and `check.jsonl` using `python3 .trellis/scripts/task.py add-context`; do not assume the loader follows links. Root `AGENTS.md` provides the portable main-session entry. Task contexts provide worker inputs; neither proves native hook execution on its own.

- [Development principles](development-principles.md): scope, evidence, workspace and development-stage boundaries.
- [Android capability contracts](android-capabilities.md): on-demand lifecycle, Thanox/Root separation, identity, profiles, templates and extension boundaries.
- [Development environment](development.md): current tool evidence, device selection and first-implementation checkpoints.
- [Validation and human review](validation.md): stage-appropriate checks, physical-device testing and submit-ready review.
- [UI workflow](ui-workflow.md): local UUPM integration and Flutter/native validation; no present design is implied.
- [Commit and archive policy](commit-policy.md): explicit-path staging, supported no-auto-commit archive route and Codex attribution.
- [Third-party inventory](../../../third_party/index.md): retained upstream notices and local-only integrations.

## Continuity

Use the mainline for the current user-approved direction and task evidence. Default to `guided`: for relevant status/continuation requests inspect requirements, task state, validation and Git status, then recommend the next permitted action. `paused` permits no new work. `serial` advancement requires a recorded, bounded user authorization and still follows normal task and review gates. No second task system is introduced.

## Loading evidence

The root `AGENTS.md` extension instructs main sessions to read this index and register task context. Actual implementation/check input is the set of explicit JSONL entries, validated with `task.py validate`; a local Codex hook may inject those inputs when available, with child-side loading as fallback. The existence of this file or a hook configuration alone is not evidence that a future worker actually loaded it.

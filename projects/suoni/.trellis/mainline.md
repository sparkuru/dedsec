# Trellis Mainline

## Initiative

- title: Suoni — personal Sony device controller APK
- parent task: none
- objective: use local analysis/reference materials to develop an independent Sony controller APK with Kotlin and Flask
- owner decision: 2026-10-17 user request; initialize directly, apply trellis-plus, do not create a task; user subsequently approved Codex-local UUPM initialization
- current authorization: repository/policy initialization and UUPM setup; the user subsequently authorized one initial Git snapshot with subject `init suoni project`. No product feature implementation, device commands or publishing is authorized by this commit request.

## Requirements and Sources

Approved: APK outcome, Kotlin and Flask stack, reference-led development. Initialization acceptance: retain reference materials, establish usable project rules and source/approval boundaries, initialize the approved local UI tool, document verification and environment prerequisites, and create no task.

Sources: `archive/analyse/docs/sony-mdr-protocol.md` §§3–9 (reverse-engineered protocol), §§10–12 (recorded device tests and corrections); `archive/analyse/sony_mdr.py` (Python protocol/session and Linux tooling); `archive/SonyMdrTile/README.md` plus its Kotlin source/build inputs (Android control/tile prototype). These archived references are explicitly relevant because the user requested analysis-based work, but their feature lists are not an approved new product backlog. Keep originals intact.

Proposed: direct Android Bluetooth ownership; Flask laboratory/simulator API; Compose UI; an initial connection/battery/NC-ASM slice followed by other capabilities or tiles. None is a confirmed first release scope. API topology, first target model/firmware, application ID, minimum Android version, backend deployment, offline behavior and visual direction remain undecided. Do not infer universal Sony support or promise the prototype's whole feature set. No database, cloud account, browser dashboard, firmware flashing, FOTA, production hosting or APK distribution is in the initialization scope.

## Continuation

- mode: guided
- serial authorization: none
- next pulse: user-requested relevant no-task continuation/status
- task policy this session: do not create or activate a task; the bootstrap record predates this request

## Ordered Work

The rows below express proposed dependencies, not authorized tasks or a priority backlog.

| order | proposed work | state | readiness and dependency evidence |
| --- | --- | --- | --- |
| 1 | Confirm the APK/Flask boundary and first physical target | proposed | Needed to choose the actual Bluetooth owner, API and acceptance environment |
| 2 | Create a reproducible minimal Android/Flask scaffold | proposed | Depends on boundary, source layout and pinned toolchain decisions; apply the before-dev checkpoint |
| 3 | Implement and verify the selected first device-control slice | proposed | Depends on scaffold, agreed features and accessible phone/headphones; archived results alone do not satisfy acceptance |

## Evidence and Decisions

- Existing Trellis 0.6.17 installation was inspected and retained; it was not reinitialized or updated. Generic backend/frontend specs are untouched templates; the applicable project-authored guidance is `.trellis/spec/trellis-plus/index.md` and its details.
- Local UUPM CLI 2.2.3 initialized for Codex; its search help ran successfully. No generated visual design was adopted.
- New application code, Flask API, new APK build, Bluetooth connection and hardware acceptance: not implemented or run in this initialization.
- Git evidence: project resides in a parent worktree and was untracked before initialization; existing archive/platform/Trellis materials are user-owned. The authorized initial snapshot includes non-ignored shared initialization files and untouched installed Trellis material with its exact license. Local platform integrations, binaries and ignored archive records stay local. No nested Git initialization or identity change.
- Bootstrap record archived on disk at [00-bootstrap-guidelines](tasks/archive/2026-10/00-bootstrap-guidelines/initialization.md), status `completed`, completedAt `2026-10-17`; active task count is zero. No task was created or activated. Its generic checklist was superseded, not falsely checked off.
- Validation: 12 Markdown files / 9 relative links passed the structural check before archive; all 34 checklist IDs covered (40 independent rows: 24 PASS, 3 GAP, 4 UNKNOWN, 9 N/A). Protected file hashes and original managed AGENTS content match the baseline; exact Trellis LICENSE comparison and Git whitespace/index checks passed. No application checks were run.
- Full archive closure is pending: the ancestor `archive/` rule ignores the destination. The user authorized an initial snapshot without changing that ignore policy; this is not an archive commit and does not add the task-archive trailer. No forced staging or ignore override is performed. The inventory records unresolved UUPM/prototype notice provenance; runtime/lifecycle validation remains explicitly unverified.
- Next product decision: choose Flask's runtime role and the first target/device-control slice before product implementation.

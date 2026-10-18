# Trellis Mainline

## Initiative

- title: Suoni — personal Sony device controller APK
- planning task: `.trellis/tasks/10-17-suoni-apk` (created 2026-10-17; scope not yet converged)
- objective: use local analysis/reference materials to develop an independent Kotlin Sony controller APK; use Flask only for development simulation and regression validation
- owner decision: 2026-10-17 user request; initialize directly, apply trellis-plus, do not create a task; user subsequently approved Codex-local UUPM initialization
- current authorization: initialization and its initial Git snapshot are complete. The user subsequently requested task creation and an initial Suoni APK plan using archive references, then approved the standalone APK / development-only Flask boundary and requested that it be written into mainline. Task creation, evidence research and planning/spec documentation are authorized; product implementation, device commands, new commits and publishing remain unapproved.

## Requirements and Sources

Approved: APK outcome, Kotlin and Flask stack, reference-led development. Initialization acceptance: retain reference materials, establish usable project rules and source/approval boundaries, initialize the approved local UI tool, document verification and environment prerequisites, and create no task.

Sources: `archive/analyse/docs/sony-mdr-protocol.md` §§3–9 (reverse-engineered protocol), §§10–12 (recorded device tests and corrections); `archive/analyse/sony_mdr.py` (Python protocol/session and Linux tooling); `archive/SonyMdrTile/README.md` plus its Kotlin source/build inputs (Android control/tile prototype). These archived references are explicitly relevant because the user requested analysis-based work, but their feature lists are not an approved new product backlog. Keep originals intact.

Confirmed runtime boundary (2026-10-17): Kotlin on Android owns the real Bluetooth connection, protocol session, device state and native controls. The APK must perform basic device control independently and offline, without a running Flask service, Sony App or Sony account. Flask is limited to development/laboratory simulation, protocol fixtures, replay and regression validation; it is outside the APK's runtime control path. Linux Bluetooth hosting, remote control and production backend deployment require a separate scope decision.

Proposed implementation: Android RFCOMM with UUID/SDP discovery; shared framing/session code with capability-driven MDR v1/v2 commands; native UI, with Compose as a candidate. The recommended first release covers paired-device selection, model/firmware/protocol information, battery, NC/ASM mode, supported ambient-level/voice controls, notifications and recoverable failures. Device-confirmed responses/notifications/readback determine success; ACK alone does not. First target model/firmware and phone, first-release feature scope, application ID, minimum Android version, UI technology/visual direction and laboratory API details remain undecided. Do not infer universal Sony support or promise the prototype's whole feature set.

Proposed first-release exclusions: EQ, playback/volume, DSEE, multipoint/pairing management, button mapping, voice-guidance configuration, tiles, persistent background operation, BLE GATT, adaptive sound, firmware flashing/FOTA, cloud accounts, browser dashboard and store publishing. These remain deferred candidates, not an authorized backlog. Core offline control is a confirmed requirement; the detailed first-release acceptance proposal is in [prd.md](tasks/10-17-suoni-apk/prd.md), with [design](tasks/10-17-suoni-apk/design.md) and [execution stages](tasks/10-17-suoni-apk/implement.md).

## Continuation

- mode: guided
- serial authorization: none
- next pulse: select the first acceptance headphone model/firmware and Android phone/version, then converge first-release scope in the planning task
- task policy this session: the new explicit task-creation request supersedes the initialization-only no-task choice. Keep `10-17-suoni-apk` in planning; do not run `task.py start` until planning converges and the user approves the latest final summary.

## Ordered Work

The rows below express proposed dependencies, not authorized tasks or a priority backlog.

| order | proposed work | state | readiness and dependency evidence |
| --- | --- | --- | --- |
| 0 | Converge target hardware, first-release scope and final plan | planning | Runtime boundary confirmed; hardware, feature and UX decisions remain; final summary approval required before implementation |
| 1 | Create a reproducible APK and laboratory scaffold | proposed | Pin toolchain/wrapper, complete the environment checkpoint, establish permissions and fixtures; acceptance: APK builds/installs and lab tools start/stop |
| 2 | Implement protocol/session and read-only device connection | proposed | Depends on scaffold and selected hardware; acceptance: deterministic byte/session checks plus real handshake, identity, capabilities and battery |
| 3 | Deliver the selected NC/ASM control loop | proposed | Depends on read-only evidence and approved features; acceptance: confirmed device state, external notifications, recoverable failures and offline operation without Flask/Sony App |
| 4 | Evaluate EQ/audio settings, tiles and multi-device control | deferred | Separate scope and capability decisions; each feature requires its own observable acceptance, including lifecycle/hardware checks where relevant |

## Evidence and Decisions

- Existing Trellis 0.6.17 installation was inspected and retained; it was not reinitialized or updated. Generic backend/frontend specs are untouched templates; the applicable project-authored guidance is `.trellis/spec/trellis-plus/index.md` and its details.
- Local UUPM CLI 2.2.3 initialized for Codex; its search help ran successfully. No generated visual design was adopted.
- New application code, Flask API, new APK build, Bluetooth connection and hardware acceptance: not implemented or run in this initialization.
- Git evidence: project resides in a parent worktree and was untracked before initialization; existing archive/platform/Trellis materials are user-owned. The authorized initial snapshot includes non-ignored shared initialization files and untouched installed Trellis material with its exact license. Local platform integrations, binaries and ignored archive records stay local. No nested Git initialization or identity change.
- Bootstrap record archived on disk at [00-bootstrap-guidelines](tasks/archive/2026-10/00-bootstrap-guidelines/initialization.md), status `completed`, completedAt `2026-10-17`; at initialization close, active task count was zero and no task had been created or activated. Its generic checklist was superseded, not falsely checked off. The later planning task is recorded separately below.
- Validation: 12 Markdown files / 9 relative links passed the structural check before archive; all 34 checklist IDs covered (40 independent rows: 24 PASS, 3 GAP, 4 UNKNOWN, 9 N/A). Protected file hashes and original managed AGENTS content match the baseline; exact Trellis LICENSE comparison and Git whitespace/index checks passed. No application checks were run.
- Full archive closure is pending: the ancestor `archive/` rule ignores the destination. The user authorized an initial snapshot without changing that ignore policy; this is not an archive commit and does not add the task-archive trailer. No forced staging or ignore override is performed. The inventory records unresolved UUPM/prototype notice provenance; runtime/lifecycle validation remains explicitly unverified.
- 2026-10-17 planning continuation: created `10-17-suoni-apk`; initial PRD/design/implementation draft and archive assessment record the proposed phone-owned RFCOMM path, Flask laboratory role and connection/battery/NC-ASM slice. These remain recommendations. Reference source code and official Android Bluetooth connection/permission docs were inspected; no build, service or hardware operation ran.
- 2026-10-17 owner confirmation: in response to the proposed standalone phone control / development-only Flask boundary, the user replied “可以；将其写成 mainline”. This confirms runtime ownership and offline behavior; it does not close the remaining device/scope decisions or authorize implementation. PRD, design, execution draft and application boundary guidance are synchronized with this decision.
- Next product decision: select the first target headphone/firmware and Android phone/version before finalizing the first device-control slice.

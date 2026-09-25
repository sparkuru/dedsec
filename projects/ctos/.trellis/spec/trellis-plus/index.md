# Trellis Plus Project Policy

- ownership: project-shared
- source: project-authored
- tracking: commit this file and its referenced project-owned detail files

This layer configures Trellis Plus for ctOS. Read it when planning, implementing,
checking, or finishing a relevant Trellis task. Keep task-specific decisions and
results in that task's normal records. Do not edit Trellis-managed workflow,
runtime, agent, hook, or platform template files to install these rules.

## Validation and submit-ready review

Use the [ctOS validation profile](validation.md) for exact commands and device
boundaries. Compare the completed diff, task acceptance criteria, and checks
before proposing any commit or archive. Classify review as `human-required`,
`human-optional`, or `human-not-needed` and state the reason in the commit plan.
Ask for targeted feedback before committing when a material check cannot run,
when real-device behavior or permissions remain unverified, or when the result
depends on visual or product judgment. Include the changed path, checks already
run, exact manual scenario, and requested pass/fail, screenshots, or logs.
Documentation-only changes with verified links and facts need no device review.

## Native UI and UUPM

ctOS is a Flutter Android app (`pubspec.yaml`, `lib/`, `test/`). Codex's
project-local UUPM entry point is `.codex/skills/ui-ux-pro-max/SKILL.md`.
For a task that changes visible structure or interaction, read that local
skill and relevant frontend guidance during planning; use its Flutter design
search to inform a task-specific design decision before implementation. Record
original, approved decisions in the Trellis task's `design.md` and load them
in implement and check context. Keep third-party generated material local
unless its license permits committing it. Check layout, state, touch,
accessibility, and device behavior against those decisions, then promote only
stable project-authored rules here after verification. Do not create an
unapproved second design-system authority.

Current UI is native Android. There is no browser-accessible acceptance path
or Playwright suite, so Playwright validation is not effective for current
Flutter screens. Reassess if a future task introduces a web target or browser
workflow. Use Flutter widget tests for automatable UI behavior and targeted
device checks for the remaining native behavior.

## Docker development entry point

Reuse the existing `./hako` and its ignored `.devhome/` cache.
Before a task needs development commands, check that the wrapper still works;
if absent or broken, follow `dev-it-in-docker` within that task's scope.
ctOS has no development server or host port, so no `dev.sh` is needed.
Agent-specific allow rules belong only in ignored personal configuration,
scoped to `./hako`; they must not authorize raw Docker or shell commands.

## Commit summary and attribution

At Trellis Phase 3.4, inspect the exact candidate paths, preserve unrelated
dirty work, and run `git diff --check` before staging. Stage only explicit
project-owned or separately authorized paths; never force-add ignored agent
configuration or stage protected Trellis files. For a substantial Codex-authored
work commit, draft a concise completion body covering the change, its reason,
checks, and material gaps, followed by
`Co-authored-by: OpenAI Codex <codex@openai.com>`. Omit the trailer for small
mechanical changes, user-authored files, and Trellis archive or journal commits.
No established Codex/OpenAI trailer appears in recent project history.

## Mainline continuity

For a project-relevant request with no active task, perform a read-only pulse:
read `.trellis/mainline.md` when present, task/archive evidence, validation,
and git state; then report the current initiative, blocker, one uniquely ready
candidate, and next permitted action. Default to `guided`: recommend, then
wait for the user to choose. `serial` continuation requires an explicit,
bounded authorization recorded in `.trellis/mainline.md`; stop for unclear
priority, dirty work, missing dependencies, risk, or scope change. Honor a
recorded `paused` mode. Do not create a mainline record from a suggested
backlog or assume an archive authorizes the next task.

## File ownership and updates

This spec and normal task data are project-owned. `.codex/`, `.agents/`, and
other platform settings are local and ignored. `.trellis/workflow.md`,
`.trellis/scripts/`, `.trellis/agents/`, `.trellis/config.yaml`, update
metadata, and Trellis-managed platform files are protected read-only inputs
for Trellis Plus. After `trellis update`, recheck this project-owned layer and
task context; never restore a customization into a protected template.
The installed Trellis license notice must be checked before any distribution
of protected material.

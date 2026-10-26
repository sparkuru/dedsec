# Development principles

These project-authored rules apply to all tacchi tasks. Read them with the active PRD and [mainline](../../mainline.md).

## Stage and safety boundaries

tacchi is an unreleased P0 prototype. No released interface or migration obligation has been established. A later task must record any real users, retained data or external contract for its affected surface before applying development shortcuts. A prototype label never makes device credentials or user files disposable.

The approved design requires offline runtime and no network service. Development convenience must not weaken IPC caller checks, per-operation biometric authorization, host credential ownership or fail-closed decisions. Use dedicated test data, not real payment secrets. If a later authorized tooling service needs device access, document its actual listening/publishing settings and trusted-network boundary; do not expose ports or change firewalls by inference.

## Changes

- Inspect the real execution path and reuse existing facilities before adding helpers or dependencies. Keep one implementation of each responsibility.
- Implement the current requirement and evidenced near-term need. Avoid speculative frameworks, caches, fallbacks and compatibility layers. The payment/command state machines explicitly required by the design remain necessary contracts.
- For unreleased interfaces without consumers, change callers and schemas together and remove obsolete prototype paths instead of retaining aliases. Preserve actual retained data; rebuilding storage requires demonstrated disposability and authorization.
- Keep unrelated cleanup, formatting, renames and upgrades out of the task. Include a discovered issue only when it blocks acceptance or the user adds it to scope.
- Justify dependencies by a concrete benefit unavailable from existing tools or a small local implementation. Pin chosen versions and retain provenance in the toolchain task.
- Handle plausible expected failures explicitly. Never convert an unavailable host, stale revision, unknown target or missing protection capability into apparent success or a weaker fallback.

## Evidence and verification

For bugs, establish observed behavior and the cause before changing the implementation. Label untested hypotheses. Do not rewrite implementation and assertions around a guess and use the rewritten test as proof of the original failure.

Test acceptance behavior through the real boundary where possible. Use fixtures only for named unavailable dependencies, and record what they cannot prove. Do not remove assertions, swallow errors, mock away the behavior under test or broaden success conditions to make a check pass. Run the smallest useful checks from [validation](validation.md); add regression coverage when it protects a meaningful contract, not for mechanical documentation changes. A build, screenshot or successful launch does not prove payment safety.

Report the exact command, result and unverified scope. Surface missing critical resources and failures promptly. Keep UI states and device evidence distinct from host unit-test evidence.

## Workspace and documentation

Inspect status and affected files before editing. Preserve pre-existing edits, untracked files and other agents' changes. Clean only artifacts created for this task; use `/tmp` for disposable outputs and retain intentional task evidence. Do not reset or clean the workspace to make verification easier.

README is human-owned: read it for context, change it only when explicitly requested. Put durable agent rules here, task-specific findings in the task, and operational help beside the actual command. Avoid redundant guides and speculative migration documentation.

Read the shared policy/mainline after interruptions. Update mainline from approved decisions and observed checks, never from accidental implementation drift. Before completion, inspect the diff for scope creep, lost user work, unintended dependencies and unverifiable claims.

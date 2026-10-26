# Completion, staging and archive

## Commit boundary

The coordinator reads [validation](validation.md), task acceptance/check evidence and mainline before staging or archive. Workers return evidence without committing, archiving or selecting later tasks. Initialization approval alone does not authorize a Git commit; reuse a granted commit/archive authorization only for its stated scope.

Inspect `git status --short`, `git diff --name-only` and every candidate path. Shared policy, task/mainline records and reviewed notices are project-shared; platform directories, environment secrets and ignored runtime state remain local. Trellis workflow/scripts/agents/config/metadata and managed platform files are protected and excluded from a Trellis Plus commit plan. Existing untracked reference sources and other user work are not implicitly included.

Stage explicit approved paths only. Never use broad or forced staging (`git add .`, `git add -A`, `git add -f` or equivalents). Check `git diff --check`, new files and `git diff --cached --name-only` before commit. Retained third-party material needs exact provenance and original notices in [the inventory](../../../third_party/index.md); mark missing evidence `license-notice-needed` and leave unresolved material out of a distribution plan. Do not change the root license or rewrite history to conceal a gap.

## Verified installed route

Trellis 0.6.17 exposes `python3 .trellis/scripts/task.py archive <task> --no-commit`. The default archive route commits automatically and stages the task source/destination plus changed child task records; it has no custom message flag. Use `--no-commit`, inspect the actual moved paths and bookkeeping, then make an explicitly scoped archive commit when authorized. For initialization tasks that were never PR-backed, the documented `--skip-branch-validation` may accompany that command; do not use it to conceal incorrect PR metadata.

Archive moves the task to `.trellis/tasks/archive/YYYY-MM/<task>/`, writes `status: completed` and `completedAt`, clears active session pointers, and can unlink child parent fields when archiving a parent. The parent retains archived child names. Inspect these effects instead of broadly staging `.trellis/`. Preserve ignored session pointers as local state.

`python3 .trellis/scripts/add_session.py --title '<title>' --commit '<OID or ->' --no-commit` writes journal/index data without auto-commit. Without that flag, its configured default can stage current-task and developer-workspace paths and commit. Journal recording is separate from archive attribution. Consult command help again if the installed version changes.

## Once-per-task attribution

For a Codex-assisted task, the successful archive commit contains a proportional completion summary: task identity, delivered outcome, actual checks, limitations and work commit references when available. End that message with exactly one trailer:

```text
Co-authored-by: OpenAI Codex <codex@openai.com>
```

Do not add this task-level trailer to ordinary work commits or separate journal commits. Do not invent attribution for unrelated historical work or change the user's Git identity. Prepare multiline text in a temporary file and use the supported `git commit -F` path after explicit staging review.

Before archive, inspect `git log --format=%B -n 20`, existing task/archive records and pending changes. A repository without commits has no message-style or archival precedent. Verify the resulting message and paths with `git show --format=full --stat <archive-commit>` and confirm the exact trailer occurs once. Update mainline with the archived path and commit reference.

If archival moved files but the commit failed, inspect destination status and Git history; resume only the pending commit once its absence is established. Never archive twice, create an empty attribution-only commit or amend old history automatically. If neither a supported custom message nor `--no-commit` route is available after an update, report `archive-attribution-blocked` before moving the task.

## Initialization closure

After applicable initialization checks, inspect `00-bootstrap-guidelines` and any prior archive attempt. Record which project-specific rules supersede generic scaffold requests, leaving undelivered original checkboxes unchecked. Real remaining acceptance work blocks closure unless completed, demonstrably superseded or explicitly transferred to an authorized linked task. Do not fabricate code examples for a repository without application code.

Close the completed child and parent separately through the supported route and review gate when archive/commit authority covers them. Verify each archived path, status/date, active-task removal and archive commit/trailer. An absent task with no incomplete attempt is skipped; an already archived and committed task is not recreated or reattributed. Record blocked or partial closure accurately in mainline; policy installation alone is not a fully closed initialization.

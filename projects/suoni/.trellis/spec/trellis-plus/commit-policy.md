# Commit, Journal and Archive Policy

## Path and Authority Checks

The Git root is currently an ancestor of this project (`../..`); do not initialize a nested `.git`, operate on unrelated projects or change Git identity. From this directory, use explicit project-relative paths; from the Git root, prefix them with `projects/suoni/`. Initialization alone did not authorize a Git commit. The user subsequently authorized the initial snapshot with the exact subject `init suoni project`; it retains non-ignored shared files and the untouched installed Trellis baseline with its exact license. This explicit initial snapshot is separate from Trellis Plus customization and is not a protected-file fork. Ignored archive records and personal integrations remain excluded.

Before staging, inspect status, unstaged/staged paths and `git diff --check`. Classify the complete candidate list: project-authored specs/mainline and task evidence are shared; platform settings and `.env` remain local; runtime, templates and managed AGENTS content are protected; normal app code needs scoped authorization. The original project section outside AGENTS' managed block is a shared policy pointer. Preserve existing inline/third-party notices. Never stage protected or personal files through Trellis Plus, never use broad or forced staging, and never include unrelated staged work in a commit. Review candidate and staged path lists separately.

## Supported Archive Route

Installed Trellis version: 0.6.17. Read `task.py archive --help` and `scripts/common/task_store.py` before relying on this route after updates. The ancestor repository's `archive/` ignore rule currently also ignores `.trellis/tasks/archive/`; local global exceptions do not override it. Preserve that behavior. A no-commit move is supported, but a future archive commit needs an explicitly approved ignore-policy resolution first. Do not force-add it or describe attribution as complete. The verified interface supports:

`python3 .trellis/scripts/task.py archive <task-name> --no-commit`

For a task without PR branch metadata, `--skip-branch-validation` is available; use only for work known not to be PR-backed. Archive updates task status/completedAt, moves `.trellis/tasks/<task-name>/` into `.trellis/tasks/archive/<YYYY-MM>/<task-name>/`, clears matching local session pointers and may unlink children. Inspect the record and all affected paths first. The default archive auto-commit stages source/destination and affected child paths, but provides no custom-message option; therefore use `--no-commit`, verify the move, then commit only reviewed explicit archive/bookkeeping paths if commit authority exists. Do not patch Trellis config/runtime to add a message route.

Once checks and required human review are satisfied, one successfully archived Codex-assisted task receives one archive commit summarizing outcome, verification/limits, task identity and work commits if any. Separate body from this exact trailer with a blank line:

`Co-authored-by: OpenAI Codex <codex@openai.com>`

Ordinary work/checkpoint commits and separate journal commits do not acquire that task trailer. Do not invent historical Codex contribution, amend history, change authors, create an empty attribution commit or duplicate a successful archive. Recent parent-repository messages use English conventional subjects; use a project-scoped subject such as `chore(suoni): archive <task-name>`.

After an authorized archive commit, inspect `git show --format=fuller --stat HEAD` and `git log -1 --format=%B` for the right paths/task and exactly one trailer, and update mainline. If a move succeeds but a commit is not authorized or fails, record the destination and pending state, inspect history before resuming only the commit; never rerun archive blindly. Unsupported message/no-commit routes must report `archive-attribution-blocked` without changing protected runtime. Missing attribution in an existing commit is reported, not automatically repaired.

## Journal and Initialization Closure

`python3 .trellis/scripts/add_session.py --help` exposes `--no-commit`; default journaling can automatically commit workspace/current-task paths. Use `--no-commit` when commit authority is absent. Journal bookkeeping is separate from task archival and receives no archive trailer.

For the pre-existing `00-bootstrap-guidelines`, record the actual project-specific replacements and checks in its existing task data. Keep the original generic checklist unchecked where it was superseded rather than delivered. This user requested no new task: do not create or activate another. When its substantive scope is completed/superseded, the invoked initialization procedure permits a data-preserving archive through the supported no-commit route. Do not claim the archive commit/attribution is complete until separately authorized and verified. Check for existing destinations, previous attempts and commits before any retry.

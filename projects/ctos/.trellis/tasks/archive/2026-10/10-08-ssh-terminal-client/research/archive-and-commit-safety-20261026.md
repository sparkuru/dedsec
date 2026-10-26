# Research: Nested-project archive and commit safety

- Query: Safely archive the delivered foreground-password SSH task and record its journal without staging dirty sibling tacchi work or breaking the native build.
- Scope: internal; read-only script, task-metadata and build-input investigation. This note is the only file written by the researcher.
- Date: 2026-10-26

## Findings

### Project root and Git index are different scopes

- `.trellis/scripts/common/paths.py:43` resolves `repo_root` to the nearest ancestor containing `.trellis`, so running from ctOS uses ctOS as the Trellis project root; it does not select the shared outer Git top-level.
- `.trellis/scripts/common/paths.py:113` places tasks at that project's `.trellis/tasks`.
- The helpers invoke Git with cwd=ctOS, so their relative staging paths stay inside ctOS. Git's index nevertheless belongs to the shared outer repository.

### Auto-commit is unsafe when another window has staged work

- `.trellis/scripts/common/task_store.py:551` sets archived task status to `completed` and `completedAt` before moving it. It does not validate original acceptance criteria.
- `.trellis/scripts/common/task_store.py:595` clears all active-session pointers referring to this task; `.trellis/scripts/common/task_store.py:606` runs auto-commit unless the CLI receives `--no-commit`.
- `.trellis/scripts/common/safe_commit.py:176` is broader than its narrow-scope comment: for a named task it stages the entire `.trellis/tasks/archive` subtree, not just the newly archived directory. Existing dirty files under other ctOS archives can enter the archive commit.
- `.trellis/scripts/common/task_store.py:692` also stages removal of the old active task with `git rm -r --cached --ignore-unmatch` scoped to that task.
- `.trellis/scripts/common/task_store.py:706` finally executes unrestricted `git commit -m`, with no path restriction. Already staged sibling files can therefore be included even though this helper did not stage them.
- `.trellis/scripts/add_session.py:437` stages the current developer's journal/index and, when known, the current task. If no task is known it deliberately skips all task paths (`:465`). However, its final `git commit -m` is likewise unrestricted (`:499`).
- `.trellis/scripts/common/config.py:217` defaults `session_auto_commit` to true; the project config does not override it. Both CLIs provide `--no-commit` (`task.py:547`, `add_session.py:632`). No protected script or config change is necessary.

Recommended main-session handling: use the existing archive/journal `--no-commit` options; stage only explicit authorized ctOS paths; inspect candidate paths; commit only those paths (for example an explicit `git commit --only -- <authorized paths>` after adding new files), preserving another window's index entries instead of resetting/unstaging them. Verify each resulting commit's changed paths. Do not use `git add -A` at the shared outer root or unrestricted script auto-commits in this dirty multi-project session.

### Archive destination and references

- `.trellis/scripts/common/task_utils.py:144` selects `.trellis/tasks/archive/<current YYYY-MM>/<same task directory>`; this session's expected destination is `.trellis/tasks/archive/2026-10/10-08-ssh-terminal-client`.
- The move is `shutil.move` (`task_utils.py:157`); the archive operation does not rewrite Markdown, shell, Gradle or JSON path references.
- `.trellis/scripts/common/task_context.py:173` only remaps exact historical task-self references while *reading JSONL context for an archived task*. This is not a file rewrite and does not rescue ordinary Markdown links or executable shell paths.
- Relative links from moved task Markdown to project `design/` become two directory levels deeper. Inbound project/parent links to the old active task need explicit repair. Keep historical command evidence clearly historical rather than claiming an old literal path is a current executable entry.

### Production build blocker discovered before archive

`android/app/build.gradle` has 13 literal active-task-prefix references at lines 182, 184, 186, 228, 229, 375, 669, 670, 673, 710, 711, 733 and 734. They include normal production native/config runner inputs and opt-in probe/test inputs. Moving only the task directory breaks future builds.

The research directory contained the following executable-input closure when inspected:

- Nine shells: `android-production-openssh-build.sh`, `android-config-evaluator-build.sh`, `android-material-inspector-build.sh`, `android-password-client-build.sh`, `android-stock-build.sh`, `android-upstream-build.sh`, `android-policy-build.sh`, `android-kex-build.sh`, `android-hostfile-issuer-build.sh`.
- Six patches: `openssh-10.6p1-bionic-includes.patch`, `openssh-10.6p1-banner-atomic.patch`, `openssh-10.6p1-hostfile-stream-writer.patch`, `openssh-10.6p1-kex-guard.patch`, `openssh-10.6p1-config-policy.patch`, `openssh-10.6p1-config-evaluator.patch`.
- No research `.md` is a live build-runner input in the inspected scripts.

Runtime details:

- Production runner reads ABI, stream-writer, KEX-guard and banner patches (`android-production-openssh-build.sh:12`, `:154`, `:171`). Gradle explicitly declares ABI/banner, while the always-run runner's own hash guards also cover stream/KEX.
- Config runner uses its fixed research directory for the config patch and input manifests (`android-config-evaluator-build.sh:10`, `:146`, `:151`, `:171`).
- Legacy stock/upstream/policy/KEX/issuer runners also hardcode active-task self-validation and patch paths. Material/password runners have no hardcoded task-root dependency, though their invocation paths and input manifests still change on relocation.
- Fixed ignored G1 source/object baseline, NDK paths, SHA guards, output roots and source-notice inputs remain separate unchanged dependencies; moving runners does not make a fresh machine's missing baseline available.

A stable build-owned directory under the existing `android/` hierarchy is appropriate. No existing `android/scripts` or root `scripts` directory was found. The initial suggestion was a new `android/scripts/ssh/`; the main session selected `android/ssh/`, which equally avoids lifecycle coupling. Move the executable closure together, keep patch bytes/expected hashes unchanged, update Gradle and runner fixed canonical/self-validation roots, and retain no-cache/no-symlink/baseline checks. Do not retain an active-task alias/symlink or teach production builds to depend on mutable task locations. Validate the relocated build inputs with the relevant native/config build; the app's Java/C/Dart runtime source need not be changed for this relocation.

### Scope closure without claiming unfinished features

- Active task metadata currently records the foreground-password increment as implemented/Android-validated, while `meta.implementation_complete` and `current_increment.parent_implementation_complete` remain false for the original full-client scope.
- The original client PRD at `prd.md:385` and `:400` assigns full parent R1–R10/AC1–AC9, which exceed the delivered foreground-password path.
- Existing parent `.trellis/tasks/10-08-ssh-terminal/task.json` is still `in_progress` and retains this child. The archive implementation preserves that relationship (`task_store.py:576`).
- `.trellis/scripts/common/tasks.py:105` counts a child missing from the active set as done, so parent child-progress can display complete even while its original requirements remain open. Preserve explicit parent status and deferred acceptance items to prevent that display from becoming a false implementation-complete claim.

Recommended closure record: user-requested archive of the delivered/password milestone; state which foreground path was actually accepted, retain original broader ACs as deferred in the existing parent/`design/plan.md`, and preserve original full-scope completion false. Do not turn all original ACs green merely because `archive` writes `status=completed`. No new task creation or next-feature implementation is implied by archive authorization.

## Related Specs

- `.trellis/spec/trellis-plus/index.md`: explicit project-owned path staging, preservation of unrelated dirty work, Codex attribution for substantive work, protected template inputs.
- `.trellis/spec/trellis-plus/validation.md`: checks proportional to the changed layer and current named-device authorization.
- `.trellis/spec/backend/directory-structure.md`: owning-layer placement; no existing stable runner directory convention was found.
- `.trellis/workflow.md`: task/archive lifecycle and scoped task context.

## External References

None required: all findings are based on the project's installed script implementation and current task metadata. No network, device, build or Git command was executed by this researcher.

## Caveats / Not Found

- The researcher did not inspect or mutate the shared Git index, so the main session must verify its live contents immediately before each actual commit.
- The shell/patch count is a snapshot before the delegated relocation worker starts; coordinate any count discrepancy with that worker rather than assuming a missing input.
- Other tasks or archives were not authorized for cleanup. Only the current delivered task should be archived.
- This note does not change protected Trellis scripts/config, task status, product files or sibling work.

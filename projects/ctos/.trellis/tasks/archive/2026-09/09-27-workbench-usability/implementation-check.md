# Implementation evidence — 2026-09-27

## Scope and outcome

The implementer changed only `lib/workbench.dart`, workbench UI/model modules,
Flutter workbench tests and this task's implementation records. Main-session
changes to planning, design and specs were preserved. No Android, Python, SDK,
CLI, global theme, device action, staging or commit was performed.

R1–R6 are implemented:

- Catalogue `tools` entries precede other scripts regardless of catalogue order;
  all nine existing entries, lazy loading/retry/refresh and environment details
  remain reachable. Temporary-file management follows scripts as a secondary
  section. Cards use 8dp gaps and 4dp vertical tile padding.
- `ParameterPresentation` owns localized labels, helpers, choice display and
  known password-length validation; unknown values retain metadata fallbacks.
  SDK keys, exact Unicode/whitespace strings and enum wire values are preserved.
- Password defaults to seed/length, numerical keyboard and 1–128 validation.
  Advanced fields stay mounted, preserving controller values/picker filenames
  and validation. Basic errors stay compact; an invalid hidden advanced field
  reveals its errors.
- Encoder exposes a source choice and retains both drafts. Submission projects
  the inactive source to empty without mutating drafts. Only an active file
  source requires a selected token, and valid selection clears earlier errors.
  Hash mode hides direction while retaining a compatible wire value.
- Public `ResultCard(scriptId, value, api)` presents masked/revealable passwords
  with direct copying, per-hash copying, text digest/IP summaries, and artifacts
  with filenames, formatted sizes, actual preview value and existing token
  export. Encoder preview maps are decoded as `kind/encoding/value`; binary
  preview is labeled Base64 and truncated preview is explicitly identified.
  Device/memory summaries retain source/capturedAt. Runtime summaries use known
  fields. Unknown result data remains readable in the fallback and all protocol
  fields remain available in original JSON details.
- On-screen sensitive raw data is masked, and any known password in stdout/
  stderr is replaced before JSON encoding so quote/backslash/newline escaping
  cannot reveal it. Explicit full JSON copy/save still uses the complete,
  untouched result envelope, with a visible notice that it contains passwords.
- HFTP state and a single state-dependent start/stop primary action appear
  first. Busy/starting safeguards, default loopback, manual startup, route
  lifecycle/polling, credential mask/copy, LAN explanation and service limits
  remain. Shared library import/clear is separate; clear uses error color and
  retains confirmation. No service lifecycle or backend changes were made.

## Commands and results

- `./hako dart format lib/workbench.dart lib/workbench` and later scoped format
  commands for the changed test/result files: exit 0.
- `./hako flutter test test/workbench_test.dart test/portable_tools_test.dart test/workbench_usability_test.dart --reporter expanded`
  passed 20/20 before the final escaped-password regression was added.
- Final `./hako flutter test --reporter expanded`: exit 0, **43/43 passed**.
  Full output: `${EVIDENCE_DIR}/ctos-workbench-full-tests.log` (temporary local evidence).
- Final `./hako flutter analyze`: exit 0, **No issues found**.
  Full output: `${EVIDENCE_DIR}/ctos-workbench-analyze.log`.
- `git diff --check`: exit 0.

Docker socket access was denied by the workspace sandbox on the first format
attempt; scoped `./hako` escalation succeeded. No automatic-review rejection
remains. An intermediate analyze found an unnecessary import in the main-owned
ignored preview harness; main removed it, and final analysis is clean.
Intermediate new-test failures were corrected: TextFormField label access,
Material FilledButton.icon subtype finding, lazy-list scrolling, Snackbar hit
position, and unintended advanced expansion during basic validation. Final
suite treats missed taps as fatal and passes without tap warnings.

## Behavioral coverage

Twelve new tests cover unordered nine-entry access; password numerical limits,
exact hidden values and hidden validation errors; source draft restoration,
localized choice wire values, hash direction, picker cancellation and required
file dispatch blocking; masked raw/copy/complete export; escaped password logs;
per-digest copying, typed preview copying and exact artifact export; nested IP
fields and unknown raw fields; unknown metadata/result fallback; explicit HFTP
start, busy/starting state, stop, navigation disposal and clear confirmation.
Three configurations additionally exercise password/encoder/crypto forms,
long crypto choice, raw/hash results, HFTP and full workbench list:
375×812, 812×375 and 375×812 with 2× text. Reduced animations are enabled;
48dp primary control height is asserted. Existing cancellation/timeout/re-run,
Unicode validation, file token, lifecycle and other app tests remain passing.

## Remaining checks

- Main/check agent owns Android lint, current APK build/signature/checksum,
  design/spec synchronization, diff review and review disposition.
- No new APK was installed. TARGET-PHONE visual quality, keyboard, TalkBack, real SAF,
  background service/notification paths and actual runtime execution are not
  established by mocked Flutter tests. Device checks require the task's current
  authorization. Other ROMs/Android 11/16KiB pages are not inferred as passing.
- Explicit JSON export is intentionally complete and can contain a password;
  default on-screen hiding does not redact the exported protocol.

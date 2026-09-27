# Quality review — 2026-09-27

Post-review update from main: user authorized installation; exact reviewed APK
installed on TARGET-PHONE, targeted UI/keyboard/SAF/source-hash acceptance passes
without source fixes. See [device-check.md](device-check.md). Review below
records the earlier source-check phase and its then-open device gate.

## Disposition

`human-required`: source review and local automated checks pass. The change
affects visible structure and interaction; the new APK still needs targeted
TARGET-PHONE visual, keyboard and SAF acceptance. Build/signature/checksum and final
design synchronization belong to the main session. This review does not claim
new device installation, service execution or acceptance.

## Findings (fixed)

None. No blocking source/test defect was found and no code or test was changed
by this reviewer. The implementation was checked directly against the active
PRD/design, frontend workbench contract, backend Python boundary and project
validation/review policy.

## Reviewed behavior

- `WorkbenchPage` renders catalogue `tools` first and keeps the remaining
  entries, existing loading/retry/refresh and secondary file manager reachable.
  It still routes by the existing catalogue rather than executing a duplicate
  registry. The existing HFTP route is retained.
- `ParameterPresentation` changes labels/helpers only. Dropdown values are the
  original protocol strings. Controllers preserve whitespace and Unicode;
  known password length is validated as an integer within 1–128. Advanced
  password fields remain mounted and validated; only invalid advanced fields
  cause the collapsed section to open.
- Encoder drafts stay mounted. `submission()` copies controller values and
  empties the inactive source in the outgoing map without clearing its draft.
  Inactive fields skip validation. Active file mode requires a token, selected
  filenames remain visible after switching, and picker cancellation preserves
  the existing selection. Hash hides direction but keeps its compatible value.
- `ResultCard` starts generated passwords masked, including raw data and known
  password occurrences in stdout/stderr before JSON encoding. Direct clipboard
  actions copy the target password/hash/preview. Explicit JSON copy/save retain
  the original complete envelope and show a password-content notice. The
  artifact token/map are passed unchanged to the existing file export API.
- Encoder preview objects use their `value`, with binary Base64 and truncation
  labels. IP summaries read nested provider data. Device/memory summaries retain
  source and capture time. Missing/unknown fields remain available through raw
  details; unknown result types use the fallback.
- HFTP layout moved status and the state-dependent primary action first while
  retaining existing host/port validation, default loopback, manual start,
  credentials, LAN explanation and lifetime. Starting/running states expose
  explicit stop; busy state disables duplicate operations. Route disposal only
  cancels polling. Share import/clear remain disabled while active, and clear
  retains confirmation and now has distinct error-color styling.
- The new tests assert real MethodChannel payloads and clipboard/export values,
  retained hidden fields, picker cancellation and file dispatch blocking,
  masking with quote/backslash/newline characters, nested IP fields, unknown
  fallbacks, HFTP action/disposal behavior, and narrow/landscape/2× text layouts.
  Missed taps are fatal, and layout cases use reduced animation settings. The
  frontend workbench spec supplied by main matches these implementation paths.

## Verification

- Reviewer ran `./hako bash -lc 'cd android && ./gradlew :app:lintRelease --console=plain'`:
  exit 0, `BUILD SUCCESSFUL in 27s`; **0 errors, 5 warnings**. Reports:
  `build/app/reports/lint-results-release.txt` and `.xml`. Warnings are the
  existing package-visibility, Gradle/dependency-version, ChromeOS ABI and
  backup-rules items; no native/config changes were made by this task.
- Flutter lint/type analysis: **pass**, using implementer's final
  `./hako flutter analyze` evidence. Reviewer inspected
  `${EVIDENCE_DIR}/ctos-workbench-analyze.log`: `No issues found!`. No later source edits
  were made during review, so the command was not repeated.
- Tests: **pass, 43/43**, using implementer's final
  `./hako flutter test --reporter expanded` evidence. Reviewer inspected
  `${EVIDENCE_DIR}/ctos-workbench-full-tests.log` and the assertions in the changed tests.
  No fix or new unresolved concern required repeating the suite.
- Reviewer ran `git diff --check`: exit 0. Review included tracked diffs and
  new `lib/workbench/result_card.dart` / `test/workbench_usability_test.dart`.
- No device operation, Python/network tool execution, APK installation,
  `hako current`, staging/commit/archive or protected configuration change was
  performed by this reviewer.

## Findings (not fixed) / acceptance gaps

No unresolved code defect. Remaining acceptance belongs to main/user:

- Build and verify the exact new current APK before device installation.
- On currently authorized TARGET-PHONE, after task-specific install authorization,
  inspect tools-first list and all entries; password collapsed/expanded form,
  masking/direct copy/raw details; encoder text/file switch with keyboard and
  real SAF cancellation/export; landscape/large text and TalkBack focus/labels.
- Any real HFTP service start/background/notification/stop or shared-library
  mutation needs current task authorization and disposable fixtures. Mocked
  tests establish UI dispatch/lifetime intent, not real Android service paths.
- Other ROMs, Android 11 and 16KiB-page environments are not inferred as passing.

The main session should keep the task unarchived and request targeted feedback
before a usability commit while the required device/product checks remain open.

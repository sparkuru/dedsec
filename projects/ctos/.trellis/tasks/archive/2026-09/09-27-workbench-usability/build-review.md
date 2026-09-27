# Build and submit-ready review — 2026-09-27

Current update: user replied “允许”; exact98f44069… APK installed to confirmed
TARGET-PHONE and targeted UI/keyboard/SAF/source-hash acceptance passes. See
[device-check.md](device-check.md). The installation request/gaps below describe
the earlier build-review phase. Usability diff stays uncommitted/unarchived
with actual screenshots ready for product feedback.

## Artifact

`./hako current` exit 0: release ARM64 APK, apksigner verification, published
copy comparison and SHA256SUMS verification passed. Artifact:
`dist/ctos-current-arm64.apk`, 26,107,591 bytes,
SHA-256 `98f44069c9fc6da80d082dd591b161cb9c1d43b34e88d522879af75e6148bab7`.
All 13 APK Python sources match the current source bytes. Baseline APK retained
temporarily at `${EVIDENCE_DIR}/ctos-workbench-baseline-f928af66.apk` (f928af66…).

## Local visual review

Main generated and inspected six 375×812dp / DPR3 component previews via
`./hako flutter test build/workbench-usability-previews/preview_test.dart --reporter expanded`:
1/1 pass. Files in ignored `build/workbench-usability-previews/`:
`list.png`, `password-form.png`, `encoder-form.png`, `password-result.png`,
`hash-result.png`, `hftp-stopped.png`. The fixtures use actual catalogue
metadata and public fake result data, project theme, mock native methods,
and explicit CJK/Roboto/icon/monospace fonts. The first hash preview had
test-runner missing glyphs; loading a real monospace font fixed the fixture,
then previews were rerun. No app-source defect or code change was needed.

Tools fit in the first screen; basic password fields and advanced action are
clear; encoder source/direction are readable; HFTP state and primary action
precede configuration/library management. Password is masked and direct copy
visible; the full digest wraps and copy is reachable. No clipped controls or
text overflow found. Route fixtures lack real navigation history/status bars;
screens are not device evidence or actual tool/service execution.

## Review disposition and commit plan

`human-required`. Local code review, analyze, 43 tests, lint (0 errors/5
existing warnings), current/signature/checksum and diff checks pass. See
`implementation-check.md` and `check.md`. No unresolved source defect.
Design/index/changelog/verification and frontend executable contracts synced.

Do not commit/archive this usability task until targeted visual/device
acceptance or an explicit user waiver. Earlier requested progress commit was
already completed as 6556918 with journal e7fce36; this is a new uncommitted
usability diff, entirely scoped to ctOS. No upload/push was performed.

Request approval to cover-install this exact APK on TARGET-PHONE after rechecking
its current serial/identity, and inspect:

- Tools-first list/all entries, password basic/advanced/numerical keyboard,
  default masking/raw details/direct copy using disposable public fixtures.
- Encoder text/file draft switching, real SAF import/cancellation and output
  export/readback using only new disposable files; hash summary/copy.
- Portrait/landscape readability, keyboard scrolling and accessible labels.
- HFTP stopped layout without service start or shared-library cleanup unless
  the user also authorizes those actions; existing native/service tests are
  historical, mocked current tests verify UI dispatch only.

Expected feedback: pass/fail for these scenarios or the specific screen/control
that needs adjustment. New device installation has not occurred. No Root grant,
notification grant, reboot, real IP lookup, HFTP start or share cleanup was done.
TalkBack interaction may require a separate authorized manual device check;
widget layout success does not establish it. Android11/other ROM/16KiB-page
behavior remains outside this validation.

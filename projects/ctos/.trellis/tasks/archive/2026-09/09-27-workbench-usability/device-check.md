# TARGET-PHONE targeted acceptance — 2026-09-27

The command below is a sanitized replay template. Set `PHONE_ADB_SERIAL` to the currently authorized, independently confirmed phone serial before use; it does not retain the historical endpoint.

## Authorization and installed artifact

User replied “允许” to the concrete APK cover-install plus UI, keyboard and
temporary SAF test request. This continues the existing task. Target confirmed
again: TARGET-PHONE / Android16, `PHONE-ADB-SERIAL`, 1272×2800 screen.
Every device command explicitly used that serial.

`adb -s "$PHONE_ADB_SERIAL" install -r dist/ctos-current-arm64.apk`: Success.
Installed base.apk SHA256 matches the local authorized artifact exactly:
`98f44069c9fc6da80d082dd591b161cb9c1d43b34e88d522879af75e6148bab7`.
Data retained; existing automatic Root session restored normally, no manual
grant or Root configuration change. No source fix/rebuild was needed.

## Actual results

| Scenario | Result |
| --- | --- |
| Workbench | Five tools fully visible on first screen; other four scripts and file manager reachable below |
| Password | Chinese basic/advanced forms, numeric length keyboard, expand/collapse and left-aligned file area pass |
| Hidden salt submission | Public seed `ctos-ui-public-20260927`, salt `public-salt`, length16 match host `generate` after advanced collapse; completed/exit0 |
| Mask/reveal/copy | Primary and expanded raw JSON masked by default; explicit reveal works; direct copy then native long-press Paste into encoder matches the16-character password, no JSON wrapper |
| Source/drafts | Explicit text/file choice retains text and selected filename/28B; file-required error blocks dispatch |
| SAF cancellation | Actual system picker return leaves empty selection; later valid selection displays no error |
| Conversion/export | Public20B `ctos-ui-saf-20260927` converts to28B Base64; preview correct; unique output exported via ACTION_CREATE_DOCUMENT, pulled and compared byte-for-byte: exact28B |
| Import/file hash | Only that new output selected via ACTION_OPEN_DOCUMENT; file SHA256 matches encoded28B |
| Retained token/text hash | Switch back to retained20B text without clearing file draft; SHA256 matches original text, proving inactive token does not override it |
| Hash UI | Direction hidden; individual hashes and copy controls render correctly |
| Portrait/landscape | Portrait forms/results and landscape encoder form readable, no clipping observed; keyboard scrolling reachable; temporary landscape restored original rotation lock0 |
| Crypto/HFTP | Chinese crypto empty form and HFTP stopped-state/status/start/configuration/library layout checked; no crypto operation or service start |

Main used explicit-serial am start/input/uiautomator/screencap/pull/rm/wm.
UI hierarchy provided actual control bounds, exact paste value and SHA256s.
Some coordinate/IME timing attempts missed a control; stable hierarchy bounds
corrected the test driver. First readback assumed Download root; provider had
retained a subdirectory. Located only the unique filename, then pulled and
verified the actual output. No existing file was overwritten. An initial
broad filename lookup emitted permission-denied paths in Android/data; no
protected contents were read or elevated, subsequent lookup scoped to Download.
Unrelated-path diagnostics are not retained as project evidence.

## Temporary evidence and cleanup

Actual device screenshots are `${EVIDENCE_DIR}/ctos-usability-list-20260927.png`,
`list-secondary-20260927.png` with the same prefix;
`password-{form,keyboard,advanced,result,raw-masked,paste}-20260927.png`;
`encoder-{form,file-required,picker-cancel,text-restored,result,file-selected}-20260927.png`;
`{file-hash,text-hash,landscape,hftp-stopped,crypto-form}-20260927.png`.
All use the `${EVIDENCE_DIR}/ctos-usability-` prefix. Public readback evidence:
`${EVIDENCE_DIR}/ctos-usability-saf-export-20260927.txt`.

External unique `ctos-usability-20260927-98f44069-base64.txt` and device/host
UI dump removed exactly. Removed picker/menu screenshots showing unrelated
document names; returned phone to workbench and confirmed `wm user-rotation`
is original `lock 0`. App-private28B output and28B imported copy remain in
managed tool temporary storage: no whole-store clear or Root private-file
cleanup. Existing documents, App data and HFTP share library preserved.

Targeted acceptance passes. No real IP query, HFTP start, notification grant,
Root grant, reboot or unrelated file/configuration change. Full TalkBack,
foreground-service/notification regression, Android11/otherROM/16KiB pages
not rechecked. Large-font/reduced-motion remain widget evidence. Prior43/43,
analyze and lint apply to this unchanged source/build. Actual screenshots are
ready for product feedback; usability diff remains uncommitted/unarchived.

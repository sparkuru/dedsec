# ctOS Validation Profile

脱敏重放约定：执行命令前设置 `PROJECT_ROOT` 为项目绝对路径、`EVIDENCE_DIR` 为独立临时证据目录，并重建所列历史夹具；原始机器路径不保留。`PHONE_ADB_SERIAL` / `BOARD_ADB_SERIAL` 须设置为当前授权并确认的目标，`TARGET-PHONE`、`TARGET-BOARD` 与 PHONE / WINDOWS / TEST-CLIENT 地址占位符仅表示设备和 LAN 角色；具体标识有意省略，不是当前探测出的连接配置，不能直接用于访问。完整约定见 [验证记录](../../../design/verification.md)。

- ownership: project-shared
- source: project-authored

## Checks by changed surface

| Surface | Focused check | Wider check when relevant |
| --- | --- | --- |
| Workflow or documentation only | Check links, command spelling, file ownership, and `git diff --check` | No app build or device action implied |
| Flutter or Dart | `./hako flutter analyze` and `./hako flutter test` | `./hako current` for an authorized APK release change |
| Android Java, JNI, Gradle, or manifest | Relevant Flutter checks and `./hako bash -lc 'cd android && ./gradlew :app:lintRelease --console=plain'` | Build/test APK and scoped instrumentation when behavior requires it |
| Native UI | Focused Flutter widget tests for changed screens and states | Manual or instrumented Android check for behavior tests cannot establish |

The source of these commands is `hako`, `README.md`, `pubspec.yaml`, `test/`,
and `design/verification.md`. `./hako current` replaces the ignored current
APK and its checksum; run it only when that output is in task scope. Record
the exact command, date, result, artifact, device environment, and gaps in
the task check evidence and `design/verification.md` when product validation
changes. Prior device results are historical evidence, not a current pass.

For device checks, use the currently authorized target only. Confirm its
identity and pass its serial with every `adb -s` command. Installing an APK,
changing Root or Vector state, and rebooting need authorization within that
task; earlier access does not grant it. Android 11 board and Android 16
phone conclusions remain separate. Native UI visual quality, permissions,
Root or Vector behavior, and hardware-dependent paths may require targeted
human review after automated checks.

The Flutter application remains native, without a Flutter-web build or a
repository Playwright dependency. HFTP now exposes a small browser interface;
use the focused service profile below. Do not apply it to native Flutter UI.

## HFTP browser validation profile

- Mode: development service smoke test, loopback only. The confirmed source
  is `android/app/src/main/python/ctos_tools/hftp.py`; the temporary runner
  creates FileServer with an ephemeral port and a fresh ${EVIDENCE_DIR} share directory.
- Runner: isolated uv environment + Playwright 1.55.0 in
  `${EVIDENCE_DIR}/ctos-hftp-browser-20260927`, using `/opt/google/chrome/chrome`.
  `PYTHONPATH=${PROJECT_ROOT}/android/app/src/main/python PYTHONDONTWRITEBYTECODE=1
  uv run --cache-dir ${EVIDENCE_DIR}/ctos-feedback-uv-cache --offline --no-sync
  browser_check.py` from that directory. The task check
  record identifies the exact runner, hash and screenshot. Recreate temporary
  fixtures before replay if ${EVIDENCE_DIR} has been removed; this is not a persistent
  project CI suite or a production-server test.
- Use a disposable no-login App browser context; block requests outside the
  loopback origin. Verify the configured limit is displayed, upload, encoded
  directory names, nested upload, download, no overwrite, CSP-compatible
  execution, and 375 px layout. Keep optional authenticated CLI compatibility
  in separate Python regressions, including 401 without credentials. Never use
  an existing user directory or expose a real phone library for this smoke test.
- The runner shuts down its server and browser. Android foreground service,
  notification permission and background/stop behavior require separately
  authorized device validation; browser success does not establish those.

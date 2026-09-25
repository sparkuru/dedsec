# ctOS Validation Profile

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

There is currently no browser application, Playwright configuration, or
browser test command. Do not invent a Playwright Validation Profile. If a
future task adds a browser-accessible UI, classify its automation mode, build
one repository-confirmed Playwright profile in this spec layer, and run the
focused browser check before requesting residual human review.

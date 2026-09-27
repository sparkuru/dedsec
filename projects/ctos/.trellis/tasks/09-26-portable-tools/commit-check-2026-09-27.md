# Progress commit check — 2026-09-27

Review: **human-optional** for the user-requested current-progress commit. The present checks pass and no blocking source defect was found in the reviewed paths. The existing task evidence records the specific PLR110 acceptance against APK `f928af6611f014206597321b2f35a094aef53b11eeb404ea7bbf72e23ee8a73b`; that evidence remains historical. This session verifies the worktree for a progress snapshot and does not claim a new device acceptance or expand the supported-device matrix. Additional native visual feedback is optional within that scope.

## Scope and contract review

- Loaded `check.jsonl`, `implement.jsonl`, `prd.md`, `design.md`, `implement.md`, the design landing/plan/constraints, backend Python workbench, frontend workbench/terminal/connection contracts, and the Trellis Plus validation/review policy.
- Reviewed current tracked changes and new runtime/UI sources for Root-only removal, Python package preparation and PTY adaptation, SDK v2, five Portable tools, file capabilities, and HFTP ownership. Root-only native/Dart/manifest/resource removals agree; App/Root PTY keeps its separate permission boundary.
- Catalogue and execution use `ctos_scripts.registry()`; Dart reads parameter metadata and submits strings. Unicode scalar length validation agrees with Python. SDK revalidates choices, required fields and opaque input tokens. UI secrets and sensitive generated output begin masked.
- File flow is explicit SAF import → App-private input token → bounded core/adapters → unique private output token → explicit SAF export. Authenticated crypto completes before artifact publication; `commit_exclusive` preserves existing entries. Legacy CBC is explicitly labelled unauthenticated.
- `PythonRunner` claims a single task, uses managed `-P -S` imports, bounds protocol/log collection, matches cancellation IDs and releases its claim in `finally`. Activity background/disposal cancels short tasks; HFTP belongs to its separate App foreground service.
- HFTP starts manually, defaults to loopback, checks notification availability, exposes explicit stop and finite service lifetime, uses `START_NOT_STICKY`, and does not stop on route disposal. Browser requests require authentication; upload/path/size/concurrency/quota checks and exclusive commits are present. LAN is explicitly selected and its HTTP limitation is displayed.
- Existing specs and design contracts reflect these paths. No new convention or implementation correction required a spec change during this check.

## Commands actually run

| Command | Current result |
| --- | --- |
| `./hako flutter analyze` | Pass; `No issues found!` |
| `./hako flutter test` | Pass; 31 tests, `All tests passed!` |
| `./hako bash -lc 'cd android && ./gradlew :app:lintRelease --console=plain'` | Pass; `BUILD SUCCESSFUL in 10s`, 48 tasks, 0 errors / 5 warnings |
| `bash -n hako-env.sh` | Pass |
| `shellcheck hako-env.sh` | Pass; tool available, no diagnostics |
| `shfmt -d hako-env.sh` | Pass; tool available, no diff |
| `git diff --check` | Pass |
| Python `ast.parse` across `android/app/src/main/python/**/*.py` | Pass; 13 source files, no bytecode written |

The first default-sandbox analyze attempt returned exit 126 because Docker socket access was denied. The scoped `./hako` escalation was then allowed; analyze, tests and lint completed successfully. Normal ignored build/cache updates were produced by these commands. `./hako current` was not run and the current APK was not replaced.

Android lint report: `build/app/reports/lint-results-release.txt`. Its five warnings are the already documented `QueryPermissionsNeeded`, `AndroidGradlePluginVersion`, `GradleDependency`, `ChromeOsAbiSupport`, and `DataExtractionRules`. Gradle also reports deprecated features relevant to a future Gradle 9 upgrade; no lint/type failure occurred.

## Findings and boundaries

No blocking issues were found or fixed. This reviewer changed only this evidence file and performed no staging, commit, APK installation, notification grant, Root authorization, service startup or device test.

Python integration/CLI/browser runs, packaged ARM64/QEMU checks, SAF, service notification/background/stop and App/Root PTY acceptance remain the dated evidence in `check.md`; they were not rerun here. Android 11, native 16 KiB pages, API 28/29 syscall fallback, fresh-install notification rejection and real-device offline behavior remain unverified as documented. The progress commit must retain those limits and must not mark the wider product plan or cross-device acceptance complete.

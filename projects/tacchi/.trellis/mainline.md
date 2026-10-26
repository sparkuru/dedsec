# Trellis Mainline

## Initiative

- title: tacchi foundation and offline Android payment assistance
- parent task: `.trellis/tasks/archive/2026-10/00-bootstrap-guidelines/` for initialization only
- current approved objective: native P0 size/scope/package feedback is locally addressed; complete native UI/host/hardware acceptance before closure
- owner decision: 2026-10-25 user requested an initialization task, confirmed Flutter + Kotlin and UUPM, then approved the initialization plan with “开始”

## Requirements and sources

The user-selected [design baseline](../archive/design.md), dated 2026-10-24, defines product direction, not completed implementation. Sections 1–3 establish one offline APK, Vector/Xposed integration, independent rewriting and non-goals. Sections 5–11 establish process, credential, authentication, cancellation and privacy boundaries. Section 14 defines acceptance; section 15 retains unresolved device/product choices.

The original 2026-10-25 Flutter + Kotlin choice was explicitly superseded on 2026-10-26: the user selected Kotlin native platform Views to prioritize a small APK after reviewing BBZQ's approach. Kotlin now owns configuration presentation and Android/IPC/Hook responsibilities. No WebView/JavaScript bridge, Flask service or movement of secrets into the manager process is authorized.

| Requirements | Preserved outcome | Status |
| --- | --- | --- |
| F01–F02 | Distinguish actual host loading/freshness from configuration; assistance defaults off | Baseline, not implemented |
| F03 | Enroll/replace inside host; cancellation preserves prior credential | Baseline, not implemented |
| F04–F06 | Verified app/version/scenario only; current-session authentication; safe cancellation/manual input | Baseline, not implemented |
| F07 | Disable/revoke and distinguish deletion request from host-confirmed cleanup | Baseline, not implemented |
| F08 | Local structured diagnostics without secrets, account data or page text | Baseline, not implemented |

No Internet permission or module-originated network behavior, analytics, remote code, software-key fallback, password export, direct Zygisk backend, root daemon, accessibility service or unverified compatibility claims. One target app/version/scenario and current Android user are the MVP boundary. Hardware/IPC feasibility remains a P0 proof obligation.

Reference evidence: `archive/refer-repo/`, FingerprintPay `4fe083e077cecb625bad7558246209951496b79d` (7.0.1/build 63). Reference support and security properties are not inherited. No reference code is imported into tacchi by initialization.

## Continuation

- mode: guided
- serial authorization: none
- next pulse: after archive or a project-related no-task request
- The main session owns scope, task transitions, commits and archive; workers operate only on assigned task files.

## Ordered work

| Order | Task or proposed phase | State | Readiness/dependencies |
| --- | --- | --- | --- |
| 1 | `.trellis/tasks/archive/2026-10/10-25-trellis-plus-init/` | complete | Work commit `b0133ec`; child archive `0400e02`; parent archive `0095533` |
| 2 | `.trellis/tasks/10-25-p0-capability-prototype/` | in progress | Kotlin native Views;92,229-byte im.majo.tacchi APK; API101.0.1/service101.0.0;29 JVM tests and strict build pass. Actual native UI/host/hardware/Binder acceptance remains open |
| 3 | P1 configuration/loading | proposed | Kotlin platform Views/applicationId/minSdk/toolchain selected; production signing and P0 device evidence needed |
| 4 | P2 credential lifecycle | proposed | P0 host authentication/identity proof and P1 control contracts |
| 5 | P3 one-app payment flow | proposed | P2 and exact target scenario evidence |
| 6 | P4 additional compatibility | proposed | Per-combination device evidence; no blanket KSU or clone support claim |

The phases are imported from the design, not authorization to create/execute a backlog. Before any product work, resolve its dependencies and use normal task planning.

## Evidence and decisions

- At discovery Trellis 0.6.17 had no tacchi app manifest, build system or Git commits. Initialization is now committed; unrelated user-owned initial files remain untracked.
- UUPM Codex initialization, search help, Flutter data search and design-system generation succeeded. No UI implementation/device validation has run.
- Independent check: eight complete documents in each implement/check context, actual native worker injection, 87 unchanged protected files, exact available notice copies and local links verified. Final checklist: 23 PASS, 1 GAP, 1 UNKNOWN, 9 N/A; task `check-results.md` distinguishes remaining provenance/update/closure evidence.
- User authorized the presented commit/archive plan with “归档” on 2026-10-25. Child archive commit `0400e02` and parent archive commit `0095533` verified, both with completed status/date and one Codex trailer each. Active task list is empty; initialization closure is complete.
- Exact UUPM notice/source provenance is unresolved; see `third_party/index.md`. Generated assets remain local and raw research is not distributed.
- Next permitted action: record the user's requested P0 work commit, then continue explicitly authorized device acceptance or concrete review feedback. Do not recreate initialization or re-request settled UI/API decisions. Real device installation/archive have not been performed; no P1/payment work is implied.

- 2026-10-25 correction: user explicitly selected libxposed API 101+ and rejected device-version collection as a prerequisite to development. Target the API 101 minimum contract, pin actual dependencies, and defer device-specific compatibility proof without claiming it passes.

- API research source correction: use Vector-maintained API documentation and its pinned module/service API revisions as the framework integration basis; generic libxposed latest docs are supplementary.

- P0 local implementation: fixed Docker toolchain, Flutter diagnostics, Kotlin module/framework service integration, controlled fixture, mutually authenticated status IPC and hardware-authentication probe. Strict checksum build, analyzer, 22 Flutter and 18 JVM tests, lint and independent read-only review passed. Device-only acceptance remains unverified; see the active task's check-results.md and device-validation.md. No claim of payment support or real hardware success.

- 2026-10-26 installed-build feedback: user reports installation succeeded, requests APK-size solutions (reference `https://github.com/HSSkyBoy/BBZQ.git`), reports no useful Vector scope selection, and selects main identity `im.majo.tacchi`. Existing APK48,345,323 bytes bundles three Flutter architectures with native/app libraries totaling41,595,892 bytes. Source-backed comparison is in active task research/size-scope-feedback.md. The user subsequently explicitly selected Kotlin platform Views. Implement the native UI migration, package/IPC/entry rename, editable scope metadata and compatible release shrinking; preserve runtime host whitelist. Device installation success is user-reported and does not prove host injection or authentication.

- Feedback implementation complete locally: main APK92,229 bytes (99.8092% smaller), no Flutter/native-engine libraries, identity im.majo.tacchi, staticScope=false with fixture-only runtime guard.29 JVM tests and strict checksum build pass; independent check found no implementation blocker. Actual native UI/Vector/Binder/biometric tests remain unexecuted (readonly adb listing found no connected devices). Disable the old module before testing the new matched pair. Current commands/artifact hashes are in active task feedback-results.md. No commit/archive/install performed by the agent.

- Work-commit authorization2026-10-26: user requested “提交一版” after reviewing the native implementation and remaining device-validation limits. Commit the application/build/test/tooling, applicable exact notices, project-owned specs and task evidence as one coherent P0 snapshot. Preserve unrelated untracked Trellis/platform/reference files; retain task in progress and do not archive or push.

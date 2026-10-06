# 提交与收尾计划

计划日期：2026-10-05。2026-10-06 用户回复“观感通过，按计划提交”，批准本计划和最终观感；所有候选均为本轮已识别改动，没有未识别 dirty 文件。

## 提交顺序

1. `feat(ctos): polish Flutter experience across all screens`
   - `.trellis/mainline.md`
   - `.trellis/spec/frontend/component-guidelines.md`
   - `.trellis/spec/frontend/directory-structure.md`
   - `.trellis/spec/frontend/quality-guidelines.md`
   - `.trellis/spec/frontend/workbench.md`
   - `design/README.md`
   - `design/architecture.md`
   - `design/changelog.md`
   - `design/flutter-experience.md`
   - `design/verification.md`
   - `lib/main.dart`
   - `lib/device_page.dart`
   - `lib/export_preview.dart`
   - `lib/terminal_interaction.dart`
   - `lib/ui/ctos_theme.dart`
   - `lib/ui/ctos_components.dart`
   - `lib/workbench.dart`
   - `lib/workbench/controls.dart`
   - `lib/workbench/file_store_card.dart`
   - `lib/workbench/hftp_page.dart`
   - `lib/workbench/models.dart`
   - `lib/workbench/parameter_field.dart`
   - `lib/workbench/result_card.dart`
   - `lib/workbench/script_page.dart`
   - `lib/workbench/task_history_page.dart`
   - `test/hftp_feedback_test.dart`
   - `test/hftp_log_test.dart`
   - `test/portable_tools_test.dart`
   - `test/state_ui_test.dart`
   - `test/terminal_interaction_test.dart`
   - `test/workbench_usability_test.dart`
   - `test/secondary_layout_test.dart`
   - `test/theme_layout_test.dart`
   - `test/workbench_layout_test.dart`
2. Trellis `task.py archive flutter-experience-polish` 的任务归档提交：仅本任务 `task.json`、PRD、design、implement、check、jsonl、此计划；不归档其他任务。
3. Trellis `add_session.py` 的开发者 journal 提交，引用第 1 步工作提交。

批准后第 1 步先把文档中的 task 链接改为本次归档路径，并更新本轮状态；第 2 步完成后核对链接和工作树。不会提交忽略的 APK、build 夹具/图片、设备原始资料或 agent 配置；不会 push。

## 工作提交说明

Unify the semantic theme, typography and all Flutter screens; preserve drafts,
PTY sessions and host/service contracts across responsive navigation. Improve
result hierarchy, readable times, destructive confirmations and save/copy
feedback. Keep normal motion brief and honor reduced animations.

Validation: analyze clean;103 Flutter tests;107 render scenarios;arm64 release
and matching installed APK hash;targeted Android15 UI/IME/PTY/SAF checks.
HFTP service/Root/other ROM acceptance remains outside this UI-device pass.

Co-authored-by: OpenAI Codex <codex@openai.com>

## 提交前人工审阅

分类：`human-required`。实现、功能、自动化、代理视觉复验均已完成，最终品牌观感需要用户确认接受。只需查看已安装新版，或公开组件图 `build/flutter-polish-evidence/phone-overview.png`、`phone-workbench.png`、`wide-overview.png`、`large-text-workbench.png`、`landscape-large-files-clear.png`，确认布局、密度和色彩可接受；若不接受，指出具体页/问题继续修正。此审阅不要求重复设备操作或其他 ROM 测试。

结果：2026-10-06 用户明确确认观感通过，人工门槛已满足；以下依据说明当时为什么需要该确认，不构成新的批准请求。

依据：`.trellis/workflow.md` Phase3.4 要求一次展示提交计划并确认；`.trellis/spec/trellis-plus/index.md` 要求视觉/产品判断提交前取得定向反馈。实现授权不替代这次提交及观感确认。

# 提交与产品审阅方案

工作提交已完成：`d0aeb019ce6bc4c0a547e8534e0f1bbc631b0092`。本任务按用户收敛后的保留范围归档，熄屏断连继续是已知限制；Root 电源扩展未实现、未触发。

2026-09-28：用户明确要求“提交”，随后要求本次提交脱敏并调用 generalize-content。按以下精确清单脱敏、复核后执行工作提交，再归档本任务并记录 journal。接受范围为现有停止修复及实测结果；熄屏失败保留为已知限制，Root 电源扩展继续暂缓。不推定用户另行完成了所有视觉或辅助功能场景。

拟一个工作提交：`feat: improve workbench and isolate HFTP root relay`。
范围为本任务R1–R25的已实现部分：工作台入口/表单/结果、图片反馈、HFTP目录/限额/日志、Root能力声明与网络adapter，以及停止边界/连续重启/日志状态回归、测试及对应design/spec。所有候选均为本轮agent修改，没有未识别或用户手工改动；不包含ignored APK、/tmp证据或平台配置。最终回归及范围收敛已完成，熄屏有效保活保留为已知限制。

## 提交说明

Improve workbench forms and results, local-directory HFTP configuration and
bounded live logs. Isolate optional Root LAN transport behind explicit host
capability declarations and a scoped adapter while keeping Python/SAF App-owned.

Validation: Flutter73/analyze, unchanged Python25, Androidlint/build,
24 native transfer/restart cycles and9control checks; final10targeted TARGET-PHONE
checks passed9.077s. Three awake LAN cycles including31MiB passed exact
PUT/GET, App Stop, owned process/port/CPU cleanup and same-port restart.
Screen-off still fails despite App wake-lock ownership; independent Root
power capability was deferred by the user and is not implemented.
Physical non-Root phones, other ROMs, Wi-Fi loss and5h remain untested.

Co-authored-by: OpenAI Codex <codex@openai.com>

## 审阅

分类：`human-required`。原因：工作台/HFTP布局与文案包含产品视觉判断；Windows功能确认已通过，手机定向界面检查亦已记录，不替代未执行的全部人工场景。用户在结果与已知限制说明后明确要求提交；本轮人类提交决定已取得，待提交内容仍须脱敏复核。

手动场景：查看已安装新版的工作台、任一表单/结果和HFTP页，确认必要信息、主操作、Root权限说明与可折叠日志符合预期，没有demo式介绍。Root运行截图 `${EVIDENCE_DIR}/ctos-feedback-hftp-root-running-20260927.png`；Root停止与默认配置截图见root-device-check.md。当前9ca71e…已安装，停止回归通过、熄屏未通过；服务当前停止，测试目录已删除；实际使用需选择自己的目录，开启所需Root中继后启动。

本轮已获用户明确提交授权；以下旧审阅待办描述记录此前阶段，当前执行以顶部授权与已收敛范围为准。按清单提交，不push；再按Trellis规则单独归档本任务、记录journal。其他active task不自动归档。

## 精确候选文件

提交前脱敏与独立复核已通过：72 个精确候选文件内的设备、LAN/ADB、动态 UID/PID、用户/本机路径和证据目录标识已泛化，未发现真实凭据。生产逻辑与已验证 APK 未因脱敏改变；两处测试地址改为 TEST-NET，定向日志测试 17/17、analyze、AndroidTest Java 编译、JSON/JSONL、相对链接及 diff 检查通过。实机结果仍是此前对应产物的实际记录，本轮没有设备操作；范围外旧提交历史未改写。复核见 [提交前检查](restart-check.md)。

- `.trellis/spec/backend/index.md`
- `.trellis/spec/backend/python-workbench.md`
- `.trellis/spec/frontend/index.md`
- `.trellis/spec/frontend/workbench.md`
- `.trellis/spec/trellis-plus/validation.md`
- `.trellis/tasks/09-27-workbench-usability/build-review.md`
- `.trellis/tasks/09-27-workbench-usability/check.jsonl`
- `.trellis/tasks/09-27-workbench-usability/check.md`
- `.trellis/tasks/09-27-workbench-usability/commit-plan.md`
- `.trellis/tasks/09-27-workbench-usability/design.md`
- `.trellis/tasks/09-27-workbench-usability/device-check.md`
- `.trellis/tasks/09-27-workbench-usability/feedback-check.md`
- `.trellis/tasks/09-27-workbench-usability/feedback-device-check.md`
- `.trellis/tasks/09-27-workbench-usability/feedback-ui-check.md`
- `.trellis/tasks/09-27-workbench-usability/implement.jsonl`
- `.trellis/tasks/09-27-workbench-usability/implement.md`
- `.trellis/tasks/09-27-workbench-usability/implementation-check.md`
- `.trellis/tasks/09-27-workbench-usability/log-backend-check.md`
- `.trellis/tasks/09-27-workbench-usability/log-ui-check.md`
- `.trellis/tasks/09-27-workbench-usability/prd.md`
- `.trellis/tasks/09-27-workbench-usability/research/hftp-saf.md`
- `.trellis/tasks/09-27-workbench-usability/research/vpn-lockdown.md`
- `.trellis/tasks/09-27-workbench-usability/root-device-check.md`
- `.trellis/tasks/09-27-workbench-usability/root-log-check.md`
- `.trellis/tasks/09-27-workbench-usability/root-native-check.md`
- `.trellis/tasks/09-27-workbench-usability/restart-analysis.md`
- `.trellis/tasks/09-27-workbench-usability/restart-check.md`
- `.trellis/tasks/09-27-workbench-usability/restart-device-check.md`
- `.trellis/tasks/09-27-workbench-usability/restart-native-check.md`
- `.trellis/tasks/09-27-workbench-usability/transfer-check.md`
- `.trellis/tasks/09-27-workbench-usability/transfer-review.md`
- `.trellis/tasks/09-27-workbench-usability/task.json`
- `README.md`
- `android/app/build.gradle`
- `android/app/src/androidTest/java/im/majo/ctos/PortableToolsTest.java`
- `android/app/src/main/cpp/hftp_relay.c`
- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/main/java/im/majo/ctos/HftpBridge.java`
- `android/app/src/main/java/im/majo/ctos/HftpConfig.java`
- `android/app/src/main/java/im/majo/ctos/HftpDocuments.java`
- `android/app/src/main/java/im/majo/ctos/HftpLogs.java`
- `android/app/src/main/java/im/majo/ctos/HftpService.java`
- `android/app/src/main/java/im/majo/ctos/HftpTreeBroker.java`
- `android/app/src/main/java/im/majo/ctos/MainActivity.java`
- `android/app/src/main/java/im/majo/ctos/RootOperationAdapter.java`
- `android/app/src/main/java/im/majo/ctos/ToolExecutionContext.java`
- `android/app/src/main/java/im/majo/ctos/ToolFiles.java`
- `android/app/src/main/python/ctos_tools/hftp.py`
- `android/app/src/main/python/ctos_tools/hftp_storage.py`
- `design/README.md`
- `design/architecture.md`
- `design/changelog.md`
- `design/constraints.md`
- `design/opportunities.md`
- `design/plan.md`
- `design/portable-tools.md`
- `design/verification.md`
- `lib/workbench.dart`
- `lib/workbench/api.dart`
- `lib/workbench/controls.dart`
- `lib/workbench/file_store_card.dart`
- `lib/workbench/hftp_page.dart`
- `lib/workbench/models.dart`
- `lib/workbench/parameter_field.dart`
- `lib/workbench/result_card.dart`
- `lib/workbench/script_page.dart`
- `test/hftp_feedback_test.dart`
- `test/hftp_log_test.dart`
- `test/portable_tools_test.dart`
- `test/workbench_feedback_test.dart`
- `test/workbench_test.dart`
- `test/workbench_usability_test.dart`

## 未识别路径

无。

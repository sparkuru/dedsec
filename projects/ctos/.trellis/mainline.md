# ctOS Mainline

## 2026-10-11 主页双返回提交与收尾

- **Authorization:** 用户在下方提示交付后回复“可以提交当前 task”；当前task提交/归档已获授权，不重新请求确认。
- **Result:** 四入口根路由2秒双返回，提示位于下方并自动避让导航/安全区/Snackbar；16定向/154全量、analyze/格式及981ef814 arm64 APK/v2验签通过，用户验收与提交批准单独记录。
- **Scope:** 只提交本任务代码/规范、共享文档内本任务段落及本任务归档/日志。公共秘密库实现、设计/验证与其他任务改动保留未提交，继续暂停。
- **Next:** 功能提交a46a296与本任务归档完成，当前指针已清除；公共底座继续暂停，不push或安装设备。

## 2026-10-11 用户实机反馈与下方提示调整

- **Authorization:** 用户回复“实机验收过；但是能否把 prompt 出现在下方？”。保留为先前交付的用户验收报告，未提供设备/API/安装哈希，不冒称 agent 实测。
- **Current:** 当前任务只将提示改至下方，利用 Scaffold公共布局自动避让导航/安全区/普通Snackbar。返回/2秒/状态逻辑保留；公共秘密库继续暂停。
- **Evidence:** 独立检查最终16定向/154全量、analyze/格式通过；底部几何/Snackbar两顺序与无按钮点击语义通过。新arm64/v2验签通过，APK `981ef8145c01f1ce037f6ee5d80bd5672fe28e46fea591ac3d762fd48f9ef02a`，完整边界见design/verification.md及task check，不沿用旧7ad包证明新布局。
- **Next:** 新版APK供用户确认下方提示位置/同屏反馈观感；保留in_progress / awaiting_position_feedback，不提交/归档或恢复公共底座。

## 2026-10-11 暂停公共底座，切换主页双返回

- **Authorization:** 用户要求“暂停另一个 task；然后开始当前 task”，随后选择“四个主入口，2 秒（推荐）”。本轮本地实现/检查/构建；没有当轮设备安装与操作授权。
- **Paused initiative:** `10-08-app-authentication` 暂停推进；保留 `in_progress` 与所有已有成果/未完成关卡，在 metadata 记录 execution_state=paused。TOTP/SSH仍未启动，不把暂停写成完成。
- **Current initiative:** `10-10-home-double-back-exit`；四入口根路由首次返回提示，2 秒内再次返回退出到桌面。正常路由/弹窗/键盘返回不计数，切换/覆盖/后台重置。
- **Evidence:** Trellis implement/check完成；退出提示被浮动Snackbar遮挡的布局红灯经修复通过，14定向/152全量、analyze/格式、arm64构建/v2验签通过。APK SHA256 `7ad103a9cf2684d79b9e582e30a8e7cb6819d29e2eac3a560e03fae9c2637407`，源码/回归/产物与限制见design/verification.md和task check。
- **Next:** 主页任务保持in_progress / awaiting_device_review，真实Android返回键/手势、IME、桌面与重开需当轮授权验收或用户反馈；未安装/替换dist/提交/归档，不自动恢复公共底座。

## 2026-10-06 Flutter 优化交付

- **Mode:** `guided`，已获本任务完整方案实施授权。
- **Task:** 全 Flutter 实现与验证完成，本轮按计划提交及收尾；[归档记录](tasks/archive/2026-10/10-05-flutter-experience-polish/check.md)。
- **Goal gate:** 最终观感与提交确认已通过；2026-10-06 用户回复“观感通过，按计划提交”，原等待确认的阻塞已解除。
- **Authorization:** 用户要求按获奖级品质参照持续优化，澄清范围为整个 Flutter，已同意创建任务并规划，并回复“允许开始实施”。配置 A 可用于已批准方案的 ctOS 有界安装、界面和关键路径验证。
- **Scope:** 全部现有 Flutter 页面/状态，设计统一入口 `design/flutter-experience.md`；不增加产品能力或修改平台权限策略。
- **Evidence:** 最终 analyze 无问题、103 项测试通过、107 场景/127 张 Flutter 矩阵图片与独立 IP 复验；最终 APK `37d22600…` 构建/签名通过，配置 A Android 15 覆盖安装、base.apk 哈希一致。全部信息入口、密码结果/复制、App PTY/Tab/历史/输出搜索复制及关闭已实测。具体边界见 `design/verification.md`。
- **Next action:** 本轮授权涵盖工作提交、此任务归档与 journal，不包含 push。后续新工作仍按 guided 模式另行确认范围。

## 2026-09-28 已完成主线记录

- **Mode:** `guided`
- **Authorization:** On 2026-09-28, the user directed Codex to complete the remaining Trellis task graph in order and archive all legacy tasks, then confirmed the parent task's three-commit plan. That authorization is fulfilled and does not extend to new scope or device actions.
- **Current scope:** The three child tasks and `09-25-product-experience-opportunities` are complete and archived. No active task remains.
- **Current task:** None. The parent integration mapped P0's eight historical acceptance criteria to saved evidence, repaired archive links, and reconciled `design/` with the delivered slices. Its documentation work commit is `3e78ad3`; archive and journal bookkeeping follow.
- **Completed evidence:** P2 implementation `ca04b04`, archive `949a0f6`, journal `a30e0ae`; P1 implementation `fc254a4`, archive `9c78cfe`, journal `5d11297`; P0 implementation `90024df`, archive `d00f11d`; bootstrap archive `71c4130`. Parent integration evidence is in the archived task's `check.md` and `design/verification.md`.
- **Device access:** No device access is currently authorized by this completed graph. Prior task device results remain scoped to their recorded APK and target.
- **Next action:** New project work requires a separate bounded scope; do not infer priority or device authorization from these archives.

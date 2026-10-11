# 主页双返回退出检查记录

日期：2026-10-11。用户报告首版实机验收通过；下方提示16定向/154全量及新APK构建验签通过。用户在最新交付后回复“可以提交当前 task”，当前收尾提交/归档，保留设备证据为用户报告。

## 用户反馈与位置调整

用户回复“实机验收过；但是能否把 prompt 出现在下方？”。作为上一轮交付的用户验收报告保留，不冒称 agent 实测或推定设备/API/安装哈希。当前只调整提示位置到下方，同时防止再次被其他Snackbar遮挡。原14/152与7ad的检查/SHA属于先前顶部提示版，不能标为新调整的检查结果。

## 下方提示检查

提交前额外将HEAD基线与本任务选定代码/文档组合为隔离快照，排除未提交的公共秘密库代码/测试；hako内离线pub get、analyze无问题、119项Flutter测试通过（14秒）。这证明本任务提交可独立检查，与完整工作区154项结果分别记录。日志 `/tmp/ctos-home-commit-isolated-check.log`；曾因临时快照tar生成工作目录错误未包含pubspec，修正准备流程后检查成功，不是产品失败。981 APK仍为当时整体工作区构建，不冒称隔离提交快照的APK。

- guard builder提供Scaffold与稳定key提示槽，centerFloat自动协调导航/安全区/Snackbar；空闲尺寸为零，提示消失立即移除文字/朗读节点，双返回逻辑保留。
- 实施15定向/153全量通过，独立检查补充实际辅助功能语义/无点击与production rail安全区后最终16定向/154全量通过；analyze无问题（独立检查2.1秒），格式3文件0变更、diffcheck通过。
- 几何断言覆盖320/375/900横屏/1200、1/2倍文字、24dp安全区；普通多行Snackbar在提示出现前或后均排在其上方，两者不重叠。提示位于实际底部导航上方，宽屏production rail路径同样通过。
- 实际Semantics节点有liveRegion、无button/tap；点击提示不退出，超时立即消失。新测试首轮仅因测试SemanticsHandle在框架清理之后释放而失败，改为测试内try/finally释放后重跑通过；无实现缺陷。
- 新arm64构建成功，Gradle26.2秒/32.2MB，v2验签/1signer。APK SHA256 `981ef8145c01f1ce037f6ee5d80bd5672fe28e46fea591ac3d762fd48f9ef02a`，日志 `/tmp/ctos-home-double-back-bottom-build-20261011.log`；覆盖build同路径旧7ad输出，未替换dist。
- source SHA：main `25cdb690b428cbde61c771c95624df31c744047669b59b4f3e485cb51abe40c6`、guard `2b799533f6dbffdec70de692edd324cbe29eeb0b78635762ed0656056a271f86`、test `18d79a0584cdbf8c92b5e98febd1e37e1c02b296946e49bcf3331ea9466304a6`。root核对最终代码/回归/产物/哈希，同步design/spec。
- 未执行设备操作。上一轮用户验收反馈不等于新位置已实机通过；最新证据见 [验证](../../../../../design/verification.md#2026-10-11-退出提示移至下方)。

## 授权与任务切换

用户要求暂停另一任务并开始本任务，随后确认四个主入口 / 2 秒。公共秘密库 metadata 已记录 paused，保留源码、in_progress 与未完成关卡；当前指针为本任务。本轮仅本地实现/检查/构建，无设备操作。

## 已执行的准备检查

- `./hako flutter --version`：首次 sandbox 内 Docker socket 被拒；经任务范围自动审查的 require_escalated 调用通过，Flutter 3.35.7 / Dart 3.9.2。
- `task.py validate`：implement/check 两组真实 spec context 各 5 条通过。
- 研究 pinned Flutter 返回与 Snackbar 代码；采用 PopScope 与局部主题短提示，避免共享 Snackbar 队列延迟/误清其他反馈。

## 自动化与产物

- 实施、独立检查最终定向14项 / 全量152项均通过；analyze 无问题，指定3个 Dart文件格式0变更，git diff --check 通过。复核全量12秒/analyze2.5秒。
- 实际派发 binding.handlePopRoute、观察 SystemNavigator.pop mock；测试2秒边界、实际 CtosApp四入口/活动PTY/弹窗，详情/弹层/键盘metrics/后台/切换/observer替换与销毁。布局覆盖320/375/900横屏/1200及2倍文字。
- 独立检查先用多行 Snackbar/提示 Material 布局矩形重现遮挡红灯；提示从 body底部移至顶部 AppBar下方后断言通过。仅 find.text 不能证明可见性。
- 兼容原公开预览接口：CtosApp保持 Stateless/MaterialApp build，Observatory()无参构造继续可用；production注册ModalRoute observer，无observer预览通过继承route isCurrent重置。
- 初次两个测试因非法生命周期快捷跳转失败，改用SystemChannels.lifecycle真实消息；analyze发现旧预览构造/build兼容与test protected调用问题，均已修正。最终检查无未修复代码发现。
- arm64 release构建成功，Gradle31.3秒/32.2MB；build-tools36 apksigner verify成功，v2=true/1signer。产物 `build/app/outputs/flutter-apk/app-release.apk`，SHA-256 `7ad103a9cf2684d79b9e582e30a8e7cb6819d29e2eac3a560e03fae9c2637407`，日志 `/tmp/ctos-home-double-back-build-20261011.log`。
- 本任务sourceSHA：main `31d63445968e98531f8edb4bea61c9c9838c5cd492cc3cc2f6cf4e43b226f09b`，guard `759c85b4487ff7b02de4340fd7d22fa084e76c467dabbb5cebdbe8f85bc7cf65`，test `87a88017a50c33a56295b26aa460fb23481732c60bb93873f242a629ba1b1c51`。
- root已核对最终源码/回归/真实build日志/签名/哈希，同步design与frontend契约。完整结果见 [验证](../../../../../design/verification.md#2026-10-11-主页双返回退出本地实现与构建)。

不将平台 mock、构建或过往设备记录作为本轮 Android 实测。APK包含当前整体源码与暂停公共底座的已有实现；未安装、替换dist或推进底座工作。

## 待当轮授权的 Android 验收

1. 在概览、信息、工作台、终端逐一按返回：第一次留在当前入口并显示提示，2 秒内第二次退出到桌面。
2. 首次返回后等待至少 2 秒再返回，只重新提示；首次后切换入口再返回也只提示。
3. 首次后打开详情/弹窗，返回只关闭当前界面；回到主页再返回重新提示。
4. 终端输入打开软键盘时，第一次返回只收键盘；然后按主页双返回处理。检查系统返回键及返回手势。
5. 首次提示后按 Home / 切到其他应用再回来，第一次返回重新提示；退出后重新打开也需重新确认。
6. 观察提示不遮住底部导航，在窄屏、横屏、大字与减少动效下可读，不清理复制/保存等其他反馈。

Review classification：`human-required`，用户已报告原返回行为验收通过，并在最新下方提示交付后明确批准提交；用户审阅门槛已解除。以上原场景保留供追溯，不冒称agent设备实测；公共底座仍暂停。

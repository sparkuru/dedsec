# Suoni APK 分阶段执行草案

状态：未启动；本文件不构成实现授权。阶段按顺序推进，前一阶段验收后再扩展；待产品决策完成，可拆成有独立验收的子任务，当前不预建子任务。

## 0. 规划收敛

- [x] 创建 planning task，核查 archive 实际文件与项目边界。
- [x] 编写 PRD、技术方案、资料研究及上下文清单。
- [x] 用户确认 APK 独立离线控制、Flask 仅用于开发验证；同步 mainline 与规划/应用边界规范。
- [ ] 依次确认第一设备/手机、首版范围、Android 支持及 UI 方向。
- [ ] 按最终决策重写 PRD，保证 R/AC 映射无遗漏；同步 design/implement 及双 context。
- [ ] 给出最终规划摘要；收到用户后续明确批准后，才运行 task.py start。

## 1. 可构建的应用与实验基座

- 确定实际 `android/`、`lab/`、`fixtures/` 布局及应用标识；不改 archive。
- 按 development.md 检查 Docker/SDK/JDK、选择兼容版本并固定 Gradle wrapper/依赖；使用 before-dev 与适用语言技能。
- 生成可安装空壳、最少权限流程、可注入 transport 和脱敏字节样本；按已确认的开发工具角色建立 Flask 真实入口和测试配置。
- 仅在 Flask 有实际入口时实现项目要求的开发 wrapper、服务生命周期与 preview；完成真实容器启动/复用/停止检查。
- 验收：从可复现环境构建 APK，真机安装启动；实验工具可启动/停止。无设备时只记环境/模拟验证，不记蓝牙通过。

## 2. 协议与只读真机连接

- 编解码固定帧、分包/粘包、畸形长度、校验/转义、缓冲上限与恢复。
- 会话 ACK/重传/去重、通知独立处理、请求匹配、超时与取消、串行写入。
- 已配对目标选择、SDP/UUID、握手、能力、设备信息/电量。
- 验收：固定字节跨参考核对、假 transport 会话测试；选定真机组合完成 AC2，并记录脱敏证据。

## 3. 第一版控制闭环

- 按真实能力实现 NC/ASM read-modify-write；确认后更新 UI，处理范围和 unsupported。
- 原生交互覆盖权限、蓝牙关闭、空设备、加载、断连恢复、外部通知；至少检查窄屏、字体放大、TalkBack 和明暗模式。
- 真机完成 AC3–AC6；确认无服务器/互联网、Sony App 退出时可用。设备写入须另有范围明确的授权。
- 验收：可安装 APK 与构建/验证记录；任何硬件未验证项保持未关闭。

## 4. 后续候选

EQ/音频设置 → 快捷磁贴/后台生命周期 → 经能力核实的多设备管理，顺序可由用户调整。每一项单独确认范围和设备验收，不由本轮规划自动授权。

## 验证与回退

现在可运行：`python3 .trellis/scripts/task.py validate .trellis/tasks/10-17-suoni-apk`、`list-context` 及 `git diff --check`。它们仅检查规划/上下文，不检查 APK。

实现后预期使用 Android wrapper 的 `:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug` 和设备上的 `:app:connectedDebugAndroidTest`；这些是待基座建立后确认的任务名，不是当前已存在或已通过的命令。Flask 测试命令、报告目录和 Docker 命令同样届时登记。

回退点：基座可构建 → 只读握手 → NC/ASM 写入。每步失败在本步修复，保留之前可验证状态，不删除/覆盖用户 archive。提交、发布、归档不在本轮授权内。

## 当前证据与审阅

仅完成本地资料阅读和官方蓝牙文档核查；未构建、未启动服务、未安装 APK、未连接设备。产品选择未收敛，规划审阅为 human-required；这不是最终规划摘要，也不进入实现。

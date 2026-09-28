# Implementation 与验证记录

日期：2026-09-28。用户确认历史策略为 App 私有目录、最多 20 条且总计 5 MiB、满额淘汰最旧记录、不自动过期并支持手动清理；Trellis task 已启动。

## 已实现

- 注册 `network.interface_diagnose`，从接口详情携带并锁定接口名；Python 仅接收新鲜 App 网络快照中的目标接口和关联路由，不发网络请求、不改配置、不改变 Root/PTY 生命周期。
- Android host 通过原子版本化 JSON 文件保存 `device.info`、`memory.snapshot` 和 `network.interface_diagnose`；不持久化 stdout/stderr 或其他工作台脚本结果。新增历史列表、错误恢复、确认清理及历史项选择导出。
- 总导出改为选择、预览后再经现有 SAF 保存；连接快照导出不含应用图标映射。
- 新增 Flutter 路由/模型/导出覆盖、Android history store 仪器用例和接口快照投影用例。

## 本地验证

- `./hako dart format`：已覆盖修改的 Dart 源码与测试。
- `./hako flutter analyze`：通过，无问题。
- `./hako flutter test`：76 项全部通过。
- Python fake Context 边界检查：DOWN/空地址状态、计数器 unavailable、非法/消失接口失败语义通过。
- `:app:lintRelease` 与 `:app:compileReleaseAndroidTestJavaWithJavac`：BUILD SUCCESSFUL。
- `:app:assembleRelease :app:assembleReleaseAndroidTest`：BUILD SUCCESSFUL；release app/test APK 均已构建。
- `git diff --check` 与 `task.py validate 09-25-read-only-task-loop`：通过。

## 授权设备验收

- `TARGET-BOARD`：Android 11 / API 30、arm64-v8a。使用 `adb install -r` 安装本 task 的 release APK 和匹配测试 APK，保留 App 数据；两份设备哈希与本地产物一致，见 [验证记录](../../../../../design/verification.md#2026-09-28-只读任务闭环验收)。
- `TaskHistoryStoreTest` 通过 3 项；仅运行 `DeviceTest#interfaceDiagnosisUsesExactFreshAppSnapshotAndCanBeStored`，通过 1 项。未运行其他设备测试、Root 测试或 PTY 测试。
- 手动接口流程验证 App 快照缺少目标接口时记录失败、App 可见接口诊断成功；强制停止并重新启动后两条记录均保留。
- SAF 只导出用户勾选的一条历史记录并读回核对；取消唯一文件名的保存后该路径不存在。通过历史页确认清除两条记录，最终显示空状态。
- 用户授权了正常启动及现有 Root 自动恢复流程；未运行 Root 测试，未改系统或 VPN 配置，未清除 App 数据。

## Spec review

本 task 没有引入需要推广到共享 `.trellis/spec/` 的新规则；容量策略、allowlist、App-only 数据投影和设备实测结果均属于本 task 的具体契约，已分别记在任务设计和项目设计/验证文档。

# Journal - ctos (Part 1)

> AI development session journal
> Started: 2026-09-25

---



## Session 1: 可信状态工作台与 QQ 分身连接

**Date**: 2026-09-25
**Task**: 可信状态工作台与 QQ 分身连接
**Branch**: `antitrust`

### Summary

完成工作台与连接状态、应用图标及名称检索，修复返回应用后的旧快照，并验证 QQ 分身 tim 别名；PLR110 定向设备测试通过。归档仅保存在本地，仓库忽略 archive/。

### Main Changes

- 实现 QQ 分身 UID 与 Oplus 桌面别名映射，支持 tim、QQ 和包名检索

### Git Commits

| Hash | Message |
|------|---------|
| `90024df` | (see git log) |

### Testing

- [OK] Flutter 15 项；Android 定向测试 4 项；PLR110 实机显示分身卡片

### Status

[OK] **Completed**


## Session 2: 终端输入同步与输出选择

**Date**: 2026-09-26
**Task**: 终端输入同步与输出选择
**Branch**: `antitrust`

### Summary

完成终端双入口、底部普通输入、Tab 与 Shell 行同步、会话命令历史、输出选择及界面文案收敛；Flutter 分析和 22 项测试通过，当前 APK 在 PLR110 实测 i→Tab→d→执行、↑/↓ 与输出快照。

### Git Commits

| Hash | Message |
|------|---------|
| `d716d15` | (see git log) |

### Status

[OK] **Completed**


## Session 3: ctOS 当前项目进度提交

**Date**: 2026-09-27
**Task**: ctOS 当前项目进度提交
**Branch**: `antitrust`

### Summary

按用户要求提交 Root-only、Python 工作台及五项 Portable 工具进度；本地检查通过，保留任务状态且未归档。

### Main Changes

- 主提交 6556918 包含源码、测试、design 及 task/spec 记录；README 更新为最终五项工具版。

### Git Commits

| Hash | Message |
|------|---------|
| `6556918` | (see git log) |

### Testing

- [OK] Flutter analyze 无问题，Flutter 31/31，Android lintRelease 0 error / 5 既有 warning；Shell、Python 语法、文档链接、暂存 diff 检查通过。
- [OK] 现有 APK f928af66 及 13 份内置 Python 源码一致；未重新构建、安装或运行设备测试。
- [OK] ADB PLR110 / Android16 / 192.168.9.9:44553 在线；默认开发板 192.168.9.13:5555 No route to host。

### Status

[OK] **Completed**

### Next Steps

- 保留现有任务状态；后续功能或跨设备验证由用户另行指定。


## Session 4: Workbench and HFTP fixes: sanitized closeout

**Date**: 2026-09-28
**Task**: Workbench and HFTP fixes: sanitized closeout
**Branch**: `WORK-BRANCH`

### Summary

已脱敏提交工作台/HFTP保留修复并归档当前专项；熄屏限制保留，Root电源扩展暂缓。

### Main Changes

完成工作台表单与结果体验、HFTP 配置/有界日志、显式可选 Root 网络 adapter，以及停止边界、同端口重启和日志页面状态修复。Python HTTP 与 SAF 文件访问继续保持 App 权限。

用户要求保留当前停止修复、不扩大 Root 范围，并明确要求脱敏后提交。已提交的文档和两处测试示例泛化设备、LAN/ADB、动态 UID/PID、用户/本机路径；原始临时证据及构建产物未纳入提交，历史提交未重写。本记录中的分支角色已脱敏。

实现阶段证据：73 项 Flutter、25 项 Python、Android lint/build、native 传输与控制回归、十项手机定向检查及三轮亮屏 LAN 传输/真实 App 停止/同端口重启（含 31 MiB）通过。脱敏后复核：17/17 日志测试、analyze、AndroidTest Java 编译、JSON/JSONL、137 个相对链接和精确候选隐私检查通过；生产逻辑及已验证 APK 未变化，本轮未操作设备。

熄屏连接仍失败，仅 App 锁归属和回收已验证；独立 Root 电源能力暂缓、未实现、未触发。其他 ROM、物理无 Root 设备、Wi-Fi 丢失和五小时持续运行仍未验收。本任务按用户收敛后的保留范围归档；其他活跃任务保持原状态，不自动开启后续工作。


### Git Commits

| Hash | Message |
|------|---------|
| `d0aeb01` | (see git log) |

### Status

[OK] **Completed**


## Session 5: Firefly Android 11 定向验收与任务归档

**Date**: 2026-09-28
**Task**: Firefly Android 11 定向验收与任务归档
**Branch**: `antitrust`

### Summary

当前 ctOS APK 与测试包在 Firefly Android 11 安装哈希一致，七项非 Root 定向测试通过；归档 Portable 六子任务及父任务、Python 工作台、Root-only，并修复归档链接。

### Main Changes

- 记录当前 APK 的 Firefly 定向验证与未测边界。
- 归档九个已完成 Trellis 任务并修复相对链接。

### Git Commits

| Hash | Message |
|------|---------|
| `0e3a312` | (see git log) |
| `5675042` | (see git log) |

### Testing

- [OK] Firefly AIO-3568J Android 11：7 项非 Root 仪器测试 OK；HFTP loopback 停止后服务为空。
- [OK] design 与本月归档任务的本地 Markdown 链接全部可解析；git diff --check 通过。

### Status

[OK] **Completed**

### Next Steps

- 如需扩展设备兼容结论，另行明确授权并验证 Firefly Root、LAN/熄屏、SAF 手操等边界。


## Session 6: 只读任务闭环与开发板验收

**Date**: 2026-09-28
**Task**: 只读任务闭环与开发板验收
**Branch**: `antitrust`

### Summary

完成并归档只读任务与选择性导出闭环，授权 Android 11 目标上的定向仪器和 SAF 流程验收通过。

### Main Changes

- 增加 App-only 接口诊断、限定三类只读任务的本地有界历史，以及逐项选择的 SAF 导出。
- 完成强制停止后重启的历史恢复、成功/失败记录、单记录导出读回、取消不写文件和手动清理实测。

### Git Commits

| Hash | Message |
|------|---------|
| `fc254a4` | (see git log) |
| `9c78cfe` | (see git log) |

### Testing

- [OK] Flutter analyze、76 项测试、Python fake Context 边界检查、Android release lint/build 通过。
- [OK] TaskHistoryStoreTest 3 项与 DeviceTest 接口投影 1 项通过；未运行 Root 测试。
- [OK] 测试期间按用户授权走既有 Root 自动恢复启动；未修改系统或 VPN 配置，未清除 App 数据。

### Status

[OK] **Completed**

### Next Steps

- 按授权的 serial mainline 开始 09-25-interface-terminal-polish，完成后再做 parent integration；新 task 的设备验收需独立授权。


## Session 7: 界面与终端可用性整理及归档

**Date**: 2026-09-28
**Task**: 界面与终端可用性整理及归档
**Branch**: `antitrust`

### Summary

完成界面与终端可用性整理，79 项 Flutter 测试、analyze、release 构建及授权 Firefly Android 11 UI/PTY 验收通过；任务已归档。

### Main Changes

- 统一 Material 3 卡片与内容宽度，改进终端快捷栏和输出搜索/复制。

### Git Commits

| Hash | Message |
|------|---------|
| `ca04b04` | (see git log) |
| `949a0f6` | (see git log) |

### Testing

- [OK] Flutter analyze、79 项 Flutter 测试、release APK 签名与 SHA-256 校验通过。
- [OK] 授权 Firefly Android 11 实测快捷栏、普通键盘、Ctrl-C、输出搜索/复制、返回后 PTY 存活及只读 Root id。

### Status

[OK] **Completed**

### Next Steps

- 进入 09-25-product-experience-opportunities 父任务 Phase 1 集成规划；三个子任务均已归档。


## Session 8: 完成产品体验父任务集成与归档

**Date**: 2026-09-28
**Task**: 完成产品体验父任务集成与归档
**Branch**: `antitrust`

### Summary

补齐 P0 八项历史验收对应证据，核对三个子任务交付边界，修复归档链接并更新 ctOS 设计文档；文档提交 3e78ad3，父任务归档 d620fe0。29 份文档、168 个本地链接均可解析，Trellis 校验与 diff 检查通过；未执行产品构建或设备操作。

### Git Commits

| Hash | Message |
|------|---------|
| `3e78ad3` | (see git log) |
| `d620fe0` | (see git log) |

### Status

[OK] **Completed**

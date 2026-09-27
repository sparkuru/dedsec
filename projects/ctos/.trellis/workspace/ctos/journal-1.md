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

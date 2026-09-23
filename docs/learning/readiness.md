# 研究就绪清单

检查日期：2026-08-12。

## 当前状态

| 项目 | 状态 | 证据或下一动作 |
|---|---|---|
| 仓库边界 | 迁移中 | `docs/`、`labs/`、`projects/`、`workbench/` 已分工；历史 `src/` 仅保留待分类材料 |
| 证据规范 | 已就绪 | `docs/research/README.md` |
| 上游 revision | 已就绪 | `third_party/upstreams.lock` |
| 实验模板 | 已就绪 | `labs/_template/README.md` |
| target app 源码 | 已就绪 | [`labs/example-app/`](../../labs/example-app/README.md) |
| 遗留材料 | 迁移中 | [遗留材料与上游参考清单](../research/materials-inventory.md) |
| target app 本机构建 | 阻塞 | 当前主机没有 Android SDK、系统 Gradle和 `javac` |
| ADB 客户端 | 已就绪 | Debian ADB 34.0.5 |
| 设备基线 | 待采集 | 运行 `scripts/device-preflight.sh` |
| 设备恢复演练 | 待确认 | 按[设备安全与恢复](device-safety.md)完成一次非事故演练 |
| Vector 动态证据 | 未开始 | 研究阶段 1–3 |
| 首个模块 | 未开始 | target app 安装后开始 legacy API 实验 |

## 本机工具基线

已观察：

- OpenJDK runtime 21.0.12。
- ADB 34.0.5。
- ShellCheck 与 shfmt。
- Ruby、unzip、zip。

缺失：

- Android SDK platform 36 与 build-tools 36.0.0。
- 可执行的 Android SDK manager。
- Gradle 系统安装。
- 完整 JDK 编译工具。

target app 自带 Gradle wrapper，但仍需完整 JDK 17+ 与 Android SDK。构建文件固定为 AGP 9.3.0、Gradle 9.5.1、compileSdk 36、targetSdk 36。

## 设备准入

设备开始承担实验前需满足：

- ADB 能稳定识别唯一 serial。
- `adb shell su -c id` 返回 uid 0。
- 已记录 Android build fingerprint、安全补丁、ABI、SELinux 和 boot slot。
- 已保留当前 boot/init_boot 镜像及其来源。
- 已验证 bootloader/recovery/fastboot 中至少一条恢复路径。
- 研究数据与日常账号、聊天数据、支付数据隔离。
- 知道如何禁用全部第三方模块并恢复一次正常启动。

设备预检命令：

```sh
./scripts/device-preflight.sh
```

报告默认写入 `/tmp`，因为它包含 serial、型号和 build fingerprint，不进入仓库。

## 首次实验前门禁

1. 运行 `./scripts/verify-readiness.sh`。
2. 在具备 SDK/JDK 的环境运行 `./scripts/verify-readiness.sh --android`。
3. 安装 target app，逐个执行主进程、后台线程、异常、重载、动态 ClassLoader 和 worker 进程场景。
4. 使用 `./scripts/capture-lab-logs.sh` 保存基线日志。
5. 记录 target app APK SHA-256、设备报告路径和 Vector revision。
6. 没有模块时的基线行为必须先稳定，再开始 Hook。

## 完成定义

研究真正开始前，应能回答：

- 当前实验针对哪个 APK、commit、设备 build、框架 revision 和进程。
- 预期观察什么，什么结果会推翻预期。
- 日志和 APK 如何对应到同一次实验。
- 失败后如何回到无模块的已知状态。

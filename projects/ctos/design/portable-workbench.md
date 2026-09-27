# 工作台、Portable 包与脚本 SDK

2026-09-26：本文保留 Python 工作台与 SDK v1 的初始基线。当前五项工具、SDK v2、文件能力及 HFTP 独立后台服务见 [portable-tools.md](portable-tools.md)。验证结论见 [verification.md](verification.md)，不能将本地 QEMU 检查当作手机权限与 PTY 验收。

## 使用入口

四入口为概览、信息、工作台、终端。工作台的 item 进入二级页，显示参数、运行状态、退出码、耗时、输出与结构化数据；结果支持复制及系统文件选择器保存 JSON。设备页移除系统负载和 `/proc/loadavg` 采集，保留系统、内存、电源、存储。

第一版内置 CPython 3.13.9 / arm64，运行时、标准库、CA 证书与 SDK 随 APK 打包，不需要 Termux、Root 或运行时下载。工作台提供环境自检、设备摘要、内存快照、文本摘要。首次进入工作台或打开终端时释放运行包，后续复用。

App Shell 与 Root PTY 的交互 Shell 加载生成的 `ENV` 文件，提供 `python3` 函数；Root 的 `su -p` 保留此环境。可运行 `python3 --version`、`python3 -c 'print(6*7)'`、`python3 script.py` 或交互解释器。该函数适用于 ctOS 的交互终端；系统 Shell、其他 App 及嵌套非交互 `sh -c` 不自动获得此入口。Python 自身子进程需要使用已配置的启动器/环境，不能假设 `sys.executable` 的 basename 就是 `python3`。

## Portable 包契约 v1

包是 ctOS 管理的逻辑挂载：APK 内清单和 ZIP/资源释放到应用私有目录，原生可执行文件仍在 APK 安装后的原生库目录。它不需要 Magisk，不向 `/system` 或系统挂载表写入，不执行安装脚本。第一版仅加载编译时内置包；外部 ZIP 导入、签名/哈希信任链、禁用/卸载和热替换另行设计。

清单位置：`android/app/src/main/assets/portable/<id>/package.json`。已有 [Python 包](../android/app/src/main/assets/portable/python/package.json) 是完整示例。

| 字段 | 契约 |
| --- | --- |
| `format` | 当前为 `1`，不支持的版本拒绝加载 |
| `id` / `version` | 有界安全标识，目录名不得包含路径分隔符 |
| `abi` | 与设备 ABI 匹配；本 APK 为 `arm64-v8a` |
| `license` | 用于环境详情显示的许可证摘要；完整声明随 APK 保留 |
| `tools` | 命令名 → `lib*.so` 形式的原生可执行文件名，不接受外部路径 |
| `archives` | ZIP asset 和包内目标目录；检查路径穿越、条目数和展开大小 |
| `directories` / `files` | APK asset 目录或文件及包内目标路径 |
| `environment` | 每个工具调用独立配置；支持 `${PACKAGE}` 和 `${NATIVE}` 占位符 |

宿主补充 `LD_LIBRARY_PATH` 指向安装后的原生库目录，使用参数数组运行工具。生成的 Shell 函数在子 Shell 中 `export` 环境并直接调用带单引号转义的可执行路径，兼容 Android 安装目录中的 `=`；无 `eval`、用户输入拼接或全局 HOME 改写。命令名重复、缺失可执行文件、非法路径和不匹配 ABI 均报错。每个 ZIP 最多 10,000 个条目、64 MiB 展开数据；资源目录限制递归深度。目录按包版本及 APK 更新标识区分，清单成功释放后写 `.ready`。应用升级停止旧进程后重新释放 SDK；当前版本不提供包缓存管理界面，卸载应用清理私有目录。

追加 curl 等原生工具时：

1. 取得可追溯来源、版本、校验和及许可证。使用 Android/Bionic arm64 构建；普通 glibc Linux ARM 二进制不能直接视为 Android 可用。
2. 使用 NDK、Android API 28+ 目标和 PIE 可执行文件；当前项目最低 Android API 为 28。可执行文件命名为例如 `libctos_curl_exec.so`，加入 APK 的 `jniLibs/arm64-v8a` 或现有 Gradle generated/jniLibs 构建输出。不要将可执行程序仅放在 ZIP 后尝试从应用数据目录 `execve`。
3. 创建 `assets/portable/curl/package.json`，把 `curl` 映射到该可执行文件。CA、配置和数据文件可以打包为 `payload.zip`，由 `archives` 释放；共享库依赖优先随 APK 原生库打包。
4. 在当前 APK 构建中保留 `useLegacyPackaging=true`，让 PackageManager 提取原生程序；验证链接依赖、16 KiB ELF 对齐、离线启动和目标设备行为。
5. 包会由 `PortablePackages` 自动发现，出现在环境页“内置运行包”中；终端自动获得其命令。新增工作台脚本仍需加入显式 SDK 注册表。

第一版实际仅包含 Python 包；curl 是扩展接入示例，尚未打包。

## 脚本 SDK v1

代码位于 `android/app/src/main/python/`：

- `ctos_sdk.py`：`Parameter`、`Script`、`Context`、统一结果和有界日志。
- `ctos_scripts.py`：显式注册表与四个内置脚本，是目录和执行白名单的单一来源。
- `ctos_workbench.py`：`catalog` / `run SCRIPT_ID` 机器 JSON 协议。

注册一个脚本即可生成工作台 item 和参数表单。例如在 `ctos_scripts.py` 添加函数，并把 Script 放入 `registry()` 返回的 tuple：

```python
def battery_snapshot(context: Context, parameters: dict[str, str]) -> dict:
    """Read the task-local battery snapshot and retain its provenance."""
    return context.section("battery")

Script("battery.snapshot", "Battery snapshot", "Battery and temperature",
       "system", battery_snapshot)
```

`Parameter` 第一版支持字符串、必填、默认值、最大字符数及多行。Python 端再次校验未知键、类型、空值和长度，不只依赖 Flutter 表单。新增脚本标题和描述可直接使用注册表内容；内置脚本在 Flutter 有中文显示映射。

`Context` 提供本次任务的 App 设备快照及私有工作目录。`section(name)` 返回 `source`、`capturedAt`、`data`，不可用时报告实际原因；不暴露 Activity、Root 会话或宿主服务。脚本返回 JSON 数据，stdout/stderr 分别被截获；异常、`SystemExit`、`KeyboardInterrupt` 都转成任务结果。

统一结果包括 `script`、`taskId`、`environment`、`sdk`、`startedAt`（Unix 毫秒）、`durationMs`、`state`、`exitCode`、`data`、`stdout`、`stderr`、`truncated`。状态为 `completed` / `failed` / `cancelled` / `timed_out`。脚本错误保留 Python traceback；启动失败通过 native channel 传回 Flutter 失败结果。

输入最多 32 KiB、结构化数据最多 32 KiB、每路 Python 日志最多 16,384 字符；原生 stdout 通道最多 256 KiB，启动诊断 stderr 最多 16 KiB。只允许一个工作台 Python 任务，最多运行 15 秒；取消、超时、二级页销毁和 Activity 后台会结束工作台子进程，之后可重新运行。每次任务使用新进程，不把脚本状态留到下一次。PTY 的 Python 是独立交互进程，使用现有终端生命周期与 Ctrl-C，不受工作台 15 秒限制。

SDK 是供 APK 内可信代码使用的编译时框架，不是第三方脚本安全沙箱。工作台以 `-P -S` 和受管 SDK 路径启动，避免可写工作目录中的同名模块覆盖内置注册表。终端由用户主动运行的 `.py` 脚本拥有所选 App/Root Shell 权限；工作台脚本始终为 App 权限。任意源码导入、运行时 pip、调度与普通脚本后台化不在该契约内。当前 HFTP 独立宿主服务使用专门权限及生命周期，见 [工具设计](portable-tools.md)。

## 运行时来源

Chaquopy 17.0.0 提供 Python 3.13.9 和 Android 标准库，ctOS 自有 NDK PIE 启动器使用 `dlopen` + `Py_BytesMain`；未调用 Java 嵌入解释器。`pyc.src=false` 保留 SDK 源码；当前 pip 依赖另由项目私有构建 Python 3.13.7 准备，不要求宿主全局安装。APK 仍保留 Chaquopy 构建默认生成的运行资源。

参考：[Chaquopy Gradle 文档](https://chaquo.com/chaquopy/doc/current/android.html)、[17.0.0 发布变更与 16 KiB 支持](https://chaquo.com/chaquopy/doc/current/changelog.html)、[Android 应用数据目录执行限制](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission)、[Magisk su 环境保留参数](https://topjohnwu.github.io/Magisk/tools.html)。版本、依赖和许可证见 [dependencies.md](dependencies.md)。

# 检查结果

## 2026-09-26 Portable Python 工作台：构建与 PLR110 验收

本轮 current APK：`dist/ctos-current-arm64.apk`，SHA-256 `00c19e0ca2ecc84b1fea32fafaec29708b152710a3f5a3b8ece64717a26d97c6`，约 24.4 MB。原命令页改为工作台、原工作台改为概览；系统负载展示及 procfs 采集删除。加入 APK 内置 CPython 3.13.9、终端 python3、四项脚本二级页、SDK 和 portable 清单。框架契约见 [portable-workbench.md](../../../design/portable-workbench.md)。

- `./hako flutter analyze`：无问题。`./hako flutter test --reporter expanded`：**28/28 通过**，包含新工作台懒加载/失败重试、二级导航、Unicode 参数校验、取消/超时后重跑、小屏/横屏/2 倍文字与低动画布局，以及已有网络和 PTY 组件回归。
- `./hako bash -lc 'cd android && ./gradlew :app:lintRelease :app:assembleReleaseAndroidTest --console=plain'`：最终原生实现通过 lint，设备测试 APK 构建成功。新增三项 Python SDK/非法脚本、取消/超时、Activity 后台回收检查，已有 App/Root PTY 测试追加 python3、标准库与 Python Ctrl-C 检查。
- `./hako current`：arm64 release 构建、签名验证及 SHA256SUMS 通过。最终 APK 检查包含 PIE 启动器、libpython3.13、标准库、三份 SDK 源文件、portable 清单和三份版权声明；无宿主 pyc。merged manifest 确认为 `extractNativeLibs=true`；启动器为 ARM64 PIE、`/system/bin/linker64` interpreter、16 KiB LOAD 对齐。
- 安装前通过显式 serial 的 getprop 确认 PLR110 / Android 16（`192.168.9.9:44603`），复制公共 Android 链接器及库到 `/tmp` 供 QEMU。用户随后明确授权覆盖安装 current APK 和测试 APK并验收。两个 APK 均安装成功，设备 base.apk SHA-256 与最终 current 一致。SELinux 为 Enforcing；未更改 Magisk/Vector 配置、重启或清除应用数据。
- 本地 `qemu-aarch64` 执行构建期间 APK 提取的 ARM64 启动器、libpython 和 SDK，`--version` 返回 Python 3.13.9；四个内置脚本通过。自检为 OpenSSL 3.0.18、SQLite 3.50.4，SQLite 内存查询和 SHA-256 校验正常；CLI 导入 SSL/SQLite/SDK 成功。非法参数、未知脚本失败；主机 SDK 的有界日志/结果及 SystemExit 结果封装检查通过。固定 `-P -S` 路径在工作目录存在恶意同名模块时仍加载内置目录。
- 真机首轮发现 Android 安装目录含 `=`，Toybox `env` 将启动器路径误当环境赋值；改为子 Shell 中 `export` 后直接执行路径，App/Root 均可调用 Python。Root PTY 返回额外 CR，测试断言沿用已有读取函数的 CR 规范化。最终完整 `DeviceTest` **OK 8/8，6.233 秒**，覆盖 SDK、自检/Unicode 文本/设备/内存、未知脚本后恢复、运行中真实进程取消和重跑、1 ms 超时回收、Activity 移到后台后取消待运行任务并释放槽位，以及 App/Root PTY 的 Python 3.13.9、SSL/SQLite/SDK 导入、Python sleep 的 Ctrl-C 与原有网络/Root 回归。日志：`/tmp/ctos-python-device-tests-20260926.log`。
- 真机 UI：概览/信息/工作台/终端导航正确，设备页不再显示系统负载；工作台四个 item 和 Python 二级页正常。点击运行自检显示 App、已完成、75 ms、退出码 0，Python 3.13.9 / aarch64 / SDK 1、OpenSSL 3.0.18、SQLite 3.50.4 和内存查询成功。通过系统文件选择器另存 `Download/ctos-python-selftest-20260926.json`，拉回后 JSON 与屏幕结果一致，stdout/stderr 为空。截图：`/tmp/ctos-python-{overview,workbench,detail,selftest,device-info}-20260926.png`；读回文件 `/tmp/ctos-python-selftest-20260926.json`。以上均为本机临时证据。
- Shell 模板通过语法、ShellCheck 和 shfmt 检查；最终生成函数在两种 Android PTY 中实测通过。`git diff --check` 通过。
- 临时证据：`/tmp/ctos-sdk-validation/`（测试脚本与 Shell 模板）、`/tmp/ctos-python-qemu/`（公共 Android 库及提取的运行包）。QEMU 树没有设备 linkerconfig/tzdata，启动产生对应诊断；stderr 与 JSON stdout 分离，脚本协议检查不受影响。这些目录仅是本机临时证据。

**边界**：Android 11、16 KiB 页面设备、其他 Root 管理器未测。参数表单的 Unicode/小屏/失败状态以组件及 SDK 检查为主，未逐项手操；后台检查验证真实 Activity.onStop 取消待运行任务，运行中进程销毁另有独立测试。curl 为扩展文档示例，未打包；外部 ZIP 导入、包缓存管理、运行时 pip、历史与后台调度未实现。

以下记录属于各自历史 APK；其中“当前包”以该条记录日期和哈希为准。


Review: human-optional。自动及 PLR110 实机检查完成，用户可继续评估脚本目录与二级页的产品体验；跨设备兼容边界已记录。既有 Root-only WIP 保留，未暂存、提交或归档。

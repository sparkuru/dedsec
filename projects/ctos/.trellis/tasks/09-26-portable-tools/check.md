# 本轮检查（2026-09-26）

当前阶段：五项实现及本地检查完成，用户已授权并完成 PLR110 安装与定向验收；结果见末尾。本文件前面的表格和预检是安装前记录。父/子 task 保持 in_progress，仅待 WIP 审阅/提交流程；保留之前 Root-only / Python-workbench 修改，未提交或归档。

| 检查 | 结果与边界 |
| --- | --- |
| Flutter analyze | No issues found |
| Flutter full tests | 31 / 31；含文件 token/秘密遮挡/独立产物导出/HFTP 页面离开不停止 |
| Android lintRelease | 通过，0 error；5 个既有 warning（包可见性、版本建议、ARM-only/ChromeOS、DataExtractionRules） |
| Android test APK | 编译通过，新增 PortableToolsTest 两项；尚未在本轮手机执行 |
| 26 核心 | /tmp uv 142 项断言，主 agent 复验通过，见 encoder 子 task |
| 五项集成 | /tmp uv 113 项断言；08 与原脚本对照，02 原始脚本产物兼容、认证篡改/错误密码和产物保留；26 token/二进制；09 mock/响应/重定向；HFTP 本机认证、上传下载、不覆盖、路径/符号链接拒绝 |
| CLI | /tmp 12 次实际调用：帮助、密码、转换、拒绝覆盖、加密往返、错误密码保留 |
| HFTP 占用端口 | 只读占用测试通过，结构化启动错误、不输出凭据、不终止原监听者 |
| HFTP browser | 真实 Chromium + Playwright：上传、编码目录、嵌套上传、下载、409 不覆盖、375px 无横向溢出/JS错误；仅宿主 loopback |
| 私有构建 bootstrap | /tmp 全新下载、官方固定 SHA-256 校验、解包、Python 3.13.7、第二次缓存复用；bash -n / ShellCheck / shfmt 均通过 |
| ARM64 QEMU | 前置加密依赖加载、AES-GCM 往返通过；最终 APK 的完整运行验证在下方追加 |
| 新动态库 | cryptography _rust、cffi、libffi 与所需 Chaquopy 原生库 LOAD 对齐均 0x4000；并非原生 16KiB 页设备验收 |
| 设备 | 仅只读 get-state/getprop：192.168.9.9:44603 在线、PLR110、Android16；本轮未安装/授予通知权限/执行 native 测试 |
| 系统边界 | 全部新增依赖/构建缓存限 APK、项目 .devhome、/tmp；无全局 pip、HOME/PATH 修改或 /system 挂载写入 |

临时验证环境：`/tmp/ctos-tools-check`，`UV_CACHE_DIR=/tmp/ctos-tools-uv-cache`。运行脚本的指纹：

- check_tools.py：`02e02ac82ff239767134cbb72570cef682cf1df5fc63c655647b8d261e76ffd1`
- check_cli.py：`f7a36835c7c90539804339a7f8662a41736c2d71df4eda02a15d3463e8e01abf`
- browser_check.py：`25dca79337981944b89f6bebe0d0d532fdc7a736c714852ed0034bb913ea3c8b`
- 浏览器截图：`/tmp/ctos-tools-check/hftp-browser-phone.png`，已实际查看；服务页面目前为简洁功能界面，不包含原脚本外部 CDN 预览。

复验（临时文件仍存在时，从上述目录执行）：

```text
UV_CACHE_DIR=/tmp/ctos-tools-uv-cache PYTHONDONTWRITEBYTECODE=1 uv run --offline --no-sync check_tools.py
UV_CACHE_DIR=/tmp/ctos-tools-uv-cache PYTHONDONTWRITEBYTECODE=1 uv run --offline --no-sync check_cli.py
UV_CACHE_DIR=/tmp/ctos-tools-uv-cache PYTHONDONTWRITEBYTECODE=1 uv run --offline --no-sync browser_check.py
```

loopback 验证需要允许宿主 socket；初次 sandbox 下 PermissionError 后，在明确仅127.0.0.1的执行权限下通过。浏览器首轮测试使用字符串 eval 被服务 CSP 拒绝，改成遵守 CSP 的 locator 断言后通过；没有放宽服务 CSP。Flutter 专项测试最初的 widget 类型/滚动断言及 Android test 的 JDK 文件 API 已修正，最终完整检查通过。

下一步：对本轮具体 APK 申请 PLR110 安装/通知授权和回归验收；只运行指定 serial，HFTP 仅 loopback，结束关闭测试服务/清理自身测试文件。保留 SAF 实机选择/取消/导出、通知拒绝/允许/停止、后台返回、App/Root 终端及 IP 在线/离线行为的实际结果，不把历史验证当成当前通过。

## 最终产物预检

- 当前 APK：`dist/ctos-current-arm64.apk`，26,105,995 bytes，SHA-256 `28a7fb5a2bb287b0aae5fb71fb37fce8167204cedebd52c3a51f916cadadcbe2`。hako current 构建、apksigner 与 SHA256SUMS 校验通过。
- 测试 APK：`build/app/outputs/apk/androidTest/release/app-release-androidTest.apk`，SHA-256 `a51a6f4478a3b6ee5eee51c88565d1b9796659fc7b3b83aa7ef1a64bf1ae555f`。
- APK 中 13 个 Python 源文件逐字节匹配本轮工作树；requirements/许可证、受管 libffi 路径与禁写字节码配置在实际包内。
- 最终 APK 解出的运行包在 QEMU 启动，SDK2 catalog 九项正确；密码长度、Base64 与 AES-GCM 往返通过，cryptography OpenSSL 3.0.18。没有用宿主源码覆盖该最终验证包。
- aapt2 实查包含 INTERNET/ACCESS_NETWORK_STATE/POST_NOTIFICATIONS/FOREGROUND_SERVICE/FOREGROUND_SERVICE_DATA_SYNC；无系统安装/全局存储权限。
- _rust.so、_cffi_backend.so、libffi.so 的所有 LOAD p_align >=16KiB。QEMU 有 linkerconfig/tzdata 缺失提示，仅模拟环境，不作为手机 SELinux/页大小通过。

安装及 scoped native 测试须等本 task 用户授权；当前没有安装/授予通知权限/开 LAN 服务。当前源码差异检查通过，保留既有 WIP。

## 本轮设备授权与安装开始

用户明确允许本轮 PLR110 覆盖安装 APK/测试 APK、授予通知权限、验收工作台/App与Root终端/HFTP本机后台通知停止。当前设备再确认 PLR110 / Android16 / 页大小4096 / SELinux Enforcing。两次 adb -s install -r 均 Success。ADB pm grant 因手机 GRANT_RUNTIME_PERMISSIONS 限制拒绝，未修改系统调试策略；改走 ctOS 正常通知权限弹窗，确认授权结果。拒绝路径未完成独立实机验收，不能宣称通过。

## Android 文件提交修复与实测

首轮 6 项中 5 项通过，HFTP 在后台可下载、通知可见，但上传返回 400。新增真实 App Python 文件输出测试确认 `os.link` 抛 EACCES。没有修改系统策略：SDK 产物、CLI 与 HFTP 改共用 `files.commit_exclusive`，使用 `renameat2(RENAME_NOREPLACE)`；已存在文件/符号链接拒绝替换，失败清理临时文件。旧 API 28/29 ARM64 syscall 路径仅静态核对官方 App 清单，尚无旧版本设备实测。

修复后 113 项集成、12 次 CLI、真实 Chromium 上传/下载/特殊目录/409/375px/CSP 回归全部通过；额外 16 个并发输出只允许一个成功，符号链接保持原样、失败提交不产生文件。

最终 APK `dist/ctos-current-arm64.apk`：SHA-256 `6f2130d1e15c8ce62cefea1cd3be7c3ca9718f88a7af5aaf42a353fd1e707c28`。13 个 APK Python 源文件逐字节匹配工作树；从 APK 解出的 ARM64 包在 QEMU 验证 SDK2/九项/原子不覆盖/AES-GCM。签名与 SHA256SUMS 通过，Android lint 0 error、5 个既有 warning。

手机修复后六项组合 **OK (6 tests), 4.681 s**，工作台后台取消单项 **OK (1 test), 1.634 s**。覆盖加密往返、篡改不提交/源保留、文件转换、终端不覆盖、HFTP认证/穿越拒绝/上传下载/409/后台常驻通知/停止监听、App与Root Python、真实取消/超时/释放槽位和未知脚本拒绝。七项合并首跑最后一项因后台 Activity 启动等待超时，保留失败记录；测试随后显式 CLEAR_TASK 隔离 Activity，最终组合复验另行记录，不把 6+1 写成单次七项通过。

真实 SAF 已将公开文本 `ctos-saf-test-20260926` 编码产物保存并读回 `Y3Rvcy1zYWYtdGVzdC0yMDI2MDkyNg==`；选择该测试文件成功，显示私有导入副本及取消选择按钮，取消选择后 token 清空。系统选择器返回需先关闭 IME 再返回，取消无文件保持空选择；没有修改既有文档。本轮导出位于选择器当前目录，仅清理确切测试文件。

本机证据：`/tmp/ctos-tools-check/device-native-results.txt`（首轮失败）、`device-native-final-results.txt`（七项合并超时）、`device-six-final-results.txt`、`device-background-final-results.txt`；运行器和 UI dump 仅在 /tmp。未做真实 IP 提供方查询、全新安装通知拒绝、Android11/原生16KiB页设备验证；相关行为分别有 mock/widget/静态证据，不能当作该手机实测。

## 最终组合验收与清理

测试 Activity 显式 NEW_TASK | CLEAR_TASK，避免 singleTop 复用后台任务。最终测试 APK SHA-256 `be024d92e5cc2a1d76c9d46ff8cf970dc04bb086304c2a2d4c57e2c59344c9c6`；组合 **OK (7 tests), 6.814 s**，日志 `/tmp/ctos-tools-check/device-seven-final-results.txt`。命令如下，实际执行后阅读 OK/失败结果，不能仅依据 am instrument 的进程退出码：

```text
adb -s 192.168.9.9:44603 shell am instrument -w -r -e class 'im.majo.ctos.PortableToolsTest,im.majo.ctos.DeviceTest#bundledPythonRunsSdkWithoutRootAndRejectsUnknownScripts,im.majo.ctos.DeviceTest#pythonCancellationAndTimeoutRecycleTheProcess,im.majo.ctos.DeviceTest#backgroundActivityCancelsWorkbenchAndAllowsNewRun,im.majo.ctos.DeviceTest#appAndRootPtyAreInteractive' im.majo.ctos.test/androidx.test.runner.AndroidJUnitRunner
```

HFTP 实际页面启动默认 loopback，切到桌面后通知仍在；展开 ctOS HFTP 通知并点击“停止”，通知消失，dumpsys services 确认无服务。没有启动 LAN 或关闭其他监听者。

设备安装 APK SHA-256 与最终产物 `6f2130d1…` 一致，产物 26,106,487 bytes。SAF 公开测试文件、两个测试输出和一个测试导入 token 已逐个核对并精确删除；没有清理 App 全部存储或原有目录。设备临时 UI dump 及包含其他文件名的宿主 UI 截图/XML 已删除；原有用户文档未改写。最终服务列表为空。

五项实现及本轮验收完成。Review: human-optional；父/子 task 仍为 in_progress，表示既有 WIP 的审阅/提交/归档未执行，没有剩余功能实现或已知失败验收项。保留上述跨设备/真实网络/权限拒绝测试边界。

## 09 现行接口校验与当前最终包

最后核对真实服务时发现原脚本域名/路径已迁移。按 [官方 Get IP Info](https://freeipapi.com/docs/api-reference/get-ip-info) 改为 `https://free.freeipapi.com/api/v1/json`，保留固定HTTPS/CA/禁止重定向/32KiB/8秒限制。宿主实际查询及PLR110工作台明确填写公共示例1.1.1.1均返回正确source/ipAddress；没有查询自身公网IP。额外mock确认目标URL、8秒超时和离线错误传播。真实离线设备仍未验证。

因此重新构建并覆盖安装当前最终 APK：26,106,495 bytes，SHA-256 **`f928af6611f014206597321b2f35a094aef53b11eeb404ea7bbf72e23ee8a73b`**，签名/SHA256SUMS及设备安装哈希匹配。当前测试 APK仍为 `be024d92…`，从最终源码重新assemble通过。最终七项组合再次 **OK (7 tests), 6.392 s**，日志 `/tmp/ctos-tools-check/device-seven-api-final-results.txt`；13份实际APK Python源码逐字节匹配，APK解包ARM64 SDK2/九项/不覆盖/AES-GCM/现行IP地址均通过。Android lint 0 error / 5既有warning。前面的 `6f2130d1…` 为文件提交修复阶段包，已被本包替换。

最终临时 UI dump 清理和服务为空复核完成。Review 为 human-optional：指定手机行为与自动化已覆盖，额外视觉反馈可选；没有留下功能实现或失败回归项。未暂存、提交或归档既有 WIP。

# Portable 工具集与独立文件服务

2026-09-26，任务为 [portable-tools](../.trellis/tasks/09-26-portable-tools/prd.md)。本轮只接入用户指定的 08、26、09、02、16-HFTP；源码和本地预检已实现，手机验收结果单独记录在 [verification.md](verification.md)。

## 模块与入口

| 模块 | 工作台 item | 终端子命令 | 职责 |
| --- | --- | --- | --- |
| `ctos_tools/password.py` | 08 · 密码生成 | `password` | 与原脚本兼容的确定性密码算法；盐值或盐文件显式提供 |
| `ctos_tools/encoder.py` | 26 · 编码与哈希 | `encoder` | Base64 / URL / Unicode 双向和自动转换，MD5 / SHA-1 / SHA-256 / SHA-512 |
| `ctos_tools/ip_lookup.py` | 09 · IP 查询 | `ip` | 手动触发固定 HTTPS 提供方查询；支持 IP、DNS 名称及空值查询公网 IP |
| `ctos_tools/crypto.py` | 02 · 文件加解密 | `crypto` | 新版认证加密、明确选择旧 CBC 格式解密 |
| `ctos_tools/hftp.py` | 16 · HFTP 文件服务 | `hftp` | 有认证的共享库浏览、建目录、上传、下载 |

`adapters.py` 负责工作台元数据和文件能力转换；`cli.py` / `__main__.py` 提供 `python3 -m ctos_tools`，复用同一套核心函数。加密依赖按固定模块名延迟加载，其他工具不会因未加载加密库而耦合。`ctos_scripts.registry()` 仍是九个脚本/item 的统一目录及短任务白名单；HFTP 的普通脚本函数拒绝启动服务，工作台服务页必须经独立原生入口手动启动。

Flutter 分为 `workbench.dart`（目录）和 `workbench/` 下的模型、API、参数表单、脚本页、HFTP 页、文件空间管理。原生分为 `ToolFiles`、`PythonRunner`、`HftpBridge`、`HftpService`、`PortablePackages`；Activity 只转发方法和生命周期。

示例：

```text
python3 -m ctos_tools --help
python3 -m ctos_tools password --length 24
python3 -m ctos_tools encoder --text hello --operation base64
python3 -m ctos_tools encoder --input file.bin --operation hash
python3 -m ctos_tools crypto encrypt input.bin encrypted.ctos
python3 -m ctos_tools crypto decrypt encrypted.ctos restored.bin
python3 -m ctos_tools ip example.com
python3 -m ctos_tools hftp --directory ./share --host 127.0.0.1 --port 8080
```

密码生成默认私密提示输入 seed；加解密默认私密提示密码，可显式选已有密码文件。命令行直接提供 seed/salt 会进入用户自己的命令记录，使用默认提示可避免该情况。`--log` 是子命令前的全局诊断选项。CLI 不自动建服务、不后台化、不删除源文件；终端 HFTP 在当前进程前台运行，Ctrl-C 停止。App / Root 终端继承各自 Shell 权限，用户主动选择的终端路径与工作台 App 文件能力是不同入口。

## SDK v2 与文件能力

SDK v2 向后兼容追加 `Parameter.kind`（text / choice / file）、`choices`、`secret`。Python 重验选项、类型、长度、必填和文件 token；Flutter 对秘密字段禁用输入建议、默认遮挡并提供显示切换。08 的结果默认遮挡；种子、盐、密码及结果不自动写入历史。用户主动复制或保存结果会导出所选结果内容。

`Context.files_root` 指向 App 的 `files/tool-files/`，选文件通过 ACTION_OPEN_DOCUMENT 复制，只返回随机 token。工作台不接受任意文件路径。选中文件是 `<uuid>.input`，产物是新的 `<uuid>.output`；Python 检查 token、普通文件和符号链接，输出经临时文件、同步及唯一名称发布。任务取消后遗留的输出 `.partial` 在下一次运行清理。输出文件通过 token 和 ACTION_CREATE_DOCUMENT 另存；取消选择器不作为保存成功。

单文件及输出最多 32 MiB，工具存储最多 128 MiB、2,000 个条目。转换输出膨胀时仍受输出上限约束。工作台有“工具临时文件”查看/清理入口，只清理 App 内的导入副本及工具产物；运行任务时拒绝清理。HFTP 共享库与之分离，互不清理。原始选中文件不改写、不删除，失败不删除既有输出。

SDK 产物、CLI 输出和 HFTP 上传共用 `files.commit_exclusive`。Android/Linux 使用 `renameat2(RENAME_NOREPLACE)` 原子发布；已有文件或符号链接均拒绝替换。PLR110 的 App Python 实测硬链接返回 EACCES，因此不依赖硬链接发布，也不调整 SELinux。API 28/29 的 ARM64 libc 尚无该符号，使用相同系统调用 276；[Android 9 App policy](https://android.googlesource.com/platform/bionic/+/refs/heads/pie-security-release/libc/seccomp/arm64_app_policy.cpp) 和 [Android 10 whitelist](https://android.googlesource.com/platform/bionic/+/refs/heads/android10-release/libc/SECCOMP_WHITELIST_COMMON.TXT) 均包含此调用。该旧版本路径经过静态核对，未做旧手机实测；文件系统不支持时明确失败，不降级为覆盖写入。

普通工作台保持单任务、15 秒、32 KiB 输入/结果及有界日志。IP 查询使用 CA 验证、固定 `https://free.freeipapi.com/api/v1/json`、不跟随重定向、最多 32 KiB 响应；地址按 [FreeIPAPI 官方接口](https://freeipapi.com/docs/api-reference/get-ip-info) 更新，不依赖原脚本旧地址的重定向。App 的整体期限同时约束 DNS 和网络等待。离线和服务方错误返回实际失败，不缓存或自动查询。CLI 的网络 I/O 使用连接/读取超时，可由 Ctrl-C 取消。

这些是 APK 内可信代码的能力契约，不是任意第三方 Python 的安全沙箱。

## 密码兼容与加密格式

08 的无盐及明确盐文件样例和原脚本对照一致，保留 SHA-256 / PBKDF2 10,000 次、字符集、必须字符、长密码分段和 1,000 次重试语义。长度限制为 1–128，字符集最多 256 字符；无法满足的必须字符数量提前拒绝。不读取系统 UUID，不自动创建盐文件。

02 新格式为 `CTOSENC` 加版本字节 `1`、16 字节随机盐、12 字节随机 nonce、AES-256-GCM 密文和 16 字节 tag。PBKDF2-HMAC-SHA256 使用固定 200,000 次迭代，头部也作为认证数据。解密通过完整认证后才生成新产物；错误密码、头部/密文/tag 篡改均失败。

`legacy-decrypt` 明确读取原 02 的 Base64 包裹、16 字节盐、PBKDF2 10,000 次、AES-CBC 格式，并严格检查 PKCS#7。原脚本实际生成的空/短/16 字节/长盐样例已经验证。旧格式没有认证，不能保证错误密码或篡改一定被识别；页面/结果保留这一说明。新加密不会再生成旧 CBC 格式。

## HFTP 生命周期与共享边界

工作台 HFTP 由 Android `HftpService` 独立拥有 Python 进程。手动启动时要求通知权限和可用通知频道，使用 `dataSync` 前台服务；通知显示状态和停止按钮，点击通知打开 App。切到后台或离开服务页继续运行；普通脚本的 Activity.onStop 取消逻辑不会影响该进程。

不注册开机启动，不自动重启，`START_NOT_STICKY`。用户停止、Service.onDestroy、启动失败或 Android timeout 会回收本服务拥有的进程；不会扫描/杀死占端口的其他进程。启动超时为 15 秒，单次服务上限 5 小时；Android 自身也有后台限额，可能更早停止，需要手动重新启动。前台服务不保证系统永不回收或休眠网络始终可用。

工作台仅共享 `files/hftp-share/`，通过文件选择器明确导入的副本及收到的上传文件才可见。服务运行时不允许 App 导入/清空共享库，避免与写入配额并发；停止后可导入或明确清空。单文件 32 MiB、共享库 128 MiB、最多 2,000 条目、路径深度 8、并发连接 4、每连接空闲超时 15 秒。上传使用独占目标和临时文件；同名返回 409，不覆盖；中断临时文件在本次请求 finally 或下一次服务启动清理。

每次启动生成新的随机密码，用户名 `ctos`，所有请求需要认证。口令不放入 argv、访问日志、通知或持久偏好；页面默认遮挡，仅用户主动显示/复制。默认仅监听 127.0.0.1，局域网模式需手动选择 0.0.0.0。HTTP 不做传输加密，LAN 页说明仅在可信网络使用。

拒绝路径穿越、符号链接、隐藏路径；下载按 attachment 返回并设置 nosniff。浏览页自包含，无 CDN、cgi、ifaddr、外部脚本；上传要求自定义头，浏览页设置 CSP、no-store 和禁止框架嵌入。保留基本浏览/目录/上传/下载，不移植原脚本的杀进程、systemd、系统安装、外部 CDN 预览和后台常驻安装功能。

参考：[Android dataSync 服务](https://developer.android.com/develop/background-work/services/fgs/service-types#data-sync)、[后台服务时间限额](https://developer.android.com/develop/background-work/services/fgs/timeout)、[AESGCM API](https://cryptography.io/en/42.0.8/hazmat/primitives/aead/)。

## 构建与环境隔离

运行包仍遵守 [Portable 清单 v1](portable-workbench.md)。APK 包含固定 cryptography 42.0.8、cffi 1.17.1、pycparser 3.0、chaquopy-libffi 3.3 及许可证；Python 环境增加受管 site-packages 和 libffi 路径，仅对子进程/终端适配函数生效。没有运行时 pip、全局 PATH/HOME 修改、系统挂载或 /system 写入。

`hako-env.sh` 为 pip 打包准备私有 CPython 3.13.7，固定官方构建包 URL 和 SHA-256，仅释放至忽略的 `.devhome/python/`；并发首次准备有项目内锁。下载、校验或已有不完整目录都会明确失败，不覆盖已有工具链目录。宿主仍只需现有 Docker 入口。工具验证环境、Python 包测试和浏览器验证均在 /tmp；没有全局 Python 包安装。

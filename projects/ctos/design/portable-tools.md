# Portable 工具集与独立文件服务

2026-09-26，任务为 [portable-tools](../.trellis/tasks/09-26-portable-tools/prd.md)。本轮只接入用户指定的 08、26、09、02、16-HFTP；源码和本地预检已实现，手机验收结果单独记录在 [verification.md](verification.md)。

## 模块与入口

| 模块 | 工作台 item | 终端子命令 | 职责 |
| --- | --- | --- | --- |
| `ctos_tools/password.py` | 密码生成 | `password` | 与原脚本兼容的确定性密码算法；盐值或盐文件显式提供 |
| `ctos_tools/encoder.py` | 编码与哈希 | `encoder` | Base64 / URL / Unicode 双向和自动转换，MD5 / SHA-1 / SHA-256 / SHA-512 |
| `ctos_tools/ip_lookup.py` | IP 查询 | `ip` | 手动触发固定 HTTPS 提供方查询；支持 IP、DNS 名称及空值查询公网 IP |
| `ctos_tools/crypto.py` | 文件加解密 | `crypto` | 新版认证加密、明确选择旧 CBC 格式解密 |
| `ctos_tools/hftp.py` | HFTP 文件服务 | `hftp` | 共享目录浏览、建目录、上传、下载；App 免登录，CLI 保留认证 |

`adapters.py` 负责工作台元数据和文件能力转换；`cli.py` / `__main__.py` 提供 `python3 -m ctos_tools`，复用同一套核心函数。加密依赖按固定模块名延迟加载，其他工具不会因未加载加密库而耦合。`ctos_scripts.registry()` 仍是九个脚本/item 的统一目录及短任务白名单；HFTP 的普通脚本函数拒绝启动服务，工作台服务页必须经独立原生入口手动启动。

Flutter 分为 `workbench.dart`（目录）和 `workbench/` 下的模型、API、参数表单、脚本页、HFTP 页、文件空间管理。原生分为 `ToolFiles`、`PythonRunner`、`HftpBridge`、`HftpService`、`PortablePackages`；Activity 只转发方法和生命周期。

## 工作台体验（2026-09-27）

首轮修改 Flutter 展示与交互，九项目录和 SDK v2 不变。首页优先显示五项常用工具，再显示其他脚本与环境；临时文件管理移至底部。已知参数与枚举显示中文，传给 SDK 的键和值仍为原协议；未知参数使用目录元数据回退。用户九张图片反馈后，工具标题去掉数字前缀、删除 script ID“技术详情”，文本按钮占满内容宽度；下拉弹窗通过 alignedDropdown 与字段边界一致。

密码默认展示种子和长度，长度使用数字键盘并校验 1–128。高级项折叠时仍保留盐值、盐文件、字符集和必含字符；隐藏高级项有错误时自动展开。编码页明确选择文本或文件，保留两份草稿，只将活动来源提交；文件来源未选择 token 时阻止运行。哈希模式隐藏转换方向，但保留兼容参数。文件区域左对齐。

`ResultCard` 优先展示密码、逐项哈希、IP 摘要或文件预览，并直接复制目标值、按原 token 保存产物。设备与内存摘要保留来源和采集时间；未知结果可查看原始数据。生成密码默认遮挡，原始详情和日志也遮挡已知密码；主动复制密码或复制/保存完整 JSON 会导出完整值，页面明确说明。结果 envelope 未改写，不自动保存密码。

秘密参数使用普通文字 IME；Flutter 的 MaskedTextController 在呈现层按 UTF-16 长度显示圆点，原始值、选区和组合输入保留。遮挡时可访问性值也遮挡，显示切换为显式操作。保留 enableSuggestions=true：Flutter 在 Android 上将 false 转为 VISIBLE_PASSWORD，会触发 TARGET-PHONE 的安全键盘；普通键盘仍可能显示建议。关闭自动更正、IME 个性化学习和智能引号/破折号，不改变系统键盘配置。

HFTP 页面将状态和随状态变化的启动/停止按钮前置，配置与共享目录管理分区，清理采用独立警示样式且仍需确认，并且只用于默认私有库。新增上传上限和本机目录选择；用户目录没有清理/导入副本按钮。进入/退出页面只影响状态轮询，服务仍按原生命周期由用户显式启动、停止。配置恢复完成前不可启动；旧状态轮询不能覆盖后续明确操作。实现及检查见 [体验优化任务](../.trellis/tasks/09-27-workbench-usability/prd.md) 与 [验证记录](verification.md)。首轮 `98f44069…` 曾完成 TARGET-PHONE 界面/键盘/SAF/输入来源定向验收，追加反馈的新版本检查单独记录。

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

SDK v2 向后兼容追加 `Parameter.kind`（text / choice / file）、`choices`、`secret`。Python 重验选项、类型、长度、必填和文件 token；Flutter 对秘密字段使用上文的普通键盘配置、默认遮挡并提供显示切换。密码生成结果默认遮挡；种子、盐、密码及结果不自动写入历史。用户主动复制或保存结果会导出所选结果内容。

`Context.files_root` 指向 App 的 `files/tool-files/`，选文件通过 ACTION_OPEN_DOCUMENT 复制，只返回随机 token。工作台不接受任意文件路径。选中文件是 `<uuid>.input`，产物是新的 `<uuid>.output`；Python 检查 token、普通文件和符号链接，输出经临时文件、同步及唯一名称发布。任务取消后遗留的输出 `.partial` 在下一次运行清理。输出文件通过 token 和 ACTION_CREATE_DOCUMENT 另存；取消选择器不作为保存成功。

单文件及输出最多 32 MiB，工具存储最多 128 MiB、2,000 个条目。转换输出膨胀时仍受输出上限约束。工作台有“工具临时文件”查看/清理入口，只清理 App 内的导入副本及工具产物；运行任务时拒绝清理。HFTP 共享库与之分离，互不清理。原始选中文件不改写、不删除，失败不删除既有输出。

SDK 产物、CLI 输出和 HFTP 上传共用 `files.commit_exclusive`。Android/Linux 使用 `renameat2(RENAME_NOREPLACE)` 原子发布；已有文件或符号链接均拒绝替换。TARGET-PHONE 的 App Python 实测硬链接返回 EACCES，因此不依赖硬链接发布，也不调整 SELinux。API 28/29 的 ARM64 libc 尚无该符号，使用相同系统调用 276；[Android 9 App policy](https://android.googlesource.com/platform/bionic/+/refs/heads/pie-security-release/libc/seccomp/arm64_app_policy.cpp) 和 [Android 10 whitelist](https://android.googlesource.com/platform/bionic/+/refs/heads/android10-release/libc/SECCOMP_WHITELIST_COMMON.TXT) 均包含此调用。该旧版本路径经过静态核对，未做旧手机实测；文件系统不支持时明确失败，不降级为覆盖写入。

普通工作台保持单任务、15 秒、32 KiB 输入/结果及有界日志。IP 查询使用 CA 验证、固定 `https://free.freeipapi.com/api/v1/json`、不跟随重定向、最多 32 KiB 响应；地址按 [FreeIPAPI 官方接口](https://freeipapi.com/docs/api-reference/get-ip-info) 更新，不依赖原脚本旧地址的重定向。App 的整体期限同时约束 DNS 和网络等待。离线和服务方错误返回实际失败，不缓存或自动查询。CLI 的网络 I/O 使用连接/读取超时，可由 Ctrl-C 取消。

这些是 APK 内可信代码的能力契约，不是任意第三方 Python 的安全沙箱。

## 密码兼容与加密格式

08 的无盐及明确盐文件样例和原脚本对照一致，保留 SHA-256 / PBKDF2 10,000 次、字符集、必须字符、长密码分段和 1,000 次重试语义。长度限制为 1–128，字符集最多 256 字符；无法满足的必须字符数量提前拒绝。不读取系统 UUID，不自动创建盐文件。

02 新格式为 `CTOSENC` 加版本字节 `1`、16 字节随机盐、12 字节随机 nonce、AES-256-GCM 密文和 16 字节 tag。PBKDF2-HMAC-SHA256 使用固定 200,000 次迭代，头部也作为认证数据。解密通过完整认证后才生成新产物；错误密码、头部/密文/tag 篡改均失败。

`legacy-decrypt` 明确读取原 02 的 Base64 包裹、16 字节盐、PBKDF2 10,000 次、AES-CBC 格式，并严格检查 PKCS#7。原脚本实际生成的空/短/16 字节/长盐样例已经验证。旧格式没有认证，不能保证错误密码或篡改一定被识别；页面/结果保留这一说明。新加密不会再生成旧 CBC 格式。

## HFTP 生命周期与共享边界

2026-09-27 停止反馈迭代源码已加入显式停止边界：Bridge/通知先进入stopping，旧ready/EOF/失败回调不可更新状态；current owner保持至后台关闭完成，status的closing布尔值使失败清理期间也锁定启动/配置。正常停止清当前reason而保留日志；显式停止已关闭的失败会话亦清当前错误。日志折叠与滚动使用独立PageStorage标识。native checked SO_REUSEADDR支持修正版本自身连续同端口重启，活跃监听器仍独占；旧无reuse的TIME_WAIT须自然过期。最终检查/当前包实测以[验证记录](verification.md)为准。

2026-09-27 实时服务日志已实现并完成TARGET-PHONE验收：Python请求事件通过有界stdout协议汇入Java内存缓存，页面可展开/选择/复制/清空；停止保留，新会话重置，不落盘。最多200条/32KiB、单条512字符；排除认证头、body、密码、query、完整SAF URI和原始私有路径，处理控制字符，旧会话不能污染新状态。`hftpStatus`和`hftpClearLogs`返回完整状态及logs字符串数组；清空仅清缓存，不停止服务。

普通LAN已监听0.0.0.0；用户WindowsWINDOWS-LAN-IP访问手机PHONE-LAN-IP仍受禁止非VPN连接/Clash不可bypass约束，扩大监听不能授予网络豁免。研究见 [VPN边界研究](../.trellis/tasks/09-27-workbench-usability/research/vpn-lockdown.md)。用户明确授权后的可选Root中继已实现并在TARGET-PHONE验证，保持VPN/系统规则不变，Windows已确认可打开；实际33MiB LAN上传/下载、无覆盖/限额/后台/停止回收通过。最终包0575e00d…，具体证据及未测范围见[Root实机记录](../.trellis/tasks/09-27-workbench-usability/root-device-check.md)。

host-owned `ToolExecutionContext.declare(id, requirement)`只允许`hftp`/APP与`hftp.networkRelay`/ROOT，声明不代表授权。`RootOperationAdapter`在效果边界检查能力、固定APK helper与参数，并独立拥有专用su代理；不复用采集RootSession，不向Python SDK提供Root句柄或任意Shell接口。普通App路径不调用该adapter；缺少Root/拒绝只限制中继，仍可选择普通模式。后续Root工具需登记具体操作、类型化adapter入口及无Root/拒绝/资源回收测试，不扩大为任意代理接口。collector/PTY尚未迁移到此新机制。

显式Root模式只适用LAN，Python/SAF仍为App UID且backend仅监听owned127.0.0.1随机端口。UID0 native helper用公开android_setsocknetwork绑定选定物理Wi-Fi监听，限定同子网IPv4及唯一loopback backend；loopback socket不绑定Wi-Fi。helper不处理HTTP/文件，最多4连接/方向64KiB/15s idle/10s心跳/5h会话，控制STOP/EOF/owner失效回收。Android生产helper拒绝非UID0，adapter核对ready UID/地址/端口，Root/Wi-Fi失败明确停止无fallback。没有Root文件访问或系统网络规则改动。

停止反馈追加R24/R25已实现：native先处理有界控制队列再检查原10s心跳，closed日志显示固定原因/errno；仅Root adapter拥有App PARTIAL_WAKE_LOCK，固定tag、最多5h无续期、构造失败及停止/异常关闭释放，普通App模式不取得锁。当前9ca71e…十项手机与三轮实际LAN传输/停止/重启含31MiB通过，但熄屏仍拒绝连接，App锁登记不代表idle时CPU可用；不能以host排队PING或isHeld通过判设备休眠通过。用户已选择暂不扩大Root范围，独立Root电源候选未实现；完整证据以[验证记录](verification.md)为准，不改变电池豁免或VPN策略。

工作台 HFTP 由 Android `HftpService` 独立拥有 Python 进程。手动启动时要求通知权限和可用通知频道，使用 `dataSync` 前台服务；通知显示状态和停止按钮，点击通知打开 App。切到后台或离开服务页继续运行；普通脚本的 Activity.onStop 取消逻辑不会影响该进程。

不注册开机启动，不自动重启，`START_NOT_STICKY`。用户停止、Service.onDestroy、启动失败或 Android timeout 会回收本服务拥有的进程；不会扫描/杀死占端口的其他进程。启动超时为 15 秒，单次服务上限 5 小时；Android 自身也有后台限额，可能更早停止，需要手动重新启动。前台服务不保证系统永不回收或休眠网络始终可用。

默认仍共享 `files/hftp-share/`，保留已导入的副本及收到的上传文件。用户可用 ACTION_OPEN_DOCUMENT_TREE 选择其他本机目录，持久 read/write grant，并可切回默认；支持本机 ExternalStorageProvider 与 DownloadStorageProvider。首次选择器定位本机 Download，系统 Downloads 入口同样有效；取消或失败保留原选择。原生目录 broker 调用 DocumentsContract，Python 通过随机 abstract AF_UNIX socket 访问，校验同 App UID；不猜测原始路径、不新增全文件权限、不使用 Root。

目录内容以所选 provider 可见文档为准。Downloads 的 MediaStore 目录可能不列出未经索引的 ADB 文件；需要完整本机文件视图时从系统选择器的设备内部存储入口选择目录，不绕过授权读取原始路径。

`hftpConfig` 返回字符串host/port/maxUploadMiB/directoryName/treeUri及布尔rootRelay（默认false）；`hftpPickDirectory` 取消返回 null，成功返回配置；`hftpUseDefaultDirectory` 返回默认目录配置。`hftpStart` 校验相同参数（数字以字符串传递）；只有显式布尔true选择Root，遗漏默认false，不能从已存配置隐式提权。UI显示实际native模式，目录选择保留其他草稿，切仅本机清除Root选项。启动、目录选择及服务运行期间锁定配置操作，授权失效显式失败。配置存在App偏好；Activity销毁后的旧选择回调不能修改新owner配置或撤销授权。

HFTP 上传上限可设置 1–1024 MiB，默认 32；私有库总配额为 max(128 MiB, 4×上传上限)，最大 4 GiB，最多 2,000 条目。普通工具仍是单文件 32 MiB/存储 128 MiB。用户目录不把既有文件计入私有总配额，每目录有界枚举/条目，路径深度 8、并发连接 4、每连接空闲超时 15 秒；下载独立上限 1 GiB。服务运行时不可导入/清理默认库，不能从 App 清理用户选中目录。

私有库仍通过临时文件和 renameat2 no-replace 原子发布。SAF 则先完整接收至自建隐藏 document，再独占创建精确目标（自动变名则清理自建文件并返回 409），复制完整内容；失败只清理本请求创建的 target/partial。最终复制时 HFTP 不显示/下载该目标，其他本机 App 仍可能看见未完成的目标，SAF 不承诺原子发布。不得通过 provider.renameDocument 的检查后重命名来承诺并发无覆盖；停止或失败不扫描用户目录删除其他文件。

按本轮图片反馈，App 默认监听 `0.0.0.0:7888`，免登录，不生成/显示用户名或密码；用户仍可改为仅本机。局域网地址排除 loopback/tun/VPN，优先 Wi-Fi；本机地址独立列出。HTTP 不加密传输，页面说明同一网络可直接访问和上传。CLI 仍默认 loopback:8080，在前台运行并使用认证口令；两个入口共享 Python 服务，不把 App 免登录默认值扩散为 CLI 改动。

拒绝路径穿越、隐藏路径和控制字符；Path 后端拒绝符号链接及非普通文件。SAF 使用系统 provider 的授权树和规范文档路径检查：API29+ isChildDocument，API28 findDocumentPath；SAF 元数据无法直接证明底层条目是否符号链接，不能将其宣称为 inode 级 symlink 检测。下载文件描述符仍要求普通文件，且不得逃出获授权树。下载按 attachment 返回并设置 nosniff。浏览页自包含，无 CDN、cgi、ifaddr、外部脚本；上传要求自定义头，浏览页设置 CSP、no-store 和禁止框架嵌入。保留基本浏览/目录/上传/下载，不移植原脚本的杀进程、systemd、系统安装、外部 CDN 预览和后台常驻安装功能。

参考：[Android dataSync 服务](https://developer.android.com/develop/background-work/services/fgs/service-types#data-sync)、[后台服务时间限额](https://developer.android.com/develop/background-work/services/fgs/timeout)、[AESGCM API](https://cryptography.io/en/42.0.8/hazmat/primitives/aead/)。

## 构建与环境隔离

运行包仍遵守 [Portable 清单 v1](portable-workbench.md)。APK 包含固定 cryptography 42.0.8、cffi 1.17.1、pycparser 3.0、chaquopy-libffi 3.3 及许可证；Python 环境增加受管 site-packages 和 libffi 路径，仅对子进程/终端适配函数生效。没有运行时 pip、全局 PATH/HOME 修改、系统挂载或 /system 写入。

`hako-env.sh` 为 pip 打包准备私有 CPython 3.13.7，固定官方构建包 URL 和 SHA-256，仅释放至忽略的 `.devhome/python/`；并发首次准备有项目内锁。下载、校验或已有不完整目录都会明确失败，不覆盖已有工具链目录。宿主仍只需现有 Docker 入口。工具验证环境、Python 包测试和浏览器验证均在 ${EVIDENCE_DIR}；没有全局 Python 包安装。

# Architecture

Flutter `MethodChannel` 调用 APK 进程中的 Java 后端；数据采集使用工作线程，UI 每两秒请求一次快照，仅在前台采样。连接列表按需刷新。导出由 Android Storage Access Framework 完成。

当前源码有四入口导航：概览、信息、工作台、终端。信息页含设备、网络、接口和连接。设备快照通过 `deviceSnapshot` 在 App 权限下按需采集，系统、内存、电池和 `/data` 存储各自返回状态、来源、采集时间及失败原因；状态区分可用、无权限、不支持和读取失败。设备页已移除系统负载，后端不再读取 `/proc/loadavg`。概览启动时读取一次设备快照，进入设备页及下拉刷新时再读取；网络轮询只在前台概览、网络及接口页进行。概览先显示设备摘要，再显示 App、Root 各自状态及恢复动作，最后提供常用入口；宽屏内容限制在 840dp 内。Root 的启动自动申请仍仅执行一次，失败后由用户手动重试。

连接页通过 Flutter 的 `ConnectionSnapshotState` 保存最近一次成功读取的内容和独立采集时间。首次读取、加载、部分结果、无匹配、旧快照和失败各有不同提示；30 秒后标记旧快照，刷新失败保留旧内容和时间，并显示错误详情。每次进入连接页以及从其他应用返回且连接页可见时重新采集，加载期间保留上次快照，避免刚打开应用却在旧快照里搜不到连接。`ConnectionReport` 把原生 `ss -tunape` 文本拆成协议、状态、队列、本地/远端、UID 和可得的应用归属，诊断行及完整原文保留供展开核对；列表逐条展示并按字段筛选。完整应用名、别名、包名等精确匹配优先于宽泛的子串匹配。`Collector.connections` 仅为本次出现的 UID 附可得应用元数据和 72px PNG 图标，按 UID+包名去重；Flutter 缓存解析结果，按显示名、应用自身名称、进程名、包名等字段检索。Root 路径解析同一包的多个用户 UID；对可访问的主用户应用复用显示名和图标，并在 OnePlus/Oplus 设备上通过受保护的桌面收藏项与用户序列号读取分身别名。桌面提供者不可用时仍保留应用原名、包名与用户 ID；别名来源与跨 ROM 支持见 [兼容性记录](compatibility.md)。同 UID 对应多个包时页面保留多包信息，不能从 UID 推断唯一应用；图标不可取得时显示占位图标。图标数据仅供界面使用，导出仍保留原有连接文本与状态，不包含 Base64 图标。工作台用独立 Flutter 页面 `workbench.dart` 展示 Python 静态注册表，进入参数和结果二级页；CPython 3.13.9 由 APK 内置 portable 包提供，短任务用独立子进程、App 权限、单任务及 15 秒超时运行，支持取消。上下文只读任务、本地历史与选择性导出见下节。`PortablePackages` 解析 APK 内置清单、释放 ZIP/资源并生成终端工具函数；自有 NDK PIE 程序从原生目录加载 libpython，PTY App/Root Shell 通过 ENV 获得 `python3`。原生 stderr 与 JSON stdout 分开、有界读取，后台取消工作台任务。完整框架与后续 curl 接入见 [portable-workbench.md](portable-workbench.md)。当前源码和 APK 的具体验证范围见 [verification.md](verification.md)。

## 只读诊断、历史与导出（2026-09-28）

静态 Python 注册表新增 `network.interface_diagnose`。接口详情把接口名预填并锁定后打开工作台；Android host 在 App 权限下采集新的网络快照，只把精确命中的接口及关联网络/路由投影传给该脚本。脚本只描述当前状态、地址、MTU、可用累计计数器和系统网络元数据，不发网络探测、不改设备配置，也不推断互联网连通性。缺失或消失的对象返回失败；DOWN、空地址和不可用计数器作为观测值保留。

Android host 通过版本化 `AtomicFile` JSON 存储最多 20 条、总文件最多 5 MiB 的任务历史。允许保存的 task ID 固定为 `device.info`、`memory.snapshot`、`network.interface_diagnose`；网络历史只含选中接口结果，不含完整网络快照、原始 stdout/stderr 或图标。超限时移除最旧记录；记录不会按年龄过期，工作台历史页提供二次确认的手动清理和选择历史导出。损坏文件显示错误且不被写入覆盖，可由用户明确清除。

原全量立即导出入口改为逐项选择和预览网络、设备、连接快照及由用户选中的历史记录。最终 JSON 只序列化勾选内容，保留每项来源、采集时间和范围；连接导出不含应用图标，SAF 取消仍不创建文件。实机交互是否通过以本轮设备验收记录为准。

2026-09-26 移除 Vector/Xposed 系统模块与广播桥接，见 [决定](decisions/2026-09-26-root-only.md)。APK 不声明模块，不再使用系统注入、QUERY/RESULT 广播或 module 快照字段。网络配置由 App 的 ConnectivityManager / LinkProperties 提供；接口计数与全部路由表优先使用已授权 Root 的固定只读查询，无 Root 时退回 TrafficStats（API 31+）或可读取的 procfs。

APK 的 Root 采集仅运行固定只读命令。任意命令只从用户操作的终端输入，终端子进程在 APK 外执行，未向其他应用导出终端服务或命令入口。Root 不是通过 system_server 提供，网络配置保留 App 可见范围，不能将其等同 UID 1000 的跨用户网络视图。

Root 采集在应用启动时通过 `rootAuto` 请求一次 `su`：首次安装可由 Magisk 弹出授权提示，成功后在应用私有偏好中保存自动启动标记；后续启动及覆盖安装恢复一个持久会话。完整卸载会清除本地标记，但重新安装时默认仍会申请一次；Magisk 是否免弹窗取决于其自身是否保留授权。接口轮询与连接查询串行复用该会话，避免每两秒重新调用 su 触发 Magisk 提示。每条命令在子 shell 内执行，随机结束标记分隔输出与退出码；输出保留上限 1 MiB。拒绝、超时或会话退出后关闭自动启动标记，接口采集退回普通 API，需用户在概览手动授权；成功后重新启用自动启动。Activity 销毁时关闭会话但保留标记。用户主动打开的 Root PTY 是独立的交互会话。

Kernel 计数使用单调时钟计算 delta。计数回退、接口消失或新出现时丢弃旧基线；历史最多保留 60 个点。各接口独立展示，避免 VPN 与物理接口重复相加。

终端未连接时直接展示“应用 Shell”和“Root PTY”两个入口；Root 不可用时入口不可启动。每次只有一个用户主动开启的 PTY，会话启动、停止和原生退出分别处理。终端由自有 JNI 代码打开 `/dev/ptmx`，创建会话和控制终端，执行 `/system/bin/sh` 或 `su -p`（保留内置工具 ENV）。文件描述符不暴露给其他应用；PTY 输出通过有界缓冲块流向 xterm，UTF-8 跨块解码。上方 `TerminalView` 只负责显示和滚动，不接受键盘输入；底部普通文本输入框可见正在输入的文字，并将 IME 已提交编辑实时、串行同步到 PTY；Shell 同时在上方回显，发送键写入回车。快捷栏提供可收起的 Ctrl-C、Tab、Esc、↑、↓ 五个控制，折叠动作位于现有会话操作栏。Tab 由 Shell 自动补全，收到 PTY 回显后从 xterm 光标所在的可编辑行同步下方输入与已发送基线，不将补全文本再次写入 PTY。无法可靠识别 Shell 行时，清除下方过时草稿并提示以上方终端为准。↑/↓浏览当前 PTY 会话从底部输入框提交的最近 100 条命令，保留未执行草稿，并经同一输入差量路径将选中命令写入 Shell。独立“选择输出”页读取当前 xterm 渲染缓冲区的文本快照，支持大小写不敏感搜索、匹配高亮和数量、滚动选择及复制全部当前输出；返回后原会话继续。输出快照阅读列最大宽度为 840 dp，交互终端本身保留整页宽度。停止 Activity 时关闭 PTY 并回收子进程，前后台切换暂停网络轮询。尚不实现后台终端、自动启动、联网同步、修改路由或防火墙。

概览、设备、网络、工作台和连接页的静态标题与提示使用简短的功能文案；各项采集来源、时间、权限与失败状态仍在对应内容中显示。终端输入框不显示说明文字，“选择输出”入口位于会话顶部“换用”右侧。

## 页面视觉收敛（2026-09-28）

Material 3 `CardThemeData` 统一概览、信息与设备页面的面板表面、圆角和间距；设备标题与能力状态文字接入同一层级。概览、信息和设备阅读内容居中，最大宽度 840 dp；工作台沿用原有宽度约束。连接结构化列表继续来自同一个 `ConnectionSnapshotState`，视觉调整不新增采集源或权限。实现与窄屏、大字号、Android 11 设备的实际覆盖见 [界面与终端任务](../.trellis/tasks/archive/2026-09/09-25-interface-terminal-polish/check.md) 和 [验证记录](verification.md)。

## Portable 五项工具扩展（2026-09-26）

`ctos_tools` 分离核心算法、SDK adapters 和 CLI，Flutter 工作台拆成目录/模型/表单/脚本/服务模块。`ToolFiles` 管理私有文件 token；`HftpBridge` 处理 Activity 配置与权限请求，`HftpService` 独立拥有前台通知、长期 Python 进程及目录 broker。短任务后台取消与 HFTP 后台运行分开。完整边界见 [portable-tools.md](portable-tools.md)。

## 工作台展示与输入投影（2026-09-27）

当前目录为九项，常用工具优先。`ParameterPresentation` 集中处理已知字段的中文标签、枚举与辅助说明；`ScriptPage.submission()` 从保留的表单控制器生成请求，仅对编码页将非活动输入来源置空。两者只负责 UI 投影，执行白名单仍来自 Python 注册表。`ResultCard` 负责有用结果、直接复制、原 token 产物导出和次级原始 JSON，保持完整结果 envelope。追加图片反馈后以共享控件统一按钮/弹窗宽度，秘密输入在 Flutter 呈现层遮挡并使用普通 IME。HFTP 增加持久配置/上传限额和 SAF 目录后端：HTTP 和工具算法继续是 Python，Java 负责 Android 权限、通知、进程及 DocumentsContract 流式读写桥接。SDK、Root、终端及运行依赖不变；交互和服务边界见 [工具设计](portable-tools.md)。

## 工具Root能力代理（2026-09-27，本轮源码）

用户追加要求后，host-owned `ToolExecutionContext.declare(id, requirement)`以固定操作白名单声明APP/ROOT需求。声明不授予权限；`RootOperationAdapter`在效果边界检查声明、固定APK程序与参数，独立拥有授权请求和子进程资源。首个接入是可选`hftp.networkRelay`，只代理网络；`hftp`与Python SDK Context仍为App权限，默认路径不探测或启动中继。缺少Root/拒绝只令中继失败，保留普通工具使用。既有collector与PTY仍使用各自边界，不宣称已全部迁移到此adapter。

Root模式下App Python先在127.0.0.1随机端口就绪；UID0 native helper通过公开Network handle绑定实际Wi-Fi监听，限制同子网并转发到唯一App backend。生产helper拒绝非UID0执行，adapter复核ready的UID/地址/端口；它不处理HTTP或文件，不提供任意Shell/目标。专用控制pipe/心跳/限时与Service停止负责精确资源回收；不修改VPN或系统网络规则。HFTP日志由Python结构化事件和Java有界会话缓存提供，UI可复制/清空且停止后保留。接口、限制与实际验收分别见[工具设计](portable-tools.md)、[后端spec](../.trellis/spec/backend/python-workbench.md)及[验证记录](verification.md)。

2026-09-28：Root adapter追加App PARTIAL_WAKE_LOCK，仅当前Root会话持有、最多5h无续期、构造/就绪/回调失败及停止释放。它不扩大Root电源权限，普通App路径不取锁。TARGET-PHONE锁归属和回收通过，但熄屏仍断连，不能据此声明idle保活；用户已选择暂不扩大Root范围，独立Root电源候选未实现。

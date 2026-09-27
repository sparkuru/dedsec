# Design

## Approved direction

用户批准前一轮基于 TARGET-PHONE 的六项建议并要求规划与实现。沿用 Flutter、深色/薄荷绿、Material、840dp 内容上限。UUPM 本地检索支持可见标签、合适键盘、渐进披露和表单；Newsletter landing、字体和网页动效不适用于本项目，未采纳或建立第二套设计系统。

## Boundaries

范围为 lib/workbench.dart、lib/workbench/ 和 Flutter 测试。原生/Python/SDK/CLI 保持不变。按 catalogue 分类组织工具；已知 script/parameter ID 仅用于展示投影，不建立第二个执行注册表；未知字段继续使用元数据和通用结果回退。

## Layout and interaction

- 首页：标题 → 常用工具紧凑列表 → 其他脚本/环境次级入口；临时文件放次级管理动作，不再抢占首屏。包版本/ID 放环境或技术详情，信息来源和结果时刻仍可查。
- 表单：密码基础项先展示，高级项可展开且值不丢失。编码文本/文件来源选择只渲染活动来源但保留两个草稿；提交将非活动来源置空，避免旧 token 覆盖文本。哈希隐藏方向但发送兼容默认值。
- 选择文件来源后必须有选定 token 才提交；取消/清空后提示选择文件，而不是转换空输入。此为前端当前来源校验，不改变 SDK 的 file 可选元数据；文本模式保留原有空文本行为。密码基础项错误只在基础项提示，隐藏高级项错误才展开对应区域。
- 参数中文/枚举投影集中在展示模型附近；不改变后端值或 catalogue。数字键盘及范围校验只用于已知密码长度。文件行伸展至宽度并左对齐。
- 专用结果组件优先目标值；artifact 文件卡使用原 token 导出；IP 读取 data 嵌套 API 字段，缺失不伪造。完整 envelope、日志和 JSON 操作放次级详情。
- IP 字段已对照 [FreeIPAPI 官方 response](https://freeipapi.com/docs/api-reference/get-ip-info)：ipAddress、countryName、regionName、cityName、timeZones（列表）、asn/asnOrganization。查询结果为 `{target, resolved, source, data}`，不得把 provider 字段误读成顶层，也不把地理信息当成精确设备位置。
- 密码默认不出现在可见原始 JSON 中；显示是显式动作。遮挡时允许直接复制密码并反馈；JSON 导出仍是用户明确选择的完整协议行为，不自动持久化。
- HFTP 状态/主动作前置，配置/共享库分区；保留 busy/starting、通知检查、LAN 提示、停止/超时及离开不停止，不自动开服务。

## Validation and rollback

行为测试覆盖目录顺序、实际 payload、折叠数据、来源切换、目标复制、遮挡、artifact 导出及 HFTP 明确操作。涵盖 375dp/横屏/2倍字号/低动画，运行 analyze/full tests/lint/current。无存储迁移，回滚仅恢复本任务 Flutter 文件；设备覆盖安装保留数据且须本轮授权。

## 图片反馈修订设计

2026-09-27 九张图片追加要求扩大到 App HFTP 原生/Python 层，优先于上方初始 UI-only 边界。普通工具 SDK、Root、终端及依赖不改变。

UI：名称投影去数字；删除 script ID 技术详情。文本操作按钮占满内容宽度；下拉弹窗与控件同宽。秘密输入采用普通 IME、仅在 Flutter 文本呈现层遮挡，保留 controller 原值、选区和组合输入；禁止用全局键盘设置规避问题。

实机修订：enableSuggestions=false 即使 obscureText=false 也被 Flutter Android 引擎转为 VISIBLE_PASSWORD，TARGET-PHONE 因而启用 Secure Keyboard。保留 enableSuggestions=true，关闭自动更正、个性化学习及智能引号/破折号；普通输入法可能显示建议。4d99… 版本已确认 inputType=0x1 和原有普通输入法，最终包另行复验。

HFTP Channel：新增 `hftpConfig`、`hftpPickDirectory`、`hftpUseDefaultDirectory`，配置返回 `{host, port, maxUploadMiB, directoryName, treeUri}`，默认 treeUri 为空。`hftpStart` 接收 `{host, port, maxUploadMiB, treeUri}`。配置及目录选择持久化，运行期间锁定更改，目录取消不改变选择；撤销授权显式报错。状态包含 maxUploadMiB、directoryName，不再包含 App 凭据。

SAF：支持本机 ExternalStorageProvider 与 DownloadStorageProvider 的 ACTION_OPEN_DOCUMENT_TREE，持久 read/write grant。Downloads 文档 ID 不解析；所有 provider 都需 canonical containment，ExternalStorage 另加 ID prefix 校验。首次选择器定位本机 Download，仅为显示 hint。MainActivity 将结果交给 HftpBridge；service owns native directory broker and Python process. Native broker 使用随机名 abstract AF_UNIX socket，校验 peer App UID，JSON header + streaming bytes；只支持已授权树下 stat/list/read/write/mkdir，拒绝隐藏项、路径穿越、深度越界，最多四并发、15 秒 idle，停止关闭 socket/workers。Python provider 抽象保留 Path 私有目录和 SAF 两种后端。无需新增 Android 权限或依赖。

SAF 上传先写自己创建的隐藏临时 document，完整接收后使用 provider.createDocument 独占创建目标并核对精确名称，再复制完整临时内容；失败只删除本请求自建 target/partial，禁止覆盖用户已有文件。AOSP renameDocument 的 unique-check 与 rename 存在外部写入竞争，因此不用于无覆盖承诺。最终复制期间 HFTP 屏蔽正在写入的目标；其他本机 App 仍可能看见它，SAF 不宣称原子发布。用户选目录后停止/失败不会扫描或清理该目录的其他文件。

限额：App HFTP 上传 1–1024 MiB；私有 HFTP 总配额为 max(128 MiB, 4×上传限额)，上限 4 GiB；选中目录不把已有用户文件计入私有库配额。普通 ToolFiles 32/128 MiB 不变。下载保留独立有界限制，不随较小上传限额缩小。CLI 仍支持原有认证，App JSON 配置不传密码。服务器页面反映实际限额。

网络：实际 bind 参数与返回地址一致；优先实际 Wi-Fi/LAN 地址，避免把 VPN/tun 地址称为局域网。使用不读取目录内容的 HTTP 请求比较 LAN 与 ADB 转发，定位超时；不改 VPN/防火墙或其他应用配置。

## HFTP 访问与日志修订

日志采用Python结构化行输出和原生有界会话缓存，ready仍为stdout首行；后续请求/上传下载日志实时汇入Channel。Java记录准备/配置/监听/进程退出/停止及错误，排除完整SAF URI和私有路径。状态轮询提供日志快照（或专用有界日志方法），单次最多200条/约32KiB；新启动清空旧会话，停止保留当前日志，清空只清日志。源会话token校验覆盖stdout/stderr与延迟回调，旧进程不能写入新日志。

UI在HFTP页追加服务日志区，可展开/选择文本，提供全宽复制和清空按钮；不使用WebView/终端权限、不落盘、无新依赖。已有状态revision保护适用于日志，页面返回只停止轮询。日志包含timestamp、request method、query-free相对路径、status、remoteIP及明确错误类型，不含认证头/请求体/密码；所有动态字段有长度与控制字符限制。外部请求没有抵达日志时不能自动断言具体防火墙或VPN原因。

## 已授权Root LAN模式

用户明确授权后追加 `rootRelay:boolean` 配置/启动字段（默认为false，向后兼容），界面显示可选Root LAN模式，只在0.0.0.0局域网下可用。真实服务仍以App UID启动Python/SAF，并在127.0.0.1:0获取实际backend端口；Root native executable只做流式网络relay。Java选择当前非VPN物理Wi-Fi IPv4地址/前缀与Network handle；relay通过android_setsocknetwork绑定listener，监听选定Wi-Fi地址及用户配置端口，并限制同子网peer。到App backend的loopback socket不绑定Wi-Fi，保持本机路由。Root失败/网路失效明确停服务，不能自动降级。

relay由ctOS的RootOperationAdapter通过专用su子进程启动，APK路径可靠shell引用，参数只为已校验数字/IP；stdin控制channel保持并以EOF/heartbeat超时停止，退出关闭ownedsocket与workers、不后台daemon化。Service.stop/timeout/Activity外生命周期均沿用已有HFTP合同，专用Root子进程不复用collector会话。状态保留外部port及真实URL，追加rootRelay字段与网络relay日志；不能把Python ready当作relay ready。来源和Root候选限制见research/vpn-lockdown.md；实际成功需TARGET-PHONE和跨设备验证，不改VPN/iptables/rules/SELinux。

## 执行上下文与Root代理边界

### 熄屏后续候选：Root临时CPU租约（用户选择暂不扩大Root范围，未实现）

9ca71e…包仍在熄屏后拒绝连接；App锁登记存在但没有ACQ标记，ROM记录屏幕关闭约11秒后REL，随后lightIdle=true。这与系统在idle忽略App锁相符，尚不能断定唯一ROM机制。App锁回收测试通过不代表CPU保活通过。

候选是单独声明`hftp.powerLease` Root能力，由ctOS adapter代理，只允许当前HFTP helper对固定`/sys/power/wake_lock`与`/sys/power/wake_unlock`写入自身生成的唯一tag，不接受文件路径/命令。内核租约30秒；仅owner存活且原10秒心跳有效时续期，整个服务仍5小时上限。STOP/EOF/失败主动释放，helper异常死亡后内核期限兜底；接口不支持或拒绝时显式失败。App Python/SAF不提权，屏幕仍可关闭，不写电池豁免、VPN/Clash/路由/防火墙/SELinux策略。计划添加独立可选项并说明耗电影响，默认关闭；未把现有Root网络授权扩张为电源控制授权。

2026-09-28用户明确选择“保留当前停止修复，暂不扩大Root范围”。候选搁置，未实现或触发；不再等待本次授权，不以此阻止保留停止修复。未来重新授权扩大Root范围后才可实现/实机触发。只读App shell检查两个sysfs节点返回Permission denied，尚未Root探测/写入；可行性、ROM冻结影响及真实异常回收均待验证。若仅RootCPU租约仍不能使App工作，不转移文件权限或继续扩大Root权限。

停止反馈追加R24/R25：有界控制队列先于原10秒心跳判定；closed事件显示固定allowlist原因/errno。Root adapter在能力、网络与APK程序检查之后取得App PARTIAL_WAKE_LOCK，固定tag、最多5小时无续期；构造/ready失败与close都回收，异常回调不可阻断子进程/CPU释放。普通App路径不创建adapter、不取CPU锁。此锁只维持用户启动的可见服务CPU工作，不使屏幕常亮，不申请电池豁免、不更改系统策略。手机168cedec包仅queuedPING修复仍熄屏退出，新增锁须新包实测，不能提前标通过。

新增host-owned ToolExecutionContext声明operation及APP/ROOT requirement；声明只描述需求，不授予权限。普通hftp上下文为APP，Python与SAF不获取Root句柄。用户选择中继后创建hftp.networkRelay的ROOT上下文，经RootOperationAdapter校验allowlist与能力匹配，再调用具体HftpRootRelay。未知operation、缺失/不匹配能力在效果发生前拒绝。adapter承载固定APK helper、参数验证、Root请求及owned进程资源；不提供任意Shell/文件/网络目标接口。

默认App分支不创建或调用Root代理，无Root/拒绝授权只令中继失败，可停止后使用普通模式。现有采集/PTY不在本轮改造范围，不以此宣称全部Root入口已迁移。页面只保留决定操作所需的简短权限与失败说明，架构与验证细节由design/spec承载。

## 停止反馈修订设计

Bridge/通知的显式停止入口在异步销毁前建立 stop-request 边界，状态为 stopping，立即清当前 reason 并使旧 ready/error/EOF 回调失效。关闭完成前，start/config 操作仍视为占用；失败清理亦保有 owned instance，不能只根据 failed 状态允许覆盖字段。正常完成变为 stopped，日志保留。Dart 将 stopping 显示为「停止中」，不允许重复停止/启动或改配置。

native listener 在 bind 前使用 checked SO_REUSEADDR，以支持新版本自身真实 TCP TIME_WAIT 下即时重启；不得使用 SO_REUSEPORT、抢占活跃 socket 或调整系统设置。旧版本未启用 reuse 的 TIME_WAIT 可能仍需自然过期，不能将此独立重启问题作为用户立即停止错误的唯一解释。

日志 ExpansionTile 的 bool 状态与日志 ScrollPosition 的 double 状态有独立 PageStorage 标识，SelectableText 内部滚动也独立隔离。回归必须保留同一 bucket，模拟滚动/折叠后重建、清空/追加和停止；只做初次静态渲染不能发现该冲突。

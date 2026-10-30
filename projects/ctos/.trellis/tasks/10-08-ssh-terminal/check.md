# 初步规划检查

## 2026-10-30 正式安装与人工验收门通过

主会话install-r更新正式7c38并核对设备哈希；UID10131/数据目录/首次安装时间前后一致。
用户明确反馈“效果可以；提交”，人工体验及本轮提交条件满足。之前记录的源码/质量/
隔离设备检查不变；按已复核49路径提交，README其他task三行及整个app-detail-fixes、
邻项目改动排除。父完整task保持in_progress，不归档。

## 2026-10-30 指定开发板：新锁屏断开/真实一键重连通过

独立reviewer核验22项最终来源/APK表均保持，fixture447c748…相同；新设备日志70.244秒、
method status0/OK1，五个锁屏关闭/正常解锁/实际一键重连公开阶段完整。源码确认实际
Power/Keyguard/vault锁定、旧owner释放/CPU锁释放/旧输入拒绝后才正常解锁；解锁设备、
重建和解锁库均不自动连接，真实Flutter按钮才建立新代次/native RUNNING及PTY，旧receiver
不能取消新owner；原Home/重建/通知Stop/config及stale binding检查同一整轮通过。

主会话清理全部隔离资源，正式373前后hash一致/UID10131保持。未重复本地检查或安装正式
新APK，人工体验/父完整AC及提交边界保持。[完整证据](../../../design/verification.md#20261030-ssh-lock-disconnect-board)。
用户要求以统一模型收敛，不再把手机/逐版本矩阵作为前置；不推断Android14/15等未测版本不能用。

## 2026-10-28 锁屏断开/用户一键重连：本地质量门通过，实机待入口

独立reviewer核对本切片backend/frontend源码、最终产物/源码哈希与实际日志，无剩余具体
发现。复核中发现重复锁屏覆盖最终closed回执的问题，已以实际controller.closed为准修复
并补输出ack延迟的真实Port回归；owner取消后仍始终确认丢弃输出。元数据严格非秘密，
新资料/选择/端点及新binding、信任认证继续检查；显式一次授权不会在后台/重读后重试。

主会话实际运行分析、65定向/293全量Flutter、29定向/775全量JVM、normal/fixture/test
构建lint及官方v2；原生输入哈希保持，未重跑ARM矩阵。lint为normal0错4警告、fixture0错
6警告；其中StaticFieldLeak经核对只有applicationContext且按owner释放，不持有Activity，
未抑制警告。完整证据见[verification](../../../design/verification.md#20261028-ssh-lock-disconnect)。

手机安装前离线，重连No route to host；新版未安装，不能把夹具源码路径或widget通过说成
新行为手机通过。当前等待用户确认可用ADB地址；临时服务/网络/代理、合成密钥/JSON及
专用ADB已清。正式应用未更新，human-required与父完整AC保持，本轮不提交或归档。

## 2026-10-28 手机权限拒绝状态与窄屏IME检查

独立reviewer确认夹具仅在自身当前已附着/聚焦窗口真实IME可见时发送正常BACK，等待
IME隐藏、概览可见并保留终端路由；不改产品/系统设置或放宽会话、锁库和关闭断言。
初轮19.065秒失败保留，最终10.594秒/status0/OK1且三个实际IME阶段齐全，四项来源/APK
哈希、14秒构建/83任务、lint0/5、官方v2及实际权限前后false核验通过。正式042哈希及
UID10497前后一致，清理后只有正式包，reverse为空，本地合成请求/客户端私钥已删。
[完整证据](../../../design/verification.md#20261028-ssh-phone-runtime-denied)。结论只覆盖实际
拒绝状态，不包含权限弹窗按钮交互、手机物理锁屏或Doze，继续human-required/父AC开放。

## 2026-10-28 开发板通道关闭：源码与实际证据复核

独立reviewer确认通道模式实际API28+/importance0前置条件、两拒绝flag互斥、无设置修改，
API30不检查不存在的运行时权限；保持真实Flutter sink直到应用内停止/CPU锁释放。
同一严格源码首轮6.937秒失败保留，实际首次FGS后重要性从0变2；正常Settings重新关闭，
实际0/userLocked4/fgShown后18.907秒/status0/OK1通过。没有改变断言或推断生产修复。
reviewer实际核对构建15秒/83任务、lint/v2、四项来源/包哈希，以及正式373前后相同和
清理后包列表。完整边界见[verification](../../../design/verification.md#20261028-ssh-board-channel-denied)。
本轮已清理隔离资源；手机/API36权限拒绝、长时Doze与最终人工体验仍待验，不提交归档。

## 2026-10-28 通知拒绝夹具：本地准备检查

沿当前R8接续，独立reviewer核对`ctosSshNotificationsDenied=true`的API33+/真实拒绝与
互斥参数门槛、无权限修改、无通知查询/PendingIntent，以及真实Flutter sink保留至关闭后
才替换、同代次/锁库/CPU锁释放断言；无具体待修发现。主会话实际隔离测试构建/lint
33s/83任务通过（0错误5警告），新测试APK ec6583d7…官方v2通过，正常a43/隔离66保持。
[完整命令/哈希/边界](../../../design/verification.md#20261028-ssh-notification-denied-preparation)。
本轮没有设备执行，不把编译或旧widget结果标为API36/通知拒绝通过。历史两份/tmp目录已
不存在，旧APK哈希可核验，原始日志和source-final表不可复核。human-required与父AC保持。

## 2026-10-27 安全锁屏/真实熄屏：最终证据复核

用户本轮另行授权后，主会话在同一 `192.168.9.14:5555`、AIO-3568J/API30/arm64
完成真实安全锁屏场景。证据 `/tmp/ctos-ssh-lockscreen-20261027.QravLlGz/` 的
`android-background-live.log` 与 `android-background.log` 是同一次运行的流式/汇总记录：
方法 status0、**OK(1 test)、85.464s**，不重复计算为两次通过。

公开阶段 `screen_locked`、`screen_off_roundtrip`、`await_device_unlock`、
`device_unlocked`、`screen_lock_pass` 均齐全。对应固定源码确认使用真实 SLEEP/WAKEUP
输入及公共 PowerManager/KeyguardManager 状态，反复断言屏幕非交互、安全设备实际锁定、
库已锁、同一 SSH generation 和服务自身 CPU锁；保持熄屏至少15秒，前后实际 PTY
分段标记通过。随后正常系统解锁，应用库保持锁定、同一SSH继续；原有 Home/超时锁库、
真实 Flutter 保留输出与输入、Activity 重建、通知返回/Stop/旧 Stop 隔离仍继续通过。

设备解锁仅由主会话在本轮授权下通过正常 SystemUI 入口完成；fixture 不接收、保存或
输入设备解锁凭据，只在真实 SystemUI 密码/PIN控件可见后发布公开就绪阶段，并等待
公共锁定状态解除。没有伪造锁定字段、修改安全策略或绕过认证；本 reviewer 未读取或
处理解锁凭据，也未进行设备/构建操作。

独立核验 `source-final-sha256.json` 的23份当前源码/测试全部一致。正常 APK仍为
`a43be2511e4b44f2aff6b0c348bb0cf548abccf2f68d20ba0b2fbbed2d2ac591`，隔离应用仍为
`66e1009c4085561655b843c0eb327777ec2fd5614f931ce79483fa536b4b3542`，仅instrumentation
扩展后的测试 APK为 `4d712a28c9695f8f765a72f97957b967ad239e09456009d5e926ad2e3256f5fb`，
与实际本地产物哈希一致、官方 v2 验签通过。`android-physical-lock-build.log`：111 tasks、
15s通过，隔离 lint通过；复用此前已核验的原生生成物，生产源码/原生没有为了本次通过
发生变化。

`device-cleanup.json` 为 passed=true、formal_apk_unchanged=true、请求/容器/网络已清理、
cleanup_errors=[]，安装包列表恢复到仅正式 `im.majo.ctos`；`owned-adb-cleanup.json`
确认本次自有进程停止且全局 ADB未变。正式设备仍保留旧 `373f7161…`，并非本轮新版安装。

这次补齐了该 API30 开发板真实安全锁屏/短时熄屏验收，替代下方10-26时“物理锁屏未验”
的当前边界。**长时 Doze、API36/手机、通知权限拒绝、最终人工观感仍未验**，审阅分类
继续 human-required、父完整AC6保持开放。前次真实UI的20.843s超时仍是原因未明的历史
失败，不因本次通过推断其根因已解决。本次无其他具体待修生产问题，`git diff --check`
通过；不推定正式安装、提交或归档授权。

## 2026-10-26 后台会话与通知 Stop：独立源码复核

复核当前 R8 合同及原生/Java、服务/MainActivity、Flutter 与测试的完整本轮差异，
保留其他任务修改。以下为本轮结果，下方均是各日期的历史记录。

已直接修复：

- `SshNativeSession` 认证后 promotion 若文件视图释放或认证来源清理失败，原先只禁止
  输出发布，其他 facade I/O 仍可查询。`requireTransportLive` 现在必须确认
  `authenticationRetired`，测试在三类失败后确认 step/status/write 全部拒绝且无原生调用，
  再由原 owner 清理。取消场景保留 CANCELLED，其他失败保留阶段拒绝。
- 旧连接的迟到取消可调用旧 owner 的生命周期回调，原通知更新/释放只看全局当前会话。
  服务 lifecycle 现在捕获并校验精确 owner；已释放 owner 不再发布 Stop 生命周期。
  新 JVM 回归确认 factory 的迟到取消不会再发布通知状态或创建/关闭额外资源。
- 返回通知的 Intent.data 会由实际 Flutter embedding 当成路由处理。返回动作改用会话
  特定 MIME discriminator 且 data=null，保持 immutable PendingIntent 身份隔离；隔离
  Android 场景补实际 resumed Activity 的 data/MIME 断言。Stop 的精确会话 URI 保留。

源码核对通过：只有实际 SUCCESS 才释放 native view borrow 并保留精确 transport claim；
认证前 role/view/selection/deadline 不放宽，认证后新秘密/配置/信任 callback 拒绝且不会
因正常拒绝毒化已建 shell。PID/UID、原线程、角色/claim、会话/KEX/取消和关闭重试保留。
服务独立、非导出、非 sticky，通知无账号/输出，Activity 仅订阅，RAM 输出有界且关闭擦除；
snapshot 重置 UTF-8/视图/草稿与排队输入，回放时禁自动终端响应，通知权限拒绝保留
应用内关闭。App/Root 原生命周期保持。新 backend spec 已同步相关执行合同。

实际检查由主会话串行执行；本 reviewer 未并行启动 hako 或访问设备：

- `flutter-final.log`：analyze 无问题；完整 Flutter **279 通过**。
- `android-final.log`：95 tasks、1m32s，JVM **765/0 failures/0 errors/0 skipped**，
  role/view/callback 门禁、Android lint 与正常 APK 构建通过。
- `native-arm-final.log`：同一严格构建的实际静态 ARM/QEMU 合成矩阵 **1,758,973 checks**
  通过。初次 sandbox 初始化失败和60秒超时不计通过，最终240秒范围的完整结果单列。
- 本轮差异 `git diff --check` 通过。

主会话最终补验：`android-final-fixture.log`正常92任务/1m39s、隔离114任务/33s通过；
最终archive重建的ARM程序再次1755235 checks通过，23项inputs无漂移。
`android-normal-aligned.log`89任务/10s复用已验证原生生成物对齐封装通过；正常/fixture
SSH库字节一致，APK均官方验签通过。正常a43be251…、fixture66e1009c…、test adedeb63…，
完整hash见design/verification.md#20261026-ssh-background和`apk-final.json`。
最终23份变更source/test已冻结核验（追加共享UI helper所属测试源），最终test官方v2
验签通过。证据目录 `/tmp/ctos-ssh-background-20261026.3D0HonR1/`。

### 当前授权开发板实测与最终证据复核

主会话在本轮明确授权的 `192.168.9.14:5555` 重新核对 AIO-3568J/API30/arm64，
使用隔离包、临时密钥与独立 SSH 服务；未读取个人凭据。reviewer 仅检查源码和本地
证据，没有自行运行构建或设备操作。

| 实际运行 | 结果与边界 |
| --- | --- |
| 首轮 bridge/background | 在重建后立即检查 RUNNING 时失败，不计通过；修正为等待真实 Dart EventChannel 订阅后仍由实际服务 snapshot 提供状态，没有伪造 running |
| `device-final/android-background.log` | 方法 status0、OK(1 test)、14.833s；真实 Home、2秒后台库超时、锁库后命令、实际通知返回、同代次 Activity 重建、snapshot、Stop/CPU锁释放及旧 Stop 隔离通过 |
| `device-ui/android-background.log` | 扩展真实 Flutter 场景首轮20.843s失败：等待“关闭会话”超时，status-2；原因仍未确定，不推断为焦点/时序根因，也不计通过 |
| `device-ui-diag/android-background.log` | 同生产应用/原生字节、仅追加安全诊断的测试APK，方法 status0、OK(1 test)、18.802s；真实 Flutter 保留输出、重建后实际输入执行/输出、概览导航再由通知返回均通过，并继续通过原 bridge/Stop/旧通知场景 |
| `device-ui-repeat/android-background.log` | 同一诊断测试APK重复运行，方法 status0、OK(1 test)、19.211s，完整相同场景通过 |

两次扩展通过均使用正常 APK `a43be251…`、隔离应用 `66e1009c…` 对应同一生产源码，
测试 APK 为 `d1d4aacf76a90f2f1170c3ccc2c19affdd995d918752b9d8b5643660a823e151`。
两份 `apk-final.json` 的 SSH native 字节相同，22项原冻结 source 无漂移。新增两份
instrument 文件只扩展真实 UI 验证及诊断；没有生产修改来换取后续通过。

诊断源码复核：snapshot 只记录允许的状态、代次、字节数和合成 marker 是否存在，临时
snapshot副本/marker字节 finally擦除；不记录正文、命令或凭据。失败截图限单个自有
fixture前台、捕获前后均核对包名，写自有固定文件，不遍历其他应用。诊断异常不替换
原始失败，原断言照常重新抛出。真实 UI 断言在任何重建后 sink 拦截之前执行；输出标记
不能由命令回显满足。没有跳过或放宽原 snapshot、库锁定、命令、代次及 Stop 断言。

`device-final`、`device-ui-diag`、`device-ui-repeat` 的 `device-cleanup.json` 均为
passed=true、正式包保持不变、请求/容器/网络清理完成、cleanup_errors=[]，包列表恢复到
只有正式 `im.majo.ctos`；各自 owned-adb-cleanup 均确认自有进程停止且全局 ADB 未变。
正式开发板仍为原 `373f7161…`，不是安装了本轮正常新版。

最终源码/本地与上述设备证据无其他具体待修生产问题；初次扩展 UI 超时仍作为未解释
历史失败保留，不能由重复通过声称其根因已解决。审阅分类仍为 **human-required**：
实际物理锁屏/熄屏和长时 Doze、API36设备、通知抽屉人工操作/权限拒绝及最终人工观感
未在本轮完成，不能由 API30 的 Home/超时锁库替代。父完整 AC6 保持开放；本节不推定
正式安装、提交或归档授权。

当前阶段说明：下列各节是按当时状态记录的历史。2026-10-08 用户随后在 Authenticator 讨论明确统一秘密模型，旧完整最终审阅候选已失效；最新共同规划见本文末尾和 [公共秘密库](../../../design/app-authentication.md)。

2026-10-08。这里只记录 planning 文档检查，不是 SSH 产品质量门槛通过。

- 读取项目 design 入口、规划/约束、相关 spec、源码与现有 task 记录；核实已有 App/Root 终端和 APK 内运行环境，记录 onStop 与历史文档的差异。
- 查阅 OpenSSH、dartssh2、Paramiko 官方文档和 Android OpenSSH 构建研究入口，来源见 [design.md](design.md)。没有安装依赖、运行外部示例或连接设备。
- 本地 UUPM design-system 与 Flutter stack 查询已执行；规划采用等待/失败恢复、列表及可访问性建议，继续沿用项目主题。
- 规划文件本地相对链接、JSON/JSONL 格式、上下文文件存在性及 `git diff --check` 已检查；task.json 状态仍为 planning。
- 用户选择兼容范围 B、存储方式 A、按建议拒绝配置本地命令及可选长期加密保存密码/口令后已更新三个规划文件：技术主方案 native OpenSSH，用户主动导入到内部私有目录、不持续同步外部目录；Q3、Q4c 未收敛，未做最终 PRD convergence pass，未执行 task.py start，未达到实施审阅门槛。
- 本轮补充 OpenSSH 上游配置求值、用户目录、ProxyJump 派生研究，以及既有 ToolFiles/HFTP SAF 和 Manifest 备份设置的源码检查；将路径管理、只读预览及多跳关闭列入后续探针，没有运行 SSH 命令。
- Flutter/Android 测试、APK 构建、SSH 集成/实机验证本轮均未运行；无产品代码变更。
- 用户追问目录安全性后，核对 Android 内部存储/应用沙箱、SAF、Keystore 和 OpenSSH 官方文档，以及 ctOS PTY UID、Manifest/迁移排除配置。新增 A/B 风险边界及安全默认建议；用户随后选择 A，但其余安全默认不算产品决定，没有实施安全措施或进行设备验收。
- 本轮新增 AC7 导入验收及原子导入/外部变化隔离计划，文档链接/JSON/JSONL 与 diff 检查通过；用户选择 A 未被当作实施授权。
- 用户“按照建议来”对应上轮本地命令问题，已记为 R9/AC8：首版拒绝并诊断，无手动放行，保留受管 ProxyJump。增加根文件/Include/每跳及 none/no 的后续验证场景；本轮仅文档检查，未执行夹具或产品验证，不推定批准其他安全默认或实施。
- 用户允许主动选择长期加密保存密码/私钥口令，已记为 R10/AC9。查阅 Android 官方 Keystore、系统认证及 KeyGenParameterSpec API 文档，补充宿主凭据库、身份绑定、删除/失效、明文使用期及 API 28/29 兼容设计；随后用户已选择生物/系统锁屏凭证验证，未生成密钥、实现认证或实测设备。

- 用户已选连接前生物/系统锁屏凭证验证，新增 R11/AC10 公共认证复用。父 task 拆为公共认证底座/SSH 接入两个子任务，均有 PRD/design/implement 与真实上下文，明写先底座后集成；当前仍为父 planning，未 task.py start。
- 本轮核对宿主/服务接口，查阅 Android 官方生物认证、Keystore 与后台 Activity 限制，记录公共认证作用域、错误、并发/取消、受保护操作和平台测试建议；没有实现接口或强制改造其他生产服务。

## 2026-10-08：后台保留与手动 Stop 规划更新

- 用户选择保留后台 SSH 连接，并要求和已有两个终端统一手动 Stop 心智模型。父 R8/AC6、设计及实施计划、子任务引用和 design 入口已同步；Q3 生命周期已关闭，Q4c 进一步兼容仍待收敛。
- 核实当前终端页面为单活动会话，Stop 与“换用”均调用 stopTerminal；规划沿用显式结束当前会话，导航/后台不主动断开 SSH，不扩大旧 App/Root 原生 PTY 生存期。
- 查阅 Android 官方前台服务类型/启动/时限及 Doze 文档，规划独立非导出 SSH 服务、specialUse 类型适配、通知 Stop、页面重新附着、有界输出及全链路回收；保留连接不延长凭据解锁上下文。
- 仅规划文件检查，不执行 task.py start，不运行产品测试/构建或设备验证。前台服务存活、系统认证、锁屏/Doze 均未实测。
- 本轮文档检查通过：三个 task 均 planning，30 条真实 JSONL 上下文、16 个 Markdown 文件及 116 个本地链接有效；git diff --check 通过，当前任务指针仍为父任务。并行 authenticator 规划未修改。

## 2026-10-08：基础 TCP 转发规划更新

- 用户回复“同意”，明确接受首版 config LocalForward / RemoteForward / DynamicForward 的基础 TCP 转发建议。已同步父 R6/AC3、技术矩阵/数据面验收、实施顺序、SSH 子任务及 design 入口。
- 查阅 OpenSSH 官方 ssh_config，区分默认 loopback、显式监听/GatewayPorts、服务器远端监听策略及 ExitOnForwardFailure 的建立失败语义；非 loopback 监听允许/确认策略未自动选定，仍列 Q4c。
- 本轮只更新规划，三个 task 保持 planning，不运行 task.py start、产品测试/构建、真实转发或设备操作；并行 authenticator 规划未修改。
- 文档检查通过：三个 planning task、30 条真实上下文、16 个 Markdown 文件及 116 个本地链接有效；git diff --check 通过，当前任务指针仍为 SSH 父任务。

## 2026-10-08：对外监听确认规划更新

- 用户选择建议方案：默认仅本机，config 显式要求对外监听时连接前展示暴露范围并确认。父 R6/AC3、设计及实施计划、子任务引用、状态备注和 design 入口已同步。
- 确认绑定本次配置快照/会话/监听集合；拒绝取消本次连接，不静默变换地址，旧会话确认不复用。远端真实监听受 sshd GatewayPorts 决定，官方手册说明 yes 可以强制通配；不能从客户端申请成功推断仅本机，无法证实时说明并纳入暴露确认。
- 剩余首版兼容矩阵已整理为待用户整体确认的建议：普通 Shell/基础 vim/top、常用认证及非执行 config、R5 主机核验策略与 CLI/桌面扩展延后。尚未将建议视为批准，也未完成最终规划审阅或执行 task.py start。
- 本轮只更新规划，未运行产品代码、构建、SSH/转发或设备验证；三个 task 保持 planning，并行 authenticator 规划未修改。
- 文档检查通过：三个 planning task、30 条真实 JSONL 上下文、16 个 Markdown 文件及 116 个本地链接有效；git diff --check 通过，当前任务指针仍为 SSH 父任务。无产品实测结果。

## 2026-10-08：远端输入职责及撤销程序兼容验收

- 用户明确远端程序使用自身操作逻辑，ctOS 不追加；SSH 连接后 ↑/↓ 交远端处理。用户拒绝特定程序的“兼容验收”，认为引入歧义；前一轮 vim/top 范围建议已撤销，不作为实施/验收要求。
- 父 R2/AC2、技术矩阵/交互设计/风险、实施计划、SSH 子任务、状态与 design 入口已同步。SSH 不使用 ctOS 本地命令历史截获，不追加程序模式、提示符推断或行回填。验证只具体描述输入/控制键传输、终端输出渲染、PTY 尺寸和会话隔离。
- 已修订父 JSONL 的终端 spec 上下文说明，保留 App/Root 回归契约，防止将本机历史行为套给 SSH。其余首版范围/主机核验选项仍待收敛，未进行最终规划审阅或 task.py start。
- 仅规划更新，未修改产品代码或执行构建/设备/真实连接；三个 task 均 planning，并行 authenticator 规划未修改。
- 文档检查通过：三个 planning task、30 条真实上下文、16 个 Markdown 文件及 116 个本地链接有效；git diff --check 通过，当前任务指针仍为 SSH 父任务。主规划三个文件已无 vim/top 专属验收建议，未产生产品实测结果。

## 2026-10-08：禁止绕过主机核验及完整规划整理

- 用户回复“可以”，确认 config 不能跳过主机指纹核验。父 R5/AC5、技术矩阵、原生/配置/每跳边界及 SSH 子任务已同步；no/off、自动接受未知等违反该规则的有效选项定位拒绝，不能静默覆盖后声称兼容。
- 重新通读并收敛父 PRD：保留 R1–R11/AC1–AC10、源码锚点、已确认输入/生命周期/导入/执行/长期保存/认证/转发决定；已回答问题移入需求，技术未实测项单列，不把实施关卡当产品未决问题。
- 常用认证与主要 config 具体选项承接最初“基础、主要功能与 OpenSSH 相似”的 UI/config 目标；通用 CLI 和桌面扩展没有从二进制存在推导为新交付。工程资源界限、BUSY/期限、API 与 native 可行性关卡明确，探针失败回规划，不静默改目标。
- 父/子 design、implement、公开 design 入口和状态均同步为完整规划等待最终审阅；六个上下文清单使用真实文件，新增权威父 PRD 引用。尚未展示后得到最终实施批准，三个 task 均 planning，没有 task.py start。
- 本轮仅文档检查，无产品代码、依赖安装、native 构建、系统认证、SSH/转发或设备实测。并行 authenticator 规划未修改。
- 完整规划文档检查通过：三个 planning task 均 awaiting_final_review，父/子依赖及当前父任务指针有效；R1–R11/AC1–AC10 与源码锚点保留；36 条真实上下文、16 个 Markdown 文件及 117 个本地链接有效，git diff --check 通过。没有产品 AC 被标记通过。

## 2026-10-08：统一秘密模型取代分业务凭据方案

- 用户要求将 Authenticator 主口令/便携库/可选设备快捷/覆盖迁移模型应用到 SSH，降低理解和维护成本。公共底座扩展为 AppSecrets + AppAuthentication，SSH 只保留类型化身份 adapter/native 秘密通道，R10/R11 和 AC9/AC10 已更新，其余 R/AC 客户端行为保留。
- 主口令格式、KDF、设备包装、库会话及迁移不再在 SSH 重复实现；旧设备-only 不迁移/每连接系统弹窗方案被统一公共会话取代，库会话锁定/复用与备份外部资源边界仍待收敛。
- 四 task 全部 planning / requirements_reconvergence，任务 ID/树关系保留，依赖顺序公共底座 → TOTP/SSH。原完整最终审阅候选失效；无实现/构建/设备或真实凭据使用。
- 本轮共同文档检查结果见 [Authenticator 共同规划检查](../archive/2026-10/10-08-authenticator/check.md) 和公共底座规划；不将历史 awaiting_final_review 当作当前授权或已收敛结论。

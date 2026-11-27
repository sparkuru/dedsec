# ctOS 信息入口

2026-11-27：按用户要求整理项目根目录的旧 SSH/QEMU core dump，并为同类转储增加忽略规则；[变更记录](changelog.md#2026-11-27-qemu-临时转储整理)。测试单中的原 HEAD 是创建时记录，执行时按[交接协议](agent-test-handoff.md)核对实际快照。

2026-11-27：用户安排其他 agent／session 执行产品测试，Codex 编排并独立审核；[测试交接设计](agent-test-handoff.md)和 [SSH 测试单](../test/codex-wont-do/)已建立，既有通过结果及后续待测范围分别记录。

2026-11-27：SSH 终端已接续同一 session 的明确未开始撤销，进行有限重试并在未知结果时保留 owner；该返回目前由测试钩子构造。[实现、验证与限制](verification.md#20261127-ssh-terminal-session-cancel-retry)／[接管](handoff-ssh-terminal-client.md)。

2026-11-27：SSH 共享 STOP 已完成本地 JVM、JNI 合成链与隔离双 sshd 分层验证；独立撤销、信任写入及设备验证仍开放。[验证和边界](verification.md#20261127-ssh-shared-stop-verification)／[接管](handoff-ssh-terminal-client.md)。

2026-11-27：SSH 为凭据提示与选定保存上下文增加共享 STOP 的局部准入；实施时暂停测试，后续本地检查见最新验证，整链清理仍开放。[实现及边界](verification.md#20261127-ssh-prompt-mutation-stop)／[接管](handoff-ssh-terminal-client.md)。

2026-11-27：SSH 开发按用户要求优先实现、禁止过度测试；此前用户要求的本地验证已有历史结果，当前测试由用户安排其他 agent／session 执行，Codex 负责交接与审核。[SSH 开发规范](../.trellis/spec/backend/ssh-development-workflow.md)。

2026-11-27：SSH 补充已启动材料的局部 STOP 准入、锁库后精确预览退役及锁屏新请求的错误处理；实施时按要求停测，后续本地验证见最新记录。[实现、已完成检查和剩余缺口](verification.md#20261127-ssh-started-material-preview)／[接管](handoff-ssh-terminal-client.md)／[材料合同](../.trellis/spec/backend/ssh-started-material-admission.md)。

2026-11-27：继续 SSH 父任务，服务在取消回调之前发布原 owner，创建结果结算与屏锁／启动失败排序；bridge 对已知回执最多尝试一次回复。已启动材料、整链取消和 Android 生命周期仍待处理。[验证](verification.md#20261127-ssh-result-delivery)／[接管](handoff-ssh-terminal-client.md)／[合同](../.trellis/spec/backend/ssh-connection-result-delivery.md)。

2026-11-27：此前完成未启动 connector 的材料退役与 create／STOP 排序，保留已经接管的 owner。[当时验证](verification.md#20261127-ssh-connector-retirement)／[合同](../.trellis/spec/backend/ssh-connector-unstarted-retirement.md)。

2026-11-27：继续 SSH 父任务，锁库失效或文件视图撤销结果不明时擦除 Java key、保留原 owner；保存结果不确定时保留回执。桥接结果投递和完整链撤销仍待处理。[本轮验证](verification.md#20261127-ssh-vault-revocation)／[接管](handoff-ssh-terminal-client.md)／[合同](../.trellis/spec/backend/ssh-vault-revocation-ownership.md)。

2026-11-25：当时继续 SSH 父任务，停止状态、未附着 owner 清理和连接结果增加顺序保护；桥接结果投递及锁库失败仍有具体未修缺口。[当时验证](verification.md#20261125-ssh-stop-owner-delivery)／[接管](handoff-ssh-terminal-client.md)／[合同](../.trellis/spec/backend/ssh-terminal-stop-intent.md)。

2026-11-25：SSH 增加终端停止准入，单个会话和共享 view 跟踪撤销及未确认所有权；信任回执也不能越过已发布的停止请求。创建期、信任写入、独立 callback／lease 与整链取消仍未统一完成。[信任回执验证](verification.md#20261125-ssh-trust-stop-order)／[会话与视图验证](verification.md#20261125-ssh-session-view-cancel)／[会话合同](../.trellis/spec/backend/ssh-session-cancellation-ownership.md)／[视图合同](../.trellis/spec/backend/ssh-shared-view-cancellation.md)。

2026-11-25：继续 [ProxyJump 首次信任](ssh-proxyjump.md#首次主机信任的下一步设计未实现)，增加测试候选正文的一次性受限转交；产品写入及首次信任仍未开放。[验证与剩余边界](verification.md#20261125-ssh-jump-candidate-transfer)。

2026-11-23：继续 [ProxyJump 首次信任](ssh-proxyjump.md#首次主机信任的下一步设计未实现)，在测试夹具中增加经模拟同意的一次性候选保留；产品信任写入与重连仍未开放。[验证和未完成边界](verification.md#20261123-ssh-jump-candidate-reservation)。

2026-11-23：继续 [ProxyJump 首次信任](ssh-proxyjump.md#首次主机信任的下一步设计未实现)，已在测试专用真实 Java／JNI child 接通签名票据、父链推进和受限输入；产品审核、写入与重连仍未开放。[验证与未测边界](verification.md#20261123-ssh-jump-pending-integrated)。

2026-11-23：继续 [ProxyJump 首次信任](ssh-proxyjump.md#首次主机信任的下一步设计未实现)，增加测试专用签名审阅票据和独立的受限 `EXT_INFO` 输入；产品审核、写入和重连仍未接通。[实际验证及限制](verification.md#20261123-ssh-jump-signed-review-ext)。

2026-11-23：继续 [ProxyJump 首次信任](ssh-proxyjump.md#首次主机信任的下一步设计未实现)，增加内部保守空闲快照与审核轮询骨架；首次确认、信任写入和重连仍未开放。[验证及限制](verification.md#20261123-ssh-jump-pending-wait)。

2026-11-23：逐跳首次信任增加原生签名 KEX 测试状态，并修复子连接异常清理与重试；生产首次确认和写入仍未开放。本机隔离双 sshd、真实 JNI 故障矩阵及最终 Android 构建结果见[验证](verification.md#20261123-ssh-jump-provisional-new)。

2026-11-23：[ProxyJump 受限单跳产品路径](ssh-proxyjump.md#当前边界)已开放已知主机及逐跳确认已保存密码；正式 Java／JNI／native 和加密库已在本机隔离双 sshd 执行目标 PTY。首次主机信任、临时密码、私钥、KBI、多跳和设备验证仍未完成；仅可经跳板到达的新目标尚无完整首次使用流程。[验证与限制](verification.md#20261123-ssh-jump-product-open)。

2026-11-20：受管 ProxyJump 的 [fresh 信任来源、逐跳回执和审阅](ssh-proxyjump.md#连接主线与阻塞顺序2026-11-20)已接入内部 Java／JNI；真实 sshd 对照、主机 JNI、加密库及 JVM／Flutter 分层验证通过。[实际结果和边界](verification.md#20261120-ssh-jump-fresh-jni)。跨跳效果顺序与产品连接仍未完成，入口保持关闭。

2026-11-20：本机双 sshd 增加[旧／新信任快照及错误密钥对照](verification.md#20261120-ssh-jump-fresh-interop)：原生客户端只在新快照信任正确目标时继续认证；该测试不代表加密库或产品连接已联合完成。

2026-11-20：受管 ProxyJump 已增加[严格逐跳路由、链身份与当前跳账号身份](ssh-proxyjump.md#连接主线与阻塞顺序2026-11-20)；密码、私钥口令和服务器问题提示显示实际请求账号。本机 Docker 双 sshd、真实 Java／JNI 角色及配置、加密库分层测试和 Android 构建通过；[证据与未完成边界](verification.md#20261120-ssh-jump-route-role)。产品多跳连接仍未开放。

2026-11-20：实际 Java 终端入口新增[跳板链调度和逆序清理](ssh-proxyjump.md#java-终端的跳板链调度)，并修复直连／跳板快速关闭时可能无限等待的问题；完整 JVM 回归和 release 编译通过，产品跳板入口仍待完成。[验证](verification.md#20261120-ssh-jump-terminal-chain)。

2026-11-20：已建立可重建的[本机隔离 OpenSSH 跳板测试环境](ssh-proxyjump.md#本机隔离-openssh-跳板测试环境)；ctOS ARM64 原生客户端使用不同逐跳账号和密码完成真实单跳，错误目标密钥在密码认证前被拒。产品连接与 Java／vault／设备验证仍待完成。[实际结果](verification.md#20261120-ssh-jump-docker-interop)。

2026-11-20：1008 增加 [Java／JNI 父子会话的内部 owner](ssh-proxyjump.md#jni-父子会话内部接入产品编排未实现)，支持部分创建后的同线程清理重试；产品连接器与跨跳效果仍待接入。[验证](verification.md#20261120-ssh-jump-jni-owner)。

2026-11-20：1008 增加 [Java 共享视图的内部会话接入](ssh-proxyjump.md#java-共享租约的内部接入产品编排未实现)，并明确以单跳连接贯通为下一集成目标；产品连接器和跨跳效果仍未接通。[验证与限制](verification.md#20261120-ssh-shared-java-view)。

2026-11-20：1008 的[受管 ProxyJump](ssh-proxyjump.md#原生已建立链的传输晋升) 新增单一原生受管句柄、三份独立借用的 SSH／JNI 合成验证；产品共享 Java 租约和跨跳效果保护仍待完成。[验证](verification.md#20261120-ssh-shared-view-native)。

2026-11-20：1008 为 [受管 ProxyJump](ssh-proxyjump.md#原生已建立链的传输晋升) 增加目标建立后由内向外的原生传输晋升；共享租约、跨跳效果和产品后台 owner 仍待实现。[本地验证](verification.md#20261120-ssh-jump-retain)。

2026-11-20：1008 增加 [受管 ProxyJump](ssh-proxyjump.md) 的主机 JVM／真实多跳原生联合测试，验证持有 session 锁时的 JNI 祖先检查、取消和测试凭据擦除；跨跳效果保护及产品连接仍待实现。[验证](verification.md#20261120-ssh-callback-jni-multihop)。

2026-11-20：1008 继续 [受管 ProxyJump](ssh-proxyjump.md)，为同步原生回调增加绑定精确 Java 接收者及 JNI 验证器的祖先时点检查；跨跳写入／凭据效果、子连接编排和产品入口仍未完成。[验证与限制](verification.md#20261120-ssh-callback-jni)。

2026-11-20：1008 继续 [受管 ProxyJump](ssh-proxyjump.md)，新增原生子回调内的顺序祖先证明检查，并为现有 SSH 输出与订阅快照增加取消准入；[分层验证及限制](verification.md#20261120-ssh-callback-output)。跨跳写入／凭据 guard、JNI 和产品入口仍未完成。

2026-11-18：1008 继续 [受管 ProxyJump](ssh-proxyjump.md)：原生实时消费者现在独占各自 borrow，并修复现有直连中主机信任写入与取消／屏锁的回执竞态。[验证及边界](verification.md#20261118-ssh-borrow-trust)。跨跳 Java 效果保护和产品连接仍未完成。

2026-11-18：1008 接续 [受管 ProxyJump](ssh-proxyjump.md)，新增内部原生子连接、逐祖先证明和可重试的子／中继／父清理；多层加密合成链测试与完整边界见[验证记录](verification.md#20261118-ssh-proxyjump-child)。跨跳信任／凭据效果、后台生命周期和产品入口仍未完成。

2026-11-18：1008 接续 [受管 ProxyJump](ssh-proxyjump.md)，同快照逐跳配置、受限原生跳板通道及有界本地字节中继已实现；双层合成 SSH 验证独立主机证明、认证及终端数据。当时子会话跨跳证明、全链路生命周期和真实互通尚待完成，产品连接仍拒绝跳板。[结果与边界](verification.md#20261118-ssh-proxyjump-relay)。

2026-11-18：指定板的 Flutter 默认入口经 Android JNI 与隔离 OpenSSH 9.2p1／PAM 完成 [KBI 首方法和密码后同连接 KBI 的端到端测试](verification.md#20261118-ssh-openssh-android-ui)。[独立 KBI 入口暂保留](ssh-server-driven-authentication.md#独立交互认证入口)：配置的方法顺序可能使它仍具有明确选择 KBI 的作用。

2026-11-18：用户确认上一版服务器驱动 SSH 认证[逻辑验收通过](verification.md#20261118-ssh-server-driven-acceptance)。自动密码与私钥口令的保存和复用通过本地回归，候选 `8b7cf8da…` 已保留数据安装并启动。开发板用隔离应用与合成账户完成[密码界面、私钥口令桥接及回归测试](verification.md#20261118-ssh-automatic-credentials-device)。

2026-11-16：用户明确新的[服务器驱动 SSH 认证流程](ssh-server-driven-authentication.md)：
目标和可选私钥先于连接，指纹核验后按服务器要求提示。源码已接入并通过本地分层
回归；自动提示的保存／复用、Android 实际 JNI、设备及真实服务器仍分别待完成或验证。
本地 ARM64 候选已构建但尚未安装。[结果与边界](verification.md#20261116-ssh-server-driven-auth)。

2026-11-16：继续 1008 的 R6／AC3，[RequestTTY no](ssh-request-tty.md) 按配置跳过
PTY 并保留 shell 成功门禁；本地验证及未测范围见[记录](verification.md#20261116-ssh-request-tty)。

2026-11-16：1008 继续覆盖密码／私钥认证启动时的主机证明失败诊断；原认证期限、
取消及不确定写入保护已完成本地回归。[结果和未测范围](verification.md#20261116-ssh-auth-proof-diagnostic)。

2026-11-16：接续 1008，KEX 主机证明绑定失败在原连接有效且本次无受跟踪写入时显示准确诊断；
取消、锁屏和不确定写入的优先级已在本地回归。认证启动阶段后续见上条；Android JNI
与真实服务仍需单独验证，见[结果](verification.md#20261116-ssh-host-proof-diagnostic)。

2026-11-16：保活无应答、后台和锁屏的指定板合成验证取得结果；期间发现并修复
[shell 确认前通道输出的兼容性问题](ssh-shell-early-output.md)。修正版已获授权保留
数据安装，指定板同一时序的连接与输出复测通过，见[验证](verification.md#20261116-ssh-early-output-device)。

2026-11-16：继续 1008 的配置兼容，[SSH 服务器保活](ssh-server-alive.md)已完成本地
实现与分层验证；用户授权后候选已保留数据安装指定板，并通过真实 Android JNI 到
受限合成服务的保活应答验证；后续无应答和生命周期结果见上条。用户本人体验待验证。
[本地结果](verification.md#20261116-ssh-server-alive-local)／
[设备结果](verification.md#20261116-ssh-server-alive-device)。

2026-11-16 反馈：用户体验新版后回复“正常了”，并询问是否能去掉“服务器交互式认证”
入口；当前自动续接与独立首认证的区别见[反馈及核对](verification.md#20261116-ssh-interactive-entry-feedback)。

2026-11-16：用户授权后，新版 `1260aae3…` 已[保留数据更新指定板并启动](verification.md#20261116-ssh-auto-question-delivery)；
限时合成测试服务已开启，后续人工反馈见上条。

2026-11-16 接续 1008：已重新核对新版 APK 和签名，并恢复合成测试夹具及完成本机回环
自检；后续授权、更新及反馈见上两条。[本次检查](verification.md#20261116-ssh-resume)。

2026-11-13 接续：用户提供 [FileZilla 录像并要求自动显示真实服务器问题](verification.md#20261113-filezilla-ssh-reference)，
避免交互式选项拖慢 SSH。源码已改为配置先选目标、连接页再选认证，并在密码／私钥
部分通过后实际需要时弹出服务器问题；连接页及换键弹窗已调整，本地检查和候选构建通过。
当时仅提供 KBI 首方法的服务器仍需选择独立入口；新自动首方法实现及当前状态见本页顶部。
[本次检查与限制](verification.md#20261113-ssh-auto-question-local)。

2026-11-13 反馈：用户指出新版 SSH 连接页、配置选择层级、指纹弹窗和后续认证引导
存在严重体验问题；已沿原 1008 任务[记录需求并研究](verification.md#20261113-ssh-flow-critical-feedback)，
当时尚未修正；最新调整见上条。

2026-11-13 接续：用户授权后，换键一次确认候选已[保留数据更新开发板并启动](verification.md#20261113-ssh-changed-one-decision-install)；
限时合成测试服务曾重启，后已消失；退出原因未确定，实际换键及人工体验待反馈。

2026-11-13 接续：已实现[换键一次确认并继续、配置主机快捷入口](ssh-host-trust-management.md#2026-11-13-换键后继续连接本地实现)；
分层检查通过情况及设备限制见[验证](verification.md#20261113-ssh-changed-one-decision)。

2026-11-13 接续：用户反馈配置主机私钥续接功能通过，但指纹变化的恢复流程点击繁多；
用户选择核对后信任并继续连接；当时方案正在收敛，后续已批准并实施，见上条。
[反馈与范围](verification.md#20261113-ssh-config-passed-flow-feedback)。

2026-11-13 接续：用户确认手动私钥勾选续接后成功；指定板 config 已安全加入并回读
测试主机，后续 config 人工反馈见上条。认证提示及指纹对话框圆角已在源码修正并通过本地
检查，尚未安装／人工验收。[实际操作与范围](verification.md#20261113-ssh-key-config-dialog-feedback)。

2026-11-13 接续：已区分主机证明异常与真实换键；本地合成回调仍可能归为中止，精确
诊断及取消竞争继续开放。[检查与边界](verification.md#20261113-ssh-proof-errors)。

2026-11-13 接续：用户确认密码后继续交互式认证并进入终端通过；按请求已重新开启
[私钥／config 合成测试服务](verification.md#20261113-ssh-mfa-key-manual)，结果待反馈。

2026-11-13：SSH 表单、指纹恢复及其 MFA 依赖已按用户许可[部分提交](verification.md#20261113-ssh-partial-commit)；
新 session 接续先看 [SSH 接管档案](handoff-ssh-terminal-client.md)，父任务继续。

2026-11-13：用户反馈“可以；目前能够接受了”，本轮 SSH 表单与指纹恢复体验
[人工验收可接受](verification.md#20261113-ssh-feedback-human-acceptance)；原 SSH 父任务及
MFA 串联的具体人工流程仍保持开放。

2026-11-13：[指纹原地忘记及连接失败诊断](verification.md#20261113-ssh-feedback-direct-recovery)
已完成438项 Flutter 检查、密码与独立交互式两条指定板隔离界面流程；正常 08881940…
已保留数据更新并启动；13:32:50启动同范围30分钟合成服务，人工反馈见上条。

2026-11-13：[SSH 表单和指纹恢复正常候选已保留数据更新并启动](verification.md#20261113-ssh-ux-formal-install)，
设备公开身份核对通过；同范围合成服务曾重启，后续提前退出且原因未确认，反馈处理结果见上条。

2026-11-13：[SSH 表单及主机指纹变更恢复](verification.md#20261113-ssh-form-host-recovery)已
完成本地回归和指定板隔离界面测试；测试时正式应用仍为旧版，随后按上条更新。

2026-11-13 11:20（新加坡时间）：用户希望延长测试时间，已启动[30分钟合成服务](verification.md#20261113-ssh-manual-fixture-30m)，
用于MFA人工体验；同一地址、端口及开发板来源限制保持。服务已按期退出，MFA人工反馈仍待。

2026-11-13 待办追加：接续 [10-26 应用细节修复](../.trellis/tasks/10-26-app-detail-fixes/prd.md)，
新增全 App 主题切换；深色保留当前外观，增加传统浅色并考虑其他主题色，具体方案待定。
任务仍为 planning，尚未实现。

2026-11-13 11:11（新加坡时间）：独立KBI人工体验获用户确认；
[临时服务重新启动](verification.md#20261113-ssh-mfa-manual-restart)，供后续MFA串联体验。

2026-11-13 11:05（新加坡时间）：用户指出应支持直接交互式认证，先前测试服务只支持
密码后续接，是测试设计错误。[新的合成服务](verification.md#20261113-ssh-direct-kbi-manual-fixture)
已支持独立KBI；用户确认回答问题后进入测试终端，MFA验收仍开放。

2026-11-13 10:56（新加坡时间）：用户授权后，[临时合成MFA服务已启动](verification.md#20261113-ssh-mfa-manual-fixture)，
监听指定地址及端口，限制开发板来源并设置5分钟寿命；人工体验待反馈。

2026-11-13 体验反馈：[再次选择独立KBI未弹问题](verification.md#20261113-ssh-kbi-recheck)；
本机SSH当前配置关闭KBI且不要求MFA，与反馈一致。反馈时临时合成测试夹具只完成本机自测。

2026-11-13 接续1008：[MFA Flutter定向回归](verification.md#20261113-ssh-mfa-resume)43项通过；
历史设备原始证据已不在原临时目录，新增MFA流程人工体验仍待反馈，父任务继续。

2026-11-06 体验反馈：[独立KBI未弹问题即失败](verification.md#20261106-ssh-kbi-no-prompt-feedback)。
截图目标的本机SSH配置关闭KBI，与现象一致；当前提示未区分服务器直接拒绝和回答失败。
本轮只读诊断，未更改服务或产品，新MFA流程人工验收仍开放。

2026-11-06：用户报告设备恢复并要求继续，沿现有授权已将MFA正常418f6d13…
[保留数据更新指定板并启动](verification.md#20261106-ssh-mfa-formal-install)。实际APK／签名匹配，
UID／数据目录／首次安装保持，自有ADB已清理；新增流程人工体验待反馈，原1008继续。
下方不可达／未交付为11-05历史。

2026-11-05：用户另回复“授权”，批准在同一指定板保留数据更新MFA正常418f6d13…并启动。
[正式交付在连接阶段停止](verification.md#20261105-ssh-mfa-formal-install)：预检及一次同址重试
均为`No route to host`，未备份／安装／启动，私有ADB已清理。交付准备及独立复核完成，
现有授权保留，待开发板恢复可达；人工体验／提交／完整父AC仍开放。下方待授权为历史。

2026-11-04 接续1008：原父任务已恢复current；独立KBI提交`bc933b5`。
[密码／私钥后继续交互式认证](ssh-mfa-authentication.md)已完成本地实现，Flutter396／
JVM856、同最终归档原生2687087／64KiB、config7995、36字体渲染、构建lint及三APK审计
通过，独立全范围复核通过；正常候选418f6d13…。用户另回复“授权”后，
**指定板实际MFA方法10.415秒通过**：5次认证／实际PTY、保存复用／取消／重放与配置禁用，
11项清理及独立运行证据复核通过；正式8b6c2a42…保持。
[设备结果与首轮驱动失败](verification.md#20261104-ssh-mfa-device)、
[本地证据／工件](verification.md#20261104-ssh-mfa-local)。正式更新及人工体验待后续授权，原完整父AC保持开放。

2026-11-04：[KBI与界面修正人工验收通过](verification.md#20261104-ssh-kbi-human-acceptance)。
用户反馈“验收通过；可以提交”，正式8b6c2a42…对应的独立交互式认证及三处UI修正
按本次授权提交。完整1008父任务/MFA方法串联仍开放，旁任务和既有工作区修改保留。

2026-11-03：[KBI体验反馈与三处界面修正](verification.md#20261103-ssh-ui-feedback)。
用户确认“其他体验认证通过”；认证选择框/弹窗及下方说明、保存选项圆角状态、私钥操作
一致性已修正。377 Flutter/analyze、42张字体截图及构建/lint/审计通过；正常8b6c2a42…
已保留数据更新指定板并启动，修正后视觉反馈待验。父1008继续，KBI未提交/归档；
下方50614及待体验是本次修正前的交付记录。

2026-11-03：[KBI正式50614d4d…已保留数据更新指定板并启动](verification.md#20261103-ssh-keyboard-interactive-formal-install)。
实际APK/签名与已审阅候选匹配，UID/数据路径/首次安装保持，独立交付复核PASS；
新增页面人工体验待反馈。入口：终端→SSH连接→服务器交互式认证；config主机也可显式选择。
父1008继续，KBI未提交/归档；下方正式未更新/待确认均为本次另行授权前的历史。

2026-11-02：[主机信任正式5e70df40…已保留数据更新开发板并启动](verification.md#20261102-ssh-host-trust-formal-install)，
实际设备APK与已审阅产物匹配，UID/数据路径/首次安装时间保持；用户随后反馈“通过；可以继续；”，
[新增页面人工验收通过](verification.md#20261102-ssh-host-trust-human-acceptance)，沿1008核对下一增量。
主机信任已按批准52路径提交`bb8fa22`；当前开始[服务器交互式认证](ssh-keyboard-interactive.md)，
覆盖手动/config入口与多轮提示，MFA方法串联另行交付；374 Flutter/846 JVM及native/
28张字体布局/构建审计通过，[本地证据与待验范围](verification.md#20261102-ssh-keyboard-interactive)。
KBI[指定板隔离验收已通过并清理](verification.md#20261102-ssh-keyboard-interactive-device)：
实际3/0/1、CHANGED/NEW/KNOWN及三次PTY，方法8.348秒，独立复核PASS。
正常50614d4d…尚未更新正式应用，新增页面人工体验待验；隔离阶段正式5e70保持。
前序353 Flutter／828 JVM、1758432客户端及材料/布局/构建审计、指定板JNI3项和实际换键/
分组忘记/NEW/KNOWN/PTY均通过并清理；[验证及历史失败](verification.md#20261102-ssh-host-trust-device)。
父1008继续，不归档；下方正式未更新或待授权均为本次明确更新授权前的历史。

2026-11-02 当前修正：实际config导入被电脑IdentityFile路径阻断，用户已批准
[先导入连接项、连接时显式选择私钥](ssh-config-identity-selection.md)。保留原路径提示和
CONFIG正文，不做迁移／猜测。324 Flutter／802 JVM、原生回归／16张布局／构建lint／
三APK审计与独立复核通过；正式eed028f0…已保留数据更新开发板并启动。用户随后确认
效果通过并成功连接实际remote，[人工验收](verification.md#20261102-ssh-config-identity-human-acceptance)已记录。
[诊断、检查和安装](verification.md#20261102-ssh-config-identity-selection)。

2026-11-02 用户澄清：“批量”是一个config读取多个Host；取消整目录导入和路径适配。
当前沿原1008收敛为[单配置与独立私钥导入](ssh-key-config-connect.md)，已有配置编辑／
粘贴保留；[目录方案](ssh-directory-import.md)转为历史。移除已完成，318 Flutter／799 JVM、
原生配置及多Host枚举／8张布局／构建lint／v2通过；用户要求更新后，新版e1241897…已保留
数据安装开发板并启动，待体验。[安装核对](verification.md#20261102-ssh-single-config-formal-install)。详情见
[本轮记录](verification.md#20261102-ssh-single-config)。

2026-11-02 接续：已恢复原1008父任务，目录导入定向14 Flutter／20 JVM重新执行通过，
三个本地产物哈希仍匹配10-30交付；旧临时原始证据已不存在，历史设备结果未重验。
[本轮检查与限制](verification.md#20261102-ssh-directory-resume)。目录增量人工体验及提交仍待反馈。

2026-10-30 接续：[SSH目录批量导入](ssh-directory-import.md)已实现受支持资料的相对结构
快照、逐项选择／私钥解锁、整批审阅与一次原子提交。819 JVM／331 Flutter、构建lint／
APK审计、28张实际字体布局及指定板隔离31.649秒通过；两次真实SSH登录，临时资源已清。
随后用户回复“允许”，正式b2d91de2…已保留数据更新同板并启动，待人工体验；
[安装记录](verification.md#20261030-ssh-directory-formal-install)及
[验证范围](verification.md#20261030-ssh-directory-import)。前序密钥交付已提交360cca2／e0f866f。

2026-10-30 人工验收：用户反馈“功能通过；可以提交并继续”，当前正式fc98的加密私钥／
口令管理／删除及卡片调整验收通过并授权提交。[验收范围](verification.md#20261030-ssh-keys-human-acceptance)。
1008父任务保持进行中，提交后继续原规划下一项。

2026-10-30 界面反馈：[私钥卡片操作区](verification.md#20261030-ssh-key-card-actions)改为
内容宽度按钮与自动换行、统一圆角和间距，删除hover不再铺满卡片。317 Flutter及24张
真实字体渲染／APK审计通过；正式fc98已保留数据更新指定板并启动，待实际体验。

2026-10-30 接续：[私钥删除](ssh-key-deletion.md)已获用户继续指示，沿1008父任务补齐。
已实现精确影响确认、库内原子清理、保留外部原文件及 CONFIG、缺失身份拒绝和重导入恢复；
799 JVM／317 Flutter及指定板隔离42.572秒通过，资源已清。
随后用户允许保留数据更新，正式0e8e已安装并启动，UID／数据目录／首次安装时间保持；
[安装记录](verification.md#20261030-ssh-key-delete-formal-install)与
[隔离验证及限制](verification.md#20261030-ssh-key-deletion)分列，待用户实际体验。

2026-10-30 最新：[加密私钥正式APK已保留数据更新开发板](verification.md#20261030-ssh-encrypted-formal-install)，
实际f26a哈希匹配，UID／数据目录／首次安装时间保持，已启动应用；等待用户实际体验。

2026-10-30 接续：[加密私钥与可选口令保存](ssh-encrypted-key-connect.md)已沿1008父任务
实现；304 Flutter／786 JVM、原生矩阵及五类互通通过。指定开发板实际加密导入／
当次连接／保存复用／忘记／重导入回归40.512秒通过，资源已清，隔离阶段正式7c38保持。
[隔离证据及范围](verification.md#20261030-ssh-encrypted-key)与上方正式安装分别记录。

2026-10-30 前序交付：[正式APK已保留数据更新开发板](verification.md#20261030-ssh-formal-install)，
新7c38哈希匹配，UID/数据目录/首次安装时间保持；用户已反馈“效果可以；提交”，本轮交付验收通过。

2026-10-30 当前：[锁屏锁库并断开SSH、实际一键重连](verification.md#20261030-ssh-lock-disconnect-board)
在用户指定开发板70.244秒通过，含新代次PTY、后台/重建及通知Stop回归。统一业务规则，
不将手机或逐版本补验设为当前前置；未测不等于不支持。临时资源已清，正式应用未更新。

2026-10-28 当前：[锁屏结束SSH、用户主动重连](ssh-background-session.md)已实现，
[本地检查](verification.md#20261028-ssh-lock-disconnect)293 Flutter/775 JVM及构建lint/v2通过；
手机ADB离线，新版未安装、实机待当前入口，临时资源已清。
停止锁屏保活/Doze及模板归因；导航/Home后台与Activity重建保留独立服务所有权，重新连接
仍经秘密库解锁和新信任/认证。不自动重放命令，不接入Mosh或部署远端组件，正式包未更新。

2026-10-28 当前：[手机真实锁屏调查](verification.md#20261028-ssh-phone-physical-investigation)
尚未通过。亮屏15秒空闲及keepalive应答正常；已只读确认锁屏时整个测试UID的祖先cgroup
被冻结，正常唤醒后解除。触发者尚未确认；用户改选锁屏断开，已停止该调查并清理隔离资源。
20分钟自然Doze观察未开始，不再作为本切片目标；正式应用未更新。

2026-10-28 最新：[Android16手机通知权限拒绝状态回归](verification.md#20261028-ssh-phone-runtime-denied)
通过后台锁库、重建、真实Flutter输入输出/提示及应用内停止；修正夹具窄屏键盘导航前置步骤，
初轮失败保留。隔离资源已清、正式应用未更新；手机物理锁屏/长时Doze仍待验。

2026-10-28 最新：[Android11开发板通知通道关闭](verification.md#20261028-ssh-board-channel-denied)
已实测通过：后台锁库、同代次恢复、真实Flutter输入输出/提示及应用内关闭。首轮系统恢复
通道重要性的失败单列保留；测试资源已清、正式应用未更新。Android13+权限拒绝/手机仍待验。

2026-10-28：[通知权限拒绝夹具准备](verification.md#20261028-ssh-notification-denied-preparation)
沿原SSH父task完成，隔离测试构建/lint与独立复核通过；真实手机/API36仍待当前目标及授权，
正式应用未更新。历史临时原始日志已不可用，现存旧APK哈希核对一致。

2026-10-27：[SSH 真实熄屏与安全锁定回归](verification.md#20261027-ssh-physical-lock)
在指定Android11板通过：熄屏15秒命令往返、正常系统解锁后库仍锁/原SSH继续，以及实际
终端恢复与通知操作。测试资源全清、正式应用未更新；手机/API36/深度Doze仍待验。

2026-10-26 当前：[SSH 后台会话与通知 Stop](ssh-background-session.md)已由用户选择，
沿原 SSH 父 task 实现，279 Flutter/765 JVM/ARM矩阵及构建通过；指定Android11板
[隔离服务与真实Flutter回归通过](verification.md#20261026-ssh-background)，正式应用未覆盖安装；
下方已通过的手机验收指前一私钥/config切片。

2026-10-26 最新：实际Android16手机SSH排查发现64KiB锁页额度及内部memfd chmod两处
兼容缺陷，均已修复。按用户指定在开发板完成四项隔离回归，最终修复APK042c6b73…已
保留数据更新手机并通过真实私钥登录与PTY命令验证，用户人工验收通过并授权提交。
[排查与验证](verification.md#20261026-phone-ssh)，沿原task，不新建或归档。

2026-10-30 待办（接续 10-26）：[应用细节修复](../.trellis/tasks/10-26-app-detail-fixes/prd.md)
已建 task，当前收集 TODO：顶部状态对话框增加 Root 授权入口、网络仅提示 Root 需求，
SSH 入口生物识别成功后继续进入连接页面，以及终端快捷键增加 Ctrl-D。保持 planning，尚未实现。

2026-10-26 最新交付：[config 与无口令私钥连接](ssh-key-config-connect.md)及可选密码
保存已沿原task完成实现，在指定Android11板通过隔离回归，并保留数据安装正式APK。
[功能入口](ssh-terminal.md)、[当前设备证据与安装](verification.md#20261026-key-config-device)。
真实DocumentsUI仍待专项验证；个人私钥及用户目标已在上述手机复测通过。
父完整规划继续，不新建task或归档。

2026-10-26 接续：用户选择[SSH 密码保存与复用](ssh-saved-password.md)，不新建 task，
沿已有 SSH 父规划实现默认当次、显式加密保存和忘记。[前序本地验证](verification.md#20261026-saved-password)；
本轮已随私钥/config新增能力完成设备回归及正式安装，以上最新记录为准。

2026-10-26：正式SSH APK已覆盖安装到指定开发板；[功能与使用入口](ssh-terminal.md)和
[安装记录](verification.md#20261026-formal-install)已更新。本轮已按前台密码连接交付归档，
其他SSH能力保留在父规划，仍只有一个正式主线。

2026-10-26 当前：[前台密码 SSH 连接](ssh-password-connect.md)已在授权的 Android 11
开发板通过实际界面验收：建库、输入连接信息、完整指纹确认、密码登录、终端命令与
渲染输出，以及关闭会话。实际bridge的首次保存/KNOWN重连也通过；仍沿唯一正式主线。
[实机证据与清理](verification.md#20261026-password-android)。正式APK已构建，测试包已清理。

2026-10-25 用户更正：[最新模型作为唯一SSH正式主线](ssh-manifest.md)，统一构造、创建、
写入和内容审阅，清理双格式业务分支。已交付TOTP通过窄输入适配保留原数据。
接下来以实际可用的SSH连接为目标，补齐同一主线的关系校验、账号使用和信任审阅。
[本轮检查](verification.md#20261025-ssh-mainline)，本地未提交。

以下为历史增量记录，不再按其中的格式划分维护主线。

2026-10-24：[全部证书事实与签名政策](ssh-certificate-facts.md)已实现CSCF168、三份公开摘要/unsigned数值/实际record来源及现代CA签名政策，保留trust/time/principal/revocation未验证。ARM22239与旧材料矩阵、定向145/全量638 JVM、normal100全重跑/fixture111及三APK审计通过；[实测](verification.md#20261024-certificate-facts)。持久graph/账号、完整信任/reviewer/provider/恢复/connector与父AC仍开放；本地未提交，无设备/真实库账号。

2026-10-23：[全部私钥材料与凭据归属](ssh-private-material-ownership.md)已实现全PRIVATE自签/验签、v1口令唯一owner/phrase审计和password namespace/deferred target。ARM19976/独立pair13812/material7784、定向120/全量613 JVM、normal98全重跑/fixture111及三APK审计通过；[实测](verification.md#20261023-private-material)。仍不提供持久关系图/真实密码账号/完整信任/provider/恢复/connector准入，父AC开放；本地未提交，无设备/真实库账号。

2026-10-23：[显式密钥配对证明](ssh-key-association.md)已实现精确ID/所选口令、证书plain subject及固定domain实签验签，拒绝仅公开字段相等的伪配对。ARM13814/独立材料7784/fresh认证1748333、定向80/全量586 JVM、normal96全重跑/fixture111与三APK审计通过；[验证](verification.md#20261023-key-association)。报告只覆盖明确所选pairs；全库关联authority/覆盖、凭据目标、完整信任/reviewer/provider/恢复/connector与父AC仍开放。本地未提交，无设备/真实库账号。

2026-10-23：[显式资源引用绑定](ssh-config-resources.md)已实现RESOURCE_AUDIT/CSCR、五类声明静态类型与真实parent直接引用，保留动态/cwd/key-loader未决。真实ARM4439含基线/全部旧矩阵/IO426，定向151/fresh全量564 JVM、normal94全重跑/fixture111与三APK审计通过；[验证](verification.md#20261023-config-resources)。材料数学关联、凭据/信任/provider/恢复/connector与父AC仍开放，本地未提交，无设备/真实库账号。

2026-10-23：[CONFIG静态引用与本地效果审计](ssh-config-audit.md)已实现独立AUDIT、真实Include父/行/子与parent直接references核对，以及所有分支有限政策。真实ARM13290含基线/原矩阵/IO426、定向124/fresh全量537 JVM、normal94全重跑/fixture111与三APK审计通过；[验证](verification.md#20261023-config-audit)。报告仍partial-only，动态/非Include引用、身份/凭据/信任完整语义provider、恢复/connector与父AC仍开放。本地未提交，无设备/真实库账号。

2026-10-23：[实际清单受管配置检查](ssh-detached-config-managed.md)已实现Java/JNI两阶段owner、全部CONFIG声明扫描与整job累计预算。ARM3683（含基线）/原矩阵/IO426、fresh JVM514及normal94全重跑/fixture111与三APK审计通过；[实际验证](verification.md#20261023-detached-managed)。引用/全分支政策、身份/凭据/证书/信任完整语义provider、恢复/connector与父AC仍开放。本地未提交，无设备/真实库账号。

2026-10-23：[独立 native 配置输入](ssh-detached-config.md)已实现，不占活动view。专项ARM1261/原配置矩阵/IO426、JVM483与normal94/fixture111及三APK审计通过；[验证与锁页/未测边界](verification.md#20261023-detached-config)。下一步Java整库编排与引用/身份/凭据/信任语义，恢复/connector与父AC仍开放。

2026-10-23：[TOTP写入与预览票据生命周期](totp-mutation-lifecycle.md)已实现原预算、锁外校验、精确交接与及时取消。定向196/全量483 JVM通过；普通与fixture构建/lint通过，APK审计见[验证](verification.md#20261023-totp-mutation)。下一步完整语义provider/恢复/connector，父任务保持进行中。


- SSH 最新：[trust CAS 生命周期](ssh-trust-cas-lifecycle.md)已实现原审批context、锁外校验、可取消provider与精确view延续，落盘后交付失败明确uncertain。定向202/全量JVM469、normal/fixture及三APK审计通过。[验证](verification.md#20261022-ssh-trust-cas)。下一项为剩余TOTP mutation，完整语义/恢复/connector仍开放。

- SSH 最新：[普通凭据保存/删除生命周期](ssh-entry-mutation-lifecycle.md)已实现原context、锁外source/candidate校验与独占候选，保留唯一effect写入claim。定向152/全量JVM455、normal92全重跑/fixture114及三APK审计通过。[验证](verification.md#20261022-ssh-entry-mutation)。下一项为trust CAS，完整语义/恢复/connector继续开放。

- SSH 最新：[typed秘密读取生命周期](ssh-secret-read-lifecycle.md)已实现原请求/role期限、锁外校验与最终host/KEX绑定受管复制；定向196/全量JVM444、normal92全重跑/fixture114及三APK审计通过。[验证](verification.md#20261022-ssh-secret-read)。下一项为普通mutation/trust CAS，完整语义/恢复/connector及父AC继续开放。

- SSH 最新：[public文件读取生命周期](ssh-public-read-lifecycle.md)已实现原context、锁外校验/
  closure、一次同步loan与native lease最终attach，保留原role预算/view和own-CAS。
  定向178通过；全量JVM431、normal92全重跑/fixture114与三APK审计通过。[验证](verification.md#20261022-ssh-public-read)。
  下一项为secret typed读取/host-KEX释放，完整语义/恢复与父AC继续开放。

- SSH 前一增量：[TOTP typed读取生命周期](totp-read-lifecycle.md)已实现原context、锁外校验/
  生成、一次receipt及最终当前窗口/锁库复查；pure不新增SSH依赖。定向140/fresh全量
  JVM410、normal92全重跑/fixture114与三APK签名隔离通过；[验证与限制](verification.md#20261022-totp-read-validation)。
  下一项为SSH public snapshot/sessionManifest原请求/role/view/loan释放；全语义/恢复仍后续。

- SSH 当前：[bundle 原请求与候选生命周期](ssh-bundle-lifecycle.md)已实现：原期限/直接取消/
  注册、独占候选与锁外校验、精确审批和一次消费，事务内禁止重读AtomicFile。
  最终定向81/fresh全量JVM391、normal92全重跑/fixture114及三APK签名隔离通过；
  [验证](verification.md#20261022-bundle-validation)。其他typed、完整语义provider与生产SSH恢复仍后续。

- SSH本轮：[清单语义义务与恢复预览生命周期](ssh-validation-lifecycle.md)：先明确完整
  语义/配置引用/身份/信任与真实Request的待证义务，再迁移preview的锁外校验/审阅、
  原期限/取消/注册绑定和一次交付claim。其他typed/bundle入口仍待迁移，生产SSH恢复门
  保持关闭；实际本地检查见[验证](verification.md#20261021-preview-validation)。

- SSH本轮：[实际清单离线内容检查](ssh-manifest-inspection.md)已实现，检查实际候选密钥/信任文件副本；ARM材料7784/effects0、完整认证/PTY1741410、fresh JVM358、normal95/fixture114及APK审计通过。报告恒contentOnly=true/admissionComplete=false，语义validator与SSH恢复仍未开放。[验证](verification.md#20261019-manifest-inspection)。

- SSH接续：[无上下文声明catalog](ssh-config-catalog.md)已实现；复用readconf词法入口，
  收集静态Include和literal Host来源，动态Include明确未解析。ARM目录71923/effects0，
  原求值3736/handle5201、fresh JVM327、normal95/fixture114及三APK审计通过。
  [本轮验证](verification.md#20261019-config-catalog)。
  不挂整库语义validator，SSH恢复门继续关闭；本地未提交，无设备/真实账号操作。

- SSH当前：[JNI/Java配置求值边界](ssh-config-managed.md)已实现，caller lease借用、独立
  token registry、标准UTF8 wire与有界来源诊断；ARM5201/effects0、fresh JVM308、normal95/
  fixture114及APK审计通过。[前置验证](verification.md#20261019-config-jni)。语义validator/
  最终连接计划及connector仍后续，保持本地未提交、不设备/真实账号。

- SSH当前增量：[受管配置求值器](ssh-config-evaluator.md)已实现native入口，复用固定readconf、
  managed Include、返回式输入诊断与完整Options；最终ARM3736项/effects0通过。
  [构建与验证](verification.md#20261019-config-evaluator)；JNI/catalog已由上方增量补齐，语义validator/connector仍后续。

## 2026-10-19 SSH 实际主机键与精确审批

启动器第一前置增量已实现：MANAGED open使用真实KEX offered key，复用KNOWN/NEW-CAS，
有界审批邮箱保留原认证/PTY安全约束。完整normal94项全重跑、fresh JVM288、fixture113项、
三APK验签/隔离与19JNI审计已通过；另用独立静态Bionic ARM测试完成1,724,636项完整回归。
旧动态运行环境已失，不混用其证据；产品config/catalog/connector及新版设备验收尚缺。
继续仅本地未提交，无设备操作。[合同](ssh-launcher.md) · [验证](verification.md#20261019-managed-host)。

- SSH 继续：[生产启动器合同与第一增量](ssh-launcher.md)，实际offered host key与精确NEW审批；认证/PTY基线保留。

- SSH 当前增量：[session channel / PTY / shell 合同与实现](ssh-session-channel.md)；沿用已完成认证基线。

## 2026-10-19 SSH shell 本地实现与终端接入

已完成单session channel/PTY/shell及native/JNI/Java六API，接现有终端host-only真实会话
factory、typed生命周期/代次/UTF8和SSH方向键；保留原认证proof/撤销/期限/线程边界。
本地normal94项全重跑、fresh JVM270、Flutter227/analyze、fixture113项和ARM合成协议通过。
生产profile/connector启动器、forward/service、设备与真实账号验收仍开放，父任务保持进行中。
[合同](ssh-session-channel.md) · [验证与工件](verification.md#20261019-ssh-shell)。

## 2026-10-19 Android/JNI 与私钥认证：已实现并完成独立实测

Java/JNI 已接入密码与私钥认证，支持 Ed25519、ECDSA p256/p384/p521、RSA SHA-2；
未加密私钥和有界加密 OpenSSH/传统 RSA、EC PEM 走同一证明绑定的一次授权。新增 CSASv2
把精确私钥和口令条目绑定到实际远端；修复真实文件视图与角色期限兼容、首次 NEW 主机
写回撤销自身视图，以及最后一次库读取后的凭据交付检查。

最终 fresh JVM244/0/0/0、ARM QEMU1,244,010 checks、normal94任务/lint/Release/v2通过；
开发板四个精确方法各 OK(1 test)，覆盖密码、未加密/加密私钥及真实 NEW/CAS。测试包/进程
已清理，正式应用 hash 未变，未使用真实库或账号。普通 APK89eb6425… 未安装；加密PKCS8、
KBI/MFA、channel/PTY/forward/service/UI 和完整客户端 AC 继续开放。
[完整证据、格式与限制](verification.md#20261019-android-auth)。

2026-10-19 SSH 最新：C-only 一次密码 USERAUTH 协议已实现，使用 owned CSAS 用户名与
证明绑定的凭据，区分成功、拒绝、部分成功和要求改密；无自动重试。新增67个加密合成
连接场景，最终 ARM QEMU **785,537 checks / exit0**、normal94项/lint/Release/v2及正式
复核通过，APK `983f2032…` 未安装。JVM221为UP-TO-DATE保留报告；Java/JNI认证编排、
私钥认证和完整客户端仍开放。[验证与限制](verification.md#2026-10-19--explicit-native-password-userauth-protocol)。

2026-10-19 SSH 最新：已接通绑定 CSAS 回执、当前 KEX 与 service-ready 的一次凭据回调。
native 使用私有 scratch，Java 在秘密库最终交付前复查当前证明，拒绝重复及旧回调降级；
JNI 异常顺序、finish 取消/重入与 Error 清理问题已修复。fresh JVM **221/0/0/0**、最终
ARM QEMU **323,726 checks / exit0**、lint/Release/v2 和正式复核通过，APK `f329056e…`
未安装。仍无 USERAUTH/私钥解析，修改后的 JNI 未设备执行；[验证与限制](verification.md#2026-10-19--receipt-bound-selected-credential-handoff)。

2026-10-19 SSH 最新：CSAS v1 已把冻结的用户名/凭据身份元数据接入 session JNI/native
owned copy，并提供只读 SHA-256 回执。fresh JVM **208/0/0/0**、最终 ARM QEMU
**253,155 checks / exit0**、lint/Release/v2 与正式复核通过；APK `bda806ca…` 未安装。
新 JNI 未设备执行，回执不授予凭据或认证权限；[验证与限制](verification.md#2026-10-19--csas-native-selection-metadata-and-receipt)。

2026-10-18 SSH 最新：managed 层已加入不可变用户名/凭据选择，selected callback 只能释放
同一 role/selection 的精确 password entry 或 private-key path/identity，拒绝旧 kind-only
回退。新增12项回归，全量 JVM **201/0/0/0**、lint/Release/v2 与正式复核通过，APK
`e6c4d58b…` 未安装。native receipt、实际认证和私钥公钥匹配仍开放；[验证与限制](verification.md#2026-10-18--managed-immutable-ssh-authentication-selection)。

2026-10-18 SSH 当前实现：production C client 可在 KEX 后显式协商 `ssh-userauth`
服务，保留原 KEX-only/JNI 行为。真实加密 KNOWN、NEW/CAS 合成 peer 已在 ARM
QEMU/Bionic 上最终通过 **246,808 checks**，callback host 为 24 组；正式代码复核无遗留
问题。normal 94 项全部重跑、JVM189/lint/Release/v2 通过，APK `8cd6da66…` 未安装。
服务 ready 不代表认证完成；[验证及边界](verification.md#2026-10-18--native-post-kex-userauth-service-negotiation)。

2026-10-18 SSH 最新实测：开发板已恢复解锁；按当前 production 修复重建独立 fixture 后，
精确 JNI 分片 transport / 初始 KEXINIT 输出 / KNOWN / EOF 清理方法 **OK (1 test)，
0.202s**。两包与进程已清理，正式 APK 未变；原锁定待测关卡关闭。完整 Android KEX、
认证与产品 AC 仍开放；[本轮验证](verification.md#2026-10-18--recovered-board-android-jni-fragmented-transport--eof-pass)。

2026-10-17 SSH 最新接续：发现宿主 QEMU 可用，已实际执行 ARM native 合成回归并修复
role errno、分片 banner 原子性、KEX 完成前输出排空及失败 open 的 KEX 解绑。
最终 **65,838 checks / exit0**；normal **94 tasks** 通过，JUnit 189/0/0/0 为保留报告。
APK `f3327427…` 已构建/验签，未安装。开发板仍锁定，Android JNI 新版 fixture 须重建
后另测；[完整证据与边界](verification.md#2026-10-17--host-qemu-arm-native-runtime-and-production-fixes)。

2026-10-17 SSH 当前增量：独立 JNI fixture 已加入 3-byte 预算的分片 banner / 初始
KEXINIT 输出读回及独立 EOF terminal/close 回归，实现与检查代理复核、Java compile、
assemble、lint 和签名检查通过（112 tasks）。开发板处于 `RUNNING_LOCKED`，系统拒绝
启动测试；用户选择暂时无法解锁、保留本地结果，两包及挂起进程均已清理，正式 APK
哈希未变。**本轮设备测试未执行**；[证据与后续步骤](verification.md#2026-10-17--android-jni-fragmented-transport--eof-fixture-local-pass-device-not-run)。

2026-10-16 SSH最新补充（G3.8 Android ARM/JNI empty-peer EAGAIN）：在 production
`SshNativeSession.open` 成功并转移 socket ownership 后，fixture 先调用一次
`step()`，空 peer 断言 `bytesRead=0`、
`inputAppended=0`、`readWouldBlock=true`，不把可能的 banner
`bytesWritten` 错误限制为 0；随后 KNOWN `verifyHostKey` 与 revoke/close
仍通过。当前授权板精确 instrumentation 为 `OK (1 test)`、status 0、0.11s，
最新 fixture main/test hash 为
`79776aad22be067e057fb2abd2a9af74bc365f890de44b1d3ba689c18d9560d3` /
`fc71429d9e06d33334cc51cbb9fa64a7b8f672f2bb8752922052aac5ec6dc71e`；只证明
bounded EAGAIN + KNOWN host-proof JNI runtime。

2026-10-16 SSH最新窄增量（G3.8 Android ARM/JNI KNOWN host-proof）：独立
`secretsFixture` 把 managed `ssh/known_hosts` 行改为同一 OpenSSH wire
public-key blob 的无换行 Base64，并在 production `SshNativeSession.open` 后、
view revoke 前调用 `verifyHostKey`。当前授权板 `192.168.9.14:5555`
（AIO-3568J、Android 11/API 30、arm64-v8a）精确方法
`SshNativeSessionPlatformTest#syntheticNativeSessionHandle` 为
`OK (1 test)`、status code 0、0.089s；fixture main/test hash 分别为
`1e3a10c709fed7d514a90e247494c61345da6a0f71dd142c17157f34ea4e9de6` 与
`2b9f80678220d89a7a67e23197bd5f85c1eee7543763bfb895da8d31a801f7e7`，测试后
两个包和进程均清理。该证据只覆盖 matching KNOWN host-proof 的 Java→JNI→native ARM
边界，不覆盖 packet/KEX、userauth 或完整 SSH runtime；task 继续
`in_progress`/`implementation_complete=false`。

2026-10-16 SSH最新窄增量（G3.8 NEW/CAS full-KEX）：空 managed `ssh/known_hosts` 已不只
覆盖 direct verifier；synthetic nonblocking OpenSSH client/server packet loop 现在真实走过
candidate → `NEW` host evidence/`CAS_COMMITTED` → `kex->done`。candidate、evidence、KEX
callback 均 exactly once，route fields、replacement body、base/replacement digest 与 peer-bound
session digest 均有断言，completion 后 `step` 为 `EALREADY`。focused/normal gate 通过，ARM
runtime 仍因无 qemu 为 `not-run`。

最新 normal APK SHA256：`7ccac2fb8f705edd761d34574b9cead4b9e2296864dfe5568d2e9b1105fd849f`；APK 内
SSH 相关 native 库仍只有生产 `lib/arm64-v8a/libctos_ssh_files.so`，task 继续
`in_progress`/`implementation_complete=false`。

2026-10-16 SSH最新窄增量（G3.8 synthetic KEX completion）：native production caller 现在由
synthetic nonblocking OpenSSH client/server socketpair 驱动到真实 `kex->done`。受管 `KNOWN`
host key 的 host evidence 恰好一次且先于 KEX-complete callback；callback 的 cycle 固定为
1，session digest 非零并与 peer session ID 的 SHA-256 一致；client 完成后再次 `step` 返回
`EALREADY`。server pump 同时覆盖分片输入、EAGAIN 与短写。focused 与 normal full gate 均通过，
但 AArch64 runtime 仍因无 `qemu-aarch64` 为 `not-run`，不能外推为 Android/完整协议验收。

固定证据：94-task normal gate、JUnit **189/0/0/0**、production/native/callback/view gates
通过，strict `compileSshFiles`、三 ABI Release、lint、assemble 通过，archive=83；APK SHA256
`7195a67fad84185714d00cbb2bbc899fbdf788b61f722282fae8b99ae8ba5f69`，APK 内 SSH 相关 native
库仍只有生产 `lib/arm64-v8a/libctos_ssh_files.so`。task 继续
`in_progress`/`implementation_complete=false`。

2026-10-15 SSH当前最新窄增量（G3.8 follow-up）：native 已接管 session 的 opaque handle、解析后
host key 和 public-view borrow；`ssh_session_jni.c` 已把五个 JNI entrypoint 接到真实
`ssh_native_client`，并强制 owner-thread、bounded key/fingerprint copy、opaque session
registry 与可重试 close/destroy。`ssh_view_handle` 的 revoke 会阻止新 borrow，已有 borrow
在 native session 释放前保持有效，避免 Java token 被当作 C pointer；`step` 现在保留
managed-view deadline 的 `ETIMEDOUT`，并有 host-only EAGAIN/EOF/deadline 与 fragmented
banner/KEX output accounting 回归；另有 synthetic managed `KNOWN` host key 的 production
verify/evidence 回归、错误 fingerprint 拒绝、managed-key `CHANGED` 拒绝和 matching
`@revoked` key 的明确 `REVOKED` 拒绝；当前又加入有效 host certificate 的明确
`CERTIFICATE` 拒绝，以及 malformed managed known_hosts 的 project-owned `INVALID`
预检分类，并覆盖空 managed known_hosts 的 production `NEW` candidate handoff、精确
replacement body/digest、`CAS_COMMITTED` evidence 和 rejection rollback。生产路径仍只
证明合成 JNI lifecycle、bounded step transport/error semantics 与部分 host-evidence
classification contract，不代表完整 SSH 协议、ARM runtime、认证或产品验收。

当前固定证据：normal hako gate 的 view-handle/native-client/callback host gate 通过，
JUnit **189/0/0/0**，production `SSH_PRODUCTION_OPENSSH_BUILD gate=pass fixture=excluded`，
native host `gate=pass runtime=not-run reason=qemu-aarch64-unavailable`，strict
`compileSshFiles`、三 ABI Release、lint、assemble 通过；APK SHA256 为
`7f0bd0f14ec2a5ae9175ee340512acfdeaf0aa76f843834d1a3c87107c97993b`，archive=83，五个
session JNI symbols 可见，APK 内 SSH 相关 native 库只包含生产 `lib/arm64-v8a/libctos_ssh_files.so`。独立
`secretsFixture` 在 `192.168.9.14:5555` API30/arm64 上分别通过
`SshNativeSessionPlatformTest#syntheticNativeSessionHandle` 与既有
`SshFileViewPlatformTest#syntheticProtectedFileViews` 各 1 项；两个 fixture UID 和包均已
卸载，正式包路径/hash 未变。task 仍 `in_progress`/`implementation_complete=false`。

2026-10-15 SSH历史窄增量（G3.6）：在 G3.5 production native C boundary 之上新增
Java `SshNativeSession` owner seam。打开、step、host-key verify、close/destroy 只能由
callback event-loop owner thread 驱动，`cancel` 是唯一允许跨线程的操作；
`SshNativeCallbackAdapter.NativeSessionClaim` 保证一个 callback binding 同时只有一个
native session。close 顺序为 native close（由 C boundary 负责 callback/KEX/socket）→
Java callback claim bookkeeping → role release → public-view release → native tombstone
destroy；每个阶段失败都保留可重试状态，失败 open 不消费 caller socket、callback 或 view。
host-key/fingerprint 进入 native binding 前复制，调用后清零。

生产 JNI 符号已接入 normal `libctos_ssh_files.so`，但 `ssh_session_jni.c` 当前对
`open/step/verifyHostKey/close/destroy` 统一抛 `IOException`：现有 C API 仍接收借用的
`struct sshkey *`/`ctos_ssh_view *`，在 native-owned handle、view snapshot/borrow 和
取消/关闭协议冻结前不能安全把 Java opaque token 当作 C 指针。JVM seam 使用 fake
`NativeBinding` 验证 owner-thread、claim、期限、清零和 retry 合同，不等于 Android
runtime/JNI/OpenSSH session 通过。

当前固定证据：full gate 的 JVM 为 **189/0/0/0**，`SshNativeSessionTest` 为 12 项，
callback host=20，production native 为 `gate=pass runtime=not-run`（原因是无
`qemu-aarch64`），严格 `compileSshFiles`、三 ABI Release、lint、assemble 全部通过；
production archive 仍为 83 个成员，session JNI symbols 可见且 forbidden=0；APK SHA256
为 `21d16325b242427c2ccfbb7aa9cf42c6ca359700dc7b7f52995a95de06b94e72`。未使用设备
`192.168.9.14:5555`、真实 vault、凭据或外部 SSH；task 保持 `in_progress`。

2026-10-15 SSH上一窄增量（G3.5）：已把受管 hostfile issuer、KEX guard 与真实 pinned
OpenSSH source/object closure 接入 normal `compileSshFiles`。新增固定 runner
`android-production-openssh-build.sh`：校验 G1 baseline/tree/patch hash，在临时构建目录用
OpenSSH 自带 `libssh-pic.a` 目标以 `-fPIC` 重编完整 10.6p1 `libssh` 成员，替换项目
stream-writer/KEX guard 对象，并纳入 PIC `ssh-pkcs11.o`/`ssh-sk-client.o`；最终归档
83 个成员，排除 stock 高层 caller。`ssh_native_client.[ch]` 现提供同进程 bounded
socket event-loop C boundary；open 失败保留 caller FD/callback，成功 close 先释放
callback，再解绑/释放 OpenSSH，最后释放原 FD，callback 失败可重试。

当前固定证据为 G3 fixture `run.aqN1F2bD`：`SSH_HOSTFILE_CALLER_BUILD gate=pass
runtime=not-run normal_apk=fixture-caller-not-wired`；生产 `testSshNativeClientHost`
为 `gate=pass runtime=not-run`，callback host=20，JUnit=177/0/0/0，strict
`compileSshFiles`、三 ABI Release、lint、assemble 全部通过；APK SHA256 为
`805fc28679aa56be85fa3be75a1608701273908a02f7fb04dddb717c4e1b601e`，ZIP 只有生产
`lib/arm64-v8a/libctos_ssh_files.so`（无 pinned fixture caller）。hako 无
`qemu-aarch64`，ARM binary、`192.168.9.14:5555`、真实 vault、凭据与外部 SSH 均未使用。
生产 C caller 已链接但尚无 Android runtime/JNI/Java 会话编排；认证、typed credential、
PTY、forward、service/UI、设备与外部 SSH 仍未验收，task 保持 `in_progress`。

2026-10-15 SSH上一窄增量：在既有 managed `AppSecrets` known_hosts provider
之上冻结独立的 opaque、同步、one-shot native→managed candidate handoff。
`ctos_ssh_callback_known_hosts_candidate`/JNI 只传 exact role/hop/target/connection/
host/port/key-type/fingerprint、base/replacement digest、candidate identity 和不超过
`MAX_FILE_BYTES` 的 body；现有 metadata-only CSHR wire 不变。managed receiver 在
body digest、snapshot 与 provider ownership 校验后才保留 private clone，后续仍由原
guarded AppSecrets CAS 才能返回 `COMMITTED`。JVM **177/0/0/0**、native client gate=27、
callback gate=20、strict compile/lint/Release 通过；APK SHA256 为
`3a6228f4237380dfc2cd98ccd537c16d4c6e81bc2e64e6205cdd6d556af9ded6`，普通 APK 未包含
host-only boundary 或 fixture OpenSSH objects。pinned hostfile issuer 已在 fixture seam
接入但 production caller 尚未接入，仍不实现 OpenSSH/KEX/auth/PTY/forward/service/UI、设备或外部 SSH，task 保持
`in_progress`。

2026-10-15 前一窄增量：已冻结并实现 role/hop/target-bound HostEvidence/CAS
native↔Java contract。`SshNativeCallbackAdapter` 的 typed host callback 传递
classification、key type、SHA256 fingerprint、32-byte base/replacement digest；NEW
host 必须经过 managed exact proposal、显式 approval 和 exact CAS `COMMITTED`，返回给
native 的结果只含 bounded metadata/digest/proposal binding，known_hosts body 不过 JNI。
native C/JNI 会重新校验结果并在失败、取消、期限竞争时清零；host callback gate=15、
JVM=166、strict compile/lint/Release 通过。这里冻结的是 provider seam，不是具体
known_hosts file/CAS writer，也不是 OpenSSH caller；`ssh_native_client.[ch]` 仍在缺
provider 时 fail-closed 返回 `ENOTSUP`，task 保持 `in_progress`/未完成。

2026-10-15 前一窄增量：pinned OpenSSH 10.6p1 source map 确认当前没有可编译的生产
known_hosts issuer/CAS native-facing contract；新增的 `ssh_native_client.[ch]` 因此只做
fail-closed boundary，严格校验 live role、PID/UID、CLOEXEC/NONBLOCK socket、callback
token/environment、deadline 和 bounded step，缺 provider 稳定返回 `ENOTSUP`，不进入
OpenSSH/KEX/JNI/credential。host-only gate=27，普通 APK 不包含该 boundary 或 fixture
OpenSSH object；真实 caller、provider、partial I/O/EOF/cancel/close retry、inbound
NEWKEYS proof 及完整 SSH 仍开放，task 保持 `in_progress`/未完成。

2026-10-14 SSH前一阶段：用户已确认同进程 native event loop 与当前 event-loop 线程同步附着 JNI callback；callback adapter 契约已编入正常 `libctos_ssh_files.so`，并新增 bounded managed-provider handoff：精确 role/hop-bound NEW proposal、host proof + KEX proof 绑定的 typed password/private-key bytes、Java/native 清零与 cleanup retry。严格限制 role/PID/UID/deadline、JNIEnv/JavaVM、线程、重入和取消；host `gate=pass value=11`、162 JVM、lint、Release 构建通过。正常 APK 的 SSH 相关条目仅为生产 `libctos_ssh_files.so`。另从当前 `.14` 用固定 Android arm64 OpenSSH 客户端成功访问本机一次性 Docker SSH pair；该 external smoke test 不等于生产 event loop/caller、具体 config/known_hosts provider/CAS、auth/PTY/forward/service/UI 接入，task 保持 `in_progress`/未完成。
生产下一步是把已实现的 managed file/provider contract 接到真实 OpenSSH caller；fixture 的 `ssh_api` 内存流与 control framing 仍只属于回归证据。

2026-10-13 SSH当前增量：Java role/trust/KEX/typed-secret bridge 已完成，bounded native role bootstrap 的 ownership/open/release JNI 已接入正常 `libctos_ssh_files.so`；native 输出清零、跨进程/UID close、stale token、Java registry 配对与失败重试均有回归。150 JVM、lint、host bootstrap 1198/1/1198、固定 KEX native 构建和正常 Release 构建通过；当前 APK `538cae5a…` 包含生产 public-file 与 role JNI。这里只证明 host/fixture seam 和 JNI contract，尚未由生产事件循环调用或接入 native launcher/OpenSSH callback、协议认证/PTY/转发/服务/UI或设备/外部账号；完整客户端仍开放。

2026-10-13 G2构建前阶段（历史）：TOTP用户验收交付已提交566ca51、归档f3762ba、journal74f8324，相关实测与未测边界保留。复用SSH客户端task；G0本地检查及用户专项批准后的`.14`独立AppUID **1项1.693秒通过**，仅API30/4KiB/Permissive，raw memfd完整候选可用、ASharedMemory文件形态不适用。夹具已卸载，正式APK/真实库未改。用户允许真实库操作，凭据不记录，远程账号未授权。G1固定OpenSSH10.6p1/OpenSSL3.5.9的Android ABI兼容基线已编译通过；当前实施G2可信合成上游文件与ProxyJump夹具，尚未构建或设备验证，尚无完整生产客户端。见 [SSH阶段验证](verification.md#2026-10-13-ssh-g0-本地实现与构建隔离)。

2026-10-13 用户已确认TOTP效果并批准提交验收、继续下一项：当前Authenticator交付收尾归档，实测及未测边界保留，下一项复用已有SSH客户端task，从Android native OpenSSH无明文磁盘文件访问关卡继续。下方同日in_progress/未提交为验收前阶段。

2026-10-13 当前设备阶段：用户授权“帮我安装、自动测试”，最终前台交接修复版 `3bc762d9…` 已安装 `192.168.9.14:5555` 并读回一致，221 Flutter/86定向/analyze与独立UID7项原生验证通过。真实系统双二维码选图与管理/生命周期全流程1项72.734秒通过，临时测试包/自有图片已清理，详情见 [设备验证](verification.md)。相机失败提示和成功预览均已补前台交接保护；该板0相机。此前复制自动审批拒绝已由用户专项批准解决，原生合成复制/敏感标记1项6.616秒通过，真实账号/跨设备混合恢复等仍开放。下方本地c704/214阶段按当时状态解释。

2026-10-13：[Authenticator](authenticator.md) 已按用户“复用原 task、先 TOTP 后 SSH”完成本地实现：工作台入口、相机/图片/手动添加、条目/倒计时/主动复制及公共库接入。214 Flutter、122 JVM、analyze、lint与45真实字体场景通过，最终arm64 APK `c704ff36…` 构建/v2验签通过；[本轮验证及未测项](verification.md#2026-10-13-authenticator-totp-本地实现与最终产物)。原task保持 `in_progress`，设备/真实账号及跨设备混合恢复尚未验收；[公共底座](app-authentication.md) 未测关卡不标完成。SSH仍planning，随后按既定顺序推进。下方10-12“无current/业务planning”均为历史。

最新源码 APK：运行 [`./preview.sh`](../preview.sh)，完成 arm64 release 编译后输出 APK 绝对路径和 `adb install` 命令；需要 Docker，具体检查见 [验证记录](verification.md)。

当前开发板入口：`192.168.9.14:5555`（用户2026-10-13更正），执行要求见 [约束](constraints.md) 和 [验证spec](../.trellis/spec/trellis-plus/validation.md#current-development-board)。

2026-10-12 最终交付：摘要DEVICE/Android运行行已移除，四分类范围描述统一、接口来源/数量保留。188全量/84真字体场景、analyze/格式及arm64/v2验签通过；25bcdf7c…已安装开发板读回一致，最终分类布局实测通过。按用户授权提交`5e67227`并[归档本任务](../.trellis/tasks/archive/2026-10/10-12-overview-settings/check.md)，当前指针已清除；[最终验证](verification.md#2026-10-12-最终概览文案收敛与提交)，既有成功认证/共享快照写盘等证据边界保留。下方current/in_progress描述为提交前各阶段历史。

2026-10-12 当前：[概览整合与设置迁移](overview-settings.md) 及四项截图细节已实施。分类均分全宽、Root标题旁信息按钮、终端眉标题精简、默认快捷/失败三选择解锁；188全量、84真字体渲染和arm64/v2验签通过，af600c41…已安装开发板读回一致。实际布局、快捷调起/Home取消/重试/主口令回退与AppPTY切换保持通过；成功系统认证本APK未重测。见 [最新验证](verification.md#2026-10-12-概览截图细节与默认快捷解锁)；未提交/归档，原公共底座未测关卡保持。

2026-10-12 用户确认修复效果，并授权提交所有相关task文件；公共底座的阶段实现/测试/spec/design与四个相关任务文件纳入本轮提交。底座仍in_progress，其他任务仍planning，未完成验收保留；见 [task check](../.trellis/tasks/10-08-app-authentication/check.md)。

2026-10-12 恢复公共底座：已修复系统认证前提交 AAD 导致的快捷登记失败。已有安全锁屏的 Android11 开发板上，同一独立UID真实空库流程旧实现失败、修复后1项95.811秒通过，涵盖登记/快捷解锁/关闭保护/主口令回退。106 JVM、analyze、lint与构建验签通过；主包 `71d00e7c…` 已覆盖安装并读回一致，用户现有库开启及快捷解锁也实测成功，结束为已锁定/快捷已开启。API28/29、生物及其他开放项仍未验收，task继续in_progress，见 [验证](verification.md#2026-10-12-安全锁屏开发板快捷认证修复与实测)。

2026-10-11 最新交付：四入口2秒双返回已实现，提示位于底部导航/安全区上方，普通Snackbar自动排在其上方；16定向/154全量、analyze/格式与arm64/v2验签通过，APK `981ef814…`。用户报告实机验收通过，并在下方提示交付后回复“可以提交当前 task”，本任务提交与收尾已获批准。用户反馈与agent检查分别记录，公共秘密库继续暂停，未替换dist或操作设备，见 [验证](verification.md#2026-10-11-退出提示移至下方)。

2026-10-10 继续任务：修复typed写入最终期限和SSH最后主机核验使会话失效后的凭据/私钥释放边界；106 JVM、138 Flutter重新执行通过，analyze无问题、lint0错误/3既有警告。新增独立UID真实快捷认证手动夹具，本轮仅本地准备，成功认证仍未实测；此前安装ca85不含本轮修复。见 [验证](verification.md#2026-10-10-继续任务本地效果边界修复与手动认证夹具)，任务仍in_progress。

2026-10-10 当前实施：公共秘密库宿主/Flutter、SSH 文件图、同名导出确认/失败精确清理及恢复审阅已实现。Bridge生命周期修复后104 JVM、35定向通过，此前未变138 Flutter全量未重跑。主包 ca85已安装配置B Android11板，6项生命周期53.408秒通过；独立UID实际release UI空库完整流程1项235.79秒通过，覆盖创建/改口令/锁定、新导出/同名取消/覆盖、双方错误口令/取消/确认恢复及来源口令解锁。测试文件/目录/授权/两fixture包已清理，生产公开库尚未创建、后台30秒保持；此前原包17平台/真实provider六阶段另记。成功快捷认证、API28/29、真实锁屏/进程死亡、非空混合恢复与SSH业务仍开放，任务in_progress，未提交/归档/替换dist。用户撤销剪贴板清理，保留主动复制/敏感标记，清理由用户自理。见 [公共契约](app-authentication.md#2026-10-10-当前实现与未完成验收)、[验证](verification.md) 和 [task check](../.trellis/tasks/10-08-app-authentication/check.md)。

2026-10-06 文案补充：顶部移除 `SYSTEM OBSERVATORY`，概览标签简化为 `DEVICE`；当前源码 analyze、103 项 Flutter 测试及格式检查通过。用户追加要求后已构建 arm64 release 包 `b7e67386…`，签名与校验和通过，未安装到设备。见 [验证记录](verification.md#2026-10-06-两处显示文案精简) 与 [变更历史](changelog.md#2026-10-06--显示文案精简)。

2026-10-06 当前交付：全 Flutter 界面优化已实施，统一主题与全部页面、900 dp 导航适配、大字布局及交互反馈。最终本地检查为 analyze 无问题、103 项测试通过、107 个实际 Flutter 渲染场景；release 包 `37d22600…` 已在配置 A Android 15 手机覆盖安装，安装哈希一致。用户已回复“观感通过，按计划提交”；实机交互见 [验证记录](verification.md)，任务见 [归档检查](../.trellis/tasks/archive/2026-10/10-05-flutter-experience-polish/check.md)。旧包/设备结论均按日期解释，既有 HFTP 熄屏断连限制未改变。

`design/` 是 ctOS 项目各种信息的统一 landing。产品规划、约束、架构、决策、兼容性、验证记录、依赖和变更历史都在这里维护。根目录 README 仅保留项目简介和使用入口；项目级 AGENTS.md 约定后续工作的执行规则。

## 阅读路径

| 文档 | 内容与用途 |
| --- | --- |
| [规划](plan.md) | 产品定位、能力范围、阶段交付与验收标准 |
| [改进机会](opportunities.md) | 基于当前证据的功能、体验、视觉与工程改进建议及优先顺序 |
| [全 Flutter 界面品质优化](flutter-experience.md) | 2026-10-05 全页面视觉、响应式、动效契约与实际复验入口 |
| [概览与设置](overview-settings.md) | 2026-10-12 信息合并、主导航、导出入口与设置/安全迁移契约 |
| [约束](constraints.md) | 工作范围、权限边界、采集与执行规则、质量要求 |
| [当前架构](architecture.md) | 已实现的组件、数据流与执行边界 |
| [Portable 五项工具与 HFTP](portable-tools.md) | 模块边界、SDK v2、文件能力、认证加密与后台文件服务 |
| [Portable 工作台与 SDK](portable-workbench.md) | 内置 Python、终端工具、运行包清单和脚本扩展契约 |
| [App 统一秘密库与公共认证](app-authentication.md) | 先行底座：主口令、加密库/会话/设置/设备快捷/备份/隐私配套；2026-10-12 恢复推进，API30凭证快捷实测通过，保留 in_progress/其余待测关卡，TOTP 与 SSH 共同依赖 |
| [SSH 终端规划](ssh-terminal.md) | APK 内客户端、OpenSSH config/一键连接和统一秘密库接入；in_progress，managed AppSecrets known_hosts CAS provider、production native C caller、native-owned session/view handle、合成 JNI lifecycle 与 bounded step error gate 已接入，完整 SSH 仍开放 |
| [SSH 终端接管档案](handoff-ssh-terminal-client.md) | 下一 session 的当前提交、证据、授权边界、阻塞与可执行接管步骤 |
| [Agent 间测试交接](agent-test-handoff.md) | 文件式测试单、执行与独立审核职责、证据和协作协议 |
| [Codex 测试分发与成果使用](codex-test-management.md) | 测试需求编排、领取协调、报告审核、结果复用和项目状态更新 |
| [第三方 Authenticator](authenticator.md) | 离线 SHA1/6位/30秒 TOTP、工作台入口与公共秘密库；修复版已安装当前开发板，本轮非copy隔离自动验收完成，账号及未测平台关卡保留 |
| [兼容性](compatibility.md) | 设备差异、已验证范围与未验证项 |
| [验证记录](verification.md) | 检查结果、实机证据及复验方式 |
| [依赖来源](dependencies.md) | 工具链、版本、来源和许可证 |
| [变更历史](changelog.md) | 已实现变更与文档调整 |
| [移除 Vector 的决定](decisions/2026-09-26-root-only.md) | 作用域与在线服务的差异，App/Root 能力和边界 |
| [Trellis Plus 规则](../.trellis/spec/trellis-plus/index.md) | 开发流程增强及项目验证配置；任务结果仍以 Trellis 任务记录为准 |
| [Trellis 编码规范](../.trellis/spec/frontend/index.md) | Flutter 与 Android/Python 宿主代码约定；后端及原生层索引见 [Runtime 规范](../.trellis/spec/backend/index.md) |

## 当前交付与阶段记录

- [APK 主页双返回退出到桌面](../.trellis/tasks/archive/2026-10/10-10-home-double-back-exit/prd.md)：16/154检查与新版构建通过，用户报告实机通过并批准最新下方提示提交；已提交并归档，见 [检查记录](../.trellis/tasks/archive/2026-10/10-10-home-double-back-exit/check.md)。
- [可信状态与工作台基础体验](../.trellis/tasks/archive/2026-09/09-25-trustworthy-state-workbench/prd.md) 已补齐历史验收对应证据；当时的 Vector 要求后来被 [移除决定](decisions/2026-09-26-root-only.md) 取代，不属于当前 App 架构。
- [只读任务与选择性导出](../.trellis/tasks/archive/2026-09/09-25-read-only-task-loop/prd.md) 仅把 `device.info`、`memory.snapshot` 和 `network.interface_diagnose` 纳入有界历史；接口诊断走 App 权限，导出由用户逐项选择后使用 SAF。
- [界面与终端整理](../.trellis/tasks/archive/2026-09/09-25-interface-terminal-polish/prd.md) 完成统一 Card 表面、840 dp 阅读宽度、终端快捷栏折叠及输出搜索/复制。Flutter analyze、79 项 Flutter 测试与 release 构建通过；授权 `TARGET-BOARD` 的 App/Root PTY 定向检查见 [验证记录](verification.md)，不推断其他 ROM 或全新安装流程通过。

### 历史阶段记录

- 2026-09-28 P1 只读任务与选择性导出完成授权设备验收：Flutter 76 项、定向 Android instrumentation 4 项通过；应用强制停止再启动后历史仍可查看，SAF 单记录读回/取消和手动清空均实测通过。Root 自动恢复仅按用户授权随正常启动执行，未运行 Root 测试；见[验证记录](verification.md)与[任务 PRD](../.trellis/tasks/archive/2026-09/09-25-read-only-task-loop/prd.md)。
- 2026-09-28 Firefly AIO-3568J 已安装当前 `9ca71e31…` APK 与匹配测试包，Android 11 / API 30 的七项非 Root 定向测试通过，HFTP 仅使用 loopback 且结束后服务为空。Root、LAN、熄屏等未在此板复验，见[验证记录](verification.md)。

- 2026-09-28 停止反馈修复已安装9ca71e…包：73项Flutter、10项最终手机检查及三轮LAN传输/停止/同7888重启含31MiB通过，日志状态正常；原配置恢复、隔离文件精确清理、VPN保持不变。熄屏仍拒绝连接，App CPU锁登记不代表idle有效保活。独立Root临时CPU租约候选用户选择暂不扩大Root范围，未实现；任务in_progress，暂不提交/归档。见[验证记录](verification.md)和[实机记录](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/restart-device-check.md)。

- 2026-09-27 HFTP日志与可选Root网络中继已实现并安装0575e00d…包：ToolExecutionContext声明能力，ctOS adapter代理网络，Python/SAF保持App UID。68项Flutter、25项Python、native15+3及手机六项定向检查通过；既有VPN lockdown/不可bypass条件下，Windows已确认可打开，实际33MiB LAN传输通过，停止owned进程/端口回收通过。默认App配置及停止状态恢复，夹具已精确清理。前端spec追加不要demo式页面描述；源码未提交/任务未归档。见[验证记录](verification.md)与[Root实机记录](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/root-device-check.md)。

- 2026-09-27 九张图片反馈已实现并安装最终 `d43f52ed…` 包：去编号/技术详情、普通键盘及宽度统一；App HFTP 默认 LAN/7888/免登录，上传限额及本机目录持久授权。52项 Flutter、21项 Python、Android lint/构建、两种本机 provider 与33MiB实际传输通过；电脑直连7888仍超时，手机自身LAN地址与ADB转发可达，跨设备LAN尚未验收。服务停止、默认配置恢复、测试目录精确清理；源码未提交/任务未归档。见 [验证记录](verification.md) 与 [反馈实机记录](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/feedback-device-check.md)。

- 2026-09-27 工作台体验优化已实现并经用户授权安装到 TARGET-PHONE：工具前置、中文分层参数、专用结果及 HFTP 状态分区。analyze、43 项 Flutter 测试、Android lint、组件预览、构建和手机定向界面/键盘/SAF/来源哈希验收通过；新版 APK `98f44069…` 安装哈希一致，未提交或归档。见 [任务 PRD](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/prd.md)、[工具设计](portable-tools.md) 与 [验证记录](verification.md)，设备结论仅覆盖实际场景。

- 2026-09-27 按用户要求提交当前项目进度并复核本地检查；ADB 在线目标为 TARGET-PHONE `PHONE-ADB-SERIAL`，默认开发板 `BOARD-ADB-SERIAL` 不可达。该进度提交阶段没有安装或设备功能验收，详见 [验证记录](verification.md)。

- 2026-09-26 `portable-tools` 五项已接入并完成 TARGET-PHONE 安装验收：31 项 Flutter、7 项定向手机测试通过，SAF 导入/取消/导出读回及 HFTP 后台通知停止通过，09 指定公共 IP 的实际 HTTPS 查询通过。该阶段 APK 为 `f928af66…`，安装哈希一致；依赖随 APK、数据在 App 私有目录，没有全局安装。详见 [工具设计](portable-tools.md) 和 [验证记录](verification.md)。

- 2026-09-26 本轮源码将原工作台改为概览、原命令改为工作台，移除系统负载采集；加入 APK 内置 CPython 3.13.9、终端 `python3`、四个脚本 item 及二级运行页。Portable 清单支持后续追加 Android 原生工具。构建、本地 QEMU 和实际设备结果分别见 [验证记录](verification.md)，旧包实机结论不自动适用于本版。

- 2026-09-26 当前终端版已加入 App/Root 双入口、底部单一输入、Shell Tab 补全、五个控制键及可选择复制的输出页；同时收敛几个页面的重复说明。当前构建与设备验收以 [验证记录](verification.md) 为准，Android 11 开发板尚未重测本版。
- 2026-09-23 旧包曾在 Android 11 开发板完成网络、PTY、Vector 桥接和导出验收；该包已由 current 包替换。
- 当前 `dist/ctos-current-arm64.apk` 已去掉 Vector/Xposed 依赖，保留连接快照状态、应用与分身映射及终端功能。Android 16 当前包的 App API、Root 和 PTY 五项设备测试通过，工作台确认 Root 自动恢复。Android 11、全新安装授权弹窗与完整导出在当前包未重测；历史系统桥接记录不再作为当前验收条件。
- 规划方向：手机本机的系统观测与操作终端，形成“查看信息 → 定位对象 → 执行命令 → 保存结果”的闭环。
- 第一阶段的只读任务闭环与选择性导出已完成本任务验收，其他产品体验机会仍按 Trellis 子任务推进。
- 开发板默认 ADB 入口和每轮授权目标均按角色占位符记录；历史验收与当前连接状态分开记录，不推定设备始终在线。
- 当前版本的增强采集仅依赖 Root；普通 API 基础路径保留。完整结果及已知边界见 [验证记录](verification.md)。

## 维护规则

1. 开始工作先读本索引、规划、约束及相关领域文档。
2. 新增项目知识写入本目录，并在索引登记；优先更新已有文档，避免同一事实维护多份副本。
3. 规划描述目标，架构描述实现，验证记录描述实际执行及结果。注明日期、证据和未验证项，不把计划写成已完成。
4. 有取舍的重大决策新增 `decisions/日期-主题.md`，记录背景、决定、影响及替代方案，并从本索引链接。
5. 临时原始产物不作为设计文档。验证记录注明产物位置及是否持久保留，不将凭据或敏感数据写入文档。

2026-09-24：原 `docs/` 下的架构、兼容性、验证、依赖文档及根目录 CHANGELOG 已迁入本目录。本次为文档整理，未重新执行历史验收。

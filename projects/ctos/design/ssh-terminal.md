# SSH 终端规划入口

2026-10-30：[锁屏锁库并结束SSH、真实一键重连](verification.md#20261030-ssh-lock-disconnect-board)
已在指定开发板70.244秒通过；同一源码/产物、资源清理及正式包保持已核验。手机和逐版本
矩阵不作为当前前置，不推断未测版本不支持；本轮未覆盖正式安装或提交。

2026-10-28 当前：[锁屏结束SSH、用户主动重连](ssh-background-session.md)已由用户选择，
已实现并通过293 Flutter/775 JVM和构建lint/v2；手机ADB离线，新版未安装、实机待验。
停止锁屏保活/Doze和模板归因；旧板保活通过仅为历史证据。重连仍经解锁库及
新配置/身份/信任认证，不自动重连或重放命令，正式应用未更新。

2026-10-27：[Android11板真实锁屏补验通过](verification.md#20261027-ssh-physical-lock)：
真实熄屏/安全锁定15秒仍可执行命令，正常系统解锁不自动解锁秘密库，原SSH及实际终端
恢复/通知操作通过。结论限该板，不冒充手机/API36或深度Doze结果，正式应用未更新。

2026-10-26 后续本地实现：[后台会话与通知 Stop](ssh-background-session.md)已接服务所有权、
认证后材料释放、Activity恢复与通知操作。指定Android11板的后台锁库、重建、通知操作
及真实Flutter恢复/输入/导航已通过隔离验收；手机物理锁屏/API36仍待验。
下文已安装APK及前台使用结论保持其原版本范围，不据新源码推定设备具备后台能力。

2026-10-26兼容性修复：最终042c6b73…已在开发板隔离包通过RSA/config/PTY、密码及
公共视图回归；修正Android16的64KiB锁页与内部memfd chmod限制。
[当前验证/产物](verification.md#20261026-phone-ssh)。下节正式安装仍指开发板373版；
指定PLR110手机已保留数据更新042版，使用原有`.ssh/id_rsa`登录实际目标并验证PTY输出，
用户人工验收通过。测试会话已关闭，原秘密库、私钥及核验保存的主机信任保留。

## 已安装功能与使用方法（2026-10-26）

正式APK已保留数据覆盖安装到`192.168.9.14:5555`的AIO-3568J，实际安装SHA256为
`373f716137414227659d9d28a78f09a687291df39e1d03c43df5897f69146b1d`。
[设备回归、安装与清理](verification.md#20261026-key-config-device)已记录。

1. 打开“终端 → SSH 连接”，创建或解锁与验证码共用的秘密库。
2. 密码连接填写主机、端口（默认22）、用户名及密码。默认仅当次；可显式选择“连接时
   保存密码”，以后按精确端点/用户名选择已存密码，也可确认后单条或全部忘记。
3. 无口令私钥：进入“私钥与配置 → 导入私钥”，选择文件并核对算法/完整公钥指纹后
   确认导入；选择“使用此私钥”，返回连接页填写目标。私钥正文不交给Flutter。
4. config：先导入需要的私钥/Include，再“导入配置 / Include”；在“配置主机”选择
   native真实枚举的Host，按有效HostName/User/Port/IdentityFile连接。可“编辑配置”，
   旧来源或旧身份选择随修改失效，须刷新后重选。
5. 首次连接核对服务端地址、算法和完整SHA256指纹，确认后“信任并保存”；已知键
   匹配后不再询问，变化/撤销会阻止连接。StrictHostKeyChecking yes拒绝未知主机。
6. 认证及PTY就绪后进入终端，保留输入、Tab/Ctrl-C/Esc/方向键、尺寸调整和输出选择；
   “关闭会话”结束连接，“换用”结束当前会话后选择其他终端。

[私钥/config](ssh-key-config-connect.md)及[密码保存](ssh-saved-password.md)沿同一公共
加密库和原生引擎，SSH不要求Root，App Shell/Root PTY保留。新Android回归通过真实
MainActivity、ContentResolver、加密提交、JNI、主机核验、私钥认证与PTY；文件选择结果
由隔离provider提供，真实DocumentsUI/文件grant和此次新界面人工体验仍待用户验证。
未访问用户真实私钥或实际SSH目标。

当前支持前台密码与普通无口令私钥直连、受约束config/Include导入编辑。加密私钥、
证书/agent/KBI、ProxyJump、转发、rekey、后台服务和复杂恢复仍未交付。未消费的配置
明确拒绝，不忽略后直连；不承诺切后台保活。父规划继续in_progress，本轮不提交/归档。

## 历史交付记录

2026-10-26：[真实Android界面连接验收通过](verification.md#20261026-password-android)。
用户授权后，在AIO-3568J/Android11/arm64隔离包内实际完成密码连接、精确主机键确认、
命令输入与输出快照、返回及关闭；bridge的NEW保存/KNOWN重连同时通过。正式主线代码
未因本轮测试改动，新增一个真实UI回归测试。正式APK已构建，测试环境已清理。

2026-10-25 当前：[正式密码连接](ssh-password-connect.md)从终端选择器进入受保护表单，
保存公开最近端点与明确确认的主机键，密码仅本次使用；真实认证、PTY和shell就绪后才
启用终端。前台shell独立于原认证期限，锁库/撤销/停止仍关闭。
[本轮验证及剩余关卡](verification.md#20261025-password-connect)。

2026-10-25：[一个正式SSH模型与写入路径](ssh-manifest.md)采用最新KeyBinding/Account
结构，取消双格式业务选择，恢复检查实际未审阅metadata。共用库的旧纯TOTP输入可读取，
正常内容写入统一最新编码。继续补齐真实关系/账号/信任与连接器，使SSH形成可操作流程；
[本轮检查](verification.md#20261025-ssh-mainline)，父AC进行中，本地未提交。

以下历史记录中的格式划分已被本次用户决定取代。

2026-10-24：[全部证书事实与签名政策](ssh-certificate-facts.md)已实现CSCF168、三份公开摘要/unsigned数值/实际record来源及现代CA签名政策，保留trust/time/principal/revocation未验证。ARM22239与旧材料矩阵、定向145/全量638 JVM、normal100全重跑/fixture111及三APK审计通过；[实测](verification.md#20261024-certificate-facts)。持久graph/账号、完整信任/reviewer/provider/恢复/connector与父AC仍开放；本地未提交，无设备/真实库账号。

2026-10-23：[全部私钥材料与凭据归属](ssh-private-material-ownership.md)已实现全PRIVATE自签/验签、v1口令唯一owner/phrase审计和password namespace/deferred target。ARM19976/独立pair13812/material7784、定向120/全量613 JVM、normal98全重跑/fixture111及三APK审计通过；[实测](verification.md#20261023-private-material)。仍不提供持久关系图/真实密码账号/完整信任/provider/恢复/connector准入，父AC开放；本地未提交，无设备/真实库账号。

2026-10-23：[显式密钥配对证明](ssh-key-association.md)已实现精确ID/所选口令、证书plain subject及固定domain实签验签，拒绝仅公开字段相等的伪配对。ARM13814/独立材料7784/fresh认证1748333、定向80/全量586 JVM、normal96全重跑/fixture111与三APK审计通过；[验证](verification.md#20261023-key-association)。报告只覆盖明确所选pairs；全库关联authority/覆盖、凭据目标、完整信任/reviewer/provider/恢复/connector与父AC仍开放。本地未提交，无设备/真实库账号。

2026-10-23：[显式资源引用绑定](ssh-config-resources.md)已实现RESOURCE_AUDIT/CSCR、五类声明静态类型与真实parent直接引用，保留动态/cwd/key-loader未决。真实ARM4439含基线/全部旧矩阵/IO426，定向151/fresh全量564 JVM、normal94全重跑/fixture111与三APK审计通过；[验证](verification.md#20261023-config-resources)。材料数学关联、凭据/信任/provider/恢复/connector与父AC仍开放，本地未提交，无设备/真实库账号。

2026-10-23：[CONFIG静态引用与本地效果审计](ssh-config-audit.md)已实现独立AUDIT、真实Include父/行/子与parent直接references核对，以及所有分支有限政策。真实ARM13290含基线/原矩阵/IO426、定向124/fresh全量537 JVM、normal94全重跑/fixture111与三APK审计通过；[验证](verification.md#20261023-config-audit)。报告仍partial-only，动态/非Include引用、身份/凭据/信任完整语义provider、恢复/connector与父AC仍开放。本地未提交，无设备/真实库账号。

2026-10-23：[实际清单受管配置检查](ssh-detached-config-managed.md)已实现Java/JNI两阶段owner、全部CONFIG声明扫描与整job累计预算。ARM3683（含基线）/原矩阵/IO426、fresh JVM514及normal94全重跑/fixture111与三APK审计通过；[实际验证](verification.md#20261023-detached-managed)。引用/全分支政策、身份/凭据/证书/信任完整语义provider、恢复/connector与父AC仍开放。本地未提交，无设备/真实库账号。

## 2026-10-23 接续：独立 native 配置输入

[独立输入合同](ssh-detached-config.md)已实现，配置扫描/求值不依赖活动vault/public view，保留独占副本、原期限/取消与诊断ordinal。专项ARM1261、旧配置矩阵/IO426、JVM483与normal94/fixture111及APK审计通过；[证据与限制](verification.md#20261023-detached-config)。仅C-only基础，Java/JNI实际清单编排、引用/身份/凭据/信任语义、恢复/connector和完整客户端AC仍后续；本地未提交，无设备/真实账号操作。

2026-10-22最新：[SSH public read](ssh-public-read-lifecycle.md)已迁移原context、锁外
真实source/loan校验/closure与一次交付；native lease锁外创建后最终attach，原role预算/
view及own-CAS continuation保持。定向178通过；全量JVM431、normal92全重跑/fixture114与三APK审计通过。
[结果与边界](verification.md#20261022-ssh-public-read)。secret typed读取、mutation/trust
CAS和完整语义/恢复/最终计划connector仍后续；本地未提交，无设备/真实库账号。

2026-10-22最新：[TOTP typed read](totp-read-lifecycle.md)迁移完成，原Request贯穿锁外
校验/生成与一次read receipt；pure不依赖SSH provider，mixed绑定原注册。实际代码
交付保持当前窗口/锁库顺序，transport/Binder限制明确保留。定向140/fresh全量JVM410及
normal92全重跑/fixture114/APK签名隔离通过，[验证](verification.md#20261022-totp-read-validation)。
下一项为SSH public snapshot/sessionManifest读者的原请求/role/view/loan/publication合同，
再迁移secret release及mutation/trust CAS；完整语义、恢复和客户端AC仍开放。

2026-10-22接续：[bundle 生命周期](ssh-bundle-lifecycle.md)已实现原context/期限/取消/
注册绑定、独占候选与临时loan、锁外校验及一次effect消费；事务内禁止重读AtomicFile。
最终定向81/fresh全量JVM391、normal92全重跑/lint/Release通过，[完整验证](verification.md#20261022-bundle-validation)。
其他typed/trust/sessionManifest与全图语义/恢复/最终连接计划仍开放，父task保持进行中。

2026-10-21接续：[语义义务与恢复预览生命周期](ssh-validation-lifecycle.md)已冻结，当前
推进preview-only锁外校验/审阅、实际认证loan/擦除、原期限/取消/校验器注册和最终发布
复查。其他业务入口与完整语义/最终连接计划仍待实现，SSH恢复门保持；本地结果见
[验证](verification.md#20261021-preview-validation)，不沿用历史设备或账号授权。

2026-10-19接续：[启动器前置合同](ssh-launcher.md)的显式actual offered-host open与exact NEW
审批邮箱已实现，原PINNED/认证/PTY保留。native完整1,724,409、专项87,305与宿主JVM288通过，
新fixture源码编译通过；完整Gradle/APK门因自动审核服务容量错误待执行，不等同生产启动器
或设备验收。[当前验证与恢复命令](verification.md#20261019-managed-host)。

2026-10-19：[session channel / PTY / shell 合同](ssh-session-channel.md)已冻结并本地实现，
沿用认证SUCCESS-only、proof/撤销/原期限和owner约束；native/JNI/Java合成路径和现有
终端host-only接入均已落地，typed生命周期/代次/UTF8、SSH方向键和有界owner控制器有回归。
normal94全重跑、fresh JVM270、Flutter227、fixture113和ARM协议通过，详见
[验证](verification.md#20261019-ssh-shell)。产品profile/连接启动器、转发与前台服务仍独立
开放；设备/真实账号验收不由本地结果推定。

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

实现合同：12 session JNI、显式 service/password/private-key phases、全线程 native 重入 guard；
签名输入使用 framed raw session ID，expected-public parse/equal_public/local verify 后才排队。
RSA必须精确SHA2协商；私钥/口令同一typed capability与snapshot释放，1MiB总界，passphrase
8192/noNUL。加密OpenSSH KDF先检查bcrypt rounds<=64；加密PKCS8明确拒绝。
NEW只允许自身精确获批CAS保留当前同一public loan，同时推进epoch、使旧操作失效，
不延长deadline、不恢复锁定状态；原file request取消仍撤销。详见backend ssh-native七段spec。

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

2026-10-19 最新：CSASv1 metadata经Java session→JNI进入nativeownedcopy/hash，新增只读
receipt JNI入口（共6个sessionJNI），旧nullselection调用兼容。JVM208/0/0/0、最终ARM
253155checks/exit0、lint/Release/v2与正式check通过，APK `bda806ca…` 未安装。修改后的
JNI仅编译链接、未Android执行；credentialcallback/USERAUTH/privatekeymatch均未增加。
见[验证](verification.md#2026-10-19--csas-native-selection-metadata-and-receipt)。

2026-10-18 最新：Java managed immutable authentication selection 已实现，固定显式用户名、
精确role/receipt和passwordentry或privatekeypath/identity/expectedpublic；selected模式
拒绝legacykind-only与再次选择，provider绕过旧requestsource，bridgefinalguard重复检查。
新增12tests，freshJVM201/0/0/0、lint/Release/v2/正式复核通过，APK `e6c4d58b…` 未安装。
publicblob仅预选声明，尚无native receipt/wire认证/私钥匹配或selectedpassphrase/KBI；
[验证与后续边界](verification.md#2026-10-18--managed-immutable-ssh-authentication-selection)。

2026-10-18 本地协议最新：C client 新增显式 post-KEX `ssh-userauth` service begin/ready，
服务阶段复核 live owner/view/role/deadline 与同一 KEX proof，拒绝畸形回复和 rekey。
加密 KNOWN 与 NEW/CAS peer 的最终 ARM QEMU 合成回归 **246,808 checks / exit0**；
callback24、normal94全重跑/JVM189/lint/Release/v2 及正式复核通过，原 Java/JNI 仍为 KEX-only。这里没有用户名、凭据
释放或 USERAUTH_REQUEST；完整认证与产品 AC 继续开放。见[验证](verification.md#2026-10-18--native-post-kex-userauth-service-negotiation)。

2026-10-18 最新：当前 native 修复版已在恢复解锁的 `.14` 上通过精确 JNI 分片 banner /
初始 KEXINIT 读回 / KNOWN / 独立 EOF 清理测试，**OK (1 test)，0.202s**；两包及进程已
清理、正式 APK 不变。该项关闭 JNI transport 待测关卡，不等同完整 Android KEX 或
userauth；[本轮验证](verification.md#2026-10-18--recovered-board-android-jni-fragmented-transport--eof-pass)。

2026-10-17 最新接续：使用宿主 QEMU/Bionic 真实运行 production native ARM suite，最终
65,838 checks / exit0。已修复 role admission errno、pinned banner 分片状态、KEX output
未排空即完成及 failed-open guard 遗留；normal gate 94 tasks 成功，APK `f3327427…`
未安装。Android JNI 与真实 managed CAS/vault、userauth、PTY/forward/service/UI 继续
开放。下一本地协议切片研究见 [userauth 前置研究](../.trellis/tasks/archive/2026-10/10-08-ssh-terminal-client/research/next-userauth-20261017.md)，
尚未实现；[本轮验证](verification.md#2026-10-17--host-qemu-arm-native-runtime-and-production-fixes)。

2026-10-17 当前增量：独立 JNI fixture 以预算 3 编码验证分片 server banner、初始
KEXINIT 完整输出读回及 peer EOF 后两次 public `SSH_SESSION_STEP` 和 close 清理。
本地 compile/assemble/lint/签名与实现/检查代理复核通过；开发板用户存储为
`RUNNING_LOCKED`，系统拒绝启动 fixture，故本轮 runtime **not-run**。用户选择保留
本地结果，两包/进程已清理，正式 APK 哈希未变。待解锁后继续该窄关卡，完整 KEX/认证
及产品验收仍开放；[验证记录](verification.md#2026-10-17--android-jni-fragmented-transport--eof-fixture-local-pass-device-not-run)。

2026-10-16 当前最新补充（G3.8 Android ARM/JNI empty-peer EAGAIN）：fixture 在
production session open 后先对空 peer 调用 `step()`，验证 bounded
`bytesRead=0`、`inputAppended=0`、`readWouldBlock=true`，再验证
KNOWN host key。当前授权板精确 instrumentation `OK (1 test)`、status 0、0.11s，
fixture 已卸载；这仍不是 ARM KEX/packet、userauth 或完整 SSH runtime。

2026-10-16 当前最新窄增量（G3.8 Android ARM/JNI KNOWN host-proof）：fixture 使用同一
OpenSSH wire key blob 生成标准 managed `known_hosts` 行，production
`SshNativeSession.verifyHostKey` 在 revoke 前真实执行。当前授权板
`192.168.9.14:5555`（AIO-3568J/API30/arm64-v8a）精确 instrumentation
`SshNativeSessionPlatformTest#syntheticNativeSessionHandle` 为
`OK (1 test)`、status 0、0.089s，fixture 包清理完成。该项只证明 KNOWN
host-proof 的 Android JNI runtime，不证明 ARM packet/KEX、userauth 或完整 SSH；task 继续
`in_progress`/`implementation_complete=false`。

2026-10-16 当前最新窄增量（G3.8 NEW/CAS full-KEX）：在 KNOWN full-KEX 之后，空 managed
`ssh/known_hosts` 也由 production caller 真实走过 candidate handoff、`NEW` host evidence
`CAS_COMMITTED`、KEX-complete 和 peer `kex->done`。测试断言 candidate/evidence/KEX callback
exactly once、candidate→evidence→KEX 顺序、role/hop/port、exact replacement body 与双 digest、
cycle=1、peer-bound session digest、bounded I/O/EAGAIN/短写和 completion 后 `EALREADY`。这是
host/AArch64 cross-link 证据，APK SHA256 为
`7ccac2fb8f705edd761d34574b9cead4b9e2296864dfe5568d2e9b1105fd849f`；ARM runtime 仍因无
qemu 为 `not-run`，完整认证与产品 runtime 仍开放。

2026-10-16 当前最新窄增量（G3.8 synthetic KEX completion）：在既有 KNOWN host-evidence 与
NEW/CAS production boundary 之上，`ssh_native_client_test.c` 用非阻塞 synthetic OpenSSH
server 驱动 production client 到真实 `kex->done`。测试断言 host evidence exactly once、先于
KEX-complete exactly once，cycle=1，callback digest 非零且等于 peer session ID 的 SHA-256，
completion 后 `step` 返回 `EALREADY`，并观察 server 端 EAGAIN/短写与 client bounded I/O。
这仍是 host/AArch64 cross-link 证据，因无 qemu 未运行 ARM binary。normal gate 的 APK SHA256
为 `7195a67fad84185714d00cbb2bbc899fbdf788b61f722282fae8b99ae8ba5f69`；完整协议 runtime、
认证、PTY/forward/service/UI 与外部 SSH 仍开放。

2026-10-15 当前最新窄增量（G3.8 follow-up）：已在 production `ssh_native_client` C boundary 之上
完成 native-owned session/view boundary。`ssh_view_handle.[ch]` 用正整数 opaque token
登记一个 native-owned public view；`borrow` 固定 native lifetime，`destroy` 是可重入 revoke，
已有 borrow 会延迟底层 view 的最终销毁。`ssh_native_client` 在 open admission 中从 borrow
取得 live view，复制并解析 bounded host-key blob，校验 fingerprint 后由 native 持有
`struct sshkey`，成功 open 后接管 view borrow；close 先 callback/KEX/OpenSSH/socket，
再 release view borrow、释放 parsed key，destroy 只接受 closed tombstone。

`ssh_session_jni.c` 现在是真实 JNI bridge：64-slot monotonic opaque session registry、
owner pthread 检查、bounded array/string clone/wipe、八个 bounded step scalar 返回值和
可重试 close/destroy；五个 `SshNativeSession$Native_*` symbols 均来自 production library。
Java `SshPublicFileLease` 在显式 revoke 后保留失败 cleanup token 供重试。`SshNativeSession`
平台测试只覆盖 synthetic socketpair 的 open → view revoke → close 生命周期；它证明的是
native handle/borrow 边界，不是 userauth、完整 packet event loop、host proof、PTY、forward、
service/UI 或外部 SSH。另已修正 `step` 在 managed view 到期时错误改写 errno 的问题，并
以 host-only synthetic socketpair 覆盖 EAGAIN、EOF、deadline terminal semantics 以及
fragmented server banner 后的 bounded client banner/KEX output accounting；另以 synthetic
managed `KNOWN` key 覆盖 production host-evidence success 与错误 fingerprint 拒绝。该项仍
以 managed-key `CHANGED` classification 拒绝和 matching `@revoked` key 的明确 `REVOKED`
classification 拒绝为补充；当前又覆盖有效合成 host certificate 的 `CERTIFICATE` 以及
malformed managed known_hosts 的 project-owned `INVALID` 预检分类。`INVALID` 不是伪造
OpenSSH `HostStatus`，而是 issuer 在 `load_hostkeys_file()` 前通过
`hostkeys_foreach_file(..., HKF_WANT_PARSE_KEY, ...)` 识别的 fail-closed 结果；两者都直接
检查 bounded key/fingerprint/digest metadata、无 candidate handoff，并在 host evidence
前由 production verifier 返回 `EACCES`。合法 no-match `NEW` 现在也由 production caller
串起完整的 candidate handoff → host evidence `CAS_COMMITTED` 边界：测试逐字节比较 replacement
body 和 digest，并覆盖 evidence rejection 后 candidate 单次 abort、client terminalization、
后续 `EALREADY` 与 close retry。仍不是 ARM runtime 或完整 host policy 验收。

固定证据：normal gate 为 JUnit **189/0/0/0**、view/native/callback host gate 通过、
`SSH_PRODUCTION_OPENSSH_BUILD gate=pass fixture=excluded`、
`SSH_NATIVE_CLIENT_BUILD gate=pass runtime=not-run reason=qemu-aarch64-unavailable`，
strict compile/lint/三 ABI Release/assemble 通过；APK `7f0bd0f1…`，archive=83，五个 JNI
symbol 可见。`.14` API30 arm64 独立 fixture 的两个 1-item instrumentation 方法均 status 0，
运行后两个 fixture 已卸载，正式 APK path/hash 保持；没有 qemu、真实 vault、凭据或外部 SSH。
task 保持 `in_progress`/`implementation_complete=false`；这些 host regressions 仍只是
AArch64 cross-link，未执行 ARM runtime 或完整 packet/KEX/host proof。

2026-10-15 历史窄增量（G3.6）：已在 production `ssh_native_client` C boundary 之上
加入 package-private `SshNativeSession`。owner event-loop thread 独占 `open/step/
verifyHostKey/close/destroy`，`cancel` 可从其他线程请求；callback 的
`NativeSessionClaim` 防止同一 binding 重复拥有 session。成功 close 的 Java 顺序是
native close 完成后记录 callback claim 已释放，再 release role、close public view、
destroy native tombstone；失败阶段保持可重试，failed open 保留 caller FD/callback/view。
host-key blob 和 fingerprint 均为 bounded clone，并在 native binding 调用后清零。

这是 Java ownership contract，不是 Android runtime pass。生产 `SshNativeSession$Native_*`
JNI entrypoints 已链接，但 `ssh_session_jni.c` 由于当前 C API 只有借用的 `sshkey`/
`ctos_ssh_view` 指针，故全部 fail-closed 抛 `IOException`；在 native-owned session handle、
view snapshot/borrow 和关闭协议具备前不得把 Java token 强转指针。JVM fake binding
验证了 12 项 session seam，完整 JUnit 为 **189/0/0/0**；callback=20、native
`runtime=not-run`、三 ABI Release/lint/assemble/strict compile 通过，APK SHA256
`21d16325b242427c2ccfbb7aa9cf42c6ca359700dc7b7f52995a95de06b94e72`，production archive
仍为 83 members。没有 qemu/device/真实 vault/凭据/外部 SSH；task 保持 `in_progress`。

2026-10-15 上一窄增量（G3.5）：已完成从 managed hostfile issuer 到 normal APK 的 pinned
OpenSSH source/object closure 与 native C caller 接入。Gradle `compileSshProductionObjects`
调用固定 hash runner，在临时目录用 pinned 10.6p1 `libssh-pic.a` 源码目标重编全部 PIC
成员，替换 stream-writer/KEX guard 对象并加入 PIC PKCS#11/SK closure；生产归档 83 个
成员，defined symbols 不含 `ssh_connect`/`ssh_login`/`ssh_userauth2`/`ssh_proxy_*`。
`ssh_native_client` 在 normal `libctos_ssh_files.so` 中实现同进程 bounded nonblocking
read/append/packet/write、host evidence/KEX callback 和 close retry；成功 close 顺序为
callback → KEX unbind/OpenSSH free → 原 caller FD，open 失败不消费原 FD/callback。

实测：G3 fixture `run.aqN1F2bD` 为 `SSH_HOSTFILE_CALLER_BUILD gate=pass runtime=not-run
normal_apk=fixture-caller-not-wired`；生产 native host 为 `gate=pass runtime=not-run`，
callback=20，JVM=177/0/0/0，strict `compileSshFiles`、三 ABI Release、lint、assemble
通过，APK SHA256 `805fc28679aa56be85fa3be75a1608701273908a02f7fb04dddb717c4e1b601e`。
没有 qemu/ARM runtime、设备、真实 vault、凭据或外部 SSH；C caller 虽已进入生产库，
Android runtime/JNI/Java 会话编排、认证/typed credential、PTY/forward/service/UI 仍未完成，
task 保持 `in_progress`。

2026-10-15 上一窄增量：已实现 host/fixture-only 的 pinned OpenSSH hostfile issuer seam。
issuer 要求 public view 以 `ssh/config` 为 `CTOS_SSH_CONFIG` root 并引用
`ssh/known_hosts`，用 `load_hostkeys_file()` 从 caller-owned memory stream 复用上游解析，
并以 project-owned `ctos_hostfile_write_entry(FILE *)` 复用上游 formatter；不访问 vault
pathname，也不调用 pathname-bound writer。非证书分类沿用 `HOST_OK/NEW/CHANGED/REVOKED`，
证书仍由当前 managed policy 拒绝，但 revoked certificate/CA 先得到 `REVOKED`。NEW 的
exact body/digest 进入已冻结的 one-shot candidate handoff。

固定 `research/android-hostfile-issuer-build.sh` 已对批准的 G1 baseline 完成 manifest/
patch/hash、严格 C11/Werror、AArch64 PIE link 和 normal-APK exclusion；结果为
`SSH_HOSTFILE_ISSUER_BUILD gate=pass runtime=not-run normal_apk=not-wired`。容器没有
`qemu-aarch64`，所以这只是可复现 source/object fixture 证据，不是 runtime/设备/SSH
验收。production caller、socket partial I/O、KEX/NEWKEYS、认证、PTY/forward/service/UI
仍未接入，`ssh_native_client` 继续 `ENOTSUP`，task 保持 `in_progress`。

2026-10-15 最新实现：在 HostEvidence/CAS seam 之上接入了真实 managed
`known_hosts` provider，并冻结独立的 opaque、同步、one-shot native→managed candidate
handoff。`snapshotFor` 从 `AppSecrets` 取得精确 public file snapshot；C/JNI handoff
只接受 exact role/hop/target/connection/host/port/key-type/fingerprint、base/replacement
digest、candidate ID 和不超过 `MAX_FILE_BYTES` 的 replacement body。managed receiver
校验 snapshot/provider ownership 后私有复制 body，native temporary 与 JNI array 在成功和
失败路径清零；现有 metadata-only CSHR wire 不变。原 proposal/CAS 仍按 object identity、
vault UUID/代次/epoch、host proof 和双 digest 绑定，只有 guarded commit 成功才返回
`COMMITTED`。完整 Release 门禁：177 JVM、native client gate=27、callback gate=20、strict
compile/lint/三 ABI assemble；APK `3a6228f4…` 未包含 host-only boundary 或 fixture
OpenSSH object。

这一步冻结并实现的是 handoff + managed vault writer，不是 production OpenSSH caller：
pinned hostfile issuer 只在下一层 fixture seam 产生 candidate body，`ssh_native_client` 在
normal production path 缺少 issuer/caller 时继续
`ENOTSUP`。真实 OpenSSH event loop、hostfile parser/issuer、KEX/auth/PTY/forward/service/UI、
设备和外部 SSH 仍未验收，task 保持 `in_progress`。

2026-10-15 前一窄增量：已冻结 role/hop/target-bound HostEvidence/CAS
native↔Java contract。C/JNI callback 重新校验 classification、key type、fingerprint、
base/replacement digest 及 bounded result；KNOWN 只返回 `CAS_NOT_REQUIRED`，NEW 必须
经过 exact proposal、显式 approval 和 exact CAS `COMMITTED`，proposal body 不过 JNI。
host gate=15、166 JVM、strict compile/lint/Release 通过。当时只有可注入的
`TrustCasWriter` seam；具体 managed known_hosts CAS provider 已由本页顶部增量接入，
真实 OpenSSH caller 仍未接入；
`ssh_native_client` 缺 provider 时继续 `ENOTSUP`，task 保持未完成。

2026-10-15 生产 caller 阻塞复核（前一窄增量，已被顶部 handoff 取代）：pinned OpenSSH 10.6p1 source map 确认 `ssh_api`
caller API 可用，但当前仍没有可编译的 native-facing known_hosts issuer/candidate
handoff。Java managed provider/CAS writer 已完成，exact replacement 仍由受管
caller/测试注入，不能把 Java CAS writer 或 fixture 的 `ssh_api` 内存流当作生产 caller。
因此 `ssh_native_client` 继续做 fail-closed boundary 和 strict host regression：校验
live role/PID/UID、CLOEXEC/NONBLOCK 受管 socket、callback token/environment、deadline
和 bounded step，缺 issuer/handoff 稳定返回 `ENOTSUP`，不猜 fingerprint trust、不创建
OpenSSH/KEX client、不触发 JNI/credential。host gate=27；普通 APK 未包含该 boundary
或 fixture OpenSSH object。待 pinned hostfile issuer、一次性 candidate handoff、同构
source/object manifest 冻结后，才补真实 partial I/O、EOF、cancel/deadline、close retry、
inbound NEWKEYS/JNI proof，详见任务 [阻塞记录](../.trellis/tasks/archive/2026-10/10-08-ssh-terminal-client/research/production-kex-caller-blocker-20261015.md)。

2026-10-14 前一窄增量：同进程KEX-only native launcher仍仅作为独立opt-in fixture/host seam；用户已确认同进程 topology 与当前 native event-loop 线程同步附着 JNI callback，adapter 已编入正常 `libctos_ssh_files.so`。当时已实现 bounded managed-provider handoff：生产 NEW host 只能由精确 role/hop-bound proposal 通过，typed password/private-key bytes 只能在匹配 host proof + KEX proof 后经 `SshCredentialBridge` 释放，native/JNI 会清零 Java array 与 caller buffer。adapter 对 role/PID/UID/deadline、JNIEnv/JavaVM、线程、重入、取消、迟到回调及 native-first cleanup fail-closed；host `gate=11`、162 JVM、lint、Release 构建通过。当时真实 OpenSSH event loop/caller、受管 config/known_hosts file view 与 trust CAS 写回、ARM、设备、外部SSH、vault、auth/PTY/forward/service/UI仍未接入。
下一历史阶段是把已实现的 managed file/provider contract 接到真实 OpenSSH caller；不把 `ssh_api` 内存流或 fixture control transport 视为产品 transport。

2026-10-13 SSH当前增量：Java role/trust/KEX/typed-secret bridge 已完成，bounded native role bootstrap 的 ownership/open/release JNI 已接入正常 `libctos_ssh_files.so`；native 输出清零、跨进程/UID close、stale token、Java registry 配对与失败重试均有回归。150 JVM、lint、host bootstrap 1198/1/1198、固定 KEX native 构建和正常 Release 构建通过；当前 APK `538cae5a…` 包含生产 public-file 与 role JNI。这里只证明 host/fixture seam 和 JNI contract，尚未由生产事件循环调用或接入 native launcher/OpenSSH callback、协议认证/PTY/转发/服务/UI或设备/外部账号；完整客户端仍开放。

2026-10-10 公共接口约束：SSH 受管文件闭包与共同 v1 密文库采用同一上限：128 文件、单文件 1 MiB、文件总量 8 MiB、引用深度 8（完整 manifest 明文 12 MiB）。超限完整拒绝，不截断配置语义；旧父设计 256/16/32 MiB 工程候选已收敛。公共文件图事务由底座实现，生产配置/密钥/信任语义、实际 native 使用及恢复仍由 SSH 接入联合验收。

2026-10-10：最新完整规划已获用户批准，公共底座子任务已进入 in_progress，前置协调 SSH 内存文件访问探针。SSH 客户端完整接入仍待底座验收，父任务保留规划/集成职责；尚未实现或验证 SSH 产品功能。下文保留规划契约，实施证据随实际结果更新。

用户要求在“终端”增加依托 APK 环境的 SSH 客户端，用于连接设备；读取 OpenSSH 风格 config 并提供主机一键连接，终端参考已有应用 Shell 与 Root PTY。

| 规划产物 | 内容 |
| --- | --- |
| [任务 PRD](../.trellis/tasks/10-08-ssh-terminal/prd.md) | 用户目标、现有证据、需求与验收、完整交付边界 |
| [技术设计](../.trellis/tasks/10-08-ssh-terminal/design.md) | APK 能力边界、客户端比较、配置/凭据/信任、共享终端及生命周期 |
| [实施计划](../.trellis/tasks/10-08-ssh-terminal/implement.md) | 收敛门槛、实施顺序、验证矩阵及回退 |

2026-10-08 用户选择 B：Include / ProxyJump 进入首版，基础及主要行为参照 OpenSSH。技术主方案为 APK 内置真实 OpenSSH 客户端，复用 Portable 和 JNI PTY；尚未固定版本、构建或验证 Android 可行性。

用户随后选择存储方式 A：“让用户自己导入”。配置/Include/密钥由用户主动导入公共库的稳定受管逻辑结构，持久副本加密、运行经解锁后的临时内存访问视图，保留引用结构；外部修改只通过用户重新导入生效，不持续同步外部目录。首版按建议拒绝 Match exec、自定义 ProxyCommand、LocalCommand、KnownHostsCommand 等配置驱动本地命令，无手动放行，保留受管 ProxyJump。密码/私钥口令允许用户主动选择长期加密保存，同时保留仅当次使用；解锁最新改为共享主口令/可选设备系统快捷模型，使用凭据前检查公共库会话和本次连接上下文；用户已确认 SSH 后台保留连接，与已有两个终端保持手动 Stop 的统一心智模型：导航不主动断开，Stop/换用显式结束当前会话，后台通知提供 Stop；资源由独立 SSH 前台服务持有。连接保留不延长凭据解锁授权，不自动重连或重放；用户同意 config 基础 TCP 转发进入首版（LocalForward / 固定目标 RemoteForward / DynamicForward），与当前 SSH 一起 Start/Stop；监听策略已确定默认仅本机，config 显式对外监听时连接前展示暴露范围并确认，绑定本次配置/会话，拒绝取消连接；SSH 输入及 ↑/↓ 已明确交给远端处理，ctOS 不追加远端程序逻辑或模式，不设特定程序兼容验收；config 不得绕过主机指纹核验已确认，未知指纹确认、变化指纹拦截；主要认证/config、工程界限、技术关卡与延后项已整理；统一秘密模型及剩余范围已整理，待共同最终审阅。

安全比较与统一秘密库接入已补入技术设计：A 更易减少第三方/外部源的读取和篡改面，但不隔离同 UID 的本机 Shell/Python，也不保证 Root 或进程被攻破时的秘密保护。目录方案、配置本地命令拒绝及可选长期加密保存已确定；共享主口令/可选快捷模型已确定；禁止 config 绕过主机核验已确定，库内 SSH 保存凭据随统一加密备份迁移，config/Include/私钥/关联公钥或证书/信任已确认与凭据同包加密保护/迁移，导出/覆盖遵循公共底座重新手动输入和真实认证解密，导入校验引用/身份/路径并审阅信任，认证失败保留旧库，成功解密不等于远端身份可信；原生加密文件临时视图/引用兼容和平台保护未实测，列为优先探针，不偷偷改成长期明文目录。

用户已要求统一秘密模型，权威入口见 [App 统一秘密库与认证](app-authentication.md)。现有公共底座 task 扩展为共享主口令/便携库/设备快捷/会话/迁移，SSH 特化仅身份、信任及远端认证通道，Authenticator 亦依赖同一底座；先公共底座后两个业务接入，任务树不自动代表调度。当前均重新收敛，不能沿用旧完整版本启动。

本轮完成仓库及官方文档研究和规划文件检查，不包含产品代码、APK 构建、安装、SSH 登录或设备操作。实现后再在本文记录批准契约，当前实现仍见 [架构](architecture.md)，实际验收见 [验证记录](verification.md)。

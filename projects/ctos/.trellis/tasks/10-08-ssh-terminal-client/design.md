# SSH 客户端与公共认证集成设计

## 2026-10-19 本连续阶段已完成：Android/JNI + 私钥认证

已完成密码与未加密/有界加密私钥的生产Java/JNI编排及独立Android实测，不再把两项列为
尚未实施。CSASv2/CSKBv1精确口令能力、RSA SHA2协商、expected-public/local verify、
全线程重入、真实view/role期限和自身NEW/CAS生命周期修复均有真实合成正反回归。

最终fresh JVM244/0/0/0、ARM1244010checks/exit0、normal94tasks65s/lint/Release/v2、
archive83/12JNI/forbidden0通过。开发板密码/未加密私钥/加密私钥/真实NEW各精确方法OK1，
26.954/27.418/23.781/23.906秒；两包进程清理，正式APK3bc762d9未变，未用真实vault/账号。
普通APK89eb6425未安装；完整证据/格式限制见design/verification.md#20261019-android-auth。
加密PKCS8、KBI/MFA、rekey/压缩、channels/PTY/forward/service/UI和父任务AC继续开放。

安全合同先冻结为七段spec，再由main实现密码之后的private-key、atomic key/passphrase与
own-CAS continuation；早期Java/JNI/fixture实现代理结果已复核，main独占所有构建与设备。
不能把key identity替代remote target，不允许第二次回调kind2；KDF先有界；仅own exact
CAS可保留原loan并推进epoch，manual lock/原file cancellation仍撤销，无状态复活。

## 2026-10-19 连续阶段初始方案（已完成，实际结果见顶部）

当前先接四个password service/auth JNI方法，Java显式phase保留KEX complete语义；static
ThreadLocal覆盖全部session native entry，防止同/异session重入全局锁。独立fixture peer
动态依赖生产libctos_ssh_files，不复制archive，真实MemoryStore AppSecrets/lease/role/
callback/session走KNOWN四结果/取消。完整七段合同见ssh-native.md顶部。

随后private-key使用ownedCSAS expectedpublic与rawsession签名；必须parse/equal_public且
本地expectedpublic验签后才排队，以防Ed25519序列化private字段与public字段不一致。
RSA SHA2协商、encryptedkey/passphrase独立能力合同另冻结；不传NULL算法落到SHA1。
本地实现和检查连续执行，设备exact包就绪后按当前授权；不使用真实账号。

## 2026-10-19 当前切片：C-only password USERAUTH（已实现，本地合成验证通过）

显式begin_password_auth从ownedCSAS用户名/固定PASSWORD选择构造唯一USERAUTH_REQUEST，
复用selectedhandoff的一次尝试、currentcycle/rawsessiondigest/READY与private scratch检查。
密码1..8192bytes、拒任何NUL，put_string不截断，64padding；send后或构包失败立即擦除
plaintext（失败仅sshpkt_start(SSH2_MSG_IGNORE)重置、不send），所有scratch wipe/free。

独立authphase，REQUEST_PENDING全部排空后才AWAIT_REPLY；step复用boundedtransport并在
I/O前/最终结果前查当前proof。只保留SUCCESS/REJECTED/PARTIAL/CHANGE_REQUIRED枚举，
无自动retry/nextmethod；banner有界丢弃，无UI/promptauthority。新constresult accessor无
I/O/provider/clientmutation且错误时清out。完整七段签名/矩阵/兼容合同见ssh-native.md顶部。

实际验证：67新增连接场景；finalfocused7tasks13s、callback35/strictnative；normal94tasks
19s（23exec71uptodate）、lint/Release/v2通过，JVM221/0/0/0为保留报告而非新跑。
最终QEMU785537checks/exit0，正式checker无productionfinding；main补强pending-BANNER
positive且实际运行通过。APK983f2032未安装。ENOMEM/hash/proof漂移、构包失败reset、
padding/upstreamwipe和精确postparsecancel未动态注入；无Java/JNI认证执行或完整transport
兼容声明，详见design/verification.md最新节。

仅Cclient.h/.c与nativeclienttest.c由实现代理写；main独占所有构建/QEMU与文档，checker
只读正式复核后协调必要修正。无JNI/Java变化、无上游patch/archive成员或真实凭据操作。

## 2026-10-19 当前切片：receipt-bound selected credential handoff（已实现，本地合成验证通过）

把CSAS身份回执接入一次selectedtypedbytehandoff，不发USERAUTH/不解析keys。Cclient
唯一新入口从ownedCSAS派生kind/receipt，要求READY、unique/currentKEXcycle和当前raw
session_id SHA256等于成功上报的digest。保存completiondigest，provider前后都核对。
使用privateboundedsecretscratch，postcheck通过才copycaller，所有失败wipe/length0。
preflightEPERM/premature不consume，scratchalloc可重试；调用selectedcallback前consume，
之后任何失败terminal且不自动重试，success也不代表authenticated。

Ccallbacktarget/API追加selected(kind,receipt32,cycle,digest32)，缺失ENOSYS且不回退旧
kind-only。JNI唯一Java方法onNativeSelectedCredentialBytes(I[BJ[B)[B；Java验证frozenCSAS
digest、currenthost/KEXobject/cycle/digest、ownednativeclaim，复用callbackpipeline。
nativeclaim首次issued后sticky拒绝旧objectselectedoverload；所有selected路径shared
one-shot，legacyunselected兼容。新增provider/bridge finalEffectGuard在AppSecretsreceiver
前检查currentadapterinvocation/proofs/cancel/clock，返回前再查；不在guard重入vault。

实际验证：final focused66/0/0/0（57tasks33s）、normal94tasks66s（29exec65uptodate）、
fresh full JVM221/0/0/0、callback35、最终QEMU323726checks/exit0、lint/Release/v2通过。
正式复核已修JNI异常检查顺序、finish取消/重入、Error逃逸后invocation清理，无遗留finding。
Error原样传播，receiver借用数组擦除与owner cleanup实测；adapter内部result wipe源码复核。
APK `f329056e…` 未安装。新JNI未Android执行；nativecycle/rawsession漂移及ENOMEM/hash故障
未动态注入，不能外推为完整认证通过。完整hash/命令见design/verification.md最新节。

完整7段错误/ownership/test合同见ssh-native.md最新节。main负责spec/docs/task及所有
build/QEMU，C和Java实现代理按冻结接口各自所有权并行，之后统一check。无设备/账号/
真实库操作，不提供JavaSessionsecretgetter，sessionJNIexports仍6；实际auth继续开放。

## 2026-10-19 CSAS v1 native selection metadata（已实现，本地合成验证通过）

沿用managed immutable selection，以CSASv1 canonicalbigendian record穿过现有sessionopen
到JNI/nativeclient。字段为magic/version/flags、native role token/hop/roleNonce/
connectionBinding、kind和五个length-prefixed username/entry/path/keyIdentity/publicblob；
max1MiB+2048，SHA256exactwire作metadatareceipt，不是认证授权。完整字段/错误合同见
ssh-native.md新7段CSASsection。privateblob解析、credentialcallback和USERAUTH均不加入。

Java从callback当前frozenplan生成wire，不接caller独立plan；NativeBinding新增nullable
wiredefaultoverload，legacy null委托旧open，非null默认拒绝，Production单一Native.open
追加array参数。native在任何ownershiptransfer前校验strictUTF8/shape/bounds和exactrole
binding并copy/hash；成功后ownedwire随close清零释放，failedopen/closeretry合同保留。

新增只读receiptaccessor（Java/NativeBinding/JNI/C）；nativeowner/liveview/role/deadline
检查但不要求KEX，所以preKEX和completedlive均可读；Java显式核对expected32byte digest。
不在nativeopen成功后做额外可失败的自动核对，避免把已消费socket说成open失败未消费。
本轮只本地JVM/ARMsynthetic、strictbuild和Releasepackaging；无设备或真实凭据操作。

实际结果：focused53/0/0/0、56tasks28s；正式复核修正directadaptercancelguard及encoder
scratchfinally两个问题，无remainingfinding。normal94tasks64s（29exec65uptodate），fresh
JVM208/0/0/0、lint/Release/v2、archive83/sixJNI/forbidden0通过；最终QEMU253,155checks/
exit0。新JNI只编译/链接未设备执行；完整hash/覆盖限制见design/verification.md最新节。

## 2026-10-18 managed authentication selection（已实现，JVM 合成验证通过）

承接 service-ready，只实现认证前 managed metadata/provider 合同，不发送 USERAUTH_REQUEST。
新增 immutable SshAuthenticationSelection：精确 live role object、显式 strictUTF8 username
（1–255bytes/noISOcontrol/不trimnormalize）、PASSWORD exactentry，或 PRIVATE_KEY
managedpath/keyIdentity/defensive-copied expected publicblob。身份匹配不能仅依赖不含nonce的
role.binding()；本轮 managed receipt 使用同一 selection object，不冻结 native serialization。

adapter.selectAuthentication 在 owner线程、任何callback或sessionclaim之前one-shot冻结；
sessionopen失败不能重新选择。selected byte overload必须携带相同selection，旧kind-only
callback在selectedadapter拒绝；未selected路径保留legacy合同。selectedprovider直接通过
bridge.openSelection按固定字段开request，不调用可重新选择的CredentialRequestSource。
Request保留selection，在final AppSecrets effectguard重复精确identity/kind/entry/path/keyid
及既有role/host/KEX/operation检查，保留boundedcopy/wipe/one-shotclose。

私钥expectedpublicblob仅声明预期公钥，真实parse/equal_public留nativeauth阶段；本轮不
支持selected KEY_PASSPHRASE/KBI。已发现manifest passphrase key:identity与现有operation
target绑定不同，未来须独立设计，不修改AppSecrets/VaultOperation来绕过。完整7段合同见
.trellis/spec/backend/ssh-native.md 的 Managed authentication selection 节。

实施边界：Java selection/bridge/provider/adapter及JVMtests；main独占hako/Gradle等执行，
代理不做设备或真实凭据操作。先focused JVM/lint，再适当normal/Release包装验证；C/JNI
wire、服务阶段接入、真实认证和产品AC继续开放。

实际结果：实现/正式复核无remainingfinding，新增12tests；focused46/0/0/0和lint通过，
normal91tasks/57s（26executed65uptodate），fresh全量JVM201/0/0/0、Release/v2/包装通过。
本轮C/JNI源码不变，无设备动作；完整产物hash和覆盖限制见design/verification.md最新节。

## 2026-10-18 post-KEX ssh-userauth service 协商切片（C 实现与 ARM 合成验证通过）

沿用既有本地实施授权和 `next-userauth-20261017.md` 研究，仅扩展 production C API：
`ctos_ssh_native_client_begin_userauth_service(client)` 显式从 KEX-only 停止点进入服务
协商；`ctos_ssh_native_client_userauth_service_ready(client)` 只报告该服务 milestone，
不代表已认证。既有 `complete()` 继续表示初始 KEX milestone；未显式 begin 的 Java/JNI
caller 仍在 KEX 后收到 EALREADY，JNI API/wire 不变。

begin 只在原 callback owner/environment、live role/view/deadline、一次成功 KEX callback
和同一非零 KEX guard cycle 下排队一个固定 `SERVICE_REQUEST("ssh-userauth")`。新增
无 secret/provider 副作用的 `ctos_ssh_callback_check_live(callback, environment)`，复用
现有 begin/finish validation，检查 owner/environment/role/cancel/reentry/deadline；不调用
host/KEX/credential target。在 service begin 与每个 service step 前校验，不能拿 credential
callback 当存活探针。错误线程/environment 为 EPERM 且不破坏原 owner 状态；有效 owner
上的失效/取消/超时 terminalize 并保留 close retry。

状态显式区分 KEX-only、request pending、await accept、service ready。仍使用 OpenSSH
sshpkt/ssh_api 编解码和有界 socket read/write；request 必须排空后才能接受回复，success
后停止，后续 step/begin 为 EALREADY。进入 service phase 时禁用 KEX 20–49 dispatch，
让重入 KEX/NEWKEYS 在 caller 处 EPROTO 拒绝，不能在 ssh_packet_next 内悄然 rekey 并
把旧 proof 当新授权。EXT_INFO 继续由固定 upstream handler 处理。

兼容性：SERVICE_ACCEPT 无 payload 或一个零长度 string 保留 stock empty-reply 容忍；
若携带非空 service string，必须逐字节等于 ssh-userauth 且无 trailing bytes。该非空值
匹配比 stock 仅打印任意字符串严格，明确用于本请求绑定；不改服务名或扩大兼容承诺。
truncated/oversized/wrong/trailing payload、早到 SERVICE_ACCEPT、USERAUTH_* 或 rekey
均 EPROTO terminal；ready 后新到重复回复不再解析，后续 step EALREADY，不二次授予状态。

错误：NULL=EINVAL、closed=ENOENT、failed/duplicate=EALREADY、pre-KEX=EACCES（无排队、
不损坏正在进行的 KEX）；view revoke=EACCES、deadline=ETIMEDOUT、callback/role cancel
保持对应 precise errno；current cycle mismatch=EACCES。close/destroy/FD/view/candidate
ownership 沿用原合同。全过程不发送 USERAUTH_REQUEST、不请求 typed credential、不
加入 username/secret 字段，也不链接 stock ssh_userauth2。

验证：保留原 KEX-only 回归；在同一真实 OpenSSH synthetic peer 上完成 KNOWN 与 NEW/CAS
KEX 后解析实际加密 SERVICE_REQUEST，再分片送 SERVICE_ACCEPT（exact/empty），检查
request exactly once、零 credential calls、bounds/accounting 与 ready 后 EALREADY。
负例覆盖错误/截断/trailing/早到/未请求 accept、USERAUTH_SUCCESS、rekey、cancel/deadline/
stale role/view、owner mismatch 和 retryable close。strict build、actual ARM QEMU 与 normal
gate 分开记录；本轮不操作设备/真实库/账号，不宣称 Android service/userauth 验收。

实现/正式复核已完成；最终真实 ARM QEMU 为 246,808 checks / exit0，callback host 为
24 组，normal94 tasks全部重跑、JVM189/lint/Release/v2通过。current-cycle mismatch 分支仅源码复核，未为动态注入暴露内部
mutable ssh 指针。最终 normal gate 和产物信息以 design/verification.md 本日 service 节为准。

## 2026-10-18 JNI 合成 transport 实测补证

设备恢复解锁后，用当前四处 production 修复重建独立 fixture，原精确方法在
AIO-3568J/API30/arm64 上 **OK (1 test)，0.202s**。预算 3 的分片 identification /
初始 KEXINIT 输出、matching KNOWN/revoke 和独立 EOF/public terminal/close 合同均
真实通过 Java→JNI→native 执行；两包清理、正式 APK 不变。本轮没有新增 API 或设计
变更；该证据关闭此前锁定下的 JNI 窄关卡，不扩大为完整 Android KEX/userauth。

## 宿主 QEMU runtime 补证（2026-10-17，native 合成验证通过）

hako 无 QEMU 不代表宿主无 QEMU。使用宿主现有 `qemu-aarch64`、既有 Android API28
arm64 PIE 测试与从当前 API30 板只读取得的公开 Bionic runtime，在 `/tmp` 中运行
本地 socketpair synthetic regression。公开库不入仓库，不安装或更改设备；无真实凭据。
沙箱拒绝必要的 socket type 查询时，通过工具的权限审核运行同一限定测试，不改测试或
production admission 来适应沙箱。记录 toolchain/binary/runtime hash、timeout、退出码和
实际断言结果；测试失败须定位并复核后修复，不再把 cross-link 成功当 runtime pass。

Android JNI 分片 fixture 的设备锁定关卡保持开放；QEMU 只补 native ARM 执行证据。

实际 ARM 执行进一步暴露 pinned `ssh_api.c::_ssh_read_banner` 的分片状态问题：未收到
完整行时 input 未消费，但传入的 version buffer 已包含部分 `SSH-`，上层把非空版本当
完整 identification，过早进入二进制 packet parser。采用最小 upstream source patch：
incomplete 分支 reset version buffer 再返回，保留原 input/版本/长度/前导行校验；固定
patch hash，仅对临时构建源应用并替换 PIC `ssh_api.o`，不修改 G1 原始基线或删减回归。
production builder 的 source/object manifest 和 Gradle inputs 一并记录该补丁。

独立复核确认 completion 还必须等待本端待发 OpenSSH output 清空：不能在收到 peer
NEWKEYS 的当步先置 completed、再只写出一个 step budget 后永久 `EALREADY`。增加快
server / 小 client budget 的 KNOWN 与 NEW/CAS 回归，先重现，再把唯一 KEX-complete
callback 移至本端 output 已消费完毕之后。既有慢分片、exactly-once、terminal step
和可重试 close 契约保留；这不是 userauth-ready 或远端认证成功。

上游 `ssh_init` 已按 server 模式自动加入 `ext-info-s`；本轮新增的是 output drain
和实际 EXT_INFO 收到的断言，而不是新增该协商能力。修复前已实际重现 completion
过早。普通快分片不保证产生该排队情形，因此回归须检查
完成时 `partial_write=false` / `write_would_block=false` 和 peer 实际接收到 EXT_INFO。
另外 failed open 若已绑定 KEX guard、随后 `F_DUPFD_CLOEXEC` 因 `EMFILE` 失败，必须先
解绑再释放 ssh；用仅降低并恢复当前进程 soft `RLIMIT_NOFILE` 的合成回归验证原
role/callback/view/socket 可重试成功，hard limit 不变，不建立生产 fault-injection 入口。
soft limit 为 1 且 fd0 已占用，保证 `F_DUPFD_CLOEXEC(...,0)` 参数有效而返回 `EMFILE`；
不能设为 0 后误把 minfd 越界的 `EINVAL` 当作 FD 耗尽。

最终 actual ARM QEMU 为 65,838 checks / exit0；normal gate 为 94 tasks 成功，JUnit
189/0/0/0 为 UP-TO-DATE 保留报告。APK `f3327427…` 未安装；Android JNI 验证仍待解锁。

## Android JNI 分片 transport 与 EOF 验证设计（2026-10-17，本地通过，待实测）

复用现有精确 opt-in `syntheticNativeSessionHandle`、独立 fixture UID、受管内存 view
和 production session。测试以非阻塞 socketpair、小 step budget 和有界循环送入
server identification 分片，逐步核对输入输出计数并从 peer 读回 client banner / KEX
输出。另以 peer 半关闭触发 transport EOF，并验证 Java terminal 状态与 owner close。
原有 matching KNOWN host-proof 和 view revoke/close 合同保持覆盖。

public `SshNativeSession.step` 将 JNI 的 generic IOException 归一为
`VaultException("SSH_SESSION_STEP")`，且后续 step 在 Java 层保持该错误；因此设备测试
不声称观察了 C errno，也不声称完整 KEX。只有实际 instrumentation、APK hash 与
两包清理完成后，才把该增量标为 Android runtime 通过。

实际本地 fixture build/lint/签名检查通过；设备 `RUNNING_LOCKED` 拒绝启动 fixture，
用户选择保留本地结果，已完成两包及挂起进程清理。本轮 runtime 保持 not-run。

## G3.8 Android ARM/JNI empty-peer EAGAIN boundary（2026-10-16，runtime 已实测）

在 KNOWN host-proof fixture 之上，production `SshNativeSession.open` 成功后先对
同一 synthetic nonblocking socketpair 调用一次 `session.step()`。空 peer 的
`StepResult` 必须保持 `bytesRead == 0`、
`inputAppended == 0`、`readWouldBlock == true`；不把
`bytesWritten` 强行断言为 0，因为同一 bounded turn 允许 OpenSSH 排队/写出
identification/banner。随后仍用同一 key 走 production `verifyHostKey`，再 revoke
和 close。

fixture 重建、lint 与 Java compile 通过；当前授权板
`192.168.9.14:5555` 的精确 instrumentation 为 `OK (1 test)`、
status 0、0.11s。新 fixture main/test SHA256 分别为
`79776aad22be067e057fb2abd2a9af74bc365f890de44b1d3ba689c18d9560d3` 与
`fc71429d9e06d33334cc51cbb9fa64a7b8f672f2bb8752922052aac5ec6dc71e`，测试后
两个包和进程无残留。该项仍只证明 Android JNI 的 bounded EAGAIN + KNOWN host-proof，
不证明 KEX、userauth 或完整 SSH runtime。

## G3.8 Android ARM/JNI KNOWN host-proof boundary（2026-10-16，runtime 已实测）

把 synthetic session fixture 的无效 `known_hosts` 占位改为由同一
51-byte OpenSSH wire public-key blob 动态 Base64 生成的标准
`127.0.0.1 ssh-ed25519 <blob>` 行，并在 production
`SshNativeSession.open` 成功后、view revoke 前调用
`verifyHostKey`。这让 Android ARM 真实执行 Java → JNI → native session registry →
hostfile issuer → synchronous KNOWN callback；不通过 test-only callback，也不启动网络、
KEX、userauth、真实 vault 或凭据。

本轮 fixture main/test APK 构建与 lint 通过。当前授权板
`192.168.9.14:5555`（AIO-3568J、Android 11/API 30、arm64-v8a）运行
`SshNativeSessionPlatformTest#syntheticNativeSessionHandle` 为
`OK (1 test)`、status code 0、0.089s。测试后仅卸载本轮两个 fixture 包，
`pm path` 与 `pidof` 均无残留。

这只证明 matching KNOWN host-proof 的 Android JNI/runtime 边界；host-only
synthetic KEX、ARM KEX/packet runtime、userauth/typed credential、PTY/forward/
service/UI、真实 managed CAS/vault 与外部 SSH 仍开放。task 继续
`in_progress`/`implementation_complete=false`。

## G3.8 NEW/CAS full-KEX host-proof boundary（2026-10-16，host-only）

把先前 direct `NEW` verifier/CAS regression 接到真实 synthetic packet loop。空 managed
`ssh/known_hosts` 由 production native client 与同进程非阻塞 OpenSSH server 共同驱动到
`kex->done`；真实 host-key callback 必须按 candidate → `NEW` evidence (`CAS_COMMITTED`) →
KEX-complete 顺序各调用一次。

测试同时绑定 candidate/evidence 的 role/hop/port、candidate ID/classification/key/fingerprint、
replacement body、base/replacement digest，以及 cycle=1 和 peer session ID SHA-256。client
bounded step、server 2-byte 分片/EAGAIN/短写、completion 后 `EALREADY` 和双方清理均须通过。
这是 host/AArch64 strict cross-link 证据，qemu 不可用时保持 `runtime=not-run`，不等同 ARM
runtime、Android JNI packet runtime、真实 managed CAS 或完整 SSH 验收。

focused 与 normal full gate 通过，APK SHA256 为
`7ccac2fb8f705edd761d34574b9cead4b9e2296864dfe5568d2e9b1105fd849f`，task 仍
`in_progress`/`implementation_complete=false`。

## G3.8 synthetic KEX completion and host-proof boundary（2026-10-16，host-only）

在既有 production `KNOWN` host-evidence 与 `NEW` candidate/CAS precommit 之上，新增一个
合成 client/server OpenSSH socketpair。server 以非阻塞、2-byte 分片泵送真实 packet loop，
production client 由 `ctos_ssh_native_client_step` 驱动到 `kex->done`；测试同时观察 server
读 EAGAIN、短写和 client bounded partial I/O。

该边界要求 matching managed key 的 host evidence 恰好调用一次，并在唯一的 KEX-complete
callback 之前；callback 必须收到 `cycle == 1`、非零 session digest，且 digest 与 peer
`session_id` 的 SHA-256 完全一致。client completion 后再次 production `step` 必须返回
`EALREADY`。这仍是 strict AArch64 cross-link/host fixture 证据，不是 ARM runtime、Android
JNI packet runtime 或完整 SSH 验收；无 qemu 时明确记录 `runtime=not-run`。

focused 与 normal full gate 均通过，最新 APK SHA256 为
`7195a67fad84185714d00cbb2bbc899fbdf788b61f722282fae8b99ae8ba5f69`，task 继续
`in_progress`/`implementation_complete=false`。

## G3.8 bounded native step/error and host-evidence classification semantics（2026-10-15，host-only）

G3.7 的 native-owned session 已经拥有 managed-view borrow，但 `step` 原先把 borrow 的
所有失败都改写成 `EACCES`，因此会隐藏 view deadline 的 `ETIMEDOUT`。本窄增量保留
`view_borrow_live` 的精确 errno，再进入统一 terminal error：到期为 `ETIMEDOUT`，revoke/
invalid 为 `EACCES` 或底层错误，后续 step 统一拒绝为 `EALREADY`。

只新增/修正 production C boundary 与 host-only synthetic socketpair regression：空
nonblocking peer 必须报告 `read_would_block` 且无读入/append 进度；半关闭 peer 必须报告
`EPIPE`；短 BOOTTIME deadline 必须报告 `ETIMEDOUT`。该切片不引入网络、凭据、vault mutation、
设备安装或外部 SSH，也不把 AArch64 cross-link 当作 ARM runtime。

同一 host-only regression 还把 synthetic server identification banner 按 `step_budget=3`
分片送入 peer。banner 完整后，production `ssh_packet_next` 生成客户端 identification 与
KEX 输出；测试确认每步 read/write 都受预算限制、`bytes_written` 与
`output_consumed` 一致、`partial_write` 表示仍有 OpenSSH 输出排队，并从 peer 读回实际
客户端 banner 与后续 KEX 字节。这仍只是 strict AArch64 cross-link 编码的回归，未执行 ARM
binary 或完整 KEX/host proof。另以独立测试分支验证 managed-view expiry 的 `ETIMEDOUT`、
invalid view 的 `EACCES` 传播和 client deadline terminal 分支。

同一 fixture 现把生成的 public key 写入受管 `ssh/known_hosts` 内存对象，并调用 production
`ctos_ssh_native_client_verify_host_key`。callback fixture 回填完整 `KNOWN` evidence result
（role/hop/port、连接/目标/host、key type/fingerprint、base/replacement digest、approved、
`CAS_NOT_REQUIRED`），精确 key 成功且只触发一次 host evidence；另一个生成的 public key
在 pinned fingerprint guard 处返回 `EACCES`，host evidence 调用数不增加。该项仍只有
strict AArch64 cross-link 编译证据，未把 host binary 或完整 Android host proof 运行当作通过。

随后增加 `CHANGED` classification 回归：fixture 保持 offered key 的 pinned fingerprint 有效，
但把另一把生成 key 写入 managed `ssh/known_hosts`。测试直接调用 issuer 并断言
`CTOS_SSH_CALLBACK_HOST_CHANGED`、key/fingerprint/base/replacement digest metadata 和无
candidate handoff，再调用 production verifier，确认返回 `EACCES` 且 host evidence 次数仍为零；
因此该项不是仅以 generic `EACCES` 推断 classification。

本轮再增加 `REVOKED` classification 回归：fixture 用同一把 offered/pinned key 构造
`@revoked` managed `ssh/known_hosts` entry。测试直接调用 issuer 并断言
`CTOS_SSH_CALLBACK_HOST_REVOKED`、key/fingerprint/base/replacement digest metadata 和无
candidate handoff，再调用 production verifier，确认在 host evidence 之前返回 `EACCES` 且
evidence 次数仍为零。该测试同样把明确的 host-file classification 与 generic rejection 分开。

本轮继续覆盖两个 fail-closed host-policy 分支。第一个用生成并签名的有效 OpenSSH host
certificate，直接 issuer 断言 `CTOS_SSH_CALLBACK_HOST_CERTIFICATE`、bounded
key/fingerprint/digest metadata 与无 candidate handoff，再断言 production verifier 在 host
evidence 前返回 `EACCES`。第二个不假造上游 `HostStatus`：issuer 在
`load_hostkeys_file()` 前用 pinned `hostkeys_foreach_file(..., HKF_WANT_PARSE_KEY, ...)`
预检同一 managed 内存 body；测试用截断行加合法 key，遇到 `HKF_STATUS_INVALID` 时直接
断言项目自有 `CTOS_SSH_CALLBACK_HOST_INVALID`、相等 digest/no handoff 和 production
`EACCES`/zero evidence。合法但无匹配的 body 仍走上游 `NEW`，因此 `INVALID` 只代表明确的
解析失败。

## G3.8 bounded native NEW candidate/CAS precommit boundary（2026-10-15，host-only）

合法但无匹配的 managed `ssh/known_hosts` body 必须进入 production `NEW` 路径：issuer
先生成唯一 bounded replacement body，再同步 handoff exact candidate metadata/body；只有
candidate callback 成功后，native verifier 才请求 host evidence。`NEW` 的 evidence result
必须是 approved 且 `CAS_COMMITTED`，并逐字段绑定 role/hop/port、connection/target/host、
key/fingerprint 与 base/replacement digest。

本窄切片增加两个 production-caller synthetic cases。成功 case 使用空 managed body，逐字节
比较 replacement、candidate ID/classification/key/fingerprint 和两个 digest，并记录
candidate-before-evidence 顺序。失败 case 让 evidence 返回 `EACCES`，断言 candidate 只 abort
一次、没有重复 callback，native client terminalize 后 `step` 返回 `EALREADY`，且 close 的
release 失败仍可重试。该行为只使用内存 view 与 socketpair，不引入 vault mutation、设备、
真实 endpoint 或凭据；AArch64 runtime 仍因无 qemu 保持 `not-run`。

## G3.7 native-owned session/key/view boundary（2026-10-15，合成 runtime 已验收）

G3.7 在同进程 production C boundary 上完成 native ownership 的最小闭环。Java
`SshPublicFileLease` 仍只暴露正整数 opaque token；`ssh_view_handle.[ch]` 把 validated
wire 放进 native registry，borrow 计数保护 view，revoke 阻止新 borrow，最后一个 borrow
release 后才允许底层 view 销毁。stale token 不会指向新 view。

`ssh_native_client_open` 在 attach OpenSSH 前校验 live role、socket、deadline、view root/
known_hosts 引用和 bounded text；从 bounded SSH public-key blob 解析 `struct sshkey`，
校验 32-byte SHA-256 fingerprint 后由 session 持有 parsed key、managed-view borrow、
OpenSSH state、原 caller FD 与 `F_DUPFD_CLOEXEC` attached FD。失败保留 caller FD/borrow，
成功后 ownership 转移。close 顺序固定为 candidate rollback → callback close → KEX/OpenSSH
free → view-borrow release → parsed key free → original FD close；失败阶段保持可重试，
destroy 只接受 closed tombstone。

`ssh_session_jni.c` 使用 64-slot monotonic session registry 保存 owner `pthread_t`，所有
JNI open/step/verify/close/destroy 都做 owner 检查；key/fingerprint/blob 只 bounded clone
并清零，`step` 只返回八个 bounded scalar。Java 层仍负责 callback claim、role/view staged
cleanup；显式 view revoke 与 native close 之间由 native borrow 提供生命周期保护。

本增量只打开 synthetic JNI lifecycle：host `SSH_VIEW_HANDLE_HOST`、native client host、
normal APK gate 和 `.14` 上 socketpair 的 open → view revoke → close 均通过。它不证明
完整 packet event loop、host proof、userauth/typed credential、PTY/forward/service/UI，
也不改变真实 vault 或外部 SSH 授权边界。

## G3.6 Java/JNI session-owner fail-closed seam（2026-10-15，runtime 未验收）

`SshNativeSession` 是同进程 production boundary 上的 Java owner 对象。它记录创建
callback 的 event-loop thread，并只允许该线程执行 native `open`、bounded `step`、
host-key `verifyHostKey`、`close` 和 tombstone `destroy`；`cancel` 可从其他线程发起，
但不在该线程执行 native I/O。`SshNativeCallbackAdapter.NativeSessionClaim` 在 open
之前占用 callback binding，在成功后转成 owned；同一 binding 的第二次 open 失败。

open 先检查 role/view/callback/deadline/socket/输入边界，再取 public-view token 和
callback token。native binding 失败时释放 claim，但保留 caller 的 socket、callback 与
view；成功后 session 持有 claim。host-key blob/fingerprint 以 clone 传入，finally 中
清零。close 的 Java 层顺序固定为：`nativeBinding.close`（native C 内部负责 callback、
KEX、socket）→ `nativeClientClosed(claim)` bookkeeping → `releaseRoleAfterNativeClient`
→ `view.close` → `nativeBinding.destroy`。每个已完成阶段用独立布尔状态记录，失败时
保留未完成阶段供 owner thread 重试。

历史实现中的 `ssh_session_jni.c` 先只实现稳定的 fail-closed entrypoint：`Native.open/step/
verifyHostKey/close/destroy` 均抛 `IOException`。原因是现有 C API 参数中有借用的
`struct sshkey *` 与 `ctos_ssh_view *`，Java token 不能直接解释为这些指针；必须先设计
native-owned session handle、key ownership、view snapshot/borrow/revoke 和 close/destroy
生命周期，再把 JNI 绑定切换为真实调用。故 fake `NativeBinding` 测试是 Java contract
证据，不是 JNI 或 Android runtime 证据。

本增量的 gate 为 189/0/0/0 JVM（其中 `SshNativeSessionTest` 12 项）、callback host=20、
native `runtime=not-run`，strict `compileSshFiles`、三 ABI Release、lint、assemble
通过；APK SHA256 为 `21d16325b242427c2ccfbb7aa9cf42c6ca359700dc7b7f52995a95de06b94e72`。

## G3.5 Production PIC OpenSSH closure 与 native C boundary（2026-10-15，runtime 未验收）

normal `compileSshFiles` 现在消费一个由固定 G1 baseline 生成的 production archive，而
不是 ignored fixture 输出目录：runner 在 scratch build 中调用 pinned OpenSSH 10.6p1
`libssh-pic.a` source target，以 `-fPIC` 重编 archive 的完整 `libssh` 成员；再以项目
stream-writer/KEX guard patch 替换 hostfile/kex 对象，并把 PIC `ssh-pkcs11.o` 与
`ssh-sk-client.o` 加入同一归档。archive evidence 必须列出 83 个成员、source/object hash
和 symbols，并拒绝 `ssh_connect`、`ssh_login`、`ssh_userauth2`、`ssh_proxy_*` defined
caller；Gradle 每次任务重新生成，不读取旧 fixture objects。

`ssh_native_client_open(config, &client)` 的 config 绑定 live role/PID/UID/deadline、
`CLOEXEC|O_NONBLOCK|SOCK_STREAM` descriptor、same-thread callback environment、managed
view/`ssh/known_hosts`、host key/fingerprint、host/candidate identity 和 bounded step
budget。所有 admission/setup 检查在 socket attach 前完成；attach 使用 `F_DUPFD_CLOEXEC`
duplicate，所以失败返回 `-1` 时 caller 原 FD 与预打开 callback 仍由 caller 关闭。成功
后 native owns 原 FD 与 duplicate；`step` 只做 bounded socket turn。`close` 先回滚未完成
candidate，再关闭 callback binding，之后 KEX unbind/`ssh_free`，最后关闭原 FD；callback
release 或原 FD close 失败不 tombstone，允许同 owner/environment 重试；`destroy` 只接受
closed tombstone。

本增量的实测为 production cross-link/normal APK build：G3 fixture caller 仍不入 normal
APK，ARM binary 因 hako 无 `qemu-aarch64` 未执行；Android runtime/JNI/Java session
orchestration、userauth/typed credentials、PTY/forward/service/UI、设备及外部 SSH 仍是
后续关卡，task 保持 `in_progress`。

## G3.4 Pinned OpenSSH caller fixture seam（2026-10-15，production 未接入）

在 managed known_hosts provider、HostEvidence/CAS、one-shot candidate handoff 和
hostfile issuer seam 之上，新增仍限于 host/fixture 的
ssh_pinned_client.[ch]。caller 只接收 live role、owned socket、future BOOTTIME
deadline、expected public host fingerprint、managed view、host/candidate identity 和
同线程 callback environment；成功后才创建 pinned ssh_api client、附着 FD、设置
nonblocking、绑定 KEX guard 和 verifier。每个 step 的顺序固定为 bounded
recv(MSG_DONTWAIT) -> ssh_input_append -> ssh_packet_next -> bounded
send(MSG_NOSIGNAL)/ssh_output_consume，保留 partial write/EAGAIN，并将 EOF、
cancel/deadline 作为 terminal failure。

verifier 通过 upstream hostfile issuer 得到 KNOWN/NEW issue，随后调用 HostEvidence，
重新比对 role/hop/port/classification/outcome/CAS status/connection/target/host/key
type/fingerprint/base/replacement digest；CHANGED/REVOKED/CERTIFICATE/INVALID 不进入
KEX。NEW candidate 已成功 handoff 后，后续 evidence/binding/protocol 失败会调用
same-thread、same-environment、幂等的 candidate abort。KEX done 后生成非零
32-byte session digest，成功 callback 只允许一次；caller close 先解绑 KEX 再
ssh_free，destroy 只接受 closed tombstone。issuer failure 与 success 都关闭临时
fmemopen，避免错误路径 FILE 泄漏；replacement scratch 使用有界堆分配，owned socket
要求 `FD_CLOEXEC|O_NONBLOCK|SOCK_STREAM`，protocol/evidence 失败会回滚已 handoff candidate。

固定 runner 对 approved G1 manifest 应用 stream-writer/KEX patches，分别链接 issuer
和 caller AArch64 PIE，并检查 no-undefined、required symbols、stock caller 排除及
normal APK exclusion。最新结果为 build/ssh-native-g3/run.3PZSbIzM/：
SSH_HOSTFILE_CALLER_BUILD gate=pass runtime=not-run normal_apk=not-wired；
callback=20、native boundary=27、JVM=177/0/0/0；normal Release APK SHA256 为
805fc28679aa56be85fa3be75a1608701273908a02f7fb04dddb717c4e1b601e。hako 无
qemu-aarch64，ARM fixture 未运行；caller/issuer/OpenSSH/KEX object 未进入
compileSshFiles 或 normal APK。

这一步只证明 source/object/host fixture seam，不是 production event loop。生产
ssh_native_client 仍 ENOTSUP；typed credential/userauth、PTY、forward、service、
UI、Android runtime、设备和外部 SSH 仍是开放关卡，task 保持
in_progress/implementation_complete=false。

2026-10-15 managed known_hosts provider and its dedicated native→managed
candidate handoff are implemented: the concrete provider consumes one exact
AppSecrets public snapshot, retains bounded object metadata/base digest, and the
separate synchronous receiver accepts one caller-supplied replacement body.
The C/JNI handoff binds role/hop/target/connection/host/port/key type/fingerprint,
base/replacement digest and explicit candidate identity; native temporary, JNI
array and managed input are wiped. The existing metadata-only CSHR wire is
unchanged. The proposal and CAS writer bind role/hop, host proof, vault
UUID/generation/epoch, object identity and both digests; `COMMITTED` is emitted
only after the guarded AppSecrets commit returns. This still is not the
production OpenSSH caller: the pinned hostfile/parser path must produce the
exact candidate body.

The previous managed-handoff quality gate after proposal receipt-ordering and
cleanup hardening was JVM 177/0/0/0, native client 27, callback host 20, strict
compile/lint and all three Release ABIs; its measured normal APK SHA256 was
`3a6228f4237380dfc2cd98ccd537c16d4c6e81bc2e64e6205cdd6d556af9ded6`.

2026-10-15 当前窄增量：HostEvidence/CAS native↔Java contract 已冻结并实现。同步
callback 绑定 native role token、hop、target、classification、key type、fingerprint 与
32-byte base/replacement digest；KNOWN 返回 `CAS_NOT_REQUIRED`，NEW 只在 exact
key-type-bound proposal 经显式 approval 且 `TrustCasWriter` 返回 `COMMITTED` 后通过。
C/JNI 复验 bounded result，proposal body 不过 JNI；host gate=15、166 JVM、strict
compile/lint/Release 通过。当时仍只有 provider seam；具体受管 file view/CAS writer
已由顶部增量实现，真实 OpenSSH caller 和完整 SSH 行为未实现。

2026-10-14 前一窄增量：用户已确认生产拓扑为同进程 native event loop，并确认 native
event-loop 线程上的同步、附着 JNI callback，以及受管 config/known_hosts 文件视图 +
exact trust proposal/CAS + typed private-key handoff 方向。当前已将 callback
transport 落为 `SshNativeCallbackAdapter`/`ssh_callback_adapter.[ch]`/
`ssh_callback_jni.c`，并实现 bounded managed-provider handoff：NEW host 只接受
role/hop-bound proposal，password/private-key bytes 只在匹配 host proof + KEX proof
后经 `SshCredentialBridge` 释放，Java/native buffer 均有清零边界。回调线程、
JNIEnv/JavaVM、role PID/UID/deadline、重入、取消、迟到回调和 native-first cleanup
均 fail-closed；当时具体 file view/CAS writer、真实 OpenSSH event loop/caller、auth/PTY/
forward/service/UI、设备、外部 SSH 或真实 vault 仍未接入。主线 host gate=11、162 JVM、
lint、Release 构建通过；最新 APK 的 SSH 相关条目仅为生产 `libctos_ssh_files.so`。

此前 2026-10-14 同进程 KEX-only launcher 窄增量已完成 fixture/host 质量检查：
`ssh_native_launcher.{c,h}` 只接受 live role 的 canonical `CSRB`、同进程
client `struct ssh` 与固定公开 host fingerprint；入口重查当前 PID/UID、role
snapshot、deadline、wire 长度/字节、KEX callback 与重复绑定，close 先关闭
native role，成功后才解绑 KEX 并擦除 slot，失败保留绑定。固定 hako 的
cross-build、host KEX loop 与 normal Release/lint/JVM 结果由主线独立复核；
reviewer 另补了过期 admission 与 duplicate close 回归断言并完成严格
launcher/test C 编译。KEX artifact 仍是独立 opt-in fixture，正常 APK 未变且
不含该库；未执行 ARM artifact、设备、外部 SSH、vault 或生产 event loop。

此前 2026-10-13 SSH增量：Java role/trust/KEX/typed-secret bridge 已完成，bounded native role bootstrap 的 ownership/open/release JNI 已接入正常 `libctos_ssh_files.so`；native 输出清零、跨进程/UID close、stale token、Java registry 配对与失败重试均有回归。这里只证明 host/fixture seam 和 JNI contract，尚未由生产事件循环调用或接入 native launcher/OpenSSH callback、协议认证/PTY/转发/服务/UI或设备/外部账号；完整客户端仍开放。

状态 in_progress；技术主体见 [父 SSH 设计](../10-08-ssh-terminal/design.md)，公共契约见 [App 公共认证](../../../design/app-authentication.md)。

## G3.3 Production caller blocker（2026-10-15，G3.4 前的历史阻塞）

最新 pinned 10.6p1 source map 已确认 `ssh_api` 的 caller API；独立 native→managed
known_hosts candidate handoff contract 现已冻结并实现，但当时仍没有可编译的 pinned
production hostfile issuer/caller。G3.4 已补齐 host/fixture seam，但它仍不属于 normal APK。
Java `SshManagedKnownHostsProvider`/AppSecrets CAS
writer 与 handoff receiver 已实现，不过 exact replacement 仍由 managed caller/test
注入，现有 adapter 也不能从 `ssh_api` host key 生成 `KNOWN`/`NEW`、exact
proposal/base/replacement digest。故本轮保持
`ssh_native_client.[ch]` fail-closed；它只验证 role/PID/UID、受管 socket FD ownership、
callback token/environment、deadline 和 step bound，随后稳定返回 host-provider missing。
它不创建 OpenSSH client、不注册 verifier、不进入 packet/KEX，也不触发 KEX guard 或
credential callback。详见
[production caller blocker](research/production-kex-caller-blocker-20261015.md)。

待 G3.4 fixture seam 能以完整 production source/object manifest、正常 APK exclusion 和
Android runtime/生命周期证据获准后，才可在 production caller target 中接入
`ssh_init`/packet APIs，
并另补真实 socket partial I/O、EAGAIN/EOF、cancel/deadline、close retry 与 inbound
NEWKEYS digest/JNI callback 证据；普通 APK 继续不包含 fixture OpenSSH objects。

## G3.2 同进程 KEX-only launcher core（当前窄增量）

基于 2026-10-13 的 source map，本增量只验证一个同进程、无 fork/exec 的
native launcher admission seam：输入是 canonical `CSRB`、已有 native role
token、同一 `struct ssh` 和固定公开 host fingerprint；launcher 重新核对
当前 PID/UID、live token、wire、hop/connection/nonce/binding/deadline 与
client KEX 对象，再进入真实 pinned OpenSSH 10.6p1 packet/KEX loop。拒绝
argv、shell、配置/可执行路径、普通明文文件、外部网络和 Java supplied FD。

该 core 先留在独立 host/KEX fixture target，不进入正常 APK；只运行到真实
NEWKEYS 完成证明，使用 `ssh_api` 有界内存输入/输出缓冲合成 peer，不启动 userauth、PTY、
转发、远程服务或 AppSecrets typed credential callback。成功 close 先撤销
native role，再解绑 KEX 对象；失败保留绑定。生产 callback transport 选择已
单独记录为同线程同步 JNI，但 fixture 不调用该生产接缝。详见
[launcher research](research/next-production-launcher-20261013.md)。

依赖公共统一秘密库已验证契约，用 SSH adapter 进行身份绑定和受管凭据释放，不另建加密库或系统解锁流程。每次请求检查公共使用会话及限定连接操作，各跳 host key/远端认证独立；会话锁定/跨功能复用按已确认公共规则：前台共享，后台秒数可设置，锁屏/手动/进程重启锁定。公共认证不归 SSH 页面或客户端所有，不修改其他生产服务的策略。

其余客户端/config/输入/资源/风险决策引用父设计，避免复制后漂移。Q3 已确认后台保留连接、手动 Stop；独立 SSH 前台服务与重新附着设计见父设计。config 基础 TCP 转发已批准，随会话 Start/Stop；非 loopback 监听已选择连接前确认暴露范围；SSH 输入/↑/↓ 交远端、不追加程序逻辑及不设特定程序兼容验收已确认；config 不得绕过指纹核验已确认；原规划已获本轮实施批准，原生格式/API30合成关卡已有顶头记录的实测，完整生产客户端及其他平台仍待验证。密文与私有数据的回退不得删除用户内容或绕过验证。
用户已选择将 Authenticator 主口令/便携库/设备快捷/迁移模型用于 SSH。已保存凭据可随加密库恢复；config/Include/私钥/关联公钥或证书/known_hosts 一并进入公共加密包；SSH adapter 负责引用/身份/信任与 native 临时访问适配，公共底座负责密文和一致事务。公共库锁定不主动关闭已完成远端认证的连接，后台保留不延长秘密会话。公共模型调整的原审阅与实施入口已按用户2026-10-13继续指令恢复；不重复请求同一范围批准，实际实现变更及联合验收继续按原task记录。

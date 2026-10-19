# OpenSSH 终端与统一秘密库接入

## 2026-10-19 本连续阶段已完成：Android/JNI + 私钥认证

已完成密码与未加密/有界加密私钥的生产Java/JNI编排及独立Android实测，不再把两项列为
尚未实施。CSASv2/CSKBv1精确口令能力、RSA SHA2协商、expected-public/local verify、
全线程重入、真实view/role期限和自身NEW/CAS生命周期修复均有真实合成正反回归。

最终fresh JVM244/0/0/0、ARM1244010checks/exit0、normal94tasks65s/lint/Release/v2、
archive83/12JNI/forbidden0通过。开发板密码/未加密私钥/加密私钥/真实NEW各精确方法OK1，
26.954/27.418/23.781/23.906秒；两包进程清理，正式APK3bc762d9未变，未用真实vault/账号。
普通APK89eb6425未安装；完整证据/格式限制见design/verification.md#20261019-android-auth。
加密PKCS8、KBI/MFA、rekey/压缩、channels/PTY/forward/service/UI和父任务AC继续开放。

## 2026-10-19 连续推进：Android/JNI 与私钥认证

用户追问两项何时完成；main明确现在连续推进，不在每个C切片结束时等待“继续”。先完成
Java/JNI密码服务/认证编排与独立Android加密peer，再完成私钥parse/equal_public/sign及
联合验证。真实账号不是开发阻塞；设备操作在精确fixture准备后按AGENTS取得当前授权。
NEW/CAS真实vault写回会撤销publiclease的生命周期问题需解决，不以fakeCAS/view宣称通过。

## 2026-10-19 当前本地交付：一次密码 USERAUTH 协议（已实现，本地合成验证通过）

在当前选择/证明绑定的凭据回调上，实现C-only显式密码认证请求与结果。用户名必须来自
ownedCSAS，密码仅一次selectedprovider，8192上限/拒NUL/短生命周期；真实加密合成peer
验证成功、拒绝、部分成功和要求改密各结果。保留旧KEX/service/handoff停止点；不增加
Java/JNI编排、私钥auth、自动重试、MFA/change操作、channel或设备/真实账号访问。
实际新增67个加密连接场景，最终ARM785537checks/exit0，strict/lint/Release/v2与正式
复核通过；normal94tasks19s，JVM221为UP-TO-DATE保留报告。APK983f2032未安装，完整
Android与产品AC继续开放；完整命令/hash/覆盖限制见design/verification.md最新节。

## 2026-10-19 当前本地交付：证明绑定的一次凭据回调

合成native会话只在CSAS选择、service-ready和当前KEX证明全部匹配时请求一次固定typed
bytes，并在库最终effect和返回caller前复查；不允许旧回调降级或秘密提前进入caller
buffer。保留unselectedlegacy合同；不发送认证包、不解析或使用真实key/账号。
本切片已实现并正式复核；fresh JVM221、callback35、最终ARM323726checks、lint/Release/
v2通过，APK `f329056e…` 未安装。修改后的JNI未Android执行，实际认证和私钥匹配继续开放。

## 2026-10-19 当前本地交付：native selection metadata

受管选择通过有界canonicalrecord进入native session并保存explicitusername/typedresource
及SHA256回执，保留legacy null路径。验证strictframing/UTF8/rolebinding、复制/cleanup和
Java receipt核对；不释放秘密、不发送USERAUTH、不把metadata回执当认证或实际公钥匹配。
该切片实现与正式复核完成；freshJVM208、最终ARM QEMU253155checks、lint/Release/v2
通过。修改后的JNI未设备执行，完整认证/receipt-boundcredentialcallback仍开放。

## 2026-10-18 当前本地交付：不可变认证选择

在实际认证前，managed层可显式绑定每hop用户名和精确passwordentry或privatekey
path/identity/expectedpublic。selected路径不能仅按credentialkind重新选择条目；保留旧
未opt-in合同，真实secretrelease仍经过既有host/KEX/vaultfinalguard。仅本地合成验证，
不发送认证包、不扩大设备/账号授权，不把预期公钥声明当实际私钥匹配证明。
该managed切片已实现并正式复核：新增12项测试，fresh全量JVM201/0/0/0、lint/Release/v2
通过。native receipt/current-cycle/实际wire使用、公钥私钥匹配及完整认证仍开放。

## 2026-10-18 本地交付：userauth 服务准备

在既有受管 KEX 成功后，原生调用方可显式协商固定 ssh-userauth 服务并观察 ready，
不改变既有 KEX-only 调用方行为。验收要求真实加密合成协议成功、错误/取消/过期/资源
清理可验证，且全过程没有远端凭据释放或已认证声明。完整用户认证与产品 AC 仍开放。
本切片 C 实现、正式复核、ARM QEMU 加密合成测试已通过；完整结果及覆盖限制见
`design/verification.md` 本日 service 节，不扩大父任务 AC 的完成范围。

## 2026-10-18 当前验收补证

当前 production 修复版独立 fixture 已在恢复的 API30/arm64 开发板上通过精确 JNI
分片 transport / 初始 KEXINIT / KNOWN / EOF 清理方法（1 项，0.202s），两包清理且
正式 APK 不变。该窄关卡不再是 not-run；完整 Android KEX、认证与产品 AC 仍开放，
`implementation_complete=false` 保持。

## 2026-10-17 最新接续验收边界

本轮已实际完成宿主 QEMU/Bionic 的 production native ARM 合成回归，65,838 checks /
exit0，并修复 role admission、banner 分片、completion output drain 和 failed-open KEX
guard 清理；normal 94-task gate 通过。该证据只覆盖 native 合成协议/生命周期；Android
JNI fixture 须重建后在解锁板上另测，真实 managed CAS/vault、userauth 与产品 AC 仍开放。
本轮未安装正常 APK、未使用真实账号，task 继续 `implementation_complete=false`。

## 2026-10-17 当前增量验收边界

新增 JNI 分片 transport / 初始 KEXINIT 输出读回与独立 EOF terminal 回归，仅本地
compile/assemble/lint/签名及代理复核通过。当前开发板 `RUNNING_LOCKED` 拒绝启动
fixture，用户选择保留本地结果，两包/自有挂起命令已清理。本轮 runtime 未执行，
不扩大父任务 AC 的完成范围；`implementation_complete=false` 保持。

## 2026-10-16 G3.8 当前补充：Android ARM/JNI empty-peer EAGAIN

在 production `SshNativeSession.open` 成功并转移 socket ownership 后，独立 fixture
先调用一次 `step()` 对空 nonblocking peer，断言 `bytesRead=0`、
`inputAppended=0`、`readWouldBlock=true`，不把同一 turn 的
`bytesWritten` 错误限制为 0；随后继续 matching KNOWN `verifyHostKey`、
revoke 和 close。当前授权板精确 instrumentation 为 `OK (1 test)`、status 0、
0.11s，fixture 清理完成。该项只证明 bounded EAGAIN + KNOWN host-proof 的 Android
Java→JNI→native runtime，不等于 ARM KEX、认证或父任务完整 AC。

## 2026-10-16 G3.8 当前增量：Android ARM/JNI KNOWN host-proof

独立 `secretsFixture` 现在以同一 OpenSSH wire hostKeyBlob 动态生成标准 managed
`ssh/known_hosts` 行，并在 production `SshNativeSession.open` 成功、view
revoke 之前调用 `verifyHostKey`。在当前授权 `192.168.9.14:5555`
（AIO-3568J、Android 11/API30、arm64-v8a）上，精确
`SshNativeSessionPlatformTest#syntheticNativeSessionHandle` 为
`OK (1 test)`、status 0、0.089s；fixture 包随后卸载且 package/process 无残留。
这只增加 matching KNOWN host-proof 的真实 Java→JNI→native ARM 证据，不等于 ARM packet/KEX、
userauth/typed credential 或父任务完整 AC；task 继续
`in_progress`/`implementation_complete=false`。

## 2026-10-15 G3.7 当前增量

G3.7 已把 native-owned session/key/view boundary 接入生产 library：Java 侧只传 opaque
role/callback/view/session token，native 侧持有 parsed host key、managed-view borrow、
OpenSSH state 和 caller socket。`ssh_view_handle.[ch]` 的 revoke 阻止新 borrow，已有 borrow
在 native close 后 release；`ssh_session_jni.c` 的五个 entrypoint 已真实调用 C API，并执行
owner-thread、bounded clone/wipe、64-slot registry 和 close/destroy retry。

当前增量的可验收范围是 native handle 生命周期，不是父任务完整 AC：host view-handle
回归、normal production link 和独立 `.14` synthetic socketpair 的
`SshNativeSessionPlatformTest#syntheticNativeSessionHandle` 已通过；既有 public-file
view matrix 也单独通过。normal JUnit 为 189/0/0/0，production gate pass，native host
runtime 因 hako 无 qemu 为 not-run，task 仍 `in_progress`/`implementation_complete=false`。
userauth/typed credential、真实 host proof、packet partial I/O/EAGAIN/EOF/deadline、PTY/
forward/service/UI、真实 vault 和外部 SSH 不在本增量的完成判据内。

以下 G3.6 段落保留为历史 Java owner/fail-closed 约束；它不再描述当前 JNI 实现。

2026-10-15 历史 G3.6 已增加 Java/JNI session-owner seam，但不宣称 Android runtime 已通过。
`SshNativeSession` 让 owner event-loop thread 独占 `open/step/verifyHostKey/close/destroy`，
只允许 `cancel` 跨线程；`NativeSessionClaim` 保证一个 callback binding 只能同时拥有
一个 session。failed open 必须清掉 opening claim 且不消费 caller FD、callback 或 view；
close 必须按 native close → callback claim bookkeeping → role → view → native destroy
顺序执行，任一失败可在 owner thread 重试。host-key/fingerprint 只能以 bounded clone
进入 binding 并在调用后清零。

生产 `ssh_session_jni.c` 已被 normal `compileSshFiles` 链接并导出五个 JNI entrypoint，
但因为当前 C client 仍要求借用 `struct sshkey *`/`ctos_ssh_view *`，它们现在全部抛
`IOException` fail-closed；缺少 native-owned handle、view snapshot/borrow 和完整关闭
协议前不得宣称 production JNI/session。fake `NativeBinding` 的 session tests 只证明
Java ownership contract。当前证据为 189/0/0/0 JVM、callback=20、native
`runtime=not-run`、strict compile/lint/三 ABI Release/assemble 通过，APK
`21d16325b242427c2ccfbb7aa9cf42c6ca359700dc7b7f52995a95de06b94e72`；task 仍
`in_progress`，`implementation_complete=false`。

2026-10-15 上一阶段 G3.5 已将 pinned OpenSSH 10.6p1 的完整 PIC source/object closure 与
`ssh_native_client` C caller 接入 normal APK。production archive 固定为 83 个成员，含
PIC PKCS#11/SK 对象并排除 stock high-level caller；native open/step/verify/close 的
ownership 与 managed host evidence contract 已有 cross-link/normal-link 证据。当前尚未
完成 Android runtime/JNI/Java session orchestration、userauth/typed credential、PTY、
forward、service/UI、设备或外部 SSH 验收；task 仍 `in_progress`，
`implementation_complete=false`。

2026-10-15 dedicated native→managed known_hosts handoff 增量已完成：在已冻结
HostEvidence/CAS seam 与 `SshManagedKnownHostsProvider` 之间新增独立 synchronous
one-shot C/JNI/managed receiver。handoff 绑定 exact role/hop/target/connection/host/port/
key type/fingerprint、base/replacement digest、candidate identity 和 bounded body；native
temporary、JNI array 与 managed input 清零，现有 metadata-only CSHR wire 不变。body
安装后仍须经 guarded AppSecrets CAS 才返回 COMMITTED。177 JVM、native client 27、
callback 20、strict compile/lint/Release 通过；APK
`3a6228f4237380dfc2cd98ccd537c16d4c6e81bc2e64e6205cdd6d556af9ded6`。pinned OpenSSH
hostfile issuer 已在 host/fixture seam 接入，但 production caller 尚未接入，task 仍 in_progress/implementation_complete=false。

2026-10-15 前一窄增量已实现 role/hop/target-bound HostEvidence/CAS native↔Java
contract：KNOWN 返回 bounded `CAS_NOT_REQUIRED`；NEW 需要 exact key-type-bound proposal、
用户显式 approval 和 exact `TrustCasWriter` `COMMITTED` 结果。C/JNI 复验 classification、
key type、fingerprint、base/replacement digest，proposal body 不过 JNI。host gate=15、
JVM=166、strict compile/lint/Release 通过。该 seam 仍不是具体 known_hosts file/CAS
provider，也不是生产 OpenSSH caller；缺 provider 的 native client 继续 fail-closed。

2026-10-15 前一生产 caller 边界：pinned source map 证明 `ssh_api` 可支持未来的同进程
incremental caller，但当时没有可编译的 production known_hosts issuer/CAS API。因此
新增 `ssh_native_client.[ch]` 只做 fail-closed admission/step/verifier boundary：严格
校验 role/PID/UID、受管 CLOEXEC/NONBLOCK socket、callback token/environment、deadline
和 bounded buffers，缺 provider 返回 `ENOTSUP`，不创建 OpenSSH client、不进入 KEX、不
触发 JNI/credential。host gate=27；该结果不代表 production caller，task 仍
`in_progress`/`implementation_complete=false`。

2026-10-14 前一窄增量：同进程 KEX-only native launcher 仍仅在独立 fixture/host seam 完成；用户已确认同进程 event loop、当前线程同步附着 JNI callback，以及受管 `config/known_hosts` 文件视图 + exact trust proposal/CAS + typed private-key handoff 方向。当前已实现 bounded managed-provider handoff：role/hop-bound NEW proposal、host proof + KEX proof 绑定的 password/private-key bytes、JNI/native 清零与 cleanup retry。host callback `gate=11`、162 JVM、lint、Release 构建通过，正常 APK 仅含生产 `libctos_ssh_files.so`。具体 file view/CAS writer、真实 OpenSSH event loop/caller、auth/PTY/forward/service/UI、设备和外部 SSH 仍未接入，完整客户端验收仍开放。

2026-10-13 SSH当前增量：Java role/trust/KEX/typed-secret bridge 已完成，bounded native role bootstrap 的 ownership/open/release JNI 已接入正常 `libctos_ssh_files.so`；native 输出清零、跨进程/UID close、stale token、Java registry 配对与失败重试均有回归。150 JVM、lint、host bootstrap 1198/1/1198、固定 KEX native 构建和正常 Release 构建通过；当前 APK `538cae5a…` 包含生产 public-file 与 role JNI。这里只证明 host/fixture seam 和 JNI contract，尚未由生产事件循环调用或接入 native launcher/OpenSSH callback、协议认证/PTY/转发/服务/UI或设备/外部账号；完整客户端仍开放。

状态 in_progress；范围已批准，按阶段实施和验证；尚未完整验收。

## 已确认的后续架构选择

- 2026-10-14 用户选择同进程生产 native event loop；不采用 fork/exec child
  handoff。当前 PID-bound role ownership 可继续作为生产入口的前置约束。
- 2026-10-14 用户确认同进程 callback 采用当前 native event-loop 线程上的同步、
  附着 JNI callback；不得引入 fixture control channel 作为生产 broker。
- 该选择不等于把 fixture transport 升级为产品 broker；Java callback 与 provider
  handoff 必须继续遵守本 task 的 role/host/KEX/secret contracts，才能把 launcher
  接入正常 APK 或真实 OpenSSH 路径。

## 已确认的 provider 方向与当前边界

- **Production host-trust/file/private-key provider**：用户已确认采用受管 public
  config/known-hosts view + exact trust proposal/CAS + typed private-key handoff。
  当前已实现并验证 `SshManagedKnownHostsProvider`/`AppSecrets` 的 managed
  `KNOWN_HOSTS` CAS writer，以及独立 native-facing one-shot candidate handoff；它只
  接受 caller 提供的一次性 exact replacement bytes。pinned native hostfile issuer 和
  真实 OpenSSH caller 仍未接入，缺少 issuer 时必须继续 fail-closed。

## Goal

交付 APK 内 OpenSSH 终端、config 主机一键连接与统一秘密库 SSH adapter，对应 [父任务](../10-08-ssh-terminal/prd.md) R1–R10 / AC1–AC9；公共秘密能力归先行底座。

## Requirements

- 客户端随 APK、无 Root 可用，复用终端/xterm/输入和输出能力；SSH 输入/控制键及 ↑/↓ 交远端，无程序专属模式或兼容清单，App/Root 终端不回退。
- Include/单多跳 ProxyJump 及主要 config 行为按父 R6 实现；LocalForward/固定目标 RemoteForward/DynamicForward、默认 loopback/显式对外监听先确认、拒绝不静默降级全部引用父契约。
- 用户通过 SAF 主动导入 config/Include/密钥至公共加密库的受管结构，不持续同步；完整备份含文件/关联凭据/信任，引用/路径/身份及信任预览校验后同代次提交，失败保护旧状态。
- native 使用解锁后的受管临时内存文件视图，不建长期或临时普通磁盘明文副本；文件读取/路径引用/各跳及信任写回以探针验证，不满足回规划。
- 拒绝配置本地命令并定位，无放行开关，保留受管 ProxyJump；未知主机确认/变化指纹拦截，根配置/Include/每跳不允许绕过。
- 登录密码/私钥口令默认不保存，用户可主动选择保存或仅当次使用；SSH adapter 绑定目标/账号/私钥身份和当前库会话/连接用途，各跳独立核验主机/远端认证，不实现另一套 KDF/秘密库/设备包装。
- 共用公共初始化/解锁/秘密库设置/备份、后台保留秒数、改主口令和关闭快捷的本次口令验证、敏感页面保护（BASE-01..15）。库锁定拒绝新秘密操作，已建立 SSH 按父 R8 的前台服务/后台保留/手动 Stop 契约继续。
- 范围/资源/完整验收由父 PRD/design 拥有，此 task 保持同一版本，不重新选择产品行为。

## Acceptance Criteria

父 AC1–AC9 完整作为此 task 的验收；此外集成验证 BASE-01..15：正确/取消/失效/迟到请求、有效会话复用/锁库、新旧库代次、源口令/目标口令完整恢复失败保护、文件身份/引用/信任、关闭快捷保留数据、改口令旧备份语义、共同设置一致及导航不停止 SSH。设备/端点需另获当前授权，未测不标通过。

## Dependencies and Status

先完成并审阅 [统一秘密库/公共认证](../10-08-app-authentication/prd.md) 的公共合同和适用独立关卡，再按任务授权开始 SSH 集成。生产 OpenSSH 语义/信任、Android native 实际使用、混合包跨设备恢复后可连接等共同 AC 在接入后联合验收，不要求尚不存在的 SSH 先通过才允许实现它。接口冻结前可协调 native 内存文件访问/Include/ProxyJump 探针；探针不等于业务完成或独立关卡通过。TOTP 是同一基座的独立业务消费者，不是 SSH 的依赖。

HOTP 特化全部撤销，SSH 已确定 R1–R10 范围保留。产品范围/依赖已收敛，版本/许可证/ABI/平台/native 行为仍需技术关卡验证；失败回规划，不静默改路线。设计入口：[SSH 设计](../10-08-ssh-terminal/design.md)、[公共秘密契约](../../../design/app-authentication.md)，实施入口：[implement.md](implement.md)。原规划已于2026-10-13按用户指令恢复为in_progress，当前继续真实KEX与每跳核验；外部SSH账号/端点仍未授权。

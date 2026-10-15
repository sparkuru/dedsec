# OpenSSH 终端与统一秘密库接入

2026-10-15 G3.5 已将 pinned OpenSSH 10.6p1 的完整 PIC source/object closure 与
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

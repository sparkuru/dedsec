# SSH 客户端与公共认证集成设计

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

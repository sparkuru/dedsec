# SSH 终端规划入口

2026-10-15 当前最新窄增量（G3.7）：已在 production `ssh_native_client` C boundary 之上
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
service/UI 或外部 SSH。

固定证据：normal gate 为 JUnit **189/0/0/0**、view/native/callback host gate 通过、
`SSH_PRODUCTION_OPENSSH_BUILD gate=pass fixture=excluded`、
`SSH_NATIVE_CLIENT_BUILD gate=pass runtime=not-run reason=qemu-aarch64-unavailable`，
strict compile/lint/三 ABI Release/assemble 通过；APK `dd962567…`，archive=83，五个 JNI
symbol 可见。`.14` API30 arm64 独立 fixture 的两个 1-item instrumentation 方法均 status 0，
运行后两个 fixture 已卸载，正式 APK path/hash 保持；没有 qemu、真实 vault、凭据或外部 SSH。
task 保持 `in_progress`/`implementation_complete=false`。

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
inbound NEWKEYS/JNI proof，详见任务 [阻塞记录](../.trellis/tasks/10-08-ssh-terminal-client/research/production-kex-caller-blocker-20261015.md)。

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

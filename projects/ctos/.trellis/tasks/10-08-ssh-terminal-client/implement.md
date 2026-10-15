# SSH 接入实施计划

## 2026-10-15 当前窄增量：native-owned session handle 与 Android JNI gate（G3.7）

- [x] 新增 `ssh_view_handle.[ch]`：正整数 opaque token、native-owned validated view、
  borrow/revoke/deferred destroy、stale-token 幂等回归。
- [x] 扩展 `ssh_native_client`：bounded host-key blob 解析和 fingerprint admission，
  native-owned parsed `sshkey`、managed-view borrow、OpenSSH/FD close order 与 destroy
  tombstone；失败保留 caller FD/borrow，临时输入清零。
- [x] 替换 `ssh_session_jni.c` fail-closed placeholder：64-slot owner-thread session
  registry、五个真实 JNI symbols、bounded key/string copy/wipe、八 scalar step result、
  close/destroy retry。
- [x] 更新 `SshPublicFileLease` revoke/retry 与 production `SshNativeSession` 文档；新增
  `testSshViewHandleHost` 和 `SshNativeSessionPlatformTest` 精确 synthetic probe。
- [x] host/normal gate：view handle pass、native `gate=pass runtime=not-run`、production
  fixture excluded、callback=20、JUnit **189/0/0/0**、strict compile/lint/三 ABI
  Release/assemble pass、archive=83、APK `dd962567…`。
- [x] `.14` API30/arm64 独立 fixture：session handle 和 public-file view matrix 各 1 项
  instrumentation status=0；fixture 已精确卸载、正式包未改。
- [ ] 后续补 packet protocol/partial I/O/EAGAIN/EOF/deadline、host proof、userauth/typed
  credential、PTY/forward/service/UI 和更广泛 ARM runtime；当前 G3.7 不勾选父任务完整
  `implementation_complete`。

## 2026-10-15 当前窄增量：Java/JNI session-owner fail-closed seam（G3.6）

- [x] 新增 `SshNativeSession` owner-thread state machine：只有创建 callback 的线程可
  `open/step/verifyHostKey/close/destroy`，`cancel` 可跨线程；step/verify 在 role
  过期、取消或错误结果时 fail-closed。
- [x] 在 `SshNativeCallbackAdapter` 增加 `NativeSessionClaim`，约束 opening/owned
  互斥；failed open 释放 claim 并保留 caller FD、callback、role 与 view；成功 close
  后才允许 role release。
- [x] 固化 Java close retry 顺序：native close → callback bookkeeping → role release
  → view release → native destroy；每个阶段可独立重试，host-key/fingerprint clone
  后清零。
- [x] 将 `ssh_session_jni.c` 纳入 normal `compileSshFiles` 并导出五个 JNI symbol，
  但因 C API 的 borrowed key/view pointer 尚无安全 native handle/borrow contract，
  五个入口统一抛 `IOException`，保持 production fail-closed。
- [x] 定向 session test 及 full gate：`SshNativeSessionTest` 12 项；全量 JUnit
  **189/0/0/0**，callback=20，native `runtime=not-run`，strict compile/lint/三 ABI
  Release/assemble 通过；APK SHA256 为
  `21d16325b242427c2ccfbb7aa9cf42c6ca359700dc7b7f52995a95de06b94e72`。
- [ ] 设计并实现 native-owned session/key/view handle 与 JNI borrow/revoke/close 协议，
  再替换 fail-closed JNI binding；未满足前不得进行 Android runtime 或设备验证。

## 2026-10-15 上一窄增量：production PIC OpenSSH closure 与 native C caller（G3.5）

本轮已把 hostfile issuer、KEX guard 与 `ssh_native_client.[ch]` 接入 normal
`compileSshFiles`，但没有把 fixture `ssh_pinned_client`/launcher 或 Java UI 接口带入生产。
`compileSshProductionObjects` 通过固定 runner 校验 G1 baseline/tree/patch hash，在临时目录
以 pinned OpenSSH 10.6p1 自带 `libssh-pic.a` source target 重编完整 `libssh` 成员，应用
project-owned hostfile/KEX patch，并以 PIC 编译 `ssh-pkcs11.o`/`ssh-sk-client.o`；最终
archive evidence 83 members，defined symbols 排除 stock high-level caller。

`ssh_native_client` 现绑定 live role、managed public view、host key/fingerprint、callback
environment 与 owned socket；step 是 bounded nonblocking socket turn，verifier 复用 issuer
和 HostEvidence/CAS，KEX 完成只回调一次。open 使用 CLOEXEC duplicate，使 admission/setup
失败保留 caller 原 FD 与 callback；close 顺序为 callback release、KEX unbind/OpenSSH free、
原 FD close，release 失败保留完整 client 可重试。native host 仅交叉编译，未运行 ARM binary。

固定结果：G3 `run.aqN1F2bD` 为
`SSH_HOSTFILE_CALLER_BUILD gate=pass runtime=not-run normal_apk=fixture-caller-not-wired`；
production native `gate=pass runtime=not-run`、callback=20、JVM=177/0/0/0、strict
`compileSshFiles`/三 ABI Release/lint/assemble 全部通过，APK SHA256
`805fc28679aa56be85fa3be75a1608701273908a02f7fb04dddb717c4e1b601e`。ShellCheck、bash -n、
diff 检查通过。当前仍未完成 Android runtime/JNI/Java 会话编排、userauth/typed credential、
PTY/forward/service/UI、设备与外部 SSH；task 继续 `in_progress`，`implementation_complete=false`。

## 2026-10-15 当前窄增量：pinned OpenSSH caller fixture seam（G3.4，production 未接入）

已新增 `ssh_pinned_client.[ch]` 与 `ssh_pinned_client_test.c`，但只放在固定的
host/fixture runner 中。caller 在同进程 `socketpair` 上使用 pinned OpenSSH 10.6p1
`ssh_api`：入口重查 live role/PID/UID/deadline/host、managed view、expected host
fingerprint、callback token/environment 和 owned FD；成功后附着 socket、设为 nonblocking、
绑定 KEX guard 和 verifier。每次 `step` 只做 bounded `recv(MSG_DONTWAIT)`、
`ssh_input_append`、`ssh_packet_next`、`send(MSG_NOSIGNAL)` 与 output consume，保留
partial write/EAGAIN/EOF/cancel/deadline 结果。verifier 通过 `ssh_hostfile_issuer` 产生
KNOWN/NEW issue，再通过 HostEvidence 复验完整 role/hop/port/classification/outcome/CAS/
connection/target/host/key/fingerprint/base/replacement binding；CHANGED/REVOKED/
CERTIFICATE/INVALID fail-closed。NEW candidate 已 handoff 后，host evidence 或 binding
检查失败调用新的幂等 candidate abort。KEX `done` 后计算 32-byte session digest，只成功
回调一次 `ctos_ssh_callback_kex_complete`。

callback adapter 新增 `ctos_ssh_callback_known_hosts_candidate_abort`，只允许创建 callback
的 event-loop 线程和原 environment，重复调用幂等；issuer/caller 错误路径会清零 body，
caller 还保证 issuer 成功或失败都关闭临时 `fmemopen`，replacement scratch 使用有界堆分配，
owned socket 要求 `FD_CLOEXEC|O_NONBLOCK|SOCK_STREAM`，protocol/evidence 失败会回滚已 handoff
candidate。runner 应用 pinned stream-writer
patch（SHA256 `a786566e…`）和 KEX guard patch（SHA256 `50a59606…`），分开链接 issuer
与 caller 测试为 AArch64 PIE，并检查 required symbols、stock high-level caller 排除、
normal APK exclusion。最新 `run.3PZSbIzM` 为
`SSH_HOSTFILE_CALLER_BUILD gate=pass runtime=not-run normal_apk=not-wired`；callback=20、
native boundary=27、JVM=177/0/0/0、Release APK SHA256
`67668e34e2bba40359e43e048404ffeb5645629a130f86e86b2d855a170ffff9`。hako 无
`qemu-aarch64`，ARM fixture 未运行。

本增量仍不是 production caller：pinned OpenSSH objects 没有进入 normal
`compileSshFiles`/APK，`ssh_native_client` 继续 `ENOTSUP`；认证、typed credential、PTY、
forward/service/UI、生产 event loop、设备和外部 SSH 仍开放，task 不完成。

## 2026-10-15 当前窄增量：pinned hostfile issuer seam（host/fixture-only）

已新增 `ssh_hostfile_issuer.[ch]`、对应 C 回归、上游 stream-writer patch 及固定 runner
`research/android-hostfile-issuer-build.sh`。issuer 只消费 live role-bound public view，
要求 `ssh/config` root 明确引用 `ssh/known_hosts`；通过 `load_hostkeys_file()` 解析内存
body，通过 `ctos_hostfile_write_entry(FILE *)` 复用上游格式化，不访问 pathname writer。
KNOWN/NEW/CHANGED/REVOKED 沿用 pinned upstream；证书保持当前 policy 的 CERTIFICATE
拒绝，但 revoked certificate/CA 先分类为 REVOKED；NEW exact body/digest 走 existing
one-shot candidate handoff，callback failure abort/wipe。

固定 runner 结果：
`SSH_HOSTFILE_ISSUER_BUILD gate=pass runtime=not-run normal_apk=not-wired`。
manifest/hash、patch、C11/Werror、AArch64 PIE link 通过；runtime 因 hako 容器无
`qemu-aarch64` 未执行。issuer/OpenSSH 未进入 normal `compileSshFiles`，native client
仍 `ENOTSUP`。本增量不推进 `implementation_complete`；production caller、真实 socket
partial I/O/EOF/EAGAIN、KEX/NEWKEYS、认证、PTY/forward/service/UI、设备和外部 SSH 仍是
后续关卡。

2026-10-15 dedicated native→managed known_hosts candidate handoff 已实现并接入前一节
managed provider seam：`ctos_ssh_callback_known_hosts_candidate`/JNI 与
`SshKnownHostsCandidateHandoff` 形成独立 synchronous one-shot channel，绑定 exact
role/hop/target/connection/host/port/key type/fingerprint、base/replacement digest、
candidate identity 和 `MAX_FILE_BYTES` replacement body。native temporary、JNI array 与
managed input 均在成功/失败路径清零，metadata-only CSHR wire 不变；handoff 只安装
provider-owned candidate，原 guarded AppSecrets CAS 成功后才返回 `COMMITTED`。
JVM=177、native=27、callback=20、strict compile/lint/Release 通过，APK SHA256 为
`3a6228f4237380dfc2cd98ccd537c16d4c6e81bc2e64e6205cdd6d556af9ded6`。pinned OpenSSH
hostfile issuer 已由当前 fixture seam 接入；production caller 尚未接入，task 不完成。

本轮只允许 fixture caller 使用此独立 handoff；production normal APK 不实现 issuer、hostfile
parsing、OpenSSH caller、KEX/auth/PTY/forward/service/UI 或设备/网络操作。候选 body
wrong identity/evidence/digest、empty/oversize、duplicate/re-entry、cancel/close/expiry
以及 failed/uncertain CAS 都保持 fail-closed；native client 继续 `ENOTSUP`，fixture
OpenSSH objects 继续排除普通 APK。

2026-10-15 HostEvidence/CAS increment 已实现：`ssh_callback_adapter.[ch]` 增加有界
native evidence/result contract，`ssh_callback_jni.c` 解析并复验 Java wire，Java
`SshManagedProvider.TrustCasWriter`、`SshTrustProposal.CasResult` 与
`SshNativeCallbackAdapter.onNativeHostEvidence` 共同绑定 role/hop/target、classification、
key type、fingerprint、base/replacement digest。KNOWN 只返回 `CAS_NOT_REQUIRED`；NEW
需要 exact proposal、显式 approval 和 `COMMITTED` CAS。JVM=166、host gate=15、严格
compile/lint/Release 通过；具体 managed known_hosts CAS provider 已由顶部增量接入，
OpenSSH caller 仍开放。

2026-10-14 前一窄增量：已按用户确认实现同进程、当前 native event-loop 线程同步附着 JNI
callback adapter 的生产契约接缝，以及 bounded managed-provider handoff：normal
`libctos_ssh_files.so` 编译 `ssh_callback_adapter.[ch]` 与 `ssh_callback_jni.c`，
Java 层对 NEW host 使用 role/hop-bound exact proposal，对 password/private-key
bytes 要求 host proof + KEX proof，并在 JNI/native 边界清零。host callback gate=11、
162 JVM、lint 和 Release 构建通过，最新 APK 的 SSH 相关条目只保留生产 library。
当时具体 public config/known_hosts file view、trust CAS writer 和真实 OpenSSH event loop
仍未接入；ARM、设备、外部 SSH、vault、userauth/PTY/forward/service/UI 仍未接入。

2026-10-15 前一窄增量：新增 `ssh_native_client.[ch]` fail-closed boundary 及
`testSshNativeClientHost`。由于当时没有可编译 production known_hosts issuer/CAS API，
boundary 在严格验证 role/PID/UID、CLOEXEC/NONBLOCK socket ownership、callback
environment/token、deadline 和 bounded step 后稳定返回 `ENOTSUP`；不伪造 host
classification/proposal/fingerprint trust，不调用 OpenSSH/KEX/credential callback。
普通 APK 的 `compileSshFiles` 输入不含该 boundary，fixture library/launcher 也未扩大。
阻塞依据见 [production-kex-caller-blocker-20261015](research/production-kex-caller-blocker-20261015.md)。

此前 2026-10-14 KEX-only native launcher 窄增量在独立 opt-in fixture/host seam 完成；过期 admission、stale retry 与 duplicate close 回归已补。该 fixture 的 normal APK 基线为 `538cae5a…`；当前 callback adapter 质量门禁及最新 APK 以本文件顶端记录为准，ARM、设备、外部 SSH、vault、真实生产 event loop/auth/PTY/forward/service/UI 仍未接入。

此前 2026-10-13 Java role/trust/KEX/typed-secret bridge 与 bounded native role bootstrap 已完成；这里只证明 host/fixture seam 和 JNI contract，尚未由生产事件循环调用或接入 native launcher/OpenSSH callback、协议认证/PTY/转发/服务/UI或设备/外部账号；完整客户端仍开放。

## 当前窄增量：同进程 KEX-only fixed launcher core（fixture-only，已完成）

研究结论见 [next-production-launcher-20261013](research/next-production-launcher-20261013.md)。在真实生产 file/trust/private-key provider 尚未完成前，只实现独立 host/KEX target 的同进程 admission；同步 JNI callback 选择已另行记录，但本 fixture 不调用生产 adapter：

- [x] 新增 `ssh_native_launcher.[ch]`，只接受 canonical `CSRB`、live token、同进程 `struct ssh` 与固定 host fingerprint；重新校验 token/PID/UID/wire/role binding/deadline，拒绝任意 CLI、shell、路径、FD 和 secret。
- [x] 让真实 pinned OpenSSH `ssh_api`/patched KEX fixture 经该 admission 进入 host callback → signature → inbound NEWKEYS 的顺序；不接 userauth、PTY、forward、service、Java typed callback 或普通 APK。
- [x] 覆盖 malformed/trailing/stale/cross-role/wrong-wire/duplicate/expired admission、fragmented banners/binary packets、generic/DH-GEX、rekey、failure close 与 exact role teardown；固定数字协议和 source/ELF/input manifest 保持。
- [x] 通过独立 fixture build、strict native warnings、host `ssh_api` in-memory packet regression、normal APK exclusion 证明仍未扩展生产入口；生产 callback 选择已记录，但 fixture 不隐式调用该 adapter。

本增量完成的是独立 fixture/host seam，不是父任务完成：主线已独立重跑固定 hako
cross-build、host `SSH_KEX handshake=27 cleanup=1 gate=27` 与 normal
`:app:testReleaseUnitTest :app:lintRelease :app:assembleRelease`；reviewer
补充过期 admission/duplicate close 断言后，当前 launcher/test C 仍以
`-Wall -Wextra -Werror` 严格编译通过。正常 APK SHA256 保持
`538cae5a32499c9ff0aa4dcbca76a2e6637b6186c4177c1785dfdf86e2f843f9`，只含生产
`libctos_ssh_files.so`。task 仍 `in_progress`、`implementation_complete=false`；
ARM artifact 未执行，生产 callback/auth/PTY/forward/service/UI、设备和外部账号
仍是边界。

## 历史：G2 合成上游文件关卡实施合同

2026-10-13 G2阶段：六组实际当前板1项/4.496秒通过，fixture均已卸载、正式APK保持，完整证据见check.md。下文为当时执行合同；后续配置政策、通用文件视图与控制transport结果见顶头当前状态，不修改冻结产物或启用未实现的真实库provider。

下一阶段直接复用固定10.6p1解析器，先验证本地命令在求值前拒绝及真实none/no、inactive/首值语义，再验证native信任早期accept守卫与严格跳板参数。Match exec包括inactive/NEVERMATCH及否定形式必须在expand/exec前拒绝；配置ProxyJump必须使用严格上游解析，不能把任意ProxyCommand当内部受管跳板。缺生产上下文/provider始终失败关闭。独立输出与合成矩阵不扩大设备/外部账号范围；正式产品接入仍依赖完整政策、路径、每跳授权和真实协议关卡。

G0已在当前板通过；G1 `stock-run.lOHF9Oai`完成OpenSSH10.6p1/OpenSSL3.5.9 API28/arm64 ABI兼容基线构建，ELF/依赖/擦除符号已核对，crypto/runtime和生产信任仍未通过。接下来复用旧Linux研究的六组，但按 [新版本源码映射](research/android-g2-source-map-20261013.md) 重做Android夹具，而不是直接转抄PASS。

本阶段仅可信固定合成配置/私钥/known_hosts，通过真实upstream readconf/glob/authfile/hostfile及ProxyJump路径验证无普通明文文件访问形态。raw syscall匿名对象、真实0600权限/锁页、有界stdio/写入/独立offset和严格清理；至多16对象、单对象16KiB、固定代次，不是产品128对象/8MiB模型。合成撤销只证明各自已核查的对象/parsed-key/heldFD边界，不伪称上游所有副本或生产broker跨进程撤销。

使用独立standalone native driver、真实10.6p1受研究hook客户端和同ABI负对照（仅观察真实closefrom，不放行继承FD）。保留closefrom/getpwuid/compat glob实际路径；以新生成Makefile的真实SSHOBJS/linkflags链接，保留OpenSSL/Zlib和原擦除。首批six groups：config/根相对嵌套Include/词法glob；实际启动FD关闭负对照；真实IdentityFile与known_hosts append/read/replace/proposal；单跳ProxyJump parent/exec-child同代次与identification失败；自有全链取消/reap；缺失/遍历/撤销新读与迟到write拒绝、自己heldFD EOF和擦除。常用RSA/ECDSA/Ed25519与加密私钥可在合成native算法子项扩展，未执行组合不标通过。

driver只接受与自身APK native目录相同的固定两个client basename，无Shell入口/任意argv/外部文件/真实库或账号；合成ProxyJump仅本driver拥有的127.0.0.1随机端口，固定短identification后失败，无完整SSH服务或外部网络。所有accept/read/poll/wait有单调期限、独立次数上限/取消/升级、独立group与subreaper/exactownedPID，PDEATH race；不得在JVM使用waitpid(-1)或只回收Java直接孩子。内存stderr/config/key输出只在driver内部有界解析并擦除，对外仅固定阶段/状态/errno/PID/代次/计数，完整阶段且清理成功才计gate。

新opt-in `ctosSshUpstreamProbe=true` 仅独立fixture；输出与assets使用专有目录，正常APK生成后仍排除stale G2产物。Java精确方法/strict opt-in/UID/fresh/root-off/无Activity与库/剪贴板守卫，复用已有G0有界drain/中断清理，G0行为不改。main统一串行构建/验签/设备；先完成可审阅产物，再依当前具体scope处理G2包含loopback的设备授权，不把G0指定方法的批准自动扩为任意网络测试。

六组正例不证明用户配置政策安全：Match exec/本地命令、指纹绕过（包括VerifyHostKeyDNS提前accept）、malicious路径/每跳秘密/信任CAS另有强制native gate，缺生产provider仍失败关闭，禁止UI接入真实库直到完成该关卡。当前只启动合成G2，不改路线/普通明文暂存/真实账号授权，不勾父完整AC。

下方planning状态/待批准语句是历史实施计划；已批准范围见顶头当前状态及task metadata。

执行主体清单与命令引用 [父实施计划](../10-08-ssh-terminal/implement.md)，顺序为 native OpenSSH 探针 → 私有配置导入/受约束求值 → 会话/认证桥/基础 TCP 转发 → 统一秘密库的 SSH adapter 接入 → UI/本机终端回归 → 当前授权设备验收。

集成额外验证：公共验证取消、锁定、不可用、过期、后台及迟到回调不能释放秘密；每跳 host key/身份绑定后才提供匹配凭据；连接结束使上下文失效。SSH 输入验证按父 R2/AC2 的字符/控制键/输出/窗口事件进行，不把本机历史截获套给 SSH，不建立特定远端程序兼容验收。父 AC1–AC9 和跨子任务验收全部映射至真实证据。

后台保留/手动 Stop 按父 R8、AC6 验证；原规划的实施批准已由本轮继续指令满足，版本/许可证、资源初值及可行性关卡见父设计。依赖失败回到规划，不自动降级客户端或免验证。
统一模型变更后不重复实现 KDF/文件/快捷包装/迁移，不以 SSH-only 系统弹窗替代公共会话。补充混合库恢复、库覆盖旧代次、SSH 条目删除不影响 TOTP、快捷失效回主口令及已建立连接与库锁定分离验证；补充 config/Include/私钥/关联公钥或证书/信任完整包恢复、路径/引用闭包及覆盖冲突/信任审阅测试，并优先验证无明文磁盘的 native 临时访问适配；覆盖/导出接入公共 BASE-10 的重新输入与认证解密，验证来源包失败/损坏/不合规不得覆盖，已有目标另验证当前口令、空目标只验证来源，成功解密不跳过指纹审阅/连接核验；首版公共 BASE-11 更换主口令后验证 SSH 文件/身份/凭据关联不变、旧会话失效、新库新口令/旧备份旧口令及失败恢复，不更改远端秘密；接入公共 BASE-12 关闭前本次主口令认证解密，验证失败/取消保持旧状态，成功锁定/数据保留/旧快捷回调失效，已完成认证的 SSH 连接仍按 Stop 规则存续；SSH 密码/私钥口令输入及实际秘密展示接公共 BASE-13，验证安全窗口/任务预览、嵌套/迟到及返回普通输出页的保护释放；SSH 接入公共唯一秘密库设置页（BASE-15），验证首次创建/导入/解锁原业务返回、取消无半成品及设置与验证器一致，导航不改变 Stop/已建立会话；以上联合验证合同保留；原规划已批准实施，未执行联合验收不计通过。

依赖基座公共合同及适用独立关卡审阅后，按任务授权做 SSH 集成，业务/native 依赖的联合 AC 在接入后验证，避免完整客户端与底座互相等待。原Linux研究已扩展为顶头记录的Android实测，生产集成仍开放；不需 TOTP 页面先完成。HOTP 特化已撤销，本客户端task当前in_progress，用户已批准192.168.9.14:5555合成测试和真实秘密库操作，外部SSH登录账号/端点仍未授权。

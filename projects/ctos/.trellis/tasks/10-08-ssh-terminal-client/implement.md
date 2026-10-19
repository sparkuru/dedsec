# SSH 接入实施计划

## 下一增量：session channel / PTY（用户已确认，尚未实施）

用户于2026-10-19确认先提交认证阶段，再续做 session channel / PTY 与终端闭环。
保持原 task `in_progress`；先用 `trellis-continue` 恢复并研究现有 native/Java/JNI 与终端
bridge，按 `trellis-before-dev` 冻结具体合同后实现，不重做已经完成的认证阶段。

- [ ] 冻结 SUCCESS-only channel open、PTY/shell、流控/背压、resize、EOF/exit/close、取消/期限/所有权合同。
- [ ] 实现 native → JNI → Java 的合成 shell 闭环和必要的正反例验证。
- [ ] 接入现有终端界面，验收输入输出、尺寸调整、正常退出与取消后资源回收。
- [ ] 按新改动运行相应检查，同步 spec/design/verification/task；设备只在当前授权覆盖下运行独立合成测试并清理。

完整可执行接续步骤见 `design/handoff-ssh-terminal-client.md` 的“下一步行动（当前有效）”。
forward/service、KBI/MFA、加密PKCS8和更广兼容仍为后续范围，不作为本增量已完成行为。

## 2026-10-19 本连续阶段已完成：Android/JNI + 私钥认证

已完成密码与未加密/有界加密私钥的生产Java/JNI编排及独立Android实测，不再把两项列为
尚未实施。CSASv2/CSKBv1精确口令能力、RSA SHA2协商、expected-public/local verify、
全线程重入、真实view/role期限和自身NEW/CAS生命周期修复均有真实合成正反回归。

最终fresh JVM244/0/0/0、ARM1244010checks/exit0、normal94tasks65s/lint/Release/v2、
archive83/12JNI/forbidden0通过。开发板密码/未加密私钥/加密私钥/真实NEW各精确方法OK1，
26.954/27.418/23.781/23.906秒；两包进程清理，正式APK3bc762d9未变，未用真实vault/账号。
普通APK89eb6425未安装；完整证据/格式限制见design/verification.md#20261019-android-auth。
加密PKCS8、KBI/MFA、rekey/压缩、channels/PTY/forward/service/UI和父任务AC继续开放。

- [x] Java/JNI service/password API、显式phase、ThreadLocal跨session guard与真实MemoryStore fixture。
- [x] native private-key parse/public-match/raw-session sign/localverify与Java/JNI接入。
- [x] CSASv2 exact passphrase selection、typed atomic bundle、bounded encrypted parser及反例。
- [x] client/hostfile view期限兼容、最后store read后的finalguard、自身NEW/CAS loan/epoch修复。
- [x] actual ARM QEMU、fresh JVM、strict/lint/Release/12JNI/archive/fixture排除及四Android精确方法。
- [x] 安装的独立两包/进程清理，正式APK读回未变；design/spec/task/context/journal同步。

本连续阶段已完成，父task保持in_progress，未commit/archive/dist/正式安装。后续转入
channels/PTY及产品集成；不重新等待用户为已经授权的本地工作逐项回复继续。

## 2026-10-19 连续阶段：Android/JNI 与私钥认证

- [x] 研究Java/JNI phase、global lock重入、fixture shared-library隔离及privatekey解析/签名。
- [x] 冻结Java四API/显式phase/ThreadLocal guard及KNOWN真实managed Androidfixture合同。
- [x] 分工实现Java/session tests、四JNI入口、fixture peer/build/instrumentation；main独占build。
- [x] 正式check/JVM/strictnative/fixture构建与normal排除审计，准备精确设备测试包。
- [x] 连续推进privatekey认证、NEWhost CAS/view生命周期修复与联合验证，不以单个增量停工。
- [x] 精确包就绪后按AGENTS当前授权运行独立设备测试并清理；同步design/spec/task/journal。

## 2026-10-19 当前切片：C-only password USERAUTH

- [x] 检查local pinnedpacket/auth源码，冻结显式API、CSASusername/8192password、noNUL、
  oneattempt、requestdrain/finalstatus、boundedresponses/cleanup与七段spec。
- [x] 实现代理独占nativeclient.h/.c和nativeclienttest.c；无build/device，main独占执行。
- [x] strictfocused/actualQEMU与正式checker，修正finding后normalJVM/lint/Release/packaging。
- [x] 同步design/spec/verification/task/context/journal，不commit/archive/设备或账号操作。

实际结果：最终focused7tasks13s（3exec4uptodate）、callback35/strictnative通过；67个新增
connection场景（初轮66后补pending-BANNER正例）。正式checker无productionfinding。
normal94tasks19s（23exec71uptodate）、lint/Release/v2/archive83/sixJNI/forbidden0通过；
JVM221/0/0/0为UP-TO-DATE保留报告。最终QEMU785537checks/exit0，APK983f2032未安装。
source-final与runtime-final manifest通过，完整hash/命令/source-only覆盖限制见design/verification。

## 2026-10-19 当前切片：selected credential callback

- [x] 冻结receipt/cycle/digest接口、READYgate、private scratch/postcheck、one-shot、
  selecteddowngrade拒绝和AppSecretsfinalEffectGuard七段合同。
- [x] C代理独占client/callback/JNI和Ctests；Java代理独占adapter/provider/bridge及JVMtests，
  不交叉改文件，不构建或设备操作；main独占hako/Gradle/QEMU与docs/spec/task。
- [x] focusedJVM/hostcallback/strictnative/JNI与actualQEMU，正式checker复核，再normalgate。
- [x] 同步design/spec/task/context/journal，不commit/archive，不扩大真实凭据/设备授权。

实际结果：final focused66/0/0/0、57tasks33s；callback35含新selected11组，strict C/JNI/lint通过。
正式checker已确认JNI异常顺序、finish时钟取消/重入和Error cleanup修复，无遗留finding。
normal94tasks66s（29exec65uptodate），fresh full JVM221/0/0/0、15reports；最终QEMU
323726checks/exit0，Release/v2/archive83/sixJNI/forbidden0通过。APK `f329056e…`未安装。
新JNI未设备执行，cycle/rawsession drift、ENOMEM/hash故障未注入；完整证据见design/verification。

## 2026-10-19 当前切片：CSAS native metadata transport

- [x] 冻结CSASv1 wire/defaultbinding/readonlyreceipt/ownership及7段spec，限定metadata-only。
- [x] 代理实现Javaencoder/session、JNI/Cvalidator/ownedcopy/receipt及JVM/ARM回归。
- [x] focused53/0/0/0、strictnative/JNI/lint；正式check两finding修复，无remainingfinding。
  normal94tasks64s（29exec65uptodate）、freshJVM208、最终QEMU253155、Release/v2/sixJNI包装通过。
- [x] 同步design/spec/task/context/journal；无设备/账号/credentials操作，不提交/归档。

main独占hako/Gradle/QEMU/设备，代理只改分配源文件并轻量sourcecheck。

## 2026-10-18 当前切片：managed authentication selection

- [x] 研究现有provider/bridge/adapter/manifest，冻结7段spec及窄实现边界；passphrase
  target/key identity能力扩展、native receipt/wire与private-key parsing明确留后。
- [x] 实现selection与exactprovider/bridgeguard、adapterone-shotopt-in/receipt拒绝及JVM回归。
- [x] focused46/0/0/0和lint，正式check无finding；normal91tasks/57s（26exec65uptodate），
  freshJVM201/0/0/0、Release/v2/native包装通过，记录实际结果与限制。
- [x] 同步design/spec/task/context/journal；完整task继续in_progress，不提交/归档。

代理只改分配Java/test文件并轻量sourcecheck，main负责所有hako/Gradle/QEMU/设备执行；
本切片无设备动作，无C/JNIwire修改，不声明native/Androidauthruntime。

APK `e6c4d58b…` 未安装/发布；archive83/fiveJNI/forbidden0，34个native源文件及此前test
binary不变，归档/库按既有forcedbuild重生。selected专用mid-returncancel/reentry未新增
测试，复用共用pipeline旧回归；public-key声明和真实私钥匹配严格区分。

## 2026-10-18 当前切片：post-KEX userauth service（C only）

- [x] 依据既有研究冻结显式 begin/ready、旧 KEX-only 行为、empty reply 兼容、current proof
  校验、no-secret callback live check、service-phase rekey 拒绝及精确错误合同。
- [x] 实现代理修改 native client/callback 的 C API 与相应 C tests，保留既有未提交改动；
  正式检查代理复核无 finding，focused7 tasks / 14s、callback24、ARM246,974 checks / exit0。
- [x] strict focused compile 与真实 ARM QEMU 加密 service negotiation（正/负例），检查代理
  复核修正后跑 normal gate、archive/symbol/APK 检查。
- [x] 同步 design/spec/verification/handoff/task；认证、Java/JNI service 接入和产品 AC 保持开放。

最终 normal94 tasks全部执行 / 1m24s，JVM189/0/0/0新跑、lint/Release/v2通过；ARM
246,808 checks / exit0，APK `8cd6da66…` 未安装。完整hash/证据/覆盖限制见 design/verification。

主会话独占 hako/Gradle/QEMU/设备命令，代理只改分配源码并做轻量 source checks；本轮无
设备动作。测试使用现有公开 Bionic 临时 runtime，先核对其存在与 hash，再实际执行。

## 2026-10-18 解锁后接续：JNI 分片/EOF gate 已完成

- [x] 用户报告已恢复，显式确认 `.14` AIO-3568J/API30/arm64/RUNNING_UNLOCKED。
- [x] 复核精确方法，用当前 production 修复重建 fixture main/test；112 tasks / 20s、
  lint/Java/native packaging/v2 同证书与独立 package/target 通过。
- [x] 90s timeout 下运行原 opt-in 方法：**OK (1 test)，0.202s**，status0 / runner-1。
  分片 banner、初始 KEXINIT 输出读回、KNOWN/revoke 和独立 EOF terminal/close 均通过。
- [x] 卸载两包，确认包/进程无残留，正式 APK 前后 hash 不变；本轮没有源码修改。

原 10-17 锁定导致的该待测关卡已关闭，历史 not-run 按原日期解释；完整 Android KEX、
认证/真实 CAS/vault/产品 AC 继续开放。下一本地协议切片仍按 userauth research 规划。

## 2026-10-17 接续：宿主 QEMU 的真实 ARM native regression

- [x] 只读检查开发板，仍 `RUNNING_LOCKED`；不重装 fixture。
- [x] 发现宿主 `/usr/bin/qemu-aarch64` 10.0.13 可用，与 hako 镜像内缺 QEMU 分开记录。
  从当前板只读复制公开 linker64/libc/libdl/libm/libz 到 `/tmp`，不修改设备状态。
- [x] 在宿主执行既有 ARM `ctos_ssh_native_client_test`，修复实际运行暴露的契约/测试问题，
  不放宽 admission、host proof 或 cleanup 断言；运行加 timeout 并禁用 core dump。
- [x] 重新 strict cross-build、完整 ARM native synthetic runtime 与检查代理复核。
- [x] 同步实际 QEMU 结果与 ABI/runtime 依赖 hash；Android JNI 设备验证仍为 not-run。

最终 ARM native 为 65,838 checks / exit0；normal 94-task gate 成功（35 executed /
59 up-to-date，JUnit 189/0/0/0 是保留报告）。role errno、banner 原子性、completion 输出
排空和 failed-open KEX guard 清理均有回归；最终 APK SHA256 为
`f3327427ce0af90ff7a8a5ea3b5cbba0ac7a7f1c396853c2fd247a45a2328dbd`，未安装/发布。

首个沙箱内尝试被 `getsockopt(SO_TYPE)` 的 EPERM 阻断，获工具权限后同一 binary 在沙箱外
可运行，暴露 stale-role errno 断言不一致。按现有 role/bootstrap 合同修正正确层，不把
宿主 QEMU 当 Android 实机/JNI pass。userauth 另作源码研究，不抢先改变认证契约。

## 2026-10-17 当前窄增量：Android JNI 分片 banner / 输出计数 / EOF

- [x] 在既有精确 opt-in 方法中，以非阻塞 socketpair 和小 step budget 编码验证分片
  server banner、逐步 read/append 计数，以及累计 bytesWritten 与 peer 实际读回输出。
- [x] 编码验证 peer 半关闭后 public Java `step()` 返回 `SSH_SESSION_STEP`，后续调用保持
  terminal；保留独立的 KNOWN 核验、view revoke 和 close 清理证据。
- [x] 完成实现/检查代理复核、fixture assemble/Java compile/lint、APK 签名与包名检查。
- [x] 用户本轮允许仅在 `192.168.9.14:5555` 安装独立合成测试包并清理；已确认
  AIO-3568J / API30 / arm64-v8a。两包安装成功；测试未能启动，随后卸载并确认无包/进程残留。
- [ ] 设备实际执行：当前 `RUNNING_LOCKED`，ActivityManager 拒绝启动非 encryption-aware
  fixture。用户选择暂时无法解锁、保留本地结果；待手动解锁后再跑，不记 runtime pass。

最终本地 fixture gate 为 112 tasks / 20s 成功；main/test SHA256 为
`78f71309dea6fc39804b9bc591c3db4a32dd291cdab5ddcd50fae02ff1e1bbf4` /
`d0634aa524053b14a94aefe9eec0ad12a3749a5140537fd7b9406c1c0503ee87`。

不改变 production API。JNI 不暴露 errno，public Java 将 native IOException 归一为
`SSH_SESSION_STEP`，不能把 Java 结果记为直接观察到 `EPIPE` / `EALREADY`。完整 KEX、
userauth、真实 CAS/vault、PTY/forward/service/UI 与外部账号验收继续开放。

## 2026-10-16 当前窄增量：Android ARM/JNI empty-peer EAGAIN（G3.8）

- [x] 在 production `SshNativeSession.open` 成功并转移 socket ownership 后调用一次
  `session.step()`；只断言空 peer 的 `bytesRead=0`、
  `inputAppended=0`、`readWouldBlock=true`，不约束可能写出的
  identification/banner。
- [x] 保留后续 production `verifyHostKey`、view revoke、native close、callback/view
  release 和 finally cleanup。
- [x] fixture compile/type-check、assemble、lint 与 ARM instrumentation 通过：精确方法
  `OK (1 test)`、status 0、0.11s。
- [ ] 仍不把该 bounded EAGAIN + KNOWN proof 外推为 ARM packet/KEX、userauth 或完整 SSH。

本轮 fixture main/test SHA256：
`79776aad22be067e057fb2abd2a9af74bc365f890de44b1d3ba689c18d9560d3` /
`fc71429d9e06d33334cc51cbb9fa64a7b8f672f2bb8752922052aac5ec6dc71e`。

## 2026-10-16 当前窄增量：Android ARM/JNI KNOWN host-proof（G3.8）

- [x] 将 synthetic managed `ssh/known_hosts` 行改为由测试用同一
  OpenSSH wire key blob 生成的无换行 Base64，移除无效 `AAAA` 占位。
- [x] 在 production `SshNativeSession.open` 后、view revoke 前调用
  `session.verifyHostKey(key)`，保留 owner、socketpair、revoke、close 和 finally wipe。
- [x] fixture main/test 构建、Java compile/type-check、fixture lint 通过。
- [x] 在当前授权 `192.168.9.14:5555`（AIO-3568J/API30/arm64-v8a）运行
  精确 opt-in instrumentation：`OK (1 test)`，status code 0；随后两个 fixture
  包卸载且 package/process 无残留。
- [ ] 不把该 KNOWN host-proof runtime 外推为 ARM packet/KEX、userauth 或完整 SSH runtime。

设备本轮产物 hash：fixture main
`1e3a10c709fed7d514a90e247494c61345da6a0f71dd142c17157f34ea4e9de6`；
fixture test
`2b9f80678220d89a7a67e23197bd5f85c1eee7543763bfb895da8d31a801f7e7`。
normal production APK 未因本 fixture-only 增量重建，task 仍
`implementation_complete=false`。

## 2026-10-16 当前窄增量：NEW/CAS full-KEX host-proof boundary（G3.8 follow-up）

- [x] 用空 managed `ssh/known_hosts` 与现有 synthetic nonblocking OpenSSH server 驱动
  production client 到真实 `kex->done`，保留 2-byte 分片、client bounded step、server EAGAIN
  与短写断言。
- [x] 断言真实 callback 顺序 candidate → `NEW` host evidence (`CAS_COMMITTED`) → KEX complete，
  三者 exactly once；精确绑定 role/hop/port、candidate ID/classification/key/fingerprint、
  replacement body、base/replacement digest。
- [x] 断言 cycle=1、callback session digest 非零且匹配 peer `session_id` SHA-256，completion
  后 `step` 为 `EALREADY`，并覆盖 server/client cleanup 的二次 close `EBADF` 语义。
- [x] check 修正 candidate/evidence route-field coverage 与 peer fd double-close coverage；
  focused gate、lint、strict native compile 和 `git diff --check` 通过。
- [x] normal full gate：94 tasks，view/native/callback host pass，JUnit **189/0/0/0**，strict
  `compileSshFiles`、三 ABI Release、lint、assemble pass，archive=83，APK SHA256
  `7ccac2fb8f705edd761d34574b9cead4b9e2296864dfe5568d2e9b1105fd849f`；APK SSH 相关 native
  库仅生产 `lib/arm64-v8a/libctos_ssh_files.so`。
- [ ] qemu/ARM runtime、Android JNI packet runtime、真实 managed CAS/provider、userauth/
  typed credential、PTY/forward/service/UI 和外部 SSH 仍开放，不能勾选父任务完成。

## 2026-10-16 当前窄增量：synthetic KEX completion/host-proof boundary（G3.8 follow-up）

- [x] 在 native callback fixture 中接入同一 production caller 的 synthetic OpenSSH server，
  使用非阻塞 socketpair、2-byte 分片输入和 bounded output pump，驱动 client 与 peer 到真实
  `kex->done`；覆盖 server EAGAIN、短写和 client partial I/O。
- [x] 断言 matching managed `KNOWN` key 的 host evidence exactly once 且先于唯一
  KEX-complete callback；callback cycle=1、session digest 非零并逐字节匹配 peer session ID
  的 SHA-256；client completion 后后续 `step` 返回 `EALREADY`。
- [x] 补充严格 OpenSSH allocator 宏隔离（含 `realloc`），保持 production/test C11/Werror
  cross-link 可用；check review 无 remaining findings。
- [x] focused gate：`BUILD SUCCESSFUL`，production gate pass，native
  `gate=pass runtime=not-run reason=qemu-aarch64-unavailable`。
- [x] normal full gate：94 tasks，view/native/callback host pass，JUnit **189/0/0/0**，strict
  `compileSshFiles`、三 ABI Release、lint、assemble pass，archive=83，APK SHA256
  `7195a67fad84185714d00cbb2bbc899fbdf788b61f722282fae8b99ae8ba5f69`；APK SSH 相关 native
  库仅生产 `lib/arm64-v8a/libctos_ssh_files.so`。
- [ ] qemu/ARM runtime、Android JNI packet runtime、managed CAS/provider runtime、userauth/
  typed credential、PTY/forward/service/UI 和外部 SSH 仍开放，不能勾选父任务完成。

## 2026-10-15 当前窄增量：bounded native NEW candidate/CAS precommit（G3.8 follow-up）

- [x] 扩展 native callback fixture，支持 exact NEW candidate handoff/abort，并让 host
  evidence 对 KNOWN/NEW 返回对应的 `CAS_NOT_REQUIRED`/`CAS_COMMITTED` 结果；保留既有
  CHANGED/REVOKED/CERTIFICATE/INVALID 断言。
- [x] 用空 managed `ssh/known_hosts` 驱动 production verifier，逐字节断言 replacement
  body、candidate ID/classification/key/fingerprint、base/replacement digest，以及
  candidate-before-evidence 顺序；精确 NEW evidence 通过后 verifier 成功。
- [x] 增加 evidence rejection rollback：candidate 只 abort 一次，无重复 callback，client
  terminalize 后 `step` 返回 `EALREADY`，close release 失败仍可重试；production verifier
  在所有失败 host-verification 路径上保留 terminal 状态。
- [x] focused gate：`BUILD SUCCESSFUL`，production gate pass，native
  `gate=pass runtime=not-run reason=qemu-aarch64-unavailable`。
- [x] normal full gate：94 tasks，view/native/callback host pass，JUnit **189/0/0/0**，strict
  `compileSshFiles`、三 ABI Release、lint、assemble pass，archive=83，APK SHA256
  `7f0bd0f14ec2a5ae9175ee340512acfdeaf0aa76f843834d1a3c87107c97993b`，五个 JNI exports，
  forbidden stock caller=0；`git diff --check` pass。
- [ ] ARM runtime、完整 packet/KEX/host proof、userauth/typed credential、PTY/forward/
  service/UI 和外部 SSH 仍开放，不能勾选父任务 `implementation_complete`。

## 2026-10-15 当前窄增量：CERTIFICATE/INVALID host-policy classification（G3.8 follow-up）

- [x] 以生成并签名的 OpenSSH host certificate 建立 synthetic fixture；直接调用 issuer
  断言 `CTOS_SSH_CALLBACK_HOST_CERTIFICATE`、key/fingerprint 与相等的 base/replacement
  digest metadata、无 replacement/candidate handoff，再调用 production verifier 断言
  `EACCES` 且 host evidence 为零。
- [x] 在 issuer 的内存入口调用 pinned `hostkeys_foreach_file(...,
  HKF_WANT_PARSE_KEY, ...)` 预检 managed body；`HKF_STATUS_INVALID` 映射为项目自有
  `CTOS_SSH_CALLBACK_HOST_INVALID`，不伪造不存在的 upstream `HostStatus`。测试使用截断
  known_hosts 行加合法记录，直接断言 `INVALID` metadata/no handoff，再断言 production
  `EACCES`/zero evidence；合法 no-match 仍保持 upstream `NEW`。
- [x] focused native gate 通过：`SSH_PRODUCTION_OPENSSH_BUILD gate=pass
  fixture=excluded`，`SSH_NATIVE_CLIENT_BUILD gate=pass runtime=not-run
  reason=qemu-aarch64-unavailable`。这仍是 strict AArch64 cross-link，不是 ARM runtime。
- [x] 重跑 normal full quality gate 并同步最终 APK/ELF/JUnit 证据：94 tasks 成功，JUnit
  **189/0/0/0**，production archive=83，APK SHA256
  `b471e55f9bdd54830f6613643deec22a9f4d493408ce24ebdae9a03e17e6a912`，五个 session JNI
  exports 可见，forbidden stock caller=0；三 ABI Release、lint 和 assemble 通过。完整 ARM packet/KEX
  runtime、NEW CAS、userauth/typed credential、PTY/forward/service/UI 和外部 SSH 仍开放，
  不勾选父任务 `implementation_complete`。

## 2026-10-15 当前窄增量：bounded native step/output and host-evidence classifications（G3.8 follow-up）

- [x] 在 `ssh_native_client_test.c` 中用 synthetic socketpair 分片送入服务端
  `SSH-2.0-...` banner，驱动 production `ssh_api` 的 banner/KEX 输出路径；每步输入与
  输出均受 `step_budget=3` 限制，验证 `bytes_written == output_consumed`、peer 读回字节和
  `partial_write` 的含义。
- [x] 保留此前 EAGAIN、EOF=`EPIPE`/后续 `EALREADY`、deadline=`ETIMEDOUT`、cancel 和
  close-retry 回归，并将 managed-view expiry/invalid `ETIMEDOUT`/`EACCES` 与 client
  deadline 分开验证；无
  production/Gradle/设备/真实网络或凭据变更。
- [x] focused `:app:testSshNativeClientHost --rerun-tasks` 通过：production gate pass，
  `SSH_NATIVE_CLIENT_BUILD gate=pass runtime=not-run reason=qemu-aarch64-unavailable`；
  这是 strict AArch64 cross-link，新增案例未在 ARM binary 上执行。
- [x] 用生成的 public key 填充 synthetic managed `ssh/known_hosts`，调用 production
  `ctos_ssh_native_client_verify_host_key` 验证 `KNOWN` evidence result：精确 key 成功且
  callback 只调用一次；不同 key 在 pinned fingerprint guard 处返回 `EACCES` 且不再调用
  host evidence。
- [x] 增加 managed key 与 offered/pinned key 不同的 `CHANGED` regression：直接断言 issuer
  分类和 bounded issue metadata，再断言 production verifier 返回 `EACCES` 且不调用 host
  evidence；避免把 generic early rejection 当作 classification 证据。
- [x] 增加 matching managed key 的 `@revoked` regression：直接断言 issuer 返回
  `HOST_REVOKED`、key/fingerprint/digest metadata 和无 candidate handoff，再断言 production
  verifier 在 host evidence 之前返回 `EACCES` 且 evidence 次数为零。
- [x] REVOKED focused gate 与 normal full gate 复验通过：production/native host pass，native
  `runtime=not-run`（qemu-aarch64 unavailable），JUnit **189/0/0/0**，archive=83，三 ABI
  Release/lint/assemble pass，最新 APK SHA256 为
  `df4605a4f41158b600184cb9daac25a8dd61e15697e614d69c4243ff640d25f9`；五个 session JNI
  exports 可见，forbidden stock caller=0。
- [ ] 完整 ARM packet/KEX runtime、全分类 host policy/NEW CAS 与 ARM host-proof runtime、
  userauth/typed credential、PTY/forward/service/UI 和外部 SSH 仍开放，不能勾选父任务
  `implementation_complete`。

## 2026-10-15 当前窄增量：bounded native step error semantics（G3.8 follow-up）

- [x] 修正 `ssh_native_client_step` 在 managed view borrow 失败时无条件改写 errno 的问题：
  到期保留 `ETIMEDOUT`，revoke/invalid view 保留 `EACCES` 或底层精确错误，并 terminalize
  client，后续 step 返回 `EALREADY`。
- [x] 扩展 `ssh_native_client_test.c` 的 synthetic socketpair 回归：空 nonblocking peer
  EAGAIN、半关闭 EOF=`EPIPE`/后续 `EALREADY`、短 BOOTTIME deadline=`ETIMEDOUT`，cleanup
  仍按一次 peer close 完成。
- [x] focused `:app:testSshNativeClientHost --rerun-tasks` 通过 strict AArch64 cross-link；
  `SSH_PRODUCTION_OPENSSH_BUILD` 与 `SSH_NATIVE_CLIENT_BUILD` 均 gate pass，runtime 因无
  qemu 为 not-run。
- [x] normal full gate 已重跑：view/native/callback host pass，JUnit **189/0/0/0**，strict
  compileSshFiles、三 ABI Release、lint、assemble pass，archive=83，APK
  `872e8f438e83217c2a6f7ae44a8a3e7065f7eedbdefe4be64284e7103c81608d`；五个 JNI exports
  可见且 forbidden stock caller=0。
- [ ] ARM protocol runtime、完整 packet/KEX、host proof、userauth/typed credential、
  PTY/forward/service/UI 和外部 SSH 仍开放，不能勾选父任务 `implementation_complete`。

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

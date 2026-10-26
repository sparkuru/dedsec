# ctOS 信息入口

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

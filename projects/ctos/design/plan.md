# ctOS 产品与实施规划

## 2026-10-26 交付与归档边界

用户要求安装、说明功能并归档提交。正式APK已安装，本轮以已通过实机的前台密码SSH
连接交付关闭；这不代表原完整SSH父需求全部完成。[使用说明](ssh-terminal.md)。

父任务继续保留：R3/R6的用户config主机列表/复杂Include与有效配置、ProxyJump和各类
转发；R7的SAF导入/编辑与私钥/证书/KBI认证；R8/AC6的前台服务、后台/重建保活及通知；
R10的可选凭据保存；复杂信任/整库可连接恢复与原AC1–AC9中的未实测联合场景。
底层已有检查器/认证能力不等于相应产品入口已开放。客户端子任务归档只计本次交付，
父任务保持进行中，不自动新建任务或继续实施未选择的功能。

2026-10-26：[最小前台密码SSH闭环实机通过](verification.md#20261026-password-android)。
指定Android 11板上的真实Flutter表单、指纹审批、登录、命令输入/输出和关闭会话已验收；
实际bridge还覆盖首次NEW保存与KNOWN重连。正式APK准备完成，隔离测试环境已清理。
后续能力继续扩展同一主线，不再把基础密码连接描述为仅有底层测试或尚无可操作入口。

2026-10-25 当前：[前台密码SSH路径](ssh-password-connect.md)已实现表单→共享库准入→TCP→
真实主机键审批→临时密码→现有PTY终端，优先以独立服务端和Android实际连接验收。
私钥/证书/跳板/转发/后台服务及复杂资料恢复继续扩展同一主线，不作为这条密码路径的
前置条件。[本轮实测](verification.md#20261025-password-connect)严格区分本地测试与设备运行。

2026-10-25 用户决定：SSH尚未实际使用，[最新模型直接作为唯一正式主线](ssh-manifest.md)。
所有新建与重新编码使用同一模型，恢复按实际内容审阅。仅保护已交付TOTP数据的窄输入
适配；不维护旧SSH变体。后续优先打通最小密码连接闭环：明确账号Request绑定、实际
主机键确认及该路径准入，接connector和可操作入口，以成功使用验收。可选证书/恢复
各按自身条件补齐，不把它们的全部扩展作为基础密码连接的前置。
[本轮检查](verification.md#20261025-ssh-mainline)，父task进行中，本地未提交。

以下是历史决策/实测，双格式安排已被上述用户决定取代。

2026-10-24：[全部证书事实与签名政策](ssh-certificate-facts.md)已实现CSCF168、三份公开摘要/unsigned数值/实际record来源及现代CA签名政策，保留trust/time/principal/revocation未验证。ARM22239与旧材料矩阵、定向145/全量638 JVM、normal100全重跑/fixture111及三APK审计通过；[实测](verification.md#20261024-certificate-facts)。持久graph/账号、完整信任/reviewer/provider/恢复/connector与父AC仍开放；本地未提交，无设备/真实库账号。

2026-10-23：[全部私钥材料与凭据归属](ssh-private-material-ownership.md)已实现全PRIVATE自签/验签、v1口令唯一owner/phrase审计和password namespace/deferred target。ARM19976/独立pair13812/material7784、定向120/全量613 JVM、normal98全重跑/fixture111及三APK审计通过；[实测](verification.md#20261023-private-material)。仍不提供持久关系图/真实密码账号/完整信任/provider/恢复/connector准入，父AC开放；本地未提交，无设备/真实库账号。

2026-10-23：[显式密钥配对证明](ssh-key-association.md)已实现精确ID/所选口令、证书plain subject及固定domain实签验签，拒绝仅公开字段相等的伪配对。ARM13814/独立材料7784/fresh认证1748333、定向80/全量586 JVM、normal96全重跑/fixture111与三APK审计通过；[验证](verification.md#20261023-key-association)。报告只覆盖明确所选pairs；全库关联authority/覆盖、凭据目标、完整信任/reviewer/provider/恢复/connector与父AC仍开放。本地未提交，无设备/真实库账号。

2026-10-23：[显式资源引用绑定](ssh-config-resources.md)已实现RESOURCE_AUDIT/CSCR、五类声明静态类型与真实parent直接引用，保留动态/cwd/key-loader未决。真实ARM4439含基线/全部旧矩阵/IO426，定向151/fresh全量564 JVM、normal94全重跑/fixture111与三APK审计通过；[验证](verification.md#20261023-config-resources)。材料数学关联、凭据/信任/provider/恢复/connector与父AC仍开放，本地未提交，无设备/真实库账号。

2026-10-23：[CONFIG静态引用与本地效果审计](ssh-config-audit.md)已实现独立AUDIT、真实Include父/行/子与parent直接references核对，以及所有分支有限政策。真实ARM13290含基线/原矩阵/IO426、定向124/fresh全量537 JVM、normal94全重跑/fixture111与三APK审计通过；[验证](verification.md#20261023-config-audit)。报告仍partial-only，动态/非Include引用、身份/凭据/信任完整语义provider、恢复/connector与父AC仍开放。本地未提交，无设备/真实库账号。

2026-10-23：[实际清单受管配置检查](ssh-detached-config-managed.md)已实现Java/JNI两阶段owner、全部CONFIG声明扫描与整job累计预算。ARM3683（含基线）/原矩阵/IO426、fresh JVM514及normal94全重跑/fixture111与三APK审计通过；[实际验证](verification.md#20261023-detached-managed)。引用/全分支政策、身份/凭据/证书/信任完整语义provider、恢复/connector与父AC仍开放。本地未提交，无设备/真实库账号。

2026-10-23 接续：[独立 native 配置源](ssh-detached-config.md)已实现并完成本地验证。下一步Java/JNI整库编排、CONFIG/Include引用与全分支政策、身份/凭据/信任语义，再明确恢复与最终计划connector；不注册不完整provider，父AC继续开放。

2026-10-23：[TOTP写入与预览票据生命周期](totp-mutation-lifecycle.md)已实现原预算、锁外校验、精确交接与及时取消。定向196/全量483 JVM通过；普通与fixture构建/lint通过，APK审计见[验证](verification.md#20261023-totp-mutation)。下一步完整语义provider/恢复/connector，父任务保持进行中。

## 2026-10-23 TOTP 写入与预览票据（本地增量完成）

继续原task的[TOTP mutation合同](totp-mutation-lifecycle.md)：prequeue origin与独立子能力，
锁外pure/mixed校验、预览跨Request明确交接、原期限/快照与唯一effect claim。定向196/全量483与构建审计通过。
旧sessionManifest已无调用并移除；下一步补完整CONFIG/Include/身份/凭据/信任验证，
不提前启用生产恢复或声明connector完成。本地未提交，无设备/真实库账号。

## 2026-10-22 SSH trust CAS（本地增量完成）

继续原task的[trust CAS 生命周期](ssh-trust-cas-lifecycle.md)：审批前固定原context，
移出慢校验并保持精确原public view延续；提交后交付失败明确不确定。定向202/全量JVM469、normal92全重跑/fixture114及
三APK审计通过，[实际验证](verification.md#20261022-ssh-trust-cas)。
之后迁移剩余TOTP add/save/delete/rename及pending/publication相关消费者，再补齐
完整语义/恢复/最终计划connector；本地未提交，无设备/真实库账号。

## 2026-10-22 SSH public文件读取

继续原task，已实现[公开文件读取生命周期](ssh-public-read-lifecycle.md)：原context/期限/
取消/注册、锁外真实source与独立loan校验/closure、一次同步loan交付，以及独立native
prepare/create/final attach。known_hosts快照保留原role.deadline/view；own-NEW-CAS仍只
保留原精确loan，不加永久旧epoch revoker。最终定向178通过；全量JVM431、normal92全重跑/fixture114与三APK审计通过。
完整结果见[验证](verification.md#20261022-ssh-public-read)。下一项冻结credential/private-key
消费者的原请求与实际host/KEX/proof释放边界，再迁移mutation/trust CAS、完整语义/恢复/
最终计划connector；父AC继续开放。仅本地未提交，无设备/真实库账号。

## 2026-10-22 TOTP typed读取生命周期

继续原SSH task，已实现[TOTP_CODE/TOTP_LIST生命周期](totp-read-lifecycle.md)：排队前原
context、锁外认证/独立loan校验/生成、一次effect与opaque receipt，mixed注册绑定而pure
不新增provider依赖。实际列表/复制继续按当前窗口和锁库顺序复查；外部transport/Binder
仍有持session的继承限制。定向140/fresh全量JVM410、normal92全重跑/fixture114及三APK
签名隔离通过，[验证](verification.md#20261022-totp-read-validation)。
下一项冻结SSH public snapshot/sessionManifest的原请求、role/view、loan释放和publication
边界，再逐项迁移credential/private-key与mutation/trust CAS。完整语义/恢复/最终计划/
connector与父AC继续开放；仅本地未提交，无设备/真实库账号操作。

## 2026-10-22 SSH bundle 校验生命周期

继续原 task 的 [bundle admission/effect 合同](ssh-bundle-lifecycle.md)：审批前固定原
context/期限/取消/注册，独占候选与临时loan，锁外校验及一次消费，最终写入前快照复查。
AtomicFile 事务内只复查metadata/审批，不递归读存储；原wrapper/claim/uncertain保持。
最终定向81项、fresh全量JVM391/22reports零失败及normal92项全重跑/lint/Release已通过；
完整工件与未测项见[本轮验证](verification.md#20261022-bundle-validation)。其他typed/
sessionManifest、完整语义/恢复门和最终计划/connector继续后续；本地未提交，不设备/账号。

## 2026-10-21 SSH清单语义与恢复预览生命周期

已冻结[全图语义与生命周期合同](ssh-validation-lifecycle.md)，列明实际source/target/
candidate、配置图/身份/凭据/证书信任与真实Request逐字段义务；未覆盖项不作准入。
本轮只迁移恢复preview的capture/work/publish、认证临时loan及修改拒绝、不可变validator
注册、原admission期限和独立审阅窗口、私有请求owner取消与一次metadata交付claim。
其他typed/bundle/sessionManifest路径和完整生产validator/reviewer仍后续，SSH恢复门保持。
实测按[本轮验证](verification.md#20261021-preview-validation)记录；不设备/真实库账号/提交。

## 2026-10-19 SSH实际清单文件内容检查

在声明目录之后推进[离线内容检查](ssh-manifest-inspection.md)：直接检查实际传入的
source/target/candidate文件副本，复用固定密钥/known_hosts parser和既有有界加密策略，
不创建current-vault会话或假lease。配置语义、私钥/公钥关联、凭据目标及信任审批仍pending；
报告恒contentOnly=true/admissionComplete=false，不注册生产validator或开放SSH恢复门。
材料ARM7784/effects0、JVM358、normal95全重跑/fixture114及三APK审计已通过；
完整认证回归见[实测记录](verification.md#20261019-manifest-inspection)。
下一步冻结全清单语义准入和最终计划/connector合同；本地未提交，无设备/账号。

## 2026-10-19 SSH配置求值生产前置增量

已实现native受管配置求值器：固定readconf与Include、完整Options、返回式输入错误、
原borrow生命周期和可重试清理；最终静态ARM3736项/effects0与源码复核通过。
JNI/Java求值边界现已实现，ARM5201/effects0、fresh JVM308、normal95/fixture114及三APK
审计通过，见[JNI边界](ssh-config-managed.md)与[本轮验证](verification.md#20261019-config-jni)。
[无上下文声明catalog](ssh-config-catalog.md)已接同一parser/JNI/owner facade，保留literal来源、
静态遍历和动态Include未解析位置；声明结果不授予语义或连接保证。
下一步为实际manifest语义validator/逐字段最终计划，再接精确凭据选择/connector到现有MANAGED会话；
必须处理或明确拒绝保留的jump/forward/session字段。父任务继续进行中，当前仅本地未提交，
不设备/真实库账号。[合同](ssh-config-evaluator.md) · [验证](verification.md#20261019-config-evaluator)。

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

2026-10-19 最新：CSAS native metadata transport/readonlyreceipt已实现，role token/hop/
nonce/binding、strictUTF8和typedshape在ownershiptransfer前校验。JVM208、ARM253155、
lint/Release/v2通过；不发送auth或释放secret。下一步需receipt-bound native credential
callback与当前acceptedKEX/service-ready联合核对，再实现password/privatekey认证；实际
keymatch、selectedpassphrase/KBI及Android新JNI运行仍开放。见[验证](verification.md#2026-10-19--csas-native-selection-metadata-and-receipt)。

2026-10-18 最新：认证前 managed immutable selection 合同已落地，显式 username、exact
role/receipt 和固定 password/private-key 资源进入 provider/bridge 的 final guard；
fresh全量JVM201/0/0/0、lint/Release/v2通过。下一步需把选择传到 native 并核对 wire
username/current KEX/service-ready及解析后公钥，再实现认证请求；selected passphrase/KBI
及其独立身份能力继续开放，不访问真实账号。见[验证](verification.md#2026-10-18--managed-immutable-ssh-authentication-selection)。

2026-10-18 本地协议进展：原生 C 的显式 post-KEX `ssh-userauth` 服务协商已实现，
真实加密合成 peer 在 ARM QEMU/Bionic 上通过，旧 KEX-only/JNI 停止点保留。下一步
认证须先绑定 wire username 与不可变 credential selection，再实现认证请求；Java/JNI
服务接入、完整 Android KEX/auth、真实 CAS/vault 与产品 AC 仍开放。见[验证](verification.md#2026-10-18--native-post-kex-userauth-service-negotiation)。

2026-10-18：已解锁板上，当前修复版 fixture 的 JNI 分片/初始 KEXINIT/KNOWN/EOF
精确合成方法 1 项 / 0.202s 通过，两包清理、正式包保持。此前锁定导致的该窄关卡已
完成；完整 Android KEX/userauth、真实 CAS/vault 和产品集成继续按原 SSH task 推进。

2026-10-17 最新接续：native ARM 已从“仅 cross-link”推进为宿主 QEMU/Bionic 实际合成
执行通过（65,838 checks），并修复四处 production/runtime 问题；normal 94-task gate
通过，APK `f3327427…` 未安装。Android JNI fixture 仍待手动解锁后重建/实测；完整
userauth、真实 CAS/vault 与 PTY/forward/service/UI 仍开放，原 task 不归档。

2026-10-17 SSH 接续：JNI 分片 transport / 初始 KEXINIT 输出与 EOF fixture 已编码并通过
本地构建、lint 和源码复核。当前板 `RUNNING_LOCKED`，测试尚未启动；用户选择保留本地
结果，测试资源已清理。下一步为手动解锁后的精确 fixture runtime 验证，完整 KEX、认证
和产品关卡仍开放；task 保持 `in_progress` / `implementation_complete=false`。

2026-10-16 SSH最新补充（G3.8 Android ARM/JNI empty-peer EAGAIN）：生产 JNI
`step()` 在空 synthetic peer 上真实返回 `bytesRead=0`、
`inputAppended=0`、`readWouldBlock=true`；同一 bounded turn 的
`bytesWritten` 不作错误的零断言。随后 KNOWN host-proof 仍为
`OK (1 test)`、status 0、0.11s，fixture 已清理。该项不扩大为 ARM KEX/packet、
认证或完整 SSH；task 仍 `in_progress`/`implementation_complete=false`。

2026-10-16 SSH最新窄增量（G3.8 Android ARM/JNI KNOWN host-proof）：production
`SshNativeSession.verifyHostKey` 已在独立 synthetic fixture 中真实走过 Java→JNI→
native hostfile issuer/callback。managed `known_hosts` 使用同一 wire key blob 的
无换行 Base64，当前授权板 `192.168.9.14:5555`（AIO-3568J/API30/arm64-v8a）精确
instrumentation 为 `OK (1 test)`、status 0、0.089s；fixture 包卸载后无
`pm path`/进程残留。该项不扩大为 ARM KEX/packet、认证或完整 SSH 验收；task 仍
`in_progress`/`implementation_complete=false`。

2026-10-16 SSH最新窄增量（G3.8 NEW/CAS full-KEX）：空 managed `known_hosts` 已在真实
synthetic OpenSSH packet loop 中完成 candidate → `CAS_COMMITTED` NEW evidence → `kex->done`
顺序；candidate/evidence/KEX callback exactly once，route/body/digest binding、cycle=1、peer
session digest 和 completion 后 `EALREADY` 均通过。focused/normal gate 通过，APK SHA256 为
`7ccac2fb8f705edd761d34574b9cead4b9e2296864dfe5568d2e9b1105fd849f`；qemu/ARM runtime、
Android JNI packet runtime、typed userauth 及更广泛产品验收仍开放。

2026-10-16 SSH最新窄增量（G3.8 synthetic KEX completion）：production native caller 已通过
synthetic nonblocking OpenSSH client/server socketpair 走到真实 `kex->done`；matching managed
`KNOWN` key 的 host evidence 恰好一次且先于唯一 KEX-complete callback，cycle=1，callback
session digest 非零并与 peer session ID SHA-256 一致，完成后的 `step` 为 `EALREADY`。server
pump 还覆盖 EAGAIN、分片输入与短写。focused/normal gate 均通过，但 AArch64 runtime 仍因
无 qemu 为 `not-run`；完整 packet/runtime、auth/PTY/forward/service/UI 与外部 SSH 仍开放。
最新 APK SHA256 为 `7195a67fad84185714d00cbb2bbc899fbdf788b61f722282fae8b99ae8ba5f69`，task
仍为 `in_progress`/`implementation_complete=false`。

2026-10-15 SSH当前最新窄增量（G3.8 follow-up）：native-owned session/key/view boundary 与真实
JNI bridge 已实现。`ssh_view_handle` 以 opaque token 管理 native public view borrow/revoke，
`ssh_native_client` 拥有 parsed host key、managed-view borrow 和 caller FD，
`ssh_session_jni.c` 使用 owner-thread session registry；`step` 保留 managed-view deadline 的
`ETIMEDOUT`，并新增 host-only EAGAIN/EOF/deadline 与 fragmented banner/KEX output accounting
regression，以及 synthetic managed `KNOWN` key 的 production host-evidence success 与
pinned fingerprint rejection、managed-key `CHANGED` rejection 和 matching `@revoked`
`REVOKED` classification regressions；当前又加入有效 host certificate 的 `CERTIFICATE`
拒绝、malformed managed known_hosts 的 project-owned `INVALID` 预检分类，以及空 managed
known_hosts 上 production `NEW` candidate handoff → host evidence/CAS_COMMITTED 成功路径与
host-evidence rejection rollback。失败的 host verification 现在 terminalize native client，
后续 `step` 返回 `EALREADY`，close 仍可重试。normal JUnit=189/0/0/0、
host/normal gate 通过，`.14` synthetic open→revoke→close 与 public-file view matrix 各 1 项
通过并清理。qemu native runtime、完整 protocol/host proof/auth/PTY/forward/service/UI、真实
vault 和外部 SSH 仍开放，task 保持 `in_progress`/`implementation_complete=false`。

2026-10-15 SSH当前最新窄增量：在 managed known_hosts CAS provider 之上，已完成仍限于
host/fixture 的 pinned OpenSSH caller seam。它使用真实 `ssh_api` 附着受管 socket FD，覆盖
bounded partial I/O/EAGAIN、host issuer/evidence、NEW candidate rollback、KEX/NEWKEYS 到
单次 digest callback；固定 AArch64 PIE runner 为 `gate=pass runtime=not-run
normal_apk=not-wired`，最新 APK `67668e34…` 只含正常生产 JNI library。该 caller 未接入
normal APK，production event loop、认证、PTY/forward/service/UI、设备与外部 SSH 仍开放。

2026-10-15 SSH上一窄增量：managed known_hosts CAS provider 之上已冻结独立的 opaque
one-shot native→managed candidate handoff。C/JNI 只同步转移受 role/hop/target/
connection/host/port/key-type/fingerprint、base/replacement digest 和 candidate ID 绑定的
bounded body；JNI/managed/native 均在成功和失败路径清零，CSHR metadata wire 不变。receiver
只接受同一 provider-owned snapshot，成功后仍必须经原 guarded AppSecrets CAS 才能返回
`COMMITTED`。完整门禁为 JVM **177/0/0/0**、native client **27**、callback **20**、strict
compile/lint/Release 通过，APK `3a6228f4…` 且普通包排除 boundary/fixture。pinned
production OpenSSH hostfile caller 仍未实现，KEX/auth/PTY/forward/service/UI、设备
和外部 SSH 继续开放。

2026-10-15 前一窄增量：已实现 role/hop/target-bound HostEvidence/CAS
native↔Java seam：KNOWN 仅返回 `CAS_NOT_REQUIRED`，NEW 需要 exact proposal、显式
approval 与 exact CAS `COMMITTED`，classification/key type/fingerprint/base/replacement
digest 均绑定并由 C/JNI 复验。host gate=15、166 JVM、strict compile/lint/Release
通过。具体受管 known_hosts file view/CAS writer 已在下一窄增量接入；真实 OpenSSH
caller、KEX/auth/PTY/forward/service/UI 和设备验证仍开放；task 继续 `in_progress`。

2026-10-15 前一窄增量：source map 已确认生产 known_hosts issuer/CAS 尚未形成可编译
native-facing contract，本轮只加入 `ssh_native_client.[ch]` fail-closed boundary。它
校验 role/PID/UID、CLOEXEC/NONBLOCK socket、callback environment/token、deadline 和
bounded step，缺 provider 返回 `ENOTSUP`，不调用 OpenSSH/KEX/JNI/credential；严格
host gate=27，普通 APK 未纳入该 boundary。provider/CAS 合同冻结前，不把它写成生产
caller；真实 transport、设备和完整 SSH 仍开放。

2026-10-14 SSH前一阶段：同进程、无 fork/exec 的 KEX-only launcher 仍仅在独立 opt-in fixture/host seam；用户已确认同进程 topology、当前 native event-loop 线程同步附着 JNI callback，以及受管 config/known_hosts 文件视图 + exact trust proposal/CAS + typed private-key handoff 方向。adapter 契约与 bounded managed-provider handoff 已编入正常 `libctos_ssh_files.so`；NEW proposal 绑定 role/hop，typed bytes 绑定 host/KEX proof，host callback `gate=11`、162 JVM、lint、Release 构建通过。具体 file/CAS provider、ARM、设备、外部 SSH、vault、真实 OpenSSH event loop/caller/auth/PTY/forward/service/UI 仍开放。
下一生产切片是把已实现的 managed file/trust provider 接到真实 OpenSSH caller；fixture 的 `ssh_api` 内存流及 control framing 不得升级为生产 transport。

2026-10-13 G2构建前阶段（历史）：SSH执行入口为10-08-ssh-terminal-client（in_progress）；用户专项批准后的G0在`.14`独立UID/API30/Permissive/4KiB真实1项1.693秒通过，raw memfd可用/ASharedMemory不满足文件形态，夹具已清理。G1 Android OpenSSH10.6p1/OpenSSL3.5.9 ABI兼容基线编译通过，当前实施G2可信合成上游文件/ProxyJump夹具，尚未构建或设备验证。真实库操作已允许，当前未访问；远程账号未授权，生产客户端、完整crypto/runtime与后续政策/协议关卡开放。TOTP工作566ca51已提交归档；共享底座仍in_progress，未测平台与混合业务继续联合验证。

2026-10-13 用户确认TOTP效果，授权提交验收并继续下一项。当前TOTP交付按已记录实测范围完成收尾；未测平台/账号/跨设备/混合关卡继续保留。下一项复用SSH客户端task，先Android native内存文件访问探针，再按原计划推进完整集成，不自动降级为明文磁盘临时文件。

2026-10-13 后续：当前`.14`开发板安装/隔离自动测试已获授权，最终前台交接修复版3bc762d9…安装读回一致，221 Flutter/86定向/analyze及7项原生实测通过；系统双二维码选图与管理/生命周期全流程1项72.734秒通过，临时测试包/自有图片已清理，真实账号/硬件相机/跨设备混合恢复保留，用户已专项批准全局覆盖，原生合成复制/敏感标记1项6.616秒通过。见 [当前验证](verification.md)。TOTP任务保持in_progress，SSH按既定顺序随后接续；以下初轮“设备另授”以本段后续授权为准。

2026-10-13：用户要求检查当前实现并继续原推迟任务，明确复用已有task、先TOTP后SSH。[Authenticator](authenticator.md) 已完成本地实施/检查/构建，214 Flutter、122 JVM、45真实字体场景及最终arm64/v2通过；[实际验证](verification.md#2026-10-13-authenticator-totp-本地实现与最终产物)。原task保持in_progress，共享秘密库API30凭证快捷历史证据可复用，底座平台/非空跨设备及混合业务关卡保持。SSH客户端仍planning，按既定顺序接续；设备安装及真实账号另行授权。后文原“不得启动业务”按原日期解释，已由本次明确恢复批准取代。

2026-10-12 最终交付：概览整合、设置/安全迁移及全部截图细节按用户授权提交5e67227并归档，当前task指针清除；最终188全量/84真字体场景、25bcdf7c…设备读回及分类布局通过。原公共底座未测关卡和TOTP/SSH规划状态保持。下方同日current/in_progress及等待授权文字属于各阶段历史；[当前交付契约](overview-settings.md)。

2026-10-12 截图细节追加已实施并完成188全量/84真实字体渲染及新版开发板安装核验；分类全宽居中、标题Root信息、移除APP/终端眉标题、默认快捷及失败三选择行为见 [当前契约](overview-settings.md)。实机快捷调起、Home取消回退/重试和AppPTY切换保持通过，成功认证未重测；[验证边界](verification.md#2026-10-12-概览截图细节与默认快捷解锁)。本任务仍in_progress，不推进TOTP/SSH或原底座开放关卡。

2026-10-12 新 current：[概览整合与设置迁移](overview-settings.md) 已按用户要求启动并实施；主导航为概览、工作台、终端、设置，原信息四分类直接合入概览，顶部导出移至设置、公共秘密库完整管理移至设置/安全。此次入口迁移不推进原公共底座其他待测项或 TOTP/SSH，验证与设备边界见 [本轮记录](verification.md#2026-10-12-概览整合与设置迁移)。

2026-10-12 恢复：用户已设置开发板安全锁屏并授权继续测试。公共底座 AAD认证时序修复、同板真实快捷登记/解锁/关闭保护/主口令回退通过，修复主包 `71d00e7c…` 已安装，现有用户库正常设置及快捷解锁通过。保持in_progress，API28/29、生物、非空混合及其余平台/业务关卡仍开放；不自动启动 TOTP/SSH，详见 [验证](verification.md#2026-10-12-安全锁屏开发板快捷认证修复与实测)。下方暂停记录为历史。

2026-10-11 追加：用户报告主页双返回首版实机验收通过，要求提示改至下方。已利用 Scaffold 公共布局槽将提示置于底部导航/安全区上方，并自动避让普通浮动 Snackbar；16 定向/154 全量、analyze/格式及新 arm64 APK 构建/v2 验签通过，新包 `981ef814…`，见 [验证](verification.md#2026-10-11-退出提示移至下方)。保留四入口/2秒/重置行为，公共底座仍暂停；新位置未由 agent 实机复验，未提交/归档。

2026-10-11 任务切换：公共秘密库已按用户要求暂停推进，保留既有实现与待测关卡，不启动 TOTP/SSH。[主页双返回退出](../.trellis/tasks/archive/2026-10/10-10-home-double-back-exit/prd.md) 四入口 / 2 秒已实现，14 定向/152 全量 Flutter、analyze/格式和 arm64 构建/v2 验签通过；本轮未安装或实测设备，不替换 dist/提交/归档。实际返回/IME/桌面验收仍开放，见 [验证](verification.md#2026-10-11-主页双返回退出本地实现与构建)。

2026-10-10 本地继续：公共底座最终期限及SSH末次主机核验失效释放边界已修复，106 JVM/138 Flutter重新执行通过；独立UID快捷认证手动夹具已打包验签。用户本轮明确先本地工作，尚无成功系统认证实测、未开始业务接入；范围及后续关卡见 [最新验证](verification.md#2026-10-10-继续任务本地效果边界修复与手动认证夹具)。

2026-09-28 交付更新：可信状态、只读任务闭环、界面与终端整理三个子任务已按各自范围完成并归档；父任务正在做证据集成。工作台/HFTP 专项另按已验证范围归档，熄屏有效保活未通过，Root 电源扩展未实现。下文各阶段的计划、旧包与未提交/待测描述按当时状态解释；当前验证范围见 [验证记录](verification.md)。

日期：2026-09-24。状态：规划基线，功能实现以当前架构和验证记录为准。

2026-10-10：用户在最新完整规划摘要后回复“批准”；公共统一秘密库/认证子任务已启动，前置协调 SSH 无明文磁盘文件访问探针。底座验收后 TOTP 与 SSH 分别接入；随后用户指定配置 B，授权当前 APK 安装及隔离设备验证。最终 104 JVM、17 项平台与真实存储1项六场景通过及边界见 [验证](verification.md)，不据此标底座/双方业务完整验收；真实账号操作仍未授权。

2026-10-10 追加收敛：用户明确取消验证码剪贴板清理，ctOS 提供 TOTP 及用户主动复制/敏感标记，清理由用户自行处理；清理时长/跟踪/探针撤销，后台库保留秒数保持。以下 2026-10-09 的“两项时间/剪贴板竞争”描述属于旧规划，已由此决定取代。

2026-10-10 继续实施：公共 SSH 文件图、用户决定的导出同名确认/失败仅本次部分清理，以及来源和目标 SSH 恢复审阅绑定已实现；101 JVM/138 Flutter 与 arm64 APK 构建/验签通过。独立公共合同/适用平台关卡与真实业务/native恢复联合验收分列，避免客户端与底座互相依赖；不据接口就绪标记完整验收或自动启动新业务。见 [当前验证](verification.md)。

2026-10-10 追加：Bridge生命周期与0秒锁定清理修复后104 JVM、35定向及配置B 6项生命周期通过，主包ca85保留。独立UID实际release UI空库完整管理/导出/恢复1项235.79秒通过，测试资源已清理；成功设备快捷认证、API28/29、非空混合/真实锁屏与进程死亡及两业务联合验收仍开放。具体版本、历次驱动失败及未测边界见 [验证](verification.md#2026-10-10-配置-b-追加bridge-生命周期与独立-uid-ui-探针)。

## 定位与范围

2026-10-11 旁任务：[APK 主页双返回退出到桌面](../.trellis/tasks/archive/2026-10/10-10-home-double-back-exit/prd.md) 已进入 `in_progress`。用户确认概览、信息、工作台、终端四个主入口都生效；第一次返回留在应用内并提示“再按一次返回键退出”，2 秒内第二次返回才退出到桌面，超时重置。二级页、弹窗及键盘沿用原返回行为；切换入口、路由覆盖、键盘与后台转换清除旧确认。本地与设备验证分别记录，不由任务启动推定验收通过。

将 ctOS 从偏网络观测的工具扩展为手机上的系统观测与操作终端。面向设备使用者，在本机完成查看状态、定位对象、执行命令和保存结果。

本阶段以 Android 本机为范围；“命令接口”首先指用户交互与本地结构化命令。远程管理、供外部应用调用的接口、真正的 Shell CLI 尚未确定，不能从本规划推导为已授权实现。

2026-10-08：用户要求建立 [SSH 终端规划 task](ssh-terminal.md)，扩展“终端”以 SSH 连接设备，并读取 OpenSSH 风格 config 形成一键连接入口。兼容范围 B：Include / ProxyJump 进入首版，基础及主要功能参照 OpenSSH，技术主方案 APK 内 native OpenSSH；存储方式 A：用户主动导入到内部私有目录、不持续同步；首版拒绝配置驱动本地命令，保留受管 ProxyJump；允许用户选择长期加密保存密码/私钥口令。系统验证已提升为 App 公共能力，后续统一为下文主口令/便携秘密库/可选快捷模型，先统一底座、后 SSH 接入；SSH 已确认后台保留、与已有终端统一手动 Stop，独立前台服务持有会话，导航不主动断开；config 基础 TCP 转发已确认纳入首版，随会话 Start/Stop；当前仅授权规划，默认仅本机、显式对外监听连接前确认已确定，SSH 输入及 ↑/↓ 交远端，不追加程序逻辑/模式、不设特定程序兼容验收已确认；config 禁止绕过指纹核验已确认；客户端规划、工程界限与技术关卡已整理，但统一秘密模型及剩余范围已整理、待共同最终审阅；尚未实施，不包含 SSH 服务端、ADB 管理或设备登录授权。

现有基础包括 Flutter 界面、Java App/Root 采集、JNI PTY、网络信息与 JSON 导出。2026-09-26 按用户要求移除 Vector 系统桥接，见 [决定](decisions/2026-09-26-root-only.md)。沿用现有技术栈，逐步扩展和拆分。

2026-10-09：已确定 [Authenticator](authenticator.md) 为离线 TOTP，相机/手机图片读码/手动添加，工作台独立入口；HOTP 已撤销，其他平台迁移排除。先交付并验收 [统一秘密库/公共认证](app-authentication.md) 的主口令/密文/设备快捷/共同设置/共享会话/两项独立可调秒数/ctOS 完整加密备份/管理操作真实认证解密/敏感页面与复制保护；库含 TOTP 种子及受管 SSH 凭据/config/Include/私钥/关联公钥或证书/信任，本地受管持久副本加密。随后 TOTP 与 [SSH 客户端](ssh-terminal.md) 分别接入，TOTP 页面不是 SSH 的依赖；native 无明文磁盘文件访问可行性探针宜前置，系统 Keystore/窗口和剪贴板竞争仍需验证，不满足回规划。四 task 当前均 planning / 已确定范围整理完成、待共同最终审阅，无实现/构建/设备或账号授权。

## 产品结构

| 入口 | 职责 | 内容 |
| --- | --- | --- |
| 概览 | 了解设备当前状态并按领域查询 | 已实现设备摘要与设备/网络/接口/连接；完整领域扩展仍按下方能力规划 |
| 工作台 | 执行目的明确的任务 | 搜索、参数表单、执行环境、输出、历史和收藏 |
| 终端 | 连续交互和自由操作 | App / Root Shell、会话切换、快捷键、输出搜索与复制 |
| 设置 | 统一全局设置与管理入口 | 数据导出、安全（完整公共秘密库管理） |

现有接口和连接页面归入网络分类。详情页支持对象关联，例如从应用查看 UID、进程、连接和日志，再携带包名进入命令界面。首页突出关键信息，完整字段放在详情页。

## 信息能力

| 领域 | 优先内容 | 后续扩展 |
| --- | --- | --- |
| 系统与设备 | 型号、系统版本、内核、架构、运行时长、显示信息 | 硬件组件、更多系统属性 |
| 性能 | 内存、可用 CPU 指标、短期趋势（系统负载已移除） | 进程分析、持续记录 |
| 电源与温度 | 电量、充电状态、可用温度数据 | 耗电趋势、唤醒与休眠分析 |
| 存储 | 容量、可用空间、可见挂载点 | 目录占用、文件检查 |
| 应用 | 可见应用、包名、版本、UID、权限信息 | 组件、安装来源、关联数据 |
| 进程与服务 | 可访问进程、PID / UID、基本状态 | 服务、线程、资源关联 |
| 网络 | 保留已有配置、接口、连接并改善关联 | DNS、连通性诊断、历史对比 |
| 日志与事件 | 按需读取、过滤、暂停、导出 | 多来源时间线、故障记录 |

以上是能力目标，具体字段依设备、ROM、权限与实测确定。优先保证来源和限制可解释，再扩充覆盖面。

## 工作台能力

结构化命令由命令目录、参数表单和结果页组成。统一记录名称、参数、执行环境、开始时间、耗时、退出码、标准输出、错误输出以及超时或取消状态。支持历史和收藏；历史的保留上限、敏感参数处理和清理方式在实现时明确。

拟定内部语义示例：`device.info`、`memory.snapshot`、`apps.inspect`、`network.connections`、`logs.read`。这些标识供界面、收藏和后续脚本复用，尚不是可在 Shell 中调用的命令。

交互终端继续承载自由 Shell 操作。结构化任务和 PTY 会话分别管理生命周期，可共享底层执行设施，但不混用输出协议或状态。

## 技术演进

职责流向：页面 → 信息仓库 / 命令注册表 / 任务与会话管理 → App、Root 采集或 PTY。

优先建立公共模型：

- 能力状态：可用能力、所需权限、不可用原因、恢复方式。
- 信息快照：数据、来源、采集时间、有效状态。
- 执行结果：输出、退出码、耗时、超时与取消状态。

逐步拆分现有集中式页面状态与原生调用分发，让网络作为一个领域迁入。通过明确的模块接口扩展，暂不引入动态插件框架。

## 阶段与验收

| 阶段 | 交付 | 验收标准 |
| --- | --- | --- |
| P0 整理底座 | 新导航、页面拆分、公共模型、能力状态 | App/Root 网络、PTY、Root 会话和导出无回退；Vector 已移除 |
| P1 扩展信息 | 系统、CPU / 内存、电源、存储 | 无 Root 的基础路径可用；受限字段有原因；采样不阻塞界面；前后台行为正确 |
| P2 命令闭环 | 命令目录、参数表单、任务结果、取消、收藏 | 信息页可发起任务并保存结果；超时可恢复；任务输出和资源有界 |
| P3 对象关联 | 应用、进程、连接、日志联动 | 可围绕一个应用完成信息查询、关联排查和结果导出 |
| P4 持续使用 | 多会话、快照对比、按需后台任务、脚本 | 生命周期、资源占用、权限变化通过实机验证；后台行为明确可控 |

原规划建议先完成 P0 + P1，再以少量只读结构化命令验证信息到操作的贯通体验；对应的可信状态、接口诊断、三项任务历史和选择性导出切片现已交付。完整 P0/P1/P2 阶段仍以本表的全部能力和各自验收为准，不能由这些切片推定完成；P3/P4 保持规划状态。

2026-09-28：只读任务与选择性导出切片已完成 Flutter/Python/Android 本地检查及授权开发板验收。应用强制停止再启动后历史仍可查看，成功/失败状态、单条 SAF 导出读回、取消不写文件和手动清理均通过；设备范围与未执行的 Root 测试见 [当前验证](verification.md)。界面与终端切片随后完成 79 项 Flutter 测试及单独授权的开发板定向验收；完整对象关联、收藏和持续任务仍未由此交付。

## 验证路线与延后范围

使用 `BOARD-ADB-SERIAL` 开发板做主要回归；手机另行验证窄屏、软键盘、前后台切换及 SELinux Enforcing 环境。已有开发板记录不能替代手机验收。

后续再评估远程 SSH / ADB 管理、完整 Linux 用户环境、自动化调度、对外 HTTP 命令服务和动态插件。后台持续任务在 P4 单独设计，不能随新增采集默认启用。

2026-09-26 按本轮用户要求，原命令页已演进为工作台：APK 内置 Python3、终端 python3、四项脚本、item 二级页和 SDK v1。运行包清单支持编译时追加 Android 原生工具与 ZIP 数据；外部 ZIP 导入、包管理、动态插件、历史和后台任务仍未实现。见 [实现契约](portable-workbench.md) 与 [当前验证](verification.md)。

## Portable 工具集阶段（2026-09-26）

已建立父 task 与公共底座、08、26、09、02、HFTP 六个子 task。本轮仅此五项：模块、SDK/CLI、文件 broker、加密依赖和后台服务均已接入，完成本地检查及用户授权的 TARGET-PHONE 验收。七项组合手机测试通过，SAF 实际导入/取消/产物导出读回、HFTP 通知停止及09指定公共IP实际查询通过。2026-09-27 按用户要求做当前进度提交；任务状态仍为 in_progress，未归档。Android11、原生16KiB页设备和真实离线网络仍不在本轮实测结论内。见 [工具设计](portable-tools.md)。

## 工作台体验优化（2026-09-27，已实现并完成 TARGET-PHONE 定向验收）

后续日志/Root分离已实现并经TARGET-PHONE实际LAN验收：工具声明能力，由ctOS adapter承载可选网络中继，文件/Python仍App权限，普通无Root基础路径保留。保持VPN/lockdown不可bypass条件下Windows已确认打开，33MiB读写及停止回收通过；源码未提交，页面产品审阅仍待确认。见[Root实机记录](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/root-device-check.md)。

追加九张图片反馈的UI及App HFTP LAN/7888/免登录、上传限额、本机目录授权已实现并安装最终d43f52ed…版本；本地检查与TARGET-PHONE普通键盘、两种目录provider、33MiB读写/无覆盖/后台/停止通过。电脑直连LAN仍超时，跨设备可达性未通过，保留任务in_progress及未提交状态。详细结果见 [验证记录](verification.md)，不把源码实现等同于完整LAN验收。

基于 TARGET-PHONE 的实际界面审阅，用户批准的工具前置、中文分层表单、专用结果展示、布局对齐及 HFTP 状态/操作分区已实现，43 项 Flutter 测试通过。新版 `98f44069…` 经本任务“允许”授权覆盖安装，完成界面、键盘、SAF 导入/取消/导出读回与来源哈希定向验收。任务与验收标准见 [workbench-usability](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/prd.md)。沿用现有工具算法、SDK 与权限边界；本轮不增加搜索、历史或新工具。实际范围和未验证项见 [验证记录](verification.md)，源码未提交/任务未归档；不据此将 P0/P1 或命令历史阶段标为完成。

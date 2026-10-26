# SSH 生产启动器合同与增量

日期：2026-10-19。原task `10-08-ssh-terminal-client` 继续进行中。
认证与session/PTY/shell本地增量已完成，本轮保留未提交工作区，不设备操作、不用真实账号。

## 目标与当前缺口

最终产品路径为：受管config导入/验证→native有效配置与主机索引→不可变连接计划→
受管socket/每跳role→实际远端host key与精确NEW审批/CAS→选定认证SUCCESS→现有终端。
必须沿用已批准的Include/ProxyJump、拒绝执行型配置、公共秘密库与前台服务合同。
生产native readconf evaluator、JNI/Java边界及[声明catalog](ssh-config-catalog.md)已实现；
[实际清单材料检查](ssh-manifest-inspection.md)也已实现，但恒contentOnly=true/admissionComplete=false。
仍缺全清单语义validator/逐字段最终计划/connector，不能另写Java字段子集或静默丢弃jump/forward。
2026-10-21进一步冻结[语义义务与生命周期](ssh-validation-lifecycle.md)，先迁移实际恢复
preview的锁外校验/审阅、原期限/取消/注册与交付/commit fence。此迁移不等于其他业务
入口或完整语义provider完成，生产SSH恢复仍拒绝；实际结果见[验证](verification.md#20261021-preview-validation)。
原PINNED open要求caller预先提供服务器公钥；本轮显式MANAGED入口已补齐实际offered-key前置链路，
尚无任意真实端点的完整产品启动路径。
研究见task research/launcher-native-20261019.md与launcher-managed-20261019.md。

## 第一增量：实际offered key（本轮冻结）

只补齐启动器的host-key前置链路与审批邮箱；不宣称产品启动器或完整客户端已完成。
旧PINNED入口、全部认证/SUCCESS-only/channel/期限/撤销/owner合同保留；不延长原60秒role。

### Native入口与guard

```c
enum ctos_ssh_native_host_key_mode {
    CTOS_SSH_HOST_KEY_PINNED = 0,
    CTOS_SSH_HOST_KEY_MANAGED = 1
};
/* config追加 host_key_mode；未显式opt-in的旧caller仍为PINNED。 */
int ctos_ssh_kex_bind_pending_host(struct ssh *, ctos_ssh_role_token);
int ctos_ssh_kex_pin_offered_host(struct ssh *, const struct sshkey *);
```

PINNED仍要求原有blob+32byte非零fingerprint；MANAGED必须两指针NULL、length0，拒绝混合
参数/未知mode。全部admission和allocation在socket ownership transfer前。Managed创建ssh_api
client时不调用ssh_add_hostkey，不预选任意known_hosts记录；空public_keys保留上游默认
host-key算法proposal，注册同一个项目verifier。不引入stock高层caller/第二套OpenSSH。

pending-host guard只绑定当前真实role/PID/UID/hop/nonce/connection/deadline并预留唯一slot，
没有host approval/signed/completion权限。只有真实初始KEX cycle1已begun、尚未approved/signed/
complete、host尚未绑定时才可pin一次；PINNED或重复/未开始/过期调用拒绝，不能改旧pin。
未pin的approved/signed/complete/ready均拒绝。旧guard注册、去重和close/unbind保留。

实际transport verifier取得upstream提供的key后：

1. 校验owner/env、当前role/view/cancel/deadline、managed mode和初始真实KEX阶段；
   预分配owned public-key copy及32byte fingerprint scratch，所有失败清理。
2. 调同一hostfile issuer分类、candidate handoff、host evidence与exact managed结果匹配；
   只接受KNOWN或已明确批准并CAS_COMMITTED的NEW。CHANGED/REVOKED/CERTIFICATE/INVALID拒绝。
3. callback后重新检查owner/env/role/view/cancel/deadline、唯一cycle及exact结果；通过后
   一次pin guard并保留owned key/fingerprint。任何失败terminal，不发凭据、不复用失败attempt。
4. 返回upstream后仍由已有approved→实际signature verify→signed→NEWKEYS→complete路径
   决定KEX完成；pin/host approval都不能代替签名、session digest或认证SUCCESS。

Managed入口禁止公开手动verifyHostKey；不能用caller传入的任意key触发discovery或信任。
只有真实transport callback可以完成pin。失败candidate依旧abort；已明确批准且已提交的
known_hosts事务不能因后续连接失败伪装撤销，仍拒绝后续认证/秘密交付。

### Java/JNI入口

```java
static SshNativeSession openManaged(SshNativeCallbackAdapter callback,
    SshPublicFileLease lease, int socketFd, String candidateId, int stepBudget)
    throws VaultException;
```

NativeBinding追加不含host-key/fingerprint参数的显式openManaged default（IOException
failclosed）；production独立JNI openManaged复用有界registry/borrow/admission/失败FD归属。
旧open签名/wire不变，不通过null旧参数隐式降级。Managed实例拒绝手动verifyHostKey且
不调用binding。selection receipt、密码/私钥、channel与close路径复用现有守卫。
JNI入口19个；不得新增credential/主机键getter，实际公钥仍只通过原证据/候选合同。

### 精确NEW审批邮箱

新增 `SshHostKeyReviewMailbox` 实现 `SshHostKeyAuthority.UnknownKeyIssuer`，在原owner线程
的同步callback中等待；只发送有界公开review ticket，不含秘密/native token/body。
每个ticket绑定exact Review对象/完整review.binding/role对象、唯一nonce、原role deadline；
UI只能对当前ticket提交一次批准/拒绝。旧/重复/跨邮箱/过期ticket无效，不记住“信任所有”。
批准只满足原UnknownKeyIssuer检查，仍需原exact proposal/CAS/后置guard；不直接签发proof。

等待循环使用独立monitor，单次wait<=50ms，整体wall-time<=30秒且不超过原role期限；
进入、等待期间和返回批准前重复exact role/current callback live checker。取消、锁定、
过期、interrupt或拒绝立即结束，ticket失效；不延长loan/role、不重新发起认证。UI决策/
取消直接到mailbox，不排队到被等待的owner、不进入任意session JNI。默认缺审批sink拒绝。
hostKeyAuthority调用方仍拥有exact身份与CAS批准，不接受只有字符串相同的旧ticket。
Sink.present/dismiss只做非阻塞发布；30秒上限约束邮箱等待，不能约束任意调用方阻塞代码。

当前JNI在同步callback期间持有全局sessions_lock，所以等待会串行阻塞所有session JNI；
必须明确保留该局限并设wall-time上限，不能承诺同时pump其他hop/session。cross-thread
cancel只用现有Java取消路径+mailbox独立唤醒，cleanup留在owner返回后；本轮不改全局锁合同。

## 本轮验收

- native真实加密peer中MANAGED open无caller key/fingerprint；KNOWN/真实NEW-CAS后完整
  KEX→密码或私钥SUCCESS→shell输入输出/resize/exit/cleanup。旧PINNED矩阵全部保留。
- 拒绝混合mode/缺少旧pin、changed/revoked/certificate/invalid、手动verify、重复pin、
  pin前approved/complete、callback取消/期限/撤销和错误环境；无provider提前释放或FD转移。
- JVM显式managed open default/owner/reentry/claim/cleanup与禁手动verify；mailbox批准、
  拒绝、过期、取消/唤醒、迟到/重复/交叉ticket、批准前postguard和有界等待。
- JNI严格构建/19 exports、独立fixture本地编译；native QEMU、fresh JVM/lint/Release、
  Flutter回归及APKs隔离验签。当前不运行设备、不安装普通APK、不提交/归档/push。

## 后续顺序

1. 生产readconf/managed Include evaluator：保留上游语义和诊断，消除输入fatal/全局状态/
   文件及命令副作用，提供有界求值结果；可执行计划需逐字段完成raw行为后冻结，未经验证不能挂到AppSecrets validator。
   native及[JNI/Java结果边界](ssh-config-managed.md)、声明catalog已实现并过本地门；下一为实际manifest语义validator与最终计划。
2. 受管导入/catalog/凭据选择与socket connector→本轮managed-open/审批→现有终端；
   所有实际网络/账号实测只按当次授权，合成端点不外推外部真实账号。
3. ProxyJump/forward与service/长生命周期依原产品合同分别落地；原role/库解锁期限分离
   必须另冻结与验证，不能为了产品“常驻”绕过当前proof/撤销约束。

## 当前实现与验证状态

原生/Java/JNI与审批邮箱已实现，两轮正式源码review无具体finding。续做已补齐完整
normal94项全重跑、fresh Gradle JVM288、fixture113项及三APK验签/隔离审计，19JNI/archive83
通过，审核服务容量阻塞已解除。旧动态Bionic运行环境随临时目录消失；独立静态NDK/Bionic
测试专项87300/完整1724636通过，不能代替旧动态执行或Android JNI实测。
[当前证据、工件与重现边界](verification.md#20261019-managed-host)。不提交、不设备操作；
native及JNI/Java配置求值、声明catalog已完成本地验证；[最新验证](verification.md#20261019-config-catalog)。下一为语义validator、最终计划及connector，SSH恢复拒绝门保留。

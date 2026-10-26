# SSH public文件读取生命周期

日期：2026-10-22。原10-08-ssh-terminal-client的接续合同；仅迁移SSH_FILES admission、
sshFiles/sshFileSnapshot同步公开loan、sshPublicFiles native lease准备及role-bound
known_hosts snapshotFor调用。credential/private-key/mutation/trust CAS和legacy
sessionManifest的其他消费者不在本轮，不注册生产语义provider/reviewer，不开放恢复。

已实现并通过final focused178/fresh全量JVM431、normal92全重跑/fixture114与三APK审计；
[实际结果与限制](verification.md#20261022-ssh-public-read)。完整SSH task仍进行中。

## 原上下文与真实来源

- native-only metadata context在queue/耗时工作前创建，绑定owner、epoch、原绝对deadline、
  guard、不可变validator注册、direct cancellation与一次admission。不得捕获key/存储或
  持core创建context。默认入口一次120秒预算；显式caller原期限不能被后续阶段续期。
- bootstrap尚无role，只绑定本次root/connection；role-bound snapshotFor必须先绑定
  同一role/public view的liveness与原role.deadline，不能新取120秒绕过role预算，也不能
  以新context替换该role已有view。该读上下文不授予host/KEX/凭据或trust权限。
- capture短core锁复制实际cipher/key/revision，单read槽直到cleanup结束；BUSY不消费
  有效effect，context admission一次。错误owner/purpose/root/connection不消费操作，
  匹配被接受effect在锁外工作前一次consume/remove，失败不可重放；取消优先于重放。
- parse/decrypt/decode、真实source语义validator和文件闭包/快照准备在core/phase/pub/
  session之外。validator借独立decode canonical loan，修改拒绝，Error也擦除；不能用
  live-view或假bundle operation代替真实来源。最终public loan不含private-key正文，
  metadata/引用顺序/原vault/generation/binding/epoch/deadline与closure合同保持。
- 各阶段检查原context/operation取消、epoch/foreground/期限/revision/原注册；同实例重挂
  也失效。原文件不存在和private root继续拒绝，不借纯TOTP免provider规则放行SSH。

## 签发、同步loan与native lease

1. admission末次raw store equality在core内、publication之前，返回后再查metadata；
   临时比较cipher缓冲在core内擦除。原guard.publish以phase→pub→session短fence签发
   精确操作并记录context.issued；context.cancel在session登记取消后锁外取消精确issued。
   hook在签发后抛错必须撤销provisional操作。metadata临界区不能读库/解析/回调/清理。
2. 同步snapshot：锁外准备snapshot，core内复查末次raw store，再以原phase→pub→session
   一次metadata delivery claim排序。释放全部锁后调用receiver，返回前复查失效，finally
   统一擦除loan/Verified/work并释放槽。claim前失效不能调用receiver；claim后失效允许
   已开始的公开loan工作完成，但不成功返回旧结果。已观察字节不能撤回，不在取消线程
   并发擦除receiver借用数组。receiver只允许同步native模块消费，不得保留借用正文数组；受管owned副本
   仍需独立生命周期，不能当作下一阶段role/trust/secret许可；本规则不得用于私钥/密码/验证码实际披露。
3. native lease必须使用独立prepare→Native.create→final attach链路，不能套用同步loan的
   先claim发布语义。native创建/encode均锁外；创建后回core末次raw equality，原phase→
   pub→session内检查原context/操作/epoch/期限/注册/revision并依次attach operation与session。
   final claim前释放core，依靠pub fence内revision复查拒绝间隙提交；不能把guard后钩子
   的取消/native revoke放进core。prepare必须一次预留attach尝试，拒绝重放不得撤销已经
   发布的同一lease；未接受参数由调用方保留所有权，生产路径仍负责失败close。
   operation锁内只登记revocation，禁止native cleanup；后查取消/lease.active并复查返回。
   cancel-before-bind拒绝；attach后cancel精确revoke；session.lock与attach由session排序。
   session已有active view时拒绝BUSY，不能替换它；所有权接收前拒绝当前session已挂接的
   同一view。参数必须是本次调用方拥有的临时lease，不是任意foreign view转移API。
   生产链路任何Throwable都在锁外关闭临时lease，
   不返回active未挂接或已失效lease。JVM用有界fake Revocation测试final attach seam；
   实际JNI create/destroy需另有平台证据，编译不能代替。
4. 所有foreground检查先于可能lazy-revoke的session getter，避免phase里做阻塞revoke。
   cleanup/loan wipe/native close/任意receiver不进入phase/pub/session/core；唯一例外为
   短core内末次opaque ciphertext比较buffer擦除。单次parser/native调用不可抢占。已有VaultSession.lock在session内同步revoke的行为
   未迁移；这里的锁外cleanup约束指本轮work/loan与失败的临时lease清理。默认context的
   foreground/epoch、capture metadata/key复制、所有权接收前foreground/attachedRevocation
   各自必须共用同一session fence，不能留下后台切换后lazy revoke的间隙；外部guard仍锁外。

## own-NEW-CAS与剩余消费者

新context只约束admission到该读/lease发布，不能新增一个永久跟踪旧epoch/registration的
lease revoker。发布后沿用operation.cancel和session已挂接view的精确撤销；原own-CAS只
保留它自己的同一active public loan，epoch可推进但原role/view/期限不续期。其他lock/
重新unlock/任意mutation仍revoke。SnapshotReceipt/CAS exact identity及host/KEX门不降级。
role/public view关闭或到期、当前库变化/取消发生在snapshotFor返回前时不返回新receipt。

## 验证

覆盖admission/effect暂停validator下direct context/operation cancel、lock、foreground、
原deadline、same/new注册、raw store/revision变化及有界响应；独立loan修改/关闭/保留/
checked/runtime/Error擦除；BUSY/reentry/错误root/connection/owner/replay和private正文排除；
publication-hook后抛错、末次store seam、claim后失效、receiver锁外/Throwable清理；
lease final attach前后取消/lock/active-view BUSY、失败精确清理、role原期限与view revocation。
复跑known_hosts own-CAS/session/operation、TOTP/bundle/preview/restore回归，再fresh全量JVM/
lint/Release/隔离fixture编译与三APK审计。仅本地未提交，不设备/真实库账号/安装/发布。

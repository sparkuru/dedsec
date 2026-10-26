# TOTP 写入与预览票据生命周期

日期：2026-10-22；2026-10-23 接续。继续原 SSH 客户端任务；实现已落地，定向196/全量483 JVM通过；构建审计见下方实际结果。
范围为 TOTP add/save/delete/rename、pending/publication、TotpPreviews 与 Bridge 生产入口。
不改变 URI/相机/图片解析及产品协议，不操作设备或真实账号，不启用生产 SSH 语义恢复。

## 原请求与预览交接

Bridge 排队前建立 metadata-only TotpMutationOrigin，固定 owner、epoch、原 deadline、
不可变 validator 注册和原 request guard。每个 capture/candidate/管理操作派生独立一次
admission context；不能让一个已使用 context 同时签发 capture 和最多 16 个候选。
派生不更新期限/注册/epoch，兼容直接入口只建立一次默认预算。
origin 在首次认证后固定 source revision/binding/mixed；capture 与候选不能各自接受
不同快照。单 context.cancel 只撤销本操作，origin.cancel 撤销整组，避免 capture
成功清理时意外取消已交接的所有候选。组内数量有界，不引入无限候选注册。

预览与保存是不同 Request。预览成功发布前必须保留原请求的 active/取消/phase 检查；
只有精确的 host-owned preview 最终发布才能交接 origin，之后由该预览拥有取消权，
保留原快照/期限/注册/一次能力，不再依赖已经完成的原 Request.active。
新 commit Request 额外提供当前 effect guard 与唯一 beforeCommit claim，不给票据续期。
失败/取消请求立即撤销 origin；成功发布的 cleanup 只撤销 capture，不误撤销保留候选。
最终发布使用 opaque 候选 operation/state，不能仅凭调用方 epoch/revision 标量伪造交接。
交付 callback 抛出时撤销该票据并清理；不能因已标记 handedOff 而遗留可提交的失败预览。

## 所有权与校验

核心短锁 capture 真实 ciphertext/key/revision；认证、decode、source/candidate 独立loan
校验、候选构造、加密与读回都在 core/session/preview monitor 外执行。
pure TOTP 不新增 SSH validator 依赖；mixed source 与完整 candidate 保留原注册校验。
所有 candidate arrays 独占，含未改 entries/files；input seed 在可撤销 owner 锁内复制后
由 worker 拥有，取消不擦除 worker 正在使用的副本。loan 变更拒绝，Throwable 释放资源。
add-only 在同一认证快照/一次 effect 内确认新 ID 不存在；rename 保留 identity/seed，
delete/save 不越入 SSH namespace。错误绑定/有效 BUSY 不消费，接受 effect 后一次消费。

## 预览并发与最终发布

TotpPreviews 只在短锁中保留/摘除精确 generation、候选与 origin；不能持 monitor 调用
慢 core/validator。关闭先失效，再锁外撤销/清理；prepare、check、commit 的迟到结果不能
覆盖新预览或关闭其他 owner。取消在 admission 尚未产生 request 时也有效。
prepare 先在 manager 锁外检查 owner/epoch/前台/解锁/期限/取消与已固定的 revision/注册，
再在短锁内拒绝比 current 更旧的 epoch/固定 revision，之后才能替换 current；
拒绝复用当前 origin 时不得取消合法保留票据。未认证的 pure/mixed 不提前猜测，
不能无条件拒绝注册变化而破坏 pure TOTP。
第二个 commit 未取得执行权时返回 BUSY，不清理第一个执行者的 ticket/seed。
摘除时先设置 origin 的可见失效位，锁外再清理；不能让旧 ticket 在这一间隙通过发布。
共享 operation 容量登记前回收过期、取消和旧 epoch 能力，保持既有 64 上限可恢复。
pending 检查保留原快照及 mixed 注册；最终 UI 发布只做 bounded metadata 检查，不读
AtomicFile/解密/校验，遵守 phase→publication→session 顺序，避免 requestGuard.publish
已持 session 时反向取 publication。实际 Result 为有界且不可重入 core 的传输回调。

## 最终事务与限制

最后 core 串行检查原 origin/context/注册/revision/epoch/期限/取消，读取 wrapper 与
ciphertext equality，再进入原 Store；事务 guard 仅 metadata，不重读存储或做校验。
context guard 只用于持续检查/发布；新 effect.beforeCommit 是唯一耐久写入 claim。
保留原 envelope id/generation/salt/key、新 nonce、wrapper、成功 session reset/旧能力
清理及既有 uncertain 锁库；不能将 own-CAS 延续规则移植到普通 TOTP 写入。
Store 仍串行，既有 guard RuntimeException/Error 磁盘 failWrite 限制不在本轮保证内。

## 验证计划

覆盖 pure/mixed 四类写入、source/candidate 暂停下取消/锁库/期限/注册/存储变化、
独占候选/loan变更/Throwable清理、错误绑定/BUSY/重放、add不覆盖和命名空间限制；
预览原预算、capture与多候选、取消未签发/已发布、跨Request正常确认与失败交接、
迟到generation隔离、最终发布无存储读取，以及唯一claim/wrapper/uncertain。
复跑已有TOTP及SSH生命周期，执行全量JVM、lint/Release/fixture/APK审计；不将宿主
合成测试或平台fixture编译称作真实Android相机/图片/AtomicFile运行。

## 已实现结构与审查修正

AppSecrets 的 origin/context/work/state 分别承载原请求、一次 admission、独占 worker
材料和 opaque pending 权限；TotpVaultAdapter/VaultOperation 传递精确能力。
Bridge 在排队和外部选图前建立 origin，原 Request 成功结束后只清理 capture；
新 commit Request 提供独立 effect guard。预览过期调度使用原 deadline 剩余时间。
TotpPreviews 保留短锁票据与 worker 独占 seed，实际核心校验和清理在 manager 锁外。

审查修复共享64能力回收、摘除与最终发布之间的取消可见性、第二个 BUSY commit
误清理首个执行者、迟到 prepare 替换有效票据、其他秘密库的 owner 权限误撤销、固定快照
字段发布，以及 Bridge 传输异常的请求清理。首次 source pin 在 tuple 写入完成后
以 volatile pinned 发布；revision 同为 volatile，manager 无需反向获取 session。
旧 sessionManifest/beginMutation/validateBusiness helper 已无生产及测试调用并删除；
这不代表管理密码、导出加密或完整 SSH 语义 provider 全部完成生命周期迁移。

## 实际验证与后续边界

最终定向196/11reports、全量483/28reports，失败/错误/跳过均为0；新类14测试方法，
含四类mixed mutation×三个暂停阶段×六种失效的72场景以及原预算/取消/所有权回归。
完整构建、lint、APK与失败修正历史见[2026-10-23验证](verification.md#20261023-totp-mutation)。
宿主合成测试不代替 Android 相机/选图/Activity/AtomicFile 生命周期实测。
下一步为完整 CONFIG/Include/identity/key/cert/credential-target/trust 语义 provider；
生产 validator 注册、SSH恢复、最终连接计划/connector与父任务AC仍开放。

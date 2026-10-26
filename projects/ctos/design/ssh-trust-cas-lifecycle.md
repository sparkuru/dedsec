# SSH trust CAS 生命周期

日期：2026-10-22。原 SSH 客户端任务的当前增量；已实现，定向202/全量JVM469与本地构建、APK审计通过。
范围为 known_hosts 精确 NEW 审批后的 CAS，保留原 public view 的 own-CAS 延续。
不注册完整生产语义 provider，不开放恢复，不操作设备或真实库账号。

## 原审批与预算

生产 provider 在 proposal 交给审批前固定 metadata-only trust context：原 owner、
validator 注册、epoch、role/snapshot 的最早 deadline、精确 proposal 对象与绑定。
不得在 write/admission/effect 新建预算。保留 snapshot、证据、host proof、对象身份、
base/replacement digest、role/hop/target/connection 的既有精确检查与一次消费。
兼容直接入口只创建一次默认 context；不能获得生产 attached-view 延续能力。

## 工作与取消

provider 短锁保留唯一 write attempt，核心工作不持 provider monitor。
关闭先标记失效并摘除引用，再在锁外取消 context/request 和关闭 proposal/snapshot；
admission 尚未签发 request 时也能撤销。worker 独占 proposal 内容副本、source、candidate，
取消不擦除正在使用的 worker 数组。候选包括所有保留 entries/files 的独立数组。
capture/最终复查使用短核心锁，认证、decode、validator loan、候选构造、加密与读回
在核心锁外执行。loan 独立且变更拒绝，所有 Throwable 路径释放本轮资源。
原注册/期限/前台/epoch/取消在各边界持续检查，不新增反向锁序。

## 提交与延续

接受 effect 后一次 consume/remove；错误绑定或 BUSY 不消费。最终核心串行复查
真实 ciphertext 与 quick wrapper，再进入原 Store 事务。事务中仅 bounded metadata
检查与原 effect.beforeCommit 唯一 claim，不读取 AtomicFile、不做 validator/全内容哈希。
proposal 的 immutable replacement digest 可预计算；不可因此放宽实际内容的审批绑定。
保留 vault id/generation/salt/key、新 nonce、wrapper 和读回一致性。

仅原精确 attached public view 可通过 advanceTrustEpoch 延续；必须保留原 view，
不重新 unlock、不替换 view、不恢复已撤销的 loan。成功 completion 仅认可本次自己的
revision/epoch 变化，不能把旧 epoch 检查机械套到成功之后，也不能接纳其他变化。
提交后外部 guard 返回后，在同一 session fence 内最后复查 metadata 并在 session→role→原 view 的 fence 内延续 epoch；
不得在最后 metadata 检查与延续之间再插入可改变注册/取消状态的外部 guard。
兼容无绑定 view 的入口保留原正常 session reset 行为。

上述最终 core continuation 是本次校验授权的完成点：原注册与本次 revision 在此确认。
其后 provider/managed 回执报告已经完成的持久化，并检查 proposal、role/view、期限和
关闭状态是否仍允许交付；不会重新校验候选，也不把完成后单独更换 validator 注册
追溯为本次提交失败。后续 typed secret admission 仍独立绑定当时的注册与请求。

Store 确认成功之后，如 guard、延续或 provider receipt 交付失败，应锁库/撤销，返回
VAULT_COMMIT_UNCERTAIN，不返回 COMMITTED、不声称未写入、不自动重试或回滚。
提交前失败仍按原错误报告。既有 Store 对 guard RuntimeException/Error 的磁盘回滚
限制不由本轮内存 cleanup 测试覆盖；不得扩张这一保证。

## 验证与限制

验证 admission/effect/source/candidate 暂停时取消、provider.close、lock、期限和注册变化；
精确审批/错误绑定/重放/BUSY，loan 变更与所有权，原 view 延续与外部 view 拒绝；
最终读与 beforeCommit 失效、事务中禁止重读、wrapper 保留，以及落盘后失效不确定。
已复跑既有 public/secret/mutation/bundle/TOTP/preview，完成全量 JVM、lint、Release、
fixture 构建与 APK 签名/隔离审计。实测结果见 [本轮验证](verification.md#20261022-ssh-trust-cas)。

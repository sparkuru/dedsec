# SSH bundle 校验与写入生命周期

日期：2026-10-22。继续原 `10-08-ssh-terminal-client`，实施前冻结的合同。
该增量现已实现；实际验证与未测边界见[验证记录](verification.md#20261022-bundle-validation)。
范围是 native-only bundle admission/effect；其他 typed/sessionManifest 和信任 CAS
保持现有行为。完整语义 validator/reviewer 未注册，生产 SSH 恢复门保持关闭。

## 原请求与所有权

- `AppSecrets.BundleContext` 由所属 core 创建，绑定 owner、原 epoch、绝对 deadline、
  原 guard 和不可变 validator registration。它只含控制元数据，不持钥匙或明文。
  显式 caller 在排队/审批前创建并保留 context，可直接取消；兼容 `bundleRequest(proposal)`
  在入口、审批前创建一次默认 120 秒 context。不能等 validator 返回才开始计时。
- context 只准开始一次 admission，失败也不能重试或重置。签发的 `VaultOperation`
  继承同一 context/deadline，effect 继续检查它，不能重新取 120 秒或复活旧注册。
  caller context guard 用于持续取消/期限检查；effect guard 仍拥有实际写入 claim。
- 原 proposal 继续由 caller 拥有。`applyTo` 候选独占所有 Entry.secret/FileObject.content，
  包括未修改对象；关闭 candidate 不得擦除 Verified 或 caller 的数组。
- validator 仅借用从认证 plaintext/canonical candidate 解码的独立临时 loan。
  返回、异常和 Error 均擦除；修改 loan 拒绝，不得修改保留的 Verified/candidate。

## Capture → work → effect

1. 短暂持 core 捕获真实 envelope、session key 副本、context/registration/epoch 和唯一
   bundle 在途槽。拒绝并发或同步回调重入；槽保留到全部 owner cleanup 结束。
   所有复制/解密/解析/validator/候选构造在 core 外进行，不调用旧 `sessionManifest()`。
2. 对实际 source 做原有必要校验，对完整 candidate 始终校验，包括删除最后 SSH 对象。
   每个阶段前后检查原 context、foreground、epoch、deadline、registration、proposal。
3. admission 在最后一次 store.read/unchanged 后再次检查，通过 guard publication 的短
   session metadata fence 签发操作；继承原期限与 context，不能在 fence 中解析或擦除正文。
   publication 返回后在 phase/session 外复查精确审批/原context/proposal；失败移除并取消
   该次 provisional operation，不留下未交付能力。操作还绑定原proposal独立metadata身份。
4. effect 在 capture 时检查精确 operation/proposal binding 并一次消费/remove，再锁外解密、
   当前清单校验、apply、候选校验、encode/encrypt/readback。任一失败都不恢复操作。
   入站错误身份或缺 provider 不签发额外能力；变更注册的旧能力不得跨注册重用。
5. 最终回 core 串行核对 ciphertext 快照，并在最后 store 读取后重查所有边界；
   保留 caller `beforeCommit`、完整 envelope+wrapper 事务、原取消/uncertain 语义和
   `unlockIfEpoch`，不调用会重新执行同步 validator 的 `commitManifest()`。
   parser 不在 core/phase/session 锁内；写入/store 仍沿用既有核心串行事务。

最后 ciphertext/quickMaterial 读取必须在调用 Store.commit **之前**完成；进入
`VaultStore.commit` 后的 `check`/`beforeCommit` 只能检查 metadata/精确审批，禁止
`Store.read`/`quickMaterial`。原因是 [Android AtomicFile.openRead](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/util/AtomicFile.java)
在 startWrite 与 finishWrite 之间可能恢复备份或删除待写文件。不能用递归读存储来
模拟更强的 final CAS。已有 core 串行事务负责写入排他；内存 fixture 必须拒绝事务内重读。

取消线程只改变 metadata，不擦除 worker 正在使用的数组。单次第三方 parser/crypto
不可抢占，返回后不得继续阶段或写入。lock/cancel/registration replacement 可在
validator 暂停时返回；不据此宣称整个 core 的所有路径都已迁移。

context 的直接取消与 capability issuance 使用同一 session metadata 锁排序；
原 guard 的 publication fence 顺序为 phase → session。context 不创建第二个 phase，
也不在 session 内调用外部 guard。写入使用 effect caller 的原 `beforeCommit` claim；
其返回后再检查 context，随后发生的取消属于 claim 之后，沿用 caller phase 的 uncertain
报告与锁库责任。metadata issuance 本身不构成 durable claim。

## 失败与验证

沿用 `VAULT_CANCELLED`、`VAULT_EXPIRED`、`VAULT_CHANGED`、`VAULT_OPERATION`、
`VAULT_CONTENT`、`VAULT_SSH_VALIDATION_REQUIRED`、`VAULT_TRUST_REVIEW`、`BUSY`
及 `VAULT_IO`/`VAULT_COMMIT_UNCERTAIN`。不记录正文、密码摘要或内部 handle。

必须覆盖：admission/effect 两阶段 source/candidate validator 暂停时的取消、lock、
注册替换/同实例重挂及 exact deadline；审批耗尽原期限；跨 core/复用 context；
失败 effect replay；真实 source/candidate 区别、loan 修改/保留/擦除/Error；候选与原
数组独立；最后 store.read 与 beforeCommit 失效；最后 SSH 删除；并发/回调重入；
原 bundle add/replace/delete、无关凭据、wrapper、精确审批与不确定写入回归。

先 focused JVM，再 fresh full JVM/lint/Release 和独立 fixture 编译/签名隔离审计。
main 独占构建；implement/check agent 按分配路径修改/复核。没有 native/Flutter 修改
则不重复 ARM/Flutter runtime；本轮不设备、真实库/账号、提交、归档、push 或 dist。

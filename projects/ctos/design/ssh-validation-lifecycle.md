# SSH 清单语义校验与恢复预览生命周期

日期：2026-10-21。原 `10-08-ssh-terminal-client` task 的接续合同。
本轮实施范围为恢复预览的 capture → inspect/review → publish 生命周期及回归；
完整语义 validator、其他业务入口迁移和生产注册仍待实现。实际结果见
[验证记录](verification.md)。沿用本地未提交、不设备/真实库账号/发布的范围。

## 准入必须证明什么

检查对象是实际传入且已经认证解密的 source、authenticated target、mutation candidate，
不是当前 live vault 的代替品。全清单要覆盖孤立文件、每个非 TOTP 凭据以及删除最后一个
SSH 对象的候选。不得递归进入 AppSecrets/sessionManifest 或构造假 live lease。

| 层次 | 必须验证或保留的义务 | 当前状态 |
| --- | --- | --- |
| 结构 | 对象 ID/type、受管路径、大小、引用存在/闭包/环/深度、认证正文摘要 | VaultManifest 已实现 |
| 实际材料 | 私钥/公钥/证书/全部 known_hosts 行及有界加密策略 | [材料检查](ssh-manifest-inspection.md)已实现；仅 contentOnly |
| 配置 | 所有 CONFIG、静态 Include 的实际来源/类型/引用闭包、所有分支的禁止本地效果政策 | [全CONFIG/静态引用与有限政策](ssh-config-resources.md)已实现；动态/cwd/key-loader/default及最终消费仍待实现 |
| 身份 | 明确的精确密钥/公钥/证书关联和真实数学一致性；精确口令条目，歧义不得挑首项 | [显式所选pair](ssh-key-association.md)已实测；全库持久关系authority/覆盖仍待实现，不从名称/后缀/refs推定 |
| 凭据 | password 的实际目标/用户名、passphrase 的实际私钥、重复/孤立/歧义策略 | [全部private/v1归属](ssh-private-material-ownership.md)已实现并首轮验证；password账号模型、持久graph和完整凭据准入仍待实现 |
| 信任 | 全部 selector/marker/CA/revocation、证书有效期/主体/CA；真实 source/target 差异 | [证书数值/公开事实与嵌入签名政策](ssh-certificate-facts.md)已实现并首轮验证；CA authority/时间/主体/撤销及reviewer仍待实现 |
| 实际连接 | 真实 Request 的 Host/Match/User/动态 Include、逐字段 Options/path/jump/forward/session | evaluator 保留 raw Options；最终计划和 connector 待实现 |

不存在可枚举的“所有可能连接目标”。通配 Host、未知 User、Match 和动态 Include 不得用
假 alias/用户名/passwd 求值为成功。离线检查必须记录未覆盖的上下文义务；只要整库
validator 的必需义务没有证明，就不能返回准入成功或注册生产 provider。具体真实连接
另须绑定其 Request、快照和完整 Options；历史报告、目录 literal row 及 Summary 都不是能力。
这不改变已批准的 OpenSSH 主要行为/Include/ProxyJump 范围，也不引入默许的兼容降级。

已实现材料报告仍恒 `contentOnly=true/admissionComplete=false`。PARSED 不证明数学关联、
传统加密 PEM 解密后 DER 完整消费、证书有效/受信或可登录。未覆盖项不能以空列表代替。
全语义生产准入的完成条件是上表各离线义务实现并通过正反矩阵，真实连接义务有明确的
逐字段强制点；在此之前维持 `VAULT_SSH_VALIDATION_REQUIRED` 等原拒绝门。

## 本轮恢复预览边界

现有 `SshContentValidator.validate(manifest)` 和 reviewer 是同步借用回调。
typed read、bundle 和 commitManifest 的调用者仍持 core monitor，不能只移除
validateBusiness 的 synchronized 就宣称完成迁移。本轮只迁移 `AppSecrets.preview`；
生产 validator/reviewer 仍无注册，原合成交易 fixture 不替换成真实 parser。

1. **Capture**：短暂持 core monitor，检查原 guard/foreground/epoch，捕获当前目标密文、
   validator 的不可变注册身份/修订和 restore provider 注册对象，建立唯一在途预览工作。
   busy/同线程回调重入不得替换在途工作；新尝试不能借旧 requestId 取消其他工作。
   Bridge每次Request须另有不对外发布的唯一preview owner标识，传到core；timeout/页面
   取消只解析自身active Request的owner。对外requestId仍用于原UI/认证/TOTP/export协议，
   已返回的预览用原精确previewId/importCancel关闭，不从另一页面的同名ID推定所有权。
2. **Inspect/review**：核心锁和请求 phase mutex 外验证来源口令与现有目标的新鲜口令，
   校验两份实际输入，构造真实 source/target 审阅并 canonical bind。每个 provider 只借用
   临时 decode loan；返回、异常、Error、取消后均擦除。provider 修改 loan 不能修改
   Verified 的 authenticated plaintext 或 retained manifest，校验 loan 的变更必须拒绝。
3. **Fence**：复制、解密、validator/reviewer/bind 前后检查同一取消源、epoch、foreground
   和原绝对期限。最终 store.read/unchanged 后再次复查；store/reentrant test seam 不能
   越过最后一次读取改变注册/取消状态。用户取消不依赖单 worker 队列排空才能被观察。
4. **Publish**：再次核对目标密文、原注册对象、工作身份、epoch、foreground 和原期限。
   仅用短临界区把结果置为当前 preview；与取消/直接 session.lock 规定先后顺序。
   请求 phase mutex 内仅允许 bounded metadata/epoch 检查和赋值，禁止 parser/KDF、
   canonical bind、store.read、旧 preview 的正文擦除、外部 UI 调用或任意 provider 工作。
5. **UI delivery**：以 phase → session 短临界区的一次 metadata delivery claim 为发布
   顺序点。claim 前已经取消/过期/锁定/失去注册或失去当前 preview 身份时拒绝；claim
   成功只设置一次交付标记，外部 result.success/cleanup 在锁外执行。claim 后才发生的
   失效可以与已获准的不可变摘要交付并行，仍须拒绝后续 commit；不承诺撤回已经取得
   交付权的摘要。main 不重新读取 store/解析文件，不在 phase/session 锁内调用外部UI。
   拒绝后安排 owner 清理对应 preview，不得清掉更新的其他 preview。
6. **Commit**：保留真实来源密文/明文绑定、精确 restore review/consent/provider revision、
   当前目标及原 guarded storage 检查；增加 validator 注册身份复查。即使重挂同一
   validator 实例也产生新注册、失效旧 SSH preview；旧校验成功不得跨注册使用。

短 publication fence 必须与直接 `session.lock()` 和请求取消排序，但不承诺抢占单次
第三方 KDF/parser。cancel/lock 在 parser 暂停时能够改变失效状态并返回；parser 返回后
不能发布或提交。所有 plaintext/loan 的释放由原 worker 在 finally 完成，取消线程不擦除
仍被 parser 使用的数组。不得启动第二个不受控 worker 来掩盖锁或线程安全问题。

## 期限、注册与失败

- Bridge 已有 120 秒 Request.deadline 原样传到 preview admission；SAF/排队/KDF/两个
  validator/reviewer 共用原期限。caller 未显式提供的 legacy overload 在入口取一次
  有界期限，不在逐阶段重置。材料 inspector 的 60 秒预算另取两者较早者，不能增加原期限。
- 成功 preview 供用户另一次 importCommit 使用的既有 120 秒 review/consent 窗口保持；
  它在最终 publication 时一次赋值，在最后 store.read 之后起算，是与原 admission 请求
  分开的阶段，不能反过来延长 admission。准备 candidate 的时间不能提前消耗此窗口。
- validator attach/re-attach/revoke 产生新的不可变 registration。旧 SSH preview 和在途
  工作失效；restore provider 原 id/version/revision/approval 检查继续有效。
- validator/reviewer 失败、丢失、partial coverage、变更 loan、注册变更、来源/目标变化、
  cancel、exact deadline 和 Error 均不能发布成功或修改存储。清理不吞掉原始错误。
- 保留现有 `VAULT_CONTENT`、`VAULT_CHANGED`、`VAULT_CANCELLED`、`VAULT_EXPIRED`/
  Bridge `VAULT_TIMED_OUT`、`VAULT_SSH_VALIDATION_REQUIRED`、`VAULT_SSH_REVIEW_REQUIRED`、
  `VAULT_TRUST_REVIEW`、`BUSY` 分类。诊断无原文、秘密、口令/密码摘要或 native handle。
- 纯 TOTP source/target 仍不需要 SSH provider；target 有 SSH 时不能借纯 TOTP source
  绕过校验和完整替换审阅。unlock/quickUnlock 本身仍不宣称 SSH 语义验收。

## 可验证的反例与完成条件

| 反例 | 本轮必须观察到的结果 |
| --- | --- |
| validator/reviewer 被 latch 暂停，另线程 lock/cancel/重挂 provider | 操作能返回；恢复 worker 后没有 preview/commit 成功，旧密文不变 |
| source B 与当前 target A 不同，validator 保留/修改其 body loan | 各自实际字节分别校验；返回后 loan 擦除；修改不能批准未校验的原文 |
| reviewer 保留数组、抛异常或 Error | source/target loan 擦除；旧 preview/存储按所属关系保留；唯一在途槽释放 |
| 第一个 validator 已耗尽 admission 期限 | 不再调用目标 validator/reviewer、不重取期限；不发布 |
| 注册相同实例、撤销/换回 validator，或最后 store.read 期间变更 | 旧注册不复活；publish/commit/final guard 拒绝 |
| main callback 的交付claim前 cancel/lock/过期/新 preview 替换 | 不交付旧成功摘要，不清除新的 preview |
| 交付claim后才发生 lock/cancel/注册变更 | 已claimed摘要可在锁外交付一次；commit拒绝，不恢复注册/库会话 |
| background期限已到并附着blocking revocation | publication拒绝，不在phase mutex内触发assertEpoch的lazy expiry/revoke/native清理 |
| 最后store.read推进clock但未超过admission期限 | review/consent独立120秒窗口从最终publication起算 |
| 库以locked状态开始管理导入 | 仍可用来源/目标新鲜口令完成预览，不要求先解锁或分配role |
| 新预览认证/校验/review失败 | 不清除无关旧preview；明确lock/注册变更的失效除外 |
| 原精确 restore binding/拒绝信任/目标口令失败/失败写入 | 仍拒绝且保留原密文；不得弱化现有 fixture 的交易断言 |
| 第二个并发或回调重入 preview | 有界 BUSY/拒绝，不递归解析、不覆盖首个工作 |
| 缺生产 validator、只拿到材料 PARSED/catalog | SSH 预览及恢复门仍拒绝；不签发 role/socket/凭据权限 |

本轮先 focused 并发/ownership/注册/期限测试，再跑完整 JVM、Android lint 和适当的
APK 编译；记录实际环境、失败和未测边界。无 native/Flutter 变更不把旧 ARM/设备结果
写成本轮通过。最终检查还须核对所有旧 typed/bundle caller 仍被明确标为未迁移。

## 后续实施顺序

1. 本轮 preview capture/work/publish 和注册复查通过后，逐入口迁移 typed reads、bundle
   admission/effect、trust CAS 与普通 mutation。每个入口先捕获真实原期限、操作消耗点和
   独占拥有的输入，再在锁外解析，最后 guarded effect；不能沿用尚未签发的 operation。
2. 提供不占 live-view singleton 的脱离会话 CONFIG 输入/managed Include 图，复用固定
   readconf/parser；补齐全材料、私公钥/证书关联、凭据/信任义务和来源定位反例。
3. 仅在完整 validator/reviewer 及全部调用链通过后注册生产 hook、开放 SSH 导入/恢复；
   同步验收混合库 TOTP、删除最后 SSH 对象、取消和 own-NEW-CAS loan continuation。
4. 再以真实 Request 建立不可变完整计划与 connector，随后 jump/forward/service/产品 UI。
   父 task 完整 AC 与设备/真实账号验收独立维护，不由本轮生命周期修正推定完成。

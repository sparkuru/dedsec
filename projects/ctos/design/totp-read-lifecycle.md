# TOTP typed 读取生命周期

日期：2026-10-22。原 SSH task 的本轮实施合同，仅迁移 TOTP_CODE/TOTP_LIST
及 Bridge 列表/复制的原请求绑定。mutation、TotpPreviews、SSH 凭据/私钥/trust CAS
继续旧路径，不注册生产 SSH 语义 provider，不开放恢复。

已实现并通过定向140/fresh全量JVM410、normal92全重跑/fixture114及三APK审计；
[实际结果与限制](verification.md#20261022-totp-read-validation)。完整SSH task仍进行中。

## 原请求与输入

- core 创建 native-only read context：owner、原 epoch、绝对 deadline、原 guard、
  不可变 validator registration、直接取消位和一次 admission。创建只处理 metadata，
  不获取 core 锁、不读库或复制 key；Bridge 在排队前传入原 Request.epoch/deadline/phase。
  不另建 phase。默认 native overload 在自身入口创建一次120秒context，不能重取预算。
- context 的 guard.check 是有界的 owner/取消/foreground/deadline metadata 检查，
  不调用解析/存储/系统UI。Bridge 使用单独 read guard，publish **只获取原 phase**；
  不复用会先持 session 的 requestGuard.publish，再去获取 publicationLock。
- 实际认证清单含任意SSH文件或非TOTP凭据时，才要求并绑定原validator registration；
  同实例重挂也失效。纯TOTP始终不依赖SSH provider，不能因provider挂载/撤销而失败。
- 独占捕获的密文/key/Verified/plaintext由worker拥有；mixed validator只借用独立decode
  loan，返回、异常、Error全路径擦除，修改loan拒绝。不能把mutable Verified.manifest
  直接交给validator，也不能用当前live view或假bundle operation代替原输入。

## Capture、读取与能力

1. 短core capture检查原context及purpose/resource，预留一个在途read槽，复制真实
   密文/key/contentRevision。槽保留到全部cleanup完；并发/同线程read重入拒绝BUSY。
2. parse/decrypt/decode、mixed语义校验及TOTP生成均在core/phase/publication/session外。
   每个阶段前后复查原取消/epoch/foreground/deadline，发现mixed后立即复查原registration，
   不能改用新provider。默认pure路径不调用provider。
3. admission最后回core核对实际ciphertext及revision，store seam后再复查；原guard.publish
   以 phase → publicationLock → session 短metadata fence签发操作。原epoch/deadline、
   purpose/resource、快照及mixed标志/registration原样保留，不重取期限。
   publication返回后的失败要移除并取消精确provisional操作，不影响别的请求。
4. effect匹配真实registry中的同一operation及purpose/resource/context；错误用途/资源不消费。
   匹配且被接受的effect在锁外工作前一次consume/remove，任何失败不恢复。重放仍按
   VAULT_OPERATION（取消优先）拒绝，不能被过时context错误码覆盖。
5. 最后worker快照读取在短publication fence之前；返回后再复查metadata。输出只有既有
   entryId/identity/label/code/unixSeconds，不返回seed/文件/数组。opaque read receipt
   绑定同一operation/context、revision、mixed事实与成功read状态，不能由自由scalar重建。
   原totpPublication对TOTP_SAVE管理预览的兼容路径保留，不混为已消费的read receipt。

## 最终列表与剪贴板交付

原有TOTP最终效果按实际披露时点检查；不把preview允许“claim后失效仍交付摘要”的
语义套用于验证码。本轮保持既有列表代码/剪贴板与session.lock的披露顺序。

- 原read guard.publish（phase-only）→publicationLock→session只做一次receipt reservation。
  拒绝未完成read、旧revision/epoch、过期、取消、mixed注册失效、重复reservation；
  foreground先于任何可能lazy revoke的session getter。这里没有store/crypto/cleanup/
  validator/ClipboardManager/Result回调。
- phase释放后，实际列表Result.success或clipboard.copyCode在既有publicationLock→session
  效果fence内再次检查原permission/context、revision/epoch/foreground/deadline、mixed
  registration和native生成的当前30秒窗口。reservation后、实际效果前的失效仍拒绝。
  effect一次尝试，异常不重试；phase/core不持有。剪贴板只调用既有write-only敏感复制，
  copied:true回执和所有cleanup在锁外。列表result只有受限transport发送，不能回调core。
- 列表result仍留在既有pub/session效果锁内以保留实际验证码披露顺序；本轮不声称消除
  任意Result重入造成的锁环。生产假设为Flutter Result有界、transport-only、不重入core；
  cleanup明确移出效果锁，禁止用户/provider/解析回调进入该锁。任意第三方Result重入
  与core→publication提交可成环，须另设计才能放开此假设，不能宣称已经解决。
- 实际Clipboard Binder或transport可能持session到调用返回，延迟direct lock/context.cancel；
  原phase.cancel不被效果调用占用。仅承诺validator暂停期间取消/lock/重挂的响应性，
  不承诺抢占单次parser/Binder/transport。最后检查后开始的外部效果不能撤回。
- Bridge wall clock用于生成并记录seconds/30，最终效果前再比当前窗口，变化返回
  TOTP_TIME_CHANGED，不在最终锁内重新生成；核心显式unixSeconds仍支持RFC历史向量。
  单调请求deadline与wall clock独立，负数输入即使空列表也拒绝。
- Request.cancel直接取消私有read context；cleanup取消它的read operation，迟到worker
  不能留下孤立能力、误取消同名其他Bridge请求或重复完成Result。read_命名空间不改。

## 验证与边界

必须覆盖list/code admission/effect paused mixed validator下取消、lock、foreground、期限、
same/new注册、操作cancel；pure无provider及provider churn；loan修改/关闭/保留擦除/
checked/runtime/Error；原排队预算、错误owner/purpose/resource、失败重放、BUSY/reentry；
worker末次store和publication seam失效、provisional清理、receipt只能一次；最终交付无
store/crypto/core/phase、reservation后失效仍阻止实际效果、29/30及前后wall跳变；
background expiry + blocking revocation不进入phase，既有TOTP_SAVE preview和SSH路径回归。

先focused JVM，再fresh full JVM/lint/Release及独立fixture编译/签名隔离审计。
仅本地未提交，不设备/真实库账号/Clipboard实际操作/commit/archive/push/dist。
JVM effect stub不等于真实Bridge/Binder/Android生命周期运行；全客户端AC继续开放。

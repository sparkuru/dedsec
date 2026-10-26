# SSH 凭据保存与删除生命周期

日期：2026-10-22。原SSH客户端任务的窄增量；已实现，定向152/全量JVM455及
本地构建通过。[实际证据与限制](verification.md#20261022-ssh-entry-mutation)。
范围仅SSH_SAVE/SSH_DELETE及SshVaultAdapter入口。TOTP mutation、trust CAS、bundle、
secret/public reads保持既有独立合同，不注册生产语义provider、不开放恢复。

## 原请求与能力边界

metadata-only mutation context在排队/host审批前固定owner、epoch、原deadline、
guard与不可变validator注册，支持直接cancel及一次admission。兼容入口一次默认120秒，
显式context继承调用方原预算；effect不能续期。保存操作绑定id/target/connection，
删除绑定id/target；host gate的原要求贯穿保存admission及最终effect。
保存的secret/label/type在effect才提供，因此admission不是对这些字节的精确审批，
不能伪造proposal/receipt或把既有host gate称作完整SSH语义准入。

## capture、候选与校验

短core/session fence捕获真实cipher/key/revision；单mutation槽至全部cleanup完成。
认证/decode、实际source校验、候选构造/校验、encrypt/readback均在核心锁外。
source含SSH时按原注册校验，纯TOTP source不需要假SSH对象；最终完整candidate始终
通过原SSH validator，包括新增第一个SSH凭据、删除最后一个SSH条目。
候选独占所有secret/file数组，未改对象也不得与Verified/caller别名共享。
validator只借独立canonical loan，变更拒绝、Throwable擦除；不能替换source/candidate。
入参secret及时复制由本轮拥有，结束后擦除副本，不改调用方数组。

错误owner/purpose/id/target/connection及有效effect的BUSY不消费；接受effect后一次
consume/remove，失败不复活。签发继承原context并登记精确issued，hook后失败撤销。
使用既有publication锁内64容量检查/登记，不新增反向session→publication锁序。

## 最终事务

最终回core串行检查原context/注册/revision/epoch/期限/guard，读取quickMaterial及
最后ciphertext equality，最后读后再次检查。Store.commit内仅metadata/host检查，
禁止read/quickMaterial/解密/validator重入；保持既有AtomicFile完整envelope+wrapper
事务与原effect guard.beforeCommit claim，不把admission.publish当作durable claim。
context guard仅负责持续检查与admission publication；实际effect guard.beforeCommit
是唯一写入claim，只调用一次。即使两者同一实例也不能重复claim，不建立第二个phase。
保持原vault id/generation/salt/key与新nonce、读回一致性、wrapper保留，以及成功清理
旧operation/preview和unlockIfEpoch失效规则；不调用旧commitManifest重新触发validator。
取消在claim前阻止写入，claim后沿用既有caller phase与Store的uncertain规则；不能承诺
已提交字节回滚或给after-claim取消创造新的成功语义。异常路径清理本轮owned数组；
Store自身的AtomicFile落盘与既有异常实现不由JVM内存测试代替。
既有VaultStore仅对IOException/VaultException调用failWrite；guard在startWrite后抛
RuntimeException/Error的回滚不在本轮保证内。此处保持Store实现，不能以候选/loan的
Throwable擦除测试推定所有磁盘事务Throwable均已回滚。

## 验证与限制

覆盖save/delete admission/effect/source/candidate暂停校验下取消、lock、foreground、
原deadline、同实例/新注册、store/revision变化；所有权与loan变更/保留/Error、
BUSY/重入/错误绑定/重放；first/last SSH、无关TOTP/files保存、cross-type/target拒绝；
最后store读取与beforeCommit失效、事务内重读拒绝、wrapper完整保留和uncertain锁库。
复跑typed/public/own-CAS/bundle/TOTP/preview，再fresh全量JVM、lint/Release/fixture与
APK审计。只有本地实现与检查，无设备/真实库账号/native运行/提交/归档/发布。

# SSH typed秘密读取生命周期

日期：2026-10-22。现有SSH客户端任务的接续实施合同；已实现，定向196/全量JVM444及
本地构建通过。[实际证据与平台限制](verification.md#20261022-ssh-secret-read)。

## 范围与原请求

迁移SSH_CREDENTIAL、SSH_PRIVATE_KEY、SSH_PRIVATE_KEY_BUNDLE admission与effect，
及SshCredentialBridge/ManagedProvider的实际selected调用链。保留既有public read、
own-NEW-CAS、TOTP、bundle/preview合同；普通mutation/trust CAS、完整语义provider、
恢复和connector另行推进。不操作设备/真实库账号，不提交/发布。

耗时工作前建立metadata-only SshSecretReadContext，固定owner、epoch、原绝对deadline、
不可变validator注册及直接取消。生产role路径必须沿用原role.deadline与同一public view；
selected路径将已有selection、host/KEX与callback guard贯穿admission及最终释放。
兼容typed入口可生成一次默认预算，但不得在effect续期；无KEX的旧open入口不授予释放权限。

## capture、校验与一次消费

短core/session fence捕获真实cipher/key/revision，单秘密读槽保持至cleanup完成；
认证解密、decode、独立canonical loan校验、精确条目/文件选择与bundle编码在锁外。
loan修改拒绝，所有Throwable路径清理，不能以public或bundle假operation替代真实来源。
错误owner/purpose/resource/target/connection不消费；有效effect遇BUSY不消费；接受后
一次consume/remove，失败不能重放。context admission一次，签发后hook失败撤销精确操作。
各阶段复查原期限/epoch/foreground/注册/revision/取消，最后raw store比较在短core内，
随后释放core，publication fence再检查revision防止间隙提交。

## 最终秘密交付

秘密不能沿用public loan的claim后锁外receiver。原guard.publish必须保留；生产selected
adapter以stateLock排序callback取消/证明变化与最后受管复制。Bridge保留request、selection、
role、host、KEX、operation精确绑定；不能只用一次check丢弃原publish fence。

最终同步接收限定为有界可信复制，不得重入core、执行native操作或阻塞等待其他线程。
在原callback/request fence下，publication→session→operation→role→实际public view
排序，末次检查后完成复制；不持core执行外部guard/publication或receiver。取消/锁库/
operation.cancel/request.close/role.close与生产view.revoke通过对应锁排序。
角色与view helper必须保持既有锁序；任何新增锁环须先修正设计再实现。
自定义测试view的撤销也须遵守该monitor合同，不能以volatile假view推定原生生命周期。

独立锁序检查发现既有snapshotFor的guard.publish持role再取publication/session，
与本轮顺序相反。因此增加内部SshRoleGuard，分开外层生命周期publish与内层withRole。
secret及role-bound public最终签发/claim在publication/session（已有operation时再取
operation）后才调用withRole；snapshotFor同步迁移此窄边界，保留原role/view/deadline、
原guard.publish及own-CAS语义。native attach的operation锁也不能放到role内获取。
这项支持变更必须纳入public/known_hosts回归，不改变public receiver锁外loan合同。

public native attach仅在attachRevocation时短暂取得operation锁，再进入role fence；
不得持operation跨lease.active/session attach，否则取消会等待最终claim。secret复制
才持有完整operation fence。所有五个operation签发点统一在publicationLock下做64项
容量复查与插入；bundle发布也统一publication→session，不能session→publication。

cleanup和secret wipe在最终交付锁外，由拥有数组的worker执行，不能在取消线程并发擦除。
receiver抛错、原guard后钩子失败或返回前失效时不成功返回旧结果，生产owned clone必须擦除。
已经完成的受管复制不可追溯撤回；后续adapter finish、JNI/native当前证明与scratch擦除
继续有效。本轮不把受管复制证明夸大为完整Android/native实际披露原子性。

## 验证要求

覆盖admission/effect暂停validator时取消、锁库、foreground、期限、同实例重注册、
revision/raw store变化；原role预算与view关闭、wrong selection/host/KEX、重入/BUSY/
错误绑定/重放、loan变更及checked/runtime/Error清理。覆盖末次store与publish间隙、
真实receiver前失效和复制与lock/cancel排序，验证receiver不持core且原publish未丢失。
复跑现有Bridge/selected callback/known_hosts own-CAS/public/TOTP/bundle/preview，
再fresh全量JVM、lint/Release及隔离fixture编译与APK审计。测试通过前不填实测结论；
Android运行、真实账号和父任务完整AC保持开放。

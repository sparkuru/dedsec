# SSH实际清单的离线文件内容检查

2026-10-24：[全部证书事实与签名政策](ssh-certificate-facts.md)已实现CSCF168、三份公开摘要/unsigned数值/实际record来源及现代CA签名政策，保留trust/time/principal/revocation未验证。ARM22239与旧材料矩阵、定向145/全量638 JVM、normal100全重跑/fixture111及三APK审计通过；[实测](verification.md#20261024-certificate-facts)。持久graph/账号、完整信任/reviewer/provider/恢复/connector与父AC仍开放；本地未提交，无设备/真实库账号。

2026-10-23：[全部私钥材料与凭据归属](ssh-private-material-ownership.md)已实现全PRIVATE自签/验签、v1口令唯一owner/phrase审计和password namespace/deferred target。ARM19976/独立pair13812/material7784、定向120/全量613 JVM、normal98全重跑/fixture111及三APK审计通过；[实测](verification.md#20261023-private-material)。仍不提供持久关系图/真实密码账号/完整信任/provider/恢复/connector准入，父AC开放；本地未提交，无设备/真实库账号。

2026-10-23：[显式密钥配对证明](ssh-key-association.md)已实现精确ID/所选口令、证书plain subject及固定domain实签验签，拒绝仅公开字段相等的伪配对。ARM13814/独立材料7784/fresh认证1748333、定向80/全量586 JVM、normal96全重跑/fixture111与三APK审计通过；[验证](verification.md#20261023-key-association)。报告只覆盖明确所选pairs；全库关联authority/覆盖、凭据目标、完整信任/reviewer/provider/恢复/connector与父AC仍开放。本地未提交，无设备/真实库账号。

日期2026-10-19；原SSH客户端task继续。本轮已实现；本地验证结果见[验证记录](verification.md#20261019-manifest-inspection)。
前置为[有效配置求值](ssh-config-managed.md)、[声明目录](ssh-config-catalog.md)和
[启动器](ssh-launcher.md)。仅本地未提交，不设备/真实账号/归档/发布。

## 目标与权限

检查调用者传入的实际VaultManifest文件内容，不读取当前AppSecrets/session/lease，不
伪造VaultOperation、vault UUID/epoch/connection tuple，不创建public view或认证role。
此接口能用于未来source/target/candidate的检查链，不能实现或注册SshContentValidator。
配置全上下文语义/Include引用绑定、私钥公钥身份关联、凭据目标、证书有效期/主体与信任
审阅均未完成；报告恒contentOnly=true、admissionComplete=false。SSH恢复门继续关闭。

AppSecrets当前validateBusiness/sessionManifest/preview/bundle同步调用validator且持core
monitor，没有独立deadline/cancel参数。将本接口接入该hook前仍须设计取消与锁边界；
不能在hook递归打开current vault，也不能把声明目录或PARSED文件当作全清单准入。

## Java实际清单检查器

新增package-private `SshManifestInspector`，同步入口
`inspect(VaultManifest manifest, long deadline, Cancellation cancellation)`，另有可注入
Clock/NativeBinding的宿主测试seam。deadline为原始elapsedRealtime/CLOCK_BOOTTIME毫秒，
入场必须未来且最多60秒；整个检查沿用该期限，逐文件不重置。Cancellation为单向atomic
flag，可跨线程cancel；其余工作同步由调用线程完成，ThreadLocal拒绝同/异对象重入。

调用者提供独占的同步manifest loan，不得并发修改或close。检查器先独立拥有所有文件
body副本和相关SSH_KEY_PASSPHRASE副本，再检查每个文件；不关闭/修改caller manifest，
不读取或复制无关TOTP/password秘密。metadata沿manifest原始次序，保留type/id/path/identity。
每个副本必须在复制前有cleanup owner；成功/失败/IOException/LinkageError/Error/取消均
finally擦除。NativeBinding仅借用本次副本，不得保留。

- PRIVATE_KEY按精确entry.identity == "key:" + file.identity取关联口令。多个匹配条目报告
  AMBIGUOUS_PASSPHRASE，不挑第一个；无条目以空口令参数传native，由内容判断是否需要。
- PUBLIC_KEY、CERTIFICATE、KNOWN_HOSTS传对应实际body，不附任何口令。
- CONFIG标记CONFIG_DEFERRED，本轮不重复建Java lexer或虚构上下文。每个文件有明确覆盖
  结果；非TOTP凭据的目标语义仍pending，不把已传口令解释为认证有效或完整关联证明。
- 返回不可变Report/Entry列表，仅metadata、解析结果与public fingerprint摘要，不含body、
  口令、完整公钥blob、native pointer、descriptor或新能力。历史报告不作为live授权。
- cancellation/deadline在复制前后、每次native调用前后和最终publication复查。单次native
  key/KDF解析不可抢占，最多既有64 bcrypt rounds；cancel阻止后续工作/报告发布，不承诺
  立即中断第三方crypto调用。deadline失败也不能返回部分成功报告。

## Native内容检查

新增 `ssh_material_inspect.[ch]` 与薄JNI，单次直接借用bounded bytes；无session/global
registry/view/socket/role/provider。C入口接type、body slice、passphrase slice、原deadline和
result output。系统/参数/期限错误返回-1/errno并清output；材料解析结果用status表示。
body最大1MiB；口令0..8192 bytes且无NUL，只对PRIVATE_KEY允许；明文文本拒内嵌NUL，
单行最多65536 bytes、累计16384行，超限失败不截断。native前后查同一CLOCK_BOOTTIME期限。
OpenSSL显式NO_LOAD_CONFIG初始化，不加载环境配置。无宿主文件、环境、DNS、命令或网络。

- 提取现有native client的software_identity/encrypted_key_policy为共享
  `ssh_key_policy.[ch]`；保留原认证行为、限额和错误，不复制私钥策略到新文件。
- PRIVATE_KEY先只读envelope/header识别加密，不运行私钥解析或KDF；已识别加密输入先用
  共享受限策略拒绝任意KDF/PKCS8，无口令则返回NEEDS，不解析payload。有口令且策略
  通过后才用固定sshkey_parse_private_fileblob解密；未加密材料直接用该parser。
  支持既有Ed25519/EC/RSA软件键，
  既有OpenSSH aes256-ctr/bcrypt盐1..64/rounds1..64及传统RSA/EC AES PEM；无agent/provider。
  解析前做完整单envelope边界检查，拒绝第二个密钥/非空尾随，不让底层只读首个对象。
  未加密PEM另查decoded top-level ASN.1 SEQUENCE长度完整占用，实际字段仍由固定parser
  解析；传统加密PEM不另写解密器，其解密后DER完整消费未获证明。PARSED不保证所有
  加密载荷后缀均被拒绝，不能把armor边界检查扩大为完整语义认证。
  不正规化私钥换行：固定OpenSSH armor要求其原LF标记，CRLF OpenSSH仍拒绝；传统PEM
  的换行兼容由固定parser决定，不能因上层envelope可辨识便承诺所有格式支持CRLF。
  PARSED仅说明可解析及软件键策略，不证明私钥与其嵌入/外部公钥数学一致；不签名。
- PUBLIC_KEY/CERTIFICATE复用sshkey_read/固定blob parser：完整文件仅一条key record，允许
  空白和注释行及标准record尾注释；第二条key record拒绝。两种type必须与实际cert标记
  一致。返回public wire blob SHA256，不返回密钥。证书解析不判断当前有效期/主体/信任。
- KNOWN_HOSTS复用hostkeys_foreach_file，host=NULL、HKF_WANT_PARSE_KEY和固定诊断source
  label；逐条拒HKF_STATUS_INVALID，允许空/注释文件，记录有效条数。补查上游null-host
  路径未验证的hashed host token：仅|1|、盐与digest均严格Base64解码为20bytes且无尾随。
  不调用host_hash或提供假host，不做匹配/信任分类。普通host pattern仍只为解析字段，
  不是有效主机目标/规则可达性证明。@cert-authority/@revoked只表示可解析marker。
- 原hostfile空host路径的日志仅可含固定source/line，不含原body/host/key；不得临时修改
  全局logger。输入fatal不得新增；原上游xalloc系统OOM限制如实保留。临时private/口令、
  decoded buffers和sshkey均擦除/释放，不把秘密写tmp文件。

## CSMI wire与JNI

新增一个 `SshManifestInspector.Native.inspect(int type, byte[] body, byte[] passphrase,
long deadline):byte[]`，类型沿用native文件tag2..5。固定60-byte big-endian CSMIv1：
magic0x43534d49、version1、status、keyKind、flags、records、line，再32-byte publicBlobDigest。
status：0 PARSED、1 MALFORMED、2 UNSUPPORTED、3 NEEDS_PASSPHRASE、4 BAD_PASSPHRASE。
BAD_PASSPHRASE仅是上游错误类别，也可能来自密文损坏，不证明用户输入了错误口令。
keyKind：0无、1Ed25519、2EC256、3EC384、4EC521、5RSA。
flags只允许1 encrypted、2 certificate、4 contentOnly；4恒置。PARSED key的records=1、
kind1..5和非零digest；PARSED KNOWN_HOSTS的kind/digest=0、records0..16384。
非PARSED的kind/records/digest=0，line为有定位的首失败行或0；口令结果仅PRIVATE_KEY且
置encrypted。certificate flag仅PARSED CERTIFICATE。其他标记组合不得猜测或接受。
Java独立验证全部长度/magic/version/枚举/类型/flags/计数/来源/摘要零值/尾随。

JNI独立检查数组和期限，使用native owned scratch及长度，不pin Java秘密；分配output
array后再执行，array write后再查期限；pending exception保留，不调用后续正常JNI API。
所有失败wipe/free scratch，返回报告不含secret。新entry不触碰既有八config/19session JNI。

## 验证与后续

真实native正反矩阵：所有软件键、加密正确/错误/缺失口令、超预算KDF、尾随第二key、
cert type mismatch、public comments、多record、known_hosts所有行/markers/空文件/坏hash
salt/digest及非匹配行坏key、NUL/行/文件/口令界限、期限与清理。effects observer确认无
宿主文件/环境/网络/命令；固定source日志单列，不把它当零日志结论。原认证完整ARM回归
必须重跑以核对共享策略提取；原config/catalog/handle源保持并跑相应回归。
Java独立验证实际candidate而非current store、source loan不变/深拷贝/全部副本擦除、关联
口令歧义、codec非法帧、所有文件覆盖、cancel/deadline/Error/重入和不可变报告。
strict、fresh JVM/lint/normal/fixture、APK导出隔离签名分别记录；可编译独立Android精确
fixture但本轮不安装执行。source/工件固定后正式checker、spec/design/task/handoff同步。
后续仍为配置语义/引用/身份/信任全图合同，再接完整validator/restore与最终计划/connector。

# 全部私钥材料与凭据归属审计

状态：已实现并完成本地验证。[实测与限制](verification.md#20261023-private-material)：ARM19976/effects0、全量JVM613及normal/fixture/APK审计通过。

日期2026-10-23。接续[显式配对](ssh-key-association.md)；本轮保持VaultManifest v1原字节格式、
所有mutation/恢复/认证政策和生产provider关闭。补全部PRIVATE_KEY的实际签名能力及v1
已有`key:`口令元数据的唯一归属，不引入持久关系图或猜测password账号语义。

## 覆盖与归属

新增一次尝试、创建线程同步 `SshManifestPrivateMaterialInspector.inspect(manifest,
originalDeadline,cancellation)`。只借用caller独占实际清单，不读取current vault/lease/role，
不修改/关闭caller。扫描所有PRIVATE_KEY与所有非TOTP entry，保留原0-based ordinal/ID，
不把未处理对象省略为成功。纯TOTP/空清单无JNI，但仍执行最终期限/取消fence。

归属仅解释v1既有显式`key:`+private.identity关系：

- PRIVATE_KEY identity重复本身不推断数学相同；若没有口令条目，两份仍各自检查签名能力。
- 每个SSH_KEY_PASSPHRASE必须使用key: namespace。候选private owner为identity完全相等者。
  零个为ORPHAN；多个为AMBIGUOUS_PRIVATE；唯一owner但该owner匹配多个phrase为
  AMBIGUOUS_PASSPHRASE；只有private owner和phrase都唯一时为OWNER_RESOLVED。
- phrase namespace错误为INVALID_NAMESPACE，即使手工构造清单绕开v1结构检查也不得猜配。
  同一identity的多phrase不挑首项、不尝试多个，不为此复制任何歧义secret。
- 每个private没有匹配phrase时以null验证；唯一private+唯一phrase才选精确entry ID。
  若其phrase关联歧义，则private记OWNER_AMBIGUOUS，不JNI、不复制该private/phrase。
  多个private共享identity但无phrase不属此歧义，native仍逐个验证实际不同正文。
- 每个SSH_PASSWORD只检查target: namespace；正确namespace记TARGET_DEFERRED，其他记
  INVALID_NAMESPACE。target后缀是v1不透明字符串，不解析成host/port/username，不复制password。
  TOTP不进入报告、不复制其secret；PUBLIC/CERT/CONFIG/KNOWN正文也不复制。

归属报告只证明v1元数据解析的唯一性，不证明口令全球唯一含义、持久关联authority、
private/public/certificate配对或password实际账号正确。OWNER_RESOLVED不等于口令可解密；
对应private验证结果另行记录NEEDS/BAD/UNSUPPORTED/MATCH等，不输出秘密摘要。

## Native全部私钥自检

在既有material模块新增同步borrowed-slice `ctos_ssh_material_verify_private(body,length,
phrase,phraseLength,originalDeadline,output60)`。复用与pair同一strict private parser、shared
software policy、RSA所有字段<=16384bit/no multiprime与签名检查辅助，禁止复制另一套parser。
parse返回的private公开字段复制为plain subject，以固定domain（含NUL）
`ctOS SSH private material proof v1`加该subject SHA256签名，再检查sigtype并以该subject
验签；RSA固定rsa-sha2-256。自检不要求caller提供公钥，也不生成/返回可复用签名能力。
secret seed与容器公开字段不一致必须MISMATCH/SIGNER，不能仅PARSED就称有效。
ALLOC/SYSTEM/LIBCRYPTO沿原pair固定返回-1/ENOMEM或EIO并使输出全零，不能吞为MISMATCH；
单纯材料签名错误才为MISMATCH/SIGNER。期限/系统错误使整个job失败，不发布前缀结果。

新CSKPv1恰60字节：big-endian七u32
`magic0x43534b50,version1,status,keyKind,flags,stage,materialType2`，再32-byte plain public
subject SHA256。status沿CSKA0MATCH/1MISMATCH/2MALFORMED/3UNSUPPORTED/4NEEDS/5BAD；
stage只COMPLETE0/PRIVATE1/SIGNER4。MATCH只0，MISMATCH只4，其他只1。
flags仅ENCRYPTED1/PRIVATE_ONLY4/DERIVED_SUBJECT8/SIGNER_VERIFIED16，4必须；8和16只MATCH
整体成功时同时出现，非MATCH kind/digest/proofbits全零，MATCH kind1..5/nonzero digest。
NEEDS/BAD必须encrypted。Java严格闭包，不得把CSKP和原CSKA/CSMI互解。

新Native.verifyPrivate一个JNI；原material1/pair1/config15/session19不变，旧pair wire/
固定challenge/状态/政策不升级。JNI有界heap副本、预分配输出、异常保留与finally擦除，
无registry/handle/mapping，不声称所有OpenSSL堆锁页。传统加密PEM在parser/KDF前UNSUPPORTED，
清晰键额外phrase同样UNSUPPORTED；支持的加密OpenSSH沿既有64bcrypt rounds政策。
单次parser/KDF/crypto不可抢占，RSA postparse上限不消除PEM内部先行成本。

## Java ownership、期限与报告

创建Loan所有cleanup slots后才复制。每body<=1MiB，全manifest body<=8MiB、files<=128、
entries<=1024；只复制可确定owner的private和所选唯一phrase，每份一次。所选phrase1..8192/
无NUL，异常输入固定IOException。先完成所有归属/边界预检，再复制/验证；每个private
最多一次解析/签名，因此总处理private bytes<=8MiB，无可重置的逐pair工作放大。
同一<=60秒原BOOTTIME期限贯穿预检/复制/逐key/native/清理/最终交付；atomic cancel只置位，
不另开worker，不在取消线程擦除正被native借用的数组。ThreadLocal拒绝同/跨实例重入。
任何IOException/Linkage/RuntimeException/Error/cancel/expiry使整个job失败，无prefix报告，
所有已拥有副本和wire finally擦除，caller原文不动。普通材料失败与归属歧义保留为条目。

不可变PrivateEntry包含private ID/ordinal、nullable phrase ID/ordinal(-1省略)、ownership
状态NO_PASSPHRASE/OWNER_RESOLVED/OWNER_AMBIGUOUS，以及nullable Verification；歧义时
Verification必须null，其他必须是严格CSKP结果。Verification只公开status/stage/kind/
encrypted/plain public subject digest；不含正文/phrase/private digest/signature/handle。
CredentialEntry只含entry ID/ordinal/type、归属状态和唯一时private ID/ordinal，不含secret。
PrivateEntry歧义时phrase ID=null/ordinal=-1，不挑任何候选。CredentialEntry在private候选
恰一时保留该唯一private locator（包括AMBIGUOUS_PASSPHRASE）；ORPHAN/AMBIGUOUS_PRIVATE/
INVALID_NAMESPACE及password均无private locator。此locator只说明唯一元数据候选，非材料成功。
列表顺序沿实际manifest各自ordinal，所有结果不可变。

Report固定 `allPrivateDeclarationsVisited=true, allNonTotpCredentialsVisited=true,
admissionComplete=false, keyAssociationsChecked=false, passwordTargetsChecked=false,
trustChecked=false`。`allPrivateMaterialsVerified`只在每个private非歧义且MATCH时true；
`allPassphraseOwnersResolved`只在每个phrase为OWNER_RESOLVED时true；这两个量词允许空集合。
password的TARGET_DEFERRED不得被这两个布尔吞为完整凭据/整库准入。
allPrivateMaterialsVerified只量化本轮strict格式子集与实际签名一致性，不升级为任意DER
规范性/所有私钥格式完整消费证明；原PEM top-level SEQUENCE检查的范围保持。

## 验证与完成边界

真实ARM验证五种软件key、自检实际签名、错误Ed seed、正确/错/缺phrase、legacy PEM提前
拒绝、clear额外phrase、RSA字段/multiprime/KDF/系统故障、所有期限边界/effects0；旧pair与
材料矩阵保持。JVM独立wire向量、全部private/credential覆盖、actual source/candidate、重复
private identity无phrase逐一检查、有phrase归属歧义不JNI、重复phrase/orphan/namespace、
仅选中副本及Error/cancel/deadline/最终wipe/oneattempt/reentry；fixture仅编译。
主会话串行构建/JVM/ARM/lint/normal+fixture/APK与文档；Trellis implement限定文件，check
独立源码/证据复核。仍不注册不完整provider、不开放恢复/connector，不设备/真实库账号/
安装/commit/archive/push/dist。全关系图authority、schema迁移、密码target/account模型、
证书CA/时间/主体/撤销/reviewer和真实Request逐字段消费及父AC仍开放。

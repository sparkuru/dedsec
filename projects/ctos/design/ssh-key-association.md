# 显式私钥与公钥/证书 subject 配对证明

已实现并完成本地验证：[实测与限制](verification.md#20261023-key-association)。

日期2026-10-23。接续[材料检查](ssh-manifest-inspection.md)与[资源引用](ssh-config-resources.md)。
本轮增加actual supplied-pair数学/签名原语，不发现或持久化整库关联，不注册生产validator/
reviewer、不开放恢复/connector。VaultManifest格式与既有material/config/auth行为保持。

## 明确输入与有限保证

新增owner-thread、一次尝试、同步 `SshManifestKeyAssociationInspector.inspect(manifest,
associations, deadline, cancellation)`。caller先提供实际独占VaultManifest loan及不可变
`Association(privateFileId, peerFileId, passphraseEntryId)` 列表，1..128个，不允许重复完整
三ID tuple。两file ID必须指实际PRIVATE_KEY与PUBLIC_KEY/CERTIFICATE，不接受路径/后缀/
identity文字作为配对替代，不读取current AppSecrets/view/role，不改/关闭caller manifest。

passphraseEntryId可null表示未选；非null必须精确命中SSH_KEY_PASSPHRASE，且该entry.identity
为`key:`+所选private.identity。按全局唯一ID选定，不扫描/自动选其他同identity口令，也不
复制TOTP/password或无关文件/秘密。显式ID选择不是整库所有同identity条目的唯一绑定证明，
未选ID对加密键返回NEEDS，即使库内另有匹配名称口令也不能自动取用。其他pair metadata
identity可以不同，不能用同名证明或拒绝实际数学关系。错误ID/type/metadata关系为固定IOException。

只复制所选文件和显式口令，cleanup slots在任何敏感复制前建立；每个body最多1MiB，
全manifest文件最多8MiB，口令1..8192且无NUL。所有pair处理的private+peer字节累计<=64MiB
预检（重复使用也计工作放大），最多128pairs、256次key解析和128次pair签名/验证；证书
解析另含内嵌CA签名验证。单次KDF仍按已有64bcrypt rounds政策。
private/peer可按原file ordinal只复制一次，复用独占副本；原始caller arrays不擦除。

## Native实际证明

在既有ssh_material_inspect.[ch]新增同步borrowed-slice `ctos_ssh_material_verify_pair`，
type仅PUBLIC_KEY3/CERTIFICATE4，参数为actual private bytes、可选精确口令、actual peer
bytes及同一原CLOCK_BOOTTIME期限，输出CSKAv1。不引入registry/OS mapping/handle；所有
临时key/sshbuf/签名/subject blob/口令副本由局部owner负责释放/擦除，失败无持久资源。
JNI新增SshManifestKeyAssociationInspector.Native.verify，material相关JNI由1增至2（不同
class各1），原CSMI及config15/session19不改。JNI参数副本使用有界heap并finally擦除，
与当前material JNI一致，不声称这些parser/OpenSSL堆分配全量锁页。

复用/提取现有private_envelope、OpenSSH encryption、pem_complete、public单记录及shared
key policy辅助逻辑，禁止复制第二套私钥/公钥parser。旧CSMI内容检查保持原结果和兼容性。
新strict-pair按以下顺序/界限：

- 检查输入/文本/单envelope、原deadline；沿用1MiB/65536byte单行/16384行和无NUL约束。
  private policy先于私钥parser/KDF，支持原软件Ed25519/ECDSA p256/384/521/RSA策略。
- 传统加密RSA/EC PEM的解密DER完整消费尚无复用证明，新pair模式在任何private parser/KDF
  前返回UNSUPPORTED（PRIVATE阶段），不能新增自写解密器或借PARSED冒充完整输入。原material/
  auth可解析这些格式的行为不改。受支持OpenSSH加密格式仍先检查共享cipher/KDF/盐/round限额。
- 清晰未加密private若附带显式非空口令，返回UNSUPPORTED（PRIVATE），不忽略口令后MATCH并
  声称已验证该凭据。加密输入缺口令NEEDS；真正parser解密错误BAD（也可能是密文损坏）。
- private和peer用固定parser处理，peer必须单个公钥或证书，无第二个非注释record；保留
  原typed材料边界。证书subject复制/demote为plain后再用shared software identity政策，
  不能将整张证书blob摘要与private公钥摘要直接相比。证书CA/信任/时间/主体用途不在证明中。
- 仅新pair对RSA modulus及所有present n/e/d/p/q/dmp1/dmq1/iqmp限制为最多16384bits，
  拒绝multiprime；超限为对应PRIVATE/PEER阶段UNSUPPORTED，不修改旧CSMI/auth/shared政策。
  此检查在parser返回后，不能消除PEM parser内RSA_blinding_on等既有非抢占成本。
- `sshkey_equal_public`只作必要条件；相等后用private对固定domain-separated本地challenge
  实际sshkey_sign，并以peer plain subject做sshkey_check_sigtype/sshkey_verify。RSA固定
  rsa-sha2-256；其他沿原软件算法。不接受caller任意待签数据，不返回signature/key blob。
  challenge绑定版本domain和canonical plain subject digest，不能变成通用签名/认证能力。
- 仅equal且sign+verify全成功给MATCH。公有成分不同为MISMATCH/RELATION；相同公开字段
  但private不能签出可验证签名为MISMATCH/SIGNER。SSH_ERR_SIGNATURE_INVALID等材料签名
  错误为MISMATCH/SIGNER；ALLOC/SYSTEM/LIBCRYPTO错误固定-1/errno，不能吞为材料不匹配。
  系统/分配/时限错误输出全零。

单次crypto/KDF不可抢占；Java cancel只置atomic状态并立即返回，worker返回后擦除并拒绝
发布，不启动额外worker。Native在阶段前后/最终输出及JNI写出后复查同一<=60秒原期限，
Java复制、每pair、调用前后、清理后的最终fence都沿同一期限，不逐pair续期。

## CSKAv1与Java报告

固定60bytes big-endian：七u32 `magic0x43534b41,version1,status,keyKind,flags,stage,peerType`，
接32byte canonical plain **public subject** SHA256（只在MATCH非零）。没有private/口令摘要。

- status MATCH0/MISMATCH1/MALFORMED2/UNSUPPORTED3/NEEDS_PASSPHRASE4/BAD_PASSPHRASE5。
- stage COMPLETE0/PRIVATE1/PEER2/RELATION3/SIGNER4；MATCH只0，MISMATCH只3/4，
  MALFORMED/UNSUPPORTED只1/2，NEEDS/BAD只1。
- flags只允许ENCRYPTED1、PAIR_ONLY4（必须）、PUBLIC_EQUAL8、SIGNER_VERIFIED16。
  两proof bits只在MATCH整体成功同时出现，不发布半证明；NEEDS/BAD必须encrypted，非MATCH
  kind与digest必须零，MATCH kind为原CSMI1..5且digest非零。peerType只3/4并匹配所选peer。

Java严格验证长度/magic/version/status/stage/flags/kind/digest/type后才保留不可变Entry。
Entry只含原file/entry ID与0-based ordinal（省略口令ordinal=-1）、status/stage/kind/encrypted/subject digest，不含正文、
口令、native引用或签名。Report固定explicitPairsOnly=true/admissionComplete=false、
trustChecked=false/credentialTargetsChecked=false；allRequestedPairsMatch只是本次显式列表
所有status==MATCH，不表示发现全部关联、密码用户名目标正确或所有材料有效。
任一cancel/expiry/IOException/Linkage/RuntimeException/Error使整次调用失败、无prefix报告，
所有敏感独占输入/临时native输出finally擦除；正常材料不匹配作为对应Entry状态保留供诊断。
native借用只能在同步调用内使用，不得保留或修改caller副本；Java ThreadLocal防同/跨实例
重入，其他工作限定创建者线程、一次尝试。

## 验证关卡与后续

真实ARM矩阵证明Ed/EC/RSA匹配、不匹配、PUBLIC/CERT subject、过期/host证书不被误当
信任证明、伪造一致公开字段但不一致private seed的真实sign/verify失败、坏frame/多记录/
unsupported/NEEDS/BAD/clear额外口令、legacy encrypted PEM解密前拒绝、KDF限额/时限/
故障/擦除及effects0。保留原material7784、key policy/auth/config旧矩阵，旧parser行为不能
被强pair路径静默收紧。JVM验证exact IDs/无名称猜配/精确口令不自动取、metadata校验、
实际source/candidate、只复制所选秘密与原ownership、strict wire/工作放大、取消/Error/
重入/最终wipe fence。平台fixture只编译，不安装运行。

main负责安全边界实现/源码复核、串行构建/ARM/JVM/lint/normal+fixture/APK审计及文档同步，
限定worker负责构建接线与JVM向量，独立复核原始验证证据。本地未提交，无设备/真实库账号/安装/
commit/archive/push/dist。全库关联authority、全格式消费、密码实际目标/用户名、口令全局
唯一归属、证书/信任/reviewer、动态资源/最终Request/provider/恢复/connector及父AC仍开放。

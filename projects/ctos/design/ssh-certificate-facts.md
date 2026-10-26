# 全部证书事实与嵌入签名政策

状态：已实现并完成本地验证。[实测与限制](verification.md#20261024-certificate-facts)：ARM22239/effects0、全量JVM638及normal/fixture/APK审计通过。

日期2026-10-24。接续[私钥审计](ssh-private-material-ownership.md)及
[证书源码研究](../.trellis/tasks/archive/2026-10/10-08-ssh-terminal-client/research/certificate-facts-native-20261023.md)。
本轮为后续信任/reviewer提供实际证书的有限事实，不改变v1、旧材料/pair/private APIs、
生产证书拒绝门、validator/provider、恢复或connector。

## 实际证书与有限政策

新增创建线程、一次尝试的同步 `SshManifestCertificateInspector.inspect(manifest,
originalDeadline,cancellation)`，访问实际独占loan里的每个CERTIFICATE，按原file ordinal
输出不可变Entry。不读取current vault/view/role，不构造Request/host/username，不修改/
关闭caller。只复制证书正文，每份一次；private/phrase/password/TOTP及其他正文不复制。
零证书不JNI，但仍检查原期限/取消及最终清理后fence。

新增同步borrowed native `ctos_ssh_material_inspect_certificate(body,length,deadline,output168)`，
复用既有text_bounds与parse_public(CERTIFICATE)单物理record/owned key parser，不另写
证书/公钥/ASN.1 parser。pinned cert_parse本来验证embedded CA签名；新操作只在其成功后
进一步应用明确的软件subject/CA及签名算法政策：

- subject复制/demote为plain；subject及embedded CA使用既有软件key政策及RSA各组成字段
  <=16384bit/no multiprime检查，支持Ed25519/EC256/384/521/RSA既有floor，不收SK/EXT/ML。
- CA签名仅 `ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,
  rsa-sha2-256,rsa-sha2-512`；复用sshkey_check_cert_sigtype与实际signature_type。
  新操作对已解析但不在此集合的`ssh-rsa` SHA1等返回UNSUPPORTED，旧CSMI/CSKA等不收紧。
- UNKNOWN/malformed公钥/证书解析沿原共享parser分类；UNSUPPORTED指已经解析但不符合
  新操作software/CA signature政策，不把旧parser分类静默重写。
- 保留实际USER1/HOST2、serial、valid_after/valid_before、principals数量、critical与
  extensions的原wire section byte长度，以及whole cert/plain subject/embedded CA三份
  分开的public SHA256。三份摘要不互相替代，不含private/credential摘要或签名正文。
  whole cert沿保留certblob序列化；subject与embedded CA是各自canonical public序列化，
  不声称CA摘要等于原始嵌入字段的非规范编码。调用key_result摘要前须独立保留parse_public
  的真实record line（key_result成功会把其result.line清零），三摘要用独立临时结果。

不导出key ID、principal或option字符串。CString可能归一化尾随NUL；本操作不声称raw
UTF8/canonical/非空/唯一/可适用principal，不解释critical/extension名称/内容。空或重复
principal、未知/重复option、过期/未来/空/反向有效区间仍按解析到的事实输出，不在无真实
purpose/target/time/approvedCA的情况下制造适用性或信任结论。不读取wall clock。
嵌入CA的签名验证只证明自洽；算法政策通过不表示CA已被批准、证书有效或能登录。

## CSCFv1固定wire

固定168bytes big-endian，12个u32、3个u64及3份32-byte SHA256：

`magic0x43534346,version1,status,flags,certificateType,subjectKind,caKind,
signatureAlgorithm,principalCount,criticalByteLength,extensionByteLength,sourceLine`；
接 `serial,validAfter,validBefore`，再 `certificateDigest,subjectDigest,caDigest`。

- status FACTS0/MALFORMED1/UNSUPPORTED2。flags FACTS_ONLY1始终必须；成功还须
  EMBEDDED_SIGNATURE_VERIFIED2/ALGORITHM_POLICY_CHECKED4（精确7），失败精确1。
- FACTS：type1/2，subjectKind与caKind沿旧kind1..5，principalCount0..256，两section
  byteLength0..1MiB，sourceLine1..16384为实际唯一证书record物理行。signature enum
  1Ed/2EC256/3EC384/4EC521/5RSA256/6RSA512，必须和caKind匹配（RSA5允许5/6）。
- u64不做signed拒绝，不比较时间或区间；Java字段名serialBits/validAfterBits/validBeforeBits
  保留完整unsigned bit pattern，不能用负数判断无效。每份digest必须非零。
- 非FACTS：除固定header及sourceLine0..16384外，所有type/kind/algorithm/count/length/u64/
  digest全部零，不能发布部分成功事实。没有trusted/time/principal/revocation标记。
- Java独立严格校验长度/magic/version/status/flags/字段闭包/CA对应关系/zero fields及digest；
  不可把CSCF和CSMI/CSKA/CSKP互解，未知/尾随数据全job拒绝。

Native/JNI系统或生命周期失败返回-1/ENOMEM或EIO/ETIMEDOUT等并清零输出。沿用
parse_system_error；不把ALLOC/SYSTEM/LIBCRYPTO吞为材料结果。pinned parser已把部分深层
getter/CA parse错误归一化为格式错误，wrapper不能恢复其错误类，也不声称深层OOM全可区分。
parse/embedded verify不可抢占，新postparse软件/算法检查不阻止此前已发生的crypto成本。

## 期限、ownership和报告

同一<=60s CLOCK_BOOTTIME原期限贯穿preflight/copy/每证书/解析/摘要/cleanup/native输出/
JNI写出/Java清理后最终交付；原子取消只置位，不启动额外worker、不由取消线程擦除借用中
数组。ThreadLocal拒绝同/跨实例重入，创建线程一次尝试；任何IOException/Linkage/Runtime/
Error/取消/过期拒绝整个job，无prefix report，所有所选副本与wire finally擦除。

counts<=128files/1024entries，每body<=1MiB、全manifest body<=8MiB，证书总复制/处理<=8MiB，
固定结果总长<=21504bytes。先完成所有大小预检并建立cleanup slots再复制；每证书一次。
JNI有界public heap scratch并finally擦除，保留pending exception；无registry/handle/mapping/
私钥/口令/通用signing callback，不声称parser/OpenSSL全堆锁页。

Entry只含file ID/path/identity/ordinal、status/sourceLine和成功时Facts（其余为null）。
Facts只含上述type/kinds/algorithm/counts/u64 bit patterns/三个public digest，不保留数组、
native key/handle/signature。Report不可变，固定allCertificateFilesInspected=true、factsOnly=true、
admissionComplete/trustChecked/timeValidityChecked/principalApplicabilityChecked/revocationChecked=false。
allCertificateFactsAvailable只量化每个CERTIFICATE为FACTS（空集合true），不代表整库信任。

## 验证与完成边界

真实ARM覆盖五subject/五CA算法、USER/HOST、三独立digest、RSA SHA2与新拒绝SHA1政策、
0/high-bit/MAX u64、空/未来/过期/反向时间、principal/critical/extensions保留事实、坏签名/
CA/第二record/decoded suffix/非证书/type/size/line/NUL、可观察系统故障/所有期限fence及
effects0。保留旧材料/pair/private矩阵；其计数可能随随机PEM长度变化，记录真实输出。
JVM独立wire闭包/unsigned bits/全部证书实际source-candidate/零证书/仅选中副本/预算/
Error/cancel/owner/reentry/最终wipe，平台fixture精确opt-in只编译，未Android执行。
Trellis implement各限native或Java；main串行全部构建/ARM/JVM/lint/APK与文档；check独立
源码/测试/原始证据复核。仍不设备/真实库账号/安装/commit/archive/push/dist。完整持久关系
模型、密码账号、CA/时间/主体/撤销与reviewer及真实Request/provider/恢复/connector/父AC开放。

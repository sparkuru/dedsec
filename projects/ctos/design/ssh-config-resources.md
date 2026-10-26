# CONFIG 显式资源引用绑定

日期：2026-10-23。接续[静态Include与有限政策审计](ssh-config-audit.md)，本轮限定五类
显式资源声明的元数据分类、静态类型与直接引用。完整材料/数学关联/信任/生产准入仍待完成。

## 模式与保证

新增显式RESOURCE_AUDIT模式及JNI `reserveResourceAudit():long`、`resourceAudit(long):byte[]`。
Java新增 `prepareDetachedResourceAudit(guard[,binding])`、`resourceAudit(maxBytes,maxRows)`，
以及同一个inspector的 `inspectResourceAudit(manifest,deadline,cancellation)`。原EFFECTIVE/
CATALOG/AUDIT、旧getter与CSEQ/CSES/CSCD/CSCAv1全部保持；新模式只允许resourceAudit getter。
内部共享scan/finite-policy/Match syntax-only/Include-edge逻辑须明确包含新模式，getter身份
须精确判别，不能用非零mode代替。没有新Request/view/库tuple或第二份native正文副本。

复用同一protected CSDI、resource-free reservation先发owner、load/run/usage/cancel/close/
destroy、原<=60s BOOTTIME、owner/PID/UID/reentry、取消attach与可重试cleanup。非CONFIG
正文不复制/打开；资源查找只能读原有type/path metadata，不能调用IO open或hostfile loader。

## 声明语法与类型

从同一固定readconf keyword/argv入口，在Host/Match/first-value之前收集所有物理声明，
包括未生效、被首值遮蔽、Include内与orphan。不执行Options/defaults，不在Java另写parser。

| kind | 指令 | 单行argv | STATIC允许type | none |
| --- | --- | --- | --- | --- |
| 1 | IdentityFile | 恰好1个非空值 | PRIVATE_KEY/PUBLIC_KEY/CERTIFICATE | 大小写不敏感DISABLED |
| 2 | CertificateFile | 恰好1个非空值 | CERTIFICATE | **字面相对路径**，DEFERRED_CWD |
| 3 | UserKnownHostsFile | 1..32个非空值 | KNOWN_HOSTS | 必须独占，DISABLED |
| 4 | GlobalKnownHostsFile | 1..32个非空值 | KNOWN_HOSTS | 必须独占，DISABLED |
| 5 | RevokedHostKeys | 1..256个非空值 | PUBLIC_KEY | 必须独占，DISABLED |

Identity none仅禁用该entry，不抹掉其他Identity声明。Certificate none没有上游skip行为，
显式Certificate声明还会影响implicit cert探测；本轮不执行该探测。Revoked仅覆盖当前可表达
单PUBLIC_KEY的typed子集，不把KNOWN_HOSTS当revocation、不新增KRL/list type；材料格式、
KRL/多key列表/CA/revocation语义仍未证明。Identity的PUBLIC/CERT不证明存在可用私钥/agent。

缺参/空argv/额外单值参数/none混合列表为INPUT；Known列表>32及通用argv/资源容量超限
为LIMIT。复用原引号/转义/注释语法，不重组raw文本。只收集显式声明；不合成默认identity/
known-host文件、`.pub`/`-cert`后缀目标或source line，不声称覆盖最终effective选项/实际认证。

## 分类与静态查找

每row的state封闭为：STATIC=1、DEFERRED_CONTEXT=2、DEFERRED_CWD=3、DISABLED=4、
DEFERRED_KEY_RESOLUTION=5。STATIC target为实际原始一基ordinal；其他state的target严格0。

1. 先按kind/arity识别合法none。裸`~`、`~other`/`~other/...`与虚拟home之外的绝对路径
   明确PATH，不能读取NSS/home/cwd。已知虚拟home是`/ctos-vault/`，不是推断宿主home。
2. Identity/Certificate/UserKnown/Revoked若含`%`或`${`保守DEFERRED_CONTEXT，不做展开或
   完整token语法认证，含`%%`也保留未决。裸`$name`按固定dollar helper是字面，不能误当
   环境展开。Global不做percent/dollar展开，`$`是可表示的字面字符；`%`因受管路径规则
   无法表示而PATH，不把它错误声称为上游插值。
3. 其余非绝对且非`~/`值一律DEFERRED_CWD，即便有同名manifest对象也不lookup。不能套
   Include的`~/.ssh/`或CONFIG父目录；相对路径的最终规范化/安全检查保持后续真实上下文义务。
4. `~/suffix`只映射既有虚拟home，或直接使用`/ctos-vault/suffix`；以非pattern的受管规则
   严格验证suffix、长度<=512、UTF8/控制、无glob/遍历/空段等，再查原metadata。所有五类
   都不glob；静态通配字符是不可表示的字面路径，不能展开/过滤。其他未决分类不授予路径权限。
5. 精确对象存在则按上表类型检查，错type PATH/EACCES；成功STATIC。缺失Known/Revoked为
   PATH/ENOENT；缺失Identity/Certificate为DEFERRED_KEY_RESOLUTION，仅这两kind允许state5。
   必须先通过静态受管path验证才允许state5，非法/越界不能降级成该state；不探测或认证suffix。

STATIC只证明**声明的literal base**映射到该metadata对象。上游public loader的`.pub`、
private-envelope与implicit cert探测、数学关联、实际签名能力和最后消费范围另须证明。
不得从本轮成功推断后缀文件被批准，或把missing-base未决称为上游坏语法。

## 来源、wire 与累计限额

资源行六u32：`kind,parent,line,argument,state,target`。parent为真实CONFIG原ordinal，
line为物理来源，argument为该行一基argv位置。按locator `(kind,parent,line,argument)` 去重
重复Include遍历，保留首次DFS顺序；不同参数/声明即便指同目标也保留。冲突state/target的
重复locator必须拒绝，不能按全六字段去重。重复读取仍计原IO/行数预算，不跳过parser遍历。

CSCRv1为big-endian：magic0x43534352/version1/flags1/auditLength/resourceCount（20bytes），
后接exact原CSCAv1，然后每row六u32。auditLength44..82732、count0..1024，整帧64..107328，
严格有界算术验证`20+auditLength+24*count==wire.length`，无尾随。flags必须且只能1，
表示显式资源声明已分类，不表示完整准入。parent/target1..128、line1..16384、argument1..256；
kind1/2的argument必须1，kind3/4<=32。DISABLED只允许kind1/3/4/5且argument1；state5只1/2；
Global不能state2。所有非STATIC target0，STATIC不能parent==target且type须按表复查。

新whole CSCR累计<=1MiB，aliases+Include-deferred+Include-edges+resources累计<=4096。
先按remainingBytes/rows/outer header预检，扣resourceCount后解码CSCA，CSCA再扣edges后解码
CSCD；嵌套只解码一次并共用对象，不重复计bytes/rows。两层临时nested副本/outer wire
finally擦除；locator打包argument256需9bits，不能沿用edge的8bit target布局。
原1024opens/64MiBreads/16384lines/64MiB复制放大与128files保持，元数据查找零额外IO；
per-token/lookup循环检查原取消/期限。新metadata由eval拥有、失败/close擦除释放。

## Java 验证与报告

`ResourceAudit`包含同一个inner `Audit`、不可变resources、encodedBytes和`hasDeferred`
（state2/3/5）。Root.catalog引用inner.audit.catalog，Root.audit引用inner Audit，
Root.resourceAudit只在新模式非空。原inspect/inspectAudit不升级、不新增资源拒绝。

对每row先核实parent为实际CONFIG；STATIC再核对target范围、上表type与
`target.path in actualParent.references`，不能用root/sibling/identity/后缀代替。缺直接引用
抛固定消息的 `ResourceReferenceException`，只含kind/parent/line/argument/target数字。
不要求references集合恰等于资源列表；Include和其他未决关联可以并存。非STATIC不制造target。

Report新增 `staticResourceReferencesVerified` 仅显式新模式成功为true，原两种检查为false；
可汇总`resourcesIncomplete`，但不与catalog.includesIncomplete混为一谈。零CONFIG无JNI且
同原最终fence，true为这项有限检查的空集合语义；全deferred也只能证明已STATIC-bound行的
直接引用。恒declarationsOnly=true/admissionComplete=false，不授予provider/恢复/连接能力。
任何root失败拒绝全report；cleanup成功正常destroy，失败保留精确owner供caller继续close，
原Throwable/取消/期限/Java副本擦除和最终publication协议复用，不新增第二套生命周期。

## 验证与范围

native真实parser/handle矩阵须覆盖五kind语法/none、类型/parent/原ordinal/line/arg256、
所有分支/重复遍历/locator、路径分类与Global/bare美元、suffix未决、无resource-body访问、
新旧模式/嵌套wire一致性、预算/取消/错owner/cleanup/getter失效与effects0/capabilities0。
JVM验证strict codec/三层配额/临时wipe、恶意来源/类型/直接父引用、新旧flags、all/orphan/
zeroCONFIG、原累计预算/取消/Error/cleanup重试/final fence；平台fixture只编译不安装。
主串行strict/ARM/旧矩阵/IO/focused/full JVM/lint/normal/fixture/APK审计与补丁hash/依赖
记录，checker独立复核，spec/design/task/handoff/journal同步。本地未提交，无设备/真实库
账号/安装/commit/archive/push/dist，完整材料/身份/凭据/信任/reviewer/provider/恢复/connector
与父AC仍开放。

## 本地实现状态

本轮已实现，真实ARM4439checks含baseline1262/effects0/capabilities0，全部旧配置矩阵/IO426、定向151/fresh全量564 JVM、normal94全重跑/fixture111与三APK审计通过。新平台fixture只编译，实际Android/JNI未执行；[完整结果与边界](verification.md#20261023-config-resources)。完整数学关联/材料/凭据/信任/provider/恢复/connector仍后续，父task保持in_progress，本地未提交。

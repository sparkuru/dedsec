# OpenSSH 终端与统一秘密库接入

## 本轮交付关闭与归档范围（2026-10-26）

用户明确要求正式安装、说明功能、归档并提交。已验收并安装的前台密码SSH闭环作为
本轮交付关闭；原下方R1–R10/AC1–AC9全功能目标没有全部实现，仍由父任务和
[项目规划](../../../../../design/plan.md)保留。归档不将未完AC改勾或认定全部SSH功能完成。
本轮只补齐归档后稳定构建入口和交付记录，不扩大实现后续能力。

## 当前：前台密码连接闭环（2026-10-25）

2026-10-26用户授权后的指定Android11板实际bridge与Flutter界面均验收通过，当前前台
密码连接闭环完成；后续全功能仍沿同一主线。[实际记录](../../../../../design/verification.md#20261026-password-android)。

按[连接合同](../../../../../design/ssh-password-connect.md)从真实SSH表单到TCP、实际主机键确认/
CAS、一次临时密码、SUCCESS及PTY/shell后进入现有终端。保存真实公开最近连接/已确认
主机键到同一加密库，密码仅本次；生产连接不建假库或使用fixture transport。支持subset的真实validator
完整覆盖并拒绝未知资料，延续唯一正式模型。认证60秒与已建shell分离，不续秘密权限。
本地检查与本轮设备隔离测试分别记录；没有用户真实账号/正式应用覆盖安装/提交/发布。

本地闭环已实现并验证：Flutter242/analyze、fresh JVM718/42全0、最终ARM client1,756,509/
issuer33、独立OpenSSH密码/PTY/resize/真实70秒后新流量与退出通过；最终APK/lint/原始证据
独立复核通过。Android完整bridge/UI已于2026-10-26实测通过；父task全功能仍进行中。
[实际结果](../../../../../design/verification.md#20261025-password-connect)。

## 用户更正：唯一正式主线（2026-10-25，当前有效）

SSH尚未正常使用，不维护v1/v2两套主线。按[正式合同](../../../../../design/ssh-manifest.md)将最新
模型设为所有构造/create/write的唯一默认，删除formatVersion/version2业务API；恢复按实际
未审阅metadata而非格式判断。只保留已交付纯TOTP的窄输入适配，读取不改库，正常内容写入/
换口令/恢复写最新编码；原认证/guard/数据保护不变。此决定覆盖下方历史双格式政策。
目标为实际SSH连接使用，不以单项门禁数量替代。本地检查通过，无设备/真实账号操作。

本轮收敛已实现并本地验证：定向177/全量691 JVM、lint/normal92/fixture111与三APK审计及独立raw复核PASS。source5/protected307；[实测](../../../../../design/verification.md#20261025-ssh-mainline)。父客户端实际可用性仍待最小密码连接闭环验收。

## 2026-10-25 当前增量：v2显式关系与账号基础（本地门禁通过）

[合同](../../../../../design/ssh-manifest.md)新增显式版本化KeyBinding/Account声明及严格结构校验，v1字节roundtrip/默认create不变，不推断旧target/关系或自动迁移。四处candidate重建保留version/metadata，loans/export/rekey保留actual bytes；源/目标v2恢复在该侧validator前拒绝并防御bind，原source-first顺序保留。此为schema/保留基础，不是数学/完整coverage/账号认证/信任/reviewer/provider/connector完成。Trellis schema/core限定源码，main串行构建/文档，check独立复核。本地未提交，无设备/真实库账号/安装/发布。证据`/tmp/ctos-ssh-manifest-v2-20261025.stybeq0l`。

生产编译41tasks17s，定向200/全量679 JVM全0，normal92全重跑/fixture111及三APK/lint审计通过；source9/protected303，独立源码/41新测试与raw证据复核PASS，无遗留finding。[实际结果](../../../../../design/verification.md#20261025-manifest-v2)。父任务仍in_progress/implementation_complete=false。

## 2026-10-24 当前增量：全部证书事实与签名政策（本地增量完成）

[合同](../../../../../design/ssh-certificate-facts.md)扫描实际清单全部CERTIFICATE，仅复制其正文；同pinned parser嵌入签名验证后施加明确软件/现代签名政策，CSCF168固定public事实，unsigned u64保留bits、物理record来源、三个不同public摘要。不构造host/user/purpose/time/CA authority；不把事实解释为信任/适用性，不改旧CSMI/CSKA/CSKP/v1/生产证书拒绝/provider/恢复/connector。Trellis native/Java implement限定文件，main串行全部构建/文档，check独立复核。本地未提交，无设备/真实库账号/安装/发布。证据`/tmp/ctos-ssh-certificate-facts-20261024.izvxli13`。

本地门禁：首compile41tasks18s/strict13tasks18s，实际ARM22239/effects0与旧private19980/pair13814/material7784、定向145/fresh全量638 JVM全0；normal100全重跑1m47s/fixture11120s及三APK/v2/隔离/同SSH库/22ALLOC/JNI15_19_1_1_1_1/archive83通过，source10/protected299匹配。[实际验证与限制](../../../../../design/verification.md#20261024-certificate-facts)。父任务继续进行中。

## 2026-10-23 当前增量：全部私钥材料与凭据归属（本地增量完成）

[合同](../../../../../design/ssh-private-material-ownership.md)补齐所有PRIVATE_KEY实际self-sign/verify及所有非TOTP凭据v1元数据归属审计。重复private identity无phrase仍逐个检查；有phrase时owner/phrase必须唯一，否则显式歧义、不猜选、不复制歧义secret。password仅target namespace/opaque suffix，未验证真实账号。CSKP60独立于旧CSMI/CSKA；复用strict parser/shared signing helper，原期限/owner/cleanup与v1格式保留。Trellis implement限定源码；main所有构建/文档；check独立源码/证据复核。本地未提交，无设备/真实库账号/安装/commit/archive/push/dist。证据 `/tmp/ctos-ssh-identity-next-20261023.j_o6gwfz`。

本地门禁通过：strict11tasks15s、ARM19976/effects0与最终独立pair13812/material7784、定向120/fresh全量613 JVM全部通过；normal98全执行1m46s/fixture11119s、三v2同signer/隔离target/同生产SSH库/22ALLOC/JNI15_19_1_1_1/archive83。source10/protected295，原v1/production provider与父AC保留。[实际验证与限制](../../../../../design/verification.md#20261023-private-material)。

## 2026-10-23 当前增量：显式密钥配对证明（本地增量完成）

[合同](../../../../../design/ssh-key-association.md)冻结精确ID选择、证书plain subject与固定domain实签验签、CSKA60严格wire、原期限/一次owner及擦除。新pair独立限制RSA所有组成字段并拒绝传统加密PEM；旧材料/auth行为保留。只证明调用方选定pairs，不建立整库authority，不注册validator或开放恢复/connector。主会话负责安全边界实现与全部构建，限定worker负责构建接线/独立JVM向量。证据 `/tmp/ctos-ssh-key-association-20261023.1p6pj89y`。本地未提交，无设备/真实库账号/安装/发布。

本地门禁：ARM13814/effects0、旧材料7784/fresh认证1748333、定向80/全量586 JVM全部通过；normal96全执行1m42s/fixture11121s及三APK/22ALLOC/JNI15_19_1_1/archive83通过，source9/protected292匹配。主会话源码复核与独立原始证据核对完成，[实际结果与限制](../../../../../design/verification.md#20261023-key-association)。父任务继续进行中。

## 2026-10-23 当前增量：显式资源类型与引用绑定（本地增量完成）

对五类显式资源声明从真实CONFIG来源收集类型、参数位置与静态目标，核对实际parent直接references；动态、cwd与key-loader未决不能假装已验证。不推断suffix/私公钥数学关系，不读取非CONFIG正文；旧模式不升级，报告仍partial-only。

证据：`/tmp/ctos-ssh-config-resource-20261023.gmfy0g24`。无设备/真实库账号/提交/发布。

本地门禁通过：ARM Resource4439含基线/全部旧矩阵/IO426、定向151/全量564 JVM、normal94全部重跑/fixture111及三APK审计。source15/protected311，独立checker无具体遗留finding。[实际结果](../../../../../design/verification.md#20261023-config-resources)。完整数学/凭据/信任/provider/恢复/connector与父AC仍开放。

## 2026-10-23 当前增量：CONFIG静态引用与本地效果审计（本地增量完成）

所有CONFIG实际静态Include必须可定位到真实父/行/子并核对直接references；所有分支的禁止本地效果/信任绕过政策不能被Host/Match或first-value隐藏。AUDIT独立于声明catalog/有效求值，不猜动态上下文；报告仍partial-only，完整身份/凭据/信任和生产恢复仍后续。

证据：`/tmp/ctos-ssh-config-audit-20261023.uvozbgq0`。无设备/真实库账号/提交/发布，父AC保持开放。

本地实现与门禁通过：ARM13290含基线/旧矩阵/IO426、JVM124/全量537、normal94全部重跑/fixture111与三APK审计；source15/protected307，独立checker无具体遗留finding。[实际结果与限制](../../../../../design/verification.md#20261023-config-audit)。完整语义/生产恢复/connector与父AC仍开放。

## 2026-10-23 当前增量：实际清单受管配置检查（本地增量完成）

实际来源/目标/候选的配置须可从Java进入独立native输入，逐个覆盖全部CONFIG且保留来源，整个job的取消/期限及复制/IO/结果资源有界。JNI复制失败也不能丢失清理owner；不复制非CONFIG/秘密条目正文。报告只声明检查，不作为完整语义或恢复准入。原native和live行为保持，仍本地未提交，无设备/真实账号。

本地实现与门禁完成：ARM3683（含基线）/原矩阵/IO426、定向101/fresh全量514 JVM、normal94全部重跑/fixture111及三APK审计通过；[实际结果与限制](../../../../../design/verification.md#20261023-detached-managed)。新spec已同步，source15/protected303；完整语义/恢复/connector与父AC继续开放，本地未提交。

## 2026-10-23 当前增量：独立 native 配置输入（本地增量完成）

实际 source/target/candidate 的 CONFIG 扫描/求值须独立于活动库和 public view，保留原始文件来源、独占输入和原期限/取消。只复制 CONFIG 正文，非配置仅 metadata；不创建虚构 vault/role。声明目录与有效求值的既有含义保持。该基础增量不证明全清单语义或恢复准入，Java/JNI 编排、引用/身份/凭据/信任与 connector 仍后续。仅本地未提交，无设备/真实库账号。

本地ARM1261/原配置矩阵/IO426、JVM483与normal94/fixture111及APK审计通过；[实际验证与限制](../../../../../design/verification.md#20261023-detached-config)。

## 2026-10-23 当前增量：TOTP mutation（本地增量完成）

[TOTP写入与票据交接](../../../../../design/totp-mutation-lifecycle.md)已实现：原origin/预算/注册、锁外独占source/candidate校验、跨Request精确票据交接和唯一effect claim。修复取消/旧票据替换/BUSY并发清理与pin可见性。定向196/全量483 JVM、normal/fixture构建和lint通过；[实际验证](../../../../../design/verification.md#20261023-totp-mutation)。主会话负责文档/构建，implement/check负责限定Java与独立复核。本地未提交，无设备/真实库账号；完整语义provider/恢复/最终计划connector与父AC保持开放。

## 2026-10-22 当前增量：trust CAS（本地增量完成）

继续[trust CAS 生命周期合同](../../../../../design/ssh-trust-cas-lifecycle.md)：审批前原context、锁外校验、可取消provider与精确view延续；落盘后交付失败明确uncertain。主会话负责文档/全部构建，implement/check代理负责限定Java/测试与独立复核。仅本地未提交，无设备/真实库账号。

定向202/全量469、normal92全重跑/fixture114、lint与三APK审计通过。源码8/protected266；新增14测试。[实际验证](../../../../../design/verification.md#20261022-ssh-trust-cas)。下一项剩余TOTP mutation，完整语义/恢复/connector与父AC继续开放。

## 2026-10-22 当前增量：普通SSH凭据保存/删除

保存/删除凭据的耗时认证与source/candidate校验不得阻塞核心锁。原期限/取消/注册、
精确id/target/connection及保存host gate贯穿一次能力与事务；候选不得共享原数组，
完整candidate包括first/last SSH都需校验。保留wrapper/原写入claim/uncertain/会话失效。
trust CAS及TOTP mutation的不同语义保持后续，不能推定它们由本增量完成迁移。

## 2026-10-22 当前增量：SSH typed秘密读取

凭据/私钥/私钥口令bundle读取的耗时认证与校验不得阻塞核心锁；原请求期限/取消/
注册及原role/view、精确host/KEX/selection贯穿一次操作和最终受管秘密复制。最终交付
必须与锁库及精确请求/角色/视图撤销排序，不沿用公开loan的claim后交付许可。保留
public native attach及时取消、共享操作上限和own-NEW-CAS；完整语义/恢复/connector另行推进。

## 2026-10-22 当前增量：SSH public读取

公开SSH文件读取不得占用core运行语义validator/JNI；原请求期限/取消/注册与精确快照贯穿，private正文不释放，role snapshot保持原view与deadline，own-NEW-CAS continuation不变。全语义/恢复及其他typed消费者继续后续。

## 2026-10-22 接续：TOTP typed读取

混合库TOTP列表/验证码读取的语义校验不得占用核心锁；原Request排队起的期限/取消/
epoch与mixed validator注册贯穿admission、一次effect和最终交付。纯TOTP不新增SSH依赖。
校验只借用可擦除独立副本，不能改变实际生成代码的原文；实际列表/复制仍按当前窗口、
快照与锁库边界检查，不用read_身份写入，不把TOTP管理preview等同于read receipt。
仅本地未提交，Android/真实复制和完整语义恢复准入不由本轮JVM/编译证明。

## 2026-10-22 接续：bundle 校验生命周期

受管文件 bundle 的校验不能占用核心锁阻塞取消或校验器撤销；审批、排队、校验、写入
共用原请求期限与取消源。候选和借用校验材料必须独占、可擦除，不能影响原始凭据。
能力精确绑定 proposal、快照和原注册，一次消费；失败不恢复，不跨注册重用。
删除最后 SSH 对象仍须校验。保持无关数据、快捷包装、原事务和精确审批保护。
仅本地未提交；完整产品与语义恢复准入仍待后续。

## 2026-10-21 接续：恢复预览校验生命周期

恢复来源与已有目标的耗时校验和信任审阅不得阻塞核心锁下的取消/失效操作；共用原请求
期限，交付claim前的取消/锁库/快照或校验器注册变化不能交付旧预览或写入；已成功claim
的不可变摘要可完成锁外交付，之后失效仍拒绝commit。校验者只借用可擦除的
实际认证输入，修改借用内容不能批准另一份原文。旧精确信任审阅、来源/目标口令与失败
保护维持；纯TOTP路径不新增SSH依赖。仅迁移恢复预览，不把整库语义/所有业务入口或
生产SSH恢复开放标为完成。仍仅本地未提交、不设备/真实账号。

## 2026-10-19 实际manifest离线文件内容检查

实际清单离线内容检查：检查传入source/target/candidate文件字节，不递归current vault、不虚构lease。密钥/known_hosts返回明确PARSED/材料失败/缺失口令，CONFIG及未覆盖语义明确pending。报告不作为SshContentValidator/信任或恢复准入；原门保持。

本内容检查增量已实现：材料ARM7784/effects0、fresh JVM358、normal95/fixture114和三APK审计通过。
完整认证回归、失败轮与边界见[验证](../../../../../design/verification.md#20261019-manifest-inspection)。
报告恒contentOnly=true/admissionComplete=false；父任务继续进行中，不设备/提交。

## 2026-10-19 当前增量：无上下文声明catalog

复用同一固定readconf词法入口，列出真实正向literal Host及首次来源，保守遍历静态Include；动态Include明确返回未解析位置，不虚构alias/user，不生成通配设备行。结果只保证声明发现，保持原生有效求值及public lease生命周期，不开放AppSecrets全清单语义校验/恢复门。仅本地未提交，无设备/真实库账号。

本增量已实现并过本地门：目录ARM71923/effects0、原求值3736/handle5201、fresh JVM327、
normal95/fixture114及三APK审计通过；独立平台fixture仅编译，父任务AC仍开放。
实际结果见design/verification.md#20261019-config-catalog。

## 2026-10-19 当前增量：JNI/Java配置求值边界

在已实现native求值器上交付生产JNI/Java生命周期与有界不可变摘要/来源诊断；借用caller lease、不撤销它，完整Options留native，原取消/期限/清理有效。暂不接catalog/validator/connector/Flutter或设备，继续本地未提交。

本增量已实现；ARM5201/effects0、fresh JVM308、normal95/fixture114及三APK审计通过。
具体工件/固定输入打包与Android未运行边界见design/verification.md#20261019-config-jni。

## 2026-10-19 当前增量：生产native配置求值器

在原客户端范围内，配置求值须复用固定OpenSSH parser，读取同一受管Include快照，
无宿主文件/环境/网络/命令效果；非法输入不导致进程退出，返回有定位的诊断。结果完整
保留有效选项，包含jump/forward/session行为；不能靠字段丢弃冒充兼容。撤销/取消/原期限
和资源回收持续有效。本轮先交付native入口与合成验证，导入/catalog/JNI/UI/connector
仍后续；无设备、真实库/账号、提交或归档。

本native增量已实现：ARM3736/effects0、共同链接会话1725125、normal95/fresh JVM288、
fixture114与APK审计通过。实际边界见design/verification.md#20261019-config-evaluator。

## 2026-10-19 接续：启动器主机键前置增量

按原任务继续本地实现MANAGED offered-host open与有界exact NEW审批，旧认证/PTY基线保持。
已实现native→JNI→Java、真实encrypted KNOWN/NEW认证至shell与fixture源码；native full1724409、
managed87305、宿主fresh JVM288通过。续做已完成normal94项全重跑、fresh Gradle JVM288、
fixture113项与三APK验签/隔离；独立静态ARM专项87300/完整1724636通过，旧动态环境已失未重跑。
新版Android运行仍未执行；验证见design/verification.md#20261019-managed-host。
生产readconf evaluator/catalog/connector及service仍后续；父任务继续进行中，不提交/设备。

## 2026-10-19 本增量：session channel / PTY / shell

用户要求先冻结合同，再完成native→JNI→Java合成闭环并接现有终端；仅认证SUCCESS
放行，保留proof/撤销/期限/线程。当前代码与本地验证已交付：命令输入输出、resize、
正常退出和取消回收；native完整ARM回归、fresh JVM270和Flutter227通过。现有终端
已具真实会话host-only接入与typed生命周期，生产profile/config/connector启动器仍开放。
设备JNI/界面实测与本地结果分列，详见design/verification.md#20261019-ssh-shell。
父任务仍in_progress/implementation_complete=false；不重做认证、不使用真实账号。

## 2026-10-19 本连续阶段已完成：Android/JNI + 私钥认证

已完成密码与未加密/有界加密私钥的生产Java/JNI编排及独立Android实测，不再把两项列为
尚未实施。CSASv2/CSKBv1精确口令能力、RSA SHA2协商、expected-public/local verify、
全线程重入、真实view/role期限和自身NEW/CAS生命周期修复均有真实合成正反回归。

最终fresh JVM244/0/0/0、ARM1244010checks/exit0、normal94tasks65s/lint/Release/v2、
archive83/12JNI/forbidden0通过。开发板密码/未加密私钥/加密私钥/真实NEW各精确方法OK1，
26.954/27.418/23.781/23.906秒；两包进程清理，正式APK3bc762d9未变，未用真实vault/账号。
普通APK89eb6425未安装；完整证据/格式限制见design/verification.md#20261019-android-auth。
加密PKCS8、KBI/MFA、rekey/压缩、channels/PTY/forward/service/UI和父任务AC继续开放。

## 2026-10-19 连续推进：Android/JNI 与私钥认证

用户追问两项何时完成；main明确现在连续推进，不在每个C切片结束时等待“继续”。先完成
Java/JNI密码服务/认证编排与独立Android加密peer，再完成私钥parse/equal_public/sign及
联合验证。真实账号不是开发阻塞；设备操作在精确fixture准备后按AGENTS取得当前授权。
NEW/CAS真实vault写回会撤销publiclease的生命周期问题需解决，不以fakeCAS/view宣称通过。

## 2026-10-19 当前本地交付：一次密码 USERAUTH 协议（已实现，本地合成验证通过）

在当前选择/证明绑定的凭据回调上，实现C-only显式密码认证请求与结果。用户名必须来自
ownedCSAS，密码仅一次selectedprovider，8192上限/拒NUL/短生命周期；真实加密合成peer
验证成功、拒绝、部分成功和要求改密各结果。保留旧KEX/service/handoff停止点；不增加
Java/JNI编排、私钥auth、自动重试、MFA/change操作、channel或设备/真实账号访问。
实际新增67个加密连接场景，最终ARM785537checks/exit0，strict/lint/Release/v2与正式
复核通过；normal94tasks19s，JVM221为UP-TO-DATE保留报告。APK983f2032未安装，完整
Android与产品AC继续开放；完整命令/hash/覆盖限制见design/verification.md最新节。

## 2026-10-19 当前本地交付：证明绑定的一次凭据回调

合成native会话只在CSAS选择、service-ready和当前KEX证明全部匹配时请求一次固定typed
bytes，并在库最终effect和返回caller前复查；不允许旧回调降级或秘密提前进入caller
buffer。保留unselectedlegacy合同；不发送认证包、不解析或使用真实key/账号。
本切片已实现并正式复核；fresh JVM221、callback35、最终ARM323726checks、lint/Release/
v2通过，APK `f329056e…` 未安装。修改后的JNI未Android执行，实际认证和私钥匹配继续开放。

## 2026-10-19 当前本地交付：native selection metadata

受管选择通过有界canonicalrecord进入native session并保存explicitusername/typedresource
及SHA256回执，保留legacy null路径。验证strictframing/UTF8/rolebinding、复制/cleanup和
Java receipt核对；不释放秘密、不发送USERAUTH、不把metadata回执当认证或实际公钥匹配。
该切片实现与正式复核完成；freshJVM208、最终ARM QEMU253155checks、lint/Release/v2
通过。修改后的JNI未设备执行，完整认证/receipt-boundcredentialcallback仍开放。

## 2026-10-18 当前本地交付：不可变认证选择

在实际认证前，managed层可显式绑定每hop用户名和精确passwordentry或privatekey
path/identity/expectedpublic。selected路径不能仅按credentialkind重新选择条目；保留旧
未opt-in合同，真实secretrelease仍经过既有host/KEX/vaultfinalguard。仅本地合成验证，
不发送认证包、不扩大设备/账号授权，不把预期公钥声明当实际私钥匹配证明。
该managed切片已实现并正式复核：新增12项测试，fresh全量JVM201/0/0/0、lint/Release/v2
通过。native receipt/current-cycle/实际wire使用、公钥私钥匹配及完整认证仍开放。

## 2026-10-18 本地交付：userauth 服务准备

在既有受管 KEX 成功后，原生调用方可显式协商固定 ssh-userauth 服务并观察 ready，
不改变既有 KEX-only 调用方行为。验收要求真实加密合成协议成功、错误/取消/过期/资源
清理可验证，且全过程没有远端凭据释放或已认证声明。完整用户认证与产品 AC 仍开放。
本切片 C 实现、正式复核、ARM QEMU 加密合成测试已通过；完整结果及覆盖限制见
`design/verification.md` 本日 service 节，不扩大父任务 AC 的完成范围。

## 2026-10-18 当前验收补证

当前 production 修复版独立 fixture 已在恢复的 API30/arm64 开发板上通过精确 JNI
分片 transport / 初始 KEXINIT / KNOWN / EOF 清理方法（1 项，0.202s），两包清理且
正式 APK 不变。该窄关卡不再是 not-run；完整 Android KEX、认证与产品 AC 仍开放，
`implementation_complete=false` 保持。

## 2026-10-17 最新接续验收边界

本轮已实际完成宿主 QEMU/Bionic 的 production native ARM 合成回归，65,838 checks /
exit0，并修复 role admission、banner 分片、completion output drain 和 failed-open KEX
guard 清理；normal 94-task gate 通过。该证据只覆盖 native 合成协议/生命周期；Android
JNI fixture 须重建后在解锁板上另测，真实 managed CAS/vault、userauth 与产品 AC 仍开放。
本轮未安装正常 APK、未使用真实账号，task 继续 `implementation_complete=false`。

## 2026-10-17 当前增量验收边界

新增 JNI 分片 transport / 初始 KEXINIT 输出读回与独立 EOF terminal 回归，仅本地
compile/assemble/lint/签名及代理复核通过。当前开发板 `RUNNING_LOCKED` 拒绝启动
fixture，用户选择保留本地结果，两包/自有挂起命令已清理。本轮 runtime 未执行，
不扩大父任务 AC 的完成范围；`implementation_complete=false` 保持。

## 2026-10-16 G3.8 当前补充：Android ARM/JNI empty-peer EAGAIN

在 production `SshNativeSession.open` 成功并转移 socket ownership 后，独立 fixture
先调用一次 `step()` 对空 nonblocking peer，断言 `bytesRead=0`、
`inputAppended=0`、`readWouldBlock=true`，不把同一 turn 的
`bytesWritten` 错误限制为 0；随后继续 matching KNOWN `verifyHostKey`、
revoke 和 close。当前授权板精确 instrumentation 为 `OK (1 test)`、status 0、
0.11s，fixture 清理完成。该项只证明 bounded EAGAIN + KNOWN host-proof 的 Android
Java→JNI→native runtime，不等于 ARM KEX、认证或父任务完整 AC。

## 2026-10-16 G3.8 当前增量：Android ARM/JNI KNOWN host-proof

独立 `secretsFixture` 现在以同一 OpenSSH wire hostKeyBlob 动态生成标准 managed
`ssh/known_hosts` 行，并在 production `SshNativeSession.open` 成功、view
revoke 之前调用 `verifyHostKey`。在当前授权 `192.168.9.14:5555`
（AIO-3568J、Android 11/API30、arm64-v8a）上，精确
`SshNativeSessionPlatformTest#syntheticNativeSessionHandle` 为
`OK (1 test)`、status 0、0.089s；fixture 包随后卸载且 package/process 无残留。
这只增加 matching KNOWN host-proof 的真实 Java→JNI→native ARM 证据，不等于 ARM packet/KEX、
userauth/typed credential 或父任务完整 AC；task 继续
`in_progress`/`implementation_complete=false`。

## 2026-10-15 G3.7 当前增量

G3.7 已把 native-owned session/key/view boundary 接入生产 library：Java 侧只传 opaque
role/callback/view/session token，native 侧持有 parsed host key、managed-view borrow、
OpenSSH state 和 caller socket。`ssh_view_handle.[ch]` 的 revoke 阻止新 borrow，已有 borrow
在 native close 后 release；`ssh_session_jni.c` 的五个 entrypoint 已真实调用 C API，并执行
owner-thread、bounded clone/wipe、64-slot registry 和 close/destroy retry。

当前增量的可验收范围是 native handle 生命周期，不是父任务完整 AC：host view-handle
回归、normal production link 和独立 `.14` synthetic socketpair 的
`SshNativeSessionPlatformTest#syntheticNativeSessionHandle` 已通过；既有 public-file
view matrix 也单独通过。normal JUnit 为 189/0/0/0，production gate pass，native host
runtime 因 hako 无 qemu 为 not-run，task 仍 `in_progress`/`implementation_complete=false`。
userauth/typed credential、真实 host proof、packet partial I/O/EAGAIN/EOF/deadline、PTY/
forward/service/UI、真实 vault 和外部 SSH 不在本增量的完成判据内。

以下 G3.6 段落保留为历史 Java owner/fail-closed 约束；它不再描述当前 JNI 实现。

2026-10-15 历史 G3.6 已增加 Java/JNI session-owner seam，但不宣称 Android runtime 已通过。
`SshNativeSession` 让 owner event-loop thread 独占 `open/step/verifyHostKey/close/destroy`，
只允许 `cancel` 跨线程；`NativeSessionClaim` 保证一个 callback binding 只能同时拥有
一个 session。failed open 必须清掉 opening claim 且不消费 caller FD、callback 或 view；
close 必须按 native close → callback claim bookkeeping → role → view → native destroy
顺序执行，任一失败可在 owner thread 重试。host-key/fingerprint 只能以 bounded clone
进入 binding 并在调用后清零。

生产 `ssh_session_jni.c` 已被 normal `compileSshFiles` 链接并导出五个 JNI entrypoint，
但因为当前 C client 仍要求借用 `struct sshkey *`/`ctos_ssh_view *`，它们现在全部抛
`IOException` fail-closed；缺少 native-owned handle、view snapshot/borrow 和完整关闭
协议前不得宣称 production JNI/session。fake `NativeBinding` 的 session tests 只证明
Java ownership contract。当前证据为 189/0/0/0 JVM、callback=20、native
`runtime=not-run`、strict compile/lint/三 ABI Release/assemble 通过，APK
`21d16325b242427c2ccfbb7aa9cf42c6ca359700dc7b7f52995a95de06b94e72`；task 仍
`in_progress`，`implementation_complete=false`。

2026-10-15 上一阶段 G3.5 已将 pinned OpenSSH 10.6p1 的完整 PIC source/object closure 与
`ssh_native_client` C caller 接入 normal APK。production archive 固定为 83 个成员，含
PIC PKCS#11/SK 对象并排除 stock high-level caller；native open/step/verify/close 的
ownership 与 managed host evidence contract 已有 cross-link/normal-link 证据。当前尚未
完成 Android runtime/JNI/Java session orchestration、userauth/typed credential、PTY、
forward、service/UI、设备或外部 SSH 验收；task 仍 `in_progress`，
`implementation_complete=false`。

2026-10-15 dedicated native→managed known_hosts handoff 增量已完成：在已冻结
HostEvidence/CAS seam 与 `SshManagedKnownHostsProvider` 之间新增独立 synchronous
one-shot C/JNI/managed receiver。handoff 绑定 exact role/hop/target/connection/host/port/
key type/fingerprint、base/replacement digest、candidate identity 和 bounded body；native
temporary、JNI array 与 managed input 清零，现有 metadata-only CSHR wire 不变。body
安装后仍须经 guarded AppSecrets CAS 才返回 COMMITTED。177 JVM、native client 27、
callback 20、strict compile/lint/Release 通过；APK
`3a6228f4237380dfc2cd98ccd537c16d4c6e81bc2e64e6205cdd6d556af9ded6`。pinned OpenSSH
hostfile issuer 已在 host/fixture seam 接入，但 production caller 尚未接入，task 仍 in_progress/implementation_complete=false。

2026-10-15 前一窄增量已实现 role/hop/target-bound HostEvidence/CAS native↔Java
contract：KNOWN 返回 bounded `CAS_NOT_REQUIRED`；NEW 需要 exact key-type-bound proposal、
用户显式 approval 和 exact `TrustCasWriter` `COMMITTED` 结果。C/JNI 复验 classification、
key type、fingerprint、base/replacement digest，proposal body 不过 JNI。host gate=15、
JVM=166、strict compile/lint/Release 通过。该 seam 仍不是具体 known_hosts file/CAS
provider，也不是生产 OpenSSH caller；缺 provider 的 native client 继续 fail-closed。

2026-10-15 前一生产 caller 边界：pinned source map 证明 `ssh_api` 可支持未来的同进程
incremental caller，但当时没有可编译的 production known_hosts issuer/CAS API。因此
新增 `ssh_native_client.[ch]` 只做 fail-closed admission/step/verifier boundary：严格
校验 role/PID/UID、受管 CLOEXEC/NONBLOCK socket、callback token/environment、deadline
和 bounded buffers，缺 provider 返回 `ENOTSUP`，不创建 OpenSSH client、不进入 KEX、不
触发 JNI/credential。host gate=27；该结果不代表 production caller，task 仍
`in_progress`/`implementation_complete=false`。

2026-10-14 前一窄增量：同进程 KEX-only native launcher 仍仅在独立 fixture/host seam 完成；用户已确认同进程 event loop、当前线程同步附着 JNI callback，以及受管 `config/known_hosts` 文件视图 + exact trust proposal/CAS + typed private-key handoff 方向。当前已实现 bounded managed-provider handoff：role/hop-bound NEW proposal、host proof + KEX proof 绑定的 password/private-key bytes、JNI/native 清零与 cleanup retry。host callback `gate=11`、162 JVM、lint、Release 构建通过，正常 APK 仅含生产 `libctos_ssh_files.so`。具体 file view/CAS writer、真实 OpenSSH event loop/caller、auth/PTY/forward/service/UI、设备和外部 SSH 仍未接入，完整客户端验收仍开放。

2026-10-13 SSH当前增量：Java role/trust/KEX/typed-secret bridge 已完成，bounded native role bootstrap 的 ownership/open/release JNI 已接入正常 `libctos_ssh_files.so`；native 输出清零、跨进程/UID close、stale token、Java registry 配对与失败重试均有回归。150 JVM、lint、host bootstrap 1198/1/1198、固定 KEX native 构建和正常 Release 构建通过；当前 APK `538cae5a…` 包含生产 public-file 与 role JNI。这里只证明 host/fixture seam 和 JNI contract，尚未由生产事件循环调用或接入 native launcher/OpenSSH callback、协议认证/PTY/转发/服务/UI或设备/外部账号；完整客户端仍开放。

状态 in_progress；范围已批准，按阶段实施和验证；尚未完整验收。

## 已确认的后续架构选择

- 2026-10-14 用户选择同进程生产 native event loop；不采用 fork/exec child
  handoff。当前 PID-bound role ownership 可继续作为生产入口的前置约束。
- 2026-10-14 用户确认同进程 callback 采用当前 native event-loop 线程上的同步、
  附着 JNI callback；不得引入 fixture control channel 作为生产 broker。
- 该选择不等于把 fixture transport 升级为产品 broker；Java callback 与 provider
  handoff 必须继续遵守本 task 的 role/host/KEX/secret contracts，才能把 launcher
  接入正常 APK 或真实 OpenSSH 路径。

## 已确认的 provider 方向与当前边界

- **Production host-trust/file/private-key provider**：用户已确认采用受管 public
  config/known-hosts view + exact trust proposal/CAS + typed private-key handoff。
  当前已实现并验证 `SshManagedKnownHostsProvider`/`AppSecrets` 的 managed
  `KNOWN_HOSTS` CAS writer，以及独立 native-facing one-shot candidate handoff；它只
  接受 caller 提供的一次性 exact replacement bytes。pinned native hostfile issuer 和
  真实 OpenSSH caller 仍未接入，缺少 issuer 时必须继续 fail-closed。

## Goal

交付 APK 内 OpenSSH 终端、config 主机一键连接与统一秘密库 SSH adapter，对应 [父任务](../../../10-08-ssh-terminal/prd.md) R1–R10 / AC1–AC9；公共秘密能力归先行底座。

## Requirements

- 客户端随 APK、无 Root 可用，复用终端/xterm/输入和输出能力；SSH 输入/控制键及 ↑/↓ 交远端，无程序专属模式或兼容清单，App/Root 终端不回退。
- Include/单多跳 ProxyJump 及主要 config 行为按父 R6 实现；LocalForward/固定目标 RemoteForward/DynamicForward、默认 loopback/显式对外监听先确认、拒绝不静默降级全部引用父契约。
- 用户通过 SAF 主动导入 config/Include/密钥至公共加密库的受管结构，不持续同步；完整备份含文件/关联凭据/信任，引用/路径/身份及信任预览校验后同代次提交，失败保护旧状态。
- native 使用解锁后的受管临时内存文件视图，不建长期或临时普通磁盘明文副本；文件读取/路径引用/各跳及信任写回以探针验证，不满足回规划。
- 拒绝配置本地命令并定位，无放行开关，保留受管 ProxyJump；未知主机确认/变化指纹拦截，根配置/Include/每跳不允许绕过。
- 登录密码/私钥口令默认不保存，用户可主动选择保存或仅当次使用；SSH adapter 绑定目标/账号/私钥身份和当前库会话/连接用途，各跳独立核验主机/远端认证，不实现另一套 KDF/秘密库/设备包装。
- 共用公共初始化/解锁/秘密库设置/备份、后台保留秒数、改主口令和关闭快捷的本次口令验证、敏感页面保护（BASE-01..15）。库锁定拒绝新秘密操作，已建立 SSH 按父 R8 的前台服务/后台保留/手动 Stop 契约继续。
- 范围/资源/完整验收由父 PRD/design 拥有，此 task 保持同一版本，不重新选择产品行为。

## Acceptance Criteria

父 AC1–AC9 完整作为此 task 的验收；此外集成验证 BASE-01..15：正确/取消/失效/迟到请求、有效会话复用/锁库、新旧库代次、源口令/目标口令完整恢复失败保护、文件身份/引用/信任、关闭快捷保留数据、改口令旧备份语义、共同设置一致及导航不停止 SSH。设备/端点需另获当前授权，未测不标通过。

## Dependencies and Status

先完成并审阅 [统一秘密库/公共认证](../../../10-08-app-authentication/prd.md) 的公共合同和适用独立关卡，再按任务授权开始 SSH 集成。生产 OpenSSH 语义/信任、Android native 实际使用、混合包跨设备恢复后可连接等共同 AC 在接入后联合验收，不要求尚不存在的 SSH 先通过才允许实现它。接口冻结前可协调 native 内存文件访问/Include/ProxyJump 探针；探针不等于业务完成或独立关卡通过。TOTP 是同一基座的独立业务消费者，不是 SSH 的依赖。

HOTP 特化全部撤销，SSH 已确定 R1–R10 范围保留。产品范围/依赖已收敛，版本/许可证/ABI/平台/native 行为仍需技术关卡验证；失败回规划，不静默改路线。设计入口：[SSH 设计](../../../10-08-ssh-terminal/design.md)、[公共秘密契约](../../../../../design/app-authentication.md)，实施入口：[implement.md](implement.md)。原规划已于2026-10-13按用户指令恢复为in_progress，当前继续真实KEX与每跳核验；外部SSH账号/端点仍未授权。

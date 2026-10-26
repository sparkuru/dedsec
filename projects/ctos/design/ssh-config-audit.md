# CONFIG 静态引用与本地效果政策检查

日期：2026-10-23。原 SSH task 的接续增量，前置为[实际清单受管输入](ssh-detached-config-managed.md)。
本轮增加独立 AUDIT 模式，复用固定 readconf 的 keyword/argv/Include 入口，不改变 EFFECTIVE
与 CATALOG 的合同、wire 或兼容性。仍不注册生产 validator/reviewer、不开放恢复或 connector。

## 模式、owner 与输入

- 原 `reserveDetached(boolean)` 仍只表示 EFFECTIVE/CATALOG。新增 `reserveAudit():long`，
  沿用既有64槽及资源空 reservation；Java预分配facade并保存token后才load。
- 复用原CSDI v1、loadDetached、run、usage、cancel、close/destroy与原始<=60s BOOTTIME，
  AUDIT没有Request，也不创建view/库tuple/假alias或用户。非CONFIG正文不进入native。
- 新 `SshConfigEvaluator.prepareDetachedAudit(guard[,binding])`、`audit(maxBytes,maxRows)`。
  AUDIT只允许audit getter，普通catalog/summary与AUDIT互相拒绝；原接口不被悄悄升级为审计。
  token/pin/registry锁外解析/单份受保护存储/失败清理owner/取消attach/最终publish规则全部继承。

## 真实静态边

在同一parser的 `enter_file` 成功定位并验证实际CONFIG子对象时，从push之前的parent
stack读取原object/Include行号。每条边为一基ordinal `(parent,line,child)`，仅递归Include
产生，root不产生边；子文件进入失败则整份审计失败。去重相同triple，保留首次DFS/glob
顺序，最多1024条/次求值，超限失败不截断。路径正规化、SSH目录锚点、类型、glob词法顺序、
缺失、深度8和栈环沿用既有受管解析；不改成宿主FS查询或父文件目录相对语义。

审计遍历所有静态Include，包括未匹配Host/Match分支；不执行任何predicate。含%或$的
Include继续只记录deferred site，不做上下文展开、不推断实际目标。多token同一行的各真实
子对象均记录，重复展开同一triple合并；不同父/行必须保留。

## 所有分支的有限政策

审计在keyword/argv之后、Host匹配与first-value之前执行有限的本地效果/信任绕过政策；
不把逐行成功称作完整OpenSSH语法、值或最终Options验证。下列政策不因未生效、先前值、
IgnoreUnknown或Host/Match选择而跳过；未知/ignored-unknown/编译不支持指令拒绝。编译禁用优先于禁用值：例如固定构建映为oUnsupported的GSS/PKCS11，即使no/none也拒绝，不按原keyword名称绕过。原oIgnore/oDeprecated保持固定parser的无效果忽略，不宣称值语义。

- ProxyCommand/LocalCommand/KnownHostsCommand仅允许与原parser raw command语义一致的
  完整 `none` 禁用值；不能仅看argv后放行quoted-none、inline-comment或附加command。
- PermitLocalCommand/ProxyUseFdpass、ForwardAgent/AddKeysToAgent、X11、Hostbased/Keysign/
  GSS认证与delegation、ForkAfterAuthentication、EnableEscapeCommandline、ControlMaster/
  ControlPersist、Tunnel仅允许原parser明确的禁用形式（ControlPersist只接受原有小写no/false，不套通用strcasecmp）；不允许provider、socket、ask/confirm、
  duration、force、auto等有效值。IdentityAgent/PKCS11Provider/SecurityKeyProvider/ControlPath
  仅允许原parser的none禁用。复用固定版本对应multistate表/大小写规则，不另造宽松别名。
- StrictHostKeyChecking只允许原表映射为YES/ASK的值（含true）；VerifyHostKeyDNS与
  NoHostAuthenticationForLocalhost只允许原表的no/false禁用，拒绝绕过/替代信任路径。
  CanonicalizeHostname只允许原表的no/false；CanonicalizePermittedCNAMEs只允许独占none；涉及宿主DNS的有效值明确UNSUPPORTED。
- Match按固定版本attribute/arity/negation/等号语法检查，exec（含!exec/exec=）COMMAND拒绝，
  localnetwork UNSUPPORTED拒绝；先验证对应grammar/arity再分类，畸形/缺参报INPUT，不改变旧EFFECTIVE分类。不执行Match、不读取用户/环境/接口/DNS，不展开token。
  保留固定all组合规则与sessiontype只支持分隔形式（不扩展sessiontype=）；必须区分attribute与criteria，`Match host exec`/`Match command "exec"`不得因值中词语被误拒绝。
  可在同一TU抽共享语法步骤或增加早于上下文准备的syntax-only分支；不得传假Request调用matcher。

纯multistate族的坏表值/标量缺参或额外参数报INPUT；ForwardAgent的表外socket/path、
AddKeysToAgent除缺首值外，非单一明确no/false禁用值的其余形态（含duration/confirm-time/额外参数），以及ControlPersist的非禁用单值可直接按
EXTERNAL拒绝，不扩展为完整复杂值parser；provider/string缺参/额外参数仍INPUT。raw command
多token是命令语义，按COMMAND拒绝。Match syntax-only还须完整消费原argv，原all分支吞空
lookahead后遗留的参数不能绕过审计；旧matcher/all规则不变。

其他已识别指令只保留原catalog词法扫描，不求其值、Host/Match有效目标、动态Include、raw
path/jump/forward/session展开或私钥/信任语义。危险/非法输入返回既有reason/object/line/errno，
诊断无原文/命令/路径/秘密。原xmalloc系统OOM边界不由本轮宣称可恢复。

## CSCA v1 与 Java 验证

新增只读AUDIT结果wire，big-endian：

```
u32 magic=0x43534341, version=1, flags=1,
u32 catalogLength, edgeCount,
byte[catalogLength] exactOriginalCSCD,
repeat edgeCount: u32 parent, line, child
```

flags只允许且必须1，表示上述有限local-effects政策已执行。原CSCD codec/flags/aliases/
deferred/scannedFiles保持，catalogLength24..70424；edgeCount0..1024；最大82732bytes。
parent/child1..128、不同、line1..16384，无重复triple/尾随。用有界算术核对20+catalogLength+12*edgeCount严格等于wire.length。Java严格先检查整wire字节/行
预算与header/原CSCDheader，先从剩余row额度扣除edgeCount再解码嵌套catalog，之后才分配entry/site/edge对象；输出wire及中间副本finally擦除。
JNI分配/写出后重新检查同一owner/READY/原lifetime；getter失效不能交付历史结果作为成功。

在已有 `SshManifestConfigInspector` 新增一次尝试的 `inspectAudit(manifest,deadline,cancel)`，
复用已有copy/all-root/budget/cancel/cleanup循环，不复制另一套owner协议。原inspect保持只
做catalog；Root保留原catalog，AUDIT时另含不可变audit元数据。Report新字段
`staticIncludeReferencesVerified` 与 `localEffectsPolicyChecked` 仅AUDIT成功为true，原inspect
为false；恒declarationsOnly=true/admissionComplete=false。zeroCONFIG同样无JNI/最终fence，
true仅表示空集合的这两项检查，不代表无身份/信任等其他义务。

每root的真实edge parent/child均须落在输入中的CONFIG，scannedFiles<=CONFIGcount；
**child.path必须出现在该真实parent的直接references中**。从root可达、其他父的reference、
同identity/文件名都不构成替代。仅检查观察到的静态Include边，不把references集合与Include
集合强制相等：其余references还可能指向key/cert/trust，精确typed关联另有待证义务。
未覆盖动态边继续显式deferred，不猜测、不截断、不制造授权。

缺直接引用用固定IOException的专用子类 `ReferenceException`，只带parent/line/child数值；
native政策失败保留原EvaluationException/Diagnostic。错误源ordinal先严格核验再使用，
不得越界访问实际manifest或把非CONFIG当来源。任何root失败拒绝整report；cleanup成功正常destroy，只有清理未完成时才保留精确owner供重试。

所有root共用原1024opens/64MiBreads/16384lines与64MiB复制放大预检；累计保留wire<=1MiB、
aliases+deferred+edges<=4096。CSCA整体只计一次（已含CSCD）；不重复计/解码/保留同一catalog。
原inspect结果预算维持aliases+deferred，旧live/effective/catalog默认资源预算不变。

## 门禁与范围

native真实矩阵验证shared reserve/load/run/audit/usage、原模式隔离、所有分支/first-value政策、
raw-none和Match语法反例、实际edge原ordinal/行/DFS/glob/去重、dynamic未展开、预算、owner/
取消/期限/failed cleanup，以及effects0/capabilities0。原eval/catalog/handle/direct/staged/IO
矩阵重跑，原catalog允许的非法Port/Match示例保持。Java测试验证严格CSCA、分配前结果额度、
实际source/candidate直接引用与非CONFIG/伪造来源反例、all/orphan/noCONFIG、旧inspect不升级、
一份loan/原预算/取消/Throwable/清理重试/最终fence。新增平台fixture仅编译，不安装。

主会话串行strict/ARM/JVM/lint/normal+fixture/APK审计、更新第三方补丁hash/来源记录与spec/
设计/任务/接管/日志；implement限定native与Java文件，checker独立复核。完整typed非Include
引用、身份/私公钥/证书/凭据目标/信任与reviewer仍后续，父task保持in_progress。
本地未提交，无设备/真实库账号/安装/commit/archive/push/dist。

## 本地实现状态

本轮已实现并完成独立源码/原始证据复核：真实ARM13290checks（含direct1262）/effects0/capabilities0，原managed3683/direct1261/eval3736/catalog71923/handle5201与IO426通过；定向124/fresh全量537 JVM全0，normal94全重跑/fixture111、lint与三APK审计通过。仅平台fixture编译，实际JNI/Android锁页未运行；[失败轮、修正与完整结果](verification.md#20261023-config-audit)。完整值/其他typed引用/身份/凭据/信任/reviewer、生产provider/恢复/connector仍后续，父AC继续in_progress，本地未提交。

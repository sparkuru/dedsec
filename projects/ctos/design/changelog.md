# Changelog

## 2026-10-26 前台密码SSH真实Android界面验收

- 用户授权后在指定Android11板完成实际bridge的NEW确认/保存、密码PTY及KNOWN重连。
- 新增真实Flutter控件回归测试，完成建库、表单、完整指纹确认、命令输入与非回显渲染输出、返回和关闭，含敏感窗口保护断言。
- 本轮无产品代码或依赖变更；314份已有代码/runner哈希保持。正式APK准备完成，测试包/请求/转发/临时SSH服务及凭据已清理。
- [原始证据与范围](verification.md#20261026-password-android)。

## 2026-10-25 前台密码SSH连接

- 正式终端入口、受保护连接表单、真实共享库配置/准入与主机键审批，临时密码不落盘。
- 有界DNS/非阻塞TCP、实际peer核对与显式FD接管；真实SUCCESS后晋升同一shell的前台会话权限。
- 取消/后台/锁库/审批与终端交接贯通；原App/Root终端保留。独立服务端测试发现并修复主机键忽略端口的问题。
- [连接合同](ssh-password-connect.md) · [实际检查及未测范围](verification.md#20261025-password-connect)。

## 2026-10-25 用户更正：SSH统一正式主线

- 最新KeyBinding/Account模型成为所有构造/create/重新编码的唯一主线，清理双格式业务API、默认旧writer及versioned测试名称。
- 保护已交付TOTP的窄旧输入读取；换口令/恢复编码正式模型，raw认证来源和effect guards保留。
- 恢复按实际未审阅metadata判断，空库/纯TOTP不再按tag拒绝；后续优先形成可实际使用的密码连接闭环。
- [正式合同](ssh-manifest.md) · [本轮验证](verification.md#20261025-ssh-mainline)。此前双格式安排被本决定取代。

## 2026-10-25 SSH 显式关系与账号清单 v2

- 新增显式plaintext2、不可变KeyBinding/Account及精确类型/全局ID/owner/目标唯一性校验，保留默认v1与旧wire。
- 四处candidate重建及loans/export/rekey保留version/metadata；引用破坏拒绝完整候选。
- preview与bind逐侧拒绝v2（含empty/pureTOTP），保留source-first错误序与旧v1恢复；生产语义provider未启用。
- [合同](ssh-manifest.md) · [实际检查与限制](verification.md#20261025-manifest-v2)。本地未提交，无设备/真实库账号/安装/发布。

2026-10-24：[全部证书事实与签名政策](ssh-certificate-facts.md)已实现CSCF168、三份公开摘要/unsigned数值/实际record来源及现代CA签名政策，保留trust/time/principal/revocation未验证。ARM22239与旧材料矩阵、定向145/全量638 JVM、normal100全重跑/fixture111及三APK审计通过；[实测](verification.md#20261024-certificate-facts)。持久graph/账号、完整信任/reviewer/provider/恢复/connector与父AC仍开放；本地未提交，无设备/真实库账号。

2026-10-23：[全部私钥材料与凭据归属](ssh-private-material-ownership.md)已实现全PRIVATE自签/验签、v1口令唯一owner/phrase审计和password namespace/deferred target。ARM19976/独立pair13812/material7784、定向120/全量613 JVM、normal98全重跑/fixture111及三APK审计通过；[实测](verification.md#20261023-private-material)。仍不提供持久关系图/真实密码账号/完整信任/provider/恢复/connector准入，父AC开放；本地未提交，无设备/真实库账号。

2026-10-23：[显式密钥配对证明](ssh-key-association.md)已实现精确ID/所选口令、证书plain subject及固定domain实签验签，拒绝仅公开字段相等的伪配对。ARM13814/独立材料7784/fresh认证1748333、定向80/全量586 JVM、normal96全重跑/fixture111与三APK审计通过；[验证](verification.md#20261023-key-association)。报告只覆盖明确所选pairs；全库关联authority/覆盖、凭据目标、完整信任/reviewer/provider/恢复/connector与父AC仍开放。本地未提交，无设备/真实库账号。

## 2026-10-23 — CONFIG 显式资源引用

- 新增独立RESOURCE_AUDIT/CSCR，五类显式资源从同一lexer采集，按静态type和真实parent直接references核对；非CONFIG正文不打开/复制。
- 明确保留动态/cwd/key-loader未决，Certificate none不误作禁用，Global字面美元与env展开区别保持；不认证suffix/数学关系/完整信任，旧mode不升级。
- ARM4439含基线/全部旧矩阵/IO426、定向151/全量564 JVM、normal94全重跑/fixture111/lint/三APK审计通过；全部首轮门禁成功。
- [合同](ssh-config-resources.md) · [验证](verification.md#20261023-config-resources)。本地未提交，无设备/真实库账号，完整provider/恢复/connector与父AC继续开放。

## 2026-10-23 — CONFIG 静态引用与本地效果审计

- 新增独立AUDIT模式，复用固定lexer/Include，采集实际静态父/行/子并核对真实parent直接references，不将其他key/trust引用强制等同Include集合。
- 所有分支在Host/first-value前检查有限禁止效果政策，Match无predicate/context；CSCA嵌套原CSCD，原owner/期限/取消/累计预算/cleanup复用，原inspect不升级。
- 修正新helper宏定义顺序、Match early-all余参消费与语法错误优先级、标量分类，保持旧模式与原all/empty规则。ARM13290含基线/旧矩阵/IO426，JVM124/全量537、normal94/fixture111/lint/三APK审计通过。
- [合同](ssh-config-audit.md) · [验证](verification.md#20261023-config-audit)。本地未提交，无设备/真实库账号；完整语义provider/恢复/connector和父AC仍开放。

## 2026-10-23 — 实际清单受管配置检查

- 实际独占VaultManifest loan现可逐个检查全部CONFIG，保留原ordinal、原期限/取消；reserve先发owner，JNI只复制到已拥有的受保护native存储，失败清理可重试。
- 全job累计IO/行数、复制放大与结果预算，新增三JNI；不制造view/Request，不复制非CONFIG或秘密条目正文，报告仍partial-only。
- ARM3683含基线/原矩阵/IO426、定向101/全量514 JVM、normal94全部重跑与fixture111/lint/三APK审计通过。修正测试关联、路径前缀与fixture Include路径，完整语义/恢复/connector仍开放。
- [合同](ssh-detached-config-managed.md) · [实际验证](verification.md#20261023-detached-managed)；本地未提交，无设备/真实库账号/发布。

## 2026-10-23 — 独立 native 配置源

- 固定readconf现可直接读取独占配置输入，保留原root ordinal/期限/取消和CLOSING清理owner；不创建虚构vault/view能力。原live错误码顺序保持。
- 真实专项ARM1261/effects0/capabilities0、原eval3736/catalog71923/handle5201、host IO426通过。fresh JVM483全0，normal94全重跑/fixture111复用同native、lint和三APK审计通过。
- 测试wrapper修为只追踪受管映射；8MiB锁页环境限额和既有缓存依赖离线重建分别记实。不修改生产保护/依赖声明，不把C-only基础当完整语义或恢复准入。
- [合同](ssh-detached-config.md) · [验证](verification.md#20261023-detached-config)。本地未提交，无设备/真实库账号；Java编排/完整provider/恢复/connector与父AC继续开放。

## 2026-10-23 — TOTP 写入与原票据生命周期

- 原origin固定epoch/期限/注册/快照，四类写入校验与候选构造移出核心锁；保留唯一effect claim。
- 预览成功交接后新Request可确认；原capture清理不误撤销，旧epoch/外来core/旧快照、
  取消与并发BUSY保持精确票据所有权。manager不持锁执行核心校验，worker独占seed。
- 定向196/全量483、normal92全重跑/fixture114/fixed77、lint0errors与三APK签名隔离通过。
  [合同](totp-mutation-lifecycle.md) · [验证](verification.md#20261023-totp-mutation)。
  本地未提交，无设备/真实账号；完整语义provider/恢复/connector和父AC继续开放。


## 2026-10-22 — trust CAS 锁外校验与精确延续

- 审批前固定原context/期限/注册/精确proposal；source/candidate独占数组与校验锁外，
  provider关闭可在admission未签发request时取消。保留唯一effect claim与wrapper。
- 最终session→role→原view检查后own-CAS推进epoch；落盘后延续/回执失效锁库并返回
  VAULT_COMMIT_UNCERTAIN。候选digest预计算，事务内不全内容哈希/重读存储。
- 定向202/全量469、normal92全重跑/fixture114、lint与三APK审计通过；新增14个测试。
  [合同](ssh-trust-cas-lifecycle.md) · [验证](verification.md#20261022-ssh-trust-cas)。
  本地未提交，无设备/真实库账号；TOTP mutation/完整语义/恢复/connector仍后续。


## 2026-10-22 — 普通SSH凭据保存/删除锁外校验

- SSH_SAVE/SSH_DELETE保留原context/host授权/注册/期限，一次effect消费；source/candidate
  独立loan及全数组独占候选在core外校验、加密，入参在host回调前复制。
- 最后存储读取在事务前，唯一effect.beforeCommit及wrapper/uncertain规则保持；
  TOTP mutation/trust CAS/own-CAS未迁移。
- 定向152/全量JVM455、normal92全重跑/fixture114及三APK审计通过。
  [合同](ssh-entry-mutation-lifecycle.md) · [验证](verification.md#20261022-ssh-entry-mutation)。
  无设备/真实库账号/提交，Android AtomicFile实际运行不在本轮结论内。

## 2026-10-22 — SSH typed秘密读取与最终受管复制

- 三类秘密读取绑定原context/期限/注册，锁外解密与独立loan校验，一次effect；selected
  admission携带原host/KEX/selection/callback guard，最终有界复制保留取消/锁库排序。
- 统一public role锁序及共享64operation容量检查，保留native attach后的即时取消。
- 定向196/全量JVM444、normal92全重跑/fixture114、三APK签名隔离与库一致性通过。
  [合同](ssh-secret-read-lifecycle.md) · [验证](verification.md#20261022-ssh-secret-read)。
  新增13个实际执行测试方法；无设备/真实库账号/native运行/提交，完整SSH仍未完成。

## 2026-10-22 — SSH public文件锁外读取与精确挂接

- 原context贯穿SSH_FILES admission/effect，真实source与独立validator loan、closure在
  核心锁外；同步公开loan一次claim后锁外交付/返回前复查，原worker统一擦除。
- native lease独立prepare/create/final attach，不替换active view；修复拒绝参数误revoke、
  重入/重放所有权和foreground/getter竞态。原role期限/view与own-CAS精确延续保持。
- 定向178通过；全量JVM431、normal92全重跑/fixture114与三APK审计通过。[合同](ssh-public-read-lifecycle.md) ·
  [验证](verification.md#20261022-ssh-public-read)。其他secret读取/全语义/恢复仍后续，
  实际JNI/Android未执行，本地未提交，无设备/真实库账号。

## 2026-10-22 — TOTP读取原请求与一次披露

- TOTP_CODE/TOTP_LIST绑定排队前原context/epoch/期限/phase；实际source认证、独立loan
  校验与生成移到核心锁外，mixed绑定原注册，pure无需SSH provider。
- 一次消费及opaque read receipt绑定完成状态/原快照/生成窗口，原phase短reservation后
  实际列表/复制仍按当前窗口/锁库顺序复查；准确取消provisional操作与锁外cleanup。
- 定向140/fresh全量JVM410零失败，normal92全重跑/fixture114及lint/三APK签名隔离通过。
  [合同](totp-read-lifecycle.md) · [验证](verification.md#20261022-totp-read-validation)。
  实际Android/Binder/剪贴板未运行，transport持session的继承限制保留；父任务仍进行中。

## 2026-10-22 — SSH bundle 原context与锁外校验

- 审批/排队前固定一次BundleContext，原期限/取消/epoch/validator注册与精确proposal身份贯穿
  admission/effect；接受effect时一次消费，失败不重放。candidate独占全部entry/file数组，
  validator借用独立canonical loan，修改拒绝且全路径擦除；校验/加密准备移到core外。
- 最终存储保留wrapper/claim/uncertain，修复事务内重读AtomicFile和publication后审批撤销
  留下provisional capability的问题；相关合同写入[生命周期](ssh-bundle-lifecycle.md)与宿主spec。
- 最终定向81/fresh全量JVM391/22reports零失败，normal92全重跑/fixture114及lint/三APK
  签名隔离通过；固定native输入再打包后两包SSH库字节一致。[验证与工件](verification.md#20261022-bundle-validation)。
  无Android实际持久化或native/Flutter运行，不设备/真实库账号/提交；其他typed/完整语义与恢复仍后续。

## 2026-10-21 — SSH恢复预览校验生命周期

- 冻结全清单语义义务与锁/取消/期限/失败合同；preview-only校验/审阅及canonical bind移到
  core monitor外，认证临时loan修改拒绝/擦除，保留原admission期限和publication起算的审阅窗口。
- 不可变validator注册、每请求私有preview owner、同名请求隔离、一次metadata交付claim与
  最终commit复查；补齐boundGuard的parent publication fence传递。其他typed/bundle入口仍后续。
- final focused66项、fresh全量JVM379/21reports零失败、normal92全重跑/fixture114及lint/
  三APK签名隔离通过；源码与最终证据独立复核见[验证](verification.md#20261021-preview-validation)。
  新增21项测试含12组paused-stage失效组合；Android运行未执行，native/Flutter未改。
- 全图语义provider和SSH恢复门仍关闭，完整客户端AC继续开放；本地未提交，无设备/真实库账号。

## 2026-10-19 — 实际SSH清单离线材料检查

- 新增无session的native材料解析、共享既有key policy、CSMI及一个JNI入口；Java对实际
  manifest文件和精确关联口令拥有副本，单期限/取消/重入守卫与全路径擦除，报告不可变。
- 材料ARM7784/effects0、安全固定日志4；原catalog71923/eval3736/handle5201、fresh JVM358、
  normal95全重跑/fixture114及三APK签名/隔离通过。完整认证/PTY ARM1741410/exit0通过；失败修正如实记录于
  [验证](verification.md#20261019-manifest-inspection)；生产角色期限未改。
- 报告恒contentOnly=true/admissionComplete=false；CONFIG、身份关联、凭据目标和信任语义
  仍pending，不挂validator或放开SSH恢复。平台方法仅编译，本地未提交，无设备/账号。

## 2026-10-19 — 无上下文SSH声明catalog

- 同一固定readconf词法入口新增显式catalog模式，完整Host argv收集literal声明和首次来源，
  静态managed Include保守遍历，动态token保留未解析位置；不虚构Request或执行Match。
  registry/JNI/Java沿用原borrow/取消/期限/重试清理，新增CSCD codec及两个config入口。
- strict11、目录ARM71923/effects0、原求值3736/handle5201、focused JVM39、fresh full JVM327、
  normal95全重跑及fixture114通过。最终archive对应目录ARM再跑通过，normal77再打包后
  生产库字节与fixture一致，22分配节/三APK签名隔离/八configJNI/19sessionJNI/archive83通过。
  正式源码及证据复核无遗留问题。[实际证据](verification.md#20261019-config-catalog)。
- 声明结果不代表有效配置，生产语义validator/SSH恢复仍未开放，最终计划/connector继续。
  新Android方法只编译未执行；保留本地未提交，无设备/真实库账号/发布。

## 2026-10-19 — 配置求值器 JNI / Java owner边界

- 新增独立64槽opaque registry、标准UTF8 wire、六个config JNI与Java不可变请求/摘要/
  来源诊断。仅借用caller lease；解析中可跨线程cancel，失败open保留cleanup token，
  close/destroy分阶段重试，完整raw Options留native。
- strict、ARM5201/effects0、focused JVM20、fresh full JVM308、normal95全重跑及fixture114
  通过；固定最终输入再打包后normal/fixture生产库字节一致，三APK验签/隔离通过。
  正式源码复核无遗留问题。[实测与工件](verification.md#20261019-config-jni)。
- 新平台fixture只编译未执行；catalog/validator/最终计划/connector继续后续。保留本地未提交，
  不设备、真实库账号、归档或发布。

## 2026-10-19 — 生产native受管配置求值器

- 新增固定readconf独立patch/对象、受管Include和完整Options生命周期，返回有来源的输入
  诊断；无宿主配置/环境/DNS/命令效果。保留raw jump/forward/session，后续必须继续校验。
- 修复固定版本初始化/完整释放不一致、postparse与算法合并来源丢失及跳板解码边界；
  正式复核无遗留问题。ARM3736/effects0、共同链接完整会话1725125、normal95全重跑/
  fresh JVM288、fixture114与三APK签名/隔离通过。[验证](verification.md#20261019-config-evaluator)。
- 仍为native-only，本地未提交、不设备/真实库账号；JNI/catalog/validator/connector与完整AC后续。

## 2026-10-19 — Offered-host 增量完整本地构建补齐

- 恢复原SSH任务，审批服务容量阻塞解除；normal94任务全重跑、fresh Gradle JVM288、
  fixture113任务及lint通过，三APK v2/单signer、隔离target、19JNI/archive83审计通过。
- 旧临时动态ARM运行库已失，未重新访问设备；独立静态NDK/Bionic合成测试专项87,300、
  完整1,724,636项通过。静态链接适配、失败轮、实际hash及动态未重跑边界分别记录。
- 本轮无生产源码修改，复核无具体finding。仍保留本地未提交；生产配置求值/catalog/
  connector及新版Android运行关卡继续开放。[验证](verification.md#20261019-managed-host)。

## 2026-10-19 — Actual offered host key / exact NEW review

- 显式MANAGED native/JNI/Java入口不再要求caller预知服务器公钥；实际KEX offered key经
  原issuer、KNOWN或精确获批NEW/CAS后一次pin，pending guard无提前审批/完成权限。
- 新增有界exact NEW审批邮箱，独立线程决定/取消，保留原role/view/期限/认证及PTY合同。
- native完整ARM1,724,409与专项87,305、宿主fresh JVM288通过；generated JNI19/archive83。
  新fixture源码编译通过；完整Gradle重打包因自动审核服务容量错误未执行，尚无新版APK验签
  或设备结果。[证据、限制及待执行命令](verification.md#20261019-managed-host)。
- config evaluator/catalog/connector/服务仍开放；按用户选择保留本地未提交工作区。

## 2026-10-19 — Session channel / PTY / shell 与现有终端接入

- 冻结SUCCESS-only单session/PTY/shell合同，新增native/JNI/Java六个API、窗口背压、
  分片输入输出、resize、EOF/u32退出状态/close与可重试回收，保留原认证安全边界。
- 接入现有终端host-only authenticated factory/串行控制器；typed生命周期、会话代次
  与独立UTF8解码阻止远端文本伪造退出。SSH方向键交远端，App/Root行为保留。
- 本地normal94项全重跑、fresh JVM270、Flutter227/analyze及隔离fixture113项通过；
  ARM实际合成协议通过。最终工件/hash、首次失败与未测边界见[验证](verification.md#20261019-ssh-shell)。
- 无产品profile/connector启动器、forward/service、真实账号或设备验收推定；父任务继续进行中。

## 2026-10-19 Android/JNI 与私钥认证：已实现并完成独立实测

Java/JNI 已接入密码与私钥认证，支持 Ed25519、ECDSA p256/p384/p521、RSA SHA-2；
未加密私钥和有界加密 OpenSSH/传统 RSA、EC PEM 走同一证明绑定的一次授权。新增 CSASv2
把精确私钥和口令条目绑定到实际远端；修复真实文件视图与角色期限兼容、首次 NEW 主机
写回撤销自身视图，以及最后一次库读取后的凭据交付检查。

最终 fresh JVM244/0/0/0、ARM QEMU1,244,010 checks、normal94任务/lint/Release/v2通过；
开发板四个精确方法各 OK(1 test)，覆盖密码、未加密/加密私钥及真实 NEW/CAS。测试包/进程
已清理，正式应用 hash 未变，未使用真实库或账号。普通 APK89eb6425… 未安装；加密PKCS8、
KBI/MFA、channel/PTY/forward/service/UI 和完整客户端 AC 继续开放。
[完整证据、格式与限制](verification.md#20261019-android-auth)。

## 2026-10-19 — Explicit native password USERAUTH

- Added C-only begin/result APIs using the owned CSAS username and existing selected
  attempt. Passwords are bounded to8192 with explicit NUL rejection, padding64 and
  short wiped plaintext lifetime; no none probe or automatic retry.
- Bounded encrypted steps distinguish SUCCESS/REJECTED/PARTIAL/CHANGE_REQUIRED,
  require request drain/current proof, and discard bounded remote text.
- Added67 real encrypted scenarios, including a discriminating pending-banner
  positive; formal review has no findings. Final ARM785537, normal94tasks19s,
  lint/Release/v2/archive83/sixJNI pass. JVM221 is an up-to-date retained report.
- APK `983f2032…` not installed/published; Java/JNI auth, private-key auth and full
  transport/product acceptance remain open. [Evidence](verification.md#2026-10-19--explicit-native-password-userauth-protocol).

## 2026-10-19 — Receipt-bound selected credential handoff

- Added a one-shot native selected byte request after current KEX/service-ready,
  with owned CSAS identity, private scratch and post-provider proof checks.
- Java verifies the native receipt/cycle/digest and owned claim, prevents selected
  downgrade, and repeats current-state guards at the AppSecrets receiver boundary.
- Fixed JNI pending-exception order and callback finish cancellation/reentry/Error
  cleanup. Thirteen new JVM methods; final focused66/fullfresh221, callback35,
  final ARM323726 checks, lint/Release/v2 and formal review pass; APK `f329056e…`.
- No USERAUTH, key parsing, device/real account access or APK installation.
  Modified selected JNI has no Android runtime evidence; [details](verification.md#2026-10-19--receipt-bound-selected-credential-handoff).

## 2026-10-19 — CSAS native metadata and explicit receipt

- Frozen managed selection now crosses session JNI as canonical bounded CSASv1.
  Native validates role bindings/UTF-8/shapes before ownership transfer and owns
  copied wire plus SHA-256; explicit live-owner receipt read also allows completed KEX.
- Legacy null metadata remains compatible. Selected old bindings fail closed;
  scratch is wiped, failed admission preserves resources, and close remains retryable.
- Fixed new getter direct-adapter-cancel validation and encoder scratch finally scope.
  Focused53/fullfresh208 JVM, final ARM253155 checks, lint/Release/v2 pass; archive83,
  six session JNI exports, forbidden0. APK `bda806ca…` not installed/published.
- No credential callback, USERAUTH or private-key match added. Modified JNI not
  device-executed; [evidence and coverage limits](verification.md#2026-10-19--csas-native-selection-metadata-and-receipt).

## 2026-10-18 — Managed immutable authentication selection

- Added explicit strict UTF-8 username and exact per-role password-entry or
  private-key path/identity/expected-public selection. Adapter freezes it before
  callbacks/session claims and requires the same selection receipt for typed bytes.
- Selected provider bypasses legacy request source; bridge repeats selection and
  existing proof/operation guards at final release, with bounded copy/wipe/close.
  Selected legacy kind-only, passphrase and KBI requests fail closed.
- Twelve new tests; focused46 and fresh full JVM201/0/0/0, lint/Release/v2 and formal
  review pass. Normal91 tasks/57s; archive83/fiveJNI/forbidden0. APK `e6c4d58b…`
  was not installed/published. No C/JNI/wire/actual auth or private/public key-match
  claim; [evidence and coverage limits](verification.md#2026-10-18--managed-immutable-ssh-authentication-selection).

## 2026-10-18 — Explicit native userauth service negotiation

- Production C adds opt-in post-KEX service begin/ready and no-secret callback
  liveness validation. Existing KEX-only/JNI callers retain their stop point.
- Request output must drain before accept; exact/legacy-empty accepts are bounded,
  malformed/out-of-phase packets and service-phase rekey fail closed. Live owner,
  role/view/deadline and the successful current KEX cycle are checked before I/O.
- Final ARM QEMU encrypted KNOWN/NEW-CAS synthetic suite: **246,808 checks / exit0**;
  callback24, independent review, normal94 tasks all rerun, fresh JVM189/0/0/0,
  strict native, Release/lint/v2 and archive83/fiveJNI/forbidden0 pass.
- APK `8cd6da66…` was built but not installed/published. No username, credentials,
  USERAUTH_REQUEST, authenticated state or Java/JNI service API added; direct cycle
  mismatch injection is a documented coverage limit. [Evidence](verification.md#2026-10-18--native-post-kex-userauth-service-negotiation).

## 2026-10-18 — Android JNI transport / EOF verified after board recovery

- Rebuilt independent fixture APKs from the current production fixes; build/lint and
  matching v2 signatures pass (112 tasks). No source changes.
- Exact synthetic JNI method passed on AIO-3568J API30/arm64: **1 test, 0.202s**, including
  fragmented banner / initial KEXINIT readback, KNOWN proof and independent EOF cleanup.
- Both fixture packages/processes were cleaned; formal APK hash stayed unchanged.
  Full Android KEX, userauth and product acceptance remain open.

## 2026-10-17 — Actual ARM QEMU runtime and production KEX fixes

- Fixed deterministic role admission errors, atomic partial identification parsing,
  completion only after local NEWKEYS/EXT_INFO output drains, and failed-open KEX unbind.
- Added current-errno/FD-retention, both-side bytewise identification, 2/64-byte peer
  completion/EXT_INFO, and real FD-exhaustion identical-resource retry regressions.
- Final actual QEMU/Bionic ARM suite passed 65,838 checks / exit0; normal gate passed
  94 tasks. JVM reports remain 189/0/0/0, reused UP-TO-DATE. APK `f3327427…` verifies v2,
  archive remains 83 members and only production SSH library is packaged.
- No device install this continuation. Android JNI is still blocked by user storage
  lock; rebuild fixture APKs from these fixes before retrying after unlock.

## 2026-10-17 — JNI fragmented transport / EOF fixture, local verification

- Added two synthetic production sessions to the existing exact opt-in fixture:
  bounded fragmented identification / initial KEXINIT readback and EOF terminal cleanup.
- Review fixed Android's unavailable `EWOULDBLOCK` constant and nested failure cleanup.
  Fixture Java compile, assemble, lint and matching v2 signatures pass (112 tasks).
- Device runtime remains not-run: the board is `RUNNING_LOCKED` and rejected process
  startup. The user chose to retain local results; owned packages/process were cleaned,
  and the formal APK hash stayed unchanged. Full SSH remains in progress.

## 2026-10-16 — Android ARM/JNI empty-peer EAGAIN boundary (G3.8 follow-up)

- The production session fixture now calls `SshNativeSession.step()` once after
  native ownership transfer and before KNOWN verification. An empty peer must report zero
  bytes read, zero input appended and `readWouldBlock=true__.
- The test intentionally leaves `bytesWritten` unconstrained because the same bounded
  turn may emit the client identification/banner. KNOWN verification, revoke and close remain
  covered by the same production path.
- On AIO-3568J Android 11/API 30/arm64 the exact instrumentation passed `OK (1 test)`,
  status 0, 0.11s. Fixture main/test hashes are
  `79776aad22be067e057fb2abd2a9af74bc365f890de44b1d3ba689c18d9560d3` and
  `fc71429d9e06d33334cc51cbb9fa64a7b8f672f2bb8752922052aac5ec6dc71e`; packages and
  processes were cleaned.

## 2026-10-16 — Android ARM/JNI KNOWN host-proof boundary (G3.8 follow-up)

- The isolated `secretsFixture` now formats the managed
  `ssh/known_hosts` line from the same OpenSSH wire public-key blob used by the
  production session, removing the invalid `AAAA` placeholder.
- `SshNativeSessionPlatformTest#syntheticNativeSessionHandle` calls production
  `verifyHostKey` before view revoke. On the authorized AIO-3568J Android 11/API 30
  arm64 board it passed `OK (1 test)` / status 0 in 0.089s; both fixture packages and
  processes were removed afterward.
- Fixture main/test hashes are
  `1e3a10c709fed7d514a90e247494c61345da6a0f71dd142c17157f34ea4e9de6` and
  `2b9f80678220d89a7a67e23197bd5f85c1eee7543763bfb895da8d31a801f7e7`. This is
  KNOWN host-proof JNI/ARM evidence only; KEX, userauth and full SSH runtime remain open.

## 2026-10-16 — NEW/CAS full-KEX host-proof boundary (G3.8 follow-up)

- The production native caller now drives an empty managed `ssh/known_hosts` through the real
  synthetic OpenSSH client/server packet loop. The host-key callback performs one exact candidate
  handoff, one approved `NEW` evidence result with `CAS_COMMITTED`, then one KEX-complete callback.
- The regression binds candidate/evidence route fields, candidate ID/classification/key/fingerprint,
  replacement body, base/replacement digests, cycle=1 and the callback session digest to the peer
  session ID; it also asserts bounded I/O, EAGAIN/short write, completion and post-completion
  `EALREADY`, with retry-safe cleanup.
- Focused and normal full gates passed: 94 tasks, JUnit `189/0/0/0`, archive=83, three ABI
  Release/lint/assemble pass, APK SHA256
  `7ccac2fb8f705edd761d34574b9cead4b9e2296864dfe5568d2e9b1105fd849f`, and production-only SSH
  native APK topology. ARM runtime remains `not-run` because qemu-aarch64 is unavailable; task
  remains `in_progress`/`implementation_complete=false`.

## 2026-10-16 — synthetic KEX completion and host-proof boundary (G3.8 follow-up)

- `ssh_native_client_test.c` now drives the production native client and an in-process,
  nonblocking OpenSSH server over a socketpair through the real `kex->done` boundary. The
  server pump uses 2-byte fragments and asserts EAGAIN/short-write behavior while the client
  remains bounded by its step budget.
- The regression asserts one matching `KNOWN` host-evidence callback before one KEX-complete
  callback, `cycle == 1`, a nonzero callback session digest matching the peer session ID's
  SHA-256, client completion, and post-completion `EALREADY`. Strict OpenSSH allocator handling
  now also isolates `realloc` macros.
- Focused and normal full gates passed: 94 tasks, JUnit `189/0/0/0`, archive=83, three ABI
  Release/lint/assemble pass, APK SHA256
  `7195a67fad84185714d00cbb2bbc899fbdf788b61f722282fae8b99ae8ba5f69`, and APK SSH native
  topology remains production-only. ARM runtime is `not-run` because qemu-aarch64 is unavailable;
  no device, vault, credential, endpoint, or external SSH was used. Task remains
  `in_progress`/`implementation_complete=false`.

## 2026-10-15 — bounded native NEW candidate/CAS precommit boundary (G3.8 follow-up)

- `ssh_native_client_test.c` now drives the production verifier with an empty managed
  `ssh/known_hosts` body and asserts the exact generated replacement body, candidate ID,
  `NEW` classification, key/fingerprint, base/replacement digest binding, candidate-before-
  evidence ordering, and `CAS_COMMITTED` success.
- A host-evidence rejection regression asserts one candidate abort, no duplicate callbacks,
  terminal `step()`=`EALREADY`, and retryable native close. The production verifier now marks
  every failed host-verification path terminal after rolling back any handed-off candidate.
- Focused and normal full gates passed: native/production host gates pass with
  `runtime=not-run` because qemu-aarch64 is unavailable; normal gate is 94 tasks, JUnit
  `189/0/0/0`, archive=83, APK SHA256
  `7f0bd0f14ec2a5ae9175ee340512acfdeaf0aa76f843834d1a3c87107c97993b`, five JNI exports,
  and no forbidden stock caller. No device, vault, credential, endpoint, or external SSH was used.
  Task remains `in_progress`/`implementation_complete=false`.

## 2026-10-15 — bounded native CERTIFICATE/INVALID host-policy classification (G3.8 follow-up)

- `ssh_native_client_test.c` now uses a generated, signed OpenSSH host certificate
  to directly assert `CTOS_SSH_CALLBACK_HOST_CERTIFICATE`, bounded key/fingerprint
  and equal base/replacement digests, no candidate handoff, and production
  `EACCES` before host evidence.
- The issuer preflights the same in-memory managed body with pinned
  `hostkeys_foreach_file(..., HKF_WANT_PARSE_KEY, ...)`. A parser-invalid line
  produces project-owned `CTOS_SSH_CALLBACK_HOST_INVALID`; it is not an invented
  upstream `HostStatus`. The regression combines a truncated line with a valid
  record, asserts metadata/no handoff, and verifies production `EACCES` with zero
  host-evidence calls. A legal no-match body remains upstream `NEW`.
- The focused native gate passed with production/native gate pass and
  `runtime=not-run reason=qemu-aarch64-unavailable`; no device, vault, credential,
  endpoint or external SSH was used. The task remains
  `in_progress`/`implementation_complete=false`.

## 2026-10-15 — bounded native REVOKED host-policy classification (G3.8 follow-up)

- `ssh_native_client_test.c` now marks a matching synthetic managed
  `ssh/known_hosts` entry with `@revoked`. The direct host-file issuer assertion returns
  `CTOS_SSH_CALLBACK_HOST_REVOKED` with key/fingerprint and equal base/replacement digests,
  without candidate handoff.
- The production verifier rejects the same offered key with `EACCES` before host evidence;
  evidence remains uncalled. The fixture uses only an in-memory managed body, so no real
  endpoint, known_hosts path, vault, credential or external SSH was used. Focused and normal
  gates passed: production/native host gates pass, JUnit `189/0/0/0`, archive=83, APK SHA256
  `df4605a4f41158b600184cb9daac25a8dd61e15697e614d69c4243ff640d25f9`, five session JNI
  exports and forbidden stock caller=0. Native runtime remains `not-run` because qemu-aarch64
  is unavailable, and the task remains `in_progress`/`implementation_complete=false`.

## 2026-10-15 — bounded native KNOWN host-evidence proof (G3.8 follow-up)

- `ssh_native_client_test.c` now places the generated Ed25519 public key in a synthetic managed
  `ssh/known_hosts` object and calls the production `ctos_ssh_native_client_verify_host_key` API.
  The callback fixture returns a complete approved `KNOWN` result with
  `CAS_NOT_REQUIRED`, matching role/identity, key/fingerprint and both digests; the exact key
  succeeds with one host-evidence callback.
- A second generated public key is rejected by the production pinned-fingerprint guard with
  `EACCES`, and the host-evidence callback count does not increase. The focused strict AArch64
  cross-link and subsequent normal full gate passed (`JUnit 189/0/0/0`, archive=83, APK SHA256
  `872e8f438e83217c2a6f7ae44a8a3e7065f7eedbdefe4be64284e7103c81608d`); native runtime remains
  `not-run` because qemu-aarch64 is unavailable. This is not ARM runtime, full host policy, or
  complete SSH proof. Task remains
  `in_progress`/`implementation_complete=false`.

- The same native fixture now separates host-file policy from the pinned guard: it keeps the
  offered key's fingerprint valid, places a different generated key in managed `known_hosts`,
  directly observes issuer classification `CHANGED` and matching digest metadata, then confirms
  production verification returns `EACCES` with zero host-evidence calls and no candidate handoff.
  The subsequent normal full gate passed with JUnit 189/0/0/0, archive=83 and APK SHA256
  `e83b155b017985f048188a4fe3e9e838933e9d3eafda590fc50f75985272746b`; native runtime remains
  `not-run` because qemu-aarch64 is unavailable.

## 2026-10-15 — bounded native SSH step packet/output accounting (G3.8 follow-up)

- `ssh_native_client_test.c` 以 synthetic socketpair 分片发送服务端 identification banner，
  让 production `ssh_api` 生成客户端 banner/KEX 输出；回归确认每步读写不超过
  `step_budget=3`、`bytes_written == output_consumed`、`partial_write` 表示仍有输出排队，
  并从 peer 读回实际 banner/KEX 字节；同时分开覆盖 managed-view expiry 与 client deadline
  的 `ETIMEDOUT` 路径。
- focused strict AArch64 cross-link 与 `git diff --check` 通过；native runtime 因无
  `qemu-aarch64` 仍为 `not-run`，没有设备、真实 vault、凭据或外部 SSH 操作。完整
  packet/KEX、host proof、认证和产品 runtime 仍开放，task 保持
  `in_progress`/`implementation_complete=false`。

## 2026-10-15 — bounded native SSH step error semantics (G3.8 follow-up)

- 修正 `ssh_native_client_step` 在 managed view borrow 失效时无条件改写 errno 的问题：
  过期 view 保留 `ETIMEDOUT`，revoke/invalid view 保留 `EACCES` 或底层精确错误，并将
  client terminalize，后续 step 返回 `EALREADY`。
- `ssh_native_client_test.c` 新增空 nonblocking peer 的 EAGAIN、半关闭 EOF=`EPIPE`/后续
  `EALREADY`、短 BOOTTIME deadline=`ETIMEDOUT` 回归；仍只使用 synthetic socketpair，
  不访问设备、凭据、真实 vault 或外部 SSH。
- focused `testSshNativeClientHost` strict AArch64 cross-link 通过，runtime 仍因容器无
  `qemu-aarch64` 为 `not-run`；随后 normal full gate 的 JUnit 为 189/0/0/0、archive=83，
  APK SHA256 为 `b71b02201717e455c45202e7408c7d490092653f4a56a5bfb1494ac4d08365bf`，APK
  仅含生产 SSH library。task 继续 `in_progress`/`implementation_complete=false`。

## 2026-10-15 — native-owned SSH session handle and Android JNI gate (G3.7)

- 新增 `ssh_view_handle.[ch]`：Java 只持有正整数 opaque token；native registry 拥有
  `ctos_ssh_view`，borrow/revoke 之间有延迟销毁和 stale-token 幂等处理，禁止把 token
  解释成 C pointer。
- `ssh_native_client` 现在在 native 侧解析并拥有 bounded host-key blob，按 fingerprint
  校验后把 parsed `sshkey` 和 managed-view borrow 绑定到 session；close/destroy 保留
  event-loop 顺序和失败重试边界。`ssh_session_jni.c` 接入 64-slot owner-thread session
  registry、五个真实 JNI entrypoint、bounded step result 与输入清零。
- 增加 host `SSH_VIEW_HANDLE_HOST` 回归和独立 Android synthetic JNI 方法。normal gate
  为 JUnit **189/0/0/0**、production gate pass、native runtime not-run（容器无
  `qemu-aarch64`）、strict compile/lint/三 ABI Release/assemble pass，APK
  `dd962567…`；`.14` 上 session handle 与既有 view matrix 各 1 项通过，fixture 已卸载且
  正式包未改。
- 本轮仍不宣称完整 SSH：userauth/typed credential、真实 host proof、partial I/O/
  EAGAIN/EOF/deadline、PTY/forward/service/UI、真实 vault 与外部 SSH 留待后续；task
  保持 `in_progress`/`implementation_complete=false`。

## 2026-10-15 — Java session-owner seam and fail-closed JNI (G3.6)

- 新增 `SshNativeSession` Java ownership seam：owner event-loop thread 独占
  `open/step/verifyHostKey/close/destroy`，`cancel` 可跨线程；callback
  `NativeSessionClaim` 防止同一 callback binding 同时被两个 native session 占用。
- 固化 native-first close 与可重试资源顺序：native close 后记录 callback claim 释放，
  再 release role、public view，最后 destroy native tombstone；failed open 释放 opening
  claim 但不消费 caller FD/callback/view；host-key/fingerprint copy 在调用后清零。
- 将 `ssh_session_jni.c` 加入 normal JNI build，但五个 production JNI entrypoint 暂时
  统一抛 `IOException` fail-closed。现有 C API 的 borrowed `sshkey`/`ctos_ssh_view`
  指针尚无 native-owned handle/view-borrow 协议，故不把 Java token 冒充为指针。
- JVM session seam 定向测试及 full gate 通过：189/0/0/0（其中 session seam 12 项）、callback host=20、native
  runtime=not-run、strict compile/lint/三 ABI Release/assemble 通过；APK SHA256
  `21d16325b242427c2ccfbb7aa9cf42c6ca359700dc7b7f52995a95de06b94e72`，archive=83。
  未执行 qemu、设备、真实 vault、凭据或外部 SSH；task 保持 `in_progress`。

## 2026-10-15 — production PIC OpenSSH closure and native caller boundary

- 将 `ssh_hostfile_issuer.c`、`ssh_kex_guard.c` 与 `ssh_native_client.c` 接入 normal
  `compileSshFiles`；native caller 现在在同一 event-loop owner 上执行受界定的 socket
  read/append/packet/partial-write turn，并把 managed host evidence、candidate rollback
  和 KEX completion 接到既有 callback contract。
- 新增固定 `android-production-openssh-build.sh`。它校验 G1 baseline/tree/source patch
  hash，在临时 build tree 调用 pinned OpenSSH 10.6p1 的 `libssh-pic.a` source target
  以 `-fPIC` 重编全部生产对象，替换项目 hostfile/KEX 对象，并加入 PIC
  `ssh-pkcs11.o`/`ssh-sk-client.o`；不消费旧 fixture object，archive evidence 为 83
  个成员且定义符号排除 stock `ssh_connect`/`ssh_login`/`ssh_userauth2`/`ssh_proxy_*`。
- 修正 native ownership：open 的 admission/setup 失败保留 caller 原 FD 与预打开 callback，
  成功 attach 使用 CLOEXEC duplicate；close 先 callback release，再 KEX unbind/OpenSSH
  free，最后关闭原 FD，callback release 失败保留完整 client 供同线程重试。
- 固定 hako G3 runner `run.aqN1F2bD` 通过（fixture caller 仍不接入 normal APK），生产
  host `gate=pass runtime=not-run`、callback host=20、JUnit **177/0/0/0**、严格
  `compileSshFiles`/三 ABI Release/lint/assemble 通过；APK SHA256 为
  `805fc28679aa56be85fa3be75a1608701273908a02f7fb04dddb717c4e1b601e`。无
  `qemu-aarch64`、设备、真实 vault、凭据或外部 SSH；Android runtime/JNI/Java 会话、认证、
  PTY、forward、service/UI 仍开放。

## 2026-10-15 — pinned OpenSSH caller fixture seam

- 新增仍限于 host/fixture 的 `ssh_pinned_client.[ch]` 与真实 `ssh_api` socketpair 回归：
  caller 绑定 live role、owned `CLOEXEC|O_NONBLOCK` FD、managed view、callback token/
  environment 和 deadline，执行 bounded `recv`/`ssh_input_append`/`ssh_packet_next`/
  `send`/`ssh_output_consume`，记录 partial write 与 EAGAIN。
- verifier 只接受 issuer 的 KNOWN/NEW 并重新比对 role/hop/port/connection/target/host、
  key type、fingerprint、base/replacement digest 和 CAS status；CHANGED/REVOKED/
  CERTIFICATE/INVALID fail-closed。NEW candidate 已被 managed receiver 接受后，host evidence
  或后续检查失败会调用幂等 abort；KEX `done` 后只允许一次 session digest callback。
- 修正 issuer 错误路径：caller 无论 issuer 成功或失败都关闭 caller-owned `fmemopen`，
  避免 native 临时 FILE 泄漏；replacement scratch 改为有界堆分配，socket 入口要求
  `FD_CLOEXEC|O_NONBLOCK|SOCK_STREAM`，protocol/evidence 失败会回滚已 handoff candidate，
  scratch/body 仍按既有边界清零。
- 修正 runner 的 normal-APK exclusion 误报：只检查正式 `compileSshFiles` 输入闭包，
  不把 opt-in KEX guard fixture 分支视为生产接入。
- 固定 runner `run.3PZSbIzM` 输出
  `SSH_HOSTFILE_CALLER_BUILD gate=pass runtime=not-run normal_apk=not-wired`，JVM
  `177/0/0/0`、callback host `20`、native boundary `27`、strict compile/lint/Release 通过，
  normal APK SHA256 为 `67668e34e2bba40359e43e048404ffeb5645629a130f86e86b2d855a170ffff9`。
  没有 qemu/Android runtime，未执行 ARM fixture；production caller 仍不在 normal APK。

## 2026-10-15 — pinned hostfile issuer fixture seam

- 新增 host/fixture-only `ssh_hostfile_issuer.[ch]` 与 C 回归：public view 必须以
  `ssh/config` 为 root 并引用 `ssh/known_hosts`；pinned OpenSSH 10.6p1 parser 从
  caller-owned memory stream 读取，source-owned stream writer 保留上游 known_hosts 格式。
- 非证书 key 沿用上游 KNOWN/NEW/CHANGED/REVOKED；证书仍 fail-closed，但 revoked certificate
  或 CA 先映射为 REVOKED。NEW exact body/digest 只进入既有 one-shot managed handoff，失败
  回滚并清零。
- 固化 `android-hostfile-issuer-build.sh`：approved G1 manifest/hash、patch application、
  C11/Werror、AArch64 PIE link 和 pathname-writer/normal-APK exclusion 通过；结果为
  `gate=pass runtime=not-run normal_apk=not-wired`。没有 qemu runtime、设备、外部 SSH 或
  真实 vault；production caller 仍未接入，task 保持 `in_progress`。

## 2026-10-15 — dedicated native→managed known_hosts candidate handoff

- 在 metadata-only HostEvidence/CSHR 之外冻结 `ctos_ssh_callback_known_hosts_candidate`
  与 `onNativeKnownHostsCandidate`：同步传递 role/hop/target/connection/host/port、key
  type/fingerprint、base/replacement digest、显式 candidate ID 和不超过 `MAX_FILE_BYTES`
  的 exact body；不传 arbitrary path/FD/Java object，也不改变 CSHR wire。
- 新增 `SshKnownHostsCandidateHandoff` 和 managed-provider ownership check。native slot 与
  managed receiver 均为 one-shot；wrong identity/evidence/digest、duplicate/re-entry、
  cancel/close/expiry、empty/oversize body 及 failed/uncertain CAS 均 fail-closed。native
  temporary、JNI array 和 managed input 在同步调用成功/失败路径清零，CAS 仍只有 guarded
  `AppSecrets` update 成功后才返回 `COMMITTED`。
- `SshManagedProvider.writeBack` 先在 proposal body 仍可验证时校验 `CasResult`，再通过
  `finally` 关闭 proposal；无效 evidence、失败/异常和 committed receipt 路径都清理 proposal。
  candidate/proposal 构造失败时的部分 Java body clone 与临时 binding digest 也会立即清零。
- 质量门：JVM **177**、native client **27**、callback host **20**、strict compile/lint/
  Release 三 ABI 全部通过；APK SHA256 `3a6228f4237380dfc2cd98ccd537c16d4c6e81bc2e64e6205cdd6d556af9ded6`。
  普通 APK 仍排除 host-only boundary 和 fixture OpenSSH objects；pinned hostfile issuer
  与 production caller 仍未接入。

## 2026-10-15 — managed known_hosts CAS provider 接入

- 在已冻结的 role/hop/target-bound HostEvidence/CAS seam 上新增
  `SshManagedKnownHostsProvider`：从 `AppSecrets` 精确读取 KNOWN_HOSTS public object，
  绑定 vault UUID/代次/epoch、对象身份、base digest、host evidence 和一次性 exact
  replacement candidate。
- `SshVaultAdapter`/`VaultOperation` 增加 proof-aware trust request/update；
  `AppSecrets` 在最终效果 guard 与旧内容 digest CAS 成功后才允许返回 `COMMITTED`，
  缺 approval、validator、candidate、proof 或发生 stale generation/epoch 均 fail-closed。
- 发现并修复取消与提交锁互等：操作取消先在 vault monitor 外 revoke，再从并发操作表移除；
  回归覆盖 after-claim cancellation/uncertain commit，避免用测试闸门掩盖真实锁竞争。
- 质量门：174 JVM tests、native client 27、callback host 15、strict compile/lint/
  Release 三 ABI 全部通过；APK SHA256 `718c3934…`，普通包排除 host-only/fixture
  OpenSSH objects。仍未接入生产 OpenSSH caller，task 保持 `in_progress`。

## 2026-10-15 — production caller blocker re-audit

- 复核 pinned OpenSSH 10.6p1 source map：`ssh_api` 的 incremental caller API 可用，
  但 Java managed CAS writer 仍只接受 caller 提供的 exact replacement bytes；仓库没有
  可编译的 hostfile issuer 或 native→managed one-shot candidate handoff。
- 保持 `ssh_native_client` 的 `ENOTSUP`/null-client/zero-progress/FD-owner contract，
  不把 fixture `ssh_api` 流程或 fingerprint 猜测升级为产品 KEX。下一输入必须同时冻结
  上游 hostfile issuer、candidate 生命周期/清零/uncertain-commit handoff，以及与 pinned
  object manifest 的同构链接证据；详见任务 [阻塞记录](../.trellis/tasks/archive/2026-10/10-08-ssh-terminal-client/research/production-kex-caller-blocker-20261015.md)。

## 2026-10-15 — HostEvidence/CAS native↔Java contract

冻结并实现 role/hop/target-bound HostEvidence/CAS seam：native callback 传递
classification、key type、SHA256 fingerprint、32-byte base/replacement digest；KNOWN
只产生 `CAS_NOT_REQUIRED`，NEW 必须经过 exact role-bound proposal、显式 approval 和
`TrustCasWriter` 的 exact `COMMITTED` 结果。C/JNI 对 Java 返回的 bounded metadata wire
再次校验；proposal body 不过 JNI，失败/取消/期限竞争会清零结果。新增 JVM/host 回归覆盖
KNOWN、NEW approved、无 provider/approval、错 hop、digest/key-type/classification mismatch
和 CAS failure。host gate=15，JVM **166/0/0/0**，strict `compileSshFiles`、lint、三 ABI
Release 通过；APK SHA256 为
`26747c49c3333eeff65544be9131668699281eaef7736166224b2856106bff06`。在这一前一阶段
还没有具体 managed known_hosts file/CAS provider；本变更记录顶部的 managed provider
记录已补上该写回层，但真实 OpenSSH caller 仍未接入，缺 native-facing issuer/handoff 的 native client
继续返回 `ENOTSUP`。

## 2026-10-15 — Production KEX caller provider blocker

新增 `ssh_native_client.[ch]` fail-closed boundary 与 host-only strict regression。
它只校验 live role、PID/UID、CLOEXEC/NONBLOCK socket ownership、callback
environment/token、deadline 和 bounded step，因缺少可编译的 production known_hosts
issuer/CAS 稳定返回 `ENOTSUP`；host-only gate=27，不调用
OpenSSH/KEX、猜 fingerprint trust 或释放 credential。普通 APK 未纳入该 source 或任何
fixture OpenSSH object。后续 provider/CAS 合同冻结后，才接真实 packet step 与 inbound
NEWKEYS/JNI proof；当前 partial I/O、EOF、cancel 和 close-retry 仍未实现。包含
`testSshNativeClientHost`、callback host、162 JVM、lint、strict `compileSshFiles` 和
三 ABI Release 的 hako 质量门完成，APK SHA256 保持
`28fc98aaf64d1e010cd63a2ba69086b891de6afc7851376ff65c663a8fc0a179`，正常 ZIP 没有
该 boundary 或 fixture OpenSSH object。

## 2026-10-14 — SSH 受管 provider typed handoff 接缝

按用户确认的受管 `config/known_hosts` 文件视图、精确 trust proposal/CAS 与 typed
private-key handoff 方向，新增 bounded managed-provider seam：NEW host proposal
必须绑定准确 role/hop，host proof 与 signed KEX/NEWKEYS proof 后才允许按类型取回
password/private-key bytes；request kind 不匹配、缺 provider、null role、wrong hop
均 fail-closed。JNI 将 Java byte[] 复制到 caller-owned buffer 并在释放前清零；native
失败路径清零输出，cleanup context 延迟到 slot 移除后释放，避免重试 UAF。

主线固定 hako 质量门禁：host `SSH_CALLBACK_HOST ... gate=pass value=11`、JVM
**162项/0 failure/0 error/0 skip**、严格 `compileSshFiles`、`:app:lintRelease` 与
`:app:assembleRelease` 全部通过；最新 APK SHA256 为
`f32bbc83a401c9997a3b901eccc4e2e5966808093ee1a3c17851ad62056df9ea`，正常 ZIP 只有生产
`lib/arm64-v8a/libctos_ssh_files.so`。具体 file-view/CAS provider、真实 OpenSSH
event-loop caller、auth/PTY/forward/service/UI、设备和外部账号仍未接入。

## 2026-10-14 — `.14` 到本机临时 Docker SSH pair 访问验证

用户更正目标为 `192.168.9.14:5555` 后，在本机 `192.168.9.5:2222` 启动一次性 SSH
容器并追加临时 Ed25519 公钥；当前板使用已固定的 Android arm64 OpenSSH 10.6p1
客户端完成 KEX、临时 host key 校验、公钥认证和固定 `printf` 命令，exit status 0。
容器、设备临时目录和本机私钥均已清理。这只是外部 endpoint smoke test，不改变生产
APK，也不代表 ctOS 的 OpenSSH event loop、provider 或完整客户端已接入。

## 2026-10-14 — SSH 同进程同步 JNI callback adapter 契约接缝

按用户确认记录并实现同进程 native event loop、当前 event-loop 线程同步附着
JNI callback：新增 `ssh_callback_adapter.[ch]`、`ssh_callback_jni.c` 和
`SshNativeCallbackAdapter`，将 host-key 分类、KEX 完成证明和 typed credential
回调绑定到 live role 的 PID/UID/deadline、JNIEnv/JavaVM 与线程生命周期。跨线程、
环境不匹配、重入、取消、过期、迟到回调和 cleanup failure 均 fail-closed；native
cleanup 成功前不释放 Java role，失败可重试。生产 JNI 不提供 unknown-host proposal
或 credential provider，因此 NEW host 与 credential callback 仍拒绝，真实 OpenSSH
event loop 尚未调用该 seam。

主线固定 hako 质量门禁通过：host `SSH_CALLBACK_HOST ... gate=pass value=6`，
JVM **160项/0 failure/0 error/0 skip**，`:app:lintRelease` 与
`:app:assembleRelease` 通过，严格 `compileSshFiles` JNI 编译通过；正常 APK
SHA256 为 `fa4a6356bf594cee5245cca24c3faf1af911605c157155488cb24086c34c067e`，
SSH 相关 ZIP 条目仅含生产 `lib/arm64-v8a/libctos_ssh_files.so`，无 fixture/probe/control/KEX
产物。未执行 ARM binary、设备、外部 SSH 或 vault；task 继续 `in_progress`。

## 2026-10-14 — SSH same-process KEX-only launcher fixture seam

在独立 opt-in fixture 中完成同进程、无 fork/exec 的 native launcher admission/teardown seam：只接受 canonical CSRB、live role、当前进程的 client KEX 和固定公开 host fingerprint；admission 重查 PID/UID/deadline/role snapshot，拒绝 trailing/mismatched wire、过期或重复绑定。close 先 native role close，再 KEX unbind；native close 失败保留绑定供重试，并清零局部副本与槽位数据。新增过期 admission、stale retry 与 duplicate close 回归，规范补齐七段 fixture-only executable contract。

主线独立验证当前源码的固定 hako host/fixture/normal checks：最终 fixture run 为 `build/ssh-native-kex/run.CYUJUpCa`，host 输出 `SSH_KEX handshake=27 cleanup=1 gate=27`，前后 manifest 一致，artifact SHA256 为 `ad8c73c8acaaefed7b12f693ec6ea78759974a55384c8e500d74a98b5ef140a8`；普通 APK SHA256 为 `538cae5a32499c9ff0aa4dcbca76a2e6637b6186c4177c1785dfdf86e2f843f9` 且 ZIP 只有生产 `libctos_ssh_files.so`；ARM binary 未执行。该 seam 不代表生产 event loop/auth/PTY/forward/service/UI，也未进行设备、外部 SSH endpoint 或 vault 操作。
该阶段记录时后续生产实现仍须确定进程 ownership、native-to-Java callback transport 与受管 trust/file/private-key provider；本轮已确认前两项并实现同步 JNI adapter 契约，受管 provider 仍是生产接入门槛。当前 `ssh_api` 内存流和 fixture control framing 仅作回归证据，不能直接作为生产 transport。

## 2026-10-13 — SSH 生产桥合同与失败路径撤销

补齐生产侧 Java role/host/credential bridge：每跳角色绑定 owned PID、connection、hop、nonce、public view 和期限；host key 分类与精确未知 key review fail-closed；typed password、key passphrase、keyboard-interactive、private-key release 必须经过匹配的 signed KEX+NEWKEYS proof。缺 KEX 的失败请求也会立即关闭并撤销，新增回归覆盖该生命周期。

固定 hako 环境下 138 项 JVM、lint、正常 Release 构建通过；Flutter analyze/test（221项）、426 项 host 文件视图/1634 次擦除检查及 24/1/24 项 host control 阶段通过。当前 APK `3b82259d…` 只保留生产 public-file JNI。尚未接入真实 OpenSSH launcher/native callback、完整协议/PTY/转发/服务/UI或设备/外部账号，见[验证](verification.md#2026-10-13-ssh-生产角色信任与凭据桥合同)。

## 2026-10-13 — SSH bounded native role bootstrap seam

在独立 opt-in KEX fixture 内加入有界 native role bootstrap，并让 KEX guard 消费 token 绑定的 hop/connection/nonce/binding/public-view/deadline；same-owner/same-hop、nonce、same-hop binding 重放拒绝，distinct hop 可共用 connection，元数据 wire 不承载 argv、shell、路径、FD 或秘密。Java decoder 要求显式 NativeOwnership，不提供默认放行；容量回收修复防止过期/撤销 role 错误占满 hop 限额。

host-only bootstrap **54/1/54**、严格 C、KEX runner/fixed-input 构建、143 JVM、lint与正常 Release 构建通过；新增满 8 槽过期回收并复用旧绑定的回归；APK `8ceacce9…` 仍只含生产 public-file JNI。没有生产 JNI/launcher/真实 OpenSSH callback、设备或外部账号验收，见[验证](verification.md#2026-10-13-ssh-bounded-native-role-bootstrap-seam)。

## 2026-10-13 — SSH production JNI role ownership/open seam

将 bounded role registry 与 canonical wire ownership 校验编入正常 `libctos_ssh_files.so`：JNI `open` 使用 native 当前 PID/UID 生成 role wire，JNI `owns` 重新验证 live token/UID/快照并做 byte-exact canonical 比较；Java 只通过显式 `productionOwnership()` 或 bounded `open()` 使用，不暴露指针、FD、argv、shell、路径或秘密正文。

146 JVM、lint、严格 Android native compile 与正常 Release 构建通过；APK `5fbdfd57…` 仍只有生产文件视图与 role JNI library。尚未接入 native launcher、真实 OpenSSH callback、协议认证/PTY/转发/服务/UI、设备或外部账号，见[验证](verification.md#2026-10-13-ssh-production-jni-role-ownershipopen-seam)。

## 2026-10-13 — SSH native role lifecycle release seam

新增受限 JNI `release(token, ownerUid)`：JNI 要求正 token、当前 UID，native registry 再按当前 PID/UID 查找 live role；stale token、跨进程 close 与 UID 不匹配均失败。Java `SshNativeRoleBootstrap.release` 将 `Issued` 绑定其创建 registry，先做 native cleanup，只有成功后才 deregister Java role；IOException、linkage 或 unchecked callback failure 保留 role 供重试。native 拒绝路径清零 token/snapshot/length 输出，避免陈旧结果被后续调用误用。

post-fix host gate **1198/1/1198**、150 JVM、lint、严格 Android native compile 与正常 Release 构建通过；APK `538cae5a…` 的生产库导出 `open/owns/release`，未安装设备，生产事件循环、OpenSSH callback、协议与完整客户端仍开放，见[验证](verification.md#2026-10-13-ssh-production-jni-role-ownershipopen-seam)。

## 2026-10-13 — SSH 真实KEX完成守卫

在固定上游generic及DH-GEX client实际验签后、incoming NEWKEYS安装keys后加入连接/hop/key/角色绑定守卫，begin撤销旧完成状态；无UI/控制帧setter。27项当前板合成用例通过，夹具清理、正式APK保持；普通release/133 JVM/lint与测试产物排除通过。修复夹具EXT_INFO处理及identification碎片化约定，失败轮保留。产品issuer/认证桥与完整客户端仍实施中，见[验证](verification.md)。

## 2026-10-13 — SSH 原生控制transport

加入只在独立夹具启用的原生sequenced Unix控制通道，绑定每包内核发送者/role/connection与严格顺序；当前板24项、1项0.388秒通过并清理。尚未授予主机或凭据权限，继续真实KEX与每跳桥，见[验证](verification.md#2026-10-13-ssh-原生控制通道)。

## 2026-10-13 — SSH 通用匿名文件桥接与精确撤销

公开文件wire/JNI/匿名锁页视图与真实OpenSSH readconf/glob的闭包访问实现；当前板147例解析政策、最终17例锁库/取消/容量回归通过，133 JVM/lint与426本地native通过，夹具已清理。继续原生控制与每跳认证，完整客户端仍开放；见[验证](verification.md#2026-10-13-ssh-通用文件解析与撤销)。

## 2026-10-13 — SSH 跳板参数与秘密库文件描述接口

真实OpenSSH跳板生成器加入逐项引用及percent_expand处理；当前板127合成用例、1项0.796秒通过，测试包精确清理、正式应用保持。公共库同步文件借用新增私钥元数据且不释放正文，125 JVM/lint通过；原任务继续JNI文件视图和认证桥，见[验证](verification.md#2026-10-13-ssh-受管跳板参数与公共文件桥接)。

## 2026-10-13 — SSH 原生配置政策首批增量

在真实OpenSSH解析/入口/host-key路径加入政策检查，单独构建并实测101公开合成用例，1项0.154秒通过。测试包已卸载、正式应用保持，真实路径/Include、每跳能力与生产provider继续实施，见 [验证](verification.md#2026-10-13-ssh-原生配置政策增量实测)。

## 2026-10-13 — SSH G2 实机回归与清理完成

修复真实OpenSSH `_exit`跳过destructor导致的夹具清理缺口，保留六组及完整回收要求；17秒重新构建/审查/验签后，用户批准的当前板独立UID测试1项4.496秒通过。两fixture已卸载、正式APK保持。继续原任务的配置政策/主机信任关卡，未标完整客户端交付，见 [实测](verification.md#2026-10-13-ssh-g2-实机回归通过与清理)。

## 2026-10-13 — SSH G2 独立上游夹具本地准备完成

G0当前板合成1项通过、G1 ABI兼容编译通过后，新增真实10.6p1上游六组匿名内存文件/单跳ProxyJump夹具。修复严格协议/信号与进程回收边界，独立CLI/Gradle完整1240输入校验及三程序白名单；最终两APK17秒构建/lint、v2验签与来源/许可证检查通过。正常APK排除stale研究输出并保持控制hash。G2尚未设备执行，待确认本轮新增本机loopback范围；正式客户端/生产信任及完整业务验收仍开放，见 [验证](verification.md#2026-10-13-ssh-g2-上游文件夹具本地准备完成)。下方同日G0待授权为先前阶段。

## 2026-10-13 — SSH G0 独立 Android 探针就绪

新增独立fixture-only匿名内存/受管exec探针，分开评估ASharedMemory和raw syscall候选，完整候选/回收门禁及有界固定协议；修复时钟失效/中断/逐流清理和未reap误报cleanup的失败路径。最终8秒构建、lint0错误3既有警告、AArch64/API28/16KiB ELF及v2验签通过；正式包生成后控制构建无probe，错误opt-in明确拒绝。当前SSH设备授权待回复，未运行AppUID/native或完整客户端。固定OpenSSH10.6p1/OpenSSL3.5.9官方研究输入/hash/许可证，新增隔离stock构建runner并启动本地cross-build，不运行产物或修改正式APK；见 [验证](verification.md)。

## 2026-10-13 — TOTP 已提交归档，启动 SSH 原任务

TOTP工作提交566ca51，归档f3762ba，journal74f8324；用户接受的交付边界及所有未测项保留。复用SSH客户端原task进入in_progress，先G0 Android匿名内存/受管执行能力探针，未据Linux研究或TOTP验收标SSH/native/账号通过。

## 2026-10-13 — TOTP 用户验收与提交批准

用户确认当前TOTP效果并批准提交验收、继续已有SSH任务。按已记录实测边界提交/归档，未测平台/账号/跨设备及混合业务继续保留，不据用户反馈伪造测试通过。SSH下一步先Android native内存文件访问关卡。

## 2026-10-13 — 开发板安装与 TOTP 自动验收

- 用户授权当前`.14`安装/自动测试；最终修复版 `3bc762d9…`（32,935,473字节）已覆盖安装并读回一致，真实库/账号保持任务范围外。
- 修复 native 相机失败先于 Dart 前台恢复时提示丢失，以及图片成功预览先于前台恢复时被取消；同请求/epoch/路由最多等待3秒，不渲染等待中的秘密、不续原生期限。新增7项回归，旧实现实际RED、新实现221 Flutter/86定向/analyze通过，独立UID7项原生检查通过，无相机恢复实测通过。
- 加入独立UID真实Flutter与精确UUID自有多QR图片夹具，收紧系统选图资源与结果/列表等待；最终fresh完整真实UI **1项/72.734秒通过**，覆盖系统双QR明确单选、管理/实际Home/锁库/空库清理，两个测试包及自有图片已回收。成功ACK分支本次未触发，跨UIDgrant与图片预览取消未做真实UI证明；完整结果及未测范围见 [验证](verification.md)。复制最初因自动审批拒绝全局覆盖而关闭，随后用户明确允许覆盖；原生合成复制/敏感标记1项6.616秒通过，未读取原内容/恢复/清空；未提交/归档/替换dist。

## 2026-10-13 — 恢复 Authenticator 并实现离线 TOTP

- 用户更正当前开发板为 `192.168.9.14:5555`，同步AGENTS、约束和Trellis验证spec；旧 `.13` 仅作失效说明，不再作为执行默认值。地址更正本身不记安装授权或设备验收通过。

- 复用 `10-08-authenticator`，按用户先TOTP后SSH的顺序恢复为in_progress；复核公共秘密库现状，保留未测平台/联合验收。
- 工作台加入验证器，接公共初始化/解锁/设置；实现账号列表、倒计时、宿主主动复制、重命名/删除，以及相机/图片/手动添加的候选确认。固定SHA1/6位/30秒，不覆盖旧条目，无HOTP/其他平台迁移/剪贴板清理。
- 加入宿主有界离线二维码解码、短期预览、安全相机/读图Activity、会话事件和最终发布guard；只读请求永久命名空间防止长期轮询消耗管理预算或重放写入。固定ZXing及完整LICENSE/NOTICE，更新设计和可执行spec。
- 最终214 Flutter/122 JVM、analyze、lint0错误3既有警告、45真实字体场景、arm64构建/v2通过，APK `c704ff36…`；[验证及未测项](verification.md#2026-10-13-authenticator-totp-本地实现与最终产物)。未安装设备、操作真实账号、替换dist、提交或归档。

## 2026-10-12 — 最终概览文案收敛

本任务全部迁移及截图反馈工作提交`5e67227`完成，已正常归档、清除当前指针；其他任务保持原状态。

- 删除概览摘要DEVICE及Android/运行时间行，保留设备名/指标与详情全部字段；网络/接口/连接副标题统一范围描述，动态接口来源/数量保留独立元数据。
- 用户授权完成后提交当前任务全部相关成果。最终188全量/84真字体场景、analyze/格式及arm64/v2验签通过，APK25bcdf7c…；[验证及既有边界](verification.md#2026-10-12-最终概览文案收敛与提交)。

## 2026-10-12 — 概览截图细节与默认快捷解锁

- 概览四分类均分铺满阅读列并居中；Root状态迁到ctOS旁信息按钮，绿/黄状态带tooltip和可读原因；移除APP状态行和终端选择页绿色眉标题。
- 安全解锁已登记快捷直接尝试、未登记直接主口令，快捷失败后三选择；新请求重试、路由/代次和取消迟到结果保护保留，管理仍要求真实口令。
- 188全量/53秘密库、analyze/格式、Android夹具编译/lint0错误3既有警告、84场景真字体渲染及arm64/v2验签通过；af600c41…已安装AIO-3568J/API30并读回一致。实际快捷调起/Home取消/三选择/重试/主口令空表单取消和AppPTY切换保持通过，成功认证未重测。见 [验证](verification.md#2026-10-12-概览截图细节与默认快捷解锁)。

## 2026-10-12 — 概览设置追加开发板检查

- 用户授权自行验收；AIO-3568J/API30覆盖安装本任务a441d114…，读回一致，旧APK保留。概览四分类、设置/安全及备份认证取消/全局SAF取消实际通过。
- Android隔离UI夹具导航/标题更新到设置/安全，固定fixture窗口/包名/CLICK限制保持，instrumentation离线编译通过。
- 实际共享快照保存/pull被自动审批拒绝，取消并确认未创建文件；随后板出现系统重启/安全锁屏，剩余返回/PTY检查待解锁，未推定原因或修改系统安全。见 [验证](verification.md#2026-10-12-概览设置追加开发板验收部分完成)。

## 2026-10-12 — 概览整合与设置迁移

- 新建并启动 current [overview-settings task](../.trellis/tasks/archive/2026-10/10-12-overview-settings/prd.md)，本轮代码和本地检查完成，设备观感待验。
- 原信息四分类与完整内容直接合入概览设备摘要后，删除网络现场/能力与恢复和独立信息入口；主导航为概览、工作台、终端、设置。
- 删除主面板顶部导出，原选择/预览/保存流程迁至设置/数据导出；完整秘密库管理移至设置/安全，移除工作台旧入口。原安全/权限/采集协议不变，关联刷新/回跳/键盘/返回同步新导航。
- 160全量测试、加强后6设置专项、analyze、12文件格式、60场景实际Flutter渲染及arm64构建/v2验签通过；APK `a441d114…`。未操作设备/替换dist/提交/归档，见 [验证](verification.md#2026-10-12-概览整合与设置迁移)。

## 2026-10-12 — 快捷认证 AAD 时序修复

- 后续用户确认“效果可以”，并授权提交所有相关task文件；提交阶段成果，底座in_progress/其他相关task planning及未测关卡保留，不归档或推送。

- 用户已设置开发板锁屏并授权继续，恢复公共底座active/in_progress；保留10-11暂停为历史，不开始TOTP/SSH。
- AppAuthentication将材料绑定AAD移至系统认证成功、最终请求/epoch/期限检查后及doFinal前，保留认证绑定Cipher/策略与持久化保护。新增固定独立UID公开能力、初始化与自身资源核清诊断，不输出秘密、不把初始化成功当认证成功。
- 同一实机手动空库E2E旧实现失败/修复后1项95.811秒通过；106JVM、analyze、lint及构建/v2验签通过。主包71d00e7c已覆盖安装/读回一致，用户已有库开启/快捷解锁实测成功，结束锁定/快捷已开；隔离包已清理。未提交/归档/替换dist，跨平台及其他关卡仍开放，见 [验证](verification.md#2026-10-12-安全锁屏开发板快捷认证修复与实测)。

## 2026-10-11 — 用户验收反馈与下方退出提示

- 用户报告上一轮实机验收通过，并要求 prompt 出现在下方；记录为用户报告，不冒称 agent 实测或推定设备身份/安装哈希。
- 退出提示改用 Scaffold centerFloat布局槽，位于底部导航/安全区上方，普通浮动Snackbar自动排在其上方；稳定提示槽空闲时零尺寸，即时移除文字/朗读内容。四入口/2秒/返回重置行为保留。
- 16定向/154全量、analyze/格式与 arm64 release构建/v2验签通过，APK `981ef814…`；覆盖两种Snackbar顺序、窄屏大字/横屏/production rail/安全区与实际无按钮点击语义。新位置尚无实机反馈，本轮未安装/替换dist/提交/归档，公共底座继续暂停，见 [验证](verification.md#2026-10-11-退出提示移至下方)。

## 2026-10-11 — 主页双返回退出与公共底座暂停

- 用户要求暂停公共秘密库任务、开始主页双返回任务，并确认四个主入口 / 2 秒；保留底座成果与待测关卡，在 task metadata/mainline 登记暂停，不标完成。
- 四入口根路由首次系统返回提示“再按一次返回键退出”，2 秒内第二次返回退出；切换/覆盖/键盘/后台重置，普通详情/弹窗返回优先。局部提示置于 AppBar 下方，不误清其他反馈或主动停止服务；公开预览构造保持兼容。
- 独立复核用实际矩形断言复现底部提示被浮动 Snackbar 遮住，移至 body 顶部后通过；最终14 定向/152 全量、analyze/格式、arm64 构建及 v2 验签通过。APK `7ad103a9…`，未安装/替换 dist/提交/归档，真实 Android 返回/IME/桌面仍待授权验收，见 [验证](verification.md#2026-10-11-主页双返回退出本地实现与构建)。

## 2026-10-10 — 继续公共秘密库本地检查

- Typed manifest 写入在最终存储 guard 再检查一次操作期限；已到期保留原密文/快捷材料并拒绝重放。SSH 凭据及私钥在最后 host-key gate 后再次核验会话/取消/期限，再调用接收端。
- 新增两项可复现回归，原实现两项失败、修复后106 JVM通过；Flutter analyze/138全量通过，lint0错误/3既有警告。
- 新增独立UID release UI真实快捷认证手动夹具，要求显式opt-in及已有安全锁屏，不读取/输入系统凭证，正确关闭检查包装/alias移除及口令回退。本轮仅本地编译/审阅；实际系统认证和API28/29仍待设备。见 [验证](verification.md#2026-10-10-继续任务本地效果边界修复与手动认证夹具)；任务保持in_progress。

## 2026-10-10 — 登记主页双返回退出旁任务

- 新增独立 [planning task](../.trellis/tasks/archive/2026-10/10-10-home-double-back-exit/prd.md)：主页第一次返回提示“再按一次返回键退出”，连续第二次返回才退出到桌面；记录超时重置、二级页/弹窗/键盘及生命周期验收要求。
- 同步规划与索引；连续返回时长及主页覆盖范围留待实施前确认。本轮仅任务与文档登记，未激活任务、修改产品代码或运行构建/设备验证。

## 2026-10-10 — APK 编译预览入口

- 新增 [`preview.sh`](../preview.sh)：默认或 `build` 调用现有 `hako` 编译最新 arm64 release APK，成功后输出绝对路径及经过 shell 转义的 `adb install` 命令；`--help` 不触发构建。
- 需要 Docker，首次运行可能下载工具链；从其他工作目录也可调用。失败保留非零退出码，不打印成功路径；不自动安装或替换 `dist/`。具体实测见 [验证记录](verification.md#2026-10-10--apk-编译预览入口)。

## 2026-10-10 — Bridge 生命周期及独立 UI 验证包

- Bridge 关闭、旧后台回调和迟到 picker 不再操作已关闭上下文；0秒锁定清理旧预览/秘密材料，保留当前锁定 epoch 的新管理请求。新增6项生命周期 instrumentation，配置 B 实跑通过，53.408秒。
- 修复后104 JVM、Flutter analyze/35定向、lint与 release/测试包编译验签通过；未变138全量沿用此前结果。主包 `ca85ec00…` 安装读回一致，生命周期自有资源及默认测试包已清理，主 App 库未创建、后台30秒保持。
- 增加显式opt-in独立UID release UI包，自身Application关闭自己的Root自动启动，测试源排除旧授予生产包的组件。修正真实输入焦点/广告动作、lazy列表锚点、活动系统窗口、primary来源标题、新目录自动导航和原生文件行边界。最终83e完整空库UI/SAF 1项235.79秒通过，成功恢复验证新UUID/nonce及完整认证manifest；此前测试错误断言和驱动失败逐版保留。测试文件/空叶目录/授权/两包精确清理，生产ca85/公开尚未创建/后台30保持。
- 成功系统认证、API28/29、未测真实存储故障及业务接入仍开放。见 [本轮验证](verification.md#2026-10-10-配置-b-追加bridge-生命周期与独立-uid-ui-探针)；不替换 dist、提交或归档。

## 2026-10-10 — 真实存储导出与路径标识复用修复

- 新 final 可在 old 移到独立 rollback 后复用历史路径 ID；Journal 按当前角色、阶段及元数据判断，不拒绝合法复用。已确认不存在的角色先持久移除，再恢复旧名，防止过期引用误指已恢复旧文件；已验证 final 始终保留。
- 新增 3 JVM/4 Journal 仪器回归及测试 APK 独立系统树 picker、私有库/journal、真实 provider 六阶段。修复测试回调跨 UID 匿名类反序列化，补精确撤权和崩溃目录定位。
- 104 JVM、配置 B 17 平台与真实本机 provider1六阶段通过，含两种中段写入主动注入故障的清理/旧内容恢复；此前未变138 Flutter 保持通过。主 APK `a309bd9d…` 构建/验签/安装读回一致。清理辅助检查单列，不计产品测试。
- 全部本轮测试文件/包/授权精确清理，仅保留新建空 ctOS parent；用户库/账号/安全设置未变。成功快捷认证、API 28/29、完整生产导出与进程死亡/云 provider、SSH 接入仍待验收，不提交/归档或替换 dist。见 [验证](verification.md#2026-10-10-配置-b-追加真实目录与路径标识复用)。

## 2026-10-10 — 配置 B 安装与隔离实机验证

- 当前主 APK `6c0f00e6…` 已安装 Android 11/API 30 板，设备读回哈希一致；生产源码/APK 未变。
- 修复测试 provider 冷启动授权及 instrumentation 包名/UID 上下文，增加固定 bootstrap 和三项平台夹具；13 项实机全通过，无跳过。生产 manifest 未增加权限。
- 实际敏感截图/录屏/最近任务保护及取消后恢复已验证；独立 Android 加密管理、AtomicFile 和 fixture SAF 各按实际范围记录。无安全锁屏设备只验证快捷不可用。
- 测试包/临时文件已精确清理，生产库仍未创建、设置保持。成功认证、API 28/29、真实存储 SAF 和 SSH 接入仍开放，未提交/归档或替换 dist。见 [验证记录](verification.md#2026-10-10-配置-b当前授权设备验收)。

## 2026-10-10 — 继续补齐公共文件图与导出/恢复审阅

- 新增 native-only SSH 文件图 ADD/REPLACE/DELETE 事务、不可变完整 proposal/旧对象 CAS/exact approval 与 validator guard，复用共同密文/会话/原子提交；61 项 JVM、lint 0 错误及仪器包编译通过，不代表生产 SSH 语义或设备验收。
- 统一 SSH 受管闭包与公共 v1 上限，修正底座与完整客户端互相等待的验收循环；独立公共合同/平台关卡先验证，完整真实恢复和连接行为在业务接入后联合验收，目标不删除。
- 用户确认同名导出覆盖提示和失败本次部分清理；已实现新文件暂存/读回、精确覆盖确认、分角色提交/失败补偿和恢复回执。恢复导入补齐来源/目标 SSH 信任审阅与批准绑定，纯 TOTP 覆盖已有 SSH 也需要审阅；生产 mixed 缺正式提供者仍拒绝。journal 持久读回/恢复身份、取消/缓存回执及坏记录边界经独立修复复核。
- 最终 101 JVM、35 定向/138 全量 Flutter、analyze/lint 无错误（5 既有警告）、10 隔离 instrumentation 编译及实际窄屏大字渲染通过；arm64 APK `6c0f00e6…` 构建/v2 验签通过，详情见 [验证](verification.md)。未安装/替换 dist/提交/归档，平台与业务联合验收仍开放。

## 2026-10-10 — 用户撤销验证码剪贴板清理

- 用户明确要求 ctOS 只提供 TOTP，验证码清理由用户自行处理；取代 2026-10-09 默认 30 秒可调清理约定。
- 移除公共设置/模型/桥接中的清理时长、状态及偏好，删除复制 ID/跟踪预留和合成清理竞态测试；保留用户主动复制/敏感标记，后台库解锁超时不变。不读取、监听或清空剪贴板，不增加相关权限、服务或清理入口。
- 同步 BASE-14/15、AUTH-06/12 及验收/实施计划、领域入口、代码规范和主线。剪贴板清理关卡关闭；其余 Keystore/隐私/SAF/SSH 关卡保留，改动后的实际验证及 APK 见 [验证记录](verification.md)。未安装、提交或归档。

## 2026-10-10 — 公共秘密库实现与本地检查

- 新增主口令/Argon2id/AES-GCM 密文库、有界混合 manifest、进程共享会话、系统认证绑定设备包装、管理操作真实口令验证及单一 Flutter 设置/解锁/敏感路由流程。主线程快速锁定、一次性请求/效果核验、旧回调/提交后锁竞态、设备包装材料与快照分别绑定已纳入检查。
- BC 固定 1.86 并打包官方 LICENSE；新增宿主/前端可执行规范及索引。本地最终 46 项 JVM、131 项 Flutter、analyze/lint 无错误（lint 5 既有警告）、4 项恢复仪器夹具编译通过。实际提交阶段取消统一不确定结果并锁库，UI 保留回执待确认；构建和完整边界见 [验证](verification.md)。
- OpenSSH 10.0p2 Linux 原生研究六组验证通过，未纳入生产客户端；生产 SSH 语义校验/文件图业务入口仍后续接入。真实平台、严格 SAF 失败输出保证和剪贴板策略待确认，自动清理未启用。未安装、提交或归档。

## 2026-10-10 — 批准公共秘密库先行实施

- 用户在最新完整规划摘要后回复“批准”。启动公共底座子任务，前置协调 SSH 无明文磁盘文件访问探针，TOTP 与 SSH 完整接入仍依赖底座验收。
- 更新任务状态/批准记录、设计入口和主线；Android 宿主、Flutter 公共 UI 与隔离探针分别推进。当前未获真实设备安装或账号操作授权。
- 实施前四任务产物及 48 条真实上下文门槛通过，hako 工具链可用；产品代码/算法/平台/native 的结果另见后续实际检查，不由任务启动推定验收通过。

## 2026-10-10 — 当前规划与底座先行进度核验

- 四 task 均 planning / awaiting_final_review，公共秘密库及配套先交付验收，TOTP 与 SSH 分别接入；SSH 父 task 管理需求/集成，TOTP 页面不是 SSH 的依赖。native 文件访问可行性探针宜前置协调，当前未运行。
- 完成 HOTP 活动设计/实施清理和 PRD 收敛核验；原任务 ID/树、剩余需求编号与 SSH 代码证据锚点保留。21 份 Markdown/144 个本地链接、48 条真实 JSONL、JSON 状态与 git diff --check 通过，仅文档检查，无实现/构建/账号/设备操作。

## 2026-10-09 — 撤销 HOTP，收敛确定范围及底座先行依赖

- 用户要求移除 HOTP 的全部设计与实现，保留剩余确定范围。删除当前公共/验证器 PRD、design、implement 与 JSONL 中的计数 schema/事务、发码/校准/恢复/并发验收和实施步骤；保留历史决定作追溯，历史 HOTP 记录全部被本次撤销取代。
- 当前为离线 TOTP（相机/图片/手动、条目/时间码/复制）、ctOS 自身加密备份及既定公共秘密配套与 SSH 范围；HOTP、其他平台迁移、云同步均排除。未确定 TOTP 算法/参数扩展不作为必交付范围，不支持组合明确拒绝。
- 明确公共秘密库及配套先实现并验收，TOTP 与 SSH 随后分别接入，TOTP 页面不是 SSH 的依赖；SSH 父 task 管理需求/最终集成。native 无明文磁盘文件读取可行性探针宜在接口冻结前协调验证，实际集成仍依赖底座验收。
- 四 task 保持 planning / awaiting_final_review，ID/任务树保留；PRD 收敛并更新当前设计入口/主线。仅文档检查，无产品代码、探针、算法实测、构建、设备或账号操作。

## 2026-10-09 — HOTP 主动发码与计数持久顺序确认

- 用户确认 HOTP 点击“生成下一码”才推进计数、本次码重复查看/复制不递增，先持久 nextCounter=C+1 再显示 HOTP(C)。刷新/前后台不自动生成，锁定/重启清理临时代码，不回退已提交计数。
- 提交前失败不发布，提交后显示/复制失败、崩溃或未使用均消耗计数；公共事务/条目版本支持并发、幂等与一致快照，正常更新不失效库会话/包装。需求/验收/实施计划移除已答发码交互问题。
- HOTP 恢复旧包/多设备提醒与手动向前校准（再验证主口令）作为待批准候选保留；仍 planning，仅文档检查，无计数/算法实测、实现/构建/设备或账号操作。


## 2026-10-09 — 首版范围明确为 TOTP、HOTP 与 ctOS 加密备份

- 用户明确纳入 HOTP，并排除其他平台专有迁移；标准 otpauth 相机/图片/手动添加及 ctOS 自身完整秘密库跨设备恢复保留。新增 AUTH-14/15、AC-12 与公共 BASE-16，原 TotpAdapter 规划名统一为 OtpAdapter，两个模式共用主口令/库/认证/敏感复制。
- 核对 RFC 4226 及 Key URI Format 的 HOTP 计数、必需 counter 和服务重同步语义；存储新增类型/无损计数/条目 revision，加密事务和导出/改口令一致快照列技术契约，正常发码不换库授权代次/使设备包装失效。
- HOTP 主动“生成下一码”、先提交计数再展示、当前码重复复制不递增及提交后失败不回退为待批准候选；旧备份/多设备计数恢复策略仍需产品决定，不能把密文认证通过视为计数新鲜性或服务必接受。
- 同步公共/业务/SSH 规划、设计主线、索引与状态；仍 planning，仅资料/文档检查，无实现/计数或算法实测/构建/设备或账号操作。


## 2026-10-09 — 首版支持手机图片二维码读取

- 用户确认首版支持从手机图片读取二维码，新增 AUTH-13/AC-11：系统选择器只读所选图、宿主离线有界解码，共用相机标准 otpauth 校验/条目确认；多码明确选择、不自动整图加入，原图保留，不扫描未选图片或执行码内容。
- 资源字节/像素/候选/耗时上限、无种子确认投影、会话/请求/代次提交检查、取消/锁定/覆盖/迟到拒绝和临时内存/URI 使用授权回收已列规划；扫码库/模型随 APK，不依赖首次下载/云识别，相机拒绝可用图片/手动。
- 同步 Authenticator 需求/设计/计划、公共跨任务审阅状态、设计主线与四 task 备注，移除图片范围未决项；额外 HOTP/旧验证器迁移范围继续收敛，仍 planning，无实现/构建/图片识别实测/设备或账号操作。


## 2026-10-09 — 验证器工作台入口与共同秘密库设置确认

- 用户确认“工作台 → Authenticator”独立业务入口，以及验证器/SSH 共用的一个秘密库设置页；公共 BASE-15 集中主口令、快捷解锁、后台解锁与验证码复制两项独立秒数、加密备份。
- 公共流程拥有首次创建/导入/解锁与原业务返回，取消无半成品；导航不替代库/管理授权，不停止已建立 SSH 或给普通页面加登录墙。验证器为 Flutter/Android 业务页面，公共设置实现唯一，不作为普通 Python 工具执行。
- 同步公共/Authenticator/SSH 规划、验收、实施计划及四 task 状态备注，移除入口未决项；仍 planning，本机图片扫码等额外范围继续收敛，无实现/构建/设备或账号操作。


## 2026-10-09 — 验证码敏感复制与可配置尽力清理纳入首版

- 用户确认验证码复制标敏感、默认 30 秒后尽力清理、时长可调，仅清仍属本次写入的 clip，保留用户后来新内容。公共 BASE-14/helper 拥有敏感标记/复制 ID/单调期限及独立设置，TOTP 检查有效会话后主动生成/复制；普通输出复制不变。
- 复核 Android ClipboardManager 焦点限制及无条件 clearPrimaryClip 接口，无法核验本次身份或排除竞态时跳过，回前台再核验；跨应用核验/清理竞态列技术探针，不能用字符串相同/宿主串行代码证明不误删，不满足要求时回规划。只持久化设置，不借明文代码落盘或保活补清，不承诺进程退出后准时清理。
- 同步公共/Authenticator PRD、设计、实施计划与四 task 备注/主线，移除剪贴板产品策略未决项；仍 planning，仅文档检查，无实现/密码运算/构建/设备或账号操作。


## 2026-10-09 — 敏感页面截图、录屏及任务预览保护纳入首版

- 用户确认验证码/口令输入等敏感页面禁止系统截图/录屏、隐藏最近任务预览，普通页面保留截图能力。公共 BASE-13 负责 Android 安全窗口与 Flutter 路由/对话框 owner 协调，TOTP/SSH 敏感呈现共用。
- 记录先保护再展示、先移除秘密再释放标记，嵌套/迟到/系统 UI 返回不得误清其他敏感 owner；后台解锁宽限不允许预览显示秘密。API 28/29、30+ 和目标 ROM 需分别验证，不以 FLAG_SECURE 设置或 mock 宣称实机通过。
- 同步四 task 规划/状态、验收/实施计划和设计主线，移除截图策略已回答问题；剪贴板候选标记/清理策略仍待决定。仍 planning，仅文档检查，无产品实现、构建、设备或账号操作。


## 2026-10-09 — 关闭设备快捷解锁也验证主口令

- 用户确认关闭生物识别前重新输入主口令；统一 BASE-12 使用本次输入真实认证解密当前库，生物成功/已有会话不能替代。错误/取消/提交前失败保留原状态，成功仅停用本设备公共快捷路径、清理包装/专属密钥并锁定，TOTP/SSH 密文库与主口令路径保留。
- 关闭授权绑定库代次和设备材料版本，拒绝并发/迟到旧回调；先持久化停用再清理不可逆密钥，提交后清理失败或中断不复活旧快捷。不修改系统生物/锁屏登记、其他设备或已完成认证 SSH 的 Stop 契约。
- 同步四 task 当前规划、验收/实施计划及主线，移除关闭快捷验证未决项；仍 planning，仅文档检查，截图/剪贴板等业务细则尚待收敛。无代码/密码运算/构建/设备或账号操作。


## 2026-10-09 — 首版包含统一秘密库更换主口令

- 用户确认首版包含更换主口令；公共 BASE-11/设置入口验证旧口令、确认新口令后以新盐/密钥/nonce 对完整 TOTP/SSH 库和受管文件重加密、读回认证/一致性校验并原子切换，不改变 TOTP 种子、SSH 远端密码或私钥自身口令。
- 提交前失败/取消保留旧库及设备包装，写入/提交中断后只能恢复完整旧或新代次；成功后旧会话/包装失效。快捷重登记失败保留新库并回退新主口令，历史备份继续用旧口令。
- 移除四 task 当前规划中的首版改口令未决项，同步验收/实施计划及主线记录；仍 planning，仅文档检查，无产品实现、密码运算、构建、账号或设备操作。


## 2026-10-09 — 管理操作真实认证解密与导入信任规则

- 用户确认导出/覆盖/改主口令必须重新手动输入，并要求输入真正用于解密及框架上的信任判定。公共 BASE-10 明确 KDF/认证解密、认证前不消费明文、认证后校验内容、预览确认、原子提交及效果边界的操作/来源快照/目标代次检查；生物识别或已解锁会话不能代替。
- 来源包口令错误/篡改/损坏/截断时拒绝采用内容，统一提示口令错误或文件损坏，保留原库及设备快捷材料。已有目标覆盖另验证当前目标口令，新设备空目标只验证来源；解密成功不自动证明发送者、内容新鲜性或 SSH 主机身份，信任仍单列审阅/核验。
- 同步四 task 规划/状态与设计入口，移除管理再次验证的已回答问题；首版改口令入口及业务细则继续收敛。仍 planning，仅文档检查，无产品实现、密码运算、构建、账号或设备操作。


## 2026-10-09 — SSH 文件纳入统一加密保护与迁移

- 用户确认 SSH config/Include、私钥/关联公钥或证书及主机信任记录与保存凭据一起进入完整加密备份，强调保护这些实际内容。公共库扩展类型化加密文件对象/引用图，受管日常持久副本也密文保存，不只加密口令或导出。
- 同步公共 BASE-09、SSH R7/R10/AC7/AC9 及两个业务接入规划；导入完整验证引用/路径/身份、预览覆盖并单独审阅信任后同代次提交，失败/取消保留原库，指纹变化和禁止执行配置规则仍生效。
- 查阅 OpenSSH 文件入口和 Android 内存/FD 官方文档，列 native 临时内存视图、Include/ProxyJump/信任写回兼容为优先技术关卡；不宣称已可用，不静默降级为长期明文目录。仍仅规划，无代码/密码运算/构建/设备或账号操作。

## 2026-10-09 — 统一秘密库后台保留时间可配置

- 用户确认前台跨功能共享有效解锁会话、锁屏/手动锁立即锁及进程重启锁定；后台解锁保留时间由用户选择秒数，默认 30、0 表示立即锁定，TOTP/SSH 共用一项设置。
- 同步公共权威契约、四 task 的需求/设计/实施计划与状态备注，移除会话策略已回答的未决问题。记录单调截止时间、期限边界/回前台/受保护操作检查及配置变化不复活旧会话；已建立 SSH 连接不因锁库断开。
- 仅更新规划，无产品实现、运行时测试、构建或设备操作；备份资源和管理操作等范围仍待收敛，未开始实施。

## 2026-10-08 — 统一秘密库规划取代分业务凭据实现

- 用户明确要求把 Authenticator 的主口令/便携加密库/可选设备快捷/迁移模型用于 SSH，降低理解与维护成本。现有 app-authentication task 扩展为公共统一秘密库，TOTP/SSH 只实现业务 adapter；没有新增平行秘密底座 task 或更改任务树。
- 同步四 task 的需求、设计、实施计划、元数据、真实上下文和设计入口。SSH 原设备-only 存储/不迁移及独立每连接系统弹窗规则被统一库会话模型取代，目标/私钥身份绑定、主机核验、native 秘密通道及客户端/转发/后台 Stop 契约保留。
- 统一库备份可带 TOTP 与显式保存 SSH 密码/私钥口令；SSH config/私钥文件/信任打包、库会话共享/锁定尚待收敛。旧完整审阅候选失效，全部仍 planning；仅文档检查，无产品代码、密码运算、构建、账号或设备操作。

## 2026-10-08 — 第三方 Authenticator task 规划

- 按用户要求创建独立 `10-08-authenticator` planning task；仅规划自有第三方 MFA 验证器，没有代码、构建、安装、真实账号绑定或登录。
- 建立 [领域规划入口](authenticator.md) 及需求/技术设计/实施计划初稿，研究标准 TOTP、服务官方声明及 Shell/Root 场景密钥边界。用户已确认离线、主口令初始化、加密导出/导入覆盖、可开启关闭的本设备快捷解锁；导入成功后重新登记设备材料，失败保留原库。对照公共认证和 SSH task，采用公共系统认证底座、业务秘密 owner 与会话隔离；当前不改 SSH 设备绑定凭据/不迁移契约，全 App 统一秘密范围及锁定等细则待收敛，尚未完成最终规划审阅。
- 本轮验证仅针对 task 元数据、文档链接与变更范围，见 [规划检查](../.trellis/tasks/archive/2026-10/10-08-authenticator/check.md)；不代表算法或服务兼容实测。

## 2026-10-08 — SSH 终端 task 规划

- 按用户要求建立 SSH 终端 planning task：APK 内客户端、OpenSSH 风格 config、主机一键连接及现有终端交互复用。
- 已写 PRD、技术设计和实施计划初稿，补充配置兼容、认证/指纹、会话隔离、生命周期与验收矩阵。用户选择兼容范围 B：Include / ProxyJump 及主要功能参照 OpenSSH；存储方式 A：用户主动导入到内部私有目录、不持续同步；首版拒绝配置驱动本地命令，无手动放行；密码/私钥口令允许用户选择长期加密保存。主方案 native OpenSSH，用户选择生物/系统锁屏凭证验证，并要求认证成为 App 公共能力；已拆公共认证底座/SSH 接入子任务，SSH 后台保留及手动 Stop 已确认，规划独立前台服务及通知 Stop，保持三个终端的操作心智模型统一；config 基础 TCP 转发已确认纳入首版（LocalForward / 固定目标 RemoteForward / DynamicForward），补充数据面、失败/Stop 回收验收；监听策略已选择默认仅本机、显式对外监听连接前展示范围并确认，绑定会话/配置且拒绝取消；SSH 输入及 ↑/↓ 已明确交远端，不追加远端程序操作或模式；撤销特定程序/全屏支持的范围与兼容验收建议，改为具体输入输出/窗口事件检查；config 禁止绕过主机指纹核验已确认；已整理完整 PRD/设计/实施顺序、工程界限及技术验证关卡，等待最终审阅，尚未开始实施。
- 仓库/官方文档研究及本地文档检查记录见 [SSH 规划入口](ssh-terminal.md) 与 [规划检查](../.trellis/tasks/10-08-ssh-terminal/check.md)。本轮无代码、构建、安装或设备登录。

## 2026-10-06 — 显示文案精简

- 顶部移除 `SYSTEM OBSERVATORY`，概览标签由 `DEVICE / 设备观测` 简化为 `DEVICE`。
- 静态分析、103 项 Flutter 测试及修改文件格式检查通过；用户追加要求后已构建 arm64 release 包 `b7e67386…`，签名与校验和通过，未复验设备。详见 [验证记录](verification.md#2026-10-06-两处显示文案精简)。

## 2026-10-05 — 全 Flutter 界面与交互优化

- 新增共享语义主题和展示组件；统一中文/数字排版、状态色、间距、48 dp 主要触控及控件边界对比。900 dp 起侧边导航保留已有内容状态，概览指标按宽度与文字缩放适配。
- 完善设备、网络、接口、连接、全部工具参数/结果、文件管理、HFTP、历史、导出及终端页面。工具目录采用紧凑分组，结果输出优先；时间仅改善展示，原始数据不变。清理影响说明、保存取消和复制失败反馈更明确。
- 终端键盘空间与快捷栏换行适配，统一输出页样式；普通过渡 200 ms，减少动画时取消非必要过渡。未修改原生/Python/协议/权限/服务生命周期，也未增加依赖。
- 最终 analyze、103 项 Flutter 测试和 release 构建/签名通过；107 场景、127 张实际 Flutter 图片补足宽度、大字和状态复验。APK `37d22600…` 与配置 A 最终安装哈希一致；具体实机范围、视觉迭代及限制见 [验证记录](verification.md)、[视觉设计](flutter-experience.md)。2026-10-06 用户确认观感通过并批准按计划提交收尾。

## 2026-09-28 — 界面与终端整理、产品机会集成

- 统一 Material 3 Card 表面、标题与能力状态字级；概览和信息阅读列宽至多 840 dp，终端画布保留整页宽度。
- 终端五个快捷键可收起/展开；输出快照增加搜索、高亮、匹配数和复制全部，保留 App/Root PTY 与原输入路径。
- `./hako flutter analyze`、79 项 Flutter 测试、release APK 构建/签名/校验通过；APK SHA-256 `ab13ceac96a7a8958659cb9bb12ab298dd65d9fa22b3ec729d2f5d26e30e014f`。另行授权的 `TARGET-BOARD` Android 11 App/Root PTY 定向验收见 [验证记录](verification.md) 与 [子任务检查](../.trellis/tasks/archive/2026-09/09-25-interface-terminal-polish/check.md)。不代表其他 ROM 或完整 Root 回归通过。
- 历史可信状态子任务补齐了逐项验收依据；当时的 Vector 条件已被后续移除决定取代。只读任务与界面终端子任务均已归档，父任务只负责文档和证据集成。

2026-09-28：用户要求的当前工作台/HFTP 保留成果脱敏提交已完成，专项任务按收敛范围归档；停止/亮屏传输/同端口重启修复已有实测，熄屏失败作为已知限制保留，Root 电源候选未实现。脱敏仅整理本次提交的设备、网络及本机环境标识，不改变协议或实现行为。以下未提交描述为对应历史阶段状态，最终结果见 [验证记录](verification.md)。

2026-09-28：完成 Trellis bootstrap 规范整理：按实际 Flutter、Android 宿主、JNI/C 与 Python 运行层重写指南，移除不适用的 React hooks 与 ORM 模板，并补充持久化、宿主桥接及状态生命周期约定。文档检查结果见 [bootstrap task](../.trellis/tasks/archive/2026-09/00-bootstrap-guidelines/check.md)。

2026-09-28：接入 `network.interface_diagnose` App-only 只读任务，接口详情携带并锁定接口名；Python 只收到新鲜快照中的单接口投影，保持实际缺失、失败、取消和超时状态。新增 Android host 版本化原子任务历史（仅三个只读 ID，20 条 / 5 MiB、淘汰最旧、手动清理）与工作台历史页面。总导出改为逐项预览选择网络、设备、连接快照及用户勾选的历史记录，保存仍走 SAF。Flutter、Python、Android 仪器和指定开发板的历史重启、单项 SAF 导出/取消及清理验收均通过，见[验证记录](verification.md)。

## 2026-09-27至28 HFTP停止与连续重启反馈（停止通过，熄屏未解决）

- 显式stop-request边界和stopping/closing状态，回调失效与后台精确关闭；失败清理期间保留owner，阻止新实例覆盖资源。
- 监听器使用checked SO_REUSEADDR，支持修正版传输后立即同端口重启，保留活跃端口独占，不改系统/VPN策略。
- 修复日志折叠bool/滚动double共用PageStorage的类型冲突，追加保留bucket的滚动/折叠/清空/重建回归。
- 修复先处理queued PING再检查原10s期限的竞态，closed日志增加固定原因；Root adapter持有有界App CPU锁，所有失败/停止路径释放，普通App不依赖它。错误标题改为服务异常，Root选项提示实际耗电。
- 9ca71e…包安装一致，73项Flutter、25项既有Python、24轮native传输+9项控制、10项最终手机检查通过；三轮实际LAN读写/停止/同端口重启含31MiB及日志操作通过。App锁仍未解决熄屏断连；用户选择暂不扩大Root范围，Root电源候选未实现。默认配置恢复、临时文件精确清理；不提交/归档。见[验证记录](verification.md)。

## 2026-09-27 HFTP日志与Root能力分离

- ToolExecutionContext显式声明APP/ROOT需求；ctOS RootOperationAdapter只承载已登记网络中继，缺失/未知/不匹配在效果前拒绝，普通App路径不依赖Root。Python HTTP/文件/SAF不提权；collector/PTY保留既有入口。
- HFTP可选Root LAN中继，UID0 helper只转发指定Wi-Fi同子网到App loopback后端；有界并发、心跳、生命周期与精确回收，不修改VPN/Clash/系统网络规则。启动必须显式Root true，缺省false。
- 实时有界服务日志、复制/清空/停止保留/旧会话保护；spec追加页面只保留必要操作说明、避免demo和实现细节堆叠。
- 最终0575e00d…包安装哈希一致；Windows与LAN33MiB读写、无覆盖/限额/后台/停止验收通过，App UID及Root UID实测。恢复默认配置并清理隔离夹具；未提交/归档。完整证据和未测范围见[验证记录](verification.md)。

## 2026-09-27 九张工作台图片反馈

- 工具标题去编号，删除script ID技术详情，文本操作与文件按钮全宽，下拉弹窗对齐；普通秘密输入保持遮挡并使用原有IME。
- App HFTP默认LAN/7888/免登录，上传1–1024MiB（默认32），恢复配置与SAF本机目录选择；保留原私有库。用户目录不提供清理，停止回收Python进程与原生目录broker，普通工具/SDK/CLI认证兼容保持。
- 修正TARGET-PHONE安全键盘触发条件，兼容Downloads本机provider；加强写入目标屏蔽、流式错误边界与请求owner保护。工具/HTTP仍是Python，Java只承担Android桥接。
- 最终d43f52ed…安装及普通IME、两个provider授权、33MiB精确传输、无覆盖/限额/后台/停止通过；电脑LAN直连仍超时，未宣称已修复原Firefox故障。服务停止与临时文件清理完成，未提交或归档；见 [验证记录](verification.md)。

## 2026-09-27 工作台体验优化

- 五项常用工具前置，其他脚本、环境和临时文件管理降为次级；保留深色/薄荷绿与全部九项入口。
- 中文参数、密码高级项折叠、长度数字校验、编码文本/文件来源及草稿保留、哈希方向隐藏、文件左对齐。
- 密码/哈希直接复制，IP 与设备摘要、文件预览及产物保存；密码默认遮挡涵盖原始详情和日志，完整 JSON 导出保持原协议。
- HFTP 状态和启动/停止主操作前置，共享库清理区分并保留确认；未改变服务生命周期、算法、SDK 或依赖。
- Flutter analyze、43 项测试及组件预览通过；Android lint、最终 APK 和新版设备验证范围见 [验证记录](verification.md)。本轮实现尚未提交或归档。
- 用户随后明确允许安装，TARGET-PHONE 新版安装哈希一致；界面、数字键盘、密码复制/遮挡、来源投影、SAF 导出28B读回/导入/取消及横屏定向验收通过。恢复旋转并精确删除外部测试文件，未运行 HFTP 或改权限。

## 2026-09-27 当前项目进度提交

按用户要求提交此前 Root-only、Python 工作台与五项 Portable 工具的源码、测试、设计和任务记录。README 的当前版本说明更新为最终五项工具包；本轮 ADB 连接和提交前本地检查见 [验证记录](verification.md)。保留任务状态，不归档；昨日设备验收记录仍按各自日期和包哈希解释。

## 2026-09-26 Portable 工具手机验收与原子提交

五项工具完成 TARGET-PHONE 安装与七项组合回归；SAF 导入/取消/产物导出读回、HFTP 常驻通知及通知停止通过。修复 Android App 硬链接 EACCES：SDK 产物、CLI 与 HFTP 共用不覆盖的原子重命名。测试显式隔离 Activity，消除 singleTop 后台任务复用带来的等待。09按官方文档使用现行免费HTTPS接口，指定公共IP实际查询通过。当前 APK `f928af66…` 与安装包哈希一致，测试服务/文件已精确清理。完整证据见 [验证记录](verification.md)，保留既有 WIP，未提交/归档。

## 2026-09-26 — Python 工作台与 Portable 包框架

- 移除系统负载及 `/proc/loadavg` 采集；四入口改为概览、信息、工作台、终端。
- APK 内置 CPython 3.13.9，工作台和 App/Root 终端共用原生启动器与标准库，提供 python3 的交互和文件执行入口。
- 四个脚本 item 进入二级页，具备参数校验、运行/取消、退出码/耗时、输出、复制及单结果 JSON 保存；SDK 统一注册表、Context 和结果。
- 新增 Portable v1 清单、APK ZIP/资源逻辑挂载、工具映射与环境生成，保留 curl 等 Android 原生工具的构建时接入方式。当前只内置 Python，不导入外部 ZIP。
- 工作台单子进程、15 秒超时、有界输入输出、后台回收；固定 -P -S 避免工作目录覆盖 SDK。内置环境失败时仍保留系统 Shell。
- Flutter 28 项、Android lint/测试包构建、本地 ARM64 QEMU 及 TARGET-PHONE 真机 8 项检查通过；App/Root 终端 python3、取消/超时/后台回收和工作台自检 JSON 保存已验收。修正 Android 安装目录含 `=` 时的工具启动兼容问题。详见 [验证记录](verification.md)。

## 2026-09-26 — 移除 Vector，使用 App/Root 采集

- 删除系统模块、Xposed API jar/入口/元数据、查询权限与 App 广播客户端；快照与 JSON 导出不再包含 module 字段。
- 删除 Vector 状态与启用/重启提示，保留 App/Root 能力、来源与失败恢复。网络配置由 App API 提供，增强接口/路由/连接及 PTY 通过已有 Root 会话工作。
- 已构建并覆盖安装当前包，Flutter 22 项、Android lint 与五项设备测试通过；当前包范围见 [验证记录](verification.md)，原因及能力取舍见 [决定](decisions/2026-09-26-root-only.md)。

## 2026-09-26 — 终端输入与输出选择

- 终端改为“应用 Shell”“Root PTY”两个会话入口；输出区只读，底部普通文本输入框显示当前输入并实时写入 PTY，键盘发送键执行回车。输入法组合文字提交后再发送，Tab 交给 Shell 自动补全并将补全结果同步到下方；↑/↓浏览本次会话从底部执行过的命令，选中命令通过同一 PTY 编辑路径显示在上下两处。
- 快捷栏保留 Ctrl-C、Tab、Esc、↑、↓；新增独立“选择输出”页，可滚动、选择和复制当前缓冲区快照，返回后会话继续。
- 清空终端输入框提示，将“选择输出”移到顶部“换用”右侧；去除终端欢迎语及工作台、设备、网络、命令、连接页的重复介绍，保留有用的权限、来源、采样和失败状态。
- 检查及当前设备的实际覆盖范围见 [验证记录](verification.md)。

## 2026-09-25 — 可信状态与工作台第一阶段

- 连接页区分首次读取、采集中、部分结果、无匹配、旧快照和刷新失败；显示独立采集时间，失败时保留上次数据。30 秒后提示重新确认。
- 连接输出增加 Flutter 解析层，将 `ss -tunape` 文本展示为逐条 item；保留无法解析的诊断与可展开原文，并支持按地址、端口、UID、应用筛选。
- 修正连接检索标题和说明居中的排版，使其与搜索框左缘对齐，并增加窄屏组件断言。
- 连接 item 加入可访问应用的真实图标、显示名与包名；按显示名、应用自身名称、进程名和包名检索，同 UID 多包可展开查看。未取得图标时显示占位图标。
- 打开 QQ 后返回 ctOS 或重新进入连接页会更新连接快照；无匹配时提示可刷新。按完整应用名、别名和包名筛选时优先精确匹配，避免短名称命中过多其他应用。
- 识别应用分身的独立 UID；在已验证的 Oplus 设备上读取桌面自定义别名，连接卡片可区分主 QQ 与名为 `CLONE-ALIAS` 的分身。别名不可取得时保留原名、包名和用户 ID。
- 图标 Base64 数据只用于界面，不写入 JSON 导出；保留导出中原有的连接文本与状态。
- 工作台分别呈现 App、Root、Vector 状态及手动恢复入口，按设备摘要、能力与恢复、常用操作排列；系统负载注明不是 CPU 使用率，权限受限时提供简明说明与可展开详情。
- 当前 APK 已在 Android 16 手机上覆盖安装并定向检查；Flutter 分析及 15 项测试、Android lint、四项适用设备测试通过。该手机的 Vector 桥接仍未响应；实机范围和一次未复现的旧包设备测试波动见 [verification.md](verification.md)。

## 2026-09-25 — Trellis Plus 初始化

- 新增项目自有的 Trellis Plus 共享规则与 ctOS 验证配置，覆盖提交前人工评审、Codex 归属、`hako` 复用、原生 UI 的 UUPM 使用及主线续接边界。
- 为 Codex 安装项目本地 UUPM 技能；生成文件位于被忽略的 `.codex/skills/`，未作为共享配置提交。现有 Trellis 受保护模板及产品代码未改动。
- 本轮未新建 Trellis 任务、未执行设备操作；检查结果见 [verification.md](verification.md)。

## 2026-09-25 — startup Root authorization and Android 16 check

- Request Root once on app startup, retain automatic startup after a successful grant, and restore the collector session after later launches or in-place installs. Failed grants or broken sessions require a manual retry; polling never reopens `su`.
- Rebuild the single current APK through `hako`, verify its signature, and install it on the user-selected TARGET-PHONE. Automatic restoration worked after a force-stop and an in-place reinstall; all three Root device tests passed. Vector bridge remained inactive on this phone.

## 2026-09-24 — Android 11 current APK check

- Install the current APK and matching Android test APK on the user-selected `TEST-BOARD-ADB-SERIAL` development board after replacing incompatible, differently signed ctOS packages.
- Verify the installed APK hash, all four Android instrumented tests, device information and navigation pages, both read-only commands, JSON export, and the App-permission terminal. Restore ctOS's Magisk Root switch to off after testing. Record pending Android 16 and fresh module-load checks in [verification.md](verification.md).

## 2026-09-24 — single current APK

- Replace the old version-named APK with `dist/ctos-current-arm64.apk` and update `dist/SHA256SUMS`.
- Add `./hako current` to rebuild, verify signing, and atomically replace the single current APK. Keep Flutter's `build/` output as an ignored intermediate.
- At this build stage, device acceptance was pending; the subsequent Android 11 check is recorded above.


## 2026-09-24 — containerized build wrapper

- Add `hako` and its container bootstrap for Flutter/Android build, analysis and tests without a host toolchain.
- Keep Flutter and SDK caches in ignored `.devhome/`; mount a container-specific `local.properties` without editing the host copy.
- No development service or host port is needed. Container analysis, six Flutter tests, APK build, signature verification and Android lint passed; device acceptance remains pending.


## 2026-09-24 — device information source changes

- Add App-permission device snapshots for system, load average, memory, battery and `/data` storage, with per-section source, timestamp, state and error.
- Add workbench, information and two read-only structured query entry points; keep network and terminal paths in the navigation.
- Limit periodic network sampling to foreground workbench/network views; device readings are requested on demand.
- Update narrow-screen widget coverage and add device snapshot parsing coverage. The Docker wrapper now builds and checks this source; device validation remains pending.


## 2026-09-24 — planning and documentation

- Establish `design/` as the project information landing, with product plan, constraints and index.
- Move architecture, compatibility, verification, dependency provenance and changelog into `design/`.
- Add project AGENTS.md to route future work and documentation to the shared landing.
- Documentation only; no new product features or device verification in this update.

## 0.1.0 — in development

- Fix repeated Magisk notifications: reuse one explicitly authorized Root collector session instead of starting su on each poll; never auto-reopen a failed session.
- Flutter network observatory, searchable interfaces and connections, JSON export.
- Vector system network snapshot bridge.
- Built-in PTY with app and root shells, resize and interrupt support.

## 2026-09-26 Portable 五项工具源码与本地预检

- 建立 portable-tools 父 task 与六个子 task，仅接入 08、26、09、02、16-HFTP；核心、SDK adapters、终端 CLI 与 UI/原生宿主分层。
- SDK v2 追加 choice/secret/file；独立私有文件导入/产物导出/空间清理，失败保留源和已有输出。
- 02 加入固定 APK 加密依赖、新 AES-GCM 认证格式和明确旧 CBC 解密；08 去自动 UUID 盐，26 保留二进制转换/哈希，09 改为有界标准库 HTTPS。
- HFTP 独立 Android 前台服务、常驻通知及停止入口，后台继续、默认本机、独立认证共享库及配额；保留浏览/建目录/上传/下载。
- 构建 Python 限于 .devhome，离线/浏览器测试限于 ${EVIDENCE_DIR}；当前手机验收状态见 verification，不复用历史结果。

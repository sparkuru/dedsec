# SSH声明 catalog 与语义校验边界

后续已补[实际清单离线材料检查](ssh-manifest-inspection.md)，仅检查所给文件副本，
不替代本合同的配置语义/最终计划或全清单准入；[实际验证](verification.md#20261019-manifest-inspection)。

日期2026-10-19，原SSH客户端task继续。本轮接口与实现完成，本地验证见文末。
前置为[native求值器](ssh-config-evaluator.md)及[JNI/Java边界](ssh-config-managed.md)。
本轮交付公共view上的无上下文声明catalog，不连接设备/账号，不提交/归档/发布。

## 目标、保证与后续validator

原Host配置仍是权威，复用固定readconf的同一keyword/argv词法入口，不能在Java另写
SSH解析器。扫描不提供虚构alias、remote user、passwd或环境上下文。它收集正向literal
Host声明和来源，保守遍历静态Include；不生成通配规则对应的虚构设备行。
所有结果恒为declarationsOnly=true，表示声明元数据。它不验证Match语法/其他指令参数值、
有效目标、raw Options最终展开、私钥/公钥匹配或known_hosts信任。实际连接须选真实alias
并用既有Request完成真实求值，USER_REQUIRED不能用App UID补全。

AppSecrets.SshContentValidator接收实际当前/来源/目标/候选manifest，公共lease仅代表当前
已准入的文件闭包，不能替代不同候选。私钥/证书/信任/引用绑定及恢复审阅仍缺；本轮不挂
attachSshValidator，不开放SSH导入/恢复/写入门。现有真实vault无法凭catalog自动引导出
可用SSH库，合成lease测试不作为完整生产导入验收。

## 原生扫描模式

在同一namespaced readconf translation unit的process_config_line_depth完成既有
strdelim/parse_token/argv_split后，加入独立catalog分支；普通求值行为保持。

- Host必须遍历整组已分词argv，不能受NEVERMATCH或首个否定匹配提前consume影响。
  只列无前导!、无*或?、符合固定ssh_valid_hostname及严格UTF8/ISO控制规则的literal。
  []按上游Host规则为literal，不能套glob元字符分类。否定/通配/不适合列为目标的token
  不列出且设置omittedSelectors标记。正向literal超过255字节明确LIMIT，不截断。
- alias按精确字节去重，保留首次来源object/line与深度优先扫描次序，最多256个不同alias。
  不推断reachable/active；静态Include中的声明即使处于Host/Match分支也可收集。
- Include沿用同一上游静态anchoring/managed glob/词法顺序与递归读取，基准为虚拟SSH目录，
  只读取CONFIG body，按root可达静态引用遍历，不扫描view中未引用的orphan对象。
  静态缺失/越界/错类型/循环按已有诊断失败。~/可正规化；~其他用户拒绝，不查NSS。
- 任意含%或$的Include不展开（含%%保守视为待处理），以原object/line加入deferredSites，
  设置includesIncomplete；不访问环境/假目标/假用户。每个不同object/line只记一次，最多256。
- Match只设置hasMatch，使用同一词法入口但不执行predicate，不调用Match exec/localnetwork。
  其他已识别指令仅词法消费，不应用Options或执行政策认证。未知指令明确拒绝；扫描不执行
  IgnoreUnknown，所以该兼容例外也不能在catalog里掩盖未知keyword。
- 保持view128对象/1MiB单对象/8MiB总、IO1024次/64MiB、深度8、每行8192字节/累计16384行。
  所有超限失败，无部分成功catalog。scannedFiles为实际进入的不同CONFIG对象数。

共用原parser trylock/current上下文、owner/PID/UID/原期限、atomic cancel和reader清理。
catalog open不接受Request；run不做first/final有效配置计算或默认填充。原生catalog getter
仅READY+正确模式+存活时可用；summary/Options getter不得把catalog误认成有效配置。
catalog专用元数据由evaluation拥有，close清零释放；失败reader/borrow阶段仍可重试。

## Native/JNI/Java合同

原evaluation对象/64槽registry支持显式catalog模式，沿用run/cancel/close/destroy；
新增C构造与guarded catalog getter，具体声明在ssh_config_eval.h/ssh_config_handle.h。
失败open的pendingBorrow/正cleanup token规则保留。原six config JNI保留，新增两个：

```java
static native long openCatalog(long view) throws IOException;
static native byte[] catalog(long handle) throws IOException;
```

Java `SshConfigEvaluator.openCatalog(lease)`、测试seam对应openCatalog(ViewBinding,NativeBinding)
返回同一owner facade的catalog模式；run后`catalog()`返回不可变Catalog及Entry/Site列表。
普通模式catalog()、catalog模式summary()明确拒绝。NativeBinding新增default fail-closed
方法，旧绑定不降级。只borrow caller lease，无额外view创建/关闭，无role/socket/凭据。
每次getter走native存活检查，无缓存授权；返回后的catalog为历史元数据，消费须重新核对。

wire `CSCD` magic0x43534344,u32版本1，big-endian：flags、scannedFiles、aliasCount、
deferredCount，再逐alias的u32 object/u32 line/u32 UTF8字节长+bytes，再逐deferredSite的
u32 object/u32 line。flags只允许低四位：1声明only（必须）、2 omittedSelectors、
4 hasMatch、8 includesIncomplete；bit8当且仅当deferredCount>0。最大70424字节，alias/
deferred各<=256，alias每个1..255字节，object1..128，line1..16384，scannedFiles1..128。
别名和Site均不重复；空列表合法但scannedFiles至少root1。Java独立严格验证长度/UTF8/
flags/计数/来源/重复/尾随，不经JNI modifiedUTF8或返回pointer/完整config body。

JNI完成array分配/写入后再次catalog存活检查，临时wire清零，pending exception保持。
Java共享owner/reentry/cancel/Error finally及两阶段close重试；不在run持阻塞cancel的锁。

## 验证计划与完成判据

真实parser正反矩阵须验证整组Host别名（含否定后的token）、引用/注释/转义、[]literal、
去重来源/顺序、静态Include在各种分支的遍历、动态Include明确未完成来源、orphan不扫、
缺失/类型/路径/循环及alias/deferred/深度/行预算边界。Match和Port非法值仍可能通过声明
扫描的例子应明确测试，再由正常求值拒绝，防止未来把catalog误挂语义validator。
覆盖撤销/期限/取消/错线程/模式混用/parser EBUSY/清理重试、FD无泄漏及effects0。

保留并实际重跑既有native求值和handle回归。Java测试覆盖wire非法帧/immutable列表、
新模式/旧绑定fail-closed、存活与取消/错误/清理；独立平台fixture仅编译，不安装执行。
strict、host ARM、fresh JVM/lint/Release、fixture/APK导出/隔离分别记录。source/工件hash
固定后完成正式checker和spec/design/task/handoff同步；父task继续进行中。

## 实现与实际验证

native/parser/registry/JNI与Java owner facade已实现；正式源码复核无遗留finding。
真实静态ARM目录71923项、原有效求值3736项及handle5201项检查均通过，effects0；
定向JVM39项通过。完整构建、工件对应与未运行范围见
[本轮验证](verification.md#20261019-config-catalog)。独立Android fixture仅本地编译；
无设备、真实库或账号操作。本增量不改变原SSH恢复拒绝门，父任务继续进行中。

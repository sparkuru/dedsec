# 实际清单的受管配置检查

日期：2026-10-23。原 SSH 客户端任务的接续实施合同。前置为[独立 native 输入](ssh-detached-config.md)
与[完整语义义务](ssh-validation-lifecycle.md)。本轮实现 Java/JNI 实际输入与全 CONFIG 声明检查，
不是整库语义准入；无生产 validator 注册、恢复开放、连接器或设备/账号操作。

## 两阶段 owner 与 native 存储

1. 新 native `reserveDetached(catalogMode):long` 只预留既有64槽中的一个正递增token。
   Java预先分配facade，取得token后直接存入并交回caller；不做可能丢失owner的失败后查。
   不创建role/view，不复制正文。原live open/catalog入口不改。
2. owner已保存后才调用 `loadDetached(token, sourceWire, root, deadline, requestWire)`。
   root零起始，deadline原BOOTTIME且<=60秒；catalog的request必须null，普通模式必须真实CSEQ。
   Java与native均只允许一次load；load前run、load期间run/close/destroy、重复load明确拒绝。
3. native先pin原slot，释放registry mutex后准备独占DONTDUMP/锁页区域；在JNI复制之前已由
   slot/evaluator拥有。CSDI直接复制到该精确长度受保护区域并原位校验/adopt，不再构造
   第二份完整native正文副本，也不使用无owner的malloc全量scratch。2026-10-26修正：
   文件表按已严格核对的实际数量单独分配，只含公开类型/长度及指向受保护源的指针，
   所有path/CONFIG正文仍仅位于锁页源。废除每次锁定最大128项索引的固定前缀，避免
   小配置也超过Android应用64KiB硬配额；不提高系统限制或回退未锁正文。
   CONFIG读取使用的内部匿名memfd也不能依赖fchmod0600：Android16实测允许创建/截断，
   但chmod因App tmpfs策略被拒绝。以实际regular/当前UID/零链接/精确长度/CLOEXEC及
   不导出FD的所有权边界检查，仍先锁页/DONTDUMP后复制、结束擦除。实际inode mode
   如实报告；虚拟库文件的逻辑0600与独立FILE_LOAN真实0600要求不能混淆。
4. GetArrayRegion/JNI异常、坏wire、取消/期限或构造失败均保留已发出的token。busy解除，
   run拒绝或返回原失败诊断；close擦除并重试所有资源，destroy仅在close成功后清槽。
   pending JNI exception保留，不因清理发出替代异常。Java load抛Error也不能丢失facade。
5. cancel跨线程仅在短registry锁内标记并向已附着eval传递atomic取消。eval附着前取消也须
   在附着后继承；不在registry锁内解析/分配/锁页/复制。Java取消先置位，再取active owner；
   attach后复查取消，弥合reserve/load之间的空窗。取消线程不擦worker正在使用的数组。

## wire 与低层 facade

既有CSEQ/CSES/CSCD不变。新增CSDI v1，标准UTF8/big-endian：

```
u32 magic=0x43534449, version=1, fileCount,
u32 maxOpens, maxReadBytes, maxLines,
for each original-order file:
    u32 nativeType, u32 pathLength, pathBytes,
    u32 bodyLength, CONFIG-only bodyBytes
```

count1..128，type1..5，path1..512bytes，无NUL/坏UTF8/ISO控制/重复/文件目录冲突；正文
1MiB单文件/8MiB总。非CONFIG必须bodyLength=0，native表示为{NULL,0}。无尾随；最大
8455704bytes。无库tuple、references、身份或秘密条目，不能把未携带语义当作已验证。
maxOpens<=1024、maxReadBytes<=64MiB、maxLines<=16384，可为0表示剩余预算耗尽。

低层Java `SshConfigEvaluator.prepareDetached(guard,binding,catalogMode)` 返回已保留owner；
`loadDetached(source,root,deadline,request)` 随后加载。新NativeBinding方法默认fail-closed，
无fake ViewBinding；guard直接检查同一job取消/原期限。新增 `usage(token):int[3]` 为
opens/readBytes/lines，有owner/READY/lifetime检查，JNI在写出后复查。结果为历史计数，
不授予任何能力。每个已成功root必须opens>=1，读取/行计数不得超过给定剩余额度。
普通live构造保持原默认预算和caller borrow/清理规则。

## Java 全 CONFIG 编排

新增package-private、owner-thread、一次尝试的 `SshManifestConfigInspector implements AutoCloseable`。
caller先持有该对象，再调用 `inspect(manifest, deadline, cancellation)`，成功/失败后均可close；
close失败时对象仍保留精确active evaluator，caller必须继续保留并重试，禁止丢弃局部owner。
同线程跨实例重入拒绝，native工作期间不持阻塞cancel的Java monitor。

- 接受实际且独占的同步VaultManifest loan，不读current AppSecrets、不改/关闭caller manifest。
  在首个native调用前复制所需type/path/CONFIG正文；不复制非CONFIG正文、密码、口令或TOTP。
  分配清理owner在复制之前。IOException/Linkage/Error/cancel/cleanup后都擦除独占wire。
- 同一Cancellation只允许一个job占用；active owner用attach/detach握手绑定。整个copy、
  逐root、getter、usage、cleanup、最终report共用原deadline，最多60秒，不逐root续期。
- 按manifest原始顺序扫描每个CONFIG，包括orphan。不制造super-root/alias/用户名/Request。
  无CONFIG时返回明确0roots的partial-only报告，不调用native，不制造scannedFiles=0 CSCD。
- 每次root把剩余opens/readBytes/lines写入CSDI header，native在实际open/read/line前执行
  额度检查。成功后严格核验usage并累计；任何失败中止整份report，不返回部分成功。
  全job上限1024 opens、64MiB受管读取、16384解析行，不能乘以root数量。
- 复制放大另设CONFIG数量×CONFIG总正文<=64MiB的预检；超过即固定LIMIT失败，不能开始
  native再放大。累计保留CSCD wire<=1MiB、alias+deferred row<=4096；decoder在list/string分配前按剩余结果预算拒绝，不截断。
- 每个catalog的object必须落在原files范围且对应CONFIG，scannedFiles<=实际CONFIG数量。
  报告root绑定原file id/path/ordinal；列表不可变，无正文/secret摘要/native句柄。
  恒 `declarationsOnly=true, admissionComplete=false`；动态Include/Match/omitted状态保留。
- cleanup是job的一部分：最后一个native owner已关闭/destroy、Java副本已擦除后，才做
  原取消/期限的最终检查并交付report。cleanup失败时不得交付成功，owner保留供close重试。

## 验证关卡

native真实矩阵验证CSDI边界/模式、reservation/load/cancel竞态与JNI-copy abort seam、
原始来源、受保护单份正文、预算在parser中的实际执行、usage/wrong owner/failed load、
close/destroy重试及无view/外部效果；原live/eval/catalog/handle/独立输入/IO矩阵保持。
JVM验证实际caller loan与副本独占、所有CONFIG/空输入、原budget与数值/来源/结果容量
拒绝、取消acquire空窗/暂停load/run、重入/Error、所有失败清理owner保留/重试和最终
publish fence。独立Android fixture编译但不安装；真实JNI执行另需当前授权。

main拥有串行构建、APK审计和文档；native/Java代理各自限定文件，独立checker复核。
actual results写入验证记录，不由mock/JNI编译推定Android运行。完整引用/政策、身份/
凭据/信任语义和provider/reviewer/恢复仍后续；父任务继续in_progress，本地未提交。

## 本地实现状态

本轮已实现并通过本地门禁：新真实ARM3683checks（含baseline1262）/effects0/capabilities0，原direct1261/eval3736/catalog71923/handle5201与hostIO426通过；定向101/fresh全量514 JVM全0，normal94全重跑/fixture111与三APK审计通过。平台fixture只重编译未执行，JNI异常运行和Android锁页尚未实测；[完整结果与失败轮](verification.md#20261023-detached-managed)。原task/父AC继续in_progress，本地未提交。

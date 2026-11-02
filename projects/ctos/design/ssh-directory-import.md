# SSH目录批量导入（历史，已撤销）

2026-11-02用户明确不需要本功能，并澄清“批量”指一个`~/.ssh/config`包含多个Host
连接项，不是扫描整个目录。当前按[单配置／独立私钥导入](ssh-key-config-connect.md)
收敛实现；本页只保留10-30设计及验证历史，不再作为实施合同，也不新增路径迁移。
移除入口不删除此前已经导入秘密库的配置、密钥或信任资料。

用户截图中的Download根目录不可选符合Android11起的[系统SAF限制](https://developer.android.com/training/data-storage/shared/documents-files#grant-access-directory)。
该限制及电脑路径缺少适配的反馈促使本次重新确认产品需求，不通过扩权或绕过系统选择器解决。

以下均为已撤销方案的历史内容。

2026-10-30，用户确认密钥增量功能通过、允许提交并继续。沿原1008父任务推进已确认R7，
不新建任务。本轮目标是完整的目录快照→选择／解锁→整批审阅→一次原子提交；
不把目录选择器或不可提交列表当作交付。实现／实测状态另记在验证入口。

10-30时实现已完成，819 JVM／331 Flutter及指定开发板隔离实际链路通过；正常APK
b2d91de2…已获用户允许保留数据更新指定开发板并启动，新增交互待人工体验。
[安装记录](verification.md#20261030-ssh-directory-formal-install)。实际范围及未验证项见
[验证记录](verification.md#20261030-ssh-directory-import)，不将本切片标为完整R7完成。

## 范围与用户操作

私钥与配置页增加「导入目录」。用户选择一个包含config／Include／私钥的目录，目录
内容按相对结构映射到受管 `.ssh/`，选择目录自身名称不参与目标路径。只读来源，不持续
同步、不持久保存URI授权、不写出明文或删除原件。

本轮导入当前direct模型支持的CONFIG、普通／加密软件私钥及其精确公钥。来源known_hosts、
保留的ctos-manual、证书、不能识别或其他不支持对象必须显式列出为阻断项；用户可明确
排除后导入剩余有效闭包。不得静默跳过或授予来源主机信任，不宣称所有任意.ssh对象支持。
公钥不能作为私钥证明：被选来源公钥须与对应已验证私钥的实际公钥相同，否则阻断；
私钥所需规范公钥始终由实际证明生成，并在目标变更清单单独列出。

快照后不再读取URI。预览显示来源文件选择、类型、加密／待解锁状态、实际已验证指纹，
以及实际目标add／replace／unchanged／refresh（仅修复引用）清单。导入口令只用于当次
本地证明，不默认保存。多个加密私钥逐个解锁，错误口令可在原票据原期限内重试。
替换已有不同内容必须另行明确确认；相同字节及同一身份的未改变对象保留口令、Account
和元数据，不把unchanged当作一次暗中重置。全部相同或全部排除时不发生持久写入。
存在实际私钥替换时说明其已存口令会清除、身份变化会移除旧连接关联；unchanged不显示
为重置。首次导入可由应用新建空known_hosts，须在目标清单标明，不能导入来源信任。

## 有界快照与原子候选

- ACTION_OPEN_DOCUMENT_TREE仅请求读取；限定所选content树，不请求Root／广泛存储权限。
  拒绝异常文档名、逃逸／歧义／重复目标路径、重复document ID／环、虚拟对象及不完整读取。
- 总节点≤256、文件≤128、深度≤16；单文件≤1MiB，源正文合计≤8MiB。来源和目标配额分别
  检查：保留文件、派生公钥和生成信任也计入候选128文件／8MiB正文／12MiB清单上限。
  目标仍受既有512 UTF-8字节／最多8个路径组件（含.ssh）约束；遍历深度16是额外安全上限，
  不意味着允许扩大清单路径深度。不可表示路径完整拒绝，不静默截断或重命名。
- 整条原Request期限沿用120秒，目录选择、查询、读取、所有选择／解锁重试和提交不续期；
  锁库／后台／取消／失效清理快照及临时材料。同步provider调用返回后检查，不声称可强行
  中断任意挂起provider。超时重选目录，不放宽公共BundleContext期限。
- 先固定全部来源及当前库，再构造完整候选。先证明所选私钥及配对，再以实际native单次
  aggregate审计整套CONFIG／Include闭包并刷新直接引用元数据；不逐文件提交或重置每个
  CONFIG的累计打开／字节／行预算，不在Java／Dart重写OpenSSH配置语义。
- 严格新CONFIG闭包政策保持；未选缺失依赖／不支持指令阻断提交。保留源库无关对象、
  TOTP、密码Account及主机信任。实际替换私钥沿现有精确口令／Account清理规则。
- 一个source-bound SshBundleProposal一次提交；守住原source／epoch／revision／validator
  注册／deadline／取消、唯一effect及不确定持久结果。最后仍经完整生产候选验证。

## 固定接口

以下是宿主→Dart公开元数据，不包含正文、私钥／口令、URI或前端trusted字段。

- `ssh.directoryImport(requestId) -> DirectoryPreview`，用户取消走既有SSH_CANCELLED。
- `ssh.directorySelect(requestId,ticketId,binding,excludedPaths) -> DirectoryPreview`，路径列表
  唯一且≤128，仅限当前快照来源；每次成功重建返回新ticketId／binding并退休旧票据。
- `ssh.directoryUnlock(requestId,ticketId,binding,path,passphrase) -> DirectoryPreview`，口令
  沿当前8192 UTF-8上限。成功亦返回新票据；错误口令保留原票据可重试，不续期。
- `ssh.directoryCommit(requestId,ticketId,binding,replace) -> {committed:true}`。
- `ssh.directoryCancel(ticketId) -> {cancelled:bool}`。

DirectoryPreview固定字段为 `ticketId,binding,label,files,changes,issues,canCommit,requiresReplace`。
`files`≤128，行字段 `path,kind,selected,encrypted,needsPassphrase,algorithm,fingerprint`，
可选`issue`固定错误码；kind为privateKey／publicKey／config／unsupported。配置／不支持
行的algorithm与fingerprint为空；未完成私钥证明不投影私钥公钥事实。`changes`≤128，
行字段 `path,kind,action,derived`，kind为privateKey／publicKey／config／knownHosts；
action为add／replace／unchanged／refresh，derived仅规范派生公钥或应用新建空信任对象。
knownHosts行必须为 `.ssh/known_hosts`、add、derived=true，且只在实际导入有效资料而源库
尚无该对象时生成；不替换已有信任，也不因来源全部排除而单独创建。issues沿用有界
SshImportIssue(code,path,line,message)≤256。
binding沿既有64小写hex，路径／公开文本沿现有库边界。

前端仅回传选择／票据，不回传可写文件或可信影响清单。重建沿原始快照与原Context，
不授予新期限；普通导入／删除／目录票据不能跨种类消费。取消和迟到结果不会撤销已
交接提交的结果跟踪，保护窗等待持久终态及结果确认后才释放。

## 验收与边界

Android树授权／子文档URI使用官方
[SAF目录访问说明](https://developer.android.com/training/data-storage/shared/documents-files)和
[DocumentsContract](https://developer.android.com/reference/android/provider/DocumentsContract)
为平台依据。带EXTRA_LOADING的未完成目录查询必须拒绝，不能提交不完整列表；虚拟文件
不能直接按普通openInputStream材料接受。本轮采用严格普通文件子集，不增加格式转换。

验证嵌套Include、多个密钥含加密私钥、公钥一致性、显式排除来源信任／证书、缺失依赖、
替换确认／真正unchanged、超限／异常provider、快照后源变化、取消／过期／旧票据／
跨种类／无部分写入，以及不确定回执。实际native准入、Android合成DocumentsProvider
和独立key-only连接分别记录；widget不代替系统DocumentsUI。未授权真实账户不访问。
父完整R7、复杂证书、信任导入／编辑、跳板转发及完整恢复不因本切片完成而勾选通过。

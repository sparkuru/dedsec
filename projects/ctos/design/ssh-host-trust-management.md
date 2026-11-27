# SSH 主机信任管理

## 2026-11-20 逐跳审阅事件和确认顺序

首次与换键审阅事件携带实际 role ticket 的 hop、host 和 port；dismissal 同时携带同一 ticket 的 binding 和 hop。连接页分别保留每笔审阅的决定回执，并在交付连接前等待所有已发决定的确认。旧跳、同 ticket 内容变化和不匹配的 dismissal 均被拒；IPv6 地址与端口分开展示。换键仍要求与直连表单目标一致，不能仅凭事件自行声明的 hop 放行跳板：未来必须由实际 factory 提供绑定原请求及受保护配置的可信路由。

## 2026-11-18 首次信任与换键的提交回执

现有直连中，NEW／CHANGED 写入与同一 native KEX 的最终确认分别记录。屏锁或服务失败发生在可能的同步信任写入期间时，会立即撤销连接，但连接回执等待写入退出；写入可能已发生而证明未确认时锁库并返回 `VAULT_COMMIT_UNCERTAIN`，不声称写入回滚。确认与取消、role／view 撤销以及 provider 失效的先后有明确顺序，原有凭据保存错误优先级保持。该修正没有提供 ProxyJump 的祖先证明。[测试与边界](verification.md#20261118-ssh-borrow-trust)。

## 2026-11-16 认证启动时的主机证明失败诊断

密码和私钥启动时会同步调用选中凭据回调。该回调发现主机证明绑定异常时，同一
session 仍终止；当原认证请求有效且本次没有受跟踪的凭据／换键信任写入时，
连接页可显示现有的证明失败提示。诊断只接受该 session 实际持有的回调，沿用原认证
请求期限；握手阶段的较短
`ConnectTimeout` 不继续限制已经开始的认证；不因此重试、改用其他凭据或进入终端。

取消、锁库、视图失效和不确定写入仍遵循前述共同保护。KBI 首方法和后续问题的认证
逻辑不改变；本次 Java 合成验证与 Android JNI、设备和真实服务器结果分别记录。
[本地结果](verification.md#20261116-ssh-auth-proof-diagnostic)。

## 2026-11-16 KEX 主机证明失败诊断（本地验证）

主机证据的内部角色／绑定错误会停止握手；其回调自身撤销此前可能让连接页只显示
“连接已中止”。本次仅在同一次握手的实际回调确实报告 `SSH_HOST_PROOF_INVALID`、
原连接和传输期限仍有效、且本次尚无受跟踪的凭据／换键信任写入时，向连接页传递现有
“主机身份验证未能完成”提示。底层
会话保持终态，不重新尝试、发送凭据或将该错误当作服务器换键。

产品取消、锁屏、秘密库失效及更短 `ConnectTimeout` 仍由原连接上下文检查；Stop／
锁屏与连接完成的先后在 owner 处确定。已写入但尚未确认的换键信任仍以
`VAULT_COMMIT_UNCERTAIN` 锁库提示。测试和设备结果分开记录，不据合成回调推定
真实服务器或 Android JNI 已发生该异常。认证启动时的证明复查不在该 KEX 切片范围，
后续处理见上节。[当时检查](verification.md#20261116-ssh-host-proof-diagnostic)。

若该次连接已发生受跟踪的凭据或换键信任写入，保留原来的中止诊断，使连接结果发布后
至界面收到回执前发生的取消仍能触发既有不确定写入保护；不把它说成已确定回滚。

## 2026-11-13 换键后继续连接（本地实现）

用户在 config 私钥续接测试后反馈功能通过，同时指出旧指纹弹窗展示两组记录、
忘记／确认／重连／新指纹确认反复点击；用户选择在一个界面核对新旧指纹，明确
选择“信任新指纹并继续”后，由应用完成必要处理并继续连接。此方向改变下方历史的
“忘记后必须再次手动发起连接”，不改变其他信任和认证保护。用户审阅完整方案后选择
“按方案实施”。源码已实现此路径，本地检查与设备体验分别跟踪；本轮候选已按用户
授权[更新指定板](verification.md#20261113-ssh-changed-one-decision-install)，人工体验待反馈。

普通路径是在本次连接的实际目标上展示已保存的旧指纹与本次服务器提供的新
指纹，来源／完整记录可按需展开；取消不修改记录。用户确认仅授权当前目标、当前实际
受管信任来源及界面所展示的新密钥，不将其他来源中恰好相同的指纹当成同一个对象。
不支持或受保护的记录仍明确阻断，不静默清除共享／通配／吊销／CA 等规则。连接目标
区另提供配置主机快捷入口，但每次连接仍显式选择认证方式和受管私钥。

此前 native 对真实 CHANGED 只记录拒绝分类并结束握手，界面并未收到本次服务器的
新指纹；config 连接的实际信任文件已在宿主求值，但未传到该错误界面，故会展示两组
供用户自行选择。当前 config 连接只支持一个实际存在的受管信任来源；有两个则拒绝，
不能将其理解为已支持合并多来源。

一次性授权的状态顺序如下：

1. 在仍存活的本次连接中，由实际原生 parser 生成针对该来源的有界旧记录、新 key
   和受保护候选；同一个审核绑定实际目标、角色／owner、配置及来源快照、原期限和
   本次收到的公钥。显示时新指纹仅为服务器提供，尚未完成签名验证。
2. 真实按钮确认后仅对这一 key 建立临时、不可读取凭据的批准，继续同一次 KEX。原生
   校验签名和 NEWKEYS；KEX 签名与主机密钥的关系见
   [RFC 4253 §8](https://www.rfc-editor.org/rfc/rfc4253.html#section-8)。
3. 在原 owner 和有效期限内重新核对 key／角色／目标／来源，并对精确候选一次性 CAS。
   仅在实际提交成功后发布正式 host/KEX 证明，允许向远端认证流程交付所选凭据。
   不能用 Dart 指纹、伪造路由返回、临时批准或另一组相同指纹替代正式证明。
4. 拒绝、取消、过期、锁库、后台、签名失败或来源变化停止继续；提交结果不确定须锁定
   并明确报告，不能自动重试或声称回滚。已有本地密钥检视及用户明确选择的保存动作
   仍按既有合同处理，不将其误述为全部发生在 KEX 之后。

现有 NEW 路径先写入再完成 KEX；新的 CHANGED 路径单独持有临时状态。候选跨回调
独占所有权，NEWKEYS 后使用精确来源执行 CAS，并检查自身提交后的 epoch／view、
回调重入与原期限。真实同 socket 协议探针使用模拟 CAS，实际秘密库 CAS 由独立 JVM
测试；两者均不能替代 Android 端到端实测。
完整研究见[底层流程](../.trellis/tasks/10-08-ssh-terminal/research/host-key-recovery-flow-20261113.md)
和[界面路径](../.trellis/tasks/10-08-ssh-terminal/research/host-key-recovery-journey-20261113.md)。
原始首次信任与独立忘记管理入口保持既有合同；设备安装、实机和人工体验独立记录。

2026-11-13 追加：根据用户截图，将换键和忘记确认对话框的文本按钮状态层改为与现有
实心按钮一致的 12dp 圆角；保留颜色、确认步骤与 48dp 触控。本地 widget 检查通过，
尚未安装或获得修正后的设备视觉反馈。
[验证](verification.md#20261113-ssh-key-config-dialog-feedback)。

2026-11-13 较早接续（本节新路径实施之前）：主机身份的内部证明或绑定不一致使用独立错误码，不能被解释成
服务器换键，也不能触发忘记旧指纹。实际 CHANGED 仍拒绝连接，恢复流程仍需新预览和
明确确认；本次未修改信任策略或放宽认证。

日期：2026-11-02。状态：**本地/隔离验收、正式保留数据更新及人工体验均通过**。沿原
[1008 父任务](../.trellis/tasks/10-08-ssh-terminal/prd.md)的 R5 / AC5 推进。
用户先授权192.168.9.14:5555隔离验收及清理，再另行授权正式APK保留数据更新并启动，均已完成；
用户随后反馈“通过；可以继续；”，[人工验收通过](verification.md#20261102-ssh-host-trust-human-acceptance)，
继续原1008；不含agent操作真实账号或批准本增量提交清单。

2026-11-13 增量：连接页新增指纹变更对话框和直达恢复流程，已完成本地回归与指定板隔离
实机测试；用户批准后正常候选已[保留数据更新并启动](verification.md#20261113-ssh-ux-formal-install)，随后根据反馈继续修正。
本段状态不改变上述历史验收范围。[本轮验证](verification.md#20261113-ssh-form-host-recovery)。

随后根据用户反馈，忘记操作改为连接页原地确认并调整管理区间距；两个认证模式的指定板
隔离界面复测及新的保留数据更新已完成，[结果和限制](verification.md#20261113-ssh-feedback-direct-recovery)，
用户随后反馈“可以；目前能够接受了”，[本轮体验可接受](verification.md#20261113-ssh-feedback-human-acceptance)。

## 历史：独立主机信任管理的目标与范围

以下描述独立查询／忘记入口及此前的换键恢复流程；连接时一次确认的新路径以上节为准。

服务器更换主机密钥时，用户能查看指定主机、端口已经保存的真实指纹，明确确认后忘记
普通旧信任记录，再主动连接并核对本次实际提供的新指纹。忘记不批准新键、不恢复失败连接。

| 界面分组 | 精确受管对象 | 身份约束 |
| --- | --- | --- |
| 密码连接 | `ssh/known_hosts` | ID `ctos-password-hosts`，identity `ctos-password-trust` |
| 配置与私钥连接 | `.ssh/known_hosts` | 现有 `id("hosts", path)` 与 `id("hosts-owner", path)` |

两组均查询，分别审阅和忘记，不提供跨组一键清空。对象由宿主按完整身份识别，不能
只凭 Flutter 提供的路径定位任意文件。没有对象时显示未保存，不自动创建。查询使用
实际连接目标的主机名/地址和端口，不把 config 的 `Host` 别名自动当作实际目标。

本轮不增加信任导入、手工添加/编辑指纹、CA/吊销管理、目录导入、路径适配、ProxyJump、
转发、证书连接或完整恢复。父完整 AC 保持开放。

## 交互方案

在现有受保护 SSH 资料页增加“主机信任”入口，进入独立敏感页面；沿用公共秘密库解锁
及页面保护。用主机、端口表单和“查看指纹”按钮查询，端口默认 22，输入校验复用现有
规则。结果按上述两组展示目标、位置、算法、完整实际 SHA256 指纹、记录性质与受限原因。
哈希记录通过目标查询匹配，不声称能反推出主机名，不增加全库主机目录。
空结果、失败及不能安全忘记分别呈现，解析失败不能显示为“没有记录”。

每组“忘记旧指纹”先取得独立预览；确认框展示**本次预览**的目标、位置、旧指纹和数量，
不把旧查询结果充当审阅权限。文案：“仅忘记这些旧记录；再次连接仍须核对服务器的新指纹。”
取消不写入，确认只提交精确候选，成功后提示主动重连，不自动连接。匹配的多个普通算法
或重复行全部进入预览，不提供任意行编辑。连接发生 CHANGED 时停止当前连接，展示实际
目标并提供“查看旧指纹”“忘记原有指纹”“取消”。查看直达预填目标的受保护管理页；
忘记在原连接表单上直接显示受保护的确认弹窗，并基于新查询和新预览再次确认。
确认弹窗被其他路由覆盖时立即撤销旧授权，不允许返回后继续使用。手动密码/交互式使用密码组，手动私钥使用配置组；
配置连接的实际信任文件可能不同，须由用户核对并选组，不自动猜测。忘记成功后确认回执，
用户主动重新连接并核对新指纹；不会自动连接或批准新密钥。
确认及成功／未确定回执均绑定当前真实按钮；外部伪造的路由返回不能提交或清除未确认回执。

编辑目标立即清空旧结果和确认，离页/锁库/后台/过期撤销未完成预览；异步回执隔离原目标
代次。提交期间保留保护并等待最终回执，不确定结果提示解锁后重新查询，不声称已回滚。

复用现有 Material 主题、Form、公共保护路由、可滚动确认框和 Wrap 操作区，Android 操作
至少48dp。完整指纹可换行/选择，不省略末尾；覆盖320×568、375宽、横屏、840dp阅读列、
软键盘和大字。已执行 UI/UX Pro Max Flutter 表单/确认/无障碍检索，采用 Form、Semantics
和系统文字缩放建议。通用设计输出的网站hero、外部字体和新配色不适用；不建立第二设计体系。

## 匹配、删除与保留政策

使用固定 OpenSSH 的真实 parser 对完整输入校验、目标匹配和计算公钥指纹；不另写
正则/分行语义编辑器，也不调用 stock 磁盘重写函数。

| 实际匹配记录 | 处理 |
| --- | --- |
| 普通单目标字面选择器 | 与现有 issuer 的 lookup 相等时可删除 |
| 普通合法单目标哈希选择器 | parser 验证匹配 lookup 后可删除 |
| 共享列表、通配、否定模式 | 显示受限原因，阻止该组整个忘记操作 |
| `@revoked` / `@cert-authority` | 保留；匹配本目标时阻止该组忘记，说明受保护策略 |
| 证书或不支持的普通键类型 | 显示受限原因，阻止该组整个忘记操作 |
| 其他主机/端口、注释和空行 | 逐字节保留 |

删除单位是所选组内全部匹配普通单目标受支持软件公钥行，至少一行；若仍有阻断记录，
不生成部分候选或声称已忘记。非匹配的 marker 行不阻止普通目标操作，仍逐字节保留。
完整材料无效或超限时完整拒绝，不提供强制忘记。

lookup 沿现有 issuer：22端口使用 host，其他端口使用 `[host]:port`，内部 lookup
的 ASCII 大写转小写。字面选择器须先证明不含 `*`、`?`、`!`、逗号并由真实parser匹配，
再对其ASCII大小写折叠后与lookup相等；这与pinned parser的主机大小写匹配保持一致，
不扩大到共享模式。正文/界面身份不改，不另行规范化IPv6拼写；endpoint方括号不当作通配拒绝。

## Native / Java 边界

新增独立 supplied-body 原生 API 与版本化 wire，不暗改固定 CSMI。宿主拥有正文副本、
host/port、操作模式和原期限，全程内存处理，不读取外部文件、暂存明文、执行命令或联网。

1. 复用实际材料校验，含所有行、公钥与hash token；用 `hostkeys_foreach_file` 得到
   实际匹配、marker和parsed key。callback内复制有界字段，用真实 `sshkey_fingerprint`
   计算SHA256，不保留借用指针。查询返回匹配/受限事实，准备模式仅在整组可删时返回候选。
2. 独立记录原正文物理行byte spans，按parser行号关联，删除所选完整行；不能用已去掉
   换行的parser `line`重建正文。CRLF、缩进、注释、无关内容及末行缺少换行均保持。
3. 返回独占公开事实、源/替换摘要和候选正文；正文与摘要仅在native/Java消费。
   MethodChannel不提供正文、可编辑replacement、任意行ID或指纹字符串写入接口。
4. JNI/Java严格校验版本、长度、数量、枚举和尾随字节；输入保留调用方所有权，
   owned副本在finally/close擦除，所有失败出口清理。检查parser debug链接不会泄露行内容。

保留单文件1MiB、单行65,536bytes、16,384行；每组最多公开128条匹配记录，两组总量
最多256条，超出完整拒绝，不能截断可见删除集合。全量校验/匹配计入原预算：管理原期限
120秒，每次native调用使用 `min(originalDeadline, now+60s)`，前后及callback复查；
重试不续期，不宣称Java取消能抢占任意同步parser调用，迟到输出必须丢弃擦除。
CSHT v1 wire在实施首步固定如下，所有整数为BE u32；Java假结果不能替代真实parser证据。

| 偏移 | 字段 |
| --- | --- |
| 0 / 4 | magic `0x43534854` / version 1 |
| 8 / 12 | mode 0 query或1 prepare / flags bit0 canForget、bit1 replacementPresent |
| 16 / 20 | rowCount≤128 / replacementLength≤1MiB |
| 24 / 56 | base SHA256[32] / replacement SHA256[32] |
| 88起 | rowCount条定长216bytes记录，随后为replacement正文 |

每条记录为line、marker、selector、reason、algorithmLength、fingerprintLength六个u32，
再接零填充algorithm[128]、fingerprint[64]。marker=0none/1revoked/2ca；
selector=0literal/1hashed/2shared；reason=0none/1shared/2policy/3unsupported。
line为真实物理行号、严格递增；实际算法文本与SHA256指纹有界。
canForget当且仅当有匹配且所有reason=none；query无replacement，prepare仅可忘记时
有replacement（正文可为空，仍设present）。缺少replacement时长度/摘要为零；拒绝未知
flags、非法组合、非零padding、摘要不匹配及尾随字节。JNI固定入口为
`SshHostTrustMaterial.nativeInspect(byte[] body, byte[] host, int port, boolean prepare, long deadline)`。

## 宿主事务与通道

查询从一次受保护 `sshLibrarySnapshot` 得到两组实际公开事实，无保存副作用；结束即释放
快照/context，查询结果不是删除权限。预览独立捕获原Request/BundleContext、epoch、
contentRevision、原期限和ciphertext snapshot binding，为所选精确对象生成一个
`SshBundleProposal.Change.replace`。只改正文；FileType/ID/path/identity/references及
其他文件、关系、账号、秘密和TOTP保持。即使变成空正文，也保留对象和CONFIG引用。

原source/完整candidate都通过生产validator。票据持有原source-bound proposal，复用bundle
注册、一次写入guard和uncertain规则，不借用NEW-only的 `SshTrustProposal` / live trust CAS。
最终写入复查原source/epoch/revision和消费者；已写但无法安全交付终态沿用
`VAULT_COMMIT_UNCERTAIN`并锁库。所有活动连接/编辑/提交/未决票据遵循既有互斥。

| 操作 | 严格输入 | 输出 |
| --- | --- | --- |
| `ssh.hostTrustQuery` | requestId, host, port | 目标与两组公开记录/阻断原因，无票据 |
| `ssh.hostTrustForgetPreview` | requestId, host, port, group | 本次公开审阅集合、ticketId、binding |
| `ssh.hostTrustForgetCommit` | requestId, ticketId, binding | 最终forgotten回执 |
| `ssh.hostTrustForgetCancel` | ticketId | cancelled回执 |

group仅为宿主定义的password/config；提交使用新的admitted requestId，保留票据原context/
期限。import、keyDelete、hostTrustForget三类明确区分，交叉提交/取消拒绝。当前源码只有
import/keyDelete布尔分派，目录票据已经移除，不恢复历史目录代码。后台/锁屏/消费者失效/
取消/过期/bridge关闭立即撤销对应context，排队后及发布前再查；重放不得二次写入。

查询返回 `{host,port,groups}`，groups固定password/config两组，字段为
`{group,path,exists,records,canForget}`；records字段固定
`{line,algorithm,fingerprint,marker,selector,reason}`，字符串枚举对应上述wire。
算法为有界公开算法文本（允许显示被阻断的证书/不支持类型），不能套用认证key allowlist。
preview返回 `{ticketId,binding,host,port,group,path,records}`，必须非空且全部reason=none；
commit返回 `{forgotten:true}`，cancel返回 `{cancelled:bool}`。不额外传removable字段，
由reason推导。固定业务错误为 `SSH_HOST_TRUST_FAILED/BLOCKED/EMPTY/LIMIT`，保留原vault/
cancel/timeout/BUSY错误，前后端严格字段及一致性检查。

## 验证、兼容与回退

验收对应父PRD的HT1–HT8，执行顺序/命令见父implement本轮章节。native证明真实parser/
指纹、端口/大小写/IPv4/IPv6/hash、共享/marker拒绝、无关字节完全保留、限额/故障和零外部
效果；JVM验证完整对象身份、单replacement图、票据生命周期及uncertain；Flutter验证
typed边界、预览确认、目标代次、保护回执和布局。

ARM C-only/QEMU、Android JNI、正常/隔离APK构建、真实新KEX/NEW审阅及人工体验分列。
本次353 Flutter/828 JVM、真实native矩阵、20张字体布局、normal/fixture构建lint和三APK
审计通过；指定板JNI三方法及真实CHANGED→忘记→NEW/Known夹具已实际通过并清理，
[实际证据/失败历史与限制](verification.md#20261102-ssh-host-trust-device)单列。原生负分类
在任何host批准回调前拒绝，仅新增owner/role/view受约束的诊断enum及固定类型异常，
避免被通用STEP吞掉；不通过异常文字猜测分类，取消/过期/失效优先。
正式5e70df40…已[保留数据安装并启动](verification.md#20261102-ssh-host-trust-formal-install)，
新页面已获用户人工验收通过，不把本切片当作完整R5或父AC完成。

不改vault schema、不迁移旧信任/密钥。回退仅撤入口/新增API，保留用户已完成的忘记操作，
不恢复旧信任；正常连接始终沿原核验。提交、安装与归档不由方案审阅自动授权。

# SAF 导出同名覆盖事务调查

2026-10-10。用户本轮决定：同名提示覆盖，失败删除本次部分并保护原文件；主会话拟采用独立临时写入/读回后提交，清理失败报告位置。本文只调查方案和界限，不修改生产代码，不授权设备或新的业务实施。

## 官方支持与调查初始源码（实现前历史）

- `ACTION_CREATE_DOCUMENT` 不能覆盖同名文件，系统会追加编号；tree picker 才提供目录及后代访问，Android 11+ 有目录选择限制。不调用 `takePersistableUriPermission` 不取得持久授权，但也不能把未持久化误写成系统 grant 立即失效；应用应限制本次使用，结束时丢弃内部 capability，不宣称能单独撤销系统临时 grant，也不影响已有其他用途授权。[官方文件访问指南](https://developer.android.com/training/data-storage/shared/documents-files)
- rename 返回的 URI 可能有新 document ID，旧 URI 随之无效；客户端必须使用返回值更新事务记录，null/异常不能当成功。[DocumentsContract](https://developer.android.com/reference/android/provider/DocumentsContract#renameDocument(android.content.ContentResolver,%20android.net.Uri,%20java.lang.String))
- provider 可按内部规则改变 rename 后名称。公共 API 没有 expected-current-digest、atomic replace、rename-no-replace 或两次 rename 事务参数；因此不能仅凭 rename flag 推定原子替换/不覆盖竞争文件。[DocumentsProvider 合同](https://developer.android.com/reference/android/provider/DocumentsProvider#renameDocument(java.lang.String,%20java.lang.String))
- flags 表示支持 create/write/rename/delete；size 和 modified 可能未知。支持标志和已知元数据都不构成并发版本锁。[Document 列及 flags](https://developer.android.com/reference/android/provider/DocumentsContract.Document)
- stream 可以是 pipe/socket；写模式和截断具体实现依 provider，query 使用显式 projection，limit 支持也取决于 provider。[ContentResolver](https://developer.android.com/reference/android/content/ContentResolver)

本调查最初读取的实现前快照中，`AppSecretsBridge.java:296-298` 用 create picker；`:350-358` 直接对新 URI 写 `wt`，没有独立 stage/读回/同名确认。`:372-378` 失败尝试删除、失败后截断这个新 URI，清理异常吞掉。`VaultExportWriter` 延迟标签且保留 post-tag uncertain，这是有效写入边界，但不等于覆盖事务。新的 cleanup 必须返回已删/残留/未知，不能继续吞掉并只显示普通失败。这里的行号和行为均为旧快照，不描述随后新增的导出事务实现。

## 主会话收敛方案：新建 final，不向可预测 final rename（推荐）

后续讨论的最小改进可以实施：只把用户明确批准的旧文档 rename 到随机 128-bit rollback 名，**final 由 `createDocument(finalName)` 新建**，绝不向已存在的 URI 开写流，也不把 temp rename 到可预测 final 名。官方 provider 合同要求 create 返回新分配 document ID，允许为避免名字冲突改变名称；因此按正常遵约 provider 的新建合同，可以保护既有文档内容，不需要无限扩张为防恶意 provider 的绝对保证。[官方 createDocument 合同](https://developer.android.com/reference/android/provider/DocumentsProvider#createDocument(java.lang.String,%20java.lang.String,%20java.lang.String))

推荐阶段为：

1. 验证当前加密快照与本次口令，选本次目录/命名，有界 collision 查询；exact 旧 ID/观察版本经显式确认。
2. 独立随机新 stage 写完整密文、close、EOF/digest 读回，登记本次所有权。此步骤保持主会话已经提出的先 stage/读回承诺，旧文件尚未改变。若实现选择只用已认证内存快照而不做 provider stage，需要同步其表述，不宣称已经实际读回。
3. 请求/快照/确认仍有效后，旧文档 rename 到随机 rollback 名；接收新的 URI/ID，检查实际名及旧内容保持。new-only 路径跳过旧 rename。缺 create/必要 read-write-delete/旧 rename 能力拒绝覆盖，不应禁掉独立新文件导出；独立导出仍报告清理不可用的真实限制。
4. 重新查 finalName，再 `createDocument(finalName)`。先核验返回 ID 不等于本次枚举中任何**当前存在**的对象 ID（包括当前 rollback）；实际 name 不符或出现其他 finalName 对象即拒绝。路径型 provider 在旧对象移动到 distinct rollback ID 后，新 final 可复用历史 original 路径 ID；需分辨历史记录与当前对象，不能仅因历史 ID 相同就拒绝，也不能把同名当新建证明。若返回当前仍存在的对象 ID，绝不开其写流、绝不把它列为可删对象。
5. 只写这个明确新建对象，close 后重新读 EOF/length/digest；验证目录中这个 final 的 ID/name 唯一。成功声明 `finalVerified`，取消与该声明排序。此前失败删除 exact 新 final/stage、尝试恢复旧名；恢复名被另一文档占用则不能 rename 到占用位置，保留旧 rollback，报告它的实际位置，绝不动新出现的无关文档。
6. finalVerified 后旧 rollback 删除与 stage 删除都是 postcommit cleanup。删除失败/不确定或取消均保留新 final，显示已完成及具体残留/未知状态；不执行通用 rollback 删除 final。

遵约 create 若碰到竞争同名文档可以失败、改名或产生可识别的重复名称；以上检查拒绝这些情况，不修改竞争文档。随机 rollback rename 仍有极低名字碰撞及异常结果窗口，调用前后检查并记录，未确认的可预测 final 名不再用于 rename。外部修改、provider 副作用/结果丢失、补偿失败和进程中断仍需准确 uncertainty/位置回执，而不是承诺原名字必恢复或删除必成功。

这个推荐满足用户可实现的真实意图：ctOS 不截断原文件，失败只清自己新建的部分，覆盖由显式确认控制，旧内容在新备份确认可用前保留。不能据此写成多文档原子事务或平台已验收；需要下文故障矩阵。下面原 temp→final rename 的讨论保留作为替代被收敛的原因，其“未知 provider 不支持覆盖 rename”结论不扩大为禁止正常 `createDocument` 新建 final 的方案。

## 初始 temp→final rename 方案的阶段（风险分析，不推荐直接使用）

以下是合作且已验证 provider 上的补偿事务设计，**不是任意 SAF 的原子保证**。

1. 本次新输入库主口令并真实认证当前一致快照。选择 tree + 输入单个 bounded 文件名；只查询所选目录直接 children 的 ID/name/flags/MIME 等必要元数据，检查碰撞，不递归、不读无关内容、不扫描其他目录。必须完整读完有界枚举；超限、cursor null、部分加载/EXTRA_LOADING、重复/歧义名字不能视为“没有碰撞”。provider 忽略 limit 时客户端也有行数/时间/取消上限，超限拒绝。
2. 没碰撞则准备新建；恰有一个可用目标才展示同名覆盖确认，绑定 tree authority/document ID、精确旧 document ID、实际 name、可用 size/modified、source snapshot digest/request/epoch。不要把 display name 当身份。若需旧内容 digest，只读取这一个明确选定要覆盖的文档且有字节上限，不读取其他文档；未知元数据或 digest 都不能变成原子 CAS。
3. 创建随机唯一 **临时名称**；document ID 由 provider 生成，不能自造 ID。检查返回对象不等于任何已知旧/无关 ID、实际名称和父目录归属，登记为本次新文件后才打开写流。写入完整密文，close 后重新 open/read 到 EOF，检查精确 length + ciphertext digest（可与已认证出口快照逐字节比较），确认读到同一版本；不解析未认证明文。检查实际 write/read/rename/delete 能力，无需为了通用 stream 导出强求 `rw`/可 seek。若实现确需 seek，则这种 provider 可用性必须单列。
4. 提交前再次复核请求/库快照、旧 ID/元数据或 digest、目录碰撞及确认绑定；变化则重新确认或终止。旧对象此时从未用 `w/wt` 打开、从未删除。
5. 在有可证明 no-clobber 行为的 provider 上，把旧对象 rename 为随机 rollback 名（记录返回新 URI）；temp rename 为 final（记录返回新 URI）；检查实际 names/IDs、唯一 final、最终内容 digest，检查旧 rollback 保留。发生 rename/name readback 不符且仍处于可补偿阶段，只删除本次创建的新对象；恢复精确旧对象名称，不能通过“同名搜索”选另一个文件去改/删。
6. **确认新 final 可用后进入完成阶段**，保持新文件。删除旧 rollback 是已批准覆盖的提交后清理；删除失败或是否已删不明，保留新 final，报告旧 rollback/残留位置，不再返回“旧状态完全恢复”。取消/超时与该完成阶段排序，不能在不可逆旧删除后再删除新 final。旧删除结果不确定尤其不能执行通用失败回滚，否则可能损失唯一有效备份。

## 事务记录和取消规则

记录角色必须区分 `created-new`、`preexisting-confirmed`、`renamed-old`、`final-verified`、`old-cleanup-started/done/uncertain`，包括 request ID、tree/authority、实际 URI/ID、实际名字、预期密文 digest 及操作开始/完成状态。只对 `created-new` 所追踪的精确对象执行失败删除；旧对象只能恢复名称，成功完成阶段才删除已批准旧对象。改名后的 ID 要沿返回链更新，不能继续用 stale URI。

provider 调用抛错/超时不证明没有效果。rename/create/delete 可能已经发生而 response 丢失；需要有界重新核对，不确定时停止破坏性动作并显示位置/旧新角色。cleanup 不应受“原业务已取消”guard 一概跳过，否则必留本次部分；它有独立且只清自己对象的能力/期限，不延长库解锁或授予新的业务动作。主线程不能等 provider I/O。

取消发生在 stage/提交前：删除本次新 stage，旧文件保持内容与名字。取消发生在 rename 补偿阶段：等待 worker 报告恢复/残留结果，不能 UI 先普通 CANCELLED。新 final 已验证且旧删除已开始：返回完成或完成但清理不确定，保留新备份；已有 `SecretRequest.completion`/保护回执应承接此语义。

RAM ledger 不能解决进程死亡/重启中断。旧改名后进程死亡可能留下旧 rollback；新改名后可能留两份完整密文。若要求恢复提醒，需私有的最小事务 journal（仅身份/阶段等元数据，无口令/明文库），在请求 mutation 前写 intent、后写 observation；未 persist tree grant 时恢复需用户重新选择同一目录，不能自动扩大权限或扫描。journal 也不能消除 response 丢失时的身份不确定，不能按 temp 前缀批量删除。若不做 journal，必须明确本轮只保证运行中补偿，崩溃残留由用户按位置恢复；主会话决定是否满足已批准要求。

## 无法由通用 SAF 保证的事项

- **no-clobber race**：query 后其他 App/provider 可创建同名文件；rename 没有无覆盖原子条件。事后名字检查可以发现自动改名/重复，但若 provider 已覆盖竞争文件，它不能发现或恢复被覆盖对象。因此“最终检查碰撞后拒绝”只能作为必要检查，不能作为“不会无确认覆盖”证明。
- 旧 metadata/digest 核对之后仍可被并发修改；rename 后旧 rollback 也可变。旧清理前 digest 重查仍不是原子 compare-delete，不能保证删除的内容与用户确认版本相同。document ID 精确绑定只能避免按名误删其他对象。
- 两次 rename 中间可见旧名字空缺/新旧并存；provider 故障或 crash 可让恢复名称失败。旧内容没有由 ctOS 截断，但“原文件原名始终存在”无法保证。
- stage close/readback 成功仅证明 provider 当时返还正确密文，不能保证云端同步成功、服务器持久化、磁盘 fsync 或之后不会外部变化；delete flag 也不保证调用成功。
- tree grant 覆盖整个子树，应用选择只用直系目录/本次对象是自身约束，不是 OS 单文件隔离；不用 persist 减少长期使用，不改变瞬时 grant 的范围。

这些结论依据公共 API 缺少对应原子条件作出推断，**没有断言所有 provider 实际都会覆盖或破坏文件**。

## 初始 rename 方案的 provider 界限与替代

必须把通用 capability flags 与经过证明的 provider 语义分开。可以构建纯 transaction engine + fake provider 故障/竞争矩阵，但不能据 fake 测试宣布真实安全覆盖。若“绝不未确认覆盖”是硬约束，对未知 provider 应拒绝执行覆盖 rename，明确提示换受支持位置/另存新文件；不能仅凭 authority 白名单或一次 rename/delete 探测就推定没有竞态。特定 provider 支持需要实际源码/文档或真正 no-clobber/版本操作，并单列真实集成证据。

无需 tree 的安全退路是 `ACTION_CREATE_DOCUMENT` 新文件导出+读回，失败仅清本次 URI。这保留较窄文档 grant、系统同名编号，**不满足同名覆盖**；必须作为明确另存选择而非偷偷把覆盖改成另存。选择旧文件 `ACTION_OPEN_DOCUMENT` 后直接 `wt` 即使先 stage，也会先损坏旧内容且无法保证恢复，不能采用。单个文件 grant 也不自然包含父目录创建 sibling 的权限；EXTRA_INITIAL_URI 只是 picker 起始位置，不能充当 tree 权限。

不要把“任意 SAF、同名覆盖、失败原文件原名必保留、无扩大目录授权”四项同时写为已保证。初始 rename final 路线应保持未知 provider 覆盖未支持；主会话后来收敛的 `createDocument` final 路线则可依据新建合同和能力检查推进，保留补偿/崩溃/外部并发 uncertainty。无额外 Android broad storage permission、无持久 tree grant；不触碰库内 AtomicFile 和 bundle worker ownership。

## 最小验证矩阵

engine 单测覆盖：无碰撞/拒绝覆盖/目标换版；写/close/EOF readback错；create返回旧ID；rename换ID/改名/response丢失；late collision；旧rename后cancel；新final验证后旧delete前/后cancel；旧delete已生效但抛错；cleanup失败和rollback恢复失败；partial/loading/超限目录；外部修改旧rollback。所有断言检查精确角色及“绝不清理无关对象”，尤其旧delete不确定时新final必须保留。

平台检查另外覆盖目标 provider 的 flags 与真实 rename/readback/delete、云端/断网/权限撤销、系统 UI 及后台取消/进程中断。最初调查没有执行新测试、安装或设备访问；随后实现/检查时间属性见下节，不将最初研究状态误当当前无代码。

## 2026-10-10 初稿实现及独立 review（尚未冻结）

主会话随后分派 owner 实现 `VaultExportTransaction`/`SafExportProvider`/`VaultExportJournal`、Bridge 与 Flutter 独立 export preview/commit/cancel/recovery/receipt。Owner 已报告 13 项 engine JVM 初稿检查通过；reviewer 读取这 13 个测试源，尚未核对最终冻结日志，不能代表全部故障或真实 provider 已验收。

初稿独立检查发现必须修复的具体问题，已交 owner（未并行编辑生产文件）：

- journal 只调用 AtomicFile.finishWrite 而没有确认实际持久记录；无声失败可能让 final_verified 未落盘却删掉旧 rollback，重启误读旧阶段后删除唯一新 final。必须 fsync + 有界 exact readback，确认完成记录前禁止不可逆旧删除，增加真实 owned-file 恢复夹具/持久状态故障回归。
- 恢复/清理按 recorded ID 删除 created role，未证明当前版本/内容仍是自身对象；旧 rename 的返回 ID 未排除 baseline 无关对象、旧名恢复未检查记录版本。需角色冲突校验、证明不足保留并准确报告，不以名字或失效 ID 猜对象归属。
- finalIntact 不通过时仍可纯 exported 且 new-only 清 journal；应保留 final unknown 回执和恢复记录。完成后旧删除不确定不能删除 final 的原则已正确写入 engine，需故障回归保持。
- provider 查询计时器只持续到 resolver.query 返回，cursor 遍历可能仍阻塞；EXTRA_LOADING 只查开头。应覆盖 cursor 生命周期、EOF 再检查完整性。journal.load 不能先 readFully 再查大小，应对实际 AtomicFile 恢复来源执行硬上限读取和角色/阶段一致性校验。
- 初稿 fake Journal 存同一 live State 引用，不能证明 intent/observation 的持久副本和崩溃恢复；故障测试应保存不可变副本并区分 before/after durable record。

Owner 正在修复，最终代码、计数及独立证据待冻结后追加。本节仅报告初稿和具体发现，不将 13 初稿通过写成最终质量结论；设备/任意 provider 原子性仍未验证。

## 2026-10-10 联合源审与本地检查追加

上述初稿缺陷随后由 owner 修复，reviewer 逐项独立复核实际源码，包括 untracked 新文件；没有并行修改 owner 的生产文件。当前实现只向已证明 fresh ID 的新 stage/final 开写流；旧文件只按 exact 观察版本和显式 ticket 同意改为随机 rollback 名。finalVerified 后保留新 final，只有已确认持久完成记录及再次确认 final 内容时才清理批准的旧 rollback；证明不足返回 exact role/location/present-or-unknown。恢复只在用户重新选择同一 tree 后处理记录对象，created role 要同时通过版本及完整 ciphertext length/digest，未知 create/rename 返回结果不按名字猜归属。

Journal 增加候选 flush/fsync、finishWrite 后有界 exact readback，以及 finishCalled 标志；调用 finalization 后即便 readback 抛错也不再 failWrite 删除新 base。load 对实际 AtomicFile.openRead 来源执行 32 KiB 上限，clear 核对 base/.bak/.new。五项独立 cache-directory Android journal 夹具包括 silent finalization 和完成后读回失败，未接触应用真实 journal；这些夹具当前仅编译，未在设备执行。

联合源审又修复了三个实际生命周期问题：prepared ticket 必须在首个可取消 guard 前仅供失败补偿附着，不能返回空 not_prepared 回执而让 stage 继续存在；已完成 ticket 的结果重放没有新 effect，取消/超时/关闭不能覆盖缓存的真实终态残留；recovery picker 取消/错误 tree 在没有 transaction 时仍从私有 journal 投影 unknown 位置，不冒充已清理。实际 engine 首 guard 故障回归检查六个稳定错误码及删除成功/失败的十二种组合，旧文件保持、stage 真正删除或留具体残留，提交授权仍独立 fail closed。跨 Activity lease 用纯 Object token，静态字段不持 Activity。

无法读取的 journal 不再阻断普通 status/bootstrap/unlock：status 只返回可选稳定 `exportRecoveryError=VAULT_EXPORT_RECOVERY_REQUIRED`，UI 严格校验此字面值并提示，普通改口令保持可用。新导出/recovery 的 load 仍拒绝，不 clear、不覆盖坏记录，不输出原始异常或猜 document ID。导出按钮仅在此限制下不可用。

实际 adapter 夹具补齐 DocumentsProvider 必需的 MANAGE_DOCUMENTS provider 保护；仅测试 APK 的 fixture provider 对确切 target 包授予 fixture authority/tree/root 的临时 read/write/PREFIX 权限。产品 manifest 没有此 provider、MANAGE_DOCUMENTS 或新增存储权限，未取得持久 tree grant。API 28 不支持的 String.repeat、Files.readString、InputStream.readAllBytes 使用已替换为兼容的字符数组、Files.readAllBytes（API 26）及 bounded stream helper；fixture 的真实启动、授权链和 provider 行为仍需单独平台运行，不能从编译推定。

Reviewer 独立读取本地最后一轮日志与 XML：101 项 JVM（Vault35/Session7/Writer3/Bundle16/Restore17/ExportTransaction23）全部 0 failure/error/skipped，XML 时间 2026-10-10T03:25:43–57Z；`/tmp/ctos-export-android-final.log` 的 testReleaseUnitTest、lintRelease、assembleReleaseAndroidTest 组合退出成功，lint 0 errors/5 既有 warnings。Flutter focused 35、full 138 和 analyze 无问题分别见 `/tmp/ctos-export-{focused,full,analyze}.log`；`git diff --check` 通过。Android 共有 Store4/Journal5/Adapter1 项 instrumentation 夹具完成编译，未运行；JVM provider/reviewer 仍明确为 synthetic。

Reviewer 实际查看 `.devhome/secrets-ui-visual/` 的 320 dp、2x CJK/Material 字体导出碰撞、文件名键盘、成功带旧残留及返回、recovery 选择目录/残留，以及恢复 coverage/trust/confirm PNG。确认与返回可滚动到达，恢复投影稳定类别已中文化，opaque credential binding 保留 native/wire 绑定用途而不出现在正文。这里只证明实际 Flutter 渲染，不证明 Android 截图/录屏保护。

本地质量检查不关闭真实 SAF/进程中断/Keystore/屏幕保护关卡；生产 mixed import 仍拒绝，真实 OpenSSH 语义、known_hosts 审阅及 Android native 跨设备恢复继续由联合 AC 验证。未执行设备、安装、提交、归档或发布操作。

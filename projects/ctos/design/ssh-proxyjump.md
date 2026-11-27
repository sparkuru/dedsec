# SSH 受管 ProxyJump

状态：产品源码已开放受限的单跳 `ProxyJump`：跳板和目标须事先有受管主机信任，采用自动认证；每个需要密码的主机须已有保存的密码，并分别确认使用。本机隔离真实 sshd 已用生产连接器、加密库和 JNI 跑通目标 PTY。多跳、首次／换键信任、临时密码、私钥和交互式认证仍未完成；Android 设备与真实账号尚未验证。

## 当前边界

固定 OpenSSH 的配置解析器能解析最多八跳，但产品列表和连接器目前只允许一跳加目标；更长路线在联网前返回 `SSH_JUMP_CHAIN_UNSUPPORTED`，不会静默回退为直连。列表与连接器均从受保护配置快照解析完整路线。`ProxyJump none` 保持直连；读取配置快照会解密并复制包含秘密的库内容。

连接器为每跳建立独立角色、主机证明及会话，采用共享视图、祖先调度和逆序清理；每跳密码仅在显示实际 endpoint 并明确确认后交付。认证方法遵循每跳有效配置次序：若服务同时提供 KBI 和密码且前者优先，会拒绝；服务支持密码时可在相关 Host 中配置 `PreferredAuthentications password`，并在自动检测中不选择私钥。服务端 `none` 成功可通过代码路径，但本轮真实双 sshd 仅验证密码认证。首次使用的闭环仍不完整：现有主机信任管理没有新增入口，手动连接与配置连接使用的信任文件也可能不同；只能经跳板访问且尚未被配置连接信任的目标，当前不能在该路径中首次确认。现有密码管理也没有独立新增入口。

## 2026-11-23 产品入口进展

本节以下部分保留开放前的开发记录；当前产品状态以上述边界和最新验证为准。曾改配置列表为完整路线识别，但复核发现 `ProxyJump none` 会被显示为可选，而当时的严格直连计划仍拒绝它，且双重求值会影响原有预算；因此当时恢复原列表策略。直连解析器随后已支持固定上游实际表示禁用的精确 `none`，现在列表已重新使用完整路线。逐跳已保存密码确认只展示实际主机和确认／取消，不允许静默使用凭据或输入当前尚不支持的密码。

严格直连处理禁用指令时，以固定 OpenSSH 传给 `parse_jump` 的同一原始参数判断精确的 `none`，并保留最终 `Options` 中真实跳板的拒绝检查。不能只检查分词后的单个 `none`：固定上游会把 `none # comment` 解析成名为 `none` 的实际跳板。Include、重复声明和 Match 的活跃指令也须逐行验证。固定解析器的 ARM64/QEMU 两道防线测试通过；不据此推定产品列表和直连已端到端通过。

跨跳取消与密码交付的效果许可已接入受限路径并经分层测试；首次信任、临时密码、密钥、KBI、多跳、后台和设备验证不能由该切片推定通过。

此前草案缺少逐跳服务端认证方法发现，且等待异步确认时会误 step 已完成 KEX 的 session；目标 `ConnectTimeout=2` 也曾被前一跳的 3.5 秒确认耗尽。当前路径按实际方法及配置次序选择，等待时继续推进需要的祖先与 relay，并按每跳启动时间计算传输期限、截断于总期限。本机双 sshd 的两次延迟确认和目标短期限已验证；native 发送许可也检查逐跳期限。

即使 Java 在 `beginJump` 前检查过当前跳的截止时间，线程也可能在检查后、native 通道建包前跨过该期限。逐跳时限须传到 native 的通道发起和实际发送许可处。可验证的时间合同是：每次发送须在期限前取得许可，已取得许可的一次非阻塞发送可能因线程调度在墙钟期限之后完成；之后不得再取得许可。取消则须阻止新许可并等待已许可动作排空。Java 检查本身不能证明这个顺序。

受限单跳草案对目标认证回复前的发送排空采用保守判断：它检查整个上游 SSH 输出，尚不能区分密码尾包与无关保活／协议输出。若目标已答复而无关输出恰逢背压，可能错误拒绝本次连接；该情形尚未在夹具中动态复现。此风险属于可能失败的可靠性限制，不得靠放宽凭据取消门禁掩盖。

## 首次主机信任的下一步设计（未实现）

2026-11-23 接续：测试专用的真实 Java／JNI child 已可将原始候选、来源、签名周期和会话绑定到一次性非授权审核对象；查询 native 签名与输出排空状态时不持 Java 的 session／callback／vault 锁。界面侧只提交决定，owner 在返回前重新检查来源、候选、角色和期限；界面可能暂时显示过期票据，决定本身不授权任何效果。[合同](../.trellis/spec/backend/ssh-proxyjump-child.md#scenario-signed-new-fixture-review-and-bounded-extension-input)。

固定 OpenSSH 的受限待审输入原语只对未认证、未压缩的 AEAD 连接检查并丢弃一条客户端初始提案实际广告过的 `EXT_INFO`，限制每次读取和报文总量，不执行认证或重协商。现已在测试专用真实 Java／JNI child 中与签名票据接通：精确 owner 在邮箱锁外分步推进父连接、relay 和受限 poll；已同意但仍有半包或待推进数据时，在原期限内继续等待。入站无数据只是时点观察；产品连接器未接通，临时连接不能交付凭据或提交信任。生产入口、信任写入的原子准入和清理后重连仍是后续关卡。[本轮验证](verification.md#20261123-ssh-jump-pending-integrated)。

固定 OpenSSH 也可能在 child 进入待审状态前，通过普通握手路径处理 `EXT_INFO` 并更新扩展状态；上述受限读取只约束待审后的输入。新的联合测试在签名 pending 后才发送合法扩展，并验证受限 poll 实际消费；它不证明先到报文的时序已动态复现。[调度研究](../.trellis/tasks/10-08-ssh-terminal/research/proxyjump-pending-poll-scheduling-20261123.md)。

目标只经跳板可达时，当前拒绝 NEW 会阻塞首次使用。不能只把现有审批回调打开：child 在候选交付前拒绝 NEW，逐跳 provider 没有允许写入的授权；一次信任 CAS 在最终写盘前推进库 revision 并使 jump effect generation 失效。即使同一 attached view 保留，它仍是旧文件快照，child receipt 和已冻结的 epoch／revision 也不能继续授权密码。现有 factory 只创建一次连接，没有确认写入后重建整条链的机制。

已增加一跳 provisional NEW 状态：真实 child 的候选和签名 KEX 可在不产生正式 host proof 的情况下到达 pending，认证和 shell 仍拒绝；ready 只观察签名和当前输出排空，不是可持久化的信任授权。测试宏下的 Java/JNI 夹具使用真实 receiver、source 和候选；生产 JNI 尚未注册该路径，回调仍为同步且不能等待产品界面。测试票据可在 JNI 返回后模拟决定；产品审阅、写入与重连未实现。[合同](../.trellis/spec/backend/ssh-proxyjump-child.md#scenario-provisional-new-child-key-native-only)。

早先的内部一跳 child 保守空闲检查逐项检查原生输入与半包、中继缓存、父连接及套接字；发现 child 入站数据或已观察的关闭就拒绝，父连接仍有待处理数据或输出未排空则要求继续 pump。该旧检查不解码报文，因此连合法的 `EXT_INFO` 也会拒绝；新增受限输入原语与其分开，不能把旧检查的拒绝结果视作新解码器的异常报文测试。多处检查都是时点观察，不能证明其后没有数据或关闭。[内部合同](../.trellis/spec/backend/ssh-proxyjump-child.md#scenario-provisional-new-idle-snapshot-and-review-waiting)。

此过程中修复了 child 信任提供器、来源和 receipt 的清理重试：明确尚未开始的原生失效可由原 owner 重试；开始或结束结果不明确时保留 owner 和父连接，不重复结束全局 invalidator。子连接一旦进入关闭，普通父子发送入口拒绝新用；先前清理步骤失败时仍尝试擦除独立候选数据。真实 JNI 的可重试和不确定结果分别经同 JVM 与独立进程验证；该合同不覆盖所有其他旧失效调用。[结果](verification.md#20261123-ssh-jump-provisional-new)。

候选方向是在精确 NEW 候选经用户核验后仅临时固定同一把密钥，完成签名 KEX 与上游排空，再按角色、来源、祖先活性及撤销顺序执行 CAS；只有确认持久化成功才关闭旧链、重新读取配置与库、签发新角色和 generation 并连接。失败、取消和写入不确定不得自动重试；新连接的每跳密码仍须明确确认。此方向尚需证明最终 CAS 与祖先取消的顺序，不能把磁盘 I/O 放进 native 的短时许可。

后续写入还必须在同一授权边界保留或转移精确候选：单独的候选身份检查只是时点采样，候选可在之后被消费，而 native generation 不会因此自动变化；generation 本身也不包含逐跳期限。现有受管库的 revision 校验和写入回执可以复用，但不能替代候选所有权、期限和取消的共同排序；先退役旧链再写入同样需要新的受保护交接与 view 生命周期合同。[调用链研究](../.trellis/tasks/10-08-ssh-terminal/research/proxyjump-new-write-boundaries-20261123.md)。

现已在测试夹具中增加非授权候选保留：只有真实签名票据经模拟同意、受限 poll 空闲及提示关闭成功后，邮箱才签发绑定精确 child／来源／候选的一次性 claim；provider 与候选各自在锁内阻止 proposal 或别名重复消费，关闭仍可撤销和擦除。这个保留不转交候选正文，也不允许写入或认证；其邮箱完成点只与该邮箱取消及已观察的撤销排序，不能替代跨链效果准入。[合同](../.trellis/spec/backend/ssh-proxyjump-child.md#scenario-approved-fixture-candidate-reservation)／[验证](verification.md#20261123-ssh-jump-candidate-reservation)。

2026-11-25：测试 child 的已批准保留现在可由原 owner 一次性转交原候选正文，原候选不再保留字节或接受重复消费；转交仍由原 provider 管理，候选、审核或 provider 撤销会擦除正文。这是夹具所有权原型，产品连接器没有调用它，也没有可交付写库的接口。来源、角色或 view 失效使后续检查失败，但正文仍须等既有 owner 清理；独立反例确认仅 native generation 已失效时 Java 转交仍可能成功。真正写入必须另外建立原 generation、原期限与所有撤销的准入顺序，并处理结果不确定和旧链清理。[合同](../.trellis/spec/backend/ssh-proxyjump-fixture-transfer.md)／[验证](verification.md#20261125-ssh-jump-candidate-transfer)。

同日发现现有取消错误路径：真实测试 JNI 的 native 失效 begin 抛错后，旧的模拟 terminal 仍可执行排队 write 和 step。现已增加终端本地停止准入，并分别保护 session 及 shared view 自己的取消／关闭；创建期、信任写入、独立 callback／lease 和整链清理仍未统一完成，已准入操作或关闭也可能在停止后物理结束。重试草案因服务启动未确认与取消／链清理边界保持撤回。[终端合同](../.trellis/spec/backend/ssh-terminal-stop-intent.md)／[会话合同](../.trellis/spec/backend/ssh-session-cancellation-ownership.md)／[本轮验证](verification.md#20261125-ssh-session-view-cancel)／[原始复现](verification.md#20261125-ssh-cancel-invalidation-failure)。

信任回执确认现在以停止请求而非仅以 native 已取消位排序：停止先发生时，等待中的失效或失效异常均不会再确认可能已写入的回执；确认先发生时保持原结果。这不建立信任写入本身与祖先撤销的共同准入，也不开放首次信任。[合同](../.trellis/spec/backend/ssh-trust-commit-ack.md)／[验证](verification.md#20261125-ssh-trust-stop-order)。

外层停止现在在 native 撤销前对 connector 可见；未附着 owner 清理、终端运行结果及最终 handoff 分别与停止排序，撤销未知仍保留原资源。当时本机发现锁库 begin 异常可发生在 Java key 擦除之前，bridge 的异常结果也可能未送达。[当时验证](verification.md#20261125-ssh-stop-owner-delivery)。

2026-11-27：vault 锁定、后台切换或 trust epoch 更新的 native 失效失败时，Java key 与 epoch 现分别失效，原 lease／未知状态保留；独立 lease 的 native destroy 失败也会传给 shared owner，不能误记取消成功。vault 外层 begin 失败时该 lease 仍可能 active，已准入效果可能完成；bridge 结果投递、材料所有权与整链撤销尚未完成，首次信任与重连仍未开放。[验证](verification.md#20261127-ssh-vault-revocation)／[合同](../.trellis/spec/backend/ssh-vault-revocation-ownership.md)。

同日未启动 connector 的本地密码／口令退役也与真正 create 准入和共享 STOP 排序；外层 owner 已接管仍须由该 owner 清理。真实 host JNI 分别覆盖失效排空及 begin 失败，但已进入创建后的迟到材料、服务 owner 发布及异步结果仍未统一。[验证](verification.md#20261127-ssh-connector-retirement)／[合同](../.trellis/spec/backend/ssh-connector-unstarted-retirement.md)。

后续服务已先发布 owner 并保留工厂实际结算结果，bridge 对结果最多尝试一次回复；这不证明回调送达或原生资源已释放。已启动材料与共享 STOP 的最终排序、根 session 的认证效果、真实 Android 生命周期及整链清理仍未完成。[最新验证](verification.md#20261127-ssh-result-delivery)／[合同](../.trellis/spec/backend/ssh-connection-result-delivery.md)。

本轮为部分已启动材料增加了安装与 STOP 的局部排序，但独立 JNI 许可实验观察到 Java STOP 已发布而 native effect 许可仍可获得的窗口；实验未执行 SSH 认证或网络发送。native credential／报文效果、mailbox 和整链停止仍需处理，不能据局部安装准入推定已保护所有跨跳效果。用户要求停止测试，最新源码尚未做完整联合验证。[记录](verification.md#20261127-ssh-started-material-preview)。

首跳未知主机也须单独完成：它使用独立 JUMP 角色和逐跳信任来源，但当前仍拒绝 NEW；现有签名后 pending 路径只覆盖有 relay 的 child。普通直连在同步主机回调中先审核并写入、随后才完成签名 KEX，其 proof 允许凭据，不能直接代替 child 的非授权票据；跳板逐跳期限、配置 snapshot 和写入后的 revision 变化也需要重新排序。[首跳调用链](../.trellis/tasks/10-08-ssh-terminal/research/proxyjump-root-new-trust-20261123.md)。

临时 pin 必须是独立于 `SshHostKeyAuthority.Proof` 的一次性 pending 状态：现有 `Proof.permitsCredentials()` 会允许 NEW 交付凭据，而 native 对 NEW 的正常完成要求 `CAS_COMMITTED`。不能伪造成功回执，也不能把普通 KEX completion 当成 durable proof。候选应绑定实际角色、endpoint、指纹、来源及摘要；在 pending 期间 native 和 Java 的认证、秘密和 shell 入口均须拒绝。首先可在受限夹具中验证该状态及清理，产品 NEW 入口在整个 CAS／重建方案完成前继续拒绝。

NEW callback 同步运行于 native `session.step()`；用户审阅期间不能重入父 session 的 JNI 锁来 pump。实现时需设计可暂停的审核状态或明确证明等待期间和提交前祖先的有效性。复用现有 review/mailbox、candidate 和 trust receipt 不会自动解决这些约束。

另一候选顺序是先仅在内存临时固定实际收到的 key、在禁止认证及写库的前提下完成签名 KEX；待 JNI step 返回并排空上游后，再展示该已验证指纹并取得用户明确同意。签名证明的是对方拥有该 key，用户仍须独立核对它属于目标；目标可以在用户同意之前观察到握手。等待时 owner 必须能继续 pump 跳板和 relay；现有普通 child `step()` 在 provisional pending 后拒绝继续，已有密码 prompt poll 又要求正式 host/KEX proof，因此需要独立的非认证等待机制。任何将此路径接入产品的实现都必须显式保留 `StrictHostKeyChecking yes` 对 NEW 的拒绝：当前此策略在普通审核回调中，单纯绕开该回调会跳过它。签名里程碑与候选、来源、KEX cycle 及用户决定仍须绑定，不能用一次 ready 采样代替最终原子写入授权。[具体调用链与待测条件](../.trellis/tasks/10-08-ssh-terminal/research/proxyjump-new-post-kex-review-20261123.md)。

即使选择“确认保存后由用户重新连接”，也须先完成旧链的可重试清理并等待对应 Android service owner 实际释放；现有连接 future 可能早于清理完成，提前重试会得到 `BUSY`。信任写入还会改变配置 binding，重试需重新读取并复核所选 alias、实际目标和用户，不能复用旧绑定。同一 request 内自动重建还会让 hop 从目标回到跳板，与当前界面逐跳顺序检查冲突；若不引入可信的尝试代次，应使用独立新请求并重新逐跳确认密码。[调用链与锁序研究](../.trellis/tasks/10-08-ssh-terminal/research/proxyjump-new-trust-cas-retry-20261123.md)。

## 连接主线与阻塞顺序（2026-11-20）

本节为当时的历史计划；当前实现与验证范围以上面的 2026-11-23 更新和最新验证记录为准。

此前已完成的配置、通道、中继、子会话和晋升是互相依赖的前置能力；它们尚未组成产品链。下一阶段以一条可运行、可清理的单跳连接贯通 Java owner、JNI、原生父子会话及真实秘密库为首个集成检查，再扩展到已有目标要求的多跳、后台和界面，不把单跳视为全部验收。

1. 让各会话借用同一个真正登记的 Java lease，并让链 owner 保留它，直到所有依赖该视图的原生借用已确认退役或关闭；锁屏和取消仍立即撤销。单次 native 释放或 Java 引用计数不能代替完整清理确认。
2. 将已实现的内部 JNI 父子连接接到产品逐跳角色、逆序清理及原 owner 重试，先保持产品拒绝门禁。失败路径必须能取得并保留所有已创建资源的清理所有权，不能只覆盖成功路径。
3. 解决信任快照：第一跳提交后，只刷新 Java 操作或复用旧 native 内容都不够。内部 Java 子连接现可将同一次真实库读取产生的 receipt／wire 交给实际 JNI 原生分类，并核对角色、路径、view、epoch／revision 和 KNOWN／NEW／CHANGED 的 base digest；原始 borrow 继续提供活性。当前 fresh Java 路径在真实祖先效果保护完成前拒绝信任写入及认证，不能直接修改有活跃 borrow 的视图。真实库、生产 Java callback 和真实 JNI 尚未在同一连接中联合验证；own CAS 后的新来源确认及最终效果仍待完成。
4. 将真实祖先失效与主机信任提交、凭据最终复制及输出发布排序，保留可能已提交但未确认时的 `VAULT_COMMIT_UNCERTAIN`。随后用真实 Java 回调、加密库和 JNI 联合验证，最后分别验证实际服务器、Android 及后台生命周期。

当前 child 信任快照的草案为每个未晋升会话保存一份完整 `mlock` 视图，同时原始租约仍保留；内层目标 RUNNING 之前可能同时存在多份。受 64 KiB 等进程锁定内存限制时，较大的配置即可能导致单跳或多跳创建失败；当前没有证明八跳容量。失败必须保留 child owner 并完成清理；可扩展的有界共享或仅复制信任对象需要另行设计和验证，不能通过放宽锁定或提前释放仍被借用的数据掩盖限制。

目前 fresh 视图在 epoch 前进后允许快照中多个 `known_hosts` 对象的内容发生变化，其他已序列化文件内容及对象元数据仍须一致；native 分类只读取当前选择的精确路径。wire 对私钥仅包含声明长度和元数据、不含私钥内容或账号条目，故这一结构兼容检查既不证明整个加密库的其他秘密未变，也不证明变化都来自本连接的逐笔信任提交。未来 factory 必须另行绑定实际库修订、凭据来源、对应回执与效果顺序。

进一步核对产品调用链发现三个不能靠单跳夹具推定的接入条件：

- 内部 v2 role 已区分共同的链身份与每跳账号目标身份；v1 保持旧合同，混合版本被拒。产品 factory 仍需将链身份绑定冻结的配置来源，并以每跳实际账号创建原生 role；不能把共同链身份拿来访问不同跳的秘密。
- `TrustCommitState` 现在为各跳 provider 使用独立一次性回执，并与同一个 owner 取消状态排序；任一笔已写入而未确认时整链仍不确定。`JumpAdmission` 要求上一跳会话已观察到认证成功，沿精确 Java owner 链顺序复核活性后才能构造下一跳回执；native `beginJump` 仍需独立核实。它不是祖先取消与 trust CAS／秘密最终复制的原子 guard；同一 hop 的第二笔写入也需要新设计。
- 密码、私钥口令和 KBI 提示已携带并显示实际 selection／role 的 endpoint 与 hop；KBI 每跳的轮次独立，IPv6 地址与端口可区分。NEW／CHANGED 审阅和 dismissal 已携带 role ticket 的 hop，且连接交付等待每笔审阅决定的确认；排队中的首次审阅会在发布前重查票据，但该检查与发布不是原子操作。产品多跳接入仍须支持逐跳密钥选择：当前密钥口令判断依赖最终目标选择的 `_identity`；CHANGED 主机键审阅也仍要求 endpoint 等于最终表单目标，须先从 factory 接入与原请求和受保护配置绑定的可信逐跳路由。

旧的无信任来源 `prepareJumpChild` 现只保留 fake view／fake native 的 JVM 测试入口；传入受管 lease 或生产 JNI binding 均在领取 child 前以 `SSH_JUMP_EFFECT_GUARD` 拒绝。它不具备 fresh 效果保护，不能作为产品 factory。fresh 入口现要求前一跳的实际 provider、已消费的精确 `JumpAdmission`、子角色及 provider 的实际 observer 身份；这仍不是跨跳效果的原子保护。

迭代时先运行与改动直接相关的 JVM／native 定向测试；涉及 native 或集成边界的完整重型回归在集成检查点及最终源码稳定后运行，而不是每个测试断言更新都从头重复。已有全量结果只说明其对应源码和测试范围，不能代替最终版本验证。

## 本机隔离 OpenSSH 跳板测试环境

用户于 2026-11-20 授权后续会话自行创建本项目所需的本机 Docker 测试环境，工作约定见 [Trellis 规范](../.trellis/spec/trellis-plus/index.md#docker-development-entry-point)。下面的夹具不连接设备或用户服务器：一次性容器使用 `--network none`，两个标准 sshd 只监听容器回环地址，分别使用不同的临时测试账号、密码及主机键。容器结束后自动删除；不发布宿主端口，也不写入项目目录。镜像构建时需要软件包网络源，测试运行本身不需要网络。

```sh
# 从固定 Debian 基础镜像重建项目专用测试镜像；apt 包版本可能随软件源变化。
docker build --pull=false -t ctos-ssh-fixture:local - < android/ssh/Dockerfile.openssh-fixture

# 标准 OpenSSH 的跳板、目标和错误主机键检查。
bash android/ssh/docker-jump-test.sh

# 新输出目录必须不存在；依赖项目现有固定 Android/OpenSSH 构建输入。
./hako bash android/ssh/android-password-client-build.sh --output /app/build/ssh-jump-interop-replay --jump-interop
bash android/ssh/docker-jump-test.sh --driver "$(pwd -P)/build/ssh-jump-interop-replay/ctos_ssh_jump_interop_test"
```

脚本默认使用 `ctos-ssh-fixture:local` 并报告实际镜像 ID；需要使用等价的已有本地镜像时，可显式设置 `CTOS_SSH_FIXTURE_IMAGE`。原生驱动经宿主 `/usr/bin/qemu-aarch64` 执行，并只读挂载驱动及夹具脚本；它将生产 native SSH 源码的父子连接和 relay 接到真实 sshd，但使用测试回调、合成账号及事先配置的两把主机键，不经生产 Java、JNI、真实 vault 或 Flutter。成功不能代替产品连接、首次信任保存、Android 和后台生命周期验证。[实际执行记录](verification.md#20261120-ssh-jump-docker-interop)。

### 产品连接器的主机 JNI 复现

下列入口使用原样的 release Java `SshPasswordConnector`、生产 native 源码、实际加密的内存库和容器内真实 sshd，检查单跳逐次确认、目标 PTY 命令、错误目标主机键、目标确认时取消、多跳拒绝及直连／`ProxyJump none`。host 测试提供 Android 时间、进程、loopback socket 和 Activity 标记接口的 shim；它不运行 Flutter、Android 或完整 MethodChannel 生命周期。信任和两个合成密码由夹具预置，不证明首次使用闭环。[结果](verification.md#20261123-ssh-jump-product-open)。

在此机器上准备步骤使用已经安装的 `rust:1.90-bookworm`、`eclipse-temurin:17.0.14_7-jdk-jammy`、`eclipse-temurin:17.0.14_7-jre-jammy` 和 `ctos-ssh-fixture:local` 本地镜像；[脚本](../android/ssh/host-product-jump-test.sh)按镜像 ID 精确核对，缺失或不同即停止，不拉取镜像。JRE 从临时容器复制至本次输出目录；因为宿主 JRE 与 sshd 镜像的 glibc 不兼容，不直接挂载宿主 Java。Bookworm 构建容器只读已复制的源码快照、离线编译固定 OpenSSH 和生产 JNI native 源码；JDK 容器编译 shim 与测试类。它不使用上一轮随机临时路径，也不运行或替代 Gradle。

待源码冻结且没有其他会话运行共享 Gradle 时，从项目根目录执行。两个目录都必须不存在；Gradle 源码前后摘要能发现构建期间 Java 源变化，不能单凭摘要反推未知来源的旧 class 已与源码一致。

```bash
set -euo pipefail
TASK_JAVA_PROOF=/tmp/ctos-product-jump-java-proof
TASK_PRODUCT_OUTPUT=/tmp/ctos-product-jump-replay
(umask 077; mkdir "$TASK_JAVA_PROOF")
find -P android/app/src/main/java -type f -name '*.java' -print0 |
  LC_ALL=C sort -z | xargs -0 sha256sum > "$TASK_JAVA_PROOF/java-before.sha256"
./hako bash -lc 'cd android && ./gradlew :app:compileReleaseJavaWithJavac --offline --rerun-tasks --console=plain' |
  tee "$TASK_JAVA_PROOF/gradle.log"
find -P android/app/src/main/java -type f -name '*.java' -print0 |
  LC_ALL=C sort -z | xargs -0 sha256sum > "$TASK_JAVA_PROOF/java-after.sha256"
cmp "$TASK_JAVA_PROOF/java-before.sha256" "$TASK_JAVA_PROOF/java-after.sha256"
bash android/ssh/host-product-jump-test.sh --output "$TASK_PRODUCT_OUTPUT"
```

脚本核对项目固定 OpenSSH 压缩包、四个补丁、NDK `jni.h`、BouncyCastle 和镜像摘要，复制 release classes、fixture 与 native 源码，验证复制前后及完整测试前后的源码和 class 摘要。历史构建缓存里的压缩包被清理时，可用 `CTOS_OPENSSH_ARCHIVE` 指定另一份同版本源码包；脚本仍以固定 SHA-256 校验，不会自动下载或信任其他版本。本项目其余主机 JNI／route／auth-chain 夹具也支持同一变量。输出目录保留清单、构建日志、JNI 检查日志和库摘要，便于审阅；生产 native 构建不启用测试 hooks。所有容器为 task-owned，使用 `--network none`、不映射宿主端口并自动删除；凭据只在容器临时文件及 stdin 内，不出现在命令参数。确认不再需要证据后，仅删除本次自己创建的精确目录：`rm -rf -- "$TASK_JAVA_PROOF" "$TASK_PRODUCT_OUTPUT"`。

脚本默认验证受限单跳已开放；`--expect-jump-closed` 仅用于源码刻意关闭门禁时的拒绝对照。

## Java 终端的跳板链调度

实际 `SshTerminalSession.NativePort` 现在从目标 session 的精确 `JumpChildOwner` 关系冻结整条会话链；各对象必须仍属于同一线程、精确共享视图和已领用的父子关系。终端每轮依次推进入口、各级 relay 和下游 session；每次 Java／JNI 入口独立结束后才进入下一调用。目标 shell 报告 RUNNING 后，从内向外保留每个祖先，再重新查询目标并完成原有确认，才能向 service 发布 running。没有跳板时继续使用同一套直连处理。

取消时逐个尝试撤销整链；关闭按目标到入口逆序进行，某步未确认时保留快照和后续所有者以便原线程重试，全部会话清理后才关闭 factory。已保留的跳板通道关闭后，Java 不再 step 该祖先，仍让子 relay 和目标排空已接收数据。若目标在首次确认 RUNNING 前就已关闭且带有输出，终端有限失败并清理，不发布未确认字节；这也修复了直连可能无限调度的同一边界。

2026-11-23 历史更新：当时产品 factory 仅有受门禁保护的链组装草案；受限单跳的后续产品接入及证据见[最新验证](verification.md#20261123-ssh-jump-product-open)。

## 首个增量：有序跳板声明

- 在原生有效配置求值结果上，按固定 OpenSSH 解析语义读取原始 `ProxyJump` 声明，输出最多八个有序条目。每项只含声明的主机／别名、可选用户和可选端口；未指定端口与显式 22 必须区分。
- 复用同一求值结果、owner、原始期限和取消状态；失败时不返回部分列表，关闭后不能再次读取。只读过程不得访问宿主文件、网络、环境变量或执行本地命令。
- 输出只是配置中显式声明的列表，不包含每跳 `Host` 的有效配置、嵌套跳板、密钥选择、主机信任、认证或传输权限。它不是连接计划，不经 JNI 或 Flutter 暴露，也不解除现有 direct/profile 的 `ProxyJump` 拒绝门禁。
- `ProxyJump none` 是禁用；`user@none`、`none:22` 和多跳列表内的 `none` 可以是真实主机。逐段重用上游解析器时，单独的 `none` 前缀用等价 URI 解析，避免误当禁用。Include 的 `%j` 沿固定 readconf 行为处理，最终 `User` 展开则保留真实的 `none` 主机名。
- 后续还需要受约束的逐跳通道、认证、信任及可操作的产品流程。只有连接和回收边界完成并验证后，才能开放产品入口。

## 原生逐跳有效配置

固定的 OpenSSH 10.6p1 在生成下一跳调用时，只有声明中明确指定用户／端口才传递 `-l`／`-p`；存在前缀时，以 `-J 前缀` 传入下一跳。这意味着显式前缀会覆盖该跳自己 Host 配置里的 ProxyJump；没有前缀时，该跳的 ProxyJump 仍可能继续展开。不能单纯逐项读取静态列表并忽略每跳配置，也不能无条件沿用目标的用户或端口。

子求值器借用同一受保护来源，沿原 owner、期限和根取消状态工作，所有跳累计原 opens／read bytes／lines 预算，失败或重试不重置。目标、每跳的 `Host` 和嵌套 `ProxyJump` 逐级求值；显式前缀覆盖当前跳的配置，无前缀时保留其配置。显式用户／端口只覆盖对应跳，目标用户名、回退用户名和远端命令不自动继承。

每跳还模拟固定客户端的 `-W` 配置上下文：下一目标的有效主机和端口、无 PTY／无 shell，以及由固定解析器产生的转发清理等默认值；这些只是配置值，不创建转发通道。链构建全部成功后按实际经过顺序提供摘要及内部只读 Options。中途失败不提供部分链；若清理失败，调用方持有仅供重试清理的对象，来源不能先关闭。[原生合同](../.trellis/spec/backend/ssh-proxyjump-route.md)。

最多八跳；相同祖先的别名、显式用户和显式端口组合重复时拒绝，相同别名使用不同显式用户或端口不会仅因此拒绝。此检查是保守的语法限制，不能证明不同别名或最终网络端点没有循环。多跳前缀中单独的 `none` 用等价 URI 保留为主机；这有意避免固定 OpenSSH 子调用的字面 `-J none` 将该前缀当禁用，从而静默漏掉已声明的跳板。

## 原生跳板通道

原生客户端在跳板自身完成主机证明和实际认证成功后，可向同一连接中紧邻的下一跳角色发起一个 `direct-tcpip` 通道。目标主机和端口取自该角色；角色必须共享原连接、目标绑定和原期限。数据读写、窗口、EOF 和失败沿现有加密包泵受限处理，取消、来源撤销或下一角色关闭会阻止继续发布。跳板角色不能打开终端 shell，最终目标的 `CLIENT` 角色仍按既有成功门禁打开 shell。[原生合同](../.trellis/spec/backend/ssh-proxyjump-channel.md)。

角色、回调和 KEX 表的容量由八个角色调整为九个，以容纳最多八个跳板和一个最终目标；第十个角色仍拒绝。角色主机的既有 ASCII／253 字节限制比配置求值器的原始输入范围窄，接入产品前须对无法表示的目标明确拒绝或完成一致性适配，不能静默更换目标。

该通道在显式晋升前保持原认证和秘密库视图的期限。原生代码有一个受跳板通道约束的本地流字节中继：每个方向最多保留 32 KiB，处理窗口／套接字背压、EOF、远端关闭和取消；父会话必须在中继关闭后才能销毁。双层合成测试使用该中继，在目标独立 KEX、主机证明、密码认证和 PTY shell 之外，由测试代码模拟远端跳板到目标服务的转送。[中继合同](../.trellis/spec/backend/ssh-proxyjump-relay.md)。

中继本身不创建或授权子会话。已进入子端 socket 的字节无法由父端撤回，故需要单独对子连接信任、认证和 I/O 复查上游角色。不能把合成夹具证明当作产品链路或完整取消回收。

## 原生子会话前置能力

内部构造器从已认证跳板和它冻结的下一角色创建私有 socketpair、受管中继及子客户端。子端使用独立的主机证明和凭据，其 KEX、回调、认证结果和终端 I/O 递归复查祖先；正常 EOF 允许已接收的字节排空，取消或上游证明失效阻断继续发布。普通 native open 只接受 hop 0，非零 hop 须经此构造器。具体 API、失败时 cleanup-only owner 和线程／描述符重试合同见[原生规范](../.trellis/spec/backend/ssh-proxyjump-child.md)。

每个客户端独占同角色 callback，子端不能重复使用任何祖先的 view borrow。原生客户端和实时配置求值器现在共用借用登记，跨无关所有者也不能同时占用同一个 borrow；构造失败时归还未公开的 claim，正式关闭失败时保留所有者重试。该约定仍要求借用指针按线性所有权使用，不防止释放后地址重用或外部过早销毁 caller-owned view。[借用合同](../.trellis/spec/backend/ssh-view-borrow-ownership.md)。

native 回调前后的检查不能撤销回调内部已发生的信任 CAS 或秘密读取，Java 的跨跳 effect guard、延迟输出发布、后台生命周期及产品整链所有者仍待完成。产品入口继续拒绝 ProxyJump，不静默回退直连。

### JNI 父子会话内部接入（产品编排未实现）

Java 先创建 `JumpChildOwner`，再通过 JNI 为精确父会话预留子 owner；预留持有父 session，只有其已认证跳板通道处于 OPEN 时才允许。实际打开复用父会话精确 view token 和原期限，返回成功或失败前将已产生的 child、relay 或待释放 borrow 保存在原 owner。失败后按状态辨别无 child、仅 child、child 加 relay 或已完全建立；只允许最后一种经后置检查后返回可使用会话。每个未确认的清理阶段可由原线程重试，先关 child、再关／销毁 relay、最后销毁 child 并解除父 pin；`close(fd)` 报错后不能再次关闭可能已经复用的数字描述符。[合同](../.trellis/spec/backend/ssh-proxyjump-child.md#java--jni-child-session-ownership)。

Java 提供跳板 begin／状态／relay pump／从目标向入口晋升。目标 shell 可以先报告 RUNNING，但它的 shell confirmation 及 Java 输出活性检查须等待全部祖先晋升；链 owner 不能据目标 RUNNING 就发布 connected。祖先活性检查仍是顺序时点检查，不与最终输出准入或秘密库写入原子排序。当前代码没有产品连接器调用方，真实产品链、信任刷新及后台 owner 仍未实现。[分层验证](verification.md#20261120-ssh-jump-jni-owner)。

## 原生已建立链的传输晋升

原生内部可在目标真正建立通道后，从最靠近目标的跳板向入口逐跳显式晋升。每一跳要求原期限内自己的认证、视图和上游证明有效，已连接的精确子端及其下游已认证并建立，且相关通道当时未 EOF／关闭。晋升保留原角色、callback、KEX 和会话绑定，释放该跳认证 view borrow 和选择信息；视图活性与借用释放在受管句柄的同一把撤销锁内裁决，角色期限在发布前再次检查。后续使用同时复查上游和精确下游，不再凭原始认证期限续用认证、信任或凭据能力。普通 EOF／关闭与撤销分开，已接收数据仍可按证明排空。重复认证请求会被拒绝，不会仅因探测而终止有效的已建立传输。[原生合同](../.trellis/spec/backend/ssh-proxyjump-retained-transport.md)。

这是原生前置步骤，不是后台功能：原有直连 Java 会话晋升时关闭其 view，新的共享会话重载及 JNI 跳板晋升尚未接入产品链 owner；原 ARM64 晋升夹具使用各自的 owned fixture view。真实加密库的基础 CAS 夹具确认，第一笔信任写入会推进 vault epoch、使之前签发的操作失效；重新签发操作也不能用旧内容摘要覆盖第一笔写入。这使用测试兼容入口和内存存储，尚未实测产品多跳共享 view；因此后续跳板如何安全获得当前信任内容、操作和原连接证明仍需设计，不能通过忽略 CAS 或自行变更基线解决。跨跳效果与取消排序、产品清理编排和实际服务生命周期仍需实现，产品门禁保持。[验证和限制](verification.md#20261120-ssh-jump-retain)。

新增主机 JNI 合成场景让三个原生客户端通过三个独立 borrow 使用同一个真实受管视图句柄。目标 shell 经生产 JNI 晋升后，测试按目标到入口的顺序晋升跳板，再撤销句柄，已建立链仍能双向传输；提前撤销则阻断未晋升跳板和待发布输出。凭据回调内的顺序撤销会令祖先检查失败，测试返回的 Java 字节数组被擦除；每次清理后还验证可创建新的受管句柄。测试使用同一合成主机名的三把不同密钥，Java 接收者和信任结果为测试实现，child 由 C 手工登记 session 表；这不是产品 Java 共享租约、真实信任写入或并发效果保护。[验证](verification.md#20261120-ssh-shared-view-native)。

### Java 共享租约的内部接入（产品编排未实现）

`SshSharedPublicView` 持有原 `SshPublicFileLease`，为绑定相同原对象的 callback 分配独立会话 binding；生产 `SshNativeSession.openManaged` 的新重载只接受真实受管 lease。目标 shell 晋升或该会话 native close 确认后，仅退役对应 binding；链 owner 封口且所有 binding 退役后才能普通关闭 lease。取消可从其他线程立即撤销原 lease，正常关闭和失败清理由创建 owner 的线程执行并可重试。native 句柄和 socket 所有权均已成功移交后，随后的检查失败若清理也失败，会保留 socket 的原 session owner；未取得句柄的发行可撤销并重试。[合同](../.trellis/spec/backend/ssh-view-borrow-ownership.md#java-shared-view-owner)、[验证及限制](verification.md#20261120-ssh-shared-java-view)。

这些内部入口目前没有产品调用方；JNI 已支持跳板晋升及受管子连接，但产品链编排仍未接入。owner 跟踪的是经该入口登记的 Java session binding，不能凭此推断其他 native 消费者已经释放；配置求值器等仍由各自 owner 清理。未来链集成必须确保正数 native handle 发布后的 socket 与 binding 领用标记不会出现未保留清理 owner 的异常路径。实际逐跳信任刷新、跨跳效果保护和产品后台仍待完成。

- 链编排须使用秘密库实际登记的同一个 `SshPublicFileLease`；各跳 `SshRoleContext`、信任来源和撤销锁继续绑定这个精确对象。每跳 native 借用和 session 清理所有权各自独立，不能把临时包装对象当成等价 role view，也不能用引用计数冒充 native claim 已释放。
- 目标 `beginShell` 成功只证明该目标 native 借用已退役。跳板须完成真实 JNI 晋升或确认关闭后才能退役对应 binding；中途失败按目标、对应 relay、父跳的逆序清理，任何未确认的清理保留原 owner 以重试。`VaultSession.lock` 或原请求取消仍须立即撤销原 lease，不能等待引用计数归零。
- 一笔信任写入后原 view 的 known_hosts 仍是旧快照，vault epoch 也会变化。新跳不能把旧 view 或旧操作解释成当前信任来源；安全刷新方案尚未实现，届时须绑定原连接、每跳主机证明、当前库版本和精确 CAS，处理并发撤销及可能已提交但未确认的回执。没有这些条件时继续拒绝，不静默覆盖或绕过信任检查。
- 单独持有共享 lease 不能证明任一祖先仍可授权 secret 或 trust 效果。最终复制／写入仍需与实际链撤销建立共同顺序；不得在 role／view 锁内进入 JNI、等待或执行磁盘操作。完整实现须分别测试真实 Java owner、受管 native 句柄、JNI、真实加密库效果和设备／服务器。

作为跨跳效果保护的前置修正，现有直连的 NEW／CHANGED 主机信任提交分别记录“存储可能已写入”和“当前 native KEX 已确认”；确认与取消、角色和视图撤销按各自的既有所有权顺序裁决。屏锁仍立即撤销，但可能进行信任写入的同步连接工厂退出前不会提前发布最终连接结果；若写入可能已发生而确认未完成，锁定库并返回 `VAULT_COMMIT_UNCERTAIN`。这只覆盖当前单会话回执，不提供祖先链证明。[信任回执合同](../.trellis/spec/backend/ssh-trust-commit-ack.md)。

## 跨跳回调效果与发布：当前研究

2026-11-20 核对当前源码：Java 回调没有持有可验证的祖先链能力；普通 session JNI 在 native step 期间持有非递归会话表锁，从其同步 Java 回调再次进入该表会死锁。当前 child callback 内直接复用常规的 child 活性检查，也会被同一回调的重入门禁拒绝。原生前后检查不能撤销回调内部已发生的写入或秘密复制。

真实受管 trust CAS 在最终 `beforeCommit` 后仍会执行文件提交；若取消在效果开始后发生而 native 尚未确认，必须保留“不确定、锁库”的回执。选中凭据、临时密码及密钥的最终受保护复制须在原有 callback／publication／session／operation／role／view 锁序下，与实际祖先撤销建立共同顺序；不能用任意布尔值或静态 role 列表冒充原生链证明，也不能在 role／view 锁内调用 JNI、执行磁盘 I/O 或等待。当前同一秘密库只允许一个活跃公有视图租约，链中各跳不能各自开启新租约，最终目标晋升也不能提前撤销祖先仍需的租约。

原生 child 实际分派回调内可顺序检查祖先证明：真实客户端在原 owner 线程登记，checker 绑定独占 callback，直接调用包装器、固定密钥的手动验证及嵌套／错误身份不能借用此作用域。私有 JNI 入口额外要求调用者是该 callback 注册的精确 Java 接收者，并复用原线程、环境和回调作用域检查，不重入 session 表。每个祖先仍是顺序采样，不是整链原子快照；允许已接收数据排空的 EOF／CLOSED 状态下成功也不表示通道仍可写。该 JNI 原语尚无产品使用方，不提供持续授权或效果原子性。[合同](../.trellis/spec/backend/ssh-proxyjump-callback-proof.md)。

主机 JVM 合成测试现将真实 child、原生祖先链和 JNI callback／session 锁放入同一进程，覆盖一跳及两跳的 host、KEX 和选中凭据回调，以及直接父节点／更深祖先在回调内取消。测试使用独立 Java receiver 和信任响应、由测试代码将 child 登记到 session 表，并以 C 驱动祖先；它不证明产品 Java 连接编排、真实库写入或并发撤销的效果顺序。[验证](verification.md#20261120-ssh-callback-jni-multihop)。

现有 Java 终端按 owner／代次把输出 data 和 replay 快照准入与取消排序。取消先于准入时丢弃新 data，锁屏状态事件尚在队列中时，新订阅也不会收到旧 replay；实际状态仍保持原样，直到原状态事件执行。若输出先被准入，其 listener 仍可能在取消返回后物理完成；现有 listener 同步持 state 锁的行为、独立 role/view/祖先失效均未由此解决。[输出合同](../.trellis/spec/backend/ssh-terminal-output-cancellation.md)。

具体源码依据、设计推断及待验证矩阵见[任务研究](../.trellis/tasks/10-08-ssh-terminal/research/proxyjump-java-effects-20261120.md)；实际分层结果见[验证](verification.md#20261120-ssh-callback-output)。这些均不开放产品连接。

## 验证

使用真实固定 OpenSSH 解析器覆盖空声明、`none`、单跳、多跳、用户名／端口／IPv6 与 URI、显式与默认值、八跳边界、关闭／取消／过期／撤销／错误 owner 和输出清零。复用副作用观察器确认读取不产生文件、网络、环境或进程行为；direct/profile 对跳板的拒绝回归通过。实际执行结果和 Android、设备边界见[验证记录](verification.md#20261118-ssh-proxyjump-declaration)。

逐跳配置、`-W` 上下文、生命周期、累计资源限制和配置链清理的实际结果见[配置验证](verification.md#20261118-ssh-proxyjump-route)。受限通道和双层合成 SSH 的结果见[通道验证](verification.md#20261118-ssh-proxyjump-channel)及[中继验证](verification.md#20261118-ssh-proxyjump-relay)；子客户端、多跳祖先失效、排队字节和清理测试见[子连接验证](verification.md#20261118-ssh-proxyjump-child)。借用与信任回执结果见[验证](verification.md#20261118-ssh-borrow-trust)。本机 ARM64 原生客户端已通过[实际 OpenSSH 单跳](verification.md#20261120-ssh-jump-docker-interop)，产品 Java／JNI 连接、设备和真实用户跳板服务器仍未验证。

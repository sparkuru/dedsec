# BASE-09 底座与 SSH 集成验收边界调查

2026-10-10，只读调查。本文提供主会话设计判断的证据与建议，不修改批准契约，不宣布任何 BASE 验收通过，不构成 SSH 业务开工或设备操作授权。

## 结论

当前文档同时要求“底座独立验收，不依赖 SSH 客户端”和“底座完整验收后才接入 SSH”，但把必须由生产 SSH 语义及真实客户端验证的恢复行为纳入底座完整验收，形成循环。容器、整图事务、语义校验、真实 native 恢复应保留完整目标，分别记录合同实现证据和联合集成证据。

生产混合包拒绝是缺少语义提供者时正确的失败关闭行为。它阻止声称 BASE-09 完整产品能力已验收，但不应阻止继续完成公共事务/接口及不依赖客户端的平台验证。不能用空 validator、简化 config parser 或测试夹具打开生产导入。

## 循环的实际出处

| 来源 | 现有约束 | 产生的依赖 |
| --- | --- | --- |
| `10-08-app-authentication/prd.md:46,56` | 不依赖 SSH/TOTP 页面，可两个受限测试 adapter 独立验收；底座验收后接入业务 | SSH 等底座 |
| 同 PRD `:30,34,36,42` | 混合库跨安装恢复；已建立 SSH 锁库不断开；另一设备恢复 Include/密钥/信任；两个真实业务导航一致 | 底座完整 AC 又需要业务 |
| `10-08-ssh-terminal-client/prd.md:22,26`、`implement.md:12` | 集成验证 BASE-01..15，但底座完整验收后才做 SSH 集成 | 不允许完成上一行所需集成 |
| `10-08-app-authentication/check.md:69` | 后续生产 SSH adapter 完整语义/信任校验及 bundle mutation 后，才验证混合包恢复；这些关卡完成前不启动完整业务 | 将循环显式固化；连独立 TOTP 也受 SSH 完整关卡阻塞 |
| `design/app-authentication.md:141` | 普通文件图导入/编辑共同事务随客户端扩展 | 公共写入合同也被推迟到消费者接入 |

这不是仅由设备授权不足造成的阻塞：即使已获设备授权，按照以上顺序也无法完成尚不存在的生产语义及客户端行为。Keystore/AtomicFile/公共安全窗口等则是独立平台关卡，不需要等完整 SSH。

## 原批准责任与当前实现

BASE-09 的责任拆分已存在于底座 PRD `:18`、design `:13`、公共领域 `design/app-authentication.md:43,53`：公共库持有类型化对象、逻辑引用和同代次加密事务；SSH adapter 验证真实配置引用/身份/信任，并适配 native 文件语义。两个业务不得重复 KDF/存储/解锁。

| 合同 | 当前实际代码 | 可得结论/缺口 |
| --- | --- | --- |
| 共同容器/结构性闭包 | `VaultManifest.java:173-219` 限制类型、ID/路径、大小、引用存在性、环/深度和私钥口令关联；`AppSecrets.java:190-238` 完整来源认证、目标验证、不可变预览、整库换代提交 | 有真实容器/整图备份事务；manifest 中声明的 references 并不证明 config 实际 Include/IdentityFile 的引用完整或密钥 identity 真实 |
| 公共受限写入 | `SshVaultAdapter.java:65-98` 提供凭据 save/delete 与独立 exact trust proposal；`AppSecrets.java:391-409` 共用加密/效果守卫/提交 | 没有 config/Include/private-key/public-key/certificate bundle add/replace/delete 的统一事务入口；`VaultOperation.java:8-9` 也没有对应 purpose。整库备份覆盖不能代替保存/编辑一个 SSH 图并保留无关 TOTP/SSH 对象 |
| 生产语义扩展口 | `AppSecrets.java:38,92-99` 的 validator 只返回 void；main 源码没有 attach 调用 | 挂接点存在，生产实现未接入。当前任何文件或非 TOTP entry 都触发此 gate；不仅有 config 的包，SSH-only 保存候选也会被挡在 `commitManifest:393` |
| 信任恢复预览 | `AppSecretsBridge.java:381-391` 固定 trustRecords=0、trustSources=[]；`AppSecrets.java:227-229` 仅 checks confirmTrust Boolean | 当前失败关闭使该占位不会流入真实 mixed 恢复。只挂 validator 不能安全打开功能：还缺真实来源/目标/冲突/回滚投影和绑定该不可变预览的确认合同 |
| 连接后的信任写回 | `SshTrustProposal.java:19-37` 绑定 target/connection/host/port/fingerprint/base/new digest；`SshVaultAdapter.java:84-97` 要 exact approval；`AppSecrets.java:369-385` 内容 CAS | 已有公共授权/事务机制；真实 known_hosts 新旧内容与所批准指纹的对应证明仍由 SSH 语义及信任 gate 实现，不能把此 CAS 当成恢复预览已实现 |
| native 临时读取/恢复 | `AppSecrets.java:456-507` 公共文件 loan 排除私钥，私钥另受 purpose/identity/target 约束；`research/ssh-memory-results.md:89-98,112` 给出生产要求 | 文件 loan 是同步 Java 边界，不是已实现 Android IPC/VFS。Linux 六组探针是文件形状研究，不证明 Bionic/每跳授权/实际登录/锁库后 transport/native 擦除或写回事务 |

`VaultTest.java:70-76` 明确使用空测试 validator；`:329-344` 的“independent installation”是两个 MemoryStore/core 实例的同格式字节恢复；`:347-350` 另验证缺 validator 拒绝生产包。可以据此证明共同核心算法/事务和 fail-closed，不可写成生产 SSH 语义、另一设备或实际可连接恢复通过。

另有接口上限漂移：SSH 父 `design.md:207` 是 256 files/depth16/32MiB，公共领域 `design/app-authentication.md:35` 与容器是 128 files/depth8/8MiB files、12MiB plaintext。应由主会话统一可存图边界或明确输入集合与最终完整闭包之间的限制，不能截取配置/引用继续连接。这项不必等客户端完成才能处理。

## 最小建议：完成公共合同，联合 AC 保持待验

1. **底座当前可独立补齐**：SSH namespace 的有界 bundle add/replace/delete 原子入口，以不可变候选图、base snapshot/对象身份、批准操作范围和 request/epoch/deadline 绑定；保留无关 TOTP/SSH 数据；复用现有 `commitManifest`/Store/effect hook/materialBinding。解析器不归此通用事务入口，语义未提供时仍拒绝生产候选。
2. **底座当前可独立补齐**：将 validator 的“合法/异常”挂接合同扩展为可供恢复流程持有的受限、不可变语义/信任审阅结果；核心绑定 source digest、target digest、review proposal 与一次批准。测试提供者可以产生严格已知的合成审阅数据，验证缺失审阅/拒绝/旧批准/目标换代/取消不提交。此证据只证明编排，不证明生产 known_hosts 解析。
3. **底座当前可独立验证**：共同容器/图事务/换口令/包装保持、两消费者权限、取消临界/不确定结果，以及经授权后的实际 AtomicFile/Keystore/公共 UI/SAF 平台检查。无须完整 SSH 终端。BASE-10 严格 SAF 输出保证的产品关卡也独立存在，不应借本次拆分隐去。
4. **SSH 所有、可与完整终端分阶段实现**：固定 OpenSSH 真实语义的 headless 校验/求值、路径/引用/密钥身份、禁止本地命令及绕过 host key 的检查；真实 known_hosts 来源/目标冲突和回滚预览；把结果接入上述共同事务。它们不必等终端 UI/服务全部完成，但需要主会话明确归属与授权，当前调查不会启动。
5. **客户接入后的联合验收**：真实 Android native VFS/IPC、每跳私钥/密码释放与 host gate、信任写回、另一安装/设备完整包恢复后的引用/身份/连接行为、旧 callback/native view 失效、已建立 transport 锁库不中断、双方真实设置/导航。这些 AC 保持原要求且 open，在消费者具备后补证据。

最小文档调整应由主会话决定：把“底座完整 BASE-01..15 全部产品验收后才接入”拆为“公共合同实现及适用独立关卡审阅后，另行授权消费者接入；业务依赖的联合 AC 在接入后最终验收”。这不把 contract-ready 自动改名为 accept-passed，也不自动消除平台/SAF 产品关卡或授予业务开工。

## 对生产混合包拒绝的判断

- 若问题是“能否宣称当前 BASE-09 完整产品验收通过”：不能，生产恢复缺失是实质未完事项。
- 若问题是“是否必须停下当前公共底座继续实现”：不必，统一 bundle 写入、审阅结果绑定及平台验证都有独立工作可做。
- 若问题是“是否必须为了底座验收马上放开生产 mixed 包”：不能。缺实际语义和信任预览时必须拒绝，保持此安全 gate 是底座可证明的正确行为。

本轮只读源码/批准文档，未运行新测试、探针或设备，未修改生产代码及公共设计/规范。

## 2026-10-10 文件图事务追加复核

上文记录调查时的缺口；主会话随后授权宿主补齐公共 bundle 事务。新增 `SshBundleProposal` 持有有界深拷贝变更，按 path 规范排序的完整 proposal binding；replace/delete 对旧 type/ID/path/identity/references/content digest 做 CAS，summary 不提供私钥字节。`SshVaultAdapter` 增加默认拒绝的 exact bundle approval，入场、效果和 `beforeCommit` 均复核；`VaultOperation.Purpose.SSH_BUNDLE_WRITE` 与读取/凭据用途隔离。

`AppSecrets.beginSshBundle/commitSshBundle` 从当前快照应用全部变更，完整 manifest 与业务 validator 校验后复用现有 `commitManifest`/Store，而非增加 KDF/存储；即使删掉最后 SSH 文件仍显式要求 validator。结构性断引用/环/深度/总库大小、关联私钥口令拒绝，不做隐式 cascade；无关 TOTP/SSH 条目及文件保留，普通内容编辑保留 material generation/wrapper 并使旧快照 operation 失效。关闭/取消/期限和最终 effect hook、提交声明后的不确定状态沿用公共合同。

独立 reviewer 已检查上述五个新/修改文件及 16 项 `SshBundleTest` 的真实 core 路径，未发现必须修复的新缺陷。宿主增加 replace old/next 非空防护及三项现有测试断言后的冻结检查已退出 0，25 秒；reviewer 读取 `/tmp/ctos-host-bundle-frozen-check.log` 和 XML，确认 61 项 JVM（Vault35/Session7/Export3/Bundle16）全通过，0 failure/error/skipped，timestamp 2026-10-10T02:48:17–20Z，lint 0 errors/5 既有 warnings，instrumentation 编译通过。`git diff --check` 通过。这一追加只覆盖公共文件图写入合同；测试明确为 fixture validator，不能代表生产 OpenSSH 语义。

因此“普通 bundle 公共写入入口缺失”已由当前代码补齐，不再推迟给客户端。真实恢复信任投影/批准绑定、生产语义、Android native 跨设备/每跳恢复及平台关卡仍未完成，生产 mixed 包 gate 和联合 AC 维持原状态。

## 2026-10-10 恢复审阅合同追加复核（联合检查前）

随后宿主补齐公共 `SshRestoreReviewer`/`ExactRestoreApproval` 和 `SshRestoreReview` 元数据投影合同。来源与目标均先真实认证；任一侧有 SSH 内容（包括 TOTP 来源覆盖有 SSH 的目标）都要求 verifier/reviewer。核心核对两侧全部 SSH file/credential 的 type、ID、逻辑路径、identity 和内容绑定，不允许遗漏/重复；provider loan 在回调结束后立即擦除，core 从认证明文重新构建 canonical coverage。

预览绑定 source/target ciphertext、provider 注册版本/revision、实际展示投影、epoch/deadline；mixed 包的 legacy Boolean 不再单独授权，提交必须带 exact review binding，default-deny approval 在入场、效果及最终 hook 重查。同意对象由 core 一次构造，替换/撤销 reviewer 清 preview；取消、源/目标变动、迟到/超时不能复用批准。

重要安全修复：原始 credential 内容摘要只能用于 native 验证，公开摘要会成为弱密码离线猜测 oracle。先核对 raw native coverage，再用每次 preview 新的随机临时 HMAC key 生成不跨预览关联的 credential token；canonical review binding 和 wire map 均绑定实际 opaque 投影，key 不进入 map/journal，原始 key 数组 finally wipe。独立 reviewer 复核 `bind`/`toMap`/Bridge `reviewMap` 路径，无 raw credential digest 返回路径。

17 项 `SshRestoreTest` 明确是 synthetic reviewer 的真实 core 编排测试；interim JVM log `/tmp/ctos-host-restore-jvm-check-2.log` 退出 0/31 秒，reviewer 读取 XML（03:05:14–28Z）：91 项全部 0 failure/error/skipped，其中 Restore17、Vault35、Bundle16、Session7、Writer3、初稿 ExportTransaction13。导出 owner 尚在修复事务故障，本计数不是最终联合冻结结果，也没有重新运行未变 bundle 检查。

恢复“投影及批准绑定公共入口缺失”已由以上代码补齐；完整 file coverage 只证明对象集合未遗漏，并不证明真实 known_hosts 的每行/冲突/回滚判断正确（测试可以用合成或空语义行）。实际生产 OpenSSH semantic reviewer/approval 尚未注册，生产 mixed gate 保持关闭。底座的公共编排可独立查证，真实 diff/身份/Android native 恢复仍在联合 AC，不把接口存在当产品验收通过。

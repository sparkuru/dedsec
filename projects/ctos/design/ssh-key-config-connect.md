# SSH config 与无口令私钥连接

2026-10-26：用户实际需要连接禁用密码认证的服务端，明确使用普通 OpenSSH 私钥、无私钥
口令。问题反馈时已安装版只提供密码入口；底层私钥认证及 config 解析并非产品入口。
本轮沿已有 SSH 父任务优先补齐这条实际路径，保留未提交的密码保存改动，不新建 task。

## 本轮可验收行为

- 在受保护 SSH 页面选择私钥文件，宿主实际解析/证明并展示算法、完整公钥指纹及目标
  受管路径；确认后与公钥/KeyBinding 同次写公共加密库。私钥不回传 Flutter，不落明文磁盘。
- 手动连接可选择已导入私钥，不要求密码；真实主机核验后由现有 selected private-key
  USERAUTH 驱动，成功后使用同一 PTY。以禁用 password 的隔离 SSH 服务验收，不能只测
  公钥解析或密码后备成功。
- 用户可导入/编辑 config，导入必要的 Include，选择由 native catalog 枚举的真实 Host
  别名，并按 native 有效配置实际使用 HostName/User/Port/IdentityFile。保留 Host *、否定、
  首值与 IdentityFile 累加的解析语义；有多个可用身份时由用户明确选择此次身份。
- 不实现第二套 config 解析器。原生实际 Options 尚不能消费的连接选项必须定位并阻止
  所选连接，不能忽略后直连或声称完整 OpenSSH config 已交付。ProxyJump/转发/后台服务、
  证书/agent/硬件身份、加密私钥和复杂恢复仍在父规划，各自不能由本轮标完成。

用户提到的真实端点仅用于说明需求；本轮代码/本地隔离测试不等于授权读取个人 ~/.ssh、
私钥、访问真实账号或安装开发板。设备与真实端点验收遵守当前明确授权。

## 唯一模型与来源

仍使用唯一 VaultManifest、AppSecretsOwner 与固定 OpenSSH 引擎。扩展规范受管 CONFIG、
PRIVATE_KEY/PUBLIC_KEY、KeyBinding/KEY Account 的支持，保留既有 TOTP、密码 Account 与
known_hosts。Account 和 KeyBinding 声明不能代替实际密钥数学关联或最终请求证明。

新增原生 helper 从真实私钥导出原始公开 blob，并使用已有自签/验签约束证明一致性；
摘要或测试 peer 不是公钥来源。只公开算法、公钥/指纹，所有私密副本 finally 擦除。
本轮明确接受未加密 OpenSSH 私钥；需要口令/不支持材料返回明确错误，不能静默当空口令。

扩展现有 SshBundleProposal 的精确不可变 metadata changes，使文件、KeyBinding/KEY Account
可同次 source/candidate 校验、原审批 binding/CAS 和原子加密提交。保留其他元数据，不能
先存文件后补关系，或将全库 metadata 清空以通过旧密码子集 validator。

生产 validator 扩展到明确支持的图：实际 CONFIG/Include 类型与直接引用、真实私钥/
公钥配对、唯一 owner、实际账号/目标关系、有效 known_hosts；连接时继续按实际 Request
完成 native plan、身份及信任准入。partial-only inspector 仍只作构件，不能冒充通用语义
validator 或开启整库恢复。密码默认/状态/保存/全部忘记在混合 KEY 数据中也须保留其他业务。

## 导入、路径与生命周期

用新的 SSH SAF 输入 broker 直接有界读取 ContentResolver 流到独占内存，复用既有
系统选择器/不可复用请求码模式；禁止使用会写普通文件的 ToolFiles。按明确逻辑路径
导入，常规 SSH 文件置于 `.ssh/...`，保留 `~/.ssh/...` 和 Include 的 native 虚拟 home
语义。桌面绝对路径/缺失依赖提供位置和补充/编辑方式，不能静默改写。

导入 config/Include 与私钥是显式不同类型，不靠后缀认证材料。来源 known_hosts 不自动
成为信任，现有信任不被导入静默覆盖；没有信任时仍实际首次指纹确认。缺失依赖不能
提交半个有效图；可以先导入所需密钥/Include 后重试根 config。所有替换在确认界面列出。
不持续同步外部文件，不持久 URI grant，不修改外部源。

导入 ticket 在系统选择前捕获原 epoch/revision/deadline/validator/cancel，待恢复前台后
按原剩余预算读取/预览；选择器不延长共享库授权。确认使用原独占候选/审批，不能再次
读取 URI 替换已审阅内容。取消、错误、过期与提交不确定回执沿现有事务规则，页面等待
最终写入回执后才释放保护。私钥只留宿主；config 编辑使用受保护短期控制器并及时清理。

## Native 有效连接计划

在既有 opaque evaluator owner 上增加有界类型化 direct-plan getter，复用真实 pinned
readconf 的 Options；给出 alias/host/user/port、依次求值的受管 IdentityFile、实际
UserKnownHostsFile、认证/PTY/超时及必须阻止的未消费项。每一项都消费或明确拒绝；不能
只读 Summary 的三个字段后丢弃其他声明。所有 expansion/路径拒绝保持 source/line。
未声明默认身份可以列入候选，但只使用用户已导入、经验证的真实匹配材料。

config 不可绕过主机核验/触发本地执行；每次选择绑定真实 root/alias、内容快照、身份、
tuple 与原期限。新连接仍先关闭旧终端；不增加密码后备、自动重连或不受限 ssh CLI。

## 已接通的入口和边界

SSH 连接页的“私钥与配置”打开库管理，分别导入私钥、config/Include，预览后确认。
私钥选择只传公开的 keyId，身份 ID 绑定受管路径与实际公钥；替换密钥会使旧选择失效，
并在同次事务中移除仅依赖该身份的运行时 KEY Account。手动输入目标可直接选择私钥；
配置连接从真实 native Host 列表选择，缺失 User 时补充用户名，多个身份时明确选择。
首次主机指纹仍须确认，StrictHostKeyChecking yes 对未知主机直接拒绝。

`ssh.library` 返回公开 keys（id/label/path/algorithm/fingerprint）和 hosts
（alias/host/port/username/keyIds/binding，可附固定 error）。host 的 binding 是原认证
密文、epoch/revision 与 alias 的来源绑定；`ssh.configConnect` 必须提交并重新核对它。
config 编辑或其他库修改后旧列表不能直接启动连接，须刷新。编辑器只返回 CONFIG 内容，
不提供私钥正文。`ssh.importFile`、`ssh.configPreview` 返回精确 ticketId/binding，
`ssh.importCommit` 只提交已审阅候选，`ssh.importCancel` 释放该候选。

SAF 流有总字节上限，取消和原期限在 provider 调用返回后检查；同步的 query/open/read
可能被第三方 provider 阻塞，不声称这些调用可按墙钟硬抢占。系统选择器离开前台不会
自取消本次选择，但也不会延长授权。当前受管路径支持空格和引号，拒绝不支持的扩展
及桌面外部路径，生成配置按实际 OpenSSH 引号规则引用路径。

Android 隔离夹具已通过实际 content:// 内存来源到 Activity result、导入、JNI、手动/
config 连接及旧 binding 拒绝的 opt-in 回归。正式APK已保留数据安装，见
[当前验证](verification.md#20261026-key-config-device)。选择器由ActivityMonitor提供结果，
不能代替真实DocumentsUI/grant。后续Android16兼容性修复已在指定手机保留数据安装，
正式界面使用用户现有私钥登录实际目标与PTY命令通过，用户人工验收并授权提交；
[最终手机验证](verification.md#20261026-phone-ssh)。真实DocumentsUI仍待专项验证。

## 检查

先分层检查真实公钥导出和 native direct plan（NDK严格编译、ARM合成/独立服务按可用
环境分别记录），再验证公共实际加密候选/取消/混合数据保留、SAF与UI状态/布局，最后
Android lint/构建/签名与独立复核。实际隔离服务必须只允许 publickey，并证明 PTY 命令
输出非回显。新增能力与已有密码保存731 JVM/255 Flutter/正式 APK 证据分别记录，不能
把旧结果当新代码通过。原父完整 AC 保留。

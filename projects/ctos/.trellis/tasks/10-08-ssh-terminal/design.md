# SSH 终端与公共认证集成设计

## 当前直接实施：config / 无口令私钥

用户最新反馈取代继续密码保存设备验收的优先级，以真正的 publickey-only 连接为目标。
当前合同为 [config 与私钥连接](../../../design/ssh-key-config-connect.md)，研究依据见
research/private-key-connection-path.md 与 research/config-key-import-connection.md。
先扩展真实 native 公钥导出/effective plan 和原子 metadata，再接宿主导入/validator/connector
与受保护界面。保留此前修改与完整父范围；这是同一正式模型扩展，不另起格式/客户端。

## 2026-10-26 已选择接续：密码保存

用户选择可选保存 SSH 密码，复用本任务直接实施。沿正式 manifest 的 Account/SSH_PASSWORD
记录保存精确端点及账号，稳定 ID/target 由无歧义 tuple 摘要生成，不从 opaque target
反解端点。多个端点各自保存，整个实际 manifest 验证覆盖账号/密码一一绑定、规范身份、
类型与长度；旧生成 CONFIG/KNOWN_HOSTS 和无关 TOTP 保留，其他未支持对象仍拒绝。

连接表单保留默认当次密码，另提供显式保存、使用已存密码及确认后忘记单条/全部密码。
已存密码不回填 Flutter；表单按精确 tuple 查询公开 saved 状态，编辑目标立即撤销旧选择，
异步回执用 revision 隔离。复用密码继续经 selected credential 的真实主机键/KEX 与最终
typed read；使用前复查 Account 与当前 role/selection。保存和 Account 同次原子事务，
请求在排队前捕获 epoch/deadline/cancel/validator；取消和 uncertain 沿用公共写入语义。
显式保存可在实际连接前完成，失败不宣称回滚已明确保存的密文；界面说明“连接时保存”。
新保存完成后才创建 public view/role，不能放宽任意 epoch 变化以继续旧请求。

固定通道合同：defaults 增加 savedPassword(bool)；passwordStatus(requestId,host,port,username)
返回 saved(bool)；passwordConnect 在原字段上增加 useSavedPassword/savePassword(bool)，
使用已存时 password 为空且 savePassword=false。forgetPassword(requestId,host,port,username,all)
返回 forgotten(bool)。不接受任意 entryId/target 或从 Flutter 传入账号绑定。

UI 决策遵循现有主题与 Form，使用可读标签的原生复选控件、忙状态和破坏性确认。
已按 UI/UX Pro Max Flutter 查询核对 Form/Semantics/辅助技术建议；覆盖窄屏、横屏、大字、
后台/迟到及失败重输。设备安装/真实账号不由本轮选择推定授权。

2026-10-08，planning。用户已选择含 Include / ProxyJump 的兼容范围 B，要求基础及主要功能与 OpenSSH 相似；存储管理选择 A，用户主动导入后使用 App 内部私有副本；首版按建议拒绝配置驱动的本地命令执行，保留受管 ProxyJump；允许用户选择长期加密保存密码/私钥口令。生物/系统锁屏凭证验证已选择公共组件，其他内部服务可复用；config 基础 TCP 转发及默认 loopback、显式对外监听连接前确认已确定；用户已确认 config 不得绕过主机指纹核验；本设计因剩余确定范围整理完成，待共同最终审阅。依赖候选未安装或实测。需求权威见 [prd.md](prd.md)，项目入口见 [SSH 终端规划](../../../design/ssh-terminal.md)。

## 现有边界及复用

| 边界 | 证据 | 对设计的影响 |
| --- | --- | --- |
| Flutter 终端状态 | `lib/main.dart:48`、`:99`、`:277`、`:331` | xterm 最多 3000 行、串行写入、会话 generation 可复用；SSH 状态应来自可信控制事件。当前依靠输出文字判断退出，不能直接用于远端流。 |
| 终端输入 | `lib/terminal_interaction.dart:9`、`:195`、`:285` | 共用 IME 差量编辑；认证秘密须用独立输入通道。SSH 输入和控制键交远端；不沿用本机历史截获、提示符识别及行回填，不加入远端程序专属操作逻辑。 |
| 本机 PTY | `android/app/src/main/java/im/majo/ctos/MainActivity.java:257`、`android/app/src/main/cpp/pty.c:18` | JNI 当前仅启动本机 Shell。采用 native 客户端需受管 argv 启动和进程退出/停止管理；禁止向 Shell 输入框拼接 ssh 命令。 |
| APK 运行包 | `android/app/src/main/java/im/majo/ctos/PortablePackages.java:29`、`:65`、`:205` | 可增加 APK 内原生工具；不可用下载到数据目录的 Linux/glibc 二进制代替 Android/Bionic 客户端，也不可全局改 HOME。 |
| 生命周期 | `android/app/src/main/java/im/majo/ctos/MainActivity.java:298`、`:320`、`:330` | 当前停止通过 generation 驱动读线程退出并最终关闭 FD，onStop 没有关闭 PTY。SSH 必须显式拥有 socket/子进程、取消认证和幂等关闭；用户已确认后台保留与手动 Stop，SSH 资源由独立前台服务持有。 |

## 客户端候选

| 路线 | 与现有环境的关系 | 代价和待验证项 | 适用范围 |
| --- | --- | --- | --- |
| APK 内置 OpenSSH native 客户端 | 复用 Portable 原生程序分发及 JNI PTY | Android API 28/Bionic、arm64、16 KiB、OpenSSL/其他依赖、许可证、私有路径；客户端配置也可能执行命令，必须先定义产品允许范围。需要可靠认证 UI/askpass 及资源回收。 | 配置兼容优先；用户需要 Include/ProxyJump 等进阶行为时优先评估。 |
| Dart SSH 库，例如 dartssh2 | 与 Flutter/xterm 直接连接，仍完全随 APK 提供 | 需自己实现 config、私有密钥存储/SAF 和主机信任；验证当前 Dart 3.9、密码/密钥/窗口变化及依赖来源。库支持 SSH 不代表具备 OpenSSH config 语义。 | 常用配置子集的优先候选，减少 JNI/Python IPC。 |
| Python + Paramiko | 复用内置 CPython 和 cryptography | 还需 bcrypt/PyNaCl 等依赖的 Android cp313 wheel 兼容研究；配置解析不会自动用于连接。需设计长会话 IPC，不能套用 15 秒工作台任务。 | Python 环境复用优先的候选；不能因已有 cryptography 就宣称可用。 |

根据已确认的 B 方案，技术主方案改为 APK 内置真实 OpenSSH native 客户端，复用 Portable 与 JNI PTY，由 OpenSSH 负责配置求值、认证、连接和跳板语义。Dart/Python 路线仅保留比较记录；若 native 探针不可行，必须回规划说明原因及兼容影响，不能自动降级为自写字段子集。当前只确定研究/设计路线，尚未固定版本、构建或证明 Android 可用。

## 首版 OpenSSH 配置与认证契约

“主要功能相似”按可测试能力收敛；产品明确选择 B 不等于要求所有桌面功能，也不等于授权本轮运行客户端。

| 能力 | 规划范围 | 验证及边界 |
| --- | --- | --- |
| config、Host 规则与生效选项 | 已确认方向，交给 native OpenSSH 求值 | 大小写、注释、引号、首值/累加、通配/否定，与相同版本参考客户端比较；UI 别名枚举只作索引，不能另写一套连接配置求值。 |
| Include | 已确认进入首版 | 多文件、嵌套、通配词法顺序、条件 Include；相对基准沿用用户配置的 SSH 目录语义，不能统一改成当前包含文件目录。用户主动目录导入，运行使用私有副本，不持续同步。 |
| ProxyJump | 已确认进入首版 | 单跳/多跳，每跳配置、认证与信任独立；失败/取消回收整条链，禁止直连回退。 |
| 密码、keyboard-interactive、RSA/Ed25519/ECDSA 私钥及加密私钥 | 常用认证交付契约，具体算法/格式组合逐项由 native 探针验证；可选长期保存已确认 | 与所选 OpenSSH/OpenSSL 构建支持一致；密码/口令由宿主凭据库加密保存，不能添加非 OpenSSH 的 config 密码字段。 |
| known_hosts、HostKeyAlias、UserKnownHostsFile、指纹核验及保活 | 主要连接契约；禁止绕过核验已确认 | 原生维护 OpenSSH 格式 known_hosts，ctOS 不平行维护另一套不一致的信任库；桌面绝对路径需导入诊断；坚持 R5 的未知确认/变化拦截，不允许配置绕过。 |
| Match host/user/originalhost/all 等不执行命令的条件 | 主要 config 条件求值契约 | 枚举和实际连接需使用一致条件语义；具体 Match 条件随固定版本写入矩阵。 |
| SSH 输入/控制键、终端输出与窗口尺寸 | 已确认，用户要求远端处理自身操作 | ↑/↓ 送远端，不截获为本地历史；ctOS 负责输入传输、终端渲染与 PTY 尺寸同步，不设远端程序名称的兼容验收。 |
| RemoteCommand、RequestTTY | 主要 config 能力，逐项对照固定版本 | 求值和连接遵循选项语义，不因界面默认交互而静默丢弃；远端程序逻辑仍由远端处理。 |
| 本机 Shell 的通用 ssh CLI | 当前 UI/config 交付不包含通用 CLI | 当前请求以 UI 主机入口为目标；增加 CLI 需另验认证/暴露确认桥、服务所有权及输入边界，不能直接把受管二进制等同已交付 CLI。 |
| Match exec、自定义 ProxyCommand、LocalCommand、KnownHostsCommand 等本地命令 | 已确认首版拒绝，无手动放行开关 | 定位执行型声明并拦截；不得静默忽略后直连。无执行效果的 none/no 不误报；ProxyJump 的宿主拥有客户端链保留。 |
| LocalForward / RemoteForward / DynamicForward 基础 TCP 转发 | 已确认进入首版，默认 loopback；对外监听连接前确认 | LocalForward、固定 TCP 目标的 RemoteForward、SOCKS4/5 DynamicForward；多条声明、IPv4/IPv6、建立失败及 Stop 全链路关闭。与任意本地命令执行分开；ProxyJump 内部转发不受普通转发产品范围影响。 |
| ssh-agent、ControlMaster/Persist、X11、FIDO/PKCS11、GSSAPI、SFTP/scp 界面 | 桌面/外部依赖扩展，当前不交付 | 不因为原生库可能支持就宣称 ctOS 提供完整产品功能；后台进程及外部依赖边界需另行设计。 |

## 需求收敛与技术验证边界

用户已确认输入归远端、无程序兼容清单、后台手动 Stop、基础转发与暴露确认，以及 config 不能绕过主机核验。下列内容把原始“基础、主要功能与 OpenSSH 相似”落实为具体工程契约，桌面扩展不由原生客户端存在而自动成为新增产品入口；整版仍待最终审阅，未实施。

交互定位已由用户确定：SSH 输入和 ↑/↓ 交远端，ctOS 不追加远端程序逻辑；已撤销特定程序/全屏支持深度的范围与兼容验收建议。认证格式和 config 的具体实现由原生探针验证；无法达到交付契约时说明原因并回到规划，不以选定库不支持为由静默删需求。
- 常用认证/配置：密码、keyboard-interactive、RSA/Ed25519/ECDSA OpenSSH/PEM 常用私钥及加密私钥；HostKeyAlias/UserKnownHostsFile、非执行 Match host/user/originalhost/all、RemoteCommand、RequestTTY 进入兼容矩阵。私有路径与缺失引用诊断保留，配置不能引用受管 SSH 数据区之外的文件；无法映射的桌面绝对路径显式报错，导入时由用户匹配，不静默重写。
- 主机信任：沿用 R5，未知主机确认、变化指纹阻止连接；不提供 StrictHostKeyChecking=no/off 等绕过核验的策略，不静默忽略其配置。ask 为默认，yes 不自动登记未知主机，按相同版本规则诊断。accept-new/no/off 或其他能跳过人工核验/变化拦截的有效选项拒绝并定位，根 config/Include/每跳一致执行；不能用宿主参数覆盖配置来假装兼容。常用证书/算法最终以固定客户端构建和测试矩阵为准，不借“相似”承诺全部桌面认证设备。
- 延后：本机 Shell 暴露通用 ssh CLI、ssh-agent/agent 转发、ControlMaster/Persist、X11、FIDO/PKCS11/GSSAPI、SFTP/scp 界面、独立转发管理 UI、多会话及 SSH server。APK 中受管可执行文件仍须 native 策略，界面限制不能作为 CLI 绕过入口。

当前范围用于日常设备连接与已确认跳板/转发；扩展通用桌面 CLI 需另设计所有权、认证桥和外部依赖。远端程序操作不增加 ctOS 专属模式或兼容清单。

## Android 原生客户端适配研究

- 受管启动使用明确 argv 与 APK 原生程序路径，指定 `-F` 私有 config；资源所有权包括 ProxyJump 派生客户端，不能仅停止最外层 PID。
- `ssh -G` 可用作有效配置参考，但会求值 Match；upstream 的 Match exec 调用本地 Shell。主机列表刷新不能直接对未经约束的用户配置运行 `ssh -G` 并当成纯解析。按已批准 R9，在固定版本解析器/执行边界阻止自定义命令，并在列表与连接前对 Include 闭包做无副作用诊断；不能用几条正则完成此边界。该策略覆盖宿主入口、可提供的 ssh CLI 及每跳客户端，不仅依赖 Flutter 按钮检查。
- 用户目录解析与 getpwuid 返回值有关，单纯给子进程设置 HOME 或 `-F` 不足以证明 Include、`~`、`%d`、默认密钥和 known_hosts 都映射正确。建议集中适配该客户端的私有 home/path，保留 Include 与 IdentityFile 各自的上游相对路径语义，不修改进程外全局 HOME、系统 passwd 或其他 App 文件。
- native ProxyJump 会派生客户端并传递 config；需保证派生使用同一 APK 可执行路径、私有目录与每跳认证通道，不依赖系统 PATH 上有 ssh。
- 未选版本与构建配置前不承诺所有算法/条件项；实施探针对同一 OpenSSH 版本做配置和实际协议对照，补全来源/哈希/许可证。

## 公共认证与父子交付

用户最新明确把 Authenticator 主口令/便携库/设备快捷解锁模型应用到 SSH。统一契约见 [App 秘密库与公共认证](../../../design/app-authentication.md)；公共底座持有 KDF/加密文件/库会话/设备包装，SSH 只提供类型化身份 adapter 与受管 native 通道，不另建认证 UI/加密库。现有宿主尚无此实现。

先交付 [统一秘密库底座](../10-08-app-authentication/design.md)，独立验证格式/会话/两消费者及平台认证，再交付 [SSH 接入](../archive/2026-10/10-08-ssh-terminal-client/design.md)；Authenticator 也依赖此底座但不依赖 SSH。父任务只管理源需求/依赖/集成；公共接口默认 App 内部复用，不导出跨 App 服务，不自动改变既有 Root/HFTP 策略。

## 建议模块与数据流

```text
终端会话选择 → SSH 主机列表 → 有效配置快照
                              ↓
                    主机核验 → 认证 → SSH transport + remote PTY
                              ↓
                      共享终端显示/输入/尺寸/输出选择

配置编辑 / SAF 导入 → App 私有 SSH 配置与密钥仓库
主机核验             → App 私有信任记录
```

- `SshConfigRepository`：保存原始文本及 Include 文件集合，枚举主机目录并调用受约束的 OpenSSH 配置求值获取有效选项/诊断。保存需原子替换，连接使用不可变配置快照，避免连接中改 config 改变目标；不能只将根 config 文本冻结而继续读取变化中的 Include。
- `SshProfile`：包含 alias、host、port、user、选定认证及存活参数，不含密码；主机列表与实际连接共用同一解析结果。
- `TerminalSession`：区分 app / root / ssh；控制器提供 start、write、resize、close、output 和 typed state。本地 PTY 与 SSH adapter 各自拥有资源，不共用 Root 采集或 HFTP adapter。
- 控制事件建议含 sessionId、kind、phase、reason、exitCode；输出仍为字节流，禁止从远端文本识别可信状态。旧会话输出、尺寸回调、认证响应和写入均按 sessionId/generation 丢弃。
- `SshTrustStore`：SSH adapter 管理公共加密库中的 known_hosts 逻辑对象及核验 UI，native 使用受管临时视图执行 HostKeyAlias/端口/哈希主机名语义；每跳分别核验，更新回送 host 检查并加密原子保存。记录随统一加密包导出，导入信任需单独预览审阅，变化指纹仍拦截；不另建明文已信任缓存，不随 Portable 升级删除。
- 密码/私钥口令用独立遮挡输入；默认仅当次使用，用户选择保存时交给宿主凭据库。具体技术路线需验证 native 对秘密的回显和保留；禁止通过 argv、环境变量、TerminalCommandHistory 或日志传递认证秘密。
- `SshCredentialAdapter`（原 SshCredentialVault 业务角色）：只处理目标/账号/私钥身份绑定、显式保存/忘记及匹配的 native 请求，存储和解锁委托公共 AppSecrets；不持有独立加密密钥或库。释放秘密前校验当前连接、库代次及 host key 核验，远端输出不能触发任意凭据读取。

## 可选长期保存的统一秘密库接入（用户已改为公共模型）

用户允许主动选择长期加密保存密码及私钥口令；这不是自动保存全部输入，也不包含动态验证码/一次性挑战的长期保存或云同步。每条认证输入默认不勾选保存，成功认证后原子保存；失败输入不覆盖先前有效记录。用户可选择当次输入，长期保存与系统解锁分别管理。

统一存储建议为主口令 KDF + 认证加密便携库，设备 Keystore 仅包装用户主动开启的公共快捷解锁材料；格式/nonce/AAD/参数限额只在公共底座实现。SSH 条目仍带身份绑定，内存和 native 暴露仍需约束，不承诺私钥不可导出或硬件签名。旧设备-only SSH 凭据密文建议已被取代。

| 边界 | 建议契约 |
| --- | --- |
| 密码绑定 | 绑定实际目标、端口及用户名，与 UI 别名分开；目标变更不能只凭同一 alias 继续使用旧密码。每跳独立匹配，在主机核验之后释放。 |
| 私钥口令绑定 | 绑定导入密钥身份，不只绑定可替换路径；替换密钥后重新确认保存关联，不复用旧记录。 |
| 状态与认证 | locked / unlocking / usable / failed；取消解锁不启动或继续秘密认证，不保存未确认输入；旧会话认证响应按 generation 丢弃。 |
| native IPC | 受管认证请求带 session/请求 ID 和固定身份，由可信控制通道传递；不经 argv/环境/stdout，不暴露可任意查询全部凭据的接口。CLI 如进入首版也须复用 host-owned 认证桥，不直接读取密文库或获得加密密钥。 |
| 删除与恢复 | 忘记单条/SSH 全部条目通过公共 namespace 操作，不删除 TOTP 或公共主口令。快捷密钥失效回退公共主口令路径，密文校验失败不覆盖/不明文降级；删除不撤销服务器授权。 |
| 生命周期 | 每次秘密请求检查公共库会话与受限连接上下文，认证结束释放明文，连接存活不延长库会话；有效前台会话跨功能不重复解锁同一库，后台按公共可配置秒数到期锁定，锁屏/手动/进程重启锁定。后台新解锁需前台，取消/旧代次回调被拒绝。 |
| 迁移与硬件 | SSH 已保存密码/私钥口令随统一主口令加密库迁移；config/Include/私钥/关联公钥或证书/信任已确认一并加密打包，导入校验引用/路径/身份并审阅信任。设备 Keystore 不迁移，不假定 StrongBox；无快捷方式仍有主口令路径。 |

最新统一规则为每次连接使用凭据时验证公共库会话并建立本次匹配操作上下文，各跳独立核验身份/主机。库锁定时用主口令或已启用的系统快捷方式解锁；不再强制保留 SSH-only 每连接系统弹窗规则。有效前台会话共享、后台秒数可配置已确认，仍逐次检查资源/身份，不是免验证任意读取。

API 兼容研究：`setUserAuthenticationParameters` 为 API 30+，API 28/29 需使用兼容认证窗口/API，不能仅复制最新示例。Android 10 及以下的 BiometricPrompt 认证器组合存在限制；需对生物识别与系统锁屏凭证采用合适兼容路径。快捷解锁需满足系统密钥认证策略；无安全锁屏/验证不可用时通过主口令解锁或当次远端重输恢复，不启用免验证设备材料。API 兼容分支集中在公共组件，不在各服务重复实现，具体平台行为经最终审阅和实测固定。

证据：[Android Keystore](https://developer.android.com/privacy-and-security/keystore)、[系统认证 UI](https://developer.android.com/identity/sign-in/biometric-auth)、[KeyGenParameterSpec.Builder](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder)。加密格式、绑定与 native 通道是本项目设计建议；本轮未实现密钥生成/加密/认证 UI，也未实测设备支持。

## 配置契约建议

配置权威副本存为公共秘密库的加密文件对象/稳定逻辑 SSH 数据区，独立于版本化 Portable 资源；解锁后编辑/求值/连接使用受管临时内存访问视图，不能将该逻辑区理解为另一份长期明文目录。不读取 Android 系统 `/etc/ssh` 或其他 App 的 config。路径映射由 SSH 功能负责，不修改本机 Shell HOME。

首版示例（仅设计夹具，不是实际设备地址）：

```sshconfig
Host lab-board
    HostName board.example.invalid
    User operator
    Port 22
    IdentityFile ~/.ssh/keys/lab_ed25519
    IdentitiesOnly yes

Host lab-spare
    HostName spare.example.invalid
    User operator
    Port 2222

Host *
    ConnectTimeout 10
    ServerAliveInterval 30
    ServerAliveCountMax 3
```

- 明确 alias 去重、Host 多别名、通配与否定规则、首次取值及多 IdentityFile 的解析夹具；列表只列可枚举的具体别名。缺少 User 时提供补全错误，不把 Android App UID 推断成远端用户。
- 维护客户端私有 home 与 SSH 目录，`~/.ssh/` 引用映射至该目录；IdentityFile 裸相对路径按所选 OpenSSH 版本和受管工作目录解析，不冒充“相对于 config 文件”。导入时提示缺失密钥并让用户匹配；电脑绝对路径不能假装在手机存在。采用已确认的用户主动导入和私有副本模式；本地命令执行按 R9 拒绝，越界路径及其他兼容项继续收敛。
- 未支持的有效配置明确报错并阻止受影响条目连接；不静默忽略 ProxyJump、ProxyCommand、Match、端口转发、RemoteCommand 或信任选项而直连/执行。解析配置本身不得执行命令。
- Include 已进入首版：需明确 SAF 授权目录/导入依赖文件、相对基准、词法展开顺序、最大文件数/深度/大小及循环拒绝；不能只选单个 config 后宣称引用文件可读。官方相对 Include 基准为用户 SSH 配置目录，而非根 config 位置或上一层 Include 文件所在目录。
- ProxyJump 已进入首版：支持单跳/多跳、每跳独立配置/认证/信任、取消及全链路关闭，失败不能退回直连。config 基础 TCP 转发已批准，默认 loopback、显式对外监听连接前确认；独立转发管理界面不随之进入首版。

### 配置目录管理决定（已选择 A）

用户选择主动 SAF 导入、不持续读取外部目录；2026-10-09 进一步要求这些内容一并加密保护/备份，因此 A 现为受管加密副本与逻辑目录，保留解锁后 config 编辑。下表 B 仍未采用，不能沿用此前明文目录实现。

| 模式 | 用户行为 | 技术与数据影响 |
| --- | --- | --- |
| A：App 私有 SSH 目录（已选） | 在 App 编辑 config，或选择目录导入 config / Include 文件 / 密钥；修改外部文件后手动重新导入 | 主动导入并保留受管逻辑目录，持久副本进入统一加密包；native 内存/FD 访问视图和引用兼容须探针验证。导入有界、冲突/信任先确认，卸载移除 App 副本，外部源不变。 |
| B：持续读取授权外部目录（未采用） | 用户在外部工具编辑，连接前重新读取授权目录 | SAF URI 不是普通 POSIX 路径，仍需有界私有快照供 native 使用；同步/授权失效等工作不进入本任务。 |

导入契约：用户选择 SSH 专用源目录或明确文件，列出拟导入对象与冲突；Include/密钥引用按保持的目录结构验证，缺失对象提示补充。先在有界内存或密文 staging 中验证并通过公共库事务提交，取消/失败保留旧配置/对象关系，不创建长期明文 staging。外部源不修改、不删除；连接不依赖导入后的 URI 可用性，不保留用于持续同步的授权。已导入 known_hosts 不因配置更新自动成为可信记录；导入清单把信任记录单列，用户明确审阅并接受后再有界合并，冲突不自动覆盖。没有显式接受的候选记录不参与连接核验；首次连接仍按 R5。

已有 ToolFiles 是私有副本导入，HFTP 支持目录授权；它们提供可复用的文件/权限处理模式，不能直接宣称 native ssh 能打开 SAF URI。Manifest 当前 `allowBackup=false`；这不替代 SSH 密钥的导入、清理及秘密保存产品决定。

## A / B 风险边界比较（2026-10-08）

用户要求比较安全性后已选择目录模式 A。以下保留威胁分析与所选方案的边界；它们属于规划，不是已实现或实测的安全能力。

在正常 Android 沙箱、相同认证保护的前提下，建议 A：App 内部私有 SSH 目录，外部目录仅供显式导入。A 减少外部编辑器/文件提供方参与运行配置及秘密存储，更易固定审阅过的连接配置。B 的风险取决于外部 provider、其他获授权应用及同步策略，不能把 SAF 授权描述为“任何应用都能访问”，也不能视为排他锁或内容可信证明。

| 风险 | A：内部私有副本 | B：持续读取外部目录 | 建议的产品边界 |
| --- | --- | --- | --- |
| 第三方读取/修改 | 正常情况下其他 UID 的应用无法直接访问内部文件；主动分享/导出另算 | provider、其他获访问权限的应用或云同步链路可能读取/修改；不是仅由 ctOS 控制 | 私钥、known_hosts 保持内部私有；不共享 SSH 数据目录给 FileProvider/HFTP，不申请全文件权限。 |
| 配置被替换 | 导入/内置编辑是显式变化；仍需验证内容 | 每次连接可能取得变化后的目标、跳板、引用和信任设置；跨文件读取可能得到混合版本 | 审阅后冻结整个 Include 闭包，连接使用同一快照；外部变更不能静默进入已确认连接。 |
| 私钥副本与泄露 | 受管副本加密，但外部原件不会因此消失；运行时已解密私钥仍暴露于进程/同 UID 攻击面 | 外部原件及私有快照均可能存在，泄露面增加；provider 是否加密不能由 SAF 推断 | 优先有口令的私钥；用户可选择通过宿主凭据库长期加密保存口令，解锁已选择公共生物/系统锁屏凭证验证。拒绝把密码放 config；不自动删除外部源，秘密只经用户主动的完整加密备份导出。 |
| 执行型配置 | 复制到私有目录不改变命令的危险性 | 外部修改更容易把新执行行为带入连接/预览 | 用户已选择首版拒绝 Match exec、自定义 ProxyCommand、LocalCommand、KnownHostsCommand 等本地执行功能，报出位置/原因，不提供放行开关。ProxyJump 的受管客户端链保留。 |
| 信任被替换/绕过 | 私有 known_hosts 更易保护，但恶意导入仍可修改信任 | 外部 known_hosts 或 config 可被修改，弱化校验或指定另一份信任文件 | 信任记录与配置导入分开；不自动信任导入的 host keys，不静默覆盖既有信任；拒绝安全默认下的关闭核验选项并解释兼容差异。首次接受须支持用户经其他可信渠道比对指纹。 |
| 路径与 Include 越界 | 同 UID 能读其他 ctOS 文件，目录名字和 chmod 不是额外沙箱 | tree 授权限制 URI 读取范围，但导入后的绝对/父目录引用仍需约束 | 应用索引、导入及客户端求值分别校验实际路径/引用闭包、软链、大小/数量/深度；仅允许受管 SSH 范围，范围外显式错误，不能静默改路径；与主动导入后使用私有副本的决定一致。 |
| 卸载、迁移、清理 | 清数据/卸载会丢失私有配置、密钥副本和信任 | 外部原件通常保留，但授权/同步/设备迁移仍可失效 | 完整受管 SSH 文件加密备份已确认，目标路径/引用/信任需验证；删除手机副本不等于撤销服务器授权。密钥泄露需要在服务器撤销对应公钥。 |
| 网络/会话副作用 | 存储私有不限制转发监听、agent 转发或本地进程 | 同样，且外部修改可引入这些选项 | 当前不交付 agent 转发，config 基础 TCP 转发已批准；默认 loopback、显式对外监听连接前确认已确定，进程数及全链路停止需显式契约。 |

### 两种存储都不能提供的隔离

- **同 App UID 的任意代码**：现有应用 Shell 由 APK fork/exec，未降权（`android/app/src/main/cpp/pty.c:51`）；终端 Python 和 native 执行型配置也可使用 App 权限。内部私有目录隔离其他 App，不隔离这些代码；明文文件及解锁后可读秘密不能靠再建一个子目录或 0600 权限保护。运行不可信本地脚本和保护同 UID 明文私钥不能同时宣称互不影响。
- **Root、系统或 ctOS 自身被攻破**：普通私有文件不构成独立硬件边界。即便使用 Keystore 包装加密私钥文件，客户端解密/使用时仍需面对进程内秘密和调用权限风险。真正用不可导出硬件 SSH 密钥签名需 OpenSSH 签名代理/适配，不能因存储在私有目录或“加密保存”就声称已经实现。
- **首次信任错误**：显示指纹并接受仅建立后续一致性，不能自动证明第一次连接的就是期望设备。每跳和最终目标都需核验；跳板转发不能替代目标服务器 host key 校验。网络加密不补救用户接受了错误服务器。
- **远端账号权限与已经执行的命令**：SSH 连接是用户主动远端操作，远端账号权限决定影响；本地 App 非 Root 不代表远端非 Root。断开、关闭转发或清理手机密钥不撤销已经完成的远端操作。

### 建议的安全默认与兼容取舍

已确定 A + 用户主动导入、首版拒绝配置驱动的本地命令执行、用户可选长期加密保存密码/私钥口令，保留 Include / 受管 ProxyJump。公共认证及生物/锁屏凭证验证已确定；config 基础 TCP 转发已确定；显式对外监听连接前确认已确定；主机核验禁止 config 绕过已确定；主要配置/认证按原生契约验证，受管路径和桌面扩展范围明确告知与 OpenSSH 的差异。兼容目标不应通过隐藏禁用、字段丢弃或“私有目录所以可信”实现。本地执行范围及长期保存选择不扩大为其他安全默认或实施授权。

执行型声明诊断覆盖根文件、所有 Include 文件及每跳配置，显示文件/行号、字段和修复动作。若无法证明一个条目不受禁止执行行为影响，则阻止其连接；若无法准确限定受影响条目，阻止该配置集合并说明原因。不能执行命令来判断它是否安全，也不能先解析运行一次再显示错误。原生策略应区分用户自定义 ProxyCommand 和宿主拥有的 ProxyJump 派生，避免误杀已批准的多跳功能；具体版本适配在探针中验证。

现有 `android:allowBackup=false`（`AndroidManifest.xml:14`）及 `data_extraction_rules.xml` 排除规则只是已核实配置，不是对每个 ROM、手动导出、云端原件或运行中秘密的实测保证。新增 SSH 数据需按所选系统/设备验证迁移和清理行为；无设备操作授权时不执行验证。

### 安全证据来源

- [Android 内部私有存储](https://developer.android.com/training/data-storage/app-specific) 与 [应用沙箱](https://source.android.com/docs/security/app-sandbox)：提供其他 App UID 的隔离；同 UID 终端风险是结合 ctOS fork/exec 源码的推断。
- [SAF 目录和持久 URI 授权](https://developer.android.com/training/data-storage/shared/documents-files)：用户选择目录限定 ctOS 获得的访问范围；授权本身不验证内容，也不承诺其他编辑器或 provider 不能修改源。
- [OpenSSH 配置手册](https://man.openbsd.org/ssh_config)：执行型命令、信任/转发选项的语义；禁用建议属于产品范围选择，不能视为原生 OpenSSH 已经默认限制全部执行功能。
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)：不可导出 Keystore 密钥与加密文件的区别，应用被攻破仍可能滥用密钥；本任务尚无硬件 SSH 签名适配。

## 交互与状态建议

在已有“应用 Shell”“Root PTY”后增加“SSH”入口，进入具体主机列表；配置动作放主机列表中，列表项点击即连接。空列表提供配置编辑/导入，解析错误显示位置和恢复入口。会话标题显示别名及 user@host:port，连接等待可取消，失败可重试/编辑。

连接状态：idle → connecting → awaiting_host_trust → authenticating → connected → closing → closed；任意中间状态可 cancelled/failed。拒绝指纹与取消输入均关闭正在连接的资源；网络故障不会自动重连或自动重放命令。

认证 UI 与远端终端输入分离。沿用 TerminalView、底部单一输入、五个快捷键、折叠及输出快照；SSH 中 ↑/↓ 和其他控制键按终端键编码发送远端，ctOS 不提供本地/远端历史切换，不调用本机 TerminalCommandHistory 替换输入。输入适配只处理字符/IME 编辑及控制序列的传输，不从远端提示符推断命令行、回填底部输入或追加远端程序操作；远端输出交终端渲染器解析，窗口变化同步 remote PTY。Start/Stop 与已有两个终端一致，不新增全屏/程序模式。检查限于字符与控制键送达、输出渲染、尺寸事件和会话隔离，不建立指定远端程序的兼容验收。

UUPM 本地查询已完成（design-system + Flutter stack）：取用清晰等待/错误恢复、长列表按需构建和触控可访问性；继续使用 `lib/ui/ctos_theme.dart` 及现有 Card/阅读宽度。查询给出的网页 Hero、字体下载、配色覆盖等不适用于现有 App，不形成第二套设计系统；界面稿为待审建议。

## 工程界限及实施首个技术关卡

工程初值用于使资源有界，不覆盖用户 config 的上游选项语义：单活动终端；2026-10-10 与公共 v1 库统一，受管导入/Include/密钥/信任闭包最多 128 文件、引用深度 8、单文件 1 MiB、文件总量 8 MiB（完整 manifest 明文最多 12 MiB）。旧候选 256/16/32 MiB 不再适用，不能接受无法由共同库保存/恢复的闭包。跳板最多 8 跳，普通转发最多 32 条；服务输出环形缓存最多 4 MiB，UI 沿用 3000 行滚动历史。超过上限给出可定位诊断，导入保持旧数据，不截掉配置字段继续连接。输出裁剪明确显示历史截断，不能影响当前可信状态。实施中的内存/流量证据若要求调整初值，记录变动，不取消有界性或改变已确认安全规则。

连接/保活使用有效 config 的 ConnectTimeout、ServerAliveInterval、ServerAliveCountMax 与选定 OpenSSH 默认；上方示例的 10/30/3 是用户可编辑的配置夹具，不是宿主隐式覆盖。Stop/取消始终可用。认证并发只允许一个系统请求，新独立请求返回 BUSY，重复 requestId 去重并只完成一次；不无限排队。系统 AuthContext 在成功后最长 120 秒且限当前设备包装/系统认证流程，完成/失败/取消即失效；公共库会话期限按前台共享/后台用户可配置秒数及锁屏/手动即锁规则，不把系统期限当作库会话或连接生存期；等待用户系统认证最多 120 秒。系统 UI 导致的生命周期切换与普通后台分别处理，OS 密钥认证窗口不能被 App 上下文延长。多跳/系统 API 探针验证这些工程期限，无法在有限期限中满足已批准行为时回规划。

在产品代码开始大规模接入前，以临时测试密钥和隔离测试端点证明：真实 OpenSSH 在 Android/Bionic 打包运行；private home/Include 语义、受控每跳子进程和 secrets/control IPC 可实现；native 求值/执行边界拒绝本地命令和绕过信任策略；APK 无需安装其他 App。先固定版本、来源哈希、构建依赖及许可证，不能把浏览 master 的研究当依赖锁定。本轮没有运行该探针；后续只有完整规划获实施批准才能执行。

## 已确认基础 TCP 转发与监听确认

用户同意首版包含 config 中的 LocalForward、RemoteForward、DynamicForward。它们随既有 SSH 会话建立与结束，不新增独立转发常驻服务或管理界面；后台保留与手动 Stop 沿用 R8。

- 使用 native OpenSSH 的 TCP 转发求值与数据通道，不另写一套代理；首版含本地端口到远端可达 TCP 目标、远端监听到客户端可达固定 TCP 目标，以及客户端 SOCKS4/5 动态代理。支持多条/重复声明与 IPv4/IPv6；Unix-domain socket、远端动态 SOCKS、TUN/TAP 及运行中 escape 命令修改转发为进一步能力，不能因字段同名静默执行。
- 配置/主机列表求值和导入不建立监听、不做转发探测；通过目标/每跳 host key 和远端认证后，当前会话才建立转发。连接预览展示转发方向、申请监听地址/端口及固定目的地，SOCKS 显示为动态目标。实际状态需区别请求、成功/失败、服务器决定的远端监听和目的端连接结果，不能仅凭远端输出判定。
- 默认监听遵循 OpenSSH 的 loopback 行为；显式 bind address 和客户端 GatewayPorts 可以改变本地暴露面，远端监听还受服务器 GatewayPorts/权限决定。不能把本地客户端字段当成对服务器实际监听的保证。用户已选择默认仅本机，显式非 loopback/通配监听连接前展示暴露范围并额外确认，不设置永久记住开关。Gate 位于启动/监听之前，批准结果绑定 sessionId、有效配置快照与所确认的监听集合；变化后只能在新确认下建立新连接。拒绝/取消结束本次连接，不静默删字段或降为 loopback。监听地址不能证明属于 loopback 时按对外暴露处理；覆盖 IPv4/IPv6、空地址、通配、指定接口及 GatewayPorts 引出的变化。远端默认请求 loopback，但 sshd 的策略可能扩大范围，客户端不能仅从转发请求成功证明服务器实际仅监听本机；无法证实时需说明为请求范围/服务器决定并走暴露确认，不能显示“已保证仅本机”。
- 遵循 ExitOnForwardFailure：yes 时请求的转发无法建立则结束本次 SSH；no 时 SSH 可继续，但展示具体转发失败。转发已建立后目的端不可达与建立监听失败分别呈现，不扩大该选项为“任意目标连接失败都退出”。远端端口 0 等实际分配值若进入固定版本支持范围，需从可信客户端事件获取，不猜测端口。
- 转发与 SSH 服务共用 sessionId/所有权；页面重建或返回不重复监听。Stop、换用、取消或连接结束关闭本地监听、已有转发通道及当前连接请求的远端转发；不影响其他应用/其他 SSH 连接。多跳同样关闭全链路，不通过 Root 修改端口权限、VPN、路由或防火墙。
- 数据面测试验证 LocalForward、固定目标 RemoteForward 和 SOCKS4/5 的双向字节传输，多条转发、IPv4/IPv6、端口冲突/服务器拒绝、ExitOnForwardFailure yes/no、目的地不可达、后台持续及 Stop 后不可再访问。非 loopback/通配监听增加展示、接受/拒绝/取消、配置变更、旧 session 确认复用、实际监听地址及 Stop 的隔离网络夹具，不读取个人 SSH 配置或创建实际局域网暴露。

依据：[OpenSSH ssh_config](https://man.openbsd.org/ssh_config) 的转发、客户端 GatewayPorts 和 ExitOnForwardFailure，以及 [sshd_config](https://man.openbsd.org/sshd_config) 的服务器 GatewayPorts 定义（yes 可强制通配监听）；范围、状态 UI 及安全确认策略为 ctOS 产品设计，尚未实现或验证。

## 已确认会话生命周期与服务设计

用户选择“保留连接”，并要求“和其他两个 terminal 一样，手动 stop；保持心智模型统一”。沿用现有单活动终端，Stop 和“换用”都是显式结束当前会话；普通导航、切后台、系统认证 UI 切换及 Activity 重建不主动关闭 SSH。会话退出仍包括远端 exit、网络故障和系统终止，不把“手动 Stop”承诺为永不掉线。新连接必须在旧会话结束后发起，不静默替换；公共库锁定不主动断开已经完成远端认证的 SSH。

- 独立、非导出的 SSH 前台服务拥有客户端/跳板子进程、PTY、读写线程、会话状态及有界输出。Activity/Flutter 只订阅并发送带 sessionId 的输入与 Stop；重建后重新附着，去重回放输出，不重复启动客户端。切后台后继续读取输出，不依赖 Flutter 页面仍存在；缓冲达到上限时裁剪滚动历史并记录截断，不因无限输出耗尽内存。
- 从前台用户连接动作启动服务并及时建立通知；通知显示会话及返回/Stop，Stop 绑定当前 sessionId，旧通知不能误停新会话。Android 13+ 通知权限拒绝时按平台实际可见性说明并保留页面 Stop；不得宣称用户仍能从通知栏操作。启动失败清理资源并显示原因。
- 规划采用 Android 14+ `specialUse` 类型，声明 `FOREGROUND_SERVICE_SPECIAL_USE` 及描述交互 SSH 持续会话的 subtype；这是依据官方类型定义作出的设计判断，实施需在目标版本验证。Android 9–13 走相应兼容前台服务路径。不能直接借 HFTP 的 `dataSync` 类型；Android 15+ 对 dataSync 后台运行有时限，不适合作为持续交互连接的默认设计。不为满足 connectedDevice 的前置条件额外申请与业务无关的网络修改权限。
- Stop/取消/失败精确关闭整个跳板进程树、FD/socket 和秘密通道，待回收完成才释放活动会话；终止前台状态和通知。服务使用不自动恢复连接的启动策略，无开机连接或网络恢复后命令重放；Android 系统停止/强制停止遵循系统行为，不能依赖必有销毁回调。
- 已完成远端认证的连接不因本地认证上下文过期而主动断开；本次用途/目标绑定上下文在认证结束后失效，明文尽快释放。后台不会自动拉起新生物认证，重连及需要新凭据的操作须回到前台。
- 验证普通后台、返回、旋转/Activity 重建、通知 Stop、页面 Stop/换用、多跳取消、断网、系统停止、通知拒绝及锁屏/Doze。前台服务不承诺绕过 Doze 或 OEM 电源限制，首版不自动增加 Root、长持 wake lock 或电源豁免；把受限时的断开/恢复结果如实记录。现有 App/Root UI 的手动操作沿用，旧原生 PTY 的生存期不随本任务暗中扩大。

依据：[前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)、[服务启动](https://developer.android.com/develop/background-work/services/fgs/launch)、[后台启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)、[dataSync 时限](https://developer.android.com/develop/background-work/services/fgs/timeout)、[Doze 限制](https://developer.android.com/training/monitoring-device-state/doze-standby)。本轮仅研究及规划，未验证设备后台存活。

## 风险、兼容及回退

- SSH 走 App 网络权限，遵守当前 VPN/网络环境，不复用 HFTP Root 中继，不自动修改系统网络。
- SSH 必须避免已有输入中的本机提示符识别、行回填和本地历史截获；以输入字节/控制键、输出渲染与尺寸同步检查传输职责，不按指定远端程序承诺或验收兼容程度。
- 后台及设备休眠不能沿用 HFTP 存活结论；本机 PTY 文档与代码差异记录为现状，本 task 不顺带扩大旧终端的生命周期行为。
- native / Python 候选涉及 ABI、来源、加密依赖及许可证；Dart 候选涉及 SDK 下限和主机信任默认行为，均须实现前做可复现探针。
- 失败可回到主机列表且保留配置；停止只负责本地资源，不承诺终止所有远端进程。实现回退关闭 SSH 入口并恢复原依赖/adapter；不删除用户配置、密钥或信任记录。
- Android 11 与手机的连接、IME、尺寸和生命周期结论分别记录。任何真实连接/安装都须任务内独立授权。

## 外部研究（2026-10-08）

- [OpenSSH 配置手册](https://man.openbsd.org/ssh_config)：定义 Host 模式、首个获得值优先、IdentityFile 累加及 Include/ProxyJump 等语义。其完整行为比字段读取广得多；本规划需声明兼容范围。
- [dartssh2 发布文档](https://pub.dev/packages/dartssh2)：提供远端 Shell/PTY、认证及主机验证回调；缺少 onVerifyHostKey 会接受主机密钥，ctOS 必须实现自己的信任判断。发布页当日为 4.1.0，仅候选，未安装，未锁定；[维护源码](https://github.com/vicajilau/dartssh2)。
- [Paramiko 配置文档](https://docs.paramiko.org/en/stable/api/config.html)：SSHConfig 提供查找结果，调用方仍需显式应用连接配置，不能等同 OpenSSH 客户端；[安装文档](https://www.paramiko.org/installing.html) 列出 cryptography、bcrypt、PyNaCl 依赖。
- [Termux OpenSSH 构建定义](https://github.com/termux/termux-packages/blob/master/packages/openssh/build.sh)：作为 Android 构建工作量的研究入口；不是可直接复制到 ctOS 的运行环境，也不要求安装 Termux。
- [OpenSSH ssh 手册](https://man.openbsd.org/ssh)：`-F` 指定用户配置且不读系统配置；`-G` 求值 Host/Match；`-J` 支持多跳。用于配置对照计划，尚未运行这些命令。
- [upstream readconf.c](https://github.com/openssh/openssh-portable/blob/master/readconf.c) 与 [ssh.c](https://github.com/openssh/openssh-portable/blob/master/ssh.c)：本轮查阅 Match exec、passwd home 及 ProxyJump 派生逻辑。上述 Android 路径和只读求值适配建议是据源码作出的推断，尚未在 ctOS 上验证；master 仅作研究来源，不是选定依赖版本。

只查阅文档及源码，未运行外部示例、未下载二进制、未做 APK、协议或设备验证。

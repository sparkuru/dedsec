# 初步规划检查

当前阶段说明：下列各节是按当时状态记录的历史。2026-10-08 用户随后在 Authenticator 讨论明确统一秘密模型，旧完整最终审阅候选已失效；最新共同规划见本文末尾和 [公共秘密库](../../../design/app-authentication.md)。

2026-10-08。这里只记录 planning 文档检查，不是 SSH 产品质量门槛通过。

- 读取项目 design 入口、规划/约束、相关 spec、源码与现有 task 记录；核实已有 App/Root 终端和 APK 内运行环境，记录 onStop 与历史文档的差异。
- 查阅 OpenSSH、dartssh2、Paramiko 官方文档和 Android OpenSSH 构建研究入口，来源见 [design.md](design.md)。没有安装依赖、运行外部示例或连接设备。
- 本地 UUPM design-system 与 Flutter stack 查询已执行；规划采用等待/失败恢复、列表及可访问性建议，继续沿用项目主题。
- 规划文件本地相对链接、JSON/JSONL 格式、上下文文件存在性及 `git diff --check` 已检查；task.json 状态仍为 planning。
- 用户选择兼容范围 B、存储方式 A、按建议拒绝配置本地命令及可选长期加密保存密码/口令后已更新三个规划文件：技术主方案 native OpenSSH，用户主动导入到内部私有目录、不持续同步外部目录；Q3、Q4c 未收敛，未做最终 PRD convergence pass，未执行 task.py start，未达到实施审阅门槛。
- 本轮补充 OpenSSH 上游配置求值、用户目录、ProxyJump 派生研究，以及既有 ToolFiles/HFTP SAF 和 Manifest 备份设置的源码检查；将路径管理、只读预览及多跳关闭列入后续探针，没有运行 SSH 命令。
- Flutter/Android 测试、APK 构建、SSH 集成/实机验证本轮均未运行；无产品代码变更。
- 用户追问目录安全性后，核对 Android 内部存储/应用沙箱、SAF、Keystore 和 OpenSSH 官方文档，以及 ctOS PTY UID、Manifest/迁移排除配置。新增 A/B 风险边界及安全默认建议；用户随后选择 A，但其余安全默认不算产品决定，没有实施安全措施或进行设备验收。
- 本轮新增 AC7 导入验收及原子导入/外部变化隔离计划，文档链接/JSON/JSONL 与 diff 检查通过；用户选择 A 未被当作实施授权。
- 用户“按照建议来”对应上轮本地命令问题，已记为 R9/AC8：首版拒绝并诊断，无手动放行，保留受管 ProxyJump。增加根文件/Include/每跳及 none/no 的后续验证场景；本轮仅文档检查，未执行夹具或产品验证，不推定批准其他安全默认或实施。
- 用户允许主动选择长期加密保存密码/私钥口令，已记为 R10/AC9。查阅 Android 官方 Keystore、系统认证及 KeyGenParameterSpec API 文档，补充宿主凭据库、身份绑定、删除/失效、明文使用期及 API 28/29 兼容设计；随后用户已选择生物/系统锁屏凭证验证，未生成密钥、实现认证或实测设备。

- 用户已选连接前生物/系统锁屏凭证验证，新增 R11/AC10 公共认证复用。父 task 拆为公共认证底座/SSH 接入两个子任务，均有 PRD/design/implement 与真实上下文，明写先底座后集成；当前仍为父 planning，未 task.py start。
- 本轮核对宿主/服务接口，查阅 Android 官方生物认证、Keystore 与后台 Activity 限制，记录公共认证作用域、错误、并发/取消、受保护操作和平台测试建议；没有实现接口或强制改造其他生产服务。

## 2026-10-08：后台保留与手动 Stop 规划更新

- 用户选择保留后台 SSH 连接，并要求和已有两个终端统一手动 Stop 心智模型。父 R8/AC6、设计及实施计划、子任务引用和 design 入口已同步；Q3 生命周期已关闭，Q4c 进一步兼容仍待收敛。
- 核实当前终端页面为单活动会话，Stop 与“换用”均调用 stopTerminal；规划沿用显式结束当前会话，导航/后台不主动断开 SSH，不扩大旧 App/Root 原生 PTY 生存期。
- 查阅 Android 官方前台服务类型/启动/时限及 Doze 文档，规划独立非导出 SSH 服务、specialUse 类型适配、通知 Stop、页面重新附着、有界输出及全链路回收；保留连接不延长凭据解锁上下文。
- 仅规划文件检查，不执行 task.py start，不运行产品测试/构建或设备验证。前台服务存活、系统认证、锁屏/Doze 均未实测。
- 本轮文档检查通过：三个 task 均 planning，30 条真实 JSONL 上下文、16 个 Markdown 文件及 116 个本地链接有效；git diff --check 通过，当前任务指针仍为父任务。并行 authenticator 规划未修改。

## 2026-10-08：基础 TCP 转发规划更新

- 用户回复“同意”，明确接受首版 config LocalForward / RemoteForward / DynamicForward 的基础 TCP 转发建议。已同步父 R6/AC3、技术矩阵/数据面验收、实施顺序、SSH 子任务及 design 入口。
- 查阅 OpenSSH 官方 ssh_config，区分默认 loopback、显式监听/GatewayPorts、服务器远端监听策略及 ExitOnForwardFailure 的建立失败语义；非 loopback 监听允许/确认策略未自动选定，仍列 Q4c。
- 本轮只更新规划，三个 task 保持 planning，不运行 task.py start、产品测试/构建、真实转发或设备操作；并行 authenticator 规划未修改。
- 文档检查通过：三个 planning task、30 条真实上下文、16 个 Markdown 文件及 116 个本地链接有效；git diff --check 通过，当前任务指针仍为 SSH 父任务。

## 2026-10-08：对外监听确认规划更新

- 用户选择建议方案：默认仅本机，config 显式要求对外监听时连接前展示暴露范围并确认。父 R6/AC3、设计及实施计划、子任务引用、状态备注和 design 入口已同步。
- 确认绑定本次配置快照/会话/监听集合；拒绝取消本次连接，不静默变换地址，旧会话确认不复用。远端真实监听受 sshd GatewayPorts 决定，官方手册说明 yes 可以强制通配；不能从客户端申请成功推断仅本机，无法证实时说明并纳入暴露确认。
- 剩余首版兼容矩阵已整理为待用户整体确认的建议：普通 Shell/基础 vim/top、常用认证及非执行 config、R5 主机核验策略与 CLI/桌面扩展延后。尚未将建议视为批准，也未完成最终规划审阅或执行 task.py start。
- 本轮只更新规划，未运行产品代码、构建、SSH/转发或设备验证；三个 task 保持 planning，并行 authenticator 规划未修改。
- 文档检查通过：三个 planning task、30 条真实 JSONL 上下文、16 个 Markdown 文件及 116 个本地链接有效；git diff --check 通过，当前任务指针仍为 SSH 父任务。无产品实测结果。

## 2026-10-08：远端输入职责及撤销程序兼容验收

- 用户明确远端程序使用自身操作逻辑，ctOS 不追加；SSH 连接后 ↑/↓ 交远端处理。用户拒绝特定程序的“兼容验收”，认为引入歧义；前一轮 vim/top 范围建议已撤销，不作为实施/验收要求。
- 父 R2/AC2、技术矩阵/交互设计/风险、实施计划、SSH 子任务、状态与 design 入口已同步。SSH 不使用 ctOS 本地命令历史截获，不追加程序模式、提示符推断或行回填。验证只具体描述输入/控制键传输、终端输出渲染、PTY 尺寸和会话隔离。
- 已修订父 JSONL 的终端 spec 上下文说明，保留 App/Root 回归契约，防止将本机历史行为套给 SSH。其余首版范围/主机核验选项仍待收敛，未进行最终规划审阅或 task.py start。
- 仅规划更新，未修改产品代码或执行构建/设备/真实连接；三个 task 均 planning，并行 authenticator 规划未修改。
- 文档检查通过：三个 planning task、30 条真实上下文、16 个 Markdown 文件及 116 个本地链接有效；git diff --check 通过，当前任务指针仍为 SSH 父任务。主规划三个文件已无 vim/top 专属验收建议，未产生产品实测结果。

## 2026-10-08：禁止绕过主机核验及完整规划整理

- 用户回复“可以”，确认 config 不能跳过主机指纹核验。父 R5/AC5、技术矩阵、原生/配置/每跳边界及 SSH 子任务已同步；no/off、自动接受未知等违反该规则的有效选项定位拒绝，不能静默覆盖后声称兼容。
- 重新通读并收敛父 PRD：保留 R1–R11/AC1–AC10、源码锚点、已确认输入/生命周期/导入/执行/长期保存/认证/转发决定；已回答问题移入需求，技术未实测项单列，不把实施关卡当产品未决问题。
- 常用认证与主要 config 具体选项承接最初“基础、主要功能与 OpenSSH 相似”的 UI/config 目标；通用 CLI 和桌面扩展没有从二进制存在推导为新交付。工程资源界限、BUSY/期限、API 与 native 可行性关卡明确，探针失败回规划，不静默改目标。
- 父/子 design、implement、公开 design 入口和状态均同步为完整规划等待最终审阅；六个上下文清单使用真实文件，新增权威父 PRD 引用。尚未展示后得到最终实施批准，三个 task 均 planning，没有 task.py start。
- 本轮仅文档检查，无产品代码、依赖安装、native 构建、系统认证、SSH/转发或设备实测。并行 authenticator 规划未修改。
- 完整规划文档检查通过：三个 planning task 均 awaiting_final_review，父/子依赖及当前父任务指针有效；R1–R11/AC1–AC10 与源码锚点保留；36 条真实上下文、16 个 Markdown 文件及 117 个本地链接有效，git diff --check 通过。没有产品 AC 被标记通过。

## 2026-10-08：统一秘密模型取代分业务凭据方案

- 用户要求将 Authenticator 主口令/便携库/可选设备快捷/覆盖迁移模型应用到 SSH，降低理解和维护成本。公共底座扩展为 AppSecrets + AppAuthentication，SSH 只保留类型化身份 adapter/native 秘密通道，R10/R11 和 AC9/AC10 已更新，其余 R/AC 客户端行为保留。
- 主口令格式、KDF、设备包装、库会话及迁移不再在 SSH 重复实现；旧设备-only 不迁移/每连接系统弹窗方案被统一公共会话取代，库会话锁定/复用与备份外部资源边界仍待收敛。
- 四 task 全部 planning / requirements_reconvergence，任务 ID/树关系保留，依赖顺序公共底座 → TOTP/SSH。原完整最终审阅候选失效；无实现/构建/设备或真实凭据使用。
- 本轮共同文档检查结果见 [Authenticator 共同规划检查](../archive/2026-10/10-08-authenticator/check.md) 和公共底座规划；不将历史 awaiting_final_review 当作当前授权或已收敛结论。

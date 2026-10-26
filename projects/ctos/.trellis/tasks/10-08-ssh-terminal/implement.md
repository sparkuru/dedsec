# SSH 终端实施计划

## 2026-10-26 最终验收与提交

- [x] 64KiB锁页实际child回归及内部memfd权限故障矩阵，保留锁页/擦除和严格对外FD规则。
- [x] 最终源码/产物冻结，完整原生、三类密钥互通、构建/lint与开发板四项回归通过。
- [x] 用户明确允许后，042版保留数据更新手机，核对APK哈希及原UID。
- [x] 正式ctOS原有私钥实际登录，公开主机键核验/保存，whoami及非回显PTY标记通过。
- [x] 测试会话、临时包和UI XML清理；用户人工验收并明确授权提交。
- [x] 最终文档/证据一致性复核通过，按用户明确授权提交本轮SSH变更；会话记录单独收尾。

审阅分类human-required，已由用户明确验收通过。真实DocumentsUI仍未专项操作；其余
导入/config/保存密码具备隔离实际bridge回归，不扩大为父完整AC通过。下方是前序阶段
记录，本轮最终证据见design/verification.md#20261026-phone-ssh。父task保留in_progress。

## 最新实施：config 与无口令私钥

- [x] 核对当前产品缺口，用户确认普通 OpenSSH 私钥无口令；复用原 task，不访问个人密钥。
- [x] 调查真实 native 私钥认证、config effective Options、SAF和原子图事务，记录当前合同。
- [x] native 真实公钥 blob 导出与 direct-plan 接口及严格测试，保留旧矩阵。
- [x] 扩展唯一 manifest 准入/原子 metadata 变更，实际 key/config 导入预览及连接/主机核验。
- [x] 受保护私钥/配置导入、编辑/别名选择与无密码认证 UI，保留旧密码路径及其改动。
- [x] key-only 隔离互通、相关 JVM/Flutter、lint/产物与独立复核；设备/账号按当前授权。
- [x] 同步 design/spec/验证，父 AC 不因该直接场景全部完成。
- [x] 当前授权下隔离Android私钥/config及保存密码回归，清理后保留数据正式覆盖安装。
- [ ] 真实DocumentsUI/文件grant、新界面人工体验及用户实际密钥/目标验收；未访问真实账号。

主会话继续串行全部 hako/设备执行与文档；native、host、Flutter 分开归属给 Trellis
implement，独立 check 复核。下方密码保存切片是已完成本地验证的前序记录。

本轮先完成753 JVM/271 Flutter本地检查；设备发现三处legacy路径消费后修正，
最终755 JVM、完整native1758247/issuer75、独立实际路径key-only及两项Android回归通过。
正式373f7161…已保留数据安装并核对，见design/verification.md#20261026-key-config-device。
真实系统选择器/人工界面/用户实际目标未验收；不提交或归档父task。

## 2026-10-26 当前实施切片

用户已选择可选保存 SSH 密码，不新建任务。本节取代下文历史 planning 对当前切片的描述；
父任务此轮拥有直接 R10 工作，不重开已归档的前台密码子任务。

- [x] 扩展正式 profile/validator：精确 Account/密码绑定及多个目标，保持 TOTP/信任。
- [x] 公共原生命周期内原子保存/忘记账号与密码，接 selected saved read，默认当次不写密码。
- [x] 精确 metadata-only status/bridge/API 与保存/复用/忘记 UI，防旧目标/迟到请求复用。
- [x] 针对性 JVM/Flutter，然后全量731 JVM/255 Flutter/analyze、Android lint/构建与源码独立复核。
- [x] 同步 design/spec/实际验证，见 design/verification.md#20261026-saved-password。
- [ ] 当前未授权的新能力实机验收；隔离 Android 回归已准备，父完整 AC 继续开放。

提交审阅分类：human-required。新增敏感表单和实际 saved-account/native 连接尚未实机，
不能用 widget/JVM 或历史一次密码验收替代。先按项目设备授权约定完成隔离实机审阅，
当前不提交/归档，不启动其他父规划范围。

主会话负责文档和全部串行 hako 构建；Trellis implement 分别拥有 Java 与 Flutter，check
独立审阅最终源码/检查结果。先完成可复核变更，再报告设备验收缺口。保留完整父 AC 未完成。

状态：planning / 已确定范围整理完成，待共同最终审阅。旧完整审阅候选失效，步骤按共享底座依赖排序；此清单不是实施授权。

## 0. 规划收敛门槛

- [x] 建 task，读取 design 入口、规划、约束及终端/Runtime spec。
- [x] 核实当前终端、APK 能力、配置标准及客户端候选，形成 design 初稿。
- [x] 用户选择 B：Include / ProxyJump 进入首版，基础及主要功能参照 OpenSSH。
- [x] 用户选择存储方式 A：用户主动导入到 App 内部私有 SSH 目录，不持续同步外部目录。
- [x] 用户按建议选择 Q4a：首版拒绝配置驱动的本地命令执行，无手动启用，保留 Include / 受管 ProxyJump。
- [x] 用户选择 Q4b：允许用户主动选择长期加密保存密码与私钥口令，同时保留仅当次使用。
- [x] 用户选择 Q4d：连接前生物/系统锁屏凭证验证，公共认证作为 App 一等公民供其他内部服务调用。
- [x] 建立先公共认证底座、后 SSH 接入两个子任务，均保持 planning。
- [x] 用户同意首版 config 基础 TCP 转发：LocalForward / 固定目标 RemoteForward / DynamicForward。
- [x] 用户选择监听策略：默认仅本机，config 显式要求对外监听时连接前确认暴露范围。
- [x] 用户确认 Q4c 信任边界：config 不能跳过指纹核验，未知确认、变化拦截；主要认证/config 与桌面扩展按初始 UI 目标落实为明确契约。
- [x] 用户选择 Q3：保留后台连接、手动 Stop，与现有两个终端心智模型统一；独立 SSH 前台服务，导航不主动断开。
- [x] 用户明确 SSH 输入及 ↑/↓ 交远端，ctOS 不追加远端程序操作，不设特定程序的兼容验收。
- [x] 固定主要 config/认证契约、资源工程初值、上游 config 超时语义、输入职责与原生可行性关卡；客户端版本/来源/许可证必须在实施首个探针锁定。
- [x] 按最终决定收敛整理 PRD，保留源码锚点，核对 R1–R11 / AC1–AC10，技术未验证项单列。
- [x] 更新 design、implement 及两个真实 JSONL，准备完整规划审阅。
- [ ] 用户在后续消息批准最新完整规划后才执行 task.py start。

## 父子执行顺序

父 task 维护源需求/依赖/集成，不作为实施入口。最终规划批准后，先执行 [公共认证底座](../10-08-app-authentication/implement.md)，交付独立可验收接口，再执行 [SSH 接入](../archive/2026-10/10-08-ssh-terminal-client/implement.md)。下文为 SSH 主体清单，供子任务引用。

## 1. 客户端与协议探针

- [ ] 在实现授权范围内选定及固定客户端依赖；使用隔离临时 SSH 测试服务和临时测试密钥，禁止读取个人 ~/.ssh。
- [ ] 验证密码、批准的密钥格式/加密格式、未知/变化指纹、SSH remote PTY、窗口变化、取消/断线关闭及超时。
- [ ] 主方案 native OpenSSH：确认 APK 原生目录、Bionic/API 28、arm64、16 KiB、私有 passwd/home/路径语义、known_hosts/密钥、认证 UI/askpass 及每跳派生客户端。同版本参考 OpenSSH 对照配置结果与实际连接。Dart/Python 仅保留方案比较，不能在失败时自动降级。
- [ ] 证明列表/配置预览不执行 Match exec/LocalCommand 等命令；核对 `ssh -G` 的副作用边界，验证 Include 闭包和每跳继承私有环境。
- [ ] 实现 R9/AC8 的 native 配置求值及执行边界检查，覆盖根文件/Include/每跳/CLI，区分自定义代理命令与受管 ProxyJump；禁止命令夹具在导入/预览/连接各阶段均不执行，none/no 不误报。
- [ ] 若探针不能实现批准行为，回规划调整方案；不删验收项或暗中换路线。

## 2. 配置、统一秘密库接入与信任

- [ ] 基于原生 OpenSSH 受约束求值实现诊断、别名列表和有效配置模型；目录索引不另写连接配置求值器。用相同版本参考客户端对照批准的语义。
- [ ] 实现公共密文文件对象/稳定受管 SSH 逻辑结构、解锁后的内置 config 编辑及用户主动 SAF 文件/目录导入，保留 Include 相对结构；有界内存或加密 staging 验证后同代次原子提交，缺失引用和冲突可恢复，取消/失败不替换旧配置。不持续同步外部目录，不删除外部源，不自动覆盖密钥/信任；配置信息与 Portable 版本资源分离。
- [ ] 优先完成加密文件对象到 native 的内存/匿名 FD/受管文件访问探针，覆盖 config/Include/IdentityFile/known_hosts、ProxyJump 每跳继承/写回、权限与生命周期；不支持时回规划，不用普通磁盘明文临时文件悄悄替代。随后实现密钥引用/缺失恢复、独立认证秘密通道和加密信任对象；根 config/Include/每跳拒绝绕过指纹核验的选项，定位不覆盖、不静默降级，导入信任单列审阅。
- [ ] 依赖公共统一秘密库已验证接口，实现 SSH 类型化 adapter 与连接操作上下文；锁定时共享主口令/设备快捷入口，各跳核验身份/host key；落实已确认的前台共享和后台可配置秒数/锁屏/手动即锁策略，不另建 SSH-only 解锁路径。
- [ ] R10：用户显式保存/忘记统一库 SSH namespace 条目，绑定身份/目标；公共底座负责 KDF/便携密文/设备包装/迁移。快捷失效回主口令，不经 argv/环境/日志传秘密，SSH 全部忘记不删除 TOTP；config/Include/私钥/关联公钥或证书/信任一并加密迁移已确认，导入校验闭包/路径/身份并审阅信任；native 内存访问视图不另存长期明文副本。
- [ ] 按最终批准的安全矩阵验证执行型配置、越界 Include/IdentityFile、导入信任替换、外部修改/混合快照、secret 导出及普通转发；明确同 UID 脚本不受文件子目录隔离，禁止将私有文件等同硬件密钥保护。
- [ ] Include / ProxyJump 为必做：先验证引用相对基准、嵌套/条件/通配/引用上限、配置集合快照及单/多跳认证/信任/全链路关闭，再做界面连接流程。

## 3. 会话管理与共享终端

- [ ] SSH adapter 拥有客户端、socket/进程和 remote PTY；与 App/Root adapter 共享视图能力。
- [ ] 接入 native 基础 TCP 转发，展示请求方向/监听/目的地及建立结果；默认 loopback，对外暴露在启动前确认并绑定会话/配置/监听集合，拒绝取消不创建监听、配置变更重审，远端实际范围未知不虚报；落实 ExitOnForwardFailure、资源所有权与 Stop 后回收，预览/导入不得绑定端口。
- [ ] 用可信状态事件区分退出、失败和取消；远端字节不得参与可信状态解析。
- [ ] 写入串行、generation/sessionId、迟到回调和认证响应隔离，取消及关闭幂等且资源有界。
- [ ] SSH 的字符/IME 编辑、Tab/控制键/↑/↓ 发送当前远端会话，不触发本地历史替换、提示符猜测或行回填；输出渲染与 PTY 窗口同步沿用终端职责，不加入程序专属模式/操作。验证传输、窗口事件和旧会话隔离，不建立特定远端程序兼容验收。
- [ ] 实现独立非导出 SSH 前台服务、相应平台服务类型/通知与页面重新附着；资源不由 Activity 持有，有界输出、单活动会话、sessionId 隔离。
- [ ] 页面 Stop/换用及通知 Stop 全链路幂等回收；后台导航不主动断开，Activity 重建不重启客户端。验证通知拒绝、服务启动失败、锁屏/Doze、断网与系统停止；不扩大 HFTP/Root 权限，不自动重连/重放。

## 4. 页面与验证

- [ ] 在终端增加 SSH 入口、主机列表、配置编辑/导入和实际目标标题。
- [ ] 完整呈现配置错误、等待、取消、指纹确认、认证、失败恢复、连接和断开状态。
- [ ] 使用当前主题及组件；覆盖 320×568、软键盘、横屏、840 dp 阅读列及大字，保留单输入与输出选择。
- [ ] Flutter 测试：AC2/3/4/5/6 的 UI/状态、配置夹具、旧会话隔离、秘密不进入历史、本机入口回归。
- [ ] 隔离 SSH 集成测试：AC1/2/4/5，测试端点不同端口/密钥、认证失败/取消、窗口变化、Ctrl-C 和资源关闭。
- [ ] 转发验证 AC3/R6：本地、固定目标远端与 SOCKS4/5 代理真实双向传输，多条/重复声明、IPv4/IPv6、端口冲突/服务器拒绝、ExitOnForwardFailure yes/no、目的端不可达、后台/重建不重复监听、Stop 后资源关闭；非 loopback 确认/拒绝/取消、IPv4/IPv6/通配/GatewayPorts、配置变更、旧确认隔离及远端实际范围未知用隔离夹具验证。
- [ ] 导入验证 AC7：取消/失败、引用缺失及冲突、外部文件修改、URI 权限撤销、重新导入；证明连接只读取已导入的有效私有配置。
- [ ] 凭据验证 AC9：当次/保存/重启、共享解锁/锁定/取消、目标及密钥身份/库代次错配、快捷失效回主口令、单条/SSH 全部忘记、多跳隔离、混合加密备份恢复及日志泄露检查；平台真实 Keystore 证据来自公共底座并做 SSH 集成，不用 JVM mock 声称设备通过。
- [ ] 获当前授权后用显式设备 serial 做无 Root SSH、IME/Tab/中文、尺寸、复制、断开、页面/后台及 App/Root 定向回归。没有授权或设备时记“未运行”。

## 验证命令与证据

本轮仅文档检查：链接、源码锚点、task 状态、JSON/JSONL 及 `git diff --check`。无需 App 构建或设备操作。

后续按变更层执行：

```bash
./hako flutter analyze
./hako flutter test
./hako bash -lc 'cd android && ./gradlew :app:lintRelease --console=plain'
```

在需要替换 release 产物且有授权时运行 `./hako current`。Python/native 测试及 SSH 夹具的具体命令在路线固定后补全，不能虚构现有 ssh 测试命令。设备检查先确认当前任务目标，再使用每条显式 `adb -s <当前授权目标>` 命令。

## 风险文件与审阅点

| 文件/责任 | 风险与检查 |
| --- | --- |
| lib/main.dart、lib/terminal_interaction.dart | 本机 PTY 回归、输入/历史、状态事件、IME 与键盘布局；应按职责提取 SSH 模块，避免继续堆集中状态。 |
| Android MainActivity / 独立 SSH 服务 / Pty / pty.c（按路线） | FD/PID/socket 所有权、跨线程退出、受管 argv/环境、认证秘密及生命周期。 |
| pubspec / Gradle / Portable manifest（按路线） | 依赖版本、SDK/ABI、离线启动、打包来源和许可证。 |
| 新 SSH 私有存储及 SAF 模块 | 原子保存、路径映射、导入取消/缺失、不覆盖无关用户内容、秘密日志与备份范围。 |

已拆公共认证与 SSH 接入，依赖写在各子 PRD/implement，先底座后集成。日后 implement/check 分派前加载真正的 spec/研究上下文，不派实现 agent 来完成未批准规划。

## 收尾与回退

- [ ] 同步 design/ssh-terminal.md、architecture、compatibility、dependencies、verification、changelog 及索引；严分计划、实现和实测。
- [ ] 对可见页面、权限和真实连接做 human-required 定向审阅，再按任务授权提交/归档。
- [ ] 依赖或客户端失败可回退 SSH 入口及 adapter；不回退用户的配置/密钥数据，不撤销其他任务改动。

## 统一模型更新的启动门槛

用户已明确统一秘密库，旧完整 SSH 审阅版本失效。先完成公共底座的主口令/便携库/统一会话/设备材料/迁移契约，Authenticator 与 SSH 分别接入；锁定共享策略已确认，SSH 完整受管文件备份已确认，管理操作再次手动输入及真实认证解密已确认，首版更换主口令已确认，关闭快捷方式也须本次口令认证解密已确认，剩余确定产品范围已整理；native 无明文磁盘视图/引用兼容探针必须通过，否则回规划，不静默落盘。全部 planning，无实施批准，未运行 task.py start。

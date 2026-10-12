# 第三方 Authenticator 规划入口

2026-10-09，planning / 已确定范围整理完成，待最终审阅。尚未实现 OTP、扫码、秘密库或系统解锁，未绑定/登录真实账号。

[任务 PRD](../.trellis/tasks/10-08-authenticator/prd.md)、[接入设计](../.trellis/tasks/10-08-authenticator/design.md)、[实施计划](../.trellis/tasks/10-08-authenticator/implement.md)、[规划检查](../.trellis/tasks/10-08-authenticator/check.md)。用户持续要求仅规划，未获实施批准。

## 用户已确定与公共责任

首版离线 TOTP、相机扫码/本机图片读码/手动添加标准服务条目、条目管理/代码/倒计时/复制，无云同步。用户选择自己设置的主口令、加密备份及导入覆盖、可开启关闭的设备系统认证快捷解锁，并明确要求该模型应用到 SSH，降低不同实现的理解/维护难度。

公共契约只维护在 [App 统一秘密库与认证](app-authentication.md)：一个主口令、一个类型化加密库、一套 KDF/持久化/设备包装/内存会话/设置和迁移流程。库内可保存 TOTP 种子及用户主动保存的 SSH 密码/私钥口令；Authenticator 是 OTP 业务调用方，SSH 是身份绑定/远端认证调用方，不各自实现另一套库或解锁流程。

```text
统一首次设置 / 主口令或生物快捷解锁 / 关闭快捷方式 / 加密迁移
                              ↓
                   App 统一秘密库及使用会话
                   ├─ Authenticator：TOTP 条目与代码
                   └─ SSH：匹配身份的保存凭据与认证
```

首次需要秘密的功能路由到公共设置/导入，再询问设备快捷解锁；有效统一会话中业务操作仍检查 consumer/条目/目标/代次。关闭快捷方式须本次手动输入主口令认证解密，成功后移除本设备包装材料并锁定，保留整个库，所有秘密业务回到主口令路径；普通信息/App/Root/HFTP 功能不因此新增口令墙。

导出先重新输入当前主口令认证解密，再生成统一主口令加密包；另一设备用来源口令完整验证、预览确认覆盖后提交；失败/取消保护旧库，成功清理旧会话/设备材料，新设备重新登记。不自动合并，设备生物/Keystore 材料不迁移。2026-10-09 已确认受管 SSH config/Include/私钥/关联公钥或证书/信任同包加密保护与恢复，导入先校验引用/身份/路径并单独审阅信任，不执行配置或绕过指纹变化检查。

## 可行性与标准兼容

验证码核心较小，主要工程量在公共秘密设施、条目/扫码、使用和恢复体验。TOTP 根据共享密钥/时间生成代码，可离线运行；服务对开放第三方 TOTP 的入口通常不按验证器品牌审批，这是基于标准及注册流程的推断，不是所有 MFA 的承诺。算法依据 [RFC 6238](https://www.rfc-editor.org/rfc/rfc6238)；仅实现 TOTP，不实现其他 OTP 类型。

服务二维码通常含 otpauth URI，来自 [Google Key URI Format](https://github.com/google/google-authenticator/wiki/Key-Uri-Format)，不是 RFC 6238 定义的注册协议。解析 Base32、issuer/label、algorithm/digits/period；相同密钥但参数不同会不匹配。历史页面的客户端忽略参数说明不能当作当前 Google App 实测。

| 服务/入口 | 官方声明 | 本任务边界 |
| --- | --- | --- |
| [GitHub TOTP](https://docs.github.com/en/authentication/securing-your-account-with-two-factor-authentication-2fa/configuring-two-factor-authentication) | 用户可选 TOTP app、扫码/手动；SHA-1/6 位/30 秒 | 标准互操作预期，未 ctOS 实测；GitHub Mobile 是另一机制 |
| [GitLab OTP](https://docs.gitlab.com/user/profile/account/two_factor_authentication/) | 支持多种 OTP app 与二维码/手动 | 外部 IdP/组织策略另核对 |
| [AWS IAM](https://docs.aws.amazon.com/IAM/latest/UserGuide/id_credentials_mfa.html) / [Identity Center](https://docs.aws.amazon.com/singlesignon/latest/userguide/mfa-types.html) | 虚拟验证器使用 TOTP，Identity Center 接受合规应用 | 不替代 FIDO/安全密钥或组织规则 |
| [Microsoft Entra OATH](https://learn.microsoft.com/en-us/entra/identity/authentication/concept-authentication-oath-tokens) | 第三方 TOTP 可用，不支持 HOTP | [管理员可禁用第三方令牌](https://learn.microsoft.com/en-us/entra/identity/authentication/how-to-mfa-manage-oath-tokens) |

所有服务结论来自官方资料及标准推断，没有 ctOS 登录结果。网站写“Google Authenticator”需检查实际密钥/URI/专用注册流程。[Microsoft 推送/数字匹配/免密](https://learn.microsoft.com/en-us/entra/identity/authentication/concept-authentication-authenticator-app)、Passkey/WebAuthn、短信/邮件及指定合规设备不由 TOTP 替代。

## 代码证据及业务边界

- pubspec.yaml 现为 Flutter/xterm；Manifest 未声明 Camera/生物认证，未找到自有 OTP/Keystore 实现。新增库/权限/许可证需后续审查。
- MainActivity.java:52 为宿主 MethodChannel，ToolFiles.java:20 为私有工具文件 broker；可参考交互但不能将工具共享库当秘密库。
- Manifest:14 allowBackup=false 及 data_extraction_rules.xml 排除只是配置，不是所有 ROM/迁移路径的实测保护。
- App Shell/Python 同 UID、Root/进程攻破和托管内存副本风险按公共权威契约记录；私有目录不是同 UID 秘密沙箱。
- TotpAdapter 在宿主的固定操作中读取自己的种子并生成短期代码，Flutter 不长期持有种子/数据密钥，不启动 Python/Shell 来算验证码。TOTP 计时展示需对时间/前后台变化重算，不用冻结倒计时代替当前代码。

2026-10-09 用户确认首版包含从手机图片读取二维码（AUTH-13）。使用系统选择器只读取用户明确选中的图片，在宿主离线有界解码，不申请全图库读取、不扫描整个图库、不自动保存种子图片或修改外部原图。仓库已有 `ToolFiles.java:98` 的 ACTION_OPEN_DOCUMENT 用户选文件入口，但二维码识别尚未实现；图片与相机共用标准 otpauth 校验和条目确认流程，识别结果不会自动提交、打开网址或执行命令。多二维码只列合规候选供明确选择，不自动整图批量加入；无有效码/不支持内容/损坏或超限有可恢复反馈，取消/失败不创建或覆盖条目。

种子/完整 otpauth URI 仅作为宿主受管短期添加请求，返回 UI 的确认投影不包含原始种子；不进入导航参数、日志/通用历史/诊断或明文缓存。图片选取/解码/预览的迟到结果绑定原请求和库代次，锁定/取消/库覆盖后不能提交；清理临时 URI 使用授权/内存图片/待添加秘密，不持久保留源授权用于扫描或同步。文件读入字节/解码像素/尺寸/候选数量/耗时设技术上限，先检查再分配；离线库/所需模型随 APK 提供，不依赖首次使用下载、上传识别或云接口。相机授权拒绝仍可用图片/手动路径，不阻塞普通功能。系统选文件 provider 能否在断网读取原图需分开说明，ctOS 不主动网络取图；用户外部原图仍由用户保管，导入不会使其自动成为加密副本。

首版已确定基础参数为 SHA-1/6 位/30 秒；algorithm/digits/period 明确解析和校验，不支持组合拒绝并提示，不静默按默认生成。其他算法/位数/周期扩展不作为本次必交付范围。按 RFC 6238 支持组合向量/时间窗口/前导零/2038 后时间验证。扫码库离线工作、图片读码已纳入，Google 批量迁移和其他验证器备份格式排除。

## 截图保护与验证码敏感复制已确认

仓库当前仅有普通复制入口：`lib/workbench/result_card.dart:32`、`lib/terminal_interaction.dart:404`；未发现 FLAG_SECURE 或专用敏感剪贴板处理，这些是实现现状，不能当作新增隐私规则已经实现。Android [敏感 Activity 保护](https://developer.android.com/security/fraud-prevention/activities) 提供 FLAG_SECURE 阻止系统截图/不安全显示及减少后台截图暴露，但不是 Root/同 UID 或所有 ROM 的安全保证。2026-10-09 用户已确认验证码/口令输入等秘密库敏感页面使用安全窗口、禁止系统截图/录屏并隐藏最近任务中的秘密，普通 ctOS 页面保留截图。统一生命周期及先保护后展示/移除秘密后释放规则只维护在公共 BASE-13；实施后逐平台验证路由切换/系统选择器/返回及后台预览，不能把安全标记状态泄漏到无关页面。

2026-10-10 用户撤销验证码剪贴板清理，取代 2026-10-09 默认 30 秒/可调尽力清理约定。TOTP adapter 只在有效会话/条目检查后响应用户主动复制当前时间码，公共 BASE-14/helper 附敏感标记；清理由用户自理。删除清理设置/复制 ID/跟踪/读取/监听/定时器/补清及相关竞态探针，不提供剪贴板清理入口。普通输出复制及后台库超时不变，具体职责见 [公共契约](app-authentication.md)。

## 入口与共同设置已确认

当前导航为概览/信息/工作台/终端（`lib/main.dart:720` 至各 NavigationDestination），工作台由 `lib/workbench.dart:14` 的 WorkbenchPage 管理现有工具入口，没有独立全局设置 tab。2026-10-09 用户确认 Authenticator 放工作台的独立入口，进入 Flutter/Android 宿主支持的验证器页面；公共秘密库管理集中到一页，从验证器/SSH 都可进入，管理主口令、快捷解锁、后台保留、验证码复制保留及加密导入导出。公共页面只实现一次，不将验证器作为 Python 脚本或普通工具共享库操作；入口已确认（公共 BASE-15），首次设置/导入/解锁完成回原业务，取消无半成品，导航不停止已建立 SSH 或替代管理授权。

## 依赖、验收与规划状态

先交付 [公共底座 task](../.trellis/tasks/10-08-app-authentication/prd.md)，再 TOTP 接入；不依赖 SSH 客户端完成。公共认证系统授权与统一库使用会话分别管理，业务目标检查逐次执行，库已解锁不自动授权任意秘密操作；2026-10-09 已确认前台跨功能共享、后台保留秒数可配置（默认 30、0 即锁）、锁屏/手动即锁与进程重启锁定，共用公共底座设置。

产品范围为离线 TOTP、相机/图片/手动添加和 ctOS 完整加密备份；用户已撤销 HOTP 与验证码清理，其他平台专有迁移仍排除。主口令管理/设备快捷/共同设置/跨页面会话/后台保留秒数/敏感页面保护/主动复制全部引用公共契约。验收覆盖支持组合的 RFC 6238 向量、断网及时间窗口/前后台、扫码无效/超限/取消、认证失败及完整恢复、两个消费者共用底座；真实服务绑定/登录需实施时另获账号授权，未测不标通过。平台/native 关卡按技术契约验证。公共底座已实施，TOTP 业务仍 planning，依底座验收接入；本业务没有设备或账号实测。

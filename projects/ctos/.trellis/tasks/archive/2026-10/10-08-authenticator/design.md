# Authenticator TOTP 接入设计

## 2026-10-13 已授权设备自动验收设计

用户追加“帮我安装、自动测试”，当前目标192.168.9.14:5555已核对AIO-3568J/Android11/API30/arm64、安全锁屏已设置但当前未锁；主包c704覆盖安装并读回一致。沿用opt-in `secretsFixture` 独立UID承载同一release Flutter/Main/Bridge，先禁止自身Root自动启动，拒绝非fixture包/共享UID或旧测试状态；主包真实库不进入夹具。

`TotpPlatformTest` 使用独立store/路径核验真实Android Bitmap多QR、guard/事件/密文及适用安全窗口/敏感复制；`TotpUiFlowTest` 通过真实Flutter语义控件手动加入公开RFC合成种子、确认/生成/管理/复制/锁定与前后台。真实图片路径显式opt-in，仅创建和选择自己UUID图，以实际系统provider授予的临时URI读取，结束按精确URI删除；不读取/遍历用户图库、清理剪贴板或输入系统凭据。设备动作与Gradle由主会话串行执行，真实相机服务0设备，权限/不可用恢复可测，但不能记硬件扫码通过。成功后精确清理自有图/授权/进程并卸载两个fresh fixture包；失败先核明自有未决状态再回收，不对生产包清数据。

实际执行边界：初轮自动审批拒绝全局剪贴板覆盖后，现场执行先关闭copy；用户随后明确“允许覆盖，完成复制测试”，仅选原生copy单项并设置true，实际6.616秒通过，不读原内容/保存/监听/恢复/清空；`ctosTotpClipboardCopy=true` 仅可在用户明确批准该副作用后使用。同UID自有MediaStore图片可以验证真实系统选图/离线解码/明确单选与源图不改，不能证明跨UID临时授权强制或撤销。失败检查只认证精确run UUID合成密文，不改变库，不读取剪贴板或生产数据。

2026-10-13：用户确认复用现有 task、先 TOTP 后 SSH，恢复本地实施。业务契约见 [领域规划](../../../../../design/authenticator.md) 和 [PRD](prd.md)，统一秘密契约只在 [公共底座](../../../../../design/app-authentication.md) 维护。

Flutter 拥有工作台验证器入口、相机/系统选图交互、手动添加、条目管理/代码/倒计时；Android TotpAdapter 校验标准 TOTP 参数、管理种子并生成短期代码。支持已确定基础 SHA-1/6 位/30 秒，不支持类型/参数明确拒绝。AppSecrets/AppAuthentication 拥有 KDF/密文/设备材料/库代次/使用会话，不由 TOTP 重复实现。

固定请求为条目操作 → 公共有效会话/consumer/操作/entryId/代次检查 → 宿主种子操作或时间码生成 → 返回展示信息/短期代码。种子/数据密钥不返回 Flutter，锁定取消旧请求并清理代码；前台有效会话跨功能共享，后台/锁屏/重启规则只引用公共契约。

图片通过系统选择器读所选 URI，宿主有界离线解码并共用相机标准 URI 校验；返回无种子确认投影，用户明确选择候选后再次检查会话/请求/代次才提交。取消/锁定/换代/迟到不采纳，不执行码内容；字节/像素/尺寸/候选/耗时有界，临时内存和授权回收，原图不改、不扫未选图片、不创建种子明文缓存。扫码实现/必要模型随 APK，拒绝相机不妨碍图片/手动。

所有初始化/解锁/设置/加密导入导出接公共 BASE-01..15。工作台业务页面和 SSH 都进入唯一秘密库设置；返回原业务、取消无半成品，导航不停止已建立 SSH。TOTP 删除仅处理自己的条目，完整加密恢复按公共来源验证/内容校验/预览覆盖事务，SSH 文件/引用/信任交由 SSH adapter 校验。

验证码和口令等敏感呈现接 BASE-13 安全窗口 owner/路由生命周期，普通页面恢复；主动复制先验证并生成当前时间码，接 BASE-14 敏感标记。2026-10-10 用户撤销验证码清理，删除清理设置/复制 ID/跟踪/读取/监听/定时器/平台竞态探针，清理由用户自行处理；目标 Android/ROM 主动复制行为仍须实测。

已审阅公共合同与 API30 凭证/真实空库管理证据后按本轮批准接入 TOTP，SSH 客户端不是 TOTP 的依赖；未测公共平台与混合业务联合关卡保留开放，底座不归档。算法向量可独立准备；共同设置/设备行为证据可引用，业务仍做集成验收。HOTP、其他平台迁移、云同步及其他非 TOTP 功能均不包含。回退关闭验证器入口，保留混合密文库及 SSH 数据，不免验证或删用户原图。

## 2026-10-13 接入与可验证边界

- 复用现有加密 manifest 和 TotpVaultAdapter，增加只含自身条目信息/当前短期码的类型化列表与重命名；不导出种子，不用普通工具/Python/Shell，不另建秘密设施。列表不得包含 SSH 数据；逐次检查会话、代次、期限和最终发布/持久效果。
- 手动、相机、图片共用严格 otpauth/Base32 校验，仅 SHA-1/6 位/30 秒；重复关键参数、issuer 冲突、非法编码、未知影响算法的参数及 HOTP/专有码明确拒绝。新条目分配宿主 UUID，不允许添加流程覆盖既有条目；重命名保留种子和身份，删除明确确认。
- 采用 APK 内固定版本 ZXing 离线解码；相机使用 App 自有非 exported 扫码 Activity/视图，权限按用户点击请求，图片使用系统单图选择器且不持久授权。不得调用外部扫码 App、缓存 barcode 图片或在 Intent 中传播原始种子/URI。扫码结果只入宿主短期票据，UI 仅看无种子确认投影，选择后再请求提交。
- 技术上限：URI 8192 字符、Base32 解码种子沿用10..128字节；图片10MiB、8192单边、1600万像素；最多16个候选，120秒单调预览期限；相机帧有界且解析不阻塞 UI，不随正常切页轮询相机。超限/空白/损坏/权限拒绝反馈可恢复，不截取秘密生成其他代码。共同库仍遵守1024条上限。
- 所有预览绑定请求、库快照/会话代次和截止时间；锁定、取消、换代、超时、销毁清理短期种子，迟到结果不发布/提交。后台系统选图返回不使旧票据重新有效，0秒锁定需重新解锁和选取。
- UI Pro Max 的 Flutter 搜索支持 Semantics/大字/主题字级；通用设计搜索推荐单列及高对比。沿用 ctOS Theme/布局/Card/Material 图标，不引入另一套主题或字体：账号→6位码→剩余秒数，48dp复制及更多操作；添加入口分手动/相机/图片，无效状态给就地恢复。320dp/2倍字/键盘下完整可滚动；倒计时用文本和进度，不只靠颜色。
- 进入业务前先取得公共窗口 owner，离开敏感路由或后台立即移除 UI 代码；回前台按宿主当前时间重算。系统时间变化不能冻结旧码，用户复制由宿主再生成当前码并标敏感，无剪贴板清理。主口令/备份仅进入现有设置/安全。
- 实施以本地 JVM/Flutter/Android lint 与 arm64构建验签为本轮交付；相机硬件、SAF真实图片、敏感呈现及真实服务 MFA 分别列为待授权实测。混合 SSH 恢复生产 provider 未挂载继续拒绝，不能用空 validator 绕过。

依据：[RFC6238](https://www.rfc-editor.org/rfc/rfc6238)、[Key URI Format](https://github.com/google/google-authenticator/wiki/Key-Uri-Format)、[ZXing](https://github.com/zxing/zxing)、[Android Embedded](https://github.com/journeyapps/zxing-android-embedded)。2026-10-13 已核对官方源码/文档；版本与许可证在接入和实际解析依赖后固定记录，不由文档浏览推定设备通过。

### 宿主与 Flutter 接入合同

经两侧实施协调，固定在 `ctos/native` 下的 `secrets.totp*`。除取消外通过 `SecretRequest` 请求，管理写入继续使用公共4096个一次ID预算；持续只读列表有独立128个滚动去重集和逐次原生受限能力，不耗尽管理预算。滚动淘汰后再用旧读取ID只允许重新通过当前会话认证读取，不能重放管理写入。事件 `ctos/secrets` 只返回 `epoch`/`unlocked`/`foreground` 元数据，不返回秘密；流缺失、损坏或断开时保持不展示代码，直到重新获得有效事件。

| 方法 | 参数/结果 |
| --- | --- |
| `totpList` | 无业务参数；`entries[{entryId,identity,label,code}]` 与宿主 `unixSeconds`，仅当前TOTP，无种子/SSH |
| `totpManualPreview` | 用户手输 `issuer/account/secret` 或 `uri`；只返回无种子确认投影 |
| `totpImagePreview` / `totpCameraPreview` | 用户主动选择/扫码；同一 `previewId`、最多16个 `candidates[{candidateId,identity,label,algorithm,digits,period}]` 与0..16整数 `invalidCodes` |
| `totpCommit` | `previewId/candidateId`；明确确认后返回新 `entryId` |
| `totpCancel` | `previewId`；清理仅该预览，无通用秘密读取 |
| `totpRename` / `totpDelete` | `entryId`及重命名 `label`；返回公共状态，种子/身份及其他命名空间保持 |
| `totpCopy` | `entryId`；宿主最终效果处生成当前码并敏感复制，仅返回 `copied:true` |

投影按整批构造，避免每个条目各自完整解密库；当前码/倒计时随墙钟重新取得，后台/路由覆盖/事件先清空旧码再恢复新鲜读取。事件不使会话解锁、不代替实际读取和提交边界的权限核验。剪贴板实际写入仍有最终会话/取消检查，不只是生成字符串前检查。

相机以 ctOS 自有 Activity 的实际 `onStart/onStop` 可见性承接前台；仅当前明确启动的相机请求可把 Main 的前台转换从 pause 延至 stop，stop时若相机尚未实际可见则按原后台期限处理，无定时“保活”例外。Main业务桥始终仍需自身resumed；Home/锁屏/取消仍失效，0秒不能人为延长。外部图片选择器按既有后台规则处理，过期后不得恢复旧请求；图片URI及短期权限由独立非exported读取Activity拥有，结束后退场，不向Flutter或父Activity传播URI/种子。超时/取消可以先关闭读取UI，晚到解码不得采用，不对任意provider的阻塞I/O作绝对完成期限承诺。

读取快照在worker核验磁盘，再取得内容revision票据；真实存储效果前失效旧票据。主线程发布只持轻量publication/session锁、检查代次/期限/revision/请求，不读文件、不取得core/preview的工作锁。界面遇到跨30秒窗口、墙钟跳变或未完成刷新应先移除过期码；剪贴板发布若生成后跨窗口则拒绝重试，不能复制旧码。

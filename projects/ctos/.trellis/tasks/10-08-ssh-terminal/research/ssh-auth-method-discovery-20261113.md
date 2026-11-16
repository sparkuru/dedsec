# Research: 自动发现 SSH 首认证方式与服务器后续问题

- Query: 确认当前实现是否在首个凭据提交前发现服务器认证方法，以及无需预先勾选地显示真实后续问题的最小安全路径。
- Scope: mixed；当前 native/Java、相关规范及 SSH RFC。未操作设备、服务器或用户数据。
- Date: 2026-11-13

## Findings

### 现状及范围

- 用户提供的录像经主会话查看：选择 FileZilla 的交互式登录但服务器仅提供公钥时失败；改选私钥后成功。录像未出现服务器发问，不能用它证明 KBI、自动发现或多因素通过。
- `android/app/src/main/cpp/ssh_native_client.c:1587-1622` 仅请求并接收 `ssh-userauth` 服务；源码搜索没有发送 USERAUTH `none` 或其他首方法之前的服务端方法发现。服务就绪不代表已知服务端可接受方法。
- `SshPasswordConnector.java:337-355` 在 native open/KEX 前冻结单一身份与可选后续许可，`:364-390` 服务就绪后立即按已选身份发密码、私钥或独立 KBI。密码请求在 `ssh_native_client.c:1031-1054` 调用一次选定凭据读取后发出；私钥也以选定 public key/签名路径开始。因此不能声称当前已自动识别 KBI-only 服务器，或在秘密交付之前获取方法列表。
- `ssh_native_client.c:1352-1364` 验证 USERAUTH FAILURE 的有界方法列表；仅真实 `partial`、阶段1且预留续接能力时保存列表，普通 REJECTED 的列表不保留。`:1200-1233` 的现有续接要求选定首身份、真实 PARTIAL、该次方法列表精确包含 `keyboard-interactive`、已消费的选定凭据及原计划收据。`:1236-1250` 的独立 KBI 首方法要求选择 kind3。`SshNativeSession.java:874-930` 也分别把独立首方法与 PARTIAL 后受证明的 stage2 区分开。
- 配置生效方法从实际 OpenSSH Options 求值：`ssh_config_eval.c:975-988` 包括三种方法的启用/成员、首方法到 KBI 的顺序。`SshPasswordConnector.java:444-446,473-488,524-539,602-614` 对现有显式续接在首方法之前严格检查链式政策。所以把既有 checkbox 默认改为 true 会让 KBI 禁用但首方法可成功的配置提前失败。

### 本轮最小路径：已选首方法后的自动问题

主会话指定本轮聚焦：继续现有用户选定的密码/私钥首方法；只在首方法实际 PARTIAL、配置许可且服务器明确提供 KBI 时，不经复选框在原连接启动现有 KBI，向用户展示真实 INFO_REQUEST 并等待用户输入或取消。独立 KBI 首方法保留现有能力；不新增 `none`、任意方法回退或错误密码自动重试。

新增有界自动续接意图时，可在 Java 将「用户允许自动处理问题」与「当前 config 实际允许此链」分开：普通单因素成功不受 KBI 禁用或顺序的影响；在政策允许时沿用既有 CSASv3/CSAP、原 owner/role/KEX、一次性首凭据消费、原期限与 challenge/mailbox。旧显式 `continueKeyboardInteractive=true` 的严格配置检查及未传字段的旧行为应保持，或在新的类型化合同中明确兼容；不能通过全局反转旧布尔掩盖差异。PARTIAL 但 KBI 不可用/未提供时，不推断一定有验证码，给出真实可恢复错误且无 shell；普通 REJECTED 不启动其他认证。

Flutter 原 `_keyboardCapable` 影响前台会话事件与挑战接收（`lib/ssh/password_connect_page.dart:195-200,442-470,1206-1217,1435-1443,1491-1502`）。去除勾选框必须覆盖等待中的 challenge/锁库/后台/迟到成功，不能让无 KBI 的单因素成功仅因新增的交互事件握手而变得不可用。用户输入需始终来自当前服务器问题；不从密码、TOTP、剪贴板或旧连接自动填答。

### 若将来要求自动识别 KBI-only 首方法

- 按 [RFC 4252 §5.2](https://www.rfc-editor.org/rfc/rfc4252.html#section-5.2)，经过可信 host/KEX 后可在同一 `ssh-userauth` 会话发一次不携带密码/签名的 `none`，取得服务器返回的方法；这需要新 native 状态机/有界列表和 JNI/Java 接口，目前不存在。用户名仍会发送；先前显式保存密码和本地私钥/口令检视也可能早于该探测，不能称作所有本地秘密处理都在探测之后。
- `none` 也可收到 `USERAUTH_SUCCESS`，服务器随后已完成认证；不得继续发其他认证请求，也不得未经产品授权将其冒充所选密码/私钥成功。`none` FAILURE 可携带空列表，方法列表也不保证列出的每个方法实际可用；KBI 名称出现本身不是实际问题，只有后续真实 INFO_REQUEST 才能显示问题。
- 当前 `ssh_native_client.c:214-276,948-974,1236-1250` 和 `SshAuthenticationSelection.java` 将原 kind、role/nonce/binding、stage 与 secret 绑定；从原密码/私钥意图切为 KBI 首方法需先设计有界可撤销的 discovery→首方法证明，绑定同一 host/KEX/owner/username、原配置及 deadline。不能把 stage2 的“首方法已 PARTIAL”证明伪造给首方法，也不能让 Dart 伪造方法列表或借此读取密码。
- 这属于对当前首认证能力和 native/JNI/Java 合同的进一步扩展：需审计 config 的 KBI 启用/偏好与初始身份意图、无认证成功和兼容性，考虑额外往返、限速/方法发现失败，设计只读探测及严格 owner/epoch/取消测试。它不能通过先提交一个错误密码再看方法列表替代。

### Tests required for current increment

1. 密码和私钥分别覆盖手动/config：首方法 SUCCESS 且 KBI 被配置禁用或排序不允许时仍可连接；原显式严格选项保持旧策略。
2. 真实同 socket 的密码/私钥 PARTIAL→服务器实际列出 KBI→真实 challenge/显式回答→SUCCESS/PTY，选定凭据只消费一次；包含换键确认后同连接续接。
3. REJECTED、PARTIAL 但 KBI 缺失/禁止、第二方法拒绝/再 PARTIAL、错误/过期问题、取消、后台、锁库、短 deadline 和迟到结果都不能产生额外凭据或 shell。
4. 保留直接 KBI 首方法、零/多问题及多轮；Flutter 保护路由、真实字体/窗口尺寸和事件先后顺序分别测试；区分 native 合成协议、JVM 实际秘密库、真实 Android JNI/设备与人工体验。

## Files and related specs

- `android/app/src/main/cpp/ssh_native_client.c`：实际服务、第一方法、FAILURE 列表与同连接阶段转换。
- `android/app/src/main/java/im/majo/ctos/SshPasswordConnector.java` / `SshNativeSession.java`：有效配置、Java owner、选定身份与阶段证明。
- `android/app/src/main/java/im/majo/ctos/SshAuthenticationSelection.java`：原用户名/凭据和可选后续能力的不可变 CSAS wire。
- `android/app/src/main/cpp/ssh_config_eval.c`：实际方法启用和偏好顺序。
- `android/app/src/test/cpp/ssh_native_client_test.c:5336-5415,5445-5517`：现有真实加密协议 PARTIAL/KBI 与换键组合。
- `.trellis/spec/backend/ssh-mfa-authentication.md`、`.trellis/spec/backend/ssh-keyboard-interactive.md`、`.trellis/spec/backend/ssh-host-trust-management.md`：历史严格路径、独立问题路径和本次换键信任约束；用户新决定落地后需更新相应合同。

## External references

- [RFC 4252 §5.1–5.3](https://www.rfc-editor.org/rfc/rfc4252.html#section-5.1)：FAILURE 列表和 partial 含义、`none` 探测、最终 USERAUTH_SUCCESS。
- [RFC 4256](https://www.rfc-editor.org/rfc/rfc4256.html)：服务器定义的 KBI 提问/回答。

## Caveats / Not Found

- 当前源码未发现首方法前的生产 `none` 请求/方法发现；这不等于 SSH 标准或其他客户端不能发现。
- 本次按主会话指示不实现 `none`；因此本轮不会自动把用户选定的密码/私钥首方法切成 KBI-only 首方法。若用户期望该能力，须明确记为仍未覆盖，保留独立 KBI 入口，不能宣传“所有交互式首方法已自动识别”。
- 本研究未运行测试或修改产品代码；录像不能作为真实 SSH 协议包或 KBI 认证成功的证据。

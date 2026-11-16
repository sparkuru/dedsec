# Research: 配置主机选择与 SSH 表单操作路径

- Query: 用户反馈选择配置主机时提前询问认证、表单再次询问认证、目标区及指纹弹窗排版不佳；定位当前实现、重排边界和验证点。
- Scope: internal；源码、现有规范、widget 测试和用户提供截图。未操作设备、未读取用户秘密库、未运行产品测试。
- Date: 2026-11-13

## Findings

### 文件与职责

| 文件 | 职责 |
| --- | --- |
| `lib/ssh/library_page.dart` | 受保护的资料页、配置主机/私钥列表及两类选择动作 |
| `lib/ssh/library_api.dart` | `SshConfigHost`/`SshIdentity` 有界类型和公开元数据 |
| `lib/ssh/password_connect_page.dart` | 连接表单、目标/认证/私钥状态、保护生命周期及换键对话框 |
| `lib/ui/ctos_components.dart` | `CtosReadingColumn`，目前内容宽度上限为 840 dp |
| `lib/ui/ctos_theme.dart` | 应用的 Material 3 深色主题及控件样式 |
| `test/ssh_key_config_test.dart` | 配置 Host 选择、绑定、私钥选择及返回行为的 widget 测试 |
| `test/ssh_password_connect_test.dart` | 连接表单、换键确认、生命周期及可访问性测试 |
| `test/ssh_keyboard_interactive_test.dart` | 直接 KBI、config KBI/密码及挑战交互测试 |

### 当前多次选择的代码原因

- 直接入口 `SshPasswordConnectPage._selectConfigHost` 调用 `_openSshLibrary(hostsOnly: true)`（`lib/ssh/password_connect_page.dart:490-512,572-586`）。资料管理入口也会打开同一个 `SshLibraryPage`（`:660-666,2214-2220`）。
- 主机列表的点击统一进入 `_selectHost`（`lib/ssh/library_page.dart:1295-1367`）；无论是否为 `hostsOnly`，该函数都先创建“选择 … 的认证方式”弹窗（`:924-943`），同一列依次放密码按钮、直接服务器交互式认证按钮、每把私钥的完整详情和“使用此私钥”按钮（`:961-1045`）。弹窗选定后才返回连接页（`:1068-1074`）。这与用户截图中选择目标后再次被迫处理认证的流程一致。
- 回到连接页后仍无条件显示 `_authenticationSelector()`（`lib/ssh/password_connect_page.dart:1965-1991,2335-2349`）；它再次打开包含密码、私钥、服务器交互式认证三项的 radio 弹窗（`:70-75,778-829,2450-2475`）。因此目前存在用户所指出的重复与层级混合。
- `SshLibrarySelection` 的 `keyboardInteractive`、`password` 默认都为 false（`lib/ssh/library_page.dart:12-22`），而 `_selectConfigHost` 将两者均 false 解读为私钥模式（`lib/ssh/password_connect_page.dart:599-620`）。只把当前 `_selectHost` 改为返回 `SshLibrarySelection(host: host)` 会意外选中私钥模式但没有 key；应显式区分“只选择目标”和“已选择认证/私钥”，并明确连接页状态转换。
- `SshConfigHost` 本身只有 alias、binding、host、port、username、keyIds、可选提示和错误（`lib/ssh/library_api.dart:37-98`），没有公开生效认证方式/顺序。不要根据 `IdentityFile` hint 或是否只有一把 key 自动推断模式、自动选 key 或忽略后端配置策略。实际连接分派仍使用 config 的 alias/binding，见 `lib/ssh/password_connect_page.dart:1256-1315`。

### 表单、返回和生命周期要点

- 连接页当前固定使用 840 dp `CtosReadingColumn` 和滚动表单（`lib/ui/ctos_components.dart:40-55`、`lib/ssh/password_connect_page.dart:2263-2296`）。目标区依次显示“选择/更换配置主机”按钮、已选 alias 的只读框、切回手动按钮、只读主机/端口（`:2297-2335`，具体输入见 `:1914-1963`）；选配置后把认证区和连接按钮进一步推到首屏以下。用户截图可证这些控件堆叠，不能单凭截图断言所有尺寸都溢出。
- 连接页的密码/私钥/KBI 仍各有不同字段；私钥模式才显示已选 key、选择/更换 key 和可选口令（`lib/ssh/password_connect_page.dart:1994-2078`）。为本次体验把 key 选择移到认证区时，必须保留单 key 也由用户明确选、无 key 时可导入、加密 key 的受保护口令/清除和两种连接路径，不应仅隐藏所有 key 选择动作。
- 打开受保护资料页会终止 idle 读取、清除密码及私钥口令，防止页面切换复用秘密（`lib/ssh/password_connect_page.dart:490-512`）；资料页自身对前台/当前路由、revision、stream health 做检查，并在离开或失效时取消原请求（`lib/ssh/library_page.dart:66-80,89-120,132-143,168-187,196-220`）。重构不可让返回值跨后台、覆盖、销毁或旧 revision 复活。
- 直接 Host 选择回来时，`_revalidateLibrarySelection` 读取新快照，并逐项校验 alias/binding/host/port/username 以及所选 key 的 ID/path/algorithm/fingerprint/encrypted/Host keyIds（`lib/ssh/password_connect_page.dart:515-569`）。取消后也重验既有公开选择、不回填密码/口令（`:624-645`）。这些是确定性的已有防护，不是多余的界面点击。
- **需特别处理的现存路径缺口**：选择私钥的 `_manageIdentities(keysOnly: true)` 调用 `_loadDefaults`（`lib/ssh/password_connect_page.dart:660-675`），而后者会清掉 `_configHost`、`_identityMode` 和已有 key（`:273-300`）。若选择页取消，当前函数只恢复文本字段，未恢复原配置/私钥认证状态（`:676-725`），于是界面可能回到手动密码模式；它不会因此自行连接，但用户原选择已丢失。当前测试 `test/ssh_key_config_test.dart:586-656`覆盖的是取消 **Host** 选择而不是取消 key-only 选择。把私钥选择移回表单时应验证取消仍保留经重验的配置、认证方式和公开 key，秘密仍清除，不能静默切为另一方式。
- 同一 key-only 返回路径在 `lib/ssh/password_connect_page.dart:676-695` 复核 Host 时只比较 alias 查询结果、host/port/username 和 keyId membership；它没有比较原 `binding`，也没有像 `_revalidateLibrarySelection` 那样比较已选 key 的 path/algorithm/fingerprint/encrypted。若同一目标的 config/规则在选择期间变化，UI 可能直接采用新 binding；若快照中的相同 keyId 元数据变化，UI 也会持有旧选择对象。后端仍有独立 guard，不能据此声称已发生秘密泄漏，但 UI 不应悄然改变所选配置事实。建议复用统一的精确重验并测试同目标 binding 变更、key 替换、取消、后台、锁定及迟到返回。
- 缺省 username 允许用户填写；已配置 username、host 和 port 为只读（`lib/ssh/password_connect_page.dart:1930-1957,1994-1999`），选 key 期间必须保留缺省 User 的显式填写（`:704-711`）并让最终 RPC 继续携带受验证 alias/binding，不能降级为手动目标。

### 指纹弹窗与安全保留项

- 用户第 6 张截图显示当前同一弹窗内有本次指纹、保存的指纹和可展开来源。这只能证明弹窗已显示；不能从截图推断用户已点击信任、同连接写入/认证/终端成功。第 7 张截图显示另一操作停在 `SSH_AUTH_PARTIAL` 提示，不能推断其他认证分支通过。
- `_ChangedHostKeyDialog` 是自定义 `AlertDialog` 内容列（`lib/ssh/password_connect_page.dart:2559-2579,2676-2776`）；来源用未专门定制形状的 `ExpansionTile`（`:2754-2775`），来源和新旧算法默认分成额外正文行，截图展开状态存在明显分隔线和紧贴按钮的问题。布局调整宜围绕层级、留白和一致性评估，不能以隐藏或省略指纹代替排版。
- 已有实现只展示“本次收到”的新指纹，明确动作才允许决定；旧记录多达 128 条、可滚动，内容溢出时必须能查看旧指纹并在末尾可见后才能确认；键盘、辅助功能滚动、route coverage、过期、错误及伪造结果保护不应因视觉调整被删除（`lib/ssh/password_connect_page.dart:2574-2667,2699-2794`）。现有 widget 验证见 `test/ssh_password_connect_test.dart:1269-1317,1669-1777`。实际服务器签名/来源单次 CAS/相同连接等约束以 `.trellis/spec/backend/ssh-host-trust-management.md` 为准。

### 与现有测试的关联

- `test/ssh_key_config_test.dart:461-495,531-584` 目前明确要求 Host 列表点击后弹出认证/key 选择，这些断言需要按新产品流程重写，不能保留重复询问只是为了旧测试通过。
- 对照保留 `test/ssh_key_config_test.dart:586-685` 的 Host picker 取消、配置 binding/所选 key 漂移与受保护回滚；新增 **key-only picker 取消/重新选择** 的 config/认证状态与同 tuple 新 binding 漂移测试。
- 仍验证无 config 的导入／有 config 但无 Host 的编辑入口（`test/ssh_key_config_test.dart:687-710`）、无 key 主机可先选定、单/多 key 明确选择、原 hint 仅供参考（`:461-528`）、多个 Host 各自正确 alias/binding/keyId（`:1266-1340`）、后端 stale binding 拒绝及无静默密码 fallback（`:1387-1413`）。现有独立 config KBI 测试 `test/ssh_keyboard_interactive_test.dart:1011-1017` 也依赖旧弹窗，需随用户确定的新流程迁移而非删除直接 KBI 能力。
- 保持实际 view 指标的 320×568、568×320、宽屏及 2× 字体、键盘 inset、真实字体截图的可见/可达验证；原来某些旧 key picker 测试仅使用 `setSurfaceSize`（`test/ssh_key_config_test.dart:714-735,783-813`），截图回归应同时控制 view 尺寸和设备像素比，避免预览指标不一致。

## Related specs

- `.trellis/spec/frontend/component-guidelines.md`：Material 3，语义主题颜色、840 dp 阅读列、48 dp 控件、窄屏/大字体和真实字体视觉检查。
- `.trellis/spec/frontend/terminal-interaction.md`：表单、保护会话和换键确认；其中 2026-11-02 配置选择及 2026-11-04 默认关闭 KBI 的规定属于既有用户决定。若用户现在明确改变，应由主会话更新对应合同，而不是声称旧规则原本如此。
- `.trellis/spec/backend/ssh-config-identity-selection.md` 和 `ssh-key-config-connect.md`：config 为实际求值的权威，IdentityFile 是非授权提示，key 显式选、binding 与后端最终校验不可移除。

## External references

无。本次结论来自项目实现、规范、测试和用户提供截图；未查阅外网。

## Caveats / Not Found

- 仅研究，未修改代码、设备或依赖；关于重排的具体视觉方案及服务器要求额外认证时如何呈现，应由主会话结合用户偏好决定。
- 第 6 张用户截图显示的换键弹窗不证明已完成信任写入或成功认证；第 7 张仅证明屏幕上出现后续认证提示，不推断服务端请求/选择前后的所有过程。
- 本报告不论证 KBI 串联默认行为、服务端方法列表或配置顺序；它们由独立认证研究负责。

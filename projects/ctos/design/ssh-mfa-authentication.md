# SSH 密码／私钥后继续交互式认证

日期：2026-11-06。状态：**本地及11-04指定板隔离验收通过；正常418f6d13…已正式保留数据更新并启动，人工体验待反馈**。
沿原[1008父任务](../.trellis/tasks/10-08-ssh-terminal/prd.md)推进R7/AC5，
不新建任务。[独立KBI](ssh-keyboard-interactive.md)已验收提交为`bc933b5`，
本页约定下一步的方法串联，不改变该增量的已验收范围。

11-05另获正式保留数据更新及启动授权，当日因目标不可达停止；11-06用户报告恢复并要求
继续，已实际安装／核对新APK及保留身份并启动。[正式交付](verification.md#20261106-ssh-mfa-formal-install)。
人工体验／提交／完整父AC仍开放，历史连接失败证据保留。

## 目标与已批准范围

使要求“私钥或密码通过后，再回答服务器问题”的SSH服务器可以完成登录。
手动目标和config主机入口均覆盖，复用原私钥选择、密码／私钥口令保存及受保护问题表单。

在密码、私钥方式下增加默认关闭的“按服务器要求继续交互式认证”选项，说明它允许在
第一种方法部分成功后继续回答问题；不增加两套重复认证表单。独立KBI入口保持。
只有选中该选项、第一方法实际返回partial success、服务器后续方法列表含
keyboard-interactive，且实际config允许时，才继续第二方法。
第一方法直接返回完整SUCCESS时可按正常成功连接；该选项是允许服务器要求的后续认证，
不保证或强制服务器实施第二因素。第一方法拒绝时失败，不尝试其他密码或密钥。

本轮只做password→keyboard-interactive和publickey→keyboard-interactive两条链。
KBI可继续沿既有合同包含零／多提示和多轮；第二方法仍partial或要求其他方法时明确失败，
不循环、不重试第一方法、不转为任意方法协商。问题回答仅当次，不读取保存密码、TOTP
或剪贴板，不将回答保存为账号凭据。

跳板、转发、证书、其他链顺序、任意多方法协商、完整SSH恢复及目录／路径适配继续在各自范围内。
用户对本页方案回复“开始”，批准本地实施、合成测试与构建／独立检查；
随后对明确隔离操作问题另回复“授权”，覆盖指定192.168.9.14:5555上已审阅两隔离包／
合成服务／实际方法及精确清理；已实际10.415秒通过并清理，不包含正式安装、真实账号或系统设置更改。

## 当前源码证据

- `android/app/src/main/cpp/ssh_native_client.c:1254`解析FAILURE的方法列表和partial，
  但列表未保留，`:1282`统一进入AUTH_FINISHED；`:1133`和`:1346`要求认证未开始，
  `:2100`阻止完成后的普通step。不能用再次调用begin方法实现正确串联。
- `android/app/src/main/java/im/majo/ctos/SshNativeSession.java:736`及`:863`只允许从
  SERVICE_READY发起方法；各结果查询将partial也置为FINISHED。
- `SshNativeCallbackAdapter.java:598`的选择在首次native owner领取前冻结，不能在已有
  transport上直接改写为不同方法；阶段切换需要与原选择证明一致的受限授权。
- `SshPasswordConnector.java:310`–`:356`实现三种独立方法，任一非SUCCESS结果失败。
  同文件`:359`之后才执行认证材料退役和会话交接，这个最终交接边界必须保留。
- `android/app/src/main/cpp/ssh_config_eval.c:964`–`:968`目前导出方法是否允许，
  未导出PreferredAuthentications的顺序。串联需要实际有效顺序的可消费信息，不能只看两个bool。
- `lib/ssh/password_connect_page.dart:59`只有三种单方法选择；新增选项应沿用刚验收的
  单选择框与下方说明，不恢复冗长的认证方式列表。

协议依据：[RFC4252 §5.1](https://www.rfc-editor.org/rfc/rfc4252.html#section-5.1)
区分“该方法已通过但仍需认证”和最终USERAUTH_SUCCESS；方法列表仅说明服务器允许继续的
方法，不授予客户端新的秘密读取权限。[RFC4256 §3](https://www.rfc-editor.org/rfc/rfc4256.html#section-3)
规定KBI交互；原多轮不是多个SSH方法的串联。上述协议原文已于本轮查阅，运行结果仍未验证。

## 技术设计

### 同一连接的不可变计划

连接启动时冻结实际目标、用户名、第一方法／所选身份以及是否允许后续KBI。
后续阶段只能消费该计划；保留同一attempt、owner、role、transport、host/KEX证明和原deadline，
不能重新TCP连接以冒充第二因素，也不能新建角色或续期来绕过原失效。
现有单方法计划作为不允许后续KBI的特例；库格式与现有持久资料无需迁移。

### Native与Java的阶段切换

保留有界服务器方法列表与真实partial结果，将“本方法完成”与“全部认证完成”分开。
只增加第一方法PARTIAL到所批准KBI的单次切换。每阶段独立绑定选择证明、序号及秘密消费；
旧阶段回调、挑战和选择收据不能授权新阶段，已释放的密码／私钥材料不重读。
不得开放通用的任意selection替换接口或放宽现有单方法selected-credential一次消费规则。

设计细化时冻结版本化计划／结果wire和阶段收据，并同时更新C、JNI、Java消费者与测试。
不得仅删除AUTH_FINISHED或SERVICE_READY检查。packet60仍按当前真实方法解释：
密码阶段是换密请求，KBI阶段才是问题；换密仍不在本次范围。
只有最终USERAUTH_SUCCESS及既有PTY/shell成功才交接服务；partial阶段无终端权。

### Config、界面与生命周期

由固定native Options导出受支持的实际方法顺序，显式禁用任一所需方法或显式顺序与所选
链冲突时给出可恢复诊断，不静默重排或忽略。未设置PreferredAuthentications时使用本次
显式选择的链；不据此提供任意自动方法回退。

公共通道传递typed continuation选项；缺省false，非法组合在建立连接和秘密交付前拒绝。
KBI沿用现有问题／回答限额、严格UTF8、逐轮票据和保护路由；问题绑定还须覆盖当前方法阶段。
原60秒认证总期限不续期。取消、Back、后台、锁库／锁屏、事件流失败与强制离页结束整条
待认证链，擦除owned秘密并等待原清理回执。

RAM重连仅保留公开目标、所选key ID／config alias及该选项，不保留回答、阶段进度、旧证明
或票据；重连重新解锁／求值／信任核验并从第一方法开始。

## 验收

- MFA1：手动和config均可显式开启两条链；关闭时保留原单方法行为，独立KBI保持可用。
- MFA2：合成服务分别要求publickey+KBI、password+KBI；实际同一连接完成两阶段后进入PTY。
  第一方法直接SUCCESS也能正常进入PTY，界面不将其标成完成了第二因素。
- MFA3：第一方法拒绝、服务器不提供KBI、config禁用／顺序冲突、第二方法拒绝／仍partial
  均失败；无额外凭据尝试、方法循环或partial阶段终端。
- MFA4：未知／变化主机键仍先核验；旧阶段、旧轮、跨连接、重复／取消／过期回答无传输。
  切换阶段不重新核验为另一主机、不复用旧秘密能力或延长原期限。
- MFA5：多轮／零提示／多提示与取消、锁库／后台、原清理回执及显式全新重连通过；
  回答不持久化，原保存密码／口令功能仍仅按精确身份和既有明确选择工作。
- MFA6：分列真实加密native协议、Java/JNI所有权、Flutter交互／实际字体布局、构建及APK
  审计；Android合成服务和人工体验另获当轮授权后记录，不以编译或mock代替。

## 执行与回退

范围已获用户批准；由主会话细化接口和上下文，再按native→Java/JNI→Flutter依赖分派
trellis-implement，trellis-check独立复核。专项先行，相关全量、正常／隔离构建与来源审计后置；
具体命令和执行状态维护于原[实施清单](../.trellis/tasks/10-08-ssh-terminal/implement.md)。
失败时撤回新增续接选项及计划／wire接口，保留已交付单方法、用户库和密钥／信任数据。
本地Flutter396／JVM856、最终同归档native2687087及64KiB专项、config7995、36张实际字体、
正常／隔离构建lint及三APK来源审计通过；[实际证据／失败历史](verification.md#20261104-ssh-mfa-local)。
独立源码审阅只修正缺失User时选key清空用户名的问题，无额外未决生产发现；最终产物复核
见同一证据目录。用户另授权后[实际Android方法10.415秒通过](verification.md#20261104-ssh-mfa-device)，
含5auth／5PTY与取消／重放／配置禁用、全部清理及独立证据复核。正式应用尚未更新，
新增页面人工体验／实际IME／点击重连及完整父AC仍开放。

## 界面细化（2026-11-04）

已执行项目UUPM Flutter检索`forms checkbox semantics touch target keyboard text scaling`及
UX检索`animation accessibility z-index loading`。沿用现有主题／单认证选择框与圆角选项组件，
续接选项仅密码／私钥可见；独立KBI隐藏且不发送续接许可。整行48dp触控和明确选中／禁用语义，
说明自然换行，忙态及后台／锁库不能切换。切换认证方式清除续接选择，防止隐藏许可残留；
显式重连只从RAM公开计划恢复该选项。保留问题表单与原反馈，新增文案只解释操作效果。
实际字体布局验证覆盖320dp／2倍字／键盘、窄横屏及宽屏，不以检索结果代替渲染验收。

## 公共接口冻结（2026-11-04）

- `continueKeyboardInteractive: bool`为可选参数，缺省false；现有
  `ssh.passwordConnect`、`ssh.keyConnect`、`ssh.configConnect`支持。
  独立`ssh.keyboardConnect`／`ssh.configKeyboardConnect`不允许该许可或其他秘密参数。
- 新增`ssh.configPasswordConnect(requestId,alias,binding,username,password,
  useSavedPassword,savePassword,continueKeyboardInteractive,columns,rows)`。
  alias／binding及可空username override遵循现有config请求；密码和两个保存布尔量遵循
  现有password请求，不允许前端另传host／port绕过实际配置。结果仍为原connected回执。
- config密码必须使用实际求值目标、config信任对象及password政策，持久密码仍绑定实际
  host／port／username。config准备和自己明确保存导致的代次推进只能按既有精确事务延续；
  不能把config复制成manual目标、改写用户config或借另一组已信任键跳过当前核验。
- RAM重连增加`mode:"configPassword"`，保留configAlias／公开端点；各password／key／config／
  configPassword模式可携带`continueKeyboardInteractive:bool`。旧缺省false；独立KBI模式
  不得为true，未知字段／非bool／不合法mode组合拒绝。新的配置binding总是重新获取。
- 继续问题的事件、回答API及CSKI/CSKR保持现有严格格式；本次一条链最多进入KBI一次，
  本方法轮数从1开始。阶段属于宿主／native证明，不把远端文案或前端传入阶段当权威。
- 固定失败诊断增加`SSH_MFA_POLICY`（本次链/config政策不符）和`SSH_MFA_AUTH`
  （部分成功后无法完成已批准续接），不在诊断中包含秘密／回答。

实现边界：native认证选择／阶段及config计划，Java/JNI callback／session／connector／bridge／
reconnect和受管config密码路径，Dart API／连接表单／config认证选择，以及相应测试。
需要少量重构提取共享KBI轮询和config准备；通过原三种单方法、saved秘密和后台服务回归证明。
不改公共vault schema／TOTP业务、Root、ADB配置、pinned upstream或开发wrapper。

## Native／配置接口冻结（2026-11-04）

依据[原选择与阶段研究](../.trellis/tasks/10-08-ssh-terminal/research/mfa-contract-20261104.md)，
保持原authenticationSelection对象及其SHA256不变，另设active method／stage。
阶段二不读取密码／私钥或更换callback selection；首阶段selected-credential一次消费保持。

- CSASv1/v2字节语义保持。开启续接的password／key使用CSASv3：原五个变长字段之后
  增加BE u32 continuationKind=3，flags仍0，原kind只能1或4。v3 key的entry非空仍表示
  精确口令bundle，不能仅按version==2判定加密key。旧版本无续接权，未知／尾字节拒绝。
- C新增`ctos_ssh_native_client_continue_keyboard_interactive_auth(client,
  expected_plan_receipt[32],out_stage_receipt[92])`；JNI为
  `continueKeyboardInteractiveAuth(handle,byte[] planReceipt)->byte[]`；
  Java owner方法`SshNativeSession.continueKeyboardInteractiveAuth()`使用内部期望值。
  只有原计划批准、首阶段真实PARTIAL、当前服务器列表精确含keyboard-interactive且原owner／
  role／view／host／KEX／deadline仍有效时单次切换；原secret-attempted标记不重置。
- CSAPv1阶段回执严格92bytes：u32 magic0x43534150、u16version1、u16flags0、
  u32stage2、u32originalKind(1|4)、u32activeKind3、32byte planSHA256、
  u64原KEX completionCycle、32byte原sessionIdSHA256。Java核对全部字段后封装为私有阶段标记，
  原回执byte[]及时擦除；阶段权威不进入Flutter／日志。native在原SSH对象发KBI请求，失败不重建或重试过渡。
- 仅保留当前实际FAILURE的至多1024byte合法方法列表，精确token匹配；消费／关闭及时清理。
  packet60以active method分派；原begin方法的单次规则保持，第二次续接或原秘密读取拒绝。
- CSCP/CSPPv3在原KBI允许flag后增加两个BE u32bool：passwordThenKeyboardAllowed、
  publickeyThenKeyboardAllowed。它们由实际Options计算，是被消费的相对顺序能力：两方法
  均允许且PreferredAuthentications未指定，或显式列表中首方法在KBI之前。旧v1/v2两项
  默认false；v3与基础方法flag矛盾、非0/1、尾字节拒绝。不另写配置解析器或导出无关方法。
- Connector在原native角色内处理first PARTIAL→受限transition→现有KBI轮询，只有最终
  SUCCESS才退役并交接。mailbox创建时捕获当前阶段，检查原选择及真实host/KEX/阶段；
  仅一次KBI阶段，现有CSKI/CSKR与公开事件无需新增可伪造的阶段参数。

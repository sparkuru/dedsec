# SSH 服务器交互式认证

日期：2026-11-02；验收更新：2026-11-04。状态：**独立KBI及三处界面修正人工验收通过，获准提交**。
沿原[1008父任务](../.trellis/tasks/10-08-ssh-terminal/prd.md)的R7/AC5推进，不新建任务。
用户在正式50614体验后反馈“其他体验认证通过”，并要求统一保存密码区域状态、
认证方式改为单选择框弹窗且说明下移、私钥卡片操作协调。本轮只调整这些UI，
原协议/存储/生命周期及删除确认保持；377 Flutter/analyze、42张实际字体截图及正常
构建/lint/来源签名审计和独立本地复核通过，正式8b6c2a42…已保留数据更新指定板并启动。
[本轮修正和交付](verification.md#20261103-ssh-ui-feedback)后，用户于2026-11-04反馈
“验收通过；可以提交”，[人工验收及提交授权](verification.md#20261104-ssh-kbi-human-acceptance)
已记录；不推断完整父AC通过。
[当前源码研究](../.trellis/tasks/10-08-ssh-terminal/research/next-increment-20261102.md)
记录本轮开始时generic credential kind与真实协议/产品入口间的缺口；当前实现见下方合同与验证。

## 目标与已批准范围

用户能连接仅提供keyboard-interactive的服务器，在受保护界面回答服务器本轮真实问题，
认证成功后进入现有交互终端。手动目标和config主机列表均支持显式选择此方式。
config路径仍按实际有效目标及认证政策准备；不能把password允许当作KBI允许，也不能
忽略明确禁用或顺序约束。配置中的IdentityFile提示不意味着本次必须选私钥。

本增量只做独立KBI方法。密码/私钥后接KBI的多因素方法串联另行交付；服务器返回
partial success仍不能开启终端或静默换方法。现有密码/私钥路径保持明确选择和原行为。
这是用户已确认的实施顺序，不撤销父R7或声称完整MFA已完成。

回答仅当次，不存入秘密库、不复用保存密码/私钥口令、不自动读取TOTP或剪贴板。
不增加目录/路径迁移、跳板、转发、证书或完整恢复。初始批准只含本地实施；随后“授权”
明确覆盖指定板隔离测试与清理，已完成，不含真实账号、正式包更新或本增量提交。
2026-11-03用户又另行授权正式保留数据更新并启动，50614d4d…已交付；仍不含真实账号或提交。

## 可观察验收

- KBI1：手动目标与config主机均能显式选择交互式认证，界面不要求无关的登录密码或私钥。
  config仍尊重实际host/user/port和KBI认证政策；明确禁用时给出可恢复错误。
- KBI2：先完成真实KEX及当前主机信任核验，再发布问题/发送回答。未知主机可拒绝，
  CHANGED/REVOKED/CERTIFICATE无回答或登录；不借挑战内容批准主机键。
- KBI3：支持零提示、多提示及多轮。显示服务器提供的有界名称、说明和问题；按echo
  显示/遮蔽，保留顺序，一次提交本轮完整回答；空回答按协议与用户明确提交处理。
- KBI4：回答绑定当前attempt/owner/role/KEX/method/round；旧轮、跨连接、重复、取消/
  过期后的回答不发送。原认证期限不因每轮交互续期，畸形/超限输入固定失败并清理。
- KBI5：Back、取消、后台、锁库/锁屏、事件流失败和强制移除路由结束待认证请求，
  清除owned回答并等待原清理回执；回前台不复活旧问题。所有提示/回答沿公共敏感保护。
- KBI6：只有真实USERAUTH_SUCCESS及PTY/shell建立成功才移交现有service owner；
  拒绝、partial、断线或超限可恢复且无会话/FD/secret残留，不自动重连或提交旧回答。
- KBI7：分别记录真实native协议/方法状态、JNI/Java ownership、typed Flutter交互、
  真实字体窄屏/键盘/大字布局、构建lint/APK审计和另获授权的Android/合成服务验收。
  generic回调测试不代替真实协议；本地通过不代替设备或用户体验。

## 技术边界与执行顺序

1. 先核对固定OpenSSH及协议原文，确定method-aware INFO_REQUEST/RESPONSE处理。
   当前packet60在password状态是换密码请求，不可全局改成KBI问题。冻结有界字节/数量/
   轮数、原deadline和零提示语义，保持partial无shell权。
2. 在现有native owner/role/view及选定认证证明上增加独立方法状态与owned挑战/回答。
   不把提示文本、方法名或Flutter枚举当作凭据/主机权威，不开放通用明文读取。
3. Java/JNI冻结严格版本化wire、challenge/response生命周期及一次消费；保持实际KEX、
   原连接上下文、取消优先、受保护最终交付和现有SUCCESS后秘密释放。
4. Flutter增加typed事件/回答及受保护表单；服务器文字明确是远端提供内容，不能被当作
   App状态、链接操作或本机命令。确认/取消可达，完整问题可滚动，大字和软键盘可用。
5. 分层专项通过后执行相关全量回归、构建/签名/源码产物审计与trellis-check独立检查；
   同步spec/设计/真实证据。随后按当轮授权准备实际Android与合成KBI服务验收。

无需迁移vault schema或历史资料。回退新增方法/事件/入口，保留既有密码、私钥、
信任管理与后台服务。当前374 Flutter/846 JVM、reviewed2399629 native及7115config、
28张字体布局和正常/隔离构建lint/APK来源审计通过；[实际证据与失败历史](verification.md#20261102-ssh-keyboard-interactive)。
独立审阅通过，随后获授权的[实际Android方法8.348秒通过并清理](verification.md#20261102-ssh-keyboard-interactive-device)。
重连仅验RAM目标合同，未点击Android Flutter重连；正式更新已另行授权并完成，
[安装/启动及清理](verification.md#20261103-ssh-keyboard-interactive-formal-install)独立复核通过，新增页面人工体验待反馈。

## 实施接口（2026-11-02冻结）

协议语义以[RFC4256](https://www.rfc-editor.org/rfc/rfc4256.txt)第3节和
[RFC4252](https://www.rfc-editor.org/rfc/rfc4252.txt)第5节为依据，结合固定OpenSSH源码。
SSH原始boolean按[RFC4251第5节](https://www.rfc-editor.org/rfc/rfc4251.txt)将任何非零
echo解释为true，再规范成内部CSKI的0/1；不能把内部wire严格校验直接套在外部boolean上。
请求method为keyboard-interactive，language/submethods空；每轮单个INFO_REQUEST，
完整等量INFO_RESPONSE，接受用户明确提交的空回答。partial不是最终成功。
密码状态的packet60仍只表示换密请求。name/instruction可空，非空prompt按原顺序显示；
过滤不适合展示的控制符，保留说明换行，不把远端文本交终端执行或写进日志。

三层共同限额：16轮、每轮0–16问题，单个名称/说明/问题/回答UTF8至多4096bytes，
完整挑战wire或完整回答wire各至多32768bytes。UTF8严格解码，无替换字符解码回退。
原连接认证期限60秒不续期；已有角色/KEX/view/库代次约束保持。

### Native与JNI

CSASv1增加kind3（KEYBOARD_INTERACTIVE），仅username非空，其余四域均空；不借generic
credential kind3读取已存SSH_PASSWORD。新增独立begin/result/challenge/respond API，
native每次查验owner/role/view、实际host/KEX证明和原选择；回应还绑定未消费的当前round。
result tags：0 NOT_STARTED、1 PENDING、2 SUCCESS、3 REJECTED、4 PARTIAL、5 CHALLENGE。
CHALLENGE由owner取公共挑战副本；响应仅owner进入，消费一次，队列明文即时擦除。

挑战CSKIv1：BE magic0x43534b49、u16version1、u16flags0、u32round（1–16）、
u32nameLength、u32instructionLength、u32promptCount，共24byte头；随后name、instruction
UTF8，再按序u32echo(0/1)、u32length、promptUTF8。无尾字节。JNI只搬运有界owned副本。
回答CSKRv1：BE magic0x43534b52、u16version1、u16flags0、u32round、u32count，
随后每项u32length、UTF8bytes；count必须等于当前挑战，允许零长度。

对应Java NativeBinding/Native方法：beginKeyboardInteractiveAuth(handle)、
keyboardInteractiveAuthResult(handle)、keyboardInteractiveChallenge(handle)→byte[]、
respondKeyboardInteractive(handle,byte[]CSKR)。SshNativeSession在owner线程验证并封装。

direct plan的CSCP/CSPP使用v2，在passwordAuthentication后增加u32
keyboardInteractiveAuthentication；旧v1解码只能取KBI=false，不能据缺字段推断允许。
实际固定Options给出KbdInteractiveAuthentication和PreferredAuthentications成员政策；
只增加消费所需关键词，不放开其他unsupported选项或命令。用户显式选择方法，列表
限制方法资格，不做自动试探/回退。非默认submethods仍明确拒绝，不静默丢失配置。

### 公共通道与Flutter

`ssh.keyboardConnect(requestId,host,port,username,columns,rows)`与
`ssh.configKeyboardConnect(requestId,alias,binding,username,columns,rows)`复用原连接结果。
`ssh.keyboardResponse(requestId,ticketId,binding,answers:[string])`返回`{accepted:bool}`；
answers为当前轮完整数组，严格UTF8和有界长度，空回答合法；不允许save字段。
宿主发事件`{type:"keyboardChallenge",requestId,ticketId,binding,round,name,instruction,
prompts:[{text,echo}],remainingMillis}`；remainingMillis为原期限内0–60000，ticketId/binding
沿原opaqueID/64hex标准。终态或消费发`{type:"keyboardChallengeDismissed",requestId,ticketId}`。
独立随机票据绑定原attempt/role/KEX/method/round；迟到事件不发布至不同owner/route。
回答到达后由原owner验证并发送，不在MethodChannel主线程调用native。

RAM重连mode增加keyboard/configKeyboard；后者保留alias和公开端点，无keyId或旧binding/
问题/回答。重连仍解锁并重新求值当前config、核验实际host、发起新认证；无自动作答。
UI沿原敏感连接页增加显式“服务器交互式认证”，隐藏无关密码/密钥/保存字段；config
列表选项也可选择此方法。挑战显示“服务器请求”及实际目标，上述远端字段作为正文显示。

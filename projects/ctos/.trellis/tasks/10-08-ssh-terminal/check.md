# 初步规划检查

## 2026-11-13 SSH 部分提交准备

用户允许部分提交并计划新开 session。独立干净 HEAD 快照证明界面与 MFA 产品代码需要
一起构成依赖闭包；快照 Dart 分析通过，快照 Flutter 测试因环境未运行。当前工作区 JVM
856 项／58 套全通过，既有最终 Flutter 438 项及设备隔离结果分别保留。
[证据与限制](../../../design/verification.md#20261113-ssh-partial-commit-preparation)。

## 2026-11-13 SSH 表单与指纹恢复人工反馈

用户反馈“可以；目前能够接受了”，本轮体验按该范围记录为可接受；用户没有逐项列出
测试步骤，不扩写 MFA 串联或父任务验收。[原始反馈与边界](../../../design/verification.md#20261113-ssh-feedback-human-acceptance)。

## 2026-11-13 指纹原地忘记与错误提示复验

438 Flutter 测试、静态分析及两认证方式的指定板完整换键界面测试通过；实际隔离测试的
早期等待失败与修正均保留。正式 08881940… 已沿同任务授权保留数据更新并启动，公开身份
保持，隔离资源与专用 ADB 已清理；人工复验待反馈。
[详细证据及错误码边界](../../../design/verification.md#20261113-ssh-feedback-direct-recovery)。

## 2026-11-13 SSH 表单与指纹恢复正式更新

用户批准指定板保留数据更新并启动。实际新 APK 160b9845…、UID／数据目录／首次安装
时间核对通过，MainActivity 已 resumed，专用 ADB 已退出；同范围合成服务启动30分钟。
用户人工体验尚未反馈，完整父任务不关闭。[回执](../../../design/verification.md#20261113-ssh-ux-formal-install)。

## 2026-11-13 SSH 表单与主机变更恢复

用户要求自行操作界面后再人工验收。全量 Flutter 408 项、analyze、追加两项双倍字体弹窗
检查通过；实际隔离包在 AIO-3568J 完成首次信任、变更取消／查看、忘记取消／确认、新
指纹核验和合成终端。首轮测试驱动等待条件失败及修正保留在验证记录；不将其算作通过。
当时正常候选 160b9845… 已构建及验签，正式仍为 418f6d13…；后续更新见上条，人工验收和父任务保持开放。
[详细证据](../../../design/verification.md#20261113-ssh-form-host-recovery)。

## 2026-11-13 延长MFA人工测试窗口

用户要求时间长一些并可开始测试；上一服务按期退出。新服务沿原地址／端口／来源限制，
设为30分钟，单次合成终端5分钟，测试和实际监听核对通过；MFA人工体验待反馈。
[启动记录](../../../design/verification.md#20261113-ssh-manual-fixture-30m)。

## 2026-11-13 MFA串联人工体验待反馈

独立KBI的提问／回答／进入测试终端由用户确认；其临时服务已按期限退出且端口释放。
沿既有授权以同一脚本重新启动同地址及范围，供密码→KBI人工体验；本次MFA尚无反馈。
[启动记录](../../../design/verification.md#20261113-ssh-mfa-manual-restart)。

## 2026-11-13 纠正独立KBI测试

用户指出不应要求独立交互式认证先做密码认证；先前助手解释和仅MFA夹具均不适用。
代码中独立分支直接发起KBI；新的合成服务支持独立KBI并完成本机自测，当前按既有
地址／端口／来源／时长授权启动。用户反馈“这次对了”；是否回答并进入终端未具体说明，
随后明确确认回答问题后进入测试终端；不据此判MFA人工验收通过。
[证据和限制](../../../design/verification.md#20261113-ssh-direct-kbi-manual-fixture)。

## 2026-11-13 合成MFA人工体验进行中

用户授权的临时服务在`192.168.9.5:22222`启动，实际监听及固定脚本来源已核对；
限制来源192.168.9.14并设置300秒寿命。用户反馈截图仍选独立KBI，尚未验证密码后续接；
本次没有ADB或真实账号操作，仍待目标流程的人工反馈。
[启动记录](../../../design/verification.md#20261113-ssh-mfa-manual-fixture)。

## 2026-11-13 再次确认独立KBI无问题提示

用户确认本次直接报错。当前本机SSH配置经合成主机密钥`sshd -T`检查仍关闭KBI，
与现象一致；未捕获实际连接报文或运行服务加载状态。合成MFA夹具完成进程内和短暂
回环自测，该阶段尚未开放局域网监听或连接开发板；人工验收保持开放。
[本轮核对](../../../design/verification.md#20261113-ssh-kbi-recheck)。

## 2026-11-13 接续本地定向回归

原1008父任务已重新选为本次current，任务上下文清单校验通过。当前工作树的
`./hako flutter test --no-pub test/ssh_keyboard_interactive_test.dart --reporter expanded`
43项通过，覆盖手动／config密码及私钥续接和问题生命周期。测试前后构建目录APK仍为
418f6d13…；本轮未重建、连接设备或重验历史设备回执。原始临时证据目录已不存在。
人工体验、提交及完整父AC仍开放；[验证范围](../../../design/verification.md#20261113-ssh-mfa-resume)。

## 2026-11-06 正式保留数据更新及启动通过

沿用户“恢复了；继续”及既有授权执行冻结控制器ad682e6d…，30源码／21生成输入与
正常418f6d13…／v2签名保持。指定AIO-3568J／API30／shellUID2000身份确认，实际旧8b6
公开APK备份后一次install-r成功；实际新418f及保留的UID10131／dataDir／首次安装核对通过。
am start为Status:ok，MainActivity实际resumed；自有ADB15061已回收且无监听，42TIME-WAIT单列。
独立原始证据复核PASS：23组命令／实际前后APK／签名／身份／单次安装／启动及清理，
48份回执清单b10cedf5…；只关闭更新／启动交付范围。
[原始交付证据](../../../design/verification.md#20261106-ssh-mfa-formal-install)。产品代码未变，
本轮不重复产品构建／测试；人工体验／提交／完整父AC仍开放。

## 2026-11-05 正式交付未执行：连接失败

用户另授权同板保留数据更新正常418f6d13…并启动。冻结交付控制器ad682e6d…的6项纯测试、
语法检查及独立安全复核完成；正常APK／v2签名、30源码／21生成输入与审计保持。
实际预检及一次同址重试均为`No route to host`；重试passed=false、COMMAND_state，
没有当前设备／应用身份、公开APK备份、install_intent、安装或启动。私有ADB15061已回收、
无监听且日志关闭，独立9组原始回执复核确认安全停止，不判交付PASS。
没有产品代码变化，无需重跑产品测试或构建。现有授权保留，等待目标恢复可达；
[本轮证据](../../../design/verification.md#20261105-ssh-mfa-formal-install)。人工体验／提交／父完整AC保持开放。

## 2026-11-04 MFA本地及指定板隔离检查通过

用户回复“开始”，批准[本轮合同](../../../design/ssh-mfa-authentication.md)的本地实施。
当前证据`/tmp/ctos-ssh-mfa-20261104.BZ6IOgdm`；中断前BM2z8Wrk临时目录已不存在，
本节不把旧目录作为可复核新证据。

- Flutter：实现定向128通过；独立审阅修正选key清空缺失User覆盖值，新增回归先红后2绿，
  analyze无问题，主会话最终完整396通过／29秒。36张真实CJK／Material字体布局刷新通过。
- JVM：完整856／58套、0失败／错误／跳过，1分34秒，原XML及真实grammar／保存保持回归。
- Config：实际ARM矩阵7995通过，包含1262基线；64KiB锁页分支通过，无额外效果。
- Native：最终确切归档全量2687087（含MFA287236）通过，两项均严格64KiB；原先同源码
  2686841为历史。首轮sandbox的getsockopt(SO_TYPE) EPERM→EINVAL已以strace定位，未弱化检查。
- 正常首建45.3秒／同输入重打27秒；正常lint42秒0错误4旧警告，隔离构建lint44秒0错误6旧警告。
  30源码／21生成输入／三APK v2／包名／实际Java-Dart-JNI／16KiB／测试隔离审计通过。
  编译随机路径造成的初次不同库未放行，固定实际输入并独立strip复现共同5356b964…库，
  再按最终归档重新跑native；原始失败和五份私有core证据保留。
- trellis-check完整本地PASS，只有上述用户名修正，无额外未决生产发现；独立重验58份XML、
  SDK签名／manifest、源码pin及标准strip产物。`review-full.md`记录最终范围及未验项。
- 独立服务8transports／4auth／4实际PTY、3错误负例／1取消与清理通过；Android方法
  随后获用户另回复“授权”，实际10.415秒、6transports／5auth／5实际PTY及1取消通过。
  5次3／0／1、保存复用忘记／重放拒绝／配置禁用／测试TOTP及key/config保持均通过。
  11项清理true，正式8b6的实际公开APK／签名／UID10131／dataDir／安装时间保持；
  request／reverse／两隔离包／本地生成秘密／listener及owned进程清理，137TIME-WAIT单列。
  独立实际运行复核PASS，62命令原始回执／server逐transport／6份实际公开APK重新核对。
- 首次`device-u3nqq1yw`停在安装前pm缺包rc1空输出，未安装／server／request／reverse，
  全部清理和正式身份保持。只修正driver的明确absence约定，12纯测试和独立差异复核后
  用同两APK／同授权重验。最终`device-6rm2kyp2`，e07ad901…驱动；未改应用或重新构建。

最终正常418f6d13…／隔离0819dddb…／测试78e04242…；完整hash／命令／失败历史／限制见
[本轮验证](../../../design/verification.md#20261104-ssh-mfa-local)。

已完成本轮明确授权的指定板隔离运行；无真实账号／正式更新／提交／归档，完整父AC保持开放。

## 2026-11-04 人工验收通过，提交前来源核对

用户反馈“验收通过；可以提交”，正式8b6c2a42…对应独立KBI及三处UI修正人工验收
通过。原验证/交付证据保留，不扩写用户逐项手测内容；本次只更新验收状态与提交。
提交范围为本轮56路径；既有journal、应用细节TODO及相邻项目改动排除，原1008
继续in_progress，完整父AC及MFA串联开放。

## 2026-11-03 KBI体验后的三处UI修正交付

用户确认其他认证体验通过，选择框弹窗及下方说明、四个保存/复用选项圆角状态、
私钥操作描边/尺寸/对齐已修正。仅两个页面/两个测试变化，原32项清单其余28项
（含全部24项Android）hash保持。相关SSH110、最终KBI24、全量Flutter377/analyze、
42张实际字体/大字/hover/键盘focus截图、正常构建47.2秒/lint38秒0错误4旧警告及
最终APK来源/签名/闭包审计、独立本地trellis-check均PASS；失败历史保留。
正式8b6c2a42…已沿本次同功能保留数据更新流程install-r指定板并启动，实际hash与候选
一致，UID10131/dataDir/首次安装保持；最后更新23:06:48，MainActivity实际resumed。
只备份公开旧50614 APK，专用ADB15057退出无监听、43条TIME_WAIT分开保留。
[本轮验证](../../../design/verification.md#20261103-ssh-ui-feedback)。修正后视觉反馈、
实际Flutter点击重连及本增量提交仍待对应验证/授权，不归档父1008或操作真实账号。

## 2026-11-03 正式KBI保留数据更新并启动

用户另回复“授权”后，实际AIO-3568J/API30已install-r更新正常50614d4d…并启动。
实际APK与已审阅候选一致，新旧v2 signer8d36…相同，UID10131/dataDir/首次安装保持，
lastUpdate=2026-11-03 00:07:09，MainActivity实际resumed。只备份公开旧5e70 APK，未
卸载/清数据/读取正式秘密库或真实账号。专用ADB15055退出无监听，43条TIME_WAIT原始
收据保留为短暂连接状态，不当作监听残留；独立交付复核PASS。
[证据](../../../design/verification.md#20261103-ssh-keyboard-interactive-formal-install)，
`/tmp/ctos-ssh-kbi-install-20261103.HQhAhckU/formal-install-review.md`。
本轮无源码变化/测试重跑，32项源码仍匹配；人工体验与KBI提交仍待反馈/批准，父不归档。

## 2026-11-02 KBI指定板实际验收与清理通过

用户“授权”后，最终`device-b565983f4d8d`实际AIO-3568J/API30方法status0/OK1，
JUnit8.348秒/驱动壁钟10.587秒：6transport、3auth/3PTY-shell，三套实际3/0/1回应，
另取消一轮未回答问题；双信任组CHANGED无问题/回答、NEW审阅、config KNOWN/禁用政策、
旧/跨/重放拒绝通过。重连只验RAM元数据，未点击Android Flutter重连或人工体验页面。
14项cleanup全部true，三份独立空容器/网络/监听收据及秘密文件缺失核对通过；正式
5e70/UID10131/数据路径/首次与最后安装时间保持，未读取正式库/真实账号。
[设备证据及首轮失败](../../../design/verification.md#20261102-ssh-keyboard-interactive-device)。

首轮`device-b5adea1a2461`仍失败：缺少编辑configRead回执的VAULT_CHANGED及空reverse
解析RuntimeError保留。只补夹具读取/正文/缺失回执负断言及驱动空行解析16纯回归；
正常50614/隔离fae925及其他31源码未变，新test2ec93仅13秒重建/签名审计后通过。
独立source/candidate/板上/清理复核PASS；正式安装、页面人工体验和本增量提交仍待批准。

## 2026-11-02 KBI本地与独立复核通过（设备授权前记录）

用户“可以”后按冻结合同实施：手动/config独立KBI，多提示/多轮、空回答/零提示明确继续、
不保存回答，不自动切方法或MFA串联。最终374 Flutter/analyze、846 JVM/57套、reviewed
2399629 native（含KBI639993）、7115config/64KiB、28张真实字体布局均通过。
normal/fixture完整构建与分别lint通过，0错误及4/6原有警告；三个APK签名/包名/arm64/
Java-Dart-JNI闭包/测试类隔离通过，32源码前后hash保持。正常50614d4d…未安装。
`/tmp/ctos-ssh-kbi-20261102.LE2RQCwF`保存分层报告/原始失败/红绿回归及
`apk-audit.json`、`all-changed-sources.sha256`、lint原XML；
[完整验证](../../../design/verification.md#20261102-ssh-keyboard-interactive)说明当前与历史计数。
独立产物/源码/文档复核PASS，设备执行驱动仅准备未运行；Android平台方法只编译/打包，
未安装或运行，新设备授权、正式更新、人工体验、本增量提交均未自动取得。
准备驱动的精确资源责任/失败清理也已独立静态复核PASS；32源码/三APK与driver最终hash
重核保持，`final-local-status.json`为本地收尾回执，不代表设备执行成功。

## 2026-11-02 批准提交并开始KBI

用户“可以”批准52路径主机信任提交清单与手动/config独立KBI范围。工作提交为
`bb8fa225fe44a1eaceef6ce21380c93d1fbef087`，52路径精确核对、六处部分内容与排除项保持；
初次底层索引路径解释错误被集合断言拦截，恢复本次索引后完整前缀重建，最终无ctOS外路径。
当前KBI实现中，不把前序353 Flutter/828 JVM/设备结果用于新认证；本轮设备/真实账号
与新提交未授权。父1008仍in_progress，不归档。

## 2026-11-02 主机信任人工验收通过并继续

正式5e70df40交付后，用户回复“通过；可以继续；”，本增量人工体验门通过。
HT1–HT8按本地/隔离设备/正式更新/用户反馈分层证据完成，不推定用户逐项执行异常测试。
本轮仅同步验收状态，产品源码和已审阅APK未改变；父完整AC保持开放，提交清单待批准。
[验收记录](../../../design/verification.md#20261102-ssh-host-trust-human-acceptance)。
最终提交准备独立复核PASS：27+2项源码及三APK hash不变，52路径/六处部分提交范围、
JSON/JSONL/文档链接/diff通过。清单在
`/tmp/ctos-ssh-host-trust-20261102.lFHL3SXq/commit-plan.md`；不含下一KBI草案、既有
journal/应用细节TODO或邻项目。新KBI研究/合同仅规划待范围确认，未实施、重建或操作设备。

## 2026-11-02 正式保留数据更新已完成，待人工体验

用户另行授权后，指定板5e70df40…已通过install-r安装并正常启动；实际APK hash与候选
匹配，新旧v2 signer相同，UID10131/dataDir/首次安装保持，MainActivity实际resumed。
旧公开eed028f0…APK已备份，未卸载/清数据/访问库内资料或真实账号。
证据`/tmp/ctos-ssh-host-trust-install-20261102.kzzZ8HBd`；驱动即时bind15051失败保留，
专用ADB已退出，独立ss确认无监听，仅短暂TIME_WAIT后全空，install-review最终PASS。
独立交付审计PASS，[当前安装记录](../../../design/verification.md#20261102-ssh-host-trust-formal-install)。
本轮没有代码变动或重跑产品测试，前序冻结源码/验证仍匹配。新增页面人工体验/提交
指示仍待反馈，父任务不归档，不自动开始下一功能。

## 2026-11-02 修复候选与指定板最终验收通过

- 最终353 Flutter/828 JVM/55套零失败错误跳过，定向74 JVM及1758432 ARM客户端矩阵
  通过，保留默认沙箱socket拒绝的失败日志；normal/fixture零lint错误4/6既有警告。
- 源码27+2项、三APK hash/arm64/v2/包名/类型异常DEX及独立复核PASS；正常5e70df40…、
  fixture903b03f5…、testfefc38bf…，修复只提供受约束终态诊断，不放宽host批准门。
- 指定AIO-3568J/API30实际JNI3项通过（JUnit0.093秒/壁钟2.363秒）；实际换键方法
  6.893秒（壁钟9.119秒）OK1/status0，含准确CHANGED、无自动审阅/写入/连接、双组
  cancel/cross-kind/replay/逐次提交/无关字节与端口保留、精确NEW批准/PTY/KNOWN及服务BUSY。
- 第三轮`/tmp/ctos-ssh-host-trust-device-20261102.Bwg1kvBz`所有11项cleanup为true，
  合成服务2密码/2shell，relay3；端口15049/15493及带标签容器网络独立核对已清。
  正式eed028f0…/UID10131/数据路径及安装时间保持，仅公开元数据审计，没有读取正式库。
- 首轮通用STEP缺口已修复；第二轮合成sshd缺AUDIT_WRITE导致密码后fatal，仅补临时容器
  能力并使用完全相同903b/fefc应用重测通过。两失败整体仍保留失败，三轮全部清理。
- 独立review最终候选+授权板关卡PASS，无未决代码问题；[完整证据](../../../design/verification.md#20261102-ssh-host-trust-device)。
  human-required只余新增Flutter页面实际体验；未更新正式包/真实账号/提交/归档/dist，父AC未全完。

## 2026-11-02 已授权设备首轮与诊断修复（重测中）

指定AIO-3568J/API30的实际JNI3项通过；真实换键方法因固定CHANGED变成通用STEP而失败，
未记设备整体通过。原native负分类拒绝在Java回调前发生，旧JNI丢失原因；本轮只增加
actualissuer终态诊断/owner-role-view及固定类型异常，保持无negative批准能力。定向74 JVM、
严格JNI编译及1758432 ARM客户端矩阵通过；353 Flutter复跑通过，最终APK/设备尚待本轮
重测。默认沙箱native socket准入失败和提升权限后的实际通过日志均保留。
首轮外部驱动所有清理标记true、端口15049/15493及容器/网络均清，正式APK/UID元数据
保持。[当前记录](../../../design/verification.md#20261102-ssh-host-trust-device)。

## 2026-11-02 主机信任：最终本地关卡通过，设备待验

- 用户“开始”及“继续”后，沿原1008实现双对象query/新preview/精确forget，保留原
  host-key拒绝、source-bound一次事务和所有无关字节/数据，不自动连接或接受新键。
- 353全量Flutter、824 JVM/55套（主机信任8+14）、analyze/格式、20张真实字体布局通过；
  normal/fixture构建lint零错误4/6既有警告，三最终APK arm64/v2/包标识/无探针审计通过。
- 最终真实ARM/QEMU trust9051、pair13814、private21009、certificate23268通过；各含
  重复材料基线，不相加。源码21+2项、native四组输入/二进制/基线与最终archive冻结复核通过。
- 独立trellis-check最终local gate PASS，无未决源码发现；已修正活动服务互斥、晚到保护
  授权、强制移除回执和底层已不确定结果传输失败后的降级。检查的暂态失败和初轮多ABI
  中间产物保留，最终产物已显式arm64重建；完整事实见[验证](../../../design/verification.md#20261102-ssh-host-trust)。
- 证据`/tmp/ctos-ssh-host-trust-20261102.lFHL3SXq`，独立`review-host-trust.md`最终PASS；
  normal a6b35d7b…、fixture99fcac8c…、test7bd207c2…。
- JNI三方法与真实CHANGED→忘记→NEW/Known集成夹具仅编译；设备/真实账号/正式安装/
  人工体验未执行，本轮未提交/归档/dist，父完整AC开放。human-required：新增界面与实际
  Android换键流程需当前授权下的隔离方法及用户体验反馈；历史设备许可不继承。

## 2026-11-02 接续1008：主机信任方案文档检查

- 当前HEAD `b1064c7`，沿已有父task；当前增量为已选但尚未实施的host-trust management。
  本次补齐[合同](../../../design/ssh-host-trust-management.md)、PRD HT1–HT8、设计、执行计划、
  两份真实上下文manifest、主线和design索引，不改产品代码/数据/邻项目。
- 定向源码复核确认两套完整对象身份、issuer lookup、CHANGED/REVOKED/CERTIFICATE拒绝、
  parser限额和byte span风险。旧研究的数字/目录票据已纠正为当前import/keyDelete布尔分派。
- 实际执行 `git diff --check -- .` 通过；`jq empty` 检查task JSON和两份JSONL通过；
  新合同及父PRD/design/implement全部33条本地文件链接存在，无缺失。
- 独立定向源码/规划一致性复核PASS，核对双对象、现有票据、原source/context、marker/
  shared拒绝和证据分层；仅为规划复核，不代表wire/实现或产品行为已经验证。
- UUPM执行Flutter表单/确认/无障碍检索及通用设计查询；只采用既有主题内的Form/
  Semantics/文字缩放建议，不增加网站布局、字体/配色依赖或第二设计体系。
- 方案待最终审阅，父status保持in_progress，current指针尚未恢复，implementation_started=false。
  未执行产品测试、native/JNI、构建、设备、真实key/账号、安装、提交、归档或dist更新。
  上方文档检查不能替代HT1–HT8或完整父AC通过，旁任务和既有journal修改保持。

## 2026-11-02 用户实际remote连接与人工验收通过

用户在正式eed028f0…交付后明确反馈“不错；效果通过；我已连接实际 remote 连接成功。”
本轮人工门通过，实际远端成功按用户报告记录，不伪造agent日志或逐Host／算法／平台
方法已通过。原本地／产物／安装证据不变，来源未改、未重复测试；用户随后明确“可以
提交”，批准本轮精确清单，父完整AC继续开放，不自动归档或推进其他能力。

## 2026-11-02 config-first显式key：本地PASS与正式修复更新

当前合同为`design/ssh-config-identity-selection.md`。原始user config只做点名只读诊断，
原文件未改、本地原文已清。324 Flutter／802 JVM／analyze0、ARM资源4872／direct5651
（各含detached1262）及catalog71923／16张布局、正常与fixture构建lint／v2通过。
独立review-config-identity.md最终PASS，无剩余具体发现；口令保存前trust检查已修，
hint分类与hasDeferred字段语义按明确合同结案。未重跑无关矩阵，未将平台方法编译当运行。
两次native重编先有产物字节差异，已冻结同一生产结果重打normal；最终19项来源、36项
native输入、APK／XML均重核。正常eed028f0…／fixture18816610…／test9936f2b1…。

沿同一更新流程，eed正式包install-r到指定板成功，设备hash匹配，UID10131／dataDir／
firstInstallTime保持、lastUpdate11:31:52，MainActivity resumed。专用ADB15047已停；
未安装隔离包／读取真实库key／访问账号，导入／选择／实际SSH和人工体验待用户重试。
父完整AC开放，未提交／归档／更新dist；详情在`design/verification.md`当前修正节。

## 2026-11-02 正式安装核对通过

用户明确“更新上去”，本轮保留数据install-r成功；指定板实际e1241897…与审阅产物相同，
UID10131／dataDir／firstInstallTime保持，lastUpdateTime10:24:03，MainActivity启动ok及
实际resumed，进程存在。原公开b2dAPK已保存，专用ADB15045已停，未读取库或访问账号。
详见 `design/verification.md#20261102-ssh-single-config-formal-install`；人工体验与提交未
因此推定通过，父完整AC保持开放。

## 2026-11-02 撤下目录，保留单config多个Host

用户澄清及新范围见PRD头部；目录代码／通道／provider／夹具已移除，保留单文件及已有
库对象，不加路径适配。41定向／318全量Flutter及analyze通过；799 JVM／53套无失败／
错误／跳过；实际原生求值3736／枚举71923、8张真实字体布局与正常／隔离构建lint／
官方三APK v2审计通过。正式e1241897…未安装设备，父完整AC和人工体验分列。
当前来源／APK／原生17项输入已核对；旧缓存native源码不符未使用，重新构建后才执行。
首次analyze旧目录临时脚本错误、统计脚本lint路径误拼及修正均如实保留，未改校验规则。
详见 `design/verification.md#20261102-ssh-single-config`；独立check最终PASS，无具体待修发现，
未改源码。review-removal.md保存在本轮证据根，重算源码／APK／native输入／JUnit与布局
均相符；本地交付ready，设备及人工体验保持human-required，不增加逐版本验收前置。

## 2026-11-02 接续定向检查

目录14 Flutter及快照8／候选12 JVM重新执行通过，失败／错误／跳过均零；三个本地APK
仍匹配10-30哈希。旧/tmp目录及其原始设备／安装／独立review／布局证据已不存在，本轮
没有重新核验历史日志；12项当前来源哈希不等于恢复历史42项冻结表。详见
`design/verification.md#20261102-ssh-directory-resume`。无产品代码变更或设备操作，未重跑
全量／lint／native；人工体验和提交许可仍待反馈，父完整AC开放。

## 2026-10-30 目录批量导入：本地及指定板隔离通过

合同 `design/ssh-directory-import.md`，权威证据
`design/verification.md#20261030-ssh-directory-import`。819 JVM／55套零失败错误跳过，
331 Flutter／目录14项／analyze0，normal与fixture构建lint／三APK验签审计通过。
28张实际字体布局通过，42项来源冻结；未改native，原9／23／25项输入相符，不重记矩阵。

指定板实际方法31.649秒／status0／OK1，覆盖快照不重读、嵌套Include／密钥证明、选择／
解锁／整批原子提交、unchanged关联保持、异常provider及主动失效快照清零；2次真实公钥
登录和PTY。正式fc98／UID10131及包元数据保持，资源全清，专用15041／15491端口已关闭。
源码三项发现由owner修复并补回归；review-final.json独立PASS，无未决发现。新增正常b2d91de2…尚未
人工验收；随后获用户允许已正式安装并启动，UID／数据目录／首次安装保持，安装证据
`design/verification.md#20261030-ssh-directory-formal-install`，review-install.json独立PASS。
真实DocumentsUI／grant及不确定写入设备故障注入未运行，父AC开放。

## 2026-10-30 人工体验通过／授权提交

用户明确“功能通过；可以提交并继续”，当前正式fc98的密钥功能与视觉调整人工验收通过。
此前检查／设备证据不变，不假称重跑；提交本轮完整已验收增量，父完整AC开放，继续原task。
提交前独立full-scope复核覆盖全部C/JNI、Java、Flutter及测试／spec／design差异，PASS，
无新增发现；31项来源仍匹配已审阅fc98。精确暂存排除旁任务README插入和下一增量研究。

## 2026-10-30 私钥卡片视觉反馈修正

仅library_page.dart局部操作组变化，其他30项来源匹配前序快照。317 Flutter／analyze0、
24张实际字体／hover／大字／窄横屏展示、正常arm64构建lint／v2与APK审计通过；
独立视觉复核PASS，无新增发现。正式fc98已保留数据更新同板并启动，旧0e8e公开APK备份
验hash，UID／dataDir／首次安装时间保持，最后更新17:14:54，专用ADB已停。安装独立复核
PASS（review-final.json）；人工体验／提交仍待。证据 `design/verification.md#20261030-ssh-key-card-actions`。

## 2026-10-30 私钥删除正式安装核对通过

用户回复“可以”后，重新核对31项来源及审阅APK，备份原公开f26a并保留数据install-r。
指定板实际新0e8e哈希匹配，UID10131、dataDir与首次安装时间保持；最后更新17:01:16，
MainActivity Status ok／实际resumed且进程存在。专用ADB已停，证据
`/tmp/ctos-ssh-key-delete-install-20261030.qC0iJ64T`及
`design/verification.md#20261030-ssh-key-delete-formal-install`。review-install.json独立PASS；
用户人工体验仍待，未推定提交／归档／dist授权，下方未安装说法仅为前序隔离阶段。

## 2026-10-30 私钥删除：本地／指定板隔离／独立复核通过

合同 `design/ssh-key-deletion.md`，证据 `design/verification.md#20261030-ssh-key-deletion`。
799 JVM／53套零失败／错误／跳过；317 Flutter、analyze及真实CJK字体20张展示通过。
正常／fixture构建lint与三APK官方v2／min28／target36审计通过，正常0e8e63e0…、
fixture7ac3c57b…、teste9e04db9…。31项来源及原生输入9／23／25行逐项一致。

实际隔离方法42.572秒／status0／OK1，两次公钥登录／shell，覆盖删除取消和精确提交、
关联清理与无关资料保留、CONFIG／Include正文保持、全有效身份缺失拒绝、严格导入闭包、
重导入修复／不复活口令账号，以及生成MANUAL有无入边。清理全部true且errors为空，
正式前后f26a／UID10131／dataDir及首次和最后安装时间保持，专用ADB也已停。

独立复核三处已修复发现：严格导入漏查Include子文件、禁用行恢复说明对比度、不可变map
追加error导致实际library刷新异常。前两次环境预检及第三次实际失败分别保留，不混入通过。
最终 `/tmp/ctos-ssh-key-delete-20261030.0YEUnyyV/review-final.json` PASS，无剩余发现。
不代替真实DocumentsUI／人工Flutter体验、其他设备／格式或迟到写入设备故障注入；
新增正式包尚未安装，未提交／归档／替换dist，父完整AC开放。

## 2026-10-30 加密私钥正式安装核对通过，待人工体验

用户明确“允许”保留数据更新正式应用。安装前核对同一板AIO-3568J/API30/arm64、
原7c38／UID10131及26项审阅来源；保存原公开APK后install-r成功。设备新f26a hash与
已验收产物匹配，UID／dataDir／firstInstallTime保持，MainActivity启动成功并resumed。
证据 `/tmp/ctos-ssh-encrypted-formal-20261030.49lEUMZ5`，权威记录
`design/verification.md#20261030-ssh-encrypted-formal-install`。未卸载／清数据／访问真实
秘密或账号，当前安装不代表新增Flutter交互人工验收；用户体验反馈及提交仍待。

## 2026-10-30 加密私钥／可选口令：本地与开发板隔离通过

用户选择继续本轮切片，随后明确允许开发板独立UID／合成账号实测。完整合同在
`design/ssh-encrypted-key-connect.md`，权威记录在
`design/verification.md#20261030-ssh-encrypted-key`。

- 实现：原始加密文件／原ticket解锁、实际公钥证明、当次或显式保存／复用口令、原子
  单条／全部忘记、同公钥重新导入失效及精确CSASv2／host／KEX最终交付。
- 本地：304 Flutter／786 JVM／51 suites无失败、normal与fixture构建/lint/v2通过；
  ARM材料21005及完整客户端1759380，五类独立SSH key-only互通通过并清理。
- 产物：正式arm64 f26a0da9…／fixture2a204b30…／testa81d6448…；同一生产原生库，
  新JNI及20sessionJNI、22分配节、26项来源及23／25／9原生输入核对一致。
- 实机：AIO-3568J/API30 `.14`，精确方法status0／OK1／40.512秒；实际加密导入、错误
  口令、当次／保存／复用／忘记和重导入通过，服务端5次公钥／shell；正式7c38／UID10131
  及数据目录／安装时间前后保持，测试包／请求／映射／relay／容器／网络全清。
- 独立Trellis源码／原始证据／产物／设备清理复核通过，无剩余缺陷。首轮JVM新增断言
  位置与Flutter pump、临时服务与字体夹具问题按原日志保留，未放宽产品规则。

review classification：human-required，新增敏感页面／口令交互还需用户实际体验。
独立provider方法不冒充真实DocumentsUI／grant或Flutter人工操作；其他设备、个人私钥／
账号、Android逐格式及复杂恢复和父完整AC仍分列。后续正式更新另按上方当前授权完成；
无提交／归档／dist授权，父任务保持in_progress，旁任务与邻项目修改保留。

## 2026-10-30 正式安装与人工验收门通过

主会话install-r更新正式7c38并核对设备哈希；UID10131/数据目录/首次安装时间前后一致。
用户明确反馈“效果可以；提交”，人工体验及本轮提交条件满足。之前记录的源码/质量/
隔离设备检查不变；按已复核49路径提交，README其他task三行及整个app-detail-fixes、
邻项目改动排除。父完整task保持in_progress，不归档。

## 2026-10-30 指定开发板：新锁屏断开/真实一键重连通过

独立reviewer核验22项最终来源/APK表均保持，fixture447c748…相同；新设备日志70.244秒、
method status0/OK1，五个锁屏关闭/正常解锁/实际一键重连公开阶段完整。源码确认实际
Power/Keyguard/vault锁定、旧owner释放/CPU锁释放/旧输入拒绝后才正常解锁；解锁设备、
重建和解锁库均不自动连接，真实Flutter按钮才建立新代次/native RUNNING及PTY，旧receiver
不能取消新owner；原Home/重建/通知Stop/config及stale binding检查同一整轮通过。

主会话清理全部隔离资源，正式373前后hash一致/UID10131保持。未重复本地检查或安装正式
新APK，人工体验/父完整AC及提交边界保持。[完整证据](../../../design/verification.md#20261030-ssh-lock-disconnect-board)。
用户要求以统一模型收敛，不再把手机/逐版本矩阵作为前置；不推断Android14/15等未测版本不能用。

## 2026-10-28 锁屏断开/用户一键重连：本地质量门通过，实机待入口

独立reviewer核对本切片backend/frontend源码、最终产物/源码哈希与实际日志，无剩余具体
发现。复核中发现重复锁屏覆盖最终closed回执的问题，已以实际controller.closed为准修复
并补输出ack延迟的真实Port回归；owner取消后仍始终确认丢弃输出。元数据严格非秘密，
新资料/选择/端点及新binding、信任认证继续检查；显式一次授权不会在后台/重读后重试。

主会话实际运行分析、65定向/293全量Flutter、29定向/775全量JVM、normal/fixture/test
构建lint及官方v2；原生输入哈希保持，未重跑ARM矩阵。lint为normal0错4警告、fixture0错
6警告；其中StaticFieldLeak经核对只有applicationContext且按owner释放，不持有Activity，
未抑制警告。完整证据见[verification](../../../design/verification.md#20261028-ssh-lock-disconnect)。

手机安装前离线，重连No route to host；新版未安装，不能把夹具源码路径或widget通过说成
新行为手机通过。当前等待用户确认可用ADB地址；临时服务/网络/代理、合成密钥/JSON及
专用ADB已清。正式应用未更新，human-required与父完整AC保持，本轮不提交或归档。

## 2026-10-28 手机权限拒绝状态与窄屏IME检查

独立reviewer确认夹具仅在自身当前已附着/聚焦窗口真实IME可见时发送正常BACK，等待
IME隐藏、概览可见并保留终端路由；不改产品/系统设置或放宽会话、锁库和关闭断言。
初轮19.065秒失败保留，最终10.594秒/status0/OK1且三个实际IME阶段齐全，四项来源/APK
哈希、14秒构建/83任务、lint0/5、官方v2及实际权限前后false核验通过。正式042哈希及
UID10497前后一致，清理后只有正式包，reverse为空，本地合成请求/客户端私钥已删。
[完整证据](../../../design/verification.md#20261028-ssh-phone-runtime-denied)。结论只覆盖实际
拒绝状态，不包含权限弹窗按钮交互、手机物理锁屏或Doze，继续human-required/父AC开放。

## 2026-10-28 开发板通道关闭：源码与实际证据复核

独立reviewer确认通道模式实际API28+/importance0前置条件、两拒绝flag互斥、无设置修改，
API30不检查不存在的运行时权限；保持真实Flutter sink直到应用内停止/CPU锁释放。
同一严格源码首轮6.937秒失败保留，实际首次FGS后重要性从0变2；正常Settings重新关闭，
实际0/userLocked4/fgShown后18.907秒/status0/OK1通过。没有改变断言或推断生产修复。
reviewer实际核对构建15秒/83任务、lint/v2、四项来源/包哈希，以及正式373前后相同和
清理后包列表。完整边界见[verification](../../../design/verification.md#20261028-ssh-board-channel-denied)。
本轮已清理隔离资源；手机/API36权限拒绝、长时Doze与最终人工体验仍待验，不提交归档。

## 2026-10-28 通知拒绝夹具：本地准备检查

沿当前R8接续，独立reviewer核对`ctosSshNotificationsDenied=true`的API33+/真实拒绝与
互斥参数门槛、无权限修改、无通知查询/PendingIntent，以及真实Flutter sink保留至关闭后
才替换、同代次/锁库/CPU锁释放断言；无具体待修发现。主会话实际隔离测试构建/lint
33s/83任务通过（0错误5警告），新测试APK ec6583d7…官方v2通过，正常a43/隔离66保持。
[完整命令/哈希/边界](../../../design/verification.md#20261028-ssh-notification-denied-preparation)。
本轮没有设备执行，不把编译或旧widget结果标为API36/通知拒绝通过。历史两份/tmp目录已
不存在，旧APK哈希可核验，原始日志和source-final表不可复核。human-required与父AC保持。

## 2026-10-27 安全锁屏/真实熄屏：最终证据复核

用户本轮另行授权后，主会话在同一 `192.168.9.14:5555`、AIO-3568J/API30/arm64
完成真实安全锁屏场景。证据 `/tmp/ctos-ssh-lockscreen-20261027.QravLlGz/` 的
`android-background-live.log` 与 `android-background.log` 是同一次运行的流式/汇总记录：
方法 status0、**OK(1 test)、85.464s**，不重复计算为两次通过。

公开阶段 `screen_locked`、`screen_off_roundtrip`、`await_device_unlock`、
`device_unlocked`、`screen_lock_pass` 均齐全。对应固定源码确认使用真实 SLEEP/WAKEUP
输入及公共 PowerManager/KeyguardManager 状态，反复断言屏幕非交互、安全设备实际锁定、
库已锁、同一 SSH generation 和服务自身 CPU锁；保持熄屏至少15秒，前后实际 PTY
分段标记通过。随后正常系统解锁，应用库保持锁定、同一SSH继续；原有 Home/超时锁库、
真实 Flutter 保留输出与输入、Activity 重建、通知返回/Stop/旧 Stop 隔离仍继续通过。

设备解锁仅由主会话在本轮授权下通过正常 SystemUI 入口完成；fixture 不接收、保存或
输入设备解锁凭据，只在真实 SystemUI 密码/PIN控件可见后发布公开就绪阶段，并等待
公共锁定状态解除。没有伪造锁定字段、修改安全策略或绕过认证；本 reviewer 未读取或
处理解锁凭据，也未进行设备/构建操作。

独立核验 `source-final-sha256.json` 的23份当前源码/测试全部一致。正常 APK仍为
`a43be2511e4b44f2aff6b0c348bb0cf548abccf2f68d20ba0b2fbbed2d2ac591`，隔离应用仍为
`66e1009c4085561655b843c0eb327777ec2fd5614f931ce79483fa536b4b3542`，仅instrumentation
扩展后的测试 APK为 `4d712a28c9695f8f765a72f97957b967ad239e09456009d5e926ad2e3256f5fb`，
与实际本地产物哈希一致、官方 v2 验签通过。`android-physical-lock-build.log`：111 tasks、
15s通过，隔离 lint通过；复用此前已核验的原生生成物，生产源码/原生没有为了本次通过
发生变化。

`device-cleanup.json` 为 passed=true、formal_apk_unchanged=true、请求/容器/网络已清理、
cleanup_errors=[]，安装包列表恢复到仅正式 `im.majo.ctos`；`owned-adb-cleanup.json`
确认本次自有进程停止且全局 ADB未变。正式设备仍保留旧 `373f7161…`，并非本轮新版安装。

这次补齐了该 API30 开发板真实安全锁屏/短时熄屏验收，替代下方10-26时“物理锁屏未验”
的当前边界。**长时 Doze、API36/手机、通知权限拒绝、最终人工观感仍未验**，审阅分类
继续 human-required、父完整AC6保持开放。前次真实UI的20.843s超时仍是原因未明的历史
失败，不因本次通过推断其根因已解决。本次无其他具体待修生产问题，`git diff --check`
通过；不推定正式安装、提交或归档授权。

## 2026-10-26 后台会话与通知 Stop：独立源码复核

复核当前 R8 合同及原生/Java、服务/MainActivity、Flutter 与测试的完整本轮差异，
保留其他任务修改。以下为本轮结果，下方均是各日期的历史记录。

已直接修复：

- `SshNativeSession` 认证后 promotion 若文件视图释放或认证来源清理失败，原先只禁止
  输出发布，其他 facade I/O 仍可查询。`requireTransportLive` 现在必须确认
  `authenticationRetired`，测试在三类失败后确认 step/status/write 全部拒绝且无原生调用，
  再由原 owner 清理。取消场景保留 CANCELLED，其他失败保留阶段拒绝。
- 旧连接的迟到取消可调用旧 owner 的生命周期回调，原通知更新/释放只看全局当前会话。
  服务 lifecycle 现在捕获并校验精确 owner；已释放 owner 不再发布 Stop 生命周期。
  新 JVM 回归确认 factory 的迟到取消不会再发布通知状态或创建/关闭额外资源。
- 返回通知的 Intent.data 会由实际 Flutter embedding 当成路由处理。返回动作改用会话
  特定 MIME discriminator 且 data=null，保持 immutable PendingIntent 身份隔离；隔离
  Android 场景补实际 resumed Activity 的 data/MIME 断言。Stop 的精确会话 URI 保留。

源码核对通过：只有实际 SUCCESS 才释放 native view borrow 并保留精确 transport claim；
认证前 role/view/selection/deadline 不放宽，认证后新秘密/配置/信任 callback 拒绝且不会
因正常拒绝毒化已建 shell。PID/UID、原线程、角色/claim、会话/KEX/取消和关闭重试保留。
服务独立、非导出、非 sticky，通知无账号/输出，Activity 仅订阅，RAM 输出有界且关闭擦除；
snapshot 重置 UTF-8/视图/草稿与排队输入，回放时禁自动终端响应，通知权限拒绝保留
应用内关闭。App/Root 原生命周期保持。新 backend spec 已同步相关执行合同。

实际检查由主会话串行执行；本 reviewer 未并行启动 hako 或访问设备：

- `flutter-final.log`：analyze 无问题；完整 Flutter **279 通过**。
- `android-final.log`：95 tasks、1m32s，JVM **765/0 failures/0 errors/0 skipped**，
  role/view/callback 门禁、Android lint 与正常 APK 构建通过。
- `native-arm-final.log`：同一严格构建的实际静态 ARM/QEMU 合成矩阵 **1,758,973 checks**
  通过。初次 sandbox 初始化失败和60秒超时不计通过，最终240秒范围的完整结果单列。
- 本轮差异 `git diff --check` 通过。

主会话最终补验：`android-final-fixture.log`正常92任务/1m39s、隔离114任务/33s通过；
最终archive重建的ARM程序再次1755235 checks通过，23项inputs无漂移。
`android-normal-aligned.log`89任务/10s复用已验证原生生成物对齐封装通过；正常/fixture
SSH库字节一致，APK均官方验签通过。正常a43be251…、fixture66e1009c…、test adedeb63…，
完整hash见design/verification.md#20261026-ssh-background和`apk-final.json`。
最终23份变更source/test已冻结核验（追加共享UI helper所属测试源），最终test官方v2
验签通过。证据目录 `/tmp/ctos-ssh-background-20261026.3D0HonR1/`。

### 当前授权开发板实测与最终证据复核

主会话在本轮明确授权的 `192.168.9.14:5555` 重新核对 AIO-3568J/API30/arm64，
使用隔离包、临时密钥与独立 SSH 服务；未读取个人凭据。reviewer 仅检查源码和本地
证据，没有自行运行构建或设备操作。

| 实际运行 | 结果与边界 |
| --- | --- |
| 首轮 bridge/background | 在重建后立即检查 RUNNING 时失败，不计通过；修正为等待真实 Dart EventChannel 订阅后仍由实际服务 snapshot 提供状态，没有伪造 running |
| `device-final/android-background.log` | 方法 status0、OK(1 test)、14.833s；真实 Home、2秒后台库超时、锁库后命令、实际通知返回、同代次 Activity 重建、snapshot、Stop/CPU锁释放及旧 Stop 隔离通过 |
| `device-ui/android-background.log` | 扩展真实 Flutter 场景首轮20.843s失败：等待“关闭会话”超时，status-2；原因仍未确定，不推断为焦点/时序根因，也不计通过 |
| `device-ui-diag/android-background.log` | 同生产应用/原生字节、仅追加安全诊断的测试APK，方法 status0、OK(1 test)、18.802s；真实 Flutter 保留输出、重建后实际输入执行/输出、概览导航再由通知返回均通过，并继续通过原 bridge/Stop/旧通知场景 |
| `device-ui-repeat/android-background.log` | 同一诊断测试APK重复运行，方法 status0、OK(1 test)、19.211s，完整相同场景通过 |

两次扩展通过均使用正常 APK `a43be251…`、隔离应用 `66e1009c…` 对应同一生产源码，
测试 APK 为 `d1d4aacf76a90f2f1170c3ccc2c19affdd995d918752b9d8b5643660a823e151`。
两份 `apk-final.json` 的 SSH native 字节相同，22项原冻结 source 无漂移。新增两份
instrument 文件只扩展真实 UI 验证及诊断；没有生产修改来换取后续通过。

诊断源码复核：snapshot 只记录允许的状态、代次、字节数和合成 marker 是否存在，临时
snapshot副本/marker字节 finally擦除；不记录正文、命令或凭据。失败截图限单个自有
fixture前台、捕获前后均核对包名，写自有固定文件，不遍历其他应用。诊断异常不替换
原始失败，原断言照常重新抛出。真实 UI 断言在任何重建后 sink 拦截之前执行；输出标记
不能由命令回显满足。没有跳过或放宽原 snapshot、库锁定、命令、代次及 Stop 断言。

`device-final`、`device-ui-diag`、`device-ui-repeat` 的 `device-cleanup.json` 均为
passed=true、正式包保持不变、请求/容器/网络清理完成、cleanup_errors=[]，包列表恢复到
只有正式 `im.majo.ctos`；各自 owned-adb-cleanup 均确认自有进程停止且全局 ADB 未变。
正式开发板仍为原 `373f7161…`，不是安装了本轮正常新版。

最终源码/本地与上述设备证据无其他具体待修生产问题；初次扩展 UI 超时仍作为未解释
历史失败保留，不能由重复通过声称其根因已解决。审阅分类仍为 **human-required**：
实际物理锁屏/熄屏和长时 Doze、API36设备、通知抽屉人工操作/权限拒绝及最终人工观感
未在本轮完成，不能由 API30 的 Home/超时锁库替代。父完整 AC6 保持开放；本节不推定
正式安装、提交或归档授权。

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

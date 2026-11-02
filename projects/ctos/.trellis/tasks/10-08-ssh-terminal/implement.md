# SSH 终端实施计划

## 2026-11-02 接续：主机信任管理

状态：本地与指定板隔离JNI3/真实换键KEX/PTY及独立检查通过；用户“通过；可以继续；”
确认新增页面人工体验通过，沿原1008核对下一增量。
原task保持current/in_progress，不新建/提交/归档；正式更新已另获授权并完成，不操作真实账号。合同见
[主机信任管理](../../../design/ssh-host-trust-management.md)，验收HT1–HT8。

1. [x] 恢复最后已选增量，核对双对象、真实lookup、parser上限、原拒绝与现有票据。
2. [x] 补齐PRD/设计/执行顺序/上下文：分组查询与忘记、受保护政策阻断、原字节保留。
3. [x] 用户批准本轮方案后恢复原task指针，分派trellis-implement；prompt以
   `Active task: .trellis/tasks/10-08-ssh-terminal`开头，禁止子代理重复分派。
4. [x] Native先冻结有界版本化wire，实现真实parser查询/候选与span保留；补实际键/
   匹配/限额/故障/零外部效果测试。明确接入Gradle和固定材料runner，不修改pinned upstream。
5. [x] Java/JNI实现owned副本和严格wire、两种对象身份、source/candidate生产校验、
   单replacement/独立ticket/原context一次写入；覆盖跨种类、重放、取消/迟到、源变化/
   后台锁库和uncertain。
6. [x] Flutter实现typed四操作、资料页入口/表单/分组/本次预览确认，完整指纹和受限原因、
   原目标代次/回执保护；覆盖窄屏/横屏/键盘/大字和取消。
7. [x] 专项通过后执行全量Flutter/JVM/native旧矩阵、normal/fixture构建lint，核对
   frozen源码/APK来源与哈希；准备Android JNI/合成换键回归，编译不记作运行。
8. [x] trellis-check独立检查最终依赖和实际证据PASS，同步spec/设计/验证/changelog。
   用户另授权后已完成指定板隔离平台验收和清理，又另授权保留数据正式更新并启动；人工体验通过。
9. [x] 人工体验：正式5e70交付后用户反馈“通过；可以继续；”。下层异常场景以各自测试
   证据为准，不扩写用户实操细节；当前提交清单待批准，不归档完整父任务。

验证命令已执行；正式arm64产物另由Flutter显式target-platform构建：

```sh
./hako flutter --version
./hako flutter analyze
./hako flutter test
./hako bash -lc 'cd android && ./gradlew :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease :app:assembleReleaseAndroidTest --console=plain'
./hako flutter build apk --release --target-platform android-arm64
./hako bash -lc 'cd android && ./gradlew -Ptarget-platform=android-arm64 -PctosSecretsUiFixture=true :app:assembleSecretsFixtureRelease :app:assembleSecretsFixtureReleaseAndroidTest :app:lintSecretsFixtureRelease --console=plain'
```

native入口 `android/ssh/android-material-inspector-build.sh --output <新目录>` 已补
`--host-trust-test` / `--static-host-trust-test`；最终静态trust及旧pair/private/certificate
均已构建并由QEMU运行，[命令/数量/产物与限制](../../../design/verification.md#20261102-ssh-host-trust)。
ARM C-only/QEMU、Android JNI、真实KEX和人工体验分列。临时证据用独立
`/tmp/ctos-ssh-host-trust-*`；已授权隔离安装并清理，正式APK已另授权install-r更新，
不替换dist、不访问真实key/账号，不提交/归档。
回退只撤入口/API，不恢复用户已忘记的旧信任，不改变其他库数据。

## 2026-11-02 人工验收通过

用户明确反馈效果通过、实际remote连接成功。本轮交付体验门通过，原本地／正式安装
证据保持，未假称agent执行合成平台方法或访问实际账号。用户随后明确“可以提交”，已
批准Phase3.4的44文件精确清单；父完整AC仍开放，不归档或自动开始下一项。

## 2026-11-02 当前修正：config-first与显式key

用户已批准本轮行为，[合同](../../../design/ssh-config-identity-selection.md)及研究已冻结；
不再停留于移除目录。原配置／用户key／真实账号不修改；诊断仅保留脱敏结构。

- [x] 产品原生IdentityFile-hint审计／direct类型与旧严格模式隔离；真实native及新wire回归。
- [x] import／stored validator／引用刷新兼容旧STATIC边；library及两处connector显式key。
- [x] typed identityHints、config-first列表、单／多key明确选择及生命周期／16张布局回归。
- [x] 40定向／802全量JVM、86定向／324全量Flutter、native资源／direct／catalog、构建lint／v2及独立PASS。
- [x] spec／设计与真实证据已同步，正式eed028f0…已保留数据安装同板并启动；父完整AC开放。

证据`/tmp/ctos-ssh-resume-20261102.Mtii8sL0`，独立`review-config-identity.md`最终PASS。
新合成平台方法仅编译，未安装隔离包／操作真实key或账号；实际导入和人工体验待重试。
源码19项／native输入36项核对，最终normal／fixture冻结同一4feccdec…库；首次产物差异
与测试夹具／命令失败均保留。安装UID／dataDir／首次安装保持，专用ADB15047已停。

## 2026-11-02 正式保留数据更新

用户“更新上去”后，已将已审阅e1241897…install-r更新指定开发板并启动；设备hash与
UID／dataDir／首次安装保持核对通过，旧公开b2dAPK已备份，专用ADB15045成功停止。
`design/verification.md#20261102-ssh-single-config-formal-install`记录新证据；未操作真实
库／密钥／账号或SAF，新增范围人工体验及提交仍待反馈，父完整AC开放。

## 2026-11-02 用户要求撤下目录功能

本轮授权为单config读取多个Host及独立私钥导入；不做路径适配。沿原父任务实施，不另建
任务，不把先前目录测试通过等同人工验收。

- [x] 移除目录页／API／宿主树导入与专用provider／测试，保留单文件路径、库内资料及共享修正。
- [x] 简化配置导入文案，验证一个config的多个连接项与独立私钥路径，不添加自动适配。
- [x] analyze／41定向／318全量Flutter、799 JVM／lint／正常及fixture构建／v2、3736及71923原生／8张布局。
- [x] 独立check最终PASS，已同步需求／设计／spec与真实检查；本轮无设备／真实账号／提交授权，父完整AC开放。

证据 `/tmp/ctos-ssh-resume-20261102.Mtii8sL0`，正式e1241897…、fixture496da199…、
teste9e04db9…。旧目录临时脚本移出后analyze通过；未将删除前测试或旧设备结果记作本轮
通过。详情见 `design/verification.md#20261102-ssh-single-config`。

## 2026-11-02 恢复目录增量

已恢复本父任务current；用户询问批量导入内容，已说明支持集合和显式排除范围，未推定
新增人工验收或提交许可。定向14 Flutter／20 JVM重新执行通过，三个本地产物哈希仍与
10-30相同。旧/tmp原始证据已不存在，设备／安装／审阅／布局无法重新核对；
本轮证据和限制见 `design/verification.md#20261102-ssh-directory-resume`。
R5主机信任恢复仅开展下一步源码研究，不启动未经选择的新增量。

## 2026-10-30 当前接续：direct资料目录批量导入

已验收密钥增量commit360cca2，日志e0f866f；用户要求继续原父任务。按
`design/ssh-directory-import.md`和`research/next-increment-20261030.md`实施R7下一切片。

- [x] 有界SAF树快照、原Context／期限／取消、实际类型及逐密钥解锁、明确排除阻断项。
- [x] 整套候选／严格native闭包、替换／真正unchanged、一次bundle提交与回执。
- [x] typed预览与文件选择／解锁／冲突确认、保护窗和布局回归。
- [x] 819 JVM／331 Flutter、实际native／合成provider／两次连接验证和事实记录。
- [x] 最终源码／产物／原始证据及布局独立审阅，review-final.json PASS，无未决发现。
- [x] 用户回复允许后保留数据更新指定板正式b2d91de2…并启动，核对hash及包身份保持。
- [ ] 新增目录页面人工体验；安装许可不等于提交许可。

证据 `/tmp/ctos-ssh-directory-20261030.bmVGMsZS`，正常b2d91de2…／fixture5b7dfa17…／
testf5903623…。指定板方法31.649秒OK1，正式fc98和UID10131／包元数据保持，隔离资源
及专用ADB15041／15491已清。28张实际字体布局通过；详情和未验证项见
`design/verification.md#20261030-ssh-directory-import`。随后已正式安装并启动，证据为
`design/verification.md#20261030-ssh-directory-formal-install`。本增量未提交，父完整AC开放。

不放宽120秒BundleContext；不导入来源主机信任／证书，不读取真实账号或改外部原件。

## 2026-10-30 用户验收通过，授权提交并继续

用户“功能通过；可以提交并继续”，当前正式fc98密钥管理／删除／视觉修正人工门通过。
当前变更按一个密钥生命周期增量提交，排除其他任务和邻项目；父完整AC保持开放，不归档。
提交后接续下一项已确认需求；提交前最后full-scope复核PASS，31项来源与已审阅fc98一致。

## 2026-10-30 体验反馈：私钥卡片操作区

用户指出按钮突兀。本轮仅调整 `library_page.dart` 的操作布局／局部圆角：内容宽度Wrap、
间距12／换行8，48dp触控；保留全部回调、准入和确认。主会话运行Flutter检查／真实字体
渲染、正常APK构建验签，并沿用户已允许的同功能保留数据更新流程交付；不重跑未改宿主
与原生矩阵，不将前序799 JVM／42.572秒记作本轮新执行。

结果：317 Flutter／analyze、24张实际字体及hover／大字展示、正常构建lint／v2通过，
独立视觉PASS；fc98已保留数据更新同板并启动，UID／dataDir／首次安装保持。证据
`design/verification.md#20261030-ssh-key-card-actions`，人工体验仍待，不提交／归档父任务。

## 2026-10-30 接续：私钥删除

合同 `design/ssh-key-deletion.md`；研究 `research/key-deletion-20261030.md`。
保持已有加密私钥改动及人工体验待办，不新建／归档父任务，不修改其他任务或邻项目。

- [x] 精确删除 proposal／公开影响预览／原票据一次提交与取消，清理关联公钥、口令和 KEY Accounts。
- [x] 保留 CONFIG 字节、窄 stored 缺失身份规则、全有效身份拒绝、重导入原子修复引用。
- [x] Flutter 删除确认与终态保护，typed API、缺失路径说明、生命周期和展示回归。
- [x] 799 JVM／317 Flutter／指定板实际native与SSH链路42.572秒、构建lint和独立检查。

最终正常APK0e8e63e0…，fixture7ac3c57b…、teste9e04db9…；隔离阶段正式f26a保持，资源全清。
`design/verification.md#20261030-ssh-key-deletion`记录实际范围、三次前序失败及最终通过；
review-final.json独立PASS。随后用户回复“可以”，正式0e8e已保留数据安装同板并启动，
UID／dataDir／首次安装时间保持；新增交互待人工体验，不提交或归档父任务。


## 2026-10-30 当前：加密私钥／可选口令保存

- [x] 原生公钥导出支持实际口令、加密标记和错误分类；保留原无口令 ABI，覆盖原矩阵。
- [x] 宿主原始文件预览／原期限解锁、加密图准入、当次与已存口令最终证明绑定。
- [x] 同库原子保存／单条与全部忘记、替换失效、不影响 TOTP／密码／无关身份。
- [x] 受保护 Flutter 导入与两种连接、默认不保存、忘记确认及重试／取消／后台回归。
- [x] 主会话串行运行 focused/full JVM、Flutter analyze/test、native ARM 矩阵和构建/lint。
- [x] 独立 Trellis check，更新 design/spec/实际证据；当前授权板隔离方法通过并清理。
- [x] 用户明确允许后保留数据更新指定板正式f26a并启动，核对hash／UID／数据目录／首次安装时间。
- [ ] 新增 Flutter 交互的人工体验反馈；安装许可不等于验收或提交许可。

合同为 `design/ssh-encrypted-key-connect.md`，研究在 `research/encrypted-key-path-20261030.md`。
本轮不修改其他 planning task 或邻项目，不提交／归档父任务或替换 dist。

当前结果：304 Flutter／786 JVM、ARM材料21005／客户端1759380、五类独立加密私钥
互通、最终arm64/APK/v2/新JNI审计及指定板完整隔离方法40.512秒通过。来源与证据独立
复核通过，隔离阶段正式7c38保持；随后按当前允许保留数据安装正式f26a并启动。
详见 `design/verification.md#20261030-ssh-encrypted-key` 及其中的后续正式安装记录。

## 2026-10-30 正式交付：保留数据安装及人工验收通过，获准提交

用户对“保留数据更新正式APK→实际体验→确认后提交当前增量”回复可以。正式7c38d4dc…
已install-r安装到指定板并启动，设备哈希匹配，UID10131/dataDir/首次安装时间保持；未卸载
或清数据，未访问真实秘密/账号。证据/tmp/ctos-ssh-formal-install-20261030.Eoz7sCcb含旧APK
备份与前后包状态。用户已回复“效果可以；提交”，体验门通过，commit_authorized=true条件满足。
提交准备为完整R8依赖+新锁屏断开/一键重连同一增量，排除app-detail-fixes task/README三行
及所有邻项目修改。父完整AC开放，不归档。下方“正式未更新/未授权”仅为安装前历史状态。

## 2026-10-30 当前：指定开发板闭环通过，禁止再以手机/版本矩阵阻塞

用户明确指定192.168.9.14完成当前验收，指出不应将统一锁屏锁库/SSH一起断开模型拆成
各系统适配或强制手机补验。已重新核对AIO-3568J/API30，使用完全相同的冻结源码及两个
隔离APK；actualSafKeyImportManualThenConfigAndStaleBinding实际70.244秒/OK1/status0。
真实锁屏锁库并关闭旧SSH/CPU锁释放、正常系统解锁后无自动连接、实际Flutter提示和一键
重连建立新代次/KNOWN/PTY、旧receiver隔离，以及Home/重建/通知Stop/config回归均通过。

证据/tmp/ctos-ssh-board-lock-close-20261030.q0wCJO1f，design/verification.md当前10-30节。
独立reviewer复核阶段/源码/哈希/成功日志无发现。所有本轮隔离包、请求、reverse、容器/
网络/精确代理、合成私钥/JSON、专用ADB均已清；正式373包前后相同、UID10131保持。
无源码变化/未重复构建，本地293 Flutter/775 JVM仍为10-28记录。当前切片进入人工体验/
正式交付决策，手机或逐版本测试不再是当前前置，未测不推断不支持。父完整AC继续开放，
不新建/归档、不自动覆盖正式APK或提交，commit_authorized仍false。

## 2026-10-30 早段：原手机入口离线（随后已改用开发板完成）

用户要求继续。当前任务仍为本父task；逐项核对10-28最终来源/APK表，22项全部一致，
git diff检查通过。未修改源码、未重复构建或将旧293 Flutter/775 JVM写成今日重跑。
只重试原192.168.10.9:37821入口，仍No route to host；已请求手机当前无线调试连接地址。
新版尚未安装、实机短测未运行，未重建合成服务或更改Thanox/VPN；后续按下方既有步骤
接续隔离验收，不重启锁屏保活/冻结/Doze调查。

## 2026-10-28 当前：锁屏结束连接，用户主动重连

用户明确选择此行为，覆盖此前锁屏保活目标。停止冻结写入者/Thanox排查，不再要求模板
代码或停用规则。保留普通Home后台及Activity重建会话，锁屏停止SSH；精确断开原因和
主动重新连接入口，正常解锁库和新认证/信任，不自动重连或重放命令。tmux/Mosh仅讨论，
未授权部署远端组件。最终源码已冻结：分析/65定向/293全量Flutter、29定向/775全量JVM、
normal/fixture/test构建lint及官方v2通过，独立源码/证据复核无剩余发现。正常APK7c38d4dc…、
fixture5e0b9ef5…、testc665402e…，来源哈希与日志在新证据目录。未变原生输入核对相同。
手机安装前已离线，原入口重连No route to host，新版未安装，尚无新行为设备通过记录。
当前仅等待可用手机ADB入口；不重跑冻结归因或改变Thanox/VPN。commit_authorized仍false。

本次新验证证据/tmp/ctos-ssh-lock-close-20261028.wh75chqr；下列临时资源已全部清理，与旧调查分开：
容器/内部网络ctos-ssh-lock-close-20261028（10.160.240.2:2222），仅loopback15469的socat；
client_key/request.json及六个容器/挂载合成hostkey私钥已删，容器/网络/精确socat已移除，
15469无监听，专用ADB15038已停。安装前离线，未创建新版远端请求或reverse、未安装两包。
此前重连核对同一PLR110/API36及正式042基线。后续拿到当前入口先核对身份和源码/APK表，
只重建合成服务/请求/专用reverse并安装两个隔离包，不重跑本地全套除非源码变化。
正常UI为测试包开启通知后，运行actualSafKeyImportManualThenConfigAndStaleBinding，参数
ctosSshBackground/ctosSshPhysicalLock/ctosSshManualDeviceUnlock/ctosSshScreenOffDiagnostics=true，
ctosSshResetOwnedFixtureVault=true及新的合成ctosSshKeyRequestFile；禁止旧HoldSeconds/
ForegroundIdleSeconds参数。手机仅用户本人正常解锁，结束再清理并核对正式包。

上一调查已结束：确认UID10499父cgroup.freeze=1、子有效frozen=1，正常唤醒后解除；
整轮124.469秒失败，15秒hold实际100秒，未观察Doze。触发者未定，不再追问。
试验性六类errno映射因上游EIO/携带errno不可靠已撤下，JNI回到原固定STEP；保留安全
Vault公开错误码透传及夹具修复。最终18定向/768全量、normal/fixture构建lint和三APK官方
v2通过，核心输入未变。旧测试两包/请求/reverse、容器/网络、两精确socat、私钥/request
和专用ADB15038均已清；正式042哈希及UID10497保持。下面精确资源列表仅作已清历史。

## 2026-10-28 手机真实锁屏/自然休眠：历史调查（已停止并清理）

最新JNI固定六类诊断：79定向/771全量、normal/fixture构建lint与三APK官方v2通过，
核心C/静态库/config对象未变且hash核对一致。仅更新两个隔离包；新应用APK3e79e9d7…
包含六类token。手机同一15秒亮屏/15秒熄屏轮99.857秒失败，hold实际67秒，deep idle
未观察；清理前首个同代次回执仍SSH_SESSION_STEP，未命中六类。约50秒停滞先取证再
WAKEUP结束；未开始20分钟观察。当前reviewer只读追踪剩余通用错误路径，不立即重测。
日志android-jni-category-physical.log、jni-category-terminal-final.txt、
source-apk-jni-category-final.sha256和jni-full-results.json；详见design/verification当前调查节。

本轮临时资源（清理只针对这些精确目标）：专用ADB15038/手机192.168.10.9:37821；
Docker容器和内部网络同名ctos-ssh-phone-lock-20261028（内部10.160.224.2）；
socat127.0.0.1:15459及192.168.9.5:15479（仅range192.168.9.5/32）到内部2222；
reverse15459；remote/data/local/tmp/ctos-ssh-key-phonelock20261028.json与
ctos-ssh-key-phonelockdirect20261028.json；独立包im.majo.ctos.secretsfixture及.test。
本地证据目录含client_key/request.json/request-direct.json合成秘密，结束删这三个，保留日志。
sshd仅本轮容器启用ClientAlive10/Count2/DEBUG3，删除容器即可回收；正式sshd未改。
Thanox白名单为用户本人操作，agent不修改/恢复；测试后提醒用户可移除该测试包条目。

最新有效控制：加入ctosSshForegroundIdleSeconds=15后，亮屏实际空闲15秒、前后PTY通过，
服务器08:37:08.808发送channel keepalive(type98)、08:37:08.822收到type100应答；随后
锁屏后才失效，整轮137.03秒失败。不存在已证实的“连接总在10秒到期”结论，源码扫描也
未发现无条件10秒已认证期限；目前范围收窄为锁屏后的调度/生命周期/既有链路。

用户截图/tmp/tmp/Screenshot_2026-10-28-16-43-09-09.jpg显示测试包BackgroundTask允许，
SmartFreeze/XMode/CleanTasks关闭、ResidentApp开启；不能再以未加白名单解释。截图非
自动模板代码，未据此推断具体暂停机制。只读系统事件只取得finished-inst结束进程记录，
尚无提前进程被杀证据；screenoff与背景超时取消链源码审阅未发现额外已建立SSH取消路径。
下一步仅在现有terminalSink记录精确代次首个closed/failed公开原因、在清理前固化；不改
Thanox/VPN或系统策略，不再要求用户停用模板。20分钟观察继续待短测可靠后进行。

后续状态：用户报告Thanox模板会在锁屏时暂停新后台进程，已本人将精确测试包加入白名单，
并确认Shell/ADB不受暂停；加入后仍复现停滞，不能将后续结果直接归因于Thanox。用户明确
无法停用整条模板（会破坏状态一致性），禁止再次要求停用或擅改模板/VPN。维持现状诊断。

已取得有效LAN前台控制2.699秒/OK1；受限监听最初只允许手机源10.9而实际来流源为本机9.5，
原策略拒绝连接；仅按观察源9.5/32重建监听，无路由/VPN更改。手机存在Clash VPN且不可bypass。
先前短测与ADB reverse的两次keepalive type98未获应答并30秒超时，不能混称global80探针。
直接LAN锁屏轮也未通过：15秒目标实际95秒后退出，服务端约10秒后见peer close（本轮不是
keepalive超时）。20分钟自然观察尚未开始，deep idle未观察到。

夹具修复：原始错误保留、cleanup异常作为suppressed且所有自有清理继续尝试；显式
ctosSshResetOwnedFixtureVault=true仅在隔离身份/UID/无Activity无会话/三固定regular文件
预检后删除本轮自有库，随后原fresh检查不变；实际重置1文件5408字节后LAN前台控制通过。
当前源码2714ee3f…/测试APKbf436a4f…，16秒构建/lint0错误5警告及官方v2通过。
下一步：不改任何模板，以亮屏15秒空闲控制检验是否约10秒连接本身失效，再与锁屏区分。

用户要求继续，选择本人手动正常解锁。当前目标仍为192.168.10.9:37821/PLR110/API36，
实际未充电、mForceIdle=false，未更改锁屏/电源/网络策略；只用隔离包及合成SSH服务。
本轮证据/tmp/ctos-ssh-phone-lock-20261028.aQnbyEbK，正式042包保留。

- [x] 准备独立服务/包，正常系统界面授予测试包通知权限；fresh-data失败一轮保留。
- [x] 手机禁止shell pm clear；使用正常系统Settings只清测试包数据，随后正常Settings恢复测试包通知。
- [x] 15秒物理测试首个熄屏PTY成功；第一轮主会话过早WAKEUP导致96.061秒失败，用户已正常解锁。
- [x] 新增15..1800秒自然熄屏观察/实际idle采样，14秒构建/lint/v2及独立源码复核通过。
- [x] 15秒新夹具在hold_started后停滞；只读记录FGS/自身CPU锁/isFrozen=false、系统INACTIVE。
  主会话按超时WAKEUP后hold跳至214秒，整轮220.531秒失败，未计通过。
- [ ] 使用显式opt-in的自有测试线程诊断定位阻塞调用；不改严格锁屏/锁库/会话断言。
- [ ] 根据诊断继续真实锁屏和自然20分钟观察；目前未开始20分钟，不据熄屏时长声称Doze通过。
- [ ] 清理本轮隔离资源、核对正式应用，更新最终验证结果。

用户当前已再次正常解锁并同意开始诊断。agent不处理手机凭据。短测完成就绪前不要求
用户再次解锁；诊断若超时，先保存线程/时钟证据，再正常唤醒结束失败轮。

## 2026-10-28 手机通知权限拒绝状态补验

- [x] 用户提供当前192.168.10.9:37821入口，重新核对PLR110/API36，隔离两包正常安装。
- [x] 实际POST_NOTIFICATIONS=false，原严格夹具首轮19.065秒在概览导航失败，保留结果与原APK。
- [x] 仅夹具补自身真实IME状态检查/正常BACK及导航可见等待，独立复核及14秒构建/lint/v2通过。
- [x] 重跑10.594秒通过，日志确认实际键盘展开→收起→导航可见；完整真实Flutter关闭及锁库保持。
- [x] 核对正常/隔离应用APK不变，隔离资源清理，正式042包/UID10497与前相同，证据及spec同步。
- [ ] 权限弹窗按钮、手机物理锁屏、长时Doze和最终人工体验；保留父完整AC及提交/安装授权边界。

实测与首轮失败见design/verification.md#20261028-ssh-phone-runtime-denied。本轮没有产品代码
变化，未将历史Flutter/JVM/原生检查记作重新运行，也未更新正式应用或提交归档。

## 2026-10-28 接续：通知权限拒绝验证

后续用户指定同一Android11板，追加独立通道关闭场景并完成实测；它不替代API33+权限拒绝。

- [x] 当前核对AIO-3568J/API30、隔离安装，通过正常Settings关闭测试SSH通道。
- [x] 同一严格测试18.907秒通过：后台锁库/重建/真实Flutter/应用内关闭/CPU锁释放。
- [x] 首次FGS将通道0恢复2的一轮失败保留，首次展示后正常Settings再次关闭、实际0/locked4后复测。
- [x] 隔离包/请求/reverse/容器/网络/socat/专用ADB清理，正式373包哈希前后相同。

本次15秒构建/lint、官方v2验签、独立源码与证据复核通过。详见
design/verification.md#20261028-ssh-board-channel-denied；手机/API36/长时Doze与人工体验仍待验。

用户要求继续当前进行中 task，恢复本父任务指针，继续 R8 验证，不新建任务。

- [x] 核对现存正常/隔离/测试 APK，三个哈希与10-27记录一致。
- [x] 确认历史 `/tmp` 原始证据目录已不存在；不宣称重新核验历史源码冻结或原始日志。
- [x] 增加独立 opt-in：API33+ 实际拒绝通知权限下的连接、后台锁库、重建、真实终端与应用内关闭。
- [x] 编译隔离测试包、lint及独立检查；记录本轮来源、产物与未运行边界。
- [ ] 确认当前手机目标及本轮隔离安装/通知权限测试授权后，重建临时合成SSH运行环境并实测、清理。

本轮本地准备不推定新的设备/账号操作授权。手机/API36、长时Doze及人工体验仍待验，
不自动安装正式应用、提交或归档父task。

## 2026-10-27 真实熄屏与系统解锁补验

- [x] 用户明确要求继续同板测试；正常系统凭据解锁，不在测试请求/文档/日志保存凭据。
- [x] opt-in真实SLEEP/WAKEUP、Power/Keyguard/vault三重状态及15秒熄屏PTY/代次/CPU锁。
- [x] 安全系统凭据控件就绪后由主会话正常输入；解锁不自动解锁库，原SSH继续。
- [x] 同一整轮实际Flutter恢复/新输入输出/重建/通知Stop与旧通知隔离，85.464s通过。
- [x] 测试包/请求/容器/网络/私有ADB进程清理，正式373包和生产源码保持。

111任务/15s隔离构建lint和新test4d712a28…官方v2通过；原279 Flutter/765 JVM/ARM按无
变化生产源码保留。证据design/verification.md#20261027-ssh-physical-lock。手机/API36、
深度Doze及人工审阅/正式安装/提交仍按各自授权，父完整task不归档。

## 2026-10-26 当前：后台会话与通知 Stop

用户选择本切片，沿原父 task 实施，不新建；合同见 design/ssh-background-session.md。

- [x] 恢复历史、明确 R8，核对 Activity 与库锁定导致断线的双重所有权问题。
- [x] 原生已认证权限与秘密读取生命周期分离，材料释放及认证前失效回归。
- [x] 独立 SSH 前台服务、唯一 owner、精确通知 Stop、失败与关闭回收。
- [x] Activity 订阅/重建、有界输出恢复、Flutter 事件与通知导航。
- [x] JVM/native/Flutter、lint、构建及独立 Trellis check。
- [x] 当前授权Android11板隔离后台锁库/重建/通知及实际Flutter恢复/输入/导航；同步记录。
- [ ] 手机物理锁屏/API36通知权限、ROM边界及完整父AC；正式安装/人工审阅/提交另按授权。

本轮本地实施已获选择，设备安装/真实账号不沿用历史授权。human-required：服务、
通知与锁屏存活须实机验收后再提交；父完整 AC 保持开放。

本地最终：279 Flutter/analyze、765 JVM、最终archive ARM1755235、正常/隔离构建lint及
签名通过。APK a43be251…，完整哈希与初轮失败/修正见design/verification.md#20261026-ssh-background。
本轮设备授权后，bridge14.833s及真实Flutter18.802/19.211s通过；一次扩展UI控件等待
失败尚未定因，详列证据不计通过。临时资源全清、正式373包不变；未替换dist或提交。

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

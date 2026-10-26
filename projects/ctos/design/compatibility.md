# Compatibility

2026-10-26 Android16 PLR110实测限制：App硬锁页64KiB，匿名memfd可创建/截断而
fchmod0600返回EACCES。修正detached索引固定开销及内部FD的600假设，仍先锁页/
DONTDUMP后复制、严格anonymous/current-UID/zero-link/exact-size/CLOEXEC且不导出。
实际mode如实报告，SCM FILE_LOAN真实600要求保留。
[最终本地、开发板及手机回归](verification.md#20261026-phone-ssh)通过；最终042版在
指定PLR110保留数据更新后，现有私钥登录真实目标与PTY输出通过，并获用户人工验收。
该结果限于此次实际设备、身份和目标，不推广为所有Android16机型或认证/config能力。

2026-10-26 接续：[config 与无口令私钥](ssh-key-config-connect.md)新增同一加密图内的
CONFIG、真实私钥/公钥、KeyBinding/KEY Account 与直接连接，保留前序密码资料及 TOTP。
新增原生 public export/direct-plan wire，复用同一固定 OpenSSH；支持的配置由实际原生
选项求值，未消费选项明确拒绝。加密私钥、证书/agent、ProxyJump、转发和后台能力仍待
父规划落实；复杂整库恢复不因新增 validator 图支持而开放。
[本轮设备验证](verification.md#20261026-key-config-device)在指定Android11/API30板通过
私钥/config及保存密码实际bridge回归，正式APK已安装；不推广为API28/其他设备、真实
DocumentsUI/个人密钥或实际账号验收。

2026-10-26 接续：[可选保存密码](ssh-saved-password.md)继续唯一 manifest/writer，允许
规范的 Account + SSH_PASSWORD 扩展既有生成配置子集；旧前台生成配置及 TOTP 保留。
多条端点密码按精确 host/port/username 隔离，默认当次不修改已存密码。
不改变 envelope/KDF/依赖或 native 协议；复杂 SSH 恢复仍受实际 metadata 审阅门限制。
[本轮本地结果](verification.md#20261026-saved-password)没有新增设备结论，也未覆盖安装正式应用。

2026-10-26：[指定Android11开发板](verification.md#20261026-password-android)的前台密码
SSH完整界面实测通过；使用同一正式Dart/Java/JNI/C代码，测试包独立UID。实际设备为
AIO-3568J、arm64-v8a、已解锁。API28兼容仍为已有编译/本地边界证据，不能把API30实测
推广为其他Android版本或后台行为验收。

2026-10-25：[前台密码SSH](ssh-password-connect.md)沿同一正式模型，支持empty/纯TOTP和
精确的应用生成CONFIG/KNOWN_HOSTS整库子集；保留无关TOTP和已确认主机键，其他SSH资料
明确拒绝而不覆盖。使用公开Os/PFD API，非22端口使用`[host]:port`主机键记录；既有
App/Root入口和terminal事件协议保留。Android实际运行结论单独记录于[验证](verification.md#20261025-password-connect)。

2026-10-25：[SSH唯一正式主线](ssh-manifest.md)取消instance格式选择和双writer，所有
构造/create/重新编码使用最新模型；内部wire tag仅用于文件识别。CTOSVLT1/crypto/KDF/
AAD/generation/12MiB不变。窄旧输入适配仅接受empty/纯TOTP，读取不写库；export保留
原envelope，正常内容修改/换口令/恢复编码正式模型。旧SSH输入拒绝，不推断关联或清库。
恢复门检查实际未审阅的关系/账号，纯TOTP/empty不因内部tag受阻。
[实测](verification.md#20261025-ssh-mainline)，无设备或真实库操作。

以下为历史兼容记录，旧双格式主线政策不再适用。

2026-10-24：[全部证书事实与签名政策](ssh-certificate-facts.md)已实现CSCF168、三份公开摘要/unsigned数值/实际record来源及现代CA签名政策，保留trust/time/principal/revocation未验证。ARM22239与旧材料矩阵、定向145/全量638 JVM、normal100全重跑/fixture111及三APK审计通过；[实测](verification.md#20261024-certificate-facts)。持久graph/账号、完整信任/reviewer/provider/恢复/connector与父AC仍开放；本地未提交，无设备/真实库账号。

2026-10-23：[全部私钥材料与凭据归属](ssh-private-material-ownership.md)已实现全PRIVATE自签/验签、v1口令唯一owner/phrase审计和password namespace/deferred target。ARM19976/独立pair13812/material7784、定向120/全量613 JVM、normal98全重跑/fixture111及三APK审计通过；[实测](verification.md#20261023-private-material)。仍不提供持久关系图/真实密码账号/完整信任/provider/恢复/connector准入，父AC开放；本地未提交，无设备/真实库账号。

2026-10-23：[显式密钥配对证明](ssh-key-association.md)已实现精确ID/所选口令、证书plain subject及固定domain实签验签，拒绝仅公开字段相等的伪配对。ARM13814/独立材料7784/fresh认证1748333、定向80/全量586 JVM、normal96全重跑/fixture111与三APK审计通过；[验证](verification.md#20261023-key-association)。报告只覆盖明确所选pairs；全库关联authority/覆盖、凭据目标、完整信任/reviewer/provider/恢复/connector与父AC仍开放。本地未提交，无设备/真实库账号。

## 2026-10-23 — CONFIG 显式资源引用

[RESOURCE_AUDIT](ssh-config-resources.md)为显式第四mode，不改变旧mode/wire。只收集显式资源声明，STATIC仅literal base/type/directrefs；relative保持CWD，缺精确I/Cert基名为KEY_RESOLUTION，不猜suffix或私钥关联。Global字面$/其他kind裸$与${区别、Certificate none字面、Revoked PUBLIC-only边界有真实矩阵。材料/签名/信任/最终消费未认证，报告admissionComplete=false。[门禁与未测](verification.md#20261023-config-resources)。

## 2026-10-23 — CONFIG 静态引用与本地效果审计

[AUDIT](ssh-config-audit.md)是独立显式模式；旧EFFECTIVE/CATALOG与CSEQ/CSES/CSCD不升级。新CSCA嵌套原CSCD，收集真实静态边/核对父直接引用，所有分支检查有限禁止效果政策；保留raw命令/argv字符串差异、固定Match语法与Include锚点，编译禁用优先。动态/非Include/值/身份/信任仍未完整，报告admissionComplete=false，生产恢复不开放。[门禁与未测范围](verification.md#20261023-config-audit)。

## 2026-10-23 — 实际清单受管配置检查

[受管检查](ssh-detached-config-managed.md)已接实际caller清单，不占live view、只复制CONFIG正文。逐root保留原始来源与原期限/取消，累计IO/行数/结果预算，不续期；路径沿用既有managed相对路径，bare Include仍锚定`.ssh/`。report恒声明only/admissionComplete=false，不能当作Include引用边、完整身份/凭据/信任或生产恢复准入。真实ARM/JVM与编译门禁通过，Android/JNI/锁页未运行；[实际验证](verification.md#20261023-detached-managed)。

## 2026-10-23 独立 native 配置输入

[独立输入](ssh-detached-config.md)已在本地真实ARM/host矩阵验证，可选实际CONFIG root并保留原始ordinal，不占活动view；原live借用、Include目录基准、声明only和raw Options边界保持。C-only尚无Java/JNI整库入口，不验证Include精确引用边或完整身份/凭据/信任，SSH恢复仍关闭。

8MiB为格式正文限额，系统锁页还需metadata/readers；额度不足明确资源失败并清理。宿主8192KiB额度下6MiB实际锁页解析通过，8MiB仅格式入场/注入失败清理；Android锁页/ROM/JNI未实测。专项1261/原配置矩阵/IO426及JVM483、normal94/fixture111/APK审计通过，[实际证据](verification.md#20261023-detached-config)。

## 2026-10-21 恢复预览生命周期边界

[当前合同](ssh-validation-lifecycle.md)只迁移preview路径；legacy validator/reviewer
可在回调前后被原deadline/cancel fence拒绝，不保证抢占单次第三方解析/KDF。
UI一次交付claim后外部回调在锁外执行；后续失效拒绝commit，但不撤回已claimed摘要。
Bridge同名外部requestId不共享私有preview owner，已返回预览沿用精确previewId取消。
其他typed/bundle仍持core monitor，尚无生产全图语义/信任provider，SSH恢复继续拒绝。
本轮Java/JVM及编译证据见[验证](verification.md#20261021-preview-validation)，不是Android
实际运行/混合恢复/真实账号或完整SSH客户端验收；native/Flutter未改，旧结果保持历史。

## 2026-10-19 实际清单离线文件检查

[材料检查器](ssh-manifest-inspection.md)直接解析候选文件副本，覆盖既有软件私钥、
公钥/证书与known_hosts所有记录；报告恒contentOnly=true/admissionComplete=false。CONFIG、
引用/身份/凭据目标、证书有效期/主体和信任语义尚未认证，SSH恢复门保持。
OpenSSH私钥CRLF仍受固定LF标记拒绝；传统PEM按固定parser支持换行。SK因固定构建
禁用而不能解析；BAD_PASSPHRASE可能表示密文损坏。未加密PEM补查单DER SEQUENCE
边界，传统加密PEM解密后的DER全消费未获证明；不宣称private/public数学一致性。
原认证策略共用、原配置求值/目录保留。独立Android方法只编译，动态ARM不运行；
具体已运行结果与工件对应见[验证](verification.md#20261019-manifest-inspection)。

## 2026-10-19 SSH声明catalog兼容边界

同一固定readconf lexer支持无Request的literal Host声明发现、静态managed Include遍历和
动态Include未解析来源；[]为literal，通配/否定不生成条目。IgnoreUnknown不掩盖未知指令。
Match仅词法消费，已识别指令的非法参数值可能通过目录扫描，不能用于语义校验/恢复准入。
目录ARM71923、原求值3736/handle5201项均effects0，定向JVM39通过。平台fixture只编译，
Android/JNI新版实际运行未执行；实际构建/工件与范围见[验证](verification.md#20261019-config-catalog)。
仍缺候选清单/私钥/信任语义validator、逐字段最终计划和connector，SSH恢复门保留。

## 2026-10-19 SSH配置JNI/Java兼容边界

生产JNI/Java已借用同一受管lease接到真实native evaluator；标准UTF8 CSEQ/CSES严格有界，
拒绝坏编码/ISO控制字符，保留有来源的错误和raw字段标记。ARM registry/parser5201项、
JVM308与构建/签名隔离通过；独立Android fixture已编译但未安装执行，动态ARM也未运行。
完整Options不经Java导出；语义validator/SSH导入恢复、最终逐字段计划及connector仍未接，
原恢复拒绝门保留。[本轮实测与限制](verification.md#20261019-config-jni)。

## 2026-10-19 SSH native配置求值边界

固定OpenSSH10.6p1真实readconf已支持受管Include/Host/first-value/Match final及完整Options
保留；跳板/forward/session字段仍可能需要后续展开，has_raw_fields不是可执行连接计划。
显式拒绝命令、外部provider、信任绕过和需宿主DNS/接口/环境的声明，不回退宿主配置。
本地静态NDK/Bionic ARM3736项/effects0通过，动态test只构建未运行；不扩展Android/JNI、
设备、外部服务器或产品启动器兼容结论。[具体证据](verification.md#20261019-config-evaluator)。

## 2026-10-13 当前开发板增量

用户授权后，最终 `3bc762d9…` 已安装AIO-3568J/Android11/API30/arm64，读回一致；221 Flutter/86定向/analyze通过，独立UID7项原生检查通过，真实无相机失败恢复通过。该板公开能力为0相机，硬件扫描不能验证。系统选图与真实生命周期最新结果见 [验证](verification.md)；同UID自有图片不能证明跨UID临时授权强制/撤销。用户专项批准后，当前ROM原生合成复制/敏感标记1项6.616秒通过；真实Flutter复制按钮E2E、服务MFA及其他ROM/API关卡保持，以下“本地/未安装”是初轮历史。

## 2026-10-13 TOTP 本地兼容边界

首版固定SHA-1、6位、30秒TOTP，只接受支持的标准otpauth/手动输入；不支持参数、重复/未知参数、issuer不一致、坏UTF8/Base32及其他迁移码明确拒绝。JVM已验证支持参数的RFC向量、前导零、窗口/2038后时间和实际生成的多二维码离线解码；不证明真实服务或组织策略接受ctOS，绑定/登录尚未执行。

minSDK28沿用；最终仅arm64 APK已构建并验v2签名，122 JVM与214 Flutter通过，45真实字体场景覆盖320dp/2倍字号/键盘/横屏等布局。自有安全相机/读图Activity源码、权限取消与迟到结果有本地检查；本APK没有设备安装或相机硬件、实际Bitmap/provider/临时授权、安全窗口/任务预览、ROM剪贴板实测。外部图片provider能否离线提供所选原图由provider决定，ctOS只离线解码。外部选择器超过共享后台期限（含0秒）会使原请求失效，需重新解锁/选择。

公共API30凭证快捷的历史成功证据保留，不推广为当前TOTP平台验收、API28/29或生物成功。非空TOTP跨设备恢复、混合SSH/native恢复及真实进程死亡仍开放；缺少生产SSH语义/信任审阅时相关恢复继续拒绝。具体 [本轮验证](verification.md#2026-10-13-authenticator-totp-本地实现与最终产物)。

## 2026-10-12 API30 系统凭证快捷认证

AIO-3568J Android11 API30 当前已由用户设置安全锁屏，公开系统凭证能力可用；同一隔离UID真实空库流程旧实现失败，认证后提交AAD的修复流程1项95.811秒通过，覆盖真实包装/解封和内容认证、关闭取消/错误保护、正确关闭及主口令回退。主包71d00e7c已覆盖安装/读回一致，现有用户库正常UI开启及快捷解锁成功，结束为库锁定/快捷已开。此结果替代10-10“无锁屏”在本板当前的限制；不证明生物识别、API28/29或其他ROM，也不覆盖真实锁屏事件/进程死亡/非空混合恢复及业务集成。AAD必须在系统认证成功及最终guard后提交，初始化探针完成不能记成功认证。完整 [验证](verification.md#2026-10-12-安全锁屏开发板快捷认证修复与实测)。

## 2026-10-10 秘密库兼容性边界

本轮主包ca85在B实跑6项Bridge生命周期通过，104 JVM重新执行、35定向通过；未变138全量未重跑。独立UID复用实际release Flutter/Main/Bridge的空库完整UI/SAF流程1项235.79秒通过，真实创建/锁定/改口令、新导出/同名取消/确认覆盖及不同来源/目标口令恢复均验证，测试资源/授权/两包清理完成。只在fixture自身关闭Root自动启动，不改系统无障碍或安全配置。真实HOME与core重建/排序注入分列，不称真实锁屏/进程死亡；非空/混合业务和成功认证未外推。以下17平台/真实provider六阶段对应此前a309包，详见 [追加验证](verification.md#2026-10-10-配置-b-追加bridge-生命周期与独立-uid-ui-探针)。

公共宿主路径ID修复时 104 JVM/未变138 Flutter/lint 通过；API 28/29 采用系统锁屏确认及认证绑定 Cipher，30+ 采用强生物/锁屏 CryptoObject。已清除 minSDK 28 不可用的 Java 集合工厂调用。配置 B T-CHIP AIO-3568J / Android 11 API 30 / arm64 的此前 `a309bd9d…` 包安装读回一致，17 平台测试与真实 ExternalStorageProvider1六阶段通过。路径型 provider 实测新 final 复用历史 original ID；journal 需区分存活角色/历史 ID，已删除角色先持久移除再恢复旧路径，已验证 final 保留。真实 provider 使用独立私有磁盘库/journal及密文认证，包含两种主动注入写入失败补偿，不代表生产 Flutter 全流程、真正进程死亡/云端/权限撤销。初轮 `6c0f00e6…` 的敏感截图无 PNG、录屏 App 区域黑、最近任务为空，取消后普通捕获恢复；相关 UI/helper 源码未变。此板无安全锁屏，不证明成功 CryptoObject/Keystore 或 API 28/29。KDF create 2035 ms 仅为样本，不外推其他 ROM。完整边界见 [本次验证](verification.md#2026-10-10-配置-b-追加真实目录与路径标识复用)。

Linux/glibc OpenSSH 10.0p2 内存文件访问六组研究验证通过，但不证明 Android/Bionic、NDK、16 KiB 页、完整登录/每跳信任或前台服务兼容。用户已撤销验证码剪贴板清理及其设置/跟踪/探针，不再有自动清理兼容性关卡；主动复制的敏感标记按平台能力生效。导出按用户新决定实现同名确认、新文档写入/读回和精确补偿；SAF rename/create/delete、权限撤销和恢复需要真实 provider 验证，清理失败或效果未知须显示位置，不承诺任意 provider 多文档原子性。只使用本次 tree 授权，不加广泛存储权限/持久 grant；Android 11+ 可选目录受系统限制。生产 SSH 内容及恢复语义 provider 缺位时拒绝来源或目标包含 SSH 的导入。见 [验证记录](verification.md) 与 [未完成关卡](app-authentication.md#2026-10-10-当前实现与未完成验收)。

2026-09-28 当前 `9ca71e31…` APK 已在 Firefly AIO-3568J（Android 11 / API 30、arm64、SELinux Permissive）覆盖安装，设备 APK 哈希一致；七项非 Root 定向仪器测试通过，覆盖内置 Python/SDK、取消/超时/后台回收、App 网络快照、文件加解密与编码、文件不覆盖及 loopback HFTP 后台/停止。Root 采集/PTY、LAN/熄屏、SAF 手操与其他 Android 版本边界未由这次开发板检查扩展。详细范围见 [verification.md](verification.md)；下方各条按当时 APK 版本解释。

2026-09-26 Portable Python 当前包 SHA-256 为 `00c19e0ca2ecc84b1fea32fafaec29708b152710a3f5a3b8ece64717a26d97c6`。Flutter 28 项、Android lint/测试包构建和本地 ARM64 Android/QEMU 检查通过。已覆盖安装 PLR110 / Android 16 / SELinux Enforcing，设备哈希一致，真机 8 项通过：App/Root python3、标准库/SDK、运行中取消、超时、后台待运行任务回收及原有 Root/网络回归。工作台二级页自检和 JSON 保存读回通过。APK 仅 arm64，最低 API 28；Python 3.13.9 和 NDK 启动器具备 16 KiB 对齐，但 Android 11、16 KiB 页面设备及其他 Root 管理器未测。下方均为旧包历史；详情见 [verification.md](verification.md)。

已验证设备包括 T-CHIP / Firefly AIO-3568J（Android 11 / API 30、arm64、SELinux Permissive）及 OnePlus PLR110（Android 16 / API 36、arm64、SELinux Enforcing）。

2026-09-26 Root-only 当前包 SHA-256 为 `be800549c2ddf6509fe9ff0de23556d58b9401b67fee39f63fa5eb361752ab93`，在 PLR110 `192.168.9.9:44603` 覆盖安装与设备哈希核对成功。五项设备测试全部通过，覆盖 App 网络 API、Root 接口/连接、会话复用/超时不重开、App/Root PTY 和分身别名解析。工作台显示 ROOT 在线、33 个接口及 root / procfs + ip 数据源。ctOS 不再依赖 Vector，当前网络配置属于 App API 可见范围；Android 11、全新安装弹窗和完整导出对当前包未重测。以下是旧包历史记录。

2026-09-26 终端版当前 APK SHA-256 为 `1a610e33fc018d20f4b99161d6eb5e91f4eb594930f0d0033e3eea5c873245ae`；在 PLR110 `192.168.9.9:45075` 覆盖安装并核对设备 `base.apk` 哈希。该包 App Shell 的 `ec`→Tab→`echo` 在终端和输入框同步显示；执行命令后 ↑ 恢复完整命令、↓ 恢复空草稿；“选择输出”子页显示当前快照。较早的 `da530c09…` 包在同机证实普通文本输入类型 `0x1`、发送动作、中文候选提交与退格、App/Root PTY 的 `id` 及选择输出复制；当前包未逐项重测这些操作。厂商键盘蓝色主题由输入法决定。Android 11 开发板及其他输入法对当前包未验收；更多检查与截图见 [verification.md](verification.md)。

2026-09-25 当前分身别名版 APK SHA-256 为 `19b8922bce927066dcec7fd9a7d45ac5caf148f32943d8c1c44090738560791d`。PLR110 覆盖安装与设备哈希匹配，Flutter 15 项、Android lint/测试包构建及分身解析与 Root/PTY 四项定向仪器测试通过。该机 Oplus 桌面可提供 QQ 分身别名 `tim`；前一轮实机包已显示 UID 99910377 卡片，当前包的精确别名筛选通过模拟测试。其他 ROM 的桌面别名提供者未验证，缺失时保留原名、包名和用户 ID。`ef6e9981…` 中间包的全量仪器测试 5 项中 Vector 桥接仍失败；Android 11 对当前包待验收。详情见 [verification.md](verification.md)。

2026-09-25 应用图标版 APK SHA-256 为 `78d7af8db4474970cd4bbcb7a9dc16c3dc9333fc13a4a3de455473ab2393537f`。PLR110 覆盖安装与设备哈希匹配，Root/PTY 三项仪器测试通过；导出修正前同界面版本 `c9965efa…` 的 Quick Connect 真实图标与显示名筛选通过，该版仅在导出时排除图标数据。Flutter 14 项、Android lint/测试包构建通过。Android 包可见性限制部分应用图标/名称；不可得时保留包名与占位图标。Android 11 与 Vector 对该版仍待验收。

2026-09-25 左对齐修正版 APK SHA-256 为 `78392858a4ee90cf9727a2219918ef058fd30333f6ec9c7b90c366db23604be8`；PLR110 覆盖安装与设备哈希匹配，连接页左对齐、item、包名筛选实机通过，Flutter 13 项及 Root/PTY 三项仪器测试通过。Android 11 与 Vector 对该版未验收。

2026-09-25 连接 item 首版 APK（SHA-256 `1f6e6833359517229c091328d07d0918ad7222e8aca89603a25e489fbee133ce`）曾在 PLR110 覆盖安装；设备包哈希匹配，连接列表及应用包名筛选实机通过。Flutter 13 项测试与 Root/PTY 三项仪器测试通过。Android 11、Vector 和全新安装授权弹窗未在该版验收。

2026-09-25 可信状态与工作台版 APK（SHA-256 `4e26b346eb457e23d22e17bf45d655ff58262cda0cc5b358c7e678b333c0a9a9`）已在 PLR110 覆盖安装并检查工作台、连接快照过期/刷新及系统负载权限说明；Root/PTY 三项仪器测试复测通过。一次首次合跑的 Root 超时断言失败未能在单项和再次合跑时复现，详见 [verification.md](verification.md)。Android 11 板、Vector 桥接及全新安装授权弹窗对这版 APK 尚未验收。下述记录属于较早版本，不代表当前包的通过结果。

2026-09-25 在用户指定的 `192.168.9.9:45797` 上覆盖安装新 current APK，安装包与设备 `base.apk` 的 SHA-256 均为 `b777fe91b2dc3292c487977dbda10c44059ef2c9afc8c94095f977fa88b95d80`。该机原有 ctOS Magisk 授权保持开启；新包在首次启动、强制结束后的再次启动、再次覆盖安装后，均无需点授权按钮就恢复 ROOT 在线及 `root / procfs + ip` 采集。3 项 Root 仪器测试通过，涵盖计数/连接、会话复用/失效停止，以及 App/Root PTY。全新安装时的 Magisk 首次弹窗尚未在该机复现。设备信息可用，但 SELinux Enforcing 下 `/proc/loadavg` 返回 `EACCES`。Vector 桥接测试因 8 秒内无响应失败；未改其配置或重启，故该机的 Vector 能力未通过验收。

2026-09-24 在用户指定的 `192.168.9.14:5555` Android 11 开发板上安装当日 current APK；安装包与设备 `base.apk` 的 SHA-256 相同。该版的 4 项仪器测试全部通过，覆盖 Vector 桥接、Root 采集与会话复用、App/Root PTY；工作台、设备信息、网络、接口、连接、只读命令、App 终端及 JSON 导出也通过实机检查。测试结束后已撤销 ctOS 的临时 Root 授权。2026-09-25 的自动 Root 版本尚未在此板重测；其模块在重启后加载也未验收。以下 2026-09-23 验证属于旧版。

框架：Vector v2.2，versionCode 3111，revision efb82883，API 102；ctOS 使用框架仍支持的 legacy API 82。作用域为 `system / 0`。

2026-09-23 已重启加载系统模块，3 项 instrumentation 测试通过：系统桥接返回 UID 1000、Root 接口与连接采集、App/Root PTY 的身份/TTY/尺寸/Ctrl-C。主网卡 eth1，界面自动选取默认网络接口。Android 11 使用 procfs 计数，Android 12+ 使用 TrafficStats API 回退；Root 采集读取 procfs 与 ip。

界面已验证接口过滤、连接端口检索及 UID 到包名映射、Root 终端 id 输出，以及通过系统文件选择器导出 JSON 并读回。未开启 App Root 采集时，导出仍包含 Vector UID 1000 和 11 个接口的计数。

2026-09-23 在 OnePlus PLR110 上仅验证过初版安装与 Wi-Fi/VPN 界面；该历史结果由上方 2026-09-25 的 current APK 检查补充。开发板和此手机的结果均不能证明其他 ROM 的完整兼容性。当前 APK 仅提供 arm64，使用开发签名。

详见 [verification.md](verification.md)。

## 五项工具当前边界（2026-09-26）

当前工具 APK SHA-256 `f928af6611f014206597321b2f35a094aef53b11eeb404ea7bbf72e23ee8a73b` 已按本 task 授权安装 PLR110 / Android16 / 4096 页 / SELinux Enforcing，设备哈希一致。七项定向 native 测试、SAF 导入/取消/产物导出读回、HFTP 本机后台通知与通知停止通过；App/Root 均可直接调用 Python。09 指定公共IP的实际HTTPS查询通过。宿主隔离测试、浏览器 loopback 与最终 APK ARM64 QEMU 另有证据。Android11、原生16KiB页设备、API28/29 no-replace syscall 分支、全新通知拒绝和真实离线网络未实测。新加密格式与旧 CBC 格式显式区分，08 无自动系统 UUID 盐，HFTP 仅保留核心文件操作，见 [工具设计](portable-tools.md)。

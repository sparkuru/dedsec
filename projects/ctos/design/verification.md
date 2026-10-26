# Verification

<a id="20261026-formal-install"></a>
## 2026-10-26 正式APK覆盖安装与交付收尾

用户明确要求“安装、然后说明功能；最后归档并提交”。已在指定
`192.168.9.14:5555`（AIO-3568J、RUNNING_UNLOCKED）使用`adb install -r`覆盖安装，
未清库或卸载正式应用。安装返回Success，开发板实际APK SHA256为
`6fa5c1bee28af584023f91a20ab0252abc02e53867bb13f1fb571468286417e8`，与已验收产物一致；
正常启动MainActivity后进程/前台Activity已核对。[功能与入口](ssh-terminal.md)。
记录：`/tmp/ctos-ssh-install-20261026.naqyw6a9`。

归档前发现正式Gradle/runner引用活动task/research目录。已将9份runner和6份patch迁到
稳定`android/ssh/`，更新Gradle全部13处引用及stream/KEX显式输入，原patch字节、固定
source/archive SHA、no-symlink/forbidden/output检查和权限保留。9份bash语法/ShellCheck
通过；7份shfmt通过，另2份仅保留逐字相同的既有格式差异。独立只读审查无finding。
实际归档前`compileSshFiles`链（production/config/JNI）通过，7任务/16s。
产品C/Java/Dart与已实测代码保持，未因路径迁移重复执行无变化的全部测试。

本次归档只关闭前台密码SSH交付；原完整SSH父任务及未完AC继续保留，不把子任务数量
当作全部需求验收。提交使用ctOS显式路径，归档/日志禁用脚本自动commit，以免纳入
共享Git索引中的其他项目改动。归档后的实际构建结果另记于本节收尾记录。

<a id="20261026-password-android"></a>
## 2026-10-26 前台密码SSH：真实Android bridge与Flutter界面通过

用户明确回复“允许”，授权在`192.168.9.14:5555`临时安装隔离测试包、使用合成账号验收
并清理。本轮实际设备为AIO-3568J、Android11/API30、arm64-v8a、RUNNING_UNLOCKED。
证据：`/tmp/ctos-ssh-device-20261026.crqhdkf1`。沿[正式连接合同](ssh-password-connect.md)，
不覆盖安装正式应用、不使用用户真实秘密库或账号，不提交/归档/发布。

| 检查 | 实际结果 |
| --- | --- |
| 实际MainActivity/shared VaultStore/JNI/TCP | `SshPasswordConnectPlatformTest#actualBridgeNewThenKnownPasswordShellAndProtectionRelease`，OK(1 test)，5.924s |
| 实际Flutter表单/审批/终端输入与渲染输出 | `SshPasswordUiPlatformTest#actualFlutterPasswordFormTrustAndShell`，最终OK(1 test)，12.802s |
| 测试构建/lint | 最终84任务、16执行/68保留、15s；lint0错误/4条既有警告 |
| 原有代码保留 | 314份既有代码/runner逐文件SHA一致；仅新增一个Android UI测试文件 |
| 独立SSH服务 | 新建内部网络OpenSSH_10.3p1，仅回环relay+ADB reverse；原始日志5次合成账号密码登录及shell/connection close |
| 正式已安装APK | 前后SHA256均为`3bc762d9e6463ab9612393b9382923f4a29f613ceec3dbe4d9159aae0078f252` |
| 最终实际测试APK | `46fc5720e4028d4c78848b7f2c14c52c758ee9cd95b15a7cc19d5fd5002d7930`，与开发板实际安装文件一致 |

bridge测试通过真实dispatcher与shared owner创建加密库，实际首次NEW完整指纹确认/CAS
保存、一次密码SUCCESS/RUNNING/PTY标记、解除表单保护后命令收发、Stop后的KNOWN重连
及最近端点回读。此测试只替换事件sink作观察，不替换实际网络/认证/存储。

UI测试完全操作真实Flutter可访问控件，无MethodChannel调用、反射、事件替换或剪贴板：
终端入口→创建主口令库→受保护SSH表单→输入端点和当次密码→同时核对实际完整端点/
算法/SHA256指纹→信任并保存→终端→发送分段printf标记→真实“选择输出”快照中读取
完整标记→AppBar返回→关闭会话。完整标记不在命令回显中，不能由回显伪造成功。
实际敏感表单FLAG_SECURE、密码字段遮蔽及成功交接后的窗口保护释放也通过断言。
最终PID31434阶段日志完整经过marker_assert、close_own_session和pass；两个runner均有
方法status0与OK(1 test)，final code -1属正常结束，未用adb exit0替代测试结果。

UI初始三次失败均保留：第一次是UiAutomation直接参数处理导致stat格式引号保留，
严格600:2000权限本身正确；第二次在实际输出阶段失败，第三次细分阶段确认是硬件
Back注入返回false。最终使用真实AppBar Back动作，保持完整输出/页面退出断言；只读
Flutter selection按其实际ACTION_SET_TEXT能力识别，不把isEditable标志直接当可写。
产品代码未因此改动，失败不改写为通过；最终测试来源/安装APK/原始日志均独立绑定。

实际最终测试APK经过ZIP/AXML/DEX、同既有signer的签名与内容摘要独立审查，minSDK28、
target=`im.majo.ctos.secretsfixture`；class_defs含两份实际连接测试而无生产类替身、无native
替身。原产品APK及fixture main继续保持前轮已验证bytes。独立Trellis raw审查通过，无遗留finding。

验收后两fixture包卸载Success，包路径/进程均为空；本轮请求JSON、UI XML和专用ADB
reverse均删除。临时容器及镜像匿名卷、内部network、回环relay、随机密码文件均清理，
无密码进入文本证据。正式APK构建产物仍为`6fa5c1bee28af584023f91a20ab0252abc02e53867bb13f1fb571468286417e8`，
本轮没有覆盖安装它。当前前台密码路径验收完成；其他Android/真实账号/后台行为不在
本轮结论中，父SSH客户端完整功能任务继续进行中。此前独立C引擎70秒证据仍单独记录，
本轮12.802秒Android UI测试不冒充实机70秒运行。

<a id="20261025-password-connect"></a>
## 2026-10-25 前台密码SSH连接（本地门禁完成，设备待验收）

[合同](ssh-password-connect.md)/[规范](../.trellis/spec/backend/ssh-password-connect.md)。
证据目录：`/tmp/ctos-ssh-password-connect-20261025.fez9zf8o`。正式表单、真实AppSecretsOwner/
VaultStore/整库子集validator、MANAGED实际主机键/审批/CAS、临时selected密码、公开API
TCP/FD接管和现有PTY终端接线已实现；可选私钥/证书、跳板/转发、rekey、后台服务及
完整复杂资料恢复不在本增量完成声明中。父task继续进行中。

已实际运行：Flutter analyze无问题，全量242项、定向15项通过；最终Java718项/42份XML，
0失败/0错误/0跳过。最终ARM/QEMU静态Bionic完整client矩阵1,756,509 checks及issuer33例
通过；包括实际FD类型/标志/所有权、DNS/IPv6主机名记录、默认/非默认端口、hash与大小写
隔离，以及拒绝条件与错误码保留。合成peer到期测试实际使用3秒预算，独立服务端另测70秒。

正式构建92项全部重跑1m56s通过；fixture111项（42执行/69保留）38s通过。
最后仅native issuer错误码修正后，正式92项（21执行/71保留）17s、fixture111项
（20执行/91保留）12s通过；Java源未变，718项报告在该次为UP-TO-DATE，不冒充再次执行。
lint最终0错误，正式/fixture分别3/4条既有警告。最终三APK实际ZIP/AXML/DEX/签名/隔离target/
normal无测试peer与测试类/同生产SSH库/22ALLOC/JNI15+20+1+1+1+1+4/archive83核对通过。
normal SHA256为`6fa5c1bee28af584023f91a20ab0252abc02e53867bb13f1fb571468286417e8`。

失败与修正保留原始日志：界面前台恢复/小屏可见性与测试fixture已修复；UTF8 decoder正常
UNDERFLOW被误判异常的产品问题、Unissued测试期限错位及shell取消错误映射已修复。
独立OpenSSH服务端首次TCP测试在KEX失败；定位到hostfile查询/新行未纳入实际端口，
已修正并补齐跨端口/hash/大小写验证。首次正式lint的5项较新API误用已按API28公开接口与
固定Linux ABI修正，保留最低SDK；实际PFD dup不继承CLOEXEC，经自身JNI在detach前恢复。
issuer旧根配置拒绝测试还发现cleanup覆盖EACCES，已修复并保留严格原断言及旧errno回归。
失败日志保留，未弱化输入/权限/签名检查。

独立服务端使用已有OpenSSH_10.3p1镜像、内部Docker网络及仅回环监听relay，无用户资料
挂载。客户端只接受实际预配置公钥的MANAGED KNOWN，不将此测试说成NEW/CAS实测。
已完成真实签名KEX、一次密码SUCCESS、PTY非回显标记、resize/stty及70秒后新命令、
exit7/EOF/CLOSE；最终相同源码/archive的70秒复验也已通过，日志为`interop-verified-runtime.log`。
该证据是C引擎独立互通，
不是Android Os/JNI/Java编排或Flutter实际点击验收。

45份实现/测试/runner与269份未改代码逐项哈希核对，438份before保留；四份既有cached
wheel及source SHA/ZIP复核通过。独立Trellis检查覆盖源码/测试及原始XML/log/实际APK签名与
内容摘要/AXML/DEX/ELF/archive以及最终互通日志，独立审查通过，无遗留finding。
测试容器、镜像自动匿名卷、内部网络、回环relay、随机测试密码及已诊断issuer崩溃的
临时core均清理，公开key/无秘密日志与清理记录保留。新Android平台fixture已经编译，尚未设备运行；
未使用真实库/账号，未安装、提交、归档或发布。后续设备验收须遵守当前授权范围。

<a id="20261025-ssh-mainline"></a>
## 2026-10-25 用户更正：SSH唯一正式主线

[合同](ssh-manifest.md)/[规范](../.trellis/spec/backend/ssh-manifest.md)。根据用户明确更正，
移除双格式业务API/默认旧writer，唯一最新模型用于所有构造/create/重新编码；只有内部
wire标记用于输入识别。窄旧empty/纯TOTP输入立即适配同一模型，旧SSH输入拒绝。
不改envelope/crypto/native/Flutter或依赖，不操作设备/真实库账号/安装/提交/归档/发布。

恢复按实际关系/账号审阅缺口判断，空库/纯TOTP不因tag拒绝。原raw来源绑定保留；loan
用独立authenticated原文派生expected，换口令/恢复用独占当前编码、精确readback和wipe。
恢复重新编码从原authenticated plaintext独立派生，保留preview manifest不成为新正文来源。

证据`/tmp/ctos-ssh-mainline-20261025.73vkexd5`。核验前轮source9/protected303/artifacts571，
434before基线；本轮protected307。首生产Java编译41tasks（7执行/34UP-TO-DATE）17s通过；
主会话审查将import新正文来源由保留model改为独立authenticated原文派生，补齐model/raw
分别变异回归；首定向177项/9reports全0。独立审查另补仅binding/仅PASSWORD-account/
两者的OR内容门矩阵。最终定向177项/9reports全0，46tasks（8执行/38UP-TO-DATE）1m27s。
最终全量691项/39reports，失败/错误/跳过0；normal92tasks全部执行3m23s。
fixture111tasks（33执行/78UP-TO-DATE）39s，复用final production native（显式排除三编译
task），未变化的instrumentation DEX/test package使用缓存。lint两包0errors、3/4项既有
warnings。所有旧输入验证均为合成fixture，不代表访问过用户真实库。

三APK ZIP完整、同一单signer/签名验证通过、实际AXML包名与测试target隔离、normal无
auth peer、两产品包含正式metadata类且无任何JVM测试类；两产品SSH库bytes一致，
generated/packaged不同role ELF的22个ALLOC节逐项相同；JNI15/19/1/1/1/1、archive83。

- normal：`0c5bac0b16c0b59f221f8817a231df5aa17db26b769a187dd2e7626eeacbdb44`
- fixture：`f7ddbb23177855a4ec789d17486cad3d26d0e416d8cf5f810e118546bf6abf75`
- test：`95ad1b51d56a4701e56f3b7701cde26e898c19854929cbe4c123cb47874b087b`

packaged SSH `7bed3d9ff9299bc4059f438cd7e48135d311a1aa5b48204e3560ce69703a4d7f`；
generated SSH `9f243821e23cf02bcef455ed588a702580eec91f6e50418747f0c838cc051447`。
signer仍`8d36c8be418174cb4cda0b897ff3e32b2da6f84032819726e8e5671872df52ef`。
最终source5/protected307已核对；首编译记录在import来源修正前，最终定向/全量重编译
与source-final为最终代码依据。独立raw XML/log/实际ZIP/AXML/DEX/签名与contentdigest/
ELF/dynsym/archive/hash复核PASS，无遗留finding；before434及final5/protected307均吻合。

本轮无ARM/native测试、Flutter测试或Android JNI/platform runtime，实际SSH连接尚未
运行。下一阶段优先验收最小密码连接的账号/Request、主机键确认、路径准入/connector/
可操作入口；本轮测试不作为实际SSH可用性验收。

下方为历史验证，旧双格式政策已被最新用户决定取代；当时合同/源码见对应/tmp快照。

<a id="20261025-manifest-v2"></a>
## 2026-10-25 显式 key-binding/account 清单 v2

[合同](ssh-manifest.md) · [规范](../.trellis/spec/backend/ssh-manifest.md)。继续原
SSH task，本地未提交，无设备/真实库账号/安装/commit/archive/push/dist。
证据：`/tmp/ctos-ssh-manifest-v2-20261025.stybeq0l`。

新增显式 plaintext v2 的不可变 KeyBinding/Account、严格结构校验与完整有序编码；
envelope CTOSVLT1/version1、KDF/AAD/generation和12MiB上限不变，默认create/empty仍v1。
v1字段/字节与原有宽松文本语义保留，新metadata严格UTF8，opaque target及host原值不规范化。
全局ID、精确类型引用、私钥/所选phrase唯一owner、account目标唯一均校验；声明不提供
数学/远端账号/信任/完整coverage。四处生产candidate改用withObjects，loans/export/rekey
保留版本/metadata及实际字节；引用破坏拒绝完整candidate，不删关联或降级。

恢复保持source-first：每侧成功authenticate/decode后、交该侧validator前拒绝v2；
empty/pureTOTP也拒绝，bind防御旧coverage。较早source-v1错误保留优先级，不保证目标v2
被发现；没有reviewer/新preview/store效果，旧v1恢复继续保留。

首生产Java编译41tasks（7执行/34UP-TO-DATE）17s通过。首定向199项有2失败，均为新
fixture错误地禁止既有v1 restore在commit guard内重读；产品代码无需修复。夹具改为仅两次
旧v1 restore显式核对exact旧target并计数，四类mutation仍默认禁止commit内read及
quickMaterial读取。另补selection原错误码/message和Unicode字节回归。
最终定向200项/9reports，失败/错误/跳过均0，46tasks（8执行/38UP-TO-DATE）53s。

最终门禁：fresh全量679项/39reports，失败/错误/跳过0；normal92tasks全部重跑1m53s。
fixture111tasks（33执行/78UP-TO-DATE）20s，显式排除三生产native编译task，复用final
native；未变化的instrumentation测试APK沿用缓存，其SHA与上轮相同。normal/fixture
lint均0errors，分别3/4项既有warnings。没有新增仪器测试或Android运行证明。

三APK ZIP完整、v2签名/同一单signer、真实AXML包名与instrumentation target隔离、
normal无auth peer、两产品包含v2模型classes且无JVM测试classes；两包SSH库逐字节相同。
packaged与generated两个role ELF的22个ALLOC节逐项相同；JNI15/19/1/1/1/1、archive83。
APK SHA256：

- normal：`35f375105a2165e21cedbc7d9796dc3fd53bfc7534efd50e385db500c8a027bb`
- fixture：`a42b561980ea4c369d414db18023afc5d8cccc9fde76ebca82941cba513830cf`
- test：`95ad1b51d56a4701e56f3b7701cde26e898c19854929cbe4c123cb47874b087b`
- packaged SSH：`476598b82070589e039453f2a104b636518e39b1607b312f20a9d48d34d40130`
- generated SSH：`94f22a8b17d624839ef68de52f3f74dc1f04319899ce5c2679c420340ed6d49e`

signer仍为`8d36c8be418174cb4cda0b897ff3e32b2da6f84032819726e8e5671872df52ef`。
独立raw XML/log/ZIP/v2/AXML/ELF及source/protected hashes复核PASS，无遗留finding；
另独立验签实际v2 signedData与chunked content digest、解析AXML/ELF dynsym/archive，
核对全部before427/first-source7/final9/protected303与当前源及snapshot一致。

独立Trellis源码/测试审查通过：7生产文件、schema25+生命周期16项新测试及夹具修正。
主会话已核对427baseline/9source/303protected、四份cached wheel与原source SHA/ZIP，
临时offline init显式no-index复用。无新增依赖或native/Flutter源码改动；normal按现有构建
重新编译native，但本轮不执行ARM/native测试、Flutter测试或Android JNI/平台fixture
runtime，不把历史实测当本轮重验。

持久schema基础已实现，后续仍须v2整库关系coverage/数学/实际账号消费、CA authority/
time/principal/revocation、graph-aware reviewer、真实Request动态资源及完整provider/
恢复/connector，父AC保持开放。

<a id="20261024-certificate-facts"></a>
## 2026-10-24 全部证书事实与嵌入签名政策

[合同](ssh-certificate-facts.md)/[规范](../.trellis/spec/backend/ssh-certificate-facts.md)已实现
实际清单全部CERTIFICATE的数值/公开事实及明确software/CA signature政策。新CSCF168只给
独立whole cert/plain subject/canonical embedded CA三摘要、USER/HOST、u64原bit pattern、
principals数量、critical/extensions原wire section长度及真实record物理行。不复制其他正文/
凭据，不读取current vault或wall clock，不构造host/user/purpose/approved CA。

pinned parser嵌入验签成功后才施加新操作的现代签名政策；RSA SHA1新UNSUPPORTED与旧
CSMI仍PARSED已有真实对照。过期/未来/反向时间、空/重复/尾随NUL归一化/nonUTF8 principals
仍按事实保留，不声称raw规范性或适用性。Report恒factsOnly=true/admissionComplete=false，
trust/timeValidity/principalApplicability/revocation全部false；生产证书拒绝门及provider/
恢复/connector/v1未改。三个公开摘要不互相替代或要求互不相等。

证据：`/tmp/ctos-ssh-certificate-facts-20261024.izvxli13`。

| 检查 | 实际结果 |
| --- | --- |
| 接管 |前轮source10/protected295/artifacts635重算通过；本轮421baseline，source10/protected299 |
| 首生产compile |41tasks10执行31UP-TO-DATE18s，通过 |
| 首strict八材料模式 |13tasks9执行4UP-TO-DATE18s，通过 |
| 首实际ARM证书 |22239checks/baseline19977/signs59/verifies127/effects0/cleanup pass/exit0；新增操作没有sign/private parse/KDF |
| 定向JVM |145tests/5reports，全失败/错误/跳过0；新增25测试 |
| 最终普通构建 |100tasks全部重跑1m47s；JVM/lint/Release及八strict模式通过 |
| fresh全量JVM |638tests/37reports，失败/错误/跳过0 |
| 最终实际ARM |证书22239含baseline19977，独立private19980/pair13814/material7784，全部exit0/effects0/cleanup pass |
| 最终native输入 |八模式10/10/9/9/8/8/7/7项source/archive inputs与最终源码、冻结archive、二进制相符 |
| 隔离fixture |111tasks37执行74UP-TO-DATE20s；final native三compile排除，主/testJava、两DEX及测试包重编译 |
| lint |normal0errors3warnings，fixture0errors4warnings |
| APK/native审计 |三v2/同一单signer/隔离fixture与target/normal无peer/同生产SSH bytes/22ALLOC节匹配生成库 |
| JNI/固定源 |config15/session19/material1/pair1/private1/newCertificate1，archive83；parser patch7afd7a9a…、G1及生产依赖保持 |
| 源码/范围 |source10/protected299全部匹配；无关v1/AppSecrets/认证/config/Flutter/Python及生产证书拒绝代码未改 |
| Android |新JNI/平台fixture仅编译，未安装/未Android运行；本轮未重跑auth/PTY/config/Flutter，不外推旧证据 |

真实C矩阵包含五subject×五CA组合（同key subject/CA摘要可相同）、RSA SHA2/SHA1边界、
USER/HOST、0/high-bit/MAX u64、空/未来/过期/反向时间、principal0/256/257及raw编码边界、
重复/未知critical/extensions、坏signature/CA/nestedCA/类型/record/suffix/size/line/NUL、
系统verify错误、每个可观察deadline和输出归零/FD/effects/caller保护。全部首轮门禁通过。
旧基线CHECK计数随随机生成PEM截断长度及外层复用CHECK略变，不代表改断言；记录真实
最终输出，不拿首轮archive替代全重跑生成的最终archive。首末源码未变，最终四静态矩阵
对应最终archive，native-first与native-final分别保留。

新public platform fixture由本地ssh-keygen生成：仅公开证书嵌入测试，Ed HOST/serialMAX/
2020过期区间/一principal；精确opt-in和SshMemory guard保留。其Base64/原始字段与固定
公开文件逐字节核对，通过DEX编译不表示JNI执行。没有设备/真实库账号/连接/安装。

所有副本cleanup slots先建立，同一<=60s BOOTTIME/atomiccancel/oneattempt/owner/ThreadLocal
贯穿preflight/copy/parse/hash/cleanup/输出/最终交付；无prefix报告。单parse/embeddedverify
不可抢占，postparse政策不阻止其先行成本；pinned parser把部分深层错误归一化为格式错误，
wrapper不声称能恢复其OOM分类。JNI有界public heap擦除不是全堆锁页证明。

完整持久关系图/账号模型、CA authority/时间/主体/撤销与reviewer、真实Request动态资源/
逐字段消费、完整provider/恢复/connector及父AC仍开放。本地未提交，无commit/archive/
push/dist。临时offline init显式no-index/find-links复用四既有wheels，cache source+wheel
SHA及ZIP本轮重核通过；无生产依赖变化。

```sh
./hako bash -lc 'cd android && ./gradlew --init-script /app/build/ctos-detached-offline.init.gradle :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease :app:testSshCertificateFactsHost :app:testSshCertificateFactsStaticHost :app:testSshPrivateMaterialHost :app:testSshPrivateMaterialStaticHost :app:testSshKeyAssociationHost :app:testSshKeyAssociationStaticHost :app:testSshMaterialInspectorHost :app:testSshMaterialInspectorStaticHost --rerun-tasks --offline --console=plain'
./hako bash -lc 'cd android && ./gradlew --init-script /app/build/ctos-detached-offline.init.gradle -PctosSecretsUiFixture=true -PctosSshAuthFixture=true :app:assembleSecretsFixtureRelease :app:assembleSecretsFixtureReleaseAndroidTest :app:lintSecretsFixtureRelease -x :app:compileSshProductionObjects -x :app:compileSshConfigEvaluator -x :app:compileSshFiles --offline --console=plain'
```

| 工件 | SHA256 |
| --- | --- |
| 普通 APK | `21401db29f5056389f96eb63ce86615f4c407fc008ebfd70a6f05bca8993d343` |
| Fixture APK | `40fee028fa526514768445a7ab56a6814e8b65cedadb04e70d192c154a66e9d5` |
| 测试 APK | `95ad1b51d56a4701e56f3b7701cde26e898c19854929cbe4c123cb47874b087b` |
| 共同包内SSH库 | `208cf211879acb495f3eea812519881c54c23184fd24787a4d3516be0b5c4500` |
| 生成SSH库 | `3f30ad96b9cb86b4b12ee08b5179f6c9344d282a871eb9b0b9ddc11b941a668b` |


<a id="20261023-private-material"></a>
## 2026-10-23 全部私钥材料与v1凭据归属审计

[合同](ssh-private-material-ownership.md)/[规范](../.trellis/spec/backend/ssh-private-material.md)
已实现每个实际PRIVATE_KEY的固定domain自签/验签，以及所有非TOTP凭据的v1元数据归属扫描。
不需要外部public/cert peer；对parsed private自己的plain public subject做真实验证，错误
Ed25519 seed不能由公开字段相同绕过。CSKP60独立新magic/type2，旧CSMI/CSKA不升级。

重复private identity无phrase时仍逐一检查；有phrase时private owner与phrase都须唯一。
归属歧义不挑首项、不按秘密相等消歧、不复制歧义private/phrase或JNI尝试；有唯一private
但重复phrase保留唯一private locator、选中phrase为空。PASSWORD只做typed target: namespace，
suffix保持opaque，正文不复制；passwordTargetsChecked恒false。TOTP及其他文件正文不复制。
Report恒admissionComplete/keyAssociationsChecked/trustChecked=false，归属解析和材料成功
分开量化，不作为完整语义validator、全库graph、证书信任、恢复或connector准入。

证据：`/tmp/ctos-ssh-identity-next-20261023.j_o6gwfz`。

| 检查 | 实际结果 |
| --- | --- |
| 接管与保留 |前轮source9/protected292/artifacts575重算通过；初391文件snapshot补22个此前已验证且仍匹配的源码，形成413baseline；本轮protected295 |
| 首次生产compile |41tasks10执行31UP-TO-DATE17s，通过 |
| 首strict全部材料模式 |11tasks7执行4UP-TO-DATE15s；private/pair/material动态与静态均编译通过 |
| 首实际ARM private |19976checks/含复用baseline13815，signs59/verifies74/effects0/cleanup pass |
| 定向JVM |120tests/4reports，失败/错误/跳过0；新增27方法 |
| 最终普通构建 |98tasks全部重跑1m46s，JVM/lint/Release/六strict材料模式通过 |
| fresh全量JVM |613tests/36reports，失败/错误/跳过0 |
| 最终实际ARM |private19976/含baseline13815、独立pair13812、独立material7784；全部exit0/effects0/cleanup pass |
| 最终输入 |六native模式input manifest的9/9/8/8/7/7项全部匹配最终源码/archive，最终archive与native快照保留 |
| 隔离fixture |111tasks37执行74UP-TO-DATE19s；final native三compile任务排除，main/testJava、两DEX及测试包重编译 |
| lint |normal0errors3warnings，fixture0errors4warnings |
| APK审计 |三v2/同一单signer/独立fixture/test及目标；normal无peer；普通/fixture生产SSH库全字节相同，22ALLOC节匹配生成库 |
| 导出/固定依赖 |config15/session19/material1/pair1/private1 JNI，archive83，parser patch7afd7a9a…d60fd5e与v1/policy不变 |
| 最终源码 |source10/protected295全部匹配；无关AppSecrets/VaultManifest/认证/config/Flutter及Python源码保留 |
| 本轮运行范围 |未Android执行新JNI/平台fixture，未重跑认证/PTY/config/Flutter或设备；不将前轮通过冒充本轮实测 |

原矩阵按随机生成PEM序列化长度遍历截断，因此CHECK计数可随运行略变；复用入口外层CHECK
也影响累计基线。首轮独立material7780/pair13814，最终7784/13812，不代表删测试或放宽
断言；源码hash、全部真实断言及exit0可复核。首末private均19976，首末全部门禁一次通过。
production archive因最终全重跑重新生成，与首轮inputs hash不同；两轮证据分开，最终
runtime采用最终archive重新构建的三静态binary，不把首轮inputs称作当前字节相同。

所有所选副本在cleanup slots建立后复制，同一原<=60s BOOTTIME/atomic cancel/owner/
ThreadLocal/final wipe fence贯穿；每个private最多一次，累计private<=8MiB。传统加密PEM
在新strict模式解密前UNSUPPORTED，clear额外phrase同样不忽略；原材料/auth格式行为不改。
新self-proof复用shared signing helper、RSA所有组成字段16384bit/no multiprime政策。
单parser/KDF/crypto不可抢占；RSA检查不消除PEM parser内部先行成本。有限heap scratch
擦除不表示parser/OpenSSL全堆锁页。allPrivateMaterialsVerified仅量化strict子集/签名关系，
不声称任意DER规范性或所有格式完整消费。

v1 plaintext/所有mutation、provider注册/恢复/connector及生产证书拒绝门均未变。持久
关联authority/覆盖、密码实际target/account/username、完整CA/时间/主体/撤销/reviewer、
动态资源及真实Request逐字段消费、完整客户端父AC仍开放。本地未提交，无设备/真实库
账号/安装/commit/archive/push/dist。附带证书事实研究仅为后续源码依据，未实现或运行。

```sh
./hako bash -lc 'cd android && ./gradlew --init-script /app/build/ctos-detached-offline.init.gradle :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease :app:testSshPrivateMaterialHost :app:testSshPrivateMaterialStaticHost :app:testSshKeyAssociationHost :app:testSshKeyAssociationStaticHost :app:testSshMaterialInspectorHost :app:testSshMaterialInspectorStaticHost --rerun-tasks --offline --console=plain'
./hako bash -lc 'cd android && ./gradlew --init-script /app/build/ctos-detached-offline.init.gradle -PctosSecretsUiFixture=true -PctosSshAuthFixture=true :app:assembleSecretsFixtureRelease :app:assembleSecretsFixtureReleaseAndroidTest :app:lintSecretsFixtureRelease -x :app:compileSshProductionObjects -x :app:compileSshConfigEvaluator -x :app:compileSshFiles --offline --console=plain'
```

相同临时offline init显式no-index/find-links；四既有wheel及cache source SHA和ZIP完整性本轮
重核通过，生产依赖配置不改。新平台方法精确opt-in，编译/DEX存在不作为Android运行证据。

| 工件 | SHA256 |
| --- | --- |
| 普通 APK | `0899625b7d1afbe14412ca35e43965a857896f1654505a900896664443dd7784` |
| Fixture APK | `4fd9c2b9d97a13f1062935ec9327a2470d419304b4bad3fe2540ce4e60179624` |
| 测试 APK | `7425ab8afb767a70b4aec23133b9d75e8421d735b08d505d7cd4c9e7b0545a2a` |
| 共同包内SSH库 | `d29a9e42f9f5c263015523e183669a1e545d67187c9137ef39115fa63e54af1b` |
| 生成SSH库 | `51f8aa2e870214cf55c916e193e05b7bc94239ad3842febfe90959962f53b789` |


<a id="20261023-key-association"></a>
## 2026-10-23 显式私钥与公钥/证书 subject 配对证明

[合同](ssh-key-association.md)与[规范](../.trellis/spec/backend/ssh-key-association.md)已实现
精确所选三ID tuple、实际supplied loan的独占所选副本、固定domain的真实签名/验证及
CSKAv1。公有字段相同但秘密种子不同的Ed25519材料实际返回MISMATCH/SIGNER。
证书demote后以plain subject计算摘要/验签；过期host证书的subject可MATCH，不表示信任。
显式口令按entry ID选取，同identity的其他条目不自动复制/重试；省略保持NEEDS。
新pair拒绝传统加密PEM及明文键额外口令，新增RSA所有组成字段16384bit和multiprime限制，
旧CSMI/auth/shared key policy保持。报告恒explicitPairsOnly=true/admissionComplete=false，
trustChecked/credentialTargetsChecked=false，不注册provider/reviewer或开放恢复/connector。

证据：`/tmp/ctos-ssh-key-association-20261023.1p6pj89y`。

| 检查 | 实际结果 |
| --- | --- |
| 接管before |405文件baseline，前resource记录保留为history；未修改无关源码 |
| 首次native/Java compile |41tasks10执行31UP-TO-DATE，18s，通过 |
| 定向JVM |80tests/4reports，失败/错误/跳过0；新增22方法 |
| 最终普通构建 |96tasks全部重跑，1m42s；strict动态/静态pair及原material编译、JVM/lint/Release通过 |
| fresh全量JVM |586tests/35reports，失败/错误/跳过0 |
| 最终实际ARM pair |13814checks（复用基线7773；因pair日志观察器不同，不冒充原7784计数）、32signs/52verifies/effects0/cleanup pass |
| 独立旧material ARM |7784checks/effects0/safe_logs4/streams34/cleanup pass |
| 原CONFIG缓存二进制实际重跑 |Resource4439/Audit13290/managed3683/direct1261/eval3736/catalog71923/handle5201，exit0/effects0；未将缓存执行写成新编译 |
| 隔离fixture |111tasks37执行74UP-TO-DATE，21s；复用最终native，main/testJava/两DEX/测试包重编译 |
| lint |normal0errors3warnings，fixture0errors4warnings |
| APK审计 |三v2、同一单signer、独立fixture/test target、normal无peer；普通/fixture SSH库全字节相同；22ALLOC节匹配生成库 |
| 导出/固定依赖 |config15/session19/material1/newPair1 JNI，archive83；parser patch7afd7a9a…d60fd5e保持 |
| 最终源码 |source9/protected292全部匹配，final-source副本及四native inputs manifest可复核 |
| Fresh认证/PTY ARM |当前源码/最终archive重编译；沙箱外AF_UNIX合成1748333checks/exit0；沙箱内getsockopt EPERM不算产品失败或通过 |
| Android运行 |未安装/未执行新JNI或平台fixture；ARM QEMU不是Android VM/锁页/真实端点证据 |

首次/第二次ARM因新实际失败签名触发固定OpenSSL日志`EVP_DigestVerify failed`，旧观察器
拒绝该尚未列入的格式并abort。核对pinned ed25519-openssl.c后，pair-only观察器加入这个
无参数常量；不放宽原material观察器。原matrix独立7784与最终pair均通过。额外认证
回归的沙箱内执行被getsockopt EPERM拦截；源码和测试未因此放宽，沙箱外合成复跑结果
已通过1748333项（native-auth-fresh-final-unsandboxed.log/exit0）；旧缓存第一次也在限额恢复
处abort，不采用其构建证据。首次APK审计inline脚本语法错误后修正重跑通过，无产物变更。

新API只证明明确所选pairs。整库关联authority/coverage、全格式完整消费、口令全局唯一
归属、密码实际目标/用户名、CA/时间/主体/撤销与reviewer、动态/cwd/key-loader/default/
final Request、完整provider/恢复/connector/父AC继续开放。单次KDF/crypto/证书内嵌验签
不可抢占；PEM parser内部RSA_blinding_on等成本先于新RSA检查，不能声称被其消除。
JNI采用有界heap scratch并擦除，不声称parser/OpenSSL所有堆全量锁页。
本地未提交，无设备/真实库账号/安装/commit/archive/push/dist。

```sh
./hako bash -lc 'cd android && ./gradlew --init-script /app/build/ctos-detached-offline.init.gradle :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease :app:testSshKeyAssociationHost :app:testSshKeyAssociationStaticHost :app:testSshMaterialInspectorHost :app:testSshMaterialInspectorStaticHost --rerun-tasks --offline --console=plain'
./hako bash -lc 'cd android && ./gradlew --init-script /app/build/ctos-detached-offline.init.gradle -PctosSecretsUiFixture=true -PctosSshAuthFixture=true :app:assembleSecretsFixtureRelease :app:assembleSecretsFixtureReleaseAndroidTest :app:lintSecretsFixtureRelease -x :app:compileSshProductionObjects -x :app:compileSshConfigEvaluator -x :app:compileSshFiles --offline --console=plain'
```

显式offline init与前轮同一四个cached wheels/no-index/find-links，仅构建环境引用，生产
依赖配置未改。完整原始XML/log、APK ZIP/signature/manifest/nm与最终hash在证据目录。

| 工件 | SHA256 |
| --- | --- |
| 普通 APK | `8919c45b814d92f70dec52ba00a0835b538c3aa7971c1e7372293cbd223678a3` |
| Fixture APK | `a6e294a217f261de2cc3994586479885d2a64edf2d02e531ec71c067ab570525` |
| 测试 APK | `17157b32d09bc5d692d2f1d43054c821caf03c97406b0a5637b00459cbb586aa` |
| 共同包内 SSH 库 | `771438db49ad9899b629c967d4440c6ba1388002ad83aee9924f8be21262cb23` |
| 生成 SSH 库 | `2c6c490023c7f5d9e7052f8f70b9c8bc74dd1759d233b3fd5d9de5c73e10143d` |


<a id="20261023-config-resources"></a>
## 2026-10-23 CONFIG 显式资源类型与直接引用

范围：[资源合同](ssh-config-resources.md)、[执行规范](../.trellis/spec/backend/ssh-config-resources.md)。
新增独立RESOURCE_AUDIT，五类显式Identity/Certificate/UserKnown/GlobalKnown/Revoked声明
通过同一lexer分类，仅按原metadata绑定静态literal base/type与真实parent直接references。
原EFFECTIVE/CATALOG/AUDIT与CSDI/CSEQ/CSES/CSCD/CSCAv1保持；新CSCR嵌套exact CSCA。
新mode共用原owner/原期限/取消/CONFIG-only独占输入/整job预算/cleanup与最终wipe fence。

静态资源引用不是完整准入：relative保留CWD，percent或${保留context，missing exact I/Cert
base保留KEY_RESOLUTION；Global全字面（$可表示、%不可表示），Certificate none不是禁用。
Identity可PRIVATE/PUBLIC/CERT但不证明签名能力，Revoked只PUBLIC_KEY文本子集，不代表KRL/
多key列表；不探.pub/-cert、不推断数学关联。默认文件与最终effective消费仍后续。新字段
只证明已STATIC-bound行，恒declarationsOnly=true/admissionComplete=false，不注册生产
validator/reviewer、不开放恢复/connector，父AC继续in_progress。本地未提交，无设备/真实库账号。

前轮source15/protected307核对，保存399文件before；daemon中断发生在研究后、实现前，恢复
时重新核对代码基线一致后继续。最终source15/protected311与当前一致，无额外代码变化。
本轮native6+parser补丁+新C测试、Java2+两JVM测试+一平台fixture、Gradle/runner，共15源。
独立checker源码及原始证据审查无具体遗留finding；本轮各首轮门禁均通过，无隐藏失败轮。

| 检查 | 实际结果 |
| --- | --- |
| 首Java生产编译 | 38tasks，7执行/31UP-TO-DATE，10s，排除native三任务，通过 |
| 首native/JNI strict | 7tasks，3执行/4UP-TO-DATE，15s，通过 |
| 新旧native全矩阵构建 | 15tasks，11执行/4UP-TO-DATE，25s，strict+Resource dynamic/static+六组旧static通过；dynamic只编译 |
| 真实ARM RESOURCE | **4439checks（含direct基线1262），effects0/capabilities0/exit0**；normal最终生成的AArch64 test binary另实跑一致 |
| 真实ARM旧矩阵 | AUDIT**13290**、managed**3683**（均含基线）、direct**1261**、eval**3736**、catalog**71923**、handle**5201**，effects0/exit0，detached类capabilities0 |
| host文件/IO | **426cases/1634wipe_checks，通过**，同源重新编译执行 |
| 定向JVM/lint | **151tests/9reports，0failures/errors/skips**；56tasks，18执行/38UP-TO-DATE，26s |
| 最终normal全重跑 | **94/94tasks执行，1m52s**，完整JVM/lint/Release+新Resource dynamic/static |
| 最终fresh完整JVM | **564tests/34reports，0failures/errors/skips**，新增27个JVM方法 |
| fixture主包/test/lint | **111tasks，37执行/74UP-TO-DATE，21s**，复用最终normal native（3compile任务排除）；主/testJava、两DEX、testpackage重编译 |
| lint | normal/fixture **0errors，3/4既有warnings** |
| 三APK审计 | v2/同一单signer/隔离ID/精确fixture target；normal无peer、生产SSH库同字节；22ALLOC节对应生成库、configJNI15/session19/material1/archive83，无生产wrapper/seed |

真实parser矩阵覆盖23资源行/all states、五kind allowed/wrong types、none差异、Global与
其他kind的美元/percent路径、静态C1与非法path拒绝、缺基名不认证suffix、nested父原ordinal/
line/arg、重复Include去重且不跳IO、嵌套CSCA等于旧AUDIT、Known32/33/Revoked arg256、
1024/1025 rows、有限policy/Match集成、旧mode不升级与owner/cancel/getter失效/cleanup重试。
仅metadata查找，不读非CONFIG正文。新JVM12codec/facade+15inspector方法覆盖strict closed
states/locator九位/三层quota与wipe、max107328、所有typed集合/真实parent而非root-sibling-
identity、deferred/旧flags/all-orphan-zero、wholebytes/combined4096rows、原work预算、reserve
取消空窗/暂停getter/Error/cleanup重试/最终fence。平台fixture只编译合成metadata，不冒充
材料有效或实际JNI运行。

parser补丁SHA256：`7afd7a9a4ed9c17096b1bb4a3cd20d41ec9ed2d5720df5c552da93384d60fd5e`，
runner固定值一致；原固定OpenSSH10.6p1/OpenSSL3.5.9/NDK27、G1/上游源树与83成员archive
合同保持，无新第三方依赖。四既有缓存wheel重新SHA/ZIP核验，临时no-index/find-links init
继续复用，生产依赖声明未改。测试与生产源码/补丁输入逐项关联，不把独立test object的临时
__FILE__路径差异冒充原object全hash相等；host ARM不是APK Android执行。
独立核对config_eval.o字节全等；readconf.o只差8字符临时目录，归一该目录后对象全等
（normalized SHA256 `d3c16e26d5d8699fda6a7a53eb44aa22c10b502bccd65639f6c79584d3a182db`），
不声称原readconf.o同hash。

最终SHA256（均未安装）：

- normal：`afe1a021409a6517ba4304c318f3a06baacdde80bd991e7f49e59f22ecf6b580`
- fixture：`fe7fcd1940f73e81cd50ac3a9fb496dcfdb8002a4b6e0d413d6cd29890dc836b`
- test：`bdfc94e2e443e04ed1e93e7ed0e1be2e8a2d054d02249f9a50a75f97857bc55a`
- packaged SSH库：`5e390ecf3f8dfafa02747529e303a43024752c2977255f4aa5c2b4ad24a88075`
- generated未strip库：`d97bd27cc4ca28fa3bed38ac34baaa85f3ba50df77804f867a06300d33d33256`

共同单signer证书SHA256：`8d36c8be418174cb4cda0b897ff3e32b2da6f84032819726e8e5671872df52ef`。
证据：`/tmp/ctos-ssh-config-resource-20261023.gmfy0g24`含399before、首/末源码与hash、
strict/native/runtime/原始XML/lint/build log、三APK/签名/manifest/nm/archive/ALLOC审计、
source15/protected311/artifacts hashes与独立报告。临时证据消失不得冒充仍可复核。

实际JNI VM异常/Android锁页与新平台fixture未执行；宿主8192KiB锁页限额和8MiB仅格式/
故障清理、6MiB真实锁页的区分保持，无unlocked fallback或xmalloc OOM恢复承诺。
未运行Flutter/认证/PTY/设备/真实库账号，无安装/commit/archive/push/dist。后续仍需精确
私公钥/证书数学关系、凭据实际目标/口令唯一绑定、完整信任/reviewer、动态/cwd/key-loader/
默认与最终Request强制点，全部义务/入口完成后才注册生产provider、恢复和最终connector。

<a id="20261023-config-audit"></a>
## 2026-10-23 CONFIG 静态引用与本地效果审计

范围：[审计合同](ssh-config-audit.md)、[执行规范](../.trellis/spec/backend/ssh-config-audit.md)。
新增独立AUDIT模式，从实际独占VaultManifest loan逐CONFIG扫描全部分支的有限本地效果/
信任绕过政策，记录实际静态Include父/行/子并检查该真实parent的直接references。原
EFFECTIVE/CATALOG与CSEQ/CSES/CSCD合同保持，AUDIT用CSCA嵌套exact CSCD及实际边。
原inspect不升级；新inspectAudit复用原owner/CONFIG-only副本/原期限/取消/累计预算/清理。
报告恒declarationsOnly=true/admissionComplete=false，两新字段只证明本次有限政策及已观察
静态边直接引用；动态Include仍deferred，不证明其他typed引用/配置值/身份/凭据/信任语义。
生产validator/reviewer、恢复/最终计划connector与父AC仍开放，本地未提交，无设备/真实库账号。

前轮source15/protected303核对一致，保存393文件baseline。最终source15/protected307与当前
源码一致，无额外代码变化。新增native6文件+parser补丁+专项C测试、Java2文件+两JVM测试+
一平台fixture，以及Gradle/runner，共15源文件。独立checker复核源码与实际证据，无具体遗留问题。

| 检查 | 实际结果 |
| --- | --- |
| 首轮Java生产编译 | 38tasks，7执行/31UP-TO-DATE，10s，通过，排除native三构建任务 |
| 首轮native严格编译 | 5tasks/7s失败：新helper在WHITESPACE宏定义前使用，见下文 |
| 修宏后native/JNI strict | 7tasks，3执行/4UP-TO-DATE，14s，通过 |
| 定向JVM/lint | **124tests/7reports，0failures/errors/skips**；53tasks，15执行/38UP-TO-DATE，16s，native三任务排除；不混称最终native运行 |
| 最终新旧native构建 | **14tasks，10执行/4UP-TO-DATE，21s**，strict+新AUDIT dynamic/static与五组旧static通过，dynamic只编译 |
| 真实ARM新AUDIT | **13290checks，含direct基线1262，effects0/capabilities0/exit0**；normal最终生成的binary另实跑一次一致 |
| 真实ARM旧矩阵 | managed-detached**3683**含基线、direct**1261**、eval**3736**、catalog**71923**、handle**5201**，均effects0/exit0，detached类capabilities0 |
| host文件/IO | **426cases/1634wipe_checks，通过**，同源重新编译执行 |
| 最终normal全重跑 | **94/94tasks执行，1m39s**，完整JVM/lint/Release+新AUDIT dynamic/static |
| 最终fresh全量JVM | **537tests/32reports，0failures/errors/skips**，新增23个JVM方法 |
| fixture主包/test/lint | **111tasks，37执行/74UP-TO-DATE，21s**；复用最终normal native（三compile任务排除），主Java/testJava/DEX/测试包重编译 |
| lint | normal/fixture **0errors，3/4既有warnings** |
| APK审计 | 三v2/同一单signer/隔离ID/精确fixture target；normal无peer；相同生产SSH库、22ALLOC节对应生成库、configJNI13/session19/material1/archive83，无生产wrapper/测试seed |

首strict失败为新policy helper引用后方才定义的WHITESPACE宏；改为在原callsite按原规则
正规化raw remainder后传入helper，保留raw command的exact-none语义。首轮源码/失败log
保留。定向JVM本轮首次即124通过，无JVM失败被隐藏。

正式审查还发现AUDIT复用Match的early-all时遗漏caller的剩余ac检查：`Match all "" exec foo`
可提前结束而留下参数。现在仅syntax-only分支在提交pending政策错误前检查完整消费，helper
也防御检查，语法错误优先INPUT；旧matcher/all规则不变。真实矩阵覆盖该例、host余参、
先exec后all空lookahead及尾部junk，另保留原允许的`Match all ""`/`Match final all ""`。
纯multistate坏值/标量arity改为INPUT，合法enabled按family拒绝；AddKeys等复杂非禁用形态
直接EXTERNAL拒绝，不宣称完整复杂值语法。quoted/commented raw-none与provider argv-none
区别、Strict true/ask、ControlPersist大小写、编译禁用优先和CNAME有限政策均有实际断言。

ARM矩阵验证所有分支与first-value之后的禁止指令、Match属性位置及安全criteria值、实际
parent/line/child原ordinal、DFS/glob/重复triple、静态/动态混合Include、1023边/1024opens
真实边界、mode guards、原usage/预算、getter后取消/过期擦除、busy/owner与cleanup重试。
原catalog仍接受文档中的非法Port/Match/本地命令声明例，AUDIT不把非政策值当完整有效配置。
独立测试与production的config_eval.o字节一致；readconf.o含不同8字符临时__FILE__目录，
规范化该路径后对象全等，不声称原object同hash。ARM最终binary为AArch64，同源/补丁输入
逐项对应；这些host测试不等同已打包APK的Android运行。
JVM覆盖CSCA82732/1024edge严格codec、outer/nested畸形/UTF8/重复/配额预检、真实parent而非
root/sibling/identity的直接引用、typed来源、all/orphan/noCONFIG、旧inspect flags、whole-CSCA
只计一次、累计rows/bytes与原work预算、暂停getter取消/Throwable/清理重试及最终fence。

最终补丁SHA256：`72f09e8a4cb7766c80e1ebb3123b34a61ac6e5d361bcaa611b36156a2a75f17b`，
runner固定值一致；固定OpenSSH10.6p1源树/原G1输入hash保持，未修改上游缓存源树或生产依赖。
仍使用前轮四个既有wheel（本轮重新SHA/ZIP核验）及临时no-index/find-links Gradle init。

最终工件SHA256（均未安装）：

- normal：`54d64b0896aa2257d0f49b4ab468adac765387534b37e2701f89e25cae4f2701`
- fixture：`cc2d7aad7048ad47e8e37990b45e41377ed60dd7b7d5fd8c1c64e3d64d4eaffa`
- test：`2c637750d4f9af8335e5edefcc48c460552e05ae87ea87e55a9d1d6aeb8cdd64`
- packaged SSH库：`e09350a1b31f04862e351b4096e1c06ecce8cc34bb1087e85c99ddeaa8384e7c`
- generated未strip库：`52535a0657e23b1d026d53cc409164cb7cbbf94a431154978a1f1a7079ae4fd5`

共同单signer证书SHA256：`8d36c8be418174cb4cda0b897ff3e32b2da6f84032819726e8e5671872df52ef`。
证据：`/tmp/ctos-ssh-config-audit-20261023.uvozbgq0`，含393文件before、first/second/third/
final源码、失败与最终log、ARM二进制/inputs/runtime、原始XML/lint、三APK/签名/manifest/
nm/archive/ALLOC审计、source15/protected307/artifacts hashes与独立报告。

平台fixture仅编译，实际JNI pending-exception/Android锁页未运行，C getter失效seam不替代
VM实测。宿主8192KiB锁页限额及8MiB仅格式/故障清理与6MiB真实锁页的区分保持；无未锁页
fallback，也不宣称恢复了上游xmalloc系统OOM。无Flutter/认证/PTY运行、设备/真实库账号/
安装/commit/archive/push/dist。非Include typed引用、动态真实Request义务、密钥/证书数学
关联、凭据目标/信任/reviewer及完整生产准入仍后续；父task保持in_progress。

<a id="20261023-detached-managed"></a>
## 2026-10-23 实际清单受管配置检查

范围：[受管合同](ssh-detached-config-managed.md)、[执行规范](../.trellis/spec/backend/ssh-detached-config-managed.md)。
复用原SSH任务，仅本地未提交。Java/JNI现在可检查实际独占VaultManifest loan的全部CONFIG，
包括orphan；原ordinal、声明状态与原取消/期限保留。报告恒declarationsOnly=true、
admissionComplete=false。Include引用边/全分支政策、身份/凭据/证书/信任完整语义provider、
生产恢复、最终连接计划/connector与父AC仍开放；本轮无注册、不操作设备或真实库账号。

native reserve先发resource-free token，Java保留facade后load到已由slot/eval拥有的
DONTDUMP/锁页单份CSDI映射，原地adopt路径/CONFIG正文，非CONFIG不带正文。JNI-copy
失败保留pending exception和cleanup owner；close/destroy失败可通过已保留inspector重试。
全job累计1024opens/64MiBreads/16384lines，逐root传剩余额度，native在超额操作前拒绝；
复制放大预检64MiB、累计结果1MiB/4096rows，decoder分配前检查剩余结果预算。原live
接口/错误码/默认预算保持；原有CSEQ/CSES/CSCD wire不变，新增CSDI与三项JNI。

接管核对前轮source7/protected272，保存383文件baseline。本轮source-final15与protected303
当前一致，无额外代码变化；before/首轮/最终快照和失败日志保留。独立checker复核源码、
测试与原始结果；当前无遗留具体finding。

| 检查 | 实际结果 |
| --- | --- |
| 首轮native/JNI严格构建 | 7tasks，3执行/4UP-TO-DATE，16s，通过 |
| 最终strict与六组native构建 | 13tasks，9执行/4UP-TO-DATE，20s，通过；dynamic只编译 |
| 新真实ARM staged矩阵 | **3683checks（含直接输入基线1262），effects0/capabilities0，exit0**；normal全重跑生成的最终binary再次实跑一致 |
| 原真实ARM矩阵 | 独立输入**1261**、求值**3736**、目录**71923**、handle**5201**，effects0/exit0 |
| host文件/IO矩阵 | **426cases/1634wipe_checks，通过**，最终IO源码重新编译执行 |
| 首轮定向JVM | 96tests/5reports，1failure；新fixture关联错误，见下文 |
| 最终定向JVM/lint | **101tests/5reports，0failures/errors/skips**；56tasks，19执行/37UP-TO-DATE，26s |
| 最终normal全重跑 | **94/94tasks执行，1m58s**，完整JVM/lint/Release与新dynamic/static native |
| 最终fresh完整JVM | **514tests/30reports，0failures/errors/skips**；新增31个JVM测试方法 |
| fixture主包/test/lint | **111tasks，37执行/74UP-TO-DATE，22s**，三项production native编译排除，复用最终normal native；fixture主Java与instrumentation Java/DEX/测试包重编译 |
| lint | normal/fixture **0errors，3/4既有warnings** |
| 三APK审计 | v2/同一单signer、隔离包名/精确fixture target；normal无peer；同一生产SSH库，22ALLOC节对应生成库，configJNI11/session19/material1/archive83，无测试wrapper |

首轮JVM失败为新测试passphrase entry缺少匹配PRIVATE identity；修正测试关联，未放宽
VaultManifest结构校验。自查与独立复核纠正Java多加的`ssh/`前缀限制，改用既有相对路径
政策与严格UTF8/ISO检查；补边界回归。新增结果预算在解码前执行、zero-CONFIG最终期限、
取消Error传播和清理Error重试测试。Native补齐absent request/nonzero length拒绝及回归。
独立审查还发现新平台fixture的`Include child`不匹配`ssh/child`；改为`.ssh/child`，保留
原Include锚点与原ordinal断言后重新编译。该fixture未安装/运行，不据编译推定JNI实测。

新native矩阵实测reservation/load/usage/close/destroy、单份受保护存储指针、部分复制abort、
取消attachment与busy重入、CSDI边界/模式、64槽、原Request、静态glob/动态deferred和实际
剩余预算执行。JNI GetArrayRegion异常顺序为源码复核/编译和C abort seam验证，未在真实VM
执行。JVM包含实际caller loan与仅CONFIG深复制、全部/孤立/零CONFIG roots、原始来源、
累计预算精确/超额、reserve空窗与暂停load/run取消、重入/Throwable及失败owner保留重试。

最终工件SHA256（均未安装）：

- normal：`6d57ef36d1720904b2230a00bde42ea47820d8bb9e04af2d93275bbe8a66de94`
- fixture：`b6dc530b150515049807ba9ed0ca4819d4cefa51618fae6585c8c9d972e72b6f`
- test：`4fdd949749f5f45af38e7a97ba619625ede19bebe31eb0f3bc6e3de98da53f2b`
- packaged SSH库：`426368dfc55a9a2b777045d05e69293b1cd50c9d926b89a653635517ca0c5acf`
- generated未strip库：`da0655eafcd047fa18529115b206c186be8f721637235e32531e19446d53edad`

共同单signer证书SHA256：`8d36c8be418174cb4cda0b897ff3e32b2da6f84032819726e8e5671872df52ef`。
依旧使用前轮已核验四个既有缓存wheel与临时no-index/find-links Gradle init，生产依赖声明
未改。本轮没有新依赖源站重试；--offline本身不代替Chaquopy pip显式离线选项。

证据：`/tmp/ctos-ssh-detached-managed-20261023.oeRy2d9W`，含383文件before、首轮失败与最终
源码、native二进制/输入hash/runtime、原始JVM XML/lint/构建log、三APK/签名/manifest/nm/
archive/ALLOC审计、source15/protected303/artifacts hashes与独立报告。临时证据消失后不得
冒充可复核。宿主8192KiB锁页限制与先前6MiB实测/8MiB格式故障清理的区分继续有效；
本轮不声称Android锁页、JNI运行、Flutter或认证/PTY runtime，也无commit/archive/push/dist。

<a id="20261023-detached-config"></a>
## 2026-10-23 独立 native 配置输入

范围：[独立输入合同](ssh-detached-config.md)。复用原SSH任务，仅本地未提交；无设备、
真实库/账号、安装、Android/JNI实机、commit/archive/push/dist。新接口为C-only基础，
Java/JNI整库编排、Include引用边/全分支政策、身份/凭据/证书/信任及完整provider、
恢复/最终计划/connector与父AC继续开放。

实现文件为四个native IO/evaluator文件、新专项C测试、Gradle与现有runner，共7个。
独立输入拥有类型/路径/CONFIG正文副本，非CONFIG仅metadata；无虚构vault tuple或live
view。原BOOTTIME期限、owner/cancel与失败构造CLOSING清理owner保留。自查补回旧的
Request/port先于borrow校验、missing-private ENOENT/existing-private EACCES顺序，并加断言。

接管时保存379文件baseline，旧TOTP mutation的/tmp证据已失，不声称重验旧hash。
最终source7/protected272均核验一致；独立checker复核增量源码、测试与实际证据无遗留问题。

| 检查 | 实际结果 |
| --- | --- |
| 首轮strict及旧配置构建 | 10tasks，6执行/4UP-TO-DATE，19s，通过 |
| 专项首次dynamic/static构建 | 7tasks，3执行/4UP-TO-DATE，15s，通过；首次runtime失败见下文 |
| 错误码修正后strict与配置矩阵构建 | 12tasks，8执行/4UP-TO-DATE，18s，通过 |
| 最终专项ARM静态执行 | **1261checks，effects0，capabilities0，wiped_sources53，exit0** |
| 旧真实ARM求值/目录/handle | **3736 / 71923 / 5201checks，均effects0/exit0**；首轮和最终源码各实跑 |
| host文件/IO | **426cases，1634wipe_checks，通过**；最终IO源码重新编译执行 |
| 最终normal全部重跑 | **94/94tasks执行，1m43s**，含新专项dynamic/static编译及完整JVM/lint/Release |
| 最终fresh JVM | **483tests/28reports，0failures/errors/skips** |
| normal lint | **0Error/Fatal，3既有warnings** |
| fixture主包/test/lint | **111tasks，33执行/78UP-TO-DATE，17s**；复用最终native输入，0errors/4warnings |
| APK审计 | 三v2/同一单signer/隔离包名与精确fixture target；normal无peer，生产库字节一致 |

最终新测试覆盖独立输入及caller擦除/free、非默认与孤立root、原始ordinal、static/glob/
deferred Include、实际Request/完整Options、type/path/count/body边界、精确now==deadline、
cancel/错线程/PID/UID、parser重入/EBUSY、reader失败及构造/close清理重试。live capability
helper均由独立计数拒绝探针覆盖且调用0次；不把这解释为整库引用语义或准入证明。

### 失败轮与环境边界

1. 专项首次runtime在测试madvise wrapper失败：把libc的MADV_DONTNEED当成受管区域。
   修正为仅追踪DONTDUMP映射，其他libc advice/unlock/unmap直接转发；受管擦除/故障断言
   保留，最终还检查所有追踪映射已释放。首次源码/二进制/log/core保留。
2. 第二runtime在最大正文用例返回ENOMEM。宿主soft/hard RLIMIT_MEMLOCK均8192KiB；
   独立qemu-strace确认mmap8405760与DONTDUMP成功、mlock8405760返回errno12并unmap。
   最终9MiB须在锁页前E2BIG，exact8MiB以故障注入证明已通过格式校验并清理，实际
   成功锁页/解析为6MiB输入加1MiB reader。没有模拟锁页成功；不宣称8MiB实际锁页通过。
3. 最初normal92/92tasks、483JVM、lint/Release通过，随后做上述两处errno修正。
   最终重跑的第一次及PIP环境变量重试分别在Chaquopy依赖阶段因源站TLS/超时失败
   （6tasks/1m17s和9tasks/1m23s），不据此计最终应用通过。插件未采纳PIP环境变量。
   从既有pip缓存原样复制四个wheel到被忽略的build目录，验证ZIP和SHA后，用临时
   Gradle init显式pip `--no-index --find-links`；最终94task日志确认四包均从本地处理。
   版本为cryptography42.0.8/cffi1.17.1/pycparser3.0/chaquopy-libffi3.3，不改生产依赖声明。
4. fixture显式排除三native编译task，复用normal最终生成库；前后生成库hash一致。
   fixture peer编译与lint analysis/report执行，未变的main/test Java编译、DEX和test打包复用；
   这不是instrumentation运行。AGP strip后库与生成库全文件hash不同，22个ALLOC节
   的类型/flags/地址/长度/内容逐一相同；normal/fixture包内库全字节相同。

normal warnings为QueryPermissionsNeeded、AndroidGradlePluginVersion、DataExtractionRules；
fixture为QueryPermissionsNeeded、两处UnsafeDynamicallyLoadedCode和DataExtractionRules。
Shell `bash -n`、ShellCheck、shfmt及diff检查通过。
原始源树/patch由固定runner校验，仍OpenSSH10.6p1/OpenSSL3.5.9/NDK27；archive83、
configJNI8/sessionJNI19/materialJNI1，生产无test wrapper/seed，专项test二进制不进APK。

### 工件与可复核入口

| 工件 | SHA-256 |
| --- | --- |
| normal-final.apk | `1059397d833d91582c7ba99bc800f8a408b8dcbf208ca2d154aebb57a5e50432` |
| fixture-final.apk | `3536ff7a56ef04c5307fd5f32de38407f2dfaf36dcaa153e0fa7e8d3d393e984` |
| fixture-test.apk | `3c431f96129672930e9731dcf85a2d400726a8361be6e360d1ccf730e4e7404c` |
| 共同包内libctos_ssh_files.so | `fd2ae50d1bb1562067683bd1b60bd6a6a6d63dbbf5743b187ca479261aee8b40` |
| 未strip生成库 | `00fb79b566ebc2242f4629ac79937a60ddf1469db83b86fd531fa46b91e38244` |

证据：`/tmp/ctos-ssh-detached-config-20261023.mUF8aSF0`。含before/first/second/final源与
二进制、increment-source.diff、source-final/protected-final SHA、全部失败/最终日志、
full-final-jvm原始XML、lint XML、APK、签名/manifest/symbols/archive审计。临时目录可能
被清理；该历史记录不保证未来仍可重验。signer SHA256为
`8d36c8be418174cb4cda0b897ff3e32b2da6f84032819726e8e5671872df52ef`。

最终本地命令使用 `./hako bash -lc`，Gradle均带 `--offline --console=plain`：

- native：`:app:testSshConfigDetachedStaticHost :app:testSshConfigDetachedHost`
  及原EvaluatorStaticHost/CatalogStaticHost/HandleStaticHost；后者各执行独立qemu-aarch64。
- 完整normal：`--init-script /app/build/ctos-detached-offline.init.gradle`
  加上述两新专项及`:app:testReleaseUnitTest :app:lintRelease :app:assembleRelease --rerun-tasks`。
- fixture：同init，`-PctosSecretsUiFixture=true -PctosSshAuthFixture=true`，
  assembleSecretsFixtureRelease/assembleSecretsFixtureReleaseAndroidTest/lintSecretsFixtureRelease，
  排除compileSshProductionObjects/compileSshConfigEvaluator/compileSshFiles。

init、wheel来源/hash在证据offline.init.gradle和cached-wheels.json；原声明parser补丁未改。
无Flutter改动/运行、认证PTY全矩阵或Android实机运行；新配置源尚无Java入口，完整语义
provider和SSH恢复门继续关闭。单次同步libc/parser调用不保证可抢占，整个任务未完成。

<a id="20261023-totp-mutation"></a>
## 2026-10-23 TOTP 写入与原预览票据交接

范围：[TOTP mutation合同](totp-mutation-lifecycle.md)。继续原SSH task，仅本地未提交；
无设备/真实库账号/安装/Android运行/commit/archive/push/dist。生产SSH语义provider、
恢复/最终连接计划/connector与父AC未完成。

- 上轮trust8source/266protected/732artifacts核验一致；本轮保存615文件before基线。
- 五个生产Java与两个测试文件：原origin、独立一次context、source/candidate/loan独占、
  锁外校验与唯一effect claim；预览跨Request交接保留原期限，capture清理不撤销票据。
- 独立审查修复64能力回收、摘除与发布的取消可见性、BUSY第二执行者误清理、迟到
  prepare/旧epoch/其他core/旧revision替换、pin安全发布和Bridge传输异常清理。
- 首轮编译失败17s：新测试局部变量重名，未运行测试。第二轮194/11reports有1失败：
  旧取消测试在admission后替换validator，先触发新注册fence；改为admission前注册同一
  callback，签发后只设置其取消目标，保留CANCELLED断言。第三轮195/11reports全通过。
- 最终定向196/11reports/47s，新增类14方法；全量483/28reports，失败/错误/跳过均0。
  mixed四操作×三暂停阶段×六失效共72场景；包含worker seed独占、loan/Throwable、
  原期限、精确owner、并发BUSY、17child上限和发布不读Store。
- normal全量重跑92/92任务、1m39s，lint0errors/3warnings；fixture114任务26s，
  lint0errors/4warnings。instrumentation Java与lint analysis执行，lint report为UP-TO-DATE；
  测试APK hash沿用相同产物，不宣称真实instrumentation运行。
- 两次独立native构建的lib hash不同，保留artifact-initial；用fixture同一native输入
  fixed正常包77任务9s再打包后，两包生产库一致，见下表。普通包无auth peer类/lib；
  fixture含peer，test无native库。三APK v2签名通过，签名证书SHA256为
  `8d36c8be418174cb4cda0b897ff3e32b2da6f84032819726e8e5671872df52ef`。
  包名为im.majo.ctos、im.majo.ctos.secretsfixture、im.majo.ctos.secretsfixture.test，
  instrumentation仅target fixture。
- source-final7/protected-final268经独立复核一致；source review无未解决问题。

| 工件 | SHA-256 |
|---|---|
| normal-final.apk | `2c1741874dd750e1d8e00c22d21760ba747fc1dbd7a651fcf71b5112b17f3898` |
| fixture-final.apk | `234a4882e6fdb0ad0d623cadf315528434c315391f59e1f4b75dcaed9b0e6e80` |
| fixture-test.apk | `3c431f96129672930e9731dcf85a2d400726a8361be6e360d1ccf730e4e7404c` |
| 共同libctos_ssh_files.so | `14fc64749df192e13fa8c8e1b65eee04d739b38f4dfa5e184ce7b810c9f6c8f8` |

证据：`/tmp/ctos-totp-mutation-20261022.envakycq`，保留每轮原始日志/XML、最终源码与
基线diff。Bridge生产源码已审阅并编译，未运行真实Android相机/图片/Activity流程；
原Store guard RuntimeException/Error failWrite限制保持，不承诺任意Throwable耐久回滚。
旧sessionManifest/beginMutation/validateBusiness已无调用；这不代表其他管理密码/
导出加密路径、完整语义provider与恢复全部迁移完成。


<a id="20261022-ssh-trust-cas"></a>
## 2026-10-22 SSH trust CAS 原审批与精确 view 延续

范围：[trust CAS 合同](ssh-trust-cas-lifecycle.md)。沿用原task，本地未提交；无设备、
真实库账号、Android/native运行、安装、commit/archive/push/dist。完整语义provider、
恢复/最终计划/connector及父任务验收仍未完成。

- 上一轮4source/269protected/684artifacts重新核验一致；本轮613文件before基线。
- 六个生产Java与两个测试文件：原审批context、锁外独立source/candidate/loan、provider
  取消与原view延续、提交后uncertain。新class10方法、既有provider新增4方法。
- 首轮定向199/10reports有4失败：3个新测试误用第二个context，1个既有测试错误码需
  反映原validator注册变化；修正fixture/预期后保留安全断言。另按源码审查补齐最终
  session→role→view fence、proposal创建失败cleanup、uncertain suppressed诊断。
- 最终定向202/10reports零失败/错误/跳过，54s；实际全量469/27reports零失败/错误/
  跳过。normal92任务全部重跑，1m40s；lint0errors/3warnings，Release构建成功。
- fixture114任务（36执行/78UP-TO-DATE），54s；lint0errors/4warnings。instrumentation
  Java重编译，未变化的测试DEX/package与lint报告渲染UP-TO-DATE，未运行instrumentation。
- 独立native重建初normal库与fixture不同，保留normal-first.apk；固定已编译输入重打包
  normal77任务（18执行/59UP-TO-DATE），20s，最终生产SSH库字节一致。
- 独立checker对最终源码/原始XML/log/ZIP/signature/hash复核通过，无遗留finding。
- 三APK均v2验签、同一signer；normal=`im.majo.ctos`、fixture=`im.majo.ctos.secretsfixture`、
  test=`im.majo.ctos.secretsfixture.test`且target仅fixture。normal无auth peer库/class，
  fixture包含peer，test无SSH native库。最终源码8/protected266哈希一致。

命令均通过`./hako bash -lc`在离线容器执行：定向`:app:testReleaseUnitTest --tests …`；
全量`:app:testReleaseUnitTest :app:lintRelease :app:assembleRelease --offline --rerun-tasks`。
fixture使用`-PctosSecretsUiFixture=true -PctosSshAuthFixture=true`及fixture assemble/test/lint；
固定输入normal排除compileSshProductionObjects/compileSshConfigEvaluator/compileSshFiles。
APK检查使用apksigner verify、aapt badging/xmltree及ZIP/DEX检查。

| 最终工件 | SHA-256 |
| --- | --- |
| normal | `b6f6e80f5dc939dc941f857fbb13a93852b41d38e4382e789fc73fc610c439e9` |
| fixture | `d573c78cd228356169532eb02b129ad2fc404db0f191177ac817df5f43c8f421` |
| fixture test | `3c431f96129672930e9731dcf85a2d400726a8361be6e360d1ccf730e4e7404c` |
| 共同production SSH库 | `51303ed8b5414df8a5760f1d185b2ef2c11e29ed34109b06e4cd1be6a676de51` |

原始证据：`/tmp/ctos-ssh-trust-cas-20261022.3koy1mha`，含baseline/before、源码快照与
增量diff、focused-first/final XML、full-jvm、日志及SHA清单。临时目录未来可能清理。

测试验证真实合成加密清单、暂停validator下取消/lock/期限/前台/注册/原始存储变化、
修改loan与Throwable清理、错误proposal/BUSY非消费、候选全数组独占、唯一effect claim、
wrapper保留、原role/view own-CAS及落盘后失效锁库。未运行Android AtomicFile；其既有
RuntimeException/Error的failWrite限制没有扩大。最终core延续是校验授权完成点；后续
回执检查交付存活性，不追溯重新校验已完成提交。后续typed读取仍有独立授权边界。


<a id="20261022-ssh-entry-mutation"></a>
## 2026-10-22 — Detached SSH credential save/delete lifecycle

Implemented the [entry mutation contract](ssh-entry-mutation-lifecycle.md), scoped to SSH_SAVE/
SSH_DELETE and adapter admission. Original metadata context precedes host approval and retains
owner/epoch/deadline/registration/cancellation; exact save id/target/connection or delete id/target
remain bound. Save admission does not approve future label/type/secret bytes supplied only at effect.
Actual source authentication, independent source/candidate validator loans, fully owned candidate
construction and encrypt/readback run outside core. First/last SSH candidates always validate.
Accepted effects consume once; wrong bindings and valid BUSY do not consume. Caller input is
cloned before slow host checks; candidate and borrowed loans never own caller/source arrays.

Final core still serializes the storage transaction. Wrapper capture and raw ciphertext equality
precede Store.commit; its guards check only original context/host/metadata, never reopen AtomicFile.
Only the original effect.beforeCommit claims the write, exactly once; context guard merely checks/
publishes admission. Success retains envelope id/generation/salt and wrapper, clears old operations/
preview and uses unlockIfEpoch. Confirmed post-persistence lock cannot resurrect a session.
VAULT_COMMIT_UNCERTAIN retains locking/invalidation; an already claimed commit is not safe to retry.
TOTP mutation, trust CAS/own-NEW-CAS, bundle/read paths and VaultStore implementation were not migrated.

Evidence:`/tmp/ctos-ssh-mutation-20261022.3u9ri93v`. Previous secret8source/264protected/707artifacts
were rehashed successfully;611files captured before work. Final4source and269protected Android/
Dart/test hashes match, with before/first-source/final-source and increment-only diff retained.

| Local check | Actual result |
| --- | --- |
| Focused JVM | **152tests/8reports,0failures/errors/skips**;46tasks,11executed/35up-to-date,45s |
| Fresh full JVM/lint/Release | **455tests/26reports,0failures/errors/skips**;92tasks,all executed,3m6s |
| Normal lint | 0Error/Fatal,3existing warnings: QueryPermissionsNeeded, AndroidGradlePluginVersion, DataExtractionRules |
| Isolated fixture main/test/lint | **114tasks,36executed/78up-to-date,30s**,pass;0Error/Fatal,4warnings |
| Fixed-input normal packaging | 77tasks,18executed/59up-to-date,19s,pass |
| APK audit | Three v2 signatures/same signer,isolated IDs/fixture target;normal excludes peer;production SSH library identical |

New SshEntryMutationValidationTest has11executed methods: paused admission/source/candidate
invalidation, source/loan mutation and checked/runtime/Error cleanup, candidate ownership both
close orders, first/last SSH, unchanged files/TOTP/wrapper/envelope identity, pre-host original
budget, input mutation during host check, wrong/BUSY/reentry/replay, final read/wrapper/preclaim
seams, exactly one effect claim, post-persistence lock, IO/uncertain and issuance-hook failure.
Existing typed/public/known_hosts own-CAS/bundle/TOTP/preview regressions passed. No failed run
or test weakening was required. Independent review aligned input-clone wipe before releasing
the mutation slot; outer cleanup remains idempotent for capture/argument failures.

MemoryStore forbids transaction read/quickMaterial reentry and models early rollback/uncertain
outcomes. It does not prove Android AtomicFile durability. Inherited VaultStore catches IO and
VaultException for failWrite, not RuntimeException/Error from a post-startWrite guard; that
unchanged limitation must not be confused with this increment's owned-array Throwable cleanup.
No native/Flutter runtime, Android/device/real vault/account, commit/archive/push/dist or complete
SSH semantic/restore claim. Task remains in_progress/parent implementation_complete=false;
next independent increment is trust CAS with exact approval and original public-view continuation.

| Final APK, not installed | SHA256 |
| --- | --- |
| Normal | `77340ea74c1eb72474ae3d1b218111e8d53c4e2da27e621d4d54f89745b5054b` |
| Fixture | `7fcc885cc7c17a4b7032a0b2884c384950b140e00d53dfde5c9bde7b1b7570a6` |
| Fixture test | `3c431f96129672930e9731dcf85a2d400726a8361be6e360d1ccf730e4e7404c` |

Production library:`2c274c3318f7ded14016ff56f377de75c6ee8be51c12ba610916006fb1a6b8ff`.
Signer:`8d36c8be418174cb4cda0b897ff3e32b2da6f84032819726e8e5671872df52ef`.
IDs:im.majo.ctos / im.majo.ctos.secretsfixture / im.majo.ctos.secretsfixture.test;
instrumentation targets only fixture. Initial normal4e656b89… retained: independently rebuilt
native library differed, then normal reused already-built fixture production input via the
existing three native-task exclusions. Fresh full JVM/lint/build remain separate evidence.
Fixture instrumentation Java recompiled; unchanged test DEX/packaging and lint report rendering
were up-to-date. No instrumentation method ran. The source, raw reports, logs, ZIP/DEX/library,
signature/manifest and input-hash audits are recorded in the evidence directory.

Final independent review approved this scope with no outstanding finding. Documentation audit:
14changed Markdown files/398local links/no missing targets,14rows per context manifest;
source4/protected269 hashes and `git diff --check` pass.

<a id="20261022-ssh-secret-read"></a>
## 2026-10-22 — Original-context typed SSH secret reads and guarded managed copy

Implemented the [secret-read contract](ssh-secret-read-lifecycle.md) in the existing SSH
client task. Credential/private-key/private-key-bundle admission and accepted effects now
capture actual source under short locks, then authenticate/decode/validate independent canonical
loans and prepare bytes outside core. Original epoch/deadline/registration/cancellation and
exact typed resources remain bound through one-shot effects. Selected production admission now
receives its original host/KEX/selection/callback guard before validation; role budgets never renew.

Final trusted bounded managed copy retains original publish fences and orders callback state,
request, publication, session, operation, role and actual public view. No core/native/reentrant
receiver work is allowed there. Worker-owned buffers are wiped outside final fences. Completed
copies cannot be retracted; later adapter/JNI/native validity checks remain required. Provider
owned-clone cleanup on late rejection is source-audited; tests observe rejection and borrowed
buffer wiping, not retained provider clone bytes. No production semantic hook/restore opening.

Supporting fixes align role-bound public snapshot's inner role fence, preserve immediate public
native-lease cancellation after bind, and serialize all five operation capacity-check/insert
sites. Bundle registration uses publication before session. Ordinary mutations/trust CAS and
remaining semantic/restore/connector work are not claimed migrated.

Evidence: `/tmp/ctos-ssh-secret-read-20261022.umpy0yd0`. Previous public-read7source/56protected/
186artifact hashes were rechecked;609 project files saved before this increment. Final8changed
Java/test hashes and264 protected Android/Dart/test inputs match. `increment-source.diff` and
before/first-source/final-source snapshots separate this work from existing uncommitted changes.

| Final local check | Actual result |
| --- | --- |
| Focused JVM | **196 tests/10 reports,0 failures/errors/skips**;46 tasks,11 executed/35 up-to-date,1m41s |
| Fresh full JVM/lint/Release | **444 tests/25 reports,0 failures/errors/skips**;92 tasks,all executed,2m35s |
| Normal lint | 0 Error/Fatal,3 existing warnings: QueryPermissionsNeeded, AndroidGradlePluginVersion, DataExtractionRules |
| Isolated fixture main/test/lint | **114 tasks,36 executed/78 up-to-date,1m2s**, pass;0 Error/Fatal,4 warnings |
| Fixed-input normal packaging | 77 tasks,18 executed/59 up-to-date,19s,pass |
| APK audit | Three v2 signatures/one matching signer; distinct IDs/exact fixture target; normal excludes peer class/library; identical production SSH library in normal/fixture |

New `SshSecretReadValidationTest` has **13 executed test methods** (corrected from an earlier
worker estimate of15), including paused invalidation matrices for all three typed paths,
independent-loan mutation/Throwable cleanup, wrong bindings/BUSY/replay, original budgets,
store/publication seams, exact final receiver fences and six concurrent revocation cases,
public role topology, deterministic shared63→64 capacity race and bundle lock ordering.
Existing public/known_hosts own-CAS, selected callback/Bridge, TOTP, bundle, preview and vault
regressions passed. Actual receiver must be bounded; test-only pauses establish fence ordering.

First focused run:195tests/10reports,1failure,0errors/skips. The new public whole-operation
monitor blocked cancellation after native attach, caught by existing
`cancellationAfterOperationAndSessionBindRevokesWithoutWaitingForFinalClaim`. Fixed by keeping
only short attachRevocation before the inner role fence; original test unchanged and final pass.
Independent source review also found role→publication inversion and the shared capacity race;
the initial capacity fix exposed bundle session→publication inversion, corrected before final run.

| Final APK, not installed | SHA256 |
| --- | --- |
| Normal | `585565fe0be8a05528bffbb38b94c2d8c8b559f07900752368de25a22a1acc50` |
| Fixture | `49b2c017c4ec7a0ef56204504f05444c6829d20094ccd52c9b086a8ed47d1323` |
| Fixture test | `3c431f96129672930e9731dcf85a2d400726a8361be6e360d1ccf730e4e7404c` |

Production library SHA256:`6f13ac814bd0c8af02597f922448729e44fd98a682c530f7d95e2301aa7e331e`.
Signer:`8d36c8be418174cb4cda0b897ff3e32b2da6f84032819726e8e5671872df52ef`.
Package IDs:im.majo.ctos / im.majo.ctos.secretsfixture / im.majo.ctos.secretsfixture.test;
instrumentation targets only the fixture. Initial normal83721611… remains as normal-first.apk;
independent native rebuilds differed in bytes, then normal packaging reused the compiled fixture
production inputs via the existing three native-task exclusions. Fresh full tests/lint/build
remain separate evidence. No native source changed or native runtime executed this round.

Fixture instrumentation Java compilation executed; unchanged test DEX/packaging and lint report
rendering were up-to-date. No instrumentation method ran. No Flutter runtime, Android AtomicFile/
JNI/view lifecycle, device, real vault/account, commit/archive/push/dist or production restore
claim. Original task remains in_progress/parent implementation_complete=false. Next migrate
ordinary mutations/trust CAS, then complete detached semantic obligations and final connector.

Final independent review approved the scoped increment after checking actual source, raw XML,
build/lint logs, ZIP CRC/library/DEX contents, signatures/manifests and hashes. No outstanding
code finding. Documentation audit:15 changed Markdown files/398 local links/no missing targets;
13 rows in each context manifest. `git diff --check` passed.

<a id="20261022-ssh-public-read"></a>
## 2026-10-22 — SSH public read lifecycle and exact native attachment

Continued the original in-progress SSH client task under the frozen
[public-read contract](ssh-public-read-lifecycle.md). Only SSH_FILES admission, synchronous
public closure loans, native lease preparation/final attachment and the role-bound known_hosts
snapshot consumer migrated. Original metadata-only context binds epoch/deadline/guard/immutable
validator registration before work. Actual authenticated source and independent canonical loan
validation/closure run outside core/phase/publication/session. Accepted effects consume once;
wrong owner/purpose/root/connection and valid BUSY do not consume. Private bodies remain excluded.

Synchronous public receivers run outside all locks after a once metadata claim, then recheck
before return. Cancellation cannot retract already observed/copied public bytes; the original
worker wipes borrowed bodies after the receiver finishes, never concurrently from cancel.
This does not grant later role/trust/secret authority or change code/private-key disclosure.
Native views use a separate opaque preparation, lock-out create and final exact attachment;
they cannot be published by a generic receiver. The final raw store read precedes the released
core→original phase/publication/session claim, whose revision check rejects an intervening commit.
Attach operation first, same session epoch second; postcheck/cancel/lock races cannot return an
active unreturned local view. Rejected helper arguments retain caller ownership; production
outer cleanup owns any still-local provisional lease. Existing active views are not replaced.

Existing-role snapshotFor retains its exact role/view and role.deadline. No permanent old-epoch/
registration lease revoker was added. The original operation/context still cancels the exact
published loan after approved own-NEW-CAS advances epoch; no deadline/key renewal or trust
shortcut. Credential/private-key/mutations/trust CAS and remaining sessionManifest callers
stay separate. No production semantic provider/reviewer or SSH restore opening.

Evidence: `/tmp/ctos-ssh-public-read-20261022.mjbo7874`.

### Final local gates

| Check | Actual result |
| --- | --- |
| Final focused JVM | **178 tests/11 reports,0 failures/errors/skips**;46 tasks/10 executed/36 up-to-date/1m1s (`focused-final.log`, `focused-final-jvm/`) |
| Full normal JVM/lint/Release | **431 fresh tests/24 reports,0 failures/errors/skips**;92 tasks/all executed/2m50s (`normal-final.log`, `full-jvm/`) |
| Normal lint | 0 errors/fatal,3 existing warnings: QueryPermissionsNeeded, AndroidGradlePluginVersion, DataExtractionRules (`lint-normal.xml`) |
| Isolated fixture main/test/lint | **114 tasks/35 executed/79 up-to-date/59s**, pass;0 errors/fatal,4 warnings (`fixture-final.log`, `lint-fixture.xml`) |
| Fixed-input normal packaging | 77 tasks/18 executed/59 up-to-date/19s, pass (`normal-fixed-package.log`) |
| Final APK audit | Three v2/one matching signer; distinct normal/fixture/test IDs and exact fixture target; normal excludes peer class/library, production SSH library byte-identical in both packages (`apk-audit.log`, `signature-audit.json`, `artifact-audit.json`) |
| Frozen inputs | Seven source-final and56 protected hashes match; before/final-source and increment-only diff retained |

| Final frozen APK, not installed | SHA256 |
| --- | --- |
| Normal | `db7f7806848d6fce5b17c2fa12bd475377c2faf237d84e594c398ceea55c56b8` |
| Fixture main | `dcffe982815b988d7b05e4d1fbbff88d326dee840138d24f6d5aae4af0304b8e` |
| Fixture test | `3c431f96129672930e9731dcf85a2d400726a8361be6e360d1ccf730e4e7404c` |

Signer SHA256 is `8d36c8be418174cb4cda0b897ff3e32b2da6f84032819726e8e5671872df52ef`.
Final production SSH library SHA256 is `bd8ab19905906d55ea4fa93a86edc4b353fbb997b1f8b1fef32ef8e4c316db02`.
IDs are im.majo.ctos, im.majo.ctos.secretsfixture and im.majo.ctos.secretsfixture.test;
instrumentation targets only im.majo.ctos.secretsfixture. Initial final-source normal403b472a…
is retained as normal-first.apk: independent forced native rebuilds produced different normal/
fixture library bytes (`artifact-initial.json`). Fixed-input normal packaging reused the already
compiled fixture production library and established equality. It does not replace the fresh
normal tests/lint/build or establish native runtime behavior. Fixture lint analysis executed;
unchanged report rendering and final instrumentation Java/DEX/packaging were up-to-date.
The earlier before-capture-fence fixture had compiled instrumentation Java; final source change
kept its API unchanged. Fixture warnings: QueryPermissionsNeeded, two UnsafeDynamicallyLoadedCode,
DataExtractionRules. No instrumentation method executed.

Final independent source/spec/XML/log/ZIP/signature/manifest/hash review passed with no
remaining concrete finding. Final documentation audit:22 Markdown/509 local links/none missing;12rows per context,source7/protected56 and186evidence hashes pass.

### Review fixes and earlier evidence

First focused172/11reports had1failure,0errors/skips (46tasks11executed35up-to-date/1m42s).
The new role invalidation matrix reused the setup's live owner/hop/nonce and correctly triggered
SSH_ROLE_REPLAY before its intended assertions. Use independent binding identities, retaining
all invalidation cases. Second focused178/11reports passed in1m30s; a subsequent178 run after
the first session-fence tightening passed in1m38s. That version also passed full431/24reports,
normal92all-rerun2m42s and fixture114/57s; these are explicitly retained under
before-capture-fence names, not used as final-source build evidence.

Independent review fixed rejected helper parameters revoking caller-owned views, including
replay/foreign/closed/identical existing-view cases. Accept only a genuinely caller-owned local
lease, reserve a single attach attempt and keep nested/replayed attempts from cancelling the
first owner. Cleanup preserves the original Throwable and records suppressed failures. Tests
assert accepted failures already made the lease inactive before caller cleanup; pre-ownership
rejections alone leave cleanup with the caller. Final claim releases core before origin hooks,
with direct beforePublish revision-gap coverage for synchronous and native delivery.

Foreground plus each potentially lazy session getter must share a session fence. Default
context epoch, pre-ownership attachedRevocation and capture metadata/key cloning were tightened;
external role guards remain outside the capture session lock. The last capture fix received a
fresh final178 focused pass and a full build rerun. Existing VaultSession.lock still revokes
synchronously under session; only this increment's owned work/loan/failed-local-lease cleanup
is moved outside locks. No claim of preempting a parser, native call or inherited session revoke.

New SshPublicReadValidationTest has19 methods with paused admission/effect invalidation matrices,
loan mutation/closure/checked/runtime/Error wiping, context/original budgets, BUSY/reentry,
wrong bindings/replay/private exclusion, raw-store/publication seams, all-lock-out receivers,
post-claim cancellation, opaque native attachment ownership/cancel/lock. Cleanup-failure suppression is source-reviewed,
not separately fault-injected.
Two new managed-provider methods check role deadlines/view/close; existing own-CAS regression
now publishes its original loan through the new opaque helper and cancels through origin context.
JVM native attachment uses a fake Revocation; native create/destroy itself was not executed.

```sh
./hako bash -lc 'cd android && ./gradlew :app:testReleaseUnitTest --tests im.majo.ctos.SshPublicReadValidationTest --tests im.majo.ctos.SshManagedKnownHostsProviderTest --tests im.majo.ctos.VaultTest --tests im.majo.ctos.VaultSessionTest --tests im.majo.ctos.VaultOperationTest --tests im.majo.ctos.SshPublicFileLeaseTest --tests im.majo.ctos.TotpReadValidationTest --tests im.majo.ctos.TotpTest --tests im.majo.ctos.SshBundleValidationTest --tests im.majo.ctos.SshPreviewValidationTest --tests im.majo.ctos.SshRestoreTest --offline --console=plain'
./hako bash -lc 'cd android && ./gradlew :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease --offline --rerun-tasks --console=plain'
./hako bash -lc 'cd android && ./gradlew -PctosSecretsUiFixture=true -PctosSshAuthFixture=true :app:assembleSecretsFixtureRelease :app:assembleSecretsFixtureReleaseAndroidTest :app:lintSecretsFixtureRelease --offline --console=plain'
./hako bash -lc 'cd android && ./gradlew :app:assembleRelease -x :app:compileSshProductionObjects -x :app:compileSshConfigEvaluator -x :app:compileSshFiles --offline --console=plain'
```

Actual Android Bridge/AtomicFile/native view lifecycle and concurrent JNI create/destroy were
not run. No native/Flutter runtime rerun, device/real vault/account/clipboard/install/commit/
archive/push/dist. Production semantic/restore, other secret reads/mutations/trust, complete
plans/connector and full client AC remain open. The new7-source/final snapshots and56-protected
hashes supersede previous turn baselines for current verification; prior TOTP7/55/93 evidence
was successfully rehashed at this increment's start.

<a id="20261022-totp-read-validation"></a>
## 2026-10-22 — TOTP typed reads and final code disclosure

Continued the original in-progress SSH client task under the frozen
[TOTP read contract](totp-read-lifecycle.md). TOTP_CODE/TOTP_LIST now capture their original
metadata-only context before Bridge queueing. Read admission/effect authenticate an owned
snapshot, validate mixed content through an independent canonical loan, and generate outside
core/phase/publication/session locks. Only mixed sources bind the original validator registration;
pure TOTP works without a provider and through provider churn. Accepted matching effects consume
once before detached work. Opaque AppSecrets.TotpReadState attached to the exact VaultOperation
binds original context, revision and completed generation window; free scalar reconstruction is
not a read receipt. TOTP_SAVE management previews and other SSH typed/mutation/trust paths retain
their separate contracts. No production semantic provider/reviewer or SSH restore opening added.

Evidence: `/tmp/ctos-totp-read-20261022.CbgSljcf`.

| Check | Actual result |
| --- | --- |
| First focused JVM | 137 tests/8 reports,0 failures/errors/skips;46 tasks/11 executed/35 up-to-date/58s (`focused-first.log`, `focused-first-jvm/`) |
| Final focused JVM | **140 tests/8 reports,0 failures/errors/skips**;46 tasks/8 executed/38 up-to-date/57s (`focused-final.log`, `focused-final-jvm/`) |
| Full normal JVM/lint/Release | **410 fresh tests/23 reports,0 failures/errors/skips**;92 tasks/all executed/2m16s (`normal-final.log`, `full-jvm/`) |
| Normal lint | 0 errors/fatal,3 existing warnings: QueryPermissionsNeeded, AndroidGradlePluginVersion, DataExtractionRules (`lint-normal.xml`) |
| Isolated fixture main/test/lint | **114 tasks/36 executed/78 up-to-date/40s**, pass;0 lint errors/fatal,4 warnings (`fixture-final.log`, `lint-fixture.xml`) |
| Fixed-input normal packaging | 77 tasks/18 executed/59 up-to-date/11s, pass (`normal-fixed-package.log`) |
| Final APK audit | Three v2 APKs/one matching signer; distinct normal/fixture/test IDs and exact fixture target; normal has only production SSH library and no peer class; normal/fixture production SSH library byte-identical (`apk-audit.log`, `artifact-audit.json`) |
| Frozen inputs | Seven changed source/test hashes and55 protected hashes match (`source-final.json`, `protected-before.json`); turn-start/final snapshots and increment-only diff retained |

```sh
./hako bash -lc 'cd android && ./gradlew :app:testReleaseUnitTest --tests im.majo.ctos.TotpReadValidationTest --tests im.majo.ctos.TotpTest --tests im.majo.ctos.VaultTest --tests im.majo.ctos.VaultSessionTest --tests im.majo.ctos.VaultOperationTest --tests im.majo.ctos.SshBundleValidationTest --tests im.majo.ctos.SshPreviewValidationTest --tests im.majo.ctos.SshRestoreTest --offline --console=plain'
./hako bash -lc 'cd android && ./gradlew :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease --offline --rerun-tasks --console=plain'
./hako bash -lc 'cd android && ./gradlew -PctosSecretsUiFixture=true -PctosSshAuthFixture=true :app:assembleSecretsFixtureRelease :app:assembleSecretsFixtureReleaseAndroidTest :app:lintSecretsFixtureRelease --offline --console=plain'
./hako bash -lc 'cd android && ./gradlew :app:assembleRelease -x :app:compileSshProductionObjects -x :app:compileSshConfigEvaluator -x :app:compileSshFiles --offline --console=plain'
```

| Final frozen APK, not installed | SHA256 |
| --- | --- |
| Normal | `41ce54cc97838c8f652cf50ddd2da9ecec30c6056089f6bc3a758b7c0ff41047` |
| Fixture main | `96bb6c86bab742f772244468e9e21aa645fa9a27583eb8c2aede92cdd0267bcd` |
| Fixture test | `3c431f96129672930e9731dcf85a2d400726a8361be6e360d1ccf730e4e7404c` |

Signer SHA256 is `8d36c8be418174cb4cda0b897ff3e32b2da6f84032819726e8e5671872df52ef`.
IDs are im.majo.ctos, im.majo.ctos.secretsfixture and im.majo.ctos.secretsfixture.test;
instrumentation targets only im.majo.ctos.secretsfixture. Final packaged production SSH library
SHA256 is `bad6962642f721e84786ccbdfca43f305fb0dacae5498baa7f5834a33668ca17` in both packages.
Initial normal `7ce2fb73…` is retained as normal-first.apk: independent forced native rebuilds
produced different library bytes (`artifact-initial.json`). Reusing the already compiled fixed
native inputs for normal packaging established byte equality. This packaging-only step does
not replace the fresh full normal build/tests/lint or establish native runtime behavior.
Fixture instrumentation Java compiled; unchanged test DEX/packaging and lint report rendering
were up-to-date. Fixture lint analysis ran; warnings are QueryPermissionsNeeded, two
UnsafeDynamicallyLoadedCode and DataExtractionRules. No instrumentation tests were executed.

### Scoped review, tests and limits

TotpReadValidationTest adds19 test methods, including paused mixed validators across list/code
admission/effect invalidations, pure-provider independence, retained loan/key/ciphertext wiping,
loan mutation/exception/Error, BUSY/reentry, wrong-owner/purpose/resource and once-use replay.
Additional final assertions cover the second raw store-read mismatch, a publication hook that
calls completion and then throws, and metadata changes made by the final time supplier.
Final receipt tests cover reservation, actual-effect invalidation, current 30-second boundaries
and clock changes. Existing TOTP_SAVE, bundle/preview/restore/session/operation regressions pass.
No application test failures occurred in either focused run. Independent source/spec review
clarified the opaque state owner and the temporary comparison-buffer cleanup boundary; owned
work/Verified/loan cleanup is outside locks, but final raw ciphertext comparison buffers are
wiped under core before the phase fence.

Issuance/completion/reservation use phase→publicationLock→session, foreground-first. Actual
list Result.success (which contains codes) or clipboard write retains the existing
publicationLock→session fence after releasing phase. It rechecks the receipt, original
permission/deadline/epoch/revision/registration and a fresh generation window, including metadata
after the clock supplier. copied:true and cleanup run outside locks. This preserves actual-code
disclosure ordering with session.lock; the preview summary's after-claim delivery policy is not
applied to codes. No store/parser/generation/core/phase is held during the actual external effect.

The list transport remains a bounded, transport-only, nonreentrant exception inside pub/session.
An arbitrary Result that reenters core can still form a lock cycle with core→publication;
this increment does not solve that inherited restriction. Clipboard Binder/transport can delay
session.lock or context.cancel until returning; phase.cancel remains free. Validator pauses are
cancellable, but individual parser/crypto/Binder calls are not preempted. Core Throwable cleanup
is tested; inherited Bridge execute catches Exception rather than Error, so immediate UI
completion after a worker Error is not newly guaranteed and may await the original timeout.

Final independent source/spec/XML/log/ZIP/signature/manifest/hash review passed with no
remaining scoped finding. Documentation audit passed:21 Markdown files/478 local links/none
missing, both focused task contexts13 valid rows. Final93-file evidence manifest verified;
git diff --check passed.

Actual Android Bridge routing/lifecycle, Binder/clipboard, AtomicFile and mixed-vault runtime
were not executed. No native or Flutter runtime rerun, no device/real vault/account access,
no install/commit/archive/push/dist. Seven Java/test inputs changed;55 protected native/config/
VaultSession/SSH consumer/preview/clipboard/Dart inputs stayed unchanged. Other SSH typed reads,
ordinary mutations/trust CAS, full detached CONFIG/identity/credential/trust semantics,
production restore, final plans/connector and complete client AC remain open.

<a id="20261022-bundle-validation"></a>
## 2026-10-22 — Outside-core SSH bundle admission and effect

Continued the original in-progress SSH client task under the frozen
[bundle lifecycle contract](ssh-bundle-lifecycle.md). Original native BundleContext is created
before approval/queueing and binds owner, epoch, deadline, guard and immutable validator
registration. A once-used operation inherits that context and exact proposal identity.
Bundle source/candidate validators borrow independent canonical loans outside core; candidate
owns all entry/file arrays. Accepted effect capture consumes before detached work. Final
serialized storage retains wrapper, nonce/material/epoch, approval, claim and uncertainty rules.
No production semantic validator/reviewer registration or SSH restore opening was added.

Evidence: `/tmp/ctos-ssh-bundle-20261022.S0t9zPRZ`.

| Check | Actual result |
| --- | --- |
| First focused JVM | 80 tests/6 reports,3 failures/0 errors/0 skips;46 tasks/57s (`focused-first.log`, `focused-first-jvm/`) |
| Second focused JVM, before final review additions | 80 tests/6 reports,0 failures/errors/skips;46 tasks/1m22s (`focused-second.log`, `focused-second-jvm/`) |
| Final focused JVM | **81 tests/6 reports,0 failures/errors/skips**;46 tasks/11 executed/35 up-to-date/1m8s (`focused-final.log`, `focused-final-jvm/`) |
| Full normal JVM/lint/Release | **391 fresh tests/22 reports,0 failures/errors/skips**;92 tasks/all executed/3m33s (`normal-final.log`, `full-jvm/`) |
| Normal lint | 0 errors/fatal,3 warnings (`lint-normal.xml`) |
| Isolated fixture main/test/lint | **114 tasks/36 executed/78 up-to-date/50s**, pass;0 lint errors/fatal,4 warnings (`fixture-final.log`, `lint-fixture.xml`) |
| Fixed-input normal packaging | 77 tasks/18 executed/59 up-to-date/22s, pass (`normal-fixed-package.log`) |
| Final APK audit | Three v2 APKs/one matching signer; distinct normal/fixture/test IDs and exact fixture target; normal has only production SSH library and no peer class; normal/fixture production SSH library byte-identical (`apk-audit.log`, `artifact-audit.json`) |
| Frozen inputs | Six changed source/test hashes and52 protected source hashes match (`source-final.json`, `protected-before.json`); turn-start snapshots and increment-only source diff retained |

```sh
./hako bash -lc 'cd android && ./gradlew :app:testReleaseUnitTest --tests im.majo.ctos.SshBundleValidationTest --tests im.majo.ctos.SshBundleTest --tests im.majo.ctos.SshPreviewValidationTest --tests im.majo.ctos.SshRestoreTest --tests im.majo.ctos.VaultOperationTest --tests im.majo.ctos.VaultSessionTest --offline --console=plain'
./hako bash -lc 'cd android && ./gradlew :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease --offline --rerun-tasks --console=plain'
./hako bash -lc 'cd android && ./gradlew -PctosSecretsUiFixture=true -PctosSshAuthFixture=true :app:assembleSecretsFixtureRelease :app:assembleSecretsFixtureReleaseAndroidTest :app:lintSecretsFixtureRelease --offline --console=plain'
./hako bash -lc 'cd android && ./gradlew :app:assembleRelease -x :app:compileSshProductionObjects -x :app:compileSshConfigEvaluator -x :app:compileSshFiles --offline --console=plain'
```

| Final frozen APK, not installed | SHA256 |
| --- | --- |
| Normal | `633499a5a4cacfe8885cfc0d3782d0ee0bf04163e5927c7c362f3ea0a859c326` |
| Fixture main | `3d25f1af16d5b63fbb1efc22770b4f3d2d441241bbfa03ed4d4f3aeb7b812018` |
| Fixture test | `3c431f96129672930e9731dcf85a2d400726a8361be6e360d1ccf730e4e7404c` |

Signer SHA256 is `8d36c8be418174cb4cda0b897ff3e32b2da6f84032819726e8e5671872df52ef`.
Final packaged production SSH library SHA256 is
`be90ca98a7f5b745a3304877f6a6d874a42fef2b3d5820f8f7e6f9bd48b87e7e` in both packages.
Initial normal APK `21438a5d…` is retained as `normal-first.apk`: independently forced native
rebuilds yielded different normal/fixture library bytes and the first equality assertion failed.
After reusing the already compiled fixed native inputs for normal packaging, equality and
signatures were rechecked and passed. Native sources and all frozen Java inputs stayed unchanged.
The packaging-only command excludes those three compile tasks; it does not replace the earlier
fresh full normal build/tests/lint or claim a new native runtime pass.

### Review fixes and limits

The first run exposed replay error-priority drift (two failures): the adapter checked stale
context before an already-consumed operation could reject as VAULT_OPERATION. Skip that stale
precheck for consumed operations; accepted capture still enforces exact identity/once-use/BUSY.
The third failure was a recovery fixture counter carried across independent transactions;
reset it while retaining exact source/candidate file-count assertions.

Independent review found an important production-store incompatibility in the first draft:
rechecking ciphertext from inside Store.beforeCommit would recursively call AtomicFile.openRead
during an active write. [AOSP AtomicFile](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/util/AtomicFile.java)
can discard the pending write in this situation. Final snapshot/quick reads now occur BEFORE
Store.commit; its guards check only metadata and approval, including after caller.beforeCommit.
The MemoryStore regression explicitly rejects reads while commit is active. Envelope encoding
also moved before final core serialization. This is source-backed prevention and a JVM boundary
test, **not actual Android AtomicFile persistence execution**.

Post-publication approval/context/proposal recheck runs outside phase/session, with exact
provisional-operation removal/cancellation on Throwable. A regression withdraws approval from
the origin publication callback, verifies no approval under the tiny locks and no orphan capability.
Final review has no remaining concrete scoped source finding. Twelve new lifecycle test methods
cover source/candidate admission/effect paused invalidations, original approval deadline/cancel,
cross-core/reuse, registration/final-read/quick/beforeCommit changes, loan mutation/wiping,
checked/runtime/Error cleanup, effect IO/replay, independent close orders and BUSY/reentry.
Existing16 bundle tests preserve last-SSH deletion, exact approval, unrelated credentials/wrapper
and after-claim cancellation/lock behavior. Arbitrary synchronous providers are not preemptible.

Fixture instrumentation Java recompiled; unchanged test DEX/packaging stayed up-to-date, hence
the unchanged test APK hash. Fixture lint analysis ran while unchanged report rendering was
up-to-date; normal reports are fresh. Compilation is not Android execution. No native/Flutter
source change or runtime, device/real vault/account action, commit/archive/push or dist publication.
Other typed/sessionManifest/trust lifecycles, detached CONFIG/identity/credential/trust semantics,
production restore, final plans/connector and full-client AC remain open. Parent stays in_progress.

Final documentation check covers18 Markdown files/441 local targets with none missing;
implement/check context manifests each contain24 valid rows. Independent checker verified
all frozen source/protected hashes and raw test/lint/build/signature/ZIP evidence. No automatic
task/archive/journal commit scripts were run; original task pointer remains active.

<a id="20261021-preview-validation"></a>
## 2026-10-21 — Outside-core SSH import-preview validation

### Implementation and scope

Continued the original SSH client task and froze the full-manifest proof obligations plus
[preview lifecycle contract](ssh-validation-lifecycle.md). Only preview capture/authentication/
supplied-input validation/review/canonical binding/publication migrated. Typed/bundle/current
sessionManifest callbacks remain on their existing synchronized path; production semantic
validator/reviewer registration and SSH restore admission remain closed.

Preview providers borrow separate authenticated decoded loans; changes are rejected and
copies wiped after callbacks or Error. One in-flight slot survives through cleanup. Immutable
validator registration identity also fences final import storage. Bridge preserves its original
admission deadline and uses private per-request preview-owner UUIDs; external IDs, TOTP/export
and exact previewId/importCancel remain separate. Successful review lifetime starts at final
publication. Phase → session metadata delivery claim is once-only; external result/cleanup is
outside locks. Invalidation after a winning claim may coexist with delivery of that claimed
immutable summary, while subsequent commit must reject.

Evidence: `/tmp/ctos-ssh-preview-validation-20261021.kxvZeWH2`.

| Check | Actual result |
| --- | --- |
| First focused JVM before formal review fixes | 64 tests/4 reports/zero failures, errors, skips; 46 tasks/46s (`focused-first.log`, `focused-first-jvm/`) |
| Final focused JVM after review fixes | **66 tests/4 reports/zero failures, errors, skips**, including21 new tests; 46 tasks/47s (`focused-final.log`, `focused-final-jvm/`) |
| Full normal JVM/lint/Release | **379 fresh tests/21 reports/zero failures, errors, skips**; 92 tasks/all executed/2m18s, pass (`normal-final.log`, `full-jvm/`) |
| Normal lint | Zero errors/three warnings; report retained as `lint-normal.xml` |
| Fixture compilation/lint | **114 tasks/36 executed/78 up-to-date/30s**, pass; zero lint errors/four warnings (`fixture-final.log`, `lint-fixture.xml`) |
| APK signature/isolation | All three v2 signed/one matching signer; normal contains only production SSH library/no peer class; distinct fixture/test IDs and exact fixture target (`apk-audit.log`, `artifact-audit.json`, `test-manifest.log`) |
| Frozen source baseline | Four changed code/test SHA inputs and seven protected native/config/sessionJNI/VaultSession/VaultOperation/Dart hashes match after all gates |

### Commands

```sh
./hako bash -lc 'cd android && ./gradlew :app:testReleaseUnitTest --tests im.majo.ctos.SshPreviewValidationTest --tests im.majo.ctos.SshRestoreTest --tests im.majo.ctos.SshBundleTest --tests im.majo.ctos.VaultSessionTest --offline --console=plain'
./hako bash -lc 'cd android && ./gradlew :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease --offline --rerun-tasks --console=plain'
./hako bash -lc 'cd android && ./gradlew -PctosSecretsUiFixture=true -PctosSshAuthFixture=true :app:assembleSecretsFixtureRelease :app:assembleSecretsFixtureReleaseAndroidTest :app:lintSecretsFixtureRelease --offline --console=plain'
```

| Frozen APK | SHA256 |
| --- | --- |
| Normal, not installed | `7b6d0c1f9dfd7c6faf5e214c59053218f533a2781b5122243c7c424c231d4f75` |
| Fixture main, not installed | `81854c4399caebc40f4ca9a7bb45369ec6ea1f2a4a1588fc883b7eac81ae2cc1` |
| Fixture test, not installed | `3c431f96129672930e9731dcf85a2d400726a8361be6e360d1ccf730e4e7404c` |

Signer SHA256 remains `8d36c8be418174cb4cda0b897ff3e32b2da6f84032819726e8e5671872df52ef`.
The test APK hash is unchanged: instrumentation Java recompiled against the changed main
classes, while unchanged test DEX/packaging stayed up-to-date. The fixture main APK and full
normal Java/JVM inputs were rebuilt. Fixture lint analysis executed; its unchanged report
rendering stayed up-to-date (retained XML timestamp 2026-10-19). Normal lint report is fresh.
Source/fixture compilation is not Android execution.

### Review, failures and limits

Formal scoped review fixed boundGuard.publish dropping the parent's publication fence and
same-named cross-Bridge failure/cancellation reaching an unrelated preview. New coverage includes
three paused callback stages × four invalidation actions, bounded direct cancellation, validator/
reviewer registration replacement, post-last-store-read invalidation, final commit registration,
actual source/target loan bytes, mutation/wiping/Error/slot recovery, locked-start import, old
preview preservation, independent consent lifetime and before/after-claim/once-only UI delivery.
Exact background-expiry publication rejects before lazy session revocation; a real blocking
revocation fixture verifies it cannot seize the request phase mutex. Same-name owner isolation
tests exercise the core; actual Android Bridge routing is source-reviewed/compiled only.

Initial hako sandbox invocation could not access the Docker socket; the scoped hako escalation
was accepted and `./hako true` passed. No automatic-review rejection or reduced check policy.
No failing application test has been observed in the two focused runs. Third-party parser/KDF
callbacks are not preempted; cancellation prevents later stages/publication and owner cleanup
waits for callback return. This is not whole-core responsiveness, complete SSH semantics,
connection/forward/service/UI completion or device acceptance. Native/Flutter were not changed;
old ARM and device outcomes were not rerun. No device, real vault/account, commit/archive/push
or dist publication. Prior `/tmp/ctos-ssh-manifest-inspection-20261019.zkcntg11` is absent;
historical hashes/runtime logs cannot be reverified this session.

Final independent audit rechecked all raw XML/log totals, three APK signatures/target/peer
isolation and lint report freshness/reuse, all four source/seven protected hashes and ten
artifact-manifest entries. No remaining scoped finding. Local documentation check covers17
Markdown files/445 local targets/none missing; implement/check context manifests each21 valid
rows. Task stays in_progress; this is an increment check, not final full-client acceptance.

<a id="20261019-manifest-inspection"></a>
## 2026-10-19 — Supplied-manifest SSH material inspection

### Implementation and scope

Implemented [offline material inspection](ssh-manifest-inspection.md) over actual supplied
source/target/candidate VaultManifest byte copies, using the pinned key/hostfile parsers and
the extracted shared authentication key policy. Java owns copies before native inspection,
wipes on every exit, retains a single original deadline, fences cancellation/publication and
returns an immutable historical report. CSMIv1 is exactly60 bytes; one new material JNI.
No current-vault recursion or fabricated session/lease/role is needed.

Every report has **contentOnly=true/admissionComplete=false**. CONFIG is CONFIG_DEFERRED;
ambiguous exact passphrase identities are explicit. Private/public mathematical association,
credential target semantics, configuration references, certificate validity/principal/CA and
trust review remain pending. No production attachSshValidator registration or SSH restore
admission was added. Parent stays in_progress/implementation_complete=false.

Evidence: `/tmp/ctos-ssh-manifest-inspection-20261019.zkcntg11`.

| Check | Actual result |
| --- | --- |
| Strict material dynamic/static, existing config regressions and production JNI/shared |12 tasks/18s; corrected test matrix rebuild7 tasks/14s, pass (`native-second.log`, `native-third.log`) |
| Focused JVM/lint |56 tasks/25s; fresh31 tests/1 report/zero failures, errors, skips (`java-second.log`, `focused-jvm/`) |
| First passed static ARM material matrix |7780 checks/effects0/safe_logs4/streams34/cleanup pass/exit0 (`material-qemu-third.log`) |
| Final material matrix against frozen final production archive |**7784 checks/effects0/safe_logs4/streams34/cleanup pass/exit0** (`material-qemu-final.log`) |
| Existing ARM catalog/effective evaluator/handle, actually rerun |**71923 / 3736 / 5201 checks**, effects0/exit0 (`catalog-qemu.log`, `eval-qemu.log`, `handle-qemu.log`) |
| Full authentication/PTY ARM regression |**1741410 checks/exit0，synthetic** (`auth-progress-qemu.log`) |
| Normal full native/JVM/lint/Release all-rerun gate |**95 tasks, all executed,1m37s**, pass (`normal-final.log`) |
| Fresh full JVM |**358 tests/20 reports/zero failures, errors, skips** (`full-jvm/`, `jvm-full.json`) |
| Isolated fixture main/test assembly and lint |114 tasks/40 executed/74 up-to-date/27s, pass; exact new method present in DEX (`fixture-final.log`) |
| Fixed-input normal package |77 tasks/19 executed/58 up-to-date/10s, pass (`normal-fixed-artifact-package.log`) |
| Targeted host rebuild after expiry-fixture corrections |5 tasks/1 executed/4 up-to-date/7s, pass (`auth-host-final-third-build.log`); frozen production inputs reused |
| Lint |normal0 errors/3 warnings; fixture0 errors/4 warnings |
| APK/native audit |three v2 APKs/one matching signer; isolated fixture/test IDs and target; normal only production SSHlib/no peer class; fixture peer depends on production library |
| Export isolation |eight config JNI/19 session JNI/one material JNI/archive83/forbidden0 (`native-audit.json`) |

### Commands and frozen artifacts

```sh
./hako bash -lc 'cd android && ./gradlew :app:testSshMaterialInspectorHost :app:testSshMaterialInspectorStaticHost :app:testSshConfigCatalogStaticHost :app:testSshConfigEvaluatorStaticHost :app:testSshConfigHandleStaticHost :app:compileSshFiles --offline --console=plain'
./hako bash -lc 'cd android && ./gradlew :app:testReleaseUnitTest --tests im.majo.ctos.SshManifestInspectorTest :app:lintRelease --offline --console=plain'
timeout 180s qemu-aarch64 /tmp/ctos-ssh-manifest-inspection-20261019.zkcntg11/material-static-final
timeout 180s qemu-aarch64 /tmp/ctos-ssh-manifest-inspection-20261019.zkcntg11/catalog-static-first
timeout 180s qemu-aarch64 /tmp/ctos-ssh-manifest-inspection-20261019.zkcntg11/eval-static-first
timeout 180s qemu-aarch64 /tmp/ctos-ssh-manifest-inspection-20261019.zkcntg11/handle-static-first
./hako bash -lc 'cd android && ./gradlew :app:testSshViewHandleHost :app:testSshNativeClientHost :app:testSshCallbackAdapterHost :app:testReleaseUnitTest :app:compileSshFiles :app:lintRelease :app:assembleRelease --offline --rerun-tasks --console=plain'
./hako bash -lc 'cd android && ./gradlew -PctosSecretsUiFixture=true -PctosSshAuthFixture=true :app:assembleSecretsFixtureRelease :app:assembleSecretsFixtureReleaseAndroidTest :app:lintSecretsFixtureRelease --offline --console=plain'
./hako bash .trellis/tasks/10-08-ssh-terminal-client/research/android-material-inspector-build.sh --output /app/build/app/verification/sshMaterialFinalStatic --static-test
./hako bash -lc 'cd android && ./gradlew :app:assembleRelease -x :app:compileSshProductionObjects -x :app:compileSshConfigEvaluator -x :app:compileSshFiles --offline --console=plain'
timeout 600s qemu-aarch64 /tmp/ctos-ssh-manifest-inspection-20261019.zkcntg11/auth-progress-static
```

Production runners retain random temporary build paths. As in the preceding increment,
freeze archive/shared after full normal and fixture gates; compile independent final static
tests against that archive, then package normal with validated native inputs. The77-task pass
does not replace the95-task full gate. Normal/fixture contain byte-identical stripped SSHlib;
all22 allocated ELF sections match the generated library. Production hashes remain unchanged
through runtime/packaging (`final-production-before-runtime.sha256`). First all-rerun normal
APK is separately saved as `normal-all-rerun-initial.apk`, SHA
`15d4624505a92b7452bf9c063c3b56782d99b2e930ac546a4a6007573c6f10b2`.

| Final artifact | SHA-256 |
| --- | --- |
| normal APK |`a1b24a0ed7fed236b85789d258e2fb7336884a5d81a767ea8642c08ae7402655` |
| fixture APK |`4de3918e3f6f0ecb62994225eb7fb1e79bda9d6dc1e76851fc0e10dfd86cb5fb` |
| fixture test APK |`3c431f96129672930e9731dcf85a2d400726a8361be6e360d1ccf730e4e7404c` |
| production archive |`e4a5f9026a657e5c619e2044cc48961c6e437543fbb08e03998bfd93985eef54` |
| generated production library |`5cddd039ca17233f09d0aae21e7f9709d1364c9733d5773280c50ed1211607bb` |
| identical packaged library |`51cb7ecdc6e82394861ca95844319f1dcf048ceee36188a8371ba90a5d08c0dc` |
| final material static binary, executed |`203b273ad49d7e7f6d62af249e017cd9c68877bab94fa5914aaa58a8f473d5ed` |
| full auth progress-only copy |`31d07fc222b40e0cb1ee732b54ceee70376996a02e9a6559feb842dbf899f093` |
| original final auth binary,180s outer timeout |`660c5587a16fdc66668bf622d578b0387d9e7f9b79eb45ab25f566ac2f47dbf0` |

Signer SHA256 remains `8d36c8be418174cb4cda0b897ff3e32b2da6f84032819726e8e5671872df52ef`.
IDs remain `im.majo.ctos.secretsfixture` / `.test`, instrumentation targets fixture.

### Failures, corrections and provenance

Initial strict builds rejected the pinned-header include order/implicit calloc declaration;
standard headers now precede pinned allocator macros, with no relaxed warnings or manual
prototypes. A test-only const mismatch was corrected. Initial material runtime expected
UNSUPPORTED for a synthetic SK record, but pinned ENABLE_SK is disabled and upstream returns
unknown type/MALFORMED; the test now asserts that exact pinned behavior. Optional final-LF
truncation sampling and positive PEM-writer return handling were also corrected. Failed
logs/binaries remain in evidence, not presented as passing runs.

Extracting shared key policy required rerunning full auth/PTY. Under current QEMU the old
500ms managed-expiry fixture timed out before its intended host callback (evidence=0,
selected=0,kex=0,errno=110); a1000ms service-handshake fixture failed similarly. Exactly four
handshake-prepared expiry setups and managed mode8 now use TEST_EXPIRY_PREPARATION_MS=5000;
only their intended expiry callbacks/direct waits use6000 one-ms polling attempts. Other
short lifetime cases/default1000-attempt helper stay unchanged. Actual original deadlines
still expire, production60s role is unchanged, and **all1037 original CHECK source lines are
identical** (`expiry-fixture-assertions.json`, `auth-test-from-original.diff`). Full strict
static/dynamic host rebuilds passed after this test-fixture timing correction.

The final original auth run hit the outer180s harness timeout, with no assertion output;
that run is not counted as pass. An ignored source copy adds exactly47 fixed stage-name
prints in main and runs with600s outer allowance. Removing those prints restores the final
original test byte-for-byte, independently checked. Functions/order/CHECKs remain identical;
no application timeout, production source or test assertion was changed for the progress run. Static Bionic
compat-minus-reallocarray and TLS alignment adaptation remain test-only.

Source manifest retains75 inputs. Protected-final retains68 unchanged original vault/view/
IO/config/eval/session/KEX/JNI/Dart inputs, excluding only the deliberately corrected old
auth test; the original69-input baseline and precise diff are preserved. Final material
check counts differ7780/7784 because random RSA DER/Base64 lengths alter truncation samples,
not because coverage was dropped. Final manifests/checker outcome are recorded below.

### Coverage and limits

Actual native matrix covers software keys, encrypted correct/wrong/missing phrases, bounded
KDF policy before parsing, full outer envelopes, unencrypted PEM top-level DER framing,
OpenSSH LF versus rejected CRLF and accepted traditional PEM CRLF, public comments versus
multiple physical key records, cert/type mismatch, all known_hosts rows/markers/empty files,
strict hashed-host salt/digest, NUL/line/file/phrase bounds, deadlines and cleanup. Observer
effects0 covers forbidden file/environment/network/command operations; four fixed safe logs
are counted separately, not described as zero logging. Missing/out-of-policy encrypted
phrases prove no parser/KDF calls. No signing or private/public mathematical check occurs.

Traditional encrypted PEM decrypted-DER full consumption is **not certified**; no encrypted
DER-suffix runtime case is claimed. Parsed cert/known_hosts markers do not grant trust,
validity/principal/CA or host-pattern semantic approval. BAD_PASSPHRASE is an upstream error
class and may also indicate corruption. Shared authentication behavior is checked separately.

Java31 tests cover actual candidate inputs, exclusive caller loan preservation, all owned
copies/wiping, exact phrase ambiguity, strict codec, every-file coverage, deadline/cancel/
Error/reentry and immutable report. Single crypto/KDF calls are not preemptible. Actual
allocation/ThreadLocal-setup OOM and JNI live-VM exceptions were not injected; upstream
xalloc system-OOM termination remains. C and fake-binding JVM are distinct from JNI runtime.

`SshManifestInspectorPlatformTest#syntheticSuppliedManifestInspection` was compiled only;
opt-in ctOS fixture guard plus `ctosSshManifestInspectorPlatformProbe=true` and exact method
remain required. No install/run. Dynamic ARM built/not run because old Bionic runtime is
absent; no device files fetched. Flutter unchanged/not rerun. No device/real vault/account,
commit/archive/push/dist actions. Whole-manifest semantics, restore review, final plans,
selection/connector and complete product AC remain open.

Final document check resolves437 local targets across23 Markdown files, none missing.
Both task context manifests validate19 rows. The existing large ssh-native spec exceeds the
32768-byte automatic injection limit; focused source/contract and current spec sections were
explicitly read, so the warning is not treated as a full automatic-injection pass.
Source75/protected68/artifact19/frozen production SHA checks, bash -n, ShellCheck, shfmt and
git diff --check pass. Final evidence review is recorded in task metadata.

<a id="20261019-config-catalog"></a>
## 2026-10-19 — Context-free SSH declaration catalog (local gates pass)

### Implementation and scope

Implemented [the catalog contract](ssh-config-catalog.md) in the same pinned namespaced
readconf lexer. Catalog has no fabricated Request/alias/user/passwd; it collects positive
literal Host declarations and first sources, traverses root-reachable static managed Include
in lexical/depth-first order regardless of branch activation, and reports located deferred
Include sites for any %/$ token. Wildcard/negative/ineligible selectors are omitted with a
flag. Match is flagged, not evaluated. Unknown directives reject, including after IgnoreUnknown.
The shared evaluator/64-slot registry/owner facade now has an explicit catalog mode, bounded
CSCDv1 codec, two added JNI methods and immutable Java entries/sites. Ordinary summary/Options
and catalog getters reject the opposite mode. Caller lease is only borrowed.

Every result is declarationsOnly: bad recognized Port/Match arguments can pass catalog and
must fail real evaluation. No semantic validator, SSH restore admission, private/trust check,
final plan, role, credential, socket, connector or UI added. Production attachSshValidator
remains absent; import/restore rejection stays closed. A current public lease cannot certify
a different source/target/candidate manifest. Parent stays in_progress/implementation_complete=false.

Evidence directory: `/tmp/ctos-ssh-config-catalog-20261019.RiaqcP03`.

| Check | Actual result |
| --- | --- |
| Strict catalog dynamic/static + ordinary evaluator/handle static + JNI/shared compile |11 tasks,7 executed/4 up-to-date,19s, pass (`native-first.log`) |
| First static ARM catalog |**71923 checks/effects0/exit0** (`catalog-qemu-first.log`) |
| Existing static ARM effective evaluator / handle |**3736 / 5201 checks, effects0/exit0** (`eval-qemu-first.log`, `handle-qemu-first.log`) |
| Focused Java/lint |56 tasks,22 executed/34 up-to-date,24s; fresh39 tests/2 reports/zero failures/errors/skips (`java-first.log`, `focused-jvm/`) |
| Normal full all-rerun native/JVM/lint/Release |**95 tasks, all executed,1m30s**, pass (`normal-final.log`) |
| Fresh full JVM |**327 tests/19 reports, zero failures/errors/skips** (`full-jvm/`, `jvm-full.json`) |
| Isolated fixture main/test assembly + lint |114 tasks,40 executed/74 up-to-date,25s, pass; new method present in test DEX (`fixture-final.log`) |
| Final static ARM catalog using frozen final production archive |**71923 checks/effects0/exit0** (`catalog-qemu-final.log`); archive/shared unchanged before/after |
| Fixed-input normal packaging |77 tasks,19 executed/58 up-to-date,9s, pass (`normal-fixed-artifact-package.log`) |
| Lint |normal0 errors/3 warnings, fixture0 errors/4 warnings; copied reports |
| APK/signature/isolation |three v2 APKs/one matching signer each, distinct fixture/test IDs and instrumentation target; normal only production SSHlib/no peer class; fixture peer DT_NEEDED production library |
| Native exports |eight config JNI,19 session JNI,83 archive members, no stock caller/parser exports, test seed or wrap observers (`native-audit.json`) |
| Static/source checks |bash -n, ShellCheck, shfmt, git diff --check,65 source inputs/16 protected inputs/17 artifact SHA manifest checks pass; formal checker no unresolved finding |

### Commands and frozen inputs

```sh
./hako bash -lc 'cd android && ./gradlew :app:testSshConfigCatalogHost :app:testSshConfigCatalogStaticHost :app:testSshConfigEvaluatorStaticHost :app:testSshConfigHandleStaticHost :app:compileSshFiles --offline --console=plain'
./hako bash -lc 'cd android && ./gradlew :app:testReleaseUnitTest --tests im.majo.ctos.SshConfigCatalogTest --tests im.majo.ctos.SshConfigEvaluatorTest :app:lintRelease --offline --console=plain'
timeout 180s qemu-aarch64 /tmp/ctos-ssh-config-catalog-20261019.RiaqcP03/catalog-static-first
timeout 180s qemu-aarch64 /tmp/ctos-ssh-config-catalog-20261019.RiaqcP03/eval-static-first
timeout 180s qemu-aarch64 /tmp/ctos-ssh-config-catalog-20261019.RiaqcP03/handle-static-first
./hako bash -lc 'cd android && ./gradlew :app:testSshViewHandleHost :app:testSshNativeClientHost :app:testSshCallbackAdapterHost :app:testReleaseUnitTest :app:compileSshFiles :app:lintRelease :app:assembleRelease --offline --rerun-tasks --console=plain'
./hako bash -lc 'cd android && ./gradlew -PctosSecretsUiFixture=true -PctosSshAuthFixture=true :app:assembleSecretsFixtureRelease :app:assembleSecretsFixtureReleaseAndroidTest :app:lintSecretsFixtureRelease --offline --console=plain'
./hako bash .trellis/tasks/10-08-ssh-terminal-client/research/android-config-evaluator-build.sh --output /app/build/app/verification/sshConfigCatalogFinalStatic --static-catalog-test
timeout 180s qemu-aarch64 /tmp/ctos-ssh-config-catalog-20261019.RiaqcP03/catalog-static-final
./hako bash -lc 'cd android && ./gradlew :app:assembleRelease -x :app:compileSshProductionObjects -x :app:compileSshConfigEvaluator -x :app:compileSshFiles --offline --console=plain'
```

As in the preceding increment, production runners embed random temporary build paths.
After the full95-task gate and fixture build, freeze the final archive/shared library, build
a separate final static test against that archive, then package normal using those validated
native inputs. The77-task packaging pass does not replace the full gate. Final normal and
fixture contain byte-identical stripped production SSH libraries, whose22 allocated sections
match the final generated library (`allocated-sections-final.json`). The final archive/shared
hashes stayed unchanged through runtime and packaging (`final-production-before-runtime.sha256`).
Initial all-rerun APK is separately preserved, SHA
`2dcf6bc2c0b85221db41916fb30522e09da9fa5b84ebc4a144c675de2a6cba33`.
An initial host audit assertion expected English readelf text; rerunning with LC_ALL=C
confirmed the peer dependency. This was an audit locale mismatch, no product change.

| Final artifact | SHA-256 |
| --- | --- |
| normal APK |`252dc31c20ab97979db8f168ffa855e56f3a56e10096b8babe205b83134e63e4` |
| fixture APK |`00cda3f3faa80510adbcaf18ae73f076193f788a833a56c1bd8a3bcf0022909b` |
| fixture test APK |`b31f941a555a519d6e1216a0dc691b83d590ed8cec69affd8bc82d14eb8ddbf4` |
| generated production archive |`c91456d71dd9f7b79be8532cb60fe6a5f44f328b4f499238cd986d213ef83e50` |
| generated production library |`3065d10304cb55fe795fa9867c104aa045c78f6a37f407ffe5bab8a66b448a37` |
| identical normal/fixture packaged library |`72d4c99f53817d943bc4b6e8e1b2368c62c0d1bc3f5b7f020b2fa2a136aa144f` |
| final static catalog test, executed |`f0e71e3d1c197f1a2afdacb49dc6be50906a115d62eeb07f5526d89aa08320f0` |
| first static catalog test, executed |`88c36d62e1eb48a9f1545cef60912fee3743d3ebf8b6d5040b64ecd297a05f07` |
| ordinary evaluator / handle tests, executed |`ab06fb80f39c87666c20fbd161d221397e3d8c217be3a920911568c967c91987` / `f36e8e39f1e9ce2209c26d8b13bd7c558f84fb19b164a0eba21da4e6b60249a0` |
| dynamic catalog test, built/not run |`552041cb55ff23bdea7313c84085286c45d5e1a56f67e5430ba2c4fc5028cf95` |

Signer SHA-256 remains
`8d36c8be418174cb4cda0b897ff3e32b2da6f84032819726e8e5671872df52ef`.
The independent IDs are `im.majo.ctos.secretsfixture` / `.test`, target fixture.
`source-first.sha256`/`source-final.sha256` retain65 inputs, and `protected-baseline.sha256`
verifies16 original vault/view/IO/session/KEX/JNI and old test sources unchanged. Parser patch
SHA is `fc52b0d65d9bbd6e5b659a16b3a25b0d269bb59ecaa241009c15c7968d26ba07`.

### Coverage and remaining boundaries

Native real-parser matrix covers full Host argv after negative tokens, quoting/comments,
literal []/UTF8/C1, first-source dedup/order, static branches/glob/orphans, dynamic source
dedup, semantic non-certification, missing/type/path/cycle, alias/deferred/depth/line and
1024-open/64MiB read budgets, exact70424-byte CSCD frame/canary, parser EBUSY/wrong-thread,
revocation/expiry/cross-thread cancel, failed-open cleanup and reader retry/FD baseline.
Formal review identified missing catalog-specific final-publication revoke/cancel cases;
worker added deterministic final reader munmap hooks before these builds, both exercised.
Effect observers report0. JVM adds19 catalog tests to unchanged20 ordinary facade tests:
strict CSCD malformed/count/flags/UTF8/duplicates/trailing/immutable lists, mode/default-binding
rejection and owner/cancel/Error/cleanup paths. C and fake-binding JVM tests are separate
evidence, not execution of JNI in an Android VM.

Compiled only: `SshConfigEvaluatorPlatformTest#syntheticManagedConfigCatalog`, opt-in
`ctosSshConfigCatalogPlatformProbe=true` plus exact class/method and existing fixture guard.
It uses a synthetic actual public lease/JNI, static/deferred Include and revoke/borrow checks.
**No install or execution.** Dynamic ARM binary was built/not run because old Bionic runtime
is absent; no device files fetched. Static compat archive minus reallocarray and TLS alignment
adaptation remain test-only. No Android/ROM/external-server or real-vault/account acceptance.
General upstream xalloc OOM and JNI VM exception/concurrent destroy stress limits remain.
Full authentication/PTY ARM1725125 evidence is historical, not rerun here. Flutter unchanged,
not rerun. No commit/archive/push/dist; semantic validator, finalization/selection/connector
and complete product AC remain open.

Final documentation check resolves407 local targets across24 Markdown files, none missing;
both task context manifests validate16 entries. The existing large ssh-native spec still
exceeds automatic injection's32768-byte limit and warns about truncation; workers/reviewer
explicitly loaded the focused source/contract and current top sections. This warning is not
treated as a full-spec automatic injection pass.

<a id="20261019-config-jni"></a>
## 2026-10-19 — Config evaluator JNI / Java owner boundary (local gates pass)

### Implementation and scope

Implemented [the managed contract](ssh-config-managed.md): separate64-slot opaque handle
registry, strict ordinary UTF8 CSEQ/CSES wire, six production JNI entries, Java owner facade
with immutable Request/Summary/Diagnostic and retryable close/destroy,20 JVM tests and an
independent opt-in Android fixture. Full Options remains owned by the native evaluator;
Java metadata retains hasRawFields=true. Caller lease is borrowed and stays usable after
evaluation close. No role, credential, socket, catalog, semantic validator, connector or
Flutter flow added. Original evaluator/parser patch, view/IO and session/KEX/JNI sources
match `unchanged-baseline.sha256` (12 inputs). Parent remains in_progress/implementation_complete=false.

Evidence directory: `/tmp/ctos-ssh-config-jni-20261019.vnQ3FInL`.

| Check | Actual result |
| --- | --- |
| Strict native handle dynamic/static tests + production JNI/shared compile |9 tasks,5 executed/4 up-to-date,17s, pass (`native-first.log`) |
| Focused Java/lint |56 tasks,22 executed/34 up-to-date,25s; fresh20 JVM/1 report/zero failures/errors/skips (`java-first.log`, `focused-jvm/`) |
| First static ARM registry/parser |5201 checks/effects0/exit0 (`handle-qemu-first.log`), intermediate binary hash below |
| Final static ARM using final production archive |**5201 checks/effects0/exit0** (`handle-qemu-final.log`); final archive/shared hashes unchanged before/after |
| Normal full all-rerun test/native/lint/Release |**95 tasks, all executed,1m36s**, pass (`normal-final.log`) |
| Fresh full Gradle JVM |**308 tests/18 reports, zero failures/errors/skips** (`full-jvm/`, `jvm-full.json`) |
| Isolated fixture main/test assembly + lint |**114 tasks,40 executed/74 up-to-date,28s**, pass; new platform fixture included in test DEX (`fixture-final.log`) |
| Fixed-input normal packaging |77 tasks,19 executed/58 up-to-date,9s, pass; reused already compiled native inputs (`normal-fixed-artifact-package.log`) |
| Lint |normal0 errors/3 warnings; fixture0 errors/4 warnings, copied reports |
| APK/signature/isolation |three v2 APKs, one matching signer each; independent fixture/test IDs and target; normal only production SSHlib/no peer class; peer dynamically depends on production library |
| Final native exports |six config JNI +19 original session JNI;83 archive members; no stock caller, test seed or wrap observers; parser exports namespaced |
| Static/source quality |bash -n, ShellCheck, shfmt, source/baseline SHA checks and git diff --check pass; formal checker no concrete finding/no edits |

### Frozen artifact handling and commands

The production runner rebuilds in random temporary directories and embeds those paths
in upstream/parser objects. Initial normal APK and fixture-generated shared library
therefore differed in allocated sections as well as hashes despite unchanged source inputs;
an attempted equality audit caught this. Initial artifacts/logs remain preserved. No code
change was needed: after full strict/JVM/lint gates passed, freeze the final generated
archive/shared library, build a separate static test against that archive without regenerating
production, and package normal with those already compiled native tasks excluded. This
packaging pass does not rerun or replace the full95-task gate. The final normal and fixture
contain **byte-identical stripped production SSH libraries**; all22 ELF allocated sections
match the final generated library (`allocated-sections-final.json`).
`final-production-before-runtime.sha256` verifies the final archive/shared library stayed
unchanged through final ARM execution and packaging. This is source/input correspondence,
not execution of the APK's JNI inside an Android VM.

```sh
./hako bash -lc 'cd android && ./gradlew :app:testSshConfigHandleHost :app:testSshConfigHandleStaticHost :app:compileSshFiles --offline --console=plain'
./hako bash -lc 'cd android && ./gradlew :app:testReleaseUnitTest --tests im.majo.ctos.SshConfigEvaluatorTest :app:lintRelease --offline --console=plain'
./hako bash -lc 'cd android && ./gradlew :app:testSshViewHandleHost :app:testSshNativeClientHost :app:testSshCallbackAdapterHost :app:testReleaseUnitTest :app:compileSshFiles :app:lintRelease :app:assembleRelease --offline --rerun-tasks --console=plain'
./hako bash -lc 'cd android && ./gradlew -PctosSecretsUiFixture=true -PctosSshAuthFixture=true :app:assembleSecretsFixtureRelease :app:assembleSecretsFixtureReleaseAndroidTest :app:lintSecretsFixtureRelease --offline --console=plain'
./hako bash .trellis/tasks/10-08-ssh-terminal-client/research/android-config-evaluator-build.sh --output /app/build/app/verification/sshConfigHandleFinalStatic --static-handle-test
timeout 180s qemu-aarch64 /tmp/ctos-ssh-config-jni-20261019.vnQ3FInL/handle-static-final
./hako bash -lc 'cd android && ./gradlew :app:assembleRelease -x :app:compileSshProductionObjects -x :app:compileSshConfigEvaluator -x :app:compileSshFiles --offline --console=plain'
```

### Actual coverage and remaining boundaries

Native real-parser tests independently construct CSEQ/CSES frames, exact9766/829-byte
boundaries, standard supplementary UTF8 and C1/malformed/NUL/control rejection, Include
target/first-value/raw Options retention, five-int errors and repeated run. Resource tests
exercise positive failed-open cleanup handles, injected borrow-release/close/destroy/unmap
failure and retry,64-slot capacity/stale tokens/INT64 exhaustion, wrong pthread and mocked
PID/UID rejection, deadline/revoke during run/after READY, repeated FD baseline. A fread-paused
parser allows a second thread to cancel before parsing resumes, and rejects owner reentry/
close/destroy while pinned; effect observers report0. Cancel-vs-destroy safety follows the
shared short lock and was source-reviewed; no independent concurrent destroy stress race.

JVM20 tests use fake NativeBinding/ViewBinding to check ordinary UTF8 framing, strict
summary/diagnostic validation, exact rejected line16385, no cache bypass, caller view retention,
positive token after revocation, cancellation during blocked run/summary, cross-object reentry,
IO/Linkage/Error cleanup and staged retry. Native C and Java tests are separate evidence;
JNI immediate exception handling, summary post-array lifetime check and ThreadLocal setup-OOM
ordering were source-reviewed, not injected inside a live VM. Complete Options remains native;
copied Summary is historical metadata and future consumers must recheck lifetime/finalize raw fields.

Compiled platform method:
`SshConfigEvaluatorPlatformTest#syntheticManagedConfigEvaluation`, exact opt-in
`ctosSshConfigEvaluatorPlatformProbe=true` plus exact class/method and existing fixture guard.
Its source covers production lease/JNI Include with Unicode host/user, caller lease usability,
revoke denial and included COMMAND diagnostic. **Not installed or executed.** No current
device authorization or real vault/account access. Dynamic ARM test is built but not run:
old Bionic runtime absent, no device files fetched. Static NDK/Bionic compat/TLS test adaptation
remains test-only; no new Android/ROM/external-server acceptance. General upstream xalloc OOM
limitation remains. Original parser3736/full-session1725125 results are prior native evidence,
not newly rerun here. Flutter unchanged, not rerun. No commit/archive/push/dist.

### Final SHA-256

| Artifact | SHA-256 |
| --- | --- |
| Final normal APK, fixed-input package |`284e956666e81e053dfbea9551aa061962dffe347fb8dd0e9c54ea66db3b2320` |
| Fixture main APK |`faccd363baac6fb5d3c033fef4f655f36063da3897fb960c20e9be8b9c055e6f` |
| Fixture test APK |`b1e3c4ce43792b5975f16d60f56b7e3045269454cd4f268bdfa63bdc04a1b075` |
| Generated shared library |`d42bd93b20215b9e56ccc69942d8df506f10d7aab739d11d11dc40a405aa9f4f` |
| Identical normal/fixture packaged stripped SSH library |`c58f41e9cf37b23df7e76c5303139a36aa35be95e80a66974f1e8da9870cc0ba` |
| Final linked production archive |`c339a52efbe7234e6867eaf0056912541ecee8c8a6732dd7da8dd1b5c5a8c197` |
| Final static handle test, executed5201/effects0 |`1291ceadf1d1cd46cb8ec9bc8f0dc9d39846627257b84adb9ccfab18ffbb5e01` |
| Dynamic handle test, built/not run |`b4fd203696c254fad9618cb83f0049b90e3ddac2725a761707a7da47b72a8a79` |
| Initial all-rerun normal APK, historical |`3f3de1858b529e8e4f0019589d1033ac64e683b396e40eba4b86a2dfeb6d3808` |
| Initial static handle test, executed5201/effects0 |`f03ad972d60e335b2b5d1155e8a03cf4da7dcf4e72bb812c8bae0ead768a5d0e` |

`artifact-audit.json`, final signature log and source/artifact manifests bind final evidence;
`artifact-audit-initial.json` and `normal-all-rerun-initial.apk` preserve earlier artifacts.
Same signer SHA256 as prior gates:
`8d36c8be418174cb4cda0b897ff3e32b2da6f84032819726e8e5671872df52ef`.
Temporary evidence may expire; this document and the contracts/spec retain the durable facts.

<a id="20261019-config-evaluator"></a>
## 2026-10-19 — Managed native readconf evaluator (local gates pass)

### Scope and result

Implemented `ssh_config_eval.[ch]`, scoped parser hooks, the independent pinned readconf
patch/runner, Gradle integration and real-parser tests under the existing SSH task.
Contract: [native evaluator](ssh-config-evaluator.md). Full owned Options is retained;
`has_raw_fields=1` explicitly requires later field finalization. This is not a launch plan,
JNI binding, catalog, AppSecrets semantic validator, connector or completed SSH client.
Original session/KEX/JNI source hashes remained unchanged during this increment.

Evidence: `/tmp/ctos-ssh-config-evaluator-20261019.DNFND9Yx`.

| Check | Actual result |
| --- | --- |
| Final focused strict object/dynamic/static test build + production shared link | 9 tasks, 5 executed/4 up-to-date, 17s, pass (`focused-eighth.log`) |
| Immutable static ARM evaluator, host QEMU/NDK Bionic | **3736 checks, effects=0, exit0** (`qemu-eighth.log`) |
| Existing full native session/auth/PTY regression linked with new config objects | **1725125 checks, exit0**, synthetic AF_UNIX peers (`session-coexist-qemu.log`) |
| Normal all-rerun test/native/lint/Release | **95 tasks, all executed, 1m36s**, pass (`normal-final.log`) |
| Fresh Gradle JVM XML | **288 tests /17 reports, 0 failures/errors/skips** (`fresh-jvm/`) |
| Isolated fixture main/test assembly + lint | **114 tasks,31 executed/83 up-to-date,23s**, pass (`fixture-final.log`) |
| Lint | normal0 errors/3 warnings; fixture0 errors/4 warnings, reports copied |
| Three APKs | v2 verified, one matching signer each; fixture/test IDs and instrumentation target isolated |
| Native packaging | normal only `lib/arm64-v8a/libctos_ssh_files.so`; peer class absent; archive83/forbidden0; session JNI19; seven new native config APIs, upstream parser exports namespaced |
| Shell/source checks | bash -n, ShellCheck, shfmt, source SHA checks and original session/KEX/JNI SHA checks pass |

APK stripped library matches the Gradle `stripReleaseDebugSymbols` output; generated library
and packaged stripped library have distinct hashes below. Both expose the expected config
APIs and19 session JNI exports. Fixture peer DT_NEEDED includes the production shared
library; it does not embed another OpenSSH/crypto. `apk-audit.log` and `artifact-audit.json`
contain signer/target/symbol details. Signer SHA256 remains
`8d36c8be418174cb4cda0b897ff3e32b2da6f84032819726e8e5671872df52ef`.

### Commands actually run

```sh
./hako bash -lc 'cd android && ./gradlew :app:compileSshConfigEvaluator :app:testSshConfigEvaluatorHost :app:testSshConfigEvaluatorStaticHost :app:compileSshFiles --offline --console=plain'
timeout 300s qemu-aarch64 /tmp/ctos-ssh-config-evaluator-20261019.DNFND9Yx/eval-static-eighth
./hako bash -lc 'cd android && ./gradlew :app:testSshViewHandleHost :app:testSshNativeClientHost :app:testSshCallbackAdapterHost :app:testReleaseUnitTest :app:compileSshFiles :app:lintRelease :app:assembleRelease --offline --rerun-tasks --console=plain'
./hako bash -lc 'cd android && ./gradlew -PctosSecretsUiFixture=true -PctosSshAuthFixture=true :app:assembleSecretsFixtureRelease :app:assembleSecretsFixtureReleaseAndroidTest :app:lintSecretsFixtureRelease --offline --console=plain'
timeout 180s qemu-aarch64 /tmp/ctos-ssh-config-evaluator-20261019.DNFND9Yx/session-with-config-static
```

The coexistence build's complete compiler/link command is in `session-coexist-build.log`;
it adds managed file IO and both config objects to the existing session test closure.
Normal/fixture Gradle native host gates compile but cannot run QEMU inside hako;
the separate immutable host executions above supply the actual ARM runtime evidence.
The runner verifies full G1 baseline hashes before and after, uses batch/fuzz0 patching
in a private temporary directory and leaves the original baseline unchanged.

### Covered boundaries, fixes and limitations

Real readconf tests cover Host patterns/negation/first-value, additive identities, Match
first/final, managed nested/glob/conditional Include and lexical order, raw jump/forward/
session preservation, command/trust/provider/effect refusals, input errors and subsequent
valid recovery. Tests include exact line/depth/list/jump/forward limits, independent `%C`
SHA1 vector, escaped `%%`, literal explicit User, numeric/scoped host handling, included
source provenance, decoded jump255/256 and invalid UTF8. Effect observers include host
file/env/NSS/DNS/socket/exec/log paths and report0. Real CSPV create/borrow/evaluate/revoke/
getter-denial/cleanup, wrong pthread/reentry, cancel/deadline, injected read/unmap failures
and repeated FD cleanup run. PID/UID rejection and borrow-release-failure recovery remain
source-reviewed rather than dynamically injected; upstream xalloc system OOM remains
terminating. No claim of recoverable general OOM or complete ssh-G postprocessing.

Failed build/runtime rounds are retained, not counted as passes: initial runner tool/Groovy
closure issues and strict signedness errors; first static execution exposed poison pointer
containers in pinned initialize_options (canonical domains/permitted CNAMEs). Complete
cleanup now initializes both to NULL and frees omitted strings/arrays and duplicate/inactive
forward temporaries. Fifth runtime exposed lost postparse HostName/User source; first-assignment
provenance now also covers algorithm assembly. Sixth3498/seventh3677 were intermediate
passes; final3736 and eighth hash alone describe the final evaluator sources. Formal full
source review followed by focused delta reviews found no remaining concrete issue.

Dynamic ARM test is built but **not run**: old board-derived Bionic runtime is absent and
was not fetched again. Static tests use a test-only compat copy without duplicate
reallocarray and native TLS alignment64, not changes to production compat. Evaluator
static ELF has no interpreter/dynamic section. Session synthetic socketpair execution uses
approved host access; no external endpoint. This does not establish Android/JNI/device,
real-vault/account, UI or external-server acceptance. Flutter unchanged and not rerun
(prior227/analyze evidence remains historical). No APK installed, commit/archive/push/dist.
Parent task remains `in_progress` / `implementation_complete=false`.

### Final SHA-256

| Artifact | SHA-256 |
| --- | --- |
| normal APK | `dd2dd9a0fbe119006e1e4cc8bf77aa01609228a18212a3fb3fc4b2ae2608a8a0` |
| fixture main APK | `74c68d9666e2b1570d91d16da7648ed52b14f5d5ac2d3ed77bdd864222989cf3` |
| fixture test APK (unchanged/up-to-date) | `17c47f1bba31300a1ceb1d08f861de1cbe92316f98540ae355a0c610dd050ab5` |
| generated production SSH library | `379cc0bd30df40eae7b21f5e3a51d290262ff2767c4fa1f600048e7ef68c2bd5` |
| packaged stripped SSH library | `b890c143d6335cc1a9dd4cd5308aaa7b77c9762c25bc75378b3b93491b26a6fb` |
| evaluator static, executed | `9f1fb6ba6ada2353ff6de52f2cc15ef6190784581d7ef20adb5d865a52686ef0` |
| evaluator dynamic, not executed | `ce2589306760df81c961a3d5d24c09564935531170a93237325d142a4dbd23e8` |
| session with config static, executed | `be3c2f74f2683f369767ae0bb5ba23477973cfe228484b0c03015fc04e70027b` |
| pinned readconf patch | `7cc3b4d84d5b6357d31457b1101dc112c8089bed14153c34267996de951a91dc` |

`source-eighth.sha256` binds the seven final code/build inputs; `session-baseline.sha256`
protects original client/KEX/JNI sources. `source-final.sha256` additionally snapshots current
application changes and native support inputs. Full artifact and copied parser input/baseline
manifests remain in the evidence directory. `/tmp` evidence is temporary; durable findings
and exact commands are recorded here and in the contract/spec.

<a id="20261019-managed-host"></a>
## 2026-10-19 — Actual offered host key / exact NEW review (local packaging and synthetic checks pass)

### Resumed local verification

The previous approval-service capacity blocker is resolved. No production source changed
during this continuation. Rebuilt normal and isolated fixture APKs now cover this increment;
the earlier PTY APKs are no longer used as its packaging evidence.

Evidence: `/tmp/ctos-ssh-managed-host-resume-20261019.ruWFiwIc/`.

| Check | Actual result |
| --- | --- |
| Normal complete gate | **94 tasks / 94 executed / 1m30s**, strict native/JNI, three ABI, tests, lint and Release pass |
| Fresh Gradle JVM | **288 tests / 17 reports / 0 failures, errors or skips** |
| Isolated fixture assembly + lint | **113 tasks / 16s**, 21 executed, 92 up-to-date; both APKs rebuilt |
| Lint | normal 0 errors/3 warnings; fixture 0 errors/4 warnings, including existing fixture dynamic-load warnings |
| APK audit | all three APKs v2, one identical signer; fixture/test IDs and instrumentation target isolated |
| Normal SSH packaging | only `lib/arm64-v8a/libctos_ssh_files.so`; 19 session JNI including openManaged; archive83, forbidden callers0 |
| Fixture native linkage | peer DT_NEEDED references production `libctos_ssh_files.so`, libdl and libc |
| ARM QEMU, independent static Bionic test | **87,300 managed checks**, **1,724,636 complete checks**, both exit0 |
| Independent source/spec/fixture review | no concrete defect; no source edits |
| Device / accounts / commit | not performed; original local-only scope retained |

| Immutable artifact | SHA256 |
| --- | --- |
| Normal APK | `84e98c04c984b91540ea7455721243a3975407ad4786bf5639d4f17cad84ec96` |
| Fixture APK | `9009448df3bc5318b0c4ad9e6be5de1d73082b9574e69ae1280fcc83b0862d53` |
| Instrumentation APK | `17c47f1bba31300a1ceb1d08f861de1cbe92316f98540ae355a0c610dd050ab5` |
| Normal packaged SSH library | `c5b62954f5875ba1ae359dc6a5f9f08a02c1b75ad9739ed01736e35b58fd5f36` |
| Rebuilt dynamic native test (not executed) | `2d220209304f444ced5ea5348f01ca306fc6c78447861ba65ae39e7432f93477` |
| Executed static native test | `6520ff350ce3ba639a95f3e19c407b0f15854625c565257ca1770ee9e6ae1248` |

The former `/tmp` evidence and board-derived dynamic Bionic runtime are absent. No device
was contacted to recreate them. The standard dynamic test was rebuilt and preserved but
**not rerun**. The additional QEMU result uses the same project sources, production archive,
OpenSSL and NDK27 Bionic static libraries in a separate test executable. It is not a rerun
of the old dynamic binary or Android/JNI runtime, and its check counts belong only to its hash.

To recreate that static test, reuse the exact `testSshNativeClientHost` compiler/source/link
list in `android/app/build.gradle`, replacing `-fPIE -pie` with
`-static -fno-pie -fno-emulated-tls`. Copy the compat archive to a test-only path and remove
only `reallocarray.o` with NDK `llvm-ar d`: NDK static libc already provides that symbol.
Link that copy, leaving the original archive and production APK unchanged. Add a test-only
C input containing `_Thread_local _Alignas(64) unsigned char ctos_static_test_tls_alignment;`
to satisfy ARM64 Bionic PT_TLS alignment. Verify no INTERP/dynamic section, PT_TLS alignment
64 and PT_LOAD alignment 16384 before running the immutable copied test:

```sh
ulimit -c 0
timeout 120s qemu-aarch64 /tmp/ctos-ssh-managed-host-resume-20261019.ruWFiwIc/native-static-native-tls --managed-host-only
timeout 120s qemu-aarch64 /tmp/ctos-ssh-managed-host-resume-20261019.ruWFiwIc/native-static-native-tls
```

Failed alternative-build logs are retained: redundant `-no-pie` rejected under Werror;
duplicate reallocarray; Bionic TLS alignment8 abort before test entry; an emulated-TLS
marker did not fix that alignment. The final native-TLS build fixes these test-link issues
without relaxed compiler/linker checks. Its sandbox run then failed the native-open
admission assertion at line1026; approved host execution of the identical binary passed
both suites. The earlier escalated run of the underaligned binary also aborted at startup;
that failure was TLS, not socket admission. No failed run is counted as a pass.

`normal-build.log`, `fixture-build.log`, `apk-audit.log`, `artifact-audit.json`, source/artifact
manifests and `qemu-static-*-approved.log` retain this continuation's actual evidence.
Flutter source is unchanged; prior227/analyze remains historical and was not rerun.
New MANAGED/controller Android execution and production readconf/catalog/connector/service
remain open. Parent task stays in_progress / implementation_complete=false; no archive/push/dist.

### Initial implementation record (historical packaging status)

Contract: [launcher prerequisite](ssh-launcher.md). Existing PINNED, selected authentication,
SUCCESS-only PTY, original role deadline and revocation/owner contracts remain. Explicit
MANAGED native/Java/JNI open takes no caller host key; the actual transport verifier alone
classifies and one-shot pins the offered public key after KNOWN or approved exact NEW/CAS.
Pending-host guard cannot approve/sign/complete before binding. Signature and NEWKEYS remain
required. Product readconf evaluator/catalog/connector/service are still absent.

Evidence: `/tmp/ctos-ssh-managed-host-20261019.j484FN3T`.

| Check | Actual result |
| --- | --- |
| Strict focused native rebuild | third build6tasks13s, pass |
| ARM QEMU/Bionic managed-host suite | **87,305 checks / exit0**, `qemu-managed-third.log` |
| ARM QEMU/Bionic complete native suite | **1,724,409 checks / exit0**, `qemu-full-third.log` |
| Focused Gradle Java/JNI/lint | **55tasks23s**, session51 + mailbox8; `managed-focused.log` |
| Final host JDK compile + full JUnit | **288 tests pass /39.199s**, all17 test classes recompiled; `host-java-full.log` |
| Final managed Android fixture source compile | pass, `host-fixture-compile.log`; not APK assembly or device execution |
| Generated production native library | sessionJNI19 including openManaged; archive83, forbidden stock callers0 |
| Scoped formal cross-layer + final delta source review | no concrete defects; no duplicate builds by reviewer |
| Final full Gradle/Release/APK/fixture packaging | **not executed**: automatic approval service capacity failure on three attempts |
| Flutter | unchanged since prior227/analyze pass; not rerun for this Java/native increment |
| Device / accounts / commit | not performed, per retained user scope |

Immutable executed binary `native-third` SHA256:
`00b689663e927d7deda4edb76d4cb0ad7f136e0da19ab9f57c6f8be7e17e95a4`.
Final native header clarification changed comments only; source-native-final.sha256 records
that exact source set. Generated JNI library SHA256:
`4bd3bfcc6f5d8f172c4d80f4142af519b21d8d4091d19c99f851ed859345318a`.
These are not APK hashes; existing APKs remain the previous shell increment artifacts.

Native dynamic coverage adds13 encrypted denial scenarios, unbound/one-shot/expiry/pinned/
cycle guard cases and six PINNED/MANAGED KNOWN/NEW × password/private-key shell roundtrips.
Two initial failures corrected new test expectations only: expired role slots are reclaimed
(close reports ENOENT), and a1s wait helper needs a shorter synthetic expiry budget. Logs and
first/second binaries retained; no authentication bypass or production deadline relaxation.
Allocation failure, opaque proof corruption and arbitrary sink blocking are not injected.

Mailbox JVM cases cover exact metadata/role identity and cloned digests, separate-thread
approval, missing UI/reject/duplicate/foreign/stale tickets, cancel/expiry/view/callback loss,
actual waiting owner wakeup/polling, frozen-clock wall cap, interruption/Error cleanup and
Sink.present reentry. Added two final methods after focused Gradle test; final full host
JUnit recompiles the three affected production Java files and all17 tests using project
cached dependencies and existing compiled unchanged classes. Host JDK21 compiler lacks
ct.sym (`--release17` unavailable), so fallback used `-source17 -target17` with one module-path
warning; it does not replace Android API/lint/packaging validation. Full288 pass includes
session51, mailbox10 and existing controller/auth/vault regressions.

`syntheticManagedHostChain` requires `allowSshAuthFixture=true` and that exact class selector
plus existing isolated-package/UID/fresh-state guards. It exercises real production JNI
managed open, KNOWN/NEW × password/encrypted Ed25519 shell command/output/resize/exit, exact
NEW mailbox on a separate response thread and original managed CAS; reject/cancel asserts
no approval/CAS/epoch change/auth request. The source compiles against cached Android/test
classes; the revised fixture has **not been assembled or run on Android**.

The mailbox waits on an independent monitor <=50ms per slice, <=30s wall time and original
role deadline; live checks run before/during/after approval. Sink hooks must be nonblocking.
JNI retains global sessions_lock during callback, so other session JNI waits too; UI resolve/
cancel must avoid session JNI and the blocked owner queue. No claim of concurrent-hop pump.
Already committed, explicitly approved trust writes are not rolled back on later KEX failure.

Three escalated `./hako` full-build attempts were not executed because automatic approval
review reported: "Selected model is at capacity ... action was not executed ... review
failure, not a determination that the action is unsafe." No bypass attempted. A safe host
compiler/JUnit alternative completed the independent Java checks above. Required remaining
local gates when that service recovers:

```sh
./hako bash -lc 'cd android && ./gradlew :app:testSshViewHandleHost :app:testSshNativeClientHost :app:testSshCallbackAdapterHost :app:testReleaseUnitTest :app:compileSshFiles :app:lintRelease :app:assembleRelease --offline --rerun-tasks --console=plain'
./hako bash -lc 'cd android && ./gradlew -PctosSecretsUiFixture=true -PctosSshAuthFixture=true :app:assembleSecretsFixtureRelease :app:assembleSecretsFixtureReleaseAndroidTest :app:lintSecretsFixtureRelease --offline --console=plain'
```

Copy rebuilt native executable to a new immutable evidence name before running final QEMU;
record its own hash/count rather than reusing native-third's result. Re-audit normal APK
fixture exclusion,19JNI/archive83, independent fixture IDs/target, v2/signer and peer DT_NEEDED.
Do not install any APK or request commit/device permission again under current scope.
Parent task remains in_progress / implementation_complete=false; no archive/push/dist.

<a id="20261019-ssh-shell"></a>
## 2026-10-19 — Session channel / PTY / shell and existing terminal (local pass)

Frozen contract: [session channel](ssh-session-channel.md). Authentication implementation
251b0f3 and handoff b4ed4e2 have identical ctOS trees to current-history b4ec864 and
12a1357; workspace was clean at recovery. Existing task resumed, no new task/archive.

Native opens one session channel only after selected final SUCCESS, sequentially
confirms PTY/shell, applies bounded windows/partial stdin and merged stdout/stderr,
resizes, tracks EOF/u32 exit-status/exit-signal and drains CLOSE before final state.
Java/JNI add six entries (18 session exports total); original step wire and KEX-only
milestone remain. Owner, accepted cycle/session digest, view/role/cancel/original
deadline, one selected credential attempt and cleanup retry remain enforced.

Evidence directory: `/tmp/ctos-ssh-shell-20261019.8nfypcZH`.

| Local check | Actual result |
| --- | --- |
| Native strict cross-build | pass, final focused rebuild14s |
| ARM QEMU/Bionic shell-only | **407,797 checks / exit0**, `qemu-shell-corrected.log` |
| ARM QEMU/Bionic full native suite | **1,652,108 checks / exit0**, `qemu-full.log` |
| Session JVM + normal lint | **43/0/0/0**,55tasks23s, `managed-final-check.log` |
| Isolated fixture main/test build + lint |113tasks25s, `fixture-second-build.log` |
| Scoped formal C/JNI/Java/fixture source review | no confirmed implementation defects; no repeated builds by reviewer |
| Final normal full gate | **94tasks all rerun /1m29s**, native/view/callback35/strictJNI/threeABI/lint/Release pass |
| Final fresh JVM | **270/0/0/0**,16reports; session43 + controller11 included |
| Flutter full / analyze | **227 pass** / no issues; typed lifecycle, SSH arrows and App/Root regressions |
| Final isolated fixture build/lint | **113tasks43s**, includes real JNI shell and controller fixture methods |
| Final rebuilt ARM binary full runtime | **1,651,641 checks / exit0**, `native-final`, `qemu-final.log` |
| Final normal APK | v2/one signer, archive83/sessionJNI18/forbidden stock callers0, fixture class/library absent |
| Final isolated APK pair | v2/same signer/separate package+test target; peer DT_NEEDED production libctos_ssh_files.so, libc/libdl only |

Both runtime commands use the immutable `native-shell-corrected`, SHA256
`7a8a87fdf45c51938d1bc9563c04efddbdb3ecb0e3db0d5f8ccb69f1c20f599a`.
The full --rerun-tasks gate regenerated the pinned PIC archive, so its new final
test binary has SHA256 `bbf14ab476f771218a84b4a6ad161e7094318025f5bd4b024730c760c8df5f7f`.
That exact immutable `native-final` was also fully executed (1,651,641/exit0);
do not substitute the earlier binary/check count for this final artifact.
Reproduce from ctOS after focused Gradle rebuild:

```sh
ulimit -c 0
timeout 120s qemu-aarch64 -L /tmp/ctos-ssh-arm-runtime-20261017.O81ADtu5 -E LD_LIBRARY_PATH=/system/lib64 /tmp/ctos-ssh-shell-20261019.8nfypcZH/native-shell-corrected --shell-only
timeout 120s qemu-aarch64 -L /tmp/ctos-ssh-arm-runtime-20261017.O81ADtu5 -E LD_LIBRARY_PATH=/system/lib64 /tmp/ctos-ssh-shell-20261019.8nfypcZH/native-shell-corrected
```

Sandboxed QEMU failed the same old socket-admission errno assertion in both new and
immutable authentication baseline binaries; approved host execution passed. First
host run then exposed an insufficient test loop:3-byte turns×20,000 could not
deliver65,536 bytes. Increased bounded fixture turns to40,000, without changing
production window/cap; retained failing logs and first binary separately.

Native dynamic shell coverage: password/private-key success, exact command/output,
resize, both stdout/stderr, partial acceptance and zero-window/backpressure, read
window credit, rejection/open-PTY-shell ordering, malformed packets, EOF direction,
u32 exit/signal, buffered close followed by socket shutdown, cancel/view denial and
close retry. Shell-specific deadline/alien environment, duplicate/open-before-drain
and unknown-request replies remain source/shared-guard reviewed rather than new
native runtime cases. JVM explicitly tests all shell entries for owner/reentry and
precise pre/post cancel/expiry/view errors, accepted EOF stickiness and read wiping.

Independent Android fixture now has `syntheticShellChannelChain`: actual production
KEX/auth with synthetic MemoryStore/keys, small8-byte peer maxpacket and16-byte
window, command/UTF8/extended output/resize/exit, NEW/CAS+encrypted key, open/PTY/shell
rejections, pending cancel and public-loan revoke. At this point it is **built, not
device-run**. First final-protocol fixture hashes f21acaa1…/c34e26ac… and v2/one signer
were checked before the final UI/controller rebuild described below.

The final pair adds `syntheticTerminalControllerChain`, running real session and peer
on one serial owner with production controller, normal command/output/resize/exit
and pending cancellation; extra resources close after the session. Both methods are
**locally compiled, not device-run**. Final hashes:

- Normal APK: `3d7e28d31a0db21fd90f954763f8cac7cd0a2d37c685aab0eb2264bdcdbbd6b5`.
- Fixture main: `5866f825098f5b47c3a8428c1b904875740fc6cedd61bb66ab5f10905d55a3f5`.
- Fixture test: `9285e1ca6a1e749b101c9fc41a48c1c8520e3b52b4db14276aa3a4d29aa597e5`.

MainActivity host-only factory and SshTerminalSession now route authenticated channels
into existing write/resize/Stop/output paths. The real product profile/resolver/socket/
host-key discovery launcher is still absent; no fake SSH picker was added. Typed host
state replaces exit-by-output-text, UTF8 decoder resets per generation and SSH arrows
go directly remote. Controller queues<=64KiB input and one<=32KiB acknowledged output;
Stop, Error, queued startup cancellation, exact owner cleanup, late acknowledgement
and destruction/shutdown races were fixed and tested. Formal reviewer rechecked fixes;
the final real-controller instrumentation source was reviewed by main, not run.

No device or real account operation, normal APK install, dist publication, commit,
push or archive has occurred. Native allocator/packet failure injection and listed
protocol gaps, full Android JNI/UI/device acceptance, product launcher, forward/service,
KBI/MFA, rekey/compression and broad compatibility remain open. Parent
implementation_complete remains false.

Final user decision: retain uncommitted workspace and local results only. Device
execution and commit were explicitly declined for this round; no pending approval
is required to retain this completed local increment. No further build was needed
after the final documentation/metadata-only synchronization.

<a id="20261019-android-auth"></a>
## 2026-10-19 — Android/JNI authentication, encrypted private keys and real NEW CAS

**Implemented and actually tested:** production Java/JNI KEX → userauth service →
password or direct-signed private-key authentication. This replaces the earlier
C-only/Android-auth-not-run status for this increment; older dated entries remain
historical. The full SSH product task stays in progress (channels/PTY/forwarding,
launcher/service/UI, KBI/MFA continuation and broader compatibility remain open).
No production vault or real account was used, and the normal APK was not installed.

Java exposes service begin/ready, password begin/result and private-key begin/result,
with explicit stable result tags and12 session JNI exports. KEX complete retains its
old meaning. A static ThreadLocal covers all session native entries, including open,
legacy operations and close/destroy, so same-thread callbacks cannot deadlock the
shared JNI registry through another session. Cancellation remains cross-thread.
Wrong phase/crossover is local and nonterminal; native mutation failures terminalize
and preserve cancel/expiry/view error precedence. Early Java/JNI source review used
the checker; main implemented and reviewed the native crypto, capability and own-CAS
changes, then ran the actual checks below.

Native private-key authentication parses owned expected public metadata before any
secret grant, then parses the exact selected private file in memory, matches public
material, signs the framed raw initial session ID and exact request fields, checks
signature type and locally verifies with expected public before queueing. The peer
independently reconstructs and verifies the request. Shared selected one-attempt
blocks retries, password/key crossover and legacy downgrade. RSA requires an exact
advertised rsa-sha2-512/256 token, prefers512 and never falls back to SHA1. Native
minimum1024 is inherited from pinned upstream (Android fixture RSA uses2048).

Encrypted selections freeze the exact passphrase entry in CSASv2; no prompt selects
an entry. One SSH_PRIVATE_KEY_BUNDLE capability binds path/key identity/entry plus
actual remote target and connection, verifies SSH_KEY_PASSPHRASE identity `key:` +
key identity in the same authenticated vault snapshot, and delivers one <=1MiB
CSKBv1 bundle through existing selected opcode4. Passphrase1..8192/noNUL; raw bundle,
parser wrapper, terminated passphrase, parsed private key, signature and owned buffers
are wiped/freed on all paths. AppSecrets rechecks the final host/KEX/role guard after
its last protected-effect store read, immediately before receiver; new regressions
prove a role revoked during that read never reaches password/key/bundle receivers.

Supported/tested native formats: unencrypted OpenSSH for all five algorithms,
traditional unencrypted PEM for RSA/all ECDSA curves, unencrypted PKCS8 for all five;
Ed25519 traditional PEM is explicitly unsupported by pinned upstream. Encrypted
OpenSSH bcrypt/aes256-ctr is tested for all five, encrypted traditional AES-128-CBC
PEM for RSA/all ECDSA curves. Policy also admits AES-256-CBC legacy PEM, but that
cipher's positive runtime path was not separately generated/tested. OpenSSH bcrypt
salt1..64/rounds1..64 is inspected before KDF; rounds65/zero and unsupported cipher
are rejected without expensive decryption. Encrypted PKCS8 remains ENOTSUP because
its imported KDF parameters are not bounded here. A fake RSA armor prefix before
PKCS8, malformed versions/framing, trailing/NUL/oversized passphrases and wrong
passphrase are rejected. No filesystem, agent, hardware key or interactive prompt.

Real integration found two deadline equalities: AppSecrets view120s vs native role
max60s made real loan admission/KEX impossible. Both client and hostfile issuer now
require view.deadline >= role.deadline; config still equals the exact role deadline,
and both are checked throughout. ARM tests reject an insufficient live view before
ownership transfer, then complete KEX/auth with the shorter role and longer loan.

Real NEW CAS formerly revoked its own loan via unlockIfEpoch. Only an already bound
exact trust operation can retain its identical currently attached active public loan
across its own approved known_hosts CAS. Vault epoch advances, old operations/previews
are invalidated, key and original loan deadline are retained; generic writes, unlock,
lock, expiry and cancellation still revoke. Late lock/revoke or wrong attached loan
cannot restore state or return COMMITTED. Both JVM and actual Android verify these
boundaries, including cancelling the original public-file request after CAS/auth.

Actual checks, with logs/binaries under `/tmp/ctos-ssh-android-auth-20261019.yqKxcIaN`:

| Check | Actual result |
| --- | --- |
| Initial Java/JNI focused JVM + lint |26/0/0/0;55 tasks29s |
| Private-key Java focused + lint |28/0/0/0;55 tasks22s |
| Encrypted/own-CAS focused JVM (fixture flavor) |70/0/0/0 in4 reports;117 tasks43s including fixture build/lint |
| Final normal native/JVM/lint/Release |94 tasks1m5s,37 executed57 up-to-date; **fresh244/0/0/0**,15 reports |
| Final host callback / archive / JNI |35 groups /83 members /12 session exports; forbidden high-level stock callers0 |
| Final actual ARM QEMU/Bionic |**1,244,010 checks, exit0**; immutable `ctos_ssh_native_client_test.final` |
| APK signatures | normal + exact fixture pair: v2, one signer; same fixture certificate |
| Normal APK isolation | only SSH entry lib/arm64-v8a/libctos_ssh_files.so; peer class/library absent |
| Fixture shared implementation | peer DT_NEEDED libctos_ssh_files.so, libc, libdl; no second crypto/archive instance |

Final native binary SHA256 `8e65b06ba5b30f06d71a830dd73b8710ca14dee3500eee57e1137045fed84d53`. Final normal APK SHA256 `89eb642529d9e5a8977b52a5d5672b7883b0f8d4836a9e4b77cbd56e606bbabc`.
`final-audit.json`, `source-final.sha256` (21 files), `runtime-final.sha256` (9 files),
`final-normal-gate.log`, `final-apk-signatures.log` and `private-arm-final.log` retain
exact checks and hashes. Intermediate loop counts vary; earlier snapshots are kept
with their own logs and are not substituted for the final binary.

Device authorization reused the user's explicit current-conversation **independent
synthetic test and cleanup only** grant. Target explicitly `192.168.9.14:5555`,
AIO-3568J/API30/arm64/RUNNING_UNLOCKED. Each exact opt-in method below ran separately
with180s host timeout in independent im.majo.ctos.secretsfixture + .test packages:

| SshPasswordAuthPlatformTest method | Scenarios / result |
| --- | --- |
| syntheticPasswordAuthenticationChain | four results, provider cancel, pending cancel, malformed success; OK(1 test),26.954s |
| syntheticPrivateKeyAuthenticationChain | five algorithms success + rejected/partial; OK(1 test),27.418s |
| syntheticEncryptedPrivateKeyAuthenticationChain | five encrypted algorithms success + wrong passphrase/no duplicate read; OK(1 test),23.781s |
| syntheticNewHostAuthenticationChain | password + five encrypted algorithms; real native candidate/approval/CAS, epoch+1, original-loan cancellation; OK(1 test),23.906s |

The first two methods used fixture main/test hashes
`7cda81d68430c8ac0372c8ea8a8a5d66e7f5512846abbaa31d5d9e9ce244aa10` /
`0a91b2be959dfaa004dbb9a199508281eb7741eacfcc6f6de3dbb00b8da28fa6`.
The last two used
`d45525e18b61c81f7e593cd4e0b19e19ecb31103102038ee47232dc56dfc80a5` /
`30031d2438f8ed13e3af61cc7b7051c0c2ddb16457a8c688d1300167509786cf`.
Peer is same-process AF_UNIX only, no listener; actual AppSecrets encrypted MemoryStore,
native public loan, role, callback and session. Host and client identity keys are
independently generated. Only fixed synthetic credentials enter the fixture.
Final force-stop/uninstall removed both packages/processes. Production device APK
before/after SHA256 remained
`3bc762d9e6463ab9612393b9382923f4a29f613ceec3dbe4d9159aae0078f252`.

Failed intermediate attempts were retained and cleaned: initial view/role deadline
admission and second hostfile equality, unsupported SK lookup errno classification,
synthetic encrypted-key identity containing spaces, and two new test expectations
using a different existing lifecycle code. Production guards were retained/fixed;
only incorrect test expectations were corrected. With fixture properties Gradle's
testReleaseUnitTest alias targets testSecretsFixtureReleaseUnitTest: its fresh XML
is in that flavor directory. The final normal gate ran the normal244 fresh suite,
replacing the earlier normal-flavor failure report.

Reproduction: normal command uses testSshViewHandleHost/testSshNativeClientHost/
testSshCallbackAdapterHost/testReleaseUnitTest/compileSshFiles/lintRelease/assembleRelease
with --offline --console=plain; fixture adds -PctosSecretsUiFixture=true and
-PctosSshAuthFixture=true, assembleSecretsFixtureRelease,
assembleSecretsFixtureReleaseAndroidTest and lintSecretsFixtureRelease. Device
selector is each exact method above with -e allowSshAuthFixture true and matching
-e class, AndroidJUnitRunner under the independent test package. QEMU uses the
existing public API30 Bionic runtime `/tmp/ctos-ssh-arm-runtime-20261017.O81ADtu5`,
LD_LIBRARY_PATH=/system/lib64,90s timeout and core dumps disabled.

Limits: allocation/hash/sign/opaque proof-drift/packet-reset failures and allocator/
upstream memory-wipe internals are source-reviewed rather than fault-injected.
No Android16/Enforcing, real account/vault, encrypted PKCS8, external server matrix,
full rekey/compression, KBI/MFA continuation, channel/PTY/forward or product UI claim.
Normal APK is local only, no dist update/install/commit/archive.

## 2026-10-19 — Explicit native password USERAUTH protocol

Added an explicit C-only password authentication transition and a current-proof
result accessor in ssh_native_client.h/.c. The decoder retains the username offset/
length into owned CSAS wire. Begin requires PASSWORD selection, service READY,
current accepted KEX cycle/raw-session SHA, live owner/environment/role/view/deadline,
no pending output and no rekey. It uses the existing selected one-shot getter,
then rechecks proof/output/rekey before encoding exactly one USERAUTH_REQUEST for
ssh-connection/password/change=false. Passwords are 1..8192 bytes, matching existing
vault bounds; all NUL positions reject without truncation or an authentication packet.
Other bytes remain exact length-prefixed bytes. No none probe, alternate credential,
automatic retry, password change or private-key authentication was added.

Password scratch is wiped/freed on all paths. Pinned successful send resets/wipes
the outgoing plaintext allocation; construction/send failure resets it using
sshpkt_start(SSH2_MSG_IGNORE) without sending that cleanup header, then maps packet
serialization errors to EIO. This reset does not wipe a separate rekey payload queue;
pre-auth no-rekey checks prevent this supported path from entering it. No upstream
patch, archive member, Java/JNI entrypoint or dependency was added.

The bounded step resumes only after explicit begin, preserving old KEX-only,
service-only and standalone selected-handoff stops. Request output must drain
before final replies are accepted. SUCCESS, REJECTED, PARTIAL and CHANGE_REQUIRED
are distinct terminal attempt results; only exact empty SUCCESS with fresh current
proof produces SUCCESS. Banners, method lists, change prompts and disconnect text
are bounded, parsed and discarded, with no display/log/provider authority. Valid
banners during request drain continue output progress. Malformed/early replies,
rekey and unexpected packets terminalize; close remains retryable. The const result
reader clears output on error and performs no I/O/provider/client mutation.

Formal review found no concrete production defect. Main requested one discriminating
positive regression beyond the initial66 connections: an encrypted BANNER arriving
while request output is pending must be accepted in a step that still writes/has
partial output, before the server has received any complete password request. It
then reaches SUCCESS with exactly one request and selected provider call. This
makes both an incorrectly early phase gate and a banner early-return regression
observable. Three-file source manifests and final diff checks pass; no edits were
made by the checker.

Focused command:

```text
./hako bash -lc 'cd android && ./gradlew :app:testSshNativeClientHost :app:testSshCallbackAdapterHost --offline --console=plain'
```

Initial result: **7 tasks / 14s**, 3 executed / 4 up-to-date, strict production/native
cross-build and callback **35 groups** pass. Initial actual ARM execution passes
**780,038 checks / exit0**, binary SHA256
`8b854ee5d7f58dbec6a048bd59d450e63a54e82fa69766633e0383db4408f12d`.
After adding the pending-banner positive, focused passes **7 tasks / 13s**, same
execution counts and callback35. The augmented executable passes **785,653 checks /
exit0**, SHA256 `1e1ffe396ee87b58f3ff08192e4ef1a31f9f1c69000f938b365c16ea5be0143e`.

Actual execution uses the verified host QEMU10.0.13/API30 Bionic runtime, separate
from hako's cross-link runtime=not-run message:

```text
ulimit -c 0
timeout 60s qemu-aarch64 -L /tmp/ctos-ssh-arm-runtime-20261017.O81ADtu5 -E LD_LIBRARY_PATH=/system/lib64 /tmp/ctos-ssh-password-userauth-20261019.obv9zw5H/ctos_ssh_native_client_test.corrected
```

The final suite adds **67 real encrypted synthetic connection scenarios**, with
KNOWN and NEW/CAS KEX/service setup. The peer decrypts and checks exact owned Unicode
username, service/method/change flag, password bytes/end and request count. Tests
cover four results, 1/8192/8193/empty passwords and first/interior/final NUL, mutated
caller CSAS, shared one-shot/standalone-consumed paths, missing/wrong-kind selection,
malformed/trailing/early finals, canonical method lists, remote text/banner caps,
rekey/unexpected/disconnect/EOF, owner/environment recovery, zero-on-error const
status with view recovery, provider-time and active-phase liveness failures, and
retryable owner close. Legacy no-auth stops and callback counters remain intact.

Normal command:

```text
./hako bash -lc 'cd android && ./gradlew :app:testSshViewHandleHost :app:testSshNativeClientHost :app:testSshCallbackAdapterHost :app:testReleaseUnitTest :app:compileSshFiles :app:lintRelease :app:assembleRelease --offline --console=plain'
```

Result: **94 tasks / 19s**, 23 executed / 71 up-to-date. View/native/callback35/
production gates, strict C/JNI link, lint and Release pass. testReleaseUnitTest is
**UP-TO-DATE**: its fifteen retained reports are **221/0/0/0**, not a new JVM run.
There is no Java/JNI source change in this increment. The forced normal production
rebuild changes artifact hashes, so the final executable was copied and actually
rerun with the same QEMU command and `.final` filename: **785,537 checks / exit0**,
SHA256 `db01d8f7aec9d7a8e9cf6f2e8477d8592f4a5041ad41e3d8025a1e4687d06829`. Loop-driven check counts vary; each hash
is paired with its own run. Final executable plus five public runtime files pass
runtime-final.sha256; public runtime hashes match the previously verified runtime.

APK SHA256 `983f2032f89adc6a71f4968c77219e455b6a6a9f3c51b12d101e321f39ad09ad`; v2 signature verifies
with one signer. Archive83, forbidden stock caller definitions0, six session JNI
exports and only SSH APK entry lib/arm64-v8a/libctos_ssh_files.so. APK not installed,
published or copied to dist. Evidence directory:
`/tmp/ctos-ssh-password-userauth-20261019.obv9zw5H/`, containing first/corrected/final
executables and QEMU logs, both focused logs, normal log, source-first/final.sha256,
corrected.sha256, runtime-final.sha256, packaging.json and signature.log.

Coverage limits: ENOMEM/hash faults, opaque current-cycle/session drift, packet
serialization-failure reset, padding64 and upstream allocation wipe are source-
reviewed rather than fault-injected/observed dynamically. Cancellation at the exact
post-parse publication boundary is not injected; active-phase/provider cancellation
and fresh final proof checks are covered separately. Missing selected target reuses
the prior getter ENOSYS test. IGNORE/DEBUG/UNIMPLEMENTED remain unsupported transport
messages and fail EPROTO; this is not full stock transport compatibility. Received
SUCCESS activates upstream delayed compression before semantic parsing, so illegal
success terminalizes; compression/channel interoperability is not established.

No Android/JNI password-auth path, device, real vault/account or external sshd was
used. Private-key parsing/match/signing, selected passphrase/KBI/MFA continuation,
Java/JNI service/auth orchestration, Android full KEX/auth, channels/PTY/forwarding/
service/UI and product AC remain open. Task stays in_progress /
implementation_complete=false, with no commit/archive.

## 2026-10-19 — Receipt-bound selected credential handoff

The native client can now request one selected PASSWORD or PRIVATE_KEY byte handoff
only after explicit service-ready and completed current KEX. It derives kind and
CSAS receipt from its owned selection, retains the digest successfully reported at
KEX completion, and checks live role/view/deadline, unique completion/current cycle
and SHA-256 of the current raw session ID before and after the provider. The provider
writes private bounded scratch; caller output is populated only after post-checks.
Premature or wrong-owner probes do not consume the attempt; any failure after
selected callback invocation consumes it and terminalizes the client. Missing new
target never falls back to legacy callbacks.

JNI calls the unique Java method `onNativeSelectedCredentialBytes(I[BJ[B)[B`.
Java requires an owned native-session claim, exact frozen CSAS receipt and current
host/KEX object, cycle and digest. It clones incoming metadata, shares one selected
attempt flag across selected routes, and permanently rejects the old object route
after the first native claim, including failed opens. Provider/bridge carry an
EffectGuard to the final AppSecrets receiver boundary and repeat it before return.
Unselected legacy password/key callbacks remain repeatable. No USERAUTH packet,
key parsing/signing or Java session secret getter was added; session JNI exports
remain six.

Review findings fixed before the final gate:

- Check each JNI metadata allocation/write immediately for a pending exception
  before the next JNI operation, then clear exceptions before wipe/delete cleanup.
- Keep `inCallback` set throughout finish-time clock checks and recheck cancel/
  reentry before success. Clock-triggered cancellation and reentry regressions pass.
- An escaping non-Linkage Error must wipe an unhanded result and abort the current
  invocation, clearing proofs and allowing owner cleanup, while propagating the
  same Error. New tests scan every post-begin clock boundary, including final finish
  reads; a real synthetic AppSecrets receiver Error also wipes its borrowed array
  and closes the request. Formal review has no remaining finding.

Focused command (run before and after the Error cleanup fix):

```text
./hako bash -lc 'cd android && ./gradlew :app:testSshCallbackAdapterHost :app:testSshNativeClientHost :app:testReleaseUnitTest --tests im.majo.ctos.SshAuthenticationSelectionTest --tests im.majo.ctos.SshNativeSessionTest --tests im.majo.ctos.SshNativeCallbackAdapterTest --tests im.majo.ctos.SshProductionBridgeTest :app:lintRelease --offline --console=plain'
```

Both runs passed **57 tasks / 33s**, 19 executed / 38 up-to-date. First focused JVM
was 64/0/0/0; after adding two Error regressions the final result is **66 tests /
0 failures / 0 errors / 0 skipped** (selection6, adapter12, session17, bridge31).
This increment adds thirteen JVM methods in total. Callback host passes **35 groups**,
including eleven new selected groups; strict C/JNI compilation and lint pass.

Actual ARM execution is separate from hako's cross-link `runtime=not-run` output:

```text
ulimit -c 0
timeout 60s qemu-aarch64 -L /tmp/ctos-ssh-arm-runtime-20261017.O81ADtu5 -E LD_LIBRARY_PATH=/system/lib64 /tmp/ctos-ssh-selected-credential-20261019.tCsurTHc/ctos_ssh_native_client_test.first
```

First QEMU run passes **323,722 checks / exit0**, binary SHA256
`8f258cbc4743baef5525026b7e78aa9bcbdccd1173f79d85fc080e64baf98671`.
The real encrypted synthetic peer completes KNOWN or NEW/CAS KEX and service-ready
before either typed handoff. Tests cover pre-KEX/pre-ready/unselected denial without
consumption, owner/environment recovery, exact receipt/cycle/peer-session digest,
private scratch isolation, one success/duplicate refusal, absent target/no downgrade,
provider errors/bad lengths and cancellation/view/role/deadline changes before output
copy. Failure wipes output, consumes the attempt and preserves close retry.

Normal command:

```text
./hako bash -lc 'cd android && ./gradlew :app:testSshViewHandleHost :app:testSshNativeClientHost :app:testSshCallbackAdapterHost :app:testReleaseUnitTest :app:compileSshFiles :app:lintRelease :app:assembleRelease --offline --console=plain'
```

Result: **94 tasks / 1m6s**, 29 executed / 65 up-to-date. Unfiltered JVM freshly
ran and passed **221 / 0 failures / 0 errors / 0 skipped**, fifteen reports.
View/native/callback35/production, strict C/JNI, lint and Release pass. The forced
production rebuild changes artifacts, so the final executable was copied and
actually rerun with the same QEMU command and `.final` filename: **323,726 checks /
exit0**, SHA256 `292d8c7e01cc2ac7bc125edcaece31e3a7226b00adfffb7e2a12c0fe2c50fd85`.
Loop-driven check counts vary; each executable hash is paired with its own run.
The final executable and five public API30 Bionic files pass runtime-final.sha256;
public runtime hashes match the prior recorded runtime. Host QEMU remains 10.0.13.

APK SHA256 `f329056e8ce108316dbd7d1674f4296b25ba286f0ce283030637cb33cba57a3f`;
v2 signature verifies with one signer. Production archive has 83 members, forbidden
stock caller definitions zero, six session JNI exports and only the SSH APK entry
`lib/arm64-v8a/libctos_ssh_files.so`. APK was not installed, published or copied to dist.
Evidence: `/tmp/ctos-ssh-selected-credential-20261019.tCsurTHc/` contains first/final
executables and QEMU logs, both focused build logs/JVM JSONs, normal build log,
packaging.json, signature.log and runtime-final.sha256.

Coverage limits: native current-cycle/raw-session-ID drift is source-reviewed,
not dynamically injected through an opaque client. Scratch ENOMEM/hash failures
are not fault-injected. JNI exception/array cleanup and adapter-internal result wipe
on Error are source-reviewed; borrowed receiver bytes and owner cleanup are tested.
The modified selected JNI chain has not run on Android. Separate JVM and native ARM
tests do not establish a full Java→JNI→native handoff on hardware. No device, real
vault/account or external endpoint was used. USERAUTH, parsed private/public key
matching, selected passphrase/KBI, Android full KEX/service/auth and product AC remain
open. Task stays in_progress / implementation_complete=false; no commit/archive.

## 2026-10-19 — CSAS native selection metadata and receipt

Frozen managed selection now encodes CSAS v1 and crosses the single session JNI
open declaration as an optional bounded byte array. C validates canonical framing,
typed shape, strict UTF-8 including C1 controls and exact role token/hop/nonce/
connection binding before copying/hash and socket transfer. Native owns the wire
and its SHA-256 receipt; no credential callback, key parsing or authentication packet
is added. Legacy null-selection bindings remain supported, while old fake bindings
reject non-null metadata rather than dropping it.

New explicit receipt reads allow pre-KEX and completed live sessions, enforcing owner,
callback and own-role/view/deadline checks. Java compares its retained expected hash,
returns a copy and wipes native scratch. It does not add a fallible receipt call
after native open has already consumed the socket. New selection admission failures
preserve caller ownership; the preexisting theoretical JNI publish-failure caveat
was not redesigned. The receipt is metadata identity, not authentication authority.

Early review found and fixed two issues before execution: the new Java read guard
needed to reject direct adapter cancellation/closing independently of the legacy
step helper, and encoder scratch allocations needed to be inside their finally
scope. Direct adapter cancellation before/during receipt return now rejects with
SSH_SESSION_CANCELLED and wipes any returned bytes. Legacy step semantics remain.

Focused gate:

```text
./hako bash -lc 'cd android && ./gradlew :app:testSshNativeClientHost :app:testReleaseUnitTest --tests im.majo.ctos.SshAuthenticationSelectionTest --tests im.majo.ctos.SshNativeSessionTest --tests im.majo.ctos.SshNativeCallbackAdapterTest --tests im.majo.ctos.SshProductionBridgeTest :app:lintRelease --offline --console=plain'
```

Result: **56 tasks / 28s** (18 executed / 38 up-to-date), strict native/JNI compile,
lint and **53 JVM tests / 0 failures / 0 errors / 0 skipped**. Classes: selection6,
session17, callback11, bridge19; seven test methods added. Hako cross-link still
reports runtime not-run; actual host ARM execution is separate:

```text
ulimit -c 0
timeout 60s qemu-aarch64 -L /tmp/ctos-ssh-arm-runtime-20261017.O81ADtu5 -E LD_LIBRARY_PATH=/system/lib64 /tmp/ctos-ssh-native-selection-20261019.4F7DmbUU/ctos_ssh_native_client_test.first
```

First ARM run: **253,110 checks / exit0**, binary SHA256
`3d8e505089a4a4d75444148366f2375ccb7c2b7e6d5a702642dea2250eb48161`.
Both typed records, copy independence, every-byte short-record truncation, malformed
framing/caps/overflow-style lengths, UTF-8/C1, mixed shapes/paths and each role-binding
field are exercised. Admission failures and real EMFILE preserve the same resources
for a valid retry. Read errors zero output without client mutation; environment
EPERM/view recovery, cancel/stale/expiry, no I/O/provider effect, cleanup retry and
selected KNOWN real KEX followed by receipt read execute in the suite. Existing
NEW/CAS and service negotiation regressions remain present separately.

New C getter wrong-thread behavior reuses the callback thread gate and source review;
its environment EPERM is dynamically tested. Allocation/hash failure and an accepted
1 MiB blob are not fault-injected; bounds and invalid/max-declared cases are covered.
JVM goldens and C tests do not establish Android execution of the modified JNI
signature/entrypoint. Formal review confirms both fixes and no remaining finding.

Normal gate:

```text
./hako bash -lc 'cd android && ./gradlew :app:testSshViewHandleHost :app:testSshNativeClientHost :app:testSshCallbackAdapterHost :app:testReleaseUnitTest :app:compileSshFiles :app:lintRelease :app:assembleRelease --offline --console=plain'
```

Result: **94 tasks / 1m4s**, 29 executed / 65 up-to-date. View/native/callback24/
production gates, strict native/JNI, Release and lint pass. Full unfiltered JVM
tests freshly ran: **208 / 0 failures / 0 errors / 0 skipped**, 15 XML reports.
The forced production rebuild changes artifact hashes, so the final native test
was copied separately and actually rerun with the same QEMU command and `.final`
filename: **253,155 checks / exit0**. Final binary SHA256
`5c18eac4e893e5d346e0afafec8174c2f98ef517d47486a9117c8bb5a09c6821`.
The final executable and five public API30 runtime files pass runtime-final.sha256;
the emulator remains host QEMU10.0.13/Bionic. Loop-driven assertion counts vary
between successful executions; hashes/logs remain paired with their own run.

APK SHA256 `bda806ca9670f51cdae1160d4615ec59098788888d550268691cca99a2e41ccf`,
v2 signature verifies with one signer. Archive83 / forbidden stock caller0 / six
session JNI exports including authenticationSelectionReceipt / only SSH APK entry
`lib/arm64-v8a/libctos_ssh_files.so`. APK not installed, published or copied to dist.
Temporary evidence: `/tmp/ctos-ssh-native-selection-20261019.4F7DmbUU/`, containing
first/final executables, runtime-final.sha256, qemu-first/final.log, focused/normal
build logs, focused-jvm.json, packaging.json and signature.log.

No device, real vault/account, credential callback, USERAUTH or key-match operation
occurred. Design/spec/task/context/journal were synced. The overall task remains
in_progress / implementation_complete=false, with no commit/archive.

## 2026-10-18 — Managed immutable SSH authentication selection

Added package-private `SshAuthenticationSelection` and connected it to the adapter,
provider and credential bridge. Selection freezes an explicit strict UTF-8 username
and one exact password entry or private-key path/identity/expected public blob for
the exact role object. It is installed once before callbacks or a native-session
claim; a failed session open cannot reopen setup. Selected callbacks require that
same selection object, reject the old kind-only route, and open the fixed request
without calling legacy CredentialRequestSource. The bridge repeats selection,
role/host/KEX/operation checks at the final AppSecrets effect guard, retaining
bounded copy/wipe and one-shot request cleanup.

Focused execution:

```text
./hako bash -lc 'cd android && ./gradlew :app:testReleaseUnitTest --tests im.majo.ctos.SshAuthenticationSelectionTest --tests im.majo.ctos.SshProductionBridgeTest --tests im.majo.ctos.SshNativeCallbackAdapterTest --tests im.majo.ctos.SshNativeSessionTest :app:lintRelease --offline --console=plain'
```

Result: **55 tasks / 27s**, 17 executed / 38 up-to-date; javac and lint pass.
Fresh focused JVM **46 tests / 0 failures / 0 errors / 0 skipped**: selection4,
production bridge19, callback adapter11, native session12. Twelve tests were added.
The matrix includes strict UTF-8 byte limits and surrogate/control rejection,
Unicode preservation, defensive copies, same legacy role digest with different
nonces, equal-fields/distinct selection rejection, one-shot/late/failed-claim
setup, and real in-memory AppSecrets password/private-key releases for KNOWN and
NEW/CAS paths. Two same-kind resources plus a deliberately different legacy source
prove the fixed selected resource wins and legacy source calls remain zero.

The final-guard expiry test advances time on the fifth clock call: the first guard
has already passed, and the second AppSecrets guard observes expiry before receiver
use. Missing this repeated guard would release bytes and fail the test. Legacy
release cannot bypass a selected Request; foreign receipt, wrong type/key identity,
vault epoch, revoked view, cancellation and missing proofs also reject with cleanup.

Independent formal review found no concrete defects and made no edits. No reflection
or mutable production test seam was introduced. Selected-specific mid-return cancel
and reentry have no new dedicated test; they use the existing shared begin/finish/
wipe pipeline and its retained tests. This coverage limit is not an additional
selected-path runtime claim.

Normal gate:

```text
./hako bash -lc 'cd android && ./gradlew :app:testReleaseUnitTest :app:compileSshFiles :app:lintRelease :app:assembleRelease --offline --console=plain'
```

Result: **91 tasks / 57s**, 26 executed / 65 up-to-date. Unfiltered JVM tests were
freshly executed: **201 tests / 0 failures / 0 errors / 0 skipped**, 15 reports.
Production pinned archive and strict native link, Release packaging and lint pass.
APK SHA256 `e6c4d58b28537e5b14a351ea1a35b4d72d64781dd34c05e0dea4d452ef57470b`,
v2 signature verifies, one signer. Archive83 / five session JNI exports /
forbidden stock callers0 / only SSH entry `lib/arm64-v8a/libctos_ssh_files.so`.
The APK was not installed, published or copied to dist.

All 34 scoped C/JNI source/header hashes and the previous ARM test executable are
unchanged. `compileSshProductionObjects` deliberately has `upToDateWhen { false }`,
so archive/library artifact hashes do change on rebuild; an initial audit assertion
that all generated artifacts stay identical was corrected to distinguish source
identity from regenerated outputs. No code/test guard was weakened. New archive
SHA256 `eadf9385f450498f6ad4d2105d6073976b70b974416f4068e462243c55072ab8`,
generated library `dc3e4ecde325b7a1fd4b0b5c1730c18378b54fadb40183874ce1aa96087a1069`.
This Java-only increment did not repeat QEMU; the prior actual ARM result remains
dated to its previous executable and is not relabeled as a new runtime result.

Evidence: `/tmp/ctos-ssh-auth-selection-20261018.y8DyzgNU/`, including focused and
normal build logs, focused-jvm.json, native-before.json, packaging.json and
signature.log. Design/spec/task/context and journal were synced; task remains
in_progress / implementation_complete=false, with no commit/archive.

This is a same-process managed contract, not a serialized native receipt. Expected
public bytes are only an immutable declaration; native parse/equal_public and actual
wire username/current-cycle/service-ready checks remain future work. Selected
KEY_PASSPHRASE and KEYBOARD_INTERACTIVE are rejected; the manifest key-scoped
passphrase versus role-target capability needs a separate contract. No C/JNI/wire,
USERAUTH packet, device, real vault/account or authentication-success claim is part
of this increment. Existing ARM service-negotiation evidence below remains separate.

## 2026-10-18 — Native post-KEX userauth service negotiation

The production C client now exposes explicit `begin_userauth_service` and
`userauth_service_ready` milestones after initial KEX. Existing KEX-only/JNI
callers retain their stop point. The new callback `check_live` reuses owner,
environment, role, cancel, reentry and deadline validation without invoking
host/KEX/credential provider targets. Begin and every active service step compare
the successful completion cycle with the current guard proof. KEX dispatch 20–49
is disabled during this slice; rekey cannot silently reuse the old proof.

Focused build:

```text
./hako bash -lc 'cd android && ./gradlew :app:testSshCallbackAdapterHost :app:testSshNativeClientHost --offline --console=plain'
```

Result: 7 tasks / 14s (3 executed / 4 up-to-date), strict Android native cross-link
pass; callback host gate **24 cases**, including 4 new check-live groups. Hako's
native task still reports runtime not-run because QEMU is outside the container.
The separately executed host ARM test is the actual protocol evidence:

```text
ulimit -c 0
timeout 60s qemu-aarch64 -L /tmp/ctos-ssh-arm-runtime-20261017.O81ADtu5 -E LD_LIBRARY_PATH=/system/lib64 /tmp/ctos-ssh-userauth-service-20261018.uFA94Ngt/ctos_ssh_native_client_test.final
```

First run: **246,974 checks / exit0**. Executable SHA256
`25a7010c06e2296b2adc9ff72f09cec54f433b3cff1d9d195ba8c74fdf93f96e`.
The five public Bionic/linker files match the previously recorded runtime hashes;
the new evidence directory contains an immutable executable copy and current
`runtime.sha256`, `focused-build.log`, `qemu-first.log`.

The real encrypted synthetic OpenSSH peer covers KNOWN and NEW/CAS KEX followed
by exactly one decoded `SERVICE_REQUEST("ssh-userauth")`. Exact, payloadless and
encoded-empty accepts reach ready with zero credential callback calls. Negative
cases cover wrong/truncated/oversized/trailing service payloads, unsolicited
accept before request drain, USERAUTH_SUCCESS, KEXINIT and KEX-method packets.
The suite also exercises pre-KEX/duplicate begin, late duplicate unread after
ready, environment EPERM recovery, cancel/stale role, invalid/expired view,
deadline expiry, bounded accounting and retryable close. Callback host tests
separately cover wrong-thread recovery and reentry without provider effects.

Direct mutation of the guard cycle is not injected: the client/guard ssh pointer
is opaque, and no mutable production test seam was added. The comparison is
source-reviewed; this specific negative branch lacks a direct runtime test.
Formal independent review found no concrete defects and made no source changes.

Normal gate, with every task explicitly rerun:

```text
./hako bash -lc 'cd android && ./gradlew :app:testSshViewHandleHost :app:testSshNativeClientHost :app:testSshCallbackAdapterHost :app:testReleaseUnitTest :app:compileSshFiles :app:lintRelease :app:assembleRelease --offline --rerun-tasks --console=plain'
```

Result: **94 tasks, all executed / 1m24s**. View/callback/native/production gates,
strict native compilation, three-ABI Release, lint and assemble pass. JVM tests
were freshly run: **189 tests / 0 failures / 0 errors / 0 skipped**, 14 XML reports.
The full rebuild produced a different ARM test hash, so the final executable was
copied separately and actually rerun: **246,808 checks / exit0**, SHA256
`3fcaee6aab8647adbdfd4af0cf4ce4f18ab6387d86b72ea7f687bc4b02079560`.
Both runtime results are successful full-suite executions; assertion totals vary
with the bounded synthetic packet-loop progress. The final copy and five runtime
files pass `runtime-final.sha256`; final log is `qemu-final.log`.

APK SHA256 `8cd6da66d836255341b81dd30577b84a85998727865a84f74154f28c8190f4a6`,
v2 signature verifies, one signer. Archive remains 83 members, forbidden stock
caller definitions 0, five unchanged session JNI exports, and the only SSH native
APK entry is `lib/arm64-v8a/libctos_ssh_files.so`. Artifact audit is `packaging.json`,
with `normal-build.log` and `signature.log` in the same temporary evidence directory.
The APK was not installed, published or copied to dist.

No device command, username, USERAUTH_REQUEST, real credential/vault/account,
authenticated milestone or Java/JNI service API is part of this slice. Full
Android service/auth and product acceptance remain open; task stays in_progress /
implementation_complete=false. Design/spec/task context and journal were synced.

## 2026-10-18 — Recovered board: Android JNI fragmented transport / EOF pass

After the user reported recovery, explicit target checks confirmed `192.168.9.14:5555`,
AIO-3568J / Android 11 API30 / arm64-v8a / `RUNNING_UNLOCKED`. Both independent fixture
packages were absent. The prior synthetic-test/cleanup authorization was reused;
the exact method's independent-UID/fresh-state guards, bounded socketpair-only behavior
and cleanup were reviewed again without source changes or outstanding findings.

Fresh fixture build from the current production fixes:

```text
./hako bash -lc 'cd android && ./gradlew -PctosSecretsUiFixture=true :app:assembleSecretsFixtureRelease :app:assembleSecretsFixtureReleaseAndroidTest :app:lintSecretsFixtureRelease --offline --console=plain'
```

Result: `BUILD SUCCESSFUL in 20s`, 112 tasks (29 executed / 83 up-to-date). Production
archive rebuilt, fixture native/Java packaging and lint pass. Both APKs verify v2 with
the same certificate; package and instrumentation target are the independent fixture.
SHA256 main `eb2349b9da92d2ade7e55008db21a4ebf5a95de3c2344b6cd6708fed322c5bc2`;
androidTest `d0634aa524053b14a94aefe9eec0ad12a3749a5140537fd7b9406c1c0503ee87`.

Both packages installed successfully. Exact scoped execution:

```text
timeout 90s adb -s 192.168.9.14:5555 shell am instrument -w -r -e class 'im.majo.ctos.SshNativeSessionPlatformTest#syntheticNativeSessionHandle' -e ctosSshNativeSessionPlatformProbe true im.majo.ctos.secretsfixture.test/androidx.test.runner.AndroidJUnitRunner
```

Result: **OK (1 test), 0.202s**, method `INSTRUMENTATION_STATUS_CODE: 0`, overall
`INSTRUMENTATION_CODE: -1` (normal successful runner result), host exit 0. The method's
two independent production Java/JNI/native sessions actually exercised budget-3
fragmented identification, complete initial KEXINIT output readback/accounting, KNOWN
verification then revoke/close, plus peer EOF with two public `SSH_SESSION_STEP`
errors, view release and idempotent close. All encoded assertions ran on this ARM board.

Both fixture packages were uninstalled (`Success`); `pm path` and `pidof` for each were
empty. Formal APK SHA256 before/after remained
`3bc762d9e6463ab9612393b9382923f4a29f613ceec3dbe4d9159aae0078f252`. No production APK
install, real vault/account access, network endpoint, Root or system-setting change.
Logs: `/tmp/ctos-ssh-jni-recovered-20261018-build.log`, `-packaging.log`,
`-instrumentation.log` (temporary evidence). `git diff --check` passes.

This closes the previously locked-board **JNI fragmented transport/EOF** gate for the
current fixture and API30/arm64 environment. It does not prove Android complete KEX,
encrypted packet/userauth, real managed AppSecrets CAS/vault, PTY/forward/service/UI or
external SSH. The separate prior native QEMU KEX proof remains separate. No source
changed, so the normal production full gate/JVM/QEMU suite was not repeated this turn.
Task remains `in_progress` / `implementation_complete=false`.

## 2026-10-17 — Host QEMU ARM native runtime and production fixes

The board still reports AIO-3568J / `RUNNING_LOCKED`; no fixture was reinstalled on
this continuation. Host `/usr/bin/qemu-aarch64` is available (10.0.13, Debian
1:10.0.13+ds-0+deb13u1), although hako still lacks QEMU. Read-only `adb -s
192.168.9.14:5555 pull` copied only public linker64 and libc/libdl/libm/libz from the
API30 board into `/tmp/ctos-ssh-arm-runtime-20261017.O81ADtu5/`. No user data, account,
vault, Root, lockscreen setting or device application was changed.

Actual execution exposed and fixed four production issues:

1. Unknown role returned registry `ENOENT`, while semantic owner/host/deadline
   rejection could retain incoming errno. Role-policy admission now deterministically
   returns `EACCES`; clock/registry operational errors remain distinct, and failed
   open retains caller resources. Regression covers clean and preexisting `EIO` errno.
2. Pinned `_ssh_read_banner` published a partial remote version without consuming
   input; the next turn parsed `SSH-` as packet length 1397966893. The fixed-hash
   scratch-only banner patch resets the version on incomplete input. Direct client
   and server tests cover one-byte fragments through CR, empty retry and final LF.
3. KEX completion could be published with local NEWKEYS/EXT_INFO still queued, making
   later steps return `EALREADY` before the output drained. The added no-pending-write
   assertion failed before the fix. Completion/callback now waits for `kex->done` and
   zero local output after bounded consumption. KNOWN and NEW/CAS paths exercise both
   2-byte and 64-byte peers, exactly-once proof and actual peer EXT_INFO receipt.
4. FD duplication failure after KEX bind could free ssh without unbinding the guard.
   Failed-open now always unbinds existing ssh before free. A real `EMFILE` test lowers
   only the current test process's soft `RLIMIT_NOFILE` to 1 with fd0 occupied, restores
   it before assertions, and retries the same role/callback/view/socket successfully.
   Soft limit 0 was corrected as a test setup error: minfd 0 then produces `EINVAL`.

The banner patch SHA256 is
`ba61f35303233ebd2bf1c39d167774667766b3461721657f8af0483e51258a18`; patched source is
`ea50ae877474623fcd369fa85ac2c69b5ab1ba231bc25ec2117cefe81e83c4bf`. The builder verifies
the patch, replaces only PIC `ssh_api.o` alongside existing project-patched members,
records source/object evidence and leaves immutable G1 inputs unchanged. Gradle tracks
the patch explicitly. Bash syntax, ShellCheck and `git diff --check` pass; no broad
reformat was applied for preexisting shfmt style differences.

Normal gate command:

```text
./hako bash -lc 'cd android && ./gradlew :app:testSshViewHandleHost :app:testSshNativeClientHost :app:testSshCallbackAdapterHost :app:testReleaseUnitTest :app:compileSshFiles :app:lintRelease :app:assembleRelease --offline --console=plain'
```

Result: `BUILD SUCCESSFUL in 21s`, 94 tasks (35 executed / 59 up-to-date), production /
view / callback gates pass, strict native compilation, Release ABIs, lint and assemble
pass. `testReleaseUnitTest` was **UP-TO-DATE**, retaining reports **189/0/0/0**; JVM tests
were not freshly rerun. A subsequent final test-only cleanup was strict-rebuilt with
`:app:testSshNativeClientHost`: 6 tasks / 12s success, with no later production change.

The actual final ARM run, separately from hako's cross-link-only runtime marker:

```sh
ulimit -c 0
timeout 60s qemu-aarch64 -L /tmp/ctos-ssh-arm-runtime-20261017.O81ADtu5 \
  -E LD_LIBRARY_PATH=/system/lib64 \
  build/app/verification/sshNativeClient/ctos_ssh_native_client_test
```

Result: exit **0**, `SSH_NATIVE_CLIENT checks=65838 admission=pass descriptor=pass
view=pass close=pass open_failure_ownership=pass runtime=synthetic`. The host sandbox
initially denied `getsockopt(SO_TYPE)` with `EPERM`; the same local socketpair test was
run through approved tool execution permissions, without weakening code or assertions.
Final executable SHA256:
`5b030e73a72a67a08d381abd74c754703d46dd60036ebe1f05933110cde96fb8`.

Normal APK SHA256 is `f3327427ce0af90ff7a8a5ea3b5cbba0ac7a7f1c396853c2fd247a45a2328dbd`;
v2 verification passes. Archive remains 83 members, forbidden stock high-level caller
definitions are zero, all five session JNI exports are visible, and APK SSH entries
contain only production `lib/arm64-v8a/libctos_ssh_files.so`. APK was not installed or
published to `dist/`.

Temporary evidence directory above contains initial/fixed build and runtime logs,
pre-fix packet/completion failures, final `build-verified.log`, `run-verified.log`,
`full-gate.log`, packaging output and `runtime.sha256` (binary plus five runtime files).
The initial generated QEMU core was removed; subsequent runs disabled core dumps.
This proves synthetic native ARM execution under QEMU/Bionic, including real KEX and
stubbed native host-evidence/CAS callbacks. It does **not** prove Android JNI runtime,
real managed AppSecrets CAS/vault, userauth, PTY/forward/service/UI or external SSH.
The task remains `in_progress` / `implementation_complete=false`; JNI fixture must
be rebuilt for these production fixes and run after a manual board unlock.

## 2026-10-17 — Android JNI fragmented transport / EOF fixture (local pass, device not-run)

The exact `SshNativeSessionPlatformTest#syntheticNativeSessionHandle` now contains two
independent synthetic production sessions. With step budget 3, the first feeds a
fragmented server identification, checks all eight public step scalars, and requires
actual peer readback to match accumulated `bytesWritten`, including a complete initial
KEXINIT packet (type 20, bounded size/padding/alignment). It retains matching KNOWN
verification, explicit view revoke and close. The second half-closes the peer, requires
public `SSH_SESSION_STEP` twice, then checks view release and idempotent close. These
are encoded assertions, not a new device runtime pass. Java exposes neither
`outputConsumed` nor C errno; no Java `EPIPE` / `EALREADY` claim is made.

Implement/check agents completed source review. The first compile failed because
Android `OsConstants` does not expose `EWOULDBLOCK`; the check agent removed it, retaining
`EAGAIN` / `EINTR`, and hardened nested failure cleanup. The final command was:

```text
./hako bash -lc 'cd android && ./gradlew -PctosSecretsUiFixture=true :app:assembleSecretsFixtureRelease :app:assembleSecretsFixtureReleaseAndroidTest :app:lintSecretsFixtureRelease --offline --console=plain'
```

Result: `BUILD SUCCESSFUL in 20s`, 112 tasks (26 executed / 86 up-to-date), including
fixture Java compile and lint. Lint reports 0 errors and 3 existing warnings
(`QueryPermissionsNeeded`, `AndroidGradlePluginVersion`, `DataExtractionRules`). Both APKs
pass v2 verification with the same certificate; package names and instrumentation target
are the independent fixture only. SHA256:

- main: `78f71309dea6fc39804b9bc591c3db4a32dd291cdab5ddcd50fae02ff1e1bbf4`
- androidTest: `d0634aa524053b14a94aefe9eec0ad12a3749a5140537fd7b9406c1c0503ee87`

The user authorized this turn's independent synthetic install/test/cleanup on
`192.168.9.14:5555`. Identity checks returned AIO-3568J / API30 / arm64-v8a; both
fixture packages were initially absent and installed successfully. The exact command was:

```text
adb -s 192.168.9.14:5555 shell am instrument -w -r -e class 'im.majo.ctos.SshNativeSessionPlatformTest#syntheticNativeSessionHandle' -e ctosSshNativeSessionPlatformProbe true im.majo.ctos.secretsfixture.test/androidx.test.runner.AndroidJUnitRunner
```

No test started or produced an instrumentation result. ActivityManager logged
`SecurityException: Package im.majo.ctos.secretsfixture is not encryption aware!`;
`dumpsys user` confirmed `State: RUNNING_LOCKED`. This is a pre-test environment block,
not a test pass or an assertion failure. The user chose “暂时无法解锁，保留本地结果”.
The hanging shell instrumentation process was identified by its exact command line and
terminated; both owned fixture packages were uninstalled (`Success`). Subsequent
`pm path` / `pidof` checks for both packages were empty. The formal APK SHA256 before
and after was unchanged:
`3bc762d9e6463ab9612393b9382923f4a29f613ceec3dbe4d9159aae0078f252`.

Temporary local evidence is under `/tmp/ctos-ssh-jni-transport-20261017-*`:
initial/fixed build logs, packaging and test manifest dumps, empty instrumentation log,
and filtered startup log. Normal production APK/full gate was not rerun for this
fixture-only change. ARM fragmented transport/EOF runtime, full KEX, userauth, real
CAS/vault, PTY/forward/service/UI and external SSH remain open. Resume the exact gate
after the user manually unlocks the board and authorizes the current device run.

## 2026-10-16 — Android ARM/JNI empty-peer EAGAIN boundary (G3.8 follow-up)

After production `SshNativeSession.open` transfers the socket ownership, the isolated
fixture calls `step()` once with an empty nonblocking peer. It asserts only the stable
read boundary: `bytesRead=0`, `inputAppended=0`,
`readWouldBlock=true`. It intentionally does not assert `bytesWritten=0`,
then runs the existing production KNOWN `verifyHostKey` path and cleanup.

The fixture main/test build and lint completed successfully (112 tasks). APK SHA256:
main `79776aad22be067e057fb2abd2a9af74bc365f890de44b1d3ba689c18d9560d3`;
androidTest `fc71429d9e06d33334cc51cbb9fa64a7b8f672f2bb8752922052aac5ec6dc71e`.
On the explicitly identified `192.168.9.14:5555` board
(AIO-3568J, Android 11/API30, arm64-v8a), the exact
`SshNativeSessionPlatformTest#syntheticNativeSessionHandle` run returned
`OK (1 test)`, 0.11s, instrumentation status 0. Both fixture packages were then
uninstalled and `pm path`/`pidof` checks were empty.

This proves only bounded Android JNI EAGAIN plus matching KNOWN host-proof. ARM packet/KEX,
userauth, real CAS/vault, and full SSH runtime remain open; the host qemu runtime remains
`not-run`.

## 2026-10-16 — Android ARM/JNI KNOWN host-proof boundary (G3.8 follow-up)

The isolated `secretsFixture` test now builds the managed
`ssh/known_hosts` line from the exact 51-byte OpenSSH wire public-key blob used by the
production session. After `SshNativeSession.open`, before view revoke, the test calls
production `SshNativeSession.verifyHostKey`; no test-only callback or network is used.

Local build and source checks:

~~~text
./hako bash -lc 'cd android && ./gradlew -PctosSecretsUiFixture=true :app:assembleSecretsFixtureRelease :app:assembleSecretsFixtureReleaseAndroidTest :app:lintSecretsFixtureRelease --offline --rerun-tasks --console=plain'
~~~

Result: BUILD SUCCESSFUL, 112 tasks. Fixture Java compile/type-check and fixture lint passed;
the production OpenSSH build gate passed. Fixture main SHA256 is
`1e3a10c709fed7d514a90e247494c61345da6a0f71dd142c17157f34ea4e9de6`; test APK SHA256 is
`2b9f80678220d89a7a67e23197bd5f85c1eee7543763bfb895da8d31a801f7e7`.

Target identity was read explicitly from `192.168.9.14:5555`: AIO-3568J,
Android 11/API 30, `arm64-v8a`. The exact run was:

~~~text
adb -s 192.168.9.14:5555 shell am instrument -w -r -e class 'im.majo.ctos.SshNativeSessionPlatformTest#syntheticNativeSessionHandle' -e ctosSshNativeSessionPlatformProbe true im.majo.ctos.secretsfixture.test/androidx.test.runner.AndroidJUnitRunner
~~~

Result: `OK (1 test)`, 0.089s, instrumentation status code 0. The test proves the
matching KNOWN host-proof path through Java → JNI → native hostfile issuer/callback, then keeps
the existing synthetic revoke/close cleanup. Both owned fixture packages were uninstalled;
subsequent `pm path` and `pidof` checks were empty.

This does not prove ARM packet/KEX completion, userauth/typed credentials, real managed CAS/vault,
PTY/forward/service/UI, or external SSH. The host native binary still reports
`runtime=not-run` without qemu-aarch64, and the task remains
`in_progress`/`implementation_complete=false`.

## 2026-10-16 — NEW/CAS full-KEX host-proof boundary (G3.8 follow-up)

The synthetic nonblocking OpenSSH server now drives an empty managed `ssh/known_hosts` through
the production host-key callback during the real packet loop. The regression requires one exact
candidate handoff, one `NEW` evidence callback with approved `CAS_COMMITTED`, and one KEX-complete
callback in candidate → evidence → KEX order. Candidate/evidence route fields, exact replacement
body, base/replacement digests, cycle=1 and the nonzero callback session digest bound to the peer
session ID are checked. The client/server bounded I/O, EAGAIN/short write, completion and later
`EALREADY`/cleanup contracts are also checked.

Focused command:

```text
./hako bash -lc 'cd android && ./gradlew :app:testSshNativeClientHost --rerun-tasks --console=plain'
```

Result: `BUILD SUCCESSFUL`; production gate pass and
`SSH_NATIVE_CLIENT_BUILD gate=pass runtime=not-run reason=qemu-aarch64-unavailable`.

Normal full gate result: 94 tasks `BUILD SUCCESSFUL`; view/native/callback host gates pass, JUnit
**189/0/0/0**, strict `compileSshFiles`, three ABI Release, lint and assemble pass, archive=83,
APK SHA256 `7ccac2fb8f705edd761d34574b9cead4b9e2296864dfe5568d2e9b1105fd849f`, and the APK
contains only production `lib/arm64-v8a/libctos_ssh_files.so` for SSH. `git diff --check` passes.
No ARM runtime was run because qemu-aarch64 is unavailable; no device, vault, credential,
endpoint or external SSH was used. Task remains `in_progress`/`implementation_complete=false`.

## 2026-10-16 — synthetic KEX completion and host-proof boundary (G3.8 follow-up)

The production native caller is now exercised with an in-process nonblocking OpenSSH server on a
socketpair. The server pump transfers 2-byte fragments, records read EAGAIN and short writes, and
the client-side step remains bounded by `step_budget`. The managed object contains the exact
offered public key, so the real verifier produces one `KNOWN` host-evidence callback before one
KEX-complete callback. The test requires `cycle == 1`, a nonzero callback session digest matching
the peer `session_id` SHA-256, client completion, and a later `step()` of `EALREADY`.

Focused verification:

```text
./hako bash -lc 'cd android && ./gradlew :app:testSshNativeClientHost --rerun-tasks --console=plain'
```

Result: `BUILD SUCCESSFUL`; `SSH_PRODUCTION_OPENSSH_BUILD gate=pass fixture=excluded` and
`SSH_NATIVE_CLIENT_BUILD gate=pass runtime=not-run reason=qemu-aarch64-unavailable`.

Normal full verification:

```text
./hako bash -lc 'cd android && ./gradlew :app:testSshViewHandleHost :app:testSshNativeClientHost :app:testSshCallbackAdapterHost :app:testReleaseUnitTest :app:compileSshFiles :app:lintRelease :app:assembleRelease --rerun-tasks --console=plain'
```

Result: 94 tasks `BUILD SUCCESSFUL`; view/native/callback host gates pass, JUnit **189/0/0/0**,
strict `compileSshFiles`, three ABI Release, lint and assemble pass, production archive=83, APK
SHA256 `7195a67fad84185714d00cbb2bbc899fbdf788b61f722282fae8b99ae8ba5f69`, and the APK contains
only production `lib/arm64-v8a/libctos_ssh_files.so` for SSH. `git diff --check` passes. No ARM
runtime was run because qemu-aarch64 is unavailable; no device, vault, credential, endpoint or
external SSH was used. The task remains `in_progress`/`implementation_complete=false`.

## 2026-10-15 — bounded native NEW candidate/CAS precommit boundary (G3.8 follow-up)

The production verifier now has a synthetic no-match `NEW` regression rather than only
direct issuer coverage. An empty managed `ssh/known_hosts` object causes one exact candidate
handoff; the callback fixture captures and compares the generated replacement body byte for
byte, candidate ID/classification/key/fingerprint, base digest and replacement digest. Host
evidence is observed strictly after the candidate, returns approved `CAS_COMMITTED`, and the
production verifier succeeds.

The rejection companion makes host evidence return `EACCES` after the handoff. The verifier
aborts that candidate once, marks the client terminal, leaves no duplicate callback path, and
subsequent `step()` returns `EALREADY`; native close still follows its retryable release order.
This fixes the prior behavior where candidate rollback did not terminalize the client.

Focused verification:

```text
./hako bash -lc 'cd android && ./gradlew :app:testSshNativeClientHost --rerun-tasks --console=plain'
```

Result: `BUILD SUCCESSFUL`; `SSH_PRODUCTION_OPENSSH_BUILD gate=pass fixture=excluded` and
`SSH_NATIVE_CLIENT_BUILD gate=pass runtime=not-run reason=qemu-aarch64-unavailable`.

Normal full verification:

```text
./hako bash -lc 'cd android && ./gradlew :app:testSshViewHandleHost :app:testSshNativeClientHost :app:testSshCallbackAdapterHost :app:testReleaseUnitTest :app:compileSshFiles :app:lintRelease :app:assembleRelease --rerun-tasks --console=plain'
```

Result: 94 tasks `BUILD SUCCESSFUL`; view/native/callback host gates pass, JUnit **189/0/0**,
strict `compileSshFiles`, three ABI Release, lint and assemble pass, production archive=83,
APK SHA256 `7f0bd0f14ec2a5ae9175ee340512acfdeaf0aa76f843834d1a3c87107c97993b`, five session
JNI exports visible, and no forbidden stock caller. `git diff --check` passes. No ARM runtime
was run because qemu-aarch64 is unavailable; no device, vault, credential or external SSH was used.
The task remains `in_progress`/`implementation_complete=false`.

## 2026-10-15 — bounded native SSH step/error, host evidence and policy classifications (G3.8 follow-up)

本轮修正 `ssh_native_client_step` 的错误传播：managed view borrow 到期时保留
`ETIMEDOUT`，revoke/invalid view 保留 `EACCES` 或底层精确 errno，并在错误后将 client
置为 terminal，后续 step 返回 `EALREADY`。新增 host-only synthetic socketpair 回归：
空 nonblocking peer 返回成功且 `read_would_block=1`、读入/append 均为 0；peer 半关闭返回
`EPIPE` 且后续 step 为 `EALREADY`；短 BOOTTIME deadline 返回 `ETIMEDOUT`，cleanup 仍可完成。
同一回归还把 synthetic server banner 按 `step_budget=3` 分片送入，使 production
`ssh_packet_next` 生成客户端 identification/KEX 输出；每步 read/write 均受预算限制，
`bytes_written == output_consumed`，`partial_write` 被实际观察，并从 peer 读回客户端 banner
与后续 KEX 字节。这是 host-only cross-link 回归，不是 ARM 行为执行。
其中 managed-view expiry、invalid view 与 client deadline 使用独立 fixture 分支，避免用
client deadline 分支冒充 view-borrow `ETIMEDOUT`/`EACCES` errno 保留证据。

同一 production caller regression 还覆盖 bounded `KNOWN` host evidence：fixture 将生成的
Ed25519 public key 通过上游 formatter 放入 synthetic managed `ssh/known_hosts` object，
调用 `ctos_ssh_native_client_verify_host_key` 后由 callback fixture 回填完整、逐字段可匹配的
`KNOWN` result（approved、`CAS_NOT_REQUIRED`、role/identity、key/fingerprint 和两组 digest），
精确 key 成功且 host evidence 只调用一次；另一把生成的 public key 在 pinned fingerprint
guard 处返回 `EACCES`，callback 次数不增加。这个 host-proof slice 仍是 strict AArch64
cross-link 编译证据，不是 ARM runtime、全分类 host policy 或完整 SSH 验收。

新增的 `CHANGED` 回归保持 offered key 的 pinned fingerprint 正确，但把另一把生成 key 写入
managed `ssh/known_hosts`。测试先直接调用 issuer，确认返回
`CTOS_SSH_CALLBACK_HOST_CHANGED`、key/fingerprint 与 base/replacement digest metadata 一致、
没有 candidate handoff；随后 production verifier 对同一 offered key 返回 `EACCES`，host
evidence 次数仍为零。它因此区分了 host-file classification rejection 与 pinned fingerprint
guard，仍没有 ARM 行为运行证据。

新增的 `REVOKED` 回归用 matching offered/pinned key 的 `@revoked` managed entry。测试先
直接调用 issuer，确认返回 `CTOS_SSH_CALLBACK_HOST_REVOKED`、key/fingerprint 与
base/replacement digest metadata 一致、没有 candidate handoff；随后 production verifier
在 host evidence 之前返回 `EACCES`，host evidence 次数仍为零。它只使用 synthetic 内存
known_hosts，不访问真实路径，也没有 ARM 行为运行证据。

本轮继续补齐两个明确的 fail-closed policy 分支。`CERTIFICATE` 使用生成并签名的有效
OpenSSH host certificate；issuer 直接返回 `CTOS_SSH_CALLBACK_HOST_CERTIFICATE`，检查
bounded key/fingerprint 与相等的 base/replacement digest、无 candidate handoff，production
verifier 随后在 host evidence 前返回 `EACCES`。`INVALID` 不伪造上游不存在的 `HostStatus`：
issuer 在 `load_hostkeys_file()` 前用 pinned `hostkeys_foreach_file(...,
HKF_WANT_PARSE_KEY, ...)` 预检同一内存 body，遇到 `HKF_STATUS_INVALID` 才返回项目自有
`CTOS_SSH_CALLBACK_HOST_INVALID`；测试使用截断记录加合法记录，断言相同 metadata/no
handoff，production verifier 返回 `EACCES` 且 evidence 为零。合法但无匹配的 body 仍保留
上游 `NEW` 语义。

focused 验证命令：

```text
./hako bash -lc 'cd android && ./gradlew :app:testSshNativeClientHost --rerun-tasks --console=plain'
```

结果：`BUILD SUCCESSFUL`；`SSH_PRODUCTION_OPENSSH_BUILD gate=pass fixture=excluded`，
`SSH_NATIVE_CLIENT_BUILD gate=pass runtime=not-run reason=qemu-aarch64-unavailable`。
这是严格 C11/Werror 的 AArch64 cross-link，host binary 未执行；所有新增回归案例均已编码，
但没有 ARM 行为运行证据。

随后重跑 normal full gate：

```text
./hako bash -lc 'cd android && ./gradlew :app:testSshViewHandleHost :app:testSshNativeClientHost :app:testSshCallbackAdapterHost :app:testReleaseUnitTest :app:compileSshFiles :app:lintRelease :app:assembleRelease --rerun-tasks --console=plain'
```

结果：view handle `gate=pass borrow=pass revoke=pass stale=pass`，production
`gate=pass fixture=excluded`，callback host=20，JUnit **189/0/0/0**；strict
`compileSshFiles`、arm64-v8a/armeabi-v7a/x86_64 Release、lint、assemble 均成功，production
archive=83。新 APK SHA256 为
`b471e55f9bdd54830f6613643deec22a9f4d493408ce24ebdae9a03e17e6a912`；APK 内 SSH 相关 native
库只有生产 `lib/arm64-v8a/libctos_ssh_files.so`，五个 session JNI exports 可见，forbidden stock
caller=0。没有设备、真实 vault、凭据或外部 SSH；task 继续
`in_progress`/`implementation_complete=false`。

## 2026-10-15 — native-owned session handle and Android JNI runtime gate (G3.7)

本轮把 G3.6 的 fail-closed JNI placeholder 替换为受管 native boundary。新增
`ssh_view_handle.[ch]`：positive opaque token 只索引 native registry；`borrow` 是 native
持有的生命周期凭证，`destroy` revoke 后禁止新 borrow，已有 borrow 在 release 前延迟
底层 view 销毁。`ssh_native_client` 在成功 open 后持有 parsed host key、managed-view
borrow 和 OpenSSH state；Java array/string 只经 bounded clone，native 端在完成后清零。
JNI session registry 保存 owner pthread，`step` 只返回八个 bounded scalar，close/destroy
失败保留可重试 slot。

host/normal gate 命令（hako 固定容器）包含：

```text
./hako bash -lc 'cd android && ./gradlew :app:testSshViewHandleHost :app:testSshNativeClientHost :app:testSshCallbackAdapterHost :app:testReleaseUnitTest :app:compileSshFiles :app:lintRelease :app:assembleRelease --rerun-tasks --console=plain'
```

结果：`SSH_VIEW_HANDLE_HOST gate=pass borrow=pass revoke=pass stale=pass`；
`SSH_PRODUCTION_OPENSSH_BUILD gate=pass fixture=excluded`；
`SSH_NATIVE_CLIENT_BUILD gate=pass runtime=not-run reason=qemu-aarch64-unavailable`；
callback host gate=20；JUnit **189/0/0/0**。strict `compileSshFiles`、arm64-v8a/
armeabi-v7a/x86_64 Release、lint 和 assemble 均成功；production archive=83；APK SHA256
`dd9625677e7f874f19ec85f4f0e3f10b31ab8acd4ab71565d145ece4403f582a`。ZIP 只有生产
`lib/arm64-v8a/libctos_ssh_files.so`；五个 JNI exports 为
`open/step/verifyHostKey/close/destroy`，forbidden stock caller=0。

独立 fixture 构建使用 `-PctosSecretsUiFixture=true`，main/test APK 的 assemble 与 lint
成功。对当前授权 `192.168.9.14:5555`（Android 11/API30/arm64-v8a）只安装这两个独立
包，精确执行：

```text
im.majo.ctos.SshNativeSessionPlatformTest#syntheticNativeSessionHandle
im.majo.ctos.SshFileViewPlatformTest#syntheticProtectedFileViews
```

两项均 `OK (1 test)`、instrumentation status code=0；前者覆盖 synthetic socketpair 的
production open、view active/revoke、native close 和 cleanup，后者复测现有 public-file
view matrix。fixture app/test UID 为 10193/10194，测试后精确卸载；无 fixture package
path、fixture/session process 残留，正式 `im.majo.ctos` APK path 为
`/data/app/~~nGo0N0WrfrAequbiq8FZTw==/im.majo.ctos-K1Mv_CfV9-JIwLY7giviTQ==/base.apk`，
安装后读回 hash 为 `3bc762d9e6463ab9612393b9382923f4a29f613ceec3dbe4d9159aae0078f252`。

这不是完整 Android SSH pass：hako 没有 `qemu-aarch64`，没有真实 vault/credential/外部
SSH；当前设备方法只证明 open/revoke/close 和 view matrix，不证明 userauth、host proof、
partial I/O/EAGAIN/EOF/deadline、PTY/forward/service/UI。task 仍为
`in_progress`/`implementation_complete=false`。

## 2026-10-15 — Java session-owner seam with fail-closed production JNI (G3.6)

本轮新增 `SshNativeSession`、`SshNativeSessionTest`、`ssh_session_jni.c`，并把 JNI
source 纳入 normal `compileSshFiles`。Java owner 只允许创建 session 的 event-loop
thread 调用 `open/step/verifyHostKey/close/destroy`；`cancel` 可跨线程设置取消并由
owner thread 把取消传入下一次 native step。`SshNativeCallbackAdapter.NativeSessionClaim`
把 callback binding 的 opening/owned 状态与 session 绑定，保证一个 callback 同时只有一个
session；failed open 清掉 claim 并保留 caller socket、callback 和 public view。close
先完成 native C close，再做 callback claim bookkeeping、role release、view release、
native destroy，失败阶段可重试。Java host-key/fingerprint 输入为私有 bounded copy，
调用后清零。

生产 JNI entrypoints 已出现在 normal APK 的 native symbol 表，但当前故意 fail-closed：
`ssh_session_jni.c` 的 `open/step/verifyHostKey/close/destroy` 都抛 `java.io.IOException`。
原因是现有 `ctos_ssh_native_client` 接口还要求借用 `struct sshkey *` 和
`ctos_ssh_view *`，而 Java 侧只有 opaque token；尚未存在可证明 revoke/close 顺序的
native-owned handle、view snapshot/borrow 协议。JVM 测试使用 fake `NativeBinding`，
因此不能外推 JNI、Android ABI、OpenSSH protocol 或设备 runtime。

验证命令：

```text
./hako bash -lc 'cd android && ./gradlew :app:testReleaseUnitTest --tests im.majo.ctos.SshNativeSessionTest --console=plain --rerun-tasks'
./hako bash -lc 'cd android && ./gradlew :app:testSshNativeClientHost :app:testSshCallbackAdapterHost :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease --rerun-tasks --console=plain'
```

结果：定向 `SshNativeSessionTest` 通过；full gate `BUILD SUCCESSFUL`，JUnit XML 汇总
**189 tests / 0 skipped / 0 failures / 0 errors**，callback host=20，
`SSH_NATIVE_CLIENT_BUILD gate=pass runtime=not-run reason=qemu-aarch64-unavailable`，
production build `SSH_PRODUCTION_OPENSSH_BUILD gate=pass fixture=excluded`。三 ABI
Release、strict `compileSshFiles`、lint 和 assemble 通过。APK SHA256 为
`21d16325b242427c2ccfbb7aa9cf42c6ca359700dc7b7f52995a95de06b94e72`；ZIP 仍只有生产
`lib/arm64-v8a/libctos_ssh_files.so`。native symbol scan 看到
`Java_im_majo_ctos_SshNativeSession_00024Native_{open,step,verifyHostKey,close,destroy}`，
forbidden=0，production archive members=83。

本轮没有使用 qemu、`192.168.9.14:5555`、真实 vault、凭据、设备或外部 SSH。JNI/runtime
session、userauth/typed credential、PTY/forward、foreground service/UI 和完整 SSH 仍未验收。
task 保持 `in_progress` / `implementation_complete=false`。

## 2026-10-15 — production PIC OpenSSH closure and native C caller

本轮把上一窄增量的 issuer/callback seam 提升为 normal APK 的 source/object closure 与
native C caller。固定 production runner 校验 G1 baseline/tree/patch hash，在 scratch build
目录执行 OpenSSH 10.6p1 `libssh-pic.a` source target 的 `-fPIC` 重编，应用项目-owned
hostfile stream-writer/KEX guard patch，编译 PIC `ssh-pkcs11.o`/`ssh-sk-client.o`，生成
83-member production archive，并记录 sources/objects/symbols；defined symbols 没有
`ssh_connect`、`ssh_login`、`ssh_userauth2` 或 `ssh_proxy_*`。

native caller 现在使用同进程 OpenSSH `ssh_api`：bounded `recv(MSG_DONTWAIT)` → input
append → packet turn → bounded `send(MSG_NOSIGNAL)`/output consume；host verifier 复用
managed public view/issuer/evidence，NEW candidate 失败回滚，KEX session digest 只回调一次。
open 失败不消费 caller 原 FD 或预打开 callback（OpenSSH 使用 CLOEXEC duplicate）；close
失败可重试，顺序为 callback release → KEX unbind/OpenSSH free → 原 FD close。这里是
production C/link boundary，不是 Android runtime/JNI/Java session 完成证据。

固定命令：

```text
./hako bash -lc 'bash /app/.trellis/tasks/10-08-ssh-terminal-client/research/android-hostfile-issuer-build.sh --baseline /app/build/ssh-native-g1/stock-run.lOHF9Oai'
./hako bash -lc 'cd android && ./gradlew :app:testSshNativeClientHost :app:testSshCallbackAdapterHost :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease --rerun-tasks --console=plain'
```

结果：G3 fixture `build/ssh-native-g3/run.aqN1F2bD/` 为
`SSH_HOSTFILE_CALLER_BUILD gate=pass runtime=not-run normal_apk=fixture-caller-not-wired`；
`:app:testSshNativeClientHost` 为 `SSH_NATIVE_CLIENT_BUILD gate=pass runtime=not-run`，
callback host gate=20，JUnit **177 tests / 0 skipped / 0 failures / 0 errors**。严格
`compileSshFiles`、arm64-v8a/armeabi-v7a/x86_64 Release native build、lint 和 assemble
均 `BUILD SUCCESSFUL`；APK SHA256 为
`805fc28679aa56be85fa3be75a1608701273908a02f7fb04dddb717c4e1b601e`。ZIP 只发现生产
`lib/arm64-v8a/libctos_ssh_files.so`，未发现 pinned caller、fixture KEX/hostfile object
或 stock high-level caller；Shell runner `bash -n`、ShellCheck、`git diff --check` 通过。

没有使用 `qemu-aarch64`、`192.168.9.14:5555`、真实 vault、凭据、设备或外部 SSH。ARM
binary 未执行；Android runtime/JNI/Java caller、userauth/typed credential、PTY/forward、
foreground service/UI 仍是开放关卡，task 保持 `in_progress`。

## 2026-10-15 — pinned OpenSSH caller fixture seam

在 hostfile issuer、managed HostEvidence/CAS 和 one-shot candidate handoff 之上新增
`ssh_pinned_client.[ch]` fixture caller。它只接受已绑定 live role 的 managed public view、
expected host fingerprint、callback token/environment、future deadline 和 owned socket；用
真实 pinned OpenSSH `ssh_api` 完成 `ssh_init`、FD attach、nonblocking packet loop、host
verifier、partial I/O/EAGAIN、EOF/cancel/deadline 与 KEX completion callback。caller 对
Java/managed 返回结果重新比对 role/hop/port/classification/outcome/CAS status/connection/
target/host/key/fingerprint/base/replacement digest；NEW candidate 在后续 host evidence
失败时显式 abort，避免 accepted candidate 残留。issuer error path 也总是关闭临时
`fmemopen`，并清理 bounded scratch/body。
replacement scratch 使用有界堆分配，owned socket 还要求 `FD_CLOEXEC|O_NONBLOCK|SOCK_STREAM`；
protocol/evidence 错误路径会回滚已 handoff 的 candidate。

固定命令：

```text
./hako bash -lc 'bash /app/.trellis/tasks/10-08-ssh-terminal-client/research/android-hostfile-issuer-build.sh --baseline /app/build/ssh-native-g1/stock-run.lOHF9Oai'
```

实际结果：`build/ssh-native-g3/run.3PZSbIzM/`，
`SSH_HOSTFILE_CALLER_BUILD gate=pass runtime=not-run normal_apk=not-wired`。runner 固定
G1 archive/tree/object hashes、应用 stream-writer 与 KEX guard patch、严格 C11/Werror 编译
并分别链接 issuer 与 pinned caller 为 AArch64 PIE，检查 required symbols、stock high-level
caller 排除和 normal APK 未接入；runner 的 exclusion 检查限定在正式 `compileSshFiles`
输入闭包，不把 opt-in KEX guard fixture 分支误报为生产接入。hako 没有 `qemu-aarch64`，所以 ARM executable 未运行，
`runtime=not-run` 不是 runtime pass。

同步质量门：`:app:testSshCallbackAdapterHost` 输出 callback host gate=20，
`:app:testSshNativeClientHost` 输出 native boundary gate=27；
`:app:testReleaseUnitTest :app:compileSshFiles :app:lintRelease :app:assembleRelease`
成功，JUnit XML 汇总 **177 tests / 0 skipped / 0 failures / 0 errors**，Release APK
SHA256 为 `67668e34e2bba40359e43e048404ffeb5645629a130f86e86b2d855a170ffff9`。normal
`libctos_ssh_files.so` 的 source/input closure 与 defined symbols 均不包含 issuer、pinned
caller 或 fixture KEX。没有连接 `192.168.9.14:5555`、真实 vault、外部 SSH 或网络设备。

这一步只完成 host/fixture source-object seam；`ssh_native_client` 生产边界仍保持
`ENOTSUP`，caller 没有进入 normal APK，也没有实现 userauth、typed credential、PTY、
forward、service、UI、Android runtime 或生产 event loop。task 继续
`in_progress`/`implementation_complete=false`。

## 2026-10-15 — pinned hostfile issuer fixture seam

在 managed `known_hosts` CAS/handoff 之上实现并固化了一个仍属 host/fixture 的
`ssh_hostfile_issuer.[ch]` seam：它只接受 live、role/deadline/connection/host 绑定且以
`ssh/config` 为 root、明确引用 `ssh/known_hosts` 的 public view；使用 pinned OpenSSH
10.6p1 `load_hostkeys_file()` 读取 caller-owned memory stream，并通过 source-owned
`ctos_hostfile_write_entry(FILE *)` 保留上游 known_hosts formatter。issuer 不调用
`add_host_to_hostfile`、`hostfile_replace_entries` 或 `hostfile_create_user_ssh_dir`，不打开
vault/host pathname。

非证书 key 保留上游 `HOST_OK/NEW/CHANGED/REVOKED` 到稳定分类的映射；证书仍按当前
Java policy `CERTIFICATE` fail-closed，但先执行 upstream revocation check，因此被撤销的
证书或 CA 分类为 `REVOKED`。NEW body 使用 exact public body + 上游 formatter 生成，计算
base/replacement SHA-256 后只通过既有 synchronous one-shot candidate handoff 传递；callback
失败会 abort 精确 candidate 并清零 scratch/body。

固定 runner：

```text
./hako bash -lc 'bash /app/.trellis/tasks/10-08-ssh-terminal-client/research/android-hostfile-issuer-build.sh --baseline /app/build/ssh-native-g1/stock-run.lOHF9Oai'
```

实际结果：`SSH_HOSTFILE_ISSUER_BUILD gate=pass runtime=not-run normal_apk=not-wired`。
runner 完成 pinned archive/patch/tree/object manifest、patch application、严格 C11/Werror
编译与 AArch64 PIE link，并把证据保留在本次 `build/ssh-native-g3/run.8YjiwDnk/`；hako
容器没有 `qemu-aarch64`，所以没有把 fixture 执行误记为 runtime pass。`compileSshFiles`
未加入 issuer/OpenSSH object，`ssh_native_client` 仍保持 `ENOTSUP`；未使用
`192.168.9.14:5555`、真实 vault、外部 SSH 或设备。

这一步尚未完成 production caller：socket partial I/O/EOF/EAGAIN、`ssh_api` event loop、
KEX/NEWKEYS proof、认证、PTY/forward/service/UI、Android runtime 和外部 SSH 仍开放，task
继续 `in_progress`/`implementation_complete=false`。

## 2026-10-15 — dedicated native→managed known_hosts candidate handoff

本轮在既有 metadata-only HostEvidence/CSHR 与 managed AppSecrets CAS writer 之间冻结并
实现独立的 synchronous one-shot handoff：C callback/JNI 只接受 `NEW`、exact role/hop/
target/connection/host/port/key-type/fingerprint、base/replacement digest、显式 candidate
ID 和不超过 `MAX_FILE_BYTES` 的 replacement body。`SshKnownHostsCandidateHandoff` 只把
body 交给同一 concrete provider 的 private candidate clone；现有 CSHR wire 没有增加 body。
native temporary、JNI array 和 managed input 在成功及每个失败路径清零；cancel/close/expiry、
duplicate/re-entry、wrong evidence/identity/digest、empty/oversize body 和 failed/uncertain
CAS 都 fail-closed。`COMMITTED` 仍只由 guarded `AppSecrets` update 成功后的原 CAS writer
构造，且 handoff 本身不会报告提交结果。

本轮固定 hako 命令：

```text
./hako bash -lc 'cd android && ./gradlew :app:testSshNativeClientHost :app:testSshCallbackAdapterHost :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease --rerun-tasks --console=plain'
```

结果：`BUILD SUCCESSFUL`；`SSH_NATIVE_CLIENT ... gate=pass value=27`、
`SSH_CALLBACK_HOST ... gate=pass value=20`，JUnit **177 tests / 0 failures / 0 errors /
0 skipped**。严格 `compileSshFiles`、arm64-v8a/armeabi-v7a/x86_64 Release native build、
lint 和 assemble 全部通过；APK SHA256 为
`3a6228f4237380dfc2cd98ccd537c16d4c6e81bc2e64e6205cdd6d556af9ded6`。ZIP 检查未发现
`ssh_native_client`、fixture/OpenSSH/launcher/KEX/host-only boundary 条目；关键词扫描唯一的
泛化命中是 Kotlin 的 `DebugProbesKt.bin`，不是 SSH/fixture object；普通包排除结论保持不变。

本轮只使用 host/JVM 与容器化本地构建；没有使用 `192.168.9.14:5555`、真实用户 vault、
外部 SSH 或网络设备。pinned OpenSSH hostfile issuer、production event loop/KEX/auth/
PTY/forward/service/UI 仍未实现，缺 issuer 时 native client 继续 `ENOTSUP`，task 仍为
`in_progress`。

## 2026-10-15 — production source/object closure re-audit

继续核对 approved G1 baseline 后确认：`libssh.a` 虽包含 `hostfile.o`、`ssh_api.o`、
`packet.o`、`sshkey.o` 和 pinned KEX objects，但现有
`android-upstream-build.sh` 只在 opt-in fixture 中发布 `g2-driver`/`g2-vfs`/
`g2-baseline`；普通 `compileSshFiles` 不消费这些 ignored `build/` 对象。
`hostfile.o` 仍依赖 OpenSSH key/buffer/HMAC 以及 `fopen`、`fdopen`、`mkstemp`、`link`、
`rename` 等未经生产 provider 收窄的文件系统闭包。

本轮测得对象哈希见 [production caller blocker](../.trellis/tasks/10-08-ssh-terminal-client/research/production-kex-caller-blocker-20261015.md#2026-10-15-sourceobject-closure-re-audit)。尚无 project-owned、同 pinned source/object manifest 的 hostfile issuer，故未链接普通 APK，
未改变 `ssh_native_client` 的 `ENOTSUP` fail-closed 边界；无代码、APK、设备、网络、
真实 vault 或 commit 变更。

## 2026-10-15 — production caller re-audit continuation（前一窄增量，已被 handoff 取代）

复核 pinned OpenSSH 10.6p1 source map 与当前 managed provider 后，确认 Java
`AppSecrets` CAS writer 已可用，但 hostfile issuer 和 native→managed exact candidate
handoff 仍不存在；因此没有链接 OpenSSH caller，也没有改变 normal APK source map。

本轮 focused hako 命令：

```text
./hako bash -lc 'cd android && ./gradlew :app:testSshNativeClientHost :app:testSshCallbackAdapterHost :app:testReleaseUnitTest --tests im.majo.ctos.SshManagedKnownHostsProviderTest --tests im.majo.ctos.SshProductionBridgeTest --rerun-tasks --console=plain'
```

结果：`BUILD SUCCESSFUL`；native client gate=27、callback gate=15，选定的 managed
provider/bridge JVM 测试任务通过，Release `compileSshFiles` 也在该任务图中严格重编通过。
本轮只更新 blocker/design 记录，没有重新生成或替换 APK，没有设备、真实 vault、外部
SSH 或网络操作；此前完整 174 JVM/lint/Release APK 结果仍以本节下一段为准。

文档/状态复核同时通过：`task.json` 与两个 task JSONL 文件可解析，当前状态仍为
`in_progress`/`implementation_complete=false`；scoped design/task/spec 文档的相对文件
链接和 heading anchors 通过，`git diff --check` 通过。该复核不新增 native/JNI 代码，
也不替代既有 native client=27、callback=15、JVM=174/0/0/0、strict
`compileSshFiles`、Release lint/assemble 及 APK hash 证据。

## 2026-10-15 — managed AppSecrets known_hosts CAS provider

本轮把前一节的 HostEvidence/CAS seam 接到真实 managed vault writer，而不是只替换
injectable test writer：

- `SshManagedKnownHostsProvider.snapshotFor` 通过 `AppSecrets` 的 SSH 文件
  snapshot 选择唯一的 KNOWN_HOSTS object，复制 path/type/id/identity、vault UUID、
  generation、epoch、deadline、request ID、snapshot binding 和 32-byte base digest；
  public body 只在同步 managed callback 内借用，消费后的 `SSH_FILES` operation
  立即取消。
- `KnownHostsCandidate` 只接受 caller 提供的 exact replacement bytes，一次性保存
  replacement digest 和 role/hop/target/evidence/object binding；它不从 fingerprint
  重新格式化 known_hosts，也不把 body 送进 JNI。
- `proposalFor` 消费一次 candidate 并产生 object-bound proposal；proof-aware
  `write` 通过 `SshVaultAdapter.trustRequest/updateTrust` 和 `VaultOperation`
  绑定 role、host proof、vault snapshot、对象身份、base/replacement digest，最终
  `AppSecrets.updateTrust` 对 KNOWN_HOSTS type/id/identity 与旧内容 digest 做 CAS，
  只有底层 guarded commit 返回后才构造 `CasResult.COMMITTED`。
- 缺 candidate、approval、validator、proof、role/object/digest mismatch、stale
  generation/epoch、取消或 deadline 均 fail-closed；proposal、candidate、digest 和
  operation cleanup 均为 one-shot。验证时发现旧的同步取消会与持 vault monitor 的
  commit 互等，已将 operation 表改为并发映射，并让 cancel 先在 monitor 外 revoke；
  `afterClaimCancellationIsUncertainAndCannotReopenLockedSession` 回归恢复通过。

本轮固定 hako 质量门：

```text
:app:testSshNativeClientHost              SSH_NATIVE_CLIENT gate=27
:app:testSshCallbackAdapterHost          SSH_CALLBACK_HOST gate=15
:app:testReleaseUnitTest                 174 tests / 0 failures / 0 errors / 0 skipped
:app:lintRelease :app:assembleRelease    BUILD SUCCESSFUL
```

JUnit XML 中新增 provider/bridge/CAS 相关回归与既有套件合计 **174 项**。严格
`compileSshFiles` 和 arm64-v8a、armeabi-v7a、x86_64 Release native build 均通过；
APK SHA256 为
`718c3934f3e972a3ac6996ab3a54aeae1116ddc3728fed1f751fdcf2619fe624`。ZIP 检查未发现
`ssh_native_client`、OpenSSH/launcher/KEX/fixture/probe/host-evidence boundary 条目；
正常包只保留生产 SSH library 及既有应用内容。

本轮没有使用 `192.168.9.14:5555`、真实用户 vault、外部 SSH 账号或新 Docker endpoint；
测试使用的是加密 manifest 的 in-memory `AppSecrets.Store`。这里证明的是 managed
AppSecrets CAS writer；exact replacement candidate 仍由受管
caller/test 注入，尚没有生产 OpenSSH hostfile parser/issuer 将其交给 provider。
`ssh_native_client` 因 native-facing provider 缺失仍返回 `ENOTSUP`，真实 OpenSSH
event loop、partial I/O/EOF、KEX/auth/PTY/forward/service/UI、设备和完整客户端 AC
继续开放，task 仍为 `in_progress`。

## 2026-10-15 — role/hop-bound HostEvidence/CAS contract（前一窄增量）

本轮冻结并实现 native↔Java host evidence seam：`SshNativeCallbackAdapter` 通过同步
callback 接收 native role token、hop、target metadata、classification、key type、SHA256
fingerprint 与 32-byte base/replacement digest。KNOWN 只返回 bounded
`CAS_NOT_REQUIRED`；NEW 必须由 `SshManagedProvider` 产出完整 role/hop/key-type-bound
proposal，经 `SshHostKeyAuthority` 显式 approval，再由注入的 `TrustCasWriter` 返回
精确 `COMMITTED` CAS 结果。返回给 native 的 wire 只有 bounded metadata、digest 和
proposal binding；known_hosts body 不跨 JNI。C/JNI 对返回结果重新校验，失败、取消或
期限竞争清零 result。

新增/强化回归：

- JVM 覆盖 KNOWN、NEW approved、无 provider、无 approval、wrong hop、base digest
  mismatch、classification/key-type substitution、stale proposal 与 exact CAS failure；
  `testReleaseUnitTest --rerun-tasks` 汇总 **166 tests / 0 failures / 0 errors / 0 skipped**。
- native callback host 覆盖 bounded evidence/result、KNOWN/NEW CAS status、malformed
  result、provider callback failure、thread/re-entry/cancel/deadline/cleanup；
  `SSH_CALLBACK_HOST ... host_evidence ... value=4`，最终 `gate=pass value=15`。
- 严格 `compileSshFiles`、三 ABI Release、`:app:lintRelease` 与 `:app:assembleRelease`
  全部 `BUILD SUCCESSFUL`。APK SHA256：
  `26747c49c3333eeff65544be9131668699281eaef7736166224b2856106bff06`；ZIP 中 SSH
  生产条目仍为 `lib/*/libctos_ssh_files.so`，没有 `ssh_native_client`、launcher、
  fixture OpenSSH object 或对应 probe 条目。

本节只证明可注入 provider contract；其后的 managed provider 段落已另行证明具体
AppSecrets known_hosts CAS writer，但不证明真实
OpenSSH event-loop caller、partial I/O/EOF、KEX/auth/PTY/forward/service/UI、设备或外部
SSH。缺 provider 的 `ssh_native_client.[ch]` 仍在独立 host gate=27 后稳定 `ENOTSUP`。

## 2026-10-15 — production KEX caller provider-missing fail-closed boundary

Pinned OpenSSH 10.6p1 source map confirms that the repository still lacks a
compilable production known_hosts issuer/CAS native-facing contract. The new
`ssh_native_client.[ch]` therefore only validates the live role, same PID/UID,
CLOEXEC/NONBLOCK socket ownership, callback token/environment, future deadline
and bounded step buffers. It returns stable `ENOTSUP` after admission, keeps
`*client == NULL`, does not close the caller FD, and never calls OpenSSH/KEX,
JNI or credential callbacks. It does not classify a fingerprint or create a
trust proposal.

The strict host gate passed:

```text
SSH_NATIVE_CLIENT phase=admission state=pass errno=0 value=27
SSH_NATIVE_CLIENT phase=provider state=pass errno=0 value=1
SSH_NATIVE_CLIENT phase=cleanup state=pass errno=0 value=1
SSH_NATIVE_CLIENT phase=gate state=pass errno=0 value=27
```

`:app:testSshNativeClientHost` passed through the project hako wrapper. The
boundary is intentionally excluded from `compileSshFiles`, so the normal APK
has no new client/OpenSSH/KEX object. This is not a production caller or KEX
pass: real socket partial I/O/EAGAIN/EOF, cancel/deadline, close retry,
inbound NEWKEYS/session digest/JNI proof, device, vault, external SSH,
userauth, PTY, forwarding, service and UI remain open until the provider/CAS
contract is frozen.

The combined hako gate
`:app:testSshNativeClientHost :app:testSshCallbackAdapterHost
:app:testReleaseUnitTest :app:lintRelease :app:assembleRelease` also completed
`BUILD SUCCESSFUL`. The JVM baseline is 162 tests with zero failures/errors/
skips; in this rerun the unit-test task was `UP-TO-DATE`. Strict
`compileSshFiles`, lint and all three Release ABIs passed. The APK SHA256 is
`28fc98aaf64d1e010cd63a2ba69086b891de6afc7851376ff65c663a8fc0a179`; ZIP
inspection shows `lib/arm64-v8a/libctos_ssh_files.so` as the only SSH-related
entry and no `ssh_native_client` or fixture OpenSSH object.

随后用 `:app:testReleaseUnitTest --rerun-tasks` 强制重跑，JUnit XML 汇总仍为 **162
tests, failures=0, errors=0, skipped=0**；因此上述 JVM 数量不是只由缓存任务状态推断。

## 2026-10-14 — SSH 受管 provider typed handoff 质量门禁

在用户确认的受管 `config/known_hosts` 文件视图、精确 trust proposal/CAS 和 typed
private-key handoff 方向上，本轮把 provider 的最小 production-facing seam 接到
同步 JNI callback：NEW host proposal 必须绑定准确 role/hop；typed password/private-key
bytes 必须同时匹配 host proof 与 signed KEX/NEWKEYS proof，并由
`SshCredentialBridge` 在最终 vault 使用前重复 role/host/operation/cancellation guard。
缺 provider、unbound/wrong-hop proposal、wrong-kind request、null role 或过期/错误环境
均 fail-closed。JNI 只把 Java byte[] 复制到 caller-owned buffer 并清零 Java array；native
失败会清零输出，cleanup context 在 native slot 移除后才释放，避免重试 UAF。

固定 hako 主线命令
`:app:testSshCallbackAdapterHost :app:testReleaseUnitTest :app:lintRelease
:app:assembleRelease` 成功。host 输出为：

```text
SSH_CALLBACK_HOST phase=thread_reentry state=pass errno=0 value=1
SSH_CALLBACK_HOST phase=cancel_deadline_cleanup state=pass errno=0 value=5
SSH_CALLBACK_HOST phase=credential_bytes state=pass errno=0 value=5
SSH_CALLBACK_HOST phase=gate state=pass errno=0 value=11
```

JVM 结果为 **162 tests, failures=0, errors=0, skipped=0**；严格 Android
`compileSshFiles`、lint 和三 ABI Release native build 通过。正常 APK SHA256 为
`f32bbc83a401c9997a3b901eccc4e2e5966808093ee1a3c17851ad62056df9ea`，ZIP 只有
`lib/arm64-v8a/libctos_ssh_files.so`，没有独立 fixture/probe/control/KEX/launcher
library。该结果只证明 bounded provider handoff 和其 host/JVM/native seam；具体
config/known_hosts file view、trust CAS 写回、真实 OpenSSH event-loop caller、userauth、
PTY、forward、service、UI、设备和外部 SSH 仍未验证，task 保持 `in_progress`/
`implementation_complete=false`。

## 2026-10-14 — `.14` 访问本机临时 Docker SSH 测试 pair

用户更正本轮目标为 `192.168.9.14:5555`，并授权在本机创建临时 Docker 服务、追加测试
SSH key pair、让该板尝试访问及完成后清理。连接时确认目标为 AIO-3568J、Android 11、
`arm64-v8a`；系统没有 `ssh` 命令，因此使用项目 G1 已固定的 Android arm64
OpenSSH `10.6p1` 客户端（OpenSSL `3.5.9`，SHA256
`5a3a15e6f4de095419aa369383a8e133861ca2bdff3c521cdb86fa896d81fa16`）。

本机 `192.168.9.5:2222` 绑定一次性 `linuxserver/openssh-server:latest` 容器（本地
image ID `d42d0d9d40e0`），仅创建 `ctos-test` 用户，关闭密码和 sudo；临时 Ed25519
公钥 fingerprint 为 `SHA256:QzXjQEBk1g1dx7uotibsDAJqHM004Yp/dDdNefst9u0`。
`.14` 使用临时 `known_hosts` 完成主机 key 匹配、KEX、公钥认证，并在容器内执行固定
命令 `printf ctos-14-pair-ok`，输出一致且 SSH exit status 为 `0`。本机先用同一 pair
回环登录也通过。

测试后停止并移除容器，删除本机 `/tmp` key pair、`.14` 的
`/data/local/tmp/ctos-ssh-test-*` 临时目录；未安装 APK、未修改正式 ctOS、未接触真实
秘密库或外部 SSH 账号。该结果证明当前板到临时 SSH endpoint 的网络、OpenSSH KEX、
host-key 文件和公钥认证链路可达，不证明 ctOS 生产 `libctos_ssh_files.so` 已调用
真实 event loop、JNI provider、userauth/PTY/forward/service/UI 或完整客户端已完成。

## 2026-10-14 — SSH 同进程同步 JNI callback adapter 质量门禁（provider seam 之前的 baseline）

用户已确认同进程 native event loop，以及当前 native event-loop 线程上的同步、
附着 JNI callback。新增 adapter 只作为正常 `libctos_ssh_files.so` 的生产契约接缝：
native 要求 live role、当前 PID/UID/deadline、原始 `JNIEnv`/`JavaVM` 和绑定线程；
Java 侧在 host proof → KEX proof 顺序上继续校验。跨线程/错误环境/重入/取消/期限
到期/迟到回调和 native-first cleanup failure 均有 fail-closed 回归。由于生产
provider 尚未确定，JNI 不传 unknown-host proposal，NEW host 返回
`SSH_HOST_KEY_REVIEW`；KEX 前 credential 返回 `SSH_KEX_REQUIRED`，KEX 后返回
`SSH_CREDENTIAL_PROVIDER`，不触碰 receiver、vault 或 UI。

固定 hako 主线命令
`:app:testSshCallbackAdapterHost :app:testReleaseUnitTest :app:lintRelease
:app:assembleRelease` 成功。host 输出为：

```text
SSH_CALLBACK_HOST phase=thread_reentry state=pass errno=0 value=1
SSH_CALLBACK_HOST phase=cancel_deadline_cleanup state=pass errno=0 value=5
SSH_CALLBACK_HOST phase=gate state=pass errno=0 value=6
```

JVM 结果为 **160 tests, failures=0, errors=0, skipped=0**；lint 无新增错误，
严格 Android JNI/native compile 通过。正常 APK SHA256 为
`fa4a6356bf594cee5245cca24c3faf1af911605c157155488cb24086c34c067e`，SSH 相关 ZIP
条目只有 `lib/arm64-v8a/libctos_ssh_files.so`，没有独立 KEX/control/policy/upstream/probe
或 launcher fixture library。该结果不代表真实 OpenSSH event-loop caller、trust/
file/private-key provider、userauth、PTY、forward、service、设备或外部 SSH 验收；
ARM binary、设备、外部 SSH 和 vault 均未执行，task 保持 `in_progress`。

## 2026-10-14 — SSH same-process KEX-only launcher fixture quality gate

当前窄增量通过源码审阅及主线独立重跑的固定 hako host/fixture/normal checks。launcher admission 的实际路径重查 token、当前 PID/UID、program/hop、nonce/binding、受限 role 字段、canonical CSRB 长度与字节内容、deadline、duplicate/capacity 和 KEX callback/fingerprint；teardown 精确匹配 token+client，先 `ctos_ssh_role_bootstrap_close`，成功后才 `ctos_ssh_kex_unbind`，失败保留 native binding 供重试，并擦除局部副本/槽位。KEX fixture 覆盖 malformed/trailing/wrong wire、过期与 stale admission、duplicate、mismatch/duplicate close、host/signature/pre-NEWKEYS、generic/DH-GEX/rekey/two-hop。

主线结果为 host `SSH_KEX handshake=27 cleanup=1 gate=27`、固定 runner 严格 `-Wall -Wextra -Werror` 编译、manifest 前后相同；最终 fixture run 为 `build/ssh-native-kex/run.CYUJUpCa`，artifact SHA256 为 `ad8c73c8acaaefed7b12f693ec6ea78759974a55384c8e500d74a98b5ef140a8`。普通 `:app:testReleaseUnitTest` 的 150 项测试与 `:app:lintRelease :app:assembleRelease` 均通过，APK SHA256 为 `538cae5a32499c9ff0aa4dcbca76a2e6637b6186c4177c1785dfdf86e2f843f9`，ZIP 只有生产 `libctos_ssh_files.so`。本 reviewer 另行通过变更后 launcher/test 的 NDK 严格 C 编译、runner `bash -n` 与 `git diff --check`。ARM binary 未执行；native close failure 的故障注入仅有源码可达路径审阅，未做运行时注入。

该结果严格限于 fixture/host seam；未做设备、外部 SSH 账号/endpoint、vault mutation，也未接入生产 event loop、认证、PTY、forward、service 或 UI。task 保持 `in_progress`，`implementation_complete=false`。

## 2026-10-13 SSH 生产角色、信任与凭据桥合同

本轮完成 production-facing Java bridge contract：角色 registry 绑定 owned PID、connection、hop、nonce、public view 和 deadline；主机 key authority 对 known/new/changed/revoked/certificate/invalid fail-closed，并将未知 key review 精确绑定到角色、目标、指纹和 known-hosts digest；凭据桥要求与 host proof 匹配的 signed KEX+NEWKEYS proof，再按 password、key passphrase、keyboard-interactive、private key 类型调用公共秘密库。失败 release（包括缺少 KEX）会关闭一次性请求并撤销对应 operation。

固定 hako 环境中 `:app:testReleaseUnitTest` **138项通过**，`lintRelease`/`assembleRelease` 成功；Flutter analyze 无问题、Flutter test **221项通过**。host C 文件视图 **426项/1634次擦除检查通过**，host control 的 transport/cleanup/gate 计数 **24/1/24** 全部通过。正常 APK SHA256 为 `3b82259dd15a0764f156473ff3500137b6e5c7f409ecb32296fd82e4be9ec7ed`，包内只保留生产 `libctos_ssh_files.so`，无测试 probe。

这是 Java 合同和本地 native 辅助验证，不是完整生产客户端验收：真实 OpenSSH launcher/native callback、host-key provider 接入、用户认证、PTY、forward、service、UI、设备安装及外部 SSH 账号/端点仍未验证。任务继续 `in_progress`。

## 2026-10-13 SSH bounded native role bootstrap seam

在独立 opt-in KEX fixture 中加入 native role bootstrap：仅当前进程 PID/UID 可签发 CLIENT/JUMP 元数据，固定 hop/connection/nonce/binding/public-view/deadline，same-owner/same-hop、nonce、same-hop binding 重放拒绝，distinct hop 允许共享 connection，过期/关闭/view revoke 可回收。metadata wire 有 2KiB 上限且不携带 argv、shell、路径、FD 或秘密正文；KEX guard 通过 token 绑定角色后仍要求 host fingerprint、签名和 NEWKEYS 完整时序。

Java decoder 要求显式 `NativeOwnership` 才能签发既有 Java role，并保留 native token/UID；无默认放行路径。`testSshRoleBootstrapHost` 实测 bootstrap/cleanup/gate **54/1/54 全部通过**，其中包含填满 8 个短期限槽位、未查询旧 token 即回收并复用的回归；严格 C 编译、KEX runner syntax/diff 与固定输入 native 构建通过（`run.WvNKWswX`，未运行 ARM binary）。容量回收修复后 143 JVM、lint、正常 Release 构建通过；最新正常 APK SHA256 为 `8ceacce92e8dc6432638ad493a3d1b1a77613edd600e5f064600efc7637bbd93`，ZIP 仍只包含生产 `libctos_ssh_files.so`。

该段是 fixture/host seam；production JNI ownership/open/release contract 另有下一节。native launcher、真实 OpenSSH host callback、协议认证/PTY/转发/服务/UI、设备安装或外部 SSH 账号/端点验收仍未完成。任务保持 `in_progress`。

## 2026-10-13 SSH production JNI role ownership/open seam

正常 `compileSshFiles` 现在把 `ssh_role_bootstrap.c` 与 `ssh_role_jni.c` 编入生产 `libctos_ssh_files.so`。JNI `open` 由 native 自身取得当前 PID/UID，只接受有界 role metadata 并返回 canonical `CSRB` wire；JNI `owns` 重新检查 live token、当前 UID、快照 owner，并将传入 wire 与 native canonical re-encode 做字节级比较。新增 JNI `release` 只接受 issued token/绑定 UID，native registry 再按当前 PID/UID 查找 live token。Java `productionOwnership()` 与 `productionRelease()` 没有 permissive 默认，`open()` 仅是 typed metadata wrapper；没有指针、FD、argv、shell、路径、文件正文或秘密接口。

post-fix 固定 hako 环境中 `:app:testReleaseUnitTest` **150项通过、0 failure/error/skip**，`lintRelease` 与 `assembleRelease` 成功；严格 Android native compile 通过，生成库 `readelf` 可见 `SshNativeRoleBootstrap` 的 `open/owns/release` JNI 导出。正常 APK SHA256 为 `538cae5a32499c9ff0aa4dcbca76a2e6637b6186c4177c1785dfdf86e2f843f9`，ZIP 仍只有一个 `lib/arm64-v8a/libctos_ssh_files.so`，没有独立 role/probe library。

该增量证明 production JNI ownership/open/release seam，但尚未由生产事件循环调用；
仍未实现 native launcher/OpenSSH host callback、协议认证/PTY/转发/服务/UI，未安装设备
或接触外部 SSH 账号/端点。完整 event-loop lifecycle integration 仍开放。

## 2026-10-13 SSH 真实KEX完成守卫

当前开发板独立UID10191/test10192，真实签名/NEWKEYS与每跳完成绑定 **27用例/1项6.999秒通过**；三类主机key、六种KEX、双向rekey、signature篡改、缺/到期role、NEWKEYS前拒绝和两hop交错/独立撤销。fixture15秒构建/lint、v2验签、真实段/ELF/安装读回一致；两个fixture已卸载，自有UID为空，正式APK3bc762d9…保持。正常release/133 JVM/lint41秒通过，APK55aef7e4…仅本地，实际排除全部测试探针与assets。失败轮与限制见[阶段检查](../.trellis/tasks/10-08-ssh-terminal-client/check.md#真实kex完成守卫27例当前板通过)。

这是实际上游KEX完成时序的证据，合成已知主机key由夹具签发；产品role issuer、未知host/证书/撤销核验、私钥/口令释放、用户认证/PTY/转发/服务/UI和联合恢复仍开放。

## 2026-10-13 SSH 原生控制通道

独立App UID10189在当前`.14` API30/4KiB/Permissive下 **24项transport用例、1项0.388秒通过**，包括实际SCM发送者、绑定/顺序/重放/截断拒绝、只读匿名FD收取和无泄漏回收。15秒构建/lint、v2签名、段核对/ELF与安装读回一致；两fixture已卸载、自有UID进程为空、正式APK3bc762d9…保持，见[完整证据](../.trellis/tasks/10-08-ssh-terminal-client/check.md#原生控制transport24例当前板通过)。

这是控制transport，不证明host-key分类、真实KEX完成、私钥/凭据释放或生产角色。真实源码先做host-key callback后验签，认证授权须在真实KEX证明后；不能从UI确认或终端文本直接放行。生产认证桥、协议/PTY/转发/服务/UI与混合恢复仍开放。

## 2026-10-13 SSH 通用文件解析与撤销

真实readconf/glob通过通用闭包视图读取：**147用例/1项0.780秒通过**；同批JNI **16用例/0.239秒通过**，实际128文件与8MiB正文边界。补齐发布后取消绑定后 **17用例/0.314秒通过**、133 JVM及lint通过。三轮独立夹具均已卸载，自有UID为空，正式APK3bc762d9…保持；426本地native用例/1634擦除检查另为host证据。完整产物/source pins、失败与限制见[阶段检查](../.trellis/tasks/10-08-ssh-terminal-client/check.md#当前通用文件解析147例与发布后撤销17例通过)。

公开文件借用不含私钥正文，类型/路径/闭包/预算独立检查，锁库/更换会话/取消精确撤销；FILE按上游真实POSIX形态读取且没有普通明文文件回退。控制transport正在接入，生产KEX/每跳授权、私钥/凭据释放、协议/PTY/转发/服务、UI与混合恢复仍未验收，不能据fixture或host结果启用生产validator。

## 2026-10-13 SSH 受管跳板参数与公共文件桥接

真实ssh.c跳板参数生成器新增逐项引用和字面百分号处理，实际percent_expand、8项自有系统shell builtin roundtrip及2项生成字段检查均完成。17秒构建/lint、v2验签与打包ELF/段核对通过，main55e39733…/test24851017…安装读回一致。当前板UID10181/test10182 **127例、1项0.796秒通过，无skip**；两fixture已卸载，自有UID进程为空，正式APK3bc762d9…保持。完整hash、固定源码及清理见[阶段检查](../.trellis/tasks/10-08-ssh-terminal-client/check.md#当前受管跳板参数127例通过公共文件视图桥接实施中)。

公共库同步文件接口随后加入私钥描述、公开正文与精确请求/库/连接绑定，正文借用结束即清零，私钥正文不在公开视图中；125 JVM与lint实际通过。JNI文件视图/锁库撤销继续实施，完整角色、生产provider、每跳审批/认证、协议/PTY/转发和客户端仍开放；参数引用通过不赋予任意命令或端点权限。

## 2026-10-13 SSH 原生配置政策与Include回归

在已通过101例基础上加入真实read_config_file_depth/compat glob、锁页匿名配置FILE，以及嵌套/相对目录/通配首值、parse-only执行拦截、展开后路径/缺失/深度8和每目标政策。两个扩展失败轮均0.166秒，数字诊断指出实际解析已成功，但存在额外拒绝文件访问1次；显式OpenSSL初始化NO_LOAD_CONFIG后归零，未放宽接受条件或实际读取普通配置文件。

修后18秒fixture构建/lint、实际v2验签通过，main0ead24fc…/test16751c66…安装读回一致。当前板UID10179/test10180 **117例、1项0.189秒通过，无skip**；fixture已卸载，自有进程为空、正式APK3bc762d9…保持。完整hash/失败诊断/边界及临时原始证据见 [check](../.trellis/tasks/10-08-ssh-terminal-client/check.md#原生配置政策与include回归117例通过)。生产file provider、受管shell参数、完整每跳审批/认证及协议仍开放。

## 2026-10-13 SSH 原生配置政策增量实测

冻结G1/G2之外新增真实上游三文件policy patch、缺provider早拒绝及独立测试APK。原parser保留none/no、inactive及首值语义，Match exec在展开前拒绝；有效指纹绕过/外部依赖和严格跳板语法按真实枚举处理。只证明缺provider时提前拒绝，不是人工信任审批或完整生产客户端。

19秒/111 tasks/36 executed构建、lint0错误3既有警告、v2/单signer及三个非法开关拒绝通过；正常APK13秒构建/lint，ZIP排除全部研究输出，hashd43dff68…保持。当前`.14`独立UID10173/test10174、公开合成固定方法 **1项/0.154秒通过，无skip，101用例**。test/main安装hash实际匹配，两个fixture已卸载，自有UID进程为空，正式APK3bc762d9…前后相同。完整hash/失败构建修正/阶段记录见 [check](../.trellis/tasks/10-08-ssh-terminal-client/check.md#原生配置政策首批增量通过)。

真实嵌套Include/受管路径、每跳绑定/审批、安全shell参数、信任CAS、认证/PTY/转发与公共库接入仍开放；本轮不接真实库，不把语法正例当已实现完整跳板。

## 2026-10-13 SSH G2 实机回归通过与清理

用户专项批准“允许，测试后清理”后，当前开发板`.14`独立UID合成G2测试第一轮1项/3.418秒失败：proxy继承缺清理回执，errno10可能是残留诊断，不能据此确认wait链根因。真实OpenSSH cleanup_exit使用 `_exit`跳过destructor；仅VFS客户端新增退出前自有对象清理hook，并明确区分launch/evidence错误，六组及清理断言保持。

修后源码/严格编译/1240输入覆盖/17秒fixture构建、lint及v2验签通过。main `23d33cca…`、test `cf673b52…`实际安装读回一致，fresh UID10171/test10172；指定方法 **1项/4.496秒通过，无skip**，六组、真实parent2199/jump2200及代次73、cleanup/gate全部通过。runtime crypto_version810549392出现；不据此标全算法矩阵通过。

两个fixture已精确卸载，自有UID进程表为空，正式APK SHA `3bc762d9…`前后相同。未操作真实库、外部SSH账号、Root、剪贴板或系统设置。完整hash与临时日志入口见 [task check](../.trellis/tasks/10-08-ssh-terminal-client/check.md#g2-第二轮当前设备回归通过与清理)。API30/4KiB/Permissive、固定16对象/16KiB可信合成及identification-only loopback是本轮证据边界；生产配置限制/主机信任、完整认证/PTY/转发及公共库接入继续实施。

## 2026-10-13 SSH G0 本地实现与构建隔离

TOTP 用户验收已提交 `566ca51`、归档 `f3762ba`、journal `74f8324`，当前复用原 SSH 客户端 task 为 in_progress。用户批准继续下一项，随后明确批准本轮 `.14` SSH G0安装/合成自动测试/卸载，并追加允许真实秘密库操作；凭据不记录。**G0已在当前板独立AppUID实际通过，完整OpenSSH与生产客户端仍未通过**；本轮仅合成数据，未操作真实库、SSH账号、锁屏输入、Root/剪贴板/系统设置或远程端点。

实现包含 opt-in C PIE、fixture-only Gradle 输出目录与固定方法的 Java instrumentation。对 ASharedMemory / raw memfd syscall 分别检查，不把 mmap 或诊断完成等同可用；至少一个完整候选通过才允许 G0 通过。固定合成载荷不创建普通数据文件，不启动页面、访问真实库/账号/剪贴板、Root 或网络。独立源码审阅及最终产物 hash 维护在 [SSH 阶段检查](../.trellis/tasks/10-08-ssh-terminal-client/check.md)。

本地实际执行：首轮14秒重建和 lint 0错误/3既有警告；API28/arm64/NDK27 严格编译，真实 AArch64 PIE `/system/bin/linker64` / `0x4000` PT_LOAD，动态依赖 libandroid/libdl/libc；两夹具 APK v2/单 signer 通过。随后又收紧失败路径诊断：未 reap 子进程不能输出 cleanup=pass；最终8秒重新构建/lint通过，最终ELF/v2验签、source SHA逐份匹配及唯一probe路径/arm64 ABI核对通过。最终两fixture hash为 `a14f1767…` / `0dafbda4…`，完整标识见task check。

生成 probe 后正常 release **26秒重新构建通过**；正常 ZIP 无 SSH 探针，APK `d43dff680ab8d31492cfb70a64e9ab315b3a99c01ef1e25ae172bc0e3384f3cb`，未安装。`ctosSshMemoryProbe=false`、true但不启独立fixture两项配置都实际拒绝，分别命中精确 opt-in / requires fixture。正常安装仍为上一轮TOTP实测的 `3bc762d9…`，未据本轮构建宣称设备已更新。

开发板实测：AIO-3568J Android11/API30/arm64、SELinux **Permissive**，正式UID10131/fresh fixture10167/test10168；两APK读回hash一致。仅执行固定方法+opt-in，**1项/1.693秒通过，无skip**。固定协议 page_size=4096、memlock_limit=67108864、eligible=1；ASharedMemory可map/mlock，但常规文件形态不适用（fstat shape errno95），raw memfd完整通过POSIX读写/取消与超时回收/受管exec白名单/失效拒绝/同步擦除/held-FD EOF及cleanup，最终gatepass、production_openssh=false。测试后自身UID进程表为空，两个精确fixture卸载Success/pm path为空，正式APK路径及SHA `3bc762d9…`前后相同。

G1 官方源码 payload、版本/许可证及工具链实际核对，来源见 [依赖](dependencies.md#2026-10-13-ssh-android-g1-构建输入)。unmodified stock make先在Bionic sentinel/兼容空宏冲突失败，后续两fresh兼容版本分别暴露bzero函数式宏与fallback定义冲突、私有resolver state不可用；三个失败run完整保留。最终兼容patch保留实际bzero函数和byte-identical explicit_bzero，仅研究专用 `__BIONIC__ && CTOS_ANDROID_ABI_BASELINE_ONLY` 分支使用公开res_init/res_query，不fake私有state/cache/headerchecks；仍不能证明EDNS0/DO/AD可信或VerifyHostKeyDNS可靠，后续受约束native必须拒绝提前accept并保留人工指纹批准。

最终 `stock-run.lOHF9Oai` 实际全量cross-build成功：OpenSSL3.5.9静态PIC库与私有install_dev，OpenSSH10.6p1 configure/WITH_OPENSSL1/WITH_ZLIB1、ssh/ssh-keygen及readelf均exit0。两个ELF实际AArch64/PIE、linker64、所有PT_LOAD0x4000，DT_NEEDED仅libz/libdl/libc、无hostglibc/动态libcrypto/TEXTREL；bzero/explicit_bzero/OPENSSL_cleanse/OpenSSL_version均有已定义符号。ssh **6,593,352B / `5a3a15e6…`**，ssh-keygen **6,172,496B / `591eb4a8…`**；完整hash和finalrunner/patch见 [阶段检查](../.trellis/tasks/10-08-ssh-terminal-client/check.md)。只计ABI兼容构建，不计unmodified stock、完整G1/运行算法/协议或生产trust通过；未执行、安装或打入正式APK。

G2 上游文件六组/Include/ProxyJump/配置执行与信任边界、G3秘密库/每跳/协议/PTY/转发/服务及父完整AC仍开放；缺失生产SSH provider继续fail-closed。当前G0不能代替Enforcing、API28/29或真实16KiB设备；编译不能代替实际协议/crypto算法。失败路径时钟/中断仍是源码审阅，未做设备故障注入。

本轮临时原始证据 `/tmp/ctos-ssh-g0-20261013/`，持久结论以本节和task check为准。

## 2026-10-13 SSH G2 上游文件夹具本地准备完成

真实10.6p1/OpenSSL3.5.9的独立driver/VFS client/closefrom observer已实现并完成本地构建与只读审阅。六组源码调用真实readconf/glob/authfile/hostfile及单跳ProxyJump，使用固定可信合成输入、匿名锁页和有界public proposal流；不是生产配置政策/信任provider或完整登录客户端。

审查修复固定metadata缺失误通过、清理回执忽略bootstrap错误、teardown信号竞态/重复关闭FD、未确认group丢失primary及reap后PGID复用风险。独立CLI与Gradle均检查完整输入树；1240文件aggregate构建前后核对，实际Gradle输入与独立oracle比较零缺失，包含.gitignore。相关pin/源码SHA及失败run全部见 [阶段检查](../.trellis/tasks/10-08-ssh-terminal-client/check.md#g2-最终本地构建与可审阅产物)。

最终fixture release/test/lint **17秒通过，111 tasks/27 executed，lint0错误/3既有警告**。两APK实际v2/1 signer、独立包名/SDK28/arm64通过；main **`aba810c1…`**、test **`cf673b52…`**。APK恰含三个G2程序及完整许可证/patch/输入manifest；strip后的.text/.rodata与最终build逐字节一致，真实APK ELF均PIE/linker64/LOAD0x4000、动态依赖仅libz/libdl/libc。G2输出已存在时正常release/lint13秒通过，ZIP不含研究程序/专用assets，正常控制APK仍`d43dff68…`，未安装。

原始产物/日志在临时`/tmp/ctos-ssh-g2-20261013/`，最后fresh native run `build/ssh-native-g2/run.Z9J9BADS`。当前`.14`只读在线，两独立fixture尚无安装路径。**G2未安装或运行，六组设备结果保持未测**；新增自有127.0.0.1两个随机端口与真实上游shell→exec链的identification失败/取消测试，当前准备可审阅产物后确认相应设备范围。正式ctOS/真实库未改，无远程账号测试，生产配置执行/信任绕过与后续认证/PTY/转发/公共库关卡继续开放。

## 2026-10-13 Authenticator TOTP 开发板自动验收（进行中）

2026-10-13 用户回复“效果不错；可以提交验收；然后继续做下一项”，接受当前TOTP交付并批准提交/归档，实际已提交566ca51/归档f3762ba。下方in_progress是验收前历史状态，执行日志、产物及未测边界继续按各自阶段解释；用户反馈不替代未执行的平台/账号/跨设备/混合测试，也不据归档标全部AC通过。

用户随后明确要求“帮我安装、自动测试”，目标为 `192.168.9.14:5555`。本节是该授权后的设备证据；下方“本地实现与最终产物”保留此前阶段结果。**安装及本轮自动验收完成：7项非copy原生检查通过，用户专项批准后的原生复制1项/6.616秒通过，最终fresh真实Flutter UI非copy全流程1项/72.734秒通过。当前task仍为in_progress / human-required，硬件相机及其他未测产品AC保持开放；不是全部平台/账号验收完成。**

设备核对为 AIO-3568J、Android11/API30、arm64，Keyguard secure=true、当前未锁定；CameraManager 公开能力及系统只读报告均为0相机。最终主包 SHA256 **`3bc762d9e6463ab9612393b9382923f4a29f613ceec3dbe4d9159aae0078f252`**，**32,935,473字节**，`./preview.sh` 28.3秒，arm64/v2/1 signer验签通过，覆盖安装后 base.apk 读回一致。此前c704及相机恢复修复版bd866（32,934,137字节）均为中间安装阶段。主包只核验公开概览/工作台，不打开用户真实秘密库或服务账号，不修改用户安全、Root设置。

自动验收采用显式 opt-in `secretsFixture` 独立UID，固定fixture/test包、无shared UID、`SecretsFixtureApplication`及Root自动启动关闭前置约束。原生测试使用独立内存密文库/UUID私有路径，实际Flutter流程使用fresh fixture库与公开RFC合成种子；图片仅创建本轮精确UUID自有PNG。测试没有读取生产私有库/Keystore别名、未选图库内容或原剪贴板。**自动审批拒绝包含全局剪贴板覆盖的执行**，理由是通用“安装、自动测试”未明确批准该副作用；未重试或绕过。平台copy方法在任何复制/读取前要求 `ctosTotpClipboardCopy=true`，UI复制也默认关闭；专项批准之前所有实际执行没有剪贴板读写。用户随后明确“允许覆盖，完成复制测试”，单项原生复制实际通过，见下方补充；没有原内容读取/保存/监听/恢复/清空或定时清理。

| 实际执行 | 结果与范围 |
| --- | --- |
| `TotpPlatformTest` 显式选取7个非copy方法 | **7项通过，62.548秒**。真实Android Bitmap离线多QR、无效码/图片上限/取消；真实Bridge typed预览/新增/重命名/删除、取消/锁定/注入预览期限与迟到读抑制；非空纯TOTP密文恢复；实际窗口FLAG_SECURE及0相机公开能力。日志 `platform-without-copy-run-1.log` |
| 真实release Flutter UI run1 `7756af71-aa02-49cc-b389-49796f510ca6` | **失败，19.792秒**：同名列表/添加动作造成driver在commit完成前核验。修复为确切列表路由/已启用条目动作的有界等待，未降低产品断言。只读自有失败状态检查 **1项/2.004秒通过**：1条/gen1，密文SHA `3ff4a2e01f3565d7df6c390b85bfc2cbab95fc624230c1f021554f8aaa17bda6`，自身持久授权0；日志 `ui-image-without-copy-run-1.log`、`failed-ui-owned-inspection-1.log` |
| 真实release Flutter UI run2 `fb90694e-4460-47f4-bea4-7bd0b6813027` | **失败，50.308秒**：创建/拒绝快捷邀请、手动预览取消/明确保存、RFC当前码/倒计时、重命名保留身份/ID/种子、真实Home宽限返回及实际锁库/主口令返回均通过；相机无硬件打开失败后的恢复提示未呈现。只读自有状态检查 **1项/2.020秒通过**：1条已重命名/gen1，密文SHA `93543e6fe3a98ee7ab32119ed22cf634501b8cfe2519a7bcd12ce155934c311c`，自身持久授权0；日志 `ui-image-without-copy-run-2.log`、`camera-failure-scoped-logcat-2.log`、`failed-ui-owned-inspection-2.log` |
| 修复后真实release Flutter UI run3 `194d0477-054a-41ca-93a4-db37b9bc9072` | **失败，45.858秒**：前述手动/管理/真实生命周期阶段保持通过，且**真实无硬件相机失败恢复通过**；随后DocumentsUI搜索字段的driver资源namespace断言失败，尚未进入图片确认。精确自有PNG已删除；日志 `ui-image-without-copy-run-3.log`、`ui-phases-3.log` |
| run3精确只读核清 | **1项/2.065秒通过**，已重命名1条/gen1，密文SHA `9a3bdc27b49637836d3994be7e34fc2b5f7ea148d9f066b875717bf82976b01c`，持久授权0，认证前检查无sidecar/日志/快捷包装；`failed-ui-owned-inspection-3.log`。随后只卸载两个精确fixture包并fresh重装 |
| 真实UI run4 `1162c6aa-3d2e-4d85-a0cc-46f1ca7107e3` | **失败，39.747秒**：搜索框及精确UUID输入通过，但Instrumentation发送Enter到跨UID DocumentsUI被系统INJECT_EVENTS边界拒绝。改用现有UiAutomation，每次按下/释放前重验准确搜索框/窗口/焦点/查询，不申请权限。PNG精确删除；只读核清**1项/1.997秒通过**，已重命名1条/gen1，密文SHA `03798988a7a8ee9cb4773eb7c0680b6f560634733cf622bcb158f4b8bec130d6`，持久授权0；日志 `ui-image-without-copy-run-4.log`、`failed-ui-owned-inspection-4.log`。核清后精确卸载两个fixture包 |
| 真实UI run5 `cf2acdd4-9979-4f1a-8e88-38e609ac9f9f` | **失败，39.770秒**：UiAutomation Enter DOWN已接受，随后driver要求搜索框继续focused的UP检查失败；该轮未记录逐项布尔值，不能冒称只捕获focus变化。修正为DOWN保留focus、配对UP保留精确原窗口/owner/资源/角色/查询而允许focus变化，并加入无原始文本的逐项布尔诊断。PNG精确删除；只读核清**1项/1.995秒通过**，已重命名1条/gen1，密文SHA `62b81139d0b0c70954c15ba34ea9e7a8440a45893f1964969850b12d67a49401`，持久授权0，随后精确卸载fixture；日志 `ui-image-without-copy-run-5.log`、`failed-ui-owned-inspection-5.log` |
| 真实UI run6 `7320559f-979b-42bc-b49d-2f3ed6df4be0` | **失败，49.945秒**：配对搜索成功，实际诊断为全部精确绑定布尔值true、仅focused=false；driver文件标题用错资源namespace，未选择图片。公开本机item_doc_grid/list布局及已有管理fixture证明文件标题为 `android:id/title`，精确修正两处，UUID/行归属/边界/窗口核验保持。PNG精确删除；只读核清**1项/2.022秒通过**，1条已重命名/gen1，密文SHA `883e86fde9393495164b5cd8a7c12eb5ef2debc440126c91296be298623532b7`，持久授权0，随后只卸载两个fixture；日志 `ui-image-without-copy-run-6.log`、`failed-ui-owned-inspection-6.log` |
| 真实UI run7 `189af0eb-91aa-4d6f-9e51-4006db65f3b9` | **失败，50.198秒**：改正标题资源后仍无可见精确图片标题，尚未选择图片。PNG精确删除；只读核清与限定picker诊断**1项/2.442秒通过**，1条已重命名/gen1，密文SHA `bd45423407dd0afb17f296d46b26e41fa6296f37105d9e497de2d9454e0a6c55`，持久授权0。选择器保留精确UUID查询，固定“图片”标签可见、roots_list不可见，标题full/stem均0；这些布尔值不证明当前根目录或文件不存在。公开本机菜单确认列表切换 `sub_menu_list`，接续测试使用标准列表视图保留严格文件名核验；日志 `ui-image-without-copy-run-7.log`、`failed-ui-owned-inspection-7.log`、`ui-phases-inspection-7.log` |
| 真实UI run8 `cadf6c22-0fdb-4289-a5e2-f3fa873f0fd7` | **失败，62.264秒**：标准列表模式通过，完整UUID标题/原item_root行归属/窗口/边界与点击均通过，随后真实Flutter“确认账号”20秒等待失败。PNG精确删除，未确认保存图片候选。源码另暴露成功native预览先于Dart前台恢复时因 `!visible` 被取消的路径；run8未抓精确结果/焦点回调时序或固定错误诊断，不能把该路径推定为唯一实机原因。随后修复有界同请求前台交接，并增加相关回归；日志 `ui-image-without-copy-run-8.log`、`ui-phases-8.log` |

相机问题的源码因果：Main原生 `onResume` 已放行pending结果，但Flutter固定3.35.7的LifecycleChannel在窗口未获焦点时仍为 `inactive`；添加页catch曾仅在 `visible=true` 保存错误，存在丢失恢复提示的路径。run2有真实camera打开失败日志，但未记录精确焦点/方法回调时序，不能冒称捕获了每一步竞态。产品修复仅按mounted/request revision/cancel/leaving核验保留稳定映射的非敏感错误；成功秘密预览仍需visible，未放宽原生会话/快照/发布guard。新增3项“错误先于恢复、相机会话变化/取消”widget回归，修复后 **217 Flutter全量、82定向、analyze无问题**；证据 `flutter-camera-fix-checks.log`。原生源码未改，**122 JVM与45真实字体场景仍是此前c704阶段结果，未在本次Flutter增量后重跑**。

修复后fixture main SHA256 `00294450c54a6ff646c0c9db1edc9b5193c1d5dc9be2ce79527e282bbb4a01b5`，fixture main/test构建与v2验签通过，fixture lint **0错误/3既有警告**，日志 `camera-fixed-artifacts-build-sign.log`。run3测试包不可变副本 `fixture-tests-fixed.apk` SHA256 **`8b3d0de79a9e668c5ff9336b9e622eeb10b27f7922849a362dbdc010b087f2f5`**；`tests-camera-inspection.apk` 的ad6e…是run2只读检查包，不混用作UI证据。DocumentsUI本机公开APK资源表核验搜索编辑框 `0x7f09013f` 对应 `com.android.documentsui:id/search_src_text`，仅修正driver精确owner资源匹配，不跳过搜索/选择/多候选必需路径。

前台交接增量：成功native预览元数据先于Dart恢复时，添加页在同请求、revision、epoch和当前路由条件下最多等待3秒；等待中不渲染秘密。Timer与单调时钟共同界定上限，原生120秒期限不续期，UI期限扣除等待时间。取消、锁定、事件失效、覆盖/离开使请求退场；超时给稳定错误并允许新请求重试。新增4个widget测试覆盖6种情形，旧bd866源码运行新增回归实际失败，新实现 **221 Flutter全量、86定向（33业务+53公共）、analyze无问题**。日志 `preview-handoff-red-regression.log`、`preview-handoff-green-final.log`；7个产品Dart文件冻结清单 `final-handoff-product-dart.sha256`。本次未重跑此前122 JVM或45字体场景。

run8失败状态只读认证 **1项/2.016秒通过**，1条/gen1，密文SHA `b7fc4e05e25ac6c6f03a10da1aa0cb8665fd439784278989603f9a944803330e`，持久授权0。核清后仅卸载两fixture包，再fresh运行修复后的产物。

run9 `fe187afc-3053-4121-b465-bbefd4d89b93` **失败，59.296秒**：实际进入双QR确认页，预览密文不变、未选择前确认禁用、明确选择Alpha并提交均通过；driver随后等待Alpha操作按钮失败。精确PNG删除，库保留核清。新增严格image_commit只读白名单检查 **1项/2.029秒通过**，认证后恰有原基线与所选Alpha、无Beta或其他条目，gen1，密文SHA `c823a6e1fd48e429fecdc31a83d1748f9df1a54f4c6835be686b7738fe6113b5`，持久授权0、检查前后密文逐字不变。不能据此推定实际进入了成功回执或其焦点时序。日志 `ui-image-without-copy-run-9.log`、`failed-ui-owned-inspection-9.log`、`ui-inspection-phases-9.log`。

最终fixture主包 `1efe858b4e185f7699dd53ea787f0960eaa3cf1b08553ce40d051038cf716ceb`；合并构建30秒、v2验签和lint0错误/3既有警告通过，日志 `preview-handoff-artifacts-build-sign.log`。run9测试包为 `2226898b610d424342a7e13ca8bc4aaf88358109e70d7883e79038642ed5f39b`；其后的 `38686de43a91c113452ffae95f86fb205cba16d6d20d111c0919104491d1b706` 为run9只读核清及run10的中间driver包。初次run10命令opt-in参数名错误，0.046秒在Before安全门跳过，未创建库；`ui-optin-argument-skip.log` **不计通过**。修正参数后run10 `864d391c-bfdf-43a1-b9c6-26a151e27ab3` **失败，60.320秒**，保存后固定诊断为列表标题可见、添加页与成功回执均不可见、会话前台且解锁；Alpha操作节点未找到。其图片精确删除，状态待核清；`ui-image-without-copy-run-10.log`、`ui-phases-10.log`。

run10只读核清 **1项/2.028秒通过**，恰有原基线和所选Alpha、无Beta/其他条目，gen1，密文SHA `1f3b05170285c59fd454e808c30b61bb4b007c07e5726737077000a1fa952c97`，持久授权0、检查前后密文字节相等；日志 `failed-ui-owned-inspection-10.log`、`ui-inspection-phases-10.log`。核清后精确卸载两fixture包并fresh重装。列表等待加入有界语义滚动，每个动作重新绑定确切Authenticator列表/窗口且无Add/编辑/确认/ACK；目标继续要求实际visible/enabled Button、精确tooltip、自身CLICK。成功ACK的支持也收紧到原窗口内fresh唯一精确标题/指定成功文案/Button，先严格认证恰有已选条目并检查FLAG_SECURE，再直接CLICK；无同名后继按钮等待，未知/失败/不确定ACK不接受。独立源码review通过。

**最终真实UI run11 `0e8e24b3-87b6-4691-b358-0fb87a297d9b`：1项/72.734秒通过，无skip。** 实际release Flutter/MethodChannel/宿主路径覆盖创建合成库并拒绝快捷邀请；手动预览取消密文不变、明确确认保存；独立HMAC核对RFC当前码/倒计时；重命名保留原ID/身份/种子；实际Home宽限清码/返回刷新、共享手动锁库隐藏/主口令返回；0硬件相机失败恢复；系统DocumentsUI列表模式与完整UUID文件选择，真实双QR预览密文不变、未选择前确认禁用、明确单选Alpha保存、无Beta/其他条目，原图摘要不变/无自身持久grant；删除Alpha后原基线保持；基线删除取消保持、确认移除；后台0秒实际Home锁库/空库主口令返回、恢复30秒并最终空库锁定、回工作台FLAG_SECURE释放。成功ACK分支本次**未触发**，不记该分支实机通过。日志 `ui-image-without-copy-run-11.log`、`ui-phases-11.log`。

最终test APK **`16a07a30ee407e567985f938a1a700d5f6441147c8829a019906bc2de44c7225`**，766,625字节，9秒增量编译及v2/1 signer通过（`list-scroll-ack-driver-build.log`、`final-fixture-test-signature.log`）；fixture主包1efe…，33,485,469字节。两包设备base.apk读回均逐字哈希匹配，文件 `installed-final-fixture-main.apk`、`installed-final-fixture-test.apk`；最终源码清单 `final-device-fixture-source.sha256` 匹配，driver SHA `41e3c8ad6b7284d515975c6cc10af71eed2be37bd7c2ac2985a8fbbb321d92fb`、helper `ae65bc10071a32d040a5111d80b58b315a93739026872d5f6631736e9af3b4bb`。正式主包及7个产品Dart冻结哈希保持3bc…，最终再次读回 `final-production-readback.apk` 一致。

最终清理：成功夹具断言空库/已锁、后台恢复30秒和Root自动启动关闭，未登记快捷；实际工作台窗口不再保护；After核验本轮精确自有MediaStore URI owner/name/mime后删除1行、随后成功query返回0行，摘要内存擦除/自有activity结束/会话锁定。随后只卸载 `.secretsfixture.test` 与 `.secretsfixture`，两次Success、`pm path`均空（退出1），正式`im.majo.ctos`仍在且哈希不变。证据 `cleanup-fixture-main-path.txt`、`cleanup-fixture-test-path.txt`、`final-production-path.txt`。所有失败run创建的自有图片均精确删除，失败库认证后才卸载；无批量图库/生产数据清理。本阶段未读写剪贴板，随后专项复制单项另记；真实库/账号未访问；未提交/归档/替换dist。

**后续复制专项批准与实测：** 用户回答“允许覆盖，完成复制测试”，明确授权用公开合成当前TOTP替换开发板全局剪贴板，不读取/保存/恢复/清空原内容；此前自动审批拒绝因而已有新的具体授权，并非绕过。重新fresh安装同一1efe主夹具/16a07测试包，显式仅选 `TotpPlatformTest#explicitNativeCopyWritesOnlyTheOwnedSyntheticCurrentCodeWithSensitiveHint` 并传 `ctosTotpClipboardCopy=true`。**1项/6.616秒通过，无skip**，`platform-explicit-copy-run-1.log`：真实Main/Bridge `secrets.totpCopy` 成功回执后才首次读取自身clip，label/item数量、已知RFC种子在实际前后墙钟窗口的当前码和 `android.content.extra.IS_SENSITIVE=true` 均断言通过；不输出代码，不读原内容、不保存/恢复/清空/监听。采用独立内存库，无生产库或真实账号；不是实际Flutter复制按钮E2E。After worker终止/库锁定/内存擦除/自有Activity结束/精确UUID空目录删除/专用prefs删除通过。随后两fixture再卸载Success，pm path均空、正式安装路径保持；证据 `copy-cleanup-fixture-main-path.txt`、`copy-cleanup-fixture-test-path.txt`、`copy-final-production-path.txt`。没有重复全类测试或全UI，也没有清理剪贴板；内容由用户自行处理。

证据边界：原生EventChannel是实际codec/Bridge上的loopback测试，不是Dart订阅；期限检查注入自有deadline而非真实等待120秒；窗口后台是注入Bridge lifecycle，证明真实FLAG而非实际Home、截图/录屏/最近任务。UI的Home/锁库是实际Android事件，但不能代替真实锁屏或进程死亡。原生Bitmap输入为自有file URI，非SAF；非空密文恢复为同设备独立内存纯TOTP目标，非跨设备或混合SSH。同UID自有MediaStore图片通过真实系统选择/解码，不证明跨UID grant强制/撤销；图片预览取消未做真实UI验证（手动取消、原生取消及widget图片取消另有证据）。相机硬件成功扫描因0设备**不支持/未测**，本轮成功恢复不代替首次权限拒绝验收。实际断网/墙钟跳变、真实Flutter复制按钮E2E、服务MFA/账号、API28/29、生物、真实进程死亡、非空跨设备/混合SSH/native与每跳信任恢复均开放，缺失生产SSH provider继续失败关闭。未提交、归档或替换dist。

所有本节相对日志均位于 `/tmp/ctos-totp-device-20261013/`，属于临时原始证据，不保证清理或环境更换后持久；持久结论与未测边界以本节和 [task check](../.trellis/tasks/archive/2026-10/10-08-authenticator/check.md) 为准。

## 2026-10-13 Authenticator TOTP 本地实现与最终产物

用户要求检查当前实现，并明确“复用现有 task，先 TOTP 后 SSH（推荐）”。已恢复原 `10-08-authenticator` 并完成工作台入口、相机/图片/手动添加、列表/倒计时/宿主复制/重命名/删除及公共秘密库接入；本轮授权本地实施、检查与构建，未安装设备或操作真实账号。公共API30凭证快捷成功属于原日期/原包证据，底座仍in_progress及未测关卡保持。

本轮用户另更正当前开发板入口为 `192.168.9.14:5555`，已同步AGENTS、design/constraints及Trellis验证spec；此前询问中的 `.13` 来源于旧AGENTS错误。地址记录更正不等于已连接/安装或平台验收通过。

本地检查均使用既有 `./hako` Docker入口（Flutter3.35.7/Dart3.9.2、项目固定Android工具链），没有更新宿主全局工具链。初始sandbox拒绝Docker socket后，在当前任务授权范围经自动审查批准执行同一入口；不是产品检查失败，也没有自动审批拒绝未解决。

| 检查 | 实际结果与证据 |
| --- | --- |
| `./hako flutter test --reporter expanded` | 最终 **214项**通过，17秒；`/tmp/ctos-totp-flutter-full.log` |
| `./hako flutter test test/authenticator_test.dart test/secrets_ui_test.dart --reporter expanded` | **79项**通过（26新增业务+53公共秘密库）；`/tmp/ctos-totp-flutter-focused.log` |
| `./hako flutter analyze`、Dart格式/diff | analyze无问题（4.9秒），格式及diff通过；`/tmp/ctos-totp-flutter-analyze.log`，7个最终产品Dart文件SHA清单 `/tmp/ctos-totp-flutter-source.sha256` 在最终APK构建后全部匹配 |
| `./hako bash -lc 'cd android && ./gradlew :app:testReleaseUnitTest :app:lintRelease --console=plain'` | **122 JVM项**（106原有+16新增）通过，XML汇总0failures/0errors/0skipped；lint **0错误/3个既有警告**，在Collector包可见性、Gradle更新提示及旧API备份规则；`/tmp/ctos-totp-native-final-unicode.log`，XML与lint位于 `build/app/` |
| `./hako bash -lc 'cd android && ./gradlew :app:assembleReleaseAndroidTest --console=plain'` | instrumentation APK编译通过，**未执行设备测试**；`/tmp/ctos-totp-instrumentation-compile.log` |
| `./hako flutter test build/authenticator-evidence/preview_test.dart --reporter expanded` | **45真实字体PNG场景**通过，5种尺寸/字号/键盘配置×9状态，0渲染异常；主会话/独立check审阅窄屏列表、2倍字体手动操作、横屏候选确认及不确定结果。`build/authenticator-evidence/matrix.json` 与PNG同目录，日志 `/tmp/ctos-totp-flutter-render.log` |
| `./preview.sh` | 最终arm64 release成功，Gradle30.8秒，**32,934,089字节**；`/tmp/ctos-totp-final-build.log` |
| `./hako bash -lc '"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs build/app/outputs/flutter-apk/app-release.apk'` | **v2=true，1 signer**，现有开发证书SHA256 `8d36c8be418174cb4cda0b897ff3e32b2da6f84032819726e8e5671872df52ef`；`/tmp/ctos-totp-final-signature.log` |

最终APK：`build/app/outputs/flutter-apk/app-release.apk`，SHA256 **`c704ff362ea8402709833217025e800a298917c698183bf6a4550388c40e85eb`**。ZIP核验只有arm64-v8a原生ABI，三份ZXing完整LICENSE/NOTICE与源资产逐字一致；依赖固定core3.5.4/Embedded4.3.0，来源/哈希见 [dependencies](dependencies.md)。没有替换 `dist`、提交或归档。

本轮新增宿主验证覆盖支持参数RFC向量、前导零/窗口/2038之后、严格URI/UTF8/Base32、真实生成的多QR图离线读取、资源上限、预览种子清理/取消/期限/代次、条目命名空间和新ID不覆盖、重命名保留内容、最终publication无store读取、5000次只读轮询及淘汰后的写入拒绝。Flutter覆盖严格投影、永久read命名空间重试/取消、公共入口有效性、事件EOF/损坏及重连、路由/生命周期/锁定/迟到结果、墙钟跳变/刷新迟到、明确选择/提交/取消/不确定回执、长名称及等待取消期间双击复制只提交一次。

独立review发现的事件EOF残留旧码、淘汰读取ID可写、预览revision未绑实际快照以及最终锁内资源清理已由owner修复；额外窄屏大字Base32提示/计数重叠、长名称对话框和双击复制也修复并补回归。最终复核没有剩余具体源码阻塞。临时渲染driver曾在延迟构建目标的ensureVisible失败，仅调整夹具，不将其记为产品失败；最终driver SHA256 `9bc582f9f466b8e0b9ec9c5ec2cb1ae221748e6c35e02bd8535004e09fe4470e`。图片内容均为合成账号和代码，没有真实种子/服务凭据。

**验收分类：human-required，task保持in_progress。** 相机硬件/权限生命周期、真实图片provider/临时授权退场、实际Bitmap与安全窗口/截图/录屏/最近任务、ROM敏感复制、断网设备发码及非空TOTP跨设备备份恢复尚未执行；mock、JVM和渲染不能代替。真实MFA绑定/登录需服务账号另授；混合SSH/native文件与每跳信任恢复仍依后续SSH provider和Android探针，当前缺失时生产相关恢复继续拒绝。公共API28/29、生物、真实进程死亡也不在本轮宣称通过。逐项AC见 [task check](../.trellis/tasks/archive/2026-10/10-08-authenticator/check.md)，下一步仅在当前设备授权后安装核验上述场景，SSH按用户顺序继续已有规划。

`/tmp/`日志及被忽略的build预览是本轮临时原始证据，不保证跨机器/清理后存在；持久结论和未测边界以本节、领域文档及task check为准。

## 2026-10-12 最终概览文案收敛与提交

工作提交`5e67227`包含本任务35个相关代码/测试/夹具/spec/design/任务文件；按用户授权正常归档并清除当前指针。下方测试结果及未测边界继续按其日期/APK解释，原公共底座仍未全量验收。

用户要求当前task一起完成并提交，不新建任务。去掉摘要DEVICE和Android/运行时间行，网络/接口/连接统一静态范围描述，接口原数量/来源移到搜索框下元数据，详细设备字段保持。本轮只有main.dart产品增量，没有host/认证/采样变化。

- implement定向27项/格式/analyze4秒通过，完整任务check无缺陷；最终188 Flutter全量15秒、analyze1.5秒、14文件格式0修改及diffcheck通过。日志`/tmp/ctos-overview-copy-*`和`/tmp/ctos-overview-copy-check-*`；Android夹具哈希不变，沿用已执行编译/lint0错误3既有警告。
- 6配置84真字体场景/1驱动7秒通过，`build/overview-final-evidence/`；主会话实看320dp、2倍字和三分类截图。驱动SHA256`b6ec4383c175b3ab745421c369a56e45fdb8b8b5ec708eaf5ecbbbe6506e4eeb`，日志`/tmp/ctos-overview-copy-render.log`。
- `./preview.sh` arm64 release成功，Gradle28.7秒，32,217,117字节；v2验签/1 signer通过。最终APK SHA256 **`25bcdf7c22067afaac0c9c457e29ad0bc802194910eec7e2c71bf1f1ada95f78`**；main源码`393741d4c48215e45766ac65f34818d8995701ea2e0b256f98d71cf4e001ea44`，其他源哈希`/tmp/ctos-overview-copy-check-reviewed-sha256.txt`，构建/签名日志`/tmp/ctos-overview-copy-{build,signature}.log`。

此轮用户提交授权覆盖整个概览/设置任务及全部反馈，先前原底座/其他业务任务的验收状态保持。新版成功系统认证、其他ROM/输入法与真实快照保存等边界沿用下方实测记录；不重试原共享写盘审批拒绝。最终设备安装/读回及分类布局结果见 [task实机记录](../.trellis/tasks/archive/2026-10/10-12-overview-settings/device-check.md)。

最终25bcdf7c…已在AIO-3568J/API30覆盖安装，不清数据；再次base.apk读回哈希一致，四分类实际节点和主会话实看4张PNG确认摘要删除和全部副标题正确，接口来源/计数、连接状态/时间/映射保留。当前设备证据`/tmp/ctos-overview-final-device-20261012/`。继续会话后本地ADB及前轮/tmp目录重置，已重连已授权.14/核对解锁身份并重建当前证据；旧/tmp日志仅按执行时位置解释，源码/最终84渲染驱动及APK哈希均与已记录结果一致，没有因此重做或冒称未测平台验收。

## 2026-10-12 概览截图细节与默认快捷解锁

继续既有 overview-settings 任务完成用户四项截图反馈：分类全宽均分/文字居中、标题旁绿色/黄色Root信息按钮、移除APP状态与终端眉标题，以及登记快捷优先/未登记口令/快捷失败三选择。独立 Trellis check 完整复核无产品缺陷，新增3项已解锁管理真实口令回归；当前用户仍未授权提交/归档或替换dist。

- Flutter初轮93定向、最终秘密库53项和全量188项通过；全量16秒，analyze无问题2.0秒，14个Dart文件格式0修改、diff检查通过。日志 `/tmp/ctos-overview-increment-{focused,secrets-final,full-final,analyze-final,format}.log`。
- Android隔离UI夹具Java编译及`lintSecretsFixtureRelease`通过，32秒，0错误/3既有警告；`/tmp/ctos-overview-increment-android-fixture.log`。本轮未运行隔离库认证E2E。
- 真CJK/Roboto/Material/monospace字体6配置×14页面/状态=84PNG、1渲染驱动7秒通过。320/375dp、横屏、1200dp、2倍字号与减少动画均包含，主会话实看代表性截图。`build/overview-increment-evidence/preview_test.dart` SHA256 `55c529a1349f6da2b32e60e5a7c1de40ff01399885c283fb3a8aa3e192023456`；matrix及图片同目录，日志 `/tmp/ctos-overview-increment-render.log`。初次临时夹具的pumpAndSettle在异步快捷路由等待上超时，采用既有测试的多帧推进后通过，没有修改产品代码来绕过。
- `./preview.sh` arm64 release成功，Gradle28.8秒，32,217,261字节；apksigner36验证v2=true/1 signer。APK `build/app/outputs/flutter-apk/app-release.apk` SHA256 **`af600c41e93040026591d2b72285eb5c5b79a41363965e30c02f98bd7f62295a`**，日志 `/tmp/ctos-overview-increment-{build,signature}.log`。

沿用本任务已获“自行连接上去，自行验收”授权；显式192.168.9.14:5555重新核对AIO-3568J/API30，Keyguard showing=false/secure=true。覆盖安装不清数据，设备base.apk读回与新版哈希一致。实际四分类均分（x330–1730，每项350px）、ctOS旁绿色Root信息/可读来源及授权结果、无旧状态行和终端眉标题通过。已登记库点击解锁直接打开系统BiometricPrompt；Back和中心取消面没有结束该ROM的浮层，通过Home取消并恢复ctOS后显示“操作已取消。”及取消/主口令/快捷三选择；选择快捷再次调起系统提示，再Home取消后选择主口令进入受保护口令页，空表单取消返回已锁定/快捷已开启。未输入系统或主口令，**本APK成功系统认证未由agent重测**，不更改快捷登记或用户库。

App PTY公开`echo CTOS_NAV_20261012`输出，在设置/工作台/概览/终端切换后保持；普通键盘输入、执行/收键盘、输出快照二级返回路径实际使用，测试会话随后关闭。该结果不代替其他输入法/中文组合、RootPTY或底座平台关卡。全部临时设备证据在 `/tmp/ctos-overview-increment-device-20261012/`；BiometricPrompt期间screencap返回空文件`quick-failure.png`，不作为画面证据，实际恢复弹窗使用`quick-recovery.png`。

四个主入口首次下方提示分别拍摄并实看；等待过期后单个`input keyevent 4 4`连续返回，各次mResumedActivity均切到QuickstepLauncher，重开成功。首轮临时驱动在两次返回间夹入截图后未退出，发送前测时未覆盖第二个ADB命令的到达耗时，不能作为2秒内失败证据；合并输入重跑全部通过、未改产品。概览图有一次自动Root恢复的Magisk系统Toast叠层，其余3张提示清晰。设备页实际滚到最后电源/温度和存储/data字段，底部内容完整可达。结果和精确驱动见back-results.json/back_check.py；系统/用户库配置保持。

原共享快照保存/pull审批拒绝边界保持，本轮没有重试该操作。没有修改系统安全、Root配置、真实库内容或其他应用。具体返回结果和剩余边界见 [本task实机记录](../.trellis/tasks/archive/2026-10/10-12-overview-settings/device-check.md)。

## 2026-10-12 概览设置追加开发板验收（部分完成）

用户追加“你可以自行连接上去，自行验收”，授权当前开发板安装和本任务UI检查。约定.13拒绝；当前ADB唯一.14经核对为AIO-3568J/Android11/API30/1920×1080/density280。保留旧71d00e7c…包并覆盖安装成功，读回base.apk与本地均为 `a441d1141da02708eecfc6638840af154accd3df7ffdc94e9a2e540e1054c4c6`；全部设备命令显式指定目标，不清应用数据。

实际概览四分类切换/滚动、实时数据/来源时间、宽屏四入口及无顶部导出、设置/安全完整管理入口与现有已锁定/快捷已开启/后台30秒状态通过。备份导出认证页打开后未输入口令即取消；安全返回设置正常。全局导出从设置到设备单项选择、启用动作、系统SAF保存页和取消反馈实际通过，不创建用户库/备份/恢复或改变安全设置。

准备唯一临时快照文件名后，实际保存到共享目录并pull动作被自动审批拒绝，理由是授权未明确覆盖该真实payload和目的地。没有绕过；取消后精确文件不存在核验通过，没有读回内容，真实写盘/单项JSON读回**未执行**，不能写通过。

后续工作台/终端导航检查时开发板出现Android启动logo和锁屏；`/proc/uptime`330.37秒、boot_completed=1，确认系统重启。agent未执行reboot/force-stop/系统配置，原因未确定，当前安全锁屏阻止继续；已请用户在板上解锁，不获取系统凭证。剩余四主入口双返回、AppPTY/IME、设备底部和历史接口回跳，不把驱动找不到锁屏后的按钮算成产品失败或验收通过。

同步 `AppSecretsUiFlowTest.java` 原工作台/秘密库设置导航、页标题与固定滚动锚点为设置/安全；只对精确入口增加View角色，保留活跃fixture包/窗口/自身CLICK与非editable约束。离线`-PctosSecretsUiFixture=true :app:assembleSecretsFixtureReleaseAndroidTest`编译通过（worker12秒）；本轮未运行原密码/认证E2E，生产源码及APK未变。完整证据与后续结果见 [device check](../.trellis/tasks/archive/2026-10/10-12-overview-settings/device-check.md)。原始截图、UI驱动和旧/新APK位于 `/tmp/ctos-overview-settings-device-20261012/`。

任务仍current/in_progress，设备验收部分完成、待系统解锁；未提交/归档/替换dist。以下本地交付阶段的“没有设备操作授权”仅为当时状态，已由本次追加授权更新。

## 2026-10-12 概览整合与设置迁移

用户要求新建 [overview-settings task](../.trellis/tasks/archive/2026-10/10-12-overview-settings/prd.md)，设为 current 并开始完成。源码已实现，task 保持 current / in_progress；本轮仅本地工作，没有 ADB/设备安装、系统设置、真实账号操作、dist 替换、提交或归档。

- **实现**：导航为概览/工作台/终端/设置。删除独立信息入口及网络现场/能力与恢复，原四分类和完整内容直接接在概览摘要后；每分类一个竖向列表，保留搜索/列表/PTY状态。顶部导出整体迁至设置/数据导出，完整秘密库管理迁至设置/安全，工作台旧入口移除。网络分类保留 Root 失败说明和重试，连接进入/返回概览/恢复前台刷新，工作台/设置不额外网络轮询。Android host、加密、依赖和权限没有修改。
- **自动化**：Trellis check 全范围复核后 `./hako flutter test` **160 项通过**（15秒）。随后修正新增设置测试的假数据缺失 `kernel.elapsed`，加强成功采样及正向概览轮询断言；`./hako flutter test test/settings_navigation_test.dart` **6 项重新通过**。`./hako flutter analyze` 无问题（4.1秒），12 个改动 Dart 文件格式检查0修改，`git diff --check` 通过。35项既有秘密库交互及16项主页返回包含在全量内，不重复计数。
- **渲染**：使用当前 Observatory、设置、安全和导出真实 Flutter 组件，合成公开 native-shaped 数据及 CJK/Roboto/等宽/MaterialIcons 字体，生成 **60 场景 / 60 PNG**。320×700、375×812、800×500、1200×900和375/800两倍字号；减少动画，覆盖概览/设备/存储/网络/接口/连接、设置、安全、备份及数据导出。全部场景异常断言通过，主会话检查代表性窄屏/宽屏/大字及下方内容。字体注入仅用于克服 widget 测试默认 Ahem，不属于生产主题修改或实际设备屏幕。
- **构建**：`./preview.sh` 成功，Gradle30.8秒，APK **32,216,569字节**；build-tools36 `apksigner verify --verbose` 通过，v2=true / 1 signer。产物 [app-release.apk](../build/app/outputs/flutter-apk/app-release.apk)，SHA-256 `a441d1141da02708eecfc6638840af154accd3df7ffdc94e9a2e540e1054c4c6`。未安装或更新 dist。

主要源码 SHA-256：main `fd7b7d40…`、settings `669cc879…`、device `5152074f…`、workbench `7e6faefc…`、secret settings `683a1cb5…`、secret flows `42fc1286…`。完整清单/验收映射见 [task check](../.trellis/tasks/archive/2026-10/10-12-overview-settings/check.md)。临时日志 `/tmp/ctos-overview-settings-{full,review-test,analyze,format,render,build,signature}.log`；渲染夹具、图片和 matrix 位于忽略的 `build/overview-settings-evidence/`，后续构建可覆盖临时产物。

Review：**human-required**，因为本轮没有实际设备观感、系统返回/软键盘或 SAF/快捷认证复验。建议检查概览四分类和滚动、设置导出及取消、完整安全操作入口、二级页普通返回及四主入口双返回；不把既有公共底座设备记录作为迁移版新 APK 的实测结论，其他公共底座未测关卡保持。

## 2026-10-12 安全锁屏开发板快捷认证修复与实测

后续用户回复“效果可以；提交所有相关 task 文件”，确认当前效果并授权阶段成果提交；未测关卡保持开放，不由此标整个底座完成。本轮提交只补做范围/元数据/产物检查，不重复设备操作或冒称新全量测试。

用户提供已设置安全锁屏的开发板并授权自行继续测试。`adb devices -l` 唯一在线目标为 `192.168.9.14:5555`，核对 AIO-3568J / Android 11 API 30。仅向已确认活动的系统 BiometricPrompt / SystemUI 数字密码框输入本轮授权凭证；不保存凭据、不修改系统安全设置、不操作真实 SSH 账号。任务恢复 active / in_progress，10-11 的暂停记录保留为历史。

- **症状与修复**：旧实现成功生成认证绑定密钥、初始化 Cipher，甚至 `updateAAD` 返回，但系统凭证认证后登记没有保存包装。将 `AppAuthentication` 的相同 `materialBinding()` AAD 从认证前 `cipher()` 移至成功认证后的 `perform()`，位于请求/epoch/期限检查及 API28/29 Cipher 创建后、`doFinal` 前。API30 继续使用原 CryptoObject/Cipher、每次认证参数 0 / 强生物或系统凭证，不改变 GCM、AAD 内容、错误回退或持久化保护。Android11 [Cipher 实现](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-11.0.0_r1/keystore/java/android/security/keystore/AndroidKeyStoreCipherSpiBase.java) 的 `engineUpdateAAD` 可缓存 Keystore 异常，后续 `doFinal` 才抛出；[GCM 实现](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-11.0.0_r1/keystore/java/android/security/keystore/AndroidKeyStoreAuthenticatedAESCipherSpi.java) 可直接提交 AAD。旧 ROM 的具体异常未直接捕获，机制解释是源码与同板红绿结果支持的判断，不声称读到了具体错误码。
- **诊断单列**：公开能力诊断 1项/0.030秒：isDeviceSecure/isKeyguardSecure=true，系统凭证及组合 canAuthenticate=0，强生物=12，未报告指纹/人脸/虹膜 feature。独立 UID 自有精确 probe alias 的生成/getKey/Cipher.init/AAD 初始化诊断 1项/0.123秒完成；API报告 authRequired=true、authType=3、validity=0、insideSecureHardware=true，alias 最终缺失。初始化成功不等于成功认证，硬件字段不等于硬件审计；该探针没有 prompt/doFinal。
- **同一真实流程红绿**：未改动的 `AppSecretsUiFlowTest#freshFixtureManualSecureQuickAuthenticationFlow`（源码 `05b7a759…`）旧 main `7650dcef…` / test `e8c8f5ed…`，run `3828e47a-d711-4bbb-a59b-7bc5d03aa2fa`，系统 PIN 已输入且认证窗口结束，但登记超时失败，100.698秒。只移动生产 AAD 时序后的 main `437628ef…` / test `5eb96824…`，run `290a2d3e-17e5-4ce3-9bac-afe3e69a7a3e`，**1项通过/95.811秒，0失败/跳过**：创建合成空库并拒绝初始快捷邀请、系统登记持久包装、锁定后系统快捷解锁、真实会话密钥认证解密未变密文、关闭取消/错口令保留、正确关闭锁定且包装/自有 alias 消失、主口令回退。连续日志混有旧 run，结论仅来自上述新 run 的十个有序阶段。两次系统认证由主 agent 使用当前授权凭证，夹具不遍历系统凭据节点。
- **隔离与清理**：初轮 fixture UID10145/test10146；失败状态先用仅自身 UID 的只读元数据诊断 1项/0.035秒核清：owned marker/库存在，六个已知文件中 bak/new/journal 均缺失，包装缺失、persisted grants=0、精确快捷 alias 缺失、进程/认证窗口已结束，才卸载本轮 fixture 两包。修复后新 UID10147/test10148；成功后只读诊断 1项/0.036秒再次确认无未决文件/包装/授权。完整 E2E 已断言 own alias 清理，后一诊断未指定 alias，不冒称独立再次核验。主/测试包设备读回哈希与构建一致，随后仅卸载这两个自有包；未创建 SAF 外部文件。测试时主包 ca85 哈希保留。
- **本地质量**：最终源码冻结后 `:app:testReleaseUnitTest :app:lintRelease :app:assembleReleaseAndroidTest --offline --console=plain` 成功，106 JVM 实际重新执行，0 failure/error/skipped（XML 2026-10-12T06:28:11–27Z）；lint 0 errors/3既有 warnings，普通 instrumentation 编译通过。fixture main/test 编译及 lint 通过；Flutter analyze 无问题（2.9秒），diffcheck通过。Flutter 未改，本轮未重跑全量；最新154项为10-11主页任务结果，不能与本轮 JVM 合并成新全量执行。独立 reviewer 核对源码与红绿日志，无剩余源码发现。
- **主包交付及现有库**：`./preview.sh` 成功（assembleRelease16.7秒/32.2MB），v2验签通过、既有 signer `8d36c8be…`；主 APK SHA256 **`71d00e7cab8460f3bd93e87f6dc6e8ccf2a3a58b2245f73a149791c586b6bcef`**。`adb -s … install -r` 成功，安装后 base.apk 读回完全一致，生产 UID10131保留。通过正常工作台/秘密库设置：原库已锁定、快捷未开、后台30秒；重新输入用户提供的当前库口令并系统认证后显示“本设备快捷解锁已开启”，再从锁定状态选择快捷解锁并系统认证后显示“秘密库已解锁”。结束时点击立即锁定，最终库已锁定/快捷已开启。这里只以公开 UI 与正常管理结果为证据，不读取现有条目或私有密文，不冒称已有库内容逐字比对；未重建/导入/导出/改口令。正常主包启动恢复既有 Root 自动采集，未执行额外 Root 指令。

冻结源码 SHA256：AppAuthentication `337c3fdec4a7b8a12934218a8c16b1742c48de2caa379b3714619289ebccb739`，新增 SecretsQuickAuthDiagnosticsTest `53c1e050c7d42105897c37a897cec679fd582516e4a5c3ed736b74da99dd8d89`。修复 fixture main `437628efffcbe3cfc27f20386cc06967b1e28f1e3cd631b99465620a6b5c49d4`，test `5eb968245e9221b696452c0a1c9729da3b3bcfcfa6f958ebb5b38ffed197db6f`。证据 `/tmp/ctos-secure-board-20261012/`：`manual-auth-e2e.log`、`fixed-auth-e2e.log`、`fixed-auth-continuous.log`、两轮 `*-fixture-own-state*`、`production-public-ui-result.json`；本地日志 `/tmp/ctos-auth-aad-order-{normal-check,analyze,fixture-build,fixture-apk-check}.log` 与 `/tmp/ctos-auth-aad-current-release-{build,apk-check}.log`。临时证据不保证跨环境持久。系统认证安全截图未产出可用图像，不计新截图验收。

范围：**本板 API30 系统凭证分支**的真实成功快捷认证已实测；生物识别、API28/29、真实锁屏事件/进程死亡、非空混合恢复、权限撤销/云 provider 及 TOTP/SSH/native 业务联合验收仍开放。不关闭整个 BASE 或启动两个新业务 task，不提交/归档/替换 dist；后续按 [task check](../.trellis/tasks/10-08-app-authentication/check.md) 的剩余验收继续。

## 2026-10-11 退出提示移至下方

用户回复“实机验收过；但是能否把 prompt 出现在下方？”。上一轮交付的实机通过登记为**用户报告**；未提供设备/API/安装哈希，不冒称 agent 实测。当前直接实施位置调整，本轮无设备操作。

- **布局**：guard以builder提供主页Scaffold及稳定key提示槽，使用公共centerFloat布局，提示位于底部导航/系统安全区上方。普通floating Snackbar由Scaffold排到提示上方，无需猜导航高度或清理别的反馈。提示不可点击，保持主题/liveRegion/宽度上限；闲时为SizedBox.shrink，立即移除提示/朗读节点，避免FAB退出动画保留旧文字。四入口/2秒/返回重置逻辑保留。
- **检查**：实施15定向/153全量通过；独立检查补充实际Semantics无button/tap、点击不退出、超时即时清除，以及production rail24dp安全区后，最终 `./hako flutter test test/home_exit_guard_test.dart` **16项通过**，`./hako flutter test` **154项通过**，`./hako flutter analyze` 无问题（2.1秒），指定3文件format0变更/diffcheck通过。新semantics测试首次因测试handle释放晚于框架检查失败，改为测试内try/finally后通过；无源码缺陷。几何断言覆盖320/375/900横屏/1200、1/2倍文字、安全区24dp和普通多行Snackbar前后两种出现顺序，两提示不重叠，闲时槽尺寸零。
- **产物**：`./hako flutter build apk --release --target-platform android-arm64` 成功，Gradle26.2秒/32.2MB；hako内build-tools36 apksigner verify --verbose成功，v2=true/1signer。`build/app/outputs/flutter-apk/app-release.apk` SHA-256 **`981ef8145c01f1ce037f6ee5d80bd5672fe28e46fea591ac3d762fd48f9ef02a`**，已覆盖同路径旧7ad构建输出，未替换dist。日志 `/tmp/ctos-home-double-back-bottom-build-20261011.log`。
- **源码冻结**：main `25cdb690b428cbde61c771c95624df31c744047669b59b4f3e485cb51abe40c6`、guard `2b799533f6dbffdec70de692edd324cbe29eeb0b78635762ed0656056a271f86`、test `18d79a0584cdbf8c92b5e98febd1e37e1c02b296946e49bcf3331ea9466304a6`。root已核对最终源码、geometry/semantics回归、build日志、签名与哈希；持久任务记录见 [check](../.trellis/tasks/archive/2026-10/10-10-home-double-back-exit/check.md)。

Review classification为human-required；用户在最新下方提示交付后回复“可以提交当前 task”，审阅门槛已解除。原返回行为实机通过为用户报告，未提供设备/API/安装哈希；本轮不冒称agent设备实测，按授权提交/归档本任务，公共秘密库继续暂停。

提交前另将HEAD基线与本任务候选组成隔离快照，排除未提交公共秘密库功能/测试：hako内离线pub get、analyze无问题、119项Flutter测试通过，14秒。完整工作区154项与独立提交119项分别记录，证明此次提交不依赖暂停任务；日志 `/tmp/ctos-home-commit-isolated-check.log`。981 APK是整体工作区当时构建，含底座已有实现，不冒称隔离候选的APK。首次快照准备因git archive工作目录导致缺pubspec而未执行产品检查，修正tar生成后通过。

## 2026-10-11 主页双返回退出：本地实现与构建

用户要求暂停公共秘密库并开始本旁任务，确认“四个主入口，2 秒”。公共底座 metadata/mainline 记录暂停，保留其成果与未完成关卡；本轮仅本地实现、检查、构建，无设备连接或安装，不推定历史设备授权适用于本任务。

- **实现**：独立 Flutter guard / production ModalRoute observer；四入口首次返回提示、2 秒内再次调用 SystemNavigator.pop，计时/路由/弹窗/键盘/后台/入口切换重置及销毁解除观察。不主动 stop PTY/HFTP，标准 Activity 生命周期保持。CtosApp 的 MaterialApp build 与 Observatory() 预览构造保持兼容。
- **可见性复核**：初版底部提示与已有浮动 Snackbar 实际矩形重叠；新增断言先失败（expected false / actual true），移动到 body 顶部 AppBar 下方后通过。使用多行普通 Snackbar 检查不遮挡，局部主题短提示即时显示/精确移除，其他反馈不清理。
- **检查**：实施与独立 Trellis check 最终 `./hako flutter test test/home_exit_guard_test.dart` 均14项通过；最终 `./hako flutter test` 152项通过（实施11秒、复核12秒），`./hako flutter analyze` 无问题（复核2.5秒）。`./hako dart format --output=none --set-exit-if-changed lib/main.dart lib/ui/home_exit_guard.dart test/home_exit_guard_test.dart` 3文件0变更，`git diff --check` 通过。初轮两个测试非法生命周期跳转及旧预览入口兼容/protected 调用问题已修正；不计为最终通过。
- **覆盖范围**：真实 binding.handlePopRoute 派发与平台退出 mock 观察；1999/2000/2300ms、四入口实际 CtosApp、活动 App PTY/弹窗，详情/弹层/键盘 metrics/生命周期/observer 替换和销毁；320/375/900横屏/1200宽度及2倍文字布局。未将 Android IME/手势/桌面当作通过。
- **构建**：`./hako flutter build apk --release --target-platform android-arm64` 成功，Gradle31.3秒，Flutter报告32.2MB；依赖按既有版本解析，未升级。经 hako 的 build-tools36 apksigner verify --verbose 成功，v2=true / 1 signer。首次 sandbox 的 Docker socket 被拒后，任务范围 require_escalated 通过自动审查。

APK：`build/app/outputs/flutter-apk/app-release.apk`，SHA-256 `7ad103a9cf2684d79b9e582e30a8e7cb6819d29e2eac3a560e03fae9c2637407`。这是当前整体源码构建，包含暂停公共底座已有实现；不代表底座开放关卡已验收。构建日志 `/tmp/ctos-home-double-back-build-20261011.log`；临时产物可能被后续构建覆盖，持久结论为本节及 [task check](../.trellis/tasks/archive/2026-10/10-10-home-double-back-exit/check.md)。

本任务源码 SHA-256：main `31d63445968e98531f8edb4bea61c9c9838c5cd492cc3cc2f6cf4e43b226f09b`、guard `759c85b4487ff7b02de4340fd7d22fa084e76c467dabbb5cebdbe8f85bc7cf65`、test `87a88017a50c33a56295b26aa460fb23481732c60bb93873f242a629ba1b1c51`。

Review classification：**human-required**。真实 Android 硬件返回/返回手势、软键盘收起、桌面退出/重开和提示观感未实测；精确场景见 task check。任务保留 in_progress，待当轮授权设备验收或用户反馈；本轮未安装、替换 dist、提交或归档，也不恢复公共底座任务。

## 2026-10-10 继续任务：本地效果边界修复与手动认证夹具

用户要求继续已有任务，并明确“暂时没有，先继续本地工作”。本轮恢复 `10-08-app-authentication` 当前指针，只做本地检查/修复和下一平台关卡准备；没有连接设备、安装、操作真实账号或更改系统安全设置。

- **修复与回归**：两个新 JVM 测试在旧实现上均失败。typed manifest 写入最后store guard遗漏操作期限；SSH最后host-key gate使会话失效后仍向receiver释放秘密。现在最终写入检查 `clock.now() >= operation.deadline`，过期保留原密文/包装且一次请求不可重放；凭据/私钥clone后依次执行最后host gate、core `protectedEffect`、receiver，finally擦除。六个释放负例覆盖两路径各自锁定/取消/恰好到期。
- **实际本地结果**：`./hako bash -lc 'cd android && ./gradlew :app:testReleaseUnitTest :app:lintRelease --offline --console=plain'` 成功，41秒；**106 JVM**全通过，0 failure/error/skipped，XML 2026-10-10T09:46:11–37Z。`./hako flutter analyze` 无问题、`./hako flutter test` **138全量**重新执行通过。lint实际报告0 errors/3 warnings（QueryPermissionsNeeded/AndroidGradlePluginVersion/DataExtractionRules），历史5项不作为当前数字。opt-in `:app:lintSecretsFixtureRelease --offline --console=plain` 另外成功，12秒、0 errors/3 warnings，日志 `/tmp/ctos-secrets-check-fixture-lint.log`。
- **真实认证准备**：新增 `AppSecretsUiFlowTest#freshFixtureManualSecureQuickAuthenticationFlow`，显式 `ctosManualSecureQuickAuth=true`、已有安全锁屏、固定fixture包/独立UID/Application/Rootauto=false、fresh vault/journal（含bak/new）/marker/无自身旧alias前置约束。创建空合成库并暂不登记，再用当前主口令开启、真人系统认证登记、锁定后真人快捷解锁；真实owner会话密钥须认证未变内容，包装真实持久存在。关闭取消/错口令保护原状态，正确口令关闭后包装消失/会话锁定，仅读自身alias有界等待异步清理，最后验证主口令回退。系统等待90秒仅轮询自身包装/会话，不请求accessibility roots；手动场景关闭字段诊断，密码与公开runId无关联。失败不自动reset/删除自有库，需按现场证据核清后处理。
- **编译和产物**：最终 `./hako bash -lc 'cd android && ./gradlew -PctosSecretsUiFixture=true :app:assembleSecretsFixtureRelease :app:assembleSecretsFixtureReleaseAndroidTest --offline --console=plain'` 成功，12秒；正常 `:app:assembleReleaseAndroidTest --offline --console=plain` 编译成功，7秒。两个fixture包均v2同证书验签，aapt确认 `im.majo.ctos.secretsfixture` / `.test`，test instrumentation精确target、minSDK28/target36。只编译打包，**未执行手动测试，也没有一次成功认证结果**。未选opt-in的skip不计通过；明确opt-in但无安全锁屏会前置失败。

| 本地产物 | SHA-256 |
| --- | --- |
| `build/app/outputs/apk/secretsFixture/release/app-secretsFixture-release.apk` | `61c49cb4e1d89e9ed129dde1c7ad579faeeef62f1023238e30bce0956549c313` |
| `build/app/outputs/apk/androidTest/secretsFixture/release/app-secretsFixture-release-androidTest.apk` | `8be2cd331fdc5ea852010b494ad74da42803ad4c0c37bbf58eaae3021cea1a18` |

后续仅在已有安全锁屏设备获得当前安装/隔离验收授权，安装核准包并复核独立UID后，明确选择此单项；`SECURE_ADB_SERIAL` 为当轮核准目标，不从历史配置推导。人只在设备系统窗口完成两次认证；驱动不输入或获取锁屏秘密。以下命令**本轮未运行**：

```sh
adb -s "$SECURE_ADB_SERIAL" shell am instrument -w -r \
  -e class 'im.majo.ctos.AppSecretsUiFlowTest#freshFixtureManualSecureQuickAuthenticationFlow' \
  -e ctosManualSecureQuickAuth true \
  im.majo.ctos.secretsfixture.test/androidx.test.runner.AndroidJUnitRunner
```

源码冻结SHA：AppSecrets `652d69514652090c02a7d2e962cbf5d648086474918769262ca7c81e2d9d73ea`、SshVaultAdapter `3c942effa0d3ecaa9b919814694668b68d626a555fbf909dd01504264fe443cc`、VaultTest `9a721d1763243a0afa3144f97a9fa539adbfca071e5b9ccca7500a765a8d618d`、UI test `05b7a75941525eeb80424111c579e87fa93c1bd272b6c18421f5a738c4a93904`。主agent独立核对红灯、最终XML/lint/Flutter日志、源码、包SHA及验签/target；临时原始日志 `/tmp/ctos-secrets-check-{red,android,fixture-package,fixture-identity,fixture-test-manifest,androidtest-final}.log` 与 `/tmp/ctos-secrets-check-flutter-{analyze,full}.log`，持久结论为本节和 [task check](../.trellis/tasks/10-08-app-authentication/check.md)。

手动夹具限定空库，不证明非空业务、强生物专门分支、API28/29、真实锁屏/kill/跨设备恢复或SSH生产集成；成功系统认证等关卡继续开放。任务仍in_progress，未提交/归档或替换dist；生产release APK没有为本轮修复重建/安装，历史ca85仅覆盖旧源码和原场景。

## 2026-10-10 — APK 编译预览入口

- 新增 `./preview.sh`（默认或 `build`），复用 `hako` 的 arm64 release 编译；不安装设备、不替换 `dist/`。用户仅要求这个原生 APK 入口，Trellis Plus 服务生命周期、监听地址和环境文件增强不适用，其他增强未执行。
- `bash -n`、ShellCheck、`shfmt -d` 通过。`/tmp` 隔离桩覆盖默认/build 参数、其他工作目录、路径含空格、帮助无副作用、非法参数、缺少 wrapper/Docker、缺失/空 APK，以及构建失败且存在旧 APK 时不打印成功结果。
- 从 `/tmp` 使用脚本绝对路径实际执行，退出 0；Gradle 14.2 秒，末尾 APK 路径和 `adb install` 命令与文件一致。产物 `build/app/outputs/flutter-apk/app-release.apk`，32,209,925 字节，SHA-256 `14fc814678f005d4614a885b971a6060ba89c0058db4ff1cbe3259959c204850`；日志 `/tmp/ctos-preview-build-20261010.log`。`dist/ctos-current-arm64.apk` 及 `SHA256SUMS` 哈希保持不变。
- 沙箱首次执行因 Docker socket 访问受限以 126 退出，没有打印成功路径；随后仅对该编译命令按工具权限机制重试并成功。没有运行 ADB 或重新执行产品验收；构建不改变秘密库任务仍在进行的状态。
- 该 APK 来自包含尚未提交秘密库实现的当前工作区；此处哈希不作为本次仅提交编译入口后独立 checkout 的产物保证。

## 2026-10-10 配置 B 追加：Bridge 生命周期与独立 UID UI 探针

修复 `AppSecretsBridge` 关闭后后台/文件选择器回调向停用 executor 提交、重复关闭和旧 `onStop` 影响新上下文的问题；恢复/关闭撤销自身定时器，关闭进入非前台并锁库。独立审查另发现 0 秒后台锁定后旧预览未及时清理，现总在 worker 清理失效预览/操作，仅取消旧 epoch 请求，不误取消本就从锁定状态开始的有效管理请求。生产仍使用唯一 Owner/原设置/Journal 与既有快捷密钥维护；包内四依赖构造只用于注入独立验收数据。

本轮最终独立UID实际release Flutter/Main/Bridge空库完整E2E **1项通过，235.79秒，0失败/跳过**。测试包 `83e6302e5a2e50a118e1edd99000f8c9800566a7bcaed6365e9d27b6831af2c3`、Java `9e731e466acc3437544ee6db98f3a98b36813737006e017b89a8365c1692563a`，fixture main仍f358/UID10142、testUID10143；编译10秒/v2同证书/设备读回一致。run `aa14c7a8-5274-4db3-87a6-d67af20fa38c` 的连续原始TAG覆盖16:58:59–17:02:54全部阶段：创建/取消/手动锁/错误及正确口令/改口令；真实系统exact叶目录授权、新导出、同名取消保留旧字节、明确覆盖并仅一个final；错误来源与目标口令拒绝、取消预览保留目标密文；确认后两侧完整有效manifest认证相同、新UUID/gen1/nonce/material及无wrapper、锁定、旧目标口令拒绝与来源Latest实际UI解锁。最后精确备份/空UUID叶目录删除和treegrant回收的测试终态均通过。原始证据 `/tmp/ctos-config-b-20261010/secrets-ui-import-generation-e2e-3f7c6a55-d0f6-459c-a580-29d7ffd02df3`；不把以下历史失败合成为通过，也不外推非空/mixed/真实认证。

终态后独立genericreadonly **0.175秒**确认own库/marker已知存在、journal各恢复变体不存在/persisted0，旧测试进程退出；shell只核精确备份与叶目录均不存在、ctos parent保留、fixture grant投影空。仅卸载本轮fresh fixture两包，实际Success且pm path/包列表均空（此板不存在包的pm path退出1，按stdout/列表核对，未误作测试失败或重复卸载）。生产APK设备读回仍ca85/UID10131，主会话查看最终设置图，公开尚未创建、快捷不可用未开、后台30保持；正常Main原Root/alias维护作用按报告边界记录。最终14项源码SHA（fixture5/export4/真实SAF3/Bridge2）保持，未替换dist/提交/归档。持久 [完整UI报告](../.trellis/tasks/10-08-app-authentication/research/flutter-ui-config-b.md) 与以下历史阶段分列。

- **本地**：修复后实际执行104 JVM，XML 2026-10-10T06:07:32–49Z，0 failure/error/skipped；最后测试归属 guard 改动复用未变 JVM 结果。lint0 errors/5既有warnings；Flutter analyze及35定向通过，未重跑此前未变138全量；arm64 release/测试APK编译及v2同证书通过。
- **产物**：主包 `ca85ec00dd7eb737454d4380e03fc6c6ae45343e3faac9fc68ab77b372116f3e`、测试包 `577d54687631df40bc40b8d70ca08944aa7c83a2fb7f3177910365d2556a1757` 在 B 安装读回一致。固定副本与日志 `/tmp/ctos-lifecycle-final.9RFwueDn/`，此前候选 d448/7bc 未安装。
- **实机6项通过，53.408秒，无失败/跳过**：真 HOME/返回的宽限与期限、两 typed consumer、手动锁/迟到解锁一次回调、关闭/旧 timer/旧 onStop、真实排队 late picker、独立 core 重建只恢复设置，以及0秒真后台后预览 key/plain/seed 擦除与 fresh locked 请求反例。直接旧回调/重建和主队列排序注入另标，不称真实 SCREEN_OFF、进程死亡或生产 SSH 语义。
- **主 App 状态/清理**：公开库前后 missing、快捷未启用且不可用、后台30秒；所有 own worker 退出后 target UID 精确删除6套自有文件/偏好成功。Shell stat 私有路径被拒，不能当独立不存在回读，没有用 su 绕过。测试包已卸载、临时XML精确删除、相关URI grant投影为空，主ca85保留。正常 Main 启动 `root_auto=true/root_active=true` 且有模块alias维护；没有新su授权操作，不称整轮Root/Keystore-free。完整 [生命周期报告](../.trellis/tasks/10-08-app-authentication/research/bridge-lifecycle-config-b.md)。
- **独立 UI 探针1项通过，4.123秒**：显式 opt-in `ctosSecretsUiFixture=true` 构建固定独立包 `im.majo.ctos.secretsfixture`/`.test`，实际UID10142/10143，生产10131。fixture Application确认自身包后仅对自己的 Root 自动启动设置同步关闭；测试 manifest 不包含旧固定authority/授予生产包的组件。主fixture `f3582423561734c1bbaa5f709cdc323cf4f159ef0fc1251d259b786da898cd83`、probe test `894938650e20bc22cb5f85f91ca8b98e584c8ebc01d895c712eb80b8b65556bd` v2/安装读回一致。真实导航及取消成功，两字段SET_TEXT返回成功；后续诊断发现未聚焦时该返回可仅更新Android预测缓存，因此探针不证明Dart控制器收到输入。fixture库与journal前后不存在，未打开文件picker；两fixture包暂保留供本轮后续验证。完整 [UI报告](../.trellis/tasks/10-08-app-authentication/research/flutter-ui-config-b.md)。
- **首轮完整 UI 尝试失败**：仅更新fixture测试包为 `974b2cd59cdb131ce03959ed8ccfa5b586fe52eeea45c61eec71f2c12551be01`，单项E2E实跑13.018秒在首个“两次口令不一致”提示检查失败；没有创建成功stage、没有打开文件picker。公开fixture仍未创建，但私有journal需由同UID只读诊断确认，不由公开UI或shell权限拒绝推断不存在。保留自有marker及两包，未清空以丢失证据；正在修正测试字段/按钮/提示定位，不能由此声称生产校验存在缺陷。
- 修正前的失败逐版本记录：c4c2未聚焦字段调用无广告SET_TEXT，13.948秒失败；a0d1聚焦后真实mismatch/创建密文认证/取消保留通过，在重试焦点缓存处19.637秒失败；37af创建/取消通过，21.337秒在锁定可见状态检查失败；d99键盘遮挡与集合类名限制14.959秒失败；2e5真实创建后因lazy顶部锚点脱树，36.201秒未找到创建回执。均未进SAF。按实际Dart/Android SDK来源只修测试驱动；targetUID只读诊断及exact授权/旧PID证据单列，仅在明确归属及无未决journal/grant后重置本轮自建fixture，不计为产品验收。
- **完整管理1项通过，29.272秒，无失败/跳过**：测试包 `a7c73fee1c1631add2752b995571680278ee2419ac173b738a906811d6641b81` 编译10秒/v2同证书/安装读回一致，mainfixture f358及UID10142/10143未变。当前驱动按准确标签重新聚焦且检查广告输入动作，业务控件用自身精确CLICK并绑定activewindow；实际垂直集合有明确metadata，固定AppBar标题在lazy子项脱树后仍能定位唯一容器，已撤掉可选键盘BACK。真实mismatch/创建及原口令KDF/GCM、管理取消cipher完整保留、手动锁/错误口令/正确重试、改口令新口令成功与旧口令AUTH拒绝均通过。证据 `/tmp/ctos-config-b-20261010/secrets-ui-toolbar-management-0b589436-324a-4369-afac-473007513930`，管理项未调用SAF；后续完整导出/恢复另跑。
- 完整E2E后续失败另记：a7c7在最终输入动作异步重建处20.619秒失败，c6a7首次picker导航33.437秒失败且核对调用者后取消。实际安装provider primary-emulated标题规则复核后，65a2只读公开primary映射PASS0.184秒；当前root标题不是由Build.MODEL或长度猜测。c3ac按active DocumentsUI窗口重新冻结/安装，E2E60.233秒在新建叶目录后的定位处失败：exact primary→Download→ctos已过，未到完整consent/Allow；可能已创建空自有leaf，保持现场待exact资源核对及测试驱动修正，不重置或猜名删除。完整导出/恢复尚未通过，逐版包/SHA/原始证据见UI报告。
- c3ac现场证实系统已自动进入新目录，末级breadcrumb不可CLICK；经逐步核对调用者取消picker、精确空叶目录rmdir后absent，genericreadonly0.167/0.163分别核对清理前后资源，不计产品测试。下一测试包 `01998a2a750c4868a6065a4fb3781fc47ff2c520cd70627aa4dd710b80b86ca7` 仅修正create后的有界完整路径等待，编译9秒/v2同证书/安装读回一致。完整E2E实际FAIL150.174秒：管理、真实新导出、同名取消保留旧完整密文、明确覆盖并核只有一个final均通过，随后Destination改口令认证通过；首次import在exact文件名选择处失败，尚未错误来源口令校验。保留本次backup/leaf/grant并核对系统控件及MIME，不重置/卸载或猜名删除；整体SAF恢复仍未通过。
- b964独立只读actions诊断PASS0.346秒，证实exact文件标题到第6层原生item_root才有广告CLICK，旧driver仅检查0–5；正常MIME为octet-stream。019旧picker按逐步caller/path证明取消，只有本次已验116-byte文件经公共格式头/mtime/设备SHA双检后精确删除，空leaf rmdir后不存在；genericreadonly0.148确认无未决journal/grant，保留ctos parent。一次清理初稿因额外保存完整加密备份被自动审批整条拒绝且未执行；收窄到公开header内存校验/设备端SHA后完成，没有完整payload传输或留档。最终8709/a156测试驱动补齐精确row身份/刷新/父链/边界/广告CLICK，编译10秒/v2及源码审阅通过，单完整E2E已放行；尚不预标恢复通过。
- 8709实际E2E FAIL227.504秒，完整连续原始TAG记录管理/三个导出阶段/错误来源口令与错误目标口令分别拒绝/取消预览密文保持。确认恢复后真实UI返回导入成功，测试却误要求与来源备份密文逐字节相同；失败byte32为新UUID起点。真实 `AppSecrets.importCommit` 生成新local UUID/generation1/nonce，沿用已认证来源salt/key/plaintext并去设备wrapper，因此成功应比较完整有效解密manifest及新材料，不能直接复制cipherbytes。旧目标口令拒绝/来源UI解锁/终态cleanup当版尚未执行，不能合成整项通过。readonly0.119确认无journal/grant；唯一自有备份按新run/116bytes/mtime/公开header/设备SHA双核后精确删除、空leaf rmdir，postreadonly0.154 settled。83e/9e仅修成功oracle，保留失败/取消字节保护，编译10秒/v2及源码审阅通过，已放行单完整E2E，尚不预标通过。
- 成功快捷认证、API28/29、真实进程死亡/权限撤销/云provider与SSH业务仍开放。下节17平台/真实provider六阶段分别对应原包，不能混称本轮新包全部重测。

## 2026-10-10 配置 B 追加：真实目录与路径标识复用

在下节初轮验证后，继续补真实 ExternalStorageProvider 的隔离事务检查。源码复核发现路径型 document ID 的合法复用会被 journal 拒绝，且删除新 final 后恢复旧路径、或已验证 final 的旧文件清理成功后仍留有过期角色引用；已修复生产 `VaultExportJournal`/`VaultExportTransaction`，保留对当前对象角色冲突的拒绝。只有记录了 distinct rollback/重命名阶段和一致名字/元数据才承认历史 original ID 被新 final 复用。确认对象已不存在后先持久确认角色移除，再恢复旧名；未确认则保留证据/不还原到该路径。已验证 final 始终保留。

- 本地组合 `:app:testReleaseUnitTest :app:lintRelease :app:assembleReleaseAndroidTest` 退出 0、36 秒，XML 2026-10-10T04:30:42–56Z：**104 JVM**（35 Vault/7 Session/3 Writer/16 Bundle/17 Restore/26 ExportTransaction），0 failure/error/skipped；lint 0 errors/5 既有 warnings。新增 3 项路径 ID/日志确认故障 JVM 与 4 项 Journal instrumentation 回归，实际设备执行另记。未变 Flutter 仍引用此前 analyze/35 定向/138 全量结果。
- 新版 arm64 release 构建通过，15.3 秒、32.2 MB；主 APK **`a309bd9d46393af5dacc6eced11577f36f85ce60911cffcabc5f016e1db3dd9f`**，平台组合测试 APK **`cb829f963d8cc42235829a85277dfdcdbdcab2ae8cd6834c9a938fb473f3427f`**，两包 v2 签名核验、配置 B 安装读回一致。证书仍 `8d36c8be418174cb4cda0b897ff3e32b2da6f84032819726e8e5671872df52ef`。本轮没有替换 dist。
- 测试专属 picker 仅接受 `com.android.externalstorage.documents` 的本轮新建 `primary:Download/ctos/ctos-saf-<UUID>`；错误选择在 query/grant/I/O 前拒绝。ResultReceiver/nonce 与 120 秒 lease 保持 helper 到清理结束，再撤销固定目标包的 exact tree 临时 grant；不取得持久授权。测试核心使用独立 UUID 私有 VaultStore/Journal，不启动 MainActivity/生产 Owner/Bridge/AppAuth/Keystore。
- **配置 B 17 项组合通过，32.066 秒，无失败/跳过**：Store4、Journal9、fixture adapter1、Platform3。新增 Journal 角色/历史 ID/恢复回归已实跑；它们的恢复 provider 为受控 fixture，不称真正进程死亡/断电。
- **真实 ExternalStorageProvider 1 项通过，59.838 秒**，包含首次新建、拒绝覆盖、确认覆盖、取消预览、stage 中段写入失败和 final 中段写入失败六阶段。每次真实私有 AtomicFile journal save 都 fresh load 校对，密文完整读回并实际 GCM 认证/0与1 typed TOTP 条目核对；`path_id_reused=true` 实际确认历史路径 ID 复用。两种 partial IOException 是测试 wrapper 在真实写入中段主动注入，不声称实遇磁盘故障。旧内容恢复及本次部分清理均通过，不按名字或前缀猜身份。
- 真实首跑在跨 UID ResultReceiver 中反序列化测试匿名类时崩溃，未进入导出，外部叶目录为空；测试专属回调修为 framework base Parcelable，增加 target UID exact tree 撤权、已知路径 marker 及有界元数据诊断。修订测试 APK **`b7c90f2b700a47b0ea0de39ca092fa07832677c3716435d7aed7a3e87c403021`** 编译/lint/v2 验签、安装读回一致，承载真实六阶段通过；主 APK/17 项无需重复。独立 preflight1 撤权通过，0.055 秒，不混入产品验收计数。
- **最终清理完成**：正常真实测试的私有库/Journal/marker 清理及 helper release ACK 通过，exact tree grant 投影为空、外部叶目录为空且已 rmdir；只保留本轮新建空 `/sdcard/Download/ctos` parent，临时 XML 已删除。首跑唯一 217-byte 私有加密缓存按唯一 producer/执行时间/canonical 路径/固定结构确认归属；专属清理包 **`c4e8170ed0e053fefa3e5a82efef94015f25e7ae1ded47b3775923fbcf3a9cc5`** 编译/lint/v2 签名及安装读回一致，cleanup1 通过、0.06 秒，删除前再次严格核对唯一文件/大小/mtime/公共头/零 wrapper，不读取密文；仅 exact file/空目录删除并回读。诊断入口与六阶段方法分离，不重复17/真实1、不把两项诊断计为产品验收。
- 测试包已卸载、`pm path` 为空，fixture authority 与 exact external tree 权限投影均为空；主 `a309bd9d…` 保留，生产库未创建或修改、后台设置 30 秒保持，没有真实账号/系统安全配置操作。持久完整结果见 [设备记录](../.trellis/tasks/10-08-app-authentication/research/device-config-b-validation.md)。
- 此项证明 native 事务与当前真实本机 provider 六阶段，不是生产 Flutter 导出口令全流程；生产库保持 missing。已有锁屏设备的成功快捷认证、API 28/29、真实权限撤销中断/进程死亡/云 provider、完整生产会话及 SSH 业务/native 接入仍开放，不据 17+1 通过关闭全部 BASE。
- 原始本地日志 `/tmp/ctos-real-saf-{journal-final,release-build,apk-signatures}.log`，源码核对 `/tmp/ctos-path-id-source-sha256.txt` 和 `/tmp/ctos-real-saf-source-sha256.txt`。初轮 13 项及 `6c0f00e6…` 包按下节历史范围解释。

## 2026-10-10 配置 B：当前授权设备验收

用户在本次安装/隔离验收请求后指定外部配置文件的“配置 B”。当前唯一 `BOARD_ADB_SERIAL=192.168.9.14:5555`，已连接并两次核对为 T-CHIP AIO-3568J / Android 11 API 30 / arm64-v8a / SELinux Permissive。授权仅当前 APK/测试包安装及合成数据/owned-file 验收，不覆盖现有用户库、操作真实账号或修改系统安全配置；没有使用其他在线设备或配置 B 的服务器。

- 主 APK **`6c0f00e61f7ad90bcd2e31b675027e619987cd191ec302c1c589946d55d8739b`** 覆盖安装成功，设备 base.apk 读回哈希一致；生产源码/APK 本轮未改。最终测试 APK **`ac95d226a6c3a123fb5181c853e0de3c0b166216ee703d3f1ad71cae2908b744`** 重编译、v2 签名核验、安装及读回哈希一致。
- 第一轮 Store4/Journal5 通过、SAF adapter 冷启动授权失败。修复仅限测试夹具：固定 bootstrap Activity 在 provider 自身 UID 初始化；instrumentation 使用 targetContext，避免包名/Binder UID 不匹配。保留 MANAGE_DOCUMENTS 保护，仅固定 fixture 根/目标包获得临时 read/write/PREFIX grant；没有新增生产权限。
- **最终 13 项实机测试全通过，30.211 秒，无失败/跳过**：4 VaultStore、5 Journal、1 真 ContentResolver/隔离 DocumentsProvider、3 平台 Window/认证不可用/Android 加密管理。BC/Argon2id/GCM 的合成 TOTP、错误口令、导出、改口令、恢复和 0/1 秒超时在独立 MemoryStore 验证；磁盘 AtomicFile 由 Store 夹具单独验证，不能称完整 KDF 加磁盘崩溃恢复联合验收。单次 create 2035 ms、管理组合 22856 ms，仅为此板样本。
- **实际界面隐私**：创建口令页只输入合成内容、未提交；敏感页两次 screencap 无可用 PNG，最近任务 ctOS 卡片为空。敏感录屏 7 帧 App 区域均黑，系统普通 IME 可见；取消后普通截图恢复，普通滚动录屏 54 帧可读。系统导入 picker 实际打开/取消，未选中或读取文件；后台 ctOS 窗口保持 SECURE，移除敏感路由后恢复普通窗口。结果仅覆盖此 API 30/ROM 与实际路径。
- **设备认证边界**：实测 `Keyguard.isDeviceSecure=false`，快捷路径在 worker/store/认证 key 操作前返回不可用，未跳过；没有设置临时 PIN/生物登记。真实凭证/强生物成功 CryptoObject、Keystore 登记/解封/撤销/失效、API 28/29 仍待已有安全锁屏设备验证。
- 上述“认证 key 操作前返回”仅指独立不可用实例；真实 MainActivity 正常启动会初始化生产 bridge、读取状态，并排入既有 `cleanupDisabledKeys()`，可能枚举和回收模块孤立 alias。未据此断言本轮删除了 alias，也不把整套 UI/instrumentation 称为无 Keystore 操作。
- **清理与未完成项**：测试 APK 已卸载，fixture grant 回收，仅精确删除本轮四个视频和一个 UI XML；主 App 保留，公开库状态前后“尚未创建”、后台 30 秒，未更改生产库或偏好、未创建外部备份。真实存储 SAF 的覆盖/取消/权限撤销/进程死亡、生产解锁会话完整生命周期及正式 SSH validator/reviewer/native/双方业务联合验收仍开放；隔离 provider 不替代真实存储 provider。
- 主 agent 独立核对最终日志/包哈希并查看敏感录屏、普通对照及最终设置图片。仅测试源码重编译/lint 通过，0 errors/5 既有 warnings；未变生产 JVM/Flutter 不重复。原始证据 `/tmp/ctos-config-b-20261010/`，持久详情为 [设备验收记录](../.trellis/tasks/10-08-app-authentication/research/device-config-b-validation.md)。以下“未安装/未授权”是此前阶段历史；任务保持 in_progress，未替换 dist、提交或归档。

## 2026-10-10 公共文件图与导出/恢复审阅：最终本地检查

本轮用户回复“继续吧”，并明确文件导出应提示同名覆盖、失败删除仅本次创建的部分/保护原文件。公共 bundle、导出事务、恢复审阅及 Flutter 联动已实现并完成最终独立源码/本地检查；下方阶段性数字和上一版 APK 按历史解释。本节是配置 B 授权前的本地冻结结果，随后设备结果见上节。

- **最终检查**：Android 组合命令退出 0、1 分钟，XML timestamp 2026-10-10T03:25:43–57Z：**101 JVM**（35 Vault/7 Session/3 Writer/16 Bundle/17 Restore/23 ExportTransaction）全部通过，零 failure/error/skipped；lint **0 errors / 5 既有 warnings**。4 VaultStore + 5 VaultExportJournal + 1 SafExportProvider 隔离 instrumentation 夹具编译通过，共 **10 项**，没有设备执行。Flutter analyze 无问题、**35 定向 / 138 全量**测试通过。主 agent 独立读 XML/log/lint，13 项 UI/导出源码签名核对一致。
- **导出结果**：同名需明确确认；新 stage/final 写入关闭及完整密文读回，旧对象从不以写流打开；失败精确补偿，已验证 final 后旧删除失败保留 final。journal 的 silent finalize/readback/finish 后失败分支有真实 AtomicFile 故障注入夹具，但仅编译。恢复 ownership/version/digest 不足则保留未知位置，不能猜 ID/按前缀删除。取消/超时/session 变更/close、系统 recovery picker 取消与 exact cached receipt 保留真实残留；坏 journal 只阻止导出/恢复，不阻止普通库使用。120 秒限制提交权限，不能保证任意阻塞 provider 的 I/O/补偿按时完成。
- **界面**：实际 CJK/Material 字体渲染检查 1 项通过；320 dp/2 倍文字与键盘、同名确认/取消、成功但残留、重新选择目录恢复、完整来源/目标恢复审阅可滚动到达。主 agent 与独立 reviewer 实看图片，信任状态中文化、opaque credential binding 不展示为正文。图片位于忽略目录 `.devhome/secrets-ui-visual/`；这不是目标 ROM 屏幕保护实测。
- **最新产物**：`./hako flutter build apk --release --target-platform android-arm64` 构建成功，32.5 秒、32.2 MB；`build/app/outputs/flutter-apk/app-release.apk` SHA-256 **`6c0f00e61f7ad90bcd2e31b675027e619987cd191ec302c1c589946d55d8739b`**。`apksigner verify --verbose --print-certs` 核验 v2 签名通过，开发证书 SHA-256 `8d36c8be418174cb4cda0b897ff3e32b2da6f84032819726e8e5671872df52ef`。测试 APK SHA-256 `5517df88df8ba054c069d7092770ca246c42f2df9cdd3ee294f62fdc80efd16d`。未安装、未替换 dist、未提交/归档。
- **证据与剩余范围**：临时日志 `/tmp/ctos-export-{analyze,focused,full,visual,android-final}.log`、源码清单 `/tmp/ctos-export-source-sha256.txt`；持久结果为本节、[task check](../.trellis/tasks/10-08-app-authentication/check.md)、测试源及 [导出研究](../.trellis/tasks/10-08-app-authentication/research/export-transaction-plan.md)。最终 review 无遗留必须修复项。当前真实 SAF/provider 授权/覆盖/中断恢复、API 28/29 与 30+ Keystore、安全窗口/最近任务及 KDF 性能未验；生产 SSH validator/reviewer/native 缺位仍拒绝相关导入，不以 synthetic tests 标 BASE 或联合业务验收通过。

- `SshBundleProposal`/typed `SSH_BUNDLE_WRITE` 已补共同原子新增/替换/删除：完整对象 CAS、exact approval 默认拒绝、候选图/正式 validator 必须存在（即便删最后 SSH 对象）、最终 beforeCommit 权限/期限/open 复核、保留无关数据及包装材料。16 项新测试使用明确 synthetic reviewer/validator，证明公共合同，不证明生产 OpenSSH 配置/信任/native 恢复。
- `./hako bash -lc 'cd android && ./gradlew :app:testReleaseUnitTest :app:lintRelease :app:assembleReleaseAndroidTest --console=plain'` 冻结 bundle 版退出 0、25 秒；主 agent 核对 XML timestamp 2026-10-10T02:48:17–20Z：**61 项 JVM 通过**（35 Vault/7 Session/3 Export/16 Bundle）零失败/错误/跳过；lint 0 errors、5 既有 warnings、instrumentation APK 编译通过。独立 review 无必须修复残项；临时日志 `/tmp/ctos-host-bundle-frozen-check.log`。此后导出/恢复接口继续变化，最终全范围结果待冻结另记。
- [验收边界调查](../.trellis/tasks/10-08-app-authentication/research/base-acceptance-boundary.md) 识别底座与完整 SSH 相互等待，文档按公共合同/独立平台、客户端接入后的联合证据分层；所有完整目标保留，未宣布 BASE-09 通过或自动授权设备/业务。
- 用户导出决定对应的 [事务方案](../.trellis/tasks/10-08-app-authentication/research/export-transaction-plan.md) 采用 create-new final，禁止直接截断旧 URI；角色明确的 stage/final/confirmed old/rollback 和成功后的旧清理分阶段。provider 拒绝清理/恢复或效果不明会显示位置及恢复状态，不能称已清理。以下为实施期间证据，最终本地检查见上，未做真实 provider 测试。
- 恢复审阅核心已暂冻结并独立源码审查：来源/已认证目标任一含 SSH 都需完整对象 coverage、provider revision、exact native approval 和同一 reviewBinding；纯 TOTP 覆盖目标 SSH 也要求审阅。公开 credential binding 使用本次随机 HMAC opaque token，避免暴露口令猜测验证器；provider loan 用后擦除。中间 JVM 退出 0、31 秒，XML 2026-10-10T03:05:14–28Z 的 **91 项**全部通过（新增 17 Restore/13 初稿 ExportTransaction），主 agent 核对日志和 XML。导出与 UI 随后继续修改，这不是最终 APK 检查；synthetic reviewer 只验证公共编排，生产 SSH 语义/信任提供者仍缺位并拒绝相关导入。
- 发现 TOTP 来源覆盖现有 SSH 也会移除目标信任，正在补 source/target 审阅快照与绑定，生产缺正式 reviewer/validator 的 mixed 路径仍失败关闭。没有读取个人配置/账号/设备，未安装、提交或归档。

## 2026-10-10 撤销验证码剪贴板清理

用户要求 ctOS 提供 TOTP，验证码清理由用户自理。Android/Flutter 删除清理时长设置、协议/status/prefs 字段、复制 ID/跟踪预留及合成竞态探针；主动复制/敏感标记保留，无读取/监听/清空或清理定时器，后台库超时不变。旧 ClipboardRaceTest 与下面第一版 46 项/包哈希是变更前历史，本次最新检查/构建结果在本节记录。

- Android 最终 `:app:testReleaseUnitTest :app:lintRelease :app:assembleReleaseAndroidTest` 退出 0、45 秒；主 agent 再核对 XML timestamp 2026-10-10T02:27:13Z：**45 项 JVM 通过**（35 VaultTest、7 Session、3 Export），0 failure/error/skipped；被撤销功能的 ClipboardRaceTest 源/报告已移除。lint **0 错误、5 既有警告**，4 项恢复 instrumentation 夹具只编译通过。临时日志 `/tmp/ctos-host-clipboard-removal-check.log`。
- Flutter 最终 analyze 无问题，**28 项定向 / 131 项全量通过**，保留管理取消/回执/安全路由/会话检查。设置只提交 `backgroundTimeoutSeconds`；验证 0、正数/超限、保存失败保留草稿，大字后台超时标签用完整外置标签避免输入框截断。主 agent 核对临时日志 `/tmp/ctos-secrets-no-clipboard-{analyze,focused,full}.log`，均成功。
- 活动代码定向搜索确认无 clipboard timeout/status/prefs/copy ID 及 get/listener/clear/timer；只有用户主动复制路径的 ClipboardManager `setPrimaryClip`/敏感标记。本轮未访问真实设备剪贴板或增加权限/后台服务，其他平台/SAF/生产 SSH 未完成关卡保留。
- 27 Markdown 本地目标与两相关 task 的 JSON/JSONL 验证通过；`git diff --check` 通过。旧清理规划在 changelog/check 中按历史保留，最新活动契约统一为用户自理。
- 最终实际 CJK/Material 渲染 1 项通过，320 dp/1 倍与 2 倍文字的设置 top/timeout/backup 图片共 6 张；实现与独立检查代理实际查看，外置完整后台标签和保存按钮可达，无剪贴板设置。临时图在 `.devhome/secrets-ui-visual/settings-{top,timeout,backup}[-large]-320.png`；不作为设备或人工观感验收。
- 最终冻结版 `./hako flutter build apk --release --target-platform android-arm64` 成功（30.8 秒、32.1 MB）；`build/app/outputs/flutter-apk/app-release.apk` SHA-256 **`72a2dd5513ccb085e031a64130d90384214d2413b5fabd719d9417231297e1d7`**。`apksigner verify --verbose --print-certs` 退出 0、v2 签名通过，开发证书 SHA-256 `8d36c8be418174cb4cda0b897ff3e32b2da6f84032819726e8e5671872df52ef`。测试 APK SHA-256 仍为 `d8e400f9f9c4604ee6e4a34eec66d59fc906677f678d04f9047d7dfc861f5fc9`。未安装或替换 dist，未提交/归档；BASE-09/10 及真实平台验收继续保留。

## 2026-10-10 公共秘密库第一版：本地检查与研究（清理撤销前）

用户批准最新规划后，当前实施入口为 `10-08-app-authentication`，保持 in_progress。以下为本地源码/算法/研究结果，本轮没有连接或安装设备，没有账号登录或读取个人 SSH/剪贴板。

- **最终 Android 宿主**：现有 `./hako` 容器执行 `:app:testReleaseUnitTest :app:lintRelease :app:assembleReleaseAndroidTest` 退出 0、39 秒。BC 1.86；主 agent 再读 XML：VaultTest 35、VaultSessionTest 7、VaultExportWriterTest 3、ClipboardRaceTest 1，**46 项全通过，0 skipped/failures/errors**。Android lint **0 错误、5 条既有警告**，4 项 VaultStore 隔离文件 instrumentation 夹具仅编译通过、没有设备运行。包括最后增加的提交阶段取消/超时临界保护及实际 core after-guard/after-persist 交错，报告在 `build/app/{test-results/testReleaseUnitTest,reports/lint-results-release.xml}`；临时日志 `/tmp/ctos-host-final-check.log`。前一轮 42 项为修复前历史。
- **最终 Flutter**：`./hako flutter analyze` 无问题；28 项秘密库定向、`./hako flutter test --reporter expanded` **131 项全通过**，主 agent 核对最终日志末行。包括取消后等待宿主完成/不确定结果及 acknowledgement、导入传递不确定、错口令重试、安全路由移除，以及 320 dp/2 倍文字/240 px 键盘 inset 下回执确认按钮可达且至少 48 dp。临时日志 `/tmp/ctos-secrets-review-flutter.log`。前一轮 24/127 为修复前历史。
- **最终可审阅 APK**：冻结后的 `./hako flutter build apk --release --target-platform android-arm64` 成功（30.2 秒、32.1 MB），文件 `build/app/outputs/flutter-apk/app-release.apk`，SHA-256 **`36d3985d3b2cc6f32688fc62afb04a82375dce45dfe710c8288a249479bef3bb`**。`apksigner verify --verbose --print-certs` 退出 0、v2 签名通过，开发证书 SHA-256 `8d36c8be418174cb4cda0b897ff3e32b2da6f84032819726e8e5671872df52ef`。测试 APK SHA-256 `d8e400f9f9c4604ee6e4a34eec66d59fc906677f678d04f9047d7dfc861f5fc9`。未替换 `dist/ctos-current-arm64.apk`、未安装；这些 build 目录是可重建的忽略产物。
- **真实 Flutter 渲染**：忽略目录 `.devhome/secrets-ui-visual/` 的 7 张 320 dp CJK/Material 图片覆盖设置上部/时间/备份、改口令/键盘、导入/信任；另生成 2 张 320 dp/2 倍文字/键盘的最终不确定回执 top/return 图片。两项独立渲染测试通过，检查代理实际查看可读性和下部动作，主 agent 再看回执返回图，按钮可达、正文竖向滚动且不横向裁切；这是本地 widget 渲染，不是安全窗口/设备/人工观感验收。
- **固定 Linux native 研究**：主 agent 独立运行 `research/ssh-memory-run.py`，官方 OpenSSH 10.0p2 archive SHA-256 `021a2e709a0edf4250b1256bd5a9e500411a90dddabea830ed59cef90eb9d85c`，实际 **6 PASS**。覆盖真实 config/Include/glob、stock FD 继承反例、私钥/known_hosts 文件形状、ProxyJump exec 临时视图、owned 取消回收及缺失/遍历/撤销读写。持久 [研究报告](../.trellis/tasks/10-08-app-authentication/research/ssh-memory-results.md) 和原始 `ssh-memory-validation.log` 记来源/哈希/边界。只用合成秘密/loopback，不落普通磁盘明文；不证明 Android/Bionic/16 KiB、完整登录、每跳受限释放或正式生产 native 集成。
- **历史剪贴板核对**：Android 公开 `clearPrimaryClip()` 没有身份条件参数；read/check 与 clear 两次调用之间仍可能被其他复制替换。当时 ClipboardRaceTest 用合成数据复现反例，未访问真实设备剪贴板，第一版仅敏感标记/ID、无自动清理。随后用户撤销清理，旧探针源及设置/ID 已移除；不把它列当前阻塞或功能验证。
- **SAF 不确定**：真实导出 writer 的 JVM provider 反例表明，完整认证标签写出后 flush 失败仍可留下有效加密包。返回 `VAULT_EXPORT_UNCERTAIN`，不能标记“失败无可用备份”通过。原子库提交无法判定时 `VAULT_COMMIT_UNCERTAIN` 锁库读回恢复；真实 provider/AtomicFile 设备分支仍待验证。

生产 SSH 语义校验器没有接入，混合 SSH 包导入明确失败关闭，不把测试注入的 validator 当生产验证。真实 API 28/29 与 30+ Keystore、强生物/系统凭证、敏感截图/录屏/最近任务、SAF、崩溃恢复、性能和完整两业务接入均未验收。详细条款与当前关卡见 [task check](../.trellis/tasks/10-08-app-authentication/check.md)；不提交/归档、不沿用旧任务设备授权。

## 2026-10-06 两处显示文案精简

按用户要求不建 task，测试并提交用户在 `lib/main.dart` 的两处修改：顶部移除 `SYSTEM OBSERVATORY`，概览标签由 `DEVICE / 设备观测` 简化为 `DEVICE`。

- 使用既有 `./hako`：Flutter 3.35.7 / Dart 3.9.2。
- `./hako flutter analyze` 无问题；`./hako flutter test --reporter expanded` **103 项全部通过**，包含概览 320/375/800/1200 dp、双倍文字和减少动画的现有组件检查。
- `./hako dart format --output=none --set-exit-if-changed lib/main.dart` 通过，0 文件需格式化；`git diff --check` 通过。
- 初次提交只验证源码，未重建 APK；随后用户明确要求 build，基于提交 `3e0833e` 执行 `./hako current`：arm64 release 构建成功（约 26.3 MB），签名校验通过，并原子更新 `dist/ctos-current-arm64.apk` 与 `dist/SHA256SUMS`，校验和检查通过。当前 APK SHA-256：**`b7e67386f6b0058f0898320f654aaea856669ded78fc0432c516409fb76d9d7b`**。
- 本轮未安装到设备、未执行设备操作或截图视觉验收。上次实机结论仍只对应其原包。

## 2026-10-05 全 Flutter 品质优化：最终代码与视觉复验

用户批准整个 Flutter 实施和配置 A 验证，设备短暂离线后回复“已恢复，继续”。本轮最终包 `dist/ctos-current-arm64.apk` SHA-256 **`37d226009771ef8711196d91542f9b2c8b95d600ec238c35bf84709b97843c01`**；证书 SHA-256 `8d36c8be418174cb4cda0b897ff3e32b2da6f84032819726e8e5671872df52ef` 与旧安装一致。`adb install -r` 成功，最终设备 base.apk 哈希一致；旧包备份后覆盖，保留 App 数据。

- **本地最终检查**：`./hako flutter analyze` 无问题；`./hako flutter test --reporter expanded` **103 项全部通过**；`./hako current` arm64 release 构建、签名与 `dist/SHA256SUMS` 校验通过。日志 `build/flutter-polish-evidence/{analyze-final,test-final,release-final2}.log`。Dart 源码由实现/检查 agent 格式化，最终 diff 另查；没有 native/Python 或依赖变更，未重复运行其他层全量测试。
- **真实 Flutter 渲染**：`./hako flutter test build/flutter-polish-evidence/preview_test.dart --reporter expanded` **1 项通过**，107 场景、127 张矩阵图片，错误数组均为空。范围为 320×568、375×812、900×450、1200×900 dp，正常/2 倍文字与减少动画；八个核心画面、九项工具表单/结果、五种 HFTP 状态、文件/历史/导出/终端输出、受限/失败/取消/超时及三种清理弹窗，含下方滚动内容。当前 catalogue、公开响应夹具和显式 CJK/Roboto/monospace/MaterialIcons；22 文件源清单最终哈希匹配，不用 HTML 代替 Flutter。
- **视觉迭代**：完整页面审查后收紧概览/目录/设备字段密度、降低历史/日志权重、提高必要输入边界对比；全矩阵复渲染后再修复原始时间戳、结果短字段、大字 IP 标签与横屏清理影响说明。Trellis check 及主 agent 最终图片复验未发现已证实的明显残余项。八维发现/修复/复验见 [任务检查](../.trellis/tasks/archive/2026-10/10-05-flutter-experience-polish/check.md)。不将测试绿灯单独当作视觉验收。
- **IP 补充复验**：长矩阵一张 IP 结果图的首数字未绘出；独立挂载真实组件，在主页/详情路由 400 ms 和稳定帧均完整显示三个 `198.51.100.8`，文本与首字符位置断言通过。原图保留限制，有效复验图 `build/flutter-polish-evidence/ip-probe-true-settled.png`；未据此改产品代码。
- **配置 A 当前设备**：Android 15 / API 35、Xiaomi 22041216UC、1080×2460 px、当前 378 dpi、文字缩放 1.0。每条 ADB 显式指定当前目标，serial 不提交。此次结论仅属于最终包及该手机，不沿用历史开发板/Root/仪器测试结论。
- **实机已完成路径**：概览/设备/网络/接口/连接/工作台入口；密码工具使用公开测试种子，本机运行完成、16 字符结果默认遮罩、复制进入 Android 剪贴板、历史页可返回。App Shell 普通键盘输入 `id` 返回 App 身份，`pyth` 经 Tab 补为 `python3`，历史键恢复 `id`；输出快照搜索 `uid` 找到 1 处、复制全部、返回保持原会话；最终关闭本轮 PTY。键盘出现时底栏让位，控件与输入保持可达。`whoam` 没有可补命令，未误判为 Tab 故障。
- **导出/HFTP 与最终页面**：导出未选择时主按钮禁用；选择网络快照后实际打开 Android SAF 保存界面，返回显示“文件选择已取消，未创建文件”，未创建外部文件。HFTP 实屏显示已停止、启动主动作、既有局域网/7888/32 MiB 配置与目录选择；只读查看，不启动/变更服务。最终重采概览/设备并回到概览，安装 hash 对应全部这些截图。19.76 秒本地录屏及逐秒接触表确认页面切换与实际数据刷新，无持续闪烁；抽样不代表逐帧性能或所有路由动效验收。
- **状态/动效边界**：减少动画、失败/受限/停止竞态/取消和清理效果以确定性 widget 测试/渲染验证；实机交互证明关键动作可执行，不声称量化帧率。Root 自动恢复属于既有启动路径，未改 Root 授权、其他应用、网络、系统字体/旋转配置。此轮未重测 Root PTY、服务后台/熄屏/LAN 传输或全部 ROM；既有 HFTP 熄屏限制仍有效。

原始 APK 备份、截图及控件树在 `/tmp/ctos-flutter-polish-20261005/device/`，含设备信息，仅本地临时保留、不提交。公开组件证据在忽略的 `build/flutter-polish-evidence/`；若临时目录被清除，需按当前授权重新生成。提交前归类 `human-required` 的观感审阅已于 2026-10-06 通过：用户回复“观感通过，按计划提交”。自动化、代理图片审查与设备交互结论仍按上述实际范围解释。

## 2026-10-05 全 Flutter 品质优化：规划与改版前基线

以下是批准前历史：用户当时仅同意创建任务/规划，产品代码未改动。后续批准与最终结果见本文件上方，设计及矩阵见 [全 Flutter 界面品质优化](flutter-experience.md)。

- `./hako flutter --version`：Flutter 3.35.7 / Dart 3.9.2；`./hako flutter test`：当前源码 **79 项全部通过**。未运行改版后检查，也未重建/替换 APK。
- 配置 A 当前在线目标的只读探测确认 Android 15、1080×2460、当前 378 dpi。打开已安装 `im.majo.ctos` 并截图，显示旧 Vector/旧导航；这是旧包视觉参考，不能作为当前源码功能验收。启动运行了该旧包的既有生命周期，未执行终端命令或调整 Root/系统配置。
- 当前仓库 APK SHA-256 `ab13ceac96a7a8958659cb9bb12ab298dd65d9fa22b3ec729d2f5d26e30e014f`。此次未覆盖安装、未清数据、未修改网络/其他应用。
- 原始截图 `/tmp/ctos-flutter-polish-20261005/baseline/overview.png` 为临时证据，不提交；若目录被清理，需重新确认设备并重采。目标 serial 按既有脱敏约定不写入项目文档。
- Trellis implement/check 上下文校验通过。视觉、宽度矩阵、减少动画、完整新包设备验收均待实施，79 项基线不能代替这些结果。
- 规划补充：复用已检查的 `build/workbench-usability-previews/preview_test.dart`，执行 `./hako flutter test build/workbench-usability-previews/preview_test.dart --reporter expanded`，**1 项通过**；生成并逐张查看六张当前源码组件基线图。采用公开模拟数据、旧目录元数据和显式字体，不是设备/真实执行证据；HFTP 图缺少配置响应，只能表示未就绪布局。夹具哈希、图片和具体发现见 [基线检查](../.trellis/tasks/archive/2026-10/10-05-flutter-experience-polish/check.md)。

## 2026-09-28 界面与终端可用性整理（P2）

本 task 在用户另行授权后，向 `TARGET-BOARD`（AIO-3568J、Android 11 / API 30）安装 release 包 `im.majo.ctos`。本地产物 `dist/ctos-current-arm64.apk` SHA-256 `ab13ceac96a7a8958659cb9bb12ab298dd65d9fa22b3ec729d2f5d26e30e014f` 与安装前核验值一致，`adb install -r` 返回 Success；未卸载既有包或清除 App 数据。ADB serial 依本文件的证据脱敏约定不记录。

- `./hako dart format` 完成；`./hako flutter analyze` 无问题。定向测试 `test/terminal_interaction_test.dart test/state_ui_test.dart` **12 项通过**；全量 `./hako flutter test` **79 项通过**。`./hako current` arm64 release 构建和签名校验成功，`dist/SHA256SUMS` 校验通过。
- 启动 ctOS 时运行了既有 Root 自动恢复流程，App 报告 Root 在线；没有单独读取 `auto_start` 偏好值。
- 实机 App Shell 输入框可唤起 Android 普通软键盘。快捷栏折叠后五个按钮从 UI 控件树移除，重新展开后恢复。执行只读 `id` 返回非 Root 应用身份；执行 `sleep 60` 后使用快捷栏 Ctrl-C，终端出现 `^C` 并回到 `$` 提示符。
- 从当前终端打开输出选择页，搜索 `sleep` 显示 **找到 2 处**并高亮两处，点击“复制全部输出”显示**已复制全部输出**。返回后原 App Shell 的历史输出与提示符仍在，PTY 未重启。
- 用户另行授权 Root PTY 的只读身份检查，输出 `uid=0(root)`。随后关闭 Root PTY；后续复核用 App Shell 会话也已关闭。未运行 Root instrumentation tests，没有改系统/VPN 配置、清除 App 数据或重启。
- 屏幕空闲进入休眠时，截图会显示时钟而非应用；触屏唤醒后前台 Activity 和完整终端输出恢复。没有修改设备休眠设置。截图与 UI hierarchy dump 仅用于本轮即时检查，没有作为项目产物保留。

## 2026-09-28 只读任务闭环验收

在用户指定并授权的 `TARGET-BOARD`（Android 11 / API 30、arm64-v8a）完成本任务验收。通过 `adb install -r` 覆盖安装 release APK 和匹配的 release test APK，保留原 App 数据；设备上两个 APK 的哈希分别与本地产物 `56b3ca3c580c5df97fdd5d5502782f2fad90bd53e89730ef38209947e82963d2` 和 `aee3475d38d2948128c488fb3fd3934bcaeb0be84df2776d4acd504bbe00239f` 一致。

- `./hako dart format` 覆盖本轮修改的 Dart 源码和测试；`./hako flutter analyze` 无问题，`./hako flutter test` **76/76 通过**。Python fake Context 边界检查通过：接口状态、不可用计数器、非法或消失接口保持各自语义。
- Android `:app:lintRelease`、`:app:compileReleaseAndroidTestJavaWithJavac`、`:app:assembleRelease` 和 `:app:assembleReleaseAndroidTest` 均 **BUILD SUCCESSFUL**。
- 指定仪器测试通过：`TaskHistoryStoreTest` **OK (3 tests)**，覆盖 allowlist、文件重载和清理、20 条/5 MiB 淘汰、失败/取消/超时状态及损坏恢复；仅运行 `DeviceTest#interfaceDiagnosisUsesExactFreshAppSnapshotAndCanBeStored`，**OK (1 test)**，确认使用新鲜 App 快照、只传单接口投影并持久化结果。没有运行其他 DeviceTest、Root 或 PTY 测试。
- 手动界面检查：Root 列表中一个接口不在新鲜 App 快照内，按预期得到失败并保留失败状态；另一个 App 可见接口诊断成功，来源显示为 App。执行 `am force-stop` 并重新启动后，历史页仍显示上述成功与失败两条记录。
- 导出预览中仅勾选成功的历史记录，网络和设备快照未勾选。通过 SAF 保存到唯一测试名后读回 JSON，顶层仅含 `exportedAt` 和 `taskHistory`，其中恰有一条记录且没有 `networkSnapshot` / `deviceSnapshot`。按返回键取消另一唯一测试名的保存，目标文件不存在；随后在历史页确认清除两条记录，页面显示空状态。已从 Downloads 精确删除本轮创建的导出文件；取消路径始终未创建。
- 按用户授权启动 ctOS，运行既有启动时 Root 自动恢复流程；没有运行 Root 测试。未修改系统或 VPN 配置，未清除 App 数据。临时截图和回读 JSON 验收后已从 `/tmp` 删除，未作为项目产物提交。

`git diff --check` 与 `python3 ./.trellis/scripts/task.py validate 09-25-read-only-task-loop` 通过。设备标识按本项目脱敏约定记为 `TARGET-BOARD`。

## 2026-09-28 Firefly 开发板当前包定向验收

`adb devices -l` 本轮仅列出 `BOARD_ADB_SERIAL`，设备自报 AIO-3568J、Android 11 / API 30、arm64-v8a。显式指定该序列号读取 `pm path`、`dumpsys package` 和已安装 `base.apk` 的 SHA-256；ctOS 已安装，但安装时间为 2026-09-24，包哈希为 `22ab383fbaf02024b59f68ef958224a9215625aca58a87b6dd23b724511c2bcd`。本地 `dist/ctos-current-arm64.apk` 为 26,193,940 bytes、SHA-256 `9ca71e316525e5b5ba6a771f855f3df2d3b52342eb75162594e7972c3518c2e3`，与板上包不同。现有测试 APK 哈希为 `3cc68b0823d1cbde047484ab0cb088b82e5f2dde7ac048cbb6714dcb10319be1`。

经用户明确授权，仅向这块板 `adb -s "$BOARD_ADB_SERIAL" install -r` 覆盖安装上述当前 APK 和测试 APK，保留 App 数据；两次安装均为 `Success`，设备上两个 `base.apk` 的 SHA-256 分别与本地产物 `9ca71e31…` 和 `3cc68b08…` 完全一致。安装前 ctOS 服务列表为空；设备为 SELinux Permissive。

指定七项非 Root 仪器测试一次合跑 **OK (7 tests), 30.17 s**：Python 3.13.9/SDK 目录、自检与未知脚本拒绝，运行中取消/超时及后台任务回收，App 网络快照，文件输出不覆盖、加解密认证篡改拒绝与编码产物，HFTP 在 `127.0.0.1` 随机端口的后台通知、上传/下载、限额/不覆盖/路径拒绝及显式停止。测试自有文件由测试 `finally` 清理；结束后 `dumpsys activity services im.majo.ctos` 为空。安装与测试均未清除 App 数据、改系统配置或开启 LAN 服务。

本轮没有运行需要 Root 授权的采集或 Root PTY 测试，也未做 SAF 手操、真实 IP 查询、熄屏/LAN 连通、通知拒绝、API 28/29 或原生 16 KiB 页验证。结论仅覆盖当前包在这块 Android 11 板上的上述七项。具体目标地址以本轮确认的 `BOARD_ADB_SERIAL` 为准，不能把项目文档中的默认地址当作当前连接状态。

2026-09-28 提交决定：用户已在当前实测结果和已知熄屏限制基础上要求提交，并要求本轮内容脱敏。脱敏复核与工作提交已完成，当前专项任务按收敛范围归档并记录 journal；无新增设备操作或 Root 电源授权。下文各阶段“未提交/待测”保留为历史状态，以首项最终记录为当前结果。

## 证据脱敏约定

`TARGET-PHONE` / `TARGET-BOARD` 表示当次确认的设备；ADB 序列号、设备产品标识、局域网地址、接口名、App UID 与 owned PID 均以角色占位符替代。PHONE / WINDOWS / TEST-CLIENT 地址仍表示同一局域网中的不同对象；文档中的 TEST-NET 地址仅为已脱敏示例，不表示重新执行的真实网络。已安装产物哈希、Android/API、权限条件、传输大小、时间与成功/失败结论保留。

重放命令前需设置 `PROJECT_ROOT`（项目绝对路径）、`EVIDENCE_DIR`（独立临时证据目录）及当前授权的 `PHONE_ADB_SERIAL` / `BOARD_ADB_SERIAL`；`${PHONE_DOWNLOAD_DIR}` 表示授权设备的测试下载目录。证据文件名用于关联历史产物，不保证临时文件仍存在；需重建夹具后才能重放，不能将占位符直接视为可达地址。

2026-09-28 提交脱敏只修改文档/任务信息和两处测试地址字面量，生产源码未改：Java 的非法监听地址与 Dart 的 mock 日志/期望改用 TEST-NET。下方已安装 APK、测试 APK 和实机结果属于此前实际产物，不宣称脱敏后的测试 APK 已安装或重复了手机验收。

## 2026-09-27 HFTP 停止错误追加回归（进行中）

2026-09-28最终记录：当前9ca71e…APK安装哈希一致、14份Python/native来源核对、73项Flutter/analyze、Androidlint0errors/5既有warnings通过；修正后十项TARGET-PHONE定向检查9.077s全部通过。三轮LAN PUT201/GET200精确（两轮1,376,257B、一轮31MiB）、真实App停止/进程端口CPU锁回收及同7888重启通过，清空日志后31MiB再读回/返回重进正常。默认私有目录/LAN7888/32MiB/Roottrue恢复，五个owned文件及空隔离目录精确删除，服务停止，VPN lockdown1/Clash bypassablefalse保持。**熄屏仍拒绝连接**：登记App锁不等同于有效保活，ROM记录REL/后续lightIdle；候选的具体退出日志因锁屏未取得，不写成已直接证明的Root退出原因。Root临时CPU租约候选另见任务design，用户已选择暂不扩大Root范围，候选未实现/未触发，熄屏问题作为已知限制保留；暂不提交/归档。完整实测与初次测试误报保留在[实机记录](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/restart-device-check.md)。

同设备ADB已改为PHONE-ADB-SERIAL。最终Service修复候选5d4e5c…安装哈希一致，七项定向手机测试通过（7.135s）；第一Root LAN1,376,257B精确读写/停止/三个owned PID与端口回收/7888重启通过。第二轮上传201但下载读回超时；同文件醒屏后两种读取方式均精确成功。屏幕关闭后GET拒绝，Root日志heartbeat_timeout；追加控制队列/期限竞态与closed原因诊断回归，新版休眠及连续流程待测。见[追加实机记录](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/restart-device-check.md)。

控制队列修复本地24轮精确传输/重启、queuedPING存活与9项控制/独占检查、严格host/NDK和Androidlint/testAPK通过，独立复核通过。168cedec…安装哈希一致、亮屏GET精确且closed reason=complete；熄屏仍两次拒绝/heartbeat_timeout，实际前台服务已确认。正在追加Root adapter App CPU锁R25，不能把上述局部通过写成最终验收。参见[传输复核](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/transfer-review.md)。

用户确认点击 App「停止」后立即报错。TARGET-PHONE 当前无存活 HFTP Service/owned 子进程；重新进入页面后保留六条日志显示 18:46:52 一次 Root 启动在 Python ready 后 `listener_bind (errno 98)`，随后精确关闭。该证据不能证明用户停止操作主动重启了服务。原页面日志/config 区有灰色 ErrorWidget，ctOS PID OWNED-PID 每两秒报 Flutter DiagnosticsProperty 异常，首次栈已不在缓冲区；同 PID 返回再进入恢复日志。追加 R21–R23 回归正在处理，不将此前单周期通过视作本次通过。

本地 native 已复现真实 TCP TIME_WAIT 后同端口重启 errno98；checked SO_REUSEADDR 后 20 轮实际传输/STOP/即时重启通过，活跃监听器仍拒绝第二 owner，原15项回归/严格 NDK 编译通过。旧无 reuse 版本遗留 TIME_WAIT 的复用边界另记，未改系统 TCP/VPN/路由；新最终包实机连续重启待测。见[本地记录](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/restart-native-check.md)。

## 2026-09-27 HFTP Root分离与日志最终验收

记录：[Root实机检查](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/root-device-check.md)、[全范围审查](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/root-log-check.md)。用户明确授权Root网络中继，仅TARGET-PHONE `PHONE-ADB-SERIAL`、Android16。

- 最终APK SHA256 `0575e00d5d7fb732d02ec4923563e4dfd68e0008eae17480791e753ad29eaf1b`，26,189,072 bytes；构建/签名/校验和及安装base.apk逐字节一致。14份Python源码与packaged relay来源核对通过。
- Flutter68/68、analyze无问题；Python25/25；Android lint0 errors/5既有warnings/testAPK通过；native15项本机网络夹具+最终3项定向复验/NDK API28严格编译及16KiB ELF对齐通过。Browser loopback九项复验通过。
- 六项手机定向检查最终通过，含能力缺失/未知/不匹配效果前拒绝、App UID执行helper被EPERM拒绝、日志脱敏/上限/会话、普通App服务后台/停止。首次日志断言误匹配序列化JSON，改查实际字符串后单项复验通过；App包未改。
- 保持VPN不可bypass、always_on_vpn_lockdown=1：WindowsWINDOWS-LAN-IP用户确认可以打开，日志记录其GET/200；LANTEST-CLIENT-LAN-IP实际33MiB上传201/下载200/哈希一致，409不覆盖/413限额/后台读回通过。Python UID APP-UID，Root helper UID0；Root仅网络，文件/SAF仍App权限。
- UI清空后不停止且继续写日志；停止后保留。owned Python/su代理/helper三个PID均退出，LAN端口ConnectionRefused。默认私有目录/LAN7888/32MiB/Root关闭及停止状态恢复，三个公开夹具与空隔离目录精确清理；没有读取/清理原有共享库，没有改变VPN/路由/防火墙/SELinux。
- 运行截图`${EVIDENCE_DIR}/ctos-feedback-hftp-root-running-20260927.png`、停止/恢复截图见详细记录；这些是临时证据，设备检查结果已摘要保存在本项目。

物理无Root设备完整流程、其他ROM/Android11、真实16KiB页、Wi-Fi真实切换/丢失和五小时持续运行未测，不从mock/QEMU/同一手机推定。页面产品审阅/提交仍待确认，任务保留in_progress；先前d43f52ed…版本LAN超时为历史结果，本版已实测修复指定Root模式。

## 2026-09-27 九张图片反馈：最终源码与 TARGET-PHONE

最终 `d43f52edb9f90b083079579ef6b14cf99ce5dccd70c132a858ddb6f276288e4c` APK（26,135,519 bytes）构建/签名/发布校验通过，覆盖安装 TARGET-PHONE `PHONE-ADB-SERIAL` 后 base.apk 哈希一致；APK 内14份 Python 源码逐字节匹配。没有转换成 Java：工具与HTTP仍为Python，Java承担Android权限/生命周期/SAF流式访问。详见 [反馈源码复核](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/feedback-check.md)、[反馈实机记录](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/feedback-device-check.md)。

- analyze 无问题、Flutter **52/52**、隔离 Python **21/21**、Android lint **0 errors/5既有warnings**、测试APK编译、隔离loopback浏览器9项通过。检查服务协议、CLI认证兼容、大文件/短写/中断/无覆盖/路径边界，以及UI准确参数、宽度、普通IME配置及过期轮询保护。
- 最终手机公开seed输入圆点遮挡、EditorInfo **inputType=0x1**、原有普通键盘生效；没有调整系统键盘。首次中间包 false suggestions 被引擎转为VISIBLE_PASSWORD，已修为 enableSuggestions=true；普通键盘可能显示建议。
- 原生最终4项组合 **OK (4 tests),1.687s**，含真实Downloads授权、配置/路径、请求owner保护与loopback前台通知/后台/上传/停止；另1项 **OK (1 test),0.009s** 确认ExternalStorage授权。通过系统picker选择自建隔离目录，不猜路径或申请全文件/Root权限。
- UI LAN/7888/64MiB启动后，经本任务ADB转发 **33MiB 上传201、下载200、34,603,008 bytes SHA完全一致**；同名409后内容不变，超过64MiB返回413；子目录建立/上传、切后台后34B读回正确。ExternalStorage能读回原ADB文件；Downloads MediaStore视图不显示未索引的原ADB文件，服务遵守provider可见文档边界。
- **跨设备LAN未通过**：电脑直连3秒超时；只读路由为LAN-INTERFACE/源TEST-CLIENT-LAN-IP，手机shell访问自己的LAN地址200，ADB转发也可用，证明HTTP监听/目录传输正常。外部端口受阻的具体原因尚未确认，没有改VPN/防火墙，也没有把免登录视为原Firefox8080故障的确定修复。
- 服务已停止（services为空）、本任务forward移除；恢复LAN/7888/32MiB及默认私有目录，精确删除自建外部文件/空目录，不清理旧共享库。中间选择失败后曾短暂启动默认私有目录服务，发现后立即停止，主会话未从该次服务列出/读取私有数据。

本任务保留in_progress与未提交状态；跨设备LAN及用户视觉判断尚待复核，不用局部通过替代全部验收。Android11/API28、其他ROM、TalkBack与原生16KiB页未测。截图、命令和日志见任务记录；下方98f44069及更早结果仍按各自版本解释。

## 2026-09-27 工作台新版 TARGET-PHONE 定向验收

用户在具体 APK 与安装/临时文件验收请求后回复“允许”。重新确认 TARGET-PHONE / Android16、`PHONE-ADB-SERIAL` 后覆盖安装成功，实际安装哈希与 **`98f44069c9fc6da80d082dd591b161cb9c1d43b34e88d522879af75e6148bab7`** 完全一致。数据保留，既有 Root 自动会话恢复，没有手动授权或改配置。详见 [手机验收记录](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/device-check.md)。

- 五项工具在首屏完整可见，其他四项及文件管理可达。密码中文基础/高级表单、长度数字键盘、折叠盐值参与计算通过；主结果和展开原始 JSON 默认遮挡，直接复制后粘贴核对为密码本身。
- 编码文本/文件切换保留草稿，文件为空阻止运行，实际系统选择器取消后保持空选择。公开20B文本 `ctos-ui-saf-20260927` 的 Base64 预览正确；唯一临时产物通过 SAF 导出读回，**28 bytes 逐字节正确**，重新导入后名称/大小显示正确。
- 文件哈希匹配编码28B内容；保留文件切回文本后，哈希匹配原20B文本，旧 token 不覆盖活动来源。哈希方向隐藏、逐项结果与复制控件正常。横屏、键盘滚动、加解密空表单和 HFTP 停止页未见布局问题；未启动 HFTP 或执行加解密。
- 已恢复原旋转 `lock 0`，回到工作台，精确删除外部测试文件及设备/宿主 UI dump；未清理共享库或既有文件。App 管理的28B产物和28B导入副本保留在工具临时存储，没有整库清理或 Root 私有文件操作。

实机截图在 `${EVIDENCE_DIR}/ctos-usability-*-20260927.png`（确切名单见手机记录），公开产物读回在 `${EVIDENCE_DIR}/ctos-usability-saf-export-20260927.txt`。仅此定向验收通过；TalkBack、服务后台/通知、IP在线/离线、其他ROM/Android11/原生16KiB页未在新版重测，大字号/减少动画仍为组件证据。本轮无源码修复，不重复构建或测试；下方本地检查适用同一 APK。源码未提交/任务未归档，实际截图供用户产品审阅。

## 2026-09-27 工作台体验优化：源码、本地预览与最终 APK

用户批准 TARGET-PHONE 界面审阅的六项建议并要求规划/实现。九项入口、中文与分层参数、编码来源投影、专用结果和 HFTP 状态分区已实现；未改 Python、SDK、原生或依赖。详见 [实现证据](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/implementation-check.md)、[复核](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/check.md) 和 [构建/审阅计划](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/build-review.md)。

- `./hako flutter analyze` 无问题；`./hako flutter test --reporter expanded` **43/43** 通过，含 12 项新增体验测试。覆盖准确参数/枚举、隐藏值、文本/文件草稿、文件必选、目标复制/完整 JSON 与原 token 导出、密码及转义日志遮挡、HFTP 显式启动停止/退出页面/清理确认、375dp/横屏/2倍字号/减少动画。日志在 `${EVIDENCE_DIR}/ctos-workbench-{analyze,full-tests}.log`，属本机临时证据。
- `./hako bash -lc 'cd android && ./gradlew :app:lintRelease --console=plain'` 成功，**0 errors / 5 既有 warnings**；报告 `build/app/reports/lint-results-release.{txt,xml}`。
- `./hako flutter test build/workbench-usability-previews/preview_test.dart --reporter expanded` **1/1** 通过。主会话检查列表、密码/编码表单、密码/哈希结果及停止状态 HFTP 六张预览，无裁切或文字溢出。预览为 375×812dp、3倍像素的 Flutter 固定公开数据组件，模拟 native channel，保留项目主题；测试字体补齐 CJK、Roboto、图标及等宽字形。它们不是真机截图，不建立执行/文件/服务结果。临时产物在忽略目录 `build/workbench-usability-previews/`，包含 `list.png`、`password-form.png`、`encoder-form.png`、`password-result.png`、`hash-result.png`、`hftp-stopped.png`。
- `./hako current` 成功，APK 签名校验、发布副本比对及 SHA256SUMS 检查通过。新版 `dist/ctos-current-arm64.apk` 为 **26,107,591 bytes**，SHA-256 **`98f44069c9fc6da80d082dd591b161cb9c1d43b34e88d522879af75e6148bab7`**。APK 内 13 份 Python 源码与当前源码逐字节一致。旧包临时备份 `${EVIDENCE_DIR}/ctos-workbench-baseline-f928af66.apk` 保持 `f928af66…`。
- 最终文档本地链接检查与 `git diff --check` 通过。审阅分类 `human-required`：新版视觉、键盘、真实 SAF 和辅助功能仍需设备验收，源码暂未提交/任务未归档。

构建阶段先使用 TARGET-PHONE `PHONE-ADB-SERIAL` 查看既有包界面，没有运行工具或启动 HFTP；只读取得 Noto CJK 字体供预览。当时新版未安装，原报告要求本任务安装授权；后续用户授权及新版定向设备结果见上方，不借用历史验收。其他 ROM、Android 11 和原生 16KiB 页仍未验证。

## 2026-09-27 当前进度提交检查

本轮按用户要求确认 ADB 连接并提交 ctOS 当前进度，没有重新安装、运行设备测试、申请权限或启动文件服务。

- `adb connect "$BOARD_ADB_SERIAL"` 返回 `No route to host`，默认开发板本轮未连接成功。
- `adb devices -l` 显示 `PHONE-ADB-SERIAL` 在线，product/model 为 TARGET-PHONE、device 为 PHONE-PRODUCT-ID；显式指定该 serial 的 `get-state` 和 `getprop` 确认为 device / TARGET-PHONE / Android 16。
- 现有 current APK 的 SHA-256 仍为 `f928af6611f014206597321b2f35a094aef53b11eeb404ea7bbf72e23ee8a73b`，其 13 份 Python 源码与当前工作树逐字节一致。此检查不代表重新构建或本轮设备安装哈希验证。
- 本轮 `./hako flutter analyze` 无问题，`./hako flutter test` 31/31 通过；`./hako bash -lc 'cd android && ./gradlew :app:lintRelease --console=plain'` 构建成功，0 error / 5 个既有 warning。`hako-env.sh` 的 Bash 语法、ShellCheck、shfmt 检查及 13 份 Python 源码语法解析通过。
- 根 README、design 和 spec 的本地 Markdown 链接检查无缺失目标，`git diff --check` 通过。本地代码复核与检查结果见 [提交检查](../.trellis/tasks/archive/2026-09/09-26-portable-tools/commit-check-2026-09-27.md)。

下方 2026-09-26 的安装、SAF、通知、Root、PTY 及网络结果保留为历史实测；本轮不扩展设备验收范围。任务状态保留，未归档。

## 2026-09-26 Portable 五项工具：最终 APK 与 TARGET-PHONE 验收

当前 APK `dist/ctos-current-arm64.apk`，26,106,495 bytes，SHA-256 `f928af6611f014206597321b2f35a094aef53b11eeb404ea7bbf72e23ee8a73b`，签名与 SHA256SUMS 通过，设备安装哈希一致。测试 APK SHA-256 `be024d92e5cc2a1d76c9d46ff8cf970dc04bb086304c2a2d4c57e2c59344c9c6`。用户明确授权本轮 APK/测试 APK 覆盖安装、通知授权及本机 HFTP/App与Root Python 验收；目标仅 TARGET-PHONE `PHONE-ADB-SERIAL` / Android16 / 页大小4096 / SELinux Enforcing。

- Flutter analyze 无问题，31/31 测试通过；Android lint 0 error / 5 个既有 warning，最终测试 APK 编译通过。五项集成113项、编码核心142项、CLI12次调用、真实 Chromium HFTP上传/下载/特殊目录/不覆盖/CSP/375px均通过。依赖及构建环境仅 APK、项目 .devhome 和 ${EVIDENCE_DIR}。
- 手机首轮 HFTP 上传400，新增 App Python 测试确认硬链接 EACCES。改为 SDK/CLI/HFTP 共用 `renameat2(RENAME_NOREPLACE)` 原子提交，已有文件/符号链接拒绝覆盖，失败清理临时文件；16个并发写入只有一个成功。不更改 Root 授权或 SELinux。
- 最终指定七项组合 **OK (7 tests), 6.392 s**：加密/转换及源文件保留、认证篡改拒绝、终端文件输出不覆盖、HFTP后台通知/认证/上传下载/路径拒绝/停止监听、SDK目录及未知脚本拒绝、App与Root python3、运行中取消/超时/后台取消及重跑。先前合并运行的 Activity 等待超时已通过测试 CLEAR_TASK 隔离修正，失败日志仍保留。最终日志 `device-seven-api-final-results.txt`。
- 真机 SAF：公开文本产物导出读回正确，重新选择该文件显示私有导入副本，取消选择及取消系统选择器后为空 token。HFTP 实际页面启动 loopback，切桌面通知保持；点通知“停止”后通知消失，服务列表为空。精确清理本轮三个私有测试文件、唯一 SAF 文件及临时 UI dump；未清空 App 或删除既有目录/文档。
- 13个实际 APK Python 源文件逐字节匹配；最终 APK 解包后 ARM64 QEMU通过 SDK2/九项/原子不覆盖/AES-GCM，新动态库LOAD对齐16KiB。QEMU linkerconfig/tzdata提示仍属模拟环境，不能推定原生16KiB设备通过。

- 09 原脚本接口已迁移，按官方文档改为 `https://free.freeipapi.com/api/v1/json`。宿主和实际手机工作台明确指定公共示例1.1.1.1的查询均通过，返回source/ipAddress正确；没有查询自身公网IP。固定目标/8秒超时/离线错误继续有mock证据。

详细命令、失败→修复证据与临时日志见 [父 task 检查](../.trellis/tasks/archive/2026-09/09-26-portable-tools/check.md)。Android11、原生16KiB页设备、API28/29 syscall分支、全新通知拒绝及真实离线设备路径未验证。实现及本轮验收完成，保留 WIP 待审阅/提交，不自动归档。下方 Python工作台/Root-only 及末尾安装前预检均为历史阶段记录。

## 2026-09-26 Portable Python 工作台：构建与 TARGET-PHONE 验收

本轮 current APK：`dist/ctos-current-arm64.apk`，SHA-256 `00c19e0ca2ecc84b1fea32fafaec29708b152710a3f5a3b8ece64717a26d97c6`，约 24.4 MB。原命令页改为工作台、原工作台改为概览；系统负载展示及 procfs 采集删除。加入 APK 内置 CPython 3.13.9、终端 python3、四项脚本二级页、SDK 和 portable 清单。框架契约见 [portable-workbench.md](portable-workbench.md)。

- `./hako flutter analyze`：无问题。`./hako flutter test --reporter expanded`：**28/28 通过**，包含新工作台懒加载/失败重试、二级导航、Unicode 参数校验、取消/超时后重跑、小屏/横屏/2 倍文字与低动画布局，以及已有网络和 PTY 组件回归。
- `./hako bash -lc 'cd android && ./gradlew :app:lintRelease :app:assembleReleaseAndroidTest --console=plain'`：最终原生实现通过 lint，设备测试 APK 构建成功。新增三项 Python SDK/非法脚本、取消/超时、Activity 后台回收检查，已有 App/Root PTY 测试追加 python3、标准库与 Python Ctrl-C 检查。
- `./hako current`：arm64 release 构建、签名验证及 SHA256SUMS 通过。最终 APK 检查包含 PIE 启动器、libpython3.13、标准库、三份 SDK 源文件、portable 清单和三份版权声明；无宿主 pyc。merged manifest 确认为 `extractNativeLibs=true`；启动器为 ARM64 PIE、`/system/bin/linker64` interpreter、16 KiB LOAD 对齐。
- 安装前通过显式 serial 的 getprop 确认 TARGET-PHONE / Android 16（`PHONE-ADB-SERIAL`），复制公共 Android 链接器及库到 `${EVIDENCE_DIR}` 供 QEMU。用户随后明确授权覆盖安装 current APK 和测试 APK并验收。两个 APK 均安装成功，设备 base.apk SHA-256 与最终 current 一致。SELinux 为 Enforcing；未更改 Magisk/Vector 配置、重启或清除应用数据。
- 本地 `qemu-aarch64` 执行构建期间 APK 提取的 ARM64 启动器、libpython 和 SDK，`--version` 返回 Python 3.13.9；四个内置脚本通过。自检为 OpenSSL 3.0.18、SQLite 3.50.4，SQLite 内存查询和 SHA-256 校验正常；CLI 导入 SSL/SQLite/SDK 成功。非法参数、未知脚本失败；主机 SDK 的有界日志/结果及 SystemExit 结果封装检查通过。固定 `-P -S` 路径在工作目录存在恶意同名模块时仍加载内置目录。
- 真机首轮发现 Android 安装目录含 `=`，Toybox `env` 将启动器路径误当环境赋值；改为子 Shell 中 `export` 后直接执行路径，App/Root 均可调用 Python。Root PTY 返回额外 CR，测试断言沿用已有读取函数的 CR 规范化。最终完整 `DeviceTest` **OK 8/8，6.233 秒**，覆盖 SDK、自检/Unicode 文本/设备/内存、未知脚本后恢复、运行中真实进程取消和重跑、1 ms 超时回收、Activity 移到后台后取消待运行任务并释放槽位，以及 App/Root PTY 的 Python 3.13.9、SSL/SQLite/SDK 导入、Python sleep 的 Ctrl-C 与原有网络/Root 回归。日志：`${EVIDENCE_DIR}/ctos-python-device-tests-20260926.log`。
- 真机 UI：概览/信息/工作台/终端导航正确，设备页不再显示系统负载；工作台四个 item 和 Python 二级页正常。点击运行自检显示 App、已完成、75 ms、退出码 0，Python 3.13.9 / aarch64 / SDK 1、OpenSSL 3.0.18、SQLite 3.50.4 和内存查询成功。通过系统文件选择器另存 `Download/ctos-python-selftest-20260926.json`，拉回后 JSON 与屏幕结果一致，stdout/stderr 为空。截图：`${EVIDENCE_DIR}/ctos-python-{overview,workbench,detail,selftest,device-info}-20260926.png`；读回文件 `${EVIDENCE_DIR}/ctos-python-selftest-20260926.json`。以上均为本机临时证据。
- Shell 模板通过语法、ShellCheck 和 shfmt 检查；最终生成函数在两种 Android PTY 中实测通过。`git diff --check` 通过。
- 临时证据：`${EVIDENCE_DIR}/ctos-sdk-validation/`（测试脚本与 Shell 模板）、`${EVIDENCE_DIR}/ctos-python-qemu/`（公共 Android 库及提取的运行包）。QEMU 树没有设备 linkerconfig/tzdata，启动产生对应诊断；stderr 与 JSON stdout 分离，脚本协议检查不受影响。这些目录仅是本机临时证据。

**边界**：Android 11、16 KiB 页面设备、其他 Root 管理器未测。参数表单的 Unicode/小屏/失败状态以组件及 SDK 检查为主，未逐项手操；后台检查验证真实 Activity.onStop 取消待运行任务，运行中进程销毁另有独立测试。curl 为扩展文档示例，未打包；外部 ZIP 导入、包缓存管理、运行时 pip、历史与后台调度未实现。

以下记录属于各自历史 APK；其中“当前包”以该条记录日期和哈希为准。

## 2026-09-26 Root-only：移除 Vector 并验收

当前 APK SHA-256：`be800549c2ddf6509fe9ff0de23556d58b9401b67fee39f63fa5eb361752ab93`。设备重新由 `adb devices -l` 确认：`PHONE-ADB-SERIAL`，TARGET-PHONE，Android 16 / API 36，1272×2800，SELinux Enforcing。全部设备命令显式指定该 serial。本轮只覆盖安装 ctOS 和匹配测试 APK、执行固定只读采集/PTY 测试及页面检查；未修改 Vector/Magisk 配置、未重启、未完整卸载应用。

- 原因核查：Root `id` 返回 uid=0，uptime 约 40.7 天。现存 Vector 日志只记录模块在 ctOS App 进程/UID APP-UID 中加载；系统广播列表只有 App 的 RESULT 接收器，没有系统 QUERY 接收器。旧模块只对 android / android 生效且在 AMS.systemReady 后注册服务，因此 App 加载或勾选作用域不等于 system_server 桥接在线。未读取 Vector 作用域数据库、未重启验证，不能将“未重启”认定为唯一原因。完整边界见 [决定](decisions/2026-09-26-root-only.md)。
- `./hako bash -lc 'dart format test/observatory_test.dart && flutter analyze && flutter test --reporter expanded'`：分析无问题，**22/22 通过**。随后仅删除终端测试 fixture 的遗留 moduleActive 字段，`./hako flutter test test/terminal_interaction_test.dart --reporter expanded` **7/7 通过**。网络组件测试直接读取 App networks，Root 已连接、未连接和会话失败状态保持可见，Vector 文案消失。
- `./hako bash -lc 'cd android && ./gradlew :app:lintRelease :app:assembleReleaseAndroidTest --console=plain'` 成功。lint 无错误，5 项 warning：包可见性、Gradle/测试 runner 可升级、缺 ChromeOS x86_64 ABI、旧系统 backup 配置提示；未通过修改规则隐藏警告。
- `./hako current` 构建 arm64 release、验证签名、原子更新 current APK 与 SHA256SUMS。`aapt2 dump xmltree` 确认 APK 无 Xposed 元数据及旧 QUERY 权限；ZIP 列表无 xposed_init，源码无模块/桥接广播引用。AndroidX Core 仍由 Flutter 传递依赖，与移除直接接收器依赖不矛盾。
- `adb -s "$PHONE_ADB_SERIAL" install -r` 分别安装 current 和匹配的 `app-release-androidTest.apk` 成功；设备 base.apk 哈希与 current 相同。
- 完整 `DeviceTest`：**OK 5/5，3.914 秒**，包含 App API 网络快照、Root 接口/连接、Root 采集会话复用/超时不自动重开、App/Root PTY 的 UID/TTY/尺寸/Ctrl-C，以及分身用户序列号/别名解析。日志：`${EVIDENCE_DIR}/ctos-root-only-device-tests-20260926.log`。
- 安装后启动自动恢复 Root，工作台显示 APP 可用、ROOT 在线、4 个网络、33 个接口、root / procfs + ip，只有 App/Root 能力。截图 `${EVIDENCE_DIR}/ctos-root-only-workbench-20260926.png`。网络页语义和截图显示蜂窝、VPN/tun0 默认网络、Wi-Fi/wlan0 以及 IP/DNS/Private DNS 等字段，无 Vector 状态或重启提示；截图 `${EVIDENCE_DIR}/ctos-root-only-network-20260926.png`。这些文件是本机临时证据。
- 本轮没有在 Android 11 开发板、其他 ROM/Root 管理器上重测；未验证全新安装弹窗、真实 live 分身别名的界面筛选、全部终端 IME 操作或完整系统文件选择器导出。Root 失败回退和无 Root 部分连接状态由组件测试覆盖，未更改设备授权模拟拒绝。

以下均为较早 APK 的历史记录；Vector 已移除，不再作为当前包待验收项。

## 2026-09-26 终端输入同步修正

用户发现输入 `i` 后按 Tab，Shell 上方已有补全或候选文字，底部输入框却清空。原因是底部编辑值与 Shell 对当前行的重绘未同步；此前另观察到直接向这台设备的 App Shell 发送原始 ↑/↓ 时，长命令历史会被 Shell 截断重绘。当前实现保留底部已输入内容直到 Tab 返回，读取终端已渲染的可编辑行并同步到输入框；↑/↓改为浏览当前 PTY 会话通过底部提交的命令，再经同一 PTY 编辑路径恢复，不读取会话外的 Shell 历史。

- 最终源码 `./hako flutter analyze` 无问题，`./hako flutter test` **22/22 通过**，`git diff --check` 通过。`./hako current` 构建 arm64 release APK、验证签名与 `dist/SHA256SUMS`。当前 APK SHA-256 为 `1a610e33fc018d20f4b99161d6eb5e91f4eb594930f0d0033e3eea5c873245ae`；在 `PHONE-ADB-SERIAL` 覆盖安装，设备 `base.apk` 哈希一致。
- 该包在 TARGET-PHONE 的 App Shell 中输入 `ec` 后按 Tab，Shell 与输入框都显示 `echo`（`${EVIDENCE_DIR}/ctos-terminal-1a610e-tab-20260926.png`）。按用户原步骤输入 `i` 后按 Tab，Shell 列出 `if`、`in`、`integer` 等多个候选，上下都保留 `i`；继续从底部输入 `d`，上下成为 `id`，点键盘发送后返回应用 UID `10497`（`${EVIDENCE_DIR}/ctos-terminal-1a610e-i-tab-20260926.png`、`${EVIDENCE_DIR}/ctos-terminal-1a610e-i-tab-d-20260926.png`、`${EVIDENCE_DIR}/ctos-terminal-1a610e-i-tab-id-20260926.png`）。执行 `echo hello` 后按 ↑，上下均恢复完整命令；按 ↓，上下均回到空草稿（`${EVIDENCE_DIR}/ctos-terminal-1a610e-history-up-20260926.png`、`${EVIDENCE_DIR}/ctos-terminal-1a610e-history-down-20260926.png`）。这些图片是本机临时证据。
- 当前包顶部“选择输出”打开了独立的“当前输出”页面，显示已渲染的命令、结果与提示符（`${EVIDENCE_DIR}/ctos-terminal-1a610e-selection-20260926.png`）。选择复制、中文候选、普通文本 IME 类型、Root PTY 的 `id` 与 Ctrl-C 设备检查来自下节的较早构建或此修正前的中间包；当前包没有重新逐项实测这些操作。Android 11 开发板、其他输入法/ROM、当前包 Vector 桥接、全新安装授权弹窗、重启后的模块加载及导出未在本轮验收。

## 2026-09-26 终端流程与文案收敛（前一构建）

目标设备由 `adb devices -l` 重新确认：`PHONE-ADB-SERIAL`，TARGET-PHONE，Android 16/API 36，1272×2800，SELinux Enforcing；所有设备操作显式带 `-s "$PHONE_ADB_SERIAL"`。本轮仅覆盖安装 ctOS 并操作其 App/Root PTY，未更改 Magisk 或 Vector 配置。

- 该阶段源码执行 `./hako flutter analyze` 无问题，`./hako flutter test` **19/19 通过**，包含 IME 组合与候选替换、空字段退格、发送/回车、双入口与快捷键、输出快照、320dp/1.5 倍文字及 300px 软键盘内边距。`git diff --check` 通过。`./hako current` 构建 arm64 release APK、验证签名并生成 `dist/SHA256SUMS`；从 `dist/` 执行 `sha256sum --check SHA256SUMS` 通过。该阶段 APK SHA-256 为 `da530c09343b416f8dc4f41aebaacf80b87fe10838b2a84f9f81ed8a17376586`；覆盖安装成功，设备 `base.apk` 哈希一致。
- 该阶段包打开终端时显示“应用 Shell”“Root PTY”两个 item；会话顶部依次为“换用”“选择输出”和停止按钮。输入框无可见标签或占位说明；五个快捷键只有 Ctrl-C、Tab、Esc、↑、↓。上方输出区点击不唤起键盘（截图 `${EVIDENCE_DIR}/ctos-terminal-final-output-tap2-20260926.png`），底部输入框唤起百度/Oplus 输入法。聚焦时 `dumpsys input_method` 显示 `inputType=0x1`、`imeOptions=0x2000004`，即普通文本与发送动作；输入法仍采用厂商自带的蓝色主题，应用不控制其皮肤。
- App Shell 中，中文拼音 `ni` 处于组合态时 Shell 行保持原样；点首个“你”候选后 Shell 显示“你”，键盘保持打开。软键盘退格删除该汉字；随后输入 `id` 并点键盘发送键，仅执行一次，结果为 `uid=APP-UID(APP-USER)`。输入 `ec` 后点 Tab，Shell 补全为 `echo` 且键盘保持打开。打开顶部“选择输出”后可看到 `id` 结果和当前 Shell 行；长按出现系统 Copy 菜单，复制并返回后 App PTY 仍在。截图：`${EVIDENCE_DIR}/ctos-terminal-final-composing2-20260926.png`、`${EVIDENCE_DIR}/ctos-terminal-final-candidate2-20260926.png`、`${EVIDENCE_DIR}/ctos-terminal-final-before-send-20260926.png`、`${EVIDENCE_DIR}/ctos-terminal-final-id2-20260926.png`、`${EVIDENCE_DIR}/ctos-terminal-final-tab2-20260926.png`、`${EVIDENCE_DIR}/ctos-terminal-final-selected2-20260926.png`、`${EVIDENCE_DIR}/ctos-terminal-final-return2-20260926.png`（本机临时证据）。
- 停止 App PTY 后重新出现双入口；用设备已有 ctOS Root 授权开启 Root PTY，输入并执行 `id` 得到 `uid=0(root)`、`context=u:r:magisk:s0`，随后关闭测试会话。截图：`${EVIDENCE_DIR}/ctos-terminal-final-after-stop2-20260926.png`、`${EVIDENCE_DIR}/ctos-terminal-final-root-id2-20260926.png`。此前中间包另核实了 ↑/↓ 历史与 `sleep 30` 的 Ctrl-C 中断；这些检查均不视为当前 `1a610e33…` 包的实测结果。
- 工作台该阶段包截图 `${EVIDENCE_DIR}/ctos-terminal-final2-20260926.png` 显示已删重复介绍而能力、采集时间与 Vector 未响应信息仍可见。设备、网络、命令和连接页的静态文案由源码审查与组件测试覆盖，本次未逐页拍摄该阶段包截图。Android 11 开发板、其他输入法/ROM、Vector 桥接、全新安装授权弹窗、重启后的模块加载及导出未在该阶段验收。

中间构建 `2c95af59…` 在同一手机上曾观察到百度输入法候选替换异常；当时输入组件过早重置编辑值。`da530c09…` 构建改为保留 IME 编辑状态并按 Unicode 差量同步到 PTY，以上中文真机检查针对该包。

## 2026-09-25 QQ 新连接与 `CLONE-ALIAS` 分身别名

用户在应用图标视觉评审通过后报告：打开 QQ 再在 ctOS 搜索 `qq` 没有结果；随后要求支持名为 `CLONE-ALIAS` 的 QQ 分身。授权 TARGET-PHONE `PHONE-ADB-SERIAL` 上，QQ 主应用正在运行且只读 `ss -tunape` 有 13 条 UID TEST-APP-UID 记录，ctOS 旧快照中筛选 `qq` 为 0 条，手动刷新后显示 12 条及真实 QQ 图标。原因是连接页从后台返回、重新进入已有缓存时没有重新采集。现改为这两种进入方式刷新；没有匹配时明确提示快照范围及刷新入口。用户已通过分身卡片视觉评审。

同一手机存在 `UserInfo{999:MultiApp}` 分身用户；`cmd package list packages -U --user 999` 给 QQ 分身 UID `99910377`，只读 `ss -tunape` 在打开该分身后出现 19 条对应连接。原生包列表的 `uid:10377,99910377` 过去只解析首个 UID。现逐个映射；Oplus 桌面受保护收藏项将该包的用户序列号 10 对应标题记为 `CLONE-ALIAS`，由已有 Root 会话的固定只读查询获取，无法读取时回退到原名/包名/用户 ID。该 OEM 数据源不代表其他 ROM 已兼容。

- 当前 `dist/ctos-current-arm64.apk` SHA-256 为 `19b8922bce927066dcec7fd9a7d45ac5caf148f32943d8c1c44090738560791d`，`./hako current` 签名和 `SHA256SUMS` 校验通过；TARGET-PHONE 覆盖安装后设备 `base.apk` 哈希一致。`./hako flutter analyze` 无问题、`./hako flutter test` **15 项通过**，最后一次 Java 修正后 Android `:app:lintRelease :app:assembleReleaseAndroidTest` **BUILD SUCCESSFUL**。
- `ef6e9981…` 中间包仪器测试全部 5 项合跑时，Vector 桥接因该机仍未响应而失败。最终 `19b8922b…` 包只跑分身序列号解析与三项 Root/PTY 检查，**OK 4/4（4.673 秒）**。这不构成 Vector 通过。Android 11 开发板未连接，当前包未在该板验收。
- 前一轮 `d9429780…` 安装包实机显示 UID `99910377` 的 QQ 企鹅图标、别名 `CLONE-ALIAS`、原名 QQ、用户 999 与包名；打开分身再返回 ctOS 后自动刷新并显示 18 条该 UID 连接，截图 `${EVIDENCE_DIR}/ctos-clone-uid-filter.png`。最终包随后增加完整应用名/别名优先匹配，以及对可直接可见分身应用的别名映射；前者由 Flutter 单元/组件测试覆盖，后者由 Android 构建和 Root 连接仪器测试覆盖。最终包未重新取得解锁后的界面截图。实机主 QQ 旧快照 0 条→手动刷新 12 条的对照截图在 `${EVIDENCE_DIR}/ctos-qq-before-refresh.png`、`${EVIDENCE_DIR}/ctos-qq-after-refresh.png`。
- 中间构建 `890d235d…` 的用户资料解析正则曾抛异常，修正后的中间构建恢复了连接采集；最终构建的解析器另有 Android 仪器测试覆盖。一次中间包覆盖安装后 Root 未自动恢复，手动点击工作台“授权 Root”后成功；未据此推断最终包的启动授权结果。

## 2026-09-25 连接应用图标与名称检索

用户追加应用图标及“应用显示名、应用自身名称、包名”检索。原生 `Collector.connections` 在原有 `output`、`partial` 上添加 `apps` 映射，只为此次 `ss -tunape` 输出中出现的 UID 收集可访问应用的显示名、包名、`ApplicationInfo.name`、进程名与 72px PNG 图标；Flutter 解析后缓存。对不可见或图标读取失败的包保留可检索身份和通用占位图标，同 UID 多包可展开查看，不宣称单条连接唯一归属某个包。

- `./hako dart format`、`./hako flutter analyze` 通过，`./hako flutter test` **14 项通过**；新增测试覆盖多种名称检索、图标 Base64 解析失败回退、连接 UI，以及导出不包含图标数据。`./hako bash -lc 'cd android && ./gradlew :app:lintRelease :app:assembleReleaseAndroidTest --console=plain'` 在最后一次 Java 改动后 **BUILD SUCCESSFUL**。
- `./hako current` 构建并校验签名、`SHA256SUMS`。该版 `dist/ctos-current-arm64.apk` SHA-256 为 `78d7af8db4474970cd4bbcb7a9dc16c3dc9333fc13a4a3de455473ab2393537f`；授权 TARGET-PHONE `PHONE-ADB-SERIAL` 覆盖安装成功，设备 `base.apk` 哈希一致。配套 Android 测试 APK 重新构建、安装，该版安装包的 Root/PTY 三项仪器测试 **OK 3/3（3.099 秒）**。
- 该版安装包实机“信息 → 连接”显示 Quick Connect 的真实图标、显示名与 `com.heytap.accessory` 包名。输入显示名 `Quick Connect` 后匹配 11 条连接；截图 `${EVIDENCE_DIR}/ctos-connection-app-icons-final.png`、`${EVIDENCE_DIR}/ctos-connection-app-name-filter-final.png` 来自上述 `78d7af8d…` APK（本机临时证据）。`ApplicationInfo.name`、进程名与同 UID 多包筛选由模拟数据测试覆盖，未在此手机逐项实测；图标可见性取决于 Android 包可见范围。Android 11 与 Vector 对该版仍待验收。

## 2026-09-25 连接检索左对齐修正

用户在连接列表视觉评审中指出标题和说明居中。原因是连接页顶部 `Column` 默认按横轴居中，而 `heading` 宽度只包住文字；改为横向撑满后，标题、说明与搜索框左缘对齐。组件测试增加左缘坐标断言；`./hako flutter analyze` 无问题，`./hako flutter test` 13 项通过。

`./hako current` 重建并验证签名与校验和，该版 APK SHA-256 为 `78392858a4ee90cf9727a2219918ef058fd30333f6ec9c7b90c366db23604be8`。在同一授权 TARGET-PHONE 上覆盖安装成功，设备 `base.apk` 哈希一致；实机截图 `${EVIDENCE_DIR}/ctos-connection-items-left-aligned.png` 和 `${EVIDENCE_DIR}/ctos-connection-items-left-aligned-filtered.png` 确认左对齐、逐条 item 及包名筛选。该次采集有 115 条连接，包名筛选匹配 11 条；数量随采集时间变化。该版安装包的三项 Root/PTY 仪器测试 **OK 3/3（2.832 秒）**。Android 11 与 Vector 仍未在此版验证。

## 2026-09-25 连接 item wrapper（用户追加）

在下节所述第一阶段验证后，用户追加“把原生 netstat 输出转换成一条条 item”。现有采集实际调用 `ss -tunape`；本次保留 Java 返回合同，在 Flutter 层解析协议、状态、队列、本地/远端、UID 和应用归属，保留权限/格式诊断及可展开原文。

- 授权设备仍为 `adb devices -l` 所列 `PHONE-ADB-SERIAL`（TARGET-PHONE，Android 16/API 36，SELinux Enforcing），所有设备命令均用 `adb -s`。只读 `ss -tunape` 样本含 IPv4/IPv6、UID、定时器和 `Cannot open netlink socket: Permission denied` 提示；解析测试覆盖这些格式与异常行。
- `./hako dart format`、`./hako flutter analyze` 通过；`./hako flutter test` **13 项通过**。`./hako current` 构建并验证签名与 `SHA256SUMS`；连接 wrapper 首版 APK SHA-256 为 `1f6e6833359517229c091328d07d0918ad7222e8aca89603a25e489fbee133ce`。覆盖安装成功，设备 `base.apk` 哈希一致。
- 实机该版的“信息 → 连接”显示 126 条结构化连接；包名 `com.heytap.accessory` 筛选显示 11 条匹配，卡片保留 UID 和应用名，旧快照提示仍显示。截图：`${EVIDENCE_DIR}/ctos-connection-items-final.png`、`${EVIDENCE_DIR}/ctos-connection-items-final-filtered.png`。曾在中间构建中观察到 128 条连接，数量随采集时刻变化；截图仅作为本机临时证据。
- 本轮仅改 Flutter 和测试，未改 Java 采集、Root 会话、Vector 或 PTY。该版覆盖安装后，三项现有 Root/PTY 仪器测试再次合跑 **OK 3/3（2.827 秒）**，覆盖接口/连接、会话复用与超时后不自动重开、App/Root PTY。Android 11 开发板及 Vector 仍待该版验收。

## 2026-09-25 可信状态与工作台第一阶段

目标设备由当次 `adb devices -l` 列出并复核：`PHONE-ADB-SERIAL`，TARGET-PHONE，Android 16/API 36，arm64，SELinux Enforcing。全部设备命令显式指定此序列号；Android 11 开发板当次未在线。本次未清除数据、改动 Magisk/Vector 配置或重启。

- `./hako dart format` 格式化改动的 Dart 文件；`./hako flutter analyze` 无问题，`./hako flutter test` 11 项通过。状态单元测试与组件测试覆盖连接状态转换、360dp/1.5 倍文字、受限负载，以及 1024dp 下的 Root 恢复入口。
- `./hako current` 成功构建、验证签名并发布 `dist/ctos-current-arm64.apk`；`dist/SHA256SUMS` 校验通过。APK SHA-256：`4e26b346eb457e23d22e17bf45d655ff58262cda0cc5b358c7e678b333c0a9a9`。`adb -s "$PHONE_ADB_SERIAL" install -r` 覆盖安装成功，设备 `base.apk` 哈希与新包一致。
- 实机工作台显示独立的 APP、ROOT、VECTOR 状态、设备摘要、恢复说明和常用操作；现有 ctOS Root 授权下 ROOT 在线，该手机的 VECTOR 未响应。连接页显示当前结果和采集时间；等待超过 30 秒后显示旧快照，手动刷新后更新采集时间。系统负载在该机被拒绝读取时显示易懂提示，原始 `EACCES` 默认收起，内存数据仍可查看。实机截图在 `${EVIDENCE_DIR}/ctos-phase1-wake.png`、`${EVIDENCE_DIR}/ctos-phase1-device.png`、`${EVIDENCE_DIR}/ctos-phase1-connections.png`、`${EVIDENCE_DIR}/ctos-phase1-connections-stale.png`、`${EVIDENCE_DIR}/ctos-phase1-connections-refreshed.png`，属于本机临时证据。
- `./hako bash -lc 'cd android && ./gradlew :app:assembleReleaseAndroidTest --console=plain'` 成功，测试 APK 覆盖安装成功。首次合跑三项 Root/PTY 仪器测试时，`rootCollectorReusesSessionAndNeverAutoReopens` 的超时断言一次收到读取线程中断异常，其余两项通过；随后单独重跑该项 **OK 1/1**，再合跑三项 **OK 3/3**。当前无法稳定复现首次失败，未改动 Root 会话实现；此波动需在后续设备回归中留意。
- 此手机的 Vector 桥接在前次检查中未响应，本次未重跑 Vector 仪器测试，也未验证全新安装授权弹窗或导出文件的实机读回。Android 11 开发板及其 Vector、PTY、导出路径对本版 APK 均待复验。Flutter 测试覆盖的刷新失败与大屏布局是模拟结果，不作为实机结论。

## 2026-09-25 Trellis Plus configuration

The project-owned policy is `.trellis/spec/trellis-plus/index.md`, with exact
ctOS checks in `validation.md`. The existing Docker wrapper, Flutter manifest,
widget tests, Trellis state and local Codex configuration were inspected.
This was workflow configuration only: no Flutter, APK or device validation was
run, and no device connection or authorization is implied. The UUPM CLI
initialization succeeded for Codex, and its local `scripts/search.py --help`
completed successfully. Documentation links, `git diff --check`, and file
classification were checked after the edit. The current Flutter UI has no
browser validation path, so no Playwright test was added.

## 2026-09-25 current APK on Android 16

Target: user-selected `PHONE-ADB-SERIAL`, confirmed as TARGET-PHONE, Android 16/API 36, `arm64-v8a`, SELinux Enforcing. All device commands explicitly selected this serial.

- The initial `adb install -r dist/ctos-current-arm64.apk` succeeded over the 2026-09-23 ctOS install, without uninstalling it. Its installed `base.apk` SHA-256 matched that day's initial artifact: `22ab383fbaf02024b59f68ef958224a9215625aca58a87b6dd23b724511c2bcd`.
- The matching Android test APK, SHA-256 `3efd86d076953baf267e1e937e9115f3b3bcec0ee4eb48c6699984e8c0cedf48`, installed successfully.
- Current ctOS launched on the phone's 1272×2800 screen. The workbench showed TARGET-PHONE, Android 16, four networks and 11 interfaces from `app / TrafficStats`; the device information page showed system and memory data. `/proc/loadavg` was denied under SELinux Enforcing and its CPU/load section explicitly reported `EACCES` as unavailable without permission.
- `DeviceTest#vectorBridgeReturnsSystemIdentity` **failed** after its 8-second wait: the system bridge did not answer. The app showed VECTOR pending activation. No Vector settings or system scope were changed and the phone was not rebooted.
- After the user authorized temporary screen control, the workbench, network and interface pages were checked. The phone's Magisk ctOS Root switch was already on; it was not changed. The three Root instrumented tests passed (**OK 3/3, 3.762 seconds**) on the initial artifact.
- To implement startup Root authorization, `rootAuto` now tries once on the first app launch, persists successful startup authorization in app preferences, and restores its collector session after subsequent launches or in-place installs. A failed or denied attempt disables automatic retries until the workbench's manual Root action succeeds. Collector polling still never opens another `su` session after a session failure.
- `./hako flutter analyze` passed; `./hako flutter test` passed all six tests, including the startup channel call; `./hako current` rebuilt the sole arm64 APK and verified its signature; Android `:app:assembleReleaseAndroidTest :app:lintRelease` passed. `dist/` contains one APK and `SHA256SUMS` matches it. The new artifact and device-installed `base.apk` have SHA-256 `b777fe91b2dc3292c487977dbda10c44059ef2c9afc8c94095f977fa88b95d80`.
- The new APK installed in place. Without tapping the Root button, the first launch showed ROOT online, four networks and 33 interfaces from `root / procfs + ip`. After `am force-stop im.majo.ctos`, the next launch again showed ROOT online without a tap. Reinstalling the same APK with `adb install -r` and launching again produced the same result.
- The three Root instrumented tests were rerun against the new target APK: **OK 3/3, 3.017 seconds** (`rootCountersAndConnections`, `rootCollectorReusesSessionAndNeverAutoReopens`, `appAndRootPtyAreInteractive`). The Vector test was not rerun after its bridge failure; Vector remains unverified on this phone.
- A genuinely fresh install's Magisk prompt was not reproduced: this phone already had ctOS approved, and clearing app data or revoking that preexisting grant was outside this check. The automatic request path is exercised by the startup channel/widget test and by automatic session restoration on the preapproved phone. The preexisting Magisk ctOS grant is preserved.

## 2026-09-24 former current APK

On 2026-09-24, `./hako current` rebuilt the arm64 release APK, verified its signature and replaced the old version-named package. `dist/` then contained exactly one APK, `ctos-current-arm64.apk` (22,844,406 bytes), SHA-256 `22ab383fbaf02024b59f68ef958224a9215625aca58a87b6dd23b724511c2bcd`. `dist/SHA256SUMS` named only this APK and `sha256sum --check` passed. Running `./hako current` a second time also passed and kept one APK. Flutter's `build/` copy is an ignored intermediate. The 2026-09-25 build above replaced this artifact.

The current APK was installed and tested on the Android 11 development board on 2026-09-24, as recorded below. Android 16 and a fresh system-module load after reboot remain untested. The 2026-09-23 results belong to the superseded artifact with SHA-256 `7869f197e8abd4ee07a5ad97933a0f9b8fea0c65b48d8a2464ff0e7145402ca6`.

## 2026-09-24 current APK on Android 11

Target: `TEST-BOARD-ADB-SERIAL`, confirmed as TARGET-BOARD, Android 11/API 30, `arm64-v8a`, SELinux Permissive. This is the user-selected address for this run; commands explicitly targeted that serial.

- The previous `im.majo.ctos` and `im.majo.ctos.test` installs had incompatible development signatures. Both old packages were uninstalled, then the current app and freshly built test APK were installed successfully. Uninstalling ctOS cleared its app-private data; the previously exported `Download/ctos-snapshot.json` remained present. No other package or Vector configuration was changed, and the device was not rebooted.
- The installed app's `base.apk` SHA-256 was `22ab383fbaf02024b59f68ef958224a9215625aca58a87b6dd23b724511c2bcd`, identical to `dist/ctos-current-arm64.apk`. The test APK was built with `./hako bash -lc 'cd android && ./gradlew :app:assembleReleaseAndroidTest --console=plain'` and has SHA-256 `3efd86d076953baf267e1e937e9115f3b3bcec0ee4eb48c6699984e8c0cedf48`.
- `DeviceTest#vectorBridgeReturnsSystemIdentity`: **OK (1 test)**. The existing system bridge answered with UID 1000, `Vector / system_server`, and network data. This did not require a module change or reboot.
- `MainActivity` launched and remained foreground. Visual checks passed for the workbench, device-information page (model, Android/API, kernel, architecture and uptime), network page, interface list (11 interfaces, `Vector / procfs`), and connections page. Without Root, network collection showed its authorization prompt and connections explicitly marked partial results.
- Both App-permission read-only commands ran on the device: `device.info` returned manufacturer/model, Android/API, kernel, architectures and uptime; `memory.snapshot` returned total/available bytes and low-memory state. Both displayed `available`.
- App-permission PTY started from the terminal page; its `id` shortcut returned UID APP-UID (`APP-USER`). The test session was closed. The recent ctOS process error log contained no entries.
- JSON export through the system file picker was saved under a new temporary name, read back, and parsed with `jq`. It reported `moduleActive=true`, system module UID 1000, 11 module interfaces, and `available` states for system, memory, CPU load, battery, storage and the last `device.info` command. The temporary device export was removed after verification; the older `Download/ctos-snapshot.json` was left intact.
- The new install initially showed Root ungranted. After the user explicitly authorized Root testing, Magisk's timed prompt expired and marked ctOS denied. Only the ctOS item in Magisk's Superuser list was then enabled; Android System and Shell entries were left unchanged.
- Full `DeviceTest` run: **OK (4 tests), 4.846 seconds**. This covered the Vector bridge, Root interface/route/connection collection, Root collector session reuse and refusal to reopen after timeout/close, and both App and Root PTY interaction including resize and Ctrl-C. The workbench then showed ROOT online, `root / procfs + ip`, one network and 11 interfaces after its own authorization action.
- The ctOS process was force-stopped to close its Root session, the ctOS Magisk switch was returned to off, and ctOS was relaunched. Its final workbench showed VECTOR online and APP without ROOT. No other Magisk grant was changed. The `im.majo.ctos.test` package was removed after testing; `im.majo.ctos` remains installed.

Screenshots and the read-back export for this run are in ignored `build/ctos-current-*` locally and are not committed. Vector displayed a pending framework-restart notice after the APK replacement; “Later” was selected and the device was not rebooted. The bridge test used the module already loaded in `system_server`; a fresh module load after reboot was not tested. Android 16 acceptance also remains pending.

## 2026-09-24 Docker build wrapper

`hako` uses `ghcr.io/cirruslabs/flutter:3.35.7` at manifest SHA-256 `d271a49ddd8ce1be6c7954f7eabe0e03766c8a1bf1430f0dce780888a21ed408`. It copies Flutter and Android SDK into ignored `.devhome/` for the mapped host UID, then installs Android SDK 36, Build Tools 36.0.0 and NDK 27.0.12077973. No host port is published. The sandbox initially denied direct Docker socket access; scoped Docker execution was then granted for this validation.

Checks run on 2026-09-24:

- `bash -n hako hako-env.sh`, `shellcheck hako hako-env.sh`, `shfmt -d hako hako-env.sh`, and `git diff --check`: passed.
- `./hako flutter --version`: Flutter 3.35.7 / Dart 3.9.2.
- `./hako flutter pub get`: passed.
- `./hako flutter analyze`: no issues.
- `./hako flutter test`: 6 tests passed.
- `./hako flutter build apk --release --target-platform android-arm64`: passed after clearing older host-path build caches with `./hako flutter clean` and rerunning pub get. A prior attempt failed because an ignored Flutter build cache still pointed to `HOST-BUILD-CACHE` outside the container.
- `./hako bash -lc 'cd android && ./gradlew :app:lintRelease --console=plain'`: passed; Android lint reports 0 errors, 6 warnings.
- Container `apksigner verify --verbose` on the new output: passed with APK Signature Scheme v2 and one signer.

The first successful container build produced `build/app/outputs/flutter-apk/app-release.apk`, 22,844,406 bytes, SHA-256 `27a473e1d5df19000ee47d68ff573a3004e958307bbcc0de1db28ac4c636f5cc`. The later `./hako current` rebuild has the hash recorded above. The current APK was subsequently installed and tested on Android 11 as recorded above; Android 16 acceptance remains pending.

## 2026-09-24 source change status

The new device information and navigation source is built and passes host-side checks through Docker. It was installed and tested on Android 11 as above. Flutter and Dart remain absent from the host PATH; the previous `${EVIDENCE_DIR}/build/` environment is gone. The 2026-09-23 device checks below apply only to the superseded artifact identified by its hash. Android 16 acceptance and a fresh system-module load for the current APK remain pending.

## Superseded 2026-09-23 artifact

Former path: `dist/ctos-0.1.0-arm64.apk` (removed when current APK replaced it).

SHA-256: `7869f197e8abd4ee07a5ad97933a0f9b8fea0c65b48d8a2464ff0e7145402ca6`

Release-mode Flutter AOT, arm64 only, development debug certificate. APK v2 signature verification passed. Includes `libapp.so`, `libflutter.so`, `libctos_pty.so`, and `assets/xposed_init`.

## Passed

- `flutter analyze`: no issues.
- `flutter test`: 5 tests passed, covering phone-width layout and interface filtering; elapsed-time rates; VPN separation; counter reset/recreation; unsupported counters; bounded history.
- Gradle `:app:assembleRelease :app:assembleReleaseAndroidTest :app:lintRelease`: passed.
- Android lint: 0 errors, 6 warnings (pinned dependency versions, arm64-only ABI, package visibility acknowledged by root package lookup fallback, pre-Android-12 backup declaration).
- Initial APK installed and launched on TARGET-PHONE / Android 16.
- Initial Flutter network cards matched the observed Wi-Fi and Clash VPN configuration. ROOT status was visible.
- Vector detected ctOS; scope query returned `system / 0`. Official revision 8a156495 legacy source confirms system callbacks use package/process `android / android`.

## Development board acceptance

### Root notification regression fix

The initial acceptance missed an interaction defect: polling ran `su -c` every two seconds, causing repeated Magisk grant notifications. The superseded artifact replaced that with one explicitly opened collector shell shared by fixed queries. Timeout/closed sessions never reopen automatically; periodic collection falls back to Vector/app data and requires a user action to regain Root.

Rebuilt release/test APKs and ran Android lint successfully. Installed the replacement and reran **all 4 DeviceTest tests: OK, 7.071 seconds**. The new regression checks eight consecutive interface collections with stable parent identity, subshell exit-code isolation, timeout, and refusal to reopen a closed/timed-out session. System bridge, root connections and both interactive PTYs still pass. System module code did not change, so no reboot was needed.

Foreground observation after one grant showed the same collector `su` PID OWNED-PID under ctOS PID OWNED-PID across successive polls, continuing traffic updates, and no recurring grant toast in the later screenshot. Evidence: `${EVIDENCE_DIR}/build/root-session-tests.txt`, `root-fix-processes-{first,second}.txt`, `root-fix-{first,second}.png`. APK signature verified; installed SHA-256 matches the artifact above. A system Shell ANR dialog appeared after instrumentation; selecting Wait dismissed it and UI validation proceeded. No cause was established for that separate system dialog.

### Initial feature acceptance

Device: TARGET-BOARD, Android 11/API 30, arm64, SELinux Permissive, Magisk 30.7, Vector 2.2/3111. User authorized exclusive ADB UI testing and reboot. Only ctOS's module/scope and Root grant were changed.

- Then-current APK installed and launched; installed base.apk SHA-256 matched the historical artifact above.
- Rebooted after installing the updated system code. Vector logs show `system_server entry loaded` and `authenticated network bridge ready`.
- Full `DeviceTest` run: **OK (3 tests), 2.822 seconds**. System bridge returned UID 1000; root interface/route/connection collection passed; App and Root PTYs passed identity, controlling TTY, resize to 83 columns × 27 rows, interrupting `sleep 30` with Ctrl-C, and exit checks.
- Root PTY uses interactive `su`: Magisk's command mode did not provide working job control. Tests wait for the first prompt because Magisk initializes another PTY and flushes early input; they accept Magisk's mounted `/pts/` path and normalize CR for output matching.
- UI: VECTOR online and ROOT badges; default interface automatically eth1; filtering `eth1` returns its live counters; filtering connections by `5555` shows ADB sockets mapped to Shell (`com.android.shell`, UID 2000); Root terminal `id` displays UID 0.
- JSON saved through ACTION_CREATE_DOCUMENT to `Download/ctos-snapshot.json`, pulled and parsed successfully. Before enabling app Root collection, export showed `moduleActive=true`, module UID 1000, `Vector / procfs`, and 11 interfaces including eth1 with nonzero traffic. This independently verifies the Android 11 system-module counter fallback.
- Terminal test session closed and app returned to overview after inspection.

Raw logs, UI dumps, screenshots and the read-back export are under `${EVIDENCE_DIR}/build/` and are not committed. The export remains in the device's Downloads directory. Android 16 current-build acceptance remains unverified after the earlier phone disconnected; no claim of universal ROM compatibility is made.

## Repeat commands

For future device validation, run from this project directory after connecting an authorized device. Rebuild the Android test APK from the current source before running instrumentation; the test APK used on 2026-09-24 was built with the Docker wrapper:

```sh
adb -s "$DEVICE_SERIAL" install -r dist/ctos-current-arm64.apk
adb -s "$DEVICE_SERIAL" install -r build/app/outputs/apk/androidTest/release/app-release-androidTest.apk
adb -s "$DEVICE_SERIAL" shell am instrument -w -r \
  -e class im.majo.ctos.DeviceTest \
  im.majo.ctos.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$DEVICE_SERIAL" shell am start -n im.majo.ctos/.MainActivity
```

Check current Vector enabled state/scope first. Only ctOS is in scope for changes; do not alter other modules. Subsequent APK changes to system module classes require reloading that process. Device tests expect an active network, a preapproved ctOS Root grant, and an enabled system module loaded after reboot.

## Historical temporary build environment

SDK/JDK/Gradle and official Flutter checkout: `${EVIDENCE_DIR}/build/`.

The host already has a Flutter global Android SDK setting pointing at another SDK. To avoid changing it, this session builds through Gradle after writing the project's ignored `android/local.properties` to point at the temporary SDK and Flutter checkout. Flutter analyze/test may rewrite that file; set it immediately before Gradle.

Build invocation used:

```sh
JAVA_HOME=${EVIDENCE_DIR}/build/jdk \
GRADLE_USER_HOME=${EVIDENCE_DIR}/build/gradle-home \
./gradlew :app:assembleRelease :app:assembleReleaseAndroidTest :app:lintRelease \
  -Ptarget-platform=android-arm64 -Ptarget=lib/main.dart --console=plain
```

Run the above in `android/`. Native PTY compilation currently targets a Linux x86_64 build host.

## 2026-09-26 Portable 五项工具本地预检（安装前历史）

父 task：[portable-tools 检查](../.trellis/tasks/archive/2026-09/09-26-portable-tools/check.md)。具体行为见 [工具设计](portable-tools.md)。

- Flutter analyze 无问题，完整 Flutter 测试 31/31；Android lintRelease 0 error / 5 已知 warning，含 PortableToolsTest 的 release 测试 APK 编译通过，尚未执行本轮 instrumentation。
- ${EVIDENCE_DIR} uv：26 核心142项，五项集成113项，CLI12次调用，均通过。包括原08对照、原02实际CBC产物、新加密认证/篡改/源文件保留、token/二进制、09离线mock/有界响应/不跟随重定向、HFTP认证/上传下载/拒绝覆盖/穿越/符号链接。没有自动查询真实IP提供方。
- HFTP 真实浏览器 loopback smoke：上传、特殊编码目录、嵌套上传、下载、409不覆盖、375px布局及无JS错误通过。截图 `${EVIDENCE_DIR}/ctos-tools-check/hftp-browser-phone.png` 已查看；独立 Android 通知/后台生命周期不能由该结果推定。
- 全新私有 Python bootstrap 在 ${EVIDENCE_DIR} 下载、官方固定SHA-256、解包/3.13.7启动、第二次复用通过；bash -n / ShellCheck / shfmt 均通过。
- 最终 APK 26,105,995 bytes，SHA-256 `28a7fb5a2bb287b0aae5fb71fb37fce8167204cedebd52c3a51f916cadadcbe2`，构建/签名/SHA256SUMS通过。13个Python源码逐字节匹配；APK解出的 SDK2 九项目录、08/26/AES-GCM在ARM64 QEMU运行通过（cryptography OpenSSL3.0.18）；新动态库LOAD均16KiB对齐。QEMU linkerconfig/tzdata提示属于模拟环境。
- 测试 APK SHA-256 `a51a6f4478a3b6ee5eee51c88565d1b9796659fc7b3b83aa7ef1a64bf1ae555f`。产物及复验入口见父 task 检查，所有包测试/浏览器运行器保存在 ${EVIDENCE_DIR}，没有全局依赖安装。
- 当前只读确认 `PHONE-ADB-SERIAL` 在线、TARGET-PHONE/Android16。本 task 尚未获安装/通知授权；SAF选择/取消/导出、通知拒绝/允许、FGS后台/返回/停止、App/Root终端以及IP实网/离线仍待本轮设备验收，不借用上一版结果。Android11/原生16KiB页设备未验证。

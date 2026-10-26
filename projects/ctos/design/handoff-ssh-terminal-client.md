# SSH 终端客户端接管档案

## 当前：正式前台密码SSH已完成具名Android界面验收

- 用户于2026-10-26明确回复“允许”，本轮指定开发板隔离测试安装/合成账号/清理已授权并执行，不应再次把历史“待授权”当当前阻塞。授权没有扩展为覆盖安装正式应用或使用真实用户库/账号。
- AIO-3568J/Android11/API30/arm64-v8a/RUNNING_UNLOCKED：实际bridge测试OK1/5.924s，覆盖真实共享库、NEW精确指纹/CAS、密码PTY、保护释放后命令、KNOWN重连；实际Flutter UI测试最终OK1/12.802s，覆盖建库、输入、指纹/算法、信任保存、真实命令与非回显渲染输出、AppBar返回和关闭。两runner方法status0/最终-1，均无skip。
- 新增`SshPasswordUiPlatformTest.java`一份测试，314份原有代码/runner逐SHA保持；不增加产品分支、依赖或协议。仅修正测试驱动的stat参数、只读selection语义与不被接受的硬件Back注入。前三失败保留，最终使用真实可访问控件，无通道/反射/输出替换/剪贴板。
- 最终测试APK SHA46fc5720e4028d4c78848b7f2c14c52c758ee9cd95b15a7cc19d5fd5002d7930，与开发板实际安装bytes一致。签名/内容摘要/AXML/DEX/isolated target/source/log与after证据独立审查PASS，无finding。[本轮完整记录](verification.md#20261026-password-android)，证据`/tmp/ctos-ssh-device-20261026.crqhdkf1`。
- 两测试包/进程、本轮JSON/XML/reverse、临时SSH容器/匿名卷/network/relay/随机密码均已清理。正式已安装APK前后仍3bc762d9…；正式新APK仍在`build/app/outputs/apk/release/app-release.apk`，SHA6fa5c1bee28af584023f91a20ab0252abc02e53867bb13f1fb571468286417e8，本轮未覆盖安装。
- 当前前台密码闭环已实际使用并验收完成。父SSH全功能task仍进行中；私钥/证书、复杂恢复、jump/forward/rekey/后台服务是同一主线后续能力，不重新作为密码连接的前置。没有提交/归档/发布，不应重新跑本轮已通过的同一门禁来消耗下一轮。

## 前序本地验证与设备计划（下述验收已执行）

- 沿同一正式主线，[密码连接合同](ssh-password-connect.md)已经实现正式终端入口、受保护表单、真实共享库/整库子集校验、真实TCP/主机键审阅/CAS、一次临时密码及现有PTY。成功表单退出不撤销shell；显式锁库/停止仍撤销。不要回到平行模型或继续堆基础检查来代替连接验收。
- 最终Flutter242/analyze、fresh JVM718/42全0；native client1,756,509与issuer33通过。最终独立OpenSSH_10.3p1实际密码/PTY/resize/70秒后新命令/exit7/EOF/close通过；该C引擎MANAGED KNOWN互通不等于Android完整编排或Flutter点击。
- normal92全部重跑116s、fixture11142执行69保留38s通过；最后仅issuer错误码修正的normal17s/fixture12s通过，JVM在最后一轮为保留报告。三APK签名/实际AXML/DEX/22ALLOC/session JNI20/role JNI4/archive83通过，独立原始证据审查无finding。source45/protected269/before438。[完整证据](verification.md#20261025-password-connect)：`/tmp/ctos-ssh-password-connect-20261025.fez9zf8o`。
- 正式APK：`build/app/outputs/apk/release/app-release.apk`，SHA256 `6fa5c1bee28af584023f91a20ab0252abc02e53867bb13f1fb571468286417e8`，未安装/发布。隔离main/test APK已生成并验签，实际包名为`im.majo.ctos.secretsfixture`与`.test`，正式包无fixture peer/测试类。
- 下一项是按当前授权在`192.168.9.14:5555`核实身份与解锁状态，用隔离测试包运行`SshPasswordConnectPlatformTest#actualBridgeNewThenKnownPasswordShellAndProtectionRelease`：实际MainActivity/shared VaultStore、NEW精确指纹审批/CAS、释放表单保护后命令、KNOWN重连。固定地址是入口信息，历史授权不能当本轮安装许可；本轮尚未连接/安装/操作设备，须先获得当前具体测试授权。用户批准后应直接执行，不再重复询问同一范围。
- 设备测试优先ADB reverse到新建的自有回环SSH服务，仅随机测试账号；请求用owner-only JSON传入`ctosSshPasswordRequestFile`，包含端点/用户名/密码/完整期望指纹，密码不进命令参数或日志。先确认fixture数据新鲜，若已有来源不明数据不擅自清库。实际Flutter界面点击仍需单独观察，不能把reflection调用说成UI验收。
- 上次临时服务/container/自动匿名卷/network/relay/密码已完整清理，不能复用旧端点。使用固定cached镜像与新临时服务重建；内部network未提供publish映射，宿主回环relay连接其内部地址，记录实际入口。根目录本轮QEMU失败core也已精确清理。任何测试后仅清理本轮创建的fixture包/请求/reverse/服务，不动正式应用数据。
- 父task仍进行中/implementation_complete=false；未提交/归档/发布。可选私钥/证书、复杂恢复、jump/forward/rekey/后台服务仍后续，先完成此实际连接验收。

## 前序用户决定：一个SSH正式主线（2026-10-25）

- 用户明确：SSH尚未正常使用，不分旧/新两套实现，最新模型直接作正式主线。本决定覆盖下方历史格式政策。
- [合同](ssh-manifest.md)/[规范](../.trellis/spec/backend/ssh-manifest.md)：唯一四列表模型/固定当前writer，删除formatVersion/version2业务API；metadata引用/所有权/四candidate保留。
- 仅保留已交付empty/纯TOTP旧输入适配，同一模型，不写库；旧SSH输入拒绝。读取/export/quick wrapper保留原cipher，正常内容mutation/换口令/恢复编码当前布局；原raw认证绑定不替换，独立expected防loan篡改。
- 恢复门改查实际非空KeyBinding/Account审阅缺口，empty/纯TOTP不因tag拒绝。生产provider/主机键/Request/原子effect等原约束继续生效。
- 本地门禁：schema28/lifecycle25方法，首末focused177/9、fresh全量691/39全0；normal92全执行3m23s、fixture11133exec78UP-TO-DATE39s，lint0errors3/4warnings、三APK/签名/隔离/共享SSHbytes/22ALLOC/JNI15_19_1_1_1_1/archive83通过。source5/protected307，434before。首compile在import来源修正前，最终source/定向/全量为权威输入；独立raw XML/log/实际ZIP/AXML/DEX/签名与contentdigest/ELF/dynsym/archive/hash复核PASS，无finding。[本轮结果](verification.md#20261025-ssh-mainline)，证据`/tmp/ctos-ssh-mainline-20261025.73vkexd5`。
- 下一步首先推进最小密码SSH连接的账号绑定、真实主机键确认/该路径准入、connector与可操作入口，直至可实际使用；可选证书/整库恢复独立补齐，不再维持两条版本主线。
- 范围：ctOS本地，父task仍in_progress；未操作设备/真实库或账号，未安装/提交/归档/发布。历史授权不自动延续。

下方全部为历史交接记录，不覆盖当前用户决定。

## 接管摘要（最新有效，2026-10-25，v2持久声明基础）

- 范围：ctOS/antitrust，原task继续in_progress/parent implementation_complete=false；dirty全部保留，本地未提交，无设备/真实库账号/安装/commit/archive/push/dist。
- [合同](ssh-manifest.md)/[规范](../.trellis/spec/backend/ssh-manifest.md)/[实测](verification.md#20261025-manifest-v2)：显式plaintext2、immutable KeyBinding/Account、精确类型引用/全局ID/owner/target唯一性，声明不提供数学/真实账号/信任或完整coverage。
- 兼容：CTOSVLT1/envelope1/KDF/AAD/generation/12MiB不变，默认create/empty/两列表constructor仍v1，旧字节/文本行为保留；只有version2 factory写2，空2不降级，无自动迁移/UI写入入口。
- 新metadata strict UTF8，shared sshText保留原Selection错误映射；opaque broad-v1 target与host spelling不规范化、不猜parse，native不支持的target字符留待实际绑定显式拒绝。
- 四处生产candidate改source.withObjects；引用删除/错type/identity拒绝整个候选，无自动prune/credential替换/降级。loans/unchanged-byte/export/rekey保留版本与metadata，已有clone/cleanup所有权保持。
- preview每侧成功认证decode后、该侧validator前拒绝v2（含empty/pureTOTP），bind同样防御；更早source-v1 auth/validator错误保持优先级，无新reviewer/preview/store效果。旧v1完整行为保留，生产SSH validator/reviewer未注册。
- 实测：首compile41tasks17s；定向200/9reports、fresh全量679/39reports全0（41新增方法）。normal92全执行1m53s，fixture11133执行78UP-TO-DATE20s；lint0errors3/4warnings。
- 首focused199/2fail仅新fixture错误套用mutation禁重读到旧v1 restore，修正为两次legacy commit显式核对旧target并计数；四mutation与quickMaterial门保留，产品无修复。首失败XML/log完整保存，新增Selection测试在最终run纳入。
- 三APK v2/同一单signer/实际隔离target/normal无peer/产品含v2类且无JVM测试类/同SSHbytes/22ALLOC/JNI15_19_1_1_1_1/archive83通过。normal35f37510…/fixturea42b5619…/test95ad1b51…未安装；testAPK同上轮实际bytes，未变instrumentation复用缓存。
- 证据：`/tmp/ctos-ssh-manifest-v2-20261025.stybeq0l`，427baseline/9source/303protected、7首编译source、首失败/末focused/full XML与日志、三APK、两role ELF及archive/raw audit。四cached wheels/source SHA/ZIP重核、临时offline no-index init复用；native/Flutter源码未改，无新增依赖。
- Trellis schema/core限定实现，main串行全部构建/文档，checker独立合同/7生产/41新测试/fixture修正及raw XML/日志/实际ZIP/v2签名和contentdigest/AXML/ELF/dynsym/archive/源hash复核PASS，无遗留finding。前证书current_increment完整保留increment_history。
- 未运行：ARM/native测试、Flutter测试、Android JNI/新平台runtime；normal重新编译native不等于执行。完整v2语义/账号消费、CA/time/principal/revocation、graph-aware reviewer、动态资源/真实Request逐字段消费、完整provider/恢复/connector/父AC保持开放。

## 下一步行动（最新有效，2026-10-25，持久schema之后）

1. 读design入口/plan/constraints及本合同/规范/实测/task/git；核对source9/protected303/artifacts-final（若/tmp仍在）。失去临时证据只标不可复核，不回滚dirty、不归档父task。
2. 基于显式v2 IDs冻结整库关系审计：逐binding使用真实private/public/cert-subject与所选phrase做数学证明，逐未绑定对象保留coverage缺口；预先限制多binding/cert展开预算，不凭同identity/partial报告宣布完整。保留v1审计API语义，先做可执行正反向量再接完整provider。
3. 冻结Account到实际Request/role/selection的精确target/host/port/username/auth-kind/credential绑定与全部入口消费；opaque target不解析/规范化，native无法表示时显式拒绝，不隐式合并相同endpoint tuple。
4. 为证书真实USER/HOST用途冻结approvedCA来源、实际principal/Unix time/撤销与known_hosts/CA selector/marker政策；不以embeddedCA摘要当authority、假/null主体代替上下文或BOOTTIME当Unix validity。
5. 扩展source/target graph-aware reviewer的kind/domain/coverage/实际metadata projection/consent，再联合完整语义provider与全部真实Request路径考虑恢复开放；之后推进最终计划connector/jump/forward/service/UI/父AC。当前v2恢复门保持关闭。
6. 当前无阻止下一轮本地实现的外部阻塞。设备/JNI/锁页/真实端点验收需届时精确目标与当前授权；本轮无设备/真实库账号/安装/提交/发布。

以下“最新”标题均为历史，不覆盖本节。

## 接管摘要（最新有效，2026-10-24，全部证书事实与嵌入签名政策）

- 范围/状态：ctOS/antitrust，原task in_progress/parent implementation_complete=false，全部dirty保留；本地未提交，无设备/真实库账号/安装/commit/archive/push/dist。
- 确认：[合同](ssh-certificate-facts.md)/[规范](../.trellis/spec/backend/ssh-certificate-facts.md)/[实测](verification.md#20261024-certificate-facts)已实现全CERT实际loan及CSCF168；不读current vault/view/role/host/user/wallclock，不复制private/phrase/password/TOTP/其他正文。
- 确认：pinned embedded验签后施加明确subject/CA软件及现代CA签名政策，RSA SHA1仅新操作UNSUPPORTED，旧CSMI PARSED真实对照保持。旧API函数体/JNI未改（C前缀仅ssh2.h include）；生产证书拒绝/provider/v1不变。
- 确认：whole cert保留blob/plain subject/canonical embeddedCA三SHA分别保存，允许同key的subject/CA摘要相等；真实物理recordline在key_result重置前保留。u64 serial/after/before完整bits，不用signed负数拒绝或比较时间。
- 确认：只导出USER/HOST/kinds/签名算法/数值/section长度/public digest，principal/option raw文本不导出、不声称UTF8/规范性/适用性。过期/未来/空/反向区间、空/重复/尾随NUL/nonUTF8 principal按事实保留；trust/time/principal/revocation报告false、factsOnly=true/admissionComplete=false。
- 确认：单次creator/oneattempt/ThreadLocal、atomiccancel/原<=60s、preflight/cleanup slots/最终wipe；128files/1024entries/body1MiB/manifest8MiB、固定结果<=21504。parser/embeddedverify不可抢占且先于postparse政策，深层错误归一化/heap非全锁页边界保留。
- 确认：首compile41tasks18s/strict八模式13tasks18s；定向145/5reports、fresh全量638/37reports全0（新增25JVM+1平台方法）。normal100全执行1m47s；fixture11137执行74UP-TO-DATE20s复用final native，主/testJava/两DEX及测试包重编译；lint0errors3/4warnings。
- 确认：首末ARM证书22239含baseline19977，累计signs59/verifies127/effects0，新增证书操作无sign/privateparse/KDF；最终独立private19980/pair13814/material7784全exit0/cleanup pass。随机PEM/嵌套CHECK计数略变，旧断言保持。
- 确认：三v2/同单signer/实际隔离target/normal无peer/普通与fixture共享SSHbytes/22ALLOC/JNI15_19_1_1_1_1/archive83。normal21401db2…/fixture40fee028…/test95ad1b51…未安装；packaged208cf211…/generated3f30ad96…。固定G1/parser patch7afd7a9a…/生产依赖不变，cached4wheels+sourceSHA/ZIP重核、临时显式offline init复用。
- 证据：`/tmp/ctos-ssh-certificate-facts-20261024.izvxli13`，421baseline/source10/protected299，首末native/source/input/runtime/XML/三APK/actualv2+AXML+ELF审计、final-production.a/两role ELF与review-final.txt；八最终input manifest匹配最终源码/archive/binary。首archive不同保留独立provenance，非当前输入替代。
- 确认：Trellis native/Java implement各限源码，main串行所有构建/文档，checker独立源码/测试/rawXML/ZIP/v2/AXML/dynsym/ALLOC/input hashes复核PASS，无finding；前private current_increment完整保留history，最后验证2026-10-24。
- 未完成：新Android JNI/平台fixture只编译，auth/PTY/config/Flutter未本轮重跑；完整graph/account、CA authority/time/principal/revocation/reviewer、动态资源/真实Request逐字段消费/完整provider/恢复/connector/父AC仍开放。

## 下一步行动（最新有效，2026-10-24，证书事实之后）

1. 接管前置：读design入口/plan/constraints、当前合同/规范/验证、task/git；在ctOS核对source10/protected299/artifacts-final（若/tmp仍在）。全匹配后继续；失去临时证据只标不可复核，不冒充重验，不回滚dirty/归档父task。
2. 当前焦点/未开始：基于[模型研究](../.trellis/tasks/archive/2026-10/10-08-ssh-terminal-client/research/identity-authority-model-20261023.md)设计精确持久关系authority与password account/target模型。先冻结明确purpose/ID关联/新authenticated plaintext version及v1精确roundtrip、所有candidate重建/restore覆盖/显式修复迁移，不用同名或当前partial报告替代。完成判据是可执行schema及正反兼容向量；不能静默迁移/丢失旧字段。
3. 未开始：为实际证书用途及信任source/target审阅冻结approvedCA来源、真实purpose/principal/Unix time/撤销政策和精确review binding。CSCF只提供事实，不能作为批准；需要actual known_hosts/CA selector/marker及revocation/KRL范围的实现/验收。不得用假/null主体绕过上下文，不复用SystemClock BOOTTIME当Unix validity，不把embeddedCA digest当authority。
4. 待验证：真实Request动态/cwd/key-loader/default及逐字段最终消费（含.pub/-cert）；保持生产证书拒绝/完整provider/SSH恢复关闭，全部义务/入口/reviewer完成后再开放相应路径，之后推进connector/jump/forward/service/UI及父AC。
5. 停止条件：Android JNI/锁页/真实端点验收需届时精确设备/隔离fixture/当前授权，不沿用历史许可；当前无阻止下一轮本地设计的外部阻塞。不设备/真实库账号/安装/commit/archive/push/dist。

以下“最新”标题为此前历史，不覆盖本节。

## 接管摘要（最新有效，2026-10-23，全部私钥材料与v1凭据归属）

- 范围/工作状态：ctOS/antitrust，原task继续in_progress/parent implementation_complete=false；所有dirty保留，本地未提交，无设备/真实库账号/安装/commit/archive/push/dist。
- 确认：[合同](ssh-private-material-ownership.md)/[规范](../.trellis/spec/backend/ssh-private-material.md)/[实测](verification.md#20261023-private-material)已实现每个实际PRIVATE_KEY的self-sign/verify与所有非TOTP凭据的v1元数据归属扫描；不依赖外部peer或current vault。
- 确认：重复private identity且无phrase仍逐一检查；有phrase时private owner/phrase均唯一才选，歧义不复制/不JNI/不按秘密相等消歧。单owner多phrase保留唯一private locator但不选任何phrase。PASSWORD只namespace，target suffix保持opaque，正文不复制。
- 确认：CSKP60/type2/独立private domain，复用同一strict parser/software/RSA/signing helper；错误Ed seed实际失败，ALLOC/SYSTEM/LIBCRYPTO不吞为MISMATCH。原CSMI/CSKA/challenge/policy不变，新增privateJNI1与原15/19/1/pair1并存。
- 确认：creator/oneattempt/ThreadLocal、atomiccancel/原<=60s、preflight/cleanup slots/最终wipe fence、每private一次/总private<=8MiB。allPrivateMaterialsVerified及allPassphraseOwnersResolved分别量化有限strict签名与v1归属，不是任意DER/持久graph/password account/trust或完整准入。
- 确认：首compile41tasks17s/strict11tasks15s、定向120/4reports；最终normal98全执行1m46s，fresh JVM613/36reports失败错误跳过0。ARMprivate19976含baseline13815/signs59/verifies74/effects0，独立pair13812/material7784，全exit0/cleanup pass。原随机PEM长度影响CHECK计数，未删/弱化断言。
- 确认：fixture11137执行74UP-TO-DATE19s复用final native，主/testJava、两DEX及测试包重编译。lint0errors3/4warnings；三v2/同一单signer/隔离target/normal无peer/共享SSH bytes/22ALLOC/JNI15_19_1_1_1/archive83。
- 确认：normal0899625b…/fixture4fd9c2b9…/test7425ab8a…未安装，packagedd29a9e42…/generated51f8aa2e…；固定parser patch7afd7a9a…/G1/依赖不变。四cached wheel与source SHA/ZIP本轮复核，临时offline init显式no-index复用。
- 证据：`/tmp/ctos-ssh-identity-next-20261023.j_o6gwfz`，413baseline（初391+22既有已验证源码补充）、source10/protected295、首末native/source/input/runtime/XML/三APK/raw audit、final-production.a/两不同role ELF与review-final.txt。最终六native input manifests均对应最终archive；首archive不同单独保留，不冒充当前字节相同。
- 确认：Trellis native/Java implement限定文件，main串行全部构建/文档；checker独立源码/测试/原始XML/ZIP/v2/AXML/ELF/输入hash复核PASS，无具体finding。前pair current_increment完整保留history；最后验证2026-10-23。
- 未完成：实际Android JNI/平台fixture未执行；本轮未重跑auth/PTY/config/Flutter，不用旧结果冒充。v1/mutation/生产provider注册、恢复/connector及host证书拒绝门未改；完整graph/account/trust/reviewer/final Request和父AC继续开放。

## 下一步行动（最新有效，2026-10-23，全部private审计之后）

1. 接管前置：读design入口/plan/constraints与当前合同/规范/验证、task/git；在ctOS重算source10/protected295/artifacts-final（若/tmp尚在），全匹配后继续，缺失只标不可复核。保留dirty，原task不归档。
2. 当前焦点/未开始：完整关系authority和password account模型需要显式认证metadata/version设计，参考[研究](../.trellis/tasks/archive/2026-10/10-08-ssh-terminal-client/research/identity-authority-model-20261023.md)。先列v1精确roundtrip、所有candidate重建/restore coverage/migration约束及正反向量，再冻结新schema/产品修复语义；不得从本轮v1唯一owner推出private/public/cert关系。
3. 未开始：基于[证书源码研究](../.trellis/tasks/archive/2026-10/10-08-ssh-terminal-client/research/certificate-facts-native-20261023.md)明确type/serial/Unix validity/principal/criticaloption/embeddedCA与信任边界，复用pinned parser；CString归一化不能冒充raw UTF8规范证明，embedded签名不是CA authority。完成实际CA/time/principal/revocation/reviewer与source/target差异验收前，保留生产证书拒绝门。
4. 待验证：真实Request动态/cwd/key-loader/default及逐字段最终消费，尤其.pub/-cert后续读取；不探宿主/不凭suffix批准。全部离线义务/入口/reviewer完成前保持生产provider和SSH恢复关闭，之后再connector/jump/forward/service/UI及父AC。
5. 停止条件：Android JNI/锁页/真实端点验收需届时精确目标/独立fixture/当前授权，不沿用历史设备许可。当前无阻止后续本地设计的外部阻塞；不设备/真实库账号/安装/commit/archive/push/dist。

以下“最新”标题为此前历史，不覆盖本节。

## 接管摘要（最新有效，2026-10-23，显式密钥配对证明）

- 目标/范围：原SSH客户端任务继续in_progress/parent implementation_complete=false；本地未提交，无设备/真实库账号/安装/commit/archive/push/dist。
- 确认：[配对合同](ssh-key-association.md)/[规范](../.trellis/spec/backend/ssh-key-association.md)/[实测](verification.md#20261023-key-association)已实现所选actual private/public/cert subject与精确口令ID；不建立持久关联图、不从同名/后缀/refs猜配。
- 确认：固定domain+plain subject SHA256真实sign/verify，公开字段相同但seed不同的Ed25519实际MISMATCH/SIGNER；expired host cert subject可MATCH，report仍trustChecked=false/admissionComplete=false。CSKA60严格闭包，新JNI1与原15/19/1并存。
- 确认：新pair拒绝legacy encrypted PEM/clear额外口令；RSA全部present字段<=16384bits/no multiprime，旧CSMI/auth/sharedpolicy不改。单parser/KDF/crypto不可抢占，PEM parser内部成本先于新RSA检查；不声称所有堆锁页。
- 确认：一次owner、ThreadLocal跨实例重入、atomic cancel、原<=60s期限、clone所选文件/phrase一次、cleanup slots/final wipe fence、128pair/64MiB重复工作预算。报告仅量化显式所选列表，空列表拒绝。
- 确认：ARM pair13814含重用基线7773/effects0；独立材料7784；fresh源码/archive认证1748333/exit0。沙箱拒绝getsockopt已留strace，获批沙箱外AF_UNIX-only合成通过；旧缓存诊断失败保留，未放宽源码/测试。旧CONFIG缓存矩阵4439/13290/3683/1261/3736/71923/5201实际重跑通过，不冒充新编译。
- 确认：定向80/4reports，fresh全量586/35reports，失败/错误/跳过0；新增22 JVM+1 opt-in平台方法。normal96全执行1m42s；fixture11137执行74UP-TO-DATE21s复用final native，主/testJava、两DEX与测试包重编译；lint0errors3/4warnings。
- 确认：三v2/同一单signer/隔离target/normal无peer/shared SSH bytes/22ALLOC/JNI15_19_1_1/archive83；normal8919c45b…/fixturea6e294a2…/test17157b32…未安装，packaged771438db…/generated2c6c4900…。G1/依赖/parser patch7afd7a9a…保持，显式临时offline init复用四既有wheels。
- 证据：`/tmp/ctos-ssh-key-association-20261023.1p6pj89y`，405before、source-final9/protected292、首末native/XML/log/三APK/audit/compiled inputs/review-evidence.txt。主会话安全边界源码复核，限定worker接线/JVM向量与独立原始证据核对；最终source/protected匹配。resource前轮current_increment完整保留history。
- 工作状态：ctOS/antitrust，全dirty保留；最后验证2026-10-23。实际Android/JNI/平台fixture未运行，host QEMU不能替代设备/锁页/真实端点验收；父AC与完整产品未完成。

## 下一步行动（最新有效，2026-10-23，显式配对之后）

1. 前置：读design入口/plan/constraints、上述合同/规范/验证及task/git；在ctOS重算source9/protected292/artifacts-final（如/tmp仍在）。判据是全部匹配后继续，缺失证据只标不可复核，不假称重验；不回滚既有dirty。
2. 当前焦点/未开始：冻结全库关联authority与覆盖、配置实际选中key/cert关系、口令全局唯一归属和凭据真实target/account/username模型。先研究VaultManifest/selection/provider已有字段，输出具体contract及正反验收向量；显式subset MATCH不能批准整库或推断同identity唯一关系。
3. 未开始：完整信任selector/marker/CA/revocation、证书时间/主体/用途与真实source/target reviewer。先核对actual材料与各入口义务，判据为可执行完整provider及reviewer测试，不将embedded cert签名或过期subject MATCH当信任；KRL/list仍待定。
4. 待验证：真实Request动态/cwd/key-loader/default及逐字段最终消费点，特别.pub/-cert后续读取；不探宿主或凭suffix批准。所有离线义务及入口完成前保持生产provider/恢复关闭，之后才connector/jump/forward/service/UI与父AC。
5. 停止条件：设备/JNI/真实端点验收需届时明确目标/隔离fixture与当前授权，不沿用历史；当前不设备/真实库账号/安装/commit/archive/push/dist。无阻止下一轮本地设计的外部阻塞。

以下“最新”标题为此前历史，不覆盖本节。

## 接管摘要（最新有效，2026-10-23，显式资源类型与直接引用）

- 原task继续in_progress/parent implementation_complete=false；[合同](ssh-config-resources.md)/[规范](../.trellis/spec/backend/ssh-config-resources.md)/[验证](verification.md#20261023-config-resources)。本地未提交，无设备/真实库账号/安装/commit/archive/push/dist。
- 显式RESOURCE_AUDIT第四mode与CSCR嵌套exact CSCA，旧mode/wire不升级；原protected CSDI/token先发/owner/原期限/取消/整job预算/失败cleanup重试保持。资源仅metadata查找，不复制/打开非CONFIG正文；configJNI15/session19/material1/archive83。
- 五类显式资源逐物理声明保留真实kind/parent/line/argOrdinal，locator去重重复遍历而非target去重；STATIC按type集合及真实parent直接refs核对。Identity允许PRIVATE/PUBLIC/CERT，Cert仅CERT，Known仅KNOWN_HOSTS，Revoked PUBLIC-only文本子集，不代表材料/数学/签名/KRL/完整信任。
- relative不猜cwd，%或${保持context，裸$字面；Global全部字面（$可表示/%PATH），Certificate none字面。~/或虚拟absolute精确I/Cert基名不存在保留KEY_RESOLUTION5，静态C1/非法/错type拒绝；不探/认证.pub/-cert。defaults/实际最终消费仍后续。
- CSCR64..107328/资源<=1024，三层依次扣resource/edge rows再解码，whole CSCR只计一次1MiB/4096combinedrows；Root内层对象共用，hasDeferred/resourcesIncomplete与Include分开，staticResourceReferencesVerified只证明STATIC行，恒declarationsOnly/admissionComplete=false。
- 真实ARM Resource4439（含baseline1262）effects0/capabilities0，旧Audit13290/managed3683/direct1261/eval3736/catalog71923/handle5201 effects0；IO426/wipe1634。定向151/9reports与fresh全量564/34reports全0，新增27JVM+1平台方法，全部首轮门禁成功。
- normal94全执行1m52s；fixture11137执行74UP-TO-DATE21s复用final native，主/testJava/两DEX/testpackage重编译；lint0errors3/4warnings。三v2/同一单signer/隔离target/normal无peer/同SSH bytes/22ALLOC，实际native/source输入对应；testparser仅临时__FILE__目录不同不冒充原object hash相等。
- normalafe1a021…/fixturefe7fcd19…/testbdfc94e2…未安装；packaged5e390ecf…/generatedd97bd27c…。patch SHA7afd7a9a…d60fd5e与runner一致，G1/上游源树/依赖声明不变，四既有wheel SHA/ZIP复核后显式no-index复用。
- 证据`/tmp/ctos-ssh-config-resource-20261023.gmfy0g24`：399baseline/before、首末source、native binary/inputs/runtime/XML/log、三APK/audit/hash/独立报告；source15/protected311当前匹配。daemon在研究后实现前中断，恢复核对未有资源代码后继续，未重做已完成实现。前audit current_increment精确保留history。
- 实际Android/JNI/新平台fixture未运行，不用host/C seam推定VM/锁页；宿主8192KiB及xmalloc OOM边界不变，无未锁页fallback。完整材料/数学/信任/provider/恢复/connector与父AC仍开放。

## 下一步行动（最新有效，2026-10-23，资源绑定之后）

1. 读design入口/plan/constraints及当前合同/规范/验证，核对task/git；若证据在，重算source15/protected311/artifacts-final与patchSHA，临时目录消失不冒充重验。保留全部dirty，父task不归档。
2. 冻结精确私公钥/证书数学关系、可用签名者、唯一口令与凭据真实目标/用户名义务；复用真实材料检查/资源literal元数据，不从同identity/后缀/CONFIG generic reference推出关联。传统加密PEM完整消费等材料边界仍需证明，不输出低熵秘密摘要。
3. 完成全部信任selector/marker/CA/revocation、证书时间/主体/CA与真实source/target reviewer。Revoked PUBLIC-only没有实现KRL/list或完整撤销，不把元数据类型当材料认证。
4. 补真实Request的动态/cwd/key-loader/default及逐字段最终消费强制点。当前资源STATIC只验证literal base；可能的.pub/-cert等后续文件消费必须单独认证，不能静默探宿主或凭suffix批准。所有离线义务/入口与reviewer完成后才注册生产provider/打开SSH恢复，之后再connector/jump/forward/service/UI/父AC。
5. Android/JNI/锁页与真实端点验收需届时精确隔离fixture和当前授权，不沿用历史设备/账号许可；管理密码/导出加密迁移单独判定。本地不commit/archive/push/dist。

以下“最新”标题为此前历史，不覆盖本节。

## 接管摘要（最新有效，2026-10-23，CONFIG静态引用/有限政策审计）

- 原task继续in_progress/parent implementation_complete=false；[合同](ssh-config-audit.md)/[规范](../.trellis/spec/backend/ssh-config-audit.md)/[验证](verification.md#20261023-config-audit)。本地未提交，无设备/真实库账号/安装/commit/archive/push/dist。
- 新显式AUDIT模式沿用resource-free reserve→protected CSDI load→run/usage→cleanup，全CONFIG/orphan/zeroCONFIG共用原owner/<=60s BOOTTIME/取消/累计预算/最终wipe fence；原bool reserve/effective/catalog/inspect不升级。新增reserveAudit/audit两JNI，config13/session19/material1/archive83。
- 同固定readconf lexer/Match grammar/managed Include，Host/first-value前执行有限本地效果/信任绕过政策；不假Request/用户，不执行predicate。真实边从child push前父栈取(parent,line,child)，DFS/glob顺序/去重。CSCA82732嵌套exact原CSCD，Java先扣edge rows再nested解码，whole wire单计1MiB/4096rows。
- 每观察到的静态edge须child.path在真实parent直接references，root/sibling/identity不可代替；其他key/trust refs不强制等同Include集合。新Report staticIncludeReferencesVerified/localEffectsPolicyChecked只证明这些有限义务，恒declarationsOnly/admissionComplete=false；动态%/$仍deferred，无完整语义/恢复准入。
- 首strict因helper使用后方WHITESPACE宏失败，改原callsite正规化raw；正式审查补Match early-all余参及grammar先于sticky policy，保留原good all-empty行为。纯table语法/arity INPUT，AddKeys复杂非禁用形态等直接family拒绝，未扩成所有值parser；原raw command/provider-none/compile-disabled区别保持。
- 最终真实ARM Audit13290（含baseline1262）effects0/capabilities0，managed3683/direct1261/eval3736/catalog71923/handle5201 effects0；IO426/wipe1634。定向124/7reports与fresh全量537/32reports全0，新23JVM+1独立平台方法。
- normal94全部执行1m39s；fixture11137执行74UP-TO-DATE21s复用final native，主/testJava/DEX/testpackage重编译。lint0errors3/4warnings，三v2/同一单signer/隔离target/normal无peer/相同生产库、22ALLOC节审计通过。平台JNI/Android锁页仅编译未运行，不据C getter seam冒充VM证据；宿主8192KiB与xmalloc OOM边界保持。
- normal54d64b08…/fixturecc2d7aad…/test2c637750…未安装，packagede09350a1…/generated52535a06…。parser补丁SHA72f09e8a…f17b，runner一致；固定源/G1/依赖声明不变，既有四wheel经SHA/ZIP核对后显式no-index复用。
- 证据`/tmp/ctos-ssh-config-audit-20261023.uvozbgq0`：393文件baseline、首/中/末源码与失败log、native输入/binary/runtime、XML/lint/构建、三APK/audit/hash/独立报告；source15/protected307当前匹配，无额外代码变化。前轮managed current_increment精确保存在increment_history。

## 下一步行动（最新有效，2026-10-23，审计之后）

1. 读design入口/plan/constraints与本轮合同/规范/验证，核对task/git；若证据在，重算source15/protected307/artifacts-final与patchSHA，不将被清理的/tmp当可复核。保留全部既有dirty，父task不归档。
2. 冻结非Include typed配置引用与剩余真实Request义务：IdentityFile/CertificateFile/信任资源等如何绑定实际类型/父引用与展开阶段；动态/Host/Match/User不能通过假alias/用户名/super-root枚举成功。AUDIT只检查有限禁止政策和已观察静态Include边，不改称完整CONFIG验证。
3. 继续精确私公钥/证书身份与数学一致性、唯一口令/凭据实际目标/用户名、全部信任selector/marker/CA/revocation/证书时间与主体和reviewer。复用实际材料与配置输入；不从同identity/后缀/generic reference推断数学关联，不输出低熵秘密摘要。材料PARSED与本轮audit均不是准入证明。
4. 完整离线义务、reviewer和所有入口通过前不注册生产SshContentValidator/reviewer，不开SSH恢复；之后再真实Request的逐字段最终计划/connector及余下jump/forward/service/UI/父AC。管理密码/导出加密迁移独立判定。
5. Android JNI/锁页、设备或真实端点验收另需当时明确范围与精确隔离fixture，不沿用历史授权。本地不commit/archive/push/dist。

以下“最新”标题为此前增量历史，不覆盖本节。

## 接管摘要（最新有效，2026-10-23，实际清单受管配置检查）

- 原task继续in_progress，parent implementation_complete=false；本轮[合同](ssh-detached-config-managed.md)/[规范](../.trellis/spec/backend/ssh-detached-config-managed.md)/[验证](verification.md#20261023-detached-managed)。仅本地未提交，无设备/真实库账号/安装/commit/archive/push/dist。
- Java先保留inspector/facade，native reserve只占既有64槽，load先附着eval与mapping再锁页/JNI复制；CSDI直接在单份受保护映射内adopt。失败含Error保留清理owner，close/destroy可重试；不占live view、不伪造tuple/role/Request。
- 实际独占VaultManifest loan只复制CONFIG正文与全文件type/path，逐个覆盖全部CONFIG（含orphan）与原ordinal；zero CONFIG不JNI。原取消/<=60s BOOTTIME贯穿最终cleanup/wipe/report；1024opens/64MiBreads/16384lines逐root传剩余，结果1MiB/4096rows在decoder分配前检查，复制放大预检64MiB。
- 报告恒declarationsOnly=true/admissionComplete=false，不是全清单语义或恢复准入。原live/default budgets/Include锚点/CSEQ/CSES/CSCD保持；新CSDI/3JNI后config11/session19/material1/archive83。
- 最终真实ARM managed3683（包含baseline1262）effects0/capabilities0，独立direct1261/eval3736/catalog71923/handle5201 effects0，hostIO426/wipe1634。定向101/5reports、fresh完整JVM514/30reports失败/错误/跳过0；新增31个JVM方法。
- normal94全部重跑1m58s，fixture11137执行74UP-TO-DATE22s复用同native，fixture主Java/testJava/DEX/测试包重编译；lint0errors3/4warnings。三APK v2/同一单signer/隔离ID与精确target/normal无peer/相同生产库，22ALLOC节等于生成库。
- 首轮JVM96/1fail为新passphrase fixture关联错误，修正未放宽结构验证。Java路径移除额外ssh/前缀，结果quota前置decoder；新平台fixture bare Include改对应.ssh/child后重新编译。JNI真实VM异常/Android锁页与fixture runtime未执行；C copy-abort seam与编译不替代Android证据。宿主8192KiB锁页上限保持，无unlocked fallback。
- normal6d57ef36…/fixtureb6dc530b…/test4fdd9497…未安装；packaged库426368df…/generatedda0655ea…。证据`/tmp/ctos-ssh-detached-managed-20261023.oeRy2d9W`含383文件baseline、before/首末源码、首轮失败/最终log/XML/ARM二进制/APK/audit，source15/protected303/artifacts hashes；独立checker无遗留具体finding。

## 下一步行动（最新有效，2026-10-23，受管配置之后）

1. 先读design入口/plan/constraints与当前合同/规范，核对task/git。若证据尚在，重算source15/protected303/artifacts-final；临时目录消失不冒充重验。保留所有已有未提交工作，父task不归档。
2. 冻结并补齐typed CONFIG/Include精确引用边和全分支安全政策：不能从catalog的同输入存在/声明alias推断authenticated edge，也不制造Request或super-root遍历所有Host/Match。研究应区分结构校验、声明、有效求值和完整语义证明。
3. 再接实际私公钥/证书身份关联、凭据目标/口令和信任语义、证书有效期/主体及reviewer；复用实际材料检查与本轮真实配置输入，不重开当前vault/view替代candidate。所有完整义务及入口通过前不注册生产SshContentValidator/reviewer，不打开SSH恢复。
4. 完整语义/恢复之后才推进逐字段最终计划/connector和其余父AC。管理密码/导出加密是否迁移单独判定；本轮不宣称全部业务入口或客户端完成。
5. 实际Android JNI/锁页、设备和真实端点验收需要当时明确范围与精确隔离fixture；不沿用历史设备/账号许可。本地不commit/archive/push/dist。

以下“最新”标题为前序增量历史，不覆盖本节。

## 接管摘要（最新有效，2026-10-23，独立 native 配置源）

- 原task仍in_progress，parent implementation_complete=false；[合同](ssh-detached-config.md)/[验证](verification.md#20261023-detached-config)。仅本地未提交，无设备/真实库账号/安装/commit/archive/push/dist。
- 四native IO/evaluator文件、新C测试及Gradle/runner共7源文件；独立受管类型/路径/CONFIG副本、原BOOTTIME期限、root ordinal与CLOSING失败清理owner，不占view/不创建vault tuple。非CONFIG无正文；原Include目录基准与live borrow保持。
- 专项最终ARM1261/effects0/capabilities0/wipes53，原eval3736/catalog71923/handle5201 effects0、IO426/wipe1634、fresh JVM483/28reports全0。normal94全部执行1m43s；fixture111/17s复用同native，Java/test打包UP-TO-DATE，peer/lint report实际执行。不是Android执行。
- 三APK v2/单signer/独立IDs/精确fixture target、normal无peer；两包库全字节相同，22ALLOC节与生成库一致，configJNI8/session19/material1/archive83。normal1059397d…/fixture3536ff7a…/test3c431f96…，共同包内库fd2ae50d…未安装。
- 证据`/tmp/ctos-ssh-detached-config-20261023.mUF8aSF0`：379-file before、各轮源码/二进制/log/core、source-final7/protected272、XML/APK/审计与review。旧TOTP mutation临时证据已失，不重验旧hash。
- 首runtime测试wrapper误捕libc advice，修为仅追踪受管映射并保留擦除断言。第二runtime真实8MiB+metadata锁页超出宿主8192KiB，trace证实mlock8405760 ENOMEM/cleanup；8MiB格式入场与注入故障、6MiB真实锁页解析分别测试，不降级生产保护。
- 最终两次重建在Chaquopy源站失败，PIP环境变量被忽略。原缓存四wheel/hash在cached-wheels.json；临时build/offline.init显式no-index/find-links后全量通过，未改依赖声明。

## 下一步行动（最新有效，独立配置源之后）

1. 核对task/current git/合同；证据尚在时核验source7/protected272和artifacts-final。已清理的临时证据不能当作本轮重验。
2. 在原task冻结Java/JNI实际VaultManifest编排/opaque owner与原取消/期限，逐CONFIG含orphan扫描、原ordinal和整库累计预算。C open失败可能返回非空CLOSING owner，包装器必须保留并重试close/destroy，不把它当可运行handle。
3. 明确并实现实际Include边与authenticated references的类型化对应、全分支禁止效果政策；catalog仅词法声明，不能fake Request遍历所有可能Host/Match/User，动态义务不能用空结果冒充完成。
4. 补真实身份/私公钥/证书数学关联、凭据目标与信任/reviewer语义，全部义务通过后才注册provider/开放恢复，再最终计划/connector。历史材料PARSED/本轮求值不证明完整准入；父AC仍开放。

以下“最新”标题是此前增量历史，不覆盖本节。

## 接管摘要（最新有效，2026-10-23，TOTP mutation）

原task保持in_progress，parent implementation_complete=false。TOTP add/save/delete/rename
及pending/publication已迁移原origin/context、锁外校验与独占候选，预览跨Request保留
原预算和精确票据所有权。定向196/全量483、normal92全重跑/fixture114/fixed77、lint与
三APK签名隔离/共同库审计通过；独立checker最终批准，无未解决问题。

- [合同](totp-mutation-lifecycle.md) · [验证与hash](verification.md#20261023-totp-mutation)。
- 五生产Java、两测试文件；新类14方法。origin首次认证固定revision/binding/mixed，17个
  独立child容纳capture+16候选。成功capture清理不撤销已交接票据；新effect独占耐久claim。
- prepare在manager外检查owner/epoch/前台/解锁/期限/取消/已固定注册快照；manager仅
  metadata比较旧epoch/旧revision，pin最后volatile发布。摘除先同步置取消位，再锁外清理。
- BUSY第二提交者不清理首个owner；worker独占seed避免取消擦除正在验证的副本。
  Bridge传输失败清理请求，选图后不续期，所有最终发布使用opaque票据。
- 证据`/tmp/ctos-totp-mutation-20261022.envakycq`：baseline615、source7/protected268、
  各轮XML/log/APK与review-final.txt。首次编译失败、第二轮194/1fail、第三195pass均保留；
  第二轮测试改为原注册callback捕获目标，未弱化CANCELLED断言。
- normal92all1m39s/fixture114 26s/fixed77 9s；lint0errors3/4warnings。普通APK2c174187…，
  fixture234a4882…，test3c431f96…，共同库14fc6474…；未安装。无设备/真实账号/提交归档。
- Store仍串行，既有Throwable failWrite限制保持；Bridge编译不等于Android生命周期实测。

## 下一步行动（最新有效，TOTP mutation之后）

1. 原task接续完整detached语义provider：CONFIG/Include/identity/key/cert/credential-target/
   trust的整体约束与诊断，复用现有catalog/evaluator/material inspection，先冻结合同。
2. 旧sessionManifest/beginMutation/validateBusiness已搜索确认无调用并删除。生产validator
   尚未注册，不能把content-only inspection当完整语义通过，也不能提前打开SSH恢复门。
3. 完整provider通过后再明确恢复准入、最终连接计划、connector及产品AC。保留原
   request/role/view/注册/期限/取消与loan约束；管理密码/导出加密另行判定迁移需求。
4. 继续仅本地、未提交，不使用历史设备/真实账号授权；父task与关联task保持进行中。


## 接管摘要（最新有效，2026-10-22，trust CAS）

- 原task保持in_progress/parent implementation_complete=false；[trust CAS合同](ssh-trust-cas-lifecycle.md)已实现。本地未提交，无设备/真实库账号/安装/commit/archive/push/dist。
- provider在proposal交付审批前固定原context/epoch/min(role,snapshot deadline)/validator注册与精确对象身份。短锁保留write attempt；close先detach，再锁外cancel/close，未签发request也能取消。
- source/candidate/loan各自独占数组，认证/校验/crypto锁外；错误proposal/有效BUSY不消费，接受effect一次consume/remove。最后raw equality/quickMaterial在Store前，事务内bounded metadata及唯一effect.beforeCommit；proposal digest预计算。
- 最终core session→role→原view fence检查原注册和本次revision后推进epoch，仅保留原精确view；这是本次校验授权完成点。后续回执检查proposal/role/view/期限/关闭交付状态，不追溯验证已完成提交。落盘后延续/回执失败锁库并返回UNCERTAIN，不自动重试。
- 定向202/10reports、全量469/27reports零失败错误跳过；新class10方法、provider新增4方法。首轮199/4fail为三处测试取消错context及原注册变化错误码预期，修正后通过。独立源码审查修复最终guard/role fence与cleanup诊断。
- normal92全部重跑1m40s、fixture114/54s、fixed77/20s、lint0errors3/4warnings、三APK签名/隔离/共同库通过。[验证](verification.md#20261022-ssh-trust-cas)。normal b6f6e80f…/fixture d573c78c…/test3c431f96…未安装，共同库51303ed8…。
- 证据`/tmp/ctos-ssh-trust-cas-20261022.3koy1mha`：613-file baseline/before、源码first/final与incrementdiff、source-final8/protected-final266、原始XML/log/APK/hash；上一轮4/269/684核验一致。
- 原Store仍在core锁内串行，Android AtomicFile/native未实测；既有RuntimeException/Error failWrite限制未扩大。生产语义provider/恢复仍未启用。

## 下一步行动（最新有效，trust CAS之后）

1. 核对原task/current git、合同与验证；证据仍在则重算source8/protected266/artifacts-final，临时文件清理后不得声称重验。
2. 冻结并迁移剩余TOTP mutation：beginTotpRename/renameTotp、addTotp/save/delete、beginMutation/mutateEntry及pending/publication相关边界；pure不引入SSH provider依赖，mixed保留原注册和真实候选校验，原Bridge审批/期限/once claim/uncertain不续建。
3. 之后补完整detached CONFIG/Include图、身份/私公钥/证书、凭据目标与信任的实际Request语义；全部入口/语义通过前不注册生产validator/reviewer、不打开恢复，再接最终计划/connector。
4. 保持当前本地范围与父AC开放；真实Android/端点验证另按当时明确授权和精确隔离工件执行。

以下同日其他“最新”标题是此前增量历史。


## 接管摘要（最新有效，2026-10-22，SSH entry mutation）

- 原task继续in_progress/parent implementation_complete=false；[普通保存/删除生命周期](ssh-entry-mutation-lifecycle.md)已实现，仅本地未提交，无设备/真实库账号/commit/archive/push/dist。
- SSH_SAVE/SSH_DELETE原context在host gate前固定owner/epoch/deadline/validator注册；保留原authorization至effect。保存能力绑定id/target/connection，不能声称admission批准effect才提供的label/type/secret。
- 实际cipher/key/revision短capture后，source认证/独立loan、candidate全数组复制/独立loan、encrypt/readback锁外。纯TOTP source可跳过SSH source校验，first/last SSH完整candidate始终校验。input在host回调前clone；owned cleanup在槽释放前完成，不擦caller。
- 错误绑定/有效BUSY不消费；accepted effect一次consume/remove。最后core串行quickMaterial/raw equality及Store事务；事务guard只metadata/host，不read存储。context仅check/publish；effect.beforeCommit是唯一claim。保留id/generation/salt/key与新nonce、wrapper、uncertain和unlockIfEpoch；不调用旧commitManifest。
- 最终focused152/8reports、fresh full455/26reports零失败错误跳过，新class11方法。normal92全重跑3m6s、fixture114/30s、fixed-normal77/19s、lint0错误3/4warnings和三APK审计通过；独立checker最终批准。[验证](verification.md#20261022-ssh-entry-mutation)。
- final normal77340ea7…/fixture7fcc885c…/test3c431f96…未安装，production SSH库2c274c33…一致。初normal4e656b89…独立native重建字节不同，保留后固定已编译输入重打包；不是native运行。
- 证据`/tmp/ctos-ssh-mutation-20261022.3u9ri93v`：baseline611、before/first/final-source、increment-source.diff、source-final4/protected-final269及原始XML/log/APK审计。上一轮8/264/707hash在本轮起点重算一致。
- 事务本身仍持core串行；只证明慢校验/crypto移出。MemoryStore不代替Android AtomicFile，既有Store对guard RuntimeException/Error未执行failWrite的限制未修，不能推定全面磁盘Throwable回滚。TOTP mutation/trust CAS/own-CAS保持旧独立合同。

## 下一步行动（最新有效，2026-10-22，entry mutation之后）

1. 读合同/验证/current task/git；若证据仍在，重算4source/269protected/artifacts-final。历史临时文件已失时明确不能重验。
2. 聚焦trust CAS：SshManagedKnownHostsProvider.write目前持provider锁贯穿trustRequest/updateTrust；冻结原context/role/view/deadline、snapshot/evidence/proposal全部精确绑定、approval与候选独占所有权。
3. 保持own-NEW-CAS只延续当前session附着且属于同一role的public view：base/replacement digest与对象身份、实际审批、一次effect及提交后advanceTrustEpoch需完整合同；不能套普通mutation成功重置session/revoke逻辑。审查provider/proposal/snapshot锁及uncertain/迟到取消边界。
4. 之后清点剩余TOTP mutation等sessionManifest消费者，补完整detached CONFIG/Include图、身份/私公钥/证书/凭据目标/信任与真实Request语义。全部入口/全语义通过前不注册生产validator/reviewer、不开放恢复，再接最终计划/connector。
5. 保持本地未提交，无设备/真实库账号/发布；父AC开放。需要Android/真实端点测试时先准备精确隔离工件并使用当时授权。

以下同日secret/public等“最新”标题为此前增量历史，不覆盖本节。

## 接管摘要（最新有效，2026-10-22，SSH secret read）

- 原task继续in_progress/parent implementation_complete=false；[typed秘密读取](ssh-secret-read-lifecycle.md)已实现。仅本地未提交，无设备/真实库账号/commit/archive/push/dist。
- SSH_CREDENTIAL/PRIVATE_KEY/PRIVATE_KEY_BUNDLE保留原metadata context/epoch/deadline/validator注册及直接取消；单槽至cleanup结束，真实cipher/key capture后锁外认证/独立loan校验/字节准备，精确accepted effect一次消费。
- selected生产链路在admission前传入原host/KEX/selection/callback guard；原role预算/view不续期。最后raw equality释放core后，以原callback→request→publication→session→operation→role→actual view完成有界受管复制，保留publish而非仅check；receiver不得重入core/native/阻塞。
- SshRoleGuard分开外层publish和内层role fence，修复public snapshot原反向锁序。public native attach仅短operation绑定，不持锁跨lease.active/session attach，保留绑定后即时取消。shared64容量检查/插入统一publication锁，bundle同样publication→session。
- 最终focused196/10reports、fresh full444/25reports零失败错误跳过；新class13方法。normal92全重跑2m35s、fixture114/1m2s、fixed-normal77/19s、lint0错误3/4warnings与三APK审计通过。[实际验证](verification.md#20261022-ssh-secret-read)。
- 首轮195/1fail为本轮public whole-operation锁阻塞取消，保留既有测试修复后通过。独立checker源码/raw XML/log/ZIP/signature/hash复核无遗留finding。
- final normal585565fe…/fixture49b2c017…/test3c431f96…未安装，production SSH库6f13ac81…一致。独立native重建初包字节不同，已保留normal-first并固定已编译输入重打包；不是native运行证据。
- 证据`/tmp/ctos-ssh-secret-read-20261022.umpy0yd0`：baseline609文件、before/first/final-source、increment-source.diff、source-final8/protected-final264、原始XML/log/APK审计。前一public7/56/186hash本轮起点重算一致。
- 已完成受管复制不可追溯撤回；后续JNI/native当前证明检查与scratch擦除继续必要。provider-owned clone清理是源码审阅，测试直接观察借用数组擦除与迟到返回拒绝；不冒充Android/native真实释放运行。

## 下一步行动（最新有效，2026-10-22，secret read之后）

1. 读取本合同/验证并检查current task/git状态；若证据仍在，重算source-final8/protected-final264/artifacts-final，不把已清理临时产物当作当前证据。
2. 继续原task，聚焦普通mutation/trust CAS：冻结原request/role/view/epoch/deadline、candidate独占所有权、exact approval、once claim、uncertain及wrapper保留，逐入口迁移，保持own-NEW-CAS只延续精确原public loan。
3. 不把本轮guard或public/secret operation当作其他来源的替身。随后补齐detached CONFIG/Include图、身份/私公钥/证书、凭据目标/信任与真实Request完整语义；所有入口通过前不注册生产validator/reviewer或打开恢复。
4. 全语义/恢复后再接最终计划/connector；父AC仍开放。JNI/Android实际view及秘密释放需另有精确隔离fixture和当前授权，不沿用历史设备/账号许可。

以下同日public/TOTP/bundle的“最新/当前”标题是此前增量历史，不覆盖本节。

## 接管摘要（最新有效，2026-10-22，SSH public read）

- 原task仍in_progress/parent implementation_complete=false；本轮完成[public文件读取迁移](ssh-public-read-lifecycle.md)，仅本地未提交，无设备/真实库账号/真实剪贴板/安装/commit/archive/push/dist。
- metadata-only SshPublicReadContext在耗时工作前固定owner/epoch/原deadline/guard/不可变validator注册；一次admission、exact issued引用直接cancel。默认bootstrap120秒，snapshotFor用原role.deadline/原role-view，不换view。
- SSH_FILES单槽至cleanup完，真实cipher/key/revision capture后锁外认证/独立canonical loan校验/closure。错误owner/purpose/root/connection及有效effect BUSY不消费，accepted effect一次consume/remove，Throwable擦除，private正文不进publicFiles。
- 同步public loan最后raw store比较后释放core，再原phase→pub→session一次claim，receiver全部锁外并返回前复查。claim后已经复制的public bytes不可撤回；worker回调结束后统一擦除借用数组，不能从取消线程并发wipe，不授予后续trust/secret权限。
- native路径独立opaque SshPublicReadPreparation，锁外encode/create，再最后core rawcompare并释放core，原phase/pub/session内revision/context/epoch/注册检查，operation→session精确attach与后查。一次attachAttempted阻止重入/重放，不替换其他active view。helper未接受参数保持caller ownership，生产外层仍关闭local临时lease。
- default foreground/epoch、capture metadata/key和preownership foreground/attachedRevocation必须同session fence；外部role guard不得进入capture session。已有session.lock同步revoke未迁移，单次parser/native调用不抢占。实际JNI创建/销毁不由fake Revocation证明。
- 不新增持续检查旧epoch/reg的lease revoker。own-NEW-CAS旧exact view仍可随epoch推进继续；现有测试已通过新prepare/attach发布原view，并以origin.context.cancel精确撤销。其他secret读/mutation/trust实现未改。
- 最终定向178/11reports零失败错误跳过；新class19方法+provider2方法，含失效矩阵、ownership/reentry、revision-gap、取消/lock与ownCAS。全量JVM431、normal92全重跑/fixture114与三APK审计通过。[完整验证](verification.md#20261022-ssh-public-read)。
- 最终normal db7f7806…/fixture dcffe982…/test3c431f96…未安装，production SSH库bd8ab199…；独立checker源码/spec/raw XML/log/APK/签名/hash复核通过，无具体遗留finding。
- 证据`/tmp/ctos-ssh-public-read-20261022.mjbo7874`：before/final-source、increment-source.diff、source-final7/protected56、所有raw XML/log/APK审计。first172/1fail为新fixture role重复标识；second178pass及capture-fence前的178/full431/normal92/fixture114分别保留。最后capture修正后重新跑完整链路，不能混用旧passing工件。

## 下一步行动（最新有效，2026-10-22，public read之后）

1. 读design入口/plan/constraints、本轮public read合同与验证，核对current task/git status。若证据仍在重算source-final7/protected56/artifacts-final；旧临时证据消失不冒充当前验证。
2. 继续原task，聚焦credential/private-key/private-key-bundle typed消费者和SshCredentialBridge，冻结原request/role/view/epoch/deadline、精确target/selection/host/KEX/proof、一次释放和最终接收边界；不得把public claim后可完成借用的策略套给secret/code披露。
3. 再逐普通mutation/trust CAS迁移，保持exact own-NEW-CAS公共loan延续、候选所有权、approval/claim/uncertain与原wrapper规则。不能靠假bundle/public operation或current live-view替代真实输入。
4. 补齐detached CONFIG/Include图、私公钥/证书关联、凭据目标/信任与真实Request语义。全入口及完整语义通过前不注册生产validator/reviewer或开放恢复，再接最终计划/connector。
5. 无设备/真实库账号/提交/发布，父AC继续开放；实际JNI/Android view生命周期需要届时精确隔离fixture与当前授权。

以下同日TOTP/bundle“最新/当前有效”记录是前一增量历史，不覆盖本节。

## 接管摘要（最新有效，2026-10-22，TOTP read）

- 原task仍in_progress/parent implementation_complete=false；本轮完成[TOTP typed读取](totp-read-lifecycle.md)。仅本地未提交，无设备/真实库账号/真实剪贴板/commit/archive/push/dist；历史设备授权不沿用。
- Bridge在worker queue前创建metadata-only TotpReadContext，原epoch/绝对deadline/phase贯穿；readGuard.publish仅原phase，不能用legacy phase→session guard再取pub锁。mixed才比较原不可变注册，pure无provider依赖且允许provider churn。
- 单read槽从capture持有到cleanup完；真实cipher/key/revision捕获后锁外authenticate/独立canonical loan校验/generate，loan修改拒绝、Throwable擦除。匹配effect一次consume/remove后工作；BUSY不消费有效effect，错误purpose/resource/owner不消费，context admission一次。
- opaque AppSecrets.TotpReadState附在精确VaultOperation，绑定原context/快照/完成与native生成窗口；不能用scalar伪造。最后store.read/比较在core内、phase前；临时比较cipher buffer在core内擦除，work/Verified/loan清理锁外。
- issuance/completion/reservation为phase→publicationLock→session，foreground-first；精确issued引用与callback后throw撤销关闭cancel/cleanup竞态。实际list Result或clipboard写在释放phase后的pub→session效果锁内再核原permission/期限/epoch/revision/注册与新鲜窗口（含clock后metadata），copied:true及cleanup锁外。
- 不套用preview摘要的claim后交付许可；保持验证码实际披露与session.lock顺序。transport假设有界且不重入core；任意Result重入仍可成锁环，Binder/transport可能延迟session.lock/context.cancel；phase.cancel仍自由。Bridge execute不捕获Error的即刻UI完成策略本轮未改，核心清理后可能等原timeout。
- final focused140/8reports、fresh full JVM410/23reports零失败错误跳过；19项新测试含paused-stage失效矩阵、second-store mismatch、callback-then-throw与clock-seam变更。normal92全执行2m16s、fixture114/40s、lint0errors/3和4warnings；固定输入normal77/11s与三APK v2/同signer/独立target/normal无peer/相同SSH库通过。[完整验证](verification.md#20261022-totp-read-validation)。
- final normal41ce54cc…/fixture96bb6c86…/test3c431f96…未安装；生产SSH库bad69626…。first normal7ce2fb73…与fixture独立native重建字节不同，保留证据后固定已编译输入再打包。instrumentationJava编译，而相同test DEX/打包和fixture lint report渲染复用；不是平台运行。
- 证据`/tmp/ctos-totp-read-20261022.CbgSljcf`：before/final-source、increment-source.diff、7source-final/55protected、raw XML/log/APK/签名/审计。前一bundle证据6source/52protected/26artifacts在本轮起点重算一致；以本轮最终source为当前基准。
- 独立checker源码/spec及最终XML/log/APK/manifest/hash复核通过，无具体遗留finding。实际Android Bridge/AtomicFile/Binder/剪贴板、native/Flutter运行未执行。TOTP_SAVE管理preview、SSH typed/mutation/trust未迁移；全语义/生产恢复/最终计划/connector与完整AC仍开放。

## 下一步行动（最新有效，2026-10-22，TOTP read之后）

1. 读design入口/plan/constraints、TOTP read合同和验证，核对current task及git status。若本轮证据仍在，重算7source-final/55protected/artifacts-final；旧证据被清理则不冒充当前检查。保持当前task，不归档。
2. 聚焦研究剩余SSH typed读取，先冻结public snapshot/sessionManifest及其消费者的原请求owner/epoch/绝对期限、role/view绑定、loan所有权/释放与最终publication。现有secret release/own-NEW-CAS精确public loan continuation必须逐项保持，不能用假bundle/read operation或live-view singleton替代原输入。
3. 合同冻结后再迁移public读；credential/private-key须额外保留精确host/KEX/proof与一次释放，普通mutation/trust CAS独立迁移。不要从TOTP的opaque receipt推定所有SSH消费者已安全，也不直接删synchronized。
4. 补齐脱离live-view的实际CONFIG/Include图、私公钥/证书关联、凭据目标/信任/真实Request义务。完整语义provider与所有入口通过前不注册生产validator/reviewer、不开放恢复，再接最终逐字段计划/connector。
5. 本地未提交，无设备/真实库账号/真实clipboard/commit/archive/push/dist。父AC继续开放；新Android/混合恢复验收需要精确隔离fixture和届时授权。

以下同日bundle摘要及其“当前有效”标题是前一增量历史，不覆盖上方最新TOTP read记录。

## 接管摘要（当前有效，2026-10-22）

- 原task继续in_progress/parent implementation_complete=false；本轮完成[bundle生命周期](ssh-bundle-lifecycle.md)。仍仅本地未提交，不commit/archive/push/dist，不设备/真实库账号；历史授权不沿用。
- BundleContext在审批/排队前绑定owner/epoch/原绝对期限/guard/不可变validator注册；直接cancel与issuance用session metadata排序，原publication走phase→session。context一次admission，操作继承原期限和proposal独立metadata身份。
- bundle capture真实密文/key后锁外解密/实际source校验/apply/完整candidate校验/加密准备；candidate独占所有entry/file数组，loan变更拒绝/Throwable擦除。删除最后SSH对象仍校验；唯一槽保留到cleanup完。
- accepted effect capture一次consume/remove；初始adapter审批拒绝/错误proposal仍不消费。publication后锁外复查审批/context，失败移除精确provisional操作。已消费重放不被旧context错误码覆盖。
- 最后store.read/quickMaterial在Store.commit之前；AtomicFile事务中只能检查metadata/精确审批，不能递归read/openRead。保留原wrapper/nonce/material/claim/uncertain/unlockIfEpoch；trust continuation未改。
- 最终focused81/6reports、fresh full JVM391/22reports零失败错误跳过；normal92全执行3m33s，fixture114/50s，lint0errors/3与4warnings，固定输入normal77/22s及三APK v2/同一signer/独立target/normal无peer/两包SSH库字节相同通过。[完整验证](verification.md#20261022-bundle-validation)。
- final normal633499a5…/fixture3d25f1af…/test3c431f96…均未安装。初normal21438a5d…因独立native重建工件不同，保留first版本并固定已编译native重新打包；不据此宣称native runtime。fixture Java重编译，test DEX/打包与lint report渲染复用情况见验证。
- 证据`/tmp/ctos-ssh-bundle-20261022.S0t9zPRZ`：turn-start快照、increment-source.diff、6source-final/52protected hashes、raw XML/日志/签名与工件。独立checker修复前发现AtomicFile重读，最终无具体遗留finding。
- 未测：实际Android AtomicFile/runtime、设备/真实账号、native/Flutter运行；同步第三方provider不可抢占。其他typed/trust/sessionManifest、全图语义provider/恢复/最终计划/connector与完整AC仍开放。

## 下一步行动（当前有效，2026-10-22）

1. 读design入口/plan/constraints、本轮bundle合同和验证；核对task.py current及git status。若证据仍存在，重算6source-final/52protected/artifacts-final；若已清理，不借旧临时工件冒充当前验证。
2. 继续原task，下一项先冻结typed read/sessionManifest消费者的原请求上下文与release/publication边界。优先梳理TOTP list/code与Bridge原deadline/epoch/phase，保留纯TOTP无需SSH provider、current-code最终publication、取消和敏感复制既有用户决定；不直接删synchronized。
3. typed reads通过后再逐入口迁移普通mutation与信任CAS；own-NEW-CAS精确public loan continuation、role/host/KEX秘密释放、一次消费和锁外独占owner均须单独保持。bundle context不是未迁移消费者的通用假operation。
4. 随后补脱离live-view的实际CONFIG/Include图、私公钥/证书关联、凭据目标与信任义务；完整语义provider与所有入口通过前不attach生产validator/reviewer、不开放恢复，再接真实Request完整计划/connector。
5. 保持本地未提交；不设备/真实库账号、archive/push/dist。父task不归档，不用本轮JVM/编译代替Android持久化或完整客户端AC。

以下接续摘要和“当前有效”标题按其历史日期解释，不覆盖2026-10-22记录。

## 接管摘要（当前有效，2026-10-21）

- 原task继续in_progress/parent implementation_complete=false；本轮完成[恢复预览生命周期](ssh-validation-lifecycle.md)，全图语义/生产恢复/最终计划与客户端AC仍开放。
- 范围：仍仅本地未提交，不commit/archive/push/dist，不设备/真实库账号。历史设备授权不沿用。
- 实现：preview capture/authenticate/实际认证临时loan/validator/reviewer/canonical bind移到core monitor外；loan修改拒绝/全路径擦除，独占在途槽保留到cleanup完。
- 边界：原Bridge Request.deadline贯穿admission；独立120秒review期限在最后store.read后publication起算。不可变validator registration贯穿commit；同实例重挂也失效旧SSH preview。
- Bridge：每Request私有UUID隔离preview owner，BUSY/同名另一页面不能取消原工作；对外requestId/TOTP/export仍旧协议，已返回preview用精确previewId/importCancel。
- 交付：phase→session一次metadata claim是顺序点，result/cleanup在锁外；claim前失效拒绝，claim后发生的取消/锁定允许已claimed摘要交付，但commit必须拒绝。foreground-first防止在phase里触发assertEpoch的lazy revoke。
- 已测：final focused66/4reports、新增21项（含3stage×4失效动作）；fresh full JVM379/21reports零失败错误跳过，normal92全重跑2m18s，fixture114/30s，lint0errors/3与4warnings，三APK v2/同一单signer/独立fixture target与normal无peer通过。[完整验证](verification.md#20261021-preview-validation)。
- 工件：normal7b6d0c1f…、fixture81854c43…、test3c431f96…未安装；instrumentation Java重编译而相同DEX/打包up-to-date，fixture lint analysis执行但未变的report渲染复用；normal报告fresh。
- 保护：4changed代码/测试hash及7protected native/config/JNI/VaultSession/VaultOperation/Dart hash全通过。native/Flutter未改未运行；无Android实际运行，新多Bridge路由仅源码审阅/编译，core owner-token碰撞由JVM验证。
- 证据：`/tmp/ctos-ssh-preview-validation-20261021.kxvZeWH2`。20261019材料检查/tmp已不存在，不能重验旧hash/日志；历史ARM结果不得写成本轮重新通过。
- review：独立checker修复boundGuard丢parent publish fence和同名Bridge误取消；主会话验证最终源码/报告/工件。本轮没有未解决的具体源码finding。

## 下一步行动（当前有效，2026-10-21）

1. 读design/README/plan/constraints、本轮lifecycle与verification，运行git status及task.py current。若新/tmp证据仍在，校验source-code-final、protected-before及artifacts-final，不能借旧临时工件冒充当前验证。
2. 继续原task，优先冻结并迁移beginSshBundle/commitSshBundle：admission尚无VaultOperation原期限/取消，effect已经consume/remove，candidate又共享旧entry数组。先明确真实原context、独占candidate/Verified owner、一次消费和capture→work→guarded effect，保留删除最后SSH对象也必校验；不能浅拷贝close或只删synchronized。
3. 再逐一迁移其他typed/sessionManifest消费者，保留TOTP publication、精确host/KEX secret release与own-NEW-CAS loan continuation；不扩大本轮preview-only的响应性结论。生产validator/reviewer仍不注册。
4. 实际全图语义还需脱离live-view singleton的CONFIG输入/Include图、私公钥/证书关联、凭据/信任与真实Request义务；材料PARSED/catalog/Summary不能代替准入。完整合同与所有入口通过后，才接恢复和最终逐字段计划/connector。
5. 本轮不设备/账号/提交/发布。若后续需要Android多Bridge路由或混合恢复验收，先准备精确隔离fixture并按届时授权处理；父task不归档。

以下2026-10-19的“下一步行动（当前有效）”为当时状态，不覆盖上方新接续记录。

## 历史：材料检查接管摘要（2026-10-19）

- 目标：继续原SSH客户端生产启动链路；实际manifest离线文件检查已实现，完整产品AC仍开放。
- 权限：仅本地未提交，不commit/archive/push/dist，不设备/真实库账号；继续不扩大历史设备授权。
- 当前实现：无session的native材料parser/共享key policy/60-byte CSMI/一个material JNI，Java实际manifest副本owner、精确口令身份歧义、单期限/cancel/重入/擦除与不可变历史报告。[合同](ssh-manifest-inspection.md)。
- 已测：材料ARM7784/effects0/safe_logs4/streams34，原catalog71923/eval3736/handle5201；focused JVM31/fresh全量358及20reports零失败、normal95全重跑1m37s/fixture114/27s。完整认证ARM：**1741410 checks/exit0，synthetic**。[全部证据](verification.md#20261019-manifest-inspection)。
- 工件：normal固定native输入77tasks10s再打包，与fixture生产库相同/22分配节对应；三v2/同一单signer/独立target、config8/session19/material1/archive83/forbidden0。normal a1b24a0e…、fixture4de3918e…/test3c431f96…；材料203b273a…、认证进度副本31d07fc2…。
- 边界：恒contentOnly=true/admissionComplete=false。CONFIG、私钥/公钥数学关联、凭据目标、配置引用/证书有效期/主体/CA/信任均pending，不挂attachSshValidator/不开放SSH恢复。传统加密PEM解密后DER完整消费未证明，不签名。
- 测试修正：五个握手准备过期fixture统一5s、对应expiry wait最多6000次；1037条原CHECK源码全不变，生产60s角色与实际过期判断不变。最终原binary触及外层180s；忽略副本仅增47固定阶段打印，外层600s，移除打印逐字节恢复原测试。
- 未测：Android精确JNI方法只编译、动态ARM只构建；旧Bionic运行库未重取。JNI VM异常/setup-OOM未注入；单KDF不可抢占、上游xalloc OOM限制保留。Flutter未改未重跑。
- 工作区：cwd `/home/wkyuu/cargo/repo/06-dedsec/projects/ctos`，branch antitrust，原task10-08-ssh-terminal-client仍in_progress/implementation_complete=false。
- 证据：`/tmp/ctos-ssh-manifest-inspection-20261019.zkcntg11`；source-final75、protected-final68、artifacts-final清单及固定production清单；旧69保护baseline包含有意修正的auth test，差异单列。最近验证2026-10-19。

## 下一步行动（当前有效）

1. **接管核对**：读design/README、plan、constraints、ssh-manifest-inspection和本轮verification；运行git status --short及task.py current。若临时证据存在，用sha256sum -c检查source-final、protected-final、artifacts-final和final-production-before-runtime；仅变化后重跑有关关卡，不复用旧hash推定新源码通过。
2. **全清单语义与取消合同**：在原task读research/manifest-validator-20261019.md与manifest-native-20261019.md，沿AppSecrets.validateBusiness/sessionManifest/preview/bundle调用链设计source/target/candidate全图、CONFIG引用与动态上下文、身份/目标/信任语义。当前hook同步持core monitor且无独立deadline/cancel，必须先冻结锁/取消/失败/restore review合同，不能递归打开current vault或把PARSED当准入。完成判据为合同和反例可验证，再分层实施。
3. **最终计划/connector**：语义与实际Request合同明确后逐字段处理path/session/forward/jump和精确选择，接MANAGED与现有终端；raw字段全部实现或明确拒绝，原proof/撤销/60s期限和socket cleanup持续有效。不得用Summary或partial报告当连接能力。
4. **设备/发布**：当前不执行。新SshManifestInspectorPlatformTest#syntheticSuppliedManifestInspection需ctosSshManifestInspectorPlatformProbe=true、精确class/method和原fixture guard；仅编译。设备、真实账号、commit和发布按届时明确授权处理，父任务不归档。

## 本轮事实与假设

- 确认：actual supplied manifest与caller loan分离；CONFIG/关联/信任未获语义证明，public lease不能认证不同候选，生产validator无注册。合同、Java31/native7784与源码复核支撑。
- 确认：缺失口令返回NEEDS；超预算加密策略在私钥parser/KDF前拒绝；known_hosts所有行均经固定parser，hashed token另验证20-byte salt/digest，无假host/HMAC，无命令/环境/网络效果。四条固定safe log单列，不宣称无日志。
- 待验证假设：此材料前置能够构成完整语义validator。只有补齐配置/引用/私公钥数学关联、凭据目标/证书信任与取消锁边界的独立正反矩阵才可证实；partial报告或当前lease代替候选验证均证伪。
- 修正：下方catalog完成后的“材料检查未实施”行动现为历史。本轮不是整库validator，不开放恢复，也不宣称新Android运行或完整SSH客户端完成。


## 历史：catalog接管摘要（2026-10-19）

- 目标与完成判据：继续原SSH客户端生产启动链路；有效求值及声明catalog本地实现完成，完整产品AC仍开放。
- 范围与权限（确认）：本地未提交，不提交/归档/push/dist，不设备/真实库账号；“继续”不扩展历史设备授权。
- 当前状态（确认）：同一readconf词法入口/full Host argv、literal首次来源、静态managed Include保守遍历、动态token定位延后；显式catalog模式和CSCD，八config JNI，共用原registry/owner/borrow生命周期。[合同](ssh-config-catalog.md)。
- 已完成（确认）：strict11tasks19s、目录ARM71923/effects0、原求值3736/handle5201、focused JVM39；normal95全重跑1m30s/fresh JVM327/19reports零失败、fixture114/25s；源码与最终证据正式复核通过。[验证](verification.md#20261019-config-catalog)。
- 工件（确认）：最终archive对应目录ARM71923再通过；normal固定输入77tasks9s再打包，两包生产库字节一致、22分配节对应生成库，三v2/单signer/独立target、config8/session19/archive83通过。
- 最终hash（确认）：normal252dc31c…、fixture00cda3f3…/testb31f941a…；目录ARMf0e71e3d…，包内生产库72d4c99f…。完整hash见验证；初始normal2dcf6bc2…另存，不能混用。
- 未完成（确认）：生产全manifest语义validator/SSH恢复、逐字段最终计划/精确选择/connector、jump/forward/service/产品UI和完整AC；新版Android/JNI实际运行未执行。
- 下一步：研究并冻结实际source/target/candidate manifest的语义校验与私钥/信任边界，再做最终计划/connector；声明目录不作准入证明。
- 阻塞与限制（确认）：本地无当前阻塞；旧动态Bionic运行库缺失未重取，Android方法只编译。Match/Port词法通过不代表语义有效，动态Include未解析，IgnoreUnknown不隐藏未知指令；JNI VM异常/独立destroy压力与xalloc OOM限制见验证。
- 工作状态（确认）：cwd `/home/wkyuu/cargo/repo/06-dedsec/projects/ctos`、branch `antitrust`；原task `10-08-ssh-terminal-client` in_progress/implementation_complete=false，全部未提交。
- 环境与证据（确认）：hako/宿主QEMU，`/tmp/ctos-ssh-config-catalog-20261019.RiaqcP03`；65 source/16 protected/17 artifact SHA均通过，完整会话1725125属历史未重跑。
- 最近更新/最后验证：2026-10-19，strict/ARM/JVM/normal/fixture/APK审计实际运行；Android新方法未执行。

## 历史：catalog完成后的行动

1. **接管检查（未开始）**：在上述cwd运行`git status --short`、`python3 .trellis/scripts/task.py current`；读design/README、plan、constraints、ssh-config-catalog和本轮验证。若证据仍在，校验source-first/protected-baseline/artifacts-final三个manifest。完成判据：原task进行中、未提交代码齐全、SHA对应；有并行变化先确认归属，无变化不重复全套构建。
2. **实际manifest语义validator合同（未开始）**：用户继续同一链路时，在原task读research/catalog-vault-20261019.md及catalog-native-20261019.md、AppSecrets.SshContentValidator/validateBusiness/sessionManifest/preview/bundle调用链和SshFileSnapshot。预期定义不依赖当前live会话的source/target/candidate全图验证、private/trust/reference校验、未知User/Match/动态Include处理和资源期限；不能从validator递归打开current vault。完成判据：冻结design/下实际输入/错误/生命周期/恢复审阅合同并保留原门，合成反例可验证；catalog成功不替代语义验证。
3. **最终计划与connector（未开始）**：前置为真实Request/逐字段合同和必要语义准入明确；逐项定义path/session/forward展开、jump路由、精确凭据与信任绑定、socket ownership和取消清理，接MANAGED到现有终端。完成判据：raw字段全部实现或明确拒绝且合成实际连接闭环；无静默直连或以Summary当计划，不延长原60秒role绕过缺口。需改变既有安全合同或真实凭据使用时先明确范围。
4. **Android/发布（当前不执行）**：新`SshConfigEvaluatorPlatformTest#syntheticManagedConfigCatalog`只编译，需`ctosSshConfigCatalogPlatformProbe=true`与精确class/method及原fixture guard；旧evaluation方法也保留。当前不安装、不取设备运行库，不沿用历史授权。设备/提交/发布分别按届时明确授权处理，父任务不归档。

## 关键事实、假设与修正（2026-10-19）

- 确认：目录无Request/passwd/假alias；任何%/$ Include都延后，静态分支遍历与root可达CONFIG闭包不同于全view扫描。源码ssh_config_eval/readconf patch及目录71923回归支撑，最后验证2026-10-19。
- 确认：AppSecrets hook验证实际manifest，当前公共lease不能覆盖source/candidate/private/trust。Unlock本身不跑语义验证；attachRevocation遇活跃lease为BUSY，不替换撤销。证据research/catalog-vault-20261019.md具体调用链及backend/app-secrets spec；生产hook仍无注册，恢复门关闭。
- 确认：CSCD最多70424 bytes、alias/site各256、标准UTF8及严格来源校验；mode getter、borrow重试、final reader撤销/取消和JNI post-array检查保留。合同及19新增JVM/真实native矩阵支撑；真实JNI VM执行仍待验证。
- 待验证假设：同一parser能支撑实际全manifest语义准入和完整计划。证实需所有候选/私钥/信任/引用/动态上下文的合同及独立正反例，再完成connector；任何递归current session依赖、漏字段或不能验证的候选均证伪，不能据此开放恢复。
- 修正：下方“catalog待开始”是前一增量历史；本轮已实现声明发现，但当时建议的contextual扫描由无上下文静态遍历/动态延后合同取代。不会因此声称语义validator完成。

## 历史：JNI/Java边界完成、catalog待接摘要（2026-10-19）

- 目标与完成判据：继续原SSH客户端生产启动链路；native readconf及JNI/Java求值边界均已过本地门，完整产品AC仍开放。
- 范围与权限（确认）：保留本地未提交结果；不提交/归档/push/dist，不设备、真实库/账号操作。用户“继续”不扩大历史设备授权。
- 当前状态（确认）：六config JNI、64槽opaque registry、标准UTF8 wire和Java owner facade已实现；借用caller lease且不撤销它，完整Options留native，Java摘要为hasRawFields=true的历史元数据。原parser/eval/view/IO/session/KEX/JNI源hash保持。[合同](ssh-config-managed.md)。
- 已完成（确认）：strict9tasks17s、focused JVM20、最终ARM5201/effects0/exit0、normal95全重跑/1m36s、fresh JVM308/18reports/零失败、fixture114/28s；正式源码复核无finding。[本轮验证](verification.md#20261019-config-jni)。
- 工件（确认）：冻结最终编译输入后normal再打包77tasks9s，normal/fixture生产库字节一致，22 allocated sections与生成库一致；三APK v2/同一单signer、configJNI6/sessionJNI19/archive83及隔离审计通过。
- 最终hash（确认）：normal284e9566…、fixturefaccd363…/testb1e3c4ce…；ARM静态1291cead…/5201、包内生产库c58f41e9…；dynamicb4fd2036…只构建。初始normal3f3de185…/ARMf03ad972…单列历史，不能混用。
- 未完成（确认）：catalog、生产语义validator/SSH恢复、逐字段最终计划/精确凭据选择/connector、jump/forward/service/UI与完整AC；新版实际Android/JNI/controller运行未执行。
- 下一步：先研究并冻结catalog/validator/原受管Include索引与最终计划合同，再实现精确选择/connector；不得把Java摘要当作完整计划或静默丢弃jump/forward。
- 阻塞与限制（确认）：本地无当前阻塞。旧动态Bionic运行库缺失未重取；JNI真实VM异常路径、cancel/destroy独立压力竞争仅源码审阅。静态测试不是Android执行；xalloc系统OOM限制保留。
- 工作状态（确认）：cwd `/home/wkyuu/cargo/repo/06-dedsec/projects/ctos`、branch `antitrust`；当前指针原`10-08-ssh-terminal-client`，父任务in_progress/implementation_complete=false，全部未提交。
- 环境与证据（确认）：hako/宿主QEMU；`/tmp/ctos-ssh-config-jni-20261019.vnQ3FInL`。source-first9项与unchanged-baseline12项校验通过，完整manifest/日志见验证。原native前置证据保持历史，未重跑3736/完整会话1725125。
- 最近更新/最后验证：2026-10-19；strict/JVM/ARM/build/APK审计均已实际运行。独立新平台方法只编译未执行。

## 历史：JNI/Java阶段下一步行动

1. **接管检查（未开始）**：在上述cwd运行`git status --short`、`python3 .trellis/scripts/task.py current`；读design/README、plan、constraints、ssh-config-managed及ssh-config-evaluator。预期原task进行中、本地未提交增量齐全。若临时证据存在，校验source-first及unchanged-baseline；有并行变化先确认归属，无源码变化不重复全套构建。
2. **catalog/validator合同（未开始）**：用户继续同一链路时，在原task研究AppSecrets.attachSshValidator现有拒绝门、导入/恢复候选清单语义、SshFileSnapshot与原Host/Include索引研究，更新design/ssh-launcher和task artifacts。预期复用固定parser，生产索引/语义检查有界且保留来源，不另写Java SSH字段子集。完成判据：定义未知User/Match上下文、Host模式与catalog范围、验证期可用的公共数据及生命周期；未验证前不放开SSH恢复，不签发role/socket/凭据权。
3. **最终计划与connector（未开始）**：前置为上述合同明确；逐项确定路径/session/forward展开、jump路由、每跳精确凭据/信任绑定与取消清理，再接MANAGED到现有终端。完成判据：raw字段全部实现或明确拒绝，原proof/撤销/期限持续有效，合成实际连接闭环；不能只靠摘要或fake Port宣称产品启动。需改变原安全合同或使用真实凭据时先明确范围，不延长原60秒role绕过缺口。
4. **Android/设备与发布（当前不执行）**：新`SshConfigEvaluatorPlatformTest#syntheticManagedConfigEvaluation`带ctosSshConfigEvaluatorPlatformProbe=true及精确class guard，已在test DEX。当前不安装执行、不取旧设备运行库、不沿用历史认证授权；父任务不归档、不提交/push。

## JNI/Java关键事实与假设（2026-10-19）

- 确认：native open在borrow后失败仍保留正cleanup handle；run五整数区分配置错误与native errno；Java wrapper预分配，无失败post-open检查丢句柄；close只释放自己的borrow。证据为ssh_config_handle/Java源及5201真实回归、20focused JVM，见[验证](verification.md#20261019-config-jni)。
- 确认：run期间短锁pin允许跨线程cancel，destroy不得释放busy对象。并发cancel/destroy互斥安全为源码复核，动态用例覆盖paused-run cancel及busy destroy拒绝；无独立destroy竞争压力测试。
- 确认：CSEQ9766/CSES829字节、标准UTF8与ISO controls拒绝，diagnostic允许第16385行的预算拒绝；full Options留native，Summary不得跨撤销作为能力。具体布局/限制在[合同](ssh-config-managed.md)。
- 待验证假设：当前边界能支撑生产catalog/validator及完整连接计划。证实需上述合同、真实parser索引/逐字段处理及合成connector闭环；缺失User上下文或未处理raw字段即不能证实，不因JNI入口存在开放恢复/网络。
- 修正：旧“JNI尚待接入”属于下方历史；本地边界已完成。普通包与fixture的随机临时构建路径导致初次工件不同，现固定已编译输入再打包并审计，最终hash以顶部本轮验证为准。

## 历史：native前置完成、JNI待接时的摘要

- 目标与完成判据：在原SSH客户端任务内继续生产启动链路；本轮native配置求值增量已实现并过本地门，完整产品AC仍开放。
- 范围与权限（确认）：用户已选择保留未提交工作区和本地结果；不提交/归档/push/dist，不设备或真实库/账号操作。当前“继续”不扩大旧设备授权。
- 当前状态（确认）：固定readconf/managed Include、完整Options、open/run/guarded getters/close/destroy、atomic cancel与可重试清理已接既有生产库；has_raw_fields=1，结果不是可执行计划。原认证/KEX/PTY源hash不变。
- 已完成（确认）：最终静态ARM求值3736/effects0、共同链接完整会话1725125/exit0；normal95全重跑/1m36s、fresh JVM288/17reports/零失败、fixture114/23s及三APK签名/隔离审计通过。正式源码及最终delta复核无遗留问题。[本轮证据](verification.md#20261019-config-evaluator)。
- 工件（确认）：normal APKdd2dd9a0…，fixture74c68d96…/test17c47f1b…；执行求值器9f1fb6ba…/会话be3c2f74…；动态testce258930…仅构建未执行。完整hash及清单见验证文档。
- 未完成（确认）：JNI/managed生产目录及语义validator、不可变最终计划/精确凭据选择/connector，jump/forward/service及产品界面；新版Android/JNI/controller/managed设备实测和完整AC。
- 下一步：按下方行动先冻结JNI结果/诊断与生命周期合同，再接catalog/validator；保留所有raw字段，未知/不支持行为明确拒绝。
- 阻塞与限制（确认）：本地实现无当前阻塞。旧动态Bionic运行库缺失，未重取设备文件；静态compat/TLS适配不能外推Android/JNI。xalloc系统OOM仍终止，PID/UID/borrow释放失败为源码审阅边界。
- 工作状态（确认）：workspace `/home/wkyuu/cargo/repo/06-dedsec/projects/ctos`；branch `antitrust`，全部增量未提交；当前指针为原`10-08-ssh-terminal-client`，in_progress/implementation_complete=false。
- 环境与证据（确认）：zsh/hako/宿主QEMU；`/tmp/ctos-ssh-config-evaluator-20261019.DNFND9Yx`。source-eighth和session-baseline清单复核通过；原G1全量manifest前后校验保持。
- 最近更新/最后验证：2026-10-19；实际命令、日志与覆盖限制见[验证](verification.md#20261019-config-evaluator)。旧摘要及结果按历史解释。

## 历史：native前置完成后的行动

1. **接管检查（未开始）**：cwd为上述ctOS目录，先运行`git status --short`、`python3 .trellis/scripts/task.py current`并读design/README、plan、constraints及ssh-config-evaluator。预期原task进行中、未提交增量存在；有并行修改先厘清归属。若临时证据仍在，校验source-eighth.sha256；无源码变化不重复全套构建。
2. **JNI/受管结果合同（未开始）**：前置为本native门已过；用户继续同一产品链路时，在原task研究现有public-view token borrow和Java owner边界，更新ssh-config-evaluator/ssh-launcher及task design，再实现。完成判据：有界不可变结果/来源诊断，run/撤销/取消/原期限/清理传播，不向Java暴露Options指针、秘密或新连接权；真实parser与JNI分层验证，不把fake当Android运行。
3. **catalog/validator/最终计划（未开始）**：前置为上述结果边界明确；研究AppSecrets现有拒绝入口与配置索引合同，逐字段定义path/session/forward展开、精确凭据选择和效果检查。完成判据为受管Include闭包与来源保留、未知User可补全、jump/forward全部实现或明确拒绝，无静默直连，再接socket connector→MANAGED→现有终端。若需改变原role/认证安全合同先明确范围，不延长原60秒role来绕过缺口。
4. **设备与发布（待授权范围改变）**：当前不执行。不得沿用历史认证设备授权安装新版fixture，不访问真实库/账号。父任务保持in_progress，不提交/归档/push。

## native前置增量关键事实（2026-10-19）

- API/边界与完整枚举见[合同](ssh-config-evaluator.md)、[spec](../.trellis/spec/backend/ssh-native.md)及[验证](verification.md#20261019-config-evaluator)；新增7个native API，session JNI仍19、archive83，parser独立对象且导出命名空间化。
- 两个上游零长度数组pointer原为poison，完整cleanup前需NULL初始化；source first-assignment追踪覆盖HostName/User/算法defaults。保留xalloc系统OOM限制，不用longjmp绕清理。
- limits为view128对象/1MiB单文件/8MiB总、IO1024次/64MiB、Include8层、每行8192字节/16384累计行、8跳/32合计forward/256其他list、255字节host/user；URI解码后仍校验UTF8和长度。
- 假设台账（待验证）：native结果可以安全映射为完整产品连接计划。证实需逐字段展开/兼容矩阵、受管路径/凭据绑定及connector真实合成闭环；只取得摘要、保留未处理raw字段或静默忽略jump/forward均不能证实。
- 修正：旧“readconf evaluator待实施”仅属于下方历史。native前置入口已实现；catalog/validator/connector仍未实施，未因此开放SSH恢复或真实连接。

## 历史：配置求值器实施前的接管摘要（已由顶部取代）

- 当前接续（实施中，尚无新版测试结果）：生产native受管配置求值器，合同
  [ssh-config-evaluator.md](ssh-config-evaluator.md)已冻结；具体parser/helper/cleanup研究在
  task research/config-evaluator-20261019.md。实现责任为新ssh_config_eval、独立readconf
  patch/构建脚本、Gradle和真实parser测试；main串行验证，证据目录
  `/tmp/ctos-ssh-config-evaluator-20261019.DNFND9Yx`。不接JNI/catalog/connector或validator。
- 目标与完成判据：MANAGED offered-host与精确NEW审批增量已补齐完整本地构建/包装关卡；下一前置增量为生产readconf/managed Include evaluator，再接catalog/connector。
- 范围与权限：用户已选“保留工作区，暂不提交”“本轮仅保留本地结果”，此后“继续”不扩大为设备或账号授权。不提交/归档/push，不设备操作，不用真实账号/库。
- 当前状态（确认）：新增MANAGED native/JNI/Java入口、pending-host一次pin guard、exact role/review绑定有界邮箱；旧PINNED和认证/PTY/proof/撤销/原60秒role/owner保留。共19个session JNI；父task仍in_progress / implementation_complete=false。
- 已完成（确认）：normal94任务全重跑/1m30s，fresh Gradle JVM288/17reports/0failure/error/skip；fixture113tasks/16s与lint通过，三APK v2/同一单signer、独立fixture/target、normal仅生产SSHlib、19JNI/archive83/forbidden0；peer动态依赖生产库。独立静态NDK/Bionic专项87,300/full1,724,636 exit0，正式源码复核无finding。[当前验证](verification.md#20261019-managed-host)。
- 未完成：新版Android JNI/controller/managed设备实测；产品readconf evaluator/catalog/connector、forward/service、KBI/MFA、加密PKCS8与完整AC。旧动态ARMtest虽已重建，本轮未动态执行。
- 阻塞与限制（确认）：旧自动审批容量阻塞已解除。旧/tmp证据与设备动态Bionic运行库已消失；未访问设备重取。新增静态测试使用独立compat副本及测试TLS对齐，不替代Android JNI或旧动态环境运行；具体失败轮与最终hash分列。
- 工作状态（确认）：workspace `/home/wkyuu/cargo/repo/06-dedsec/projects/ctos`；branch `antitrust`；本轮全部修改未提交。认证251b0f3/交接b4ed4e2与当前b4ec864/12a1357的ctOS树相同，未重做认证。
- 环境与证据（确认）：zsh、项目hako Docker、宿主QEMU；本次`/tmp/ctos-ssh-managed-host-resume-20261019.ruWFiwIc`。本轮JVM为Gradle完整fresh288，不再仅依赖旧宿主JDK fallback。静态测试构建log记录精确编译参数；源码和工件manifest均重新保存。
- 最后工件（确认）：normal APK84e98c04…；fixture9009448d…/test17c47f1b…；normal内生产SSHlib c5b62954…。实际执行静态ARM SHA6520ff35…；重建但未执行的动态test SHA2d220209…。不得混用旧native-third hash/count或旧PTY APK。[完整hash](verification.md#20261019-managed-host)。
- 最近更新：2026-10-19。
- 会话接续：已恢复当前session指针到原SSH客户端task，未创建新task；manifest校验通过，
  但大文件注入截断/源码清单警告仍在，子代理须按相关章节和实际源码补读，不能只依赖自动注入。

## 历史：offered-host收尾后的行动

1. **完整本地关卡已完成**：本轮normal/fixture/APK审计与独立静态ARM回归通过，验证命令与hash见[验证文档](verification.md#20261019-managed-host)。无源码变化不重复全套构建；旧动态ARM环境缺失与Android未执行分别保留，不重取设备文件。仍不提交、不设备。
2. **下一产品前置增量**：按ssh-launcher.md及原research研究并冻结生产readconf/managed Include evaluator的不可变计划，保留原Include/ProxyJump与执行型配置拒绝，不自写Java配置子集、不静默忽略jump/forward。当前policy/provider/Include仍是fixture-only或fail-closed，readconf输入fatal、全局状态、文件/命令副作用与final pass必须先冻结安全方案；再接catalog/精确凭据选择/socket connector→MANAGED→现有终端。原role期限不为“长生命周期”延长。
3. **设备验收（当前未授权，不执行）**：后续仅在范围明确改变时执行精确fixture。旧shell/controller与新syntheticManagedHostChain均尚无本地新版设备结果；不沿用原认证设备授权、不重复请求本轮已拒绝的安装/提交。
4. **持续保留状态**：父任务in_progress/implementation_complete=false；不归档/push。遇到真实凭据需求、未授权设备操作或需改变认证安全合同再明确范围；不能把PARTIAL/CHANGE_REQUIRED放行。

## 本次实际主机键与审批合同

- [冻结合同](ssh-launcher.md)、[backend spec](../.trellis/spec/backend/ssh-native.md)。MANAGED无caller key；实际issuer/KNOWN或获批CAS NEW后一次pin，仍需upstream签名/NEWKEYS和selected SUCCESS。
- exact Review/role对象与一次ticket；原role deadline、30s wall cap、<=50ms等待片；owner等待时UI直接mailbox.resolve/cancel，不进入session JNI/owner队列。Sink必须非阻塞；JNI全局sessions_lock仍串行阻塞其他会话，此局限未更改。
- native first/second仅修正新expiry测试预期/等待预算；最终third实际87305/full1724409通过。JNI/Java open共享claims/借用/FD失败清理，MANAGED手动verify本地拒绝。
- 新fixture exact opt-in，KNOWN/NEW×password/encrypted Ed25519至shell，拒绝/取消无CAS/epoch变更/认证请求；仅源码编译与审阅，尚无APK/设备结果。详见当前验证。

## 上一PTY增量关键合同与证据

- [冻结合同](ssh-session-channel.md)、[backend spec](../.trellis/spec/backend/ssh-native.md)、[terminal spec](../.trellis/spec/frontend/terminal-interaction.md)。仅选定认证SUCCESS放行；proof/撤销/原60秒role期限/线程及cleanup retry保留。
- 收发window65536/maxpacket32768，native input256KiB/encrypted output128KiB；host stdin64KiB/单个ack输出32KiB；typed代次先过滤再UTF8 decode，SSH ↑/↓交远端。
- native-first的旧sandbox admission失败和3-byte测试预算不足均保留记录；最终新binary实跑通过，不混用初轮hash/count。正式checker复核取消token、输出ack、Error、owner转移和executor销毁竞争修复。
- Android两个新方法已本地编译，尚未设备执行。产品profile/connector启动器未做；不把fake Port/JVM或Fluttermock冒充真实端点UI验收。

## 2026-10-19 Android/JNI 与私钥认证：已实现并完成独立实测

Java/JNI 已接入密码与私钥认证，支持 Ed25519、ECDSA p256/p384/p521、RSA SHA-2；
未加密私钥和有界加密 OpenSSH/传统 RSA、EC PEM 走同一证明绑定的一次授权。新增 CSASv2
把精确私钥和口令条目绑定到实际远端；修复真实文件视图与角色期限兼容、首次 NEW 主机
写回撤销自身视图，以及最后一次库读取后的凭据交付检查。

最终 fresh JVM244/0/0/0、ARM QEMU1,244,010 checks、normal94任务/lint/Release/v2通过；
开发板四个精确方法各 OK(1 test)，覆盖密码、未加密/加密私钥及真实 NEW/CAS。测试包/进程
已清理，正式应用 hash 未变，未使用真实库或账号。普通 APK89eb6425… 未安装；加密PKCS8、
KBI/MFA、channel/PTY/forward/service/UI 和完整客户端 AC 继续开放。
[完整证据、格式与限制](verification.md#20261019-android-auth)。

接续顺序：从已完成的认证底层进入channels/PTY、转发与产品service/UI；KBI/MFA与重试策略、
rekey/压缩和更广服务器兼容仍需合同，不允许把partial/change当成功放行。当前父task保持
in_progress / implementation_complete=false。证据目录 `/tmp/ctos-ssh-android-auth-20261019.yqKxcIaN`；当前无设备测试包残留。
后续设备/真实库/账号操作按实际会话授权，不能把本轮合成授权扩成真实账号访问。

## 历史接续焦点：session channel / PTY（本会话开始时）

用户在认证阶段完成后确认采用此顺序，并要求先提交当前状态、提供续做 prompt。
这是下一增量的范围确认，具体 API、状态机、流控上限与安全合同仍待源码研究和冻结。
forward/service、KBI/MFA、加密 PKCS8 与更广兼容不并入首个 PTY 增量。

## 历史下一步行动（本会话开始时，已由上方取代）

1. **恢复原任务并核对基线** — 状态：未开始。
   - 前置条件/触发：新会话在 ctOS workspace 中接续，先读 `AGENTS.md` 与当前摘要。
   - 执行位置：`/home/wkyuu/cargo/repo/06-dedsec/projects/ctos`。
   - 命令：`python3 .trellis/scripts/get_context.py`；`python3 .trellis/scripts/task.py current`；`python3 .trellis/scripts/get_context.py --mode phase`。
   - 预期与判据：active task 为 `10-08-ssh-terminal-client`、状态 `in_progress`；读完原 PRD/design/implement/check、jsonl 及相关 spec，识别并保留新出现的用户改动。
   - 失败处理：任务指针不同先核对用户意图；临时证据消失只代表需重建，不代表认证尚未实现。
2. **研究并冻结认证后 channel / PTY 合同** — 状态：未开始。
   - 前置条件/触发：上一步基线一致，应用 `trellis-before-dev`；沿用 pinned OpenSSH 和现有会话 owner/取消边界。
   - 文件：`android/app/src/main/cpp/ssh_native_client.[ch]`、`ssh_session_jni.c`、`android/app/src/main/java/im/majo/ctos/SshNativeSession.java`、现有终端 bridge；同步 `design/ssh-terminal.md` 和 `.trellis/spec/backend/ssh-native.md`。
   - 预期与判据：明确仅 SUCCESS 可开 session、channel open/confirm/failure、pty-req/shell、stdin/stdout/extended-data、window/背压、resize、EOF/exit-status/close、cancel/deadline 和资源所有权；先冻结边界再编码。
   - 失败处理：不以 KEX complete/service-ready/PARTIAL/CHANGE_REQUIRED 放行；不为接续而绕过 proof、vault 撤销或 owner-thread 检查。
3. **实现合成闭环并验证后接入现有终端界面** — 状态：未开始。
   - 前置条件/触发：合同已冻结，复用现有 native/JVM/独立 fixture 测试入口；先本地 native/Java/JNI，再接 UI。
   - 文件/命令：原任务 `implement.md` / `check.md`；构建与 QEMU 命令沿用 [验证记录](verification.md#20261019-android-auth)，按新边界增加必要验证。
   - 预期与完成判据：合成 peer 中完成认证→申请 PTY/shell→输入命令并验证输出→resize→正常退出；拒绝、背压、EOF、取消、期限及 close 重试不泄漏 FD/channel/role/view。UI 接入后能在现有终端完成相同交互。
   - 失败处理：设备仅在届时当前授权覆盖时运行独立合成 fixture 并清理；不得自动安装普通 APK、访问真实库/账号或扩大远端操作。

## 历史接管验证（本会话开始时）

- `git status --short` / `git log -2 --oneline`：确认提交基线及新会话本地改动；不要覆盖未识别改动。
- `python3 .trellis/scripts/task.py current`：应为 `.trellis/tasks/10-08-ssh-terminal-client`，无需新建或重复 start 已进行中的任务。
- 若临时证据仍在，可在 workspace 重验 `sha256sum -c /tmp/ctos-ssh-android-auth-20261019.yqKxcIaN/source-final.sha256`；后续修改源码后旧哈希不再作为新版本的通过证据。
- 接管判据：原任务和认证基线一致、当前改动归属明确、已读新的 channel/PTY 计划。完成第一个动作后原地更新本摘要、当前焦点和行动状态。

## 历史记录说明

以下保留历次阶段事实与当时的下一步。旧标题中的“最新/当前”、未提交、缺少 ARM/JNI/auth
以及 `.git` 只读等描述仅对当时有效；当前状态以上方摘要、当前行动和最新验证记录为准。

## 上一阶段接管状态（2026-10-19，C-only password USERAUTH 本地验证通过）

- 原task/branch不变，in_progress / implementation_complete=false，未提交归档。
- 实现仅native_client.h/.c与native_client_test.c三文件：显式begin_password_auth和const
  result API，从ownedCSAS用户名及一次selected PASSWORD交付构造唯一请求，8192/noNUL/
  padding64；currentproof/output/rekey前后检查，scratch与outgoingplaintext按合同清理。
- 独立requestpending/await/final状态保留旧KEX/service/getter停止点；全部request排空后
  才接受SUCCESS/REJECTED/PARTIAL/CHANGE_REQUIRED，无自动重试/change/MFA/channel。
  Banner/methods/prompt/disconnect有界丢弃；const status无provider/I/O/clientmutation。
- 实现代理停止写入；checker正式只读无finding。main补强pending-BANNER正例：同一步
  banner+partialwrite+byteswritten>0、peer尚未收完整request，最终SUCCESS/provider1。
- 67新增真实加密连接场景；finalfocused7tasks13s，callback35/strictnative通过；normal
  94tasks19s（23exec71uptodate）、lint/Release通过。JVM221/0/0/0为15份保留报告，未新跑。
- 最终QEMU785537checks/exit0，binarySHA `db01d8f7aec9d7a8e9cf6f2e8477d8592f4a5041ad41e3d8025a1e4687d06829`。
  first780038与augmented785653另存；source-final3项和runtime-final6项hash验证通过。
- APK `983f2032f89adc6a71f4968c77219e455b6a6a9f3c51b12d101e321f39ad09ad`，v2/one signer、archive83/
  sixsessionJNI/forbidden0/仅生产SSHlib；未安装发布或复制dist。
- 证据 `/tmp/ctos-ssh-password-userauth-20261019.obv9zw5H/`；
  [验证](verification.md#2026-10-19--explicit-native-password-userauth-protocol)记录命令、三次binaryhash和覆盖限制。
- ENOMEM/hash/opaqueproof漂移、构包故障reset、padding/upstreamwipe与精确postparsecancel
  未动态注入；missingtarget复用旧gettercase。IGNORE/DEBUG/UNIMPLEMENTED未支持，不能
  宣称完整OpenSSH transport或压缩兼容。无设备/真实库账号/外部sshd操作。
- 后续需设计private-key parse/equal_public/sign与signature算法及selectedpassphrase/KBI
  能力，再完成Java/JNI service/auth、Android完整KEX/auth和channels/PTY/forward/service/UI。
  预期publicblob仍非实际私钥匹配证明，partial或change结果不能作为继续放行/取秘密依据。

## 最新接管状态（2026-10-19，selected credential handoff 本地验证通过）

- 继续原 task/branch，in_progress / implementation_complete=false，未提交归档。
- C/JNI 与 Java 共12文件完成本切片：client 从 owned CSAS 派生 kind/receipt，
  READY、成功 completion digest/current cycle/raw session ID SHA 前后复查；私有 scratch
  在 postcheck 通过后才复制 caller。attempt 后失败 terminal，不自动重试，无 legacy 回退。
- Java unique `(I[BJ[B)[B` 核对 owned claim/frozen selection/current host/KEX；所有 selected
  路径共享一次尝试，首次 native claim 后永久拒绝旧对象路径。AppSecrets receiver 前及
  callback 返回前检查 current invocation/proofs/cancel/clock；未添加 Session secret getter。
- 已修 JNI 分步异常检查、finish 时钟期间取消/重入与 Error 逃逸的 invocation 清理。
  Error 继续传播；source-reviewed result wipe 与动态 receiver wipe/owner cleanup 分列。
  正式 checker 无 remaining finding，代理停止写入，main 完成全部构建与执行。
- final focused66/0/0/0（57tasks33s）；normal94tasks66s（29exec65uptodate），
  fresh full JVM221/0/0/0、15reports，callback35、strict JNI/lint/Release/v2通过。
- 最终 QEMU323726checks/exit0，binarySHA
  `292d8c7e01cc2ac7bc125edcaece31e3a7226b00adfffb7e2a12c0fe2c50fd85`；
  first323722/SHA8f258cbc…另存。runtime-final.sha256 六项通过。
- APK `f329056e8ce108316dbd7d1674f4296b25ba286f0ce283030637cb33cba57a3f`；
  archive83/sixsessionJNI/forbidden0/仅生产SSHlib，未安装发布或复制dist。
- 临时证据 `/tmp/ctos-ssh-selected-credential-20261019.tCsurTHc/`；
  [验证](verification.md#2026-10-19--receipt-bound-selected-credential-handoff)记录命令、hash和覆盖限制。
- 无设备/真实 vault/账号操作。native cycle/raw-session drift、ENOMEM/hash 故障未注入；
  新 JNI 未在 Android 执行。接下来设计 USERAUTH_REQUEST/response 状态机；私钥必须实际
  parse/equal_public，selected passphrase/KBI 能力另行设计，不能凭 blob 声明推定匹配。
  完整 Android KEX/service/auth、真实 CAS/vault、PTY/forwarding/service/UI 与产品 AC 开放。

## 最新接管状态（2026-10-19，CSAS native metadata 已实现）

- 原task/branch保持 `10-08-ssh-terminal-client` / `antitrust`；in_progress /
  implementation_complete=false，未提交归档，保留既有脏改动。
- 9文件Java/JNI/C/test已实现canonicalCSASv1，编码来自adapterfrozenplan，含exactrole
  token/hop/nonce/binding和username/entry/path/keyIdentity/publicblob，max1MiB+2048。
  Native严格校验后ownedcopy/hash；所有新增可失败步骤在sockettransfer前。
- 新explicit authenticationSelectionReceipt为liveowner只读metadatafingerprint，允许
  preKEX/complete；Java核对expected32bytes且wipe临时array，不在open成功后自动调用。
  Legacy null兼容，旧binding对nonnull默认拒绝。Close成功清理ownedwire，失败可重试。
- Earlycheck两finding已修：directadaptercancel/closing新getterguard与localcancel映射，
  encoder scratch nullabletry/finally；before/duringcancel回归具区分力，正式复核无遗留。
- Focused56tasks28s、JVM53/0/0/0、strictJNI/lint；normal94tasks64s（29exec65uptodate），
  freshJVM208/0/0/0、15reports，view/native/callback24/production/Release/v2通过。
- 最终ARM QEMU **253155checks/exit0**；binary SHA256
  `5c18eac4e893e5d346e0afafec8174c2f98ef517d47486a9117c8bb5a09c6821`，公开API30
  Bionic与runtime-final.sha256一致。初轮253110/binary另存，不能混用hash。
- APK `bda806ca9670f51cdae1160d4615ec59098788888d550268691cca99a2e41ccf`，v2，
  archive83/sixsessionJNI/forbidden0/SSH仅生产lib；未安装发布或复制dist。
- 证据 `/tmp/ctos-ssh-native-selection-20261019.4F7DmbUU/` 包含first/finalbinary、
  runtime-final.sha256、qemu/buildlogs、focused-jvm.json、packaging.json、signature.log；
  [验证](verification.md#2026-10-19--csas-native-selection-metadata-and-receipt)含完整命令与限制。
- Cgetterwrongthread仅复用callbackgate+源码，新getter动态测env；malloc/hashfailure和
  accepted1MiBblob未注入。修改后JNI未Android执行；无设备/真实vault/账号操作。
- 下一切片为receipt-bound native credentialcallback与currentacceptedKEX/service-ready
  联合核对，再实现auth请求。Publicblob仅声明，需真实privatekeyparse/equal_public；
  selectedpassphrase/KBI及vault key:identity能力另行设计，Android与产品AC继续开放。

## 最新接管状态（2026-10-18，managed auth selection 已实现）

- 仍为 `10-08-ssh-terminal-client` / `antitrust`，未提交/归档，完整task
  `in_progress` / `implementation_complete=false`；保留原有脏改动。
- 新 `SshAuthenticationSelection` 固定exactroleobject、explicitstrictUTF8username与
  PASSWORD entry或PRIVATE_KEY path/keyIdentity/expectedpublicblob。exactobject为managed
  receipt；role.binding不含nonce，不能以其string equality替代exactrole检查。
- adapter.selectAuthentication须在owner、任意callback/issuedclaim前one-shot调用；
  failedopen不重开setup。selected byteoverload要求同selection，legacykind-only拒绝；
  unselectedlegacy合同保留。provider直接bridge.openSelection，不调用旧requestsource；
  Request保留selection，finalEffectGuard重复固定资源/role/host/KEX/operation检查。
- 7个Java文件实现与正式checker完成，无finding。新增12tests；focused46/0/0/0和lint
  通过，normal91tasks57s（26exec65uptodate），fresh全量JVM201/0/0/0、15reports，
  pinnedproduction/strictnative/Release/v2/包装检查通过。
- APK SHA256 `e6c4d58b28537e5b14a351ea1a35b4d72d64781dd34c05e0dea4d452ef57470b`，
  archive83/fiveJNI/forbidden0/SSH仅生产lib；未安装/发布或复制dist。34个C/JNI源码hash及
  上轮ARMtestbinary不变，production builder强制重生archive/lib，不能要求生成物hash不变。
- 临时证据 `/tmp/ctos-ssh-auth-selection-20261018.y8DyzgNU/` 含focused/normal-build.log、
  focused-jvm.json、native-before.json、packaging.json、signature.log。详细命令与hash见
  [验证](verification.md#2026-10-18--managed-immutable-ssh-authentication-selection)。本轮没有QEMU或设备动作。
- 覆盖限制：selected专用mid-returncancel/reentry没有新测试，复用同一pipeline旧回归。
  expectedpublicblob只是声明，未解析privatekey/equal_public；selection未序列化到C/JNI。
- 下一切片应设计native selection receipt/explicitusername与当前acceptedKEX/service-ready
  绑定，再发送认证请求。selected passphrase/KBI保持拒绝；passphrase的key:identity与
  role target不同，需要单独的vault能力合同，不改AppSecrets/VaultOperation绕过。
  Android完整KEX/service/auth、真实CAS/vault、PTY/forward/service/UI与产品AC仍开放。

## 最新接管状态（2026-10-18，C-only userauth service 已实现并实测）

- 当前 task/branch 仍是 `10-08-ssh-terminal-client` / `antitrust`；未提交/归档，
  `implementation_complete=false`。保留本轮以前全部未提交改动。
- 六个 C 文件已完成实现与正式检查代理复核，无 remaining finding：新增显式
  `begin_userauth_service` / `userauth_service_ready` 与 callback `check_live`。
  后者复用 owner/env/role/cancel/reentry/deadline validation，不调用 provider target。
- 原 `complete()`/Java/JNI 保持 KEX-only；service begin 与 active step 校验唯一成功
  completion cycle 和当前 guard。请求排空后才接受 exact/payloadless/empty-string
  service reply，ready 后停止；KEX20–49 dispatch 禁用，畸形/越阶段/rekey terminal。
- 加密 synthetic KNOWN、NEW/CAS peer 实际解密一个固定 service request；全部正反例、
  liveness/环境恢复和 close retry 在 ARM QEMU/Bionic 通过，credential spies始终为零。
  direct cycle mismatch 未动态注入（ssh 指针 opaque），该分支仅源码复核。
- focused7 tasks / 14s；normal **94 tasks全部重跑 / 1m24s**，fresh JVM189/0/0/0、
  callback24、view/native/production gates、strict native/三ABI Release/lint通过。
- 完整重建后的最终 binary SHA256
  `3fcaee6aab8647adbdfd4af0cf4ce4f18ab6387d86b72ea7f687bc4b02079560`，实际
  QEMU **246,808 checks / exit0**。初轮246,974/checks及旧binary证据另存，不混用hash。
- APK SHA256 `8cd6da66d836255341b81dd30577b84a85998727865a84f74154f28c8190f4a6`，
  v2 pass，archive83、五sessionJNI、forbidden0、SSH只生产lib；未安装/发布或复制dist。
- 临时证据 `/tmp/ctos-ssh-userauth-service-20261018.uFA94Ngt/` 含 `.final` binary、
  runtime-final.sha256、qemu-final.log、normal-build.log、packaging.json/signature.log；
  Bionic仍来自原公开API30 runtime。复现和证据边界见 [验证](verification.md#2026-10-18--native-post-kex-userauth-service-negotiation)。
- 本切片无设备/真实库/账号/Root/系统设置操作。下一工作先冻结 wire username 与不可变
  credential-selection/entry-or-key/public-key binding，再实现 typed authentication；
  另需 Java/JNI service 接入与 Android完整KEX/service/auth验证。不能从 service-ready
  推定已认证、typed credential、PTY/forward/service/UI 或产品 AC 完成。

## 最新接管状态（2026-10-18，Android JNI 分片/EOF 实测通过）

- 用户报告设备恢复；`.14` 已确认 AIO-3568J / API30 / arm64-v8a / RUNNING_UNLOCKED。
- 复核原精确方法后，用当前 production 修复重建独立 fixture：112 tasks / 20s 成功，
  lint/Java/native packaging、v2 同证书/独立包名/target 通过；没有修改源码。
- 新 main SHA256 `eb2349b9da92d2ade7e55008db21a4ebf5a95de3c2344b6cd6708fed322c5bc2`；
  test SHA256 `d0634aa524053b14a94aefe9eec0ad12a3749a5140537fd7b9406c1c0503ee87`。
- 原精确 `SshNativeSessionPlatformTest#syntheticNativeSessionHandle` **OK (1 test)，
  0.202s，method status0 / runner code-1 / host exit0**。两个 session 的分片 banner、
  完整初始 KEXINIT 输出读回、KNOWN 核验/revoke，以及独立 EOF terminal/close 均实跑。
- 两包随后卸载，包/进程均无残留；正式 APK 前后仍为 `3bc762d9…`，未安装正式包，
  未访问真实库/账号或改变系统设置。命令和日志见 `design/verification.md` 最新节。
- 原锁定环境导致的 JNI 分片/EOF 待测关卡已关闭；下文同日以前的锁定/not-run 为历史。
  **完整 Android KEX、认证及产品 AC 仍开放**，QEMU native KEX 证据继续分开解释。
- 下一工作仍是既有 `next-userauth-20261017.md` 的本地 post-KEX service negotiation
  切片及 typed auth identity-binding 合同；尚未实施，不使用真实账号。Task 保持
  in_progress / implementation_complete=false，未提交或归档。

## 最新接管状态（2026-10-17，ARM native QEMU 已通过）

- 当前 task/branch 仍是 `10-08-ssh-terminal-client` / `antitrust`，未提交/归档。
- 宿主 `/usr/bin/qemu-aarch64` 10.0.13 可用；hako 内仍无 QEMU。只读拉取 `.14` 公开
  linker64 + libc/libdl/libm/libz 到 `/tmp/ctos-ssh-arm-runtime-20261017.O81ADtu5/` 后，
  实际运行 ARM production-native regression，而非仅 cross-link。
- 四处修复：role-policy errno 稳定 `EACCES`；固定 hash upstream banner incomplete reset；
  KEX completion 等本端 output 排空；failed-open 无论 attach 是否完成都解绑 KEX guard。
  检查代理追加 2/64-byte peer 完成/EXT_INFO 与 soft RLIMIT1 下真实 EMFILE/同资源重试回归。
- 最终 strict focused build 6 tasks / 12s 成功，actual ARM QEMU **65,838 checks / exit0**。
  executable SHA256 `5b030e73a72a67a08d381abd74c754703d46dd60036ebe1f05933110cde96fb8`。
  normal gate 94 tasks / 21s 成功（35 executed / 59 up-to-date）；JUnit 报告 189/0/0/0
  是 `UP-TO-DATE`，未本轮重跑。archive=83、五 JNI exports、forbidden stock caller=0。
- normal APK SHA256 `f3327427ce0af90ff7a8a5ea3b5cbba0ac7a7f1c396853c2fd247a45a2328dbd`，
  v2 通过，SSH 仅生产 `libctos_ssh_files.so`，未安装/发布。baseline tree 保持固定 hash。
- `.14` 仍 `RUNNING_LOCKED`；本次接续没有重装测试包，仅只读检查和复制公开 runtime。
  下节中的 fixture APK 是修复前版本；解锁后**先重建 fixture main/test，再记录新 hash**，
  运行原精确 opt-in 方法并清理，不能用旧 APK 冒充本轮 production 修复的 JNI 证据。
- 下一本地切片研究已在 task research `next-userauth-20261017.md`：先显式 post-KEX
  ssh-userauth service negotiation；typed password/private-key 仍需 wire username 和不可变
  credential selection 绑定合同，尚未实施，不使用真实账号。
- 完整任务仍 `in_progress` / `implementation_complete=false`。QEMU 证明 native 合成 KEX，
  不证明 Android JNI、真实 AppSecrets CAS/vault、认证、PTY/forward/service/UI 或外部 SSH。
- 复现命令、patch/source/runtime hashes、失败与修复记录见 `design/verification.md` 最新节；
  `/tmp` 证据为临时文件，若不存在须重建，不能推定仍可用。

## 最新接管状态（2026-10-17，本地通过 / 设备未执行）

- 当前 task 已恢复为本 session 的 `10-08-ssh-terminal-client`，未创建新 task。
- `SshNativeSessionPlatformTest#syntheticNativeSessionHandle` 新增两个独立 production
  session：预算 3 的分片 banner / 完整初始 KEXINIT 输出读回，以及 peer 半关闭后的两次
  public `SSH_SESSION_STEP` / view release / idempotent close。KNOWN 核验与显式 revoke 保留。
- 实现/检查代理已复核；fixture compile/assemble/lint 为 112 tasks / 20s 成功，v2 同证书。
  main SHA256 `78f71309dea6fc39804b9bc591c3db4a32dd291cdab5ddcd50fae02ff1e1bbf4`；
  test SHA256 `d0634aa524053b14a94aefe9eec0ad12a3749a5140537fd7b9406c1c0503ee87`。
- 用户本轮授权 `.14` 独立合成测试并清理；目标身份正确且两包安装成功，但系统报告
  `Package ... is not encryption aware!`，`dumpsys user` 为 `RUNNING_LOCKED`。测试未启动，
  没有 instrumentation pass/fail。用户选择“暂时无法解锁，保留本地结果”。
- 已精确终止自有挂起 instrumentation 进程并卸载两包；两包 `pm path` / `pidof` 为空。
  正式 APK 前后 SHA256 均为 `3bc762d9e6463ab9612393b9382923f4a29f613ceec3dbe4d9159aae0078f252`。
- 下一步：用户手动解锁后，在当前授权范围内重装这两个 fixture APK，运行精确 opt-in
  方法并清理；使用 host timeout 给 `am instrument -w` 加上外部时限，避免启动被拒绝后
  无限等待。不得通过 directBootAware、改锁屏或系统设置来绕过环境关卡。
- task 仍 `in_progress` / `implementation_complete=false`；本轮未提交。旧段落中的“当前”
  APK 和通过结果属于对应日期；详细命令/边界见 `design/verification.md` 最新节。

## 当前接管状态（G3.8 Android ARM/JNI KNOWN host-proof，2026-10-16）

- 最新补充的 Android JNI bounded EAGAIN：production session 在 socket ownership 转移后对空
  synthetic peer 调用一次 `step()`，断言 `bytesRead=0`、
  `inputAppended=0`、`readWouldBlock=true`，不约束同一 turn 的
  `bytesWritten`。随后 KNOWN `verifyHostKey`、revoke、close 仍通过。
  当前授权板精确 instrumentation 为 `OK (1 test)`、status 0、0.11s；最新 fixture
  main/test hash 为 `79776aad22be067e057fb2abd2a9af74bc365f890de44b1d3ba689c18d9560d3`
  与 `fc71429d9e06d33334cc51cbb9fa64a7b8f672f2bb8752922052aac5ec6dc71e`。
- 最新 Android runtime 增量：fixture 把 managed `ssh/known_hosts` 行改为同一
  OpenSSH wire public-key blob 的无换行 Base64，并在 production
  `SshNativeSession.open` 后、view revoke 前调用
  `session.verifyHostKey(key)`。当前授权板 `192.168.9.14:5555`（AIO-3568J、
  Android 11/API 30、arm64-v8a）精确运行该方法为 `OK (1 test)`、status code 0、
  0.089s；测试后两个 fixture 包已卸载，`pm path`/`pidof` 均为空。
- 本轮 fixture APK hash：main
  `1e3a10c709fed7d514a90e247494c61345da6a0f71dd142c17157f34ea4e9de6`；
  androidTest
  `2b9f80678220d89a7a67e23197bd5f85c1eee7543763bfb895da8d31a801f7e7`。
  这只证明 matching KNOWN host-proof 的 Java → JNI → native issuer/callback ARM 边界，
  不证明 KEX、packet、userauth 或完整 SSH runtime。
- native-owned session/view boundary 已实现：`ssh_view_handle.[ch]` 用 opaque token
  登记 native-owned public view，borrow/revoke 有延迟销毁；`ssh_native_client` 在成功
  open 后拥有 parsed host key、managed-view borrow 和 OpenSSH state；Java token 从不被
  转成 C pointer。
- `ssh_session_jni.c` 已是实际 bridge：64-slot monotonic session registry、owner
  pthread 检查、bounded key/fingerprint/string copy/wipe、八个 step scalar 和可重试
  close/destroy。`SshPublicFileLease` 的 revoke 在失败时保留 token 以便重试。
- host/normal gate：view handle `borrow/revoke/stale` 全通过，native client
  `gate=pass runtime=not-run reason=qemu-aarch64-unavailable`，production
  `gate=pass fixture=excluded`，callback=20，JUnit **189/0/0/0**，strict compile/
  lint/三 ABI Release/assemble 通过，production archive=83，APK SHA256
  `7ccac2fb8f705edd761d34574b9cead4b9e2296864dfe5568d2e9b1105fd849f`；五个 JNI
  symbols 可见，APK 内 SSH 相关 native 库只有生产 `lib/arm64-v8a/libctos_ssh_files.so`。
- `.14` 当前授权仅用于独立 `secretsFixture`：
  `SshNativeSessionPlatformTest#syntheticNativeSessionHandle` 与
  `SshFileViewPlatformTest#syntheticProtectedFileViews` 各 `OK (1 test)`，status code=0；
  fixture UID 10193/10194 已卸载，fixture package/process 无残留，正式 APK 安装后 hash
  为 `3bc762d9e6463ab9612393b9382923f4a29f613ceec3dbe4d9159aae0078f252`。
- G3.8 follow-up 修正 `ssh_native_client_step` 的错误传播：managed view deadline 到期保留
  `ETIMEDOUT`，revoke/invalid view 保留 `EACCES` 或底层精确 errno；新增空 peer EAGAIN、
  半关闭 EOF=`EPIPE`/后续 `EALREADY`、短 deadline 回归。focused host task 已 strict
  cross-link 通过，但 ARM binary 仍因无 qemu 未运行。
- task `10-08-ssh-terminal-client` 仍 `in_progress`，`implementation_complete=false`。
  本轮新增证明 host-only bounded step transport/error semantics、`KNOWN` host-evidence
  contract、`CHANGED`/`REVOKED`/`CERTIFICATE`/project-owned `INVALID` fail-closed
  classifications，以及合法 no-match `NEW` 的 candidate → `CAS_COMMITTED` precommit 边界；
  完整 ARM packet/KEX runtime、userauth/typed credential、PTY/forward/service/UI、qemu、真实
  vault 和外部 SSH 仍未完成。
- 另新增 fragmented server banner 后的 bounded client banner/KEX output accounting 回归；
  该案例也只完成 strict AArch64 cross-link，未执行 ARM runtime。
- 当前又新增 bounded `KNOWN` host-evidence 回归：synthetic managed `ssh/known_hosts` 含生成
  的 public key，production `ctos_ssh_native_client_verify_host_key` 对 exact key 成功并只
  调用一次 host evidence；另一把生成 key 在 pinned fingerprint guard 处返回 `EACCES`，
  callback 次数不增加。该项同样只有 strict AArch64 cross-link 证据。
- 随后新增 `CHANGED` host-policy 回归：offered key 的 pinned fingerprint 保持有效，但
  managed `known_hosts` 放入另一把生成 key；测试直接观察 issuer 返回 `HOST_CHANGED` 和
  digest metadata，再确认 production verifier 返回 `EACCES`、无 candidate handoff 且不调用
  host evidence。该项同样未执行 ARM binary。
- 随后新增 `REVOKED` host-policy 回归：matching offered/pinned key 在 synthetic managed
  `known_hosts` 中标为 `@revoked`；测试直接观察 issuer 返回 `HOST_REVOKED` 和 key/
  fingerprint/digest metadata、无 candidate handoff，再确认 production verifier 在 host
  evidence 之前返回 `EACCES` 且不调用 host evidence。该项同样未执行 ARM binary。
- 当前又新增 `CERTIFICATE` 与项目自有 `INVALID` host-policy 回归：前者使用生成并签名的
  有效 host certificate，后者使用截断 managed known_hosts 行加合法记录；issuer 直接检查
  classification、key/fingerprint/digest metadata 和无 handoff，production verifier 均在
  host evidence 前返回 `EACCES`。`INVALID` 由 `hostkeys_foreach_file(...,
  HKF_WANT_PARSE_KEY, ...)` 的 `HKF_STATUS_INVALID` 预检产生，不冒充上游 `HostStatus`；合法
  no-match 仍是 `NEW`。focused 与 normal full gate 均已通过，native runtime 仍未执行。
- `test_step_expired_view_preserves_errno` 与独立的 client deadline case 使用不同期限，
  分别覆盖 managed-view `ETIMEDOUT` 传播和 client terminal 分支。
- 当前 NEW/CAS follow-up 使用空 synthetic managed `known_hosts`：production verifier
  逐字节验证 replacement body、candidate metadata/digest、candidate-before-evidence 顺序
  与 `CAS_COMMITTED`；evidence rejection 会单次 abort candidate、terminalize client，后续
  `step` 为 `EALREADY`，close 仍可重试。focused 与 normal full gate 已重新通过；APK
  SHA256 为 `7f0bd0f14ec2a5ae9175ee340512acfdeaf0aa76f843834d1a3c87107c97993b`。
- 当前 synthetic KEX completion follow-up 使用同一 production caller 和匹配 managed `KNOWN`
  key，由非阻塞 OpenSSH client/server socketpair 走到真实 `kex->done`。host evidence 恰好一次
  且先于唯一 KEX-complete callback；cycle=1，callback session digest 非零并与 peer session
  ID 的 SHA-256 一致；client completion 后再次 `step` 为 `EALREADY`，server 端 EAGAIN/短写也
  有断言。focused 与 94-task normal full gate 通过；APK SHA256 为
  `7195a67fad84185714d00cbb2bbc899fbdf788b61f722282fae8b99ae8ba5f69`，ARM runtime 仍因
  qemu-aarch64 unavailable 为 `not-run`。
- 当前 NEW/CAS full-KEX follow-up 把空 managed `known_hosts` 接入同一真实 packet loop：candidate
  handoff、`NEW` evidence=`CAS_COMMITTED`、KEX-complete 按顺序各一次；candidate/evidence
  role/hop/port、ID/classification/key/fingerprint、replacement body、双 digest、cycle=1 和
  peer-bound session digest 均精确断言，completion 后 step 为 `EALREADY`，server/client fd
  二次 close 为 `EBADF`。focused 与 94-task normal full gate 通过；APK SHA256 为
  `7ccac2fb8f705edd761d34574b9cead4b9e2296864dfe5568d2e9b1105fd849f`，ARM runtime 仍因
  qemu-aarch64 unavailable 为 `not-run`。

> 下列早期条目保留为 G3.6 及更早阶段的历史上下文；遇到与本节冲突的“fail-closed JNI”
> 或 clean-worktree 描述，以本节和 `design/verification.md` 的 G3.8 记录为准。

- 目标与完成判据：在 ctOS 同一个 APK 内完成受管 OpenSSH SSH 客户端；production caller、事件循环、主机信任/凭据、认证、PTY、转发、服务/UI 与 Android 运行时联合验收均通过，且所有未测边界有明确证据。
- 范围与权限：只在 `projects/ctos` 内工作。允许 `.14`（`192.168.9.14:5555`）上的合成/隔离 SSH 测试和已授权的真实秘密库操作；不使用真实外部 SSH 账号，不把账号、密码、token、私钥写入代码、任务或日志；不 push。
- 历史状态（G3.6）：Java owner seam 已提交为 `297c328`（父 checkpoint `df73a64`）；当时 normal APK 已包含 production native OpenSSH closure/C caller 与 Java JNI symbols，但 JNI native binding 仍 fail-closed。
- 已完成：managed `known_hosts` AppSecrets/CAS provider、一次性 native→managed candidate handoff、同步 JNI callback/role bridge、hostfile issuer、production PIC OpenSSH source/object closure、`ssh_native_client` C boundary、pinned caller fixture、`SshNativeSession` Java owner/claim/retry seam、host/Gradle/Release/normal-APK exclusion 质量门。
- 历史下一步（G3.6）：冻结并实现安全的 native-owned handle/view snapshot/borrow 生命周期，再替换 fail-closed JNI binding；该步骤现已完成，剩余工作见当前接管状态。
- 阻塞与停止条件：不得把 AArch64 cross-link、fixture `ssh_api` 内存流、host/fixture runner 或历史 Docker smoke 当作 Android runtime/production SSH pass。没有 runtime 生命周期、清理、授权范围或外部账号证据时停止，不接真实外部 SSH 账号。
- 工作状态：workspace `/home/wkyuu/cargo/repo/06-dedsec/projects/ctos`；branch `antitrust`；G3.7 代码、测试、设计和 spec 已提交 checkpoint `8c0a615`；G3.8 follow-up 当前有未提交代码/测试/文档变更，task commit 指针仍为 G3.7；journal sessions 27-33 已记录。
- 提交限制：本环境 `.git` 为只读挂载，`git add` 无法创建 `../.git/index.lock`；本轮没有 commit/push，工作区改动已通过 `git diff --check`，可在可写 git 环境直接提交。
- 环境：Android module 在 `android/`；hako/Docker 调用可能需要提升权限；容器内没有 `qemu-aarch64`，因此 AArch64 fixture 只能 link，不能在本机 runtime 执行。
- 最近更新：2026-10-16
- 最后验证：G3.8 focused 与 normal full gate 均 `BUILD SUCCESSFUL`、native
  `gate=pass runtime=not-run`；本轮 APK SHA256 为
  `7ccac2fb8f705edd761d34574b9cead4b9e2296864dfe5568d2e9b1105fd849f`；另有
  上述 fixture-only Android KNOWN host-proof runtime 通过。

## 目标 / 资产清单

| 项 | 值 / 描述 | 状态 | 证据来源 |
| --- | --- | --- | --- |
| 当前 work commit | `8c0a615`（G3.7 native-owned SSH session handle/JNI checkpoint；父 `297c328`） | 确认 | `git show --stat 8c0a615` |
| managed provider | `SshManagedKnownHostsProvider` + `AppSecrets` guarded CAS | 确认 | `android/app/src/main/java/im/majo/ctos/SshManagedKnownHostsProvider.java`; `design/verification.md` |
| candidate handoff | exact role/hop/target/connection/host/port/key/digest/body，一次性、清零 | 确认 | `SshKnownHostsCandidateHandoff.java`; `ssh_callback_adapter.[ch]`; `design/ssh-terminal.md` |
| hostfile issuer | pinned upstream parser + project-owned stream writer，production source and fixture evidence | 确认 | `ssh_hostfile_issuer.[ch]`; production/G3 runners |
| pinned caller fixture | real `ssh_api` socketpair packet loop，partial I/O/EAGAIN/EOF/cancel/deadline | 确认 | `ssh_pinned_client.[ch]`; `research/android-hostfile-issuer-build.sh` |
| normal APK | production `libctos_ssh_files.so` with PIC OpenSSH closure；pinned fixture caller excluded | 确认 | `design/verification.md`; `android/app/build.gradle` |
| Java/native session owner | `SshNativeSession` owner-thread/claim seam；真实 JNI registry、native key/view borrow 已接入；synthetic open/revoke/close 与 KNOWN host-proof runtime 已通过 | 部分确认 | `SshNativeSession.java`; `ssh_session_jni.c`; `ssh_view_handle.[ch]`; `design/verification.md` |
| production caller | native C `ssh_native_client` owns parsed host key and view borrow after open; bounded KNOWN host-evidence/fingerprint regression and fixture ARM KNOWN proof pass；qemu native KEX runtime not-run | 部分确认 | `ssh_native_client.[ch]`; `design/verification.md` |
| device | `192.168.9.14:5555`，仅当前授权合成范围 | 确认 | `AGENTS.md`; task `meta.implementation_approval` |

## 关键事实与发现

- **当前阶段不是 production SSH 完成** — 状态：确认
  - 证据：`design/ssh-terminal.md` 顶部的 G3.7 边界；`.trellis/tasks/10-08-ssh-terminal-client/task.json` 的 `implementation_complete=false`。
  - 最后验证：2026-10-15。
- **G3.7 native-owned JNI handle 已通过 host/fixture/device synthetic gate** — 状态：确认
  - 证据：`ssh_view_handle.[ch]`、`ssh_native_client.[ch]`、`ssh_session_jni.c` 与两个平台测试；normal JUnit=189/0/0/0，view/native/callback host gate 通过，`.14` 两个独立 instrumentation 方法各 1 项 status=0。
  - 最后验证：2026-10-15。
  - 备注：设备方法只证明 synthetic open/revoke/close 和 public-file view matrix；不证明完整 protocol/auth。
- **G3.5 production source/object/native gate 已通过，但 ARM runtime 未执行** — 状态：确认
  - 证据：`build/ssh-native-g3/run.aqN1F2bD/`，结果为 `gate=pass runtime=not-run normal_apk=fixture-caller-not-wired`；production archive 为 83 个 PIC members，本机缺少 `qemu-aarch64`，fixture caller 仍排除 normal APK。
  - 最后验证：2026-10-15。
- **正常 Release 质量门通过** — 状态：确认
  - 证据：`:app:testSshViewHandleHost :app:testSshCallbackAdapterHost :app:testSshNativeClientHost :app:testReleaseUnitTest :app:compileSshFiles :app:lintRelease :app:assembleRelease`；189 tests、0 skipped、0 failures、0 errors；本次重跑 APK SHA256 `7f0bd0f14ec2a5ae9175ee340512acfdeaf0aa76f843834d1a3c87107c97993b`。
  - 最后验证：2026-10-15。
- **生产拓扑选择为同进程** — 状态：确认
  - 证据：task `prd.md` / `design.md` 的用户决策；不得将 fork/exec child 或 fixture control channel 升级为生产 broker。
  - 最后验证：2026-10-15。
- **生产 OpenSSH source/object closure 已通过 link gate** — 状态：确认
  - 证据：`android-production-openssh-build.sh`、`build/app/generated/sshProduction/arm64-v8a/`；83-member PIC archive、PKCS#11/SK closure、stock high-level symbol exclusion、normal shared-library link 均通过。
  - 备注：这是 source/object/link 证据，不是 Android runtime 或完整 SSH 产品验收；G3.8 新增
    step 案例包含 banner/KEX output accounting，但因无 qemu 仍未执行。

## 假设台账

- **OpenSSH 将以正常 APK 内的受控 native engine 形式接入** — 状态：待验证
  - 证实判据：normal `compileSshFiles` 能在不引入 CLI/argv/pathname/fork/exec/fixture transport 的前提下链接批准的 OpenSSH client/KEX closure，并通过 ABI、符号、生命周期和 APK 检查。
  - 证伪判据：只能通过子进程/不受管 pathname/无法提供 callback 的方式运行，或无法形成可维护的 Android source/object closure。
  - 当前倾向：沿用用户已确认的同进程 native event loop，但需要先完成 source/object 复核。

## 决策与权衡

- **同进程 native event loop**：选择同进程；否决 fork/exec child handoff。原因是 Vault、host-key CAS、typed credential 和生命周期必须由 ctOS callback 精确绑定。
- **fixture 与 production 分离**：保留 `ssh_pinned_client` 在 opt-in runner；在 production caller 未闭合前，不把它加入 normal APK。
- **提交策略**：本轮提交代码、测试、研究、设计/spec 和任务记录为单一 checkpoint；不归档未完成 task，不 push。

## 当前焦点（G3.8 follow-up）

- **Android runtime/JNI session integration** — 状态：窄增量已通过
  - 已有：normal APK 的 PIC OpenSSH archive、native C caller、native-owned key/session/view contract、真实 JNI symbols 和 `.14` synthetic lifecycle pass。
  - 卡点：hako 无 qemu；设备尚未覆盖 packet protocol/host proof/EOF/deadline/auth，不能外推为完整 SSH runtime。
- **Bounded native step semantics** — 状态：回归已编码，cross-link 与 normal gate 已复验
  - 已有：EAGAIN zero-read/append、EOF terminal `EPIPE`/后续 `EALREADY`、managed-view deadline `ETIMEDOUT`，以及 fragmented banner 后的 bounded client banner/KEX output accounting。
  - 卡点：host binary 未运行；仍未覆盖完整 ARM packet/KEX runtime、host proof 和 Android JNI step runtime。
- **Synthetic packet/KEX host-proof boundary** — 状态：host cross-link 已通过
  - 已有：synthetic nonblocking server pump、真实 client `kex->done`、KNOWN evidence exactly once
    before KEX completion exactly once、cycle=1、session digest binding、completion 后 `EALREADY`，
    以及 client/server bounded partial I/O/EAGAIN 断言。
  - 卡点：无 qemu，ARM binary 未运行；尚未证明 Android JNI packet runtime、真实 managed CAS、
    userauth、PTY/forward/service/UI 或外部 SSH。
- **NEW/CAS full-KEX host-proof boundary** — 状态：host cross-link 已通过
  - 已有：空 managed body 的 candidate → `CAS_COMMITTED` evidence → KEX completion exactly-once
    顺序、精确 body/route/digest binding、peer-bound session digest、bounded EAGAIN/短写和
    completion 后 `EALREADY`。
  - 卡点：仍无 ARM/device packet runtime 或真实 managed CAS commit；认证、PTY/forward/service/UI
    和外部 SSH 未验收。
- **Bounded KNOWN host evidence** — 状态：回归已编码，focused cross-link 通过
  - 已有：受管内存 `ssh/known_hosts`、production verifier、完整 `KNOWN` callback result、exact
    key success 和错误 fingerprint `EACCES`/no-second-callback。
  - 卡点：host binary 未运行；本卡片历史上未覆盖的 CHANGED/REVOKED/CERTIFICATE/INVALID
    现已由后续窄切片补齐；完整 KEX host-proof runtime、认证和 Android JNI runtime 仍未覆盖。
- **Managed-key NEW candidate/CAS precommit** — 状态：host/normal gate 已通过，ARM runtime
  待验证
  - 已有：空 managed body 的 exact replacement、candidate metadata/digest、candidate-before-
    evidence 顺序、approved `CAS_COMMITTED`；evidence rejection 后 candidate 单次 abort、
    client `EALREADY` terminalization 和 close retry。
  - 卡点：AArch64 binary 未运行；尚未覆盖完整 host proof/NEWKEYS、真实 managed CAS、认证和
    Android JNI step runtime。
- **Managed-key CHANGED classification** — 状态：回归已编码，focused cross-link 通过
  - 已有：offered/pinned key 有效、managed key 不同；issuer 直接返回 `HOST_CHANGED`，production
    verifier `EACCES`，无 candidate handoff/host evidence。
  - 卡点：host binary 未运行；本卡片历史上未覆盖的 REVOKED/CERTIFICATE/INVALID/NEW CAS
    precommit 现已由后续窄切片补齐；仍未覆盖 ARM host-proof runtime、认证和 Android JNI runtime。
- **Managed-key REVOKED/CERTIFICATE/INVALID classification** — 状态：实现、check 与
  normal full gate 已完成；native runtime 仍为 `not-run`
  - 已有：matching `@revoked` managed entry；issuer 直接返回 `HOST_REVOKED`，production
    verifier `EACCES`，无 candidate handoff/host evidence。
  - 卡点：host binary 未运行；仍未覆盖 ARM host-proof runtime、认证和 Android JNI runtime。
- **完整 SSH client** — 状态：开放
  - 卡点：userauth/typed credential、PTY、forward、foreground service/UI、设备和外部 SSH 均未验收。

## 下一步行动（G3.8 之后）

- **完成 G3.8 full quality gate** — 状态：已完成
  - 运行 normal `testSshViewHandleHost`、`testSshNativeClientHost`、callback host、JUnit、
    strict `compileSshFiles`、lint、三 ABI Release、assemble、APK/ELF scan 和 `git diff --check`。
  - 结果：JUnit 189/0/0/0、archive=83、新 APK `7ccac2fb8f705edd761d34574b9cead4b9e2296864dfe5568d2e9b1105fd849f`；不把 focused AArch64 cross-link 当 ARM runtime。
- **继续协议/runtime coverage** — 状态：进行中
  - bounded banner/output、KNOWN/CHANGED/REVOKED/CERTIFICATE/INVALID、NEW candidate/CAS、KNOWN
    KEX 和 NEW/CAS full-KEX slices 已完成；下一窄切片转向 ARM/device 可执行性或 typed
    userauth，仍不得使用真实外部 SSH 账号或凭据。

- **提交 G3.7 checkpoint** — 状态：已完成
  - 结果：`8c0a615` 已提交本轮 native handle/JNI code、tests、spec/design/task 变更；未 push，task 仍保持 `in_progress`。
- **实现 Java/JNI production session owner** — 状态：G3.6 历史完成，G3.7 native bridge 已完成
  - 前置条件：复用现有 role/view/callback contracts，不新增明文文件、CLI、fork/exec 或外部账号。
  - 结果：Java owner/claim/cleanup seam、native session registry、parsed key ownership、view borrow/revoke 和 synthetic Android lifecycle 已完成；未把该结果误报为完整 SSH。
  - 完成判据：G3.7 host/normal/device synthetic evidence 已满足；protocol/auth/runtime coverage 仍开放。
- **冻结 native-owned JNI handle/view contract** — 状态：已完成当前安全增量
  - 前置条件：明确 `sshkey` ownership、view snapshot/borrow/revoke、socket/FD ownership、callback environment 和 destroy retry 的 native API。
  - 执行位置：`android/app/src/main/cpp/ssh_native_client.[ch]`、`ssh_session_jni.c`、对应 Java binding/test/spec。
  - 结果：JNI 不再把 Java token 当 C pointer；host、normal link 与一套隔离 Android synthetic runtime 已分别记录。
  - 完成判据：host view-handle revoke/stale 测试和 Android open/revoke/close 测试通过；更广泛 ARM protocol runtime 仍未覆盖。
- **Android `.14` runtime** — 状态：G3.7 synthetic lifecycle 已完成，协议 runtime 待后续
  - 前置条件：用户当前授权的合成/隔离范围仍有效；APK、测试包和清理方案固定。
  - 执行位置：目标 `192.168.9.14:5555`，仅使用本机拥有的随机 listener/Docker pair 或等价合成 endpoint。
  - 完成判据：当前只证明该 APK/ABI 的 synthetic lifecycle；后续若覆盖协议必须继续固定测试包、清理和授权，不外推真实账号或完整产品验收。
  - 失败处理 / 停止条件：出现真实账号、非 owned endpoint、Root/剪贴板/系统设置或真实用户库影响时立即停止。

## 接管验证

- 环境检查：`git status --short` → G3.8 文档/代码复验并提交 follow-up 后应为空；当前 G3.7 code/docs 由 `8c0a615` 保存，G3.8 尚未提交。
- 状态检查：`git show --stat --oneline 8c0a615` → G3.7 checkpoint；内容包括 native view handle、production client ownership、real JNI registry、Android synthetic test 与入口文档。
- 最小验证：focused `:app:testSshNativeClientHost` 与 94-task normal full gate 均
  `BUILD SUCCESSFUL`；再运行 `python3 -m json.tool .trellis/tasks/10-08-ssh-terminal-client/task.json`
  与 JSONL parse，保持 `in_progress`/`implementation_complete=false`。当前 normal gate 为
  JUnit 189/0/0/0、archive=83、APK `7f0bd0f14ec2a5ae9175ee340512acfdeaf0aa76f843834d1a3c87107c97993b`；
  `.14` synthetic session/view 各 1 项 status=0 是历史证据。
- 接管判据：G3.7 host/normal gate、G3.8 focused/full gate、JSON metadata 和 synthetic Android lifecycle 证据已读到；下一 agent 从 G3.8 继续协议/runtime coverage，不得把任何 cross-link/lifecycle gate 当完整 SSH pass。
- 接管后更新：完成 native handle、runtime 或改变授权/设备范围后，先更新本档案顶部摘要、关键事实、假设台账和下一步，再继续实现。

## 证据索引

- `design/verification.md`（cwd=ctOS，时间=2026-10-15）→ 支撑 JVM、Gradle、APK exclusion、fixture runner 和未测边界。
- `design/ssh-terminal.md`（时间=2026-10-15）→ 支撑当前阶段、G3.5 production C boundary 与 Android runtime 未验收边界。
- `.trellis/tasks/10-08-ssh-terminal-client/check.md`（时间=2026-10-15）→ 支撑本轮质量门和限制。
- `.trellis/tasks/10-08-ssh-terminal-client/research/android-production-openssh-build.sh`（时间=2026-10-15）→ 支撑 pinned PIC source/object closure、83-member archive 与 symbol gate。
- `.trellis/tasks/10-08-ssh-terminal-client/research/android-hostfile-issuer-build.sh`（时间=2026-10-15）→ 支撑 pinned manifest/patch/object/link/exclusion runner。
- `.trellis/tasks/10-08-ssh-terminal-client/task.json`（时间=2026-10-15）→ 支撑授权范围、当前 implementation phase 和 run ID。
- `android/app/src/main/cpp/ssh_native_client.c` 与 `android/app/src/test/cpp/ssh_native_client_test.c`
  （时间=2026-10-15）→ 支撑 NEW candidate/CAS precommit、single rollback、terminal `EALREADY`
  与 close retry contract。
- `./hako bash -lc 'cd android && ./gradlew :app:testSshNativeClientHost --rerun-tasks --console=plain'`
  （cwd=ctOS，时间=2026-10-15）→ `BUILD SUCCESSFUL`，production/native gate pass，runtime
  `not-run` because qemu-aarch64 unavailable。
- `./hako bash -lc 'cd android && ./gradlew :app:testSshViewHandleHost :app:testSshNativeClientHost :app:testSshCallbackAdapterHost :app:testReleaseUnitTest :app:compileSshFiles :app:lintRelease :app:assembleRelease --rerun-tasks --console=plain'`
  （cwd=ctOS，时间=2026-10-15）→ 94 tasks success、JUnit 189/0/0/0、archive=83、APK
  SHA256 `7f0bd0f14ec2a5ae9175ee340512acfdeaf0aa76f843834d1a3c87107c97993b`。
- `git show --stat 297c328`（cwd=ctOS，时间=2026-10-15）→ 支撑 G3.6 production Java owner/fail-closed JNI checkpoint 内容；父 G3.5 checkpoint 为 `df73a64`。

## 已修正 / 历史结论

- **“还没有 Java/JNI session orchestration”**
  - 修正于：2026-10-15。
  - 推翻依据：G3.6 `SshNativeSession`、`SshNativeSessionTest` 与 normal APK JNI symbol gate 已落地。
  - 现结论（历史 G3.6）：Java ownership seam 已存在；当时 production JNI 五个入口统一抛 `IOException`。G3.7 已替换该 placeholder；native host protocol runtime 仍尚未通过。

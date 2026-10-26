# ctOS Mainline

## 2026-10-26 最新：手机真实私钥登录与人工验收通过

最终042c6b73…修复Android16实际64KiB锁页与内部memfd chmod限制；同一PLR110保留
数据更新后，经正式ctOS选择原私钥、核对精确主机指纹并保存信任，实际登录目标及
whoami/非回显PTY标记通过。测试会话和临时资源已清理，用户人工验收并明确授权提交。
[完整证据](../design/verification.md#20261026-phone-ssh)。本轮提交已有私钥/config、
可选保存密码及兼容性修复；父规划未完成项保留in_progress，不新建或归档task。
以下相同日期条目保留各阶段历史，不覆盖本次最终手机结论或提交授权。

## 2026-10-26 最新优先：config 与无口令私钥实际连接

用户报告禁用密码服务无法登录，明确普通 OpenSSH 无口令私钥。复用当前 SSH 父 task，
按[合同](../design/ssh-key-config-connect.md)补齐实际导入、身份选择、native config计划及
key-only登录；保留原未提交密码保存工作。真实账号/个人密钥/设备操作不由本地实现推定
授权；不新建 task，不先继续密码保存设备安装，不将底层认证/解析矩阵当产品能力。

755 JVM/271 Flutter/analyze、原生1758247/issuer75、三类key-only及构建/lint通过；
用户授权后在指定Android11板完成实际私钥/config及保存密码回归，最终正式APK
`373f7161…`已保留数据覆盖安装、设备hash/原UID/启动核对通过。
[当前验证](../design/verification.md#20261026-key-config-device)。设备发现的三处旧路径
假设已修正；中断轮未计成功，测试包/凭据/映射/容器全部清理。未读取个人密钥或访问
实际账号，真实DocumentsUI/新界面人工体验待用户验收。父task保持in_progress，
不提交/归档，不自动启动其他能力。

## 2026-10-26 当前接续：可选保存 SSH 密码

用户明确不新建 task，并在本轮选择推进密码保存。复用已有 SSH 父 task 的直接 R10
切片，按[合同](../design/ssh-saved-password.md)实现默认当次、显式保存、精确账号绑定、
主机核验后复用及单条/全部忘记。已归档客户端 milestone 不重开；父完整范围继续保留。
本地实现完成，255 Flutter/731 JVM/analyze及正式构建/lint通过；独立源码复核通过，
[实测记录](../design/verification.md#20261026-saved-password)。新增能力待当前授权下的隔离
实机审阅（human-required），当前未提交/归档/安装；不推定真实账号授权，不启动其他
父范围。相邻项目修改不属于本轮。

## 2026-10-26 当前交付收尾：正式安装、说明与归档提交

用户已要求并授权正式安装与提交。APK6fa5c1…已保留数据覆盖安装到指定板，实际路径
与SHA核对通过；[功能入口/限制](../design/ssh-terminal.md)和[安装验证](../design/verification.md#20261026-formal-install)
已记录。当前前台密码SSH milestone已[归档](tasks/archive/2026-10/10-08-ssh-terminal-client/prd.md)，原全功能父需求保持in_progress。
构建runner/patch已迁入android/ssh，不依赖活动任务生命周期。后续模式为guided：归档
不自动启动父规划的其他能力，等待用户选择。相邻项目改动不纳入本次提交。

## 2026-10-26 当前：前台密码SSH实际界面验收完成

用户授权的AIO-3568J/Android11实际bridge和Flutter UI均通过，完成NEW确认/保存、密码
认证、PTY命令/渲染输出、KNOWN重连及关闭。[原始证据与清理](../design/verification.md#20261026-password-android)。
只新增一份真实UI测试，314份原有代码/runner保持；测试包和临时服务/凭据已清理，
正式APK已准备但未覆盖安装。本轮前台密码闭环完成，父task全功能仍进行中，同一正式主线。

## 2026-10-25 当前：正式密码SSH本地闭环通过

[生产密码入口](../design/ssh-password-connect.md)已接真实共享库准入、主机键审阅/CAS、
临时密码、TCP与现有PTY。最终独立OpenSSH密码/命令/resize/70秒/退出及本地门禁通过，
[原始证据](../design/verification.md#20261025-password-connect)与独立复核完成。
下一项为当前授权下的Android隔离fixture及实际界面验收；本轮未操作设备/真实库账号，
APK已准备未安装。父task仍进行中，不再拆版本或把可选完整扩展作为基础密码连接前置。

## 2026-10-25 用户更正：最新实现是唯一正式主线

[正式合同](../design/ssh-manifest.md)取消双格式业务选择、默认旧writer和按格式恢复门。
保留已交付TOTP的窄输入适配，重新编码统一当前模型；[本轮验证](../design/verification.md#20261025-ssh-mainline)。
后续以最小可用的密码SSH连接闭环推进实际账号/主机键确认/连接器与操作入口，所需准入
必须落实到该路径；可选证书和恢复能力按自身条件继续补齐，不再按版本拆主线。
父task进行中，本地未提交，未操作设备或真实账号。下方均为历史增量。

## 2026-10-25 当前接续：清单 v2 基础

[显式关系与账号](../design/ssh-manifest.md)加入plaintext2/schema及四candidate完整保留，
默认v1不自动迁移，v2恢复逐侧拒绝。[验证](../design/verification.md#20261025-manifest-v2)。
父task继续in_progress/implementation_complete=false；后续整库语义/账号实际消费、信任/
graph-aware reviewer、完整provider/恢复/connector与父AC，本地未提交，无设备/真实库账号。

## 2026-10-24 当前接续：全部证书事实（本地增量完成）

2026-10-24：[全部证书事实与签名政策](../design/ssh-certificate-facts.md)已实现CSCF168、三份公开摘要/unsigned数值/实际record来源及现代CA签名政策，保留trust/time/principal/revocation未验证。ARM22239与旧材料矩阵、定向145/全量638 JVM、normal100全重跑/fixture111及三APK审计通过；[实测](../design/verification.md#20261024-certificate-facts)。持久graph/账号、完整信任/reviewer/provider/恢复/connector与父AC仍开放；本地未提交，无设备/真实库账号。

Trellis implement限定native/Java，main全部构建与文档，check独立源码/测试/原始证据。source10/protected299，前private current_increment完整转入history。下一步持久关系与账号模型、完整CA/时间/主体/撤销及reviewer；保持生产证书拒绝及provider/恢复关闭，之后才真实Request/connector与父AC。以下为此前历史。

## 2026-10-23 当前接续：全部私钥材料与v1凭据归属（本地增量完成）

2026-10-23：[全部私钥材料与凭据归属](../design/ssh-private-material-ownership.md)已实现全PRIVATE自签/验签、v1口令唯一owner/phrase审计和password namespace/deferred target。ARM19976/独立pair13812/material7784、定向120/全量613 JVM、normal98全重跑/fixture111及三APK审计通过；[实测](../design/verification.md#20261023-private-material)。仍不提供持久关系图/真实密码账号/完整信任/provider/恢复/connector准入，父AC开放；本地未提交，无设备/真实库账号。

Trellis native/Java implement限定源码，main串行全部构建与文档，check独立源码/测试/原始证据。source10/protected295；前pair current_increment完整转入history。下一步全库持久关系authority/覆盖及password target/account模型、完整证书/信任与reviewer，最终Request资源消费义务保持；完整provider之前不开放恢复。以下为此前历史。

## 2026-10-23 当前接续：显式密钥配对证明（本地增量完成）

2026-10-23：[显式密钥配对证明](../design/ssh-key-association.md)已实现精确ID/所选口令、证书plain subject及固定domain实签验签，拒绝仅公开字段相等的伪配对。ARM13814/独立材料7784/fresh认证1748333、定向80/全量586 JVM、normal96全重跑/fixture111与三APK审计通过；[验证](../design/verification.md#20261023-key-association)。报告只覆盖明确所选pairs；全库关联authority/覆盖、凭据目标、完整信任/reviewer/provider/恢复/connector与父AC仍开放。本地未提交，无设备/真实库账号。

主会话负责安全边界实现/源码复核/所有构建，限定worker负责接线/JVM向量及独立原始证据复核。source9/protected292，原resource current_increment原样转入history。下一步明确整库关联authority与覆盖、口令唯一归属/凭据真实目标和信任/reviewer义务，保留动态资源及真实Request最终消费关卡，完整provider之前不开放恢复。以下为此前增量历史。

## 2026-10-23 当前接续：显式资源类型与引用（本地增量完成）

[资源合同](../design/ssh-config-resources.md)已实现显式第四mode/CSCR、五类声明的静态type与真实parent直接references，保留context/cwd/key-loader未决、原owner/预算/cleanup与旧mode。ARM4439含基线/effects0/capabilities0、全部旧矩阵/IO426、定向151/fresh全量564 JVM、normal94全部重跑1m52s/fixture11121s及三APK审计通过；[验证](../design/verification.md#20261023-config-resources)。source15/protected311，patch7afd7a9a…，独立复核无finding。
下一步精确私公钥/证书数学关系与凭据目标/口令唯一性、完整信任和reviewer，再补动态/cwd/key-loader/default/final Request强制点。完整义务与所有入口通过前不注册生产provider或恢复，之后才最终计划connector。报告仍partial-only，原task/父AC继续in_progress；本地未提交，无设备/真实库账号/安装/commit/archive/push/dist。以下是前序历史。

## 2026-10-23 当前接续：CONFIG静态引用与本地效果审计（本地增量完成）

[审计合同](../design/ssh-config-audit.md)已实现独立AUDIT/CSCA、真实静态Include父/行/子与parent直接references、所有分支有限政策；旧mode不升级，原owner/期限/取消/累计预算/清理保持。ARM13290含基线/effects0/capabilities0、旧矩阵/IO426、定向124/fresh全量537 JVM、normal94全部重跑1m39s/fixture11121s与三APK审计通过；[验证](../design/verification.md#20261023-config-audit)。source15/protected307，patch72f09e8a…，checker无具体遗留finding。
下一步非Include typed引用/动态真实Request义务、身份/私公钥/证书数学关联、凭据目标/信任和reviewer；完整provider与所有调用链通过前不注册生产hook/恢复，之后再最终计划connector。报告仍partial-only，原task/父AC继续in_progress。本地未提交，无设备/真实库账号/安装/commit/archive/push/dist。以下为前序增量历史。

## 2026-10-23 当前接续：实际清单受管配置检查（本地增量完成）

[受管合同](../design/ssh-detached-config-managed.md)已实现Java/JNI两阶段owner、已拥有的单份受保护CSDI、所有CONFIG roots与全job累计预算/最终cleanup fence。新ARM3683含baseline/effects0/capabilities0，原矩阵/IO426、定向101/fresh完整JVM514、normal94全部执行1m58s与fixture11122s及三APK审计通过；[验证](../design/verification.md#20261023-detached-managed)。source15/protected303，独立checker无具体遗留finding。
下一步CONFIG/Include typed引用边/全分支政策，再身份/私公钥/证书/凭据目标/信任完整语义与reviewer，之后才能注册生产provider/恢复与最终计划connector。报告partial-only，父任务继续in_progress/implementation_complete=false。本地未提交，无设备/真实库账号/安装/commit/archive/push/dist。以下为前序增量历史。

## 2026-10-23 当前接续：独立 native 配置输入（本地增量完成）

原task仍in_progress，parent implementation_complete=false。[独立配置源](../design/ssh-detached-config.md)已解除活动view依赖，拥有CONFIG副本/原期限与可重试清理，无虚构库能力。专项ARM1261/effects0/capabilities0、原eval3736/catalog71923/handle5201、IO426、fresh JVM483和normal94全部重跑通过；fixture111复用同native，三APK签名隔离/同库与22ALLOC节审计通过。[验证](../design/verification.md#20261023-detached-config)。
下一步Java/JNI实际manifest整库编排与CONFIG/Include引用/政策，之后身份/凭据/证书/信任完整语义，provider完成后再恢复/最终计划connector。原管理密码/导出加密另行判定迁移需求；父AC开放。宿主锁页上限8192KiB，8MiB只证明格式入场/故障清理，真实锁页解析6MiB。仅本地未提交，无设备/真实库账号/安装/发布；旧TOTP mutation/tmp已失不声称重验。以下为此前增量历史。

## 2026-10-23 最新接续：TOTP mutation

原task保持in_progress，parent implementation_complete=false。TOTP add/save/delete/rename
及pending/publication已迁移原origin/context、锁外校验与独占候选，预览跨Request保留
原预算和精确票据所有权。定向196/全量483、normal92全重跑/fixture114/fixed77、lint与
三APK签名隔离/共同库审计通过；独立checker最终批准，无未解决问题。
[验证](../design/verification.md#20261023-totp-mutation)。下一步完整CONFIG/Include/identity/
key/cert/credential-target/trust语义provider，再明确恢复/最终计划connector；生产validator
未注册、SSH恢复未启用。旧sessionManifest路径已移除，管理密码/导出加密不在本轮全迁移声明内。
本地未提交，无设备/真实库账号/安装/发布。


## 2026-10-22 最新接续：SSH trust CAS

原task继续in_progress/parent implementation_complete=false。原审批context/注册/期限与
精确proposal贯穿锁外source/candidate校验；provider可取消，own-CAS只延续原view。
提交后失效明确uncertain并锁库。定向202/全量469、normal92全重跑/fixture114与三APK
审计通过。[验证](../design/verification.md#20261022-ssh-trust-cas)。
下一项剩余TOTP add/save/delete/rename及pending/publication消费者，再补完整语义/
恢复/最终计划connector。本地未提交，无设备/真实库账号/发布，父AC开放。


## 2026-10-22 最新接续：普通SSH凭据保存/删除

原task继续in_progress/parent implementation_complete=false。SSH_SAVE/SSH_DELETE原context/
host gate/注册/期限贯穿，锁外source与candidate校验/加密，独占数组与一次消费已实现；
唯一effect.beforeCommit、wrapper及uncertain保持。定向152/全量455、normal92全重跑/
fixture114和三APK审计通过。[验证](../design/verification.md#20261022-ssh-entry-mutation)。
下一项trust CAS必须保留exact approval与own-NEW-CAS原public view；TOTP mutation、
完整语义/恢复/最终计划connector仍开放。本地未提交，无设备/真实库账号/发布。

## 2026-10-22 最新接续：SSH typed秘密读取

原task继续in_progress/parent implementation_complete=false。credential/private-key/bundle
读取原context/role期限/注册/精确host-KEX-selection贯穿，锁外真实source校验与最终有界
复制已实现；public锁序/即时取消和shared64容量回归保持。定向196、全量JVM444、normal92
全重跑/fixture114及三APK审计通过，独立check无遗留finding。
[验证](../design/verification.md#20261022-ssh-secret-read) · [接管](../design/handoff-ssh-terminal-client.md)。
下一项为普通mutation/trust CAS，再推进完整语义/恢复/最终计划connector；仅本地未提交，
无设备/真实库账号/发布。以下public/TOTP/bundle记录为此前增量历史。

## 2026-10-22 最新接续：SSH public读取

原10-08-ssh-terminal-client继续in_progress/parent implementation_complete=false。
SSH_FILES原context/一次effect、锁外认证/独立loan校验/closure与同步交付已实现；native
lease独立prepare/create/final attach，拒绝参数不转移所有权，不加永久旧epoch revoker。
known_hosts沿用原role.deadline/view，own-CAS与origin.cancel精确撤销保持。最终定向178
通过；全量JVM431、normal92全重跑/fixture114与三APK审计通过。[验证](../design/verification.md#20261022-ssh-public-read)。
下一项为credential/private-key的原请求/实际host-KEX-proof释放，再迁移mutation/trust
CAS及全语义/恢复/最终计划connector。仅本地未提交，无设备/真实库账号/发布。
以下同日TOTP/bundle接续为前一增量历史。[接管](../design/handoff-ssh-terminal-client.md)。

## 2026-10-22 最新接续：TOTP typed读取与最终披露

原10-08-ssh-terminal-client继续in_progress/parent implementation_complete=false。
排队前原read context/期限/epoch/phase、锁外认证/loan校验/生成、一次effect和opaque
receipt已实现；mixed绑定原注册、pure无provider依赖。实际代码按当前窗口/锁库顺序
在pub→session披露，保留transport非重入与Binder阻塞限制，copied回执和cleanup锁外。
final focused140/fresh full JVM410/23reports零失败，normal92全重跑/fixture114/lint、
固定输入normal77/三APK签名隔离/SSH库字节相同通过。[验证](../design/verification.md#20261022-totp-read-validation)。
下一项冻结SSH public snapshot/sessionManifest原请求/role/view/loan/publication边界，
再处理secret release、mutation/trust CAS与全图语义/恢复/最终计划connector。
本地未提交，无设备/真实库账号/commit/archive/push/dist。[接管](../design/handoff-ssh-terminal-client.md)。
以下同日bundle记录为前一增量历史。

## 2026-10-22 当前接续：bundle admission/effect生命周期

原10-08-ssh-terminal-client继续in_progress/parent implementation_complete=false。
审批前原BundleContext/期限/取消/注册与proposal身份、独占candidate/loan、锁外校验与
一次effect消费已实现；修复AtomicFile事务内递归读取与审批撤销后provisional能力残留。
final focused81/fresh full JVM391/22reports零失败，normal92全重跑/fixture114/lint及
固定输入normal77/三APK签名隔离/两包SSH库字节相同通过。[验证](../design/verification.md#20261022-bundle-validation)。
下一项先冻结typed read/sessionManifest尤其TOTP list/code的原context与最终release/
publication边界；再逐mutation/trust CAS与全图语义/恢复/最终计划connector。
本地未提交，不设备/真实库账号/commit/archive/push/dist；实际Android AtomicFile与
native/Flutter运行未执行。[接管](../design/handoff-ssh-terminal-client.md)。

## 2026-10-21 当前接续：恢复预览校验生命周期

原10-08-ssh-terminal-client继续in_progress/parent implementation_complete=false。
已冻结全图语义义务并迁移preview-only capture/锁外实际认证loan校验/审阅/canonical bind/
publication；原期限/取消/不可变validator注册、私有request owner、一次metadata交付claim与
最终commit fence通过。final focused66/fresh full JVM379/21reports零失败、normal92全重跑/
fixture114/lint与三APK签名隔离通过。[验证](../design/verification.md#20261021-preview-validation)。
其他typed/bundle/sessionManifest仍为后续迁移；生产全语义/reviewer未注册，SSH恢复仍拒绝。
下一项先冻结并迁移bundle admission/effect的真实原context/独占candidate/一次消费边界，
再逐入口迁移及补齐detached CONFIG/身份/信任语义，最后最终计划/connector。
本地未提交、不设备/真实库账号/归档/push/dist。[接管](../design/handoff-ssh-terminal-client.md)。

## 2026-10-19 历史：实际清单离线材料检查

原10-08-ssh-terminal-client仍in_progress/implementation_complete=false。无session材料
检查与共享key policy/CSMI/material JNI、Java实际manifest深拷贝/擦除已实现。报告恒
contentOnly=true/admissionComplete=false；CONFIG/身份关联/凭据目标/信任与整库语义仍开放。
材料ARM7784/effects0、原目录71923/eval3736/handle5201、fresh JVM358/20reports、normal95
全重跑/fixture114及三APK签名隔离通过；normal/fixture生产库相同、22分配节对应。
完整认证/PTY ARM1741410/exit0，本轮实测通过；见[验证](../design/verification.md#20261019-manifest-inspection)。
normal a1b24a0e…，material203b273a…；本地未提交，不设备/真实库账号/归档/push/dist。
下一为实际清单语义/引用/身份/信任全图与取消锁边界合同，再做逐字段最终计划/connector。
[接管](../design/handoff-ssh-terminal-client.md)。

## 2026-10-19 历史：声明catalog接续

原`10-08-ssh-terminal-client`继续in_progress。无上下文声明catalog已实现：同一readconf
词法/full Host argv、literal首次来源、静态managed Include和动态延后位置，CSCD与八config
JNI，共用borrow/owner/取消/期限/清理。declarationsOnly，不代表语义有效或最终计划。
strict11、目录ARM71923/effects0、原求值3736/handle5201、focused JVM39、normal95全重跑/
fresh JVM327、fixture114通过；最终archive对应ARM再通过，normal77固定输入再打包，
生产库两包字节相同/22分配节对应，config8/session19/archive83及三APK签名隔离通过。
normal252dc31c…/ARMf0e71e3d…，正式源码和证据复核通过。下一为实际manifest语义validator、
逐字段最终计划/精确选择/connector，SSH恢复门保留；新版Android实际运行未执行。
本地未提交、不设备/真实库账号，不archive/push/dist；父implementation_complete=false。
证据：[验证](../design/verification.md#20261019-config-catalog) · [接管](../design/handoff-ssh-terminal-client.md)。

## 2026-10-19 历史：JNI/Java边界接续

复用既有`10-08-ssh-terminal-client`（in_progress），native readconf之后的JNI/Java求值边界
已实现并过本地门：borrow-only caller lease、64槽opaque registry/短锁cancel、标准UTF8
wire/有界来源诊断、完整Options留native，Java摘要不是最终计划。strict9、focused JVM20、
最终ARM5201/effects0、normal95全重跑/fresh JVM308、fixture114与三APK审计通过。
固定编译输入后normal77项再打包，normal/fixture生产库字节一致，configJNI6/sessionJNI19/
archive83保持；final normal284e9566…/ARM1291cead…。下一为catalog/语义validator与逐字段
最终计划/connector，SSH恢复拒绝门保持。无新版Android实际执行；本地未提交、不设备/
真实账号、无archive/push/dist范围继续。证据和接管统一见[入口](../design/README.md)、
[本轮验证](../design/verification.md#20261019-config-jni)及[接管](../design/handoff-ssh-terminal-client.md)。

以下记录按各自日期解释，旧待执行/当前指针/授权文字不覆盖本节。

2026-10-15 SSH当前窄增量：在 hostfile issuer seam 之上新增 host/fixture-only pinned
OpenSSH 10.6p1 caller `ssh_pinned_client.[ch]`。它绑定 live role、managed public view、
callback environment 与 caller-owned nonblocking socket，经过真实 `ssh_api` packet loop
覆盖 partial read/write、EAGAIN、EOF、cancel/deadline、KNOWN/NEW host evidence、candidate
rollback 和 KEX session digest callback。固定 runner `run.3PZSbIzM` 为
`SSH_HOSTFILE_CALLER_BUILD gate=pass runtime=not-run normal_apk=not-wired`；normal APK
SHA256 `67668e34…`，JVM=177/0/0/0、callback=20、native boundary=27，仍不含 fixture
OpenSSH objects。hako 无 `qemu-aarch64`，未执行 ARM；production event loop/auth/PTY/
forward/service/UI、设备和外部 SSH 仍开放，task 继续 `in_progress`。

2026-10-15 SSH上一窄增量：新增 host/fixture-only pinned OpenSSH 10.6p1 hostfile issuer
seam。它绑定 `ssh/config` root→`ssh/known_hosts` 引用，复用上游 memory-stream parser 和
source-owned formatter；证书先做 revoked certificate/CA 判定，其他证书按当前 policy
拒绝；NEW exact body 走既有 one-shot managed handoff。固定 G1 manifest、严格 C11/Werror
与 AArch64 PIE link runner 通过，但 runtime 因容器无 `qemu-aarch64` 未运行，普通 APK 未接入，
`ssh_native_client` 仍 `ENOTSUP`，task 继续 `in_progress`。

2026-10-15 SSH上一阶段：在 managed `AppSecrets` known_hosts provider 上接入独立
native→managed one-shot candidate handoff，并修正 proposal receipt 校验顺序。精确
snapshot、candidate、proposal 和 proof-aware CAS writer 均绑定 vault UUID/代次/epoch、
对象 identity、host proof 与 base/replacement digest；`writeBack` 在 proposal 仍开放时
校验 `CasResult`，随后在 `finally` 清理 proposal，构造失败的 Java body clone 也立即清零。
177 JVM、native client gate=27、callback gate=20、strict compile/lint/Release 通过，APK
`3a6228f4…`，普通包排除 host-only/fixture。hostfile issuer 已在 G3.4 fixture seam 接入，
生产 OpenSSH caller 尚未接入，
task 继续 `in_progress`。

2026-10-15 SSH前一阶段（provider-only历史）：已把 role/hop/target-bound HostEvidence/CAS
seam 接到真实 managed `AppSecrets` known_hosts provider。精确 snapshot、一次性 replacement
candidate、proposal 和 proof-aware CAS writer 均绑定 vault UUID/代次/epoch、对象 identity、
host proof 与 base/replacement digest；只有 guarded commit 成功才返回 `COMMITTED`。取消路径
在 Store.commit 持锁时仍可立即 revoke。174 JVM、native client gate=27、callback gate=15、
strict compile/lint/Release 通过，APK `718c3934…`，普通包排除 host-only/fixture。生产
OpenSSH caller/hostfile issuer 尚未接入，task 继续 `in_progress`。

2026-10-15 SSH前一窄增量（provider-missing，已被顶部 handoff 取代）：pinned OpenSSH 10.6p1 source map 确认生产 known_hosts
issuer/CAS native-facing contract 尚未存在；新增 `ssh_native_client.[ch]` 只作为
fail-closed boundary，校验 role/PID/UID、CLOEXEC/NONBLOCK socket、callback
environment/token、deadline 和 bounded step，缺 provider 稳定返回 `ENOTSUP`。host-only
gate=27，普通 APK 不含 boundary 或 fixture OpenSSH object；真实 caller、provider、
partial I/O、EOF、cancel、close retry、inbound NEWKEYS/JNI proof、设备和完整客户端仍开放。

2026-10-14 SSH前一阶段：同进程、无 fork/exec 的 KEX-only native launcher 仍作为独立 opt-in fixture/host seam 完成；在此基础上，用户确认同步附着 JNI callback 与受管 config/known_hosts + exact trust proposal/CAS + typed private-key handoff 方向，bounded managed-provider handoff 已接入正常 `libctos_ssh_files.so`。role/hop-bound NEW proposal、host/KEX proof、typed request kind、Java/native 清零与 cleanup retry 通过；host gate=11、162 JVM、lint、assembleRelease 通过，最新 APK `f32bbc83…` 仍只有生产 SSH library。具体 file/CAS provider、ARM、设备、外部 SSH、vault、生产 event loop/caller/auth/PTY/转发/服务/UI和联合AC仍开放。

2026-10-13 G2构建前阶段（历史）：TOTP工作566ca51、归档f3762ba、journal74f8324完成；现有10-08-ssh-terminal-client已启动in_progress，父SSH task同为in_progress，仅维护依赖/集成。用户专项批准本轮`.14` G0安装/合成测试/清理：独立UID/API30/4KiB/Permissive实际1项1.693秒通过，rawmemfd完整可用/ASharedMemory文件形态不适用，夹具已卸载，正式APK3bc762d9前后哈希相同。G1 OpenSSH10.6p1/OpenSSL3.5.9 ABI兼容基线编译通过；当前G2合成上游文件/ProxyJump夹具实施中，Java/Gradle边界修补已复审，有效配置与四拒绝分支检查通过。尚无G2完整构建或设备运行；不是生产客户端/完整native gate。

2026-10-13 当前授权：用户确认TOTP并批准提交/归档与继续SSH；随后明确允许本轮开发板G0操作及真实秘密库操作，密码不保存至任务/日志。当前G0只使用合成数据，未访问真实库、远程账号、Root/剪贴板/系统设置；远程真实SSH账号未授权。main串行全部build/device，TOTP未测边界和共享底座in_progress保持；下方同日TOTP in_progress/SSH planning为验收前历史。

## 2026-10-13 恢复既有 TOTP 与 SSH 任务

- **Subsequent device authorization:** 用户在地址更正后明确“帮我安装、自动测试”。当前`.14`核对AIO-3568J/Android11/API30/arm64，secure=true但当前未锁；最终3bc762d9前台交接修复主包覆盖安装、读回一致。实际0相机失败恢复通过，221Flutter/86定向/analyze及独立UID7项原生通过；系统双二维码选图与管理/生命周期全流程1项72.734秒通过，临时测试包/自有图片已清理，具体失败/核清/修复按验证记录。fixture主包1efe858b，不操作真实库/账号/系统安全。复制最初被自动审批拒绝；随后用户明确允许覆盖，原生合成复制/敏感标记1项6.616秒通过且再清理两个fixture包；0相机不能证明硬件扫码。下方初轮授权/本地检查为历史。

- **Mode / Authorization:** 用户要求检查实现并继续推迟的任务，随后明确“复用现有 task，先 TOTP 后 SSH（推荐）”。复用已存在且已审阅的规划，本轮先恢复 TOTP 本地实施、测试与构建；无本轮设备安装或真实账号授权。
- **Current task:** `10-08-authenticator` 已启动为 `in_progress`。SSH 父/客户端仍 planning，后续按用户确定顺序接续，不创建重复 task。
- **Base pulse:** 工作区初始干净，最新代码提交 `5e67227`；公共库/共同管理及 TOTP SHA1/6/30 核心已实现，API30锁屏凭证快捷曾成功实测。底座仍 `in_progress`，API28/29、生物、真实进程死亡、混合 SSH/native 恢复等开放关卡保持，不据用户“秘密库完成”概括标记全量验收。
- **Dependency boundary:** 按既有合同/联合验收拆分和本轮明确授权接入 TOTP，不要求尚未存在的 SSH 业务先验收才实施 TOTP；生产 SSH validator/reviewer 缺失时继续拒绝相关恢复。
- **Local result:** Authenticator 相机/图片/手动添加、条目列表/倒计时/复制/管理及公共库接入完成；214 Flutter、122 JVM、79定向、analyze、lint0错误3既有警告和45真实字体场景通过，最终arm64/v2 APK `c704ff362ea8402709833217025e800a298917c698183bf6a4550388c40e85eb`。独立复核无剩余具体源码阻塞；完整证据在 `design/verification.md` 和 task check。
- **Next action:** implementation_complete，review=human-required；当前设备已授权安装/自动验收，本轮非copy真实系统选图及清理完成；原生复制已获专项批准并通过；Flutter复制按钮E2E/硬件相机/真实服务账号/跨设备混合关卡另记。SSH随后按已有规划先验证Android native无明文文件视图，不提交/归档当前未验收task。
- **Current board correction:** 用户于本轮更正开发板入口为 `192.168.9.14:5555`，已同步AGENTS/constraints/validation spec，替代错误 `.13` 默认入口；地址更正本身不是安装/验收证据。

## 2026-10-12 最终概览文案收敛与提交批准

- **Authorization:** 用户要求“不建task；在当前task一起完成；完成后提交”，后续“继续任务”保留该授权；不重复提交许可。
- **Scope:** 当前overview-settings全部概览/设置/安全迁移及截图反馈；最终删除DEVICE/Android运行摘要，统一网络/接口/连接范围描述，保留动态来源/计数。只提交本任务相关路径。
- **Evidence:** 最终188全量15秒、analyze1.5秒/14格式、84真字体场景、arm6428.7秒/v2通过；25bcdf7c…开发板安装读回一致。其他产品/测试/Android fixture源未变，已有快捷/返回/PTY有界实机结论与未测项按原APK解释。
- **Result:** 工作提交5e67227完成，本任务已归档到tasks/archive/2026-10/10-12-overview-settings，当前指针已清除；会话记录按收尾流程提交。原底座in_progress和其余业务planning保持，原共享快照审批拒绝边界保留，不自动启动下一任务。

## 2026-10-12 继续概览截图细节与默认解锁

- **Authorization:** 用户在初轮确认有效后追加四项截图要求，本轮“继续任务”恢复同一overview-settings指针；沿用本任务自行连接验收授权与已核对的.14开发板，不新建任务、不推断成功认证凭证授权。
- **Scope:** 分类全宽均分居中、Root标题旁绿/黄信息按钮、移除APP状态及终端眉标题，已登记默认快捷/未登记口令/失败三选择，新请求/取消/代次/路由保护；管理真实口令保持。
- **Evidence:** 独立check无产品缺陷、补3管理认证回归；188全量/53秘密库、analyze/14格式、Android夹具编译及lint0错误3既有警告、84真字体场景、arm64/v2通过。af600c41…已安装并读回一致，详见design/verification.md和task check。
- **Device:** 解锁后的AIO-3568J/API30新版布局、系统快捷直接调起/Home取消后三选择/显式重试/主口令空表单取消、AppPTY公开echo切换保持及四主入口连续双返回桌面/重开通过。成功认证新版未重测，原共享快照写盘审批拒绝不重试，原底座平台/业务关卡保持。
- **Next:** implementation_complete=true、current/in_progress，待新细节观感/成功认证等未测反馈；未提交/归档/替换dist或推进TOTP/SSH。

## 2026-10-12 概览整合与设置迁移

- **Authorization:** 用户明确新建 task、作为 current 并开始完成；已建立 [overview-settings](tasks/archive/2026-10/10-12-overview-settings/prd.md)，完成规划并启动，in_progress。
- **Scope:** 概览合并原信息四分类及完整内容，删除网络现场/能力与恢复和信息主入口；移除顶部导出，将原流程和完整秘密库管理迁到设置/安全。
- **Boundary:** 初轮仅本地；用户随后授权自行连接验收，已在当前AIO-3568J/API30覆盖安装及部分UI检查。未获系统安全修改/真实库改变/账号操作、dist替换或提交归档授权，不推进原公共底座待测项或SSH/TOTP。
- **Result:** 代码、160全量/增强后6专项、analyze/格式、60场景渲染、arm64构建/v2验签通过；APK a441d114…，design/spec同步，未操作设备/替换dist/提交/归档。
- **Device:** a441d114…已安装读回一致，概览/设置/安全及导出取消检查通过；真实共享快照保存/pull被自动审批拒绝并取消确认不存在。板随后系统重启、进入锁屏，原因未确认。
- **Next:** 已请求系统解锁，继续剩余四入口返回和AppPTY检查，保持current/in_progress；详情tasks/archive/2026-10/10-12-overview-settings/device-check.md。

## 2026-10-12 用户确认效果并授权相关任务提交

- **Authorization:** 用户回复“效果可以；提交所有相关 task 文件”，当前成果提交已获授权，不再次请求确认。
- **Scope:** 公共秘密库实现/测试/spec/design，以及app-authentication、Authenticator、SSH父任务和客户端任务的规划/研究/验收文件；仅ctOS，不推送或更改设备。
- **Status:** 用户确认当前实际效果，不替代未测平台/业务AC；底座active/in_progress，其他三个task保持planning。阶段成果提交后记录session，保留未完成关卡，不归档。

## 2026-10-12 恢复公共底座与快捷认证修复

- **Authorization:** 用户设置开发板安全锁屏并授权自行继续测试；仅本轮目标192.168.9.14:5555，不改系统安全设置或操作真实SSH账号，凭证不持久化。
- **Result:** 认证绑定Cipher的AAD移至认证成功后的guard与doFinal之间；同一真实空库流程旧版失败/修复后1项95.811秒通过，登记/快捷解锁/关闭保护/真实认证解密/主口令回退均通过。106JVM、analyze、lint0错误3既有警告与构建/v2验签通过。
- **Delivery:** 主包71d00e7c已覆盖安装、读回一致，UID10131；现有用户库正常UI开启/快捷解锁通过，结束锁定/快捷已开。隔离fixture两包已清理，未改主口令/重建库/导入导出/dist。
- **Next:** 当前指针10-08-app-authentication，active/in_progress；API28/29/生物、真实锁屏/进程死亡/非空混合及正式业务/native联合验收仍开放，不启动TOTP/SSH，不提交/归档。详见 [验证](../design/verification.md#2026-10-12-安全锁屏开发板快捷认证修复与实测)。

## 2026-10-11 主页双返回提交与收尾

- **Authorization:** 用户在下方提示交付后回复“可以提交当前 task”；当前task提交/归档已获授权，不重新请求确认。
- **Result:** 四入口根路由2秒双返回，提示位于下方并自动避让导航/安全区/Snackbar；16定向/154全量、analyze/格式及981ef814 arm64 APK/v2验签通过，用户验收与提交批准单独记录。
- **Scope:** 只提交本任务代码/规范、共享文档内本任务段落及本任务归档/日志。公共秘密库实现、设计/验证与其他任务改动保留未提交，继续暂停。
- **Next:** 功能提交a46a296与本任务归档完成，当前指针已清除；公共底座继续暂停，不push或安装设备。

## 2026-10-11 用户实机反馈与下方提示调整

- **Authorization:** 用户回复“实机验收过；但是能否把 prompt 出现在下方？”。保留为先前交付的用户验收报告，未提供设备/API/安装哈希，不冒称 agent 实测。
- **Current:** 当前任务只将提示改至下方，利用 Scaffold公共布局自动避让导航/安全区/普通Snackbar。返回/2秒/状态逻辑保留；公共秘密库继续暂停。
- **Evidence:** 独立检查最终16定向/154全量、analyze/格式通过；底部几何/Snackbar两顺序与无按钮点击语义通过。新arm64/v2验签通过，APK `981ef8145c01f1ce037f6ee5d80bd5672fe28e46fea591ac3d762fd48f9ef02a`，完整边界见design/verification.md及task check，不沿用旧7ad包证明新布局。
- **Next:** 新版APK供用户确认下方提示位置/同屏反馈观感；保留in_progress / awaiting_position_feedback，不提交/归档或恢复公共底座。

## 2026-10-11 暂停公共底座，切换主页双返回

- **Authorization:** 用户要求“暂停另一个 task；然后开始当前 task”，随后选择“四个主入口，2 秒（推荐）”。本轮本地实现/检查/构建；没有当轮设备安装与操作授权。
- **Paused initiative:** `10-08-app-authentication` 暂停推进；保留 `in_progress` 与所有已有成果/未完成关卡，在 metadata 记录 execution_state=paused。TOTP/SSH仍未启动，不把暂停写成完成。
- **Current initiative:** `10-10-home-double-back-exit`；四入口根路由首次返回提示，2 秒内再次返回退出到桌面。正常路由/弹窗/键盘返回不计数，切换/覆盖/后台重置。
- **Evidence:** Trellis implement/check完成；退出提示被浮动Snackbar遮挡的布局红灯经修复通过，14定向/152全量、analyze/格式、arm64构建/v2验签通过。APK SHA256 `7ad103a9cf2684d79b9e582e30a8e7cb6819d29e2eac3a560e03fae9c2637407`，源码/回归/产物与限制见design/verification.md和task check。
- **Next:** 主页任务保持in_progress / awaiting_device_review，真实Android返回键/手势、IME、桌面与重开需当轮授权验收或用户反馈；未安装/替换dist/提交/归档，不自动恢复公共底座。

## 2026-10-10 本地继续公共底座

- **Authorization:** 用户要求继续已有 task，并回复“暂时没有，先继续本地工作”；本轮只做本地实现/检查及平台夹具准备，没有设备操作。
- **Current task:** `10-08-app-authentication` 当前指针已恢复，继续 `in_progress`；保留其他旁任务与修改，未启动TOTP/SSH客户端。
- **Evidence:** 最终写入期限和末次SSH host gate失效释放两个问题经红灯复现修复；106 JVM/138全量Flutter、analyze、正常/fixture lint0错误3既有警告通过。真实快捷认证手动夹具两APK打包/v2验签/固定target通过，尚无真人认证或设备执行。
- **Next:** 获得已有安全锁屏设备和当轮安装/隔离验证范围后执行已准备的手动夹具；本地结果不替代API28/29、真实进程死亡、非空业务及SSH/native联合验收。详见 [验证](../design/verification.md#2026-10-10-继续任务本地效果边界修复与手动认证夹具)。历史ca85安装包不含此次修复；未提交/归档/替换dist。

## 2026-10-10 统一秘密库实施批准

- **Authorization:** 原“批准”授权底座实施与 SSH 内存探针；用户现回复“配置 B”，授权当前 APK/测试包安装及隔离设备验收。唯一当前目标 `192.168.9.14:5555` 已核对为 T-CHIP AIO-3568J / Android 11 API 30 / arm64 / Permissive；不覆盖用户库、操作真实账号或更改系统安全配置。
- **Current task:** `10-08-app-authentication` 已通过 `task.py start` 进入 `in_progress`；SSH 父任务维护集成，TOTP 与 SSH 客户端仍待底座验收后接入。
- **Execution:** Android 宿主、Flutter 公共设置/解锁 UI 与隔离 native 文件访问探针按独立文件责任推进；实现与检查遵循 Trellis 子代理流程。
- **Local evidence:** 公共流程/SSH 文件图/导出事务/恢复审阅已实现，路径 ID 复用与角色持久移除修复后最终104 JVM通过；未变 Flutter analyze/35定向/138全量保持通过，lint 0 errors/5既有warnings。实际字体窄屏/大字/键盘独立审阅，Linux OpenSSH 内存文件六组研究通过；研究不代替Android/native接入。
- **Scope update:** 2026-10-10 用户明确撤销验证码剪贴板清理，ctOS 仅生成 TOTP 并响应用户主动复制/敏感标记，清理由用户自行处理。清理时长/status/prefs/复制 ID/跟踪及竞态探针移除；后台库超时保留。
- **Device evidence:** 最终17平台测试32.066秒/真实ExternalStorageProvider1六阶段59.838秒通过，两个额外诊断单列；actual path_id_reused=true。真实provider由own磁盘VaultStore/Journal+合成密文认证验证新建/拒绝/确认/取消与主动注入stage/final半途失败清理和旧恢复，每save freshload；不是生产Flutter导出全流程或真正kill/cloud故障。初轮敏感截图/录屏/最近任务及普通恢复已实测，UI/helper源未变。生产库missing、后台30秒保持。详见design/verification.md和research/device-config-b-validation.md。
- **Technical gates:** B无安全锁屏，仅快捷early unavailable通过；成功Keystore/凭证/强生物、API28/29、真实锁屏/进程死亡/权限撤销/云provider、非空混合业务恢复及SSH语义/native接入开放。独立UID空库实际release UI+本机SAF完整流程已通过，不代替这些关卡。剪贴板清理已撤销。
- **Additional evidence:** Bridge关闭/自身timer/旧onStop/迟到picker与0秒失效预览修复后104JVM实际重新执行，analyze/35定向通过，未变138全量未重跑。新ca85/577构建验签/安装读回一致，真实HOME等6项53.408秒通过；旧callback/排序注入/core重建分列，不代表SCREEN_OFF/真实kill/生产SSH。own6套资源及默认test清理，生产missing/后台30保持；正常Main的Rootauto/active和alias维护如实记录。
- **Next:** 当前授权B的Bridge6项和独立UID空库完整UI/SAF 1项235.79秒均通过并精确清理。成功Keystore/凭证/强生物仍需已有锁屏设备；API28/29、真实锁屏/进程死亡/权限撤销或云provider、非空混合与实际SSH/native业务联合验收开放，不改PIN/访问未授权目标。保持in_progress，未提交/归档。
- **Artifact:** 生产main SHA256 ca85ec00dd7eb737454d4380e03fc6c6ae45343e3faac9fc68ab77b372116f3e/UID10131保留，默认577及独立fixture两包已卸载；UI mainf358/test83e安装读回与1项235.79秒结果分别记录。旧a309/cb829/b7c90/c4e817按原版本历史解释。完整报告见research/flutter-ui-config-b.md与research/bridge-lifecycle-config-b.md，未替换dist/提交/归档。
- **Toolchain:** `./hako flutter --version` 已验证可用；容器调用需要 sandbox escalation，已通过自动审查。未替换 dist current APK；本轮已按配置 B 连接并核对设备，安装/实跑结果另记。

## 2026-10-10 统一秘密库、TOTP 与 SSH 规划待审（批准前记录）

- **Mode / Authorization:** guided；持续仅规划，未获代码、构建、设备或账号操作授权，不运行 task.py start。
- **Tasks:** [统一秘密库](tasks/10-08-app-authentication/prd.md)、[Authenticator](tasks/archive/2026-10/10-08-authenticator/prd.md)、[SSH 接入](tasks/archive/2026-10/10-08-ssh-terminal-client/prd.md) 与 [SSH 父任务](tasks/10-08-ssh-terminal/prd.md) 均 planning / awaiting_final_review；ID/原树关系保留。
- **Scope:** 用户撤销 HOTP 的全部设计/实现，只保留已确定离线 TOTP（相机/图片/手动、条目/时间码/复制）与 ctOS 自身完整加密备份，其他平台迁移排除；已确定 SSH 客户端/config/Include/ProxyJump/信任/转发/手动 Stop 范围保留。
- **Base:** 唯一主口令/KDF/密文文件与受管 SSH 文件对象/设备生物或锁屏快捷/共同秘密库设置/使用会话/管理认证解密/改口令/完整导入导出事务/敏感窗口与复制 helper。前台共享、锁屏/手动即锁/重启锁定；后台默认 30 秒可选（0 即锁），验证码复制默认 30 秒可调且仅尽力清理本次 clip，两项设置独立。
- **Dependencies:** 先交付并验收公共底座，再由 TOTP 与 SSH 分别接入；TOTP 页面不是 SSH 的依赖。SSH 父任务管理完整需求和最终集成，不是另一份业务实现；真实依赖写在子 task，而非由父子树位置推导。
- **Technical gates:** native 无明文磁盘文件视图/Include/ProxyJump/信任写回探针宜在底座接口冻结前验证；密码库/KDF/资源上限、API 28/29 与 30+ 真实认证/Keystore、窗口及剪贴板竞争需定向验证。不满足既定行为回规划，不静默降级。
- **Progress:** PRD/design/implement 与 JSONL 已按剩余确定范围整理；产品实现、算法/平台探针和真实设备验收均未开始；2026-10-10 文档/元数据/真实上下文/链接及 diff 检查通过。历史 HOTP 讨论与曾暂定状态见 design/changelog.md 和任务 check.md，以本节最新撤销决定为准。
- **Next:** 对最新完整规划进行共同最终审阅；只有后续明确实施授权才开始公共底座，当前不推进实现。

## 2026-10-06 Flutter 优化交付

- **Mode:** `guided`，已获本任务完整方案实施授权。
- **Task:** 全 Flutter 实现与验证完成，本轮按计划提交及收尾；[归档记录](tasks/archive/2026-10/10-05-flutter-experience-polish/check.md)。
- **Goal gate:** 最终观感与提交确认已通过；2026-10-06 用户回复“观感通过，按计划提交”，原等待确认的阻塞已解除。
- **Authorization:** 用户要求按获奖级品质参照持续优化，澄清范围为整个 Flutter，已同意创建任务并规划，并回复“允许开始实施”。配置 A 可用于已批准方案的 ctOS 有界安装、界面和关键路径验证。
- **Scope:** 全部现有 Flutter 页面/状态，设计统一入口 `design/flutter-experience.md`；不增加产品能力或修改平台权限策略。
- **Evidence:** 最终 analyze 无问题、103 项测试通过、107 场景/127 张 Flutter 矩阵图片与独立 IP 复验；最终 APK `37d22600…` 构建/签名通过，配置 A Android 15 覆盖安装、base.apk 哈希一致。全部信息入口、密码结果/复制、App PTY/Tab/历史/输出搜索复制及关闭已实测。具体边界见 `design/verification.md`。
- **Next action:** 本轮授权涵盖工作提交、此任务归档与 journal，不包含 push。后续新工作仍按 guided 模式另行确认范围。

## 2026-09-28 已完成主线记录

- **Mode:** `guided`
- **Authorization:** On 2026-09-28, the user directed Codex to complete the remaining Trellis task graph in order and archive all legacy tasks, then confirmed the parent task's three-commit plan. That authorization is fulfilled and does not extend to new scope or device actions.
- **Current scope:** The three child tasks and `09-25-product-experience-opportunities` are complete and archived. No active task remains.
- **Current task:** None. The parent integration mapped P0's eight historical acceptance criteria to saved evidence, repaired archive links, and reconciled `design/` with the delivered slices. Its documentation work commit is `3e78ad3`; archive and journal bookkeeping follow.
- **Completed evidence:** P2 implementation `ca04b04`, archive `949a0f6`, journal `a30e0ae`; P1 implementation `fc254a4`, archive `9c78cfe`, journal `5d11297`; P0 implementation `90024df`, archive `d00f11d`; bootstrap archive `71c4130`. Parent integration evidence is in the archived task's `check.md` and `design/verification.md`.
- **Device access:** No device access is currently authorized by this completed graph. Prior task device results remain scoped to their recorded APK and target.
- **Next action:** New project work requires a separate bounded scope; do not infer priority or device authorization from these archives.

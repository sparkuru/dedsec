# Verification

2026-10-13 用户回复“效果不错；可以提交验收；然后继续做下一项”，接受当前交付并批准提交/归档。下方执行日志、产物及未测边界继续按各自阶段解释；用户反馈不替代未执行的平台/账号/跨设备/混合测试，也不据归档标全部AC通过。

## 2026-10-13 Authenticator TOTP 开发板自动验收（进行中）

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

所有本节相对日志均位于 `/tmp/ctos-totp-device-20261013/`，属于临时原始证据，不保证清理或环境更换后持久；持久结论与未测边界以本节和 [task check](../.trellis/tasks/10-08-authenticator/check.md) 为准。

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

**验收分类：human-required，task保持in_progress。** 相机硬件/权限生命周期、真实图片provider/临时授权退场、实际Bitmap与安全窗口/截图/录屏/最近任务、ROM敏感复制、断网设备发码及非空TOTP跨设备备份恢复尚未执行；mock、JVM和渲染不能代替。真实MFA绑定/登录需服务账号另授；混合SSH/native文件与每跳信任恢复仍依后续SSH provider和Android探针，当前缺失时生产相关恢复继续拒绝。公共API28/29、生物、真实进程死亡也不在本轮宣称通过。逐项AC见 [task check](../.trellis/tasks/10-08-authenticator/check.md)，下一步仅在当前设备授权后安装核验上述场景，SSH按用户顺序继续已有规划。

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

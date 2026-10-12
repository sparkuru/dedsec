# 配置 B：独立 UID 真实 Flutter UI 验证

2026-10-10 最终83e独立UID真实Flutter+SAF完整E2E一项通过235.790秒，空业务库双口令恢复、真实解锁与精确清理通过；此前probe、a7c7管理、只读诊断和历版失败分别记录，不合并版本/计数。本轮fixture两包与自建外部备份/叶子/临时授权已回收，生产ca85/UID10131保留，公开尚未创建/后台30秒。成功系统认证、非空和混合业务等仍未验证。与 [默认Bridge六项](bridge-lifecycle-config-b.md) 分包解释。

## 隔离及源码审阅

用户当前配置 B、根会话 probe-only 放行，唯一设备 `192.168.9.14:5555`。固定独立主包 `im.majo.ctos.secretsfixture`、测试包 `.test`，安装前两者不存在。生产 `im.majo.ctos` UID10131/主 ca85 保留；实际 fixture UID10142、fixture.test UID10143，无 shared UID，已安装 instrumentation 的实际 targetPackage 仅 fixture。

`-PctosSecretsUiFixture=true` 为显式 opt-in flavor，其他参数拒绝；未提供属性时默认构建身份/源选择保持原状。独立 Application 首先确认自身 package，再在 MainActivity 前同步提交自身 `root_access.auto_start=false`。测试 manifest 替换旧测试源，实际合并没有旧 DocumentsProvider/辅助 Activity/grant 权限，AndroidX authority 由 fixture package 派生。main 保留原权限，未加广泛存储或 Root 权限。

测试使用 `UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`，只设置自身自动化连接能力；没有修改系统设置、secure flags 或 Clipboard。操作节点只来自 fixture 或实际 DocumentsUI package；本轮 probe 不启动 DocumentsUI。

完整 E2E 初稿在 Allow 前仅以 contains(leaf) 核对可见文本，真正 exact tree grant 在导出后才检查。review 已要求 owner 在 Allow 前以受限 toolbar/title/breadcrumb 证明当前 exact 新 UUID 目录，失败不授权。probe 本身不走此代码；后续 E2E 等新的源码冻结/单独放行。

## 冻结包及本轮结果

使用只读副本 `/tmp/ctos-secrets-ui-fixture-probe`，安装前与设备 base.apk 读回 SHA256 一致，两包 v2 验签且同既有 `8d36c8be…` 证书：

| 角色 | SHA256 |
| --- | --- |
| fixture main | `f3582423561734c1bbaa5f709cdc323cf4f159ef0fc1251d259b786da898cd83` |
| fixture test | `894938650e20bc22cb5f85f91ca8b98e584c8ebc01d895c712eb80b8b65556bd` |

源码五文件 SHA 清单 `/tmp/ctos-secrets-ui-fixture-source-sha256.txt` 在 probe 安装前通过；owner 已保存对应测试历史 snapshot，后续 helper 改动不影响上述已冻结 APK。

仅运行 `AppSecretsUiFlowTest#semanticsProbeWithoutCreatingVault`，实际 `OK (1 test)`、4.123 秒，无失败/跳过。真实 release Flutter 控件显示 ROOT 未连接，进入秘密库设置/创建页面，两个 editable 字段 ACTION_SET_TEXT 返回成功，点击取消返回尚未创建；前后 own 库、记录恢复变体、export journal 均 absent 断言通过。前置 own root auto false、target UID、Application 身份、当前板 Keyguard.isDeviceSecure=false 断言均执行。该探针没有提交/验证 controller 值；后续失败诊断表明未广告 SET_TEXT 的节点也可能在 engine 预测 cache 中更新，故不能把探针的 action 返回或可观察文字当作真实 controller 输入已验证。

实际 stage `run=4298ebfd-6d99-4eb8-b7a4-59c614b7bded phase=semantics_probe_pass_no_vault_created`。证据目录 `/tmp/ctos-config-b-20261010/secrets-ui-probe-e15d70ce-da2a-4bb9-b772-b815eb01bf08`，包含安装/readback SHA、实际 instrumentation target、原始 probe 日志及 stage 日志。

## probe 结束时资源及历史界限

probe 完成时 fixture 两包 fresh 保留，随后按下面新包/单项放行尝试 E2E。生产主 ca85 保留安装。不能由语义探针推定完整创建/改口令/覆盖/恢复已通过；成功系统锁屏认证、API28/29 和真实 SSH 仍未验证。

## 首轮 E2E 失败记录（974 测试包）

root 已放行单项 `freshFixtureEndToEndFlow`；仅覆盖 fixture.test，冻结测试副本 `/tmp/ctos-secrets-ui-fixture-bounded-saf/app-secretsFixture-release-androidTest.apk` SHA256 `974b2cd59cdb131ce03959ed8ccfa5b586fe52eeea45c61eec71f2c12551be01`，安装读回一致。fixture main f358/UID10142 未变，production ca85/UID10131 未动。新版源码收紧为 Download/ctos/唯一 leaf 的 exact 导航/breadcrumb、已安装 DocumentsUI 本地化完整确认 title/message 与同 window positive button；不足则 fail closed。来源 Latest/目标 Destination 的不同口令流程已写入，但尚未到达。

2026-10-10 14:29（UTC+8），run `aa1aee14-6330-4fb2-b767-9b11a9d938cc`，`Tests run: 1, Failures: 1`，13.018 秒。首个确认口令不同值后，期望可见的 `两次口令不一致` 未出现。源 Flutter 校验文案确实相同；仅此事实不能判定生产校验有缺陷，尚需核对测试控件命中/真实字段值和提交动作。没有出现 create_and_confirmation_pass stage，没有调用 export、打开 picker 或授权外部目录。

证据目录 `/tmp/ctos-config-b-20261010/secrets-ui-e2e-79e5a37f-4b92-43a5-a377-63d7b046f747`；原始 `e2e.log`、安装/readback 和允许的 stages 已保存。失败后仅从隔离 fixture 公开设置 UI 观察：仍尚未创建、快捷不可用未开启、后台30。该公开观察不能独立证明私有 journal absent。own marker/fixture 包均保留，未 pm clear/卸载以丢弃证据；只读 target-UID 诊断和测试 driver 修正由 owner 实施，新的源码/测试包需再冻结、签名、审阅及单项放行。

## c4c2 只读诊断及管理项失败

冻结 test-only 包 `/tmp/ctos-secrets-ui-fixture-driver-exact-status/app-secretsFixture-release-androidTest.apk` SHA256 `c4c2bd964282635df98d6cdc78607c38abe57d07bbda919a1bacce92869efc27`，对应 Java `b0a3b3322815496ba745ace3ba81af269dd3f8fbac9694a24676dae71eeffae9`。v2 编译/同证书核验、五源 SHA 和设备测试 APK 拉回 SHA 均通过。main f358/UID10142 与 testUID10143 未变。

先按 diagnostic-only 放行单项 `readOwnFixtureDiagnostics`：实际 PASS、0.155 秒。target-UID 复核 own vault/journal 三变体不存在、marker 存在、persisted URI count0；package-filtered 授权查询有明确 `(nothing)`。原始证据 `/tmp/ctos-config-b-20261010/secrets-ui-diagnostic-0233e232-3fd5-41bd-a8b3-b334f6838d8e`。此单项不启动 Main、输入、provider 查询或改变 grant，是测试资源诊断，不计作产品功能验收。

依据该诊断、974 未调用 export/picker 的阶段证据及明确 root gate，仅对本轮自建 `im.majo.ctos.secretsfixture` 执行一次 `pm clear` 恢复空夹具；生产和其他包没有 clear。重读 main f358 APK/UID 未变，新 Application 前置 own rootauto=false 断言重新执行。

随后只运行 `freshFixtureManagementOnlyFlow`，不进入 SAF。实际仍失败：一项、13.948 秒、首个 `两次口令不一致` 等待未出现，run `a78cfd22-f337-4c16-b535-409cb9bc6b7e`。正确 label 的两个 distinct editor 已绑定，SET_TEXT 后三样本的 textLength45/expectedValueEqual=true，且直接 Button ACTION_CLICK 返回成功；失败后的相同两节点却 textLength=-1，submit 位置因表单布局变化下移。节点动作只广告 `[16,64]`，没有 SET_TEXT。缓存值更新不足以证明 Dart controller 接收实际输入；是否需要真实输入焦点/IME、或生命周期清草稿仍待进一步诊断。未据此修改产品或断言 root cause 已修复。

管理失败证据 `/tmp/ctos-config-b-20261010/secrets-ui-management-f6bd7acf-61bf-4c4d-9aea-8823f1dbfdc8`，含完整原始日志与 bounded 无文本诊断。失败后追加同包只读诊断 PASS、0.174 秒，再证 own库/journal三变体 absent、markertrue、persisted0、exact fixture grant query `(nothing)`。没有自动再次 clear，fixture 包/marker 保留，后续新驱动和管理项须独立冻结/放行。没有 create_success、文件选择或外部目录效果。

随后独立阅读当前本地 SDK 的 [AccessibilityBridge.java](../../../../.devhome/flutter/engine/src/flutter/shell/platform/android/io/flutter/view/AccessibilityBridge.java) `performSetText`：它派发 SET_TEXT 后直接预测更新 node.value 并返回 true。当前 [rendering/editable.dart](../../../../.devhome/flutter/packages/flutter/lib/src/rendering/editable.dart) 仅 `hasFocus && !readOnly` 时注册 onSetText。与上述未 focused、未广告 SET_TEXT 的实测节点一致，根因为测试驱动对不支持该动作的未聚焦字段执行 SET_TEXT；不是由这些证据证明的生产密码校验错误。下一版只修测试：先 exact editor CLICK，等真实 input focus 与 advertised SET_TEXT，再输入；仍需实际 validator/密文结果证明 controller 输入，不能仅复用预测 metadata 当验收。

## a0d1 管理项：已验证创建，重试焦点处失败

按 root 新 gate，只覆盖 fixture.test 为 `/tmp/ctos-secrets-ui-fixture-focused-input/app-secretsFixture-release-androidTest.apk` SHA256 `a0d103f116926c98e3dc5d266254e614c6c88a230f6af70075daf67295997557`，Java `eb8abceb40c2ab942aa8a3c0e24ee48c62d94f340c64c6376f4a2c7e176fc4bb`，v2/原证书、五源和安装读回通过。基于 c4c2 失败后只读 absence/grant 双证，仅再对 own fixture UID 做一次明确授权的 fresh reset；未对生产或其他包操作。setup 实际重新断言自身 rootauto=false、UID隔离和 Keyguard unavailable。

单项 `freshFixtureManagementOnlyFlow` 运行一项失败，19.637 秒，run `ccc9d3ad-4f6a-47a3-9020-3bc45c045003`。这次真实 focus + advertised SET_TEXT 输入已让口令不一致校验出现；后续创建得到终态成功并从 own VaultStore 拉取实际密文，以 original 主口令真正 KDF/GCM 认证解密通过。更换口令表单取消后实际 own 密文完整一致。随后手动锁、错误口令完整 UI 错误及密文不变断言执行通过。

失败发生在错误口令后的再次输入 original：同一个 editor 的 Android `isFocused=true`，但广告动作已只有 `[16,64]`，没有 SET_TEXT；驱动只在 !isFocused 时重新 CLICK，因此跳过实际重新聚焦，严格 SET_TEXT gate 五秒后拒绝。产品 `_submit/_clear` 会在提交和错误时 clear controller 并 unfocus；此处不能用 Android 的单个 focused 缓存标记代替可执行输入动作。已交 owner 最小修复测试重新聚焦条件，保持 unadvertised SET_TEXT 禁止要求，不改生产校验。

证据 `/tmp/ctos-config-b-20261010/secrets-ui-focused-management-c5c916e1-7441-4983-9554-5f598ed58f51`：完整原始结果、raw-free `diagnostic-stages.log`、安装/readback/UID及 own reset。实际 stages 为 `create_and_confirmation_pass`、`management_cancel_preserves_cipher`；不能宣称整个管理项通过。当前 own 库已经创建，业务条目为空、marker 保留；没有调用 SAF/export/import，也没有外部 leaf。此状态不符合旧 readonly method 的 assertFresh，故没有将其预期失败当产品错误；等待泛用 own-resource metadata 诊断与单独 reset gate。没有自动再次 reset 或跑 E2E，生产 ca85/数据不受该测试创建影响。

## 37af 泛用资源诊断及管理项状态等待失败

test-only 冻结包 SHA256 `37afadbdbfdd7189cb73da08a1d8fa60e9ff5244462b66b54c822be57a808803`，Java `d16d3741feb51fbd845bdf9748bb39efe9ed40906a01e6d1d693b202cf57a56b`，编译/v2/五源/安装读回一致。setter 改为总从 exact own editor 的 enabled/editable/advertised CLICK 重新聚焦，不再相信单个 cached isFocused；SET_TEXT 广告 gate 保留。泛用 readonly 允许已知 own 库 present，但严格断言没有 journal 三变体、持久 grant 或父级目录授权。

先单项 readonly 实际 PASS、0.172 秒；known own vaulttrue、markertrue、journalfalse/persisted0、精确 fixture package grant `(nothing)`，上轮 PID17243 查询仅 header 无进程。证据 `/tmp/ctos-config-b-20261010/secrets-ui-retry-diagnostic-b6368a7e-e1c4-40ed-8b7e-63cc112565bc`。根据这轮诊断和明确 root gate，才对 own fixture 再做一次 fresh reset，核 UID/main f358 未变。

37af 的单项管理尝试实际仍失败：21.337 秒、run `bd59be69-2fcb-4698-9022-252b0b7c61c6`。真实创建/确认/original 主口令认证、管理表单取消后的完整密文不变通过；点击立即锁定后，exact `已锁定` 的等待失败。尚未到 retry input、rekey 或 SAF。After metadata 仍在设置页，非 password 的两字符时间字段 focused；当时没有足够已知按钮/锁定消息 boolean 诊断，不能断言是产品锁定失败，亦不能把锁定通过。owner 将收紧设置动作同样使用直接 exact actionable Button，并等待源定义的完整终态消息及必要可见状态/scroll，再验证。

失败证据 `/tmp/ctos-config-b-20261010/secrets-ui-retry-management-958851cc-076c-4972-ad90-1c66c033a124`。追加同37af readonly 单项 PASS、0.165 秒：vaulttrue/markertrue、journal三变体false/persisted0、fixture grant `(nothing)`；旧 PID17916 不存在、新诊断 PID18308。该诊断只确认资源回收归属，不计产品管理功能通过。当前 own 库/marker保留，没有自动 reset 或 E2E；production untouched。

## d99 active controls：列表角色导致提交定位失败

候选 e24 在 review 发现 active-window gate 缺失后被 hold，未安装。修正后 test-only d99 APK SHA256 `d99aefdff7c7d6655da1fe2d89d09e4f5918f7448abdf5777fc27b5a583d15e6`，Java `174af44c50ca2132a233ea307fcea45a2126e7a5c49a0d48cab69405e720de6c`，编译/v2/五源/安装读回通过。TARGET 控件改为 exact direct advertised CLICK，角色仅 Button 和固定 nav/checkbox 例外；控件、notice 和输入发前均绑定 actual active scope window，scope/IME 不匹配时 wait/fail；滚动禁止 editable，并约束 active window 和方向。按照明确 gate 才 fresh reset own fixture，没有再动生产数据。

单管理项实际失败14.959秒、run `32745616-5acd-4da7-be81-66dd29509b4a`。本轮 mismatch 真校验通过；改正确确认值后 submit 查找失败，没有 create_success。bound confirm 位于可见窗口底部 y511，首次 submit 仅 y498–511 的13px区域可见，校验错误后 submit 不再出现在 visible nodes。原驱动滚动硬限 `android.widget.ScrollView`；独立阅读本地 [AccessibilityBridge.java](../../../../.devhome/flutter/engine/src/flutter/shell/platform/android/io/flutter/view/AccessibilityBridge.java) 847–879，HAS_IMPLICIT_SCROLLING 且 shouldSetCollectionInfo=true 时设置 collectionInfo，保留 generic View，不设置 ScrollView 类。此源码提供 fixture 列表角色缺口的具体线索；下一版须用实测 collection/scroll/action/window metadata 和明确 noneditable 集合角色，不能回退任意 View 或字段内部滚动。

证据 `/tmp/ctos-config-b-20261010/secrets-ui-active-management-8048bff1-c8ef-4b9b-b45c-e3d3199b5454`。失败后 d99 readonly 单项 PASS0.175秒：own vault/journal三变体 absent、markertrue、persisted0、fixture grants `(nothing)`，旧 PID19119 不存在、新诊断 PID19473。未创建外部 leaf/打开 SAF，没有自动 reset、跑 E2E或 production 改动；保留 marker/包待后续冻结 gate。

## 2e5 collection/keyboard：创建回执定位仍未通过

冻结 test-only 包 SHA256 `2e5eacaf5fd1dadfc779bb6c3745692172c27d15fc475454ef1c2c39e68c3a7f`，Java snapshot `a311f416bfe77861399013b513cdfca0624d23e0a498e2223e4d9312c481a454`，本地 compile/v2 同证书通过；配置 B 安装 readback SHA、fixture UID10142/testUID10143/instrumentation target 核准。依据 d99 postdiagnostic absence/no-grant、旧进程退出和明确 root gate，才仅 reset 自建 fixture 后单跑 management；production 数据保持。

本轮补充 scroll 只接纳明确 noneditable/advertised scroll/active window 的 ScrollView 或 bounded vertical collectionInfo View；只在 own Main、可见 IME、唯一 exact bound focus 时单次 BACK，随后必须证明原 exact editor/label/package/window 仍在。实际两次 `owned_ime_dismissed_current_fixture_window_and_form_preserved` 到达，真实 mismatch 校验通过；正确确认后直接提交执行。

实际 management 一项失败、36.201 秒，run `573e1050-9fd9-452e-aac3-325f83a128b5`：line119 等完整 `秘密库已创建。` 超时，没有 create-and-authenticate pass stage。界面已返回设置页，不能把回执定位失败当作创建未发生。日志首次显示真实 `android.view.View` scroll role：rows11/columns0/nonhierarchical/NONE、active window468、广告 forward；后续 After rows9，已广告 backward，30秒内只有一次 scroll_action。owner 正核对 lazy list 的子树 page-anchor 在滚出顶部后失效、不能再回到持久 notice 的测试驱动缺口；没有据此修改生产行为或宣布根因修复。

证据 `/tmp/ctos-config-b-20261010/secrets-ui-collection-management-387fec31-40cd-4ffd-99e1-31b684148fba`，包括原始 management、当前 run 无秘密 metadata、安装/readback/UID 和 own reset。未执行 SAF、创建外部 leaf 或授权目录；fixture 包/marker/可能已提交的 own 密文保持，等待单独只读资源诊断与下一 gate，不自动再次 reset，不宣称管理或 E2E 通过。

随后按单独 readonly gate，2e5 泛用诊断实际 PASS0.170秒、run `f4d62973-cd7e-4a6d-9417-444dbf66a417`：ownUID10142 vaultPresent=true、journal三变体false、markertrue、persisted0；exact fixture grant `(nothing)`，旧PID20352 已退出、新诊断20647。故库写入确已发生，但管理项尚未执行紧随回执之后的密文认证 oracle；不能把该 presence 当完整创建验收。After 自有设置页可见 status/notice/lock/unlock booleans 均false，仅说明当前滚动位置下不可见，不能读为库状态 false；readonly 不启动 Activity，诊断自身 activityPresent=false。上述资源双证仅供下一次明确 own reset gate，现场当前保持、不自动重置。

## a7c7 管理项通过（不含 SAF）

最终 test-only 冻结包 `/tmp/ctos-secrets-ui-fixture-toolbar-scroll/app-secretsFixture-release-androidTest.apk` SHA256 `a7c73fee1c1631add2752b995571680278ee2419ac173b738a906811d6641b81`，Java snapshot `e011145bb06464d41ba3168cd0af457f1e9b39e323b5a59308acff585f7d06cf`。本地 compile10秒/v2true/原8d36证书、五源 SHA 与配置B安装 readback通过，UID10142/10143/target不变；fixture main仍f358，production mainca85。

测试撤回全部 KeyEvent/keyboard BACK；滚动 fallback 新增同 active TARGET window 中、在 viewport 上方的 exact已知 AppBar标题（非editable/nonCLICK）作为页面证明。明确 bounded collection role、方向广告、viewport、多候选拒绝保持；没有任意 View/contains/ancestor click 放宽。历史2e5 BACK真实执行按其版本保留，不能当作a7c7行为。按2e5 readonly资源双证和 root 精确新 gate，仅 reset own fixture 后单跑管理，未进入 E2E。

实际 `freshFixtureManagementOnlyFlow` PASS、29.272秒、一项0失败/跳过，run `402be69e-ff32-4f00-930a-2ac134e6b0af`，真实阶段：

- mismatch 校验及完整创建回执；从 own VaultStore 读到真实密文，以 original 主口令实际 KDF/GCM 认证通过。
- 管理口令表单取消，own 密文逐字节不变。
- 实际手动锁定完整回执/状态；错误口令完整错误消息/密文不变，重试正确口令真实解锁回执/状态。
- 实际改口令完整回执；密文确改变、新主口令 KDF/GCM 认证通过、旧主口令 AUTH 拒绝。
- `management_only_pass_no_picker_fixture_uid_retained_for_reviewer_cleanup`。

collection View 的真实 scroll metadata 显示 `toolbarPageProven=true`，键盘下 viewport缩至511仍能找到 direct actionable Button；持久 notice 返回顶部后确被完整匹配。真实 Flutter UI 行为和读取合成 own 密文的 native 认证 oracle 分列，不能推定真实锁屏认证/TOTP业务/SSH/mixed或SAF通过。证据 `/tmp/ctos-config-b-20261010/secrets-ui-toolbar-management-0b589436-324a-4369-afac-473007513930`，含原始管理结果、当前run无秘密 metadata、install/readback/UID和own reset。成功后 own空业务库/marker 保持，尚未 reset/E2E，等待独立资源和完整导出恢复 gate。

## a7c7 完整 E2E：最终输入 action 采样时序失败，尚未 SAF

管理成功后同包泛用 readonly PASS0.184秒：knownownvaulttrue/markertrue/journal三变体false/persisted0、exact grant `(nothing)`、旧PID21301退出。fixture mainf358/testa7c7设备base读回SHA、UID10142/10143/target与source5重新核准；基于明确条件gate，只 reset ownfixture 后运行完整 `freshFixtureEndToEndFlow`，生产未改。

实际一项失败20.619秒，run `821766d3-9581-4ed2-8073-0fc716bf8141`。创建/确认/密文认证、管理取消密文不变、真实手动锁定/错误口令拒绝/正确解锁均到达；尚未完成第一次rekey，未调用export或DocumentsUI，没有新外部leaf/grant。失败为line125第三个改口令字段的 `setEditor` 发前guard：首次focus+advertised SET_TEXT 已满足，但字段当时仅y499–511可见；核验先前两字段的约35毫秒后，最终refresh的focused node暂时不广告 SET_TEXT，驱动按安全要求拒绝调用（line691）。After 同字段已经滚至y407–505且广告SET_TEXT再次存在，提供异步ensureVisible/semantics重建的具体时序线索；不能把此前支持cache或之后支持状态当作当时应绕过guard的理由，亦不能判生产输入bug。

已交owner仅test的最小有界最终等待建议：始终同原exactboundeditor/label/package/activewindow、enabled+focus+advertised SET_TEXT，未广告绝不调用，不使用BACK或重复已发 effect。新源码/immutable包及root go仍单独审阅。保留own库/marker和原失败证据，没有自动reset。历史同包management PASS29.272仍成立，不能把失败E2E计为通过或合成同一次全流程结果。证据 `/tmp/ctos-config-b-20261010/secrets-ui-toolbar-e2e-395caa6e-cd80-496f-aa4f-f89562b0df8e` 包含pre-reset只读/资源双证、安装包SHA及E2E原始结果/当前run无秘密metadata。

失败后同包 generic readonly PASS0.165秒、run `76ffe22f-6074-4276-b474-49939059da28`：ownvaulttrue/markertrue/journal三变体false/persisted0、exact fixture grant `(nothing)`，旧PID21659退出、新诊断21943；现场未reset。此元数据只核已知测试资源/无未决外部效果，不能替代E2E功能验收。

## c6a7 完整 E2E：首次系统导航拒绝，picker 已取消

下一 test-only 冻结包 SHA256 `c6a7afa61ae1b5c09da8bffe9a49ec5510325f6ef484134aa7717238cc9727ba`，Java `e5ee8a54dc81b04ba4fd2505262b64148d54365989428cea90a32134a6e6e52d`，compile8秒/v2原证书、五源与设备readback/UID/target通过。仅在最后 SET_TEXT 发前增加5秒同原节点/精确label/pkg/activewindow/enabled/editable/focused/广告action等待；就绪立即单次派发，没有未广告fallback、已派发重试或BACK。依a7c7 postfailure readonly/PID/无外部双证及root新gate，仅reset自建fixture后单完整E2E，不额外重跑管理。

实际一项失败33.437秒、run `45c4764e-5494-42f1-a89b-4bdabd2cc256`。真实create/确认/认证、cancel保护密文、手动锁/错误口令/正确解锁、rekey新认证旧拒绝均到达。第一次真实系统TREE picker打开后，测试 downloadDirectory 的旧 roots_toolbar fallback找不到，line477 `System navigation semantic action unavailable`。尚未选择ctos、创建UUIDleaf、点击Allow或授权目录；没有宣布导出成功。

实际 DocumentsUI versionCode30/versionName11，同包工具栏资源只读证据：`toolbar` 有标题TextView、`option_menu_create_dir`/`option_menu_sort`直接clickable控件和完整本地化“更多选项”ImageView；没有导航ImageButton/roots_toolbar。`drawer_layout`/`drawer_edge`存在，但未观测root侧栏或exact标准Internal storage标签，不能依据横屏猜测常驻导航。调查输出仅固定工具栏/导航资源、roles/bounds/action和标准label匹配，不保存文件列表、账号或未知标题文本；已安装DocumentsUI APK拉本地用于实际版本资源分析。

第一次失败后 genericreadonly PASS0.201秒：own库true/journal三变体false/markertrue/persisted0、exactgrant空、旧PID22241gone；系统picker仍RESUMED。随后按明确cancelgate核 `ActivityRecord b4ac6fd`、TREE Intent、callerUID10142 fixture、resultTo旧fixtureMain(t-1 f)；仅在该确切系统picker单次System BACK，确认picker离开，前台回既有production普通Main，没有再Back/生产输入。旧Main/进程已退出，不能要求已销毁Dart画面再显示取消回执。

取消后 genericreadonly另项PASS0.154秒、run `fa523d57-4f7a-4fd5-ae0d-21d99a74b390`：ownvaulttrue/markertrue/journal三变体false/persisted0、exactgrant `(nothing)`、原PID22241gone。未有外部leaf或provider.prepare；对应Bridge源码在tree返回前保留的是request.output字节数组，未见该路径私有prepared文件producer。这是源码边界，不能把journalfalse称整个cache已空，未扫描/清理其他Flutter缓存。每个UI调查临时XML均只删除本次精确路径。当前fixture保持，下一导航源码/包仍须freeze及root明确reset/E2E gate。

证据 `/tmp/ctos-config-b-20261010/secrets-ui-dispatch-e2e-a00c7276-e507-4874-9d15-2159a2da4feb`：原始E2E/只读结果、current-run安全metadata、工具栏资源、版本/调用者/取消前后foreground、exactgrant/PID和安装readback。本版失败与历史management PASS分列，尚无完整UI导出/恢复通过。

本地实际 DocumentsUI APK 反编译（仅调查产物）进一步确认 RootsFragment `onLoadFinished` 对过滤后root列表size<=1调用setRootsDrawerLocked；锁drawer时无导航按钮。该源码解释为何旧driver不能假定hamburger存在，但没有证明本次唯一root必为primary，也不能凭19字符自定义标题判断路径。按新只读APK许可，拉实际 ExternalStorageProvider v30/11 安装代码到上述证据目录，SHA256 `f297d324c1d2a150419a65004de647acef03858cc4280637adf38b6a75129d7c`，供本地标题生成规则/公开primary元数据复核；未查询用户文件或追加MANAGE_DOCUMENTS/Root/系统设置/grant。下一步只有可证明的exact primary breadcrumb导航才可接续。

## 65a2 公开 primary 映射只读探针

实际安装Provider源码 primaryEmulated分支读取 `Settings.Global.device_name`，空值用自身localized `root_internal_storage`；随后设置rootId=`primary`及root.title。与当前APK SHA f297的源码一致，不是由Build.MODEL或标题长度猜根身份。root明确许可此单Global公开字段及StorageManager primary元数据，不添加权限或改设置。

只读测试APK SHA256 `65a2dd26c4b9671cc9e6c527836a6d0b9dc3158a5acd6e39bf3a44a97b736f5e`，源码snapshot `9d630b918734249c1eeaaa8d2fb6124e9200d877f1335998a628d634e9690f0d`；compile10秒/v2原证书、设备readback及UID/target核准。仅覆盖fixture.test，单运行 `readPrimaryStorageMapping`，没有reset/Activity启动/picker导航/provider查询/系统设置写。

实际PASS0.184秒、run `520f8e1c-8880-4c89-99fc-ed49d5fe1351`，primary=true/emulated=true、derivedRootLabelLength19/publicDescriptionEqual=false。自身库true/markertrue/journal三变体false/persisted0、exact fixture grant空。日志只有len/bool，无device_name原值/未知root标题/账号。此结果证明公开来源规则可用，还没有将下一系统picker当前breadcrumb首项与derived label作实际exact匹配，不能称目录导航/授权通过。证据 `/tmp/ctos-config-b-20261010/secrets-ui-primary-mapping-0ac374fd-9d2a-4a83-9183-be0da7ac39e8`。

同时review发现旧系统find/breadcrumb/点击helper可命中同package的背景Docs窗口，已交owner窄fix。下一源码f870仅使用actual active scope root，对每个node/window/package/visibility约束；发前resource、祖先边界和Allow均refresh/active/广告CLICK，仍强制完整路径和localizedconsent。已sourcegate通过，未提前安装/运行E2E；65a2只读副本与源码snapshot保持独立，不因后续工作区source变化改写其测试归属。

## c3ac 完整 E2E：新目录已创建，自动进入时序被驱动误判

只读primary映射后无effects、exactgrant空与旧PID退出复核，按root明确gate安装 test-only `c3acb4b2ea075dd398f191de798c7f2a53a3e85e8b823b639e90123e9c524ad8`/Java `f870bbfc5e73bce007e7bcc5d7c4ba179a369f323bd8781db4bbaff49b09ede8`，compile9秒/v2原证书、五源与设备readback身份通过，只reset自建fixture单跑完整E2E。

实际一项失败60.233秒，run `7b6c810c-d690-4514-9c4f-a2e61787309d`。管理全部阶段含真实rekey通过；实际source-derived primary exact匹配、Download/ctos全breadcrumb导航通过，原生新文件夹dialog输入确认后，driver再次 `clickExact(leaf)`失败（chooseExportFolder:285）。没有点击UseThisFolder/Allow或取得grant，没有stage/final备份。

只读证明本次唯一 `/sdcard/Download/ctos/ctos-secrets-ui-7b6c810c-d690-4514-9c4f-a2e61787309d` 存在且空0children、mtime与本次16:06窗口吻合；当前fullbreadcrumb四段：source-primaryExacttrue/Download/ctos/ownedLeafExacttrue。最后breadcrumb和toolbar都exact本次leaf，但不可点击。实际APK CreateDirectoryTask.onPostExecute→onDirectoryCreated→openContainerDocument(newdir)会异步自动进入，最后breadcrumb刻意不提供CLICK；故driver在一次过早path采样后错误尝试点击不可行动末级路径。已交owner仅test窄fix：create时只等待有界exact完整newleafbreadcrumb，不新增点击，existing路径仍exactleafrowclick/fullpath；未据此改产品或把整个SAF验收通过。

Picker `566c14e/task250` 为TREE/callerUID10142 fixture/resultTo旧finishedMain。此前单次System BACK实际仅返回parent ctOS，没有取消；安全离开断言拦住rmdir，未继续向其他App发键。按root追加有界授权，每一步重新核同picker/caller/source-primary完整breadcrumb，depth2→1→0分别一次BACK，随后picker真正离开到既有production普通Main，立即停止（无生产输入）。

只在已证明离开后，再核精确leaf为目录、非symlink、空；只rmdir该UUID叶并回读不存在，不递归、不删除ctOS parent。precleanup ownreadonly另项PASS0.167；postcleanup另项PASS0.163、run `30de0609-8d1e-447c-9288-06b7d66c4bb4`：ownvaulttrue/markertrue/journal三变体false/persisted0/exactgrant空、原PID24316gone。原失败、诊断、人工授权的task-isolated取消/精确清理分别记录，不合成为自动E2E成功。临时XML均精确回收。

证据 `/tmp/ctos-config-b-20261010/secrets-ui-active-documents-e2e-298daadf-56fe-4237-b18b-7241f50d4b64`：原始E2E、exact-owned-leaf/完整安全UI metadata、caller、bounded-picker-cancellation、exact-empty-leaf-cleanup及两次readonly/grant/PID。候选01998/c8f789已sourcegate及compile/v2冻结，尚等独立root新E2Ego；当前包/own库保持、旧外部leaf已清，没有自动reset。

## 01998 三项真实导出已达，完整 E2E 在导入文件定位失败

按旧picker/空leaf精确回收与readonly settled双证，安装新 test-only SHA256 `01998a2a750c4868a6065a4fb3781fc47ff2c520cd70627aa4dd710b80b86ca7`/Java `c8f789dd437cb887975490a4eefc75a05a64da121af6f611f0608e45db2edc81`（compile9秒/v2原证书、readback/UID/target核准），只 reset自建fixture后单完整E2E。create目录模式改为等待系统异步自动进入完整exact newleafbreadcrumb；没有点击末级breadcrumb或放宽consent。

实际一项失败150.174秒，run `60268730-d86e-41fe-8c46-5ea0ef5b8e90`。管理所有步骤、真实系统新建导出/同名取消保留旧密文/明确确认覆盖并无残留均已执行；live只TAG采样实际观察到 `actual_saf_explicit_overwrite_verified_no_residue`（16:18:47），其前各原始stage因小device logbuffer有覆盖，不能补造。对应冻结源码从freshFlow(true)到该postassert stage的路径包含完整fullbreadcrumb、localized app+leaf consent、exact tree grant/noParent、导出密文逐字节一致及KDF/GCM认证、取消旧字节保留、覆盖只一个final。随后目标确rekey为Destination，目标密文认证通过且与Latest来源备份不同。

失败发生在第一次导入的 `chooseImportFile:310 clickExact(filename)`、freshFlow:169，尚未读入/验证错误来源口令，未开始source/target wrong/cancel/commit恢复验收。当前exact外部leaf+有效备份是真实已完成效果，不能凭失败而删除final或pmclear丢失证据；全流程不计通过。后续临时TAG collector只捕获到后期header，亦不能伪装早期完整原始日志；下一必要实跑会在启动前连续采集。

只读归属：实际OPEN_DOCUMENT picker `9ff83e3/task251` caller10142/resultTo旧finishedfixtureMain，intent type=`application/octet-stream`；完整source-primary/Download/ctos/ownedleaf匹配。exact `fixture-60268730-d86e-41fe-8c46-5ea0ef5b8e90.ctos` 存在、116bytes、mtime1791620323；正常shell getType及后续targetUID getType均为octet-stream，不是由这些证据证明的生产MIME过滤bug。只读own资源另项PASS0.173、run `5f1ca04a-d319-4d18-a176-995f523738a3`：ownvaulttrue/markertrue/journal三变体false/persisted0/exactgrant空、旧PID25614gone；grant回收不等于外部backup消失。备份和picker均保持，未删除、reset或卸包。

## b964 已定位 native document action 深度

root另放行只读诊断APK `b96477dcf185018c011af19eaea4d2f39265a284d1e96ce7877e3c302e0e829d`/Java `2e86251b243677e3c5744885fc7ff04196d0f29a37c5fabb34d74ad5f77dce8e`，compile10秒/v2原证书/readbackUID/target核准。仅安装fixture.test，并重核同一actual OPEN_DOCUMENT picker及full602路径；只运行hardcoded本次knownfile的 `readPendingImportDocumentActions`，无Main启动/UIaction/Key/provider枚举/权限或文件变化。

实际PASS0.346秒，run `937ef8ee-465d-4268-9416-02c3778fe7ac`。filename标题及父链depth0–5没有ACTION_CLICK；depth6明确 `com.android.documentsui:id/item_root` FrameLayout实际actions含16(ACTION_CLICK)，visible/enabled/currentwindow/package全true。depth7 dir_list没有CLICK。XML此前clickable=false不足以判断delegate广告action；本次实际action诊断才证明driver旧 depth<6（0–5）漏掉原生document row。普通targetUID getType readable=true/octetStream=true。最小测试修复应限定exact title→有界明确item_root角色/边界/广告CLICK并止于row，不能把通用祖先遍历无限加深或引入坐标、键和MIME降级。

01998失败及现有外部效果证据 `/tmp/ctos-config-b-20261010/secrets-ui-created-folder-e2e-77dac27e-611f-4a18-a2e4-c4d79028dad7`；b964纯诊断原始结果/actions `/tmp/ctos-config-b-20261010/secrets-ui-import-actions-fe94aa60-7495-4bd9-b814-5e5d2df0f7c1`。当前未cleanup外部backup/leaf、未reset/E2E新候选，等待root精确reconciliation gate。生产ca85/业务数据未动；完整双口令恢复仍未验收。

01998旧效果已按root精确reconciliation许可清理：同OPEN_DOCUMENT9ff83e3/task251/caller10142、每步完整source-primary路径证明后有界最多4次System BACK，确认picker离开即停。原E2E/只读diag进程退出、ownjournal absent/grant空前提已核。唯一exact文件为常规非symlink、116bytes、mtime1791620323、typeoctet；仅公开88-byte CTOSVLT1/v1/Argon参数/generation/declaredlength在内存核验，设备端SHA256 `c06eb647e46a90eef28a83d62909c807eb42d230af86a4c5b7dee526355fb518`，删除前metadata+SHA重复一致。只删该exact文件并回读absence，再rmdir空UUIDleaf并absence，保留ctOS parent。

第一版清理命令曾被自动审批整条拒绝：额外把完整加密payload留档不在精确header/SHA清理授权内；整条未执行，没有该次BACK/删除或本地cipher副本。按更窄方案移除额外步骤，用设备端SHA和公开header内存校验完成，未传输/保存完整payload，不请求用户重复许可。该真实审批拒绝和安全收窄不能省略或表述成审批通过的原版。

清理后b964泛用资源诊断另项PASS0.148秒、run `52b61bb1-6b50-4611-bfba-ba95799c651c`：ownvaulttrue/markertrue/journal各变体false/persisted0/exactgrant空、旧PID25614/26906退出。证据仍在77dac目录 `bounded-import-picker-cancellation.json`、`exact-owned-backup-cleanup.json`、`post-cleanup-*`。包/私有库保持；下一候选必须以refresh后same exact title→row lineage/role/bounds/activewindow/action再证明后才一次click，尚待重新freeze和rootgo，没有自动reset或装未审包。

## 8709 最终文件行归属修复与单项重跑

纯测试候选 `8709aa91320d45c357d5cf847ac7c502e8c79b38893e21a9871ce4b2846017c3` / Java `a15699d06c0302d8d02204a6c62903592ba29926945ad7090109e788fff51dd8` 已独立审阅：完整 active source-primary/Download/ctos/owned-leaf 路径之后，刷新 exact title，再≤12层重走其 lineage，第一明确 item_root 必须 equals 原 bound row，并在 dir_list 前停止；最终 row 再刷新核 FrameLayout/resource/pkg/currentwindow/visible/enabled/广告CLICK、新bounds包含当前title，单次 CLICK，false不重发。旧0f196候选未安装，未把通用目录祖先cap6扩大，未改生产行为或MIME。

compile10秒/v2原证书及五源清单核准后，按root明确hash gate，仅B覆盖fixture.test；设备安装base.apk SHA8709/mainf358一致，UID10142/10143及target fixture核准。初次安装后的本地校验把 `pm list instrumentation` 的过滤参数误给 test 包，因该参数过滤target包而空结果；修正为target包后通过。该机械断言发生时尚未reset或instrument，不算产品失败。旧602清理后readonly0.148、精确grant空/旧PID27674退出且无后续effects复核，随后仅pm clear本轮自建fixture，单项完整E2E于16:46启动，run `aee88d88-ce12-4265-8ef5-d500dc182d38`。

从instrument之前连续采集仅 `CtosSecretsUiFixture` TAG；文件含历史本任务tag缓冲，当前run须按UUID筛选，不把历史phase当新run。证据 `/tmp/ctos-config-b-20261010/secrets-ui-row-lineage-e2e-6877e236-115f-434a-adb0-7a5c541e824a`。此段记录启动事实；终态和外部effects另补，不预先计完整E2E通过。

8709此次JUnit终态 **FAIL227.504秒**（adb shell返回0不能当pass）。连续raw TAG完整捕获所有管理阶段、16:47:19新export verified、16:47:53碰撞取消保留oldbytes、16:48:19确认overwrite/noresidue，及16:49:36错误来源口令/错误目标口令/取消恢复预览保持Destinationcipher阶段。最后实际文件行点击成功，确认覆盖导入并出现完整typedUI成功notice后，测试在freshFlow:178要求 `assertArrayEquals(overwritten,cipher())` 失败，首差byte32。Destination拒绝/Latest真实UI解锁和自动exact外部cleanup尚未执行，不计通过。

独立源码证实此为测试oracle与合同矛盾：AppSecrets.java:338–340明确 `VaultEnvelope.encrypt(UUID.randomUUID(),1,sourceSalt,sourceKey,sourcePlaintext)`，VaultEnvelope.java:83生成新nonce，byte32恰为UUID起点。正确恢复应保留来源可认证明文、使用来源口令、fresh本地身份和锁定状态，不能要求整个新密文与来源备份逐字节相同。已交owner仅测试修正，未改生产去满足误断言。既有生产export4/realSAF3/Bridge2 SHA再次全部一致，diff--check通过。

按root单只读gate，8709 generic资源诊断另项 **PASS0.119秒**，run `ed07c083-bd06-4391-ad73-adf0fc26c086`：UID10142 ownvaultpresenttrue（导入后的fresh envelope现场保持）、markertrue/journal三变体false/persisted0/exactgrant空、旧E2E PID28504退出。没有Main启动、导航/输入、provider读取或文件删除。外部 `Download/ctos/ctos-secrets-ui-aee88d88-ce12-4265-8ef5-d500dc182d38/fixture-aee88d88-ce12-4265-8ef5-d500dc182d38.ctos` 和两个fixture包保留，等待精确reconciliation及新hash授权，不reset/uninstall。证据6877e目录 `e2e.log`、启动前连续 `continuous-fixture-tag.log`、`terminal.json`（wall229.646秒）、`postfailure-readonly.log`、`postfailure-own-state.log`、`postfailure-resource-projection.json`。

按root先只读exact资源gate，核aee leaf为非symlink目录且唯一成员为exact常规非symlinkbackup，116bytes/mtime1791622095/SHA256 `77d963ef39e3c9146e47ec9b0399db7498fb71d61bdc3ccefa2ff2866be13843`。公开header仅内存结构确认CTOSVLT1/v1/KDF1/Argon64MiB3/4/v19/generation3/declaredcipher28；没有完整payload传输/留档。原连续overwrite时序与唯一syntheticproducer相合，current focused/resumed不是Docs、旧PID28504/29073已退出、exactgrant空。证据 `exact-owned-backup-metadata.json`，本步骤只读，不据此删除。

owner仅测试成功oracle修正source `9e731e466acc3437544ee6db98f3a98b36813737006e017b89a8365c1692563a` 已独立sourcegate PASS：新本地ID分别不同于source/target、generation1、来源salt/新nonce/materialcontext变化、双方真实GCM+decode+完整plaintext equal仅boolean、char/key/plainfinallywipe、nullwrapper/UIlocked；再验证Destination拒绝和Latest实UI解锁及importedCipher稳定基线。外部backup仍按overwritten原bytes，前wrong/cancel原保护断言不变，entries/files明确0，无mixed业务外推。冻结新APK `83e6302e5a2e50a118e1edd99000f8c9800566a7bcaed6365e9d27b6831af2c3` compile10秒/v2原证书，尚等root资源cleanup与新E2Ego，未提前安装。

按root追加精确cleanup授权，aee文件删前两次复核116bytes/mtime1791622095/同SHA77d963…、公开gen3header及leaf唯一成员/非symlink，picker已离开、旧PID退出和grant空。仅rm已证明exact文件并absence，再rmdir该空UUID叶/absence，保留ctos parent；无递归/通配、无fullpayload传输保存。收据 `exact-owned-backup-cleanup.json`。清理后8709 generic只读另项 **PASS0.154秒**，run `0403a12d-3b27-4135-9465-109cd977a4f2`/PID29613：ownvaulttrue/markertrue/journal变体false/persisted0/exactgrant空，旧PID28504/29073gone。仅私有已导入库与包保留，未自动reset/安装新候选；对应post-cleanup-*已保存。

## 83e 按真实恢复契约重跑

root核8709旧effect精确清理/postreadonly0.154后，明确新hash gate安装纯test APK `83e6302e5a2e50a118e1edd99000f8c9800566a7bcaed6365e9d27b6831af2c3`（source9e731/compile10秒/v2原证书）。设备base读回83e与mainfixture f358一致，UID10142/test10143与runner target核准，旧PID29613退出/精确grant空，无后续effect，然后仅清本轮自建fixture，单项完整E2E开始。连续onlyTAG collector在instrument前启动，证据 `/tmp/ctos-config-b-20261010/secrets-ui-import-generation-e2e-3f7c6a55-d0f6-459c-a580-29d7ffd02df3`。此段仅启动记录，终态另补；未额外跑管理或修改生产数据。

83e此次完整实际Flutter+SAF E2E **PASS235.790秒，1项0失败/跳过**，run `aa14c7a8-5274-4db3-87a6-d67af20fa38c`/PID29860。连续TAG从instrument之前启动，按UUID分离 `current-run-only-tag.log` 与 `current-run-stages.log`，全部管理/实际新导出/同名取消保留旧cipher/明确覆盖仅一final/错误来源和目标口令各拒绝/取消审阅保持targetcipher/确认恢复/来源口令真实UI解锁及精确外部cleanup phases完整原始可核。

最后恢复已实际验证freshUUID分别不同于source/target、generation1、source盐/新nonce/materialcontext变化、无设备wrapper、来源和新库各真实GCM+decode且完整plaintext一致（全inmemory bool、finallywipe）、Destination KDF/GCM失败、真实界面Destination失败后Latest成功并保持已导入密文字节；外部backup保持Latest原密文。17:02:52.484真实确认/设置返回通过，17:02:54.041只删本次备份/空UUIDleaf/撤exactgrant，随后end_to_end_pass。该fixture明确entries/files均0，仅空业务库，不能据此证明非空TOTP/SSH产品或混合生产恢复。

终态后独立shell只核本次exactbackup/leaf均absent、ctos parent仍在、PID29860gone/fixturegrant空；再83e own generic只读另项 **PASS0.175秒**，run `ad9b18aa-7e07-4c70-9a56-5c1829b37d8e`：ownvaulttrue/markertrue/journal全部变体false/persisted0。随后仅卸本轮独立fixture.test与fixture主包，两个卸载Success、pm path空/pm list为空、exactgrant空；此ROM未安装pm path返回1属于正常absence，本地helper初次误视为错误停止在test已卸、main未卸，调整只读absence验证后完成main卸载，无额外测试或数据清理。

生产App保留UID10131，设备安装base.apk再次拉回SHA `ca85ec00dd7eb737454d4380e03fc6c6ae45343e3faac9fc68ab77b372116f3e`。仅正常Main启动与工作台→秘密库设置公开导航，无新Root授权/秘密输入/设置写；公开仍尚未创建、快捷不可用、后台30秒。`production-public-status.png` 实际view_image核查，metadata均true，任务临时XML精确回收。正常Main仍会沿其原Owner/cleanup及既有Root自动恢复，不声称本轮整体Keystore/Root-free，不断言某alias实际被回收。

最终证据3f7c6a55目录：candidate/installed/readback、完整E2E/连续TAG/当前run阶段、success-resource-receipt、post-success-readonly/ownstate、fixture-uninstall-receipt、production-main-readback/publicstatus JSON+PNG。source5/生产export4/真实SAF3/Bridge2最终SHA全部一致，diff--check通过，未重复旧104JVM/138Flutter/lifecycle6/SAF17+real1。当前无本轮外部文件/leaf/临时grant/fixture包残留；本地仅非秘密证据/APKs/公共截图，未保存完整加密备份或明文payload。成功认证、API28/29、真正SCREEN_OFF/系统进程kill、未测provider故障以及非空/真实两业务联合验收仍开放。

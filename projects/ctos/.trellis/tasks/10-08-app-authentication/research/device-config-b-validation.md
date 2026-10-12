# 配置 B：公共秘密库隔离设备验证

2026-10-10，用户指定配置 B，主会话授权安装本次主 APK/测试包及隔离验证。Reviewer 独占本轮 ADB，所有命令明确指定 `192.168.9.14:5555`；未操作其他设备、服务器或真实账号。原始证据仅在本地 `/tmp/ctos-config-b-20261010/`，不把历史设备结论替代本次结果。

最终阶段：主包 a309 保留；cb829 测试包的 17 项组合、b7 测试包的真实 SAF 六阶段单项通过，c4 测试包的独立精确清理通过。测试包已卸载、精确 tree grant/文件/私有合成残留已回收；详细哈希与各包范围见后续记录，不将全部结果归于单一测试包。

## 首轮设备与包

独立查询：T-CHIP / AIO-3568J，Android 11 / API 30，arm64-v8a，1920×1080 横屏，SELinux 当前 Permissive。未修改 Root、SELinux、网络、系统 PIN 或生物登记，未重启。

首轮主 APK：`build/app/outputs/flutter-apk/app-release.apk`，SHA-256 `6c0f00e61f7ad90bcd2e31b675027e619987cd191ec302c1c589946d55d8739b`。覆盖安装成功，安装后的 base.apk 拉回哈希一致，主生产源码和 APK 在首轮 13 项阶段没有变化；后续 Journal/Engine 修复重建 a309，见追加记录。

初始测试 APK SHA-256 `5517df88df8ba054c069d7092770ca246c42f2df9cdd3ee294f62fdc80efd16d`，安装读回一致。真实权限初始化失败后仅修改 androidTest 夹具，重编译、签名核验、覆盖安装及读回：13 项组合对应 SHA-256 `ac95d226a6c3a123fb5181c853e0de3c0b166216ee703d3f1ad71cae2908b744`，v2 签名验证通过。中间测试包 `4a20b146…`/`aa05c18b…` 属修复阶段，不是 13 项结果对应包；后来 SAF 清理修订与定向检查的包另见追加记录。

测试完成后仅卸载本轮 `im.majo.ctos.test`，主 App 保留。首轮公开设置前后均为“尚未创建”、快捷未开启/不可用、后台默认 30 秒；没有创建、导入、覆盖、打开或删除生产秘密库内容。实际 MainActivity 正常启动会初始化公共 owner、读取正常状态元数据，不能称为零生产状态读取；首轮平台加密测试独立使用自身 MemoryStore，不经 AppSecretsOwner/Bridge/prefs。

实际 MainActivity 启动还会由 Bridge 构造流程排入 `authentication.cleanupDisabledKeys()`；该正常生产路径可能枚举、回收本 App UID 内 `ctos.vault.quick.*` 的模块孤立 alias。因此整套 instrumentation/实际 UI 运行不能称为不触生产 Keystore，本轮没有记录其枚举/回收结果，也不据此断言实际删除了 alias。只有独立 AppAuthentication unavailable 夹具对象的 early rejection，保证在它自身 worker/store/key 效果之前返回；该独立对象没有调用上述清理方法。

## 真实首次权限失败与修复

首次精确运行 Store4/Journal5/Adapter1：9 通过、SAF adapter 1 失败。AMS 在 target UID 首次 acquire MANAGE_DOCUMENTS 保护的测试 provider 前拒绝访问，provider 尚未启动，因此 `onCreate` 的固定 tree grant 没有机会执行。尝试 shell 对 exact fixture 根查询也被拒绝，没有绕开保护或增加生产权限。

增加测试 APK 专属 `ExportFixtureGrantActivity`：不读取任何 caller 参数，只有固定 fixture document/root 查询，在 provider 自身 UID 下启动它。Provider 的既有 `onCreate` 仅对固定 `im.majo.ctos` 授予固定 fixture authority/tree/root 的 read/write/PREFIX grant，无持久 grant。Instrumentation 的实际 Binder UID 属 target；首次修复仍使用 testContext 发起 Activity，实机进一步拒绝包名/UID 不匹配，最终改为 targetContext 发起显式 test APK component 并取得 resolver。修复后单独 Adapter1 实跑通过；随后最终组合再次验证所有原夹具。

生产 manifest 没有测试 provider/Activity、MANAGE_DOCUMENTS 或新增存储权限。夹具资源来自测试 APK 私有 cache，不访问用户目录或用户文件内容；另行 UI 验证中的系统 picker 只显示其正常元数据列表，没有选中文档。

本轮源码清单：

- `android/app/src/androidTest/AndroidManifest.xml`：注册固定测试 bootstrap Activity。
- `android/app/src/androidTest/java/im/majo/ctos/ExportFixtureGrantActivity.java`：自身 UID 固定 provider 初始化，不接受 URI/package 输入。
- `android/app/src/androidTest/java/im/majo/ctos/SafExportProviderTest.java`：正确 target UID 上下文、显式 bootstrap、5 秒有界等待 grant。
- `android/app/src/androidTest/java/im/majo/ctos/SecretsPlatformTest.java`：三项 Android 真实 helper/crypto 隔离检查。
- 本研究记录。未修改生产 Java/Dart、design/spec/task 元数据。

## 13 项组合实跑（ac95 版本）

`instrumentation-13.log`：精确指定四个测试类，`OK (13 tests)`，30.211 秒；13 个结束状态均为 0，无 failure/skip。

| 夹具 | 实际验证 | 结果 |
| --- | --- | --- |
| VaultStoreTest 4 | Android AtomicFile 中断候选、取消后保留旧代次/包装、完整切换、强制 silent rename failure 的 uncertain | 4 通过，独立 UUID cache 目录清理 |
| VaultExportJournalTest 5 | 实际 AtomicFile 有界持久读回、超限 backup、角色别名拒绝、silent finalize、完成后读回异常不再 failWrite | 5 通过，独立目录清理 |
| SafExportProviderTest 1 | 真 ContentResolver/DocumentsProvider/grant，stage 验证前旧内容保留，确认后 fresh ID final/读回及 exact 清理 | 1 通过，provider 是隔离 fixture，非 DocumentsUI 的真实存储 provider |
| SecretsPlatformTest Window | 真实 Main Window 的嵌套 token、重复 stale release、background 保持、foreground 恢复、原 SECURE 继承 | 通过；此前真实口令路由已取消并确认普通可截图，未同时覆盖另一活动敏感 route 的位 |
| SecretsPlatformTest AppAuth | 真实 Keyguard.isDeviceSecure=false，availability=false，start 在 worker/store/认证 key 操作前返回 VAULT_UNAVAILABLE | 通过且未跳过；隔离对象未调用 cleanupDisabledKeys 或成功 enable 路径 |
| SecretsPlatformTest crypto | 实际 Android BC/Argon2id/GCM：own MemoryStore 创建、typed TOTP 保存/RFC code、错误口令不变、导出、更换口令、新库/旧包各自口令、带本次目标口令恢复、恢复锁定及解锁、真实 elapsedRealtime 0/1 秒 | 通过，全部合成数据/数组 finally 擦除，无 SSH 假 parser/reviewer |

Crypto 持久化为 MemoryStore；真实磁盘恢复由前四项 VaultStoreTest 单独验证，**没有宣称完整 KDF 加 AtomicFile 的联合崩溃恢复**已跑。Window 夹具直接验证一个自身 helper 和继承 baseline，不证明不同 coordinator 自动共享所有权；实际 Flutter route 生命周期另见 UI 观测。

`native-crypto-timing.log` 本次单设备合成样本：create 2035 ms；创建/typed save/认证/导出/改口令/恢复累计 22856 ms。不是通用性能 benchmark，也未测不同内存压力或持续负载。

仅测试源码变更的 `assembleReleaseAndroidTest`/`lintRelease` 组合通过；最终 lint 0 errors/5 既有 warnings，`git diff --check` 通过。既有未变生产 JVM/Flutter 检查未重复。

## 实际界面、截图、录屏及 picker

- 普通工作台和秘密库设置可截图，已实际 view `ordinary-home.png`、`secrets-status.png`、`final-settings.png`。
- 打开创建口令页，没有提交，只输入合成文本。实际 Window dump 含 SECURE；同一 `screencap -p` 在空口令/合成输入页两次均没有返回 PNG（0 bytes），普通页同命令正常。证据是该路径无法获得可用敏感页截图和真实窗口标记，不把 0-byte 文件称为可视截图。
- HOME 后打开最近任务，`recents-password.png` 实际 view：ctOS 卡片全空，没有口令页内容；返回时两字段为空。取消路由后普通设置 screenshot 正常，Window 不再含 SECURE，生产库仍 missing。
- 用合成来源口令进入真实 ACTION_OPEN_DOCUMENT；系统 DocumentsUI 可见，未选择、预览或读取任何文件。后台 ctOS Window 仍含 SECURE；按返回取消 picker，移除口令路由后普通工作台可截图/录屏，未留下导入预览或新库。
- 使用同一 `screenrecord --time-limit 3`：合成输入的安全页输出 1920×1080 H264，7 帧、encoded 3.315644 秒；首帧实际 view 显示 App 区域黑，系统普通 IME 仍可见但没有输入文字。App 上部 ROI 1920×440（y=50，排除系统栏/IME）七帧全部 `pblack:100`，见 `secure-video-blackframes.log`。不声称 App 能保护系统 IME 或整台设备所有窗口。
- 普通静态控制最初只有 1 编码帧、duration=0（该实现只编码更新帧）；追加同一 3 秒命令并同步滚动普通设置，得到 54 帧、encoded 1.806389 秒。`ordinary-motion-first-frame.png` 实际 view 可读正常 ctOS 内容，说明 codec/普通 App 捕获工作。3 秒是命令上限，不能把静态输出元数据误写为连续三秒视频。

上述为这台 API 30/ROM 的有限观测，不外推 API 28/29、所有 ROM、Root/同 UID/不安全显示、转场每一帧或长期录屏。

## 清理与剩余关卡

Store/Journal `@After` 清理通过；test APK 卸载成功，其私有 provider 文件和 grant 回收，`pm path im.majo.ctos.test` 为空，fixture authority 的权限投影为空。只删除本轮精确创建的 `/data/local/tmp/ctos-b-ui.xml` 与 secure/secure-input/ordinary/ordinary-motion 四个视频，没有按前缀批量删。最初四项删除后逐项 `ls` 确认不存在；新增 motion/XML 也单独删除。没有在 `/sdcard` 创建数据。主 App 安装保留，未 clear data/卸载主 App、修改库/后台偏好或其他设备配置；Android 日志只保留非秘密的合成耗时，没有清空系统日志。

尚需真正平台或消费者接入：

- 已有安全锁屏设备上的成功 DEVICE_CREDENTIAL/强生物 CryptoObject、真实 Keystore 登记/解封/撤销/失效；本板无 PIN 的 early unavailable 不能替代成功分支。未设置临时 PIN。
- API 28/29 分支；本轮只授权配置 B，没有访问其他在线设备。
- 真实存储 SAF 的同名确认/取消及两个受控 partial-write 场景已在后续六阶段实跑通过；仍待途中权限撤销、真正进程死亡后恢复、云端等故障及生产 Flutter 导出全流程。最初隔离 DocumentsProvider 不替代它，后续真实 provider 结果也只覆盖所列场景。没有为了导出而创建测试生产库。
- 真实已解锁生产会话的锁屏/进程重启/Bridge 调度擦除、双方业务入口和 transport；这里的 native timeout 与 UI background 观测只覆盖所列路径。
- 正式 SSH semantic validator/known_hosts reviewer/native consumer 和跨设备连接恢复；生产 mixed import 仍 fail closed。

这些剩余项保持 open；不据本次 13 项通过宣称全部 BASE 或业务联合验收完成。

## 2026-10-10 后续 SAF 夹具清理修订与定向实跑

主会话独立源审发现，原 `SafExportProviderTest` 在 try/finally 之前已 create old、write/close 并构造 transaction；setup 抛错会漏掉已返回的旧文件 ID，finally 的首个删除异常也会跳过其余已知角色。Reviewer 仅修订该测试文件，保留其他 agent 修改，没有改生产实现：

- 从 `old=p.create(...)` 起即置于 try/finally，写入/close/transaction 构造也受同一清理范围覆盖。create 未返回 ID 时不按名字猜对象。
- 去重记录已返回 old 和 transaction 的 original/stage/finalFile/rollback 精确角色 ID；成功 final 查询也使用已记录 final ID，并另外断言实际文件名。
- 每个 ID 独立 query/delete/readback，首个清理异常不跳过其他 ID；汇总清理错误，已有原失败时作为 suppressed 保留原失败，无原失败时才抛聚合清理错误。不按名字或前缀删除。

`saf-cleanup-build.log`：assembleReleaseAndroidTest/lintRelease 成功（13 秒），0 lint errors/5 既有 warnings，Java 编译与 `git diff --check` 通过。最新测试包 SHA-256 `a202eae5799952bf19c084f5174c0e80796c4f0fc3865d0d5f05a825c7759750`；`saf-cleanup-signature.log` v2 验签通过，配置 B 安装 base.apk 读回同一哈希。

只重跑修订后的 `SafExportProviderTest`：`saf-cleanup-targeted.log` 为 `OK (1 test)`、0.492 秒、结束状态 0。未重复未变的另外 12 项；原 13 项组合结果仍明确对应 ac95 版本，不声称最新 a202 包已重新全跑 13。此次定向结果验证本次正常事务及 finally 的精确清理；setup/多重删除故障分支的修订来自源审，并未另加注入故障实跑。

读回后再次卸载仅测试 APK，`saf-cleanup-package-removal.log` 为 Success，测试 package 路径及 fixture grant 投影为空；没有新增设备临时视频或外部文件。主 APK 保持 `6c0f00e61f7ad90bcd2e31b675027e619987cd191ec302c1c589946d55d8739b`，未改系统安全或用户库。上一节 MainActivity 正常 cleanupDisabledKeys 的可能 Keystore 作用已补充，不将独立 unavailable 实例的无效果保证扩大到整套 UI/instrumentation。

## 2026-10-10 真实存储 SAF 前置修复与新包 17 项复验

新增检查使用配置 B 同一目标和本轮新叶目录 `Download/ctos/ctos-saf-1d03a365-9016-4440-9038-bea216e45d39`；预检不存在，固定 `ctos` 父目录原本也不存在，故精确创建父目录及唯一叶，结束保留父目录。没有选择其他目录或文件。本节证据位于 `/tmp/ctos-config-b-20261010/real-saf-1d03a365-9016-4440-9038-bea216e45d39/`，前文未使用外部目录的说法仅对应前一轮。

源审发现 path-based document ID 的合法时序复用：旧 final 改名到独立 rollback 后，新建 final 可复用旧路径 ID。原 Journal 无条件拒绝与 historic original 的别名，不适用于此时序。负责人窄修 Journal：只允许有独立随机 rollback、旧对象元数据和对应 phase 的 final 历史别名，stage/current rollback 别名仍拒绝。Engine 在完整 query 确认不存在后才退休角色并确认持久记录；未确认 final 退休时禁止恢复旧文件到同路径，已验证 final 不删除，旧文件确认清理后消费历史角色。新增 3 JVM 和 4 Journal 仪器回归，Reviewer 独立源审通过。

`/tmp/ctos-real-saf-journal-final.log` 的联合检查成功（36 秒），实际 XML 共 104 JVM、failure/error/skipped 都为 0；lint 0 errors/5 既有 warnings，测试 APK 编译通过。主 release 重新构建、两 APK v2 验签通过，同 signer。安装后实际 base.apk 读回匹配：

- 主 APK：`a309bd9d46393af5dacc6eced11577f36f85ce60911cffcabc5f016e1db3dd9f`。
- 测试 APK：`cb829f963d8cc42235829a85277dfdcdbdcab2ae8cd6834c9a938fb473f3427f`。

`instrumentation-17.log` 为 `OK (17 tests)`、32.066 秒，17 项结束状态均为 0：Store4、Journal9、隔离 Adapter1、Platform3。这是新包的实际结果，前文 ac95 的 13 和 a202 的定向 1 保留为前版证据。Window/独立 unavailable/MemoryStore crypto 的范围仍按前述限制解释；没有重复截图或录屏验收。

真实 ExternalStorageProvider 的第一轮只运行 opt-in 单项 RealSafExportProviderTest。实际系统 DocumentsUI 已定位唯一空叶，截图 `picker-exact-leaf.png` 和 UI dump 确认完整 UUID；系统确认只授予 test APK 此树，并选择允许。测试却因 WAITING 回调 Bundle 的匿名 ResultReceiver 子类跨进程反序列化失败而 process crashed（`real-saf-1.log`），**尚未执行任何导出阶段，不能标实测通过**。叶目录再次确认为空。退出 helper 后发现 target 包仍持该 exact tree global RW、persistable/persisted 为 0，正在由 test-only target UID 精确撤销；不把 Activity 退出当作回收证明。原 random UUID 私有合成加密缓存因崩溃跳过 finally，暂未证明精确归属，禁止按前缀猜删。负责人修订测试回调/授权兜底及未来私有目录账本后，仅重跑该真实 SAF 单项，不重复已通过的 17。后续终态证据另追加。

## 2026-10-10 真实 ExternalStorageProvider 六阶段通过与终态清理

回调夹具修复仅影响测试 APK：两方向 ResultReceiver 通过 Parcel/CREATOR 序列化为系统基类，解包先设置系统 classloader，错误回调转为有界失败而非杀进程；目录撤权由 target UID 对 exact tree 兜底。新私有目录按本轮已知 leaf UUID 派生，写库前先记录合成 fixture marker，避免异常后身份失联。testcompile/lint 成功（13 秒）、0 errors/5 既有 warnings、v2 签名及安装字节读回匹配测试包 `b7c90f2b700a47b0ea0de39ca092fa07832677c3716435d7aed7a3e87c403021`。主包仍为 a309，没有重建或重复 17 项。

先单列诊断 `revokeExactTreeAndReportOrphanCandidates`：`preflight-1.log` 为 `OK (1 test)`、0.055 秒。App UID 对本轮确切 tree 撤权后，read/write permission check 都 denied，权限投影为空。诊断只读 reserved UUID cache 候选元数据、96-byte 公共 header，不解密、读取密文或删除候选，不作为业务验收计数。

随后真实 SAF 单项 `realTreeNewDeclinedConfirmedAndCancelledExports` 重跑通过：`real-saf-1-retry.log` 为 `OK (1 test)`、59.838 秒、结束状态 0。时间包含人工/ADB 树选择等待，不是加密性能基准。再次通过真实系统树 picker 选择相同且仍为空的唯一测试叶；六阶段共用一次临时 tree grant，不请求持久权限，不访问任何其他目录：

| 阶段 | 实际断言 | 结果 |
| --- | --- | --- |
| 新导出 | create 新文档、完整 cipher 写入/close/读回；实际 GCM 认证读取空合成库 | 通过 |
| 未确认同名覆盖 | preview collision 后拒绝覆盖，本次 stage 删除，既有 final 版本/字节保持 | 通过 |
| 确认覆盖 | old 改名返回独立 rollback，删除前读回完整旧 cipher；新 final 完整且实际认证含 1 个合成 TOTP，清旧 rollback | 通过 |
| 取消 preview | exact 本次 stage 清理，已存 final 版本和 cipher 不变 | 通过 |
| stage 中段写失败 | wrapper 在实际 provider 写入部分后注入 IOException，观察到真实 partial 长度，补偿后 remaining 空/旧 final 不变 | 通过 |
| final 中段写失败 | 旧文件已改名后，新建 final 实际部分写入再注入 IOException；退役新 final 后恢复旧 path，完整旧 cipher 与当前返回版本吻合、remaining 空 | 通过 |

`real-saf-stages.log` 中六阶段均 pass，**实际 `path_id_reused=true`**：该 ROM 的 ExternalStorageProvider 确实在旧文件改名后复用原 final 路径 ID。每次事务 Journal.save 后通过新 VaultExportJournal 实例 load，对比全部状态及四角色版本/位置；六次终态 clear 后 fresh load 都为空。这同时覆盖真实 provider 时序与私有 AtomicFile 记录解码，未以 no-op journal 代替。文件读取/写入由真实 SafExportProvider/ContentResolver 完成；测试 RecordingProvider 仅限定返回角色、核验 owned 内容和注入上述两个受控失败。

这两个 IO 是测试主动注入，**不是设备实际遭遇磁盘/云故障**。本轮没有真实进程死亡后重新选择树恢复、途中权限撤销、云 provider、任意 provider 原子性或生产 Flutter 主口令导出全流程；没有为了 UI 导出而创建生产库。真实存储 SAF 已通过所列场景，其他平台和生产混合 SSH 仍按剩余关卡解释，不据此关闭整个 BASE-10。

成功后 independent `after-success-grants.log` 的 exact tree 权限投影为空；测试 finally 的 exact owned-role 内容/版本清理、私有 marker/record 删除、target UID 撤权和 helper 终态 ACK 全部通过。`leaf-remove.log` 确认仅 rmdir 已为空的新 UUID leaf 并读回不存在，保留 `ctos` 父目录；`xml-remove.log` 确认删除唯一 `/data/local/tmp/ctos-real-saf-1d03a365.xml`。

首次 crash 遗留私有 cache 的归属由主会话根据唯一 producer、唯一候选、执行时间与固定布局判定并明确批准，不由诊断自行推导删除：`ctos-real-saf-3e0c2b6c-d365-4335-8db1-a4f1550518a7/vault.bin`，217 bytes/mtime 1791606899000，唯一固定成员，公开容器/加密 header 与零 wrapper 空间相符。负责人只新增独立清理 test：硬绑定批准 UUID/size/mtime，canonical 直系 cache 路径/唯一成员两次复核、公共 header 参数及尾部 wrapper-length=0；seek 跳过 cipher 不读取内容，再删除 exact file/empty directory 并读回。没有按前缀清理或碰 production noBackup。

清理专用包 `c4e8170ed0e053fefa3e5a82efef94015f25e7ae1ded47b3775923fbcf3a9cc5`：testcompile/lint 成功（11 秒）、0 errors/5 既有 warnings、v2 同 signer、安装字节读回一致。`approved-cleanup-1.log` 为 `OK (1 test)`、0.06 秒，批准对象删除/readback 通过。它只用于此独立清理；17 项的包仍 cb829、真实六阶段和诊断的包仍 b7，没有把此前结果谎称为 c4 全跑。新增清理入口以外源码字节未改变，源 hash 校验通过。

最后仅测试 APK 卸载，`test-uninstall.log` 为 Success；`test-after-uninstall.log` 空，`final-fixture-grants.log` 的 exact tree/fixture authority 投影空。主 a309 APK 保留、App data 未清空，未卸载主 App、设置 PIN/生物、修改系统/网络/Root 或访问其他设备。生产库/认证不参与真实 SAF 单项；正常 MainActivity 读取元数据和可能 cleanupDisabledKeys 的范围仍按第一节解释，不能把整套 17 项称为零生产 Keystore 接触。

本轮新增源码清单：`ExportTreePickerActivity.java`、`RealSafExportProviderTest.java` 和 test manifest；生产窄修为 `VaultExportJournal.java`、`VaultExportTransaction.java` 及相应 JVM/Android Journal 回归，由原 owner 完成，Reviewer 独立审阅与设备执行。报告保留源码修复/本地检查/真实设备结果/未测试项的区别。

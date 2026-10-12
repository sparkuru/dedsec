# 配置 B：真实 Bridge 生命周期隔离验证

2026-10-10 当前轮次完成；本记录仅对应下面冻结包与六项新增测试，不重复或合并此前 17 项平台、真实 ExternalStorageProvider 六阶段的包版本。公共验收仍有未测项。

## 授权、隔离与产物

沿用用户配置 B 及本轮明确安装放行，仅操作 `192.168.9.14:5555`。只读确认 T-CHIP AIO-3568J、Android 11/API 30、arm64-v8a、SELinux Permissive；主包 UID 10131。没有操作其他设备、设置 PIN/系统安全/Root 权限或真实账号，没有 force-stop、clear-data、卸载主 App 或写生产秘密库/后台设置。

冻结文件位于 `/tmp/ctos-lifecycle-final.9RFwueDn`。安装前 SHA256 与设备安装 `base.apk` 拉回后的 SHA256 完整一致：

| 角色 | SHA256 |
| --- | --- |
| 主包 `ctos-lifecycle-main-arm64.apk` | `ca85ec00dd7eb737454d4380e03fc6c6ae45343e3faac9fc68ab77b372116f3e` |
| 测试包 `ctos-lifecycle-androidTest.apk` | `577d54687631df40bc40b8d70ca08944aa7c83a2fb7f3177910365d2556a1757` |

两包 v2 验签通过、同签名证书 SHA256 `8d36c8be418174cb4cda0b897ff3e32b2da6f84032819726e8e5671872df52ef`。只安装上述临时目录的冻结副本，未使用后来独立 UID UI 夹具构建覆盖的输出。旧候选 `VbtP9mur` 已标作 superseded，没有安装。

原始证据目录：`/tmp/ctos-config-b-20261010/bridge-lifecycle-ca16381e-c652-40f7-8834-d62d3d35f257`。包含 preflight、安装/读回哈希、前后公开设置 XML、六项 instrumentation 原始日志、允许的 Lifecycle 元数据、精确资源列表及清理结果。

## 独立源审阅与修复

审阅真实 untracked 源码，并用 `/tmp/ctos-bridge-lifecycle-before.java` 对比生产 delta。修改仅由 host owner 实施；reviewer 直接协调并复核，没有同时覆盖其文件。

- `AppSecretsBridge` 增加 package-private 四依赖构造。生产构造仍取得唯一 `AppSecretsOwner`、原设置和私有 journal，并调用原 `cleanupDisabledKeys()`；测试仅注入 own Store/Preferences/Journal。
- 关闭变得幂等，明确前台状态为 false；旧 onStop 不再影响新上下文，背景 timer 在恢复/关闭时移除并检查版本。已排队的迟到 picker UI 不再向关闭的 executor 提交。
- review 发现 timeout=0 先撤销 session key 后原 schedule 提前返回，旧预览 key/plain/seed 与 operation 可能仍驻留。修复为总在 worker 清理失效上下文，仅取消 epoch 不匹配的 active request，并只丢弃过期 prepared export；已锁定同 epoch 的新认证请求保留。Main 不读取 worker-owned prepared 字段。
- 夹具初版迟到 picker 断言及 barrier 没有证明真实 queued UI 分支，已改成 Main 内提交 exportPreview、own-worker fence 确认 output 已准备且 UI 尚未分配，再 close 后释放 Main 队列。预期为真实 cancelled typed receipt，而非 Result.error。
- 夹具 teardown 独立汇总失败；所有 own workers 终止后才删 own prefs/files。mkdir 成功前不取得删除归属，exact prefs XML/.bak 必须不存在才创建。worker 未终止保留精确 marker/path。

本轮冻结源：Bridge SHA256 `47a65a7956a55a4ef508c56911dc2146b46945d37d879067b0d199ecbd3ddf16`；LifecycleTest SHA256 `c4be9afe60cf5af90f396c73d69aa08947adaca339d5c3618f970f732d6bc30d`。开始和收尾核验两源及此前 export/journal/真实 SAF 七文件 SHA 清单均一致。

## 本地与设备结果

独立核对冻结 XML：104 JVM = 35 Vault + 7 Session + 3 Writer + 26 ExportTransaction + 16 Bundle + 17 Restore，零失败/错误/跳过。104 实际执行发生在 timeout=0 修正后；随后仅夹具归属 guard 修改的构建复用未变 JVM 结果。最终 Android lint 零错误、五项既有 warning，测试 APK 编译及 arm64 release 构建通过。Flutter analyze 无问题，相关 35 项测试通过；此前未变的 138 项全量结果没有重复运行。

实际设备命令为显式目标 `adb -s 192.168.9.14:5555 shell am instrument -w -r -e class im.majo.ctos.AppSecretsLifecycleTest im.majo.ctos.test/androidx.test.runner.AndroidJUnitRunner`。2026-10-10 14:15–14:16（UTC+8）运行，`OK (6 tests)`，53.408 秒，无跳过/失败。

| 方法 | 本轮实际验证与边界 |
| --- | --- |
| actualHomeGraceRetainsTheSameSessionForTwoTypedConsumers | 真 HOME/onPause/onStop 期间拒绝新操作、宽限内保留 key，真返回同 epoch 两受限消费者可用；0 秒真 HOME 清 key。SSH hook 为合成固定身份边界。 |
| actualHomeExpiryUsesHandlerAndTimeoutSettingsNeverReviveKeys | 真 HOME 后 Handler 期限擦 key，不靠读取触发 lazy expiry；缩短期限即时锁，延长不复活已锁 key。 |
| manualLockCancelsLateAuthenticatedUnlockExactlyOnceWithoutWriting | own Store 读 barrier 下手动锁/迟到认证仅一次 Main Result，旧两 consumer token 失效，own 密文不变。 |
| closedBridgeCannotAffectNewContextOrResubmitLatePickerWork | 真 HOME 后关闭/替换 Bridge，旧 timer 与直接旧 onStop 无效；确定已排队 late picker UI 后关闭，收到 cancelled receipt、无 executor 拒绝/二次回调/残留 journal。Main 队列阻塞为测试排序注入，不是系统 picker 或性能实测。 |
| freshScopedCoreRestoresOnlyOwnTimeoutSetting | 同 own Store/prefs 的新 core/Bridge 只恢复 7 秒设置，不恢复 key；onResume 为直接测试调用，不是进程死亡重启。 |
| zeroTimeoutWipesRealPreviewButPreservesNewLockedEpochRequest | pure TOTP 真来源/目标口令 preview，经真 HOME0 + worker fence 检查 key/plain/seed 全零、preview/operations 已移除；同 epoch 已锁定的新 unlock 经 HOME/返回未被误取消。 |

普通 Main 的正常启动真实执行：日志 `root_auto_configured=true root_auto_value=true`，六个完成方法均观察 `root_active=true`，公开 UI ROOT 在线，没有出现或点击新的 su 授权界面。Main 自身 Bridge 初始化会排入 `authentication.cleanupDisabledKeys()`，可能枚举/回收本 UID 内模块孤立 alias。因此不能声称整轮 Keystore-free/Root-free，也没有证据断言本轮实际删除了某个 alias。独立 AppAuth unavailable 实例的早期无副作用边界仍仅属于此前特定夹具。

## 状态与回收

公开设置界面前后均显示：秘密库尚未创建；快捷未开启且当前设备不可用；后台解锁保留 30 秒。只查看，没有输入、保存或点击创建/认证。独立 own 测试设置没有写生产设置。

六个 UUID 路径和 prefs 名精确记录于 `owned-resources.json`。每方法实际通过的 target-UID @After 确认所有 own workers terminated、独立 prefs clear/delete 和每个精确 own 文件/空目录 delete 成功，失败均会汇总使测试失败；没有 cleanup 异常。Shell 对这些 exact 私有路径 stat 得到 Permission denied，不能视为独立 absent 回读；未使用 su 绕过。

`im.majo.ctos.test` 卸载返回 Success，pm path 为空；两项本轮 `/data/local/tmp/ctos-lifecycle-ca16381e-{before,after}.xml` 精确删除成功。任务相关 URI grant 投影没有匹配行。本轮不申请 tree grant、不创建外部文件。主 ca85 保留安装。

## 未测项

六项不证明真实 ACTION_SCREEN_OFF 系统投递、真实进程被杀/进程死亡恢复、成功 DEVICE_CREDENTIAL/CryptoObject 解封、API28/29、不安全外接显示、production Flutter 完整导入导出或真实 SSH/native 每跳恢复。板无安全锁屏，成功快捷分支仍需已有锁屏设备另行授权。production mixed import 仍 fail closed，合成 SSH hook 不解除业务关卡。后续独立 package/UID 的 Flutter UI 夹具需单独源码冻结、包身份核验和安装放行，其证据不混入本轮六项。

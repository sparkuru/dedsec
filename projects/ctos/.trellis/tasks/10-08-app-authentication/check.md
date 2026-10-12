# 公共秘密库与认证实施检查

## 2026-10-12 用户确认与阶段成果提交

用户在修复安装与真实快捷认证交付后回复“效果可以；提交所有相关 task 文件”。本轮授权提交公共底座实现/测试/spec/design，以及四个相关task的规划/研究/验收文件；不推送、不改变设备或未完成AC。沿用前轮冻结源码与实际检查结果，提交前补做范围、凭据/产物、JSON/JSONL及diff复核；底座active/in_progress，Authenticator、SSH父/客户端仍planning，不归档。

## 2026-10-12 恢复：安全锁屏真实快捷认证

- 当前授权目标192.168.9.14:5555 / AIO-3568J Android11 API30；用户已设置安全锁屏并授权自行继续测试。凭证只用于当前UI，不进入文件/记录；不改系统安全设置、真实账号或已有库内容。
- 原Cipher在系统认证前updateAAD；移动同一materialBinding至成功认证、执行加密前guard/epoch/deadline之后及doFinal之前，保留CryptoObject与认证参数以及后续持久化检查。新增固定独立UID诊断，公开能力/初始化/自身资源读取分开，初始化返回不冒称认证通过。原ROM具体异常未直接捕获，AOSP11缓存机制及同板红绿支持判断。
- 同一未变手动E2E旧实现登记失败100.698秒；修复后run290a2d3e-17e5-4ce3-9bac-afe3e69a7a3e **1项95.811秒通过**、0失败/跳过，真实登记包装、快捷解锁并认证解密未变空库、关闭取消/错口令保护、正确关闭/alias清理及主口令回退。独立reviewer读取原始日志，排除连续日志中历史run；无源码残项。
- 106 JVM实际重新执行全绿、analyze无问题、正常/fixture lint0错误3既有警告、instrumentation编译及APK验签通过。Flutter本轮未改未全量重跑，最新154为10-11结果。两轮ownstate诊断分别0.035/0.036秒；失败先核清再卸载/重建UID，成功后无未决journal/包装/授权/进程，两个fixture包已精确卸载。
- 主包 **71d00e7cab8460f3bd93e87f6dc6e8ccf2a3a58b2245f73a149791c586b6bcef** 已install-r并读回一致，UID10131保持。现有库正常UI开启快捷及锁定后快捷解锁成功，结束已锁定/快捷已开启，口令/内容未重建或更改；这是公开UI证据，不是已有密文内容私有读回审计。
- 成功快捷的本板API30凭证分支关卡已补齐；生物/API28/29、真实锁屏事件/进程死亡、非空混合及其他平台/业务关卡仍开放，不把整个BASE-03/12或任务标完成，不开始两个业务。active/in_progress，未提交/归档/dist替换。完整 [验证](../../../design/verification.md#2026-10-12-安全锁屏开发板快捷认证修复与实测)。

## 2026-10-10 继续任务：本地效果边界修复

- 恢复已有 `in_progress` 任务的当前指针；用户明确“暂时没有，先继续本地工作”。本轮没有设备访问、安装或安全设置操作，历史配置 B 结果仍按当时包解释。
- 新增两项回归，在原实现上均失败：typed mutation 在存储最终 guard 前到达操作期限仍提交；SSH 最后一次 host-key gate 使会话失效后仍释放凭据。已补最终 `clock.now() >= operation.deadline` 拒绝，并把 credential/private-key 最后 gate 放进 core，在 clone 后、`protectedEffect` 前运行，再向 receiver 释放。锁定/取消/恰好到期六场景拒绝，clone 均在 `finally` 擦除。
- 修复后实际 **106 JVM**（Vault37/Session7/Writer3/Bundle16/Restore17/Export26），0 failure/error/skipped，XML 2026-10-10T09:46:11–37Z；Flutter analyze 无问题、**138 全量测试**重新执行通过。Android lint 当前 **0 errors/3 warnings**（QueryPermissionsNeeded/AndroidGradlePluginVersion/DataExtractionRules），此前5项是历史报告。主 agent 独立核对红灯日志、源码顺序、XML、lint及Flutter终态。
- 增加显式手动安全锁屏夹具 `AppSecretsUiFlowTest#freshFixtureManualSecureQuickAuthenticationFlow`，实际独立UID release UI/Bridge/Owner 路径；仅 `ctosManualSecureQuickAuth=true` 且已有安全锁屏、fresh vault/journal/marker/alias 时启动。真实系统登记/解锁由人完成，等待只读自身包装和会话，不遍历/输入/记录系统凭证节点；关闭时取消/错口令保护、正确关闭和异步alias清理、口令回退/会话密钥认证空manifest均设断言。只有编译/源码审阅，不计认证成功；未来命令及产物见 [验证记录](../../../design/verification.md#2026-10-10-继续任务本地效果边界修复与手动认证夹具)。
- 最终fixture两APK打包12秒、正常instrumentation编译7秒通过；fixture main `61c49cb4…` / test `8be2cd33…`，v2同证书与target包核对通过，UI test SHA `05b7a759…`。opt-in fixture lint另跑12秒、0 errors/3 warnings。主agent独立核对最终源码/两包SHA、完整验签/target、构建日志；未运行任何instrumentation/真人认证。
- 日志 `/tmp/ctos-secrets-check-{red,android}.log`、`/tmp/ctos-secrets-check-flutter-{analyze,full}.log`，持久契约已更新 [宿主spec](../../spec/backend/app-secrets.md)。成功认证/API28/29、真实进程死亡及业务联合关卡仍开放；任务in_progress，未提交/归档或替换dist。当前源码已变化，原安装ca85包不包含本轮修复。

## 2026-10-10 配置 B 追加：Bridge 与独立 UID UI

- Bridge关闭/自身计时/迟到picker及0秒后台失效预览清理修复，独立review通过；保留fresh locked epoch请求。修复后实际104 JVM、analyze/35定向通过，lint0错误/5既有警告；此前未变138全量未重跑。
- 主 `ca85ec00dd7eb737454d4380e03fc6c6ae45343e3faac9fc68ab77b372116f3e` 与测试 `577d54687631df40bc40b8d70ca08944aa7c83a2fb7f3177910365d2556a1757` 编译/v2同证书/安装读回一致。B真HOME/返回等6项53.408秒通过；直接旧回调、队列排序注入和core重建另标，不称真实锁屏/kill/生产SSH。
- own6套文件/设置仅在workers退出后由targetUID精确清理，默认测试包已卸载。正常Main Root自动启动和模块alias维护如实记录；生产公开库missing/快捷不可用未启用/后台30前后保持。完整 [报告](research/bridge-lifecycle-config-b.md)。
- 显式独立包im.majo.ctos.secretsfixture/.test实际UID10142/10143，只关闭自身Root自动启动；主fixture f358不变。probe894导航/取消1项4.123秒及a7c7真实管理1项29.272秒分版记录。最终83e/9e源码审阅、编译10秒/v2同证书/设备读回一致，完整实际release Flutter/Main/Bridge空库E2E **1项235.79秒通过，0失败/跳过**：管理、新导出/同名取消/覆盖、双方错误口令/取消恢复及确认后fresh UUID/gen1/nonce/完整认证manifest相同、旧目标口令拒绝/来源实际UI解锁。全部阶段连续原始TAG保存；test内精确回收与独立readonly0.175均通过，外部文件/空叶目录/授权及两fresh fixture包已清理。生产ca85/UID10131读回一致，公开missing/快捷不可用未开/后台30保持。历史失败、一次被拒绝的额外备份留档及安全收窄、只读诊断均分别说明，不合并计数；完整 [报告](research/flutter-ui-config-b.md)。
- 主ca85已替代下节a309保留安装，历史17平台/真实provider六阶段仍属于原包；不合并为当前全部重测。成功认证/API28/29/未测SAF故障和两业务联合验收仍开放，任务in_progress，未提交/替换dist/归档。
- 最终主agent独立读完整235.79秒JUnit结果、全部连续stage、安装/设备读回/UID、独立readonly及卸载/精确文件和权限清理收据，并查看生产设置图。14项源码SHA保持、冻结UI报告SHA匹配；23 Markdown/203本地目标、4 task JSON/8 JSONL及引用路径有效，diff检查通过。Session12用`--no-commit`记录且明确任务仍in_progress，不把本轮验证通过写成公共底座全部验收。

## 2026-10-10 配置 B 追加：路径 ID 与真实目录

初轮设备验证后继续真实存储 provider 检查，发现并修复 journal 将历史 original 路径 ID 与当前 final 混淆，以及已删除对象角色引用未消费的问题。现仅有 distinct rollback/阶段/名字/元数据证据时允许历史路径 ID 复用；当前 stage/final/rollback 仍不得别名。删除角色需确认不存在并持久确认移除，未确认 final 移除时不恢复旧路径；恢复旧文件后保留实际 returned ID/version 直到历史角色消费确认；已验证 final 永不被回收。

- 源码 owner 与独立 reviewer 冻结 4 个生产/回归文件、3 个真实 picker/test 文件；主 agent 核对两份源码签名全部一致。
- Android JVM/lint/testcompile 退出 0、36 秒，XML 04:30:42–56Z：**104 JVM、0 failure/error/skipped**，26 项 ExportTransaction 含 3 个路径复用/持久失败/重启回归；Journal instrument 由 5 增至 9，含 14 个非法状态负例/4 个合法阶段及恢复，不将子场景计为独立 tests。lint 0 errors/5 既有 warnings；Flutter 源未变，不重复已通过的 138 全量。
- 新主 APK **`a309bd9d46393af5dacc6eced11577f36f85ce60911cffcabc5f016e1db3dd9f`**、平台测试 APK **`cb829f963d8cc42235829a85277dfdcdbdcab2ae8cd6834c9a938fb473f3427f`** 构建/v2 签名、配置 B 安装读回一致，证书不变，主构建 15.3 秒/32.2 MB。**17 组合实跑通过，32.066 秒，无失败/跳过**。
- 真实 picker fixture 在测试 APK 中，authority/本轮 UUID 叶目录/固定目标包严格校验、nonce/release ACK/有界 lease、exact 临时 grant 撤销；独立 VaultStore/真实私有 Journal/实际 ContentResolver，未启动 MainActivity/Owner/Bridge/Auth/Keystore。首跑跨 UID 匿名 ResultReceiver 类反序列化崩溃发生于导出前；修为 framework base Parcelable/known path marker，增加 target UID 撤权兜底。修订 APK **`b7c90f2b700a47b0ea0de39ca092fa07832677c3716435d7aed7a3e87c403021`** 验签/安装读回一致，**真实 provider1 六阶段通过，59.838 秒**；首次/拒绝/确认/取消及两种中段写入主动注入失败的实际清理/旧内容恢复均通过，真实 `path_id_reused=true`。17 项 source 未变，不重复；独立撤权/元数据 preflight1 0.055 秒单列诊断。
- 真实测试自身 cache/Journal/marker、exact tree grant/release ACK、空外部叶目录和临时 XML 已清理；只保留本轮新建 ctOS 空 parent。唯一旧 crash 加密缓存经主会话根据唯一 producer/时间/canonical 固定结构确认归属，strict UUID/217bytes/mtime/header/wrapper0 双检后 exact 删除与回读。清理辅助包 `c4e8170ed0e053fefa3e5a82efef94015f25e7ae1ded47b3775923fbcf3a9cc5` 编译/lint/v2签名/安装读回一致，cleanup1 0.06 秒通过；preflight1/cleanup1 均单列诊断，不计17+真实1验收。测试包已卸载、pm path/fixture grant/外部 exact grant 为空，主a309保留。
- 真实 native/provider 六阶段不替代生产 Flutter 导出口令全流程、真正进程死亡/云端/权限撤销中断或全部 BASE；无锁屏 B 仍不能验证成功快捷认证，API 28/29、生产会话及正式 SSH 接入待验。完整证据见 [设备记录](research/device-config-b-validation.md)。
- 最终主 agent 独立核对 XML104、17/真实1/cleanup1 日志、各包设备读回哈希、7项冻结源签名并查看真实 picker/初轮隐私图片；29 Markdown/217本地目标和4 task JSON/JSONL有效，diff检查通过。Session11以 `--no-commit` 记录；未提交/归档，待用户已有锁屏设备信息。

## 2026-10-10 配置 B：实际设备检查

用户指定配置 B，授权当前 APK/测试包安装和隔离验收；唯一目标 `192.168.9.14:5555` 当前核对为 T-CHIP AIO-3568J / Android 11 API 30 / arm64 / Permissive。主 APK `6c0f00e6…` 与最终测试 APK `ac95d226…` 验签、安装读回一致。以下本地记录中的未安装/未授权是此前阶段历史。

- **13 项全通过，无 failure/skip，30.211 秒**：Store4、Journal5、隔离 DocumentsProvider adapter1、真实 Window/认证不可用/Android crypto3。修复仅测试 provider 固定 bootstrap 及 targetContext 包名/UID，未增加生产权限；测试编译/lint 通过，0 errors/5 既有 warnings。
- BC/KDF/GCM 的合成 TOTP 保存、错误口令、导出、改口令、旧包恢复、0/1 秒超时在自身 MemoryStore 验证；磁盘 AtomicFile 另由 Store 夹具证明，完整加密磁盘联合恢复仍 open。create 2035 ms/组合 22856 ms 是单次板上样本。
- 真实合成口令页未提交：敏感 screenshot 两次无可用 PNG、录屏 App 区域黑、最近任务卡为空；取消后普通 screenshot/录屏恢复。真实系统 picker 打开/取消，没有读取用户文件；Window helper 嵌套/背景/继承基线通过，不外推全部 ROM/转场。
- 实机 `Keyguard.isDeviceSecure=false`，快捷在 worker/store/key 操作前拒绝；未跳过此检查。成功 DEVICE_CREDENTIAL/强生物 CryptoObject 与 Keystore 生命周期需已有安全锁屏设备；未设置 PIN 或生物登记。
- 无 key 操作结论只限独立 AppAuth unavailable 实例；实际 MainActivity 启动包含正常 bridge 状态读取和模块孤立 alias 维护，不称整套测试零 Keystore 作用、不据此推定实际删除 alias。
- 测试 APK 已卸载/fixture grant 回收、四个视频与一个 XML 精确清理；主 App 保留，公开库前后 missing、后台 30 秒。无真实账号、用户库修改、系统安全设置变更或外部备份创建。
- 主 agent 独立读最终日志/哈希、查看安全/普通录屏帧及最终设置；持久 [完整设备记录](research/device-config-b-validation.md)，临时原始证据 `/tmp/ctos-config-b-20261010/`。真实存储 SAF 覆盖/权限撤销/进程死亡、API 28/29、生产会话完整生命周期和 SSH 语义/native/双方业务联合验收仍开放，任务保持 in_progress，未提交/归档或替换 dist。

## 2026-10-10 当前冻结：公共图、导出事务及恢复审阅

用户决定的同名确认/失败精确清理及来源/目标恢复审阅已实现，最终联合源码审查无遗留必须修复项。此前章节的“当前/最新”、数字和包哈希按对应阶段历史解释，本节是最新本地结果；BASE/设备/业务联合验收仍开放。

- 最终 Android 组合检查退出 0、1 分钟：**101 JVM**（35 Vault/7 Session/3 Writer/16 Bundle/17 Restore/23 ExportTransaction）0 failure/error/skipped，XML 2026-10-10T03:25:43–57Z；lint **0 errors/5 既有 warnings**。4 VaultStore、5 Journal、1 SAF adapter 共 **10 项**隔离 instrumentation 夹具仅编译通过，未设备执行。主 agent 和 reviewer 独立读日志/XML/报告。
- Flutter analyze 无问题，**35 定向/138 全量**测试通过；实际 CJK/Material 字体 320 dp/2x/键盘渲染 1 项通过。主 agent/reviewer 实看同名确认/取消、成功残留/恢复、完整恢复审阅及底部动作；已知状态中文化，opaque credential binding 不显示为正文。临时日志 `/tmp/ctos-export-{analyze,focused,full,visual,android-final}.log`、13 项源签名 `/tmp/ctos-export-source-sha256.txt`，全部核对一致。
- 导出仅本次 tree grant，bounded 完整同名查询、随机新 stage/final、精确旧 rename-returned ID 链，绝不对原 URI 写流。journal 显式 fsync/持久读回与 finishCalled 防护，未确认记录前不清旧；恢复 role/version/content 不足不删替代文档。final 已验证后旧清理失败保留新备份。请求入队后取消/超时/session 变化和 exact cached receipt/recovery picker 取消保留真实终态，不显示假的已清理；坏 journal 报稳定错误且拒绝新导出，不妨碍普通库使用。不承诺任意 provider 的原子或 I/O 完成期限。
- 源码冻结后 release build 32.5 秒、32.2 MB；最新 APK SHA-256 **`6c0f00e61f7ad90bcd2e31b675027e619987cd191ec302c1c589946d55d8739b`**，v2 签名核验通过，开发证书 `8d36c8be418174cb4cda0b897ff3e32b2da6f84032819726e8e5671872df52ef`。测试 APK **`5517df88df8ba054c069d7092770ca246c42f2df9cdd3ee294f62fdc80efd16d`**。未安装、未替换 dist、未提交/归档。
- `human-required`：待当前目标/安装授权后运行 owned-file/provider 夹具与系统流程；API 28/29、30+ Keystore、截图/录屏/最近任务、真实 SAF/provider 授权/故障/中断及性能未验。正式 SSH validator/reviewer/native 尚未挂载，synthetic test 仅证明公共编排，相关 mixed 导入继续拒绝。独立与联合 AC 分列，不标产品全部通过。
- 最终 25 Markdown 本地目标、四 task JSON/JSONL 及其引用路径有效，`git diff --check` 通过；session 10 已用 `add_session.py --no-commit` 记录，无自动提交。设备安装需本任务当前授权，依据 `.trellis/spec/trellis-plus/validation.md`，不沿用历史设备结果或授权。

## 2026-10-10 追加决定：清理由用户自行处理

用户明确撤销验证码剪贴板清理，理由是职责/权限影响过大及越权风险；ctOS 提供 TOTP，验证码清理由用户自行处理。该决定取代旧 BASE-14 默认 30 秒可调清理要求，取消 clipboard 清理策略待决/平台竞态探针关卡，不取消后台库超时、敏感页面或用户主动复制/敏感标记。

已同步 BASE-14/15、AUTH-06/12、公共和 TOTP 的 PRD/design/implement、公共领域/架构/兼容/索引、代码规范及任务元数据。Android/Flutter 删除清理时长输入/参数/模型、status 清理能力字段、prefs 读写、copy ID/保留跟踪及合成 ClipboardRaceTest；仅 typed 当前 TOTP 主动复制附敏感标记，无读取/监听/清空/清理 timer。以下第一版检查数量/截图/包哈希按变更前历史解释，最新验证在本节追加。

- Android 最终检查退出 0、45 秒：**45 项 JVM**（35 VaultTest/7 Session/3 Export）0 failure/error/skipped，XML timestamp 2026-10-10T02:27:13Z；lint 0 errors/5 既有 warnings、4 项恢复夹具编译通过。临时日志 `/tmp/ctos-host-clipboard-removal-check.log`；没有设备运行。
- Flutter 最终 analyze 无问题、28 定向/131 全量通过，主 agent 核对 `/tmp/ctos-secrets-no-clipboard-{analyze,focused,full}.log`。后台库秒数的0/正数/超限和保存失败保留草稿仍验证，管理回执/请求修复保留，大字号标签已纠正；清理字段/控件/参数检索无残留。
- 27 Markdown 本地目标和相关 task JSON/JSONL 有效，diff 检查通过；旧历史研究记录保留但不进入当前验收要求。新 BASE-14 主动复制的设备业务集成仍未运行，不能因为移除清理就声明其他 BASE 全部验收。
- 最新设置 320 dp/1x/2x 实际字体渲染 1 项通过、6 张图；独立 review 实看 timeout 正常/大字及大字 backup，无清理字段、后台完整标签/保存可达。最终全范围 delta check 冻结，无额外已证实问题；其他平台/产品关卡保持开放。
- 最终 arm64 APK 构建/签名核验通过（30.8 秒、32.1 MB），`build/app/outputs/flutter-apk/app-release.apk` SHA-256 `72a2dd5513ccb085e031a64130d90384214d2413b5fabd719d9417231297e1d7`；证书仍 `8d36c8be418174cb4cda0b897ff3e32b2da6f84032819726e8e5671872df52ef`。未安装/替换 dist/commit/archive；新包替代本记录下面第一版 `36d3985d…` 作为当前可审阅产物。

## 2026-10-10 实施批准与恢复

- 用户在最新完整规划摘要后回复“批准”，授权先启动公共底座并前置协调 SSH 无明文磁盘文件访问探针。
- `task.py start .trellis/tasks/10-08-app-authentication` 成功，当前子任务为 `in_progress`；父任务不作为实现入口，TOTP/SSH 完整接入仍依赖底座验收。
- 服务重启后核对当前指针、task JSON 与 git 状态，确认启动已经完成、尚无产品代码/探针文件写入；恢复原 Android 宿主与 native 探针责任，另分派 Flutter 公共 UI。
- 四 task 的实施前产物/JSONL 门槛已检查：各有 PRD/design/implement，48 条真实文件上下文有效；文档 `git diff --check` 通过。
- `./hako flutter --version` 验证 Flutter 3.35.7 / Dart 3.9.2 可用。普通 sandbox 的 Docker socket 调用被拒；同一项目命令的 `require_escalated` 经自动审查批准后成功。这不是产品测试。
- 本轮未连接设备、安装 APK、运行真实账号或读取个人 SSH 配置；实际产品、算法、平台及 native 集成结论待后续检查。

## 技术关卡

- 公共密文格式/密码库/KDF 参数与资源限额需固定并验证，原子提交/崩溃恢复及业务命名空间不得仅靠 mock 断言。
- native 临时视图必须支持逻辑文件引用/目录匹配、Include、各跳继承和主机信任写回；普通磁盘明文 staging 不合约。
- 剪贴板条件清理的 API 核对/合成反例属历史；用户已撤销清理，不继续跨应用竞态探针。只验证主动复制/敏感标记和未扩大职责。
- 真实 Keystore、安全窗口、剪贴板平台行为及可见页面审阅尚未运行，不将本地单元/widget 测试记为设备验收。

## 验收状态

BASE-01..15 当前均未标记产品验收通过。实现、检查及未运行项将在本记录持续追加。

## 2026-10-10 源码及本地最终检查

- Android 公共核心/桥接、完整密文+包装记录、manifest、设备认证、共享单调会话、安全窗口和类型化 adapter 已实现；Flutter 公共初始化/解锁/管理/设置已接入工作台，未伪造完整 Authenticator/SSH 业务入口。
- 独立检查修复 API 28 集合工厂兼容、主线程读写/锁阻塞、错误口令重试 ID、晚回调解锁、typed mutation/target/connection/hostGate 实际效果复核、独立 exact trust proposal consent、snapshot 与 material binding、AtomicFile 无声提交失败读回和预览到期擦除。
- `./hako bash -lc 'cd android && ./gradlew :app:testReleaseUnitTest :app:lintRelease :app:assembleReleaseAndroidTest --console=plain'` 最终退出 0，39 秒；BC 1.86 冻结源码。主 agent 核对 XML：VaultTest 35、VaultSessionTest 7、VaultExportWriterTest 3、ClipboardRaceTest 1，**46 项全通过，0 skipped/failure/error**，timestamp 2026-10-10T02:19:19Z。lint 报告 **0 errors / 5 既有 warnings**。
- 最后一轮独立交错复核增加 `EffectGuard.beforeCommit`/`VaultRequestPhase`，guard wrapper 必须转发 hook；最终权限核验后声明提交阶段，与取消使用小 mutex 排序、不在主线程等待 I/O。pre-claim 取消保留旧记录，after-guard/after-persist 取消可能新记录已提交，返回 `VAULT_COMMIT_UNCERTAIN` 并锁库、不迟到解锁。取消/超时/close 统一分类，export-tag 阶段为 `VAULT_EXPORT_UNCERTAIN`。4 项新增 core 测试覆盖实际 rekey/持久状态及 create/import/shortcut/typed SSH hook 链；前一轮 42 项仅历史。
- `VaultStoreTest` **4 项隔离 owned-file 恢复/提交夹具编译通过**，没有设备运行；APK 为 `build/app/outputs/apk/androidTest/release/app-release-androidTest.apk`。完整宿主日志临时位于 `/tmp/ctos-host-final-check.log`，持久记录为本节及测试源；临时目录清理后不假定日志仍存在。
- BC JAR 和固定官方 LICENSE 哈希主 agent 再核对一致，见 design/dependencies.md。格式参数、限制及跨层接口已收录 `.trellis/spec/{backend,frontend}/app-secrets.md`，design 索引维护实际状态。
- Flutter 最终 `flutter analyze` 无问题、定向 28 项和全量 **131 项测试通过**。主 agent 核对 `/tmp/ctos-secrets-review-flutter.log`；取消的管理写入/导出等实际宿主终止结果可见并需确认，已完成操作不被本地 cancelled 标记遮蔽。新增 320 dp/2 倍字/240 px keyboard inset 回执可达性回归通过；前一轮 24/127 仅历史。
- 最终冻结源码的 arm64 release APK 构建成功（32.1 MB），`build/app/outputs/flutter-apk/app-release.apk` SHA-256 `36d3985d3b2cc6f32688fc62afb04a82375dce45dfe710c8288a249479bef3bb`；主 agent `apksigner verify --verbose --print-certs` 确认 v2 签名通过。开发证书 SHA-256 `8d36c8be418174cb4cda0b897ff3e32b2da6f84032819726e8e5671872df52ef`。测试包 SHA-256 `d8e400f9f9c4604ee6e4a34eec66d59fc906677f678d04f9047d7dfc861f5fc9`；未安装/替换 dist。最后全范围 review 冻结未见额外已证实源码问题；BASE/platform gates 未过，质量验收不记全通过。
- 新增宿主/前端七节规范和上下文索引；23 Markdown 本地目标及任务 JSONL 路径全部存在，最终 `git diff --check` 通过。所有 dirty planning/task 文档保留未提交。
- 原生 OpenSSH 研究独立重放 **6 PASS**，版本/源码/测试二进制哈希与实际范围见 `research/ssh-memory-results.md`，持久原始日志 `research/ssh-memory-validation.log`。Linux/glibc 结果不证明 Android/Bionic/完整 SSH 登录/每跳安全通道已集成。

## 未完成验收与回规划关卡

| 条款/范围 | 当前代码与证据 | 未完成内容 |
| --- | --- | --- |
| BASE-01/02/05/07/09/10/11/12 | 真实 KDF/加解密、混合格式、typed 操作和完整记录事务 JVM 检查 | 跨安装/设备、真实 AtomicFile 崩溃恢复；生产 SSH 语义校验/native 集成及受管文件图业务入口 |
| BASE-03/04/08 | 认证绑定 Cipher 分支、进程唯一 owner、monotonic session/late lock 检查 | API 28/29 与 30+ 真实 Keystore/强生物/凭证、服务与两业务完整生命周期 |
| BASE-06/13/15 | 无普通页面登录墙；敏感路由 owner/初始化返回和共享设置 | 真实截图/录屏/最近任务、目标 ROM、完整业务设置入口及人工观感审阅 |
| BASE-10 同名确认与失败精确清理 | 用户已决定提示覆盖、失败清理本次部分且保护原文件；新 stage/final/rollback 事务实施中 | 同名确认/故障补偿/恢复回执的源码与真实 provider 验证；清理或恢复失败须报告精确位置，不能称已清理 |
| BASE-14（新范围） | 主动复制当前 TOTP/敏感提示；用户清理，无 ctOS 清理设置/跟踪 | 主动复制/敏感标记随 TOTP 业务做目标平台集成；旧清理策略/竞态关卡撤销 |

此前主 agent 解释“探针”指验证旧规划中默认 30 秒清理并保留后续 clip 的可行性；只做 API 核对/合成反例，未读取或清理用户手机剪贴板。用户随后明确撤销清理，收敛范围见本文件顶部；其他平台/SSH/SAF 验收仍未完成，任务 in_progress，不提交/归档；本轮没有真实设备安装/账号授权。

## 下一步可审阅验证

提交审阅分类为 `human-required`，依据 `.trellis/spec/trellis-plus/index.md` 的平台/权限/观感规则：Keystore、SAF、截图/最近任务不能由 JVM/widget 检查替代；原产品约定还有两项实质关卡。这里记录待验场景，不构成当前设备操作授权。

- 经当前授权确认目标后，安装准确哈希 APK/测试包，在隔离测试文件运行 4 项恢复夹具；不删除生产秘密库、不覆盖既有用户秘密。
- 在无真实秘密的独立验收环境检查创建/错口令重试/锁定、0/30 秒后台期限、屏幕锁、快捷启用/失效/关闭、改口令及后台库保留设置；API 28/29、30+ 分列结果。
- 合成 TOTP-only 包经真实 SAF 导出/取消/读回/损坏导入/预览覆盖；输入源/目标不同口令、锁定或取消临界时检查明确的完成/不确定反馈。生产 SSH 包当前必须拒绝，不以夹具绕过。
- 320 dp 与软键盘检查管理路径，截图/录屏/最近任务不含秘密，普通页截图恢复；人工给出对应场景通过/失败。验证码清理由用户处理，不测试或重新实现自动清理。

共同 bounded bundle mutation 已在本轮补齐，生产 SSH adapter 的完整语义/信任校验和真实 native 集成仍在其接入时完成，混合包真正跨环境恢复属于联合验收；保持无明文磁盘 staging。独立公共合同/适用平台关卡与客户端联合 AC 分列，不能用“尚无完整客户端”阻止实现其自身；未完成的独立关卡和任务授权仍约束下一步，不据合同就绪声明完整产品通过或自行提交/归档。

## 2026-10-10 继续实施：公共文件图与导出事务

- 只读复核发现底座独立验收与真实 SSH 恢复 AC 的循环，证据见 `research/base-acceptance-boundary.md`。按既有责任拆分保留全部联合目标，公共接口先验证，配置/信任/native/实际可连接恢复在客户端接入时补联合证据；没有把 BASE-09 整体标通过或自动启动业务。
- 新增 `SshBundleProposal` 与 `SshVaultAdapter.bundleRequest/commitBundle`，native-only SSH_BUNDLE_WRITE exact capability，deep copies/full object CAS/bounded closed graph/default-deny ExactBundleApproval+validator（包括最后一个 SSH 对象删除），复用共同 commitManifest/material epoch/beforeCommit。有关联口令/共享引用删除拒绝，无级联；无关 TOTP/SSH 数据保留。真实生产 validator 未提供，相关写入及 mixed 导入仍拒绝。
- 最终 host bundle 检查退出 0、25 秒：**61 项 JVM**（35 Vault/7 Session/3 Export/16 Bundle），XML timestamp 2026-10-10T02:48:17–20Z，0 failures/errors/skipped；lint 0 errors/5 既有 warnings、instrumentation APK 编译通过，未设备执行。主 agent 已读 XML/log，独立 review 未见必须修复残项。临时日志 `/tmp/ctos-host-bundle-frozen-check.log`；新 backup 事务正在修改，因此这不是下一 APK 的最终全范围检查。
- 统一 SSH 父设计与公共 v1 的图上限：128 文件/单文件 1 MiB/文件总量 8 MiB/深度 8、完整 manifest 明文 12 MiB。旧 256/16/32 MiB 候选不再适用，超限完整拒绝，不截断字段或闭包。
- 用户对导出关卡给出明确新行为：同名提示覆盖，失败删除仅本次新创建的部分并保护原文件。已记录 stage/new final/confirmed old/rollback 的分角色方案及 create-new final，而非向可预测 final 名做覆盖 rename；同名/失败/补偿/重启明确回执正在实施。研究见 `research/export-transaction-plan.md`，尚未记平台或实现检查通过。
- 恢复公共核心暂冻结：新增版本化 `SshRestoreReviewer`、有界 `SshRestoreReview`、default-deny `ExactRestoreApproval` 和 core-owned consent；来源或已认证目标任一有 SSH 都需完整 coverage，包括纯 TOTP 覆盖目标 SSH。core 校验原始 provider 对象绑定后，credential 投影采用临时随机 HMAC opaque token，避免公开低熵口令的猜测验证器；reviewBinding 绑定实际显示投影/双方密文/注册版本。旧 bool-only mixed 提交拒绝，生产 hook 未挂载继续 fail-closed。
- 中间 JVM 检查退出 0、31 秒，主 agent 独立读 `/tmp/ctos-host-restore-jvm-check-2.log` 和 XML（2026-10-10T03:05:14–28Z）：**91 项**（35 Vault/7 Session/3 Writer/16 Bundle/17 Restore/13 ExportTransaction）零 failure/error/skipped。UI/导出后续必修项和适配仍在修改，不能当最终源码或 APK 验证；最终联合检查待冻结。

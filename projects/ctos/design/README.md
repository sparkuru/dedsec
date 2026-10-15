# ctOS 信息入口

2026-10-15 SSH当前最新窄增量（G3.7）：native 已接管 session 的 opaque handle、解析后
host key 和 public-view borrow；`ssh_session_jni.c` 已把五个 JNI entrypoint 接到真实
`ssh_native_client`，并强制 owner-thread、bounded key/fingerprint copy、opaque session
registry 与可重试 close/destroy。`ssh_view_handle` 的 revoke 会阻止新 borrow，已有 borrow
在 native session 释放前保持有效，避免 Java token 被当作 C pointer。生产路径仍只证明
open/revoke/close 的合成 JNI runtime，不代表完整 SSH 协议、认证或产品验收。

当前固定证据：normal hako gate 的 view-handle/native-client/callback host gate 通过，
JUnit **189/0/0/0**，production `SSH_PRODUCTION_OPENSSH_BUILD gate=pass fixture=excluded`，
native host `gate=pass runtime=not-run reason=qemu-aarch64-unavailable`，strict
`compileSshFiles`、三 ABI Release、lint、assemble 通过；APK SHA256 为
`dd9625677e7f874f19ec85f4f0e3f10b31ab8acd4ab71565d145ece4403f582a`，archive=83，五个
session JNI symbols 可见，APK 只包含生产 `lib/arm64-v8a/libctos_ssh_files.so`。独立
`secretsFixture` 在 `192.168.9.14:5555` API30/arm64 上分别通过
`SshNativeSessionPlatformTest#syntheticNativeSessionHandle` 与既有
`SshFileViewPlatformTest#syntheticProtectedFileViews` 各 1 项；两个 fixture UID 和包均已
卸载，正式包路径/hash 未变。task 仍 `in_progress`/`implementation_complete=false`。

2026-10-15 SSH历史窄增量（G3.6）：在 G3.5 production native C boundary 之上新增
Java `SshNativeSession` owner seam。打开、step、host-key verify、close/destroy 只能由
callback event-loop owner thread 驱动，`cancel` 是唯一允许跨线程的操作；
`SshNativeCallbackAdapter.NativeSessionClaim` 保证一个 callback binding 同时只有一个
native session。close 顺序为 native close（由 C boundary 负责 callback/KEX/socket）→
Java callback claim bookkeeping → role release → public-view release → native tombstone
destroy；每个阶段失败都保留可重试状态，失败 open 不消费 caller socket、callback 或 view。
host-key/fingerprint 进入 native binding 前复制，调用后清零。

生产 JNI 符号已接入 normal `libctos_ssh_files.so`，但 `ssh_session_jni.c` 当前对
`open/step/verifyHostKey/close/destroy` 统一抛 `IOException`：现有 C API 仍接收借用的
`struct sshkey *`/`ctos_ssh_view *`，在 native-owned handle、view snapshot/borrow 和
取消/关闭协议冻结前不能安全把 Java opaque token 当作 C 指针。JVM seam 使用 fake
`NativeBinding` 验证 owner-thread、claim、期限、清零和 retry 合同，不等于 Android
runtime/JNI/OpenSSH session 通过。

当前固定证据：full gate 的 JVM 为 **189/0/0/0**，`SshNativeSessionTest` 为 12 项，
callback host=20，production native 为 `gate=pass runtime=not-run`（原因是无
`qemu-aarch64`），严格 `compileSshFiles`、三 ABI Release、lint、assemble 全部通过；
production archive 仍为 83 个成员，session JNI symbols 可见且 forbidden=0；APK SHA256
为 `21d16325b242427c2ccfbb7aa9cf42c6ca359700dc7b7f52995a95de06b94e72`。未使用设备
`192.168.9.14:5555`、真实 vault、凭据或外部 SSH；task 保持 `in_progress`。

2026-10-15 SSH上一窄增量（G3.5）：已把受管 hostfile issuer、KEX guard 与真实 pinned
OpenSSH source/object closure 接入 normal `compileSshFiles`。新增固定 runner
`android-production-openssh-build.sh`：校验 G1 baseline/tree/patch hash，在临时构建目录用
OpenSSH 自带 `libssh-pic.a` 目标以 `-fPIC` 重编完整 10.6p1 `libssh` 成员，替换项目
stream-writer/KEX guard 对象，并纳入 PIC `ssh-pkcs11.o`/`ssh-sk-client.o`；最终归档
83 个成员，排除 stock 高层 caller。`ssh_native_client.[ch]` 现提供同进程 bounded
socket event-loop C boundary；open 失败保留 caller FD/callback，成功 close 先释放
callback，再解绑/释放 OpenSSH，最后释放原 FD，callback 失败可重试。

当前固定证据为 G3 fixture `run.aqN1F2bD`：`SSH_HOSTFILE_CALLER_BUILD gate=pass
runtime=not-run normal_apk=fixture-caller-not-wired`；生产 `testSshNativeClientHost`
为 `gate=pass runtime=not-run`，callback host=20，JUnit=177/0/0/0，strict
`compileSshFiles`、三 ABI Release、lint、assemble 全部通过；APK SHA256 为
`805fc28679aa56be85fa3be75a1608701273908a02f7fb04dddb717c4e1b601e`，ZIP 只有生产
`lib/arm64-v8a/libctos_ssh_files.so`（无 pinned fixture caller）。hako 无
`qemu-aarch64`，ARM binary、`192.168.9.14:5555`、真实 vault、凭据与外部 SSH 均未使用。
生产 C caller 已链接但尚无 Android runtime/JNI/Java 会话编排；认证、typed credential、
PTY、forward、service/UI、设备与外部 SSH 仍未验收，task 保持 `in_progress`。

2026-10-15 SSH上一窄增量：在既有 managed `AppSecrets` known_hosts provider
之上冻结独立的 opaque、同步、one-shot native→managed candidate handoff。
`ctos_ssh_callback_known_hosts_candidate`/JNI 只传 exact role/hop/target/connection/
host/port/key-type/fingerprint、base/replacement digest、candidate identity 和不超过
`MAX_FILE_BYTES` 的 body；现有 metadata-only CSHR wire 不变。managed receiver 在
body digest、snapshot 与 provider ownership 校验后才保留 private clone，后续仍由原
guarded AppSecrets CAS 才能返回 `COMMITTED`。JVM **177/0/0/0**、native client gate=27、
callback gate=20、strict compile/lint/Release 通过；APK SHA256 为
`3a6228f4237380dfc2cd98ccd537c16d4c6e81bc2e64e6205cdd6d556af9ded6`，普通 APK 未包含
host-only boundary 或 fixture OpenSSH objects。pinned hostfile issuer 已在 fixture seam
接入但 production caller 尚未接入，仍不实现 OpenSSH/KEX/auth/PTY/forward/service/UI、设备或外部 SSH，task 保持
`in_progress`。

2026-10-15 前一窄增量：已冻结并实现 role/hop/target-bound HostEvidence/CAS
native↔Java contract。`SshNativeCallbackAdapter` 的 typed host callback 传递
classification、key type、SHA256 fingerprint、32-byte base/replacement digest；NEW
host 必须经过 managed exact proposal、显式 approval 和 exact CAS `COMMITTED`，返回给
native 的结果只含 bounded metadata/digest/proposal binding，known_hosts body 不过 JNI。
native C/JNI 会重新校验结果并在失败、取消、期限竞争时清零；host callback gate=15、
JVM=166、strict compile/lint/Release 通过。这里冻结的是 provider seam，不是具体
known_hosts file/CAS writer，也不是 OpenSSH caller；`ssh_native_client.[ch]` 仍在缺
provider 时 fail-closed 返回 `ENOTSUP`，task 保持 `in_progress`/未完成。

2026-10-15 前一窄增量：pinned OpenSSH 10.6p1 source map 确认当前没有可编译的生产
known_hosts issuer/CAS native-facing contract；新增的 `ssh_native_client.[ch]` 因此只做
fail-closed boundary，严格校验 live role、PID/UID、CLOEXEC/NONBLOCK socket、callback
token/environment、deadline 和 bounded step，缺 provider 稳定返回 `ENOTSUP`，不进入
OpenSSH/KEX/JNI/credential。host-only gate=27，普通 APK 不包含该 boundary 或 fixture
OpenSSH object；真实 caller、provider、partial I/O/EOF/cancel/close retry、inbound
NEWKEYS proof 及完整 SSH 仍开放，task 保持 `in_progress`/未完成。

2026-10-14 SSH前一阶段：用户已确认同进程 native event loop 与当前 event-loop 线程同步附着 JNI callback；callback adapter 契约已编入正常 `libctos_ssh_files.so`，并新增 bounded managed-provider handoff：精确 role/hop-bound NEW proposal、host proof + KEX proof 绑定的 typed password/private-key bytes、Java/native 清零与 cleanup retry。严格限制 role/PID/UID/deadline、JNIEnv/JavaVM、线程、重入和取消；host `gate=pass value=11`、162 JVM、lint、Release 构建通过。正常 APK 的 SSH 相关条目仅为生产 `libctos_ssh_files.so`。另从当前 `.14` 用固定 Android arm64 OpenSSH 客户端成功访问本机一次性 Docker SSH pair；该 external smoke test 不等于生产 event loop/caller、具体 config/known_hosts provider/CAS、auth/PTY/forward/service/UI 接入，task 保持 `in_progress`/未完成。
生产下一步是把已实现的 managed file/provider contract 接到真实 OpenSSH caller；fixture 的 `ssh_api` 内存流与 control framing 仍只属于回归证据。

2026-10-13 SSH当前增量：Java role/trust/KEX/typed-secret bridge 已完成，bounded native role bootstrap 的 ownership/open/release JNI 已接入正常 `libctos_ssh_files.so`；native 输出清零、跨进程/UID close、stale token、Java registry 配对与失败重试均有回归。150 JVM、lint、host bootstrap 1198/1/1198、固定 KEX native 构建和正常 Release 构建通过；当前 APK `538cae5a…` 包含生产 public-file 与 role JNI。这里只证明 host/fixture seam 和 JNI contract，尚未由生产事件循环调用或接入 native launcher/OpenSSH callback、协议认证/PTY/转发/服务/UI或设备/外部账号；完整客户端仍开放。

2026-10-13 G2构建前阶段（历史）：TOTP用户验收交付已提交566ca51、归档f3762ba、journal74f8324，相关实测与未测边界保留。复用SSH客户端task；G0本地检查及用户专项批准后的`.14`独立AppUID **1项1.693秒通过**，仅API30/4KiB/Permissive，raw memfd完整候选可用、ASharedMemory文件形态不适用。夹具已卸载，正式APK/真实库未改。用户允许真实库操作，凭据不记录，远程账号未授权。G1固定OpenSSH10.6p1/OpenSSL3.5.9的Android ABI兼容基线已编译通过；当前实施G2可信合成上游文件与ProxyJump夹具，尚未构建或设备验证，尚无完整生产客户端。见 [SSH阶段验证](verification.md#2026-10-13-ssh-g0-本地实现与构建隔离)。

2026-10-13 用户已确认TOTP效果并批准提交验收、继续下一项：当前Authenticator交付收尾归档，实测及未测边界保留，下一项复用已有SSH客户端task，从Android native OpenSSH无明文磁盘文件访问关卡继续。下方同日in_progress/未提交为验收前阶段。

2026-10-13 当前设备阶段：用户授权“帮我安装、自动测试”，最终前台交接修复版 `3bc762d9…` 已安装 `192.168.9.14:5555` 并读回一致，221 Flutter/86定向/analyze与独立UID7项原生验证通过。真实系统双二维码选图与管理/生命周期全流程1项72.734秒通过，临时测试包/自有图片已清理，详情见 [设备验证](verification.md)。相机失败提示和成功预览均已补前台交接保护；该板0相机。此前复制自动审批拒绝已由用户专项批准解决，原生合成复制/敏感标记1项6.616秒通过，真实账号/跨设备混合恢复等仍开放。下方本地c704/214阶段按当时状态解释。

2026-10-13：[Authenticator](authenticator.md) 已按用户“复用原 task、先 TOTP 后 SSH”完成本地实现：工作台入口、相机/图片/手动添加、条目/倒计时/主动复制及公共库接入。214 Flutter、122 JVM、analyze、lint与45真实字体场景通过，最终arm64 APK `c704ff36…` 构建/v2验签通过；[本轮验证及未测项](verification.md#2026-10-13-authenticator-totp-本地实现与最终产物)。原task保持 `in_progress`，设备/真实账号及跨设备混合恢复尚未验收；[公共底座](app-authentication.md) 未测关卡不标完成。SSH仍planning，随后按既定顺序推进。下方10-12“无current/业务planning”均为历史。

最新源码 APK：运行 [`./preview.sh`](../preview.sh)，完成 arm64 release 编译后输出 APK 绝对路径和 `adb install` 命令；需要 Docker，具体检查见 [验证记录](verification.md)。

当前开发板入口：`192.168.9.14:5555`（用户2026-10-13更正），执行要求见 [约束](constraints.md) 和 [验证spec](../.trellis/spec/trellis-plus/validation.md#current-development-board)。

2026-10-12 最终交付：摘要DEVICE/Android运行行已移除，四分类范围描述统一、接口来源/数量保留。188全量/84真字体场景、analyze/格式及arm64/v2验签通过；25bcdf7c…已安装开发板读回一致，最终分类布局实测通过。按用户授权提交`5e67227`并[归档本任务](../.trellis/tasks/archive/2026-10/10-12-overview-settings/check.md)，当前指针已清除；[最终验证](verification.md#2026-10-12-最终概览文案收敛与提交)，既有成功认证/共享快照写盘等证据边界保留。下方current/in_progress描述为提交前各阶段历史。

2026-10-12 当前：[概览整合与设置迁移](overview-settings.md) 及四项截图细节已实施。分类均分全宽、Root标题旁信息按钮、终端眉标题精简、默认快捷/失败三选择解锁；188全量、84真字体渲染和arm64/v2验签通过，af600c41…已安装开发板读回一致。实际布局、快捷调起/Home取消/重试/主口令回退与AppPTY切换保持通过；成功系统认证本APK未重测。见 [最新验证](verification.md#2026-10-12-概览截图细节与默认快捷解锁)；未提交/归档，原公共底座未测关卡保持。

2026-10-12 用户确认修复效果，并授权提交所有相关task文件；公共底座的阶段实现/测试/spec/design与四个相关任务文件纳入本轮提交。底座仍in_progress，其他任务仍planning，未完成验收保留；见 [task check](../.trellis/tasks/10-08-app-authentication/check.md)。

2026-10-12 恢复公共底座：已修复系统认证前提交 AAD 导致的快捷登记失败。已有安全锁屏的 Android11 开发板上，同一独立UID真实空库流程旧实现失败、修复后1项95.811秒通过，涵盖登记/快捷解锁/关闭保护/主口令回退。106 JVM、analyze、lint与构建验签通过；主包 `71d00e7c…` 已覆盖安装并读回一致，用户现有库开启及快捷解锁也实测成功，结束为已锁定/快捷已开启。API28/29、生物及其他开放项仍未验收，task继续in_progress，见 [验证](verification.md#2026-10-12-安全锁屏开发板快捷认证修复与实测)。

2026-10-11 最新交付：四入口2秒双返回已实现，提示位于底部导航/安全区上方，普通Snackbar自动排在其上方；16定向/154全量、analyze/格式与arm64/v2验签通过，APK `981ef814…`。用户报告实机验收通过，并在下方提示交付后回复“可以提交当前 task”，本任务提交与收尾已获批准。用户反馈与agent检查分别记录，公共秘密库继续暂停，未替换dist或操作设备，见 [验证](verification.md#2026-10-11-退出提示移至下方)。

2026-10-10 继续任务：修复typed写入最终期限和SSH最后主机核验使会话失效后的凭据/私钥释放边界；106 JVM、138 Flutter重新执行通过，analyze无问题、lint0错误/3既有警告。新增独立UID真实快捷认证手动夹具，本轮仅本地准备，成功认证仍未实测；此前安装ca85不含本轮修复。见 [验证](verification.md#2026-10-10-继续任务本地效果边界修复与手动认证夹具)，任务仍in_progress。

2026-10-10 当前实施：公共秘密库宿主/Flutter、SSH 文件图、同名导出确认/失败精确清理及恢复审阅已实现。Bridge生命周期修复后104 JVM、35定向通过，此前未变138 Flutter全量未重跑。主包 ca85已安装配置B Android11板，6项生命周期53.408秒通过；独立UID实际release UI空库完整流程1项235.79秒通过，覆盖创建/改口令/锁定、新导出/同名取消/覆盖、双方错误口令/取消/确认恢复及来源口令解锁。测试文件/目录/授权/两fixture包已清理，生产公开库尚未创建、后台30秒保持；此前原包17平台/真实provider六阶段另记。成功快捷认证、API28/29、真实锁屏/进程死亡、非空混合恢复与SSH业务仍开放，任务in_progress，未提交/归档/替换dist。用户撤销剪贴板清理，保留主动复制/敏感标记，清理由用户自理。见 [公共契约](app-authentication.md#2026-10-10-当前实现与未完成验收)、[验证](verification.md) 和 [task check](../.trellis/tasks/10-08-app-authentication/check.md)。

2026-10-06 文案补充：顶部移除 `SYSTEM OBSERVATORY`，概览标签简化为 `DEVICE`；当前源码 analyze、103 项 Flutter 测试及格式检查通过。用户追加要求后已构建 arm64 release 包 `b7e67386…`，签名与校验和通过，未安装到设备。见 [验证记录](verification.md#2026-10-06-两处显示文案精简) 与 [变更历史](changelog.md#2026-10-06--显示文案精简)。

2026-10-06 当前交付：全 Flutter 界面优化已实施，统一主题与全部页面、900 dp 导航适配、大字布局及交互反馈。最终本地检查为 analyze 无问题、103 项测试通过、107 个实际 Flutter 渲染场景；release 包 `37d22600…` 已在配置 A Android 15 手机覆盖安装，安装哈希一致。用户已回复“观感通过，按计划提交”；实机交互见 [验证记录](verification.md)，任务见 [归档检查](../.trellis/tasks/archive/2026-10/10-05-flutter-experience-polish/check.md)。旧包/设备结论均按日期解释，既有 HFTP 熄屏断连限制未改变。

`design/` 是 ctOS 项目各种信息的统一 landing。产品规划、约束、架构、决策、兼容性、验证记录、依赖和变更历史都在这里维护。根目录 README 仅保留项目简介和使用入口；项目级 AGENTS.md 约定后续工作的执行规则。

## 阅读路径

| 文档 | 内容与用途 |
| --- | --- |
| [规划](plan.md) | 产品定位、能力范围、阶段交付与验收标准 |
| [改进机会](opportunities.md) | 基于当前证据的功能、体验、视觉与工程改进建议及优先顺序 |
| [全 Flutter 界面品质优化](flutter-experience.md) | 2026-10-05 全页面视觉、响应式、动效契约与实际复验入口 |
| [概览与设置](overview-settings.md) | 2026-10-12 信息合并、主导航、导出入口与设置/安全迁移契约 |
| [约束](constraints.md) | 工作范围、权限边界、采集与执行规则、质量要求 |
| [当前架构](architecture.md) | 已实现的组件、数据流与执行边界 |
| [Portable 五项工具与 HFTP](portable-tools.md) | 模块边界、SDK v2、文件能力、认证加密与后台文件服务 |
| [Portable 工作台与 SDK](portable-workbench.md) | 内置 Python、终端工具、运行包清单和脚本扩展契约 |
| [App 统一秘密库与公共认证](app-authentication.md) | 先行底座：主口令、加密库/会话/设置/设备快捷/备份/隐私配套；2026-10-12 恢复推进，API30凭证快捷实测通过，保留 in_progress/其余待测关卡，TOTP 与 SSH 共同依赖 |
| [SSH 终端规划](ssh-terminal.md) | APK 内客户端、OpenSSH config/一键连接和统一秘密库接入；in_progress，managed AppSecrets known_hosts CAS provider、production native C caller、native-owned session/view handle 与合成 JNI runtime gate 已接入，完整 SSH 仍开放 |
| [SSH 终端接管档案](handoff-ssh-terminal-client.md) | 下一 session 的当前提交、证据、授权边界、阻塞与可执行接管步骤 |
| [第三方 Authenticator](authenticator.md) | 离线 SHA1/6位/30秒 TOTP、工作台入口与公共秘密库；修复版已安装当前开发板，本轮非copy隔离自动验收完成，账号及未测平台关卡保留 |
| [兼容性](compatibility.md) | 设备差异、已验证范围与未验证项 |
| [验证记录](verification.md) | 检查结果、实机证据及复验方式 |
| [依赖来源](dependencies.md) | 工具链、版本、来源和许可证 |
| [变更历史](changelog.md) | 已实现变更与文档调整 |
| [移除 Vector 的决定](decisions/2026-09-26-root-only.md) | 作用域与在线服务的差异，App/Root 能力和边界 |
| [Trellis Plus 规则](../.trellis/spec/trellis-plus/index.md) | 开发流程增强及项目验证配置；任务结果仍以 Trellis 任务记录为准 |
| [Trellis 编码规范](../.trellis/spec/frontend/index.md) | Flutter 与 Android/Python 宿主代码约定；后端及原生层索引见 [Runtime 规范](../.trellis/spec/backend/index.md) |

## 当前交付与阶段记录

- [APK 主页双返回退出到桌面](../.trellis/tasks/archive/2026-10/10-10-home-double-back-exit/prd.md)：16/154检查与新版构建通过，用户报告实机通过并批准最新下方提示提交；已提交并归档，见 [检查记录](../.trellis/tasks/archive/2026-10/10-10-home-double-back-exit/check.md)。
- [可信状态与工作台基础体验](../.trellis/tasks/archive/2026-09/09-25-trustworthy-state-workbench/prd.md) 已补齐历史验收对应证据；当时的 Vector 要求后来被 [移除决定](decisions/2026-09-26-root-only.md) 取代，不属于当前 App 架构。
- [只读任务与选择性导出](../.trellis/tasks/archive/2026-09/09-25-read-only-task-loop/prd.md) 仅把 `device.info`、`memory.snapshot` 和 `network.interface_diagnose` 纳入有界历史；接口诊断走 App 权限，导出由用户逐项选择后使用 SAF。
- [界面与终端整理](../.trellis/tasks/archive/2026-09/09-25-interface-terminal-polish/prd.md) 完成统一 Card 表面、840 dp 阅读宽度、终端快捷栏折叠及输出搜索/复制。Flutter analyze、79 项 Flutter 测试与 release 构建通过；授权 `TARGET-BOARD` 的 App/Root PTY 定向检查见 [验证记录](verification.md)，不推断其他 ROM 或全新安装流程通过。

### 历史阶段记录

- 2026-09-28 P1 只读任务与选择性导出完成授权设备验收：Flutter 76 项、定向 Android instrumentation 4 项通过；应用强制停止再启动后历史仍可查看，SAF 单记录读回/取消和手动清空均实测通过。Root 自动恢复仅按用户授权随正常启动执行，未运行 Root 测试；见[验证记录](verification.md)与[任务 PRD](../.trellis/tasks/archive/2026-09/09-25-read-only-task-loop/prd.md)。
- 2026-09-28 Firefly AIO-3568J 已安装当前 `9ca71e31…` APK 与匹配测试包，Android 11 / API 30 的七项非 Root 定向测试通过，HFTP 仅使用 loopback 且结束后服务为空。Root、LAN、熄屏等未在此板复验，见[验证记录](verification.md)。

- 2026-09-28 停止反馈修复已安装9ca71e…包：73项Flutter、10项最终手机检查及三轮LAN传输/停止/同7888重启含31MiB通过，日志状态正常；原配置恢复、隔离文件精确清理、VPN保持不变。熄屏仍拒绝连接，App CPU锁登记不代表idle有效保活。独立Root临时CPU租约候选用户选择暂不扩大Root范围，未实现；任务in_progress，暂不提交/归档。见[验证记录](verification.md)和[实机记录](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/restart-device-check.md)。

- 2026-09-27 HFTP日志与可选Root网络中继已实现并安装0575e00d…包：ToolExecutionContext声明能力，ctOS adapter代理网络，Python/SAF保持App UID。68项Flutter、25项Python、native15+3及手机六项定向检查通过；既有VPN lockdown/不可bypass条件下，Windows已确认可打开，实际33MiB LAN传输通过，停止owned进程/端口回收通过。默认App配置及停止状态恢复，夹具已精确清理。前端spec追加不要demo式页面描述；源码未提交/任务未归档。见[验证记录](verification.md)与[Root实机记录](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/root-device-check.md)。

- 2026-09-27 九张图片反馈已实现并安装最终 `d43f52ed…` 包：去编号/技术详情、普通键盘及宽度统一；App HFTP 默认 LAN/7888/免登录，上传限额及本机目录持久授权。52项 Flutter、21项 Python、Android lint/构建、两种本机 provider 与33MiB实际传输通过；电脑直连7888仍超时，手机自身LAN地址与ADB转发可达，跨设备LAN尚未验收。服务停止、默认配置恢复、测试目录精确清理；源码未提交/任务未归档。见 [验证记录](verification.md) 与 [反馈实机记录](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/feedback-device-check.md)。

- 2026-09-27 工作台体验优化已实现并经用户授权安装到 TARGET-PHONE：工具前置、中文分层参数、专用结果及 HFTP 状态分区。analyze、43 项 Flutter 测试、Android lint、组件预览、构建和手机定向界面/键盘/SAF/来源哈希验收通过；新版 APK `98f44069…` 安装哈希一致，未提交或归档。见 [任务 PRD](../.trellis/tasks/archive/2026-09/09-27-workbench-usability/prd.md)、[工具设计](portable-tools.md) 与 [验证记录](verification.md)，设备结论仅覆盖实际场景。

- 2026-09-27 按用户要求提交当前项目进度并复核本地检查；ADB 在线目标为 TARGET-PHONE `PHONE-ADB-SERIAL`，默认开发板 `BOARD-ADB-SERIAL` 不可达。该进度提交阶段没有安装或设备功能验收，详见 [验证记录](verification.md)。

- 2026-09-26 `portable-tools` 五项已接入并完成 TARGET-PHONE 安装验收：31 项 Flutter、7 项定向手机测试通过，SAF 导入/取消/导出读回及 HFTP 后台通知停止通过，09 指定公共 IP 的实际 HTTPS 查询通过。该阶段 APK 为 `f928af66…`，安装哈希一致；依赖随 APK、数据在 App 私有目录，没有全局安装。详见 [工具设计](portable-tools.md) 和 [验证记录](verification.md)。

- 2026-09-26 本轮源码将原工作台改为概览、原命令改为工作台，移除系统负载采集；加入 APK 内置 CPython 3.13.9、终端 `python3`、四个脚本 item 及二级运行页。Portable 清单支持后续追加 Android 原生工具。构建、本地 QEMU 和实际设备结果分别见 [验证记录](verification.md)，旧包实机结论不自动适用于本版。

- 2026-09-26 当前终端版已加入 App/Root 双入口、底部单一输入、Shell Tab 补全、五个控制键及可选择复制的输出页；同时收敛几个页面的重复说明。当前构建与设备验收以 [验证记录](verification.md) 为准，Android 11 开发板尚未重测本版。
- 2026-09-23 旧包曾在 Android 11 开发板完成网络、PTY、Vector 桥接和导出验收；该包已由 current 包替换。
- 当前 `dist/ctos-current-arm64.apk` 已去掉 Vector/Xposed 依赖，保留连接快照状态、应用与分身映射及终端功能。Android 16 当前包的 App API、Root 和 PTY 五项设备测试通过，工作台确认 Root 自动恢复。Android 11、全新安装授权弹窗与完整导出在当前包未重测；历史系统桥接记录不再作为当前验收条件。
- 规划方向：手机本机的系统观测与操作终端，形成“查看信息 → 定位对象 → 执行命令 → 保存结果”的闭环。
- 第一阶段的只读任务闭环与选择性导出已完成本任务验收，其他产品体验机会仍按 Trellis 子任务推进。
- 开发板默认 ADB 入口和每轮授权目标均按角色占位符记录；历史验收与当前连接状态分开记录，不推定设备始终在线。
- 当前版本的增强采集仅依赖 Root；普通 API 基础路径保留。完整结果及已知边界见 [验证记录](verification.md)。

## 维护规则

1. 开始工作先读本索引、规划、约束及相关领域文档。
2. 新增项目知识写入本目录，并在索引登记；优先更新已有文档，避免同一事实维护多份副本。
3. 规划描述目标，架构描述实现，验证记录描述实际执行及结果。注明日期、证据和未验证项，不把计划写成已完成。
4. 有取舍的重大决策新增 `decisions/日期-主题.md`，记录背景、决定、影响及替代方案，并从本索引链接。
5. 临时原始产物不作为设计文档。验证记录注明产物位置及是否持久保留，不将凭据或敏感数据写入文档。

2026-09-24：原 `docs/` 下的架构、兼容性、验证、依赖文档及根目录 CHANGELOG 已迁入本目录。本次为文档整理，未重新执行历史验收。

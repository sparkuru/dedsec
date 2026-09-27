# HFTP 日志 / Root 能力后端研发记录

脱敏重放约定：执行命令前设置 `PROJECT_ROOT` 为项目绝对路径、`EVIDENCE_DIR` 为独立临时证据目录，并重建所列历史夹具；原始机器路径不保留。

日期：2026-09-27。范围为本任务 R14–R18 的后端实现；设备安装、最终 APK 及 Windows/LAN 直连由主会话验收。本记录不把静态声明、NDK 本机测试或 App loopback 当作 VPN lockdown 下实际 LAN 通过。

## 实现

- `HftpLogs.java`：process-local 会话 owner 缓存；最多 200 行，`JSONArray.toString()` 的 UTF-8 字节不超过 32768，单行不超过 512 UTF-16 字符。动态字段控制字符、方向控制符和行分隔符替换；只处理已知事件字段。开始新会话清空、停止保留，显式清空只清缓存。`HftpService.publish` 不将日志快照存进 state，避免清空后旧副本仍被 state 引用。
- `HftpService.java`：`hftpStatus` 包含 `logs:Array<String>`；时间 / 等级由原生格式化。服务准备、provider 类型、上限、监听地址、URL、实际 active VPN 提示、超时、退出及停止原因进入日志。stdout/stderr、启动 timer、退出、网络回调都验证当前 owner，旧实例不能改新会话 state / logs。未知 stdout、stderr 或 traceback 不复制原文。
- `HftpBridge.java`：`hftpClearLogs` 返回完整当前状态 JSON 字符串；`hftpStart` 起始回包含空日志。Root 标志为 boolean，LAN-only；缺省不请求 Root（最后一项与严格类型回归由 check agent 修订）。
- `ctos_tools/hftp.py`：保留 Python HTTP / SAF / PathStorage / 认证和文件边界。stdout 首行 ready JSON 不变，之后 `type=log` 结构化行；连接、请求、status、上传 / 下载字节、mkdir、错误类型可见。query、Authorization、请求体、完整目录路径、异常正文不输出。CLI stderr 为带时间和等级的普通文本，CLI Basic auth 保留。启动失败仅返回 exception class / errno。
- `HftpConfig.java`：`rootRelay:boolean` 默认 false，旧构造器兼容；偏好保存、目录更换保留标志；Root + loopback 明确拒绝。
- `ToolExecutionContext.java`：`declare(id, Requirement.APP|ROOT)` 只允许 `hftp` / APP 与 `hftp.networkRelay` / ROOT。缺失、未知、不匹配均在效果前拒绝。声明描述需求，不自动代表授权。
- `RootOperationAdapter.java`：唯一效果边界，只接受 Root networkRelay 声明。固定 APK helper `libctos_hftp_relay.so`，固定参数和正确引用的 `su -c exec`；无任意 shell / Python Root / 文件句柄接口。无 `su`、拒绝授权及 ready 失败是本次操作错误，不自动降级或修改 VPN。验证 host / external port / backend port / pid 与 `uid==0`。stderr 不复制原文，relay 事件与失败 reason 使用显式 allowlist。
- Root mode 中 `HftpService` 启动 App Python `127.0.0.1:0`，取得 backend port 后才经 adapter 请求 Root relay。实际非 VPN Wi-Fi IPv4 / 前缀 / network handle 由 Android API 选择；仅 relay ready 才发布 running，URL 是实际 Wi-Fi listener。Wi-Fi 丢失、地址 / capability 变化即停止，不 fallback；未调用全局 process network bind 或规则 / VPN 设置修改。
- adapter PING 与 STOP 同 monitor 序列化，心跳 fixed delay 2 秒；关闭写 STOP / EOF，等待拥有的子进程并强制结束拥有的 su 对象。native 另有心跳 / owner / EOF / 信号 / 会话 watchdog。App-only 路径不调用 adapter 或探测 `su`。

Python看到 relay 后的 peer 是实际 loopback连接；Root relay 单独记录真实 LAN client IP，不伪装 Python peer 或把两条日志当作已相关联的同一请求。

当前仓库没有可直接比对的原始 HFTP 独立脚本；本轮实现可读请求 / 传输诊断，不宣称逐字复刻原始 log 文案。

## 验证

1. `${EVIDENCE_DIR}/ctos-feedback-service-check/test_hftp_logs.py` 新增 4 项定向回归：真实 subprocess ready 第一行、HTTP request / transfer / error 事件、query / auth / body / path 泄露排除，CLI Basic auth 与可读 stderr，字段 allowlist / 长度 / 控制字符，启动错误不含目录原文。
2. 与原 21 项 HFTP / 流式下载失败回归一起运行：

   `PYTHONPATH=${PROJECT_ROOT}/android/app/src/main/python PYTHONDONTWRITEBYTECODE=1 uv run --cache-dir ${EVIDENCE_DIR}/ctos-feedback-uv-cache --offline --no-sync pytest -q test_hftp_logs.py test_hftp_feedback.py test_stream_failure.py`

   工作目录 `${EVIDENCE_DIR}/ctos-feedback-service-check`；最终 **25 passed in 10.32s**。证据 `${EVIDENCE_DIR}/ctos-hftp-log-python-tests.log`。首次在默认沙箱执行因 socket EPERM 失败，使用获准 scoped `uv run` 后通过；非实现失败。
3. `PortableToolsTest.java` 追加日志 200 行 / 实际 JSON bytes / 512 字符、控制字符 / IPv6 / query 排除、clear 后新 owner 可继续写、旧 owner append / event 丢弃；扩展真实 non-Root HFTP 生命周期测试，检查请求 / 传输日志、认证 / body / query 不泄露、clear 后新请求、stop 保留 / 再清空。新增 capability 未声明 / 未知 / 不匹配效果前拒绝测试，null Context 验证未触碰 Android / helper / su。check agent另加 Root flag 缺省与 helper App UID 拒绝测试。
4. 首次 `./hako bash -lc 'cd android && ./gradlew :app:lintRelease :app:assembleReleaseAndroidTest --console=plain'` **BUILD SUCCESSFUL in 29s**；证据 `${EVIDENCE_DIR}/ctos-hftp-log-root-android-check.log`。首次 lint 0 errors / 6 warnings（1 个新 fixed-rate scheduler 警告已改 fixed-delay，另 5 个既有）。随后 PING/STOP 序列化、flag 默认、uid 和 allowlist 等最终增量已交 check agent 统一复验；最终结论见 `root-log-check.md`。
5. `git diff --check` 通过。此 agent 没有执行 adb、安装、Root / 网络设备操作，也未构建 current APK。

## 交接

- 主会话需最终 APK / 签名 / source 校验、TARGET-PHONE Root helper UID 与 App Python UID、隔离目录请求日志、Root LAN 与普通 LAN 对比、上传 / 下载 / no-overwrite / 停止 / 清空等实机验收。
- Windows WINDOWS-LAN-IP 到手机 PHONE-LAN-IP，Always-on VPN lockdown 与 Clash allowBypass=false 是用户给出的条件，未在此 agent 修改。Root 网络绑定及策略是否许可，须看实际 helper ready / 请求与系统行为，不能从声明或本机 socket 测试推出。
- 其他 Android / ROM、Root manager、Wi-Fi 前缀以外来源及 OEM VPN 实现仍有设备边界；启动失败是显式状态且普通模式仍可使用。
- 没有修改公共 design / spec、提交或归档；主会话负责同步。

## 停止 / 重启反馈追加

主会话转述用户：Root HFTP 打开、上传、下载后点击 App「停止」立即报错，用户明确否认是再次启动。当前页面 `listener_bind (errno98)`。主会话随后读取本次 6 条保留日志，发现 18:46:52.432 Preparing → .497 Python ready → .579 relay startup bind98 → .782 closed after failure；这些证明当前错误来自一次新启动，不能据此断言停止本身执行了 bind 或解释用户完整点击时序。Native worker独立复现 TIME_WAIT 重启 errno98 并修复 SO_REUSEADDR，此缺陷与下述停止回调窗口分开验证。

源码证据：此前 Bridge `hftpStop` 调用异步 `stopService` 后立即 success；Dart 即刻 status，再释放 busy。Service直到 `onDestroy` 才设置 destroyed，期间 reader EOF / relay exit / startup失败的 Main回调仍通过 live；一旦在窗口内发布 failed，onDestroy又明确保留 failed/reason。另一方面 fail将state变failed后active原先返回false，旧实例资源清理尚未结束便可能被新的启动替换字段。

本次最小修复（源码冻结，设备执行仍由主会话完成）：

- Bridge调用 `HftpService.requestStop(Context)`，同步建立 stop-request fence 并发布 stopping / reason空；实际Android销毁仍异步。正常显式停止最终stopped / reason空，保留本次日志。服务已不存在时显式stop也清除缓存reason，保留失败诊断。
- live排除 stopRequested / failureRequested / destroyed；fail第一次立刻建立 failure fence，避免晚到同会话错误覆盖原始失败。停止请求优先把可见状态切到stopping，此后late READY / EOF / startup failure不能再发布failed。
- current实例持有资源释放claim。active包含当前实例及stopping；`hftpStatus`动态 `closing:boolean`，failed清理中也为true，current清除后false。UI worker按closing锁定配置 / 重启，不锁复制清日志。
- onDestroy只在Main摘取owned字段 / 注销Wi-Fi回调 / 移除通知，实际relay STOP/reap、Python停止、broker关闭移到 `ctos-hftp-cleanup`。该后台清理结束、owner仍匹配才发布终态并释放claim，不让Main同步等待relay的1500ms。
- 若Android在旧destroyed实例仍后台清理时创建新实例，其onStart会拒绝并 `stopSelf(startId)`；不覆盖旧session/resources，也不遗留未进入foreground的空Service。
- `RootOperationAdapter`固定错误allowlist追加native新的 `listener_reuse`。

定向原生回归代码：

1. `hftpRemainsVisibleInBackgroundAndStopsItsOwnedListener`：原有HTTP / 传输验收改为通过真实Bridge stop，立即断言stopping / active，完成后reason空 / closingfalse / listener关闭。
2. `stopRequestFencesRealProcessExitBeforeAndroidDestroy`：测试Context可控延迟Android实际destroy，调用两次requestStop，确认重复start被Bridge拒绝；仅强制退出该测试的App Python，join实际launch-reader后flushMain callbacks，断言仍stopping/reason空且仅1条stop请求日志；之后实际destroy到stopped。这是停止请求与真正EOF回调之间的确定性故障注入，与TIME_WAIT启动独立。
3. `replacementDuringDetachedCleanupCannotOwnTheOldSession`：owned Process包装为可控reap延迟，等后台cleanup开始后直接FGS启动绕过Bridge，确认新实例被拒绝、old owner不变、stopping/closing保持；释放reap到stopped。包装只影响本测试owned App child，5秒内自动释放，不使用Root或用户文件。
4. `explicitStopClearsAnAlreadyFailedStartupWithoutClearingLogs`：占用测试loopback端口使Python真实启动失败，等owned资源回收，然后显式stop清reason而保留失败日志。

此增量 `git diff --check` 已通过，Android lint/testAPK及上述TARGET-PHONE方法由统一checker / 主会话执行并补充真实结果；本agent未调用hako、adb、安装或current。不可把待执行的定向回归写成已通过。

统一checker追加发现与验证边界：初轮本增量Android lint/testAPK编译通过（28秒，checker报告，非本agent执行）；随后发现冷STOP无session时，默认null日志owner可能让beginStop错误标记stopping。checker负责加入明确session-null guard，并以不清空已有日志的独立unowned Service测试验证。checker同时补充 failed+closing 状态中的可控reap延迟与直接替换启动测试，确保原失败reason/owner保持、资源清理前不重启，清理结束后仍可显式stop清错误。生产源码和NativeTest已交其自修/最终复验，本agent此后仅更新此研发记录。

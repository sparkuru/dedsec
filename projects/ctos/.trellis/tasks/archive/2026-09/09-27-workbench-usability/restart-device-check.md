# TARGET-PHONE HFTP stop/restart device regression

2026-09-27. User authorized Root network relay and scoped TARGET-PHONE validation.
Target confirmed TARGET-PHONE `PHONE-ADB-SERIAL`, Android16. No system VPN/Clash,
routing, firewall or SELinux change is part of this regression.

User later changed the same device's ADB endpoint to `PHONE-ADB-SERIAL`;
connected and confirmed model TARGET-PHONE again. All resumed device commands use
this new explicit serial. Earlier ${EVIDENCE_DIR} artifacts are no longer present after
the interruption; recreate only task fixtures, preserve repository evidence.

## Final candidate after foreground correction

Rebuilt current APK with successful build/signature/checksum on resumed
session (Gradle29.7s), SHA256
`5d4e5cd46cf6356c6afef583354da8c45b1afaf4016aa30c63479e74397a95ab`,
26,191,916 bytes. Main/test installs succeeded on PHONE-ADB-SERIAL. All14 Python sources
still match and packaged relay still matches the stripped Gradle ELF hash
below. Service/test source hashes match the checker's final freeze. Build log
`${EVIDENCE_DIR}/ctos-hftp-restart-final-build-20260927.log`.

## Package and unchanged implementation boundary

- First regression candidate APK SHA256 `a43d8ca57ec25381067b9cbf9432bfd9f99cfa03a933da3a0e8d9edb09dd3557`,
  26,191,940 bytes. `./hako current` build/signature/checksum passed; main and
  test APK installs succeeded. Installed base.apk hash exactly matches current.
- All14 packaged Python sources match `android/app/src/main/python` byte for
  byte; Python remains HTTP/tool implementation. Packaged relay matches final
  stripped Gradle ELF, SHA256 `46d4ccdb3f836ab679eb8049005ca99876510c8ef89951092a950c40e1fb9fb5`.
- Source checks: Flutter73/73/analyze, final Android lint0errors/5existing
  warnings/testAPK, native5focused/20restart rounds+15existing passed. Evidence
  and limits in restart-check.md and restart-native-check.md.

## Baseline preservation and isolated fixture

Before install, no HFTP Service or owned helper process was found. Current
visible user config recorded LAN/7888/32MiB/default App private directory and
Root checkbox **true** (XML checked attr); do not replace this with the prior
turn's Rootfalse baseline. Current diagnostic logs are in-memory only and
were captured before package install.

VPN baseline: always_on_vpn_lockdown=1, active Clash VPN reports
bypassable=false. Read-only observations; no settings write.

Created new empty `${PHONE_DOWNLOAD_DIR}/HFTP-TEST-DIR` with mkdir
that fails on existing target. Planned SAF selection only this own directory;
no user files/readback outside it. Temporary bounded LAN runner
`${EVIDENCE_DIR}/ctos-hftp-restart-device-20260927.py`, SHA256
`efb17226e9e52b807b4ff46915a306810ea560db18ba0b321b5c50f371b3770b`,
uploads/reads only three unique public filenames, 1,376,257 bytes per round.

## Actual device results

First seven-method run **did not pass**. `explicitStopClearsAnAlreadyFailedStartupWithoutClearingLogs`
timed out waiting for failed: the occupied socket fixture used Java's default
loopback, which may resolve IPv6 while Python explicitly binds IPv4. Pin that
fixture to 127.0.0.1; do not change the production bind contract.

`replacementDuringDetachedCleanupCannotOwnTheOldSession` then crashed the
process with ForegroundServiceDidNotStartInTimeException: a new rejected
foreground Service called stopSelf without first fulfilling foreground
promotion. Its startForegroundService obligation remains even during
rejection. This is a real platform boundary to fix and rerun, keeping the test's
foreground start trigger. Shell exit0/INSTRUMENTATION_CODE0 are not passes.
Log:
`${EVIDENCE_DIR}/ctos-hftp-restart-instrumentation-20260927.log`.

Source correction, new final build/install and all seven tests pending. After
crash, dumpsys reports no ctOS service. No Root LAN session has been started
in this iteration yet; the created external fixture is still empty.

## Resumed final-source instrumentation and LAN results

Installed5d4e5c… base.apk hash matched. All **seven tests passed in7.135s**,
including the unchanged foreground replacement trigger and both controlled
cleanup paths. Log `${EVIDENCE_DIR}/ctos-hftp-restart-final-instrumentation-20260927.log`.
User interrupted during fixture preparation, then explicitly resumed. Current
config verified again LAN/7888/32MiB/private/Roottrue; selected only the task
directory via SAF. Its earlier path no longer existed, so recreated with
exclusive mkdir. Actual provider this time ExternalStorage (not the previous
single-cycle Downloads provider).

First Root cycle: PythonPID OWNED-PID/UID APP-UID, suPID OWNED-PID/UID APP-UID,
helperPID OWNED-PID/UID0, backend40683, physicalWi-FiPHONE-LAN-IP:7888.
LANTEST-CLIENT-LAN-IP upload201/download200,1,376,257 bytes with SHA256
`4604cefa7730305d4c02fba9781afb984a08c052e193f027da49a85be5300664`.
App Stop succeeded; LAN connection refused, all three exact PIDs absent.
Next UI Start used the same7888 port successfully (no waiting for TCP expiry).

Second cycle: backend43999, AppPythonPID OWNED-PID/su-OWNED-PID. Upload201 at23:00:23;
GET200/Python reports download complete1,376,257 bytes at23:00:23.284, but
client bounded read timed out15s. Native closed client at23:00:38.351; current
closed-event diagnostic lacked the reason. No Stop command was executed after
this request because the independent metadata tool approval timed out before
execution. That read-only check was retried with a narrower adb-filter command.

Device later screen-off; waking with KEYCODE_WAKEUP revealed Root stopped
reason=heartbeat_timeout at23:13:53, then Service failed and closed owned
resources. This is a **separate later event**, not proof it caused the earlier
download stall. Screenshot `${EVIDENCE_DIR}/ctos-feedback-hftp-restart-round2-awake-20260927.png`.

After a fresh same-directory start, GET-only existing public file2 succeeded
both with bounded read1 chunks (Content-Length1376257/exacthash,0.465s) and
the original response.read1376258 (clipped to Content-Length,exacthash,0.197s).
`HTTPResponse.read` clipping and `_download` Content-Length verified in source;
do not dismiss the timeout as an extra-byte read without evidence.

Native large-download stress, queued-heartbeat expiry regression and closed
reason diagnostics now under implementation/review. Repeated final-package
LAN cycles, log interactions, restoration and fixture cleanup remain pending.
# 最终停止/重启回归通过，熄屏仍未解决（2026-09-27至28）

当前安装9ca71e316525e5b5ba6a771f855f3df2d3b52342eb75162594e7972c3518c2e3
与dist逐字节哈希一致。修正后的测试APK3cc68b0823d1cbde047484ab0cb088b82e5f2dde7ac048cbb6714dcb10319be1：
三项CPU检查先1.25s通过；服务停止后完整十项9.077s全部通过。保留下面旧批次9/10失败，不覆盖历史。

亮屏实际LANTEST-CLIENT-LAN-IP到手机7888三轮PUT201/GET200均完整，完成读回后才点击App停止：

- final1、final2各1,376,257B，SHA4604cefa7730305d4c02fba9781afb984a08c052e193f027da49a85be5300664。
- final3共32,505,856B（31MiB，在原32MiB限额内），SHA34e8470b5e0b3995aaad4f1df187dfb099c754b802fb963cd2e0294b87fd3dd5。
- 每轮App停止后owned Python/su/helper均无，RootHftpRelay活动锁无，LAN拒绝连接；检查结束即正常UI重启同7888，不等待TCP自然过期。第一轮PID OWNED-PID，backend36645；第三轮backend45333。未记录第二轮PID，不推造。
- 长日志滚动/折叠展开、清空后重新31MiB GET200/hash一致1.382s、返回重进通过；日志显示closed reason=complete，无灰块。运行截图${EVIDENCE_DIR}/ctos-feedback-hftp-current-logs-20260927.png。最终当前PID OWNED-PID未见DiagnosticsProperty/ErrorWidget/Flutter fatal匹配，但此前PID的完整错误日志未采集，不据此宣称整个历史无异常。

**熄屏未通过**：23:45:23关屏，23:46:56GET拒绝0bytes0.136s；Dozing，App RootHftpRelay登记行仍存在但无ACQ标记。ROM记录23:45:34.243 REL、23:47:10短暂ACQ/REL；随后lightIdle=true、deviceIdle=false。唤醒后锁释放、owned服务退出，但进入锁屏，未取得该候选的最终失败日志，因此不能把具体退出原因写成已直接观测。用户正常解锁后完成上述亮屏回归。App锁登记/isHeld并不证明idle中CPU可用；与Android idle忽略App锁的限制相符，不能确定唯一ROM机制。

收尾：默认App私有目录/LAN7888/32MiB/Roottrue准确恢复，释放测试SAF grant；精确删除两个原公开文件与三个final文件，rmdir空隔离目录。未启动默认目录服务/读取用户库。服务已停止、当前错误无、最终无HFTP锁；VPN lockdown=1，Clash bypassable=false。停止截图${EVIDENCE_DIR}/ctos-feedback-hftp-current-stopped-20260928.png。测试套件清空内存诊断后最后UI日志0条，截图保留实际运行日志；不声称最终设备仍留有这些测试日志。

用户已明确选择保留当前停止修复、暂不扩大Root范围；独立Root电源候选搁置、未实现/触发，熄屏问题保留为已知限制。原授权Root仅网络，未扩权，任务in_progress、源码未提交/未归档。

## 控制队列修复仍不能通过手机熄屏

后续9ca71e316525e5b5ba6a771f855f3df2d3b52342eb75162594e7972c3518c2e3
APK26,193,940bytes已构建/签名/安装，14份Python匹配，native ELF仍d733d2…。
Flutter73/73及analyze、最终Androidlint0errors/5既有warnings通过。
首轮10项手机测试8.774s，9通过/1失败：UiAutomation原锁检查把管道当Shell，
实际Runtime.exec不解释管道，读取完整dumpsys后误报锁。实际WakeLocks:size0，
无RootHftpRelay活动行且无owned进程；不是CPU泄漏。测试改为Java有界解析
活动PARTIAL_WAKE_LOCK行及准确quotedtag；测试APK3cc68b…待停服务后复验。

当前隔离SAF会话23:44:31启动：App-OWNED-PID/CPUlock UID APP-UID，Python-OWNED-PID/su-OWNED-PID
UID APP-UID，helper-OWNED-PID UID0，backend55339，RootLAN7888。已有文件2亮屏
GET200/1,376,257B/hash4604cefa…一致0.196s；23:45:23熄屏开始新包验证。

同日168cedece8b28df0163cfeb668e9d25e23ab413a4fd2df2b664468f48cacdc1f
current构建/签名/安装成功；14份Python源码逐字节匹配，packaged relay
SHA256 d733d2f679d61d4335d9a8e4ccdb492b2a7cdcd01eda498ccecc6537cb14e2de
匹配Gradle stripped产物。此包不是最终通过版本。

亮屏GET既有公开夹具2：200/1,376,257B/SHA4604cefa…一致，0.217s，
日志closed reason=complete。23:27:38熄屏，约40秒后GET拒绝0bytes；
唤醒日志23:28:35 heartbeat_timeout，失败清理。再正常启动后明确
isForeground=true/1602/dataSync；23:30:05亮屏启动，熄屏后只读电源
Dozing、deviceIdle=false/lightIdle=false，App-OWNED-PID/Python-OWNED-PID/su-OWNED-PID/
helper-OWNED-PID仍存活，稍后GET再次拒绝；唤醒后owned进程均已回收。
这说明队列修复不足以解决手机后台可达性，不能称下载/休眠已解决。

追加R25：仅显式Root adapter持有App PARTIAL_WAKE_LOCK，最长5h、不续期，
所有失败/停止路径释放；普通App基础路径不依赖它。实现与设备验收待完成。
没有修改VPN/Clash、休眠/电池豁免或Root系统设置。

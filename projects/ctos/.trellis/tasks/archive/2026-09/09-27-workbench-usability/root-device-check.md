# HFTP Root分离、日志与TARGET-PHONE验收（2026-09-27）

本轮用户明确允许实现并验证Root网络中继，随后要求能力声明/ctOS adapter及无Root基础路径。目标仅TARGET-PHONE `PHONE-ADB-SERIAL`、Android16。Windows客户端WINDOWS-LAN-IP；另一实际LAN测试主机TEST-CLIENT-LAN-IP。没有修改VPN/Clash/路由/防火墙/SELinux，Root不承担HTTP或文件。

## 最终源码与产物

- Flutter68/68、analyze clean；Python25/25；Android lint0 errors/5既有warnings，testAPK编译通过。native严格NDK API28编译/16KiB对齐、15项本机网络夹具及最终3项定向复验通过。Trellis全范围review无源码阻断；分别见root-log-check.md、log-backend-check.md、log-ui-check.md、root-native-check.md。
- `./hako current`通过构建/签名/SHA256SUMS；APK26,189,072 bytes，SHA256 `0575e00d5d7fb732d02ec4923563e4dfd68e0008eae17480791e753ad29eaf1b`。
- APK内14份Python源码逐字节匹配当前源码，中继匹配stripped native产物，packaged relay SHA256 `2c10cc26518d049438e5bf64875ae9abaeedf5e296b03fcd6eb587bb2fb21619`。
- 已覆盖安装主包/testAPK，读回base.apk与current逐字节相同。构建日志`${EVIDENCE_DIR}/ctos-hftp-root-build-20260927.log`；安装读回`${EVIDENCE_DIR}/ctos-hftp-root-installed-base-20260927.apk`。
- Browser loopback九项复验通过：免登录、限额展示、上传/下载、无覆盖、编码目录/嵌套上传、CSP、375px。runner SHA256 `d50fadb28e5ef5c9ec9c739e56f7aa6d28bbf9cccce8bdf0b9cd808fa2846ee3`。

## 手机能力与日志检查

六项定向instrumentation最终均通过：日志容量/控制字符/脱敏/会话归属，配置/文档路径，声明缺失/未知/不匹配在效果前拒绝，可信helper直接以App UID执行被root_required/EPERM拒绝且没有网络事件，请求owner/共享库锁定，普通App HFTP通知/后台/无覆盖/限额/日志清空及停止回收。

首次6项有1项失败：测试用JSONArray序列化文本匹配`GET /public.txt`，Android会escape斜杠。仅将断言改为getString(0)实际日志行，脱敏和容量断言保留；更新testAPK后单项重跑OK1/.197s，其余五项先前通过。主App/current无变化。证据`${EVIDENCE_DIR}/ctos-hftp-root-device-instrumentation-20260927.log`与`${EVIDENCE_DIR}/ctos-hftp-root-log-device-rerun-20260927.log`。

## Root实际LAN传输

新建不存在的唯一隔离目录`${PHONE_DOWNLOAD_DIR}/HFTP-TEST-DIR`，仅公开夹具。经系统SAF选择后实际provider为Downloads（目录显示名准确）；端口7888、上传限额64MiB，显式选中Root中继。App Python先在127.0.0.1:33729就绪，再由Root监听PHONE-LAN-IP:7888。

- 跨设备33MiB上传201/下载200，34,603,008 bytes；SHA256 `c28a8f34a7efbd4cffe424a21e4a6e4d5bfa8b5daccc381f9eb3c1dc5bac689c`一致。同名409、64MiB+1声明长度413。后台再次下载同文件200且哈希相同，证明碰撞没有覆盖。
- Python PID OWNED-PID UID APP-UID；su代理PID OWNED-PID UID APP-UID；实际native helper PID OWNED-PID UID0。Root仅转发网络，SAF及文件由App进程处理。
- Windows用户明确反馈“可以打开”。日志独立记录两次`Root relay accepted client=WINDOWS-LAN-IP`与`GET / -> 200`；TEST-CLIENT-LAN-IP的上传/下载/错误状态/字节也实际记录。Python远端为loopback，真实LAN客户端由relay日志给出，未冒称Python直接看到原客户端。
- UI清空后显示0条，再次LAN读34B公开夹具200并出现新日志；停止后保留该次请求与停止日志。运行截图`${EVIDENCE_DIR}/ctos-feedback-hftp-root-running-20260927.png`、停止截图`${EVIDENCE_DIR}/ctos-feedback-hftp-root-stopped-20260927.png`。
- 精确三个owned PID停止后均不存在；LAN连接得到ConnectionRefusedError。无广泛kill，无残留HFTP进程。VPN secure always_on_vpn_lockdown始终1，vpn_management实际字段bypassable=false，Clash仍在；connectivity仍有物理Wi-FiPHONE-LAN-IP/24。

## 夹具差异与恢复

首次GET ADB创建的public.txt返回404：Downloads MediaStore视图不列出未索引ADB文件，为已知provider边界。后续通过HFTP上传的夹具可正常下载。首次大上传探针遗漏X-ctos-upload:1，服务器400提前关闭导致客户端reset；补齐协议头后同33MiB检查全部通过。这两次失败都实际记录，未归因于relay。

停止后恢复默认私有目录，释放选中测试树，Root关闭/32MiB/LAN7888。为保存并验证普通配置，短暂启动App模式后立即停止；没有请求、读取或清理默认共享库。UI显示App服务/私有目录/32MiB，未调用Root中继adapter；最终Root checkbox未选、服务停止。恢复截图`${EVIDENCE_DIR}/ctos-feedback-hftp-root-restored-20260927.png`。

只删除经名称核对的三份自建文件public.txt、root-public-20260927-e6a4.txt、root-public-33MiB-20260927-e6a4.bin，再rmdir空隔离目录；保留原有共享文件。没有ADBforward，没有系统配置变化。

## 结论与未测范围

TARGET-PHONE在既有VPN不可bypass与lockdown条件下，Root中继LAN可达，Windows及33MiB读写已验收；能力声明/adapter及普通App独立路径已验证。物理无Root设备完整流程、其他ROM/Android11、真实16KiB页、真实Wi-Fi失效/切换及五小时运行未测；native本机模式不能证明Android绑定，仅上述实机请求证明本目标可用。页面视觉产品审阅/新源码提交仍待确认，任务保留in_progress，不自动归档。

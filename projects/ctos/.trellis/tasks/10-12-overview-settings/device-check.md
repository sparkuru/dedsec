# 开发板验收（2026-10-12，进行中）

## 最终：文案收敛新版25bcdf7c…（2026-10-12）

用户授权完成后提交当前task。最终APK已正常覆盖安装，不清数据；继续会话后本地ADB状态及前轮/tmp设备目录已重置，重新显式connect .14并核对AIO-3568J/API30、showing=false/secure=true。再次读回base.apk，32,217,117字节，SHA256 `25bcdf7c22067afaac0c9c457e29ad0bc802194910eec7e2c71bf1f1ada95f78`，与本地产物一致。

四分类实际节点和截图均通过：摘要只含T-CHIP AIO-3568J/内存/电量，没有DEVICE和Android运行摘要行；设备标题及“系统、资源与电源快照”保留，系统详情字段仍有Android/运行时间。网络为“网络状态与实时流量”；接口为“接口、地址与链路状态”，检索框下仍有12个接口和root/procfs+ip来源；连接为“TCP / UDP 连接与应用映射”，独立状态/时间/数量和应用映射仍显示。主会话实看4张PNG，无溢出/崩溃证据。

当前设备证据重建在 `/tmp/ctos-overview-final-device-20261012/`：installed.apk及overview/network/interfaces/connections.png，临时驱动`/tmp/ctos-final-ui.py`；由该驱动生成的唯一设备XML已清理。本轮不执行秘密库/系统认证/实际快照保存，旧版本的成功认证/返回/PTY结论按原APK解释，不据最终文案验收宣称这些在25bcdf7c重新跑过。下方旧/tmp路径可能已不存在，执行时结果仍保留于本task和design。

## 最新：截图追加新版 af600c41…（2026-10-12）

继续既有任务与设备授权；.14重新核对AIO-3568J/API30、Keyguard showing=false/secure=true。正常覆盖安装成功，不清数据；base.apk读回32,217,261字节，SHA256 `af600c41e93040026591d2b72285eb5c5b79a41363965e30c02f98bd7f62295a`，与本地相同。初轮a441及锁屏记录保留在下方，已不表示当前设备仍锁屏。

| 本轮实际检查 | 结果 |
| --- | --- |
| 分类/标题/终端选择 | 四分类x330–1730均分，每项350px，文字居中；ctOS旁绿色Root信息按钮可读在线/来源/最近授权结果，旧APP和body Root状态行及终端绿色眉标题不存在 |
| 默认快捷与系统取消 | 现有已锁定/快捷已开启库，点解锁直接显示系统BiometricPrompt；没有先显示方式选择。此ROM Back及点中心取消描述未结束浮层；Home取消后恢复ctOS，显示“操作已取消。”和取消/主口令/快捷三选择 |
| 显式快捷重试/口令回退 | 点快捷再次调起BiometricPrompt，再Home取消、恢复后选使用主口令，进入受保护主口令页。没有输入任何系统/主口令，空表单取消回安全，已锁定/快捷已开启状态保持 |
| AppPTY/IME与切换 | 本轮新建App权限PTY，仅执行公开`echo CTOS_NAV_20261012`；设置/工作台/概览/终端切换后输出快照仍有命令/标记/提示符，同一会话保留。实际输入/发送/收键盘、选择输出返回使用正常，测试会话已关闭 |
| 四主入口双返回 | 概览/工作台/终端/设置各拍首次下方提示，等待过期后单个`input keyevent 4 4`连续发送，4次mResumedActivity均为QuickstepLauncher；每次重新打开ctOS成功。安全和输出快照二级返回保持普通返回 |
| 设备列表底部 | 连续向上滚动可达电源/温度与最后的存储卡片，完整显示/data可用/总量，底部有留白，不存在固定高度详情窗阻挡 |

返回临时驱动首轮夹入截图再分开发第二次返回，概览未退出；其发送前测时没有覆盖第二个ADB命令的到达耗时，无法据此认定两事件实际在2秒内。随后单input发送验证通过，未改产品代码。4张提示图均已主会话查看；概览图存在一次自动Root恢复的Magisk系统Toast叠层，其他3张提示无叠层，不把系统Toast当作Flutter Snackbar协调检查。

原始证据 `/tmp/ctos-overview-increment-device-20261012/`：installed.apk、overview/root-info/terminal-picker/security-before、quick-recovery、terminal-live/preserved、四entry-exit-prompt.png及back-results.json；临时back_check.py明确指定唯一serial。系统BiometricPrompt期间screencap返回0字节quick-failure.png，不作为视觉证据，没有输入或记录凭证。

边界：新版成功系统认证、无登记真实设备分支、Root黄色离线实机、其他输入法/中文组合、接口历史回跳本轮未重测；对应Flutter状态/导航测试已通过，未借用旧包实测充数。原真实快照保存/pull审批拒绝及未执行边界保持，本轮未重试；不改用户库/快捷登记/系统安全，未开始TOTP/SSH或原底座开放验收项。task current/in_progress，待本轮观感及未测场景反馈，未提交/归档/替换dist。

结束前再次进入安全确认：已锁定、快捷已开启、后台30秒，返回设置；本轮App测试会话已关闭。

## 初轮 a441d114… 历史记录

## 目标与授权

用户在本地交付后回复“你可以自行连接上去，自行验收”，授权当前开发板安装与本任务界面/交互检查。约定192.168.9.13:5555连接拒绝；当前ADB唯一在线192.168.9.14:5555，核对AIO-3568J、Android11/API30、arm64-v8a、1920×1080、density280、ctOS UID10131。所有设备命令显式使用该serial。不改系统安全、VPN、网络或真实秘密库内容，不启动HFTP或Root任意命令。

## 部署

- 当前旧APK通过pm path及pull保留，SHA256 `71d00e7cab8460f3bd93e87f6dc6e8ccf2a3a58b2245f73a149791c586b6bcef`。
- `adb -s … install -r build/app/outputs/flutter-apk/app-release.apk`成功；不清应用数据。
- 安装后base.apk读回，32,216,569字节，SHA256 `a441d1141da02708eecfc6638840af154accd3df7ffdc94e9a2e540e1054c4c6`，与本地产物一致。
- 启动后既有Root自动恢复在线，无额外手动申请/Root命令；普通概览截图和当前节点确认同版实际UI。

## 已完成检查

| 检查 | 实际结果 |
| --- | --- |
| 主导航/概览 | 宽屏rail为概览/工作台/终端/设置，四分类直接位于设备摘要/内存/电量下；旧信息入口、网络现场/能力与恢复、顶部导出不存在 |
| 四分类 | 设备、网络、接口、连接实际点击切换/上下滚动正常；设备系统、实时流量/来源时间、接口筛选框和真实连接快照可见，无崩溃/溢出证据 |
| 设置 | 数据导出/安全两个可点击入口、状态标签及宽屏布局正常 |
| 安全 | 现有库已锁定、设备快捷已开启、后台30秒；解锁/改口令/关闭快捷、时间和备份导入导出入口保留。截图仅元数据，不读取真实库内容 |
| 加密备份 | 从安全打开导出认证页，显示重新输入主口令/文件名/验证保存/取消；未输入口令，取消后回安全。未创建/导出/恢复用户库 |
| 二级返回 | 安全返回设置，数据导出返回设置路径已实际使用；其余四主入口双返回尚待完成 |
| 数据导出取消 | 从设置打开选择页，选设备一项、按钮启用，进入系统SAF保存页；返回后明确“文件选择已取消，未创建文件”。未选数据不写入的真实保存未执行 |

## 自动审批边界

首次取消通过后曾准备唯一 `ctos-overview-settings-20261012-1537.json`（已确认不存在），仅在系统保存页编辑了拟用文件名。保存并pull动作在执行前被自动审批拒绝：理由是“真实设备快照保存到共享存储并复制回本地”的payload/目的地未被授权明确覆盖。未绕过拒绝，随后取消；精确`test ! -e`通过，本地export-device.json不存在。真实写盘/读回不计通过，不删除或覆盖已有文件。

一个取消后导航复合命令的自动审批超时，按工具指示拆成只读不存在核验和UI导航重试后获批；超时不当作产品或安全故障。

## 当前现场与待测

随后UI驱动尝试工作台/终端时出现Android启动logo和系统锁屏，目标`/proc/uptime`为330.37秒，`sys.boot_completed=1`，确认系统已重启，原因未确定。agent没有执行reboot、force-stop、系统配置或安全设置变更；临时driver找不到按钮是当前锁屏导致，不能算应用导航失败或验收通过。已异步请求用户在板上解锁，未获取或输入系统凭证。

剩余：四主入口双返回/提示位置/退出桌面及重开、AppPTY切换保持/IME、设备最下方字段和接口历史回跳。无需重跑未变底座认证/加解密或将迁移验收扩展为原公共底座全部关卡。

结束前只读确认：KeyguardServiceDelegate showing=true、secure=true，screenState=ON/interactive=AWAKE，确为安全锁屏。task保持current/in_progress，等待系统解锁，不尝试获取凭证或绕过锁屏。

夹具独立检查无缺陷，按缺少持久编译日志的证据需要仅重跑该instrumentation编译：BUILD SUCCESSFUL in7s，64tasks/6executed/58up-to-date，日志`/tmp/ctos-overview-settings-fixture-check.log`；artifact665,785字节。diff检查通过，不重跑未变Flutter或认证E2E，生产APK保持a441d114…。

原始证据与旧/新APK在 `/tmp/ctos-overview-settings-device-20261012/`，可能随tmp清理消失。overview-initial/device/storage/network/interfaces/connections、settings/security/security-backup/export-select/selected/cancelled图片按实际拍摄状态解释；workbench.png/terminal.png为重启过程诊断画面，不能作为正常工作台/终端证据。系统picker节点仅临时查询，不纳入持久文档。未提交、归档或替换dist。

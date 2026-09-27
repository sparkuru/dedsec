# Implementation

2026-09-28 提交阶段：用户已明确要求提交并脱敏，当前范围按 PRD 收敛决定保留。最终源码/构建安装、十项手机检查与三轮亮屏 LAN/真实 App 停止/同端口重启已完成；R24 控制竞态、closed 日志及大文件复验通过。R25 App 锁归属/失败与停止回收通过，熄屏有效保活失败并作为已知限制暂缓。以下逐轮未提交或待测描述保留为历史状态，不将熄屏失败标记成通过；Root 电源候选未实现、未触发。

- [x] 完成已批准 PRD/design/context，启动任务。
- [x] 首页工具前置/收紧列表，环境和临时文件降为次级入口。
- [x] 中文参数、密码高级项、数字校验、文件左对齐。
- [x] 编码来源/哈希方向联动，提交值兼容且来源明确。
- [x] 专用结果/复制/导出，保留原始协议和遮挡边界。
- [x] HFTP 状态/主动作/共享库分区，不改生命周期。
- [x] 行为回归、Flutter analyze/full tests（43/43，analyze 无问题）。
- [x] Android lint（0 errors / 5 既有 warnings）。
- [x] Trellis check agent 复核，无阻断问题；实际设备验收完成，截图供用户产品审阅。
- [x] 构建 current APK、签名、校验和（98f44069…）；经本任务授权安装，设备哈希一致。
- [x] 主会话同步 design 及 workbench spec，区分实测范围。
- [x] 用户“允许”后完成 TARGET-PHONE 界面/键盘/SAF/来源哈希定向验收，精确清理外部测试文件；未启动服务或改权限。详见 device-check.md。

代码尚未提交/任务未归档，保留实际截图用于用户产品反馈。

## 图片反馈迭代

- [x] UI：去数字/技术详情、普通 IME、统一按钮及 popup 宽度，行为测试。
- [x] HFTP：LAN/7888/免登录、上传限额、SAF 本机目录直连及配置恢复。
- [x] 跨设备 LAN 直连：旧App模式受用户VPN条件约束；授权Root中继后Windows及实际33MiB LAN读写通过，VPN/系统规则未改。见root-device-check.md。
- [x] 全范围源码检查、最终d43f52ed…构建/安装及两个provider隔离目录验收，33MiB精确传输/无覆盖/限额/后台/停止通过；已同步设计和实测边界，保留任务未提交/归档。详见 feedback-device-check.md。

分工：UI implement owns models/parameter_field/script_page/result_card/controls 和非 HFTP Flutter 测试；service implement owns Java HFTP/SAF broker、Python HFTP/storage 及原生定向测试；主会话 owns HFTP Dart/API/对应测试、设计和最终联调。互相保留改动，不嵌套 agent、不提交。

命令：`./hako flutter analyze`、`./hako flutter test`、`./hako bash -lc 'cd android && ./gradlew :app:lintRelease :app:assembleReleaseAndroidTest --console=plain'`、`./hako current`、`git diff --check`。Docker 被沙箱限制时仅申请 scoped ./hako。追加反馈已涉及原生/Python，实际instrumentation与文件回归按当前授权选择，证据见反馈复核及实机记录。

## 访问与日志后续

- [x] R14外部访问对比与可证实原因/应用修复；授权Root路径已从Windows及实际LAN验证。
- [x] R15/R16 Python请求日志、Java有界会话缓存/Channel、Flutter日志显示/复制/清空。
- [x] 相关行为测试、Trellis复核与最终0575e00d…包TARGET-PHONE隔离日志/网络检查；design/spec已同步。

Backend implement owns HftpService/HftpBridge/native channel forwarding、新日志缓存和Python HFTP日志及native测试；UI implement owns HFTP Dart/API/日志测试。主会话负责只读设备/网络诊断、设计、构建安装与联调。彼此不独占代码，不回退旧修改，hako检查需协调窗口。

- [x] R17–R18 native helper/Gradle、config/service/UI集成，App Python/SAF不提权；TARGET-PHONE保持VPN条件的Windows/33MiB读写/后台/日志/精确停止回收通过。
- [x] R19 ToolExecutionContext能力声明、RootOperationAdapter边界及HFTP首个代理；App路径独立/能力拒绝/App UID不能执行helper/owned生命周期通过，物理无Root设备完整验收未测。
- [x] R20 frontend/workbench spec与索引追加必要文案规则、好/坏例子和审阅点；本轮新Root选项按规则检查。

## 用户停止错误回归

- [x] R21：Service/Bridge 显式停止边界、关闭期间资源与回调隔离、正常停止清 reason；Java 确定性回归及最终手机检查通过。
- [x] R22 本地：checked SO_REUSEADDR；真实旧版 TIME_WAIT/errno98 复现，20 轮同端口实际传输/STOP/重启、活跃端口拒绝及失败回收，原 15 项 native 回归和严格 NDK 编译通过；不作为用户立即停止症状的唯一解释。
- [x] R23：独立日志滚动/展开状态，复现并修复运行时灰块；73项Dart及手机日志操作回归通过。
- [ ] 最终源码检查、构建安装与 TARGET-PHONE 多轮隔离 LAN 传输/停止/立即重启；更新 design/spec、保留产品审阅/提交待办。

分工：native implement 只拥有 hftp_relay.c 与本地 fixture；backend implement 拥有 HftpService/HftpBridge/RootOperationAdapter 和必要 instrumentation；UI implement 拥有 hftp_page.dart 与日志/状态 widget tests；主会话拥有文档、最终构建/设备联调。统一检查窗口，不并发写构建输出。

- [x] R21 本地及手机：最终Service前台拒绝路径修正后，TARGET-PHONE七项定向测试7.135s通过；第一Root LAN传输/停止/PID端口回收/同端口重启通过。
- [ ] R24：queued-PING期限竞态、closed原因日志及大文件压力复验；新版TARGET-PHONE休眠/唤醒和完整多轮流程待测。此前第二轮下载超时及休眠heartbeat_timeout如实保留，不判全流程通过。
- [x] R24本地：24轮精确传输/重启、排队PING存活与9项控制/独占断言通过，独立复核通过。168cedec…包亮屏精确GET及closed reason=complete通过，熄屏仍heartbeat_timeout；不能判手机后台问题解决。
- [ ] R25：Root adapter拥有有界App CPU锁，效果前能力检查、构造失败/关闭回收及普通App路径无锁；TARGET-PHONE熄屏传输/停止释放待验。
- [x] 当前9ca71e…包：最终10项手机9.077s全部通过；三轮RootLAN上传/下载/真实App停止/回收后同7888重启通过，两轮1,376,257B、一轮31MiB；清空日志后31MiB再读回通过。五个自建文件精确清理、默认目录/LAN7888/32MiB/Roottrue恢复、服务停止，VPN lockdown1/bypassablefalse保持。
- [ ] R25熄屏仍未通过：注册App锁存在但熄屏后拒绝连接，ROM记录REL/后续lightIdle。只证明锁归属/释放，不证明有效保活。Root临时CPU租约候选另见design.md，用户已选择暂不扩大Root范围；熄屏问题记录为已知限制，不提交/归档。

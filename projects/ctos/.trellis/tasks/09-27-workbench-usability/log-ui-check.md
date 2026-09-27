# HFTP 日志 UI 检查（2026-09-27）

范围：`lib/workbench/hftp_page.dart`、`lib/workbench/api.dart`、新增
`test/hftp_log_test.dart` 及原 `test/hftp_feedback_test.dart` 的字段滚动定位。
保留所有既有配置、SAF 目录、启动/停止和显式操作 revision 行为；不修改
design/spec、原生/Python、依赖、系统 Root/VPN 设置或设备。

## 实现

- 服务日志位于主操作下方、配置上方，默认展开且可折叠。日志使用可选择文本，
  长日志最多占 320dp 的滚动区域，默认显示末尾，允许回看；普通页面仍可滚动。
- 复制日志、清空日志使用全宽次级动作和至少 48dp 触摸高度。运行及业务处理中
  可复制/清空；清空只锁定重复清空，不触发启动、停止、目录清理或配置变更。
  空日志解释启动后的请求将显示在这里，停止保留日志，返回页面恢复当前快照。
- `hftpStatus` 与 `hftpClearLogs` 返回完整状态 JSON，`logs` 是字符串数组。
  `hftpClearLogs` 无参数；UI只应用清空返回中的日志，保留当前服务状态。
  `hftpLogLines` 在 API 层统一投影，缺失或无效日志形状回退空日志，忽略非字符串。
- 独立 `logsRevision` 防止清空前发出的 poll/启动/停止响应回填旧日志；原 `revision`
  继续保护服务状态。清空期间不新发轮询；清空返回/错误在有更新业务操作时丢弃，
  避免旧清空污染新会话。下一次正常轮询接收最新日志。
- 复制使用当前快照各行按一个换行符连接的精确原文，不加入标题或元数据；复制及
  清空错误在日志区显示，保留已有日志并允许重试。页面明确日志仅在内存保留，
  停止可查看，重新启动服务开始新日志。

沿用已批准主题、Material 和 WorkbenchActions；本地 UUPM Flutter 查询关于大字号/
主题字体的建议用于日志排版，不引入查询生成的网页视觉、配色或第二套设计系统。

## 实际检查

1. `./hako dart format lib/workbench/api.dart lib/workbench/hftp_page.dart test/hftp_log_test.dart`
   完成。
2. `./hako flutter test test/hftp_log_test.dart test/hftp_feedback_test.dart test/portable_tools_test.dart test/workbench_usability_test.dart`
   31/31 通过，记录 `${EVIDENCE_DIR}/ctos-hftp-log-ui-tests.log`。
3. `./hako flutter analyze` 无问题，记录 `${EVIDENCE_DIR}/ctos-hftp-log-ui-analyze.log`。
4. `./hako flutter test` 63/63 通过（包括最终日志末尾显示与过期错误保护），记录
   `${EVIDENCE_DIR}/ctos-hftp-log-ui-full-tests.log`。
5. `git diff --check -- lib/workbench/api.dart lib/workbench/hftp_page.dart test/hftp_feedback_test.dart test/hftp_log_test.dart`
   通过。

新 11 项断言覆盖日志兼容投影及 Channel 契约、空状态/折叠、两秒轮询追加、停止后
保留/返回恢复、精确复制、运行中仅清日志、重复清空锁定、旧 poll 回填、业务 busy
时清空与启动状态并存、旧清空不能覆盖新会话、复制/清空错误恢复，以及 375dp 和
812dp 横屏 2x 字号/减少动画/200 条长文本下的全宽按钮与可滚动布局。

初次相关测试因新增日志区使端口字段移出懒加载范围失败；将原 HFTP 测试改为滚动
定位字段后检查，未放宽配置值或编辑锁定断言，其余现有测试保留并通过。

## 验证边界

日志缓存容量、脱敏、Python 请求是否到达、原生会话 token、真实 Android
生命周期和外部网络可达性由 backend/主会话验证。上述 Flutter 测试使用模拟快照，
不能代替最终 APK 的服务日志联调。本 agent 未构建 current、安装、操作设备、修改
VPN/防火墙、提交或归档。

## 已授权 Root 中继 UI 追加

主会话更新 R17–R18 后，追加可选 `rootRelay:boolean` 配置与启动字段。默认 false，
只接受配置中的布尔 true，普通 App 模式不请求 Root。局域网配置显示 checkbox
“Root 局域网中继”，一句说明“仅转发网络，文件仍以 App 权限访问。需要 Root
授权。”；无 demo 或架构介绍。配置加载恢复已保存选择，但仅本机范围会清除
不一致的 Root 选择。切至“仅本机”自动取消，切回 LAN 不自动恢复。

目录选择/取消/恢复默认只更新目录字段，不覆盖未保存的 Root/端口/限额草稿。
运行或业务 busy 时不可改选项；日志复制和清空仍独立。启动提交完整字段与显式
Root 布尔值，服务状态显示依据 native `rootRelay` 的 App 服务或 Root 中继，
不根据 checkbox 草稿伪造运行方式。原生失败仍使用现有状态 reason 显示。

本次还修改 `test/workbench_usability_test.dart` 的 HFTP exact payload 断言，仅增加
新布尔字段 false，保留其余协议断言。新增五项测试覆盖 strict/default 解码、保存
配置/目录草稿/取消与精确 Root 启动、Root/App 状态、host 切换不重启 Root、busy/
active 禁改，以及 Channel false/true 原样传递；原日志竞争与小屏测试继续保留。

Root UI 实际检查：

1. `./hako dart format lib/workbench/api.dart lib/workbench/hftp_page.dart test/hftp_feedback_test.dart test/hftp_log_test.dart test/workbench_usability_test.dart`
   完成，记录 `${EVIDENCE_DIR}/ctos-hftp-root-ui-format.log`。
2. `./hako flutter test test/hftp_feedback_test.dart test/hftp_log_test.dart test/workbench_usability_test.dart`
   33/33 通过，记录 `${EVIDENCE_DIR}/ctos-hftp-root-ui-tests.log`。
3. `./hako flutter analyze` 无问题，记录 `${EVIDENCE_DIR}/ctos-hftp-root-ui-analyze.log`。
4. 上述五个源码/测试文件 `git diff --check` 通过。

Root 首轮测试中，busy 结束使进度条消失、懒加载范围变化，测试直接定位 checkbox
失败；恢复后先滚动到字段再断言，保留 true 与不可编辑断言，复验通过。
上方 63/63 全范围记录是 Root 追加前的日志实现检查；Root 追加后的 full suite、
实际 Root 网络中继和无 Root 设备行为由后续统一 check/主会话验证，本 agent 不以
模拟配置测试声明其已经通过。

## 停止与灰色错误区域 followup

2026-09-27 主会话提供 `${EVIDENCE_DIR}/ctos-feedback-hftp-restart-error-20260927.png`：
状态为 listener_bind/errno98 启动失败，主按钮可见，其下从日志 Card 起为灰色
ErrorWidget。设备现有 logcat 只保留重复异常，无首个堆栈；同进程退出/重入页面
后日志恢复，由主会话确认。截图本身不能证明首个异常类型。

源码中已确定性复现两个 PageStorage 类型冲突，均为真实 UI 缺陷：

- 同一个 PageStorageBucket 下，滚动长日志、移除页面再重建：ExpansionTile
  从自己的 key 路径读到子滚动区域保存的 double；抛
  `type 'double' is not a subtype of type 'bool?' in type cast`，首帧
  `_ExpansibleState.initState`（本项目 Flutter3.35.7 expansible.dart:301）。
  修复前记录 `${EVIDENCE_DIR}/ctos-hftp-log-page-storage-before.log`。
- 折叠日志、轮询清空、再轮询新日志：新滚动区域从同路径读到折叠 bool；抛
  `type 'bool' is not a subtype of type 'double?' in type cast`。修复前记录
  `${EVIDENCE_DIR}/ctos-hftp-log-collapsed-before.log`。

修复为分别给 ExpansionTile、日志外层 SingleChildScrollView、SelectableText
内部滚动以及日志错误文本独立 PageStorageKey 路径。测试保留同桶与实际滚动/
折叠/清空/重建顺序，修复后检查无异常、日志与配置可达；不是清掉 PageStorage
或捕获忽略异常。日志文字 finder 相应使用新的 PageStorageKey。

停止契约由 backend 确认：hftpStop 只建立停止请求边界，state=stopping 保持到
owned 资源释放；动态 closing:boolean 也覆盖 failed 后资源仍回收中的间隔。
UI立即显示停止中，并把 stopping/closing 视为 active，禁止启动、重复停止和
配置修改。failed+closing 保留真实 reason、显示结束中；closing=false 后才允许
重试。日志复制和清空始终只改日志，不覆盖这些状态。

本 followup 只修改 `lib/workbench/hftp_page.dart` 与 `test/hftp_log_test.dart`。
五个新行为回归覆盖两个缓存冲突、停止请求 pending/停止中资源释放边界、进入
停止中页面以及失败关闭锁定/保留原因/释放后重试。测试辅助函数通过外层滚动
位置定位懒加载子节点，避免长日志内层区域消耗测试的外层拖拽手势；保持真实
PageStorage 与 widget 行为断言。

检查：`./hako dart format lib/workbench/hftp_page.dart test/hftp_log_test.dart`
完成；`./hako flutter test test/hftp_log_test.dart` 17/17 通过，记录
`${EVIDENCE_DIR}/ctos-hftp-stop-ui-tests.log`；两个源码/测试文件 diff 检查通过。
源码已冻结、hako窗口释放，全量 test/analyze 与原生/设备联调由统一 checker/
主会话执行。本 agent 未构建、安装或操作设备，不将 widget 修复测试写成设备
首个异常堆栈或真实 Root 停止已通过。

# ctOS 信息入口

最新源码 APK：运行 [`./preview.sh`](../preview.sh)，完成 arm64 release 编译后输出 APK 绝对路径和 `adb install` 命令；需要 Docker，具体检查见 [验证记录](verification.md)。

2026-10-06 文案补充：顶部移除 `SYSTEM OBSERVATORY`，概览标签简化为 `DEVICE`；当前源码 analyze、103 项 Flutter 测试及格式检查通过。用户追加要求后已构建 arm64 release 包 `b7e67386…`，签名与校验和通过，未安装到设备。见 [验证记录](verification.md#2026-10-06-两处显示文案精简) 与 [变更历史](changelog.md#2026-10-06--显示文案精简)。

2026-10-06 当前交付：全 Flutter 界面优化已实施，统一主题与全部页面、900 dp 导航适配、大字布局及交互反馈。最终本地检查为 analyze 无问题、103 项测试通过、107 个实际 Flutter 渲染场景；release 包 `37d22600…` 已在配置 A Android 15 手机覆盖安装，安装哈希一致。用户已回复“观感通过，按计划提交”；实机交互见 [验证记录](verification.md)，任务见 [归档检查](../.trellis/tasks/archive/2026-10/10-05-flutter-experience-polish/check.md)。旧包/设备结论均按日期解释，既有 HFTP 熄屏断连限制未改变。

`design/` 是 ctOS 项目各种信息的统一 landing。产品规划、约束、架构、决策、兼容性、验证记录、依赖和变更历史都在这里维护。根目录 README 仅保留项目简介和使用入口；项目级 AGENTS.md 约定后续工作的执行规则。

## 阅读路径

| 文档 | 内容与用途 |
| --- | --- |
| [规划](plan.md) | 产品定位、能力范围、阶段交付与验收标准 |
| [改进机会](opportunities.md) | 基于当前证据的功能、体验、视觉与工程改进建议及优先顺序 |
| [全 Flutter 界面品质优化](flutter-experience.md) | 2026-10-05 全页面视觉、响应式、动效契约与实际复验入口 |
| [约束](constraints.md) | 工作范围、权限边界、采集与执行规则、质量要求 |
| [当前架构](architecture.md) | 已实现的组件、数据流与执行边界 |
| [Portable 五项工具与 HFTP](portable-tools.md) | 模块边界、SDK v2、文件能力、认证加密与后台文件服务 |
| [Portable 工作台与 SDK](portable-workbench.md) | 内置 Python、终端工具、运行包清单和脚本扩展契约 |
| [兼容性](compatibility.md) | 设备差异、已验证范围与未验证项 |
| [验证记录](verification.md) | 检查结果、实机证据及复验方式 |
| [依赖来源](dependencies.md) | 工具链、版本、来源和许可证 |
| [变更历史](changelog.md) | 已实现变更与文档调整 |
| [移除 Vector 的决定](decisions/2026-09-26-root-only.md) | 作用域与在线服务的差异，App/Root 能力和边界 |
| [Trellis Plus 规则](../.trellis/spec/trellis-plus/index.md) | 开发流程增强及项目验证配置；任务结果仍以 Trellis 任务记录为准 |
| [Trellis 编码规范](../.trellis/spec/frontend/index.md) | Flutter 与 Android/Python 宿主代码约定；后端及原生层索引见 [Runtime 规范](../.trellis/spec/backend/index.md) |

## 当前交付与阶段记录

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

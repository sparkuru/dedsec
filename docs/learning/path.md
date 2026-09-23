# LSPosed 与 Vector 学习路径

## 学习契约

目标：独立实现并发布一个限定宿主、限定进程、可诊断、版本失效时安全停止的 Vector/libxposed 工具。

达成证据：

- 不看模板解释从 Zygisk 注入到模块回调的完整链路。
- 在自编 target app 中分别完成 legacy 和 modern API 模块。
- 能定位混淆目标、证明候选唯一，并处理宿主版本变化。
- 能解释模块 App、框架服务和注入进程之间的信任边界。
- 发布一个带兼容矩阵、失败策略和可复现构建的实际工具。

## 当前基线

已有抓手：QAuxiliary 静态调研已经覆盖加载链、进程限制、DexKit、QQNT 消息服务和工具分层。

尚未观察到的证据：

- 自己编写并运行的最小模块。
- 对 Vector 加载路径的断点或日志追踪。
- legacy 与 modern API 的同题对照。
- 对混淆版本变化的可执行 resolver 测试。
- 一个进入 `projects/` 的可构建产品。

因此从最小动态实验开始，不继续用大型参考仓库代替运行证据。

## 阶段 0：实验环境

对象：一台可恢复的 Android 实验设备或模拟环境、自编 target app、固定版本的 Vector 和 Zygisk 实现。

学习动作：记录 Android build、ABI、root 方案、Zygisk 实现、Vector commit、模块 APK hash 和日志获取方式。

验收：一次实验能够仅凭记录在同一环境重现。

## 阶段 1：最小 Hook

对象：包含普通方法、构造器、异常、重载、后台线程和独立进程的 target app。

学习动作：用 legacy API 分别实现 before、after、replace、调用原方法和解除 Hook。

验收：能够预测并用日志证明参数、返回值、异常、线程和进程；能够解释一次 Hook 未生效的原因。

## 阶段 2：现代 API

对象：与阶段 1 相同的 target app。

学习动作：使用 libxposed API 重做同一组行为，增加 scope、模块配置和 service 通信。

验收：列出两套 API 在入口、回调模型、元数据、通信和兼容策略上的结构差异，而不是只比较语法。

## 阶段 3：框架加载链

对象：固定 commit 的 Vector、libxposed API/service 和 LSPlant。

学习动作：追踪 Zygisk 入口、进程筛选、模块发现、ClassLoader、回调分发和 LSPlant 调用。

验收：产出一张每个节点都有源码锚点或运行日志的调用图；明确哪些是观察事实，哪些是上游声明。

## 阶段 4：ClassLoader 与版本适配

对象：开启混淆并能生成两个结构版本的 target app。

学习动作：依次使用稳定类名、反射约束、字符串特征和 DexKit 结构查询定位同一目标。

验收：候选唯一时成功；零候选或多候选时安全失败；缓存键包含宿主版本和规则版本。

## 阶段 5：模块 App 与注入探针

对象：一个普通 Android UI 进程和一个注入 target app 的只读探针。

学习动作：传输任务请求、小型 DTO、错误状态和文件描述符；验证 UID、request ID、过期时间和协议版本。

验收：不跨进程传宿主对象或反射对象；大文件不经过 Binder byte array；错误结果可归因。

## 阶段 6：LSPatch 对照

对象：阶段 1 和阶段 5 的同一套目标与模块。

学习动作：在 LSPatch 环境运行，测试签名、升级、split APK、跨应用调用和模块配置。

验收：形成 Zygisk 与 APK patch 的兼容矩阵，所有差异由实验支持。

## 阶段 7：高阶机制

对象：LSPlant、native library 和受控系统进程实验。

学习动作：方法反优化、native 加载回调、inline hook、system_server 作用域和崩溃恢复。

验收：能说明何时应更换 Hook 点，而不是扩大反优化或系统注入范围。

## 阶段 8：发布工具

对象：QQ 图片提取器或另一个范围窄、结果完整性可验证的工具。

学习动作：建立 host adapter、稳定领域模型、版本自检、诊断报告、兼容矩阵和 release。

验收：项目满足 `projects/README.md` 的发布契约，且不依赖 `labs/`、`workbench/upstreams/` 或历史遗留材料。

## 人机分工

学习者保留：目标定义、第一条完整实现、断点和日志观察、机制复述、异常归因、证据判断与架构取舍。

Agent 承担：资料定位、源码索引、脚手架、机械扩展、测试矩阵、CI 配置、diff 复核和文档一致性检查。

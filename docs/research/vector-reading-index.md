# Vector 源码阅读索引

目标 revision：`a0ab735e13a3593839421c99c1c1aba6a129be7d`。

本索引先定义问题和证据出口。实际路径与行号只有在固定 revision 的本地 clone 中核对后才能填写。

## 入口与注入

需要回答：

- Zygisk 向 Vector 交付哪些生命周期和进程信息。
- Vector 在 fork 前后分别保留和释放什么状态。
- 包名、进程名、UID 和用户如何参与注入决策。

优先目录：`zygisk/`、`native/`、`daemon/`。

## 模块发现与作用域

需要回答：

- manager 如何保存模块启用状态和 scope。
- daemon/service 如何向注入进程提供有效模块集合。
- 多用户、system_server 和普通应用的 scope 如何区分。

优先目录：`manager/`、`daemon/`、`services/`。

## ClassLoader 与入口分发

需要回答：

- legacy 与 modern 模块 APK 如何被发现。
- 模块、框架、boot 和宿主类分别由哪个 ClassLoader 解析。
- 同一模块在不同进程中的实例和静态状态如何隔离。

优先目录：`legacy/`、`xposed/`、`services/`。

## Hook 调用链

需要回答：

- 上层 callback 如何注册、排序、调用原方法和解除 Hook。
- 相同方法被多个模块 Hook 时如何维护回调集合。
- deoptimization 在什么条件触发，并如何到达 LSPlant。

优先目录：`xposed/`、`native/`、LSPlant 固定 revision。

## Modern API 与 Service

需要回答：

- libxposed API/service 的 commit 如何与 Vector 构建绑定。
- 模块 App 如何认证并连接 framework service。
- 配置、文件和 Binder 对象的生命周期如何管理。

优先目录：`services/`、`xposed/` 及相同 lock 中的 libxposed 仓库。

## 诊断与故障隔离

需要回答：

- daemon、注入侧和模块异常分别写入哪里。
- 模块崩溃、Zygisk 崩溃和目标应用崩溃如何区分。
- safe mode、模块禁用和上一次会话日志如何参与恢复。

证据出口：源码锚点、日志样例、进程关系图和失败复现实验。

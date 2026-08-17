# 实验索引

实验编号表达学习依赖，不表达发布日期。

| 实验 | 核心问题 | 主要证据 | 前置 |
|---|---|---|---|
| [`target-app`](../../labs/target-app/README.md) | 如何构造稳定、可观察的 Hook 靶点 | 测试、日志、进程表 | 无 |
| `01-legacy-api` | XposedBridge 如何改变方法调用 | 参数、返回值、异常和线程日志 | target app |
| `02-libxposed-api` | modern API 如何表达同一能力 | API 对照、scope、service 日志 | 01 |
| `03-classloader-dexkit` | 如何在混淆和多 ClassLoader 下定位目标 | resolver 报告、唯一性测试 | 01–02 |
| `04-module-app-ipc` | 模块 App 如何安全控制注入探针 | UID、request、协议与 PFD 测试 | 02 |
| `05-lspatch-comparison` | APK patch 与 Zygisk 注入的边界差异 | 兼容矩阵、安装和签名结果 | 01–04 |

每个实验目录应包含：

- `README.md`：问题、环境、预测、步骤、观察、结论和未决项。
- 可独立构建的最小源码。
- 自动化测试或明确的设备操作清单。
- 不含账号、聊天内容、设备标识和密钥的样例输出。

实验进入项目的条件：机制被重复验证；失败边界明确；实现可缩减为稳定接口；许可证和来源可追踪。

新实验从 [`labs/_template`](../../labs/_template/README.md) 复制结构，不复制结论。

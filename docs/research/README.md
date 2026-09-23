# 研究文档规范

研究文档用于保存会影响实现和决策的事实，不保存无来源的功能清单。

## 事实状态

| 状态 | 含义 | 可支持的结论 |
|---|---|---|
| `OBSERVED` | 已从源码、运行结果、日志或测试直接观察 | 当前实现行为 |
| `DECLARED` | 上游 README、文档或规格明确声明 | 项目意图和公开支持范围 |
| `GENERAL` | Android、ART 或 Xposed 的通用机制 | 背景模型 |
| `PROPOSED` | 本仓库提出但尚未验证 | 候选设计和实验 |

`DECLARED`、`GENERAL` 和 `PROPOSED` 不得写成目标项目已经发生的 `OBSERVED` 行为。

## 单个框架记录

每个框架文档至少包含：

1. 上游 URL、revision、检查日期和许可证。
2. 注入拓扑和需要的权限。
3. 模块发现、作用域和加载流程。
4. Java、native、资源及服务能力。
5. 进程、Android 版本和模块 API 边界。
6. 失败模式、日志路径和可执行验证。
7. 与其他实现的结构性差异。

引用源码时固定到 commit，不以会漂移的默认分支行号作为长期证据。

## 停止条件

新增材料已经不能改变实验顺序、实现选择或风险判断时，停止收集并进入实验。未知项保留为待验证，不用类比填充。

## 当前索引

- [框架地图](framework-map.md)
- [能力与边界](capability-model.md)
- [Vector 源码阅读索引](vector-reading-index.md)
- [CaptureSposed 架构审计](donthookmyscreenshot-audit.md)
- [遗留材料与上游参考清单](materials-inventory.md)

# LSPosed-like 框架地图

检查日期：2026-08-12。

## 分类轴

框架比较应先回答四个问题：

1. 代码如何进入目标进程。
2. 目标 APK、系统分区或运行环境是否被修改。
3. 能覆盖哪些包和进程。
4. 向模块暴露哪套 API 与通信服务。

## 当前主线

| 实现 | 注入拓扑 | 主要能力 | 研究角色 |
|---|---|---|---|
| [Vector](https://github.com/JingMatrix/Vector) | Zygisk → 应用/系统进程 → LSPlant | 不改 APK；旧 Xposed API；现代 libxposed API；作用域和框架服务 | 实践主线 |
| [LSPosed](https://github.com/LSPosed/LSPosed) | Riru/Zygisk → 应用/系统进程 → LSPlant | 原版 Xposed 兼容、作用域、manager/daemon/service、模块仓库 | 上游设计基线 |
| [LSPatch](https://github.com/JingMatrix/LSPatch) | 向目标 APK 插入 dex/so | 免 root；目标应用内加载 Xposed 模块 | 非 root 对照线 |
| [NPatch](https://github.com/7723mod/NPatch) | LSPatch 衍生的 APK patch | 免 root；延续 LSPosed/LSPatch 核心 | 衍生实现观察 |

Vector 上游声明支持 Android 8.1 至 Android 17 Beta，并同时提供 legacy 和 modern API。具体实验必须固定 Vector commit、Android 构建和 Zygisk 实现，不能只记录版本名称。

LSPatch 会改变目标 APK 的内容和签名环境。研究时需单独验证签名自检、跨应用认证、split APK、升级和模块发现，不得把 Zygisk 路线的结论直接套用。

## 历史实现

| 实现 | 核心价值 | 不作为当前主环境的原因 |
|---|---|---|
| [EdXposed](https://github.com/ElderDrivers/EdXposed) | Riru、YAHFA、SandHook、XposedBridge 的演进关系 | 官方支持范围停留在 Android 8–11 |
| [Dreamland](https://github.com/canyie/Dreamland) | Pine Hook 引擎、严格模块作用域、独立管理器 | 上游明确说明低活跃维护 |
| [VirtualXposed](https://github.com/android-hacker/VirtualXposed) | VirtualApp 容器中的免 root 模块运行 | 不能修改系统，且不支持资源 Hook |

历史实现用于解释设计取舍和失败模式，不进入首批实验的兼容矩阵。

## 底层组件

| 组件 | 职责 |
|---|---|
| [LSPlant](https://github.com/LSPosed/LSPlant) | Java 方法 Hook、解除 Hook、inline deoptimization |
| [libxposed API](https://github.com/libxposed/api) | 类型化的现代模块 API |
| [libxposed Service](https://github.com/libxposed/service) | 框架与模块应用之间的通信接口 |
| Dobby | native inline hook，供框架底层使用 |
| DexKit | 基于 DEX 结构和特征定位混淆目标 |

## 第一批源码问题

1. Vector 在哪个阶段取得包名、进程名和应用 ClassLoader。
2. 作用域配置如何从 manager 到达注入侧。
3. legacy 与 modern 模块入口如何被发现、隔离并调用。
4. 同一方法被多个模块 Hook 时，回调链如何排序和调用原方法。
5. framework service 如何验证模块身份并提供跨进程配置。
6. LSPlant 的 Hook、backup method 和 deoptimization 如何映射到上层 API。
7. 模块异常如何隔离，框架如何记录一次加载的完整诊断证据。

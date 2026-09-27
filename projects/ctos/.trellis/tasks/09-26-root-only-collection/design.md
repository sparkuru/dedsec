# Design

移除 SystemModule、Xposed 入口/依赖/元数据/签名查询权限，以及 MainActivity 的广播注册、nonce、系统快照缓存。snapshot 保持 Android API 网络配置，加上已授权 Root 的 Collector.interfaces；Root 失效时退回 app / TrafficStats 或 app / procfs。

Flutter 直接读取 snapshot.networks，不再读取 moduleActive/module/moduleAgeMs。删除 Vector 状态、工作台说明和网络桥接卡片；保留 App/Root 状态、来源、时间、错误及授权按钮。JSON 导出随快照移除 module 字段，无对外稳定 API。

Root 提供接口计数、路由、socket、应用/分身映射及终端；Android API 保留网络、设备、电源等无需提权字段。不承诺与 system_server 相同的所有用户/VPN 网络可见范围；将来如有需求另行设计 Root 数据源。

本地 UUPM design-system 查询建议深色状态界面，flutter stack 查询提醒完整异步状态。本轮沿用配色、布局和导航，只删失效能力，保留失败恢复；不采用营销页面结构。

回退包：/tmp/ctos-before-root-only-20260926.apk，可覆盖安装，不完整卸载应用。

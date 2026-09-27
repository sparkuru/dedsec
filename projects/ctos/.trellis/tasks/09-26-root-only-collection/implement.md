# Implementation and verification

1. 目标 192.168.9.9:44603 / PLR110 / Android 16 API 36；Root id 为 uid=0。旧日志只有 ctOS App PID 的模块加载，系统广播列表没有 im.majo.ctos.QUERY，uptime 约 40.7 天。旧模块在 system_server 的 systemReady 时注册，解释作用域勾选与在线服务的区别；不以日志缺失断言具体 Vector 故障。
2. 移除模块与桥接，保留 Root/App 后端；更新 widget fixtures 和 App 网络 API 设备测试。
3. hako dart format、flutter analyze/test、lintRelease、assembleReleaseAndroidTest、current；检查 APK 无 Xposed 入口/元数据。
4. 覆盖安装并核对哈希，跑当前手机全部适用设备测试；检查工作台、网络、接口、连接及 Root 自动恢复。
5. 同步设计与实测记录，检查 diff 和 git diff --check。未授权提交/发布，不自动提交；Android 11、全新安装弹窗与跨 ROM 能力明确记录。

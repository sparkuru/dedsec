# Compatibility

当前验证设备：T-CHIP / Firefly AIO-3568J，Android 11 / API 30，arm64，SELinux Permissive，Magisk 30.7。

框架：Vector v2.2，versionCode 3111，revision efb82883，API 102；ctOS 使用框架仍支持的 legacy API 82。作用域为 `system / 0`。

2026-09-23 已重启加载系统模块，3 项 instrumentation 测试通过：系统桥接返回 UID 1000、Root 接口与连接采集、App/Root PTY 的身份/TTY/尺寸/Ctrl-C。主网卡 eth1，界面自动选取默认网络接口。Android 11 使用 procfs 计数，Android 12+ 使用 TrafficStats API 回退；Root 采集读取 procfs 与 ip。

界面已验证接口过滤、连接端口检索及 UID 到包名映射、Root 终端 id 输出，以及通过系统文件选择器导出 JSON 并读回。未开启 App Root 采集时，导出仍包含 Vector UID 1000 和 11 个接口的计数。

先前设备 OnePlus PLR110（Android 16 / API 36、SELinux Enforcing、Vector 2.2/3094）只验证过初版安装与 Wi-Fi/VPN 界面；之后无线 ADB 断开。开发板的成功不能证明该手机或其他 Enforcing ROM 上当前版本的完整兼容性。当前 APK 仅提供 arm64，使用开发签名。

详见 [verification.md](verification.md)。

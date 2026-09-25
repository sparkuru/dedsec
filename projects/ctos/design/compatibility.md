# Compatibility

已验证设备包括 T-CHIP / Firefly AIO-3568J（Android 11 / API 30、arm64、SELinux Permissive）及 OnePlus PLR110（Android 16 / API 36、arm64、SELinux Enforcing）。

2026-09-25 当前分身别名版 APK SHA-256 为 `19b8922bce927066dcec7fd9a7d45ac5caf148f32943d8c1c44090738560791d`。PLR110 覆盖安装与设备哈希匹配，Flutter 15 项、Android lint/测试包构建及分身解析与 Root/PTY 四项定向仪器测试通过。该机 Oplus 桌面可提供 QQ 分身别名 `tim`；前一轮实机包已显示 UID 99910377 卡片，当前包的精确别名筛选通过模拟测试。其他 ROM 的桌面别名提供者未验证，缺失时保留原名、包名和用户 ID。`ef6e9981…` 中间包的全量仪器测试 5 项中 Vector 桥接仍失败；Android 11 对当前包待验收。详情见 [verification.md](verification.md)。

2026-09-25 应用图标版 APK SHA-256 为 `78d7af8db4474970cd4bbcb7a9dc16c3dc9333fc13a4a3de455473ab2393537f`。PLR110 覆盖安装与设备哈希匹配，Root/PTY 三项仪器测试通过；导出修正前同界面版本 `c9965efa…` 的 Quick Connect 真实图标与显示名筛选通过，该版仅在导出时排除图标数据。Flutter 14 项、Android lint/测试包构建通过。Android 包可见性限制部分应用图标/名称；不可得时保留包名与占位图标。Android 11 与 Vector 对该版仍待验收。

2026-09-25 左对齐修正版 APK SHA-256 为 `78392858a4ee90cf9727a2219918ef058fd30333f6ec9c7b90c366db23604be8`；PLR110 覆盖安装与设备哈希匹配，连接页左对齐、item、包名筛选实机通过，Flutter 13 项及 Root/PTY 三项仪器测试通过。Android 11 与 Vector 对该版未验收。

2026-09-25 连接 item 首版 APK（SHA-256 `1f6e6833359517229c091328d07d0918ad7222e8aca89603a25e489fbee133ce`）曾在 PLR110 覆盖安装；设备包哈希匹配，连接列表及应用包名筛选实机通过。Flutter 13 项测试与 Root/PTY 三项仪器测试通过。Android 11、Vector 和全新安装授权弹窗未在该版验收。

2026-09-25 可信状态与工作台版 APK（SHA-256 `4e26b346eb457e23d22e17bf45d655ff58262cda0cc5b358c7e678b333c0a9a9`）已在 PLR110 覆盖安装并检查工作台、连接快照过期/刷新及系统负载权限说明；Root/PTY 三项仪器测试复测通过。一次首次合跑的 Root 超时断言失败未能在单项和再次合跑时复现，详见 [verification.md](verification.md)。Android 11 板、Vector 桥接及全新安装授权弹窗对这版 APK 尚未验收。下述记录属于较早版本，不代表当前包的通过结果。

2026-09-25 在用户指定的 `192.168.9.9:45797` 上覆盖安装新 current APK，安装包与设备 `base.apk` 的 SHA-256 均为 `b777fe91b2dc3292c487977dbda10c44059ef2c9afc8c94095f977fa88b95d80`。该机原有 ctOS Magisk 授权保持开启；新包在首次启动、强制结束后的再次启动、再次覆盖安装后，均无需点授权按钮就恢复 ROOT 在线及 `root / procfs + ip` 采集。3 项 Root 仪器测试通过，涵盖计数/连接、会话复用/失效停止，以及 App/Root PTY。全新安装时的 Magisk 首次弹窗尚未在该机复现。设备信息可用，但 SELinux Enforcing 下 `/proc/loadavg` 返回 `EACCES`。Vector 桥接测试因 8 秒内无响应失败；未改其配置或重启，故该机的 Vector 能力未通过验收。

2026-09-24 在用户指定的 `192.168.9.14:5555` Android 11 开发板上安装当日 current APK；安装包与设备 `base.apk` 的 SHA-256 相同。该版的 4 项仪器测试全部通过，覆盖 Vector 桥接、Root 采集与会话复用、App/Root PTY；工作台、设备信息、网络、接口、连接、只读命令、App 终端及 JSON 导出也通过实机检查。测试结束后已撤销 ctOS 的临时 Root 授权。2026-09-25 的自动 Root 版本尚未在此板重测；其模块在重启后加载也未验收。以下 2026-09-23 验证属于旧版。

框架：Vector v2.2，versionCode 3111，revision efb82883，API 102；ctOS 使用框架仍支持的 legacy API 82。作用域为 `system / 0`。

2026-09-23 已重启加载系统模块，3 项 instrumentation 测试通过：系统桥接返回 UID 1000、Root 接口与连接采集、App/Root PTY 的身份/TTY/尺寸/Ctrl-C。主网卡 eth1，界面自动选取默认网络接口。Android 11 使用 procfs 计数，Android 12+ 使用 TrafficStats API 回退；Root 采集读取 procfs 与 ip。

界面已验证接口过滤、连接端口检索及 UID 到包名映射、Root 终端 id 输出，以及通过系统文件选择器导出 JSON 并读回。未开启 App Root 采集时，导出仍包含 Vector UID 1000 和 11 个接口的计数。

2026-09-23 在 OnePlus PLR110 上仅验证过初版安装与 Wi-Fi/VPN 界面；该历史结果由上方 2026-09-25 的 current APK 检查补充。开发板和此手机的结果均不能证明其他 ROM 的完整兼容性。当前 APK 仅提供 arm64，使用开发签名。

详见 [verification.md](verification.md)。

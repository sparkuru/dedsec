# Compatibility

## 2026-10-12 API30 系统凭证快捷认证

AIO-3568J Android11 API30 当前已由用户设置安全锁屏，公开系统凭证能力可用；同一隔离UID真实空库流程旧实现失败，认证后提交AAD的修复流程1项95.811秒通过，覆盖真实包装/解封和内容认证、关闭取消/错误保护、正确关闭及主口令回退。主包71d00e7c已覆盖安装/读回一致，现有用户库正常UI开启及快捷解锁成功，结束为库锁定/快捷已开。此结果替代10-10“无锁屏”在本板当前的限制；不证明生物识别、API28/29或其他ROM，也不覆盖真实锁屏事件/进程死亡/非空混合恢复及业务集成。AAD必须在系统认证成功及最终guard后提交，初始化探针完成不能记成功认证。完整 [验证](verification.md#2026-10-12-安全锁屏开发板快捷认证修复与实测)。

## 2026-10-10 秘密库兼容性边界

本轮主包ca85在B实跑6项Bridge生命周期通过，104 JVM重新执行、35定向通过；未变138全量未重跑。独立UID复用实际release Flutter/Main/Bridge的空库完整UI/SAF流程1项235.79秒通过，真实创建/锁定/改口令、新导出/同名取消/确认覆盖及不同来源/目标口令恢复均验证，测试资源/授权/两包清理完成。只在fixture自身关闭Root自动启动，不改系统无障碍或安全配置。真实HOME与core重建/排序注入分列，不称真实锁屏/进程死亡；非空/混合业务和成功认证未外推。以下17平台/真实provider六阶段对应此前a309包，详见 [追加验证](verification.md#2026-10-10-配置-b-追加bridge-生命周期与独立-uid-ui-探针)。

公共宿主路径ID修复时 104 JVM/未变138 Flutter/lint 通过；API 28/29 采用系统锁屏确认及认证绑定 Cipher，30+ 采用强生物/锁屏 CryptoObject。已清除 minSDK 28 不可用的 Java 集合工厂调用。配置 B T-CHIP AIO-3568J / Android 11 API 30 / arm64 的此前 `a309bd9d…` 包安装读回一致，17 平台测试与真实 ExternalStorageProvider1六阶段通过。路径型 provider 实测新 final 复用历史 original ID；journal 需区分存活角色/历史 ID，已删除角色先持久移除再恢复旧路径，已验证 final 保留。真实 provider 使用独立私有磁盘库/journal及密文认证，包含两种主动注入写入失败补偿，不代表生产 Flutter 全流程、真正进程死亡/云端/权限撤销。初轮 `6c0f00e6…` 的敏感截图无 PNG、录屏 App 区域黑、最近任务为空，取消后普通捕获恢复；相关 UI/helper 源码未变。此板无安全锁屏，不证明成功 CryptoObject/Keystore 或 API 28/29。KDF create 2035 ms 仅为样本，不外推其他 ROM。完整边界见 [本次验证](verification.md#2026-10-10-配置-b-追加真实目录与路径标识复用)。

Linux/glibc OpenSSH 10.0p2 内存文件访问六组研究验证通过，但不证明 Android/Bionic、NDK、16 KiB 页、完整登录/每跳信任或前台服务兼容。用户已撤销验证码剪贴板清理及其设置/跟踪/探针，不再有自动清理兼容性关卡；主动复制的敏感标记按平台能力生效。导出按用户新决定实现同名确认、新文档写入/读回和精确补偿；SAF rename/create/delete、权限撤销和恢复需要真实 provider 验证，清理失败或效果未知须显示位置，不承诺任意 provider 多文档原子性。只使用本次 tree 授权，不加广泛存储权限/持久 grant；Android 11+ 可选目录受系统限制。生产 SSH 内容及恢复语义 provider 缺位时拒绝来源或目标包含 SSH 的导入。见 [验证记录](verification.md) 与 [未完成关卡](app-authentication.md#2026-10-10-当前实现与未完成验收)。

2026-09-28 当前 `9ca71e31…` APK 已在 Firefly AIO-3568J（Android 11 / API 30、arm64、SELinux Permissive）覆盖安装，设备 APK 哈希一致；七项非 Root 定向仪器测试通过，覆盖内置 Python/SDK、取消/超时/后台回收、App 网络快照、文件加解密与编码、文件不覆盖及 loopback HFTP 后台/停止。Root 采集/PTY、LAN/熄屏、SAF 手操与其他 Android 版本边界未由这次开发板检查扩展。详细范围见 [verification.md](verification.md)；下方各条按当时 APK 版本解释。

2026-09-26 Portable Python 当前包 SHA-256 为 `00c19e0ca2ecc84b1fea32fafaec29708b152710a3f5a3b8ece64717a26d97c6`。Flutter 28 项、Android lint/测试包构建和本地 ARM64 Android/QEMU 检查通过。已覆盖安装 PLR110 / Android 16 / SELinux Enforcing，设备哈希一致，真机 8 项通过：App/Root python3、标准库/SDK、运行中取消、超时、后台待运行任务回收及原有 Root/网络回归。工作台二级页自检和 JSON 保存读回通过。APK 仅 arm64，最低 API 28；Python 3.13.9 和 NDK 启动器具备 16 KiB 对齐，但 Android 11、16 KiB 页面设备及其他 Root 管理器未测。下方均为旧包历史；详情见 [verification.md](verification.md)。

已验证设备包括 T-CHIP / Firefly AIO-3568J（Android 11 / API 30、arm64、SELinux Permissive）及 OnePlus PLR110（Android 16 / API 36、arm64、SELinux Enforcing）。

2026-09-26 Root-only 当前包 SHA-256 为 `be800549c2ddf6509fe9ff0de23556d58b9401b67fee39f63fa5eb361752ab93`，在 PLR110 `192.168.9.9:44603` 覆盖安装与设备哈希核对成功。五项设备测试全部通过，覆盖 App 网络 API、Root 接口/连接、会话复用/超时不重开、App/Root PTY 和分身别名解析。工作台显示 ROOT 在线、33 个接口及 root / procfs + ip 数据源。ctOS 不再依赖 Vector，当前网络配置属于 App API 可见范围；Android 11、全新安装弹窗和完整导出对当前包未重测。以下是旧包历史记录。

2026-09-26 终端版当前 APK SHA-256 为 `1a610e33fc018d20f4b99161d6eb5e91f4eb594930f0d0033e3eea5c873245ae`；在 PLR110 `192.168.9.9:45075` 覆盖安装并核对设备 `base.apk` 哈希。该包 App Shell 的 `ec`→Tab→`echo` 在终端和输入框同步显示；执行命令后 ↑ 恢复完整命令、↓ 恢复空草稿；“选择输出”子页显示当前快照。较早的 `da530c09…` 包在同机证实普通文本输入类型 `0x1`、发送动作、中文候选提交与退格、App/Root PTY 的 `id` 及选择输出复制；当前包未逐项重测这些操作。厂商键盘蓝色主题由输入法决定。Android 11 开发板及其他输入法对当前包未验收；更多检查与截图见 [verification.md](verification.md)。

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

## 五项工具当前边界（2026-09-26）

当前工具 APK SHA-256 `f928af6611f014206597321b2f35a094aef53b11eeb404ea7bbf72e23ee8a73b` 已按本 task 授权安装 PLR110 / Android16 / 4096 页 / SELinux Enforcing，设备哈希一致。七项定向 native 测试、SAF 导入/取消/产物导出读回、HFTP 本机后台通知与通知停止通过；App/Root 均可直接调用 Python。09 指定公共IP的实际HTTPS查询通过。宿主隔离测试、浏览器 loopback 与最终 APK ARM64 QEMU 另有证据。Android11、原生16KiB页设备、API28/29 no-replace syscall 分支、全新通知拒绝和真实离线网络未实测。新加密格式与旧 CBC 格式显式区分，08 无自动系统 UUID 盐，HFTP 仅保留核心文件操作，见 [工具设计](portable-tools.md)。

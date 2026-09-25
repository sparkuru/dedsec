# ctOS

个人 Android 系统观测与操作终端。当前 arm64 包包含网络观测、终端、设备信息和只读查询入口；容器构建及 Android 11、Android 16 实机检查已有记录。Flutter 界面，Java 采集后端，Vector/Xposed 系统模块。

项目各种信息统一入口：[design/README.md](design/README.md)。产品规划、约束、架构及验证记录均在 `design/` 维护。

状态：`incubating`。Android 11 开发板在较早包验证了 Vector 桥接和完整 4 项设备测试；Android 16 手机在当前包通过分身解析与 Root/PTY 四项定向测试，更早包验证过启动及覆盖安装后自动恢复 Root。该手机的 Vector 桥接未响应；重启后的新模块加载尚未验收。

唯一 current 安装包：`dist/ctos-current-arm64.apk`，校验和见 `dist/SHA256SUMS`。完整的已验证/待验证项目见 [verification.md](design/verification.md)。

## 功能

- 设备信息：系统、CPU 负载、内存、电池温度和存储快照，显示来源与可用状态。
- 只读命令：`device.info`、`memory.snapshot`，在 App 权限下按需执行。
- 网络配置：IP、DNS、路由、默认网络、VPN、Private DNS。
- 接口：地址、MTU、收发字节和包数、错误、丢包；按接口计算实时速率。
- 连接：TCP/UDP 快照，可按 IP、端口、状态、UID、应用名称、分身别名或包名检索；重新进入或从其他应用返回时刷新。
- 系统模块：在 system_server 中采集系统可见网络，通过受签名权限保护的广播返回。
- 终端：APK 内置原生 PTY，支持应用权限和已授权 Root 会话，xterm 渲染、Ctrl-C、ANSI 和窗口尺寸调整。
- JSON 导出：通过系统文件选择器保存，不上传数据。

## 安装

安装 APK 并首次启动时，ctOS 会向 Magisk 申请一次 Root 授权。允许后，后续启动和覆盖安装会自动恢复 Root 采集；拒绝或会话失败后不会反复弹窗，可在工作台点击“授权 Root”重试。完整卸载会清除 ctOS 的本地偏好；重新安装仍会主动申请 Root，是否免弹窗取决于 Magisk 是否保留该应用的授权。在 Vector 中启用 ctOS，作用域选择系统框架。首次加载系统模块需要重启设备。模块真正返回 UID 1000 的快照后，界面才显示 VECTOR 在线。

模块未启用时，普通 API 和已授权 root 采集仍可工作；界面明确显示来源。关闭 ctOS 会关闭终端会话，当前版本不提供后台常驻终端。

## 构建

固定工具链：Flutter 3.35.7 / Dart 3.9.2、JDK 17+、Android SDK 36、AGP 8.13.0、Gradle 8.14.3。

宿主只需 Docker 时，在本目录运行：

```sh
./hako flutter pub get
./hako flutter analyze
./hako flutter test
./hako current
```

`hako` 每次启动临时容器，首次运行会把镜像工具链复制到本项目被忽略的 `.devhome/`，并补齐 Android SDK 36、Build Tools 36.0.0 和 NDK 27.0.12077973；需要网络和足够磁盘空间。容器按当前用户 UID/GID 写文件，不发布端口。`android/local.properties` 在容器中由 `.devhome/local.properties` 覆盖，宿主原文件保留。该项目没有开发服务器，因此不生成 `dev.sh`；容器在命令结束时自动删除。

若从宿主构建切换到容器时遇到旧绝对路径缓存错误，先运行 `./hako flutter clean`，再运行 `./hako flutter pub get` 和构建命令。

原生 PTY 构建任务指定 Linux x86_64 NDK 工具，`hako` 因此固定使用 amd64 容器。`./hako current` 构建并验证签名后，原子替换 `dist/ctos-current-arm64.apk`、更新校验和；`dist/` 只保留这一份 current APK。`build/app/outputs/flutter-apk/app-release.apk` 是可再生成的中间产物。

已有宿主工具链时，也可直接运行：

```sh
flutter pub get
flutter analyze
flutter test
flutter build apk --release --target-platform android-arm64
```

初始开发构建使用 Android debug 签名，即使 Flutter 编译模式为 release。不要将它当作正式分发签名；签名密钥不纳入仓库。

## 已知边界

- 系统模块不是通用 root 授权器；APK、系统模块和终端是不同执行环境。
- 连接列表来自采样瞬间的内核 socket 信息，不是完整网络历史或抓包，也不承诺每条连接都有应用映射。
- 接口累计值不是“今日流量”；接口重置时重新建立速率基线。Wi-Fi、蜂窝、VPN、回环不直接求和。
- 终端提供 Android 系统已有命令，不包含 Linux 发行版、软件包管理器或 SSH 服务。Root 会话需要可用的 `su`。
- 系统模块只注册查询入口，不更改网络策略、不拦截或修改数据包、不占用 VPN。

设计见 [architecture.md](design/architecture.md)，兼容性见 [compatibility.md](design/compatibility.md)，依赖来源见 [dependencies.md](design/dependencies.md)，变更历史见 [changelog.md](design/changelog.md)。

# ctOS

个人 Android 系统观测与操作终端。当前 arm64 包包含网络观测、终端、设备信息和 Python 工作台。Flutter 界面，Java App/Root 采集后端；不依赖 Vector/Xposed。

项目各种信息统一入口：[design/README.md](design/README.md)。产品规划、约束、架构及验证记录均在 `design/` 维护。

状态：`incubating`。2026-09-27 工作台优化、HFTP日志与可选Root网络中继已安装TARGET-PHONE；保持原VPN限制时Windows可访问，33MiB LAN读写及停止回收通过。普通工具/文件仍App权限，无Root基础路径保留；各版本检查和未测范围见[验证记录](design/verification.md)。

唯一 current 安装包：`dist/ctos-current-arm64.apk`，校验和见 `dist/SHA256SUMS`。完整的已验证/待验证项目见 [verification.md](design/verification.md)。

## 功能

- 设备信息：系统、内存、电池温度和存储快照，显示来源与可用状态。
- 工作台：APK内置离线CPython 3.13.9；密码、编解码/哈希、IP、文件加解密及HFTP工具，另有环境与设备等脚本，进入二级页配置、运行及保存结果。
- HFTP：手动启动，选择本机目录、上传限额及App/可选Root网络中继，查看和复制服务日志；操作与权限边界见[工具设计](design/portable-tools.md)。
- Portable 运行包与 SDK：统一清单、参数和结果协议，可在构建时追加 Android 原生工具、ZIP 数据与基础脚本；见 [扩展契约](design/portable-workbench.md)。
- 网络配置：App API 可见的 IP、DNS、路由、默认网络、VPN、Private DNS。
- 接口：地址、MTU、收发字节和包数、错误、丢包；按接口计算实时速率。
- 连接：TCP/UDP 快照，可按 IP、端口、状态、UID、应用名称、分身别名或包名检索；重新进入或从其他应用返回时刷新。
- 终端：APK 内置原生 PTY，提供应用 Shell / Root PTY 双入口、普通键盘输入、Tab 补全、Ctrl-C 与输出选择复制；xterm 渲染 ANSI 并随界面调整尺寸。
- JSON 导出：通过系统文件选择器保存，不上传数据。

## 安装

安装 APK 并首次启动时，ctOS 会向设备的 Root 管理器申请一次授权。允许后，后续启动和覆盖安装会自动恢复 Root 采集；拒绝或会话失败后不会反复弹窗，可在概览点击“授权 Root”重试。完整卸载会清除 ctOS 的本地偏好；重新安装仍会主动申请 Root，是否免弹窗取决于 Root 管理器是否保留授权。无需配置 Vector 作用域或重启系统。

未授权 Root 时，普通 API 基础信息仍可工作，连接结果标明权限限制；界面明确显示来源。关闭 ctOS 会关闭终端会话，当前版本不提供后台常驻终端。

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

- Root 采集与 Root PTY 是不同会话，均通过设备已有的 `su` 工作。
- 连接列表来自采样瞬间的内核 socket 信息，不是完整网络历史或抓包，也不承诺每条连接都有应用映射。
- 接口累计值不是“今日流量”；接口重置时重新建立速率基线。Wi-Fi、蜂窝、VPN、回环不直接求和。
- 终端提供 Android 系统已有命令及内置 `python3`，可运行交互解释器和 `.py` 文件；不包含 Linux 发行版、运行时 pip 或 SSH 服务。Root 会话需要可用的 `su`。
- 网络观测使用固定只读查询，不更改网络策略、不拦截或修改数据包、不占用 VPN；网络 API 视图不承诺跨用户可见范围。

设计见 [architecture.md](design/architecture.md)，兼容性见 [compatibility.md](design/compatibility.md)，依赖来源见 [dependencies.md](design/dependencies.md)，变更历史见 [changelog.md](design/changelog.md)。

五项 Portable 工具（08 / 26 / 09 / 02 / 16-HFTP）的入口、文件边界与后台服务说明见 [工具设计](design/portable-tools.md)；本轮验证状态见 [verification](design/verification.md)。

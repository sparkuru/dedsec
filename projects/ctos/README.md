# ctOS

个人 Android 网络观测与终端工具。Flutter 界面，Java 采集后端，Vector/Xposed 系统模块。

状态：`incubating`，Android 11 / arm64 开发板 / Vector 2.2 已完成核心实机验收。Android 16 手机完成过初版界面验证，当前版本尚未在该手机完成验收。

当前安装包：`dist/ctos-0.1.0-arm64.apk`。完整的已验证/待验证项目见 [verification.md](docs/verification.md)。

## 功能

- 网络配置：IP、DNS、路由、默认网络、VPN、Private DNS。
- 接口：地址、MTU、收发字节和包数、错误、丢包；按接口计算实时速率。
- 连接：TCP/UDP 快照，可按 IP、端口、状态、UID、应用名称或包名检索。
- 系统模块：在 system_server 中采集系统可见网络，通过受签名权限保护的广播返回。
- 终端：APK 内置原生 PTY，支持应用权限和已授权 Root 会话，xterm 渲染、Ctrl-C、ANSI 和窗口尺寸调整。
- JSON 导出：通过系统文件选择器保存，不上传数据。

## 安装

安装 APK 后，在概览页点击“授权 Root”并在 Magisk 中授权。在 Vector 中启用 ctOS，作用域选择系统框架。首次加载系统模块需要重启设备。模块真正返回 UID 1000 的快照后，界面才显示 VECTOR 在线。

模块未启用时，普通 API 和已授权 root 采集仍可工作；界面明确显示来源。关闭 ctOS 会关闭终端会话，当前版本不提供后台常驻终端。

## 构建

固定工具链：Flutter 3.35.7 / Dart 3.9.2、JDK 17+、Android SDK 36、AGP 8.13.0、Gradle 8.14.3。

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

设计见 [architecture.md](docs/architecture.md)，验证状态见 [compatibility.md](docs/compatibility.md)，依赖来源见 [dependencies.md](docs/dependencies.md)。

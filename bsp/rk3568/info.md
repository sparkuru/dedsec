# RK3568 开发板

- 对象：Firefly AIO-3568J HDMI（Rockchip RK3568）开发板及其 Android 系统
- 范围与边界：记录该开发板的系统基线、研究事实、证据、风险判断与后续行动；不记录密码、完整原始日志、无需长期保存的临时输出
- 最近更新：2026-08-19
- 状态摘要：已通过 ADB 完成只读基线。设备运行 Android 11 userdebug，root ADB 经 TCP 5555 暴露，SELinux 为 permissive，Verified Boot 为 orange；硬件与 RKNN 服务正常，当前适合作为开发研究环境，不宜直接作为生产安全基线。

## 目标 / 资产清单

| 项 | 值 / 描述 | 状态 | 证据来源 |
| --- | --- | --- | --- |
| ADB 设备 | `192.168.9.14:5555`；product=`rk3568_firefly_aioj`；model=`AIO_3568J` | 确认 | `adb devices -l`；本次 ADB 基线采集 |
| 设备树 | model=`AIO-3568J HDMI (Android)`；compatible=`rockchip,rk3568-firefly-aioj`, `rockchip,rk3568` | 确认 | `adb -s 192.168.9.14:5555 shell` 读取 `/proc/device-tree/model`、`/proc/device-tree/compatible` |
| Android | Android 11，API 30；build type=`userdebug`；build display=`rk3568_firefly_aioj-userdebug 11 RD2A.211001.002 eng.lwy.20230721.161140 release-keys` | 确认 | `getprop ro.build.version.release`、`ro.build.version.sdk`、`ro.build.display.id`、`ro.build.type` |
| 厂商属性 | manufacturer=`T-CHIP`；brand=`Firefly`；model=`AIO-3568J`；hardware=`rk30board` | 确认 | `getprop ro.product.*`、`getprop ro.hardware`、`getprop ro.boot.hardware` |
| 内核 / 架构 | Linux `4.19.232 #66 SMP PREEMPT`；`aarch64` | 确认 | `uname -a` |
| CPU | 4 个逻辑 CPU；CPU part=`0xd05`；ARMv8/AArch64 | 确认 | `nproc`、`/proc/cpuinfo` |
| 内存 | `MemTotal=3981524 kB`；`MemAvailable=2704772 kB`；约 1.9 GiB zram swap，当前未使用 | 确认 | `free -h`、`/proc/meminfo` |
| 存储介质 | `mmcblk2` 约 29 GiB；`/data` 24G，使用约 5% | 确认 | `df -h`、`/proc/partitions` |
| 系统分区 | `/`、`/vendor`、`/product`、`/system_ext` 为 dm 分区，显示 100% 使用且以 `ro` 挂载 | 确认 | `df -h`、`mount` |
| 当前网络 | `eth1` 为 `192.168.9.14/24`，链路 UP；当前路由仅有 `192.168.9.0/24`，未见默认路由 | 确认 | `ip addr`、`ip route` |
| 其他网卡 | `eth0` 无 carrier；`wlan0` 当前 dormant / 无 carrier；`can0`、`can1` down | 确认 | `ip addr` |
| 监听端口 | `*:5555`、`*:5037` 由 `adbd` 监听；`*:59777` 由 `ngs.android.pop` 进程监听；另有两个 loopback 监听端口 | 确认 | `ss -ltnp` |
| ADB 权限 | shell 身份为 `root`，SELinux context=`u:r:su:s0`；`adbd` running | 确认 | `whoami`、`id`、`getprop init.svc.adbd`、`ps` |
| 调试属性 | `ro.debuggable=1`；`ro.secure=1`；`service.adb.tcp.port=5555`；USB 配置为 `adb` | 确认 | `getprop` 定向复核 |
| 启动安全 | `ro.boot.verifiedbootstate=orange`；`ro.boot.veritymode=enforcing`；启动参数含 `androidboot.selinux=permissive` | 确认 | `getprop`、`/proc/cmdline` |
| SELinux | `Permissive` | 确认 | `getenforce` |
| 加密 / 启动完成 | `ro.crypto.state=encrypted`；`sys.boot_completed=1` | 确认 | `getprop` |
| 关键服务 | `rknn_server`、vendor RKNPU、GPU、SurfaceFlinger、音视频、相机等服务处于 running | 确认 | `getprop` 中的 `init.svc.*` 状态 |

## 关键事实与发现

- **目标是 Firefly AIO-3568J HDMI / RK3568 板卡** — 状态：确认
  - 证据：设备树 `/proc/device-tree/model`、`/proc/device-tree/compatible`；`adb devices -l`。

- **系统是 Android 11 API 30 的 userdebug 构建，构建时间为 2023-07-21** — 状态：确认
  - 证据：`getprop ro.build.version.release`、`ro.build.version.sdk`、`ro.build.display.id`、`ro.build.type`。

- **板卡当前启动完成，4 核 AArch64 CPU、约 3.8 GiB 内存，运行压力暂不高** — 状态：确认
  - 证据：`sys.boot_completed=1`；`nproc=4`；`/proc/cpuinfo`；`free -h`；采集时 uptime 约 23 分钟，load average=`0.31 0.47 0.52`。

- **可写数据空间充足，Android 系统镜像分区为只读且文件系统表面上已满** — 状态：确认
  - 证据：`df -h` 与 `mount`；`/data` 约 24G、使用 5%，`/`、`/vendor`、`/product`、`/system_ext` 为 `ro`。
  - 备注：这些分区是否属于构建时按镜像容量填满的正常状态，仍需结合 super/dynamic partition 元数据进一步确认。

- **当前 ADB 是 root 级 TCP 调试入口** — 状态：确认
  - 证据：`whoami=root`；`id` 显示 `uid=0(root)` 与 `u:r:su:s0`；`service.adb.tcp.port=5555`；`ss -ltnp` 显示 `*:5555` 和 `*:5037` 由 `adbd` 监听。

- **当前安全基线偏开发态而非生产态** — 状态：确认
  - 证据：`ro.debuggable=1`、`ro.build.type=userdebug`、`getenforce=Permissive`、`ro.boot.verifiedbootstate=orange`、`/proc/cmdline` 中的 `androidboot.selinux=permissive`。
  - 备注：`ro.adb.secure` 本次定向读取为空，不能据此确认 ADB 已启用认证；在 root ADB 和 TCP 暴露同时存在的情况下，应按高风险处理。

- **RKNN 与主要 Android 硬件服务已经启动** — 状态：确认
  - 证据：`getprop` 的 `init.svc.rknn_server`、`init.svc.vendor.rknn-1-0`、GPU、SurfaceFlinger、vendor media / gralloc / hwcomposer 状态为 running。

- **网络可达性目前主要依赖 eth1 的局域网链路，未观察到默认路由** — 状态：确认
  - 证据：`ip addr` 显示 `eth1=192.168.9.14/24`；`ip route` 仅显示 `192.168.9.0/24 dev eth1`。

- **内核日志存在需要后续确认的稳定性信号** — 状态：确认
  - 证据：`dmesg | tail` 出现 `audit_lost=466`、`audit rate limit exceeded`、一次 `adbd` 重启、`dwc3 ... failed to enable ep0out`，以及有线 / Wi-Fi 链路上下线记录。

## 假设台账

- **系统分区 100% 使用是 Android 只读系统镜像的正常表现，而不是空间故障** — 状态：待验证
  - 证实判据：读取 super/dynamic partition 元数据，确认 dm 分区大小与镜像布局一致，且系统服务无因只读分区空间不足导致的错误。
  - 证伪判据：发现系统分区存在可写需求、实际写入失败，或 logcat / dmesg 明确报告 no space left on device。
  - 当前倾向：偏正常；因为相关分区均以 `ro` 挂载，而 `/data` 仍有充足空间。

- **`adbd` 重启与 USB endpoint 错误是偶发事件，而非持续性 USB/ADB 不稳定** — 状态：待验证
  - 证实判据：持续观察一段时间后不再出现新的 adbd 重启，且 USB ADB / TCP ADB 连续操作稳定。
  - 证伪判据：重复出现 adbd 退出、USB gadget 配置失败或 ADB 连接周期性断开。
  - 当前倾向：待观察；当前 TCP ADB 连接仍可用。

- **端口 `59777` 是板上预期的应用服务，而非不必要的第三方暴露** — 状态：待验证
  - 证实判据：将监听 PID `1671` 映射到明确包名、启动配置和业务用途，并确认访问控制。
  - 证伪判据：无法归属、无需对外提供，或可从局域网无认证访问。
  - 当前倾向：需核查；当前只能确认进程名为 `ngs.android.pop`。

- **未见默认路由是实验室局域网的有意配置，而非网络配置缺失** — 状态：待验证
  - 证实判据：用户确认只需访问 `192.168.9.0/24`，或后续网络测试表明无互联网需求。
  - 证伪判据：后续任务需要访问外部仓库、时间同步或远程服务而当前无法连通。
  - 当前倾向：待结合后续开发任务确认。

## 决策与权衡

- **工作目录**：固定为 `/home/wkyuu/cargo/repo/06-dedsec/rk3568/`。
  - 原因：用户指定该目录作为开发板基本资料与后续研究的长期工作位置。
  - 性质：用户明确指定。

- **资料组织**：使用单一活档 `work-dossier-rk3568.md`，按对象持续增量更新。
  - 原因：避免按日期拆散基线、发现、证据与后续行动。
  - 性质：工作档案约束。

- **敏感数据处理**：不把密码、完整内核日志、完整 MAC 地址或 Wi-Fi SSID 固定进档案。
  - 原因：这些内容不是当前基本参数所必需，且会增加后续资料暴露面。
  - 性质：安全与最小化记录原则。

## 踩坑 / 反直觉点

- **首次本机 `adb devices -l` 失败并不代表开发板不可达**
  - 根因：本地沙箱不允许 ADB daemon 绑定 `5037`，随后在受控权限下启动成功并发现设备在线。
  - 规避：后续遇到同类错误，先区分本机 ADB daemon 权限问题与板端网络 / 认证问题。

- **Android 系统分区显示 100% 不等于 `/data` 已满**
  - 根因：system/vendor/product 等 dm 分区是只读系统镜像，容量显示与镜像布局相关。
  - 规避：同时查看 `mount` 的 `ro/rw` 属性和 `/data` 单独容量，不据单个 `df` 百分比判断整机磁盘故障。

## 当前焦点

- 保留 2026-08-19 ADB 只读基线，后续变更前后以本档案为对比基准。
- 建立 Android 启动链、AVB / dm-verity、SELinux、ADB 暴露面的研究边界。
- 建立 RKNN、显示、编解码、GPU、设备树与内核驱动的开发资料索引。
- 首次刷机、分区写入或系统修改前，先确定可恢复的固件 / 分区备份方案。

## 下一步行动

- **在首次写入、刷机或修改系统分区前，采集可恢复基线**
  - 触发条件：准备执行任何持久化修改或需要比较修改前后行为时。
  - 预期影响：获得分区布局、启动链、关键属性与回滚所需信息。

- **核查 ADB 和网络暴露面**
  - 触发条件：准备把板卡接入非隔离网络，或开始安全研究时。
  - 预期影响：明确 `5555`、`5037`、`59777` 的认证、访问范围与关闭条件。

- **复核 adbd / USB / audit 日志是否持续增长**
  - 触发条件：出现 ADB 断连、USB 识别异常、应用权限异常或系统稳定性问题时。
  - 预期影响：区分偶发启动噪声与持续性驱动 / 安全策略故障。

- **建立 RKNN 与嵌入式开发工具链清单**
  - 触发条件：开始部署模型、编译 native 组件、修改设备树或调试驱动时。
  - 预期影响：锁定 SDK、NDK、交叉编译器、运行库和目标 ABI 版本。

## 证据索引

- `adb devices -l` → 支撑 ADB 设备序列、product、model、transport 状态。
- `adb -s 192.168.9.14:5555 shell 'printf "probe=ok\\n"; uname -s'` → 支撑 ADB 连通性与 Linux shell 响应。
- `adb -s 192.168.9.14:5555 shell sh -s`（2026-08-19，只读基线脚本）→ 支撑身份、设备树、Android 属性、内核、CPU、内存、存储、挂载、网络、端口、进程、服务、SELinux 与 dmesg 条目。
- `adb -s 192.168.9.14:5555 shell sh -s`（2026-08-19，ADB 属性复核）→ 支撑 `ro.adb.secure`、`ro.debuggable`、TCP ADB 端口、USB 配置、Verified Boot、adbd 状态。

## 已修正 / 历史结论

当前没有被新证据推翻的历史结论。

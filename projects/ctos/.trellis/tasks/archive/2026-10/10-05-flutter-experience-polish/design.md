# 技术设计

状态：方案已批准，实施中。视觉权威入口为 `design/flutter-experience.md`。

## 结构及边界

- 提取 `lib/ui/` 主题/语义 token 和适量共享展示组件，保留 `CtosApp` 为唯一主题入口。先搜索复用 card、heading、readingColumn、WorkbenchActions，不创建平行状态或执行 registry。
- `main.dart` 保持观测数据、采样和 PTY 状态，仅调整展示。宽屏导航转换保持同一内容子树/Key/IndexedStack，不能双构造或停止终端。
- 概览投影既有 DeviceSnapshot/网络；未知保持未知，真实数据才绘制指标。TrafficChart 保留实际历史、接口独立计数和采样时机。
- 设备/接口/连接字段及条件不变；按宽度/textScaler 调整行列，包名/IP/输出可换行或选择复制。
- 工作台复用 catalogue/models/API；详情改用共享展示，任务取消、SAF、业务/log revision、HFTP stopping 锁和独立 PageStorageKeys 保留。
- `terminal_interaction.dart` Unicode/IME/历史算法不改；可调整输出/输入/控制展示。只读 TerminalView、onResize 与唯一 IME 输入保持。

## 展示契约

颜色、字号、表面和间距依项目设计文档；普通文字对比至少 4.5:1，使用系统字体 tabularFigures，不下载字体、不钳制文字缩放。共享组件不执行平台操作。按钮主要区域至少 48 dp，真实禁用和语义标签。Material/ScaffoldMessenger 反馈配文字/图形状态；160–240 ms 运动响应 MediaQuery.disableAnimations。

约 900 dp 起 NavigationRail，约 840 dp 正文；概览约 640 dp 有效宽度可分列，大字退回单列。终端有键盘时保留输入/必要控制。主题变化不能隐藏权限/错误，也不能通过放宽测试掩盖回退。

## 风险、兼容与回滚

布局改变影响焦点、滚动、测试 finder 和 PTY 尺寸；用行为/有效载荷断言验证，结构改变可以更新查询但保持断言。当前 Android 15 安装旧包不是当前基线；实施时保存旧 APK/签名，再覆盖新包并核验。签名不符不能卸载/清数据绕过。

只回退本任务展示改动，保留用户/其他任务工作。APK 和原始证据保留 `/tmp`，需要设备回滚时使用核验同签名包。新包启动沿既有 Root 自动恢复，不能修改 Root 管理器或系统设置。项目设计、架构、依赖、实测依实际变化同步 `design/`，任务 `check.md` 汇总 R1–R8 证据。

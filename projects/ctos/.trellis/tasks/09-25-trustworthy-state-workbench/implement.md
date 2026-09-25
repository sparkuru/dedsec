# 实施顺序与验证

1. 复核工作区差异、适用 Trellis 前端规范、`design/constraints.md` 和本任务设计；保留非本任务改动。
2. 在 Flutter 侧建立连接快照状态模型，处理首次读取、部分结果、成功后失败、旧数据、无匹配和采集时间；为状态转移写聚焦测试。
3. 让工作台/顶栏分别表达 App、Root、Vector，并把已有 `rootError` 与启动授权失败接到可恢复提示；测试不重复申请 Root。
4. 修改系统负载的受限说明与原始错误展开；重新组织工作台首屏并建立少量复用样式/宽度约束。
5. 添加关键组件测试：360dp、大字号、连接状态、Root/Vector 组合和 Android 16 受限负载。对现有网络、终端、导出测试保持回归。
6. 运行 `./hako flutter analyze`、`./hako flutter test`；若改动 Java，再运行 `./hako bash -lc 'cd android && ./gradlew :app:lintRelease --console=plain'`。运行 `./hako current` 构建并校验当前 APK。仅对 `adb devices -l` 中在线且身份复核的 `192.168.9.9:45797` 用 `adb -s` 定向覆盖安装/检查。不要清数据、改 Magisk/Vector、重启。
7. 对比新包的工作台、连接页和负载受限页面；记录 APK 标识、设备环境、实际结果及 Android 11 待验收项，更新 `design/architecture.md`、`changelog.md`、`verification.md` 与任务检查证据。
8. 用户追加连接 wrapper：解析现有 `ss -tunape` 输出为 item，保留诊断与原文；补充解析及 UI 测试，重跑 Flutter 检查、构建并在同一授权设备检查连接列表和筛选。更新本任务和 `design/` 后重新请求评审及提交确认。
9. 用户追加应用图标与名称检索：原生侧只为本次连接出现的 UID 附应用元数据与图标，Flutter 缓存并显示，支持多种名称字段筛选与同 UID 多包展开。跑 Android lint、构建/测试 APK、Flutter 测试及同一授权手机回归；将结果归档到 `design/`，再请求可视评审。
10. 用户反馈 QQ 新连接与分身别名：复现旧快照搜不到 QQ；前后台恢复及重新进入连接页时更新快照。解析多用户包 UID，并仅对可得的 Oplus 桌面别名做固定只读查询；缺失时回退，精确应用名优先。补 Flutter/Android 解析测试、定向实机验证，更新兼容性与验证记录后重做视觉评审。

## 检查门槛

- `./hako` 若不可用，先诊断工具链，不绕过验证。
- 完成差异审查与 Trellis check 后，按 `.trellis/spec/trellis-plus/index.md` 对可视结果和实机权限行为分类人工评审；提交只包含本任务相关路径。
- 下一兄弟任务以本任务实际接口为依据，不能假定本设计文本即已实现。

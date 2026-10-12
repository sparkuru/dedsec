# 实施计划

- [x] 实施前读任务三份文档、frontend 相关规范及公共秘密库契约。
- [x] 新建设置展示页面；迁移安全入口/标题/引导文案，保留既有秘密库所有操作。
- [x] 概览合并四分类和完整内容，删除两个区块；更新四入口及全部索引、刷新、键盘和回跳逻辑。
- [x] 更新导航/导出/布局/双返回相关旧测试，补充设置路由、延迟秘密库访问、完整滚动及轮询回归。
- [x] `./hako dart format ...`、`./hako flutter analyze`、相关 widget tests，然后 `./hako flutter test`。
- [x] 必要时用现有真实字体 Flutter render 方法生成临时截图检查；`./preview.sh` 构建 arm64 release APK 并验签，不安装/替换 dist。
- [x] Trellis check 复核完整 diff 与 AC1–AC5，主会话核实证据；同步 design 索引/架构/规划/认证/变更/验证及必要 spec。
- [x] 记录 human-required（导航及滚动视觉验收），保留 current/in_progress，交付产物和具体待验步骤，未获授权不提交或归档。

实现范围：lib/main.dart、lib/settings_page.dart、lib/workbench.dart、lib/secrets/settings_page.dart/flows.dart、受影响 Flutter tests。检查不得修改 Android 安全实现来绕过失败。

## 追加开发板验收

- [x] 用户追加授权后核对当前AIO-3568J/API30，保留旧APK，覆盖安装并读回哈希一致。
- [x] 检查四分类滚动、设置及完整安全入口、备份认证取消和快照SAF取消。
- [x] 同步Android隔离UI夹具旧导航/标题，并离线编译instrumentation（未重跑认证E2E）。
- [x] 开发板已解锁，新版继续四入口双返回、二级返回及App PTY切换验收通过。
- [x] 按最终实际范围同步device check和design；真实共享快照写盘遭自动审批拒绝、保留未执行边界。

## 用户截图追加实施

- [x] 分类全宽居中、Root标题旁信息按钮、移除APP状态和终端眉标题，更新布局/状态测试和夹具。
- [x] 自动首次快捷/无登记口令/失败三选择、重试新请求与取消迟到保护，补齐针对性回归。
- [x] 188全量/53秘密库、analyze/14格式、84真实字体渲染及arm64/v2验签；同步spec/design。
- [x] 核对当前授权开发板已解锁后安装新版、读回哈希，并验收新布局及快捷直接调起/Home系统取消后回退选择，不输入系统凭证。
- [x] 用户基于新版截图继续要求细节收敛，并授权完成后提交当前任务；新版成功认证等未测边界保留，不重复提交审批。

## 最终截图收敛与提交

- [x] 去掉DEVICE/Android运行摘要行；网络/接口/连接副标题统一范围描述，接口来源/计数保留元数据。
- [x] 定向、analyze/格式及完整任务check；84真字体场景通过，构建/v2验签通过。
- [x] 新版安装读回与有界四分类布局实测；同步design/spec/check，归档链接在归档阶段更新。
- [x] 明确35个本任务候选路径，实现/测试/spec/design/任务提交5e67227完成，其他任务状态保持。
- [x] 完成后按正常归档/会话记录流程收尾；成功认证/快照写盘未测边界作为历史证据保留。

# 实施计划

- [x] 读取 PRD/design、frontend 与验证 spec，检查当前源码与 pinned Flutter 返回实现。
- [x] 在 Flutter UI 层实现局部双返回 guard，主页/observer 接线，保留二级页/弹窗/键盘与后台语义。
- [x] 增加有意义的 widget 回归：真实 back 派发与退出 channel 观察、超时边界、四入口及重置/资源释放。
- [x] `./hako dart format --output=none --set-exit-if-changed <本任务 Dart 文件>`。
- [x] `./hako flutter test test/home_exit_guard_test.dart`，14项通过。
- [x] `./hako flutter analyze` 与 `./hako flutter test`，无问题/152项通过。
- [x] Trellis check 代理独立审阅并修复；修复提示遮挡后重跑全部相关检查。
- [x] `./hako flutter build apk --release --target-platform android-arm64`，v2验签与 SHA-256；未替换 dist 或安装。
- [x] 同步 design 架构/规划/索引/变更/真实验证、task check 与稳定 frontend 契约。
- [x] 分类 `human-required`：已提供可安装产物及精确手动场景，真实 Android 返回/IME/桌面行为未验证，暂不提交/归档。

本轮不修改 Android/native/秘密库功能。现有 dirty work 必须保留。公共秘密库暂停登记在 task metadata 与 mainline，不将其标为完成。

## 用户验收后的下方提示调整

- [x] 记录用户报告上一轮实机通过，仅更改提示位置。
- [x] 利用 Scaffold公共布局槽使提示靠下并避让导航/其他Snackbar，保留计时/退出/重置逻辑。
- [x] 底部位置/多行Snackbar不重叠/大字窄屏/production rail回归，最终16定向/154全量、analyze与格式通过。
- [x] 独立Trellis check、最新981ef814 arm64 APK构建/验签/哈希，同步design/spec/真实检查与旧版用户反馈。

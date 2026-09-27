# 图片反馈 UI 实现检查（2026-09-27）

范围：models、parameter_field、script_page、result_card、controls、file_store_card、workbench 重试操作，以及既有 Flutter 工作台测试和新增 workbench_feedback_test。HFTP Dart/API/新增 HFTP 测试由主会话维护。

- 五项工具列表及详情标题去掉数字前缀；删除脚本 ID“技术详情”tile。
- 文本操作按钮以 WorkbenchActions 在内容区纵向展开，间隔 8dp、最小高度 48dp，覆盖选择/取消选择/高级选项/运行/取消/结果复制及保存/临时文件对话框/重试。图标按钮保留。
- WorkbenchDropdown 采用 ButtonTheme.fromButtonThemeData(alignedDropdown: true)，取消 Flutter 下拉 route 的横向额外 margin；保留 enum wire values、动态高度和完整文本。
- 秘密输入使用普通 TextInputType.text 且平台 obscureText=false。MaskedTextController 只呈现 UTF-16 等长 bullet，组合区保留下划线，原始 controller 与编辑选区/组合区双向同步；保留普通建议标志，关闭自动更正及 IME 个性化学习。遮挡时替代 accessibility value，显示图标单独保留语义。显式显示切换不改变输入值。

验证：

1. `./hako flutter analyze`：无问题。
2. `./hako flutter test test/workbench_test.dart test/portable_tools_test.dart test/workbench_usability_test.dart test/workbench_feedback_test.dart --reporter expanded`：25/25 通过。
3. `git diff --check -- lib/workbench.dart lib/workbench test`：通过。

新增四项测试验证秘密输入普通 IME 配置、精确 Unicode/UTF-16 选区及组合编辑值、遮挡 glyph/accessibility/reveal；375dp、812dp 横屏、2x 字号实际弹窗 Material 与字段左边界/宽度一致，文件/运行/保存/复制按钮完整宽度。原有精确提交、盐值折叠、文件来源草稿、密码结果及 raw JSON 遮挡继续通过。

未声明实测：OEM 普通键盘行为、屏幕阅读器交互及最终 APK 视觉效果仍需要主会话设备验收；未构建 Android、安装、提交或清理设备文件。最初新增 HFTP 定向测试遇到测试 helper 的进度动画 settle/懒加载问题，已向主会话报告，由其维护；以上 25 项记录不包含该文件。

## 普通 IME 实机失败后的窄修订

主会话实际安装 cf740d27… 后发现 R8 未通过：seed 字段 EditorInfo.inputType=0x80091，mCurId=com.oplus.securitykeyboard/.InputService。只设置 obscureText=false 不足以保证普通 IME。其核对 [Flutter Android TextInputPlugin 官方源码](https://dart.googlesource.com/external/github.com/flutter/engine/+/8bbf25ecf6a2577266e02c0fb4bd68169e9168a4/shell/platform/android/io/flutter/plugin/editing/TextInputPlugin.java)：enableSuggestions=false 会合成 TYPE_TEXT_VARIATION_VISIBLE_PASSWORD，触发 OEM secure keyboard。

窄修订将 enableSuggestions 保留 true，以便引擎采用普通 text variation；仍保持 autocorrect=false、enableIMEPersonalizedLearning=false、smartQuotes/smartDashes disabled 以及原始值、glyph 和 accessibility 遮挡。测试新增 platform 配置 true 断言，防止以后为了关闭建议重新引入密码 variation。此处不改全局键盘配置；普通建议标志是遵循用户明确普通键盘要求的兼容取舍。最终新包 EditorInfo/IME 仍由主会话实测，不能用 Flutter 测试声明 OEM 行为通过。

窄修订验证：`./hako flutter test test/workbench_feedback_test.dart test/workbench_usability_test.dart test/portable_tools_test.dart --reporter expanded`，19/19 通过；`./hako flutter analyze` 无问题；三个窄修订文件 `git diff --check` 通过。未重新构建或安装 APK。

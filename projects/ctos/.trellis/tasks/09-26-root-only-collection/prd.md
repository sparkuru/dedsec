# Remove Vector dependency and verify Root collection

## Goal

解释当前手机启用作用域但桥接未响应的原因；按用户要求去掉不必要的 Vector 依赖，保留 Root 采集和终端。

## Requirements

- 工作限 projects/ctos；只覆盖安装 ctOS 与测试包，不修改 Vector/Magisk 配置或重启。
- 保留 Root 会话复用、自动恢复、失败后手动授权及无 Root 基础信息。

## Acceptance Criteria

- [x] APK 无 Xposed 模块声明/API/入口，App 无桥接广播与 module 字段。
- [x] 界面只有 App/Root 能力，接口/路由/连接/分身解析及 PTY 检查通过；live 分身筛选本轮未重测。
- [x] Flutter 检查、Android lint、APK 构建及当前手机设备测试通过，未测范围已记录。

## Notes

- 当前用户请求授权完成实现与设备验证，不扩展到远程服务或其他功能。

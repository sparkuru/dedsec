# SSH 配置：RequestTTY no

状态：2026-11-16 接续 1008，源码和本地分层检查完成；设备和真实服务器未验证。

本项消费实际选定 SSH config 的 `RequestTTY`，让明确设置为 `no` 或 `false` 的
连接在认证后直接请求远端 shell，不请求 PTY。固定 OpenSSH 解析器继续决定 Host、
Include 和首个生效值；`auto`、`yes`、`true`、`force` 及手动连接沿用原有 PTY。
计划中的原字段跨重复受保护快照核对，变化则失败；重连重新求值。

无 PTY 时，仍须获得服务器真实 shell 成功答复才能开放输入及提前输出；失败、取消
与关闭沿用现有会话清理。尺寸变化对无 PTY 会话成功消费，但不会发出 window-change。
无 PTY 的远端程序可能不回显输入、没有提示符，也可能不按终端处理控制字符；实际
行为由远端决定。此功能不实现 ProxyJump、转发或 RemoteCommand。

协议依据：[OpenSSH 配置手册](https://man.openbsd.org/ssh_config#RequestTTY)和
[RFC 4254 的会话通道请求](https://www.rfc-editor.org/rfc/rfc4254.html#section-6.2)；
值及优先级另对照项目固定 OpenSSH 10.6p1 的 `readconf.c`。完整合同见
[后端规范](../.trellis/spec/backend/ssh-request-tty.md)，本地测试与未测范围见
[验证记录](verification.md#20261116-ssh-request-tty)。

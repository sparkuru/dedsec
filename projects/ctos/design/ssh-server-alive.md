# SSH 配置：服务器保活

状态：2026-11-16 接续 1008；源码与本地分层验证完成，已授权候选在指定板经受限
合成服务验证应答、无应答关闭、后台保活及锁屏清理；用户本人体验和真实服务器待验证。
测试中发现的通道输出时序问题已修复并完成[指定板复测](verification.md#20261116-ssh-early-output-device)。

## 目标

支持用户主动导入的 SSH config 中 `ServerAliveInterval` 与
`ServerAliveCountMax`，避免配置因这两个常用选项被直接拒绝，并按实际生效配置在
已建立的 SSH 会话中检查服务器是否响应。手动连接和没有启用保活的配置保持现有行为。
本项是原 1008 的 R6／AC3 配置兼容切片，不代表 ProxyJump、TCP 转发或完整 config 已完成。

## 已实现行为

- 使用项目固定的 OpenSSH 配置求值及 Host／Include 规则，保留其默认值：间隔 0 表示
  不启用，未指定的次数为 3。间隔和次数属于本次实际配置，不另增加 App 全局设置。
- 会话使用受保护的 SSH 加密通道发送需要答复的保活请求。服务器数据到达会重新计算
  空闲间隔；成功或失败答复都表示服务器已回应。连续无回应达到配置次数后，下一次
  间隔仍无服务器数据则结束连接，并走现有错误及资源回收路径。次数为 0 时首个空闲
  间隔即断开。
- 原生求值得出的值须经有界的版本化连接计划传给同一受管会话，配置更改、取消、
  锁屏、Stop 和重连均遵守现有所有权与失效语义。不会因保活绕过主机指纹或认证，也
  不从远端输出推断连接成功。真实服务器、设备和锁屏表现单独实测。

## 验收边界

核对根配置和 Include／Host 优先级、默认值、0 与最大合法值、错误配置及旧版计划；
在真实加密合成 SSH 协议上覆盖空闲触发、请求成功／失败答复、连续无响应、收到数据
后的计时、多个未答复请求、取消和连接关闭。独立验证 Java 计划传递、JNI 编译绑定及
既有登录、终端和后台会话回归；实际 Android JNI 调用、网络及用户体验只按实测报告。

协议依据：[OpenBSD ssh_config 手册](https://man.openbsd.org/ssh_config#ServerAliveInterval)、
[RFC 4254 第 4 节](https://www.rfc-editor.org/rfc/rfc4254#section-4)；同时对照项目
固定的 OpenSSH 10.6p1 `clientloop.c` 和 `readconf.c`，不以较新的在线源码覆盖固定版本。
实现研究与执行进度见 [任务记录](../.trellis/tasks/10-08-ssh-terminal/implement.md)，
本次[本地证据](verification.md#20261116-ssh-server-alive-local)、
[指定板证据和未测范围](verification.md#20261116-ssh-server-alive-device)分列。

# ServerAlive 配置与会话执行研究

日期：2026-11-16；范围为现有 1008 R6／AC3 的 `ServerAliveInterval`、
`ServerAliveCountMax`。本记录是源码／固定上游研究，不是实现或设备验收。

## 现状

- `ssh_config_eval.c` 的 direct keyword allowlist 不接受这两项；
  `ssh_config_managed_detached_test.c` 将 `ServerAliveInterval 1` 列为拒绝案例。
  不能只放开解析：C direct plan、CSCP／CSPP、Java `DirectPlan` 和连接器均未传递值。
- `SshTerminalSession` 的原 owner 每 10 ms 推进 `SshNativeSession.step()`；真正的
  SSH 加密数据收发与 shell 协议在 `ssh_native_client.c`。会话已建立后不再依赖短期
  私钥／配置视图，因此保活策略必须在受保护的配置快照仍有效时冻结到该会话。
- 现有 `shell_packet` 接收服务器的 global request，但没有客户端保活请求及其
  REQUEST_SUCCESS／FAILURE 匹配；两种答复不能按 channel packet 读取。

## 固定上游与协议

- 项目固定 OpenSSH 10.6p1 `readconf.c` 的默认 interval 为 0、count 为 3；首次
  生效值、时间格式和非法值由已有上游求值器决定。
- 固定版本 `clientloop.c` 在服务器一段时间没有发送数据时发出
  `keepalive@openssh.com` global request，要求答复；服务器数据重设下次探测时间，
  关联的 global reply 将未响应次数清零。计数先递增，超过 count 才断开；因而
  count=0 在首个到期间隔断开。计时与写出缓冲、输入分片、停止和资源回收需一并处理。
- [OpenBSD ssh_config](https://man.openbsd.org/ssh_config#ServerAliveInterval)
  描述两个选项；[RFC 4254 §4](https://www.rfc-editor.org/rfc/rfc4254#section-4)
  定义 global request/response。只把实际加密协议夹具、JVM、JNI/Android 和设备证据
  分别报告，不把任意一层冒充完整链路。

## 实施边界

按上游求值扩展 direct/profile plan 的有界版本化 wire；旧版本仍按 interval 0/count 3
读取。配置密码、私钥和独立 KBI 必须取得同一快照的策略；手动连接不启用。仅原
session owner 可以在已完成认证的原会话配置一次，之后通过现有 pump 驱动；认证成功
仍是建立 shell 的门槛。`ServerAliveInterval 0` 不发送探测；Stop、锁屏、取消和异常
仍执行原有关闭流程。客户端当前没有其他需 global reply 的功能，未知或无待答复的
reply 应保持协议拒绝；未来转发实现需要统一有序 global request 队列，不能假定所有
答复都属于保活。

测试须覆盖原生配置计划、旧版本和非法 wire、真实加密请求及两种回复、无响应阈值、
数据延期和资源回收；单独检查 JNI 策略传递与 JVM 生命周期。不得为了让测试配置通过
而放宽其他目前拒绝的选项，不操作真实账号或设备。

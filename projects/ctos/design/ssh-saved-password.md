# SSH 密码保存与复用

2026-10-26：用户在已有 SSH 父规划中选择此项接续，明确不新建 task。本文是本轮实现
合同，早期本地检查见[本轮验证](verification.md#20261026-saved-password)。随后已通过
开发板实际bridge保存/复用/忘记及隔离回归，并随正式APK安装；
[最终验证及人工审阅](verification.md#20261026-phone-ssh)、[使用入口](ssh-terminal.md)。

## 用户行为

连接表单默认输入密码、仅本次使用。用户勾选“连接时保存密码”才把该密码写入与 TOTP
共用的加密库；保存动作可先于网络连接完成，后续连接失败不代表已保存内容回滚。
同一连接已有密码时，用户可明确选择“使用已保存密码”，不把秘密回填到输入框。
新输入密码可替换同一连接的旧密码；未选择保存时，不修改该连接原先保存的密码。

保存记录精确绑定 host、port、username，多条连接分别保存。不 trim 用户名或密码。
修改任一目标字段立即取消旧的已存选择，异步状态回执不能重新启用旧目标。
界面只能读取“是否已保存”；密码 bytes 留在宿主的受限读取/认证通道。

提供“忘记此连接密码”及“忘记全部 SSH 密码”，确认后只删除对应 Account/密码，
不删除 TOTP、最近连接配置或主机信任。忘记/保存必须持有当前前台库会话和敏感页面保护。

## 存储与认证边界

沿唯一正式 `VaultManifest` 的 Account + SSH_PASSWORD，不建立第二凭据数据库或格式。
账号元数据保存实际 tuple、密码 entry ID 与 target identity；ID/target 从无歧义的长度
分帧 tuple 摘要派生，不能依赖分隔符拼接或从 opaque identity 反解目标。
所有已存账号/密码必须一一匹配规范 tuple、ID/identity、类型及 1..8192 bytes/noNUL；
整个实际 manifest 仍拒绝本连接方式不支持的 SSH 对象，保留生成 CONFIG/KNOWN_HOSTS 与 TOTP。

账号和密码同次原子写入/删除。复用公共请求的原 epoch、deadline、取消、validator 注册、
独占 source/candidate 校验、唯一 commit claim 与提交不确定规则；提交前失败保留旧库。
连接所需写入在创建 public view/role 前完成，仅允许本次精确写入推进 epoch。

复用走已有 selected credential：实际主机键审批、签名 KEX、role/selection、目标/用户名、
库代次与最终 guarded copy 均匹配后，才向 native 认证交付密码。主机键变化/撤销、锁库、
取消、过期、目标/账号错配不得消费密码。未保存的密码仍走一次临时 lease，不先持久化。
密码不进入事件、默认值、普通配置、历史或日志；宿主持有的 bytes 在所有退出路径擦除。
Flutter/MethodChannel 不可变 String 的清引用不等同于 VM 内存擦除。

## 通道合同

| 方法 | 输入 | 公开响应 |
| --- | --- | --- |
| `ssh.defaults` | `requestId` | `host, port, username, savedPassword: bool` |
| `ssh.passwordStatus` | `requestId, host, port, username` | `saved: bool` |
| `ssh.passwordConnect` | 原连接字段，加 `useSavedPassword, savePassword` | 原 `requestId, status` |
| `ssh.forgetPassword` | `requestId, host, port, username, all: bool` | `forgotten: bool` |

使用已存密码时 `password` 为空且 `savePassword=false`；其他情况保持严格密码输入校验。
客户端不传 entryId/target。status 使用独立 request ID，取代旧 idle guard；取消仅命中
精确 ID。目标编辑、后台、离页、通道失败及旧回执分别受 revision/请求所有权约束。
`all=true` 的忘记操作固定传空 host/username、port=22，不要求存在最近连接或合法表单
tuple；宿主以明确的全部密码用途执行，不构造一个虚假的连接账号。

显式保存连接或忘记处于进行中时，页面取消等待宿主最终回执；提交不确定需在受保护
页面确认已知悉后离开。连接成功与取消竞争时，只能以原 requestId 取消精确已交接
connector，不能发送无目标的 terminalStop，也不能由旧请求影响更新后的终端。

## 验证边界

本轮实际覆盖真实加密读写的 JVM 场景、精确绑定/篡改/取消/锁定/过期/失败保持旧库，
Flutter 的默认当次/保存/复用/忘记/迟到/窄屏横屏大字，以及 analyze、Android lint 和构建。
合成测试不代表 Android 实际 Keystore、网络认证或设备界面通过；当前不沿用历史安装或
真实账号授权。复杂 SSH 恢复仍遵守既有 metadata 审阅门，不因新增账号提前放行。

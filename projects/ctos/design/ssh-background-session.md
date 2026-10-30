# SSH 后台会话与通知 Stop

2026-10-30 当前：[用户指定开发板闭环](verification.md#20261030-ssh-lock-disconnect-board)
70.244秒通过，真实锁屏锁库并断开SSH、实际点击一键建立新代次及原后台/重建/通知Stop
均通过。统一业务模型不按系统版本拆分；手机/逐版本补验不作为当前前置。正式包未更新。

2026-10-28 当前获用户选择的调整（已实现/本地检查通过，手机待连）：锁屏结束SSH，回来由用户点“重新连接”，
正常解锁秘密库及重新检查配置/身份/主机信任。普通Home后台和Activity重建继续沿服务
所有权；不追求手机锁屏/Doze保活，不修改Thanox或VPN。冻结环境调查已结束并清理，
[内核UID冻结证据](verification.md#20261028-ssh-phone-physical-investigation)保留为历史失败。
本次不接入Mosh、部署tmux或自动重连；下文行为合同更新为新目标，实测以verification为准。

2026-10-28：[Android16手机通知权限拒绝状态](verification.md#20261028-ssh-phone-runtime-denied)
已通过后台锁库、恢复与真实Flutter应用内停止；权限弹窗交互、手机物理锁屏及长时Doze仍待验。

2026-10-26：用户选择沿原 SSH 父 task 实施 R8 切片。本文为实施合同，实际结果另记
[verification](verification.md)，完成前不据此声称实机通过。

2026-10-27：[指定Android11板真实锁屏补验](verification.md#20261027-ssh-physical-lock)通过，
含15秒熄屏PTY及正常系统解锁后库仍锁/SSH继续；手机/API36/深度Doze仍未由此验证。

2026-10-28：[同板通知通道关闭](verification.md#20261028-ssh-board-channel-denied)通过后台锁库、
恢复及真实Flutter应用内停止。首次FGS恢复通道的一轮失败保留；首次展示后正常Settings
再次关闭才通过。Android13+运行时权限拒绝仍待较新系统验证。

## 行为与所有权

- 仅用户前台连接启动独立、非导出的 SSH 前台服务，服务拥有唯一串行 executor 和原生
  会话。导航、普通Home后台、Activity重建不主动关闭已建立的SSH；收到真实锁屏事件时
  取消待连接/已建立SSH，立即禁止旧代次输入输出，再由原执行器完成关闭。系统杀进程、网络
  失败或远端退出仍结束连接；不自动重连、重放输入或开机恢复。
  服务仅在本次会话运行/清理时持有自身 PARTIAL_WAKE_LOCK，完成释放；不更改电池优化、
  Wi-Fi、VPN 或系统电源策略。
- 锁屏关闭显示“已因锁屏断开”和“重新连接”；只保留RAM中的端点、认证模式及选择ID/
  别名，Activity重建可从关闭快照恢复。用户点击后打开现有受保护连接流程，重新读取当前
  密钥/config、生成新binding并执行信任/认证；必要的解锁、密码输入或主机确认仍须完成。
  私钥/config重连可在用户这次明确点击及正常解锁/资料核对后发起一次连接；密码仍提示输入。
  唤醒、恢复前台、失败后重新加载都不会自动连接。不复用旧票据/请求或密码，不重放历史命令。
  原选择已删或目标不匹配时要求
  重新选择；同ID资料更新仍读取当前元数据并重新校验，不声称检测全部内容变更。
- 整个UID被冻结期间应用无法执行关闭代码，不承诺远端命令在锁屏瞬间停止。当前实际
  锁定检查和排队的锁屏事件都必须先使精确旧owner失效，之后才能重新使用UI；若历史事件
  完全丢失且系统已解锁，不能仅凭当前Keyguard状态证明此前锁屏。不得用关闭所有Home
  返回会话或修改系统策略来伪造该证明。服务接收器绑定原owner，迟到事件不能结束新连接。
- 独立低重要性通知频道，只显示 SSH 状态，不显示账号、地址、命令、输出或秘密。
  提供返回终端及“停止”；Stop 绑定本次随机会话标识，旧通知不能停止后来连接。
  START_NOT_STICKY；通知/空 Intent 不能创建连接。
- App/Root 与 SSH 单活动终端互斥；清理未确认拒绝新连接。写入、resize、Stop 继续
  检查 generation。Activity 只订阅事件，销毁解除订阅；App/Root 沿原生命周期。
- 新订阅恢复同一代次状态与最多 256KiB 内存输出，不落盘；原子 snapshot 先重置终端和
  UTF-8 decoder，避免重复追加。截断提示、旧代次隔离，不重放输入。
- 通知点击只导航终端。Android13+ 通知权限拒绝仍可连接，界面说明通知 Stop 不可见，
  保留应用内关闭；不反复请求或绕过系统设置。
  返回动作保留尚未完成的敏感路由及其回执，退出该路由后显示终端；不强制丢弃操作。

## 已认证连接与秘密库

库仍按既有后台超时、锁屏、手动锁定及 Activity 关闭规则锁定，不延长解锁时间。
锁屏同时主动结束SSH；其他库锁定不会为已有会话提供读取秘密或重新认证的权限。
新连接、审批和秘密读取仍需要原准入。只有实际认证 SUCCESS 后，经原 owner 建立精确
native/callback/session 绑定的运行权限，才允许现有 shell 独立继续。
建立时释放/擦除当次密码、private/config/known_hosts 明文视图及认证来源；终端只保留
协议运行所需资源与精确身份/取消能力。PID/UID、owner、claim、取消/关闭校验保留。
锁库后不能读取材料或再次调用认证能力；认证前失效仍拒绝。本轮不增加 rekey、跳板、
转发或新认证方式。

## 平台与交互

用户主动交互 SSH 使用 specialUse 前台服务类型及具体 subtype，依据
[Android 服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)。
通知权限与服务启动分开，依据
[通知权限](https://developer.android.com/develop/ui/compose/notifications/notification-permission)。
不要求 Root、电池豁免或系统配置修改，不承诺 ROM 强杀后的保活。

已用项目 UI/UX Pro Max Flutter/UX 检索：复用系统通知与既有终端，等待/停止显示明确
状态，不增加装饰动画或第二套输入。小屏、大字体、返回终端和应用内关闭需回归。

## 验证门槛

JVM/原生覆盖认证前失效、认证后锁库 shell 可继续且材料不可读、取消/关闭重试；服务
覆盖精确 Stop、失败释放、重建订阅、输出上限及无重连；Flutter 覆盖 snapshot、旧代次
及通知导航。运行 analyze、Flutter/JVM、对应原生矩阵、Android lint、APK 构建及独立
Trellis check。实机按本轮目标授权分别测 Home、超时锁库、锁屏、重建、通知返回/Stop、
远端断开及 App/Root 互斥。未执行项保持待验，不据本地检查标 AC6 完成。

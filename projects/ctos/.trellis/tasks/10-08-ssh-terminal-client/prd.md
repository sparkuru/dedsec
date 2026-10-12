# OpenSSH 终端与统一秘密库接入

状态 planning / 已确定范围整理完成，待共同最终审阅；未实现或实测。

## Goal

交付 APK 内 OpenSSH 终端、config 主机一键连接与统一秘密库 SSH adapter，对应 [父任务](../10-08-ssh-terminal/prd.md) R1–R10 / AC1–AC9；公共秘密能力归先行底座。

## Requirements

- 客户端随 APK、无 Root 可用，复用终端/xterm/输入和输出能力；SSH 输入/控制键及 ↑/↓ 交远端，无程序专属模式或兼容清单，App/Root 终端不回退。
- Include/单多跳 ProxyJump 及主要 config 行为按父 R6 实现；LocalForward/固定目标 RemoteForward/DynamicForward、默认 loopback/显式对外监听先确认、拒绝不静默降级全部引用父契约。
- 用户通过 SAF 主动导入 config/Include/密钥至公共加密库的受管结构，不持续同步；完整备份含文件/关联凭据/信任，引用/路径/身份及信任预览校验后同代次提交，失败保护旧状态。
- native 使用解锁后的受管临时内存文件视图，不建长期或临时普通磁盘明文副本；文件读取/路径引用/各跳及信任写回以探针验证，不满足回规划。
- 拒绝配置本地命令并定位，无放行开关，保留受管 ProxyJump；未知主机确认/变化指纹拦截，根配置/Include/每跳不允许绕过。
- 登录密码/私钥口令默认不保存，用户可主动选择保存或仅当次使用；SSH adapter 绑定目标/账号/私钥身份和当前库会话/连接用途，各跳独立核验主机/远端认证，不实现另一套 KDF/秘密库/设备包装。
- 共用公共初始化/解锁/秘密库设置/备份、后台保留秒数、改主口令和关闭快捷的本次口令验证、敏感页面保护（BASE-01..15）。库锁定拒绝新秘密操作，已建立 SSH 按父 R8 的前台服务/后台保留/手动 Stop 契约继续。
- 范围/资源/完整验收由父 PRD/design 拥有，此 task 保持同一版本，不重新选择产品行为。

## Acceptance Criteria

父 AC1–AC9 完整作为此 task 的验收；此外集成验证 BASE-01..15：正确/取消/失效/迟到请求、有效会话复用/锁库、新旧库代次、源口令/目标口令完整恢复失败保护、文件身份/引用/信任、关闭快捷保留数据、改口令旧备份语义、共同设置一致及导航不停止 SSH。设备/端点需另获当前授权，未测不标通过。

## Dependencies and Status

先完成并审阅 [统一秘密库/公共认证](../10-08-app-authentication/prd.md) 的公共合同和适用独立关卡，再按任务授权开始 SSH 集成。生产 OpenSSH 语义/信任、Android native 实际使用、混合包跨设备恢复后可连接等共同 AC 在接入后联合验收，不要求尚不存在的 SSH 先通过才允许实现它。接口冻结前可协调 native 内存文件访问/Include/ProxyJump 探针；探针不等于业务完成或独立关卡通过。TOTP 是同一基座的独立业务消费者，不是 SSH 的依赖。

HOTP 特化全部撤销，SSH 已确定 R1–R10 范围保留。产品范围/依赖已收敛，版本/许可证/ABI/平台/native 行为仍需技术关卡验证；失败回规划，不静默改路线。设计入口：[SSH 设计](../10-08-ssh-terminal/design.md)、[公共秘密契约](../../../design/app-authentication.md)，实施入口：[implement.md](implement.md)。当前仅规划，不运行 task.py start。

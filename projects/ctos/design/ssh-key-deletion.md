# SSH 私钥删除

2026-11-02更新：[配置先导入、连接时选key](ssh-config-identity-selection.md)取代下方基于
IdentityFile缺失禁用用户配置的旧产品规则。删除后该keyId／口令／Account仍精确失效，
Host连接项保留；其他key只有用户重新明确选择才可使用，不静默回退。CONFIG正文不变，
旧可证明STATIC引用边与新增hint结果统一校验，生成manual及generic检查器保持严格。

2026-10-30，沿原 `10-08-ssh-terminal` 父任务接续。用户发现缺少删除入口并要求继续。
本合同范围已实现；799 JVM／317 Flutter及指定板隔离链路42.572秒通过，
具体范围以 [验证记录](verification.md#20261030-ssh-key-deletion) 为准。随后按用户允许，
正式0e8e已保留数据安装并启动；[安装记录](verification.md#20261030-ssh-key-delete-formal-install)，待人工体验。
其后按用户视觉反馈仅调整卡片操作区，正式fc98已同样保留数据更新并启动，
[最新视觉及安装](verification.md#20261030-ssh-key-card-actions)。

## 用户操作与影响

卡片操作按内容宽度紧凑排列并在小屏／大字时换行，使用与忘记／删除统一12圆角、
48dp触控尺寸及明确间距，避免整行操作和胶囊悬停背景占满卡片。此为用户截图反馈后的
视觉修正，仍沿现有ctOS主题；确认及库内删除行为保持。

私钥卡片提供「删除私钥」，与「忘记口令」区分。宿主先生成仅含公开资料的精确预览，
显示私钥路径、指纹、配对公钥路径、是否保存口令、将删除的 KEY 连接关联数量，以及
受影响的用户 CONFIG 路径。确认后单次原子删除库内私钥、精确配对公钥、KeyBinding、
该 binding 的口令条目和全部依赖 KEY Accounts。保留其他身份、密码账号、TOTP、主机
信任和外部原文件。删除不是远端 authorized_keys 管理或既有 shell 撤销。

用户 CONFIG 内容字节保持，引用元数据仅移除被删除对象。受管生成的 `.ssh/ctos-manual`
仅在属于所删身份且没有保留 CONFIG 引用它时一起删除；否则保留内容并修正引用元数据。
受影响 CONFIG 路径包含直接引用私钥或配对公钥的文件（包括未激活分支／Include 文件），
不把生成文件混同用户文件；是否移除生成文件单独展示。

## 边界接口

- `ssh.keyDeletePreview(requestId,keyId)` → `ticketId,binding,keyId,label,path,algorithm,
  fingerprint,publicPath,hasSavedPassphrase,accountCount,configPaths,removesManualConfig`。
  `accountCount` 上限 1024，唯一受管 `configPaths` 上限 128；其他字段沿用 library 公共约束。
- `ssh.keyDeleteCommit(requestId,ticketId,binding)` → `{deleted:true}`。
- `ssh.keyDeleteCancel(ticketId)` → `{cancelled:bool}`。
- library 的 host 行增加 `missingIdentityPaths`（唯一受管路径数组，上限 128，默认空）；
  任一实际有效 IdentityFile 不对应当前受管身份时，行错误为 `SSH_KEY_MISSING` 且不可选。

前端只回传宿主票据及 binding，不把影响清单当作写权限。票据绑定原 Request、源快照、
epoch、revision、validator 注册、原期限、精确 proposal 和一次 effect claim。与现有
import 共用串行互斥、取消、过期、锁库和后台清理；接口不得跨种类消费票据。
写入后交付失效仍走既有不确定写入与锁库规则。Flutter 等待终态／确认结果后才退出保护窗。

## 保留配置与重新导入

生产存储校验仅为 `IdentityFile(kind=1)` 的 `DEFERRED_KEY_RESOLUTION(state=5)`
允许缺失静态身份且不生成不存在的引用边。这代表可保存的不完整配置，不证明历史删除，
不授权连接。导入／编辑 CONFIG 仍严格拒绝缺失依赖；其他 kind/state、缺失 Include／
known_hosts、动态／CWD／证书和错误现存类型维持拒绝。原生 parser 和 wire 不另建分支。

library 和两条 key connector 准备路径均检查实际 native DirectPlan 的所有有效身份路径。
任何缺失均在保存口令、TCP 和秘密释放之前拒绝，不以另一把可用密钥静默回退。
重新导入身份时在同一原子 proposal 内，以同一原预算下的实际 aggregate native audit
刷新全部 CONFIG 引用元数据，保持内容字节，不恢复旧口令或旧 Accounts。

删除撤销旧库选择／未完成认证能力；已经认证并退休秘密所有权的服务 shell 沿用现有规则。
后续重连重新读取当前库。不得自动停止无关会话、自动重连或重放命令。

## 验证要求

验证精确清理与其他资料保留、预览取消／过期／重放／替换／跨票据拒绝、原预算及不确定
写入、CONFIG／Include 内容不变、A+B 缺失 A 时 B 不回退、生成文件的两种引用情形、
同路径重新导入修复，以及严格导入／其他未决类型拒绝。实际 native/JNI、Flutter 窄屏和
放大字体展示、设备隔离链路与正式安装分别记录，不以旧增量通过代替本轮验证。

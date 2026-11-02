# SSH配置连接项与显式私钥选择

2026-11-02，沿原10-08父任务。用户导入Download/config.bin时收到`.ssh/config:5`路径
错误；只读诊断确认6个Host条目、6处带引号的电脑绝对IdentityFile引用、两种私钥路径，
没有BOM／CRLF／NUL。原始文件未修改，本地临时副本已清理，证据只保留脱敏结构。
用户明确批准“先导入连接项，连接时选择私钥”，本页取代原导入／连接必须依赖IdentityFile
受管路径的产品约定；不恢复目录功能或引入路径迁移。

本地实现／检查及独立复核已通过：324 Flutter／802 JVM、原生资源／direct及枚举、
16张实际字体布局、构建lint和三APK审计。正式eed028f0…已保留数据更新指定开发板并
启动；用户已确认效果通过且实际remote连接成功，新平台合成方法仍仅编译，见
[实际结果](verification.md#20261102-ssh-config-identity-selection)。

## 1. 范围与结果

- config先独立导入／编辑，保留正文；具体Host按实际native语义列出有效目标。
- 原IdentityFile仅为受保护的路径提示，不读取其电脑路径，不按文件名匹配或自动改写。
- 用户点选Host后明确选择本次已导入私钥，即便只有一把也不自动选择；无私钥时保留
  连接项并提示先导入。默认不新增持久的Host→私钥偏好。
- 既有普通／加密私钥、当次／已存口令、真实主机核验、PTY与后台／锁屏契约保留。
  PubkeyAuthentication no和其他未支持／受限选项继续明确拒绝，不降级密码连接。

## 2. 接口与类型

宿主增加明确的产品IdentityFile-hint原生审计／direct-plan入口；原generic resource／
direct接口与v1 wire仍严格。新结果必须与受管identityFiles类型／wire明确区分，不能把
电脑路径塞进VaultManifest.path或放宽全局路径校验。实际prepare／getter／wire签名由
本轮实现固定并在spec记录；所有旧getter不得把新提示误解为可读文件授权。

公开`SshConfigHost`保留alias／binding／host／port／username／keyIds，新增可选
`identityHints: List<String>`（缺省空）。每行≤256条、每条≤512 UTF-8字节，禁止控制字符，
不静默截断；整次library公开提示文本≤1MiB、提示总行≤4096，超限明确拒绝。
keyIds列出当前库内可用私钥ID，按真实key metadata校验；无密钥不使配置导入失败或隐藏
目标信息。真正配置错误继续用error；不再以电脑IdentityFile缺失产生SSH_KEY_MISSING。

`ssh.configConnect`继续传原alias／binding／keyId／username／尺寸及可选口令字段；
不接受前端permissive标志、可写路径或替换配置。host-owned代码决定配置身份提示政策。

## 3. 原生、图与认证边界

- 两个产品入口只改变IdentityFile的凭据权威。共享parser保持参数个数、引号、Host／
  Match／首值优先／累加与原预算；活动IdentityFile原始参数投影为提示，不展开或打开
  外部路径。默认值没有真实来源，不能伪称原文件中的显式声明。
- 新资源结果可为IdentityFile增加target=0的非权威hint状态；其他kind不能使用该状态。
  仍可证明的已有STATIC身份边保留原语义，避免新validator拒绝旧库；不可解析／不可用
  IdentityFile不伪造图target，不加入外部references。非身份资源和Include仍严格。
  hint是已完成分类的非资源提示，非待解决的文件依赖；hasDeferred／resourcesIncomplete
  仍只表示旧state2／3／5，profile政策及state6／target0表达非权威，报告仍不授予准入。
- import／edit、stored源与候选validator、私钥新增／替换／删除后的引用刷新统一使用
  这个产品政策，保留原source／候选／once／cancel／uncertain，不跳过整图关系检查。
  `.ssh/ctos-manual`生成路径与legacy password模型继续通过原严格约束。
- library投影、prepareKeyPhrase（保存口令前）、prepareKey（TCP前）一致消费新计划。
  两处均验证当前keyId／身份、configBinding／epoch／revision、pubkey开关及真实目标／
  信任配置；移除“所选key必须在IdentityFile路径列表中”的产品限制。
- 选择只从受管KeyBinding取真实身份，沿现有Account ownership、CSAS／实际host／KEX／
  typed私钥／口令最终交付。提示不授权读文件或释放秘密；实际失败不试其他key／密码。
- 既有主动重连可复用RAM中公开keyId提示，仍须按当前库／配置重新核对并通过用户明确
  连接动作；不新增长期默认key或自动重连，不保留旧秘密／授权。

## 4. 错误与恢复

| 场景 | 行为 |
| --- | --- |
| config有电脑／相对／动态IdentityFile，尚无key | 可原样保存并列出目标／提示，连接时要求选择受管key |
| key已导入但路径与提示不同 | 用户明确选择后使用该真实key，不改CONFIG |
| key缺失／替换、配置binding变化、取消／锁库／过期 | 口令保存和TCP前拒绝，需重新选择 |
| PubkeyAuthentication no、未支持会话／命令／跳板／转发 | 保持原明确拒绝，不绕过 |
| 缺失／错误类型Include或信任文件、弱主机核验 | 原严格失败，不用hint政策放行 |
| 提示字符串／总量超限或无效wire／模式错配 | 完整拒绝，不截断／伪造已验证路径 |

## 5. 示例

一个config包含多个Host和`IdentityFile "/home/source/.ssh/id_rsa"`，即便库中无私钥也
可先导入。稍后用户选择已导入的`.ssh/phone-key`，用其真实身份连接对应Host；提示与
CONFIG正文保持原样。另一个Host可明确选择另一把key，无默认猜测或相互串用。

## 6. 验证

真实native覆盖产品hint与旧严格模式分离、带引号外部／相对／动态／none／重复路径、
多Host目标与提示、无读外部文件／网络／执行效果，及Include／信任／不安全选项反例。
JVM覆盖新wire／模式拒绝、旧图STATIC边和config-first、随后key增删／刷新、两个连接
准备阶段及最终选择；Flutter覆盖无key列表、一个／多个key均明确选择、取消／后台／
迟到、原路径提示与窄屏大字。合成key-only实际登录用于证明选择独立于原路径，不能访问
用户真实key／账号。构建、Android JNI、SAF、人工反馈各按实际执行单列；不再建立逐版本门槛。

本轮实现／检查按现有hako与native runner执行；具体命令、产物和运行边界写入
[验证记录](verification.md#20261102-ssh-config-identity-selection)，不将源码研究记为实测。

## 7. 禁止误用

错误：只在Java忽略异常，或把所有resource target校验关掉；把“hint”当作受管路径。
正确：新增受限产品parser／typed结果，只分离IdentityFile提示与所选key权威，其他原生
政策、旧图兼容和秘密释放证明继续执行。错误：导入时重写用户电脑路径或静默默认选第一把key。
正确：原样保存配置，用户每次明确选择实际key后连接。

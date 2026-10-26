# SSH 清单正式主线

2026-10-25 用户更正：SSH 尚未正常使用，不维护两套实现，最新模型直接作为正式主线。
本合同替代此前“默认旧格式、显式新格式”的安排。工作仍限本地源码与验证，不操作设备、
真实库或账号，不安装/提交/归档/发布。目标是完成实际可用的连接流程。

## 唯一模型与写入路径

- `VaultManifest(entries, files, keyBindings, accounts)` 是正式模型；两列表便利构造仅提供
  空的关系/账号列表，empty/create及所有candidate均使用同一模型，不再选择版本。
- 删除instance `formatVersion`、`version2` factory及按它们分支的writer/validation。
  `withObjects`保留immutable metadata并重验引用，不再保留格式变体。
- writer只输出最新布局，内部wire tag为2；这个数字仅防止误解码，不提供产品版本/模式。
  entries/files后始终有keyBindings/accounts列表，空列表也编码其count。
- CTOSVLT1 envelope、KDF/AAD/crypto/generation及12MiB cap不改；native/JNI wire协议
  与APK签名中的数字属于各自协议，不因清理SSH主线而修改。

## 正式数据结构

保留已实现的不可变 `VaultSshMetadata`：

`KeyBinding(id, privateFileId, publicFileIdOrNull, certificateFileIds, passphraseEntryIdOrNull)`。
精确ID/type引用PRIVATE_KEY、可选PUBLIC_KEY、有序唯一CERTIFICATE列表与可选
SSH_KEY_PASSPHRASE；phrase.identity必须等于`key:`+private.identity。每private及每被
选phrase至多一个binding owner；public/cert允许显式共享。允许private-only及暂未绑定
对象，不推断同名关系；完整coverage/数学证明后续统一补齐。

`Account(id, targetIdentity, host, port, username, AuthKind.PASSWORD|KEY, credentialId)`。
PASSWORD引用SSH_PASSWORD且identity精确等于targetIdentity，KEY引用binding。全局ID
跨entries/files/bindings/accounts唯一；account原始target唯一，不按endpoint tuple合并。
IDs ASCII `[A-Za-z0-9_-]{1,128}`；target沿原opaque `target:` suffix规则，不解析或修复；
host沿现有ASCII role字符谓词，不宣称DNS/IP有效；port1..65535；username非空strict
UTF8<=255bytes、无ISO control/非法surrogate，保留空格/Unicode。复用VaultCodec.sshText
并保留Selection原错误码/message。所有其他既有entry/file文本校验保持原行为。

bindings<=128/accounts<=1024，entries1024/files128、单file1MiB/总file8MiB/深度8、
整体12MiB等原上限保留。新metadata构造错误固定VaultException.invalid，不读secret正文
做结构推断，不调用网络/native认证。字段声明不是实际账号/材料数学/信任批准。

## 唯一布局

big-endian u32 tag2；原entries/files字段顺序不变；随后：

1. u32 bindingCount；每项text id/privateFileId/publicOptional/phraseOptional，u32 certCount及
   顺序text certIDs。optional空text表示null，API使用null，空ID非法。
2. u32 accountCount；每项text id/targetIdentity/host，u32 port，text username，u8 auth
   （PASSWORD1/KEY2），text credentialId。

text沿现有u32 byteLength+strict解码UTF8；未知tag/enum、超界/负count、错type/悬空/
矛盾引用、重复owner/target/全局ID、截断/尾随字节均拒绝。新增metadata严格UTF8，既有
entry/file字段校验不顺带重写。失败decode擦除临时owned arrays，encode擦临时buffer/
副本，不能擦除caller/source数组。四生产candidate保留既有独占clone/cleanup生命周期。

## 已交付TOTP的输入适配

TOTP已交付且与SSH共用加密库，保留窄的只读旧输入适配：旧wire tag1仅接受empty或纯
TOTP（无files、无非TOTP条目），解码立即得到同一正式模型；旧SSH输入明确拒绝。
没有旧模型、writer、迁移按钮、版本选项或旧SSH主线，不猜测关系或清空数据。

认证后原始plaintext/envelope仍由Verified独占保存，原cipher/epoch/preview绑定不改。
只读unlock/list/code/quickUnlock不写库；export返回原envelope作为精确备份快照。
首次正常内容修改、换口令、恢复提交都编码正式模型，语义内容保留且仍走原原子guard；
quick wrapper仅更新既有cipher包装时不强制重写正文。

`requireUnchangedLoan`保留快路径raw equality；确需适配时从authenticated原文独立decode/
encode得到expected current bytes，再与loan编码比较，临时副本finally擦除。不能用loan
自己生成expected，也不能把原Verified.plaintext替换成canonical内容而伪装原认证来源。
换口令/import单独拥有并擦除新的encoded正文，readback核对实际写入的该正文。
import从原authenticated plaintext独立decode/encode，不能因保留的preview manifest可变
数组而把未审阅内容重新写入；原source/cipher绑定与提交前复查保持。

## 恢复按实际内容审阅

删除按format拒绝的helper与调用。旧review覆盖FILE/CREDENTIAL/TRUST，尚不展示关系/
账号，因此用 `requireReviewedMetadata` 在每侧认证decode后、该侧validator前检查实际
keyBindings/accounts非空时拒绝 `VAULT_SSH_REVIEW_REQUIRED`，bind重复防御。
empty/纯TOTP及没有这些metadata的当前数据不因内部tag而拒绝；原SSH validator/reviewer
注册和内容门继续生效，生产不挂不完整provider。source-first错误序、取消/旧preview/
cleanup/唯一effect/uncertain保持。后续graph-aware审阅补在这一正式主线上。

## 验证与推进

将两份刚新增的versioned测试改名为 `VaultManifestTest` / `VaultManifestLifecycleTest`。
保留独立当前golden/严格畸形/limits/immutable/引用/四实际mutation/loans与擦除证据；删除
双主线默认与空格式分支假设，验证所有构造/create/write均正式布局、恢复仅按内容门判断。
单独用原始legacy TOTP向量验证读取/快解锁/导出无写入、正常mutation/换口令/restore按
正式编码保留seed/字段、wrongpassword/cancel/失败保持旧库、旧SSH输入拒绝、independent
loan expected可检测篡改。主会话串行JVM/lint/normal/fixture与APK检查，独立check复核。

此后不再围绕版本拆增量。继续完成同一主线的整库关系/实际账号Request消费、证书信任
及恢复审阅，最终以可实际连接/使用的SSH功能验收，不把单项测试数量当用户可用性。

# SSH 独立配置输入

日期：2026-10-23。原 SSH 客户端任务的基础增量，已完成本地实现及独立复核。
前置为[完整语义义务](ssh-validation-lifecycle.md)、[有效求值器](ssh-config-evaluator.md)
和[声明目录](ssh-config-catalog.md)。实际结果见[验证](verification.md#20261023-detached-config)。

## 范围与保证

给固定 readconf 提供来自实际 source/target/candidate 的独立文件输入，不读取当前库，
不构造 vault UUID/epoch/request/connection，不申请 role、public lease 或 view token。
本轮仅 native C API；Java/JNI 整库编排、完整语义 provider、恢复和 connector 仍后续。
声明扫描仍仅声明，实际求值仍需真实 Request，Summary/raw Options 仍不是连接计划。

输入保留完整文件次序、类型和路径，诊断 object 为该次序的 1-based ordinal；CONFIG
正文独立复制，非 CONFIG 只接受 metadata，不携带私钥/凭据正文。root 显式选择真实
CONFIG 对象，不要求名为 ssh/config，也不把孤立配置当作不存在。后续整库编排须逐个
CONFIG 检查，不能仅检查单 root 的可达部分。本轮不验证声明 references 与实际 Include
边的对应；不存在/错类型/越界/循环仍拒绝，但同输入中的路径存在不等于已证明引用语义。
选 root 不改变既有 Include 的虚拟 SSH 目录基准，不将其改为所选 root 的父目录。

## 生命周期与共享解析器

- 独立源拥有复制的路径和 CONFIG 正文，调用后 caller 可擦除/释放输入。容量沿用
  128 对象、1MiB 单文件、8MiB 总正文、512-byte path；路径重复/文件目录冲突拒绝。
- 入场使用原始 CLOCK_BOOTTIME 绝对期限，必须未来且至多 60 秒；复制、run、getter、
  IO、cleanup 后结果检查共用原期限。cancel 是单向 atomic，可跨线程；其余调用绑定
  原 pthread/PID/UID。不承诺抢占 libc 单次操作。
- 复用既有 parser trylock/current、词法/求值/Include/glob/无外部效果政策及全部预算。
  IO 通过文件集合与 liveness 适配器访问输入；旧 live 构造仍使用原 borrow_live，成功
  后仅释放原 borrow，不撤销 caller view；不得减弱原撤销或重试规则。
- CONFIG 数据只进入锁页、DONTDUMP 的独占区域；建立清理所有权后才复制。close 擦除
  正文与元数据，资源释放失败保留可重试对象，destroy 仅在 close 完成后允许。
  构造中获取资源后的失败也须明确交还可清理 owner，不能丢失映射。
- 8MiB 是格式正文上限，锁页还需源header/路径和parser reader空间；公开指针索引按
  实际数量独立分配，不再锁定最大128项固定表。系统锁页额度不足
  必须返回资源错误并清理，不降级为未锁页存储。整库多 root 的总工作预算仍由后续
  编排合同统一限定，不能把每次扫描上限乘以文件数后当作已有整库资源保证。
- getter 在结果复制前复查原生命周期；返回的历史 metadata 不授予后续连接权限。

## 验证与后续

真实 pinned parser 正反例覆盖不同 supplied source、调用后原数组变更、非默认 root、
孤立 root、静态/动态 Include、原始 ordinal、错误类型/路径/重复/容量、真实 Request
求值、取消/错线程/期限/parser busy、cleanup 重试和禁止效果计数。重跑既有普通求值、
catalog、handle 和受影响 IO 回归；主会话串行 strict 构建、ARM QEMU、JVM/lint/APK。
独立 checker 复核源差异和实际证据。无设备、真实账号、提交/归档/发布。

该增量不能注册 SshContentValidator。之后仍须实现整库 CONFIG/Include 引用和全分支政策、
身份/私公钥/证书数学关联、凭据目标、信任及真实 Request 的逐字段执行约束；这些义务
与审阅通过前保持 SSH 恢复拒绝门和父任务未完成状态。

## 本地实现结果

`ssh_file_io.[ch]` 已提供内部文件集合/liveness 适配；`ssh_config_eval.[ch]` 新增
`ctos_ssh_config_eval_open_detached` 和 `ctos_ssh_config_eval_open_catalog_detached`。
输入为 `ctos_ssh_config_file {type,path,body}` 数组、count、零起始 root 和原 deadline；
普通求值另传真实 Request。返回 -1 通常 out 为空，仅清理未完成时保留非空 CLOSING
owner；caller 必须重试 close，成功后 destroy，不能把该 owner 当可运行结果。

专项真实 ARM1261/effects0/capabilities0、原求值3736/目录71923/句柄5201以及 host
文件IO426场景通过。最终 JVM483/28reports 零失败错误跳过；normal94任务全执行、
fixture111任务复用同一生产 native 输入，lint0errors3/4warnings，三APK签名/隔离通过。
不将这些结果外推为 Android/JNI 实机执行或完整 SSH 客户端验收。

宿主锁页额度仅8192KiB；实际跟踪证实8MiB正文加元数据的8405760-byte mlock返回
ENOMEM，随后清理。测试保留8MiB格式入场与故障清理断言，实际锁页/解析成功为6MiB
输入加1MiB reader；没有模拟锁页成功，也没有降低生产保护。完整失败轮和依赖缓存
重建过程均保存在验证记录；本轮不宣称8MiB全部锁页成功。

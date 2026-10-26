# SSH 受管配置求值器

后续已补[实际清单离线材料检查](ssh-manifest-inspection.md)，仅检查所给文件副本，
不替代本合同的配置语义/最终计划或全清单准入；[实际验证](verification.md#20261019-manifest-inspection)。

日期：2026-10-19。属于原`10-08-ssh-terminal-client`进行中任务。
本增量先交付生产native配置求值入口；不提供导入/catalog/JNI/UI或socket启动。
仍为本地未提交，不操作设备、真实秘密库或账号。

接续[JNI/Java边界](ssh-config-managed.md)已实现并过本地门；下方native结果
属于已验证前置，Android内JNI实际运行仍未执行。[声明catalog](ssh-config-catalog.md)
也已在同一lexer实现，并重跑普通求值/handle回归；不提供语义validator或最终连接计划。

## 输入、结果与权限

复用固定OpenSSH10.6p1的readconf，不另写SSH指令解析器。输入是已校验的公共文件
view borrow、明确alias和有界请求上下文；只读取同一受管快照内的CONFIG对象。
private-key对象无body且不得读取，config/known_hosts/密钥内容不通过新的通用getter。

求值结果拥有完整upstream Options和不可变请求副本，不能只保留HostName/User/Port
子集。摘要包含目标、用户名是否待补、端口、identity/forward数量及jump/session等
所需元数据；摘要与完整Options都不是连接能力。保留ProxyJump、forward、RemoteCommand、
RequestTTY和其余有效选项，后续connector必须逐项实现或明确拒绝，不得悄悄直连。
本增量不调用stock ssh/sshconnect，不签发role/proof，不使用凭据或建立监听/连接。

## 生命周期与线程

native API采用open→run一次→只读结果→close→destroy：

```c
ctos_ssh_config_eval_open(borrow, request, &evaluation);
ctos_ssh_config_eval_run(evaluation, &diagnostic);
ctos_ssh_config_eval_summary(evaluation, &summary);
ctos_ssh_config_eval_options(evaluation, &const_options); /* native internal only */
ctos_ssh_config_eval_cancel(evaluation);                 /* atomic only */
ctos_ssh_config_eval_close(evaluation);
ctos_ssh_config_eval_destroy(evaluation);
```

request中的remote_user是显式覆盖，remote_user_fallback仅供Match/%r上下文而不覆盖config
User；local_user/local_hostname/local_uid是明确的虚拟上下文。summary.has_raw_fields=1
表示完整Options仍含需后续按各字段上游规则展开/校验的路径、session与forward字段，
不能把该摘要当成最终runtime连接计划；const_options指针不得跨close或缓存绕过存活检查。

- open校验borrow、alias/上下文、原期限、owner/PID及分配后才转移borrow；失败仍由caller持有。
- run在同一owner线程同步执行；明确NEW/RUNNING/READY/FAILED/CLOSING/CLOSED状态，重复run拒绝。
- parser上下文隔离，使用非阻塞串行准入保护仍存在的上游静态cache；重入或并发调用报EBUSY，
  不更改全局HOME、passwd、环境、日志handler或既有session/callback锁。
- 唯一跨线程操作是原子cancel；每次文件/目录访问、每轮/行处理及最终发布复查cancel和borrow
  存活/原期限。getter仅READY且owner和原borrow仍有效时可用；无期限续期或撤销后复活。
- run失败保留对象和可能待清理reader；close逐阶段清理Options、reader、borrow，失败可重试，
  不丢失仍映射的内存/FD上下文；destroy只释放已关闭tombstone。

## 固定parser与无副作用边界

在独立build目录给固定readconf打项目patch，只编译parser与适配器；原G1输入不变，
原production libssh83成员及认证/KEX/PTY合同不改。新增parser对象独立链接进既有生产
libctos_ssh_files.so，不引入第二套OpenSSH/crypto或stock高层caller。

文件/目录、glob、用户/home/hostname、端口和展开辅助函数在parser translation unit
显式适配，不能对最终共享库使用影响既有session的全局--wrap。端口只按数字解析，
禁止getservbyname读取宿主services；ProxyJump的URI/helper传递调用也必须覆盖。
Include按原SSH目录相对基准、词法glob顺序、嵌套/条件及first-value语义求值，读取仅
使用现有ssh_file_io的memfd与受管目录枚举。先将~/正规化到虚拟home，再清除
GLOB_TILDE调用受管ALTDIRFUNC，避免compat glob先读取进程HOME；~其他用户、越界绝对
路径/../缺失引用和循环有定位地拒绝，不回退宿主文件。

保留上游首次/Match final重求值；HostName展开后再次求值使用解析后的host和原alias，
禁止DNS/canonicalization/接口探测。需要这些效果的有效声明明确报UNSUPPORTED。
Match exec即使在未命中分支也拒绝；所有本地命令、外部provider、主机核验绕过仍遵循
原政策。Match host/user/originalhost/all/final等无副作用条件沿用上游；其他条件若需
宿主效果则提前给出明确诊断。

展开只使用请求/受管上下文，畸形/未知%和环境变量不得进入会fatal或getenv的共享helper。
没有远端User时不从App UID/虚拟passwd推导远端用户名；需要未知远端用户名才能求值的
条件/展开必须要求调用方补全或给出明确诊断。保留用户显式配置，不用默认覆盖伪装兼容。

所有可由配置触发的fatal/exit改为带清理的返回值，包含数量超限、缺参数、RefuseConnection、
Include、token展开与传递helper；禁止setjmp/longjmp跨越分配。上游xmalloc的系统OOM约定
不等于已实现可恢复OOM，本增量须明确该剩余边界，且不能用该约定掩盖可预检的输入超限。
诊断返回稳定reason/object-index/line和errno，不输出配置行、命令、路径或秘密到日志。
完整Options释放需覆盖固定版本遗漏的字段和未激活/重复forward的临时分配。

## 有界输入与验收

沿用view最多128对象/单对象1MiB/总8MiB和file_io最多1024次open/64MiB读取；Include
深度最多8，检测当前递归栈循环；配置拒绝NUL/非法编码，行最多8192字节、两轮累计
最多16384行；沿用父设计的跳板最多8跳、普通转发（local/remote/dynamic合计）最多32条，
其他可变列表最多256项，不能在截断后声称成功。alias/host/user最多255字节；
上限在native头文件统一命名并测试边界。不读取私钥body或环境变量，不持久化明文。

验收使用真实固定parser而非mock：多Host alias/通配/否定、first-value、additive identity、
first/final条件、嵌套/通配/条件Include、词法顺序和SSH目录相对基准；明确缺失/循环/越界。
保留完整jump/forward/session选项；执行型/信任绕过/服务名端口/未知展开、各类旧fatal输入
返回失败，随后有效求值仍可成功。覆盖NUL/长行/数量与读取预算、取消/撤销/期限、错线程/
重入、失败close重试和反复求值无FD/reader泄漏；effect traps证明没有宿主file/DNS/socket/
exec/env效果。严格native构建、独立ARM合成执行、normal lint/Release及APK隔离审计分列；
不把native入口就绪当成AppSecrets validator、catalog、连接或Android运行通过。

## 状态

本native增量已实现；最终真实parser静态ARM合成测试通过3736次检查，effect计数为0，
正式源码复核无遗留问题。完整构建与工件状态见[本轮验证](verification.md#20261019-config-evaluator)。
源码研究见原task research/config-evaluator-20261019.md；启动器衔接见
[后续顺序](ssh-launcher.md#后续顺序)。JNI与声明catalog已在接续增量实现；语义validator、connector与Android运行仍后续。

实现中修复固定parser初始化/完整释放不一致（两个零长度数组的指针原为poison），
HostName/User与算法合并失败的first-assignment来源追踪，以及转义%%与实际%C的区分。
跳板URI解码后的host/user同样校验255字节与UTF-8，不以摘要截断代替拒绝。

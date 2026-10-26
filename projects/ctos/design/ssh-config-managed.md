# SSH配置求值 JNI 与Java边界

后续已补[实际清单离线材料检查](ssh-manifest-inspection.md)，仅检查所给文件副本，
不替代本合同的配置语义/最终计划或全清单准入；[实际验证](verification.md#20261019-manifest-inspection)。

接续[声明catalog](ssh-config-catalog.md)已扩展同一owner facade和registry；新增两JNI，
当前共八个config入口。下文六入口合同仍描述普通有效求值模式，不把catalog当Summary。

日期：2026-10-19；原SSH客户端task继续。本文记录冻结接口与实现，实际结果单独引用。
前置为已过本地门的[native求值器](ssh-config-evaluator.md)。本轮只接生产JNI/Java，
不接catalog/导入validator/connector/Flutter，不设备、真实库账号、提交、归档或发布。

## 权限与所有权

Java `SshConfigEvaluator`借用caller持有的`SshPublicFileLease`；不调用lease.close/revoke，
不创建role、认证selection或socket。完整Options留在native evaluation，不向Java传递
指针或Options内容。Java摘要是有界不可变元数据，始终hasRawFields=true，不是连接计划。
后续native connector仍须通过guarded getter逐字段完成或拒绝raw行为。

返回后的Summary只是当次求值的历史元数据；未来UI/catalog/connector在消费时仍须复查
原evaluation/lease，不得把已复制摘要缓存当作仍存活的授权。

生产与测试seam分开：生产接受真实lease；package-private ViewBinding只提供token/active，
无close能力。NativeBinding用于宿主JVM测试，不代表真实JNI运行。所有操作除cancel外
只允许创建线程；原view的PID/UID/期限/撤销有效，不从App UID推导远端用户名。

## Native registry与失败资源

新增独立可测试`ssh_config_handle.[ch]`和薄`ssh_config_jni.c`，最多64槽，正signed64
递增opaque token，不能把指针cast成长整型。销毁后旧token无效，不复用token值。
owner operation在短registry锁下标记busy/pin，释放锁后执行parser/IO，再解除pin。
cancel在短锁下找到live evaluation并只做atomic cancel，不受run持锁阻塞；destroy拒绝busy，
不能在取消使用指针期间释放。PID/UID在跨线程cancel也校验。无Java callback或全局session锁。

open先完成请求复制/校验及槽预留，再borrow。borrow前失败返回0/IOException并释放槽；
borrow后若eval_open失败，仍发布正token，保存pendingBorrow/open_errno供run诊断和close
回收。正token只表示拥有一次求值尝试，不能作为READY。Java wrapper在native open前
预分配，得到正token后始终保留对象，不能因lease随后撤销或初始native失败而丢弃句柄。
close释放evaluation或pendingBorrow，失败保留资源重试；成功后destroy才清槽。
仅释放自己的borrow，不撤销整个caller view。失败run后仍可close；getter不能复活撤销结果。

## JNI签名与wire

`SshConfigEvaluator.Native`提供六个入口：

```java
static native long open(long view, byte[] request) throws IOException;
static native int[] run(long handle) throws IOException;
static native byte[] summary(long handle) throws IOException;
static native void cancel(long handle) throws IOException;
static native void close(long handle) throws IOException;
static native void destroy(long handle) throws IOException;
```

使用标准UTF-8 bytes，禁止JNI modified-UTF8或NewStringUTF处理配置值。全部多字节整数
big-endian；文本以u32字节长度+bytes表示，无NUL/坏UTF8/ISO控制字符（含U+0080..009F），
严格总长、无尾随。原parser对配置内容的语义不因本wire改写；摘要解码遇不支持字符明确拒绝。

- request `CSEQ`(0x43534551),u32版本1,u32 port(0或1..65535),u32 local_uid，依次七个
  text：alias、remote_user、remote_user_fallback、local_user、local_hostname、tag、
  remote_command。前六个各<=255字节，command<=8192；总<=9766。alias/local_user/
  local_hostname非空，其余空表示无值。Java拒绝未配对surrogate，不用替换字符修补。
- run精确int[5]：status(0成功/1配置错误/2native生命周期或准入错误),reason,object,line,
  errno。配置reason沿CTOS_CONFIG枚举，object为1-based/0无来源；status2保留reason0和
  原errno，不把重复run/EBUSY/owner错误伪造为INPUT。无配置行/路径/命令写入错误消息。
  object<=128，line<=16385：行预算拒绝的是第16385行，不能在Java丢掉该合法LIMIT诊断。
- summary `CSES`(0x43534553),u32版本1；依次11个32位值：user_required,port,identities,
  certificates,local_forwards,remote_forwards,has_jump,has_remote_command,request_tty,
  session_type,has_raw_fields；最后alias/hostname/user三个text，总<=829。TTY/session按
  原signed32值保留，校验固定版本有效TTY0..3/session0..2，不在本轮解释为执行行为。
  flags仅0/1、identity/cert<=256、combined forward<=32；user_required与空user一致，
  has_raw_fields必须为1。结果只取guarded summary，不暴露config body。

JNI立即检查每次可能抛异常的分配/array访问，保持原pending exception；native操作前
预分配run结果容器。摘要序列化及JNI分配/写回后再次存活检查，失败不返回已填对象。
临时request/summary bytes清零，JNI不接收或返回C指针。普通library新增六个config JNI
export，原19个session JNI不变，不引入fixture、stock caller或第二套crypto。

## Java状态与诊断

Request与Summary不可变；一次evaluate/run，重复调用明确拒绝；每次summary均经过native
getter，不用缓存绕过期限/撤销。run的配置错误转为携带不可变Diagnostic的IOException子类，
消息为固定无敏感内容；native lifecycle error同样保留status/errno，可与配置错误区分。
Request校验/编码在native资源获取前；native IO/Linkage失败转换为固定错误，不记录输入。
run/getter不持阻塞cancel的Java monitor。cancel跨线程可用，标记本地取消并调用native；
错线程run/summary/close拒绝。close分native close、destroy两阶段，任何失败保留可重试状态。
抛Error时finally清除in-flight/reentry状态，保留caller可关闭的对象，不静默吞掉Error。

## 验收与当前状态

实现已完成：strict native/JNI、20项focused JVM、真实静态ARM5201次检查/effects0通过，
normal95项全重跑、fresh JVM308/18reports/零失败通过。完整工件和最终fixture结果见
[本轮验证](verification.md#20261019-config-jni)。Android实际JNI运行未执行。

fixture114项及三APK签名/隔离审计通过；固定最终编译输入后normal再打包77项通过，
normal/fixture内生产库字节一致，最终静态ARM5201/effects0再次通过。首次构建、固定输入
打包和Android未运行分别记录，最终APK为284e9566…，不是最初all-rerun工件3f3de185…。

验收覆盖宿主JVM的UTF8/wire/typed诊断、非法帧、
owner/reentry/cancel与两阶段清理测试；真实native registry/borrow/parser的正常、旧token、
容量、撤销、跨线程取消、失败open/pendingBorrow与close重试测试。JNI严格编译、normal
JVM/lint/Release、独立fixture编译/APK隔离审计分别记录；有条件用host ARM QEMU验证
真实C路径，不把mock或仅编译当作Android/JNI执行。原native3736/effects0基线不改语义。

主代理负责串行构建、spec/design/task/证据记录；实现代理负责各自文件，checker正式复核。
完整客户端仍in_progress/implementation_complete=false。

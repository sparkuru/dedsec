# 前台密码 SSH 连接

2026-10-26 新接续：[可选保存与复用](ssh-saved-password.md)扩展同一正式密码路径，
默认当次行为保留。下文描述首轮已交付的一次密码基线；新增 Account/密码准入、保存/
复用/忘记以接续合同及本轮验证为准，不再把所有持久密码一律视为不支持。

2026-10-25。继续[唯一正式主线](ssh-manifest.md)，目标是从真实界面连接到显式TCP端点、
核对真实主机键并进入现有PTY终端。沿用固定OpenSSH引擎，不再停在预认证factory或测试
picker。实现、本地检查与设备验收分别记录。2026-10-26用户已明确允许指定开发板的临时
隔离测试安装/合成账号验收/清理；实际bridge与Flutter界面均通过，见
[实机记录](verification.md#20261026-password-android)。本轮未覆盖安装正式应用或提交发布。

## 用户路径与保存行为

终端空闲选择器增加“SSH 连接”，与原App/Root入口并列。先共用初始化/解锁，再进入既有
敏感保护路由。输入host、port、username、password，密码默认且本轮仅当次使用。公开的
最近连接信息与用户确认的主机键保存在同一个真实加密库，页面明确提示“保存主机信息；
密码仅本次使用”。不提供未实现的保存密码/私钥/证书/跳板选项。

host按现有role ASCII谓词有界检查，port1..65535，username保留原值并严格UTF8<=255。
password严格UTF8非空<=8192bytes、拒NUL，不trim/规范化；不进入终端输入、history、日志、
events或preferences。Host不接受URI/附带端口的伪解析；IPv6具体支持按connector实际验证。
密码field在提交、取消、覆盖、后台、失败/成功及dispose清空；Dart/MethodChannel不可变
String的清引用不冒充VM内所有副本擦除。host-owned数组各退出均wipe。

只有真实password SUCCESS、PTY及shell RUNNING才由现有terminal stream发布running。
表单Future成功/用户确认/套接字建立不构成连接成功。复用现有输出/输入/resize/Stop，
预连接取消有独立入口，不能受“已有session才允许Stop”旧条件阻塞。

## 实际库中的最小受管配置

沿现有MANAGED真实offered-key→NEW审批→精确CAS，不增加假KNOWN/PINNED或“内存提交”
结果。使用真实AppSecretsOwner、真实typed操作及独立candidate/source校验，不建第二库。

首路径只支持empty/pureTOTP，或精确的应用生成密码连接配置与known_hosts集合：

- 固定CONFIG逻辑路径`ssh/config`、ID`ctos-password-config`、identity`ctos-password-profile`；
  body固定header`# ctOS password connection`及literal Host alias`ctos-password`，显式
  HostName/Port/User/UserKnownHostsFile。User用双引号包围，双引号与反斜杠分别可逆转义，
  不能trim或借shell转义。validator还须用pinned解析核对实际host/port/user与显式request。
  保存最近一次显式端点信息，旧的
  unrelated TOTP保留。不能覆盖不符合该生成合同的已有CONFIG。
- 固定KNOWN_HOSTS逻辑路径`ssh/known_hosts`、ID`ctos-password-hosts`、identity`ctos-password-trust`，
  初始empty；CONFIG直接reference它，后续
  更新最近端点配置时完整保留已存主机键正文。IDs/identity由host固定拥有，不从opaque
  target解析host/user或把密码伪装成持久entry。runtime target采用固定明确的manual用途
  identity`target:ctos-password`，connection/role nonce与actual host/port/user共同绑定每次真实连接。
- 真实validator覆盖整个实际source/candidate：除TOTP与这两个明确类型/路径/ID/canonical
  CONFIG及有效KNOWN_HOSTS外均显式拒绝（包括私钥/证书/其他CONFIG/持久SSH凭据或非空
  keyBindings/accounts），不忽略未知对象，不把partial inspector报告当全语义许可。
  复用固定parser检查known_hosts和实际配置；不能用`manifest -> {}`。
- 首次创建及修改配置仍走AppSecrets原始Request/epoch/deadline、独占candidate/loan、
  唯一effect与原子写入/uncertain规则。form提交只批准本次精确生成proposal，不能注册
  通用bundle批准器。取消/连接失败可保留已明确保存的公开连接信息；不声称撤销已落盘
  的配置或主机键。未支持资料保持原库并解释当前连接路径不支持。

这是一条受支持的完整窄路径，不是格式分支；可选功能扩展同一准入能力。

## 临时密码与真实认证

新增host-owned一次临时password lease，明确handoff到原terminal owner。它绑定实际
shared-vault前台/解锁context、原request/cancel、role/selection对象、username、target、
connection、原认证deadline和现有host/KEX proof。仅selected password回调使用，不经过
临时落盘/删除或假MemoryStore；不能经legacykind-only provider绕过正式selection。

保留现有proof-aware CredentialBridge的最终host/KEX/role/session/guarded-copy检查；
只改变所选秘密来源。真实NEW-CAS引起的own-view/epoch延续必须复用原精确continuation，
不能把任意epoch变化当批准，也不能在合法own-CAS后错误拒绝同一次已选临时密码。
attempt/UI guard也须处理同一精确profile创建与NEW-CAS续接，不能因合法本次commit自行
cancel；外部write/lock/cancel照常撤销，不能套用TOTP对任意epoch变更清空的一刀切策略。
第一次copy后消费，重入/重复/换role/换selection/取消/锁库/过期均拒绝；所有副本finally
擦除。密码释放必须已通过真实签名KEX；开shell必须已收到selected password SUCCESS。

## TCP、线程与FD所有权

共享terminalWorker是创建native session到shell的同一串行owner；不能把已建session移到
网络线程。DNS可用一个有界worker，等待每<=50ms检查原deadline/cancel；系统DNS可能不
可抢占，取消后结果不得发布且不能无限创建resolver线程/排队任务。

使用公开API `Os.socket/connect/poll/getpeername` 建立非阻塞TCP，按原<=60s认证总预算
逐地址尝试；poll每<=50ms检查取消，随后getpeername确认实际peer与已选IP/port相等才
认定连接成功，不能仅看POLLOUT。Os.getsockoptInt是隐藏API，不用反射/隐藏SDK调用。
FD必须STREAM/RDWR/
CLOEXEC/NONBLOCK；无Root/host subprocess/外部ssh进程。解析/连接失败返回固定错误，
不把内部路径、密码或原始异常放入产品提示。

`ParcelFileDescriptor.dup(FileDescriptor)`后显式detach转交一份独占描述符，原Os fd关闭，
无需通过Socket私有FD/反射，也避开API28 fromSocket非真正dup的兼容陷阱。相关公开契约：
[Android Os](https://developer.android.com/reference/android/system/Os)与
[ParcelFileDescriptor](https://developer.android.com/reference/android/os/ParcelFileDescriptor)。

最低API28不能直接引用API29才公开的SOCK标志或API30的Os.fcntlInt。socket创建使用
Android/Linux已存在的NONBLOCK/CLOEXEC ABI值；dup共享NONBLOCK，但不继承FD_CLOEXEC。
在PFD仍持有副本时，经自身JNI `SshNativeSession.prepareTcpDescriptor(int)`检查STREAM/
RDWR/NONBLOCK并设置CLOEXEC，成功后才detach；失败仍由原PFD关闭。不通过隐藏API，
不改变最低SDK或放宽native入场检查。依据为固定
[Android9 socket头](https://android.googlesource.com/platform/bionic/+/android-9.0.0_r61/libc/include/sys/socket.h)、
[fcntl常量](https://raw.githubusercontent.com/aosp-mirror/platform_bionic/android-9.0.0_r61/libc/kernel/uapi/asm-generic/fcntl.h)、
[PFD实现](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-9.0.0_r61/core/java/android/os/ParcelFileDescriptor.java)与
[Os原生实现](https://android.googlesource.com/platform/libcore/+/android-9.0.0_r61/luni/src/main/native/libcore_io_Linux.cpp)。

pinned config求值使用真实localUser/localHostname；`SshNativeRoleBootstrap.systemIdentity()`
通过有界getpwuid_r(getuid())和gethostname读取，严格UTF8解码，失败明确拒绝。不借隐藏
Os.getpwuid或伪造local用户上下文。remoteUser/host/port仍取真实显式连接输入。

生产openManaged新增明确ownership对象重载：`SshSocketOwnership(int fd, Closer)`，
`openManaged(callback, lease, ownership, candidateId, budget)`；native正handle返回后、任何
Java postguard前标记native已接管。未接管失败由wrapper精确close一次；接管后失败/取消
只能由native client关闭，不根据generic exception猜测FD是否还属于caller。host不导出FD。
接管后构造/finish/postguard失败必须保留可重试的native清理句柄及其资源owner，不能只
标记转移再丢失尚未成功close的client。wrapper只管理caller-owned rawfd，不代native强关。
JNI reservation/publication保证C成功接管后没有可失败的发布步骤；容量/参数错误在接管前
结束。legacy int测试接缝可保留，生产连接器必须使用显式ownership。

## 认证期限与已建立会话

保留原<=60s role/auth/file/credential预算，不延长常量或续发秘密权限。仅在真实所选
SUCCESS之后、原auth proof仍live的beginShell阶段，晋升同一session的shell-only authority。
绑定原owner PID/UID/thread、socket/client、accepted KEX cycle/session digest/实际主机键、
connection及显式revocation/cancel；不能替换KEX、认证方式、view或transport。

晋升后step/status/read/write/resize/eof只检查这一窄已建立权限，不能再因认证deadline或
view的时间预算自然到期强制断开；显式role/view撤销、锁库、取消/Stop、Activity teardown
仍关闭本轮前台会话。文件/凭据/信任/新auth APIs仍用原时间与proof检查，不从shell-only
权限获得新权利。rekey/forward/jump仍按原未支持政策明确拒绝，不承诺后台服务/R8全部
完成；本轮先得到实际可操作的前台密码shell。

## Flutter与host协议

ctos/native新增下列methods，不能被terminal前缀串行排队规则拦住取消或审批：

- `ssh.defaults {requestId}` → `{host,port,username}`公开最近信息，仍需shared前台/解锁/敏感保护。
- `ssh.passwordConnect {requestId,host,port,username,password,columns,rows}` → `{requestId,status:'connected'}`，
  只有真实终端RUNNING才resolve；原terminal state stream仍是UI输入启用的权威。
- `ssh.cancel {requestId}` → `{cancelled:bool}`；立即标记原attempt、唤醒mailbox，owner做cleanup。
- `ssh.hostKeyDecision {requestId,ticketId,binding,accept}` → `{accepted:bool}`；host匹配当前
  exact Ticket，直接resolve，不等被其阻塞的terminalWorker，不接受仅Boolean批准。

独立ctos/ssh stream，严格解码一次，不扩展ctos/terminal的bytes/lifecycle协议：

- `{type:'state',requestId,state:'connecting'|'reviewing'|'connected'|'failed'|'cancelled',reason?:code}`。
- `{type:'hostKey',requestId,ticketId,binding,host,port,keyType,fingerprint,remainingMillis}`；
  remainingMillis0..30000，仅公开metadata，不能把BOOTTIME当Unix时间比较。
- `{type:'hostKeyDismissed',requestId,ticketId}`。

表单idledefaults与connect使用不同fresh requestId；host有界metadata-only 100ms idle
检查可用`state:cancelled`对defaultsId通知锁库/失效，开始连接前撤除这一idle监视，避免
合法本次profile/CAS写入触发自取消。UI进入connecting后忽略旧defaultsId事件。

host一时只允许一个attempt或terminal；request/terminal generation各自精确fence。审批
显示实际host/port/算法/SHA256指纹，按钮“信任并保存”与拒绝；无默认批准。CHANGED/
REVOKED/证书拒绝，不退回NEW确认或重写known_hosts。已有KNOWN匹配不再提示。
取消/stream失败/route覆盖/后台清空密码并取消pending，不关闭已成功交给终端的session。
不另建第二个ctos/secrets listener。App/Root原UI行为和typed terminal代次保留。

known_hosts查询与新行写入都使用实际role端口：22沿用原host，其余按OpenSSH的
`[host]:port`形式。role、审批及签名证明中的host仍保留原值，不能从opaque target推导；
同一host的其他端口不得复用该主机键记录。该条件由独立服务端测试发现并补入正式路径。

## 检查与验收

三块独立所有权：native/Java session权利与FD接管；host实际配置/validator/临时密码/TCP/
bridge；Flutter表单与主机键UI。main串行构建，独立checker复核合同/代码/原始证据。
覆盖真实orchestration序列及拒绝/取消/锁库/重入/late reply/FD失败/cleanup保留，不只注入
预认证session。native实际加密peer验证晋升后越过原60s仍shell工作，而秘密/文件/newauth
仍过期拒绝，rekey/换KEX/role/revoke拒绝；旧认证/PTY矩阵保留。

本地JVM/Flutter/strict C/lint/normal/fixture/APK与可用的隔离TCP SSH server验证分别记录，
合成VM/注入测试不等于Android真机实际连接。代码与APK准备完整后再按当前授权安排
明确设备/隔离端点实测，不沿用历史设备许可，不以测试数量替代用户可用性。

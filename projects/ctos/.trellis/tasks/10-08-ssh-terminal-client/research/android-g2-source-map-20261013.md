# Research: G2 Android OpenSSH 六组内存文件探针源码映射

- Query: 把旧Linux六组probe映射至实际OpenSSH10.6p1/API28源码、签名、链接对象与Android AppUID夹具入口，区分可复用逻辑和必须重做部分。
- Scope: internal；只读指定解压源码、生成配置、当前G0/G1检查及旧probe；不build/network/device/commit/spawn。研究角色未加载implement/check JSONL。
- Date: 2026-10-13

## Findings

### 定位与当前证据边界

下文用 `S/` 表示 **`build/ssh-native-g1/stock-run.ZErimQnc/sources/openssh-10.6p1/`**，`B/`表示同run的`openssh-build/`，`L/`表示`.trellis/tasks/10-08-app-authentication/research/`。这些是实际本地官方解压源码/生成配置锚点，不能沿用先前web源码行号。`S/version.h:3–6`为OpenSSH10.6/p1；G1仅加Bionic include-order兼容patch的baseline，不能称完全unmodified stock。

当前`check.md`记录G0仅API30/Permissive/4KiB AppUID真实1项通过：raw memfd完整，ASharedMemory POSIX形态不支持。这里未重跑；G1 baseline compile仍由main推进，不能据已有对象/配置认定最终构建成功。**G2需真实10.6p1上游调用在Android AppUID执行；小自写parser、G0状态协议或旧Linux PASS均不能替代。**

### 新run实际 ABI / linker 入口

| 项 | 当前本地证据 | G2处理边界 |
| --- | --- | --- |
| glob ABI | `B/config.h:166,169,172,792`：HAVE_GLOB_H=1、GL_MATCHC=1，但ALTDIRFUNC/GL_STATV undef；`S/openbsd-compat/glob.h:40–53`因此重命名`glob_t`/glob/globfree | 使用**上游内部**`_ssh_compat_glob_t`及`_ssh__compat_glob`/`_ssh__compat_globfree`，不能用Bionic libc glob_t替代 |
| closefrom | `B/config.h:304,307,475,603,1127`：HAVE_CLOSEFROM/CLOSE_RANGE/FCNTL_CLOSEM undef，DIRFD/PROC_PID=1 | `S/openbsd-compat/bsd-closefrom.c:123–149`的`void closefrom(int lowfd)`走`/proc/<ownpid>/fd`关闭，失败才fallback循环；startup真实函数会关闭继承FD |
| 客户端对象 | `S/Makefile.in:117–118`/`B/Makefile:117–118` | `ssh.o readconf.o clientloop.o sshtty.o sshconnect.o sshconnect2.o mux.o ssh-pkcs11.o ssh-sk-client.o`；**旧runner缺`ssh-pkcs11.o`**，即使PKCS11未启也要以当前实际链接规则为准 |
| 实际链接规则 | `B/Makefile:223–224,56–57,70` | 客户端在SSHOBJS后依次LDFLAGS、`-lssh -lopenbsd-compat $(LIBS) $(GSSLIBS) $(CHANNELLIBS)`；当前LIBS=`-lz`、CHANNELLIBS=`-lcrypto -lz`，libcrypto来自本run私有prefix，不能搬旧runner只`-lcrypto`的linkline |
| libssh对象 | `S/Makefile.in:91–111` | authfile/hostfile/sshbuf-io/sshkey及算法依赖已进入libssh.a；新lib含`libcrux-mlkem-mldsa.o ssh-mldsa-eddsa.o ed25519-openssl.o ssherr-libcrypto.o`等。读取新archive/Makefile，不另拼旧版本对象闭包 |
| Driver链接 | `L/ssh-memory-run.py:69–71`旧driver加ssh-sk-client.o/libssh/libcompat/libcrypto | 新driver需要真实头文件和本runarchive；实际undefined-symbol检查/链接决定是否需额外stub对象，不靠关闭算法或省略符号过关 |

旧wrap列表（`L/ssh-memory-run.py:15–18`）仍是待核验入口：`open,fopen,stat,access,mkdir,mkstemp,link,rename,unlink,getpwuid,_ssh__compat_glob,_ssh__compat_globfree,closefrom`。签名见下文。实际NDK头文件`bits/fortify/fcntl.h:33,59–63`可把open降为`__open_2(const char*,int)`；本runCFLAGS未显示_FORTIFY_SOURCE，但重编译adapter/上游时须从真实undefined symbols确认open/open64/fortify变体，不因source名称相同就假定wrap全部生效。不需要因此关闭硬化。

旧shim声明/定义的基础hook签名（`L/ssh-memory-native.c:57–69,155–259,344–427`）：`int open(const char*,int,...)`；`FILE *fopen(const char*,const char*)`；`int stat(const char*,struct stat*)`；`int access(const char*,int)`；`int mkdir(const char*,mode_t)`；`int mkstemp(char*)`；`int link(const char*,const char*)`；`int rename(const char*,const char*)`；`int unlink(const char*)`；`struct passwd *getpwuid(uid_t)`；`void closefrom(int)`。新adapter按真实NDK与upstream头文件声明编译，不拷glibc结构布局。

### 六组映射

#### 1. Config / 根相对嵌套 Include / 上游 glob 词法顺序

- 上游入口：`S/ssh.c:564` static `void process_config_files(const char *host_name, struct passwd *pw, int final_pass, int *want_final_pass)`；`572–579`指定-F时只读选定config，带SSHCONF_USERCONF，不读取系统config。
- 公共调用：`S/readconf.c:2619` **`int read_config_file(const char *filename, struct passwd *pw, const char *host, const char *original_host, const char *remote_command, Options *options, int flags, int *want_final_pass)`**。
- 实际文件访问：static `read_config_file_depth(..., int *activep, int *want_final_pass, int depth)`在`2631–2682`，`2644 fopen(filename,"r")`→`2650 fstat(fileno(f),...)`权限检查→`2664 getline`→`2672 process_config_line_depth`。不能绕过上游权限检查，匿名对象mode/uid必须真实满足。
- Include：`S/readconf.c:2072–2154`，先上游token/env展开，裸相对路径锚`~/.ssh`，`2115 glob(...,GLOB_TILDE,NULL,&gl)`，递归时带CHECKPERM，保留包含文件Match状态。父config不在`.ssh`内也不改变这一基准。
- glob签名：`S/openbsd-compat/glob.c:180` **`int glob(const char *pattern, int flags, int (*errfunc)(const char *, int), glob_t *pglob)`**；经宏重命名为`_ssh__compat_glob`。`973 void globfree(glob_t*)`；`glob.h:71–75`提供`gl_opendir/readdir/closedir/stat/lstat`真实callback形状。`glob.c:588`上游qsort排序，`396`tilde可调用getpwuid。
- 可复用：旧`L/ssh-memory-native.c:269–341`有界合成目录和alt-dir callback逻辑、乱序20/10manifest及root-relative nested夹具、`-G`结果user/port/host断言；**不自写matcher/parser**。
- 必须重做：API28匿名对象创建、exact同代次快照/请求验证、NDKdirent/stat/glob ABI及真实wrap触发；新parser输出与stock同版本对照。当前upstream深度限16（`readconf.c:2629`）不是产品深度8，probe成功也不证明产品资源上限。
- 依赖：客户端SSHOBJS+libssh.a+libcompat；glob.o来自compat archive，readconf.o直接客户端对象。

#### 2. ABI baseline 原版启动关闭继承 FD 的负对照

- `S/ssh.c:646` startup **`closefrom(STDERR_FILENO+1)`** 在选项处理前；`664` **`struct passwd *getpwuid(uid_t)`**，随后`670 pwcopy`。旧`L/ssh-memory-native.c:251–259`只替换pw_dir，其余来源真实getpwuid。
- 旧`418–427`保留FD198的closefrom wrap只能作为**合成probe专属**约定；不是生产FD能力接口。实际新closefrom实现见上表，不应禁掉上游closefrom或让全部FD自然继承。
- 可复用：ABI兼容baseline（无VFS政策hook）`-G -F /proc/self/fd/198 probe`失败与受约束client正对照。
- 必须重做：亲自证明自己的FD在exec前有效，baseline失败原因是启动后FD不可用，不能接受Bionicgetpwuid错误/缺动态库/路径错误作为负对照PASS。保留FD只限已验证synthetic bootstrap/control白名单；非白名单FD拒绝。
- 依赖：baseline及受约束client都用同10.6p1/G1输入，兼容patch相同，差异仅G2researchhooks；`bsd-closefrom.o`来自libcompat。生产安装路径含随机段，argv0必须由fixture nativeLibraryDir确定。

#### 3. IdentityFile 真解析 + known_hosts 真 append/read/replace/proposal

真实公开函数/签名（以本地头文件/实现为准）：

| 函数 | 实际签名 | 锚点 |
| --- | --- | --- |
| sshkey_generate | `int (int type, u_int bits, struct sshkey **keyp)` | `S/sshkey.c:1493`、`sshkey.h:200` |
| sshkey_private_to_fileblob | `int (struct sshkey *key, struct sshbuf *blob, const char *passphrase, const char *comment, int format, const char *openssh_format_cipher, int openssh_format_rounds)` | `S/sshkey.c:3414–3416` |
| sshkey_load_private_type | `int (int type, const char *filename, const char *passphrase, struct sshkey **keyp, char **commentp)` | `S/authfile.c:110–111` |
| sshkey_perm_ok | `int (int fd, const char *filename)` | `S/authfile.c:82–106`：fstat/真实mode检查 |
| sshkey_load_private_type_fd | `int (int fd, int type, const char *passphrase, struct sshkey **keyp, char **commentp)` | `S/authfile.c:142–159` |
| init_hostkeys / load_hostkeys | `struct hostkeys *(void)` / `void (struct hostkeys*, const char *host, const char *path, u_int note)` | `S/hostfile.c:216,286` |
| check_key_in_hostkeys | `HostStatus (struct hostkeys*, struct sshkey*, const struct hostkey_entry **)` | `S/hostfile.c:408`、`hostfile.h` |
| add_host_to_hostfile | `int (const char *filename, const char *host, const struct sshkey *key, int store_hash)` | `S/hostfile.c:542–543` |
| hostfile_replace_entries | `int (const char *filename, const char *host, const char *ip, struct sshkey **keys, size_t nkeys, int store_hash, int quiet, int hash_alg)` | `S/hostfile.c:623–624` |
| hostkeys_foreach | `int (const char *path, hostkeys_foreach_fn *callback, void *ctx, const char *host, const char *ip, u_int options, u_int note)` | `S/hostfile.c:966–967`；callback为`int (struct hostkey_foreach_line*,void*)` |

- Identity真实链：`authfile.c:120 open(O_RDONLY)`→权限→`sshbuf_load_fd`（`S/sshbuf-io.c:34–74`）fstat/atomicio(read)/size一致→上游parse私钥；不能仅直接调parse_fileblob就声称IdentityFile路径读取通过。
- known_hosts append：`hostfile.c:504–524`上游tilde/stat/mkdir；`551 fopen("a+")`、setvbuf/fseek/fputc，上游write_host_entry；不是简单pwrite替代上游append。
- replace：`657 mkstemp`→`663 fdopen("w")`→`672 hostkeys_foreach`真实读旧对象→`721 fclose`→`726 unlink(back)`→`732 link(old,back)`→`739 rename(temp,filename)`；无变化/失败走unlink(temp)。所有临时名和`.old`均须逻辑匿名对象，不能普通磁盘staging。
- 可复用：旧driver内存生成未加密Ed25519、序列化→实际IdentityFile loader/sshkey_equal；HOST_OK/HOST_CHANGED、bounded proposal读回；public结构/函数签名未见本组不兼容变化。
- 必须重做：raw syscall匿名对象/权限、真实Bionicstdio及seek、boundedtemp命名/写入/失败清理；旧link wrap（`L/ssh-memory-native.c:379–394`）复制字节不是完整POSIX硬链接alias行为，只够此合成replace形状，不能推广为产品VFS全部语义。
- 依赖：libssh.a中authfile/sshbuf-io/hostfile/sshkey等+libcompat+实际PIC libcrypto；必要ssh-sk-client对象以新link结果为准。G2此组成功仍只覆盖选定未加密Ed25519，不宣称RSA/ECDSA/加密格式或远端auth。

#### 4. 真 ProxyJump parent/exec child 同代次继承 + identification失败

- `S/ssh.c:1278–1328`上游根据ProxyJump生成implicit ProxyCommand；`1297 access(argv0,X_OK)`失败会回落`ssh`，`1319–1321`把-F传每跳；proxy_use_fdpass明确为0（`1305`）。不能靠ProxyUseFdpass保存研究FD。
- 实际static函数 **`int ssh_proxy_connect(struct ssh *ssh, const char *host, const char *host_arg, u_short port, const char *proxy_command)`**：`S/sshconnect.c:225–300`，expand_proxy_command→fork→pipe映射stdin/out→`233–234`选SHELL或_PATH_BSHELL→`270–280 shell -c` execv→父记录proxy PID。该函数是static，不可假设`--wrap=ssh_proxy_connect`就拦到调用。
- 可复用：独立127.0.0.1临时端口listener，仅接受SSH-2.0 identification随即关闭；各client合成config读取PID/代次事件；明确主链失败无直连fallback。
- 必须重做：AndroidSHELL默认值、APK absolute argv0及命令引号/路径（安装路径可含`=`）、bootstrap FD跨primary→**实际shell**→jumpclient的每次exec；固定合成host/user/path输入，不能把调试Shell环境作为生产任意本地执行放行。用PID/generation关联的固定控制事件证明两次读取，旧“substring出现>=2次”不足以证明不同parent/child。
- 依赖：完整新客户端/sshconnect对象、同版本VFS hooks、raw memfd bootstrap；G0只验证自有self-exec，未证明此shell/上游链继承。此组不含KEX/host key gate/auth/转发成功。

#### 5. ProxyJump owned cancel / descendant reap

- 上游 **`void ssh_kill_proxy_command(void)`**（`S/sshconnect.c:305–312`）仅SIGHUP记录的proxy PID，注释明确不wait、依赖init；不能把上游调用视为ctOS全树回收。
- 旧driver `L/ssh-memory-native.c:479–524`：自身fork primary并setpgid，listener identification后对**自己group**SIGTERM，waitpid primary，然后subreaper waitpid(-1)。`main:555`设置PR_SET_CHILD_SUBREAPER。
- 可复用：自有processgroup/subreaper作为独立PIE研究driver，明确取消触发点、primary和jumpchain身份；不能用于Java进程中任意waitpid(-1)回收别的ctOS孩子。
- 必须重做：旧accept/read/wait阻塞只靠runner20秒总体期限，Android需poll+monotonic截止+固定尝试上限，保留未reap PID所有权、超时升级SIGKILL/失败仍清理；不能只terminate Java直接child就丢其SSH后代。G0自身exactchild有参考实现，但不覆盖真实上游孙进程。
- 依赖：client/sshconnect真实链+独立driver的prctl/signal/wait；系统API是否容许及锁屏/服务/进程死亡不由源码映射证明。

#### 6. 缺失/遍历拒绝、revoke新读/迟到写、擦除/heldFD EOF

- 旧`L/ssh-memory-native.c:134–151`lookup检查snapshot revoked和路径；`656–673`实际缺失/遍历拒绝、持有Identity FD、revoke后新增私钥读与add_host_to_hostfile拒绝、wipe_owned_view/ftruncate/readEOF。
- 可复用：ENOENT/EINVAL/EACCES区别；通过上游late add触发拒绝；自己映射wipe/munmap/truncate后自己heldFD EOF，不把返回空当成功。
- 必须重做：旧`initialize:115–131`在**每个进程**从bootstrap记录复制成独立object；共享revoked只拦新lookup，不擦除既有各进程object/stdio/native parsed key。G0同步擦除自己child特定region不等于OpenSSH所有副本撤销。若G2不实现/测上游child协同擦除，结果必须明确仍只是driver本地heldFD边界。
- old canonical_path只是`/../`/`/./`/`//`等合成约束且允许其他path libc passthrough；不能作为恶意配置沙箱或产品完整越界检查。epoch/generation/public request受限协议需由main拟定，本研究不选生产broker架构。
- 依赖：VFS lookup/writes、raw memfd POSIX shape、上游hostfile调用与自有cleanup；与AppSecrets epoch/CAS/已认证transport存活尚未集成。

### 最小 Android AppUID fixture 实施触点（非生产bridge设计）

可执行的数据流边界：独立fixture Java精确opt-in → 从其`nativeLibraryDir`启动固定G2研究driver → driver只在内存生成有界公开合成config/Include/Ed25519/trust → raw memfd/mmap/mlock → 自有baseline和受约束10.6p1client → 真实upstream六组 → 固定非秘密阶段结果 → 精确回收。没有用户目录/真实库/SAF/远程账号入口。

| 触点 | 现有可参考代码 | 最小新增责任（由main决定文件/命名） |
| --- | --- | --- |
| raw anonymous创建 | `android/app/src/main/cpp/ssh_memory_probe.c:286–301`，SYS_memfd_create/MFD_CLOEXEC、ftruncate和/proc/self/fd重开 | API28不能直接依赖API30 Bionic memfd符号；只复用已测candidate形态，新大小/对象上限需实际测mlock，不掉到ASharedMemory或常规文件 |
| strict driver owner | 同文件`118–160,198–224`期限/精确child/spawn | 新研究native adapter/driver可双模式编译参考旧SSH_MEMORY_DRIVER；startup/每跳FD的保留与同步要真实受限，合成全部snapshot不作为生产能力模型 |
| upstream link | G1 private prefix/newMakefile及`L/ssh-memory-run.py:54–73` | 保留ABI baseline；另外链接G2受约束client和driver。先检查实际wrap符号/头文件配置一致，不能在正在build的G1目录原地改源码/产物 |
| APK opt-in隔离 | `android/app/build.gradle:10–15,57–59,124–140` | 新G2专用开关/输出目录仅fixture sourceSet，fixed three research executables；普通release无G2残留，不加入Portable tools/生产SSH入口 |
| Java AppUID守卫 | `SshMemoryPlatformTest.java:55–80,213–245` | 独立测试类/固定selected method/stricttrue、生产UID不同/no sharedUID/freshfixture/Root关闭；无Activity和vault调用；固定native paths由Java确定，传递非秘密目标参数须native白名单验证 |
| 输出/资源清理 | 同测试`drain/validate/cleanup`有界protocol、interrupt与逐流finally | G2单独协议，不弱化G0 validator；stdout仅fixedphase/errno/PID/代次/计数，SSH原始输出只在driver内部有界解析并擦除，不向Android日志输出key/config/trust payload |

六组研究snapshot至多16对象/16KiB是旧夹具参数，不是产品128文件/8MiB授权；实际G2尺寸由实现明确，不能一次交每跳全部真实秘密。使用合成key只为本group调用上游loader，不触及生产HostKeyGate/provider/KDF或真实库存储。

### G2安全策略需要另行映射/验证，六组原样重放未覆盖

`S/readconf.c:526`static execute_in_shell实际execv，`885`调用Match exec；LocalCommand/ProxyCommand/KnownHostsCommand解析在`2056/1539/1535`；StrictHostKeyChecking有效值在`1009,1348–1350`。仅wrap文件函数不能防本地命令/信任绕过。native解析/执行边界的具体patch与control方案由main另审定，不能靠Flutter检查、几条正则或先跑-G再报错。

首批六组只用可信合成config，不能写“用户config安全策略通过”。若G2范围同时包含政策gate，须补根/Include/跳板声明的执行前拦截、none/no非误报、受管ProxyJump继续、绕过指纹有效选项拒绝及固定标记/网络负对照；不因六组PASS自动勾父AC8/AC5。

## Related specs / external references

- `.trellis/spec/backend/{host-bridge.md,quality-guidelines.md,app-secrets.md}`：资源owner/分层证据/生产SSH validator缺失失败关闭。
- task`prd.md/design.md/implement.md/check.md`及父`10-08-ssh-terminal/design.md`：内存文件路线、不静默降级/明文staging、config/每跳完整验收。
- 本轮未browse。external输入是main已取得并固定的官方10.6p1源码，归档hash/许可证来源仍见`android-openssh-build-inputs-20261013.md`及design/dependencies；本地API证据来自固定NDK27头文件。

## Caveats / Not Found

- 只source/config映射，未执行preprocess、nm、link/build、测试或设备操作；fortify/undefined symbol实际集合待main构建后核对。
- 本研究不宣布G1最终成功、G2 Android六组通过、生产文件broker/策略/每跳认证通过或真实秘密库已操作。
- 当前API30/4KiB/Permissive G0结果不能代替API28/29/Enforcing/真实16KiB、跨进程native复制/缓冲擦除；ASharedMemory当前shape unsupported不可隐藏。
- inherited memfd/shim passthrough/合成fixedFD只提供研究复用触点，不授予普通Shell/同UID代码通用秘密句柄；生产架构/真实端点授权由main另处理。
- 尤其要保留group4 identification、group6本地EOF及group3单未加密Ed25519的窄范围，不能写完整登录/每跳host key/全密钥格式/信任encrypted CAS/锁库transport通过。

# Research: Android OpenSSH 无明文磁盘 gate 的启动条件

- Query: 现有 Linux 六组 probe、Android 原生构建和公共库接口是否足以开始 Android gate；首个最小 probe、最多三个实施文件和可验证 AC 是什么？
- Scope: internal；只读现有设计、代码、NDK 头文件和既有研究。未构建、执行 probe、连接设备、访问网络或读取个人 SSH 数据。
- Date: 2026-10-13

## Findings

### 结论与证据等级

可以开始 Android gate 的实施：项目已有固定 NDK、arm64/API28 原生构建入口、APK 原生目录执行模式及独立 UID 测试 flavor。**尚无 Android OpenSSH 客户端构建、运行或无明文文件视图通过证据**。建议先完成不引入 OpenSSH/OpenSSL 的 Android 匿名内存/受管执行 probe，随后移植真实上游文件访问六组对照；第一层通过不能标父任务 AC1–AC9 或 native OpenSSH gate 整体通过。

最明确的移植障碍是：Linux probe 直接调用 `memfd_create`，而项目 NDK 的公开 Bionic 声明要求 API30。项目实际编译目标为 API28。不能通过把编译目标提高到30来掩盖 API28/29 的空缺；也不能把宿主 gcc/libcrypto 输出打入 APK。

本轮用户“验收提交后继续下一项”由主会话负责更新 SSH 启动状态。磁盘中的 `planning`/旧“未批准”段落仍是历史状态，不应据此重问已有实施授权；本研究不修改 metadata。真实设备安装/SSH 账号登录未由本研究新增授权。

### Files found

| 文件 | 内容与用途 |
| --- | --- |
| `.trellis/tasks/10-08-ssh-terminal-client/{prd.md,design.md,implement.md,task.json}` | 现有客户端范围、底座依赖与 native-first 执行顺序；研究角色未加载 implement/check JSONL |
| `.trellis/tasks/10-08-ssh-terminal/{prd.md,design.md,implement.md}` | 权威 OpenSSH 行为、禁止本地命令/信任绕过、资源限额与 Android gate |
| `.trellis/tasks/10-08-app-authentication/research/ssh-memory-native.c` | 真实上游文件函数的 Linux/glibc 包装器与合成 probe driver |
| `.trellis/tasks/10-08-app-authentication/research/ssh-memory-run.py` | 固定源码 hash、宿主 configure/make、严格编译、wrap 链接及20秒运行期限 |
| `.trellis/tasks/10-08-app-authentication/research/ssh-memory-results.md` | 六组历史实际结果、来源、限制和公共库/native 接口约束 |
| `.trellis/tasks/10-08-app-authentication/research/ssh-memory-validation.log` | 持久保存的六条 PASS；不是本轮 Android 运行日志 |
| `android/app/build.gradle` | 固定 SDK/NDK/ABI，Gradle Exec 构建 C/JNI/PIE；独立测试 flavor |
| `android/app/src/main/cpp/{pty.c,python_exec.c,hftp_relay.c}` | 现有 native 入口；没有 OpenSSH 或 CMake 构建体系 |
| `android/app/src/main/java/im/majo/ctos/PortablePackages.java` | APK 原生目录执行和包资源分发；自动暴露 tools 的行为不适合直接授予受管 SSH CLI |
| `android/app/src/main/java/im/majo/ctos/{AppSecrets.java,SshVaultAdapter.java}` | 已有 typed SSH 文件/凭据/信任请求及 fail-closed validator 注册点 |
| `android/app/src/secretsFixture/java/im/majo/ctos/SecretsFixtureApplication.java` | fixture 固定包名、同步关闭其自身 Root auto_start |
| `android/app/src/androidTestSecretsFixture/java/im/majo/ctos/TotpPlatformTest.java` | 独立 UID/no sharedUserId/生产 UID 不同的测试守卫示例 |
| `hako`, `hako-env.sh` | 固定 Docker 工具链，项目挂载到 `/app`，宿主 `/tmp` 不自动挂载 |
| `.devhome/android-sdk/ndk/27.0.12077973/.../sysroot/usr/include/{sys/mman.h,android/sharedmem.h}` | 当前实际 NDK 的 API 标记及匿名共享内存约束 |

### 构建入口与 ABI

- `android/app/build.gradle:12–21`：compile/target SDK36、minSdk28、NDK `27.0.12077973`、唯一 `arm64-v8a`。
- `android/app/build.gradle:75–112`：没有 CMake；现有 Gradle `Exec` 用 `${android.ndkDirectory}/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang`，`-Wall -Wextra -Werror`。独立可执行文件使用 `-fPIE -pie -Wl,-z,max-page-size=16384`；HFTP 入口还链接 `-landroid`。
- `android/app/build.gradle:34–35`：generated jniLibs + `useLegacyPackaging=true`。生产执行必须来自 `nativeLibraryDir`，不能从私有数据目录的解压 binary 执行（`design/portable-workbench.md:31–38`）。
- `hako:135–148`：临时 amd64 容器挂载项目为 `/app`，没有共享宿主 `/tmp`。跨构建调用要保留的源码/产物应放项目内 ignored `build/` 或 `.devhome/`；证据拷回 `/tmp`。不要用 `./hako current`，该入口替换 dist。
- `PortablePackages.java:192–212`：tools 自动形成终端函数，且拒绝包环境键 HOME/CODEX_HOME。SSH 首版通用 CLI 已延后，受管二进制不能通过加入 portable tools 就默认暴露给普通 Shell；私有 home/path 适配属于 SSH 进程，不修改全局 HOME。

### NDK 匿名内存的具体空缺

当前 SDK 头文件给出直接的编译期证据：

- `sys/mman.h:196`：`memfd_create(...) __INTRODUCED_IN(30)`。
- `android/sharedmem.h:78`：`ASharedMemory_create(...) __INTRODUCED_IN(26)`，可作为 API28 最小 probe 的正式 NDK 入口，链接 `-landroid`。
- `android/sharedmem.h:67–69`：若跨 exec 传递其 FD，需显式清除 `FD_CLOEXEC`。其余 FD 保持 close-on-exec，继承白名单只限本 probe 自有对象。
- `android/sharedmem.h:93–95`：`ASharedMemory_setProt` 只能移除权限，并且不影响已经存在的 mmap。不能据 setProt/父端 close 声称其他进程或已解析秘密被撤销。
- `sys/mman.h:126`：有 `mlock` 声明，但设备是否成功、限额多少不能从头文件推定。先读自身 `RLIMIT_MEMLOCK` 和实际 page size，并对实际申请尺寸测锁页；失败明确记录 errno，不偷偷放弃 mlock、改临时文件或标 PASS。

**ASharedMemory 的 mmap 可用不等于 Linux memfd 的 POSIX 文件行为可用。** Linux 包装器依赖 `/proc/self/fd` 重开取得独立读偏移、fstat/ftruncate、fdopen/fopen、append/truncate/replace；ASharedMemory 的公开头文件并未保证全部这些行为。第一层 probe 应分别给出 map/锁页、读偏移/文件读写、exec/撤销结果；失败保留为 adapter 待解决项，不将 mmap 成功包装成 OpenSSH 文件视图通过。API28 可否用经版本/运行时检查的 syscall 另需专门验证，本研究不建议直接添加未验证的 syscall fallback。

### Linux 六组可复用边界

持久 runner 固定 archive SHA256 `021a2e709a0edf4250b1256bd5a9e500411a90dddabea830ed59cef90eb9d85c`，实际 OpenSSH10.0p2；archive 解压目录名是 `openssh-10.0p1`，`version.h` 才定义 p2（`ssh-memory-run.py:94–110`）。OpenSSL3.5.7 是当时宿主依赖，**不是 Android 依赖选版**。

| 历史组 | 可复用的输入/断言 | Android 仍要补证 |
| --- | --- | --- |
| `ssh -G` + Include | 根相对嵌套、故意乱序 manifest、上游 glob 词法顺序与实际 readconf | 同固定版本 Bionic parser/glob ABI、私有 home/getpwuid、所有文件引用实际进入内存钩子 |
| stock FD 负对照 | 原版启动 closefrom 导致继承 FD 失败，证明只传 `/proc/self/fd/N` 不够 | Android 上游 closefrom 实际编译分支、受管白名单和 SELinux/FD 可用性 |
| 私钥 + known_hosts | 真实 authfile.c 的 Ed25519 加载及 hostfile.c append/replace/proposal | 常用加密/RSA/ECDSA 格式；Android stream/权限/写回；AppSecrets CAS 与批准桥 |
| ProxyJump 失败 | 真实 parent/exec child 同代次读取、loopback SSH identification 后失败、不直连回退 | APK 子进程路径、受管 argv、每跳身份/控制通道；KEX/auth/转发尚无证据 |
| ProxyJump 取消 | 自有进程组 SIGTERM + subreaper 精确回收 | Android 上可用性、服务资源所有权及并发/迟到隔离 |
| 缺失/遍历/revoke/擦除 | ENOENT/EINVAL/EACCES，自己的映射擦除、持有 FD 读EOF | 跨进程同步撤销、stdio/解析对象副本、已认证 transport 存活规则 |

关键代码：

- `ssh-memory-run.py:14–18,54–73`：GNU/LLVM linker wrap 候选符号与真实上游对象链接；必须复核 Android configure 选中的符号，不能假设 glibc wrap 清单在 Bionic 完整生效。
- `ssh-memory-native.c:91–127`：16个对象、每对象16384字节和整个固定 snapshot 的 memfd+mmap+mlock。snapshot 大约260 KiB，加各对象映射；与产品128文件/8MiB上限不同。probe 不能因小夹具通过证明产品上限可用，也不能保留巨大含全部私钥的 bootstrap 为生产设计。
- `ssh-memory-native.c:155–207`：非虚拟路径 libc passthrough，`/proc/self/fd` 重开后 fdopen，**研究 shim 不是恶意配置沙箱**。
- `ssh-memory-native.c:318–341`：上游 compat glob `GLOB_ALTDIRFUNC`，保留 matcher/sort，不另写简化 glob/parser。
- `ssh-memory-native.c:418–450`：固定FD198保留、自有映射擦除/ftruncate；不能据父进程 close 推断 child 所有副本擦除。
- `ssh-memory-native.c:555–599`：合成 loopback listener、全量合成 snapshot和内存生成未加密Ed25519，只是研究 driver。

### 首个可执行最小实施切片：最多三个文件

这三个是**建议新建/修改路径，尚未实现**；只做 G0 匿名内存与受管执行，不接生产 UI、AppSecrets 数据或实际 SSH 账号。

1. `android/app/src/main/cpp/ssh_memory_probe.c`（新）：独立 PIE，无上游/OpenSSL依赖。通过固定自测模式创建公开合成字节的匿名对象；有界mmap+mlock；正/负读写与独立偏移；执行自身固定 `--child` argv，继承仅自有白名单FD；child和parent按受控同步事件检查撤销与擦除；所有阶段有期限，失败仅输出固定阶段/errno/尺寸。不得读 `.ssh`、个人库或任意路径，不创建内容的普通文件。
2. `android/app/build.gradle`（修改）：严格 `ctosSshMemoryProbe=true` opt-in，要求已有 `ctosSecretsUiFixture=true`；沿现有 API28 clang/16KiB/strict warnings构建 `libctos_ssh_memory_probe.so`。**输出到专用 `generated/sshProbeJniLibs`，只加到 secretsFixture 的 jniLibs**，不要用生产 shared generated/jniLibs，避免后续普通APK捡到旧probe产物。task 只绑定相应 fixture variant 的preBuild，不给普通生产构建加probe。
3. `android/app/src/androidTestSecretsFixture/java/im/majo/ctos/SshMemoryPlatformTest.java`（新）：先严格检查当前测试/target包、无sharedUserId、UID相同且与生产不同、Root自动启动关闭；只在额外明确 instrumentation opt-in 下执行。通过绝对 `target.applicationInfo.nativeLibraryDir` 路径用 ProcessBuilder 启动固定probe，不借Shell/Root；固定allowlist解析结果，输出有界，超时精确关闭/reap自己的child。无需启动MainActivity/真实库。

建议主会话执行的命令入口（本研究未运行；task名需按实际Gradle实现核对）：

```sh
./hako bash -lc 'cd android && ./gradlew -PctosSecretsUiFixture=true -PctosSshMemoryProbe=true -Ptarget-platform=android-arm64 :app:assembleSecretsFixtureRelease :app:assembleSecretsFixtureReleaseAndroidTest :app:lintSecretsFixtureRelease --console=plain'
```

实现后先严格warning编译、fixture lint、arm64 ELF interpreter/依赖检查和 PT_LOAD 16KiB 对齐、APK仅fixture含probe/普通APK不含probe验证。真实设备阶段由主会话另确认授权，以明确serial安装独立fixture并选择该单个测试类。仅adb shell身份运行binary不证明App UID/SELinux兼容。

### G0 可验证 AC 与后续 gate

| AC | 实际断言 | 允许宣称 |
| --- | --- | --- |
| G0-01 构建/分发 | API28 NDK strict compile、arm64 PIE、16KiB ELF PT_LOAD alignment、无 glibc/OpenSSH依赖；probe仅opt-in fixture APK | 构建/打包通过；不是Android设备或原生16KiB实测 |
| G0-02 匿名对象/锁页 | App UID下 ASharedMemory/create/mmap/实际mlock成功，记录backend/API/page size/申请尺寸/RLIMIT；失败不降级 | 该设备/尺寸内存能力通过 |
| G0-03 文件访问形状 | 按Linux实际需求分别验证 reopen-independent offsets、fstat、stdio、append/replace与必要truncate；不满足逐项明确失败 | 仅通过的POSIX形状；不能自动适配OpenSSH |
| G0-04 受管exec | 固定APK绝对路径、自有child PID、FD白名单、同合成generation、非白名单FD不可见；成功/超时均reap | 自有Android exec继承/清理，不是ProxyJump/服务 |
| G0-05 撤销/清理 | 撤销后新请求拒绝、自己和child明确同步观察擦除，FD/map清理、无常规明文对象文件；已映射页/缓冲副本边界分列 | 此probe拥有的能力生命周期，不是任意sameUID隔离 |
| G0-06 错误/界限 | 尺寸0/超界、缺失请求、旧代次、主动取消/timeout、mlock失败均明确失败且资源收回；日志不输出payload | probe边界，没有prod broker或已认证transport证据 |

G0完成后，G1仍需主会话固定可追溯 OpenSSH/OpenSSL版本、hash、许可证和 Bionic cross configure/make；注册新的native构建依赖。G2再用同版本 stock与受约束client重放上述真实六组（不能用自写函数代替上游），补入拒绝本地命令/信任绕过的解析执行边界。G3才接公共库 typed能力、每跳主机确认/私钥分阶段释放、encrypted known_hosts proposal CAS、真实合成SSH服务完整KEX/auth/PTY/转发以及锁库时已认证transport存续。不得因为G0/G1通过启用尚无生产语义validator的混合库恢复。

### 公共库当前可接入点

`SshVaultAdapter.java:52–82` 在请求及使用两个阶段调用真实 `HostKeyGate`，私钥/凭据按连接、目标、密钥身份绑定；`64–69`提供文件视图请求；`104–117`要求精确 trust proposal批准。`AppSecrets.java:145–170`提供SSH validator/reviewer注册，缺失时返回`VAULT_SSH_VALIDATION_REQUIRED`。这些是既有生产公共合同，不是OpenSSH协议gate已实现；不能把测试dummy validator直接注册成生产通过实现。禁止另建KDF/明文目录/与TOTP平行库。

## External references

以下是既有研究记录中的primary来源，本轮未重新访问或选择当前版本：

- OpenSSH10.0p2官方归档和固定源码：[archive](https://cdn.openbsd.org/pub/OpenBSD/OpenSSH/portable/openssh-10.0p2.tar.gz)、[LICENCE](https://raw.githubusercontent.com/openssh/openssh-portable/V_10_0_P2/LICENCE)。这里只复用已固定Linux对照，不据此宣布Android产品选版。
- [Android NDK memory API](https://developer.android.com/ndk/reference/group/memory)。本轮API证据来自项目本地NDK27头文件，而不是未经访问的网页。
- [Linux memfd_create](https://man7.org/linux/man-pages/man2/memfd_create.2.html)。匿名FD本身不证明锁页、无swap或Android兼容。

## Related specs

- `.trellis/spec/backend/{directory-structure.md,host-bridge.md,quality-guidelines.md}`：Java拥有资源与生命周期；native child不重新进入JVM；严格warning与实际设备授权/分层证据。
- `.trellis/spec/backend/app-secrets.md:185–190,280–281`：缺失SSH语义provider默认拒绝，不用简化parser或独立秘密库；Linux研究不替代Android/native验收。
- `.trellis/spec/guides/{cross-layer-thinking-guide.md,code-reuse-thinking-guide.md}`：保留既有公共 typed接口和真实上游权威，不复制第二套配置语义。
- `.trellis/tasks/10-08-ssh-terminal/design.md:207–211`：128文件、深度8、1MiB/文件、8MiB总量、8跳等工程上限与完整native前置gate；资源上限不是本probe16对象/16KiB的研究夹具参数。
- `design/constraints.md`、`design/portable-workbench.md`：当前授权、显式目标和App网络权限；无Root/其他App或系统配置调整。

## Caveats / Not Found

- 本轮没有执行任何构建/测试、安装、网络连接或读取真实配置；上述AC都是待执行检查。
- 项目源文件/配置没有 OpenSSH/SSL cross-build入口，研究固定归档只曾放/tmp；`.devhome`搜索未发现 OpenSSH源码/归档或供NDK使用的libcrypto开发产物。Chaquopy已有cryptography不能推定可作为OpenSSH C headers/link依赖。未探测全部/tmp或宿主系统安装，避免把旧缓存存在当可复现入口。
- 没有Android API28/29实机、16KiB真实设备、native每跳完整登录、host-key桥、askpass/keyboard-interactive、生产SSH文件validator、受管服务/锁库transport集成证据。
- Linuxshim的passthrough、固定FD198、全量含私钥bootstrap和简单遍历检查是可信合成夹具假设，不构成产品安全实现。文件图完整不等于各跳可读全部秘密。
- ASharedMemory的实现后端/权限/锁页/stdio/截断行为尚未执行核对；不能把mmap/exec单项通过报告为无明文磁盘OpenSSH gate通过。若要求的语义无法达到，回规划，不用普通磁盘暂存或静默换Dart/Python客户端。

# SSH 内存文件访问探针：2026-10-10

本文件是公共底座任务的研究证据，由 `design/app-authentication.md` 作为领域入口引用。只新增本任务 `research/ssh-memory-*`；没有修改 Android/Flutter/Gradle、增加产品 OpenSSH 依赖、连接设备或使用真实 SSH 账号。

## 结论及关卡状态

主会话独立复核：先前 agent 所报 `/tmp/ctos-ssh-memory-zq63u83y` 在主会话检查时已不可访问，未据此假定原始日志仍存在。主会话重新下载相同官方档案、核对固定 hash，并在全新 `/tmp/ctos-ssh-memory-l5y_72nj` 执行本 runner，实际退出码 0、六组 PASS。可读的非秘密结果已保存为 [ssh-memory-validation.log](ssh-memory-validation.log)，探针 C/Python 源码 hash 与原记录一致。新目录的 binary hash 因构建路径不同而变化；不将旧 `/tmp` 路径作为当前可访问证据。

固定 OpenSSH 10.0p2 源码的 Linux/glibc 宿主探针已经执行，六组检查通过。匿名内存对象能够覆盖所测 config/Include、私钥解析、known_hosts 读写和实际 ProxyJump 执行子进程的文件访问。上游自身 glob 的目录回调可保留其匹配/排序逻辑，底座不必向普通磁盘解密 staging。

这是文件访问形状的可行性证据，尚不是 Android native 客户端交付或安全授权实现。底座可以采用下文逻辑对象/受限快照/条件写回接口，不能冻结为“直接给每个 ssh 子进程全部秘密 FD”。Android/Bionic/API 28/arm64/16 KiB、完整登录、每跳主机核验及已建立 transport 锁库后持续运行仍须后续技术关卡；失败须回规划。

## 实际版本、来源和许可证

- 官方源码：[OpenSSH 10.0p2 tar.gz](https://cdn.openbsd.org/pub/OpenBSD/OpenSSH/portable/openssh-10.0p2.tar.gz)，来自 [官方 portable 镜像入口](https://www.openssh.org/portable.html)。SHA-256：`021a2e709a0edf4250b1256bd5a9e500411a90dddabea830ed59cef90eb9d85c`。
- 此档案内部目录名实际是 `openssh-10.0p1`，但 `version.h` 定义 `SSH_VERSION "OpenSSH_10.0"`、`SSH_PORTABLE "p2"`，源码构建的是 10.0p2。runner 同时检查 archive hash 和该定义；不能仅凭目录名判版本。未执行 PGP 签名验证，记录的 hash 来自此次官方 HTTPS 下载，并用于后续复现固定输入。
- OpenSSH 采用各组成文件的 BSD/其他宽松许可证，完整依据为固定版本 [LICENCE](https://raw.githubusercontent.com/openssh/openssh-portable/V_10_0_P2/LICENCE)。归档完整源码只留在 `/tmp`，没有复制上游源码到仓库。后续 APK 若引入需保留完整通知并登记项目依赖。
- 宿主链接的 OpenSSL 为 `3.5.7`（`pkg-config --modversion openssl` 实测）；OpenSSL 3.x 采用 [Apache License 2.0](https://openssl-library.org/source/license/index.html)。此次使用宿主已安装 libcrypto，没有下载或给产品打包该版本。
- 宿主：`Linux 6.12.111+deb13-amd64 x86_64 GNU/Linux`；`cc (Debian 14.2.0-19) 14.2.0`。使用宿主 gcc/libcrypto 是为了核验 Linux/glibc 文件钩子；不是 hako/NDK 或 Android/Bionic 构建。系统已有 `/usr/bin/ssh` 为 Debian 10.0p2 衍生版本，但正式正/负对照均使用此次固定源码构建。

## 复现入口与已执行命令

持久源码：[ssh-memory-native.c](ssh-memory-native.c)、[ssh-memory-run.py](ssh-memory-run.py)。外部要求：Linux、Python 3.12+（tar 的 data filter）、C 编译器/make/OpenSSL 开发库、足够的 mlock 限额及 loopback socket 权限。runner 不下载依赖、不读取个人 `~/.ssh`，不安装任何二进制。

从项目目录下载固定档案后执行（档案必须匹配上述 hash）：

```sh
curl --fail --location --proto '=https' --tlsv1.2 \
  --output /tmp/ctos-ssh-memory-probe/openssh-10.0p2.tar.gz \
  https://cdn.openbsd.org/pub/OpenBSD/OpenSSH/portable/openssh-10.0p2.tar.gz
python3 .trellis/tasks/10-08-app-authentication/research/ssh-memory-run.py \
  --archive /tmp/ctos-ssh-memory-probe/openssh-10.0p2.tar.gz --log
```

runner 每次默认创建新的 `/tmp/ctos-ssh-memory-*`；可用 `--workdir` 重用自己创建的构建目录。可信复现应使用默认的新目录；重用目录不是对现有源码修改的完整性证明。此次先使用 `/tmp/ctos-ssh-memory-probe` 迭代，随后按上述命令进行了独立全新目录构建/复现，路径是 `/tmp/ctos-ssh-memory-zq63u83y`。

runner 实际执行的构建步骤：

```sh
./configure --without-zlib --without-pam --without-selinux \
  --without-kerberos5 --disable-pkcs11 --disable-security-key
make -j4 ssh ssh-keygen
```

适配器与 driver 都用 `cc -std=c11 -Wall -Wextra -Werror -O2 -I. -Iopenbsd-compat` 编译，driver 追加 `-DSSH_MEMORY_DRIVER`。runner 记录了完整编译/链接调用；通过 GNU ld `--wrap` 钩住 `open/fopen/stat/access/mkdir/mkstemp/link/rename/unlink/getpwuid/closefrom` 和上游 `_ssh__compat_glob/_ssh__compat_globfree`，与实际 `ssh.o/readconf.o/.../libssh.a/libopenbsd-compat.a` 链接，没有修改 upstream 源文件。`ssh-stock` 是同一源码的原版对照。

初次普通 sandbox 下 DNS 及 socket 创建被限制；下载和 loopback 探针使用获自动批准的 scoped escalation。未访问真实目标，监听固定为 `127.0.0.1` 的系统分配临时端口。整个 probe subprocess 的 runner 超时为 20 秒；编译步骤各有界为 120 秒。

附加实际检查：`python3 -m py_compile .../ssh-memory-run.py`、`git diff --check` 通过；原生新增源文件两种编译模式均通过严格 warning 检查。没有运行 Flutter、Android lint、NDK 构建或设备检查，因未修改对应产品代码。

## 实际通过的场景

| 场景 | 执行对象及断言 | 边界 |
| --- | --- | --- |
| config/嵌套相对 Include/通配顺序 | 实际 patched `ssh -G -F /ctos-memory/home/.ssh/config probe`；故意按 20、10 顺序构建 manifest，上游 glob 按词法排序得到 `User first`；被 Include 文件里的 `Include nested/options.conf` 从逻辑 `~/.ssh` 根读取，得到 `Port 2222` 和 loopback HostName | 上游 parser/glob 实际执行；只覆盖这组路径/匹配夹具 |
| 原版匿名 FD 负对照 | stock 同版 `ssh -G -F /proc/self/fd/198 probe` 在启动关闭继承 FD 后失败并包含 `No such file or directory` | 单靠继承 FD 路径不能替代受管启动/文件接口 |
| IdentityFile | driver 调真实 `sshkey_generate` 在内存生成 Ed25519；真实 `sshkey_private_to_fileblob` 序列化；真实 `authfile.c` 的 `sshkey_load_private_type` 经虚拟路径读取，密钥身份一致 | 单个未加密 Ed25519 私钥格式；没有声称 RSA/ECDSA/加密私钥或远端登录通过 |
| known_hosts | 真实 `hostfile.c` 追加、读取、`hostfile_replace_entries` 的 mkstemp/link/rename 替换均在匿名对象发生；新 key 为 HOST_OK，旧 key 为 HOST_CHANGED；有界 pread 取得回传 proposal 字节 | 真实上游文件逻辑；没有 AppSecrets 加密提交、用户主机审批或 CAS 实装 |
| ProxyJump 继承/失败 | 真正的 parent+exec jump client 从同代次 memfd bootstrap 构建视图；各自打印非秘密 config-read PID/代次；jump 实际发 SSH identification 到独立 loopback listener；listener 随即关闭，连接失败，整组终止/reap | 只到协议 identification；没有完成 KEX、主机核验、认证、转发或远端终端 |
| ProxyJump 取消 | identification 到达时对本 probe 拥有的进程组发送 SIGTERM；driver 作为 subreaper 回收 primary 和 proxy descendant | 没有按进程名/端口停止进程；没有测 Android 前台服务生命周期 |
| 错误与锁定清理 | 缺失对象 ENOENT、`/../` 遍历 EINVAL、revoked 后新私钥读 EACCES、迟到信任追加拒绝；本进程匿名对象擦除/截断，已持有 local descriptor 读回 EOF；关闭 bootstrap/key 对象 | 现有别的进程已持有的 FD/stdio readahead/解析后的 native 私钥不受此单进程测试保证 |

全新复现的 `/tmp/ctos-ssh-memory-zq63u83y/results.log` 输出：

```text
PASS pinned OpenSSH -G: config, nested root-relative Include, lexical glob
PASS negative control: unadapted inherited FD is rejected
PASS native IdentityFile parse and known_hosts append/read/replace/proposal
PASS ProxyJump parent/exec-child snapshot inheritance and handshake failure cleanup
PASS ProxyJump owned cancellation and descendant reap
PASS missing/traversal failure, lock rejection, local descriptor wipe and cleanup
Could not stat /ctos-memory/home/.ssh: Permission denied
```

最后一行来自故意 revoke 后的真实 hostfile 失败路径，是预期诊断，退出码仍为 0。stdout/stderr 只记录合成路径/状态，不输出私钥/口令/known_hosts 内容。

复现目录下保留 `configure.log/build.log/vfs-compile.log/driver-compile.log/ssh-link.log/driver-link.log/results.log`。该 `/tmp` 证据可能随宿主清理丢失，持久源码和本结果记录支持重新执行。

| 产物 | 全新复现 SHA-256 |
| --- | --- |
| ssh-memory-native.c | `1056035019e770048adaa301d53bc8da9bfa603fd49ce19636be709c652fb92f` |
| ssh-memory-run.py | `6084b1f998c71343b63109d825ddf407fa57019b06a3cb87cde5950a254da107` |
| /tmp/ctos-ssh-memory-zq63u83y/ssh-stock | `3fa0d5d773a632000783493cbec5cc00b89561fc9a2f4fec91153248df9fdb30` |
| /tmp/ctos-ssh-memory-zq63u83y/ssh-memory | `03df84ca5dc05564d093d95ef8e6aec2475fd614daaeb228f3447d06fa95b61d` |
| /tmp/ctos-ssh-memory-zq63u83y/memory-probe | `c373246385bd2ec9dea5c7d885e66f0c1423555a70bf67c552fef0f03a541aa1` |

构建路径进入调试符号，所以不同 `/tmp` 目录二进制 hash 不必相同；源档案和本探针源码 hash 固定才是复现输入。

## 给公共底座的接口约束

以下是基于探针的设计建议，尚未实现为公共库/native 生产接口：

1. 便携库对象包含 `namespace/type/objectId/logicalPath/contentDigest/resourceIdentity`。SSH manifest 保存完整引用闭包及一致代次，禁止将运行 FD、源绝对路径或 SAF grant 存成迁移后可用引用；目录由逻辑路径合成，不另建明文目录。
2. 受限视图请求绑定 `vaultId/generation/sessionId/requestId/purpose`（evaluate/connect）；宿主持有不可变 snapshot 和取消/锁定能力。每个对象读取必须验证类型、请求、当前库会话及业务授权，原生从 fd/stream 获取有限字节，不获得库密钥、任意解密或秘密枚举能力。
3. 求值阶段可使用已解锁的 config/Include/必要公钥或证书；私钥和密码不能随初始快照无条件给所有跳板。每跳 host-key gate 完成后，才向对应 identity/host/user/request 释放私钥/auth 对象。完整引用闭包与全部秘密可读是两种不同权限。
4. SSH owner 映射自身虚拟 home、`.ssh` 和受控逻辑工作目录。Include 相对基准保持上游 `~/.ssh` 语义；裸相对 IdentityFile 按受控工作目录定义并另测。通配使用上游 glob 的目录回调，不能另写一套简化 parser/glob；每跳 exec 客户端同样受约束。
5. known_hosts 的匿名更新只是 proposal：`{requestId, vaultId, generation, trustObjectId, baseContentDigest, approvedHostPortFingerprint, newBytes}`。宿主复核业务信任批准、对象身份、大小/格式和当前代次/摘要，再作 encrypted CAS/同代次事务；锁定/取消/换代/旧 base digest 必须拒绝。本文只验证能取得修改字节，没有实现事务。
6. 视图 owner 在失败/取消/锁定后撤销新文件/认证请求，回收未完成链并擦除自己拥有的内存/FD；已完成认证的 transport 应另有生命周期，关闭秘密文件 capability 后可继续。不能因为关闭父 FD 就声称其他进程复制的 FD 或已经解析的秘密被擦除。

## 源码证据与仍需补齐的边界

[固定 ssh.c](https://raw.githubusercontent.com/openssh/openssh-portable/V_10_0_P2/ssh.c) 有启动 `closefrom(3)`，ProxyJump 会生成包含 `-F` 的 child 命令；探针确实执行到 jump child。生产版需受管描述符/IPC、明确 APK 客户端路径和每跳授权，研究用 FD 198 穿过 shell 的办法不能直接作为能力安全方案。

[固定 readconf.c](https://raw.githubusercontent.com/openssh/openssh-portable/V_10_0_P2/readconf.c) 会锚定用户 Include、调用 glob 及 fopen/fstat；本探针替换目录操作，保留上游自身 glob。构建使用的是其 compat glob ABI，并非 glibc glob_t；初次只 wrap libc glob 未生效，已修正并完成全新重放。

[固定 hostfile.c](https://raw.githubusercontent.com/openssh/openssh-portable/V_10_0_P2/hostfile.c) 的写入路径同时涉及追加及 mkstemp/link/rename/unlink。本探针实现了其所需内存操作并真实跑过替换；生产版不应把任意 native 写文件当作信任批准。

[固定 sshconnect2.c](https://raw.githubusercontent.com/openssh/openssh-portable/V_10_0_P2/sshconnect2.c) 存在加载/签名后释放及 `pubkey_cleanup` 路径；这只能提供后续验证入口，不能据源码宣称认证后所有副本已擦除。必须用完整连接测试证明 lock 期间 transport 继续、重认证/迟到私钥请求拒绝、原生私钥生命周期满足约束。

探针采用 memfd+mmap+mlock，所有合成 config/key/trust 内容仅进入匿名内存；从未创建这些内容的普通临时文件。memfd 自身并不保证无法 swap，依据 [Linux memfd_create](https://man7.org/linux/man-pages/man2/memfd_create.2.html)；探针强制 mlock 成功，失败即停止，不把匿名 FD 等同“永不落物理存储”。生产 Android 是否有可用共享内存/锁页及对应策略还需验证。[Android NDK memory](https://developer.android.com/ndk/reference/group/memory) 只提供相关内存 API，不能证明该 OpenSSH/NDK/ROM 组合兼容。

此研究适配器刻意保留非虚拟路径的 libc passthrough，用于宿主程序正常装载；不是恶意 config 的沙箱。manifest、固定 FD、bootstrap 内容、POSIX 权限与目录均是可信合成夹具；没有并发/broker 身份校验、全套路径规范化、秘密分阶段授权、写入硬限额或密文 CAS。生产不得复用这些简化信任假设，也不能把合成完整快照交给普通 Shell/Python。直接映射的页面擦除不证明托管运行时、stdio 缓冲、OpenSSL/OpenSSH 已解析对象或同 UID/Root 隔离。

待验证还包括：API 28 的可用内存实现、Bionic/arm64/16 KiB 构建和加载、SELinux `/proc`/FD 访问、每跳 host key/身份桥、完整 auth/key 格式矩阵、裸相对/tilde/%token/其他 glob/Match 路径、并发会话隔离、跨进程即时 revoke、已认证 transport 的 lock 行为、真实加密信任写回及崩溃/超限处理。没有因宿主探针通过而删去这些关卡。

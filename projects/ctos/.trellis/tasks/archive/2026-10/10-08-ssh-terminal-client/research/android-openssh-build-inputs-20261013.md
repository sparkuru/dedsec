# Research: G1 Android OpenSSH/OpenSSL 固定构建输入

- Query: 官方当前 stable portable OpenSSH、安全修复、归档 hash/许可证；OpenSSL Android arm64/API28 构建入口、受支持版本、host prerequisites；可复现 hako 构建建议与剩余 native gate。
- Scope: mixed；读取项目既有设计/G0研究和官方发布、固定 tag 源码/构建文档；不下载源码 payload、不构建、不操作设备、不读取个人SSH文件。
- Date: 2026-10-13

## Findings

### 候选 pin 与证据状态

研究建议 **OpenSSH10.6p1 + OpenSSL3.5.9**，待主会话确定后才进入依赖/spec/产品构建。OpenSSH10.6p1于2026-10-06发布；OpenSSL官方当前稳定最高major为4.0.3，但建议使用最新3.5LTS补丁3.5.9（2026-09-29），支持至2030-04-08；4.1.0-beta2是预发布。这里的LTS选择是维护期和既有3.x集成风险的研究判断，不是“OpenSSL4不兼容”的结论。[OpenSSH10.6官方发布](https://www.openssh.org/txt/release-10.6)、[OpenSSL官方下载](https://openssl-library.org/source/)、[支持策略](https://openssl-library.org/policies/releasestrat/)。

既有Linux对照仍为10.0p2/当时宿主OpenSSL3.5.7，记录在 `../10-08-app-authentication/research/ssh-memory-results.md:15–19`；本研究不改旧pin/日志。新版本必须重新建立同版本stock/受约束client对照，不能用旧六组结果证明10.6p1/Android通过。

| 输入 | 精确定位与官方校验 | 本轮验证到什么 |
| --- | --- | --- |
| OpenSSH归档 | `https://cdn.openbsd.org/pub/OpenBSD/OpenSSH/portable/openssh-10.6p1.tar.gz`；官方SHA256 base64 `qdyVZd/+hkD2TYY80poyvEo9vewFZqf8RMXW7nZ9Xzk=`；转换hex **`a9dc9565dffe8640f64d863cd29a32bc4a3dbdec0566a7fc44c5d6ee767d5f39`** | 官方[release-10.6](https://www.openssh.org/txt/release-10.6)第296–303行可读；本地只执行Python base64 decode，没有取得归档或计算payload hash |
| OpenSSH签名 | 同归档URL后缀`.asc`；`https://cdn.openbsd.org/pub/OpenBSD/OpenSSH/RELEASE_KEY.asc` | 官方发布页提供签名公钥入口；未取得/验证PGP签名，不宣称signature verified |
| OpenSSH固定源码 | 官方镜像tag `V_10_6_P1`：`https://raw.githubusercontent.com/openssh/openssh-portable/V_10_6_P1/` | INSTALL/LICENCE/configure.ac/ssh.c/readconf.c/readpass.c/pathnames.h/Makefile.in实际网页读取成功；tag不是尚未取得归档的完整性证明 |
| OpenSSL归档 | `https://github.com/openssl/openssl/releases/download/openssl-3.5.9/openssl-3.5.9.tar.gz`；SHA256 **`603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a`** | 官方[expanded_assets](https://github.com/openssl/openssl/releases/expanded_assets/openssl-3.5.9)第0–4行明确给该归档asset digest；无需依赖第三方hash |
| OpenSSL checksum附件 | 上述归档URL后缀`.sha256` | 官方网站有链接，但web读取附件返回Internal Error；expanded_assets也列出该附件自身digest `f482705825eb43b47ce08d5cf4287ae062cdbada1a142d728a8d89c387300bf0`，**这是87字节checksum附件的hash，不是源码归档hash** |
| OpenSSL签名 | 同归档URL后缀`.asc`，签名公钥入口`https://openssl-library.org/source/pubkeys.asc` | 官方下载页给主key fingerprint `B146647E45A7B33947AB226B2A2C87D161692D40`；未下载/验签 |
| OpenSSL固定源码 | 官方tag `openssl-3.5.9`：`https://raw.githubusercontent.com/openssl/openssl/openssl-3.5.9/` | NOTES-ANDROID/INSTALL/NOTES-PERL/Configurations/15-android.conf/NEWS/LICENSE实际读取成功 |

主会话实际取得归档时仍须独立核对本表归档hash、内含版本及许可证；记录取得方式与签名是否验证。不要把GitHub自动生成的“Source code (tar.gz)”当已校验的发布asset，也不要沿用旧10.0p2的`openssh-10.0p1`解压目录假设。

OpenSSL元数据读取证据：本agent的web.open上述expanded_assets、lineno=0返回Total lines=30，L0为`openssl-3.5.9.tar.gz`，L2为`sha256:603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a`，L4为`50.8 MB 2026-09-29T14:08:32Z`。主会话独立web解析同URL未读到该字段，存在可见性差异；主会话应实际取得官方checksum核对再冻结构建输入，不能把agent元数据报告当payload独立校验。

### 安全修复与版本影响

10.6包含客户端相关压缩侧信道缓解、膨胀包长度检查、命令行用户名特殊字符检查；另有大量sshd/sftp修复，本产品不因这些工具存在而增加服务端/SFTP入口。用户名限制不覆盖config中的User，仍不能替代ctOS原生禁止本地命令及受管ProxyJump安全边界。[官方10.6说明](https://www.openssh.org/txt/release-10.6)。

OpenSSL3.5.9官方NEWS标为安全补丁，含EC/SM2时序及AES-SIV失败报告等修复，还有TLS/QUIC等修复。SSH链接libcrypto不代表全部TLS漏洞可由SSH触发；不基于这种区分继续采用旧补丁版。[固定NEWS](https://raw.githubusercontent.com/openssl/openssl/openssl-3.5.9/NEWS.md)。

### 许可证与打包输入

- OpenSSH有BSD/ISC/MIT及公共领域等组成，必须保存该固定版本完整[LICENCE](https://raw.githubusercontent.com/openssh/openssh-portable/V_10_6_P1/LICENCE)，不能只放“BSD”一句话；项目修改版标记来源/patch说明。
- OpenSSL3.5.9完整[LICENSE.txt](https://raw.githubusercontent.com/openssl/openssl/openssl-3.5.9/LICENSE.txt)为Apache2.0。主会话从取得归档收集实际LICENSE/NOTICE及有编译组成的附带声明，保留各份hash；当前未下载通知文件或加入APK资产。
- 静态libcrypto进PIE也属于分发依赖，应登记其版本/hash/配置；不能借Chaquopy cryptography已有资产和许可证就宣称覆盖另一套OpenSSL构建。

### 官方 Android 支持程度

OpenSSL固定3.5.9[NOTES-ANDROID](https://raw.githubusercontent.com/openssl/openssl/openssl-3.5.9/NOTES-ANDROID.md)有正式cross-build说明：指定`android-arm64`，构建全过程使用`ANDROID_NDK_ROOT`与对应NDK的PATH，并用`-D__ANDROID_API__=28`固定最低API；不能只让Configure自动选latest。固定[15-android.conf](https://raw.githubusercontent.com/openssl/openssl/openssl-3.5.9/Configurations/15-android.conf)第217–222行提供aarch64目标。这是上游构建支持，不是ctOS NDK27/设备已通过。

OpenSSH固定[configure.ac](https://raw.githubusercontent.com/openssh/openssh-portable/V_10_6_P1/configure.ac)第622–626行有Android include-order兼容，第736–739行有android host分支；cross时若干运行时检查跳过。需用Android host triple触发正确平台分支，并记录所有跳过项；无真实NDK编译前不能列出“必须打多少补丁”或宣称无需补丁。

### host prerequisites（待主会话实际检查）

项目入口仍是`./hako`，固定amd64镜像、NDK27.0.12077973、API28 clang、arm64、16KiB ELF。`hako:135–148`仅挂项目到`/app`，宿主/tmp未挂载；归档/解压/build prefix宜在ignored `build/ssh-native-g1/<run-id>/`，非秘密日志复制到/tmp。正常生产目录/个人HOME不参与构建。

- Host：make、Perl5/core modules、tar/gzip、hash工具；Perl Text::Template上游含fallback副本，先检查不额外联网安装。[固定INSTALL](https://raw.githubusercontent.com/openssl/openssl/openssl-3.5.9/INSTALL.md)、[NOTES-PERL](https://raw.githubusercontent.com/openssl/openssl/openssl-3.5.9/NOTES-PERL.md)。本轮未进Docker检查这些命令是否已在镜像中。
- NDK：`aarch64-linux-android28-clang`、llvm-ar/ranlib/readelf及Bionic sysroot；不可用host pkg-config的libcrypto/libssl路径。
- OpenSSH发布归档应含configure；仅改configure.ac/由git生成才需要autoreconf。固定[INSTALL](https://raw.githubusercontent.com/openssh/openssh-portable/V_10_6_P1/INSTALL)第90–95行要求autoconf2.69/automake1.16.1或更新。缺少时记录并用项目私有工具链方案，不改宿主全局配置。
- OpenSSH依赖实际Android libcrypto（PIC）。不要用`--without-openssl`“先完成”：该模式算法受限，不能满足已批准RSA/ECDSA/加密私钥矩阵。[固定INSTALL](https://raw.githubusercontent.com/openssh/openssh-portable/V_10_6_P1/INSTALL)。

### 受约束 G1 构建建议（推断，未执行）

采用静态PIC **libcrypto.a** 链入Android PIE，保留Bionic动态系统链接；这不等于完全静态ELF。先避免独立libcrypto.so/动态provider打包混淆，再实际检查provider初始化、RAND和各私钥算法。保留RSA/EC/Ed25519等算法；不使用no-deprecated，因为OpenSSH仍会使用兼容API。[OpenSSL固定INSTALL](https://raw.githubusercontent.com/openssl/openssl/openssl-3.5.9/INSTALL.md)。

在hako单次受控构建中，固定源码hash校验通过后，再按以下argv/环境组织runner；这里不是已存在的构建命令或已通过脚本：

| 步骤 | 候选参数/环境 |
| --- | --- |
| OpenSSL工具链 | `ANDROID_NDK_ROOT=/app/.devhome/android-sdk/ndk/27.0.12077973`，只对子进程PATH加入同NDK`toolchains/llvm/prebuilt/linux-x86_64/bin`；llvm-ar/ranlib由实际配置记录确认 |
| OpenSSL Configure | `perl Configure android-arm64 -D__ANDROID_API__=28 no-shared no-module no-dso -fPIC -Wl,-z,max-page-size=16384 --prefix=<本次私有openssl-prefix> --openssldir=<本次私有openssl-prefix>/ssl --libdir=lib` |
| OpenSSL构建 | `make -j4`，`make install_sw`仅到上述ignored私有prefix；不安装宿主，不在Linux直接运行Android make test并当设备验证 |
| OpenSSH环境 | `CC=<NDK>/bin/aarch64-linux-android28-clang`，`AR=<NDK>/bin/llvm-ar`，`RANLIB=<NDK>/bin/llvm-ranlib`；`CFLAGS=-O2 -fPIE`，`CPPFLAGS=-I<私有openssl-prefix>/include`，`LDFLAGS=-L<私有openssl-prefix>/lib -pie -Wl,-z,max-page-size=16384` |
| OpenSSH Configure | `./configure --build=x86_64-pc-linux-gnu --host=aarch64-linux-android --with-ssl-dir=<私有openssl-prefix> --with-pie --without-rpath --without-pam --without-selinux --without-kerberos5 --disable-pkcs11 --disable-security-key --without-zlib` |
| OpenSSH最小构建 | `make -j4 ssh ssh-keygen`；G1首先保存stock产物/完整命令/config.log/config.h/依赖，不运行make install，不替换dist或加Portable tools |

注意：`--without-zlib`这里只是**第一轮构建实验**减少额外依赖，沿旧Linuxprobe形式；不自动成为生产Compression行为或验收范围删减。main须随后决定用NDK公开zlib或另固定构建来源并验证10.6压缩行为；配置要求不能被静默忽略。PAM/SELinux库构建开关不改变设备SELinux，不禁用远端keyboard-interactive；FIDO/PKCS11本就延后。

no-module/no-dso也是候选构建取舍；静态default provider与所需算法必须实际验证后才能冻结。不能为体积添加no-rsa/no-ec/no-deprecated等缩减批准能力的参数。

官方3.5.9 Android说明未明示默认16KiB；当前master说明3.6起默认16KiB、较旧版本可传link flag。本方案始终显式传flag，最终仍检查每个ELF的PT_LOAD、interpreter、DT_NEEDED和API符号；archive `.a`本身不是runtime ELF对齐证明。[官方Android说明](https://raw.githubusercontent.com/openssl/openssl/master/NOTES-ANDROID.md)。

### 首轮主要 patch/gate（源码证据，不是“已修复”）

| Gate | 固定upstream证据 | ctOS需验证/适配 |
| --- | --- | --- |
| 受管FD/私有home | [ssh.c](https://raw.githubusercontent.com/openssh/openssh-portable/V_10_6_P1/ssh.c):611启动closefrom(3)，627 getpwuid | G0只是内存/exec；G1 stock会清继承FD。生产仅保留受限broker/control白名单，适配虚拟passwd/home，不交全部私钥snapshot |
| 原生配置禁执行 | [readconf.c](https://raw.githubusercontent.com/openssh/openssh-portable/V_10_6_P1/readconf.c):498–525 execute_in_shell，844实际执行Match exec | 在执行前native解析/执行边界拒绝并定位；`ssh -G`也不可直接用于未约束config，禁止先执行再判断 |
| Include/glob | [readconf.c](https://raw.githubusercontent.com/openssh/openssh-portable/V_10_6_P1/readconf.c):2010–2039根相对user SSH目录、glob、递归读 | 保留上游匹配/排序/条件语义；移植旧compat glob hook需复核新configure实际ABI。不能用另写parser或明文目录绕过 |
| ProxyJump派生 | [ssh.c](https://raw.githubusercontent.com/openssh/openssh-portable/V_10_6_P1/ssh.c):1220–1268依据argv0生成代理命令，路径不可用回落ssh | 明确APK绝对客户端路径、受管argv/每跳能力与整链回收；不可依赖PATH“ssh”或将用户数据拼进任意Shell。upstream内部代理不等于用户ProxyCommand放行 |
| 密码/口令控制通道 | [readpass.c](https://raw.githubusercontent.com/openssh/openssh-portable/V_10_6_P1/readpass.c):45–101 askpass fork/pipe，113–181环境选择与TTY读取 | 原生可信请求附连接/跳/epoch/身份，不从提示字符串推导秘密；不经argv/env/远端stdout送密码。stock askpass只给底层入口，未建立AppSecrets桥 |
| 信任文件写回 | 旧Linux真实hostfile结果，加新版本stock/受约束重测 | 内存write proposal不等于批准；同代次/digestCAS/主机确认、取消/锁库/迟到拒绝必须接公共typed事务 |
| 上游对象集合变化 | [Makefile.in](https://raw.githubusercontent.com/openssh/openssh-portable/V_10_6_P1/Makefile.in):85–111 | 10.6 SSHOBJS含ssh-pkcs11.o，库有新PQ对象；旧10.0p2 runner手写link对象表不能直接搬。从新生成Makefile取真实依赖，不静默丢对象/算法 |

G1的AC仅包括：固定输入/hash/许可证、可重复cross configure/build、真实PIC libcrypto与Android arm64 PIE正确链接/16KiB格式、无host glibc或私有系统crypto依赖、stock/受约束同版本产物分开。Android App UID运行、API28/29实机、完整六组内存文件probe、密码/常用加密私钥/每跳主机核验、PTY/转发、已认证transport锁库存续仍分别待测。构建artifact可生成并审阅后再进入设备步骤。

### Exact next input checklist

1. main确定候选版本，按本表官方asset URL取得两个归档、SHA附件/签名/公钥（如选择验签），独立hash匹配后解压；保存tarball目录和`version.h`/OpenSSL version证据。
2. 收集固定归档完整LICENCE/LICENSE/NOTICE及source hash；声明未验签时不把hash校验称PGP验签。
3. 在hako先read-only检查host perl/make、NDK clang/ar/ranlib/readelf及实际版本，确认私有输入/输出路径。无自动apt/pip/CPAN或外部安装hook。
4. main实施受约束build runner，严格固定archive hash/命令/环境/资源期限、fresh run目录、失败保留日志；native自有adapter继续strict warnings，不通过关闭检查吞错误。
5. 最终产物的header/library version一致检查不能只靠cross configure跳过运行检查；在实际Android运行阶段核对crypto runtime version/算法自测。生产与研究artifact/UID/能力保持分开。

## Related local files / specs

- `research/android-gate-20261013.md`：冻结G0建议和API30 memfd/API26 ASharedMemory区别；本轮未修改。
- `../10-08-app-authentication/research/ssh-memory-{native.c,run.py,results.md,validation.log}`：旧同版本Linux六组对照，非Android产品pin。
- `android/app/build.gradle:12–21,75–112`、`hako:135–148`、`design/portable-workbench.md:31–38`：NDK/ABI/API与native目录执行。
- `.trellis/spec/backend/{host-bridge.md,quality-guidelines.md,app-secrets.md}`：host拥有资源、typed授权、生产validator缺失失败关闭、分层验证。
- `.trellis/tasks/10-08-ssh-terminal/{prd.md,design.md,implement.md}`：OpenSSH主路线、禁止本地命令/信任绕过、Include/ProxyJump/常用认证/基本转发必做；失败回规划。

## Caveats / Not Found

- 本轮只web-read官方元数据/文档/固定源码、以及本地base64转换；没有取得tar payload、计算payload/许可证文件hash、PGP验签、构建、安装、设备或实际网络登录。
- OpenSSL直接`.sha256`/`.asc`附件及API release endpoint web读取失败；最终hash依据成功读取的**官方openssl仓库expanded_assets归档asset digest**，不是搜索结果第三方值。不同对象hash已明确区分。
- OpenSSH CDN目录HTML未展示10.6条目（其可读版本仍是旧索引）；官方10.6发布/固定tag可读，archive URL待主会话实际取得确认；读取失败不得静默换镜像payload/版本。
- 没有确认hako包含全部host构建工具；cross configure候选命令未运行，具体Bionic符号、resolver/link依赖、上游警告和所需patch须由实际失败诊断，不猜测填cache值或用`--without-openssl-header-check`放弃一致性。
- 不宣称G0/G1或任何客户端AC已通过；不修改生产validator/路线/普通明文暂存/真实账号授权。研究建议由main审阅固定，再落依赖和设计。

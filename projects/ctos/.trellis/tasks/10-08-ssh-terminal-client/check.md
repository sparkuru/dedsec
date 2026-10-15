# SSH 客户端阶段检查

## 2026-10-15 Java/JNI session-owner fail-closed seam（G3.6）

本轮复核了 Java/C/JNI 的 ownership 边界，并修正 session owner 必须持有
`NativeSessionClaim` 的集成缺口：callback claim 现在从 begin-open 贯穿到 native close
后的 bookkeeping，第二个 session 会被拒绝；failed open 会释放 opening claim，因而
同一 callback 可在 caller 保留资源的前提下重试。关闭阶段按 native close、callback claim
release bookkeeping、role、view、native destroy 分段记录，失败可重试；跨线程只允许
cancel，native I/O/verify/close 仍 owner-thread-only。

`ssh_session_jni.c` 已进入 normal compile closure，但故意让五个 JNI entrypoint 抛
`IOException`。这是对现有 borrowed `struct sshkey *`/`ctos_ssh_view *` API 的安全边界，
不是 runtime 失败被隐藏：JVM tests 使用 fake binding，Android JNI/OpenSSH session 尚未
执行。必须先有 native-owned handle、key ownership、view snapshot/borrow/revoke 与
close/destroy 协议，才可打开真实 JNI。

验证：定向 `SshNativeSessionTest` 通过；full command 的 `BUILD SUCCESSFUL`、JUnit
**189/0/0/0**、callback host=20、`SSH_PRODUCTION_OPENSSH_BUILD gate=pass
fixture=excluded`、`SSH_NATIVE_CLIENT_BUILD gate=pass runtime=not-run
reason=qemu-aarch64-unavailable`、strict compile/lint/三 ABI Release/assemble 均通过。
APK SHA256 为 `21d16325b242427c2ccfbb7aa9cf42c6ca359700dc7b7f52995a95de06b94e72`；
session JNI symbols 可见、forbidden=0，production archive=83 members。

边界：无 qemu、设备、真实 vault、凭据或外部 SSH；task 仍
`in_progress`/`implementation_complete=false`。下一检查必须在真实 native-owned JNI
协议落地后重新覆盖 Android runtime，不得把 fake binding 或 cross-link 当 runtime pass。

## 2026-10-15 production PIC OpenSSH closure 与 native C caller（G3.5）

本轮质量复核修复了两个真实构建/ownership 问题：`libssh.a` 的基线对象是 `-fPIE` 而非
shared-library-safe `-fPIC`，因此 production runner 改为在 scratch build 中调用 pinned
OpenSSH `libssh-pic.a` source target 重编全闭包；同时 `libssh.a` 对
`pkcs11_key_free`/`pkcs11_sign`/`sshsk_sign` 的引用通过 PIC `ssh-pkcs11.o` 与
`ssh-sk-client.o` 纳入归档。native open 改用 CLOEXEC duplicate，保证 attach/setup
失败不消费 caller 原 FD；close 仍保持 callback release → KEX unbind/OpenSSH free → 原 FD，
release failure 保留 client 供同线程重试。

验证结果：

- G3 固定 runner `run.aqN1F2bD`：`SSH_HOSTFILE_CALLER_BUILD gate=pass runtime=not-run
  normal_apk=fixture-caller-not-wired`；pinned fixture caller 仍排除 normal APK。
- `:app:testSshNativeClientHost`：`SSH_NATIVE_CLIENT_BUILD gate=pass runtime=not-run`；
  无 `qemu-aarch64`，所以只完成 AArch64 cross-link。
- `:app:testSshCallbackAdapterHost`：callback gate=20。
- `:app:testReleaseUnitTest :app:compileSshFiles :app:lintRelease :app:assembleRelease`：
  `BUILD SUCCESSFUL`；JUnit **177/0/0/0**，三 ABI Release native build 通过。
- APK SHA256：`805fc28679aa56be85fa3be75a1608701273908a02f7fb04dddb717c4e1b601e`；ZIP
  只有生产 `lib/arm64-v8a/libctos_ssh_files.so`，production archive evidence 为 83
  members，未发现 stock high-level caller symbols。
- runner `bash -n`、ShellCheck 与 `git diff --check` 通过；没有设备、真实 vault、凭据或
  外部 SSH 操作。

本轮证明 source/object/normal-link 与 native C lifecycle boundary，未证明 Android runtime、
JNI/Java caller、userauth/typed credential、PTY/forward/foreground service/UI 或外部 SSH；
task 继续 `in_progress`/`implementation_complete=false`。

## 2026-10-15 pinned OpenSSH caller fixture seam（G3.4）

主会话对 G3.4 变更做了完整复核，并修正了一个 native 错误路径：
`ssh_pinned_client` 在 issuer 成功和失败时都关闭临时 `fmemopen`，避免 issuer failure
泄漏 FILE；replacement scratch 改为有界堆分配，socket 入口要求
`FD_CLOEXEC|O_NONBLOCK|SOCK_STREAM`，protocol/evidence 失败会回滚已 handoff candidate；
修正后重新完成 runner 和项目质量门。

代码与 runner 复核点：

- `ssh_pinned_client_open` 只接受 live role、当前 PID/UID、future BOOTTIME deadline、
  managed public view、expected SHA-256 host fingerprint、callback environment 和 owned
  socket；成功后才附着 FD、设置 nonblocking、绑定 KEX guard 和 verifier。
- `step` 的 read/parse/write 顺序使用真实 pinned `ssh_api`，每次有 bounded read/write，
  记录 partial/EAGAIN，EOF/cancel/deadline 进入 terminal failure；KEX `done` 后 session
  digest callback 只成功一次。
- verifier 复用 issuer 的 upstream classification/formatter；CHANGED/REVOKED/
  CERTIFICATE/INVALID 不进 KEX。KNOWN/NEW 的 managed result 重新比对 role/hop/port、
  classification/outcome/CAS、connection/target/host、key/fingerprint 和双 digest；NEW
  handoff 后 evidence mismatch/failure 会 abort 精确 candidate。
- `ctos_ssh_callback_known_hosts_candidate_abort` 要求同一 event-loop thread/environment，
  成功后幂等；normal `compileSshFiles` 和 APK 不包含 caller/issuer/KEX fixture。

固定 caller runner：

```text
./hako bash -lc 'bash /app/.trellis/tasks/10-08-ssh-terminal-client/research/android-hostfile-issuer-build.sh --baseline /app/build/ssh-native-g1/stock-run.lOHF9Oai'
```

实际结果：`build/ssh-native-g3/run.3PZSbIzM/`，
`SSH_HOSTFILE_CALLER_BUILD gate=pass runtime=not-run normal_apk=not-wired`。固定 manifest/
patch/object hashes、stream-writer/KEX patch application、C11/Werror、两个 AArch64 PIE
link、required symbol 和 stock caller exclusion 均通过；hako 无 `qemu-aarch64`，未执行
ARM binary。没有设备、真实 vault、外部 SSH 或网络操作。

项目质量门：

- `:app:testSshCallbackAdapterHost`：`SSH_CALLBACK_HOST ... gate=pass value=20`。
- `:app:testSshNativeClientHost`：`SSH_NATIVE_CLIENT ... gate=pass value=27`。
- `:app:testReleaseUnitTest :app:compileSshFiles :app:lintRelease :app:assembleRelease`：
  `BUILD SUCCESSFUL`，JUnit XML 为 **177 tests / 0 skipped / 0 failures / 0 errors**。
- Release APK SHA256：`67668e34e2bba40359e43e048404ffeb5645629a130f86e86b2d855a170ffff9`；
  normal `libctos_ssh_files.so` 无 fixture caller/KEX symbols。

边界：这只是 source/object/host fixture seam；生产 `ssh_native_client` 仍
`ENOTSUP`，没有 userauth/credential/PTY/forward/service/UI、Android runtime、设备或外部
SSH 证据，task 仍 `in_progress`/`implementation_complete=false`。未提交。

## 2026-10-15 pinned hostfile issuer seam

实现/检查代理共同范围内的新增 issuer 产物已由主会话补齐并复核：

- `ssh_hostfile_issuer.c` 绑定 live role/deadline/connection/host 与
  `ssh/config`→`ssh/known_hosts` public view root；不访问 vault pathname。
- pinned `load_hostkeys_file()` 复用内存解析，stream-writer patch 复用上游格式；源码
  明确排除 `add_host_to_hostfile`、`hostfile_replace_entries`、
  `hostfile_create_user_ssh_dir`。证书仍 CERTIFICATE fail-closed，但 revoked cert/CA
  先返回 REVOKED；测试覆盖 KNOWN/NEW/CHANGED/REVOKED、root reject、cert revoke、
  one-shot body wipe 与 callback rollback。
- `research/android-hostfile-issuer-build.sh` 固定 approved G1 archive/tree/object
  hashes，应用 patch，严格 C11/Werror 编译 issuer/view/role/callback/test，链接 pinned
  OpenSSH/crypto/PKCS#11/SK closure 为 AArch64 PIE，并检查 symbol/path/normal APK exclusion。

固定命令：

```text
./hako bash -lc 'bash /app/.trellis/tasks/10-08-ssh-terminal-client/research/android-hostfile-issuer-build.sh --baseline /app/build/ssh-native-g1/stock-run.lOHF9Oai'
```

结果：`SSH_HOSTFILE_ISSUER_BUILD gate=pass runtime=not-run normal_apk=not-wired`；
`bash -n` 通过，shellcheck 在容器中不可用。hako 无 `qemu-aarch64`，所以未执行 ARM
fixture runtime；没有设备、真实 vault、外部 SSH 或网络操作。normal APK 与
`ssh_native_client=ENOTSUP` 保持不变，task 仍为 `in_progress`/
`implementation_complete=false`。未提交。

## 2026-10-15 dedicated native→managed known_hosts candidate handoff

在既有 metadata-only HostEvidence/CSHR seam 与 managed AppSecrets CAS writer 之间完成
独立 one-shot handoff 质量门：C/JNI 接口只同步转移 exact role/hop/target/connection/
host/port/key-type/fingerprint、base/replacement digest、candidate ID 和 bounded
replacement body；`SshKnownHostsCandidateHandoff` 只接受同一 concrete provider 的 live
snapshot，并在首次尝试后消费 receiver。native temporary、JNI array 和 managed input
在成功及失败路径清零；duplicate/re-entry/cancel/close/expiry、wrong identity/evidence/
digest、empty/oversize body 及 failed/uncertain CAS 均无 committed 结果。CSHR wire 未增加
known_hosts body，`COMMITTED` 仍仅由现有 guarded AppSecrets update 成功后产生。

本轮固定 hako 全量质量门：

```text
SSH_NATIVE_CLIENT phase=gate state=pass errno=0 value=27
SSH_CALLBACK_HOST phase=known_hosts_candidate state=pass errno=0 value=5
SSH_CALLBACK_HOST phase=gate state=pass errno=0 value=20
JVM 177 tests / 0 failures / 0 errors / 0 skipped
BUILD SUCCESSFUL: compileSshFiles, Release lint, Release assemble
APK SHA256 3a6228f4237380dfc2cd98ccd537c16d4c6e81bc2e64e6205cdd6d556af9ded6
```

ZIP 检查仍未发现 host-only boundary、fixture OpenSSH、launcher 或 KEX object；没有使用
开发板、真实 vault、外部 SSH 或网络。pinned hostfile issuer/production event loop/KEX/
auth/PTY/forward/service/UI 仍开放，native client 缺 issuer 继续 `ENOTSUP`，task 保持
`in_progress`/`implementation_complete=false`。

## 2026-10-15 production source/object closure re-audit

继续核对 approved G1 baseline：`libssh.a` 确实包含 `hostfile.o`、`ssh_api.o`、`packet.o`、
`sshkey.o` 与 pinned KEX objects，但现有 runner 只产出 opt-in fixture，普通
`compileSshFiles` 不消费 ignored `build/` 对象。`hostfile.o` 仍有未收窄的 OpenSSH/
crypto/stdio/filesystem 依赖，尚未形成能排除 stock high-level caller、fork/exec、
ProxyCommand 和 fixture transport 的 production source/object closure。

对象哈希与完整阻塞证据已记录在
[research/production-kex-caller-blocker-20261015.md](research/production-kex-caller-blocker-20261015.md#2026-10-15-sourceobject-closure-re-audit)。本轮无代码、Gradle、APK、设备、网络、真实 vault 或 commit 变更；
`ssh_native_client` 继续保持 `ENOTSUP`，不把 research baseline 升级为生产依赖。

## 2026-10-15 reviewer document/state re-audit

本轮只复核 production-caller blocker 增量的文档、任务状态和链接；没有新增或放宽
native/JNI 行为。下列 managed AppSecrets provider 门禁是本轮 handoff 之前的历史结果：
native client=27、callback host=15、JVM=174/0/0/0、strict `compileSshFiles`、
Release lint/assemble 和 APK SHA256 `718c3934f3e972a3ac6996ab3a54aeae1116ddc3728fed1f751fdcf2619fe624`。

聚焦复核结果：`task.json`、`check.jsonl`/`implement.jsonl` 均通过 JSON 解析及当前
`in_progress`/`implementation_complete=false` 状态断言；任务、设计、研究和 backend
spec 的相对文件链接与 heading anchors 通过；`git diff --check` 通过。设备、真实 vault、
外部 SSH 和网络均未使用。

## 2026-10-15 managed AppSecrets known_hosts CAS provider

在前一节 role/hop-bound HostEvidence/CAS seam 上完成真实 managed writer 质量门：
`snapshotFor` 精确选择 KNOWN_HOSTS object 并保存 snapshot/base digest；candidate
必须是一次性 exact replacement bytes；proof-aware `proposalFor/write` 经
`SshVaultAdapter`、`VaultOperation` 和 `AppSecrets` 的 object identity、vault UUID/
generation/epoch、approval、validator、base/replacement digest 与最终 store guard。
只有底层更新成功后返回 `COMMITTED`。发现 `cancelOperation` 在提交持锁时会与测试闸门
互等，改为并发操作表且先在 monitor 外 revoke；after-claim uncertain-commit 回归通过。

固定 hako 全量质量门 `:app:testSshNativeClientHost :app:testSshCallbackAdapterHost
:app:testReleaseUnitTest :app:lintRelease :app:assembleRelease --rerun-tasks`：
native client gate **27**、callback host gate **15**、JVM **174/0/0/0**，strict
compileSshFiles、三 ABI Release、lint 和 assemble 全部 `BUILD SUCCESSFUL`。APK SHA256
为 `718c3934f3e972a3ac6996ab3a54aeae1116ddc3728fed1f751fdcf2619fe624`；ZIP 无
host-only boundary 或 fixture OpenSSH object，`git diff --check` 通过。

边界：本轮未使用设备/新 Docker/真实用户 vault 或外部 SSH；测试只使用加密 manifest
的 in-memory Store。exact candidate 仍由 managed caller/test 注入，production OpenSSH hostfile issuer、event loop、KEX/auth/
PTY/forward/service/UI 仍开放，task 保持 `in_progress`/`implementation_complete=false`。

## 2026-10-15 role/hop-bound HostEvidence/CAS contract（前一窄增量）

本轮质量门确认 native↔Java HostEvidence/CAS seam 已闭合：C/JNI 重新验证 role token、
hop/target、classification、key type、fingerprint、32-byte base/replacement digest 和
bounded CAS result；KNOWN 只允许 `CAS_NOT_REQUIRED`，NEW 必须经过 exact key-type-bound
proposal、显式 approval 与 exact `COMMITTED` writer result。proposal body 不跨 JNI，失败、
取消和期限竞争清零 native result。JVM 覆盖无 provider/approval、wrong hop、digest、
classification/key-type substitution、stale proposal 和 CAS failure。

验证结果：`:app:testSshNativeClientHost :app:testSshCallbackAdapterHost
:app:testReleaseUnitTest :app:lintRelease :app:assembleRelease --rerun-tasks` 为
`BUILD SUCCESSFUL`；native-client gate=27，callback host gate=15，JUnit XML
`166 tests / 0 failures / 0 errors / 0 skipped`，严格 `compileSshFiles` 与三 ABI
Release 通过。APK SHA256 为
`26747c49c3333eeff65544be9131668699281eaef7736166224b2856106bff06`，正常 APK 未包含
`ssh_native_client`、launcher 或 fixture OpenSSH object。

边界：本节只证明可注入 contract；上节已另行验证具体 managed AppSecrets
known_hosts CAS writer，但仍不证明真实
OpenSSH event-loop caller、partial I/O/EOF、KEX/auth/PTY/forward/service/UI、设备或外部
SSH；task 继续 `in_progress`/`implementation_complete=false`。

## 2026-10-15 production KEX caller fail-closed boundary

当前 pinned source map 证实 `ssh_api` 可支持未来的同进程 incremental caller，但
仓库仍没有 production known_hosts issuer/CAS API，不能安全产生 role-bound host
digest 或 `KNOWN`/`NEW` evidence。因此新增的 `ssh_native_client.[ch]` 只做稳定
host-provider-missing boundary：校验 live role、PID/UID、CLOEXEC/NONBLOCK socket
ownership、callback token/environment、deadline binding 与 step bounds，拒绝错误 role/
无效或不匹配 deadline/malformed input，并保证 provider 缺失时不关闭 caller FD、不生成
fingerprint trust、不触发 KEX guard/completion/credential。

本地严格 host 编译/运行通过：

```text
SSH_NATIVE_CLIENT phase=admission state=pass errno=0 value=27
SSH_NATIVE_CLIENT phase=provider state=pass errno=0 value=1
SSH_NATIVE_CLIENT phase=cleanup state=pass errno=0 value=1
SSH_NATIVE_CLIENT phase=gate state=pass errno=0 value=27
```

该 gate 不是 production OpenSSH caller/KEX 通过；真实 partial socket I/O、EAGAIN、
EOF、cancel/deadline、close retry、inbound NEWKEYS digest/JNI callback 仍待 provider
合同冻结后实现。未执行设备、外部 SSH、vault 或 UI。

同一轮 hako 质量门
`:app:testSshNativeClientHost :app:testSshCallbackAdapterHost :app:testReleaseUnitTest
:app:lintRelease :app:assembleRelease` 为 `BUILD SUCCESSFUL`；`testReleaseUnitTest`
本次为 `UP-TO-DATE`，最新执行基线仍为 162 tests/0 failures/0 errors/0 skipped。
严格 `compileSshFiles`、lint 与三 ABI Release 通过，APK SHA256 为
`28fc98aaf64d1e010cd63a2ba69086b891de6afc7851376ff65c663a8fc0a179`，ZIP 无
`ssh_native_client` 或 fixture OpenSSH object。
随后 `:app:testReleaseUnitTest --rerun-tasks` 强制执行成功，JUnit XML 汇总为 162
tests/0 failures/0 errors/0 skipped。
reviewer follow-up 修正了 provider-missing/cancelled step 在返回前清零 consumed counters
的边界，并以同一固定 27-check host gate、相关 SSH host tests、lint 和 Release 构建复验通过。

## 2026-10-14 managed provider typed handoff seam quality gate

在用户确认的受管 `config/known_hosts` 文件视图、精确 trust proposal/CAS 与 typed
private-key handoff 方向上，本轮实现并复核 bounded managed-provider seam：NEW host
proposal 必须绑定准确 role/hop；password/private-key bytes 必须匹配 host proof 与
signed KEX/NEWKEYS proof；request kind、role、期限、vault operation 和 cancellation
在最终使用前重复检查。缺 provider、wrong kind、wrong hop、null role、错误 JNI 环境
和 cleanup 不确定性均 fail-closed。JNI 复制并清零 Java byte[]，native 失败路径清零
caller-owned output，JNI context 延迟到 native callback slot 移除后释放。

固定 hako 命令：

```text
./hako bash -lc 'cd android && ./gradlew :app:testSshCallbackAdapterHost :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease --console=plain'
```

结果：BUILD SUCCESSFUL；host
`SSH_CALLBACK_HOST phase=thread_reentry state=pass errno=0 value=1`、
`cancel_deadline_cleanup ... value=5`、`credential_bytes ... value=4`、
`gate=pass value=11`；JVM **162 tests, failures=0, errors=0, skipped=0**；严格
`compileSshFiles`、lint 和三 ABI Release native build 通过。APK SHA256：
`f32bbc83a401c9997a3b901eccc4e2e5966808093ee1a3c17851ad62056df9ea`；正常 ZIP 只有
`lib/arm64-v8a/libctos_ssh_files.so`。具体 public file view/CAS writer、真实
OpenSSH event-loop caller、userauth/PTY/forward/service/UI、设备和外部 SSH 仍未验证；
task 继续 `in_progress`，`implementation_complete=false`。

## 2026-10-14 本机 Docker SSH pair 与当前板访问 smoke test

在用户确认目标为 `192.168.9.14:5555` 后，创建仅绑定本机
`192.168.9.5:2222` 的临时 `linuxserver/openssh-server:latest` 容器，使用一次性
`ctos-test` 账号和 Ed25519 pair（公钥 fingerprint
`SHA256:QzXjQEBk1g1dx7uotibsDAJqHM004Yp/dDdNefst9u0`）。`.14` 为 AIO-3568J/Android 11/
arm64；因系统无 `ssh`，推送并执行已固定的 Android arm64 OpenSSH 10.6p1 客户端
（SHA256 `5a3a15e6f4de095419aa369383a8e133861ca2bdff3c521cdb86fa896d81fa16`）。

实际结果：`.14` 完成到 `192.168.9.5:2222` 的连接、KEX、临时 `known_hosts` 主机 key
匹配、公钥认证，并执行唯一固定命令 `printf ctos-14-pair-ok`，exit status `0`；本机
回环登录亦通过。容器、主机 `/tmp` pair、设备 `/data/local/tmp/ctos-ssh-test-*`
均已精确清理。该项是外部临时 endpoint smoke test，不是生产 APK/event loop/provider/
userauth/PTY/forward/service/UI 验收；正式 APK、真实 vault 和真实 SSH 账号均未触碰。

## 2026-10-14 reviewer quality gate: synchronous attached JNI callback adapter

用户已确认生产拓扑为同进程 native event loop，并确认 native event-loop 线程上的
同步、附着 JNI callback。本轮实现 `ssh_callback_adapter.[ch]`、
`ssh_callback_jni.c` 与 `SshNativeCallbackAdapter`：open 绑定 live role 的当前
PID/UID/deadline、当前 pthread 和同一 JavaVM/`JNIEnv`；callback 对跨线程、错误
环境、重入、取消、过期、迟到结果和 Java/native cleanup failure 均 fail-closed。
native cleanup 成功前不释放 Java role，失败保留 binding/role 供重试。生产 JNI 不
携带 unknown-host proposal，也没有 credential provider；NEW host 继续返回
`SSH_HOST_KEY_REVIEW`，credential 在 KEX 前/后分别返回
`SSH_KEX_REQUIRED`/`SSH_CREDENTIAL_PROVIDER`，不触碰 receiver、vault 或 UI。

固定 hako 质量门禁命令：
`:app:testSshCallbackAdapterHost :app:testReleaseUnitTest :app:lintRelease
:app:assembleRelease`，**BUILD SUCCESSFUL**。host 输出：
`SSH_CALLBACK_HOST phase=thread_reentry state=pass errno=0 value=1`、
`SSH_CALLBACK_HOST phase=cancel_deadline_cleanup state=pass errno=0 value=5`、
`SSH_CALLBACK_HOST phase=gate state=pass errno=0 value=6`。JVM 共 **160 tests**，
`failures=0 errors=0 skipped=0`；严格 Android `compileSshFiles` JNI 编译通过。
正常 APK SHA256 为
`fa4a6356bf594cee5245cca24c3faf1af911605c157155488cb24086c34c067e`，SSH 相关条目仅为
生产 `lib/arm64-v8a/libctos_ssh_files.so`，没有独立 KEX/control/policy/upstream/
probe/launcher fixture library。未执行 ARM binary、设备、外部 SSH 或 vault；真实
OpenSSH event-loop caller、provider、userauth、PTY、forward、service、UI 仍开放，
task 继续 `in_progress`/`implementation_complete=false`。

## 2026-10-14 reviewer quality gate: same-process KEX-only launcher fixture

本轮复核了 `ssh_native_launcher.{c,h}`、KEX fixture test、固定 runner 与
Gradle opt-in wiring。admit 只在同进程 client KEX 对象、live native role、
固定非零 host fingerprint 和 canonical `CSRB` 完整长度/字节比较通过后绑定；
role snapshot 重新检查当前 PID/UID、受限元数据和 `CLOCK_BOOTTIME` deadline。
trailing byte、malformed/wrong wire、重复绑定、缺 role/错误 host、签名失败、
NEWKEYS 前和跨 hop 证据均保持 fail-closed。close 要求精确 `(ssh, token)`，
native role close 先于 KEX unbind，失败保留 slot/binding，成功后擦除 slot；
reviewer 补上了过期后 admission 拒绝和成功 close 后 duplicate close 拒绝的
fixture 回归。

主线已独立重跑当前固定 hako 的 fixture cross-build、host `ssh_api` in-memory KEX 运行和
normal `:app:testReleaseUnitTest`（150 项，0 failure/error/skip）、`:app:lintRelease`
和 `:app:assembleRelease`：最终
fixture run 为 `build/ssh-native-kex/run.CYUJUpCa`，host 记录为
`SSH_KEX handshake=27 cleanup=1 gate=27`；固定 run 的 baseline before/after
manifest 一致，artifact SHA256 为
`ad8c73c8acaaefed7b12f693ec6ea78759974a55384c8e500d74a98b5ef140a8`；ARM artifact 未执行。正常 APK SHA256 保持
`538cae5a32499c9ff0aa4dcbca76a2e6637b6186c4177c1785dfdf86e2f843f9`，ZIP 只含
生产 `lib/arm64-v8a/libctos_ssh_files.so`。reviewer 在本地复核了当前 launcher
与 test 的 NDK `-Wall -Wextra -Werror` 编译、`bash -n` 和 `git diff --check`。

本节只计 fixture/host/build seam；未使用设备、外部 SSH 账号/端点、真实 vault、
Root、剪贴板或生产 event loop。父任务仍 `in_progress` 且
`implementation_complete=false`；生产 host-key issuer/callback、typed
credential transport、userauth、PTY、forward、service、UI、child handoff 和
完整客户端验收仍开放。未注入 native role-close 故障，因此“失败保留绑定”由
源码路径审阅和可达错误分支保证，尚无 fault-injection runtime 证据。

## 2026-10-13 reviewer recheck: bounded role lifecycle

本次复核直接修正了两个 fail-closed 边界：native `open`/`snapshot`/`encode`
在拒绝时清除输出，且 snapshot/active 在取得 mutex 后重新读取
`CLOCK_BOOTTIME`；Java `Issued` 绑定创建它的 registry，错误 registry、空记录及
unchecked native callback failure 均在 native cleanup 前拒绝或转为稳定失败，native
cleanup 失败仍保留 Java role。`testSshRoleBootstrapHost` 当前输出
`bootstrap1198/cleanup1/gate1198`，并通过 `-Wall -Wextra -Werror` host 编译。

post-fix `testReleaseUnitTest` 共 **150 项通过、0 failure/error/skip**；同一固定
hako 容器中的 `lintRelease` 与 `assembleRelease` **BUILD SUCCESSFUL**。当前 APK
SHA256 为 `538cae5a32499c9ff0aa4dcbca76a2e6637b6186c4177c1785dfdf86e2f843f9`；
正常 ZIP 仍只包含生产 `lib/arm64-v8a/libctos_ssh_files.so`，未包含独立 role/probe
library。生成库的 `readelf` 可见 `SshNativeRoleBootstrap` 的 `open/owns/release`
三个 JNI 导出；本轮未安装设备或执行 JNI runtime test。

## 2026-10-13 当前生产角色、信任与凭据桥增量

本轮完成了 production-facing Java bridge contract：`SshRoleContext`/`SshRoleRegistry` 固定 owned PID、connection、hop、nonce、binding、public-view 与 deadline；`SshHostKeyAuthority` 将 known/new/changed/revoked/certificate/invalid 分类并对未知 key 要求精确 proposal/review；`SshKexProof` 只接受匹配 role/host 的 signed KEX+NEWKEYS proof；`SshCredentialBridge` 按 password、key passphrase、keyboard-interactive、private key 分型，并在最终 receiver 调用前再次检查 role、host、库操作和撤销状态。缺少 KEX 的失败 release 现在也会关闭一次性 request 并撤销 operation。生产 native launcher、host-key provider 和 OpenSSH callback 尚未接入。

当前实现实际验证：`./hako bash -lc 'cd android && ./gradlew :app:testReleaseUnitTest --console=plain'` **138项通过**；同一固定容器中的 `lintRelease` 与 `assembleRelease` **BUILD SUCCESSFUL**。`./hako flutter analyze` 为 `No issues found`，`./hako flutter test` **221项通过**。host C 文件视图回归为 `SSH_VIEW_HOST cases=426 wipe_checks=1634 state=pass`；host 控制通道回归的 transport/cleanup/gate 固定阶段均为 `state=pass`，计数分别24/1/24。正常 APK `build/app/outputs/flutter-apk/app-release.apk` SHA256 为 `3b82259dd15a0764f156473ff3500137b6e5c7f409ecb32296fd82e4be9ec7ed`，ZIP 只包含生产 `libctos_ssh_files.so`，未包含 memory/upstream/policy/control/kex probe。

过程中的编译引用修正、信任 review digest 断言修正及缺 KEX request 生命周期回归均已在最终结果中重跑；`git diff --check` 通过。以上是本机/固定容器证据，未安装或运行本轮 APK，未使用真实 SSH 账号/端点，也不代表完整协议认证、PTY、forward、service、UI 或联合 AC 通过。

## 2026-10-13 bounded native role bootstrap seam

在 opt-in KEX fixture 内加入 `ssh_role_bootstrap.{c,h}`：native 进程只可为自身 PID/UID 签发有界 CLIENT/JUMP role，保存 hop/connection/nonce/binding/public-view/deadline 元数据；same-owner/same-hop、nonce 和 same-hop binding 重放拒绝，distinct hop 可共用 connection，过期/关闭/view revoke 可回收。`ctos_ssh_role_bootstrap_encode` 只编码 bounded metadata，不携带 argv、shell、路径、FD 或秘密正文。KEX guard 改为消费该 bootstrap token，仍要求 host fingerprint、签名和 NEWKEYS 的完整顺序。

Java `SshNativeRoleBootstrap` 只在显式 `NativeOwnership` 通过后把 wire 解码为既有 role；没有 permissive 默认 ownership，保留 token/UID 供 native callback 绑定。新增 host-only Gradle task `:app:testSshRoleBootstrapHost`，当前实际输出 **bootstrap54/cleanup1/gate54 全部 pass**；严格 C 编译、KEX runner syntax/diff及固定输入 native 构建通过（`build/ssh-native-kex/run.WvNKWswX`），未执行 ARM binary。容量回收修复后，open 在取得角色 mutex 后重新读取 `CLOCK_BOOTTIME`，再做过期回收、重复和容量判断，避免争用时接纳已过期角色；`testReleaseUnitTest` **143项**、lint和正常 `assembleRelease`通过；APK SHA256 `8ceacce92e8dc6432638ad493a3d1b1a77613edd600e5f064600efc7637bbd93`，正式 ZIP 未包含 role/KEX/probe library。

这是 fixture/host seam 和 bounded JNI contract，不是 native launcher、host callback 或完整 OpenSSH 客户端。生产 ownership/open JNI 另有下一节；真实 protocol/auth/PTY/forward/service/UI、设备安装及外部账号/端点仍未实现或验证；无真实 vault mutation。

## 2026-10-13 production JNI role ownership/open seam

正常 `compileSshFiles` 现在把 `ssh_role_bootstrap.c` 与 `ssh_role_jni.c` 编入生产 `libctos_ssh_files.so`。JNI `open` 从 native 自身取得当前 PID/UID，只接受有界 role metadata，生成 canonical `CSRB` wire；JNI `owns` 重新检查 live token、当前 UID、快照 owner，并将传入 wire 与 native canonical re-encode 做字节级比较。新增 JNI `release` 只接受 issued token 与绑定 UID，native registry 再次按当前 PID/UID 查找 live token。Java `productionOwnership()`/`productionRelease()` 都无默认放行，`open()` 仅是 typed metadata wrapper；没有指针、FD、argv、shell、路径、文件正文或秘密接口。

post-fix 固定 hako 环境中 `:app:testReleaseUnitTest` **150项通过**，`lintRelease` 与 `assembleRelease` **BUILD SUCCESSFUL**；严格 Android native compile 通过，生成库 `readelf` 可见 `SshNativeRoleBootstrap` 的 `open/owns/release` JNI 导出。最新正常 APK SHA256 为 `538cae5a32499c9ff0aa4dcbca76a2e6637b6186c4177c1785dfdf86e2f843f9`，ZIP 仍只有一个 `lib/arm64-v8a/libctos_ssh_files.so`，没有独立 role/probe library。

该增量提供 production JNI ownership/open/release seam，但尚未由生产事件循环调用；
仍未实现 native launcher/OpenSSH host callback、协议认证/PTY/转发/服务/UI，未安装
设备或接触外部 SSH 账号/端点。完整 event-loop lifecycle integration 仍开放。

## 真实KEX完成守卫27例当前板通过

固定10.6p1 kex.c/kexgen.c/kexgexc.c加入真实hook：每轮开始撤销旧完成状态，host callback后核对该hop预期公开key，实际sshkey_verify成功后记录同key，收到NEWKEYS并安装入站keys后才给本轮完成证明。缺角色/过期/进程或nonce/连接/hop/key不匹配均拒绝。最多8个native event-loop拥有的角色；当前签发的是合成已知主机key，尚无产品issuer/未知主机审阅、证书/撤销分类或AppSecrets生产HostKeyGate。完成counter不是私钥grant，控制帧或UI不能设置它。

当前板`.14` API30/4KiB/Permissive独立App UID10191/test10192 **27例/1项6.999秒通过，无skip**：RSA2048、ECDSA P256、Ed25519 × curve25519/ecdhnistp256/group14sha256/DH-GEXsha256/sntrup761x25519/mlkem768x25519，18组各含初始及client/server双向rekey；3种key篡改真实signature均失败；未批准host、verifier拒绝、缺role、BOOTTIME到期拒绝；真实有效signature后逐字节送包、尚未NEWKEYS时仍无证明；两hop交错握手并撤销其中一hop不影响另一hop。真实DH-GEX使用test-only upstream内置group14选择器，不读host moduli文件；这不是生产算法选择或所有算法支持声明。

最终native `build/ssh-native-kex/run.uGkaa9Pd`，fixture15秒构建/lint通过（0错误/3既有警告），新增C -Wall/-Wextra/-Werror；固定upstream patched文件保留其原配置下OpenSSL deprecated warnings。main `802ffb446921331cd1722fbfc8c57effe64c4b493aaebf8e86b102c14159009c`，test `63b9efde097ae46bb3c6fbd949242e4d49378b7677af18826a9bc2054718e9f6`；v2/1signer、实际打包.text/.rodata逐字节对照、AArch64 PIE/linker64/LOAD0x4000/仅z/dl/c/无TEXTREL与安装读回通过。固定G1的1240文件manifest前后相同。精确两个fixture卸载Success、自有UID进程为空，正式APK仍3bc762d9…；无网络、真实vault、Root或剪贴板操作。

最终普通release/JVM/lint **41秒通过、133 JVM无失败/错误/skip**；APK `55aef7e487acff41a8670e34a2b06ff4e3436871663fd9bf6a71eab63d63915f`仅本地未安装，ZIP实际排除全部G0/G2/policy/control/KEX探针及专有assets，仅保留生产public-file JNI。当前源码：guard.c `92eef0c3a42391909179ffe8e8e98f6e5d1e26c07733e90e35fd51ac87654b31`、header `bae4eee55a55b9891d910a535e78b74868a6b219066896c92677e5949ba201e6`、native test `94303eca79e173255e3f4b71118ec9b5f9b4184940d0e2f4b48b7fb86d05b1c4`、Java `917a067e6618ec39718844db9fef04276713f93bd450a1f70801c2194f00ec17`、runner `d19408b0692b4d45b5bbc1350ef12b8a2056ea90fe4bf8e7e38aa91eccee7fc4`、patch `50a59606000dc871f1b29e33eeb6d77fb5da456f40526d2871357758b61f8785`。原始证据为临时`/tmp/ctos-ssh-broker-20261013/kex-*`。

失败保留：首次链接缺上游PKCS11/SK对象，补固定链接对象后成功；device首次0.192秒、诊断轮0.194秒均因ssh_api把EXT_INFO交给caller，夹具错误要求type0，接真实kex_input_ext_info后18组及签名拒绝跑通；第三轮4.623秒失败于逐字节identification，upstream测试API需要完整banner，改为完整identification后仅碎片化binary KEX，未放宽签名或NEWKEYS条件。三轮失败不能计gate。其后的 Java role/trust/typed-secret bridge 已在本轮单独完成；真实 native launcher、生产 KEX callback 接线、完整用户认证/PTY/转发/服务/UI/联合恢复与客户端AC仍开放，不提交归档或注册restore provider。

## 原生控制transport24例当前板通过

仅原生控制transport增量：AF_UNIX SOCK_SEQPACKET、CLOEXEC/NONBLOCK、fork前开启SO_PASSCRED，每个recvmsg的SCM_CREDENTIALS绑定owned launch PID/App UID；88字节固定header、32768字节payload、严格顺序、role nonce/connection binding，拒绝损坏、重放、截断、wrong peer与unexpectedFD。LOAN只接一件只读/CLOEXEC/0600/无链接/匿名regular/精确大小FD；形态不证明内容/授权，生产owned blob registry/KEX/private guard尚未接入。拒绝收包关闭所有实际接到的FD并关闭channel，绝不取payload数字当FD。控制信息/nonce/hash/host blob不输出日志。

host **24项通过**；Android独立夹具15秒构建/lint通过（0错误/3既有警告），v2/1 signer、打包.text/.rodata逐字节核对、AArch64 PIE/linker64/LOAD0x4000、仅libdl/libc通过。当前板`.14` App UID10189/test10190 **1项0.388秒/24用例通过，无skip**，固定transport/cleanup/gate完整协议与exit0一致。main `b1f7343a9d706a34e9643dc1843d14e9a55005058b6f836a3f651837b57ad039`，test `54770ece1573dcbedd12ec269294deecf8e309d66ec471dce2f4303a3e5338c7`；安装读回一致。自有UID进程为空，两fixture卸载Success，正式APK3bc762d9…前后相同。

源码冻结：control.c `00c145040c55e4102d67c09aaa14d42347a5bcd0bf3b2095d6712252c2d61eb9`、header `ec0578fc00dfdf7542c6d227f7d6fdf7cf810cf952f6150fed6f4dad53e7f50e`、native test `0c266fdfeca2f41f68fd9d8f3299c0f55f4f46f6e4a9750a91f528dcce07914b`、Java `7172cb4c7e6fd44577085101845dbbefb43c245ff22a4a08df1e68a47ceb13f6`。仅独立opt-in `ctosSshControlProbe=true`；false、缺fixture、混入policy三条配置拒绝均实际通过。最终普通release/JVM/lint37秒通过，133 JVM无失败/错误/skip；APK `0e0be4f963037e019e3a434bbce63cb53561253d900d2348e4ae77d3e0d142cc`未安装，ZIP实际排除全部G0/G2/policy/control探针及专有assets，仅含生产文件视图JNI。临时证据在`/tmp/ctos-ssh-broker-20261013/control-*`。

真实源码映射确认：10.6p1 `kexgen.c`先kex_verify_host_key(171行)，后sshkey_verify(232行)，因此指纹确认不能直接授予凭据权限；`kex.c` input_newkeys在设置入站密钥后才done=1(600行)，具体KEX完成/署名证明桥还未实现。ssh.c load_public_identity_files(1646行)在连接认证前，必须先处理公开identity/私钥metadata，不能为discovery提前借用私钥正文。sshconnect2.c私钥/密码/KBI需各自typed hooks，不能按prompt或终端输出猜类型。仍无生产role registry/HostKeyGate/actual private handoff、客户端UI或完整协议验收；继续这些步骤，不提交归档或注册production restore provider。

## 当前：通用文件解析147例与发布后撤销17例通过

生产AppSecrets同步借用的公开正文已经接入JNI：私钥只有元数据、正文不入wire；明确wire tags与请求/库UUID/代次/epoch/deadline/连接/密文binding，native再次校验严格UTF-8、类型/长度、唯一ID/路径、文件与目录前缀冲突、完整可达闭包/无环/depth8、128对象/1MiB单件/8MiB总量/12MiBwire与尾部。JNI直接复制到先MADV_DONTDUMP/mlock的0600/CLOEXEC raw memfd；Java编码准确预分配并finally清零。单活不透明token，旧close不能关闭新对象；CLOCK_BOOTTIME与elapsedRealtime对齐检查到期。清理先擦除验证/解锁/确认unmap，再截断及一次close；任何不确定阻止新借用，未确认映射保留所有权，不声称JVM/上游全部副本可擦除。

会话观察者在显式锁/超时/替换解锁/直接session.lock撤销；请求单次绑定在发布前完成，发布后cancel也撤销，旧请求不能改绑新视图。撤销不调用AppSecrets，不持lease/operation锁去取session锁。上游类型访问另以固定`/ctos-vault`命名空间与受限compat glob回调接完整元数据图，匹配后校验CONFIG再读，私钥只能stat描述。每次required public FILE独立0600/锁页匿名对象/独立offset/不缓冲；空正文无payload映射，拒绝普通文件回退。每次求值128并发FILE/1024次open/8MiB在读正文/64MiB累计复制，超限整次拒绝。

同一源码接真实10.6p1 readconf/glob后 **147用例/1项0.780秒通过，无skip**，新增managed_files20；`run.YDuVYoaN`、45秒构建/lint、130 fixture JVM/0失败，v2/1 signer、两个打包程序.text/.rodata与编译输出逐字节一致；所有LOAD0x4000。JNI同批 **16用例/1项0.239秒通过**，包括实际128文件和8MiB正文。main `2ba6954524a853a0a4503022195ddb8cb3f61d0327dc683fc289bd5e80ebbd0d`、test `8846656003822f3794e6458dfe43e27d0dd08b0331aa06decc8936d5e3c4ab38`，UID10185/test10186，读回一致；测试后两个包均卸载，自有UID进程为空，正式APK3bc762d9…保持。更早JNI版本16例/0.308秒也通过并清理，不能替代当前源码。

后续发现发布后的cancel只置请求标记，补上原操作与lease的单次绑定；新增3项JVM通过，总 **133 JVM/0失败**，42秒构建/lint。最终取消回归 **17用例/1项0.314秒通过，无skip**，UID10187/test10188；main `05477234e79618e778ecd1ac332165998b9204c013112f22db6a0c1a2474f780`、test `c107d8d39589024d3f06ce0562dbe5c28997f7002da3497d9d7a884a56138616`，v2/读回一致。两fixture卸载Success、自有进程为空、正式APK保持。native解析源码不受此Java取消修补影响，不重复147例。

本地native最终 **426逻辑用例/1634次映射擦除检查通过**，含每个截断前缀、恶意类型/路径/UTF-8/重复与闭包、实际FILE/heldFD EOF、128并发/1024次读取、实际64×1MiB复制后下一次拒绝，以及mlock/madvise/munlock/munmap/ftruncate/close/clock故障。host不同于Android实测；未确认munmap时保留backing file，第二次清理仍保留失败记录。正常release/lint/JVM130于旧取消稿37秒通过，APK31d6b507…仅本地未安装；正常ZIP恰有生产`libctos_ssh_files.so`，无stale G0/G2/policy探针与专用assets。该旧阶段的后续正常构建已实际完成，最新133 JVM与全部probe排除见顶头KEX记录。

保留失败记录：首轮native计数把6项lookup误算为6次wipe；精确资源计数后通过。新增JVM夹具盐宽度写错触发header断言，改回v1既有16字节，未修改密文格式；instrumentation首次误用不存在的afterCommit，测试Store改为禁止write。合并fixture/normal lint命令因variant歧义在任务选择阶段失败，后分开执行；容器缺shellcheck时先停，已有主机只读静态工具验证通过。原始证据与所有失败轮在临时`/tmp/ctos-ssh-broker-20261013/`，不能把未运行步骤计pass。

原生控制通道24项host及本轮开发板测试均已通过，安装清理和普通构建结果见顶头控制transport记录。**生产 native role issuer/KEX callback 接线、restore validator、协议/PTY/转发/服务/UI仍未接通，完整客户端AC开放。** Java role/trust/typed-secret contract 已有独立本地证据；通用解析适配仍仅opt-in policy fixture，生产main/trust guard拒绝缺provider，不能从这些结果启用真实秘密。

下文为阶段历史；以本节及之后新增的明确版本证据为准。

## 当前：受管跳板参数127例通过，公共文件视图桥接实施中

2026-10-13，真实ssh.c生成器已使用有界参数引用 helper，逐项引用程序/config/用户/跳板链/已解析目标及端口，字面百分号留给唯一一次percent_expand，缺固定程序不回退system ssh。新增proxy_format10：8项经实际percent_expand和自有/system/bin/sh builtin以独立exec argv比较；2项检查真实单/多跳命令字段。format通过不赋予生产角色/host-key审批能力，生产入口继续拒绝缺provider。

最终fresh run `build/ssh-native-policy/run.d3GNr1d2`；17秒构建/lint通过（0错误/3既有警告）。main SHA256 `55e3973352eb0b7c4fe35abd403a953393284d8669bac3280aa62014eebafa7b`，test `248510175eaeb3856982dc90c4419654b491df3602513eb739e23fba0861e419`；实际v2/1 signer、安装读回一致、打包.text/.rodata逐字节核对与AArch64 PIE/linker64/LOAD0x4000检查通过。固定G1完整1240文件manifest构建前后保持。

当前板`.14`独立UID10181/test10182，**1项/0.796秒、127用例全部通过，无skip**：match_exec21、commands12、trust_options19、external42、jump_syntax5、include_closure14、hop_options2、proxy_format10、entry1、trust_entry1、gate127。真实shell只执行公开合成builtin，零网络；自有组/子进程均有界回收。结束自有UID进程为空、两fixture卸载均Success，正式APK仍3bc762d9…且路径相同。原始证据为临时`/tmp/ctos-ssh-policy-20261013/`的platform-run-5、platform-phases-5、frozen-source-5、signature/packed/formal-apk-after-5与卸载记录；不是外部账号或完整协议验收。

随后AppSecrets新增同步SshFileSnapshot借用：闭包内含私钥元数据，公开正文单独列表，私钥正文仍只能经独立host-key guard的privateKeyRequest/usePrivateKey取得；callback结束/抛错后manifest正文清零。首次新增测试因callback异常签名编译失败，窄修后实际**125 JVM/0失败与lint通过（40秒）**，临时jvm-lint-2日志保留。当前继续实现只含公开正文的JNI匿名文件视图及会话撤销；未接生产validator、每跳控制/认证、PTY或UI，不勾完整客户端AC。

下方101/117例和G0/G1/G2“待运行”按当时版本保留。

2026-10-13，原 task 已按用户“效果不错；可以提交验收；然后继续做下一项”恢复为 in_progress。TOTP 已提交 `566ca51`、归档 `f3762ba`、journal `74f8324`。本记录是 SSH 阶段检查，不是父 AC1–AC10 完成或用户验收。

## G0 当前证据

最新结果见下节；后续“待授权/待运行”均为当时阶段记录。

## 原生配置政策首批增量通过

此101例为冻结首批证据；当前扩展结果见下节。

## 原生配置政策与Include回归117例通过

扩展到真实read_config_file_depth与compat glob，使用3个固定0600/锁页memfd配置、原目录callback，不委托普通filesystem。Include相对~/.ssh、嵌套定位与通配/首值、inactive/NEVERMATCH执行拒绝、实际展开后的遍历/其他用户home/proc-FD/缺失引用及深度8拒绝、两个目标分别求值均纳入。固定数字诊断仅reason/object/line/native结果编码/memory errno/effect计数，不输出路径或内容。

扩展首轮117矩阵 **1项0.166秒失败**，只出现前5组与Include error、没有完整gate；增加数字诊断后一轮同为0.166秒失败：首个真实嵌套Include已经返回1且端口正确、memory错误0，但额外拒绝观察器计数1。固定上游Include会生成连接哈希并首次初始化OpenSSL，源码有默认配置加载BIO_file路径；在两个public parser入口加入显式`OPENSSL_INIT_NO_LOAD_CONFIG`后，额外访问计数归零，其他断言不放宽。未收集/记录所尝试的实际路径，不称读取过用户文件。

修后fresh **`run.L15qyw90`，18秒/111 tasks/24 executed，lint0错误3既有警告**；两APK实际v2/单signer通过。main **`0ead24fc2c7b65856ffde31789c0851511e972d7eef4afbea3bb4f2be4bcd06d`**、test **`16751c66518e65fdc3856ea0009f259987eb8b1bf1ad35e011b2bbb9dd6b742e`**当前板安装实际读回一致。fresh UID10179/test10180，同一精确方法 **1项0.189秒通过、无skip，gate117**：原七组计数保持，新增Include14/两目标政策2；每case未授权file/access/fork/exec/socket/connect观察器零调用、匿名FILE全部关闭/擦除/解锁/unmap且无错误。前失败run原始证据均保留；测试后两包卸载Success、自有UID进程为空、正式APK3bc762d9…前后cmp相同。

本轮临时日志 `/tmp/ctos-ssh-policy-20261013/fixture-build-5.log`、`signature-4.log`、`platform-run-4.log`、`platform-phases-4.log`、UID/进程/卸载及formal前后；早一扩展APK strip后text/rodata与build实际cmp相同，修后最终APK未重复该cmp，按其实际验签记录解释。此固定3对象/4096字节仅Include合成载体，不是生产128对象/1MiB/8MiB provider；两目标求值不是完整运行跳板/每跳凭据审批。当前继续受管命令参数、完整上下文和真实协议。

## 原生配置政策首批101例证据（历史）

在冻结G1/G2之外建立fresh政策构建，真实10.6p1 readconf/ssh/sshconnect三处patch及独立policy模块：Match exec在展开前拒绝，包括!exec/exec=、inactive/NEVERMATCH/final；真实none/no、非执行字段inactive及首值语义保留；有效strict绕过、DNS非零/localhost及外部依赖拒绝。配置ProxyJump采用原strict parser。未编译PKCS11/GSSAPI的声明也进入原解析分支，明确拒绝有效能力，避免上游默默忽略。native main在std-FD/argv/file副作用之前拒绝缺provider，verify_host_key/check_host_key守卫在key/cache/DNS/localhost操作之前；目前缺生产provider始终拒绝，没有人工审批正例或完整受管跳板证明。

初次严格编译缺ssh.h依赖、随后public process_config_line签名多一个参数，失败run保留。正确签名及parser接口下cross-build通过；首fixture构建19秒前阶段因Gradle预建空输出目录与发布标记守卫冲突失败，修为仅允许空目录或当前ownership marker，非空未拥有目录保持拒绝。最终 `run.Wc7BAJSa` **19秒/111 tasks/36 executed成功，lint0错误/3既有警告**。runner bash语法/ShellCheck/shfmt通过，三个非法Gradle开关实际拒绝；baseline完整1240输入前后aggregate保持。首只读验签harness因head/SIGPIPE提前结束，改sed后两个实际v2/单signer/独立包名通过；正常release/lint13秒通过，ZIP无G0/G2/policy输出，hash保持d43dff68…。

独立main **`d241c70e2ea258513fa9cbcce025f1cf97122ac1bd5f5d5e2702b0ef18c62650`**、test **`c9c2f6f4ec979b781df6749573ab086fec08360cba19fb433f6c1cad5a67f314`**安装实际读回一致。当前`.14`fresh UID10173/test10174，仅固定`SshPolicyPlatformTest#syntheticNativeConfigurationPolicy`和opt-in：**1项0.154秒通过，无skip，101例**。阶段计数match_exec21/commands12/trust_options19/external42/jump_syntax5/entry1/trust_entry1，gate101；case内file/access/fork/exec/socket/connect deny-observer必须零调用。全部公开合成字符串，不启动Activity、不访问真实库/账号或监听端口。

测试后两自身UID进程表为空，精确卸载两个fixture均Success；正式APK3bc762d9…前后cmp相同。原始构建/验签/拒绝/阶段及JUnit、UID、进程/卸载与正式APK前后证据保留 `/tmp/ctos-ssh-policy-20261013/`。本增量证明行解析/配置限制、严格跳板语法和缺provider早拒绝；尚未证明真实嵌套Include闭包/受管文件路径、safe shell quoting、每跳审批/凭据或完整协议。原task继续这些门槛，未启用生产SSH validator/restore reviewer。

## G2 第二轮当前设备回归通过与清理

修补源码 C `cde9fadee525a1f11f82ac1e38355fec28a5e350233bd77c493afd0a936f7340`、runner `994efbed48b18c1f93ab7d99de5335b9e61f571eb42cc1188f1b1a857ddc2049`，Gradle/Java保持最终审查版本。fresh `run.pVUcIdvd` **17秒/111 tasks/24 executed**成功、lint0错误/3既有警告，1240输入覆盖仍无缺失。仅VFS客户端包装真实 `_exit`，先执行自有资源清理后保留原退出码；driver、baseline与signal路径不改变。checker修后只读复核无具体阻塞。

当前测试main SHA256 **`23d33ccadab6be86391557e79f370d5a36825c1805c5f56393734d4395176b53`**，test保持 **`cf673b520cac854c37e02775634eb09a94f0edc28db53213b2d3b04b65413806`**；v2/单signer通过，两安装包实际读回一致。用户既有G2专项授权下，仅当前`.14`指定方法/opt-in：fresh UID10171/test10172，**1项/4.496秒通过，无skip**，JUnit `OK (1 test)`、方法status0；不能单凭adb退出码判定。

固定协议六组config/fd_negative/identity_hostfile/proxy_inherit/cancel_reap/revoke_local均pass/errno0。driver2177、真实client_parent2199/client_jump2200、代次73；两个actor各value1且proxy_inherit value2，cleanup value0、gate value6。crypto_version810549392及四个固定资源边界记录均出现且通过。该实测解决第一轮缺清理回执的失败，不把原errno10断定为wait链根因。

测试后自有UID进程表为空，两fixture精确卸载均Success/pm path为空。正式APK路径及SHA **`3bc762d9e6463ab9612393b9382923f4a29f613ceec3dbe4d9159aae0078f252`**前后相同。原始 `platform-run-2.log`、`platform-phases-2.log`、`installed-apks-2.sha256`、UID/进程/卸载及正式包后验保留 `/tmp/ctos-ssh-g2-20261013/`。API30/4KiB/Permissive证据仅证明此合成关卡，不证明完整协议、生产政策/provider、人工信任CAS、全部算法或其他平台。原task继续配置限制与信任关卡。

## G0 当前证据（阶段历史）

- 独立 fixture 的匿名内存/受管 exec 探针已实现；固定 UID/包/Application/方法及 opt-in 守卫，不启动 Activity，不接触正式库、账号、剪贴板、Root 或网络。
- 分别检查 API26 ASharedMemory 与明确标注的 raw memfd syscall 候选；至少一项必须通过完整 POSIX/锁页/受管子进程/擦除/持有 FD EOF/回收，零可用候选明确失败。不是完整 OpenSSH 或同 UID 沙箱证明。
- 首轮 source review 找到 C 时钟失败可能失去回收期限、Java 中断跳过清理和首个流关闭异常跳过其余流。owner 已修复：固定尝试上限、未回收 PID 保留所有权并重试、8 秒 Java 清理总期限、逐流关闭及最终恢复中断。main 又修正未 reap 时 cleanup 不能报 pass；checker 对最终 SHA 复审，无剩余具体源码阻塞。只读审阅不代替运行验证。
- `assembleSecretsFixtureRelease`、`assembleSecretsFixtureReleaseAndroidTest`、`lintSecretsFixtureRelease` **14 秒通过**，111 tasks（25 executed）；lint **0 errors / 3 existing warnings**。API28/arm64、NDK27.0.12077973、自有 C `-Wall -Wextra -Werror`。
- ELF 实际为 AArch64 PIE，解释器 `/system/bin/linker64`、PT_LOAD `0x4000`；动态依赖仅 `libandroid.so` / `libdl.so` / `libc.so`。两份 APK 实际 v2=true、1 signer，沿用开发证书。
- probe 生成后正常 release 重新构建 **26 秒通过**；ZIP 无 `ssh_memory_probe`，正常 APK hash `d43dff680ab8d31492cfb70a64e9ab315b3a99c01ef1e25ae172bc0e3384f3cb`。只构建隔离控制，没有安装此包。
- Gradle `ctosSshMemoryProbe=false` 与仅 `ctosSshMemoryProbe=true`（未启 fixture）各实际配置失败，分别命中 strict true / requires fixture；不是产品测试失败。

最终 source SHA256：C `d473e5e4db71eeb85947755a2a3ed513627382ea692515a60c0899a3dfc3fbbc`；Java `a6e7f40282c2fba2a4529ae2a43f734db975803abbfd82dcec153c430cd8e16e`；Gradle `0d5fd99091e84bb2ca33eff548c8d72676f6d985051663fb76497a9996ec3d0b`。最终追加 cleanup 诊断修正后 **8秒重新构建通过**，111 tasks/19 executed，lint仍0错误/3既有警告；最终ELF/两APK v2验签通过，source manifest逐份匹配，fixture ZIP唯一probe路径且仅arm64 ABI。

可审阅最终 APK：fixture main **`a14f176711b571cf8fa6083897f3c4831ff793a86377b0d109240ca3c742e4ea`**；fixture test **`0dafbda4774cfc6c3a5cee3cf21218cf851d717da0ae8344478b84298dd9f17a`**。临时副本 `fixture-main-final.apk` / `fixture-test-final.apk` 和本节原始日志位于 `/tmp/ctos-ssh-g0-20261013/`，跨环境不保证保留。

## G0 当前设备通过

用户已明确批准本轮 `.14` 的 SSH G0 安装/合成测试/卸载，并追加允许真实秘密库操作；不保存或输出密码。当前 G0 只用合成数据，没有打开真实库/账号、锁屏输入、Root/剪贴板/系统设置或网络端点。

真实设备核对：`192.168.9.14:5555`，AIO-3568J，Android11/API30，arm64，**SELinux Permissive**；正式 UID10131，fresh fixture UID10167/test10168，安装前两fixture无路径。两 APK 安装后实际 SHA 与最终产物一致。

仅执行 `SshMemoryPlatformTest#syntheticAnonymousMemoryAndManagedExec` + `ctosSshMemoryPlatformProbe=true`：**1项/1.693秒通过，无skip**（instrumentation method status=0，OK1，最终code=-1；不只依据adb exit0）。固定协议实际 page_size=4096、RLIMIT_MEMLOCK=67108864、eligible=1：

- ASharedMemory create/map/mlock及现有映射保护边界通过，但不满足 fstat 常规文件形态，`unsupported errno=95`，candidate=unsupported；明确不纳入可用候选。
- raw memfd syscall candidate 完整通过 POSIX形态、cancel/timeout exact-child reap、FD whitelist exec、失效代次/缺失对象拒绝、同步擦除/子进程自身buffer擦除、unmap/truncate后held-FD EOF与清理；candidate=pass，gate=pass，production_openssh=false。

测试后只读查询自身UID10167进程列表为空；精确卸载两个本轮fixture均Success，之后pm path均为空/exit1。正式APK路径、UID及SHA仍为 `3bc762d9e6463ab9612393b9382923f4a29f613ceec3dbe4d9159aae0078f252`，前后核验一致，没有覆盖正式应用。

原始证据 `platform-run-1.log`、`platform-phases-1.log`、`owned-processes-after-1.log`、安装/卸载日志及 `board-{before,after}.json` 位于上述tmp目录。**这仅是API30/4KiB/Permissive AppUID合成G0能力通过，不是Enforcing/API28/29/真实16KiB、完整OpenSSH或同UID隔离证明。** injected failure/interruption并未以设备故障注入重测，代码失败路径审阅不能冒称实测。

## G1 输入准备

main 已从官方地址实际取得 OpenSSH10.6p1 与 OpenSSL3.5.9 发布归档，独立计算 payload SHA256，与官方发布/实际 OpenSSL `.sha256` 附件一致；核对归档内 version.h / VERSION.dat 及完整 LICENCE / LICENSE.txt。版本、来源和许可证 hash 见 [依赖记录](../../../design/dependencies.md)。未验证 PGP 签名，不能称验签。

工具只读检查实际通过：hako Perl5.38.2、make4.3、NDK27/API28 clang18.0.1、llvm-ar/ranlib/readelf 及 NDK API28 公开 zlib header/stub。stock runner `455bb5…` 实际run `build/ssh-native-g1/stock-run.zPRULpSC/`：OpenSSL Configure/build_libs/install_dev通过，OpenSSH configure通过/WITH_ZLIB=1，但make在bsd-closefrom.c失败；defines.h空对象宏 `__sentinel__` 先于Bionic unistd.h的带参数属性声明引发编译冲突。失败配置/日志未改动。

首份 Bionic-only include-order patch（`12c0664e…` / runner `3199e33c…`）在fresh `stock-run.ZErimQnc`实际应用成功，但上游bzero fallback定义又被Bionic函数式宏展开；只跳过fallback会使上游explicit_bzero的volatile函数指针丢失真实符号，不能作为修复。

第二次patch（`9801da6b…` / runner `17049f30…`）保留真实fallback，括号化函数定义/声明，保持explicit_bzero.c原SHA逐字节一致。fresh `stock-run.JxZvxbXV` 编译通过该对象，后续getrrsetbyname.c因Bionic不公开`_res`/`__res_state`/RES_INIT而失败；不能fake私有结构或cache、降低擦除能力或隐去失败。

公开resolver ABI研究分支只在 `__BIONIC__ && CTOS_ANDROID_ABI_BASELINE_ONLY` 显式编译守卫下使用公开res_init/res_query，不假造私有state；它不能证明EDNS0/DO或AD来自可信DNSSEC验证器。普通getaddrinfo目标DNS与SSHFP查询分开。上游VerifyHostKeyDNS=yes可能依据SECURE/MATCH提前accept，G2生产受约束验证必须拒绝此类主机核验绕过并走人工指纹批准；不据ABI构建注册生产trust provider。

最终runner **`cff9878ec3f54fa3113a4c40ecf6f30abd785bca6ceb92284678cbf51e97eca2`** / patch **`ea81738f191e7d9fb70ce0943b45ea06b07c4057d3d0128a694331b3a32f8b89`** 的fresh run **`build/ssh-native-g1/stock-run.lOHF9Oai/` 实际成功**：OpenSSL Configure/build_libs/install_dev、OpenSSH configure/WITH_ZLIB1/WITH_OPENSSL1、make ssh/ssh-keygen及readelf均exit0。只有研究ABI兼容基线编译，不称完整G1通过或unmodified stock。

最终实际产物 SHA256：libcrypto.a **`feb776737f8f84d4110492a03e4c4d91312a65638408851c3a509aabb9736376`**；ssh **`5a3a15e6f4de095419aa369383a8e133861ca2bdff3c521cdb86fa896d81fa16`**；ssh-keygen **`591eb4a865b3b9d9fbf3a9ba51702d8505a8053224a5844481f6953be0eb161d`**。两最终ELF都是AArch64 DYN/PIE、`/system/bin/linker64`、所有PT_LOAD0x4000、DT_NEEDED仅libz/libdl/libc，无动态libcrypto/hostglibc/TEXTREL条目；符号表有已定义bzero/explicit_bzero/OPENSSL_cleanse/OpenSSL_version。explicit_bzero源码与原版cmp一致，不能代替实际运行擦除/crypto算法。没有执行、安装、接入生产或替换dist。

原始最终阶段日志/配置/全source notices/hash位于该ignored run；主会话外层日志 `g1-abi-compat-build-3.log` / `g1-symbols-final.log` 在G0 tmp证据目录。三个失败run原样保留。最终源码复审、语法/ShellCheck/shfmt以及固定input/patch前后SHA核对继续保留，仍未运行OpenSSH/crypto算法或生产客户端。

## 未完成验收

G1 ABI兼容编译已完成，但运行与crypto算法仍开放。G2同版本上游文件六组/Include/ProxyJump、另行强制的无本地执行与信任绕过政策关卡、G3公共秘密库/每跳授权/信任 CAS/认证/PTY/转发/前台服务，以及父完整客户端 AC 均未通过。Enforcing/API28/29 和真实 16KiB 设备另待实测；当前G0不能代替。生产 SSH validator/trust provider 缺失继续 fail-closed。当前不提交或归档 SSH，也不宣称公共底座所有开放项完成。

## G2 当前源码审阅进度

Java/Gradle夹具初次只读审阅发现两项缺口：capacity/object_limit/proposal_stream/local_scope记录若缺失仍可计pass，baseline输入树仅根目录检查symlink。owner已将四条固定边界记录列为必需，并在输入快照前不跟随symlink逐目录/成员检查后冻结输入列表。crypto_version按协议仍可选，不出现不证明runtime crypto通过。G0 Java仅guard/drain/cleanup包内可见性调整，原validator和执行行为保持。

当前窄修SHA256：Gradle `dc32716a9d7711e80843c3532e107d170b5d883aece0ae89c95a5f2b255a6c24`；G0 Java `cc55ec8a39f07484e2bdfd2330062a944dd1a67c2da530edcd22bdfd32f8a4e3`；G2 Java `b113f2ba264e016ca03291fd7929ec72b48a9e59d8f93b7cf9768d34235c0970`。源码diff检查与checker只读复审通过，无剩余具体Java/Gradle源码阻塞；main在hako中实际执行有效G2 baseline配置的`:app:help`，7秒成功，确认Gradle DSL及输入枚举可执行。native/runner仍在实施，尚未完整构建或设备验证；help不证明夹具编译或运行通过。Java等待预算分别为60秒进程、3秒reader及共用8秒cleanup，不能表述为总61秒硬限。

main实际配置拒绝检查四项通过：`ctosSshUpstreamProbe=false`、启probe未启fixture、启fixture/probe但缺baseline、baseline含`..`；各Gradle非零并命中对应固定拒绝消息。首个检查脚本因容器缺rg在比对时停止，保留原日志；改用容器可用grep的第二轮四项全部完成，没有改变源码或降低断言。原始配置日志在临时 `/tmp/ctos-ssh-g2-20261013/gradle-config-{valid,negative,negative-2}.log`。

main进一步在仅启独立fixture、未启native G2开关时执行`compileSecretsFixtureReleaseAndroidTestJavaWithJavac`与`lintSecretsFixtureRelease`：14秒成功，59 tasks/15 executed，lint0错误/3既有警告。G0/G2 Java均实际编译，日志`java-lint-precheck.log`位于上述临时目录；这不构建或执行尚未完成的三份native G2产物，也不计完整G2 fixture pass。

## G2 最终本地构建与可审阅产物

最终源码：C `5ee25a4c276591fd84eb79b385f8c916b324b728eaf9bf779408115f51e6883a`；runner `1bff434a573ddc7ec5b39dd0ecf94af65af99362b2b31bb144b1cb70fcf40564`；Gradle `030cc1b92307f029abd34f50f29990be80776d501c21aa4185998a17ca4b2173`；G0/G2 Java分别保持`cc55ec8a…`/`b113f2ba…`。checker最终只读复核无剩余具体源码阻塞，不代表运行通过。

审查并修复实际边界：bootstrap munlock/close/munmap失败不能被较早EV_CLEAN覆盖；正常teardown全程屏蔽取消信号至view=NULL，成功关闭BOOTFD后记录signal-safe所有权；保留独立primary PID及WNOWAIT zombie anchor，TERM/有界grace/KILL全部在reap前执行，后续重试仅reap，不再向可能复用的PGID发信号。主PID已确认回收且group ECHILD后才释放所有权。保留失败回执和保留退出码99，ProxyJump预期非零退出也不能掩盖清理失败。

runner直接CLI和Gradle各自检查四个完整baseline输入树，无followlinks/特殊成员；根官方archive/patch pins保留。当前指定`stock-run.lOHF9Oai`的完整1240文件manifest aggregate为`e3b0c5215b8050db328bd8773cc4ba00212eb9f8b21d924ed0ab207501ef0ed6`，构建前后核对并cmp。Gradle直接收已验证全部regular文件，不受Ant的.gitignore默认排除影响。main实际输入集合对独立1240条oracle校验，`G2_INPUT_COVERAGE expected=1240 missing=0 gitignore=included`。

第一轮native严格编译三种C模式通过，driver因缺实际上游ssh-pkcs11.o的两个符号链接失败，保留`run.QQCDZqKE`；随后从真实SSHOBJS/SKOBJS衍生完整driver对象而非fake实现。旧清理稿两APK14秒构建通过但不安装，最终修补稿第一次17秒通过；额外输入覆盖检查脚本首轮在Flutter included build误触发，保留日志，限定实际:app后最终**17秒/111 tasks/27 executed成功，lint0错误/3既有警告**。最终fresh native run为`build/ssh-native-g2/run.Z9J9BADS`；未执行任何ARM产物。

最终可审阅fixture main **`aba810c19d8e651935e227b514de82db6227b3436beb62c66d620b61278433fa`**；test **`cf673b520cac854c37e02775634eb09a94f0edc28db53213b2d3b04b65413806`**，均实际v2=true/1 signer，SDK28，独立包名及arm64核对。tmp副本`fixture-{main,test}-final.apk`位于`/tmp/ctos-ssh-g2-20261013/`。APK恰含三个固定G2 probe/client/baseline程序、完整上游notices和本地patch/前后manifest；其他既有运行包仍按原组成保留。实际APK strip后的三程序`.text`与`.rodata`逐字节一致于最终编译产物，ELF仍AArch64 PIE、linker64、所有LOAD0x4000、仅libz/libdl/libc动态依赖；不能把strip前后全文件hash混为一致。

G2专用输出生成后正常release/lint实际**13秒通过，0错误/3既有警告**；ZIP无G0/G2研究程序或专用assets，正常APK SHA仍`d43dff680ab8d31492cfb70a64e9ab315b3a99c01ef1e25ae172bc0e3384f3cb`，仅本地隔离控制未安装。最终Gradle后续窄修只调整fixture输入集合，normal sourceSet排除不变。

当前只读设备状态：`.14`在线，两fixture pm path为空/exit1。**G2尚未安装或执行，六组设备结果和生产政策/信任/完整客户端保持开放。** 本G2新增自有127.0.0.1两个随机listener及真实上游shell→exec跳板，仅合成identification失败/取消；与先前用户批准的不含网络G0固定方法区分，待相应当前范围确认后才做设备阶段。不会以真实库操作许可代替真实SSH账号授权。

## G2 第一轮当前设备失败与清理

用户随后明确回复“允许，测试后清理”，授权当前`.14`两份已审G2 APK安装、指定合成loopback方法及卸载。当前只读身份AIO-3568J/API30/Permissive/4096已核对；fresh fixture UID10169，正式10131，两安装APK实际读回SHA与aba810c1…/cf673b52…一致。

仅执行指定`SshUpstreamPlatformTest#syntheticUpstreamMemoryFilesAndProxyJump`及`ctosSshUpstreamPlatformProbe=true`：**1项/3.418秒失败，无skip**，JUnit failure1/status-2，不能据adb exit0记pass。固定协议实际crypto_version810549392与capacity/object_limit/proposal_stream/local_scope、config/fd_negative/identity_hostfile均pass；proxy_inherit/cleanup/gate均error errno10，没有成功parent/jump记录，cancel_reap/revoke_local未执行。实际运行版本与未加密Ed25519相关合成读写得到前置证据，但不据此标完整crypto/G2关卡通过。

源码定位发现报告的errno10可能来自此前成功waitpid的残留errno，不能据此断定wait链本身失败。真实OpenSSH `cleanup_exit`直接`_exit`会跳过ELF destructor，可能造成CONFIG后缺CLEAN回执；owner加入VFS-only真实_exit hook先清理自有对象再退出，并改用明确的launch/evidence返回码及既有error.value固定数字诊断，不放宽任何六组/清理/actor条件。**此时只确认源码缺口，是否为原失败根因须修后实际回归。**

失败后自身UID10169进程表为空；两本轮fixture精确卸载均Success。正式APK前后hash及路径仍3bc762d9…一致；没有操作真实库/SSH账号/Root/剪贴板/系统设置。`platform-run-1.log`、`platform-phases-1.log`、安装读回hash、自有进程及卸载/正式包前后证据保留上述G2 tmp目录。当前C/runner修补冻结cde9fade…/994efbed…，main串行重新构建中，G2整体保持失败/待回归。

## 当前下一步：生产 native launcher 与协议接入

当前task仍in_progress，完整客户端未提交/归档。G0/G1/G2、147 parser、17 JNI撤销、24 transport、27真实KEX及本轮 Java bridge 结果各自版本已固定，不再重做探索或重复请求现有合成测试授权。下一实现把已有 role registry/host authority/KEX proof/typed bridge 接到生产 owned launcher 和真实 OpenSSH callback：固定APK程序和独立nonce/连接/hop/owned PID，受管public-view引用；getopt/closefrom/文件或网络之前拒绝缺上下文及任意CLI，每hop独立channel与有限取消/reap。原Native配置main/trust guard保持拒绝缺provider。

Java 层已经完成上述 host-key 分类、精确 review 和 typed bridge；仍需把真实 host callback/issuer 和 27例 KEX完成证明对接到生产 HostKeyGate。kexgen.c 先 host callback 后 signature；AppSecrets saved credential/private key 的 final EffectGuard 必须依赖实际 signed KEX+NEWKEYS 且匹配同hop/目标/身份/当前库UUID/代次/epoch/连接。ssh.c public identity discovery在认证前，允许公开sidecar/metadata，禁止提前私钥正文；sshconnect2.c private/password/passphrase/KBI分别typed hook。原API usePrivateKey/useCredential仍守卫关闭，不能从prompt/stdout/UI bool或控制帧直接授予权限。完整协议认证/PTY/forward/service/UI和混合恢复联合关卡仍按父合同。

当前正式应用仍为已验收TOTP3bc762d9…，本轮未安装APK，临时测试资源按既有阶段已清理。外部SSH账号/端点未授权；不用ADB地址推导SSH端点。正常最新 callback-adapter APK `fa4a6356…` 仅本地，不更新dist。

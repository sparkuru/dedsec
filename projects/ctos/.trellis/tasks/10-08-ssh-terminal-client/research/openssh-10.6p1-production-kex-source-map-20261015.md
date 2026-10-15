# Research: OpenSSH 10.6p1 同进程 production KEX/NEWKEYS source map

- Query: 在 pinned OpenSSH 10.6p1 与 `build/ssh-native-g1/stock-run.lOHF9Oai` 下，不调用 stock main/getopt/closefrom/fork/exec/ProxyCommand，确认 client struct、socket FD、identification、KEX、host callback、signed-KEX、inbound NEWKEYS 的最小调用顺序；映射当前 KEX guard/hook 与 `ssh_callback_adapter`/JNI；给出最多 3 个新增 production files 的停在 KEX/NEWKEYS 的实现建议和严格 host-test 点。
- Scope: mixed (pinned internal source/build plus current project adapter contracts)
- Date: 2026-10-15

## Findings

### 1. Pinned build and source baseline

本次源码锚点是：

- `build/ssh-native-g1/stock-run.lOHF9Oai/sources/openssh-10.6p1/`：实际解压的 OpenSSH 10.6p1 源码。
- `build/ssh-native-g1/stock-run.lOHF9Oai/evidence/source-archives.sha256:1-2`：OpenSSH 归档 SHA-256 为 `a9dc9565dffe8640f64d863cd29a32bc4a3dbdec0566a7fc44c5d6ee767d5f39`，OpenSSL 3.5.9 为 `603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a`。
- `build/ssh-native-g1/stock-run.lOHF9Oai/evidence/commands.txt:1-13`：当前 build 是 API 28 / arm64 NDK baseline，OpenSSL no-shared，OpenSSH cross-configure 后构建 `ssh`/`ssh-keygen`；该目录明确是 ABI compatibility baseline，不是 production adaptation。
- `build/ssh-native-g1/stock-run.lOHF9Oai/evidence/scope.txt:1-9`：明确没有 production SSH adaptation、APK packaging 或 executable launch，且没有证明 memory adapter、认证或 trust gate。
- `build/ssh-native-g1/stock-run.lOHF9Oai/openssh-build/Makefile:81-109,117-118`：`ssh_api.o` 属于 OpenSSH library object；`sshconnect.o`/`sshconnect2.o` 属于 stock client executable object，二者不等于可直接复用的受管 production caller。

本地 stock source tree 没有 `CTOS_SSH_KEX_GUARD`。当前 guard patch 是独立 fixture 输入 `.trellis/tasks/10-08-ssh-terminal-client/research/openssh-10.6p1-kex-guard.patch:1-93`，在 `build/ssh-native-kex/run.Nb5f1CnJ` 中以 `KEX_FLAGS = -DCTOS_SSH_KEX_GUARD=1` 编译 `kex.c/kexgen.c/kexgexc.c`（`Kex.mk:1-13`，`evidence/patch.log:1-3`）。因此 fixture 通过不能被写成 normal `stock-run.lOHF9Oai` 或 production APK 已接入。

### 2. 可确立的最小 client/KEX 调用顺序

下面是可以由当前源码直接支持的顺序；其中“wrapper”是建议的新增本地层，不是当前已经存在的函数。

1. **创建 client object 和初始 KEX state**

   调用 `ssh_init(&ssh, 0, &params)`。`ssh_api.c:86-118` 显示它先以 `ssh_packet_set_connection(NULL, -1, -1)` 创建 packet state，然后用 `kex_proposal_populate_entries()` 和 `kex_ready()` 建立 proposal/dispatch；`ssh_api.c:140-156` 为 client 填充 `kex_gen_client`、`kexgex_client` 和 `verify_host_key` 默认函数表。`ssh_api.h:36-47` 是公开初始化/释放声明。

   `params` 应由受管配置结果产生，而不是从 `getopt` 或 stock `Options` 隐式读取。若要在 KEX-only 阶段限制算法，使用 `struct kex_params` 的 proposal 数组（`ssh_api.h:32-34`）并保留上游 `kex_proposal_populate_entries()` 语义；不要自写 proposal parser。

2. **建立/接管 socket FD**

   连接 socket、地址解析和超时必须由 ctOS wrapper/受管网络层完成；不能调用 stock `ssh_connect()` 作为隐式入口。`ssh_packet_set_connection(ssh, fd, fd)` 的公开声明在 `packet.h:92-101`，实现 `packet.c:296-335` 将 FD 写入 `state->connection_in/out`、初始化 `none` cipher 并缓存地址信息。`ssh_packet_get_connection_in/out()` 在 `packet.c:502-515` 可取回 FD。`ssh_packet_close()` 最终会关闭该 FD（`packet.c:680-699,757-768`），所以 wrapper 必须明确 FD ownership，不能把 Java/外部 owner 的同一个 FD 无条件交给 `ssh_free()`。

   同进程 event loop 应在接管后将 socket 设为 nonblocking；`ssh_packet_set_nonblocking()` 的实现只做 FD `set_nonblock`（`packet.c:490-500`）。该函数在 `packet.h:97` 可见，但不在 `ssh_api.h` 的窄 API 注释中，仍应由本地 wrapper 集中调用。

3. **在第一次 KEX 输入前绑定 role、callback 和 host verifier**

   先用当前 native role token 调 `ctos_ssh_kex_bind_role(ssh, role, host_digest)`；其公开于 `android/app/src/main/cpp/ssh_kex_guard.h:7-10`，实现会检查 client、当前 PID/UID、live bootstrap、hop/connection/nonce/deadline 和 host fingerprint（`ssh_kex_guard.c:54-79`）。随后在同一个 event-loop 线程调用 `ctos_ssh_callback_open(..., environment, &callback)`，再通过 `ssh_set_verify_host_key_callback(ssh, verifier)` 注册 wrapper verifier（`ssh_api.h:65-72`）。这保证 guard 和 JNI binding 都在真实 event-loop 线程建立；当前 adapter 明确拒绝其他线程的 callback/close（`ssh_callback_adapter.c:150-183,264-299`）。

   `ssh_add_hostkey()` 不应被误用作 client trust registration。它是 public API，但 client 分支只是把一个 public key 放入 `ssh->public_keys`（`ssh_api.c:199-231`），默认 `_ssh_verify_host_key()` 只做 public-key equality（`ssh_api.c:511-523`），不表达当前受管 known_hosts view、NEW/CHANGED/REVOKED 分类或 exact CAS。

4. **identification 和 KEXINIT 的同进程增量循环**

   对已接管的 nonblocking FD，首选 `ssh_packet_next(ssh, &type)` + `ssh_input_append()`/`ssh_output_ptr()`/`ssh_output_consume()` 的内存边界 API，而不是 `kex_exchange_identification()`。`ssh_api.h:74-134` 明确规定 caller 负责把网络输入 append 到 input buffer、把 output buffer 写回网络并按实际写入量 consume；`ssh_api.c:246-289` 实现了这一循环。

   第一次 `ssh_packet_next()` 在 banner 未完成时进入 `_ssh_exchange_banner()`（`ssh_api.c:253-266`）。client 分支先读 peer banner，再写本地 `SSH-2.0-<version>\\r\\n`，双方 banner 齐全后调用 `_ssh_order_hostkeyalgs()` 和 `kex_send_kexinit()`（`ssh_api.c:442-478`）。因此 identification 的可验证阶段顺序是：

   `read FD -> ssh_input_append -> ssh_packet_next -> _ssh_read_banner -> _ssh_send_banner -> kex_send_kexinit -> output_ptr/write/consume`。

   `kex_exchange_identification()` 仍是公开于 `kex.h:206` 的 stock helper，但它自己 `atomicio(vwrite/read)` 并用 `waitrfd` 阻塞等待（`kex.c:1277-1358`）；把它塞进同一个 UI/native event-loop 会破坏当前用户确认的同步 event-loop topology，且会绕开 `ssh_api` 的 caller-owned buffer 语义。它只能作为独立 blocking caller 方案，不能作为本次 production sequence 的推荐步骤。

5. **KEX dispatch、host callback、signed-KEX**

   `kex_ready()` 把 transport range 设为 protocol error，并把 `SSH2_MSG_KEXINIT` 设为 `kex_input_kexinit`（`kex.c:791-801`）。收到 peer KEXINIT 后，`kex_input_kexinit()` 解析 peer proposal、必要时发送本地 KEXINIT、选择配置，然后调用 `ssh->kex->kex[ssh->kex->kex_type]`（`kex.c:647-711`）。client 函数表由 `ssh_api.c:140-155` 建立：普通 DH/ECDH/curve/KEM 走 `kex_gen_client`，DH-GEX 走 `kexgex_client`。

   KEX reply 解析后，`kex_verify_host_key()` 先检查 negotiated host-key type，再调用 `ssh->kex->verify_host_key`（`kex.c:1226-1241`）。所以真正的 host callback 位置是：

   `server host key parsed -> kex_verify_host_key -> ctOS verifier -> exact host proof -> patched ctos_ssh_kex_approved`。

   当前 patch 在上游 verifier 成功后插入 `ctos_ssh_kex_approved(ssh, server_host_key)`（`openssh-10.6p1-kex-guard.patch:35-45`）；guard 实现要求先由 verifier 证明，再将 key 的 SHA-256 raw fingerprint 与 role-bound host digest 比较（`ssh_kex_guard.c:46-52,95-100`）。这不是 Java callback 本身，必须由新 wrapper verifier 显式调用 `ctos_ssh_callback_host_key()`。

   对普通 DH/ECDH/curve/KEM，`kexgen.c:217-237` 的顺序是计算 H、`sshkey_verify()`、`kex_derive_keys()`、`kex_send_newkeys()`；对 DH-GEX，等价位置在 `kexgexc.c:186-210`。patch 在 signature verification 成功后、derive/send NEWKEYS 前插入 `ctos_ssh_kex_signed()`（`openssh-10.6p1-kex-guard.patch:60-70,83-93`）。因此签名证明的严格点是“签名已由 OpenSSH 验证、但 inbound NEWKEYS 尚未安装”。

6. **outbound NEWKEYS、inbound NEWKEYS 和 KEX completion**

   `kex_send_newkeys()` 会重置 KEX dispatch、发送 `SSH2_MSG_NEWKEYS`，并注册 static `kex_input_newkeys` 处理 peer 的 inbound NEWKEYS（`kex.c:424-438`）。该 handler 先 `sshpkt_get_end()`，再调用 `ssh_set_newkeys(ssh, MODE_IN)`（`kex.c:561-576`）。随后它清理 initial proposal state；stock 在 `kex.c:597-605` 才把 `kex->done = 1`。

   当前 guard patch 在 `kex->done = 1` 之前插入 `ctos_ssh_kex_complete(ssh)`（`openssh-10.6p1-kex-guard.patch:13-23`）。所以 guard 的 completion hook 不是 Java callback，也不能代表 Java 已收到 KEX proof。对于 production wrapper，安全的可推断顺序是：

   `kex_input_newkeys -> ssh_set_newkeys(MODE_IN) -> ctos_ssh_kex_complete -> kex->done=1 -> ssh_packet_next returns -> digest session_id -> ctos_ssh_callback_kex_complete`。

   `ssh->kex->session_id` 是 `struct kex` 字段，`kex.h:158-165` 可见；`ssh_digest_buffer(SSH_DIGEST_SHA256, ssh->kex->session_id, digest, 32)` 有明确声明（`digest.h:51-57`）。但“在 `ssh_packet_next()` 返回后调用 Java completion callback”是建议的新增 wrapper 行为，不是当前生产代码已有行为。当前 `ssh_kex_guard.c:108-125` 的 `ctos_ssh_kex_ready()` 还要求 `complete=1`、`ssh->kex->done=1`、正确 hop/nonce/connection，进一步说明 Java completion 必须在 inbound NEWKEYS 后发生。

   `ssh_packet_next()` 只自动 dispatch KEX/transport 范围（`ssh_api.c:273-289`）；现有 fixture 明确把 `SSH2_MSG_EXT_INFO` 留给 caller（`android/app/src/test/cpp/ssh_kex_test.c:139-153`）。KEX-only wrapper 必须对这个返回值作有界处理，不能把它静默当作 userauth/service；`kex_input_ext_info()` 的上游声明在 `kex.h:220-223`。

### 3. stock `sshconnect.c/sshconnect2.c` 为什么不能作为本次入口

- `ssh_connect()` 虽在 `sshconnect.h:73-75` 公开，但实现直接依赖全局 `Options`（`sshconnect.c:572-599`），正常路径进入 static `ssh_connect_direct()`（`sshconnect.c:476-569`），proxy 分支会进入 static `ssh_proxy_connect()`/`ssh_proxy_fdpass_connect()`；后者明确使用 `fork()`、`execv()`、shell `-c`、pipe/socketpair 和 wait（`sshconnect.c:138-218,224-301`）。这与本次禁止 fork/exec/ProxyCommand 的边界冲突。
- `ssh_kex2()` 虽在 `sshconnect.h:88-89` 公开，但其实现依赖全局 `options`、hostkey ordering、`order_hostkeyalgs()` 和 `ssh_conn_info`，错误使用 `fatal_r()`，最后调用 `ssh_dispatch_run_fatal(ssh, DISPATCH_BLOCK, &ssh->kex->done)`（`sshconnect2.c:218-281`）。它不是 event-loop step API，且会把 stock config/trust globals 重新带入 production。
- `ssh_login()` 是更高层的 banner + nonblocking + `ssh_kex2()` + `ssh_userauth2()` 组合（`sshconnect.c:1621-1669`），不符合停在 NEWKEYS 和不做 userauth 的范围。
- `kex_input_newkeys()`、`kex_reset_dispatch()` 和各 KEX reply handler 是 static/内部 dispatch；不能从新增 wrapper 直接调用。应让 `ssh_packet_next()` 进入上游 dispatch，或新增一个很薄的 upstream wrapper，而不是声明一个当前不存在的 public caller。

`nm` 也与此一致：stock `ssh_api.o` 只导出 `ssh_init`, `ssh_free`, `ssh_set_verify_host_key_callback`, `ssh_packet_next`, input/output buffer functions 等；`sshconnect.o` 导出的是 `ssh_connect`, `ssh_login` 等高层入口，不导出 `ssh_connect_direct`, `ssh_proxy_connect` 或其他 static helper（当前 build 的对象符号见 `build/ssh-native-g1/stock-run.lOHF9Oai/openssh-build/ssh_api.o` 和 `sshconnect.o`）。

### 4. 当前 KEX guard/hook 与 callback adapter/JNI 对接

#### Native KEX guard

`android/app/src/main/cpp/ssh_kex_guard.h:7-21` 的公开 native contract 只有 role bind/unbind、`begin`、`approved`、`signed`、`complete` 和只读 `ready`。实现的状态机是：

`bind role -> patched begin -> host approved -> signature verified -> patched signed -> inbound NEWKEYS / patched complete -> kex->done -> ready`。

它不会调用 JNI，不持有 `JNIEnv*`，也不会生成 host classification、trust proposal 或 private-key bytes。`ssh_kex_guard.c:11-20,85-125` 的 slot 只保存 role/host digest/state。

#### Native adapter

`ssh_callback_adapter.h:32-53` 定义同步 target：host key、KEX completion、boolean credential、release，以及可选 bounded `credential_bytes`；`ssh_callback_adapter.h:55-82` 定义 open/cancel/close 和四个 callback entry points。`ctos_ssh_callback_open()` 把 callback 绑定到“创建时的 event-loop thread”、role/PID/UID/deadline/environment（`ssh_callback_adapter.c:209-252`）。实际 callback 进入前再次检查 exact thread、environment、role snapshot、deadline、re-entry/cancel state（`ssh_callback_adapter.c:150-183`）；close 必须在同线程，release 失败可重试（`ssh_callback_adapter.c:264-299`）。

#### JNI boundary

`ssh_callback_jni.c:50-60` 只接受同一 JavaVM 且 `GetEnv()` 返回当前传入的 attached `JNIEnv*`；host callback 把 key type/fingerprint/base/replacement digest 复制成 Java对象（`ssh_callback_jni.c:74-103`）；KEX callback 把 cycle/session digest 复制成 Java array（`ssh_callback_jni.c:106-123`）；typed bytes 复制回 native caller buffer，并在成功/失败路径清零 managed array 和 native output（`ssh_callback_jni.c:137-203`）。JNI open 找到 `onNativeHostKey`, `onNativeKexComplete`, `onNativeCredential`, optional `onNativeCredentialBytes`，然后调用 C adapter open（`ssh_callback_jni.c:225-267`）。

#### Java provider contract

- `SshNativeCallbackAdapter.java:101-149` 把 binding 创建限制在当前 Java event-loop thread，并把 native role/deadline 传入 JNI。
- `SshNativeCallbackAdapter.java:233-269` 对 NEW host 允许 managed provider 提供 exact proposal，然后仍由 `SshHostKeyAuthority.requireVerified()` 校验；known host 不允许借 proposal 改写信任。
- `SshNativeCallbackAdapter.java:272-289` 只有 native 已观察 signed KEX + inbound NEWKEYS 后才接受 `SshKexProof.fromNative()`。
- `SshNativeCallbackAdapter.java:320-363` 只有 host proof 和 KEX proof 都存在且与 role/hop 匹配，才通过 `SshManagedProvider.credentialBytes()` 取得 bounded typed bytes；`SshManagedProvider.java:58-103` 会再次检查 role/proof 并用 `SshCredentialBridge.release()` 交付。
- `SshHostKeyAuthority.java:141-177` 对 NEW host 要求 path/object/role/target/host/port/fingerprint/baseDigest/replacementDigest 全部 exact match，并调用 review issuer；CHANGED/REVOKED/CERTIFICATE/INVALID fail-closed。

所以当前接口的正确接法不是让 OpenSSH 直接请求 private key。KEX-only 阶段不得调用 `ctos_ssh_callback_credential_bytes()`；待 `ctos_ssh_callback_kex_complete()` 成功形成 `SshKexProof` 后，后续 userauth 才能按 typed kind 请求。当前 C/JNI adapter 可以承载这个 proof 顺序，但还没有把 `struct sshkey*` verifier 或 `ssh_packet_next()` loop 接到 adapter。

### 5. 明确的 private/public 边界和必须新增的 wrapper

| 符号/结构 | 当前性质 | 本任务结论 |
|---|---|---|
| `ssh_init`, `ssh_free`, `ssh_set_verify_host_key_callback`, `ssh_packet_next`, input/output buffer APIs | `ssh_api.h` public（`ssh_api.h:36-134`） | 可作为窄 production caller 基础 |
| `ssh_packet_set_connection`, `ssh_packet_set_nonblocking`, FD getters | `packet.h` public declaration，底层 packet API | 可由窄 wrapper 使用；要写清 FD ownership |
| `kex_ready`, `kex_setup`, `kex_send_kexinit`, `kex_input_kexinit`, `kex_input_ext_info` | `kex.h` exported | `ssh_init`/`ssh_packet_next` 已覆盖初始 KEX；wrapper 只在明确需要时调用，不能替代 dispatch |
| `struct ssh`, `struct kex` | header-visible，但含内部 state/dispatch/session_id | 可用于同版本 wrapper；不应当被 Java 或长期公共 API 直接暴露 |
| `_ssh_exchange_banner`, `_ssh_read_banner`, `_ssh_send_banner`, `_ssh_verify_host_key` | 非窄 public API 的内部符号；stock object 导出部分 underscore symbol | 不直接调用；让 `ssh_packet_next` 驱动 |
| `kex_input_newkeys`, `kex_reset_dispatch`, `input_kex_gen_reply`, `input_kex_dh_gex_reply` | static/internal | 不可直接调用；依赖上游 dispatch |
| `ssh_connect_direct`, `ssh_proxy_connect`, `ssh_proxy_fdpass_connect` | static | 不可链接为 production entry；含 stock network retry/proxy/fork/exec 语义 |
| `ssh_connect`, `ssh_kex2`, `ssh_login` | exported，但高层 stock client entry | 不调用；分别受全局 `Options`、blocking/fatal dispatch、userauth 影响 |
| `ctos_ssh_kex_*` | 当前项目 production/fixture guard contract | 需要在新 wrapper 与 patched OpenSSH object 中绑定，不能当 Java callback |
| `ctos_ssh_callback_*` | 当前项目 synchronous C adapter | verifier 和 event-loop completion wrapper 显式调用；必须传同一 attached `JNIEnv*` |

必须新增的 native glue 至少包括：

1. 一个 client lifecycle/step wrapper：创建 params、接管 FD、nonblocking、role bind、callback open、host verifier 注册、`ssh_packet_next` 的 input/output step、done 后 session digest 和 close ordering。
2. 一个 verifier bridge（可与上述 `.c` 合并）：从 `struct sshkey*` 调用现有 `sshkey_ssh_name()`/`sshkey_fingerprint()`/`sshkey_fingerprint_raw()`（声明在 `sshkey.h:189-191,249-250`），再调用 `ctos_ssh_callback_host_key()`；但 classification/base/replacement digest 必须来自真正的受管 known_hosts view/provider，不能由 fingerprint wrapper 猜测。
3. 一个 JNI/native entry（可以独立新文件，也可以在冻结后扩展现有 `ssh_callback_jni.c`）：让 Java 在其 event-loop thread 驱动上述 lifecycle/step，并把当前 `JNIEnv*` 作为 adapter environment。现有 JNI open/cancel/close 本身没有 client create/step/FD ownership 方法。

### 6. 最多 3 个新增 production files 的建议

建议最多新增以下 3 个文件，且只实现到 inbound NEWKEYS：

1. `android/app/src/main/cpp/ssh_native_client.h`：内部窄 contract。参数只接受受管、已解析的 connection target/`sockaddr`、KEX proposal、role token、host digest、callback token/environment 和 bounded step buffers；不接受 argv、shell、ProxyCommand、普通 pathname 或 Java supplied arbitrary FD。暴露 `create/step/close` 类生命周期，不把 `struct ssh` 或 OpenSSH private state 出口化。
2. `android/app/src/main/cpp/ssh_native_client.c`：实现 custom socket/connect（不复用 stock `ssh_connect`）、`ssh_init`、`ssh_packet_set_connection`、`ssh_packet_set_nonblocking`、role bind、adapter open、`ssh_set_verify_host_key_callback` 和 read/write step。`step` 内调用 `ssh_packet_next`，按实际网络读写量调用 input append/output consume；看到 `ssh->kex->done` 后用 `ssh_digest_buffer` 得到 32-byte session digest，再同步调用 `ctos_ssh_callback_kex_complete`。close 顺序应是停止 step、adapter close、`ctos_ssh_kex_unbind`、`ssh_free`，并对 role release failure 保留可重试状态。
3. `android/app/src/main/cpp/ssh_native_client_jni.c`：仅做 Java event-loop 线程的 typed lifecycle/step 锚定；调用既有 `SshNativeCallbackAdapter.Native.open/cancel/close` contract 或等价的已冻结 JNI binding，禁止另建异步 broker/handler thread。该文件不能新增 auth、PTY、forward、service 或 UI 方法。

这 3 个文件仍不足以“实现” exact known_hosts CAS：host verifier bridge 应暂时 fail-closed，直到受管 public `config/known_hosts` view、exact trust proposal/CAS writer 的 native-facing contract 真正冻结。若把 provider 接口也塞进以上 wrapper，必须先证明它不会把普通明文路径、Java arbitrary FD 或未绑定 proposal 引入 OpenSSH；在此之前不应声称 production caller 可编译完成。

明确不做：`sshconnect2.c` 的 `ssh_userauth2`/`userauth`、PTY/channel、Local/Remote/Dynamic forward、remote service、后台 service、终端 UI、ProxyJump/ProxyCommand 执行器。ProxyJump 的受管多跳应在未来由每跳独立 role/client wrapper 组合，而不是重新启用 `ssh_proxy_connect`。

### 7. 严格 host-test 验证点

**Source/link gate**

- 从干净 pinned 10.6p1 + 当前 KEX patch 编译，确认 patch 只命中 `kex.c/kexgen.c/kexgexc.c`，同时保留 `source-archives.sha256`、patch hash 和 object manifest。
- 新 wrapper object 做 `nm`/link-map/静态源码检查：不得引用 `main`, `getopt`, `closefrom`, `fork`, `vfork`, `exec*`, `waitpid(-1)`, `ssh_connect`, `ssh_login`, `ssh_userauth2`, `ssh_proxy_*` 或 `ProxyCommand`；不能因链接整个 stock executable object 而把禁止路径带入生产库。
- `ssh_api.o`、packet/KEX objects 和 guard objects 在同一 pinned config 下编译；normal APK 仍排除 fixture-only `libctos_ssh_kex_probe.so`、`ssh_native_launcher` 和 synthetic control transport。

**Transport/event-loop gate**

- 使用 host-only `socketpair` 或 loopback synthetic peer，验证 partial banner、partial binary packet、partial write、EAGAIN、output consume 精确字节数；不要只测当前 fixture 的 in-memory copy。
- 记录并断言 `ssh_init -> fd attach -> nonblocking -> callback bind -> verifier register -> first step` 顺序；第一次 `ssh_packet_next` 必须产生/消费 identification 和 KEXINIT，不允许调用 blocking `kex_exchange_identification`。
- 两端都用 pinned OpenSSH packet/KEX code 生成测试 transcript；不能手写 KEX packet parser。测试 `curve25519`, ECDH, DH, DH-GEX 以及当前 fixture 已覆盖的 sntrup/ML-KEM 路径，并覆盖 Ed25519/ECDSA/RSA host-key combinations。

**Security/order gate**

- 断言 host callback 先于 `ctos_ssh_kex_approved`；错误/未知/changed/revoked/certificate/invalid host、proposal role/host/port/fingerprint/base/replacement 任一不匹配时，KEX 失败且无 credential callback。
- 断言 `ctos_ssh_kex_signed` 只在 `sshkey_verify()` 成功后触发；篡改签名时不得进入 signed hook、derive keys 或 NEWKEYS。
- 断言 inbound `SSH2_MSG_NEWKEYS` 先完成 `ssh_set_newkeys(MODE_IN)`，再完成 guard completion，最后 `kex->done` 为 true，才调用 `ctos_ssh_callback_kex_complete`；Java 得到的 `SshKexProof` 必须含非零 32-byte session digest。
- 断言 typed password/private-key 请求在 KEX proof 前全部拒绝；KEX 后只允许正确 role/hop/host proof + KEX proof 的 kind，返回数组/native buffer/所有失败路径均清零。
- 覆盖 deadline、cancel、native close failure retry、Java exception、JNI attached-env mismatch、wrong thread、re-entry、stale role、cross-UID/PID、duplicate close 和 connection teardown。

**Protocol lifecycle gate**

- 初始 KEX 完成后对 `SSH2_MSG_EXT_INFO` 做明确有界处理；不能把它静默升级为 userauth/service。
- 触发 rekey，验证每个 cycle 的 begin/approved/signed/complete 重新绑定、旧 KEX proof 不可复用，且 inbound NEWKEYS 后新 digest/sequence 正确。
- 连接失败、peer EOF、timeout、cancel 和 normal close 均验证：FD 恰好关闭一次、role 先撤销/解绑、callback release 可重试且无 stale callback/secret。
- 通过 host test 只能证明 pinned source/wrapper/KEX/NEWKEYS；不能把它写成 Android device、真实 vault、外部 SSH account、userauth、PTY、forward 或 service 验收。

## Caveats / Not Found

1. **无法安全建立完整 production call sequence 的关键阻塞**：当前仓库没有真正调用 `ssh_api` 的 production client wrapper。`ssh_api` 能建立 struct、接管 FD、增量交换 banner、驱动 dispatch 和到达 NEWKEYS，但“何时从真实 OpenSSH verifier 产生 `HOST_KNOWN/NEW/CHANGED/REVOKED` 及 base/replacement digest，并把 exact proposal/CAS 结果交给 Java”没有现成 public API。`sshconnect.c` 的 `verify_host_key()`/known_hosts 路径依赖 stock globals/options 和更高层 caller；直接调用会引入尚未冻结的 provider 及信任语义。不得虚构一个现成 `ssh_host_callback` 或 `ssh_known_hosts_cas` API。
2. **当前 patch 与 adapter 尚未接通**：KEX patch 只调用 `ctos_ssh_kex_begin/approved/signed/complete`；它没有 callback token、`JNIEnv*` 或 Java invocation。`ctos_ssh_callback_kex_complete` 必须由未来 wrapper 在 `kex->done` 变 true 后调用；当前 fixture `ssh_kex_test.c` 只测 native guard/host fixture，不调用 `SshNativeCallbackAdapter`。
3. **`ssh_api` 的可见 struct 不是稳定生产 ABI**：`ssh_api.h` 间接 include `packet.h`/`kex.h`，因此 wrapper 可以在这一个 pinned source tree 读 `ssh->kex->done/session_id`，但这些字段仍属于 OpenSSH internal state。若未来升级 pin，必须重新做 compile/source map，不应把这些字段暴露给 Java。
4. **FD ownership 是上游行为，不是 adapter contract**：`ssh_packet_close()` 会关闭 connection FD；当前 callback adapter 只管理 role/JNI/provider release，不管理 socket。新 wrapper 必须明确 socket owner，避免 Java/SSH 双重 close。
5. **当前 build 不是 unmodified stock**：`stock-run.lOHF9Oai` 已有 Bionic include/resolver ABI compatibility patch（`evidence/local-changes.txt:1-9`），而 KEX guard 又只存在于独立 fixture tree；不能把 stock ELF、fixture KEX artifact 或既有 host/JVM gate 拼接成 production OpenSSH client 已实现。
6. **未执行外部/设备验证**：本记录只做源码、对象符号、patch 和已有 host fixture 证据映射；没有连接真实 SSH endpoint、安装 APK、运行 ARM client、访问 vault 或修改任何 production source/Gradle/spec。

## Related specs

- `.trellis/spec/backend/ssh-native.md:1-7,654-695,829-879`：生产 SSH 尚未实现、G1/G2/G3 gate、KEX/NEWKEYS fixture 边界，以及用户确认的 synchronous attached JNI callback/managed provider contract。
- `.trellis/tasks/10-08-ssh-terminal-client/prd.md:1-18`：当前 task 的同进程 event-loop、同步 JNI callback、managed public file/trust/private-key handoff 和 KEX-only 未完成状态。
- `.trellis/tasks/10-08-ssh-terminal-client/design.md:1-25`：当前 fixture-only launcher 与未来 production event loop/provider 的分界。
- `design/ssh-terminal.md:1-6`：用户已确认的 topology 和 provider 方向；真实 OpenSSH caller、file view、trust CAS、auth/PTY/forward/service/UI 仍开放。

## External references

- OpenSSH portable 10.6p1 pinned archive/tag：本地归档和 hash 以 `build/ssh-native-g1/stock-run.lOHF9Oai/evidence/source-archives.sha256:1` 为准；上游版本入口为 `https://github.com/openssh/openssh-portable/tree/V_10_6_P1`。
- OpenSSH 10.6 release notes / archive provenance：现有固定版本研究记录 `.trellis/tasks/10-08-ssh-terminal-client/research/android-openssh-build-inputs-20261013.md:11-18`；本次没有把外部网页内容当作本地 build 通过证据。

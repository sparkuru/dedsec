# SSH 终端客户端接管档案

## 接管摘要（当前有效，2026-10-19）

- 目标与完成判据：继续原 SSH 客户端任务；下一增量以合成 peer 完成认证后 shell 输入输出、resize、正常退出及资源回收为验收。
- 范围与权限：用户已确认先提交认证阶段，再继续 session channel / PTY；本次只提交和交接。后续本地实现已获确认，设备须符合届时当前授权，真实账号不在合成测试授权内。
- 当前状态（确认）：Android/JNI 密码、未加密/有界加密私钥和真实 NEW/CAS 认证完成；父任务仍 `in_progress` / `implementation_complete=false`。
- 已完成（确认）：fresh JVM244、ARM1,244,010 checks、normal94任务/lint/Release/v2 及四项 Android 独立合成方法通过；证据见下方认证记录和 [验证记录](verification.md#20261019-android-auth)。
- 未完成：session channel / PTY / shell、现有终端界面接入、forward/service，以及 KBI/MFA、加密 PKCS8 和兼容性矩阵。
- 下一步：用 `$trellis-continue` 恢复 `.trellis/tasks/10-08-ssh-terminal-client`，研究并冻结认证成功后的 channel/PTY 合同，再实现本地合成闭环。
- 阻塞与停止条件：暂无本地阻塞；设备不可用不阻塞本地工作。遇到真实凭据需求、未授权设备操作或需改变认证安全合同，先明确范围；不能把 PARTIAL/CHANGE_REQUIRED 当成功。
- 工作状态（确认）：workspace `/home/wkyuu/cargo/repo/06-dedsec/projects/ctos`；branch `antitrust`；认证实现提交 `251b0f3`，随后的交接/日志提交见 `git log -2 --oneline`。父任务不归档、不 push。
- 环境（确认）：zsh、项目 `./hako` Docker 构建入口；临时证据 `/tmp/ctos-ssh-android-auth-20261019.yqKxcIaN`，临时工件缺失时按验证文档重建。
- 最近更新：2026-10-19。
- 最后验证（确认）：2026-10-19 提交前重新执行 `sha256sum -c` 的 source-final21项、runtime-final9项均通过；本次未重跑构建或设备测试。

## 2026-10-19 Android/JNI 与私钥认证：已实现并完成独立实测

Java/JNI 已接入密码与私钥认证，支持 Ed25519、ECDSA p256/p384/p521、RSA SHA-2；
未加密私钥和有界加密 OpenSSH/传统 RSA、EC PEM 走同一证明绑定的一次授权。新增 CSASv2
把精确私钥和口令条目绑定到实际远端；修复真实文件视图与角色期限兼容、首次 NEW 主机
写回撤销自身视图，以及最后一次库读取后的凭据交付检查。

最终 fresh JVM244/0/0/0、ARM QEMU1,244,010 checks、normal94任务/lint/Release/v2通过；
开发板四个精确方法各 OK(1 test)，覆盖密码、未加密/加密私钥及真实 NEW/CAS。测试包/进程
已清理，正式应用 hash 未变，未使用真实库或账号。普通 APK89eb6425… 未安装；加密PKCS8、
KBI/MFA、channel/PTY/forward/service/UI 和完整客户端 AC 继续开放。
[完整证据、格式与限制](verification.md#20261019-android-auth)。

接续顺序：从已完成的认证底层进入channels/PTY、转发与产品service/UI；KBI/MFA与重试策略、
rekey/压缩和更广服务器兼容仍需合同，不允许把partial/change当成功放行。当前父task保持
in_progress / implementation_complete=false。证据目录 `/tmp/ctos-ssh-android-auth-20261019.yqKxcIaN`；当前无设备测试包残留。
后续设备/真实库/账号操作按实际会话授权，不能把本轮合成授权扩成真实账号访问。

## 当前焦点：session channel / PTY（未开始）

用户在认证阶段完成后确认采用此顺序，并要求先提交当前状态、提供续做 prompt。
这是下一增量的范围确认，具体 API、状态机、流控上限与安全合同仍待源码研究和冻结。
forward/service、KBI/MFA、加密 PKCS8 与更广兼容不并入首个 PTY 增量。

## 下一步行动（当前有效）

1. **恢复原任务并核对基线** — 状态：未开始。
   - 前置条件/触发：新会话在 ctOS workspace 中接续，先读 `AGENTS.md` 与当前摘要。
   - 执行位置：`/home/wkyuu/cargo/repo/06-dedsec/projects/ctos`。
   - 命令：`python3 .trellis/scripts/get_context.py`；`python3 .trellis/scripts/task.py current`；`python3 .trellis/scripts/get_context.py --mode phase`。
   - 预期与判据：active task 为 `10-08-ssh-terminal-client`、状态 `in_progress`；读完原 PRD/design/implement/check、jsonl 及相关 spec，识别并保留新出现的用户改动。
   - 失败处理：任务指针不同先核对用户意图；临时证据消失只代表需重建，不代表认证尚未实现。
2. **研究并冻结认证后 channel / PTY 合同** — 状态：未开始。
   - 前置条件/触发：上一步基线一致，应用 `trellis-before-dev`；沿用 pinned OpenSSH 和现有会话 owner/取消边界。
   - 文件：`android/app/src/main/cpp/ssh_native_client.[ch]`、`ssh_session_jni.c`、`android/app/src/main/java/im/majo/ctos/SshNativeSession.java`、现有终端 bridge；同步 `design/ssh-terminal.md` 和 `.trellis/spec/backend/ssh-native.md`。
   - 预期与判据：明确仅 SUCCESS 可开 session、channel open/confirm/failure、pty-req/shell、stdin/stdout/extended-data、window/背压、resize、EOF/exit-status/close、cancel/deadline 和资源所有权；先冻结边界再编码。
   - 失败处理：不以 KEX complete/service-ready/PARTIAL/CHANGE_REQUIRED 放行；不为接续而绕过 proof、vault 撤销或 owner-thread 检查。
3. **实现合成闭环并验证后接入现有终端界面** — 状态：未开始。
   - 前置条件/触发：合同已冻结，复用现有 native/JVM/独立 fixture 测试入口；先本地 native/Java/JNI，再接 UI。
   - 文件/命令：原任务 `implement.md` / `check.md`；构建与 QEMU 命令沿用 [验证记录](verification.md#20261019-android-auth)，按新边界增加必要验证。
   - 预期与完成判据：合成 peer 中完成认证→申请 PTY/shell→输入命令并验证输出→resize→正常退出；拒绝、背压、EOF、取消、期限及 close 重试不泄漏 FD/channel/role/view。UI 接入后能在现有终端完成相同交互。
   - 失败处理：设备仅在届时当前授权覆盖时运行独立合成 fixture 并清理；不得自动安装普通 APK、访问真实库/账号或扩大远端操作。

## 接管验证（当前有效）

- `git status --short` / `git log -2 --oneline`：确认提交基线及新会话本地改动；不要覆盖未识别改动。
- `python3 .trellis/scripts/task.py current`：应为 `.trellis/tasks/10-08-ssh-terminal-client`，无需新建或重复 start 已进行中的任务。
- 若临时证据仍在，可在 workspace 重验 `sha256sum -c /tmp/ctos-ssh-android-auth-20261019.yqKxcIaN/source-final.sha256`；后续修改源码后旧哈希不再作为新版本的通过证据。
- 接管判据：原任务和认证基线一致、当前改动归属明确、已读新的 channel/PTY 计划。完成第一个动作后原地更新本摘要、当前焦点和行动状态。

## 历史记录说明

以下保留历次阶段事实与当时的下一步。旧标题中的“最新/当前”、未提交、缺少 ARM/JNI/auth
以及 `.git` 只读等描述仅对当时有效；当前状态以上方摘要、当前行动和最新验证记录为准。

## 上一阶段接管状态（2026-10-19，C-only password USERAUTH 本地验证通过）

- 原task/branch不变，in_progress / implementation_complete=false，未提交归档。
- 实现仅native_client.h/.c与native_client_test.c三文件：显式begin_password_auth和const
  result API，从ownedCSAS用户名及一次selected PASSWORD交付构造唯一请求，8192/noNUL/
  padding64；currentproof/output/rekey前后检查，scratch与outgoingplaintext按合同清理。
- 独立requestpending/await/final状态保留旧KEX/service/getter停止点；全部request排空后
  才接受SUCCESS/REJECTED/PARTIAL/CHANGE_REQUIRED，无自动重试/change/MFA/channel。
  Banner/methods/prompt/disconnect有界丢弃；const status无provider/I/O/clientmutation。
- 实现代理停止写入；checker正式只读无finding。main补强pending-BANNER正例：同一步
  banner+partialwrite+byteswritten>0、peer尚未收完整request，最终SUCCESS/provider1。
- 67新增真实加密连接场景；finalfocused7tasks13s，callback35/strictnative通过；normal
  94tasks19s（23exec71uptodate）、lint/Release通过。JVM221/0/0/0为15份保留报告，未新跑。
- 最终QEMU785537checks/exit0，binarySHA `db01d8f7aec9d7a8e9cf6f2e8477d8592f4a5041ad41e3d8025a1e4687d06829`。
  first780038与augmented785653另存；source-final3项和runtime-final6项hash验证通过。
- APK `983f2032f89adc6a71f4968c77219e455b6a6a9f3c51b12d101e321f39ad09ad`，v2/one signer、archive83/
  sixsessionJNI/forbidden0/仅生产SSHlib；未安装发布或复制dist。
- 证据 `/tmp/ctos-ssh-password-userauth-20261019.obv9zw5H/`；
  [验证](verification.md#2026-10-19--explicit-native-password-userauth-protocol)记录命令、三次binaryhash和覆盖限制。
- ENOMEM/hash/opaqueproof漂移、构包故障reset、padding/upstreamwipe与精确postparsecancel
  未动态注入；missingtarget复用旧gettercase。IGNORE/DEBUG/UNIMPLEMENTED未支持，不能
  宣称完整OpenSSH transport或压缩兼容。无设备/真实库账号/外部sshd操作。
- 后续需设计private-key parse/equal_public/sign与signature算法及selectedpassphrase/KBI
  能力，再完成Java/JNI service/auth、Android完整KEX/auth和channels/PTY/forward/service/UI。
  预期publicblob仍非实际私钥匹配证明，partial或change结果不能作为继续放行/取秘密依据。

## 最新接管状态（2026-10-19，selected credential handoff 本地验证通过）

- 继续原 task/branch，in_progress / implementation_complete=false，未提交归档。
- C/JNI 与 Java 共12文件完成本切片：client 从 owned CSAS 派生 kind/receipt，
  READY、成功 completion digest/current cycle/raw session ID SHA 前后复查；私有 scratch
  在 postcheck 通过后才复制 caller。attempt 后失败 terminal，不自动重试，无 legacy 回退。
- Java unique `(I[BJ[B)[B` 核对 owned claim/frozen selection/current host/KEX；所有 selected
  路径共享一次尝试，首次 native claim 后永久拒绝旧对象路径。AppSecrets receiver 前及
  callback 返回前检查 current invocation/proofs/cancel/clock；未添加 Session secret getter。
- 已修 JNI 分步异常检查、finish 时钟期间取消/重入与 Error 逃逸的 invocation 清理。
  Error 继续传播；source-reviewed result wipe 与动态 receiver wipe/owner cleanup 分列。
  正式 checker 无 remaining finding，代理停止写入，main 完成全部构建与执行。
- final focused66/0/0/0（57tasks33s）；normal94tasks66s（29exec65uptodate），
  fresh full JVM221/0/0/0、15reports，callback35、strict JNI/lint/Release/v2通过。
- 最终 QEMU323726checks/exit0，binarySHA
  `292d8c7e01cc2ac7bc125edcaece31e3a7226b00adfffb7e2a12c0fe2c50fd85`；
  first323722/SHA8f258cbc…另存。runtime-final.sha256 六项通过。
- APK `f329056e8ce108316dbd7d1674f4296b25ba286f0ce283030637cb33cba57a3f`；
  archive83/sixsessionJNI/forbidden0/仅生产SSHlib，未安装发布或复制dist。
- 临时证据 `/tmp/ctos-ssh-selected-credential-20261019.tCsurTHc/`；
  [验证](verification.md#2026-10-19--receipt-bound-selected-credential-handoff)记录命令、hash和覆盖限制。
- 无设备/真实 vault/账号操作。native cycle/raw-session drift、ENOMEM/hash 故障未注入；
  新 JNI 未在 Android 执行。接下来设计 USERAUTH_REQUEST/response 状态机；私钥必须实际
  parse/equal_public，selected passphrase/KBI 能力另行设计，不能凭 blob 声明推定匹配。
  完整 Android KEX/service/auth、真实 CAS/vault、PTY/forwarding/service/UI 与产品 AC 开放。

## 最新接管状态（2026-10-19，CSAS native metadata 已实现）

- 原task/branch保持 `10-08-ssh-terminal-client` / `antitrust`；in_progress /
  implementation_complete=false，未提交归档，保留既有脏改动。
- 9文件Java/JNI/C/test已实现canonicalCSASv1，编码来自adapterfrozenplan，含exactrole
  token/hop/nonce/binding和username/entry/path/keyIdentity/publicblob，max1MiB+2048。
  Native严格校验后ownedcopy/hash；所有新增可失败步骤在sockettransfer前。
- 新explicit authenticationSelectionReceipt为liveowner只读metadatafingerprint，允许
  preKEX/complete；Java核对expected32bytes且wipe临时array，不在open成功后自动调用。
  Legacy null兼容，旧binding对nonnull默认拒绝。Close成功清理ownedwire，失败可重试。
- Earlycheck两finding已修：directadaptercancel/closing新getterguard与localcancel映射，
  encoder scratch nullabletry/finally；before/duringcancel回归具区分力，正式复核无遗留。
- Focused56tasks28s、JVM53/0/0/0、strictJNI/lint；normal94tasks64s（29exec65uptodate），
  freshJVM208/0/0/0、15reports，view/native/callback24/production/Release/v2通过。
- 最终ARM QEMU **253155checks/exit0**；binary SHA256
  `5c18eac4e893e5d346e0afafec8174c2f98ef517d47486a9117c8bb5a09c6821`，公开API30
  Bionic与runtime-final.sha256一致。初轮253110/binary另存，不能混用hash。
- APK `bda806ca9670f51cdae1160d4615ec59098788888d550268691cca99a2e41ccf`，v2，
  archive83/sixsessionJNI/forbidden0/SSH仅生产lib；未安装发布或复制dist。
- 证据 `/tmp/ctos-ssh-native-selection-20261019.4F7DmbUU/` 包含first/finalbinary、
  runtime-final.sha256、qemu/buildlogs、focused-jvm.json、packaging.json、signature.log；
  [验证](verification.md#2026-10-19--csas-native-selection-metadata-and-receipt)含完整命令与限制。
- Cgetterwrongthread仅复用callbackgate+源码，新getter动态测env；malloc/hashfailure和
  accepted1MiBblob未注入。修改后JNI未Android执行；无设备/真实vault/账号操作。
- 下一切片为receipt-bound native credentialcallback与currentacceptedKEX/service-ready
  联合核对，再实现auth请求。Publicblob仅声明，需真实privatekeyparse/equal_public；
  selectedpassphrase/KBI及vault key:identity能力另行设计，Android与产品AC继续开放。

## 最新接管状态（2026-10-18，managed auth selection 已实现）

- 仍为 `10-08-ssh-terminal-client` / `antitrust`，未提交/归档，完整task
  `in_progress` / `implementation_complete=false`；保留原有脏改动。
- 新 `SshAuthenticationSelection` 固定exactroleobject、explicitstrictUTF8username与
  PASSWORD entry或PRIVATE_KEY path/keyIdentity/expectedpublicblob。exactobject为managed
  receipt；role.binding不含nonce，不能以其string equality替代exactrole检查。
- adapter.selectAuthentication须在owner、任意callback/issuedclaim前one-shot调用；
  failedopen不重开setup。selected byteoverload要求同selection，legacykind-only拒绝；
  unselectedlegacy合同保留。provider直接bridge.openSelection，不调用旧requestsource；
  Request保留selection，finalEffectGuard重复固定资源/role/host/KEX/operation检查。
- 7个Java文件实现与正式checker完成，无finding。新增12tests；focused46/0/0/0和lint
  通过，normal91tasks57s（26exec65uptodate），fresh全量JVM201/0/0/0、15reports，
  pinnedproduction/strictnative/Release/v2/包装检查通过。
- APK SHA256 `e6c4d58b28537e5b14a351ea1a35b4d72d64781dd34c05e0dea4d452ef57470b`，
  archive83/fiveJNI/forbidden0/SSH仅生产lib；未安装/发布或复制dist。34个C/JNI源码hash及
  上轮ARMtestbinary不变，production builder强制重生archive/lib，不能要求生成物hash不变。
- 临时证据 `/tmp/ctos-ssh-auth-selection-20261018.y8DyzgNU/` 含focused/normal-build.log、
  focused-jvm.json、native-before.json、packaging.json、signature.log。详细命令与hash见
  [验证](verification.md#2026-10-18--managed-immutable-ssh-authentication-selection)。本轮没有QEMU或设备动作。
- 覆盖限制：selected专用mid-returncancel/reentry没有新测试，复用同一pipeline旧回归。
  expectedpublicblob只是声明，未解析privatekey/equal_public；selection未序列化到C/JNI。
- 下一切片应设计native selection receipt/explicitusername与当前acceptedKEX/service-ready
  绑定，再发送认证请求。selected passphrase/KBI保持拒绝；passphrase的key:identity与
  role target不同，需要单独的vault能力合同，不改AppSecrets/VaultOperation绕过。
  Android完整KEX/service/auth、真实CAS/vault、PTY/forward/service/UI与产品AC仍开放。

## 最新接管状态（2026-10-18，C-only userauth service 已实现并实测）

- 当前 task/branch 仍是 `10-08-ssh-terminal-client` / `antitrust`；未提交/归档，
  `implementation_complete=false`。保留本轮以前全部未提交改动。
- 六个 C 文件已完成实现与正式检查代理复核，无 remaining finding：新增显式
  `begin_userauth_service` / `userauth_service_ready` 与 callback `check_live`。
  后者复用 owner/env/role/cancel/reentry/deadline validation，不调用 provider target。
- 原 `complete()`/Java/JNI 保持 KEX-only；service begin 与 active step 校验唯一成功
  completion cycle 和当前 guard。请求排空后才接受 exact/payloadless/empty-string
  service reply，ready 后停止；KEX20–49 dispatch 禁用，畸形/越阶段/rekey terminal。
- 加密 synthetic KNOWN、NEW/CAS peer 实际解密一个固定 service request；全部正反例、
  liveness/环境恢复和 close retry 在 ARM QEMU/Bionic 通过，credential spies始终为零。
  direct cycle mismatch 未动态注入（ssh 指针 opaque），该分支仅源码复核。
- focused7 tasks / 14s；normal **94 tasks全部重跑 / 1m24s**，fresh JVM189/0/0/0、
  callback24、view/native/production gates、strict native/三ABI Release/lint通过。
- 完整重建后的最终 binary SHA256
  `3fcaee6aab8647adbdfd4af0cf4ce4f18ab6387d86b72ea7f687bc4b02079560`，实际
  QEMU **246,808 checks / exit0**。初轮246,974/checks及旧binary证据另存，不混用hash。
- APK SHA256 `8cd6da66d836255341b81dd30577b84a85998727865a84f74154f28c8190f4a6`，
  v2 pass，archive83、五sessionJNI、forbidden0、SSH只生产lib；未安装/发布或复制dist。
- 临时证据 `/tmp/ctos-ssh-userauth-service-20261018.uFA94Ngt/` 含 `.final` binary、
  runtime-final.sha256、qemu-final.log、normal-build.log、packaging.json/signature.log；
  Bionic仍来自原公开API30 runtime。复现和证据边界见 [验证](verification.md#2026-10-18--native-post-kex-userauth-service-negotiation)。
- 本切片无设备/真实库/账号/Root/系统设置操作。下一工作先冻结 wire username 与不可变
  credential-selection/entry-or-key/public-key binding，再实现 typed authentication；
  另需 Java/JNI service 接入与 Android完整KEX/service/auth验证。不能从 service-ready
  推定已认证、typed credential、PTY/forward/service/UI 或产品 AC 完成。

## 最新接管状态（2026-10-18，Android JNI 分片/EOF 实测通过）

- 用户报告设备恢复；`.14` 已确认 AIO-3568J / API30 / arm64-v8a / RUNNING_UNLOCKED。
- 复核原精确方法后，用当前 production 修复重建独立 fixture：112 tasks / 20s 成功，
  lint/Java/native packaging、v2 同证书/独立包名/target 通过；没有修改源码。
- 新 main SHA256 `eb2349b9da92d2ade7e55008db21a4ebf5a95de3c2344b6cd6708fed322c5bc2`；
  test SHA256 `d0634aa524053b14a94aefe9eec0ad12a3749a5140537fd7b9406c1c0503ee87`。
- 原精确 `SshNativeSessionPlatformTest#syntheticNativeSessionHandle` **OK (1 test)，
  0.202s，method status0 / runner code-1 / host exit0**。两个 session 的分片 banner、
  完整初始 KEXINIT 输出读回、KNOWN 核验/revoke，以及独立 EOF terminal/close 均实跑。
- 两包随后卸载，包/进程均无残留；正式 APK 前后仍为 `3bc762d9…`，未安装正式包，
  未访问真实库/账号或改变系统设置。命令和日志见 `design/verification.md` 最新节。
- 原锁定环境导致的 JNI 分片/EOF 待测关卡已关闭；下文同日以前的锁定/not-run 为历史。
  **完整 Android KEX、认证及产品 AC 仍开放**，QEMU native KEX 证据继续分开解释。
- 下一工作仍是既有 `next-userauth-20261017.md` 的本地 post-KEX service negotiation
  切片及 typed auth identity-binding 合同；尚未实施，不使用真实账号。Task 保持
  in_progress / implementation_complete=false，未提交或归档。

## 最新接管状态（2026-10-17，ARM native QEMU 已通过）

- 当前 task/branch 仍是 `10-08-ssh-terminal-client` / `antitrust`，未提交/归档。
- 宿主 `/usr/bin/qemu-aarch64` 10.0.13 可用；hako 内仍无 QEMU。只读拉取 `.14` 公开
  linker64 + libc/libdl/libm/libz 到 `/tmp/ctos-ssh-arm-runtime-20261017.O81ADtu5/` 后，
  实际运行 ARM production-native regression，而非仅 cross-link。
- 四处修复：role-policy errno 稳定 `EACCES`；固定 hash upstream banner incomplete reset；
  KEX completion 等本端 output 排空；failed-open 无论 attach 是否完成都解绑 KEX guard。
  检查代理追加 2/64-byte peer 完成/EXT_INFO 与 soft RLIMIT1 下真实 EMFILE/同资源重试回归。
- 最终 strict focused build 6 tasks / 12s 成功，actual ARM QEMU **65,838 checks / exit0**。
  executable SHA256 `5b030e73a72a67a08d381abd74c754703d46dd60036ebe1f05933110cde96fb8`。
  normal gate 94 tasks / 21s 成功（35 executed / 59 up-to-date）；JUnit 报告 189/0/0/0
  是 `UP-TO-DATE`，未本轮重跑。archive=83、五 JNI exports、forbidden stock caller=0。
- normal APK SHA256 `f3327427ce0af90ff7a8a5ea3b5cbba0ac7a7f1c396853c2fd247a45a2328dbd`，
  v2 通过，SSH 仅生产 `libctos_ssh_files.so`，未安装/发布。baseline tree 保持固定 hash。
- `.14` 仍 `RUNNING_LOCKED`；本次接续没有重装测试包，仅只读检查和复制公开 runtime。
  下节中的 fixture APK 是修复前版本；解锁后**先重建 fixture main/test，再记录新 hash**，
  运行原精确 opt-in 方法并清理，不能用旧 APK 冒充本轮 production 修复的 JNI 证据。
- 下一本地切片研究已在 task research `next-userauth-20261017.md`：先显式 post-KEX
  ssh-userauth service negotiation；typed password/private-key 仍需 wire username 和不可变
  credential selection 绑定合同，尚未实施，不使用真实账号。
- 完整任务仍 `in_progress` / `implementation_complete=false`。QEMU 证明 native 合成 KEX，
  不证明 Android JNI、真实 AppSecrets CAS/vault、认证、PTY/forward/service/UI 或外部 SSH。
- 复现命令、patch/source/runtime hashes、失败与修复记录见 `design/verification.md` 最新节；
  `/tmp` 证据为临时文件，若不存在须重建，不能推定仍可用。

## 最新接管状态（2026-10-17，本地通过 / 设备未执行）

- 当前 task 已恢复为本 session 的 `10-08-ssh-terminal-client`，未创建新 task。
- `SshNativeSessionPlatformTest#syntheticNativeSessionHandle` 新增两个独立 production
  session：预算 3 的分片 banner / 完整初始 KEXINIT 输出读回，以及 peer 半关闭后的两次
  public `SSH_SESSION_STEP` / view release / idempotent close。KNOWN 核验与显式 revoke 保留。
- 实现/检查代理已复核；fixture compile/assemble/lint 为 112 tasks / 20s 成功，v2 同证书。
  main SHA256 `78f71309dea6fc39804b9bc591c3db4a32dd291cdab5ddcd50fae02ff1e1bbf4`；
  test SHA256 `d0634aa524053b14a94aefe9eec0ad12a3749a5140537fd7b9406c1c0503ee87`。
- 用户本轮授权 `.14` 独立合成测试并清理；目标身份正确且两包安装成功，但系统报告
  `Package ... is not encryption aware!`，`dumpsys user` 为 `RUNNING_LOCKED`。测试未启动，
  没有 instrumentation pass/fail。用户选择“暂时无法解锁，保留本地结果”。
- 已精确终止自有挂起 instrumentation 进程并卸载两包；两包 `pm path` / `pidof` 为空。
  正式 APK 前后 SHA256 均为 `3bc762d9e6463ab9612393b9382923f4a29f613ceec3dbe4d9159aae0078f252`。
- 下一步：用户手动解锁后，在当前授权范围内重装这两个 fixture APK，运行精确 opt-in
  方法并清理；使用 host timeout 给 `am instrument -w` 加上外部时限，避免启动被拒绝后
  无限等待。不得通过 directBootAware、改锁屏或系统设置来绕过环境关卡。
- task 仍 `in_progress` / `implementation_complete=false`；本轮未提交。旧段落中的“当前”
  APK 和通过结果属于对应日期；详细命令/边界见 `design/verification.md` 最新节。

## 当前接管状态（G3.8 Android ARM/JNI KNOWN host-proof，2026-10-16）

- 最新补充的 Android JNI bounded EAGAIN：production session 在 socket ownership 转移后对空
  synthetic peer 调用一次 `step()`，断言 `bytesRead=0`、
  `inputAppended=0`、`readWouldBlock=true`，不约束同一 turn 的
  `bytesWritten`。随后 KNOWN `verifyHostKey`、revoke、close 仍通过。
  当前授权板精确 instrumentation 为 `OK (1 test)`、status 0、0.11s；最新 fixture
  main/test hash 为 `79776aad22be067e057fb2abd2a9af74bc365f890de44b1d3ba689c18d9560d3`
  与 `fc71429d9e06d33334cc51cbb9fa64a7b8f672f2bb8752922052aac5ec6dc71e`。
- 最新 Android runtime 增量：fixture 把 managed `ssh/known_hosts` 行改为同一
  OpenSSH wire public-key blob 的无换行 Base64，并在 production
  `SshNativeSession.open` 后、view revoke 前调用
  `session.verifyHostKey(key)`。当前授权板 `192.168.9.14:5555`（AIO-3568J、
  Android 11/API 30、arm64-v8a）精确运行该方法为 `OK (1 test)`、status code 0、
  0.089s；测试后两个 fixture 包已卸载，`pm path`/`pidof` 均为空。
- 本轮 fixture APK hash：main
  `1e3a10c709fed7d514a90e247494c61345da6a0f71dd142c17157f34ea4e9de6`；
  androidTest
  `2b9f80678220d89a7a67e23197bd5f85c1eee7543763bfb895da8d31a801f7e7`。
  这只证明 matching KNOWN host-proof 的 Java → JNI → native issuer/callback ARM 边界，
  不证明 KEX、packet、userauth 或完整 SSH runtime。
- native-owned session/view boundary 已实现：`ssh_view_handle.[ch]` 用 opaque token
  登记 native-owned public view，borrow/revoke 有延迟销毁；`ssh_native_client` 在成功
  open 后拥有 parsed host key、managed-view borrow 和 OpenSSH state；Java token 从不被
  转成 C pointer。
- `ssh_session_jni.c` 已是实际 bridge：64-slot monotonic session registry、owner
  pthread 检查、bounded key/fingerprint/string copy/wipe、八个 step scalar 和可重试
  close/destroy。`SshPublicFileLease` 的 revoke 在失败时保留 token 以便重试。
- host/normal gate：view handle `borrow/revoke/stale` 全通过，native client
  `gate=pass runtime=not-run reason=qemu-aarch64-unavailable`，production
  `gate=pass fixture=excluded`，callback=20，JUnit **189/0/0/0**，strict compile/
  lint/三 ABI Release/assemble 通过，production archive=83，APK SHA256
  `7ccac2fb8f705edd761d34574b9cead4b9e2296864dfe5568d2e9b1105fd849f`；五个 JNI
  symbols 可见，APK 内 SSH 相关 native 库只有生产 `lib/arm64-v8a/libctos_ssh_files.so`。
- `.14` 当前授权仅用于独立 `secretsFixture`：
  `SshNativeSessionPlatformTest#syntheticNativeSessionHandle` 与
  `SshFileViewPlatformTest#syntheticProtectedFileViews` 各 `OK (1 test)`，status code=0；
  fixture UID 10193/10194 已卸载，fixture package/process 无残留，正式 APK 安装后 hash
  为 `3bc762d9e6463ab9612393b9382923f4a29f613ceec3dbe4d9159aae0078f252`。
- G3.8 follow-up 修正 `ssh_native_client_step` 的错误传播：managed view deadline 到期保留
  `ETIMEDOUT`，revoke/invalid view 保留 `EACCES` 或底层精确 errno；新增空 peer EAGAIN、
  半关闭 EOF=`EPIPE`/后续 `EALREADY`、短 deadline 回归。focused host task 已 strict
  cross-link 通过，但 ARM binary 仍因无 qemu 未运行。
- task `10-08-ssh-terminal-client` 仍 `in_progress`，`implementation_complete=false`。
  本轮新增证明 host-only bounded step transport/error semantics、`KNOWN` host-evidence
  contract、`CHANGED`/`REVOKED`/`CERTIFICATE`/project-owned `INVALID` fail-closed
  classifications，以及合法 no-match `NEW` 的 candidate → `CAS_COMMITTED` precommit 边界；
  完整 ARM packet/KEX runtime、userauth/typed credential、PTY/forward/service/UI、qemu、真实
  vault 和外部 SSH 仍未完成。
- 另新增 fragmented server banner 后的 bounded client banner/KEX output accounting 回归；
  该案例也只完成 strict AArch64 cross-link，未执行 ARM runtime。
- 当前又新增 bounded `KNOWN` host-evidence 回归：synthetic managed `ssh/known_hosts` 含生成
  的 public key，production `ctos_ssh_native_client_verify_host_key` 对 exact key 成功并只
  调用一次 host evidence；另一把生成 key 在 pinned fingerprint guard 处返回 `EACCES`，
  callback 次数不增加。该项同样只有 strict AArch64 cross-link 证据。
- 随后新增 `CHANGED` host-policy 回归：offered key 的 pinned fingerprint 保持有效，但
  managed `known_hosts` 放入另一把生成 key；测试直接观察 issuer 返回 `HOST_CHANGED` 和
  digest metadata，再确认 production verifier 返回 `EACCES`、无 candidate handoff 且不调用
  host evidence。该项同样未执行 ARM binary。
- 随后新增 `REVOKED` host-policy 回归：matching offered/pinned key 在 synthetic managed
  `known_hosts` 中标为 `@revoked`；测试直接观察 issuer 返回 `HOST_REVOKED` 和 key/
  fingerprint/digest metadata、无 candidate handoff，再确认 production verifier 在 host
  evidence 之前返回 `EACCES` 且不调用 host evidence。该项同样未执行 ARM binary。
- 当前又新增 `CERTIFICATE` 与项目自有 `INVALID` host-policy 回归：前者使用生成并签名的
  有效 host certificate，后者使用截断 managed known_hosts 行加合法记录；issuer 直接检查
  classification、key/fingerprint/digest metadata 和无 handoff，production verifier 均在
  host evidence 前返回 `EACCES`。`INVALID` 由 `hostkeys_foreach_file(...,
  HKF_WANT_PARSE_KEY, ...)` 的 `HKF_STATUS_INVALID` 预检产生，不冒充上游 `HostStatus`；合法
  no-match 仍是 `NEW`。focused 与 normal full gate 均已通过，native runtime 仍未执行。
- `test_step_expired_view_preserves_errno` 与独立的 client deadline case 使用不同期限，
  分别覆盖 managed-view `ETIMEDOUT` 传播和 client terminal 分支。
- 当前 NEW/CAS follow-up 使用空 synthetic managed `known_hosts`：production verifier
  逐字节验证 replacement body、candidate metadata/digest、candidate-before-evidence 顺序
  与 `CAS_COMMITTED`；evidence rejection 会单次 abort candidate、terminalize client，后续
  `step` 为 `EALREADY`，close 仍可重试。focused 与 normal full gate 已重新通过；APK
  SHA256 为 `7f0bd0f14ec2a5ae9175ee340512acfdeaf0aa76f843834d1a3c87107c97993b`。
- 当前 synthetic KEX completion follow-up 使用同一 production caller 和匹配 managed `KNOWN`
  key，由非阻塞 OpenSSH client/server socketpair 走到真实 `kex->done`。host evidence 恰好一次
  且先于唯一 KEX-complete callback；cycle=1，callback session digest 非零并与 peer session
  ID 的 SHA-256 一致；client completion 后再次 `step` 为 `EALREADY`，server 端 EAGAIN/短写也
  有断言。focused 与 94-task normal full gate 通过；APK SHA256 为
  `7195a67fad84185714d00cbb2bbc899fbdf788b61f722282fae8b99ae8ba5f69`，ARM runtime 仍因
  qemu-aarch64 unavailable 为 `not-run`。
- 当前 NEW/CAS full-KEX follow-up 把空 managed `known_hosts` 接入同一真实 packet loop：candidate
  handoff、`NEW` evidence=`CAS_COMMITTED`、KEX-complete 按顺序各一次；candidate/evidence
  role/hop/port、ID/classification/key/fingerprint、replacement body、双 digest、cycle=1 和
  peer-bound session digest 均精确断言，completion 后 step 为 `EALREADY`，server/client fd
  二次 close 为 `EBADF`。focused 与 94-task normal full gate 通过；APK SHA256 为
  `7ccac2fb8f705edd761d34574b9cead4b9e2296864dfe5568d2e9b1105fd849f`，ARM runtime 仍因
  qemu-aarch64 unavailable 为 `not-run`。

> 下列早期条目保留为 G3.6 及更早阶段的历史上下文；遇到与本节冲突的“fail-closed JNI”
> 或 clean-worktree 描述，以本节和 `design/verification.md` 的 G3.8 记录为准。

- 目标与完成判据：在 ctOS 同一个 APK 内完成受管 OpenSSH SSH 客户端；production caller、事件循环、主机信任/凭据、认证、PTY、转发、服务/UI 与 Android 运行时联合验收均通过，且所有未测边界有明确证据。
- 范围与权限：只在 `projects/ctos` 内工作。允许 `.14`（`192.168.9.14:5555`）上的合成/隔离 SSH 测试和已授权的真实秘密库操作；不使用真实外部 SSH 账号，不把账号、密码、token、私钥写入代码、任务或日志；不 push。
- 历史状态（G3.6）：Java owner seam 已提交为 `297c328`（父 checkpoint `df73a64`）；当时 normal APK 已包含 production native OpenSSH closure/C caller 与 Java JNI symbols，但 JNI native binding 仍 fail-closed。
- 已完成：managed `known_hosts` AppSecrets/CAS provider、一次性 native→managed candidate handoff、同步 JNI callback/role bridge、hostfile issuer、production PIC OpenSSH source/object closure、`ssh_native_client` C boundary、pinned caller fixture、`SshNativeSession` Java owner/claim/retry seam、host/Gradle/Release/normal-APK exclusion 质量门。
- 历史下一步（G3.6）：冻结并实现安全的 native-owned handle/view snapshot/borrow 生命周期，再替换 fail-closed JNI binding；该步骤现已完成，剩余工作见当前接管状态。
- 阻塞与停止条件：不得把 AArch64 cross-link、fixture `ssh_api` 内存流、host/fixture runner 或历史 Docker smoke 当作 Android runtime/production SSH pass。没有 runtime 生命周期、清理、授权范围或外部账号证据时停止，不接真实外部 SSH 账号。
- 工作状态：workspace `/home/wkyuu/cargo/repo/06-dedsec/projects/ctos`；branch `antitrust`；G3.7 代码、测试、设计和 spec 已提交 checkpoint `8c0a615`；G3.8 follow-up 当前有未提交代码/测试/文档变更，task commit 指针仍为 G3.7；journal sessions 27-33 已记录。
- 提交限制：本环境 `.git` 为只读挂载，`git add` 无法创建 `../.git/index.lock`；本轮没有 commit/push，工作区改动已通过 `git diff --check`，可在可写 git 环境直接提交。
- 环境：Android module 在 `android/`；hako/Docker 调用可能需要提升权限；容器内没有 `qemu-aarch64`，因此 AArch64 fixture 只能 link，不能在本机 runtime 执行。
- 最近更新：2026-10-16
- 最后验证：G3.8 focused 与 normal full gate 均 `BUILD SUCCESSFUL`、native
  `gate=pass runtime=not-run`；本轮 APK SHA256 为
  `7ccac2fb8f705edd761d34574b9cead4b9e2296864dfe5568d2e9b1105fd849f`；另有
  上述 fixture-only Android KNOWN host-proof runtime 通过。

## 目标 / 资产清单

| 项 | 值 / 描述 | 状态 | 证据来源 |
| --- | --- | --- | --- |
| 当前 work commit | `8c0a615`（G3.7 native-owned SSH session handle/JNI checkpoint；父 `297c328`） | 确认 | `git show --stat 8c0a615` |
| managed provider | `SshManagedKnownHostsProvider` + `AppSecrets` guarded CAS | 确认 | `android/app/src/main/java/im/majo/ctos/SshManagedKnownHostsProvider.java`; `design/verification.md` |
| candidate handoff | exact role/hop/target/connection/host/port/key/digest/body，一次性、清零 | 确认 | `SshKnownHostsCandidateHandoff.java`; `ssh_callback_adapter.[ch]`; `design/ssh-terminal.md` |
| hostfile issuer | pinned upstream parser + project-owned stream writer，production source and fixture evidence | 确认 | `ssh_hostfile_issuer.[ch]`; production/G3 runners |
| pinned caller fixture | real `ssh_api` socketpair packet loop，partial I/O/EAGAIN/EOF/cancel/deadline | 确认 | `ssh_pinned_client.[ch]`; `research/android-hostfile-issuer-build.sh` |
| normal APK | production `libctos_ssh_files.so` with PIC OpenSSH closure；pinned fixture caller excluded | 确认 | `design/verification.md`; `android/app/build.gradle` |
| Java/native session owner | `SshNativeSession` owner-thread/claim seam；真实 JNI registry、native key/view borrow 已接入；synthetic open/revoke/close 与 KNOWN host-proof runtime 已通过 | 部分确认 | `SshNativeSession.java`; `ssh_session_jni.c`; `ssh_view_handle.[ch]`; `design/verification.md` |
| production caller | native C `ssh_native_client` owns parsed host key and view borrow after open; bounded KNOWN host-evidence/fingerprint regression and fixture ARM KNOWN proof pass；qemu native KEX runtime not-run | 部分确认 | `ssh_native_client.[ch]`; `design/verification.md` |
| device | `192.168.9.14:5555`，仅当前授权合成范围 | 确认 | `AGENTS.md`; task `meta.implementation_approval` |

## 关键事实与发现

- **当前阶段不是 production SSH 完成** — 状态：确认
  - 证据：`design/ssh-terminal.md` 顶部的 G3.7 边界；`.trellis/tasks/10-08-ssh-terminal-client/task.json` 的 `implementation_complete=false`。
  - 最后验证：2026-10-15。
- **G3.7 native-owned JNI handle 已通过 host/fixture/device synthetic gate** — 状态：确认
  - 证据：`ssh_view_handle.[ch]`、`ssh_native_client.[ch]`、`ssh_session_jni.c` 与两个平台测试；normal JUnit=189/0/0/0，view/native/callback host gate 通过，`.14` 两个独立 instrumentation 方法各 1 项 status=0。
  - 最后验证：2026-10-15。
  - 备注：设备方法只证明 synthetic open/revoke/close 和 public-file view matrix；不证明完整 protocol/auth。
- **G3.5 production source/object/native gate 已通过，但 ARM runtime 未执行** — 状态：确认
  - 证据：`build/ssh-native-g3/run.aqN1F2bD/`，结果为 `gate=pass runtime=not-run normal_apk=fixture-caller-not-wired`；production archive 为 83 个 PIC members，本机缺少 `qemu-aarch64`，fixture caller 仍排除 normal APK。
  - 最后验证：2026-10-15。
- **正常 Release 质量门通过** — 状态：确认
  - 证据：`:app:testSshViewHandleHost :app:testSshCallbackAdapterHost :app:testSshNativeClientHost :app:testReleaseUnitTest :app:compileSshFiles :app:lintRelease :app:assembleRelease`；189 tests、0 skipped、0 failures、0 errors；本次重跑 APK SHA256 `7f0bd0f14ec2a5ae9175ee340512acfdeaf0aa76f843834d1a3c87107c97993b`。
  - 最后验证：2026-10-15。
- **生产拓扑选择为同进程** — 状态：确认
  - 证据：task `prd.md` / `design.md` 的用户决策；不得将 fork/exec child 或 fixture control channel 升级为生产 broker。
  - 最后验证：2026-10-15。
- **生产 OpenSSH source/object closure 已通过 link gate** — 状态：确认
  - 证据：`android-production-openssh-build.sh`、`build/app/generated/sshProduction/arm64-v8a/`；83-member PIC archive、PKCS#11/SK closure、stock high-level symbol exclusion、normal shared-library link 均通过。
  - 备注：这是 source/object/link 证据，不是 Android runtime 或完整 SSH 产品验收；G3.8 新增
    step 案例包含 banner/KEX output accounting，但因无 qemu 仍未执行。

## 假设台账

- **OpenSSH 将以正常 APK 内的受控 native engine 形式接入** — 状态：待验证
  - 证实判据：normal `compileSshFiles` 能在不引入 CLI/argv/pathname/fork/exec/fixture transport 的前提下链接批准的 OpenSSH client/KEX closure，并通过 ABI、符号、生命周期和 APK 检查。
  - 证伪判据：只能通过子进程/不受管 pathname/无法提供 callback 的方式运行，或无法形成可维护的 Android source/object closure。
  - 当前倾向：沿用用户已确认的同进程 native event loop，但需要先完成 source/object 复核。

## 决策与权衡

- **同进程 native event loop**：选择同进程；否决 fork/exec child handoff。原因是 Vault、host-key CAS、typed credential 和生命周期必须由 ctOS callback 精确绑定。
- **fixture 与 production 分离**：保留 `ssh_pinned_client` 在 opt-in runner；在 production caller 未闭合前，不把它加入 normal APK。
- **提交策略**：本轮提交代码、测试、研究、设计/spec 和任务记录为单一 checkpoint；不归档未完成 task，不 push。

## 当前焦点（G3.8 follow-up）

- **Android runtime/JNI session integration** — 状态：窄增量已通过
  - 已有：normal APK 的 PIC OpenSSH archive、native C caller、native-owned key/session/view contract、真实 JNI symbols 和 `.14` synthetic lifecycle pass。
  - 卡点：hako 无 qemu；设备尚未覆盖 packet protocol/host proof/EOF/deadline/auth，不能外推为完整 SSH runtime。
- **Bounded native step semantics** — 状态：回归已编码，cross-link 与 normal gate 已复验
  - 已有：EAGAIN zero-read/append、EOF terminal `EPIPE`/后续 `EALREADY`、managed-view deadline `ETIMEDOUT`，以及 fragmented banner 后的 bounded client banner/KEX output accounting。
  - 卡点：host binary 未运行；仍未覆盖完整 ARM packet/KEX runtime、host proof 和 Android JNI step runtime。
- **Synthetic packet/KEX host-proof boundary** — 状态：host cross-link 已通过
  - 已有：synthetic nonblocking server pump、真实 client `kex->done`、KNOWN evidence exactly once
    before KEX completion exactly once、cycle=1、session digest binding、completion 后 `EALREADY`，
    以及 client/server bounded partial I/O/EAGAIN 断言。
  - 卡点：无 qemu，ARM binary 未运行；尚未证明 Android JNI packet runtime、真实 managed CAS、
    userauth、PTY/forward/service/UI 或外部 SSH。
- **NEW/CAS full-KEX host-proof boundary** — 状态：host cross-link 已通过
  - 已有：空 managed body 的 candidate → `CAS_COMMITTED` evidence → KEX completion exactly-once
    顺序、精确 body/route/digest binding、peer-bound session digest、bounded EAGAIN/短写和
    completion 后 `EALREADY`。
  - 卡点：仍无 ARM/device packet runtime 或真实 managed CAS commit；认证、PTY/forward/service/UI
    和外部 SSH 未验收。
- **Bounded KNOWN host evidence** — 状态：回归已编码，focused cross-link 通过
  - 已有：受管内存 `ssh/known_hosts`、production verifier、完整 `KNOWN` callback result、exact
    key success 和错误 fingerprint `EACCES`/no-second-callback。
  - 卡点：host binary 未运行；本卡片历史上未覆盖的 CHANGED/REVOKED/CERTIFICATE/INVALID
    现已由后续窄切片补齐；完整 KEX host-proof runtime、认证和 Android JNI runtime 仍未覆盖。
- **Managed-key NEW candidate/CAS precommit** — 状态：host/normal gate 已通过，ARM runtime
  待验证
  - 已有：空 managed body 的 exact replacement、candidate metadata/digest、candidate-before-
    evidence 顺序、approved `CAS_COMMITTED`；evidence rejection 后 candidate 单次 abort、
    client `EALREADY` terminalization 和 close retry。
  - 卡点：AArch64 binary 未运行；尚未覆盖完整 host proof/NEWKEYS、真实 managed CAS、认证和
    Android JNI step runtime。
- **Managed-key CHANGED classification** — 状态：回归已编码，focused cross-link 通过
  - 已有：offered/pinned key 有效、managed key 不同；issuer 直接返回 `HOST_CHANGED`，production
    verifier `EACCES`，无 candidate handoff/host evidence。
  - 卡点：host binary 未运行；本卡片历史上未覆盖的 REVOKED/CERTIFICATE/INVALID/NEW CAS
    precommit 现已由后续窄切片补齐；仍未覆盖 ARM host-proof runtime、认证和 Android JNI runtime。
- **Managed-key REVOKED/CERTIFICATE/INVALID classification** — 状态：实现、check 与
  normal full gate 已完成；native runtime 仍为 `not-run`
  - 已有：matching `@revoked` managed entry；issuer 直接返回 `HOST_REVOKED`，production
    verifier `EACCES`，无 candidate handoff/host evidence。
  - 卡点：host binary 未运行；仍未覆盖 ARM host-proof runtime、认证和 Android JNI runtime。
- **完整 SSH client** — 状态：开放
  - 卡点：userauth/typed credential、PTY、forward、foreground service/UI、设备和外部 SSH 均未验收。

## 下一步行动（G3.8 之后）

- **完成 G3.8 full quality gate** — 状态：已完成
  - 运行 normal `testSshViewHandleHost`、`testSshNativeClientHost`、callback host、JUnit、
    strict `compileSshFiles`、lint、三 ABI Release、assemble、APK/ELF scan 和 `git diff --check`。
  - 结果：JUnit 189/0/0/0、archive=83、新 APK `7ccac2fb8f705edd761d34574b9cead4b9e2296864dfe5568d2e9b1105fd849f`；不把 focused AArch64 cross-link 当 ARM runtime。
- **继续协议/runtime coverage** — 状态：进行中
  - bounded banner/output、KNOWN/CHANGED/REVOKED/CERTIFICATE/INVALID、NEW candidate/CAS、KNOWN
    KEX 和 NEW/CAS full-KEX slices 已完成；下一窄切片转向 ARM/device 可执行性或 typed
    userauth，仍不得使用真实外部 SSH 账号或凭据。

- **提交 G3.7 checkpoint** — 状态：已完成
  - 结果：`8c0a615` 已提交本轮 native handle/JNI code、tests、spec/design/task 变更；未 push，task 仍保持 `in_progress`。
- **实现 Java/JNI production session owner** — 状态：G3.6 历史完成，G3.7 native bridge 已完成
  - 前置条件：复用现有 role/view/callback contracts，不新增明文文件、CLI、fork/exec 或外部账号。
  - 结果：Java owner/claim/cleanup seam、native session registry、parsed key ownership、view borrow/revoke 和 synthetic Android lifecycle 已完成；未把该结果误报为完整 SSH。
  - 完成判据：G3.7 host/normal/device synthetic evidence 已满足；protocol/auth/runtime coverage 仍开放。
- **冻结 native-owned JNI handle/view contract** — 状态：已完成当前安全增量
  - 前置条件：明确 `sshkey` ownership、view snapshot/borrow/revoke、socket/FD ownership、callback environment 和 destroy retry 的 native API。
  - 执行位置：`android/app/src/main/cpp/ssh_native_client.[ch]`、`ssh_session_jni.c`、对应 Java binding/test/spec。
  - 结果：JNI 不再把 Java token 当 C pointer；host、normal link 与一套隔离 Android synthetic runtime 已分别记录。
  - 完成判据：host view-handle revoke/stale 测试和 Android open/revoke/close 测试通过；更广泛 ARM protocol runtime 仍未覆盖。
- **Android `.14` runtime** — 状态：G3.7 synthetic lifecycle 已完成，协议 runtime 待后续
  - 前置条件：用户当前授权的合成/隔离范围仍有效；APK、测试包和清理方案固定。
  - 执行位置：目标 `192.168.9.14:5555`，仅使用本机拥有的随机 listener/Docker pair 或等价合成 endpoint。
  - 完成判据：当前只证明该 APK/ABI 的 synthetic lifecycle；后续若覆盖协议必须继续固定测试包、清理和授权，不外推真实账号或完整产品验收。
  - 失败处理 / 停止条件：出现真实账号、非 owned endpoint、Root/剪贴板/系统设置或真实用户库影响时立即停止。

## 接管验证

- 环境检查：`git status --short` → G3.8 文档/代码复验并提交 follow-up 后应为空；当前 G3.7 code/docs 由 `8c0a615` 保存，G3.8 尚未提交。
- 状态检查：`git show --stat --oneline 8c0a615` → G3.7 checkpoint；内容包括 native view handle、production client ownership、real JNI registry、Android synthetic test 与入口文档。
- 最小验证：focused `:app:testSshNativeClientHost` 与 94-task normal full gate 均
  `BUILD SUCCESSFUL`；再运行 `python3 -m json.tool .trellis/tasks/10-08-ssh-terminal-client/task.json`
  与 JSONL parse，保持 `in_progress`/`implementation_complete=false`。当前 normal gate 为
  JUnit 189/0/0/0、archive=83、APK `7f0bd0f14ec2a5ae9175ee340512acfdeaf0aa76f843834d1a3c87107c97993b`；
  `.14` synthetic session/view 各 1 项 status=0 是历史证据。
- 接管判据：G3.7 host/normal gate、G3.8 focused/full gate、JSON metadata 和 synthetic Android lifecycle 证据已读到；下一 agent 从 G3.8 继续协议/runtime coverage，不得把任何 cross-link/lifecycle gate 当完整 SSH pass。
- 接管后更新：完成 native handle、runtime 或改变授权/设备范围后，先更新本档案顶部摘要、关键事实、假设台账和下一步，再继续实现。

## 证据索引

- `design/verification.md`（cwd=ctOS，时间=2026-10-15）→ 支撑 JVM、Gradle、APK exclusion、fixture runner 和未测边界。
- `design/ssh-terminal.md`（时间=2026-10-15）→ 支撑当前阶段、G3.5 production C boundary 与 Android runtime 未验收边界。
- `.trellis/tasks/10-08-ssh-terminal-client/check.md`（时间=2026-10-15）→ 支撑本轮质量门和限制。
- `.trellis/tasks/10-08-ssh-terminal-client/research/android-production-openssh-build.sh`（时间=2026-10-15）→ 支撑 pinned PIC source/object closure、83-member archive 与 symbol gate。
- `.trellis/tasks/10-08-ssh-terminal-client/research/android-hostfile-issuer-build.sh`（时间=2026-10-15）→ 支撑 pinned manifest/patch/object/link/exclusion runner。
- `.trellis/tasks/10-08-ssh-terminal-client/task.json`（时间=2026-10-15）→ 支撑授权范围、当前 implementation phase 和 run ID。
- `android/app/src/main/cpp/ssh_native_client.c` 与 `android/app/src/test/cpp/ssh_native_client_test.c`
  （时间=2026-10-15）→ 支撑 NEW candidate/CAS precommit、single rollback、terminal `EALREADY`
  与 close retry contract。
- `./hako bash -lc 'cd android && ./gradlew :app:testSshNativeClientHost --rerun-tasks --console=plain'`
  （cwd=ctOS，时间=2026-10-15）→ `BUILD SUCCESSFUL`，production/native gate pass，runtime
  `not-run` because qemu-aarch64 unavailable。
- `./hako bash -lc 'cd android && ./gradlew :app:testSshViewHandleHost :app:testSshNativeClientHost :app:testSshCallbackAdapterHost :app:testReleaseUnitTest :app:compileSshFiles :app:lintRelease :app:assembleRelease --rerun-tasks --console=plain'`
  （cwd=ctOS，时间=2026-10-15）→ 94 tasks success、JUnit 189/0/0/0、archive=83、APK
  SHA256 `7f0bd0f14ec2a5ae9175ee340512acfdeaf0aa76f843834d1a3c87107c97993b`。
- `git show --stat 297c328`（cwd=ctOS，时间=2026-10-15）→ 支撑 G3.6 production Java owner/fail-closed JNI checkpoint 内容；父 G3.5 checkpoint 为 `df73a64`。

## 已修正 / 历史结论

- **“还没有 Java/JNI session orchestration”**
  - 修正于：2026-10-15。
  - 推翻依据：G3.6 `SshNativeSession`、`SshNativeSessionTest` 与 normal APK JNI symbol gate 已落地。
  - 现结论（历史 G3.6）：Java ownership seam 已存在；当时 production JNI 五个入口统一抛 `IOException`。G3.7 已替换该 placeholder；native host protocol runtime 仍尚未通过。

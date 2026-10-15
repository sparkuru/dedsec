# SSH 终端客户端接管档案

## 当前接管状态（G3.7，2026-10-15）

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
  `dd9625677e7f874f19ec85f4f0e3f10b31ab8acd4ab71565d145ece4403f582a`；五个 JNI
  symbols 可见，APK 只有生产 `lib/arm64-v8a/libctos_ssh_files.so`。
- `.14` 当前授权仅用于独立 `secretsFixture`：
  `SshNativeSessionPlatformTest#syntheticNativeSessionHandle` 与
  `SshFileViewPlatformTest#syntheticProtectedFileViews` 各 `OK (1 test)`，status code=0；
  fixture UID 10193/10194 已卸载，fixture package/process 无残留，正式 APK 安装后 hash
  为 `3bc762d9e6463ab9612393b9382923f4a29f613ceec3dbe4d9159aae0078f252`。
- task `10-08-ssh-terminal-client` 仍 `in_progress`，`implementation_complete=false`。
  本轮只证明 native handle/borrow 和合成 open→revoke→close；userauth/typed credential、
  真实 host proof、partial I/O/EAGAIN/EOF/deadline、PTY/forward/service/UI、qemu、真实
  vault 和外部 SSH 仍未完成。

> 下列早期条目保留为 G3.6 及更早阶段的历史上下文；遇到与本节冲突的“fail-closed JNI”
> 或 clean-worktree 描述，以本节和 `design/verification.md` 的 G3.7 记录为准。

- 目标与完成判据：在 ctOS 同一个 APK 内完成受管 OpenSSH SSH 客户端；production caller、事件循环、主机信任/凭据、认证、PTY、转发、服务/UI 与 Android 运行时联合验收均通过，且所有未测边界有明确证据。
- 范围与权限：只在 `projects/ctos` 内工作。允许 `.14`（`192.168.9.14:5555`）上的合成/隔离 SSH 测试和已授权的真实秘密库操作；不使用真实外部 SSH 账号，不把账号、密码、token、私钥写入代码、任务或日志；不 push。
- 历史状态（G3.6）：Java owner seam 已提交为 `297c328`（父 checkpoint `df73a64`）；当时 normal APK 已包含 production native OpenSSH closure/C caller 与 Java JNI symbols，但 JNI native binding 仍 fail-closed。
- 已完成：managed `known_hosts` AppSecrets/CAS provider、一次性 native→managed candidate handoff、同步 JNI callback/role bridge、hostfile issuer、production PIC OpenSSH source/object closure、`ssh_native_client` C boundary、pinned caller fixture、`SshNativeSession` Java owner/claim/retry seam、host/Gradle/Release/normal-APK exclusion 质量门。
- 历史下一步（G3.6）：冻结并实现安全的 native-owned handle/view snapshot/borrow 生命周期，再替换 fail-closed JNI binding；该步骤现已完成，剩余工作见当前接管状态。
- 阻塞与停止条件：不得把 AArch64 cross-link、fixture `ssh_api` 内存流、host/fixture runner 或历史 Docker smoke 当作 Android runtime/production SSH pass。没有 runtime 生命周期、清理、授权范围或外部账号证据时停止，不接真实外部 SSH 账号。
- 工作状态：workspace `/home/wkyuu/cargo/repo/06-dedsec/projects/ctos`；branch `antitrust`；最近已提交 checkpoint `297c328`；G3.7 代码、测试、设计和 task metadata 尚未提交。
- 环境：Android module 在 `android/`；hako/Docker 调用可能需要提升权限；容器内没有 `qemu-aarch64`，因此 AArch64 fixture 只能 link，不能在本机 runtime 执行。
- 最近更新：2026-10-15
- 最后验证：以本档案顶部 G3.7 记录为准；G3.6 APK hash `21d16325…` 仅为历史产物。

## 目标 / 资产清单

| 项 | 值 / 描述 | 状态 | 证据来源 |
| --- | --- | --- | --- |
| 当前 work commit | `297c328`（G3.6 Java/JNI owner seam checkpoint；父 `df73a64`） | 确认 | `git show --stat 297c328` |
| managed provider | `SshManagedKnownHostsProvider` + `AppSecrets` guarded CAS | 确认 | `android/app/src/main/java/im/majo/ctos/SshManagedKnownHostsProvider.java`; `design/verification.md` |
| candidate handoff | exact role/hop/target/connection/host/port/key/digest/body，一次性、清零 | 确认 | `SshKnownHostsCandidateHandoff.java`; `ssh_callback_adapter.[ch]`; `design/ssh-terminal.md` |
| hostfile issuer | pinned upstream parser + project-owned stream writer，production source and fixture evidence | 确认 | `ssh_hostfile_issuer.[ch]`; production/G3 runners |
| pinned caller fixture | real `ssh_api` socketpair packet loop，partial I/O/EAGAIN/EOF/cancel/deadline | 确认 | `ssh_pinned_client.[ch]`; `research/android-hostfile-issuer-build.sh` |
| normal APK | production `libctos_ssh_files.so` with PIC OpenSSH closure；pinned fixture caller excluded | 确认 | `design/verification.md`; `android/app/build.gradle` |
| Java/native session owner | `SshNativeSession` owner-thread/claim seam；真实 JNI registry、native key/view borrow 已接入；仅 synthetic open/revoke/close runtime | 部分确认 | `SshNativeSession.java`; `ssh_session_jni.c`; `ssh_view_handle.[ch]`; `design/verification.md` |
| production caller | native C `ssh_native_client` owns parsed host key and view borrow after open; qemu native runtime not-run; device method is synthetic lifecycle only | 部分确认 | `ssh_native_client.[ch]`; `design/verification.md` |
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
  - 证据：`:app:testSshViewHandleHost :app:testSshCallbackAdapterHost :app:testSshNativeClientHost :app:testReleaseUnitTest :app:compileSshFiles :app:lintRelease :app:assembleRelease`；189 tests、0 skipped、0 failures、0 errors；APK SHA256 `dd9625677e7f874f19ec85f4f0e3f10b31ab8acd4ab71565d145ece4403f582a`。
  - 最后验证：2026-10-15。
- **生产拓扑选择为同进程** — 状态：确认
  - 证据：task `prd.md` / `design.md` 的用户决策；不得将 fork/exec child 或 fixture control channel 升级为生产 broker。
  - 最后验证：2026-10-15。
- **生产 OpenSSH source/object closure 已通过 link gate** — 状态：确认
  - 证据：`android-production-openssh-build.sh`、`build/app/generated/sshProduction/arm64-v8a/`；83-member PIC archive、PKCS#11/SK closure、stock high-level symbol exclusion、normal shared-library link 均通过。
  - 备注：这是 source/object/link 证据，不是 Android runtime 或完整 SSH 产品验收。

## 假设台账

- **OpenSSH 将以正常 APK 内的受控 native engine 形式接入** — 状态：待验证
  - 证实判据：normal `compileSshFiles` 能在不引入 CLI/argv/pathname/fork/exec/fixture transport 的前提下链接批准的 OpenSSH client/KEX closure，并通过 ABI、符号、生命周期和 APK 检查。
  - 证伪判据：只能通过子进程/不受管 pathname/无法提供 callback 的方式运行，或无法形成可维护的 Android source/object closure。
  - 当前倾向：沿用用户已确认的同进程 native event loop，但需要先完成 source/object 复核。

## 决策与权衡

- **同进程 native event loop**：选择同进程；否决 fork/exec child handoff。原因是 Vault、host-key CAS、typed credential 和生命周期必须由 ctOS callback 精确绑定。
- **fixture 与 production 分离**：保留 `ssh_pinned_client` 在 opt-in runner；在 production caller 未闭合前，不把它加入 normal APK。
- **提交策略**：本轮提交代码、测试、研究、设计/spec 和任务记录为单一 checkpoint；不归档未完成 task，不 push。

## 当前焦点（G3.7）

- **Android runtime/JNI session integration** — 状态：窄增量已通过
  - 已有：normal APK 的 PIC OpenSSH archive、native C caller、native-owned key/session/view contract、真实 JNI symbols 和 `.14` synthetic lifecycle pass。
  - 卡点：hako 无 qemu；设备尚未覆盖 packet protocol/host proof/EOF/deadline/auth，不能外推为完整 SSH runtime。
- **完整 SSH client** — 状态：开放
  - 卡点：userauth/typed credential、PTY、forward、foreground service/UI、设备和外部 SSH 均未验收。

## 下一步行动（G3.7 之后）

- **提交 G3.6 checkpoint** — 状态：已完成
  - 结果：`297c328` 已提交本轮 intended code/test/spec/design/task 变更；未 push，task 仍保持 `in_progress`。
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

- 环境检查：`git status --short` → metadata follow-up 提交完成后应为空；G3.6 code/docs 已由 `297c328` 保存。
- 状态检查：`git show --stat --oneline 297c328` → G3.6 checkpoint；内容包括 `SshNativeSession.java`、`SshNativeSessionTest.java`、`ssh_session_jni.c`、callback/lease/role/build 与入口文档。
- 最小验证：`jq -e . .trellis/tasks/10-08-ssh-terminal-client/task.json` → JSON 有效；metadata 保持 `in_progress` 与 `implementation_complete=false`；full G3.6 gate 为 189/0/0/0。
- 接管判据：Java seam 的 ownership 证据与 fail-closed JNI 边界已读到；只有 native-owned handle/view contract 落地且验证通过，才可开始 Android runtime integration。
- 接管后更新：完成 native handle、runtime 或改变授权/设备范围后，先更新本档案顶部摘要、关键事实、假设台账和下一步，再继续实现。

## 证据索引

- `design/verification.md`（cwd=ctOS，时间=2026-10-15）→ 支撑 JVM、Gradle、APK exclusion、fixture runner 和未测边界。
- `design/ssh-terminal.md`（时间=2026-10-15）→ 支撑当前阶段、G3.5 production C boundary 与 Android runtime 未验收边界。
- `.trellis/tasks/10-08-ssh-terminal-client/check.md`（时间=2026-10-15）→ 支撑本轮质量门和限制。
- `.trellis/tasks/10-08-ssh-terminal-client/research/android-production-openssh-build.sh`（时间=2026-10-15）→ 支撑 pinned PIC source/object closure、83-member archive 与 symbol gate。
- `.trellis/tasks/10-08-ssh-terminal-client/research/android-hostfile-issuer-build.sh`（时间=2026-10-15）→ 支撑 pinned manifest/patch/object/link/exclusion runner。
- `.trellis/tasks/10-08-ssh-terminal-client/task.json`（时间=2026-10-15）→ 支撑授权范围、当前 implementation phase 和 run ID。
- `git show --stat 297c328`（cwd=ctOS，时间=2026-10-15）→ 支撑 G3.6 production Java owner/fail-closed JNI checkpoint 内容；父 G3.5 checkpoint 为 `df73a64`。

## 已修正 / 历史结论

- **“还没有 Java/JNI session orchestration”**
  - 修正于：2026-10-15。
  - 推翻依据：G3.6 `SshNativeSession`、`SshNativeSessionTest` 与 normal APK JNI symbol gate 已落地。
  - 现结论（历史 G3.6）：Java ownership seam 已存在；当时 production JNI 五个入口统一抛 `IOException`。G3.7 已替换该 placeholder；native host protocol runtime 仍尚未通过。

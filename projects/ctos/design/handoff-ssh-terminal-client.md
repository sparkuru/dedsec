# SSH 终端客户端接管档案

- 目标与完成判据：在 ctOS 同一个 APK 内完成受管 OpenSSH SSH 客户端；production caller、事件循环、主机信任/凭据、认证、PTY、转发、服务/UI 与 Android 运行时联合验收均通过，且所有未测边界有明确证据。
- 范围与权限：只在 `projects/ctos` 内工作。允许 `.14`（`192.168.9.14:5555`）上的合成/隔离 SSH 测试和已授权的真实秘密库操作；不使用真实外部 SSH 账号，不把账号、密码、token、私钥写入代码、任务或日志；不 push。
- 当前状态：基线 checkpoint 为 `6dc6b34`；本轮 G3.5 代码、测试、runner、spec/design 变更已完成并待 checkpoint 提交。task `10-08-ssh-terminal-client` 仍为 `in_progress`，`implementation_complete=false`；normal APK 已包含 production native OpenSSH closure 和 C caller，但没有 Java/JNI session orchestration。
- 已完成：managed `known_hosts` AppSecrets/CAS provider、一次性 native→managed candidate handoff、同步 JNI callback/role bridge、hostfile issuer、production PIC OpenSSH source/object closure、`ssh_native_client` C boundary、pinned caller fixture、host/Gradle/Release/normal-APK exclusion 质量门。
- 未完成：Android runtime/JNI/Java 会话编排；userauth/typed credential、PTY、forward、foreground service、UI；设备/ARM runtime 和外部 SSH 验收。
- 下一步：先提交并复核 G3.5 checkpoint，然后在当前 production C boundary 之上接入受审计的 Java/JNI event-loop owner；完成后才安排 `.14` 合成 runtime 验证。
- 阻塞与停止条件：不得把 AArch64 cross-link、fixture `ssh_api` 内存流、host/fixture runner 或历史 Docker smoke 当作 Android runtime/production SSH pass。没有 runtime 生命周期、清理、授权范围或外部账号证据时停止，不接真实外部 SSH 账号。
- 工作状态：workspace `/home/wkyuu/cargo/repo/06-dedsec/projects/ctos`；branch `antitrust`；基线提交 `6dc6b34`；本轮变更尚未提交。
- 环境：Android module 在 `android/`；hako/Docker 调用可能需要提升权限；容器内没有 `qemu-aarch64`，因此 AArch64 fixture 只能 link，不能在本机 runtime 执行。
- 最近更新：2026-10-15
- 最后验证：固定 runner `build/ssh-native-g3/run.aqN1F2bD/` 返回 `SSH_HOSTFILE_CALLER_BUILD gate=pass runtime=not-run normal_apk=fixture-caller-not-wired`；production native `gate=pass runtime=not-run`；Gradle host gates、177 JVM、strict compile/lint/Release assemble 通过；APK SHA256 `805fc28679aa56be85fa3be75a1608701273908a02f7fb04dddb717c4e1b601e`。

## 目标 / 资产清单

| 项 | 值 / 描述 | 状态 | 证据来源 |
| --- | --- | --- | --- |
| 当前 work commit | `6dc6b34`（本轮 checkpoint 待提交） | 确认 | `git show --stat 6dc6b34` |
| managed provider | `SshManagedKnownHostsProvider` + `AppSecrets` guarded CAS | 确认 | `android/app/src/main/java/im/majo/ctos/SshManagedKnownHostsProvider.java`; `design/verification.md` |
| candidate handoff | exact role/hop/target/connection/host/port/key/digest/body，一次性、清零 | 确认 | `SshKnownHostsCandidateHandoff.java`; `ssh_callback_adapter.[ch]`; `design/ssh-terminal.md` |
| hostfile issuer | pinned upstream parser + project-owned stream writer，production source and fixture evidence | 确认 | `ssh_hostfile_issuer.[ch]`; production/G3 runners |
| pinned caller fixture | real `ssh_api` socketpair packet loop，partial I/O/EAGAIN/EOF/cancel/deadline | 确认 | `ssh_pinned_client.[ch]`; `research/android-hostfile-issuer-build.sh` |
| normal APK | production `libctos_ssh_files.so` with PIC OpenSSH closure；pinned fixture caller excluded | 确认 | `design/verification.md`; `android/app/build.gradle` |
| production caller | native C `ssh_native_client` linked and cross-compiled; Android/JNI runtime not yet verified | 部分确认 | `.trellis/spec/backend/ssh-native.md`; `design/verification.md` |
| device | `192.168.9.14:5555`，仅当前授权合成范围 | 确认 | `AGENTS.md`; task `meta.implementation_approval` |

## 关键事实与发现

- **当前阶段不是 production SSH 完成** — 状态：确认
  - 证据：`design/ssh-terminal.md` 顶部；`.trellis/tasks/10-08-ssh-terminal-client/task.json` 的 `implementation_complete=false`。
  - 最后验证：2026-10-15。
- **G3.5 production source/object/native gate 已通过，但 ARM runtime 未执行** — 状态：确认
  - 证据：`build/ssh-native-g3/run.aqN1F2bD/`，结果为 `gate=pass runtime=not-run normal_apk=fixture-caller-not-wired`；production archive 为 83 个 PIC members，本机缺少 `qemu-aarch64`，fixture caller 仍排除 normal APK。
  - 最后验证：2026-10-15。
- **正常 Release 质量门通过** — 状态：确认
  - 证据：`:app:testSshCallbackAdapterHost :app:testSshNativeClientHost :app:testReleaseUnitTest :app:compileSshFiles :app:lintRelease :app:assembleRelease`；177 tests、0 skipped、0 failures、0 errors；APK SHA256 `805fc28679aa56be85fa3be75a1608701273908a02f7fb04dddb717c4e1b601e`。
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

## 当前焦点

- **Android runtime/JNI session integration** — 状态：下一阶段
  - 已有：normal APK 的 PIC OpenSSH archive、hostfile issuer、native C caller 和 close/ownership contract。
  - 卡点：还没有 Java/JNI API 将 role/view/callback/socket owner 驱动到该 C boundary，也没有 ARM runtime evidence。
- **完整 SSH client** — 状态：开放
  - 卡点：userauth/typed credential、PTY、forward、foreground service/UI、设备和外部 SSH 均未验收。

## 下一步行动

- **提交 G3.5 checkpoint** — 状态：当前轮次
  - 前置条件：固定 runner、native host、G3 exclusion、full Gradle gate 和文档检查均为 pass。
  - 完成判据：只提交本轮 intended code/test/runner/spec/design/task 变更，不 push；task 仍保持 `in_progress`。
- **实现 Java/JNI production session owner** — 状态：待开始
  - 前置条件：复用现有 role/view/callback contracts，不新增明文文件、CLI、fork/exec 或外部账号。
  - 完成判据：同一 event-loop 线程同步调用 native open/step/verify/close，证明 callback/FD/managed-view 生命周期和取消/期限。
- **Android `.14` runtime** — 状态：待 Java/JNI production caller 完成后执行
  - 前置条件：用户当前授权的合成/隔离范围仍有效；APK、测试包和清理方案固定。
  - 执行位置：目标 `192.168.9.14:5555`，仅使用本机拥有的随机 listener/Docker pair 或等价合成 endpoint。
  - 完成判据：只证明该 APK/ABI/runtime 范围，不外推真实账号或完整产品验收。
  - 失败处理 / 停止条件：出现真实账号、非 owned endpoint、Root/剪贴板/系统设置或真实用户库影响时立即停止。

## 接管验证

- 环境检查：`git status --short` → 先确认 dirty path 都属于本轮 G3.5 intended 变更。
- 状态检查：`git show --stat --oneline 6dc6b34` → 基线 checkpoint；本轮新 checkpoint 需在提交后更新本档案。
- 最小验证：`jq -e . .trellis/tasks/10-08-ssh-terminal-client/task.json` → JSON 有效；metadata 应保持 `in_progress` 与 `implementation_complete=false`。
- 接管判据：当前 branch、task 状态、dirty path 和本档案一致，才可开始 Java/JNI runtime integration。
- 接管后更新：完成 runtime 或改变授权/设备范围后，先更新本档案顶部摘要、关键事实、假设台账和下一步，再继续实现。

## 证据索引

- `design/verification.md`（cwd=ctOS，时间=2026-10-15）→ 支撑 JVM、Gradle、APK exclusion、fixture runner 和未测边界。
- `design/ssh-terminal.md`（时间=2026-10-15）→ 支撑当前阶段、G3.5 production C boundary 与 Android runtime 未验收边界。
- `.trellis/tasks/10-08-ssh-terminal-client/check.md`（时间=2026-10-15）→ 支撑本轮质量门和限制。
- `.trellis/tasks/10-08-ssh-terminal-client/research/android-production-openssh-build.sh`（时间=2026-10-15）→ 支撑 pinned PIC source/object closure、83-member archive 与 symbol gate。
- `.trellis/tasks/10-08-ssh-terminal-client/research/android-hostfile-issuer-build.sh`（时间=2026-10-15）→ 支撑 pinned manifest/patch/object/link/exclusion runner。
- `.trellis/tasks/10-08-ssh-terminal-client/task.json`（时间=2026-10-15）→ 支撑授权范围、当前 implementation phase 和 run ID。
- `git show --stat 6dc6b34`（cwd=ctOS，时间=2026-10-15）→ 支撑本轮提交内容。

# SSH 终端客户端接管档案

- 目标与完成判据：在 ctOS 同一个 APK 内完成受管 OpenSSH SSH 客户端；production caller、事件循环、主机信任/凭据、认证、PTY、转发、服务/UI 与 Android 运行时联合验收均通过，且所有未测边界有明确证据。
- 范围与权限：只在 `projects/ctos` 内工作。允许 `.14`（`192.168.9.14:5555`）上的合成/隔离 SSH 测试和已授权的真实秘密库操作；不使用真实外部 SSH 账号，不把账号、密码、token、私钥写入代码、任务或日志；不 push。
- 当前状态：本轮阶段成果已提交为 `6dc6b34`（`feat(ctos): checkpoint SSH provider and OpenSSH fixture work`）。task `10-08-ssh-terminal-client` 仍为 `in_progress`，`implementation_complete=false`；正常 APK 尚未包含 production OpenSSH caller。
- 已完成：managed `known_hosts` AppSecrets/CAS provider、一次性 native→managed candidate handoff、同步 JNI callback/role bridge、pinned OpenSSH 10.6p1 hostfile issuer 与 caller fixture、host/Gradle/Release/normal-APK exclusion 质量门。
- 未完成：production caller 与真实 OpenSSH event loop；正常 APK 内的 OpenSSH source/object closure；userauth/typed credential 实接、PTY、forward、foreground service、UI、Android runtime/设备验收；ARM fixture runtime、外部 SSH 验收。
- 下一步：先完成最小接管验证，然后从 `ssh_native_client` 的 production integration gate 开始，保持同进程 native event loop 和 event-loop 线程同步附着 JNI callback；通过 normal APK 编译与排除检查后，再安排 `.14` 合成 runtime 验证。
- 阻塞与停止条件：当前 `ssh_native_client` 在缺少 production issuer/caller 时必须返回 `ENOTSUP`；不得把 fixture 的 `ssh_api` 内存流、host/fixture runner 或历史 Docker smoke 当作 production pass。没有可审计的 OpenSSH source/object manifest、生命周期/清理证据或授权范围扩大时停止，不接外部账号。
- 工作状态：workspace `/home/wkyuu/cargo/repo/06-dedsec/projects/ctos`；branch `antitrust`；当前提交 `6dc6b34`；提交后工作树应为空。
- 环境：Android module 在 `android/`；hako/Docker 调用可能需要提升权限；容器内没有 `qemu-aarch64`，因此 AArch64 fixture 只能 link，不能在本机 runtime 执行。
- 最近更新：2026-10-15
- 最后验证：固定 runner `build/ssh-native-g3/run.3PZSbIzM/` 返回 `SSH_HOSTFILE_CALLER_BUILD gate=pass runtime=not-run normal_apk=not-wired`；Gradle host gates、177 JVM、strict compile/lint/Release assemble 通过。

## 目标 / 资产清单

| 项 | 值 / 描述 | 状态 | 证据来源 |
| --- | --- | --- | --- |
| 当前 work commit | `6dc6b34` | 确认 | `git show --stat 6dc6b34` |
| managed provider | `SshManagedKnownHostsProvider` + `AppSecrets` guarded CAS | 确认 | `android/app/src/main/java/im/majo/ctos/SshManagedKnownHostsProvider.java`; `design/verification.md` |
| candidate handoff | exact role/hop/target/connection/host/port/key/digest/body，一次性、清零 | 确认 | `SshKnownHostsCandidateHandoff.java`; `ssh_callback_adapter.[ch]`; `design/ssh-terminal.md` |
| hostfile issuer | pinned upstream parser + project-owned stream writer，fixture/host-only | 确认 | `ssh_hostfile_issuer.[ch]`; `research/android-hostfile-issuer-build.sh` |
| pinned caller fixture | real `ssh_api` socketpair packet loop，partial I/O/EAGAIN/EOF/cancel/deadline | 确认 | `ssh_pinned_client.[ch]`; `research/android-hostfile-issuer-build.sh` |
| normal APK | `libctos_ssh_files.so` only；fixture OpenSSH/caller object excluded | 确认 | `design/verification.md`; `android/app/build.gradle` |
| production caller | `ssh_native_client` still fail-closed with `ENOTSUP` | 确认 | `.trellis/spec/backend/ssh-native.md`; `design/ssh-terminal.md` |
| device | `192.168.9.14:5555`，仅当前授权合成范围 | 确认 | `AGENTS.md`; task `meta.implementation_approval` |

## 关键事实与发现

- **当前阶段不是 production SSH 完成** — 状态：确认
  - 证据：`design/ssh-terminal.md` 顶部；`.trellis/tasks/10-08-ssh-terminal-client/task.json` 的 `implementation_complete=false`。
  - 最后验证：2026-10-15。
- **G3.4 fixture runner 已通过 source/object gate，但 ARM runtime 未执行** — 状态：确认
  - 证据：`build/ssh-native-g3/run.3PZSbIzM/`，结果为 `gate=pass runtime=not-run normal_apk=not-wired`；本机缺少 `qemu-aarch64`。
  - 最后验证：2026-10-15。
- **正常 Release 质量门通过** — 状态：确认
  - 证据：`:app:testSshCallbackAdapterHost :app:testSshNativeClientHost :app:testReleaseUnitTest :app:compileSshFiles :app:lintRelease :app:assembleRelease`；177 tests、0 skipped、0 failures、0 errors；APK SHA256 `67668e34e2bba40359e43e048404ffeb5645629a130f86e86b2d855a170ffff9`。
  - 最后验证：2026-10-15。
- **生产拓扑选择为同进程** — 状态：确认
  - 证据：task `prd.md` / `design.md` 的用户决策；不得将 fork/exec child 或 fixture control channel 升级为生产 broker。
  - 最后验证：2026-10-15。
- **生产 OpenSSH source/object closure 仍需单独设计和审计** — 状态：待验证
  - 证据：`research/production-kex-caller-blocker-20261015.md` 与 `research/openssh-10.6p1-production-kex-source-map-20261015.md`。
  - 备注：当前 fixture source map 不等于正常 APK 的 production link closure。

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

- **Production OpenSSH caller** — 状态：未开始/开放
  - 卡点：需要完成 source/object closure、native caller API、真实 socket/event-loop 生命周期和 managed issuer/callback 接线。
- **Android runtime** — 状态：待验证
  - 卡点：必须先有可安装的 production caller APK；当前仅有 fixture cross-link 和 host evidence。

## 下一步行动

- **接管环境** — 状态：未开始
  - 前置条件：进入 `/home/wkyuu/cargo/repo/06-dedsec/projects/ctos`。
  - 执行位置：workspace。
  - 命令或文件：`git status --short && git show --stat --oneline 6dc6b34`。
  - 预期结果：工作树干净，当前提交为 `6dc6b34`。
  - 完成判据：确认没有未提交的用户变更后再编辑。
  - 失败处理 / 停止条件：若出现新 dirty path，先分类，不重置、不 checkout、不覆盖。
- **重读生产边界** — 状态：未开始
  - 前置条件：接管环境通过。
  - 执行位置：workspace。
  - 命令或文件：`.trellis/tasks/10-08-ssh-terminal-client/{prd.md,design.md,implement.md,check.md}`、`.trellis/spec/backend/ssh-native.md`、`design/ssh-terminal.md`。
  - 预期结果：确认 G3.4 是 fixture-only，`ssh_native_client` 仍 `ENOTSUP`。
  - 完成判据：实现计划与当前 task metadata 一致。
  - 失败处理 / 停止条件：发现状态漂移时先更新 task/design，不直接接入 normal APK。
- **形成 production source/object closure** — 状态：未开始
  - 前置条件：approved OpenSSH 10.6p1 baseline、Android ABI 约束和 callback/provider contract 已读完。
  - 执行位置：`android/app/build.gradle`、`android/app/src/main/cpp/`、task research runner。
  - 命令或文件：沿用固定 baseline/manifest runner；先做 strict C11/Werror、symbol/no-undefined、normal APK exclusion，再实现 caller。
  - 预期结果：可审计的 production target，仍拒绝 CLI/argv/pathname/fork/exec/fixture transport。
  - 完成判据：native caller 能驱动真实 socket 的 bounded partial I/O，并接入 managed host verifier/candidate handoff；`ssh_native_client` 不再因 provider 缺失而返回 `ENOTSUP`。
  - 失败处理 / 停止条件：source/object closure 不完整、callback 生命周期不确定或会把明文落盘时，保持 fail-closed，回写 blocker 研究记录。
- **Android `.14` runtime** — 状态：待 production caller 完成后执行
  - 前置条件：用户当前授权的合成/隔离范围仍有效；production APK 安装包和清理方案固定。
  - 执行位置：目标 `192.168.9.14:5555`，仅使用本机拥有的随机 listener/Docker pair 或等价合成 endpoint。
  - 预期结果：安装、测试、卸载和 owned resource 清理均有原始证据。
  - 完成判据：只能证明该 APK/ABI/runtime 范围，不外推真实账号或完整产品验收。
  - 失败处理 / 停止条件：出现真实账号、非 owned endpoint、Root/剪贴板/系统设置或真实用户库影响时立即停止。

## 接管验证

- 环境检查：`git status --short` → 当前 checkpoint 后应为空。
- 状态检查：`git show --stat --oneline 6dc6b34` → 应显示 103 个文件的阶段提交。
- 最小验证：`jq -e . .trellis/tasks/10-08-ssh-terminal-client/task.json` → JSON 有效；metadata 应保持 `in_progress` 与 `implementation_complete=false`。
- 接管判据：当前 branch/commit、task 状态和工作树均与本档案一致，才可开始 production caller 设计。
- 接管后更新：完成 source/object closure 或改变授权/设备范围后，先更新本档案顶部摘要、关键事实、假设台账和下一步，再继续实现。

## 证据索引

- `design/verification.md`（cwd=ctOS，时间=2026-10-15）→ 支撑 JVM、Gradle、APK exclusion、fixture runner 和未测边界。
- `design/ssh-terminal.md`（时间=2026-10-15）→ 支撑当前阶段、G3.4 contract 与 production `ENOTSUP` 边界。
- `.trellis/tasks/10-08-ssh-terminal-client/check.md`（时间=2026-10-15）→ 支撑本轮质量门和限制。
- `.trellis/tasks/10-08-ssh-terminal-client/research/android-hostfile-issuer-build.sh`（时间=2026-10-15）→ 支撑 pinned manifest/patch/object/link/exclusion runner。
- `.trellis/tasks/10-08-ssh-terminal-client/task.json`（时间=2026-10-15）→ 支撑授权范围、当前 implementation phase 和 run ID。
- `git show --stat 6dc6b34`（cwd=ctOS，时间=2026-10-15）→ 支撑本轮提交内容。

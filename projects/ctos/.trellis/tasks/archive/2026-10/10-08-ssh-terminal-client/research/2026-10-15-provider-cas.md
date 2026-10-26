# Research: managed known_hosts provider/CAS

- Query: 确定下一窄增量的最小真实 managed known_hosts provider/CAS contract，使现有 role/hop-bound HostEvidence/CAS seam 能从 AppSecrets 的受管 known_hosts 文件对象得到 exact base/replacement digest，并安全调用现有 updateTrust CAS；同时在 pinned OpenSSH `ssh_api` caller 接入前保持 fail-closed。
- Scope: internal / mixed
- Date: 2026-10-15

## Findings

### Bottom line

当前 seam 足以让 Java 侧读取一个受管 `KNOWN_HOSTS` 对象并独立计算 exact base digest，也足以在 proposal 已经由受信 managed provider 构造、且有明确 approval/proof 时调用 `SshVaultAdapter.updateTrust`。它还不能形成一个真实的 replacement digest：

1. `SshNativeCallbackAdapter` 收到的 HostEvidence 只有 replacement digest，没有 replacement bytes 或候选对象（`android/app/src/main/java/im/majo/ctos/SshNativeCallbackAdapter.java:312-405`、`android/app/src/main/cpp/ssh_callback_adapter.h:48-79`）。
2. `SshTrustProposal` 要求完整 `newContent`，并由该内容计算 replacement digest；当前 `TrustProposalSource`/`TrustCasWriter` 没有 candidate、snapshot、host proof 或 approval receipt 参数（`android/app/src/main/java/im/majo/ctos/SshManagedProvider.java:9-16`）。
3. `SshPublicFileLease` 和 native file view 是只读、public-body-only 的受管 view；没有写入、candidate 导出或 trust proposal 提取 API（`android/app/src/main/java/im/majo/ctos/SshPublicFileLease.java:58-126`、`android/app/src/main/cpp/ssh_file_io.c:65-124`）。

因此下一窄增量应先冻结“managed candidate”的内部 contract，并实现 Java/AppSecrets 真 CAS 及测试；它可以使用测试拥有的 exact candidate bytes 验证全链路，但不能宣称已有 OpenSSH caller。若 candidate 最终由 pinned OpenSSH hostfile 逻辑在 native 侧产生，必须另行定义受控的一次性 handoff；在该 handoff 冻结前，production caller 继续保持 `ENOTSUP`/provider-missing。

### Files found

- `android/app/src/main/java/im/majo/ctos/SshManagedProvider.java`: 当前 package-private proposal source、CAS writer 和 typed credential provider seam。
- `android/app/src/main/java/im/majo/ctos/SshVaultAdapter.java`: AppSecrets 文件 snapshot、public file lease、trust request/update 的 adapter；当前 trust gate 只显式接收 proposal。
- `android/app/src/main/java/im/majo/ctos/AppSecrets.java`: `beginTrustUpdate`/`updateTrust` 的 exact object/base-digest CAS、operation epoch、manifest commit 和 session revoke。
- `android/app/src/main/java/im/majo/ctos/VaultOperation.java`: opaque operation 的 vault/generation/epoch/deadline/snapshot binding 以及已有 role binding 辅助。
- `android/app/src/main/java/im/majo/ctos/SshTrustProposal.java`: exact known_hosts proposal、base/new digest、role/hop/target binding 和 CAS result。
- `android/app/src/main/java/im/majo/ctos/SshFileSnapshot.java`: 同步借用的 manifest closure；metadata 包含 type/id/path/identity，public bodies 只在 receiver 生命周期内可见。
- `android/app/src/main/java/im/majo/ctos/SshPublicFileLease.java`: singleton native-owned read-only public view；wire 包含 vault snapshot metadata，但 Java 侧只有 summary/revoke 生命周期 API。
- `android/app/src/main/java/im/majo/ctos/SshHostKeyAuthority.java`: KNOWN/NEW 分类、host proof 和 unknown-key approval；当前 Proof 的 `requireMatches` 未覆盖 key type/classification/digest。
- `android/app/src/main/java/im/majo/ctos/SshNativeCallbackAdapter.java`: role/hop-bound evidence、proposal、host proof、KEX proof 和 provider callback 生命周期。
- `android/app/src/main/cpp/ssh_file_view.c`: exact virtual path/type lookup、locked memfd view、generation/epoch/deadline/binding 校验；没有 mutation API。
- `android/app/src/main/cpp/ssh_file_io.c`: OpenSSH-facing virtual read-only `FILE *`/memfd；拒绝 host fallback，没有 write/candidate API。
- `android/app/src/main/cpp/ssh_native_client.c`: 当前 host-only fail-closed client boundary；provider missing 时 `ENOTSUP`，不创建 OpenSSH client。
- `android/app/build.gradle:157-176,246-266`: normal APK 只编译 ssh file/JNI/role/callback；`ssh_native_client` 是 host-only gate，当前 OpenSSH caller 不在 APK。
- `.trellis/tasks/10-08-ssh-terminal-client/prd.md`: 本任务当前事实、G3.3 blocker 和“provider/caller 尚未实现”的范围。
- `.trellis/tasks/10-08-ssh-terminal-client/design.md`: 当前 HostEvidence/CAS seam 及下一步 concrete provider/caller 方向。
- `.trellis/tasks/10-08-ssh-terminal-client/check.md`: 已验证 seam 的范围，以及没有验证 concrete provider、CAS writer、file view 生产 caller 的 caveat。
- `.trellis/spec/backend/app-secrets.md`: managed file view、trust CAS、epoch/lease 和 fail-closed 约束。
- `.trellis/spec/backend/ssh-native.md`: production SSH 尚未接入、callback lifecycle、HostEvidence/CAS wire 和 `ssh_native_client` fail-closed contract。
- `.trellis/tasks/10-08-ssh-terminal-client/research/openssh-10.6p1-production-kex-source-map-20261015.md`: pinned OpenSSH 10.6p1 的 future incremental packet/KEX 接入顺序；不是现有 caller。
- `.trellis/tasks/10-08-ssh-terminal-client/research/production-kex-caller-blocker-20261015.md`: 现有 production KEX caller 的具体 blocker 清单。

### Existing boundaries and what they can prove

#### Managed file snapshot and base digest

`SshVaultAdapter.fileRequest(rootPath, connectionId)` / `useFileSnapshot` 经过 `AppSecrets.beginSshFiles` 和一次性 `sshFileSnapshot`。`SshFileSnapshot` 带有 `vaultId`、`generation`、`epoch`、`deadline`、`requestId`、`connectionId` 和 `snapshotBinding`；其 `Metadata` 带有 file type/id/path/identity，而 `publicFiles` 才带 public body（`SshVaultAdapter.java:65-80`、`SshFileSnapshot.java:7-57`）。receiver 返回或抛异常后，AppSecrets 会 wipe public body（`AppSecrets.java:699-747`）。

最小 provider 可以在同步 receiver 内：

- 要求 `type == KNOWN_HOSTS`，精确匹配 managed 配置选定的 `path`、`objectId` 和 `identity`；不能按文件名或前缀猜测。
- 对该对象的 exact `content` 调用现有 `VaultCodec.digest`，只复制 32-byte base digest 和必要 metadata；不要把 `FileObject` 或 body 存入长生命周期 provider。
- 用 constant-time 比较该 digest 与 native Evidence 的 `baseDigest`；不一致时返回 typed stale/CAS failure，不构造 proposal。
- 同时保存 snapshot 的 vault/generation/epoch/deadline/request/snapshotBinding receipt，供后续 proposal 和 CAS guard 检查。

这一步是真实可行的 Java/AppSecrets provider contract；但它只是 base side。`SshFileSnapshot` 是一次性借用，不应在 proposal callback 之后复用；另开一次 file operation 也不能替代在同一个受管 snapshot receipt 上做 binding 校验。

#### Exact replacement digest and candidate gap

`SshTrustProposal` 的构造会校验并保存 `newContent`，`newDigest()` 是该完整内容的 digest；`matchesEvidence` 要求 role/hop/target/connection/host/port、classification、key type、fingerprint 以及 base/replacement digest 全部相等（`SshTrustProposal.java:158-279`）。`SshManagedProvider.proposalFor` 目前只传 `Evidence`，而 `Evidence` 只携带 replacement digest，不携带 hostfile candidate 或 host-key bytes（`SshHostKeyAuthority.java:17-64`）。

所以 provider 不能安全地从当前参数“反推” replacement content，也不能用 base content 加一个 Java 自制行格式化器冒充 pinned OpenSSH hostfile 结果。当前 native view 的 `ctos_ssh_io_open` 只能读 exact virtual public file，`SshPublicFileLease` 也没有 candidate/read-back API；proposal body 明确不进入 JNI callback wire（`SshNativeCallbackAdapter.java:83-123`、`.trellis/spec/backend/ssh-native.md:1047-1075`）。

最小 contract 必须显式有一次性、provider-owned 的 `KnownHostsCandidate`，至少绑定：

- exact `path`、`objectId`、`identity` 和 `KNOWN_HOSTS` type；
- role object、role binding/hop、connection/target/host/port；
- vault UUID、generation、session epoch、deadline、request ID、snapshotBinding；
- candidate 的 base digest 和 exact replacement bytes（由该 bytes 计算 replacement digest）；
- close/wipe 状态和不可重放的 candidate/request identity。

candidate body 只能存在于受控 provider/CAS 生命周期内，构造 proposal 后在 CAS 返回或失败路径 wipe。若 candidate 来自 native OpenSSH hostfile writer，当前“proposal body 不过 JNI”的设计没有足够签名；必须在后续 caller 任务中冻结一个专门的 opaque one-shot native/provider handoff。这个问题未解决前，provider 缺少 candidate 必须 fail closed。

#### CAS and approval

现有 `SshVaultAdapter.trustRequest` 会检查 `proposal.requireOpen()`、`HostKeyGate.requireVerified(connectionId,targetIdentity)` 和 exact `TrustProposalGate.requireApproved(proposal)`，然后调用 `AppSecrets.beginTrustUpdate`；`updateTrust` 在最终 effect guard 再次检查 host gate/trust gate，再进入 `AppSecrets.updateTrust`（`SshVaultAdapter.java:115-128`）。`AppSecrets.updateTrust` 会重新验证 operation envelope/epoch/current vault binding，并把当前 manifest 中同一个 object 的 content digest 与 proposal base digest 比较；不相等即 `VAULT_CHANGED`，成功后才 commit replacement（`AppSecrets.java:556-585,781-816`）。

真实 writer 应该复用这条路径，而不是直接访问 Store 或制造 `CasResult.committed`。`COMMITTED` 只能在 `updateTrust` 正常返回之后产生；异常、deadline、取消、stale base、session lock 或 commit uncertain 都不能转成 committed。proposal 必须保持 open 到 writer 返回，因为 adapter/CAS 会读取 proposal body，当前 callback finally 才关闭 proposal（`SshNativeCallbackAdapter.java:312-350`）。

不过当前 `trustRequest/updateTrust` 签名没有显式 role、host Proof、snapshot receipt 或 epoch 参数。它们现在依赖连接/target gate 和 AppSecrets operation 的部分检查，不能单独表达“这一次 HostEvidence 的 exact role/hop/proof/key type/classification 对应这一次 CAS”。建议 internal contract 增加 proof/snapshot 参数，或在 adapter 内先对 operation 调用 `VaultOperation.bindRole` 并在最终 guard 调用 `requireRole`，再额外检查 exact proposal/evidence。不能只依赖 `VaultOperation.bindRole`：它当前主要检查 role/hop/connection/target/fingerprint，Proof 的 `requireMatches` 也没有检查 key type、classification 和 digest（`VaultOperation.java:48-70`、`SshHostKeyAuthority.java:102-130`）。

最小的内部形状可以是以下语义（不是本次实现）：

```text
KnownHostsSnapshot snapshotFor(role, exactPath, connectionId)
KnownHostsCandidate acquireCandidate(evidence, hostProof, snapshot)
SshTrustProposal proposalFor(evidence, hostProof, candidate)
CasResult write(evidence, hostProof, proposal, snapshotReceipt)
```

`proposalFor` 应验证 candidate 的 base/replacement digest 与 evidence、proposal 的 exact fields；`write` 应验证 role active/deadline、hostProof、approval receipt、candidate/snapshot binding，并通过现有 `trustRequest` + `updateTrust` 完成 CAS。若为了兼容现有 `SshManagedProvider` 接口保留旧 overload，也只能把它作为测试/legacy path，production path 不得让 proof/candidate 变成隐式状态。

#### Lifecycle, epoch and view binding

`VaultOperation` 已捕获 operation 创建时的 vault ID、generation、session epoch、deadline 和 snapshot binding，`AppSecrets.verifyOperation`/`protectedEffect` 会在消费和最终 effect 前重验这些值（`VaultOperation.java:5-40`、`AppSecrets.java:781-816`）。session lock 会 wipe key、递增 epoch 并撤销单个 attached lease（`VaultSession.java:68-89`）；成功 commit 的 `unlockIfEpoch` 也会先 lock，使旧 view/operation 失效。

仍有三个边界必须在 provider contract 中补齐：

1. `SshTrustProposal` 自身没有 vault ID、generation、epoch、deadline、request ID 或 snapshotBinding；provider 不能只缓存 proposal，必须把 live `VaultOperation`/snapshot receipt 作为 CAS context。
2. `SshPublicFileLease` 是 singleton native view，wire 有 generation/epoch/deadline/binding，但 Java 侧只暴露 summary/revoke；`SshRoleContext.close()` 只标记 role inactive，不自动关闭 public lease。role close、callback cancel、session lock、CAS 完成和 cleanup failure 都必须走同一个 lease owner 的 revoke/close，cleanup 不确定时禁止新 caller。
3. 当前 role bootstrap 主要保存 `publicViewId`，HostEvidence wire 没有 vault snapshot binding。provider 必须让 `publicViewId` 成为 provider-owned、不可猜的 view identity，并在 role issuance 时把它与 snapshot receipt 关联；否则 native digest 与 Java AppSecrets snapshot 之间只有事后 base CAS 检查，没有强的 view-to-vault proof。若不增加该关联，仍要把它视为 production enablement blocker，而不能用字符串 id 代替 proof。

另一个实际 gate 是 AppSecrets 的 SSH semantic validator：`commitManifest` 会执行 business validation，但当前生产代码尚未注册真实 `sshValidator`；validator 缺失会 fail closed（`AppSecrets.java:145-173,614-634`）。provider/CAS 单元测试可注入明确的 test validator，production 不得用 dummy validator 绕过。

### Minimal next increment

建议把下一增量限定为“Java managed snapshot + candidate contract + 真 AppSecrets CAS”，不接 OpenSSH caller：

1. 新增一个 provider-owned snapshot receipt，精确选择 AppSecrets `KNOWN_HOSTS` object，计算并保存 base digest 和 vault/epoch/snapshot binding。
2. 新增一次性 candidate 输入；candidate 缺失、body 被修改、replacement digest 不等于 Evidence 或 proposal digest 时立即失败。
3. 让 proposal source 显式持有 `hostProof`/approval context，构造完整 `SshTrustProposal`；proposal body 仍只在 managed provider/CAS 内，不加入 JNI wire。
4. 让 CAS writer 绑定 role/hop/target/connection/host proof、key type/classification/fingerprint/digests、snapshot epoch/deadline，并只通过 `SshVaultAdapter.trustRequest`/`updateTrust`。成功返回前不能释放 proposal/candidate；成功后全部 wipe/revoke。
5. 保留现有 `SshNativeCallbackAdapter` 的 `ENOTSUP`/missing-provider 行为；用 Java test-owned candidate 验证真实 Store/manifest CAS，但不要把该 fixture 描述成 OpenSSH caller。

### Fail-closed before pinned OpenSSH `ssh_api` caller

当前 `ssh_native_client.open` 会验证 role PID/UID、owned socket、CLOEXEC/NONBLOCK、callback token、environment 和 deadline，然后仍以 `ENOTSUP` 返回；不会创建 OpenSSH client、读 socket、启动 KEX 或关闭 caller-owned FD（`android/app/src/main/cpp/ssh_native_client.c:61-133`）。`step` 和 verifier 同样保持 `ENOTSUP`/zero progress。该目标文件是 host-only，不在 normal APK 的 `compileSshFiles` 中（`android/app/build.gradle:157-176,246-266`）。

在 provider/CAS contract 完整前，必须保持以下规则：

- no provider / no exact managed snapshot / no candidate / no approval / no host proof / no live epoch：不调用 pinned `ssh_api`，返回 provider-missing 或 typed CAS failure；不能把 digest match 当作 host approval。
- `KNOWN` 只能走 `CasResult.NOT_REQUIRED`，不得创建 replacement；`NEW` 必须有 exact proposal、unknown-key approval、trust proposal approval 和最终 `COMMITTED`。
- 不从 native read-only file view 猜测 proposal body，不在 Java 中重写 OpenSSH hostfile 语义，不用旧 view、旧 epoch、旧 role 或只匹配 host/target 的 proof。
- 在未来 caller 具备 provider 后，仍需先绑定 role/callback/verifier，再 attach managed socket；使用 pinned source map 指定的 incremental packet loop，不能调用 stock `ssh_connect`/blocking `ssh_kex2` 路径。host verification、signature、NEWKEYS proof 和 credential handoff 必须在同一 hop/target/epoch 上完成（`.trellis/tasks/10-08-ssh-terminal-client/research/openssh-10.6p1-production-kex-source-map-20261015.md:21-84`）。
- provider/candidate 或 native cleanup 任一不确定时，保持 fd caller-owned、关闭/cleanup failure fail-closed，不返回 partial host success 或 credential。

### Missing signatures and bindings

| Boundary | Current gap | Required contract decision |
| --- | --- | --- |
| `TrustProposalSource.proposalFor(Evidence)` | 无 candidate bytes、managed snapshot、proof/approval | 增加一次性 candidate + snapshot receipt + host approval context，或让 provider-owned object 显式承载这些值；缺失即拒绝 |
| `TrustCasWriter.write(Evidence, Proposal)` | 无 proof、role context、epoch/snapshot receipt；approval 只靠隐式调用顺序 | writer 必须接收/验证 immutable Proof/approval 和 snapshot context，并在 final guard 重验 |
| `SshVaultAdapter.trustRequest/updateTrust` | 只显式 gate connection/target/proposal，不绑定 `SshRoleContext`/hop/proof | 增加 role/proof-bound overload，或强制 `VaultOperation.bindRole` + `requireRole` + exact key type/classification/digest checks |
| `VaultOperation` | 已有 epoch/deadline/snapshot，但 trust path 未必绑定 role；现有 role check 不覆盖全部 host evidence fields | trust operation 绑定 role/hop/proposal binding/host proof；最终 guard 检查 epoch/deadline/current vault and exact evidence |
| `SshTrustProposal` | 有 role/hop/object/base/new binding，但没有 vault/epoch/request receipt | 不一定把密钥材料塞进 proposal；至少用不可伪造 provider snapshot receipt 与 operation 绑定，禁止 proposal 跨 callback/cache 重放 |
| `SshPublicFileLease` / native view | read-only；Java 无 digest/body API，role close 不自动 revoke | 明确由 provider/role owner 管理 lease；view identity 必须与 snapshot receipt 一一关联；candidate handoff 另行冻结 |
| HostEvidence JNI wire | 有 host/evidence digests，无 view binding/candidate handle | 在 caller 任务决定是否增加 opaque view/candidate token；在此之前 Java provider 不能把 native digest 当作受管 snapshot proof |
| AppSecrets validator | production real SSH semantic validator 尚未注册 | enable production CAS 前注册真实 validator；缺失继续 `VAULT_SSH_VALIDATION_REQUIRED` |

### Test matrix

| Scenario | Required assertion |
| --- | --- |
| KNOWN exact object | type/path/id/identity 和 base digest exact；无 proposal/body；只返回 `NOT_REQUIRED` |
| NEW happy path | candidate replacement bytes 的 digest exact match Evidence；role/hop/target/connection/host/port/key type/classification/fingerprint 全匹配；两类 approval 都通过；真实 AppSecrets content 被 CAS 替换后才返回 `COMMITTED` |
| Base stale / generation or epoch stale | `VAULT_CHANGED`/expired/typed failure；原 known_hosts 不变；不返回 committed |
| Replacement mismatch | candidate body、proposal digest 或 Evidence digest 任一不匹配；不进入 `updateTrust` |
| Wrong object | wrong type/path/id/identity、private object、missing object、reference closure mismatch；拒绝且不从相似 path fallback |
| Proof substitution | wrong role/hop/connection/target/host/port、key type、classification、fingerprint 或 digest；final guard 拒绝 |
| Approval/provider missing | missing semantic validator、unknown-key approval、trust proposal approval 或 candidate；保持 fail-closed |
| Cancellation/lifecycle | role close、session lock、background timeout、callback cancel、deadline、lease cleanup failure；proposal/candidate wipe，view revoke，CAS 不发生 |
| Commit uncertainty | store/readback/guard failure；锁定或报告 typed failure，绝不伪造 `COMMITTED` |
| View boundary | public bodies readable only in synchronous callback；private body never appears；one singleton lease、old token、poisoned cleanup 均不能获得新 view |
| Existing native gate | host `ssh_native_client` tests 继续断言 provider missing、zero progress、fd ownership unchanged；normal APK 不含 host-only client/OpenSSH caller |

现有 `SshProductionBridgeTest` 的 fake managed provider/fake CAS 只能验证 wire 和闭包，不证明真实 AppSecrets mutation（`android/app/src/test/java/im/majo/ctos/SshProductionBridgeTest.java:312-560`）。因此 happy path 必须新增使用真实 `AppSecrets`/`SshVaultAdapter`/approval gate 的 provider test，而不是扩大 fake `CasResult` 覆盖。

### Suggested implementation file set

这是给后续 implement agent 的最小候选集合；本研究没有修改这些文件：

1. `android/app/src/main/java/im/majo/ctos/SshManagedKnownHostsProvider.java`（新文件）：拥有 exact managed path/object、snapshot receipt、candidate 生命周期；实现真实 proposal source/CAS writer。
2. `android/app/src/main/java/im/majo/ctos/SshManagedProvider.java`：增加 proof/candidate/snapshot-aware 的内部 overload 或迁移 seam；保持旧 fake 测试接口只能用于兼容测试。
3. `android/app/src/main/java/im/majo/ctos/SshVaultAdapter.java`：增加 role/proof-bound trust request/update overload，最终 guard 强制 operation role/epoch 和 exact evidence。
4. `android/app/src/main/java/im/majo/ctos/VaultOperation.java`：仅在现有 `bindRole`/`requireRole` 不足时增加 trust-specific binding；不要重复实现 AppSecrets 的 epoch verification。
5. `android/app/src/main/java/im/majo/ctos/SshTrustProposal.java`：仅在 snapshot receipt 无法作为独立 provider-owned context 时扩展不可重放 binding；不把 replacement body 放进 JNI result。
6. `android/app/src/test/java/im/majo/ctos/SshManagedKnownHostsProviderTest.java`（新文件，或受控扩展 `SshProductionBridgeTest.java`）：真实 managed object、digest、approval、CAS、stale/epoch/proof/candidate cases。
7. `android/app/src/test/java/im/majo/ctos/VaultTest.java`：trust operation 的 role/proof/epoch、validator、readback/uncertain failure 回归。
8. `android/app/src/test/java/im/majo/ctos/SshPublicFileLeaseTest.java`：仅在 snapshot/view identity 变化时覆盖 lease binding 和 cleanup；维持 private-body 不泄露。

本增量不应修改 `ssh_api` caller、OpenSSH KEX loop、normal APK native build 或 `ssh_native_client` 的 fail-closed gate；这些要等 candidate handoff、real socket、partial I/O、NEWKEYS digest 和 credential proof 的独立实现/验证完成后再进入范围。

### Verification commands

以下是后续实现完成后的建议命令；本研究阶段未运行它们，也未声称 provider/caller 已实现：

```sh
./hako bash -lc 'cd android && ./gradlew :app:testReleaseUnitTest --tests im.majo.ctos.SshManagedKnownHostsProviderTest --tests im.majo.ctos.SshProductionBridgeTest --tests im.majo.ctos.VaultTest --tests im.majo.ctos.SshPublicFileLeaseTest --console=plain'
```

```sh
./hako bash -lc 'cd android && ./gradlew :app:testSshNativeClientHost :app:testSshCallbackAdapterHost :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease --console=plain'
```

```sh
unzip -l android/app/build/outputs/apk/release/app-release.apk | rg 'ssh_native_client|openssh|launcher|probe|control'
```

最后一条应保持 normal APK 不包含 host-only `ssh_native_client`、OpenSSH caller 或实验 launcher；若后续变更触发匹配，必须先停止 caller 接入并重新审查 build boundary。OpenSSH 真 caller 的 pinned-source build/real-socket test 不属于本增量验证命令。

### External references

- 本研究未新增网络检索；外部协议依据限定为仓库保存的 pinned OpenSSH 10.6p1 source map：`.trellis/tasks/10-08-ssh-terminal-client/research/openssh-10.6p1-production-kex-source-map-20261015.md`。
- 历史 `ssh-memory-results.md` 的 hostfile object feasibility 只证明匿名内存对象实验，不证明 AppSecrets provider/CAS、Android lifecycle 或 production caller，不能作为已实现证据。

### Related specs

- `.trellis/spec/backend/app-secrets.md`: managed public file view、同步借用/wipe、trust CAS、operation/epoch/lease 和 missing-provider fail-closed。
- `.trellis/spec/backend/ssh-native.md`: HostEvidence/CAS seam、callback lifecycle、production SSH blocker、normal APK exclusion 和 `ssh_native_client` fail-closed contract。
- `design/README.md`, `design/plan.md`, `design/constraints.md`, `design/ssh-terminal.md`, `design/app-authentication.md`: ctOS landing、当前规划/约束、SSH terminal 状态和 no-plaintext-disk / managed known_hosts 方向。

## Caveats / Not Found

- 未找到 production managed known_hosts provider、real `TrustCasWriter`、candidate body handoff、OpenSSH `ssh_api` caller、KEX loop、真实 native verifier 或 production SSH semantic validator。
- 当前 native file view 只能读取受管 public object；不能从它导出 replacement candidate，也不能让 OpenSSH 直接写回 AppSecrets。
- 当前 fake bridge tests 不验证真实 encrypted manifest mutation；现有 `VaultTest` 验证了低层 exact trust CAS，但尚未把 role/hop-bound HostEvidence、host Proof 和 managed file snapshot 合并成一条真实 provider path。
- 没有执行设备连接、网络 SSH、OpenSSH caller、构建或测试命令；历史设备授权不被本研究视为当前验证。
- 本文件是研究/contract 建议，不是实现记录；没有编辑生产源码、spec、workflow、任务的其他 artifacts，也没有派生 implement/check agent。

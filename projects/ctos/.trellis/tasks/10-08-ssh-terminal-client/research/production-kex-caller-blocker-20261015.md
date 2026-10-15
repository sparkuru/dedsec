# Production KEX caller blocker — 2026-10-15

## Status after the managed CAS increment

The managed Java/AppSecrets writer is now implemented and verified, but it is
not the missing native caller. `SshManagedKnownHostsProvider` can snapshot one
exact `KNOWN_HOSTS` object and commit one caller-supplied exact replacement
through the role/hop-bound proof and CAS guards. It deliberately does not
parse an OpenSSH host key, format a `known_hosts` line, or expose replacement
bytes through the existing HostEvidence JNI wire.

The remaining blocker is therefore narrower than “no CAS writer”: a
compilable pinned-OpenSSH hostfile issuer seam now exists for host/fixture
verification, but there is still no production caller and no same-build
source/object manifest linking that seam to the newly frozen handoff in the
normal APK. The separate
`ctos_ssh_callback_known_hosts_candidate`/JNI/
`SshKnownHostsCandidateHandoff` contract now transfers one bounded exact body
once, binds it to the live role/evidence and managed snapshot, wipes the native
and managed temporary arrays, and leaves the existing CSHR metadata wire
unchanged. The current read-only `SshPublicFileLease`/native view still cannot
produce that body itself.

本轮按 `openssh-10.6p1-production-kex-source-map-20261015.md` 检查后，没有把
fixture 的 `ssh_api` 内存流升级为 production caller。pinned `ssh_api` 确实提供
`ssh_init`、`ssh_packet_set_connection`、`ssh_packet_set_nonblocking`、
`ssh_set_verify_host_key_callback`、`ssh_packet_next` 及 input/output buffer API，
但当前 production role snapshot 没有 host digest，仓库也没有能从受管
`config/known_hosts` view 产生 `KNOWN`/`NEW` 分类、exact proposal/base/replacement
digest 和 CAS 写回的 pinned native-facing hostfile issuer。Java managed provider
现在能完成 CAS writer 这一半，但 exact replacement 仍由 managed caller/test 注入。

因此本次保持 `ssh_native_client.[ch]` fail-closed，并记录下一接入所需的精确输入，
而不链接一个不完整的 caller：

- 校验 live role token、当前 PID/UID、明确 socket ownership、CLOEXEC/NONBLOCK FD、
  callback token/environment、精确 deadline binding 与 bounded step buffer；
- 在验证完这些边界后稳定返回 `ENOTSUP`（95，`HOST_PROVIDER_MISSING`），不调用
  `ssh_init`、`ssh_packet_set_connection`、`ssh_packet_set_nonblocking`、
  `ssh_set_verify_host_key_callback` 或 `ssh_packet_next`；
- 不根据 fingerprint 猜 `KNOWN`/`NEW`，不调用 `ctos_ssh_kex_approved`、
  `ctos_ssh_kex_signed`、`ctos_ssh_kex_complete` 或任何 credential callback；
- 失败时不接管或关闭 caller socket，输出 client 保持 NULL，step 不读取 payload。

## 2026-10-15 source/object closure re-audit

The approved G1 run does contain the upstream pieces, but that is evidence of
an ABI baseline, not a production input boundary. `libssh.a` contains
`hostfile.o`, `ssh_api.o`, `packet.o`, `sshkey.o` and the pinned KEX objects;
the measured object hashes are:

```text
hostfile.o  3d6c72ef5898352adbee92b589ef515e1bc1c981309649e2f6c70689d3038bde
ssh_api.o   438aa4eef0dc7935e5b59369ea1e724f27ac716821e1b982de8ce5bb78396438
sshkey.o    b544638c003fd5186a53995038977247f93bc911f65235a6353a82949894dfb2
packet.o    49faf16fef8c86cd561fcfee6731a7625ccd9e1df2f1438f597de14b30bdb627
libssh.a    20b12e756e3be0bf94323dbcc87c7a59eb741c9568a9b2558c089ccfaf0a04f3
```

The existing `android-upstream-build.sh` verifies that baseline and only
publishes the opt-in fixture `g2-driver`/`g2-vfs`/`g2-baseline` outputs. Normal
`compileSshFiles` does not consume those objects. `hostfile.o` still has an
unreviewed dependency closure including OpenSSH key/buffer/HMAC code and
stdio/filesystem operations (`fopen`, `fdopen`, `mkstemp`, `link`, `rename`).
No project-owned production issuer has yet narrowed those operations to the
managed public view, and no same-build manifest excludes the stock high-level
caller and fixture transport while linking the required KEX closure. Therefore
the ignored `build/` objects must not be promoted into the normal APK by path
or by hash alone.

This re-audit made no source, Gradle, APK, device, network, vault or commit
change. It confirms the existing fail-closed boundary rather than extending
the production claim.

## Issuer seam detail from the same source audit

The pinned `hostfile.c` does expose `load_hostkeys_file()` for a caller-owned
`FILE *`, so parsing a managed public body can stay in memory.  Its write path
is different: `add_host_to_hostfile()` opens a pathname and the exact
`write_host_entry()` formatter is `static`.  A production issuer therefore
needs a reviewed upstream stream-writer patch (or an equivalent source-owned
wrapper that preserves that exact formatter); it must not call the pathname
writer against a vault path or replace it with a Java-side formatter.

The managed Java snapshot currently retains the exact object metadata and base
digest, while the public file view and the native candidate handoff are
separate lifetimes.  No production caller currently binds the public
`KNOWN_HOSTS` view, the pinned issuer result, the role-bound evidence, and the
one-shot candidate receiver under one deadline/cancel/close owner.  That
binding is part of the next implementation increment, not evidence that the
existing CAS provider has already become a native OpenSSH provider.

## Exact next input required before linking `ssh_api`

The next caller increment needs all of the following as one reviewed contract,
not only a source archive or a Java CAS writer:

1. A pinned 10.6p1 hostfile issuer that consumes the managed public view and
   produces the exact `KNOWN`/`NEW`/reject classification, key type, raw
   SHA-256 fingerprint, base digest, and replacement digest using upstream
   hostfile semantics. It must not use stock `Options`, `ssh_connect`,
   `fork`/`exec`, `ProxyCommand`, or a simplified Java formatter.
2. Integration of the already-frozen one-shot native-to-managed candidate
   handoff for `NEW`. The pinned issuer must call the dedicated API, bind the
   role/hop/target/connection/host/port/key type/fingerprint, candidate ID,
   vault snapshot and both digests, and prove that its temporary body lifetime,
   cancellation/close cleanup and uncertain-CAS behavior obey the receiver
   contract. The current bounded HostEvidence JNI record remains metadata-only
   and cannot carry the proposal body.
3. A same-pinned-build source/object manifest proving that the issuer and
   handoff can link without importing the stock high-level caller or fixture
   transport into the normal APK. Only after those inputs exist should the
   wrapper implement `ssh_init` → owned FD attach → incremental
   `ssh_packet_next` → host callback → inbound `NEWKEYS`/session digest.

Until those inputs are available, the valid executable result remains
`ENOTSUP` with a null client, zero progress, and caller-owned FD. The next
focused regression should then cover the real socket partial-I/O/EAGAIN/EOF,
deadline/cancel, close retry, host proof, and inbound `NEWKEYS` JNI proof
ordering; this boundary intentionally does not claim any of them.

## 2026-10-15 pinned hostfile issuer seam (fixture-only)

The next narrow input now exists as a project-owned host/fixture seam, but not
as the production caller:

- `android/app/src/main/cpp/ssh_hostfile_issuer.[ch]` requires a live role-bound
  public view whose root is `ssh/config` and whose config object references
  `ssh/known_hosts`; it selects the known-hosts object by exact virtual path and
  type and never opens a vault or host pathname.
- `load_hostkeys_file()` consumes the managed body through a caller-owned
  memory stream. The reviewed patch
  `openssh-10.6p1-hostfile-stream-writer.patch` exposes only the exact upstream
  formatter through `ctos_hostfile_write_entry(FILE *)`; the issuer does not
  call `add_host_to_hostfile`, `hostfile_replace_entries` or
  `hostfile_create_user_ssh_dir`.
- Non-certificate classification follows pinned `HOST_OK/NEW/CHANGED/REVOKED`.
  Certificates remain policy-denied as `CERTIFICATE`, but the issuer runs
  upstream revocation matching first so a revoked certificate or CA is
  reported as `REVOKED`.
- NEW builds the exact body/digests and invokes the existing one-shot candidate
  handoff; callback failure aborts that managed candidate and wipes the native
  scratch/body. The C fixture covers root rejection, certificate/CA revoke,
  candidate wipe and callback rollback.

The reproducible runner is
`research/android-hostfile-issuer-build.sh`. With the approved
`build/ssh-native-g1/stock-run.lOHF9Oai` baseline it completed fixed
archive/tree/object hashes, patch application, strict C11/Werror compilation,
and AArch64 PIE linking:

```text
SSH_HOSTFILE_ISSUER_BUILD gate=pass runtime=not-run normal_apk=not-wired
```

Evidence was retained at `build/ssh-native-g3/run.8YjiwDnk/`. The hako image
does not contain `qemu-aarch64`, so the ARM fixture was not executed. Normal
`compileSshFiles` was not changed and `ssh_native_client` remains `ENOTSUP`.
The production caller still lacks socket partial-I/O/EOF/EAGAIN, event-loop
`ssh_api` integration, KEX/NEWKEYS proof ordering, authentication, and device
or external-SSH validation; this seam does not clear the blocker.

这不是 production OpenSSH client 已接入的证据。只有在 exact host provider/CAS
合同冻结并能与 pinned object 同一构建后，才能实现 `ssh_init` → FD attach →
nonblocking → verifier → incremental packet step → inbound NEWKEYS/session digest
→ synchronous attached JNI callback 的 caller；到那时必须另补 partial read/write、
EAGAIN、EOF、BOOTTIME deadline、cancel 和 close-retry 的真实 socket regression。

## 2026-10-15 pinned caller fixture seam（G3.4）

上述输入现已在 host/fixture 范围内组成一个可链接的 caller seam，但没有改变
production blocker：

- `ssh_pinned_client.[ch]` 使用 pinned `ssh_api` 在同进程 socketpair 上附着 caller-owned
  FD，入口绑定 live role/PID/UID/deadline/host、managed view、expected fingerprint 和
  callback environment；step 覆盖 bounded `recv`/`ssh_input_append`/`ssh_packet_next`/
  `send`/output consume，以及 partial write、EAGAIN、EOF、cancel/deadline。
- host verifier 复用 `ssh_hostfile_issuer` 的 upstream classification/stream formatter，
  只允许 KNOWN/NEW；caller 对 HostEvidence/CAS result 再比对完整 role/hop/port、identity、
  classification/outcome/CAS、connection/target/host/key/fingerprint 和两份 digest。
  NEW candidate 已 handoff 后，evidence/binding/protocol failure 通过新的幂等 abort API
  回滚精确 managed candidate。`kex->done` 后只调用一次 session digest callback。
- runner 固定 stream-writer patch SHA256
  `a786566e1842996c0282647457f4dd63558cde00bc79ae1ba6bcd552bf30484a`、KEX guard patch
  SHA256 `50a59606000dc871f1b29e33eeb6d77fb5da456f40526d2871357758b61f8785`，分别链接
  issuer/caller AArch64 PIE，检查 no-undefined、required symbols、stock caller 排除和
  normal APK exclusion。`build/ssh-native-g3/run.3PZSbIzM` 为
  `SSH_HOSTFILE_CALLER_BUILD gate=pass runtime=not-run normal_apk=not-wired`。
- `qemu-aarch64` 不在 hako 镜像中，因此没有 ARM runtime 结果；normal APK SHA256
  `67668e34e2bba40359e43e048404ffeb5645629a130f86e86b2d855a170ffff9`，JVM=177/0/0/0、
  callback=20、native boundary=27。未连接设备、真实 vault、外部 SSH 或网络。

caller fixture 仍不进入 `compileSshFiles`/normal APK，生产
`ssh_native_client` 仍返回 `ENOTSUP`。仍待 production event loop、typed credential/
userauth、PTY、forward、service、UI、Android runtime 和外部 SSH 验证。另修正了一个
错误路径：issuer 返回失败时 caller 也必须 `fclose` 临时 `fmemopen`，不能只清零 body
而遗留 FILE；该修正已通过最新 strict AArch64 runner。

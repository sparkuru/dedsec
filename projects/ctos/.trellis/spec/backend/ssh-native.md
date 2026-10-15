# SSH Native Gates

## Current scope

`10-08-ssh-terminal-client` now owns a production source/object and native C
caller boundary, using the approved parent PRD/design and existing
AppSecrets/SshVaultAdapter contracts. The normal APK production archive is
rebuilt from the pinned OpenSSH 10.6p1 source with `-fPIC`; old fixture objects
and stock high-level callers are not valid inputs. The client is not yet a
complete Android product: JNI/Java session orchestration, runtime/ARM
execution, userauth, typed credentials, PTY, forwarding, service/UI, device
and external SSH remain open gates. Missing semantic/trust/runtime providers
must keep the product path fail-closed.

## G3.5: production source/object closure and native C caller

### 1. Scope / Trigger

- Trigger: `compileSshFiles` now links a real pinned OpenSSH caller and the
  hostfile/KEX provider seam into the normal APK.
- Scope: only the same-process native boundary and normal-link closure are
  implemented. `ssh_pinned_client` and other opt-in fixture callers remain
  excluded from the APK.

### 2. Signatures

```c
int ctos_ssh_native_client_open(
    const struct ctos_ssh_native_client_config *,
    struct ctos_ssh_native_client **);
int ctos_ssh_native_client_step(
    struct ctos_ssh_native_client *,
    struct ctos_ssh_native_client_step *);
int ctos_ssh_native_client_verify_host_key(
    struct ctos_ssh_native_client *, const void *opaque_host_key);
int ctos_ssh_native_client_close(struct ctos_ssh_native_client *);
int ctos_ssh_native_client_destroy(struct ctos_ssh_native_client *);
```

The fixed production builder is
`.trellis/tasks/10-08-ssh-terminal-client/research/android-production-openssh-build.sh`.
Gradle must run `compileSshProductionObjects` before `compileSshFiles` and
must consume only its generated archive/include output.

### 3. Contracts

- `config` binds live role, current PID/UID, future BOOTTIME deadline,
  `CLOEXEC|O_NONBLOCK|O_RDWR|SOCK_STREAM` descriptor, same-thread callback
  token/environment, managed view, `ssh/known_hosts` path, host/candidate
  identity, host public key/fingerprint and a step budget no larger than
  `CTOS_SSH_NATIVE_CLIENT_MAX_STEP_BYTES`.
- All admission/setup checks run before OpenSSH attaches a descriptor. A
  successful open attaches a `F_DUPFD_CLOEXEC` duplicate and the native client
  owns both the duplicate and original descriptor. A failed open leaves the
  original descriptor and pre-opened callback caller-owned.
- One `step` performs a bounded nonblocking socket read, OpenSSH input/packet
  processing, and bounded output write/consume. Non-empty application input or
  output pipes are rejected; OpenSSH owns transport bytes.
- Host verification consumes the managed public view through the pinned
  hostfile issuer, rechecks HostEvidence/CAS binding, and rolls back a handed
  off NEW candidate on later failure. KEX completion emits one session digest
  callback only.
- `close` is event-loop-owner synchronous: candidate abort, callback release,
  KEX unbind/OpenSSH free, then original descriptor close. A release/close
  failure leaves a retryable non-tombstone. `destroy` accepts only a closed
  tombstone.
- The production archive must be source-recompiled PIC, contain the complete
  pinned `libssh` member set plus PIC `ssh-pkcs11.o`/`ssh-sk-client.o`, and
  define no `ssh_connect`, `ssh_login`, `ssh_userauth2`, or `ssh_proxy_*` stock
  caller.

### 4. Validation & Error Matrix

| Condition | Required result |
| --- | --- |
| Null/malformed config, stale role/view, wrong owner/deadline, bad socket or missing trust input | `-1`; `*client == NULL`; original FD and callback remain caller-owned |
| Non-empty step input/output pipe | `-1`, `EINVAL`, zero progress |
| Cancel, expired deadline, EOF, protocol/I/O failure | terminal error; no further progress; candidate rollback if needed |
| Step after close | `-1`, `ENOENT` |
| Step after terminal completion/failure | `-1`, `EALREADY` |
| Callback release failure | `-1`; client remains retryable with OpenSSH and original FD still attached |
| Original-FD close failure | `-1`; client remains retryable with the original FD handle retained for another close attempt |
| Baseline hash/tree/patch drift, non-PIC or incomplete archive, stock caller symbol | production builder fails before APK link |
| Missing qemu/Android runtime | report `runtime=not-run`; never count cross-link as runtime pass |

### 5. Good / Base / Bad Cases

- Good: fixed source/tree hashes, 83-member PIC archive, explicit PKCS#11/SK
  closure, no stock high-level symbols, normal APK shared link succeeds.
- Base: AArch64 host cross-link and JVM/build gates pass; ARM execution and
  Android/JNI session orchestration remain unverified.
- Bad: copy G1 `-fPIE` objects into a shared `.so`, omit the explicit
  `ssh-pkcs11`/`ssh-sk-client` definitions, or wire `ssh_pinned_client` into
  `compileSshFiles`.

### 6. Tests Required

- Run the fixed production builder and inspect baseline/source/object/member/
  symbol evidence; assert PIC archive build and high-level caller exclusion.
- Run `:app:testSshNativeClientHost` as an AArch64 cross-link and assert the
  explicit `runtime=not-run` boundary when qemu is unavailable.
- Run callback host, JVM, strict `compileSshFiles`, all Release ABIs, lint,
  assemble, APK ZIP scan and `git diff --check`; assert 0 JVM failures/errors/
  skips and no fixture caller objects.
- Before claiming runtime, add an authorized Android ARM execution test for
  partial I/O/EAGAIN/EOF/cancel/deadline, host proof, KEX completion, close
  retry and JNI/Java session ownership. Build success alone is insufficient.

### 7. Wrong vs Correct

Wrong: extract the ignored G1 `libssh.a`/fixture objects and link them directly
into `libctos_ssh_files.so`, or close the callback after `ssh_free`.

Correct: rebuild the pinned source closure with `-fPIC`, add the measured
PKCS#11/SK PIC objects, reject stock high-level symbols, and close the same
thread callback before KEX unbind/OpenSSH free and descriptor release.

## G0: synthetic Android memory and managed exec

The first probe is opt-in and independent of production data. Compile for
API28/arm64 with the pinned NDK, strict warnings and 16KiB ELF alignment.
Only `ctosSshMemoryProbe=true` together with `ctosSecretsUiFixture=true` may
package it. Use a separate generated jniLibs directory included only by that
fixture variant; a later normal build must not pick up stale probe output.
Never register the executable as a Portable tool or expose a general SSH CLI.

Use fixed package/test identities and verify distinct UID, no shared UID,
fixture Application and its own Root auto-start disabled before execution.
Select only the explicit probe instrumentation method/opt-in. Execute the
fixed absolute `nativeLibraryDir` binary through ProcessBuilder, without
Shell, Root, arbitrary argv/path, activity launch, user vault, personal SSH
files, network listeners, clipboard or ordinary payload files.

Probe anonymous memory candidates separately. API26 ASharedMemory is a public
NDK entry; the libc memfd_create declaration is API30. A raw syscall candidate
at API28 may only be a clearly labelled capability experiment, not a silent
fallback or proof of API28/29 device support. Record actual backend/errno and
retain unsupported candidates. At least one complete eligible candidate is
required; zero eligible is a failure, not a skipped or successful gate.

An eligible file candidate needs actual map/mlock and the upstream-required
POSIX file shape: independent offsets on reopen, fstat, stdio, append/truncate
and owned held-FD behavior. mmap alone is insufficient. Restricting shared
memory protection or closing a parent FD does not revoke an existing child
mapping or parsed secret. Use explicit bounded parent/child synchronization,
own-buffer wiping and verifiable held-FD cleanup. This is evidence only for
probe-owned resources, not arbitrary same-UID isolation or a production broker.

Native exec inherits only a deliberate owned FD whitelist and fixed synthetic
generation. Use finite poll/wait deadlines, owned child PID/process group,
death-race checks and reap on success, cancellation and failure. Native child
code must never re-enter the JVM. Java must drain bounded output and close
its exact Process/executor streams on every path. Report only fixed protocol
stages/statuses and numeric metadata; never dump payload, arbitrary paths,
environment, raw exception messages or unfiltered child output.

## Gate progression and validation

G0 compile/packaging is not a device pass. A current App UID/SELinux execution
is required; adb-shell execution cannot substitute. API30/4KiB evidence does
not prove API28/29 or a real 16KiB device. Device install needs current SSH-task
authorization; earlier TOTP authorization is not inherited.

G1 pins official OpenSSH/OpenSSL inputs, hashes/licenses and Bionic builds.
G2 uses real same-version upstream config/glob/authfile/hostfile functions and
parent/ProxyJump behavior, including no local command or trust bypass effects.
G3 integrates typed host capabilities, per-hop host/identity approval, bounded
credential release, encrypted trust proposal CAS and actual synthetic protocol
authentication/PTY/forwarding/service lifecycle. Do not replace upstream
semantics with a simplified parser, or quietly write plaintext temporary files
when a gate fails. Return to the approved design to resolve a failed premise.

Keep actual results and gaps in design/verification.md and the existing task.

## Executable contract: G0 and Android build baseline

### 1. Scope / trigger

Applies to the Java-to-native synthetic probe and the G1 research builder.
Actual 2026-10-13 G0 evidence is API30/4KiB/Permissive, App UID10167:
raw memfd is eligible; ASharedMemory maps/locks but fails regular-file shape
with errno95. Do not promote this to Enforcing, API28/29 or OpenSSH behavior.

### 2. Signatures

```sh
./hako bash -lc 'cd android && ./gradlew -PctosSecretsUiFixture=true -PctosSshMemoryProbe=true -Ptarget-platform=android-arm64 :app:assembleSecretsFixtureRelease :app:assembleSecretsFixtureReleaseAndroidTest :app:lintSecretsFixtureRelease'
adb -s <current-authorized-serial> shell am instrument -w -r -e class 'im.majo.ctos.SshMemoryPlatformTest#syntheticAnonymousMemoryAndManagedExec' -e ctosSshMemoryPlatformProbe true im.majo.ctos.secretsfixture.test/androidx.test.runner.AndroidJUnitRunner
./hako bash /app/.trellis/tasks/10-08-ssh-terminal-client/research/android-stock-build.sh /app/build/ssh-native-g1/<verified-input-directory>
```

Native entry is the fixed APK `libctos_ssh_memory_probe.so --probe`;
only its owned self-exec modes may receive fixed FDs3/4/5 and synthetic
generation73. No Shell or caller-supplied file, argv, payload or environment.

### 3. Contracts

Protocol records are `SSH_G0 backend=<enum> phase=<allowlist> state=pass|unsupported|error errno=<0..4095> value=<u64>`.
Backends are `meta`, `ashared`, `raw_memfd`; Java rejects unknown records,
non-ASCII, more than16384 bytes/80 records/200 chars per record. Pass requires
errno0, no mandatory error, complete candidate phases, final gate/count/exit
agreement and at least one eligible backend. Diagnostics alone do not pass.

Native reap uses exact owned PID, WNOHANG, finite monotonic deadlines plus
100/200 attempt caps. Clock failure forces error; unconfirmed PID ownership
persists, prevents another spawn and receives a bounded final retry.
`cleanup=pass` requires region cleanup and no pending child. Java cleanup
has an 8-second wait budget, tries each of three streams independently, keeps
terminating/reaping/executor cleanup through interruption and restores the
interrupt flag afterwards. Cleanup failure fails the test.

G1 only accepts canonical nonsymlink `/app/build/ssh-native-g1/` inputs inside
hako, verifies the two fixed official payload hashes before fresh mktemp and
again after copy, then extracts. Configure/make use `env -i`, pinned NDK/API28,
private static PIC libcrypto, public NDK zlib and stage timeouts. Preserve
Compression (`WITH_ZLIB=1`), OpenSSL/header checks, config logs, complete source
notices and failed runs; do not execute ARM artifacts or install to production.
Applied compatibility patches must pin original sources and patch SHA, use
exact hunks, preserve before/after evidence and identify a patched ABI baseline.

### 4. Validation and error matrix

| Condition | Required result |
| --- | --- |
| Probe property false/unknown or true without fixture | Gradle configuration failure |
| Missing instrumentation opt-in | No execution; never count as gate pass |
| Wrong method, package/UID, reused data or invalid executable | Fail before execution |
| ASharedMemory maps but lacks regular POSIX shape | Unsupported candidate; retain errno |
| Zero complete candidates, clock failure, unconfirmed reap or cleanup failure | Gate/test failure |
| Changed source/patch hash, symlink/escaped input, cross-build error | Stop and retain evidence |
| Native/provider or crypto/DNS behavior unverified | Preserve open gate; no production pass |

### 5. Good / base / bad cases

Good: complete raw memfd lifecycle passes while unsupported ASharedMemory
remains explicit. Base: source/build/ELF only, device gate still open.
Bad: using mmap success, skipped JUnit, adb exit0, patched compile or a
diagnostic-complete log as proof of production SSH.

### 6. Tests required

Build and sign exact fixture artifacts; verify frozen source hashes, arm64 ELF,
interpreter/DT_NEEDED/PT_LOAD and normal APK exclusion after stale probe output.
Run only the explicit authorized method; assert JUnit1/pass/no skip and fixed
candidate/gate protocol. Check owned UID process cleanup, precise uninstall and
production path/hash preservation. Future G1 crypto/runtime and G2 upstream
file/each-hop tests remain required. Fault-injection claims need actual tests;
reviewing clock/interruption code cannot replace them.

### 7. Wrong vs correct: Bionic compatibility

Wrong: fake HAVE_BZERO, skip its fallback because Bionic has a callable macro,
disable crypto/header checks, or call a patched build unmodified stock.
Correct: parse Bionic unistd.h before fallback attribute macros; preserve the
real `(bzero)(...)` definition/prototype for upstream `explicit_bzero`'s
volatile function pointer, and keep that wipe implementation byte-identical.
Resolver compatibility must retain actual DNS/security behavior; never invent
an opaque `_res` structure or silently disable DNSSEC to get a green build.
The current research-only public resolver branch additionally requires both
`__BIONIC__` and `CTOS_ANDROID_ABI_BASELINE_ONLY`; without that flag the upstream
path remains intact. Public res_init/res_query and preserved AD parsing do not
prove EDNS0/DO control or trusted DNSSEC validation. Do not run/package this
baseline as a production trust provider. Native constrained G2 must reject
VerifyHostKeyDNS paths that could accept before the required human host-key
approval; ordinary getaddrinfo target DNS is a separate capability.

## G2 fixture contract

Actual 2026-10-13 evidence: API30/4KiB/Permissive App UID10171,
six groups and real parent/jump exec inheritance passed in 1 test/4.496s.
Both fixture packages were removed and the production APK hash stayed equal.
This is trusted-synthetic evidence, not the production policy/provider gate.

OpenSSH cleanup_exit uses _exit and skips ELF destructors. The VFS-only
wrapper must release owned resources before real _exit without changing its
status; driver/baseline and signal teardown do not use this hook. Hold the
primary WNOWAIT zombie anchor through every group signal, then reap; never
signal a group after its ownership anchor has been reaped. A cleanup receipt
must include bootstrap close/unlock errors and cannot hide later unmap errors.
Mask cancellation through normal teardown until shared view ownership is gone.

Build input collection must include every verified regular member (including
.gitignore): Gradle FileTree default exclusions are insufficient. Direct
Files.walk without FOLLOW_LINKS plus canonical/member checks freezes the
complete 1240-file baseline input list; compare its aggregate before/after build.

This stage uses only fixed trusted synthetic inputs. Its six upstream-file
groups do not establish the production command/trust policy. Native source,
build, packaged artifacts and current App UID execution each require evidence;
Java/Gradle review or successful configuration alone cannot pass this stage.

The fixture property is `ctosSshUpstreamProbe=true` with
`ctosSecretsUiFixture=true` and an explicit canonical nonsymlink baseline
`ctosSshUpstreamBaseline=/app/build/ssh-native-g1/stock-run.<id>`.
Before Gradle snapshots inputs, inspect every descendant of its input trees
without following links, check selected files again, and freeze the File list.
Fixture-only generated JNI and license assets must remain excluded from a
later normal APK even with stale generated output.

Java executes only `libctos_ssh_upstream_probe.so` with two absolute fixed
sibling executables, `libctos_ssh_upstream_client.so` and
`libctos_ssh_upstream_baseline.so`. The explicit test method is
`im.majo.ctos.SshUpstreamPlatformTest#syntheticUpstreamMemoryFilesAndProxyJump`
with `ctosSshUpstreamPlatformProbe=true`. Preserve G0's package/UID/fresh-data/
Root-off guard and bounded drain/cleanup. Java waits at most60 seconds for
the native process, then3 seconds for its reader, with a shared8-second cleanup
wait budget; do not describe these as a total61-second deadline.

Records must match exactly:

```text
SSH_G2 phase=<allowlist> state=pass|error errno=<0..4095> value=<u64> pid=<positive-int32> generation=73
```

Require unique allowlisted complete ASCII records within16384 bytes,
80 records and200 chars per record. `pass` requires errno0 and `error` requires
nonzero errno. Require all six groups `config`, `fd_negative`,
`identity_hostfile`, `proxy_inherit`, `cancel_reap`, `revoke_local`, plus
`cleanup`, distinct verified `client_parent`/`client_jump` PIDs, and fixed
boundary records `capacity=16384`, `object_limit=16`, `proposal_stream=1`,
`local_scope=1`. Both actor records have value1 and precede `proxy_inherit`
with value at least2. A final `gate=pass value=6` and process exit0 must agree;
missing metadata, errors or incomplete cleanup must fail. Optional
`crypto_version` must be positive if emitted; absence proves no runtime
version or algorithm result. Never echo arbitrary child output on failure.

Immutable private payloads need true-length locked memfd-backed access.
Mutable public known_hosts proposals may use bounded NDK funopen streams over
locked anonymous storage with explicit logical length, without claiming full
POSIX compatibility or locking/erasing every upstream heap copy. Each group
must call actual pinned upstream readconf/glob/authfile/hostfile functions.
Only owned random127.0.0.1 listeners are permitted for the synthetic jump and
target. Require actual distinct parent/jump exec reads, no direct-target
fallback, bounded full owned-chain reap and explicit local cleanup evidence.
The baseline negative observer must preserve upstream FD-closing behavior;
startup/linker/getpwuid failures cannot substitute for observing that boundary.

Before device execution, build and review exact artifacts/ABI/signatures/
licenses, test strict configuration guards, and check normal APK exclusion.
Apply the current user scope for this particular loopback fixture rather than
expanding a prior no-network G0 method approval. Never call trusted synthetic
success production policy success: local command hooks, native bypasses,
malicious paths, per-hop capabilities, human host-key approval and encrypted
trust CAS require separate mandatory gates before real-vault UI integration.

## Native configuration policy increment

### 1. Scope / trigger

After frozen G2, copy the pinned source to a fresh policy build. Never mutate
the G1 baseline or claim this increment completes the production broker.
The first increment covers native parser restrictions and missing-provider
entry/trust rejection; managed paths, approved contexts, shell quoting and
per-hop capabilities remain mandatory before product integration.

### 2. Signatures

`ctos_ssh_policy_options(const Options *, const char *object, int line)` checks
actual upstream option values after successful parsing. Rejection records only
fixed reason, numeric synthetic object ID and line. `Match exec` is rejected
inside its original parser branch before expansion, irrespective of activep.
`ctos_ssh_policy_entry()` and `ctos_ssh_policy_trust()` reject without a real
provider; no test boolean may make a production key trusted.

### 3. Contracts

Preserve original Host/Match, first-value and additive semantics. Nonexecuting
inactive directives and overwritten values do not become effective policy
violations; Match exec/!exec/exec= always rejects, including NEVERMATCH.
Use original option_clear_or_none for command/provider neutral strings and
strict parse_jump validation for configuration as well as CLI. Reject effective
strict no/off/false/accept-new, DNS yes/ask and localhost bypass. DNS ask is an
unsupported capability, not the same automatic-accept behavior as DNS yes.
Every policy rejection is sticky until the synthetic evaluation is reset.

### 4. Validation and errors

Require actual parser positives and negatives for none/no, case, comments,
first values, inactive/final/NEVERMATCH and strict jump strings. Observe native
fork/access/file/network calls with fail-closed test wrappers; a rejected
Match must never reach execution or expansion. Native entry and verify_host_key
must reject before their original file/key/cache/DNS operations.

### 5. Good / base / bad

Good: safe first value shadows a later dangerous value exactly as upstream;
Match exec "none" rejects. Base: pinned patch builds but device untested.
Bad: simplified config parser, ignoring inactive Match exec, trusting arbitrary
ProxyCommand or treating a parser-only result as full path/provider approval.

### 6. Tests required

Compile the real patched readconf/ssh/sshconnect objects with pinned baseline
libraries; compile owned C with strict warnings. Retain original source hashes,
patch, fresh output and ELF provenance. Device evidence needs an independently
packaged explicit method, numeric bounded records and exact cleanup under the
current authorized synthetic scope. No ordinary plaintext payload files.

### 7. Wrong vs correct

Wrong: inspect only activep before execute_in_shell or mark jump_host nonnull
as a trusted shell command. Correct: refuse execution in the recognized Match
branch before expansion; validate configuration jumps with original strict
parser, then require separate managed-chain binding and safe generated argv.

### Next increment: real Include closure

Actual API30/4KiB/Permissive evidence: 117 cases/1 test/0.189s,
fresh UID10179, fixtures removed and production APK preserved.
The two earlier expanded runs failed and remain recorded. Prepare OpenSSL with
OPENSSL_INIT_NO_LOAD_CONFIG before either public parser entry: Include expands
connection hashes, so lazy crypto initialization can otherwise attempt loading
an external configuration. Keep the deny observer; do not mask an extra access
by accepting it or setting OPENSSL_CONF to a plaintext pathname.

Exercise read_config_file_depth with locked anonymous synthetic config objects
and original compat glob using in-memory directory callbacks. Do not delegate
unknown paths to the ordinary filesystem. Check expanded Include patterns
before glob, then actual returned object paths before fopen; reject escaping,
other-user home, proc-FD paths, missing members and depth over eight. Keep
user Include relative to ~/.ssh; no including-file-directory substitution.
Return a bounded validation failure after closing streams instead of terminating
the validation worker inside readconf. Production path/provider is still absent
and must reject; synthetic fixed object IDs do not authorize real files.

### Managed ProxyJump formatting increment

Use a native helper from the real ssh.c ProxyJump generator. Quote each program,
user, jump chain, config and destination argument, and double literal percent
characters for OpenSSH's later single percent_expand pass. Build -W from the
already resolved target and port, rather than placing an unquoted replacement
inside shell syntax. Never fall back to a system ssh executable. Formatting
does not create a trusted role: missing bootstrap/provider still rejects.

Test quoted values through actual percent_expand and a fixed owned system
shell builtin, with the expected value provided as a separate exec argv item.
Do not compare two strings produced by the same escaping helper. Bound exact
child/group lifetime, hold ownership through signalling/reap, reject cleanup
failure and keep all child output away from the fixed numeric protocol.

## Public file view bridge (implementation gate, no production session yet)

The synchronous AppSecrets file loan includes closed-graph metadata for private
keys, but their bodies have no representation in the public native view. Encode
explicit wire tags (not enum ordinals), snapshot identity, root, connection and
request bindings, lengths and references. Revalidate in native code: strict
UTF-8, canonical vault paths, unique IDs/paths, exact types/body lengths, full
reachable closure, no cycles and depth <=8. Bound128 objects/1MiB each/8MiB total
declared contents and the complete view to12MiB. Reject trailing bytes.
Native admission and lookup check CLOCK_BOOTTIME against the exact
elapsedRealtime request deadline, including suspend; clock failure denies access.

Copy directly from the bounded JNI byte array into an owned0600 raw memfd,
mapped/locked with MADV_DONTDUMP before copying. Wipe the Java encoding in a
finally block; do not expose native pointers, FDs, bodies or arbitrary filenames
over MethodChannel. One live opaque native token is allowed; stale close must
never affect a later token. No fallback to disk or unlocked memory. Cleanup
wipes and verifies its owned mapping before munlock/unmap, truncates the held
file to zero and closes once. Any cleanup uncertainty poisons new admission.

Register the returned lease against the exact VaultSession epoch before
publication, then repeat the operation's cancellation/snapshot/deadline guard.
Explicit lock, timeout, replacement unlock and direct session lock revoke it.
Session revocation must not call AppSecrets or acquire its publication lock.
Explicit lease close must not acquire the session lock while holding the lease
lock. Revoke only this bootstrap file view: established SSH transport and its
already approved public host-key state remain the separate R8 service owner.
The owning VaultOperation also binds the lease before publication: cancellation
after a successful return revokes that exact view, while an old request cannot
rebind to or revoke a newer operation's view. Drop the operation monitor before
invoking revocation; cancelled-before-bind rollback remains caller-owned.

This public-file bridge alone does not create a trusted native role, register the
production restore validator, or launch SSH. The separate Java role/host/KEX/
credential contract below may release a typed secret only when its caller has
supplied matching proofs; native launcher and protocol integration are still
required before this becomes a production session.

## Java production-facing bridge contract (native integration gate)

### 1. Scope / Trigger

This contract was added after the role, host-key, signed-KEX and typed-secret
implementation increment. It is the Java-side boundary between the public
`AppSecrets` vault and a future owned native OpenSSH launcher. It is not a JNI
or MethodChannel authority, does not parse prompts/terminal output, and does
not itself prove that a native callback came from the real OpenSSH event loop.

### 2. Signatures

- `SshRoleRegistry.issue(SshRoleContext.Bootstrap, PublicView)` issues one
  package-private role for `CLIENT` or `JUMP`; `release(role)` revokes it.
- `SshHostKeyAuthority.requireVerified(role, Evidence, SshTrustProposal)`
  returns a role-bound `Proof` or throws a stable `SSH_HOST_KEY_*` error.
- `SshKexProof.fromNative(role, hostProof, cycle, sessionDigest)` creates the
  proof only for an approved `KNOWN`/reviewed `NEW` host key.
- `SshCredentialBridge.openCredential(role, evidence, proposal, entryId,
  Kind)` and `openPrivateKey(role, evidence, proposal, path, keyIdentity)`
  create a once-used `Request`.
- `SshCredentialBridge.release(request, kexProof, Receiver)` performs the
  final guarded callback and closes/revokes the request in all post-validation
  paths.

### 3. Contracts

- A bootstrap carries `ownerPid`, `hop`, `connectionId`, target/host/port,
  `publicViewId`, 32-byte role nonce, 32-byte connection binding and a
  `CLOCK`-compatible deadline. It contains no argv, shell text or secret body.
- Evidence is role-bound and carries key type, `SHA256:` fingerprint, host-key
  classification, known-hosts base/replacement digests and native proof data.
  A `NEW` key needs an exact immutable proposal/review; `KNOWN` cannot rewrite
  known-hosts; changed, revoked, certificate and invalid classifications deny.
- A KEX proof binds the same role, host proof, positive cycle and non-zero
  32-byte session digest. It is a readiness proof, not a cached credential.
- Credential `Kind` is one of `PASSWORD`, `KEY_PASSPHRASE`,
  `KEYBOARD_INTERACTIVE` or `PRIVATE_KEY`; the vault manifest type is checked
  before any receiver callback. Requests bind role, target, connection and
  the originating vault operation.
- `release` repeats role deadline, public-view, host proof, KEX proof,
  operation binding and cancellation checks immediately before vault use.
  Missing KEX, stale role, wrong hop or closed operation must never reach the
  receiver; the request is then closed and its operation revoked.

### 4. Validation & Error Matrix

| Condition | Required result |
| --- | --- |
| Duplicate owner/hop, nonce or same-hop binding | `SSH_ROLE_REPLAY`; no second role |
| More than eight live hops | `SSH_ROLE_BUSY` |
| Missing/expired/closed role or revoked public view | `SSH_ROLE_REQUIRED`, `SSH_ROLE_EXPIRED` or `SSH_FILE_VIEW` |
| NEW key without exact role/target/digest review | `SSH_HOST_KEY_REVIEW` |
| Changed/revoked/certificate/invalid host evidence | `SSH_HOST_KEY_CHANGED`, `SSH_HOST_KEY_REVOKED`, `SSH_HOST_KEY_CERTIFICATE` or `SSH_HOST_KEY_INVALID` |
| Missing or cross-hop KEX proof | `SSH_KEX_REQUIRED` or `SSH_HOST_KEY_CHANGED` |
| Missing receiver, invalid request or manifest-kind mismatch | `PARAMETERS` or the existing vault operation error |
| Failure after opening a request | close the request and revoke its exact operation; do not rebind it |

### 5. Good / Base / Bad Cases

- Good: a distinct hop on one connection receives a distinct binding, a
  known/reviewed host proof and a matching signed KEX+NEWKEYS proof; only its
  typed vault entry reaches the receiver.
- Base: a request is opened and then cancelled, locked, expired or released
  without KEX; the receiver is untouched and the operation is revoked.
- Bad: a UI boolean, prompt text, control frame, cached host fingerprint or
  same connection ID is used to release a secret; reject it and keep the
  public/native provider gates closed.

### 6. Tests Required

- `SshProductionBridgeTest`: replay and distinct-hop binding, all host-key
  classifications, exact NEW-key review, kind mismatch, missing-KEX closure,
  successful typed password/private-key release, expiry and cross-hop proof.
- `SshRoleRegistry` regression: distinct hops on one connection must be
  allowed while same-hop binding/nonce reuse is rejected.
- `:app:testReleaseUnitTest`, `lintRelease`, and the host native view/control
  tests must pass before changing native provider or launcher gates. These are
  local contract checks and do not substitute for device or SSH endpoint AC.

### 7. Wrong vs Correct

#### Wrong

```java
if (uiConfirmed || prompt.contains("password")) {
    receiver.accept(secret);
}
```

#### Correct

```java
bridge.release(request, signedKexAndNewkeysProof, receiver);
```

The correct call is still allowed only through the package-private bridge and
its repeated role/host/operation guards; native production wiring must supply
the proof from the real event loop.

## Native role bootstrap seam (bounded registry; KEX fixture integration)

The role registry is bounded and internal, not a production launcher. Its KEX
integration is compiled only by the opt-in KEX fixture and by the host-only
`testSshRoleBootstrapHost` task; the normal APK does not package the KEX probe
or its OpenSSH objects. The same registry implementation is now compiled into
the normal `libctos_ssh_files.so` only to support the production JNI
ownership/open seam described below. The C API is bounded and internal:

- `ctos_ssh_role_bootstrap_open(request, &token)` accepts only `CLIENT` or
  `JUMP`, the calling process's exact PID/UID, hop `< 8`, valid port, non-zero
  32-byte nonce/binding, restricted identifiers and a BOOTTIME deadline within
  60 seconds. Same owner PID/hop, nonce, or same-hop binding is replay; a
  distinct hop on the same connection is allowed.
- `ctos_ssh_role_bootstrap_snapshot/active/close(token)` require the token's
  owner PID/UID to still match the caller. Expiry, close, or revoked public
  view makes the record unavailable and wipes its bounded snapshot.
- `ctos_ssh_role_bootstrap_encode(token, output, capacity, &length)` emits a
  versioned `CSRB` record of role metadata only. It never accepts argv,
  command text, paths, descriptors, terminal output or secret/file bodies.
- Admission rereads `CLOCK_BOOTTIME` after taking the role mutex; that sample
  validates the deadline and drives expired-slot reclamation before duplicate
  and capacity checks.
- `SshNativeRoleBootstrap.issue(wire, registry, view, clock, NativeOwnership)`
  rejects malformed/trailing/non-ASCII/expired records, and has no default
  ownership callback. The callback must bind the exact native token/UID and
  metadata before the Java registry issues an existing role; the returned
  `Issued` object retains token, UID, and its creating registry for later native
  lifecycle binding. Release validates that exact live Java role before native
  cleanup; cleanup/linkage/unchecked callback failure leaves the Java role
  registered, and only a successful native close deregisters it.

The host task currently reports `bootstrap1198/cleanup1/gate1198` after the
stale-output, post-lock-clock, and stale-token regression checks. The KEX runner
links the role object into the existing fixture library, but this remains
synthetic native evidence: no OpenSSH launcher, real OpenSSH callback, device
execution or external endpoint is implied.

## Production JNI role ownership/open seam

`ssh_role_jni.c` is compiled into the normal `libctos_ssh_files.so` alongside
the public-file view. Its JNI `open` obtains the current native PID/UID itself,
accepts only the bounded role metadata, and returns the canonical `CSRB` wire;
it never accepts argv, shell text, paths, descriptors, file bodies or secrets.
Its JNI `owns` requires the current UID, a live token owned by the current
process, and byte-for-byte equality with a fresh native canonical re-encoding.
Malformed, oversized, expired, mismatched, or unavailable records fail closed.

`SshNativeRoleBootstrap.productionOwnership()` is the only production Java
ownership factory. `open(Bootstrap)` is a typed metadata wrapper and does not
start a process or an SSH connection. The decoded wire is retained as an
immutable copy for the native proof; no default/test callback is substituted in
production. `SshNativeRoleBootstrap.productionRelease()` is the only
production release callback: JNI accepts only the issued token and bound UID,
then the native registry additionally requires the current process's live
token. `release(issued, registry, callback)` validates the exact live Java role,
calls native cleanup first, and deregisters the Java role only after a
successful native close; `IOException`, linkage, or unchecked callback failure
leaves the Java role registered for retry. The release seam is not yet called
by a production event loop and does not implement the launcher, host-key
callback, protocol authentication, PTY/forward/service, or UI.

### Native role lifecycle release contract

#### 1. Scope / Trigger

This contract applies when an already-issued role is explicitly torn down. It
is a lifecycle seam for a future owned event loop, not a launcher, process
supervisor, or proof that an OpenSSH connection exists.

#### 2. Signatures

- JNI: `Native.release(long token, int ownerUid) throws IOException`.
- Java test/production seam: `NativeRelease.release(long token, int ownerUid)`.
- Java lifecycle: `SshNativeRoleBootstrap.release(Issued, SshRoleRegistry,
  NativeRelease) throws VaultException`.
- `Issued` retains the opaque token, bound UID, creating registry, and Java
  role; callers cannot replace these with an argv, pathname, descriptor, or
  secret.

#### 3. Contracts

The JNI boundary requires a positive token and a supplied UID equal to the
current process UID. The native registry then looks up the live token owned by
the current PID and UID; tokens are monotonic and stale close cannot affect a
later role. Java validates the exact `Issued`/registry pair and an active role,
calls native cleanup first, and deregisters the Java role only after native
close succeeds. `IOException`, linkage, or unchecked callback failure leaves
the Java role registered so the owner can retry; no permissive fallback is
allowed.

#### 4. Validation & Error Matrix

| Condition | Required result |
| --- | --- |
| Non-positive token, negative UID, null record/registry/callback | `SSH_ROLE_REQUIRED`; native callback is not called |
| Issued role belongs to another registry or is inactive | `SSH_ROLE_REQUIRED`; both native and Java role remain unchanged |
| UID mismatch, wrong process, stale/expired token, or missing native slot | Native `IOException`; Java role remains registered |
| Successful native close | Java registry releases exactly that role |
| Repeated release after successful Java close | `SSH_ROLE_REQUIRED`; no second native call |

#### 5. Good / Base / Bad Cases

- Good: the event-loop owner calls `release(issued, registry,
  productionRelease())`, native close succeeds, then the matching Java role is
  deregistered.
- Base: native cleanup is temporarily unavailable; the Java role stays live
  and the caller records/retries the bounded failure.
- Bad: a UI boolean, arbitrary token/UID pair, stale callback, or different
  registry is allowed to close or deregister a role.

#### 6. Tests Required

- Host role test: assert stale-token, cross-process, UID and rejected-output
  behavior, with strict `-Wall -Wextra -Werror` compilation.
- `SshNativeRoleBootstrapTest`: assert native-first ordering, exact registry
  binding, malformed input, IOException/linkage/runtime failure retention,
  retry, and post-success inactive role.
- Release build: assert `open`, `owns`, and `release` JNI exports and that the
  normal APK contains only the production SSH library, not fixture probes.

#### 7. Wrong vs Correct

Wrong:

```java
registry.release(issued.role); // native ownership may remain live
```

Correct:

```java
SshNativeRoleBootstrap.release(issued, registry,
        SshNativeRoleBootstrap.productionRelease());
```

The correct call still does not prove launcher/event-loop ownership or a live
SSH protocol; those gates remain separate.

### Typed public FILE access

Resolve only the fixed virtual home `/ctos-vault` into canonical vault paths;
reject file/directory prefix collisions. Scope original compat glob callbacks to
this closed metadata graph, with no ordinary stat/opendir fallback. Check the
expanded pattern before glob and each actual match's CONFIG type before reading.
Private metadata may support stat, but cannot produce a readable FILE.

For each required public read create a fresh0600/CLOEXEC memfd and lock its
true-length mapping before copying. Empty files have no payload mapping. FILE
offsets are independent and stdio buffering is disabled. Close verifies the
wipe, confirms unmap before truncating, and makes held descriptors EOF. Uncertain
cleanup keeps ownership and blocks further reads; teardown never masks it.
Close every reader before destroying its parent view.

Bound one validation evaluation to128 simultaneous readers,1024 open attempts,
8MiB held reader payloads and64MiB cumulative copied reads. Exceeding these
work budgets rejects the complete evaluation rather than truncating Include
semantics. Keep this budget separate from the128-object/8MiB source manifest.
These parser adapters currently run only in the opt-in policy fixture; production
main/trust entry guards remain closed and no restore provider is registered.

## Native control transport increment (not a host-approval authority)

Use an owned AF_UNIX SOCK_SEQPACKET socketpair with CLOEXEC/NONBLOCK and
SO_PASSCRED enabled on both ends before fork. SO_PEERCRED on a pre-fork pair
reports credentials captured at pair creation; authenticate each received
packet's SCM_CREDENTIALS against the launcher's exact owned PID and App UID.
See [Linux unix(7)](https://man7.org/linux/man-pages/man7/unix.7.html).

Bound the binary frame to88 header bytes plus32768 payload bytes: fixed magic,
version, allowlisted kind, zero flags, exact length, positive monotonic sequence,
32-byte issued role nonce and32-byte connection binding. Reject replay, gaps,
wrong peer/binding, truncation, extra or missing ancillary messages and unexpected
FDs. Only the LOAN kind may carry one readonly, CLOEXEC,0600 anonymous regular
descriptor with exact declared length and purpose-specific bounds. The owned
blob registry and later parser must separately prove provenance and contents;
file shape is not a secret-release grant.

Use recvmsg MSG_CMSG_CLOEXEC; close every actually received descriptor on every
rejection, never a number parsed from payload. Fail and close the channel on
protocol/peer failure. No log may contain frame bytes, role nonce, body hash,
host-key blob, request metadata or exception text. Poll/IO retry work uses a
finite CLOCK_BOOTTIME deadline and independent attempt cap. Forked native tests
own and reap their exact children; no JVM fork or external endpoints.

This transport does not classify host keys or authorize saved credentials.
Native production role issuance, real KEX completion wiring, each-hop host
trust callback, final AppSecrets guards and bounded private FD handoff remain
subsequent mandatory steps. The Java typed-request contract exists, but it is
not a native provider and must never be bypassed from terminal output or a UI
boolean.

## Real KEX completion guard increment

`ssh_kex_guard` binds up to eight live client `struct ssh` objects to an owned
native PID, distinct issued role nonce, connection binding, hop index, expected
SHA256 public host-key fingerprint and a bounded BOOTTIME admission deadline.
Only the native event-loop launcher may issue these records; there is no JNI,
MethodChannel, terminal-output or control-frame setter. The current fixture
issues synthetic known keys. Java host classification/review and typed release
contracts now exist, but native product host issuance, callback wiring,
certificate/revocation provider integration and broker role issuance remain
unimplemented.

Patch the pinned original `kex.c`, `kexgen.c` and `kexgexc.c` in a fresh tree:
begin invalidates the previous completion; host approval records the exact
expected key; the signed hook follows a successful real `sshkey_verify` in
both generic and DH-GEX client paths; completion runs only after the actual
incoming NEWKEYS packet was parsed and inbound keys installed. Require the
same approved/signed key, valid session ID and current cycle. Missing context,
wrong process/key/phase, nonce/connection/hop mismatch and expiry deny readiness.
Client- and server-initiated rekey must repeat signed KEX; a cached public host
key does not require vault unlock. Credential readiness still expires and is
only a prerequisite for the future final AppSecrets effect guard.

The control fixture opt-in also packages the separately generated KEX executable
and source notices; neither belongs to the normal APK. Execute only its fixed
`--synthetic-kex` mode with generated in-memory keys and paired real in-memory
packet streams, no network or ordinary payload files. The actual DH-GEX transcript
uses an upstream built-in group through a test-only group selector, not a host
moduli file. Validate EXT_INFO with the real handler; the upstream test API
expects complete identification lines, so fragmentation starts at binary KEX.
Require exact numeric handshake/cleanup/gate records and native exit agreement.
These results do not prove user authentication, private release, PTY, forwarding,
foreground service or a production HostKeyGate.

## G3.2 same-process KEX-only launcher contract (fixture-only)

This is an executable contract for the current narrow increment, not a
production launcher contract. It applies only to the opt-in
`ctosSshControlProbe=true` fixture and its host/KEX build. It must not be
enabled by the normal APK, JNI production library, Java event loop, device
instrumentation, external SSH endpoint, vault provider or user credential.

### 1. Scope / trigger

The fixture trigger is the fixed `--synthetic-kex` test mode in the separately
generated KEX artifact. The only admission entry point is a same-process call
before the fixture KEX loop:

```c
int ctos_ssh_native_launcher_admit(const unsigned char *wire,
    size_t wire_length, ctos_ssh_role_token token, struct ssh *ssh,
    const unsigned char expected_host[32]);
int ctos_ssh_native_launcher_close(struct ssh *ssh,
    ctos_ssh_role_token token);
```

It accepts no `argc/argv`, shell or command text, executable/config pathname,
ordinary file, descriptor, network endpoint, callback transport or secret. The
`struct ssh` is an already-created client KEX fixture object; the launcher does
not call stock `main`, `getopt`, `closefrom`, config parsing, file access,
`exec`, userauth, PTY, forwarding or remote service code.

### 2. Signatures

`wire` is a bounded versioned `CSRB` byte string emitted by
`ctos_ssh_role_bootstrap_encode(token, ...)`; the accepted maximum is
`CTOS_SSH_ROLE_WIRE_BYTES` (2048). `token` is a positive live native role
token owned by the calling PID/UID. `ssh` must be non-null, client-side, have a
client KEX object and a non-null host-key callback. `expected_host` is exactly
32 non-zero bytes containing the launcher-owned expected SHA-256 host-key
fingerprint. No caller-supplied host-key classification or credential body is
accepted.

### 3. Contracts

Admission takes a fresh native role snapshot and rejects a missing, closed,
stale or expired role. The snapshot must still match the current `getpid()` and
`getuid()`, an allowed role program/hop/port and restricted metadata; its
`CLOCK_BOOTTIME` deadline must be strictly in the future. The launcher then
re-encodes the live token and requires both exact byte equality and exact length
equality. Therefore malformed bytes, non-canonical bytes and every trailing
byte are rejected before KEX binding.

The launcher rejects a duplicate `struct ssh` or token and binds at most eight
live fixture objects. Only after all checks pass does it call
`ctos_ssh_kex_bind_role`; the patched KEX sequence must remain host approval,
real signature verification, inbound NEWKEYS installation, then completion.
Generic, DH-GEX and rekey checks remain guarded by the same role/hop/nonce,
connection-binding, host-fingerprint and deadline state.

Close requires the exact `(ssh, token)` pair. A mismatched pair has no effect;
a repeated close after successful teardown has no effect. Successful teardown
is native-first: close the native role, then unbind the KEX object and wipe the
launcher slot. If native role close fails, return failure and retain the KEX
binding/slot as a poisoned retry boundary; never unbind or report Java
deregistration success on that path. Admission wipes its local snapshot,
canonical wire, supplied wire and fingerprint copies on every exit; successful
close wipes the slot.

### 4. Validation and error matrix

| Condition | Required fixture result |
| --- | --- |
| Null/zero/oversized wire, token, SSH object or fingerprint | Reject before KEX bind |
| Server KEX object or missing host callback | Reject before KEX bind |
| Missing/closed/stale role, wrong current PID/UID, invalid role metadata or expired deadline | Reject; no slot or KEX binding |
| Canonical length mismatch, malformed CSRB or any trailing byte | Reject; no partial prefix acceptance |
| Existing token/SSH binding or launcher capacity exhausted | Reject; existing bindings unchanged |
| Wrong host/hop/nonce/connection binding or KEX phase | KEX guard rejects; no completion proof |
| Mismatched close | Reject with binding retained |
| Duplicate close after successful close | Reject with no second native/KEX action |
| Native role close failure | Return failure and retain/poison native-first binding |
| Clock/read/packet/signature/NEWKEYS failure | Fail closed; no credential, userauth or service effect |

### 5. Good / base / bad cases

Good is a live canonical CSRB plus a client KEX object completing the fixed
synthetic loop, including fragmented binary packets, generic and DH-GEX paths,
rekey, exact host-key approval/signature ordering and successful native-first
close. Base is a strict source/build/host result with the ARM artifact not
executed. Bad is accepting a prefix with trailing bytes, using a stale or
expired snapshot, allowing a duplicate/mismatched close to affect another
role, unbinding before native close, retaining local copies, or treating this
fixture as a production trust/credential/client result.

### 6. Tests required

The fixture test must cover malformed, trailing, wrong-wire, duplicate,
expired/stale, missing-role, wrong-host, rejected-host, signature failure,
pre-NEWKEYS, generic/DH-GEX, rekey, two-hop isolation, mismatched close and
duplicate close. Its output is fixed allowlisted numeric protocol records and
must agree with native exit status. The runner must use the pinned OpenSSH
inputs, verify the complete before/after baseline manifest, compile launcher
and test C with `-Wall -Wextra -Werror`, and keep the artifact in the opt-in
fixture output directory. Host execution is allowed only for the synthetic
test; the cross-built ARM artifact is not executed.

The normal `:app:testReleaseUnitTest`, `:app:lintRelease` and
`:app:assembleRelease` checks must pass after fixture output exists. Inspect the
normal APK ZIP and require only intentional production libraries; no KEX,
control, policy, upstream, notice or stale generated fixture output may enter
it. No device, external account/endpoint, vault mutation, installation or
production event-loop result is a pass condition for this contract.

### 7. Wrong vs correct

Wrong:

```c
memcmp(wire, canonical, canonical_length) == 0; /* accepts trailing bytes */
ctos_ssh_kex_unbind(ssh);                         /* before native close */
```

Correct:

```c
if (wire_length != canonical_length ||
    memcmp(wire, canonical, canonical_length) != 0)
	return reject(EPROTO);
if (ctos_ssh_role_bootstrap_close(token) != 0)
	return reject(errno); /* keep slot and KEX binding for retry/poison */
ctos_ssh_kex_unbind(ssh);
```

The correct fixture path still proves only a same-process KEX admission and
teardown seam. It does not authorize production OpenSSH, host-key trust CAS,
typed credentials, PTY, forwarding, service lifecycle, Java callback transport,
device execution or the parent task's complete client acceptance criteria.

### Post-G3.2 production integration gate

After this fixture gate passes, no new production launcher, event loop, or
OpenSSH object may be added to the normal APK until the task design records
process topology/ownership, native-to-Java callback transport, and the managed
host-trust/file/private-key provider. The first two are now recorded as
same-process ownership and attached synchronous calls on the event-loop thread;
the bounded managed-provider handoff is recorded, and the managed AppSecrets
known_hosts CAS writer is now implemented; the native-facing public
config/known-hosts issuer/handoff and real caller remain open and block real
protocol integration. The current PID-bound role token does not cross
`fork()` by assumption, and the
fixture `ssh_api` memory buffers and `ssh_control.[ch]` framing are not
production transports.

Until those decisions and their contracts exist, the only architecture-neutral
follow-up is evidence maintenance: rerun the fixture, strict native build,
normal APK exclusion, and source/hash checks. Do not relabel that evidence as
production event-loop, callback, trust-provider, device, or external-SSH
validation. The decision matrix and required evidence live in the task's
`research/next-production-launcher-20261013.md`.

## Synchronous attached JNI callback adapter and managed-provider handoff contract (production seam; no OpenSSH caller)

This contract records the user-confirmed callback choice. The adapter is
compiled into the normal `libctos_ssh_files.so`, but no real OpenSSH event loop
currently calls it. The bounded Java managed-provider seam now supplies an
exact role/hop-bound NEW-host proposal and a typed, caller-owned credential
byte handoff; the managed AppSecrets known_hosts CAS writer is implemented,
but its native-facing public config/known-hosts issuer/handoff and the real
caller remain open. This section makes that boundary explicit without
promoting fixture control framing or memory buffers to a product transport.

### 1. Scope / trigger

The trigger is binding an already-issued `SshNativeRoleBootstrap.Issued` role to
the same-process native event-loop thread. `SshNativeCallbackAdapter.bind`
captures the calling thread and the JNI environment supplied by production
JNI. The callback is synchronous: native waits for the Java method result on
that same thread. Cancellation may be requested by another owner thread, but
close and all callbacks remain event-loop-thread operations.

### 2. Signatures

Native C entry points:

```c
int ctos_ssh_callback_open(const struct ctos_ssh_callback_target *target,
    ctos_ssh_role_token role, int owner_uid, uint64_t deadline,
    void *environment, ctos_ssh_callback_token *callback);
int ctos_ssh_callback_cancel(ctos_ssh_callback_token callback);
int ctos_ssh_callback_close(ctos_ssh_callback_token callback, void *environment);
int ctos_ssh_callback_host_key(ctos_ssh_callback_token callback, void *environment,
    int classification, const char *key_type, const char *fingerprint,
    const unsigned char base_digest[32],
    const unsigned char replacement_digest[32]);
int ctos_ssh_callback_kex_complete(ctos_ssh_callback_token callback,
    void *environment, uint64_t cycle, const unsigned char session_digest[32]);
int ctos_ssh_callback_credential(ctos_ssh_callback_token callback,
    void *environment, int kind);
int ctos_ssh_callback_credential_bytes(ctos_ssh_callback_token callback,
    void *environment, int kind, unsigned char *output, size_t capacity,
    size_t *length);
```

Java/JNI entry points are `Native.open(target, role, ownerUid, deadline)`,
`Native.cancel(callback)` and `Native.close(callback)`, plus the synchronous
package-private callback methods:

- `onNativeHostKey(int, String, String, byte[32], byte[32]) -> boolean`;
- `onNativeKexComplete(long, byte[32]) -> boolean`;
- `onNativeCredential(int) -> boolean` (legacy availability callback);
- `onNativeCredentialBytes(int) -> byte[]` (bounded typed handoff).

The Java seam is `SshManagedProvider`: its trust source must return a
role/hop-bound `SshTrustProposal`, and its credential source must open the
proof-bound `SshCredentialBridge` request matching the native `Kind`.

Host classification tags are stable `KNOWN=1`, `NEW=2`, `CHANGED=3`,
`REVOKED=4`, `CERTIFICATE=5`, `INVALID=6`; credential tags are stable
`PASSWORD=1`, `KEY_PASSPHRASE=2`, `KEYBOARD_INTERACTIVE=3`,
`PRIVATE_KEY=4`. They are explicit wire values, not Java enum ordinals.

### 3. Contracts

- `open` accepts only the live role token, current UID, strictly future
  BOOTTIME deadline and an attached `JNIEnv` from the same `JavaVM`. It accepts
  no argv, shell, pathname, descriptor, prompt, terminal output or secret
  body. The native table is bounded to eight live callback bindings.
- Each callback requires the exact event-loop `pthread`, the original attached
  environment, a live same-PID/UID role and a non-expired deadline. A callback
  is marked in-flight before releasing the table lock; re-entry is rejected and
  poisons the binding. Native cancellation, close, late role expiry and Java
  failure cannot be converted into success after the Java method returns.
- Host evidence is passed to `SshHostKeyAuthority`. `KNOWN` can produce a
  proof without a proposal; `NEW` needs an exact role/target/hop/fingerprint/
  base-digest/replacement-digest review. The managed provider creates the
  proposal from an exact role-bound source, the authority compares it, and the
  adapter closes it after the synchronous callback. Production JNI supplies no
  proposal over the wire; an absent provider or an unbound/wrong-hop proposal
  remains `SSH_HOST_KEY_REVIEW`. Changed, revoked, certificate and invalid
  classifications remain denied.
- KEX completion requires the prior host proof and a positive cycle with a
  non-zero 32-byte session digest. It produces `SshKexProof` only after the
  native caller has asserted signed KEX and inbound `NEWKEYS`; the adapter
  itself does not parse the SSH packet stream.
- The legacy boolean credential callback remains provider-less. The bounded
  byte callback requires the matching host and KEX proofs, then asks the
  managed provider for one matching `SshCredentialBridge.Request`. The bridge
  repeats role, host, KEX, vault-operation and cancellation guards immediately
  before use; a request-kind mismatch fails closed. Java returns at most
  `VaultManifest.MAX_FILE_BYTES`; JNI copies into caller-owned storage and
  wipes the Java array, while native wipes the caller buffer on failure. No
  prompt, UI, log, ordinary disk staging or cached secret is introduced.
- The native target's optional `destroy` runs only after its callback slot is
  removed. `release` leaves its context valid for a cleanup retry, so a
  post-release mutex/finalization failure cannot leave a dangling JNI context.
- `close` releases the native callback binding before releasing the Java role.
  A native or role cleanup failure leaves the live binding/role for an explicit
  retry; Java deregistration is never reported before native cleanup succeeds.
  Successful cleanup wipes callback proof state and rejects late callbacks.

### 3a. Dedicated one-shot known_hosts candidate handoff

`HostEvidence` and its `CSHR` result remain metadata-only. A future pinned
OpenSSH hostfile issuer uses this separate synchronous entry point to transfer
one exact replacement body; the body is never added to the existing evidence
wire:

```c
int ctos_ssh_callback_known_hosts_candidate(
    ctos_ssh_callback_token callback, void *environment,
    const char *candidate_id, int classification,
    const char *key_type, const char *fingerprint,
    const unsigned char base_digest[32],
    const unsigned char replacement_digest[32],
    unsigned char *replacement_body, size_t replacement_body_length);
```

```java
boolean onNativeKnownHostsCandidate(long nativeRoleToken, int nativeHop,
    int nativePort, String nativeConnectionId, String nativeTargetIdentity,
    String nativeHost, int classification, String keyType, String fingerprint,
    byte[] baseDigest, byte[] replacementDigest, String candidateId,
    byte[] replacementBody);
```

The C handoff accepts only `NEW`, a non-empty body of at most
`CTOS_SSH_CALLBACK_MAX_FILE_BYTES`/`VaultManifest.MAX_FILE_BYTES`, a candidate
identity matching `[A-Za-z0-9_-]{1,128}`, valid key/fingerprint fields, and
non-zero 32-byte digests. Before Java is called it re-reads the live role, so
the candidate carries the exact role token, hop, port, connection, target,
host, key type, fingerprint, base digest and replacement digest. The native
callback slot claims a one-shot bit before invoking Java; duplicate or
re-entrant calls are rejected and poison the slot.

`SshKnownHostsCandidateHandoff` is the explicit managed receiver. It accepts
one `Evidence` plus the issuer candidate ID and body, requires the same
provider-owned `SnapshotReceipt`, verifies the body digest against the
replacement digest, and installs a private provider clone only after all
checks pass. `SshManagedProvider.ownsNativeCandidateProvider` requires that
receiver, proposal source and CAS writer are the same concrete
`SshManagedKnownHostsProvider`; an arbitrary provider, path, FD or Java object
cannot be retained by the native seam. The receiver is consumed on the first
attempt (success or failure), and `cancel`, `close`, expiry, duplicate, wrong
role/hop/evidence, empty/oversize/mismatched body or digest all fail closed.

The call is synchronous. Native wipes its bounded temporary/body on success
and every failure; JNI wipes the managed `byte[]` before releasing it; the
managed receiver wipes its input array in a `finally` block. For an over-limit
length, native wipes only the bounded maximum prefix because the API has no
unchecked capacity argument; an issuer must keep any bytes beyond that bound
in its own bounded scratch and wipe them before retrying. No payload, digest,
path or candidate body is logged.

This handoff acknowledges candidate installation only. It never returns
`COMMITTED`: the existing metadata-only HostEvidence path must still build the
exact proposal and complete the guarded `AppSecrets` update, and the managed
writer constructs `CasResult.COMMITTED` only after that update succeeds. A
failed or uncertain CAS closes the provider and returns no committed result.
Until a pinned hostfile issuer and its source/object manifest are linked into
the production caller, `ssh_native_client` remains fail-closed with `ENOTSUP`;
fixture OpenSSH objects stay out of the normal APK.

### 3b. Pinned hostfile issuer seam (fixture/host-only until caller closure)

This is the reviewed boundary for `ssh_hostfile_issuer.[ch]`. It is a
compile/link-tested issuer seam, not permission to add OpenSSH objects to the
normal APK or to claim a production SSH caller.

#### 1. Scope / trigger

Use it only when a pinned OpenSSH 10.6p1 hostfile parser must consume a
validated managed public view and produce an exact candidate for the dedicated
one-shot handoff. The source/object closure is built by
`research/android-hostfile-issuer-build.sh`; its output is opt-in fixture
material and must remain outside `compileSshFiles`.

#### 2. Signatures

```c
int ctos_ssh_hostfile_issue(const struct ctos_ssh_view *view,
    ctos_ssh_role_token role, const char *known_hosts_path, const char *host,
    const struct sshkey *host_key, const char *candidate_id,
    FILE *replacement_stream, unsigned char *replacement_body,
    size_t replacement_capacity, ctos_ssh_callback_token callback,
    void *environment, struct ctos_ssh_hostfile_issue *issue);
```

The pinned source-owned stream hook is
`ctos_hostfile_write_entry(FILE *, const char *, const char *, const struct
sshkey *, int)`. It wraps the upstream formatter only; it is not a pathname
writer.

#### 3. Contracts

- The view must be live, role/deadline/connection/host bound, rooted at the
  `CTOS_SSH_CONFIG` object `ssh/config`, and that root must explicitly refer to
  the `CTOS_SSH_KNOWN_HOSTS` object `ssh/known_hosts`. The issuer selects the
  object only through `ctos_ssh_view_lookup`; it never opens a vault path.
- Parsing uses pinned `load_hostkeys_file()` over a caller-owned memory stream;
  writing uses the exact upstream formatter through the project-owned stream
  hook. `add_host_to_hostfile`, `hostfile_replace_entries` and
  `hostfile_create_user_ssh_dir` are forbidden from the issuer.
- Non-certificate keys map upstream `HOST_OK/NEW/CHANGED/REVOKED` to stable
  `KNOWN/NEW/CHANGED/REVOKED`. Certificates remain project-policy
  `CERTIFICATE` (fail-closed), but `check_key_in_hostkeys()` must run first so
  a revoked certificate or revoked CA maps to `REVOKED` and cannot be hidden by
  the generic certificate denial.
- `NEW` appends one upstream-formatted entry to a copy of the exact public
  body, computes base/replacement SHA-256, and calls the existing one-shot
  candidate handoff. The handoff owns wiping after the synchronous call;
  issuer failure wipes its bounded scratch and returns no issue.
- The runner pins the approved G1 archive/configuration/object hashes, the
  stream-writer patch, required PKCS#11/SK closure objects and complete input
  manifest. It must show an AArch64 PIE link and must not wire the fixture into
  the normal APK.

#### 4. Validation & error matrix

| Condition | Required behavior |
| --- | --- |
| View root is not `ssh/config`, wrong type, or does not refer to `ssh/known_hosts` | Reject `EACCES`; do not parse or hand off |
| View/role deadline, connection, host or candidate identity mismatch | Reject; wipe issue/body |
| Managed known_hosts body is malformed | Upstream parser yields no trusted match; classify `NEW` only when the normal upstream result is `HOST_NEW`, otherwise reject parse failures |
| Certificate is not revoked | `CERTIFICATE`; fail closed by managed authority |
| Certificate or its signing CA is revoked | `REVOKED`; never downgrade to `CERTIFICATE` |
| Issuer attempts a pathname writer | Build/source check fails; no vault or host path is opened |
| Candidate callback rejects, cancels or expires | Abort the exact managed candidate, wipe native scratch and return failure; never report `COMMITTED` |
| Pinned manifest, object hash or AArch64 link closure differs | Runner fails before publishing a fixture result; normal APK remains unchanged |

#### 5. Good / Base / Bad cases

- Good: a live rooted public view, exact role metadata and pinned host key are
  classified from an in-memory `FILE *`; `NEW` formatting matches upstream and
  the one-shot receiver accepts the exact body.
- Base: the runner compiles and links the issuer as an AArch64 PIE and records
  `runtime=not-run` when no Android runtime mapping is available; this proves
  the source/object seam, not a live SSH session.
- Bad: use `/etc/passwd` or a vault pathname as parser input, accept a view
  whose root is unrelated to known_hosts, format a line in Java, classify a
  revoked certificate as generic `CERTIFICATE`, or link the issuer into the
  normal APK. All fail closed.

#### 6. Tests required

- `ssh_hostfile_issuer_test.c`: valid root graph, KNOWN/NEW/CHANGED/REVOKED,
  certificate and revoked-CA classification, root/path rejection, exact body
  digest, one-shot handoff wipe and callback-failure rollback.
- `android-hostfile-issuer-build.sh`: `bash -n`, fixed archive/patch/tree and
  object hashes, patched source application, strict C11/Werror compilation,
  AArch64 PIE link, required symbol checks, pathname-writer exclusion and
  normal-APK exclusion. A missing `qemu-aarch64` is recorded as
  `runtime=not-run`, never as a runtime pass.
- The production caller still needs a separate partial-I/O/EAGAIN/EOF,
  deadline/cancel/close-retry, KEX/NEWKEYS ordering and device/external-SSH
  gate before `ssh_native_client` may leave `ENOTSUP`.

#### 7. Wrong vs correct

Wrong:

```c
add_host_to_hostfile(vault_path, host, ip, host_key, 0);
```

Correct:

```c
FILE *memory = fmemopen(public_body, public_length, "r");
load_hostkeys_file(hostkeys, host, synthetic_name, memory, 0);
ctos_hostfile_write_entry(replacement, host, NULL, host_key, 0);
```

The correct path keeps the public view and replacement stream caller-owned,
uses upstream parsing/formatting, binds the root and role before classification,
and hands only the bounded exact body to the existing managed receiver.

### 3c. Pinned caller fixture seam (G3.4; not a production APK contract)

This contract applies only to the opt-in AArch64 source/object fixture that
combines the issuer, callback handoff and real pinned ssh_api packet loop.
It does not authorize adding OpenSSH objects to compileSshFiles, and it
does not clear the production ssh_native_client ENOTSUP gate.

#### 1. Scope / trigger

Use it to prove the caller ordering and ownership seam after the managed
known_hosts issuer/CAS contracts exist. The caller is host/fixture-only,
uses a same-process socketpair, and has no stock Options, pathname, argv,
ssh_connect, fork, exec, userauth, PTY, forwarding or service state. The
fixed runner links the issuer and caller as separate AArch64 PIE test
binaries against the approved OpenSSH 10.6p1 ABI baseline.

#### 2. Signatures

~~~c
int ctos_ssh_pinned_client_open(
    const struct ctos_ssh_pinned_client_config *,
    struct ctos_ssh_pinned_client **);
int ctos_ssh_pinned_client_step(
    struct ctos_ssh_pinned_client *,
    struct ctos_ssh_pinned_client_step *);
int ctos_ssh_pinned_client_complete(
    const struct ctos_ssh_pinned_client *);
int ctos_ssh_pinned_client_close(struct ctos_ssh_pinned_client *);
int ctos_ssh_pinned_client_destroy(struct ctos_ssh_pinned_client *);

int ctos_ssh_callback_known_hosts_candidate_abort(
    ctos_ssh_callback_token, void *environment);
~~~

The config binds a live role, owned SOCK_STREAM FD, future BOOTTIME deadline,
borrowed expected host public key/fingerprint, managed view and virtual
known_hosts selector, host/candidate identity, callback token/environment,
and bounded read/write chunks. The step result reports read/write bytes,
appended/consumed bytes, partial write and read/write would-block states.

#### 3. Contracts

- open revalidates role/PID/UID/deadline/host and view liveness, requires a
  valid owned descriptor and exact ssh/config to ssh/known_hosts view, then
  creates ssh_init, attaches the FD, sets nonblocking mode, binds the KEX
  guard and registers the verifier. Ownership begins only after attachment.
- Each step uses bounded recv(MSG_DONTWAIT) -> ssh_input_append ->
  ssh_packet_next, accepts only the fixture's expected packet boundary,
  and sends at most the configured output chunk with MSG_NOSIGNAL before
  calling ssh_output_consume. EAGAIN is progress-neutral; partial writes
  are observable; EOF, cancellation, clock failure and expired deadline are
  terminal failures.
- The verifier first calls the pinned issuer. Only KNOWN and NEW may continue
  to HostEvidence. The caller must compare the returned result with the
  issuer issue and current role snapshot for role/hop/port/classification/
  outcome/CAS/connection/target/host/key/fingerprint/base/replacement
  binding. CHANGED, REVOKED, CERTIFICATE and INVALID never enter KEX.
- If NEW was handed off and a later evidence, binding or protocol check fails,
  ctos_ssh_callback_known_hosts_candidate_abort releases that exact managed
  candidate. Abort is same-thread/environment restricted and idempotent.
  The temporary fmemopen is closed on both issuer success and failure;
  native issue/result/replacement storage is wiped on every return path. The
  bounded replacement scratch must not be an event-loop stack allocation, and
  the owned socket admission requires `FD_CLOEXEC|O_NONBLOCK|SOCK_STREAM`.
- After inbound NEWKEYS makes kex->done, the caller derives a non-zero
  32-byte session digest and invokes the KEX callback exactly once. close
  unbinds the guard before ssh_free; destroy accepts only a closed tombstone.
- The runner must pin the approved baseline/tree/object hashes plus stream
  writer SHA256
  a786566e1842996c0282647457f4dd63558cde00bc79ae1ba6bcd552bf30484a and
  KEX guard SHA256
  50a59606000dc871f1b29e33eeb6d77fb5da456f40526d2871357758b61f8785.
  It must check no undefined symbols, AArch64 PIE headers, required fixture
  symbols, forbidden stock caller symbols and normal APK exclusion; the
  exclusion check must inspect the formal `compileSshFiles` input closure
  rather than reject the separately guarded opt-in KEX fixture branch in the
  same Gradle file.

#### 4. Validation & error matrix

| Condition | Required result |
| --- | --- |
| Null/malformed config, stale role, wrong PID/UID/host/deadline/view or unowned FD | Reject; output client remains null and caller FD remains open |
| Blocking/non-owned/non-O_RDWR/non-CLOEXEC/non-SOCK_STREAM descriptor | Reject before OpenSSH attach |
| Read/write EAGAIN or bounded partial transfer | Return ordinary step with exact counters; never fabricate full progress |
| EOF, cancellation, clock failure or expired deadline | Terminal failure; later step is refused until close |
| Host classification CHANGED/REVOKED/CERTIFICATE/INVALID | Reject before KEX/callback completion |
| Host result role/hop/port/identity/key/fingerprint/digest/CAS mismatch | Reject; if NEW was handed off, abort exact candidate |
| Accepted NEW candidate followed by evidence/protocol failure | Idempotent candidate abort; no COMMITTED claim |
| KEX callback duplicate or zero session digest | Fail closed; completion is not successful |
| fmemopen/issuer failure | Close stream, wipe bounded scratch, return failure |
| Missing qemu-aarch64 or Android runtime | Record runtime=not-run; never report fixture runtime pass |

#### 5. Good / Base / Bad cases

Good: a real same-process socketpair completes pinned KEX after exact KNOWN
evidence, records a partial write and EAGAIN, and invokes one digest
callback. Base: the fixed runner proves strict compile/link/symbol/source
closure and records SSH_HOSTFILE_CALLER_BUILD gate=pass runtime=not-run
normal_apk=not-wired. Bad: link the fixture into the normal APK, trust only
the fingerprint, ignore the managed result binding, leave a candidate after a
later failure, or leak the temporary FILE on issuer error.

#### 6. Tests required

- The pinned caller test must use a real nonblocking socketpair and cover
  KNOWN success, CHANGED/REVOKED rejection, NEW candidate callback failure,
  NEW evidence failure with rollback, partial read/write, EAGAIN, EOF,
  cancellation, deadline, completion-once and close/destroy tombstone order.
- The callback host test must retain candidate abort coverage and the fixed
  SSH_CALLBACK_HOST gate value; the normal JVM/compile/lint/Release gate
  must still pass with no fixture object in libctos_ssh_files.so.
- The fixed runner must revalidate manifest/patch/object hashes, apply both
  source patches, compile with C11/Werror, link AArch64 PIE/no-undefined and
  preserve evidence when runtime is unavailable. Device, external SSH and
  production event-loop tests remain separate mandatory gates.

#### 7. Wrong vs correct

Wrong:

~~~c
if (fingerprint_matches(host_key))
    return ctos_ssh_kex_approved(ssh, fingerprint); /* no issuer/evidence */
~~~

Correct:

~~~c
issue = pinned_hostfile_issue(view, role, host_key, candidate_id);
result = ctos_ssh_callback_host_evidence(callback, environment, issue, &result);
if (!matches_issue_and_role(issue, result)) {
    if (issue.handed_off)
        ctos_ssh_callback_known_hosts_candidate_abort(callback, environment);
    return reject();
}
~~~

The correct shape is fixture evidence only until the same source/object
closure, production provider, Android runtime and complete protocol lifecycle
are separately approved and wired.

### 4. Validation & error matrix

| Condition | Required result |
| --- | --- |
| Null target, non-live role, wrong UID/PID, invalid deadline/environment | Open rejects; no live binding |
| Callback from another thread or another `JNIEnv`/`JavaVM` | Native `EPERM`; Java `SSH_CALLBACK_THREAD`/fail closed |
| Re-enter callback while one is in flight | Native `EALREADY`; Java `SSH_CALLBACK_REENTRANT`; binding poisoned |
| Cancelled, closing, closed, stale or expired role | Reject; no proof or credential effect |
| `NEW` without exact managed proposal/provider | `SSH_HOST_KEY_REVIEW` |
| `NEW` proposal lacks the exact role/hop binding | `SSH_HOST_KEY_REVIEW` |
| Changed/revoked/certificate/invalid host classification | Stable `SSH_HOST_KEY_CHANGED`/`REVOKED`/`CERTIFICATE`/`INVALID` |
| KEX before host proof or empty/wrong session binding | `SSH_KEX_REQUIRED`; no KEX proof |
| Credential before KEX / after KEX without provider | `SSH_KEX_REQUIRED` / `SSH_CREDENTIAL_PROVIDER` |
| Invalid credential kind, output pointer, length or capacity | Native `EINVAL`/`EOVERFLOW`; length zero and any valid bounded output wiped (an over-limit request only wipes the bounded maximum prefix) |
| Managed provider returns a different typed request | `SSH_CREDENTIAL_PROVIDER`; request closed; no receiver |
| Candidate handoff has wrong role/hop/evidence, identity, classification or digest | Reject with `EINVAL`/`SSH_HOST_KEY_REVIEW`; native and managed bodies are wiped |
| Candidate handoff is empty, oversize, duplicate, re-entrant, cancelled, late or expired | Reject; one-shot receiver/slot is poisoned; no provider candidate remains |
| Candidate accepted but guarded AppSecrets CAS fails or is uncertain | Close provider; return typed CAS failure; never report `COMMITTED` |
| Java exception or native release failure | Stable generic failure; no exception text/secret disclosure; cleanup retry remains possible |
| Successful native close followed by role release | Role deregisters exactly once; late callback rejects |

### 5. Good / Base / Bad cases

- Good: an already-issued role invokes known-host evidence and KEX completion
  synchronously on its bound event-loop thread, then obtains one matching
  typed credential through the managed provider and closes native state and
  the role in order.
- Base: the normal library compiles the adapter and bounded provider seam, the
  host state-machine/JVM tests pass, and the concrete file-view/CAS provider
  and real OpenSSH caller remain absent.
- Bad: invoking from a worker thread, using a control frame or terminal prompt
  as a callback broker, accepting a cached fingerprint, passing an unknown-key
  proposal through an unreviewed path, or handing a credential directly to a
  receiver/UI. All must fail closed.

### 6. Tests required

- Host `testSshCallbackAdapterHost`: strict `-Wall -Wextra -Werror` compile and
  assertions for thread/environment ownership, re-entry, cancellation,
  deadline expiry, concurrent cancel, cross-thread close, bounded credential
  output wiping, dedicated candidate transfer/duplicate/re-entry/oversize
  cleanup and cleanup retry; fixed output must include
  `SSH_CALLBACK_HOST ... gate=pass value=20`.
- `SshNativeCallbackAdapterTest` and `SshProductionBridgeTest`: known-host→KEX
  proof, every rejected host classification, exact role/hop-bound NEW review,
  cross-thread callback/close, re-entry, cancel/late callback, stable Java
  exception mapping, absent-provider fail-closed behavior, successful typed
  password/private-key bytes, wrong-kind/null-role rejection and native
  cleanup retry. Assert no receiver/vault path is touched on failure.
- `SshManagedKnownHostsProviderTest`: positive one-shot native candidate
  transfer, body wiping, wrong identity/base digest, duplicate/closed/expired
  lifecycle rejection, and failed/uncertain guarded CAS with no committed
  receipt.
- `:app:testReleaseUnitTest`, `:app:lintRelease`,
  `:app:assembleRelease` and strict `compileSshFiles` must pass. Inspect the
  normal APK and require the intentional production SSH library only; no
  KEX/control/probe/launcher fixture library or notice may enter it.
- These tests do not substitute for real event-loop, provider, device,
  external-SSH, authentication, PTY, forwarding or service validation.

### 7. Wrong vs correct

Wrong:

```java
if (uiConfirmed || promptText.contains("password")) {
    receiver.accept(secret);
}
```

Correct:

```java
nativeCallback.onNativeKexComplete(cycle, sessionDigest);
byte[] key = nativeCallback.onNativeCredentialBytes(CREDENTIAL_PRIVATE_KEY);
try { usePrivateKeyBytes(key); }
finally { VaultCodec.wipe(key); }
```

The bounded handoff is still only a prerequisite: its source must be the
managed public config/known-hosts view and exact CAS workflow, and the real
OpenSSH event loop must supply the host/KEX evidence before production
userauth. The normal APK does not yet contain that caller or full client.

## Role/hop-bound HostEvidence/CAS contract

This is a bounded native↔Java provider seam, not a production OpenSSH caller.
The native callback supplies the live role token, hop and target metadata
together with host classification, key type, SHA256 fingerprint, and 32-byte
base/replacement digests. Java reconstructs
`SshHostKeyAuthority.Evidence` only after matching that metadata to the issued
role.

### 1. Scope / trigger

Use this contract when a native SSH host-key event needs a managed trust
decision. It covers `ctos_ssh_callback_host_evidence`, the JNI callback
`onNativeHostEvidence`, `SshHostKeyAuthority`, and the
`SshManagedProvider.TrustCasWriter` seam. It does not authorize a concrete
known_hosts file view, a CAS implementation, user-interface approval, or an
OpenSSH event loop.

### 2. Signatures

```c
int ctos_ssh_callback_host_evidence(
    ctos_ssh_callback_token callback, void *environment, int classification,
    const char *key_type, const char *fingerprint,
    const unsigned char base_digest[32],
    const unsigned char replacement_digest[32],
    struct ctos_ssh_host_result *result);

int (*host_evidence)(void *context, void *environment,
    const struct ctos_ssh_host_evidence *evidence,
    struct ctos_ssh_host_result *result);
```

```java
byte[] onNativeHostEvidence(long nativeRoleToken, int nativeHop, int nativePort,
    String nativeConnectionId, String nativeTargetIdentity, String nativeHost,
    int classification, String keyType, String fingerprint,
    byte[] baseDigest, byte[] replacementDigest);

SshTrustProposal.CasResult write(
    SshHostKeyAuthority.Evidence evidence, SshTrustProposal proposal)
    throws VaultException;
```

`CasResult` has the bounded statuses `NOT_REQUIRED`, `COMMITTED`, and
`FAILED`; only `KNOWN` may produce `NOT_REQUIRED`, and only `NEW` may produce
the proposal-bound `COMMITTED` result consumed by the native callback.

### 3. Contracts

- The request is bound to a positive role token, active hop, port,
  connection ID, target identity, host, classification `KNOWN` through
  `INVALID`, key type, exact `SHA256:<43 base64 characters>` fingerprint, and
  two 32-byte digests. Native re-reads the live role snapshot and checks UID,
  PID, deadline, hop and port before invoking Java.
- The Java result is a big-endian, version-1 record with magic `CSHR`, approved
  outcome, zero flags, role/hop/port/classification/CAS status, six bounded
  ASCII length-prefixed fields, and the two exact digests. The total result is
  at most 2048 bytes. Only the proposal binding crosses JNI; proposal and
  known_hosts bodies remain in the managed provider.
- `KNOWN` returns an empty proposal binding and `CAS_NOT_REQUIRED`; it never
  accepts a proposal or writes trust. `NEW` requires an exact role/hop/target/
  key-type-bound proposal, explicit `UnknownKeyIssuer` approval, and an exact
  `CAS_COMMITTED` receipt from `TrustCasWriter`.
- C and JNI revalidate every returned field, including classification,
  key type, binding, status and digests. Cancellation, deadline races,
  callback errors and rejected results clear the result buffer.

### 4. Validation and error matrix

| Condition | Required behavior |
| --- | --- |
| Null/invalid input, malformed key type or fingerprint | Reject before Java/native provider use; `EINVAL` or a typed Java failure |
| Stale role, wrong UID/PID, inactive hop, expired deadline or metadata substitution | Reject closed; no host result is accepted |
| `KNOWN` with a proposal, non-empty binding or non-`NOT_REQUIRED` CAS | Reject as an invalid result |
| `NEW` without provider, exact proposal, explicit approval or CAS writer | Reject with `SSH_HOST_KEY_REVIEW`/`SSH_HOST_KEY_CAS` |
| Changed/revoked/certificate/invalid classification | Reject; `NOT_REQUIRED` is not a general bypass |
| Wrong classification, key type, hop, role, target, fingerprint or digest in the returned result | Reject with invalid/CAS failure and zero the result |
| Callback cancellation, deadline race or Java exception | Fail closed, clear native/JNI result bytes and retain no proposal |

### 5. Good / base / bad cases

Good: an active `KNOWN` hop returns exact metadata, both digests and
`CAS_NOT_REQUIRED`; an approved `NEW` hop returns the exact proposal binding
and `CAS_COMMITTED`. Base: the callback seam compiles and rejects missing
providers while no concrete file/CAS implementation is available. Bad: reuse a
proposal from another hop, change only the key type or classification, accept
approval without a committed CAS receipt, or send the known_hosts body through
JNI.

### 6. Tests required

Host C tests must cover role re-entry, cancellation/deadline cleanup,
credential-byte cleanup, `KNOWN`, approved `NEW`, malformed result fields and
callback failure; the current host gate is 15. JVM tests must assert the
bounded KNOWN/NEW wire, wrong hop, digest mismatch,
classification/key-type substitution, missing provider/approval, and exact
CAS failure; the current JVM gate is 166 tests. A normal Release build must
compile the production library, pass lint and all three ABIs while excluding
boundary/fixture OpenSSH objects from the APK.

### 7. Wrong vs correct

Wrong: treat a boolean host-key approval as trust persistence, derive a new
proposal from Java-side host text, or let native consume a result whose
metadata was not compared with the live event.

Correct: bind evidence to the issued role and hop, require the exact
key-type/fingerprint/digest-bound proposal and explicit approval for `NEW`,
validate the bounded CAS receipt in Java, then revalidate the same metadata and
digests in JNI and C before returning success.

This HostEvidence section is only the bounded callback contract; by itself it
does not prove a concrete provider or production caller. The managed
`SshManagedKnownHostsProvider`/AppSecrets CAS implementation is documented and
tested in the later managed-provider section, but it still does not provide the native hostfile
issuer/candidate handoff or the production OpenSSH caller.

## Production native-client boundary while the host provider is missing

This section records the 2026-10-15 fail-closed boundary. It is a production
facing admission contract, not a production OpenSSH caller: the source map has
identified the future `ssh_api` sequence, but there is still no compilable
native-facing hostfile issuer/candidate handoff for the managed
`config/known_hosts` view. The managed Java/AppSecrets provider and CAS writer
already exist, but they require caller-supplied exact replacement bytes. The
normal APK therefore must not construct a KEX client until the native-facing
issuer/handoff contract is available.

### 1. Scope / trigger

The host-only `testSshNativeClientHost` target compiles
`ssh_native_client.[ch]` with the role bootstrap and strict C warnings. It is
not part of `compileSshFiles`, does not link any OpenSSH object and is not a
transport fixture. The future production caller may only open after a live
role, an explicitly transferred nonblocking/CLOEXEC socket, a synchronous
callback binding and a strictly future deadline have all been admitted.

### 2. Signatures

```c
struct ctos_ssh_native_client_config {
    ctos_ssh_role_token role;
    int socket_fd;
    uid_t owner_uid;
    int owns_socket;
    ctos_ssh_callback_token callback;
    void *callback_environment;
    uint64_t deadline;
    size_t step_budget;
};

struct ctos_ssh_native_client_step {
    const unsigned char *input;
    size_t input_length;
    unsigned char *output;
    size_t output_capacity;
    size_t input_consumed;
    size_t output_consumed;
    int cancelled;
};

int ctos_ssh_native_client_open(
    const struct ctos_ssh_native_client_config *,
    struct ctos_ssh_native_client **);
int ctos_ssh_native_client_step(
    struct ctos_ssh_native_client *,
    struct ctos_ssh_native_client_step *);
int ctos_ssh_native_client_verify_host_key(
    struct ctos_ssh_native_client *, const void *opaque_host_key);
int ctos_ssh_native_client_close(struct ctos_ssh_native_client *);
```

`CTOS_SSH_NATIVE_CLIENT_MAX_STEP_BYTES` is 64 KiB. `owns_socket == 1` means
the future client may receive ownership only after the provider gate is
implemented; the current missing-provider path never consumes or closes the
descriptor. `callback_environment` is opaque and must remain the exact
environment captured by the synchronous attached JNI adapter.

### 3. Contracts

- `open` starts with `*client = NULL`, rejects null/zero/malformed metadata,
  verifies the live role against the current PID/UID and passed owner UID,
  requires exact role/deadline binding, and admits only `FD_CLOEXEC|O_NONBLOCK`.
- A valid admission is still stopped before `ssh_init`, FD attach,
  `ssh_packet_set_nonblocking`, verifier registration or packet dispatch when
  the provider is absent. It returns `-1` with `errno == ENOTSUP` and leaves
  the caller descriptor open.
- `step` validates cancellation, bounded input/output lengths and non-null
  buffers, clears both consumed counters, then returns the same provider-missing
  error without reading input or writing output. No fake partial-I/O result is
  allowed.
- `verify_host_key` never derives trust from the opaque key or its fingerprint;
  until a role-bound `KNOWN`/`NEW` provider result and exact CAS contract exist,
  it returns `ENOTSUP`. `close` cannot report success for a client that was
  never created.
- This boundary does not call `ctos_ssh_kex_*`, callback/JNI functions or any
  credential release path. It does not classify host keys, issue proposals or
  mutate the vault.

### 4. Validation and error matrix

| Condition | Required result |
| --- | --- |
| Null config/output, zero role/callback, missing environment, non-owned socket or invalid budget | `EINVAL`, output client remains `NULL` |
| Non-CLOEXEC or blocking socket | `EINVAL`, descriptor remains caller-owned |
| Wrong role, owner UID, PID/UID or deadline binding | `EACCES`, no client and no descriptor close |
| Valid admission while provider/CAS is unavailable | `ENOTSUP`, no OpenSSH/KEX/JNI/credential call |
| Null/cancelled step, null bounded buffer or over-limit length | `EINVAL`, no payload access |
| Bounded step after provider gate | `ENOTSUP`, consumed counters reset to zero |
| Host verifier without a provider | `ENOTSUP`, no host classification or trust |
| Close before a client exists | `ENOENT`/`EINVAL`, no native release |

### 5. Good / Base / Bad cases

Good is a strict admission rejection with stable `ENOTSUP`, a null client,
zero consumed counters and the caller socket still open. Base is the host
gate only: `SSH_NATIVE_CLIENT` admission 27, provider 1, cleanup 1 and gate
27. Bad is constructing `struct ssh` before a provider exists, accepting a
fingerprint as `KNOWN`, inventing `NEW` proposal/base/replacement digests,
calling blocking stock `ssh_connect`/`ssh_login`, or closing a caller FD on
the missing-provider path.

### 6. Tests required

`testSshNativeClientHost` must continue to compile with
`-std=c11 -Wall -Wextra -Werror -pthread` and cover null/ownership/role/
PID-UID/deadline/socket-flag/step-boundary/cancel/provider-missing and
non-consumption cleanup cases. The fixed output must retain the four
`SSH_NATIVE_CLIENT` records and gate value 27. The normal build must also
pass `testReleaseUnitTest`, `lintRelease`, `assembleRelease`, strict
`compileSshFiles`, and APK inspection must show no boundary or fixture
OpenSSH object.

After the provider contract is frozen, add separate real-socket regression
for partial input/output, EAGAIN, EOF, BOOTTIME deadline, cancellation,
close-retry, inbound NEWKEYS/session digest and synchronous JNI proof. Until
then, absence of those tests is an open gate, not a skipped pass.

### 7. Wrong vs correct

Wrong:

```c
fingerprint = sshkey_fingerprint_raw(host_key);
return ctos_ssh_kex_approved(ssh, fingerprint); /* guessed trust */
```

Correct:

```c
if (!managed_provider_has_exact_host_evidence(role, host_key, &proposal))
    return reject(ENOTSUP);
return ctos_ssh_callback_host_key(callback, environment,
    proposal.classification, proposal.key_type, proposal.fingerprint,
    proposal.base_digest, proposal.replacement_digest);
```

The second snippet is a future contract shape, not an existing callable
provider. It must be implemented only after the managed file view and CAS
writer provide exact role/hop-bound evidence; the current boundary deliberately
stops before it.

## Managed known_hosts provider and AppSecrets CAS contract

This section is the concrete managed-vault increment layered on the
HostEvidence/CAS callback above. It provides a real AppSecrets writer, but it
still does not provide the native OpenSSH hostfile parser/issuer or a caller
that can produce exact replacement bytes.

### 1. Scope / trigger

Use this contract when a managed KNOWN_HOSTS object must be reviewed and
updated for one active SSH role/hop. It covers
`SshManagedKnownHostsProvider`, `SshVaultAdapter`, `VaultOperation`, and the
`AppSecrets` trust CAS. It is not a prompt/terminal-output parser, a
fingerprint-to-file formatter, or an authorization to pass a proposal body
through JNI.

### 2. Signatures

```java
static SnapshotReceipt snapshotFor(SshVaultAdapter adapter, SshRoleContext role,
    SshRoleContext.Clock clock, String rootPath, String path, String objectId,
    String objectIdentity) throws VaultException;
static KnownHostsCandidate candidateFor(SnapshotReceipt snapshot,
    SshHostKeyAuthority.Evidence evidence, byte[] exactReplacement)
    throws VaultException;
static SshManagedKnownHostsProvider bind(SshVaultAdapter adapter,
    SnapshotReceipt snapshot, KnownHostsCandidate candidate) throws VaultException;

SshTrustProposal proposalFor(SshHostKeyAuthority.Evidence evidence,
    SshHostKeyAuthority.Proof proof) throws VaultException;
SshTrustProposal.CasResult write(SshHostKeyAuthority.Evidence evidence,
    SshHostKeyAuthority.Proof proof, SshTrustProposal proposal)
    throws VaultException;

VaultOperation trustRequest(SshRoleContext role,
    SshHostKeyAuthority.Evidence evidence, SshHostKeyAuthority.Proof proof,
    SnapshotReceipt snapshot, SshTrustProposal proposal) throws VaultException;
void updateTrust(VaultOperation request, SshRoleContext role,
    SshHostKeyAuthority.Evidence evidence, SshHostKeyAuthority.Proof proof,
    SnapshotReceipt snapshot, SshTrustProposal proposal,
    AppSecrets.EffectGuard guard) throws VaultException;
```

`exactReplacement` is bounded by the manifest file limit. The public
`CasResult` contains only a status and exact evidence/proposal binding;
replacement bytes remain managed and are never a JNI payload.

### 3. Contracts

- `snapshotFor` selects exactly one public `KNOWN_HOSTS` object matching
  path, object ID and identity. It retains only metadata, the vault UUID,
  generation, session epoch, deadline, snapshot binding and base digest.
  The consumed `SSH_FILES` request is cancelled after the synchronous copy.
- `KnownHostsCandidate` owns one clone of caller-supplied exact replacement
  bytes. Its replacement digest must equal the NEW evidence digest and its
  binding must match the snapshot, role, hop, target, host, port, key type
  and fingerprint. It is consumed once when `proposalFor` builds the exact
  object-bound proposal.
- The proof-aware writer must reject the legacy unbound overload. It creates
  a new AppSecrets trust operation, binds role and trust proof, checks the
  current vault UUID/generation/epoch/binding, and invokes the final guarded
  `AppSecrets.updateTrust`. `COMMITTED` may be constructed only after that
  method returns successfully.
- `SshManagedProvider.writeBack` must validate `CasResult.matches(evidence,
  proposal, COMMITTED)` while the proposal is still open, then close the
  proposal in `finally`. Closing first wipes the proposal binding, so even a
  successful guarded CAS becomes an incorrectly rejected/expired receipt.
- `AppSecrets.updateTrust` rechecks file type, ID, path, identity and the
  base digest against the current manifest before committing the replacement.
  Approval and the SSH validator are checked at admission and in the final
  effect guard.
- Cancellation must not wait for the vault monitor while `Store.commit` is
  blocked. The operation table is concurrent; `cancelOperation` revokes the
  operation first and then removes the exact map entry.

### 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Missing/ambiguous object, wrong type, ID, path or identity | Reject with `VAULT_OPERATION`/`VAULT_CONTENT`; retain old vault bytes |
| Closed, expired, cancelled or inactive role/snapshot/candidate | Reject fail-closed; wipe owned digests/body |
| Candidate bytes exceed manifest limit or digest differs from evidence | Reject `SSH_HOST_KEY_REVIEW`; do not create proposal |
| Wrong hop/role/target/host/port/key type/fingerprint or object binding | Reject `SSH_HOST_KEY_REVIEW`/`VAULT_OPERATION` |
| Missing approval, validator or proof | Reject `VAULT_TRUST_REVIEW`/`SSH_HOST_KEY_CAS`; no commit |
| Vault UUID, generation, epoch, snapshot binding or old base digest changed | Reject `VAULT_CHANGED`/`SSH_HOST_KEY_CAS`; preserve old record |
| Store commit fails or returns uncertain | Do not report `COMMITTED`; preserve uncertainty/lock semantics |
| Cancel races with a store commit | Revoke without waiting for the vault monitor; never deadlock the caller |

### 5. Good / Base / Bad Cases

Good: one exact managed KNOWN_HOSTS object is snapshotted, an exact candidate
matches the NEW evidence digest, approval is repeated, and the guarded CAS
returns `COMMITTED`.

Base: an exact snapshot/candidate/proposal can be tested with an in-memory
caller-supplied replacement; this proves the vault writer seam, not an
OpenSSH hostfile writer.

Bad: derive replacement bytes from a fingerprint, choose the first file with
the right path but wrong ID/identity, return `COMMITTED` after approval alone,
retain a consumed SSH_FILES operation, or take the AppSecrets monitor while
waiting for a store commit.

### 6. Tests Required

- Provider JVM tests must assert exact object selection, base/replacement
  digest binding, one-shot candidate/proposal consumption, wrong role/hop/key
  type/classification, stale generation/epoch, missing approval/validator,
  missing candidate/proof and cleanup after failure.
- AppSecrets/VaultOperation tests must assert old bytes remain on every
  rejected or failed CAS, successful commits replace only the exact object,
  and after-claim cancellation can return without waiting for a blocked
  store callback while uncertain-commit semantics remain intact.
- The latest managed known_hosts/candidate handoff gate is 177 tests with zero
  failure/error/skip; callback host is 20 and the native-client
  missing-provider gate remains 27. The earlier provider-only 174/15 gate is
  historical. Release compile, lint, all three ABIs and APK inspection must
  show no host-only or fixture OpenSSH object.

### 7. Wrong vs Correct

#### Wrong

```java
// Approval is not a write, and a fingerprint is not a candidate body.
return CasResult.committed(evidence, proposal);
```

#### Correct

```java
VaultOperation request = adapter.trustRequest(role, evidence, proof, snapshot, proposal);
adapter.updateTrust(request, role, evidence, proof, snapshot, proposal, guard);
return CasResult.committed(evidence, proposal); // only after updateTrust returns
```

The correct path keeps body bytes in the managed provider, binds the operation
to the current vault snapshot, and makes cancellation independent of the
AppSecrets monitor. It still must not be called a production OpenSSH caller
until the real hostfile/parser path supplies the exact candidate.

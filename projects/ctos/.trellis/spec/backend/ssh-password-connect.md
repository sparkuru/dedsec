# Foreground password SSH connection

## 1. Scope / Trigger

Follow [the contract](../../../design/ssh-password-connect.md) for the first operable password
connection on the single formal mainline. Reuse the pinned client, actual managed host-key/CAS
proofs, shared vault, sensitive-route protection and existing typed terminal. No fake vault,
fixture transport, blanket validator/approval, guessed PINNED key or second client implementation.

## 2. Signatures

`ssh.defaults`, `ssh.passwordConnect`, `ssh.cancel`, `ssh.hostKeyDecision` on ctos/native;
independent ctos/ssh public attempt/review stream, unchanged ctos/terminal data/state contract.
`openManaged(callback, lease, SshSocketOwnership, candidateId, budget)` is the production FD
handoff. Actual platform identity comes from `SshNativeRoleBootstrap.systemIdentity()`.

## 3. Contracts

The default form saves public recent endpoint configuration and confirmed host keys in the
actual encrypted shared vault; password is default one-use, never a temporary saved entry.
Explicit persistent password choices follow the saved-password scenario below.
Fixed generated CONFIG ssh/config and KNOWN_HOSTS ssh/known_hosts, IDs/identity/alias and
reversible quoting are frozen in the contract. Validate the entire supplied manifest against
the supported subset, pinned parse/actual tuple, trust material/types/references/bounds;
reject every unsupported SSH object. Preserve all unrelated TOTP and existing known_hosts.
Only the precise generated proposal is approved by its original actual submit context.
Known-host lookup and candidate serialization use the actual role port: raw host at 22,
OpenSSH `[host]:port` otherwise (also IPv6). Keep evidence/role host raw and port separately
bound. A trusted record for another port must not transfer trust to this endpoint.

Transient selected-password authority binds actual request, role/selection identity, username,
target/connection, host/KEX proof, shared foreground/unlock, original deadline and final guarded
copy. One attempt/copy, no legacy kind-only fallback. Exact own-profile/NEW-CAS continuation
must keep this attempt alive; foreign writes/lock/cancel revoke. Wipe owned arrays on Throwable.

Nonblocking public Os socket/connect/poll/getpeername confirms actual selected peer; no hidden
getsockoptInt/reflection and no POLLOUT-only success. DNS worker/queue is bounded and late
results cannot publish. Dup/detach gives an owned FD wrapper; original fd closes without shutdown.
Mark native adoption immediately after positive handle and before Java finish/postguards.
Keep prepared native session for retryable cleanup; never raw-close an adopted/reused fd.
JNI publication cannot fail after C ownership success; failures before transfer remain caller-owned.
API28 uses the established Linux atomic socket flag values rather than newer Java fields.
PFD dup shares NONBLOCK but clears CLOEXEC; while PFD still owns it, own JNI
`SshNativeSession.prepareTcpDescriptor(int) throws IOException` validates STREAM/RDWR/NONBLOCK
and sets CLOEXEC before detach. It never closes/consumes the descriptor or grants authority.
No hidden Os.fcntlInt, broad suppression or minimum-SDK increase.

Only fresh real selected SUCCESS at beginShell promotes that exact client to shell-only authority.
It may outlive auth/view time budgets, retains owner/transport/KEX identity and explicit revocation/
cancel checks, and grants no credential/file/trust/new-auth/rekey privileges. Old auth<=60s stays.
Foreground-only first path is not completion of background service/jump/forward/rekey contracts.

## 4. Validation & Error Matrix

Source/candidate/proposal mismatch, changed/revoked/unsupported host, stale ticket/request,
cancel/lock/expiry/context change and malformed payload fail closed before effect/secret copy.
UI decision and cancel bypass blocked terminal owner, resolve exact original ticket and cannot
approve by Boolean alone. RUNNING comes only after SUCCESS+PTY+shell, never RPC/socket completion.
Error text is bounded and practical, with no passwords/raw exception payloads. Pending attempt
cleanup retains resources until confirmed; a completed trust CAS is never falsely rolled back.

## 5. Good / Base / Bad Cases

Good: actual NEW key appears with host/port/algorithm/fingerprint, user chooses trust-and-save,
the exact CAS succeeds, then one transient password reaches real authenticated channel.
Base: a known matching key reconnects without asking, while password is still only this attempt.
Bad: another epoch is accepted as own-CAS, password is briefly stored then deleted, a 60s auth
budget is globally extended, or a failed open double-closes a reused descriptor.

## 6. Tests Required

Actual orchestration sequencing with injected boundaries, canonical profile/whole-subset/no-effect
failures, ephemeral replay/mismatch/preproof/lock/cancel/own-CAS, DNS/connect/peer/FD transfer and
retryable cleanup. NEW/KNOWN/changed/revoked, exact decisions/dismissal/stale events and cancel while
awaiting approval. Native encrypted tests prove post-auth deadline crossing only for bound shell;
credentials/files/new-auth expire, owner/revoke/changed KEX/rekey still fail. Preserve old matrices.
Flutter protected-before-form, masking/clear/lifecycle, pending cancel/stream failure, successful
handoff, narrow keyboard/large text and App/Root regressions. Main serializes hako and raw APK checks.
Independent local sshd and authorized Android runtime are distinct from injected/same-library tests.
The opt-in Android UI fixture must operate actual advertised Flutter controls without channel
calls, reflection, sink replacement or clipboard. Require the isolated target/UID, fresh owned
fixture data and an owner-only request; compare the complete actual endpoint and fingerprint
before clicking trust-and-save. Verify native sensitive-window protection, actual command
submission and a split marker in the rendered output snapshot, then return and close normally.
Flutter static labels can use contentDescription; read-only selection can report editable, so
exclude writable ACTION_SET_TEXT nodes while requiring the actual snapshot route. UiAutomation
command arguments do not use shell unquoting; keep the safe stat format unquoted. Use the
advertised AppBar Back action when hardware injection is rejected; never directly pop routes.
Require method status 0 and the expected OK test count without failures/skips: adb process
exit 0 and instrumentation final code -1 alone do not prove a passing test. Cleanup only the
packages/data/requests/reverse/server created in the authorized session.

## 7. Wrong vs Correct

Wrong: expose a test picker, autoapprove trust, ignore unsupported config or infer FD consumption
from an exception. Correct: one real production path with explicit public-state effects, exact
authority, narrow post-auth session capability and evidence scoped to what actually ran.

## Scenario: Optional saved password

### 1. Scope / Trigger

The selected R10 increment extends this same production path; follow
[saved-password contract](../../../design/ssh-saved-password.md). The original transient default,
real host trust/KEX, original deadlines and foreground-only connection behavior remain required.

### 2. Signatures

`ssh.defaults(requestId)` adds `savedPassword: bool` to the public tuple.
`ssh.passwordStatus(requestId, host, port, username)` returns `{saved: bool}`.
`ssh.passwordConnect` adds strict `useSavedPassword` and `savePassword` booleans;
`ssh.forgetPassword(requestId, host, port, username, all)` returns `{forgotten: bool}`.
No payload accepts a caller-selected entry ID/target or returns password bytes.
For `all=true`, send empty host/username and port22; no valid current tuple is required.
The host owns a distinct all-password mutation context rather than an invented account.

### 3. Contracts

Canonical Account/SSH_PASSWORD records bind exact host/port/username via domain-separated,
length-framed UTF8 tuple digest, stable IDs and matching target; metadata is validated against
every actual record. Reject orphan credentials, noncanonical IDs/targets, unsupported account
kinds, changed usernames and secret length/NUL/encoding violations. Preserve TOTP, generated
config/known_hosts and unrelated saved tuples. Save/delete account and credential atomically in
the existing original-context entry mutation pipeline, with independent owned candidates and
the same effect/uncertain handling. No second crypto/storage implementation.

Default transient does not modify persisted passwords. Explicit save can commit before network
connect; connection failure does not imply rollback. Complete exact own profile/password writes
before creating the public view; never accept arbitrary epoch advances or extend the budget.
Saved selection uses proof-bound typed reads and validates actual Account host/port/username,
entry and target both on admission and release. Foreground, unlocked vault, original deadline,
validator registration, exact role/selection/KEX, cancellation and final guarded copy all apply.

Status replaces only its exact prior idle guard; cancelled older IDs cannot retire newer work.
Forget uses its own request/guard and cannot be overtaken by connect. Late cancellation after
durable mutation reports uncertain and locks as required by the shared pipeline. Complex SSH
restore metadata review remains closed; adding accounts grants no restore approval.
Pin the original immutable validator registration before queueing and retain its cancellation/
deadline checks across exact own writes; this metadata-only guard does not grant old-epoch bundle
admission. The bridge retains one successful request/connector solely for exact late cancellation;
replace it on successful handoff, not an attempted BUSY connection. Factory identity prevents
an old cancellation affecting a newer terminal. Ordinary event-stream disposal does not cancel
the retained successful connector.

### 4. Validation & Error Matrix

Bad booleans, saved+nonempty password, saved+save, malformed tuple/password -> PARAMETERS.
Absent saved account -> SSH_PASSWORD_MISSING; mismatched account -> fail before credential copy,
never fall back to another entry.
Foreign content -> SSH_PROFILE_UNSUPPORTED; changed vault/epoch -> VAULT_CHANGED.
Cancel/lock/expiry preserve the original typed errors; durable-but-unconfirmed writes ->
VAULT_COMMIT_UNCERTAIN. Return only fixed bounded error text, no raw secrets/exceptions.

### 5. Good / Base / Bad Cases

Good: explicitly save tuple A, reconnect A with an empty frontend password and exact native
account selection, then forget A while tuple B/TOTP/trust remain. Base: old generated profile
and pureTOTP continue; default supplied password never becomes a persistent entry.
Bad: select by username alone, concatenate ambiguous tuple fields, silently use a saved password
after target editing, or serialize password bytes into defaults/events.

### 6. Tests Required

Actual encrypted store round trips for create/update/one/all delete and mixed preservation;
tuple boundary/Unicode/cross-port/cross-user/cross-host mismatch and metadata tampering;
current typed read Account checks, preproof rejection and original lock/cancel/epoch/expiry cases;
commit claim/uncertain and validator loan mutation. Flutter exact payloads/stale status/forget
confirmations and layout remain separate; Android runtime requires current explicit authorization.

### 7. Wrong vs Correct

Wrong: read a saved password into Flutter and reuse it after editing the host.
Correct: publish only saved availability; native resolves the exact Account and performs the
existing proof-bound selected read at authentication time.

# Managed SSH host-trust inspection and forgetting

NEW and CHANGED writes that precede native KEX acknowledgement also follow the
[trust commit acknowledgement](ssh-trust-commit-ack.md) contract, including
in-flight screen-lock ordering and the possibly committed receipt.

## Scenario: order local host review against STOP

### 1. Scope / Trigger

STOP may be published while native invalidation is draining or has an unknown result. A pending NEW or CHANGED decision must not rely only on a prior live sample or on the later mailbox cancellation.

### 2. Signatures

Production `SshHostKeyReviewMailbox` receives the connector's exact `SshSessionOwner.Cancellation`; ticket installation, `resolve`, and delivery of an accepted decision to its owner use `Cancellation.admitTerminalIo()`. The signed-NEW fixture additionally admits its local claim and reservation transfer. Standalone constructors retain independent cancellation for isolated callers.

### 3. Contracts

Perform each admission under the mailbox monitor immediately before publishing that local state, except the fixture's lock-free one-shot consume, which admits before its own atomic claim. STOP first denies that step even if native invalidation never completes. An admitted decision or presentation may physically finish after STOP. The decision remains bound to the original review, and the owner rechecks the live role and deadline. A local decision grants no persistent trust write, credential use, new proof, or chain-wide cleanup authority. The product connector uses its shared cancellation; fixture-only signed review is not a product NEW-host flow.

### 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| STOP before ticket, decision, or owner delivery admission | Deny that local transition; dismiss any presented ticket on its owner path |
| Decision or presentation admitted before STOP | It may finish later; subsequent unadmitted transitions are denied |
| Native invalidation throws | Keep the native outcome and owner unresolved; local mailbox cancellation is not proof of native cleanup |
| Signed fixture review is locally admitted | It remains a test-only candidate reservation, not trust-write authority |

### 5. Good / Base / Bad Cases

Good: STOP wins before decision admission and a late UI result is rejected. Base: the exact live review is accepted and checked by its owner. Bad: treat an earlier live check or accepted UI decision as a durable trust-write permit.

### 6. Tests Required

Exercise actual mailbox and shared cancellation with barriers on both sides of ticket installation, resolve, owner delivery and signed-fixture claim; include pending and unknown native invalidation and confirm the original owner and result are retained. The user authorized local verification on 2026-11-27; keep Android/device results separate from host checks.

### 7. Wrong vs Correct

Wrong: approve a decision after STOP because native invalidation has not finished. Correct: order each local transition with STOP and separately enforce the original trust-commit and native-effect contracts.

## Scenario: report a rejected host proof during key exchange

### 1. Scope / Trigger

An internal host-proof binding failure cancels the native callback and makes the session terminal.
Report that fixed diagnostic without mistaking real cancellation, a changed server key, or an
uncertain persistent write for the proof failure.

### 2. Signatures

`SshNativeCallbackAdapter.reportableHostProofFailure() -> boolean` is a read-only snapshot.
`SshNativeSession.ownsCallback(candidate) -> boolean` checks exact adapter identity.
`SshPasswordConnector.keyExchangeStep(session, callback, transportGuard, trackedWrite)` wraps the actual
KEX step. The existing connection error is `SSH_HOST_PROOF_INVALID`; it carries no remote text.

### 3. Contracts

Only a terminal `SSH_SESSION_CANCELLED` from the current KEX step plus that same callback's
fixed `SSH_HOST_PROOF_INVALID` can be translated. Any tracked credential or changed-trust write
keeps the original cancellation code: completion delivery may still be cancelled after the owner
publishes its future, and the bridge needs that code to preserve its uncertain-write rule.
Recheck the original product guard, including
actual transport deadline, before reporting; the callback must still own an active role/view,
have no external cancellation, and be outside an active invocation. Recheck both after the first
snapshot. First require `session.ownsCallback(callback)`; a different session's callback must never
supply diagnostic evidence. The native session remains terminal, and no retry, proof, credential, trust approval,
or host-key replacement follows from this diagnostic. Owner publication orders Stop and screen
lock against the connection future: a cancellation already recorded wins; a completed future is
not rewritten by a later stop. `completionFailure` retains final authority for uncertain writes.

### 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Exact internal proof failure, active original attempt | `SSH_HOST_PROOF_INVALID`; no native replay |
| A credential or changed trust was already written in this attempt | Keep original session failure; preserve the bridge's uncertain-write handling |
| A callback belongs to another session | Keep original session failure without consulting it as proof evidence |
| Explicit session/callback cancellation races a timeout or view failure | Existing session cancellation wins; no proof diagnosis |
| Product cancellation, screen lock, expired transport or revoked view observed first | Existing cancellation, lock, timeout or view error; no proof diagnosis |
| Changed trust was written without confirmed native completion | `VAULT_COMMIT_UNCERTAIN` and locked vault, regardless of the tentative diagnostic |
| Native exception text merely contains a code or another callback fails | Original fixed failure; never infer a proof error from text |

### 5. Good / Base / Bad Cases

Good: actual bound callback failure reaches the existing verification-failed message after both
guards. Base: a cancelled callback or true changed host retains its original behavior. Bad:
turn every callback cancellation into proof failure or present proof failure as a server key change.

### 6. Tests Required

Exercise the real Java callback and session with a synthetic native failure, original and shorter
deadlines, revocation, product/session/callback cancellation on both sides of the guard, no replay,
arbitrary exception text, and tracked writes before and between guard checks. Exercise owner
publication ordering with real threads, simultaneous callback cancellation/timeout, uncertain
write versus cancellation, cross-session callbacks during both KEX and first-factor startup, and actual
encrypted-vault changed-key CAS with the uncertain completion rule. JNI, Android and real-server
results are separate from these JVM fixtures.

### 7. Wrong vs Correct

Wrong: inspect native exception text and immediately publish a host-key conflict.
Correct: validate same-owner callback state and the original live attempt, keep the session
terminal, and let a possibly committed trust write override the diagnostic with uncertainty.

## Scenario: report a rejected host proof while starting first-factor authentication

### 1. Scope / Trigger

Password and private-key startup synchronously request their selected credential from the Java
callback. A proof binding failure there cancels the callback before any credential is handed off.

### 2. Signatures

`SshPasswordConnector.beginFirstFactor(session, callback, privateKey, authenticationGuard,
trackedWrite)` invokes exactly one of `beginPasswordAuth()` or `beginPrivateKeyAuth()`.
The KEX wrapper and first-factor wrapper use the same fixed proof-failure classifier.

### 3. Contracts

Translate only the same live callback's `SSH_HOST_PROOF_INVALID`, under the cancellation,
revocation and tracked-write rules in the KEX scenario above. First-factor startup uses the
original authentication guard `check()` and request deadline; the earlier transport deadline
has already served its purpose and must not cut short authentication. The diagnostic grants no
retry, additional secret read, later factor or shell; any already-created sensitive buffer still
follows the existing guarded-copy and wipe contract. Standalone keyboard-interactive and continuation retain their
existing separate entry points. Successful starts and unrelated errors retain their normal behavior.

### 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Mismatched selected host proof with live original authentication | `SSH_HOST_PROOF_INVALID`; no credential or shell |
| Original authentication deadline expired or view revoked | Existing timeout or vault error; no proof diagnosis |
| Earlier transport deadline elapsed but authentication remains live | Proof diagnosis is still possible; do not apply the transport guard |
| External cancellation, tracked write or uncertain commit | Preserve the cancellation/uncertainty rules from the KEX scenario |
| Ordinary native start error, rejection or other callback failure | Preserve its original error; do not try another authentication method |

### 5. Good / Base / Bad Cases

Good: a selected password or key callback rejects a foreign role proof before reading a secret,
and the connection reports a fixed proof error. Base: normal first-factor startup is unchanged.
Bad: reuse the shorter KEX deadline or retry a different credential after callback rejection.

### 6. Tests Required

Invoke the real Java selected-credential callback for both methods with a deliberately mismatched
proof; verify zero secret reads, one native start, terminal session, no shell and no replay.
Exercise original-deadline versus simulated shorter-transport timing, cancellation, view
revocation, tracked writes, uncertain results and unrelated native errors. Keep Java fixtures,
JNI/Android execution and real-server evidence separate.

### 7. Wrong vs Correct

Wrong: `beginPasswordAuth` fails -> retry another method or use the old transport deadline.
Correct: preserve the original authentication context and error ordering, then terminate the
same attempt without reading or resending the selected credential.

## 1. Scope / Trigger

Apply to `SshHostTrustMaterial`, `SshHostTrustManagement`, `SshProfilesBridge`, native
`ssh_host_trust.[ch]` / JNI and their tests. Product scope and wire layout live in
[the contract](../../../design/ssh-host-trust-management.md). This operation forgets old local
ordinary records; it never supplies NEW evidence, approves an offered key or reconnects.
The separate same-connection CHANGED transaction below does not reuse a forget ticket.

## 2. Signatures

```text
ssh.hostTrustQuery(requestId,host,port)
  -> {host,port,groups:[{group,path,exists,records,canForget}]}
ssh.hostTrustForgetPreview(requestId,host,port,group)
  -> {ticketId,binding,host,port,group,path,records}
ssh.hostTrustForgetCommit(requestId,ticketId,binding) -> {forgotten:true}
ssh.hostTrustForgetCancel(ticketId) -> {cancelled:bool}
record = {line,algorithm,fingerprint,marker,selector,reason}
```

Return exactly two ordered groups password/config, including absent objects. Marker strings
are none/revoked/ca, selector literal/hashed/shared, reason none/shared/policy/unsupported.
Public algorithm text may describe a blocked certificate or unsupported key; do not reuse an
authentication-key allowlist for display. Fingerprints must be actual canonical SHA256 facts.

## 3. Contracts

Host selection uses the full exact current object identity, not a caller path:
`ssh/known_hosts` with password fixed ID/identity; `.ssh/known_hosts` with deterministic
`id("hosts",path)` / `id("hosts-owner",path)`. Reject wrong type/identity/references. Query
uses one protected source snapshot, releases its context and grants no mutation capability.
Forget preview obtains an independent snapshot; only its actual fresh metadata is reviewed.

Native uses supplied owned bytes and the pinned real parser/key implementation, with whole
material validation and the issuer-compatible lookup: port22 host, otherwise `[host]:port`,
ASCII-folded internally. A literal is removable only after parser match, absence of `*?!,`
and ASCII-folded exact lookup equality. Hash matching must be real parser verified. Never
normalize IPv6 spelling independently or mistake endpoint brackets for wildcard syntax.

All matching ordinary single-target supported software-key rows enter the candidate. Any
matching shared/pattern, marker, unsupported or certificate row blocks the entire group.
Keep every marker and all other endpoints. Never use stock whole-row filesystem deletion
for shared selectors. Correlate upstream line numbers to original physical byte spans; omit
only reviewed whole spans, preserving CRLF/comments/spacing/missing final newline exactly.

CSHTv1 is separate from CSMI: BE header88, rows216, max128 rows per object and1MiB body;
see product contract for exact fields. Strictly validate version/mode/flags, lengths/count,
row order/enums, canonical fingerprint, zero padding, base/replacement digests and tail.
Zero-length replacement is present data and must retain its present flag. No replacement
bytes cross MethodChannel. JNI/Java own bounded copies and wipe every owned exit, including
failed or late publication. Callback borrows do not survive callbacks. No external file,
network, command or plaintext temporary-file effect is permitted by the native operation.

Preserve whole-input1MiB, line65,536bytes/16,384lines limits. Original management budget is
120s; each native call receives min(original,now+60s), never renewed. Check original guard
before/after synchronous native calls and during callbacks; do not promise arbitrary Java
cancellation can preempt upstream parsing. Check logging linkage instead of leaking raw rows.

Prepare one `SshBundleProposal.Change.replace` retaining the exact FileType/ID/path/identity/
references, including an empty resulting known-host object. Other graph/credentials/TOTP
remain unchanged. Validate complete source and candidate using the production validator.
Preview retains original Request/BundleContext, epoch/revision/ciphertext binding/deadline;
fresh commit request IDs do not renew authority. Separate IMPORT / KEY_DELETE / HOST_TRUST_FORGET
ticket kinds explicitly. Cross-kind commit/cancel, replay and stale/source-changed requests
cannot consume a different ticket or write. No removed directory-ticket branch is restored.

Reuse bundle registration, original source guard, once write claim and final terminal
delivery/uncertain handling. Keep commit-owned proposal alive until its outcome is known.
Active connection, input/edit/read/commit and pending ticket ownership must exclude conflicting
trust operations in both directions; disabling a Flutter button is insufficient. Background,
lock, cancel, timeout and bridge close revoke original context immediately and suppress late
publication. Durable write with unsafe terminal delivery locks and reports uncertainty.
Check `SshSessionService.active()` separately from the in-flight connect/mutation supplier:
successful handoff clears the latter while an established service session still exists,
including after Activity replacement. Keep this check trust-specific at admission, original
work guard and terminal delivery; do not block unrelated library/key behavior by accident.
If AppSecrets already reports VAULT_COMMIT_UNCERTAIN before the local written flag can be
set, retain that may-have-written status through a failed first MethodChannel delivery.
Never downgrade an uncertain effect to generic failure or retry its mutation.

## 4. Validation & Error Matrix

| Condition | Outcome |
| --- | --- |
| No matching records/object in query | explicit empty/absent group, no mutation |
| Forget with no records/object | SSH_HOST_TRUST_EMPTY |
| Matching shared/marker/unsupported row | SSH_HOST_TRUST_BLOCKED, no candidate write |
| Bounded native output overflow | SSH_HOST_TRUST_LIMIT |
| Malformed material/wire/system failure | fixed SSH_HOST_TRUST_FAILED |
| Wrong/stale/replayed/cross-kind ticket | reject without mutation, existing vault error |
| Busy/cancel/timeout/locked/changed source | preserve original typed guard outcome |
| Write completed but delivery unsafe | VAULT_COMMIT_UNCERTAIN and locked vault |

## 5. Good / Base / Bad Cases

Good: preview all exact target rows in one group, cancel without write, confirm one byte-span
replacement, then separately connect and review the actual newly offered key. Base: other
port/group records and all revocation policies stay unchanged. Bad: accept a fingerprint
string from Dart, remove a matching shared row, approve CHANGED, or reuse a prior query ticket.

## 6. Required Tests

Real native parser/key/hash/port/case/IPv4/IPv6 matching, marker/shared/cert refusal, duplicate
rows/algorithms, whole-file validity, exact preserved bytes, input/output limits, deadlines,
cleanup/failure outputs and wrapped zero external effects. Java injected-wire ownership/
strictness, both object identities, one-change graph preservation, actual guarded bundle
source/candidate validation, original context/replay/cancel/source/uncertain semantics.
Flutter typed boundary/confirmation/lifecycle/layout coverage is separate. C-only/QEMU,
Android JNI execution, real KEX/new-key review, human UI feedback and install evidence must
remain distinct; do not call a compiled Android test an executed test.

## 7. Wrong vs Correct

```text
Wrong: saved query metadata -> delete a line by caller-supplied fingerprint -> retry CHANGED.
Correct: fresh source-bound preview -> exact once bundle commit -> user-started new KEX/review.
```

## Scenario: same-connection changed host key

### 1. Scope / Trigger

Apply when the real managed host-file issuer classifies the offered server key as CHANGED.
This transaction is separate from the standalone query/forget operation above and from NEW
approval. It changes the native/JNI/Java/vault/Flutter contract and is scoped to the exact
single managed known-hosts object used by the current connection.

### 2. Signatures

```text
hostKey {requestId,ticketId,binding,host,port,hop,keyType,fingerprint,remainingMillis}
hostKeyDismissed {requestId,ticketId,binding,hop}
hostKeyChanged {requestId,ticketId,binding,host,port,hop,source,keyType,fingerprint,
                oldKeys:[{keyType,fingerprint}],remainingMillis}
hostKeyChangedDismissed {requestId,ticketId,binding,hop}
ssh.hostKeyDecision(requestId,ticketId,binding,accept) -> applied
onNativeChangedHostCandidate(role,hop,port,connection,target,host,classification,
    keyType,fingerprint,baseDigest,replacementDigest,candidateId,body,oldRecords) -> boolean
onNativeChangedHostReview(role,hop,port,connection,target,host,classification,
    keyType,fingerprint,baseDigest,replacementDigest) -> boolean
onNativeKexComplete(cycle,sessionDigest) -> boolean
```

### 3. Contracts

- The event contains public facts only: 1–128 ordered old rows, canonical SHA256 fingerprints,
  the actual managed relative source path and a remaining budget of 1–30000 ms. Reject extra,
  missing, malformed, stale or mismatched fields. The source label is never a caller-selected
  authority or replacement body. The actual offered fingerprint is **received**, not yet
  signature verified, when the user reviews it.
- Both NEW and CHANGED events carry the exact ticket's role-derived host, port and hop (0–8),
  while dismissal carries that same ticket's binding and hop. The Flutter owner tracks each
  review decision and waits for every submitted decision acknowledgement before handing off
  the connection. A later hop cannot replace an earlier pending acknowledgement, and a stale
  or mismatched ticket, binding or hop cannot dismiss or approve the current review. Until a
  factory supplies a protected request-bound route, CHANGED review still requires the direct
  form's exact endpoint; an event's self-reported hop does not authorize another endpoint.
- The native parser prepares the replacement from the same validated snapshot and exact
  endpoint; preserve unrelated byte spans. Shared, wildcard, marker, certificate,
  unsupported and unrepresentable matches cannot enter the replacement path. Do not merge
  or mutate another source merely because it contains an equal fingerprint.
- Explicit one-shot approval pins only that offered key provisionally on the original
  owner/role/target/view/connection and within the original deadlines. It grants neither
  credentials nor a durable proof. Verify the server signature and inbound NEWKEYS on the
  same KEX before making a source/identity/epoch/base-digest/replacement-bound once-only CAS.
  Only a successful guarded CAS and successful native completion publish formal host/KEX
  proofs and release the selected credential to the transport. Local key inspection and
  separately opted-in credential saving retain their existing earlier contracts.
- Keep the original connection future and protected route alive through terminal completion,
  including late cancellation, route destruction and a possible handoff. On cancellation,
  expiration, background, lock, bad signature, source change, duplicate callback or failed
  CAS, fail closed. If a write may have happened but its safe completion cannot be confirmed,
  lock and report `VAULT_COMMIT_UNCERTAIN`; never claim rollback or silently retry.
- An independent exact review of the selected credential and any opted-in keyboard-
  interactive continuation still follows the existing authentication policy and same-session
  PARTIAL-to-KBI contract. A changed-key approval never answers a server prompt.

### 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Ordinary exact old records, valid review and signature, guarded CAS succeeds | Update only actual source; continue original connection |
| Shared/pattern/marker/certificate/unsupported or over-limit matches | `SSH_HOST_TRUST_BLOCKED`; no unsafe replacement |
| `StrictHostKeyChecking` requires an already trusted key | `SSH_HOST_CHANGED_POLICY`; no review or write |
| Decline, stale/forged ticket, wrong binding or route covered | No approval, credential or pre-commit write |
| Invalid signature, stale source/view, revoked role or expired transport deadline | Stop; no formal proof or credential release |
| Write outcome or post-write delivery cannot safely be confirmed | `VAULT_COMMIT_UNCERTAIN`; lock and require reinspection |

### 5. Good / Base / Bad Cases

Good: review all old and received new fingerprints; approve once; validate the signed KEX;
commit the exact source candidate; continue the same selected key plus KBI. Base: existing
NEW approval and independent old-record management retain their own contracts. Bad: trust
Dart-provided key/path/body, mark approval as KEX proof, or update both independent sources.

### 6. Tests Required

Exercise real native same-socket KEX/signature/NEWKEYS with private key, password and KBI;
separately exercise the real encrypted vault CAS, source isolation, stale/forged binding,
once-only reads and cancellation/write/deadline races. Flutter tests cover exact event parsing,
explicit decisions, overflow/keyboard review, failed decision and late lifecycle delivery.
Record synthetic-native, real-vault/JVM, Flutter, APK, Android and human evidence separately;
none alone proves an actual Android end-to-end connection.

### 7. Wrong vs Correct

```text
Wrong: received changed fingerprint -> UI approves -> delete all matching sources -> send secret.
Correct: exact candidate -> explicit provisional pin -> signed KEX/NEWKEYS -> exact CAS
         -> native completion/proofs -> selected authentication on the original connection.
```

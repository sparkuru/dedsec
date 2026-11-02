# Managed SSH host-trust inspection and forgetting

## 1. Scope / Trigger

Apply to `SshHostTrustMaterial`, `SshHostTrustManagement`, `SshProfilesBridge`, native
`ssh_host_trust.[ch]` / JNI and their tests. Product scope and wire layout live in
[the contract](../../../design/ssh-host-trust-management.md). This operation forgets old local
ordinary records; it never supplies NEW evidence, approves an offered key or reconnects.

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

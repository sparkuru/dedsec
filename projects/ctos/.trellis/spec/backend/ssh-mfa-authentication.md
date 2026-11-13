# SSH single continuation to keyboard-interactive

## 1. Scope / Trigger

Apply to the approved password/publickey PARTIAL→KBI chains for manual/config targets.
Product scope and interface freeze live in [the contract](../../../design/ssh-mfa-authentication.md).
Local implementation, protocol/JVM/Flutter checks and normal/isolated APK closure passed;
dated results live in design/verification.md#20261104-ssh-mfa-local, not this contract.
Separately authorized AIO-3568J/API30 actual bridge/JNI/MFA/PTY and cleanup passed;
dated runtime evidence lives in design/verification.md#20261104-ssh-mfa-device.
Do not infer full parent1008, arbitrary MFA, other Android targets or human acceptance from it.

## 2. Signatures

```text
ssh.passwordConnect(..., continueKeyboardInteractive?: bool=false)
ssh.keyConnect(..., continueKeyboardInteractive?: bool=false)
ssh.configConnect(..., continueKeyboardInteractive?: bool=false)
ssh.configPasswordConnect(requestId,alias,binding,username,password,
    useSavedPassword,savePassword,continueKeyboardInteractive?,columns,rows)
SshNativeSession.continueKeyboardInteractiveAuth()
NativeBinding.continueKeyboardInteractiveAuth(handle,byte[] planReceipt) -> byte[] CSAPv1
ctos_ssh_native_client_continue_keyboard_interactive_auth(client,
    expected_plan_receipt[32],out_stage_receipt[92])
```

Standalone keyboard/configKeyboard operations accept no continuation permission.
Existing challenge/response APIs and CSKI/CSKR remain unchanged. RAM reconnect adds
configPassword and optional strict continuation bool; absent means false.

## 3. Contracts

Freeze the original target/user/identity/continuation plan before native open. Preserve the
same selection object/hash, owner/role/view/host/KEX/transport and original60s total deadline.
Runtime active method/stage is separate from immutable first-method selection. Selected
secret release remains once throughout the chain; stage2 has no stored-credential read.

CSASv1/v2 retain their original framing. Opted-in chains use CSASv3: original five
length-prefixed fields followed by BEu32 continuationKind3; flags0, original kind1 or4.
The plan digest includes the suffix. A v3 key with nonempty entry still uses its exact
phrase bundle; version2 alone cannot be the encrypted-key discriminator. Legacy versions
have no continuation authority. Reject unknown versions, kinds, suffixes and trailing bytes.

Only exact current stage1 PARTIAL plus frozen permission and current actual server list
containing the exact KBI token authorizes the single transition. Keep the first secret's
consumed marker set. A malformed/alien transition cannot acquire another owner's rights;
owner-authorized failures after mutation are terminal. Never rewind to SERVICE_READY,
rewrite callback selection, reconstruct TCP/KEX, or retry a partially mutated transition.

CSAPv1 is exactly92bytes BE: u32magic0x43534150, u16version1, u16flags0, u32stage2,
u32originalKind(1|4), u32activeKind3, 32byte planSHA256, u64originalKexCompletionCycle,
32byte originalSessionIdSHA256. Java compares every field against internally captured
proofs, seals a private local stage marker and wipes the temporary receipt bytes. No caller-supplied UI stage or server prompt text
is authority. Bind the mailbox guard to the validated stage plus original plan/host/KEX.

Retain only the current FAILURE's bounded1024byte valid method list; match exact tokens,
not substrings, and clear owned storage on consumption/close. Network partial boolean
follows nonzero=true. Packet60 dispatch uses the active method: password change-required
does not become KBI, and only active KBI may publish questions. KBI retains original prompt,
answer, round, UTF8 and owned-buffer limits from the standalone spec.

First-method complete SUCCESS follows the existing success path. First REJECTED, absent
server KBI, stage2 REJECTED/PARTIAL or requests for another method fail with no fallback,
loop or partial shell authority. Final USERAUTH_SUCCESS plus PTY/shell success is still the
only service handoff boundary; retire all original authentication material there.

CSCP/CSPPv3 add two BEu32 strict bools immediately after the KBI flag:
passwordThenKeyboardAllowed and publickeyThenKeyboardAllowed. Compute from actual Options:
both methods enabled/members and explicit first-method ordinal before KBI; absent preference
allows the explicitly selected chain. These are consumed relative-order predicates, not
claims about arbitrary negotiation. v1/v2 decode both false. Reject inconsistent true when
either base method is disabled. Check initial admission and final preparation before writes
or sockets; unsupported options remain refused rather than ignored.

configPassword uses actual source binding/effective target/user/trust and password policy.
It cannot reuse the generated manual CONFIG writer or switch trust collections. Saved
password stays exact actual host/port/user-bound; preserve imported CONFIG/key graph bytes
and existing guarded own-write/NEW-CAS behavior. Reconnect resolves new binding and current
target, beginning stage1 again; RAM hints never carry secret, receipt, answer or progress.

All KBI-capable UI attempts use common-vault stream readiness, current challenge guards,
protected route lifetime and original cleanup receipts, including password-save variants.
Back/cancel/background/lock/stream loss/disposal cancels the entire pending chain. Late
success after route removal must close the exact newly handed-off session before releasing
protection. No stage or round renews the original deadline.

## 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Missing continuation bool | Existing single-method behavior |
| Non-bool/unknown key or standalone permission | Strict request rejection before effects |
| Disabled/reversed config chain | SSH_MFA_POLICY, no persistence/socket |
| Stage1 complete SUCCESS | Normal final promotion, no claim of second-factor completion |
| Stage1 PARTIAL + approved chain + server KBI | One same-owner stage2 transition |
| Stage1 rejection or unavailable continuation | Fixed authentication failure, no alternative credential |
| Wrong hash/cycle/session/stage/owner or repeat transition | Refuse, no renewed or cross-owner authority |
| Stage2 rejection/PARTIAL | SSH_MFA_AUTH; no shell, no third method |
| Lock/expiry/cancel/source change | Original typed guard failure and original cleanup |
| Old/duplicate/cross-attempt answer | No response transmission or secret read |

## 5. Good / Base / Bad Cases

Good: explicitly select a config private key and allow continuation; the actual same server
accepts that key partially, asks for an OTP, then completes authentication and PTY.
Base: absent option retains key-only behavior; an opted-in server that accepts the first
method completely still opens normally. Bad: copy config host fields to the manual route,
rewrite selected credential kind after KEX, or treat PARTIAL as permission to start a shell.

## 6. Tests Required

- Actual encrypted native peers cover both chains, one transport/session identity, complete
  SUCCESS, server method lists, active-method packet60, zero/multi rounds, malformed frames,
  duplicate/wrong receipts, owner/view/expiry/cancel and no second selected-secret read.
- Actual Options/config framing covers absent/matching/reversed/missing/disabled methods,
  Include precedence, legacy false defaults and malformed/inconsistent v3 wire.
- Java fake-binding tests independently cover CSASv3 and exact CSAP, phase progression,
  mailbox current-stage checks, no callback rewrite, bridge strict parameters and config
  password preservation/target/own-write/cancellation plus current reconnect resolution.
- Flutter tests cover all entry variants/default-off/method switching/config retention,
  continued question lifecycle and late success cleanup including password save, strict
  reconnect and legacy flows; actual-font captures cover narrow/keyboard/landscape/large text.
- Build/lint/APK provenance, Android synthetic service and human review are separate gates.
  Compilation and mocks do not establish Android protocol/runtime acceptance.

## 7. Wrong vs Correct

Wrong: change `authenticationSelection` from key to KBI and clear the consumed marker so
the old owner can begin another method; or accept the Flutter checkbox as phase authority.

Correct: freeze a two-stage plan before open, preserve the original selection and consumed
secret, and consume one native proof-bound transition only after the actual server PARTIAL.

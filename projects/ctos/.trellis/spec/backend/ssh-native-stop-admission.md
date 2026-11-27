# SSH shared STOP admission at native effects

## 1. Scope / Trigger

A shared connection STOP can be published before native generation invalidation begins. An old native generation alone therefore cannot decide whether a later credential or wire effect is admissible. This contract orders the selected Java, JNI and native boundaries below against the original connection's STOP; it does not establish complete session, view, vault or trust-write revocation.

## 2. Signatures

Production `SshPasswordConnector` supplies its exact `SshSessionOwner.Cancellation` to each root and hop `SshNativeCallbackAdapter.bind(...)`. Its private `onNativeEffectAdmission()` performs only the same nonblocking `Cancellation.admitTerminalIo()` CAS used by STOP. `ctos_ssh_callback_client_claim_scoped_effect(...)` returns the claimed target's admission function and context to the owning native client; `ctos_ssh_native_client_admit_input_copy(...)` exposes an owner-thread admission for JNI credential input. `AppSecrets.SshStopCopyGuard` covers the selected Java copy; its child specialization `SshSecretCopyGuard` retains the existing native generation permit.

## 3. Contracts

The native client retains the exact callback target only while it owns the callback claim. Production JNI requires the Java admission method at callback creation. The admission method must never take application, callback, session or vault locks, invoke another JNI entry, or perform I/O. JNI invokes that method with the original attached environment and target; a false result or exception denies the effect. After native callback close succeeds the client clears its borrowed function and context before further cleanup.

For a guarded child or wire effect, acquire and check the existing native generation/deadline first, then perform the shared STOP CAS before the bounded effect. Denial releases the acquired native permit. For direct or otherwise unguarded wire operations, the same STOP CAS precedes the local effect without inventing a native-generation guarantee. A STOP that wins the CAS denies that effect; an effect admitted first may physically complete later. A single admission is not reusable for the next copy, packet or socket call.

The selected password/key paths admit the final Java credential copy, the JNI selected-byte copy and the native output copy. Explicit password and keyboard answers have separate Java and JNI input-copy admissions, and native password/KBI packet construction is admitted again. Native private-key passphrase copying, key-buffer creation, parsing, signing and packet construction also use separate STOP admissions. Wire sends, guarded relay transfers, selected authentication service/discovery and jump opening use their applicable original guards plus this STOP admission. Rejected owned buffers remain with their existing cleanup owners and are wiped by their original `finally` / cleanup paths.

Session liveness and changed-host diagnostic publication also consult the original shared STOP so an effect rejected by that STOP is not turned into an unrelated native I/O failure merely because independent session invalidation has not yet completed. Existing uncertain-commit precedence and independently admitted cleanup remain separate.

These admissions are specific to the shared product STOP; independent callback/session/vault cancellation, native unconfirmed cleanup, pre-admission temporary material, all persistent trust and credential writes, and creation-time/whole-chain cleanup remain separate obligations. The direct root path is not thereby covered by the child native generation fence. Native-only and old test-only JNI targets without an admission callback have no Java STOP owner; they are not evidence of product STOP protection. Standalone Java binding overloads create independent cancellation for isolated callers and must not substitute for the connector's shared instance.

## 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| STOP wins before an enumerated final admission | Deny that copy, packet, signature or wire effect; preserve exact cleanup owner |
| Effect wins before STOP | That one operation may finish later; later operations need fresh admission |
| Native generation permit acquired, then STOP wins | Deny and release that permit; do not infer callback or session destruction |
| JNI method is missing in a production callback or admission throws | Fail closed; never use a generic receiver as a product grant |
| Callback close succeeds, later client cleanup fails | Forget the borrowed target; retain the original native client for its own cleanup retry |
| Independent view/session/vault invalidation or trust commit races | Use its own contract; shared STOP CAS is not a replacement |

## 5. Good / Base / Bad Cases

Good: STOP is visible while native invalidation is blocked; the real product callback denies a later child password or wire permit. Base: an admitted copy finishes and the next effect independently rechecks. Bad: equate a native generation snapshot or an earlier Java liveness check with final shared STOP admission.

## 6. Tests Required

Use the real production Java/JNI adapter and native clients with barriers immediately before the relevant root and child effects; exercise STOP before and after admission, in-flight generation drain, JNI reentry and error, close/retry with exact ownership, and real isolated encrypted peers. Verify the no-STOP direct, KBI and private-key paths and retain Android/device results separately. The user authorized local verification on 2026-11-27; distinguish checks actually run from the earlier unverified implementation.

The [2026-11-27 local verification](../../../design/verification.md#20261127-ssh-shared-stop-verification) covers the exact shared production callback for root discovery and three child credential/packet boundaries, callback target isolation and exceptions, independent cleanup fixtures, and no-STOP product direct and single-jump password paths. It does not establish every in-flight admission race or product JNI KBI/private-key combination; native-only synthetic coverage and Android/device behavior remain separate.

## 7. Wrong vs Correct

Wrong: acquire the old global native generation and assume Java STOP cannot already be published. Correct: under the original target lifetime, order each selected effect with the same connection-specific STOP CAS while preserving the separate native generation and cleanup contracts.

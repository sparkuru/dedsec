# Research: native admission of immutable authentication selection metadata

- Query: Carry the frozen managed selection through session open into owned native metadata, retaining explicit username and an exact canonical receipt for future USERAUTH without sending authentication or releasing credentials.
- Scope: internal; Java session/binding/selection/adapter, JNI session registry, native client admission/cleanup, native role/view utilities and JVM/C tests. Only this report is writable; no build, Docker, device, network, real vault/account/credentials or subagent action.
- Date: 2026-10-18

2026-10-19 implementation follow-up: CSASv1 metadata/receipt is implemented and
formally reviewed. Focused53/fullfresh208 JVM and final ARM253155 checks pass,
along with lint/Release/v2/package checks. Main's final contract uses EACCES for
native absent selection and nonterminal SSH_AUTH_SELECTION in the Java getter;
suggested null/ENODATA alternatives below were not adopted. [Actual evidence](../../../../../../design/verification.md#2026-10-19--csas-native-selection-metadata-and-receipt).

## Findings

### Recommended narrow connection

Use the adapter's existing frozen immutable selection to create a nullable bounded canonical byte array during `SshNativeSession.open`. Do not add a caller-supplied arbitrary selection/wire argument to the public session open seam. Extend the single JNI `Native.open` declaration with that array, add matching borrowed wire/length fields to the native config, and validate/copy/hash the metadata before OpenSSH/socket ownership transfer. Existing null-selection callers remain KEX-only. Neither presence of this metadata nor the receipt enables service negotiation or authentication.

Add an explicitly invoked read-only receipt accessor for a successfully opened session. It permits roundtrip comparison without invoking any credential provider and without treating a digest as authorization. Do not automatically verify a returned receipt after native open has consumed the FD and then report an apparent open failure.

### Files found and patterns

| File / location | Finding |
| --- | --- |
| `SshAuthenticationSelection.java:10-18,35-62` | Exact role object, explicit username, typed resource and defensive public-blob copy already exist. Suitable owner of canonical encoding. |
| `SshNativeCallbackAdapter.java:559-575` | Explicit one-shot immutable selection installation; adapter owns the role token, clock and setup lock. |
| `SshNativeCallbackAdapter.java:667-699` | Native claim locks selection setup permanently even after a failed open. Selection generation must preserve this state machine. |
| `SshNativeCallbackAdapter.java:811-817` | Adapter exposes trusted role token/UID/deadline/host; encoding should be performed here from the frozen selection rather than session caller metadata. |
| `SshNativeSession.java:33-42,96-115` | NativeBinding old open and ProductionNativeBinding forwarding seam can gain a fail-closed default overload. |
| `SshNativeSession.java:130-140` | Only one native method named open today; retain one declaration to avoid JNI short-name ambiguity. |
| `SshNativeSession.java:187-233` | Claim, view token, bounded key/fingerprint copies and finally-wipe pattern; selection wire fits this same pre-open ownership flow. |
| `ssh_session_jni.c:127-148,180-257` | Bounded Java array copying and wipe/free cleanup; append optional selection array handling here. |
| `ssh_session_jni.c:88-124` | Session token reserve/publish and owner-thread slot lookup for a unique new receipt entrypoint. |
| `ssh_native_client.h:20-44` | Config accepts borrowed data and owned view borrow; add optional wire+length at end. |
| `ssh_native_client.c:48-75,577-715` | Owned native client, admission snapshot, setup and failed-open cleanup. Parser can remain in this file; no new upstream object required. |
| `ssh_native_client.c:881-943` | Callback-first close retry, view release, socket cleanup and tombstone destruction. Owned selection storage must follow these retries. |
| `ssh_role_bootstrap.h:42-55` | Snapshot includes exact token, hop, nonce, connection binding, PID/UID/deadline and role coordinates. |
| `ssh_file_view.c:37-59,70-94` | Existing strict scalar UTF-8 parser and exported `ctos_ssh_view_path`; UTF-8 routine currently omits C1 control rejection and is private. |
| `SshNativeSessionTest.java:91-160,249-294` | Existing fake binding and failed-open/claim tests should remain source-compatible. |
| `ssh_native_client_test.c:588-780,1000-1087,2161-2171` | Synthetic native fixture, zero-credential cleanup assertions, failed-open ownership/KEX-unbind and close-retry test hooks. |

Paths in this table are under `android/app/src/main/{java/im/majo/ctos,cpp}` or corresponding test source directories.

### Main-selected canonical wire

Main selected the following local contract during this investigation. Use big-endian network byte order everywhere; no Java modified UTF-8, native struct layout, enum ordinal, optional alternate representation or trailing bytes.

| Order | Field |
| --- | --- |
| 1 | u32 magic `0x43534153` (`CSAS`) |
| 2 | u16 version 1; u16 flags 0 |
| 3 | u64 positive native role token (≤INT64_MAX) |
| 4 | u32 hop |
| 5 | 32-byte role nonce; 32-byte connection binding |
| 6 | u32 credential kind, explicitly PASSWORD=1 or PRIVATE_KEY=4 |
| 7 | Five u32-length-prefixed byte fields: username, entry ID, private path, key identity, expected public-key blob |

Header is 88 bytes and all five field-length words add 20 bytes. Apply total maximum `VaultManifest.MAX_FILE_BYTES + 2048` (1 MiB + 2048); validate all individual bounds as well. A pointer-null/length-zero native pair is legacy absence. Non-null empty bytes, pointer/length inconsistency, unknown flags/version/kind, malformed/truncated/trailing or noncanonical typed shape are rejected.

- Username: strict scalar UTF-8, 1–255 bytes; reject U+0000–001F and U+007F–009F, unpaired surrogates/overlong encoding/invalid continuations/out-of-range Unicode. No trim, normalization or target parsing.
- PASSWORD: ID `[A-Za-z0-9_-]{1,128}`; path/keyIdentity/public blob lengths all exactly zero.
- PRIVATE_KEY: entry ID length exactly zero; private path/keyIdentity 1–512 strict UTF-8 bytes with no ISO control scalars; blob 1–MAX_FILE_BYTES bytes. Reuse `ctos_ssh_view_path(path,len,0)` after stricter text validation for exact existing relative path policy.
- All five fields are present, including zero-length inapplicable fields. Do not permit absent fields or collapse equivalent strings. Unicode bytes are retained exactly.
- Receipt is SHA-256 of the entire exact canonical wire, including role token, hop and both opaque bindings. It is a fingerprint of selected metadata, not host/KEX proof, secret release capability or authentication authority.
- Blob is still only an expected declaration: do not parse or compare keys in this slice.

### Admission and lifetime contract

1. Generate the wire only from `authenticationSelection` under adapter owner/liveness/selection checks. For legacy sessions return null. An adapter helper may encode its selection using its own `issued.token`, role and clock; caller cannot substitute another plan. Once claim setup starts the existing sticky setup lock remains true on every failure.
2. In Java, bound and clone encoded bytes for the binding call and wipe the exact supplied array in finally, alongside current key/fingerprint arrays. Wire is metadata, but keeping the same scratch hygiene prevents accidental retention and supports precise tests. Ensure a VaultException during encoding releases an outstanding opening claim exactly as view-token failure already does.
3. JNI treats null distinctly from a zero-length array. For a present array enforce total cap before allocating/copying. Wipe/free JNI scratch on every path, including reserve/view-borrow/C-open failure.
4. C config adds `const unsigned char *authentication_selection_wire` and `size_t authentication_selection_length`. Validate optional pair, header, every length/shape/text rule and total consumption. Use cursor subtraction checks (`length <= remaining`) rather than unchecked offset addition. Length-prefixed username must never be decoded with strlen or modified UTF-8.
5. Compare parsed role token/hop/nonce/connectionBinding byte-exactly with the native live role snapshot already obtained during admission. Existing role snapshot checks establish target/host/view/UID/PID/deadline, so duplicating all those strings in CSAS is unnecessary. Wrong exact role binding is admission denial even if username/resource fields are otherwise valid.
6. Perform all new fallible parse, allocation, copy and SHA-256 work before duplicating/attaching the socket and declaring success. Failure must leave original FD, callback and view borrow caller-owned, exactly like current C failure handling. Parser/hash failure must not invoke host/KEX/credential target callbacks.
7. Own an immutable canonical wire allocation plus receipt inside the native client. Parsed offsets/lengths into that allocation can retain explicit username/resource/blob for future auth without another large allocation; do not keep pointers into JNI/caller scratch. If fixed username fields are copied, preserve encoded bytes and explicit lengths.
8. On failed open wipe/free any allocation before client free. On close failures before all existing ownership releases succeed, keep the whole selection available for retry; no partially freed dangling offsets. Once close succeeds wipe/free owned wire and receipt, then tombstone destroy wipes fixed metadata. Do not postpone a dynamic wire allocation's free until struct-only destroy.

### Binding compatibility and JNI names

Keep `NativeBinding.open(old arguments)` as the existing abstract method so old test bindings compile. Add a default `open(old arguments, byte[] wire)` that delegates to old open only for null wire and throws IOException for any present wire. It must not silently drop selected metadata. ProductionNativeBinding overrides the new overload to call the one changed `Native.open` JNI declaration. Its old method may call that same native declaration with null.

A separate unique `Native.authenticationSelectionReceipt(long handle)` (name illustrative) avoids overloaded-native short-name lookup ambiguity and adds one session JNI export. Packaging checks currently expect five session exports; update that expectation to six when this method is implemented. Java interface accessor can default to null for legacy fake compatibility; selected mode must treat a missing receipt as failure rather than downgrade. Existing fixture source using old binding remains valid through default-null behavior.

### Read-only accessor and error recommendations

Suggested C form:

```c
int ctos_ssh_native_client_authentication_receipt(
    struct ctos_ssh_native_client *client, unsigned char *out, size_t capacity);
```

- Require exact 32-byte output capacity, wipe valid bounded output before failure, and never return internal pointers. C null/invalid output → EINVAL; closed client → ENOENT; legacy absent selection → ENODATA (JNI maps only this case to null). Main may choose another explicit absent representation, but distinguish absence from malformed selection and generic native error.
- Malformed wire/UTF-8/shape/length → EINVAL; exact role token/hop/nonce/binding mismatch → EACCES; allocation → ENOMEM; hash failure → EIO; preserve operational syscall errors.
- Getter is allowed before KEX and after KEX complete; it requires owner/callback environment/live role/view/deadline, no cancelled/failed/closed session. `ctos_ssh_callback_check_live` supplies owner/environment/cancel validation without calling host/KEX/credential providers; do not use a credential callback as a liveness probe. Define whether getter rejection merely rejects this read or terminalizes client; no network traffic should be queued by the getter.
- Existing Java `requireOpenLocked()` rejects `complete` (`SshNativeSession.java:358-361`). Do not reuse it unchanged for a metadata getter intended to work after the KEX milestone. Use a read-only guard that rejects nativeClosed/destroyed/terminal failure/cancellation while allowing complete.
- Java explicit `authenticationSelectionReceipt()` validates native 32-byte output against its expected SHA-256 and returns a defensive copy; legacy absence must remain null. Mismatch/missing receipt for a selected session fails closed, wipes scratch and leaves cleanup possible. Do not perform this extra native call inside successful open or misrepresent its failure as caller still owning the FD.
- JNI uses existing owner-thread `lock_session`, asks C to copy the digest, creates a new 32-byte Java array and wipes stack scratch. It must release the slot mutex and local references on all paths. No public wire/body accessor is necessary merely to prove the exact full wire crossed the boundary: matching full-wire digest is sufficient.

### Existing publish caveat

`ssh_session_jni.c:227-234` currently performs `publish_session` after native open succeeds. A theoretical publish failure closes an already owned native client and then returns an open exception, so the broad Java statement that every possible failed JNI open preserves caller FD is stronger than that rare existing branch. Do not add any new post-ownership fallible selection check to this sequence. New selection work can preserve all normal admission-failure guarantees without enlarging this slice into a session registry redesign. If main requires unconditional failure atomicity here, reserve/publish ownership needs a separate explicit fix and test rather than claiming the selection parser solved it.

### Focused validation plan

- Java canonical encoding tests: fixed expected bytes/header offsets, big-endian role/hop/nonces/kind, all five lengths and payloads; password/private-key shapes, Unicode exact preservation, max blob cap, deterministic digest and defensive copies.
- Java session fake captures wire reference/clone: adapter-selected metadata reaches new overload, input array wiped after success and IOException; null legacy reaches old fake; selected metadata with only old fake fails closed and releases claim; failed claim/open does not permit reselection. Old ownership/close tests remain.
- Java explicit receipt tests: exact expected SHA-256, defensive result, selected missing/wrong-size/mismatched receipt, legacy null, owner thread/cancel/expiry/closed failure, usable before KEX and after complete. No extra open-time receipt call.
- Native parser/admission matrix: password/private-key accepted and expected digest exact; caller input mutation after open cannot alter owned receipt; null legacy accepted; zero/non-null, null/nonzero, caps, every truncation boundary, trailing bytes, version/flags/kind, typed mixed fields, oversized declared lengths and overflow-style lengths reject.
- Native UTF-8 matrix: valid multibyte/supplementary username, byte limit, overlong/invalid/truncated sequences, encoded surrogate, >U+10FFFF, ASCII/C1 controls; path traversal/absolute/backslash/glob/9 segments and strict keyIdentity bounds.
- Native role replay matrix: independently mutate token, hop, nonce, connectionBinding; wrong binding rejects before host/KEX/credential effects and leaves FD/callback/view caller-owned. Retry correct wire on same resources succeeds.
- Native receipt tests before KEX and after real synthetic KEX/service milestones where appropriate; old KEX-only behavior retained, zero credential calls throughout. Accessor must not create packets or alter service state. Wrong owner/environment/cancel/stale role/view/deadline and retryable close covered using existing fixture helpers.
- Test close retry preserves metadata until success, success clears owned storage, destroy only closed tombstone. Use existing `test_failed_open_releases_kex_role` and `test_close_retry_order` fixtures rather than new external runtime.
- Main runs targeted JVM + strict native build, actual ARM QEMU/Bionic native suite, then appropriate normal lint/Release/package checks. No device needed for local acceptance; JNI compile/link does not become Android runtime evidence.

## Related specs

- `.trellis/spec/backend/ssh-native.md`: current managed selection, native C client ownership, callback liveness, opaque session and view-borrow contracts.
- Active task PRD/design: metadata-only continuation, legacy KEX-only compatibility and full authentication still open.
- `research/auth-selection-20261018.md`: managed exact-object receipt and passphrase limitations remain valid; a serialized digest does not replace existing effect guards.

## External references

None. Current project source is sufficient; no upstream authentication research or live documentation lookup was repeated.

## Caveats / Not Found

- This report produces no implementation or runtime result and modifies only this research file.
- Metadata receipt is not user authentication or authority to release a password/private key; existing JNI credential callback remains kind-only and cannot yet satisfy the managed selected overload.
- Actual native auth still needs a separately designed receipt-bound callback, current service/KEX checks and private/public-key parsing/matching.
- Existing UTF-8 path validator needs added C1 scalar control rejection for Java-equivalent selection validation.
- Existing JNI post-open publish failure is a rare ownership caveat; all newly introduced fallible selection work should precede native ownership transfer.

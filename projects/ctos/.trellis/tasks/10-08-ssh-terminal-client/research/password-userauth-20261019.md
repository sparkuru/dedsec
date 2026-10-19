# Research: explicit native password USERAUTH protocol

- Query: Implement the smallest C-only password authentication transition using retained CSAS username and the existing one-shot proof-bound credential getter, preserving all legacy milestones and ownership.
- Scope: internal; current native client/API/tests and locally pinned OpenSSH 10.6p1 sources. Only this report is writable. No build, device, network, real vault/account/credentials, private-key parsing or subagent action.
- Date: 2026-10-19

Main subsequently froze the exact contract in `ssh-native.md`: DISCONNECT uses
bounded parsing then ECONNRESET, packet serialization failures map to EIO, and
unsupported IGNORE/DEBUG/UNIMPLEMENTED remain a recorded compatibility limit.
That normative contract takes precedence over recommendations below. This report
contains source findings; execution evidence belongs in `design/verification.md`.

## Findings

### Recommended and main-selected scope

Use exactly three implementation/test files: `android/app/src/main/cpp/ssh_native_client.h`, `ssh_native_client.c`, and `android/app/src/test/cpp/ssh_native_client_test.c`. No new Java/JNI entrypoint, callback contract, upstream patch/object or stock high-level caller is needed. Continue to distinguish initial KEX complete, ssh-userauth service ready and an explicit password-auth result.

Main selected this contract during research:

```c
enum ctos_ssh_native_client_password_auth_result {
    CTOS_SSH_PASSWORD_AUTH_NOT_STARTED = 0,
    CTOS_SSH_PASSWORD_AUTH_PENDING = 1,
    CTOS_SSH_PASSWORD_AUTH_SUCCESS = 2,
    CTOS_SSH_PASSWORD_AUTH_REJECTED = 3,
    CTOS_SSH_PASSWORD_AUTH_PARTIAL = 4,
    CTOS_SSH_PASSWORD_AUTH_CHANGE_REQUIRED = 5
};
int ctos_ssh_native_client_begin_password_auth(struct ctos_ssh_native_client *);
int ctos_ssh_native_client_password_auth_result(
    const struct ctos_ssh_native_client *, enum ctos_ssh_native_client_password_auth_result *);
```

These signatures/numeric values now match the main-frozen seven-part password spec. The result accessor resets a non-null output to NOT_STARTED before errors, validates live owner/view/current proof without mutating client, and is available only for PASSWORD-selected + READY sessions. It makes no provider/I/O call. Normal application results are terminal for this auth attempt but do not mark client failed; protocol/I/O/cancel/proof errors mark failed and accessor errors. All terminal results stop further step/begin; no retry, alternate method, password change, channel or authenticated product flow follows automatically.

Password protocol cap is **8192 bytes**, independent of generic selected handoff cap 1 MiB. Reject any NUL after provider release with EINVAL/terminal and no authentication packet; do not truncate. Other bytes remain byte-exact through `sshpkt_put_string`; do not convert to String or newly reinterpret UTF-8. This is a scoped compatibility policy, not a claim arbitrary binary passwords interoperate with stock sshd.

### Source inventory and precise behavior

All upstream locations below refer to `build/ssh-native-g1/stock-run.lOHF9Oai/sources/openssh-10.6p1/`.

| Source | Finding |
| --- | --- |
| `sshconnect2.c:1042-1075` | Stock password request is USERAUTH_REQUEST, username, service, method, false change flag, password, add_padding(64), send; scratch password is freezero'd. Static handler reads global options/prompts and is not suitable for reuse. |
| `sshconnect2.c:570-604,622-653` | Banner parsing, success and failure/partial reference behavior. Stock failure automatically selects another method; this slice deliberately stops. |
| `sshconnect2.c:1082-1104` | Type60 password-change request carries prompt and language; stock prompts and sends another password request. This slice only reports CHANGE_REQUIRED. |
| `ssh2.h:82-95,118-127` | DISCONNECT1, IGNORE2, SERVICE_ACCEPT6, EXT_INFO7, KEX20..49, USERAUTH_REQUEST50/FAILURE51/SUCCESS52/BANNER53, method-specific type60. |
| `packet.h:106-113,173-206` | Public rekeying query, packet put/get/string-direct/end and padding APIs; no public current outgoing-packet reset/getter. |
| `packet.c:2864-2873` | sshpkt_start resets current outgoing sshbuf, then writes only a six-byte frame/type header; it does not send. |
| `packet.c:2938-2944,2968-2972` | sshpkt_send delegates to packet_send2; add_padding sets extra_pad. |
| `packet.c:1225-1388` | Wrapped send constructs padding, MAC/encryption into output and resets outgoing plaintext after successful encryption/packet accounting. |
| `packet.c:1404-1485` | Rekeying can move current plaintext to an internal outgoing queue and replace current packet buffer; this is why a reset alone is not a queued-packet wipe. |
| `packet.c:1118-1155` | Soft rekey is disabled while pre-auth; hard pre-auth limits fail with NEED_REKEY. |
| `packet.c:1173-1205,1840-1843` | Received USERAUTH_SUCCESS automatically sets after_authentication and activates delayed compression before caller's semantic handler runs. |
| `packet.c:98,1660-1698` | Hard encrypted incoming packet cap is 256 KiB. `ssh_packet_set_maxsize` does not change this compile-time cap. |
| `packet.c:683-707` | Close frees input/output/current outgoing/incoming and every queued packet payload. |
| `sshbuf.c:161-189,196-215` | sshbuf_free uses freezero for owned allocation; reset wipes full owned allocation after resetting offsets (readonly/shared exceptions do not describe normal outgoing buffer). |
| `sshbuf.c:392-411` | consume advances offsets and does not wipe the consumed bytes; output consume is not itself a plaintext cleanup primitive. |
| `ssh_api.c:253-290` | packet_next calls read_poll2, dispatches only KEX20..49 and returns other packet types; it does not use read_poll_seqnr's implicit DISCONNECT/IGNORE/DEBUG handling. |
| `auth2-passwd.c:60-72` | Stock server parses password as cstring and subsequently passes a C-string to authentication. |
| `sshbuf-getput-basic.c:269-302` | get_cstring rejects interior NUL but tolerates a single NUL at final byte; rejecting every NUL here is intentionally stricter and avoids tail truncation. |
| `Makefile.in:80-111,116-118` | Existing libssh closure contains ssh_api/packet/sshbuf/kex/dispatch primitives; sshconnect2.o remains a stock SSHOBJS high-level object, not needed. |

Current project source patterns:

- `ssh_native_client.c:174-225` parses CSAS and owns copied wire/kind. Retain username offset and length when copying; field0 is already strict validated. Point into the owned clone by offset, never the caller/JNI array. No second partial parser or external username argument is needed.
- `:601-642,649-707` saves successful completion cycle/digest and validates current role/view/guard/session digest.
- `:710-759` selected getter performs preflight allocation, one-shot consumption, proof-bound callback, post-provider proof validation, scratch-copy/wipe and terminal failure.
- `:766-800` service phase uses request-pending/await/ready and disables KEX dispatch20..49.
- `:945-1078` step currently stops at service READY and must resume only when new explicit password state is pending, retaining old stop behavior otherwise.
- `ssh_native_client_test.c:1099-1198` synthetic encrypted peer parses service and sends controlled replies; `:1507-1628` creates KNOWN/NEW and selected service fixtures with byte-budget fragmentation and no legacy credential calls.

### Password bound and byte policy

`android/app/src/main/java/im/majo/ctos/VaultManifest.java:98` decodes every entry secret with maximum8192, and `:179` rejects empty or >8192-byte entry secrets. SSH_PASSWORD is one of those entry types. Thus a protocol cap8192 matches actual manifest password storage today while leaving generic private-key handoff at 1 MiB.

An initial proposal to allow a 1 MiB password packet would exceed pinned incoming PACKET_MAX_SIZE256KiB. Even a lower 64KiB policy would be an arbitrary larger protocol cap than current vault password entries require. Main chose8192. Pass this capacity to the existing getter, which already returns EOVERFLOW and consumes/terminalizes if provider returns more.

Use memchr over the returned explicit length to reject **any** NUL; neither strlen nor silent prefix extraction is acceptable. `sshpkt_put_string(ssh, bytes, len)` safely encodes length and exact non-NUL byte sequence. Password is already typed managed bytes; do not normalize, trim or convert. UTF-8 username validation happened in CSAS admission; use its retained length via put_string as well.

### Explicit begin and one-shot lifetime

1. Null→EINVAL, closed→ENOENT, failed/repeated auth or previously consumed selected getter→EALREADY. Check owner/environment before mutable state; EPERM cannot consume/poison owner. Require PASSWORD selection + READY service and current unique KEX proof; premature/wrong-kind→EACCES with no credential effect or packet.
2. Verify `ssh_packet_is_rekeying(ssh)==0` and current output queue empty before constructing a password request. Existing service READY should already imply own service output drained; do not assume arbitrary future output remains absent. A rekeying invariant violation is terminal before provider invocation, not permission to enqueue plaintext into a rekey queue.
3. Allocate outer heap scratch8192 as preflight. If allocation fails before getter, ENOMEM may retry. Call the existing selected getter once with cap8192; it performs its own bounded private scratch and authoritative attempt consumption. Do not reset its bit or implement a parallel credential request. If a caller already used the getter, begin must not send another request.
4. On getter success, validate returned length and reject any NUL; this is after authorization/one-shot consumption and therefore terminal on error. Repeat live current proof/no-rekey checks before serialization.
5. Start USERAUTH_REQUEST50, retained username bytes, fixed service `ssh-connection`, fixed method `password`, u8 change=0, exact password string, add_padding64, then send. Wipe/free outer scratch on every return, immediately after send/failed construction; no plaintext secret field persists in native client.
6. A successful begin queues an encrypted request and sets password PENDING/request-pending, but sends no socket bytes itself. Existing bounded step drains output; transition to await-response only when own output reaches zero. Success is not visible merely because the packet was queued.
7. Packet put/send/cleanup failure terminalizes client, attempt remains consumed. Preserve original precise error where possible; never retry or send fallback credentials.

### Sensitive packet cleanup and rekey queue risk

No dedicated public outgoing reset exists in packet.h. Calling `sshpkt_start(ssh, SSH2_MSG_IGNORE)` on any incomplete packet construction/send failure is a valid narrow reset: it invokes sshbuf_reset **before** attempting its six-byte allocation/write, so even a failing return has cleared old current plaintext. **Do not call sshpkt_send on this cleanup header.** Correct constant is SSH2_MSG_IGNORE, not undefined SSH_MSG_IGNORE. Preserve original packet failure errno.

On normal pre-auth send success, `ssh_packet_send2_wrapped:1374` resets the outgoing packet, and owned sshbuf reset wipes its full allocation. Ciphertext remains in transport output for bounded socket send; consume does not wipe but contains no plaintext password in this supported path.

Important restriction: ssh_packet_send2 can enqueue a plaintext payload when already rekeying. Resetting current outgoing buffer would not clear that separate queued payload. Pre-auth soft need_rekeying returns0 (`packet.c:1118`), so if begin checks not-rekeying and the original owner does not reenter, normal password send cannot enter this queue. Preserve hard NEED_REKEY error and stop. Do not claim reset cleans all queued payloads. Full close frees/wipes them; failed close before callback release deliberately retains retryable state, so avoiding the queue before serializing secrets is necessary.

On receive, USERAUTH_SUCCESS itself enables compression and may trigger post-auth rekey checks before application validation. A malformed/early success must still terminalize immediately; no further request may be constructed on that ssh object. Successful auth parser does not need ssh_packet_set_authenticated: receive calls ssh_packet_enable_delayed_compress at packet.c:1839–1840, and that function itself sets state->after_authentication=1 at :1183 before enabling COMP_DELAYED in both modes. The separate setter at :2386–2388 merely sets the same flag and would not activate compression on its own. This slice stops at result; compression/channel interoperability is not automatically covered by a synthetic default-none exchange.

### Response and bounded-step policy

Main selected valid final replies only in await-response, after request output drain. A success/failure/change response before then is EPROTO/terminal, even if upstream has already changed internal compression state. BANNER may be parsed in request-pending or await phase.

| Packet | Required interpretation |
| --- | --- |
| SUCCESS52 | Empty payload only (`sshpkt_get_end`); publish SUCCESS after final live/current-proof check and output drain. |
| FAILURE51 | Bounded name-list string≤1024 and one u8 partial, exact end. Empty list allowed; otherwise canonical printable ASCII names separated by single commas, no empty member/whitespace/control/non-ASCII. Any nonzero partial means PARTIAL, zero means REJECTED. Discard list; no next method. |
| PASSWD_CHANGEREQ60 | Two bounded strings prompt≤8192/language≤128, exact end. Discard and publish CHANGE_REQUIRED; do not prompt or send new password. Type60 is interpreted only because password method is active. |
| BANNER53 | Message≤8192/language≤128, exact end. At most16 banners per auth attempt; no display/log/retention. Reject oversized/truncated/trailing/count-exceeded terminal. |
| EXT_INFO7 | Preserve existing pinned kex_input_ext_info validation while password pending/await; malformed→EPROTO. No new extension authority. Once a valid terminal result stops stepping, later packets are not parsed. |
| KEX20..49 | Dispatch remains disabled from service opt-in, returned packet rejected EPROTO; no silent rekey/current-proof reuse. |
| DISCONNECT1 | packet_next returns it directly. Parse u32 reason plus bounded description/language and exact end, discard; terminal transport error (recommend ECONNRESET). Malformed→EPROTO. Do not rely on read_poll_seqnr's implicit disconnect handler/logging. |
| Other | Unexpected service accept, USERAUTH_REQUEST, method-specific/connection packet, etc. → EPROTO terminal. |

Use borrowed `sshpkt_get_string_direct` spans only during current packet handling; validate length and grammar without copying remote text. No remote prompt, methods list or banner participates in credential selection. For text being discarded, no display-oriented decoding is required; validate protocol framing/caps and never retain/log raw bytes.

For valid final replies, keep an internal final-result-pending value until output empty and post-parse owner/live view/deadline/current guard/session digest checks pass. Normally await-response already has output empty, but do not publish success before the common output/completion path. Read/parse can fail or cause upstream state changes; no ready flag should precede those checks. Final valid REJECTED/PARTIAL/CHANGE_REQUIRED likewise stop transport without setting generic client failed. Protocol/cancel/EOF errors set failed and status accessor errors; later begin/step EALREADY, close retry remains.

The result accessor must use const/read-only client checks, not the existing selected_credential_is_live/service_is_live directly, since those terminalize on errors. Reuse/factor validation logic carefully. As with the existing const receipt accessor, no-provider callback_check_live may enforce callback slot liveness internally, but the accessor itself must not mutate native client result/failed/attempt state or issue I/O/provider calls. PASSWORD+READY permits NOT_STARTED read before begin; other states return EACCES.

### Test plan using real encrypted synthetic peer

Extend the existing peer and fixture, not a state-only mock:

1. For both KNOWN and NEW/CAS, complete actual KEX/service, call explicit begin password, parse the decrypted USERAUTH_REQUEST on pinned server and verify username exact retained Unicode bytes, service ssh-connection, method password, change0, exact selected password, exact end and one request. Keep source/provider callback count1; old credential targets0.
2. Cover 1-byte and8192-byte passwords, multibyte/raw non-NUL bytes, exact explicit length and64-byte padding behavior where observable through encrypted packet length. NUL interior/final/first and8193 provider output reject before an authentication packet and permanently consume the attempt. Fixture passwords must be fixed synthetic bytes, promptly wiped.
3. Prior standalone getter success→begin EALREADY/no wire; begin→getter EALREADY. Pre-ready/wrong-kind/missing selection, wrong thread, invalid mode and allocation-preflight errors do not cause provider packets. Avoid exposing internal pointers merely to inject ENOMEM/hash/cycle corruption.
4. Drive request through small step budgets/EAGAIN/short writes. Server response after full decrypt reaches only one final result; response while local output still pending rejects EPROTO and never publishes SUCCESS. Preserve step byte accounting, KEX complete and service-ready semantics, old no-begin KEX/service stop tests.
5. Real encrypted finals: empty SUCCESS, FAILURE reject with empty/nonempty lists, nonzero partial→PARTIAL, CHANGE_REQUIRED prompt/language; no method retry or password-change request. Valid results stop further begin/step; status reads remain live/read-only; status output deterministic on errors.
6. Response errors: nonempty success, truncated/oversized/trailing failure/change/banner, malformed name lists,17 banners, early final, rekey/NEWKEYS, unknown type, disconnect malformed/valid, EXT_INFO malformed. Do not assert stock protocol behavior stricter than the frozen policies above.
7. Cancellation/deadline/view revoke/role stale before begin, during provider and during pending reply must leave no success or retained scratch. Reuse current selected callback actions for provider-time failure. EOF midrequest/midreply terminal; close failure and retry preserve ownership and wipe native selection/session buffers on success.
8. Verify the peer receives no duplicate request after valid rejected/partial/change or local error, and no USERAUTH from unchanged independent getter tests. Split fixture expected counters instead of weakening all no-credential assertions.
9. Main executes strict native build, actual ARM QEMU/Bionic suite and appropriate normal gate/archive83/forbidden high-level symbols/six JNI exports/package checks. No new Android runtime evidence is produced merely by these C tests; no device/account operation is needed.

## Related specs

- `.trellis/spec/backend/ssh-native.md`: latest proof-bound selected getter, CSAS admission, service-ready/KEX guard, bounded step and retryable close contracts.
- Current task PRD/design/implementation plan: selected handoff completed, actual password protocol still open before this slice.
- `research/selected-credential-callback-20261019.md`: native scratch, current proof and no-downgrade guarantees already implemented; do not replace with a new weaker credential path.

## External references

Pinned local upstream OpenSSH10.6p1 source only; no live web/upstream branch, account or network research. Source provenance and immutable G1 baseline are documented by existing production runner/task research.

## Caveats / Main Decisions Still Needed

- Main has frozen enum/API names, numeric result values,8192/NUL policy, result-accessor behavior and banner/method caps in ssh-native.md. No implementation blocker found. DISCONNECT terminal errno is still recommended ECONNRESET; keep its bounded parse/error mapping explicit.
- No public packet reset API wipes a queued rekey payload; enforce no-rekey invariant before serialization and do not overstate current-buffer reset as complete transport queue erasure.
- Received early/malformed SUCCESS may already activate delayed compression upstream; terminalizing is mandatory, rollback is not available through an authentication-state setter.
- Supporting binary SSH string framing is not stock server password interoperability. Main chose reject all NUL and no additional text transformation.
- No code/build/runtime result was produced by this researcher. Private-key auth, passphrase/KBI, channels/PTY, Java/JNI begin/status exposure, Android full auth and product AC remain outside this proposed C slice.

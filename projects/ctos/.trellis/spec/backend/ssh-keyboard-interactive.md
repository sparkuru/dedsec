# SSH keyboard-interactive authentication

## 1. Scope / Trigger

Apply to the explicit standalone KBI method in native transport, selected authentication,
JNI/Java session/connector/mailbox/bridge, config plan and typed Flutter input. Product scope
and exact wire live in [the contract](../../../design/ssh-keyboard-interactive.md).
Local source/tests/build audit and authorized isolated Android runtime passed; human acceptance
remains pending. Android RAM reconnect metadata is not an actual Flutter reconnect-click test.
This spec is not a device acceptance receipt; dated evidence lives in design/verification.md.
Do not confuse KBI's multiple prompt rounds with chained publickey/password→KBI MFA.

## 2. Signatures

```text
SshNativeSession.Native.beginKeyboardInteractiveAuth(handle)
SshNativeSession.Native.keyboardInteractiveAuthResult(handle) -> int
SshNativeSession.Native.keyboardInteractiveChallenge(handle) -> byte[] CSKIv1
SshNativeSession.Native.respondKeyboardInteractive(handle, byte[] CSKRv1)
ssh.keyboardConnect(requestId,host,port,username,columns,rows)
ssh.configKeyboardConnect(requestId,alias,binding,username,columns,rows)
ssh.keyboardResponse(requestId,ticketId,binding,answers:[string]) -> {accepted:bool}
```

Challenge event type is `keyboardChallenge`; fields are requestId, ticketId, binding, round,
name, instruction, prompts[{text,echo}], remainingMillis. Dismissal is
`keyboardChallengeDismissed` with requestId/ticketId. Use the original connection result,
one current attempt and the existing sensitive consumer, not another global listener.

## 3. Contracts

Only actual verified host/KEX and current owner/role/view plus selected CSASkind3 authorize
the KBI method. CSASv1kind3 has username and empty entry/path/identity/public-key fields.
Never route it through generic kind3's stored SSH_PASSWORD lookup. No saved answer or TOTP
read, automatic credential reuse, prompt guessing or fallback to another method is permitted.

Interpret packet60 by the active method: KBI INFO_REQUEST vs password change-required.
KBI sends the fixed keyboard-interactive request with empty language/submethods. Keep at
most one unanswered round. Native owns and validates the current round and consumes its
complete equal-count response exactly once; a Flutter ticket is not native protocol authority.
Only actual USERAUTH_SUCCESS grants authentication; PARTIAL grants no shell or service handoff.
An external SSH boolean follows RFC4251: zero=false and every nonzero value=true. Normalize
echo to canonical0/1 when emitting CSKI; internal CSKI/JVM/Dart validation stays strict0/1.
Do not reject a protocol-valid noncanonical true byte merely because our internal wire is strict.

Strict owned CSKI/CSKR BEv1 framing is in the contract. Limits:16 rounds,0–16 prompts per
round,4096 UTF8bytes per text/answer field,32768 total bytes per wire. Name/instruction may
be empty, prompts may not; empty answers are valid. Reject malformed Unicode/tail/length/
flags/count/echo rather than normalize it into another input. Preserve response order.
Native/JNI/Java temporary byte copies are owned and wiped on all exits. Dart/Java strings
cannot promise in-place erasure; clear references/controllers and prohibit persistence/logging.
Response UTF8 encoding/validation uses explicit bounded byte/char scratch and CoderResult
checks with finally wiping. Convenience encode/decode allocators can hide partial temporary
output when malformed input follows a secret prefix. Do not construct answer Strings while
validating CSKR or rely only on wiping success-path buffers.

Keep the original60s authentication deadline, role/KEX/view/epoch/source/consumer guards;
neither posting a challenge nor answering renews it. Owner publishes a public challenge
snapshot and waits through a bounded mailbox. UI-thread responses only validate and place
owned bounded bytes for the original owner to consume; native session entry stays on owner.
Bind opaque ticket/binding to attempt, method, role/KEX and round. Replays, cross-attempt
answers and delayed delivery cannot release a secret or revive a retired round.

Back/cancel/background/lock/stream loss/route destruction retire pending prompts, clear
answers and await original terminal cleanup. Sensitive protection outlives an in-flight
connection response; successful handoff and failed/cancelled cleanup keep their prior rules.
Untrusted remote name/instruction/prompt content is body text. Filter control characters
while preserving useful newlines; it cannot become a lifecycle event, local command or URL.

CSCP/CSPPv2 add the KBI flag immediately after passwordAuthentication; oldv1 means no KBI
authority. The flag comes from actual fixed Options, including KbdInteractiveAuthentication
and allowed PreferredAuthentications membership. Explicit selection does not try methods in
sequence. Non-consumed submethod policy and unrelated options remain explicitly unsupported.
Config mode uses current source binding and actual effective host/user/port/trust path both
before admission and final preparation, without requiring an unrelated private key.

RAM-only reconnect modes keyboard/configKeyboard retain public target/alias, never old
binding/ticket/challenge/answer. Reconnect resolves current config and performs fresh host
and authentication checks. It never submits old answers or silently changes authentication.

## 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Zero prompts | Display name/instruction, explicit continue sends count0 |
| Empty answer | Send empty string at its original position |
| Multiple rounds | Fresh round/ticket; old answer has no effect |
| Malformed/oversized/duplicate outstanding request | Fail and clean up, no partial answer |
| Host negative classification | Preserve refusal, publish/send no KBI answer |
| Disabled/missing effective KBI policy | Refuse without changing method/target |
| Partial or authentication rejection | No PTY/shell authority; fixed recoverable error |
| Lock/background/expiry/cancel/old source | Original typed guard error, no renewed deadline |
| Submitted answer after cancellation/replay | accepted=false or existing typed guard failure, no send |

## 5. Good / Base / Bad Cases

Good: actual trusted server asks visible username then masked OTP; user submits both,
responds to a second round, and only actual SUCCESS+PTY yields the existing terminal.
Base: a zero-prompt explanatory round requires explicit continue; an empty answer remains
empty. Bad: parse prompt text to auto-send saved passwords, treat PARTIAL as SUCCESS, or
reuse a previous attempt's response after reconnect.

## 6. Required Tests

Actual encrypted native peer verifies method/request and complete response bytes, multiple
rounds/zero/empty/echo, malformed/count/limit/state/duplicate/owner/role/view failures,
negative-host zero-answer and SUCCESS-only shell. Config tests use actual Options/wire policy,
not invented Java flags. Java tests cover strict wire/ownership/mailbox and original request
cleanup, stale/cross-attempt/replay/timeout/epoch/stream failure plus password/privatekey
regression. Flutter tests cover strict events/answers, both entry paths, protected multi-field
form and late/back/background/forced disposal. Actual-font layout, builds/lint/signing, Android
JNI/protocol and human feedback remain separately labeled gates.

## 7. Wrong vs Correct

Wrong: adding kind3 to generic credential release and using a stored password for any prompt;
or treating everypacket60 as a challenge and handing PARTIAL to a shell.

Correct: bind explicit kind3 to the exact live method/KEX, present its real bounded current
challenge, collect an explicit equal-count response once and wait for true SUCCESS.

# Research: KBI returns to connection form without a challenge

- Query: User reports that the delivered normal `418f6d13…` build immediately returns to the connection form with the generic KBI error, without ever showing a server question. Determine the exact source paths and existing coverage before deciding whether a product fix is warranted.
- Scope: internal, read-only source investigation; no device, network, account or server-policy probe.
- Date: 2026-11-06

## Findings

### Observed report and limits

The screenshot selects the standalone “服务器交互式认证” method. The user explicitly confirms “完全没弹出问题”. This is not evidence that the password/publickey→KBI continuation was attempted, or that an answer was rejected. The source can produce the screenshot's same error before any server question exists. Actual server behavior remains unproven by this source review.

### Files found

| File | Role |
| --- | --- |
| `lib/ssh/password_connect_page.dart` | Chooses standalone KBI, displays current challenge events and returns failed attempts to the editable form. |
| `lib/ssh/api.dart` | Maps several distinct KBI failures to the same user-facing sentence. |
| `android/app/src/main/java/im/majo/ctos/SshPasswordConnector.java` | Owns the KBI result loop and publishes only actual challenges. |
| `android/app/src/main/java/im/majo/ctos/SshNativeSession.java` | Maps native result/state failures and maintains KBI phase ownership. |
| `android/app/src/main/cpp/ssh_native_client.c` | Sends the selected method and parses server INFO_REQUEST / FAILURE / SUCCESS packets. |
| `android/app/src/test/cpp/ssh_native_client_test.c` | Actual encrypted peer coverage for KBI rounds and outcomes. |
| `android/app/src/test/java/im/majo/ctos/SshNativeSessionTest.java` | Includes direct result rejection/partial without any challenge. |
| `test/ssh_keyboard_interactive_test.dart` | Typed event, protected question form and lifecycle coverage; no dedicated immediate SSH_KEYBOARD_AUTH rejection case found. |

### Exact failure and display paths

1. `SshPasswordConnector.java:339–341` starts standalone KBI and calls `completeKeyboardAuthentication()` independently of password/key first-factor handling. The continuation path is separately guarded at `:361–363`.
2. `ssh_native_client.c:1148–1161` sends one `keyboard-interactive` USERAUTH_REQUEST with empty language/submethods and sets PENDING. It does not manufacture a question.
3. `ssh_native_client.c:1319–1331` accepts server USERAUTH_FAILURE, validates its method-list/partial flag, and sets REJECTED or PARTIAL; `:1349–1351` finishes authentication and clears any challenge. This path does not require a preceding INFO_REQUEST. The method list is retained only for an opted-in first-factor PARTIAL continuation (`:1328–1330`), so standalone KBI does not currently retain enough diagnostic metadata to distinguish “method unavailable” from another pre-question rejection.
4. Server INFO_REQUEST is packet 60 interpreted under active KBI at `ssh_native_client.c:1333–1335`. The actual valid question parser sets CHALLENGE at `:1138–1139`.
5. The connector only calls `events.present(...)` through the mailbox when it sees CHALLENGE (`SshPasswordConnector.java:383–392`). If the first terminal result is REJECTED/PARTIAL, it never enters this branch, closes the mailbox, then throws `SSH_KEYBOARD_AUTH` at `:401–404`.
6. The same `SSH_KEYBOARD_AUTH` also covers native begin failures (`SshNativeSession.java:889–890`), native result/state failures (`:985–986`), and unexpected loss of the challenge while awaiting an answer (`SshPasswordConnector.java:392–395`). Consequently, that error alone does not identify a specific server policy or prove that the server sent a question.
7. `lib/ssh/api.dart:450` maps the fixed code to “服务器交互式认证未完成或被拒绝，请重新连接后回答服务器问题。” This sentence conflates rejection before a question, answer rejection, and some native failures. Its suggestion to answer questions is misleading for the confirmed no-question report.
8. The Flutter form displays questions only upon a matching `SshKeyboardChallenge` event (`password_connect_page.dart:1050–1071`). Rejected lifecycle/session/round guards have their own “问题已失效／顺序无效” messages (`:1051–1065`). The screenshot instead matches the generic fixed error mapping. Ordinary failed connect response (`:980–998`) or failed state event (`:1026–1046`) clears the pending question/request, stops busy state, and reveals the error on the form; this explains the reported return without requiring a navigation failure.

### Existing test coverage and gap

- `SshNativeSessionTest.java:464–487`, `keyboardMalformedWireReplayPartialAndPrematureSuccessCannotPromote`, scenarios 3/4 begin standalone KBI, then set native PARTIAL/REJECTED **without a challenge**, assert the result, reject shell promotion, and assert zero keyboard responses. Thus this session-level state is explicitly supported and covered with a fake native binding.
- `ssh_native_client_test.c:4733–4780`, `test_keyboard_rounds_and_outcomes`, exercises actual encrypted multi-round KBI and final SUCCESS/REJECTED/PARTIAL **after three challenge responses**. It checks refusal of shell for non-success.
- Its direct zero-question branch at `:4782–4787` only sends SUCCESS. No explicit standalone encrypted-peer zero-question FAILURE test was found among `keyboard_finish_packet` callers. No new tests were run or written in this read-only investigation.
- A focused search of `test/ssh_keyboard_interactive_test.dart` found no `SSH_KEYBOARD_AUTH` error case or immediate server-denial fixture. Existing tests concentrate on protected events/forms, cancellation/lifecycle, current request identity and continuation payloads.

### Next diagnostic

Check effective server authentication policy for this exact user/source connection without attempting a real login or changing server configuration. Parent session is separately checking that policy. If KBI is disabled/unavailable or requires a prior method, immediate rejection is consistent with the code. A reliable distinction needs server evidence or bounded native rejection metadata; the screenshot and generic error cannot establish that distinction. Do not “fix” it by inventing a question, changing methods silently, assuming a password prompt, or reading saved credentials.

Parent's separately reported read-only evidence: the screenshot target is the local host, port 22 listens, the listener command uses `/usr/sbin/sshd -D` without a custom-config argument, and `/etc/ssh/sshd_config:64` actively sets `KbdInteractiveAuthentication no` (`:87` sets `UsePAM yes`, include directory empty). Parent's non-root `sshd -T` attempt failed because host keys were unavailable to that check, so no successful effective-policy or live advertised-method receipt was obtained. These parent observations support server-disabled KBI as the explanation; this agent did not execute or independently inspect those host probes. Parent elected to document the diagnosis only and make no product-code change for this question.

If a product diagnostic change is pursued, keep its boundary to distinguishing actual rejection before the first published challenge from post-question rejection/native failures. Preserve the selected method, original ownership/deadline and no automatic fallback. This research does not authorize implementation or deployment.

## Related specs and references

- `.trellis/spec/backend/ssh-keyboard-interactive.md`, sections 3–6: actual INFO_REQUEST only, no automatic credential use, no shell for rejection/partial; fixed recoverable errors.
- `.trellis/spec/backend/ssh-mfa-authentication.md`: separately opted-in password/publickey PARTIAL→KBI plan, distinct from standalone kind3.
- `.trellis/spec/frontend/terminal-interaction.md`, standalone KBI paragraph: actual bounded current questions, strict lifecycle and empty/zero-prompt semantics.
- `design/ssh-keyboard-interactive.md` and `design/ssh-mfa-authentication.md`: project product/protocol contracts referenced by those specs.
- External references: none newly fetched. Findings are derived from the checked project source, not assumed OpenSSH defaults or live server settings.

## Caveats / Not Found

- No ADB, network/authentication call, real account operation, server-policy read or settings change was performed by this agent.
- No source or spec was edited; this research file is the only write.
- No live server method-list or per-attempt server log was captured for this exact user attempt. Parent's current config evidence supports disabled KBI; do not broaden that into packet-level proof or assert a successful effective-policy dump.
- The reported problem does not itself demonstrate that the already-tested challenge rendering failed; no challenge event has been observed for this attempt.

# Research: server-requested continuation without a pre-connection checkbox

- Query: after a password or selected private key is partially accepted, show the server's actual follow-up questions in the same connection, without asking the user to understand a protocol option in advance; retain ordinary config login and all existing trust/secret/lifecycle boundaries.
- Scope: mixed; current repository source/specs plus primary SSH protocol references.
- Date: 2026-11-13

## Findings

### Current path and why the screenshot fails

- `lib/ssh/password_connect_page.dart:195-200,2164-2177` defaults `_continueKeyboardInteractive` to false and shows an opt-in checkbox. Four password/private-key submission branches forward that bool at lines 1241-1325; standalone KBI is a separate first-method selection.
- `lib/ssh/api.dart:379-415,562-567` omits the continuation field when false and says to tick the checkbox after `SSH_AUTH_PARTIAL`. In `SshPasswordConnectBridge.java:328-330,365,370-394,597-639`, the host validates exact arguments, treats an absent bool as false, and freezes it on the connector before use.
- `SshPasswordConnector.java:337-352` encodes an immutable role-bound authentication selection; only enabled continuation produces `withKeyboardContinuation`. The password/private-key result is processed at lines 367-405: a real PARTIAL without the enabled plan becomes `SSH_AUTH_PARTIAL` and the current attempt ends. The screenshot's wording alone does **not** prove which follow-up method the server advertised; when continuation is disabled the native path does not retain that list.
- `android/app/src/main/cpp/ssh_native_client.c:1352-1364` parses the server's bounded method list and partial flag. It saves the list only for stage 1 PARTIAL with a continuation-capable plan. At `1200-1224`, the same-owner transition requires the original plan receipt, actual PARTIAL, one already-attempted selected credential, and an exact `keyboard-interactive` token in that list. It cannot silently retry the first method or enter a shell.
- A standalone KBI connection begins separately in `ssh_native_client.c:1236-1250`; do not confuse choosing it as the *first* method with continuing after a partially successful password/key.

### Critical compatibility trap: defaulting the old checkbox to true

- `SshPasswordConnector.java:444-446` currently rejects an enabled continuation when the imported config's effective chain policy is false. This is checked **before** first-factor authentication in config password at `473-488`, config key at `523-539`, and key-passphrase preparation at `599-615` (also checked in both passes when applicable).
- `ssh_config_eval.c:975-988` derives the existing plan from actual effective `PasswordAuthentication`, `PubkeyAuthentication`, `KbdInteractiveAuthentication` and supported `PreferredAuthentications` membership/order. `SshConfigEvaluator.java:231-251` validates the corresponding wire predicates.
- Thus simply removing the checkbox and passing existing `continueKeyboardInteractive=true` everywhere breaks a config whose first factor would succeed normally but whose KBI is disabled, omitted or ordered incompatibly. The historical strict option deliberately produced `SSH_MFA_POLICY` before persistence/socket in that situation; changing it globally would also change the previous MethodChannel/spec contract. Distinguish “a user selected this first method and permits a server-requested continuation when policy allows” from “the imported config actually permits this continuation.”
- The smallest safe design can reuse the existing immutable CSASv3/CSAPv1, mailbox and C stage transition: determine a **frozen effective continuation capability** from the exact current config and chosen first method during its protected preparation; permit a normal first factor even if that capability is false; encode continuation only if both the new product behavior and config allow it. If PARTIAL arrives while config disallows a suitable continuation, stop and explain that the current configuration prevents completing authentication, without guessing or changing config. Recheck the policy and snapshot across the existing two preparation passes and own-write epoch rules.
- To avoid silently changing legacy strict `continueKeyboardInteractive=true` semantics for old callers, one option is an explicit internal automatic-continuation mode/field with mutually exclusive strict validation, leaving absent/old behavior intact and using the new mode from the revised connection page. Alternatively, intentionally change and document the existing internal contract with its tests. The actual repository does not prove which compatibility strategy is preferred; implementing either requires an explicit cross-layer contract update.
- Even when config allows a chain, the server's actual list may not offer KBI. A `continueKeyboardInteractiveAuth` attempt currently becomes `SSH_MFA_AUTH` through `SshNativeSession.java:895-930`; a clear fixed message may be sufficient, or a bounded typed reason can distinguish missing KBI. Do not claim an OTP/question exists unless the server actually sends it.

### Preserve the security and lifecycle guarantees

- The revised product behavior authorizes showing server questions after **actual first-factor PARTIAL**, while the user still chooses whether to answer or cancel. It does not authorize substituting a KBI first method after an ordinary failed/rejected password or key. RFC 4252 distinguishes the failure packet's `partial success` from its possible next-method list; list membership is not proof the method will succeed. Final `USERAUTH_SUCCESS`, then successful PTY/shell, remains required.
- `SshAuthenticationSelection.java:79-113` retains the original role/user/key/public blob and hashes the optional continuation into CSASv3. `SshNativeCallbackAdapter.java:1243-1249` validates that frozen selected password/key; `SshNativeSession.java:895-936` validates the stage receipt against original host/KEX/session digest and owner. First-factor credential release remains once only. Preserve trust-change signature/NEWKEYS/CAS and original public source identity; none of these may be bypassed by auto-continuation.
- `SshPasswordConnector.java:410-440` guards each actual KBI round by original host proof, KEX proof, role/view/selection and owned stage; only its bounded current challenge and explicit user response go forward. `lib/ssh/keyboard_challenge_form.dart:37-46,55-117,130-211` presents server name/instruction/prompts, respects echo, supports cancel, clears answer controllers and never supplies an answer automatically. Preserve zero-prompt explicit continuation and multi-round handling.
- `lib/ssh/password_connect_page.dart:200,442-470,1206-1217,1435-1443,1491-1502` uses `_keyboardCapable` for the vault event-stream handshake, admission, challenge acceptance and whether an early connected event can close the form. Removing the checkbox without adapting all these guards would either drop a valid challenge or let the page disappear before a late result. Verify route destruction, cancel, background/lock, stream loss and exact late-handoff cleanup. Do not make ordinary one-factor success silently dependent on an unnecessary new preconnection stream failure; solve event readiness and buffering under the same protected original request.
- `SshReconnectTarget` retains only public fresh-attempt metadata; do not persist an old challenge, answer, decision, stage or secret. Keep original request/role deadlines and do not reconnect automatically after PARTIAL.

### Targeted validation

1. Manual and config password/key: server first factor succeeds and config forbids KBI → ordinary login still succeeds with no KBI request; explicit old strict callers retain their documented behavior if that compatibility choice is made.
2. Actual stage 1 PARTIAL, config permits and server offers exact KBI → same socket/KEX, actual server question presented without prior checkbox; explicit answer succeeds, cancel does not send answer, no repeated selected-secret read.
3. PARTIAL with KBI disabled/reversed by config or omitted from the real server list → no KBI response/secret reuse/shell, a truthful actionable outcome. Ordinary first-factor rejection → no unapproved first-method fallback.
4. Existing standalone KBI, wrong credentials, zero/multiple rounds, password change-required packet 60, host change approval/cancel, timeout/lock/background/disposal, forged/stale challenge and late connected result all retain their contracts.
5. Exercise actual effective config evaluator and native peers, Java role/selection/guard tests and Flutter lifecycle/widgets. Real vault JVM, C synthetic network, installed Android JNI and user's actual visual experience remain separately reported evidence.

## Related specs

- `.trellis/spec/backend/ssh-mfa-authentication.md:13-29,31-85,89-101` — existing default-off API, explicit-plan admission and tests; must be updated if product policy changes.
- `.trellis/spec/backend/ssh-keyboard-interactive.md:28-44,49-82` — standalone method, challenge and lifecycle boundaries.
- `.trellis/spec/backend/ssh-host-trust-management.md` — same-connection changed-host transaction; unchanged by the choice to present actual server questions.
- `design/ssh-mfa-authentication.md` and `design/ssh-keyboard-interactive.md` — user-facing scope/history; distinguish historical authorization from the new decision.

## External references

- [RFC 4252 §5.1, Responses to Authentication Requests](https://www.rfc-editor.org/rfc/rfc4252.html#section-5.1): the failure packet separates remaining methods and partial success; only final USERAUTH_SUCCESS completes authentication.
- [RFC 4256, Generic Message Exchange Authentication for SSH](https://www.rfc-editor.org/rfc/rfc4256.html): keyboard-interactive carries server-defined prompt/response exchanges and is an authentication method, not automatically equivalent to a prior password's acceptance.

## Caveats / Not Found

- This is source and specification research, not an Android or network execution; screenshot #7 was not backed by a captured SSH FAILURE packet in this investigation.
- The exact wording/layout of standalone interactive first-method choice remains a UX decision; the user's accepted behavior covers follow-up questions after actual partial success.
- A config that forbids KBI may never be silently overridden; a server requesting some other second factor remains unsupported unless separately designed and approved.

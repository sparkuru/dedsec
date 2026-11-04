# Research: next SSH increment after host-trust delivery

- Query: identify one useful remaining approved R1–R11 product increment after the user accepted host-trust management and said “通过；可以继续；”. Compare keyboard-interactive, individual Include import, major direct options and SSH recovery.
- Scope: internal, current checkout; read-only source investigation. No runtime, build, device, network, real account or Git operation.
- Date: 2026-11-02

## Findings

### Recommended next slice: explicit keyboard-interactive direct authentication

R7 explicitly includes keyboard-interactive. Password, ordinary/encrypted private key, config-host selection and narrow trust recovery now have product paths. Keyboard-interactive is a real remaining authentication gap, not an absent button over an already complete native implementation. A coherent next delivery is an explicit keyboard-interactive connection mode, with real protocol challenge/response, protected bounded prompts, cancellation and successful PTY handoff. This recommendation sets implementation order; it does not establish that the user has approved every new UX or MFA policy choice below.

Evidence:

- `android/app/src/main/java/im/majo/ctos/SshCredentialBridge.java:9` declares `KEYBOARD_INTERACTIVE`; `SshNativeCallbackAdapter.java:1075` maps the generic credential kind. This is typed secret plumbing only, not actual SSH keyboard-interactive protocol support. Its generic non-key entry path at `SshCredentialBridge.java:132` maps to SSH_PASSWORD, which must not become permission to guess a server prompt or automatically send a saved password/OTP.
- `SshAuthenticationSelection.java:34` and `:41` expose password/private-key factories; `:87` encodes only native kind1 or4. `android/app/src/main/cpp/ssh_native_client.c:235` rejects any other selection kind, and `:804`–`:805` requires the same two kinds at selected credential liveness. Simply adding a UI enum cannot activate KBI.
- `ssh_native_client.c:946` begins one password request. Its `auth_packet` at `:1005` accepts success/failure, banner/disconnect and password-change request. `:1039` returns PARTIAL; it does not continue an MFA chain. Packet60 is interpreted as password change only for the password selection at `:1042`; this number is method-dependent and must not be globally reinterpreted as KBI INFO_REQUEST. A new method/state-aware decoder is necessary.
- `android/app/src/main/cpp/ssh_native_client.h:161`–`:174` exposes password and private-key USERAUTH only. `SshPasswordConnector.java:270`–`:286` chooses exactly one of those methods and rejects every non-SUCCESS result. `:289` retires authentication only after that success. Existing PARTIAL must continue to grant no shell authority.
- `lib/ssh/api.dart:19`–`:79` currently parses only state/hostKey/hostKeyDismissed events. `SshPasswordConnectBridge.java:83`–`:106` handles password/key/config connect, cancel and exact host-key decisions. No server challenge event/answer submission was found in these production paths.
- `.trellis/spec/backend/ssh-native.md:578`, `:925` and `:1050` explicitly keep KBI/MFA continuation outside prior native deliveries. The current report must not call that remaining work implemented.

Suggested observable scope and acceptance for the main session to freeze:

1. User explicitly chooses keyboard-interactive for a direct target; the connection never silently changes from an explicitly selected password/private-key method. A config-host route, if included, must use the actual effective host/user/port and respect real KbdInteractiveAuthentication/PreferredAuthentications policy rather than infer eligibility from the password flag.
2. Actual verified host/KEX precedes any challenge answer. Unknown host still requires exact current fingerprint approval; CHANGED/REVOKED/CERTIFICATE sends no response. No auto-trust or stale prior review.
3. A real server may issue zero prompts, several prompts in one round, and multiple rounds. UI presents bounded name/instruction/prompt text as server-supplied content, uses the echo flag for masked vs visible input, preserves field order, and sends exactly one complete answer vector for the current challenge. Responses are only for that connection/role/KEX/method/round. Duplicate, old or cross-attempt answers fail without transmission.
4. Cancel, Back, lock, background, expiry, stream loss and forced route disposal dismiss prompts, clear owned answers and await original cleanup. Returning foreground cannot resurrect an old round. The existing original authentication deadline is not silently renewed per prompt.
5. Prompt answers are ephemeral; no automatic retrieval from stored login passwords, key phrases, TOTP entries, clipboard, config or remote-output parsing. No save-answer facility without a separately explicit product contract. Untrusted prompt text cannot emit terminal lifecycle or local operation events.
6. Only actual USERAUTH_SUCCESS followed by actual PTY/shell success hands the existing native owner to the service. Rejection, malformed/oversized challenge, partial authentication and transport failure produce fixed recoverable failure. Session Stop/reconnect uses existing explicit behavior, with no stored answers or reconnect auto-submit.
7. Tests distinguish actual native encrypted protocol/method state and packet framing, Java/JNI ownership/strict wire, Flutter typed/protected prompt UI, narrow/keyboard/large-text layouts, and separately authorized actual server/Android end-to-end evidence. A generic callback test is not protocol interoperability evidence.

User-owned decisions still worth making explicit in the next contract: whether the first slice exposes KBI on both manual and config-host routes; whether publickey/password→KBI MFA continuation belongs in that same slice or is deferred. Standalone KBI can be independently useful, but deferring chained authentication must remain visible and must not be described as full MFA support or full R7/AC5 completion. Prompt/response byte/count/round limits and cancellation ownership are technical contracts to settle before implementation, not reasons to ask the user to design protocol internals.

### Why individual Include import is not the next missing feature

`lib/ssh/library_page.dart:229` opens the existing destination-path dialog. `:248` already tells the user that `.ssh/config` is the root and Include files use the path named by the configuration. `:252` accepts that explicit managed path; `:297` invokes the normal file import. `SshImportProposal.java:83`–`:96` creates a CONFIG at the supplied path and derives actual references against the temporary candidate. Missing dependencies are diagnosed; included files can be imported before their parents. This is a single-file workflow, consistent with the user's withdrawal of directory scanning/path migration.

There may be usability or scenario-specific gaps, but no missing individual-Include import mechanism was established here. Do not rebuild a directory picker, desktop path adapter or automatic import tree under an “Include support” label. Full dynamic Include compatibility and broader parent acceptance remain distinct from the already existing static managed-file entry.

### Why major direct options come after a dedicated consumed-plan design

The pinned evaluator retains real Options, but the production direct-plan gate at `android/app/src/main/cpp/ssh_config_eval.c:930`–`:939` refuses `RequestTTY no`, non-default SessionType, ProxyJump, RemoteCommand, forwarding, certificates and HostKeyAlias. `:941`–`:946` exports only currently consumed authentication flags, timeout and strict host policy. Current `plan->password_authentication` also requires no PreferredAuthentications override (`:943`). Removing these refusals would silently lose behavior.

- HostKeyAlias affects trust lookup identity independently of transport host. It needs corresponding role/issuer/known_hosts-management and displayed approval semantics, not just another string in a plan.
- RemoteCommand/RequestTTY require a consumed immutable command/session plan, actual channel exec vs shell/no-PTY support, terminal/UI behavior and service/reconnect tests. The current native API is `begin_shell(..., cols, rows)` (`ssh_native_client.h:183`). They are valid approved future work but are not merely parser switches.
- ProxyJump/forwarding add transport chains/listening exposure confirmation and per-hop secret/trust lifetimes, making them larger than a single authentication-method slice.

### Why full SSH restore remains a broader integration

`android/app/src/main/java/im/majo/ctos/AppSecrets.java:703` defines `attachSshRestoreReviewer`; a production-source search found no invocation, only the definition. `:854`–`:858` rejects SSH-containing source/target restore when validation or an actual reviewer is absent. `:863` binds a supplied review to real authenticated source/target views. Existing graph transactions and current narrow trust management do not authorize attaching a permissive reviewer.

Completing recovery requires actual full coverage of supported CONFIG/key/account/credential/trust semantics, source/target conflicts, explicit trust review, authenticated exact-source consent and atomic failure preservation. It is high-value R10/R11 work, but larger and more coupled than the next method-specific connection slice. Do not describe ciphertext export availability as cross-device SSH recovery completion.

## Files found

- `.trellis/tasks/10-08-ssh-terminal/prd.md` — approved R5/R6/R7/R10/R11 and open parent acceptance criteria; latest host-trust slice.
- `android/app/src/main/cpp/ssh_native_client.[ch]` — real managed KEX, one-shot password/private-key authentication, terminal PARTIAL and shell promotion.
- `android/app/src/main/java/im/majo/ctos/SshAuthenticationSelection.java` — actual CSAS selection factories and fixed1/4 kind encoding.
- `SshNativeCallbackAdapter.java`, `SshCredentialBridge.java` in the same package — generic typed credential kinds and current secret-release boundary.
- `SshPasswordConnector.java`, `SshPasswordConnectBridge.java` in the same package — production single-method orchestration, original cancellation/deadline and public host-review channel.
- `lib/ssh/api.dart`, `lib/ssh/library_page.dart` — strict current event model and existing single-file config/Include destination UI.
- `android/app/src/main/java/im/majo/ctos/SshImportProposal.java` — actual CONFIG candidate/reference derivation at the supplied managed path.
- `android/app/src/main/cpp/ssh_config_eval.c` — actual direct-plan unsupported/consumed-option boundary.
- `android/app/src/main/java/im/majo/ctos/AppSecrets.java` — registered-reviewer requirement for SSH restore.

## Related specs

- `.trellis/spec/backend/ssh-native.md` — real transport/authentication/selected proof/one-shot cleanup; KBI and MFA remain separate.
- `.trellis/spec/backend/ssh-password-connect.md` — actual host/KEX-first secret delivery, original attempt cleanup, true SUCCESS+PTY handoff and separate device evidence.
- `.trellis/spec/backend/ssh-key-config-connect.md` and `ssh-config-identity-selection.md` — immutable actual effective plans, explicit selected key, strict nonidentity policy and existing individual Include workflow.
- `.trellis/spec/backend/app-secrets.md`, `.trellis/spec/frontend/app-secrets.md` — common vault/guarded outcomes and protected route lifecycle.
- `.trellis/spec/backend/ssh-host-trust-management.md` — approved query/forget scope; no future incoming-key approval authority.

## External references

None consulted. Findings concern current local source and project contracts; no latest external API, library recommendation or protocol-correctness claim is made. A protocol implementation should verify its exact method semantics against the pinned OpenSSH/reference protocol material during its design stage.

## Caveats / Not Found

- No implementation changes or tests were performed. This is source research and a proposed next-slice order, not an implementation approval or acceptance report.
- Host-trust human acceptance is taken from the latest user message; this report does not alter task/spec/design status. The main session owns acceptance bookkeeping and any commit plan.
- No actual KBI backend operation, challenge wire, production challenge UI, native INFO_REQUEST handling or MFA continuation was found in the inspected paths. Generic credential kind3 alone is insufficient.
- Includes already have an individual managed-path entry; dynamic Includes and full config compatibility should not be inferred from that entry.
- Original1008 remains open. The current user's continuation does not itself grant new device/account/deployment operations or change canceled directory/path adaptation scope.

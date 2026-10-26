# Research: Minimum usable password SSH UI and channel path

- Query: Find the missing Flutter entrypoint and the smallest password-connect/NEW-host review UI that reuses the existing terminal and respects the sole formal SSH mainline.
- Scope: internal source/specification inspection only; no source edits, builds, devices, accounts or network effects.
- Date: 2026-10-25

## Findings

### The terminal renderer already supports SSH, but there is no connect entrypoint

`lib/main.dart:1629` builds the idle terminal picker. Its only actions are App Shell (`:1647`, `:1664`) and Root PTY (`:1669`, `:1684`); the heading still describes local Shell (`:1638`). `startTerminal(bool privileged)` at `:302` sends only `terminalStart({root, columns, rows})` (`:312`). There is no host/port/username/password form, SSH API, profile list, host-key prompt or SSH start MethodChannel invocation anywhere under `lib/`.

This is not a missing second terminal implementation. The `ctos/terminal` subscription (`lib/main.dart:115`) passes typed events through `TerminalEventDecoder`, then establishes `session`, `terminalRemote` and `terminalGeneration` from host state (`:126`–`:140`). Once a host factory attaches a real RUNNING session, the existing terminal displays its mode, output, resize and Stop actions without a separate SSH screen.

Reusable behavior is already exercised by `test/terminal_interaction_test.dart:41`–`:96`: synthetic remote RUNNING attaches to the read-only terminal, split UTF8 decodes correctly, remote Up/Down send raw escape sequences, resize uses the same generation, stale data is discarded, and Stop retires the session. Those tests deliberately inject a host event; they do not start an SSH connection or exercise host confirmation.

### Existing lifecycle rules must survive the new form

- `lib/main.dart:302` prevents duplicate local starts through `session || terminalBusy`. Its start result is ignored for lifecycle; only the host event makes a session live (`:132`–`:135`). Preserve this for SSH: a successful connect RPC or local UI choice must not synthesize RUNNING.
- `stopTerminal` (`:324`) currently requires `session && !terminalBusy`. Therefore it cannot cancel an SSH attempt before RUNNING. A connecting route needs an explicit attempt/request cancellation operation, usable while its connect Future is pending; it cannot merely call this guarded local Stop method.
- `sendTerminal` serializes writes and captures the generation (`:362`–`:377`); resize and Stop also pass generation (`:324`–`:354`). Reuse these after attachment.
- `sendTerminalInput` excludes remote sessions from local history (`:380`–`:385`). Existing SSH arrows are raw bytes and the new form must never send the login password through that terminal input/history path.
- App navigation does not call terminalStop. `_ObservatoryState.dispose` only drops UI listeners (`:166`–`:174`); host Activity destruction cancels SSH resources (`MainActivity.java:555`–`:566`). Do not make disposal of a successfully completed connect form stop the attached terminal. Activity-owned lifetime is still not proof of the original independent-foreground-service/background-retention requirement.

### The current channel does not carry connecting or host-review state

`lib/terminal_events.dart:9`–`:52` accepts only strict `data` or `state` maps. State is exactly `running|closed|failed`, with an allowlist of keys; unknown types/keys are rejected. `TerminalEventDecoder` (`:85` onward) is a generation-fenced byte/lifecycle decoder, not a pending-attempt or approval bus.

The Android boundary similarly has only terminalStart/Write/Resize/Stop dispatch (`MainActivity.java:368`–`:400`). `attachAuthenticatedSsh` is host-only and returns only when the actual channel is RUNNING or fails (`MainActivity.java:50`–`:65`, `:85`–`:93`). No current handler exposes mailbox tickets to Flutter.

Recommended boundary: add a small typed SSH attempt/review API, while keeping actual terminal bytes/state on the existing terminal stream. A separate SSH event stream avoids overloading terminal RUNNING or feeding new map kinds into its strict parser. Names below are a proposal for the main session to freeze, not existing methods:

1. Start password connection with a one-use `requestId`, exact host spelling, explicit port, username, transient password and current terminal dimensions. Host owns actual target/account/connection identity, validation, deadline and authority; do not derive a targetIdentity from hostname in Dart.
2. Receive bounded metadata-only attempt status and NEW-host ticket events, correlated to that request/attempt. No password, private key, raw key blob or generic secret enumeration appears in responses/events.
3. Resolve an exact ticket by opaque ticket ID plus its binding/attempt correlation and explicit accept/reject. Cancel pending attempt by request/attempt ID. Host retains the actual Ticket object and resolves only its current one; a Boolean alone is not trust authority.
4. End pending UI on cancellation, failure, host dismissal or successful terminal attachment. Only the existing remote RUNNING event enables terminal input.

Avoid a method name that starts with `terminal` for a normal queued review decision unless it is explicitly routed before owner dispatch. `MainActivity.java:317` queues every such method on terminalWorker. The host mailbox can be waiting synchronously on that owner, so placing the decision on the same queue would deadlock until expiry. Its decision/cancellation path must remain independent.

### The host mailbox already supplies the metadata and exact-decision semantics

`SshHostKeyReviewMailbox.java:19`–`:29` defines a public-metadata Ticket: opaque ID, binding, hop, connectionId, targetIdentity, host, port, keyType, fingerprint and deadline. `MAX_WAIT_MILLIS` is 30 seconds (`:8`); it may be shortened but not extended (`:78`–`:86`). Missing UI is immediate refusal (`:84`, `:95`). Only an actual NEW classification is eligible (`:90`–`:93`). The class has present/dismiss callbacks and resolve/cancel, but these are host-only today.

Display the actual host and explicit port, algorithm and SHA256 fingerprint before the accept action. Use a distinct reject/cancel action; no default or delayed auto-approval. Changed/revoked keys must remain a failure, not appear as another NEW confirmation. Do not label an approval "temporary trust" if the host actually performs persistent known_hosts CAS. The host must define the exact user-visible persistence effect.

The ticket deadline belongs to the host monotonic context; do not compare it to Dart Unix wall time or renew the budget after UI interaction. If a countdown is desired, have the host supply a bounded remaining duration. Stale/mismatched request/ticket/binding decisions and decisions after dismiss must do nothing, with the host still authoritative.

### Protected routes and transient credentials are existing approved behavior

The active PRD explicitly requires passwords/passphrases to default to not saved and allows the user to choose saved or only-this-time (`prd.md:380`). It also requires shared vault initialization/unlock and current vault/connection context checks (`:380`–`:381`). A manual password form with "仅用于本次连接" is an approved first path; it need not invent a saved-password feature to be useful. It is still a first path within the same formal model, not a test/fixture picker or a separate product variant. Do not silently ignore configured ProxyJump/forwarding options or claim the original complete config scope is finished by a manual direct-connect form (`prd.md:375`–`:382`).

Reuse the opening sequence in `lib/authenticator/page.dart:17`–`:35`: ensure shared vault unlocked, then enter a protected consumer route. `showSensitiveSecretRoute` (`lib/secrets/flows.dart:43`–`:70`) acquires native protection before building the sensitive page and releases only after removal plus an end frame. The new form can compose this helper through `SecretVaultApi`; it does not itself grant native business authority.

Existing password widgets disable autocorrect/suggestions/personalized learning, use obscured input and a password keyboard (`flows.dart:438`–`:449`), clear on lifecycle loss (`:269`–`:276`) and clear/dispose all controllers (`:279`–`:285`). Use equivalent behavior for SSH password, including cancel, route coverage, background and terminal outcome. Clear the password field after handing it to the one connection request and never put it into display mode, snackbar text, history, persisted preferences or an event payload. Dart Strings and MethodChannel argument copies are immutable managed data; clearing a controller is reference minimization, not a guarantee of zeroizing every VM/string copy. Host transient secret ownership/wiping still needs the host path implementation.

Do not reuse `SecretRequest.call` blindly as the SSH connect protocol: it prefixes every method with `secrets.` and assumes a Map response (`lib/secrets/api.dart:151`–`:181`). Its one-use request/cancellation/completion patterns (`:109`–`:143`, `:193`) are useful, but the new API needs its own correctly typed connection outcome.

For sensitive-route session health, `AuthenticatorSensitiveState` provides a useful pattern: it observes foreground/coverage/session epoch, clears on locked/changed/covered routes and handles stream failure (`lib/authenticator/sensitive_state.dart:19`–`:25`, `:47`–`:79`, `:84`–`:114`). It is TOTP-named and should not be inherited merely to borrow business-specific APIs. Also avoid a second independent `receiveBroadcastStream` listener on `ctos/secrets`: AuthenticatorApi currently owns shared listener/multiplexing and a fresh-handshake lifetime (`lib/authenticator/api.dart:138`–`:140`, `:197` onward). Either reuse/refactor that common session-event facility deliberately or let a frozen SSH attempt API report session invalidation; do not accidentally replace another EventChannel handler.

### Suggested bounded ownership and useful tests

Suggested UI implementation owner: new `lib/ssh/api.dart`, new `lib/ssh/password_connect_page.dart`, new `test/ssh_password_connect_test.dart`. Keep the large `lib/main.dart` change narrow: import/open the protected form from a new SSH picker item, retain existing typed terminal event subscription and rendering. Main session can own this integration to avoid overlapping changes. The host implementation owner freezes the start/attempt/review/cancel wire contract with that UI owner before either assumes field names.

Useful checks:

- Parser/API tests reject malformed/bounded/unknown attempt and ticket fields and ensure no raw credential fields appear in returned metadata.
- Widget test adds a real SSH entry beside unchanged App/Root entries; valid form sends exactly one start, invalid host/port/empty user does not call host. Host remains final validator; never trim/normalize username/password or reinterpret host URI syntax silently.
- Protected-before-render, password obscuring/controller clear on submit/cancel/background/covered/dispose, fresh request ID for retry and no saved-by-default behavior.
- Pending connection can cancel before RUNNING; back/background cannot approve or retain a pending password; late success/review events cannot reopen a stale route or enable input.
- Actual NEW ticket metadata displayed, reject/cancel and accept send the exact correlation/binding, stale/dismissed/duplicate tickets do not produce approval, and no changed-key acceptance path exists.
- Successful RPC without a host RUNNING event leaves the terminal non-live; an early closed/failed event cannot be overridden by a late RPC result. Existing remote UTF8/Up/Down/resize/Stop tests remain active.
- 320dp width plus keyboard/large text keeps the form/review actions scrollable and usable. Fakes test wiring and UI lifetimes only, not native/KEX/CAS or real SSH success.

## Files Found

- `lib/main.dart` — sole terminal picker/controller/rendering and current local start RPC.
- `lib/terminal_events.dart` — strict existing terminal lifecycle and generation/UTF8 decoder.
- `lib/terminal_interaction.dart` — existing PTY input/history/selection behavior to retain.
- `lib/secrets/flows.dart` — shared bootstrap/unlock and native-protected route lifetime/password controls.
- `lib/secrets/api.dart` — current SecretRequest and shared-vault MethodChannel/protection API.
- `lib/authenticator/api.dart` — current shared native session-event multiplexing implementation.
- `lib/authenticator/sensitive_state.dart` — sensitive route/foreground/epoch/event-health pattern.
- `android/app/src/main/java/im/majo/ctos/MainActivity.java` — host-only authenticated SSH attachment and existing terminal dispatch/owner queue boundary.
- `android/app/src/main/java/im/majo/ctos/SshHostKeyReviewMailbox.java` — bounded exact NEW ticket/decision contract not yet exposed to Flutter.
- `test/terminal_interaction_test.dart` — existing synthetic SSH rendering/input/lifecycle coverage and reusable channel fake.
- `test/terminal_events_test.dart` — strict parser/generation decoder coverage.

## Related Specs

`.trellis/spec/frontend/terminal-interaction.md`, `frontend/app-secrets.md`, `frontend/type-safety.md`, `frontend/component-guidelines.md`; `design/ssh-manifest.md`, `design/ssh-launcher.md`, `design/ssh-session-channel.md`; current PRD requirements `:375`–`:382`.

## External References

None needed: findings concern the checked-out implementation and local product/spec contracts. No external library behavior was assumed or changed.

## Caveats / Not Found

- No current SSH form/API/NEW-key prompt or Flutter profile/account management was found; no runtime or real endpoint was inspected.
- Host socket connector, transient-password provider/actual account binding, full path admission and foreground-service ownership are outside this focused UI investigation. A visible form alone cannot make the existing host-only attachment callable or safe.
- UI protocol names above are proposals, not implemented contracts. Parent must freeze them with the host flow and retain existing proof/CAS/credential-release gates.
- Original full config/ProxyJump/forward/service scope remains open; latest priority permits a minimum usable direct password path first, not silent removal of those requirements.
- Line references describe source observed before the new password-path implementation. Other agents may subsequently edit those files.

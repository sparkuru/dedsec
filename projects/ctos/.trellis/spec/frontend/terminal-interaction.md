# Terminal interaction contract

2026-11-04: [MFA continuation](../backend/ssh-mfa-authentication.md) adds the explicit default-off
`continueKeyboardInteractive` option for password/key, including configPassword/config-key.
Keep the accepted single authentication chooser and rounded option row. Changing method clears
the option; standalone KBI hides it. Config-password retains actual alias/binding/User policy
and uses its dedicated API rather than turning into a manual target. During same-config key-only
refresh, preserve a user-entered missing-User override; a nonempty current config User wins.
All continuing modes use KBI stream readiness/challenge/route-external lifetime, including saved
password and saving variants. A connected event before the original RPC completes does not
release protection; forced disposal cancels a late exact handoff before cleanup resolves.
RAM reconnect accepts strict optional bool and configPassword mode, resolves current config and
starts a fresh first method. It carries no question, answer, old binding or stage receipt.
Phase-A review fixed the missing-User override regression; actual-font/scoped tests and full396
passed. Android IME/device and final cross-layer acceptance remain separate evidence gates.

2026-11-03: the manual authentication choice is one labeled `ssh-authentication` field,
showing the current method and an expand icon. Open `ssh-authentication-dialog` for the three
explicit choices; cancel/back retains the original choice, and mode-specific help follows the
closed field. Use an owned route and original revision guards: background, coverage, leaving,
busy or stale completion cannot change the method or revive cleared sensitive state.
Keep narrow/large-text options scrollable and the selector keyboard/screen-reader operable.
An outlined InputDecorator's floating label paints across its bounds: keep the selector's
Material unclipped and bound InkWell feedback with borderRadius instead of clipping the label.
Saved password/passphrase option tiles have their own 12 dp rounded, clipped Material with
theme field fill, 8 dp separation and normal visible hover/focus/press feedback. Rounding only
the checkbox does not bound the row's state layer. Verify actual hovered/focused renders, not
just the static checkbox tree; selecting a new method must preserve existing secret clearing.

2026-11-02: [standalone KBI](../backend/ssh-keyboard-interactive.md) adds explicit manual/config
authentication choice and current-round `keyboardChallenge`/`keyboardChallengeDismissed` events.
A config profile with no key may choose KBI; key authentication still requires an explicit key.
Display all bounded server prompts together in the protected connection page, including
name/instruction and actual target; honor echo independently, disable suggestions/autofill and
save controls, preserve empty answers, and require explicit continue for zero prompts.
Typed payload validation owns UTF8/count/round/total limits. Remote text is labeled body text,
not App status/actions. Never fill from saved passwords, key phrases, TOTP or clipboard.
Clear controllers on round dismissal/retirement; fence late events by original attempt/ticket.
Cancel/back/background/lock/stream failure and forced route destruction keep protection until
original connection cleanup/late-handoff cancellation completes, using route-external lifetime
ownership. Healthy own-trust epoch advance before a challenge must not fabricate a new request.
Coalesce only an active cleanup future. Clear its cached reference on completion with an
identity guard: after completed cleanup and a later retain(), finish() must await that later
connection and its late-handoff cancellation rather than return the old completed future.
RAM reconnect modes keyboard/configKeyboard retain only the public target/alias and open the
fresh protected flow; there is no keyId, old binding, saved answer or automatic response.
Local tests and real-font layout checks passed; native/device evidence stays separately scoped.

Private-key card actions use content-width controls in a wrapping action group, with at least
8 dp between runs and 48 dp touch targets. Use the current 12 dp control corner shape for
the outlined use/forget/delete actions, including hover/focus. Match control height and outline
style while retaining destructive text/icon color; use theme state feedback for enabled/disabled.
Do not stretch each card action into full-width banners. Keep destructive error color and
the existing exact impact confirmation; layout must support clear and saved-phrase keys.

2026-11-02: [config identity selection](../backend/ssh-config-identity-selection.md) makes a
valid imported profile independent of its original IdentityFile paths. Parse optional
identityHints as bounded protected public text, never managed paths. Keep no-key profiles visible;
clicking explains key import. With keys, always show an explicit scrollable chooser, even for
one key, with original hints and actual key label/path/algorithm/fingerprint. Cancel/coverage/
background/stale generation cannot select. There is no automatic first-key or saved-Account
default. Preserve exact alias/binding/keyId configConnect and ordinary encrypted phrase flow.

2026-10-30: [private-key deletion](../backend/ssh-key-deletion.md) adds a public impact preview,
explicit destructive confirmation and exact ticket commit/cancel. Keep the sensitive window
until terminal mutation outcome is acknowledged. Retain profiles and original hint text after
deletion; an unavailable selected ID needs fresh choice, never silent fallback. Real config
error rows remain disabled with readable explicit theme onSurface/onSurfaceVariant and icon.

2026-10-30: [encrypted private-key flows](../backend/ssh-encrypted-key-connect.md) extend the
protected library/connection route. Library exposes only encrypted/saved-state flags; locked
imports unlock the original ticket before fingerprint confirmation. Mask phrase inputs, disable
suggestions/autocorrect, default to one-use, and send saved reuse with empty plaintext. Reset
controllers/choices on source/target/lifecycle changes. Encrypted reconnect requires the fresh
form. Forget single/all phrases has explicit confirmation and awaited terminal receipt before
protection release; it preserves the key and other secrets. Widget tests need a pumped frame
after onChanged before reading rebuilt checkbox state.

## 1. Scope / Trigger

Use this contract when changing the Flutter terminal page, its IME input, PTY shortcuts, output selection, or the `ctos/native` terminal method calls. App and Root PTYs remain separate from the persistent Root collector. ctOS no longer has a Vector bridge.
The SSH form hierarchy and changed-host recovery also follow this contract and the
[host-trust transaction contract](../backend/ssh-host-trust-management.md).

## 2. Signatures

- `SshHostKeyConflictDialog(host, port, onAction)` reports `view`, `forget` or `cancel`;
  it is an explanation of a failed connection, never a host-key approval ticket.
- `showSshHostTrust(..., initialTarget: ({String host, int port})?,
  initialForgetGroup: SshHostTrustGroup?, returnAfterForget: bool,
  selectGroupForForget: bool, directForget: bool)` can open the existing protected trust flow for an exact
  target. Automatic query waits for the unlocked foreground session handshake; automatic
  preview is permitted only for a known group and an eligible fresh result.
- `TerminalCommandInput(onWrite: ValueChanged<String>, focusNode?: FocusNode)` emits committed input bytes as Dart strings.
- `TerminalCommandHistory` stores at most 100 commands submitted through the current PTY input field, preserving an unsubmitted draft while navigating.
- `terminalOutputSnapshot(Terminal) -> String` copies the active xterm buffer into selectable plain text.
- Native methods: `terminalStart({root: bool, columns: int, rows: int}) -> String`, `terminalWrite({generation: int, text: String})`, `terminalResize({generation: int, columns: int, rows: int})`, and `terminalStop({generation: int})` on `ctos/native`.
- `ctos/terminal` uses typed data `{type:'data', generation:int, bytes:Uint8List}` and state `{type:'state', generation:int, state:'running'|'closed'|'failed', mode:String, remote:bool, exitStatus?:int, reason?:String}`. `TerminalEvent.parse` validates once; `TerminalEventDecoder` fences stale generations before incremental UTF-8 decode and flushes/resets each session. Only host running state establishes a live session; start's mode String cannot override an earlier exit.
- Service reattachment adds exact `snapshot` and `focus` events per the
  [background-session spec](../backend/ssh-background-session.md). Snapshot resets xterm/UTF-8,
  clears draft and queued input revision; suppress renderer responses while replaying bytes.
  Same-generation closed/retired sessions cannot resurrect. Focus selects tab2 only for the
  current running remote generation, preserving any unfinished protected route above it.
- `SshReconnectTarget.parse(Object?)` strictly parses the finite mode/endpoint/selection schema in
  the [background-session spec](../backend/ssh-background-session.md). Only final remote
  `closed/SSH_SCREEN_LOCKED` may expose it. Malformed optional reconnect metadata removes the
  action, never suppresses an otherwise valid closure or leaves old input enabled.

## 3. Contracts

- Organize the SSH form in order: connection target, login authentication, current-connection
  options, primary Connect, then a collapsed connection/password-management section. Keep
  private-key selection reachable within its authentication flow and place destructive password
  management behind the secondary section. Wide screens may place host and port in one row;
  narrow screens, enlarged text and keyboard insets must keep all controls usable.
- On `SSH_HOSTKEY_CHANGED` or `SSH_HOST_KEY_CHANGED`, wait for the original request to settle,
  clear transient secrets and show the exact endpoint with View old fingerprints / Forget old
  fingerprints / Cancel. View opens the exact target; Forget opens an in-place protected
  confirmation over the connection form, without navigating to the trust-management page.
  It still requires a fresh source-bound preview and separate explicit confirmation. Direct password and direct keyboard use the
  password trust group, direct private key uses the config trust group. A config alias can use
  another known-hosts file: show the available sources and require an explicit group choice,
  never infer it from the auth method. Protected/shared records remain blocked.
- Forget success returns only after the protected receipt is acknowledged; it does not connect
  or approve a new key. A subsequent user-started connection performs ordinary new-key review.
  Keep the target, not passwords or passphrases; revalidate a restored config/key binding. Route
  coverage, background, lock, stale revision or a contradictory late connected event must not
  revive the failed attempt, the recovery action or a prior approval. In particular, the actual
  confirmation route must synchronously mark itself invalid when another route covers it;
  returning from that route must never revive its captured confirmation callback or ticket.
  Commit additionally requires the current confirmation button's one-shot approval; a bare
  `Navigator.pop(true)` is not an explicit user confirmation. The success and uncertain-outcome
  receipts likewise require their own actual acknowledgement button. A forged route result must
  retain and re-present the pending receipt while its owner remains live; route or Navigator
  destruction must keep the existing cleanup and protection lifetime guarantees.
- A generic native `SSH_SESSION_STEP` failure reports SSH negotiation or communication failure
  and suggests checking server state and network. Do not present a changed-host dialog unless
  the connection reports a corresponding host-key change code. The current host authority also
  uses `SSH_HOST_KEY_CHANGED` for proof-binding mismatches; those are fail-closed and have not
  been reproduced through the normal UI in this validation. Distinguishing those causes remains
  follow-up work and must not be represented as already implemented.
- With no session, show `应用 Shell`, `Root PTY`, and the production `SSH 连接` entry. Root is unavailable unless the current Root capability is online. Start/stop transitions are guarded against duplicate actions; switching closes the current PTY before another is offered.
- The upper `TerminalView` is `readOnly: true`. It may render, scroll, and resize, but must not open the IME or become a second keyboard path. Text selection belongs to the separate output snapshot page. The bottom input is the sole keyboard target; the Shell prompt and edited command appear through PTY echo above.
- The bottom input requests `TextInputType.text` and `TextInputAction.send`, with no password input or automatic command rewriting. It visibly shows text currently typed into the field while sending committed edits to PTY, so Shell echo above may temporarily show the same text. Keep the IME editing value intact until Enter, including committed text, because some vendor IMEs commit pinyin before replacing it with a selected Hanzi. Compare the current committed Unicode text against the last text sent to PTY: erase the changed suffix with `\x7f` per code point, then send the replacement suffix. Hold active composing text until commit. Its invisible anchor exists only to make soft-keyboard delete observable; never forward the anchor to PTY. An empty-field delete emits `\x7f`; Enter/send flushes any visible composing text, emits `\r`, and resets the editing value.
- Before Tab, flush any visible IME text to PTY, then send `\t` for Shell completion. Keep the bottom text visible while waiting for the PTY response. Read the Shell's rendered editable line at the cursor and update the bottom input and its sent-text baseline without writing the synchronized text back to PTY. A unique completion such as `ec` to `echo` must show the completed command in both places; subsequent typing and deletion must edit that same line. If the rendered Shell line cannot be identified reliably, clear the stale bottom draft without a PTY write and explain that the upper terminal is authoritative.
- App/Root Up and Down browse commands submitted through the current PTY's bottom field. Replace the editable line by sending the same Unicode diff/backspaces as an IME edit, and update the field. Keep the draft when browsing back down, cap history at 100, and reset it for a new session. This avoids Android App Shell's observed long-command horizontal history redraw on the target device. It does not read Shell history created outside this input path. While Tab completion is pending, do not navigate local history until the line settles. SSH (`remote:true`) sends raw `\x1b[A` / `\x1b[B` to the remote PTY and does not record/use local command history.
- Ctrl-C forwards `\x03` and Esc forwards `\x1b`; both clear the canceled local editing span. Restore bottom-input focus after shortcut taps.
- Serialize `terminalWrite` calls. Session generation prevents queued input from reaching a later session. Native exit while a start call is pending must not be overwritten by that call's completion.
- A remote failed lock fence or cleanup-pending snapshot may finalize to same-generation
  `closed/SSH_SCREEN_LOCKED`; this narrowly permits cleanup completion, never RUNNING resurrection.
  Keep this state through interim cleanup failure, reject retired/stale generations, and enable
  reconnect only from final close. Show “已因锁屏断开” and “重新连接”; button must still match the
  exact current closed generation, foreground/not-busy/no-session state and prepared target.
  Tapping reuses the protected connection route, loads current key/config metadata and fresh
  binding, and leaves transient password/save/use blank or unselected. Do not auto-submit on
  resume. Deleted/mismatched selections require explicit correction, not silent auth fallback.
  Explicit root reconnect for key/config carries one-shot connect-on-open authorization: consume
  it before initial reads, then invoke ordinary connect after Form mount only if original revision,
  foreground/exposure, ready/not-busy/not-closing and originating closed-generation checks still
  hold. Password mode never consumes this as submission. Reload/error/background cannot retry it.
- Remote bytes, including the historical `[session exited:` marker, never change lifecycle. Duplicate running after a terminal state cannot resurrect that generation. Data for an unknown/not-running generation is dropped before decoding; pending UTF-8 suffix cannot leak into a later session. Host checks generation again on write/resize/Stop.
- The authenticated SSH factory creates/retains the actual session on its original serial owner. Channel/PTY/shell must reach RUNNING before an SSH state event; input queue<=65536 and one acknowledged output<=32768 keep asynchronous UI work bounded. The production password entry uses the shared protected vault, actual TCP, explicit host-key review and a one-use password. See [password connection](../../../design/ssh-password-connect.md) and [channel contract](../../../design/ssh-session-channel.md).
- Password input never goes through terminal input/history. Clear it on submit, failure, cancel, route coverage, background and disposal. Pending cancellation and exact host-key decisions use independent methods while the terminal owner waits. Only the matching host RUNNING event enables terminal input; successful form dismissal must not cancel the attached shell. Defaults and connection request IDs are distinct, and retired defaults events cannot cancel the connection.
- Optional saved-password controls follow [the same connection extension](../../../design/ssh-saved-password.md).
  Default save/use choices are false. Saved availability is metadata only; never fill a password
  controller from a host reply. Explicit saved use sends empty password and save=false. Editing
  host/port/username clears password and both choices immediately, retires the old idle ID and
  debounces a fresh exact tuple status; revision checks discard late replies. Disable connect
  while status is pending. Save helper explains that a failed connection can retain the saved password.
- Forget one/all requires an exact confirmation and independent request. All forgetting is usable
  without a valid current tuple (empty host/username and port22 on the wire), and preserves
  TOTP/config/trust. Valid entered tuple stays after forgetting; refresh only its saved status.
  Pending forget and opt-in save connection cancellation wait for the original terminal write
  result under route protection; acknowledge committed/uncertain outcomes before leaving.
  Capture a save receipt before stale/mounted guards can discard it. Connected events do not
  navigate while closing; if handoff won, reissue cancellation for that exact request ID.
  Retain unacknowledged receipts after background/dialog removal. Lock/background/revision changes
  retire stale dialogs and cannot submit old approvals.
- `terminalOutputSnapshot` reads rendered `Terminal.lines`, joins soft-wrapped lines without adding a newline, preserves meaningful spaces, omits trailing empty buffer rows, and does not expose ANSI control codes. The selection page shows a point-in-time snapshot and does not stop the PTY.

## 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Host key changes | Stop the connection; offer exact-target view/forget/cancel, with no approval or automatic retry. |
| Connection is refused or the SSH handshake is interrupted | Explain the corresponding connection or negotiation/communication failure; neither implies a host-key change. |
| Config source is not known | Query available groups; require a user choice before the fresh preview, and never guess which group to mutate. |
| Recovery is backgrounded or the original request sends a late connected result | Retire its authority and any pending dialog; do not enter a terminal or reuse its secrets. |
| Root capability unavailable | Root item explains that authorization is needed and cannot start a PTY. |
| Start fails or exits immediately | Return to session picker; do not label the dead PTY as connected. |
| Input arrives with no active session or during stop | Do not send it to native. |
| IME is composing text | Do not send the active composing range. An IME may commit pinyin before candidate selection; then reconcile its later replacement by suffix deletion and insertion. |
| IME replaces already committed `ni` with `你` | Send `\x7f\x7f你` to replace the Shell suffix; keep IME focus and keyboard. |
| User taps Send while IME composition is still visible | Flush the visible preedit before `\r`; do not drop it or submit twice. |
| Tab changes the Shell line | Synchronize the rendered editable Shell line into the bottom field and its sent-text baseline; never send the synchronized text back to PTY. If parsing fails, clear the stale draft without writing to PTY. |
| Up/Down browses this session's submitted commands | Restore the selected command and any unsubmitted draft through the single PTY edit path; do not send raw arrow escape sequences that trigger long-line redraw defects on the tested App Shell. |
| Ctrl-C or Esc cancels local editing | Clear the bottom field and its sent-text baseline; leave PTY control semantics to the Shell. |
| Backspace on an apparently empty bottom field | Send PTY delete, allowing Shell editing of completion/history text. |
| Output buffer empty | Selection page shows an empty state; no storage/export is created. |
| Vendor IME chooses its own skin | Report the actual device appearance separately from the app's ordinary text input request. |

## 5. Good / Base / Bad Cases

- Good: View the changed endpoint's old fingerprints, or confirm a fresh exact-group preview;
  reconnect deliberately and review the new fingerprint as a new host key.
- Bad: Treat the changed-host error itself as permission to delete a record, infer a config
  source, automatically reconnect, or convert a late connected event into a running session.
- Good: Type `ec`, tap Tab, see Shell expand it to `echo` in both the terminal and the bottom input; edit and send from the bottom input. Tap Up after a completed command and see that session command in both places, with keyboard focus below.
- Base: Type `id` in App Shell and use the keyboard send key; output shows an app UID. Open Root PTY with existing authorization; `id` shows UID 0. Open and return from output selection without losing the PTY.
- Bad: Make xterm editable while also showing a bottom `TextField`; one input path may accept characters while the other owns Enter, reproducing the original unreliable interaction.

## 6. Tests Required

- SSH form/conflict: assert hierarchy and action placement, wide/narrow/large-text/keyboard
  reachability, exact target, cancellation without mutation, fresh preview before commit,
  protected groups, config source selection, background and late-event fences. Real-device
  evidence must separately exercise a synthetic key change and actual new-key review.
- Flutter widget test: picker entries and unavailable Root, read-only `TerminalView`, exactly five shortcuts with correct writes, visible bottom text, committed input/IME composing/delete/paste, candidate replacement after committed pinyin, one Enter, Tab line synchronization followed by edit/delete/send, bounded per-session Up/Down history and draft restoration, Ctrl-C/Esc reset, output selection and return, and start/stop state.
- Flutter widget test: a 320×568 screen with a keyboard inset does not overflow; terminal status chips may collapse while the keyboard is visible.
- Flutter widget test: protected-before-form, masked/cleared password, explicit endpoint/fingerprint review, reject/cancel/late-event handling, foreground recovery, visible errors and review actions with keyboard/large text, and successful terminal handoff. Independent SSH and Android runtime evidence remain separate from widget tests.
- Saved-password widget cases: explicit save and use payloads, default transient, no secret-bearing
  defaults/status, immediate tuple-edit deselection, late reply isolation, forget cancel/confirm/
  one/all including empty defaults, lock/background/uncertain and pending-cancel receipt.
  Exercise saved controls and destructive confirmation with small/landscape/large-text layouts.
- Screen-lock reconnect: running→failed-lock→optional-cleanup-pending→closed-lock (state and
  snapshot), reattachment first seeing cleanup-pending, no running resurrection, malformed/secret
  hint degradation, stale generations and App/Root replacement; no button before final close.
  Assert tap opens protected flow only, cancelled unlock sends no connect, transient password empty,
  and fresh key/config lookup rejects missing IDs, endpoint drift or key-membership mismatch.
  A single explicit root tap for key/config must make exactly one fresh connect request and still
  allow rejecting real host-key review. Background followed by delayed metadata/reload must never
  reuse the one-shot; password/invalid selection never auto-submit.
- Authorized Android UI test: use real controls to create the isolated vault, enter SSH fields, verify the complete offered endpoint/fingerprint, trust-and-save, submit a split-marker command and read its rendered output through the actual output snapshot page; return via its advertised AppBar control and close the session. Assert sensitive-window protection and release. Keep passwords out of shell arguments/logs/clipboard; a direct bridge fixture is separate evidence.
- Device check with explicit serial: app requests ordinary text (`EditorInfo.inputType` text), output tap does not show IME, keyboard send executes once, Tab completes in the live Shell and the bottom input reflects its resulting command line, Up/Down restore a submitted command and draft in both places, Ctrl-C interrupts a foreground command, selection/copy works, Root behavior matches current authorization. Record device-specific IME appearance and untested input methods honestly.

## 7. Wrong vs Correct

```dart
// Wrong: two editable surfaces compete for focus and Enter semantics.
TerminalView(terminal);
TextField(onSubmitted: (value) => sendTerminal('$value\n'));

// Correct: one live input path, with xterm as a read-only renderer.
TerminalView(terminal, readOnly: true);
TerminalCommandInput(onWrite: sendTerminal);
```

See [architecture](../../../design/architecture.md) for the current behavior and [verification](../../../design/verification.md) for dated device evidence.

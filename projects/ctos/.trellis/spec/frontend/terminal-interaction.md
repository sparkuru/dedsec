# Terminal interaction contract

## 1. Scope / Trigger

Use this contract when changing the Flutter terminal page, its IME input, PTY shortcuts, output selection, or the `ctos/native` terminal method calls. App and Root PTYs remain separate from the persistent Root collector and Vector bridge.

## 2. Signatures

- `TerminalCommandInput(onWrite: ValueChanged<String>, focusNode?: FocusNode)` emits committed input bytes as Dart strings.
- `TerminalCommandHistory` stores at most 100 commands submitted through the current PTY input field, preserving an unsubmitted draft while navigating.
- `terminalOutputSnapshot(Terminal) -> String` copies the active xterm buffer into selectable plain text.
- Native methods: `terminalStart({root: bool, columns: int, rows: int}) -> String`, `terminalWrite({text: String})`, `terminalResize({columns: int, rows: int})`, and `terminalStop()` on `ctos/native`.
- Native output arrives as UTF-8 byte lists on the `ctos/terminal` EventChannel and is decoded across chunk boundaries before `Terminal.write`.

## 3. Contracts

- With no session, show two item entries: `应用 Shell` and `Root PTY`. Root is unavailable unless the current Root capability is online. Start/stop transitions are guarded against duplicate actions; switching closes the current PTY before another is offered.
- The upper `TerminalView` is `readOnly: true`. It may render, scroll, and resize, but must not open the IME or become a second keyboard path. Text selection belongs to the separate output snapshot page. The bottom input is the sole keyboard target; the Shell prompt and edited command appear through PTY echo above.
- The bottom input requests `TextInputType.text` and `TextInputAction.send`, with no password input or automatic command rewriting. It visibly shows text currently typed into the field while sending committed edits to PTY, so Shell echo above may temporarily show the same text. Keep the IME editing value intact until Enter, including committed text, because some vendor IMEs commit pinyin before replacing it with a selected Hanzi. Compare the current committed Unicode text against the last text sent to PTY: erase the changed suffix with `\x7f` per code point, then send the replacement suffix. Hold active composing text until commit. Its invisible anchor exists only to make soft-keyboard delete observable; never forward the anchor to PTY. An empty-field delete emits `\x7f`; Enter/send flushes any visible composing text, emits `\r`, and resets the editing value.
- Before Tab, flush any visible IME text to PTY, then send `\t` for Shell completion. Keep the bottom text visible while waiting for the PTY response. Read the Shell's rendered editable line at the cursor and update the bottom input and its sent-text baseline without writing the synchronized text back to PTY. A unique completion such as `ec` to `echo` must show the completed command in both places; subsequent typing and deletion must edit that same line. If the rendered Shell line cannot be identified reliably, clear the stale bottom draft without a PTY write and explain that the upper terminal is authoritative.
- Up and Down browse commands submitted through the current PTY's bottom field. Replace the editable line by sending the same Unicode diff/backspaces as an IME edit, and update the field. Keep the draft when browsing back down, cap history at 100, and reset it for a new session. This avoids Android App Shell's observed long-command horizontal history redraw on the target device. It does not read Shell history created outside this input path. While Tab completion is pending, do not navigate history until the line settles.
- Ctrl-C forwards `\x03` and Esc forwards `\x1b`; both clear the canceled local editing span. Restore bottom-input focus after shortcut taps.
- Serialize `terminalWrite` calls. Session generation prevents queued input from reaching a later session. Native exit while a start call is pending must not be overwritten by that call's completion.
- `terminalOutputSnapshot` reads rendered `Terminal.lines`, joins soft-wrapped lines without adding a newline, preserves meaningful spaces, omits trailing empty buffer rows, and does not expose ANSI control codes. The selection page shows a point-in-time snapshot and does not stop the PTY.

## 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
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

- Good: Type `ec`, tap Tab, see Shell expand it to `echo` in both the terminal and the bottom input; edit and send from the bottom input. Tap Up after a completed command and see that session command in both places, with keyboard focus below.
- Base: Type `id` in App Shell and use the keyboard send key; output shows an app UID. Open Root PTY with existing authorization; `id` shows UID 0. Open and return from output selection without losing the PTY.
- Bad: Make xterm editable while also showing a bottom `TextField`; one input path may accept characters while the other owns Enter, reproducing the original unreliable interaction.

## 6. Tests Required

- Flutter widget test: picker entries and unavailable Root, read-only `TerminalView`, exactly five shortcuts with correct writes, visible bottom text, committed input/IME composing/delete/paste, candidate replacement after committed pinyin, one Enter, Tab line synchronization followed by edit/delete/send, bounded per-session Up/Down history and draft restoration, Ctrl-C/Esc reset, output selection and return, and start/stop state.
- Flutter widget test: a 320×568 screen with a keyboard inset does not overflow; terminal status chips may collapse while the keyboard is visible.
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

# Terminal input and output evidence

Date: 2026-09-26. Sources are the checked-out ctOS code and locally installed pinned `xterm` 4.0.0 source; this is planning evidence, not a validation result.

## Existing path

- `lib/main.dart:57-116` creates a 3000-line `Terminal`, forwards xterm output to native `terminalWrite`, streams native bytes into xterm, and forwards resize events.
- `lib/main.dart:1275-1362` shows an editable `TerminalView` plus a separate bottom `TextField`. The former has its own IME path; the latter sends a whole line on submit. The five control buttons already send PTY sequences, but three extra command shortcuts are present.
- `MainActivity.java:162-181,195-231` exposes start/write/resize/stop and returns `app · PTY` or `root · PTY`. Root start checks current Root collector authorization. Native behavior need not change for the requested UI flow.

## Pinned xterm 4.0.0 behavior

- `TerminalView` exposes `readOnly`, `keyboardType`, `focusNode`, and `autofocus`. In read-only mode it does not create the editable input connection or hardware keyboard listener; resize and scrolling remain in the renderer. Source: `lib/src/terminal_view.dart:24-50,258-310` in the locally installed package.
- Its default input type is `TextInputType.emailAddress`; its internal `CustomTextEdit` requests `autocorrect: false`, `enableSuggestions: false`, and `enableIMEPersonalizedLearning: false`. This may contribute to a vendor keyboard treating it as special input. Source: `terminal_view.dart:43`, `ui/custom_text_edit.dart:157-171`. The app should use an ordinary text input configuration in its sole bottom input surface and verify actual IME behavior on PLR110.
- `Terminal.lines` exposes the active rendered buffer and `BufferLine.getText()` returns rendered text without ANSI control codes; `BufferLine.isWrapped` identifies soft line wraps. Source: `lib/src/terminal.dart:205-217`, `lib/src/core/buffer/line.dart:23-32,325-343`. A point-in-time copy of these lines covers the currently retained scrollback, bounded by `Terminal(maxLines: 3000)`.

## Design consequences

- Shell completion and shell history are PTY line-editing operations. When the bottom input is live, text insertion, deletion, Enter, Tab, Esc, and arrows must all reach the same PTY session in order, without a second keyboard path from the output view.
- A normal Flutter `TextField` that simply clears on every `onChanged` loses empty-field backspace events on some soft keyboards and can interrupt IME composition. The implementation must explicitly handle deletion and committed composition, or use an input-client mechanism with equivalent behavior, then verify on device.
- Ordinary text IME settings are app requests. The installed keyboard may still choose a vendor layout; device acceptance must record what appeared rather than claim the app can force an IME implementation.

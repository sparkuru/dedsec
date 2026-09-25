import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:xterm/xterm.dart';

/// The IME keeps a small invisible prefix so deleting on an otherwise empty
/// input still produces an editing update. Shell echo remains in TerminalView.
class TerminalCommandInput extends StatefulWidget {
  const TerminalCommandInput({
    super.key,
    required this.onWrite,
    this.focusNode,
  });

  final ValueChanged<String> onWrite;
  final FocusNode? focusNode;

  @override
  TerminalCommandInputState createState() => TerminalCommandInputState();
}

class TerminalCommandInputState extends State<TerminalCommandInput> {
  static const _anchor = '\u200b\u200b';
  final _controller = TextEditingController(text: _anchor);
  bool _resetting = false;
  String _sentText = '';

  @override
  void initState() {
    super.initState();
    _controller.selection = const TextSelection.collapsed(offset: 2);
    _controller.addListener(_handleEdit);
  }

  @override
  void dispose() {
    _controller.removeListener(_handleEdit);
    _controller.dispose();
    super.dispose();
  }

  void _handleEdit() {
    if (_resetting) return;
    final value = _controller.value;
    final hasAnchor = value.text.startsWith(_anchor);
    final anchorLength = hasAnchor ? _anchor.length : 0;
    final content = value.text.substring(anchorLength).replaceAll('\u200b', '');
    var committed = content;
    if (value.composing.isValid && !value.composing.isCollapsed) {
      final start = value.composing.start - anchorLength;
      final end = value.composing.end - anchorLength;
      if (start >= 0 && end <= content.length) {
        committed = content.replaceRange(start, end, '');
      }
    }
    final hadSentText = _sentText.isNotEmpty;
    _syncShell(committed);

    // Keep IME text intact through candidate replacement. Restore the anchor
    // only when an empty field consumes it to signal a Shell backspace.
    if (content.isEmpty &&
        value.text.length < _anchor.length &&
        value.composing.isCollapsed) {
      if (!hadSentText) widget.onWrite('\x7f');
      _resetInput();
    }
  }

  void _syncShell(String text) {
    final before = _sentText.runes.toList();
    final after = text.runes.toList();
    var common = 0;
    while (common < before.length &&
        common < after.length &&
        before[common] == after[common]) {
      common++;
    }
    final deletes = List.filled(before.length - common, '\x7f').join();
    final insertion = String.fromCharCodes(after.skip(common));
    if (deletes.isNotEmpty || insertion.isNotEmpty) {
      widget.onWrite('$deletes$insertion');
    }
    _sentText = text;
  }

  void _resetInput() {
    _resetting = true;
    _controller.value = const TextEditingValue(
      text: _anchor,
      selection: TextSelection.collapsed(offset: 2),
    );
    _resetting = false;
  }

  void _submit() {
    final value = _controller.value;
    final anchorLength = value.text.startsWith(_anchor) ? _anchor.length : 0;
    _syncShell(value.text.substring(anchorLength).replaceAll('\u200b', ''));
    widget.onWrite('\r');
    _sentText = '';
    _resetInput();
  }

  /// A Shell control may change its editable line without an IME edit event.
  /// Flush visible composition when the control operates on the current line,
  /// then start a fresh local editing span for text typed afterward.
  void prepareForShellControl({required bool flushComposing}) {
    if (flushComposing) {
      final value = _controller.value;
      final anchorLength = value.text.startsWith(_anchor) ? _anchor.length : 0;
      _syncShell(value.text.substring(anchorLength).replaceAll('\u200b', ''));
    }
    if (!flushComposing) {
      _sentText = '';
      _resetInput();
    }
  }

  String get visibleText {
    final text = _controller.text;
    return text
        .substring(text.startsWith(_anchor) ? _anchor.length : 0)
        .replaceAll('\u200b', '');
  }

  bool get isComposing {
    final composing = _controller.value.composing;
    return composing.isValid && !composing.isCollapsed;
  }

  /// Reconcile the visible draft and PTY diff baseline without writing bytes.
  void applyShellLine(String line) {
    _sentText = line;
    _resetting = true;
    _controller.value = TextEditingValue(
      text: '$_anchor$line',
      selection: TextSelection.collapsed(offset: _anchor.length + line.length),
    );
    _resetting = false;
  }

  void clearLocalWithoutWrite() {
    _sentText = '';
    _resetInput();
  }

  /// Replace the current command through the same PTY edit path as IME text.
  void replaceFromHistory(String line) {
    _syncShell(line);
    applyShellLine(line);
  }

  @override
  Widget build(BuildContext context) => Semantics(
    label: '命令输入',
    child: TextField(
      key: const Key('terminal-command-input'),
      controller: _controller,
      focusNode: widget.focusNode,
      keyboardType: TextInputType.text,
      textInputAction: TextInputAction.send,
      textCapitalization: TextCapitalization.none,
      autocorrect: false,
      enableSuggestions: true,
      smartDashesType: SmartDashesType.disabled,
      smartQuotesType: SmartQuotesType.disabled,
      style: const TextStyle(fontFamily: 'monospace', fontSize: 13),
      decoration: InputDecoration(
        isDense: true,
        suffixIcon: IconButton(
          tooltip: '执行命令',
          onPressed: _submit,
          icon: const Icon(Icons.send),
        ),
      ),
      onEditingComplete: () {},
      onSubmitted: (_) => _submit(),
    ),
  );
}

/// Commands submitted through this PTY's sole input field.
class TerminalCommandHistory {
  TerminalCommandHistory({this.capacity = 100});

  final int capacity;
  final List<String> _commands = [];
  int? _cursor;
  String _draft = '';

  void reset() {
    _commands.clear();
    cancelNavigation();
  }

  void cancelNavigation() {
    _cursor = null;
    _draft = '';
  }

  void record(String command) {
    if (command.trim().isNotEmpty &&
        !command.contains('\n') &&
        !command.contains('\r')) {
      if (_commands.isEmpty || _commands.last != command) {
        _commands.add(command);
        if (_commands.length > capacity) _commands.removeAt(0);
      }
    }
    cancelNavigation();
  }

  String? previous(String current) {
    if (_commands.isEmpty) return null;
    _cursor ??= _commands.length;
    if (_cursor == _commands.length) _draft = current;
    if (_cursor! > 0) _cursor = _cursor! - 1;
    return _commands[_cursor!];
  }

  String? next() {
    if (_cursor == null) return null;
    if (_cursor! < _commands.length - 1) {
      _cursor = _cursor! + 1;
      return _commands[_cursor!];
    }
    _cursor = null;
    return _draft;
  }
}

/// Reads the rendered logical line at xterm's cursor, including soft wraps.
/// The terminal exposes display cells, not a semantic Shell edit buffer.
String? terminalCursorLogicalLine(Terminal terminal) {
  if (terminal.isUsingAltBuffer) return null;
  final buffer = terminal.buffer;
  final cursorRow = buffer.absoluteCursorY;
  if (cursorRow < 0 || cursorRow >= terminal.lines.length) return null;
  var first = cursorRow;
  while (first > 0 && terminal.lines[first].isWrapped) {
    first--;
  }
  var last = cursorRow;
  while (last + 1 < terminal.lines.length &&
      terminal.lines[last + 1].isWrapped) {
    last++;
  }
  final result = StringBuffer();
  for (var row = first; row <= last; row++) {
    final line = terminal.lines[row];
    if (row != last) {
      result.write(line.getText());
      continue;
    }
    var lastVisibleColumn = 0;
    for (var column = line.length - 1; column >= 0; column--) {
      final codePoint = line.getCodePoint(column);
      if (codePoint != 0 && codePoint != 32) {
        lastVisibleColumn = column + 1;
        break;
      }
    }
    final end = math.max(
      lastVisibleColumn,
      row == cursorRow ? buffer.cursorX : 0,
    );
    result.write(line.getText(0, end));
  }
  return result.toString();
}

/// Uses the displayed prompt prefix as an anchor without parsing Shell syntax.
class TerminalEditableLineTracker {
  String? _promptPrefix;

  void reset() => _promptPrefix = null;

  void captureBeforeInput(Terminal terminal) {
    _promptPrefix ??= terminalCursorLogicalLine(terminal);
  }

  bool beginControl(Terminal terminal, String visibleText) {
    final line = terminalCursorLogicalLine(terminal);
    if (line == null) return false;
    if (_promptPrefix == null || !line.startsWith(_promptPrefix!)) {
      _promptPrefix = visibleText.isNotEmpty && line.endsWith(visibleText)
          ? line.substring(0, line.length - visibleText.length)
          : line;
    }
    return true;
  }

  String? readCurrentCommand(Terminal terminal) {
    final prefix = _promptPrefix;
    final line = terminalCursorLogicalLine(terminal);
    if (prefix == null || line == null || !line.startsWith(prefix)) {
      return null;
    }
    return line.substring(prefix.length);
  }
}

String terminalOutputSnapshot(Terminal terminal) {
  final lines = <String>[];
  for (var index = 0; index < terminal.lines.length; index++) {
    final line = terminal.lines[index];
    final text = line.getText();
    if (line.isWrapped && lines.isNotEmpty) {
      lines[lines.length - 1] += text;
    } else {
      lines.add(text);
    }
  }
  while (lines.isNotEmpty && lines.last.isEmpty) {
    lines.removeLast();
  }
  return lines.join('\n');
}

class TerminalOutputPage extends StatelessWidget {
  const TerminalOutputPage({super.key, required this.output});

  final String output;

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('当前输出')),
    body: output.isEmpty
        ? const Center(child: Text('暂无可选择的输出'))
        : SelectionArea(
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(16),
              child: SizedBox(
                width: double.infinity,
                child: Text(
                  output,
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 13),
                ),
              ),
            ),
          ),
  );
}

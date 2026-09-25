import 'dart:async';
import 'dart:convert';

import 'package:ctos/main.dart';
import 'package:ctos/terminal_interaction.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:xterm/xterm.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('live input waits for IME commit and detects empty backspace', (
    tester,
  ) async {
    final writes = <String>[];
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: TerminalCommandInput(onWrite: writes.add)),
      ),
    );
    final field = tester.widget<TextField>(find.byType(TextField));
    expect(field.keyboardType, TextInputType.text);
    expect(field.textInputAction, TextInputAction.send);
    expect(field.obscureText, isFalse);
    expect(field.enableSuggestions, isTrue);
    expect(field.decoration?.labelText, isNull);
    expect(field.decoration?.hintText, isNull);
    expect(field.style?.color, isNot(Colors.transparent));

    final controller = field.controller!;
    controller.value = const TextEditingValue(
      text: '\u200b\u200bni',
      selection: TextSelection.collapsed(offset: 4),
      composing: TextRange(start: 2, end: 4),
    );
    expect(writes, isEmpty);
    await tester.tap(find.byTooltip('执行命令'));
    expect(writes, ['ni', '\r']);
    expect(controller.text, '\u200b\u200b');
    writes.clear();
    controller.value = const TextEditingValue(
      text: '\u200b\u200bni',
      selection: TextSelection.collapsed(offset: 4),
      composing: TextRange(start: 2, end: 4),
    );
    expect(writes, isEmpty);
    controller.value = const TextEditingValue(
      text: '\u200b\u200b你',
      selection: TextSelection.collapsed(offset: 3),
    );
    expect(writes, ['你']);
    expect(controller.text, '\u200b\u200b你');

    await tester.tap(find.byTooltip('执行命令'));
    expect(writes.last, '\r');
    expect(controller.text, '\u200b\u200b');

    controller.value = const TextEditingValue(
      text: '\u200b',
      selection: TextSelection.collapsed(offset: 1),
    );
    expect(writes, ['你', '\r', '\x7f']);

    controller.value = const TextEditingValue(
      text: '\u200b\u200bpasted text',
      selection: TextSelection.collapsed(offset: 13),
    );
    expect(writes.last, 'pasted text');
    await tester.tap(find.byTooltip('执行命令'));
    expect(writes.last, '\r');
  });

  testWidgets(
    'IME candidate replaces already echoed pinyin without losing focus',
    (tester) async {
      final writes = <String>[];
      final focus = FocusNode();
      addTearDown(focus.dispose);
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: TerminalCommandInput(onWrite: writes.add, focusNode: focus),
          ),
        ),
      );
      await tester.tap(find.byKey(const Key('terminal-command-input')));
      await tester.pump();
      expect(focus.hasFocus, isTrue);
      final controller = tester
          .widget<TextField>(find.byType(TextField))
          .controller!;

      // Some IMEs first report pinyin as ordinary committed text, then replace
      // it with the selected Chinese candidate.
      controller.value = const TextEditingValue(
        text: '\u200b\u200bni',
        selection: TextSelection.collapsed(offset: 4),
      );
      expect(writes, ['ni']);
      controller.value = const TextEditingValue(
        text: '\u200b\u200b你',
        selection: TextSelection.collapsed(offset: 3),
      );
      expect(writes, ['ni', '\x7f\x7f你']);
      expect(controller.text, '\u200b\u200b你');
      expect(focus.hasFocus, isTrue);

      controller.value = const TextEditingValue(
        text: '\u200b\u200b你 ni',
        selection: TextSelection.collapsed(offset: 6),
        composing: TextRange(start: 4, end: 6),
      );
      expect(writes.last, ' ');
      controller.value = const TextEditingValue(
        text: '\u200b\u200b你 你',
        selection: TextSelection.collapsed(offset: 5),
      );
      expect(writes.last, '你');

      controller.value = const TextEditingValue(
        text: '\u200b\u200b你 你! ',
        selection: TextSelection.collapsed(offset: 7),
      );
      expect(writes.last, '! ');
      controller.value = const TextEditingValue(
        text: '\u200b',
        selection: TextSelection.collapsed(offset: 1),
      );
      expect(writes.last, '\x7f\x7f\x7f\x7f\x7f');
      expect(controller.text, '\u200b\u200b');
    },
  );

  testWidgets('Shell line sync replaces visible draft without PTY echo', (
    tester,
  ) async {
    final writes = <String>[];
    final key = GlobalKey<TerminalCommandInputState>();
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: TerminalCommandInput(key: key, onWrite: writes.add),
        ),
      ),
    );
    final controller = tester
        .widget<TextField>(find.byType(TextField))
        .controller!;
    controller.value = const TextEditingValue(
      text: '\u200b\u200bec',
      selection: TextSelection.collapsed(offset: 4),
      composing: TextRange(start: 2, end: 4),
    );
    expect(writes, isEmpty);
    key.currentState!.prepareForShellControl(flushComposing: true);
    expect(writes, ['ec']);
    expect(controller.text, '\u200b\u200bec');

    key.currentState!.applyShellLine('echo');
    expect(controller.text, '\u200b\u200becho');
    expect(writes, ['ec']);
    controller.value = const TextEditingValue(
      text: '\u200b\u200bechox',
      selection: TextSelection.collapsed(offset: 7),
    );
    expect(writes, ['ec', 'x']);
    controller.value = const TextEditingValue(
      text: '\u200b\u200becho',
      selection: TextSelection.collapsed(offset: 6),
    );
    expect(writes.last, '\x7f');
    key.currentState!.prepareForShellControl(flushComposing: false);
    expect(controller.text, '\u200b\u200b');
    expect(writes, ['ec', 'x', '\x7f']);

    // An IME may replace the entire editing value with one character.
    controller.value = const TextEditingValue(
      text: 'a',
      selection: TextSelection.collapsed(offset: 1),
    );
    expect(writes.last, 'a');
    controller.value = const TextEditingValue(
      text: 'b',
      selection: TextSelection.collapsed(offset: 1),
    );
    expect(writes.last, '\x7fb');
  });

  test(
    'rendered Shell line tracker handles completion, history and new prompt',
    () {
      final terminal = Terminal(maxLines: 3000)..resize(18, 8);
      final tracker = TerminalEditableLineTracker();
      terminal.write('λ /tmp › ');
      tracker.captureBeforeInput(terminal);
      terminal.write('i');
      expect(tracker.beginControl(terminal, 'i'), isTrue);
      terminal.write('d');
      expect(tracker.readCurrentCommand(terminal), 'id');

      terminal.write('\r\x1b[2Kλ /tmp › echo hi');
      expect(tracker.readCurrentCommand(terminal), 'echo hi');
      terminal.write('\r\x1b[2Kλ /tmp › ');
      expect(tracker.readCurrentCommand(terminal), '');

      terminal.write('\r\nλ /new › ');
      tracker.reset();
      tracker.captureBeforeInput(terminal);
      terminal.write('cd');
      expect(tracker.beginControl(terminal, 'cd'), isTrue);
      terminal.write('x');
      expect(tracker.readCurrentCommand(terminal), 'cdx');

      final narrow = Terminal(maxLines: 3000)..resize(12, 8);
      final wrappedTracker = TerminalEditableLineTracker();
      narrow.write('p> ');
      wrappedTracker.captureBeforeInput(narrow);
      narrow.write('abcdefghij');
      expect(wrappedTracker.beginControl(narrow, 'abcdefghij'), isTrue);
      narrow.write('k');
      expect(wrappedTracker.readCurrentCommand(narrow), 'abcdefghijk');

      narrow.write('\x1b[?1049h');
      expect(wrappedTracker.readCurrentCommand(narrow), isNull);
    },
  );

  test('session history preserves draft, bounds entries, and resets', () {
    final history = TerminalCommandHistory(capacity: 2);
    expect(history.previous('draft'), isNull);
    history.record('id');
    history.record('echo hello');
    history.record('echo hello');
    expect(history.previous('draft'), 'echo hello');
    expect(history.previous('echo hello'), 'id');
    expect(history.previous('id'), 'id');
    expect(history.next(), 'echo hello');
    expect(history.next(), 'draft');
    expect(history.next(), isNull);
    history.record('pwd');
    expect(history.previous(''), 'pwd');
    expect(history.previous('pwd'), 'echo hello');
    history.reset();
    expect(history.previous(''), isNull);
    history.record('echo one\necho two');
    expect(history.previous(''), isNull);
    history.record('id');
    history.record('pwd');
    expect(history.previous('draft'), 'pwd');
    expect(history.previous('pwd'), 'id');
    history.cancelNavigation();
    expect(history.previous(''), 'pwd');
  });

  test('output snapshot uses rendered text and joins soft wraps', () {
    final terminal = Terminal(maxLines: 3000);
    terminal.write('first\r\nsecond\r\n');
    expect(terminalOutputSnapshot(terminal), contains('first\nsecond'));
    terminal.write('\x1b[31mred\x1b[0m');
    expect(terminalOutputSnapshot(terminal), contains('red'));
    expect(terminalOutputSnapshot(terminal), isNot(contains('\x1b[31m')));
    final wrapped = List.filled(terminal.viewWidth + 5, 'x').join();
    terminal.write('\r\n$wrapped');
    expect(terminalOutputSnapshot(terminal), contains(wrapped));
    terminal.write('\r\ncolumn  \r\nnext');
    expect(terminalOutputSnapshot(terminal), contains('column  \nnext'));
  });

  testWidgets('entry, controls, output page, and close preserve PTY flow', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(412, 915);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetViewInsets);
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    final writes = <String>[];
    var starts = 0;
    var stops = 0;
    Completer<void>? holdNextWrite;
    messenger.setMockMethodCallHandler(
      const MethodChannel('ctos/terminal'),
      (_) async => null,
    );
    messenger.setMockMethodCallHandler(native, (call) async {
      switch (call.method) {
        case 'rootAuto':
          return jsonEncode({'root': false, 'attempted': false});
        case 'snapshot':
          return jsonEncode({
            'root': false,
            'moduleActive': false,
            'networks': [],
            'kernel': {
              'source': 'test',
              'elapsed': 1000,
              'interfaces': [],
              'routes': '',
            },
          });
        case 'deviceSnapshot':
          return jsonEncode({});
        case 'terminalStart':
          starts++;
          return 'app · PTY';
        case 'terminalWrite':
          writes.add((call.arguments as Map)['text'] as String);
          final hold = holdNextWrite;
          holdNextWrite = null;
          if (hold != null) await hold.future;
          return null;
        case 'terminalStop':
          stops++;
          return null;
      }
      return null;
    });
    addTearDown(() {
      messenger.setMockMethodCallHandler(native, null);
      messenger.setMockMethodCallHandler(
        const MethodChannel('ctos/terminal'),
        null,
      );
    });
    Future<void> emitTerminal(String output) async {
      await messenger.handlePlatformMessage(
        'ctos/terminal',
        const StandardMethodCodec().encodeSuccessEnvelope(utf8.encode(output)),
        null,
      );
      await tester.pump();
    }

    await tester.pumpWidget(const CtosApp());
    await tester.pump(const Duration(milliseconds: 100));
    await tester.tap(find.text('终端').last);
    await tester.pumpAndSettle();
    expect(find.text('应用 Shell'), findsOneWidget);
    expect(find.text('Root PTY'), findsOneWidget);
    expect(find.text('Root 未连接，请先在工作台授权'), findsOneWidget);
    expect(
      tester
          .widget<ListTile>(find.byKey(const Key('terminal-root-entry')))
          .onTap,
      isNull,
    );
    await tester.tap(find.byKey(const Key('terminal-app-entry')));
    await tester.pumpAndSettle();
    expect(starts, 1);
    expect(find.text('app · PTY'), findsOneWidget);
    expect(
      tester
          .widget<TerminalView>(find.byKey(const Key('terminal-output-view')))
          .readOnly,
      isTrue,
    );
    tester.view.physicalSize = const Size(320, 568);
    tester.view.viewInsets = const FakeViewPadding(bottom: 300);
    await tester.pump();
    expect(tester.takeException(), isNull);
    tester.view.viewInsets = FakeViewPadding.zero;
    tester.view.physicalSize = const Size(412, 915);
    await tester.pump();
    for (final label in ['Ctrl-C', 'Tab', 'Esc', '↑', '↓']) {
      expect(find.text(label), findsOneWidget);
    }
    for (final label in ['ip addr', 'ss', 'id']) {
      expect(find.text(label), findsNothing);
    }
    await tester.tap(find.byKey(const Key('terminal-command-input')));
    await tester.pump();
    expect(
      tester
          .widget<TextField>(find.byKey(const Key('terminal-command-input')))
          .focusNode!
          .hasFocus,
      isTrue,
    );
    await tester.tap(find.text('Tab'));
    await tester.pump();
    expect(
      tester
          .widget<TextField>(find.byKey(const Key('terminal-command-input')))
          .focusNode!
          .hasFocus,
      isTrue,
    );
    await tester.tap(find.text('↑'));
    await tester.tap(find.text('↓'));
    await tester.tap(find.text('Ctrl-C'));
    await tester.tap(find.text('Esc'));
    await tester.pump();
    expect(writes, ['\t', '\x03', '\x1b']);

    final input = tester.widget<TextField>(
      find.byKey(const Key('terminal-command-input')),
    );
    input.controller!.value = const TextEditingValue(
      text: '\u200b\u200bni',
      selection: TextSelection.collapsed(offset: 4),
      composing: TextRange(start: 2, end: 4),
    );
    final writesBeforeEmptyHistory = writes.length;
    await tester.tap(find.text('↑'));
    await tester.pump();
    expect(writes, hasLength(writesBeforeEmptyHistory));
    expect(input.controller!.value.composing, const TextRange(start: 2, end: 4));
    await tester.tap(find.text('Ctrl-C'));
    await tester.pump();

    await emitTerminal('\r\n:/tmp \$ ');
    await tester.enterText(
      find.byKey(const Key('terminal-command-input')),
      'i',
    );
    await tester.pump();
    expect(writes.last, 'i');
    expect(input.controller!.text, 'i');
    await emitTerminal('i');
    await tester.tap(find.text('Tab'));
    await tester.pump();
    expect(writes.last, '\t');
    expect(input.controller!.text, 'i');
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 450)),
    );
    await tester.pump(const Duration(milliseconds: 100));
    expect(input.controller!.text, 'i');
    await emitTerminal('d');
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 220)),
    );
    await tester.pump(const Duration(milliseconds: 100));
    expect(input.controller!.text, '\u200b\u200bid');
    await tester.enterText(
      find.byKey(const Key('terminal-command-input')),
      '\u200b\u200bidx',
    );
    await tester.pump();
    expect(writes.last, 'x');
    await tester.enterText(
      find.byKey(const Key('terminal-command-input')),
      '\u200b\u200bid',
    );
    await tester.pump();
    expect(writes.last, '\x7f');
    await tester.testTextInput.receiveAction(TextInputAction.send);
    await tester.pump();
    expect(writes.last, '\r');
    expect(writes.where((text) => text == '\r'), hasLength(1));

    await emitTerminal('\r\n:/tmp \$ ');
    await tester.tap(find.text('↑'));
    await tester.pump();
    expect(writes.last, 'id');
    expect(input.controller!.text, '\u200b\u200bid');
    await tester.tap(find.text('↓'));
    await tester.pump();
    expect(writes.last, '\x7f\x7f');
    expect(input.controller!.text, '\u200b\u200b');

    await tester.enterText(
      find.byKey(const Key('terminal-command-input')),
      'draft',
    );
    await tester.pump();
    expect(writes.last, 'draft');
    await tester.tap(find.text('↑'));
    await tester.pump();
    expect(writes.last, '\x7f\x7f\x7f\x7f\x7fid');
    expect(input.controller!.text, '\u200b\u200bid');
    await tester.tap(find.text('↓'));
    await tester.pump();
    expect(writes.last, '\x7f\x7fdraft');
    expect(input.controller!.text, '\u200b\u200bdraft');
    await tester.tap(find.text('Ctrl-C'));
    await tester.pump();

    // A character typed while Tab is awaiting Shell output must be appended
    // after the completion, then remain editable with one Backspace.
    await tester.enterText(
      find.byKey(const Key('terminal-command-input')),
      'i',
    );
    await emitTerminal('i');
    await tester.tap(find.text('Tab'));
    await tester.pump();
    await tester.enterText(
      find.byKey(const Key('terminal-command-input')),
      '\u200b\u200bix',
    );
    await tester.pump();
    expect(writes.last, 'x');
    await emitTerminal('d');
    await tester.pump(const Duration(milliseconds: 120));
    expect(input.controller!.text, '\u200b\u200bix');
    await emitTerminal('x');
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 220)),
    );
    await tester.pump(const Duration(milliseconds: 100));
    expect(input.controller!.text, '\u200b\u200bidx');
    await tester.enterText(
      find.byKey(const Key('terminal-command-input')),
      '\u200b\u200bid',
    );
    await tester.pump();
    expect(writes.last, '\x7f');

    await tester.testTextInput.receiveAction(TextInputAction.send);
    await tester.pump();
    await emitTerminal('\r\n:/tmp \$ ');
    await tester.enterText(
      find.byKey(const Key('terminal-command-input')),
      'i',
    );
    await emitTerminal('i');
    await tester.tap(find.text('Tab'));
    await tester.pump();
    final heldBackspace = Completer<void>();
    holdNextWrite = heldBackspace;
    await tester.enterText(
      find.byKey(const Key('terminal-command-input')),
      '\u200b\u200b',
    );
    await tester.pump();
    expect(writes.last, '\x7f');
    await emitTerminal('d');
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 550)),
    );
    await tester.pump(const Duration(milliseconds: 100));
    expect(input.controller!.text, isNot('\u200b\u200bid'));
    heldBackspace.complete();
    await tester.pump();
    await emitTerminal('\b \b');
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 550)),
    );
    await tester.pump(const Duration(milliseconds: 100));
    expect(input.controller!.text, '\u200b\u200bi');

    await tester.tap(find.text('选择输出'));
    await tester.pumpAndSettle();
    expect(find.text('当前输出'), findsOneWidget);
    expect(find.byType(SelectionArea), findsOneWidget);
    await tester.pageBack();
    await tester.pumpAndSettle();
    expect(stops, 0);
    expect(find.text('输入命令'), findsNothing);
    expect(
      tester.getTopLeft(find.text('换用')).dx,
      lessThan(tester.getTopLeft(find.text('选择输出')).dx),
    );
    expect(
      tester.getTopLeft(find.text('选择输出')).dx,
      lessThan(tester.getTopLeft(find.byTooltip('关闭会话')).dx),
    );
    tester.view.physicalSize = const Size(320, 700);
    tester.view.viewInsets = const FakeViewPadding(bottom: 300);
    tester.platformDispatcher.textScaleFactorTestValue = 1.5;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(
      tester.getTopLeft(find.text('换用')).dx,
      lessThan(tester.getTopLeft(find.text('选择输出')).dx),
    );
    expect(
      tester.getTopLeft(find.text('选择输出')).dx,
      lessThan(tester.getTopLeft(find.byTooltip('关闭会话')).dx),
    );
    expect(
      tester.getBottomRight(find.byTooltip('关闭会话')).dx,
      lessThanOrEqualTo(320),
    );
    await tester.tap(find.byTooltip('关闭会话'));
    await tester.pumpAndSettle();
    expect(stops, 1);
    expect(find.text('应用 Shell'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.tap(find.byKey(const Key('terminal-app-entry')));
    await tester.pumpAndSettle();
    expect(starts, 2);
    final writesBeforeNewHistory = writes.length;
    await tester.tap(find.text('↑'));
    await tester.pump();
    expect(writes, hasLength(writesBeforeNewHistory));
    await tester.tap(find.byTooltip('关闭会话'));
    await tester.pumpAndSettle();
    expect(stops, 2);
  });
}

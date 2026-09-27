import 'package:ctos/workbench.dart';
import 'package:ctos/workbench/parameter_field.dart';
import 'package:ctos/workbench/result_card.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'workbench_usability_test.dart' as fixture;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  WidgetController.hitTestWarningShouldBeFatal = true;

  testWidgets('secret presentation masks ordinary IME and preserves editing', (
    tester,
  ) async {
    final semantics = tester.ensureSemantics();
    try {
      await tester.pumpWidget(
        MaterialApp(home: ScriptPage(script: fixture.password())),
      );
      final field = fixture.input('seed');
      await tester.tap(field);
      await tester.pump();
      const original = '  A🙂e\u0301中  ';
      const editing = TextEditingValue(
        text: original,
        selection: TextSelection(baseOffset: 2, extentOffset: 7),
        composing: TextRange(start: 2, end: 8),
      );
      tester.testTextInput.updateEditingValue(editing);
      await tester.pump();
      final editable = tester.widget<EditableText>(
        find.descendant(of: field, matching: find.byType(EditableText)),
      );
      expect(editable.obscureText, isFalse);
      expect(editable.enableSuggestions, isTrue);
      expect(editable.autocorrect, isFalse);
      expect(editable.enableIMEPersonalizedLearning, isFalse);
      expect(editable.keyboardType, TextInputType.text);
      expect(editable.smartDashesType, SmartDashesType.disabled);
      expect(editable.smartQuotesType, SmartQuotesType.disabled);
      expect(tester.testTextInput.setClientArgs!['obscureText'], isFalse);
      expect(tester.testTextInput.setClientArgs!['enableSuggestions'], isTrue);
      expect(tester.testTextInput.setClientArgs!['autocorrect'], isFalse);
      expect(
        tester.testTextInput.setClientArgs!['enableIMEPersonalizedLearning'],
        isFalse,
      );
      expect(editable.controller.value, editing);
      final controller = editable.controller as MaskedTextController;
      expect(
        controller
            .buildTextSpan(context: tester.element(field), withComposing: true)
            .toPlainText(),
        '•' * original.length,
      );
      final data = tester
          .getSemantics(find.bySemanticsLabel('种子'))
          .getSemanticsData();
      expect(data.value, '•' * original.length);
      expect(data.flagsCollection.isObscured, isTrue);
      expect(data.flagsCollection.isTextField, isTrue);
      expect(find.text('技术详情'), findsNothing);
      final eye = find.descendant(
        of: find.byKey(const ValueKey('seed')),
        matching: find.byTooltip('显示'),
      );
      await tester.tap(eye);
      await tester.pump();
      expect(controller.value, editing);
      expect(
        controller
            .buildTextSpan(context: tester.element(field), withComposing: true)
            .toPlainText(),
        original,
      );
      await tester.tap(
        find.descendant(
          of: find.byKey(const ValueKey('seed')),
          matching: find.byTooltip('隐藏'),
        ),
      );
      await tester.pump();
      expect(controller.value, editing);
      expect(tester.testTextInput.setClientArgs!['obscureText'], isFalse);
      expect(tester.testTextInput.setClientArgs!['enableSuggestions'], isTrue);
    } finally {
      semantics.dispose();
    }
  });

  for (final config in [
    (size: const Size(375, 812), scale: 1.0),
    (size: const Size(812, 375), scale: 1.0),
    (size: const Size(375, 812), scale: 2.0),
  ]) {
    testWidgets('popup and actions align ${config.size} ${config.scale}', (
      tester,
    ) async {
      tester.view.physicalSize = config.size;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      Widget app(Widget child) => MaterialApp(
        theme: ThemeData.dark(useMaterial3: true),
        home: MediaQuery(
          data: MediaQueryData(
            size: config.size,
            textScaler: TextScaler.linear(config.scale),
            disableAnimations: true,
          ),
          child: child,
        ),
      );
      await tester.pumpWidget(app(ScriptPage(script: fixture.encoder())));
      final source = find.byType(DropdownButtonFormField<String>).first;
      await fixture.tapVisible(tester, source);
      final expected = tester.getRect(source);
      final menu = find
          .ancestor(of: find.text('文件').last, matching: find.byType(Material))
          .first;
      final actual = tester.getRect(menu);
      expect(actual.left, closeTo(expected.left, 0.1));
      expect(actual.width, closeTo(expected.width, 0.1));
      await tester.tap(find.text('文件').last);
      await tester.pumpAndSettle();
      final fileButton = find.ancestor(
        of: find.text('选择文件'),
        matching: find.byWidgetPredicate((widget) => widget is OutlinedButton),
      );
      await fixture.revealWidget(tester, fileButton);
      expect(tester.getSize(fileButton).width, expected.width);
      final run = find.ancestor(
        of: find.text('运行'),
        matching: find.byWidgetPredicate((widget) => widget is FilledButton),
      );
      await fixture.revealWidget(tester, run);
      expect(tester.getSize(run).width, expected.width);
      expect(tester.getSize(run).height, greaterThanOrEqualTo(48));
      await tester.pumpWidget(
        app(
          Scaffold(
            body: ListView(
              padding: const EdgeInsets.all(20),
              children: [
                ResultCard(
                  scriptId: 'tools.encoder',
                  value: fixture.envelope({
                    'preview': {'kind': 'text', 'value': 'sample'},
                    'artifact': {
                      'token': 'original.output',
                      'name': 'output.txt',
                      'bytes': 6,
                    },
                  }),
                  api: const WorkbenchApi(),
                ),
              ],
            ),
          ),
        ),
      );
      final save = find.ancestor(
        of: find.text('保存输出文件'),
        matching: find.byWidgetPredicate((widget) => widget is OutlinedButton),
      );
      await fixture.revealWidget(tester, save);
      final previewCopy = find.ancestor(
        of: find.text('复制预览'),
        matching: find.byWidgetPredicate((widget) => widget is TextButton),
      );
      expect(tester.getSize(save).width, tester.getSize(previewCopy).width);
      expect(tester.getSize(save).width, config.size.width - 72);
      expect(tester.takeException(), isNull);
    });
  }
}

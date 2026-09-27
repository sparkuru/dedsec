import 'dart:async';
import 'dart:convert';
import 'package:ctos/workbench.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

const channel = MethodChannel('ctos/native');

Map<String, dynamic> catalog() => {
  'python': '3.13.9',
  'architecture': 'aarch64',
  'sdk': 1,
  'offline': true,
  'packages': [
    {'id': 'python', 'version': '3.13.9', 'abi': 'arm64-v8a', 'license': 'PSF'},
  ],
  'scripts': [
    {
      'id': 'python.selftest',
      'title': 'Python',
      'description': 'Check',
      'category': 'runtime',
      'parameters': [],
    },
    {
      'id': 'text.digest',
      'title': 'Digest',
      'description': 'Digest text',
      'category': 'text',
      'parameters': [
        {
          'name': 'text',
          'label': 'Text',
          'required': true,
          'max_length': 4,
          'multiline': true,
          'default': '',
        },
      ],
    },
  ],
};

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  testWidgets(
    'catalogue starts on entry, retries errors and opens environment detail',
    (tester) async {
      var calls = 0;
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method != 'pythonCatalog') return null;
        if (++calls == 1)
          throw PlatformException(code: 'PYTHON', message: 'Not ready');
        return jsonEncode(catalog());
      });
      await tester.pumpWidget(
        const MaterialApp(home: WorkbenchPage(active: false)),
      );
      expect(calls, 0);
      await tester.pumpWidget(
        const MaterialApp(home: WorkbenchPage(active: true)),
      );
      await tester.pumpAndSettle();
      expect(find.text('环境暂时不可用'), findsOneWidget);
      await tester.tap(find.text('重试'));
      await tester.pumpAndSettle();
      expect(calls, 2);
      await tester.tap(find.text('Python3 环境'));
      await tester.pumpAndSettle();
      expect(find.text('CPython 3.13.9'), findsOneWidget);
      expect(find.textContaining('python3 --version'), findsOneWidget);
      await tester.pageBack();
      await tester.pumpAndSettle();
      expect(find.text('文本摘要'), findsOneWidget);
      expect(calls, 2);
    },
  );

  testWidgets('parameter validation counts unicode and passes exact values', (
    tester,
  ) async {
    Map? submitted;
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'pythonRun') {
        submitted = call.arguments as Map;
        return jsonEncode({
          'state': 'completed',
          'durationMs': 3,
          'exitCode': 0,
          'data': {'sha256': 'digest'},
        });
      }
      return null;
    });
    final script = WorkbenchScript(
      (catalog()['scripts'] as List)[1] as Map<String, dynamic>,
    );
    await tester.pumpWidget(MaterialApp(home: ScriptPage(script: script)));
    await tester.tap(find.text('运行'));
    await tester.pumpAndSettle();
    expect(find.text('请填写文本'), findsOneWidget);
    expect(submitted, isNull);
    await tester.enterText(find.byType(TextFormField), '12345');
    await tester.tap(find.text('运行'));
    await tester.pumpAndSettle();
    expect(submitted, isNull);
    await tester.enterText(find.byType(TextFormField), '🙂中文');
    await tester.tap(find.text('运行'));
    await tester.pumpAndSettle();
    expect(submitted?['params'], {'text': '🙂中文'});
    expect(submitted?['script'], 'text.digest');
    expect(find.text('已完成'), findsOneWidget);
    expect(find.textContaining('"sha256": "digest"'), findsOneWidget);
  });

  testWidgets(
    'running task prevents duplicate runs, cancels and can rerun after timeout',
    (tester) async {
      var response = Completer<String>();
      var runs = 0;
      String? runningId;
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'pythonRun') {
          runs++;
          runningId = (call.arguments as Map)['taskId'] as String;
          return response.future;
        }
        if (call.method == 'pythonCancel') {
          expect((call.arguments as Map)['taskId'], runningId);
          response.complete(
            jsonEncode({
              'state': 'cancelled',
              'durationMs': 20,
              'exitCode': 130,
            }),
          );
        }
        return null;
      });
      final script = WorkbenchScript(
        (catalog()['scripts'] as List)[0] as Map<String, dynamic>,
      );
      await tester.pumpWidget(MaterialApp(home: ScriptPage(script: script)));
      await tester.tap(find.text('运行'));
      await tester.pump();
      expect(runs, 1);
      final runButton = tester.widget<FilledButton>(
        find.byWidgetPredicate((widget) => widget is FilledButton),
      );
      expect(runButton.onPressed, isNull);
      await tester.tap(find.text('取消'));
      await tester.pumpAndSettle();
      expect(find.text('已取消'), findsOneWidget);
      response = Completer<String>();
      await tester.tap(find.text('运行'));
      await tester.pump();
      response.complete(
        jsonEncode({
          'state': 'timed_out',
          'durationMs': 15000,
          'exitCode': 124,
        }),
      );
      await tester.pumpAndSettle();
      expect(runs, 2);
      expect(find.text('超时'), findsOneWidget);
      expect(find.textContaining('进程已回收'), findsOneWidget);
    },
  );

  for (final configuration in [
    (size: const Size(375, 812), scale: 1.0),
    (size: const Size(812, 375), scale: 1.0),
    (size: const Size(375, 812), scale: 2.0),
  ]) {
    testWidgets(
      'environment layout ${configuration.size} scale ${configuration.scale}',
      (tester) async {
        tester.view.physicalSize = configuration.size;
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        final script = WorkbenchScript(
          (catalog()['scripts'] as List)[0] as Map<String, dynamic>,
        );
        await tester.pumpWidget(
          MaterialApp(
            home: MediaQuery(
              data: MediaQueryData(
                size: configuration.size,
                textScaler: TextScaler.linear(configuration.scale),
                disableAnimations: true,
              ),
              child: ScriptPage(script: script, runtime: catalog()),
            ),
          ),
        );
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
        await tester.scrollUntilVisible(
          find.text('运行自检'),
          150,
          scrollable: find.byType(Scrollable).first,
        );
        expect(tester.takeException(), isNull);
        expect(
          tester
              .getSize(
                find.byWidgetPredicate((widget) => widget is FilledButton),
              )
              .height,
          greaterThanOrEqualTo(48),
        );
      },
    );
  }
}

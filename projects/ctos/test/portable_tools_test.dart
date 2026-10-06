import 'dart:convert';
import 'package:ctos/workbench.dart';
import 'package:ctos/workbench/hftp_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

const channel = MethodChannel('ctos/native');

WorkbenchScript tool() => WorkbenchScript({
  'id': 'tools.crypto',
  'title': 'Crypto',
  'description': 'Files',
  'category': 'tools',
  'parameters': [
    {
      'name': 'operation',
      'label': 'Operation',
      'required': true,
      'max_length': 8192,
      'default': 'encrypt',
      'kind': 'choice',
      'choices': ['encrypt', 'decrypt'],
    },
    {
      'name': 'file',
      'label': 'Input file',
      'required': true,
      'max_length': 8192,
      'default': '',
      'kind': 'file',
    },
    {
      'name': 'password',
      'label': 'Password',
      'required': true,
      'max_length': 8192,
      'default': '',
      'secret': true,
    },
  ],
});

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  testWidgets(
    'file selection uses token, password is masked and output exports separately',
    (tester) async {
      Map? submitted, exported;
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'toolFilePick')
          return jsonEncode({
            'token': '${'a' * 32}.input',
            'name': 'source.bin',
            'bytes': 9,
          });
        if (call.method == 'pythonRun') {
          submitted = call.arguments as Map;
          return jsonEncode({
            'state': 'completed',
            'durationMs': 2,
            'exitCode': 0,
            'data': {
              'artifact': {
                'token': '${'b' * 32}.output',
                'name': 'encrypted.ctos',
                'bytes': 10,
              },
            },
          });
        }
        if (call.method == 'toolFileExport') {
          exported = call.arguments as Map;
          return true;
        }
        return null;
      });
      await tester.pumpWidget(MaterialApp(home: ScriptPage(script: tool())));
      expect(
        tester.widget<EditableText>(find.byType(EditableText)).obscureText,
        isFalse,
      );
      await tester.enterText(find.byType(TextFormField), 'private-password');
      await tester.tap(find.text('选择文件'));
      await tester.pumpAndSettle();
      expect(find.textContaining('source.bin'), findsOneWidget);
      await tester.ensureVisible(find.text('运行'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('运行'));
      await tester.pumpAndSettle();
      expect(submitted?['params'], {
        'operation': 'encrypt',
        'file': '${'a' * 32}.input',
        'password': 'private-password',
      });
      await tester.scrollUntilVisible(
        find.text('保存输出文件'),
        200,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('保存输出文件'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('保存输出文件'));
      await tester.pumpAndSettle();
      expect(exported?['token'], '${'b' * 32}.output');
      expect(exported?['name'], 'encrypted.ctos');
      expect(exported?.containsKey('password'), isFalse);
    },
  );

  testWidgets('sensitive result stays hidden until explicitly revealed', (
    tester,
  ) async {
    messenger.setMockMethodCallHandler(
      channel,
      (call) async => call.method == 'pythonRun'
          ? jsonEncode({
              'state': 'completed',
              'durationMs': 1,
              'exitCode': 0,
              'data': {'sensitive': true, 'password': 'generated-secret'},
            })
          : null,
    );
    final script = WorkbenchScript({
      'id': 'tools.password',
      'title': 'Password',
      'description': 'Local',
      'category': 'tools',
      'parameters': [],
    });
    await tester.pumpWidget(MaterialApp(home: ScriptPage(script: script)));
    await tester.tap(find.text('运行'));
    await tester.pumpAndSettle();
    expect(find.textContaining('generated-secret'), findsNothing);
    await tester.ensureVisible(find.text('显示密码'));
    await tester.tap(find.text('显示密码'));
    await tester.pumpAndSettle();
    expect(find.textContaining('generated-secret'), findsOneWidget);
  });

  testWidgets('HFTP background service is stopped only by explicit action', (
    tester,
  ) async {
    var stops = 0;
    var running = true;
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'hftpConfig')
        return jsonEncode({
          'host': '0.0.0.0',
          'port': '7888',
          'maxUploadMiB': '32',
          'directoryName': '默认共享目录',
          'treeUri': '',
        });
      if (call.method == 'hftpStatus')
        return jsonEncode(
          running
              ? {
                  'state': 'running',
                  'host': '0.0.0.0',
                  'port': 7888,
                  'urls': ['http://127.0.0.1:7888/'],
                }
              : {'state': 'stopped'},
        );
      if (call.method == 'hftpStop') {
        stops++;
        running = false;
      }
      return null;
    });
    await tester.pumpWidget(
      const MaterialApp(home: HftpPage(api: WorkbenchApi())),
    );
    await tester.pumpAndSettle();
    expect(find.text('运行中 · 后台继续'), findsOneWidget);
    final start = tester.widget<FilledButton>(
      find.byWidgetPredicate((widget) => widget is FilledButton),
    );
    expect(start.onPressed, isNotNull);
    expect(find.text('启动服务'), findsNothing);
    await tester.pumpWidget(const SizedBox());
    await tester.pump();
    expect(stops, 0);
    await tester.pumpWidget(
      const MaterialApp(home: HftpPage(api: WorkbenchApi())),
    );
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('停止'));
    await tester.tap(find.text('停止'));
    await tester.pumpAndSettle();
    expect(stops, 1);
    await tester.scrollUntilVisible(
      find.text('已停止'),
      -150,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('已停止'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });
}

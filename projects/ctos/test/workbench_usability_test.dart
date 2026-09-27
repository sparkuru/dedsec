import 'dart:async';
import 'dart:convert';
import 'package:ctos/workbench.dart';
import 'package:ctos/workbench/hftp_page.dart';
import 'package:ctos/workbench/parameter_field.dart';
import 'package:ctos/workbench/result_card.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

const channel = MethodChannel('ctos/native');
const hftpConfig = {
  'host': '0.0.0.0',
  'port': '7888',
  'maxUploadMiB': '32',
  'directoryName': '默认共享目录',
  'treeUri': '',
};

Map<String, dynamic> field(
  String name, {
  String initial = '',
  bool required = false,
  String kind = 'text',
  List<String> choices = const [],
  bool secret = false,
  int max = 8192,
}) => {
  'name': name,
  'label': 'Original $name',
  'default': initial,
  'required': required,
  'kind': kind,
  'choices': choices,
  'secret': secret,
  'max_length': max,
  'multiline': name == 'text',
};

WorkbenchScript script(
  String id, {
  List<Map<String, dynamic>> fields = const [],
}) => WorkbenchScript({
  'id': id,
  'title': 'Unknown title',
  'description': 'Unknown description',
  'category': id.startsWith('tools.') ? 'tools' : 'system',
  'parameters': fields,
});
WorkbenchScript password() => script(
  'tools.password',
  fields: [
    field('seed', required: true, secret: true),
    field('length', initial: '16', max: 3),
    field('salt', secret: true),
    field('salt_file', kind: 'file'),
    field('charset', max: 256),
    field('must', max: 128),
  ],
);
WorkbenchScript encoder() => script(
  'tools.encoder',
  fields: [
    field(
      'operation',
      initial: 'base64',
      required: true,
      kind: 'choice',
      choices: ['base64', 'url', 'unicode', 'hash'],
    ),
    field(
      'direction',
      initial: 'encode',
      required: true,
      kind: 'choice',
      choices: ['encode', 'decode', 'auto'],
    ),
    field('text'),
    field('file', kind: 'file'),
  ],
);
WorkbenchScript crypto() => script(
  'tools.crypto',
  fields: [
    field(
      'operation',
      initial: 'encrypt',
      kind: 'choice',
      choices: ['encrypt', 'decrypt', 'legacy-decrypt'],
    ),
    field('file', required: true, kind: 'file'),
    field('password', required: true, secret: true),
  ],
);
Map<String, dynamic> envelope(Object data) => {
  'script': 'fixture',
  'taskId': 'test-task',
  'environment': 'App',
  'state': 'completed',
  'startedAt': 1000,
  'durationMs': 2,
  'exitCode': 0,
  'stdout': '',
  'stderr': '',
  'data': data,
};
Finder input(String name) => find.descendant(
  of: find.byKey(ValueKey(name)),
  matching: find.byType(TextFormField),
);
Finder choice(String name) => find.descendant(
  of: find.byKey(ValueKey(name)),
  matching: find.byType(DropdownButtonFormField<String>),
);

Future<void> revealWidget(WidgetTester tester, Finder finder) async {
  await tester.pump();
  if (finder.evaluate().isEmpty) {
    await tester.scrollUntilVisible(
      finder,
      150,
      scrollable: find.byType(Scrollable).first,
      maxScrolls: 50,
    );
  }
  await Scrollable.ensureVisible(tester.element(finder), alignment: 0.5);
  await tester.pumpAndSettle();
}

Future<void> tapVisible(WidgetTester tester, Finder finder) async {
  await revealWidget(tester, finder);
  ScaffoldMessenger.of(tester.element(finder)).hideCurrentSnackBar();
  await tester.pumpAndSettle();
  await tester.tap(finder);
  await tester.pumpAndSettle();
}

Future<void> select(WidgetTester tester, Finder dropdown, String title) async {
  await tapVisible(tester, dropdown);
  await tester.tap(find.text(title).last);
  await tester.pumpAndSettle();
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  WidgetController.hitTestWarningShouldBeFatal = true;
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  testWidgets(
    'tools lead an unordered catalogue and all nine entries remain reachable',
    (tester) async {
      final ids = [
        'device.info',
        'tools.crypto',
        'memory.snapshot',
        'tools.ip',
        'python.selftest',
        'tools.encoder',
        'text.digest',
        'tools.hftp',
        'tools.password',
      ];
      final scripts = [
        for (final id in ids)
          {
            'id': id,
            'title': id,
            'description': 'description',
            'category': id.startsWith('tools.') ? 'tools' : 'system',
            'parameters': <Object>[],
          },
      ];
      messenger.setMockMethodCallHandler(
        channel,
        (call) async => call.method == 'pythonCatalog'
            ? jsonEncode({'scripts': scripts})
            : null,
      );
      await tester.pumpWidget(
        const MaterialApp(home: WorkbenchPage(active: true)),
      );
      await tester.pumpAndSettle();
      expect(find.text('文件加解密'), findsOneWidget);
      expect(find.text('设备摘要'), findsNothing);
      final ordered = [
        ...ids.where((id) => id.startsWith('tools.')),
        ...ids.where((id) => !id.startsWith('tools.')),
      ];
      for (final id in ordered) {
        final title = script(id).title;
        await revealWidget(tester, find.text(title));
        expect(find.text(title), findsOneWidget);
      }
      await tapVisible(tester, find.text('Python3 环境'));
      expect(find.text('运行'), findsOneWidget);
      await tester.pageBack();
      await tester.pumpAndSettle();
      await revealWidget(tester, find.text('工具临时文件'));
      expect(find.text('工具临时文件'), findsOneWidget);
    },
  );

  testWidgets(
    'password advanced fields retain exact payload and numeric length validation',
    (tester) async {
      final calls = <Map>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'pythonRun') {
          calls.add(call.arguments as Map);
          return jsonEncode(
            envelope({'sensitive': true, 'password': 'secret', 'length': 16}),
          );
        }
        if (call.method == 'toolFilePick')
          return jsonEncode({
            'token': 'salt.input',
            'name': 'salt.txt',
            'bytes': 2048,
          });
        return null;
      });
      await tester.pumpWidget(
        MaterialApp(home: ScriptPage(script: password())),
      );
      expect(find.byType(TextFormField), findsNWidgets(2));
      expect(find.text('盐值（可选）'), findsNothing);
      expect(find.text('密码长度'), findsOneWidget);
      expect(
        tester
            .widget<EditableText>(
              find.descendant(
                of: input('length'),
                matching: find.byType(EditableText),
              ),
            )
            .keyboardType,
        TextInputType.number,
      );
      await tester.enterText(input('seed'), '  Seed🙂  ');
      for (final value in ['0', '129', 'abc']) {
        await tester.enterText(input('length'), value);
        await tapVisible(tester, find.text('运行'));
        expect(find.text('请输入 1–128 之间的整数'), findsOneWidget);
        expect(calls, isEmpty);
        expect(find.text('盐值（可选）'), findsNothing);
      }
      await tester.enterText(input('length'), '128');
      // Basic validation errors keep advanced fields collapsed.
      if (find.text('高级选项').evaluate().isNotEmpty)
        await tapVisible(tester, find.text('高级选项'));
      await tester.enterText(input('salt'), '  salt  ');
      await tester.enterText(input('charset'), 'abc123!');
      await tester.enterText(input('must'), '!');
      await tapVisible(tester, find.text('选择文件'));
      expect(find.text('salt.txt · 2.0 KiB'), findsOneWidget);
      await tapVisible(tester, find.text('收起高级选项'));
      await tapVisible(tester, find.text('运行'));
      expect(calls.single['params'], {
        'seed': '  Seed🙂  ',
        'length': '128',
        'salt': '  salt  ',
        'salt_file': 'salt.input',
        'charset': 'abc123!',
        'must': '!',
      });
      await tapVisible(tester, find.text('高级选项'));
      expect(
        tester.widget<TextFormField>(input('salt')).controller!.text,
        '  salt  ',
      );
      expect(find.text('salt.txt · 2.0 KiB'), findsOneWidget);
      await tester.enterText(input('must'), 'x' * 129);
      await tapVisible(tester, find.text('收起高级选项'));
      await tapVisible(tester, find.text('运行'));
      expect(calls.length, 1);
      expect(find.text('最多 128 个字符'), findsOneWidget);
      expect(find.text('收起高级选项'), findsOneWidget);
    },
  );

  testWidgets(
    'encoder switches sources without losing drafts, hides hash direction and sends wire values',
    (tester) async {
      final calls = <Map>[];
      var cancelled = false;
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'toolFilePick')
          return cancelled
              ? null
              : jsonEncode({
                  'token': 'source.input',
                  'name': 'source.bin',
                  'bytes': 9,
                });
        if (call.method == 'pythonRun') {
          calls.add(call.arguments as Map);
          return jsonEncode(
            envelope({
              'bytes': 9,
              'hashes': {'sha256': 'digest-value'},
            }),
          );
        }
        return null;
      });
      await tester.pumpWidget(MaterialApp(home: ScriptPage(script: encoder())));
      expect(find.text('选择文件'), findsNothing);
      await tester.enterText(input('text'), '  draft🙂  ');
      final source = find.byType(DropdownButtonFormField<String>).first;
      await select(tester, source, '文件');
      await tapVisible(tester, find.text('选择文件'));
      expect(find.textContaining('source.bin'), findsOneWidget);
      final file = find.byKey(const ValueKey('file'));
      expect(tester.getTopLeft(file).dx, tester.getTopLeft(source).dx);
      expect(tester.getSize(file).width, tester.getSize(source).width);
      await select(tester, choice('operation'), '哈希摘要');
      expect(find.text('转换方向'), findsNothing);
      await tapVisible(tester, find.text('运行'));
      expect(calls.last['params'], {
        'operation': 'hash',
        'direction': 'encode',
        'text': '',
        'file': 'source.input',
      });
      await select(tester, source, '文本');
      expect(
        tester.widget<TextFormField>(input('text')).controller!.text,
        '  draft🙂  ',
      );
      await tapVisible(tester, find.text('运行'));
      expect(calls.last['params'], {
        'operation': 'hash',
        'direction': 'encode',
        'text': '  draft🙂  ',
        'file': '',
      });
      await select(tester, source, '文件');
      expect(find.textContaining('source.bin'), findsOneWidget);
      cancelled = true;
      await tapVisible(tester, find.text('选择文件'));
      expect(find.textContaining('source.bin'), findsOneWidget);
      await tapVisible(tester, find.text('取消选择'));
      expect(find.textContaining('source.bin'), findsNothing);
      final runs = calls.length;
      await tapVisible(tester, find.text('运行'));
      expect(calls.length, runs);
      expect(find.text('请选择输入文件'), findsOneWidget);
      await tapVisible(tester, find.text('选择文件'));
      await tapVisible(tester, find.text('运行'));
      expect(calls.length, runs);
      cancelled = false;
      await tapVisible(tester, find.text('选择文件'));
      expect(find.text('请选择输入文件'), findsNothing);
      await select(tester, choice('operation'), 'URL 编码');
      expect(find.text('转换方向'), findsOneWidget);
      await select(tester, choice('direction'), '自动识别');
      await tapVisible(tester, find.text('运行'));
      expect(calls.last['params'], {
        'operation': 'url',
        'direction': 'auto',
        'text': '',
        'file': 'source.input',
      });
    },
  );

  testWidgets(
    'password raw details stay masked while explicit copy and export preserve full value',
    (tester) async {
      String? clipboard, exported;
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'export') {
          exported = (call.arguments as Map)['text'] as String;
          return true;
        }
        return null;
      });
      messenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
        if (call.method == 'Clipboard.setData')
          clipboard = (call.arguments as Map)['text'] as String;
        return null;
      });
      addTearDown(
        () => messenger.setMockMethodCallHandler(SystemChannels.platform, null),
      );
      final value = envelope({
        'sensitive': true,
        'password': 'generated-secret',
        'length': 16,
      });
      value['stderr'] = 'generated-secret';
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: ListView(
              children: [
                ResultCard(
                  scriptId: 'tools.password',
                  value: value,
                  api: const WorkbenchApi(),
                ),
              ],
            ),
          ),
        ),
      );
      await tapVisible(tester, find.text('原始 JSON 与日志'));
      expect(find.textContaining('generated-secret'), findsNothing);
      await tapVisible(tester, find.text('复制密码'));
      expect(clipboard, 'generated-secret');
      expect(find.textContaining('generated-secret'), findsNothing);
      await tapVisible(tester, find.text('保存 JSON'));
      expect(jsonDecode(exported!), value);
      expect(value['data']['password'], 'generated-secret');
      await tapVisible(tester, find.text('显示密码'));
      expect(find.textContaining('generated-secret'), findsNWidgets(2));
      await tapVisible(tester, find.text('隐藏密码'));
      expect(find.textContaining('generated-secret'), findsNothing);
    },
  );

  testWidgets(
    'raw log masking covers escaped password characters without changing envelope',
    (tester) async {
      const secret = 'special"password\\with\nnewline';
      final value = envelope({'sensitive': true, 'password': secret});
      value['stdout'] = 'Output: $secret';
      value['stderr'] = 'Error: $secret';
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: ListView(
              children: [
                ResultCard(
                  scriptId: 'tools.password',
                  value: value,
                  api: const WorkbenchApi(),
                ),
              ],
            ),
          ),
        ),
      );
      await tapVisible(tester, find.text('原始 JSON 与日志'));
      expect(find.textContaining('special'), findsNothing);
      expect(value['data']['password'], secret);
      expect(value['stdout'], 'Output: $secret');
      await tapVisible(tester, find.text('显示密码'));
      expect(find.text(secret), findsOneWidget);
    },
  );

  testWidgets(
    'hash copying targets each digest and conversion preview uses actual value',
    (tester) async {
      String? clipboard;
      Map? exported;
      messenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
        if (call.method == 'Clipboard.setData')
          clipboard = (call.arguments as Map)['text'] as String;
        return null;
      });
      addTearDown(
        () => messenger.setMockMethodCallHandler(SystemChannels.platform, null),
      );
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'toolFileExport') {
          exported = call.arguments as Map;
          return true;
        }
        return null;
      });
      Widget result(Object data) => MaterialApp(
        home: Scaffold(
          body: ListView(
            children: [
              ResultCard(
                key: ValueKey(data),
                scriptId: 'tools.encoder',
                value: envelope(data),
                api: const WorkbenchApi(),
              ),
            ],
          ),
        ),
      );
      await tester.pumpWidget(
        result({
          'bytes': 2048,
          'hashes': {'md5': 'md5-value', 'sha256': 'sha256-value'},
        }),
      );
      await tapVisible(tester, find.text('复制 MD5'));
      expect(clipboard, 'md5-value');
      await tapVisible(tester, find.text('复制 SHA256'));
      expect(clipboard, 'sha256-value');
      final artifact = {
        'token': 'exact.output',
        'name': 'converted.bin',
        'bytes': 2048,
      };
      await tester.pumpWidget(
        result({
          'preview': {'kind': 'binary', 'encoding': 'base64', 'value': 'YWJj'},
          'previewTruncated': true,
          'artifact': artifact,
        }),
      );
      expect(find.text('二进制预览（Base64）'), findsOneWidget);
      expect(find.text('YWJj'), findsOneWidget);
      expect(find.text('2.0 KiB'), findsOneWidget);
      await tapVisible(tester, find.text('复制预览'));
      expect(clipboard, 'YWJj');
      await tapVisible(tester, find.text('保存输出文件'));
      expect(exported, artifact);
    },
  );

  testWidgets(
    'IP summary uses nested provider values and unknown fields remain in details',
    (tester) async {
      final data = {
        'target': 'example.test',
        'resolved': '192.0.2.1',
        'source': 'fixture-only',
        'data': {
          'ipAddress': '192.0.2.1',
          'countryName': 'Example',
          'timeZones': ['UTC'],
          'isProxy': false,
          'futureField': 'retained',
        },
      };
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: ListView(
              children: [
                ResultCard(
                  scriptId: 'tools.ip',
                  value: envelope(data),
                  api: const WorkbenchApi(),
                ),
              ],
            ),
          ),
        ),
      );
      expect(find.text('IP 地址：192.0.2.1'), findsOneWidget);
      expect(find.text('时区：UTC'), findsOneWidget);
      expect(find.text('代理标记：否'), findsOneWidget);
      expect(find.textContaining('retained'), findsNothing);
      await tapVisible(tester, find.text('原始 JSON 与日志'));
      expect(find.textContaining('"futureField": "retained"'), findsOneWidget);
    },
  );

  testWidgets('unknown parameters and results retain metadata fallback', (
    tester,
  ) async {
    final p = ScriptParameter(
      field(
        'future',
        initial: 'literal',
        kind: 'choice',
        choices: ['literal', 'other'],
      ),
    );
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ParameterField(
            parameter: p,
            controller: TextEditingController(text: 'literal'),
            api: const WorkbenchApi(),
            enabled: true,
            scriptId: 'tools.future',
          ),
        ),
      ),
    );
    expect(find.text('Original future'), findsOneWidget);
    expect(find.text('literal'), findsOneWidget);
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ListView(
            children: [
              ResultCard(
                scriptId: 'tools.future',
                value: envelope({'unknown': 'retained'}),
                api: const WorkbenchApi(),
              ),
            ],
          ),
        ),
      ),
    );
    expect(find.textContaining('"unknown": "retained"'), findsOneWidget);
  });

  testWidgets(
    'HFTP starts only explicitly, supports stopping starting state and confirms destructive clear',
    (tester) async {
      var state = 'stopped', starts = 0, stops = 0, clears = 0;
      final response = Completer<String>();
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'hftpConfig') return jsonEncode(hftpConfig);
        if (call.method == 'hftpStatus') return jsonEncode({'state': state});
        if (call.method == 'hftpStart') {
          starts++;
          expect(call.arguments, {
            'host': '0.0.0.0',
            'port': '7888',
            'maxUploadMiB': '32',
            'treeUri': '',
            'rootRelay': false,
          });
          return response.future;
        }
        if (call.method == 'hftpStop') {
          stops++;
          state = 'stopped';
        }
        if (call.method == 'hftpClearShare') clears++;
        return null;
      });
      await tester.pumpWidget(
        const MaterialApp(home: HftpPage(api: WorkbenchApi())),
      );
      await tester.pumpAndSettle();
      expect(starts, 0);
      expect(
        tester.getTopLeft(find.text('已停止')).dy,
        lessThan(tester.getTopLeft(find.text('服务配置')).dy),
      );
      await tester.tap(find.text('启动服务'));
      await tester.pump();
      expect(starts, 1);
      expect(
        tester
            .widget<FilledButton>(
              find.byWidgetPredicate((widget) => widget is FilledButton),
            )
            .onPressed,
        isNull,
      );
      state = 'starting';
      response.complete(jsonEncode({'state': 'starting'}));
      await tester.pump();
      await tester.pump();
      expect(find.text('停止'), findsOneWidget);
      await tester.tap(find.text('停止'));
      await tester.pumpAndSettle();
      expect(stops, 1);
      await tapVisible(tester, find.text('清空共享库'));
      expect(clears, 0);
      await tester.tap(find.text('返回'));
      await tester.pumpAndSettle();
      expect(clears, 0);
      await tapVisible(tester, find.text('清空共享库'));
      await tester.tap(find.text('清空'));
      await tester.pumpAndSettle();
      expect(clears, 1);
      await tester.pumpWidget(const SizedBox());
      expect(stops, 1);
    },
  );

  for (final config in [
    (size: const Size(375, 812), scale: 1.0),
    (size: const Size(812, 375), scale: 1.0),
    (size: const Size(375, 812), scale: 2.0),
  ]) {
    testWidgets(
      'tool forms/results/HFTP layout ${config.size} scale ${config.scale}',
      (tester) async {
        tester.view.physicalSize = config.size;
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        messenger.setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'hftpConfig') return jsonEncode(hftpConfig);
          if (call.method == 'pythonCatalog')
            return jsonEncode({
              'scripts': [
                for (final id in [
                  'python.selftest',
                  'device.info',
                  'memory.snapshot',
                  'text.digest',
                  'tools.password',
                  'tools.encoder',
                  'tools.ip',
                  'tools.crypto',
                  'tools.hftp',
                ])
                  {
                    'id': id,
                    'title': id,
                    'description': 'description',
                    'category': id.startsWith('tools.') ? 'tools' : 'system',
                    'parameters': <Object>[],
                  },
              ],
            });
          if (call.method == 'hftpStatus')
            return jsonEncode({
              'state': 'running',
              'urls': ['http://127.0.0.1:7888/long/path/'],
            });
          return null;
        });
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
        await tester.pumpWidget(app(ScriptPage(script: password())));
        await tapVisible(tester, find.text('高级选项'));
        await revealWidget(tester, find.text('运行'));
        expect(tester.takeException(), isNull);
        expect(
          tester
              .getSize(
                find.byWidgetPredicate((widget) => widget is FilledButton),
              )
              .height,
          greaterThanOrEqualTo(48),
        );
        await tester.pumpWidget(
          app(ScriptPage(key: const ValueKey('encoder'), script: encoder())),
        );
        await select(
          tester,
          find.byType(DropdownButtonFormField<String>).first,
          '文件',
        );
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(
          app(ScriptPage(key: const ValueKey('crypto'), script: crypto())),
        );
        await select(tester, choice('operation'), '旧格式解密（未认证）');
        await revealWidget(tester, find.text('运行'));
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(
          app(
            Scaffold(
              body: ListView(
                children: [
                  ResultCard(
                    scriptId: 'tools.encoder',
                    value: envelope({
                      'hashes': {'sha512': 'f' * 128},
                      'bytes': 2048,
                    }),
                    api: const WorkbenchApi(),
                  ),
                ],
              ),
            ),
          ),
        );
        await tapVisible(tester, find.text('原始 JSON 与日志'));
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(app(const HftpPage(api: WorkbenchApi())));
        await tester.pumpAndSettle();
        await revealWidget(tester, find.text('共享目录'));
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(app(const WorkbenchPage(active: true)));
        await tester.pumpAndSettle();
        await revealWidget(tester, find.text('工具临时文件'));
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox());
      },
    );
  }
}

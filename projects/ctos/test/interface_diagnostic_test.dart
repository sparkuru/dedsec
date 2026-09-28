import 'dart:convert';
import 'package:ctos/export_preview.dart';
import 'package:ctos/workbench.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

const channel = MethodChannel('ctos/native');

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test('interface diagnosis validates a Linux interface name', () {
    final script = WorkbenchScript.interfaceDiagnostic();
    final parameter = script.parameters.single;
    final presentation = ParameterPresentation(script.id, parameter);

    expect(script.title, '接口诊断');
    expect(presentation.validate('wlan0'), isNull);
    expect(presentation.validate(''), '请填写网络接口');
    expect(presentation.validate('wlan/0'), '接口名称格式无效');
  });

  testWidgets('context interface stays fixed and result is shown', (
    tester,
  ) async {
    Map? submission;
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'pythonRun') {
        submission = call.arguments as Map;
        return jsonEncode({
          'script': 'network.interface_diagnose',
          'taskId': 'task-1',
          'environment': 'App',
          'state': 'completed',
          'startedAt': 1000,
          'durationMs': 4,
          'exitCode': 0,
          'data': {
            'source': 'Android App API / NetworkInterface',
            'capturedAt': 900,
            'interfaceName': 'wlan0',
            'summary': '接口 wlan0：UP · 1 个地址',
            'interface': {
              'name': 'wlan0',
              'state': 'UP',
              'addresses': ['192.0.2.4/24'],
              'counters': {'rxBytes': 12},
              'unavailableCounters': [],
            },
            'networks': [],
            'findings': [],
          },
        });
      }
      return null;
    });

    await tester.pumpWidget(
      MaterialApp(
        home: ScriptPage(
          script: WorkbenchScript.interfaceDiagnostic(),
          initialParameters: const {'interface_name': 'wlan0'},
          lockedParameters: const {'interface_name'},
        ),
      ),
    );
    expect(
      tester.widget<TextFormField>(find.byType(TextFormField)).enabled,
      isFalse,
    );
    await tester.tap(find.text('运行'));
    await tester.pumpAndSettle();

    expect(submission?['script'], 'network.interface_diagnose');
    expect(submission?['params'], {'interface_name': 'wlan0'});
    expect(find.text('接口 wlan0：UP · 1 个地址'), findsOneWidget);
    expect(find.textContaining('192.0.2.4/24'), findsOneWidget);
  });

  test('export document contains selected snapshots and history only', () {
    final items = {
      'network': ExportDataItem(
        key: 'network',
        title: 'Network',
        source: 'App API',
        capturedAt: 'time-n',
        scope: 'interfaces',
        data: {'address': '192.0.2.1'},
      ),
      'device': ExportDataItem(
        key: 'device',
        title: 'Device',
        source: 'Android API',
        capturedAt: 'time-d',
        scope: 'system',
        data: {'model': 'test'},
      ),
    };
    final history = [
      {'taskId': 'h-1', 'script': 'device.info'},
      {'taskId': 'h-2', 'script': 'network.interface_diagnose'},
    ];

    final selected = buildExportDocument(
      items: items,
      selectedItemKeys: {'network'},
      historyCandidates: history,
      selectedHistoryIds: {'h-2'},
      exportedAt: DateTime.utc(2026, 1, 2),
    );

    expect(selected.keys, {'network', 'taskHistory', 'exportedAt'});
    expect(selected['network']['data'], {'address': '192.0.2.1'});
    expect(selected['taskHistory'], [history.last]);
    expect(selected.containsKey('device'), isFalse);
  });
}

import 'dart:convert';
import 'package:ctos/main.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  testWidgets('phone layout displays Root collection and filters interfaces', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(412, 915);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    var autoRootCalls = 0;
    messenger.setMockMethodCallHandler(
      const MethodChannel('ctos/terminal'),
      (_) async => null,
    );
    messenger.setMockMethodCallHandler(native, (call) async {
      if (call.method == 'rootAuto') {
        autoRootCalls++;
        return jsonEncode({'root': true, 'attempted': true});
      }
      if (call.method == 'deviceSnapshot')
        return jsonEncode({
          'system': {
            'state': 'available',
            'source': 'Android API',
            'capturedAt': 1000,
            'data': {
              'manufacturer': 'Test',
              'model': 'device',
              'android': '16',
              'sdk': 36,
              'kernel': 'test',
              'architectures': ['arm64-v8a'],
              'uptimeMs': 60000,
            },
          },
          'memory': {
            'state': 'available',
            'source': 'ActivityManager.MemoryInfo',
            'capturedAt': 1000,
            'data': {
              'totalBytes': 4294967296,
              'availableBytes': 2147483648,
              'low': false,
            },
          },
        });
      if (call.method == 'pythonCatalog')
        return jsonEncode({
          'python': '3.13.9',
          'architecture': 'aarch64',
          'sdk': 1,
          'scripts': [
            {
              'id': 'device.info',
              'title': 'Device summary',
              'description': 'Device',
              'category': 'system',
              'parameters': [],
            },
          ],
        });
      if (call.method == 'pythonRun' &&
          (call.arguments as Map)['script'] == 'network.interface_diagnose')
        return jsonEncode({
          'script': 'network.interface_diagnose',
          'taskId': 'task-interface',
          'state': 'completed',
          'environment': 'App',
          'startedAt': 1000,
          'durationMs': 10,
          'exitCode': 0,
          'data': {
            'source': 'Android App API / test',
            'capturedAt': 1000,
            'interfaceName': 'wlan0',
            'summary': '接口 wlan0：UP · 1 个地址',
            'interface': {
              'name': 'wlan0',
              'state': 'UP',
              'addresses': ['192.0.2.10/24'],
              'counters': {'rxBytes': 100},
              'unavailableCounters': [],
            },
            'networks': [],
            'findings': [],
            'warnings': [],
          },
        });
      if (call.method == 'pythonRun')
        return jsonEncode({
          'script': 'device.info',
          'state': 'completed',
          'environment': 'App',
          'durationMs': 10,
          'exitCode': 0,
          'data': {'model': 'device'},
          'stdout': '',
          'stderr': '',
        });
      if (call.method != 'snapshot') return null;
      return jsonEncode({
        'device': 'Test device',
        'android': '16',
        'root': true,
        'networks': [
          {
            'transport': 'Wi-Fi',
            'interface': 'wlan0',
            'default': true,
            'addresses': ['192.0.2.10/24'],
            'dns': ['192.0.2.1'],
            'routes': ['default via 192.0.2.1 dev wlan0'],
            'validated': true,
            'metered': false,
            'privateDns': false,
          },
        ],
        'kernel': {
          'elapsed': 1000,
          'source': 'test',
          'routes': '',
          'interfaces': [
            for (final name in ['wlan0', 'tun0'])
              {
                'name': name,
                'rx': 100,
                'tx': 20,
                'addresses': [],
                'state': 'UP',
                'rxPackets': 1,
                'txPackets': 1,
                'rxErrors': 0,
                'txErrors': 0,
                'rxDrops': 0,
                'txDrops': 0,
              },
          ],
        },
      });
    });
    await tester.pumpWidget(const CtosApp());
    await tester.pump(const Duration(milliseconds: 100));
    expect(autoRootCalls, 1);
    expect(find.text('● ROOT 在线'), findsOneWidget);
    expect(find.textContaining('VECTOR'), findsNothing);
    expect(find.textContaining('Vector'), findsNothing);
    expect(tester.takeException(), isNull);
    await tester.tap(find.text('信息').last);
    await tester.pumpAndSettle();
    expect(find.text('设备信息'), findsOneWidget);
    await tester.tap(find.text('网络').last);
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(
      find.text('Wi-Fi / wlan0'),
      200,
      scrollable: find.byType(Scrollable).last,
    );
    expect(find.text('192.0.2.10/24'), findsOneWidget);
    expect(find.textContaining('Vector'), findsNothing);
    await tester.tap(find.text('接口').last);
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField).first, 'tun0');
    await tester.pump();
    expect(find.text('tun0'), findsWidgets);
    expect(find.text('wlan0'), findsNothing);
    await tester.enterText(find.byType(TextField).first, '');
    await tester.pump();
    await tester.tap(find.text('wlan0').first);
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(
      find.text('诊断此接口'),
      180,
      scrollable: find.byType(Scrollable).last,
    );
    await tester.tap(find.text('诊断此接口'));
    await tester.pumpAndSettle();
    expect(find.text('接口诊断'), findsOneWidget);
    expect(
      tester.widget<TextFormField>(find.byType(TextFormField)).enabled,
      isFalse,
    );
    await tester.tap(find.text('运行'));
    await tester.pumpAndSettle();
    expect(find.text('接口 wlan0：UP · 1 个地址'), findsOneWidget);
    await tester.pageBack();
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    await tester.tap(find.text('工作台').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('设备摘要'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('运行'));
    await tester.pumpAndSettle();
    expect(find.textContaining('"model": "device"'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
    messenger.setMockMethodCallHandler(native, null);
    messenger.setMockMethodCallHandler(
      const MethodChannel('ctos/terminal'),
      null,
    );
  });
}

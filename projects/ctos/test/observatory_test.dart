import 'dart:convert';
import 'package:ctos/main.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  testWidgets(
    'phone layout displays authenticated bridge and filters interfaces',
    (tester) async {
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
        if (call.method != 'snapshot') return null;
        return jsonEncode({
          'device': 'Test device',
          'android': '16',
          'root': true,
          'moduleActive': true,
          'moduleAgeMs': 500,
          'module': {'uid': 1000, 'pid': 123, 'networks': []},
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
      expect(find.text('● VECTOR 在线'), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.tap(find.text('信息').last);
      await tester.pumpAndSettle();
      expect(find.text('设备信息'), findsOneWidget);
      await tester.tap(find.text('接口').last);
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).first, 'tun0');
      await tester.pump();
      expect(find.text('tun0'), findsWidgets);
      expect(find.text('wlan0'), findsNothing);
      expect(tester.takeException(), isNull);
      await tester.tap(find.text('命令').last);
      await tester.pumpAndSettle();
      await tester.tap(find.text('执行').first);
      await tester.pumpAndSettle();
      expect(find.textContaining('"model": "device"'), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
      messenger.setMockMethodCallHandler(native, null);
      messenger.setMockMethodCallHandler(
        const MethodChannel('ctos/terminal'),
        null,
      );
    },
  );
}

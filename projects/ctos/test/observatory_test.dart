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
      messenger.setMockMethodCallHandler(
        const MethodChannel('ctos/terminal'),
        (_) async => null,
      );
      messenger.setMockMethodCallHandler(native, (call) async {
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
      expect(find.text('● VECTOR 在线'), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.tap(find.text('接口').last);
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).first, 'tun0');
      await tester.pump();
      expect(find.text('tun0'), findsWidgets);
      expect(find.text('wlan0'), findsNothing);
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

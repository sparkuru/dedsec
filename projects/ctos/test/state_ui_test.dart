import 'dart:convert';

import 'package:ctos/connection_state.dart';
import 'package:ctos/main.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('readable pages cap content width on wide screens', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(1200, 900);
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
      switch (call.method) {
        case 'rootAuto':
          return jsonEncode({'root': false, 'attempted': false});
        case 'snapshot':
          return jsonEncode({
            'root': false,
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

    await tester.pumpWidget(const CtosApp());
    await tester.pump(const Duration(milliseconds: 100));
    final appTheme = tester
        .widget<MaterialApp>(find.byType(MaterialApp))
        .theme!;
    expect(appTheme.cardTheme.color, panel);
    expect(
      tester.getSize(find.byKey(const Key('overview-reading-column'))).width,
      840,
    );
    await tester.tap(find.text('信息').last);
    await tester.pumpAndSettle();
    expect(
      tester.getSize(find.byKey(const Key('information-reading-column'))).width,
      840,
    );
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('connection refresh failure keeps the dated partial result', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.textScaleFactorTestValue = 1.5;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    var connectionCalls = 0;
    var autoRootCalls = 0;
    Map<String, dynamic>? exported;
    messenger.setMockMethodCallHandler(
      const MethodChannel('ctos/terminal'),
      (_) async => null,
    );
    messenger.setMockMethodCallHandler(native, (call) async {
      switch (call.method) {
        case 'rootAuto':
          autoRootCalls++;
          return jsonEncode({'root': false, 'attempted': false});
        case 'snapshot':
          return jsonEncode({
            'root': false,
            'networks': [],
            'kernel': {
              'source': 'app / TrafficStats',
              'elapsed': 1000,
              'interfaces': [],
              'routes': '',
            },
          });
        case 'deviceSnapshot':
          return jsonEncode({
            'system': {
              'state': 'available',
              'source': 'Android API',
              'capturedAt': 1000,
              'data': {
                'manufacturer': 'Test',
                'model': 'Phone',
                'android': '16',
              },
            },
            'cpu': {
              'state': 'permission_denied',
              'source': '/proc/loadavg',
              'capturedAt': 1000,
              'reason': 'EACCES from procfs',
            },
          });
        case 'connections':
          connectionCalls++;
          if (connectionCalls == 1) {
            return jsonEncode({
              'output':
                  'Netid State Recv-Q Send-Q Local Peer\n'
                  'tcp ESTAB 0 0 127.0.0.1:443 192.0.2.1:5555 uid:2000\n'
                  '  ↳ Shell (com.android.shell)\n',
              'apps': {
                '2000': [
                  {
                    'label': 'Shell',
                    'packageName': 'com.android.shell',
                    'applicationName': 'ShellApplication',
                    'processName': 'com.android.shell',
                  },
                ],
              },
              'partial': true,
            });
          }
          if (connectionCalls == 2) {
            return jsonEncode({
              'output':
                  'Netid State Recv-Q Send-Q Local Peer\n'
                  'tcp ESTAB 0 0 127.0.0.1:1888 192.0.2.2:443 uid:10377\n'
                  'udp UNCONN 0 0 *:1888 *:* uid:99910377\n',
              'apps': {
                '10377': [
                  {
                    'label': 'QQ',
                    'packageName': 'com.tencent.mobileqq',
                    'applicationName': '',
                    'processName': 'com.tencent.mobileqq',
                  },
                ],
                '99910377': [
                  {
                    'label': 'QQ',
                    'alias': 'tim',
                    'userId': 999,
                    'packageName': 'com.tencent.mobileqq',
                  },
                ],
              },
              'partial': false,
            });
          }
          throw PlatformException(code: 'CTOS', message: 'timed out');
        case 'export':
          exported = Map<String, dynamic>.from(
            jsonDecode((call.arguments as Map)['text'] as String) as Map,
          );
          return false;
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

    await tester.pumpWidget(const CtosApp());
    await tester.pump(const Duration(milliseconds: 100));
    expect(autoRootCalls, 1);
    expect(find.text('● APP 可用'), findsOneWidget);
    expect(find.text('● ROOT 未连接'), findsOneWidget);
    expect(find.textContaining('VECTOR'), findsNothing);
    expect(tester.takeException(), isNull);

    await tester.tap(find.text('信息').last);
    await tester.pumpAndSettle();
    await tester.drag(find.byType(ListView).first, const Offset(0, -350));
    await tester.pumpAndSettle();
    expect(find.text('系统负载'), findsNothing);
    expect(find.text('EACCES from procfs'), findsNothing);
    await tester.tap(find.text('连接'));
    await tester.pumpAndSettle();
    expect(
      tester.getTopLeft(find.text('连接检索')).dx,
      tester.getTopLeft(find.byType(TextField).last).dx,
    );
    expect(find.textContaining('部分结果'), findsOneWidget);
    expect(find.textContaining('采集于'), findsOneWidget);
    expect(find.text('TCP'), findsOneWidget);
    expect(find.textContaining('本地  127.0.0.1:443'), findsOneWidget);
    expect(find.text('Shell'), findsOneWidget);
    expect(find.text('com.android.shell'), findsOneWidget);
    await tester.enterText(find.byType(TextField).last, 'shellapplication');
    await tester.pump();
    expect(find.textContaining('1 条匹配'), findsOneWidget);
    expect(find.text('Shell'), findsOneWidget);
    await tester.enterText(find.byType(TextField).last, 'no-such-connection');
    await tester.pump();
    expect(find.text('此快照没有匹配的连接；刷新可检查新连接'), findsOneWidget);
    expect(find.textContaining('0 条匹配'), findsOneWidget);
    await tester.enterText(find.byType(TextField).last, '');
    await tester.pump();
    await tester.tap(find.byTooltip('选择导出内容'));
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(
      find.text('连接快照'),
      300,
      scrollable: find.byType(Scrollable).last,
    );
    await tester.tap(find.text('连接快照'));
    await tester.pump();
    await tester.tap(find.text('预览并保存所选内容'));
    await tester.pumpAndSettle();
    final exportedConnections =
        exported?['connections'] as Map<String, dynamic>?;
    expect(exportedConnections?['data']['output'], contains('tcp ESTAB'));
    expect(exportedConnections?['data'].containsKey('apps'), isFalse);
    expect(exported?.containsKey('network'), isFalse);
    await tester.pageBack();
    await tester.pumpAndSettle();
    await tester.pump(connectionFreshness);
    await tester.pump();
    expect(find.textContaining('旧快照'), findsOneWidget);
    for (final state in [
      AppLifecycleState.inactive,
      AppLifecycleState.hidden,
      AppLifecycleState.paused,
      AppLifecycleState.hidden,
      AppLifecycleState.inactive,
      AppLifecycleState.resumed,
    ]) {
      tester.binding.handleAppLifecycleStateChanged(state);
    }
    await tester.pump();
    await tester.pumpAndSettle();
    expect(connectionCalls, 2);
    await tester.enterText(find.byType(TextField).last, 'qq');
    await tester.pump();
    expect(find.textContaining('2 条匹配'), findsOneWidget);
    expect(find.text('QQ'), findsOneWidget);
    expect(find.text('com.tencent.mobileqq'), findsOneWidget);
    await tester.enterText(find.byType(TextField).last, 'tim');
    await tester.pump();
    expect(find.textContaining('1 条匹配'), findsOneWidget);
    expect(find.text('tim'), findsWidgets);
    expect(find.text('QQ · 用户 999 · com.tencent.mobileqq'), findsOneWidget);
    await tester.tap(find.byTooltip('刷新连接'));
    await tester.pumpAndSettle();
    expect(find.textContaining('刷新失败'), findsOneWidget);
    expect(find.text('UDP'), findsOneWidget);
    expect(find.text('tim'), findsWidgets);
    expect(connectionCalls, 3);
    expect(autoRootCalls, 1);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('root session failure shows a manual recovery action on tablet', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(1024, 768);
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
      switch (call.method) {
        case 'rootAuto':
          autoRootCalls++;
          return jsonEncode({'root': true, 'attempted': true});
        case 'snapshot':
          return jsonEncode({
            'root': false,
            'rootError': 'Root session ended; authorize Root again',
            'networks': [],
            'kernel': {
              'source': 'app / TrafficStats',
              'elapsed': 1000,
              'interfaces': [],
              'routes': '',
            },
          });
        case 'deviceSnapshot':
          return jsonEncode({
            'system': {
              'state': 'available',
              'source': 'Android API',
              'capturedAt': 1000,
              'data': {
                'manufacturer': 'Test',
                'model': 'Tablet',
                'android': '16',
              },
            },
          });
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

    await tester.pumpWidget(const CtosApp());
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.text('Root 采集会话已结束，请手动重新授权。'), findsOneWidget);
    expect(find.text('授权 Root'), findsOneWidget);
    expect(find.text('● ROOT 未连接'), findsOneWidget);
    expect(find.textContaining('VECTOR'), findsNothing);
    expect(autoRootCalls, 1);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
  });
}

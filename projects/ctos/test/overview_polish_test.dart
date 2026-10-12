import 'dart:async';
import 'dart:convert';

import 'package:ctos/main.dart';
import 'package:ctos/ui/ctos_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'helpers/overview_navigation.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  var online = false;
  String? rootError;
  Completer<String>? auto;
  final calls = <String>[];

  setUp(() {
    online = false;
    rootError = null;
    auto = null;
    calls.clear();
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(
      const MethodChannel('ctos/terminal'),
      (_) async => null,
    );
    messenger.setMockMethodCallHandler(native, (call) async {
      calls.add(call.method);
      return switch (call.method) {
        'rootAuto' =>
          auto?.future ??
              jsonEncode({
                'root': online,
                'attempted': true,
                'output': online ? 'uid=0 (active)' : 'permission denied',
              }),
        'snapshot' => jsonEncode({
          'root': online,
          if (rootError != null) 'rootError': rootError,
          'networks': [],
          'kernel': {
            'source': online ? 'root / procfs' : 'app / procfs',
            'elapsed': 1000,
            'interfaces': [],
            'routes': '',
          },
        }),
        'deviceSnapshot' => jsonEncode({}),
        'connections' => jsonEncode({'output': '', 'apps': {}}),
        'pythonCatalog' => jsonEncode({'scripts': []}),
        _ => null,
      };
    });
  });
  tearDown(() {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(native, null);
    messenger.setMockMethodCallHandler(
      const MethodChannel('ctos/terminal'),
      null,
    );
  });

  testWidgets(
    'title Root info reflects online state with metadata and no implicit authorization',
    (tester) async {
      online = true;
      await tester.pumpWidget(const CtosApp());
      await tester.pumpAndSettle();
      final info = find.byKey(const Key('root-status-info'));
      expect(find.byTooltip('ROOT 在线'), findsOneWidget);
      expect(tester.widget<IconButton>(info).color, CtosColors.primary);
      expect(tester.getSize(info).width, greaterThanOrEqualTo(48));
      expect(tester.getSize(info).height, greaterThanOrEqualTo(48));
      final title = tester.getRect(find.text('ctOS'));
      expect(tester.getRect(info).left, closeTo(title.right + 4, .01));
      expect(find.textContaining('APP 可用'), findsNothing);
      expect(find.text('● ROOT 在线'), findsNothing);
      await tester.tap(info);
      await tester.pumpAndSettle();
      expect(find.text('ROOT 在线'), findsOneWidget);
      expect(find.textContaining('root / procfs'), findsOneWidget);
      expect(find.textContaining('uid=0 (active)'), findsOneWidget);
      expect(calls.where((method) => method == 'root'), isEmpty);
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(find.byType(AlertDialog), findsNothing);
      expect(find.text('再按一次返回键退出'), findsNothing);
      online = false;
      rootError = 'Root session ended; authorize Root again';
      await tester.pump(const Duration(seconds: 2));
      await tester.pumpAndSettle();
      expect(find.byTooltip('ROOT 未连接'), findsOneWidget);
      expect(tester.widget<IconButton>(info).color, CtosColors.warning);
      await tester.tap(info);
      await tester.pump(const Duration(milliseconds: 400));
      expect(find.textContaining(rootError!), findsOneWidget);
      expect(find.textContaining('手动重新授权'), findsOneWidget);
      expect(calls.where((method) => method == 'root'), isEmpty);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets(
    'granting Root is yellow then offline retains actual denial reason',
    (tester) async {
      auto = Completer<String>();
      await tester.pumpWidget(const CtosApp());
      await tester.pump();
      final info = find.byKey(const Key('root-status-info'));
      expect(find.byTooltip('ROOT 授权中'), findsOneWidget);
      expect(tester.widget<IconButton>(info).color, CtosColors.warning);
      await tester.tap(info);
      await tester.pump(const Duration(milliseconds: 400));
      expect(find.textContaining('正在等待 Root 授权结果'), findsOneWidget);
      await tester.tap(find.text('关闭'));
      await tester.pump(const Duration(milliseconds: 400));
      auto!.complete(
        jsonEncode({
          'root': false,
          'attempted': true,
          'output': 'permission denied',
        }),
      );
      await tester.pumpAndSettle();
      expect(find.byTooltip('ROOT 未连接'), findsOneWidget);
      await tester.tap(info);
      await tester.pumpAndSettle();
      expect(find.textContaining('permission denied'), findsOneWidget);
      expect(calls.where((method) => method == 'rootAuto'), hasLength(1));
      expect(calls.where((method) => method == 'root'), isEmpty);
      await tester.pumpWidget(const SizedBox());
    },
  );

  for (final size in [
    const Size(320, 700),
    const Size(375, 812),
    const Size(800, 500),
    const Size(1200, 900),
  ]) {
    for (final scale in [1.0, 2.0]) {
      testWidgets(
        'overview selector fills content and centers equal categories at $size / $scale',
        (tester) async {
          tester.view.physicalSize = size;
          tester.view.devicePixelRatio = 1;
          tester.platformDispatcher.textScaleFactorTestValue = scale;
          addTearDown(tester.view.resetPhysicalSize);
          addTearDown(tester.view.resetDevicePixelRatio);
          addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
          await tester.pumpWidget(const CtosApp());
          await tester.pumpAndSettle();
          await selectOverviewCategory(tester, '设备');
          final selector = find.byKey(const Key('overview-category-selector'));
          final bounds = tester.getRect(selector);
          final list = tester.widget<ListView>(find.byType(ListView).first);
          final listBounds = tester.getRect(find.byType(ListView).first);
          final padding = list.padding! as EdgeInsets;
          expect(bounds.left, closeTo(listBounds.left + padding.left, .01));
          expect(bounds.right, closeTo(listBounds.right - padding.right, .01));
          expect(bounds.height, greaterThanOrEqualTo(48));
          final labels = ['设备', '网络', '接口', '连接'];
          for (var i = 0; i < labels.length; i++) {
            final text = find.descendant(
              of: selector,
              matching: find.text(labels[i]),
            );
            expect(
              tester.getCenter(text).dx,
              closeTo(bounds.left + bounds.width * (i + .5) / 4, 1),
            );
            expect(text.hitTestable(), findsOneWidget);
          }
          for (final label in labels.skip(1)) {
            await selectOverviewCategory(tester, label);
            expect(
              tester.takeException(),
              isNull,
              reason: '$label at $size / $scale',
            );
          }
          await tester.tap(find.text('终端').last);
          await tester.pumpAndSettle();
          expect(find.text('TERMINAL / 交互'), findsNothing);
          expect(find.text('选择终端会话'), findsOneWidget);
          await tester.pumpWidget(const SizedBox());
        },
      );
    }
  }
}

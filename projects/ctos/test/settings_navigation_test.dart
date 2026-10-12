import 'dart:convert';

import 'package:ctos/main.dart';
import 'package:ctos/settings_page.dart';
import 'package:ctos/ui/ctos_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'helpers/overview_navigation.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('new entries move export and lazily open complete security', (
    tester,
  ) async {
    var snapshots = 0;
    var secretReads = 0;
    final messenger = tester.binding.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(
      const MethodChannel('ctos/terminal'),
      (_) async => null,
    );
    messenger.setMockMethodCallHandler(native, (call) async {
      switch (call.method) {
        case 'rootAuto':
          return jsonEncode({'root': false, 'attempted': false});
        case 'snapshot':
          snapshots++;
          return jsonEncode({
            'root': false,
            'networks': [],
            'kernel': {
              'source': 'test',
              'elapsed': snapshots * 1000,
              'interfaces': [],
              'routes': '',
            },
          });
        case 'deviceSnapshot':
          return jsonEncode({});
        case 'pythonCatalog':
          return jsonEncode({'scripts': []});
        case 'secrets.status':
          secretReads++;
          return {
            'state': 'locked',
            'exists': true,
            'generation': 1,
            'quickUnlockEnabled': false,
            'quickUnlockAvailable': false,
            'backgroundTimeoutSeconds': 30,
          };
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
    await tester.pumpAndSettle();
    final nav = tester.widget<NavigationBar>(find.byType(NavigationBar));
    expect(
      nav.destinations.cast<NavigationDestination>().map((item) => item.label),
      ['概览', '工作台', '终端', '设置'],
    );
    expect(find.text('能力与恢复'), findsNothing);
    expect(find.text('网络现场'), findsNothing);
    expect(find.byTooltip('选择导出内容'), findsNothing);
    await selectOverviewCategory(tester, '网络');
    await revealOverviewContent(tester, find.text('授权 Root'));
    expect(find.text('授权 Root'), findsOneWidget);
    expect(find.textContaining('采集于'), findsOneWidget);
    final overviewSnapshots = snapshots;
    await tester.pump(const Duration(seconds: 4));
    expect(snapshots, greaterThan(overviewSnapshots));
    await tester.tap(find.text('工作台').last);
    await tester.pumpAndSettle();
    expect(find.text('秘密库设置'), findsNothing);
    var before = snapshots;
    await tester.pump(const Duration(seconds: 4));
    expect(snapshots, before);
    await tester.tap(find.text('设置').last);
    await tester.pumpAndSettle();
    expect(secretReads, 0);
    before = snapshots;
    await tester.pump(const Duration(seconds: 4));
    expect(snapshots, before);
    await tester.tap(find.text('数据导出'));
    await tester.pumpAndSettle();
    expect(find.text('选择导出内容'), findsOneWidget);
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.text('数据导出'), findsOneWidget);
    expect(find.text('再按一次返回键退出'), findsNothing);
    await tester.tap(find.text('安全'));
    await tester.pumpAndSettle();
    expect(secretReads, 1);
    expect(find.text('更换主口令'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('导出加密备份'),
      160,
      scrollable: overviewScroll,
    );
    await tester.pumpAndSettle();
    expect(find.text('导出加密备份'), findsOneWidget);
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.text('数据导出'), findsOneWidget);
    expect(find.text('再按一次返回键退出'), findsNothing);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
  });

  for (final size in [
    const Size(320, 700),
    const Size(375, 812),
    const Size(800, 500),
    const Size(1200, 900),
  ]) {
    testWidgets('settings actions remain reachable at $size with large text', (
      tester,
    ) async {
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1;
      tester.platformDispatcher.textScaleFactorTestValue = 2;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      var exports = 0;
      await tester.pumpWidget(
        MaterialApp(
          theme: CtosTheme.dark(),
          home: Scaffold(body: SettingsPage(onExport: () => exports++)),
        ),
      );
      await tester.ensureVisible(find.text('数据导出'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('数据导出'));
      expect(exports, 1);
      await tester.ensureVisible(find.text('安全'));
      await tester.pumpAndSettle();
      final security = find.widgetWithText(ListTile, '安全');
      expect(tester.getSize(security).height, greaterThanOrEqualTo(48));
      expect(security.hitTestable(), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('settings disables snapshot export while no snapshot exists', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: CtosTheme.dark(),
        home: const Scaffold(body: SettingsPage(onExport: null)),
      ),
    );
    expect(
      tester.widget<ListTile>(find.widgetWithText(ListTile, '数据导出')).enabled,
      isFalse,
    );
    expect(find.text('暂无可导出的快照'), findsOneWidget);
    expect(
      tester.widget<ListTile>(find.widgetWithText(ListTile, '安全')).enabled,
      isTrue,
    );
  });
}

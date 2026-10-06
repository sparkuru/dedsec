import 'dart:convert';

import 'package:ctos/main.dart';
import 'package:ctos/ui/ctos_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:xterm/xterm.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('semantic foregrounds meet normal text contrast on all surfaces', () {
    for (final background in [
      CtosColors.background,
      CtosColors.surface,
      CtosColors.surfaceRaised,
    ]) {
      for (final foreground in [
        CtosColors.text,
        CtosColors.textSecondary,
        CtosColors.primary,
        CtosColors.secondary,
        CtosColors.warning,
        CtosColors.error,
      ]) {
        final contrast =
            (foreground.computeLuminance() + .05) /
            (background.computeLuminance() + .05);
        expect(
          contrast,
          greaterThanOrEqualTo(4.5),
          reason: '$foreground on $background',
        );
      }
    }
  });

  test('enabled field and outline controls have visible boundaries', () {
    final theme = CtosTheme.dark();
    final inputBorder =
        theme.inputDecorationTheme.enabledBorder! as OutlineInputBorder;
    for (final color in [
      theme.colorScheme.outline,
      inputBorder.borderSide.color,
    ]) {
      for (final surface in [
        theme.colorScheme.surface,
        theme.inputDecorationTheme.fillColor!,
      ]) {
        final contrast =
            (color.computeLuminance() + .05) /
            (surface.computeLuminance() + .05);
        expect(contrast, greaterThanOrEqualTo(3));
      }
    }
  });

  for (final size in [
    const Size(320, 700),
    const Size(375, 812),
    const Size(800, 500),
    const Size(1200, 900),
  ]) {
    testWidgets(
      'core information supports $size with doubled text and reduced motion',
      (tester) async {
        tester.view.physicalSize = size;
        tester.view.devicePixelRatio = 1;
        tester.platformDispatcher.textScaleFactorTestValue = 2;
        tester.platformDispatcher.accessibilityFeaturesTestValue =
            const FakeAccessibilityFeatures(disableAnimations: true);
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
        addTearDown(
          tester.platformDispatcher.clearAccessibilityFeaturesTestValue,
        );
        mockHost();
        await tester.pumpWidget(const CtosApp());
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
        await tester.scrollUntilVisible(
          find.byKey(const Key('overview-metric-可用内存')),
          150,
          scrollable: find.byType(Scrollable).first,
        );
        expect(
          tester
              .widget<Text>(find.byKey(const Key('overview-metric-可用内存')))
              .data,
          '—',
        );
        await tester.tap(find.text('信息').last);
        await tester.pumpAndSettle();
        for (final label in ['网络', '接口', '连接']) {
          final tab = find.text(label).last;
          await tester.ensureVisible(tab);
          await tester.tap(tab);
          await tester.pumpAndSettle();
          expect(tester.takeException(), isNull, reason: '$label at $size');
        }
        final field = find.byType(TextField).last;
        await tester.enterText(field, 'not-in-snapshot');
        await tester.pumpAndSettle();
        await tester.scrollUntilVisible(
          find.textContaining('0 条匹配'),
          100,
          scrollable: find
              .descendant(
                of: find.byKey(const PageStorageKey('connections-scroll')),
                matching: find.byType(Scrollable),
              )
              .first,
        );
        expect(find.textContaining('0 条匹配'), findsOneWidget);
        await tester.pumpWidget(const SizedBox());
      },
    );
  }

  testWidgets('navigation breakpoint preserves interface draft and live PTY', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(800, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    var starts = 0;
    var stops = 0;
    mockHost(
      onCall: (call) {
        if (call.method == 'terminalStart') starts++;
        if (call.method == 'terminalStop') stops++;
      },
    );
    await tester.pumpWidget(const CtosApp());
    await tester.pumpAndSettle();
    await tester.tap(find.text('信息').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('接口').last);
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField).first, 'veth-long');
    await tester.pump();
    tester.view.physicalSize = const Size(1200, 900);
    await tester.pumpAndSettle();
    expect(find.byType(NavigationRail), findsOneWidget);
    expect(
      tester.widget<TextField>(find.byType(TextField).first).controller?.text ??
          tester
              .widget<EditableText>(find.byType(EditableText).first)
              .controller
              .text,
      'veth-long',
    );
    await tester.tap(find.text('终端').last);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('terminal-app-entry')));
    await tester.pumpAndSettle();
    final terminal = tester
        .widget<TerminalView>(find.byKey(const Key('terminal-output-view')))
        .terminal;
    tester.view.physicalSize = const Size(375, 812);
    await tester.pumpAndSettle();
    expect(find.byType(NavigationBar), findsOneWidget);
    expect(
      tester
          .widget<TerminalView>(find.byKey(const Key('terminal-output-view')))
          .terminal,
      same(terminal),
    );
    expect(starts, 1);
    expect(stops, 0);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
  });
}

void mockHost({void Function(MethodCall)? onCall}) {
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  messenger.setMockMethodCallHandler(
    const MethodChannel('ctos/terminal'),
    (_) async => null,
  );
  messenger.setMockMethodCallHandler(native, (call) async {
    onCall?.call(call);
    switch (call.method) {
      case 'rootAuto':
        return jsonEncode({'root': true, 'attempted': true});
      case 'snapshot':
        return jsonEncode({
          'root': true,
          'android': '16',
          'networks': [
            {
              'transport': 'Wi-Fi',
              'interface': 'veth-long-interface-name',
              'default': true,
              'addresses': ['2001:db8:aaaa:bbbb:cccc:dddd:eeee:ffff/64'],
              'dns': ['2001:db8::1'],
              'routes': [],
              'validated': true,
              'metered': false,
              'privateDns': false,
            },
          ],
          'kernel': {
            'source': 'test',
            'elapsed': 1000,
            'routes': '',
            'interfaces': [
              {
                'name': 'veth-long-interface-name',
                'state': 'UP',
                'rx': 100,
                'tx': 200,
                'addresses': ['2001:db8::1/64'],
              },
            ],
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
              'uptimeMs': 60000,
            },
          },
        });
      case 'connections':
        return jsonEncode({
          'output': 'tcp ESTAB 0 0 127.0.0.1:443 192.0.2.1:5555 uid:2000\n',
          'partial': false,
        });
      case 'terminalStart':
        return 'App · PTY';
      case 'pythonCatalog':
        return jsonEncode({
          'python': '3.13.9',
          'architecture': 'aarch64',
          'sdk': 1,
          'scripts': [],
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
}

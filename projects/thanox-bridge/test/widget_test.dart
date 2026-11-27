import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:thanox_bridge/main.dart';

const channel = MethodChannel('im.majo.thanox_bridge/permissions');

Map<String, Object?> app({
  String name = '相机测试',
  String package = 'dev.test.camera',
}) => {
  'label': name,
  'packageName': package,
  'uid': 10123,
  'userId': 0,
  'sources': ['Android'],
};

Map<String, Object?> catalog(List<Map<String, Object?>> apps) => {
  'apps': apps,
  'notice': 'Android 列表可能不完整，可检查设备的应用列表访问设置。',
};

Map<String, Object?> details({String state = 'DENY', String? serviceError}) => {
  ...app(),
  'identityToken': 'identity-of-selected-installation',
  'permissions': [
    {
      'name': 'android.permission.CAMERA',
      'label': '相机',
      'granted': state == 'ALLOW_ALWAYS',
      'protection': 'dangerous',
    },
  ],
  'ops': serviceError == null
      ? [
          {
            'code': 26,
            'name': 'CAMERA',
            'permission': 'android.permission.CAMERA',
            'state': state,
            'runtime': true,
            'allowedStates': ['ALLOW_ALWAYS', 'DENY'],
          },
        ]
      : [],
  'serviceError': serviceError,
  'coverageNote': '仅显示当前接口支持的 AppOps。',
};

Future<void> startAppList(WidgetTester tester) async {
  await tester.pumpWidget(const ThanoxBridgeApp());
  await tester.ensureVisible(find.text('读取应用列表'));
  await tester.tap(find.text('读取应用列表'));
  await tester.pumpAndSettle();
}

Future<void> openDetails(WidgetTester tester) async {
  await startAppList(tester);
  await tester.tap(find.text('相机测试'));
  await tester.pumpAndSettle();
}

Future<void> chooseAllow(WidgetTester tester) async {
  final edit = find.byKey(const ValueKey('op-26-change'));
  await tester.ensureVisible(edit);
  await tester.tap(edit);
  await tester.pumpAndSettle();
  await tester.tap(find.text('始终允许'));
}

Future<void> revealDetail(WidgetTester tester, Finder target) =>
    tester.scrollUntilVisible(
      target,
      200,
      scrollable: find
          .descendant(
            of: find.byKey(const PageStorageKey<String>('permission-scroll')),
            matching: find.byType(Scrollable),
          )
          .first,
    );

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  testWidgets('initial list read and later refresh are explicit', (
    tester,
  ) async {
    var reads = 0;
    final pending = Completer<Map<String, Object?>>();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'listApps');
          reads++;
          return reads == 1 ? pending.future : catalog([app(name: '刷新结果')]);
        });
    await tester.pumpWidget(const ThanoxBridgeApp());
    await tester.pumpAndSettle();
    expect(reads, 0);
    expect(find.textContaining('选择应用查看其权限'), findsOneWidget);
    expect(find.textContaining('设备可能询问'), findsOneWidget);
    expect(find.text('读取应用列表'), findsOneWidget);

    await tester.tap(find.text('读取应用列表'));
    await tester.pump();
    expect(reads, 1);
    expect(find.text('正在读取应用列表…'), findsOneWidget);
    pending.complete(catalog([app()]));
    await tester.pumpAndSettle();
    expect(find.text('相机测试'), findsOneWidget);
    await tester.tap(find.byTooltip('刷新应用列表'));
    await tester.pumpAndSettle();
    expect(reads, 2);
    expect(find.text('刷新结果'), findsOneWidget);
  });

  for (final oldRequestFails in [false, true]) {
    testWidgets(
      'refresh keeps the newer list when an older request ${oldRequestFails ? 'fails' : 'succeeds'} later',
      (tester) async {
        final oldRequest = Completer<Map<String, Object?>>();
        final newRequest = Completer<Map<String, Object?>>();
        var reads = 0;
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(channel, (call) async {
              expect(call.method, 'listApps');
              reads++;
              if (reads == 1) return oldRequest.future;
              if (reads == 2) return newRequest.future;
              fail('Unexpected additional list request');
            });

        await tester.pumpWidget(const ThanoxBridgeApp());
        await tester.tap(find.text('读取应用列表'));
        await tester.pump();
        expect(reads, 1);
        expect(find.text('正在读取应用列表…'), findsOneWidget);
        await tester.tap(find.byTooltip('刷新应用列表'));
        await tester.pump();
        expect(reads, 2);

        newRequest.complete(
          catalog([app(name: '新列表', package: 'dev.test.new')]),
        );
        await tester.pumpAndSettle();
        expect(find.text('新列表'), findsOneWidget);
        if (oldRequestFails) {
          oldRequest.completeError(
            PlatformException(code: 'failed', message: '旧请求失败'),
          );
        } else {
          oldRequest.complete(
            catalog([app(name: '旧列表', package: 'dev.test.old')]),
          );
        }
        await tester.pumpAndSettle();
        expect(find.text('新列表'), findsOneWidget);
        expect(find.text('旧列表'), findsNothing);
        expect(find.text('旧请求失败'), findsNothing);
        expect(find.text('正在读取应用列表…'), findsNothing);
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets('refresh may explicitly start the first list read', (
    tester,
  ) async {
    var reads = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'listApps');
          reads++;
          return catalog([app()]);
        });
    await tester.pumpWidget(const ThanoxBridgeApp());
    await tester.pumpAndSettle();
    expect(reads, 0);
    await tester.tap(find.byTooltip('刷新应用列表'));
    await tester.pumpAndSettle();
    expect(reads, 1);
    expect(find.text('相机测试'), findsOneWidget);
  });

  testWidgets('exact package lookup works before reading the list', (
    tester,
  ) async {
    final target = app(name: '查询目标', package: 'dev.test.target');
    final methods = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          methods.add(call.method);
          if (call.method == 'findApp') {
            expect(call.arguments, {'packageName': 'dev.test.target'});
            return target;
          }
          expect(call.method, 'getAppDetails');
          expect(call.arguments, {
            'packageName': 'dev.test.target',
            'userId': 0,
            'uid': 10123,
          });
          return {...details(), ...target};
        });
    await tester.pumpWidget(const ThanoxBridgeApp());
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const Key('app-search')),
      'dev.test.target',
    );
    await tester.pump();
    expect(methods, isEmpty);
    await tester.tap(find.text('按完整包名查找'));
    await tester.pumpAndSettle();
    expect(methods, ['findApp']);
    expect(find.text('查询目标'), findsOneWidget);
    await tester.tap(find.text('查询目标'));
    await tester.pumpAndSettle();
    expect(methods, ['findApp', 'getAppDetails']);
    expect(find.text('Android 声明权限'), findsOneWidget);
  });

  testWidgets('an initial exact lookup cannot replace a newer query', (
    tester,
  ) async {
    final pending = Completer<Map<String, Object?>>();
    final methods = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          methods.add(call.method);
          expect(call.method, 'findApp');
          return pending.future;
        });
    await tester.pumpWidget(const ThanoxBridgeApp());
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('app-search')), 'dev.test.old');
    await tester.pump();
    await tester.tap(find.text('按完整包名查找'));
    await tester.pump();
    await tester.enterText(find.byKey(const Key('app-search')), 'dev.test.new');
    pending.complete(app(name: '旧结果', package: 'dev.test.old'));
    await tester.pumpAndSettle();
    expect(methods, ['findApp']);
    expect(find.text('旧结果'), findsNothing);
    expect(find.text('按完整包名查找'), findsOneWidget);
  });

  testWidgets('initial lookup is usable with large text and the keyboard', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(375, 812);
    tester.view.devicePixelRatio = 1;
    tester.view.viewInsets = const FakeViewPadding(bottom: 300);
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
      tester.view.resetViewInsets();
      tester.platformDispatcher.clearTextScaleFactorTestValue();
    });
    final methods = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          methods.add(call.method);
          expect(call.method, 'findApp');
          return app(name: '查询目标', package: 'dev.test.target');
        });
    await tester.pumpWidget(const ThanoxBridgeApp());
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const Key('app-search')),
      'dev.test.target',
    );
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('按完整包名查找'));
    await tester.tap(find.text('按完整包名查找'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('查询目标'));
    expect(find.text('查询目标'), findsOneWidget);
    expect(methods, ['findApp']);
    expect(tester.takeException(), isNull);
  });

  testWidgets('app list can be searched by package name', (tester) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'listApps');
          return catalog([
            app(),
            app(name: '日历测试', package: 'dev.test.calendar'),
          ]);
        });
    await startAppList(tester);
    expect(find.text('相机测试'), findsOneWidget);
    expect(find.text('日历测试'), findsOneWidget);
    await tester.enterText(find.byKey(const Key('app-search')), 'calendar');
    await tester.pump();
    expect(find.text('相机测试'), findsNothing);
    expect(find.text('日历测试'), findsOneWidget);
  });

  testWidgets('app list explains device visibility without inferring access', (
    tester,
  ) async {
    final visible = app()..['sources'] = ['Android'];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'listApps');
          return catalog([visible]);
        });
    await startAppList(tester);
    expect(find.textContaining('设备的应用列表访问设置'), findsOneWidget);
    expect(find.textContaining('列表可能不完整'), findsOneWidget);
    expect(find.textContaining('用户 0 · UID 10123\n来源：Android'), findsOneWidget);
    expect(find.text('相机测试'), findsOneWidget);
  });

  testWidgets('an empty Android list does not claim access was denied', (
    tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'listApps');
          return catalog([]);
        });
    await startAppList(tester);
    expect(find.text('没有可显示的应用。'), findsOneWidget);
    expect(find.textContaining('设备的应用列表访问设置'), findsOneWidget);
    expect(find.textContaining('列表可能不完整'), findsOneWidget);
    expect(find.textContaining('已拒绝'), findsNothing);
    expect(find.textContaining('已允许'), findsNothing);
  });

  testWidgets('an omitted app can be found by its exact package name', (
    tester,
  ) async {
    final target = app(name: '身份测试目标', package: 'dev.test.identity');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'listApps') return catalog([app()]);
          if (call.method == 'findApp') {
            expect(call.arguments, {'packageName': 'dev.test.identity'});
            return target;
          }
          expect(call.method, 'getAppDetails');
          expect((call.arguments as Map)['packageName'], 'dev.test.identity');
          return {...details(), ...target};
        });
    await startAppList(tester);
    await tester.enterText(
      find.byKey(const Key('app-search')),
      'dev.test.identity',
    );
    await tester.pump();
    await tester.tap(find.text('按完整包名查找'));
    await tester.pumpAndSettle();
    expect(find.text('身份测试目标'), findsOneWidget);
    await tester.tap(find.text('身份测试目标'));
    await tester.pumpAndSettle();
    expect(find.text('Android 声明权限'), findsOneWidget);
  });

  testWidgets('exact lookup remains visible alongside partial matches', (
    tester,
  ) async {
    final target = app(name: '身份测试目标', package: 'dev.test.identity');
    final similar = app(name: '相似应用', package: 'dev.test.identity.other');
    var lookups = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'listApps') return catalog([similar]);
          expect(call.method, 'findApp');
          expect(call.arguments, {'packageName': 'dev.test.identity'});
          lookups++;
          return target;
        });
    await startAppList(tester);
    await tester.enterText(
      find.byKey(const Key('app-search')),
      'dev.test.identity',
    );
    await tester.pump();
    expect(find.text('相似应用'), findsOneWidget);
    expect(find.text('按完整包名查找'), findsOneWidget);
    await tester.testTextInput.receiveAction(TextInputAction.done);
    await tester.pumpAndSettle();
    expect(lookups, 1);
    expect(find.text('身份测试目标'), findsOneWidget);
    expect(find.text('相似应用'), findsOneWidget);
  });

  testWidgets('a dotless package can be looked up alongside partial matches', (
    tester,
  ) async {
    final target = app(name: 'Android 框架', package: 'android');
    final similar = app(name: 'Android 组件', package: 'com.android.component');
    var lookups = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'listApps') return catalog([similar]);
          expect(call.method, 'findApp');
          expect(call.arguments, {'packageName': 'android'});
          lookups++;
          return target;
        });
    await startAppList(tester);
    await tester.enterText(find.byKey(const Key('app-search')), 'android');
    await tester.pump();
    expect(find.text('Android 组件'), findsOneWidget);
    expect(find.text('按完整包名查找'), findsOneWidget);
    expect(lookups, 0);
    await tester.tap(find.text('按完整包名查找'));
    await tester.pumpAndSettle();
    expect(lookups, 1);
    expect(find.text('Android 框架'), findsOneWidget);
    expect(find.text('Android 组件'), findsOneWidget);

    await tester.enterText(
      find.byKey(const Key('app-search')),
      'com.android.component',
    );
    await tester.pumpAndSettle();
    expect(find.text('Android 组件'), findsOneWidget);
    expect(find.text('Android 框架'), findsNothing);
    expect(find.text('按完整包名查找'), findsNothing);
    expect(lookups, 1);
  });

  testWidgets('exact lookup distinguishes no result from a platform failure', (
    tester,
  ) async {
    var attempts = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'listApps') return catalog([app()]);
          expect(call.method, 'findApp');
          attempts++;
          if (attempts == 1) return null;
          throw PlatformException(code: 'failed', message: '查询失败');
        });
    await startAppList(tester);
    await tester.enterText(
      find.byKey(const Key('app-search')),
      'dev.test.missing',
    );
    await tester.pump();
    await tester.tap(find.text('按完整包名查找'));
    await tester.pumpAndSettle();
    expect(find.text('未找到或无权查看该应用。'), findsOneWidget);
    await tester.tap(find.text('按完整包名查找'));
    await tester.pumpAndSettle();
    expect(find.text('查询失败'), findsOneWidget);
  });

  testWidgets('exact lookup remains available when the app list fails', (
    tester,
  ) async {
    var attempts = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'listApps') {
            throw PlatformException(code: 'failed', message: '列表读取失败');
          }
          expect(call.method, 'findApp');
          attempts++;
          if (attempts == 1) {
            return app(name: '查询目标', package: 'dev.test.missing');
          }
          throw PlatformException(code: 'failed', message: '包名查询失败');
        });
    await startAppList(tester);
    await tester.enterText(
      find.byKey(const Key('app-search')),
      'dev.test.missing',
    );
    await tester.pumpAndSettle();
    expect(find.text('应用列表不可用。'), findsOneWidget);
    expect(find.text('列表读取失败'), findsOneWidget);
    expect(find.text('重试'), findsOneWidget);
    await tester.tap(find.text('按完整包名查找'));
    await tester.pumpAndSettle();
    expect(find.text('查询目标'), findsOneWidget);
    expect(find.text('列表读取失败'), findsOneWidget);
    await tester.tap(find.text('按完整包名查找'));
    await tester.pumpAndSettle();
    expect(find.text('包名查询失败'), findsOneWidget);
    expect(find.text('列表读取失败'), findsOneWidget);
  });

  testWidgets('a lookup from a failed list cannot replace a newer search', (
    tester,
  ) async {
    final pending = Completer<Map<String, Object?>>();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'listApps') {
            throw PlatformException(code: 'failed', message: '列表读取失败');
          }
          expect(call.method, 'findApp');
          return pending.future;
        });
    await startAppList(tester);
    await tester.enterText(find.byKey(const Key('app-search')), 'dev.test.old');
    await tester.pump();
    await tester.tap(find.text('按完整包名查找'));
    await tester.pump();
    await tester.enterText(find.byKey(const Key('app-search')), 'dev.test.new');
    pending.complete(app(name: '旧结果', package: 'dev.test.old'));
    await tester.pumpAndSettle();
    expect(find.text('旧结果'), findsNothing);
    expect(find.text('列表读取失败'), findsOneWidget);
  });

  testWidgets('failed-list lookup fits with large text and a keyboard', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(375, 812);
    tester.view.devicePixelRatio = 1;
    tester.view.viewInsets = const FakeViewPadding(bottom: 300);
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
      tester.view.resetViewInsets();
      tester.platformDispatcher.clearTextScaleFactorTestValue();
    });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'listApps') {
            throw PlatformException(code: 'failed', message: '列表读取失败');
          }
          expect(call.method, 'findApp');
          return app(name: '查询目标', package: 'dev.test.missing');
        });
    await startAppList(tester);
    await tester.enterText(
      find.byKey(const Key('app-search')),
      'dev.test.missing',
    );
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('按完整包名查找'));
    await tester.tap(find.text('按完整包名查找'));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.text('查询目标'), findsOneWidget);
    expect(find.text('列表读取失败'), findsOneWidget);
  });

  testWidgets('a completed lookup cannot replace a newer search', (
    tester,
  ) async {
    final pending = Completer<Map<String, Object?>>();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'listApps') return catalog([app()]);
          expect(call.method, 'findApp');
          return pending.future;
        });
    await startAppList(tester);
    await tester.enterText(find.byKey(const Key('app-search')), 'dev.test.old');
    await tester.pump();
    await tester.tap(find.text('按完整包名查找'));
    await tester.pump();
    await tester.enterText(find.byKey(const Key('app-search')), 'dev.test.new');
    pending.complete(app(name: '旧结果', package: 'dev.test.old'));
    await tester.pumpAndSettle();
    expect(find.text('旧结果'), findsNothing);
  });

  testWidgets('package lookup fits with large text and an open keyboard', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(375, 812);
    tester.view.devicePixelRatio = 1;
    tester.view.viewInsets = const FakeViewPadding(bottom: 300);
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
      tester.view.resetViewInsets();
      tester.platformDispatcher.clearTextScaleFactorTestValue();
    });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'listApps') return catalog([app()]);
          expect(call.method, 'findApp');
          return app(name: '测试应用', package: 'dev.test.missing');
        });
    await startAppList(tester);
    await tester.enterText(
      find.byKey(const Key('app-search')),
      'dev.test.missing',
    );
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.text('按完整包名查找'), findsOneWidget);
    await tester.tap(find.text('按完整包名查找'));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.text('测试应用'), findsOneWidget);
  });

  testWidgets(
    'an immediate list failure before the next frame can be retried',
    (tester) async {
      var reads = 0;
      final firstRequest = Completer<Map<String, Object?>>();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            expect(call.method, 'listApps');
            reads++;
            if (reads == 1) return firstRequest.future;
            return catalog([app()]);
          });
      await tester.pumpWidget(const ThanoxBridgeApp());
      await tester.tap(find.text('读取应用列表'));
      await tester.idle();
      expect(reads, 1);
      expect(find.text('读取应用列表'), findsOneWidget);

      firstRequest.completeError(
        PlatformException(code: 'failed', message: '读取失败'),
      );
      await tester.idle();
      expect(find.text('读取应用列表'), findsOneWidget);
      expect(tester.takeException(), isNull);

      await tester.pumpAndSettle();
      expect(find.text('读取失败'), findsOneWidget);
      await tester.tap(find.text('重试'));
      await tester.pumpAndSettle();
      expect(find.text('相机测试'), findsOneWidget);
      expect(reads, 2);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('permission details fit a narrow screen with large text', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(375, 812);
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
      tester.platformDispatcher.clearTextScaleFactorTestValue();
    });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'listApps') return catalog([app()]);
          expect(call.method, 'getAppDetails');
          return details(serviceError: '服务未连接');
        });
    await openDetails(tester);
    expect(find.text('Android 声明权限'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('Android permissions remain visible without Thanox', (
    tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'listApps') return catalog([app()]);
          expect(call.method, 'getAppDetails');
          expect(call.arguments, {
            'packageName': 'dev.test.camera',
            'userId': 0,
            'uid': 10123,
          });
          return details(serviceError: '服务未连接');
        });
    await openDetails(tester);
    expect(find.text('Android 声明权限'), findsOneWidget);
    expect(find.text('相机'), findsOneWidget);
    expect(find.text('Thanox 不可用：服务未连接'), findsOneWidget);
    await revealDetail(tester, find.text('Thanox AppOps'));
    expect(find.text('Thanox AppOps'), findsOneWidget);
    expect(find.byKey(const ValueKey('op-26-change')), findsNothing);
  });

  testWidgets('a stale identity error prevents editing', (tester) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'listApps') return catalog([app()]);
          expect(call.method, 'getAppDetails');
          expect((call.arguments as Map)['uid'], 10123);
          throw PlatformException(code: 'stale', message: '应用身份已改变，请重新加载');
        });
    await openDetails(tester);
    expect(find.text('应用身份已改变，请重新加载'), findsOneWidget);
    expect(find.byKey(const ValueKey('op-26-change')), findsNothing);
  });

  testWidgets('unverified operations remain read-only', (tester) async {
    final readOnlyDetails = details();
    final operations = readOnlyDetails['ops'] as List;
    (operations.single as Map<String, Object?>)['allowedStates'] = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'listApps') return catalog([app()]);
          expect(call.method, 'getAppDetails');
          return readOnlyDetails;
        });
    await openDetails(tester);
    await revealDetail(tester, find.text('仅查看'));
    expect(find.text('仅查看'), findsOneWidget);
    expect(find.byKey(const ValueKey('op-26-change')), findsNothing);
  });

  testWidgets(
    'missing identity makes an otherwise editable operation read-only',
    (tester) async {
      final withoutIdentity = details()..['identityToken'] = null;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            if (call.method == 'listApps') return catalog([app()]);
            expect(call.method, 'getAppDetails');
            return withoutIdentity;
          });
      await openDetails(tester);
      await revealDetail(tester, find.text('仅查看'));
      expect(find.byKey(const ValueKey('op-26-change')), findsNothing);
    },
  );

  testWidgets('refresh preserves position in a long operation list', (
    tester,
  ) async {
    final longDetails = details();
    final operations = List<Map<String, Object?>>.from(
      longDetails['ops'] as List,
    );
    longDetails['ops'] = operations;
    for (var index = 0; index < 20; index++) {
      operations.add({
        'code': index + 100,
        'name': 'READ_ONLY_$index',
        'permission': null,
        'state': 'DEFAULT',
        'runtime': false,
        'allowedStates': <String>[],
      });
    }
    var reads = 0;
    final refresh = Completer<Map<String, Object?>>();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'listApps') return catalog([app()]);
          expect(call.method, 'getAppDetails');
          reads++;
          return reads == 1 ? longDetails : refresh.future;
        });
    await openDetails(tester);
    final list = find.byKey(const PageStorageKey<String>('permission-scroll'));
    final scrollable = find
        .descendant(of: list, matching: find.byType(Scrollable))
        .first;
    await tester.drag(list, const Offset(0, -700));
    await tester.pumpAndSettle();
    final originalOffset = tester
        .state<ScrollableState>(scrollable)
        .position
        .pixels;
    expect(originalOffset, greaterThan(0));
    await tester.tap(find.byTooltip('刷新权限状态'));
    await tester.pump();
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
    refresh.complete(longDetails);
    await tester.pumpAndSettle();
    expect(
      tester.state<ScrollableState>(scrollable).position.pixels,
      closeTo(originalOffset, 1),
    );
  });

  testWidgets('single operation disables controls and reloads actual state', (
    tester,
  ) async {
    var detailReads = 0;
    var state = 'DENY';
    final pendingWrite = Completer<Map<String, Object?>>();
    Map<Object?, Object?>? writeArguments;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          switch (call.method) {
            case 'listApps':
              return catalog([app()]);
            case 'getAppDetails':
              detailReads++;
              return details(state: state);
            case 'setOpMode':
              writeArguments = Map<Object?, Object?>.from(
                call.arguments as Map,
              );
              return pendingWrite.future;
            default:
              throw MissingPluginException();
          }
        });
    await openDetails(tester);
    await chooseAllow(tester);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.byType(LinearProgressIndicator), findsOneWidget);
    expect(
      tester
          .widget<IconButton>(find.byKey(const ValueKey('op-26-change')))
          .onPressed,
      isNull,
    );
    expect(writeArguments, {
      'packageName': 'dev.test.camera',
      'uid': 10123,
      'userId': 0,
      'identityToken': 'identity-of-selected-installation',
      'code': 26,
      'mode': 'ALLOW_ALWAYS',
    });
    state = 'ALLOW_ALWAYS';
    pendingWrite.complete({
      'requestedState': 'ALLOW_ALWAYS',
      'observedState': state,
      'matched': true,
    });
    await tester.pumpAndSettle();
    expect(detailReads, 2);
    expect(find.text('已读取实际状态：始终允许。'), findsOneWidget);
    await revealDetail(tester, find.textContaining('Thanox 状态：始终允许'));
    expect(find.textContaining('Thanox 状态：始终允许'), findsOneWidget);
  });

  testWidgets('mismatched readback is not reported as success', (tester) async {
    var detailReads = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'listApps') return catalog([app()]);
          if (call.method == 'getAppDetails') {
            detailReads++;
            return details();
          }
          return {
            'requestedState': 'ALLOW_ALWAYS',
            'observedState': 'DENY',
            'matched': false,
          };
        });
    await openDetails(tester);
    await chooseAllow(tester);
    await tester.pumpAndSettle();
    expect(detailReads, 2);
    expect(find.textContaining('结果与请求不一致'), findsOneWidget);
    expect(find.textContaining('已读取实际状态'), findsNothing);
  });

  testWidgets('failed write retains its error and reloads current state', (
    tester,
  ) async {
    var detailReads = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'listApps') return catalog([app()]);
          if (call.method == 'getAppDetails') {
            detailReads++;
            return details();
          }
          throw PlatformException(code: 'denied', message: '写入被拒绝');
        });
    await openDetails(tester);
    await chooseAllow(tester);
    await tester.pumpAndSettle();
    expect(detailReads, 2);
    expect(find.text('写入被拒绝'), findsOneWidget);
    expect(find.textContaining('已读取实际状态'), findsNothing);
    await revealDetail(tester, find.textContaining('Thanox 状态：拒绝'));
    expect(find.textContaining('Thanox 状态：拒绝'), findsOneWidget);
    await tester.tap(find.byTooltip('刷新权限状态'));
    await tester.pumpAndSettle();
    expect(find.text('写入被拒绝'), findsOneWidget);
  });

  testWidgets('a timed-out write stays uncertain while the worker is busy', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(375, 812);
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
      tester.platformDispatcher.clearTextScaleFactorTestValue();
    });
    var detailReads = 0;
    var workerBusy = true;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'listApps') return catalog([app()]);
          if (call.method == 'getAppDetails') {
            detailReads++;
            if (detailReads == 1) return details();
            if (workerBusy) {
              throw PlatformException(
                code: 'operation_busy',
                message: '上一次操作尚未结束，该请求未执行；请稍后重试。',
              );
            }
            return details(state: 'ALLOW_ALWAYS');
          }
          expect(call.method, 'setOpMode');
          throw PlatformException(
            code: 'operation_timeout',
            message: '操作超时，结果不确定，可能仍会在后台执行；重启不能保证取消已提交的操作。请稍后刷新检查当前状态。',
          );
        });

    await openDetails(tester);
    await revealDetail(tester, find.byKey(const ValueKey('op-26-change')));
    await tester.pumpAndSettle();
    await chooseAllow(tester);
    await tester.pumpAndSettle();

    expect(find.textContaining('结果不确定'), findsOneWidget);
    expect(find.textContaining('可能仍会'), findsOneWidget);
    expect(find.textContaining('上一次操作尚未结束'), findsOneWidget);
    expect(find.byKey(const ValueKey('op-26-change')), findsNothing);
    expect(find.textContaining('已读取实际状态'), findsNothing);
    expect(tester.takeException(), isNull);

    workerBusy = false;
    await tester.tap(find.text('重试'));
    await tester.pumpAndSettle();
    expect(detailReads, 3);
    expect(find.textContaining('结果不确定'), findsOneWidget);
    await revealDetail(tester, find.textContaining('Thanox 状态：始终允许'));
    expect(find.textContaining('Thanox 状态：始终允许'), findsOneWidget);
  });

  testWidgets('manual refresh clears an earlier successful readback', (
    tester,
  ) async {
    var state = 'DENY';
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'listApps') return catalog([app()]);
          if (call.method == 'getAppDetails') return details(state: state);
          state = 'ALLOW_ALWAYS';
          return {
            'requestedState': state,
            'observedState': state,
            'matched': true,
          };
        });
    await openDetails(tester);
    await chooseAllow(tester);
    await tester.pumpAndSettle();
    expect(find.text('已读取实际状态：始终允许。'), findsOneWidget);
    state = 'DENY';
    await tester.tap(find.byTooltip('刷新权限状态'));
    await tester.pumpAndSettle();
    expect(find.text('已读取实际状态：始终允许。'), findsNothing);
    await revealDetail(tester, find.textContaining('Thanox 状态：拒绝'));
    expect(find.textContaining('Thanox 状态：拒绝'), findsOneWidget);
  });

  testWidgets('a changed state during post-write refresh is reported', (
    tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'listApps') return catalog([app()]);
          if (call.method == 'getAppDetails') return details();
          return {
            'requestedState': 'ALLOW_ALWAYS',
            'observedState': 'ALLOW_ALWAYS',
            'matched': true,
          };
        });
    await openDetails(tester);
    await chooseAllow(tester);
    await tester.pumpAndSettle();
    expect(find.textContaining('刷新后状态：拒绝'), findsOneWidget);
    await revealDetail(tester, find.textContaining('Thanox 状态：拒绝'));
    expect(find.textContaining('Thanox 状态：拒绝'), findsOneWidget);
  });

  testWidgets('a missing post-write refresh is reported as uncertain', (
    tester,
  ) async {
    var reads = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'listApps') return catalog([app()]);
          if (call.method == 'getAppDetails') {
            reads++;
            return reads == 1 ? details() : details(serviceError: '服务未连接');
          }
          return {
            'requestedState': 'ALLOW_ALWAYS',
            'observedState': 'ALLOW_ALWAYS',
            'matched': true,
          };
        });
    await openDetails(tester);
    await chooseAllow(tester);
    await tester.pumpAndSettle();
    expect(find.textContaining('刷新后无法读取该操作'), findsOneWidget);
    expect(find.text('Thanox 不可用：服务未连接'), findsOneWidget);
  });

  for (final updatedToken in ['identity-of-replacement', null]) {
    testWidgets(
      'a ${updatedToken == null ? 'missing' : 'changed'} identity after writing is reported',
      (tester) async {
        var reads = 0;
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(channel, (call) async {
              if (call.method == 'listApps') return catalog([app()]);
              if (call.method == 'getAppDetails') {
                reads++;
                if (reads == 1) return details();
                return details(state: 'ALLOW_ALWAYS')
                  ..['identityToken'] = updatedToken;
              }
              return {
                'requestedState': 'ALLOW_ALWAYS',
                'observedState': 'ALLOW_ALWAYS',
                'matched': true,
              };
            });
        await openDetails(tester);
        await chooseAllow(tester);
        await tester.pumpAndSettle();
        expect(find.textContaining('刷新后应用身份已变化或无法核验'), findsOneWidget);
        expect(find.textContaining('已读取实际状态'), findsNothing);
        expect(find.textContaining('操作后回读：始终允许'), findsOneWidget);
      },
    );
  }

  testWidgets('an identity change does not hide a mismatched write', (
    tester,
  ) async {
    var reads = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'listApps') return catalog([app()]);
          if (call.method == 'getAppDetails') {
            reads++;
            return reads == 1
                ? details()
                : (details()..['identityToken'] = 'identity-of-replacement');
          }
          return {
            'requestedState': 'ALLOW_ALWAYS',
            'observedState': 'DENY',
            'matched': false,
          };
        });
    await openDetails(tester);
    await chooseAllow(tester);
    await tester.pumpAndSettle();
    expect(find.textContaining('结果与请求不一致'), findsOneWidget);
    expect(find.textContaining('刷新后应用身份已变化或无法核验'), findsOneWidget);
  });
}

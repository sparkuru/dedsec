import 'dart:convert';

import 'package:ctos/device_info.dart';
import 'package:ctos/device_page.dart';
import 'package:ctos/export_preview.dart';
import 'package:ctos/terminal_interaction.dart';
import 'package:ctos/ui/ctos_theme.dart';
import 'package:ctos/workbench/api.dart';
import 'package:ctos/workbench/file_store_card.dart';
import 'package:ctos/workbench/result_card.dart';
import 'package:ctos/workbench/task_history_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

const _history = <Map<String, dynamic>>[
  {
    'taskId': 'history-1',
    'script': 'network.interface_diagnose',
    'state': 'failed',
    'source': 'Android App API / NetworkInterface',
    'interfaceName': 'wlan0',
    'startedAt': 1000,
    'data': {'reason': 'Permission denied', 'interface': 'wlan0'},
  },
];
const _items = <String, ExportDataItem>{
  'device': ExportDataItem(
    key: 'device',
    title: '设备快照',
    source: 'Android API',
    capturedAt: '2026-10-05T12:00:00',
    scope: 'App',
    data: {'model': 'Test device'},
  ),
};

Widget _host(Widget page, {double scale = 1}) => MaterialApp(
  theme: CtosTheme.dark(),
  builder: (context, child) => MediaQuery(
    data: MediaQuery.of(
      context,
    ).copyWith(textScaler: TextScaler.linear(scale), disableAnimations: true),
    child: child!,
  ),
  home: page,
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('ctos/native');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  setUp(() {
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'taskHistoryList')
        return jsonEncode({'records': _history});
      return null;
    });
  });
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  testWidgets('result times are readable and original export retains precision', (
    tester,
  ) async {
    const capture = 1791172800123;
    const start = 1791172800000;
    const envelope = {
      'state': 'completed',
      'startedAt': start,
      'durationMs': 1,
      'exitCode': 0,
      'data': {
        'data': {'model': 'Field Unit'},
        'source': 'Android API',
        'capturedAt': capture,
      },
    };
    Map<String, dynamic>? exported;
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'export') {
        exported =
            jsonDecode((call.arguments as Map)['text'] as String)
                as Map<String, dynamic>;
        return false;
      }
      return null;
    });
    await tester.pumpWidget(
      _host(
        const Scaffold(
          body: SingleChildScrollView(
            child: ResultCard(
              scriptId: 'device.info',
              value: envelope,
              api: WorkbenchApi(),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(
      find.text(
        '采集时间：${DateTime.fromMillisecondsSinceEpoch(capture).toLocal()}',
      ),
      findsOneWidget,
    );
    expect(
      find.text(
        '开始于 ${DateTime.fromMillisecondsSinceEpoch(start).toLocal().toString().replaceFirst(RegExp(r'\.000$'), '')}',
      ),
      findsOneWidget,
    );
    expect(
      tester.getTopLeft(find.text('型号')).dy,
      tester.getTopLeft(find.text('Field Unit')).dy,
    );
    await tester.ensureVisible(find.text('原始 JSON 与日志'));
    await tester.tap(find.text('原始 JSON 与日志'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('保存 JSON'));
    await tester.tap(find.text('保存 JSON'));
    await tester.pumpAndSettle();
    expect(exported, envelope);
  });

  testWidgets('large landscape file confirmation shows effects before clear', (
    tester,
  ) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(900, 450);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPhysicalSize);
    var clears = 0;
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'toolFilesInfo')
        return jsonEncode({'bytes': 24117248});
      if (call.method == 'toolFilesClear') clears++;
      return null;
    });
    await tester.pumpWidget(
      _host(const Scaffold(body: FileStoreCard(api: WorkbenchApi())), scale: 2),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('工具临时文件'));
    await tester.pumpAndSettle();
    final effect = find.text('清理会删除 App 内的导入副本和工具输出；已保存到外部的文件及 HFTP 共享库不受影响。');
    final cancel = find.widgetWithText(TextButton, '返回');
    expect(
      tester.getRect(effect).bottom,
      lessThanOrEqualTo(tester.getRect(cancel).top),
    );
    expect(find.text('已使用 23.0 MiB · 总容量 128 MiB'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.tap(cancel);
    await tester.pumpAndSettle();
    expect(clears, 0);
    await tester.tap(find.text('工具临时文件'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(TextButton, '清理'));
    await tester.pumpAndSettle();
    expect(clears, 1);
  });

  testWidgets('result save cancellation and clipboard errors give feedback', (
    tester,
  ) async {
    const artifact = {'token': 'original-token', 'name': 'out.txt', 'bytes': 4};
    const envelope = {
      'state': 'completed',
      'durationMs': 1,
      'exitCode': 0,
      'data': {'artifact': artifact, 'preview': 'data'},
    };
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return false;
    });
    messenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
      if (call.method == 'Clipboard.setData') {
        throw PlatformException(code: 'clipboard-unavailable');
      }
      return null;
    });
    addTearDown(
      () => messenger.setMockMethodCallHandler(SystemChannels.platform, null),
    );
    await tester.pumpWidget(
      _host(
        const Scaffold(
          body: SingleChildScrollView(
            child: ResultCard(
              scriptId: 'tools.crypto',
              value: envelope,
              api: WorkbenchApi(),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('保存输出文件'));
    await tester.pumpAndSettle();
    expect(calls.single.method, 'toolFileExport');
    expect(calls.single.arguments, artifact);
    expect(find.text('文件选择已取消，未创建文件'), findsOneWidget);
    await tester.pump(const Duration(seconds: 5));
    await tester.pumpAndSettle();
    await tester.tap(find.text('复制预览'));
    await tester.pumpAndSettle();
    expect(find.text('复制失败，请重试'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pump(const Duration(seconds: 5));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('原始 JSON 与日志'));
    await tester.tap(find.text('原始 JSON 与日志'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('保存 JSON'));
    await tester.tap(find.text('保存 JSON'));
    await tester.pumpAndSettle();
    expect(calls.last.method, 'export');
    expect(
      jsonDecode((calls.last.arguments as Map)['text'] as String),
      envelope,
    );
    expect(find.text('文件选择已取消，未创建文件'), findsOneWidget);
  });

  testWidgets('phone device fields leave resource summary in first viewport', (
    tester,
  ) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(375, 812);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPhysicalSize);
    final snapshot = DeviceSnapshot.fromJson(
      jsonEncode({
        'system': {
          'state': 'available',
          'source': 'Android API',
          'capturedAt': 1000,
          'data': {
            'manufacturer': 'Test',
            'model': 'Device',
            'android': '15',
            'sdk': 35,
            'kernel': '6.1.1',
            'architectures': ['arm64-v8a'],
            'uptimeMs': 3600000,
          },
        },
        'memory': {
          'state': 'available',
          'source': 'ActivityManager.MemoryInfo',
          'capturedAt': 1000,
          'data': {'totalBytes': 4294967296, 'availableBytes': 2147483648},
        },
      }),
    );
    await tester.pumpWidget(
      _host(
        Scaffold(
          body: DevicePage(
            snapshot: snapshot,
            loading: false,
            error: '',
            onRefresh: () async {},
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(
      tester.getTopLeft(find.text('设备')).dy,
      tester.getTopLeft(find.text('Test Device')).dy,
    );
    expect(
      tester.getRect(find.text(deviceBytes(2147483648))).bottom,
      lessThan(812),
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'history omits zero fractions and preserves meaningful precision',
    (tester) async {
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'taskHistoryList') {
          return jsonEncode({
            'records': [
              _history.first,
              {..._history.first, 'taskId': 'history-2', 'startedAt': 1123},
            ],
          });
        }
        return null;
      });
      await tester.pumpWidget(
        _host(
          TaskHistoryPage(
            api: const WorkbenchApi(),
            onExportSelected: (_) {},
            onBrowseInterfaces: () {},
          ),
        ),
      );
      await tester.pumpAndSettle();
      final subtitles = tester
          .widgetList<CheckboxListTile>(find.byType(CheckboxListTile))
          .map((tile) => (tile.subtitle! as Text).data!)
          .toList();
      expect(subtitles.first, isNot(contains('.000')));
      expect(subtitles.last, contains('.123'));
    },
  );

  testWidgets(
    'secondary pages retain readable actions across layout and text matrix',
    (tester) async {
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPhysicalSize);
      final snapshot = DeviceSnapshot.fromJson(
        jsonEncode({
          'system': {
            'state': 'available',
            'source': 'Android API',
            'capturedAt': 1000,
            'data': {
              'model': 'Test model with a long descriptive device name',
              'manufacturer': 'Test',
              'android': '15',
              'sdk': 35,
              'kernel': 'Long kernel name 6.1.1',
              'architectures': ['arm64-v8a'],
            },
          },
          'memory': {
            'state': 'available',
            'source': 'ActivityManager.MemoryInfo',
            'capturedAt': 1000,
            'data': {'totalBytes': 4294967296, 'availableBytes': 2147483648},
          },
          'battery': {
            'state': 'permission_denied',
            'source': 'BatteryManager',
            'capturedAt': 1000,
            'reason': '当前没有访问此传感器的权限',
          },
        }),
      );
      for (final size in [
        const Size(320, 700),
        const Size(375, 812),
        const Size(844, 390),
        const Size(1200, 900),
      ]) {
        tester.view.physicalSize = size;
        for (final scale in [1.0, 2.0]) {
          await tester.pumpWidget(
            _host(
              Scaffold(
                body: DevicePage(
                  snapshot: snapshot,
                  loading: false,
                  error: '',
                  onRefresh: () async {},
                ),
              ),
              scale: scale,
            ),
          );
          await tester.pumpAndSettle();
          expect(
            tester.takeException(),
            isNull,
            reason: 'device $size / $scale',
          );
          await tester.scrollUntilVisible(
            find.text('当前没有访问此传感器的权限'),
            250,
            scrollable: find.byType(Scrollable).first,
          );
          expect(find.text('无权限'), findsOneWidget);
          expect(tester.takeException(), isNull);
          await tester.pumpWidget(
            _host(
              ExportPreviewPage(
                api: const WorkbenchApi(),
                items: _items,
                historyCandidates: _history,
              ),
              scale: scale,
            ),
          );
          await tester.pumpAndSettle();
          expect(
            tester.takeException(),
            isNull,
            reason: 'export $size / $scale',
          );
          final exportButton = find.byKey(const Key('export-save-selection'));
          expect(tester.getSize(exportButton).height, greaterThanOrEqualTo(48));
          expect(
            tester.getRect(exportButton).right,
            lessThanOrEqualTo(size.width),
          );
          await tester.pumpWidget(
            _host(
              TaskHistoryPage(
                api: const WorkbenchApi(),
                onExportSelected: (_) {},
                onBrowseInterfaces: () {},
              ),
              scale: scale,
            ),
          );
          await tester.pumpAndSettle();
          await tester.scrollUntilVisible(
            find.byType(Checkbox),
            100,
            scrollable: find.byType(Scrollable).first,
          );
          await tester.pumpAndSettle();
          await tester.tap(find.byType(Checkbox));
          await tester.pumpAndSettle();
          expect(
            tester.takeException(),
            isNull,
            reason: 'history $size / $scale',
          );
          final historyButton = find.byKey(
            const Key('history-export-selection'),
          );
          expect(
            tester.getSize(historyButton).height,
            greaterThanOrEqualTo(48),
          );
          expect(
            tester.getRect(historyButton).right,
            lessThanOrEqualTo(size.width),
          );
          await tester.tap(find.byTooltip('清除历史'));
          await tester.pumpAndSettle();
          expect(
            tester.takeException(),
            isNull,
            reason: 'history confirmation $size / $scale',
          );
          final cancel = find.widgetWithText(TextButton, '取消');
          await tester.ensureVisible(cancel);
          await tester.tap(cancel);
          await tester.pumpAndSettle();
          await tester.pumpWidget(
            _host(
              const TerminalOutputPage(output: 'echo hello\nhello world'),
              scale: scale,
            ),
          );
          await tester.pumpAndSettle();
          await tester.tap(find.byTooltip('搜索输出'));
          await tester.pumpAndSettle();
          await tester.enterText(
            find.byKey(const Key('terminal-output-search')),
            'hello',
          );
          await tester.pumpAndSettle();
          expect(find.text('找到 2 处'), findsOneWidget);
          expect(
            tester.takeException(),
            isNull,
            reason: 'output $size / $scale',
          );
          await tester.pumpWidget(const SizedBox.shrink());
        }
      }
    },
  );

  testWidgets(
    'export selection sends original chosen data and distinguishes picker cancel',
    (tester) async {
      Map<String, dynamic>? exported;
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'export') {
          exported =
              jsonDecode((call.arguments as Map)['text'] as String)
                  as Map<String, dynamic>;
          return false;
        }
        return null;
      });
      await tester.pumpWidget(
        _host(
          const ExportPreviewPage(
            api: WorkbenchApi(),
            items: _items,
            historyCandidates: _history,
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byType(CheckboxListTile).first);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('export-save-selection')));
      await tester.pumpAndSettle();
      expect(exported!['device'], _items['device']!.toJson());
      expect(exported!['taskHistory'], _history);
      expect(find.text('文件选择已取消，未创建文件'), findsOneWidget);
      expect(find.byType(ExportPreviewPage), findsOneWidget);
      expect(
        tester
            .widget<FilledButton>(
              find.byKey(const Key('export-save-selection')),
            )
            .onPressed,
        isNotNull,
      );
    },
  );

  testWidgets(
    'history export preserves exact record and clear confirmation controls deletion',
    (tester) async {
      var clears = 0;
      List<Map<String, dynamic>>? selection;
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'taskHistoryList')
          return jsonEncode({'records': _history});
        if (call.method == 'taskHistoryClear') clears++;
        return null;
      });
      await tester.pumpWidget(
        _host(
          TaskHistoryPage(
            api: const WorkbenchApi(),
            onExportSelected: (value) => selection = value,
            onBrowseInterfaces: () {},
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byType(CheckboxListTile));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('history-export-selection')));
      expect(selection, _history);
      await tester.tap(find.byTooltip('清除历史'));
      await tester.pumpAndSettle();
      expect(find.text('将永久删除 1 条本机只读任务记录。'), findsOneWidget);
      await tester.tap(find.widgetWithText(TextButton, '取消'));
      await tester.pumpAndSettle();
      expect(clears, 0);
      await tester.tap(find.byTooltip('清除历史'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, '清除历史'));
      await tester.pumpAndSettle();
      expect(clears, 1);
      expect(find.text('还没有只读任务记录'), findsOneWidget);
      expect(find.text('预览并导出 1 项'), findsNothing);
    },
  );
}

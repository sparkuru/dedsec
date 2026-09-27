import 'dart:async';
import 'dart:convert';
import 'package:ctos/workbench/api.dart';
import 'package:ctos/workbench/hftp_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

class LogHftpApi extends WorkbenchApi {
  Map<String, dynamic> status = {'state': 'stopped', 'logs': <String>[]};
  Completer<Map<String, dynamic>>? pollResponse, clearResponse, startResponse;
  Completer<void>? stopResponse;
  Object? clearFailure;
  int starts = 0, stops = 0, logClears = 0, shareClears = 0;

  @override
  Future<HftpSettings> hftpConfig() async => const HftpSettings();

  @override
  Future<Map<String, dynamic>> hftpStatus() async =>
      pollResponse == null ? status : pollResponse!.future;

  @override
  Future<Map<String, dynamic>> hftpStart(
    String host,
    String port, {
    required String maxUploadMiB,
    required String treeUri,
    bool rootRelay = false,
  }) async {
    starts++;
    if (startResponse != null) return startResponse!.future;
    return status = {
      'state': 'running',
      'logs': ['12:00:00 listening 0.0.0.0:7888'],
    };
  }

  @override
  Future<void> hftpStop() async {
    stops++;
    if (stopResponse != null) {
      status = {...status, 'state': 'stopping'};
      await stopResponse!.future;
      return;
    }
    status = {
      ...status,
      'state': 'stopped',
      'logs': [...hftpLogLines(status), '12:00:03 stopped by user'],
    };
  }

  @override
  Future<Map<String, dynamic>> hftpClearLogs() async {
    logClears++;
    if (clearFailure != null) throw clearFailure!;
    if (clearResponse != null) return clearResponse!.future;
    return status = {...status, 'logs': <String>[]};
  }

  @override
  Future<void> clearShare() async {
    shareClears++;
  }
}

Finder get logText => find.byKey(const PageStorageKey('hftp-log-text'));
Finder button(String text) => find.ancestor(
  of: find.text(text),
  matching: find.byWidgetPredicate((widget) => widget is ButtonStyleButton),
);

Future<void> show(WidgetTester tester, Finder finder) async {
  final scrollable = find.byType(Scrollable).first;
  final position = tester.state<ScrollableState>(scrollable).position;
  if (finder.evaluate().isEmpty) {
    position.jumpTo(0);
    await tester.pump();
  }
  for (var step = 0; step < 40 && finder.evaluate().isEmpty; step++) {
    position.jumpTo(
      (position.pixels + 150).clamp(0, position.maxScrollExtent).toDouble(),
    );
    await tester.pump();
  }
  await tester.ensureVisible(finder);
  await tester.pump(const Duration(milliseconds: 300));
}

Future<void> tap(WidgetTester tester, String text) async {
  await show(tester, find.text(text));
  await tester.tap(find.text(text));
  await tester.pump();
}

Future<void> mount(WidgetTester tester, LogHftpApi api) async {
  await tester.pumpWidget(MaterialApp(home: HftpPage(api: api)));
  await tester.pumpAndSettle();
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  test(
    'log projection accepts only string lines and tolerates old snapshots',
    () {
      expect(hftpLogLines({'state': 'stopped'}), isEmpty);
      expect(hftpLogLines({'logs': 'bad shape'}), isEmpty);
      expect(
        hftpLogLines({
          'logs': ['one', null, 2, 'two'],
        }),
        ['one', 'two'],
      );
    },
  );

  test(
    'clear-log channel returns a snapshot without service or directory calls',
    () async {
      const channel = MethodChannel('ctos/hftp-log-test');
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return jsonEncode({'state': 'running', 'logs': <String>[]});
      });
      addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
      final snapshot = await const WorkbenchApi(
        channel: channel,
      ).hftpClearLogs();
      expect(snapshot['state'], 'running');
      expect(hftpLogLines(snapshot), isEmpty);
      expect(calls.map((call) => call.method), ['hftpClearLogs']);
      expect(calls.single.arguments, isNull);
    },
  );

  test(
    'start channel sends an explicit Root boolean and exact directory fields',
    () async {
      const channel = MethodChannel('ctos/hftp-root-config-test');
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return jsonEncode({
          'state': 'starting',
          'rootRelay': call.arguments['rootRelay'],
        });
      });
      addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
      const api = WorkbenchApi(channel: channel);
      for (final rootRelay in [false, true]) {
        final snapshot = await api.hftpStart(
          '0.0.0.0',
          '9001',
          maxUploadMiB: '64',
          treeUri: 'content://tree/fixture',
          rootRelay: rootRelay,
        );
        expect(snapshot['rootRelay'], rootRelay);
        expect(calls.last.method, 'hftpStart');
        expect(calls.last.arguments, {
          'host': '0.0.0.0',
          'port': '9001',
          'maxUploadMiB': '64',
          'treeUri': 'content://tree/fixture',
          'rootRelay': rootRelay,
        });
      }
    },
  );

  testWidgets('empty logs explain requests and can collapse and expand', (
    tester,
  ) async {
    final api = LogHftpApi();
    await mount(tester, api);
    expect(api.starts, 0);
    await show(tester, find.text('服务日志'));
    expect(find.textContaining('收到的请求会显示在这里'), findsOneWidget);
    expect(tester.widget<ButtonStyleButton>(button('复制日志')).onPressed, isNull);
    expect(tester.widget<ButtonStyleButton>(button('清空日志')).onPressed, isNull);
    await tap(tester, '服务日志');
    await tester.pumpAndSettle();
    expect(find.textContaining('收到的请求会显示在这里'), findsNothing);
    await tap(tester, '服务日志');
    await tester.pumpAndSettle();
    expect(find.textContaining('收到的请求会显示在这里'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets(
    'scrolling logs preserves a safe card state across page rebuilds',
    (tester) async {
      final bucket = PageStorageBucket();
      final api = LogHftpApi()
        ..status = {
          'state': 'failed',
          'reason': 'Root relay startup failed: listener_bind (errno 98)',
          'logs': List.generate(
            40,
            (index) => '12:00:$index public request 200',
          ),
        };
      Widget app(bool visible) => MaterialApp(
        home: PageStorage(
          bucket: bucket,
          child: visible ? HftpPage(api: api) : const SizedBox(),
        ),
      );
      await tester.pumpWidget(app(true));
      await tester.pumpAndSettle();
      await show(tester, logText);
      final logScrollable = find.byWidgetPredicate(
        (widget) =>
            widget is Scrollable && widget.axisDirection == AxisDirection.up,
      );
      final position = tester.state<ScrollableState>(logScrollable).position;
      expect(position.maxScrollExtent, greaterThan(100));
      position.jumpTo(100);
      await tester.pump();
      await tester.pumpWidget(app(false));
      await tester.pump();
      await tester.pumpWidget(app(true));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      await show(tester, find.text('服务日志'));
      expect(find.text('服务日志'), findsOneWidget);
      await show(tester, find.text('服务配置'));
      expect(find.text('服务配置'), findsOneWidget);
      await tap(tester, '服务日志');
      await tester.pumpAndSettle();
      final tile = find.byKey(const PageStorageKey('hftp-service-logs'));
      expect(find.descendant(of: tile, matching: logText), findsNothing);
      await tester.pumpWidget(app(false));
      await tester.pump();
      await tester.pumpWidget(app(true));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.descendant(of: tile, matching: logText), findsNothing);
      await tap(tester, '服务日志');
      await tester.pumpAndSettle();
      expect(find.descendant(of: tile, matching: logText), findsOneWidget);
      expect(api.starts, 0);
      expect(api.stops, 0);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets(
    'collapsed logs can clear and refill without corrupting text state',
    (tester) async {
      final api = LogHftpApi()
        ..status = {
          'state': 'running',
          'logs': ['first public request'],
        };
      await mount(tester, api);
      await tap(tester, '服务日志');
      await tester.pumpAndSettle();
      api.status = {'state': 'running', 'logs': <String>[]};
      await tester.pump(const Duration(seconds: 2));
      await tester.pumpAndSettle();
      api.status = {
        'state': 'running',
        'logs': ['second public request'],
      };
      await tester.pump(const Duration(seconds: 2));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      await tap(tester, '服务日志');
      await tester.pumpAndSettle();
      await show(tester, logText);
      expect(
        tester.widget<SelectableText>(logText).data,
        'second public request',
      );
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets(
    'stop request locks operations until owned resources have stopped',
    (tester) async {
      final api = LogHftpApi()
        ..status = {
          'state': 'running',
          'rootRelay': true,
          'logs': ['public request'],
        }
        ..stopResponse = Completer<void>();
      await mount(tester, api);
      await tap(tester, '停止');
      expect(api.stops, 1);
      expect(find.text('启动服务'), findsNothing);
      await show(tester, button('停止中…'));
      expect(
        tester.widget<ButtonStyleButton>(button('停止中…')).onPressed,
        isNull,
      );
      api.stopResponse!.complete();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
      expect(
        tester.widget<ButtonStyleButton>(button('停止中…')).onPressed,
        isNull,
      );
      await show(tester, find.byType(CheckboxListTile));
      expect(
        tester
            .widget<CheckboxListTile>(find.byType(CheckboxListTile))
            .onChanged,
        isNull,
      );
      await show(
        tester,
        find.byWidgetPredicate(
          (widget) =>
              widget is TextField && widget.decoration?.labelText == '端口',
        ),
      );
      expect(
        tester
            .widget<TextField>(
              find.byWidgetPredicate(
                (widget) =>
                    widget is TextField && widget.decoration?.labelText == '端口',
              ),
            )
            .enabled,
        false,
      );
      await show(tester, button('复制日志'));
      expect(
        tester.widget<ButtonStyleButton>(button('复制日志')).onPressed,
        isNotNull,
      );
      await tap(tester, '清空日志');
      expect(api.logClears, 1);
      expect(api.starts, 0);
      expect(api.stops, 1);
      await show(tester, button('停止中…'));
      expect(
        tester.widget<ButtonStyleButton>(button('停止中…')).onPressed,
        isNull,
      );
      api.status = {
        'state': 'stopped',
        'reason': '',
        'logs': ['stopped by user'],
      };
      await tester.pump(const Duration(seconds: 2));
      await tester.pumpAndSettle();
      await show(tester, find.text('启动服务'));
      expect(
        tester.widget<ButtonStyleButton>(button('启动服务')).onPressed,
        isNotNull,
      );
      expect(find.text('服务异常'), findsNothing);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets(
    'opening a stopping session does not offer start or duplicate stop',
    (tester) async {
      final api = LogHftpApi()
        ..status = {
          'state': 'stopping',
          'logs': ['releasing network relay'],
        };
      await tester.pumpWidget(MaterialApp(home: HftpPage(api: api)));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
      expect(
        tester.widget<ButtonStyleButton>(button('停止中…')).onPressed,
        isNull,
      );
      expect(find.text('启动服务'), findsNothing);
      expect(find.text('停止'), findsNothing);
      expect(api.starts, 0);
      expect(api.stops, 0);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets('failed service remains locked until resource cleanup finishes', (
    tester,
  ) async {
    const reason = 'Root relay startup failed: listener_bind (errno 98)';
    final api = LogHftpApi()
      ..status = {
        'state': 'failed',
        'closing': true,
        'reason': reason,
        'logs': ['listener bind failed; releasing resources'],
      };
    await tester.pumpWidget(MaterialApp(home: HftpPage(api: api)));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.text(reason), findsOneWidget);
    expect(find.text('服务异常'), findsOneWidget);
    expect(tester.widget<ButtonStyleButton>(button('结束中…')).onPressed, isNull);
    await show(tester, find.byType(CheckboxListTile));
    expect(
      tester.widget<CheckboxListTile>(find.byType(CheckboxListTile)).onChanged,
      isNull,
    );
    await show(tester, button('选择本机目录'));
    expect(
      tester.widget<ButtonStyleButton>(button('选择本机目录')).onPressed,
      isNull,
    );
    await show(tester, button('复制日志'));
    expect(
      tester.widget<ButtonStyleButton>(button('复制日志')).onPressed,
      isNotNull,
    );
    await tap(tester, '清空日志');
    expect(api.logClears, 1);
    expect(api.starts, 0);
    expect(api.stops, 0);
    api.status = {...api.status, 'closing': false};
    await tester.pump(const Duration(seconds: 2));
    await tester.pumpAndSettle();
    await show(tester, button('启动服务'));
    expect(
      tester.widget<ButtonStyleButton>(button('启动服务')).onPressed,
      isNotNull,
    );
    await show(tester, find.text(reason));
    expect(find.text(reason), findsOneWidget);
    await show(tester, find.byType(CheckboxListTile));
    expect(
      tester.widget<CheckboxListTile>(find.byType(CheckboxListTile)).onChanged,
      isNotNull,
    );
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets(
    'poll adds selectable logs; stop and page return retain snapshot',
    (tester) async {
      final api = LogHftpApi();
      await mount(tester, api);
      await tap(tester, '启动服务');
      await tester.pumpAndSettle();
      api.status = {
        ...api.status,
        'logs': [...hftpLogLines(api.status), '12:00:01 192.0.2.2 GET / 200'],
      };
      await tester.pump(const Duration(seconds: 2));
      await show(tester, logText);
      expect(
        tester.widget<SelectableText>(logText).data,
        '12:00:00 listening 0.0.0.0:7888\n12:00:01 192.0.2.2 GET / 200',
      );
      await tap(tester, '停止');
      await tester.pumpAndSettle();
      await show(tester, logText);
      expect(
        tester.widget<SelectableText>(logText).data,
        contains('stopped by user'),
      );
      await tester.pumpWidget(const SizedBox());
      expect(api.stops, 1);
      await mount(tester, api);
      await show(tester, logText);
      expect(
        tester.widget<SelectableText>(logText).data,
        contains('GET / 200'),
      );
      expect(
        tester.widget<SelectableText>(logText).data,
        contains('stopped by user'),
      );
      await tester.pumpWidget(const SizedBox());
      expect(api.starts, 1);
      expect(api.stops, 1);
    },
  );

  testWidgets('copy sends exact lines; clear while running changes only logs', (
    tester,
  ) async {
    final api = LogHftpApi()
      ..status = {
        'state': 'running',
        'logs': [
          '12:00:01 GET /public%20file.txt 200',
          '12:00:02 PUT /new.bin 201',
        ],
      };
    String? copied;
    messenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
      if (call.method == 'Clipboard.setData') {
        copied = (call.arguments as Map)['text'] as String;
      }
      return null;
    });
    addTearDown(
      () => messenger.setMockMethodCallHandler(SystemChannels.platform, null),
    );
    await mount(tester, api);
    await tap(tester, '复制日志');
    await tester.pumpAndSettle();
    expect(
      copied,
      '12:00:01 GET /public%20file.txt 200\n12:00:02 PUT /new.bin 201',
    );
    await tap(tester, '清空日志');
    await tester.pumpAndSettle();
    expect(logText, findsNothing);
    await show(tester, find.text('停止'));
    expect(find.text('停止'), findsOneWidget);
    expect(api.logClears, 1);
    expect(api.starts, 0);
    expect(api.stops, 0);
    expect(api.shareClears, 0);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets(
    'late poll cannot refill cleared logs and duplicate clear is locked',
    (tester) async {
      final api = LogHftpApi()
        ..status = {
          'state': 'running',
          'logs': ['old request'],
        };
      await mount(tester, api);
      final stale = Completer<Map<String, dynamic>>();
      api.pollResponse = stale;
      await tester.pump(const Duration(seconds: 2));
      api.clearResponse = Completer<Map<String, dynamic>>();
      await tap(tester, '清空日志');
      expect(
        tester.widget<ButtonStyleButton>(button('清空中…')).onPressed,
        isNull,
      );
      expect(
        tester.widget<ButtonStyleButton>(button('复制日志')).onPressed,
        isNotNull,
      );
      api.status = {'state': 'running', 'logs': <String>[]};
      api.clearResponse!.complete(api.status);
      await tester.pumpAndSettle();
      stale.complete({
        'state': 'running',
        'logs': ['old request', 'stale request'],
      });
      api.pollResponse = null;
      await tester.pumpAndSettle();
      expect(logText, findsNothing);
      expect(api.logClears, 1);
      api.status = {
        'state': 'running',
        'logs': ['new request'],
      };
      await tester.pump(const Duration(seconds: 2));
      await show(tester, logText);
      expect(tester.widget<SelectableText>(logText).data, 'new request');
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets(
    'clear during start protects logs without discarding start state',
    (tester) async {
      final api = LogHftpApi()
        ..status = {
          'state': 'stopped',
          'logs': ['previous session'],
        }
        ..startResponse = Completer<Map<String, dynamic>>();
      await mount(tester, api);
      await tap(tester, '启动服务');
      await tap(tester, '清空日志');
      expect(api.logClears, 1);
      api.startResponse!.complete({
        'state': 'running',
        'logs': ['previous session'],
      });
      await tester.pumpAndSettle();
      expect(logText, findsNothing);
      await show(tester, find.text('停止'));
      expect(find.text('停止'), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets('old clear response cannot replace a newer service session', (
    tester,
  ) async {
    final api = LogHftpApi()
      ..status = {
        'state': 'stopped',
        'logs': ['previous session'],
      }
      ..clearResponse = Completer<Map<String, dynamic>>();
    await mount(tester, api);
    await tap(tester, '清空日志');
    await tap(tester, '启动服务');
    await tester.pumpAndSettle();
    api.clearResponse!.complete({'state': 'stopped', 'logs': <String>[]});
    await tester.pumpAndSettle();
    await show(tester, logText);
    expect(tester.widget<SelectableText>(logText).data, contains('listening'));
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('clear and clipboard errors retain logs and allow recovery', (
    tester,
  ) async {
    final api = LogHftpApi()
      ..status = {
        'state': 'running',
        'logs': ['keep this request'],
      }
      ..clearFailure = PlatformException(
        code: 'LOGS',
        message: 'Cannot clear logs',
      );
    messenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
      if (call.method == 'Clipboard.setData') {
        throw PlatformException(code: 'COPY', message: 'Cannot copy logs');
      }
      return null;
    });
    addTearDown(
      () => messenger.setMockMethodCallHandler(SystemChannels.platform, null),
    );
    await mount(tester, api);
    await tap(tester, '清空日志');
    await tester.pumpAndSettle();
    await show(tester, find.textContaining('Cannot clear logs'));
    expect(find.textContaining('Cannot clear logs'), findsOneWidget);
    await show(tester, logText);
    expect(tester.widget<SelectableText>(logText).data, 'keep this request');
    await tap(tester, '复制日志');
    await tester.pumpAndSettle();
    await show(tester, find.textContaining('Cannot copy logs'));
    expect(find.textContaining('Cannot copy logs'), findsOneWidget);
    expect(find.textContaining('Cannot clear logs'), findsNothing);
    api.clearFailure = null;
    await tap(tester, '清空日志');
    await tester.pumpAndSettle();
    expect(logText, findsNothing);
    expect(find.textContaining('Cannot copy logs'), findsNothing);
    await tester.pumpWidget(const SizedBox());
  });

  for (final size in [const Size(375, 812), const Size(812, 375)]) {
    testWidgets('long logs and full-width actions fit $size at 2x text', (
      tester,
    ) async {
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final api = LogHftpApi()
        ..status = {
          'state': 'running',
          'logs': List.generate(
            200,
            (index) => '$index GET /${'public_' * 40} 200',
          ),
        };
      await tester.pumpWidget(
        MaterialApp(
          theme: ThemeData.dark(useMaterial3: true),
          home: MediaQuery(
            data: MediaQueryData(
              size: size,
              textScaler: TextScaler.linear(2),
              disableAnimations: true,
            ),
            child: HftpPage(api: api),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await show(tester, button('复制日志'));
      final copyBounds = tester.getRect(button('复制日志'));
      await show(tester, button('清空日志'));
      final clearBounds = tester.getRect(button('清空日志'));
      expect(copyBounds.width, closeTo(clearBounds.width, 0.01));
      expect(copyBounds.width, greaterThan(size.width - 100));
      expect(copyBounds.height, greaterThanOrEqualTo(48));
      expect(clearBounds.height, greaterThanOrEqualTo(48));
      await show(tester, find.text('停止'));
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
    });
  }
}

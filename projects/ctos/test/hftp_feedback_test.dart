import 'dart:async';
import 'package:ctos/workbench/api.dart';
import 'package:ctos/workbench/hftp_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

class FakeHftpApi extends WorkbenchApi {
  HftpSettings config = const HftpSettings();
  HftpSettings? picked;
  Map<String, dynamic> status = {'state': 'stopped'};
  Map<String, Object>? submitted;
  int stops = 0, imports = 0, clears = 0;
  Completer<HftpSettings?>? selection;
  Completer<Map<String, dynamic>>? pollingResponse;

  @override
  Future<HftpSettings> hftpConfig() async => config;
  @override
  Future<Map<String, dynamic>> hftpStatus() async =>
      pollingResponse == null ? status : pollingResponse!.future;
  @override
  Future<HftpSettings?> hftpPickDirectory() async =>
      selection == null ? picked : selection!.future;
  @override
  Future<HftpSettings> hftpUseDefaultDirectory() async =>
      config = const HftpSettings();
  @override
  Future<Map<String, dynamic>> hftpStart(
    String host,
    String port, {
    required String maxUploadMiB,
    required String treeUri,
    bool rootRelay = false,
  }) async {
    submitted = {
      'host': host,
      'port': port,
      'maxUploadMiB': maxUploadMiB,
      'treeUri': treeUri,
      'rootRelay': rootRelay,
    };
    return status = {'state': 'running', 'rootRelay': rootRelay};
  }

  @override
  Future<void> hftpStop() async {
    stops++;
    status = {'state': 'stopped'};
  }

  @override
  Future<Map<String, dynamic>?> pickFile({bool share = false}) async {
    imports++;
    return {'name': 'fixture.txt'};
  }

  @override
  Future<void> clearShare() async {
    clears++;
  }
}

Finder field(String label) => find.byWidgetPredicate(
  (widget) => widget is TextField && widget.decoration?.labelText == label,
);
Future<void> reveal(WidgetTester tester, Finder target) async {
  if (target.evaluate().isEmpty) {
    tester
        .state<ScrollableState>(find.byType(Scrollable).first)
        .position
        .jumpTo(0);
    await tester.pump();
  }
  await tester.scrollUntilVisible(
    target,
    200,
    scrollable: find.byType(Scrollable).first,
  );
  await tester.ensureVisible(target);
  await tester.pumpAndSettle();
}

Future<void> enter(WidgetTester tester, String label, String value) async {
  await reveal(tester, field(label));
  await tester.enterText(field(label), value);
}

Future<void> tap(WidgetTester tester, String label) async {
  await reveal(tester, find.text(label));
  await tester.tap(find.text(label));
  await tester.pump();
}

Future<void> mount(WidgetTester tester, FakeHftpApi api) async {
  await tester.pumpWidget(MaterialApp(home: HftpPage(api: api)));
  await tester.pumpAndSettle();
}

void main() {
  test('Root relay configuration requires an explicit boolean true', () {
    expect(const HftpSettings().rootRelay, false);
    expect(HftpSettings.fromJson({}).rootRelay, false);
    expect(HftpSettings.fromJson({'rootRelay': 'true'}).rootRelay, false);
    expect(HftpSettings.fromJson({'rootRelay': 1}).rootRelay, false);
    expect(HftpSettings.fromJson({'rootRelay': true}).rootRelay, true);
  });

  testWidgets(
    'restored Root choice retains directory drafts and exact payload',
    (tester) async {
      final api = FakeHftpApi()
        ..config = const HftpSettings(rootRelay: true, maxUploadMiB: '96');
      await mount(tester, api);
      expect(api.submitted, isNull);
      await reveal(tester, find.byType(CheckboxListTile));
      expect(
        tester.widget<CheckboxListTile>(find.byType(CheckboxListTile)).value,
        true,
      );
      await enter(tester, '端口', '9001');
      await enter(tester, '最大上传大小（MiB）', '64');
      api.picked = const HftpSettings(
        directoryName: 'Root relay fixture',
        treeUri: 'content://tree/relay-fixture',
      );
      await tap(tester, '选择本机目录');
      await tester.pumpAndSettle();
      await reveal(tester, find.byType(CheckboxListTile));
      expect(
        tester.widget<CheckboxListTile>(find.byType(CheckboxListTile)).value,
        true,
      );
      api.picked = null;
      await tap(tester, '选择本机目录');
      await tester.pumpAndSettle();
      await tap(tester, '启动服务');
      expect(api.submitted, {
        'host': '0.0.0.0',
        'port': '9001',
        'maxUploadMiB': '64',
        'treeUri': 'content://tree/relay-fixture',
        'rootRelay': true,
      });
      await tester.pumpAndSettle();
      await reveal(tester, find.text('访问方式：Root 局域网中继'));
      expect(find.text('访问方式：Root 局域网中继'), findsOneWidget);
      await reveal(tester, find.byType(CheckboxListTile));
      expect(
        tester
            .widget<CheckboxListTile>(find.byType(CheckboxListTile))
            .onChanged,
        isNull,
      );
      await tester.pumpWidget(const SizedBox());
      expect(api.stops, 0);
    },
  );

  testWidgets(
    'switching to loopback clears Root choice and never re-enables it',
    (tester) async {
      final api = FakeHftpApi();
      await mount(tester, api);
      await tap(tester, 'Root 局域网中继');
      expect(
        tester.widget<CheckboxListTile>(find.byType(CheckboxListTile)).value,
        true,
      );
      Future<void> selectHost(String label) async {
        await reveal(tester, find.byType(DropdownButtonFormField<String>));
        await tester.tap(find.byType(DropdownButtonFormField<String>));
        await tester.pumpAndSettle();
        await tester.tap(find.text(label).last);
        await tester.pumpAndSettle();
      }

      await selectHost('仅本机');
      expect(find.byType(CheckboxListTile), findsNothing);
      expect(find.text('仅本机模式无需 Root 中继。'), findsOneWidget);
      await tap(tester, '启动服务');
      expect(api.submitted!['host'], '127.0.0.1');
      expect(api.submitted!['rootRelay'], false);
      await tester.pumpAndSettle();
      await reveal(tester, find.text('访问方式：App 服务'));
      expect(find.text('访问方式：App 服务'), findsOneWidget);
      await tap(tester, '停止');
      await tester.pumpAndSettle();
      await selectHost('局域网');
      expect(
        tester.widget<CheckboxListTile>(find.byType(CheckboxListTile)).value,
        false,
      );
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets(
    'loopback restored config cannot activate Root and busy locks choice',
    (tester) async {
      final api = FakeHftpApi()
        ..config = const HftpSettings(host: '127.0.0.1', rootRelay: true);
      await mount(tester, api);
      await tap(tester, '启动服务');
      expect(api.submitted!['rootRelay'], false);
      await tap(tester, '停止');
      await tester.pumpAndSettle();
      await reveal(tester, find.byType(DropdownButtonFormField<String>));
      await tester.tap(find.byType(DropdownButtonFormField<String>));
      await tester.pumpAndSettle();
      await tester.tap(find.text('局域网').last);
      await tester.pumpAndSettle();
      await tap(tester, 'Root 局域网中继');
      api.selection = Completer<HftpSettings?>();
      await tap(tester, '选择本机目录');
      final scrollable = find.byType(Scrollable).first;
      tester.state<ScrollableState>(scrollable).position.jumpTo(0);
      await tester.pump();
      await tester.scrollUntilVisible(
        find.byType(CheckboxListTile),
        150,
        scrollable: scrollable,
      );
      await tester.pump();
      final checkbox = tester.widget<CheckboxListTile>(
        find.byType(CheckboxListTile),
      );
      expect(checkbox.value, true);
      expect(checkbox.onChanged, isNull);
      api.selection!.complete(null);
      await tester.pumpAndSettle();
      await reveal(tester, find.byType(CheckboxListTile));
      expect(
        tester.widget<CheckboxListTile>(find.byType(CheckboxListTile)).value,
        true,
      );
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets('late status poll cannot undo an explicit start', (tester) async {
    final api = FakeHftpApi();
    await mount(tester, api);
    final previous = Completer<Map<String, dynamic>>();
    api.pollingResponse = previous;
    await tester.pump(const Duration(seconds: 2));
    await tap(tester, '启动服务');
    previous.complete({'state': 'stopped'});
    api.pollingResponse = null;
    await tester.pumpAndSettle();
    expect(find.text('停止'), findsOneWidget);
    expect(find.text('启动服务'), findsNothing);
    await tester.pumpWidget(const SizedBox());
  });
  testWidgets(
    'LAN and 7888 defaults submit exact configured upload limit without credentials',
    (tester) async {
      final api = FakeHftpApi();
      await mount(tester, api);
      expect(api.submitted, isNull);
      expect(find.text('用户名：ctos'), findsNothing);
      expect(find.byTooltip('复制密码'), findsNothing);
      await reveal(tester, field('端口'));
      expect(tester.widget<TextField>(field('端口')).controller!.text, '7888');
      await enter(tester, '最大上传大小（MiB）', '256');
      await tap(tester, '启动服务');
      expect(api.submitted, {
        'host': '0.0.0.0',
        'port': '7888',
        'maxUploadMiB': '256',
        'treeUri': '',
        'rootRelay': false,
      });
      await tester.pump();
      expect(find.text('停止'), findsOneWidget);
      await reveal(tester, field('端口'));
      expect(tester.widget<TextField>(field('端口')).enabled, false);
      await tap(tester, '停止');
      await tester.pumpAndSettle();
      expect(api.stops, 1);
      await tester.pumpWidget(const SizedBox());
      expect(api.stops, 1);
    },
  );

  testWidgets('upload limits reject invalid bounds before native start', (
    tester,
  ) async {
    final api = FakeHftpApi();
    await mount(tester, api);
    for (final value in ['0', '1025', '1.5', 'invalid']) {
      await enter(tester, '最大上传大小（MiB）', value);
      await tap(tester, '启动服务');
      await tester.pumpAndSettle();
      expect(api.submitted, isNull);
      await reveal(tester, find.textContaining('上传大小范围为 1–1024 MiB'));
      expect(find.textContaining('上传大小范围为 1–1024 MiB'), findsOneWidget);
    }
    await enter(tester, '最大上传大小（MiB）', '1024');
    await tap(tester, '启动服务');
    expect(api.submitted!['maxUploadMiB'], '1024');
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets(
    'directory cancellation retains drafts; selected user directory has no clear/import action',
    (tester) async {
      final api = FakeHftpApi();
      await mount(tester, api);
      await enter(tester, '端口', '9000');
      await enter(tester, '最大上传大小（MiB）', '64');
      await tap(tester, '选择本机目录');
      await tester.pumpAndSettle();
      expect(find.text('默认共享目录'), findsOneWidget);
      expect(tester.widget<TextField>(field('端口')).controller!.text, '9000');
      api.picked = const HftpSettings(
        directoryName: 'Download / ctOS-test',
        treeUri: 'content://tree/test',
      );
      await tap(tester, '选择本机目录');
      await tester.pumpAndSettle();
      expect(find.text('Download / ctOS-test'), findsOneWidget);
      expect(find.text('清空共享库'), findsNothing);
      expect(find.text('导入共享文件'), findsNothing);
      await tap(tester, '启动服务');
      expect(api.submitted, {
        'host': '0.0.0.0',
        'port': '9000',
        'maxUploadMiB': '64',
        'treeUri': 'content://tree/test',
        'rootRelay': false,
      });
      await tester.pump();
      await tap(tester, '停止');
      await tester.pumpAndSettle();
      await tap(tester, '使用默认共享目录');
      await tester.pumpAndSettle();
      expect(find.text('清空共享库'), findsOneWidget);
      expect(find.text('导入共享文件'), findsOneWidget);
      expect(api.clears, 0);
      expect(api.imports, 0);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets(
    'saved directory and limits restore; picker busy state prevents start',
    (tester) async {
      final api = FakeHftpApi()
        ..config = const HftpSettings(
          host: '127.0.0.1',
          port: '9090',
          maxUploadMiB: '96',
          treeUri: 'content://tree/saved',
          directoryName: 'Saved directory',
        );
      await mount(tester, api);
      await reveal(tester, find.text('Saved directory'));
      expect(find.text('Saved directory'), findsOneWidget);
      await reveal(tester, field('端口'));
      expect(tester.widget<TextField>(field('端口')).controller!.text, '9090');
      await reveal(tester, field('最大上传大小（MiB）'));
      expect(
        tester.widget<TextField>(field('最大上传大小（MiB）')).controller!.text,
        '96',
      );
      api.selection = Completer<HftpSettings?>();
      await tap(tester, '选择本机目录');
      tester
          .state<ScrollableState>(find.byType(Scrollable).first)
          .position
          .jumpTo(0);
      await tester.pump();
      expect(
        tester
            .widget<FilledButton>(
              find.byWidgetPredicate((widget) => widget is FilledButton),
            )
            .onPressed,
        isNull,
      );
      expect(api.submitted, isNull);
      api.selection!.complete(null);
      await tester.pumpAndSettle();
      await reveal(tester, find.text('Saved directory'));
      expect(find.text('Saved directory'), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
    },
  );
}

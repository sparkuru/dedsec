import 'package:ctos/ui/ctos_theme.dart';
import 'package:ctos/workbench.dart';
import 'package:ctos/workbench/hftp_page.dart';
import 'package:ctos/workbench/result_card.dart';
import 'package:ctos/workbench/parameter_field.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'hftp_log_test.dart' show LogHftpApi;
import 'workbench_usability_test.dart' as fixture;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  WidgetController.hitTestWarningShouldBeFatal = true;

  for (final size in [
    const Size(320, 700),
    const Size(375, 812),
    const Size(812, 375),
    const Size(1200, 900),
  ]) {
    for (final scale in [1.0, 2.0]) {
      testWidgets('workbench decisions remain reachable $size / $scale', (
        tester,
      ) async {
        tester.view.physicalSize = size;
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        Widget app(Widget child) => MaterialApp(
          theme: CtosTheme.dark(),
          home: MediaQuery(
            data: MediaQueryData(
              size: size,
              textScaler: TextScaler.linear(scale),
              disableAnimations: true,
            ),
            child: child,
          ),
        );
        for (final script in [
          fixture.password(),
          fixture.encoder(),
          fixture.crypto(),
          fixture.script('text.digest', fields: [fixture.field('text')]),
          fixture.script('tools.ip', fields: [fixture.field('target')]),
          fixture.script('python.selftest'),
        ]) {
          await tester.pumpWidget(const SizedBox());
          await tester.pumpWidget(app(ScriptPage(script: script)));
          await tester.pumpAndSettle();
          final run = find.ancestor(
            of: find.text('运行'),
            matching: find.byWidgetPredicate(
              (widget) => widget is FilledButton,
            ),
          );
          await fixture.revealWidget(tester, run);
          expect(tester.getSize(run).height, greaterThanOrEqualTo(48));
          final bounds = tester.getRect(run);
          expect(bounds.left, greaterThanOrEqualTo(16));
          expect(bounds.right, lessThanOrEqualTo(size.width - 16));
          expect(tester.takeException(), isNull);
        }
        final hftp = LogHftpApi();
        await tester.pumpWidget(app(HftpPage(api: hftp)));
        await tester.pumpAndSettle();
        for (final action in ['启动服务', '选择本机目录', '导入共享文件', '服务日志']) {
          await fixture.revealWidget(tester, find.text(action));
          expect(tester.takeException(), isNull);
        }
        expect(hftp.starts, 0);
        await tester.pumpWidget(const SizedBox());
        final results = <String, Object>{
          'tools.password': {
            'sensitive': true,
            'password': 'long-secret-value',
            'length': 17,
          },
          'tools.encoder': {
            'hashes': {'sha256': 'a' * 64},
            'bytes': 200,
          },
          'tools.crypto': {
            'preview': {'kind': 'binary', 'value': 'YWJj' * 30},
            'artifact': {
              'token': 'original-token',
              'name': 'output-encrypted-file.ctos',
              'bytes': 40,
            },
          },
          'tools.ip': {
            'data': {
              'ipAddress': '2001:db8:1234:5678::1',
              'asnOrganization':
                  'Example network organisation with a long name',
            },
          },
          'text.digest': {
            'characters': 24,
            'utf8Bytes': 48,
            'lines': 2,
            'sha256': 'a' * 64,
          },
          'device.info': {
            'source': 'App',
            'capturedAt': 1000,
            'data': {
              'model': 'Example device',
              'kernel': '6.1-example-long-version',
            },
          },
          'memory.snapshot': {
            'source': 'App',
            'capturedAt': 1000,
            'data': {
              'totalBytes': 8000000000,
              'availableBytes': 4000000000,
              'low': false,
            },
          },
          'python.selftest': {
            'python': '3.13.9',
            'architecture': 'arm64-v8a',
            'offline': true,
          },
          'unknown': {'future': 'retained'},
        };
        for (final entry in results.entries) {
          await tester.pumpWidget(const SizedBox());
          await tester.pumpWidget(
            app(
              Scaffold(
                body: ListView(
                  padding: const EdgeInsets.all(20),
                  children: [
                    ResultCard(
                      scriptId: entry.key,
                      value: fixture.envelope(entry.value),
                      api: const WorkbenchApi(),
                    ),
                  ],
                ),
              ),
            ),
          );
          await fixture.tapVisible(tester, find.text('原始 JSON 与日志'));
          await fixture.revealWidget(tester, find.text('保存 JSON'));
          expect(tester.takeException(), isNull);
        }
        await tester.pumpWidget(const SizedBox());
      });
    }
  }

  testWidgets(
    'resize preserves secret draft and reduced-motion advanced controls',
    (tester) async {
      tester.view.physicalSize = const Size(375, 812);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      await tester.pumpWidget(
        MaterialApp(
          theme: CtosTheme.dark(),
          home: MediaQuery(
            data: const MediaQueryData(disableAnimations: true),
            child: ScriptPage(script: fixture.password()),
          ),
        ),
      );
      await tester.enterText(fixture.input('seed'), '  secret🙂  ');
      await fixture.tapVisible(tester, find.text('高级选项'));
      expect(
        tester.widget<AnimatedRotation>(find.byType(AnimatedRotation)).duration,
        Duration.zero,
      );
      await tester.enterText(fixture.input('salt'), 'retained salt');
      tester.view.physicalSize = const Size(1200, 900);
      await tester.pumpAndSettle();
      expect(
        tester.widget<TextFormField>(fixture.input('seed')).controller!.text,
        '  secret🙂  ',
      );
      expect(
        tester.widget<TextFormField>(fixture.input('salt')).controller!.text,
        'retained salt',
      );
      final controller =
          tester.widget<TextFormField>(fixture.input('seed')).controller!
              as MaskedTextController;
      expect(
        controller
            .buildTextSpan(
              context: tester.element(fixture.input('seed')),
              withComposing: true,
            )
            .toPlainText(),
        '•' * '  secret🙂  '.length,
      );
      expect(tester.takeException(), isNull);
    },
  );
}

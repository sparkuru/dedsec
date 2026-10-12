import 'dart:async';
import 'package:ctos/secrets/api.dart';
import 'package:ctos/secrets/flows.dart';
import 'package:ctos/secrets/settings_page.dart';
import 'package:ctos/ui/ctos_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

Map<String, dynamic> status({bool exists = true, bool unlocked = false}) => {
  'state': unlocked ? 'unlocked' : 'locked',
  'exists': exists,
  'generation': exists ? 1 : 0,
  'quickUnlockEnabled': false,
  'quickUnlockAvailable': false,
  'backgroundTimeoutSeconds': 30,
};

const preview = {
  'status': 'preview',
  'previewId': 'preview-a',
  'totpEntries': 2,
  'sshEntries': 1,
  'files': 3,
  'trustRecords': 1,
  'overwrite': true,
  'trustSources': [
    'example.test:22 ed25519 SHA256:fixture — replaces target trust',
  ],
};

Map<String, dynamic> exportReceipt({
  String state = 'exported',
  List<Map<String, dynamic>> remaining = const [],
}) => {
  'status': state,
  'phase': 'postcleanup',
  'fileName': 'ctos-backup.ctvault',
  'locationLabel': 'fixture',
  'remaining': remaining,
};
const exportPreview = {
  'status': 'export_preview',
  'exportId': 'export-a',
  'fileName': 'ctos-backup.ctvault',
  'locationLabel': 'fixture',
  'collision': true,
  'overwriteAvailable': true,
  'existing': {'bytes': 3},
};

const restoreReview = {
  'providerId': 'fixture',
  'providerVersion': '1',
  'providerRevision': 1,
  'sourceCipherDigest': 'source-digest',
  'targetCipherDigest': 'target-digest',
  'coverage': [
    {
      'side': 'source',
      'kind': 'file',
      'type': 'PRIVATE_KEY',
      'objectId': 'source-key',
      'path': 'keys/example',
      'identity': 'fixture identity',
      'objectBinding': 'bound-key',
    },
  ],
  'trustChanges': [
    {
      'status': 'changed',
      'sourceObjectId': 'source-key',
      'targetObjectId': 'target-key',
      'host': 'example.test',
      'port': 22,
      'keyType': 'ed25519',
      'sourceFingerprint': 'SHA256:source',
      'targetFingerprint': 'SHA256:target',
      'marker': '',
    },
  ],
  'conflicts': [
    {
      'kind': 'file',
      'status': 'content_changed',
      'sourceObjectId': 'source-key',
      'targetObjectId': 'target-key',
    },
  ],
};

Future<void> frames(WidgetTester tester) async {
  for (var i = 0; i < 12; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

Future<void> reveal(WidgetTester tester, Finder finder) async {
  final list = find.byType(ListView).last;
  for (var i = 0; i < 35 && finder.evaluate().isEmpty; i++) {
    await tester.drag(list, const Offset(0, -180));
    await tester.pump();
  }
  for (var i = 0; i < 70 && finder.evaluate().isEmpty; i++) {
    await tester.drag(list, const Offset(0, 180));
    await tester.pump();
  }
  await tester.pump(const Duration(milliseconds: 300));
  await Scrollable.ensureVisible(tester.element(finder), alignment: .5);
  await tester.pump(const Duration(milliseconds: 300));
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const api = SecretVaultApi();
  final calls = <MethodCall>[];
  Future<Object?> Function(MethodCall)? response;
  var ownerCounter = 0;
  final owners = <String>{};
  void Function(String)? onRelease;

  setUp(() {
    calls.clear();
    response = null;
    owners.clear();
    ownerCounter = 0;
    onRelease = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(api.channel, (call) async {
          calls.add(call);
          if (call.method == 'secrets.protect') {
            final args = call.arguments as Map;
            if (args['action'] == 'acquire') {
              final owner = 'owner-${++ownerCounter}';
              owners.add(owner);
              return {'owner': owner};
            }
            final owner = args['owner'] as String;
            onRelease?.call(owner);
            expect(owners.remove(owner), isTrue);
            return null;
          }
          if (call.method == 'secrets.cancel' ||
              call.method == 'secrets.importCancel')
            return null;
          if (response != null) return response!(call);
          return status();
        });
  });
  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(api.channel, null);
  });

  Widget app(
    Widget home, {
    GlobalKey<NavigatorState>? navigatorKey,
    double scale = 1,
  }) => MaterialApp(
    navigatorKey: navigatorKey,
    theme: CtosTheme.dark(),
    builder: (context, child) => MediaQuery(
      data: MediaQuery.of(
        context,
      ).copyWith(textScaler: TextScaler.linear(scale)),
      child: child!,
    ),
    home: home,
  );

  Widget launcher(
    Future<Object?> Function(BuildContext) open, {
    void Function(Object?)? completed,
  }) => Scaffold(
    body: Builder(
      builder: (context) => TextButton(
        onPressed: () async {
          try {
            final result = await open(context);
            completed?.call(result);
          } catch (error) {
            completed?.call(error);
          }
        },
        child: const Text('打开'),
      ),
    ),
  );

  test(
    'typed API rejects missing status and never exposes platform diagnostics',
    () {
      expect(
        () => SecretVaultStatus.fromMap({'state': 'unlocked'}),
        throwsFormatException,
      );
      expect(
        secretVaultError(
          PlatformException(code: 'OTHER', message: 'secret-value'),
        ),
        isNot(contains('secret-value')),
      );
      expect(
        secretVaultError(PlatformException(code: 'VAULT_AUTHENTICATION')),
        contains('口令错误或文件损坏'),
      );
      expect(
        secretVaultError(PlatformException(code: 'VAULT_EXPORT_UNCERTAIN')),
        contains('加密备份可能已写入'),
      );
      expect(
        secretVaultError(PlatformException(code: 'VAULT_COMMIT_UNCERTAIN')),
        contains('主口令重新打开并核验内容'),
      );
    },
  );

  testWidgets(
    'wrong password retry uses a fresh host request and cancels that attempt',
    (tester) async {
      final admitted = <String>{};
      final pending = Completer<Object?>();
      response = (call) async {
        final id = call.arguments['requestId'] as String;
        if (!admitted.add(id)) throw PlatformException(code: 'PARAMETERS');
        if (admitted.length == 1) {
          throw PlatformException(code: 'VAULT_AUTHENTICATION');
        }
        return pending.future;
      };
      Object? result = 'waiting';
      await tester.pumpWidget(
        app(
          launcher(
            (context) => showSecretPasswordFlow(
              context,
              api: api,
              action: SecretFormAction.unlock,
            ),
            completed: (value) => result = value,
          ),
        ),
      );
      await tester.tap(find.text('打开'));
      await frames(tester);
      await tester.enterText(find.byType(TextFormField), 'wrong');
      await tester.tap(find.widgetWithText(FilledButton, '解锁秘密库'));
      await frames(tester);
      expect(find.textContaining('口令错误或文件损坏'), findsOneWidget);
      await tester.enterText(find.byType(TextFormField), 'correct');
      await tester.tap(find.widgetWithText(FilledButton, '解锁秘密库'));
      await tester.pump();
      final attempts = calls
          .where((call) => call.method == 'secrets.unlock')
          .toList();
      expect(attempts, hasLength(2));
      expect(
        attempts[0].arguments['requestId'],
        isNot(attempts[1].arguments['requestId']),
      );
      await tester.binding.handlePopRoute();
      await frames(tester);
      expect(
        calls
            .singleWhere((call) => call.method == 'secrets.cancel')
            .arguments['requestId'],
        attempts[1].arguments['requestId'],
      );
      pending.complete(status(unlocked: true));
      await frames(tester);
      expect(result, isNull);
      expect(owners, isEmpty);
      expect(tester.takeException(), isNull);
    },
  );

  test(
    'request forbids concurrent calls and cannot renew after cancellation',
    () async {
      final pending = Completer<Object?>();
      response = (_) => pending.future;
      final request = SecretRequest(api);
      final first = api.call('unlock', request: request);
      await expectLater(
        api.call('unlock', request: request),
        throwsA(isA<PlatformException>().having((e) => e.code, 'code', 'BUSY')),
      );
      await request.cancel();
      pending.complete(status(unlocked: true));
      expect(await first, status(unlocked: true));
      final cancelledId = request.id;
      await expectLater(
        api.call('unlock', request: request),
        throwsA(
          isA<PlatformException>().having(
            (e) => e.code,
            'code',
            'VAULT_CANCELLED',
          ),
        ),
      );
      expect(request.id, cancelledId);
      expect(
        calls.where((call) => call.method == 'secrets.unlock'),
        hasLength(1),
      );
    },
  );

  testWidgets(
    'cancelled export waits for host completion and preserves committed or uncertain receipt',
    (tester) async {
      for (final uncertain in [false, true]) {
        await tester.pumpWidget(const SizedBox());
        calls.clear();
        final pending = Completer<Object?>();
        response = (_) => pending.future;
        Object? result = 'waiting';
        await tester.pumpWidget(
          app(
            launcher(
              (context) => showSecretPasswordFlow(
                context,
                api: api,
                action: SecretFormAction.export,
              ),
              completed: (value) => result = value,
            ),
          ),
        );
        await tester.tap(find.text('打开'));
        await frames(tester);
        await tester.enterText(
          find.byKey(const ValueKey('password')),
          'password',
        );
        await tester.tap(find.widgetWithText(FilledButton, '验证并选择保存位置'));
        await tester.pump();
        await tester.tap(find.text('取消'));
        await frames(tester);
        expect(result, 'waiting');
        expect(owners, hasLength(1));
        if (uncertain) {
          pending.completeError(
            PlatformException(code: 'VAULT_EXPORT_UNCERTAIN'),
          );
        } else {
          pending.complete(exportReceipt());
        }
        await frames(tester);
        expect(
          find.textContaining(uncertain ? '加密备份可能已写入' : '加密备份已保存'),
          findsOneWidget,
        );
        expect(find.byType(TextFormField, skipOffstage: false), findsNothing);
        expect(result, 'waiting');
        await tester.tap(find.widgetWithText(FilledButton, '返回'));
        await frames(tester);
        expect(
          result,
          uncertain ? {'errorCode': 'VAULT_EXPORT_UNCERTAIN'} : exportReceipt(),
        );
        expect(owners, isEmpty);
        expect(tester.takeException(), isNull);
      }
    },
  );

  testWidgets(
    'cancelled create reports the host committed library before returning',
    (tester) async {
      final pending = Completer<Object?>();
      response = (_) => pending.future;
      Object? result = 'waiting';
      await tester.pumpWidget(
        app(
          launcher(
            (context) => showSecretPasswordFlow(
              context,
              api: api,
              action: SecretFormAction.create,
            ),
            completed: (value) => result = value,
          ),
        ),
      );
      await tester.tap(find.text('打开'));
      await frames(tester);
      await tester.enterText(
        find.byKey(const ValueKey('password')),
        'password',
      );
      await tester.enterText(
        find.byKey(const ValueKey('confirmation')),
        'password',
      );
      await tester.tap(find.widgetWithText(FilledButton, '创建秘密库'));
      await tester.pump();
      await tester.tap(find.text('取消'));
      await frames(tester);
      pending.complete(status());
      await frames(tester);
      expect(find.text('秘密库已创建。'), findsOneWidget);
      expect(result, 'waiting');
      await tester.tap(find.widgetWithText(FilledButton, '返回'));
      await frames(tester);
      expect(result, status());
      expect(owners, isEmpty);
    },
  );

  testWidgets(
    'cancelled import waits for host committed or uncertain state without claiming original data retained',
    (tester) async {
      for (final uncertain in [false, true]) {
        await tester.pumpWidget(const SizedBox());
        calls.clear();
        final pending = Completer<Object?>();
        response = (call) async {
          if (call.method == 'secrets.importPreview') return preview;
          return await pending.future;
        };
        Object? result = 'waiting';
        await tester.pumpWidget(
          app(
            launcher(
              (context) => importSecretVault(context, api: api, exists: false),
              completed: (value) => result = value,
            ),
          ),
        );
        await tester.tap(find.text('打开'));
        await frames(tester);
        await tester.enterText(find.byType(TextFormField), 'password');
        await tester.tap(find.widgetWithText(FilledButton, '选择备份并验证'));
        await frames(tester);
        await tester.tap(find.text('确认覆盖全部现有库内容'));
        await reveal(tester, find.text('已审阅并批准来源信任记录及覆盖范围'));
        await tester.tap(find.text('已审阅并批准来源信任记录及覆盖范围'));
        await reveal(tester, find.widgetWithText(FilledButton, '确认导入'));
        await tester.tap(find.widgetWithText(FilledButton, '确认导入'));
        await tester.pump();
        expect(
          calls.where((call) => call.method == 'secrets.importCommit'),
          hasLength(1),
        );
        await tester.tap(find.text('取消'));
        await frames(tester);
        expect(
          result,
          'waiting',
          reason: '$uncertain: ${calls.map((call) => call.method).join(', ')}',
        );
        if (uncertain)
          pending.completeError(
            PlatformException(code: 'VAULT_COMMIT_UNCERTAIN'),
          );
        else
          pending.complete(status());
        await frames(tester);
        expect(
          find.textContaining(uncertain ? '无法确认秘密库' : '备份已导入'),
          findsOneWidget,
          reason: find
              .byType(Text)
              .evaluate()
              .map((element) => (element.widget as Text).data)
              .join(' | '),
        );
        expect(result, 'waiting');
        await reveal(tester, find.widgetWithText(FilledButton, '返回'));
        await tester.tap(find.widgetWithText(FilledButton, '返回'));
        await frames(tester);
        if (uncertain)
          expect(
            result,
            isA<PlatformException>().having(
              (error) => error.code,
              'code',
              'VAULT_COMMIT_UNCERTAIN',
            ),
          );
        else
          expect(result, true);
        expect(tester.takeException(), isNull);
      }
    },
  );

  testWidgets(
    'uncertain receipt remains readable and acknowledgement reachable on narrow keyboard layout',
    (tester) async {
      tester.view.physicalSize = const Size(320, 568);
      tester.view.devicePixelRatio = 1;
      tester.view.viewInsets = const FakeViewPadding(bottom: 240);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetViewInsets);
      final pending = Completer<Object?>();
      response = (_) => pending.future;
      await tester.pumpWidget(
        app(
          launcher(
            (context) => showSecretPasswordFlow(
              context,
              api: api,
              action: SecretFormAction.export,
            ),
          ),
          scale: 2,
        ),
      );
      await tester.tap(find.text('打开'));
      await frames(tester);
      await reveal(tester, find.byKey(const ValueKey('password')));
      await tester.enterText(
        find.byKey(const ValueKey('password')),
        'password',
      );
      await reveal(tester, find.widgetWithText(FilledButton, '验证并选择保存位置'));
      await tester.tap(find.widgetWithText(FilledButton, '验证并选择保存位置'));
      await tester.pump();
      await reveal(tester, find.text('取消'));
      await tester.tap(find.text('取消'));
      pending.completeError(PlatformException(code: 'VAULT_EXPORT_UNCERTAIN'));
      await frames(tester);
      final message = find.textContaining('加密备份可能已写入');
      await reveal(tester, message);
      expect(message, findsOneWidget);
      final acknowledgement = find.widgetWithText(FilledButton, '返回');
      await reveal(tester, acknowledgement);
      expect(tester.getRect(acknowledgement).bottom, lessThanOrEqualTo(328));
      expect(tester.getSize(acknowledgement).height, greaterThanOrEqualTo(48));
      expect(find.byType(TextFormField, skipOffstage: false), findsNothing);
      await tester.tap(acknowledgement);
      await frames(tester);
      expect(owners, isEmpty);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'protection completes before password field first renders, releases after removal',
    (tester) async {
      final protection = Completer<Object?>();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(api.channel, (call) async {
            calls.add(call);
            if (call.method == 'secrets.protect' &&
                call.arguments['action'] == 'acquire')
              return protection.future;
            if (call.method == 'secrets.protect')
              expect(
                find.byType(TextFormField, skipOffstage: false),
                findsNothing,
              );
            return null;
          });
      await tester.pumpWidget(
        app(
          launcher(
            (context) => showSecretPasswordFlow(
              context,
              api: api,
              action: SecretFormAction.unlock,
            ),
          ),
        ),
      );
      await tester.tap(find.text('打开'));
      await frames(tester);
      expect(find.byType(TextFormField), findsNothing);
      protection.complete({'owner': 'late-owner'});
      await frames(tester);
      expect(find.byType(TextFormField), findsOneWidget);
      await tester.enterText(find.byType(TextFormField), 'draft');
      await tester.tap(find.text('取消'));
      await frames(tester);
      expect(calls.where((call) => call.method == 'secrets.protect').length, 2);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'late acquire after caller disposal releases its own owner without rendering',
    (tester) async {
      final protection = Completer<Object?>();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(api.channel, (call) async {
            calls.add(call);
            return call.method == 'secrets.protect' &&
                    call.arguments['action'] == 'acquire'
                ? protection.future
                : null;
          });
      await tester.pumpWidget(
        app(
          launcher(
            (context) => showSecretPasswordFlow(
              context,
              api: api,
              action: SecretFormAction.unlock,
            ),
          ),
        ),
      );
      await tester.tap(find.text('打开'));
      await tester.pumpWidget(const SizedBox());
      protection.complete({'owner': 'late-owner'});
      await frames(tester);
      expect(find.byType(TextFormField), findsNothing);
      expect(
        calls.any(
          (call) =>
              call.method == 'secrets.protect' &&
              call.arguments['owner'] == 'late-owner',
        ),
        isTrue,
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'create validates confirmation and submits exact untrimmed values',
    (tester) async {
      Object? result;
      response = (_) async => status(unlocked: true);
      onRelease = (_) =>
          expect(find.byType(TextFormField, skipOffstage: false), findsNothing);
      await tester.pumpWidget(
        app(
          launcher(
            (context) => showSecretPasswordFlow(
              context,
              api: api,
              action: SecretFormAction.create,
            ),
            completed: (value) => result = value,
          ),
        ),
      );
      await tester.tap(find.text('打开'));
      await frames(tester);
      await tester.enterText(
        find.byKey(const ValueKey('password')),
        ' spaced secret ',
      );
      await tester.enterText(
        find.byKey(const ValueKey('confirmation')),
        'wrong',
      );
      await tester.tap(find.widgetWithText(FilledButton, '创建秘密库'));
      await tester.pump();
      expect(find.text('两次口令不一致'), findsOneWidget);
      expect(calls.where((call) => call.method == 'secrets.create'), isEmpty);
      await tester.enterText(
        find.byKey(const ValueKey('confirmation')),
        ' spaced secret ',
      );
      await tester.tap(find.widgetWithText(FilledButton, '创建秘密库'));
      await frames(tester);
      final args =
          calls.singleWhere((call) => call.method == 'secrets.create').arguments
              as Map;
      expect(args['password'], ' spaced secret ');
      expect(args['confirmation'], ' spaced secret ');
      expect(args['requestId'], matches(r'^[a-zA-Z0-9_-]+$'));
      expect(result, isA<Map>());
      expect(owners, isEmpty);
    },
  );

  testWidgets('failure clears password draft and displays a recovery error', (
    tester,
  ) async {
    response = (_) async => throw PlatformException(
      code: 'VAULT_AUTHENTICATION',
      message: 'never show secret',
    );
    await tester.pumpWidget(
      app(
        launcher(
          (context) => showSecretPasswordFlow(
            context,
            api: api,
            action: SecretFormAction.export,
          ),
        ),
      ),
    );
    await tester.tap(find.text('打开'));
    await frames(tester);
    await tester.enterText(find.byKey(const ValueKey('password')), 'secret');
    await tester.tap(find.widgetWithText(FilledButton, '验证并选择保存位置'));
    await frames(tester);
    expect(find.textContaining('口令错误或文件损坏'), findsOneWidget);
    expect(
      tester
          .widget<TextFormField>(find.byKey(const ValueKey('password')))
          .controller!
          .text,
      isEmpty,
    );
    expect(owners, hasLength(1));
    await tester.tap(find.text('取消'));
    await frames(tester);
    expect(owners, isEmpty);
  });

  testWidgets('pending operation cancelled by back ignores late success', (
    tester,
  ) async {
    final pending = Completer<Object?>();
    Object? result = 'waiting';
    response = (_) => pending.future;
    await tester.pumpWidget(
      app(
        launcher(
          (context) => showSecretPasswordFlow(
            context,
            api: api,
            action: SecretFormAction.unlock,
          ),
          completed: (value) => result = value,
        ),
      ),
    );
    await tester.tap(find.text('打开'));
    await frames(tester);
    await tester.enterText(find.byType(TextFormField), 'secret');
    await tester.tap(find.widgetWithText(FilledButton, '解锁秘密库'));
    await tester.pump();
    await tester.binding.handlePopRoute();
    await frames(tester);
    final operation = calls.singleWhere(
      (call) => call.method == 'secrets.unlock',
    );
    final cancel = calls.singleWhere((call) => call.method == 'secrets.cancel');
    expect(cancel.arguments['requestId'], operation.arguments['requestId']);
    expect(result, isNull);
    pending.complete(status(unlocked: true));
    await frames(tester);
    expect(result, isNull);
    expect(owners, isEmpty);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'external route removal cancels pending request and releases after disposal',
    (tester) async {
      final navigator = GlobalKey<NavigatorState>();
      final pending = Completer<Object?>();
      response = (_) => pending.future;
      onRelease = (_) =>
          expect(find.byType(TextFormField, skipOffstage: false), findsNothing);
      await tester.pumpWidget(
        app(
          launcher(
            (context) => showSecretPasswordFlow(
              context,
              api: api,
              action: SecretFormAction.unlock,
            ),
          ),
          navigatorKey: navigator,
        ),
      );
      await tester.tap(find.text('打开'));
      await frames(tester);
      await tester.enterText(find.byType(TextFormField), 'secret');
      await tester.tap(find.widgetWithText(FilledButton, '解锁秘密库'));
      await tester.pump();
      navigator.currentState!.removeRoute(
        ModalRoute.of(tester.element(find.byType(TextFormField)))!,
      );
      await frames(tester);
      expect(
        calls.where((call) => call.method == 'secrets.cancel'),
        hasLength(1),
      );
      expect(owners, isEmpty);
      pending.complete(status(unlocked: true));
      await frames(tester);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'nested sensitive routes release only their own protection owner',
    (tester) async {
      await tester.pumpWidget(
        app(
          launcher(
            (context) => showSensitiveSecretRoute<void>(
              context,
              api: api,
              builder: (context) => Scaffold(
                appBar: AppBar(title: const Text('外层')),
                body: TextButton(
                  onPressed: () => showSensitiveSecretRoute<void>(
                    context,
                    api: api,
                    builder: (_) => Scaffold(
                      appBar: AppBar(title: const Text('内层')),
                      body: const Text('敏感显示'),
                    ),
                  ),
                  child: const Text('嵌套'),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('打开'));
      await frames(tester);
      await tester.tap(find.text('嵌套'));
      await frames(tester);
      expect(owners, {'owner-1', 'owner-2'});
      await tester.binding.handlePopRoute();
      await frames(tester);
      expect(owners, {'owner-1'});
      await tester.binding.handlePopRoute();
      await frames(tester);
      expect(owners, isEmpty);
    },
  );

  testWidgets(
    'destroying Navigator revokes request and releases sensitive owner',
    (tester) async {
      final pending = Completer<Object?>();
      response = (_) => pending.future;
      Object? result = 'waiting';
      await tester.pumpWidget(
        app(
          launcher(
            (context) => showSecretPasswordFlow(
              context,
              api: api,
              action: SecretFormAction.unlock,
            ),
            completed: (value) => result = value,
          ),
        ),
      );
      await tester.tap(find.text('打开'));
      await frames(tester);
      await tester.enterText(find.byType(TextFormField), 'secret');
      await tester.tap(find.widgetWithText(FilledButton, '解锁秘密库'));
      await tester.pump();
      await tester.pumpWidget(const SizedBox());
      await frames(tester);
      expect(owners, isEmpty);
      expect(result, isNull);
      expect(
        calls.where((call) => call.method == 'secrets.cancel'),
        hasLength(1),
      );
      pending.complete(status(unlocked: true));
      await frames(tester);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'picker cancellation returns to original caller with no preview or commit',
    (tester) async {
      response = (_) async => {'status': 'cancelled'};
      Object? result;
      await tester.pumpWidget(
        app(
          launcher(
            (context) => importSecretVault(context, api: api, exists: false),
            completed: (value) => result = value,
          ),
        ),
      );
      await tester.tap(find.text('打开'));
      await frames(tester);
      await tester.enterText(find.byType(TextFormField), 'source');
      await tester.tap(find.widgetWithText(FilledButton, '选择备份并验证'));
      await frames(tester);
      expect(result, false);
      expect(find.text('打开'), findsOneWidget);
      expect(
        calls.where((call) => call.method == 'secrets.importCommit'),
        isEmpty,
      );
      expect(owners, isEmpty);
    },
  );

  testWidgets(
    'background clears password draft and window protection remains until exit',
    (tester) async {
      await tester.pumpWidget(
        app(
          launcher(
            (context) => showSecretPasswordFlow(
              context,
              api: api,
              action: SecretFormAction.unlock,
            ),
          ),
        ),
      );
      await tester.tap(find.text('打开'));
      await frames(tester);
      await tester.enterText(find.byType(TextFormField), 'draft');
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      await tester.pump();
      expect(
        tester
            .widget<TextFormField>(find.byType(TextFormField))
            .controller!
            .text,
        isEmpty,
      );
      expect(owners, hasLength(1));
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.binding.handlePopRoute();
      await frames(tester);
      expect(owners, isEmpty);
    },
  );

  testWidgets(
    'new library bootstrap offers optional quick unlock and returns to business',
    (tester) async {
      var exists = false;
      response = (call) async {
        if (call.method == 'secrets.create') exists = true;
        return {
          ...status(exists: exists, unlocked: exists),
          'quickUnlockAvailable': true,
        };
      };
      Object? result;
      await tester.pumpWidget(
        app(
          launcher(
            (context) => ensureSecretVaultUnlocked(context, api: api),
            completed: (value) => result = value,
          ),
        ),
      );
      await tester.tap(find.text('打开'));
      await frames(tester);
      await tester.tap(find.widgetWithText(FilledButton, '创建秘密库'));
      await frames(tester);
      await tester.enterText(
        find.byKey(const ValueKey('password')),
        'new-password',
      );
      await tester.enterText(
        find.byKey(const ValueKey('confirmation')),
        'new-password',
      );
      await tester.tap(find.widgetWithText(FilledButton, '创建秘密库'));
      await frames(tester);
      await tester.tap(find.text('暂不开启'));
      await frames(tester);
      expect(result, true);
      expect(find.text('打开'), findsOneWidget);
      expect(
        calls.where((call) => call.method == 'secrets.quickUnlock'),
        isEmpty,
      );
      expect(owners, isEmpty);
    },
  );

  testWidgets('quick unlock cancellation ignores late authentication result', (
    tester,
  ) async {
    final pending = Completer<Object?>();
    response = (call) async {
      if (call.method == 'secrets.quickUnlock') return pending.future;
      return {
        ...status(),
        'quickUnlockAvailable': true,
        'quickUnlockEnabled': true,
      };
    };
    Object? result = 'waiting';
    await tester.pumpWidget(
      app(
        launcher(
          (context) => ensureSecretVaultUnlocked(context, api: api),
          completed: (value) => result = value,
        ),
      ),
    );
    await tester.tap(find.text('打开'));
    await frames(tester);
    await tester.tap(find.widgetWithText(FilledButton, '快捷解锁'));
    await frames(tester);
    await tester.binding.handlePopRoute();
    await frames(tester);
    expect(result, false);
    expect(
      calls
          .singleWhere((call) => call.method == 'secrets.cancel')
          .arguments['requestId'],
      calls
          .singleWhere((call) => call.method == 'secrets.quickUnlock')
          .arguments['requestId'],
    );
    pending.complete(status(unlocked: true));
    await frames(tester);
    expect(result, false);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'import requires both fresh passwords, overwrite and concrete trust consent',
    (tester) async {
      response = (call) async =>
          call.method == 'secrets.importPreview' ? preview : status();
      Object? result;
      await tester.pumpWidget(
        app(
          launcher(
            (context) => importSecretVault(context, api: api, exists: true),
            completed: (value) => result = value,
          ),
        ),
      );
      await tester.tap(find.text('打开'));
      await frames(tester);
      await tester.enterText(
        find.byKey(const ValueKey('sourcePassword')),
        'source-password',
      );
      await tester.enterText(
        find.byKey(const ValueKey('targetPassword')),
        'target-password',
      );
      await tester.tap(find.widgetWithText(FilledButton, '选择备份并验证'));
      await frames(tester);
      expect(find.byType(TextFormField, skipOffstage: false), findsNothing);
      expect(owners, isEmpty);
      expect(
        tester
            .widget<FilledButton>(find.widgetWithText(FilledButton, '确认导入'))
            .onPressed,
        isNull,
      );
      await tester.tap(find.text('确认覆盖全部现有库内容'));
      await reveal(tester, find.text('已审阅并批准来源信任记录及覆盖范围'));
      await tester.tap(find.text('已审阅并批准来源信任记录及覆盖范围'));
      await reveal(tester, find.widgetWithText(FilledButton, '确认导入'));
      await tester.tap(find.widgetWithText(FilledButton, '确认导入'));
      await frames(tester);
      final args = calls
          .singleWhere((call) => call.method == 'secrets.importPreview')
          .arguments;
      expect(args['sourcePassword'], 'source-password');
      expect(args['targetPassword'], 'target-password');
      expect(
        calls
            .singleWhere((call) => call.method == 'secrets.importCommit')
            .arguments['confirmTrust'],
        true,
      );
      expect(result, true);
      expect(
        calls.where((call) => call.method == 'secrets.importCancel'),
        isEmpty,
      );
    },
  );

  testWidgets(
    'empty target import does not request nonexistent target password and cancel revokes preview',
    (tester) async {
      response = (_) async => {...preview, 'overwrite': false};
      await tester.pumpWidget(
        app(
          launcher(
            (context) => importSecretVault(context, api: api, exists: false),
          ),
        ),
      );
      await tester.tap(find.text('打开'));
      await frames(tester);
      expect(find.byType(TextFormField), findsOneWidget);
      await tester.enterText(find.byType(TextFormField), 'source');
      await tester.tap(find.widgetWithText(FilledButton, '选择备份并验证'));
      await frames(tester);
      await tester.binding.handlePopRoute();
      await frames(tester);
      expect(
        calls.where((call) => call.method == 'secrets.importCommit'),
        isEmpty,
      );
      expect(
        calls.where((call) => call.method == 'secrets.importCancel'),
        isNotEmpty,
      );
      expect(
        calls
            .singleWhere((call) => call.method == 'secrets.importPreview')
            .arguments
            .containsKey('targetPassword'),
        isFalse,
      );
    },
  );

  testWidgets(
    'valid shared session returns to business without another unlock',
    (tester) async {
      response = (_) async => status(unlocked: true);
      Object? result;
      await tester.pumpWidget(
        app(
          launcher(
            (context) => ensureSecretVaultUnlocked(context, api: api),
            completed: (value) => result = value,
          ),
        ),
      );
      await tester.tap(find.text('打开'));
      await frames(tester);
      expect(result, true);
      expect(calls.map((call) => call.method), [
        'secrets.status',
        'secrets.status',
      ]);
      expect(find.text('打开'), findsOneWidget);
    },
  );

  testWidgets(
    'bootstrap rechecks lock and generation after late unlocked status',
    (tester) async {
      for (final replacement in [
        status(),
        {...status(unlocked: true), 'generation': 2},
      ]) {
        var read = 0;
        final initial = Completer<Object?>();
        response = (_) async {
          if (++read == 1) return await initial.future;
          return replacement;
        };
        Object? result = 'waiting';
        await tester.pumpWidget(const SizedBox());
        await tester.pumpWidget(
          app(
            launcher(
              (context) => ensureSecretVaultUnlocked(context, api: api),
              completed: (value) => result = value,
            ),
          ),
        );
        await tester.tap(find.text('打开'));
        await tester.pump();
        initial.complete(status(unlocked: true));
        await frames(tester);
        expect(result, false);
        expect(find.byType(TextFormField), findsNothing);
      }
    },
  );

  for (final size in [
    const Size(320, 568),
    const Size(812, 375),
    const Size(1200, 900),
  ]) {
    testWidgets('background timeout remains reachable $size at 2x text', (
      tester,
    ) async {
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      response = (_) async => status();
      await tester.pumpWidget(app(const SecretVaultSettingsPage(), scale: 2));
      await frames(tester);
      final background = find.byKey(const ValueKey('background-timeout'));
      await reveal(tester, background);
      expect(find.text('后台解锁保留（秒）'), findsOneWidget);
      expect(
        tester
            .widget<TextField>(
              find.descendant(of: background, matching: find.byType(TextField)),
            )
            .decoration!
            .labelText,
        '秒数',
      );
      await tester.enterText(background, '0');
      await reveal(tester, find.text('保存时间设置'));
      await tester.tap(find.text('保存时间设置'));
      await frames(tester);
      final args = calls
          .singleWhere((call) => call.method == 'secrets.settings')
          .arguments;
      expect(args, {'backgroundTimeoutSeconds': 0});
      expect(
        tester.getSize(find.widgetWithText(OutlinedButton, '保存时间设置')).height,
        greaterThanOrEqualTo(48),
      );
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets(
    'background timeout rejects invalid seconds and preserves draft on failure',
    (tester) async {
      response = (call) async {
        if (call.method == 'secrets.settings')
          throw PlatformException(code: 'BUSY');
        return status();
      };
      await tester.pumpWidget(app(const SecretVaultSettingsPage()));
      await frames(tester);
      final background = find.byKey(const ValueKey('background-timeout'));
      for (final invalid in ['-1', '86401', '1.5', 'not-seconds']) {
        await reveal(tester, background);
        await tester.enterText(background, invalid);
        await reveal(tester, find.text('保存时间设置'));
        await tester.tap(find.text('保存时间设置'));
        await tester.pump();
        expect(find.text('请输入 0–86400 的整数秒数'), findsOneWidget);
        expect(
          calls.where((call) => call.method == 'secrets.settings'),
          isEmpty,
        );
      }
      await reveal(tester, background);
      await tester.enterText(background, '75');
      await reveal(tester, find.text('保存时间设置'));
      await tester.tap(find.text('保存时间设置'));
      await frames(tester);
      await reveal(tester, find.text('已有操作进行中，请稍后重试。'));
      expect(find.text('已有操作进行中，请稍后重试。'), findsOneWidget);
      await reveal(tester, background);
      expect(tester.widget<TextFormField>(background).controller!.text, '75');
    },
  );

  testWidgets(
    'password form stays scrollable with keyboard inset and large text',
    (tester) async {
      tester.view.physicalSize = const Size(320, 568);
      tester.view.devicePixelRatio = 1;
      tester.view.viewInsets = const FakeViewPadding(bottom: 240);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetViewInsets);
      await tester.pumpWidget(
        app(
          launcher(
            (context) => showSecretPasswordFlow(
              context,
              api: api,
              action: SecretFormAction.changePassword,
            ),
          ),
          scale: 2,
        ),
      );
      await tester.tap(find.text('打开'));
      await frames(tester);
      await reveal(tester, find.byKey(const ValueKey('confirmation')));
      await tester.enterText(
        find.byKey(const ValueKey('confirmation')),
        'draft',
      );
      await reveal(tester, find.text('取消'));
      expect(tester.getRect(find.text('取消')).bottom, lessThanOrEqualTo(328));
      expect(tester.takeException(), isNull);
      await tester.tap(find.text('取消'));
      await frames(tester);
      expect(owners, isEmpty);
    },
  );
  test(
    'unknown restore categories remain explicit and private bindings stay hidden',
    () {
      final review = SecretRestoreReview.fromMap({
        ...restoreReview,
        'coverage': [
          {
            ...(restoreReview['coverage'] as List).first as Map,
            'type': 'FUTURE_TYPE',
          },
        ],
      });
      expect(review.display.join('\n'), contains('待审阅（FUTURE_TYPE）'));
      expect(review.display.join('\n'), isNot(contains('bound-key')));
    },
  );
  testWidgets(
    'export overwrite requires exact preview consent and carries no password',
    (tester) async {
      response = (call) async => call.method == 'secrets.exportPreview'
          ? exportPreview
          : exportReceipt(
              remaining: [
                {
                  'role': 'renamed_old',
                  'name': '.rollback',
                  'location': 'fixture / .rollback',
                  'state': 'present',
                },
              ],
            );
      await tester.pumpWidget(
        app(launcher((context) => showSecretExportFlow(context, api: api))),
      );
      await tester.tap(find.text('打开'));
      await frames(tester);
      await tester.enterText(
        find.byKey(const ValueKey('password')),
        'fresh password',
      );
      await tester.tap(find.widgetWithText(FilledButton, '验证并选择保存位置'));
      await frames(tester);
      expect(owners, isEmpty);
      expect(find.byType(TextFormField, skipOffstage: false), findsNothing);
      expect(
        calls.where((call) => call.method == 'secrets.exportCommit'),
        isEmpty,
      );
      expect(
        tester
            .widget<FilledButton>(find.widgetWithText(FilledButton, '覆盖并导出'))
            .onPressed,
        isNull,
      );
      await tester.tap(find.text('确认覆盖此同名文件'));
      await tester.pump();
      await tester.tap(find.widgetWithText(FilledButton, '覆盖并导出'));
      await frames(tester);
      final commit =
          calls
                  .singleWhere((call) => call.method == 'secrets.exportCommit')
                  .arguments
              as Map;
      expect(commit['exportId'], 'export-a');
      expect(commit['overwrite'], true);
      expect(commit.containsKey('password'), false);
      expect(find.textContaining('原有备份（仍存在）'), findsOneWidget);
      expect(find.textContaining('加密备份已保存'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'export capability refusal cancels its opaque ticket without overwrite',
    (tester) async {
      response = (call) async => call.method == 'secrets.exportPreview'
          ? {...exportPreview, 'overwriteAvailable': false}
          : exportReceipt(state: 'cancelled');
      await tester.pumpWidget(
        app(launcher((context) => showSecretExportFlow(context, api: api))),
      );
      await tester.tap(find.text('打开'));
      await frames(tester);
      await tester.enterText(
        find.byKey(const ValueKey('password')),
        'password',
      );
      await tester.tap(find.widgetWithText(FilledButton, '验证并选择保存位置'));
      await frames(tester);
      expect(find.textContaining('无法安全替换'), findsOneWidget);
      await tester.tap(find.text('取消'));
      await frames(tester);
      expect(
        calls.where((call) => call.method == 'secrets.exportCommit'),
        isEmpty,
      );
      expect(
        calls
            .singleWhere((call) => call.method == 'secrets.exportCancel')
            .arguments['exportId'],
        'export-a',
      );
      expect(find.textContaining('本次暂存已清理'), findsOneWidget);
    },
  );
  testWidgets(
    'late cancelled export commit displays terminal recovery receipt',
    (tester) async {
      final pending = Completer<Object?>();
      response = (call) async => call.method == 'secrets.exportPreview'
          ? {...exportPreview, 'collision': false}
          : call.method == 'secrets.exportCommit'
          ? await pending.future
          : exportReceipt(state: 'cancelled');
      await tester.pumpWidget(
        app(launcher((context) => showSecretExportFlow(context, api: api))),
      );
      await tester.tap(find.text('打开'));
      await frames(tester);
      await tester.enterText(
        find.byKey(const ValueKey('password')),
        'password',
      );
      await tester.tap(find.widgetWithText(FilledButton, '验证并选择保存位置'));
      await frames(tester);
      await tester.tap(find.text('取消'));
      await tester.pump();
      pending.complete(
        exportReceipt(
          state: 'recovery_required',
          remaining: [
            {
              'role': 'created_final',
              'name': 'partial',
              'location': 'fixture / partial',
              'state': 'unknown',
            },
          ],
        ),
      );
      await frames(tester);
      expect(
        calls.where((call) => call.method == 'secrets.exportCommit'),
        hasLength(1),
      );
      expect(find.textContaining('本次新备份（状态待核验）'), findsOneWidget);
      expect(find.textContaining('未导出备份'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'recovery requires confirmation then reselects directory without vault password',
    (tester) async {
      response = (_) async => exportReceipt(state: 'failed');
      await tester.pumpWidget(
        app(
          launcher(
            (context) => recoverSecretExport(
              context,
              api: api,
              recovery: SecretExportRecovery.fromMap({
                'recoveryId': 'recovery-a',
                'fileName': 'backup',
                'locationLabel': 'fixture',
                'phase': 'compensated',
              }),
            ),
          ),
        ),
      );
      await tester.tap(find.text('打开'));
      await frames(tester);
      expect(
        calls.where((call) => call.method == 'secrets.exportRecover'),
        isEmpty,
      );
      await tester.tap(find.text('确认并选择目录'));
      await frames(tester);
      final args =
          calls
                  .singleWhere((call) => call.method == 'secrets.exportRecover')
                  .arguments
              as Map;
      expect(args['recoveryId'], 'recovery-a');
      expect(args.containsKey('password'), false);
      expect(owners, isEmpty);
    },
  );
  testWidgets(
    'restore projection scrolls complete trust changes and binds accepted review',
    (tester) async {
      response = (call) async => call.method == 'secrets.importPreview'
          ? {
              ...preview,
              'restoreReview': restoreReview,
              'reviewBinding': 'review-a',
            }
          : status();
      await tester.pumpWidget(
        app(
          launcher(
            (context) => importSecretVault(context, api: api, exists: true),
          ),
          scale: 2,
        ),
      );
      await tester.tap(find.text('打开'));
      await frames(tester);
      await tester.enterText(
        find.byKey(const ValueKey('sourcePassword')),
        'source',
      );
      await tester.enterText(
        find.byKey(const ValueKey('targetPassword')),
        'target',
      );
      await reveal(tester, find.text('选择备份并验证'));
      await tester.tap(find.text('选择备份并验证'));
      await frames(tester);
      await reveal(tester, find.text('确认覆盖全部现有库内容'));
      await tester.tap(find.text('确认覆盖全部现有库内容'));
      await tester.pump();
      await reveal(tester, find.textContaining('SHA256:source'));
      expect(find.textContaining('SHA256:target'), findsOneWidget);
      expect(find.textContaining('变更 · example.test:22'), findsOneWidget);
      expect(find.textContaining('bound-key'), findsNothing);
      await reveal(tester, find.text('已审阅并批准来源信任记录及覆盖范围'));
      await tester.tap(find.text('已审阅并批准来源信任记录及覆盖范围'));
      await tester.pump();
      await reveal(tester, find.text('确认导入'));
      await tester.tap(find.text('确认导入'));
      await frames(tester);
      expect(
        calls
            .singleWhere((call) => call.method == 'secrets.importCommit')
            .arguments['reviewBinding'],
        'review-a',
      );
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'bad export journal allows bootstrap unlock and other management but refuses export',
    (tester) async {
      bool unlocked = false;
      response = (call) async {
        if (call.method == 'secrets.exportPreview')
          throw PlatformException(code: 'VAULT_EXPORT_RECOVERY_REQUIRED');
        if (call.method == 'secrets.unlock') unlocked = true;
        return {
          ...status(unlocked: unlocked),
          'exportRecoveryError': 'VAULT_EXPORT_RECOVERY_REQUIRED',
        };
      };
      Object? ready;
      await tester.pumpWidget(
        app(
          launcher(
            (context) => ensureSecretVaultUnlocked(context, api: api),
            completed: (value) => ready = value,
          ),
        ),
      );
      await tester.tap(find.text('打开'));
      await frames(tester);
      await tester.enterText(
        find.byKey(const ValueKey('password')),
        'actual password',
      );
      await tester.tap(find.widgetWithText(FilledButton, '解锁秘密库'));
      await frames(tester);
      expect(ready, true);
      await tester.pumpWidget(const SizedBox());
      await tester.pumpWidget(app(const SecretVaultSettingsPage()));
      await frames(tester);
      expect(find.textContaining('秘密库可以继续使用'), findsOneWidget);
      await reveal(tester, find.text('导出加密备份'));
      expect(
        tester
            .widget<OutlinedButton>(
              find.widgetWithText(OutlinedButton, '导出加密备份'),
            )
            .onPressed,
        isNull,
      );
      await reveal(tester, find.text('更换主口令'));
      expect(
        tester
            .widget<OutlinedButton>(
              find.widgetWithText(OutlinedButton, '更换主口令'),
            )
            .onPressed,
        isNotNull,
      );
      await tester.pumpWidget(const SizedBox());
      await tester.pumpWidget(
        app(launcher((context) => showSecretExportFlow(context, api: api))),
      );
      await tester.tap(find.text('打开'));
      await frames(tester);
      await tester.enterText(
        find.byKey(const ValueKey('password')),
        'actual password',
      );
      await tester.tap(find.widgetWithText(FilledButton, '验证并选择保存位置'));
      await frames(tester);
      expect(find.textContaining('未确认的导出恢复记录'), findsOneWidget);
      expect(
        calls.where((call) => call.method == 'secrets.exportCommit'),
        isEmpty,
      );
      expect(
        () => SecretVaultStatus.fromMap({
          ...status(),
          'exportRecoveryError': 'x' * 129,
        }),
        throwsFormatException,
      );
    },
  );
}

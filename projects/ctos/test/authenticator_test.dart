import 'dart:async';

import 'package:ctos/authenticator/api.dart';
import 'package:ctos/authenticator/page.dart';
import 'package:ctos/ui/ctos_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

Map<String, dynamic> vaultStatus({
  bool unlocked = true,
  bool exists = true,
  int generation = 1,
}) => {
  'state': exists
      ? unlocked
            ? 'unlocked'
            : 'locked'
      : 'missing',
  'exists': exists,
  'generation': generation,
  'quickUnlockEnabled': false,
  'quickUnlockAvailable': false,
  'backgroundTimeoutSeconds': 30,
};
Map<String, dynamic> entry({
  String id = 'entry-a',
  String code = '001234',
  String label = 'Test service',
}) => {
  'entryId': id,
  'identity': 'Test service:alice',
  'label': label,
  'code': code,
};
Map<String, dynamic> candidate(String id) => {
  'candidateId': id,
  'identity': 'service:$id',
  'label': 'Account $id',
  'algorithm': 'SHA1',
  'digits': 6,
  'period': 30,
};
Map<String, dynamic> preview({int candidates = 1}) => {
  'previewId': 'preview-a',
  'candidates': List.generate(candidates, (index) => candidate('$index')),
  'invalidCodes': 0,
};

void main() {
  final binding = TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('ctos/native');
  const events = MethodChannel('ctos/secrets');
  final calls = <MethodCall>[];
  Future<Object?> Function(MethodCall)? override;
  var status = vaultStatus();
  var rows = <Map<String, dynamic>>[entry()];
  var seconds = 29;
  var active = true;

  setUp(() {
    calls.clear();
    override = null;
    status = vaultStatus();
    rows = [entry()];
    seconds = 29;
    active = true;
    binding.defaultBinaryMessenger.setMockMethodCallHandler(events, (
      call,
    ) async {
      if (call.method == 'listen')
        scheduleMicrotask(() {
          binding.defaultBinaryMessenger.handlePlatformMessage(
            'ctos/secrets',
            const StandardMethodCodec().encodeSuccessEnvelope({
              'epoch': 1,
              'unlocked': status['state'] == 'unlocked',
              'foreground': true,
            }),
            null,
          );
        });
      return null;
    });
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      calls.add(call);
      final intercepted = await override?.call(call);
      if (intercepted != null) return intercepted;
      switch (call.method) {
        case 'secrets.status':
          return status;
        case 'secrets.protect':
          return {'owner': 'owner-${calls.length}'};
        case 'secrets.totpList':
          if (!((call.arguments as Map)['requestId'] as String).startsWith(
            'read_',
          )) {
            throw PlatformException(code: 'PARAMETERS');
          }
          return {'entries': rows, 'unixSeconds': seconds};
        case 'secrets.totpManualPreview':
        case 'secrets.totpImagePreview':
        case 'secrets.totpCameraPreview':
          return preview();
        case 'secrets.totpCommit':
          rows = [...rows, entry(id: 'new-entry', label: 'New account')];
          return {'entryId': 'new-entry'};
        case 'secrets.totpCopy':
          return {'copied': true};
        case 'secrets.totpDelete':
          rows = [];
          return status;
        case 'secrets.totpRename':
          rows = [entry(label: (call.arguments as Map)['label'] as String)];
          return status;
        case 'secrets.create':
        case 'secrets.unlock':
          status = vaultStatus();
          return status;
        case 'secrets.cancel':
        case 'secrets.totpCancel':
          return null;
        default:
          throw PlatformException(code: 'UNEXPECTED');
      }
    });
  });
  tearDown(() {
    binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
    binding.defaultBinaryMessenger.setMockMethodCallHandler(events, null);
  });

  Widget app({double scale = 1, double keyboard = 0}) => MaterialApp(
    theme: CtosTheme.dark(),
    builder: (context, child) => MediaQuery(
      data: MediaQuery.of(context).copyWith(
        textScaler: TextScaler.linear(scale),
        viewInsets: EdgeInsets.only(bottom: keyboard),
      ),
      child: child!,
    ),
    home: Scaffold(
      body: Builder(
        builder: (context) => TextButton(
          onPressed: () => showAuthenticator(
            context,
            api: AuthenticatorApi(clock: () => seconds),
            canOpen: () => active,
          ),
          child: const Text('Open'),
        ),
      ),
    ),
  );
  Future<void> open(WidgetTester tester) async {
    await tester.pumpWidget(app());
    await tester.tap(find.text('Open'));
    await tester.pumpAndSettle();
  }

  Future<void> add(WidgetTester tester) async {
    await tester.tap(find.text('添加验证码'));
    await tester.pumpAndSettle();
  }

  Future<void> event(WidgetTester tester, Map<String, dynamic> value) async {
    await binding.defaultBinaryMessenger.handlePlatformMessage(
      'ctos/secrets',
      const StandardMethodCodec().encodeSuccessEnvelope({
        'foreground': true,
        ...value,
      }),
      (_) {},
    );
    await tester.pump();
    await tester.pump();
  }

  Future<void> remove(WidgetTester tester) async {
    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
  }

  testWidgets('deactivated origin refuses late shared unlock setup', (
    tester,
  ) async {
    final pending = Completer<Object?>();
    override = (call) async =>
        call.method == 'secrets.status' ? pending.future : null;
    await tester.pumpWidget(app());
    await tester.tap(find.text('Open'));
    await tester.pump();
    active = false;
    pending.complete(
      vaultStatus(exists: false, unlocked: false, generation: 0),
    );
    await tester.pumpAndSettle();
    expect(find.text('设置秘密库'), findsNothing);
    expect(calls.where((call) => call.method == 'secrets.protect'), isEmpty);
  });

  testWidgets(
    'covered origin releases late protection without rendering consumer',
    (tester) async {
      final protection = Completer<Object?>();
      override = (call) async =>
          call.method == 'secrets.protect' &&
              (call.arguments as Map)['action'] == 'acquire'
          ? protection.future
          : null;
      await tester.pumpWidget(app());
      await tester.tap(find.text('Open'));
      await tester.pump();
      Navigator.of(tester.element(find.text('Open'))).push(
        MaterialPageRoute<void>(
          builder: (_) => const Scaffold(body: Text('Cover')),
        ),
      );
      await tester.pumpAndSettle();
      protection.complete({'owner': 'late-owner'});
      await tester.pumpAndSettle();
      await tester.pump();
      expect(find.text('Authenticator', skipOffstage: false), findsNothing);
      expect(
        calls.any(
          (call) =>
              call.method == 'secrets.protect' &&
              (call.arguments as Map)['action'] == 'release' &&
              (call.arguments as Map)['owner'] == 'late-owner',
        ),
        isTrue,
      );
      await remove(tester);
    },
  );

  testWidgets(
    'ended event stream fails closed and reconnects on explicit retry',
    (tester) async {
      await open(tester);
      await binding.defaultBinaryMessenger.handlePlatformMessage(
        'ctos/secrets',
        null,
        null,
      );
      await tester.pump();
      await tester.pump();
      await tester.pump();
      expect(find.text('001 234'), findsNothing);
      await tester.pump(const Duration(seconds: 2));
      expect(find.text('001 234'), findsNothing);
      await tester.tap(find.text('解锁并刷新'));
      await tester.pumpAndSettle();
      await tester.pump();
      await tester.pump();
      expect(find.text('001 234'), findsOneWidget);
      await remove(tester);
    },
  );

  testWidgets(
    'expired host picker result cannot become a preview after background',
    (tester) async {
      final scan = Completer<Object?>();
      override = (call) async =>
          call.method == 'secrets.totpImagePreview' ? scan.future : null;
      await open(tester);
      await add(tester);
      await tester.tap(find.text('从图片读取'));
      await tester.pump();
      binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      await tester.pump();
      binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      scan.completeError(
        PlatformException(
          code: 'VAULT_EXPIRED',
          message: 'raw-uri-must-never-appear',
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('确认账号'), findsNothing);
      expect(find.text('秘密库或操作已失效，请重新开始。'), findsOneWidget);
      expect(find.textContaining('raw-uri-must-never-appear'), findsNothing);
      expect(
        calls.where((call) => call.method == 'secrets.totpCommit'),
        isEmpty,
      );
      await remove(tester);
    },
  );

  testWidgets(
    'picker error before Dart resume survives foreground recovery safely',
    (tester) async {
      final scan = Completer<Object?>();
      override = (call) async =>
          call.method == 'secrets.totpCameraPreview' ? scan.future : null;
      await open(tester);
      await add(tester);
      await tester.tap(find.text('相机扫码'));
      await tester.pump();
      binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      await event(tester, {'epoch': 1, 'unlocked': true, 'foreground': false});
      scan.completeError(
        PlatformException(
          code: 'TOTP_CAMERA_PERMISSION',
          message: 'raw-picker-detail-must-never-appear',
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('001 234'), findsNothing);
      expect(find.widgetWithText(TextField, '密钥'), findsNothing);
      binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await event(tester, {'epoch': 1, 'unlocked': true});
      await tester.pumpAndSettle();
      expect(find.text('相机权限未开启，可使用图片或手动添加。'), findsOneWidget);
      expect(find.widgetWithText(TextField, '密钥'), findsOneWidget);
      expect(find.text('从图片读取'), findsOneWidget);
      expect(find.textContaining('raw-picker-detail'), findsNothing);
      expect(
        calls.where((call) => call.method == 'secrets.totpCommit'),
        isEmpty,
      );
      await remove(tester);
    },
  );

  testWidgets('epoch cancellation refuses a late picker recovery error', (
    tester,
  ) async {
    final scan = Completer<Object?>();
    override = (call) async =>
        call.method == 'secrets.totpCameraPreview' ? scan.future : null;
    await open(tester);
    await add(tester);
    await tester.tap(find.text('相机扫码'));
    await tester.pump();
    await event(tester, {'epoch': 2, 'unlocked': false});
    scan.completeError(PlatformException(code: 'TOTP_CAMERA_PERMISSION'));
    await tester.pumpAndSettle();
    await event(tester, {'epoch': 2, 'unlocked': true});
    await tester.pumpAndSettle();
    expect(find.text('相机权限未开启，可使用图片或手动添加。'), findsNothing);
    expect(find.widgetWithText(TextField, '密钥'), findsOneWidget);
    expect(calls.where((call) => call.method == 'secrets.cancel'), isNotEmpty);
    await remove(tester);
  });

  testWidgets('leaving cancelled picker cannot restore its error on reentry', (
    tester,
  ) async {
    final scan = Completer<Object?>();
    override = (call) async =>
        call.method == 'secrets.totpCameraPreview' ? scan.future : null;
    await open(tester);
    await add(tester);
    await tester.tap(find.text('相机扫码'));
    await tester.pump();
    await tester.tap(find.byTooltip('取消添加'));
    await tester.pumpAndSettle();
    scan.completeError(PlatformException(code: 'TOTP_CAMERA_PERMISSION'));
    await tester.pumpAndSettle();
    await add(tester);
    expect(find.text('相机权限未开启，可使用图片或手动添加。'), findsNothing);
    expect(find.widgetWithText(TextField, '密钥'), findsOneWidget);
    expect(calls.where((call) => call.method == 'secrets.cancel'), isNotEmpty);
    await remove(tester);
  });

  testWidgets('camera permission refusal leaves manual recovery reachable', (
    tester,
  ) async {
    override = (call) async {
      if (call.method == 'secrets.totpCameraPreview')
        throw PlatformException(code: 'TOTP_CAMERA_PERMISSION');
      return null;
    };
    await open(tester);
    await add(tester);
    await tester.tap(find.text('相机扫码'));
    await tester.pumpAndSettle();
    expect(find.text('相机权限未开启，可使用图片或手动添加。'), findsOneWidget);
    expect(find.text('从图片读取'), findsOneWidget);
    expect(find.widgetWithText(TextField, '密钥'), findsOneWidget);
    await remove(tester);
  });

  testWidgets(
    'cancel commit waits for terminal success acknowledgement before pop',
    (tester) async {
      final commit = Completer<Object?>();
      override = (call) async =>
          call.method == 'secrets.totpCommit' ? commit.future : null;
      await open(tester);
      await add(tester);
      await tester.tap(find.text('从图片读取'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('确认添加'));
      await tester.pump();
      await tester.tap(find.byTooltip('取消添加'));
      await tester.pump();
      expect(find.text('添加验证码'), findsOneWidget);
      commit.complete({'entryId': 'committed-entry'});
      await tester.pumpAndSettle();
      expect(find.text('核验操作结果'), findsOneWidget);
      expect(find.text('操作已完成。请重新打开验证器核验条目。'), findsOneWidget);
      await tester.tap(find.text('知道了'));
      await tester.pumpAndSettle();
      expect(find.text('Authenticator'), findsOneWidget);
      await remove(tester);
    },
  );

  testWidgets(
    'long bounded labels keep more actions scrollable at narrow large text',
    (tester) async {
      tester.view.physicalSize = const Size(320, 760);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final label = '演示账号' * 40;
      rows = [entry(label: label)];
      await tester.pumpWidget(app(scale: 2));
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.byTooltip('${label}更多操作'));
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('${label}更多操作'));
      await tester.pumpAndSettle();
      expect(find.text('验证码操作'), findsOneWidget);
      await tester.ensureVisible(find.text('重命名'));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      await tester.tap(find.text('取消'));
      await tester.pumpAndSettle();
      await remove(tester);
    },
  );

  testWidgets(
    'double action while cancelling a slow read creates only one operation',
    (tester) async {
      await open(tester);
      final read = Completer<Object?>(), cancellation = Completer<Object?>();
      override = (call) async {
        if (call.method == 'secrets.totpList') return read.future;
        if (call.method == 'secrets.cancel') return cancellation.future;
        return null;
      };
      await tester.pump(const Duration(seconds: 1));
      await tester.tap(find.text('复制验证码'));
      await tester.tap(find.text('复制验证码'));
      await tester.pump();
      expect(calls.where((call) => call.method == 'secrets.totpCopy'), isEmpty);
      cancellation.complete(null);
      read.complete({
        'entries': [entry()],
        'unixSeconds': 29,
      });
      override = null;
      await tester.pumpAndSettle();
      expect(
        calls.where((call) => call.method == 'secrets.totpCopy'),
        hasLength(1),
      );
      await remove(tester);
    },
  );

  test(
    'strict snapshots reject seeds malformed codes duplicates and oversized UTF8 labels',
    () {
      expect(
        TotpSnapshot.fromMap({
          'entries': [entry()],
          'unixSeconds': 29,
        }).remainingSeconds,
        1,
      );
      for (final value in [
        {
          'entries': [
            {...entry(), 'secret': 'forbidden'},
          ],
          'unixSeconds': 29,
        },
        {
          'entries': [entry(code: '12a456')],
          'unixSeconds': 29,
        },
        {
          'entries': [entry(), entry()],
          'unixSeconds': 29,
        },
        {
          'entries': [entry(label: '密' * 171)],
          'unixSeconds': 29,
        },
        {
          'entries': [entry()],
          'unixSeconds': -1,
        },
      ]) {
        expect(() => TotpSnapshot.fromMap(value), throwsFormatException);
      }
    },
  );
  test(
    'preview rejects unsupported parameters raw URI and duplicate candidates',
    () {
      expect(TotpPreview.fromMap(preview(candidates: 2)).candidates.length, 2);
      expect(
        TotpPreview.fromMap(preview(candidates: 16)).candidates.length,
        16,
      );
      expect(
        () => TotpPreview.fromMap(preview(candidates: 17)),
        throwsFormatException,
      );
      for (final value in [
        {...preview(), 'uri': 'forbidden'},
        {
          ...preview(),
          'candidates': [
            {...candidate('0'), 'digits': 8},
          ],
        },
        {
          ...preview(),
          'candidates': [candidate('0'), candidate('0')],
        },
        {...preview(), 'candidates': []},
        {...preview(), 'invalidCodes': '0'},
      ]) {
        expect(() => TotpPreview.fromMap(value), throwsFormatException);
      }
    },
  );

  testWidgets('acquires protection before render and releases after removal', (
    tester,
  ) async {
    final protection = Completer<Object?>();
    override = (call) async =>
        call.method == 'secrets.protect' &&
            (call.arguments as Map)['action'] == 'acquire'
        ? protection.future
        : null;
    await tester.pumpWidget(app());
    await tester.tap(find.text('Open'));
    await tester.pump();
    expect(find.text('Authenticator'), findsNothing);
    protection.complete({'owner': 'owned'});
    await tester.pumpAndSettle();
    expect(find.text('001 234'), findsOneWidget);
    await tester.tap(find.byTooltip('返回工作台'));
    await tester.pump();
    expect(
      calls.where(
        (call) =>
            call.method == 'secrets.protect' &&
            (call.arguments as Map)['action'] == 'release',
      ),
      isEmpty,
    );
    await tester.pumpAndSettle();
    await tester.pump();
    expect(find.text('001 234'), findsNothing);
    expect(
      calls.where(
        (call) =>
            call.method == 'secrets.protect' &&
            (call.arguments as Map)['action'] == 'release',
      ),
      hasLength(1),
    );
  });

  testWidgets('uses host time across windows and jumps with fresh read IDs', (
    tester,
  ) async {
    await open(tester);
    expect(find.text('1 秒后更新'), findsOneWidget);
    seconds = 30;
    rows = [entry(code: '999888')];
    await tester.pump(const Duration(seconds: 1));
    await tester.pump();
    expect(find.text('999 888'), findsOneWidget);
    expect(find.text('30 秒后更新'), findsOneWidget);
    seconds = 2147483669;
    rows = [entry(code: '111222')];
    await tester.pump(const Duration(seconds: 1));
    await tester.pump();
    expect(find.text('111 222'), findsOneWidget);
    final ids = calls
        .where((call) => call.method == 'secrets.totpList')
        .map((call) => (call.arguments as Map)['requestId'])
        .toList();
    expect(ids.toSet().length, ids.length);
    await remove(tester);
  });

  testWidgets(
    'background hides immediately resumes freshly and ignores late read',
    (tester) async {
      await open(tester);
      final pending = Completer<Object?>();
      override = (call) async =>
          call.method == 'secrets.totpList' ? pending.future : null;
      await tester.pump(const Duration(seconds: 1));
      binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      await tester.pump();
      expect(find.text('001 234'), findsNothing);
      pending.complete({
        'entries': [entry(code: '777777')],
        'unixSeconds': 5,
      });
      await tester.pump();
      override = null;
      rows = [entry(code: '654321')];
      binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pumpAndSettle();
      expect(find.text('654 321'), findsOneWidget);
      expect(find.text('777 777'), findsNothing);
      await remove(tester);
    },
  );

  testWidgets('stalled refresh hides expired snapshot before native response', (
    tester,
  ) async {
    await open(tester);
    final pending = Completer<Object?>();
    override = (call) async =>
        call.method == 'secrets.totpList' ? pending.future : null;
    seconds = 30;
    await tester.pump(const Duration(seconds: 1));
    expect(find.text('001 234'), findsNothing);
    seconds = 9000;
    await tester.pump(const Duration(seconds: 1));
    expect(find.text('001 234'), findsNothing);
    pending.complete({
      'entries': [entry(code: '456789')],
      'unixSeconds': seconds,
    });
    await tester.pump();
    await tester.pump();
    expect(find.text('456 789'), findsOneWidget);
    await remove(tester);
  });

  testWidgets(
    'malformed session event remains closed beyond poll until recovered event',
    (tester) async {
      await open(tester);
      await event(tester, {'epoch': 'invalid', 'unlocked': true});
      await tester.pump();
      expect(find.text('001 234'), findsNothing);
      await tester.pump(const Duration(seconds: 2));
      expect(find.text('001 234'), findsNothing);
      expect(find.text('无法监听秘密库状态，请重试。'), findsOneWidget);
      await event(tester, {'epoch': 8, 'unlocked': true});
      await tester.pumpAndSettle();
      expect(find.text('001 234'), findsOneWidget);
      await remove(tester);
    },
  );

  testWidgets('host lock and epoch replacement immediately hide old codes', (
    tester,
  ) async {
    await open(tester);
    await event(tester, {'epoch': 1, 'unlocked': true});
    status = vaultStatus(unlocked: false);
    await event(tester, {'epoch': 2, 'unlocked': false});
    expect(find.text('001 234'), findsNothing);
    status = vaultStatus(generation: 2);
    rows = [entry(code: '987654')];
    await event(tester, {'epoch': 3, 'unlocked': true});
    await tester.pumpAndSettle();
    expect(find.text('987 654'), findsOneWidget);
    await remove(tester);
  });

  testWidgets(
    'settings and add coverage hide underlying codes with nested owners',
    (tester) async {
      await open(tester);
      await add(tester);
      expect(find.text('001 234', skipOffstage: false), findsNothing);
      await tester.tap(find.byTooltip('取消添加'));
      await tester.pumpAndSettle();
      expect(find.text('001 234'), findsOneWidget);
      await tester.tap(find.byTooltip('秘密库设置'));
      await tester.pumpAndSettle();
      expect(find.text('001 234', skipOffstage: false), findsNothing);
      await remove(tester);
    },
  );

  testWidgets(
    'multi image selection requires choice and explicit confirmation only',
    (tester) async {
      override = (call) async => call.method == 'secrets.totpImagePreview'
          ? preview(candidates: 2)
          : null;
      await open(tester);
      await add(tester);
      await tester.tap(find.text('从图片读取'));
      await tester.pumpAndSettle();
      expect(
        calls.where((call) => call.method == 'secrets.totpCommit'),
        isEmpty,
      );
      expect(
        tester
            .widget<FilledButton>(find.widgetWithText(FilledButton, '确认添加'))
            .onPressed,
        isNull,
      );
      await tester.tap(find.text('Account 1'));
      await tester.pump();
      expect(
        calls.where((call) => call.method == 'secrets.totpCommit'),
        isEmpty,
      );
      await tester.tap(find.text('确认添加'));
      await tester.pumpAndSettle();
      final commit = calls.singleWhere(
        (call) => call.method == 'secrets.totpCommit',
      );
      expect((commit.arguments as Map)['candidateId'], '1');
      expect((commit.arguments as Map).keys.toSet(), {
        'previewId',
        'candidateId',
        'requestId',
      });
      expect(find.text('New account'), findsOneWidget);
      await remove(tester);
    },
  );

  testWidgets(
    'early image preview waits for matching foreground before explicit choice',
    (tester) async {
      final scan = Completer<Object?>();
      override = (call) async =>
          call.method == 'secrets.totpImagePreview' ? scan.future : null;
      await open(tester);
      await add(tester);
      await tester.tap(find.text('从图片读取'));
      await tester.pump();
      binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      await event(tester, {'epoch': 1, 'unlocked': true, 'foreground': false});
      final cancellations = calls
          .where((call) => call.method == 'secrets.totpCancel')
          .length;
      scan.complete(preview(candidates: 2));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 40));
      expect(find.text('确认账号'), findsNothing);
      expect(find.text('Account 0'), findsNothing);
      expect(
        calls.where((call) => call.method == 'secrets.totpCancel').length,
        cancellations,
      );
      expect(
        calls.where((call) => call.method == 'secrets.totpCommit'),
        isEmpty,
      );
      binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await event(tester, {'epoch': 1, 'unlocked': true});
      await tester.pump(const Duration(milliseconds: 40));
      await tester.pumpAndSettle();
      expect(find.text('确认账号'), findsOneWidget);
      expect(find.text('Account 0'), findsOneWidget);
      expect(find.text('Account 1'), findsOneWidget);
      expect(
        tester
            .widget<FilledButton>(find.widgetWithText(FilledButton, '确认添加'))
            .onPressed,
        isNull,
      );
      expect(
        calls.where((call) => call.method == 'secrets.totpCommit'),
        isEmpty,
      );
      await tester.tap(find.text('Account 1'));
      await tester.pump();
      expect(
        tester
            .widget<FilledButton>(find.widgetWithText(FilledButton, '确认添加'))
            .onPressed,
        isNotNull,
      );
      expect(
        calls.where((call) => call.method == 'secrets.totpCommit'),
        isEmpty,
      );
      await tester.tap(find.text('确认添加'));
      await tester.pumpAndSettle();
      expect(
        (calls
                .singleWhere((call) => call.method == 'secrets.totpCommit')
                .arguments
            as Map)['candidateId'],
        '1',
      );
      await remove(tester);
    },
  );

  testWidgets(
    'early picker preview retires on epoch lock or failed session stream',
    (tester) async {
      for (final streamFailure in [false, true]) {
        final scan = Completer<Object?>();
        override = (call) async =>
            call.method == 'secrets.totpImagePreview' ? scan.future : null;
        await open(tester);
        await add(tester);
        await tester.tap(find.text('从图片读取'));
        await tester.pump();
        binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
        await event(tester, {
          'epoch': 1,
          'unlocked': true,
          'foreground': false,
        });
        final cancellations = calls
            .where((call) => call.method == 'secrets.totpCancel')
            .length;
        scan.complete(preview(candidates: 2));
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 40));
        if (streamFailure) {
          await event(tester, {'epoch': 'invalid', 'unlocked': true});
        } else {
          await event(tester, {'epoch': 2, 'unlocked': false});
        }
        await tester.pump(const Duration(milliseconds: 40));
        expect(
          calls.where((call) => call.method == 'secrets.totpCancel').length,
          cancellations + 1,
        );
        binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
        await event(tester, {'epoch': 3, 'unlocked': true});
        await tester.pumpAndSettle();
        expect(find.text('确认账号'), findsNothing);
        expect(find.text('Account 0'), findsNothing);
        expect(
          calls.where((call) => call.method == 'secrets.totpCommit'),
          isEmpty,
        );
        await remove(tester);
      }
    },
  );

  testWidgets('early picker preview retires on route coverage or leaving', (
    tester,
  ) async {
    for (final cover in [true, false]) {
      final scan = Completer<Object?>();
      override = (call) async =>
          call.method == 'secrets.totpImagePreview' ? scan.future : null;
      await open(tester);
      await add(tester);
      await tester.tap(find.text('从图片读取'));
      await tester.pump();
      binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      await event(tester, {'epoch': 1, 'unlocked': true, 'foreground': false});
      final cancellations = calls
          .where((call) => call.method == 'secrets.totpCancel')
          .length;
      scan.complete(preview(candidates: 2));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 40));
      final navigator = Navigator.of(tester.element(find.byTooltip('取消添加')));
      if (cover) {
        unawaited(
          navigator.push<void>(
            MaterialPageRoute<void>(
              builder: (_) => const Scaffold(body: Text('Covered')),
            ),
          ),
        );
      } else {
        await tester.tap(find.byTooltip('取消添加'));
      }
      await tester.pumpAndSettle();
      expect(
        calls.where((call) => call.method == 'secrets.totpCancel').length,
        cancellations + 1,
      );
      binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await event(tester, {'epoch': 1, 'unlocked': true});
      if (cover) {
        navigator.pop();
        await tester.pumpAndSettle();
      } else {
        await tester.pumpAndSettle();
        await add(tester);
      }
      expect(find.text('确认账号'), findsNothing);
      expect(find.text('Account 0'), findsNothing);
      expect(find.widgetWithText(TextField, '密钥'), findsOneWidget);
      expect(
        calls.where((call) => call.method == 'secrets.totpCommit'),
        isEmpty,
      );
      await remove(tester);
    }
  });

  testWidgets(
    'early picker preview times out without resume and permits fresh retry',
    (tester) async {
      final scan = Completer<Object?>();
      override = (call) async =>
          call.method == 'secrets.totpImagePreview' ? scan.future : null;
      await open(tester);
      await add(tester);
      await tester.tap(find.text('从图片读取'));
      await tester.pump();
      binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      await event(tester, {'epoch': 1, 'unlocked': true, 'foreground': false});
      scan.complete(preview(candidates: 2));
      await tester.pump();
      await tester.pump(const Duration(seconds: 4));
      await tester.pump();
      expect(
        calls.where((call) => call.method == 'secrets.totpCancel'),
        hasLength(1),
      );
      expect(find.text('确认账号'), findsNothing);
      expect(find.text('Account 0'), findsNothing);
      binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await event(tester, {'epoch': 1, 'unlocked': true});
      await tester.pumpAndSettle();
      expect(find.text('请回到前台后重试。'), findsOneWidget);
      expect(find.widgetWithText(TextField, '密钥'), findsOneWidget);
      override = (call) async => call.method == 'secrets.totpImagePreview'
          ? {...preview(candidates: 2), 'previewId': 'preview-retry'}
          : null;
      await tester.tap(find.text('从图片读取'));
      await tester.pumpAndSettle();
      expect(find.text('确认账号'), findsOneWidget);
      final requests = calls
          .where((call) => call.method == 'secrets.totpImagePreview')
          .toList();
      expect(requests, hasLength(2));
      expect(
        (requests[0].arguments as Map)['requestId'],
        isNot((requests[1].arguments as Map)['requestId']),
      );
      expect(
        calls.where((call) => call.method == 'secrets.totpCommit'),
        isEmpty,
      );
      await remove(tester);
    },
  );

  testWidgets(
    'camera picker survives background but host expiration stays failure',
    (tester) async {
      final scan = Completer<Object?>();
      override = (call) async =>
          call.method == 'secrets.totpCameraPreview' ? scan.future : null;
      await open(tester);
      await add(tester);
      await tester.tap(find.text('相机扫码'));
      await tester.pump();
      final cancelBefore = calls
          .where((call) => call.method == 'secrets.cancel')
          .length;
      binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      await tester.pump();
      expect(
        calls.where((call) => call.method == 'secrets.cancel').length,
        cancelBefore,
      );
      binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      scan.complete(preview());
      await tester.pumpAndSettle();
      expect(find.text('确认账号'), findsOneWidget);
      expect(
        calls.where((call) => call.method == 'secrets.totpCommit'),
        isEmpty,
      );
      await remove(tester);
    },
  );

  testWidgets(
    'cancel pending picker cancels request and refuses late preview',
    (tester) async {
      final scan = Completer<Object?>();
      override = (call) async =>
          call.method == 'secrets.totpImagePreview' ? scan.future : null;
      await open(tester);
      await add(tester);
      await tester.tap(find.text('从图片读取'));
      await tester.pump();
      final request = calls.singleWhere(
        (call) => call.method == 'secrets.totpImagePreview',
      );
      await tester.tap(find.byTooltip('取消添加'));
      await tester.pumpAndSettle();
      expect(
        calls.any(
          (call) =>
              call.method == 'secrets.cancel' &&
              (call.arguments as Map)['requestId'] ==
                  (request.arguments as Map)['requestId'],
        ),
        isTrue,
      );
      scan.complete(preview());
      await tester.pumpAndSettle();
      expect(
        calls.where((call) => call.method == 'secrets.totpCancel'),
        isNotEmpty,
      );
      expect(
        calls.where((call) => call.method == 'secrets.totpCommit'),
        isEmpty,
      );
      await remove(tester);
    },
  );

  testWidgets('manual preview clears inputs and only commits host ticket', (
    tester,
  ) async {
    await open(tester);
    await add(tester);
    await tester.enterText(find.widgetWithText(TextField, '服务名称'), 'Fixture');
    await tester.enterText(find.widgetWithText(TextField, '账号'), 'alice');
    await tester.enterText(
      find.widgetWithText(TextField, '密钥'),
      'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ',
    );
    await tester.ensureVisible(find.text('检查并预览'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('检查并预览'));
    await tester.pumpAndSettle();
    expect(find.text('确认账号'), findsOneWidget);
    expect(calls.where((call) => call.method == 'secrets.totpCommit'), isEmpty);
    await tester.tap(find.text('重新选择'));
    await tester.pumpAndSettle();
    expect(
      tester
          .widget<TextField>(find.widgetWithText(TextField, '密钥'))
          .controller!
          .text,
      isEmpty,
    );
    await remove(tester);
  });

  testWidgets(
    'rename and delete require confirmation and copy sends only entry ID',
    (tester) async {
      await open(tester);
      await tester.tap(find.text('复制验证码'));
      await tester.pumpAndSettle();
      final copy = calls.singleWhere(
        (call) => call.method == 'secrets.totpCopy',
      );
      expect((copy.arguments as Map).keys.toSet(), {'entryId', 'requestId'});
      await tester.tap(find.byTooltip('Test service更多操作'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('重命名'));
      await tester.pumpAndSettle();
      expect(find.text('001 234', skipOffstage: false), findsNothing);
      await tester.enterText(find.byType(TextField), 'Renamed');
      await tester.tap(find.text('确认重命名'));
      await tester.pumpAndSettle();
      expect(find.text('Renamed'), findsOneWidget);
      await tester.tap(find.byTooltip('Renamed更多操作'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('删除'));
      await tester.pumpAndSettle();
      expect(
        calls.where((call) => call.method == 'secrets.totpDelete'),
        isEmpty,
      );
      await tester.tap(find.text('取消'));
      await tester.pumpAndSettle();
      expect(find.text('Renamed'), findsOneWidget);
      await tester.tap(find.byTooltip('Renamed更多操作'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('删除'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('确认删除'));
      await tester.pumpAndSettle();
      expect(find.text('还没有验证码。添加账号后可在此查看和复制。'), findsOneWidget);
      await remove(tester);
    },
  );

  testWidgets(
    'missing vault offers shared create/import and cancel renders no business',
    (tester) async {
      status = vaultStatus(exists: false, unlocked: false, generation: 0);
      await open(tester);
      expect(find.text('设置秘密库'), findsOneWidget);
      expect(find.text('导入备份'), findsOneWidget);
      await tester.tap(find.text('取消'));
      await tester.pumpAndSettle();
      expect(find.text('Authenticator'), findsNothing);
      expect(calls.where((call) => call.method == 'secrets.totpList'), isEmpty);
    },
  );

  testWidgets(
    'locked vault uses shared protected password then returns to business',
    (tester) async {
      status = vaultStatus(unlocked: false);
      await open(tester);
      expect(find.text('解锁秘密库'), findsWidgets);
      await tester.enterText(find.byType(TextField), 'fixture-password');
      await tester.tap(find.widgetWithText(FilledButton, '解锁秘密库'));
      await tester.pumpAndSettle();
      expect(find.text('001 234'), findsOneWidget);
      await remove(tester);
    },
  );

  testWidgets(
    'narrow large text keyboard leaves manual and cancel actions reachable',
    (tester) async {
      tester.view.physicalSize = const Size(320, 760);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      await tester.pumpWidget(app(scale: 2, keyboard: 260));
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();
      await add(tester);
      await tester.ensureVisible(find.text('检查并预览'));
      await tester.pump();
      expect(tester.takeException(), isNull);
      await tester.ensureVisible(find.text('取消添加'));
      await tester.tap(find.text('取消添加'));
      await tester.pumpAndSettle();
      expect(find.text('Authenticator'), findsOneWidget);
      await remove(tester);
    },
  );

  testWidgets(
    'uncertain commit acknowledges without claiming cancellation rolled back',
    (tester) async {
      override = (call) async {
        if (call.method == 'secrets.totpCommit')
          throw PlatformException(code: 'VAULT_COMMIT_UNCERTAIN');
        return null;
      };
      await open(tester);
      await add(tester);
      await tester.tap(find.text('从图片读取'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('确认添加'));
      await tester.pumpAndSettle();
      expect(find.text('核验操作结果'), findsOneWidget);
      expect(find.textContaining('无法确认秘密库是否保存完成'), findsOneWidget);
      await tester.tap(find.text('知道了'));
      await tester.pumpAndSettle();
      await remove(tester);
    },
  );
}

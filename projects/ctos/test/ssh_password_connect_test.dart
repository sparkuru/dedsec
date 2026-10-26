import 'dart:async';
import 'dart:convert';

import 'package:ctos/main.dart';
import 'package:ctos/ssh/api.dart';
import 'package:ctos/ssh/password_connect_page.dart';
import 'package:ctos/ui/ctos_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test(
    'SSH field validators preserve exact text and reject malformed input',
    () {
      expect(sshHostError('example.invalid'), isNull);
      expect(sshHostError('https://example.invalid'), isNotNull);
      expect(sshHostError(' example.invalid'), isNotNull);
      expect(sshPortError('22'), isNull);
      expect(sshPortError('0'), isNotNull);
      expect(sshPortError('65536'), isNotNull);
      expect(sshPortError(' 22'), isNotNull);
      expect(sshUsernameError(' 用户😀 '), isNull);
      expect(sshUsernameError('bad\u0085'), isNotNull);
      expect(sshUsernameError('\ud800'), isNotNull);
      expect(sshUsernameError(List.filled(128, 'é').join()), isNotNull);
      expect(sshPasswordError(' secret\n '), isNull);
      expect(sshPasswordError('bad\u0000'), isNotNull);
      expect(sshPasswordError('\udc00'), isNotNull);
      expect(sshPasswordError(List.filled(8193, 'x').join()), isNotNull);
      expect(
        sshConnectionError(const FormatException('untrusted response')),
        'SSH 连接响应无效，请重新打开连接页面。',
      );
      expect(
        sshConnectionError(PlatformException(code: 'SSH_HOST_KEY_CHANGED')),
        '主机密钥与已保存记录不同，连接已拒绝。',
      );
    },
  );

  test('SSH events have a closed metadata-only protocol', () {
    final good = _ticket('attempt');
    final event = SshEvent.parse(good) as SshHostKeyEvent;
    expect(event.requestId, 'attempt');
    expect(event.host, 'server.invalid');
    expect(event.port, 2222);
    expect(event.remainingMillis, 30000);
    for (final bad in [
      {...good, 'password': 'must-not-display'},
      {...good, 'remainingMillis': 30001},
      {...good, 'port': 22.0},
      {...good, 'binding': 'not-a-binding'},
      {...good, 'fingerprint': 'arbitrary text'},
      {...good, 'host': 'host\ntext'},
      {
        'type': 'data',
        'requestId': 'attempt',
        'bytes': [1],
      },
      {'type': 'state', 'requestId': 'attempt', 'state': 'running'},
      {
        'type': 'state',
        'requestId': 'attempt',
        'state': 'failed',
        'reason': 'secret text',
      },
    ]) {
      expect(() => SshEvent.parse(bad), throwsFormatException);
    }
    expect(
      SshEvent.parse({
        'type': 'hostKeyDismissed',
        'requestId': 'attempt',
        'ticketId': 'ticket-a',
      }),
      isA<SshHostKeyDismissed>(),
    );
    expect(
      () => SshConnectionDefaults.parse({
        'host': '',
        'port': 22,
        'username': '',
        'password': 'no',
      }),
      throwsFormatException,
    );
  });

  testWidgets(
    'protected form is created only after shared unlock and protection',
    (tester) async {
      final host = _SshHost(tester);
      host.protection = Completer<Object?>();
      await host.mount();
      await tester.tap(find.text('Open SSH'));
      await tester.pump();
      expect(find.byKey(const Key('ssh-password')), findsNothing);
      expect(host.defaultsCalls, isEmpty);
      expect(
        host.calls.where((call) => call.method == 'secrets.status').length,
        2,
      );
      host.protection!.complete({'owner': 'protected-ssh'});
      await host.advance();
      expect(find.byKey(const Key('ssh-password')), findsOneWidget);
      final password = tester.widget<TextFormField>(
        find.byKey(const Key('ssh-password')),
      );
      final field = tester.widget<TextField>(
        find.descendant(
          of: find.byKey(const Key('ssh-password')),
          matching: find.byType(TextField),
        ),
      );
      expect(password.enabled, isTrue);
      expect(field.obscureText, isTrue);
      expect(field.autocorrect, isFalse);
      expect(field.enableSuggestions, isFalse);
      expect(field.enableIMEPersonalizedLearning, isFalse);
      expect(find.text('保存主机信息；密码仅本次使用。'), findsOneWidget);
      await host.leave();
      expect(host.releases, ['protected-ssh']);
      expect(find.byKey(const Key('ssh-password')), findsNothing);
    },
  );

  testWidgets('SSH picker is lazy and preserves local entries', (tester) async {
    final host = _SshHost(tester);
    await host.mount(root: true);
    expect(
      host.calls.where((call) => call.method.startsWith('secrets.')),
      isEmpty,
    );
    await tester.tap(find.text('终端').last);
    await host.advance();
    expect(find.byKey(const Key('terminal-app-entry')), findsOneWidget);
    expect(find.byKey(const Key('terminal-root-entry')), findsOneWidget);
    expect(find.byKey(const Key('terminal-ssh-entry')), findsOneWidget);
    expect(
      tester
          .widget<ListTile>(find.byKey(const Key('terminal-root-entry')))
          .onTap,
      isNull,
    );
    await tester.ensureVisible(find.byKey(const Key('terminal-ssh-entry')));
    await tester.tap(find.byKey(const Key('terminal-ssh-entry')));
    await host.advance();
    expect(find.byKey(const Key('ssh-password')), findsOneWidget);
    await host.leave();
    await tester.pumpAndSettle();
    expect(
      tester
          .widget<ListTile>(find.byKey(const Key('terminal-app-entry')))
          .onTap,
      isNotNull,
    );
    await tester.tap(find.byKey(const Key('terminal-app-entry')));
    await tester.pump();
    expect(
      host.calls
          .where((call) => call.method == 'terminalStart')
          .single
          .arguments,
      containsPair('root', false),
    );
    await host.remove();
  });

  testWidgets('invalid form never starts and password is absent after submit', (
    tester,
  ) async {
    final host = _SshHost(tester);
    await host.open();
    await tester.enterText(find.byKey(const Key('ssh-host')), 'https://bad');
    await host.tapConnect();
    expect(host.connectCalls, isEmpty);
    await host.fill(username: ' 用户😀 ', password: ' secret password ');
    await host.tapConnect();
    expect(host.connectCalls, hasLength(1));
    final request = host.connectCalls.single;
    expect(request['host'], 'server.invalid');
    expect(request['port'], 2222);
    expect(request['username'], ' 用户😀 ');
    expect(request['password'], ' secret password ');
    expect(request['columns'], 81);
    expect(request['rows'], 25);
    expect(host.passwordController.text, isEmpty);
    expect(host.calls.where((call) => call.method == 'terminalWrite'), isEmpty);
    await host.tapConnect();
    expect(host.connectCalls, hasLength(1));
    await host.leave();
    expect(
      host.cancelled.where((id) => id == request['requestId']),
      hasLength(1),
    );
    host.completeConnect(error: PlatformException(code: 'SSH_CANCELLED'));
    await host.advance();
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'authentication retry uses a fresh request and ignores stale state',
    (tester) async {
      final host = _SshHost(tester);
      await host.open();
      await host.fill();
      await host.tapConnect();
      final first = host.connectCalls.single['requestId'] as String;
      host.completeConnect(
        error: PlatformException(
          code: 'SSH_AUTH_REJECTED',
          message: 'must-not-display',
        ),
      );
      await host.advance();
      expect(find.text('SSH 登录被拒绝，请检查用户名和密码。'), findsOneWidget);
      expect(find.text('must-not-display'), findsNothing);
      expect(host.passwordController.text, isEmpty);
      await tester.enterText(
        find.byKey(const Key('ssh-password')),
        'second password',
      );
      await host.tapConnect();
      final second = host.connectCalls.last['requestId'] as String;
      expect(second, isNot(first));
      await host.emit({
        'type': 'state',
        'requestId': first,
        'state': 'connected',
      });
      expect(find.byKey(const Key('ssh-password')), findsOneWidget);
      await host.emit({
        'type': 'state',
        'requestId': second,
        'state': 'connected',
      });
      await host.advance();
      expect(find.byKey(const Key('ssh-password')), findsNothing);
      expect(host.cancelled, isNot(contains(second)));
      host.completeConnect(error: PlatformException(code: 'LATE_FAILURE'));
      await host.advance();
      expect(tester.takeException(), isNull);
      expect(host.releases, isNotEmpty);
    },
  );

  testWidgets(
    'real host ticket decision is exact and does not await connect RPC',
    (tester) async {
      final host = _SshHost(tester);
      await host.open();
      await host.fill();
      await host.tapConnect();
      final id = host.connectCalls.single['requestId'] as String;
      final ticket = _ticket(id);
      await host.emit(ticket);
      await host.advance();
      expect(find.text('确认主机密钥'), findsOneWidget);
      expect(find.text('server.invalid:2222'), findsOneWidget);
      expect(find.text(ticket['fingerprint'] as String), findsOneWidget);
      await host.emit(ticket);
      expect(find.text('确认主机密钥'), findsOneWidget);
      await tester.tap(find.text('信任并保存'));
      await host.advance();
      expect(host.decisions, [
        {
          'requestId': id,
          'ticketId': 'ticket-a',
          'binding': List.filled(64, 'a').join(),
          'accept': true,
        },
      ]);
      expect(host.connectResults.single.isCompleted, isFalse);
      host.completeConnect();
      await host.advance();
      expect(find.text('Open SSH'), findsOneWidget);
      expect(host.cancelled, isNot(contains(id)));
    },
  );

  testWidgets('reject and expired host prompts never auto-approve', (
    tester,
  ) async {
    for (final expire in [false, true]) {
      final host = _SshHost(tester);
      await host.open();
      await host.fill();
      await host.tapConnect();
      final id = host.connectCalls.single['requestId'] as String;
      await host.emit({
        ..._ticket(id),
        'remainingMillis': expire ? 500 : 30000,
      });
      await host.advance(milliseconds: 100);
      if (expire) {
        await tester.pump(const Duration(milliseconds: 501));
      } else {
        await tester.tap(find.text('拒绝'));
      }
      await host.advance();
      expect(host.decisions.single['accept'], isFalse);
      host.completeConnect(
        error: PlatformException(code: 'SSH_HOSTKEY_DENIED'),
      );
      await host.advance();
      expect(find.text('未信任此主机，连接已结束。'), findsOneWidget);
      await host.remove();
    }
  });

  testWidgets('dismissed and foreign tickets cannot submit a late approval', (
    tester,
  ) async {
    final host = _SshHost(tester);
    await host.open();
    await host.fill();
    await host.tapConnect();
    final id = host.connectCalls.single['requestId'] as String;
    await host.emit(_ticket('foreign'));
    expect(find.text('确认主机密钥'), findsNothing);
    await host.emit(_ticket(id));
    await host.advance();
    final stale = tester
        .widget<FilledButton>(find.widgetWithText(FilledButton, '信任并保存'))
        .onPressed!;
    await host.emit({
      'type': 'hostKeyDismissed',
      'requestId': id,
      'ticketId': 'ticket-a',
    });
    await host.advance();
    stale();
    await host.advance();
    expect(host.decisions, isEmpty);
    expect(tester.takeException(), isNull);
    await host.leave();
    host.completeConnect(error: PlatformException(code: 'SSH_CANCELLED'));
    await host.advance();
  });

  testWidgets(
    'changed and revoked hosts are failures without another approval',
    (tester) async {
      final host = _SshHost(tester);
      await host.open();
      await host.fill();
      await host.tapConnect();
      final id = host.connectCalls.single['requestId'] as String;
      await host.emit({
        'type': 'state',
        'requestId': id,
        'state': 'failed',
        'reason': 'SSH_HOSTKEY_CHANGED',
      });
      expect(find.text('主机密钥与已保存记录不同，连接已拒绝。'), findsOneWidget);
      expect(find.text('信任并保存'), findsNothing);
      expect(host.decisions, isEmpty);
      host.completeConnect(
        error: PlatformException(code: 'SSH_HOSTKEY_CHANGED'),
      );
      await host.advance();
      await tester.enterText(
        find.byKey(const Key('ssh-password')),
        'retry password',
      );
      await host.tapConnect();
      final retry = host.connectCalls.last['requestId'] as String;
      await host.emit({
        'type': 'state',
        'requestId': retry,
        'state': 'failed',
        'reason': 'SSH_HOSTKEY_REVOKED',
      });
      expect(find.text('主机密钥已被撤销，连接已拒绝。'), findsOneWidget);
      expect(find.text('信任并保存'), findsNothing);
      host.completeConnect(
        error: PlatformException(code: 'SSH_HOSTKEY_REVOKED'),
      );
      await host.remove();
    },
  );

  testWidgets(
    'stream failure clears drafts and cancels the pending attempt once',
    (tester) async {
      final host = _SshHost(tester);
      await host.open();
      await host.fill();
      await host.tapConnect();
      final id = host.connectCalls.single['requestId'] as String;
      final controller = host.passwordController;
      await host.emit({
        'type': 'state',
        'requestId': id,
        'state': 'failed',
        'password': 'leaked-secret',
      });
      await host.emitEnd();
      await host.advance();
      expect(controller.text, isEmpty);
      expect(find.text('leaked-secret'), findsNothing);
      expect(find.text('连接状态不可用，请返回后重新打开 SSH 连接。'), findsOneWidget);
      expect(host.cancelled.where((value) => value == id), hasLength(1));
      host.completeConnect(error: PlatformException(code: 'SSH_CANCELLED'));
      await host.remove();
    },
  );

  testWidgets(
    'background and route coverage clear sensitive data without self-cancelling review',
    (tester) async {
      final host = _SshHost(tester);
      await host.open();
      await host.fill();
      final controller = host.passwordController;
      final idleId = host.defaultsCalls.single['requestId'] as String;
      for (final state in [
        AppLifecycleState.inactive,
        AppLifecycleState.hidden,
        AppLifecycleState.paused,
      ]) {
        tester.binding.handleAppLifecycleStateChanged(state);
      }
      await host.advance();
      expect(controller.text, isEmpty);
      expect(host.cancelled, contains(idleId));
      _resumeApp(tester);
      await tester.pump();
      expect(
        tester
            .widget<OutlinedButton>(find.byKey(const Key('ssh-reload')))
            .onPressed,
        isNotNull,
      );
      await tester.tap(find.byKey(const Key('ssh-reload')));
      await host.advance();
      expect(host.defaultsCalls, hasLength(2));
      expect(host.defaultsCalls.last['requestId'], isNot(idleId));
      await host.fill();
      await host.tapConnect();
      final id = host.connectCalls.single['requestId'] as String;
      await host.emit(_ticket(id));
      await host.advance();
      expect(host.cancelled, isNot(contains(id)));
      final dialogContext = tester.element(find.text('确认主机密钥'));
      Navigator.of(dialogContext).push(
        MaterialPageRoute<void>(
          builder: (_) => const Scaffold(body: Text('Covered')),
        ),
      );
      await host.advance();
      expect(host.cancelled.where((value) => value == id), hasLength(1));
      expect(host.decisions, isEmpty);
      host.completeConnect(error: PlatformException(code: 'SSH_CANCELLED'));
      await host.remove();
    },
  );

  testWidgets(
    'idle session revocation clears draft and old default events do not cancel own connect',
    (tester) async {
      final host = _SshHost(tester);
      await host.open();
      await host.fill();
      final idleId = host.defaultsCalls.single['requestId'] as String;
      final controller = host.passwordController;
      await host.emit({
        'type': 'state',
        'requestId': idleId,
        'state': 'cancelled',
        'reason': 'VAULT_LOCKED',
      });
      expect(controller.text, isEmpty);
      expect(find.byKey(const Key('ssh-password')), findsNothing);
      await tester.tap(find.byKey(const Key('ssh-reload')));
      await host.advance();
      final nextIdle = host.defaultsCalls.last['requestId'] as String;
      await host.fill();
      await host.tapConnect();
      final id = host.connectCalls.single['requestId'] as String;
      await host.emit({
        'type': 'state',
        'requestId': nextIdle,
        'state': 'cancelled',
        'reason': 'VAULT_CHANGED',
      });
      expect(host.cancelled, isNot(contains(id)));
      expect(find.text('正在连接…'), findsOneWidget);
      await host.leave();
      host.completeConnect(error: PlatformException(code: 'SSH_CANCELLED'));
      await host.remove();
    },
  );

  testWidgets(
    '320 dp keyboard and large text keep form and review actions reachable',
    (tester) async {
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPhysicalSize);
      for (final size in [const Size(320, 568), const Size(1200, 800)]) {
        tester.view.physicalSize = size;
        final host = _SshHost(tester);
        await host.open(scale: 2, keyboard: size.width == 320 ? 220 : 0);
        await host.fill();
        await host.tapConnect();
        final id = host.connectCalls.single['requestId'] as String;
        await host.emit(_ticket(id));
        await host.advance();
        await tester.ensureVisible(find.text('信任并保存'));
        expect(find.text('信任并保存').hitTestable(), findsOneWidget);
        expect(
          tester.getBottomRight(find.text('信任并保存')).dx,
          lessThanOrEqualTo(size.width),
        );
        expect(tester.takeException(), isNull);
        expect(find.text('拒绝').hitTestable(), findsOneWidget);
        await tester.tap(find.text('拒绝'));
        await host.advance();
        host.completeConnect(
          error: PlatformException(code: 'SSH_HOSTKEY_DENIED'),
        );
        await host.advance();
        await host.leave();
        await host.remove();
      }
    },
  );

  testWidgets(
    'mismatched connect response fails closed and never establishes a UI session',
    (tester) async {
      final host = _SshHost(tester);
      await host.open();
      await host.fill();
      await host.tapConnect();
      host.connectResults.last.complete({
        'requestId': 'wrong-request',
        'status': 'connected',
      });
      await host.advance();
      expect(find.byKey(const Key('ssh-password')), findsNothing);
      expect(find.text('SSH 连接响应无效，请重新打开连接页面。'), findsOneWidget);
      expect(find.text('SSH 连接响应无效，请重新打开连接页面。').hitTestable(), findsOneWidget);
      await host.remove();
    },
  );
}

Map<String, Object> _ticket(String id) => {
  'type': 'hostKey',
  'requestId': id,
  'ticketId': 'ticket-a',
  'binding': List.filled(64, 'a').join(),
  'host': 'server.invalid',
  'port': 2222,
  'keyType': 'ssh-ed25519',
  'fingerprint': 'SHA256:${List.filled(43, 'A').join()}',
  'remainingMillis': 30000,
};

void _resumeApp(WidgetTester tester) {
  if (tester.binding.lifecycleState == AppLifecycleState.paused) {
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
  }
  if (tester.binding.lifecycleState == AppLifecycleState.hidden) {
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
  }
  if (tester.binding.lifecycleState != AppLifecycleState.resumed) {
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
  }
}

class _SshHost {
  _SshHost(this.tester);
  final WidgetTester tester;
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final calls = <MethodCall>[];
  final connectResults = <Completer<Map<String, Object>>>[];
  Completer<Object?>? protection;
  late SshPasswordApi api;
  int _sequence = 0;
  bool _root = false;

  List<Map<dynamic, dynamic>> get defaultsCalls => _arguments('ssh.defaults');
  List<Map<dynamic, dynamic>> get connectCalls =>
      _arguments('ssh.passwordConnect');
  List<Map<dynamic, dynamic>> get decisions =>
      _arguments('ssh.hostKeyDecision');
  List<String> get cancelled =>
      _arguments('ssh.cancel').map((a) => a['requestId'] as String).toList();
  List<String> get releases => _arguments('secrets.protect')
      .where((a) => a['action'] == 'release')
      .map((a) => a['owner'] as String)
      .toList();
  List<Map<dynamic, dynamic>> _arguments(String method) => calls
      .where((call) => call.method == method)
      .map((call) => call.arguments as Map)
      .toList();

  TextEditingController get passwordController => tester
      .widget<TextFormField>(find.byKey(const Key('ssh-password')))
      .controller!;

  Future<Object?> _handle(MethodCall call) async {
    calls.add(call);
    switch (call.method) {
      case 'secrets.status':
        return {
          'state': 'unlocked',
          'exists': true,
          'generation': 1,
          'quickUnlockEnabled': false,
          'quickUnlockAvailable': false,
          'backgroundTimeoutSeconds': 30,
        };
      case 'secrets.protect':
        final args = call.arguments as Map;
        return args['action'] == 'release'
            ? null
            : protection?.future ?? {'owner': 'protected-ssh'};
      case 'ssh.defaults':
        return {'host': '', 'port': 22, 'username': ''};
      case 'ssh.passwordConnect':
        final pending = Completer<Map<String, Object>>();
        connectResults.add(pending);
        return pending.future;
      case 'ssh.cancel':
        return {'cancelled': true};
      case 'ssh.hostKeyDecision':
        return {'accepted': true};
      case 'rootAuto':
        return jsonEncode({'root': false, 'attempted': false});
      case 'snapshot':
        return jsonEncode({
          'root': false,
          'networks': [],
          'kernel': {
            'source': 'test',
            'elapsed': 1000,
            'interfaces': [],
            'routes': '',
          },
        });
      case 'deviceSnapshot':
        return '{}';
      case 'terminalStart':
        return 'app · PTY';
      case 'terminalWrite':
      case 'terminalResize':
      case 'terminalStop':
        return null;
      default:
        throw PlatformException(code: 'UNEXPECTED');
    }
  }

  Future<void> mount({
    bool root = false,
    double scale = 1,
    double keyboard = 0,
  }) async {
    _root = root;
    api = SshPasswordApi(requestIdFactory: () => 'request_${++_sequence}');
    messenger.setMockMethodCallHandler(
      const MethodChannel('ctos/native'),
      _handle,
    );
    messenger.setMockMethodCallHandler(
      const MethodChannel('ctos/ssh'),
      (_) async => null,
    );
    messenger.setMockMethodCallHandler(
      const MethodChannel('ctos/terminal'),
      (_) async => null,
    );
    addTearDown(() {
      _resumeApp(tester);
      messenger.setMockMethodCallHandler(
        const MethodChannel('ctos/native'),
        null,
      );
      messenger.setMockMethodCallHandler(const MethodChannel('ctos/ssh'), null);
      messenger.setMockMethodCallHandler(
        const MethodChannel('ctos/terminal'),
        null,
      );
    });
    await tester.pumpWidget(
      root
          ? const CtosApp()
          : MaterialApp(
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
                    onPressed: () => showPasswordSshConnect(
                      context,
                      api: api,
                      columns: 81,
                      rows: 25,
                    ),
                    child: const Text('Open SSH'),
                  ),
                ),
              ),
            ),
    );
    await advance();
  }

  Future<void> open({double scale = 1, double keyboard = 0}) async {
    await mount(scale: scale, keyboard: keyboard);
    await tester.tap(find.text('Open SSH'));
    await advance();
  }

  Future<void> advance({int milliseconds = 400}) async {
    await tester.pump();
    await tester.pump(Duration(milliseconds: milliseconds));
    await tester.pump();
  }

  Future<void> fill({
    String username = 'alice',
    String password = 'synthetic password',
  }) async {
    for (final field in {
      'ssh-host': 'server.invalid',
      'ssh-port': '2222',
      'ssh-username': username,
      'ssh-password': password,
    }.entries) {
      await tester.ensureVisible(find.byKey(Key(field.key)));
      await tester.enterText(find.byKey(Key(field.key)), field.value);
    }
    // Let EditableText finish its queued caret-reveal scroll before seeking actions.
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
  }

  Future<void> tapConnect() async {
    final action = find.byKey(const Key('ssh-connect'));
    await tester.ensureVisible(action);
    await tester.pump(const Duration(milliseconds: 300));
    await tester.ensureVisible(action);
    await tester.pump();
    expect(action.hitTestable(), findsOneWidget);
    await tester.tap(action);
    await tester.pump();
  }

  Future<void> leave() async {
    final action = find.byKey(const Key('ssh-cancel'));
    await tester.scrollUntilVisible(
      action,
      150,
      scrollable: find
          .descendant(
            of: find.byType(SshPasswordConnectPage),
            matching: find.byType(Scrollable),
          )
          .first,
    );
    await tester.pump(const Duration(milliseconds: 300));
    expect(action.hitTestable(), findsOneWidget);
    await tester.tap(action);
    await advance();
  }

  void completeConnect({Object? error}) {
    final pending = connectResults.last;
    if (error != null) {
      pending.completeError(error);
    } else {
      pending.complete({
        'requestId': connectCalls.last['requestId'] as String,
        'status': 'connected',
      });
    }
  }

  Future<void> emit(Map<String, Object> event) async {
    await messenger.handlePlatformMessage(
      'ctos/ssh',
      const StandardMethodCodec().encodeSuccessEnvelope(event),
      null,
    );
    await tester.pump();
  }

  Future<void> emitEnd() async {
    await messenger.handlePlatformMessage('ctos/ssh', null, null);
    await tester.pump();
  }

  Future<void> remove() async {
    await tester.pumpWidget(const SizedBox());
    await advance();
    if (_root) await tester.pump(const Duration(seconds: 3));
  }
}

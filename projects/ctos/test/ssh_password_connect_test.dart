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

  testWidgets(
    'password retry prepares its exact endpoint with no retained password or automatic submit',
    (tester) async {
      final host = _SshHost(tester)..saved = true;
      await host.mount(
        connectOnOpen: true,
        reconnect: const SshReconnectTarget(
          mode: SshReconnectMode.password,
          host: 'server.invalid',
          port: 2222,
          username: 'alice',
        ),
      );
      await tester.tap(find.text('Open SSH'));
      await host.advance();
      expect(
        tester
            .widget<TextFormField>(find.byKey(const Key('ssh-host')))
            .controller!
            .text,
        'server.invalid',
      );
      expect(host.passwordController.text, isEmpty);
      expect(
        tester
            .widget<CheckboxListTile>(
              find.byKey(const Key('ssh-use-saved-password')),
            )
            .value,
        isFalse,
      );
      expect(
        tester
            .widget<CheckboxListTile>(
              find.byKey(const Key('ssh-save-password')),
            )
            .value,
        isFalse,
      );
      expect(host.statusCalls.single, containsPair('host', 'server.invalid'));
      expect(host.connectResults, isEmpty);
      await host.leave();
      await host.remove();
    },
  );

  testWidgets(
    'root retry opens the protected prepared form only after an explicit tap',
    (tester) async {
      final host = _SshHost(tester);
      await host.mount(root: true);
      await host.messenger.handlePlatformMessage(
        'ctos/terminal',
        const StandardMethodCodec().encodeSuccessEnvelope({
          'type': 'snapshot',
          'generation': 7,
          'state': 'closed',
          'mode': 'ssh',
          'remote': true,
          'reason': 'SSH_SCREEN_LOCKED',
          'bytes': Uint8List(0),
          'truncated': false,
          'reconnect': {
            'mode': 'password',
            'host': 'server.invalid',
            'port': 2222,
            'username': 'alice',
          },
        }),
        null,
      );
      await tester.tap(find.text('终端').last);
      await host.advance();
      expect(find.byKey(const Key('ssh-reconnect')), findsOneWidget);
      expect(host.defaultsCalls, isEmpty);
      expect(host.connectResults, isEmpty);
      await tester.tap(find.byKey(const Key('ssh-reconnect')));
      await host.advance();
      expect(
        tester
            .widget<TextFormField>(find.byKey(const Key('ssh-host')))
            .controller!
            .text,
        'server.invalid',
      );
      expect(host.passwordController.text, isEmpty);
      expect(host.connectResults, isEmpty);
      expect(
        host.calls
            .map((call) => call.method)
            .toList()
            .indexOf('secrets.status'),
        lessThan(
          host.calls
              .map((call) => call.method)
              .toList()
              .indexOf('ssh.defaults'),
        ),
      );
      await host.leave();
      await host.remove();
    },
  );

  testWidgets(
    'key retry reloads the exact identity and requires explicit connect',
    (tester) async {
      final host = _SshHost(tester)..libraryValues = _reconnectLibrary();
      await host.mount(
        reconnect: const SshReconnectTarget(
          mode: SshReconnectMode.key,
          host: 'server.invalid',
          port: 2222,
          username: 'alice',
          keyId: 'key_a',
        ),
      );
      await tester.tap(find.text('Open SSH'));
      await host.advance();
      expect(find.text('Current fixture key'), findsOneWidget);
      expect(find.byKey(const Key('ssh-password')), findsNothing);
      expect(
        host.calls.where((call) => call.method == 'ssh.library'),
        hasLength(1),
      );
      expect(host.connectResults, isEmpty);
      await host.tapConnect();
      expect(host.identityCalls.single, containsPair('keyId', 'key_a'));
      expect(host.identityCalls.single, containsPair('host', 'server.invalid'));
      host.completeConnect(error: PlatformException(code: 'SSH_AUTH_REJECTED'));
      await host.advance();
      await host.leave();
      await host.remove();
    },
  );

  for (final mode in [SshReconnectMode.key, SshReconnectMode.config]) {
    testWidgets(
      'one root reconnect tap submits one fresh ${mode.name} connection',
      (tester) async {
        final host = _SshHost(tester)..libraryValues = _reconnectLibrary();
        await host.mount(root: true);
        await host.messenger.handlePlatformMessage(
          'ctos/terminal',
          const StandardMethodCodec().encodeSuccessEnvelope({
            'type': 'snapshot',
            'generation': 7,
            'state': 'closed',
            'mode': 'ssh',
            'remote': true,
            'reason': 'SSH_SCREEN_LOCKED',
            'bytes': Uint8List(0),
            'truncated': false,
            'reconnect': {
              'mode': mode.name,
              'host': 'server.invalid',
              'port': 2222,
              'username': 'alice',
              'keyId': 'key_a',
              if (mode == SshReconnectMode.config) 'configAlias': 'fixture',
            },
          }),
          null,
        );
        await tester.tap(find.text('终端').last);
        await host.advance();
        expect(host.connectResults, isEmpty);
        await tester.tap(find.byKey(const Key('ssh-reconnect')));
        await host.advance();
        expect(host.identityCalls, hasLength(1));
        expect(host.identityCalls.single['keyId'], 'key_a');
        expect(
          host.calls.where((call) => call.method == 'ssh.library'),
          hasLength(1),
        );
        if (mode == SshReconnectMode.config) {
          expect(
            host.identityCalls.single['binding'],
            List.filled(64, 'b').join(),
          );
        }
        final requestId = host.identityCalls.single['requestId'] as String;
        await host.emit(_ticket(requestId));
        expect(find.text('拒绝'), findsOneWidget);
        expect(host.decisions, isEmpty);
        await tester.tap(find.text('拒绝'));
        await host.advance();
        expect(host.decisions.single['accept'], isFalse);
        host.completeConnect(error: PlatformException(code: 'VAULT_CHANGED'));
        await host.advance();
        await tester.ensureVisible(find.byKey(const Key('ssh-reload')));
        await tester.tap(find.byKey(const Key('ssh-reload')));
        await host.advance();
        expect(find.text('Current fixture key'), findsOneWidget);
        expect(host.identityCalls, hasLength(1));
        await host.leave();
        await host.remove();
      },
    );
  }

  testWidgets(
    'background cancels a pending retry library read and discards its late metadata',
    (tester) async {
      final delayedLibrary = Completer<Map<String, Object>>();
      final host = _SshHost(tester)..pendingLibrary = delayedLibrary;
      await host.mount(
        connectOnOpen: true,
        reconnect: const SshReconnectTarget(
          mode: SshReconnectMode.key,
          host: 'server.invalid',
          port: 2222,
          username: 'alice',
          keyId: 'key_a',
        ),
      );
      await tester.tap(find.text('Open SSH'));
      await host.advance();
      final libraryCall = host.calls.singleWhere(
        (call) => call.method == 'ssh.library',
      );
      final readId = (libraryCall.arguments as Map)['requestId'] as String;
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      await host.advance();
      expect(host.cancelled, contains(readId));
      _resumeApp(tester);
      await host.advance();
      delayedLibrary.complete(_reconnectLibrary());
      await host.advance();
      expect(find.text('Current fixture key'), findsNothing);
      expect(find.byKey(const Key('ssh-host')), findsNothing);
      expect(find.byKey(const Key('ssh-connect')), findsNothing);
      expect(find.byKey(const Key('ssh-reload')), findsOneWidget);
      expect(host.connectResults, isEmpty);
      expect(
        host.calls.where((call) => call.method == 'ssh.library'),
        hasLength(1),
      );
      expect(tester.takeException(), isNull);
      await tester.tap(find.byKey(const Key('ssh-reload')));
      await host.advance();
      expect(find.text('Current fixture key'), findsOneWidget);
      expect(host.connectResults, isEmpty);
      await host.leave();
      await host.remove();
    },
  );

  testWidgets('config retry resolves a fresh binding and exact key selection', (
    tester,
  ) async {
    final host = _SshHost(tester)..libraryValues = _reconnectLibrary();
    await host.mount(
      reconnect: const SshReconnectTarget(
        mode: SshReconnectMode.config,
        host: 'server.invalid',
        port: 2222,
        username: 'alice',
        keyId: 'key_a',
        configAlias: 'fixture',
      ),
    );
    await tester.tap(find.text('Open SSH'));
    await host.advance();
    expect(host.connectResults, isEmpty);
    expect(find.text('fixture'), findsOneWidget);
    await host.tapConnect();
    final args = host.identityCalls.single;
    expect(args['alias'], 'fixture');
    expect(args['keyId'], 'key_a');
    expect(args['binding'], List.filled(64, 'b').join());
    expect(args.containsKey('password'), isFalse);
    host.completeConnect(error: PlatformException(code: 'SSH_AUTH_REJECTED'));
    await host.advance();
    await host.leave();
    await host.remove();
  });

  testWidgets(
    'missing key or changed config target never falls back to a different login',
    (tester) async {
      for (final config in [false, true]) {
        final host = _SshHost(tester)
          ..libraryValues = _reconnectLibrary(
            includeKey: config,
            configHost: config ? 'changed.invalid' : 'server.invalid',
          );
        await host.mount(
          connectOnOpen: true,
          reconnect: SshReconnectTarget(
            mode: config ? SshReconnectMode.config : SshReconnectMode.key,
            host: 'server.invalid',
            port: 2222,
            username: 'alice',
            keyId: 'key_a',
            configAlias: config ? 'fixture' : null,
          ),
        );
        await tester.tap(find.text('Open SSH'));
        await host.advance();
        expect(find.byKey(const Key('ssh-password')), findsNothing);
        expect(
          find.text(
            config ? '上次的配置目标或私钥选择已变化，请在“私钥与配置”中重新选择。' : '上次使用的私钥已不可用，请重新选择私钥。',
          ),
          findsOneWidget,
        );
        await host.tapConnect();
        expect(host.connectResults, isEmpty);
        expect(host.connectCalls, isEmpty);
        expect(host.identityCalls, isEmpty);
        await host.leave();
        await host.remove();
      }
    },
  );

  testWidgets(
    'lock retry waits for shared unlock and cancelled unlock starts no SSH flow',
    (tester) async {
      final host = _SshHost(tester)..vaultLocked = true;
      await host.mount(
        reconnect: const SshReconnectTarget(
          mode: SshReconnectMode.password,
          host: 'server.invalid',
          port: 2222,
          username: 'alice',
        ),
      );
      await tester.tap(find.text('Open SSH'));
      await host.advance();
      expect(find.text('解锁秘密库'), findsWidgets);
      expect(find.byKey(const Key('ssh-password')), findsNothing);
      expect(host.defaultsCalls, isEmpty);
      await tester.tap(find.byTooltip('取消并返回'));
      await host.advance();
      expect(host.defaultsCalls, isEmpty);
      expect(host.connectResults, isEmpty);
      expect(find.text('Open SSH'), findsOneWidget);
      await host.remove();
    },
  );

  testWidgets('notification denial allows SSH and requests only once', (
    tester,
  ) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(412, 915);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetViewInsets);
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    final host = _SshHost(tester)..notificationsEnabled = false;
    await host.mount(root: true);
    await tester.tap(find.text('终端').last);
    await host.advance();
    await tester.ensureVisible(find.byKey(const Key('terminal-ssh-entry')));
    await tester.tap(find.byKey(const Key('terminal-ssh-entry')));
    await host.advance();
    expect(find.byKey(const Key('ssh-password')), findsOneWidget);
    final methods = host.calls.map((call) => call.method).toList();
    expect(
      methods.indexOf('ssh.requestNotifications'),
      lessThan(methods.indexOf('secrets.status')),
    );
    await host.leave();
    await tester.pumpAndSettle();
    expect(
      tester
          .widget<ListTile>(find.byKey(const Key('terminal-ssh-entry')))
          .onTap,
      isNotNull,
    );
    await tester.tap(find.byKey(const Key('terminal-ssh-entry')));
    await host.advance();
    expect(find.byKey(const Key('ssh-password')), findsOneWidget);
    expect(
      host.calls.where((call) => call.method == 'ssh.requestNotifications'),
      hasLength(1),
    );
    await host.leave();
    await host.messenger.handlePlatformMessage(
      'ctos/terminal',
      const StandardMethodCodec().encodeSuccessEnvelope({
        'type': 'state',
        'generation': 1,
        'state': 'running',
        'mode': 'ssh',
        'remote': true,
      }),
      null,
    );
    await host.advance();
    expect(
      find.byKey(const Key('ssh-notifications-unavailable')),
      findsOneWidget,
    );
    tester.platformDispatcher.textScaleFactorTestValue = 1.5;
    for (final scenario in [
      (const Size(320, 700), 300.0),
      (const Size(700, 400), 120.0),
    ]) {
      tester.view.physicalSize = scenario.$1;
      tester.view.viewInsets = FakeViewPadding(bottom: scenario.$2);
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      final warning = find.byKey(const Key('ssh-notifications-unavailable'));
      final stop = find.byTooltip('关闭会话');
      expect(warning, findsOneWidget);
      expect(stop, findsOneWidget);
      expect(tester.getTopLeft(warning).dx, greaterThanOrEqualTo(0));
      expect(
        tester.getBottomRight(warning).dx,
        lessThanOrEqualTo(scenario.$1.width),
      );
      expect(
        tester.getBottomRight(stop).dx,
        lessThanOrEqualTo(scenario.$1.width),
      );
      expect(
        tester.getBottomRight(stop).dy,
        lessThanOrEqualTo(scenario.$1.height - scenario.$2),
      );
    }
    await host.remove();
  });

  testWidgets(
    'permission result waits for resume before opening protected SSH',
    (tester) async {
      final host = _SshHost(tester)
        ..notificationsEnabled = false
        ..notificationPermission = Completer<Map<String, Object>>();
      await host.mount(root: true);
      await tester.tap(find.text('终端').last);
      await host.advance();
      await tester.ensureVisible(find.byKey(const Key('terminal-ssh-entry')));
      await tester.tap(find.byKey(const Key('terminal-ssh-entry')));
      await tester.pump();
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      host.notificationPermission!.complete({'enabled': false});
      await tester.pump();
      expect(
        host.calls.where((call) => call.method == 'secrets.status'),
        isEmpty,
      );
      _resumeApp(tester);
      await host.advance();
      expect(find.byKey(const Key('ssh-password')), findsOneWidget);
      await host.leave();
      await host.remove();
    },
  );

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
      expect(find.text('保存主机信息；密码默认仅本次使用。'), findsOneWidget);
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
    expect(request['useSavedPassword'], isFalse);
    expect(request['savePassword'], isFalse);
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
    'saving a password requires explicit opt-in on the current tuple',
    (tester) async {
      final host = _SshHost(tester);
      await host.open();
      await host.fill();
      final save = find.byKey(const Key('ssh-save-password'));
      expect(tester.widget<CheckboxListTile>(save).value, isFalse);
      await tester.ensureVisible(save);
      await tester.tap(save);
      await host.tapConnect();
      expect(host.connectCalls.single['savePassword'], isTrue);
      expect(host.connectCalls.single['useSavedPassword'], isFalse);
      expect(host.passwordController.text, isEmpty);
      host.completeConnect(
        error: PlatformException(code: 'SSH_AUTHENTICATION'),
      );
      await host.advance();
      await host.remove();
    },
  );

  testWidgets(
    'saved password is metadata only and still requires explicit selection',
    (tester) async {
      final host = _SshHost(tester)..saved = true;
      await host.open();
      await host.fill();
      final use = find.byKey(const Key('ssh-use-saved-password'));
      expect(tester.widget<CheckboxListTile>(use).value, isFalse);
      expect(host.passwordController.text, 'synthetic password');
      await tester.ensureVisible(use);
      await tester.tap(use);
      await tester.pump();
      expect(host.passwordController.text, isEmpty);
      expect(
        tester
            .widget<TextFormField>(find.byKey(const Key('ssh-password')))
            .enabled,
        isFalse,
      );
      await host.tapConnect();
      expect(host.connectCalls.single['password'], isEmpty);
      expect(host.connectCalls.single['useSavedPassword'], isTrue);
      expect(host.connectCalls.single['savePassword'], isFalse);
      host.completeConnect(
        error: PlatformException(code: 'SSH_AUTHENTICATION'),
      );
      await host.advance();
      await tester.ensureVisible(use);
      await tester.tap(use);
      await tester.pump();
      expect(
        tester
            .widget<TextFormField>(find.byKey(const Key('ssh-password')))
            .enabled,
        isTrue,
      );
      await host.remove();
    },
  );

  testWidgets(
    'tuple changes immediately clear saved selection and ignore late metadata',
    (tester) async {
      final host = _SshHost(tester)..saved = true;
      await host.open();
      await host.fill();
      final use = find.byKey(const Key('ssh-use-saved-password'));
      await tester.ensureVisible(use);
      await tester.tap(use);
      host.deferStatus = true;
      final field = find.byKey(const Key('ssh-host'));
      await tester.ensureVisible(field);
      await tester.enterText(field, 'first.invalid');
      await host.advance();
      expect(use, findsNothing);
      expect(
        tester
            .widget<FilledButton>(find.byKey(const Key('ssh-connect')))
            .onPressed,
        isNull,
      );
      final first = host.statusCalls.last['requestId'] as String;
      await tester.enterText(field, 'second.invalid');
      await host.advance();
      expect(host.cancelled, contains(first));
      expect(host.statusCalls.last['host'], 'second.invalid');
      host.statusResults.last.complete({'saved': false});
      await host.advance();
      host.statusResults.first.complete({'saved': true});
      await host.advance();
      expect(use, findsNothing);
      await tester.enterText(
        find.byKey(const Key('ssh-password')),
        'new password',
      );
      await host.tapConnect();
      expect(host.connectCalls.single['host'], 'second.invalid');
      expect(host.connectCalls.single['useSavedPassword'], isFalse);
      expect(host.connectCalls.single['savePassword'], isFalse);
      host.completeConnect(error: PlatformException(code: 'SSH_CANCELLED'));
      await host.remove();
    },
  );

  testWidgets(
    'forget connection and all passwords require confirmation and preserve tuple',
    (tester) async {
      for (final all in [false, true]) {
        final host = _SshHost(tester)..saved = true;
        await host.open();
        await host.fill();
        final action = find.byKey(
          Key(all ? 'ssh-forget-all-passwords' : 'ssh-forget-password'),
        );
        await tester.ensureVisible(action);
        await tester.tap(action);
        await host.advance();
        expect(host.forgetCalls, isEmpty);
        await tester.tap(find.text('取消'));
        await host.advance();
        expect(host.forgetCalls, isEmpty);
        await tester.ensureVisible(action);
        await tester.tap(action);
        await host.advance();
        await tester.tap(find.byKey(const Key('ssh-forget-confirm')));
        await host.advance();
        final request = host.forgetCalls.single;
        expect(request['host'], all ? '' : 'server.invalid');
        expect(request['port'], all ? 22 : 2222);
        expect(request['username'], all ? '' : 'alice');
        expect(request['all'], all);
        expect(request.keys.toSet(), {
          'requestId',
          'host',
          'port',
          'username',
          'all',
        });
        expect(find.byKey(const Key('ssh-use-saved-password')), findsNothing);
        expect(host.passwordController.text, isEmpty);
        expect(
          tester
              .widget<TextFormField>(find.byKey(const Key('ssh-host')))
              .controller!
              .text,
          'server.invalid',
        );
        expect(host.statusCalls, hasLength(2));
        await host.remove();
      }
    },
  );

  testWidgets(
    'lock during forget confirmation cancels its authority and closes dialog',
    (tester) async {
      final host = _SshHost(tester)..saved = true;
      await host.open();
      await host.fill();
      final action = find.byKey(const Key('ssh-forget-password'));
      await tester.ensureVisible(action);
      await tester.tap(action);
      await host.advance();
      final confirm = tester
          .widget<FilledButton>(find.byKey(const Key('ssh-forget-confirm')))
          .onPressed!;
      await host.emit({
        'type': 'state',
        'requestId': host.idleId,
        'state': 'cancelled',
        'reason': 'VAULT_LOCKED',
      });
      await host.advance();
      // A callback captured before revocation cannot authorize a write.
      expect(find.byKey(const Key('ssh-forget-confirm')), findsNothing);
      expect(host.forgetCalls, isEmpty);
      expect(host.cancelled, contains(host.idleId));
      confirm();
      await host.advance();
      expect(host.forgetCalls, isEmpty);
      await host.remove();
    },
  );

  testWidgets(
    'malformed password status fails closed without showing secret payload',
    (tester) async {
      final host = _SshHost(tester)..deferStatus = true;
      await host.open();
      await host.fill();
      host.statusResults.single.complete({
        'saved': true,
        'password': 'must-not-display',
      });
      await host.advance();
      expect(find.byKey(const Key('ssh-password')), findsNothing);
      expect(find.text('must-not-display'), findsNothing);
      expect(find.text('SSH 连接响应无效，请重新打开连接页面。'), findsOneWidget);
      await host.remove();
    },
  );

  testWidgets(
    'uncertain forget requires reopening and never reports successful deletion',
    (tester) async {
      final host = _SshHost(tester)
        ..saved = true
        ..forgetError = PlatformException(code: 'VAULT_COMMIT_UNCERTAIN');
      await host.open();
      await host.fill();
      final action = find.byKey(const Key('ssh-forget-password'));
      await tester.ensureVisible(action);
      await tester.tap(action);
      await host.advance();
      await tester.tap(find.byKey(const Key('ssh-forget-confirm')));
      await host.advance();
      expect(find.byKey(const Key('ssh-password')), findsNothing);
      expect(find.text('已忘记保存的密码。'), findsNothing);
      expect(find.text('保存结果未能确认，请重新解锁并核验连接信息、已保存密码或主机密钥。'), findsOneWidget);
      await host.remove();
    },
  );

  testWidgets('all-password forget works before any recent connection exists', (
    tester,
  ) async {
    final host = _SshHost(tester);
    await host.open();
    final action = find.byKey(const Key('ssh-forget-all-passwords'));
    await tester.ensureVisible(action);
    await host.advance();
    expect(action.hitTestable(), findsOneWidget);
    expect(tester.widget<TextButton>(action).onPressed, isNotNull);
    await tester.tap(action);
    await host.advance();
    await tester.tap(find.byKey(const Key('ssh-forget-confirm')));
    await host.advance();
    expect(host.forgetCalls.single, {
      'requestId': host.forgetCalls.single['requestId'],
      'host': '',
      'port': 22,
      'username': '',
      'all': true,
    });
    expect(host.defaultsCalls, hasLength(2));
    expect(host.statusCalls, isEmpty);
    await host.remove();
  });

  testWidgets(
    'cancel forgetting awaits native completion and acknowledges committed result',
    (tester) async {
      final host = _SshHost(tester)
        ..saved = true
        ..pendingForget = Completer<Map<String, Object>>();
      await host.open();
      await host.fill();
      final action = find.byKey(const Key('ssh-forget-password'));
      await tester.ensureVisible(action);
      await tester.tap(action);
      await host.advance();
      await tester.tap(find.byKey(const Key('ssh-forget-confirm')));
      await host.advance();
      final requestId = host.forgetCalls.single['requestId'] as String;
      await host.leave();
      expect(host.cancelled, contains(requestId));
      expect(host.releases, isEmpty);
      expect(find.text('Open SSH'), findsNothing);
      host.pendingForget!.complete({'forgotten': true});
      await host.advance();
      expect(find.text('密码操作结果'), findsOneWidget);
      expect(host.releases, isEmpty);
      await tester.tap(find.text('知道了'));
      await host.advance();
      expect(find.text('Open SSH'), findsOneWidget);
      expect(host.releases, isNotEmpty);
      await host.remove();
    },
  );

  testWidgets('background invalidation discards a late saved-password status', (
    tester,
  ) async {
    final host = _SshHost(tester)..deferStatus = true;
    await host.open();
    await host.fill();
    final requestId = host.statusCalls.single['requestId'] as String;
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    await host.advance();
    host.statusResults.single.complete({'saved': true});
    await host.advance();
    expect(host.cancelled, contains(requestId));
    expect(find.byKey(const Key('ssh-use-saved-password')), findsNothing);
    expect(find.byKey(const Key('ssh-password')), findsNothing);
    _resumeApp(tester);
    await host.remove();
  });

  testWidgets(
    'cancel saving waits for native uncertain receipt under protection',
    (tester) async {
      final host = _SshHost(tester);
      await host.open();
      await host.fill();
      final save = find.byKey(const Key('ssh-save-password'));
      await tester.ensureVisible(save);
      await tester.tap(save);
      await host.tapConnect();
      final requestId = host.connectCalls.single['requestId'] as String;
      expect(host.connectCalls.single['savePassword'], isTrue);
      await host.leave();
      expect(host.cancelled, contains(requestId));
      expect(host.releases, isEmpty);
      expect(find.text('Open SSH'), findsNothing);
      await host.emit({
        'type': 'state',
        'requestId': requestId,
        'state': 'failed',
        'reason': 'VAULT_COMMIT_UNCERTAIN',
      });
      expect(host.releases, isEmpty);
      host.completeConnect(
        error: PlatformException(code: 'VAULT_COMMIT_UNCERTAIN'),
      );
      await host.advance();
      expect(find.text('密码操作结果'), findsOneWidget);
      expect(find.text('保存结果未能确认，请重新解锁并核验连接信息、已保存密码或主机密钥。'), findsWidgets);
      expect(host.releases, isEmpty);
      final staleAcknowledge = tester
          .widget<TextButton>(find.widgetWithText(TextButton, '知道了'))
          .onPressed!;
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      await host.advance();
      expect(find.text('密码操作结果'), findsNothing);
      staleAcknowledge();
      await host.advance();
      expect(host.releases, isEmpty);
      _resumeApp(tester);
      await host.advance();
      await host.leave();
      expect(find.text('密码操作结果'), findsOneWidget);
      await tester.tap(find.text('知道了'));
      await host.advance();
      expect(find.text('Open SSH'), findsOneWidget);
      expect(host.releases, isNotEmpty);
      await host.remove();
    },
  );

  testWidgets(
    'successful saved connection racing cancel closes only its exact request before receipt',
    (tester) async {
      final host = _SshHost(tester);
      await host.open();
      await host.fill();
      final save = find.byKey(const Key('ssh-save-password'));
      await tester.ensureVisible(save);
      await tester.tap(save);
      await host.tapConnect();
      final requestId = host.connectCalls.single['requestId'] as String;
      await host.leave();
      await host.emit({
        'type': 'state',
        'requestId': requestId,
        'state': 'connected',
      });
      expect(host.releases, isEmpty);
      expect(find.text('Open SSH'), findsNothing);
      host.completeConnect();
      await host.advance();
      expect(host.cancelled.where((id) => id == requestId), hasLength(2));
      expect(
        host.calls.where((call) => call.method == 'terminalStop'),
        isEmpty,
      );
      expect(find.text('密码已保存到秘密库。'), findsOneWidget);
      expect(host.releases, isEmpty);
      await tester.tap(find.text('知道了'));
      await host.advance();
      expect(find.text('Open SSH'), findsOneWidget);
      expect(host.releases, isNotEmpty);
      await host.remove();
    },
  );

  testWidgets(
    'saving cancelled before commit waits for result without fabricating saved receipt',
    (tester) async {
      final host = _SshHost(tester);
      await host.open();
      await host.fill();
      final save = find.byKey(const Key('ssh-save-password'));
      await tester.ensureVisible(save);
      await tester.tap(save);
      await host.tapConnect();
      await host.leave();
      expect(host.releases, isEmpty);
      host.completeConnect(error: PlatformException(code: 'SSH_CANCELLED'));
      await host.advance();
      expect(find.text('密码操作结果'), findsNothing);
      expect(find.text('密码已保存到秘密库。'), findsNothing);
      expect(find.text('Open SSH'), findsOneWidget);
      expect(host.releases, isNotEmpty);
      await host.remove();
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
      expect(host.connectCalls, hasLength(2));
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
      final idleId = host.idleId;
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
      final idleId = host.idleId;
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
    'saved controls and review remain reachable with narrow keyboard landscape and large text',
    (tester) async {
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPhysicalSize);
      for (final size in [
        const Size(320, 568),
        const Size(568, 320),
        const Size(1200, 800),
      ]) {
        tester.view.physicalSize = size;
        final host = _SshHost(tester)..saved = true;
        await host.open(scale: 2, keyboard: size.width == 320 ? 220 : 0);
        await host.fill();
        for (final key in [
          'ssh-use-saved-password',
          'ssh-save-password',
          'ssh-forget-password',
          'ssh-forget-all-passwords',
        ]) {
          final control = find.byKey(Key(key));
          await tester.ensureVisible(control);
          await tester.pump(const Duration(milliseconds: 300));
          await tester.ensureVisible(control);
          await tester.pump();
          expect(control.hitTestable(), findsOneWidget);
          expect(
            tester.getBottomRight(control).dx,
            lessThanOrEqualTo(size.width),
          );
        }
        final forget = find.byKey(const Key('ssh-forget-password'));
        await tester.ensureVisible(forget);
        await tester.pump();
        expect(forget.hitTestable(), findsOneWidget);
        await tester.tap(forget);
        await host.advance();
        final confirm = find.byKey(const Key('ssh-forget-confirm'));
        await tester.ensureVisible(confirm);
        expect(confirm.hitTestable(), findsOneWidget);
        expect(tester.takeException(), isNull);
        await tester.tap(find.text('取消'));
        await host.advance();
        expect(host.forgetCalls, isEmpty);
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

Map<String, Object> _reconnectLibrary({
  bool includeKey = true,
  String configHost = 'server.invalid',
}) => {
  'keys': [
    if (includeKey)
      {
        'id': 'key_a',
        'label': 'Current fixture key',
        'path': '.ssh/id_fixture',
        'algorithm': 'ssh-ed25519',
        'fingerprint': 'SHA256:${List.filled(43, 'A').join()}',
      },
  ],
  'hosts': [
    if (includeKey)
      {
        'alias': 'fixture',
        'binding': List.filled(64, 'b').join(),
        'host': configHost,
        'port': 2222,
        'username': 'alice',
        'keyIds': ['key_a'],
      },
  ],
  'hasConfig': includeKey,
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
  final statusResults = <Completer<Map<String, Object>>>[];
  Completer<Object?>? protection;
  bool saved = false, deferStatus = false;
  bool vaultLocked = false;
  Map<String, Object> libraryValues = {
    'keys': <Object>[],
    'hosts': <Object>[],
    'hasConfig': false,
  };
  bool notificationsEnabled = true;
  Completer<Map<String, Object>>? notificationPermission;
  Completer<Map<String, Object>>? pendingLibrary;
  Object? forgetError;
  Completer<Map<String, Object>>? pendingForget;
  Map<String, Object> defaultValues = {'host': '', 'port': 22, 'username': ''};
  late SshPasswordApi api;
  int _sequence = 0;
  bool _root = false;

  List<Map<dynamic, dynamic>> get defaultsCalls => _arguments('ssh.defaults');
  List<Map<dynamic, dynamic>> get connectCalls =>
      _arguments('ssh.passwordConnect');
  List<Map<dynamic, dynamic>> get identityCalls => calls
      .where(
        (call) =>
            call.method == 'ssh.keyConnect' ||
            call.method == 'ssh.configConnect',
      )
      .map((call) => call.arguments as Map)
      .toList();
  List<Map<dynamic, dynamic>> get statusCalls =>
      _arguments('ssh.passwordStatus');
  List<Map<dynamic, dynamic>> get forgetCalls =>
      _arguments('ssh.forgetPassword');
  String get idleId =>
      (statusCalls.isEmpty ? defaultsCalls.last : statusCalls.last)['requestId']
          as String;
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
      case 'ssh.notificationStatus':
        return {'enabled': notificationsEnabled};
      case 'ssh.requestNotifications':
        return notificationPermission?.future ??
            {'enabled': notificationsEnabled};
      case 'secrets.status':
        return {
          'state': vaultLocked ? 'locked' : 'unlocked',
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
        return {...defaultValues, 'savedPassword': saved};
      case 'ssh.library':
        return pendingLibrary?.future ?? libraryValues;
      case 'secrets.cancel':
        return null;
      case 'ssh.passwordStatus':
        if (!deferStatus) return {'saved': saved};
        final pendingStatus = Completer<Map<String, Object>>();
        statusResults.add(pendingStatus);
        return pendingStatus.future;
      case 'ssh.forgetPassword':
        if (forgetError != null) throw forgetError!;
        if (pendingForget != null) return pendingForget!.future;
        saved = false;
        return {'forgotten': true};
      case 'ssh.passwordConnect':
      case 'ssh.keyConnect':
      case 'ssh.configConnect':
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
    SshReconnectTarget? reconnect,
    bool connectOnOpen = false,
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
                      reconnect: reconnect,
                      connectOnOpen: connectOnOpen,
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
    // Flush text-field layout and its caret reveal before scrolling to the action.
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
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
    // Failure messages scroll into a lazy ListView in a post-frame callback.
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

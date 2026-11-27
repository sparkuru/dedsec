import 'dart:async';
import 'dart:convert';
import 'dart:ui' show PointerDeviceKind, SemanticsAction;

import 'package:ctos/main.dart';
import 'package:ctos/ssh/api.dart';
import 'package:ctos/ssh/host_trust_page.dart';
import 'package:ctos/ssh/password_connect_page.dart';
import 'package:ctos/ui/ctos_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('automatic connection asks for password only after host review', (
    tester,
  ) async {
    final host = _SshHost(tester);
    await host.mount(automatic: true);
    await tester.tap(find.text('Open SSH'));
    await host.advance();
    expect(find.byKey(const Key('ssh-password')), findsNothing);
    expect(find.text('私钥（可选）'), findsOneWidget);
    for (final field in {
      'ssh-host': 'server.invalid',
      'ssh-port': '2222',
      'ssh-username': 'alice',
    }.entries) {
      await tester.enterText(find.byKey(Key(field.key)), field.value);
    }
    await host.tapConnect();
    final request = host._arguments('ssh.autoConnect').single;
    expect(request['keyId'], '');
    expect(request.containsKey('password'), isFalse);
    expect(request.containsKey('passphrase'), isFalse);
    final id = request['requestId'] as String;
    await host.emit(_ticket(id));
    expect(find.text('信任并保存'), findsOneWidget);
    expect(find.byKey(const Key('ssh-secret-dialog')), findsNothing);
    await tester.tap(find.text('信任并保存'));
    await host.advance();
    await host.emit({
      'type': 'passwordRequest',
      'hop': 0,
      'host': 'jump.invalid',
      'port': 2201,
      'username': 'jump-user',
      'requestId': id,
      'ticketId': 'prompt_1',
      'binding': 'c' * 64,
      'remainingMillis': 25000,
    });
    await host.advance();
    expect(find.text('输入登录密码'), findsOneWidget);
    expect(find.text('jump-user@jump.invalid:2201'), findsOneWidget);
    final input = tester.widget<EditableText>(
      find.descendant(
        of: find.byKey(const Key('ssh-secret-input')),
        matching: find.byType(EditableText),
      ),
    );
    expect(input.obscureText, isTrue);
    await tester.enterText(
      find.byKey(const Key('ssh-secret-input')),
      'synthetic',
    );
    await tester.tap(find.byKey(const Key('ssh-secret-submit')));
    await host.advance();
    final response = host._arguments('ssh.passwordResponse').single;
    expect(response['ticketId'], 'prompt_1');
    expect(response['binding'], 'c' * 64);
    await host.emit({
      'type': 'passwordRequestDismissed',
      'requestId': id,
      'ticketId': 'prompt_1',
    });
    host.connectResults.single.complete({
      'requestId': id,
      'status': 'connected',
    });
    await host.advance();
    await host.advance();
    expect(find.byType(SshPasswordConnectPage), findsNothing);
    await host.remove();
  });

  testWidgets('automatic connection shows PAM questions after host review', (
    tester,
  ) async {
    final host = _SshHost(tester);
    await host.mount(automatic: true);
    await tester.tap(find.text('Open SSH'));
    await host.advance();
    for (final field in {
      'ssh-host': 'server.invalid',
      'ssh-port': '2222',
      'ssh-username': 'alice',
    }.entries) {
      await tester.enterText(find.byKey(Key(field.key)), field.value);
    }
    await host.tapConnect();
    final id = host._arguments('ssh.autoConnect').single['requestId'] as String;
    expect(host._arguments('ssh.keyboardConnect'), isEmpty);
    await host.emit(_ticket(id));
    expect(find.text('信任并保存'), findsOneWidget);
    expect(find.byKey(const Key('ssh-keyboard-dialog')), findsNothing);
    await tester.tap(find.text('信任并保存'));
    await host.advance();
    final firstChallenge = <String, Object>{
      'type': 'keyboardChallenge',
      'hop': 0,
      'host': 'jump.invalid',
      'port': 2201,
      'username': 'jump-user',
      'requestId': id,
      'ticketId': 'pam_round_1',
      'binding': 'e' * 64,
      'round': 1,
      'name': '',
      'instruction': '',
      'prompts': [
        {'text': 'Password: ', 'echo': false},
      ],
      'remainingMillis': 25000,
    };
    await host.emit(firstChallenge);
    await host.advance();
    expect(find.text('Password: '), findsOneWidget);
    expect(find.text('jump-user@jump.invalid:2201'), findsOneWidget);
    await host.emit(firstChallenge);
    expect(find.text('Password: '), findsOneWidget);
    final input = tester.widget<TextField>(
      find.descendant(
        of: find.byKey(const Key('ssh-keyboard-answer-0')),
        matching: find.byType(TextField),
      ),
    );
    expect(input.obscureText, isTrue);
    expect(host._arguments('ssh.keyboardResponse'), isEmpty);
    await tester.enterText(
      find.byKey(const Key('ssh-keyboard-answer-0')),
      'synthetic',
    );
    await tester.tap(find.byKey(const Key('ssh-keyboard-submit')));
    await host.advance();
    final first = host._arguments('ssh.keyboardResponse').single;
    expect(first['ticketId'], 'pam_round_1');
    expect(first['answers'], ['synthetic']);
    await host.emit({
      'type': 'keyboardChallengeDismissed',
      'requestId': id,
      'ticketId': 'pam_round_1',
    });
    await host.emit({
      'type': 'keyboardChallenge',
      'hop': 0,
      'host': 'jump.invalid',
      'port': 2201,
      'username': 'jump-user',
      'requestId': id,
      'ticketId': 'pam_round_2',
      'binding': 'f' * 64,
      'round': 2,
      'name': '',
      'instruction': '',
      'prompts': <Object>[],
      'remainingMillis': 25000,
    });
    await host.advance();
    expect(find.text('继续'), findsOneWidget);
    expect(find.text('jump-user@jump.invalid:2201'), findsOneWidget);
    expect(host._arguments('ssh.keyboardResponse'), hasLength(1));
    await tester.tap(find.byKey(const Key('ssh-keyboard-submit')));
    await host.advance();
    final second = host._arguments('ssh.keyboardResponse').last;
    expect(second['ticketId'], 'pam_round_2');
    expect(second['answers'], isEmpty);
    await host.emit({
      'type': 'keyboardChallengeDismissed',
      'requestId': id,
      'ticketId': 'pam_round_2',
    });
    // A later hop may use the same endpoint and starts its own round counter.
    await host.emit({
      'type': 'keyboardChallenge',
      'hop': 1,
      'host': 'jump.invalid',
      'port': 2201,
      'username': 'jump-user',
      'requestId': id,
      'ticketId': 'next_hop_round_1',
      'binding': 'a' * 64,
      'round': 1,
      'name': '',
      'instruction': '',
      'prompts': <Object>[],
      'remainingMillis': 25000,
    });
    await host.advance();
    expect(find.text('jump-user@jump.invalid:2201'), findsOneWidget);
    await tester.tap(find.byKey(const Key('ssh-keyboard-submit')));
    await host.advance();
    expect(
      host._arguments('ssh.keyboardResponse').last['ticketId'],
      'next_hop_round_1',
    );
    await host.emit({
      'type': 'keyboardChallengeDismissed',
      'requestId': id,
      'ticketId': 'next_hop_round_1',
    });
    host.connectResults.single.complete({
      'requestId': id,
      'status': 'connected',
    });
    await host.advance();
    await host.advance();
    expect(find.byType(SshPasswordConnectPage), findsNothing);
    await host.remove();
  });

  testWidgets('a later hop cannot be replaced by a delayed earlier prompt', (
    tester,
  ) async {
    for (final staleKeyboard in [false, true]) {
      final host = _SshHost(tester);
      await host.mount(automatic: true);
      await tester.tap(find.text('Open SSH'));
      await host.advance();
      for (final field in {
        'ssh-host': 'server.invalid',
        'ssh-port': '2222',
        'ssh-username': 'alice',
      }.entries) {
        await tester.enterText(find.byKey(Key(field.key)), field.value);
      }
      await host.tapConnect();
      final id =
          host._arguments('ssh.autoConnect').single['requestId'] as String;
      await host.emit({
        'type': 'keyboardChallenge',
        'hop': 1,
        'host': 'jump.invalid',
        'port': 2201,
        'username': 'jump-user',
        'requestId': id,
        'ticketId': 'later_hop',
        'binding': 'c' * 64,
        'round': 1,
        'name': '',
        'instruction': '',
        'prompts': <Object>[],
        'remainingMillis': 25000,
      });
      await host.advance();
      await tester.tap(find.byKey(const Key('ssh-keyboard-submit')));
      await host.advance();
      await host.emit({
        'type': 'keyboardChallengeDismissed',
        'requestId': id,
        'ticketId': 'later_hop',
      });
      await host.emit({
        'type': staleKeyboard ? 'keyboardChallenge' : 'passwordRequest',
        'hop': 0,
        'host': 'jump.invalid',
        'port': 2201,
        'username': 'jump-user',
        'requestId': id,
        'ticketId': 'old_hop',
        'binding': 'd' * 64,
        if (staleKeyboard) ...{
          'round': 2,
          'name': '',
          'instruction': '',
          'prompts': <Object>[],
        },
        'remainingMillis': 25000,
      });
      await host.advance();
      expect(host.cancelled, contains(id));
      expect(host._arguments('ssh.passwordResponse'), isEmpty);
      expect(host._arguments('ssh.keyboardResponse'), hasLength(1));
      expect(find.byKey(const Key('ssh-secret-dialog')), findsNothing);
      host.connectResults.single.completeError(
        PlatformException(code: 'SSH_CANCELLED'),
      );
      await host.advance();
      await host.remove();
    }
  });

  testWidgets(
    'automatic selected key asks for its passphrase only after connecting',
    (tester) async {
      final host = _SshHost(tester)
        ..libraryValues = _reconnectLibrary(encrypted: true);
      await host.mount(
        automatic: true,
        scale: 1.4,
        keyboard: 200,
        reconnect: const SshReconnectTarget(
          mode: SshReconnectMode.auto,
          host: 'server.invalid',
          port: 2222,
          username: 'alice',
          keyId: 'key_a',
        ),
      );
      await tester.tap(find.text('Open SSH'));
      await host.advance();
      expect(find.text('Current fixture key'), findsOneWidget);
      expect(find.byKey(const Key('ssh-key-passphrase')), findsNothing);
      await host.tapConnect();
      final request = host._arguments('ssh.autoConnect').single;
      expect(request['keyId'], 'key_a');
      expect(request.containsKey('passphrase'), isFalse);
      final id = request['requestId'] as String;
      await host.emit({
        'type': 'keyPassphraseRequest',
        'hop': 0,
        'host': 'key-jump.invalid',
        'port': 2203,
        'username': 'key-user',
        'requestId': id,
        'ticketId': 'key_prompt',
        'binding': 'd' * 64,
        'remainingMillis': 25000,
      });
      await host.advance();
      expect(find.text('输入私钥口令'), findsOneWidget);
      expect(find.text('key-user@key-jump.invalid:2203'), findsOneWidget);
      expect(find.byKey(const Key('ssh-secret-input')), findsOneWidget);
      await tester.enterText(
        find.byKey(const Key('ssh-secret-input')),
        'synthetic',
      );
      final save = find.byKey(const Key('ssh-secret-save'));
      await tester.ensureVisible(save);
      expect(tester.widget<CheckboxListTile>(save).value, isFalse);
      await tester.tap(save);
      await tester.tap(find.byKey(const Key('ssh-secret-submit')));
      await host.advance();
      final response = host._arguments('ssh.passwordResponse').single;
      expect(response['ticketId'], 'key_prompt');
      expect(response['save'], isTrue);
      expect(response['useSaved'], isFalse);
      await host.emit({
        'type': 'passwordRequestDismissed',
        'requestId': id,
        'ticketId': 'key_prompt',
      });
      host.connectResults.single.complete({
        'requestId': id,
        'status': 'connected',
      });
      await host.advance();
      await host.advance();
      expect(find.byType(SshPasswordConnectPage), findsNothing);
      await host.remove();
    },
  );

  testWidgets(
    'automatic saved password stays explicit and clears typed draft',
    (tester) async {
      final host = _SshHost(tester);
      await host.mount(
        automatic: true,
        reconnect: const SshReconnectTarget(
          mode: SshReconnectMode.auto,
          host: 'server.invalid',
          port: 2222,
          username: 'alice',
        ),
      );
      await tester.tap(find.text('Open SSH'));
      await host.advance();
      await host.tapConnect();
      final id =
          host._arguments('ssh.autoConnect').single['requestId'] as String;
      await host.emit({
        'type': 'passwordRequest',
        'hop': 0,
        'host': 'server.invalid',
        'port': 2222,
        'username': 'alice',
        'requestId': id,
        'ticketId': 'prompt_saved',
        'binding': 'c' * 64,
        'remainingMillis': 25000,
        'savedAvailable': true,
      });
      await host.advance();
      final saved = find.byKey(const Key('ssh-secret-use-saved'));
      final input = find.byKey(const Key('ssh-secret-input'));
      final save = find.byKey(const Key('ssh-secret-save'));
      expect(tester.widget<CheckboxListTile>(saved).value, isFalse);
      expect(tester.widget<CheckboxListTile>(save).value, isFalse);
      expect(host._arguments('ssh.passwordResponse'), isEmpty);
      await tester.enterText(input, 'synthetic-draft');
      await tester.tap(save);
      await tester.tap(saved);
      await host.advance();
      expect(input, findsNothing);
      expect(save, findsNothing);
      await tester.tap(saved);
      await host.advance();
      expect(tester.widget<TextFormField>(input).controller!.text, isEmpty);
      expect(tester.widget<CheckboxListTile>(save).value, isFalse);
      await tester.tap(saved);
      await host.advance();
      await tester.tap(find.byKey(const Key('ssh-secret-submit')));
      await host.advance();
      final response = host._arguments('ssh.passwordResponse').single;
      expect(response['ticketId'], 'prompt_saved');
      expect(response['useSaved'], isTrue);
      expect(response['save'], isFalse);
      expect(response['password'], isEmpty);
      await host.emit({
        'type': 'passwordRequestDismissed',
        'requestId': id,
        'ticketId': 'prompt_saved',
      });
      host.connectResults.single.complete({
        'requestId': id,
        'status': 'connected',
      });
      await host.advance();
      await host.advance();
      expect(find.byType(SshPasswordConnectPage), findsNothing);
      await host.remove();
    },
  );

  testWidgets('each saved-only hop requires a separate explicit confirmation', (
    tester,
  ) async {
    final host = _SshHost(tester);
    await host.mount(
      automatic: true,
      reconnect: const SshReconnectTarget(
        mode: SshReconnectMode.auto,
        host: 'server.invalid',
        port: 2222,
        username: 'alice',
      ),
    );
    await tester.tap(find.text('Open SSH'));
    await host.advance();
    await host.tapConnect();
    final id = host._arguments('ssh.autoConnect').single['requestId'] as String;
    for (final (hop, endpoint, username, port) in [
      (0, 'jump.invalid', 'jump-user', 2201),
      (1, 'server.invalid', 'alice', 2222),
    ]) {
      final ticket = 'saved_prompt_$hop';
      await host.emit({
        'type': 'passwordRequest',
        'hop': hop,
        'host': endpoint,
        'port': port,
        'username': username,
        'requestId': id,
        'ticketId': ticket,
        'binding': 'c' * 64,
        'remainingMillis': 25000,
        'savedAvailable': true,
        'savedOnly': true,
      });
      await host.advance();
      expect(find.text('$username@$endpoint:$port'), findsOneWidget);
      expect(find.text('确认使用已保存的密码'), findsOneWidget);
      expect(find.byKey(const Key('ssh-secret-input')), findsNothing);
      expect(find.byKey(const Key('ssh-secret-save')), findsNothing);
      expect(find.byKey(const Key('ssh-secret-use-saved')), findsNothing);
      expect(host._arguments('ssh.passwordResponse'), hasLength(hop));
      await tester.tap(find.byKey(const Key('ssh-secret-submit')));
      await host.advance();
      final response = host._arguments('ssh.passwordResponse').last;
      expect(response['ticketId'], ticket);
      expect(response['useSaved'], isTrue);
      expect(response['save'], isFalse);
      expect(response['password'], isEmpty);
      await host.emit({
        'type': 'passwordRequestDismissed',
        'requestId': id,
        'ticketId': ticket,
      });
      await host.advance();
    }
    host.connectResults.single.complete({
      'requestId': id,
      'status': 'connected',
    });
    await host.advance();
    await host.advance();
    expect(find.byType(SshPasswordConnectPage), findsNothing);
    await host.remove();
  });

  testWidgets('hop reviews show each endpoint before its own saved password', (
    tester,
  ) async {
    final host = _SshHost(tester);
    await host.mount(
      automatic: true,
      reconnect: const SshReconnectTarget(
        mode: SshReconnectMode.auto,
        host: 'server.invalid',
        port: 2222,
        username: 'alice',
      ),
    );
    await tester.tap(find.text('Open SSH'));
    await host.advance();
    await host.tapConnect();
    final id = host._arguments('ssh.autoConnect').single['requestId'] as String;
    final firstReview = {
      ..._ticket(id),
      'host': 'jump.invalid',
      'port': 2201,
      'ticketId': 'jump-review',
    };
    await host.emit(firstReview);
    await host.advance();
    expect(find.text('jump.invalid:2201'), findsOneWidget);
    expect(host._arguments('ssh.passwordResponse'), isEmpty);
    await tester.tap(find.text('信任并保存'));
    await host.advance();
    await host.emit({
      'type': 'hostKeyDismissed',
      'requestId': id,
      'ticketId': 'jump-review',
      'binding': 'a' * 64,
      'hop': 0,
    });
    await host.emit({
      'type': 'passwordRequest',
      'hop': 0,
      'host': 'jump.invalid',
      'port': 2201,
      'username': 'jump-user',
      'requestId': id,
      'ticketId': 'jump-password',
      'binding': 'c' * 64,
      'remainingMillis': 25000,
      'savedAvailable': true,
      'savedOnly': true,
    });
    await host.advance();
    expect(find.text('jump-user@jump.invalid:2201'), findsOneWidget);
    await tester.tap(find.byKey(const Key('ssh-secret-submit')));
    await host.advance();
    await host.emit({
      'type': 'passwordRequestDismissed',
      'requestId': id,
      'ticketId': 'jump-password',
    });
    await host.advance();
    final targetReview = {
      ..._ticket(id),
      'ticketId': 'target-review',
      'binding': 'b' * 64,
      'hop': 1,
      'fingerprint': 'SHA256:${'B' * 43}',
    };
    await host.emit(targetReview);
    await host.advance();
    expect(find.text('server.invalid:2222'), findsOneWidget);
    expect(find.text(targetReview['fingerprint'] as String), findsOneWidget);
    expect(host._arguments('ssh.passwordResponse'), hasLength(1));
    await tester.tap(find.text('信任并保存'));
    await host.advance();
    expect(host.decisions.map((decision) => decision['ticketId']), [
      'jump-review',
      'target-review',
    ]);
    await host.emit({
      'type': 'hostKeyDismissed',
      'requestId': id,
      'ticketId': 'target-review',
      'binding': 'b' * 64,
      'hop': 1,
    });
    await host.emit({
      'type': 'passwordRequest',
      'hop': 1,
      'host': 'server.invalid',
      'port': 2222,
      'username': 'alice',
      'requestId': id,
      'ticketId': 'target-password',
      'binding': 'd' * 64,
      'remainingMillis': 25000,
      'savedAvailable': true,
      'savedOnly': true,
    });
    await host.advance();
    expect(find.text('alice@server.invalid:2222'), findsOneWidget);
    expect(host._arguments('ssh.passwordResponse'), hasLength(1));
    await tester.tap(find.byKey(const Key('ssh-secret-submit')));
    await host.advance();
    expect(
      host._arguments('ssh.passwordResponse').last['ticketId'],
      'target-password',
    );
    expect(host._arguments('ssh.passwordResponse').last['useSaved'], isTrue);
    await host.emit({
      'type': 'passwordRequestDismissed',
      'requestId': id,
      'ticketId': 'target-password',
    });
    host.connectResults.single.complete({
      'requestId': id,
      'status': 'connected',
    });
    await host.advance();
    await host.remove();
  });

  testWidgets('saved-only prompt rejects a changed duplicate ticket', (
    tester,
  ) async {
    final host = _SshHost(tester);
    await host.mount(
      automatic: true,
      reconnect: const SshReconnectTarget(
        mode: SshReconnectMode.auto,
        host: 'server.invalid',
        port: 2222,
        username: 'alice',
      ),
    );
    await tester.tap(find.text('Open SSH'));
    await host.advance();
    await host.tapConnect();
    final id = host._arguments('ssh.autoConnect').single['requestId'] as String;
    final prompt = {
      'type': 'passwordRequest',
      'hop': 0,
      'host': 'jump.invalid',
      'port': 2201,
      'username': 'jump-user',
      'requestId': id,
      'ticketId': 'prompt_1',
      'binding': 'c' * 64,
      'remainingMillis': 25000,
      'savedAvailable': true,
      'savedOnly': true,
    };
    await host.emit(prompt);
    await host.advance();
    await host.emit({...prompt, 'savedOnly': false});
    await host.advance();
    expect(host.cancelled, contains(id));
    expect(host._arguments('ssh.passwordResponse'), isEmpty);
    host.connectResults.single.completeError(
      PlatformException(code: 'SSH_CANCELLED'),
    );
    await host.advance();
    await host.remove();
  });

  testWidgets(
    'automatic save cancellation waits for the guarded vault outcome',
    (tester) async {
      final host = _SshHost(tester);
      await host.mount(
        automatic: true,
        reconnect: const SshReconnectTarget(
          mode: SshReconnectMode.auto,
          host: 'server.invalid',
          port: 2222,
          username: 'alice',
        ),
      );
      await tester.tap(find.text('Open SSH'));
      await host.advance();
      await host.tapConnect();
      final id =
          host._arguments('ssh.autoConnect').single['requestId'] as String;
      await host.emit({
        'type': 'passwordRequest',
        'hop': 0,
        'host': 'server.invalid',
        'port': 2222,
        'username': 'alice',
        'requestId': id,
        'ticketId': 'pending_save',
        'binding': 'c' * 64,
        'remainingMillis': 25000,
      });
      await host.advance();
      await tester.enterText(
        find.byKey(const Key('ssh-secret-input')),
        'synthetic',
      );
      await tester.tap(find.byKey(const Key('ssh-secret-save')));
      await tester.tap(find.byKey(const Key('ssh-secret-submit')));
      await host.advance();
      expect(host._arguments('ssh.passwordResponse').single['save'], isTrue);
      await tester.tap(find.byKey(const Key('ssh-secret-cancel')));
      await host.advance();
      expect(host.cancelled, contains(id));
      expect(host.releases, isEmpty);
      host.connectResults.single.completeError(
        PlatformException(code: 'VAULT_COMMIT_UNCERTAIN'),
      );
      await host.advance();
      expect(find.text('认证凭据操作结果'), findsOneWidget);
      expect(host.releases, isEmpty);
      await tester.tap(find.text('知道了'));
      await host.advance();
      expect(host.releases, isNotEmpty);
      await host.remove();
    },
  );

  testWidgets(
    'automatic password request cannot bypass an outstanding host review',
    (tester) async {
      final host = _SshHost(tester);
      await host.mount(automatic: true);
      await tester.tap(find.text('Open SSH'));
      await host.advance();
      for (final field in {
        'ssh-host': 'server.invalid',
        'ssh-port': '2222',
        'ssh-username': 'alice',
      }.entries) {
        await tester.enterText(find.byKey(Key(field.key)), field.value);
      }
      await host.tapConnect();
      final id =
          host._arguments('ssh.autoConnect').single['requestId'] as String;
      await host.emit(_ticket(id));
      expect(find.text('信任并保存'), findsOneWidget);
      await host.emit({
        'type': 'passwordRequest',
        'hop': 0,
        'host': 'server.invalid',
        'port': 2222,
        'username': 'alice',
        'requestId': id,
        'ticketId': 'prompt_1',
        'binding': 'c' * 64,
        'remainingMillis': 25000,
      });
      await host.advance();
      expect(find.byKey(const Key('ssh-secret-dialog')), findsNothing);
      expect(host._arguments('ssh.passwordResponse'), isEmpty);
      expect(host.cancelled, contains(id));
      host.connectResults.single.completeError(
        PlatformException(code: 'SSH_CANCELLED'),
      );
      await host.advance();
      await host.remove();
    },
  );

  testWidgets(
    'automatic public-key-only rejection asks the user to select a key',
    (tester) async {
      final host = _SshHost(tester);
      await host.mount(automatic: true);
      await tester.tap(find.text('Open SSH'));
      await host.advance();
      for (final field in {
        'ssh-host': 'server.invalid',
        'ssh-port': '2222',
        'ssh-username': 'alice',
      }.entries) {
        await tester.enterText(find.byKey(Key(field.key)), field.value);
      }
      await host.tapConnect();
      host.connectResults.single.completeError(
        PlatformException(code: 'SSH_PUBLICKEY_REQUIRED'),
      );
      await host.advance();
      expect(find.text('服务器要求公钥登录，请选择对应私钥后重试。'), findsOneWidget);
      expect(find.byKey(const Key('ssh-secret-dialog')), findsNothing);
      await host.leave();
      await host.remove();
    },
  );

  testWidgets(
    'automatic rejected selected key shows the key error from either failure path',
    (tester) async {
      for (final eventFirst in [false, true]) {
        final host = _SshHost(tester)..libraryValues = _reconnectLibrary();
        await host.mount(
          automatic: true,
          reconnect: const SshReconnectTarget(
            mode: SshReconnectMode.auto,
            host: 'server.invalid',
            port: 2222,
            username: 'alice',
            keyId: 'key_a',
          ),
        );
        await tester.tap(find.text('Open SSH'));
        await host.advance();
        await host.tapConnect();
        final id =
            host._arguments('ssh.autoConnect').single['requestId'] as String;
        final failure = {
          'type': 'state',
          'requestId': id,
          'state': 'failed',
          'reason': 'SSH_KEY_REJECTED',
        };
        if (eventFirst) await host.emit(failure);
        host.connectResults.single.completeError(
          PlatformException(code: 'SSH_KEY_REJECTED'),
        );
        await host.advance();
        if (!eventFirst) await host.emit(failure);
        await host.advance();
        expect(find.text('私钥登录被拒绝，请核对用户名、公钥授权和所选私钥。'), findsOneWidget);
        expect(find.byKey(const Key('ssh-secret-dialog')), findsNothing);
        await host.leave();
        await host.remove();
      }
    },
  );

  testWidgets(
    'automatic page retains the standalone keyboard-interactive entry',
    (tester) async {
      final host = _SshHost(tester);
      await host.mount(automatic: true);
      await tester.tap(find.text('Open SSH'));
      await host.advance();
      for (final field in {
        'ssh-host': 'server.invalid',
        'ssh-port': '2222',
        'ssh-username': 'alice',
      }.entries) {
        await tester.enterText(find.byKey(Key(field.key)), field.value);
      }
      await tester.tap(find.byKey(const Key('ssh-other-authentication')));
      await host.advance();
      await tester.tap(find.byKey(const Key('ssh-auth-keyboard')));
      await host.advance();
      await host.tapConnect();
      final request = host._arguments('ssh.keyboardConnect').single;
      expect(request.containsKey('password'), isFalse);
      host.connectResults.single.completeError(
        PlatformException(code: 'SSH_KEYBOARD_AUTH'),
      );
      await host.advance();
      await host.leave();
      await host.remove();
    },
  );

  testWidgets(
    'automatic configured host can connect without any available private key',
    (tester) async {
      final host = _SshHost(tester)
        ..libraryValues = {
          'keys': <Object>[],
          'hosts': [
            {
              'alias': 'fixture',
              'binding': 'b' * 64,
              'host': 'server.invalid',
              'port': 2222,
              'username': 'alice',
              'keyIds': <String>[],
            },
          ],
          'hasConfig': true,
        };
      await host.mount(
        automatic: true,
        reconnect: const SshReconnectTarget(
          mode: SshReconnectMode.configAuto,
          host: 'server.invalid',
          port: 2222,
          username: 'alice',
          configAlias: 'fixture',
        ),
      );
      await tester.tap(find.text('Open SSH'));
      await host.advance();
      expect(find.text('配置：fixture'), findsOneWidget);
      expect(find.byKey(const Key('ssh-password')), findsNothing);
      await host.tapConnect();
      final request = host._arguments('ssh.configAutoConnect').single;
      expect(request['keyId'], '');
      expect(request['alias'], 'fixture');
      expect(request.containsKey('password'), isFalse);
      final id = request['requestId'] as String;
      host.connectResults.single.complete({
        'requestId': id,
        'status': 'connected',
      });
      await host.advance();
      await host.advance();
      expect(find.byType(SshPasswordConnectPage), findsNothing);
      await host.remove();
    },
  );

  testWidgets(
    'automatic reconnect never silently drops an unavailable selected key or config',
    (tester) async {
      final host = _SshHost(tester)
        ..libraryValues = _reconnectLibrary(includeKey: false);
      await host.mount(
        automatic: true,
        reconnect: const SshReconnectTarget(
          mode: SshReconnectMode.auto,
          host: 'server.invalid',
          port: 2222,
          username: 'alice',
          keyId: 'key_a',
        ),
      );
      await tester.tap(find.text('Open SSH'));
      await host.advance();
      await host.tapConnect();
      await host.advance();
      expect(host.connectResults, isEmpty);
      expect(find.text('上次选择的私钥已不可用，请重新选择或明确选择不使用私钥。'), findsOneWidget);
      await tester.ensureVisible(
        find.byKey(const Key('ssh-clear-private-key')),
      );
      await tester.tap(find.byKey(const Key('ssh-clear-private-key')));
      await host.advance();
      await host.tapConnect();
      final request = host._arguments('ssh.autoConnect').single;
      expect(request['keyId'], '');
      host.connectResults.single.completeError(
        PlatformException(code: 'SSH_CANCELLED'),
      );
      await host.advance();
      await host.leave();
      await host.remove();

      final changed = _SshHost(tester)
        ..libraryValues = _reconnectLibrary(includeKey: false);
      await changed.mount(
        automatic: true,
        reconnect: const SshReconnectTarget(
          mode: SshReconnectMode.configAuto,
          host: 'server.invalid',
          port: 2222,
          username: 'alice',
          configAlias: 'fixture',
        ),
      );
      await tester.tap(find.text('Open SSH'));
      await changed.advance();
      expect(find.byKey(const Key('ssh-connect')), findsNothing);
      expect(changed.connectResults, isEmpty);
      await changed.leave();
      await changed.remove();
    },
  );

  testWidgets(
    'automatic success waits for the exact password response acknowledgement',
    (tester) async {
      for (final accepted in [true, false]) {
        final host = _SshHost(tester)
          ..pendingSecretResponse = Completer<Map<String, Object>>();
        await host.mount(automatic: true);
        await tester.tap(find.text('Open SSH'));
        await host.advance();
        for (final field in {
          'ssh-host': 'server.invalid',
          'ssh-port': '2222',
          'ssh-username': 'alice',
        }.entries) {
          await tester.enterText(find.byKey(Key(field.key)), field.value);
        }
        await host.tapConnect();
        final id =
            host._arguments('ssh.autoConnect').single['requestId'] as String;
        await host.emit({
          'type': 'passwordRequest',
          'hop': 0,
          'host': 'server.invalid',
          'port': 2222,
          'username': 'alice',
          'requestId': id,
          'ticketId': 'prompt_1',
          'binding': 'c' * 64,
          'remainingMillis': 25000,
        });
        await host.advance();
        await tester.enterText(
          find.byKey(const Key('ssh-secret-input')),
          'synthetic',
        );
        await tester.tap(find.byKey(const Key('ssh-secret-submit')));
        await host.advance();
        await host.emit({
          'type': 'passwordRequestDismissed',
          'requestId': id,
          'ticketId': 'prompt_1',
        });
        host.connectResults.single.complete({
          'requestId': id,
          'status': 'connected',
        });
        await host.advance();
        expect(find.byType(SshPasswordConnectPage), findsOneWidget);
        host.pendingSecretResponse!.complete({'accepted': accepted});
        await host.advance();
        await host.advance();
        if (accepted)
          expect(find.byType(SshPasswordConnectPage), findsNothing);
        else {
          expect(host.cancelled, contains(id));
          expect(find.byType(SshPasswordConnectPage), findsOneWidget);
        }
        await host.remove();
      }
    },
  );

  testWidgets(
    'automatic prompt is destroyed on background and late replies cannot reconnect',
    (tester) async {
      final host = _SshHost(tester);
      await host.mount(automatic: true);
      await tester.tap(find.text('Open SSH'));
      await host.advance();
      for (final field in {
        'ssh-host': 'server.invalid',
        'ssh-port': '2222',
        'ssh-username': 'alice',
      }.entries) {
        await tester.enterText(find.byKey(Key(field.key)), field.value);
      }
      await host.tapConnect();
      final id =
          host._arguments('ssh.autoConnect').single['requestId'] as String;
      final event = <String, Object>{
        'type': 'passwordRequest',
        'hop': 0,
        'host': 'server.invalid',
        'port': 2222,
        'username': 'alice',
        'requestId': id,
        'ticketId': 'prompt_1',
        'binding': 'c' * 64,
        'remainingMillis': 25000,
      };
      await host.emit(event);
      await host.advance();
      await tester.enterText(
        find.byKey(const Key('ssh-secret-input')),
        'synthetic',
      );
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      await host.advance();
      expect(find.byKey(const Key('ssh-secret-dialog')), findsNothing);
      expect(host.cancelled, contains(id));
      expect(host._arguments('ssh.passwordResponse'), isEmpty);
      await host.emit(event);
      expect(find.byKey(const Key('ssh-secret-dialog')), findsNothing);
      host.connectResults.single.completeError(
        PlatformException(code: 'SSH_CANCELLED'),
      );
      await host.advance();
      await host.remove();
    },
  );

  testWidgets(
    'automatic authentication switches between server questions and password',
    (tester) async {
      for (final keyboardFirst in [true, false]) {
        final host = _SshHost(tester);
        await host.mount(automatic: true);
        await tester.tap(find.text('Open SSH'));
        await host.advance();
        for (final field in {
          'ssh-host': 'server.invalid',
          'ssh-port': '2222',
          'ssh-username': 'alice',
        }.entries) {
          await tester.enterText(find.byKey(Key(field.key)), field.value);
        }
        await host.tapConnect();
        final id =
            host._arguments('ssh.autoConnect').single['requestId'] as String;
        Future<void> password() async {
          await host.emit({
            'type': 'passwordRequest',
            'hop': 0,
            'host': 'server.invalid',
            'port': 2222,
            'username': 'alice',
            'requestId': id,
            'ticketId': 'password_ticket',
            'binding': 'c' * 64,
            'remainingMillis': 25000,
          });
          await host.advance();
          expect(find.text('输入登录密码'), findsOneWidget);
          await tester.enterText(
            find.byKey(const Key('ssh-secret-input')),
            'synthetic',
          );
          await tester.tap(find.byKey(const Key('ssh-secret-submit')));
          await host.advance();
          await host.emit({
            'type': 'passwordRequestDismissed',
            'requestId': id,
            'ticketId': 'password_ticket',
          });
        }

        Future<void> keyboard() async {
          await host.emit({
            'type': 'keyboardChallenge',
            'hop': 0,
            'host': 'server.invalid',
            'port': 2222,
            'username': 'alice',
            'requestId': id,
            'ticketId': 'question_ticket',
            'binding': 'e' * 64,
            'round': 1,
            'name': '',
            'instruction': '',
            'prompts': [
              {'text': 'Code:', 'echo': false},
            ],
            'remainingMillis': 25000,
          });
          await host.advance();
          expect(find.text('Code:'), findsOneWidget);
          await tester.enterText(
            find.byKey(const Key('ssh-keyboard-answer-0')),
            'synthetic',
          );
          await tester.tap(find.byKey(const Key('ssh-keyboard-submit')));
          await host.advance();
          await host.emit({
            'type': 'keyboardChallengeDismissed',
            'requestId': id,
            'ticketId': 'question_ticket',
          });
        }

        if (keyboardFirst) {
          await keyboard();
          await password();
        } else {
          await password();
          await keyboard();
        }
        expect(host._arguments('ssh.passwordResponse'), hasLength(1));
        expect(host._arguments('ssh.keyboardResponse'), hasLength(1));
        host.connectResults.single.complete({
          'requestId': id,
          'status': 'connected',
        });
        await host.advance();
        await host.advance();
        expect(find.byType(SshPasswordConnectPage), findsNothing);
        await host.remove();
      }
    },
  );

  testWidgets(
    'automatic secret prompt remains usable on narrow and large-text displays',
    (tester) async {
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetViewInsets);
      for (final scenario in [
        (const Size(320, 680), 2.0, 230.0, false),
        (const Size(640, 360), 2.0, 110.0, false),
        (const Size(320, 680), 2.0, 230.0, true),
        (const Size(640, 360), 2.0, 110.0, true),
      ]) {
        tester.view.physicalSize = scenario.$1;
        final host = _SshHost(tester);
        await host.mount(
          automatic: true,
          scale: scenario.$2,
          keyboard: scenario.$3,
        );
        await tester.tap(find.text('Open SSH'));
        await host.advance();
        for (final field in {
          'ssh-host': 'server.invalid',
          'ssh-port': '2222',
          'ssh-username': 'alice',
        }.entries) {
          await tester.ensureVisible(find.byKey(Key(field.key)));
          await tester.enterText(find.byKey(Key(field.key)), field.value);
        }
        await host.tapConnect();
        final id =
            host._arguments('ssh.autoConnect').single['requestId'] as String;
        await host.emit({
          'type': 'passwordRequest',
          'hop': 0,
          'host': 'server.invalid',
          'port': 2222,
          'username': 'alice',
          'requestId': id,
          'ticketId': 'prompt_1',
          'binding': 'c' * 64,
          'remainingMillis': 25000,
          'savedAvailable': true,
          'savedOnly': scenario.$4,
        });
        await host.advance();
        expect(tester.takeException(), isNull);
        if (!scenario.$4) {
          await tester.ensureVisible(find.byKey(const Key('ssh-secret-save')));
          expect(
            tester.getSize(find.byKey(const Key('ssh-secret-save'))).height,
            greaterThanOrEqualTo(48),
          );
        }
        await tester.ensureVisible(find.byKey(const Key('ssh-secret-submit')));
        expect(
          tester.getSize(find.byKey(const Key('ssh-secret-submit'))).height,
          greaterThanOrEqualTo(48),
        );
        await tester.tap(find.byKey(const Key('ssh-secret-cancel')));
        await tester.pump();
        host.connectResults.single.completeError(
          PlatformException(code: 'SSH_CANCELLED'),
        );
        await host.advance();
        await host.advance();
        expect(host.cancelled, contains(id));
        await host.remove();
      }
    },
  );

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

  testWidgets('partial key authentication reports a neutral next step', (
    tester,
  ) async {
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
    await host.tapConnect();
    host.completeConnect(error: PlatformException(code: 'SSH_AUTH_PARTIAL'));
    await host.advance();
    expect(find.text('服务器要求进一步认证，但当前连接无法继续。请检查服务器及配置。'), findsOneWidget);
    expect(find.textContaining('私钥登录被拒绝'), findsNothing);
    expect(host.identityCalls, hasLength(1));
    await host.leave();
    await host.remove();
  });

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
    expect(find.text('配置：fixture'), findsOneWidget);
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
    expect(find.byKey(const Key('ssh-other-authentication')), findsOneWidget);
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
    expect(find.byKey(const Key('ssh-other-authentication')), findsOneWidget);
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
      expect(find.byKey(const Key('ssh-other-authentication')), findsOneWidget);
      await host.leave();
      await host.remove();
    },
  );

  test('SSH field validators preserve exact text and reject malformed input', () {
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
      '主机密钥与已保存记录不同，连接已拒绝。可在“私钥与配置 → 主机信任”核对并管理旧记录。',
    );
    expect(
      sshConnectionError(PlatformException(code: 'SSH_JUMP_AUTH_UNSUPPORTED')),
      '跳板连接目前支持已保存密码或免认证。若服务器支持密码，请在对应主机的 SSH 配置中设置 PreferredAuthentications password，连接时选择“自动检测”并确保未选择私钥。',
    );
    expect(
      sshConnectionError(PlatformException(code: 'SSH_JUMP_PASSWORD_MISSING')),
      '跳板或目标尚未保存密码。当前跳板连接无法输入新密码。',
    );
    expect(
      sshConnectionError(PlatformException(code: 'SSH_JUMP_EFFECT_GUARD')),
      '此跳板连接暂不可用，连接已停止。',
    );
    expect(
      sshConnectionError(PlatformException(code: 'SSH_JUMP_USER_REQUIRED')),
      '跳板缺少用户名，请在 SSH 配置中为跳板指定用户名。',
    );
  });

  test('SSH events have a closed metadata-only protocol', () {
    final good = _ticket('attempt');
    final event = SshEvent.parse(good) as SshHostKeyEvent;
    expect(event.requestId, 'attempt');
    expect(event.host, 'server.invalid');
    expect(event.port, 2222);
    expect(event.hop, 0);
    expect(event.remainingMillis, 30000);
    for (final bad in [
      {...good, 'password': 'must-not-display'},
      {...good, 'remainingMillis': 30001},
      {...good}..remove('hop'),
      {...good, 'hop': -1},
      {...good, 'hop': 9},
      {...good, 'hop': '0'},
      {...good, 'port': 22.0},
      {...good, 'binding': 'not-a-binding'},
      {...good, 'binding': 'A' * 64},
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
        'binding': 'a' * 64,
        'hop': 0,
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

  test(
    'changed-host challenge is bounded, canonical and preserves all rows',
    () {
      final good = _changedTicket('attempt');
      final parsed = SshEvent.parse(good) as SshHostKeyChangedEvent;
      expect(parsed.host, 'server.invalid');
      expect(parsed.hop, 0);
      expect(parsed.source, 'ssh/known_hosts');
      expect(parsed.oldKeys.map((row) => row.fingerprint), [
        _changedOldFingerprint,
        _changedOldFingerprint,
        _changedOtherFingerprint,
      ]);
      expect(() => parsed.oldKeys.clear(), throwsUnsupportedError);
      final config =
          SshEvent.parse({...good, 'source': '.ssh/known_hosts'})
              as SshHostKeyChangedEvent;
      expect(config.source, '.ssh/known_hosts');
      for (final bad in <Map<String, Object>>[
        {...good, 'unexpected': true},
        {...good}..remove('source'),
        {...good}..remove('hop'),
        {...good, 'hop': 9},
        {...good, 'source': '/etc/known_hosts'},
        {...good, 'source': 'ssh/../known_hosts'},
        {...good, 'source': 'ssh//known_hosts'},
        {...good, 'source': r'ssh\known_hosts'},
        {...good, 'source': 'ssh/known*'},
        {...good, 'source': 'a/b/c/d/e/f/g/h/i'},
        {...good, 'source': 'a' * 513},
        {...good, 'oldKeys': <Object>[]},
        {
          ...good,
          'oldKeys': List.filled(129, {
            'keyType': 'ssh-ed25519',
            'fingerprint': _changedOldFingerprint,
          }),
        },
        {
          ...good,
          'oldKeys': [
            {'keyType': 'x' * 129, 'fingerprint': _changedOldFingerprint},
          ],
        },
        {...good, 'keyType': 'x' * 65},
        {...good, 'keyType': 'ssh invalid'},
        {...good, 'fingerprint': 'SHA256:${'B' * 43}'},
        {...good, 'remainingMillis': 0},
        {...good, 'remainingMillis': 30001},
        {...good, 'binding': 'not-an-exact-binding'},
      ]) {
        expect(() => SshEvent.parse(bad), throwsFormatException);
      }
      final dismissed =
          SshEvent.parse({
                'type': 'hostKeyChangedDismissed',
                'requestId': 'attempt',
                'ticketId': 'ticket-a',
                'binding': 'a' * 64,
                'hop': 0,
              })
              as SshHostKeyChangedDismissed;
      expect(dismissed.ticketId, 'ticket-a');
      expect(dismissed.binding, 'a' * 64);
      expect(
        () => SshEvent.parse({
          'type': 'hostKeyChangedDismissed',
          'requestId': 'attempt',
          'ticketId': 'ticket-a',
        }),
        throwsFormatException,
      );
    },
  );

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
      expect(find.text('使用登录密码验证身份；密码默认仅本次使用。'), findsOneWidget);
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
    expect(find.byKey(const Key('ssh-other-authentication')), findsOneWidget);
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
      // The state event is advisory; the original RPC owns the handoff.
      expect(find.byKey(const Key('ssh-password')), findsOneWidget);
      host.completeConnect();
      await host.advance();
      await host.advance();
      expect(find.byKey(const Key('ssh-password')), findsNothing);
      expect(host.cancelled, isNot(contains(second)));
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
      await tester.pump(const Duration(milliseconds: 300));
      await tester.ensureVisible(use);
      await tester.pump();
      expect(use.hitTestable(), findsOneWidget);
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
        await host.openManagement();
        final action = find.byKey(
          Key(all ? 'ssh-forget-all-passwords' : 'ssh-forget-password'),
        );
        await tester.ensureVisible(action);
        await host.advance();
        await tester.tap(action);
        await host.advance();
        expect(host.forgetCalls, isEmpty);
        await tester.tap(find.text('取消'));
        await host.advance();
        expect(host.forgetCalls, isEmpty);
        await tester.ensureVisible(action);
        await host.advance();
        await tester.ensureVisible(action);
        await tester.pump();
        expect(action.hitTestable(), findsOneWidget);
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
      await host.openManagement();
      final action = find.byKey(const Key('ssh-forget-password'));
      await tester.ensureVisible(action);
      await host.advance();
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
      await host.openManagement();
      final action = find.byKey(const Key('ssh-forget-password'));
      await tester.ensureVisible(action);
      await host.advance();
      await tester.tap(action);
      await host.advance();
      await tester.tap(find.byKey(const Key('ssh-forget-confirm')));
      await host.advance();
      expect(find.byKey(const Key('ssh-password')), findsNothing);
      expect(find.text('已忘记保存的密码。'), findsNothing);
      expect(find.text('保存结果未能确认，请重新解锁并核验连接信息、SSH 凭据或主机密钥。'), findsOneWidget);
      await host.remove();
    },
  );

  testWidgets('all-password forget works before any recent connection exists', (
    tester,
  ) async {
    final host = _SshHost(tester);
    await host.open();
    await host.openManagement();
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
      await host.openManagement();
      final action = find.byKey(const Key('ssh-forget-password'));
      await tester.ensureVisible(action);
      await host.advance();
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
      expect(find.text('保存结果未能确认，请重新解锁并核验连接信息、SSH 凭据或主机密钥。'), findsWidgets);
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

  testWidgets(
    'changed host displays every fingerprint and continues the same attempt',
    (tester) async {
      final host = _SshHost(tester);
      await host.open();
      await host.fill();
      await host.tapConnect();
      final id = host.connectCalls.single['requestId'] as String;
      final ticket = _changedTicket(id);
      await host.emit(ticket);
      await host.advance();
      expect(find.text('服务器指纹已变化'), findsOneWidget);
      expect(find.text('server.invalid:2222'), findsOneWidget);
      expect(find.text('本次收到的新指纹'), findsOneWidget);
      expect(find.text(_changedNewFingerprint), findsOneWidget);
      expect(find.text(_changedOldFingerprint), findsNWidgets(2));
      expect(find.text(_changedOtherFingerprint), findsOneWidget);
      expect(find.text('ssh/known_hosts'), findsNothing);
      await tester.ensureVisible(
        find.byKey(const Key('ssh-changed-host-details')),
      );
      await tester.tap(find.byKey(const Key('ssh-changed-host-details')));
      await host.advance();
      expect(find.text('ssh/known_hosts'), findsOneWidget);
      await host.emit(ticket);
      await tester.ensureVisible(
        find.byKey(const Key('ssh-changed-host-accept')),
      );
      await tester.tap(find.byKey(const Key('ssh-changed-host-accept')));
      await host.advance();
      expect(host.decisions, [
        {
          'requestId': id,
          'ticketId': 'changed-ticket',
          'binding': 'a' * 64,
          'accept': true,
        },
      ]);
      expect(host.connectResults, hasLength(1));
      expect(
        host.calls.where((call) => call.method.startsWith('ssh.hostTrust')),
        isEmpty,
      );
      host.completeConnect();
      await host.advance();
      expect(find.text('Open SSH'), findsOneWidget);
      expect(host.cancelled, isNot(contains(id)));
      await host.remove();
    },
  );

  testWidgets('host review rejects changed metadata on an existing ticket', (
    tester,
  ) async {
    for (final mutation in [
      {'hop': 1},
      {'host': 'other.invalid'},
      {'port': 2201},
      {'keyType': 'ssh-rsa'},
      {'fingerprint': 'SHA256:${'B' * 43}'},
    ]) {
      final host = _SshHost(tester);
      await host.open();
      await host.fill();
      await host.tapConnect();
      final id = host.connectCalls.single['requestId'] as String;
      final ticket = _ticket(id);
      await host.emit(ticket);
      await host.advance();
      expect(find.text('确认主机密钥'), findsOneWidget);
      await host.emit({...ticket, ...mutation});
      await host.advance();
      expect(find.text('确认主机密钥'), findsNothing);
      expect(host.decisions, isEmpty);
      expect(host.cancelled, contains(id));
      host.completeConnect(error: PlatformException(code: 'SSH_CANCELLED'));
      await host.advance();
      await host.remove();
    }
  });

  testWidgets('host review rejects a late prior hop and mismatched dismissal', (
    tester,
  ) async {
    for (final mismatch in ['old-hop', 'dismiss-hop', 'dismiss-binding']) {
      final host = _SshHost(tester);
      await host.open();
      await host.fill();
      await host.tapConnect();
      final id = host.connectCalls.single['requestId'] as String;
      final first = _ticket(id);
      await host.emit(first);
      await host.advance();
      await tester.tap(find.text('信任并保存'));
      await host.advance();
      final next = {
        ...first,
        'ticketId': 'ticket-b',
        'binding': 'b' * 64,
        'hop': 1,
      };
      await host.emit(next);
      await host.advance();
      expect(find.text('确认主机密钥'), findsOneWidget);
      if (mismatch == 'old-hop') {
        await host.emit({...first, 'ticketId': 'old-ticket'});
      } else {
        await host.emit({
          'type': 'hostKeyDismissed',
          'requestId': id,
          'ticketId': 'ticket-b',
          'binding': mismatch == 'dismiss-binding' ? 'c' * 64 : 'b' * 64,
          'hop': mismatch == 'dismiss-hop' ? 0 : 1,
        });
      }
      await host.advance();
      expect(find.text('确认主机密钥'), findsNothing);
      expect(host.decisions, hasLength(1));
      expect(host.cancelled, contains(id));
      host.completeConnect(error: PlatformException(code: 'SSH_CANCELLED'));
      await host.advance();
      await host.remove();
    }
  });

  testWidgets('changed-host reviews await every hop decision acknowledgement', (
    tester,
  ) async {
    for (final firstAccepted in [true, false]) {
      final firstAck = Completer<Map<String, Object>>();
      final secondAck = Completer<Map<String, Object>>();
      final host = _SshHost(tester)..pendingDecision = firstAck;
      await host.open();
      await host.fill();
      await host.tapConnect();
      final id = host.connectCalls.single['requestId'] as String;
      await host.emit(_changedTicket(id));
      await host.advance();
      await tester.tap(find.byKey(const Key('ssh-changed-host-accept')));
      await host.advance();
      await host.emit({
        'type': 'hostKeyChangedDismissed',
        'requestId': id,
        'ticketId': 'changed-ticket',
        'binding': 'a' * 64,
        'hop': 0,
      });
      host.pendingDecision = secondAck;
      await host.emit({
        ..._changedTicket(id),
        'ticketId': 'changed-ticket-2',
        'binding': 'b' * 64,
        'hop': 1,
      });
      await host.advance();
      expect(find.text('服务器指纹已变化'), findsOneWidget);
      await tester.tap(find.byKey(const Key('ssh-changed-host-accept')));
      await host.advance();
      expect(host.decisions, hasLength(2));
      secondAck.complete({'accepted': true});
      await host.emit({'type': 'state', 'requestId': id, 'state': 'connected'});
      host.completeConnect();
      await host.advance();
      expect(find.text('Open SSH'), findsNothing);
      firstAck.complete({'accepted': firstAccepted});
      await host.advance();
      if (firstAccepted) {
        expect(find.text('Open SSH'), findsOneWidget);
      } else {
        expect(find.text('Open SSH'), findsNothing);
        expect(host.cancelled, contains(id));
      }
      await host.remove();
    }
  });

  testWidgets('new-host review waits for its own decision acknowledgement', (
    tester,
  ) async {
    for (final accepted in [true, false]) {
      final ack = Completer<Map<String, Object>>();
      final host = _SshHost(tester)..pendingDecision = ack;
      await host.open();
      await host.fill();
      await host.tapConnect();
      final id = host.connectCalls.single['requestId'] as String;
      await host.emit(_ticket(id));
      await host.advance();
      await tester.tap(find.text('信任并保存'));
      await host.advance();
      await host.emit({
        'type': 'hostKeyDismissed',
        'requestId': id,
        'ticketId': 'ticket-a',
        'binding': 'a' * 64,
        'hop': 0,
      });
      await host.emit({'type': 'state', 'requestId': id, 'state': 'connected'});
      host.completeConnect();
      await host.advance();
      expect(find.text('Open SSH'), findsNothing);
      ack.complete({'accepted': accepted});
      await host.advance();
      if (accepted) {
        expect(find.text('Open SSH'), findsOneWidget);
      } else {
        expect(find.text('Open SSH'), findsNothing);
        expect(host.cancelled, contains(id));
      }
      await host.remove();
    }
  });

  testWidgets('mixed host reviews retain earlier acknowledgement ownership', (
    tester,
  ) async {
    for (final firstChanged in [false, true]) {
      final firstAck = Completer<Map<String, Object>>();
      final secondAck = Completer<Map<String, Object>>();
      final host = _SshHost(tester)..pendingDecision = firstAck;
      await host.open();
      await host.fill();
      await host.tapConnect();
      final id = host.connectCalls.single['requestId'] as String;
      final first = firstChanged ? _changedTicket(id) : _ticket(id);
      await host.emit(first);
      await host.advance();
      await tester.tap(
        firstChanged
            ? find.byKey(const Key('ssh-changed-host-accept'))
            : find.text('信任并保存'),
      );
      await host.advance();
      await host.emit({
        'type': firstChanged ? 'hostKeyChangedDismissed' : 'hostKeyDismissed',
        'requestId': id,
        'ticketId': first['ticketId']!,
        'binding': first['binding']!,
        'hop': 0,
      });
      host.pendingDecision = secondAck;
      final second = firstChanged ? _ticket(id) : _changedTicket(id);
      await host.emit({
        ...second,
        'ticketId': 'second-ticket',
        'binding': 'b' * 64,
        'hop': 1,
      });
      await host.advance();
      await tester.tap(
        firstChanged
            ? find.text('信任并保存')
            : find.byKey(const Key('ssh-changed-host-accept')),
      );
      await host.advance();
      secondAck.complete({'accepted': true});
      await host.emit({'type': 'state', 'requestId': id, 'state': 'connected'});
      host.completeConnect();
      await host.advance();
      expect(find.text('Open SSH'), findsNothing);
      firstAck.complete({'accepted': false});
      await host.advance();
      expect(find.text('Open SSH'), findsNothing);
      expect(host.cancelled, contains(id));
      await host.remove();
    }
  });

  testWidgets(
    'untrusted changed-host endpoint is not authorized by hop alone',
    (tester) async {
      final host = _SshHost(tester);
      await host.open();
      await host.fill();
      await host.tapConnect();
      final id = host.connectCalls.single['requestId'] as String;
      await host.emit({
        ..._changedTicket(id),
        'hop': 1,
        'host': 'jump.invalid',
        'port': 2201,
      });
      await host.advance();
      expect(find.text('主机信息已变化，请重新连接。'), findsOneWidget);
      expect(find.text('服务器指纹已变化'), findsNothing);
      expect(host.decisions, isEmpty);
      expect(host.cancelled, contains(id));
      host.completeConnect(error: PlatformException(code: 'SSH_CANCELLED'));
      await host.advance();
      await host.remove();
    },
  );

  testWidgets('host review displays unambiguous IPv6 endpoints', (
    tester,
  ) async {
    for (final (changed, endpoint) in [
      (false, '2001:db8::1'),
      (true, '2001:db8::1'),
      (false, '[2001:db8::1]'),
      (true, '[2001:db8::1]'),
    ]) {
      final host = _SshHost(tester);
      await host.open();
      await host.fill();
      await tester.enterText(find.byKey(const Key('ssh-host')), endpoint);
      await tester.enterText(
        find.byKey(const Key('ssh-password')),
        'synthetic password',
      );
      await host.tapConnect();
      final id = host.connectCalls.single['requestId'] as String;
      await host.emit({
        ...(changed ? _changedTicket(id) : _ticket(id)),
        'host': endpoint,
      });
      await host.advance();
      expect(find.text('[2001:db8::1]:2222'), findsOneWidget);
      host.completeConnect(error: PlatformException(code: 'SSH_CANCELLED'));
      await host.advance();
      await host.remove();
    }
  });

  testWidgets('changed-host actions use the same rounded state layer', (
    tester,
  ) async {
    final host = _SshHost(tester);
    await host.open();
    await host.fill();
    await host.tapConnect();
    final id = host.connectCalls.single['requestId'] as String;
    await host.emit(_changedTicket(id));
    await host.advance();
    const reject = 'ssh-changed-host-reject';
    _expectChangedHostActionShape(tester, reject);
    _expectChangedHostActionShape(tester, 'ssh-changed-host-accept');
    final mouse = await tester.createGesture(kind: PointerDeviceKind.mouse);
    await mouse.addPointer(location: Offset.zero);
    await mouse.moveTo(tester.getCenter(find.byKey(const Key(reject))));
    await tester.pump();
    expect(
      _changedHostActionInk(tester, reject).statesController!.value,
      contains(WidgetState.hovered),
    );
    _expectChangedHostActionShape(tester, reject);
    await mouse.down(tester.getCenter(find.byKey(const Key(reject))));
    await tester.pump(const Duration(milliseconds: 150));
    expect(
      _changedHostActionInk(tester, reject).statesController!.value,
      contains(WidgetState.pressed),
    );
    _expectChangedHostActionShape(tester, reject);
    await mouse.cancel();
    await mouse.removePointer();
    Focus.of(
      tester.element(
        find.descendant(
          of: find.byKey(const Key(reject)),
          matching: find.text('取消'),
        ),
      ),
    ).requestFocus();
    await tester.pump();
    expect(
      _changedHostActionInk(tester, reject).statesController!.value,
      contains(WidgetState.focused),
    );
    _expectChangedHostActionShape(tester, reject);
    host.completeConnect(error: PlatformException(code: 'SSH_HOSTKEY_DENIED'));
    await host.advance();
    await host.remove();
  });

  testWidgets('changed-host reject, expiry and forged pop never approve', (
    tester,
  ) async {
    for (final scenario in ['reject', 'expiry', 'forged']) {
      final host = _SshHost(tester);
      await host.open();
      await host.fill();
      await host.tapConnect();
      final id = host.connectCalls.single['requestId'] as String;
      await host.emit({
        ..._changedTicket(id),
        'remainingMillis': scenario == 'expiry' ? 500 : 30000,
      });
      await host.advance(milliseconds: 100);
      if (scenario == 'reject') {
        await tester.tap(find.byKey(const Key('ssh-changed-host-reject')));
      } else if (scenario == 'expiry') {
        await tester.pump(const Duration(milliseconds: 501));
      } else {
        Navigator.of(tester.element(find.text('服务器指纹已变化'))).pop(true);
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

  testWidgets('changed-host dismissal and changed target revoke the decision', (
    tester,
  ) async {
    for (final changedTarget in [false, true]) {
      final host = _SshHost(tester);
      await host.open();
      await host.fill();
      await host.tapConnect();
      final id = host.connectCalls.single['requestId'] as String;
      await host.emit({
        ..._changedTicket(id),
        if (changedTarget) 'host': 'another.invalid',
      });
      await host.advance();
      if (changedTarget) {
        expect(find.text('主机信息已变化，请重新连接。'), findsOneWidget);
      } else {
        final stale = tester
            .widget<FilledButton>(
              find.byKey(const Key('ssh-changed-host-accept')),
            )
            .onPressed!;
        await host.emit({
          'type': 'hostKeyChangedDismissed',
          'requestId': id,
          'ticketId': 'changed-ticket',
          'binding': 'a' * 64,
          'hop': 0,
        });
        await host.advance();
        stale();
        await host.advance();
      }
      expect(host.decisions, isEmpty);
      expect(find.text('服务器指纹已变化'), findsNothing);
      host.completeConnect(error: PlatformException(code: 'SSH_CANCELLED'));
      await host.advance();
      await host.remove();
    }
  });

  testWidgets('changed-host waits for the decision acknowledgement and RPC', (
    tester,
  ) async {
    for (final outcome in ['accepted', 'rejected', 'error']) {
      final host = _SshHost(tester)
        ..pendingDecision = Completer<Map<String, Object>>();
      await host.open();
      await host.fill();
      await host.tapConnect();
      final id = host.connectCalls.single['requestId'] as String;
      await host.emit(_changedTicket(id));
      await host.advance();
      await tester.tap(find.byKey(const Key('ssh-changed-host-accept')));
      await host.advance();
      await host.emit({
        'type': 'hostKeyChangedDismissed',
        'requestId': id,
        'ticketId': 'changed-ticket',
        'binding': 'a' * 64,
        'hop': 0,
      });
      await host.emit({'type': 'state', 'requestId': id, 'state': 'connected'});
      host.completeConnect();
      await host.advance();
      expect(find.text('Open SSH'), findsNothing);
      expect(host.releases, isEmpty);
      if (outcome == 'error') {
        host.pendingDecision!.completeError(
          PlatformException(code: 'SSH_FAILED'),
        );
      } else {
        host.pendingDecision!.complete({'accepted': outcome == 'accepted'});
      }
      await host.advance();
      if (outcome == 'accepted') {
        expect(find.text('Open SSH'), findsOneWidget);
      } else {
        expect(find.text('Open SSH'), findsNothing);
        expect(host.cancelled, contains(id));
      }
      await host.remove();
    }
  });

  testWidgets(
    'early changed-host connected event cannot hide uncertain commit',
    (tester) async {
      final host = _SshHost(tester);
      await host.open();
      await host.fill();
      await host.tapConnect();
      final id = host.connectCalls.single['requestId'] as String;
      await host.emit(_changedTicket(id));
      await host.advance();
      await tester.tap(find.byKey(const Key('ssh-changed-host-accept')));
      await host.advance();
      await host.emit({'type': 'state', 'requestId': id, 'state': 'connected'});
      expect(find.text('Open SSH'), findsNothing);
      expect(host.releases, isEmpty);
      host.completeConnect(
        error: PlatformException(code: 'VAULT_COMMIT_UNCERTAIN'),
      );
      await host.advance();
      expect(find.text('保存结果未能确认，请重新解锁并核验连接信息、SSH 凭据或主机密钥。'), findsOneWidget);
      expect(find.text('Open SSH'), findsNothing);
      await host.leave();
      expect(find.text('密码操作结果'), findsOneWidget);
      expect(host.releases, isEmpty);
      await tester.tap(find.text('知道了'));
      await host.advance();
      expect(host.releases, isNotEmpty);
      await host.remove();
    },
  );

  testWidgets(
    'changed-host rejects a connected event before explicit approval',
    (tester) async {
      final host = _SshHost(tester);
      await host.open();
      await host.fill();
      await host.tapConnect();
      final id = host.connectCalls.single['requestId'] as String;
      await host.emit(_changedTicket(id));
      await host.advance();
      await host.emit({'type': 'state', 'requestId': id, 'state': 'connected'});
      await host.advance();
      expect(find.text('主机密钥确认未完成，请重新连接。'), findsOneWidget);
      expect(host.decisions, isEmpty);
      expect(host.cancelled, contains(id));
      host.completeConnect();
      await host.advance();
      expect(find.text('Open SSH'), findsNothing);
      await host.remove();
    },
  );

  testWidgets('all connect attempts retain protection before the first event', (
    tester,
  ) async {
    final host = _SshHost(tester);
    await host.open();
    await host.fill();
    await host.tapConnect();
    final id = host.connectCalls.single['requestId'] as String;
    expect(host.connectCalls.single['autoKeyboardInteractive'], isTrue);
    await host.remove();
    expect(host.releases, isEmpty);
    expect(host.cancelled.where((value) => value == id), hasLength(1));
    host.completeConnect(error: PlatformException(code: 'SSH_CANCELLED'));
    await host.advance();
    expect(host.cancelled.where((value) => value == id), hasLength(2));
    expect(host.releases, isNotEmpty);
  });

  testWidgets(
    'changed-host protection survives disposal until uncertain outcome',
    (tester) async {
      final host = _SshHost(tester);
      await host.open();
      await host.fill();
      await host.tapConnect();
      final id = host.connectCalls.single['requestId'] as String;
      await host.emit(_changedTicket(id));
      await host.advance();
      await tester.tap(find.byKey(const Key('ssh-changed-host-accept')));
      await host.advance();
      await host.remove();
      expect(host.releases, isEmpty);
      host.completeConnect(
        error: PlatformException(code: 'VAULT_COMMIT_UNCERTAIN'),
      );
      await host.advance();
      expect(host.releases, isNotEmpty);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('cancel after changed-host acceptance closes late handoff', (
    tester,
  ) async {
    final host = _SshHost(tester);
    await host.open();
    await host.fill();
    await host.tapConnect();
    final id = host.connectCalls.single['requestId'] as String;
    await host.emit(_changedTicket(id));
    await host.advance();
    await tester.tap(find.byKey(const Key('ssh-changed-host-accept')));
    await host.advance();
    await host.leave();
    expect(host.releases, isEmpty);
    expect(host.cancelled.where((value) => value == id), hasLength(1));
    host.completeConnect();
    await host.advance();
    expect(host.cancelled.where((value) => value == id), hasLength(2));
    expect(find.text('Open SSH'), findsOneWidget);
    expect(host.releases, isNotEmpty);
    await host.remove();
  });

  testWidgets('blocked actual trust source never opens group recovery', (
    tester,
  ) async {
    for (final (mode, code, message) in [
      (
        SshReconnectMode.password,
        'SSH_HOST_TRUST_BLOCKED',
        '此连接使用的信任文件包含受保护或共享记录，无法直接更新。请在“主机信任”中查看。',
      ),
      (
        SshReconnectMode.key,
        'SSH_HOST_TRUST_BLOCKED',
        '此连接使用的信任文件包含受保护或共享记录，无法直接更新。请在“主机信任”中查看。',
      ),
      (
        SshReconnectMode.config,
        'SSH_HOST_TRUST_BLOCKED',
        '此连接使用的信任文件包含受保护或共享记录，无法直接更新。请在“主机信任”中查看。',
      ),
      (
        SshReconnectMode.config,
        'SSH_HOST_CHANGED_POLICY',
        '此配置要求已信任的主机，不能直接替换。请检查配置或在“主机信任”中管理。',
      ),
    ]) {
      final host = _SshHost(tester)..libraryValues = _reconnectLibrary();
      if (mode == SshReconnectMode.password) {
        await host.open();
        await host.fill();
      } else {
        await host.mount(
          reconnect: SshReconnectTarget(
            mode: mode,
            host: 'server.invalid',
            port: 2222,
            username: 'alice',
            keyId: 'key_a',
            configAlias: mode == SshReconnectMode.config ? 'fixture' : null,
          ),
        );
        await tester.tap(find.text('Open SSH'));
        await host.advance();
      }
      await host.tapConnect();
      host.connectResults.last.completeError(PlatformException(code: code));
      await host.advance();
      expect(find.text(message), findsOneWidget);
      expect(find.text('服务器主机密钥已变化'), findsNothing);
      expect(
        host.calls.where((call) => call.method.startsWith('ssh.hostTrust')),
        isEmpty,
      );
      await host.leave();
      await host.remove();
    }
  });

  testWidgets(
    'changed-host fingerprints must fit or be reviewed before trust',
    (tester) async {
      addTearDown(() => tester.binding.setSurfaceSize(null));
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      tester.view.devicePixelRatio = 1;
      for (final compact in [false, true]) {
        final size = compact ? const Size(320, 568) : const Size(1024, 768);
        tester.view.physicalSize = size;
        await tester.binding.setSurfaceSize(size);
        final host = _SshHost(tester);
        await host.open(scale: compact ? 2 : 1);
        await host.fill();
        await host.tapConnect();
        final id = host.connectCalls.single['requestId'] as String;
        await host.emit({
          ..._changedTicket(id),
          'oldKeys': [
            {'keyType': 'ssh-ed25519', 'fingerprint': _changedOldFingerprint},
          ],
        });
        await host.advance();
        final accept = find.byKey(const Key('ssh-changed-host-accept'));
        if (compact) {
          expect(tester.widget<FilledButton>(accept).onPressed, isNull);
          expect(find.text('滚动查看全部指纹'), findsOneWidget);
          await tester.tap(accept);
          expect(host.decisions, isEmpty);
          expect(
            find.byKey(const Key('ssh-changed-host-reject')).hitTestable(),
            findsOneWidget,
          );
          final scrollView = find.descendant(
            of: find.byKey(const Key('ssh-changed-host-scroll-focus')),
            matching: find.byType(SingleChildScrollView),
          );
          Focus.of(tester.element(scrollView)).requestFocus();
          await tester.pump();
          await tester.sendKeyEvent(LogicalKeyboardKey.end);
          await host.advance();
        }
        expect(tester.widget<FilledButton>(accept).onPressed, isNotNull);
        expect(find.text('信任新指纹并继续'), findsOneWidget);
        await tester.tap(accept);
        await host.advance();
        expect(host.decisions.single['accept'], isTrue);
        host.completeConnect(
          error: PlatformException(code: 'SSH_HOSTKEY_DENIED'),
        );
        await host.advance();
        await host.remove();
      }
    },
  );

  testWidgets('changed-host review exposes accessible scrolling', (
    tester,
  ) async {
    final semantics = tester.ensureSemantics();
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(568, 320);
    await tester.binding.setSurfaceSize(const Size(568, 320));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final host = _SshHost(tester);
    await host.open(scale: 2);
    await host.fill();
    await host.tapConnect();
    final id = host.connectCalls.single['requestId'] as String;
    await host.emit(_changedTicket(id));
    await host.advance();
    final accept = find.byKey(const Key('ssh-changed-host-accept'));
    expect(tester.widget<FilledButton>(accept).onPressed, isNull);
    final scrollable = find.descendant(
      of: find.byKey(const Key('ssh-changed-host-scroll')),
      matching: find.byType(Scrollable),
    );
    SemanticsNode? scrollSemantics;
    void findScrollSemantics(SemanticsNode node) {
      if (node.getSemanticsData().hasAction(SemanticsAction.scrollUp)) {
        scrollSemantics = node;
      } else {
        node.visitChildren((child) {
          findScrollSemantics(child);
          return scrollSemantics == null;
        });
      }
    }

    findScrollSemantics(tester.getSemantics(scrollable.first));
    expect(scrollSemantics, isNotNull);
    for (var step = 0; step < 20; step++) {
      if (tester.widget<FilledButton>(accept).onPressed != null) break;
      scrollSemantics!.owner!.performAction(
        scrollSemantics!.id,
        SemanticsAction.scrollUp,
      );
      await tester.pump(const Duration(milliseconds: 300));
    }
    expect(tester.widget<FilledButton>(accept).onPressed, isNotNull);
    await tester.tap(accept);
    await host.advance();
    expect(host.decisions.single['accept'], isTrue);
    host.completeConnect(error: PlatformException(code: 'SSH_HOSTKEY_DENIED'));
    await host.advance();
    await host.remove();
    semantics.dispose();
  });

  testWidgets('changed-host review supports 128 rows and large text', (
    tester,
  ) async {
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    tester.view.devicePixelRatio = 1;
    for (final size in [const Size(320, 568), const Size(568, 320)]) {
      tester.view.physicalSize = size;
      await tester.binding.setSurfaceSize(size);
      final host = _SshHost(tester);
      await host.open(scale: 2);
      await host.fill();
      await host.tapConnect();
      final id = host.connectCalls.single['requestId'] as String;
      await host.emit({
        ..._changedTicket(id),
        'oldKeys': List.generate(
          128,
          (index) => {
            'keyType': 'ssh-ed25519',
            'fingerprint': index.isEven
                ? _changedOldFingerprint
                : _changedOtherFingerprint,
          },
        ),
      });
      await host.advance();
      expect(find.text('已保存的指纹（128 条）'), findsOneWidget);
      await tester.ensureVisible(
        find.byKey(const Key('ssh-changed-host-old-fingerprint-127')),
      );
      expect(
        find
            .byKey(const Key('ssh-changed-host-old-fingerprint-127'))
            .hitTestable(),
        findsOneWidget,
      );
      await tester.ensureVisible(
        find.byKey(const Key('ssh-changed-host-accept')),
      );
      expect(
        find.byKey(const Key('ssh-changed-host-accept')).hitTestable(),
        findsOneWidget,
      );
      await tester.tap(find.byKey(const Key('ssh-changed-host-reject')));
      await host.advance();
      expect(host.decisions.single['accept'], isFalse);
      host.completeConnect(
        error: PlatformException(code: 'SSH_HOSTKEY_DENIED'),
      );
      await host.advance();
      expect(tester.takeException(), isNull);
      await host.remove();
    }
    await tester.binding.setSurfaceSize(null);
  });

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
      'binding': 'a' * 64,
      'hop': 0,
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
    'host proof and callback cancellation never offer changed-key recovery',
    (tester) async {
      for (final scenario in [
        ('SSH_HOST_PROOF_INVALID', '主机身份验证未能完成，连接已停止。请重新连接并核对服务器信息。'),
        ('SSH_SESSION_CANCELLED', 'SSH 连接已中止，请重新连接。'),
        (
          'SSH_JUMP_HOST_TRUST_REQUIRED',
          '跳板连接暂不支持在连接中核验新主机或已变化的主机指纹，请检查主机信任记录。',
        ),
        ('SSH_JUMP_HOST_TRUST_FAILED', '跳板连接的主机身份未能验证，连接已停止。请检查主机信任配置。'),
      ]) {
        for (final useStateEvent in [false, true]) {
          final host = _SshHost(tester);
          await host.open();
          await host.fill();
          await host.tapConnect();
          final request = host.connectCalls.single['requestId'] as String;
          if (useStateEvent) {
            await host.emit({
              'type': 'state',
              'requestId': request,
              'state': 'failed',
              'reason': scenario.$1,
            });
          }
          host.completeConnect(error: PlatformException(code: scenario.$1));
          await host.advance();
          expect(find.text(scenario.$2), findsOneWidget);
          expect(find.text('服务器主机密钥已变化'), findsNothing);
          expect(
            find.byKey(const Key('ssh-host-changed-forget')),
            findsNothing,
          );
          expect(
            host.calls.where((call) => call.method.startsWith('ssh.hostTrust')),
            isEmpty,
          );
          expect(host.decisions, isEmpty);
          await host.leave();
          await host.remove();
        }
      }
    },
  );

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
      expect(find.text('服务器主机密钥已变化'), findsNothing);
      expect(find.text('信任并保存'), findsNothing);
      expect(host.decisions, isEmpty);
      host.completeConnect(
        error: PlatformException(code: 'SSH_HOSTKEY_CHANGED'),
      );
      await host.advance();
      expect(find.text('服务器主机密钥已变化'), findsOneWidget);
      expect(find.text('server.invalid:2222'), findsOneWidget);
      expect(find.byKey(const Key('ssh-host-changed-view')), findsOneWidget);
      expect(find.byKey(const Key('ssh-host-changed-forget')), findsOneWidget);
      await tester.tap(find.byKey(const Key('ssh-host-changed-cancel')));
      await host.advance();
      expect(
        host.calls.where((call) => call.method.startsWith('ssh.hostTrust')),
        isEmpty,
      );
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
    'changed-host failure remains closed if a connected event and reply arrive later',
    (tester) async {
      final host = _SshHost(tester);
      await host.open();
      await host.fill();
      await host.tapConnect();
      final request = host.connectCalls.single['requestId'] as String;
      await host.emit({
        'type': 'state',
        'requestId': request,
        'state': 'failed',
        'reason': 'SSH_HOSTKEY_CHANGED',
      });
      await host.emit({
        'type': 'state',
        'requestId': request,
        'state': 'connected',
      });
      host.completeConnect();
      await host.advance();
      expect(host.cancelled, contains(request));
      expect(find.text('服务器主机密钥已变化'), findsOneWidget);
      expect(find.text('信任并保存'), findsNothing);
      expect(host.decisions, isEmpty);
      expect(find.byType(SshPasswordConnectPage), findsOneWidget);
      await tester.tap(find.byKey(const Key('ssh-host-changed-cancel')));
      await host.advance();
      await host.leave();
      await host.remove();
    },
  );

  testWidgets('config conflict never infers which trust file to forget', (
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
    await host.tapConnect();
    host.completeConnect(error: PlatformException(code: 'SSH_HOSTKEY_CHANGED'));
    await host.advance();
    await tester.tap(find.byKey(const Key('ssh-host-changed-forget')));
    await host.advance();
    await host.advance();
    expect(find.text('配置可能使用其他信任文件；请核对来源后选择要忘记的记录。'), findsOneWidget);
    expect(
      host.calls.where((call) => call.method == 'ssh.hostTrustForgetPreview'),
      isEmpty,
    );
    expect(
      host.calls.where((call) => call.method == 'ssh.hostTrustForgetCommit'),
      isEmpty,
    );
    host.libraryValues = _reconnectLibrary(configHost: 'other.invalid');
    expect(find.byKey(const Key('ssh-trust-direct-dialog')), findsOneWidget);
    expect(find.text('主机信任'), findsNothing);
    await tester.tap(find.byKey(const Key('ssh-trust-close')));
    await host.advance();
    await host.advance();
    expect(host.identityCalls, hasLength(1));
    expect(find.text('配置目标或私钥已变化，请返回并重新选择。'), findsOneWidget);
    expect(find.byKey(const Key('ssh-connect')), findsNothing);
    await host.leave();
    await host.remove();
  });

  testWidgets(
    'changed host can view the exact saved fingerprints without forgetting',
    (tester) async {
      final host = _SshHost(tester);
      await host.open();
      await host.fill();
      await host.tapConnect();
      host.completeConnect(
        error: PlatformException(code: 'SSH_HOST_KEY_CHANGED'),
      );
      await host.advance();
      await tester.tap(find.byKey(const Key('ssh-host-changed-view')));
      await host.advance();
      await host.advance();
      expect(find.text('主机信任'), findsOneWidget);
      expect(find.text(_trustFingerprint), findsOneWidget);
      final query = host.calls.singleWhere(
        (call) => call.method == 'ssh.hostTrustQuery',
      );
      expect(query.arguments, containsPair('host', 'server.invalid'));
      expect(query.arguments, containsPair('port', 2222));
      expect(
        host.calls.where((call) => call.method == 'ssh.hostTrustForgetPreview'),
        isEmpty,
      );
      await tester.tap(
        find.descendant(
          of: find.byType(SshHostTrustPage),
          matching: find.byTooltip('返回'),
        ),
      );
      await host.advance();
      await host.advance();
      expect(find.byType(SshPasswordConnectPage), findsOneWidget);
      expect(
        tester
            .widget<TextFormField>(find.byKey(const Key('ssh-host')))
            .controller!
            .text,
        'server.invalid',
      );
      expect(host.passwordController.text, isEmpty);
      expect(host.connectCalls, hasLength(1));
      await host.leave();
      await host.remove();
    },
  );

  testWidgets(
    'changed host forget requires fresh preview and explicit confirmation',
    (tester) async {
      final host = _SshHost(tester);
      await host.open();
      await host.fill();
      await host.tapConnect();
      host.completeConnect(
        error: PlatformException(code: 'SSH_HOSTKEY_CHANGED'),
      );
      await host.advance();
      await tester.tap(find.byKey(const Key('ssh-host-changed-forget')));
      await host.advance();
      await host.advance();
      expect(find.text('忘记这些旧指纹？'), findsOneWidget);
      expect(find.byType(SshPasswordConnectPage), findsOneWidget);
      expect(find.byKey(const Key('ssh-host')), findsOneWidget);
      expect(find.text('主机信任'), findsNothing);
      expect(
        find.descendant(
          of: find.byType(AlertDialog),
          matching: find.text(_trustFingerprint),
        ),
        findsOneWidget,
      );
      final preview = host.calls.singleWhere(
        (call) => call.method == 'ssh.hostTrustForgetPreview',
      );
      expect(preview.arguments, containsPair('host', 'server.invalid'));
      expect(preview.arguments, containsPair('port', 2222));
      expect(preview.arguments, containsPair('group', 'password'));
      expect(
        host.calls.where((call) => call.method == 'ssh.hostTrustForgetCommit'),
        isEmpty,
      );
      await tester.tap(find.byKey(const Key('ssh-trust-confirm')));
      await host.advance();
      expect(
        host.calls.where((call) => call.method == 'ssh.hostTrustForgetCommit'),
        hasLength(1),
      );
      expect(find.text('已忘记旧指纹，请重新连接并核对新指纹。'), findsOneWidget);
      await tester.tap(find.byKey(const Key('ssh-trust-acknowledge')));
      await host.advance();
      await host.advance();
      expect(find.byType(SshPasswordConnectPage), findsOneWidget);
      expect(host.connectCalls, hasLength(1));
      expect(host.passwordController.text, isEmpty);
      await host.leave();
      await host.remove();
    },
  );

  testWidgets(
    'backgrounding host recovery cannot restore its old target or credentials',
    (tester) async {
      final host = _SshHost(tester);
      await host.open();
      await host.fill();
      await host.tapConnect();
      host.completeConnect(
        error: PlatformException(code: 'SSH_HOSTKEY_CHANGED'),
      );
      await host.advance();
      await tester.tap(find.byKey(const Key('ssh-host-changed-view')));
      await host.advance();
      await host.advance();
      expect(find.byType(SshHostTrustPage), findsOneWidget);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      await host.advance();
      _resumeApp(tester);
      await host.advance();
      await tester.tap(
        find.descendant(
          of: find.byType(SshHostTrustPage),
          matching: find.byTooltip('返回'),
        ),
      );
      await host.advance();
      await host.advance();
      expect(find.byKey(const Key('ssh-host')), findsNothing);
      expect(find.byKey(const Key('ssh-password')), findsNothing);
      expect(find.byKey(const Key('ssh-reload')), findsOneWidget);
      expect(host.connectCalls, hasLength(1));
      await host.leave();
      await host.remove();
    },
  );

  testWidgets(
    'background invalidates a changed-host dialog and its stale actions',
    (tester) async {
      final host = _SshHost(tester);
      await host.open();
      await host.fill();
      await host.tapConnect();
      host.completeConnect(
        error: PlatformException(code: 'SSH_HOSTKEY_CHANGED'),
      );
      await host.advance();
      final stale = tester
          .widget<FilledButton>(
            find.byKey(const Key('ssh-host-changed-forget')),
          )
          .onPressed!;
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      await host.advance();
      stale();
      await host.advance();
      expect(find.text('服务器主机密钥已变化'), findsNothing);
      expect(
        host.calls.where((call) => call.method.startsWith('ssh.hostTrust')),
        isEmpty,
      );
      _resumeApp(tester);
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

  testWidgets('connection form keeps management after its primary flow', (
    tester,
  ) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(1200, 1400);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPhysicalSize);
    final host = _SshHost(tester);
    await host.open();
    expect(find.byKey(const Key('ssh-manage-identities')), findsNothing);
    expect(find.byKey(const Key('ssh-forget-all-passwords')), findsNothing);
    expect(find.byKey(const Key('ssh-cancel')), findsNothing);
    expect(find.byTooltip('返回'), findsOneWidget);
    expect(
      tester.getTopLeft(find.text('连接目标')).dy,
      lessThan(tester.getTopLeft(find.text('登录认证')).dy),
    );
    expect(
      tester.getTopLeft(find.text('登录认证')).dy,
      lessThan(tester.getTopLeft(find.text('本次连接')).dy),
    );
    final connect = find.byKey(const Key('ssh-connect'));
    final management = find.byKey(const Key('ssh-connection-management'));
    expect(
      tester.getBottomRight(connect).dy,
      lessThan(tester.getTopLeft(management).dy),
    );
    expect(tester.getSize(connect).height, greaterThanOrEqualTo(48));
    await host.openManagement();
    final managementTitle = find.text('连接资料与密码管理');
    final identities = find.byKey(const Key('ssh-manage-identities'));
    expect(
      tester.getTopLeft(identities).dy -
          tester.getBottomRight(managementTitle).dy,
      greaterThanOrEqualTo(16),
    );
    expect(tester.getSize(identities).height, greaterThanOrEqualTo(48));
    final all = find.byKey(const Key('ssh-forget-all-passwords'));
    expect(all.hitTestable(), findsOneWidget);
    expect(
      tester.getSize(all).width,
      lessThan(tester.getSize(find.byKey(const Key('ssh-host'))).width),
    );
    expect(find.byKey(const Key('ssh-forget-password')), findsNothing);
    expect(host.connectCalls, isEmpty);
    expect(host.forgetCalls, isEmpty);
    await host.leave();
    await host.remove();
  });

  testWidgets('target fields adapt without losing the entered credential', (
    tester,
  ) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(1200, 1000);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPhysicalSize);
    final host = _SshHost(tester);
    await host.open();
    await host.fill();
    final hostField = find.byKey(const Key('ssh-host'));
    final portField = find.byKey(const Key('ssh-port'));
    expect(tester.getTopLeft(hostField).dy, tester.getTopLeft(portField).dy);
    tester.view.physicalSize = const Size(320, 700);
    await host.advance();
    expect(
      tester.getTopLeft(portField).dy,
      greaterThan(tester.getBottomRight(hostField).dy),
    );
    expect(
      tester.widget<TextFormField>(hostField).controller!.text,
      'server.invalid',
    );
    expect(tester.widget<TextFormField>(portField).controller!.text, '2222');
    expect(host.passwordController.text, 'synthetic password');
    expect(host.connectCalls, isEmpty);
    expect(tester.takeException(), isNull);
    await host.leave();
    await host.remove();
  });

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
          'ssh-connect',
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
        await host.openManagement();
        for (final key in ['ssh-forget-password', 'ssh-forget-all-passwords']) {
          final control = find.byKey(Key(key));
          await tester.ensureVisible(control);
          await tester.pump(const Duration(milliseconds: 300));
          expect(control.hitTestable(), findsOneWidget);
          expect(tester.getSize(control).height, greaterThanOrEqualTo(48));
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

InkWell _changedHostActionInk(WidgetTester tester, String key) =>
    tester.widget<InkWell>(
      find
          .descendant(of: find.byKey(Key(key)), matching: find.byType(InkWell))
          .first,
    );

void _expectChangedHostActionShape(WidgetTester tester, String key) {
  final button = find.byKey(Key(key));
  final material = tester.widget<Material>(
    find.descendant(of: button, matching: find.byType(Material)).first,
  );
  const radius = BorderRadius.all(Radius.circular(12));
  expect(material.shape, isA<RoundedRectangleBorder>());
  expect((material.shape! as RoundedRectangleBorder).borderRadius, radius);
  final inkBorder = _changedHostActionInk(tester, key).customBorder;
  expect(inkBorder, isA<RoundedRectangleBorder>());
  expect((inkBorder! as RoundedRectangleBorder).borderRadius, radius);
  expect(tester.getSize(button).height, greaterThanOrEqualTo(48));
}

Map<String, Object> _ticket(String id) => {
  'type': 'hostKey',
  'requestId': id,
  'ticketId': 'ticket-a',
  'binding': List.filled(64, 'a').join(),
  'host': 'server.invalid',
  'port': 2222,
  'hop': 0,
  'keyType': 'ssh-ed25519',
  'fingerprint': 'SHA256:${List.filled(43, 'A').join()}',
  'remainingMillis': 30000,
};

final _changedOldFingerprint = 'SHA256:${'B' * 42}A';
final _changedOtherFingerprint = 'SHA256:${'C' * 42}A';
final _changedNewFingerprint = 'SHA256:${'D' * 42}A';

Map<String, Object> _changedTicket(String id) => {
  'type': 'hostKeyChanged',
  'requestId': id,
  'ticketId': 'changed-ticket',
  'binding': 'a' * 64,
  'host': 'server.invalid',
  'port': 2222,
  'hop': 0,
  'source': 'ssh/known_hosts',
  'keyType': 'ssh-ed25519',
  'fingerprint': _changedNewFingerprint,
  'oldKeys': [
    {'keyType': 'ssh-rsa', 'fingerprint': _changedOldFingerprint},
    {'keyType': 'ssh-rsa', 'fingerprint': _changedOldFingerprint},
    {'keyType': 'ecdsa-sha2-nistp256', 'fingerprint': _changedOtherFingerprint},
  ],
  'remainingMillis': 30000,
};

final _trustFingerprint = 'SHA256:${List.filled(43, 'C').join()}';

Map<String, Object> _trustRow() => {
  'line': 1,
  'algorithm': 'ssh-ed25519',
  'fingerprint': _trustFingerprint,
  'marker': 'none',
  'selector': 'literal',
  'reason': 'none',
};

Map<String, Object> _trustQuery() => {
  'host': 'server.invalid',
  'port': 2222,
  'groups': [
    {
      'group': 'password',
      'path': 'ssh/known_hosts',
      'exists': true,
      'records': [_trustRow()],
      'canForget': true,
    },
    {
      'group': 'config',
      'path': '.ssh/known_hosts',
      'exists': false,
      'records': <Object>[],
      'canForget': false,
    },
  ],
};

Map<String, Object> _trustPreview() => {
  'ticketId': 'trust_ticket',
  'binding': List.filled(64, 'd').join(),
  'host': 'server.invalid',
  'port': 2222,
  'group': 'password',
  'path': 'ssh/known_hosts',
  'records': [_trustRow()],
};

Map<String, Object> _reconnectLibrary({
  bool includeKey = true,
  bool encrypted = false,
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
        if (encrypted) 'encrypted': true,
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
  Completer<Map<String, Object>>? pendingDecision;
  Completer<Map<String, Object>>? pendingSecretResponse;
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
      case 'ssh.hostTrustQuery':
        return _trustQuery();
      case 'ssh.hostTrustForgetPreview':
        return _trustPreview();
      case 'ssh.hostTrustForgetCommit':
        return {'forgotten': true};
      case 'ssh.hostTrustForgetCancel':
        return {'cancelled': true};
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
      case 'ssh.autoConnect':
      case 'ssh.configAutoConnect':
      case 'ssh.keyboardConnect':
      case 'ssh.configKeyboardConnect':
        final pending = Completer<Map<String, Object>>();
        connectResults.add(pending);
        return pending.future;
      case 'ssh.cancel':
        return {'cancelled': true};
      case 'ssh.hostKeyDecision':
        return pendingDecision?.future ?? {'accepted': true};
      case 'ssh.passwordResponse':
        return pendingSecretResponse?.future ?? {'accepted': true};
      case 'ssh.keyboardResponse':
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
    bool automatic = false,
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
    messenger.setMockMethodCallHandler(const MethodChannel('ctos/secrets'), (
      call,
    ) async {
      if (call.method == 'listen') {
        scheduleMicrotask(
          () => unawaited(
            messenger.handlePlatformMessage(
              'ctos/secrets',
              const StandardMethodCodec().encodeSuccessEnvelope({
                'epoch': 1,
                'unlocked': true,
                'foreground': true,
              }),
              (_) {},
            ),
          ),
        );
      }
      return null;
    });
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
      messenger.setMockMethodCallHandler(
        const MethodChannel('ctos/secrets'),
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
                      initialAuthentication: automatic
                          ? SshInitialAuthentication.automatic
                          : SshInitialAuthentication.password,
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

  Future<void> openManagement() async {
    final management = find.byKey(const Key('ssh-connection-management'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    await tester.scrollUntilVisible(
      management,
      150,
      scrollable: find
          .descendant(
            of: find.byType(SshPasswordConnectPage),
            matching: find.byType(Scrollable),
          )
          .first,
    );
    await tester.ensureVisible(management);
    await advance();
    if (find.byKey(const Key('ssh-manage-identities')).evaluate().isEmpty) {
      await tester.tap(find.text('连接资料与密码管理'));
      await advance();
    }
  }

  Future<void> leave() async {
    final action = find.byKey(const Key('ssh-cancel'));
    if (action.evaluate().isEmpty) {
      await tester.tap(find.byTooltip('返回').last);
      await advance();
      return;
    }
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

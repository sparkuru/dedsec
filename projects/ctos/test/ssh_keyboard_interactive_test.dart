import 'dart:async';

import 'package:ctos/ssh/api.dart';
import 'package:ctos/ssh/keyboard_challenge_form.dart';
import 'package:ctos/ssh/password_connect_page.dart';
import 'package:ctos/ui/ctos_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('completed cleanup cannot hide a newly retained KBI outcome', () async {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    final ids = <String>[];
    messenger.setMockMethodCallHandler(const MethodChannel('ctos/native'), (
      call,
    ) async {
      expect(call.method, 'ssh.cancel');
      ids.add((call.arguments as Map)['requestId'] as String);
      return {'cancelled': true};
    });
    addTearDown(
      () => messenger.setMockMethodCallHandler(
        const MethodChannel('ctos/native'),
        null,
      ),
    );
    final lifetime = SshConnectLifetime(SshPasswordApi());
    await lifetime.finish();
    final result = Completer<void>();
    lifetime.retain('later_attempt', result.future);
    var retired = false;
    final finishing = lifetime.finish().then((_) => retired = true);
    await Future<void>.delayed(Duration.zero);
    expect(retired, isFalse);
    expect(ids, ['later_attempt']);
    result.complete();
    await finishing;
    expect(retired, isTrue);
    expect(ids, ['later_attempt', 'later_attempt']);
  });

  test('cleanup cancels a newly retained request before awaiting it', () async {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    final ids = <String>[];
    final enteredFirstCancel = Completer<void>();
    final releaseFirstCancel = Completer<void>();
    final firstResult = Completer<void>();
    final secondResult = Completer<void>();
    messenger.setMockMethodCallHandler(const MethodChannel('ctos/native'), (
      call,
    ) async {
      expect(call.method, 'ssh.cancel');
      final id = (call.arguments as Map)['requestId'] as String;
      ids.add(id);
      if (id == 'attempt_a' && !enteredFirstCancel.isCompleted) {
        enteredFirstCancel.complete();
        await releaseFirstCancel.future;
        firstResult.complete();
      } else if (id == 'attempt_b' && !secondResult.isCompleted) {
        secondResult.complete();
      }
      return {'cancelled': true};
    });
    addTearDown(
      () => messenger.setMockMethodCallHandler(
        const MethodChannel('ctos/native'),
        null,
      ),
    );
    final lifetime = SshConnectLifetime(SshPasswordApi());
    lifetime.retain('attempt_a', firstResult.future);
    final finishing = lifetime.finish();
    await enteredFirstCancel.future;
    lifetime.retain('attempt_b', secondResult.future);
    releaseFirstCancel.complete();
    await finishing.timeout(const Duration(seconds: 2));
    expect(ids, ['attempt_a', 'attempt_a', 'attempt_b', 'attempt_b']);
    await lifetime.finish();
    expect(ids, hasLength(4));
  });

  test(
    'KBI challenge decoder bounds complete actual metadata and strict Unicode',
    () {
      final parsed =
          SshEvent.parse(_challenge('attempt')) as SshKeyboardChallenge;
      expect(parsed.prompts.map((prompt) => prompt.echo), [true, false]);
      expect(parsed.instruction, '请输入账号\n然后回答服务器问题。');
      for (final bad in <Map<String, Object>>[
        {..._challenge('attempt'), 'extra': 'raw'},
        {..._challenge('attempt'), 'round': 0},
        {..._challenge('attempt'), 'round': 17},
        {..._challenge('attempt'), 'remainingMillis': 60001},
        {..._challenge('attempt'), 'binding': 'unbound'},
        {..._challenge('attempt'), 'name': String.fromCharCode(0xd800)},
        {..._challenge('attempt'), 'instruction': '文' * 1366},
        {
          ..._challenge('attempt'),
          'prompts': [
            {'text': '', 'echo': false},
          ],
        },
        {
          ..._challenge('attempt'),
          'prompts': [
            {'text': '问题', 'echo': 1},
          ],
        },
        {
          ..._challenge('attempt'),
          'prompts': List.filled(17, {'text': '问题', 'echo': false}),
        },
        {
          ..._challenge('attempt'),
          'prompts': List.filled(8, {'text': 'a' * 4096, 'echo': false}),
        },
      ]) {
        expect(() => SshEvent.parse(bad), throwsFormatException);
      }
      expect(
        SshEvent.parse(_challenge('attempt', prompts: [])),
        isA<SshKeyboardChallenge>(),
      );
      expect(sshKeyboardAnswerError(''), isNull);
      expect(sshKeyboardAnswerError('a\u0000b'), isNull);
      expect(sshKeyboardAnswerError(String.fromCharCode(0xdc00)), isNotNull);
      expect(
        sshKeyboardDisplayText('名字\x1b\u202e\u0000\n说明\t文字'),
        '名字\n说明\t文字',
      );
    },
  );

  test(
    'KBI response encodes exact round ticket and permits empty answers only within bounds',
    () async {
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(const MethodChannel('ctos/native'), (
        call,
      ) async {
        calls.add(
          MethodCall(
            call.method,
            Map<String, Object?>.from(call.arguments as Map),
          ),
        );
        return {'accepted': true};
      });
      addTearDown(
        () => messenger.setMockMethodCallHandler(
          const MethodChannel('ctos/native'),
          null,
        ),
      );
      final api = SshPasswordApi();
      final ticket =
          SshEvent.parse(_challenge('attempt')) as SshKeyboardChallenge;
      expect(await api.keyboardResponse(ticket, ['', '临时回答']), isTrue);
      expect(calls.single.arguments, {
        'requestId': 'attempt',
        'ticketId': 'ticket_1',
        'binding': 'b' * 64,
        'answers': ['', '临时回答'],
      });
      for (final bad in [
        <String>[],
        ['one'],
        ['a' * 4097, ''],
        [String.fromCharCode(0xd800), ''],
      ]) {
        expect(() => api.keyboardResponse(ticket, bad), throwsFormatException);
      }
      final many =
          SshEvent.parse(
                _challenge(
                  'attempt',
                  prompts: List.generate(
                    8,
                    (_) => {'text': '问题', 'echo': false},
                  ),
                ),
              )
              as SshKeyboardChallenge;
      expect(
        () => api.keyboardResponse(many, List.filled(8, 'a' * 4096)),
        throwsFormatException,
      );
      expect(calls, hasLength(1));
    },
  );

  for (final configured in [false, true]) {
    test(
      'RAM ${configured ? 'config' : 'manual'} KBI reconnect has no key or previous authority',
      () {
        final value = {
          'mode': configured ? 'configKeyboard' : 'keyboard',
          'host': 'server.invalid',
          'port': 2222,
          'username': 'alice',
          if (configured) 'configAlias': 'fixture',
        };
        final target = SshReconnectTarget.parse(value);
        expect(target.keyId, isNull);
        expect(target.configAlias, configured ? 'fixture' : null);
        expect(
          () => SshReconnectTarget.parse({...value, 'keyId': 'key_a'}),
          throwsFormatException,
        );
        expect(
          () => SshReconnectTarget.parse({...value, 'binding': 'b' * 64}),
          throwsFormatException,
        );
        expect(
          () => SshReconnectTarget.parse({
            ...value,
            'answers': ['old'],
          }),
          throwsFormatException,
        );
      },
    );
  }

  testWidgets(
    'ordinary password success does not wait for a KBI session event',
    (tester) async {
      final ui = _Ui(tester);
      await ui.open();
      await tester.enterText(
        find.byKey(const Key('ssh-password')),
        'first-factor',
      );
      await ui.tap('ssh-connect');
      expect(
        ui.connections.single.arguments,
        containsPair('autoKeyboardInteractive', true),
      );
      ui.complete(success: true);
      await ui.pump();
      await tester.pump(const Duration(milliseconds: 400));
      expect(find.byType(SshPasswordConnectPage), findsNothing);
      expect(ui.answers, isEmpty);
      await ui.remove();
    },
  );

  testWidgets(
    'pending automatic challenge expires or is revoked before display',
    (tester) async {
      for (final expires in [true, false]) {
        final ui = _Ui(tester);
        await ui.open();
        await tester.enterText(
          find.byKey(const Key('ssh-password')),
          'first-factor',
        );
        await ui.tap('ssh-connect');
        final id = ui.connectId;
        await ui.event({
          ..._challenge(id),
          if (expires) 'remainingMillis': 500,
        });
        expect(find.byType(SshKeyboardChallengeForm), findsNothing);
        if (expires) {
          await tester.pump(const Duration(milliseconds: 500));
        } else {
          await ui.session(unlocked: false);
        }
        await ui.pump();
        await ui.session();
        expect(find.byType(SshKeyboardChallengeForm), findsNothing);
        expect(ui.answers, isEmpty);
        expect(ui.cancelled, contains(id));
        ui.complete();
        await ui.pump();
        await ui.remove();
      }
    },
  );

  testWidgets(
    'success racing an unanswered automatic challenge cancels exact handoff',
    (tester) async {
      for (final sessionReady in [false, true]) {
        final ui = _Ui(tester);
        await ui.open();
        await tester.enterText(
          find.byKey(const Key('ssh-password')),
          'first-factor',
        );
        await ui.tap('ssh-connect');
        final id = ui.connectId;
        await ui.event(_challenge(id));
        if (sessionReady) await ui.session();
        ui.complete(success: true);
        await ui.pump();
        expect(ui.cancelled, contains(id));
        expect(find.byType(SshPasswordConnectPage), findsOneWidget);
        expect(ui.answers, isEmpty);
        await ui.remove();
      }
    },
  );

  for (final order in ['dismiss-first', 'success-first', 'accepted-first']) {
    testWidgets('submitted challenge tolerates $order receipt ordering', (
      tester,
    ) async {
      final ui = _Ui(tester)
        ..keyboardResponse = Completer<Map<String, Object>>();
      await ui.open();
      await tester.enterText(find.byKey(const Key('ssh-password')), 'first');
      await ui.tap('ssh-connect');
      final id = ui.connectId;
      expect(find.byKey(const Key('ssh-keyboard-dialog')), findsNothing);
      await ui.event(_challenge(id));
      await ui.session();
      expect(find.byKey(const Key('ssh-keyboard-dialog')), findsOneWidget);
      await ui.tap('ssh-keyboard-submit');
      if (order == 'accepted-first') {
        ui.keyboardResponse!.complete({'accepted': true});
        await ui.pump();
      }
      if (order == 'dismiss-first') {
        await ui.event({
          'type': 'keyboardChallengeDismissed',
          'requestId': id,
          'ticketId': 'ticket_1',
        });
      }
      ui.complete(success: true);
      await ui.pump();
      if (order != 'accepted-first') {
        expect(find.byType(SshPasswordConnectPage), findsOneWidget);
        expect(ui.cancelled, isNot(contains(id)));
        ui.keyboardResponse!.complete({'accepted': true});
        await ui.pump();
      }
      await ui.pump();
      expect(find.byType(SshPasswordConnectPage), findsNothing);
      expect(ui.cancelled, isNot(contains(id)));
      await ui.remove();
    });
  }

  testWidgets('dismissed unanswered challenge cannot hand off a session', (
    tester,
  ) async {
    final ui = _Ui(tester);
    await ui.open();
    await tester.enterText(find.byKey(const Key('ssh-password')), 'first');
    await ui.tap('ssh-connect');
    final id = ui.connectId;
    await ui.event(_challenge(id));
    await ui.session();
    await ui.event({
      'type': 'keyboardChallengeDismissed',
      'requestId': id,
      'ticketId': 'ticket_1',
    });
    ui.complete(success: true);
    await ui.pump();
    expect(ui.cancelled, contains(id));
    expect(find.byType(SshPasswordConnectPage), findsOneWidget);
    expect(ui.answers, isEmpty);
    await ui.remove();
  });

  testWidgets(
    'challenge dismissed before session readiness still requires an answer',
    (tester) async {
      final ui = _Ui(tester);
      await ui.open();
      await tester.enterText(find.byKey(const Key('ssh-password')), 'first');
      await ui.tap('ssh-connect');
      final id = ui.connectId;
      await ui.event(_challenge(id));
      await ui.event({
        'type': 'keyboardChallengeDismissed',
        'requestId': id,
        'ticketId': 'ticket_1',
      });
      ui.complete(success: true);
      await ui.pump();
      expect(ui.cancelled, contains(id));
      expect(find.byType(SshPasswordConnectPage), findsOneWidget);
      expect(ui.answers, isEmpty);
      await ui.remove();
    },
  );

  testWidgets('accepted answer no longer expires while server finishes', (
    tester,
  ) async {
    final ui = _Ui(tester);
    await ui.open();
    await tester.enterText(find.byKey(const Key('ssh-password')), 'first');
    await ui.tap('ssh-connect');
    final id = ui.connectId;
    await ui.event(_challenge(id, remainingMillis: 3000));
    await ui.session();
    await ui.tap('ssh-keyboard-submit');
    await tester.pump(const Duration(seconds: 4));
    expect(ui.cancelled, isNot(contains(id)));
    expect(find.byKey(const Key('ssh-keyboard-dialog')), findsOneWidget);
    ui.complete(success: true);
    await ui.pump();
    await ui.pump();
    expect(find.byType(SshPasswordConnectPage), findsNothing);
    await ui.remove();
  });

  testWidgets('revocation while answer receipt is pending cannot hand off', (
    tester,
  ) async {
    final ui = _Ui(tester)..keyboardResponse = Completer<Map<String, Object>>();
    await ui.open();
    await tester.enterText(find.byKey(const Key('ssh-password')), 'first');
    await ui.tap('ssh-connect');
    final id = ui.connectId;
    await ui.event(_challenge(id));
    await ui.session();
    await ui.tap('ssh-keyboard-submit');
    ui.complete(success: true);
    await ui.session(unlocked: false);
    await ui.pump();
    expect(ui.cancelled, contains(id));
    ui.keyboardResponse!.complete({'accepted': true});
    await ui.pump();
    expect(find.byType(SshPasswordConnectPage), findsOneWidget);
    expect(find.byKey(const Key('ssh-keyboard-dialog')), findsNothing);
    await ui.remove();
  });

  for (final resolution in ['rejected', 'timeout']) {
    testWidgets('$resolution answer receipt cannot hand off the session', (
      tester,
    ) async {
      final ui = _Ui(tester)
        ..keyboardResponse = Completer<Map<String, Object>>();
      await ui.open();
      await tester.enterText(find.byKey(const Key('ssh-password')), 'first');
      await ui.tap('ssh-connect');
      final id = ui.connectId;
      await ui.event(_challenge(id, remainingMillis: 3000));
      await ui.session();
      await ui.tap('ssh-keyboard-submit');
      ui.complete(success: true);
      if (resolution == 'rejected') {
        ui.keyboardResponse!.complete({'accepted': false});
      } else {
        await tester.pump(const Duration(seconds: 4));
      }
      await ui.pump();
      expect(ui.cancelled, contains(id));
      if (resolution == 'timeout') {
        ui.keyboardResponse!.complete({'accepted': true});
        await ui.pump();
      }
      expect(find.byType(SshPasswordConnectPage), findsOneWidget);
      await ui.remove();
    });
  }

  testWidgets(
    'later question retains its own deadline after an accepted answer',
    (tester) async {
      final ui = _Ui(tester);
      await ui.open();
      await tester.enterText(find.byKey(const Key('ssh-password')), 'first');
      await ui.tap('ssh-connect');
      final id = ui.connectId;
      await ui.event(_challenge(id));
      await ui.session();
      await ui.tap('ssh-keyboard-submit');
      await ui.event({
        'type': 'keyboardChallengeDismissed',
        'requestId': id,
        'ticketId': 'ticket_1',
      });
      await ui.event(_challenge(id, round: 2, remainingMillis: 500));
      await tester.pump(const Duration(milliseconds: 501));
      await ui.pump();
      expect(ui.cancelled, contains(id));
      expect(ui.answers, hasLength(1));
      ui.complete();
      await ui.pump();
      await ui.remove();
    },
  );

  testWidgets(
    'challenge dialog cancellation retains protection until receipt',
    (tester) async {
      final ui = _Ui(tester);
      await ui.open();
      await tester.enterText(find.byKey(const Key('ssh-password')), 'first');
      await ui.tap('ssh-connect');
      final id = ui.connectId;
      await ui.event(_challenge(id));
      await ui.session();
      await ui.tap('ssh-keyboard-cancel');
      expect(ui.cancelled, contains(id));
      expect(ui.releases, isEmpty);
      expect(ui.answers, isEmpty);
      ui.complete();
      await ui.pump();
      expect(ui.releases, hasLength(1));
      await ui.remove();
    },
  );

  testWidgets(
    'challenge dialog actions and new rounds remain reachable at large text sizes',
    (tester) async {
      addTearDown(() => tester.binding.setSurfaceSize(null));
      for (final size in [const Size(320, 568), const Size(568, 320)]) {
        await tester.binding.setSurfaceSize(size);
        final ui = _Ui(tester);
        await ui.open(scale: 2, keyboard: 80);
        await tester.enterText(find.byKey(const Key('ssh-password')), 'first');
        await ui.tap('ssh-connect');
        final id = ui.connectId;
        await ui.event(_challenge(id));
        await ui.session();
        final dialog = find.byKey(const Key('ssh-keyboard-dialog'));
        expect(dialog, findsOneWidget);
        expect(
          find.descendant(of: dialog, matching: find.byType(Scrollbar)),
          findsOneWidget,
        );
        for (final action in ['ssh-keyboard-submit', 'ssh-keyboard-cancel']) {
          final button = find.byKey(Key(action));
          await tester.ensureVisible(button);
          await ui.pump();
          expect(button.hitTestable(), findsOneWidget);
        }
        await ui.tap('ssh-keyboard-submit');
        await ui.event({
          'type': 'keyboardChallengeDismissed',
          'requestId': id,
          'ticketId': 'ticket_1',
        });
        await ui.event(_challenge(id, round: 2));
        expect(
          tester.getTopLeft(find.text('服务器请求')).dy,
          greaterThanOrEqualTo(0),
        );
        expect(tester.takeException(), isNull);
        ui.complete();
        await ui.pump();
        await ui.remove();
      }
    },
  );

  test(
    'MFA reconnect metadata grants only the explicit bounded continuation',
    () {
      for (final mode in ['password', 'key', 'config', 'configPassword']) {
        final value = <String, Object>{
          'mode': mode,
          'host': 'server.invalid',
          'port': 2222,
          'username': 'alice',
          if (mode == 'key' || mode == 'config') 'keyId': 'key_a',
          if (mode == 'config' || mode == 'configPassword')
            'configAlias': 'fixture',
        };
        expect(
          SshReconnectTarget.parse(value).continueKeyboardInteractive,
          isFalse,
        );
        expect(
          SshReconnectTarget.parse(value).autoKeyboardInteractive,
          isFalse,
        );
        expect(
          SshReconnectTarget.parse({
            ...value,
            'autoKeyboardInteractive': true,
          }).autoKeyboardInteractive,
          isTrue,
        );
        expect(
          SshReconnectTarget.parse({
            ...value,
            'continueKeyboardInteractive': true,
          }).continueKeyboardInteractive,
          isTrue,
        );
        for (final bad in <Object?>['true', 1, null]) {
          expect(
            () => SshReconnectTarget.parse({
              ...value,
              'continueKeyboardInteractive': bad,
            }),
            throwsFormatException,
          );
          expect(
            () => SshReconnectTarget.parse({
              ...value,
              'autoKeyboardInteractive': bad,
            }),
            throwsFormatException,
          );
        }
        expect(
          () => SshReconnectTarget.parse({
            ...value,
            'continueKeyboardInteractive': false,
            'autoKeyboardInteractive': true,
          }),
          throwsFormatException,
        );
        expect(
          () => SshReconnectTarget.parse({...value, 'stage': 2}),
          throwsFormatException,
        );
        if (mode == 'configPassword') {
          expect(
            () => SshReconnectTarget.parse({...value, 'keyId': 'key_a'}),
            throwsFormatException,
          );
        }
      }
      for (final mode in ['keyboard', 'configKeyboard']) {
        for (final field in [
          'continueKeyboardInteractive',
          'autoKeyboardInteractive',
        ]) {
          expect(
            () => SshReconnectTarget.parse({
              'mode': mode,
              'host': 'server.invalid',
              'port': 2222,
              'username': 'alice',
              if (mode == 'configKeyboard') 'configAlias': 'fixture',
              field: false,
            }),
            throwsFormatException,
          );
        }
      }
    },
  );

  for (final configured in [false, true]) {
    for (final key in [false, true]) {
      testWidgets(
        '${configured ? 'config' : 'manual'} ${key ? 'key' : 'password'} presents requested KBI on original protected attempt',
        (tester) async {
          final ui = _Ui(tester)..withKey = key;
          await ui.open(
            connectOnOpen: false,
            reconnect: SshReconnectTarget(
              mode: configured
                  ? (key
                        ? SshReconnectMode.config
                        : SshReconnectMode.configPassword)
                  : (key ? SshReconnectMode.key : SshReconnectMode.password),
              host: 'server.invalid',
              port: 2222,
              username: 'alice',
              keyId: key ? 'key_a' : null,
              configAlias: configured ? 'fixture' : null,
            ),
          );
          expect(
            find.byKey(const Key('ssh-continue-keyboard-interactive')),
            findsNothing,
          );
          if (!key)
            await tester.enterText(
              find.byKey(const Key('ssh-password')),
              'first-method-only',
            );
          await ui.tap('ssh-connect');
          final call = ui.connections.single;
          expect(
            call.method,
            configured
                ? (key ? 'ssh.configConnect' : 'ssh.configPasswordConnect')
                : (key ? 'ssh.keyConnect' : 'ssh.passwordConnect'),
          );
          expect(call.arguments, containsPair('autoKeyboardInteractive', true));
          expect(
            call.arguments,
            isNot(contains('continueKeyboardInteractive')),
          );
          if (configured) {
            expect(call.arguments, containsPair('alias', 'fixture'));
            expect(call.arguments, containsPair('binding', 'a' * 64));
            expect(call.arguments, isNot(contains('host')));
            expect(call.arguments, isNot(contains('port')));
            expect(call.arguments, containsPair('username', ''));
          }
          await ui.event(_challenge(ui.connectId));
          expect(find.byType(SshKeyboardChallengeForm), findsNothing);
          await ui.session();
          expect(find.byType(SshKeyboardChallengeForm), findsOneWidget);
          expect(find.byKey(const Key('ssh-password')), findsNothing);
          await tester.enterText(
            find.byKey(const Key('ssh-keyboard-answer-1')),
            'only-current-round',
          );
          await ui.tap('ssh-keyboard-submit');
          expect(ui.answers.single['answers'], ['', 'only-current-round']);
          expect(ui.connections, hasLength(1));
          ui.complete();
          await ui.pump();
          await ui.close();
          await ui.remove();
        },
      );
    }
  }

  testWidgets(
    'config password selection and method changes retain actual config authority',
    (tester) async {
      final ui = _Ui(tester);
      await ui.open();
      await ui.tap('ssh-manage-identities');
      await ui.tap('ssh-config-host-fixture');
      await ui.pump();
      expect(
        find.byKey(const Key('ssh-host')),
        findsOneWidget,
        reason: tester
            .widgetList<Text>(find.byType(Text))
            .map((text) => text.data)
            .join(' | '),
      );
      expect(
        tester
            .widget<TextField>(
              find.descendant(
                of: find.byKey(const Key('ssh-host')),
                matching: find.byType(TextField),
              ),
            )
            .readOnly,
        isTrue,
      );
      expect(
        ui.arguments('ssh.passwordStatus').last,
        containsPair('host', 'server.invalid'),
      );
      await ui.tap('ssh-authentication');
      await ui.tap('ssh-auth-keyboard');
      await ui.session();
      expect(
        find.byKey(const Key('ssh-continue-keyboard-interactive')),
        findsNothing,
      );
      await ui.tap('ssh-authentication');
      await ui.tap('ssh-auth-password');
      expect(
        find.byKey(const Key('ssh-continue-keyboard-interactive')),
        findsNothing,
      );
      expect(
        tester
            .widget<TextField>(
              find.descendant(
                of: find.byKey(const Key('ssh-host')),
                matching: find.byType(TextField),
              ),
            )
            .readOnly,
        isTrue,
      );
      await tester.enterText(
        find.byKey(const Key('ssh-password')),
        'only-first',
      );
      await ui.tap('ssh-connect');
      expect(ui.connections.single.method, 'ssh.configPasswordConnect');
      expect(
        ui.connections.single.arguments,
        containsPair('binding', 'a' * 64),
      );
      expect(
        ui.connections.single.arguments,
        isNot(contains('continueKeyboardInteractive')),
      );
      expect(
        ui.connections.single.arguments,
        containsPair('autoKeyboardInteractive', true),
      );
      ui.complete();
      await ui.pump();
      await ui.close();
      await ui.remove();
    },
  );

  testWidgets(
    'changing config password to an explicit key preserves the alias binding',
    (tester) async {
      final ui = _Ui(tester)
        ..withKey = true
        ..libraryBinding = 'd' * 64;
      await ui.open(
        connectOnOpen: false,
        reconnect: const SshReconnectTarget(
          mode: SshReconnectMode.configPassword,
          host: 'server.invalid',
          port: 2222,
          username: 'alice',
          configAlias: 'fixture',
        ),
      );
      await ui.tap('ssh-authentication');
      await ui.tap('ssh-auth-privateKey');
      await ui.tap('ssh-select-private-key');
      await ui.tap('ssh-key-key_a');
      await ui.pump();
      expect(
        tester
            .widget<TextFormField>(find.byKey(const Key('ssh-host')))
            .controller!
            .text,
        'server.invalid',
      );
      await ui.tap('ssh-connect');
      expect(ui.connections.single.method, 'ssh.configConnect');
      expect(
        ui.connections.single.arguments,
        containsPair('binding', 'd' * 64),
      );
      expect(ui.connections.single.arguments, containsPair('alias', 'fixture'));
      expect(ui.connections.single.arguments, containsPair('keyId', 'key_a'));
      ui.complete();
      await ui.pump();
      await ui.close();
      await ui.remove();
    },
  );

  testWidgets(
    'same-config key refresh preserves an explicit username when config omits User',
    (tester) async {
      final ui = _Ui(tester)
        ..withKey = true
        ..libraryUsername = ''
        ..libraryBinding = 'e' * 64;
      await ui.open(
        connectOnOpen: false,
        reconnect: const SshReconnectTarget(
          mode: SshReconnectMode.configPassword,
          host: 'server.invalid',
          port: 2222,
          username: 'explicit-user',
          configAlias: 'fixture',
        ),
      );
      await ui.tap('ssh-authentication');
      await ui.tap('ssh-auth-privateKey');
      await ui.tap('ssh-select-private-key');
      await ui.tap('ssh-key-key_a');
      await ui.pump();
      expect(
        tester
            .widget<TextFormField>(find.byKey(const Key('ssh-username')))
            .controller!
            .text,
        'explicit-user',
      );
      await ui.tap('ssh-connect');
      expect(ui.connections.single.method, 'ssh.configConnect');
      expect(
        ui.connections.single.arguments,
        containsPair('binding', 'e' * 64),
      );
      expect(
        ui.connections.single.arguments,
        containsPair('username', 'explicit-user'),
      );
      ui.complete();
      await ui.pump();
      await ui.close();
      await ui.remove();
    },
  );

  testWidgets(
    'config password reconnect resolves current binding and never falls back after target drift',
    (tester) async {
      final ui = _Ui(tester)..libraryBinding = 'c' * 64;
      await ui.open(
        connectOnOpen: false,
        reconnect: const SshReconnectTarget(
          mode: SshReconnectMode.configPassword,
          host: 'server.invalid',
          port: 2222,
          username: 'alice',
          configAlias: 'fixture',
          continueKeyboardInteractive: true,
        ),
      );
      await ui.session();
      expect(ui.connections, isEmpty);
      expect(
        tester
            .widget<TextFormField>(find.byKey(const Key('ssh-password')))
            .controller!
            .text,
        isEmpty,
      );
      await tester.enterText(
        find.byKey(const Key('ssh-password')),
        'fresh-first',
      );
      await ui.tap('ssh-connect');
      expect(
        ui.connections.single.arguments,
        containsPair('binding', 'c' * 64),
      );
      ui.complete();
      await ui.pump();
      await ui.close();
      await ui.remove();

      final changed = _Ui(tester)..libraryHost = 'changed.invalid';
      await changed.open(
        connectOnOpen: false,
        reconnect: const SshReconnectTarget(
          mode: SshReconnectMode.configPassword,
          host: 'server.invalid',
          port: 2222,
          username: 'alice',
          configAlias: 'fixture',
          continueKeyboardInteractive: true,
        ),
      );
      await changed.session();
      expect(find.byKey(const Key('ssh-connect')), findsNothing);
      expect(find.text('上次的配置目标已变化，请在“私钥与配置”中重新选择。'), findsOneWidget);
      expect(changed.connections, isEmpty);
      await changed.close();
      await changed.remove();
    },
  );

  for (final removal in ['removeRoute', 'destroyNavigator']) {
    for (final saving in [false, true]) {
      testWidgets(
        '${saving ? 'save' : 'reuse'} password MFA $removal awaits RPC after late connected event',
        (tester) async {
          final ui = _Ui(tester);
          await ui.open();
          if (saving) {
            await tester.enterText(
              find.byKey(const Key('ssh-password')),
              'first-save-only',
            );
            await ui.tap('ssh-save-password');
          } else {
            await ui.tap('ssh-use-saved-password');
          }
          await ui.tap('ssh-connect');
          expect(
            ui.connections.single.arguments,
            containsPair('useSavedPassword', !saving),
          );
          expect(
            ui.connections.single.arguments,
            containsPair('password', saving ? 'first-save-only' : ''),
          );
          await ui.event(_challenge(ui.connectId));
          await ui.session();
          await ui.event({
            'type': 'state',
            'requestId': ui.connectId,
            'state': 'connected',
          });
          expect(find.byType(SshPasswordConnectPage), findsOneWidget);
          if (removal == 'removeRoute') {
            final context = tester.element(find.byType(SshPasswordConnectPage));
            Navigator.of(context).removeRoute(ModalRoute.of(context)!);
          } else {
            await tester.pumpWidget(const SizedBox.shrink());
          }
          await ui.pump();
          expect(ui.releases, isEmpty);
          expect(ui.cancelled, contains(ui.connectId));
          ui.complete(success: true);
          await ui.pump();
          expect(ui.releases, hasLength(1));
          expect(
            ui.cancelled.where((id) => id == ui.connectId).length,
            greaterThanOrEqualTo(2),
          );
          expect(ui.answers, isEmpty);
          await ui.remove();
        },
      );
    }
  }

  for (final cause in [
    'background',
    'locked',
    'stream',
    'expired',
    'oldRound',
  ]) {
    testWidgets(
      '$cause retires password-to-KBI without late response or protection release',
      (tester) async {
        final ui = _Ui(tester);
        await ui.open();
        await ui.tap('ssh-use-saved-password');
        await ui.tap('ssh-connect');
        final id = ui.connectId;
        await ui.event(
          _challenge(id, remainingMillis: cause == 'expired' ? 500 : 60000),
        );
        await ui.session();
        await tester.enterText(
          find.byKey(const Key('ssh-keyboard-answer-1')),
          'clear-on-retire',
        );
        switch (cause) {
          case 'background':
            tester.binding.handleAppLifecycleStateChanged(
              AppLifecycleState.inactive,
            );
          case 'locked':
            await ui.session(unlocked: false);
          case 'stream':
            await ui.messenger.handlePlatformMessage(
              'ctos/ssh',
              const StandardMethodCodec().encodeErrorEnvelope(code: 'FAILED'),
              null,
            );
          case 'expired':
            await tester.pump(const Duration(milliseconds: 501));
          case 'oldRound':
            await ui.event({
              'type': 'keyboardChallengeDismissed',
              'requestId': id,
              'ticketId': 'ticket_1',
            });
            await ui.event(_challenge(id));
        }
        await ui.pump();
        expect(find.byType(SshKeyboardChallengeForm), findsNothing);
        expect(ui.cancelled, contains(id));
        expect(ui.releases, isEmpty);
        if (cause == 'background')
          tester.binding.handleAppLifecycleStateChanged(
            AppLifecycleState.resumed,
          );
        await ui.event(_challenge(id, round: 2));
        expect(find.byType(SshKeyboardChallengeForm), findsNothing);
        expect(ui.answers, isEmpty);
        ui.complete();
        await ui.pump();
        await ui.close();
        await ui.remove();
      },
    );
  }

  test(
    'config password API validates source binding and first-method secret modes',
    () async {
      expect(
        sshConnectionError(PlatformException(code: 'SSH_PASSWORD_POLICY')),
        '此配置未允许密码认证，请检查配置中的认证方式。',
      );
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(const MethodChannel('ctos/native'), (
        call,
      ) async {
        calls.add(call);
        return {
          'requestId': (call.arguments as Map)['requestId'],
          'status': 'connected',
        };
      });
      addTearDown(
        () => messenger.setMockMethodCallHandler(
          const MethodChannel('ctos/native'),
          null,
        ),
      );
      final api = SshPasswordApi();
      await api.configPasswordConnect(
        requestId: 'attempt',
        alias: 'fixture',
        binding: 'b' * 64,
        password: '',
        useSavedPassword: true,
        continueKeyboardInteractive: true,
        columns: 80,
        rows: 24,
      );
      expect(calls.single.arguments, {
        'requestId': 'attempt',
        'alias': 'fixture',
        'binding': 'b' * 64,
        'username': '',
        'password': '',
        'useSavedPassword': true,
        'savePassword': false,
        'continueKeyboardInteractive': true,
        'columns': 80,
        'rows': 24,
      });
      for (final value in [
        ('unbound', '', true, false),
        ('b' * 64, 'unexpected', true, false),
        ('b' * 64, '', true, true),
        ('b' * 64, '', false, false),
      ]) {
        expect(
          () => api.configPasswordConnect(
            requestId: 'bad',
            alias: 'fixture',
            binding: value.$1,
            password: value.$2,
            useSavedPassword: value.$3,
            savePassword: value.$4,
            columns: 80,
            rows: 24,
          ),
          throwsFormatException,
        );
      }
      expect(calls, hasLength(1));
    },
  );

  testWidgets(
    'authentication selector cancels without discarding the draft and places help below',
    (tester) async {
      final ui = _Ui(tester);
      await ui.open();
      if (tester
              .widget<CheckboxListTile>(
                find.byKey(const Key('ssh-use-saved-password')),
              )
              .value ==
          true) {
        await ui.tap('ssh-use-saved-password');
      }
      await tester.enterText(
        find.byKey(const Key('ssh-password')),
        'one-use draft',
      );
      final selector = find.byKey(const Key('ssh-authentication'));
      final help = find.byKey(const Key('ssh-authentication-help'));
      final semantics = tester.getSemantics(selector);
      expect(semantics.label, '认证方式');
      expect(semantics.value, '密码');
      expect(
        tester.getBottomLeft(selector).dy,
        lessThanOrEqualTo(tester.getTopLeft(help).dy),
      );
      expect(find.byType(RadioListTile), findsNothing);
      await ui.tap('ssh-authentication');
      expect(
        find.byKey(const Key('ssh-authentication-dialog')),
        findsOneWidget,
      );
      await ui.tap('ssh-authentication-cancel');
      expect(find.byKey(const Key('ssh-authentication-dialog')), findsNothing);
      expect(
        tester
            .widget<TextFormField>(find.byKey(const Key('ssh-password')))
            .controller!
            .text,
        'one-use draft',
      );
      var selectorFocused = false;
      for (var i = 0; i < 20; i++) {
        await tester.sendKeyEvent(LogicalKeyboardKey.tab);
        await ui.pump();
        final focused = FocusManager.instance.primaryFocus?.context;
        if (focused?.findAncestorWidgetOfExactType<InkWell>()?.key ==
            const Key('ssh-authentication')) {
          selectorFocused = true;
          break;
        }
      }
      expect(selectorFocused, isTrue);
      await tester.sendKeyEvent(LogicalKeyboardKey.enter);
      await ui.pump();
      expect(
        find.byKey(const Key('ssh-authentication-dialog')),
        findsOneWidget,
      );
      await tester.binding.handlePopRoute();
      await ui.pump();
      expect(find.byKey(const Key('ssh-authentication-dialog')), findsNothing);
      expect(
        tester
            .widget<TextFormField>(find.byKey(const Key('ssh-password')))
            .controller!
            .text,
        'one-use draft',
      );
      await ui.tap('ssh-authentication');
      await ui.tap('ssh-auth-privateKey');
      expect(find.byKey(const Key('ssh-password')), findsNothing);
      expect(find.byKey(const Key('ssh-select-private-key')), findsOneWidget);
      expect(find.text('使用秘密库中的私钥，并核验目标主机密钥。'), findsOneWidget);
      await ui.remove();
    },
  );

  for (final cause in ['background', 'covered']) {
    testWidgets('authentication choice cannot apply after $cause', (
      tester,
    ) async {
      final ui = _Ui(tester);
      await ui.open();
      await ui.tap('ssh-authentication');
      final staleTap = tester
          .widget<InkWell>(
            find
                .descendant(
                  of: find.byKey(const Key('ssh-auth-keyboard')),
                  matching: find.byType(InkWell),
                )
                .first,
          )
          .onTap!;
      if (cause == 'background') {
        tester.binding.handleAppLifecycleStateChanged(
          AppLifecycleState.inactive,
        );
      } else {
        final context = tester.element(
          find.byKey(const Key('ssh-authentication-dialog')),
        );
        unawaited(
          Navigator.of(context).push(
            MaterialPageRoute<void>(
              builder: (_) => const Scaffold(body: Text('Covered')),
            ),
          ),
        );
      }
      await ui.pump();
      staleTap();
      await ui.pump();
      expect(find.byKey(const Key('ssh-authentication-dialog')), findsNothing);
      expect(ui.connections, isEmpty);
      expect(ui.arguments('ssh.keyboardResponse'), isEmpty);
      await ui.remove();
    });
  }

  testWidgets(
    'manual KBI starts without password, asks ordered echo fields and sends one complete round',
    (tester) async {
      final ui = _Ui(tester);
      await ui.open();
      await ui.chooseKeyboard();
      expect(find.byKey(const Key('ssh-password')), findsNothing);
      expect(find.byKey(const Key('ssh-save-password')), findsNothing);
      expect(find.byKey(const Key('ssh-select-private-key')), findsNothing);
      await ui.tap('ssh-connect');
      final id = ui.connectId;
      expect(ui.connections.single.method, 'ssh.keyboardConnect');
      expect(ui.connections.single.arguments, {
        'requestId': id,
        'host': 'server.invalid',
        'port': 2222,
        'username': 'alice',
        'columns': 80,
        'rows': 24,
      });
      expect(ui.arguments('ssh.passwordStatus'), isEmpty);
      await ui.event(_challenge('old_attempt'));
      expect(find.byType(SshKeyboardChallengeForm), findsNothing);
      await ui.event(_challenge(id));
      expect(find.text('服务器请求'), findsOneWidget);
      expect(find.text('alice@server.invalid:2222'), findsOneWidget);
      final first = tester.widget<TextField>(
        find.descendant(
          of: find.byKey(const Key('ssh-keyboard-answer-0')),
          matching: find.byType(TextField),
        ),
      );
      final second = tester.widget<TextField>(
        find.descendant(
          of: find.byKey(const Key('ssh-keyboard-answer-1')),
          matching: find.byType(TextField),
        ),
      );
      expect(first.obscureText, isFalse);
      expect(second.obscureText, isTrue);
      expect(second.enableSuggestions, isFalse);
      expect(second.enableIMEPersonalizedLearning, isFalse);
      await tester.enterText(
        find.byKey(const Key('ssh-keyboard-answer-0')),
        'alice',
      );
      await tester.enterText(
        find.byKey(const Key('ssh-keyboard-answer-1')),
        '当次秘密',
      );
      await ui.tap('ssh-keyboard-submit');
      expect(ui.answers.single['answers'], ['alice', '当次秘密']);
      expect(first.controller!.text, isEmpty);
      expect(second.controller!.text, isEmpty);
      await ui.tap('ssh-keyboard-submit');
      expect(ui.answers, hasLength(1));
      await ui.event({
        'type': 'keyboardChallengeDismissed',
        'requestId': id,
        'ticketId': 'ticket_1',
      });
      await ui.event(_challenge(id, round: 2, prompts: []));
      expect(find.text('继续'), findsOneWidget);
      await ui.event({
        'type': 'keyboardChallengeDismissed',
        'requestId': id,
        'ticketId': 'ticket_1',
      });
      expect(find.text('继续'), findsOneWidget);
      await ui.tap('ssh-keyboard-submit');
      expect(ui.answers.last['answers'], isEmpty);
      expect(ui.answers.last['ticketId'], 'ticket_2');
      await ui.event({
        'type': 'keyboardChallengeDismissed',
        'requestId': id,
        'ticketId': 'ticket_2',
      });
      ui.complete(success: true);
      await ui.pump();
      await tester.pump(const Duration(milliseconds: 400));
      await ui.pump();
      expect(
        find.byType(SshPasswordConnectPage),
        findsNothing,
        reason: tester
            .widgetList<Text>(find.byType(Text))
            .map((text) => text.data)
            .join(' | '),
      );
      expect(ui.cancelled, isEmpty);
      expect(ui.releases, hasLength(1));
      await ui.remove();
    },
  );

  testWidgets(
    'config chooser offers KBI with no imported key and sends current alias binding',
    (tester) async {
      final ui = _Ui(tester);
      await ui.open();
      await ui.tap('ssh-manage-identities');
      await ui.tap('ssh-config-host-fixture');
      final statusCount = ui.arguments('ssh.passwordStatus').length;
      await ui.tap('ssh-authentication');
      await ui.tap('ssh-auth-keyboard');
      await ui.session();
      await ui.tap('ssh-connect');
      expect(ui.connections.single.method, 'ssh.configKeyboardConnect');
      expect(ui.connections.single.arguments, {
        'requestId': ui.connectId,
        'alias': 'fixture',
        'binding': 'a' * 64,
        'username': '',
        'columns': 80,
        'rows': 24,
      });
      expect(find.byKey(const Key('ssh-select-private-key')), findsNothing);
      expect(ui.arguments('ssh.passwordStatus'), hasLength(statusCount));
      ui.complete();
      await ui.pump();
      await ui.close();
      await ui.remove();
    },
  );

  for (final configured in [false, true]) {
    testWidgets(
      '${configured ? 'config' : 'manual'} KBI reconnect prepares current data and never automatically submits',
      (tester) async {
        final ui = _Ui(tester);
        await ui.open(
          reconnect: SshReconnectTarget(
            mode: configured
                ? SshReconnectMode.configKeyboard
                : SshReconnectMode.keyboard,
            host: 'server.invalid',
            port: 2222,
            username: 'alice',
            configAlias: configured ? 'fixture' : null,
          ),
        );
        await ui.session();
        expect(ui.connections, isEmpty);
        expect(ui.arguments('ssh.passwordStatus'), isEmpty);
        expect(find.byKey(const Key('ssh-password')), findsNothing);
        await ui.tap('ssh-connect');
        expect(
          ui.connections.single.method,
          configured ? 'ssh.configKeyboardConnect' : 'ssh.keyboardConnect',
        );
        ui.complete();
        await ui.pump();
        await ui.close();
        await ui.remove();
      },
    );
  }

  for (final removal in ['back', 'removeRoute', 'destroyNavigator']) {
    for (final lateSuccess in [false, true]) {
      testWidgets(
        '$removal retains protection through original KBI ${lateSuccess ? 'late success' : 'cancelled'} receipt',
        (tester) async {
          final ui = _Ui(tester);
          await ui.open();
          await ui.chooseKeyboard();
          await ui.tap('ssh-connect');
          await ui.event(_challenge(ui.connectId));
          final controller = tester
              .widget<TextFormField>(
                find.byKey(const Key('ssh-keyboard-answer-1')),
              )
              .controller!;
          await tester.enterText(
            find.byKey(const Key('ssh-keyboard-answer-1')),
            'must disappear',
          );
          if (removal == 'back') {
            await tester.binding.handlePopRoute();
          } else if (removal == 'removeRoute') {
            final context = tester.element(find.byType(SshPasswordConnectPage));
            Navigator.of(context).removeRoute(ModalRoute.of(context)!);
          } else {
            await tester.pumpWidget(const SizedBox.shrink());
          }
          await ui.pump();
          expect(find.byType(SshKeyboardChallengeForm), findsNothing);
          // A disposed controller cannot be read; the visible widget and all answers are gone.
          if (removal == 'back') expect(controller.text, isEmpty);
          expect(ui.cancelled, contains(ui.connectId));
          expect(ui.answers, isEmpty);
          expect(ui.releases, isEmpty);
          await ui.event(_challenge(ui.connectId, round: 2));
          expect(find.byType(SshKeyboardChallengeForm), findsNothing);
          ui.complete(success: lateSuccess);
          await ui.pump();
          expect(ui.releases, hasLength(1));
          expect(
            ui.cancelled.where((id) => id == ui.connectId).length,
            greaterThanOrEqualTo(2),
          );
          expect(tester.takeException(), isNull);
          await ui.remove();
        },
      );
    }
  }

  for (final cause in [
    'background',
    'locked',
    'stream',
    'expired',
    'oldRound',
  ]) {
    testWidgets('$cause clears KBI fields and cannot revive a late challenge', (
      tester,
    ) async {
      final ui = _Ui(tester);
      await ui.open();
      await ui.chooseKeyboard();
      await ui.tap('ssh-connect');
      final id = ui.connectId;
      await ui.event(
        _challenge(id, remainingMillis: cause == 'expired' ? 500 : 60000),
      );
      await tester.enterText(
        find.byKey(const Key('ssh-keyboard-answer-1')),
        'transient',
      );
      switch (cause) {
        case 'background':
          tester.binding.handleAppLifecycleStateChanged(
            AppLifecycleState.inactive,
          );
        case 'locked':
          await ui.session(unlocked: false);
        case 'stream':
          await ui.messenger.handlePlatformMessage(
            'ctos/ssh',
            const StandardMethodCodec().encodeErrorEnvelope(code: 'FAILED'),
            null,
          );
        case 'expired':
          await tester.pump(const Duration(milliseconds: 501));
        case 'oldRound':
          await ui.event({
            'type': 'keyboardChallengeDismissed',
            'requestId': id,
            'ticketId': 'ticket_1',
          });
          await ui.event(_challenge(id));
      }
      await ui.pump();
      expect(find.byType(SshKeyboardChallengeForm), findsNothing);
      expect(ui.cancelled, contains(id));
      if (cause == 'background')
        tester.binding.handleAppLifecycleStateChanged(
          AppLifecycleState.resumed,
        );
      await ui.event(_challenge(id, round: 2));
      expect(find.byType(SshKeyboardChallengeForm), findsNothing);
      expect(ui.answers, isEmpty);
      ui.complete();
      await ui.pump();
      await ui.close();
      await ui.remove();
    });
  }

  testWidgets(
    'echo and masked form accepts empty answers and keeps all prompts in one round',
    (tester) async {
      List<String>? sent;
      await tester.pumpWidget(
        MaterialApp(
          theme: CtosTheme.dark(),
          home: Scaffold(
            body: ListView(
              children: [
                SshKeyboardChallengeForm(
                  ticket:
                      SshEvent.parse(_challenge('attempt'))
                          as SshKeyboardChallenge,
                  target: 'alice@server.invalid:2222',
                  onSubmit: (answers) async {
                    sent = List.of(answers);
                    return true;
                  },
                  onCancel: () {},
                  onExpired: () {},
                ),
              ],
            ),
          ),
        ),
      );
      await tester.ensureVisible(find.byKey(const Key('ssh-keyboard-submit')));
      await tester.tap(find.byKey(const Key('ssh-keyboard-submit')));
      await tester.pump();
      expect(sent, ['', '']);
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
    },
  );
}

Map<String, Object> _challenge(
  String id, {
  int round = 1,
  int remainingMillis = 60000,
  List<Map<String, Object>>? prompts,
}) => {
  'type': 'keyboardChallenge',
  'requestId': id,
  'ticketId': 'ticket_$round',
  'binding': 'b' * 64,
  'round': round,
  'name': '服务器身份验证',
  'instruction': '请输入账号\n然后回答服务器问题。',
  'prompts':
      prompts ??
      [
        {'text': '账号：', 'echo': true},
        {'text': '一次性回答：', 'echo': false},
      ],
  'remainingMillis': remainingMillis,
};

class _Ui {
  _Ui(this.tester);
  final WidgetTester tester;
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final calls = <MethodCall>[];
  final pending = <Completer<Object?>>[];
  int sequence = 0;
  bool withKey = false;
  String libraryBinding = 'a' * 64;
  String libraryHost = 'server.invalid';
  String libraryUsername = 'alice';
  Completer<Map<String, Object>>? keyboardResponse;
  late SshPasswordApi api;

  List<Map<dynamic, dynamic>> arguments(String method) => calls
      .where((call) => call.method == method)
      .map((call) => call.arguments as Map)
      .toList();
  List<MethodCall> get connections => calls
      .where(
        (call) => const {
          'ssh.keyboardConnect',
          'ssh.configKeyboardConnect',
          'ssh.passwordConnect',
          'ssh.keyConnect',
          'ssh.configConnect',
          'ssh.configPasswordConnect',
        }.contains(call.method),
      )
      .toList();
  String get connectId =>
      (connections.last.arguments as Map)['requestId'] as String;
  List<Map<dynamic, dynamic>> get answers => arguments('ssh.keyboardResponse');
  List<String> get cancelled => arguments(
    'ssh.cancel',
  ).map((call) => call['requestId'] as String).toList();
  List<Map<dynamic, dynamic>> get releases => arguments(
    'secrets.protect',
  ).where((call) => call['action'] == 'release').toList();

  Future<Object?> handle(MethodCall call) async {
    final args = call.arguments;
    calls.add(
      MethodCall(
        call.method,
        args is Map
            ? {
                ...args,
                if (args['answers'] is List)
                  'answers': List.of(args['answers'] as List),
              }
            : args,
      ),
    );
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
        return (args as Map)['action'] == 'release'
            ? null
            : {'owner': 'protected_${++sequence}'};
      case 'ssh.defaults':
        return {
          'host': 'server.invalid',
          'port': 2222,
          'username': 'alice',
          'savedPassword': true,
        };
      case 'ssh.library':
        return {
          'keys': <Object>[
            if (withKey)
              {
                'id': 'key_a',
                'label': 'Fixture key',
                'path': '.ssh/id_ed25519',
                'algorithm': 'ssh-ed25519',
                'fingerprint': 'SHA256:${'A' * 43}',
              },
          ],
          'hasConfig': true,
          'hosts': [
            {
              'alias': 'fixture',
              'binding': libraryBinding,
              'host': libraryHost,
              'port': 2222,
              'username': libraryUsername,
              'keyIds': <String>[if (withKey) 'key_a'],
              'identityHints': ['/computer/key-not-imported'],
            },
          ],
        };
      case 'ssh.keyboardConnect':
      case 'ssh.configKeyboardConnect':
      case 'ssh.passwordConnect':
      case 'ssh.keyConnect':
      case 'ssh.configConnect':
      case 'ssh.configPasswordConnect':
        final completion = Completer<Object?>();
        pending.add(completion);
        return completion.future;
      case 'ssh.keyboardResponse':
        return keyboardResponse?.future ?? {'accepted': true};
      case 'ssh.passwordStatus':
        return {'saved': true};
      case 'ssh.cancel':
        return {'cancelled': true};
      default:
        throw PlatformException(code: 'UNEXPECTED');
    }
  }

  Future<void> open({
    SshReconnectTarget? reconnect,
    double scale = 1,
    double keyboard = 0,
    bool connectOnOpen = true,
  }) async {
    if (tester.binding.lifecycleState != AppLifecycleState.resumed) {
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    }
    api = SshPasswordApi(requestIdFactory: () => 'request_${++sequence}');
    messenger.setMockMethodCallHandler(
      const MethodChannel('ctos/native'),
      handle,
    );
    messenger.setMockMethodCallHandler(
      const MethodChannel('ctos/ssh'),
      (_) async => null,
    );
    messenger.setMockMethodCallHandler(
      const MethodChannel('ctos/secrets'),
      (_) async => null,
    );
    addTearDown(() {
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      messenger.setMockMethodCallHandler(
        const MethodChannel('ctos/native'),
        null,
      );
      messenger.setMockMethodCallHandler(const MethodChannel('ctos/ssh'), null);
      messenger.setMockMethodCallHandler(
        const MethodChannel('ctos/secrets'),
        null,
      );
    });
    await tester.pumpWidget(
      MaterialApp(
        theme: CtosTheme.dark(),
        builder: (context, child) => RepaintBoundary(
          key: const Key('kbi-visual-root'),
          child: MediaQuery(
            data: MediaQuery.of(context).copyWith(
              textScaler: TextScaler.linear(scale),
              viewInsets: EdgeInsets.only(bottom: keyboard),
            ),
            child: child!,
          ),
        ),
        home: Scaffold(
          body: Builder(
            builder: (context) => TextButton(
              onPressed: () => showPasswordSshConnect(
                context,
                api: api,
                columns: 80,
                rows: 24,
                initialAuthentication: SshInitialAuthentication.password,
                reconnect: reconnect,
                connectOnOpen: connectOnOpen,
              ),
              child: const Text('Open SSH'),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('Open SSH'));
    await pump();
  }

  Future<void> pump() async {
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pump();
  }

  Future<void> tap(String key) async {
    if (key == 'ssh-manage-identities' &&
        find.byKey(Key(key)).evaluate().isEmpty) {
      final management = find.byKey(const Key('ssh-connection-management'));
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
      await tester.tap(management);
      await pump();
    }
    final finder = find.byKey(Key(key));
    await tester.pump(const Duration(milliseconds: 300));
    await tester.ensureVisible(finder);
    await tester.pump(const Duration(milliseconds: 300));
    await tester.tap(finder);
    await pump();
  }

  Future<void> chooseKeyboard() async {
    await tap('ssh-authentication');
    await tap('ssh-auth-keyboard');
    await session();
  }

  Future<void> session({bool unlocked = true}) async {
    await messenger.handlePlatformMessage(
      'ctos/secrets',
      const StandardMethodCodec().encodeSuccessEnvelope({
        'epoch': 1,
        'unlocked': unlocked,
        'foreground': true,
      }),
      null,
    );
    await pump();
  }

  Future<void> event(Map<String, Object> value) async {
    await messenger.handlePlatformMessage(
      'ctos/ssh',
      const StandardMethodCodec().encodeSuccessEnvelope(value),
      null,
    );
    await pump();
  }

  void complete({bool success = false}) {
    if (pending.last.isCompleted) return;
    if (success)
      pending.last.complete({'requestId': connectId, 'status': 'connected'});
    else
      pending.last.completeError(PlatformException(code: 'SSH_CANCELLED'));
  }

  Future<void> close() async {
    if (find.byKey(const Key('ssh-cancel')).evaluate().isNotEmpty)
      await tap('ssh-cancel');
  }

  Future<void> remove() async {
    for (final operation in pending) {
      if (!operation.isCompleted)
        operation.completeError(PlatformException(code: 'SSH_CANCELLED'));
    }
    await pump();
    await tester.pumpWidget(const SizedBox.shrink());
    await pump();
  }
}

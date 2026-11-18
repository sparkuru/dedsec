import 'package:ctos/ssh/api.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final request = <String, Object>{
    'type': 'passwordRequest',
    'requestId': 'attempt_1',
    'ticketId': 'ticket_1',
    'binding': 'a' * 64,
    'remainingMillis': 25000,
  };

  test('password prompts are strictly bounded and distinct from KBI', () {
    final parsed = SshEvent.parse(request) as SshPasswordRequest;
    expect(parsed.ticketId, 'ticket_1');
    expect(parsed.remainingMillis, 25000);
    expect(parsed.keyPassphrase, isFalse);
    expect(parsed.savedAvailable, isFalse);
    expect(
      (SshEvent.parse({...request, 'type': 'keyPassphraseRequest'})
              as SshPasswordRequest)
          .keyPassphrase,
      isTrue,
    );
    for (final bad in <Map<String, Object>>[
      {...request, 'extra': 'unexpected'},
      {...request, 'binding': 'bad'},
      {...request, 'remainingMillis': -1},
      {...request, 'remainingMillis': 60001},
      {...request, 'remainingMillis': 'soon'},
      {...request, 'savedAvailable': 'true'},
    ]) {
      expect(() => SshEvent.parse(bad), throwsFormatException);
    }
    expect(
      SshEvent.parse({
        'type': 'passwordRequestDismissed',
        'requestId': 'attempt_1',
        'ticketId': 'ticket_1',
      }),
      isA<SshPasswordRequestDismissed>(),
    );
  });

  test(
    'response is sent only to the matching ticket as bounded bytes',
    () async {
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(const MethodChannel('ctos/native'), (
        call,
      ) async {
        expect(call.method, 'ssh.passwordResponse');
        final value = call.arguments as Map;
        expect(value['requestId'], 'attempt_1');
        expect(value['ticketId'], 'ticket_1');
        expect(value['binding'], 'a' * 64);
        expect(value['password'], isA<Uint8List>());
        expect(value['password'], [115, 121, 110, 116, 104, 101, 116, 105, 99]);
        return {'accepted': true};
      });
      addTearDown(
        () => messenger.setMockMethodCallHandler(
          const MethodChannel('ctos/native'),
          null,
        ),
      );
      final ticket = SshEvent.parse(request) as SshPasswordRequest;
      expect(
        await SshPasswordApi().passwordResponse(ticket, 'synthetic'),
        isTrue,
      );
      expect(
        () => SshPasswordApi().passwordResponse(ticket, ''),
        throwsFormatException,
      );
    },
  );

  test(
    'saved reuse carries no plaintext and saving requires typed input',
    () async {
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      const channel = MethodChannel('ctos/native');
      final calls = <Map>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        expect(call.method, 'ssh.passwordResponse');
        calls.add(call.arguments as Map);
        return {'accepted': true};
      });
      addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
      final api = SshPasswordApi();
      final ticket =
          SshEvent.parse({
                ...request,
                'type': 'keyPassphraseRequest',
                'savedAvailable': true,
              })
              as SshPasswordRequest;
      expect(ticket.savedAvailable, isTrue);
      expect(await api.passwordResponse(ticket, '', useSaved: true), isTrue);
      expect(calls.single['password'], isEmpty);
      expect(calls.single['useSaved'], isTrue);
      expect(calls.single['save'], isFalse);
      expect(
        await api.passwordResponse(ticket, 'synthetic', save: true),
        isTrue,
      );
      expect(calls.last['password'], 'synthetic'.codeUnits);
      expect(calls.last['save'], isTrue);
      expect(calls.last['useSaved'], isFalse);
      expect(
        () => api.passwordResponse(ticket, 'synthetic', useSaved: true),
        throwsFormatException,
      );
      expect(
        () => api.passwordResponse(ticket, '', useSaved: true, save: true),
        throwsFormatException,
      );
      expect(
        () => api.passwordResponse(ticket, '', save: true),
        throwsFormatException,
      );
      expect(
        () => api.passwordResponse(
          SshEvent.parse(request) as SshPasswordRequest,
          '',
          useSaved: true,
        ),
        throwsFormatException,
      );
      expect(calls, hasLength(2));
    },
  );

  test(
    'automatic requests and reconnects carry only target and optional key',
    () async {
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
      await api.automaticConnect(
        requestId: 'manual',
        host: 'server.invalid',
        port: 22,
        username: 'alice',
        columns: 80,
        rows: 24,
      );
      await api.configAutomaticConnect(
        requestId: 'configured',
        alias: 'server',
        binding: 'a' * 64,
        username: '',
        keyId: 'key_1',
        columns: 80,
        rows: 24,
      );
      expect(calls.map((call) => call.method), [
        'ssh.autoConnect',
        'ssh.configAutoConnect',
      ]);
      expect((calls.first.arguments as Map)['keyId'], '');
      expect((calls.last.arguments as Map)['keyId'], 'key_1');
      for (final call in calls) {
        final args = call.arguments as Map;
        expect(args.containsKey('password'), isFalse);
        expect(args.containsKey('passphrase'), isFalse);
      }
      for (final configured in [false, true])
        for (final key in [false, true]) {
          final target = SshReconnectTarget.parse({
            'mode': configured ? 'configAuto' : 'auto',
            'host': 'server.invalid',
            'port': 22,
            'username': 'alice',
            if (configured) 'configAlias': 'server',
            if (key) 'keyId': 'key_1',
          });
          expect(
            target.mode,
            configured ? SshReconnectMode.configAuto : SshReconnectMode.auto,
          );
          expect(target.keyId, key ? 'key_1' : null);
        }
      expect(
        () => SshReconnectTarget.parse({
          'mode': 'auto',
          'host': 'server.invalid',
          'port': 22,
          'username': 'alice',
          'continueKeyboardInteractive': true,
        }),
        throwsFormatException,
      );
    },
  );
}

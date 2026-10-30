import 'dart:typed_data';

import 'package:ctos/terminal_events.dart';
import 'package:ctos/ssh/api.dart';
import 'package:flutter_test/flutter_test.dart';

Map<String, Object> state(int generation, String state) => {
  'type': 'state',
  'generation': generation,
  'state': state,
  'mode': 'SSH · synthetic',
  'remote': true,
};

Map<String, Object> data(int generation, List<int> bytes) => {
  'type': 'data',
  'generation': generation,
  'bytes': Uint8List.fromList(bytes),
};

Map<String, Object> snapshot(
  int generation,
  List<int> bytes, {
  String status = 'running',
  bool truncated = false,
}) => {
  ...state(generation, status),
  'type': 'snapshot',
  'bytes': Uint8List.fromList(bytes),
  'truncated': truncated,
};

void main() {
  test('lock close carries only validated public reconnect selections', () {
    final target = <String, Object>{
      'mode': 'config',
      'host': 'server.invalid',
      'port': 2222,
      'username': 'alice',
      'keyId': 'key_a',
      'configAlias': 'fixture',
    };
    final closed = {
      ...snapshot(7, [], status: 'closed'),
      'reason': 'SSH_SCREEN_LOCKED',
      'reconnect': target,
    };
    final parsed = TerminalEvent.parse(closed) as TerminalSnapshotEvent;
    expect(parsed.state.reconnect!.mode, SshReconnectMode.config);
    expect(parsed.state.reconnect!.keyId, 'key_a');
    expect(parsed.state.reconnect!.configAlias, 'fixture');
    final decoder = TerminalEventDecoder();
    expect(decoder.accept(closed)!.state!.reconnect!.host, 'server.invalid');
    expect(decoder.running, isFalse);
    expect(decoder.accept(state(7, 'running')), isNull);
    expect(decoder.accept(data(7, [65])), isNull);
    decoder.accept(state(8, 'running'));
    expect(decoder.accept(closed), isNull);
    expect(decoder.running, isTrue);
  });

  test('malformed optional reconnect hints never hide a valid closure', () {
    final target = <String, Object>{
      'mode': 'key',
      'host': 'server.invalid',
      'port': 22,
      'username': 'alice',
      'keyId': 'key_a',
    };
    for (final malformed in <Object?>[
      null,
      {...target, 'password': 'private'},
      {...target, 'binding': 'old'},
      {...target, 'port': 0},
      {...target, 'username': 'bad\nuser'},
      {...target, 'keyId': 'bad/id'},
      {...target}..remove('keyId'),
      {...target, 'mode': 'config'},
      {...target, 'mode': 'password'},
      {...target, 'configAlias': 'wrong for key'},
    ]) {
      final decoder = TerminalEventDecoder()..accept(state(1, 'running'));
      final update = decoder.accept({
        ...state(1, 'closed'),
        'reason': 'SSH_SCREEN_LOCKED',
        'reconnect': malformed,
      })!;
      expect(update.state!.reconnect, isNull);
      expect(decoder.running, isFalse);
    }
    for (final status in ['failed', 'closed']) {
      final parsed =
          TerminalEvent.parse({
                ...state(2, status),
                'reason': 'SSH_TERMINAL_IO',
                'reconnect': target,
              })
              as TerminalStateEvent;
      expect(parsed.reconnect, isNull);
    }
  });

  test('password retry target rejects identity or credential authority', () {
    final password = {
      'mode': 'password',
      'host': 'server.invalid',
      'port': 22,
      'username': 'alice',
    };
    expect(SshReconnectTarget.parse(password).mode, SshReconnectMode.password);
    for (final extra in [
      'keyId',
      'configAlias',
      'requestId',
      'password',
      'bytes',
    ]) {
      expect(
        () => SshReconnectTarget.parse({...password, extra: 'old'}),
        throwsFormatException,
      );
    }
  });

  test(
    'screen lock failure fences immediately and gains retry only after confirmed close',
    () {
      final decoder = TerminalEventDecoder()..accept(state(3, 'running'));
      final early = decoder.accept({
        ...state(3, 'failed'),
        'reason': 'SSH_SCREEN_LOCKED',
      })!;
      expect(early.state!.reconnect, isNull);
      expect(decoder.running, isFalse);
      expect(decoder.accept(state(3, 'running')), isNull);
      expect(
        decoder.accept({
          ...state(3, 'failed'),
          'reason': 'SSH_TERMINAL_CLEANUP_PENDING',
        }),
        isNull,
      );
      final finalized = {
        ...state(3, 'closed'),
        'reason': 'SSH_SCREEN_LOCKED',
        'reconnect': {
          'mode': 'password',
          'host': 'server.invalid',
          'port': 22,
          'username': 'alice',
        },
      };
      expect(decoder.accept(finalized)!.state!.reconnect, isNotNull);
      expect(decoder.running, isFalse);
      expect(decoder.accept(finalized), isNull);
      expect(decoder.accept(state(3, 'running')), isNull);
      decoder.accept(state(4, 'running'));
      expect(decoder.accept(finalized), isNull);
      expect(decoder.running, isTrue);
      final recreated = TerminalEventDecoder();
      recreated.accept({
        ...snapshot(3, [], status: 'failed'),
        'reason': 'SSH_TERMINAL_CLEANUP_PENDING',
      });
      expect(recreated.accept(finalized)!.state!.reconnect, isNotNull);
      expect(recreated.running, isFalse);
    },
  );

  test('snapshot validates remote ownership, byte bounds and exact fields', () {
    expect(TerminalEvent.parse(snapshot(1, [])), isA<TerminalSnapshotEvent>());
    for (final malformed in [
      {...snapshot(1, []), 'remote': false},
      {...snapshot(1, [])}..remove('bytes'),
      {...snapshot(1, [])}..remove('truncated'),
      {...snapshot(1, []), 'bytes': <int>[]},
      {...snapshot(1, []), 'bytes': Uint8List(256 * 1024 + 1)},
      {...snapshot(1, []), 'truncated': 1},
      {...snapshot(1, []), 'extra': true},
      {'type': 'focus', 'generation': 1, 'extra': true},
    ]) {
      expect(TerminalEvent.parse(malformed), isNull);
    }
  });

  test(
    'snapshot replaces decoder state and continues retained UTF8 suffix',
    () {
      final decoder = TerminalEventDecoder();
      decoder.accept(state(2, 'running'));
      decoder.accept(data(2, [0xe4]));
      final restored = decoder.accept(snapshot(2, [0x61, 0xe4, 0xbd]))!;
      expect(restored.reset, isTrue);
      expect(restored.output, 'a');
      expect(restored.state!.generation, 2);
      expect(decoder.accept(data(2, [0xa0]))!.output, '你');
      final replaced = decoder.accept(snapshot(2, [0x62], truncated: true))!;
      expect(replaced.output, 'b');
      expect(replaced.truncated, isTrue);
      expect(replaced.reset, isTrue);
      expect(decoder.accept(snapshot(1, [0x63])), isNull);
    },
  );

  test('snapshot cannot resurrect a closed or locally retired generation', () {
    final decoder = TerminalEventDecoder();
    decoder.accept(snapshot(4, [0x61], status: 'closed'));
    expect(decoder.running, isFalse);
    expect(decoder.accept(snapshot(4, [0x62])), isNull);
    decoder.accept(snapshot(5, []));
    decoder.retire(5);
    expect(decoder.accept(snapshot(5, [0x63])), isNull);
    expect(decoder.accept(state(5, 'running')), isNull);
    expect(decoder.accept(snapshot(6, []))!.state!.state, 'running');
  });

  test('focus navigates only the exact currently running SSH generation', () {
    final decoder = TerminalEventDecoder();
    Map<String, Object> focus(int generation) => {
      'type': 'focus',
      'generation': generation,
    };
    expect(decoder.accept(focus(1)), isNull);
    decoder.accept({...state(1, 'running'), 'remote': false});
    expect(decoder.accept(focus(1)), isNull);
    expect(decoder.accept(snapshot(1, [])), isNull);
    decoder.accept(snapshot(2, []));
    expect(decoder.accept(focus(1)), isNull);
    expect(decoder.accept(focus(3)), isNull);
    expect(decoder.accept(focus(2))!.focus, isTrue);
    decoder.accept(state(2, 'closed'));
    expect(decoder.accept(focus(2)), isNull);
  });
  test(
    'strict event parsing rejects malformed required and optional fields',
    () {
      for (final malformed in [
        null,
        [1, 2],
        {...state(1, 'running')}..remove('remote'),
        {...state(1, 'running'), 'generation': '1'},
        state(0, 'running'),
        state(1, 'success'),
        {...state(1, 'running'), 'mode': ''},
        {...state(1, 'running'), 'remote': 'true'},
        {...state(1, 'closed'), 'exitStatus': null},
        {...state(1, 'failed'), 'reason': 1},
        {...state(1, 'running'), 'extra': true},
        {
          ...data(1, []),
          'bytes': [1, 2],
        },
        {...data(1, []), 'extra': true},
      ]) {
        expect(TerminalEvent.parse(malformed), isNull, reason: '$malformed');
      }
      final parsed =
          TerminalEvent.parse({
                ...state(1, 'closed'),
                'exitStatus': 0,
                'reason': 'exit',
              })
              as TerminalStateEvent;
      expect(parsed.exitStatus, 0);
      expect(parsed.remote, isTrue);
    },
  );

  test('UTF-8 decoder fences stale data and resets at session closure', () {
    final decoder = TerminalEventDecoder();
    expect(decoder.accept(data(1, [0xe4])), isNull);
    decoder.accept(state(1, 'running'));
    expect(decoder.accept(data(1, [0xe4, 0xbd]))!.output, '');
    expect(decoder.accept(data(1, [0xa0]))!.output, '你');
    expect(decoder.accept(data(1, [0xe4]))!.output, '');
    expect(decoder.accept(state(1, 'closed'))!.output, '�');
    expect(decoder.accept(state(1, 'running')), isNull);
    decoder.accept(state(2, 'running'));
    expect(decoder.accept(data(1, [0xe4, 0xbd])), isNull);
    expect(decoder.accept(data(2, [0x61]))!.output, 'a');
    expect(decoder.accept(state(1, 'failed')), isNull);
    expect(decoder.running, isTrue);
    expect(decoder.retire(1), '');
    expect(decoder.running, isTrue);
    decoder.retire(2);
    expect(decoder.accept(data(2, [0x62])), isNull);
    expect(decoder.accept(state(2, 'running')), isNull);
  });

  test('closed-before-running and invalid events never fabricate success', () {
    final decoder = TerminalEventDecoder();
    decoder.accept(state(4, 'closed'));
    expect(decoder.accept(state(4, 'running')), isNull);
    expect(decoder.accept({...state(5, 'running'), 'remote': null}), isNull);
    expect(decoder.generation, 4);
    expect(decoder.running, isFalse);
    decoder.accept(state(5, 'running'));
    decoder.accept(state(5, 'closed'));
    expect(decoder.running, isFalse);
    expect(decoder.accept(state(5, 'running')), isNull);
  });
}

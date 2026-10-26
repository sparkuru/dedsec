import 'dart:typed_data';

import 'package:ctos/terminal_events.dart';
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

void main() {
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
